(ns slopp.rules.rest-test
  "The typed surface as a project declares it, read off a store value.

  Two properties are the reason this report exists rather than pointing a
  reader at the schemas directly: it is inert until the capability is on, so a
  project is never DESCRIBED as publishing an API it does not; and it reports
  NAMES rather than schemas, because this is the one payload that grows with
  the application instead of with the question."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rules.rest :as rules.rest]
            [slopp.store :as store]))

(deftest the-rest-section-reports-the-TYPED-surface
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:web/method :post :web/path \"/api/orders\" :web/auth :public\n"
                 "        :web/request [:map [:sku :string] [:qty :int]]\n"
                 "        :web/response [:map [:id :int]]}\n"
                 "  create! \"Place an order.\" [req] req)\n\n"
                 "(defn ^{:web/method :get :web/path \"/api/health\" :web/auth :public\n"
                 "        :web/response [:map [:ok :boolean]]}\n"
                 "  health \"Health.\" [req] req)\n\n"
                 "(defn ^{:web/method :get :web/path \"/\" :web/auth :public\n"
                 "        :web/client false}\n"
                 "  page \"A document, not an API.\" [req] req)\n")
        off (store/ingest (store/empty-store) 'shop.api src)
        on  (assoc-in off [:config "capabilities" :values "rest.enabled"] "true")]

    (testing "empty until rest.enabled"
      ;; the reading side of the same inertness the gates have: a project that
      ;; publishes no typed API must not be DESCRIBED as having one
      (is (empty? (rules.rest/contracts-report off))))

    (let [rows (rules.rest/contracts-report on)
          by   (into {} (map (juxt :path identity)) rows)]
      (testing "every declared contract is a row, and says what kind it is"
        (is (= 3 (count rows)) (pr-str rows))
        (is (every? #(= :contract (:kind %)) rows)
            "so a renderer that knows nothing about this capability can still
             draw it beside a command or an endpoint"))

      (testing "the shape is NAMES, not schemas"
        ;; a malli schema pretty-prints to several lines, and this surface is
        ;; already the one whose payload grows with the APP rather than with
        ;; the question — query_rules is what that looks like when it goes
        ;; wrong (17 of 43 rules over the wire). The names answer what the
        ;; endpoint is FOR; query_slice on the handler answers it exactly.
        (is (= [:sku :qty] (:request (by "/api/orders"))))
        (is (= [:id] (:response (by "/api/orders"))))
        (is (= 'shop.api/create! (:handler (by "/api/orders")))))

      (testing "a GET declares no request, and that absence is reported as one"
        (is (nil? (:request (by "/api/health"))))
        (is (= [:ok] (:response (by "/api/health")))))

      (testing "an endpoint OUT of the published API says so"
        ;; :web/client false is how a document opts out of the typed surface —
        ;; a generated fetch wrapper over an HTML page is nonsense — and a
        ;; reader asking what consumers can call needs that visible rather
        ;; than inferred from a missing schema
        (is (false? (:published (by "/"))))
        (is (true? (:published (by "/api/orders"))))))))
