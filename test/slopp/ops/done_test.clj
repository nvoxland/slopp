(ns slopp.ops.done-test
  "The done-point require-prune candidate logic — pure over the store, so it
   runs in-image. The effectful try-remove-verify-restore loop is exercised
   through a real session in slopp.ops-test."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.ops.done :as done] [slopp.ops.external :as external] [slopp.kernel.boot :as boot] [slopp.ops :as ops]))

(defn- store-with-requires []
  (store/ingest (store/empty-store) 'du.core
                (str "(ns du.core\n"
                     "  (:require [clojure.set :as s]\n"
                     "            ^:side-effect [du.eff :as e]\n"
                     "            [du.used :as u]))\n"
                     "(defn f [] (u/g))\n")))

(deftest unused-requires-finds-only-prunable-candidates
  (let [st (store-with-requires)
        cands (done/unused-requires st 'du.core)]
    (testing "an unused require is a candidate; a USED one is not"
      (is (= '[clojure.set] (mapv :lib cands)) (pr-str cands)))
    (testing "a candidate carries the ^:side-effect re-add form for the restore path"
      (is (= "^:side-effect [clojure.set :as s]" (:marked (first cands)))))
    (testing "a require already marked ^:side-effect is NOT re-offered (no churn)"
      (is (not (contains? (set (map :lib cands)) 'du.eff)) (pr-str cands)))))

(deftest side-effect-required-reads-the-marker
  (let [st (store-with-requires)]
    (is (true?  (done/side-effect-required? st 'du.core 'du.eff)))
    (is (false? (done/side-effect-required? st 'du.core 'clojure.set)))
    (is (false? (done/side-effect-required? st 'du.core 'du.used)))))

(deftest ^:external a-stale-host-rides-the-verdict-not-just-the-brief
  ;; The currency record has always existed; it only ever reached
  ;; session_brief. A host running superseded code produced verdicts that said
  ;; nothing about it, and the resulting investigation eliminated four correct
  ;; mechanisms in `rt` before finding the stale process.
  ;;
  ;; boot-info is nil in any process that did not boot from a store (every
  ;; test JVM), so the record is planted here — that IS the path under test:
  ;; done reaches the kernel carrier and puts what it finds on the verdict.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hs.core "(ns hs.core)\n(defn f \"F.\" [] 1)\n")
      (testing "no boot record — the process cannot be stale, and the verdict is quiet"
        (let [r (external/done! sess :label "clean")]
          (is (nil? (get-in r [:findings :host-stale])) (pr-str (:findings r)))))
      (testing "a snapshot host with code deltas since boot rides the verdict"
        (reset! boot/boot-info {:dir "." :mode :snapshot :booted-at 1})
        (ops/edit-replace! sess 'hs.core 'f "(defn f \"F.\" [] 2)"
                           :prompt "a code delta the host cannot be running")
        (let [r  (external/done! sess :label "stale host")
              hs (get-in r [:findings :host-stale])]
          (is (some? hs) (pr-str (:findings r)))
          (is (= :snapshot (:mode hs)))
          (is (re-find #"(?i)suspect" (str (:verdict-note hs)))
              "it must say what staleness means for THIS result")))
      (finally
        (reset! boot/boot-info nil)
        (ops/close! sess)))))

(deftest a-verdict-covers-forms-the-branch-must-actually-have
  ;; Friction #14, ranked third by both agents and the one that makes every
  ;; other green untrustworthy. `done` computes green against a THREAD image
  ;; holding the whole episode, and then lands. If anything drops between
  ;; those two moments the green is honest and wrong — measured with two
  ;; forms, where `http.dispatch/handle!` landed and `http/context` did not,
  ;; and every shell then served 200 while the commit-point read green.
  ;;
  ;; A red that lies costs an investigation. A GREEN that lies ships.
  (let [judged #{['app.core "f"] ['app.core "g"] ['app.web "handle"]}]
    (testing "everything judged is on the branch — nothing to say"
      (is (empty? (done/landed-gap
                   judged
                   {'app.core {:elements [{:name 'f} {:name 'g}]}
                    'app.web  {:elements [{:name 'handle}]}}))))

    (testing "a form the verdict covered that the branch does not have is NAMED"
      (is (= [['app.web "handle"]]
             (done/landed-gap
              judged
              {'app.core {:elements [{:name 'f} {:name 'g}]}
               'app.web  {:elements [{:name 'other}]}}))))

    (testing "a whole namespace that never arrived counts every form in it"
      (is (= [['app.web "handle"]]
             (done/landed-gap
              judged
              {'app.core {:elements [{:name 'f} {:name 'g}]}}))))

    (testing "unnamed elements cannot be matched and must not mask a gap"
      ;; an ns form and a bare comment carry no :name; counting them as
      ;; present would let any namespace vouch for any form
      (is (= [['app.core "g"]]
             (done/landed-gap
              #{['app.core "f"] ['app.core "g"]}
              {'app.core {:elements [{:name 'f} {:name nil} {}]}}))))))

(deftest a-verdict-covers-declarations-the-branch-must-actually-have
  ;; `landed-gap` compares the FORMS a verdict covered against the branch. A
  ;; `module_dep` is not a form — it is a `:module-edge` delta folded into the
  ;; manifest — so it carries exactly the hazard that check exists for and sits
  ;; outside its reach by construction.
  ;;
  ;; Measured: three edges declared and landed were gone from the trunk hours
  ;; later while still present in the declaring session's store. The declaring
  ;; agent's full_check stayed green (it reads its own session); another
  ;; agent's went red on twenty undeclared edges belonging to somebody who
  ;; could not see the loss, and their commit-point was blocked by it.
  ;;
  ;; The MECHANISM is still unknown — the fold is edge-grained, `merge-logs`
  ;; unions concurrent declarations, and a two-agent land preserves them
  ;; (`thread-test/a-module-edge-survives-another-agents-landing`). So this is
  ;; the detector, and it is also how the mechanism gets caught: it names the
  ;; loss at the done that loses it, with the episode still in hand.
  (let [declared [{:from "app.web" :to "app.core"}
                  {:from "app.web" :to "app.util" :test-only true}]]
    (testing "everything declared is on the branch — nothing to say"
      (is (empty? (done/declared-edge-gap
                   declared
                   {"app.web" #{"app.core"}}
                   {"app.web" #{"app.util"}}))))

    (testing "a production edge the branch does not have is NAMED"
      (is (= [{:from "app.web" :to "app.core"}]
             (done/declared-edge-gap declared {} {"app.web" #{"app.util"}}))))

    (testing "a TEST-ONLY edge is checked against the test manifest, not the production one"
      ;; the two are different fields on purpose — a fixture require must not
      ;; open the production graph — so checking one against the other would
      ;; report every test-only declaration as lost
      (is (= [{:from "app.web" :to "app.util" :test-only true}]
             (done/declared-edge-gap declared {"app.web" #{"app.core"}} {})))
      (is (empty? (done/declared-edge-gap
                   [{:from "app.web" :to "app.util" :test-only true}]
                   {} {"app.web" #{"app.util"}}))))

    (testing "an edge to a module the branch has never heard of counts as missing"
      (is (= [{:from "app.web" :to "app.core"}]
             (done/declared-edge-gap [{:from "app.web" :to "app.core"}]
                                     {"other.thing" #{"app.core"}} {}))))))

(deftest episode-spread-counts-production-forms-namespaces-and-modules
  ;; Agent success falls under 10% once a task touches three or more units.
  ;; The episode knows what it touched; done REPORTS the spread (never gates).
  (let [st  (-> (store/empty-store)
                (store/ingest 'sp.a.core "(ns sp.a.core)\n(defn f [] 1)\n(defn g [] 2)\n")
                (store/ingest 'sp.a.util "(ns sp.a.util)\n(defn h [] 3)\n")
                (store/ingest 'sp.b.core "(ns sp.b.core)\n(defn k [] 4)\n")
                (store/ingest 'sp.c.core "(ns sp.c.core)\n(defn m [] 5)\n")
                (store/ingest 'sp.a.core-test "(ns sp.a.core-test)\n(defn t [] 6)\n"))
        fid (fn [n nm] (:id (store/form-named st n nm)))]
    (testing "counts forms, distinct namespaces and distinct modules"
      (is (= {:forms 4 :namespaces 3 :modules 2}
             (done/episode-spread st [(fid 'sp.a.core 'f) (fid 'sp.a.core 'g)
                                      (fid 'sp.a.util 'h) (fid 'sp.b.core 'k)]))))
    (testing "a test namespace's forms are not production spread"
      (is (= {:forms 1 :namespaces 1 :modules 1}
             (done/episode-spread st [(fid 'sp.a.core 'f) (fid 'sp.a.core-test 't)]))))
    (testing "three modules carry a note; fewer carry none"
      (let [wide (done/spread-report st [(fid 'sp.a.core 'f) (fid 'sp.b.core 'k) (fid 'sp.c.core 'm)])]
        (is (= 3 (get-in wide [:spread :modules])))
        (is (re-find #"(?i)split" (str (:spread-note wide)))))
      (let [narrow (done/spread-report st [(fid 'sp.a.core 'f) (fid 'sp.b.core 'k)])]
        (is (= 2 (get-in narrow [:spread :modules])))
        (is (nil? (:spread-note narrow)))))))

(deftest ^:external done-carries-the-episodes-spread
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'spr.core "(ns spr.core)\n(defn f \"F.\" [] 1)\n")
      (let [r (external/done! sess :label "spread")]
        (is (= 1 (get-in r [:spread :modules])) (pr-str (:spread r)))
        (is (= 1 (get-in r [:spread :namespaces])))
        (is (pos? (get-in r [:spread :forms])))
        (is (nil? (:spread-note r))))
      (finally
        (ops/close! sess)))))

(deftest unread-callees-name-what-an-edit-was-made-blind-to
  (let [st  (-> (store/empty-store)
                (store/ingest 'ur.core "(ns ur.core)\n\n(defn a [x] x)\n\n(defn b [x] x)\n\n(defn c [x] (+ (a x) (b x) (d x)))\n\n(defn d [x] x)\n")
                (store/ingest 'ur.core-test "(ns ur.core-test (:require [ur.core :as u]))\n\n(defn t [] (u/a 1))\n"))
        fid (fn [n nm] (:id (store/form-named st n nm)))]
    (is (= '{ur.core/c [ur.core/b]}
           (done/unread-callees st [(fid 'ur.core 'c) (fid 'ur.core 'd) (fid 'ur.core-test 't)]
                                #{(fid 'ur.core 'a)}))
        "a was read, d was written this episode, the test form is not production: b is the blind spot")
    (is (= {} (done/unread-callees st [(fid 'ur.core 'c)] (map #(fid 'ur.core %) '[a b d])))
        "every callee read")))
