(ns slopp.lab.reads
  "What do slopp's own answers cost, and does withholding one ever save?

  An INSTRUMENT, run by hand, and it exists to put a number under a claim
  that has been standing since the read tier was first profiled: reads are
  52% of the token bill and got the least optimization. The one lever that
  did ship on that tier is the response size gate — over the limit, the
  payload is cut and the whole thing spooled behind a retrieval id.

  Nobody could say whether that gate pays. It was measured exactly once, by
  hand, off a single transcript: an 8,367-char trimmed read followed by a
  21,676-char re-fetch, against 21,676 for sending it whole. The trim spent
  30k characters to save nothing. A gate that is usually re-fetched is a NET
  LOSS, and until the wire started recording what each answer sent and what
  it withheld, that case and the case where the agent never came back looked
  identical from outside.

  This folds those records — laid onto the `:turn-end` deltas by the per-turn
  fold, because the wire's ring is cleared at every turn boundary — across
  the whole journal. It decides nothing. A human reads it against a
  threshold and chooses, the same way the verdict-cache reuse rate is read,
  and it is allowed to say the lever is not worth building on.

  The reading trap is the same one too: a re-fetch rate over a journal that
  never withheld anything is NOT MEASURABLE YET, not zero.")

(defn read-ledger
  "Fold every `:read-cost` delta into one answer for the store.

  Returns `{:spans :calls :chars :withheld :trimmed :stubbed :refetched
  :refetched-chars :refetched-elsewhere :refetch-rate :by-tool :first :last}`.

  `:refetch-rate` is `:refetched` over `:withheld`, and it is nil when nothing
  was withheld — there is no rate, rather than a rate of none. That
  distinction is the whole reading discipline here: a journal with no trims in
  it says the gate is unmeasured, and a 0.0 in its place would say it is free.

  `:refetched-elsewhere` are retrievals the span fold could not tie to a
  withholding it saw, because the trim and the re-fetch fell on either side of
  a flush. They survive to this grain deliberately — attributing them to
  nobody would let a flush boundary read as evidence the gate paid.

  Read `:refetched-chars` against `:chars`, not on its own: the finding this
  exists to make findable is a withheld answer plus its re-fetch costing more
  than the answer would have whole.

  **WHAT THIS CANNOT SEE, and it cannot count it either.** A span becomes
  durable only when a WRITE tool flushes it, because every read tool declares
  `readOnlyHint` on the wire and writing a journal delta from one would break
  that promise. So a session that reads and never writes — a review, a
  question answered, a plan — contributes nothing here and leaves no trace of
  having been omitted. There is no `:unmeasured` column for it because there
  is nothing to count: the absence is total.

  That matters because the omission is not random. It skews toward read-heavy
  work, which is the work this number is about, so **every rate here is a
  lower bound on how much reading actually happens** and should be quoted as
  one. The earlier version of this ledger rode `:turn-end` and had the same
  bias much worse — turns rotate only on a user prompt followed by a write, so
  it also lost every event-driven session, measured at zero records across 321
  and 118 closed turns in two stores. The flush fixed that half. This half is
  a consequence of the read-only promise and is not going away."
  [store]
  (let [spans (filter #(= :read-cost (:op %)) (:deltas store))
        recs  (keep :reads spans)
        sum   (fn [k] (reduce + 0 (keep k recs)))
        held  (sum :withheld)
        rows  (->> (mapcat :by-tool recs)
                   (group-by :tool)
                   (map (fn [[t rs]]
                          (let [n-tr (reduce + 0 (keep :trimmed rs))
                                n-st (reduce + 0 (keep :stubbed rs))
                                n-re (reduce + 0 (keep :refetched rs))]
                            (cond-> {:tool t
                                     :n     (reduce + 0 (keep :n rs))
                                     :chars (reduce + 0 (keep :chars rs))}
                              (pos? n-tr) (assoc :trimmed n-tr)
                              (pos? n-st) (assoc :stubbed n-st)
                              (pos? n-re) (assoc :refetched n-re)))))
                   (sort-by (juxt (comp - :chars) :tool))
                   vec)]
    {:spans               (count spans)
     :calls               (sum :calls)
     :chars               (sum :chars)
     :withheld            held
     :trimmed             (sum :trimmed)
     :stubbed             (sum :stubbed)
     :refetched           (sum :refetched)
     :refetched-chars     (sum :refetched-chars)
     :refetched-elsewhere (sum :refetched-elsewhere)
     :refetch-rate        (when (pos? held) (double (/ (sum :refetched) held)))
     
     :by-tool             rows
     :first               (:id (first spans))
     :last                (:id (last spans))}))
