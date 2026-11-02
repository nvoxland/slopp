(ns slopp.rules.mutate
  "Mutation testing over the functions an episode changed: does any test NOTICE when the code is wrong?"
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [slopp.store :as store]
            [slopp.edit.hotload :as hotload]
            [slopp.image.repl :as repl] [slopp.store.db :as db]))

(def operator-swaps
  "Each mutation operator as `from -> to`: one small fault a test that
  constrains the code should notice. Kept to faults that still compile and
  still run, so a surviving mutant means the tests did not look, not that the
  mutant could not load."
  '{< <=, <= <, > >=, >= >, = not=, not= =, and or, or and, inc dec, dec inc,
    + -, - +, if if-not, if-not if, when when-not, when-not when,
    empty? seq, seq empty?, nil? some?, some? nil?, pos? neg?, neg? pos?,
    zero? pos?, first last, max min, min max})

(defn- in-body?
  "True when `loc` sits in the function's BODY: below the form's top list and
  not inside its arglist or attribute map (direct children that are vectors or
  maps), nor inside metadata, quoted data or a discarded `#_` form — none of
  which is behaviour a test could notice changing."
  [root-node loc]
  (loop [l loc]
    (let [up (z/up l)]
      (cond
        (nil? up) false
        (contains? #{:quote :syntax-quote :meta :uneval} (z/tag up)) false
        (identical? (z/node up) root-node)
        (and (not (identical? l loc)) (not (contains? #{:vector :map} (z/tag l))))
        :else (recur up)))))

(defn- replacement
  "The mutation for the token at `loc` as `{:from :to}`, or nil when no
  operator applies: a swap from [[operator-swaps]], a flipped boolean, or an
  integer literal off by one."
  [loc]
  (when (= :token (z/tag loc))
    (let [v (try (z/sexpr loc) (catch Exception _ ::unreadable))]
      (cond
        (boolean? v) {:from v :to (not v)}
        (and (symbol? v) (contains? operator-swaps v)) {:from v :to (operator-swaps v)}
        (and (integer? v) (not (instance? clojure.lang.BigInt v))) {:from v :to (inc v)}
        :else nil))))

(defn mutants
  "Up to `k` mutants of the defn source `src`, each `{:mutation \"< → <=\"
  :at \"(< x 10)\" :source s}`: the same source with exactly one token
  changed. `:at` is the expression the token sits in, cut to 60 characters —
  where it is, said the way a reader finds it, never as a coordinate. Only the
  body is mutated (see [[in-body?]]). When there are more sites than `k`, the
  k are spread evenly over the body in source order, so a long function is not
  judged on its first few lines."
  [src k]
  (let [top   (z/of-string src)
        root  (if (= :meta (z/tag top)) (-> top z/down z/rightmost) top)
        rnode (z/node root)
        sites (vec (keep (fn [loc]
                           (when (in-body? rnode loc)
                             (when-let [r (replacement loc)] (assoc r :loc loc))))
                         (take-while #(not (z/end? %)) (iterate z/next root))))
        n     (count sites)
        picked (if (<= n k) sites (mapv #(sites (quot (* % n) k)) (range k)))
        cut   (fn [s] (if (> (count s) 60) (str (subs s 0 60) "…") s))]
    (mapv (fn [{:keys [loc from to]}]
            {:mutation (str (pr-str from) " → " (pr-str to))
             :at       (cut (n/string (z/node (z/up loc))))
             :source   (z/root-string (z/replace loc (n/token-node to)))})
          picked)))

(defn run-string
  "A self-contained eval string for the IMAGE: run `tests` (qualified deftest
  symbols) one at a time and stop at the first that fails or errors. It
  answers `{:killed-by t :ran n}`, `{:survived true :ran n}`, or `{:timeout
  true}` when `timeout-ms` passed first; `:skipped n` rides along when some
  were not run. A test that does not resolve, or is `^:external` or
  `^:integration`, is skipped: those spawn processes or need the outside world,
  and only behave in the external tier.

  Self-contained because the image is the PROJECT's process and need not have
  slopp on its classpath; only clojure.test is assumed. Counters and output
  are bound per test, so a mutant's failures never reach whatever reporting
  the image was already doing. A timed-out run is cancelled, but a mutant that
  loops without checking for interruption keeps its thread; the caller reports
  the timeout rather than pretending it cleaned up."
  [tests timeout-ms]
  (format
   (str "(do (require 'clojure.test)"
        " (let [run (fn [] (loop [[t & more] '%s ran 0 skipped 0]"
        "   (let [done (fn [m] (cond-> m (pos? skipped) (assoc :skipped skipped)))]"
        "    (if (nil? t) (done {:survived true :ran ran})"
        "     (let [v (try (resolve t) (catch Exception _ nil)) m (meta v)]"
        "      (if (or (nil? v) (not (:test m)) (:external m) (:integration m))"
        "       (recur more ran (inc skipped))"
        "       (let [c (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)"
        "                         clojure.test/*test-out* (java.io.StringWriter.)]"
        "                 (clojure.test/test-vars [v]) @clojure.test/*report-counters*)]"
        "        (if (pos? (+ (:fail c 0) (:error c 0)))"
        "         (done {:killed-by t :ran (inc ran)})"
        "         (recur more (inc ran) skipped)))))))))"
        "       f (future (run))"
        "       r (deref f %d ::timeout)]"
        "   (if (= r ::timeout) (do (future-cancel f) {:timeout true}) r)))")
   (pr-str (vec tests)) (long timeout-ms)))

(defn ^:export form-sig
  "The VERSION of form `fid` in `store`: a hash of its source, so a recorded
  result names the exact code it judged and any edit makes it stale. nil when
  the form no longer exists."
  [store fid]
  (when-let [e (store/form-by-id store fid)]
    (store/sha256-of (.getBytes ^String (n/string (:node e)) "UTF-8"))))

(defn- mutate-form!
  "Run up to `k` mutants of one target `{:fid :form :tests}` against `image`,
  restoring the real form from `store` after each. Returns the target with a
  `:status` — `:killed`, `:survived` (with `:survivors`), `:untested` (no form
  or no test to run), `:no-sites`, `:unchecked` (the budget ran out first) or
  `:restore-failed`, which is the one result that means the image no longer
  matches the store and must be rebuilt."
  [image store {:keys [fid tests] :as target} k per-mutant-ms left]
  (let [base   (select-keys target [:form :fid :sig])
        e      (store/form-by-id store fid)
        ns-sym (store/ns-of-form-id store fid)
        ms     (when (and e (seq tests)) (mutants (n/string (:node e)) k))]
    (cond
      (or (nil? e) (empty? tests)) (assoc base :status :untested)
      (empty? ms) (assoc base :status :no-sites)
      :else
      (loop [[m & more] ms acc {:killed 0 :timeout 0 :invalid 0 :survivors []}]
        (cond
          (nil? m)
          (merge base acc {:mutants (count ms)
                           :status  (if (seq (:survivors acc)) :survived :killed)})

          (<= (left) 0) (assoc base :status :unchecked)

          :else
          (let [st'  (first (store/replace-node store ns-sym fid (p/parse-string (:source m))))
                err  (when st' (hotload/hot-load-form! image st' fid))
                ran? (and st' (nil? err))
                r    (when ran?
                       (try (first (repl/eval! image (run-string tests (max 1 (min per-mutant-ms (left))))))
                            (catch Throwable t (str t))))
                back (when ran? (hotload/hot-load-form! image store fid))]
            (if back
              (assoc base :status :restore-failed :error back)
              (recur more
                     (cond
                       (or (not ran?) (not (map? r)) (and (:survived r) (zero? (:ran r 0))))
                       (update acc :invalid inc)
                       (:timeout r)   (update acc :timeout inc)
                       (:killed-by r) (update acc :killed inc)
                       :else (update acc :survivors conj
                                     {:mutation (:mutation m) :at (:at m) :tests (vec tests)}))))))))))

(defn ^:export mutate!
  "Mutation-test `targets` (`[{:fid :form :tests :sig?} …]`) against `image`,
  whose code must be `store`'s. Returns one result per target (see
  [[mutate-form!]] for the statuses).

  Bounded by `:budget-ms` of wall time (default 10s), not by a mutant count
  alone: targets run cheapest first — fewest covering tests — so a spent
  budget leaves the expensive tail, and everything it did not reach comes back
  `:unchecked` for the caller to record rather than silently absent. `:k`
  (default 5) caps mutants per form; `:per-mutant-ms` (default 5s) bounds one
  mutant's run, since a fault can turn a loop infinite. Stops at the first
  `:restore-failed`, because every result after it would be about the wrong
  code."
  [image store targets & {:keys [budget-ms k per-mutant-ms]
                          :or {budget-ms 10000 k 5 per-mutant-ms 5000}}]
  (let [t0   (System/currentTimeMillis)
        left #(- budget-ms (- (System/currentTimeMillis) t0))]
    (loop [[t & more :as todo] (sort-by (comp count :tests) targets) out []]
      (cond
        (nil? t) out
        (<= (left) 0)
        (into out (map #(assoc (select-keys % [:form :fid :sig]) :status :unchecked)) todo)
        :else
        (let [r (mutate-form! image store t k per-mutant-ms left)]
          (if (= :restore-failed (:status r))
            (conj out r)
            (recur more (conj out r))))))))

(defn ^:export backlog
  "The forms mutation testing still OWES: from recorded result `rows`
  (`[{:payload {:fid :sig :form :status}} …]`, oldest first), those whose
  latest result for their CURRENT version (see [[form-sig]]) is `:unchecked`.
  A form edited since, or deleted, drops out by itself: its version changed,
  so the old debt is about code that no longer exists. Returns `[{:fid :form
  :sig}]` sorted by form."
  [store rows]
  (->> rows
       (keep :payload)
       (filter :fid)
       (reduce (fn [m p] (assoc m [(:fid p) (:sig p)] p)) {})
       vals
       (filter #(and (= :unchecked (:status %)) (= (:sig %) (form-sig store (:fid %)))))
       (map #(select-keys % [:fid :form :sig]))
       (sort-by (comp str :form))
       vec))

(defn defn-source?
  "True when `src` is a `defn`/`defn-` (reader metadata on the whole form
  allowed): the only forms mutated, because only a function body is behaviour
  a test can be asked to notice."
  [src]
  (let [top  (try (z/of-string src) (catch Exception _ nil))
        root (if (and top (= :meta (z/tag top))) (-> top z/down z/rightmost) top)
        head (some-> root z/down)]
    (boolean (and head (= :list (z/tag root))
                  (contains? '#{defn defn-} (try (z/sexpr head) (catch Exception _ nil)))))))

(defn ^:export targets
  "The mutation targets among form ids `fids`: each production `defn`, with
  the tests the trace map `tmap` (`{test #{form …}}`) saw run it, sorted, and
  its version (see [[form-sig]]). Test namespaces are skipped — a test is the
  oracle here, not the subject — and so is anything that is not a function."
  [store tmap fids]
  (vec (for [fid  fids
             :let [e (store/form-by-id store fid) ns-sym (store/ns-of-form-id store fid)]
             :when (and e ns-sym (:name e)
                        (not (str/ends-with? (str ns-sym) "-test"))
                        (defn-source? (n/string (:node e))))
             :let [ks (set (store/form-trace-keys ns-sym e))]]
         {:fid fid :form (symbol (str ns-sym) (str (:name e))) :sig (form-sig store fid)
          :tests (vec (sort (keep (fn [[t fs]] (when (some #(contains? ks %) fs) t)) tmap)))})))

(defn ^:export findings
  "Done findings from [[mutate!]] `results`. A survivor is a finding on its
  form naming each surviving fault, its line and the tests that ran it. A
  failed restore is a finding too, since the image may still hold the mutant.
  What the budget did not reach, and changed functions with no in-image test,
  are one formless `:info` finding each: neither is a fault in the code, but
  reporting nothing would read as a clean result nobody earned."
  [results]
  (let [by       (group-by :status results)
        owed     (mapv :form (:unchecked by))
        untested (mapv :form (:untested by))
        names    (fn [xs] (str (str/join ", " (take 5 xs)) (when (> (count xs) 5) ", …")))]
    (vec (concat
          (for [{:keys [form survivors]} (:survived by)]
            {:form      form
             :survivors survivors
             :teach     (str (count survivors) " mutant(s) survived: "
                             (str/join "; " (for [{:keys [mutation at]} survivors]
                                              (str mutation " in " at)))
                             ". The tests that run it (" (names (:tests (first survivors)))
                             ") still pass with that fault in place, so they watch this code"
                             " without constraining it. Add an assertion that fails on the"
                             " fault; if no input can tell the two apart (an equivalent"
                             " mutant), there is nothing to do.")})
          (for [{:keys [form error]} (:restore-failed by)]
            {:form  form
             :teach (str "the real form could not be re-loaded after a mutant (" error
                         "), so the image may still hold the mutant. `restart` rebuilds"
                         " it from the store.")})
          (when (seq owed)
            [{:severity  :info
              :unchecked owed
              :teach     (str "the mutation budget ran out before " (count owed)
                              " changed function(s) were checked: " (names owed)
                              ". They are recorded as owed; `verify {op mutate}` checks"
                              " what is owed with a larger budget.")}])
          (when (seq untested)
            [{:severity :info
              :untested untested
              :teach    (str (count untested) " changed function(s) have no in-image test"
                             " to mutate against: " (names untested)
                             ". Mutation testing cannot say whether anything constrains them.")}])))))

(defn ^:export record!
  "Record each of `results` as a `mutation` measurement on `conn` — beside the
  journal, not in it, so a statistic never moves the head — for [[backlog]]
  and for the false-green rate to read back later."
  [conn results]
  (doseq [r results]
    (db/record-measurement! conn "mutation" nil
                            (select-keys r [:fid :form :sig :status :mutants :killed
                                            :timeout :invalid :survivors]))))
