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

(defn ^:export reuse-by-grain
  "What the verdict cache would have skipped, replayed from the journal and
  split by GRAIN: `{:narrowed {…} :whole-suite {…}}`.

  [[reuse-rate]] answers the question the gate was written against and mixes
  grains doing it. This answers the one the BUILD needs: the cache exists at
  done grain (narrowed `:only` runs) and full_check is deliberately
  uncached, so a fraction that includes whole-suite runs cannot say whether
  what was built pays.

  Per grain: `:runs`, the population `:tests` (or namespaces) that carried a
  closure hash, `:already-green`, `:fraction` — and `:runs-fully-avoidable`,
  which is the number that actually maps to wall time. The external tier's
  cost floor is a JVM BOOT, not a test: skipping some of a run's tests saves
  nothing, and only a run whose every test was already green at exactly this
  content is a run that need not have happened.

  The evidence rules are the ones any such cache has to use (they lived in
  `reusable-verdicts`, deleted with the cache when this measurement refused
  it — the seam in `done!` carries the numbers): a green narrowed run clears the tests it named, a green whole-suite
  run clears the namespaces in its scope, red clears nothing, and everything
  is keyed by the closure hash recorded with it. Observations that predate
  the per-namespace verdict are read the same way it would record them — a
  green run with no `:only` covered its whole scope."
  [store]
  (let [ns-of (fn [t] (some-> t str symbol namespace symbol))
        obs   (filter #(= :observe (:op %))
                      (or (seq (:deltas store)) (:recent store)))]
    (loop [[o & more] obs
           green-t {} green-n {}
           nar {:runs 0 :tests 0 :already-green 0 :runs-fully-avoidable 0}
           who {:runs 0 :namespaces 0 :already-green 0 :runs-fully-avoidable 0}]
      (if-not o
        {:narrowed   (assoc nar :fraction (when (pos? (:tests nar))
                                            (double (/ (:already-green nar) (:tests nar)))))
         :whole-suite (assoc who :fraction (when (pos? (:namespaces who))
                                             (double (/ (:already-green who) (:namespaces who)))))}
        (let [r      (:result o)
              cl     (:closure o)
              green? (= :green (:status r))
              only   (seq (:only r))]
          (if only
            ;; DONE GRAIN: the tests this run named
            (let [pairs (for [t only :let [n (ns-of t) h (when n (get cl n))] :when h] [t n h])
                  hits  (count (filter (fn [[t n h]] (or (contains? (get green-t (symbol (str t))) h)
                                                         (contains? (get green-n n) h)))
                                       pairs))]
              (recur more
                     (if green?
                       (reduce (fn [m [t _ h]] (update m (symbol (str t)) (fnil conj #{}) h))
                               green-t pairs)
                       green-t)
                     green-n
                     (-> nar
                         (update :runs inc)
                         (update :tests + (count pairs))
                         (update :already-green + hits)
                         (update :runs-fully-avoidable
                                 + (if (and (seq pairs) (= hits (count pairs))) 1 0)))
                     who))
            ;; WHOLE SUITE: the namespaces in its scope
            (let [pairs (for [n (:scope o) :let [h (get cl n)] :when h] [n h])
                  hits  (count (filter (fn [[n h]] (contains? (get green-n n) h)) pairs))]
              (recur more
                     green-t
                     (if green?
                       (reduce (fn [m [n h]] (update m n (fnil conj #{}) h)) green-n pairs)
                       green-n)
                     nar
                     (-> who
                         (update :runs inc)
                         (update :namespaces + (count pairs))
                         (update :already-green + hits)
                         (update :runs-fully-avoidable
                                 + (if (and (seq pairs) (= hits (count pairs))) 1 0)))))))))))

^{:entry-point "resolved by NAME from the command line — --main slopp.lab.verdicts/-main; nothing inside the store refers to it"}
(defn ^:export -main
  "CLI: the reuse replay over a store's WHOLE journal — the gate the verdict
  cache waits on, made takeable. The tree is fileless, so this goes through
  the boot kernel:

    slopp --main slopp.lab.verdicts/-main

  Prints [[reuse-rate]] (`:source :journal`) and then [[reuse-by-grain]] —
  the first is the gate's own question, the second is the one the built
  cache is answerable to. Read them against the threshold in the idea file;
  the numbers are allowed to say no."
  [& [dir]]
  (let [session (external/open! {:slopp.ops/dir (or dir ".")})]
    (try
      (let [st (:store @(ops/with-history session))]
        (println (pr-str (reuse-rate st)))
        (println (pr-str (reuse-by-grain st))))
      (finally (ops/close! session)))))
