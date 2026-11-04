(ns slopp.ops.review
  "Whole-codebase TRIAGE: which forms should a reviewer read first, and why.

  It exists because the obvious reading order is the wrong one. A reviewer
  arriving at a store has no file to open and no diff to follow, and reading
  namespaces alphabetically spends the most attention on whatever sorts first.
  So this derives risk per form from signals the store already holds — observed
  coverage, caller count, size, lint, docs, effectfulness — and ranks by it.

  **The hard part is not the ranking, it is what a flag is allowed to CLAIM.**
  Every signal here is an inference from evidence that can be absent for
  innocent reasons, and a flag that overstates its evidence produces a list
  whose top rows cannot be acted on — which is worse than no list, because
  people stop reading it. Hence the exclusions each carry their reason: a plain
  `(def x <data>)` can never acquire trace evidence, a `^{:covers}` marker names
  a dispatch path nothing can see, and `:cljs` is reported as `:off-platform`
  rather than `:untested` because no test could ever cover it. When adding a
  signal, the question to answer first is what its absence could innocently mean.

  Coverage answers that question by CLASS rather than by exclusion: every row
  carries `:evidence`, so a reader is told whether a test ran the form, claimed
  it, merely reaches it in four hops, or nothing at all — instead of inferring
  it from the absence of a word. An exclusion list grows one bite at a time; a
  class the row states cannot silently acquire a fifth member.

  Read-only over the store value plus the session's trace map; the done-point's
  own findings live in `slopp.ops.done`."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.edit.modules :as edit.modules]
            [slopp.index.refs :as refs]
            [slopp.index :as index]
            [slopp.store.render :as store.render]
            [slopp.store :as store] [slopp.ops.done :as done] [slopp.store.db :as db] [slopp.rules.markers :as markers] [slopp.ops.engine :as engine] [slopp.rules.mutate :as mutate]))

(defn red-signal
  "What the red index says about one form, for a review row. `reds-by-fid` is
  {form-id distinct-test-count} (nil when there is no durable store — then say
  nothing rather than zero); `recent` the form ids changed since the last
  commit point; `traced` the form's observed covering-test count; `created`
  the form ids CREATED in that window.

  `:reds` is reported for every form the index can speak about. `:never-red`
  is raised only where the claim is precise: an EXISTING form changed in
  current work, that tests demonstrably exercise, which no test has ever failed
  beside. Two exemptions, both measured or structural: an old form's quiet
  history is absence of evidence, not a finding; and a form created in current
  work cannot be credited at all, because its red-first red was recorded before
  the form existed (`assertions-never-red` grades those). It stays a PROXY: a
  behaviour-preserving refactor is also never red, which is why oracle strength
  proper is mutation's question, not this flag's."
  ([reds-by-fid recent fid traced] (red-signal reds-by-fid recent fid traced #{}))
  ([reds-by-fid recent fid traced created]
   (if (nil? reds-by-fid)
     {}
     (let [n (get reds-by-fid fid 0)]
       (cond-> {:reds n}
         (and (zero? n) (pos? traced) (contains? recent fid)
              (not (contains? created fid)))
         (assoc :never-red true))))))

(def escape-weights
  "Risk each escape marker adds to a review row; a marker not listed weighs 1.
  `:unsafe` weighs 3: it waives the dialect, the rules that keep the form
  verifiable at all. `:reveals` weighs 3: it hands a secret's real value to the
  world. Every other escape weighs 1: it waives one named rule and says why."
  {:unsafe 3 :reveals 3})

(defn form-escapes
  "The sorted vector of escape markers the form `s` (its sexpr) carries.
  The set comes from `markers/marker-registry` entries of `:kind :escape`, never
  a hand list, so a new escape marker is ranked by being declared. A marker
  `:on :form` is read off the whole form's metadata; `:on :name` off the
  defined symbol's."
  [s]
  (vec (sort (for [{:keys [marker on kind]} markers/marker-registry
                   :when (and (= :escape kind)
                              (get (if (= :form on)
                                     (meta s)
                                     (when (seq? s) (meta (second s))))
                                   marker))]
               marker))))

(defn flagged-tests
  "The standing flag_test objections on this line: [{:test qsym :reason :agent
  :at}], oldest first. An objection stands until the test it names is written
  again after it, or deleted: the human editing the test IS the decision, and
  a flag that never retired would outlive the test it disputes. Read from the
  journal when the session has one (`:db`), so a commit point does not hide a
  flag; a bare fixture's value carries its own `:deltas`."
  [session st]
  (let [ds   (if-let [conn (:db @session)]
               (db/line-deltas conn (engine/session-line session)
                               :ops [:flag-test :add :ingest :replace :subform :patch :delete])
               (:deltas st))
        fids (fn [d] (if (:form-id d) [(:form-id d)] (:form-ids d)))
        live (reduce (fn [live d]
                       (if (= :flag-test (:op d))
                         (let [t (:test d)
                               e (when (and (symbol? t) (namespace t))
                                   (store/form-named st (symbol (namespace t)) (symbol (name t))))]
                           (if e
                             (assoc live t {:test t :reason (:reason d) :agent (:agent d)
                                            :at (:at d) :fid (:id e)})
                             live))
                         (let [touched (set (fids d))]
                           (into {} (remove (fn [[_ f]] (contains? touched (:fid f)))) live))))
                     {} ds)]
    (vec (sort-by #(or (:at %) 0) (map #(dissoc % :fid) (vals live))))))

^:reads (defn ^:export review-scan
  "Whole-codebase (or one-ns) REVIEW TRIAGE — the fileless store's answer
  to 'where do I look first'. For every form it surfaces what the store
  knows and files don't: tested?, blast radius (callers), size, lint
  findings, undocumented public surface, effects — then RISK-RANKS so a
  reviewer reads the dangerous forms first instead of eyeballing
  everything. Every row says WHICH KIND of evidence stands behind it, in
  `:evidence`: `:observed` (the trace watched a test exercise it — the only
  one that means verified), `:declared` (a `^{:covers}` marker names the
  dispatch/data path neither reach nor trace can see), `:static` (some form
  in a test namespace reaches it in the call graph, with `:hops` saying how
  far — this is what survives ^:external tests, which never touch the
  in-image trace map, and at three or four hops it is nearly free),
  `:off-platform` (the JVM oracle cannot load it at all) or `:none`.
  `:evidence` in the summary is that split over the whole flagged list.
  `:none` is WIDER than the `:untested` flag on purpose: a plain
  (def x <data>) has no invocation to observe, so it is honestly
  evidence-less and is nonetheless not a gap anyone can close. ONE analysis pass (analyze + lint
  share the memoized kondo). Drill into a flagged form with query_slice.
  `:ns` scopes to one namespace; `:limit` caps the rows (default 25), the
  tail in :omitted. Each row also carries `:escapes`, the sorted escape
  markers the form uses (every `:kind :escape` entry of the marker registry),
  and each adds to `:risk`: `:unsafe` +3 (waives the dialect), `:reveals` +3
  (hands a secret to the world), any other +1 (waives one named rule). Clean forms drop out.
  `:flagged-tests` carries the standing flag_test objections (see
  [[flagged-tests]]), and a row the flagged test reaches is flagged
  `:flagged-test`: its oracle is disputed, and a reviewer decides which is wrong."
  [session & {:keys [ns limit] :or {limit 25}}]
  (let [st    (:store @session)
        nses  (if ns [(symbol (str ns))] (sort (keys (:namespaces st))))
        ;; retired: the graph owns the known-set
        tmap  (:test-map @session)
        ;; the red index (form_reds), read ONCE: {form-id distinct-tests}. nil
        ;; without a durable store, so rows say nothing rather than zero
        recent (into #{} (mapcat (fn [d] (if (:form-id d) [(:form-id d)] (:form-ids d))))
                     (:recent st))
        created (into #{} (comp (filter #(#{:add :ingest} (:op %)))
                                (mapcat (fn [d] (if (:form-id d) [(:form-id d)] (:form-ids d)))))
                      (:recent st))
        reds-by-fid (when-let [conn (:db @session)]
                      (frequencies
                       (map :form-id
                            (db/reds-for conn (vec (for [nsx nses e (store/forms st nsx)
                                                         :when (:name e)]
                                                     (:id e)))))))
        rendered (into {} (map (fn [n] [n (store.render/render-ns st n)])) nses)
        ;; WEAK ORACLE, as of the last done: forms whose tests ran them and
        ;; still passed with a mutant in place, so the tests watch the code
        ;; without constraining it. Read off the latest :done in the window;
        ;; a later done that changes the form asks again
        weak    (into (into #{} (map :form)
                            (some-> (last (filter #(= :done (:op %)) (:recent st)))
                                    :findings :mutants-survived))
                      (when-let [conn (:db @session)]
                        (mutate/weak-forms st (db/measurements conn "mutation" nil))))
        ;; FLAGGED TESTS: an agent was refused an edit to a test grading its
        ;; own change and recorded an objection instead (flag_test). The
        ;; human decides, and the objection stands until the test is written
        ;; again after it. Read off the line's journal, so a commit point does
        ;; not hide it. The rows the flagged test reaches through the trace
        ;; map carry the flag: the deftest itself is skipped below.
        flagged (flagged-tests session st)
        flagged-forms (into #{} (mapcat #(get tmap (:test %))) flagged)
        ns-lint (into (sorted-map)
                      (for [[nsx src] rendered
                            :let [c (count (for [f (index/lint src (store/kondo-lang st nsx))
                                                 :let [e (store.render/owner-form st nsx (:row f) (:col f))
                                                       s (when e (try (n/sexpr (:node e)) (catch Exception _ nil)))]
                                                 :when (and (seq? s) (= 'ns (first s))
                                                            (not (done/marked-unused? st nsx f)))]
                                             f))]
                            :when (pos? c)]
                        [nsx c]))
        ;; one analyze per ns → every store-internal call edge
        ;; THE reference graph — whole-store edges (carriers included), so
        ;; caller counts are true even in :ns-scoped scans
        usages (for [r (refs/refs st)
                     :when (and (not= :declared (:via r)) (:from-var r))]
                 [(symbol (str (:from-ns r)) (str (:from-var r)))
                  (symbol (str (:to-ns r)) (str (:to-name r)))])
        blast (frequencies (for [[from to] usages :when (not= from to)] to))
        adj   (reduce (fn [m [from to]] (update m from (fnil conj #{}) to)) {} usages)
        ;; STATIC coverage: everything reachable from a test ns's forms.
        ;; Seeded from the WHOLE store, exactly like `adj` and `covered-declared`
        ;; above and for the same reason: whether a test reaches this form is
        ;; not a fact about how widely the caller asked. Seeding from the scoped
        ;; `nses` meant a {ns "x"} scan seeded from ONE namespace — so unless
        ;; that namespace was itself a -test, nothing seeded it and every form
        ;; came back :untested. Measured before the fix: 18 such rows in
        ;; `slopp.index.refs` alone, `covered-by` among them.
        test-seed (set (for [nsx (keys (:namespaces st))
                             :when (str/ends-with? (str nsx) "-test")
                             e (store/forms st nsx) :when (:name e)]
                         (symbol (str nsx) (str (:name e)))))
        ;; {form hops}, not a set. "A test namespace calls this directly" and
        ;; "a chain of six calls reaches this" are the same word today, and the
        ;; second is nearly free: measured on this store, 1120 of 1285
        ;; production forms are covered this way and 297 of those sit at three
        ;; hops or more, where `test → ops → edit → store` marks the store form
        ;; covered. The closure stays UNBOUNDED on purpose — bounding it is what
        ;; `refs/covered-by` does, and a bound here would flip every deep form
        ;; to a false :untested, which is why this cannot simply call it. The
        ;; distance is REPORTED instead of the reach being narrowed.
        covered-static (loop [seen {} frontier test-seed hop 0]
                         (if (empty? frontier)
                           seen
                           (let [seen' (reduce #(if (contains? %1 %2) %1 (assoc %1 %2 hop))
                                               seen frontier)]
                             (recur seen' (into #{} (comp (mapcat adj) (remove seen'))
                                                frontier)
                                    (inc hop)))))
        ;; DECLARED coverage: a ^{:covers} marker names a form the graph and
        ;; the trace both miss (dispatch/data/child-image). Whole-store so
        ;; :ns scoping still sees the declaration in the test namespace.
        covered-declared (set (for [r (refs/refs st)
                                    :when (= :covers (:marker r))]
                                (symbol (str (:to-ns r)) (str (:to-name r)))))
        ;; DECLARED LIVENESS: something outside the store references this —
        ;; `^:entry-point`, `^:unused-ok`, and the D-web declarations (a
        ;; `:http/path` endpoint the dispatcher calls, a `:http/read` /
        ;; `:http/effect` performer the interpreter resolves by name). The graph
        ;; already models every one as a `:via :declared` edge from `:external`.
        ;;
        ;; This used to be five hand-written metadata checks a few lines below,
        ;; re-deriving from `(meta (second s))` exactly what `usages` had just
        ;; discarded by filtering `:declared` out — and each was added only when
        ;; its absence bit somebody. Excluding declared edges from the CALLER
        ;; COUNT is right (the framework is not an in-store call site and would
        ;; inflate blast radius); deriving LIVENESS from that same count is what
        ;; was wrong. Two questions, one number.
        ;;
        ;; `:covers` is excluded because coverage is not liveness — the same
        ;; distinction `unused-report` makes.
        declared-alive (set (for [r (refs/refs st)
                                 :when (and (= :declared (:via r))
                                            (not= :covers (:marker r)))]
                             (symbol (str (:to-ns r)) (str (:to-name r)))))
        lint-by-form (frequencies
                      (for [[nsx src] rendered
                            f (index/lint src (store/kondo-lang st nsx))
                            :let [e (store.render/owner-form st nsx (:row f) (:col f))]
                            :when e]
                        (symbol (str nsx) (str (or (:name e) (:id e))))))
        rows (for [nsx nses
                   e (store/forms st nsx)
                   :when (:name e)
                   :let [nm       (:name e)
                         q        (symbol (str nsx) (str nm))
                         s        (try (n/sexpr (:node e)) (catch Exception _ nil))
                         skip?    (and (seq? s) (contains? '#{deftest ns} (first s)))
                         test?    (str/ends-with? (str nsx) "-test")
                         traced   (let [ks (store/form-trace-keys nsx e)]
                                    ;; any name the form defines carries evidence (#129)
                                    (count (keep (fn [[t fs]] (when (some fs ks) t)) tmap)))
                         rs       (red-signal reds-by-fid recent (:id e) traced created)
                         callers  (get blast q 0)
                         loc      (count (str/split-lines (n/string (:node e))))
                         lints    (get lint-by-form q 0)
                         bang?    (str/ends-with? (str nm) "!")
                         doc?     (some? (edit.modules/missing-doc-warning st nsx nm))
                         ;; zero-caller PUBLICS need the whole graph — only a
                         ;; full scan sees every caller, so :ns scoping skips it
                         unused   ;; the whole-store graph makes caller counts true even
                         ;; under :ns scoping — the flag works everywhere
                         (and (not test?)
                              (zero? callers)
                              (seq? s)
                              (contains? '#{defn def} (first s))
                              (not (:private (store/form-name-meta e)))
                              ;; ONE question, asked of the graph: does anything
                              ;; outside the store declare that it references
                              ;; this? Covers ^:entry-point, ^:unused-ok and the
                              ;; three D-web declarations at once, and covers the
                              ;; NEXT one for free — `edit.refs/declared-refs` is
                              ;; where that vocabulary lives, so a marker added
                              ;; there arrives here without a second edit.
                              (not (contains? declared-alive q))
                              ;; the two that are genuinely not graph facts:
                              ;; ^:generated is machinery awaiting front-end
                              ;; calls, and -main is a naming convention
                              (not (:generated (store/form-name-meta e)))
                              (not= '-main nm))
                         ;; OUTSIDE THE ORACLE, not behind on tests. A :cljs
                         ;; namespace never loads into the JVM image, so no test
                         ;; can trace it and none can statically reach it from a
                         ;; test ns — "add a test" is advice nobody can take, and
                         ;; on a real store it dominated the ranking (15 of
                         ;; slopp-ui's 17 :untested rows). The reasoning was
                         ;; already here, keyed to the wrong fact: ^:generated was
                         ;; excluded BECAUSE it is :cljs. Ask the platform.
                         off-platform (and (not test?)
                                           (= :cljs (store/platform-for st nsx)))
                         untested (and (not test?)
                                       ;; a plain (def x <data>) has no invocation to
                                       ;; trace, so it can never acquire evidence —
                                       ;; flagging it is a finding nobody can discharge.
                                       ;; defn/defmulti stay flaggable: they are callable.
                                       (not (and (seq? s) (= 'def (first s))))
                                       (zero? traced)
                                       (not (contains? covered-static q))
                                       ;; a ^{:covers} declaration discharges :untested —
                                       ;; it names the exact dispatch path neither the
                                       ;; trace nor static reach can see.
                                       (not (contains? covered-declared q))
                                       ;; the oracle cannot reach it at all — reported as
                                       ;; :off-platform below, which says so
                                       (not off-platform)
                                       ;; a ^:generated wrapper is machine-written surface
                                       ;; nobody hand-tests; the :cljc contracts ns is
                                       ;; generated too, so this is not just the :cljs case
                                       (not (:generated (meta (second s)))))
                         ;; WHICH KIND of evidence, strongest first. The words
                         ;; are `refs/covered-by`'s — one distinction, spelled
                         ;; one way, so a reader who has seen :via :static on a
                         ;; form page does not have to learn a second vocabulary
                         ;; for the same fact here.
                         ;;
                         ;; :none is WIDER than :untested and deliberately so.
                         ;; :evidence answers "what do we know", :untested
                         ;; answers "is this worth an afternoon" — a plain
                         ;; (def x <data>) has no invocation to observe, so it
                         ;; is honestly evidence-less and is nonetheless not a
                         ;; gap anyone can close.
                         hops     (get covered-static q)
                         evidence (cond
                                    (pos? traced)                  :observed
                                    (contains? covered-declared q) :declared
                                    hops                           :static
                                    off-platform                   :off-platform
                                    :else                          :none)
                         escapes  (form-escapes s)
                         flags    (cond-> []
                                    untested       (conj :untested)
                                    off-platform   (conj :off-platform)
                                    unused         (conj :unused)
                                    (>= callers 8) (conj :high-blast)
                                    (>= loc 50)    (conj :large)
                                    (pos? lints)   (conj :lint)
                                    doc?           (conj :undocumented)
                                    bang?          (conj :effectful)
                                    (:never-red rs) (conj :never-red)
                                    (seq escapes)  (conj :escape)
                                    (weak q)       (conj :weak-oracle)
                                    (flagged-forms q) (conj :flagged-test))
                         risk     (+ (if untested 4 0)
                                     ;; 1, not untested's 4: a standing fact about
                                     ;; the platform is worth SEEING and is not a
                                     ;; gap anyone can close, so it must never
                                     ;; outrank code that could be tested and isn't
                                     (if off-platform 1 0)
                                     (if unused 2 0)
                                     (cond (>= callers 8) 2 (>= callers 3) 1 :else 0)
                                     (cond (>= loc 50) 2 (>= loc 30) 1 :else 0)
                                     (min 2 lints)
                                     (if doc? 1 0)
                                     (if bang? 1 0)
                                     ;; 1: a covered form in current work whose
                                     ;; tests have never failed beside it — the
                                     ;; oracle may not constrain it
                                     (if (:never-red rs) 1 0)
                                     (reduce + 0 (map #(get escape-weights % 1) escapes))
                                     ;; 2: green tests that cannot fail on a
                                     ;; fault are the false comfort a reviewer
                                     ;; most needs to see
                                     (if (weak q) 2 0)
                                     ;; 2: the oracle grading this form is
                                     ;; disputed by the agent that wrote it;
                                     ;; a reviewer decides which is wrong
                                     (if (flagged-forms q) 2 0))]
                   :when (and (not skip?) (pos? risk))]
               (cond-> {:form q :risk risk :loc loc :callers callers
                        :covered traced :flags flags :evidence evidence}
                 (= :static evidence) (assoc :hops hops)
                 (seq escapes)        (assoc :escapes escapes)
                 (contains? rs :reds) (assoc :reds (:reds rs))))
        ranked (sort-by (juxt (comp - :risk) (comp - :callers) (comp str :form)) rows)]
    (cond-> {:reviewed (if ns (str ns) (str (count nses) " namespaces"))
             :forms    (reduce + 0 (map #(count (filter :name (store/forms st %))) nses))
             :flagged  (count rows)
             :top      (vec (take limit ranked))
             :totals   (into (sorted-map) (frequencies (mapcat :flags rows)))
             ;; WHAT the triage list rests on, over the same rows :totals counts.
             ;; Nobody reads 954 rows, so "892 of these are covered only by
             ;; static reach" is a fact about the suite that no amount of
             ;; per-row reading adds up to — and it is the fact that decides
             ;; whether the quiet rows deserve to be quiet.
             :evidence (into (sorted-map) (frequencies (map :evidence rows)))
             ;; the SHAPE of form sizes, not just how many cross 50 loc:
             ;; decomposing a god-form ADDS forms, so the :large count can
             ;; rise while the codebase genuinely improves. Max and median
             ;; move the right way.
             :loc      (let [sized (for [nsx nses
                                         e (store/forms st nsx)
                                         :when (:name e)]
                                     [(symbol (str nsx) (str (:name e)))
                                      (count (str/split-lines (n/string (:node e))))])
                             ls    (sort (map second sized))
                             n     (count ls)]
                         (when (pos? n)
                           {:max     (last ls)
                            :largest (first (last (sort-by second sized)))
                            :p95     (nth ls (min (dec n) (int (* 0.95 n))))
                            :median  (nth ls (quot n 2))}))}
      ;; NAMESPACE grain, so it cannot ride :top (which ranks forms). A
      ;; namespace that never says what it is FOR is the gap a newcomer hits
      ;; first, and the one thing no tool can derive around — the inventory
      ;; is already shown everywhere.
      true (assoc :purpose
                  (let [ws (keep #(edit.modules/namespace-purpose-warning st %) nses)]
                    (cond-> {:stated (- (count nses) (count ws)) :missing (count ws)}
                      (seq ws) (assoc :namespaces (vec (sort (map :ns ws)))
                                      :teach (:teach (first ws))))))
      (seq ns-lint)          (assoc :ns-lint ns-lint)
      ;; the standing objections, whole: a flag names a test and a reason,
      ;; and its rows above only say that one exists
      (seq flagged)          (assoc :flagged-tests (mapv #(update % :test str) flagged))
      (> (count rows) limit) (assoc :omitted (- (count rows) limit)))))
