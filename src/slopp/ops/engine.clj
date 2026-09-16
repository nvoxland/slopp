(ns slopp.ops.engine
  "The write ENGINE — image lifecycle, the rebasing commit, and verification.

  Every operation in `slopp.api` is a pure transform handed to `rebased-write!`
  or its group sibling, which runs it inside a `swap!` so concurrent writes to
  DIFFERENT forms rebase and land without locks, gates the result once before
  committing, hot-loads it, and verifies exactly the tests the change reaches.
  The operations above supply intent; the sequencing lives here.

  The practical consequence, and it decides where fixes belong: a rule
  implemented HERE lands for every write, and one implemented in an operation
  lands for that operation. Four gates were once hand-pasted at four write
  sites because the chokepoint was not used, and every later fix to them had
  to be applied four times."
  (:require [clojure.edn :as edn] [clojure.set :as set] [clojure.string :as str] [rewrite-clj.node :as n] [slopp.store.db :as db] [slopp.edit :as edit] [slopp.image :as image] [slopp.store.render :as store.render] [slopp.image.repl :as repl] [slopp.store :as store] [slopp.index.analyze :as analyze] [slopp.edit.hotload :as hotload] [slopp.edit.lintgate :as lintgate] [rewrite-clj.parser :as p] [slopp.rules.http :as rules.http] [slopp.index.refs :as refs] [slopp.image.currency :as image.currency] [slopp.kernel.boot :as boot] [clojure.java.io :as io] [slopp.project.capabilities :as capabilities] [slopp.index.crossings :as crossings]))

^{:auto-declare "mutual recursion: adopt-line!, commit-appended!, follow-branch-if-idle!, rebased-write!, refresh-cache!"}
(declare adopt-line! commit-appended! follow-branch-if-idle! rebased-write! refresh-cache!)

(def ^{:export "slopp.concurrency"} ^:dynamic *pre-commit-hook*
  "Test seam (item 4): invoked between an op's hot-load and its commit CAS to
  simulate a concurrent competitor deterministically. Never set in production.
  Exported to the contention specs that bind it; package-private otherwise."
  nil)

^:reads
(defn session-identity
  "The identity a fresh session starts with when the caller names none: a
  generated unique id.

  **No environment is read here, deliberately.** Identity keys a THREAD, so
  it must belong to a conversation — and most sessions are not one. Hundreds
  of tests call `open!` with no agent, and every image and test-runner JVM
  inherits its parent's environment, so an id read at this depth would be
  adopted by every subprocess slopp spawns: each one would claim the driving
  agent's thread and stamp its deltas. That was measured, not feared — four
  mcp-tests went red the moment this function read the environment, every
  one of them because a test session had become the developer's own session.

  So the thread an agent writes on is what the agent PASSES on every write,
  and the daemon mints each session's own label at attach; both arrive here
  as an explicit `:slopp.ops/agent-id`. What is left is the honest default
  for everyone else: this session is nobody's continuation, and it says so
  instead of borrowing a name."
  []
  (str "s-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

(defn load-trace
  "The persisted trace map, pruned to tests/forms that still exist in `store`
  (names move between sessions — including renames that never re-persisted —
  so stale entries drop out and narrowing stays conservative)."
  [conn store]
  (when conn
    (let [raw   (try (some-> (db/get-meta conn "trace-map") edn/read-string)
                     (catch Exception _ nil))
          live? (fn [qsym]
                  (let [n (some-> (namespace qsym) symbol)]
                    (boolean (and n (store/form-named store n (symbol (name qsym)))))))]
      (into {}
            (keep (fn [[t forms]]
                    (when (live? t)
                      (let [fs (into #{} (filter live?) forms)]
                        (when (seq fs) [t fs])))))
            raw))))

(defn stub-missing-test-vars!
  "The GENERIC red-first seam (command-agnostic — every write path that
  compiles through the image inherits it, including future ops): when a
  namespace with TESTS fails to load, intern a throwing stub in `image` for
  every store var its tests reference but nothing defines — aliased and
  qualified calls via kondo rows, :refer'd names via the ns form (stubs
  precede the require, so the refer check passes) — then the caller retries
  the load and the spec lands as an honest RED naming the stub. Never
  touches the store; the real implementation redefines the var. Returns the
  stubbed qsyms (nil when none — the failure wasn't red-first).

  A `-test` namespace counts whole. A namespace that keeps its deftests
  beside its code counts too — eval10's terrain does, and a test-first
  `ns_create` there failed to load instead of landing red, so the agent
  wrote the stubs by hand — but only usages FROM its deftest forms are
  stubbed there: a production form's genuine unresolved symbol stays the
  compile error it is."
  [image candidate ns-syms]
  (let [nses    (set (keys (:namespaces candidate)))
        head    (fn [e] (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                          (when (seq? s) (first s))))
        deftests (fn [t] (into #{} (comp (filter #(= 'deftest (head %))) (keep :name))
                               (store/forms candidate t)))
        test-ns? (fn [t] (str/ends-with? (str t) "-test"))
        tests   (filter #(or (test-ns? %) (seq (deftests %))) ns-syms)
        ns-form (fn [t]
                  (some #(let [s (try (n/sexpr (:node %)) (catch Exception _ nil))]
                           (when (and (seq? s) (= 'ns (first s))) s))
                        (store/forms candidate t)))
        missing (vec (distinct
                      (concat
                       (for [t tests
                             :let [from-tests (when-not (test-ns? t) (deftests t))]
                             u (:var-usages (analyze/analyze (store.render/render-ns candidate t)))
                             :when (and (contains? nses (:to u)) (:name u)
                                        (or (nil? from-tests) (contains? from-tests (:from-var u)))
                                        (not (store/form-named candidate (:to u) (:name u))))]
                         (symbol (str (:to u)) (str (:name u))))
                       (for [t tests
                             :when (test-ns? t)
                             :let [form (ns-form t)]
                             clause (when form
                                      (mapcat rest
                                              (filter #(and (seq? %) (= :require (first %)))
                                                      form)))
                             :when (and (vector? clause)
                                        (contains? nses (first clause)))
                             [k v] (partition 2 (rest clause))
                             :when (and (= :refer k) (vector? v))
                             sym v
                             :when (not (store/form-named candidate (first clause) sym))]
                         (symbol (str (first clause)) (str sym))))))]
    (doseq [q missing]
      (repl/eval! image
                  (format "(intern '%s '%s (fn [& _] (throw (ex-info \"red-first stub: %s is speced but not implemented\" {:red-first '%s}))))"
                          (namespace q) (name q) q q)))
    (when (seq missing) missing)))

(defn persist-trace!
  "Q3: the trace map survives the session — written to store meta so the NEXT
  session (or a CLI one-shot) starts with narrowing warm instead of
  {:ran 0 :affected :all}. Last writer wins; load-trace prunes stale names."
  [session]
  (when-let [conn (:db @session)]
    (db/set-meta! conn "trace-map" (pr-str (:test-map @session)))))

(defn with-ms
  "Attach total op wall time (item 2 observability)."
  [m t0]
  (if (map? m)
    (assoc m :ms (quot (- (System/nanoTime) t0) 1000000))
    m))

(defn green? [summary]
  (zero? (+ (:fail summary 0) (:error summary 0))))

(def reload-signature-res
  "Failure texts that smell like hot-reload staleness rather than logic bugs."
  [#"Unable to resolve symbol"
   #"Attempting to call unbound fn"
   #"No implementation of method"
   #"Var .* is unbound"])

(defn reload-signature? [failure]
  (let [s (str (:actual failure) " " (:message failure))]
    (or (boolean (some #(re-find % s) reload-signature-res))
        ;; same-named classes cast-failing against each other = redefined type
        (boolean
         (when-let [[_ c1 c2] (re-find #"class (\S+) cannot be cast to class (\S+)" s)]
           (= (last (str/split c1 #"\.")) (last (str/split c2 #"\."))))))))

(defn suspicious-red?
  "Could this red plausibly be image staleness rather than a genuine failure
  (D5.1)? Yes iff: no edit context; a truncated failure list; a
  reload-signature failure; or an UNEXPLAINED FLIP — a failing test whose
  traced form-set doesn't intersect the just-edited forms and which wasn't
  itself edited (this also catches value-capture staleness, since captured
  calls bypass the trace)."
  [session edited summary]
  (let [tmap       (:test-map @session)
        failures   (:failures summary)
        truncated? (> (+ (:fail summary 0) (:error summary 0)) (count failures))]
    (or (nil? edited)
        truncated?
        (boolean (some reload-signature? failures))
        (boolean
         (some (fn [f]
                 (let [t       (:test f)
                       touched (get tmap t)]
                   (or (nil? touched)
                       (and (not (contains? edited t))
                            (empty? (set/intersection touched edited))))))
               failures)))))

(defn implicate
  "Rock 2: annotate each failure with the just-changed forms that failing
  test actually exercises (trace map ∩ edited) — the correlation agents
  otherwise re-derive from raw expected/actual on every red.

  Each failure also carries `:attribution`, because that intersection has
  THREE outcomes and reporting only the hits collapsed the last two:

  - `:mine`     the test exercises a form this episode changed, or IS one
                — `:implicated` names which
  - `:foreign`  the test IS traced and its trace is disjoint from this
                episode's edits — somebody else's red
  - `:untraced` no trace for this test, so nothing was intersected and
                neither answer is available

  `:foreign` and `:untraced` both used to arrive as a missing `:implicated`
  key, leaving 'assume it is mine' as the only safe reading — which is how
  one agent's red comes to freeze every other agent's thread. Only
  `:foreign` is evidence of innocence. `:untraced` is the ABSENCE of
  evidence and must never be read as the presence of it, which is why an
  empty trace counts as untraced rather than as a disjoint one.

  The test's OWN name is checked against `edited` before its trace is,
  because the trace maps a test to the source forms it exercises and so can
  never name the test itself. Writing a failing test against code you did
  not touch is the ordinary red-first move, and reading its trace alone
  called it somebody else's."
  [summary tmap edited]
  (if-not (and (seq (:failures summary)) (seq edited))
    summary
    (let [edited (set edited)]
      (update summary :failures
              (fn [fs]
                (mapv (fn [f]
                        (let [own    (when (contains? edited (:test f)) [(:test f)])
                              traced (seq (get tmap (:test f)))
                              hits   (some->> traced set (set/intersection edited) seq)]
                          (cond
                            (or own hits)
                            (assoc f :attribution :mine
                                   :implicated (vec (sort (distinct (concat own hits)))))
                            traced (assoc f :attribution :foreign)
                            :else  (assoc f :attribution :untraced))))
                      fs))))))

(defn shape-episode-reds!
  "Mid-episode response diet (direction over repetition): full failure
  detail rides ONLY for tests newly red on THIS write; tests already
  reported red this episode compress to :still-red names; previously-red
  tests that ran clean report :went-green. The ledger lives on the
  session (:episode-reds) and the done-point (`boundary?` true) bypasses
  compression — the boundary always reports every standing red in full —
  and resets the ledger. Explicit test_run bypasses this shaping too
  (spot-checks get everything).

  **`:went-green` is derived from `:failed-tests` — the summary's complete
  list of failing test names — and never from the failure BLOCKS.** The
  blocks are capped for response size, so on a run with more failing
  assertions than the cap allows, a still-failing test simply has no block,
  which is indistinguishable here from having passed. Measured: a write
  announced a test green while an immediate re-run showed it failing with the
  message it had before the write. A false green is the worst direction for
  this signal — it is the one an agent reads to decide it is FINISHED — and it
  misfires only on large red runs, which is when the reader most needs it.

  When a summary carries no `:failed-tests` and its detail was demonstrably
  capped, no green is claimed and `:reds-uncertain` says why. That is not a
  hypothetical: the injected runtime is read off the reading process's own
  classpath, so a jar older than the store produces exactly this summary.
  Absence with a stated cause can be acted on; absence alone reads as \"nothing
  went green\", which is a different and wrong claim."
  [session summary affected scope boundary?]
  (let [prev     (or (:episode-reds @session) #{})
        blocks   (vec (:failures summary))
        named    (:failed-tests summary)
        capped?  (< (count blocks) (+ (:fail summary 0) (:error summary 0)))
        now-red  (if named
                   (set named)
                   (into #{} (keep :test) blocks))
        scope-ns (into #{} (map str) (if (sequential? scope) scope [scope]))
        ran      (if (seq affected)
                   (set affected)
                   (into #{} (filter #(contains? scope-ns (namespace %))) prev))
        blind?   (and (nil? named) capped?)
        greens   (if blind?
                   []
                   (vec (sort (remove now-red (filter ran prev)))))
        ;; a test the shaper could not observe stays on the ledger: dropping it
        ;; would report it as newly red next time, in full, having never left
        ledger   (-> prev (set/difference (set greens)) (into now-red))]
    (swap! session assoc :episode-reds (if boundary? now-red ledger))
    (if boundary?
      summary
      (let [new-blocks (vec (remove #(contains? prev (:test %)) blocks))
            stills     (vec (sort (filter prev now-red)))]
        (cond-> (assoc summary :failures new-blocks)
          (empty? new-blocks) (dissoc :failures)
          (seq stills)        (assoc :still-red stills)
          (seq greens)        (assoc :went-green greens)
          blind?              (assoc :reds-uncertain
                                     (str "the failure detail was capped at "
                                          (count blocks) " of " (+ (:fail summary 0)
                                                                   (:error summary 0))
                                          ", and this runner did not report the"
                                          " failing test names — so no test can"
                                          " be shown to have gone green on this"
                                          " run. Rebuild the jar to restore the"
                                          " signal; until then read :still-red"
                                          " as a floor, not a list"))
          blind?              (dissoc :went-green))))))

(defn test-ns?
  "Does `nsx` hold any deftest? (Inline tests count — Q13.)"
  [store nsx]
  (some #(str/starts-with? (str/triml (n/string (:node %))) "(deftest")
        (store/forms store nsx)))

(defn test-nses-reaching
  "Test namespaces (any ns holding a deftest) whose require-closure
  reaches one of `changed-nses` — the PROVABLE set of tests a change can
  affect (a test only exercises code it can load). The honest fallback
  scope when the trace map is silent."
  [store changed-nses]
  (let [changed (set changed-nses)]
    (vec (sort (for [t (keys (:namespaces store))
                     :when (and (test-ns? store t)
                                (seq (set/intersection
                                      (store/ns-closure store t)
                                      changed)))]
                 t)))))

(defn rename-in-trace
  "Carry the observed test→form map across a rename (old qsym → new qsym)."
  [tmap qold qnew]
  (into {}
        (map (fn [[t forms]]
               [(if (= t qold) qnew t)
                (into #{} (map #(if (= % qold) qnew %)) forms)]))
        tmap))

(defn external-test-nses
  "Of `nses`, those defining at least one ^:external deftest — tests only
  the EXTERNAL tier can execute (they spawn sessions/images; in-image runs
  skip them). The done-point uses this to route impacted tests to the
  right tier without the agent choosing tiers."
  [store nses]
  (vec (for [nsx nses
             :when (some (fn [e]
                           (let [s (try (n/sexpr (:node e))
                                        (catch Exception _ nil))]
                             (and (seq? s)
                                  (= 'deftest (first s))
                                  (boolean (:external (meta (second s)))))))
                         (store/forms store nsx))]
         nsx)))

(defn test-var-tiers
  "Plain deftest names of `ns-sym` split by execution tier:
   {:image [...] :external [...]}. `^:external` tests spawn images / recurse,
   so the IN-IMAGE runner must skip them (they only behave in the external
   tier) — this is what lets `traced-run!` defer them as :external-pending
   instead of running (and false-greening) them in-image."
  [store ns-sym]
  (reduce (fn [m e]
            (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
              (if (and (seq? s) (= 'deftest (first s)))
                (update m (if (:external (meta (second s))) :external :image)
                        (fnil conj []) (second s))
                m)))
          {:image [] :external []}
          (store/forms store ns-sym)))

(defn absorb-trace!
  "Merge an EXTERNAL-tier trace (#121) into the session's test-map and persist
  it (Q3), exactly as `traced-run!` does for the in-image tier — one test-map,
  one shape, whichever tier observed it. No-op on nil/empty: `read-traces`
  returns nil when the build carried no trace runner, and 'not traced' must
  never overwrite what another tier observed.

  Plain `merge`, not `merge-with into`: a fresh run of a test is the
  AUTHORITATIVE current set for that test — unioning would accumulate forms it
  no longer touches and quietly rot the narrowing."
  [session trace]
  (when (seq trace)
    (swap! session update :test-map merge trace)
    (persist-trace! session)))

(defn external-among
  "Of qualified test syms `tests`, those tagged ^:external — the ones only the
  EXTERNAL tier can execute.

  The routing half of affected-test selection (#127). `affected-tests` names the
  tests a change reaches; this says which of them the in-image runner had to
  defer, so `done!` can hand exactly those to the external tier instead of
  re-deriving a set from the require-closure. That closure selects a median 43
  of 46 external test namespaces (measured over every source ns 2026-07-17) —
  it is not narrowing, it is 'everything' with rounding.

  Empty is NOT the same as a silent trace: it means the evidence names tests and
  none of them are external, so the external tier has nothing to do. A silent
  trace is `affected-tests` returning nil, and that must still fall back to the
  closure."
  [store tests]
  (vec (sort (mapcat (fn [[nsx syms]]
                       (let [iso (set (:external (test-var-tiers store nsx)))]
                         (filter #(iso (symbol (name %))) syms)))
                     (group-by (comp symbol namespace) tests)))))

(defn covering-test-nses
  "Test namespaces whose requires REACH any of `ns-syms` — the verification
  scope to fall back on when trace evidence is missing.

  The old fallback ran tests IN the touched PRODUCTION namespaces, which
  contain none: on slopp's own store `test_run {ns \"slopp.git\"}` runs zero
  tests while five test namespaces cover it. So a write without trace evidence
  verified NOTHING while still reporting a result — and a multi-form refactor,
  least likely to carry complete evidence, was the most exposed of all.

  Naming cannot answer this. `slopp.git` is covered by
  `slopp.git-projection-test`, not `slopp.git-test`, so an `x` → `x-test`
  heuristic finds nothing here. Only the require graph knows, and it is
  walked TRANSITIVELY so a test reaching the change through one hop counts.

  Returns a sorted vector, empty when genuinely nothing covers `ns-syms` —
  which is a real answer, and the caller reports it as such rather than as a
  pass."
  [store ns-syms]
  (let [known   (set (keys (:namespaces store)))
        targets (set ns-syms)
        reqs    (memoize (fn [n] (filter known (store/ns-requires store n))))
        reaches? (fn [t]
                   (loop [seen #{}, queue [t]]
                     (if-let [n (first queue)]
                       (cond
                         (seen n)    (recur seen (rest queue))
                         (targets n) true
                         :else       (recur (conj seen n)
                                            (into (vec (rest queue)) (reqs n))))
                       false)))]
    (->> known
         (filter store.render/test-ns?)
         (filter reaches?)
         sort
         vec)))

(defn load-observations
  "Every persisted observation, `{meta-key raw-edn}`, or `{}` without a db —
  the sibling of `load-trace`. Observations are durable (written by
  `remember-observation!`) but READ constantly by the card view, so they load
  once into session state rather than making every card a db query. That is
  what lets `slopp.read.orient` stay off `slopp.store.db`."
  [conn]
  (if conn
    (or (db/meta-with-prefix conn "observed/") {})
    {}))

(defn- ensure-db!
  "The session's journal connection, CREATING the store on first use when the
  session has a dir but no store yet.

  This is the only place a directory becomes slopp-managed implicitly, and
  it is deliberately on the WRITE path: `external/open!` no longer creates a
  store just because the MCP server was launched somewhere, so a session on
  an unadopted dir runs cache-only until real work arrives. Returns nil for
  a dirless session, which stays ephemeral forever."
  [session]
  (or (:db @session)
      (when-let [dir (:dir @session)]
        (let [conn (db/open! dir)
              s    (swap! session update :db #(or % conn))]
          ;; a concurrent writer may have won the race — keep the winner's
          ;; connection and release ours rather than leaking it
          (when-not (identical? conn (:db s))
            (.close ^java.sql.Connection conn))
          (:db s)))))

(defn inert-ns-require-change?
  "True when an ns-form edit only ADDED require specs that cannot change the
  resolution or load behaviour of anything already compiled: alias-only
  vectors (`[lib :as a]`) naming IN-STORE namespaces whose require-CLOSURE
  registers no methods. Everything else — :refer (resolution can shift),
  removals or renames, out-of-store libs (load effects unknown), a required
  ns whose closure LOADS defmethods, ANY metadata change on the ns form, any
  non-require edit — is not inert. Conservative: an unreadable or absent
  baseline answers false.

  `old-src` is the ns form's source at the baseline to diff against, and the
  caller names it: the write path hands the delta immediately prior
  (`prior-source`, one edit); the done path hands the LAST-DONE source so a
  multi-edit episode where an earlier edit added a :refer isn't masked by a
  later alias-only edit (review V-F3). A pure question over a value and a
  string — the journal read that finds the baseline stays with the caller.

  frictions #2: ns_add_require on slopp.api invalidated 331 external tests
  for an edit whose blast radius is zero — the require-closure fallback
  treated a require-list touch as a code change to the whole namespace."
  [store fid old-src]
  (let [read* (fn [s] (try (n/sexpr (p/parse-string (str s)))
                           (catch Exception _ nil)))
        e     (store/form-by-id store fid)
        new   (some-> e :node n/sexpr)
        old   (read* old-src)
        req?  (fn [c] (and (seq? c) (= :require (first c))))
        reqs  (fn [form] (set (mapcat rest (filter req? (drop 2 form)))))
        ;; the ns form with its require clauses stripped — everything whose
        ;; change is NOT a plain require add: name, docstring, :import,
        ;; :require-macros, :gen-class …
        non-req (fn [form] (cons (second form) (remove req? (drop 2 form))))
        ;; metadata is invisible to = (on symbols and colls alike), and a
        ;; test-selector tag / load hint on the ns name is behaviourally
        ;; live — compare the metadata of every node explicitly (V-F2)
        metas   (fn [form] (mapv meta (tree-seq coll? seq form)))
        ;; a required ns is quiet only if its WHOLE in-store closure
        ;; registers no methods — loading it loads them all (V-F1)
        quiet?  (fn [lib]
                  (not-any? store/method-carrying?
                            (mapcat #(store/forms store %)
                                    (store/ns-closure store lib))))]
    (boolean
     (and (seq? old) (seq? new)
          (= 'ns (first old) (first new))
          (= (non-req old) (non-req new))
          (= (metas (non-req old)) (metas (non-req new)))
          (set/subset? (reqs old) (reqs new))
          (let [added (set/difference (reqs new) (reqs old))]
            (and (seq added)
                 (every? (fn [spec]
                           (and (vector? spec)
                                (symbol? (first spec))
                                (even? (count (rest spec)))
                                (every? #(= :as %) (take-nth 2 (rest spec)))
                                (contains? (:namespaces store) (first spec))
                                (quiet? (first spec))))
                         added)))))))

(def cljs-deferred-summary
  "Verification summary for a write to a :cljs (non-jvm-loadable) namespace.
  Such code references js/* / the DOM and never loads into the JVM oracle, so
  there is nothing to run here — its red/green comes from the ClojureScript
  compiler (compile_client), not the test suite. Reported :unverified with a
  reason that says the check is DEFERRED, distinct from :no-covering-tests (a
  coverage gap the agent should close). D-web-cljs."
  {:test 0 :pass 0 :status :unverified :reason :cljs-deferred-to-compile})

(defn- ran-nothing
  "The summary for an in-image run that did not HAPPEN, carrying what the runner
  said instead.

  `image/traced-test-run` answers `{:summary … :trace …}`, and answers something
  else when the eval threw — the exception arrives as printed text. Destructuring
  that gave nil, and nil flowed on to callers whose `cond->` dressed it as a map
  with no counts in it. Counts-of-zero is the one shape that must never come
  back, because every caller reads it as a clean run: `full_check` reported its
  in-image tier green having run nothing at all.

  So `:error 1` — every status check in the codebase sums `:fail` and `:error`,
  which makes this red everywhere without a new convention — and the runner's
  own words ride along, bounded, because the next cause will be a different one
  and undiagnosable without them."
  [result]
  (let [said (str result)]
    {:test 0 :pass 0 :fail 0 :error 1 :type :summary
     :failures [{:test     'slopp.image/traced-test-run
                 :type     :error
                 :message  (str "the in-image runner returned no summary — the"
                                " run did not happen, so this is not a green")
                 :expected "{:summary {...} :trace {...}}"
                 :actual   (subs said 0 (min 400 (count said)))}]}))

(defn traced-run!
  "Run `test-ns`'s tests (all, or `only` names) with form-tracing; absorb the
  observed test→form map into the session (persisted — Q3); return the summary.
  `skip-integration?` drops `^:integration` tests (M5, the fast-path default).
  The in-image tier NEVER runs `^:external` tests (they spawn images / recurse
  and only behave in the external tier): any in scope are filtered OUT of the
  run and reported as `:external-pending` on the summary — never executed
  in-image (which would false-green/false-red them). The done-point / merge
  gate runs them for real in the external tier.

  A result carrying no `:summary` becomes [[ran-nothing]] rather than nil: the
  runner threw, and a run that did not happen has to read as red, not as a run
  that found nothing."
  [session test-ns only & [skip-integration?]]
  (let [{:keys [image store]} @session
        nses     (if (coll? test-ns) test-ns [test-ns])
        external (into #{} (mapcat #(:external (test-var-tiers store %))) nses)
        run!     (fn [only']
                   (let [res (image/traced-test-run
                              image store test-ns :only only'
                              :skip-integration? skip-integration?)
                         {:keys [summary trace]} (when (map? res) res)]
                     (swap! session update :test-map merge trace)
                     (persist-trace! session)
                     (or summary (ran-nothing res))))]
    (if (empty? external)
      ;; no ^:external tests in scope — original path, untouched
      (run! only)
      ;; some are external — run only the in-image tier, defer the rest
      (let [pending (if only (filterv external only) (vec external))
            only'   (if only
                      (vec (remove external only))
                      (vec (mapcat #(:image (test-var-tiers store %)) nses)))
            summary (if (empty? only')
                      ;; every impacted test is external — nothing to run here
                      {:test 0 :pass 0 :fail 0 :error 0 :type :summary}
                      (run! only'))]
        (cond-> summary
          (seq pending)
          (assoc :external-pending
                 (cond-> {:count (count pending)
                          :tests (vec (take 5 (sort pending)))}
                   (> (count pending) 5)
                   (assoc :note (str "first 5 shown — the done-point / merge gate"
                                     " runs them all in the external tier")))))))))

(defn load-error-message
  "The message to report for a `hot-load-all!` result — nil when it loaded.

  When the heal's retry failed DIFFERENTLY from the first attempt, the
  post-heal error is an artifact of the RECOVERY and the pre-heal one is the
  fault. Reporting only `:err` is how a merge refusal pointed at a classpath
  that was never the problem, hiding the compile error underneath it for
  hours. Both, labelled, or the surface is lying about which is which."
  [r]
  (when-let [e (:err r)]
    (if-let [f (:first-err r)]
      (str e "\n\nNOTE: the image was refreshed mid-load and the retry failed"
           " differently. The error BEFORE the refresh — the one to fix — was:\n"
           f)
      e)))

(defmulti ^:export after-write!
  "Follow-up once a write to `ns-sym` has LANDED, dispatched on that
  namespace's platform (`:jvm` / `:cljc` / `:cljs`). Whatever a method returns
  is merged into the write's result map; nil adds nothing, and `:default` is
  nil — so the ordinary JVM write pays one platform lookup and a dispatch.

  This exists so the write engine does not have to know which app types exist
  (R6). It used to know: four forms of ClojureScript bundle machinery lived
  here, called from every generic write verb, and they could only reach the
  compiler through `store/late-ref` — an ^:unsafe escape hatch whose entire job
  was to break a cycle the misplacement itself created, since the client build
  requires the operation surface that calls this engine.

  Registering inverts that edge: the app type depends on the engine, never the
  reverse, and app type #2 arrives as another `defmethod` rather than another
  branch in here. Dispatching on PLATFORM rather than on \"is this web?\" is the
  same discipline one level down — the engine asks a question the store can
  answer about any namespace, not a question only one app type has."
  (fn [session ns-sym] (store/platform-for (:store @session) ns-sym)))

(defmethod after-write! :default [_ _] nil)

(defn load-all-namespaces!
  "Load every store namespace into `image` (dependency order, red-first test
  specs stubbed and retried), returning `[{:ns sym :why err} …]` for the ones
  that FAILED — empty when the whole store loaded.

  Collect-and-continue, never throw-on-first: the kernel HOST boot has worked
  this way since frictions 3b/3f/19 (\"ONE namespace that no longer compiles
  took down every tool in every process — including the edit_add_form that
  would have put the missing form back\"), and its boot note promises the
  store stayed open so the broken namespace can be FIXED. The ORACLE boots
  (session open, restart) were the only loops still refusing outright — the
  refusal parked a Throwable in :image-ready and `await-image!` rethrew it in
  front of every non-read tool, wedging a real consumer's store with no
  repair available from inside (slopp-ui, 2026-08-06). The wedge population
  is code verified-good at write time and invalidated from OUTSIDE — a
  framework rename, a dependency bump, a platform declaration stranding a
  JVM caller; per-write verification means nothing inside a store creates it.

  Callers record the result on the session as `:image-load-failures`, where
  the write path reconciles it ([[hot-load-all!]]) and `done!` subtracts and
  reports it."
  [image store]
  (vec (keep (fn [ns-sym]
               (when-let [err (image/load-ns! image store ns-sym)]
                 (when-not (and (stub-missing-test-vars! image store [ns-sym])
                                (nil? (image/load-ns! image store ns-sym)))
                   {:ns ns-sym :why err})))
             (store/ns-dependency-order store))))

(defn- sha256
  "Hex SHA-256 of a string. A REAL digest rather than [[slopp.image.currency/hash-of]],
  which says in its own docstring that it is in-process only because its
  registry is never persisted — this one is written into the journal and
  compared by a later process, so the guarantee has to hold across JVMs."
  [^String s]
  (->> (.digest (java.security.MessageDigest/getInstance "SHA-256")
                (.getBytes s "UTF-8"))
       (map #(format "%02x" %))
       (apply str)))

(defn ^:export closure-hashes
  "For each namespace in `scope`, the CONTENT IDENTITY of everything a verdict
  for it depends on: its own source, the source of every namespace its
  require-closure reaches, and the dependency manifest.

  This is what makes a verdict reusable in principle — *this test was green
  against exactly this content* — and it is recorded on the `:observe` delta so
  the question can be asked later, by a different process, from the journal
  alone. Two properties decide soundness and pull opposite ways: it must change
  when anything the test can LOAD changes (or a stale green outlives a real
  edit), and it must NOT change when unrelated code moves (or it is merely a
  store version and nothing is ever reusable).

  Reach is the require closure — [[slopp.store/ns-closure]], the same producer
  `test-nses-reaching` selects with, so the set a verdict is keyed to and the
  set a change is routed to cannot disagree. That closure is a conservative
  OVER-approximation of what a test executes, which is the safe direction here:
  it can only ever invalidate a verdict that would still have been valid.

  Each namespace is digested ONCE and the closures are combined from those
  digests, so asking about a hundred test namespaces renders each source once
  rather than once per closure that contains it."
  [store scope]
  (let [needed (into #{} (mapcat #(store/ns-closure store %)) scope)
        per-ns (into {} (map (juxt identity #(sha256 (str (store.render/render-ns store %))))) needed)
        deps   (sha256 (pr-str (:deps store)))]
    (into {} (for [n scope]
               [n (sha256 (str/join "|" (cons deps (map #(get per-ns % "?")
                                                        (sort (store/ns-closure store n))))))]))))

^:reads (defn ^:export session-branch-line
  "The BRANCH line this session's work belongs to — its id, or nil for a
  session that has neither a journal nor a branch identity yet.

  Distinct from [[session-line]], which is where writes GO. They are the same
  line until a session adopts a thread, and the land is the first caller that
  needs them apart: it moves one onto the other.

  Resolved from the branch NAME rather than kept in the session, because the
  name is what a checkout changes and a stored id would be a second copy of
  the same fact. The trunk falls out without a special case — a store whose
  `main` row does not exist yet has it minted here, which is the same lazy
  resolution [[session-line]] does and for the same reason.

  An EPHEMERAL session takes its line as-is. It has no journal, so it has no
  threads either, and its line is its branch by construction — `branch!`
  mints a bare id there precisely so a nameless branch still has an identity."
  [session]
  (if-let [conn (:db @session)]
    (or (db/line-id-by-name conn (:branch @session))
        (db/trunk-line-id! conn))
    (:line @session)))

(defn ^:export used-families
  "The capabilities whose framework family `store` USES — the set both the
  vendored FILES and the supplied DEPS are derived from.

  One derivation because they are two halves of one fact. Vendoring hands over
  source, and source has requires: an earlier version supplied the files and
  not the deps, and the framework landed intact and failed inside itself. If
  the two were computed separately they could disagree again, and the symptom
  would be identical.

  A family counts as used when the store does NOT define it and either requires
  something in it or carries one of its declared entry markers. Both halves come
  from `capabilities/shipping-families` and the catalog, so a capability is
  covered by existing rather than by an edit here.

  **A used family arrives with the families it REQUIRES**, and that closure is
  the same failure one level in: a vendored family has requires of ITS own. A
  browser app names `slopp.webapp` and nothing else, so it was handed the
  webapp family alone — while `slopp.webapp` requires `slopp.http.endpoint`, a
  capability over. The framework landed intact and failed inside itself, as
  `No such namespace` from the ClojureScript compiler, which reads like the
  app's own mistake.

  Read off `capabilities/prerequisites` rather than a list of permitted
  cross-family requires, because the catalog already states this edge for a
  reason that covers it: `webapp :requires [\"http\"]` because a browser app has
  to be SERVED. A family a shipped namespace may legitimately reach is a family
  the store is given.

  DEFINES is applied AFTER the closure, not before. slopp's own store defines
  `slopp.http.*`, and vendoring there would shadow the code being edited with
  the last-shipped copy — that has to hold for a family pulled in by a
  prerequisite exactly as it holds for one named directly."
  [store]
  (let [nses (keys (:namespaces store))
        in?  (fn [prefix n] (let [s (str n)]
                              (or (= s prefix) (str/starts-with? s (str prefix ".")))))
        fams (capabilities/shipping-families)
        defines? (fn [cap] (some #(in? (get fams cap) %) nses))
        named (into #{}
                    (for [[cap prefix] fams
                          :let  [ms (set (:entry-markers (capabilities/capability cap)))]
                          :when (and (not (defines? cap))
                                     (or (some (fn [n]
                                                 (some #(in? prefix %) (store/ns-require-libs store n)))
                                               nses)
                                         (some (fn [n]
                                                 (some (fn [f]
                                                         (some ms (keys (store/form-name-meta f))))
                                                       (store/forms store n)))
                                               nses)))]
                      cap))]
    (into #{}
          (remove defines?)
          (into named (mapcat capabilities/prerequisites) named))))

(defn ^:export framework-injection
  "The framework FILES slopp vendors into `store` — `{\"slopp/cli.clj\" src …}` —
  or nil when it should supply nothing.

  `files` arrives keyed BY CAPABILITY (`{\"cli\" {path src} \"http\" {…}
  \"_\" {…}}`); the answer is the flattened union of the families this store
  actually uses, plus `\"_\"` (the dialect's own helpers, which are part of the
  syntax rather than of any capability).

  D-framework-injection. The framework is slopp's own, so slopp provides it,
  exactly as `external/client-build-deps` provides the ClojureScript compiler
  and `repl/inherent-deps` provides nREPL and malli. A store that declared it
  instead could be pinned to a release the slopp serving it is not — not
  hypothetical: `slopp-ui` sat on 0.1.3 for a day while the fix made FOR it
  shipped in the host at 0.1.4.

  **Files, not a coord (part 2).** The framework is never published to a
  remote, so a coord names something only the machine that built it can
  resolve: portable in appearance, not in fact.

  **Per capability (part 3), and it follows USE rather than ENABLEMENT.** A
  store is handed the families it actually reaches for, so a command-line app
  carries no `slopp/http/**` and inherits none of http's deps — a smaller tree
  and a smaller dependency surface, which is what this buys.

  It does NOT make the capability opt-in hold at runtime, and an earlier
  version of this docstring claimed it did. slopp-ui found the hole by tracing
  it against their own store: `used-families` keys off requires and entry
  markers, so a store whose requires already exist still resolves `slopp.http`
  with `http.enabled` false. Withholding a family from a store that neither
  requires nor marks it withholds it from the one store that was not going to
  require it.

  **Keying on enablement instead would be worse, which is why it stays.** A
  store mid-migration — retired config keys, new registry, requires unchanged —
  would boot with the framework missing, fail to load, and lose the very
  `config_file` calls that repair it. Vendoring on USE is what keeps that a
  diagnosable message instead of a wedge. The opt-in is enforced where it can
  answer for itself: the write gates, and the capability-driven behaviour
  (the server does not start, the rules are inert).

  The families come from `capabilities/shipping-families` rather than from a
  prefix written here, so a new capability vendors by existing.

  Conditions, each load-bearing in a different direction.

  **USES but does not DEFINE.** slopp's own store CONTAINS `slopp.http.*`, and
  `src` is the FIRST classpath entry — so vendoring there would shadow the code
  being edited with the last-shipped copy, and slopp would test its release
  instead of its working tree. Judged per family: a store may define one and
  legitimately use another.

  **USES is not only REQUIRING.** A `^:app/entry` app is opened by
  `slopp.cljnx`, which slopp calls on the app's BEHALF, so the app's own
  code may name none of the framework. **For `cli` this is the ONLY signal**:
  with a generated entry an app writes commands and slopp writes the launcher,
  so nothing in the store ever requires `slopp.cli`. The markers come from the
  catalog for the same reason the prefixes do.

  Empty or nil `files` (a checkout, a `clojure -M` run) vendors nothing rather
  than half a framework."
  [store files]
  (when (seq files)
    (let [used (used-families store)]
      (when (seq used)
        (not-empty (reduce merge (get files "_") (map #(get files %) used)))))))

(defn marker-readers
  "Test forms that READ a marker `ns-sym/nm` carries — the tests whose subject is
  a declaration rather than a call.

  `(:http/path (meta #'app/page))` invokes nothing, so there is no trace edge to
  record and no static var reference to follow. Such a test is invisible to
  every other producer [[affected-tests]] has, and the measured consequence was
  a write reporting green on an edit that broke three of them.

  **Two conditions, and the second is what makes it usable.** A test qualifies
  when it mentions a marker THIS form carries *and* actually reads metadata —
  `(meta …)`, `ns-publics`, or `store/form-name-meta`, which are the three ways
  a declaration can be reached.

  Mentioning a marker is not reading one, and the difference is the whole cost
  of this producer. Measured over slopp's own store, which is the worst case
  because slopp IS the machinery that tests markers:

  ```
  :http/path      74 tests mention it  →   3 read metadata
  :http/method    69                   →   1
  :rest/response  53                   →   1
  :malli/schema  26                   →   1
  ```

  14 of 1457 test forms read metadata at all. Without the second condition an
  endpoint edit would union in a tenth of the suite — including `^:external`
  tests, which cost a JVM each — for forms whose declaration nothing asserts.

  **Not keyed on the form's NAME, and that is the case that decided the rule.**
  A marker test is very often a SWEEP — `(map meta (vals (ns-publics 'app)))` —
  which names no form at all and is affected by ANY form gaining or losing the
  marker. Keying on the name would have caught two of the three readers in the
  incident that prompted this and missed exactly the one that makes a
  declaration checkable store-wide.

  **The honest limit:** a test that reaches a declaration through some other
  accessor is missed here. `done` runs the whole in-image suite and is the
  backstop; this producer exists so the WRITE stops saying green, which is the
  answer an author actually acts on.

  Slopp-namespaced markers only, via `crossings/known-markers`. An app's own
  `:myapp/thing` is not a declaration slopp gives meaning to, so a form carrying
  one has no slopp-visible readers to find.

  Test namespaces only — both because that is what may be RUN, and because
  skipping production forms is what keeps this cheap enough to do on every
  write."
  [store ns-sym nm]
  (let [e     (store/form-named store (symbol (str ns-sym)) (symbol (str nm)))
        owned (crossings/known-markers)
        marks (when e
                (into #{} (filter owned) (keys (store/form-name-meta e))))
        reads-meta? (fn [^String s]
                      (or (str/includes? s "(meta ")
                          (str/includes? s "ns-publics")
                          (str/includes? s "form-name-meta")))]
    (when (seq marks)
      (vec (sort (for [nsx   (keys (:namespaces store))
                       :when (store.render/test-ns? nsx)
                       t     (store/forms store nsx)
                       :when (:name t)
                       :let  [src (str (:node t))]
                       :when (and (reads-meta? src)
                                  (some #(str/includes? src (str %)) marks))]
                   (symbol (str nsx) (str (:name t)))))))))

(defn red-attribution
  "Whose red is this? Splits a verification summary's failing tests by the
  three-way `:attribution` [[implicate]] put on each one, and returns nil
  when nothing is red.

  {:mine [...] :foreign [...] :untraced [...] :unseen n} — non-empty
  entries only. `:mine` and `:foreign` are claims; `:untraced` and
  `:unseen` are the two ways of having no claim to make, and they are kept
  because the whole point of the split is that a caller may act on
  innocence and must never infer it from silence:

  - `:untraced` — the run produced no trace for this failing test
  - `:unseen`   — the summary counts MORE failures than it carries blocks
                  for, so some red is not represented here at all. Failure
                  detail is capped for response size, and attributing only
                  what survived the cap would read a truncation as a clean
                  bill of health.

  A caller wanting \"none of this red is mine\" needs all three of `:mine`,
  `:untraced` and `:unseen` to be absent."
  [summary]
  (let [total (+ (:fail summary 0) (:error summary 0))]
    (when (pos? total)
      (let [blocks (vec (:failures summary))
            named  (into #{} (keep :test) blocks)
            ;; a test the run NAMED as failing but carried no block for is a
            ;; red we cannot attribute — same standing as an untraced one
            capped (vec (sort (remove named (:failed-tests summary))))
            by     (group-by #(or (:attribution %) :untraced) blocks)
            bucket (fn [k] (vec (sort (distinct (keep :test (get by k))))))
            unseen (max 0 (- total (count blocks) (count capped)))]
        (cond-> {}
          (seq (get by :mine))    (assoc :mine (bucket :mine))
          (seq (get by :foreign)) (assoc :foreign (bucket :foreign))
          (or (seq (get by :untraced)) (seq capped))
          (assoc :untraced (vec (sort (distinct (concat (bucket :untraced) capped)))))
          (pos? unseen)           (assoc :unseen unseen))))))

(defn red-history
  "The tests that went red in episodes where `ns-sym/nm` changed, from the
  red-after index (`db/reds-for`) — nil without a durable store or without
  evidence. A FLOOR for selection, never a selector: measured on slopp's own
  journal it recalls 38% of failing tests alone (48% with namespace grain)
  against a 95% bar, so it cannot replace the closure fallback; what it adds
  is what a trace cannot see — a test that reads a marker or reaches the
  form through a dispatch or a route, and went red beside it anyway.

  Only tests that still EXIST: the index remembers a name as it was, and a
  renamed or deleted test handed to the runner would make the run report
  `:scope-ran-nothing` for a scope that was mostly right."
  [session ns-sym nm]
  (when-let [conn (:db @session)]
    (let [st (:store @session)]
      (when-let [fid (:id (store/form-named st ns-sym nm))]
        (seq (filter (fn [t] (store/form-named st (symbol (namespace t)) (symbol (name t))))
                     (map :test (db/reds-for conn [fid]))))))))

(defn affected-tests
  "Which tests must re-run after editing `ns-sym/nm`: the tests observed (via
  tracing) to exercise that form — or the form itself if it IS a test. nil =
  no usable trace information; run everything (conservative).

  Form-aware (#129): evidence is matched against EVERY name the form defines
  (`store/form-trace-keys`) — a test calling protocol method `m` recorded
  `ns/m`, though the form's primary name is `P`; `->R` evidence belongs to
  `R`'s form. And a `method-carrying?` form (defmethod, defrecord/deftype,
  extend-*) NEVER narrows: its bodies run where the tracer cannot fully see
  them, so its evidence is structurally partial, and narrowing on partial
  evidence is how a false green happens. nil sends the caller to the same
  closure fallback a silent trace does.

  ROUTE-aware (D-web-html): a web endpoint's tests reach it through
  `web/handle!`'s runtime route scan, so they leave no static reference AND no
  trace evidence until they have run once — every endpoint write reported
  `:no-covering-tests` during exactly the writes its red route test existed
  for. When trace evidence is silent, `api.web/endpoint-test-refs` joins the
  static route table to the literal URIs in test forms. Consulted only AFTER
  tracing, so recorded evidence always wins; a form that is not an endpoint
  simply misses the join and falls through to nil as before.

  DECLARE-aware (#4 follow-up): a `^{:covers}` test reaches the form through a
  dispatch/data/child-image path the tracer structurally can't see, so it
  leaves no trace and no static edge. Its coverage — the `:declared` producer
  of `refs/covered-by` — is UNIONED into any non-nil result: a declaration is
  a floor (at least these run), not a ceiling, so it never narrows on its own
  (a nil result already runs everything, the declared tests included).

  MARKER-aware (2026-08-16, reported by slopp-ui): a test that READS a form's
  declaration — `(:http/path (meta #'app/page))` — never CALLS it, so it leaves
  no trace edge and no static reference either. Trace evidence about a MARKED
  form is therefore partial by construction, and the measured consequence was a
  write reporting `{:ran 2, :pass 18, :status :green}` on the edit that broke
  three assertions: other tests DID have trace evidence, so the set narrowed to
  those and every reader of the declaration fell out.

  That is the same shape `method-carrying?` guards against, one level out — a
  body the tracer cannot see, versus a declaration nothing executes at all —
  and it breaks the rule the whole narrowing rests on: **partial evidence must
  not select.** A form on both a traced and an untraced path gets a small,
  confident count and narrows to it, which is exactly what a false green looks
  like from the outside.

  So [[marker-readers]] is UNIONED in, on the same terms as `:covers`: a floor,
  never a ceiling, and it can only ever ADD tests — which is what makes it
  incapable of causing the failure it fixes.

  HISTORY-aware (2026-08-29): [[red-history]] — the tests that went red in
  past episodes where this form changed — is unioned in on exactly those
  terms. Measured on slopp's own journal, history alone recalls 38% of
  failing tests (48% with namespace grain) against a 95% bar, so it never
  SELECTS: an untraced form still falls back to the closure. What it adds is
  the same blind spot the two floors above cover, learned rather than
  declared."
  [session ns-sym nm]
  (let [qform (symbol (str ns-sym) (str nm))
        tmap  (:test-map @session)
        store (:store @session)
        declared (->> (refs/covered-by store tmap qform)
                      (filter #(contains? (:via %) :declared))
                      (map :test))
        readers  (marker-readers store ns-sym nm)
        history  (red-history session ns-sym nm)
        with-declared (fn [res]
                        (when res
                          (vec (sort (distinct (concat res declared readers history))))))
        via-routes (fn []
                     (when-let [hits (get (rules.http/endpoint-test-refs store)
                                          qform)]
                       (vec (sort hits))))]
    (with-declared
      (if (contains? tmap qform)
        [qform]
        (let [e (store/form-named store ns-sym nm)]
          (cond
            (nil? e)
            (let [hits (->> tmap
                            (keep (fn [[t forms]] (when (contains? forms qform) t)))
                            sort vec)]
              (when (seq hits) hits))

            (store/method-carrying? e) nil

            :else
            (let [ks   (store/form-trace-keys ns-sym e)
                  hits (->> tmap
                            (keep (fn [[t forms]] (when (some forms ks) t)))
                            distinct sort vec)]
              (if (seq hits) hits (via-routes)))))))))

(defn stub-unresolved-test-symbol!
  "The red-first seam's second source. `stub-missing-test-vars!` reads the
  reference graph, and an UNQUALIFIED symbol a deftest names in its own
  namespace before it exists has no row there — kondo reports it as
  unresolved, and only the load error names it. When `err` is `Unable to
  resolve symbol: X` and the form it failed in (`edit/anchor-error`) is a
  deftest, intern a throwing stub for that form's namespace `/X` in `image`
  and return `[qsym]`; nil for anything else, so a production form's genuine
  unresolved symbol stays the compile error it is. eval10 s5: a test-first
  `ns_create` on a namespace that keeps its tests beside its code failed to
  load three times while the agent wrote the stubs by hand.

  The namespace is the ANCHOR's, and `ns-sym` only the fallback when the
  error carries no coordinate: a group touching several namespaces asked
  this once per namespace with the anchored form's NAME, and the first
  namespace holding a form of that name won the stub."
  [image candidate ns-sym err]
  (when-let [[_ sym] (re-find #"Unable to resolve symbol: ([^\s/]+) in this context" (str err))]
    (let [anchor (edit/anchor-error candidate err)
          nsx    (or (some-> (:form anchor) namespace symbol) ns-sym)
          form   (some-> (:form anchor) name symbol)
          e      (when form (store/form-named candidate nsx form))
          head   (when e (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                           (when (seq? s) (first s))))]
      (when (and (= 'deftest head)
                 ;; a clojure.test name is a missing REQUIRE, never a stub: stubbed,
                 ;; `deftest` became a var and the next error named the test itself
                 (not (edit/clojure-test-public? (symbol sym)))
                 (not (store/form-named candidate nsx (symbol sym))))
        (let [q (symbol (str nsx) sym)]
          (repl/eval! image
                      (format "(intern '%s '%s (fn [& _] (throw (ex-info \"red-first stub: %s is speced but not implemented\" {:red-first '%s}))))"
                              nsx sym q q))
          [q])))))

(defn ^:export thread-key
  "The key this session's THREAD is adopted under: an explicit `:thread` when
  a call named one, else the session's identity.

  Two things that used to be one. `:agent-id` is WHO — what the harness said
  at start, what the intent mailbox is claimed by, what a delta is labelled
  with when nothing else is. The thread is WHERE the work goes, and it
  defaults to the identity so a session that never names one behaves exactly
  as before: one conversation, one line. A call that does name one — a
  subagent sharing its parent's connection, an orchestrator multiplexing —
  routes there without changing who it is.

  Every adoption resolves through here (`session-line`, `adopt-line!`, a
  branch switch, the re-fork after a land), because two adopt sites that
  disagreed about the key would put one session's writes on two lines."
  [session]
  (or (some-> (:thread @session) str) (:agent-id @session)))

^:reads (defn ^:export session-line
  "The LINE this session reads and writes — its own THREAD, or nil for an
  ephemeral session, which has no journal for a line to point into.

  A session's writes are private until `done` lands them, and this is where
  that becomes true: everything below — the write CAS, the cache refresh, the
  materialization — resolves through here, so the thread is not a mode the
  rest of the system has to know about. It is simply which line the answer
  names.

  ADOPT-OR-CREATE, keyed by (agent, branch). The db owns that decision
  (`db/adopt-thread!`), and the result is cached on the session so the row is
  touched once per session rather than once per call.

  Resolved LAZILY, and it matters twice over. A session can acquire its store
  after opening — `ensure-db!` materializes one on the first durable write —
  so an id read eagerly at open would name a store that did not exist yet.
  And a session's IDENTITY can arrive after opening too: the harness session
  id comes in on the first prompt, so adopting before then would key the
  thread to a placeholder."
  [session]
  (if-let [conn (:db @session)]
    (let [cached (:line @session)
          status (when cached (db/line-status conn cached))]
      ;; The cache is CHECKED, not trusted, and that is the whole of this
      ;; change. Another process can settle this line underneath us: the
      ;; plugin's Stop hook runs `done` through a one-shot carrying this
      ;; session's own agent id, so every session pause adopts this line,
      ;; lands it, and settles it while this server holds the id it cached at
      ;; first use.
      ;;
      ;; Writing to a settled line is silent and total. Every write reports
      ;; success, none of them can land — settled lines are not landable — and
      ;; the thread cannot be dropped to recover, because settled lines are
      ;; not droppable either. Observed live: `session_brief` reporting 20
      ;; un-landed while `thread_list` reported 0 for the line it named and
      ;; `thread_drop` refused with "already landed". Work that could go
      ;; neither forward nor back, and the only exit was restarting the server.
      ;;
      ;; The cost is a primary-key lookup per resolution, against a write that
      ;; is about to open a transaction. The docstring's "touched once per
      ;; session" was protecting adoption's UPDATE, which this still does once.
      (cond
        ;; still ours to write to
        (= "open" status) cached

        ;; ABSENT from the registry: a broken invariant, NOT a settled line,
        ;; and re-adopting here would heal it silently. `land-thread!` reports
        ;; this case on purpose — it names the thread, says the writes are in
        ;; the journal but not on the branch, and tells the agent to restart
        ;; and re-apply. Taking that away by quietly issuing a new line is how
        ;; work goes missing with nobody told.
        (and cached (nil? status)) cached

        ;; SETTLED under us — the Stop hook case. Move to a fresh line so the
        ;; next write goes somewhere that can land.
        ;; a session that only READS (a one-shot `--call query_…`) answers from
        ;; the branch and adopts nothing — every such call used to mint a
        ;; thread it would never write to (s16 probes: five per probe)
        (:read-only-line? @session)
        (session-branch-line session)

        :else
        (let [id (db/adopt-thread! conn (session-branch-line session)
                                   (thread-key session))]
          (swap! session assoc :line id)
          id)))
    ;; ephemeral: no journal, so no registry to disagree with
    (:line @session)))

(defn try-commit!
  "Commit base→st' — JOURNAL-FIRST for durable sessions (m5a storage
  inversion): st's `:pending` deltas + the full element rows of `nses` land
  in ONE conditional db transaction (iff the journal head still equals base's
  `:head`), then the cache follows; the cache is only ever behind the
  journal, never ahead. Ephemeral sessions commit to the cache alone
  (identity CAS). True iff committed; false = the head/cache moved — caller
  refreshes and rebases, or surfaces contention.

  What this reads off the value is exactly what `record-delta` maintains:
  `:head` for the CAS, `:pending` for the suffix, `:line-pos` to decide
  whether the cache advanced. It used to recover all three from two whole
  delta lists — the base's count dropped off the candidate's — which is the
  reason the lists had to be in RAM at all.

  The REFERENCE INDEX is refreshed here for `nses` — the namespaces whose
  elements this commit rewrites — before anything lands, so the committed
  value's `:refs` entries for them are current and `write-snapshot!` persists
  them beside the elements. This is the one chokepoint every write passes,
  which is why the refresh lives here and not in each operation: the graph
  after a write costs one namespace's analysis rather than the store's.

  What lands in the session carries NO delta list: `store/committed` clears
  the suffix and the list is dropped here. A candidate that carries one — a
  merge folds a hydrated value — is committed like any other, and the live
  value stays the journal's facts, not the journal."
  [session base st' nses]
  (let [;; then ARRANGED: every namespace this commit rewrites takes its
        ;; derived order (definitions before callers, ties by creation
        ;; rank) here, at the one chokepoint — so a revert, an extract, an
        ;; ingest and a merge are arranged by the same rule a plain write
        ;; is, and the positions `write-snapshot!` persists are the ones a
        ;; fold of the journal derives. The refresh comes first because the
        ;; arrangement is derived FROM the index (which is order-insensitive)
        st'    (let [st' (refs/refresh st' nses)]
                 (reduce refs/arrange st'
                         (filter #(get-in st' [:namespaces %]) nses)))
        landed (dissoc (store/committed st') :deltas)]
    (if-let [conn (ensure-db! session)]
      (if (db/append! conn st' (:pending st') (vec nses)
                      (session-line session) (:head base))
        (do (swap! session
                   (fn [s]
                     (if (< (:line-pos (:store s) 0) (:line-pos st' 0))
                       (assoc s :store landed)
                       s)))
            true)
        false)
      (let [[old _] (swap-vals! session
                                (fn [s]
                                  (if (identical? (:store s) base)
                                    (assoc s :store landed)
                                    s)))]
        (identical? (:store old) base)))))

(defn prior-source
  "The source `fid` held immediately BEFORE the newest delta that touched it,
  read from the journal — nil when unknown (created by ingest, or touched
  only once), which callers treat conservatively. One indexed read over the
  deltas that touched this form (`db/deltas-touching`), newest first. Takes
  the SESSION, not the store value: the value no longer carries its log, and
  this is the write path's one baseline read between dones."
  [session fid]
  (->> (db/deltas-touching (:db @session) (session-line session) [fid])
       reverse
       (keep #(get (:sources %) fid))
       (drop 1)
       first))

(defn impacted-tests
  "Every test var the changed form-ids can affect, decided PER FORM (#132):
  a form with trace evidence contributes exactly its observed tests; a form
  without contributes every test in the namespaces whose require-closure
  reaches ITS namespace. Never nil — [] means nothing reaches.

  Replaces the all-or-nothing collapse, where ONE untraced form discarded
  every other form's evidence and reverted the whole done to closure runs.
  Measured on the journal (2026-07-17): 54.4% of real episodes touched a form
  the tracer can never see — 43.2% an NS FORM (ns_add_require edits one),
  28% a data def — so the collapse was the common case, not the corner.

  The dominant untraced form is the ns form, and its commonest edit is an
  alias-only require addition — SEMANTICALLY inert, so it contributes
  NOTHING instead of its whole closure (inert-ns-require-change?,
  frictions #2). Inertness is judged against the LAST-DONE baseline (the
  episode's start), so a multi-edit episode where an earlier edit added a
  :refer isn't masked by a later alias-only edit (review V-F3). Every other
  untraced shape keeps the closure fallback: `test-nses-reaching` over a
  union of namespaces IS the union of the per-namespace calls (the closure
  intersection distributes), so untraced forms select exactly what the
  global fallback selected for them, while traced forms keep their narrow
  sets."
  [session store changed]
  (let [baseline (->> (:recent store) (filter #(= :done (:op %))) last :id)
        ;; the baseline sources of the CHANGED forms only — a fold over the
        ;; deltas that touched them, not the whole log from the root
        base-src (when baseline
                   (db/sources-at (:db @session) (session-line session) baseline changed))
        reach (memoize
               (fn [ns-sym]
                 (vec (for [tns (test-nses-reaching store [ns-sym])
                            :let [tiers (test-var-tiers store tns)]
                            nm (concat (:image tiers) (:external tiers))]
                        (symbol (str tns) (str nm))))))]
    (vec (sort (distinct
                (mapcat (fn [fid]
                          (if-let [e (store/form-by-id store fid)]
                            (let [ns-sym (store/ns-of-form-id store fid)]
                              (cond
                                (and (= (:name e) ns-sym)
                                     (inert-ns-require-change?
                                      store fid
                                      (if baseline
                                        (get base-src fid)
                                        (prior-source session fid))))
                                []

                                :else
                                (or (affected-tests session ns-sym
                                                    (or (:name e) (symbol (:id e))))
                                    (reach ns-sym))))
                            []))
                        changed))))))

(defn impacted-external
  "The ^:external test vars the changed form-ids can affect, for the
  done-point to route to the external tier — `impacted-tests` filtered to the
  tier only the external runner can execute.

  Never nil (#132): an untraced form expands to its own namespace's reach
  instead of collapsing the whole answer, so [] genuinely means no external
  test can be affected. The #127 version returned nil on ANY untraced form and
  done! fell back to the require-closure of everything — which selects a
  median 43 of 46 external test namespaces and deferred 84.6% of changes."
  [session store changed]
  (external-among store (impacted-tests session store changed)))

^:reads (defn ^:export session-fork-line
  "The line this session's thread forks from and LANDS INTO: its parent
  thread when it is a child (`thread_open {parent}`), else its branch.

  Distinct from [[session-branch-line]], which every caller used to mean by
  this: a child's branch is still the branch, but the line it follows when
  idle and moves onto at a done is its parent. The land, the idle-follow and
  the post-land gap check all want THIS answer; the branch listing and the
  drop want the branch. Reads `:line` as held rather than resolving it, so
  asking never adopts."
  [session]
  (let [branch (session-branch-line session)
        conn   (:db @session)
        line   (:line @session)]
    (if (and conn line)
      (db/thread-fork-line conn line branch)
      branch)))

^:reads (defn ^:export line-label
  "What a land's report CALLS `line-id`: the branch's name when it is this
  session's branch, else `thread <id>` in the id the caller passes — a child
  lands into its parent, and saying so in a line uuid would name nothing the
  caller has ever seen."
  [session line-id]
  (if (= line-id (session-branch-line session))
    (:branch @session)
    (let [row (when-let [conn (:db @session)]
                (first (filter #(= line-id (:id %)) (db/lines conn))))]
      (str "thread " (or (:agent row) line-id)))))

^:reads (defn ^:export episode-agents
  "Whose deltas an episode on this session's line GRADES: `agent`'s own, and
  those of every CHILD thread that has landed into this line. A child
  (`thread_open {parent}`) lands into its parent rather than the branch, so
  the parent's done is what stands behind that work when the parent lands —
  a done that graded only its own agent's deltas would land a subagent's
  work unjudged. Read from the registry, not the session: a child's row
  survives its land with `parent` still naming this line."
  [session agent]
  (into #{agent}
        (when-let [conn (:db @session)]
          (when-let [line (:line @session)]
            (into #{} (comp (filter #(= line (:parent %))) (keep :agent))
                  (db/lines conn))))))

(defn ^:export reload-namespaces!
  "Reload `nses` and every namespace that (transitively) requires one of
  them WHOLE into the session's image, dependencies first, and answer the
  failures `[{:ns :why}]` — empty when everything loaded. Nothing to do
  without an image.

  A form-level hot-load evaluates the form that changed and nothing else.
  A value DERIVED from it in another form of the same namespace — a table
  built from a def, a registry folded at load time — keeps the value it had,
  so the namespace can be one no fresh process will ever load while every
  check on the hot image stays green (2026-09-04: a bare pair in a
  descriptor vector; `classified` threw only on a cold load; the store sat
  unbootable for half an hour behind a live host serving old definitions).
  A whole-namespace reload re-evaluates every form, which is the cold-load
  question asked without a JVM boot. Dependents come along because a
  namespace that requires the broken one breaks with it, and the failure
  the agent has to read is the FIRST one.

  Reconciles `:image-load-failures`: a namespace that failed here joins it
  (or refreshes its `:why`), one that loaded leaves it. This episode's own
  failures are also kept under `::reload-failures` — what `done!` counts
  against the EPISODE, as distinct from a namespace somebody else left
  unloadable, which is the store's red and not this thread's. Red-first
  test specs are stubbed and retried as the boot does."
  [session nses]
  (if-let [image (:image @session)]
    (let [st     (:store @session)
          all    (store/ns-dependency-order st)
          reqs   (into {} (map (fn [n] [n (set (store/ns-requires st n))])) all)
          wanted (loop [acc (set nses)]
                   (let [more (into acc (filter #(some acc (reqs %)) all))]
                     (if (= more acc) acc (recur more))))
          fails  (vec (keep (fn [n]
                              (when-let [err (image/load-ns! image st n)]
                                (when-not (and (stub-missing-test-vars! image st [n])
                                               (nil? (image/load-ns! image st n)))
                                  {:ns n :why err})))
                            (filter wanted all)))]
      (swap! session
             (fn [s]
               (assoc s
                      :image-load-failures
                      (not-empty (vec (concat (remove #(wanted (:ns %)) (:image-load-failures s))
                                              fails)))
                      ::reload-failures fails)))
      fails)
    (do (swap! session assoc ::reload-failures [])
        [])))

(defn ^:export attribute-unloadable
  "`attribution` — [[red-attribution]]'s split of the failing tests — with
  the unloadable namespaces split the same way under `:unloadable`: `:mine`
  are the ones THIS episode's reload broke ([[reload-namespaces!]]),
  `:foreign` the ones the store already could not load. nil when there is
  nothing to attribute at all, so a clean done carries no key."
  [session attribution]
  (let [all  (mapv :ns (:image-load-failures @session))
        mine (set (map :ns (::reload-failures @session)))]
    (if (seq all)
      (assoc (or attribution {})
             :unloadable {:mine    (vec (filter mine all))
                          :foreign (vec (remove mine all))})
      attribution)))

(defn arrange-replayed
  "`st` with every namespace the replayed `suffix` deltas touched re-arranged
  in its derived order — the ns form, declares, then definitions before
  callers, exactly what the write pipeline arranges on every write.

  Replay APPENDS an added form (`store/replay-delta` ignores the recorded
  anchor, since order is derived) and rendering follows the elements
  vector, so without this a session that absorbs another's writes by
  replay rendered a helper after its caller while the writer rendered it
  before: every warm check green, and a fresh boot from that value refused
  the namespace. The daemon's reader is such a session, and its value is
  what the dev instance boots from.

  Touched means named by a delta (`:ns`) or owning a form its rewrite
  carried (`:sources`); a namespace the value no longer holds is skipped."
  [st suffix]
  (let [touched (into #{}
                      (concat (keep #(let [n (:ns %)] (when (symbol? n) n)) suffix)
                              (for [d suffix
                                    fid (keys (:sources d))
                                    :let [n (store/ns-of-form-id st fid)]
                                    :when n]
                                n)))]
    (reduce (fn [s nsx]
              (if (contains? (:namespaces s) nsx)
                (refs/arrange s nsx)
                s))
            st
            touched)))

(defn ^:export framework-files-from-source
  "The framework families read off the CLASSPATH SOURCE rather than the jar's
  generated manifest — `{\"http\" {\"slopp/http.clj\" src …} … \"_\" {…}}` — for
  a process that has no manifest: a checkout, a `clojure -M -m` run, which is
  what CI's proof lanes are. Empty when nothing is found.

  Two sources of one fact, and this is the one the OTHER is generated from:
  `build.clj` walks this tree to write `framework-files.edn` into the jar.
  So they agree by construction on the tree the jar was built from, and
  differ only where the jar is stale — which under a checkout is the state
  you asked for.

  The families and their prefixes come from the capability catalog, as
  [[used-families]] reads them, so a capability vendors by existing. The
  common family (`\"_\"`, the dialect's helpers) is the fixed list the jar's
  manifest carries for it. Files are found by asking the classpath for the
  namespace's own path and a listing of its subtree, which a directory root
  answers and a jar does not — and a jar has the manifest.

  Here rather than in the kernel beside `slopp.kernel.boot/framework-files`:
  the kernel may not reach up to the catalog (it would close a module cycle),
  and this layer already does."
  []
  (let [common  ["slopp/lang.cljc" "slopp/cache.clj" "slopp/cljnx.clj"
                 "slopp/cljnx/hiccup.clj" "slopp/cljnx/render.clj"]
        src-ext? (fn [^String n] (or (.endsWith n ".clj") (.endsWith n ".cljc") (.endsWith n ".cljs")))
        read!   (fn [p] (when-let [r (io/resource p)] [p (slurp r)]))
        family  (fn [prefix]
                  (let [path (str/replace (str prefix) "." "/")
                        tops (keep #(read! (str path %)) [".clj" ".cljc" ".cljs"])
                        below (when-let [dir (some-> (io/resource path) io/file)]
                                (when (.isDirectory dir)
                                  (for [f (file-seq dir)
                                        :when (and (.isFile f) (src-ext? (.getName f)))
                                        :let [rel (str path "/" (subs (.getPath f) (inc (count (.getPath dir)))))]]
                                    [rel (slurp f)])))]
                    (into (sorted-map) (concat tops below))))
        fams    (into (sorted-map)
                      (for [[cap prefix] (capabilities/shipping-families)
                            :let [fs (family prefix)]
                            :when (seq fs)]
                        [cap fs]))
        c       (into (sorted-map) (keep read!) common)]
    (cond-> fams (seq c) (assoc "_" c))))

^:reads (defn ^:export framework-files*
  "The framework this process can vendor: the jar's generated manifest
  (`boot/framework-files`), else the classpath source
  ([[framework-files-from-source]]); nil when neither has anything. THE reader
  every consumer in this layer asks, so a checkout and a jar vendor the same
  families through one door."
  []
  (or (boot/framework-files)
      (not-empty (framework-files-from-source))))

(defn ^:export vendor-framework!
  "Write the framework `store` needs into `dir`/src. Returns the paths written,
  or nil when the store needs none.

  An image runs with its own dir as cwd and `src` as the FIRST (relative)
  classpath entry. So vendoring is a file write: no `-Sdeps` entry, no
  `:local/root`, no repository. `external/build!` writes into the materialized
  tree the same way, which is why this takes a dir rather than an image.

  **It must happen BEFORE the process starts.** A JVM caches a relative
  classpath directory that did not exist at launch, so writing into a RUNNING
  image's dir does nothing — measured while testing this. Every caller creates
  and fills the dir first, then launches.

  The VERSION STAMP travels with the files, which handles provenance.

  **The files are only half of what a coord carried.** The other half is the
  DEPS — vendored source still has requires, and the pom was what pulled garden,
  hiccup, cheshire and http-kit in. That is [[image-deps]]'s job, and it has to
  be done by every consumer of this fn or the framework lands intact and fails
  inside itself. An earlier docstring here claimed provenance was the only
  property needing replacement; it was wrong, and a store went unloadable
  proving it.

  Deliberately NOT the uberjar on the image classpath, which would be simpler
  and would destroy the property this rests on: a store's image receives the
  FRAMEWORK and nothing else, so reaching for `slopp.api` from an app fails to
  compile there."
  [store dir]
  (when-let [files (framework-injection store (framework-files*))]
    (let [written (vec (sort (for [[path src] files]
                               (let [f (io/file dir "src" path)]
                                 (io/make-parents f)
                                 (spit f src)
                                 path))))]
      (when-let [v (boot/framework-version)]
        (let [f (io/file dir "src" boot/framework-version-path)]
          (io/make-parents f)
          (spit f v)))
      written)))

(defn framework-dir!
  "The dir to launch an image for `store` in — one per session, created and
  filled on demand — or nil when that store needs no framework.

  The DECISION is per-store (branch lines each have their own); the DIR is per
  session, because the framework is slopp's own and every image it launches
  needs the same bytes. Cached under `:framework-dir`.

  **Two axes, and conflating them is the documented failure.** WHICH families a
  store gets is re-derived HERE, on every image launch, from the current store —
  so a store that gains web code (or enables a capability, or renames the marker
  `used-families` keys on) has it on its NEXT image rather than never. WHAT IS
  IN a family comes from `boot/framework-files`, which reads this process's own
  jar resources, and is frozen until the process restarts. A slopp fix needs a
  rebuild and a restart; a capability just turned on does not.

  A consumer's own notes said the tree is materialized from the jar at process
  start, which is half right and produced a wrong prediction: they enabled
  `webapp` mid-session, found `slopp/webapp.cljc` in a tree that supposedly
  could not contain it, and had no model that explained it.

  **The vendor call only WRITES, never removes**, so within a session the tree is
  the UNION of every family the store has used at any point in it — and a fresh
  session re-derives from scratch. A family that stops being used therefore keeps
  resolving until the session ends. That asymmetry is deliberate rather than an
  oversight: removal would break the store this whole mechanism protects, the one
  mid-migration whose requires outlive its config and which must still boot to be
  repairable."
  [session store]
  (when (framework-injection store (framework-files*))
    (let [dir (or (:framework-dir @session)
                  (let [d (str (java.nio.file.Files/createTempDirectory
                                "slopp-framework"
                                (make-array java.nio.file.attribute.FileAttribute 0)))]
                    (swap! session assoc :framework-dir d)
                    d))]
      (vendor-framework! store dir)
      dir)))

(defn start-spare!
  "Kick off a background-warming spare image (D5 warm spare) if enabled.

  Launched with no DEPS — the manifest can change between warming and adoption,
  so `image-with-deps!` reconciles it there — but IN the session's framework
  dir, because that half cannot be reconciled later: a JVM cannot pick up a
  relative classpath directory after launch. The dir is resolved here, on the
  calling thread, rather than inside the future: it writes to the session."
  [session]
  (when (:warm-spare? @session)
    (let [dir (framework-dir! session (:store @session))]
      (swap! session assoc :spare
             (future (repl/start! (cond-> {}
                                    dir (assoc :slopp.image.repl/dir dir))))))))

(defn ^:export framework-deps-from-source
  "What a vendored family needs from OUTSIDE, for a process with no jar
  manifest: the checkout's own `deps.edn` `:deps`, handed WHOLE to every family
  [[framework-files-from-source]] found — `{\"http\" {lib coord …} …}`. Empty
  when no `deps.edn` can be found.

  The jar's manifest derives each family's deps exactly, by resolving every
  vendored file's requires against the basis that built the jar. A checkout
  has no basis to ask, but it has the thing that basis was BUILT from: the
  kernel `deps.edn`, whose own comment says every entry is there because a
  shipping namespace requires it. So under a checkout every family is handed
  all of them. More than a jar hands over, and only under a checkout — a
  built app from a checkout declares a few libs it does not load, which is
  the cost of not going stale.

  `deps.edn` is never a classpath RESOURCE (`:paths [\"src\"]`), so it is
  found as a file: the parent of whichever directory put `slopp/http.clj` on
  the classpath — a checkout's `src/`, a materialized tree's `src/` — else
  the working directory.

  Found by CI's native-web-app lane: the files vendored from source, the
  image came up, and `slopp.http.auth` died on `cheshire` — the failure the
  deps manifest was built after, one layer over."
  []
  (let [src-root (some-> (io/resource "slopp/http.clj") io/file .getParentFile .getParentFile)
        f        (some #(when (and % (.exists ^java.io.File %)) %)
                       [(some-> src-root .getParentFile (io/file "deps.edn"))
                        (io/file "deps.edn")])
        deps     (some-> f slurp edn/read-string :deps)
        deps     (into {} (remove (fn [[lib _]] (= "org.clojure" (namespace lib)))) deps)]
    (if (seq deps)
      (into (sorted-map)
            (map (fn [cap] [cap deps]))
            (keys (framework-files-from-source)))
      {})))

^:reads (defn ^:export framework-deps*
  "What the vendored framework needs from outside, keyed by capability: the
  jar's generated manifest (`boot/framework-deps`), else the checkout's own
  deps ([[framework-deps-from-source]]); nil when neither has anything. The
  sibling of [[framework-files*]], and every consumer asks both through these
  two doors so the files and their deps come from the same place."
  []
  (or (boot/framework-deps)
      (not-empty (framework-deps-from-source))))

(defn image-deps
  "The dep map an image for `store` should carry: the store's own manifest plus
  what the vendored framework requires.

  Vendoring hands over SOURCE, and source has requires. `slopp.http.css` needs
  garden, `slopp.http.html` needs hiccup, the servers need cheshire and http-kit
  — all of which used to arrive transitively through the coord's pom, and all of
  which vanished with it. The files landed and then failed inside themselves.

  Merged UNDER the store's manifest, not over it: an app pinning its own hiccup
  keeps it. slopp supplies what the framework needs, never what the app chose.

  **Only the families this store USES**, from the same `used-families`
  derivation the vendoring reads. `framework-deps` is keyed by capability for
  this reason: merging all of it would hand a web app cli's malli and a cli app
  garden, so every store would pay for every capability — the opt-in not
  holding in the one place a consumer notices it, their dependency list."
  [store]
  (let [used (used-families store)
        fw   (framework-deps*)]
    (if (and (seq used) (seq (framework-files*)))
      (apply merge (concat (map #(get fw %) used) [(get fw "_") (:deps store)]))
      (:deps store))))

(defn ^:export start-image!
  "THE door: every owned image is launched here, for `store`.

  It exists because there was no such door, and that cost three rounds. Four
  paths launch images — session open (`external/boot-image!`), `fresh-image!`
  (restart, deps changes, ns_rename, the D5 staleness heal), `branch/boot-line-image!`
  for a line, and the warm spare — and each was a sibling of `repl/start!`
  rather than a caller of one preparation. So \"every image gets X\" was a
  convention, re-implemented per path. Framework vendoring was added to one of
  the four; the branch-line path had it missing for a week and nobody noticed,
  because nothing exercises branches and web code together.

  This namespace's own docstring already names that class for WRITES —
  four gates hand-pasted at four write sites because the chokepoint was not
  used, and every later fix applied four times. `rebased-write!` is that
  chokepoint. This is its counterpart for images, and the lesson was available
  the whole time.

  Anything that must be true of EVERY image belongs in this function. If a new
  requirement shows up and you find yourself adding it to a caller, that is the
  bug repeating."
  [session store]
  (let [dir (framework-dir! session store)]
    (repl/start! (cond-> {:slopp.image.repl/deps (image-deps store)}
                   dir (assoc :slopp.image.repl/dir dir)))))

(defn image-with-deps!
  "A ready owned image for `store`: adopt the bare `spare` and hot-`add-libs`
  what the store needs into it, or launch fresh through [[start-image!]]. The
  caller owns spare bookkeeping (nil-ing + rewarming).

  Adoption is safe because [[start-spare!]] launches the spare in the session's
  framework dir. It briefly was not: a spare launched in its own dir has nothing
  vendored, and a JVM cannot pick up a relative classpath directory after
  launch, so adopting one handed back an image missing the framework. The first
  fix REFUSED adoption whenever a framework was needed — correct, and it cost
  the warm spare on every restart. Giving the spare the dir up front is the same
  guarantee without the loss, and it follows from having one door: whatever
  `start-image!` prepares, the spare is prepared with too.

  **The spare's dir must MATCH what this store now needs**, which is not the
  same as it having one. A spare is launched from the store as it was THEN; a
  store that gained web code since is a store whose spare was warmed without a
  framework, and adopting it would hand back the exact broken image this whole
  round has been about. `add-libs!` cannot repair that — it reconciles the
  MANIFEST, which can change between warming and adoption, and the framework is
  files, which cannot. Found by writing the adoption test rather than by hitting
  it, which is the order worth preferring."
  [session store spare]
  (let [deps (image-deps store)
        dir  (framework-dir! session store)]
    ;; `nil? dir` FIRST: a spare always has a dir of its own, so a bare
    ;; equality check refuses adoption for every store needing no framework —
    ;; which is most of them, and is a warm-spare regression rather than a
    ;; safety property. The dir only has to MATCH when one is required.
    (if (and spare (or (nil? dir) (= dir (:dir @spare))))
      (let [img @spare]
        (if (and (seq deps) (:err (repl/add-libs! img deps)))
          (do (repl/stop! img) (start-image! session store))
          img))
      (do (when spare (repl/stop! @spare))
          (start-image! session store)))))

(defn fresh-image!
  "Replace the image with a fresh process reloaded from the store — faithful by
  construction (the D5 backstop). With a warm spare, the swap avoids a JVM boot
  on the critical path; the next spare starts warming immediately.

  `for` is the store the new image is PREPARED for — its framework families
  vendored, their deps supplied — and it defaults to the committed store. A
  write hands the CANDIDATE: the first namespace of a fresh web project
  reaches for `slopp.http`, which the committed store never used, and an
  image prepared for the committed store cannot resolve it however many
  times it is relaunched. The namespaces LOADED are the committed store's
  either way; the caller replays the candidate's.

  A namespace that fails to load is RECORDED (session `:image-load-failures`,
  via [[load-all-namespaces!]]) and the boot continues — never thrown. The
  throw was half of a real consumer's wedge: a store invalidated from outside
  could not restart, and the write that would fix it died at the same error."
  ([session] (fresh-image! session (:store @session)))
  ([session for]
   (let [{:keys [image spare]} @session
         ;; THE fix: every image this session boots gets the framework, not just
         ;; the first. fresh-image! is on the path of restart, deps_add/remove,
         ;; branch switch, ns_rename and the D5 staleness heal — all of which
         ;; used to hand back an image with no framework at all, masked for as
         ;; long as the store still declared the coord.
         fresh (image-with-deps! session for spare)]  ; adopt+reconcile or fresh
     (repl/stop! image)
     (swap! session assoc :image fresh :spare nil)
     (start-spare! session)
     ;; No reset call: the new image carries its OWN record, minted empty by
     ;; `repl/start!`. This used to be `(currency/forget-all!)` against a
     ;; process-global atom, with a comment explaining that carrying the dead
     ;; image's stamps would claim the new one holds them. A record that cannot
     ;; outlive its image cannot make that claim.
     (let [{:keys [store image]} @session
           fails (load-all-namespaces! image store)]
       (swap! session assoc :image-load-failures (not-empty fails))
       ;; the loop above stamped every namespace it loaded, so the record is now
       ;; complete and a form without a stamp is real news. Arming only here —
       ;; never on a stamp — is what stops a half-filled record reporting the
       ;; whole store as never-loaded.
       (image.currency/arm! image)))))

(defn hot-load-all!
  "Checked-load `form-ids` from a CANDIDATE store value into the image (S1).
  nil on success; {:healed true} when a STALE IMAGE had to be refreshed to
  make the load succeed (D5.1); {:stubbed [qsyms]} when red-first stubs made
  a -test namespace compile (the generic red-first seam — the spec runs and
  fails honestly); {:err msg} when the forms genuinely don't compile (image
  restored either way; :first-err carries the pre-heal error when it
  differs). Keys compose.
  The heal boots from the COMMITTED store, so the candidate's touched nses
  are replayed from the CANDIDATE (dependency order, full load-ns! so new
  namespaces exist and are *loaded-libs*-stamped) before the retry —
  without that, a candidate that CREATES a namespace (extract_ns) dies
  with FileNotFound when a survivor requires it.
  A touched namespace sitting in the session's `:image-load-failures` (it
  failed the last boot) is RECONCILED on success: the candidate namespace is
  loaded WHOLE, and when it loads it leaves the failure set — so the write
  that fixes a boot-broken namespace verifies against the POST-edit state,
  which is the promise the boot note makes and the sequencing used to break
  (a real consumer's wedge, 2026-08-06)."
  [session candidate form-ids]
  (let [nses    (vec (distinct (keep #(store/ns-of-form-id candidate %) form-ids)))
        stub!   #(stub-missing-test-vars! (:image @session) candidate nses)
        replay! #(let [committed (set (keys (:namespaces (:store @session))))
                       ;; every namespace the CANDIDATE has that the COMMITTED
                       ;; store lacks, not just this call's. fresh-image! boots
                       ;; from the committed store, so those cannot survive it —
                       ;; and a MERGE creates them in EARLIER hot-load-all!
                       ;; calls whose form-ids are not ours. Replaying only
                       ;; `nses` left them missing and the dependent's :require
                       ;; died with FileNotFound: an error the heal itself
                       ;; MANUFACTURED, naming a classpath problem that never
                       ;; existed while burying the real first failure.
                       want      (into (set nses)
                                       (remove committed)
                                       (keys (:namespaces candidate)))]
                   (doseq [ns-sym (filter want (store/ns-dependency-order candidate))]
                     (image/load-ns! (:image @session) candidate ns-sym)))
        reconcile! #(when-let [failed (not-empty
                                       (set/intersection
                                        (set (map :ns (:image-load-failures @session)))
                                        (set nses)))]
                      (doseq [ns-sym failed]
                        (when (nil? (image/load-ns! (:image @session) candidate ns-sym))
                          (swap! session update :image-load-failures
                                 (fn [fs] (not-empty
                                           (vec (remove (comp #{ns-sym} :ns) fs))))))))]
    (letfn [(load-all []
              (loop [ids (seq form-ids)]
                (when ids
                  (or (when (store/jvm-loadable? candidate
                                             (store/ns-of-form-id candidate (first ids)))
                        ;; a :cljs form (js/*/DOM) is never JVM-loaded — skip it
                        ;; here exactly as image/load-ns! skips a :cljs ns, so the
                        ;; refactor ops (rename/move/extract/change-sig/…) work on
                        ;; client forms (D-web-cljs). Per-form-id, so a multi-ns op
                        ;; that mixes platforms loads the :jvm/:cljc ids and skips
                        ;; the :cljs ones in the same pass. A skipped id is nil,
                        ;; the same shape as an unresolved id, so the loop recurs.
                        (hotload/hot-load-form! (:image @session) candidate (first ids)))
                      (recur (next ids))))))
            (stub-round []
              ;; both red-first sources, stub-and-retry until the load is
              ;; clean or nothing new was stubbed (ingest!'s loop, group
              ;; face): the graph names qualified/referred missing vars at
              ;; once; an UNQUALIFIED same-ns symbol a deftest names before
              ;; it exists has no graph row, and only the load error names
              ;; it — one at a time. Returns [err stubbed].
              (if-let [err1 (load-all)]
                (loop [err err1, acc [], n 0]
                  (if (or (nil? err) (<= 12 n))
                    [err (not-empty acc)]
                    (let [;; BOTH sources, every round — never `or`. The graph source
                          ;; answers from the STORE and names the same vars every
                          ;; round; behind an `or` the load error's one unqualified
                          ;; symbol was never consulted, and a four-namespace change
                          ;; was refused as a compile error (eval22 step 2)
                          s   (into (vec (stub!))
                                    (some #(stub-unresolved-test-symbol!
                                            (:image @session) candidate % err)
                                          nses))
                          new (seq (remove (set acc) s))]
                      (if new
                        (recur (load-all) (into acc new) (inc n))
                        [err (not-empty acc)]))))
                [nil nil]))]
      (let [result
            (let [[err1 stubbed] (stub-round)]
              (cond
                (and (nil? err1) (nil? stubbed)) nil
                (nil? err1) {:stubbed stubbed}
                :else
                (do (fresh-image! session candidate)     ; maybe the image was stale — or prepared for a store that used one family fewer
                    (replay!)                            ; candidate truth over the committed boot
                    ;; a fresh image loses stubs — the round re-stubs from both sources
                    (let [[err2 stubbed2] (stub-round)]
                      (if err2
                        (do (fresh-image! session)
                            (cond-> (merge {:err err2}
                                           (edit/anchor-error candidate err2))
                              (not= err1 err2) (assoc :first-err err1)))
                        (cond-> {:healed true}
                          (seq stubbed2) (assoc :stubbed stubbed2)))))))]
        (when-not (:err result)
          (reconcile!))
        result))))

(defn diagnosed-run!
  "Run tests. Reds cross-check on a fresh image ONLY when staleness is
  plausible (D5.1: reload signatures, unexplained flips, missing provenance);
  a red clearly caused by the just-edited forms returns immediately as
  {:diagnosis :genuine} — no restart, no second run. `:fresh true` restarts
  FIRST and runs once against a guaranteed-faithful image."
  [session test-ns only & {:keys [edited fresh include-integration?]}]
  (when fresh (fresh-image! session))
  (let [skip? (not include-integration?)               ; M5: fast path skips
        r1    (traced-run! session test-ns only skip?)]
    (cond
      (green? r1) r1

      fresh (assoc r1 :fresh-confirmed true)

      (suspicious-red? session edited r1)
      (do (fresh-image! session)
          (let [r2 (traced-run! session test-ns only skip?)]
            (if (green? r2)
              (assoc r2 :staleness-detected true)
              (assoc r2 :fresh-confirmed true))))

      :else (assoc r1 :diagnosis :genuine))))

(defn run-verification!
  "Diagnosed run of `affected` tests (grouped by their namespace), or of all of
  `default-ns`'s tests when there's no trace information — and of `default-ns`
  ANYWAY when a non-empty `affected` resolves to no tests at all, because a
  scope that names tests which no longer exist is a stale scope, not an answer.
  `affected` = `[]` is exempt: that is a deliberate verify-nothing, not a gap. `:edited` (the
  just-changed form qsyms) powers the D5.1 genuine-vs-suspicious call and the
  red-result :implicated correlation (Rock 2). Results pass through the
  episode-red shaper (direction over repetition); `:boundary? true` (the
  done-point) bypasses compression and resets the ledger.

  The summary carries `:ms`, the wall time this verification took. It rides
  the `:verify` delta's `:result` through all twelve `record-verification`
  call sites, so the journal answers what verification COSTS as well as what
  it found. Without it the only after-the-fact attribution is the gap between
  consecutive deltas, which mixes tool execution with agent thinking and gets
  the answer wrong — the expensive whole-store operations are precisely the
  ones that leave no delta at all."
  [session default-ns affected & {:keys [edited fresh include-integration? boundary?]}]
  (let [t0 (System/currentTimeMillis)
        r  (cond-> (shape-episode-reds!
                    session
                    (implicate
                     (if (nil? affected)
                       (diagnosed-run! session default-ns nil :edited edited :fresh fresh
                                       :include-integration? include-integration?)
                       (let [by-affected
                             (reduce (fn [acc [tns tsyms]]
                                       (merge-with (fn [a b]
                                                     (cond (number? a) (+ a b)
                                                           (and (sequential? a) (sequential? b)) (into (vec a) b)
                                                           :else (or b a)))
                                                   acc
                                                   (diagnosed-run! session tns (mapv (comp symbol name) tsyms)
                                                                   :edited edited :fresh fresh
                                                                   :include-integration? include-integration?)))
                                     {}
                                     (group-by (comp symbol namespace) affected))]
                         ;; The affected set can name tests that no longer RESOLVE —
                         ;; a renamed deftest, a deleted one, a stale trace entry.
                         ;; Then this runs zero and reports :scope-ran-nothing, which
                         ;; `slopp.mcp/summarize` already classifies as a slopp bug:
                         ;; honest, and useless, because the write IS verifiable —
                         ;; just not by this scope. Fall back to the namespace, the
                         ;; same rule `done` uses per form when trace evidence is
                         ;; missing.
                         ;;
                         ;; Only when something WAS named. `affected` = [] is a
                         ;; DELIBERATE verify-nothing (an alias-only require change
                         ;; is semantically inert), so a blanket retry would overturn
                         ;; a considered decision instead of recovering from a stale
                         ;; one. nil means no evidence, [] means evidence of nothing
                         ;; to do, and only the first two warrant a second look.
                         (if (and (seq affected) (zero? (:test by-affected 0)))
                           (diagnosed-run! session default-ns nil :edited edited :fresh fresh
                                           :include-integration? include-integration?)
                           by-affected)))
                     (:test-map @session)
                     edited)
                    affected default-ns boundary?)
             ;; the done-point runs the external tier for REAL right after this, and
             ;; reports its own cap in :findings — an in-image deferral note there is
             ;; noise about an implementation detail
             boundary? (dissoc :external-pending))]
    (assoc r :ms (- (System/currentTimeMillis) t0))))

(defn ^{:export "slopp-server.mcp"} refresh-cache!
  "Advance the cached store from the journal (the record of truth in a
  durable session): INCREMENTALLY when every foreign delta in the suffix
  replays (the common case — no full re-parse), falling back to a full
  load-store otherwise (:ingest/:move/unknown ops). Advance-only — the
  cache can never regress, and \"ahead\" is read off `:line-pos`, the
  position along the line `record-delta` maintains.

  An incremental advance RE-ARRANGES the namespaces the suffix touched
  ([[arrange-replayed]]): replay appends an added form, the writer
  arranged it before its callers, and rendering follows the vector — so
  without this the replaying session rendered bytes no fresh process could
  load, while every warm check agreed with the writer.

  When the suffix is EMPTY something still committed, and it is not always
  bookkeeping: `elements` is the journal materialized, and a migration, a
  repair script or any external process rewriting rows changes what the store
  IS without appending a delta. Every branch here used to be gated on the
  suffix, so such a change was invisible indefinitely — and worse than
  invisible, since the next write re-persisted the cached shape over the
  migrated rows. `restart` cannot help: the stale value is upstream of the
  image.

  That case is gated on `db/elements-digest` rather than on `data_version`,
  which is too coarse to act on — it moves for git_map pins, the trace map and
  the dep-surface cache, none of which touch a form. An unrecorded digest
  (a session that has not refreshed yet) counts as CHANGED: absence is not
  agreement, and one rebuild per session is the cheap side of that bet.

  The LINE is resolved once and shared by all three reads. Digesting one line
  and rebuilding from another would advance the cache from a view it never
  graded — and once a session sits on its own thread rather than the trunk,
  that is not a hypothetical.

  Exposed to `slopp-server.mcp` for one caller: a project's app OWNER under the
  daemon has to hold the branch's current value before its server is
  re-served from it, and `sync-with-journal!` is gated on an image the
  owner may still be booting."
  [session]
  (when-let [conn (:db @session)]
    (let [line   (session-line session)
          ;; FORK ON WRITE: a thread with no un-landed content has nothing to
          ;; pin, so when the branch moved it re-forks at the new head — the
          ;; state a fresh thread would start from — and the value is reloaded
          ;; from there. A thread with WORK keeps its pin; rows alone are not
          ;; work (`follow-branch-if-idle!`).
          _      (follow-branch-if-idle! session)
          local  (:store @session)
          suffix (db/deltas-after conn line (:line-pos local 0))
          digest (db/elements-digest conn line)]
      (if (seq suffix)
        (let [incr  (reduce (fn [st d]
                              (if-let [st' (store/replay-delta st d)]
                                st'
                                (reduced nil)))
                            local suffix)
              ;; the incremental path replays FOREIGN deltas verbatim, and a
              ;; :commit marker arrives carrying its whole files manifest — so
              ;; every commit-point landed during this server's life would add one
              ;; back, undoing at runtime what load-store does at open. The
              ;; full-load fallback thins itself.
              fresh (if incr
                      (arrange-replayed incr suffix)
                      (db/load-store conn line))]
          (when fresh
            (swap! session
                   (fn [s]
                     (if (> (:line-pos fresh 0) (:line-pos (:store s) 0))
                       (assoc s :store fresh)
                       s)))))
        ;; the journal did not move, so the position advance test cannot
        ;; decide this one — the rows themselves are the evidence, and they
        ;; were just read from the db, so accepting them is not a regression
        (when (not= digest (:elements-digest @session))
          (swap! session update :store assoc :namespaces (db/load-elements conn line))))
      (swap! session assoc :elements-digest digest))))

(defn ^:export commit-appended!
  "Commit a pure APPEND `f` (store → store', deltas only unless `nses`),
  retrying across journal/cache races. Returns the committed store'.

  **The retry is a race now, not a livelock.** It used to be possible for
  every attempt to be identical: two sessions drawing ids from one shared
  counter re-derived the same id each pass and collided twelve times in a row.
  Ids are random names minted per call, so a losing attempt genuinely differs
  from the one before it — which is what makes retrying a strategy rather than
  a ritual.

  Exhausting the retries therefore means one thing, and the throw says it:
  the branch head moved under every attempt because another writer is landing
  continuously."
  [session f nses]
  (loop [n 0]
    (let [base (:store @session)
          st'  (f base)]
      (cond
        (try-commit! session base st' nses) st'
        (< n 12) (do (refresh-cache! session) (recur (inc n)))
        :else
        (throw (ex-info
                (str "the branch head moved under all 12 attempts — another"
                     " writer is landing continuously. Ordinary contention on a"
                     " busy branch: call again.")
                {:retryable true}))))))

(defn rebased-write!
  "Run a single-form write with an atomic rebasing commit (item 4, the
  granularity dodge). The pure `transform` (store → {:store :delta ...} |
  {:error}) runs INSIDE swap!, so concurrent different-form writes rebase and
  land without locks or starvation; if the TARGET form itself changed since
  this op began (`target-node`: store → CST node), the commit aborts with
  {:conflict ...} — C5's MV-register semantics, Phase-1 face.
  The compile gate runs once, before commit: the form's CONTENT (what the
  image compiles) is invariant across rebases. Red-first stubs surface as
  :red-first; lint errors in OTHER forms (stale callers) surface as
  :carried-errors — both ride the result, never block, and the done-point
  re-checks. A genuine compile failure returns an ANCHORED error
  (edit/compile-error — form + snippet, no file:line).
  AUTO-AVOID-DECLARE: the pure transform is WRAPPED so a candidate with a
  forward reference is reordered (defs moved above callers) before the
  cold-load gate — the agent never writes (declare ...). The reorder rides
  inside the swap! rerun too, so durable rebasing stays consistent; a genuine
  cycle (mutual recursion) reorder can't fix falls through to the existing
  refusal, which teaches the declare."
  [session raw-transform target-node target-desc ns-sym
   & {:keys [load?] :or {load? true}}]
  (let [orig      (some-> (target-node (:store @session)) n/string)
        conflict  {:conflict {:form target-desc
                              :reason "form changed concurrently — re-read and retry"}}
        transform (fn [base]
                    (let [out (raw-transform base)]
                      ;; NOT gated on `load?`. Ordering is a property of the SOURCE, and
                      ;; ClojureScript has the same define-before-use rule (its
                      ;; compiler warns). Skipping the reorder for :cljs let a
                      ;; forward reference land SILENTLY — and since the move gate
                      ;; still refused any move while a violation stood, no single
                      ;; edit_move reached a legal state. Created without a word,
                      ;; then unfixable by the tool the error message recommends.
                      (if (:error out)
                        out
                        (if-let [rz (edit/resolve-cold-load
                                     (:store out) ns-sym
                                     :prompt "auto-reorder: define before use")]
                          (assoc out :store (:store rz))
                          out))))]
    (if (:db @session)
      ;; durable: the JOURNAL arbitrates (m5a) — append-CAS, refresh, rebase
      (loop [attempt 0, loaded? false, healed? false, stubbed nil, carried nil]
        (if (> attempt 12)
          {:error "commit contention: too many concurrent writes — retry"}
          (let [base (:store @session)
                cur  (some-> (target-node base) n/string)]
            (if (and (pos? attempt) (not= orig cur))
              ;; the loser's code is already hot-loaded, and each durable
              ;; session has its OWN image — the winner's hot-load happened in
              ;; another process. Reboot from the refreshed store so nothing
              ;; verifies against code the journal rejected.
              (do (when loaded? (fresh-image! session))
                  conflict)
              (let [out (transform base)]
                (if (:error out)
                  out
                  (let [load-res (when (and load? (not loaded?))
                                   (let [lr (lintgate/lint-refusals base (:store out) [ns-sym]
                                                                [(:form-id (:delta out))])]
                                     (if-let [gate (or (edit/cold-load-errors (:store out) [ns-sym])
                                                       (:refuse lr))]
                                       {:err gate}
                                       (merge (hot-load-all! session (:store out)
                                                    [(:form-id (:delta out))])
                                     (select-keys lr [:carried :red-first-arity])))))]
                    (if (:err load-res)
                      (edit/compile-error (:store out) (:err load-res)
                                          "form failed to compile: " ns-sym)
                      (do (when *pre-commit-hook* (*pre-commit-hook*))
                          (if (try-commit! session base (:store out) [ns-sym])
                            (cond-> out
                              (or healed? (:healed load-res))
                              (assoc :image-healed true)

                              (or stubbed (:stubbed load-res))
                              (assoc :red-first (or stubbed (:stubbed load-res)))

                              (or carried (:carried load-res))
                              (assoc :carried-errors (or carried (:carried load-res)))

                              ;; NOT threaded through the retry like `stubbed`
                              ;; and `carried` are: those two decide whether the
                              ;; write is honest, this one is a note explaining
                              ;; a red the agent is about to see anyway. A
                              ;; CONTENDED write (attempt > 0, where the gate no
                              ;; longer runs) drops it, and the cost is a missing
                              ;; sentence rather than a missing verdict.
                              (:red-first-arity load-res)
                              (assoc :red-first-arity (:red-first-arity load-res)))
                            (do (refresh-cache! session)
                                (recur (inc attempt) true
                                       (or healed? (boolean (:healed load-res)))
                                       (or stubbed (:stubbed load-res))
                                       (or carried (:carried load-res))))))))))))))
      ;; ephemeral: the pure transform reruns INSIDE swap! — starvation-free.
      ;; No image heal on conflict here: ephemeral writers share ONE image, so
      ;; the competitor's own hot-load already put the winner's code in it.
      (let [base0 (:store @session)
            out0  (transform base0)]
        (if (:error out0)
          out0
          (let [load-res (when load?
                           (let [lr (lintgate/lint-refusals base0 (:store out0) [ns-sym]
                                                        [(:form-id (:delta out0))])]
                             (if-let [gate (or (edit/cold-load-errors (:store out0) [ns-sym])
                                               (:refuse lr))]
                               {:err gate}
                               (merge (hot-load-all! session (:store out0)
                                             [(:form-id (:delta out0))])
                                      (select-keys lr [:carried :red-first-arity])))))]
            (if (:err load-res)
              (edit/compile-error (:store out0) (:err load-res)
                                  "form failed to compile: " ns-sym)
              (do (when *pre-commit-hook* (*pre-commit-hook*))
                  (let [res (volatile! nil)]
                    (swap! session update :store
                           (fn [base]
                             (if (not= orig (some-> (target-node base) n/string))
                               (do (vreset! res conflict) base)
                               (let [out (transform base)]
                                 (if (:error out)
                                   (do (vreset! res out) base)
                                   (do (vreset! res out) (:store out)))))))
                    (cond-> @res
                      (and (nil? (:error @res)) (nil? (:conflict @res))
                           (:healed load-res))
                      (assoc :image-healed true)

                      (and (nil? (:error @res)) (nil? (:conflict @res))
                           (:stubbed load-res))
                      (assoc :red-first (:stubbed load-res))

                      (and (nil? (:error @res)) (nil? (:conflict @res))
                           (:carried load-res))
                      (assoc :carried-errors (:carried load-res))

                      (and (nil? (:error @res)) (nil? (:conflict @res))
                           (:red-first-arity load-res))
                      (assoc :red-first-arity (:red-first-arity load-res))))))))))))

(defn ^:export adopt-line!
  "Put the session on its thread for the current branch, resynchronizing the
  store and the image when that thread already holds work. Returns the line
  id, or nil for an ephemeral session.

  Called when a session's IDENTITY becomes known — the harness session id
  arrives on the first prompt, after the session is already open — and
  therefore after the store and image were loaded from the branch. If the
  agent left un-landed work in a thread last time, both are loaded from the
  wrong line, and only the store would heal on its own: the write CAS fails,
  the cache refreshes, and the write lands. The IMAGE would not, and a
  verification run against the branch's code while the store holds the
  thread's is a wrong verdict rather than a slow one.

  So the reboot is gated on the one question that distinguishes the two
  cases: does the thread's head match what the session is holding? A freshly
  minted thread sits exactly on the branch head, which is what the session
  loaded, so the ordinary path costs two SELECTs and no reboot. Only a
  genuine resume pays for an image."
  [session]
  (when-let [conn (:db @session)]
    (let [line (db/adopt-thread! conn (session-branch-line session)
                                 (thread-key session))]
      (swap! session assoc :line line)
      ;; a thread with nothing to pin follows the branch before anything is
      ;; read from it (2026-09-03: a stale copy served as the branch)
      (follow-branch-if-idle! session)
      (when (not= (db/line-head conn line)
                  (:head (:store @session)))
        (swap! session assoc :store (db/load-store conn line))
        (when (:image @session) (fresh-image! session)))
      line)))

(defn ^:export follow-branch-if-idle!
  "Re-fork the session's thread at its branch's head when it has NOTHING TO
  PIN — no un-landed work of its own — and its base is behind the branch;
  the store value is reloaded from there. True when it did, nil otherwise.

  Rows are not a pin; WORK is. A thread minted before fork on write carries a
  full copied view with not one write in it, and a rule that re-forked only
  ROWLESS threads served that copy as the branch after a restart: the live
  server ran code 1,238 deltas behind, every read answered from it, and
  nothing said so (2026-09-03). What a thread has written since its fork is
  the only thing that can hold its view still.

  And work is ANYTHING that is not a marker (`db/unlanded-work-count`), not
  the form-content ops the badge counts: an ns_rename, a deps_add or a
  module_purity is a delta on the thread with no form behind it, and counting
  content alone read such a thread as idle and re-forked it — declaration
  dropped — the next time anybody landed.

  Called at every point a session lands on a thread — `open!` when the
  caller named the agent, `adopt-line!` when the identity arrives later, and
  `refresh-cache!` on every sync — because the first two happen BEFORE any
  journal change could make the third fire.

  The re-fork leaves the thread's own markers behind in the journal; an OPEN
  turn is the ask's bracket and rides across, so a one-shot process that
  began a turn before another agent landed still finds it open."
  [session]
  (when-let [conn (:db @session)]
    (let [line   (:line @session)
          branch (session-fork-line session)]
      (when (and line branch (not= line branch)
                 (zero? (db/unlanded-work-count conn line))
                 ;; MOVED means the thread's BASE is behind the branch — not its
                 ;; head, which a turn marker of its own advances
                 (not= (db/line-base conn line) (db/line-head conn branch)))
        (let [agent (:agent-id @session)
              marks (filter #(and (contains? #{:turn-begin :turn-end} (:op %))
                                  (= agent (:agent %)))
                            (db/line-deltas conn line))
              open  (let [m (last marks)] (when (= :turn-begin (:op m)) m))]
          (db/refork-thread! conn line branch)
          (swap! session assoc :store (db/load-store conn line))
          (when open
            (commit-appended! session
                              #(first (store/record-turn % :turn-begin
                                                         :agent agent
                                                         :intent (:intent open)
                                                         :user (:user open)))
                              []))
          true)))))
