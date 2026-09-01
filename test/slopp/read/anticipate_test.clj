(ns slopp.read.anticipate-test
  "Pins the predictor to what the offline replay validated — direct
  requires only, smallest first, budget refuses rather than truncates —
  so a future signal idea has to re-argue with the measurement, not
  quietly widen the attachment."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.read.anticipate :as anticipate]))

(deftest expansion-carries-the-direct-requires-smallest-first
  ;; The replayed truth this pins (eval12 wave A, 6 transcripts): models read
  ;; WHOLE NAMESPACES and walk the require graph one edge at a time, so the
  ;; direct requires of what was just read are the next asks — 46-61% of
  ;; sonnet's read calls, ~3k tokens of rent. Every richer signal (2-hop,
  ;; reverse edges) bought <=1 more call for 7-20x the rent.
  (let [st (-> (store/empty-store)
               (store/ingest 'fix.b "(ns fix.b)\n(defn b \"B.\" [x] x)\n")
               (store/ingest 'fix.c "(ns fix.c)\n(defn c1 \"C.\" [x] x)\n(defn c2 \"C2.\" [x] x)\n(defn c3 \"C3.\" [x] x)\n")
               (store/ingest 'fix.a "(ns fix.a (:require [clojure.string :as str] [fix.b :as b] [fix.c :as c]))\n(defn a \"A.\" [x] (b/b (c/c1 x)))\n")
               (store/ingest 'fix.d "(ns fix.d)\n(defn d \"D.\" [x] x)\n"))]
    (testing "direct requires only, store members only, smallest first"
      (let [rows (anticipate/expansion st 'fix.a (constantly false) 2000)]
        (is (= '[fix.b fix.c] (mapv :ns rows)) (pr-str rows))
        (is (every? (comp string? :source) rows))
        (is (every? (comp pos? :tokens) rows))))
    (testing "a namespace the reader already holds is never attached"
      (is (= '[fix.c] (mapv :ns (anticipate/expansion st 'fix.a #{'fix.b} 2000)))))
    (testing "the budget is cumulative and refuses, never truncates"
      (let [small (:tokens (first (anticipate/expansion st 'fix.a (constantly false) 2000)))]
        (is (= '[fix.b] (mapv :ns (anticipate/expansion st 'fix.a (constantly false) small))))))
    (testing "a leaf expands to nothing, and an unknown ns answers empty"
      (is (= [] (anticipate/expansion st 'fix.b (constantly false) 2000)))
      (is (= [] (anticipate/expansion st 'nope.core (constantly false) 2000))))))
