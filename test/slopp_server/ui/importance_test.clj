(ns slopp-server.ui.importance-test
  "Tests for the ranking, which exist because a ranking is the kind of thing
  that can be plausible and wrong.

  A colour is applied or it is not, and a broken render is visible in a
  screenshot. A ranking that puts the wrong form first LOOKS exactly like a
  ranking that puts the right one first, and the only way to find out is to
  state the expected order in advance and check it. Two of the bugs pinned
  here were found that way and not by looking: a recursive form counting its
  own mass twice, and a constant axis flooring the whole ramp so the light end
  was never reached.

  The fixtures are deliberately tiny and hand-computable — a three-link chain,
  a two-form cycle — because a fixture I cannot do in my head cannot tell me
  the arithmetic is wrong, only that it changed."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp-server.ui.importance :as importance]))

(deftest reach-is-transitive-and-survives-a-cycle
  (testing "everything downstream, not just the direct edges"
    (is (= #{"b" "c"} (importance/reach {"a" ["b"] "b" ["c"] "c" []} "a")))
    (is (= #{"c"}     (importance/reach {"a" ["b"] "b" ["c"] "c" []} "b")))
    (is (= #{}        (importance/reach {"a" ["b"] "b" ["c"] "c" []} "c"))))
  (testing "mutual recursion terminates, and each sees the other"
    (let [g {"p" ["q"] "q" ["p"]}]
      (is (= #{"p" "q"} (importance/reach g "p")))
      (is (= #{"p" "q"} (importance/reach g "q")))))
  (testing "a self-call is reach, not an infinite walk"
    (is (= #{"r"} (importance/reach {"r" ["r"]} "r"))))
  (testing "an edge to something the graph does not hold is ignored — the row
            for a callee outside this namespace is simply not here"
    (is (= #{"b"} (importance/reach {"a" ["b" "elsewhere"] "b" []} "a"))))
  (testing "an unknown start reaches nothing rather than throwing"
    (is (= #{} (importance/reach {"a" []} "nope")))))

(deftest covers-is-what-you-read-and-carries-is-what-leans-on-it
  ;; a --> b --> c, with a fourth form off to the side
  (let [rows [{:name "a" :mass 10 :calls ["b"]}
              {:name "b" :mass 20 :calls ["c"]}
              {:name "c" :mass 30 :calls []}
              {:name "lonely" :mass 5 :calls []}]
        w    (importance/weigh rows)]
    (testing "covers is the form plus everything it transitively calls —
              the code you take on by reading it"
      (is (= 60 (:covers (w "a"))) "10 + 20 + 30")
      (is (= 50 (:covers (w "b"))))
      (is (= 30 (:covers (w "c"))) "a leaf covers only itself")
      (is (= 5  (:covers (w "lonely")))))
    (testing "carries is the form plus everything that transitively calls it —
              what stops making sense if it goes"
      (is (= 10 (:carries (w "a"))) "nothing calls the root")
      (is (= 30 (:carries (w "b"))) "20 + a's 10")
      (is (= 60 (:carries (w "c"))) "30 + 20 + 10")
      (is (= 5  (:carries (w "lonely")))))
    (testing "both include the form ITSELF, so a big leaf is not weightless
              and a tiny root is not free"
      (is (every? #(<= (:mass %) (:covers (w (:name %)))) rows))
      (is (every? #(<= (:mass %) (:carries (w (:name %)))) rows))))
  (testing "a missing mass counts as zero rather than throwing — a row the
            wire did not fill should not take the ranking down with it"
    (let [w (importance/weigh [{:name "x" :calls []} {:name "y" :mass 4 :calls ["x"]}])]
      (is (= 0 (:covers (w "x"))))
      (is (= 4 (:covers (w "y"))))))
  (testing "mutual recursion gives both the same covers, which is correct:
            you cannot read either without the other"
    (let [w (importance/weigh [{:name "p" :mass 7 :calls ["q"]}
                        {:name "q" :mass 9 :calls ["p"]}])]
      (is (= 16 (:covers (w "p")) (:covers (w "q")))))))

(deftest steps-rank-within-the-namespace-and-bucket-into-a-usable-ramp
  (let [chain (fn [n] (vec (for [i (range n)]
                             {:name (str "f" i) :mass (* 10 (inc i))
                              :calls (if (zero? i) [] [(str "f" (dec i))])
                              :callers-out 0})))]
    (testing "the deepest form in a chain is the darkest: it covers everything"
      (let [s (importance/steps (chain 8) 4)]
        (is (= 3 (s "f7")))
        (is (= 0 (s "f0")))))
    (testing "every step is in range, and the ramp is USED — a listing where
              everything lands in one bucket carries no information"
      (let [s (importance/steps (chain 8) 4)]
        (is (every? #(<= 0 % 3) (vals s)))
        (is (= #{0 1 2 3} (set (vals s))))))
    (testing "ONE definition is not a ramp: it gets the top step rather than a
              division by zero or a mid-grey that means nothing"
      (is (= {"only" 3} (importance/steps [{:name "only" :mass 5 :calls []}] 4))))
    (testing "identical rows tie rather than being ordered by accident"
      (let [s (importance/steps [{:name "a" :mass 9 :calls []}
                          {:name "b" :mass 9 :calls []}
                          {:name "c" :mass 9 :calls []}] 4)]
        (is (apply = (vals s)))))
    (testing "no rows is an empty map, not a crash"
      (is (= {} (importance/steps [] 4))))
    (testing "PRODUCTION callers count and test callers do not — a form called
              by four deftests is better exercised, not more important"
      (let [rows [{:name "hot"  :mass 10 :calls [] :callers-out 9 :callers-out-test 0}
                  {:name "cold" :mass 10 :calls [] :callers-out 0 :callers-out-test 99}]
            s    (importance/steps rows 4)]
        (is (> (s "hot") (s "cold")))))))
