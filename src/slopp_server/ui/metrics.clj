(ns slopp-server.ui.metrics
  "Pure aggregation for the Dashboard: wire rows in, chart-ready numbers out. The Dashboard's panels are arithmetic over documents this app already fetches, and arithmetic is the half worth testing — so it lives here rather than inside the render, where an assertion would have to go through hiccup t…")

(defn store-shape
  "Namespace and form totals from `GET /api/namespaces` rows.

  A row with no `:forms` counts as ZERO rather than throwing. The wire fills it
  today, and a panel that died on one unfilled row would take the whole
  dashboard with it — the same choice [[slopp-server.ui.importance/weigh]] makes for a
  missing `:mass`."
  [rows]
  {:namespaces (count rows)
   :forms      (reduce + 0 (map #(or (:forms %) 0) rows))})

(defn by-day
  "Commit points bucketed by the DAY they were recorded, oldest first —
  `[{:day \"2026-08-30\" :count 2} …]`.

  The day is the first ten characters of the wire's `:at`, which arrives
  already formatted (`\"2026-08-30 11:45\"`). No date parsing and no date type:
  the document ships a rendered string on purpose, and reading the prefix is
  the whole operation.

  An `:at` too short to carry a date is DROPPED rather than bucketed under a
  truncated key — on a cadence chart a wrong day is worse than a missing one,
  because a bar in the wrong place reads as a fact.

  ISO dates sort lexically, which is why oldest-first needs no comparator."
  [commit-points]
  (->> commit-points
       (keep #(let [at (str (:at %))]
                (when (<= 10 (count at)) (subs at 0 10))))
       frequencies
       (sort-by key)
       (mapv (fn [[day n]] {:day day :count n}))))

(defn token-totals
  "What the asks in `rows` spent — `GET /api/cost?by=ask`.

  **Only rows carrying a `:model` block count.** An ask with no requests spent
  nothing, and averaging over it would report a mean that no ask ever cost:
  fifty asks of which two used the model would divide by fifty. `:asks` is
  therefore the number of asks that SPENT, which is the denominator the mean
  needs and a figure worth showing beside it.

  `:model` is a BLOCK here. Under `by=model` the same key is the model's NAME,
  and the contract declares both — so this function is `by=ask` only, and
  destructuring it as a string is the mistake the split invites."
  [rows]
  (let [spent (filterv :model rows)
        toks  (reduce + 0 (map #(or (:tokens (:model %)) 0) spent))]
    {:asks        (count spent)
     :requests    (reduce + 0 (map #(or (:requests (:model %)) 0) spent))
     :tokens      toks
     :cost-usd    (reduce + 0.0 (map #(or (:cost-usd (:model %)) 0.0) spent))
     :mean-tokens (if (seq spent) (quot toks (count spent)) 0)}))

(defn recent-token-use
  "The most recent `n` asks that spent anything, as bars — `{:intent :tokens
  :cost-usd :share}`, wire order kept.

  **Wire order IS the time axis.** `/api/cost?by=ask` answers newest first and
  its `:at` is epoch millis; this dialect has no portable way to format one,
  so a date axis would mean inventing date maths for a label. The ask's own
  recorded intent is a better label anyway — `what did this cost` is a question
  about the work, and the work has a name.

  **An ask that spent nothing is DROPPED, not drawn as a zero.** A zero bar
  says the ask was cheap; absence of telemetry says nothing was recorded. The
  totals beside this report how many asks spent at all, which is where that
  fact belongs.

  `:share` is against the biggest in the WINDOW rather than of all time, so
  the tallest bar on screen fills the pane. A share of ALL TIME would
  draw every bar as a sliver once the journal grew, which is a picture of how
  long the project has run rather than of what the recent work cost."
  [rows n]
  (let [shown (into [] (comp (filter :model) (take n)) rows)
        peak  (reduce max 0 (map #(or (:tokens (:model %)) 0) shown))]
    (mapv (fn [r]
            (let [t (or (:tokens (:model r)) 0)]
              {:intent   (:intent r)
               :tokens   t
               :cost-usd (or (:cost-usd (:model r)) 0.0)
               :share    (if (pos? peak) (/ (double t) peak) 0.0)}))
          shown)))

(defn usd
  "`d` as dollars and cents — `10.242674` becomes `\"$10.24\"`.

  Integer arithmetic and string surgery rather than a formatter, because
  `format` is JVM-only and `Math/round` is not portable, and this dialect
  refuses the reader conditional that would let one form branch. A money figure
  is not worth asking slopp for a helper; a date axis would have been.

  Rounds rather than truncating: a cost reported LOW is the wrong direction to
  be wrong in."
  [d]
  (let [c (int (+ 0.5 (* 100 (or d 0))))]
    (str "$" (quot c 100) "." (subs (str (+ 100 (rem c 100))) 1))))

(defn spend-summary
  "What `GET /api/cost?by=ask` says the work cost — the attributed total, and
  the two disclosures that belong beside it.

  **The rows are not the whole document and reading only them understates the
  spend.** `:unattributed` is requests that fell in no turn bracket, carrying
  their own model block; `:undated` is how many carried no timestamp at all.
  slopp keeps both OUT of the ask rows deliberately — attributing them to a
  neighbouring ask would invent a fact — which means a consumer that folds only
  `:rows` reports a number lower than what was spent and says nothing about the
  difference.

  So: `:tokens` stays the ATTRIBUTED total, because an ask still means an ask;
  `:unattributed` sits beside it; and `:total-tokens` is the honest grand
  total. Three numbers rather than one, for the same reason this screen names
  which kind of nothing an empty panel is."
  [doc]
  (let [attributed (token-totals (:rows doc))
        u          (:unattributed doc)
        u-tokens   (or (:tokens (:model u)) 0)]
    (assoc attributed
           :unattributed {:requests (or (:requests u) 0)
                          :tokens   u-tokens
                          :cost-usd (or (:cost-usd (:model u)) 0.0)}
           :undated      (or (:undated doc) 0)
           :total-tokens (+ (:tokens attributed) u-tokens))))

(defn growth
  "Store size at each commit point, oldest first — from `by=commit-point` rows.

  **Oldest first**, because the wire answers newest first and a growth chart
  reads left to right through time.

  **A row with no `:forms` is DROPPED rather than zeroed.** The contract says
  the count is absent for a commit point older than the fold, and a zero there
  would draw the store springing into existence at the moment slopp started
  counting — a picture of the instrument rather than of the code.

  The counts are LIVE forms, so a sweep that deleted a hundred reads as the
  fall it was; that is slopp's doing and it is the property that makes this
  worth plotting at all."
  [rows]
  (->> rows
       (filter :forms)
       (sort-by :at)
       (mapv #(select-keys % [:commit :description :at :forms :namespaces]))))

(defn effort-totals
  "The `by=commit-point` rows summed — turns, calls, the wall-clock split,
  refusals and context rent.

  **`:slopp-share` is derived from the SUMS rather than averaged from the
  per-row shares.** Averaging percentages weights a segment of one call the
  same as a segment of a hundred, which is the classic way to publish a figure
  that is arithmetically fine and describes nothing. slopp computes its own
  share against ACTIVE time for the same reason — so a human going to bed is
  not counted as time slopp failed to use — and this keeps that denominator.

  `:carried-chars` is context rent in CHARACTERS, not tokens: an answer's size
  times the calls that follow it. It is a within-turn lower bound."
  [rows]
  (letfn [(sum [f] (reduce + 0 (map #(or (f %) 0) rows)))]
    (let [active (sum #(:active-ms (:wall %)))
          slopp  (sum #(:slopp-ms (:wall %)))]
      {:turns         (sum :turns)
       :calls         (sum :calls)
       :refused       (sum #(:count (:refused %)))
       :active-ms     active
       :slopp-ms      slopp
       :outside-ms    (sum #(:outside-ms (:wall %)))
       :idle-ms       (sum #(:idle-ms (:wall %)))
       :carried-chars (sum #(:carried-chars (:rent %)))
       :slopp-share   (if (pos? active) (int (* 100 (/ (double slopp) active))) 0)})))

(defn arc-summary
  "One range's red/green arc, summarised — `{:verifications :red :ms}`.

  **The denominator is OPTIONAL and stays optional.** `:tests`, `:pass` and
  `:ms` arrived on 2026-09-05; an arc entry recorded before that carries none,
  and defaulting them to zero would render a suite with no tests in it — a
  false statement where absence is the true one. So `:ms` is nil when no entry
  reported a time, and each red entry keeps its own `:tests` rather than the
  summary inventing a shared one.

  `:red` keeps whole entries rather than delta ids, because the count and the
  size it is a count OF have to travel together: two failures of twelve and
  two of two thousand are the same number and different news."
  [arc]
  (let [red (filterv #(pos? (or (:fail %) 0)) arc)
        ms  (reduce + 0 (map #(or (:ms %) 0) arc))]
    {:verifications (count arc)
     :red           red
     :ms            (when (pos? ms) ms)}))
