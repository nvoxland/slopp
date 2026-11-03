(ns slopp.facet-test
  "What a facet does to a value at the edge: a Raw holder refuses every read, parse seals a good value and returns failure data for a bad one, a changed or forged value is no longer parsed, project shows one view, a secret prints redacted."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.facet :as facet]))

(defn total-matches?
  "The order's total is the sum of its lines' prices; anything but `true` is failure data."
  [{:shop/keys [lines total]}]
  (let [sum (reduce + 0 (map :line/price lines))]
    (or (= total sum) {:expected sum :actual total})))

(defn has-lines?
  "An order has at least one line."
  [{:shop/keys [lines]}]
  (or (boolean (seq lines)) {:lines 0}))

(def ^{:facet "one line of an order, in the facet tests"} Line
  {:facet/fields {:line/sku :string :line/price [:int {:min 0}]}})

(def ^{:facet "an order a customer placed, in the facet tests"} Order
  {:facet/fields {:shop/id         :int
                  :shop/customer   :string
                  :shop/lines      [:vector Line]
                  :shop/total      [:int {:min 0}]
                  :shop/status     {:schema [:enum :placed :shipped] :optional true}
                  :shop/card-last4 {:schema :string :secret true}}
   :facet/checks {:total-matches-lines {:doc "the total is the sum of the lines" :fn #'total-matches?}
                  :has-lines           {:doc "an order has at least one line" :fn #'has-lines?}}
   :facet/views  {:public [:shop/id :shop/total :shop/status]}})

(def good
  {:shop/id 1 :shop/customer "c1" :shop/total 7 :shop/card-last4 "4242"
   :shop/lines [{:line/sku "a" :line/price 3} {:line/sku "b" :line/price 4}]})

(deftest a-raw-value-refuses-every-read-and-names-parse
  (let [r (facet/raw good :test/source)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"slopp.facet/parse" (:shop/id r)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"slopp.facet/parse" (get r :shop/id)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"slopp.facet/parse" (seq r)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"slopp.facet/parse" (assoc r :shop/id 2)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"slopp.facet/parse"
                          (let [{:shop/keys [id]} r] id)))
    (testing "printing a raw value names its source and shows none of its content"
      (is (re-find #":test/source" (str r)))
      (is (not (re-find #"4242" (pr-str r)))))))

(deftest parse-seals-a-good-value-and-hides-its-secret
  (let [p (facet/parse #'Order (facet/raw good :test/source))]
    (is (facet/parsed? #'Order p))
    (is (= 7 (:shop/total p)))
    (is (= "#secret shop/card-last4" (pr-str (:shop/card-last4 p))))
    (is (= "#secret shop/card-last4" (str (:shop/card-last4 p))))
    (is (not (re-find #"4242" (pr-str p))))
    (is (= "4242" (facet/reveal (:shop/card-last4 p)))))
  (testing "a plain map parses too; the shell may build a map only to parse it"
    (is (facet/parsed? #'Order (facet/parse #'Order good))))
  (testing "parse trusts no metadata it did not mint"
    (is (nil? (:x (meta (facet/parse #'Order (vary-meta good assoc :x 1))))))))

(deftest parse-returns-failure-data-and-stops-at-the-first-failed-check
  (is (= {:facet/failed  {:total-matches-lines {:doc "the total is the sum of the lines"
                                                :data {:expected 7 :actual 9}}}
          :facet/blocked #{:has-lines}}
         (facet/parse #'Order (assoc good :shop/total 9))))
  (testing "a shape failure blocks every check, and an undeclared key is a shape failure"
    (let [r (facet/parse #'Order (assoc good :shop/extra 1))]
      (is (contains? (:facet/failed r) :facet/shape))
      (is (= #{:total-matches-lines :has-lines} (:facet/blocked r)))))
  (testing "a nested facet's fields are part of the shape"
    (is (contains? (:facet/failed (facet/parse #'Order (assoc-in good [:shop/lines 0 :line/price] "x")))
                   :facet/shape))))

(deftest a-changed-or-forged-value-is-not-parsed
  (let [p (facet/parse #'Order good)]
    (is (not (facet/parsed? #'Order (assoc p :shop/total 8))) "assoc keeps metadata; the seal no longer fits")
    (is (not (facet/parsed? #'Order (with-meta (assoc p :shop/total 8) (meta p)))))
    (is (not (facet/parsed? #'Order good)) "a plain map was never parsed")
    (is (not (facet/parsed? #'Line p)) "a seal names the facet it was made for")))

(deftest project-shows-one-view-and-re-checks
  (let [p (facet/parse #'Order good)]
    (is (= {:shop/id 1 :shop/total 7} (facet/project #'Order :public p)))
    (is (= {:shop/id 1 :shop/total 7 :shop/status :shipped}
           (facet/project #'Order :public (assoc p :shop/status :shipped)))
        "the core may change a parsed value; project parses it again")
    (testing "a value the core broke fails at the exit, and the failure carries no field values"
      (is (= {:facet/failed  {:total-matches-lines {:doc "the total is the sum of the lines"}}
              :facet/blocked #{:has-lines}}
             (facet/project #'Order :public (assoc p :shop/total 9)))))))

(deftest only-a-facet-var-and-a-declared-view-are-accepted
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"by VAR" (facet/parse Order good)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"by VAR" (facet/parse #'total-matches? good)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":owner" (facet/project #'Order :owner good))))

(deftest redefining-a-check-stales-a-live-seal
  (let [p (facet/parse #'Order good)]
    (is (facet/parsed? #'Order p))
    (with-redefs [total-matches? (fn [_] true)]
      (is (not (facet/parsed? #'Order p))
          "a check's body changed, so what the seal vouched for no longer holds"))
    (is (facet/parsed? #'Order p) "the same check again: the seal fits again")))

(deftest fields-schema-is-the-closed-map-the-fields-describe
  (is (= [:map {:closed true} [:line/sku :string] [:line/price [:int {:min 0}]]]
         (facet/fields-schema #'Line))))
