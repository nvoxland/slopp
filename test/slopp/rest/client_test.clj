(ns slopp.rest.client-test
  "The outbound caller, exercised through the sanctioned double rather than a
  socket.

  Every far side here is `slopp.http.client/fake-requester` or a plain function
  in the `:requester` slot, which is what makes the interesting cases
  assertable at all: an unreachable host, a 404, an upstream that answers the
  wrong shape, and a path that tries to name a different origin are each one
  map away from each other here and a deployment apart anywhere else.

  **The safety half is tested with no instance behind it, deliberately.** A
  store whose upstreams are all localhost cannot demonstrate a bug in a
  timeout, an escaping path or an EDN reader that evaluates — which is the
  argument for those living in the framework rather than in whichever store
  eventually meets one. The assertions are what the framework promises, written
  where the promise is, before anybody depends on it.

  Neighbours: `slopp.http.client-test` holds `requester-contract`, the suite
  both adapters of the PORT pass; this asks a different question, about the
  policy stacked on top of it."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rest.client :as rest.client]
            [slopp.rest.contract :as contract]
            [slopp.http.client :as http.client] [slopp.http.endpoint :as endpoint]))

(deftest a-server-calls-an-upstream-through-the-same-builder-a-browser-uses
  ;; The gap a consuming store measured: slopp generates a typed client for the
  ;; BROWSER and hands the SERVER a raw socket. Their hub holds the upstream's
  ;; contract — it renders it on a screen — and forwards to it with a
  ;; hand-built url string and forty lines nobody wanted to own.
  ;;
  ;; The endpoint DESCRIPTOR already works on the JVM; nothing performed it
  ;; there. This is the performer, and it receives a request whose url was
  ;; resolved once by `slopp.rest.endpoint/request` — so a browser and a server
  ;; are handed the identical value and neither decides anything about it.
  (let [thing {:http/method :get :http/path "/api/things/:id"
               :http/params #{:id :depth}}
        up    {:rest/base-url "https://up.test"}
        serve (fn [routes] (http.client/fake-requester "https://up.test" routes))]

    (testing "the descriptor's request becomes a real call, and the answer is DECODED"
      (let [r (rest.client/call!
               up (endpoint/request thing {:id "f1" :depth 2})
               {:requester (serve {[:get "/api/things/f1?depth=2"]
                                   (fn [_] {:status 200
                                            :headers {"Content-Type" "application/json"}
                                            :body "{\"name\":\"thing\"}"})})})]
        (is (= 200 (:status r)) (pr-str r))
        (is (= {:name "thing"} (:body r))
            (str "a JSON body arrives as DATA, or every caller writes the same"
                 " parse: " (pr-str r)))))

    (testing "EDN is decoded too, because slopp's own API answers it"
      (let [r (rest.client/call!
               up {:http/method :get :http/url "/api/contracts"}
               {:requester (serve {[:get "/api/contracts"]
                                   (fn [_] {:status 200
                                            :headers {"Content-Type" "application/edn"}
                                            :body "{:endpoints [] :v 1}"})})})]
        (is (= {:endpoints [] :v 1} (:body r)) (pr-str r))))

    (testing "a non-2xx comes BACK as data — the port's rule, unchanged"
      ;; an answered request returns whatever the status; only an unanswered
      ;; one throws. Deciding what a 404 means is the caller's, and this
      ;; framework must not take that away
      (let [r (rest.client/call!
               up {:http/method :get :http/url "/api/gone"}
               {:requester (serve {})})]
        (is (= 404 (:status r)) (pr-str r))))

    (testing "an UNREACHABLE upstream throws the port's own shape, unwrapped"
      ;; the consuming store's narrow catch exists because a broad one reported
      ;; THEIR bug as \"the project has probably stopped\". A second
      ;; representation here would make that catch wrong again
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"nothing is listening"
           (rest.client/call!
            {:rest/base-url "https://elsewhere.test"}
            {:http/method :get :http/url "/api/x"}
            {:requester (serve {})}))))))

(deftest what-the-framework-handles-that-a-raw-socket-does-not
  ;; Nathan's ask was two words and the second is the one with no instance yet:
  ;; "easy for you to do and SAFE (it handles security aspects of it)". A store
  ;; whose upstreams are all localhost cannot demonstrate a bug in any of this,
  ;; which is exactly why it belongs in the framework rather than in the store
  ;; that happens to notice first.
  (let [up {:rest/base-url "https://up.test"}
        ok (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                    :body "{}"})]

    (testing "a url that would leave the base REFUSES, rather than being cleaned"
      ;; the request path is the one part an upstream's own data can reach — a
      ;; slug, an id, a name from a previous response. If it can become a
      ;; different HOST then the base was never a boundary
      (doseq [p ["//evil.test/x" "https://evil.test/x" "http://evil.test/x"]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"cannot leave"
             (rest.client/call! up {:http/method :get :http/url p}
                                {:requester (constantly {:http/status 200
                                                         :http/headers {}
                                                         :http/body ""})}))
            (str "a path that names another origin must not be sent: " p))))

    (testing "a TIMEOUT is always set, even when the caller declares none"
      ;; the raw port makes it optional, so the default is \"wait forever\" and
      ;; one unresponsive upstream holds a request thread until something else
      ;; gives up. A framework that made calling easy and left this optional
      ;; would have made the wrong thing easy
      (let [seen (atom nil)]
        (rest.client/call! up {:http/method :get :http/url "/api/x"}
                           {:requester (fn [req] (reset! seen req) (ok nil)
                                         {:http/status 200 :http/headers {} :http/body ""})})
        (is (pos-int? (:http/timeout-ms @seen))
            (str "no timeout means wait forever: " (pr-str @seen))))
      (testing "and a declared one WINS"
        (let [seen (atom nil)]
          (rest.client/call! (assoc up :rest/timeout-ms 250)
                             {:http/method :get :http/url "/api/x"}
                             {:requester (fn [req] (reset! seen req)
                                           {:http/status 200 :http/headers {} :http/body ""})})
          (is (= 250 (:http/timeout-ms @seen)) (pr-str @seen)))))

    (testing "declared headers travel, which is how auth reaches the upstream"
      (let [seen (atom nil)]
        (rest.client/call! (assoc up :rest/headers {"Authorization" "Bearer t"})
                           {:http/method :get :http/url "/api/x"}
                           {:requester (fn [req] (reset! seen req)
                                         {:http/status 200 :http/headers {} :http/body ""})})
        (is (= "Bearer t" (get (:http/headers @seen) "Authorization")) (pr-str @seen))))

    (testing "a body is ENCODED by what the request declares"
      (let [seen (atom nil)]
        (rest.client/call! up {:http/method :post :http/url "/api/x"
                               :http/body {:a 1}}
                           {:requester (fn [req] (reset! seen req)
                                         {:http/status 200 :http/headers {} :http/body ""})})
        (is (= "{\"a\":1}" (:http/body @seen)) (pr-str @seen))
        (is (= "application/json" (get (:http/headers @seen) "Content-Type"))
            (pr-str @seen))))

    (testing "a CHECK that fails is a typed throw, not a value to ignore"
      ;; the upstream broke its own promise. There is no branch a caller can
      ;; take that is not \"this is broken\", and returning it would let the
      ;; wrong shape travel one more layer before anyone noticed
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"contract"
           (rest.client/call! up {:http/method :get :http/url "/api/x"}
                              {:check (fn [_] "expected a :name")
                               :requester (fn [_] {:http/status 200
                                                   :http/headers {"content-type" "application/json"}
                                                   :http/body "{}"})}))))

    (testing "and a check that passes is invisible"
      (is (= 200 (:status (rest.client/call!
                           up {:http/method :get :http/url "/api/x"}
                           {:check (constantly nil)
                            :requester (fn [_] {:http/status 200
                                                :http/headers {"content-type" "application/json"}
                                                :http/body "{}"})})))))))

(deftest the-CHECK-slot-takes-the-real-contract-check
  ;; The docstring claims the typed halves already existed and only needed a
  ;; performer. A test that passes `(constantly nil)` proves the slot is
  ;; called and nothing about that claim — so this wires the actual thing a
  ;; descriptor's `:rest/response` gets judged by:
  ;; `slopp.rest.contract/check-response`, on what a CLIENT receives.
  (let [schema [:map [:name :string] [:qty :int]]
        check  (partial contract/check-response schema)
        up     {:rest/base-url "https://up.test"}
        answer (fn [body]
                 (fn [_] {:http/status 200
                          :http/headers {"content-type" "application/json"}
                          :http/body body}))]

    (testing "an answer that honours the contract passes through decoded"
      (let [r (rest.client/call! up {:http/method :get :http/url "/api/x"}
                                 {:check check
                                  :requester (answer "{\"name\":\"a\",\"qty\":2}")})]
        (is (= {:name "a" :qty 2} (:body r)) (pr-str r))))

    (testing "an answer that breaks it throws, carrying the upstream's own words"
      (let [e (try (rest.client/call! up {:http/method :get :http/url "/api/x"}
                                      {:check check
                                       :requester (answer "{\"name\":\"a\"}")})
                   nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e) "a contract violation must not be returned as a value")
        (is (= :contract (:rest/error (ex-data e))) (pr-str (ex-data e)))
        (is (re-find #"qty" (str (:rest/violation (ex-data e))))
            (str "the explain has to name the field, or a caller learns only"
                 " that something is wrong: " (pr-str (ex-data e))))
        (is (nil? (:rest/headers (ex-data e)))
            "no credential may reach ex-data — headers are not carried here")))

    (testing "and a NON-2xx is not judged against the contract at all"
      ;; a 500's body is an error document, not the shape the endpoint
      ;; publishes. Checking it would report a contract violation for a
      ;; response that never claimed to be one, which sends the reader to the
      ;; wrong end of the wire
      (let [r (rest.client/call! up {:http/method :get :http/url "/api/x"}
                                 {:check check
                                  :requester (fn [_] {:http/status 500
                                                      :http/headers {"content-type" "application/json"}
                                                      :http/body "{\"error\":\"boom\"}"})})]
        (is (= 500 (:status r)) (pr-str r))
        (is (= {:error "boom"} (:body r)) (pr-str r))))))
