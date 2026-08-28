(ns slopp.currency-test
  "That a derivation stamp keeps THREE answers apart — current, stale, and
  nobody measured — and that it notices the two different ways a store moves.

  These are `^:external` because the mechanism's whole point is a real
  journal: the cases worth pinning are a line advancing under a stamp and
  `elements` rows being rewritten with no delta appended, neither of which can
  be staged against an in-memory store."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [slopp.currency :as slopp.currency]
            [slopp.store :as store]
            [slopp.store.db :as db]))

(defn- temp-dir []
  (str (System/getProperty "java.io.tmpdir") "/slopp-currency-" (System/nanoTime)))

(deftest ^:external a-derived-value-can-ask-whether-its-store-moved
  ;; Cause 1 of the two-agent wave: slopp tracks CONTENT rigorously and tracks
  ;; DERIVATION not at all once a value leaves the process that made it. A
  ;; route table, a jar, a projection, a verdict and a cache entry are each a
  ;; value with no record of what it came from — so every reader answers
  ;; confidently, and ten of seventeen measured frictions are that.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            s1    (store/ingest (store/empty-store) 'cur.one "(ns cur.one)\n\n(def a 1)\n")
            _     (db/append! conn s1 (store/deltas s1) ['cur.one] trunk nil)
            stamp (slopp.currency/of conn trunk)]

        (testing "a stamp taken now reads as current, and says what it came from"
          (let [r (slopp.currency/report conn stamp)]
            (is (true? (:current? r)) (pr-str r))
            (is (= stamp (:derived-from r)) (pr-str r))))

        (testing "and stops being current when the line moves under it"
          (let [s2  (store/ingest s1 'cur.two "(ns cur.two)\n\n(def b 2)\n")
                new (vec (drop (count (store/deltas s1)) (store/deltas s2)))]
            (is (true? (db/append! conn s2 new ['cur.two] trunk (:head stamp)))
                "fixture: the line really did move")
            (is (false? (:current? (slopp.currency/report conn stamp)))
                (pr-str (slopp.currency/report conn stamp)))))

        (testing "a value NOBODY stamped is unknown — never current, and never stale either"
          ;; the standing rule this whole mechanism exists to serve: "I did not
          ;; check" and "I checked and it was fine" must not share a
          ;; representation. false would be a claim about the store that
          ;; nothing measured.
          (let [r (slopp.currency/report conn nil)]
            (is (nil? (:current? r)) (pr-str r))
            (is (some? (:why r)) "and it says which of the two it is"))))
      (finally (.close conn)))))

(deftest ^:external a-stamp-notices-rows-that-changed-without-a-delta
  ;; Why the identity is a head AND a digest. `elements` is the journal
  ;; materialized, and a migration, a repair script or any external process
  ;; rewriting those rows changes what the store IS without appending a delta.
  ;; The head does not move, so a head-only stamp reports "current" about a
  ;; store that changed underneath it. This is the same case
  ;; `engine/refresh-cache!` gates the session cache on, for the same reason.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            s1    (store/ingest (store/empty-store) 'cur.three "(ns cur.three)\n\n(def a 1)\n")
            _     (db/append! conn s1 (store/deltas s1) ['cur.three] trunk nil)
            stamp (slopp.currency/of conn trunk)]
        (is (true? (:current? (slopp.currency/report conn stamp))) "fixture: current to start")
        (jdbc/execute! conn ["UPDATE elements SET source = source || ' ;; migrated' WHERE line = ?"
                             trunk])
        (is (= (:head stamp) (db/line-head conn trunk))
            "fixture: the head did NOT move — this is exactly what a head-only stamp misses")
        (is (false? (:current? (slopp.currency/report conn stamp)))
            (pr-str (slopp.currency/report conn stamp))))
      (finally (.close conn)))))
