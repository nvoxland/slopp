(ns slopp.lab.reads-test
  "Cover for a number that decides whether the read tier gets a lever.

  The read ledger is read once, by a human, to decide whether the size gate
  should become budget-aware — and the standing warning about this tier is
  that its cheap levers have measured zero before. So the failure that
  matters is not the arithmetic. It is the ledger lying in the direction that
  argues FOR building, which is the direction nobody double-checks.

  Two shapes do that and both are pinned here: a rate computed over the turns
  that happen to carry a read record while every turn recorded before the
  record existed quietly leaves the population, and a zero re-fetch rate over
  a journal that never withheld anything — which reads as *measured, and the
  trim is free* when it means *nothing has been measured yet*."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.lab.reads :as lab.reads]))

(deftest the-read-ledger-counts-only-the-turns-that-carry-the-record
  (let [turn   (fn [store timing]
                 (first (store/record-turn store :turn-end :timing timing)))
        ledger lab.reads/read-ledger]
    (testing "an empty journal has no rate — a 0.0 here reads as 'measured,
              and the trim never costs anything'"
      (let [r (ledger (store/empty-store))]
        (is (= 0 (:turns r)) (pr-str r))
        (is (= 0 (:measured r)) (pr-str r))
        (is (nil? (:refetch-rate r)) (pr-str r))))

    (testing "turns recorded before the read record existed are COUNTED as
              unmeasured, never silently dropped — a ledger over the handful
              of turns that carry it looks exactly like one over all of them"
      (let [st (-> (store/empty-store)
                   (turn {:calls 3 :slopp-ms 90})
                   (turn {:calls 1 :reads {:chars 500 :withheld 0 :trimmed 0
                                           :stubbed 0 :refetched 0
                                           :refetched-chars 0
                                           :refetched-elsewhere 0
                                           :by-tool [{:tool "query_slice" :n 1 :chars 500}]}}))
            r  (ledger st)]
        (is (= 2 (:turns r)) (pr-str r))
        (is (= 1 (:measured r)) (pr-str r))
        (is (= 1 (:unmeasured r)) (pr-str r))
        (is (= 500 (:chars r)) (pr-str r))
        (is (nil? (:refetch-rate r))
            (str "nothing was withheld across the measured turns, so there is"
                 " no rate: " (pr-str r)))))

    (testing "per-tool cost accumulates ACROSS turns — the question is which
              tool costs the most over a session, and one turn cannot say"
      (let [reads-of (fn [rows] {:chars (reduce + (map :chars rows))
                                 :withheld 0 :trimmed 0 :stubbed 0
                                 :refetched 0 :refetched-chars 0
                                 :refetched-elsewhere 0 :by-tool rows})
            st (-> (store/empty-store)
                   (turn {:reads (reads-of [{:tool "query_slice" :n 2 :chars 900}
                                            {:tool "done" :n 1 :chars 400}])})
                   (turn {:reads (reads-of [{:tool "query_slice" :n 1 :chars 600}])}))
            r  (ledger st)]
        (is (= 1900 (:chars r)) (pr-str r))
        (is (= [{:tool "query_slice" :n 3 :chars 1500}
                {:tool "done" :n 1 :chars 400}]
               (:by-tool r))
            (pr-str r))))

    (testing "the re-fetch rate is over what was WITHHELD, and it is the
              number the whole instrument exists to produce: a withholding
              that gets opened anyway cost more than sending the payload"
      (let [st (-> (store/empty-store)
                   (turn {:reads {:chars 8367 :withheld 1 :trimmed 1 :stubbed 0
                                  :refetched 1 :refetched-chars 21676
                                  :refetched-elsewhere 0
                                  :by-tool [{:tool "query_history" :n 1 :chars 8367
                                             :trimmed 1 :refetched 1}]}})
                   (turn {:reads {:chars 8000 :withheld 1 :trimmed 1 :stubbed 0
                                  :refetched 0 :refetched-chars 0
                                  :refetched-elsewhere 0
                                  :by-tool [{:tool "query_rules" :n 1 :chars 8000
                                             :trimmed 1}]}}))
            r  (ledger st)]
        (is (= 2 (:withheld r)) (pr-str r))
        (is (= 1 (:refetched r)) (pr-str r))
        (is (= 21676 (:refetched-chars r)) (pr-str r))
        (is (= 0.5 (:refetch-rate r)) (pr-str r))
        (is (= 1 (:refetched (first (filter #(= "query_history" (:tool %))
                                            (:by-tool r)))))
            (pr-str r))))

    (testing "a re-fetch the per-turn fold could not attribute stays visible
              at journal grain — the trim and the re-fetch straddling a turn
              boundary is the case a per-turn number silently forgives"
      (let [st (turn (store/empty-store)
                     {:reads {:chars 900 :withheld 0 :trimmed 0 :stubbed 0
                              :refetched 0 :refetched-chars 0
                              :refetched-elsewhere 2
                              :by-tool [{:tool "query_detail" :n 2 :chars 900}]}})
            r  (ledger st)]
        (is (= 2 (:refetched-elsewhere r)) (pr-str r))
        (is (nil? (:refetch-rate r)) (pr-str r))))))
