(ns slopp.lab.falsegreen-test
  "Cover for the false-green instrument's two pure halves: which forms recent episodes changed, and the rates over mutation results."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.lab.falsegreen :as fg]))

(deftest the-forms-are-what-the-last-episodes-authored
  (let [ds [{:op :replace :form-id "f-old"}
            {:op :done}
            {:op :replace :form-id "f1"}
            {:op :replace :form-id "f-sys" :system true}
            {:op :verify}
            {:op :done}
            {:op :add :form-id "f2"}
            {:op :replace :form-id "f1"}
            {:op :done}]]
    (testing "the last two episodes, distinct, without the pipeline's own writes"
      (is (= ["f1" "f2"] (fg/episode-form-ids ds 2))))
    (testing "one more episode reaches further back"
      (is (= ["f-old" "f1" "f2"] (fg/episode-form-ids ds 3))))))

(deftest the-rate-counts-surviving-defects-and-says-what-it-could-not-test
  (let [r (fg/rates [{:status :killed :mutants 4 :killed 3 :timeout 1 :invalid 0 :survivors []}
                     {:status :survived :mutants 5 :killed 2 :timeout 0 :invalid 1
                      :survivors [{:mutation "a"} {:mutation "b"}]}
                     {:status :untested}
                     {:status :untested}
                     {:status :no-sites}
                     {:status :unchecked}])]
    (testing "a hang counts as caught: a done that never finishes is not green"
      (is (= {:caught 6 :survived 2 :invalid 1} (:mutants r))))
    (testing "per mutant and per form, over the functions that have a test"
      (is (= 0.25 (get-in r [:false-green :per-mutant])))
      (is (= 0.5 (get-in r [:false-green :per-form]))))
    (testing "an untested function lets every defect through, so it counts when included"
      (is (= 0.75 (get-in r [:false-green :per-form-incl-untested]))))
    (testing "what was not measured is counted, not dropped"
      (is (= {:forms 6 :tested 2 :untested 2 :no-sites 1 :unchecked 1}
             (select-keys r [:forms :tested :untested :no-sites :unchecked]))))
    (testing "no denominator is no rate, not zero"
      (is (nil? (get-in (fg/rates []) [:false-green :per-mutant]))))))

(deftest a-count-a-result-does-not-carry-is-zero
  (is (= {:caught 0 :survived 1 :invalid 0}
         (:mutants (fg/rates [{:status :survived :survivors [{:mutation "a"}]}])))))

(deftest a-form-whose-mutants-all-failed-to-load-moves-no-rate
  ;; slopp-s8x.4: it used to read :killed, so the rate counted it as a tested,
  ;; non-surviving form exactly where the oracle had vanished
  (let [r (fg/rates [{:form 'a/f :status :unchecked :invalid 3}
                     {:form 'a/g :status :killed :killed 1}])]
    (is (= 1 (:tested r)))
    (is (= 1 (:unchecked r)))
    (is (= 3 (get-in r [:mutants :invalid])) (pr-str r))
    (is (= 0.0 (get-in r [:false-green :per-mutant])))))
