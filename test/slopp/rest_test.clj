(ns slopp.rest-test
  "The in-process caller, which is the loop this capability owes an author.

  What these tests pin is the difference between the value a handler RETURNS
  and the value a consumer RECEIVES — a keyword that becomes a string, a set
  that becomes an array. Every hand-written in-image contract assertion in this
  repo has been checking the first while claiming the second, which is what the
  crossings registry has meant by calling the wire crossing blind."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rest :as slopp.rest]))

(deftest calling-your-own-endpoint-shows-what-a-CLIENT-would-see
  ;; The e2e loop this capability owes an author. Today, to find out what a
  ;; consumer actually receives, you have to start a server — slopp's own
  ;; `fake-requester` says so in its docstring ("a test that depends on the
  ;; parsed shape must run against a real server to mean anything") and
  ;; `handle!`'s teach marker tells you to round-trip through JSON by hand.
  ;;
  ;; So the framework knew, and made it the author's problem. This is the
  ;; framework doing it: in process, no socket, no browser, through the SAME
  ;; encoding the adapter uses.
  (let [ctx (slopp.rest/validating
             {:web/routes
              [{:handler (fn [req] {:status 200 :body {:echo (:body req) :kind :ok}})
                :method :post :path "/api/echo" :auth :public
                :web/request [:map [:sku :string]]}
               {:handler (fn [_] {:status 200 :body {:tags #{"a"}}})
                :method :get :path "/api/tags" :auth :public}]})]

    (testing "the body comes back as the CLIENT receives it, not as Clojure"
      ;; :ok is a keyword here and a string over there. An in-image assertion
      ;; on the handler's return value would see the keyword and be checking a
      ;; shape no consumer ever gets.
      (let [r (slopp.rest/call ctx {:method :post :path "/api/echo" :body {:sku "abc"}})]
        (is (= 200 (:status r)) (pr-str r))
        (is (= "ok" (:kind (:body r)))
            "a keyword arrives as a string, which is the fact every hand-written
             in-image contract assertion has been getting wrong")
        (is (= {:sku "abc"} (:echo (:body r))))))

    (testing "and a set arrives as an array"
      (let [r (slopp.rest/call ctx {:method :get :path "/api/tags"})]
        (is (= ["a"] (:tags (:body r)))
            "the other half of the same fact, and the one that makes an
             in-image :set contract assertion pass while the consumer breaks")))

    (testing "the boundary is REAL in the fake too — a bad body is refused here
              exactly as it would be over a socket"
      ;; if the fake skipped validation it would be a second implementation of
      ;; the server, and the two would drift on the first change
      (let [r (slopp.rest/call ctx {:method :post :path "/api/echo" :body {:sku 42}})]
        (is (= 400 (:status r)) (pr-str r))))

    (testing "an unrouted path answers 404, not nil"
      (let [r (slopp.rest/call ctx {:method :get :path "/api/nope"})]
        (is (= 404 (:status r)) (pr-str r))))))
