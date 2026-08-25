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
            [slopp.store :as store] [slopp.edit.rest :as edit.rest] [clojure.string :as str] [slopp.edit.http :as edit.http]))

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
                               (str "(def ^{:http/method :get :http/path \"/about\""
                                    " :http/auth :public} about \"A page.\""
                                    " [:main [:h1 \"About\"]])")
                               :prompt "an HTML page, not an endpoint")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'doc.pages 'about))
              "an app serving documents is not asked for a JSON contract")))
      (testing "and enabling rest is what starts asking"
        (ops/config-file! sess "capabilities" :key "rest.enabled" :value "true"
                          :prompt "now publish a typed API")
        (let [r (ops/add-form! sess 'doc.pages
                               (str "(defn ^{:http/method :get :rest/path \"/api/x\""
                                    " :http/auth :public} x \"X.\" [req] req)")
                               :prompt "no response contract")]
          (is (re-find #":rest/response" (str (:error r))) (pr-str r))))

      (testing "and the PAGE that landed before rest was on is now under-declared"
        ;; the cost of the partition becoming total, stated rather than
        ;; discovered: `/api/x` as :http/path was unremarkable in an http-only
        ;; store, and enabling rest makes that space the API's. The refusal
        ;; names the kind to declare rather than the contract to add, which is
        ;; the difference between a fixable message and a misleading one
        (let [r (ops/add-form! sess 'doc.pages
                               (str "(defn ^{:http/method :get :http/path \"/api/page\""
                                    " :http/auth :public} p \"P.\" [req] req)")
                               :prompt "content inside the api prefix")]
          (is (re-find #":rest/path" (str (:error r))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external rest-endpoint-schema-requires-a-response-contract
  ;; The gate asks `:rest/path` — a TYPED api — rather than every route. It
  ;; used to ask `:http/path`, which was the only path marker there was, so a
  ;; stylesheet was asked for a JSON contract too. The fixtures declare the
  ;; kind they are, and sit under the API prefix the partition requires.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shopc.api "(ns shopc.api)\n\n(defn seed \"S.\" [x] x)\n")
      (testing "before opt-in, an endpoint without :rest/response lands (grandfathered)"
        (let [r (ops/add-form! sess 'shopc.api
                               "(defn ^{:http/method :get :rest/path \"/api/pre\" :http/auth :public} pre \"P.\" [req] req)"
                               :prompt "pre-optin endpoint")]
          (is (nil? (:error r)) (pr-str r))))
      (ops/config-file! sess "capabilities" :key "rest.enabled" :value "true"
                        :prompt "opt into a typed API — implies http")
      (testing "under opt-in, an api with auth but NO :rest/response is refused, never lands"
        (let [r (ops/add-form! sess 'shopc.api
                               "(defn ^{:http/method :get :rest/path \"/api/list\" :http/auth :public} list-it \"L.\" [req] req)"
                               :prompt "no response contract")]
          (is (re-find #":rest/response" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shopc.api 'list-it)) "never lands")))
      (testing "and CONTENT is asked for none of it, which is the point of the split"
        ;; the case that used to force `:rest/response :string` onto a page and
        ;; then `:rest/client false` to undo the wrapper
        (let [r (ops/add-form! sess 'shopc.api
                               (str "(def ^{:http/method :get :http/path \"/css/app.css\""
                                    " :http/auth :public"
                                    " :http/media-type \"text/css\"}"
                                    " sheet \"S.\" \"main{margin:0}\")")
                               :prompt "a stylesheet is not an api")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'shopc.api 'sheet)))))
      (testing "declaring :rest/response (here inline) lets it land"
        (let [r (ops/add-form! sess 'shopc.api
                               (str "(defn ^{:http/method :get :rest/path \"/api/ok\" :http/auth :public"
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
      (testing "a POST api with :rest/response but NO :rest/request is refused"
        (let [r (ops/add-form! sess 'shopr.api
                               (str "(defn ^{:http/method :post :rest/path \"/api/orders\" :http/auth :public"
                                    " :rest/response [:map [:id :int]]} create \"C.\" [req] req)")
                               :prompt "no request contract")]
          (is (re-find #":rest/request" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shopr.api 'create)))))
      (testing "a GET api needs only :rest/response (no request body)"
        ;; and this is the asymmetry the RUNTIME boundary honours too: a GET's
        ;; :rest/request, when one is declared anyway, describes PARAMS rather
        ;; than a body, so the dispatcher does not judge it as one
        (let [r (ops/add-form! sess 'shopr.api
                               (str "(defn ^{:http/method :get :rest/path \"/api/orders\" :http/auth :public"
                                    " :rest/response [:map]} listing \"L.\" [req] req)")
                               :prompt "get needs only response")]
          (is (nil? (:error r)) (pr-str r))))
      (testing "declaring both contracts lets the POST land"
        (let [r (ops/add-form! sess 'shopr.api
                               (str "(defn ^{:http/method :post :rest/path \"/api/orders2\" :http/auth :public"
                                    " :rest/request [:map [:item :string]] :rest/response [:map [:id :int]]}"
                                    " create2 \"C.\" [req] req)")
                               :prompt "both contracts")]
          (is (nil? (:error r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'shopr.api 'create2)))))
      (finally (ops/close! sess)))))

(deftest the-API-and-CONTENT-partition-has-no-allowed-intersection
  ;; Nathan, 2026-08-23: "there should be no allowed intersection between rest
  ;; APIs and general http content." The partition is what makes `/api/*` mean
  ;; something a proxy, a CSP or a reader can rely on WITHOUT consulting
  ;; metadata — the consuming store's hub proxies `/p/:slug/api/*path` today on
  ;; exactly that assumption, which nothing was enforcing.
  ;;
  ;; Three refusals, all declaration-grounded: the author says which kind it
  ;; is, so there is no coincidence test anywhere in this (D-rule-grounding).
  (let [on (-> (store/empty-store)
               (store/record-config-put "capabilities" :manifest "http.enabled" "true") first
               (store/record-config-put "capabilities" :manifest "rest.enabled" "true") first)
        with (fn [st src] (store/ingest st 'demo.api (str "(ns demo.api)\n\n" src)))
        check (fn [st nm] (edit.rest/rest-path-partition st 'demo.api nm))]

    (testing "a REST api under the prefix is fine"
      (let [st (with on (str "(defn ^{:rest/path \"/api/orders\" :http/method :get"
                             " :http/auth :public :rest/response [:map]}\n"
                             "  orders \"O.\" [_] {:status 200})\n"))]
        (is (nil? (check st 'orders)))))

    (testing "and a REST api is still DEFAULT-DENY, like any other route"
      ;; the hole a second path marker opens if the gates keep reading only the
      ;; first: default-deny would stop applying to exactly the endpoints most
      ;; likely to carry data. `edit.http/route-path` is the one accessor every
      ;; reachability gate asks, so a new kind cannot slip past one of them
      (let [st (with on (str "(defn ^{:rest/path \"/api/secret\" :http/method :get"
                             " :rest/response [:map]}\n"
                             "  secret \"S.\" [_] {:status 200})\n"))
            m  (edit.http/http-auth-refusal st 'demo.api 'secret)]
        (is (some? m) "a :rest/path endpoint with no :http/auth must refuse")
        (is (str/includes? (str m) ":http/auth") (str m))))

    (testing "and CONTENT outside it is fine, asked for no contract"
      ;; the whole point: content is not asked for :rest/response. That demand
      ;; is what made a stylesheet declare :rest/response :string and then
      ;; :rest/client false to undo it
      (let [st (with on (str "(defn ^{:http/path \"/css/app.css\" :http/method :get"
                             " :http/auth :public}\n"
                             "  sheet \"S.\" [_] {:status 200})\n"))]
        (is (nil? (check st 'sheet)))
        (is (nil? (edit.rest/rest-endpoint-schema st 'demo.api 'sheet)))))

    (testing "BOTH markers on one form REFUSES — one endpoint is one kind"
      (let [st (with on (str "(defn ^{:rest/path \"/api/x\" :http/path \"/api/x\""
                             " :http/method :get :http/auth :public"
                             " :rest/response [:map]}\n"
                             "  both \"B.\" [_] {:status 200})\n"))
            m  (check st 'both)]
        (is (some? m))
        (is (str/includes? (str m) ":rest/path") (str m))
        (is (str/includes? (str m) ":http/path") (str m))))

    (testing "a REST api OUTSIDE the prefix refuses, naming the prefix and its key"
      ;; a refusal that does not name the setting cannot be acted on
      (let [st (with on (str "(defn ^{:rest/path \"/orders\" :http/method :get"
                             " :http/auth :public :rest/response [:map]}\n"
                             "  loose \"L.\" [_] {:status 200})\n"))
            m  (check st 'loose)]
        (is (some? m))
        (is (str/includes? (str m) "/api") (str m))
        (is (str/includes? (str m) "rest.prefix") (str m))))

    (testing "and CONTENT inside the prefix refuses — /api is the API's"
      ;; the fix it names is to declare the kind it actually is
      (let [st (with on (str "(defn ^{:http/path \"/api/page.html\" :http/method :get"
                             " :http/auth :public}\n"
                             "  page \"P.\" [_] {:status 200})\n"))
            m  (check st 'page)]
        (is (some? m))
        (is (str/includes? (str m) ":rest/path") (str m))))

    (testing "the prefix is a SETTING, so a store may serve its API at /v1"
      ;; one answer per store, or the partition is not total
      (let [v1 (-> on (store/record-config-put "capabilities" :manifest
                                               "rest.prefix" "/v1") first)
            ok (with v1 (str "(defn ^{:rest/path \"/v1/orders\" :http/method :get"
                             " :http/auth :public :rest/response [:map]}\n"
                             "  orders \"O.\" [_] {:status 200})\n"))
            no (with v1 (str "(defn ^{:rest/path \"/api/orders\" :http/method :get"
                             " :http/auth :public :rest/response [:map]}\n"
                             "  orders \"O.\" [_] {:status 200})\n"))]
        (is (nil? (edit.rest/rest-path-partition ok 'demo.api 'orders)))
        (is (some? (edit.rest/rest-path-partition no 'demo.api 'orders)))))))
