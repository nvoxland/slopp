(ns slopp.rules.webapp-test
  "The browser app's DONE-grain advisories, driven through real sessions.

  Both need setup a source-only fixture cannot carry — a capability, and either
  a `:module-platform` declaration or a done BASELINE to diff an episode
  against — which is why they are `^:external` and why the registry records a
  `:selftest-note` saying so.

  Fixtures enable `webapp`, which turns `http` on with it. That is not
  incidental: `webapp-client-routes-consequences-check` gated on NOTHING until wave 4, so
  it fired on every store while the arms report claimed an owner controlled it
  — the same defect slopp-ui measured in rest's contract advisories. A fixture
  that enables the capability is what makes the gating observable.

  Neighbours: `slopp.edit.webapp-test` covers the write-grain half." (:require [clojure.test :refer [deftest is testing]] [slopp.ops :as ops] [slopp.ops.external :as external] [slopp.rules.webapp :as rules.webapp] [slopp.store :as store]))

(deftest ^:external declaring-CLIENT-ROUTES-says-what-it-changed
  ;; `:webapp/client-routes` is the biggest behavioural change available in one piece of
  ;; metadata: every path under the prefix stops being a 404 and starts being a
  ;; 200, with not-found moving into the client. Nothing said so — the change
  ;; was noticed only because two existing tests asserted the old status.
  ;;
  ;; It fires once, for the episode that DECLARED it, so it cannot decay into a
  ;; standing warning.
  ;;
  ;; The fixture is a `def` holding the whole document, which is what a served
  ;; document IS now: it carried `:rest/client false` and `:rest/response
  ;; :string` when a page was an endpoint like any other, and both of those
  ;; existed only to undo questions a page should never have been asked.
  (let [sess (external/open!)
        doc  (fn [extra body]
               (str "(def ^{:http/method :get :http/path \"/\" :http/auth :public"
                    extra "}\n"
                    "  doc \"" body "\"\n"
                    "  [:html [:head [:title \"D\"]] [:body [:div {:id \"app\"}]]])"))]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "the browser owns routing here — this turns http on with it")
      (ops/ingest! sess 'browser.ui
                   (str "(ns browser.ui)\n" (doc "" "The document.") "\n"))
      (external/done! sess :label "baseline")
      (testing "adding the declaration states the consequence"
        (ops/edit-replace! sess 'browser.ui 'doc
                           (doc "\n        :webapp/client-routes [\"/store\"]"
                                "The document.")
                           :prompt "the client routes /store")
        (let [f (get-in (external/done! sess :label "client-routes") [:findings :webapp-client-routes-consequences])]
          (is (some #(= 'browser.ui/doc (:form %)) f) (pr-str f))
          (is (re-find #"200" (str (:teach (first f)))) (pr-str f))
          (is (re-find #"(?i)not-found" (str (:teach (first f)))) (pr-str f))))
      (testing "it does NOT re-fire while the declaration merely stands"
        (ops/edit-replace! sess 'browser.ui 'doc
                           (doc "\n        :webapp/client-routes [\"/store\"]"
                                "The document, reworded.")
                           :prompt "touch the form without touching the declaration")
        (let [f (get-in (external/done! sess :label "again") [:findings :webapp-client-routes-consequences])]
          (is (nil? f) (pr-str f))))
      (finally (ops/close! sess)))))

(deftest ^:external a-page-reaching-cljs-cannot-be-opened-and-done-says-so
  ;; The write gate checks the entry's OWN namespace. That is the shallow half:
  ;; an entry can sit in :cljc and reach a :cljs view, passing the gate and
  ;; failing the tool — which is exactly where a real app lands, because the
  ;; entry is small and the views are where the code is.
  ;;
  ;; An ADVISORY rather than a gate, and the reason is structural. The reach
  ;; changes when ANOTHER form moves: declaring some namespace :cljs today can
  ;; strand an entry written last week, and no write to that entry ever
  ;; happens. A per-form write gate cannot see it, however it is written.
  ;;
  ;; Both setup steps carry a control. The first cut of this test guarded only
  ;; the platform declaration and the step that had silently failed was the
  ;; INGEST, a line above it — refused by the module gate, which left the
  ;; advisory reporting nothing over a namespace that had no entry in it. Two
  ;; assertions passed vacuously before either one was doubted.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "the browser owns routing here — this turns http on with it")
      (ops/ingest! sess 'demo.app.views
                   "(ns demo.app.views)\n\n(defn page-view \"V.\" [s] [:div (str s)])\n")
      (let [i (ops/ingest! sess 'demo.app
                           (str "(ns demo.app (:require [demo.app.views :as views]))\n\n"
                                "(defn ^:app/entry app \"A.\" []"
                                " {:state (atom {}) :view views/page-view})\n"))]
        (is (nil? (:error i)) (pr-str i))
        (is (some #{'app} (map :name (store/forms (:store @sess) 'demo.app)))
            "the entry has to EXIST before anything about its reach means anything"))

      (let [ids (fn [] (mapv :id (store/forms (:store @sess) 'demo.app)))]
        (testing "reaching only portable code is clean"
          (is (empty? (rules.webapp/webapp-page-reach-check sess (:store @sess) (ids)))))

        (testing "declaring the VIEWS :cljs strands the entry, and the advisory names both"
          (let [d (ops/module-platform! sess "demo.app.views" "cljs"
                                        :prompt "someone moves the views to the client")]
            (is (nil? (:error d)) (pr-str d))
            (is (= :cljs (store/platform-for (:store @sess) 'demo.app.views))
                "the fixture is only a fixture once the platform actually says :cljs"))
          (let [r (rules.webapp/webapp-page-reach-check sess (:store @sess) (ids))]
            (is (seq r) "the entry is now unopenable and no write to it happened")
            (is (= 'demo.app/app (:form (first r))))
            (is (some #{'demo.app.views} (:cljs (first r)))
                "naming the namespace that stranded it is the finding — the entry is fine"))))
      (finally (ops/close! sess)))))

(deftest the-webapp-section-reports-what-a-BROWSER-APP-IS
  ;; The fifth thing a capability is — PORT, ADAPTER, FAKE, GATES, SURFACE
  ;; REPORT — and the only one `webapp` has never had. Its readers are the
  ;; three the catalog names: the agent, a consuming tool, and the HUMAN, who
  ;; does not read the code and needs a rendered picture of what the
  ;; application is.
  ;;
  ;; Three questions, which is what a browser app is: what screens are there,
  ;; what can a reader DO, and how much of this is outside the fast loop.
  (let [src (str "(ns shop.ui)\n\n"
                 "(def thing-api \"E.\" {:http/method :get :http/path \"/api/thing/:id\"})\n\n"
                 "(defn ^{:webapp/path \"/things\"} things \"Every thing.\" [_p] [:p \"things\"])\n"
                 "(defn ^{:webapp/path \"/things/:id\"} thing \"One thing.\" [_p]\n"
                 "  [:p (:http/path thing-api)])\n\n"
                 "(defn ^{:http/method :get :http/path \"/p/:slug\"} doc \"D.\" [_] {})\n\n"
                 "(defn ^:app/entry app \"A.\" []\n"
                 "  {:webapp/state (atom {})\n"
                 "   :webapp/actions {:thing/rename {}\n"
                 "                    :thing/delete {:effectful? true}\n"
                 "                    :project/switch {:leaves? true}}})\n")
        on  (assoc-in (store/ingest (store/empty-store) 'shop.ui src)
                      [:config "capabilities" :values "webapp.enabled"] "true")]

    (testing "inert until the capability is on"
      ;; the reading side of the same inertness the gates have: a project with
      ;; no browser app must not be DESCRIBED as having one
      (is (empty? (:screens (rules.webapp/webapp-report
                             (store/ingest (store/empty-store) 'shop.ui src))))))

    (testing "every page's own MARKER is a screen row, naming what renders it"
      ;; the addresses used to be read out of a `:webapp/routes` table inside
      ;; the entry fn — a value, so a table built in pieces read as fewer
      ;; screens than the app had. Metadata on a name cannot be half-declared
      (let [rows (:screens (rules.webapp/webapp-report on))]
        (is (= ["/things" "/things/:id"] (mapv :path rows)) (pr-str rows))
        (is (= '[shop.ui/things shop.ui/thing] (mapv :screen rows)))
        (is (= ["Every thing." "One thing."] (mapv :doc rows))
            "what a page IS, which the path alone never says")
        (is (every? #(= :screen (:kind %)) rows)
            "rows are self-describing, so a renderer that knows nothing about
             this capability can still draw one")))

    (testing "a row names the ENDPOINTS its page calls, and says nothing when it calls none"
      ;; graph-derived, so it cannot disagree with the code. The row used to
      ;; carry `:request` — the name of a builder var — and `:loads`, a url
      ;; taken from whatever single path that builder named; a page makes as
      ;; many calls as it likes and names descriptors to make them
      (let [by (into {} (map (juxt :path identity))
                     (:screens (rules.webapp/webapp-report on)))]
        (is (= [{:endpoint 'shop.ui/thing-api :method :get :path "/api/thing/:id"}]
               (:calls (by "/things/:id")))
            (pr-str (by "/things/:id")))
        (is (nil? (:calls (by "/things"))) (pr-str (by "/things")))))

    (testing "actions say what a reader can DO, and which kind each is"
      ;; the three kinds are the app's own declaration, and a human asking what
      ;; a screen does needs the effectful ones visible — those are the controls
      ;; that reach a server
      (let [by (into {} (map (juxt :action identity))
                     (:actions (rules.webapp/webapp-report on)))]
        (is (= #{:thing/rename :thing/delete :project/switch} (set (keys by))))
        (is (true? (:effectful? (by :thing/delete))))
        (is (true? (:leaves? (by :project/switch))))
        (is (not (:effectful? (by :thing/rename))))))

    (testing "there is no :session-loads key, because there is no such declaration"
      ;; it answered what an app fetched that belonged to no screen. A page
      ;; ASKS for what it needs now and `ask!` is start-if-absent, so a load
      ;; belonging to everyone is asked for by every page that shows it and
      ;; found already there after the first
      (is (= #{:screens :actions :unreadable :cljs}
             (set (keys (rules.webapp/webapp-report on))))
          (pr-str (rules.webapp/webapp-report on))))

    (testing "and the :cljs count, which is the goal stated as a number"
      ;; "an app that opts into webapp writes NO ClojureScript" is an aspiration
      ;; until a store can answer how much it writes. Zero here, and a store
      ;; that has drifted says so
      (is (= 0 (:cljs (rules.webapp/webapp-report on)))))))

(deftest a-screens-REQUEST-PATH-is-joined-against-what-this-store-SERVES
  ;; The gap wave 4d created, recorded in `index.crossings` at the moment it was
  ;; created rather than found later: a literal `:href` is resolved against the
  ;; served route table by `http-dangling-route-refs`, and an endpoint
  ;; DESCRIPTOR's `:http/path` is the same kind of claim about the same table,
  ;; with nothing reading it.
  ;;
  ;; So an app could name an endpoint this store does not serve, and the only
  ;; symptom is a load that always fails — at a url that routes, on a screen
  ;; that renders, in an app where every other pane works.
  ;;
  ;; **The join is EQUALITY, not `router/match`.** A descriptor's path is a
  ;; PATTERN in the same grammar as `:http/path` — `/api/things/:id`, resolved
  ;; against params only when a request is built — so matching it as though it
  ;; were a concrete url would ask the wrong question and answer nil for every
  ;; parameterized endpoint in the store.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:http/method :get :http/path \"/api/things\"\n"
                 "        :http/auth :public :rest/response :string}\n"
                 "  things \"T.\" [_] {:status 200 :body \"[]\"})\n\n"
                 "(defn ^{:http/method :get :http/path \"/api/things/:id\"\n"
                 "        :http/auth :public :rest/response :string}\n"
                 "  thing \"T.\" [_] {:status 200 :body \"{}\"})\n\n"
                 "(def list-ep {:http/method :get :http/path \"/api/things\"})\n\n"
                 "(def detail-ep {:http/method :get :http/path \"/api/things/:id\"\n"
                 "                :http/params #{:id}})\n\n"
                 "(def typo-ep {:http/method :get :http/path \"/api/thing\"})\n\n"
                 "(def far-ep {:http/method :get\n"
                 "             :http/path \"https://api.example.com/v1/rates\"})\n")
        on  (assoc-in (store/ingest (store/empty-store) 'shop.ui src)
                      [:config "capabilities" :values "webapp.enabled"] "true")]

    (testing "a descriptor that names a declared endpoint is served"
      (let [unserved (set (map :path (rules.webapp/request-paths-unserved on)))]
        (is (not (contains? unserved "/api/things")) (pr-str unserved))
        (is (not (contains? unserved "/api/things/:id"))
            (str "a PARAMETERIZED endpoint must join by its pattern — resolving"
                 " it as a concrete url answers nil for every one of them: "
                 (pr-str unserved)))))

    (testing "and a path nothing serves is reported, with the form that names it"
      (let [rows (rules.webapp/request-paths-unserved on)]
        (is (= ["/api/thing"] (mapv :path rows)) (pr-str rows))
        (is (= 'shop.ui/typo-ep (:form (first rows))) (pr-str rows))))

    (testing "an ABSOLUTE url is somebody else's server and is left alone"
      ;; the escape that keeps this worth having: an app calling a third-party
      ;; API declares a whole url, and reporting it would make the advisory
      ;; noise on every app that talks to anything
      (is (not (contains? (set (map :path (rules.webapp/request-paths-unserved on)))
                          "https://api.example.com/v1/rates"))))

    (testing "a RESOLVED request is not scanned at all"
      ;; `slopp.rest.endpoint/request` returns `:http/url`, a finished address
      ;; with no pattern to join. What this reads is the DESCRIPTOR, which is a
      ;; `def` and therefore always readable — so the join stopped depending on
      ;; where an app happens to assemble a map.
      (let [src5 (str "(ns shop.five)\n\n"
                      "(defn hub-request \"R.\" [_p]\n"
                      "  {:http/url \"/api/projects\" :webapp/base \"\"})\n")
            st   (assoc-in (store/ingest (store/empty-store) 'shop.five src5)
                           [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (= [] (rules.webapp/request-paths-unserved st))
            (pr-str (rules.webapp/request-paths-unserved st)))))

    (testing "and a form marked ^:http/external-path is skipped WHOLE"
      ;; the escape an absolute url cannot cover, reported by the app that
      ;; needed it: its API is proxied by the PROJECT server under the same
      ;; mount point, so the path is real, served, and not this store's — and
      ;; it cannot be written in full because the prefix is the slug, known
      ;; only at runtime. Eight findings with nothing to do about them is the
      ;; permanent-finding failure: a list nobody can clear is a list everybody
      ;; skims.
      ;;
      ;; GENERATION declares it on every descriptor built from a foreign
      ;; contract, so the escape is never hand-edited onto generated code —
      ;; which the next generation would drop silently.
      (let [src2 (str "(ns shop.far)\n\n"
                      "(def ^{:http/external-path \"the project server proxies /api/*\"}\n"
                      "  far-ep {:http/method :get :http/path \"/api/modules\"})\n")
            st   (assoc-in (store/ingest (store/empty-store) 'shop.far src2)
                           [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (= [] (rules.webapp/request-paths-unserved st))
            (str "a declared crossing was still reported, so the only escape"
                 " available to a proxied API is one it cannot use: "
                 (pr-str (rules.webapp/request-paths-unserved st))))))

    (testing "the advisory is INERT until the store opts into webapp"
      ;; the reading side of the same inertness every webapp rule has
      (let [off (store/ingest (store/empty-store) 'shop.ui src)]
        (is (empty? (rules.webapp/webapp-request-paths-are-served-check
                     nil off nil))
            "a project with no browser app must not be told about one")
        (is (seq (rules.webapp/webapp-request-paths-are-served-check nil on nil))
            "and with it on, the finding is there — or the line above is vacuous")))

    (testing "the finding NAMES the paths this store does serve"
      ;; a complaint that says only "not served" leaves an author diffing two
      ;; lists by eye, and the near-miss is the common case — /api/thing for
      ;; /api/things
      (let [f (first (rules.webapp/webapp-request-paths-are-served-check nil on nil))]
        (is (re-find #"/api/thing\b" (:teach f)) (:teach f))
        (is (re-find #"/api/things" (:teach f)) (:teach f))))))

(deftest the-CLJS-a-webapp-still-writes-is-reported-at-DONE-not-only-on-request
  ;; The capability's goal stated as a number — "an app that opts into `webapp`
  ;; writes NO ClojureScript" — has been readable since `query_surface` gained
  ;; `:cljs`. Readable is not the same as REPORTED: nobody asks a surface report
  ;; on a normal day, so a store that drifts from zero to five drifts silently
  ;; and the number is only ever consulted by whoever already suspects.
  ;;
  ;; The consuming app's own score, sent unprompted once they read the metric:
  ;; six hand-written `:cljs` forms, four of which slopp had an answer for and
  ;; two of which it did not. That exchange is what this advisory automates —
  ;; not the fixing, the ASKING.
  ;;
  ;; Advisory and never a refusal: sketching in a `:cljs` namespace is
  ;; legitimate, and a browser-only library binding may have no portable form at
  ;; all. What is not legitimate is not knowing.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:http/method :get :http/path \"/\" :http/auth :public\n"
                 "        :rest/response :string :webapp/client-routes [\"/things\"]}\n"
                 "  doc \"D.\" [_] {:status 200 :body \"<html></html>\"})\n")
        cljs (str "(ns shop.sketch)\n\n(defn draw \"D.\" [x] x)\n")
        st   (-> (store/ingest (store/empty-store) 'shop.ui src)
                 (store/ingest 'shop.sketch cljs))
        st   (first (store/record-module-platform st "shop.sketch" :cljs))
        on   (assoc-in st [:config "capabilities" :values "webapp.enabled"] "true")]

    (testing "a store at ZERO says nothing — the goal being met is silence"
      (let [none (assoc-in (store/ingest (store/empty-store) 'shop.ui src)
                           [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (empty? (rules.webapp/webapp-client-code-check nil none nil))
            "an app writing no ClojureScript was told about ClojureScript")))

    (testing "and a store above zero NAMES each namespace"
      (let [found (rules.webapp/webapp-client-code-check nil on nil)]
        (is (= '[shop.sketch] (mapv :ns found)) (pr-str found))
        (is (re-find #"(?i)compil" (:teach (first found)))
            (str "the finding must say what a :cljs namespace COSTS — that it is"
                 " outside the in-image loop and verified by compiling alone: "
                 (pr-str found)))))

    (testing "INERT until the store opts into webapp, like every rule here"
      ;; a store with browser code and no browser app is an ordinary cljs
      ;; project, and telling it about a goal it never adopted is noise
      (is (empty? (rules.webapp/webapp-client-code-check nil st nil))
          (pr-str (rules.webapp/webapp-client-code-check nil st nil))))))

(deftest the-webapp-section-survives-a-declaration-it-cannot-READ
  ;; `webapp-report`'s own docstring says it: **no member may take the report
  ;; down**, citing `rules.rest/contracts-report`, where one contract that threw
  ;; made nine endpoints unreadable on the day a store enabled the capability.
  ;;
  ;; It happened here, in the function that cites it. An app may name a VAR
  ;; rather than write a literal —
  ;;
  ;;   {:webapp/routes routes :webapp/session-loads session-loads}
  ;;
  ;; — and every extractor seq'd the value, so a symbol threw
  ;; `Don't know how to create ISeq from: clojure.lang.Symbol`. Not a refusal
  ;; naming a store condition: a raw throw, taking `query_surface` with it, and
  ;; with it `:cli`, `:http` and `:rest`, which have nothing to do with any of
  ;; this.
  ;;
  ;; Reported by the store that could no longer read the number `D-webapp` names
  ;; as this capability's target — so the throw hid the metric the wave is
  ;; scored by, in the tool that reports it.
  ;;
  ;; **Two of the three declarations have since been deleted rather than
  ;; fixed**, which is the better answer to a value that cannot be read: the
  ;; addresses are metadata on the pages themselves, and session loads stopped
  ;; being declared at all. `:webapp/actions` is what is left, and it is still
  ;; a map literal an app may name a var for.
  (let [src (str "(ns shop.six)\n\n"
                 "(def actions \"A.\" {})\n\n"
                 "(defn ^{:webapp/path \"/things\"} things \"T.\" [_p] [:p \"t\"])\n\n"
                 "(defn ^:app/entry app \"A.\" []\n"
                 "  {:webapp/state   (atom {})\n"
                 "   :webapp/actions actions})\n\n"
                 ;; a second, READABLE declaration in the same store — without
                 ;; it a report that returned empty would look like it coped
                 "(def other-actions \"O.\" nil)\n\n"
                 "(defn readable \"R.\" []\n"
                 "  {:webapp/actions {:thing/save {:effectful? true}}})\n")
        on  (assoc-in (store/ingest (store/empty-store) 'shop.six src)
                      [:config "capabilities" :values "webapp.enabled"] "true")]

    (testing "a declaration naming a VAR does not throw"
      (is (map? (rules.webapp/webapp-report on))
          "a store that names its actions by var made the whole surface unreadable"))

    (testing "and what IS readable is still reported"
      ;; the half that makes skipping honest rather than a shrug: an
      ;; unreadable declaration costs its own rows and nothing else
      (let [r (rules.webapp/webapp-report on)]
        (is (= ["/things"] (mapv :path (:screens r))) (pr-str r))
        (is (= [:thing/save] (mapv :action (:actions r))) (pr-str r))
        (is (some #(re-find #"actions" %) (:unreadable r)) (pr-str r))))

    (testing "and the ADDRESSES cannot be named by a var at all any more"
      ;; the class of failure removed rather than guarded. A page's address is
      ;; metadata on its name, so there is no value for an app to compute and
      ;; nothing for this reader to fail to read
      (let [one (str "(ns shop.one)\n\n(def v \"V.\" nil)\n\n"
                     "(defn ^:app/entry app \"A.\" [] {:webapp/state v})\n")
            st  (assoc-in (store/ingest (store/empty-store) 'shop.one one)
                          [:config "capabilities" :values "webapp.enabled"] "true")
            r   (rules.webapp/webapp-report st)]
        (is (map? r) "an entry naming its state by var still takes the report down")
        (is (= [] (:screens r)) (pr-str r))
        (is (= [] (:unreadable r))
            (str "there is no address declaration left to be unreadable: "
                 (pr-str r)))))))

(deftest what-the-report-CANNOT-read-is-named-at-every-grain
  ;; The hole in the fix for the throw. Turning a crash into a SKIP was right;
  ;; naming the skip only at SECTION grain was not, and the sentence that
  ;; justified the section-grain naming turns on it one step further in:
  ;;
  ;;   A section quietly missing reads as a store that declares nothing there.
  ;;
  ;; An ENTRY quietly missing reads as an app that declares FEWER than it does.
  ;; And the actions case is the worse of the two, because `[]` is an
  ;; affirmative claim of emptiness rather than an absence — nothing in that
  ;; answer distinguishes it from a store with no actions at all.
  ;;
  ;; Reported by the store that would have used this to ask "does the surface
  ;; agree with what I declared?", which is the question the tool is for. It
  ;; said yes about routes and quietly no about the other two.
  ;;
  ;; **Two of the three grains have since stopped existing**, and that is the
  ;; better ending than a guard: the addresses are metadata on the pages, so
  ;; there is no value for an app to compute, and session loads are not
  ;; declared at all. What a computed ROUTE TABLE cost this reader — one
  ;; finding for the table, never one per element of the call form it walked —
  ;; is recorded in `page-routes`' own docstring, where the declaration went.
  (let [src (str "(ns shop.seven)\n\n"
                 "(def actions \"A.\" {:thing/save {:effectful? true}})\n"
                 "(defn ^{:webapp/path \"/things\"} things \"T.\" [_p] [:p \"t\"])\n\n"
                 "(defn ^:app/entry app \"A.\" []\n"
                 "  {:webapp/state (atom {})\n"
                 ;; a whole declaration naming a VAR
                 "   :webapp/actions actions})\n\n"
                 ;; a readable one elsewhere, with ONE unreadable entry in it
                 "(defn other \"O.\" []\n"
                 "  {:webapp/actions {\"thing/save\" {:effectful? true}\n"
                 "                    :thing/rename {}}})\n")
        on  (assoc-in (store/ingest (store/empty-store) 'shop.seven src)
                      [:config "capabilities" :values "webapp.enabled"] "true")
        r   (rules.webapp/webapp-report on)]

    (testing "the readable half is still reported, which is what makes skipping honest"
      (is (= ["/things"] (mapv :path (:screens r))) (pr-str r))
      (is (= [:thing/rename] (mapv :action (:actions r))) (pr-str r)))

    (testing "a whole DECLARATION it cannot read is named"
      (is (some #(re-find #"webapp/actions" %) (:unreadable r))
          (str "an empty :actions is an affirmative claim of emptiness — there"
               " is nothing in it a reader could tell from a store with no"
               " actions: " (pr-str (:unreadable r)))))

    (testing "and a single unreadable ENTRY inside a readable one is named too"
      (is (some #(re-find #"thing/save" %) (:unreadable r))
          (str "one action vanished from an otherwise-complete list: "
               (pr-str (:unreadable r)))))

    (testing "the note SHOWS what it found, so the reader can see why"
      ;; "not a literal" is a rule; the value is the evidence, and it is what
      ;; tells an author whether they meant it
      (is (some #(re-find #"actions" %) (:unreadable r)) (pr-str (:unreadable r))))

    (testing "a store it can read entirely says NOTHING — silence is the good case"
      (let [clean (assoc-in (store/ingest (store/empty-store) 'shop.eight
                                          (str "(ns shop.eight)\n\n"
                                               "(defn ^{:webapp/path \"/things\"} things \"T.\" [_p] [:p \"t\"])\n\n"
                                               "(defn ^:app/entry app \"A.\" []\n"
                                               "  {:webapp/state (atom {})})\n"))
                            [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (empty? (:unreadable (rules.webapp/webapp-report clean)))
            (pr-str (:unreadable (rules.webapp/webapp-report clean))))))))

(deftest a-store-that-MOUNTS-is-the-one-that-calls-mount-not-the-one-that-requires-dom
  ;; The signal behind the generated browser entry: `build!` must not emit a
  ;; second mount beside a store's own. What counts as "its own" has to be the
  ;; CALL, and the two cheap approximations are both wrong in a way that costs
  ;; something real.
  ;;
  ;; By NAMESPACE NAME (the guard this replaced): only `native.client` counted,
  ;; so every store whose entry is called anything else got a silent second
  ;; mount — which is how this arrived, from a store that compiled the bundle
  ;; and counted two bootstraps.
  ;;
  ;; By REQUIRE: `slopp.webapp.dom` also publishes `click-data` and
  ;; `typed-value`, which an event handler reads without mounting anything.
  ;; That store would be told it owns an entry it never wrote, and silently
  ;; lose the generation the capability exists to give it.
  (let [mounts  (str "(ns shop.client.app\n"
                     "  (:require [slopp.webapp.dom :as dom]\n"
                     "            [shop.ui :as ui]))\n\n"
                     "(defn ^:export main \"Mount it.\" [] (dom/mount! (ui/app)))\n")
        ;; requires the SAME namespace, mounts nothing
        reads   (str "(ns shop.client.handlers\n"
                     "  (:require [slopp.webapp.dom :as dom]))\n\n"
                     "(defn on-click \"The clicked row.\" [e] (:id (dom/click-data e)))\n")
        st-of   (fn [n src] (-> (store/empty-store) (store/ingest n src)))]
    (testing "a form CALLING dom/mount! is a store that mounts its own app"
      (is (= '[shop.client.app]
             (rules.webapp/own-mount-nses (st-of 'shop.client.app mounts)))))

    (testing "requiring dom for something else is NOT mounting"
      ;; the precision that makes this safe to act on: get it wrong here and
      ;; an app that writes no ClojureScript stops getting an entry, which is
      ;; the capability's whole promise
      (is (= [] (rules.webapp/own-mount-nses (st-of 'shop.client.handlers reads)))))

    (testing "a store that does neither has nothing to collide with"
      (is (= [] (rules.webapp/own-mount-nses
                 (st-of 'shop.plain "(ns shop.plain)\n\n(defn f \"F.\" [x] x)\n")))))))

(deftest a-screens-request-is-joined-against-what-the-store-SERVES
  ;; The api/content split gave routes two markers, and this join asks a
  ;; question that does not care which: **does this store serve the thing the
  ;; screen asks for?** Serving is serving.
  ;;
  ;; Read rather than assumed, which is why this is its own test. The four
  ;; `rules.webapp` forms reading a path marker split two ways, and only these
  ;; two take both kinds:
  ;;
  ;;   request-paths-unserved / …-are-served-check   BOTH — a screen may fetch
  ;;                                                 an api OR an asset
  ;;   client-routes-unserved / derived-…-prefixes   :http/path ONLY — the SPA
  ;;                                                 mount is a document, and an
  ;;                                                 api must never be picked
  ;;                                                 as one
  ;;
  ;; Restricting this one to `:rest/path` would report a screen fetching a
  ;; served asset as unserved — a false positive in the report whose whole
  ;; value is that its findings can be discharged.
  (let [st (-> (store/empty-store)
               (store/record-config-put "capabilities" :manifest "webapp.enabled" "true") first
               (store/ingest 'shop.api
                             (str "(ns shop.api)\n\n"
                                  "(defn ^{:rest/path \"/api/things\" :http/method :get"
                                  " :http/auth :public :rest/response [:map]}\n"
                                  "  things \"T.\" [_] {:status 200})\n"))
               (store/ingest 'shop.assets
                             (str "(ns shop.assets)\n\n"
                                  "(defn ^{:http/path \"/css/app.css\" :http/method :get"
                                  " :http/auth :public}\n"
                                  "  sheet \"S.\" [_] {:status 200})\n"))
               (store/ingest 'shop.ui
                             (str "(ns shop.ui)\n\n"
                                  "(def ep-a {:http/method :get"
                                  " :http/path \"/api/things\"})\n\n"
                                  "(def ep-b {:http/method :get"
                                  " :http/path \"/css/app.css\"})\n\n"
                                  "(def ep-c {:http/method :get"
                                  " :http/path \"/api/nope\"})\n")))]

    (testing "an api this store declares is served"
      (is (not-any? #(= "/api/things" (:path %)) (rules.webapp/request-paths-unserved st))
          (pr-str (rules.webapp/request-paths-unserved st))))

    (testing "and so is CONTENT — a screen may legitimately fetch an asset"
      (is (not-any? #(= "/css/app.css" (:path %)) (rules.webapp/request-paths-unserved st))
          (str "restricting the join to :rest/path reports a served asset as"
               " missing, which is a finding nobody can discharge: "
               (pr-str (rules.webapp/request-paths-unserved st)))))

    (testing "a path nothing declares is still reported, or the join says nothing"
      (is (= ["/api/nope"] (mapv :path (rules.webapp/request-paths-unserved st)))
          (pr-str (rules.webapp/request-paths-unserved st))))))

(deftest a-PAGE-declares-its-own-address-and-the-store-can-always-read-it
  ;; The change this whole wave is about, at its smallest. A client route used
  ;; to live in a `:webapp/routes` VECTOR inside the `^:app/entry` fn's return
  ;; value — so reading it meant scanning map literals, a table built in pieces
  ;; was unreadable, and `:unreadable` existed to say so.
  ;;
  ;; A marker on the function that renders the page is readable by
  ;; construction: `store/form-name-meta` answers for every form, always. Same
  ;; placement `:rest/path` and `:http/path` already use, and from it the same
  ;; things follow — write gates, `query_surface`, a published document, the
  ;; reference graph.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:webapp/path \"/things\"} things\n"
                 "  \"Every thing, listed.\"\n"
                 "  [_app _params] [:main \"things\"])\n\n"
                 "(defn ^{:webapp/path \"/things/:id\"} thing\n"
                 "  \"One thing.\"\n"
                 "  [_app _params] [:main \"thing\"])\n\n"
                 "(defn plain \"Not a page.\" [x] x)\n")
        st  (store/ingest (store/empty-store) 'shop.ui src)]

    (testing "every marked form is a page, sorted by address"
      (is (= ["/things" "/things/:id"] (mapv :path (rules.webapp/page-routes st)))))

    (testing "each row names the function that renders it, qualified"
      (is (= '[shop.ui/things shop.ui/thing]
             (mapv :page (rules.webapp/page-routes st)))))

    (testing "and its docstring, which is what a reader scanning addresses wants"
      (is (= ["Every thing, listed." "One thing."]
             (mapv :doc (rules.webapp/page-routes st)))))

    (testing "an unmarked form is not a page"
      (is (not-any? #(= 'shop.ui/plain (:page %)) (rules.webapp/page-routes st))))

    (testing "a store with no browser app answers [] and never nil"
      ;; so a caller joining against it never has to tell \"no pages\" from
      ;; \"could not read them\" — which is the question `:unreadable` existed
      ;; to answer and no longer has to
      (is (= [] (rules.webapp/page-routes (store/empty-store)))))

    (testing "a COMPUTED table is not a thing that can happen any more"
      ;; the whole reason this replaces the literal scan. There is no
      ;; expression to compute: a marker is metadata on a name, so a store
      ;; cannot half-declare its pages
      (let [built (store/ingest (store/empty-store) 'shop.two
                                (str "(ns shop.two)\n\n"
                                     "(defn s \"S.\" [_a _p] [:p])\n\n"
                                     "(def table (mapv identity [[\"/x\" s]]))\n\n"
                                     "(defn ^:app/entry app \"A.\" []\n"
                                     "  {:webapp/routes table})\n"))]
        (is (= [] (rules.webapp/page-routes built))
            (str "a table nobody marked is not a page table — the addresses"
                 " are on the FORMS now: "
                 (pr-str (rules.webapp/page-routes built))))))))

(deftest a-PAGE-the-server-does-not-serve-404s-on-a-hard-load
  ;; `:webapp/client-routing`'s blind spot, and the failure the only real
  ;; webapp hit with eight routes at once: every in-app CLICK keeps working,
  ;; because that is client routing, and only a refresh or a shared link 404s.
  ;; So the app is fine for whoever is already inside it and broken for whoever
  ;; was sent a url — the population that never reports it, because they assume
  ;; the link was bad.
  ;;
  ;; **The join is now `router/match` against the SHELL routes**, and that is
  ;; the whole simplification. It used to compare a page path in APP space
  ;; against a hand-written prefix in SERVER space, which is why it tried every
  ;; suffix split of the prefix and guessed. A page declares its address the
  ;; way a document declares its own, so both sides are one coordinate system
  ;; and the question is just: does a shell's pattern cover this?
  (let [src (fn [shell-path pages]
              (str "(ns shop.ui)\n\n"
                   "(def ^{:http/method :get :http/path \"" shell-path "\"\n"
                   "       :http/auth :public :webapp/shell true}\n"
                   "  shell [:html [:head] [:body [:div {:id \"app\"}]]])\n\n"
                   (apply str
                          (map-indexed
                           (fn [i p]
                             (str "(defn ^{:webapp/path \"" p "\"} page" i
                                  " \"P.\" [_a _p] [:main \"p\"])\n"))
                           pages))))
        on  (fn [shell-path pages]
              (assoc-in (store/ingest (store/empty-store) 'shop.ui (src shell-path pages))
                        [:config "capabilities" :values "webapp.enabled"] "true"))]

    (testing "a page a shell's wildcard covers is served"
      (is (= [] (mapv :path (rules.webapp/pages-unserved
                             (on "/store/**" ["/store" "/store/form/:id"])))))
      (is (= [] (mapv :path (rules.webapp/pages-unserved
                             (on "/**" ["/anything" "/deep/er/still"]))))))

    (testing "and one it does not is reported, with the page that declares it"
      (let [rows (rules.webapp/pages-unserved
                  (on "/store/**" ["/store/form/:id" "/settings/:tab"]))]
        (is (= ["/settings/:tab"] (mapv :path rows)) (pr-str rows))
        (is (= 'shop.ui/page1 (:page (first rows))) (pr-str rows))))

    (testing "a store with NO shell reports every page"
      ;; not silence: an app whose browser owns routing and whose server serves
      ;; no shell is the whole failure, not an app with nothing to check
      (let [none (assoc-in (store/ingest
                            (store/empty-store) 'shop.ui
                            (str "(ns shop.ui)\n\n"
                                 "(defn ^{:webapp/path \"/store\"} page0"
                                 " \"P.\" [_a _p] [:main])\n"))
                           [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (= ["/store"] (mapv :path (rules.webapp/pages-unserved none))))))

    (testing "and a store with no pages reports nothing"
      (is (= [] (rules.webapp/pages-unserved (on "/store/**" [])))))

    (testing "the shell's own ROOT is covered, because ** matches zero segments"
      ;; the rule that used to need a second explicit server route per section,
      ;; deleted by the grammar rather than enforced better
      (is (= [] (mapv :path (rules.webapp/pages-unserved
                             (on "/store/**" ["/store"]))))))))

(deftest a-page-row-names-the-ENDPOINTS-it-calls
  ;; What a reader of a browser app actually wants from a page row: not which
  ;; function computes a url, but which endpoint this screen talks to. The old
  ;; answer was `:request` — a var name — plus `:loads`, a url derived from
  ;; whatever single path that var happened to name.
  ;;
  ;; Both were wrong in the same direction. A page makes as many calls as it
  ;; likes now, and it makes them by naming DESCRIPTORS, which are vars. So the
  ;; answer is the reference graph: what this form references that is an
  ;; endpoint. It cannot drift from what the page really calls, because it IS
  ;; what the page really calls.
  (let [st (-> (store/empty-store)
               (store/ingest 'shop.api
                             (str "(ns shop.api)\n\n"
                                  "(def thing \"One thing.\"\n"
                                  "  {:http/method :get :http/path \"/api/thing/:id\"})\n\n"
                                  "(def things \"All of them.\"\n"
                                  "  {:http/method :get :http/path \"/api/things\"})\n"))
               (store/ingest 'shop.ui
                             (str "(ns shop.ui (:require [shop.api :as api]\n"
                                  "                      [slopp.webapp :as webapp]))\n\n"
                                  "(defn ^{:webapp/path \"/things/:id\"} thing-page \"A thing.\"\n"
                                  "  [{:keys [params] :as page}]\n"
                                  "  [:main (webapp/ask! page api/thing {:id (:id params)})\n"
                                  "         (webapp/ask! page api/things {})])\n\n"
                                  "(defn ^{:webapp/path \"/quiet\"} quiet-page \"Calls nothing.\"\n"
                                  "  [_page]\n"
                                  "  [:main \"nothing to load\"])\n")))
        calls (rules.webapp/page-calls st)]

    (testing "every endpoint the page references is named, with its address"
      (is (= [{:endpoint 'shop.api/thing  :method :get :path "/api/thing/:id"}
              {:endpoint 'shop.api/things :method :get :path "/api/things"}]
             (get calls 'shop.ui/thing-page))
          (pr-str calls)))

    (testing "and a page that calls nothing has no entry rather than a wrong one"
      ;; absent, not `[]` — an empty vector is an affirmative claim, and the
      ;; two are the same only when the derivation is complete
      (is (nil? (get calls 'shop.ui/quiet-page)) (pr-str calls)))))

(deftest a-page-in-a-TEST-namespace-is-not-this-applications-surface
  ;; Measured on slopp's own store, by a consumer fetching `/api/webapp/paths`
  ;; against it: the document published `slopp.cljnx-test/fixture-things-page`,
  ;; a fixture written that morning to prove the router finds a marked page.
  ;;
  ;; `app-namespaces` states the rule for every other publisher and says why —
  ;; it is built on `web-endpoint-rows`, "and TEST namespaces are excluded
  ;; there, which is right here too: a fixture endpoint is not surface." The
  ;; page traversal was written without that filter.
  ;;
  ;; **The published document is the mild half.** `page-routes` is also what
  ;; the build reads to generate a browser app's route table, so a fixture
  ;; would ship an address into a real application — reachable, rendering a
  ;; test's hiccup, at a url nobody wrote.
  (let [st (-> (store/empty-store)
               (store/ingest 'shop.ui
                             (str "(ns shop.ui)\n\n"
                                  "(defn ^{:webapp/path \"/things\"} things \"T.\" [_p] [:ul])\n"))
               (store/ingest 'shop.ui-test
                             (str "(ns shop.ui-test)\n\n"
                                  "(defn ^{:webapp/path \"/fixture\"} fixture \"F.\" [_p] [:ul])\n")))]

    (testing "the application's page is published and the fixture is not"
      (is (= ["/things"] (mapv :path (rules.webapp/page-routes st)))
          (pr-str (rules.webapp/page-routes st))))

    (testing "and the same holds for what each page CALLS"
      ;; one traversal's rule applied in two places is one place too many, but
      ;; a fixture page carrying calls would put them in the surface as well
      (let [st2 (-> st
                    (store/ingest 'shop.api
                                  (str "(ns shop.api)\n\n"
                                       "(def thing \"E.\" {:http/method :get :http/path \"/api/t\"})\n"))
                    (store/ingest 'shop.ui-test
                                  (str "(ns shop.ui-test (:require [shop.api :as api]))\n\n"
                                       "(defn ^{:webapp/path \"/fixture\"} fixture \"F.\"\n"
                                       "  [_p] [:ul (:http/path api/thing)])\n")))]
        (is (= {} (rules.webapp/page-calls st2))
            (pr-str (rules.webapp/page-calls st2)))))))
