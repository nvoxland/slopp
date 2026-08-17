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
