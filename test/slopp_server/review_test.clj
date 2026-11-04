(ns slopp-server.review-test
  "zrs.4: the review endpoint lives in its OWN namespace (slopp-server.review) so the server-surface control lists that pin slopp-server.api.endpoints and the typed document stay true; the write session reaches it by late binding to a new process fn. server/context is restored to what it was; th…" (:require [clojure.test :refer [deftest is testing]] [slopp.store :as store] [slopp-server.api.server :as server] [slopp.http :as http] [slopp.rest :as slopp.rest] [slopp-server.review :as review]))

(deftest a-review-is-written-only-by-an-authenticated-POST
  ;; slopp-s8x.2: the served list itself carries the review door now, so the
  ;; context is built from it as the server builds its own
  (let [st (assoc (store/empty-store)
                  :deltas [{:id "d1" :op :add :ns 'demo.core :form-id "f1"}
                           {:id "d2" :op :commit :target "d1" :status :green :at 1784900000000
                            :description "first"}])
        session (atom {:store st})
        ctx (http/context
             {:http/namespaces server/served-namespaces
              :http/wrap-context slopp.rest/validating
              :http/perform-ctx {:open-reader (constantly session)
                                 :open-writer (constantly session)}})
        n   (fn [] (count (filter #(= :review (:op %)) (:recent (:store @session)))))
        nathan {:http/sub "nathan" :http/groups #{}}
        POST (fn [body ident]
               (http/handle! ctx (cond-> {:request-method :post
                                                :uri "/api/projects/demo/review"
                                                :body body}
                                         ident (assoc :http/identity ident))))
        body {:target "d2" :verdict "endorsed" :note "read the diff"}]
    (testing "no identity is a 401 and nothing is written"
      (is (= 401 (:status (POST body nil))))
      (is (= 0 (n))))
    (testing "a target that is no commit point is a 400 with the reason"
      (let [r (POST {:target "d1" :verdict "flagged"} nathan)]
        (is (= 400 (:status r)))
        (is (re-find #"commit point" (str (:error (:body r)))))
        (is (= 0 (n)))))
    (testing "a verdict outside the enum never reaches the handler"
      (is (= 400 (:status (POST {:target "d2" :verdict "approved"} nathan))))
      (is (= 0 (n))))
    (testing "an authenticated POST records the review, :by from the identity"
      (let [r (POST body nathan)
            d (last (:recent (:store @session)))]
        (is (= 200 (:status r)) (pr-str r))
        (is (= 1 (n)))
        (is (= :review (:op d)))
        (is (= "d2" (:target d)))
        (is (= :endorsed (:verdict d)))
        (is (= "nathan" (:by d)))
        (is (= "read the diff" (:note d)))))))

(deftest a-review-needs-a-real-commit-point-and-a-verdict
  (let [st (assoc (store/empty-store)
                  :deltas [{:id "d1" :op :add :ns 'demo.core :form-id "f1"}
                           {:id "d2" :op :commit :target "d1" :status :green :at 1784900000000
                            :description "first"}])
        s  (atom {:store st})
        ;; a committed marker leaves the value's :deltas and sits in :recent
        n  (fn [] (count (filter #(= :review (:op %)) (:recent (:store @s)))))]
    (testing "a target that is not a commit point is refused, with the way out"
      (let [r (review/review-commit! s "d1" :endorsed :by "nathan")]
        (is (string? (:error r)))
        (is (re-find #"commit point" (:error r)))
        (is (= 0 (n)) "nothing was written")))
    (testing "a verdict other than endorsed or flagged is refused"
      (let [r (review/review-commit! s "d2" :approved :by "nathan")]
        (is (re-find #"endorsed" (:error r)))
        (is (= 0 (n)))))
    (testing "a review needs a reviewer"
      (is (string? (:error (review/review-commit! s "d2" :endorsed))))
      (is (= 0 (n))))
    (testing "a real commit point and a verdict record the marker"
      (let [r (review/review-commit! s "d2" :flagged :by "nathan" :note "too wide")]
        (is (nil? (:error r)) (pr-str r))
        (is (= 1 (n)))
        (is (= {:op :review :target "d2" :verdict :flagged :by "nathan" :note "too wide"}
               (select-keys (last (:recent (:store @s))) [:op :target :verdict :by :note])))))))
