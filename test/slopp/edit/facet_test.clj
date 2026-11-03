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

(deftest an-owned-key-in-the-shell-is-used-only-where-it-was-parsed
  (let [base (st (order ""))
        chk  (fn [ns-sym body] (edit.facet/facet-key-ownership (st base ns-sym body) ns-sym 'h))]
    (testing "read, destructured or built by hand in the shell, with no parse in sight"
      (doseq [body ["(defn h [m] (:shop/total m))"
                    "(defn h [{:shop/keys [total]}] total)"
                    "(defn h [] {:shop/total 5})"]]
        (is (re-find #"shop.order/Order" (str (chk 'shop.web body))) body)))
    (testing "where the value was parsed, or by the facet's own namespace"
      (is (nil? (chk 'shop.web "(defn h [m] (:shop/total (facet/parse #'shop.order/Order m)))")))
      (is (nil? (chk 'shop.web "(defn ^{:rest/request shop.order/Order} h [r] (:shop/total (:body r)))"))
          "the route's port parsed the body before the handler ran")
      (is (nil? (chk 'shop.order "(defn h [m] (:shop/total m))"))))
    (testing "inside the region a parsed value is plain data"
      (let [s (first (store/record-module-tier (st base 'shop.core "(defn h [m] (:shop/total m))") "shop.core" :pure))]
        (is (nil? (edit.facet/facet-key-ownership s 'shop.core 'h)))))
    (testing "facets in test namespaces own nothing in the program"
      (is (nil? (edit.facet/facet-key-ownership
                 (st (st (store/empty-store) 'shop.order-test (order "")) 'shop.web "(defn h [m] (:shop/total m))")
                 'shop.web 'h))))
    (is (= :advisory (:rule/severity (meta #'edit.facet/facet-key-ownership)))
        "advisory until slopp's own store has measured where it fires")
    (is (some #{#'edit.facet/facet-key-ownership} gates/per-form-write-gates))))

(deftest reveal-is-a-port
  (let [chk (fn [body] (edit.facet/facet-reveal (st body) 'shop.order 'h))]
    (is (re-find #":reveals" (str (chk "(defn h [x] (facet/reveal x))"))) "an unmarked reveal is refused")
    (is (nil? (chk "(defn ^{:reveals \"the card processor needs the number\"} h [x] (facet/reveal x))")))
    (is (nil? (chk "(def h '(facet/reveal x))")) "a quoted call is data")
    (is (nil? (chk "(defn h [x] (facet/parse x))")))
    (is (some #{#'edit.facet/facet-reveal} gates/per-form-write-gates))
    (is (= :production (:rule/applies-to (meta #'edit.facet/facet-reveal))))))

(deftest adapters-emit-raw
  (let [base (st (order ""))
        chk  (fn [s body] (edit.facet/facet-adapter-raw (st s 'shop.db body) 'shop.db 'h))
        db   "(defn ^{:adapter \"db — orders table; tests pass a fake\"} h [conn] (vec conn))"]
    (is (re-find #"facet/raw" (str (chk base db))) "a db adapter without raw gets the advisory")
    (is (nil? (chk base "(defn ^{:adapter \"db — orders table; tests pass a fake\"} h [conn] (facet/raw (vec conn) :db/orders))")))
    (is (nil? (chk base "(defn ^{:adapter \"process — git\"} h [conn] (vec conn))")))
    (is (nil? (chk (store/empty-store) db)) "no production facet, no advisory")
    (is (= :advisory (:rule/severity (meta #'edit.facet/facet-adapter-raw))))
    (is (= :production (:rule/applies-to (meta #'edit.facet/facet-adapter-raw))))
    (is (some #{#'edit.facet/facet-adapter-raw} gates/per-form-write-gates))))

(deftest the-port-hands-parse-a-var-it-holds
  (is (nil? (edit.facet/facet-by-var (st (str (order "") "(defn h [v x] (facet/parse v x))")) 'shop.order 'h))
      "a local holding a var is checked by parse itself at runtime: it refuses anything but a facet var")
  (is (nil? (edit.facet/facet-key-ownership
             (st (st (order "")) 'shop.web "(defn ^{:rest/request #'shop.order/Order} h [r] (:shop/total (:body r)))")
             'shop.web 'h))
      "a route that declares its facet by var had its body parsed by the port"))

(deftest weakenings-name-every-way-a-facet-loosens
  (let [old '{:facet/fields {:x/a :int :x/b {:schema :string :secret true} :x/c :int}
              :facet/checks {:ok {:doc "d" :fn (var ok?)} :pure {:doc "p" :fn (var p?)}}
              :facet/views  {:public [:x/a]}}]
    (is (= [] (edit.facet/weakenings old old)) "the same declaration loosens nothing")
    (is (= ["check :ok was removed"
            "check :pure now declares :sources, so parse no longer runs it"
            "field :x/b is no longer secret"
            "field :x/c became optional"
            "view :public now also shows [:x/b]"]
           (edit.facet/weakenings old '{:facet/fields {:x/a :int :x/b :string :x/c {:schema :int :optional true}}
                                        :facet/checks {:pure {:doc "p" :fn (var p?) :sources [:db/x]}}
                                        :facet/views  {:public [:x/a :x/b]}})))
    (is (= ["field :x/c was dropped, so the key has no owner"]
           (edit.facet/weakenings old (update old :facet/fields dissoc :x/c))))
    (is (= [] (edit.facet/weakenings old (assoc-in old [:facet/fields :x/d] :int)))
        "a new field is a stronger contract, not a weaker one")))
