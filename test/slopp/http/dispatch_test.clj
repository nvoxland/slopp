(ns slopp.http.dispatch-test
  "The request pipeline with no socket under it — `handle!` takes a request map
  and returns a response map, so everything between those two is checkable
  in-image: routing, auth policy, schema validation, effect interpretation, and
  what happens when a handler blows up.

  That is the whole reason `dispatch` is separate from `web.server.*`. The
  adapters own bytes and ports and need the external tier; the decisions live
  here and cost milliseconds.

  Where a test needs a handler to FAIL, it fails the way real third-party code
  does — including one that throws a bare exception leaking a filesystem path,
  because masking that is the thing being asserted."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.http.dispatch :as dispatch] [slopp.rest.contract :as rest.contract] slopp.http))

(deftest dispatch-runs-the-whole-pipeline-portlessly
  (let [performed (atom [])
        routes [{:handler (fn [req] {:status 200 :body {:got (:http/reads req)}})
                 :method :get :path "/u/:id" :auth :public
                 :http/reads {:user [:user/by-id [:path-params :id]]}}
                {:handler (fn [req] {:status 201 :body {:ok true}
                                     :http/effects [[:user/insert (:body req)]
                                                   [:email/welcome "hi"]]})
                 :method :post :path "/u" :auth [:group "admin"]
                 :http/effects [:user/insert :email/welcome]}
                {:handler (fn [_] {:status 201 :http/effects [[:rogue/kind 1]]})
                 :method :post :path "/rogue" :auth :public
                 :http/effects [:rogue/kind]}]
        ctx {:http/routes routes
             :http/read-performers {:user/by-id (fn [_ id] {:user/id id})}
             :http/effect-performers {:user/insert (fn [_ row] (swap! performed conj [:insert row]))
                                     :email/welcome (fn [_ to] (swap! performed conj [:mail to]))}}]
    (testing "no route → 404 data, never a throw"
      (is (= 404 (:status (dispatch/handle! ctx {:request-method :get :uri "/nope"})))))
    (testing "policy runs BEFORE the handler: :authenticated-shaped policies refuse first"
      (is (= 401 (:status (dispatch/handle! ctx {:request-method :post :uri "/u"}))))
      (is (= 403 (:status (dispatch/handle! ctx {:request-method :post :uri "/u"
                                                :http/identity {:http/sub "eve" :http/groups #{"dev"}}})))))
    (testing "declared reads are fetched before the handler; the handler needs no stub"
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/u/42"})]
        (is (= 200 (:status r)))
        (is (= {:user {:user/id "42"}} (:got (:body r))))))
    (testing "the perform-ctx reaches the handler as :http/deps"
      (let [ctx2 (assoc ctx
                        :http/perform-ctx {:who :deps-probe}
                        :http/routes [{:handler (fn [req] {:status 200
                                                          :body {:deps (:http/deps req)}})
                                      :method :get :path "/deps" :auth :public}])]
        (is (= {:who :deps-probe}
               (:deps (:body (dispatch/handle! ctx2 {:request-method :get :uri "/deps"})))))))
    (testing "returned effects run in order through the performers"
      (reset! performed [])
      (let [r (dispatch/handle! ctx {:request-method :post :uri "/u"
                                    :body {:user/name "ada"}
                                    :http/identity {:http/sub "root" :http/groups #{"admin"}}})]
        (is (= 201 (:status r)))
        (is (= [[:insert {:user/name "ada"}] [:mail "hi"]] @performed))))
    (testing "an effect kind with no performer refuses at runtime, effects-so-far intact"
      (reset! performed [])
      (let [r (dispatch/handle! ctx {:request-method :post :uri "/rogue"})]
        (is (= 500 (:status r)))
        (is (empty? @performed))))))

(deftest dispatch-resolves-identity-from-auth-config
  (let [ctx {:http/routes [{:handler (fn [req] {:status 200
                                               :body {:sub (:http/sub (:http/identity req))}})
                           :method :get :path "/who" :auth :authenticated}]
             :http/auth-config {:auth/providers [:bearer]
                               :auth/bearer {"ci" {:secret "tok-9" :groups ["ci"]}}}}]
    (testing "a bearer header authenticates through the configured providers"
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/who"
                                     :headers {"authorization" "Bearer tok-9"}})]
        (is (= 200 (:status r)) (pr-str r))
        (is (= "ci" (:sub (:body r))))))
    (testing "anonymous stays 401"
      (is (= 401 (:status (dispatch/handle! ctx {:request-method :get :uri "/who"})))))
    (testing "a pre-resolved :http/identity is respected over resolution"
      (is (= "pre" (:sub (:body (dispatch/handle! ctx {:request-method :get :uri "/who"
                                                       :http/identity {:http/sub "pre"}}))))))))

(deftest empty-composite-policies-fail-closed
  ;; review W1: `(every? pred '())` is true, so [:all] (an empty conjunction)
  ;; authorized EVERYONE incl. anonymous — the one degenerate policy that
  ;; failed OPEN while [:any]/[:group]/nil all denied. A composite with no
  ;; sub-policies must deny.
  (testing "[:all] with no sub-policies denies (anonymous and authenticated)"
    (is (not (dispatch/authorized? [:all] nil)))
    (is (not (dispatch/authorized? [:all] {:http/sub "x" :http/groups #{}}))))
  (testing "[:any] with no sub-policies denies"
    (is (not (dispatch/authorized? [:any] nil))))
  (testing "non-empty composites still work"
    (is (dispatch/authorized? [:all :authenticated] {:http/sub "x"}))
    (is (not (dispatch/authorized? [:all :authenticated [:group "admin"]] {:http/sub "x"})))
    (is (dispatch/authorized? [:any [:group "a"] [:group "b"]] {:http/groups #{"b"}}))))

(deftest handler-cannot-emit-an-undeclared-effect-kind
  ;; review W4: run-effects! validated only against the app-wide performer
  ;; set, never the ROUTE's declared :http/effects — so a handler could emit
  ;; any kind a performer provides, incl. a write from a route that declared
  ;; none (or a :get that http-unsafe-get "proved" safe). The static gate
  ;; sees only what it can read in the handler body; the runtime must bound
  ;; effects to the route's declaration.
  (let [performed (atom [])
        ctx {:http/routes [{:handler (fn [_] {:status 200
                                             :http/effects [[:danger/write "pwned"]]})
                           :method :get :path "/x" :auth :public
                           :http/effects nil}]   ; declares NO effects
             :http/effect-performers {:danger/write (fn [_ v] (swap! performed conj v))}}]
    (testing "an effect kind the route did not declare is refused, nothing performed"
      (reset! performed [])
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/x"})]
        (is (= 500 (:status r)) (pr-str r))
        (is (empty? @performed))))))

(deftest ^{:bare-throw-ok "the bare throw IS the subject. This asserts that a handler
              which leaks a filesystem path through a non-ex-info exception has
              that detail MASKED before it reaches a client — so the fixture has
              to throw exactly the kind of exception the rule elsewhere
              forbids. Replacing it with ex-info would test the opposite case."}
  error-responses-do-not-leak-internal-detail
  ;; review W3: the catch returned raw ex-message + the whole ex-data (minus
  ;; :http/status). A 500 disclosed lib exception messages (paths); a handler
  ;; ex-info disclosed whatever it carried. Unexpected errors get a generic
  ;; body; deliberate boundary errors surface their message and ONLY a
  ;; :http/public allowlist.
  (let [ctx {:http/routes
             [{:handler (fn [_] (throw (java.io.FileNotFoundException. "/etc/shadow (nope)")))
               :method :get :path "/boom" :auth :public}
              {:handler (fn [_] (throw (ex-info "bad request"
                                               {:http/status 400
                                                :db/password "hunter2"
                                                :http/public {:field "email"}})))
               :method :get :path "/bad" :auth :public}]}]
    (testing "an UNEXPECTED error is a generic 500 — no message, no data leak"
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/boom"})]
        (is (= 500 (:status r)))
        (is (not (re-find #"shadow|etc" (str (:body r)))) (pr-str r))))
    (testing "a DELIBERATE boundary error surfaces its message + only :http/public"
      (let [r (dispatch/handle! ctx {:request-method :get :uri "/bad"})]
        (is (= 400 (:status r)))
        (is (= "bad request" (:error (:body r))))
        (is (= {:field "email"} (:data (:body r))))
        (is (not (re-find #"hunter2|password" (str (:body r)))) (pr-str r))))))

(deftest bounded-body-caps-the-request-read
  ;; review W8: both adapters slurp the whole body unbounded (JDK → heap/OOM
  ;; DoS; http-kit falls back to its own default), and the configured
  ;; http.max-body-bytes was read by nothing. The shared bounded reader caps
  ;; it and signals overflow so the adapter can answer 413.
  (let [in (fn [s] (java.io.ByteArrayInputStream. (.getBytes (str s) "UTF-8")))]
    (testing "a body within the cap reads through"
      (is (= "hello" (:body (dispatch/bounded-body-string (in "hello") 1024)))))
    (testing "a body over the cap signals :too-large, does not return content"
      (let [r (dispatch/bounded-body-string (in (apply str (repeat 100 "x"))) 10)]
        (is (:too-large r))
        (is (nil? (:body r)))))
    (testing "nil stream is an empty body, never a throw"
      (is (nil? (:body (dispatch/bounded-body-string nil 1024)))))
    (testing "exactly-at-cap is allowed"
      (is (= "12345" (:body (dispatch/bounded-body-string (in "12345") 5)))))))

(deftest a-contract-on-the-CONTEXT-is-what-makes-the-boundary-real
  ;; The contract was declared, gate-enforced at write time, published to
  ;; consumers and used to generate typed clients — and honoured by nothing.
  ;; An untrusted body reached the handler unchecked. This is where that stops.
  ;;
  ;; The validators arrive as FUNCTIONS ON THE CONTEXT, exactly as
  ;; :http/read-performers does, and that is not a style choice: it is what
  ;; keeps malli out of the http framework. An app serving HTML must not start
  ;; carrying a validation library because a DIFFERENT capability needs one, so
  ;; `slopp.rest` requires malli and `slopp.http.*` still does not. The module
  ;; edge this test needs is declared TEST-ONLY for that reason.
  (let [ran (atom 0)
        row {:handler (fn [req] (swap! ran inc) {:status 200 :body {:got (:body req)}})
             :method :post :path "/api/x" :auth :public
             :rest/request [:map [:sku :string]]
             :rest/response [:map [:got :map]]}
        base {:http/routes [row]}
        rest-ctx (assoc base
                        :rest/decode-request rest.contract/decode-request
                        :rest/check-response rest.contract/check-response)
        post (fn [body] {:request-method :post :uri "/api/x" :body body})]

    (testing "with no validators on the context nothing changes"
      ;; every http-only app is this case, and it must cost exactly nothing
      (reset! ran 0)
      (let [r (dispatch/handle! base (post {:sku 42}))]
        (is (= 200 (:status r)) (pr-str r))
        (is (= 1 @ran) "the handler ran, as it always has")))

    (testing "a body that violates the declared contract is REFUSED"
      (reset! ran 0)
      (let [r (dispatch/handle! rest-ctx (post {:sku 42}))]
        (is (= 400 (:status r)) (pr-str r))
        (is (zero? @ran)
            "and the handler never ran — work on unvalidated input is the thing
             a boundary exists to prevent, so the refusal comes BEFORE it")))

    (testing "a body that matches reaches the handler DECODED"
      (reset! ran 0)
      (let [r (dispatch/handle! rest-ctx (post {:sku "abc"}))]
        (is (= 200 (:status r)) (pr-str r))
        (is (= 1 @ran))))

    (testing "a handler that breaks its OWN response contract is a 500"
      ;; the client was generated from that schema, so a violating response
      ;; breaks the consumer anyway — failing at the source beats failing
      ;; obscurely at the far end
      (let [bad (assoc row :handler (fn [_] {:status 200 :body {:got "not-a-map"}}))
            r   (dispatch/handle! (assoc rest-ctx :http/routes [bad]) (post {:sku "abc"}))]
        (is (= 500 (:status r)) (pr-str r))
        (is (not (re-find #"not-a-map" (pr-str (:body r))))
            "and the explain does NOT reach the client — same rule the
             dispatcher already follows for an unexpected exception")))

    (testing "a GET's :rest/request describes its PARAMS, and they are judged too"
      ;; slopp's own API taught the first half by 400ing on four endpoints:
      ;; `/api/ns/:ns` declares [:map [:ns :string]] for its PATH SEGMENT, so
      ;; judging that schema against a nil body refuses every correct request.
      ;;
      ;; The stopgap was to judge only body methods, which left path segments
      ;; and query strings — untrusted input, in every link — checked by
      ;; nobody. `:rest/request` means what the caller SENDS, so the params are
      ;; the contract and are judged with everything else.
      (reset! ran 0)
      ;; a REAL path segment: `router/match` derives :path-params from the
      ;; pattern and overwrites anything a fixture assoc'd onto the row, so
      ;; handing the dispatcher params it did not extract itself would be
      ;; testing a shape the router never produces.
      (let [g (assoc row :method :get :path "/api/:ns"
                     :rest/request [:map [:ns :string] [:depth {:optional true} :int]]
                     :rest/response [:map [:got :map]]
                     :handler (fn [req] (swap! ran inc)
                                {:status 200 :body {:got {:d (:depth (:query-params req))}}}))
            ctx' (assoc rest-ctx :http/routes [g])]
        (testing "a declared param that arrived is DECODED for the handler"
          ;; the payoff: a query string can only carry text, so [:depth :int]
          ;; is honoured by decoding rather than repaired — and the handler
          ;; parses nothing
          (let [r (dispatch/handle! ctx' {:request-method :get :uri "/api/app.core"
                                          :query-string "depth=2"})]
            (is (= 200 (:status r)) (pr-str r))
            (is (= 2 (:d (:got (:body r))))
                "a query parameter declared :int reaches the handler an int")))

        (testing "and one that violates its type is refused before the handler"
          (reset! ran 0)
          (let [r (dispatch/handle! ctx' {:request-method :get :uri "/api/app.core"
                                          :query-string "depth=banana"})]
            (is (= 400 (:status r)) (pr-str r))
            (is (zero? @ran)
                "?depth=banana against a declared :int used to reach the handler
                 as a string — that is the gap this closes")))))

    (testing "an ERROR response is not judged against the success contract"
      ;; :rest/response describes the 200 body. A 404's {:error …} does not
      ;; match it and must not be turned into a 500 for failing to.
      (let [nf (assoc row :handler (fn [_] {:status 404 :body {:error "no such sku"}}))
            r  (dispatch/handle! (assoc rest-ctx :http/routes [nf]) (post {:sku "abc"}))]
        (is (= 404 (:status r)) (pr-str r))
        (is (= {:error "no such sku"} (:body r)))))))

(deftest a-RAW-response-is-judged-as-the-document-it-encodes
  ;; The defect a consuming store measured on slopp's own /api/contracts. That
  ;; endpoint serializes its own body (`:http/raw`, `pr-str`) and declared
  ;; `:rest/response :string` — which is TRUE of the bytes and says nothing
  ;; about the document. Measured through their hub:
  ;;
  ;;   the check on the raw body     → holds
  ;;   the check on the decoded map  → "should be a string"
  ;;
  ;; and the decoded map is what a consumer works with. So the check either
  ;; fails on what the consumer has or passes by asserting that text is text.
  ;;
  ;; Their diagnosis was that the schema language was describing two things at
  ;; once — the envelope and the document — and that `:rest/media-type` already
  ;; owns the envelope. It does. So `:rest/response` describes the DOCUMENT for
  ;; every endpoint, which it already did for every one that does NOT serialize
  ;; itself: a JSON endpoint's schema describes the decoded value, because
  ;; check-response round-trips before judging.
  (let [doc  {:items [{:id 1}] :v 2}
        row  {:handler (fn [_] {:status 200 :http/raw true
                                :headers {"Content-Type" "application/edn"}
                                :body (pr-str doc)})
              :method :get :path "/api/doc" :auth :public
              :rest/media-type "application/edn"
              :rest/response [:map [:items [:vector [:map [:id :int]]]] [:v :int]]}
        ctx  {:http/routes [row]
              :rest/check-response rest.contract/check-response}
        GET  {:request-method :get :uri "/api/doc"}]

    (testing "a raw body is DECODED by its declared media type, then judged"
      (let [r (dispatch/handle! ctx GET)]
        (is (= 200 (:status r))
            (str "the document matches the schema; only the ENVELOPE is a"
                 " string, and judging the envelope is what made :string look"
                 " like a type: " (pr-str r)))))

    (testing "and a raw body whose DOCUMENT breaks the contract is a 500"
      ;; the half that keeps it a check. Without this the fix could be
      ;; \"decode and accept anything\", which reads identical from outside
      (let [bad (assoc row :handler
                       (fn [_] {:status 200 :http/raw true
                                :headers {"Content-Type" "application/edn"}
                                :body (pr-str {:items "not-a-vector" :v 2})}))
            r   (dispatch/handle! (assoc ctx :http/routes [bad]) GET)]
        (is (= 500 (:status r)) (pr-str r))))

    (testing "a media type slopp cannot decode leaves the body a STRING"
      ;; and then :string is an honest description rather than a lie — an
      ;; endpoint that really answers text says so and is really checked
      (let [txt (assoc row
                       :rest/media-type "text/plain"
                       :rest/response :string
                       :handler (fn [_] {:status 200 :http/raw true
                                         :headers {"Content-Type" "text/plain"}
                                         :body "hello"}))
            r   (dispatch/handle! (assoc ctx :http/routes [txt]) GET)]
        (is (= 200 (:status r)) (pr-str r))))))

(deftest a-DECLARED-contract-with-no-validator-refuses-at-assembly
  ;; The asymmetry a consuming store measured, in the form that lets a store's
  ;; own suite see it. `slopp.rest/validating` is opt-in, so "declared a
  ;; contract" and "enforces a contract" were two independent facts: an app
  ;; could stand up the same routes two ways and only one validated. Theirs
  ;; did — production wrapped, the test path did not — so a test that started a
  ;; real server and asserted 200 passed while the served endpoint 500'd.
  ;;
  ;; **Contracts were the only declaration in this map that could go unhonoured
  ;; silently.** A declared READ with no performer already refuses here, and
  ;; that refusal's reasoning applies word for word: it is checked at assembly
  ;; because at request time it is invisible. Worse here, in fact — a missing
  ;; performer 500s, while a missing validator serves 200s nobody checked.
  ;;
  ;; No malli is needed to ask this, which is what keeps it in http: the
  ;; question is whether two KEYS are present, not what they do.
  (let [row  {:handler (fn [_] {:status 200 :body {:ok true}})
              :method :get :path "/api/x" :auth :public
              :rest/response [:map [:ok :boolean]]}
        opts {:http/namespaces [] :http/routes [row]}
        wrap #(assoc % :rest/check-response rest.contract/check-response
                     :rest/decode-request rest.contract/decode-request)]

    (testing "a route declaring a contract with no validator REFUSES"
      (let [e (try (slopp.http/context opts) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "an unhonoured contract must not come up quietly")
        (is (re-find #"validating" (ex-message e))
            (str "the remedy is one call and the message has to name it: "
                 (ex-message e)))
        (is (= ["GET /api/x"] (:rest/unhonoured-contracts (ex-data e)))
            (pr-str (ex-data e)))))

    (testing "and lands once the validators are on the context"
      ;; `:http/wrap-context` is applied BY assembly, so the check sees what
      ;; the server will serve. Applying it after assembly would have let the
      ;; refusal fire on a context that was about to be given validators
      (let [ctx (slopp.http/context (assoc opts :http/wrap-context wrap))]
        (is (= 1 (count (:http/routes ctx))))
        (is (some? (:rest/check-response ctx)))))

    (testing "a route declaring NO contract is unaffected"
      ;; every http-only app is this case and it must cost exactly nothing
      (is (some? (slopp.http/context
                  {:http/namespaces []
                   :http/routes [(dissoc row :rest/response)]}))))))
