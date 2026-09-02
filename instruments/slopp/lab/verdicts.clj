(ns slopp.lab.verdicts
  "Does the external tier repeat work a content-keyed cache could skip?

  An INSTRUMENT, run by hand, and it exists to answer one question that has
  been open since 2026-07-22: a verdict cache is designed, and the argument
  against building it was never that the design is wrong — it is that nobody
  could say how much waste there is to remove. A cache keyed by content is
  sound in principle and a HIT runs nothing, so a wrong one persists a false
  green until the content changes. That is a bad trade for an unmeasured win,
  and the cheap levers on this tier have a history of measuring zero: a warm
  image pool was built end to end and reverted at no gain, and raising the
  shard count shipped a 44% regression.

  What was missing was the record. A verdict could not be keyed to anything,
  so the waste could not be counted. The `:observe` delta now carries the
  content each run observed, which makes the question a replay over the
  journal rather than a study to be commissioned — and the number it produces
  is allowed to say no.

  Nothing here decides anything by itself. It reports; a human reads it
  against the threshold and chooses." (:require [slopp.ops.external :as external] [slopp.ops :as ops]))

(defn reuse-rate
  "Replay the journal's `:observe` deltas and report how much of the external
  tier's work re-verified content that was ALREADY green at exactly that
  content — the measurement the verdict cache is gated on.

  Returns `{:source :observations :without-closure :namespace-runs
  :first-sighting :already-green :fraction :first :last}`.
  `:namespace-runs` is the population: one per (observation,
  namespace-in-its-scope) pair that carries a closure hash, so a suite sweep
  over a hundred namespaces is a hundred chances to reuse rather than one.
  `:already-green` counts the pairs whose hash a PRIOR green observation had
  already covered — the runs a content-keyed cache would have skipped.

  **`:source` is part of the answer.** This folded `(:deltas store)`, and a
  DURABLE store's value carries none — the journal lives in SQLite and the
  value keeps a bounded `:recent` window (which is where `standing-run`
  reads). So its own recorded reading, \"108 namespace-runs, every one a
  first sighting, not measurable yet\", was this function describing an empty
  list, and the cache stayed deferred on a number that could not move
  (s19). It now replays whichever record it was handed and names it:
  `:journal` for a hydrated value (`ops/with-history`, what `-main` passes),
  `:recent-window` for the bounded tail — with a note, because a window's
  rate read as the store's is the same lie in the other direction.

  Three rules keep the number from arguing for a cache that is not warranted,
  and all three matter because both ways this can lie argue FOR building:

  - Only a GREEN observation contributes content, so a red run followed by a
    re-run at the same hash is the retry that finds the fix, not reuse.
  - An observation with no closure key counts in `:without-closure` instead of
    vanishing. Every observation recorded before the key existed lacks one,
    and a fraction computed over the handful that carry it looks exactly like
    a fraction computed over all of them.
  - A pair whose namespace has never been observed green before is a FIRST
    SIGHTING and cannot possibly be a hit. A journal made only of those yields
    0.0 by construction — which reads exactly like *measured, and there is no
    waste to remove*. So `:fraction` is nil when nothing was comparable, the
    same way it is nil for an empty journal: there is no rate, rather than a
    rate of none.

  Read the result against the threshold in the idea file rather than as a
  target: the point of the number is that it can say NO, and the cheap levers
  on this tier have measured zero before — one of them shipped a 44%
  regression."
  [store]
  (let [hydrated? (seq (:deltas store))
        record    (if hydrated? (:deltas store) (:recent store))
        obs       (filter #(= :observe (:op %)) record)]
    (loop [[o & more] obs, green {}, runs 0, hits 0, firsts 0, blind 0]
      (if-not o
        (let [comparable (- runs firsts)]
          (cond-> {:source          (if hydrated? :journal :recent-window)
                   :observations    (count obs)
                   :without-closure blind
                   :namespace-runs  runs
                   :first-sighting  firsts
                   :already-green   hits
                   :fraction        (when (pos? comparable) (double (/ hits runs)))
                   :first           (:id (first obs))
                   :last            (:id (last obs))}
            (not hydrated?)
            (assoc :note (str "read the bounded :recent WINDOW, not the whole journal"
                              " — this rate is that window's, and the store's own may"
                              " differ. Hydrate first (ops/with-history), which is what"
                              " slopp.lab.verdicts/-main does."))))
        (let [cl    (:closure o)
              pairs (for [n (:scope o) :let [h (get cl n)] :when h] [n h])]
          (recur more
                 (if (= :green (get-in o [:result :status]))
                   (reduce (fn [m [n h]] (update m n (fnil conj #{}) h)) green pairs)
                   green)
                 (+ runs (count pairs))
                 (+ hits (count (filter (fn [[n h]] (contains? (get green n) h)) pairs)))
                 (+ firsts (count (remove (fn [[n _]] (contains? green n)) pairs)))
                 (cond-> blind (empty? cl) inc)))))))

^{:entry-point "resolved by NAME from the command line — --main slopp.lab.verdicts/-main; nothing inside the store refers to it"}
(defn ^:export -main
  "CLI: the reuse replay over a store's WHOLE journal — the gate the verdict
  cache waits on, made takeable. The tree is fileless, so this goes through
  the boot kernel:

    clojure -M -m slopp.kernel.boot . --snapshot --main slopp.lab.verdicts/-main

  Prints the [[reuse-rate]] map (`:source :journal`). Read it against the
  threshold in the idea file; the number is allowed to say no."
  [& [dir]]
  (let [session (external/open! {:slopp.ops/dir (or dir ".")})]
    (try
      (println (pr-str (reuse-rate (:store @(ops/with-history session)))))
      (finally (ops/close! session)))))
