(ns slopp.read.telemetry-test
  "Cover for `slopp.read.telemetry` — the folds slopp uses to measure itself.

  Every subject here is pure, so the tests hand it a synthetic journal or call
  ring and assert on data: no session, no image, in-image and sub-millisecond.
  The fixtures deliberately carry the REAL numbers that motivated each
  measure (the turn that read 0% while a human was asleep, the 57 refusals in
  the first nine records), because a measurement whose motivating observation
  is lost is one nobody can tell is still worth taking."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.read.telemetry :as telemetry]
            [slopp.store :as store] [slopp.read.query :as query]))

(deftest rule-telemetry-fire-rate-and-persistence
  (let [[s1 _] (store/record-done (store/empty-store) "d1"
                                  :findings {:key-typos [{:used :a/emial :suggest :a/email}]
                                             :test-status :green})
        [s2 _] (store/record-done s1 "d2"
                                  :findings {:key-typos [{:used :a/emial :suggest :a/email}]
                                             :unused-public ['app.core/x]})
        [s3 _] (store/record-done s2 "d3" :findings {:test-status :green})
        t      (telemetry/rule-telemetry s3)]
    (testing "fire-rate: dones-fired + total instances per rule"
      (is (= 3 (get-in t [:window :dones])))
      (is (= 2 (get-in t [:fire-rate :key-typos :dones])))
      (is (= 2 (get-in t [:fire-rate :key-typos :instances])))
      (is (= 1 (get-in t [:fire-rate :unused-public :dones]))))
    (testing "persistence: the same instance flagged across >1 done is un-discharged"
      (is (= 1 (get-in t [:fire-rate :key-typos :persisted])))
      (is (= 0 (get-in t [:fire-rate :unused-public :persisted]))))
    (testing "metadata finding keys (test-status) are not counted as rule fires"
      (is (nil? (get-in t [:fire-rate :test-status]))))))

(deftest call-timing-splits-a-turn-into-slopp-and-everything-else
  ;; Measured over a real session: 1,703s elapsed, 390s of it recorded as
  ;; verification — 22%. The other 78% was invisible, and "invisible" was the
  ;; whole problem: you cannot tell an agent that reasons slowly from a tool
  ;; that runs slowly if only one of them is instrumented.
  ;;
  ;; The server sees both edges. It knows when a call arrived and when it
  ;; answered, so the gap BETWEEN calls is time slopp was not working. That is
  ;; deliberately not called "thinking time": it is agent reasoning plus every
  ;; non-slopp tool (file reads, shell, subagents) plus the harness, and the
  ;; server cannot tell them apart. Naming it for what it measures is the
  ;; point — P7 says the cost of leaving slopp is invisible unless written
  ;; down, and this is where it lands.
  (let [calls [{:tool "query_slice" :start 1000 :end 1050}
               {:tool "edit_add_form" :start 3050 :end 3550}
               {:tool "done" :start 5550 :end 9550}]]
    (testing "the two halves and their total"
      (let [t (telemetry/call-timing calls)]
        (is (= 3 (:calls t)))
        (is (= 4550 (:slopp-ms t)) "50 + 500 + 4000")
        (is (= 4000 (:outside-ms t)) "2000 between call 1 and 2, 2000 between 2 and 3")
        (is (= 8550 (:elapsed-ms t)) "first arrival to last answer")
        (is (= (:elapsed-ms t) (+ (:slopp-ms t) (:outside-ms t)))
            "the split must be exhaustive — an unexplained remainder is the bug")))
    (testing "the tools that actually cost, largest first"
      (let [top (:top (telemetry/call-timing calls))]
        (is (= "done" (:tool (first top))))
        (is (= 4000 (:ms (first top))))
        (is (= 1 (:n (first top))))))
    (testing "repeated calls of one tool aggregate"
      (let [t (telemetry/call-timing [{:tool "test_run" :start 0 :end 100}
                                      {:tool "test_run" :start 100 :end 300}])]
        (is (= [{:tool "test_run" :n 2 :ms 300}] (:top t)))
        (is (zero? (:outside-ms t)))))
    (testing "no calls is nil, not a zeroed record that reads as measured"
      (is (nil? (telemetry/call-timing [])))
      (is (nil? (telemetry/call-timing nil))))))

(deftest call-timing-counts-the-calls-that-were-REFUSED
  ;; The measurement nobody had taken. 78% of a session's wall clock is spent
  ;; outside slopp — agent reasoning and non-slopp tools — and the largest
  ;; identifiable waste in that half is calls that get refused and retried: a
  ;; malformed edit_subform match, a lint error in a form being written, an
  ;; arity break. Each costs a full round trip and none was ever counted.
  ;;
  ;; Reported as a RATE with the tools named, because the actionable form is
  ;; "a fifth of your writes bounced, mostly on edit_subform" — a raw count
  ;; says nothing about whether it is worth changing anything.
  (let [calls [{:tool "query_slice"    :start 0    :end 50}
               {:tool "edit_subform"   :start 100  :end 200 :refused? true}
               {:tool "edit_subform"   :start 300  :end 400 :refused? true}
               {:tool "edit_subform"   :start 500  :end 600}
               {:tool "edit_add_form"  :start 700  :end 900 :refused? true}
               {:tool "done"           :start 1000 :end 3000}]
        t (telemetry/call-timing calls)]
    (testing "the rate, and the tools that bounced"
      (is (= 3 (get-in t [:refused :count])))
      (is (= 50 (get-in t [:refused :pct])) "3 of 6 calls")
      (is (= [{:tool "edit_subform" :n 2} {:tool "edit_add_form" :n 1}]
             (get-in t [:refused :by-tool]))
          "largest first — the one to fix is the one that bounces most"))
    (testing "refused calls still count toward the time they cost"
      (is (= 3 (count (filter #(= "edit_subform" (:tool %)) calls)))
          "fixture sanity")
      (is (= 300 (:ms (first (filter #(= "edit_subform" (:tool %)) (:top t)))))
          "100 + 100 + 100 — a bounced call costs its wall time like any other"))
    (testing "a clean turn says so with a zero, not by omitting the key"
      ;; absence would read as unmeasured, which is the conflation this
      ;; codebase keeps paying for
      (let [clean (telemetry/call-timing [{:tool "done" :start 0 :end 10}])]
        (is (= 0 (get-in clean [:refused :count])))
        (is (= [] (get-in clean [:refused :by-tool])))))))

(deftest call-timing-does-not-count-a-paused-session-as-time-slopp-failed-to-use
  ;; Read from the live journal, 2026-07-25, the first nine turns that ever
  ;; carried timing: one recorded 46 calls, 224s of slopp work and 45,501s
  ;; elapsed — `:slopp-share "0%"`. Nothing was slow. The human went away
  ;; mid-turn and the turn stayed open, because rotation fires on the
  ;; WRITE-tool gate and a read-only ask folds into the next writing one.
  ;;
  ;; So the instrument's headline number read "slopp was 0% of the working
  ;; time" about a turn where slopp was most of it. `:outside-ms` is honestly
  ;; named for what it measures, but a twelve-hour gap is not the thing the
  ;; name was defending — it is a session nobody was in, and folding it into
  ;; the same bucket as agent reasoning makes both unreadable.
  (let [hour  (* 60 60 1000)
        calls [{:tool "query_slice"   :start 0                :end 1000}
               {:tool "edit_add_form" :start (+ 1000 (* 6 hour)) :end (+ 2000 (* 6 hour))}]
        t     (telemetry/call-timing calls)]
    (testing "a gap no agent could spend is IDLE, named separately"
      (is (= (* 6 hour) (:idle-ms t)))
      (is (= 0 (:outside-ms t))
          "and it does NOT also count as time slopp was not working"))
    (testing "the split stays exhaustive — now three ways"
      (is (= (:elapsed-ms t) (+ (:slopp-ms t) (:outside-ms t) (:idle-ms t)))))
    (testing "share is against ACTIVE time, so a pause cannot read as slopp being slow"
      (is (= "100%" (:slopp-share t))))
    (testing "ordinary between-call gaps are NOT idle — that is the number being defended"
      (let [t2 (telemetry/call-timing [{:tool "query_slice" :start 0    :end 100}
                                       {:tool "done"        :start 2100 :end 2200}])]
        (is (= 0 (:idle-ms t2)))
        (is (= 2000 (:outside-ms t2)))))))

(deftest refused-calls-carry-the-reason-they-bounced
  ;; The first nine real turn records: 57 refusals across 492 calls (11.6%),
  ;; 23 of them `edit_subform` and 41 of 57 writes. The RATE was actionable.
  ;; The cause was recorded nowhere, so the only advice the number could ever
  ;; support was "read that tool's contract" — which is the guess, not the
  ;; finding.
  ;;
  ;; A classification table written now would be invented from source greps
  ;; rather than derived from what actually bounces, and this codebase has
  ;; already paid for one of those (the `:positional-form-access` advisory,
  ;; withdrawn at 4-5 false positives out of 5). So carry the messages
  ;; VERBATIM and bounded, and let a later read derive the classes.
  (let [calls [{:tool "edit_subform"  :start 0  :end 10 :refused? true
                :error "no match for `(let [x 1]` in my.app.orders/place!"}
               {:tool "edit_add_form" :start 20 :end 30 :refused? true
                :error "dialect (D3): denylisted symbol used — read-string"}
               {:tool "done"          :start 40 :end 50}]
        t     (telemetry/call-timing calls)]
    (testing "each bounced call reports what it said, with the tool that said it"
      (is (= [{:tool "edit_subform"  :error "no match for `(let [x 1]` in my.app.orders/place!"}
              {:tool "edit_add_form" :error "dialect (D3): denylisted symbol used — read-string"}]
             (get-in t [:refused :samples]))))
    (testing "bounded — a turn that bounces fifty times does not carry fifty messages onto its delta"
      (let [many (map (fn [i] {:tool "edit_subform" :start i :end i
                               :refused? true :error (str "reason " i)})
                      (range 50))]
        (is (= 10 (count (get-in (telemetry/call-timing many) [:refused :samples]))))))
    (testing "and each message is truncated — a refusal can carry a whole form back"
      (let [long-one [{:tool "edit_subform" :start 0 :end 1 :refused? true
                       :error (apply str (repeat 900 "x"))}]]
        (is (= 200 (count (:error (first (get-in (telemetry/call-timing long-one)
                                                 [:refused :samples])))))
            "a bounded sample, not a payload")))
    (testing "a clean turn reports an empty vector, never an absent key"
      ;; absence would read as unmeasured — the same conflation the count and
      ;; the by-tool list already refuse to make
      (is (= [] (get-in (telemetry/call-timing [{:tool "done" :start 0 :end 1}])
                        [:refused :samples]))))))

(deftest a-turns-reads-are-ranked-by-what-they-COST-and-a-trim-can-be-a-NET-LOSS
  ;; The standing finding is that reads are 52% of the token bill and got the
  ;; least optimization. The one lever shipped on that tier — the response
  ;; trim — has been measured exactly once, by hand, off a single transcript:
  ;; an 8,367-char trimmed history read plus a 21,676-char re-fetch, where
  ;; sending the payload whole would have cost 21,676. That trim spent 30k to
  ;; save nothing, and nothing in the system could tell that case from the one
  ;; where the trim paid. This fold is what tells them apart, so the ways it
  ;; can lie are the subject here rather than the arithmetic.
  (let [cost telemetry/read-cost]
    (testing "a turn nobody measured is not a turn that cost nothing — every
              call recorded before the size existed carries no :chars, and a
              zeroed record over those reads exactly like a measured zero"
      (is (nil? (cost [])))
      (is (nil? (cost [{:tool "done" :start 0 :end 10}]))))

    (testing "tools rank by what they actually SENT, largest first — the tool
              that costs the most may be the cheap one called a hundred times"
      (let [r (cost [{:tool "query_slice" :chars 400}
                     {:tool "query_rules" :chars 8000}
                     {:tool "query_slice" :chars 600}])]
        (is (= 9000 (:chars r)) (pr-str r))
        (is (= [{:tool "query_rules" :n 1 :chars 8000}
                {:tool "query_slice" :n 2 :chars 1000}]
               (:by-tool r))
            (pr-str r))))

    (testing "a withholding nobody opened is the trim PAYING, and 0.0 is a
              true statement about it — unlike the nil above"
      (let [r (cost [{:tool "query_rules" :chars 8000 :trimmed? true :spooled "r1"}])]
        (is (= 1 (:withheld r)) (pr-str r))
        (is (= 0 (:refetched r)) (pr-str r))
        (is (= 0.0 (:refetch-rate r)) (pr-str r))))

    (testing "a trim the agent immediately re-fetched cost MORE than sending
              the payload whole, and both halves of that sum are present"
      (let [r (cost [{:tool "query_rules" :chars 8367 :trimmed? true :spooled "r1"}
                     {:tool "query_detail" :chars 21676 :detail-asked "r1"}])]
        (is (= 1 (:refetched r)) (pr-str r))
        (is (= 21676 (:refetched-chars r)) (pr-str r))
        (is (= 1.0 (:refetch-rate r)) (pr-str r))
        (is (= 1 (:refetched (first (filter #(= "query_rules" (:tool %)) (:by-tool r)))))
            (str "the re-fetch is charged to the tool that MINTED the id."
                 " Charged to query_detail it would rank the retrieval path as"
                 " the expensive one and leave every trimming tool clean: "
                 (pr-str r)))))

    (testing "a re-fetch naming an id this turn never minted is COUNTED, not
              dropped — a turn boundary between the trim and the re-fetch is
              not evidence that the trim paid"
      (let [r (cost [{:tool "query_detail" :chars 900 :detail-asked "r99"}])]
        (is (= 0 (:refetched r)) (pr-str r))
        (is (= 1 (:refetched-elsewhere r)) (pr-str r))
        (is (nil? (:refetch-rate r))
            (str "nothing was withheld in this turn, so there is no rate to"
                 " take — not a rate of none: " (pr-str r)))))

    (testing "the dedup path asks the same question: an :unchanged stub the
              agent had to open is a withholding that was paid for twice"
      (let [r (cost [{:tool "query_slice" :chars 120 :stub? true :spooled "r2"}
                     {:tool "query_detail" :chars 3000 :detail-asked "r2"}])]
        (is (= 1 (:stubbed r)) (pr-str r))
        (is (= 1 (:refetched r)) (pr-str r))))

    (testing "a span says how many calls it is over, because a row with no
              count is a row nobody can weigh against another"
      (let [r (cost [{:tool "query_slice" :chars 400}
                     {:tool "query_slice" :chars 600}
                     {:tool "done" :start 0 :end 9}])]
        (is (= 2 (:calls r))
            (str "the calls that carried a size, not every call in the ring —"
                 " counting the ones with nothing to measure would inflate the"
                 " denominator of anything taken per call: " (pr-str r)))))

    (testing "the TURN record does NOT carry it — the read cost has its own
              journal citizen now, and two homes at two grains is the shape
              where the two eventually disagree"
      ;; It rode `:turn-end` first, and inherited the rotation gate with it: a
      ;; turn closes only when a user PROMPT arrived and a WRITE followed, so
      ;; a read-only ask and an event-driven session both recorded nothing.
      ;; `:read-cost` flushes on its own schedule; `call-timing` is back to
      ;; being only about the clock.
      (let [t (telemetry/call-timing [{:tool "query_rules" :start 0 :end 10
                                       :chars 8000 :trimmed? true :spooled "r1"}])]
        (is (not (contains? t :reads))
            (str "the turn record must not carry a read fold any more: "
                 (pr-str t)))
        (is (= 1 (:calls t)) (str "and is otherwise untouched: " (pr-str t)))))))

(deftest turn-cost-folds-the-records-that-nothing-has-ever-read
  ;; `call-timing` has written `:timing` onto every `:turn-end` delta since it
  ;; shipped, and no surface folds it. Answering "where did the wall clock go"
  ;; over this store took ~90 minutes of hand-written `query_store` folds on
  ;; 2026-08-27; it should be one call. Same shape as `rule-telemetry`: a
  ;; read-only fold over the delta log, no new instrumentation.
  (let [t1 {:calls 3 :slopp-ms 1000 :outside-ms 2000 :idle-ms 0
            :elapsed-ms 3000 :slopp-share "33%"
            :top [{:tool "done" :n 1 :ms 800}
                  {:tool "query_slice" :n 2 :ms 200}]
            :refused {:count 1 :pct 33 :by-tool [{:tool "edit_subform" :n 1}]}}
        t2 {:calls 2 :slopp-ms 500 :outside-ms 500 :idle-ms 10000
            :elapsed-ms 11000 :slopp-share "50%"
            :top [{:tool "full_check" :n 2 :ms 500}]
            :refused {:count 0 :pct 0 :by-tool []}}
        [s1 _] (store/record-turn (store/empty-store) :turn-end :timing t1)
        ;; a turn that recorded nothing must not read as a turn that cost zero
        [s2 _] (store/record-turn s1 :turn-end)
        [s3 _] (store/record-turn s2 :turn-end :timing t2)
        c      (telemetry/turn-cost s3)]

    (testing "only measured turns are counted"
      (is (= 2 (get-in c [:window :turns]))
          "the un-timed turn is absent, not a zero"))

    (testing "the wall clock splits three ways and stays exhaustive"
      (is (= 14000 (get-in c [:wall :elapsed-ms])))
      (is (= 10000 (get-in c [:wall :idle-ms])))
      (is (= 4000  (get-in c [:wall :active-ms])) "elapsed minus idle")
      (is (= 1500  (get-in c [:wall :slopp-ms])))
      (is (= 2500  (get-in c [:wall :outside-ms])))
      (is (= (get-in c [:wall :elapsed-ms])
             (+ (get-in c [:wall :slopp-ms])
                (get-in c [:wall :outside-ms])
                (get-in c [:wall :idle-ms])))
          "an unexplained remainder is the bug"))

    (testing "the share is against ACTIVE time, as call-timing takes it"
      (is (= "37%" (get-in c [:wall :slopp-share]))
          "1500 of 4000 — a session nobody was in is not time slopp failed to use"))

    (testing "tools rank by what they COST, not by how often they were called"
      (is (= "done" (:tool (first (:tools c)))) "800ms in one call outranks…")
      (is (= "query_slice" (:tool (last (:tools c)))) "…200ms in two")
      (is (= {:tool "full_check" :calls 2 :ms 500 :avg-ms 250}
             (second (:tools c)))))

    (testing "refusals aggregate across turns"
      (is (= 5 (get-in c [:calls :total])))
      (is (= 1 (get-in c [:refused :count])))
      (is (= 20 (get-in c [:refused :pct])) "1 of 5, over calls not turns")
      (is (= [{:tool "edit_subform" :n 1}] (get-in c [:refused :by-tool]))))

    (testing "repeats inside ONE ask are ranked by what they COST"
      ;; the finding this exists for: 114 of 320 full_checks were repeats
      ;; within a single turn — 7.5 hours of asking the same question twice.
      ;;
      ;; Both tools here ran twice in one turn, and only one of them is waste:
      ;; two reads in an ask is ordinary. Nothing is filtered out, because a
      ;; cut-off would be a number nobody measured — the ORDER carries the
      ;; judgement instead, and the expensive one has to come first.
      (is (= [{:tool "full_check"  :turns 1 :extra 1 :extra-ms 250}
              {:tool "query_slice" :turns 1 :extra 1 :extra-ms 100}]
             (:repeats c))
          "half of each tool's turn cost is attributable to its second run"))

    (testing "since windows to turns AFTER that delta"
      (let [ids (mapv :id (:deltas s3))
            c2  (telemetry/turn-cost s3 :since (first ids))]
        (is (= 1 (get-in c2 [:window :turns]))
            "only the last timed turn survives the window")))))

(deftest turn-cost-reports-the-MODEL-side-beside-the-wall-clock
  ;; The question this whole fold exists for is "where did the time go", and
  ;; until now it could only answer for slopp's own half. The other half —
  ;; tokens, cost, and how full the conversation was — is the harness's, and it
  ;; arrives as :otel deltas. Reporting them in the SAME call is the point: a
  ;; session that is slow because of context size and one that is slow because
  ;; of whole-store checks look identical in wall time alone.
  (let [turn  {:op :turn-end :id "d1"
               :timing {:elapsed-ms 1000 :idle-ms 0 :slopp-ms 400
                        :outside-ms 600 :calls 2}}
        otel  {:op :otel :id "d2"
               :requests [{:session "s" :input 2 :output 4 :cache-read 9987
                           :cache-creation 7559 :context 17548 :cost-usd 0.08}
                          {:session "s" :input 10 :output 20 :cache-read 500
                           :cache-creation 0 :context 510 :cost-usd 0.01}]}]

    (testing "ABSENT when no telemetry was ever received"
      ;; opt-in: the exporter has to be configured. Absent must not read as
      ;; "this session cost nothing", which a zeroed section would.
      (is (not (contains? (telemetry/turn-cost {:deltas [turn]}) :model))))

    (testing "totals across every received batch"
      (let [m (:model (telemetry/turn-cost {:deltas [turn otel]}))]
        (is (= 2 (:requests m)) (pr-str m))
        (is (= 12 (:input m)))
        (is (= 24 (:output m)))
        (is (= 10487 (:cache-read m)))
        (is (= 7559 (:cache-creation m)))
        (is (= (+ 12 24 10487 7559) (:tokens m)) "every token the window paid for")
        ;; compared in CENTS: 0.08 + 0.01 is not exactly 0.09 in a double, and
        ;; a test that asserts otherwise fails on arithmetic rather than on
        ;; behaviour
        (is (= 9 (Math/round (* 100.0 (:cost-usd m)))) (pr-str (:cost-usd m)))))

    (testing "CONTEXT is reported as a distribution, because its max is the cap"
      ;; a mean hides the thing that matters — approaching the context limit is
      ;; what forces a compaction, and one enormous request does that while an
      ;; average stays comfortable
      (let [m (:model (telemetry/turn-cost {:deltas [turn otel]}))]
        (is (= 17548 (:max (:context m))) (pr-str m))
        (is (some? (:p50 (:context m))) (pr-str m))))

    (testing "and it is WINDOWED like everything else in this fold"
      (let [m (:model (telemetry/turn-cost {:deltas [turn otel]} :since "d1"))]
        (is (= 2 (:requests m)) "batches after the since-point count"))
      (let [m (:model (telemetry/turn-cost {:deltas [turn otel]} :since "d2"))]
        (is (nil? m) "nothing after the last delta, so no model section at all")))))

(deftest turn-cost-given-the-per-call-rows-is-a-census-with-bytes-out
  ;; `:tools` from the turn-end ring is a LOWER BOUND — five tools per turn,
  ;; ms only. A tool that is never a turn's top five is invisible however often
  ;; it runs, and nothing said what a tool SENT, which is what the context
  ;; costs. The per-call measurement rows are the census: every call, its
  ;; wall, and the characters on the wire.
  (let [store (store/empty-store)
        rows  [{:tool "query_slice" :ms 10 :chars 4000 :refused? false}
               {:tool "query_slice" :ms 12 :chars 6000 :refused? false}
               {:tool "edit_add_form" :ms 3000 :chars 300 :refused? false}
               {:tool "edit_add_form" :ms 5 :chars 80 :refused? true}]
        t     (telemetry/turn-cost store :tool-calls rows)]
    (testing "every tool is present, with calls, ms and chars summed"
      (let [by (into {} (map (juxt :tool identity)) (:tools t))]
        (is (= {:tool "query_slice" :calls 2 :ms 22 :avg-ms 11 :chars 10000}
               (get by "query_slice")) (pr-str (:tools t)))
        (is (= 2 (:calls (get by "edit_add_form"))))
        (is (= 380 (:chars (get by "edit_add_form"))))))
    (testing "and the ranking is by what a tool SENT, since that is what every later request re-reads"
      (is (= "query_slice" (:tool (first (:tools t))))))
    (testing "the totals are carried, and say they are a census"
      (is (= 4 (get-in t [:calls :total])))
      (is (= 10380 (get-in t [:calls :chars])))
      (is (= :census (get-in t [:calls :basis])))))
  (testing "without the rows the fold is what it was — a lower bound, and it says so"
    (is (= :turn-top (get-in (telemetry/turn-cost (store/empty-store)) [:calls :basis])))))

(deftest refusals-are-counted-by-what-they-SAY-and-by-whether-the-agent-retried
  ;; s19: grouping refusals by TOOL produced two wrong levers in one
  ;; session — a 462 that was a since-fixed classifier's artefact, and a 282
  ;; that was every refusal of that tool rather than the message it was
  ;; attributed to. The message is the class.
  (let [call (fn [t s e & [err]]
               (cond-> {:tool t :start s :end e}
                 err (assoc :refused? true :error err)))]
    (testing "the SHAPE collapses the particulars, so one class counts as one class"
      (is (= "unknown argument :… for query_slice"
             (telemetry/refusal-shape
              "error: unknown argument :targets for query_slice — a mistyped or unsupported argument is refused")))
      (is (= (telemetry/refusal-shape "error: unknown argument :full for query_slice — accepted: :ns")
             (telemetry/refusal-shape "error: unknown argument :targets for query_slice — accepted: :ns"))
          "same class, different particulars"))
    (testing "a compile failure is keyed on its REASON, not its preamble"
      (is (= "No such namespace: …"
             (telemetry/refusal-shape
              "{:error \"form failed to compile: Syntax error compiling\nNo such namespace: web\n class x\"}")))
      (is (= (telemetry/refusal-shape "{:error \"form failed to compile: Syntax error compiling\nNo such namespace: web\"}")
             (telemetry/refusal-shape "{:error \"form failed to compile: Syntax error compiling\nNo such namespace: db\"}"))))
    (testing "an ordinary message keeps its head, and a blank one has no shape"
      (is (= "subform not found in …" (telemetry/refusal-shape "subform not found in quote-breakdown — :source-now is")))
      (is (nil? (telemetry/refusal-shape "")))
      (is (nil? (telemetry/refusal-shape nil))))
    (testing "the fold reports the shapes and the RETRIES — a refusal the agent answered with the same tool"
      (let [t (telemetry/call-timing
               [(call "edit" 0 10 "error: unknown argument :ns for change — accepted: :impl")
                (call "edit" 20 30 "error: unknown argument :name for change — accepted: :impl")
                (call "read" 40 50)
                (call "edit" 60 70)])]
        (is (= 2 (get-in t [:refused :count])) (pr-str (:refused t)))
        (is (= [{:shape "unknown argument :… for change" :n 2}]
               (get-in t [:refused :by-shape])) (pr-str (:refused t)))
        (is (= 1 (get-in t [:refused :retried]))
            (str "the first refusal was followed by the same tool; the second was not: "
                 (pr-str (:refused t))))))))

(deftest what-an-answer-COSTS-is-its-size-times-how-long-it-rides
  ;; s19 tracking: a long session's bill is context rent — 1.93B cache-read
  ;; tokens against 4.2M output on this store — and nothing folded it, though
  ;; the ring has carried :chars per call since reads were found to be 52%
  ;; of the bill. Size alone cannot rank: the same 47k answer is nearly free
  ;; as a turn's last call and the most expensive thing in it as the first.
  (let [call (fn [t s e chars] {:tool t :start s :end e :chars chars})]
    (testing "an answer is charged for every call that follows it"
      (let [r (:rent (telemetry/call-timing
                      [(call "read" 0 10 100)     ; two calls follow → 200
                       (call "edit" 20 30 10)     ; one follows        → 10
                       (call "done" 40 50 1000)]))] ; none              → 0
        (is (= 210 (:carried-chars r)) (pr-str r))
        (is (= [{:tool "read" :carried 200} {:tool "edit" :carried 10}]
               (remove #(zero? (:carried %)) (:by-tool r)))
            (pr-str r))))
    (testing "the LAST answer is free however large, and the first is not"
      (let [late  (:carried-chars (:rent (telemetry/call-timing
                                          [(call "a" 0 1 1) (call "big" 2 3 50000)])))
            early (:carried-chars (:rent (telemetry/call-timing
                                          [(call "big" 0 1 50000) (call "a" 2 3 1)])))]
        (is (= 1 late) "the small first answer rode once")
        (is (= 50000 early) "the big first answer rode once — and that is the whole ranking")))
    (testing "a turn with one call has nothing riding, and says so rather than nothing"
      (is (= 0 (:carried-chars (:rent (telemetry/call-timing [(call "read" 0 1 999)]))))))))

(deftest a-window-that-predates-a-measurement-says-so-instead-of-reporting-zero
  ;; s19: :retried, :by-shape and :rent land on turns closed after they
  ;; shipped, so any window reaching back past that has turns without them.
  ;; Reported as 0 and [] they read as "measured, and there was none" — the
  ;; conflation D-surface-honesty forbids, and precisely how a since-fixed
  ;; refusal classifier's frozen rows became a performance lever earlier
  ;; today. Unmeasured has to look different from none.
  (let [old   {:op :turn-end :at 1000
               :timing {:calls 2 :slopp-ms 10 :outside-ms 5 :idle-ms 0
                        :elapsed-ms 15 :top [{:tool "read" :n 2 :ms 10}]
                        :refused {:count 0 :pct 0 :by-tool []}}}
        fresh {:op :turn-end :at 2000
               :timing {:calls 2 :slopp-ms 10 :outside-ms 5 :idle-ms 0
                        :elapsed-ms 15 :top [{:tool "read" :n 2 :ms 10}]
                        :refused {:count 1 :pct 50 :by-tool [{:tool "edit" :n 1}]
                                  :retried 1 :by-shape [{:shape "unknown argument" :n 1}]}
                        :rent {:carried-chars 400 :by-tool [{:tool "read" :carried 400}]}}}]
    (testing "a window of turns that predate the measurement reports it as unmeasured"
      (let [r (telemetry/turn-cost {:deltas [old]})]
        (is (nil? (:carried-chars (:rent r))) (pr-str (:rent r)))
        (is (re-find #"unmeasured|not recorded" (str (:note (:rent r)))) (pr-str (:rent r)))
        (is (nil? (:by-shape (:refused r))) (pr-str (:refused r)))))
    (testing "and once a turn carries them, the numbers are the answer"
      (let [r (telemetry/turn-cost {:deltas [old fresh]})]
        (is (= 400 (:carried-chars (:rent r))) (pr-str (:rent r)))
        (is (= 1 (:retried (:refused r))) (pr-str (:refused r)))
        (is (= [{:shape "unknown argument" :n 1}] (:by-shape (:refused r))) (pr-str (:refused r)))))))

(deftest the-cost-door-forwards-the-census-and-can-split-by-commit-point
  ;; TWO findings in one pin. First: the wire passes :tool-calls (the
  ;; per-call census, with the chars each answer put on the wire) and
  ;; query-turn-cost destructured only [since otel] — so it was dropped, and
  ;; every reading of this store's tools came from the turn-top path the
  ;; descriptor itself calls a LOWER BOUND (five tools per turn). Second:
  ;; costs keyed to commit-points, so a wave's effect is a slope rather than a
  ;; hand comparison across recorded rows.
  (let [turn  (fn [at ms] {:op :turn-end :at at
                           :timing {:calls 1 :slopp-ms ms :outside-ms 1 :idle-ms 0
                                    :elapsed-ms (inc ms) :top [{:tool "read" :n 1 :ms ms}]
                                    :refused {:count 0 :pct 0 :by-tool []}}})
        sess  (atom {:store {:deltas [(turn 100 10)
                                      {:op :commit :id "dM1" :description "first wave" :at "2026-09-01 10:00"}
                                      (turn 200 20)
                                      (turn 300 30)
                                      {:op :commit :id "dM2" :description "second wave" :at "2026-09-02 10:00"}
                                      (turn 400 40)]}})]
    (testing "the per-call census reaches the fold when the caller passes it"
      (let [r (query/query-turn-cost sess :tool-calls [{:tool "read" :ms 5 :chars 100}
                                                       {:tool "edit" :ms 7 :chars 20}])]
        (is (= :census (get-in r [:calls :basis])) (pr-str (:calls r)))
        (is (= 2 (get-in r [:calls :total])) (pr-str (:calls r)))
        (is (= 120 (get-in r [:calls :chars])) (pr-str (:calls r)))))
    (testing "without it the fold still answers, and SAYS which basis it used"
      (is (= :turn-top (get-in (query/query-turn-cost sess) [:calls :basis]))))
    (testing "by commit-point: one row per segment, newest first, each naming the commit-point it follows"
      (let [r (query/query-turn-cost sess :by "commit-point")]
        (is (= :commit-point (:by r)) (pr-str r))
        (is (= ["dM2" "dM1"] (mapv :commit (:rows r))) (pr-str (:rows r)))
        (is (= [1 2] (mapv :turns (:rows r))) "the segment AFTER each commit-point")
        (is (= 40 (get-in (first (:rows r)) [:wall :slopp-ms])) (pr-str (:rows r)))))))

(deftest a-rate-is-taken-against-the-population-it-came-FROM
  ;; s19: connecting the per-call census made :calls :total the census count
  ;; while :refused :count still came from turn timings — and the percentage
  ;; divided one population by the other, reporting 57% where the real rate
  ;; was 9%. Two populations in one map is exactly how a measurement lies
  ;; while every number in it is individually true.
  (let [turn (fn [calls refused]
               {:op :turn-end :at 1
                :timing {:calls calls :slopp-ms 1 :outside-ms 1 :idle-ms 0 :elapsed-ms 2
                         :top [] :refused {:count refused :pct 0 :by-tool []}}})
        ;; 100 calls seen by turns, 10 refused; the census saw 1000 calls
        store {:deltas [(turn 100 10)]}
        census (repeat 1000 {:tool "read" :ms 1 :chars 10})]
    (testing "the refusal rate is against the turns' own calls, not the census total"
      (let [r (telemetry/turn-cost store :tool-calls census)]
        (is (= 1000 (get-in r [:calls :total])) "the census answers how many calls")
        (is (= :census (get-in r [:calls :basis])))
        (is (= 10 (get-in r [:refused :count])))
        (is (= 10 (get-in r [:refused :pct]))
            (str "10 of the 100 calls turns recorded — not 10 of 1000: "
                 (pr-str (:refused r))))
        (is (= :turn-timings (get-in r [:refused :basis])) (pr-str (:refused r)))))
    (testing "and with no census the two populations are the same one"
      (let [r (telemetry/turn-cost store)]
        (is (= 100 (get-in r [:calls :total])))
        (is (= 10 (get-in r [:refused :pct])))))))

(deftest a-family-call-is-ranked-under-its-op-not-its-family
  ;; s20: since the surface became fourteen families, the census recorded
  ;; :tool "read"/"edit"/"verify" — 237/293/175 rows on this store with no
  ;; op — so nothing in the store could rank query_source against
  ;; query_slice for any call made since s11. Every lever in the s20 plan
  ;; was ranked from a TRANSCRIPT; the store has to be able to do it.
  (let [c (fn [t op s e chars & [m]]
            (merge (cond-> {:tool t :start s :end e :chars chars} op (assoc :op op)) m))]
    (testing "call-timing names the op"
      (let [t (telemetry/call-timing [(c "read" "query_source" 0 30 100)
                                      (c "read" "query_slice" 40 50 50)
                                      (c "done" nil 60 65 10)])]
        (is (= ["read/query_source" "read/query_slice" "done"] (mapv :tool (:top t)))
            (pr-str (:top t)))))
    (testing "read-cost charges a re-fetch to the OP that minted the id"
      (let [r (telemetry/read-cost [(c "read" "query_source" 0 10 8000 {:trimmed? true :spooled "r1"})
                                    (c "read" "query_detail" 20 30 16000 {:detail-asked "r1"})])]
        (is (= 1 (:refetched r)) (pr-str r))
        (is (= {:tool "read/query_source" :n 1 :chars 8000 :trimmed 1 :refetched 1}
               (first (filter #(= "read/query_source" (:tool %)) (:by-tool r))))
            (pr-str (:by-tool r)))))
    (testing "the census carries the SEND side, per op"
      (let [r (telemetry/turn-cost {:deltas []}
                                   :tool-calls [{:tool "read" :op "query_source" :ms 5 :chars 100 :chars-in 40}
                                                {:tool "read" :op "query_source" :ms 5 :chars 100 :chars-in 60}])]
        (is (= [{:tool "read/query_source" :calls 2 :ms 10 :avg-ms 5 :chars 200 :chars-in 100}]
               (:tools r))
            (pr-str (:tools r)))))))

(deftest the-re-buy-rate-per-op-is-one-query
  ;; s20: read-cost has computed :trimmed and :refetched per call since it
  ;; shipped, on :read-cost deltas nothing folded. This is the number that
  ;; says whether a size gate is a saving or a tax — measured by hand off
  ;; one transcript, query_source's was 69% re-bought — and it is the
  ;; regression check for changing the gate.
  (let [rc (fn [rows] {:op :read-cost
                       :reads {:trimmed (reduce + (keep :trimmed rows))
                               :refetched (reduce + (keep :refetched rows))
                               :by-tool rows}})]
    (testing "folded from the read records in the window, keyed by op"
      (let [r (telemetry/turn-cost
               {:deltas [(rc [{:tool "read/query_source" :n 10 :chars 1 :trimmed 10 :refetched 7}
                              {:tool "read/query_search" :n 3 :chars 1 :trimmed 3 :refetched 0}])]})]
        (is (= 13 (get-in r [:refetched :trimmed])) (pr-str (:refetched r)))
        (is (= 7 (get-in r [:refetched :refetched])) (pr-str (:refetched r)))
        (is (= {:op "read/query_source" :trimmed 10 :rebought 7 :rate 0.7}
               (first (get-in r [:refetched :by-op])))
            (pr-str (:refetched r)))))
    (testing "a window with no read record says unmeasured, not zero"
      (let [r (telemetry/turn-cost {:deltas []})]
        (is (nil? (get-in r [:refetched :rate])))
        (is (re-find #"unmeasured" (str (get-in r [:refetched :note]))) (pr-str (:refetched r)))))))

(deftest cost-by-model-splits-the-model-block-by-the-name-each-request-records
  ;; The `:model` block folds every request together, so a session that ran a
  ;; cheap model for its greps and an expensive one for its reasoning reads
  ;; exactly like one that ran the expensive model throughout — and only one
  ;; of those has a lever in it. `model` is already an attribute on every
  ;; request, so the split needed no new measurement, only a fold that stops
  ;; throwing the name away.
  (let [otel {:op :otel :id "d1"
              :requests [{:model "claude-opus-5" :input 2 :output 4
                          :cache-read 100 :cache-creation 10 :context 116
                          :cost-usd 0.08}
                         {:model "claude-opus-5" :input 8 :output 6
                          :cache-read 200 :cache-creation 0 :context 214
                          :cost-usd 0.02}
                         {:model "claude-haiku-4-5" :input 1 :output 1
                          :cache-read 0 :cache-creation 0 :context 2
                          :cost-usd 0.001}]}
        r    (telemetry/cost-by-model {:deltas [otel]})
        rows (:rows r)]

    (is (= :model (:by r)))
    (is (= ["claude-opus-5" "claude-haiku-4-5"] (mapv :model rows))
        "dearest first — the row worth acting on is the expensive one")

    (testing "a row carries the same facts the single :model block does"
      (let [o (first rows)]
        (is (= 2 (:requests o)) (pr-str o))
        (is (= 10 (:input o)))
        (is (= 10 (:output o)))
        (is (= 300 (:cache-read o)))
        (is (= 10 (:cache-creation o)))
        (is (= (+ 10 10 300 10) (:tokens o)) "every token this model was paid for")
        ;; in CENTS: 0.08 + 0.02 is not exactly 0.10 in a double, and a test
        ;; asserting otherwise fails on arithmetic rather than on behaviour
        (is (= 10 (Math/round (* 100.0 (:cost-usd o)))) (pr-str (:cost-usd o)))
        (is (= 214 (:max (:context o)))
            "context stays a DISTRIBUTION per model — every request ships the whole conversation, so a sum counts the same tokens once per round trip")))

    (testing "a request with no model name rows under nil rather than joining a neighbour"
      ;; its tokens were paid for either way, and a name nobody sent is not a
      ;; name to invent
      (let [rs (:rows (telemetry/cost-by-model
                       {:deltas [{:op :otel :id "d1"
                                  :requests [{:input 5 :output 1 :cost-usd 0.5}]}]}))]
        (is (= [nil] (mapv :model rs)))
        (is (= 6 (:tokens (first rs))))))

    (testing "no telemetry is NO ROWS, never a zeroed one"
      ;; a zeroed row would say *this model cost nothing* where the truth is
      ;; *nobody was measuring*
      (is (= [] (:rows (telemetry/cost-by-model {:deltas []})))))

    (testing "handed-in batches count beside the journal's own :otel deltas"
      (is (= 2 (count (:rows (telemetry/cost-by-model
                              {:deltas []}
                              :otel [{:requests (:requests otel)}]))))))

    (testing "and it is WINDOWED by :since like the rest of the fold"
      (is (= [] (:rows (telemetry/cost-by-model {:deltas [otel]} :since "d1")))))))

(deftest cost-by-ask-attributes-requests-to-the-turn-they-were-made-in
  ;; Both halves of this join already existed and nothing read them together:
  ;; every request carries its own timestamp, and every ask brackets itself in
  ;; the journal with turn-begin/turn-end. "What did that ask cost" was being
  ;; answered by eye off a whole-session total.
  (let [nanos (fn [ms] (str (* 1000000 ms)))  ; as the CLI sends them: int64 as a string
        req   (fn [ms prompt cost]
                {:at-ns (nanos ms) :prompt prompt :model "claude-opus-5"
                 :input 1 :output 1 :cache-read 8 :cache-creation 0
                 :context 10 :cost-usd cost})
        store {:deltas [{:op :turn-begin :id "d1" :at 1000 :agent "a" :intent "first ask"}
                        {:op :turn-end   :id "d2" :at 2000 :agent "a"}
                        {:op :turn-begin :id "d3" :at 3000 :agent "a" :intent "second ask"}
                        {:op :turn-end   :id "d4" :at 4000 :agent "a"}]}
        otel  [{:requests [(req 1500 "p1" 0.1)
                           (req 1600 "p1" 0.2)
                           (req 3500 "p2" 0.5)
                           (req 2500 "p9" 0.9)               ; between the brackets
                           {:prompt "p0" :cost-usd 1.0}]}]   ; no timestamp at all
        r     (telemetry/cost-by-ask store :otel otel)
        rows  (:rows r)]

    (is (= :ask (:by r)))
    (is (= ["d3" "d1"] (mapv :ask rows)) "newest ask first")

    (testing "a row says what ONE ask cost, and names the ask"
      (let [a (second rows)]
        (is (= "first ask" (:intent a)) "the verbatim ask, so the row is readable")
        (is (= 1000 (:ms a)) "and how long the bracket was open")
        (is (= 2 (:requests a)) (pr-str a))
        (is (= 30 (Math/round (* 100.0 (:cost-usd (:model a))))) (pr-str (:model a)))
        (is (= 20 (:tokens (:model a))) "the model block, folded over this ask's requests only")))

    (testing "the prompt id is carried as EVIDENCE — the clock is the join"
      ;; the journal has no prompt id to match against, so the bracket places
      ;; the request and the id corroborates it. One id per row is the reading
      ;; to expect; two mean the bracket spans more than one harness prompt,
      ;; which is worth seeing rather than worth hiding.
      (is (= ["p1"] (:prompts (second rows))))
      (is (= ["p2"] (:prompts (first rows)))))

    (testing "requests in NO bracket are reported, not distributed"
      ;; between one ask ending and the next beginning: a compaction, a
      ;; background summary. They were paid for, and handing them to a
      ;; neighbouring ask would be a guess dressed as a measurement.
      (is (= 1 (:requests (:unattributed r))) (pr-str (:unattributed r)))
      (is (= ["p9"] (:prompts (:unattributed r))))
      (is (= 90 (Math/round (* 100.0 (:cost-usd (:model (:unattributed r))))))))

    (testing "a request carrying no timestamp cannot be placed by any rule, and says so"
      (is (= 1 (:undated r))))

    (testing "an ask whose turn-end never landed runs to the NEXT ask's opening"
      ;; turn-begin! supersedes an unclosed turn for exactly this reason, so
      ;; the next ask's opening is the honest end of this one
      (let [r2 (telemetry/cost-by-ask
                {:deltas [{:op :turn-begin :id "d1" :at 1000 :agent "a" :intent "unclosed"}
                          {:op :turn-begin :id "d3" :at 3000 :agent "a" :intent "next"}]}
                :otel [{:requests [(req 2500 "p9" 0.9) (req 9999 "p3" 0.1)]}])
            [newest oldest] (:rows r2)]
        (is (= 1 (:requests oldest)) "the stray now belongs to the ask it was made in")
        (is (not (contains? oldest :ms)) "no turn-end, so no duration — absent, not zero")
        (is (= 1 (:requests newest)) "and the last ask runs on to now")
        (is (nil? (:unattributed r2)))))

    (testing "an ask with no telemetry carries NO model block, which is not zero"
      (let [a (first (:rows (telemetry/cost-by-ask store)))]
        (is (= 0 (:requests a)))
        (is (not (contains? a :model)))))))
