(ns slopp.ui.metrics-test
  "The Dashboard's arithmetic, checked as arithmetic."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.metrics :as metrics]))

(deftest store-shape-counts-every-row-and-a-missing-count-is-zero
  (testing "the totals are the row count and the sum of forms"
    (is (= {:namespaces 3 :forms 60}
           (metrics/store-shape [{:ns "a" :forms 10} {:ns "b" :forms 20} {:ns "c" :forms 30}]))))
  (testing "an empty document is ZERO rather than nil — a dashboard renders 0"
    (is (= {:namespaces 0 :forms 0} (metrics/store-shape []))))
  (testing "a row the wire did not fill counts as zero rather than taking the panel down"
    (is (= {:namespaces 2 :forms 4} (metrics/store-shape [{:ns "x"} {:ns "y" :forms 4}])))))

(deftest by-day-buckets-on-the-date-the-wire-already-formatted
  (testing "one bucket per day, counted, oldest first"
    (is (= [{:day "2026-08-28" :count 1} {:day "2026-08-30" :count 2}]
           (metrics/by-day [{:at "2026-08-30 11:45"} {:at "2026-08-28 11:31"} {:at "2026-08-30 02:09"}]))))
  (testing "an :at too short to carry a date is DROPPED, not bucketed under a fragment —
            a wrong day is worse than a missing one on a cadence chart"
    (is (= [{:day "2026-08-28" :count 1}]
           (metrics/by-day [{:at "2026-08-28 11:31"} {:at "nope"} {}]))))
  (testing "no commit points is an empty seq"
    (is (= [] (metrics/by-day [])))))

(deftest token-totals-counts-only-asks-that-actually-spent-anything
  (let [rows [{:ask "d1" :requests 5 :model {:tokens 1000 :cost-usd 2.0 :requests 5}}
              {:ask "d2" :requests 0}
              {:ask "d3" :requests 1 :model {:tokens 500 :cost-usd 1.0 :requests 1}}]]
    (testing "an ask with no model block spent nothing and is not averaged over"
      (is (= {:asks 2 :requests 6 :tokens 1500 :cost-usd 3.0 :mean-tokens 750}
             (metrics/token-totals rows))))
    (testing "no telemetry at all is zeros rather than a division by zero"
      (is (= {:asks 0 :requests 0 :tokens 0 :cost-usd 0.0 :mean-tokens 0}
             (metrics/token-totals [{:ask "d1" :requests 0}]))))))

(deftest recent-token-use-keeps-wire-order-and-shares-against-the-biggest-in-view
  (let [rows [{:ask "d1" :intent "newest"  :model {:tokens 100 :cost-usd 1.0}}
              {:ask "d2" :intent "middle"  :model {:tokens 200 :cost-usd 2.0}}
              {:ask "d3" :intent "nothing"}
              {:ask "d4" :intent "oldest"  :model {:tokens 50  :cost-usd 0.5}}]]
    (testing "wire order is kept — the document is newest first and that IS the axis"
      (is (= ["newest" "middle" "oldest"] (mapv :intent (metrics/recent-token-use rows 5)))))
    (testing "an ask that spent nothing is dropped rather than drawn as a zero bar"
      (is (= 3 (count (metrics/recent-token-use rows 5)))))
    (testing "share is against the biggest IN VIEW, so the window's own peak fills it"
      (is (= [0.5 1.0 0.25] (mapv :share (metrics/recent-token-use rows 5)))))
    (testing "the window is honoured"
      (is (= 2 (count (metrics/recent-token-use rows 2)))))
    (testing "nothing to show is an empty seq"
      (is (= [] (metrics/recent-token-use [] 5))))))

(deftest usd-renders-cents-without-a-platform-specific-formatter
  ;; `format` is JVM-only and `Math/round` is not portable, so this is integer
  ;; arithmetic and string surgery on purpose — the dialect refuses reader
  ;; conditionals and a money figure is not worth a helper request.
  (testing "two places, always, including the ones that need padding"
    (is (= "$10.24" (metrics/usd 10.242674)))
    (is (= "$0.00"  (metrics/usd 0)))
    (is (= "$1.00"  (metrics/usd 1.0)))
    (is (= "$0.05"  (metrics/usd 0.05)))
    (is (= "$0.50"  (metrics/usd 0.5))))
  (testing "it rounds rather than truncating — a cost reported low is the
            wrong direction to be wrong in"
    (is (= "$0.01" (metrics/usd 0.009))))
  (testing "nothing spent is zero, not blank"
    (is (= "$0.00" (metrics/usd nil)))))

(deftest spend-summary-discloses-what-fell-outside-every-ask
  ;; slopp keeps these OUT of the ask rows deliberately — attributing them to a
  ;; neighbouring ask would invent a fact. A consumer reading only :rows
  ;; therefore reports a total lower than what was spent, and says nothing.
  (let [doc {:rows [{:ask "d1" :model {:tokens 1000 :cost-usd 2.0 :requests 3}}]
             :unattributed {:requests 2 :prompts ["p1" "p2"]
                            :model {:tokens 400 :cost-usd 0.8 :requests 2}}
             :undated 7}
        s   (metrics/spend-summary doc)]
    (testing "the attributed total is unchanged — asks still mean asks"
      (is (= 1000 (:tokens s)))
      (is (= 1 (:asks s))))
    (testing "what fell outside is reported beside it, not folded into it"
      (is (= {:requests 2 :tokens 400 :cost-usd 0.8} (:unattributed s))))
    (testing "and the honest grand total says what was actually spent"
      (is (= 1400 (:total-tokens s))))
    (testing "undated requests are a COUNT — with no clock there is nothing to
              attribute, only something to disclose"
      (is (= 7 (:undated s)))))
  (testing "a document with neither reports zeros, and the grand total equals
            the attributed one rather than diverging by a nil"
    (let [s (metrics/spend-summary {:rows [{:ask "d1" :model {:tokens 50 :cost-usd 0.1 :requests 1}}]})]
      (is (= {:requests 0 :tokens 0 :cost-usd 0.0} (:unattributed s)))
      (is (= 0 (:undated s)))
      (is (= 50 (:total-tokens s))))))

(deftest growth-is-oldest-first-and-drops-points-the-fold-cannot-count
  (let [rows [{:commit "c3" :at 300 :forms 120 :namespaces 12}
              {:commit "c2" :at 200 :forms 90  :namespaces 10}
              {:commit "c1" :at 100}]]
    (testing "oldest first — the wire is newest first and a growth chart is not"
      (is (= [200 300] (mapv :at (metrics/growth rows)))))
    (testing "a point the fold cannot count is DROPPED, not zeroed — a zero
              would draw the store springing into existence"
      (is (= 2 (count (metrics/growth rows))))
      (is (not-any? #(= "c1" (:commit %)) (metrics/growth rows))))
    (testing "nothing to plot is an empty seq"
      (is (= [] (metrics/growth []))))))

(deftest effort-totals-sums-the-wall-clock-split-and-derives-its-share
  (let [rows [{:turns 2 :calls 20 :refused {:count 1 :pct 5}
               :wall {:active-ms 800 :slopp-ms 200 :outside-ms 600 :idle-ms 50}
               :rent {:carried-chars 1000}}
              {:turns 1 :calls 10 :refused {:count 0 :pct 0}
               :wall {:active-ms 200 :slopp-ms 100 :outside-ms 100 :idle-ms 10}
               :rent {:carried-chars 500}}]
        e   (metrics/effort-totals rows)]
    (testing "the counts sum"
      (is (= 3 (:turns e)))
      (is (= 30 (:calls e)))
      (is (= 1 (:refused e))))
    (testing "the wall split sums, idle included so elapsed can be recovered"
      (is (= 1000 (:active-ms e)))
      (is (= 300 (:slopp-ms e)))
      (is (= 60 (:idle-ms e))))
    (testing "share is derived from the SUMS, not averaged from per-row shares —
              averaging percentages weights a one-call segment like a hundred"
      (is (= 30 (:slopp-share e))))
    (testing "context rent carries through in characters"
      (is (= 1500 (:carried-chars e))))
    (testing "no rows is zeros rather than a division by zero"
      (is (= 0 (:slopp-share (metrics/effort-totals [])))))))

(deftest arc-summary-keeps-the-denominator-optional-because-old-entries-have-none
  (testing "red entries are the ones that failed, each keeping its own suite size"
    (let [s (metrics/arc-summary [{:delta "d1" :fail 0 :tests 41 :ms 180}
                                  {:delta "d2" :fail 2 :tests 41 :ms 240}])]
      (is (= 2 (:verifications s)))
      (is (= ["d2"] (mapv :delta (:red s))))
      (is (= 41 (:tests (first (:red s)))))
      (is (= 420 (:ms s)))))
  (testing "an entry recorded before the denominator shipped carries none, and
            a zero would read as a suite with no tests in it"
    (let [s (metrics/arc-summary [{:delta "d1" :fail 1}])]
      (is (= 1 (:verifications s)))
      (is (nil? (:tests (first (:red s)))))
      (is (nil? (:ms s)) "no entry reported a time, so there is no time to report")))
  (testing "a clean arc has no red and still counts"
    (is (= {:verifications 2 :red [] :ms nil}
           (select-keys (metrics/arc-summary [{:delta "d1" :fail 0} {:delta "d2" :fail 0}])
                        [:verifications :red :ms])))))
