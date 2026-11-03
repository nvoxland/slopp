(ns slopp.edit.facet-test
  "Tests for the facet-integrity write gates: a declaration with a missing check, a view naming an undeclared field, two owners for one key, and a parse that takes a computed declaration are refused; a sound facet passes."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.edit.facet :as edit.facet]
            [slopp.edit.gates :as gates]
            [slopp.store :as store]))

(defn- st
  "A store holding `shop.order` with `body` after its ns form."
  ([body] (st (store/empty-store) 'shop.order body))
  ([base ns-sym body]
   (store/ingest base ns-sym (str "(ns " ns-sym " (:require [slopp.facet :as facet]))\n" body))))

(defn- order
  "A facet def whose declaration map has `decl` spliced in after its fields."
  [decl]
  (str "(defn total-ok? [o] true)\n"
       "(def ^{:facet \"an order\"} Order {:facet/fields {:shop/id :int :shop/total :int} " decl "})\n"))

(deftest a-facet-declaration-says-only-what-exists
  (let [check (fn [body] (edit.facet/facet-declaration (st body) 'shop.order 'Order))]
    (is (nil? (check (order ":facet/checks {:total-ok {:doc \"the total is right\" :fn #'total-ok?}} :facet/views {:public [:shop/id]}")))
        "a sound facet passes")
    (testing "each way a declaration can name something that is not there"
      (doseq [[decl expect] [[":facet/checks {:total-ok {:doc \"d\" :fn #'nope?}}" #"nope\?"]
                             [":facet/checks {:total-ok {:doc \"d\" :fn total-ok?}}" #"#'"]
                             [":facet/checks {:total-ok {:fn #'total-ok?}}" #":doc"]
                             [":facet/views {:public [:shop/id :shop/secret]}" #":shop/secret"]]]
        (is (re-find expect (str (check (order decl)))) decl)))
    (is (re-find #":facet/fields" (str (check "(def ^{:facet \"x\"} Order (merge {} {}))")))
        "the value is the declaration, written as a map literal")))

(deftest a-key-has-one-owner
  (let [base (st (order ""))]
    (is (re-find #"shop.order/Order"
                 (str (edit.facet/facet-declaration
                       (st base 'shop.other "(def ^{:facet \"b\"} Other {:facet/fields {:shop/id :int}})")
                       'shop.other 'Other)))
        "a second owner for :shop/id is refused, naming the first")
    (is (nil? (edit.facet/facet-declaration
               (st base 'shop.order-test "(def ^{:facet \"fixture\"} Fx {:facet/fields {:shop/id :int}})")
               'shop.order-test 'Fx))
        "a test namespace's fixture facet is not a second owner")))

(deftest parse-and-project-take-the-facet-by-var
  (let [check (fn [body] (edit.facet/facet-by-var (st (str (order "") body)) 'shop.order 'h))]
    (is (nil? (check "(defn h [x] (facet/parse #'Order x))")))
    (is (nil? (check "(defn h [x] (facet/project #'shop.order/Order :public x))")))
    (doseq [body ["(defn h [x] (facet/parse Order x))"
                  "(defn h [x] (facet/parse (dissoc Order :facet/checks) x))"
                  "(defn h [x] (facet/parsed? #'total-ok? x))"]]
      (is (re-find #"by VAR" (str (check body))) body))
    (is (nil? (check "(def h '(facet/parse Order x))")) "a quoted call is data")))

(deftest both-facet-gates-are-registered
  (doseq [g [#'edit.facet/facet-declaration #'edit.facet/facet-by-var]]
    (is (some #{g} gates/per-form-write-gates) (str g " is a registered write gate")))
  (is (= :production (:rule/applies-to (meta #'edit.facet/facet-by-var)))
      "a test may hand parse a non-facet on purpose, to watch it refuse"))

(deftest fields-must-be-a-map-even-inside-a-map-declaration
  (is (re-find #":facet/fields"
               (str (edit.facet/facet-declaration
                     (st "(def ^{:facet \"x\"} Order {:facet/fields [:shop/id :int]})") 'shop.order 'Order)))))

(deftest only-facet-defs-are-facets
  (is (= [['shop.order 'Order]]
         (keys (edit.facet/facets (st (str (order "") "(defn f [] {:facet/fields {:x/a :int}})\n")))))
      "a form that only mentions :facet/fields is not a facet"))
