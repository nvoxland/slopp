(ns slopp.rest.facet-test
  "A route typed by a facet, end to end through the real encoding: the port parses what arrives and the handler sees a parsed value; the port projects what leaves to the route's view, with secrets redacted; a failed check is a 400 naming the check; a value the handler broke fails at the exit."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rest :as slopp.rest]
            [slopp.facet :as facet]))

(defn qty-positive?
  "A quantity is at least one; anything but `true` is failure data."
  [{:shop/keys [qty]}]
  (or (pos? qty) {:qty qty}))

(def ^{:facet "an item a client sends to the shop, in the rest facet tests"} Item
  {:facet/fields {:shop/sku :string :shop/qty :int :shop/card {:schema :string :secret true}}
   :facet/checks {:qty-positive {:doc "a quantity is at least one" :fn #'qty-positive?}}
   :facet/views  {:public [:shop/sku :shop/qty :shop/card]}})

(def ctx
  (slopp.rest/validating
   {:http/routes
    [{:handler (fn [req] {:status 200 :body (:body req)})
      :method :post :path "/api/items" :auth :public
      :rest/request #'Item :rest/response #'Item :facet/view :public}
     {:handler (fn [req] {:status 200 :body {:parsed (facet/parsed? #'Item (:body req))}})
      :method :post :path "/api/seen" :auth :public
      :rest/request #'Item :rest/response [:map [:parsed :boolean]]}
     {:handler (fn [req] {:status 200 :body (assoc (:body req) :shop/qty 0)})
      :method :post :path "/api/broken" :auth :public
      :rest/request #'Item :rest/response #'Item :facet/view :public}]}))

(deftest a-facet-typed-route-parses-in-and-projects-out
  (let [good {:shop/sku "a" :shop/qty 2 :shop/card "4242"}
        call (fn [path body] (slopp.rest/call ctx {:method :post :path path :body body}))]
    (testing "the handler receives the value parsed by the port"
      (is (true? (:parsed (:body (call "/api/seen" good))))))
    (testing "what leaves is the route's view, and a secret leaves redacted"
      (let [r (call "/api/items" good)]
        (is (= 200 (:status r)) (pr-str r))
        (is (= 2 (get-in r [:body :shop/qty])) (pr-str r))
        (is (not (re-find #"4242" (pr-str r))))
        (is (re-find #"#secret shop/card" (pr-str r)))))
    (testing "a failed check is the sender's 400, and it says which check"
      (let [r (call "/api/items" (assoc good :shop/qty 0))]
        (is (= 400 (:status r)))
        (is (re-find #"a quantity is at least one" (pr-str (:body r))))))
    (testing "an undeclared key is refused at the port"
      (is (= 400 (:status (call "/api/items" (assoc good :shop/extra 1))))))
    (testing "a value the handler broke fails at the exit, as the server's fault"
      (is (= 500 (:status (call "/api/broken" good)))))))

(def ^{:facet "a lookup sent as a query string, in the rest facet tests"} Lookup
  {:facet/fields {:lookup/qty :int}})

(deftest a-facet-route-decodes-a-query-string-to-its-field-types
  (let [c (slopp.rest/validating
           {:http/routes
            [{:handler (fn [req] {:status 200 :body {:next (inc (:lookup/qty (:body req)))}})
              :method :get :path "/api/lookup" :auth :public
              :rest/request #'Lookup :rest/response [:map [:next :int]]}]})]
    (is (= 3 (:next (:body (slopp.rest/call c {:method :get :path "/api/lookup?lookup/qty=2"})))))))
