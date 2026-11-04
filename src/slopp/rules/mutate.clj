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
  changed. `:at` is the expression the token sits in, on one line and cut to
  60 characters — where it is, said the way a reader finds it, never as a
  coordinate. When that expression is too long to show whole and the token is
  not its head, `:at` is the token's left neighbour and the token instead: a
  token directly in a cond or let would otherwise be placed by the first sixty
  characters of the whole form, which need not contain it. Only the body is
  mutated (see [[in-body?]]). When there are more sites than `k`, the k are
  spread evenly over the body in source order, so a long function is not
  judged on its first few lines."
  [src k]
  (let [top    (z/of-string src)
        root   (if (= :meta (z/tag top)) (-> top z/down z/rightmost) top)
        rnode  (z/node root)
        sites  (vec (keep (fn [loc]
                            (when (in-body? rnode loc)
                              (when-let [r (replacement loc)] (assoc r :loc loc))))
                          (take-while #(not (z/end? %)) (iterate z/next root))))
        n      (count sites)
        picked (if (<= n k) sites (mapv #(sites (quot (* % n) k)) (range k)))
        line   (fn [node] (str/replace (n/string node) #"\s+" " "))
        cut    (fn [s] (if (> (count s) 60) (str (subs s 0 60) "…") s))
        at     (fn [loc]
                 (let [whole (line (z/node (z/up loc)))
                       left  (z/left loc)]
                   (cut (if (and (> (count whole) 60) left)
                          (str (line (z/node left)) " " (line (z/node loc)))
                          whole))))]
    (mapv (fn [{:keys [loc from to]}]
            {:mutation (str (pr-str from) " → " (pr-str to))
             :at       (at loc)
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
                           :status  (cond
                                     (seq (:survivors acc)) :survived
                                     (pos? (+ (:killed acc) (:timeout acc))) :killed
                                     :else :unchecked)})

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
  "True when `src` holds a function body a test can be asked to notice: a
  `defn`/`defn-`, a `defmethod`, or a `def` whose value is a `fn` or `#()`
  (reader metadata on the whole form allowed). Data defs are not: they have no
  body to run. Logic in a shape this misses is outside red-proof and mutation,
  so a `defrecord`/`deftype`/`reify` method or a custom defn-like macro is a
  known limit."
  [src]
  (let [top  (try (z/of-string src) (catch Exception _ nil))
        root (if (and top (= :meta (z/tag top))) (-> top z/down z/rightmost) top)
        head (some-> root z/down)
        h    (when head (try (z/sexpr head) (catch Exception _ nil)))]
    (boolean
     (and head (= :list (z/tag root))
          (or (contains? '#{defn defn- defmethod} h)
              (and (= 'def h)
                   (let [v (try (last (z/sexpr root)) (catch Exception _ nil))]
                     (and (seq? v) (contains? '#{fn fn*} (first v))))))))))

(defn ^:export targets
  "The mutation targets among form ids `fids`: each production function (see
  [[defn-source?]]), with the tests the trace map `tmap` (`{test #{form …}}`)
  saw run it, sorted, and its version (see [[form-sig]]). A `defmethod` has no
  name of its own, so its `:form` is `ns/<form id>`, the key its trace uses.
  Test namespaces are skipped: a test is the oracle here, not the subject."
  [store tmap fids]
  (vec (for [fid  fids
             :let [e (store/form-by-id store fid) ns-sym (store/ns-of-form-id store fid)]
             :when (and e ns-sym
                        (not (str/ends-with? (str ns-sym) "-test"))
                        (defn-source? (n/string (:node e))))
             :let [ks (set (store/form-trace-keys ns-sym e))]]
         {:fid fid :form (symbol (str ns-sym) (str (or (:name e) (:id e)))) :sig (form-sig store fid)
          :tests (vec (sort (keep (fn [[t fs]] (when (some #(contains? ks %) fs) t)) tmap)))})))

(defn ^:export findings
  "Done findings from [[mutate!]] `results`. A survivor is a finding on its
  form naming each surviving fault, its line and the tests that ran it. A
  failed restore is a finding too, since the image may still hold the mutant.
  What the budget did not reach, and changed functions with no in-image test,
  are one formless `:info` finding each: neither is a fault in the code, but
  reporting nothing would read as a clean result nobody earned.

  A mutant that ran past the per-mutant limit is one more formless `:info`
  finding. The timeout counts as a kill, but the run is only cancelled: a
  mutant that loops without checking for interruption keeps its thread in the
  image, costing CPU under every later run until the image restarts. Nothing
  can stop that thread from outside, so the result says so and names the fix."
  [results]
  (let [by       (group-by :status results)
        owed     (mapv :form (remove :invalid (:unchecked by)))
        untested (mapv :form (:untested by))
        timed    (filterv #(pos? (:timeout % 0)) results)
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
                             " mutant), say so on the form: ^{:equivalent-mutants"
                             " {\"<the mutation as printed>\" \"why no input tells them apart\"}}.")})
          (for [{:keys [form error]} (:restore-failed by)]
            {:form  form
             :teach (str "the real form could not be re-loaded after a mutant (" error
                         "), so the image may still hold the mutant. `restart` rebuilds"
                         " it from the store.")})
          (when (seq timed)
            [{:severity  :info
              :timed-out (mapv :form timed)
              :teach     (str (reduce + (map :timeout timed)) " mutant(s) ran past the"
                              " per-mutant limit and count as killed: "
                              (names (map :form timed))
                              ". Each run was cancelled, but a mutant that loops without"
                              " checking for interruption keeps its thread in the image and"
                              " slows every later run. `build {op restart}` rebuilds the"
                              " image from the store and clears it.")}])
          (when (seq owed)
            [{:severity  :info
              :unchecked owed
              :teach     (str "the mutation budget ran out before " (count owed)
                              " changed function(s) were checked: " (names owed)
                              ". They are recorded as owed; `verify {op mutate}` checks"
                              " what is owed with a larger budget.")}])
          (when-let [bad (seq (filter :invalid (:unchecked by)))]
            [{:severity  :info
              :unchecked (mapv :form bad)
              :teach     (str "the mutants of " (names (map :form bad)) " could not be loaded and"
                              " run, so nothing was learned about the tests. It stays owed.")}])
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

(defn ^:export discharge
  "`results` with every survivor its form DISCHARGES removed: a form's name
  may carry `^{:equivalent-mutants {\"<mutation>\" \"why no input tells them
  apart\"}}`, keyed by the mutation's label as findings print it. A result
  whose survivors are all discharged becomes `:discharged`. An equivalent
  mutant is not a gap in the tests, and without this the only way to quiet
  one was to rewrite the code into a shape the mutator does not touch."
  [store results]
  (mapv (fn [{:keys [form status survivors] :as r}]
          (if-not (= :survived status)
            r
            (let [named (into {} (remove (comp str/blank? str val))
                              (some-> (store/form-named store (symbol (namespace form)) (symbol (name form)))
                                      store/form-name-meta :equivalent-mutants))
                  left  (vec (remove #(contains? named (:mutation %)) survivors))]
              (cond
                (empty? named) r
                (empty? left)  (assoc r :status :discharged :survivors [])
                :else          (assoc r :survivors left)))))
        results))

(defn- defn-key
  "A defn's source as data with its docstring removed — what a call can
  observe of it — and its name. nil when the source does not parse."
  [src]
  (when-let [x (try (n/sexpr (p/parse-string src)) (catch Exception _ nil))]
    (when (seq? x)
      (let [[head nm & more] x
            body (if (and (string? (first more)) (next more)) (rest more) more)]
        {:name nm :body (list* head body)}))))

(defn ^:export behaviour-change?
  "True when `new-src` changes what its function DOES compared with `old-src`.
  A docstring-only edit is not a change, and neither is a rename: the old name
  is a different function, so its version proves nothing about this one.
  Source that does not parse counts as a change, so it is checked rather than
  quietly skipped."
  [old-src new-src]
  (let [o (defn-key old-src) n' (defn-key new-src)]
    (cond
      (or (nil? o) (nil? n')) true
      (not= (:name o) (:name n')) false
      :else (not= (:body o) (:body n')))))

(defn ^:export proof-findings
  "Done findings from [[prove!]] `results`. An `:unproven` or `:uncovered`
  function is a finding on its form, and it holds the landing. What the image
  could not judge is one formless `:info` row, never a hold."
  [results]
  (let [by    (group-by :status results)
        exits (str " If the change keeps behaviour (a refactor), waive it on the done call:"
                   " done {untested {\"ns/name\" \"why\"}}. A function no in-image test can"
                   " watch takes ^{:untested-ok \"why\"} on its name.")]
    (vec (concat
          (for [{:keys [form tests]} (:unproven by)]
            {:form  form
             :teach (str "every test that runs it (" (str/join ", " (take 3 tests))
                         (when (> (count tests) 3) ", …")
                         ") also passes against its version from the last done, so nothing"
                         " shows this change was needed. Add an assertion that fails on the"
                         " old behaviour." exits)})
          (for [{:keys [form]} (:uncovered by)]
            {:form  form
             :teach (str "no in-image test runs this changed function, so nothing shows what"
                         " it should do. Write a test that calls it." exits)})
          (let [cant (concat (:unprovable by) (:unchecked by) (:restore-failed by))]
            (when (seq cant)
              [{:severity :info
                :unjudged (mapv :form cant)
                :teach    (str "the image could not show whether these changes were needed: "
                               (str/join "; " (for [r cant]
                                                (str (:form r) " (" (or (:why r)
                                                                        (some-> (:status r) name)) ")")))
                               (when (seq (:restore-failed by))
                                 ". The image may still hold an old version; `build {op restart}` rebuilds it"))}]))))))

(defn run-all-string
  "A self-contained eval string for the IMAGE: run every one of `tests`
  (qualified deftest symbols) and answer `{:failed [t …] :passed [t …] :skipped
  n}`, with `:timeout true` when `timeout-ms` passed first; what finished before
  then still counts. The sibling of [[run-string]], which stops at the first
  failure because one failure kills a mutant: a red state judges many functions
  at once, so it needs every test's result. The same tests are skipped, for the
  same reason: `^:external` and `^:integration` only behave in the external tier."
  [tests timeout-ms]
  (format
   (str "(do (require 'clojure.test)"
        " (let [res (atom {:failed [] :passed [] :skipped 0})"
        "       run (fn [] (doseq [t '%s]"
        "    (let [v (try (resolve t) (catch Exception _ nil)) m (meta v)]"
        "     (if (or (nil? v) (not (:test m)) (:external m) (:integration m))"
        "      (swap! res update :skipped inc)"
        "      (let [c (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)"
        "                        clojure.test/*test-out* (java.io.StringWriter.)]"
        "                (clojure.test/test-vars [v]) @clojure.test/*report-counters*)]"
        "       (swap! res update (if (pos? (+ (:fail c 0) (:error c 0))) :failed :passed) conj t))))) ::done)"
        "       f (future (run))"
        "       r (deref f %d ::timeout)]"
        "   (when (= r ::timeout) (future-cancel f))"
        "   (cond-> @res (= r ::timeout) (assoc :timeout true))))")
   (pr-str (vec (distinct tests))) (long timeout-ms)))

(defn- load-old!
  "Put `fid`'s version from `old` (source text) into `image`; nil `old` means the
  form is new, so `form` becomes a stub that throws. A new `defmethod` is removed
  from its multi instead: `form` names no var, and an intern over the multi would
  outlive the restore, which only re-evaluates the defmethod. Returns nil, or why
  it could not load."
  [image store fid form old]
  (if (nil? old)
    (let [src  (some-> (store/form-by-id store fid) :node n/string)
          x    (when src (try (n/sexpr (p/parse-string src)) (catch Exception _ nil)))
          code (if (and (seq? x) (= 'defmethod (first x)))
                 (format "(let [n (the-ns '%s)] (binding [*ns* n] (remove-method (deref (ns-resolve n '%s)) (eval '%s))) :stubbed)"
                         (namespace form) (second x) (pr-str (nth x 2)))
                 (format "(do (intern '%s '%s (fn [& _] (throw (ex-info \"absent before this change\" {})))) :stubbed)"
                         (namespace form) (name form)))
          r    (try (repl/eval! image code)
                    (catch Throwable t [(str t)]))]
      (when (not= [:stubbed] r) (str r)))
    (let [st' (try (first (store/replace-node store (store/ns-of-form-id store fid) fid (p/parse-string old)))
                   (catch Throwable _ nil))]
      (if st' (hotload/hot-load-form! image st' fid) "it does not parse"))))

(defn ^:export prove!
  "Check that each changed function's tests NEED the change, by building the
  state before it. A target is `{:fid :form :tests :old}`, where `:old` is the
  function's source at the last done (nil when it is new). Every target goes back
  together: its old version loaded into `image`, or a stub that throws when it is
  new; `:also-old` (`{fid source}`) sends other changed forms back too, such as a
  `def`. The tests run once, then `store`'s versions are restored.

  One state rather than one function at a time, because a change is usually more
  than one function: a fix spread over two passes with either half alone, and a
  new function can only go red if it is absent. Statuses:

  - `:proven` (with `:by`, the test) — one of its tests failed in that state.
  - `:unproven` — its tests all passed in that state.
  - `:uncovered` — no in-image test runs it.
  - `:unprovable` (with `:why`) — the image cannot judge: only `^:external`
    tests run it, or its old version no longer loads.
  - `:unchecked` — the `:budget-ms` (default 10s) ran out before its tests did.
  - `:restore-failed` — the image may still hold an old version."
  [image store targets & {:keys [budget-ms also-old] :or {budget-ms 10000}}]
  (let [base   #(select-keys % [:form :fid :tests])
        {none true some-tests false} (group-by (comp empty? :tests) targets)
        nope   (mapv #(assoc (base %) :status :uncovered) none)]
    (if (<= budget-ms 0)
      (into nope (map #(assoc (base %) :status :unchecked)) some-tests)
      (let [errs   (into {} (keep (fn [{:keys [fid form old]}]
                                    (when-let [e (load-old! image store fid form old)] [fid e])))
                         some-tests)
            _      (doseq [[fid src] also-old]
                     (load-old! image store fid nil src))
            loaded (remove (comp errs :fid) some-tests)
            r      (when (seq loaded)
                     (try (first (repl/eval! image (run-all-string (mapcat :tests loaded) budget-ms)))
                          (catch Throwable t (str t))))
            back   (first (into [] (keep #(hotload/hot-load-form! image store %))
                                (concat (map :fid some-tests) (keys also-old))))
            failed (set (:failed r))
            ran    (into failed (:passed r))]
        (if back
          (into nope (map #(assoc (base %) :status :restore-failed :error back)) some-tests)
          (into nope
                (for [{:keys [fid tests] :as t} some-tests]
                  (let [b (base t) by (first (filter failed tests))]
                    (cond
                      (errs fid)     (assoc b :status :unprovable
                                            :why (str "its version from the last done no longer loads (" (errs fid) ")"))
                      (not (map? r)) (assoc b :status :unprovable :why (str r))
                      by             (assoc b :status :proven :by by)
                      
                      (and (:timeout r) (not-every? ran tests)) (assoc b :status :unchecked)
                      (not-any? ran tests) (assoc b :status :unprovable :why "only tests outside the image run it")
                      :else          (assoc b :status :unproven))))))))))

(defn ^:export weak-forms
  "The forms whose tests let a mutant survive: from recorded result `rows`, those
  whose latest result for their CURRENT version is `:survived`. Read from the
  record because a done's findings carry a repeated row only as a count, so a
  survivor would otherwise vanish from review one done later. An edit retires it:
  the version changed."
  [store rows]
  (->> rows
       (keep :payload)
       (filter :fid)
       (reduce (fn [m p] (assoc m [(:fid p) (:sig p)] p)) {})
       vals
       (filter #(and (= :survived (:status %)) (= (:sig %) (form-sig store (:fid %)))))
       (map :form)
       set))
