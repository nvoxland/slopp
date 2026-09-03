(ns slopp.read.telemetry
  "slopp measuring ITSELF, by reading its own journal back.

  Everything here is a PURE FOLD over facts already recorded — rule firings
  and discharges from the delta log, a turn's call edges from the wire. There
  are no counters and no sampling: the journal is written for provenance
  anyway, so measurement costs nothing extra and cannot drift from what
  actually happened.

  The house rule that shapes the whole namespace: a number here must be one
  someone can ACT on, and it must not conflate \"measured, and the answer was
  zero\" with \"never measured\". So folds return nil rather than a zeroed
  record, and a clean result still carries its empty buckets."
  (:require [rewrite-clj.node :as n]
            [slopp.rules.catalog :as catalog] [clojure.string :as str]))

(defn- escape-markers
  "Store-wide counts of the discharge markers agents add to opt OUT of the
   analyzer / gates — the write-side friction proxy. `^:unsafe`/`^:reads` ride the
   form; `^:unused-ok` rides the defined name."
  [store]
  (reduce
   (fn [acc [_ns {:keys [elements]}]]
     (reduce (fn [acc e]
               (if-let [node (:node e)]
                 (let [s  (try (n/sexpr node) (catch Exception _ nil))
                       fm (meta s)
                       nm (when (seq? s) (meta (second s)))]
                   (cond-> acc
                     (:unsafe fm)    (update :unsafe inc)
                     (:reads fm)     (update :reads inc)
                     (:unused-ok nm) (update :unused-ok inc)))
                 acc))
             acc elements))
   {:unsafe 0 :reads 0 :unused-ok 0}
   (:namespaces store)))

(defn rule-telemetry
  "Fire-rate + discharge signal for the D9 rules, computed READ-ONLY over the delta
   log — no new instrumentation: the log already records every done's `:findings`
   and every escape marker. Optional `:since` (a delta id) windows to deltas AFTER
   it (e.g. a commit-point `:target`). Returns
   `{:window {:dones :since}
     :fire-rate {rule {:dones :instances :persisted :discharged}}
     :escape-markers {:unsafe :reads :unused-ok}
     :dials {:rules {…} :gates {…}}}`.
   `:persisted` = an instance flagged in MORE THAN ONE done (kept firing —
   un-discharged / ignored); `:discharged` = flagged exactly once (fixed or moved
   on). Metadata finding keys (`:test-status` etc.) aren't rules and aren't
   counted."
  [store & {:keys [since]}]
  (let [rule-keys (into #{:missing-doc :unused-public :stale-unused-ok}
                        ;; the CATALOG, not the executable registry: it is a
                        ;; verified superset (catalog-covers-every-registered-rule)
                        ;; and carries no check vars, so telemetry stays pure.
                        ;; Its extra write-gate keys are inert here — they simply
                        ;; never match a done finding.
                        (map :rule catalog/rule-catalog))
        deltas    (:deltas store)
        window    (if since (rest (drop-while #(not= since (:id %)) deltas)) deltas)
        dones     (filter #(= :done (:op %)) window)
        inst-of   (fn [x] (if (map? x) (or (:form x) (:used x) x) x))
        fires     (for [d dones
                        [k v] (:findings d)
                        :when (and (rule-keys k) (coll? v) (seq v))]
                    {:rule k :insts (mapv inst-of v)})]
    {:window {:dones (count dones) :since (or since :all)}
     :fire-rate
     (into (sorted-map)
           (for [[rule fs] (group-by :rule fires)
                 :let [freqs (frequencies (mapcat :insts fs))]]
             [rule {:dones      (count fs)
                    :instances  (reduce + (map (comp count :insts) fs))
                    :persisted  (count (filter #(> (val %) 1) freqs))
                    :discharged (count (filter #(= 1 (val %)) freqs))}]))
     :escape-markers (escape-markers store)
     :dials {:rules (get-in store [:config "rules" :values] {})
             :gates (get-in store [:config "gates" :values] {})}}))

(def idle-gap-ms
  "The between-call gap at or above which a turn is PAUSED rather than slow —
  five minutes.

  It is a judgement call and it is named here so it reads as one. The bound it
  has to clear: a single agent step (reasoning plus a handful of non-slopp
  tools) that legitimately belongs in `:outside-ms`. Measured on real turns
  those run seconds, while the gaps this is aimed at ran six to twelve HOURS —
  so the threshold sits two orders of magnitude clear of both, and nothing
  about the split is sensitive to its exact value."
  (* 5 60 1000))

(def refusal-samples
  "How many verbatim refusal messages a turn record carries. Enough to see the
  shapes, few enough that a bad turn cannot bloat its own delta."
  10)

(def refusal-sample-chars
  "How much of each refusal message a turn record keeps. slopp refusals are
  deliberately generous — `:source-now` hands back a whole form — so the
  leading sentence is the part that says WHY, and the rest is the recovery
  payload the agent already consumed."
  200)

(def ^:export read-flush-calls
  "How many recorded calls accumulate before the read ring is flushed to its
  own delta — two hundred.

  It is not a size limit. The record folds to per-TOOL rows, so a span of two
  hundred calls and a span of ten thousand produce nearly the same delta;
  what the threshold bounds is LOSS. The ring lives in the session, so
  everything in it dies with the process, and a flush is the only thing that
  makes a span durable.

  Two hundred is roughly a long working ask here and well inside a session
  that never rotates a turn at all. The number is a judgement call and the
  measurement is insensitive to it: rows are SUMMED by the ledger, so the
  width changes the resolution and not the totals.

  A UNIFORM width is the point, and it is why this is a count rather than a
  turn. Two stores with differently shaped turns — minutes here, hours in a
  consumer driven by background events — produced rows that could not be
  compared. Rows of two hundred calls are the same unit everywhere."
  200)

(defn- model-summary
  "The model side of a window: what its requests carried and what they cost.

  `:context` is a DISTRIBUTION rather than a total, and that is the only
  interesting choice here. Context does not accumulate — every request ships
  the whole conversation, so summing it counts the same tokens once per round
  trip and produces a number with no meaning. What matters is how CLOSE the
  largest got to the limit, because that is what forces a compaction, and a
  mean hides exactly that: one enormous request against a hundred small ones
  leaves the average comfortable.

  `:tokens` IS a sum, and legitimately: input, output and cache traffic are
  each paid per request."
  [rs]
  (let [sum (fn [k] (reduce + 0 (keep k rs)))
        ctx (vec (sort (keep :context rs)))]
    {:requests       (count rs)
     :input          (sum :input)
     :output         (sum :output)
     :cache-read     (sum :cache-read)
     :cache-creation (sum :cache-creation)
     :tokens         (+ (sum :input) (sum :output)
                        (sum :cache-read) (sum :cache-creation))
     :cost-usd       (sum :cost-usd)
     :context        (when (seq ctx)
                       {:p50 (nth ctx (quot (count ctx) 2))
                        :max (peek ctx)})}))

(defn ^:export refusal-shape
  "A refusal message reduced to its CLASS: the particulars collapsed, the
  sentence that identifies it kept. nil for an empty message.

  A NORMALIZATION, deliberately, not a taxonomy — quoted text, qualified
  names, keywords, numbers and trailing bare names become an ellipsis, and a
  compiler failure is keyed on its REASON line rather than the `failed to
  compile` preamble every one of them shares. A hand-written category table
  would be invented rather than derived and would only ever fit this store;
  this fits any store's refusals, including ones nobody has seen yet.

  Why it exists (s19): refusals were counted by TOOL, and a tool is not a
  class. Two performance 'levers' were read off that grouping in one session
  and both were wrong — one was a since-fixed classifier's artefact, the
  other was every refusal of a tool attributed to the single message that
  happened to be sampled."
  [error]
  (let [s (str/trim (str error))]
    (when (seq s)
      (let [lines (remove str/blank? (str/split s #"\n"))
            ;; a compiler failure's first line is boilerplate; its second is
            ;; the reason, which is the thing that differs between classes
            head  (if (and (re-find #"compil" (str (first lines))) (second lines))
                    (second lines)
                    (first lines))
            ;; cut to the head BEFORE collapsing particulars — the rules below
            ;; anchor at the end, and a trailing explanation moves that end
            head  (first (str/split (str head) #" — "))
            norm  (-> head
                      (str/replace #"^\{:error\s+\"" "")
                      (str/replace #"^error:\s*" "")
                      ;; the EDN wrapper's tail, when the reason IS the last line
                      (str/replace #"\"\s*\}?\s*$" "")
                      (str/replace #"\"[^\"]*\"" "…")
                      (str/replace #"\{[^}]*\}" "{…}")
                      (str/replace #"\b[\w.-]+/[\w.?!*<>=+$-]+" "…")
                      (str/replace #":[\w?!*<>=+-]+" ":…")
                      (str/replace #"\d+" "N")
                      ;; a trailing bare name is a particular too
                      (str/replace #":\s+[\w.$?!*-]+\s*$" ": …")
                      (str/replace #"\bin\s+[\w.$?!*<>=+-]+\s*$" "in …")
                      str/trim)]
        (not-empty (subs norm 0 (min (count norm) 60)))))))

(defn- tool-label
  "The name a call is RANKED under: `read/query_source` when the call came
  through a family with an op, the bare tool otherwise. Since the surface
  became fourteen families, a row that knew only the family could not rank
  `query_source` against `query_slice` at all (s20)."
  [{:keys [tool op]}]
  (if (seq (str op)) (str tool "/" op) (str tool)))

(defn ^:export read-cost
  "What a turn's answers COST to send, and whether withholding one saved
  anything — the pure fold over the same `calls` ring `call-timing` reads,
  using the response facts the wire records alongside each call: `:chars` on
  the wire, `:trimmed?` when the size gate cut the payload, `:stub?` when the
  knowledge differential withheld it, `:spooled` for the retrieval id either
  path minted, and `:detail-asked` for what a retrieval call went back for.

  Returns `{:chars :withheld :trimmed :stubbed :refetched :refetched-chars
  :refetched-elsewhere :refetch-rate :by-tool}`, or NIL when no call carries a
  size — the ring predates this record, and a zeroed total over unmeasured
  calls reads exactly like a measured zero.

  **The re-fetch is the number the tier was missing.** Reads are 52% of the
  token bill and got the least optimization; the one lever shipped on that
  tier is the size gate, and it was measured once, by hand, off a single
  transcript: an 8,367-char trimmed read plus a 21,676-char re-fetch, against
  21,676 for sending it whole. A withholding that is opened anyway is a NET
  LOSS, and until this existed nothing could tell that case from the one where
  the agent never came back. `:refetch-rate` is over what was WITHHELD, so it
  is nil when nothing was — there is no rate, rather than a rate of none.

  A re-fetch is charged to the tool that MINTED the id, not to the retrieval
  call that spent the characters. Charged the other way the ledger ranks
  `query_detail` as the expensive tool and leaves every trimming tool looking
  clean, which inverts the thing being asked.

  `:refetched-elsewhere` counts retrievals naming an id no call in this turn
  minted — a trim in one ask opened in the next. Attributing those to nobody
  would let a turn boundary read as evidence the trim paid.

  Rows are not capped. A turn touches a handful of tools and each row is a few
  dozen characters, well inside what `refusal-samples` already allows onto a
  delta; a cap here would silently shrink the population the ledger folds."
  [calls]
  (when (seq (filter :chars calls))
    (let [sized    (filter :chars calls)
          minted   (into {} (keep (fn [{:keys [spooled] :as c}]
                                    (when spooled [spooled (tool-label c)]))
                                  calls))
          fetches  (filter :detail-asked calls)
          hits     (filter #(minted (:detail-asked %)) fetches)
          re-by    (frequencies (map #(minted (:detail-asked %)) hits))
          withheld (count (filter #(or (:trimmed? %) (:stub? %)) calls))
          rows     (->> (group-by tool-label sized)
                        (map (fn [[t cs]]
                               (let [n-tr (count (filter :trimmed? cs))
                                     n-st (count (filter :stub? cs))
                                     n-re (get re-by t 0)]
                                 (cond-> {:tool t :n (count cs)
                                          :chars (reduce + 0 (map :chars cs))}
                                   (pos? n-tr) (assoc :trimmed n-tr)
                                   (pos? n-st) (assoc :stubbed n-st)
                                   (pos? n-re) (assoc :refetched n-re)))))
                        (sort-by (juxt (comp - :chars) :tool))
                        vec)]
      {:calls               (count sized)
       :chars               (reduce + 0 (map :chars sized))
       :withheld            withheld
       :trimmed             (count (filter :trimmed? calls))
       :stubbed             (count (filter :stub? calls))
       :refetched           (count hits)
       :refetched-chars     (reduce + 0 (keep :chars hits))
       :refetched-elsewhere (- (count fetches) (count hits))
       :refetch-rate        (when (pos? withheld)
                              (double (/ (count hits) withheld)))
       :by-tool             rows})))

(defn ^:export call-timing
  "A turn's wall clock split into the part slopp spent working, the part it
  did not, and the part nobody was there for — the pure fold over `calls`,
  each `{:tool :start :end}` in epoch ms as the wire recorded them.

  Returns `{:calls :slopp-ms :outside-ms :idle-ms :elapsed-ms :slopp-share
  :top :refused}`, or NIL when nothing was called: a zeroed record would read
  as \"measured, and the answer was nothing\", which is the conflation
  D-surface-honesty forbids.

  **`:outside-ms` is not \"thinking time\".** It is the gap between one answer
  going out and the next call arriving: agent reasoning, every non-slopp tool
  (file reads, shell, subagents), and the harness, none of which the server
  can tell apart. Naming it for what it MEASURES rather than what we suspect
  it contains is the point — and it is the number that was missing. Measured
  over one real session before this existed: 1,703s elapsed against 390s of
  recorded verification, so 78% of the wall clock had no producer at all.
  P7's standing complaint is exactly this: the cost of leaving slopp lands
  where no slopp metric sees it.

  **`:idle-ms` is the session nobody was in.** A turn rotates on the
  WRITE-tool gate, so a read-only ask folds into the next writing one and a
  turn can straddle a human going to bed. Reading the first nine real records
  found exactly that: 46 calls, 224s of work, 45,501s elapsed, `:slopp-share
  \"0%\"` — a true division and a false statement, since slopp was most of the
  time anyone was actually working. Gaps of `idle-gap-ms` or more are counted
  here instead, and `:slopp-share` is taken against ACTIVE elapsed
  (`:elapsed-ms` minus `:idle-ms`). The three-way split stays exhaustive; what
  changes is that the two kinds of not-working are no longer one number.

  `:top` is by total cost, largest first, aggregated per tool — the tool that
  cost the most may be the one called a hundred times cheaply, and a per-call
  median hides that."
  [calls]
  (when (seq calls)
    (let [in    (reduce + 0 (map #(- (:end %) (:start %)) calls))
          span  (- (:end (last calls)) (:start (first calls)))
          idle  (->> (map (fn [a b] (- (:start b) (:end a))) calls (rest calls))
                     (filter #(>= % idle-gap-ms))
                     (reduce + 0))
          live  (max 1 (- span idle))

          by    (->> (group-by tool-label calls)
                     (map (fn [[t cs]] {:tool t :n (count cs)
                                        :ms (reduce + 0 (map #(- (:end %) (:start %)) cs))}))
                     (sort-by (juxt (comp - :ms) :tool))
                     vec)]
      {:calls      (count calls)
       :slopp-ms   in
       :outside-ms (- span in idle)
       :idle-ms    idle
       :elapsed-ms span
       :slopp-share (str (int (* 100 (/ in (double live)))) "%")
       :top        (vec (take 5 by))
       ;; CONTEXT RENT: what an answer COSTS rather than what it spent — its
       ;; size times the number of calls that follow it, because every one of
       ;; those re-reads it. The ring has carried :chars since reads were
       ;; measured at 52% of the bill; nothing folded it, so the most
       ;; expensive event in a session was invisible to every metric here (a
       ;; 47k read early in a long turn outweighs a hundred small ones).
       ;;
       ;; A within-turn LOWER BOUND. The payload keeps riding after the turn
       ;; ends, until a compaction the server never sees — so this undercounts
       ;; by construction, which is the safe direction for a number that
       ;; argues for making answers smaller.
       :rent       (let [n    (count calls)
                         rows (reduce (fn [m [t v]] (update m t (fnil + 0) v))
                                      {}
                                      (map-indexed
                                       (fn [i c] [(:tool c) (* (long (or (:chars c) 0))
                                                               (- n 1 i))])
                                       calls))]
                     {:carried-chars (reduce + 0 (vals rows))
                      :by-tool (->> rows
                                    (map (fn [[t v]] {:tool t :carried v}))
                                    (sort-by (juxt (comp - :carried) :tool))
                                    (take 5)
                                    vec)})
       ;; REFUSED calls — a malformed match, a lint error in the form being
       ;; written, an arity break. Each is a whole round trip that produced
       ;; nothing, and they live in the 78% of wall clock spent outside slopp,
       ;; where nothing had ever counted them. Always present, zero when
       ;; clean: an absent key would read as unmeasured.
       ;;
       ;; `:samples` carries what they SAID, bounded and truncated because a
       ;; refusal can hand back a whole form and this rides on a delta
       ;; forever. `:by-shape` is the classification those samples could only
       ;; hint at, done MECHANICALLY ([[refusal-shape]] normalizes rather
       ;; than categorizes) — because a TOOL is not a class, and reading
       ;; these by tool produced two wrong performance levers in one session
       ;; (s19). `:retried` counts the refusals the agent answered by calling
       ;; the same tool again: the retry, not the refusal, is what a fix
       ;; deletes.
       :refused    (let [r (filter :refused? calls)
                         retried (count (filter (fn [[a b]] (and (:refused? a)
                                                                (= (:tool a) (:tool b))))
                                                (map vector calls (rest calls))))]
                     {:count (count r)
                      :pct   (int (* 100 (/ (count r) (double (count calls)))))
                      :retried retried
                      :by-tool (vec (sort-by (juxt (comp - :n) :tool)
                                             (map (fn [[t cs]] {:tool t :n (count cs)})
                                                  (group-by tool-label r))))
                      :by-shape (->> r
                                     (keep (comp refusal-shape :error))
                                     frequencies
                                     (map (fn [[s n]] {:shape s :n n}))
                                     (sort-by (juxt (comp - :n) :shape))
                                     vec)
                      :samples (->> r
                                    (keep (fn [{:keys [tool error]}]
                                            (when error
                                              (let [s (str error)]
                                                {:tool  tool
                                                 :error (subs s 0 (min (count s)
                                                                       refusal-sample-chars))}))))
                                    (take refusal-samples)
                                    vec)})})))

(defn ^:export turn-cost
  "Where this store's wall clock went, folded READ-ONLY over the delta log —
  no new instrumentation: `call-timing` has been writing `:timing` onto every
  `:turn-end` since it shipped. Optional `:since` (a delta id) windows to
  turns AFTER it.

  Returns `{:window :wall :calls :refused :tools :repeats}`, plus `:model`
  when the harness's telemetry has been received.

  **The three-way split is the point, and it is exhaustive.** `:slopp-ms` is
  time inside a tool, `:idle-ms` is the session nobody was in, and
  `:outside-ms` is everything else — agent reasoning, every non-slopp tool,
  the harness — which the server cannot tell apart and does not pretend to.
  `:slopp-share` is taken against ACTIVE time (elapsed minus idle), because a
  share against elapsed makes a human going to bed look like time slopp
  failed to use.

  **`:model` is the half slopp cannot measure about itself** — tokens, cost,
  and how full the conversation was — and it is here rather than in a separate
  call because the two are only useful together: a session slow from context
  size and one slow from whole-store checks look identical in wall time alone.
  It is ABSENT when no `:otel` delta is in the window. Telemetry is opt-in, so
  a zeroed section would say *this window cost nothing* where the truth is
  *nobody was measuring*.

  `:repeats` names a tool run more than once inside ONE ask, which is the
  question a per-tool total cannot answer: a hundred cheap calls and two
  expensive ones look alike in a sum, and only one of them is waste. It
  exists because a first reading of this store found 114 of 320 `full_check`
  runs were repeats within a single turn — the same whole-store question
  asked twice, at about four minutes each.

  Ranked by `:extra-ms`, the cost attributable to the runs beyond the first,
  and NOT filtered. Two reads in one ask is ordinary and two whole-store
  checks is four minutes wasted; a cut-off that separated them would be a
  number nobody measured, so the order carries the judgement and the cheap
  repeats sink to the bottom where they cost the reader nothing.

  **`:tools` stands on one of two bases, and `:calls :basis` names it.**
  Given `:tool-calls` — the per-call `tool-call` measurement rows, one per
  call with `:tool :ms :chars :refused?` — it is a CENSUS: every tool, every
  call, and `:chars`, the characters it put on the wire, which is what every
  later request re-reads and so what a tool COSTS rather than what it spent.
  Ranked by `:chars`. Without them it is the `:turn-top` fold — the five
  costliest tools per turn, ms only — and a LOWER BOUND: a tool never in a
  turn's top five contributes nothing however often it ran. That biases
  toward expensive tools, which is the direction the old fold is read in, so
  it is reported rather than corrected; but a total there is not a census and
  a cheap tool's absence is not evidence it was not called.

  A turn with no `:timing` is ABSENT rather than zero: nothing was measured,
  which is a different fact from nothing having been spent."
  [store & {:keys [since otel tool-calls]}]
  (let [deltas  (:deltas store)
        window  (if since (rest (drop-while #(not= since (:id %)) deltas)) deltas)
        ts      (keep :timing window)
        sum     (fn [k] (reduce + 0 (keep k ts)))
        elapsed (sum :elapsed-ms)
        idle    (sum :idle-ms)
        active  (- elapsed idle)
        in      (sum :slopp-ms)
        census? (some? tool-calls)
        calls   (if census? (count tool-calls) (sum :calls))
        refused (sum (comp :count :refused))
        tally   (fn [rows key-fn val-fn]
                  (reduce (fn [m r] (update m (key-fn r) (fnil + 0) (val-fn r)))
                          {} rows))
        ;; Telemetry lives in the `measurements` table now — writing it as a
        ;; delta moved the head every few seconds and made the store
        ;; unwritable. `otel` is those rows, read by the caller because this
        ;; fold is pure over a store VALUE and a table is not in one.
        ;;
        ;; The journal is still read for it, and not out of caution: real
        ;; `:otel` deltas were written during the hour before the move, and
        ;; dropping them would silently shorten the history of the very
        ;; measurement this section reports.
        model   (into (vec (mapcat :requests (filter #(= :otel (:op %)) window)))
                      (mapcat :requests otel))]
    (cond->
     {:window  {:turns (count ts) :since (or since :all)}
      :wall    {:elapsed-ms elapsed
                :idle-ms    idle
                :active-ms  active
                :slopp-ms   in
                :outside-ms (sum :outside-ms)
                :slopp-share (str (int (* 100 (/ in (double (max 1 active))))) "%")}
      :calls   (cond-> {:total calls :basis (if census? :census :turn-top)}
                 census? (assoc :chars (reduce + 0 (keep :chars tool-calls))))
      :refused (let [classified? (some #(contains? (:refused %) :by-shape) ts)
                     ;; AGAINST THE TURNS' OWN CALLS. The census counts every
                     ;; call ever recorded while these refusals come from turn
                     ;; timings — dividing one by the other reported 57% on a
                     ;; store whose rate is 9% (s19). A rate belongs to the
                     ;; population its numerator came from.
                     turn-calls (sum :calls)]
                 (cond-> {:count   refused
                          :basis   :turn-timings
                          :pct     (int (* 100 (/ refused (double (max 1 turn-calls)))))
                          :by-tool (->> (mapcat (comp :by-tool :refused) ts)
                                        (#(tally % :tool :n))
                                        (map (fn [[t n]] {:tool t :n n}))
                                        (sort-by (juxt (comp - :n) :tool))
                                        vec)}
                   ;; BY WHAT THEY SAID: a tool is not a class, and grouping
                   ;; by tool produced two wrong levers in one session (s19).
                   ;; Present only when some turn in the window RECORDED it —
                   ;; an empty list would read as \"classified, and there were
                   ;; no classes\", which is how frozen rows from a since-fixed
                   ;; classifier became a lever in the first place.
                   classified?
                   (assoc :retried  (sum (comp :retried :refused))
                          :by-shape (->> (mapcat (comp :by-shape :refused) ts)
                                         (#(tally % :shape :n))
                                         (map (fn [[s n]] {:shape s :n n}))
                                         (sort-by (juxt (comp - :n) :shape))
                                         vec))

                   (not classified?)
                   (assoc :note (str "unmeasured in this window, not zero: refusal"
                                     " shapes and retries are recorded on turns"
                                     " closed after they shipped. Window with"
                                     " {since <a later delta>} to read them."))))
      ;; CONTEXT RENT, the half wall time cannot see: what earlier answers
      ;; keep costing every request after them. A within-turn lower bound
      ;; (see call-timing) — and on this store the model side reads 1.93B
      ;; cached tokens against 4.2M written, so this is where a long
      ;; session's bill actually is.
      :rent    (if (some #(contains? % :rent) ts)
                 {:carried-chars (sum (comp :carried-chars :rent))
                  :by-tool (->> (mapcat (comp :by-tool :rent) ts)
                                (#(tally % :tool :carried))
                                (map (fn [[t v]] {:tool t :carried v}))
                                (sort-by (juxt (comp - :carried) :tool))
                                (take 8)
                                vec)}
                 {:note (str "unmeasured in this window, not zero: context rent"
                             " is recorded on turns closed after it shipped."
                             " Window with {since <a later delta>} to read it.")})
      :tools   (if census?
                 ;; ranked under the OP (tool-label): a row that knows only
                 ;; the family cannot rank query_source against query_slice.
                 ;; :chars-in is the SEND side — present only when some row
                 ;; measured it, since a zero would read as measured
                 (let [ms  (tally tool-calls tool-label #(or (:ms %) 0))
                       n   (tally tool-calls tool-label (constantly 1))
                       ch  (tally tool-calls tool-label #(or (:chars %) 0))
                       ci  (tally tool-calls tool-label #(or (:chars-in %) 0))
                       in? (some :chars-in tool-calls)]
                   (->> (keys n)
                        (map (fn [t]
                               (cond-> {:tool t :calls (n t) :ms (ms t)
                                        :avg-ms (long (/ (ms t) (max 1 (n t))))
                                        :chars (ch t)}
                                 in? (assoc :chars-in (ci t)))))
                        (sort-by (juxt (comp - :chars) :tool))
                        vec))
                 (let [rows (mapcat :top ts)
                       ms   (tally rows :tool :ms)
                       n    (tally rows :tool :n)]
                   (->> (keys ms)
                        (map (fn [t]
                               {:tool t :calls (n t) :ms (ms t)
                                :avg-ms (long (/ (ms t) (max 1 (n t))))}))
                        (sort-by (juxt (comp - :ms) :tool))
                        vec)))
      ;; THE RE-BUY RATE, per op — the number that says whether a size gate
      ;; is a saving or a tax. read-cost has computed it per call since it
      ;; shipped, onto :read-cost deltas nothing folded; measured by hand
      ;; off one transcript, query_source's trims were re-bought 69% of the
      ;; time, each re-buy a whole model request at p50 485k context (s20).
      :refetched (let [rs (keep :reads (filter #(= :read-cost (:op %)) window))]
                   (if (empty? rs)
                     {:note (str "unmeasured in this window, not zero: read records"
                                 " land on :read-cost deltas when the read ring"
                                 " flushes on a write path; none flushed here.")}
                     (let [trimmed (reduce + 0 (keep :trimmed rs))
                           re      (reduce + 0 (keep :refetched rs))
                           by      (->> (mapcat :by-tool rs)
                                        (group-by :tool)
                                        (map (fn [[t xs]]
                                               (let [tr (reduce + 0 (keep :trimmed xs))
                                                     rf (reduce + 0 (keep :refetched xs))]
                                                 (cond-> {:op t :trimmed tr :rebought rf}
                                                   (pos? tr) (assoc :rate (double (/ rf tr)))))))
                                        (filter #(pos? (:trimmed %)))
                                        (sort-by (juxt (comp - :rebought) :op))
                                        vec)]
                       {:trimmed   trimmed
                        :refetched re
                        :rate      (when (pos? trimmed) (double (/ re trimmed)))
                        :by-op     by})))
      :repeats (let [rows (mapcat #(filter (fn [x] (> (:n x) 1)) (:top %)) ts)]
                 (->> (group-by :tool rows)
                      (map (fn [[t xs]]
                             {:tool     t
                              :turns    (count xs)
                              :extra    (reduce + 0 (map #(dec (:n %)) xs))
                              ;; what the repeats COST, which is the whole
                              ;; question: two reads in one ask is ordinary, two
                              ;; whole-store checks is four minutes of asking
                              ;; the same thing twice. Ranked rather than
                              ;; filtered — a threshold here would be a number
                              ;; nobody measured.
                              :extra-ms (long (reduce + 0 (map #(* (:ms %)
                                                                   (/ (dec (:n %))
                                                                      (double (:n %))))
                                                               xs)))}))
                      (sort-by (juxt (comp - :extra-ms) :tool))
                      vec))}
      (seq model) (assoc :model (model-summary model)))))

(defn ^:export cost-by-commit-point
  "[[turn-cost]] per COMMIT-POINT segment: one row for the work recorded after
  each `:commit`, newest first. `{:by :commit-point :rows […]}`.

  The series the per-window fold cannot be. A single window answers \"where
  did the clock go\", and the question a project actually asks is whether a
  landed change MOVED it — which was being answered by hand, comparing rows
  recorded in a file beside the store.

  Rows carry only what a turn records: wall, calls, refusals, rent. The
  model side (`:otel`) and the per-call census are deliberately NOT split
  across segments — both are read from a table by timestamp, and attributing
  them by delta position would be a guess dressed as a measurement."
  [store & {:keys [limit] :or {limit 20}}]
  (let [ds   (or (seq (:deltas store)) (:recent store))
        segs (->> ds
                  (reduce (fn [acc d]
                            (cond
                              (= :commit (:op d))
                              (conj acc {:commit (:id d)
                                         :description (:description d)
                                         :at (:at d)
                                         :deltas []})

                              (seq acc)
                              (update-in acc [(dec (count acc)) :deltas] conj d)

                              ;; work before the first commit-point belongs to no
                              ;; segment; it is not dropped silently — the row
                              ;; for it has no commit to name, so it is left out
                              ;; and the caller can window with :since instead
                              :else acc))
                          [])
                  reverse
                  (take limit))]
    {:by   :commit-point
     :rows (mapv (fn [{:keys [commit description at deltas]}]
                   (let [c (turn-cost {:deltas deltas})
                         d (str description)]
                     {:commit      commit
                      :description (subs d 0 (min 80 (count d)))
                      :at          at
                      :turns       (get-in c [:window :turns])
                      :calls       (get-in c [:calls :total])
                      :wall        (select-keys (:wall c) [:active-ms :slopp-ms :outside-ms :slopp-share])
                      :refused     (select-keys (:refused c) [:count :pct :retried])
                      :rent        (select-keys (:rent c) [:carried-chars])}))
                 segs)}))
