(ns slopp-server.ui.pages-test
  "What the ASKING half does — [[slopp-server.ui.pages]].

  These assertions moved out of `views-test` with the forms they pin. A page
  and `chrome` both call `slopp.webapp/ask!`, which is why they live in an
  `:external` namespace at all; a test for them belongs beside them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp-server.ui.pages :as pages]
            [slopp-server.ui.views :as views]
            [slopp-server.ui.wire.api :as api]
            [slopp.http.endpoint :as endpoint]
            [slopp.webapp :as webapp] [slopp.cljnx :as cljnx] [malli.core :as m] [slopp-server.ui.page :as page]))

(defn probe!
  "A `page` map that RECORDS what it is asked for and answers only what it was
  seeded with.

  `answers` is a seq of `[descriptor params value]`. Each is keyed the way
  `slopp.webapp/ask!` keys it — by the REQUEST the descriptor and params build
  — so seeding goes through `endpoint/request` rather than through a
  hand-written key. A key written by hand is the fixture-agrees-with-itself
  shape: it would keep passing after the descriptor's path changed.

  Anything NOT seeded is left pending rather than made an error. That is what a
  real page sees while a fetch is in flight, and it is the state most of these
  assertions are about — a rail that must not appear yet, a pane that must say
  it is loading. `:webapp/call` records and never calls back, so `ask!` starts
  the load, finds no answer, and reports `:loading`.

  The recorded requests are the other half of its job: `:asked` is an atom of
  every request built, which is how an assertion checks that a page asked for
  the right URL without performing anything."
  [state params answers]
  (let [asked (atom [])
        loads (into {} (for [[descriptor p value] answers]
                         ;; `""` is the base `ask!` will key with — `probe!` declares
                          ;; `:webapp/base ""` below, and a seeded key that used
                          ;; a different one would sit where nothing looks.
                          [(webapp/load-key "" (endpoint/request descriptor p))
                           {:status :ready :value value}]))
        st    (assoc state :params params :loads loads)
        store (atom st)]
    {:webapp/state store
     :webapp/base  ""
     :webapp/call  (fn [request _ok _err] (swap! asked conj request) nil)
     :asked        asked
     :state        st
     :params       params}))

(deftest chrome-is-the-layout-around-a-page-and-nothing-else
  ;; A page renders its own content and hands it here as `inner`. So chrome is
  ;; the shell, the section nav, the rail and the lens bar — and nothing that
  ;; belongs to the page inside it.
  ;;
  ;; Four things this pins, each of which is a way the split can go wrong.
  (let [nodes  (fn [t] (filter string? (tree-seq coll? seq t)))
        text   (fn [v region] (str/join " " (nodes (views/find-region v region))))
        params {:slug "demo"}
        page   (probe! {:path "/p/demo/store"} params
                       ;; the DOCUMENT the endpoint publishes, not the vector inside it. The
                       ;; session load this replaced declared `:derive :modules` and handed
                       ;; chrome the vector; `ask!` has no derive, so seeding the vector here
                       ;; would be a fixture agreeing with a shape nothing produces — and it
                       ;; passed that way until the unwrapping was written.
                       [[(views/at-project params api/modules) {}
                         {:modules [{:module "demo.core" :namespaces ["demo.core"]}]}]])
        v      (pages/chrome page :code {:status :ready :value []} [:div [:h1 "INNER"]])]

    (testing "inner is placed, not re-rendered — chrome receives finished hiccup
              and its only job is deciding where it sits"
      (is (str/includes? (text v :main) "INNER")))

    (testing "the rail is drawn from the SUBJECT the page passed, so a lens
              address is still inside its section. `code-gaps-page` is three
              addresses away from `:code` and the nav must not lose it"
      (is (some? (views/find-region v :nav/local))
          "the Code section's module nav is absent on a code screen"))

    (testing "the module rail comes from an ASK, not from a plain state key.
              It used to be `:webapp/session-loads`, started by the framework's
              boot; the load is keyed by its request now, so seeding it any
              other way would pass while the real page fetched nothing"
      (is (str/includes? (text v :nav/local) "demo.core")))

    (testing "and chrome does NOT prefix links. `derived-view` calls
              `prefix-links` over the FINISHED tree, so a chrome that also
              prefixed would double every mount point — `/p/x/p/x/store`"
      (let [hrefs (->> (tree-seq coll? seq v)
                       (filter map?)
                       (keep :href)
                       (filter string?))]
        (is (seq hrefs) "no links at all — the check below would pass vacuously")
        ;; chrome DOES put the tenant segment back on, which is the one
        ;; prefixing it owns; what it must not do is prefix twice.
        (is (every? #(not (str/starts-with? % "/p/demo/p/")) hrefs)
            (str "chrome prefixed a link twice: " (pr-str (vec hrefs))))))))

(deftest every-page-asks-for-a-url-measured-against-its-own-PROJECT
  ;; Two retired tests, one population. They used to read a route row's
  ;; `:request` and call it; a page ASKS while it renders, so the way to see
  ;; what an address fetches is to render it and record what it asked for.
  ;;
  ;; **The leak this closes was `?slug=demo` reaching a project that never
  ;; asked for it.** `:slug` is a capture of THIS app's route, not of
  ;; `/api/form/:id`, and a builder handed the whole params map turned it into
  ;; a query parameter — silent in both directions, because the upstream
  ;; ignores an unknown key and the screen renders. The guard now lives in
  ;; `slopp.http.endpoint/request`, which REFUSES a param the descriptor does
  ;; not declare, so rendering a page that over-shares throws rather than
  ;; asserting: this test's population is what makes that refusal reach every
  ;; address.
  ;;
  ;; **And every request is measured against the project in the address.** A
  ;; page that forgets asks the HUB for a project's endpoint, which the hub does
  ;; not serve: a 404 on one pane while everything around it works.
  (let [table    (cljnx/marked-pages)
        ;; each page's OWN params, the way the router produces them — a
        ;; concrete path built from that pattern's own segments, matched back.
        ;; A hand-written map would be the fixture-agrees-with-the-code shape:
        ;; the first version of this crammed every route's captures into one
        ;; sample and reported six false leaks.
        concrete (fn [pattern]
                   (->> (str/split pattern #"/")
                        (map (fn [s] (cond (= s "**") "a/b"
                                           (= s "*") "x"
                                           (str/starts-with? s ":") (subs s 1)
                                           :else s)))
                        (str/join "/")))]

    (testing "the POPULATION is real — every pattern routes back to a page"
      (is (seq table))
      (doseq [[pattern _] table]
        (is (some? (webapp/match-route table (concrete pattern)))
            (str pattern " does not match the path built from its own segments"))))

    (doseq [[pattern page-fn] table]
      (testing pattern
        (let [path (concrete pattern)
              m    (webapp/match-route table path)
              p    (probe! {:path path} (:params m) [])]
          ;; rendering is what asks. A page that hands an endpoint a key it
          ;; does not declare throws HERE, which is the refusal being asserted.
          (is (some? (page-fn p))
              (str pattern " rendered nothing at all"))

          (let [urls (mapv #(str (:http/url %)) @(:asked p))]
            (is (seq urls) (str pattern " asked for nothing"))

            (doseq [u urls]
              (is (str/starts-with? u "/api/")
                  (str pattern " asked for " (pr-str u)
                       ", which is not this app's API at all"))

              ;; the HUB's own endpoint is the ONE exception and says so with an
              ;; empty base — it is served at the origin, not under a project.
              (when-not (= u "/api/projects")
                (is (str/starts-with? u "/api/projects/slug/")
                    (str pattern " asked for " (pr-str u)
                         " — not measured against the project in the address,"
                         " so the hub is asked for a project's endpoint")))

              (is (not (str/includes? u "slug="))
                  (str pattern " leaked the tenant into a query string: "
                       (pr-str u))))))))))

(deftest ^:external every-canned-answer-satisfies-its-published-contract
  ;; What it asserts is a DRIFT between two things nothing else compares: the
  ;; shapes this app cans for its headless fixtures, and the shapes the project
  ;; PUBLISHES. A fixture that drifts is a screen tested against data no server
  ;; sends — every assertion about that screen still passes, and the screen is
  ;; wrong in production.
  ;;
  ;; **It moved here when `slopp-server.ui.client.check-map` was deleted**, and the
  ;; population got better on the way. It used to iterate a hand-maintained map
  ;; of path to generated `-check`; it now reads the DESCRIPTORS, whose
  ;; `:rest/response` is the schema the endpoint published and which the pages
  ;; name when they ask. So the set of endpoints checked is exactly the set this
  ;; app reads, derived rather than listed.
  ;;
  ;; `^:external` because a malli validator reaches outside the process by
  ;; slopp's gate, which is what put the retired checks namespace in that tier.
  (let [descriptors (->> (ns-publics 'slopp-server.ui.wire.api)
                         vals
                         (map deref)
                         (filter #(and (map? %) (:http/path %) (:rest/response %))))]

    (testing "the populations are real — an empty one on either side would make
              the loop below pass by iterating nothing"
      (is (seq descriptors))
      (is (seq page/answers)))

    (testing "every endpoint this app READS and cans is canned with a shape its
              own published contract accepts"
      (doseq [d descriptors
              :let [path (:http/path d)
                    response (:rest/response d)]
              ;; only what the fixture actually answers. An endpoint the
              ;; descriptors carry and the fixture does not can is covered by
              ;; `every-declared-request-path-has-a-CANNED-answer`, which asks
              ;; that question from the other side — what the PAGES request.
              :when (contains? page/answers path)]
        (testing path
          (let [canned (get page/responses (get page/answers path))]
            (is (some? canned)
                (str path " is mapped to a response key that has no entry"))
            (when canned
              (is (m/validate response canned)
                  (str path " is canned with a shape its own published contract"
                       " refuses — every screen test using it passes against"
                       " data no server sends. "
                       (pr-str (m/explain response canned)))))))))))

(deftest the-dashboard-joins-four-documents-and-each-lands-in-its-own-panel
  ;; The Dashboard asks FOUR times, and twice of one endpoint under different
  ;; splits. `page/answers` keys the canned fixture by PATH, so under
  ;; `page/page` both cost asks resolve to one document and whichever panel it
  ;; does not match renders its empty state — which means the headless screen
  ;; cannot check this wiring at all.
  ;;
  ;; `probe!` keys by the REQUEST, params included, so it can. This is the only
  ;; assertion on that joint: a page that passed the by=ask document to the
  ;; growth panel, or dropped the second ask, would render `not counted`
  ;; forever and every other test in this store would still be green.
  (let [nodes  (fn [t] (filter string? (tree-seq coll? seq t)))
        text   (fn [v] (str/join " " (nodes v)))
        params {:slug "demo"}
        page   (probe! {:path "/p/demo/dashboard"} params
                       [[(views/at-project params api/namespaces) {}
                         [{:ns "demo.core" :forms 7}]]
                        [(views/at-project params api/timeline) {}
                         {:commit-points [{:at "2026-09-01 10:00"}]}]
                        ;; the two SPLITS, deliberately carrying numbers that
                        ;; cannot be confused for each other's
                        [(views/at-project params api/cost) {:by "ask"}
                         {:by "ask"
                          :rows [{:ask "d1" :intent "the ask that spent"
                                  :model {:tokens 900 :cost-usd 1.5 :requests 2}}]}]
                        [(views/at-project params api/cost) {:by "commit-point"}
                         {:by "commit-point"
                          :rows [{:commit "c1" :at 100 :forms 90 :namespaces 9
                                  :turns 1 :calls 10 :refused {:count 0 :pct 0}
                                  :wall {:active-ms 100 :slopp-ms 20 :outside-ms 80 :idle-ms 5}
                                  :rent {:carried-chars 300}}]}]])
        t      (text (pages/dashboard-page page))]

    (testing "the by=ask split reaches the TOKEN panel"
      (is (re-find #"900 tokens" t))
      (is (re-find #"the ask that spent" t)))

    (testing "the by=commit-point split reaches GROWTH — a different panel and a
              different number, so neither can be satisfied by the other"
      (is (re-find #"90 forms" t))
      (is (re-find #"9 namespaces" t)))

    (testing "and the same split reaches EFFORT, which reads keys the growth
              panel never touches"
      (is (re-find #"10 calls" t))
      (is (re-find #"300 characters" t)))

    (testing "none of the four panels fell back to its empty state, which is how
              a dropped ask would look"
      (is (not (re-find #"(?i)not counted" t)))
      (is (not (re-find #"(?i)not exported" t))))))
