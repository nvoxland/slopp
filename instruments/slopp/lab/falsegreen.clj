(ns slopp.lab.falsegreen
  "How often does green lie? An INSTRUMENT, run by hand."
  (:require [slopp.rules.mutate :as mutate]
            [slopp.store.db :as db]
            [slopp.ops.external :as external]
            [slopp.ops :as ops]))

(defn ^:export episode-form-ids
  "The form ids the last `n` episodes in `deltas` (oldest first, episodes
  separated by `:done`) AUTHORED: `:replace`/`:add` deltas, not the pipeline's
  `:system` writes, distinct, in first-seen order."
  [deltas n]
  (->> deltas
       (partition-by #(= :done (:op %)))
       (remove #(= :done (:op (first %))))
       (take-last n)
       (apply concat)
       (filter #(and (contains? #{:replace :add} (:op %)) (not (:system %))))
       (keep :form-id)
       distinct
       vec))

(defn ^:export rates
  "False-green rates over mutation `results` (one per form, as
  `slopp.rules.mutate/mutate!` returns them). A mutant that timed out counts
  as caught, since a done that never finishes is not a green one. A form whose
  mutants all failed to load is `:unchecked`, not tested: it adds to `:invalid`
  and to no rate. Rates are nil, not zero, when nothing was measured."
  [results]
  (let [by       (group-by :status results)
        tested   (concat (:killed by) (:survived by))
        caught   (reduce + 0 (map #(+ (:killed % 0) (:timeout % 0)) tested))
        survived (reduce + 0 (map #(count (:survivors %)) tested))
        invalid  (reduce + 0 (map #(:invalid % 0) (concat tested (:unchecked by))))
        n-surv   (count (:survived by))
        n-test   (count tested)
        n-untest (count (:untested by))
        ratio    (fn [a b] (when (pos? b) (/ (Math/round (* 1000.0 (/ a b))) 1000.0)))]
    {:forms     (count results)
     :tested    n-test
     :untested  n-untest
     :no-sites  (count (:no-sites by))
     :unchecked (count (:unchecked by))
     :mutants   {:caught caught :survived survived :invalid invalid}
     :false-green {:per-mutant (ratio survived (+ caught survived))
                   :per-form   (ratio n-surv n-test)
                   :per-form-incl-untested (ratio (+ n-surv n-untest) (+ n-test n-untest))}}))

(defn ^:export measure!
  "Plant defects in what the last `episodes` (default 50) on the trunk
  changed, run them against `session`'s image under `budget-ms` (default 10
  minutes), and append the [[rates]] as one `false-green` measurement.
  Returns that summary. Mutation results are NOT recorded as owed or checked:
  this measures `done`, it does not stand in for one."
  [session & {:keys [episodes budget-ms]}]
  (let [{:keys [db image store test-map]} @session
        n    (or episodes 50)
        fids (episode-form-ids (db/line-deltas db (db/trunk-line-id! db)) n)
        ts   (mutate/targets store test-map fids)
        t0   (System/currentTimeMillis)
        rs   (mutate/mutate! image store ts :budget-ms (or budget-ms 600000))
        out  (assoc (rates rs) :episodes n :ms (- (System/currentTimeMillis) t0))]
    (db/record-measurement! db "false-green" nil out)
    out))

^{:entry-point "resolved by NAME from the command line — --main slopp.lab.falsegreen/-main; nothing inside the store refers to it"}
(defn ^:export -main
  "CLI: the false-green rate over the last 50 episodes of the store at `dir`.
  The tree is fileless, so this goes through the boot kernel:

    slopp --main slopp.lab.falsegreen/-main

  Prints the [[measure!]] summary."
  [& [dir]]
  (let [session (external/open! {:slopp.ops/dir (or dir ".")})]
    (try
      (println (pr-str (measure! session)))
      (finally (ops/close! session)))))
