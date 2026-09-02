(ns slopp.observe-test
  "`query_observe` and `query_macroexpand`: what actually FLOWED through a
  form, and what a macro actually expanded to.

  Both exist because everything else slopp answers is static, and these are
  the two questions where a static answer is routinely wrong — a value's real
  shape at a call site, and code that no reader of the source can see. Small
  namespace, and the pair belongs together: they are the store admitting the
  limits of reading."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.ops.external :as external] [slopp.store :as store] [slopp.ops.engine :as engine]))

(deftest ^:external observe-captures-what-flows-through
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ob.core
                   (str "(ns ob.core)\n"
                        "(defn area [shape] (* (:w shape) (:h shape)))\n"
                        "(defn total-area [shapes] (reduce + (map area shapes)))\n"
                        "(defn risky [x] (if (neg? x) (throw (ex-info \"neg!\" {})) x))\n"))
      (testing "args and returns are captured per call, driver result included"
        (let [r (ops/query-observe sess 'ob.core 'area
                                   "(ob.core/total-area [{:w 2 :h 3} {:w 4 :h 5}])")]
          (is (= 2 (:count r)))
          (is (= "26" (:result r)))
          (is (= ["{:w 2, :h 3}"] (:args (first (:calls r)))))
          (is (= "6" (:ret (first (:calls r)))))))
      (testing "exceptions are recorded, not swallowed"
        (let [r (ops/query-observe sess 'ob.core 'risky
                                   "(try (ob.core/risky -1) (catch Exception _ :caught))")]
          (is (= ":caught" (:result r)))
          (is (re-find #"neg!" (:threw (first (:calls r)))))))
      (testing "the sample limit bounds capture"
        (let [r (ops/query-observe sess 'ob.core 'area
                                   "(ob.core/total-area (repeat 50 {:w 1 :h 1}))"
                                   :limit 5)]
          (is (= 5 (:count r)))))
      (testing "instrumentation is restored afterwards"
        (is (= [6] (ops/query-eval sess "(ob.core/area {:w 2 :h 3})"))))
      (testing "the driver is observe-gated (T5 applies here too)"
        (is (:error (ops/query-observe sess 'ob.core 'area "(def sneaky 1)"))))
      (finally (ops/close! sess)))))

(deftest ^:external macroexpand-is-a-first-class-question
  (let [sess (external/open!)]
    (try
      (let [r (ops/query-macroexpand sess "(when x y z)")]
        (is (re-find #"\(if x" (:expand-1 r)))
        (is (re-find #"do" (:expand-1 r))))
      (testing "unparseable input errors cleanly"
        (is (:error (ops/query-macroexpand sess "(when x"))))
      (finally (ops/close! sess)))))

(deftest an-observation-carries-per-namespace-TIME-when-the-run-measured-it
  ;; Shard balancing weights by image BOOTS, and the tier costs its slowest
  ;; shard, so the proxy is paid on every whole-store check. The observation is
  ;; where the honest number has to land: it is already appended per run and
  ;; already carries the scope those namespaces came from.
  ;;
  ;; A bare map stands in for the store: `observation-of` reads it only to
  ;; qualify BARE failure names, and every run here is green.
  (testing "ABSENT when the build wrote no timing"
    ;; the failure this guards is a balancer reading a missing measurement as
    ;; zero and packing an expensive namespace as if it were free. Absent and
    ;; zero must not be the same value.
    (is (not (contains? (external/observation-of {} {:status :green :ran 3})
                        :ns-ms))))
  (testing "carried when the run measured it"
    (is (= '{a.core-test 1200 b.core-test 300}
           (:ns-ms (external/observation-of
                    {} {:status :green :ran 3
                        :ns-ms '{a.core-test 1200 b.core-test 300}})))))
  (testing "and it rides beside the evidence rather than replacing any of it"
    (let [o (external/observation-of {} {:status :green :ran 3
                                         :ns-ms '{a.core-test 1200}})]
      (is (= :external (:tier o)))
      (is (= :green (:status o)))
      (is (= 3 (:ran o)))
      (is (= [] (:failures o))))))

(deftest an-observation-says-WHICH-namespaces-were-green-not-only-the-run
  ;; s19: the verdict cache's gate cleared — 44.6% of 28,639 namespace-runs
  ;; re-verified content already green at exactly that content. But the
  ;; record could not authorize a single skip: `:status` was the whole
  ;; RUN's, so one red namespace made the other fifty unusable, while the
  ;; `:closure` hash beside it was already per namespace. A verdict keyed by
  ;; content has to be recorded at the grain the content is keyed at.
  ;;
  ;; A bare map stands in for the store: observation-of reads it only to
  ;; qualify BARE failure names.
  (testing "a green run clears every namespace it measured"
    (is (= '{a.core-test :green b.core-test :green}
           (:ns-status (external/observation-of
                        {} {:status :green :ran 3
                            :ns-ms '{a.core-test 1200 b.core-test 300}})))))
  (testing "a red run clears the namespaces that passed and names the one that did not"
    (is (= '{a.core-test :red b.core-test :green}
           (:ns-status (external/observation-of
                        {} {:status :red :ran 3
                            :ns-ms '{a.core-test 1200 b.core-test 300}
                            :failing [{:test 'a.core-test/boom}]})))))
  (testing "a failure nothing can be attributed to clears NOBODY — an unplaced red could be any of them"
    ;; the soundness bar: a cache HIT runs nothing, so a green recorded here
    ;; on a guess persists as a false green until the content changes
    (let [o (external/observation-of {} {:status :red :ran 3
                                         :ns-ms '{a.core-test 1200 b.core-test 300}
                                         :failing [{:test 'mystery}]})]
      (is (empty? (filter #{:green} (vals (:ns-status o)))) (pr-str (:ns-status o)))))
  (testing "a run that measured nothing says nothing — absent, never empty"
    (is (not (contains? (external/observation-of {} {:status :green :ran 3})
                        :ns-status)))))

(deftest a-NARROWED-run-clears-no-namespace
  ;; the hole this closes was mine, landed the same day: :ns-status is
  ;; derived from the namespaces that RAN, and a run narrowed by :only runs
  ;; a handful of tests inside them — done's external slice is exactly such
  ;; a run. Marking the namespace green there would authorize skipping
  ;; tests that never executed, which is the one failure a content-keyed
  ;; cache cannot detect: a hit runs nothing.
  (testing "a whole-namespace run clears its namespaces"
    (is (= '{a.core-test :green}
           (:ns-status (external/observation-of
                        {} {:status :green :ran 2 :ns-ms '{a.core-test 10}})))))
  (testing "a run narrowed to named tests clears NONE of them — its evidence is the tests it named"
    (let [o (external/observation-of
             {} {:status :green :ran 1 :ns-ms '{a.core-test 10}
                 :only '[a.core-test/one]})]
      (is (not (contains? o :ns-status)) (pr-str o))
      (is (= '[a.core-test/one] (:only o)) "and it still records WHICH tests it ran"))))

(deftest a-test-green-at-exactly-this-content-does-not-run-again
  ;; s19: the verdict-cache gate, measured over the whole journal, cleared
  ;; at 44.6% (28,639 namespace-runs, 12,786 already green at exactly that
  ;; content). This is the decision it authorized — done-grain only.
  ;;
  ;; A cache HIT RUNS NOTHING, so every rule here is the conservative one:
  ;; the evidence must be GREEN, must be at THIS content, and a narrowed
  ;; run clears only the tests it named.
  (let [st  (-> (store/empty-store)
                (store/ingest 't.core "(ns t.core)\n(defn f [] 1)\n")
                (store/ingest 't.core-test
                              (str "(ns t.core-test\n  (:require [clojure.test :refer [deftest is]]\n"
                                   "            [t.core :as c]))\n"
                                   "(deftest one (is (= 1 (c/f))))\n(deftest two (is true))\n")))
        h     (get (engine/closure-hashes st '[t.core-test]) 't.core-test)
        obs   (fn [result] {:op :observe :closure {'t.core-test h} :result result})
        tests '[t.core-test/one t.core-test/two]]
    (testing "a narrowed green clears exactly the tests it named"
      (let [r (external/reusable-verdicts st [(obs {:status :green :only '[t.core-test/one]})] tests)]
        (is (= '[t.core-test/one] (:reused r)) (pr-str r))
        (is (= '[t.core-test/two] (:run r)) (pr-str r))))
    (testing "a whole-namespace green clears the namespace"
      (let [r (external/reusable-verdicts st [(obs {:status :green :ns-status '{t.core-test :green}})] tests)]
        (is (= tests (:reused r)) (pr-str r))
        (is (empty? (:run r)) (pr-str r))))
    (testing "the same green at DIFFERENT content clears nothing — that is the whole key"
      (let [r (external/reusable-verdicts
               st [{:op :observe :closure {'t.core-test "a-hash-from-before-the-edit"}
                    :result {:status :green :ns-status '{t.core-test :green}}}]
               tests)]
        (is (empty? (:reused r)) (pr-str r))
        (is (= tests (:run r)) (pr-str r))))
    (testing "a RED run clears nothing, at any content"
      (let [r (external/reusable-verdicts st [(obs {:status :red :ns-status '{t.core-test :red}})] tests)]
        (is (empty? (:reused r)) (pr-str r))))
    (testing "and with no evidence at all, everything runs"
      (is (= tests (:run (external/reusable-verdicts st [] tests)))))))
