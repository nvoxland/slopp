(ns slopp.edit.rest-test
  "The rest capability's write gate: an endpoint that publishes a typed API must
  type out its contract.

  These moved here from `slopp.rules.http-test` with the gate itself on
  2026-08-15. The test that matters most is the one that could not exist
  before — an http-only app is no longer asked for a contract, which is the
  loosening the move was FOR and the only part of it a user would notice."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops]
            [slopp.ops.external :as external]
            [slopp.store :as store]))

(deftest ^:external rest-endpoint-schema-is-inert-until-the-app-publishes-an-API
  ;; THE point of moving this gate out of http. Serving a document is http's
  ;; business; publishing a typed API is rest's. An app that renders HTML has
  ;; no client generated from a schema and no consumer to keep a promise to, so
  ;; demanding one was one app type's vocabulary applied to every project — the
  ;; R6 mistake, in the gate that most looked like a general rule.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'doc.pages "(ns doc.pages)\n\n(defn seed \"S.\" [x] x)\n")
      (ops/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "serve HTML, publish no typed API")
      (testing "http alone: a page with no :rest/response LANDS"
        (let [r (ops/add-form! sess 'doc.pages
                               (str "(defn ^{:http/method :get :http/path \"/about\""
                                    " :http/auth :public} about \"A page.\" [req] req)")
                               :prompt "an HTML page, not an endpoint")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'doc.pages 'about))
              "an app serving documents is not asked for a JSON contract")))
      (testing "and enabling rest is what starts asking"
        (ops/config-file! sess "capabilities" :key "rest.enabled" :value "true"
                          :prompt "now publish a typed API")
        (let [r (ops/add-form! sess 'doc.pages
                               (str "(defn ^{:http/method :get :http/path \"/api/x\""
                                    " :http/auth :public} x \"X.\" [req] req)")
                               :prompt "no response contract")]
          (is (re-find #":rest/response" (str (:error r))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external rest-endpoint-schema-requires-a-response-contract
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopc.api "(ns shopc.api)\n\n(defn seed \"S.\" [x] x)\n")
      (testing "before opt-in, an endpoint without :rest/response lands (grandfathered)"
        (let [r (ops/add-form! sess 'shopc.api
                               "(defn ^{:http/method :get :http/path \"/pre\" :http/auth :public} pre \"P.\" [req] req)"
                               :prompt "pre-optin endpoint")]
          (is (nil? (:error r)) (pr-str r))))
      (ops/config-file! sess "capabilities" :key "rest.enabled" :value "true"
                        :prompt "opt into a typed API — implies http")
      (testing "under opt-in, an endpoint with auth but NO :rest/response is refused, never lands"
        (let [r (ops/add-form! sess 'shopc.api
                               "(defn ^{:http/method :get :http/path \"/list\" :http/auth :public} list-it \"L.\" [req] req)"
                               :prompt "no response contract")]
          (is (re-find #":rest/response" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shopc.api 'list-it)) "never lands")))
      (testing "declaring :rest/response (here inline) lets it land"
        (let [r (ops/add-form! sess 'shopc.api
                               (str "(defn ^{:http/method :get :http/path \"/ok\" :http/auth :public"
                                    " :rest/response [:map [:n :int]]} ok \"O.\" [req] req)")
                               :prompt "with a response contract")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'shopc.api 'ok)))))
      (finally (ops/close! sess)))))

(deftest ^:external rest-endpoint-schema-requires-request-on-body-methods
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopr.api "(ns shopr.api)\n\n(defn seed \"S.\" [x] x)\n")
      (ops/config-file! sess "capabilities" :key "rest.enabled" :value "true"
                        :prompt "opt into a typed API — implies http")
      (testing "a POST endpoint with :rest/response but NO :rest/request is refused"
        (let [r (ops/add-form! sess 'shopr.api
                               (str "(defn ^{:http/method :post :http/path \"/orders\" :http/auth :public"
                                    " :rest/response [:map [:id :int]]} create \"C.\" [req] req)")
                               :prompt "no request contract")]
          (is (re-find #":rest/request" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shopr.api 'create)))))
      (testing "a GET endpoint needs only :rest/response (no request body)"
        ;; and this is the asymmetry the RUNTIME boundary honours too: a GET's
        ;; :rest/request, when one is declared anyway, describes PARAMS rather
        ;; than a body, so the dispatcher does not judge it as one
        (let [r (ops/add-form! sess 'shopr.api
                               (str "(defn ^{:http/method :get :http/path \"/orders\" :http/auth :public"
                                    " :rest/response [:map]} listing \"L.\" [req] req)")
                               :prompt "get needs only response")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "declaring both contracts lets the POST land"
        (let [r (ops/add-form! sess 'shopr.api
                               (str "(defn ^{:http/method :post :http/path \"/orders2\" :http/auth :public"
                                    " :rest/request [:map [:item :string]] :rest/response [:map [:id :int]]}"
                                    " create2 \"C.\" [req] req)")
                               :prompt "both contracts")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'shopr.api 'create2)))))
      (finally (ops/close! sess)))))
