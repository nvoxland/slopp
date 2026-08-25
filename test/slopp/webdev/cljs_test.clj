(ns slopp.webdev.cljs-test
  "Tests for the ClojureScript path — the one place slopp's oracle cannot
  reach.

  Everything else here is verified by RUNNING it in the image. Client code
  cannot be: there is no JS runtime in the loop, so the COMPILER stands in as
  the oracle, and these tests exist to hold that substitute honest. They
  check what a compile produces, that a failure anchors to a real form rather
  than to a line number in generated output, where the bytes land, and that
  the loop around it — recompile on write, a mount that actually serves the
  result — behaves.

  They are `^:external` and genuinely slow: each shells a fresh JVM and runs
  a real compile. That cost is the point. A faked compiler would leave the
  only unverifiable layer in slopp verified by something that cannot fail."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.webdev.cljs :as cljs]
            [slopp.store :as store] [slopp.ops :as ops] [slopp.ops.external :as external] [slopp.store.artifacts :as artifacts] [slopp.store.render :as store.render] [clojure.string :as str] [slopp.http.client :as http.client] [slopp.edit.tiers :as tiers]))

(deftest parse-result-extracts-the-marked-edn
  (testing "reads the EDN after the SLOPP-CLJS-RESULT marker, ignoring other output"
    (let [out (str "Compiling client...\n"
                   "WARNING: abs already refers to ...\n"
                   "SLOPP-CLJS-RESULT {:warnings [{:type :undeclared-var :line 2 :ns \"app.widget\" :symbol \"foo\"}] :error nil}\n"
                   "done\n")]
      (is (= {:warnings [{:type :undeclared-var :line 2 :ns "app.widget" :symbol "foo"}]
              :error nil}
             (cljs/parse-result out)))))
  (testing "nil when the marker is absent (the runner JVM crashed before printing)"
    (is (nil? (cljs/parse-result "boom, no marker here\n")))
    (is (nil? (cljs/parse-result "")))))

(deftest anchor-warnings-names-the-owning-form
  (let [st   (store/ingest (store/empty-store) 'app.widget
                           (str "(ns app.widget)\n"
                                "(defn greet [n] (undeclared-thing n))\n"))
        ;; derived: rendering synthesizes the space between forms, so a
        ;; literal line here encodes one renderer version and goes red on the
        ;; next. The compiler reports against render output, so ask it.
        line (->> (str/split-lines (store.render/render-ns st 'app.widget))
                  (keep-indexed (fn [i l]
                                  (when (str/includes? l "undeclared-thing") (inc i))))
                  first)]
    (testing "a finding at a form's line anchors to that form + a snippet"
      (let [[a] (cljs/anchor-warnings
                 st [{:type :undeclared-var :line line :ns "app.widget"
                      :message "Use of undeclared Var app.widget/undeclared-thing"}])]
        (is (= 'app.widget/greet (:form a)) (pr-str a))
        (is (= "(defn greet [n] (undeclared-thing n))" (:at a)))
        (is (= "Use of undeclared Var app.widget/undeclared-thing" (:message a)))))
    (testing "an unresolvable ns/line keeps the message but carries no form anchor"
      (let [[a] (cljs/anchor-warnings st [{:type :x :line 99 :ns "nope.gone" :message "m"}])]
        (is (nil? (:form a)))
        (is (= "m" (:message a)))))))

(deftest ^:external compiles-a-clean-cljs-namespace-to-a-served-blob
  (let [sess (external/open!)]
    (try
      (ops/deps-add! sess 'org.clojure/clojurescript {:mvn/version "1.11.132"}
                     :client true :prompt "the cljs compiler")
      (ops/module-platform! sess "tc.client" :cljs :prompt "browser code")
      (ops/ingest! sess 'tc.client
                   (str "(ns tc.client)\n"
                        "(defn greet [n] (str \"Hi \" n))\n"))
      (let [r (cljs/compile-client! sess :output "public/tc.js")]
        (testing "the client namespace compiles to a served JS blob (no Node)"
          (is (= 1 (:compiled r)) (pr-str r))
          (is (nil? (:error r)) (pr-str r))
          (is (pos? (or (:bytes r) 0)) (pr-str r))
          ;; derived, so it lands in :artifacts — sha and recipe in the store, bytes
          ;; on disk. The old assertion looked in :files, where a 2MB bundle used
          ;; to sit inline in a delta on every compile.
          (let [entry (get-in @sess [:store :artifacts "public/tc.js"])]
            (is (string? (:sha entry)) (pr-str entry))
            (is (= {:kind :build :tool "compile_client"} (:recipe entry)))
            (is (nil? (get-in @sess [:store :files "public/tc.js"]))
                "and NOT on the files manifest — one path, one manifest")
            (is (.exists (artifacts/cache-file (:dir @sess) (:sha entry)))
                "the bytes are on disk under their sha")))
        (testing "recompiling RECLAIMS the bundle it supersedes"
          (let [old (get-in @sess [:store :artifacts "public/tc.js" :sha])]
            (ops/add-form! sess 'tc.client "(defn shout [n] (str \"HI \" n))")
            (cljs/compile-client! sess :output "public/tc.js")
            (let [new-sha (get-in @sess [:store :artifacts "public/tc.js" :sha])]
              (is (not= old new-sha) "the bundle really changed")
              (is (not (.exists (artifacts/cache-file (:dir @sess) old)))
                  "the superseded bytes are gone — else the cache grows by a bundle per compile")
              (is (.exists (artifacts/cache-file (:dir @sess) new-sha)))))))
      (finally (ops/close! sess)))))

(deftest ^:external a-cljs-write-lands-unverified-not-refused
  (let [sess (external/open!)]
    (try
      ;; CONTROL — a :jvm namespace: js/* is genuinely unresolvable on the JVM,
      ;; so the write is REFUSED (the oracle cannot load it). This is the
      ;; behaviour that must stay for ordinary Clojure.
      (ops/ingest! sess 'wp.server "(ns wp.server)\n(defn ok [] 1)\n")
      (is (:error (ops/add-form! sess 'wp.server "(defn boom [] (js/alert \"hi\"))"))
          "a js/* form in a :jvm ns fails to load — refused")
      ;; a :cljs namespace: the SAME form LANDS, its verification deferred to
      ;; the cljs compiler (compile_client), reported :unverified with a reason.
      (ops/module-platform! sess "wp.client" :cljs :prompt "browser code")
      (ops/ingest! sess 'wp.client "(ns wp.client)\n")
      (let [r (ops/add-form! sess 'wp.client "(defn boom [] (js/alert \"hi\"))"
                             :prompt "client click handler")]
        (is (nil? (:error r)) (pr-str r))
        (is (some? (:delta r)) (pr-str r))
        (is (some? (store/form-named (:store @sess) 'wp.client 'boom))
            "the js/* form is really in the store")
        (is (= :unverified (:status (:test r))) (pr-str (:test r)))
        (is (= :cljs-deferred-to-compile (:reason (:test r))) (pr-str (:test r))))
      ;; ingest! of a :cljs ns whose body ALREADY uses js/* also lands
      (ops/module-platform! sess "wp.widget" :cljs :prompt "browser code")
      (let [r (ops/ingest! sess 'wp.widget
                           "(ns wp.widget)\n(defn go [] (js/console.log \"x\"))\n")]
        (is (nil? (:error r)) (pr-str r))
        (is (= :cljs-deferred-to-compile (:reason (:test r))) (pr-str (:test r))))
      (finally (ops/close! sess)))))

(deftest ^:external ns-create-with-a-platform-is-born-there
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'wc.client :source "(ns wc.client)\n"
                      :platform :cljs :prompt "browser code")
      (testing "the platform is declared at creation, at the namespace grain"
        (is (= :cljs (store/platform-for (:store @sess) 'wc.client))))
      (testing "born :cljs, a js/* form lands straight away — no separate decl"
        (let [r (ops/add-form! sess 'wc.client "(defn boom [] (js/alert \"hi\"))")]
          (is (nil? (:error r)) (pr-str r))
          (is (= :cljs-deferred-to-compile (:reason (:test r))) (pr-str (:test r)))))
      (finally (ops/close! sess)))))

(deftest ^:external auto-compile-recompiles-the-client-bundle-on-write
  (let [sess (external/open!)]
    (try
      (ops/deps-add! sess 'org.clojure/clojurescript {:mvn/version "1.11.132"}
                     :client true :prompt "the cljs compiler")
      (ops/module-platform! sess "ac.client" :cljs :prompt "browser code")
      (ops/ingest! sess 'ac.client "(ns ac.client)\n")
      (testing "auto-compile OFF (default): a client write does NOT recompile"
        (let [r (ops/add-form! sess 'ac.client "(defn a [] (js/alert \"a\"))")]
          (is (nil? (:client-recompiling r)) (pr-str r))
          (is (nil? (get-in @sess [:store :artifacts "public/cljs/main.js"]))
              "no bundle written yet")))
      (testing "auto-compile ON: a client write schedules an ASYNC recompile"
        (ops/config-file! sess "client" :key "auto-compile" :value "true"
                          :prompt "dev loop")
        (let [r (ops/add-form! sess 'ac.client "(defn b [] (js/alert \"b\"))")]
          (is (true? (:client-recompiling r)) (pr-str r))
          ;; async: the background compile registers the artifact shortly after.
          ;; The bundle is DERIVED now, so it lands in :artifacts as a sha and a
          ;; recipe — polling :files would wait out the full timeout forever.
          (let [entry (loop [n 0]
                        (or (get-in @sess [:store :artifacts "public/cljs/main.js"])
                            (when (< n 120)
                              (Thread/sleep 500)
                              (recur (inc n)))))]
            (is (string? (:sha entry)) "fresh bundle registered within timeout")
            (is (.exists (artifacts/cache-file (:dir @sess) (:sha entry)))
                "and its bytes are in the cache"))))
      (finally (ops/close! sess)))))

(deftest ^:external edit-rename-handles-a-cljs-form
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'rf.client
                      :source "(ns rf.client)\n(defn boom [] (js/alert \"hi\"))\n"
                      :platform :cljs :prompt "browser code")
      (testing "edit_rename renames a :cljs form (js/* — never loaded on the JVM)"
        (let [r (ops/rename! sess 'rf.client 'boom 'kaboom)]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'rf.client 'kaboom))
              "renamed form is present")
          (is (nil? (store/form-named (:store @sess) 'rf.client 'boom))
              "old name is gone")))
      (finally (ops/close! sess)))))

(deftest ^:external edit-move-forms-handles-cljs-forms
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'mvc.a
                      :source "(ns mvc.a)\n(defn ping [] (js/alert \"a\"))\n"
                      :platform :cljs :prompt "browser code")
      (ops/create-ns! sess 'mvc.b
                      :source "(ns mvc.b)\n(defn other [] (js/console.log \"b\"))\n"
                      :platform :cljs :prompt "browser code")
      (testing "edit_move_forms moves a :cljs form between :cljs namespaces"
        (let [r (ops/move-forms! sess 'mvc.a '[ping] 'mvc.b)]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'mvc.b 'ping))
              "moved into the target")
          (is (nil? (store/form-named (:store @sess) 'mvc.a 'ping))
              "gone from the source")))
      (finally (ops/close! sess)))))

(deftest client-wrapper-specs-resolves-endpoints-and-schemas
  (let [st (-> (store/empty-store)
               (store/ingest 'shop.contracts
                             "(ns shop.contracts)\n\n(def order [:map [:item :string] [:qty :int]])\n"))
        st (first (store/record-module-platform st "shop.contracts" :cljc))
        st (store/ingest st 'shop.api
                         (str "(ns shop.api)\n\n"
                              "(defn ^{:http/method :post :rest/path \"/api/orders\""
                              " :rest/request shop.contracts/order :rest/response shop.contracts/order}"
                              " create-order [req] req)\n\n"
                              "(defn ^{:http/method :get :rest/path \"/api/orders/:id\""
                              " :rest/response shop.contracts/order} get-order [req] req)\n"))
        {:keys [wrappers problems]} (cljs/client-wrapper-specs st)]
    (testing "one wrapper per endpoint; mutating verbs get a ! suffix"
      (is (= '[create-order! get-order] (mapv :fn-name wrappers)))
      (is (= [:post :get] (mapv :method wrappers))))
    (testing "an endpoint already named with ! does not get a second one"
      ;; slopp's own store hit this: POST /call is `call-endpoint!` and
      ;; generated `call-endpoint!!`. Naming a mutating endpoint with a bang
      ;; is the DIALECT'S OWN convention, so the generator meeting that
      ;; convention with a double bang is it fighting the house style.
      (let [st2 (store/ingest st 'shop.api2
                              (str "(ns shop.api2)\n\n"
                                   "(defn ^{:http/method :post :rest/path \"/api/pay\""
                                   " :rest/request shop.contracts/order"
                                   " :rest/response shop.contracts/order}"
                                   " pay! [req] req)\n"))
            names (mapv :fn-name (:wrappers (cljs/client-wrapper-specs st2)))]
        (is (some #{'pay!} names) (pr-str names))
        (is (not (some #{'pay!!} names)) (pr-str names))))
    (testing "schema refs resolve to fully-qualified vars in the :cljc contracts ns"
      (is (= 'shop.contracts/order (get-in (first wrappers) [:request :sym])))
      (is (= 'shop.contracts/order (get-in (first wrappers) [:response :sym])))
      (is (= :none (get-in (second wrappers) [:request :kind]))
          "this GET DECLARES no :rest/request — :none because there is none"))
    (testing "a GET that DOES declare a request keeps it"
      ;; The assertion above had the right value for the wrong reason, and the
      ;; difference is what shipped a broken client: with no declaration on the
      ;; fixture, `:none` was ambiguous between "the planner drops it" and
      ;; "there was none", and the planner DID drop it —
      ;; `(if (#{:post :put :patch} method) req {:kind :none})`.
      ;;
      ;; So `?depth=` on /api/form/:id answered on the wire and the generated
      ;; wrapper had nowhere to put it, which pushes a consumer toward the
      ;; hand-rolled fetch `direct-http` refuses.
      (let [st3 (store/ingest st 'shop.api3
                              (str "(ns shop.api3)\n\n"
                                   "(defn ^{:http/method :get :rest/path \"/api/search\""
                                   " :rest/request shop.contracts/order"
                                   " :rest/response shop.contracts/order}"
                                   " search [req] req)\n"))
            spec (first (filter #(= 'search (:fn-name %))
                                (:wrappers (cljs/client-wrapper-specs st3))))]
        (is (some? spec))
        (is (= 'shop.contracts/order (get-in spec [:request :sym]))
            "a GET sends its request as a QUERY STRING — how it travels follows
             from the method, and dropping the schema removes the caller's only
             way to say anything the path does not carry")))
    (testing "the source endpoint rides each spec as provenance"
      (is (= 'shop.api/create-order (:endpoint (first wrappers)))))
(testing "the declared KEYS ride the spec, read out of the var's literal"
      ;; the half that needs the store. contract->plan is handed schemas as
      ;; VALUES; here `:rest/request` is a symbol, so the keys are behind a
      ;; name and generation has to look them up — without which the guard
      ;; would exist only for stores consuming somebody else's API.
      (is (= #{:item :qty} (:request-keys (first wrappers)))
          (pr-str (first wrappers)))
      (is (nil? (:request-keys (second wrappers)))
          "an endpoint declaring no request has no keys to enumerate"))
    (testing "a clean fixture yields no problems"
      (is (empty? problems) (pr-str problems)))))

(deftest client-wrapper-specs-flags-non-cljc-schemas
  (let [st (-> (store/empty-store)
               (store/ingest 'shop.contracts
                             "(ns shop.contracts)\n\n(def order [:map [:item :string]])\n")
               ;; platform LEFT :jvm — a jvm-only schema cannot ship to the client
               (store/ingest 'shop.api
                             (str "(ns shop.api)\n\n"
                                  "(defn ^{:http/method :post :rest/path \"/api/orders\""
                                  " :rest/request shop.contracts/order :rest/response shop.contracts/order}"
                                  " create-order [req] req)\n")))
        {:keys [wrappers problems]} (cljs/client-wrapper-specs st)]
    (testing "an endpoint whose schema ns is not :cljc becomes a problem; its wrapper is skipped"
      (is (empty? wrappers) (pr-str wrappers))
      (is (= 1 (count problems)) (pr-str problems))
      (is (= :not-cljc (:issue (first problems))))
      (is (= 'shop.contracts/order (:schema-ref (first problems)))))))

(deftest render-client-ns-emits-typed-wrappers
  (let [src (cljs/render-client-ns
             'shop.client.api
             [{:fn-name 'create-order! :method :post :path "/api/orders"
               :endpoint 'shop.api/create-order
               :request  {:kind :var :sym 'shop.contracts/order :ns 'shop.contracts}
               :response {:kind :var :sym 'shop.contracts/order :ns 'shop.contracts}}
              {:fn-name 'get-order :method :get :path "/api/orders/:id"
               :endpoint 'shop.api/get-order
               :request  {:kind :none}
               :response {:kind :var :sym 'shop.contracts/order :ns 'shop.contracts}}])]
    (testing "one ns form plus one defn per wrapper (structural parse is checked end-to-end at ingest)"
      (is (re-find #"\(ns shop\.client\.api" src) src)
      (is (= 2 (count (re-seq #"\(defn \^\{:generated " src)))
          "one WRAPPER per endpoint — counted by its provenance marker, so the
           namespace's own helpers do not read as endpoints")
      (is (= (count (re-seq #"\(" src)) (count (re-seq #"\)" src))) "balanced parens"))
    (testing "the ns requires malli + the schema's contracts ns"
      (is (re-find #"malli\.core" src))
      (is (re-find #"malli\.transform" src))
      (is (re-find #"shop\.contracts" src)))
    (testing "each wrapper carries its ^:generated provenance, is ^:export, and fetches"
      (is (re-find #"\^\{:generated \"shop\.api/create-order\"\}" src))
      (is (re-find #"\^\{:generated \"shop\.api/get-order\"\}" src))
      (is (re-find #":export" src))
      (is (re-find #"js/fetch" src)))
    (testing "request validation on a body verb; response validation both; path param substituted"
      (is (re-find #"m/validate shop\.contracts/order params" src) "request validated out")
      (is (re-find #"m/decode shop\.contracts/order" src) "response decoded in")
      (is (re-find #"\(str \"/api/orders/\" \(:id params\)\)" src) "path param interpolated"))
    (testing "every fetch goes through a BASE the app can set, so a slopp app
              can be served under a path prefix (D-hub part 2). Default \"\"
              is exactly today's behaviour — an app served at the root emits
              the same urls it always did"
      (is (re-find #"\(defonce \^:export base \(atom \"\"\)\)" src)
          "an exported base the mounting app sets once")
      (is (re-find #"\(defn \^:export set-base! \[b\] \(reset! base b\)\)" src)
          "set through a FN, not by reaching into the atom — a defn is the
           surface a generated namespace should offer, and it is also the
           thing clj-kondo resolves across a cljs namespace boundary")
      (is (= 2 (count (re-seq #"js/fetch \(url " src)))
          "EVERY wrapper routes through it — one that did not would 404 under a prefix"))))

(deftest ^:external generate-client-writes-a-protected-cljs-namespace
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopg.contracts
                   "(ns shopg.contracts)\n\n(def order [:map [:item :string] [:qty :int]])\n")
      (ops/module-platform! sess "shopg.contracts" "cljc" :prompt "shared contract")
      (ops/ingest! sess 'shopg.api
                   (str "(ns shopg.api)\n\n"
                        "(defn ^{:http/method :post :rest/path \"/api/orders\""
                        " :rest/request shopg.contracts/order :rest/response shopg.contracts/order}"
                        " create-order \"Create an order.\" [req] req)\n"))
      (let [r (cljs/generate-client! sess :ns 'shopg.client.api)]
        (testing "one wrapper per endpoint, written into a stored :cljs namespace"
          (is (= 'shopg.client.api (:generated r)) (pr-str r))
          (is (= 1 (:endpoints r)))
          (is (= ["create-order!"] (:wrappers r)))
          (is (= :cljs (store/platform-for (:store @sess) 'shopg.client.api)))
          (is (some? (store/form-named (:store @sess) 'shopg.client.api 'create-order!))))
        (testing "no shippable-schema problems for a clean :cljc contract"
          (is (nil? (:problems r)) (pr-str (:problems r)))))
      (testing "the generated form refuses a hand edit — the protection gate is wired end to end"
        (let [r (ops/edit-replace! sess 'shopg.client.api 'create-order!
                                   (str "(defn ^{:generated \"shopg.api/create-order\"} ^:export create-order!"
                                        " \"x\" [params] :hacked)")
                                   :prompt "try to hand-edit the generated wrapper")]
          (is (re-find #"generate_client" (str (:error r))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest client-wrapper-specs-generates-for-APIS-and-nothing-else
  ;; This test was `…-honors-the-client-opt-out`, and its subject is gone.
  ;;
  ;; The original finding was real: an HTML page was a `:http/path` form like
  ;; any other, so generate_client emitted a typed fetch wrapper whose
  ;; (.json resp) can never succeed on HTML. The remedy was `:rest/client
  ;; false` — an opt-out on the page.
  ;;
  ;; A page declares `:http/path` now, so it is excluded by KIND and needs no
  ;; flag. And the flag's other use — excluding a REAL api — turned out not to
  ;; be the producer's call at all: whether to generate a wrapper is the
  ;; generating consumer's question, and the flag reached every other consumer
  ;; too, including out of the published document.
  ;;
  ;; So: generate for every api, for nobody's convenience in particular.
  (let [st (-> (store/empty-store)
               (store/ingest 'pg.api
                             (str "(ns pg.api)\n\n"
                                  "(defn ^{:http/method :get :http/path \"/\" :http/auth :public}"
                                  " home \"The page.\" [r] r)\n\n"
                                  "(defn ^{:http/method :get :rest/path \"/api/x\" :http/auth :public"
                                  " :rest/response :map} data \"Data.\" [r] r)\n\n"
                                  "(defn ^{:http/method :get :rest/path \"/api/y\" :http/auth :public"
                                  " :rest/response :map}"
                                  " also \"Another api.\" [r] r)\n")))
        {:keys [wrappers problems]} (cljs/client-wrapper-specs st)]

    (testing "CONTENT gets no wrapper because it is content — no flag needed"
      (is (not (some #{'home} (map :fn-name wrappers))) (pr-str wrappers)))

    (testing "and every API gets one, whoever does or does not want it"
      (is (= '[data also] (mapv :fn-name wrappers)) (pr-str wrappers)))

    (testing "excluding by kind is not a problem to report — it is a declaration"
      (is (empty? problems) (pr-str problems)))))

(deftest ^:external compiling-a-bundle-says-how-to-serve-it
  ;; The bundle existed in the files manifest from the wave that added it, and
  ;; every page 404'd on it for two more, because serving it needs an
  ;; http.static.* mount and nothing said so. Serving it IS one config line —
  ;; the gap was never capability, it was that the line was undiscoverable.
  ;;
  ;; Discoverability lives in the RESULT, not in a doc someone might read:
  ;; the tool that wrote the file names the mount that would serve it, and
  ;; says nothing once one exists.
  (let [sess (external/open!)]
    (try
      (ops/deps-add! sess 'org.clojure/clojurescript {:mvn/version "1.11.132"}
                     :client true :prompt "the cljs compiler")
      (ops/module-platform! sess "sv.client" :cljs :prompt "browser code")
      (ops/ingest! sess 'sv.client "(ns sv.client)\n(defn greet [n] (str \"Hi \" n))\n")
      (testing "nothing serves the output yet, so the result says how"
        (let [r (cljs/compile-client! sess :output "public/cljs/main.js")]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #"http\.static\." (str (:serve-with r)))
              (str "expected the mount line: " (pr-str r)))
          (is (re-find #"config_file" (str (:serve-with r))) (pr-str r))))
      (testing "once a mount covers it, the hint goes away"
        ;; repeating advice already taken is how a result becomes noise
        (ops/config-file! sess "capabilities" :key "http.static./js"
                          :value "public/cljs" :prompt "serve the bundle")
        (let [r (cljs/compile-client! sess :output "public/cljs/main.js")]
          (is (nil? (:serve-with r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest a-hard-compile-error-anchors-like-a-warning
  ;; Verification stops at the boundary. Analyzer WARNINGS cross the
  ;; cljs compile beautifully — anchor-warnings turns {:ns :line} into a form
  ;; and a snippet, so a cljs warning reads like a clj compile error. A hard
  ;; FAILURE crossed as `failed compiling file:cljs-src/slopp/ui/client/app.cljs`:
  ;; a path into a temp directory the agent never created, with no message, no
  ;; form and no line. It also breaks slopp's own standing invariant that no
  ;; file:line ever reaches the agent.
  ;;
  ;; The information exists — ClojureScript throws ex-data carrying the file
  ;; and line. The runner was dropping it on the floor.
  (let [st (-> (store/empty-store)
               (store/ingest 'app.view
                             "(ns app.view)\n\n(defn a [] 1)\n\n(defn b [] (a))\n"))
        st (first (store/record-module-platform st "app.view" :cljs))]
    (testing "a located error becomes a form anchor, not a path"
      (let [r (cljs/anchor-error st "Wrong number of args passed to defonce"
                                 {:file "cljs-src/app/view.cljs" :line 5})]
        (is (= 'app.view/b (:form r)) (pr-str r))
        (is (= "(defn b [] (a))" (:at r)))
        (is (= "Wrong number of args passed to defonce" (:error r)))
        (testing "and no file path survives into the result"
          (is (not (re-find #"cljs-src|\.cljs" (pr-str r))) (pr-str r)))))
    (testing "the compiler's own message is stripped of paths and line numbers"
      ;; this is what a real one looks like, and it repeats the temp-dir path
      ;; TWICE plus a line number — all of which :form and :at now carry
      ;; properly. Leaving them keeps slopp's no-file:line invariant broken in
      ;; the one field the agent actually reads.
      (let [r (cljs/anchor-error
               st
               (str "failed compiling file:cljs-src/app/view.cljs"
                    " / Wrong number of args (3) passed to: cljs.core/defonce"
                    " at line 5 cljs-src/app/view.cljs")
               {:file "cljs-src/app/view.cljs" :line 5})]
        (is (= 'app.view/b (:form r)))
        (is (not (re-find #"cljs-src|\.cljs|at line" (:error r))) (:error r))
        (testing "while the part that says what is WRONG survives intact"
          (is (re-find #"Wrong number of args \(3\) passed to: cljs\.core/defonce"
                       (:error r))))))
    (testing "an underscore in a path is a hyphen in a namespace"
      ;; the munging is the whole reason this needs a function rather than a
      ;; string replace at the call site
      (let [st2 (-> (store/empty-store)
                    (store/ingest 'app.my-view "(ns app.my-view)\n\n(defn a [] 1)\n"))
            st2 (first (store/record-module-platform st2 "app.my-view" :cljs))]
        (is (= 'app.my-view/a
               (:form (cljs/anchor-error st2 "boom"
                                         {:file "cljs-src/app/my_view.cljs" :line 3}))))))
    (testing "an unlocatable error still carries its message, and says no more"
      ;; never let \"could not anchor\" and \"anchored fine\" look alike
      (let [r (cljs/anchor-error st "something went wrong" nil)]
        (is (= "something went wrong" (:error r)))
        (is (nil? (:form r)))
        (is (nil? (:at r))))
      (let [r (cljs/anchor-error st "boom" {:file "cljs-src/nope/gone.cljs" :line 2})]
        (is (= "boom" (:error r)))
        (is (nil? (:form r)) "a file with no matching store namespace anchors nothing")))))

(deftest foreign-libs-translate-only-the-formats-that-can-be-concatenated
  (let [st {:js-deps {"roughjs" {:format :iife :global "rough"
                                 :file "public/js/roughjs-4.6.6.js"}
                      "excalidraw" {:format :esm :global "ExcalidrawLib"
                                    :file "public/js/excalidraw.js"}}}
        fl (cljs/foreign-libs-for st)]
    (testing "an :iife library becomes a foreign lib mapped to its global"
      (is (= [{:file "public/js/roughjs-4.6.6.js"
               :provides ["roughjs"]
               :global-exports {'roughjs 'rough}}]
             fl)))
    (testing ":esm is skipped — the page loads it, and concatenating an ES module
              yields a bundle that fails at runtime with nothing to point at"
      (is (not-any? #(= "public/js/excalidraw.js" (:file %)) fl)))
    (testing "a store that vendors nothing produces nothing, not an empty declaration"
      (is (empty? (cljs/foreign-libs-for {}))))))

(deftest a-published-contract-becomes-a-client-plan
  ;; The consuming half of contract publication. A contract is plain DATA, so
  ;; this needs no server, no store and no fixtures — which is the property
  ;; that makes generating against someone else's API cheap to test at all.
  (let [document {:slopp/contract-version 2
                  :endpoints [{:method :get :path "/api/things" :name 'things
                               :request nil :response [:sequential :string]}
                              {:method :post :path "/api/things" :name 'create!
                               :request [:map [:name :string]]
                               :response [:map [:id :int]]}]}
        plan (cljs/contract->plan document 'demo.client.contracts)
        by-fn (into {} (map (juxt (comp str :fn-name) identity)) (:wrappers plan))
        defs  (into {} (map (juxt :name :schema)) (:defs plan))]

    (testing "one wrapper per endpoint, named the way local generation names them"
      ;; create! already carries the bang the dialect asks of a mutating verb,
      ;; so the generator must not add a second one.
      (is (= #{"things" "create!"} (set (keys by-fn)))))

    (testing "each schema becomes a def named from its endpoint, since the author's names did not survive publication"
      (is (= {'things-response [:sequential :string]
              'create-request  [:map [:name :string]]
              'create-response [:map [:id :int]]}
             defs))
      (is (not (contains? defs 'create!-request))
          "the bang belongs to the wrapper, not to a schema's name"))

    (testing "wrappers reference those defs as vars, so the generated client reads like a local one"
      (is (= {:kind :var :sym 'demo.client.contracts/things-response
              :ns 'demo.client.contracts}
             (:response (by-fn "things"))))
      (is (= {:kind :var :sym 'demo.client.contracts/create-request
              :ns 'demo.client.contracts}
             (:request (by-fn "create!")))))

    (testing "a verb with no body carries no request schema at all"
      (is (= {:kind :none} (:request (by-fn "things")))))

    (testing "an unknown contract version is refused rather than guessed at"
      (let [p (cljs/contract->plan (assoc document :slopp/contract-version 99)
                                   'demo.client.contracts)]
        (is (empty? (:wrappers p)))
        (is (seq (:problems p))
            "a consumer that silently generated from a shape it does not know
             would fail later, further away, and with no clue why")))))

(deftest a-generated-contracts-namespace-is-ordinary-verified-source
  ;; The schemas land as SOURCE in the consuming store, not as data parsed at
  ;; runtime. That is what makes the round trip "print a form, read a form" —
  ;; the thing the store already does on every write — instead of a schema
  ;; importer nobody can be sure of.
  (let [src (cljs/render-contracts-ns
             'demo.client.contracts
             [{:name 'things-response :schema [:sequential :string] :endpoint 'things}
              {:name 'create-request :schema [:map [:name :string]] :endpoint 'create!}])]

    (testing "a real ns form, so the JVM oracle verifies it like any other namespace"
      (is (str/starts-with? src "(ns demo.client.contracts")))

    (testing "each schema is a plain def of the published value"
      ;; a large schema gets its own line, so pin NAME PAIRED WITH VALUE
      ;; rather than their layout — the bug worth catching is a def bound to
      ;; the wrong schema, which a whitespace-sensitive match would miss.
      (is (str/includes? src "things-response"))
      (is (str/includes? src "[:sequential :string]")))

    (testing "every def says which endpoint it came from — generated, not hand-written"
      (is (str/includes? src "{:generated \"things\"}"))
      (is (str/includes? src "{:generated \"create!\"}")))

    (testing "the store parses it into exactly the defs it claims"
      ;; the wire format's safety argument, checked rather than asserted: if
      ;; a published schema could not survive as source, ingest is where that
      ;; shows up.
      (let [st   (store/ingest (store/empty-store) 'demo.client.contracts src)
            form (fn [n] (str (store/form-named st 'demo.client.contracts n)))]
        (is (str/includes? (form 'things-response) "[:sequential :string]"))
        (is (str/includes? (form 'create-request) "[:map [:name :string]]"))
        (is (not (str/includes? (form 'things-response) "[:map [:name :string]]"))
            "each def carries ITS OWN schema — a renderer that paired names with
             values by position would pass every presence check above")))))

(deftest ^:external a-cljs-namespace-does-not-silence-the-whole-project-run
  ;; Found reviewing slopp-ui, which has four :cljs namespaces: `test_run
  ;; {all true}` answered `{:external-pending {…} :ms 43}` — no :test, no
  ;; :pass, 43ms for 32 tests. Nothing ran, and nothing said so.
  ;;
  ;; `slopp.kernel.rt/traced-run` maps `ns-interns` over every namespace it is handed,
  ;; and the whole-project path hands it EVERY namespace in the store. A :cljs
  ;; namespace does not exist in the JVM image, so `ns-interns` throws "No
  ;; namespace: … found" — lazily, inside the run. The throw crosses the eval
  ;; boundary as text, `{:keys [summary trace]}` destructures to nil, and the
  ;; caller's cond-> builds a map with no counts in it.
  ;;
  ;; Which is the worst shape available: `full_check` — the gate you run before
  ;; a commit you want to stand behind — reports its in-image tier green having
  ;; run nothing, and the warranty trace never fills, so `review_scan` calls
  ;; well-tested forms :untested. Every store with client code, not just this
  ;; one.
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'wp.core :source "(ns wp.core)\n(defn f [x] (* 2 x))\n")
      (ops/create-ns! sess 'wp.core-test
                      :source (str "(ns wp.core-test\n"
                                   "  (:require [clojure.test :refer [deftest is]]\n"
                                   "            [wp.core :as c]))\n"
                                   "(deftest doubles-it (is (= 4 (c/f 2))))\n"))
      (testing "the whole-project run reports what it ran, with no cljs present"
        (let [r (ops/test-run! sess nil)]
          (is (= 1 (:test r)) (pr-str r))
          (is (= 1 (:pass r)) (pr-str r))))
      (ops/create-ns! sess 'wp.client :source "(ns wp.client)\n"
                      :platform :cljs :prompt "browser code")
      (testing "and a :cljs namespace in the store does not change that — it
                cannot run in the image, which is a reason to leave it out of
                the run, never a reason for the run to vanish"
        (let [r (ops/test-run! sess nil)]
          (is (= 1 (:test r)) (pr-str r))
          (is (= 1 (:pass r)) (pr-str r))
          (is (zero? (+ (:fail r 0) (:error r 0))) (pr-str r))))
      (testing "and the trace still lands, which is what review_scan reads"
        (is (contains? (get (:test-map @sess) 'wp.core-test/doubles-it) 'wp.core/f)
            (pr-str (:test-map @sess))))
      (finally (ops/close! sess)))))

(deftest a-published-contract-is-READ-and-never-evaluated
  ;; The docstring's central claim is a SECURITY one: this is data off a network
  ;; boundary, so it goes through `clojure.edn/read-string`, which evaluates
  ;; nothing. Nothing checked it. A fake transport can serve a payload that
  ;; would prove the difference — `#=(…)` is read-eval, which `read-string`
  ;; honours and the EDN reader refuses — and that is a far better test than any
  ;; real server, because no real server would ever send it.
  (let [at (fn [body] (http.client/fake-requester
                       "http://pub.test/"
                       {[:get "/contract"] (fn [_] {:status 200 :body body})}))]
    (testing "an ordinary contract round-trips as data"
      (is (= [:map [:id :int]]
             (cljs/fetch-contract "http://pub.test/contract"
                                  (at "[:map [:id :int]]")))))
    (testing "a payload carrying read-eval does NOT evaluate — it refuses. If
              this ever passes by returning a value, the reader was swapped for
              one that runs whatever a contract server sends"
      (is (thrown? Exception
                   (cljs/fetch-contract "http://pub.test/contract"
                                        (at "#=(java.lang.System/getProperty \"user.name\")")))))
    (testing "a non-200 fails instead of being parsed as though it were a
              contract — an error page is not a schema"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"404"
           (cljs/fetch-contract "http://pub.test/contract"
                                (http.client/fake-requester "http://pub.test/" {})))))))

(deftest ^:external a-bare-generate-client-targets-THIS-stores-family
  ;; Reported by slopp-ui. Their store's entire family is `slopp-ui.*` — 22
  ;; namespaces — and it already had a generated client at
  ;; `slopp-ui.client.api`. A bare `generate_client {from …}` wrote a SECOND
  ;; one under a literal `app.client.*`, reported success, and said nothing
  ;; about the client that already existed.
  ;;
  ;; Not cosmetic downstream: the new namespace is marked `:cljs`, so
  ;; `compile_client` would have compiled a duplicate contracts namespace into
  ;; the browser bundle. Cost to undo: 19 calls. `ns_delete` refuses a
  ;; non-empty namespace, there is no bulk delete, and `undo` — the tool you
  ;; would reach for — was friction #28's own bug.
  ;;
  ;; `app.client.api` is only ever right for a store whose family is literally
  ;; `app`. It is a placeholder that shipped as a default.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopf.contracts
                   "(ns shopf.contracts)\n\n(def order [:map [:item :string]])\n")
      (ops/module-platform! sess "shopf.contracts" "cljc" :prompt "shared contract")
      (ops/ingest! sess 'shopf.api
                   (str "(ns shopf.api)\n\n"
                        "(defn ^{:http/method :post :rest/path \"/api/orders\""
                        " :rest/request shopf.contracts/order"
                        " :rest/response shopf.contracts/order}"
                        " create-order \"Create an order.\" [req] req)\n"))
      (testing "the default is derived from the store, not a placeholder"
        (let [r (cljs/generate-client! sess)]
          (is (= 'shopf.client.api (:generated r)) (pr-str r))
          (is (= 1 (:endpoints r)) (pr-str r))))
      (testing "an explicit :ns still wins — the default is a default"
        (let [r (cljs/generate-client! sess :ns 'shopf.elsewhere.api)]
          (is (= 'shopf.elsewhere.api (:generated r)) (pr-str r))
          (testing "and it says which OTHER generated client now stands"
            ;; the case this change itself creates: a store generated under
            ;; the old placeholder default keeps that namespace, marked :cljs,
            ;; and compile_client will keep bundling it. Silence here is how
            ;; someone ends up shipping two clients.
            (is (= ['shopf.client.api] (:other-clients r)) (pr-str r))
            (is (re-find #"shopf\.client\.api" (str (:note r))) (pr-str r)))))
      (testing "and regenerating in place says nothing — it is not an other client"
        (let [r (cljs/generate-client! sess :ns 'shopf.elsewhere.api)]
          (is (not (contains? (set (:other-clients r)) 'shopf.elsewhere.api))
              (pr-str r))))
      (finally (ops/close! sess)))))

(deftest a-generated-client-namespace-states-its-own-purpose
  ;; slopp-ui, 2026-08-03: `namespace-purpose` fires on their generated client
  ;; namespace and they CANNOT discharge it. Hand-editing is overwritten by the
  ;; next generate_client, so the advisory returns on every contract change —
  ;; a permanent finding a consumer cannot clear, which trains the reader to
  ;; skim the whole list.
  ;;
  ;; It is an inconsistency between the generator's two outputs rather than a
  ;; missing feature: `render-contracts-ns` has written a purpose since it
  ;; existed. A generator emitting code subject to a rule has to emit code
  ;; that SATISFIES it, or it manufactures debt its user has no way to pay.
  (let [src (cljs/render-client-ns
             'app.client
             [{:name "get-thing" :method :get :path "/api/thing"
               :response {:ns 'app.contracts :sym 'thing}}])]
    (testing "the ns form opens with a docstring, before the requires"
      (is (re-find #"\(ns app\.client\n\s+\"" src) src))
    (testing "and it says what the namespace IS, plus the one rule for generated code"
      ;; the advisory's own teaching: not a list of contents, which
      ;; query_project already derives — why it exists, and for generated
      ;; source the instruction that keeps a reader from editing it
      (is (re-find #"generate_client" src) src)
      (is (re-find #"(?i)regenerate|never hand-edit" src) src))
    (testing "the requires still follow, so the docstring did not displace them"
      (is (re-find #"\(:require \[malli\.core :as m\]" src) src))))

(deftest a-GET-wrapper-sends-what-the-path-does-not-consume-as-a-QUERY
  ;; slopp-ui, 2026-08-04: `?depth=N` answered correctly on the wire and was
  ;; unreachable through the generated client, because a wrapper takes a params
  ;; map and only the PATH ever reads from it. And `direct-http` is a swept
  ;; rule, correctly — so hand-rolling a fetch past the generated client is
  ;; precisely the workaround a typed client exists to prevent.
  ;;
  ;; No new declaration for this. `:rest/request` already means "what the caller
  ;; SENDS"; how it travels follows from the METHOD, which the contract already
  ;; carries. A body verb keeps sending a body; a GET sends a query string.
  (let [inline (fn [s] {:kind :inline :schema s})
        wrap   (fn [method path request]
                 (#'cljs/render-wrapper
                  {:fn-name "form" :method method :path path
                   :endpoint "demo/form" :request request :response nil}))
        schema (inline '[:map [:id :string] [:depth {:optional true} :int]])
        get-   (wrap :get "/api/form/:id" schema)
        post   (wrap :post "/api/form/:id" schema)
        bare   (wrap :get "/api/things" nil)]
    (testing "the GET wrapper builds a query string, and never a body"
      (is (re-find #"qs" get-) get-)
      (is (not (re-find #"JSON.stringify" get-))
          "a GET with a body is the bug being fixed, not a different spelling of it"))
    (testing "the PATH params are not repeated in the query"
      ;; :id is interpolated into the path; sending it twice would be wrong and
      ;; would make every url depend on the map's ordering
      (is (re-find #"dissoc params :id" get-) get-))
    (testing "a body verb is untouched — it still sends a body"
      (is (re-find #"JSON.stringify" post) post)
      (is (not (re-find #"\(qs " post))))
    (testing "a GET with NO request schema and no path params is what it was"
      (is (not (re-find #"qs" bare)) bare)
      (is (re-find #"\n  \[\]\n" bare) bare))
    (testing "the url expression is ONE str, not a str inside a str"
      ;; generated code is read by whoever consumes the API, so a nested
      ;; (str (str …) …) is a seam showing. And an unchanged wrapper has to
      ;; stay byte-identical: this namespace is regenerated wholesale and
      ;; diffed by eye, so a cosmetic churn across every endpoint is noise
      ;; that hides the one line that actually moved.
      (is (re-find #"\(url \(str \"/api/form/\" \(:id params\) \(qs \(dissoc params :id\)\)\)\)" get-)
          get-)
      (is (re-find #"\(url \"/api/things\"\)" bare) bare))
    (testing "and the runtime helper the wrapper calls actually exists"
      ;; the half that would otherwise ship a wrapper calling nothing
      (let [src (cljs/render-client-ns
                 'demo.client.api
                 [{:fn-name "form" :method :get :path "/api/form/:id"
                   :endpoint "demo/form" :response nil :request schema}])]
        (is (re-find #"\(defn- qs " src) src)
        (is (re-find #"encodeURIComponent" src))))))

(deftest both-client-producers-agree-about-what-a-GET-SENDS
  ;; There are TWO producers of a wrapper spec and they must agree:
  ;; `client-wrapper-specs` reads the LOCAL store, `contract->plan` reads a
  ;; PUBLISHED contract document. contract->plan's own docstring makes the
  ;; claim — "generating against someone else's API and against your own
  ;; produce the same kind of namespace".
  ;;
  ;; It was a COMMENT, and that is the finding. Fixing the local producer to
  ;; keep a GET's request made the remote one wrong in the same commit, and the
  ;; line above it read:
  ;;
  ;;   ;; a body verb carries a request; every other verb declares none,
  ;;   ;; the same split client-wrapper-specs makes locally
  ;;
  ;; — prose asserting a parity that had just been broken, positioned exactly
  ;; where the next reader checks whether both paths were covered. slopp-ui
  ;; read it, believed it, and looked elsewhere first.
  ;;
  ;; Their rule, which is why this test exists in this shape: **a comment
  ;; naming another function as the reason this code is correct is a candidate
  ;; for being that function's test instead.**
  (let [schema '[:map [:id :string] [:depth {:optional true} :int]]
        url-of (fn [spec]
                 (second (re-find #"js/fetch \((url .*?)\) \(clj->js"
                                  (#'cljs/render-wrapper spec))))
        ;; LOCAL: the store's own endpoint
        st     (-> (store/empty-store)
                   (store/ingest 'shop.contracts
                                 (str "(ns shop.contracts)\n\n(def q " (pr-str schema) ")\n")))
        st     (first (store/record-module-platform st "shop.contracts" :cljc))
        st     (store/ingest st 'shop.api
                             (str "(ns shop.api)\n\n"
                                  "(defn ^{:http/method :get :rest/path \"/api/form/:id\""
                                  " :rest/request shop.contracts/q"
                                  " :rest/response shop.contracts/q}"
                                  " form [req] req)\n"))
        local  (first (filter #(= 'form (:fn-name %))
                              (:wrappers (cljs/client-wrapper-specs st))))
        ;; REMOTE: the same endpoint as a published contract DOCUMENT. Built as
        ;; data on purpose — that is exactly what crosses the wire, and a
        ;; consumer has no store to read.
        doc    {:slopp/contract-version 2
                :endpoints [{:method :get :path "/api/form/:id" :name 'form
                             :request schema :response schema}]}
        remote (first (:wrappers (cljs/contract->plan doc 'demo.client.contracts)))]
    (testing "both producers found the endpoint at all — the control"
      (is (some? local))
      (is (some? remote)))
    (testing "both keep the request, so both can express what the path does not carry"
      (is (not= :none (:kind (:request local))) (pr-str local))
      (is (not= :none (:kind (:request remote))) (pr-str remote)))
    (testing "and they render the SAME url expression, which is the parity itself"
      ;; not the whole wrapper: the schema SYMBOL legitimately differs (a
      ;; published contract lost the publisher's var names, so the remote path
      ;; re-derives `form-request` from the endpoint). The url is the part that
      ;; must not depend on which producer you came through.
      (is (= "url (str \"/api/form/\" (:id params) (qs (dissoc params :id)))"
             (url-of local))
          (url-of local))
      (is (= (url-of local) (url-of remote))
          (str "local: " (url-of local) "\nremote: " (url-of remote))))))

(deftest a-generated-wrapper-CHECKS-THE-STATUS-before-it-blames-the-contract
  ;; Found by the app that consumes these wrappers, in the nine forms it did
  ;; not write. `js/fetch` rejects only on a NETWORK error, so a 500 RESOLVES
  ;; and a wrapper that goes straight to `.json` hands the error page's body to
  ;; `m/decode`, where it fails validation and rejects with:
  ;;
  ;;     modules response failed validation
  ;;
  ;; **The server said 500 and the screen blames the contract.** A plain wrong
  ;; sentence, shipped, on a screen that otherwise works — and it costs more
  ;; than the wrong words. Contract validation exists to detect DRIFT, and a
  ;; transport failure wearing its clothes makes real drift and a 502 from a
  ;; proxy produce the same sentence, which destroys the signal the validation
  ;; was added to give.
  ;;
  ;; Worse with a non-JSON body: an HTML error page rejects inside `.json`, and
  ;; the reader is shown `Unexpected token <`.
  (let [src (cljs/render-client-ns
             'shop.client.api
             [{:fn-name 'get-order :method :get :path "/api/orders/:id"
               :endpoint 'shop.api/get-order
               :request  {:kind :none}
               :response {:kind :var :sym 'shop.contracts/order :ns 'shop.contracts}}
              {:fn-name 'ping :method :get :path "/api/ping"
               :endpoint 'shop.api/ping
               :request  {:kind :none}
               :response {:kind :none}}])]

    (testing "the status is checked, and the failure names it"
      (is (re-find #"\.-ok" src)
          (str "no status check at all — every non-2xx becomes whatever the"
               " decode says about the error page's body: " src))
      (is (re-find #"\.-status" src)
          "404 and 500 are different problems and the message must separate them"))

    (testing "the check comes BEFORE the body is read, structurally"
      ;; the ordering is the whole fix: after the decode there is nothing left
      ;; to report but the decode's own complaint. Pinned as NESTING rather
      ;; than as string order, because two `.then` steps in the right order is
      ;; a thing a later edit can quietly swap
      (is (re-find #"\(\.json \(ok! resp\)\)" src)
          (str "the body must be read THROUGH the guard, not beside it: " src)))

    (testing "one guard, beside the other generated helpers"
      ;; `url` and `qs` are already shared here, and a check inlined per
      ;; wrapper is one an edit can fix in eight places and miss the ninth
      (is (re-find #"\(defn- ok!" src) src))

    (testing "EVERY wrapper reads its body through it, contract or not"
      ;; a wrapper with nothing to validate has nothing to be wrongly blamed —
      ;; but it would silently succeed on a 500 and hand back an error page,
      ;; which is the same failure with the diagnosis removed entirely
      (is (= 2 (count (re-seq #"\(ok! resp\)" src)))
          (str "one wrapper skipped the guard: " src)))))

(deftest a-webapp-store-generates-REQUESTS-and-CHECKS-not-performers
  ;; Named by the app that adopted the screen value: `generate_client` and
  ;; `webapp` now overlap. The generated namespace is two things — schemas
  ;; (`:cljc`) and fetch WRAPPERS (`:cljs`) — and once the framework performs
  ;; every request the wrappers are dead surface. Nine public fns in theirs.
  ;;
  ;; So a store with `webapp` on gets what it can actually use: REQUEST builders
  ;; for a row's `:request` and CONTRACT checks for its `:check`. That also
  ;; restores what 4d took away — their response validation lived in the
  ;; wrappers, and when the framework took over performing it went with them.
  ;;
  ;; **In TWO namespaces, and that is not tidiness.** A builder is a pure data
  ;; function; a check reaches malli, which the functional-core gate reads as
  ;; IO. Shipped together, the builders inherit the checks' tier — and the app
  ;; that tried to use them could not, because its views are `:pure` and the
  ;; edge to an `:external` namespace is a layering violation. It ended up
  ;; hand-writing every request map: correct by inspection rather than by
  ;; construction, which is exactly the drift generation exists to remove.
  (let [wrappers [{:fn-name 'get-order :method :get :path "/api/orders/:id"
                   :endpoint 'shop.api/get-order
                   :request  {:kind :var :sym 'shop.contracts/query :ns 'shop.contracts}
                   :response {:kind :var :sym 'shop.contracts/order :ns 'shop.contracts}}
                  {:fn-name 'create-order! :method :post :path "/api/orders"
                   :endpoint 'shop.api/create-order
                   :request  {:kind :var :sym 'shop.contracts/order :ns 'shop.contracts}
                   :response {:kind :none}}
                  {:fn-name 'ping :method :get :path "/api/ping"
                   :endpoint 'shop.api/ping
                   :request  {:kind :none}
                   :response {:kind :none}}]
        src  (cljs/render-request-ns 'shop.client.api wrappers nil)
        chk  (cljs/render-check-ns 'shop.client.checks wrappers)]

    (testing "one REQUEST builder per endpoint, each carrying its provenance"
      (is (re-find #"\(ns shop\.client\.api" src) src)
      (is (= 3 (count (re-seq #"\(defn \^\{:generated .*?-request" src)))
          (str "one request builder per endpoint, and the ! is dropped because"
               " building a request performs nothing: " src))
      (is (re-find #"\^\{:generated \"shop\.api/get-order\"\}" src) src))

    (testing "the builder returns slopp.webapp's request SHAPE"
      (is (re-find #":webapp/method :get" src) src)
      (is (re-find #":webapp/path \"/api/orders/:id\"" src) src)
      (is (re-find #":webapp/path-params \{:id \(:id params\)\}" src)
          (str "a captured segment must be supplied SEPARATELY, or request-url"
               " has nothing to substitute: " src)))

    (testing "a body verb sends a BODY; anything else sends a query"
      (is (re-find #":webapp/body params" src) src)
      (is (re-find #":webapp/query \(dissoc params :id\)" src)
          (str "a path param must not also arrive as a query key: " src)))

    (testing "an endpoint with no request takes NO arguments"
      ;; `ping` declares nothing, so a builder taking params would invent a
      ;; parameter the contract does not have
      (is (re-find #"ping-request\n  \"[^\"]*\"\n  \[\]" src) src))

    (testing "the REQUESTS namespace requires NOTHING, which is what keeps it pure"
      ;; the friction this split closes: malli is what makes the checks
      ;; `:external`, and a builder that shipped beside them could not be
      ;; reached from a `:pure` view at all
      (is (not (re-find #":require" src))
          (str "a request builder needs no library to build a map, and a"
               " require is what would tier it: " src))
      (is (not (re-find #"m/validate|m/decode" src)) src))

    (testing "and the CHECKS are their own namespace, which may reach malli"
      (is (re-find #"\(ns shop\.client\.checks" chk) chk)
      (is (re-find #"malli\.core" chk) chk)
      (is (re-find #"get-order-check" chk) chk)
      (is (re-find #"m/validate shop\.contracts/order" chk) chk)
      (is (not (re-find #"ping-check" chk))
          "an endpoint with no response contract has nothing to check")
      (is (not (re-find #"-request" chk))
          "a builder in the checks namespace would defeat the split"))

    (testing "the check DECODES first, or it validates the wire's shapes"
      ;; a keyword field arrives from JSON as a string, so validating the raw
      ;; body fails a contract the server honoured — the false drift report
      ;; this whole mechanism exists to avoid
      (is (re-find #"m/decode shop\.contracts/order" chk) chk))

    (testing "a FOREIGN contract's builders carry their own escape"
      ;; the second friction, and the escape had no reachable form: the
      ;; advisory reports a generated builder whose path this store does not
      ;; serve, and its escape is a MARKER on a form nobody may hand-edit,
      ;; because the next generation would drop it silently.
      ;;
      ;; Generation knows where the contract came from, so it declares it —
      ;; the escape stays a declaration and nothing is hand-edited.
      (let [far (cljs/render-request-ns 'shop.client.api wrappers
                                        "http://pub.test/contract")]
        (is (= 3 (count (re-seq #":http/external-path" far)))
            (str "every builder for somebody else's API must carry it, or the"
               " advisory reports the ones that did not: " far))
        (is (re-find #"http://pub\.test/contract" far)
            (str "and the REASON is where the contract came from: " far))))

    (testing "both namespaces are PORTABLE, which is the whole point"
      (doseq [s [src chk]]
        (is (not (re-find #"js/" s))
            (str "generated code that reaches for the browser is code no"
                 " in-image test can read: " s))
        (is (= (count (re-seq #"\(" s)) (count (re-seq #"\)" s)))
            "balanced parens")))))

(deftest ^:external generate-client-follows-WHO-PERFORMS-the-request
  ;; The overlap named by the consuming app: with `webapp` on, the framework
  ;; performs every request, so a generated `:cljs` fetch wrapper is surface
  ;; nothing calls — and it is `:cljs`, so it is what `webapp-client-code`
  ;; reports and what an author is then told to justify.
  ;;
  ;; So the capability decides the SHAPE. Not a flag: which artifact is useful
  ;; follows from who performs, and that is already declared.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopw.contracts
                   (str "(ns shopw.contracts)\n\n"
                        "(def order \"O.\" [:map [:id :string]])\n"))
      (ops/module-platform! sess "shopw.contracts" :cljc :prompt "shared with the browser")
      (ops/ingest! sess 'shopw.api
                   (str "(ns shopw.api)\n\n"
                        "(defn ^{:http/method :get :rest/path \"/api/orders/:id\"\n"
                        "        :http/auth :public :rest/response shopw.contracts/order}\n"
                        "  get-order \"G.\" [_] {:status 200 :body {}})\n"))

      (testing "without webapp, generation still emits the :cljs performer"
        ;; a store whose browser does NOT own routing has no framework
        ;; performer, so a typed fetch wrapper is exactly what it needs
        (let [r (cljs/generate-client! sess :ns 'shopw.client.api)]
          (is (= :cljs (:platform r)) (pr-str r))))

      (testing "with webapp on, it emits the PORTABLE request namespace instead"
        (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                          :prompt "this store's browser owns routing")
        (let [r   (cljs/generate-client! sess :ns 'shopw.client.api)
              src (str (store.render/render-ns (:store @sess) 'shopw.client.api))
              chk (str (store.render/render-ns (:store @sess) 'shopw.client.checks))]
          (is (= :cljc (:platform r))
              (str "a webapp store was handed fetch wrappers its own framework"
                   " makes unreachable: " (pr-str r)))
          (is (= 'shopw.client.checks (:checks r)) (pr-str r))
          (is (re-find #"get-order-request" src) src)
          (is (not (re-find #"js/fetch" src))
              "a webapp store got a performer anyway")

          (testing "the builders are :pure, which is what lets a :pure view name them"
            ;; the friction this split closes: a check reaches malli, which the
            ;; functional-core gate reads as IO, so builders shipped beside them
            ;; inherited that tier and a `:pure` view could not name them at all
            (is (= :pure (tiers/tier-for (:store @sess) 'shopw.client.api))
                (pr-str (tiers/tier-for (:store @sess) 'shopw.client.api)))
            ;; the require FORM, not the word: this namespace's docstring names
            ;; malli to say where the checks went, and prose about a thing is
            ;; not the thing — the same trap `the-browser-SHIM-cannot-branch`
            ;; documents and avoids by scanning sexprs
            (is (not (re-find #"\(:require" src))
                (str "a require is what would tier the builders: " src)))

          (testing "while the CHECKS are their own namespace and may reach it"
            (is (re-find #"get-order-check" chk) chk)
            (is (re-find #"malli\.core" chk) chk))))

      (finally (ops/close! sess)))))

(deftest ^:external generating-FROM-a-published-contract-follows-who-performs-too
  ;; The half the branch missed, reported by the app it was built for.
  ;; `generate-client!` reads the endpoints THIS store serves;
  ;; `generate-client-from!` reads a contract published elsewhere. Only the
  ;; first got the webapp branch — which is backwards for the motivating case,
  ;; because a browser app consuming somebody ELSE'S API reaches generation
  ;; only through `from`, and that is the architecture `D-webapp` names:
  ;;
  ;;   "a frontend consuming a GENERATED contract instead of sharing implicit
  ;;    server internals"
  ;;
  ;; They regenerated, got nine `:cljs` wrappers their own framework makes
  ;; unreachable, and `webapp-client-code` then named the namespace holding
  ;; them — a finding whose only fix was a tool that would not emit it.
  ;;
  ;; The FETCH is redefed rather than given a seam: what is under test is which
  ;; artifact the store gets, and `a-published-contract-is-READ-and-never-
  ;; evaluated` already drives the transport through `fake-requester`.
  (let [doc  {:slopp/contract-version 2
              :endpoints [{:method :get :path "/api/modules" :name 'modules
                           :response [:map [:names :string]]}]}
        sess (external/open!)]
    (try
      (with-redefs [cljs/fetch-contract (fn [& _] doc)]
        (testing "without webapp, the consumer still gets the :cljs performer"
          (let [r (cljs/generate-client-from! sess "http://pub.test/contract"
                                              :ns 'shopx.client.api)]
            (is (= :cljs (:platform r)) (pr-str r))))

        (testing "with webapp on, it gets the portable REQUEST namespace"
          (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                            :prompt "this store's browser owns routing")
          (let [r   (cljs/generate-client-from! sess "http://pub.test/contract"
                                                :ns 'shopx.client.api)
                src (str (store.render/render-ns (:store @sess) 'shopx.client.api))]
            (is (= :cljc (:platform r))
                (str "the path a consuming browser app actually reaches still"
                     " emitted wrappers its framework cannot call: " (pr-str r)))
            (is (re-find #"modules-request" src) src)
            (is (not (re-find #"js/fetch" src))
                "a webapp store got a performer through the consumer path")))

        (testing "and the CONTRACTS namespace is :cljc either way"
          ;; unchanged by any of this: the schemas have to load in the image AND
          ;; compile into the bundle, which is what makes one definition check
          ;; both sides of the wire
          (is (= :cljc (store/platform-for (:store @sess) 'shopx.client.contracts))
              (pr-str (store/platform-for (:store @sess) 'shopx.client.contracts)))))

      (finally (ops/close! sess)))))

(deftest ^:external generation-REFUSES-to-overwrite-what-it-did-not-write
  ;; Reported as a near-miss rather than a break, which is the only reason it
  ;; is cheap to fix. The consuming app nearly generated into `<root>.api`,
  ;; which DERIVES `<root>.contracts` for the schemas — a hand-written
  ;; namespace that store already had. `store/ingest` is BELOW the per-form
  ;; gates, deliberately, so regeneration can overwrite its own output
  ;; wholesale; the same property makes overwriting somebody's own code silent.
  ;;
  ;; They caught it by reading what the generator DERIVES rather than what they
  ;; passed. That is not a habit a tool should need: the derived name is the
  ;; generator's to compute, so the collision is the generator's to refuse.
  ;;
  ;; A namespace whose forms are all `^:generated` is this tool's own output and
  ;; is overwritten as before — regeneration is the ONLY writer there and that is
  ;; the whole contract.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopz.contracts
                   (str "(ns shopz.contracts\n"
                        "  \"Hand-written. The hub's own schemas.\")\n\n"
                        "(def project \"P.\" [:map [:slug :string]])\n"))
      (ops/module-platform! sess "shopz.contracts" :cljc :prompt "shared")
      (ops/ingest! sess 'shopz.api
                   (str "(ns shopz.api)\n\n"
                        "(defn ^{:http/method :get :http/path \"/api/things\"\n"
                        "        :http/auth :public :rest/response shopz.contracts/project}\n"
                        "  things \"T.\" [_] {:status 200 :body {}})\n"))

      (testing "generating into a name whose DERIVED sibling is hand-written is refused"
        ;; `<root>.thing` derives `<root>.contracts` for the published schemas,
        ;; which is exactly the near-miss: the name the caller passes is not the
        ;; only name written, and the derived one is the generator's to compute
        (with-redefs [cljs/fetch-contract
                      (fn [& _] {:slopp/contract-version 2
                                 :endpoints [{:method :get :path "/api/things"
                                              :name 'things
                                              :response [:map [:id :string]]}]})]
          (let [r (cljs/generate-client-from! sess "http://pub.test/contract"
                                              :ns 'shopz.thing)]
            (is (:error r)
                (str "generation was about to overwrite hand-written code and"
                     " said nothing: " (pr-str r)))
            (is (re-find #"shopz\.contracts" (str (:error r))) (pr-str r)))))

      (testing "and the hand-written namespace is UNTOUCHED"
        ;; the assertion that makes the refusal worth having rather than a
        ;; message beside a completed overwrite
        (let [src (str (store.render/render-ns (:store @sess) 'shopz.contracts))]
          (is (re-find #"Hand-written" src) src)
          (is (re-find #"def project" src) src)))

      (testing "while regenerating over its OWN output still works"
        ;; regeneration is the only writer of a generated namespace and
        ;; overwrites wholesale — that is the contract, and a refusal that
        ;; caught it too would make the tool unusable on its second run
        (let [r1 (cljs/generate-client! sess :ns 'shopz.client.api)
              r2 (cljs/generate-client! sess :ns 'shopz.client.api)]
          (is (nil? (:error r1)) (pr-str r1))
          (is (nil? (:error r2))
              (str "the second run refused its own first: " (pr-str r2)))))

      (finally (ops/close! sess)))))

(deftest ^:external a-webapp-store-generates-OUTSIDE-its-browser-module
  ;; Reported after the tier fix landed and a MODULE gate stood behind it. The
  ;; builders were `:pure` and still unusable:
  ;;
  ;;   views/client-routes names api/timeline-request
  ;;     → module_dep refuses: views → client → app → views
  ;;
  ;; The browser ENTRY is `<root>.client.app`; it requires the app, which reads
  ;; the route table in the views. So a client generated under `<root>.client`
  ;; closes the loop — and `<root>.client.api` was the default while
  ;; `<root>.client.*` is the natural home for an entry. **The recommended
  ;; target and the recommended entry wanted the same module**, so every webapp
  ;; store taking this shape meets it.
  ;;
  ;; Their fix and their word: `<root>.wire.*`. It says the useful thing rather
  ;; than merely avoiding the clash — a CONSUMED api is not part of this app's
  ;; browser layer, and naming it after the wire says where it lives.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopv.views (str "(ns shopv.views)\n\n(defn v \"V.\" [] [:p])\n"))
      (ops/ingest! sess 'shopv.api
                   (str "(ns shopv.api)\n\n"
                        "(defn ^{:http/method :get :rest/path \"/api/things\"\n"
                        "        :http/auth :public :rest/response :string}\n"
                        "  things \"T.\" [_] {:status 200 :body \"[]\"})\n"))

      (testing "without webapp the default is unchanged — no entry, no cycle"
        (let [r (cljs/generate-client! sess)]
          (is (= 'shopv.client.api (:generated r)) (pr-str r))))

      (testing "with webapp on it defaults OUTSIDE the browser module"
        (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                          :prompt "this store's browser owns routing")
        (let [r (cljs/generate-client! sess :ns nil)]
          (is (= 'shopv.wire.api (:generated r))
              (str "a webapp store's generated client defaulted into the module"
                   " its browser entry lives in, which closes a cycle the app"
                   " cannot declare its way out of: " (pr-str r)))
          (is (= 'shopv.wire.checks (:checks r)) (pr-str r))))

      (testing "and an explicit :ns still wins, because the default is a default"
        (let [r (cljs/generate-client! sess :ns 'shopv.elsewhere.api)]
          (is (= 'shopv.elsewhere.api (:generated r)) (pr-str r))))

      (finally (ops/close! sess)))))

(deftest a-wrapper-decodes-what-the-endpoint-ANSWERS
  ;; Found while retiring `:rest/client`. Every generated fetch wrapper does
  ;; `(.json (ok! resp))` unconditionally, and slopp's own `/api/contracts`
  ;; answers `application/edn` — a malli schema is keywords and symbols, and
  ;; JSON renders `:string` and `"string"` identically, so the far end could
  ;; not tell them apart. A wrapper for it would fail on the first character.
  ;;
  ;; That endpoint carried `:rest/client false` with the reason "generating a
  ;; typed wrapper for the endpoint that describes the wrappers is circular and
  ;; useless". The consumer showed that is not the fact — nothing is circular at
  ;; runtime, and generating it would REMOVE the two request paths they
  ;; hand-write precisely because it cannot be generated.
  ;;
  ;; The actual fact is about what the endpoint ANSWERS. It is checkable,
  ;; positive rather than an opt-out, and about the endpoint rather than about
  ;; a consumer — which is why it replaces the flag instead of joining it.
  (let [st (-> (store/empty-store)
               (store/ingest 'doc.api
                             (str "(ns doc.api)\n\n"
                                  "(defn ^{:http/method :get :rest/path \"/api/things\""
                                  " :http/auth :public :rest/response [:map]}\n"
                                  "  things \"JSON, the default.\" [_] {:status 200})\n\n"
                                  "(defn ^{:http/method :get :rest/path \"/api/contracts\""
                                  " :http/auth :public :rest/response :string"
                                  " :rest/media-type \"application/edn\"}\n"
                                  "  contract \"EDN, not JSON.\" [_] {:status 200})\n")))
        by (into {} (map (juxt :fn-name identity))
                 (:wrappers (cljs/client-wrapper-specs st)))]

    (testing "the media type rides the spec, defaulting to JSON when unsaid"
      (is (= "application/json" (:media-type (by 'things))) (pr-str (by 'things)))
      (is (= "application/edn" (:media-type (by 'contract))) (pr-str (by 'contract))))

    (testing "a JSON endpoint renders the decode it always did"
      (let [src (#'cljs/render-wrapper (by 'things))]
        (is (str/includes? src ".json") src)
        (is (str/includes? src "json-transformer") src)))

    (testing "and a NON-json endpoint reads TEXT and does not json-decode it"
      (let [src (#'cljs/render-wrapper (by 'contract))]
        (is (str/includes? src ".text") src)
        (is (not (str/includes? src ".json"))
            (str "`.json` on application/edn fails on the first character: " src))
        (is (not (str/includes? src "json-transformer"))
            (str "and there is no JSON boundary to transform across: " src))))))

(deftest a-BUILDER-refuses-params-its-contract-does-not-name
  ;; The measured failure: a consumer passed the map it had — its own route
  ;; params — and `:slug` fell through to the query string of a service that
  ;; never asked for it. Correct response, correct render, visible only in
  ;; somebody else's access log.
  ;;
  ;; The server closes the same question (D-closed-request). This is the same
  ;; refusal one layer earlier, where it names the key without a round trip —
  ;; and it needs no malli, so it stays in the `:cljc` builder namespace that
  ;; requires NOTHING.
  (let [spec {:fn-name 'get-order :method :get :path "/api/orders/:id"
              :endpoint 'shop.api/get-order
              :request  {:kind :var :sym 'shop.contracts/query :ns 'shop.contracts}
              :request-keys #{:depth}
              :response {:kind :none}}
        src  (cljs/render-request-ns 'shop.client.api [spec] nil)]

    (testing "the builder carries the declared key set and refuses anything else"
      (is (re-find #"remove #\{" src) src)
      (is (re-find #":depth" src) src))

    (testing "a PATH SEGMENT is allowed even when the contract omits it"
      ;; the builder needs it to build the url at all. Whether the contract
      ;; ought to name it is the SERVER's question, and closing there already
      ;; asks it — a builder inventing a stricter rule than the boundary would
      ;; refuse requests the boundary accepts
      (is (re-find #"#\{[^}]*:id" src)
          (str "a captured segment must stay allowed or the url cannot be"
               " built: " src)))

    (testing "the guard names the endpoint and the offending keys"
      (is (re-find #"get-order-request" src) src)
      (is (re-find #"does not name" src) src))

    (testing "and it still requires NOTHING — the whole reason it is not malli"
      (is (not (re-find #":require" src)) src)
      (is (not (re-find #"m/validate" src)) src))
(testing "the :cljs WRAPPER gets the same guard, because it had the same hole"
      ;; its `m/validate` looked like the check that was missing and never was
      ;; one: malli map schemas are OPEN, so an undeclared key passed it and
      ;; always had. A fix reaching only one renderer would have charged every
      ;; store a regeneration and reached some of them.
      (let [w (cljs/render-client-ns
               'shop.client
               [{:fn-name 'get-order :method :get :path "/api/orders/:id"
                 :endpoint 'shop.api/get-order
                 :request  {:kind :var :sym 'shop.contracts/query :ns 'shop.contracts}
                 :request-keys #{:depth}
                 :response {:kind :none}}])]
        (is (re-find #"does not name" w) w)
        (is (< (.indexOf w "does not name") (.indexOf w "m/validate"))
            (str "the undeclared check must run BEFORE the typed one, or a"
                 " caller learns about a type error in a key that should never"
                 " have been sent: " w)))))

  (testing "an endpoint whose declared keys are UNKNOWN gets no guard"
    ;; degrade safely: a non-literal or non-map schema means generation cannot
    ;; enumerate what is allowed, and a guard built from a guess would refuse
    ;; correct calls. No check beats a wrong one.
    (let [spec {:fn-name 'ping :method :get :path "/api/ping"
                :endpoint 'shop.api/ping
                :request {:kind :none} :response {:kind :none}}]
      (is (not (re-find #"remove #\{"
                        (cljs/render-request-ns 'shop.client.api [spec] nil)))))))

(deftest fetching-a-contract-is-BOUNDED
  ;; The same omission as `slopp.http.jwks/fetch-jwks!`, found in the same
  ;; sweep: `:http/timeout-ms` is optional on the port, so leaving it out means
  ;; wait forever. Less dangerous here — this is a dev-time tool rather than a
  ;; request path — and worth fixing for the reason that makes it findable at
  ;; all: an author running `generate_client` against a host that accepts and
  ;; stalls gets a tool that never returns and never says why.
  (let [seen (atom nil)]
    (cljs/fetch-contract "https://pub.test/api/contracts"
                         (fn [req]
                           (reset! seen req)
                           {:http/status 200 :http/headers {}
                            :http/body "{:slopp/contract-version 2 :endpoints []}"}))
    (is (pos-int? (:http/timeout-ms @seen))
        (str "an unbounded fetch hangs the tool: " (pr-str @seen)))))

(deftest a-generated-CHECK-decodes-by-what-the-endpoint-ANSWERS
  ;; The wrapper learned this when `:rest/media-type` landed: `.json` on an EDN
  ;; body fails on the first character. The CHECK kept transforming through
  ;; `mt/json-transformer` unconditionally, which is the mirrored mistake and
  ;; quieter — a json transformer exists to undo what JSON does to a value, and
  ;; EDN does none of it. Applied to a document that never went through JSON it
  ;; is at best a no-op and at worst a second opinion about types nobody asked
  ;; for.
  ;;
  ;; It stayed invisible while slopp's own EDN endpoint declared `:string`,
  ;; because a string survives any transformer. Giving that endpoint a real
  ;; document schema is what made the question reachable.
  (let [spec (fn [mt] {:fn-name 'thing :method :get :path "/api/thing"
                       :endpoint 'shop.api/thing
                       :media-type mt
                       :request {:kind :none}
                       :response {:kind :var :sym 'shop.contracts/thing
                                  :ns 'shop.contracts}})
        gen  (fn [mt] (cljs/render-check-ns 'shop.client.checks [(spec mt)]))]

    (testing "a JSON endpoint's check decodes through the json transformer"
      (is (re-find #"mt/json-transformer" (gen "application/json"))))

    (testing "an EDN endpoint's check validates what it was given"
      (let [edn (gen "application/edn")]
        (is (not (re-find #"json-transformer" edn))
            (str "EDN never went through JSON, so undoing JSON is a second"
                 " opinion about types nobody asked for: " edn))
        (is (re-find #"m/validate shop\.contracts/thing" edn) edn)))

    (testing "and a check with no declared media type still assumes JSON"
      ;; the default everywhere else in generation, kept so nothing changes for
      ;; an endpoint that never said
      (is (re-find #"mt/json-transformer" (gen nil))))))
