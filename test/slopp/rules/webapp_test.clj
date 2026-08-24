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
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "the browser owns routing here — this turns http on with it")
      (ops/ingest! sess 'browser.ui
                   (str "(ns browser.ui)\n"
                        "(defn ^{:http/method :get :http/path \"/\" :http/auth :public\n"
                        "        :rest/client false :rest/response :string}\n"
                        "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})\n"))
      (external/done! sess :label "baseline")
      (testing "adding the declaration states the consequence"
        (ops/edit-replace! sess 'browser.ui 'doc
                           (str "(defn ^{:http/method :get :http/path \"/\" :http/auth :public\n"
                                "        :rest/client false :rest/response :string\n"
                                "        :webapp/client-routes [\"/store\"]}\n"
                                "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})")
                           :prompt "the client routes /store")
        (let [f (get-in (external/done! sess :label "client-routes") [:findings :webapp-client-routes-consequences])]
          (is (some #(= 'browser.ui/doc (:form %)) f) (pr-str f))
          (is (re-find #"200" (str (:teach (first f)))) (pr-str f))
          (is (re-find #"(?i)not-found" (str (:teach (first f)))) (pr-str f))))
      (testing "it does NOT re-fire while the declaration merely stands"
        (ops/edit-replace! sess 'browser.ui 'doc
                           (str "(defn ^{:http/method :get :http/path \"/\" :http/auth :public\n"
                                "        :rest/client false :rest/response :string\n"
                                "        :webapp/client-routes [\"/store\"]}\n"
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

(deftest the-declared-CLIENT-ROUTE-TABLE-is-readable-from-the-store
  ;; What step B bought, collected: an app's client routes are a value now, so
  ;; anything can join against them. `^:webapp/client-path` exists because they were
  ;; not — `rules.http/ui-route-refs` says so in its own docstring:
  ;;
  ;;   teaching the check to SEE the prefixing is not possible in general,
  ;;   because the base arrives through an ordinary function call
  ;;
  ;; That stopped being true when slopp took over the prefixing. A literal in a
  ;; view is a CLIENT ROUTE KEY, and this is the table it is a key into.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn things \"T.\" [_s] [:p \"things\"])\n\n"
                 "(defn ^:app/entry app \"A.\" []\n"
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
                                        "(defn ^:app/entry app \"A.\" []\n"
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
                       "(defn ^{:http/method :get :http/path \"/p/:slug\"\n"
                       "        :webapp/client-routes " (pr-str prefixes) "}\n"
                       "  doc \"D.\" [_] {:status 200 :body \"<html>\"})\n\n"
                       "(defn ^:app/entry app \"A.\" []\n"
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
  ;; The plan for this step was "derive `:webapp/client-routes` from the client
  ;; table so the two cannot drift", and the obstacle looked fatal: a prefix is
  ;; in SERVER space and a client route is in APP space, and the mount point is
  ;; a deployment fact no store knows.
  ;;
  ;; It is not. **The document's own `:http/path` IS the mount point**, and the
  ;; document is the form carrying `:webapp/client-routes` — the only unambiguous
  ;; way to name it. Identifying it any other way was the first cut's bug: it
  ;; took the alphabetically-first endpoint, which was the same form in these
  ;; fixtures and `/` in the first real store.
  (let [src (fn [routes]
              (str "(ns shop.ui)\n\n"
                   "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                   ;; sorts first, is not the document
                   "(defn ^{:http/method :get :http/path \"/\"} root \"R.\" [_] {})\n\n"
                   "(defn ^{:http/method :get :http/path \"/p/:slug\"\n"
                   "        :webapp/client-routes [\"/p/:slug/store\"]}\n"
                   "  doc \"D.\" [_] {})\n\n"
                   "(defn ^:app/entry app \"A.\" []\n"
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
      ;; mount from, and guessing at one — the first endpoint, the ^:app/entry
      ;; entry — is exactly the bug this rewrite fixed. The advisory names the
      ;; app-space segments instead, which is the half that IS known
      (let [none (store/ingest (store/empty-store) 'shop.ui
                               (str "(ns shop.ui)\n\n"
                                    "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                                    "(defn ^{:http/method :get :http/path \"/p/:slug\"}\n"
                                    "  doc \"D.\" [_] {})\n\n"
                                    "(defn ^:app/entry app \"A.\" []\n"
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
                 "(defn ^{:http/method :get :http/path \"/p/:slug\"} doc \"D.\" [_] {})\n\n"
                 "(defn ^:app/entry app \"A.\" []\n"
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
                      "(defn ^:app/entry app \"A.\" []\n"
                      "  {:webapp/routes [[\"/things/:id\" {:render thing :request thing-request}]]})\n")
            rows (:screens (rules.webapp/webapp-report
                            (assoc-in (store/ingest (store/empty-store) 'shop.two src2)
                                      [:config "capabilities" :values "webapp.enabled"] "true")))]
        (is (= ["/things/:id"] (mapv :path rows)) (pr-str rows))
        (is (= '[shop.two/thing] (mapv :screen rows)) (pr-str rows))
        (is (= '[shop.two/thing-request] (mapv :request rows)) (pr-str rows))
        (is (= ["/api/thing"] (mapv :loads rows))
            (str "a var name answers WHICH function, and the reader's question"
                 " is which endpoint — the report is the picture somebody who"
                 " does not read the code is looking at: " (pr-str rows)))))

    (testing "a row is READABLE or it is skipped — never a thrown report"
      ;; `rules.rest/contracts-report`'s own scar: one member that threw made
      ;; nine endpoints unreadable on the day a store turned the capability on
      (let [src3 (str "(ns shop.three)\n\n"
                      "(defn ^:app/entry app \"A.\" []\n"
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

    (testing "declared SESSION loads are reported, because they belong to no screen"
      ;; the fetch a reader never navigates to and every screen may read — a
      ;; nav rail, a signed-in user. Absent from the screen rows by definition,
      ;; so a report drawing only screens shows an app fetching less than it does
      (let [src4 (str "(ns shop.four)\n\n"
                      "(defn modules-request \"R.\" [_s] {:webapp/path \"/api/modules\"})\n\n"
                      "(defn ^:app/entry app \"A.\" []\n"
                      "  {:webapp/routes        []\n"
                      "   :webapp/session-loads {:modules {:request modules-request}\n"
                      "                          :user    {}}})\n")
            rows (:session-loads (rules.webapp/webapp-report
                                  (assoc-in (store/ingest (store/empty-store) 'shop.four src4)
                                            [:config "capabilities" :values "webapp.enabled"] "true")))]
        (is (= [:modules :user] (mapv :load rows)) (pr-str rows))
        (is (= '[shop.four/modules-request nil] (mapv :request rows)) (pr-str rows))
        (is (= ["/api/modules" nil] (mapv :loads rows))
            (str "the url a session load fetches is the same question a screen's"
                 " is: " (pr-str rows)))
        (is (every? #(= :session-load (:kind %)) rows) (pr-str rows))))

    (testing "and the :cljs count, which is the goal stated as a number"
      ;; "an app that opts into webapp writes NO ClojureScript" is an aspiration
      ;; until a store can answer how much it writes. Zero here, and a store
      ;; that has drifted says so
      (is (= 0 (:cljs (rules.webapp/webapp-report on)))))))

(deftest the-mount-point-is-the-form-that-DECLARES-client-routes
  ;; Reported by slopp-ui on the first real run, with three false positives and
  ;; a malformed remedy: `:declare ["//change" "//endpoints" "//store"]`.
  ;;
  ;; **Their store separates two forms my fixtures had merged.** `^:app/entry` is
  ;; the HEADLESS entry — zero-arg, `:cljc`, carrying canned fixtures, and NOT a
  ;; route, because `:app/entry` means "an entry `screen` can open" and a store
  ;; may mark a page that is served by something else. The document is a
  ;; different form, and it is the one carrying `:webapp/client-routes`.
  ;;
  ;; The cause was worse than "read the wrong marker": the derivation read NO
  ;; marker. It took the alphabetically-first endpoint path in the store — `/`
  ;; in theirs — which is how a mount point became the empty string and every
  ;; derived prefix gained a doubled slash. The docstring meanwhile explained
  ;; that it "picks the FIRST document by name … a store with two ^:app/entry
  ;; entries is already refused", which describes a selection the code did not
  ;; make. A justification for behaviour that does not exist is the shape this
  ;; week has been about, arriving in my own new form.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn s \"S.\" [_st] [:p \"s\"])\n\n"
                 ;; sorts FIRST and is not the document — the trap
                 "(defn ^{:http/method :get :http/path \"/\"} root \"R.\" [_] {})\n\n"
                 "(defn ^{:http/method :get :http/path \"/api/things\"} api \"A.\" [_] {})\n\n"
                 ;; the prefix ROOT's own route — the catch-all under
                 ;; /p/:slug/store needs a segment below it, so this is the
                 ;; remedy the advisory names and the one it must be able to SEE
                 "(defn ^{:http/method :get :http/path \"/p/:slug/store\"} sroot \"S.\" [_] {})\n\n"
                 ;; the DOCUMENT: it declares the client routes
                 "(defn ^{:http/method :get :http/path \"/p/:slug\"\n"
                 "        :webapp/client-routes [\"/p/:slug/store\"]}\n"
                 "  doc \"D.\" [_] {})\n\n"
                 ;; the HEADLESS entry, a different form with no server path
                 "(defn ^:app/entry page \"P.\" []\n"
                 "  {:webapp/routes [[\"/\" s] [\"/store\" s] [\"/store/form/:id\" s]]})\n")
        st  (assoc-in (store/ingest (store/empty-store) 'shop.ui src)
                      [:config "capabilities" :values "webapp.enabled"] "true")]

    (testing "the mount point comes from the form DECLARING client routes"
      (is (= ["/p/:slug/store"] (rules.webapp/derived-client-route-prefixes st))
          "not the first endpoint by path, and not the ^:app/entry entry"))

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
                                    "(defn ^{:http/method :get :http/path \"/p/:slug\"\n"
                                    "        :webapp/client-routes [\"/p/:slug/store\"]}\n"
                                    "  doc \"D.\" [_] {})\n\n"
                                    "(defn ^:app/entry page \"P.\" []\n"
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
                                        "(defn ^{:http/method :get :http/path \"/p/:slug\"\n"
                                        "        :webapp/client-routes [\"/p/:slug/change\"]}\n"
                                        "  doc \"D.\" [_] {})\n\n"
                                        "(defn ^:app/entry page \"P.\" []\n"
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
                                    "(defn ^{:http/method :get :http/path \"/p/:slug\"\n"
                                    "        :webapp/client-routes [\"/p/:slug/store\"]}\n"
                                    "  doc \"D.\" [_] {})\n\n"
                                    "(defn ^:app/entry page \"P.\" []\n"
                                    "  {:webapp/routes [[\"/settings/:tab\" s]]})\n"))
                 [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (= ["/settings/:tab"] (rules.webapp/client-routes-unserved gap)))))))

(deftest a-screens-REQUEST-PATH-is-joined-against-what-this-store-SERVES
  ;; The gap wave 4d created, recorded in `index.crossings` at the moment it was
  ;; created rather than found later: a literal `:href` is resolved against the
  ;; served route table by `http-dangling-route-refs`, and the `:webapp/path`
  ;; inside a screen's request is the same kind of claim about the same table,
  ;; made in a different key, with nothing reading it.
  ;;
  ;; So a screen could name an endpoint this store does not serve, and the only
  ;; symptom is a load that always fails — at a url that routes, on a screen
  ;; that renders, in an app where every other pane works.
  ;;
  ;; **The join is EQUALITY, not `router/match`.** A request path is a PATTERN
  ;; in the same grammar as `:http/path` — `/api/things/:id`, with the captures
  ;; supplied separately as `:webapp/path-params` — so matching it as though it
  ;; were a concrete url would ask the wrong question and answer nil for every
  ;; parameterized endpoint in the store.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:http/method :get :http/path \"/api/things\"\n"
                 "        :http/auth :public :rest/response :string}\n"
                 "  things \"T.\" [_] {:status 200 :body \"[]\"})\n\n"
                 "(defn ^{:http/method :get :http/path \"/api/things/:id\"\n"
                 "        :http/auth :public :rest/response :string}\n"
                 "  thing \"T.\" [_] {:status 200 :body \"{}\"})\n\n"
                 "(defn list-request \"R.\" [_p] {:webapp/path \"/api/things\"})\n\n"
                 "(defn detail-request \"R.\" [p]\n"
                 "  {:webapp/path \"/api/things/:id\" :webapp/path-params {:id (:id p)}})\n\n"
                 "(defn typo-request \"R.\" [_p] {:webapp/path \"/api/thing\"})\n\n"
                 "(defn far-request \"R.\" [_p]\n"
                 "  {:webapp/path \"https://api.example.com/v1/rates\"})\n")
        on  (assoc-in (store/ingest (store/empty-store) 'shop.ui src)
                      [:config "capabilities" :values "webapp.enabled"] "true")]

    (testing "a request path that names a declared endpoint is served"
      (let [unserved (set (map :path (rules.webapp/request-paths-unserved on)))]
        (is (not (contains? unserved "/api/things")) (pr-str unserved))
        (is (not (contains? unserved "/api/things/:id"))
            (str "a PARAMETERIZED endpoint must join by its pattern — resolving"
                 " it as a concrete url answers nil for every one of them: "
                 (pr-str unserved)))))

    (testing "and a path nothing serves is reported, with the form that names it"
      (let [rows (rules.webapp/request-paths-unserved on)]
        (is (= ["/api/thing"] (mapv :path rows)) (pr-str rows))
        (is (= 'shop.ui/typo-request (:form (first rows))) (pr-str rows))))

    (testing "an ABSOLUTE url is somebody else's server and is left alone"
      ;; the escape that keeps this worth having: an app calling a third-party
      ;; API declares a whole url, and reporting it would make the advisory
      ;; noise on every app that talks to anything
      (is (not (contains? (set (map :path (rules.webapp/request-paths-unserved on)))
                          "https://api.example.com/v1/rates"))))

    (testing "a FROM-ORIGIN request is not this store's to serve either"
      ;; the same statement an absolute url makes, in the form a MOUNTED app
      ;; can actually write: the path is measured from the origin, so it is
      ;; addressed at whatever sits there — which is not this store, or the
      ;; declaration would be saying nothing
      (let [src5 (str "(ns shop.five)\n\n"
                      "(defn hub-request \"R.\" [_p]\n"
                      "  {:webapp/path \"/api/projects\" :webapp/from-origin true})\n")
            st   (assoc-in (store/ingest (store/empty-store) 'shop.five src5)
                           [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (= [] (rules.webapp/request-paths-unserved st))
            (pr-str (rules.webapp/request-paths-unserved st)))))
(testing "a request naming its own BASE is not this store's to serve either"
      ;; the general form of the line above, and the reason it had to become
      ;; general: a client-routed app switches which upstream it reads WITHOUT
      ;; a page load, so the base is route state carried on the request. A
      ;; request that names one is addressed at whatever sits THERE — the same
      ;; statement from-origin makes, which is now just the empty case of it.
      (let [src6 (str "(ns shop.six)\n\n"
                      "(defn project-request \"R.\" [p]\n"
                      "  {:webapp/path \"/api/modules\"\n"
                      "   :webapp/base (str \"/p/\" (:slug p))})\n")
            st   (assoc-in (store/ingest (store/empty-store) 'shop.six src6)
                           [:config "capabilities" :values "webapp.enabled"] "true")]
        (is (= [] (rules.webapp/request-paths-unserved st))
            (str "a request measured from another api was reported against"
                 " THIS store's routes: "
                 (pr-str (rules.webapp/request-paths-unserved st))))))

    (testing "and a form marked ^:http/external-path is skipped WHOLE"
      ;; the escape the absolute-url one cannot cover, reported by the app that
      ;; needed it: its API is proxied by the PROJECT server under the same
      ;; mount point, so the path is real, served, and not this store's — and
      ;; it cannot be written in full because the prefix is the slug, known
      ;; only at runtime. Eight findings with nothing to do about them is the
      ;; permanent-finding failure: a list nobody can clear is a list everybody
      ;; skims.
      ;;
      ;; Same marker `http-dangling-route-refs` already uses for a link, for
      ;; the same question, and it carries a REASON rather than a silence
      (let [src2 (str "(ns shop.far)\n\n"
                      "(defn ^{:http/external-path \"the project server proxies /api/*\"}\n"
                      "  far-request \"R.\" [_p] {:webapp/path \"/api/modules\"})\n")
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
      ;; a complaint an author cannot act on is one they learn to skim: the
      ;; endpoint table is right there, and a typo is nearly always one of them
      (let [f (first (rules.webapp/webapp-request-paths-are-served-check nil on nil))]
        (is (re-find #"/api/things" (:teach f)) (pr-str f))))))

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
  (let [src (str "(ns shop.six)\n\n"
                 "(def routes \"R.\" [])\n"
                 "(def actions \"A.\" {})\n"
                 "(def session-loads \"S.\" {:modules {}})\n\n"
                 "(defn things \"T.\" [_s] [:p \"t\"])\n\n"
                 "(defn ^:app/entry app \"A.\" []\n"
                 "  {:webapp/routes        routes\n"
                 "   :webapp/actions       actions\n"
                 "   :webapp/session-loads session-loads})\n\n"
                 ;; a second, READABLE app in the same store — without it a
                 ;; report that returned empty would look like it had coped
                 "(defn ^:app/entry other \"O.\" []\n"
                 "  {:webapp/routes  [[\"/things\" things]]\n"
                 "   :webapp/actions {:thing/save {:effectful? true}}})\n")
        on  (assoc-in (store/ingest (store/empty-store) 'shop.six src)
                      [:config "capabilities" :values "webapp.enabled"] "true")]

    (testing "a declaration naming a VAR does not throw"
      (is (map? (rules.webapp/webapp-report on))
          "a store that names its route table by var made the whole surface unreadable"))

    (testing "and what IS readable is still reported"
      ;; the half that makes skipping honest rather than a shrug: an
      ;; unreadable declaration costs its own rows and nothing else
      (let [r (rules.webapp/webapp-report on)]
        (is (= ["/things"] (mapv :path (:screens r))) (pr-str r))
        (is (= [:thing/save] (mapv :action (:actions r))) (pr-str r))))

    (testing "every extractor, not just the one that was reported"
      ;; routes, actions and session-loads all seq'd their value, so fixing the
      ;; reported one would leave two loaded guns in the same function
      (doseq [k [:webapp/routes :webapp/actions :webapp/session-loads]]
        (let [one (str "(ns shop.one)\n\n(def v \"V.\" nil)\n\n"
                       "(defn ^:app/entry app \"A.\" [] {" k " v})\n")
              st  (assoc-in (store/ingest (store/empty-store) 'shop.one one)
                            [:config "capabilities" :values "webapp.enabled"] "true")]
          (is (map? (rules.webapp/webapp-report st))
              (str k " taken by var still takes the report down")))))))

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
  ;; Not asking it to RESOLVE a `cond->` — skipping what it cannot read is
  ;; right, and their declaration is a `cond->` for a real reason: `:check` is
  ;; only there when a contract was generated.
  (let [src (str "(ns shop.seven)\n\n"
                 "(def actions \"A.\" {:thing/save {:effectful? true}})\n"
                 "(defn things \"T.\" [_s] [:p \"t\"])\n"
                 "(defn modules-request \"R.\" [_s] {:webapp/path \"/api/modules\"})\n\n"
                 "(defn ^:app/entry app \"A.\" []\n"
                 "  {:webapp/routes        [[\"/things\" things]]\n"
                 ;; a whole declaration naming a VAR
                 "   :webapp/actions       actions\n"
                 ;; a readable declaration with ONE unreadable entry
                 "   :webapp/session-loads {:modules  (cond-> {:request modules-request})\n"
                 "                          :projects {:request modules-request}}})\n")
        on  (assoc-in (store/ingest (store/empty-store) 'shop.seven src)
                      [:config "capabilities" :values "webapp.enabled"] "true")
        r   (rules.webapp/webapp-report on)]

    (testing "the readable half is still reported, which is what makes skipping honest"
      (is (= ["/things"] (mapv :path (:screens r))) (pr-str r))
      (is (= [:projects] (mapv :load (:session-loads r))) (pr-str r)))

    (testing "a whole DECLARATION it cannot read is named"
      (is (some #(re-find #"webapp/actions" %) (:unreadable r))
          (str "an empty :actions is an affirmative claim of emptiness — there"
               " is nothing in it a reader could tell from a store with no"
               " actions: " (pr-str (:unreadable r)))))

    (testing "and a single unreadable ENTRY inside a readable one is named too"
      (is (some #(re-find #":modules" %) (:unreadable r))
          (str "one session load vanished from an otherwise-complete list: "
               (pr-str (:unreadable r)))))

    (testing "the note SHOWS what it found, so the reader can see why"
      ;; "not a literal" is a rule; the value is the evidence, and it is what
      ;; tells an author whether they meant it
      (is (some #(re-find #"actions" %) (:unreadable r)) (pr-str (:unreadable r)))
      (is (some #(re-find #"cond->" %) (:unreadable r))
          (str "the reader has to go and look otherwise: " (pr-str (:unreadable r)))))

    (testing "a COMPUTED table is ONE finding, not one per element of the call"
      ;; The false-positive half, and it is worse than an ordinary one because
      ;; it lives in the mechanism built to stop false confidence — running the
      ;; other way. The answer was COMPLETE and claimed three things were
      ;; missing from it, so a reader who trusts the list hunts for routes that
      ;; are there, and a reader who checks once learns to skim it.
      ;;
      ;; `(mapv (fn [[p s]] …) (:webapp/routes views/client-routes))` is a LIST,
      ;; and a list is `sequential?` — so the reader walked the CALL as if it
      ;; were the vector of rows and reported its three elements: the symbol
      ;; `mapv`, the `fn`, and the argument. A threaded arg would have made it
      ;; four. `:unreadable` only works if it is exactly as trustworthy as the
      ;; answer beside it.
      (let [src2 (str "(ns shop.nine)\n\n"
                      "(defn things \"T.\" [_s] [:p \"t\"])\n"
                      "(def client-routes \"CR.\" {:webapp/routes [[\"/things\" things]]})\n\n"
                      "(defn ^:app/entry app \"A.\" []\n"
                      "  {:webapp/routes (mapv (fn [[p s]] [p s])\n"
                      "                        (:webapp/routes client-routes))})\n")
            st   (assoc-in (store/ingest (store/empty-store) 'shop.nine src2)
                           [:config "capabilities" :values "webapp.enabled"] "true")
            r2   (rules.webapp/webapp-report st)]
        (is (= ["/things"] (mapv :path (:screens r2)))
            (str "the literal table elsewhere still reads: " (pr-str r2)))
        (is (= 1 (count (:unreadable r2)))
            (str "a call form's ELEMENTS were each reported as a route row: "
                 (pr-str (:unreadable r2))))
        (is (re-find #"mapv" (first (:unreadable r2))) (pr-str (:unreadable r2)))
        (is (not (re-find #"a route row" (first (:unreadable r2))))
            (str "the finding is about the TABLE, not about a row inside it: "
                 (pr-str (:unreadable r2))))))

    (testing "a store it can read entirely says NOTHING — silence is the good case"
      (let [clean (assoc-in (store/ingest (store/empty-store) 'shop.eight
                                          (str "(ns shop.eight)\n\n"
                                               "(defn things \"T.\" [_s] [:p \"t\"])\n\n"
                                               "(defn ^:app/entry app \"A.\" []\n"
                                               "  {:webapp/routes [[\"/things\" things]]})\n"))
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
                                  "(defn screen-a \"A.\" []\n"
                                  "  {:request (fn [_] {:webapp/method :get"
                                  " :webapp/path \"/api/things\"})})\n\n"
                                  "(defn screen-b \"B.\" []\n"
                                  "  {:request (fn [_] {:webapp/method :get"
                                  " :webapp/path \"/css/app.css\"})})\n\n"
                                  "(defn screen-c \"C.\" []\n"
                                  "  {:request (fn [_] {:webapp/method :get"
                                  " :webapp/path \"/api/nope\"})})\n")))]

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
