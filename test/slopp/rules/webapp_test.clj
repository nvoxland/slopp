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
  ;; `:web/client-routes` is the biggest behavioural change available in one piece of
  ;; metadata: every path under the prefix stops being a 404 and starts being a
  ;; 200, with not-found moving into the client. Nothing said so — the change
  ;; was noticed only because two existing tests asserted the old status.
  ;;
  ;; It fires once, for the episode that DECLARED it, so it cannot decay into a
  ;; standing warning.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "the browser owns routing here — this turns http on with it")
      (ops/ingest! sess 'browser.ui
                   (str "(ns browser.ui)\n"
                        "(defn ^{:web/method :get :web/path \"/\" :web/auth :public\n"
                        "        :web/client false :web/response :string}\n"
                        "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})\n"))
      (external/done! sess :label "baseline")
      (testing "adding the declaration states the consequence"
        (ops/edit-replace! sess 'browser.ui 'doc
                           (str "(defn ^{:web/method :get :web/path \"/\" :web/auth :public\n"
                                "        :web/client false :web/response :string\n"
                                "        :web/client-routes [\"/store\"]}\n"
                                "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})")
                           :prompt "the client routes /store")
        (let [f (get-in (external/done! sess :label "client-routes") [:findings :webapp-client-routes-consequences])]
          (is (some #(= 'browser.ui/doc (:form %)) f) (pr-str f))
          (is (re-find #"200" (str (:teach (first f)))) (pr-str f))
          (is (re-find #"(?i)not-found" (str (:teach (first f)))) (pr-str f))))
      (testing "it does NOT re-fire while the declaration merely stands"
        (ops/edit-replace! sess 'browser.ui 'doc
                           (str "(defn ^{:web/method :get :web/path \"/\" :web/auth :public\n"
                                "        :web/client false :web/response :string\n"
                                "        :web/client-routes [\"/store\"]}\n"
                                "  doc \"The document, reworded.\" [_] {:status 200 :body \"<html></html>\"})")
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
                                "(defn ^:web/page app \"A.\" []"
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

(deftest the-declared-CLIENT-ROUTE-TABLE-is-readable-from-the-store
  ;; What step B bought, collected: an app's client routes are a value now, so
  ;; anything can join against them. `^:web/client-path` exists because they were
  ;; not — `rules.http/ui-route-refs` says so in its own docstring:
  ;;
  ;;   teaching the check to SEE the prefixing is not possible in general,
  ;;   because the base arrives through an ordinary function call
  ;;
  ;; That stopped being true when slopp took over the prefixing. A literal in a
  ;; view is a CLIENT ROUTE KEY, and this is the table it is a key into.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn things \"T.\" [_s] [:p \"things\"])\n\n"
                 "(defn ^:web/page app \"A.\" []\n"
                 "  {:webapp/state (atom {})\n"
                 "   :webapp/routes [[\"/store\" things]\n"
                 "                   [\"/store/form/:id\" things]]})\n")
        st  (store/ingest (store/empty-store) 'shop.ui src)]

    (testing "the patterns come back, and nothing else does"
      (is (= ["/store" "/store/form/:id"] (rules.webapp/client-routes st))))

    (testing "a store with no browser app has no client routes"
      ;; and answers EMPTY rather than nil, so a caller joining against it does
      ;; not have to tell "no webapp" apart from "a webapp routing nothing"
      (let [plain (store/ingest (store/empty-store) 'shop.plain
                                "(ns shop.plain)\n\n(defn f \"F.\" [x] x)\n")]
        (is (= [] (rules.webapp/client-routes plain)))))

    (testing "a row whose pattern is not a literal string is SKIPPED, not guessed"
      ;; a computed pattern is one this cannot read, and inventing an answer
      ;; would make the join silently partial — which is worse than a link
      ;; reported as dangling, because that at least gets looked at
      (let [computed (store/ingest (store/empty-store) 'shop.dyn
                                   (str "(ns shop.dyn)\n\n"
                                        "(def base \"/store\")\n\n"
                                        "(defn s \"S.\" [_] [:p])\n\n"
                                        "(defn ^:web/page app \"A.\" []\n"
                                        "  {:webapp/routes [[base s] [\"/real\" s]]})\n"))]
        (is (= ["/real"] (rules.webapp/client-routes computed)))))))

(deftest a-client-route-the-SERVER-does-not-serve-404s-on-a-hard-load
  ;; `:webapp/client-routing`'s blind spot, stated in the inventory itself:
  ;; *nothing compares the client's route table to the server's.* The failure is
  ;; the one the consuming app hit for real — eight client routes that worked on
  ;; every in-app click and 404'd on refresh or on a shared link, because the
  ;; document's declared prefixes and the client's table had drifted apart.
  ;;
  ;; **The comparison is on the prefix's TAIL, and it has to be.** A declared
  ;; prefix is in SERVER space (`/p/:slug/store`) and a client route is in APP
  ;; space (`/store/form/:id`), because the mount point is a deployment fact the
  ;; store cannot know. What is decidable is whether some suffix of the prefix
  ;; is a leading segment of the client route — which is exactly the question
  ;; the generated catch-all answers.
  (let [app-src (fn [prefixes routes]
                  (str "(ns shop.ui)\n\n"
                       "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                       "(defn ^{:web/method :get :web/path \"/p/:slug\"\n"
                       "        :web/client-routes " (pr-str prefixes) "}\n"
                       "  doc \"D.\" [_] {:status 200 :body \"<html>\"})\n\n"
                       "(defn ^:web/page app \"A.\" []\n"
                       "  {:webapp/routes " routes "})\n"))
        check   (fn [prefixes routes]
                  (rules.webapp/client-routes-unserved
                   (store/ingest (store/empty-store) 'shop.ui
                                 (app-src prefixes routes))))]

    (testing "a client route BELOW a declared prefix is served by the fallback"
      (is (= [] (check ["/p/:slug/store"]
                       "[[\"/store/form/:id\" s]]"))))

    (testing "a client route under NO declared prefix is reported"
      ;; the eight-routes-404 failure, caught before a reader meets it
      (is (= ["/settings/:tab"]
             (check ["/p/:slug/store"]
                    "[[\"/store/form/:id\" s] [\"/settings/:tab\" s]]"))))

    (testing "the prefix ROOT is reported too, and it is the subtle one"
      ;; `["/store"]` generates `/store/*client-path`, which needs at least one
      ;; segment below it — so the root itself is NOT covered by the fallback
      ;; and needs its own server route. That gotcha is documented in
      ;; `client-route-rows` and nothing has ever enforced it
      (is (= ["/store"]
             (check ["/p/:slug/store"] "[[\"/store\" s]]"))))

    (testing "a store with no declared prefixes reports every client route"
      ;; not silence: an app whose browser owns routes and whose document
      ;; declares none is the whole failure, not an app with nothing to check
      (is (= ["/store"] (check [] "[[\"/store\" s]]"))))

    (testing "and a store with no client routes reports nothing"
      (is (= [] (check ["/p/:slug/store"] "[]"))))))

(deftest the-prefixes-an-app-should-declare-are-DERIVABLE
  ;; The plan for this step was "derive `:web/client-routes` from the client
  ;; table so the two cannot drift", and the obstacle looked fatal: a prefix is
  ;; in SERVER space and a client route is in APP space, and the mount point is
  ;; a deployment fact no store knows.
  ;;
  ;; It is not. **The document's own `:web/path` IS the mount point**, and the
  ;; document is the form carrying `:web/client-routes` — the only unambiguous
  ;; way to name it. Identifying it any other way was the first cut's bug: it
  ;; took the alphabetically-first endpoint, which was the same form in these
  ;; fixtures and `/` in the first real store.
  (let [src (fn [routes]
              (str "(ns shop.ui)\n\n"
                   "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                   ;; sorts first, is not the document
                   "(defn ^{:web/method :get :web/path \"/\"} root \"R.\" [_] {})\n\n"
                   "(defn ^{:web/method :get :web/path \"/p/:slug\"\n"
                   "        :web/client-routes [\"/p/:slug/store\"]}\n"
                   "  doc \"D.\" [_] {})\n\n"
                   "(defn ^:web/page app \"A.\" []\n"
                   "  {:webapp/routes " routes "})\n"))
        at  (fn [routes] (rules.webapp/derived-client-route-prefixes
                          (store/ingest (store/empty-store) 'shop.ui (src routes))))]

    (testing "the document's own path is the mount point, and the rest is the table"
      (is (= ["/p/:slug/change" "/p/:slug/endpoints" "/p/:slug/store"]
             (at (str "[[\"/store\" s] [\"/store/form/:id\" s]"
                      " [\"/change/:range\" s] [\"/endpoints\" s]]")))))

    (testing "one prefix per TOP-LEVEL segment, not one per route"
      ;; /store and /store/form/:id are one prefix: the catch-all under /store
      ;; answers both, and listing them separately would be three declarations
      ;; where one serves
      (is (= ["/p/:slug/store"]
             (at "[[\"/store\" s] [\"/store/form/:id\" s] [\"/store/ns/:ns\" s]]"))))

    (testing "the app ROOT contributes nothing, because a prefix needs a segment"
      ;; `["/"]` would generate `//*client-path`, which is not a path — and the
      ;; document already answers its own url
      (is (= [] (at "[[\"/\" s]]"))))

    (testing "an app that declares NO prefix has no mount point, and says nothing"
      ;; the honest limit. Without a declaration there is no form to read the
      ;; mount from, and guessing at one — the first endpoint, the ^:web/page
      ;; entry — is exactly the bug this rewrite fixed. The advisory names the
      ;; app-space segments instead, which is the half that IS known
      (let [none (store/ingest (store/empty-store) 'shop.ui
                               (str "(ns shop.ui)\n\n"
                                    "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                                    "(defn ^{:web/method :get :web/path \"/p/:slug\"}\n"
                                    "  doc \"D.\" [_] {})\n\n"
                                    "(defn ^:web/page app \"A.\" []\n"
                                    "  {:webapp/routes [[\"/store\" s]]})\n"))]
        (is (= [] (rules.webapp/derived-client-route-prefixes none)))))))

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
                 "(defn things \"T.\" [_s] [:p \"things\"])\n"
                 "(defn thing \"T.\" [_s] [:p \"thing\"])\n\n"
                 "(defn ^{:web/method :get :web/path \"/p/:slug\"} doc \"D.\" [_] {})\n\n"
                 "(defn ^:web/page app \"A.\" []\n"
                 "  {:webapp/routes  [[\"/things\" things] [\"/things/:id\" thing]]\n"
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

    (testing "every declared route is a screen row, naming what renders it"
      (let [rows (:screens (rules.webapp/webapp-report on))]
        (is (= ["/things" "/things/:id"] (mapv :path rows)))
        (is (= '[shop.ui/things shop.ui/thing] (mapv :screen rows)))
        (is (every? #(= :screen (:kind %)) rows)
            "rows are self-describing, so a renderer that knows nothing about
             this capability can still draw one")))

    (testing "a row written as a screen VALUE reports its render AND what it loads"
      ;; the shape a screen takes once it names its own request. The report is
      ;; the human's picture of the app, and \"which url does this screen ask
      ;; for\" is half of what they came for — reporting the whole map verbatim
      ;; would answer neither question
      (let [src2 (str "(ns shop.two)\n\n"
                      "(defn thing \"T.\" [_s] [:p \"thing\"])\n"
                      "(defn thing-request \"R.\" [_p] {:webapp/path \"/api/thing\"})\n\n"
                      "(defn ^:web/page app \"A.\" []\n"
                      "  {:webapp/routes [[\"/things/:id\" {:render thing :request thing-request}]]})\n")
            rows (:screens (rules.webapp/webapp-report
                            (assoc-in (store/ingest (store/empty-store) 'shop.two src2)
                                      [:config "capabilities" :values "webapp.enabled"] "true")))]
        (is (= ["/things/:id"] (mapv :path rows)) (pr-str rows))
        (is (= '[shop.two/thing] (mapv :screen rows)) (pr-str rows))
        (is (= '[shop.two/thing-request] (mapv :request rows)) (pr-str rows))))

    (testing "a row is READABLE or it is skipped — never a thrown report"
      ;; `rules.rest/contracts-report`'s own scar: one member that threw made
      ;; nine endpoints unreadable on the day a store turned the capability on
      (let [src3 (str "(ns shop.three)\n\n"
                      "(defn ^:web/page app \"A.\" []\n"
                      "  {:webapp/routes [[\"/ok\" {:render identity}]\n"
                      "                   [\"/broken\" {:request identity}]]})\n")
            rows (:screens (rules.webapp/webapp-report
                            (assoc-in (store/ingest (store/empty-store) 'shop.three src3)
                                      [:config "capabilities" :values "webapp.enabled"] "true")))]
        (is (= ["/broken" "/ok"] (mapv :path rows))
            "a screen with no :render is still an ADDRESS this app declares")
        (is (nil? (:screen (first rows))) (pr-str rows))))

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

    (testing "and the :cljs count, which is the goal stated as a number"
      ;; "an app that opts into webapp writes NO ClojureScript" is an aspiration
      ;; until a store can answer how much it writes. Zero here, and a store
      ;; that has drifted says so
      (is (= 0 (:cljs (rules.webapp/webapp-report on)))))))

(deftest the-mount-point-is-the-form-that-DECLARES-client-routes
  ;; Reported by slopp-ui on the first real run, with three false positives and
  ;; a malformed remedy: `:declare ["//change" "//endpoints" "//store"]`.
  ;;
  ;; **Their store separates two forms my fixtures had merged.** `^:web/page` is
  ;; the HEADLESS entry — zero-arg, `:cljc`, carrying canned fixtures, and NOT a
  ;; route, because `:web/page` means "an entry `screen` can open" and a store
  ;; may mark a page that is served by something else. The document is a
  ;; different form, and it is the one carrying `:web/client-routes`.
  ;;
  ;; The cause was worse than "read the wrong marker": the derivation read NO
  ;; marker. It took the alphabetically-first endpoint path in the store — `/`
  ;; in theirs — which is how a mount point became the empty string and every
  ;; derived prefix gained a doubled slash. The docstring meanwhile explained
  ;; that it "picks the FIRST document by name … a store with two ^:web/page
  ;; entries is already refused", which describes a selection the code did not
  ;; make. A justification for behaviour that does not exist is the shape this
  ;; week has been about, arriving in my own new form.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                 ;; sorts FIRST and is not the document — the trap
                 "(defn ^{:web/method :get :web/path \"/\"} root \"R.\" [_] {})\n\n"
                 "(defn ^{:web/method :get :web/path \"/api/things\"} api \"A.\" [_] {})\n\n"
                 ;; the prefix ROOT's own route — the catch-all under
                 ;; /p/:slug/store needs a segment below it, so this is the
                 ;; remedy the advisory names and the one it must be able to SEE
                 "(defn ^{:web/method :get :web/path \"/p/:slug/store\"} sroot \"S.\" [_] {})\n\n"
                 ;; the DOCUMENT: it declares the client routes
                 "(defn ^{:web/method :get :web/path \"/p/:slug\"\n"
                 "        :web/client-routes [\"/p/:slug/store\"]}\n"
                 "  doc \"D.\" [_] {})\n\n"
                 ;; the HEADLESS entry, a different form with no server path
                 "(defn ^:web/page page \"P.\" []\n"
                 "  {:webapp/routes [[\"/\" s] [\"/store\" s] [\"/store/form/:id\" s]]})\n")
        st  (assoc-in (store/ingest (store/empty-store) 'shop.ui src)
                      [:config "capabilities" :values "webapp.enabled"] "true")]

    (testing "the mount point comes from the form DECLARING client routes"
      (is (= ["/p/:slug/store"] (rules.webapp/derived-client-route-prefixes st))
          "not the first endpoint by path, and not the ^:web/page entry"))

    (testing "no doubled slash, which is what a wrong mount point looks like"
      (is (not-any? #(re-find #"//" %)
                    (rules.webapp/derived-client-route-prefixes st))))

    (testing "an EXPLICIT server route is coverage, which the remedy already said"
      ;; `/store` is a prefix root, so the generated catch-all does not answer
      ;; it — and the advisory's own escape text says "or give it a server route
      ;; of its own". The check did not look for one, so it reported three
      ;; routes as unserved that a socket test proves are served
      (is (= [] (rules.webapp/client-routes-unserved st))
          (str "the client root maps to the document's own path, and /store to"
               " the declared prefix — both are real server routes: "
               (pr-str (rules.webapp/client-routes-unserved st)))))

    (testing "and a route with NO coverage of either kind is still reported"
      ;; the arm that keeps the fix from being a blanket pass
      (let [gap (assoc-in
                 (store/ingest (store/empty-store) 'shop.ui
                               (str "(ns shop.ui)\n\n"
                                    "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                                    "(defn ^{:web/method :get :web/path \"/p/:slug\"\n"
                                    "        :web/client-routes [\"/p/:slug/store\"]}\n"
                                    "  doc \"D.\" [_] {})\n\n"
                                    "(defn ^:web/page page \"P.\" []\n"
                                    "  {:webapp/routes [[\"/settings/:tab\" s]]})\n"))
                 [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (= ["/settings/:tab"] (rules.webapp/client-routes-unserved gap)))))
(testing "a prefix whose ROOT is not a client route needs no server route"
      ;; slopp-ui's third case, and it is correct rather than a gap. Their
      ;; `/change` prefix exists only to generate the catch-all: the client
      ;; table has `/change/:range` and no bare `/change` screen, so arm 1
      ;; covers everything under it and arm 2 has nothing to do.
      ;;
      ;; Worth a fixture because the two shapes are indistinguishable from the
      ;; DECLARATION and opposite in what they require:
      ;;
      ;;   root IS a client route      → needs its own server route
      ;;   root is NOT a client route  → needs nothing
      ;;
      ;; The check gets this right by never asking about a path the app does not
      ;; route — which is correct by construction and therefore easy to break
      ;; while refactoring, since nothing else asserts it
      (let [no-root (assoc-in
                     (store/ingest (store/empty-store) 'shop.ui
                                   (str "(ns shop.ui)\n\n"
                                        "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                                        "(defn ^{:web/method :get :web/path \"/p/:slug\"\n"
                                        "        :web/client-routes [\"/p/:slug/change\"]}\n"
                                        "  doc \"D.\" [_] {})\n\n"
                                        "(defn ^:web/page page \"P.\" []\n"
                                        "  {:webapp/routes [[\"/change/:range\" s]]})\n"))
                     [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (= [] (rules.webapp/client-routes-unserved no-root))
            "a prefix with no root screen is a catch-all generator and nothing more")))

    (testing "and a route with NO coverage of either kind is still reported"
      ;; the arm that keeps the fix from being a blanket pass
      (let [gap (assoc-in
                 (store/ingest (store/empty-store) 'shop.ui
                               (str "(ns shop.ui)\n\n"
                                    "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                                    "(defn ^{:web/method :get :web/path \"/p/:slug\"\n"
                                    "        :web/client-routes [\"/p/:slug/store\"]}\n"
                                    "  doc \"D.\" [_] {})\n\n"
                                    "(defn ^:web/page page \"P.\" []\n"
                                    "  {:webapp/routes [[\"/settings/:tab\" s]]})\n"))
                 [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (= ["/settings/:tab"] (rules.webapp/client-routes-unserved gap)))))))
