(ns slopp.ops.external
  "The api's IO face — the operations that leave this process.

  Opening a session boots a JVM and a sqlite connection; the external test
  tier spawns processes; `build!` writes files; `commit_point!` reaches git.
  Those live here rather than in `slopp.api` so the tier boundary is a
  namespace boundary too, which is what lets the pure core behind it be
  declared pure and tested at ~0.5ms instead of ~370ms.

  It is also where the WHOLE-STORE questions land — `full_check!`,
  `built-store` — because each needs the store AND something outside it: a
  fresh JVM, or a materialized project on disk. `built-store` in particular
  exists to end a specific failure: a whole-store invariant with no store to
  reach passes on a population of zero, which is indistinguishable from
  passing on the truth."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str] [slopp.store.db :as db] [clojure.java.io :as io] [rewrite-clj.node :as n] [slopp.ops :as ops] [slopp.project.deps :as project.deps] [slopp.ops.done :as done] [slopp.read.history :as history] [slopp.read.modules :as read.modules] [slopp.rules :as rules] [slopp.ops.engine :as engine] [slopp.ops.testrun :as testrun] [slopp.build :as build] [slopp.edit :as edit] [slopp.edit.modules :as edit.modules] [slopp.index :as index] [slopp.store.render :as store.render] [slopp.image.repl :as repl] [slopp.store :as store] [slopp.index.analyze :as analyze] [slopp.project.capabilities :as capabilities] [slopp.read.orient :as orient] [slopp.index.crossings :as crossings] [slopp.store.artifacts :as artifacts] [slopp.rules.currency :as rules.currency] [slopp.image.currency :as image.currency] [slopp.edit.tiers :as tiers] [slopp.kernel.boot :as boot] [slopp.ops.branch :as branch] [slopp.edit.cli :as edit.cli] [slopp.rules.webapp :as rules.webapp] [slopp.store.fields :as fields]))

^:reads (defn ^:export git-config-value
  "`git config <k>` as git would resolve it in `dir` (local then global), or
  nil. The \"<git>\" fallback of the G5 author config."
  [dir k]
  (let [r (sh/sh "git" "-C" (str dir) "config" k)]
    (when (zero? (:exit r))
      (let [v (str/trim (:out r))]
        (when-not (str/blank? v) v)))))

^:reads (defn ^:export author-identity
  "The author identity milestones are stamped with (G5): meta `user.name` /
  `user.email`; a key that is unset or \"<git>\" defers to `git config` in
  the project dir. Nil when nothing resolves (the projection then falls back
  to the legacy agent identity). Durable sessions only."
  [session]
  (when-let [conn (:db @session)]
    (let [dir (:dir @session)
          res (fn [k]
                (let [v (db/get-meta conn k)]
                  (if (or (nil? v) (= v "<git>"))
                    (git-config-value dir k)
                    v)))
          nm  (res "user.name")
          em  (res "user.email")]
      (when (and nm em)
        {:name nm :email em}))))

(defn ^:export config!
  "Read or set store config (the meta k/v side-table): keys `user.name` /
  `user.email` — the git author identity milestones are stamped with (G5).
  A key unset or set to \"<git>\" defers to `git config <key>` in the project
  dir, resolved AT MILESTONE TIME. With no `v`: read —
  {:key :configured :effective}. Durable sessions only."
  [session k & [v]]
  (let [allowed #{"user.name" "user.email" "git-remote"}]
    (cond
      (not (contains? allowed (str k)))
      {:error (str "unknown config key " k " — allowed: "
                   (str/join ", " (sort allowed)))}

      (not (:db @session))
      {:error "config lives in the durable store (this session has no db)"}

      (some? v)
      (do (db/set-meta! (:db @session) (str k) (str v))
          {:key (str k) :configured (str v)})

      :else
      (let [conf (db/get-meta (:db @session) (str k))]
        {:key (str k)
         :configured conf
         :effective (if (or (nil? conf) (= conf "<git>"))
                      (git-config-value (:dir @session) (str k))
                      conf)}))))

(defn- boot-image!
  "Bring the session's image up: spawn it, warm a spare, schedule the branch
  reaper, load every namespace (dependency order, stubbing red-first specs),
  and adopt modules. THE slow part of open! (loading N namespaces into a
  child JVM). On the SYNC path (no :image-ready) a failure throws, as open!
  always did; on the ASYNC path (a background thread) the failure is
  delivered to the ready-promise so `api/await-image!` surfaces it on first
  oracle use instead of killing the server at startup. Returns the session.

  Every boot is RECORDED as an `image-boot` measurement — `{:ms :namespaces
  :recycled? :failures :head}` — beside the journal. The JVM-budget A/B was
  one careful experiment done by hand; a row per boot makes every boot an
  observation, and variance-vs-drift a query rather than a re-run."
  [session store conn agent-id ttl]
  (try
    (let [t0    (System/nanoTime)
          ;; A recycled image carrying EXACTLY this store's classpath, or nil
          ;; and a real boot. `add-libs!` cannot be undone, so an image that
          ;; carried deps is no longer the baseline it was parked against.
          ;; Keying by deps is what makes reuse apply to a real project: the
          ;; first cut refused any store with dependencies, so it only ever
          ;; helped dep-free stores — slopp's own fixtures and nothing a user
          ;; has.
          ;;
          ;; DELIBERATELY only here, at session creation. `fresh-image!` is the
          ;; D5 staleness backstop, where a genuinely new process IS the point;
          ;; recycling there would undermine the diagnostic that catches a
          ;; stale image, which is the opposite of what this is for.
          ;;
          ;; And a store needing the FRAMEWORK never recycles either: a parked
          ;; image has its own dir with nothing vendored, and a JVM cannot pick
          ;; up a relative classpath dir after launch — so reuse would hand back
          ;; an image missing the framework, surfacing as a missing namespace at
          ;; first use, far from here. Recycling exists for dep-free fixture
          ;; stores, which by definition need no framework, so nothing that
          ;; benefits today loses anything.
          ;;
          ;; The dir is the SESSION's, not a private one: every image this
          ;; session boots reuses it, which is what makes a restart work. A
          ;; private dir here is exactly why restart booted without the
          ;; framework at all.
          vdir     (engine/framework-dir! session store)
          recycled (when-not vdir (repl/unpark! (engine/image-deps store)))
          image    (or recycled (engine/start-image! session store))]
      (swap! session assoc :image image)
      (engine/start-spare! session)
      (let [t      (java.util.Timer. "slopp-branch-reaper" true)
            period (long (max 1000 (quot ttl 3)))]
        (.schedule t
                   (proxy [java.util.TimerTask] []
                     (run [] (try (ops/reap-idle-images! session)
                                  (catch Throwable _))))
                   period period)
        (swap! session assoc :reaper t))
      ;; No reset: a fresh image is minted with an empty record and a recycled
      ;; one was emptied by `reset-to-baseline!`, which is where its handle
      ;; changed tenant. This used to reset a process-global atom, because the
      ;; stamps it carried described an image that no longer existed.
      ;; note-and-continue, kernel-host parity: a namespace that fails to load
      ;; is RECORDED, not thrown. The throw parked its Throwable in
      ;; :image-ready and await-image! rethrew it in front of every non-read
      ;; tool — including the edit that would fix the namespace (a real
      ;; consumer's wedge, 2026-08-06). Process-level failures still throw
      ;; through the outer catch; a per-namespace compile failure is the
      ;; store's business, and the store stays open to fix it.
      (let [fails (engine/load-all-namespaces! image store)]
        (swap! session assoc :image-load-failures (not-empty fails))
        (when conn
          (db/record-measurement!
           conn "image-boot" nil
           {:ms         (quot (- (System/nanoTime) t0) 1000000)
            :namespaces (count (:namespaces store))
            :recycled?  (some? recycled)
            :failures   (count fails)
            :head       (:head store)})))
      ;; ARM only now, with everything stamped: from here an unstamped form
      ;; means never-loaded rather than not-yet-looked-at. Without this the
      ;; registry stayed unarmed for a whole session and every currency
      ;; surface honestly reported "not measured" — correct, and useless,
      ;; because only a restart (through fresh-image!) ever armed it.
      (image.currency/arm! image)
      ;; module adoption: a populated store from a pre-module db (:modules
      ;; nil) gets its manifest derived from reality, once — fresh stores
      ;; are born with {} and enforcement already on
      (when (and conn (seq (:namespaces store))
                 (or (nil? (:modules store))
                     (and (empty? (:modules store))
                          ;; "has any module edge ever been declared" is one indexed read
                          (nil? (db/last-marker conn (or (:line @session) (db/trunk-line-id! conn))
                                                :module-edge)))))
        (ops/adopt-modules! session :agent (or agent-id "slopp")))
      (when-let [p (:image-ready @session)] (deliver p :ok))
      session)
    (catch Throwable t
      (if-let [p (:image-ready @session)]
        (do (deliver p t) session)   ; async: rides home to await-image!
        (throw t)))))

(defn ^:export client-build-deps
  "slopp's OWN toolchain deps for a BUILD of `store`, injected at build time —
   NEVER user manifest deltas — when the store carries CLIENT code (:cljc/:cljs):
   {:runtime {lib coord} :client {lib coord}}. malli goes in the runtime channel
   (schema code loads on the JVM oracle + external tier, and the :cljs compile
   inherits base :deps), versioned centrally from repl/inherent-deps; the
   configured compiler (build/compiler-coord) goes in the build-only :client
   channel. {:runtime {} :client {}} for a non-client store, so its generated
   deps.edn stays byte-identical. slopp versions these centrally so an upgrade
   reaches every store with no migration; the agent adds only APPLICATION deps (a
   D-web-contracts dogfood finding — the two-config split the user named)."
  [store]
  (if (some #(#{:cljc :cljs} (store/platform-for store %)) (keys (:namespaces store)))
    (let [[clib ccoord] (build/compiler-coord (build/client-compiler store))]
      {:runtime (select-keys repl/inherent-deps '[metosin/malli])
       :client  (if clib {clib ccoord} {})})
    {:runtime {} :client {}}))

(defn ^:export store-health
  "What this store CARRIES, in bytes — the journal per op (heaviest first), the
  materialized state, the blob table, and the on-disk artifact cache. Cheap:
  SQLite LENGTH and `File.length` only, nothing parsed.

  Reach for it when a session feels slow to open, before growing what a delta
  carries, and periodically. `full_check` answers whether the store is CORRECT;
  this answers what it COSTS, and nothing else did — which is how a byte-exact
  tree snapshot in every milestone reached 94% of a 344MB journal, unnoticed
  across 239 of them, against a design note estimating \"tens of KB\". Naming it
  was not enough either: it was still 82MB, 39% of the journal, when the
  snapshot was finally removed rather than made cheaper. A store can rot by
  growing.

  `:artifacts` is here because derived files now live OUTSIDE the journal. That
  change removed 30MB from the delta log, and would have re-created the very
  blind spot this tool was built for if the bytes had simply moved somewhere
  nothing counted. Its `:orphaned` figure is the reclaimable one."
  [session]
  (let [{:keys [db dir store]} @session]
    (merge (if db
             (db/journal-stats db)
             {:note "no durable store on disk yet — nothing has been written"})
           {:artifacts (artifacts/cache-stats dir store)})))

(defn- host-warning-now
  "The host code-currency warning for a verdict produced right now, or nil.

  The kernel namespace exists only in a process that BOOTED from a store (the
  MCP server, a jar launch), so the carrier is reached defensively and any
  failure reads as absence — a test JVM cannot be stale, because nothing
  hot-reloaded into it. One resolver for every verdict surface: done,
  full_check and test_run must not disagree about whether the host is
  current.

  That guard is also what makes it safe to COMPARE rather than count. An
  empty record honestly means \"this image loaded nothing\", which is true and
  useless in a plain test JVM; gating on the boot record means drift is only
  ever computed where an empty record would be news.

  Takes the SESSION: the count of code deltas since boot is a journal read
  (`db/code-deltas-after`, by clock), and the image whose record is read is
  the session's oracle. It used to be implied, because the record was a
  process-global atom and \"the image\" meant the oracle by convention — the
  assumption that broke the day a second image started running on purpose."
  [session st]
  (when-let [info (try ((store/late-ref 'slopp.kernel.boot/current-boot-info))
                       (catch Throwable _ nil))]
    (orient/host-warning info
                         (db/code-deltas-after (:db @session) (engine/session-line session)
                                               {:at (:booted-at info 0)})
                         (rules.currency/drift (:image @session) st))))

(def ^:export external-slice-cap
  "How many impacted `^:external` tests `done` will run before deferring to
  `full_check`.

  It was 40, and the reason was mechanical rather than principled: a narrowed
  run could not shard (`:only` forced `par` = 1 and one serial JVM), so a
  large impacted set cost MORE than the sharded full suite and deferring was
  the least-bad option available. `testrun/only-shards` removed that, so the
  number is re-derived from what deferrals actually looked like.

  Measured over 40 consecutive dones: 15 deferred, and they cluster at both
  ends — six between 52 and 136 tests, nine between 339 and 394 of 409. The
  first group is real narrowing the trace map had computed correctly and
  nothing ran; 150 converts all of it. The second is a change to the core,
  where the impacted set IS the suite and narrowing saves nothing — that is
  what `full_check` is for, and no selection can improve on it.

  A var so a test can bind it rather than build 151 fixture tests to cross it."
  150)

^:reads (defn ^:export built-store
  "The store value reconstructed from the MATERIALIZED PROJECT at `dir`
  (default: the working directory) — the seam a whole-store invariant test
  needs, and the thing every own-store guard has been missing.

  **The problem it solves.** A guard that wants to assert something about the
  whole store — no prose naming a tool that does not exist, every form
  certifying or marked fallback — cannot reach one. The `^:external` tier runs
  in a temp dir that `build!` filled with SOURCE and no `.slopp/store.db`, so
  `(open! {:slopp.ops/dir \".\"})` hands back an EMPTY store, the scan finds
  nothing, and the guard passes on nothing. `slopp-prose-never-names-a-tool-
  that-does-not-exist` has been green that way since it was written, and
  `root-cause-fix-plan` item 2 has been blocked on exactly this.

  The store is recoverable without any of it: the code is on disk, and
  ingesting it back yields what a code-shaped invariant needs — namespaces,
  forms, CSTs. No db, no origin path plumbed through the runner, no marker
  file in a user's build output.

  **REFUSES rather than returning an empty store.** A directory with no
  Clojure under `src/` throws. That is the whole point: vacuity has to be
  loud, because a guard scanning nothing is indistinguishable from a guard
  finding nothing wrong, and this seam exists to end that.

  **What it is NOT.** Deltas, module registers, purity tiers and form IDS do
  not survive the round trip — `ingest` re-mints ids, and the journal is not
  in the build at all. This answers questions about CODE. A question about
  history or provenance needs the live store and is not what this is for."
  ([] (built-store "."))
  ([dir]
   (let [root  (io/file dir)
         ;; `.cljs` too, and it is the reason this line has a comment. A `:cljs`
         ;; namespace invisible here is invisible to EVERY whole-store guard
         ;; standing on this seam, and each one then reports clean on a
         ;; population that silently excludes browser code — the failure this
         ;; function was written to end, recurring inside it one extension wide.
         ;; Found by a guard that asserts its own population before it asserts
         ;; anything else, on the run that should have gone green.
         clj?  #(and (.isFile ^java.io.File %)
                     (re-find #"\.clj[cs]?$" (.getName ^java.io.File %)))
         srcs  (for [sub ;; `cljs-src` is the third root, and the one that was missing.
                      ;; `build!` renders a `:cljs` namespace there rather than
                      ;; under `src/`, off the JVM classpath by design — so
                      ;; browser code was invisible to every whole-store guard
                      ;; standing on this seam, and each reported clean on a
                      ;; population that quietly excluded it
                      ["src" "test" "cljs-src" "cljs-test"]
                     :let [d (io/file root sub)]
                     :when (.isDirectory ^java.io.File d)
                     f (file-seq d)
                     :when (clj? f)]
                 [(->> (.relativize (.toPath (io/file root sub)) (.toPath ^java.io.File f))
                       str
                       (#(str/replace % #"\.clj[cs]?$" ""))
                       (#(str/replace % #"/" "."))
                       (#(str/replace % #"_" "-"))
                       symbol)
                  (slurp f)])]
     (when-not (seq srcs)
       (throw (ex-info (str "no source under " (.getPath root) "/src — this is"
                            " not a materialized slopp project, and returning an"
                            " empty store here is how a whole-store guard comes"
                            " to pass on nothing")
                       {:dir (.getPath root)})))
     (reduce (fn [st [ns-sym src]] (store/ingest st ns-sym src))
             (store/empty-store)
             srcs))))

(defn- materialize-artifacts!
  "Copy every registered artifact into `target`, returning what could NOT be
  resolved — `[{:path :sha :recipe :refill :why}]`, empty when all landed.

  Derived files hold a sha and a recipe in the store and their bytes in the
  on-disk cache, so a cold clone has a manifest and an empty cache. That is
  the designed state, not an error — but a build that skips the file and
  still reports `{:built …}` turns it into the failure the recipe exists to
  prevent: the compile breaks much later as a missing namespace, nowhere near
  the cause.

  Reports rather than refuses, deliberately. `compile_client` calls `build!`
  on its way to regenerating the very artifact that may be missing, so a
  refusal here would make the one path that can fix the gap the one path
  that cannot run."
  [session st target]
  (vec
   (keep (fn [[path _]]
           (let [r    (artifacts/fetch (:dir @session) st (str path))
                 file (io/file target (str path))]
             (if-let [^bytes bs (:bytes r)]
               (do (io/make-parents file)
                   (io/copy bs file)
                   nil)
               {:path   (str path)
                :sha    (:sha r)
                :recipe (:recipe r)
                :why    (or (:why r) (:error r))
                :refill (artifacts/refill-instruction (str path) (:recipe r))})))
         (:artifacts st))))

(defn- unchanged-since-done
  "The STANDING done result when nothing has happened since it, else nil.

  Three callers each reasonably ask for a done-point and none can see that the
  others just did: the agent, the plugin's Stop hook, and `commit-point!`, which
  runs `done!` itself because the milestone has no gates of its own. Measured on
  slopp's own store, that left five `:done` deltas in the last eight, every one
  recording `:test-status :none` — the representation that means \"this judged
  nothing\". A log of markers asserting nothing is worse than quiet: it is what
  `session_brief`'s `:last-done` reads, so a `:none` can surface while a real
  verdict sits behind it.

  Making the no-op the CALLEE's job is what lets all three keep calling. The
  alternative — each caller checking whether a done is still current — is three
  places reasoning about verdict freshness, which is the second-bar shape
  `commit-point!`'s own docstring warns about.

  The condition is NOT \"the previous delta is a done\": `:turn-begin`,
  `:turn-end` and `:verify` markers interleave, so that test misses the ordinary
  case. It is that every delta since the last done is [[bookkeeping-ops]] — an
  allow-list, for the reason recorded there. `:normalize` is emitted by done
  itself but lands BEFORE the boundary marker, so a done that rewrote something
  is correctly not seen as unchanged."
  [st]
  (let [back  (reverse (:recent st))
        tail  (take-while #(not= :done (:op %)) back)
        prior (first (drop-while #(not= :done (:op %)) back))]
    (when (and prior
               (every? #(contains? fields/bookkeeping-ops (:op %)) tail))
      {:done     (:id prior)
       :normalized 0
       :rewrites []
       :lint     []
       :findings (:findings prior)
       :note     (str "nothing has happened since the done-point "
                      (:id prior)
                      (when-let [l (:label prior)] (str " (" l ")"))
                      " — its verdict still stands and no second boundary was"
                      " recorded. A done that judged nothing must not supersede"
                      " one that judged something.")})))

(defn ^:export observation-of
  "An external run's `result` as an OBSERVATION: `{:tier :status :ran
  :failures}`, where `:failures` is a list of `{:test <symbol>}`.

  **Qualification is the load-bearing step.** clojure.test prints
  `FAIL in (name)` and [[slopp.ops.testrun/parse-test-failures]] carries that
  BARE name, while a reader of red evidence — `rules/assertions-never-red-check`
  — compares `(str 'ns/name)`. An unqualified name matches nothing, so this
  resolves it against `store` and keeps the bare symbol where the answer is
  ABSENT or AMBIGUOUS. Bare is the safe direction: an unmatched name makes the
  advisory fire again, never closes it silently.

  `:failures` is `[]` rather than absent on a green run, so a reader never has
  to tell 'no failures' from 'no answer'. Pure, and separate from
  [[record-run-observation!]] for a reason: a GREEN run records `[]` and
  exercises none of the qualification, so this is where the evidence that it
  works has to come from."
  [store result]
  (let [qualify (fn [nm]
                  (let [s (str nm)]
                    (if (str/includes? s "/")
                      (symbol s)
                      (let [hits (distinct
                                  (for [n (keys (:namespaces store))
                                        f (store/forms store n)
                                        :when (= s (str (:name f)))]
                                    (symbol (str n) s)))]
                        (if (= 1 (count hits)) (first hits) (symbol s))))))]
    (cond->
     {:tier     :external
      :status   (:status result)
      :ran      (:ran result)
      ;; DISTINCT: clojure.test emits a FAIL block per failing ASSERTION, so one
      ;; red test arrives three times. The observation records which tests went
      ;; red, not how many of their assertions did — measured on a real red run
      :failures (vec (distinct (for [f (:failing result) :when (:test f)]
                                 {:test (qualify (:test f))})))}
      ;; per-NAMESPACE wall time, when the build's runner measured it.
      ;; CONDITIONAL, and that is the whole care here: a run from a build with
      ;; no timing runner must leave the key ABSENT, because the reader this
      ;; exists for is a shard balancer, and a balancer that reads "unmeasured"
      ;; as zero packs an expensive namespace as though it were free — the
      ;; exact mistake that made the last re-weighting measure WORSE than the
      ;; boot-count proxy it replaced.
      (:ns-ms result) (assoc :ns-ms (:ns-ms result))
      ;; what the run WAS — `standing-run` finds a repeat by these
      (:test-run result) (assoc :test-run true)
      (:only result)     (assoc :only (:only result)))))

(defn- record-run-observation!
  "Append the run's result as an `:observe` delta — *these tests ran, in this
  tier, at this content, and this is what happened*.

  This tier is the ONLY place an `^:external` test ever executes, so it is the
  only place their red evidence can come from; before this it appended nothing
  and `:assertions-never-red` was consequently unclearable for one, which is
  filed three times from three directions.

  The closure key comes from [[slopp.ops.engine/closure-hashes]] rather than
  from anything local, so the key WRITTEN here and the key a later reader
  recomputes are one derivation — a second one would agree until the day it
  did not, and a verdict cache is exactly where that costs a false green.

  The shell: [[observation-of]] and `closure-hashes` are the transforms, and
  the parts that can be WRONG are all in there. Returns the result WITHOUT
  the `:test-run` marker: that is for the record, where a repeated run finds
  it (`ops/standing-run`), not for the caller."
  [session scope result]
  (let [st (:store @session)]
    (engine/commit-appended!
     session
     #(store/record-observation % scope (observation-of st result)
                                (engine/closure-hashes st scope))
     []))
  (dissoc result :test-run))

(defn- clear-source-roots!
  "Delete the materialized source roots under `target` so the tree about to be
  written EQUALS the store rather than accumulating. Returns nil when it
  cleared (or had nothing to clear), or a REASON string when it declined.

  **Attested by the head stamp**, never assumed. `build!` takes an arbitrary
  absolute directory, so deleting `src/` on trust would delete a directory
  somebody else owns. `src/META-INF/slopp/head.edn` is written by `build!` and
  by nothing else, so its presence is proof this tree is a materialization.
  No source root at all is the other safe case: a fresh directory.

  **Deletes rather than swapping into place.** A swap would be atomic, and the
  cost of not having it is a crash mid-build leaving a partial tree — which
  `uber` already refuses, loudly, because the head stamp is gone with it. A
  silent wrong jar is the failure worth engineering against; a loud missing
  one is not.

  Only the roots in `store.render/source-roots`, only under `target`, and only
  after `build!`'s existing guards have established that `target` is absolute
  and does not enclose the running process."
  [^java.io.File target]
  (let [roots   (map #(io/file target %) store.render/source-roots)
        present (filter #(.exists ^java.io.File %) roots)
        stamp   (io/file target "src" boot/head-resource-path)]
    (cond
      (empty? present) nil
      (not (.exists stamp))
      (str "left " (count present) " existing source root(s) in place: no "
           (str "src/" boot/head-resource-path) " to attest this tree was"
           " materialized by build!. A namespace deleted from the store may"
           " still be sitting there — build into a fresh directory, or remove"
           " the roots by hand")
      :else
      (letfn [(rm! [^java.io.File f]
                (when (.isDirectory f) (run! rm! (.listFiles f)))
                (.delete f))]
        (run! rm! present)
        nil))))

(defn ^:export build!
  "C1/C6 explicit build: materialize a runnable project under `dir` —
  `src/<ns-path>.clj` per namespace plus a minimal `deps.edn` (F8). Guarded
  (X4: an eval agent once built into the host repo, clobbering its deps.edn):
  absolute paths only, never a directory enclosing the running process, and a
  deps.edn this build didn't generate is never overwritten.

  With `:main` (a qualified entry fn, e.g. 'calc.core/run-cli) also emits the
  native-binary recipe (O4): a generated gen-class launcher at
  src/native/main.clj, a `:native` deps alias, and an executable
  build-native.sh that GraalVM-compiles the project to a self-contained
  binary `:name` (default: the entry ns's first segment). `:main` and `:name`
  FALL BACK to the persisted app manifest — the `app.main` / `app.name`
  capability settings — so a store that declares its entry point builds with
  no arguments; explicit args override."
  [session dir & {:keys [main force] bin-name :name}]
  (let [f        (io/file dir)
        target   (.getCanonicalFile f)
        cwd      (.getCanonicalFile (io/file "."))
        st       (:store @session)
        main     (or main (capabilities/effective st "app.main"))
        bin-name (or bin-name (capabilities/effective st "app.name"))
        ;; A cli app declares NO entry fn: the author writes commands and slopp
        ;; writes the launcher. So "does this build produce an executable" stops
        ;; being "is app.main set" — it was literally `(boolean main)`, which
        ;; would hand a cli app no :native alias and no AOT path for the very
        ;; launcher slopp generated for it.
        cli?     (capabilities/enabled? st "cli")
        cmd-nses (vec (sort (distinct (map :ns (edit.cli/command-rows st)))))
        entry?   (or (boolean main) cli?)
        de       (io/file target "deps.edn")
        provided (client-build-deps st)
        ;; `session/image-deps` adds what the VENDORED framework requires — the
        ;; build path has the identical hole the image did, and for the identical
        ;; reason: the tree gets slopp/http/** and the pom that used to supply
        ;; garden/hiccup/cheshire/http-kit is gone. A built app would fail inside
        ;; slopp.http.css exactly as an image did.
        deps     (merge (engine/image-deps st) (:runtime provided))
client-deps (merge (:client-deps st) (:client provided))
        has-tests? (boolean (or (some store.render/test-ns? (keys (:namespaces st)))
                                (some (fn [nsx]
                                        (some #(re-find #"^\(deftest\b"
                                                        (n/string (:node %)))
                                              (store/forms st nsx)))
                                      (keys (:namespaces st)))))
        incompat (when entry? (seq (filter project.deps/native-incompatible-deps (keys deps))))
        ;; a deps.edn is ours iff it's byte-identical to a generated variant
        ;; (for THIS store's manifest + test layout — else it reads as foreign)
        traced?  (boolean (and has-tests?
                               (get-in st [:namespaces 'slopp.image.testmain])))
;; NAMESPACES, not the register: a declaration on a module that holds
        ;; nothing would otherwise put a `:paths` entry in deps.edn with no
        ;; directory under it. The tree describes what was materialized.
        instr?   (boolean (some #(and (= :instrument (store/role-for st %))
                                      (not (store.render/test-ns? %)))
                                (keys (:namespaces st))))
        ours?    #(contains? #{(build/deps-edn false deps has-tests? traced? client-deps instr?)
                               (build/deps-edn true deps has-tests? traced? client-deps instr?)}
                             (slurp de))
        entry-ns (some-> main namespace symbol)]
    (cond
      (not (.isAbsolute f))
      {:error "build needs an ABSOLUTE directory path"}

      (.startsWith (.toPath cwd) (.toPath target))
      {:error (str "refusing to build into " target
                   " — it contains the running system")}

      ;; BEFORE any main-specific check, because this is a contradiction in the
      ;; CONFIG and stays one whether or not the named fn exists — diagnosing
      ;; the missing form first would answer a question the author is not
      ;; asking. They collide concretely (both want src/native/main.clj and the
      ;; reserved native.main), but the silent version is the reason for the
      ;; refusal: `native?` used to be `(boolean main)`, so a store with both
      ;; got a launcher calling the author's fn directly — no parsing, no
      ;; injected streams, no exit code. Exactly the bare `-m` the capability
      ;; replaces, handed to a store that had opted INTO the capability.
      (and main cli?)
      {:error (str "app.main and cli.enabled both declare an entry, and a build"
                   " has room for one. cli.enabled means slopp GENERATES the"
                   " entry from your :cli/command forms — argument parsing,"
                   " injected streams and exit codes come with it. app.main"
                   " means your own fn is handed argv and owns all of that."
                   " Keep the one you meant: unset app.main to use the"
                   " commands, or set cli.enabled false to keep " main ".")}

      ;; A shell over nothing. It would build, run, and be able to do exactly
      ;; nothing — and `commands-in` cannot distinguish "no commands here" from
      ;; "that namespace never loaded", so the binary would not say so either.
      (and cli? (empty? cmd-nses))
      {:error (str "cli.enabled, but no form in this store declares a"
                   " :cli/command — the generated entry would have no commands"
                   " to run. Add one: (defn ^{:cli/command \"greet\""
                   " :cli/doc \"...\" :cli/args [:catn [:who :string]]} greet"
                   " [ctx args] …), or set cli.enabled false.")}

      (and main (nil? entry-ns))
      {:error (str ":main must be a qualified entry fn (ns/name), got " main)}

      (and main (nil? (store/form-named st entry-ns (symbol (name main)))))
      (edit/missing-form-error st entry-ns (symbol (name main)))

      (and entry? (get-in st [:namespaces 'native.main]))
      {:error "a store namespace named native.main collides with the generated launcher"}

      ;; the same collision on the browser side: the name and the file path
      ;; are the generator's, so a store namespace called this one renders over
      ;; the entry slopp writes.
      ;;
      ;; This is NOT how a store says it owns its browser entry — that is
      ;; `own-mount-nses` below, which reads the mounting CALL. Asking the
      ;; name was once the whole guard, and it caught only the generator
      ;; colliding with itself: a hand-written entry is named whatever its
      ;; author named it, so every other name got a silent second mount.
      (and (capabilities/enabled? st "webapp")
           (seq (rules.webapp/page-rows st))
           (get-in st [:namespaces 'native.client]))
      {:error (str "a store namespace named native.client collides with the"
                   " generated browser entry — the name and the path"
                   " cljs-src/native/client.cljs are slopp's. Rename yours."
                   " To own the browser entry instead, keep the form that"
                   " calls dom/mount! and slopp generates nothing.")}

      

      (and entry? (.exists de) (not (ours?)))
      {:error (str target "/deps.edn exists and wasn't generated by build! — "
                   "the native recipe must own it; build into a fresh directory")}

      (and incompat (not force))
      {:error (str "refusing a native build: dependencies known to break "
                   "GraalVM native-image: " (str/join ", " incompat)
                   " (pass :force true to build anyway)")
       :native-incompatible (vec incompat)}

      :else
      (let [unpruned (clear-source-roots! target)
              ;; exactly one page row, because the marker's own gate allows
              ;; only one — and a store declaring none would get an entry
              ;; mounting nothing
              page-rows (when (capabilities/enabled? st "webapp")
                          (rules.webapp/page-rows st))

              ;; …and nothing generated when the store MOUNTS THE APP ITSELF.
              ;; Two mounts over one element is a page that renders twice, and
              ;; it is invisible downstream: a bundle with two mounts compiles
              ;; exactly as clean as one with one, so `compile_client` reports
              ;; success either way. Reported by a store that compiled the
              ;; artifact and counted the bootstraps.
              ;;
              ;; This is also the store SAYING it owns its browser entry —
              ;; which some must, for reasons the generated one cannot meet
              ;; (a contract check that reaches `:external`, a `:boot` that
              ;; reads the DOM). Owning a mount IS the declaration, so there
              ;; is no second setting to keep in agreement with it.
              own-mounts (when (seq page-rows) (rules.webapp/own-mount-nses st))

              ;; the table the PAGES declare, which the entry fn no longer carries.
              ;; Their namespaces join the require list for the same reason:
              ;; the entry used to name every page, so the closure reached
              ;; them, and now nothing in it does
              route-rows (when (seq page-rows)
                           (vec (for [{:keys [path page]} (rules.webapp/page-routes st)]
                                  [path page])))

              client-entry
              (when (and (= 1 (count page-rows)) (empty? own-mounts))
                (let [{:keys [page closure]} (first page-rows)
                      nses (vec (sort (into (set closure)
                                            (map #(symbol (namespace (second %))))
                                            route-rows)))
                      f (io/file target "cljs-src" "native" "client.cljs")]
                  (io/make-parents f)
                  (spit f (build/webapp-launcher-source page nses route-rows))
                  "cljs-src/native/client.cljs"))

              ;; said out loud, because the other failure is the quiet one: a
              ;; store expecting a generated entry and getting none should not
              ;; have to diff the tree to find out
              client-entry-skipped
              (when (and (= 1 (count page-rows)) (seq own-mounts))
                (str "no browser entry generated: "
                     (str/join ", " own-mounts)
                     " already mounts this app, and a second mount over the"
                     " same element renders it twice. Delete the hand-written"
                     " mount to take the generated entry."
                     " **The ROUTE TABLE goes with it.** The generated entry is"
                     " what supplies :webapp/routes from your pages'"
                     " :webapp/path markers, so your own mount must now pass a"
                     " table to slopp.webapp.dom/mount! — nothing else will."
                     " A headless drive will NOT tell you: slopp.cljnx/driver-for"
                     " scans the loaded vars and fills the table itself, so the"
                     " suite stays green while the browser throws at startup."))]
          (doseq [ns-sym (keys (:namespaces st))]
    (let [file (io/file target (store.render/source-path ns-sym
                                                   (store/platform-for st ns-sym)
                                                   (store/role-for st ns-sym)))]
      (io/make-parents file)
      (spit file (store.render/render-ns st ns-sym))))
  (doseq [[path entry] (:files st)]
    (let [file (io/file target (str path))]
      (io/make-parents file)
      (if (map? entry)
        ;; a binary asset: real bytes from the content-addressed cache
        ;; a binary asset: real bytes, from the in-memory cache when this
        ;; session wrote them, else straight from the content-addressed
        ;; table — :blobs is a PARTIAL cache and is not populated at open
        (when-let [^bytes bs (or (get (:blobs st) (:sha entry))
                                 (some-> (:db @session) (db/get-blob (:sha entry))))]
          (io/copy bs file))
        (spit file entry))))
  ;; every config entry EXCEPT the locally-declared ones. `capabilities`,
  ;; `rules` and `gates` configure the PRODUCT and a built app reads them;
  ;; `dev` says what to RUN while somebody works on this project, so shipping
  ;; it would put one developer's port and entry point inside the artifact.
  (doseq [[path entry] (remove (comp store/local-config-paths key)
                               (cond-> (:config st)
                                 (read.modules/modules-config-entry st)
                                 (assoc "modules" (read.modules/modules-config-entry st))))]
    (let [file (io/file target (str path))]
      (io/make-parents file)
      (spit file (store/render-config entry))))
          ;; PROVENANCE: what this materialization was built FROM. A derived
          ;; artifact that cannot state its origin eventually gets trusted when
          ;; it should not — `uber` jarred a two-day-old materialization and
          ;; printed success. The head delta id makes the staleness check exact
          ;; instead of an mtime guess.
          ;;
          ;; UNDER src/, so the stamp goes where the code goes. It sat beside
          ;; the tree at first, which meant the build could read it and nothing
          ;; downstream ever could: six separate incidents came down to "which
          ;; slopp am I running", answered by unzipping a jar and grepping for a
          ;; symbol — a check a human can run and a tool cannot run on itself.
          ;; `boot/jar-head` reads this back.
          (let [hf (io/file target "src" boot/head-resource-path)]
            (io/make-parents hf)
            (spit hf (pr-str {:head (:head st)})))
          ;; friction 2: the purity tiers, as a classpath RESOURCE under the
          ;; source root, so they ride into a published jar with the code they
          ;; describe. A tier is a declaration in this store, not anything in
          ;; the code, so without this a consumer sees every namespace of a
          ;; published library as undeclared — hence :external — and its own
          ;; correct functions get !-flagged for calling them.
          (when-let [tiers (read.modules/tiers-resource st)]
            (let [tf (io/file target "src" read.modules/tiers-resource-path)]
              (io/make-parents tf)
              (spit tf (pr-str tiers))))
          ;; THE FRAMEWORK, copied in rather than named (D-framework-injection
          ;; part 2). slopp-web is never published to a remote, so a coord in
          ;; the deps.edn below would name something only the machine that ran
          ;; slim-install can resolve — a build that ships broken to anywhere
          ;; else. Vendored, the tree is self-contained and needs no repository
          ;; at all. Same call the oracle image makes, so the app is built
          ;; against the framework it was developed against.
          (engine/vendor-framework! st target)
          ;; THE BROWSER ENTRY, on the same principle as the cli launcher and
          ;; for a sharper reason. A cli app's alternative is a `-main` written
          ;; once; a browser app's is TWO wirings — the `:cljs` shell that
          ;; mounts and whatever the headless driver is handed — with a
          ;; hand-written adapter between them that nothing compares.
          ;;
          ;; `build/webapp-launcher-source` was written, tested, and cited by
          ;; `slopp.webapp.dom/mount!`'s own `^:unused-ok` as having a caller no
          ;; reference graph could see. It had no caller at all: its cli twin is
          ;; emitted below and this one never was, so every browser app still
          ;; hand-wrote the two forms slopp generates for it — which is two of
          ;; the three `:cljs` forms the only real consumer had left against
          ;; this capability's own metric.
          ;;
          ;; Under `cljs-src/`, where a `:cljs` namespace goes, so the
          ;; ClojureScript compiler finds it and the JVM classpath does not.
          
          
          (when (or entry? (not (.exists de)))
            (when has-tests? (.mkdirs (io/file target "test")))
            (spit de (build/deps-edn entry? deps has-tests? traced? client-deps instr?)))
          (cond-> (let [missing (materialize-artifacts! session st target)]
                        (cond-> {:built (str target)}
                          (seq missing) (assoc :missing-artifacts missing)
                          ;; the tree is a SUPERSET of the store when this is
                          ;; set — say so, because the whole failure mode is
                          ;; that nothing does
                          unpruned (assoc :unpruned unpruned)
                          client-entry (assoc :client-entry client-entry)
                          client-entry-skipped
                          (assoc :client-entry-skipped client-entry-skipped)))
            entry?
            (assoc :native
                   ;; the binary's name is ALSO the program's name in usage, and
                   ;; deliberately ONE string rather than two settings: if they
                   ;; could differ, generated help would teach a command the
                   ;; shell does not have.
                   (let [bin   (or bin-name
                                   (first (str/split (str (if cli? (first cmd-nses) entry-ns))
                                                     #"\.")))
                         launcher (io/file target "src" "native" "main.clj")
                         script   (io/file target "build-native.sh")]
                     (io/make-parents launcher)
                     (spit launcher
                           (if cli?
                             ;; no arity to inspect: the entry is slopp's own
                             ;; `run`, of known shape. That is what the
                             ;; capability buys — an author's fn has to be
                             ;; MEASURED before argv can be handed to it, and
                             ;; whatever that measurement decides, everything
                             ;; downstream of it is still the author's problem.
                             (build/cli-launcher-source bin cmd-nses)
                             (let [an   (analyze/analyze (store.render/render-ns st entry-ns))
                                   vdef (first (filter #(and (= entry-ns (:ns %))
                                                             (= (symbol (name main)) (:name %)))
                                                       (:var-definitions an)))]
                               (build/launcher-source main (build/arg-style vdef)))))
                     (spit script (build/native-script bin (keys (:files st))))
                     (.setExecutable script true false)
                     (let [warns (vec (for [[lib coord] deps
                                            :when (= :none (:verdict
                                                            (project.deps/dep-native-verdict session lib coord)))]
                                        lib))]
                       (cond-> {:binary bin
                                :launcher "src/native/main.clj"
                                :script   "build-native.sh"}
                         ;; M6: deps with no reachability metadata may need
                         ;; a tracing-agent run before native-image succeeds
                         (seq warns)
                         (assoc :warnings
                                (str "no GraalVM reachability metadata for: "
                                     (str/join ", " warns)
                                     " — the native build may need a tracing-agent run")
                                :metadata-missing warns))))))))))

(defn delete-dir! [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(defn ^:export external-test-run!
  "Run the STORE's test suite in a FRESH EXTERNAL JVM: materialize the store
  (build!) into a throwaway dir and shell `clojure -M<alias>` there — the
  out-of-process counterpart to in-image `traced-run`, and the ONLY tier that
  executes ^:external tests (they spawn their own images/subprocesses, so
  running them in-image would recurse). Needs no repo files — the store is
  the source, which is what lets the working dir go fileless. `:ns` narrows
  to one test namespace, `:only` to specific ns-qualified test vars (Q2);
  `:affected true` narrows to the PROVABLE slice (test namespaces whose
  require-closure reaches a form changed since the last milestone);
  `:parallel` SHARDS a full/affected run across concurrent JVMs — one
  build, round-robin namespace shards, merged into one summary. Defaults
  to AUTO (auto-parallel: scales with test-ns count + cores, serial below
  ~8 nses where boot overhead beats the gain); an explicit N overrides
  (1 forces serial). A single :ns/:only run never shards. Returns {:external
  true :status :ran :assertions :failures :errors :exit :ms} plus :failing +
  :all-failing {file [tests]} + :themes (clustered causes) when red.

  Repeated with nothing landed since — the same scope and selection — it
  answers from the `:observe` it recorded (`ops/standing-run`): `:standing
  true`, no build, no JVM. This tier is where the wall clock goes, and a
  repeat inside one ask was measured to be a quarter of it.

  `:ms` is the wall cost, on EVERY exit including the early ones. This tier
  is where the time goes — measured, ~187s of a ~190s full_check, almost all
  of it the fresh-JVM boots the isolation requires — and it used to report
  nothing about that, so `done` and `full_check` could not be split into
  their phases and the cost had to be inferred from delta gaps.

  A sharded run also carries `:cost` ([[testrun/shard-cost]]): the build, the
  per-shard wall times, and the FLOOR — the fastest shard, which ran the least
  work and still paid a whole boot, so no narrowed run goes below it. That
  turns whether `{affected true}` would help into a reading instead of a
  guess. It is here because the guess was made and published: the skill said
  `affected` was the gear to reach for by default, and the measurement said
  full = 1297 tests in ~223s against affected = 839 in ~229s. The number was
  always available and never decomposed.

  Every runner is BOUNDED (testrun/run-cmd!) — a hung ^:external test used
  to wedge done! and the milestone gate forever. A green summary is only
  trusted when the JVM also exited zero: a runner that printed green then
  died (System/exit in teardown, OOM in a shutdown hook) is :error, not
  :green. The throwaway build dir is deleted when the run ends, whatever
  the outcome — it used to leak a full materialized project per run.

  Also ABSORBS the run's form trace (#121) when the build carried the trace
  runner: this is the only tier that ever executes an ^:external test, so it
  is the only place their test→form evidence can come from. Silent — the
  trace lands in the session's test-map (and persists), surfacing later as
  honest `:warranty` and affected-test narrowing, not as output here."
  [session & {:keys [alias ns only affected parallel nses]}]
  (let [t0    (System/currentTimeMillis)
        stamp (fn [r] (cond-> r (map? r) (assoc :ms (- (System/currentTimeMillis) t0))))
        aff   (when affected (ops/affected-test-nses session))]
    (if (and aff (empty? (:selected aff)))
      (stamp {:external true :ran 0 :status :green :affected aff
              :note (str "no test namespace can reach the changes since the last"
                         " milestone — nothing to verify (run without affected for"
                         " the full gate)")})
      ;; the full/affected set is shardable (a single :ns or :only run is not);
      ;; :parallel defaults to AUTO — scale the shard count to the work + cores
      (let [full-set (cond
                       (seq nses)
                       (vec (sort (map symbol nses)))

                       (and (nil? ns) (empty? only))
                       (or (:selected aff)
                           (vec (sort (filter #(engine/test-ns? (:store @session) %)
                                              (keys (:namespaces (:store @session))))))))
            ;; WHAT this run covers — the observation's scope, computed once
            ;; here so the record and the standing check cannot disagree
            scope (vec (or (seq full-set)
                           (when ns [(symbol (str ns))])
                           (seq (vec (sort (distinct
                                            (keep #(some-> (symbol (str %))
                                                           namespace symbol)
                                                  only)))))
                           []))
            selection (when (seq only) (vec (map #(symbol (str %)) only)))
            standing  (ops/standing-run (:store @session) :observe scope selection)]
        (if standing
          (stamp (assoc standing :external true))
          (let [par (cond (some? parallel) parallel
                          (nil? full-set)  1
                          :else (testrun/auto-parallel (count full-set)
                                                       (.availableProcessors (Runtime/getRuntime))))
                shard-nses (when (and (> par 1) (seq full-set)) full-set)
                ;; A NARROWED run shards too, along namespace lines. Without this
                ;; :only forced par=1 and one serial JVM, so a 130-test impacted
                ;; set cost more than the sharded full suite and `done` deferred
                ;; it instead — measured, 37.5% of recent dones, six of fifteen
                ;; deferrals in the 52–136 range that the trace map had picked out
                ;; correctly. Small sets stay serial: sharding four tests buys
                ;; nothing and costs three extra JVM boots.
                only-par (when (and (seq only) (nil? ns) (> (count only) 8))
                           (testrun/auto-parallel
                            (count (distinct (keep #(namespace (symbol (str %))) only)))
                            (.availableProcessors (Runtime/getRuntime))))
                only-groups (when (and only-par (> only-par 1))
                              (testrun/only-shards (:store @session) only only-par))
                ;; narrowed runs need the filter-free alias: the :test alias bakes
                ;; -r \".*\" (inline tests, Q13) which UNIONS with -n and defeats it
                alias (or alias
                          (if (or ns aff (seq only) (seq nses) shard-nses)
                            ":test-run" ":test"))
                dir (str (java.nio.file.Files/createTempDirectory
                          "slopp-external"
                          (make-array java.nio.file.attribute.FileAttribute 0)))]
            (try
              (let [tb       (System/currentTimeMillis)
                    b        (build! session dir)
                    ;; materializing the store is a FIXED cost this tier pays before
                    ;; any test runs, so it belongs in the breakdown rather than
                    ;; inside the number narrowing is judged against
                    build-ms (- (System/currentTimeMillis) tb)]
                (if (:error b)
                  (stamp b)
                  (let [result
                        (if (or (seq shard-nses) (seq only-groups))
                          (let [;; balanced by IMAGE BOOTS, not by index: the shards run concurrently,
                                ;; so this tier costs its SLOWEST shard. Round-robin split
                                ;; slopp's own 402 boots [139 100 90 73] — one shard still
                                ;; had 66 to go after the fastest had finished.
                                ;; ONE shard shape for both cases: the namespaces to discover, and the
                                ;; vars to run within them (nil = the whole namespace).
                                shards (if (seq only-groups)
                                         (mapv (fn [g]
                                                 {:nses (distinct
                                                         (map #(symbol (namespace (symbol (str %)))) g))
                                                  :only g})
                                               only-groups)
                                         (mapv (fn [g] {:nses g})
                                               (testrun/balance-shards (:store @session)
                                                                       shard-nses par)))
                                ;; each shard carries its OWN wall time, because the
                                ;; spread between the fastest and the slowest is what
                                ;; says whether narrowing this tier could help — see
                                ;; [[testrun/shard-cost]]
                                timed  (fn [grp]
                                         (let [s (System/currentTimeMillis)
                                               o (testrun/run-shard! alias dir
                                                                     (:nses grp) (:only grp))]
                                           (assoc o :ms (- (System/currentTimeMillis) s))))
                                runs   (mapv (fn [grp] (future (timed grp))) shards)
                                outs0  (mapv deref runs)
                                ;; a shard with NO parseable summary is a JVM-level death
                                ;; (fork pressure, OOM) — test failures PARSE. Retry those
                                ;; shards once, SERIALLY, off the concurrent storm.
                                dead?  (fn [o] (nil? (testrun/parse-test-summary
                                                      (str (:out o) "\n" (:err o)))))
                                outs   (mapv (fn [grp o] (if (dead? o) (timed grp) o))
                                             shards outs0)
                                retries (count (filter dead? outs0))
                                out    (str/join "\n" (map #(str (:out %) "\n" (:err %)) outs))
                                sums   (mapv #(testrun/parse-test-summary (str (:out %) "\n" (:err %))) outs)]
                            (if (some nil? sums)
                              (cond-> {:external true :exit (apply max (map :exit outs))
                                       :status :error
                                       :shards (count shards)
                                       :output (->> (str/split-lines out)
                                                    (remove str/blank?)
                                                    (take-last 12) (str/join "\n")
                                                    testrun/anchor-output)}
                                (pos? retries) (assoc :shard-retries retries))
                              (let [merged {:ran        (reduce + (map :ran sums))
                                            :assertions (reduce + (map :assertions sums))
                                            :failures   (reduce + (map :failures sums))
                                            :errors     (reduce + (map :errors sums))}
                                    exit   (apply max (map :exit outs))
                                    red?   (pos? (+ (:failures merged) (:errors merged)))]
                                (cond-> (merge {:external true
                                                :exit exit
                                                :shards (count shards)
                                                :status (cond red?        :red
                                                              (pos? exit) :error
                                                              :else       :green)}
                                               merged
                                               (when aff {:affected aff})
                                               (when (pos? retries) {:shard-retries retries})
                                               (when-let [c (testrun/shard-cost
                                                             build-ms (keep :ms outs))]
                                                 {:cost c}))
                                  (and (not red?) (pos? exit))
                                  (assoc :note (str "summaries parsed green but a runner"
                                                    " JVM exited nonzero — not trusting"
                                                    " the green"))
                                  red? (assoc :failing (testrun/parse-test-failures out)
                                              :all-failing (testrun/failing-test-rollup out))
                                  (and red? (seq (testrun/failure-themes out)))
                                  (assoc :themes (testrun/failure-themes out))))))
                          (let [args (cond-> [repl/clojure-bin (str "-M" alias)]
                                       ns         (conj "-n" (str ns))
                                       (seq nses) (into (mapcat #(vector "-n" (str %)) nses))
                                       aff        (into (mapcat #(vector "-n" (str %))
                                                                (:selected aff)))
                                       ;; -n rides along with -v: cognitect's var
                                       ;; filter only resolves vars in DISCOVERED
                                       ;; namespaces, and the default discovery
                                       ;; regex is -test$ — a named test living
                                       ;; anywhere else was unresolvable
                                       (seq only) (into (mapcat #(vector "-n" %)
                                                                (distinct
                                                                 (keep #(namespace (symbol (str %)))
                                                                       only))))
                                       (seq only) (into (mapcat #(vector "-v" (str %)) only)))
                                r    (testrun/run-cmd! args dir)
                                out  (str (:out r) "\n" (:err r))
                                s    (testrun/parse-test-summary out)]
                            (merge {:external true :exit (:exit r)}
                                   (when aff {:affected aff})
                                   (cond
                                     (nil? s)           {:status :error
                                                         :output (->> (str/split-lines out)
                                                                      (remove str/blank?)
                                                                      (take-last 8) (str/join "\n")
                                                                      testrun/anchor-output)}
                                     (= :red (:status s)) (cond-> (assoc s
                                                                         :failing (testrun/parse-test-failures out)
                                                                         :all-failing (testrun/failing-test-rollup out))
                                                            (seq (testrun/failure-themes out))
                                                            (assoc :themes (testrun/failure-themes out)))
                                     (pos? (:exit r))
                                     (assoc s :status :error
                                            :note (str "summary parsed green but the JVM"
                                                       " exited nonzero — not trusting"
                                                       " the green"))
                                     :else s))))]
                    ;; #121: ONE absorb point for BOTH branches — the external tier is
                    ;; the only place an ^:external test ever runs, so a trace missed
                    ;; here is missed forever. nil when the build carried no runner, so
                    ;; untraced stores behave exactly as before.
                    (engine/absorb-trace! session (testrun/read-traces dir))
                    ;; and the run itself is EVIDENCE — the same argument as the
                    ;; trace one line up: this is the only tier that ever runs an
                    ;; ^:external test, so a red missed here is missed forever, and
                    ;; `:assertions-never-red` had nothing to read for one
                    (stamp
                     (record-run-observation!
                      session
                      scope
                      ;; The shards' per-namespace wall time, when the build carried
                      ;; a runner that measured it. Read ONCE, HERE, because `dir`
                      ;; is deleted in the finally below and this is the last moment
                      ;; the measurement exists — and folded into the RESULT rather
                      ;; than passed beside it so the journal and the caller cannot
                      ;; disagree about what the run cost.
                      (let [timings (testrun/read-timings dir)
                            r (cond-> result
                                timings   (assoc :ns-ms timings)
                                ;; what this run WAS, so a repeat can find it
                                true      (assoc :test-run true)
                                selection (assoc :only selection))]
                        ;; and the COST as a `test-run` measurement beside the
                        ;; journal: the observation above carries the verdict, this
                        ;; row carries what it took — tier, wall, and the per-
                        ;; namespace time the shards measured — so variance-vs-
                        ;; drift is a query over runs rather than a re-run, and a
                        ;; selection model has something to train on.
                        (when-let [c (:db @session)]
                          (db/record-measurement!
                           c "test-run" nil
                           {:tier     :external
                            :status   (:status r)
                            :ran      (:ran r)
                            :failures (:failures r)
                            :errors   (:errors r)
                            :ms       (- (System/currentTimeMillis) t0)
                            :shards   (count (get-in r [:cost :shard-ms]))
                            :ns-ms    (:ns-ms r)}))
                        r))))))
              (finally
                ;; a full materialized project per run; nothing else ever deletes it
                (delete-dir! (io/file dir))))))))))

(defn ^:export done!
  "The DONE-POINT: call when you believe your changes are complete. Marks
  the episode boundary and runs the automatic done-processing — normalize
  every form changed this episode (conservative behavior-preserving
  rewrites), clean up safe (declare)s, kondo-lint every touched namespace,
  and RUN THE AFFECTED TESTS for everything the episode touched (no
  test_run needed first — mid-episode runs are for spot-checks). Unused
  PUBLIC surface in touched namespaces GATES here (error-grade): delete it
  or mark the name ^:unused-ok; a stale marker (the var is called now)
  fails symmetrically. Findings ride the boundary delta so the next
  session's brief surfaces anything left red.

  Findings carry TWO verdicts. `:test-status` grades the STORE and is what
  commit_point and session_brief read. `:episode-status` grades this
  episode's own work and is what the LAND turns on, so a store left red by
  another agent no longer holds this thread — `:red-attribution` names
  which failing tests are `:mine`, `:foreign`, `:untraced` or `:unseen`,
  and only `:foreign` counts as innocence.

  Returns {:done id :normalized n :rewrites [{:form :applied}]
  :lint [...] :test s :findings {...}}."
  [session & {:keys [label agent external?] :or {external? true}}]
  (let [t0       (System/currentTimeMillis)
        st       (:store @session)
        ;; nothing written since the last done → no unit of work to bound, so
        ;; no boundary is recorded and its verdict still stands. See
        ;; [[unchanged-since-done]]: three callers each reasonably ask for a
        ;; done and none can see that the others just did. Everything below is
        ;; already a no-op in this case (`changed` is empty, so normalize,
        ;; declare/require hygiene, the suite and the external slice all skip),
        ;; which is why the guard only has to stop the RECORDING.
        standing (unchanged-since-done st)
        changed  (->> (history/episode-span st agent)
                      (filter #(and (contains? history/content-ops (:op %))
                                    (= agent (:agent %))))
                      (mapcat history/delta-fids)
                      distinct
                      (filter #(store/ns-of-form-id st %)))
        rewrites (done/normalize-rewrites changed st)
        _        (done/apply-normalization! rewrites st label agent  session)
        ;; automatic declare hygiene: the pipeline OWNS declares (auto-inserted
        ;; for a genuine cycle); once the cycle breaks the declare is stale —
        ;; remove it here. SILENT: the agent never manages declares, so this
        ;; runs for effect and is not reported.
        _
        (doseq [ns* (distinct (keep #(store/ns-of-form-id (:store @session) %)
                                    changed))]
          (ops/fix-declares! session ns*
                         :prompt (or label "done declare hygiene")
                         :agent agent))
        ;; require hygiene, REPORTED (unlike declares, which the agent never
        ;; sees): try dropping each unused require — a genuinely dead one goes,
        ;; a load-bearing one is restored marked ^:side-effect. The agent never
        ;; manages unused requires; done does.
        pruned-reqs
        (into (sorted-map)
              (for [ns* (distinct (keep #(store/ns-of-form-id (:store @session) %)
                                        changed))
                    :let [pr (ops/prune-requires! session ns*
                                                  :prompt (or label "done require hygiene")
                                                  :agent agent)]
                    :when (or (seq (:pruned pr)) (seq (:kept pr)))]
                [ns* pr]))
        ;; kondo lint over every namespace touched since the last done-point —
        ;; carried mid-episode errors (stale callers) get re-checked HARD here
        lint (done/anchored-lint session changed)
        ;; the unused-public GATE: unmarked dead surface — and stale
        ;; ^:unused-ok markers — join as ERROR-grade lint (never demoted)
        unused-rep (let [st* (:store @session)]
                     ;; episode-scoped, like the lint scan: a form elsewhere
                     ;; can become dead because THIS episode deleted its last
                     ;; caller, so the store-wide sweep is real — it is just
                     ;; `full_check`'s job, not every done point's.
                     (read.modules/unused-report
                      st* (distinct (keep #(store/ns-of-form-id st* %) changed))))
        lint (done/with-unused-gate lint unused-rep)
        ;; NEW warnings (on forms this episode touched) report in full;
        ;; CARRIED ones (pre-existing, untouched forms) compress to a count —
        ;; re-listing them at every done buries real findings. Errors and
        ;; unattributed rows never demote.
        touched-q (into #{}
                        (keep (fn [fid]
                                (let [st* (:store @session)]
                                  (when-let [e (store/form-by-id st* fid)]
                                    (symbol (str (store/ns-of-form-id st* fid))
                                            (str (or (:name e) (:id e))))))))
                        changed)
        loud?     (fn [f] (or (= :error (:level f))
                              (nil? (:form f))
                              (contains? touched-q (:form f))))
        lint-new  (vec (filter loud? lint))
        carried   (vec (remove loud? lint))
        ;; THE done-point verification: the episode's whole working set —
        ;; independent of whether normalize rewrote anything
        summary
        (when (seq changed)
          (let [st*      (:store @session)
                qsyms    (into #{}
                               (keep (fn [fid]
                                       (when-let [e (store/form-by-id st* fid)]
                                         (symbol (str (store/ns-of-form-id st* fid))
                                                 (str (or (:name e) (:id e)))))))
                               changed)
                ;; the ENTIRE in-image suite, not the impacted slice: "done
                ;; means done". Impacted-only answered the weaker question
                ;; "does what I touched still work" — and impacted SELECTION
                ;; was itself a source of misses (one untraced form used to
                ;; collapse the whole narrowing, on 54.4% of real episodes).
                ;; Running everything retires that machinery here.
                ;;
                ;; The full ISOLATED tier is still skipped (it spawns JVMs)
                ;; and the findings SAY so, so running it stays a visible
                ;; choice rather than a silent omission.
                ;; minus what the ORACLE could not load: the tracer walks ns-interns,
                ;; which throws on a namespace the image cannot hold, and "done
                ;; means done" must not mean dying on a namespace the boot
                ;; already reported. Excluded AND reported — the finding rides
                ;; below as :unloadable-namespaces, the :external-pending
                ;; pattern, never a silent skip.
                unloadable (mapv :ns (:image-load-failures @session))
                main-ns  (vec (sort (remove (set unloadable)
                                            (keys (:namespaces st*)))))
                ;; nil affected => every test in main-ns; :edited still powers
                ;; the red :implicated correlation
                s        (engine/run-verification! session main-ns nil
                                            :edited qsyms
                                            :include-integration? true
                                            :boundary? true)]  ; M5
            (engine/commit-appended! session
                              #(store/record-verification % main-ns s) [])
            s))
        ;; the tier is an implementation detail: ^:external tests the episode's
        ;; changes reach run in the EXTERNAL tier here — capped, and a deferral
        ;; is REPORTED (external-pending), never silent.
        ;;
        ;; #127: selected from THE TRACE, like the in-image half above, instead
        ;; of re-derived from the require-closure. That closure selects a median
        ;; 43 of 46 external test nses (measured over every source ns
        ;; 2026-07-17) — it never narrowed, it just always blew the cap, so 84.6%
        ;; of changes deferred and the tier effectively never ran here. The
        ;; evidence was already computed a few lines up and thrown away.
        iso (when (and external? (seq changed))
              (let [st*      (:store @session)
                    iso-only (engine/impacted-external session st* changed)]
                ;; #132: impacted-external is never silent — an untraced form expands
                ;; to its own namespace's reach — so the old closure fallback is
                ;; gone with the collapse that needed it. Run exactly the named
                ;; tests. A :only run is one serial JVM (it never shards), so the
                ;; cap is on TESTS: p50 is 12 covering tests and a cap of 40 fits
                ;; ~71% of forms, while the tail (p90 = 218) is the core-form
                ;; case that honestly wants the whole suite anyway.
                ;; The cap was 40 because a narrowed run could not shard — :only forced
                ;; one serial JVM, so a large impacted set cost MORE than the
                ;; sharded full suite and deferring was the least-bad option.
                ;; `only-shards` removed that, so the number is re-derived from
                ;; what deferrals actually looked like: measured over 40 recent
                ;; dones, 15 deferred, six of them between 52 and 136 tests —
                ;; sets the trace map had picked out correctly and nothing ran.
                ;; 150 converts all six. Past that the impacted set approaches
                ;; the whole suite (the other nine were 339–394 of 409), where
                ;; narrowing saves nothing and full_check is the honest answer.
                (when (seq iso-only)
                  (if (<= (count iso-only) external-slice-cap)
                    (external-test-run! session :only iso-only)
                    {:pending {:count (count iso-only)
                               :tests (vec (take 5 iso-only))
                               :note  (str "NO ^:external test ran this time — these "
                                           (count iso-only) " impacted ones were deferred"
                                           " (most of the external suite; narrowing saves"
                                           " nothing), so the green above is the in-image"
                                           " suite only. full_check is the external evidence"
                                           " for a change this broad.")}}))))
        findings (let [lint-errors (count (filter #(= :error (:level %)) lint))
      lint-warns  (vec (for [f lint :when (= :warning (:level f))]
                         (select-keys f [:form :type :message])))
      failures    (+ (:fail summary 0) (:error summary 0)
                     (:failures iso 0) (:errors iso 0))
      iso-red?    (contains? #{:red :error} (:status iso))
      st*         (:store @session)
      ;; the done-time advisory REGISTRY (D9 rule-registry, done grain): schema
      ;; drift (status-affecting), key typos + contract breakage (advisory). A
      ;; new done finding registers in slopp.rules/done-advisories — ONE
      ;; entry — not by hand-wiring a binding, a clause, and a status term here.
      advisories  (rules/run-done-advisories! session st* changed)
      advisory-red? (rules/status-affecting-fired? st* advisories)
      ;; WHOSE red is this? `implicate` splits the failing tests three ways
      ;; and only :foreign is evidence of innocence — :untraced and :unseen
      ;; are the two ways of having no evidence at all, and both leave the
      ;; red this episode's problem. Anything red in the external slice is
      ;; this episode's by construction: those tests were SELECTED as the
      ;; ones its changes impact.
      attribution (engine/red-attribution summary)
      foreign-red?
      (boolean (and attribution
                    (seq (:foreign attribution))
                    (not (:mine attribution))
                    (not (:untraced attribution))
                    (not (:unseen attribution))
                    (not iso-red?)
                    (zero? (+ (:failures iso 0) (:errors iso 0)))))
      ;; the STORE's verdict — what commit_point and session_brief read
      store-red?   (or (pos? failures) iso-red? (pos? lint-errors) advisory-red?)
      ;; THIS EPISODE's verdict — what the land reads. It differs from the
      ;; store's exactly when the store is red for reasons that provably
      ;; exercise nothing this episode touched. Lint, dead surface and the
      ;; advisories are episode-scoped already, so they are mine by
      ;; construction and stay on this side.
      episode-red? (or (and (pos? failures) (not foreign-red?))
                       iso-red? (pos? lint-errors) advisory-red?)
      nothing-judged? (and (nil? summary) (nil? iso) (zero? lint-errors))
      missing-doc (vec (sort (distinct
                              (keep (fn [fid]
                                      (when-let [e (store/form-by-id st* fid)]
                                        (:var (edit.modules/missing-doc-warning
                                               st*
                                               (store/ns-of-form-id st* fid)
                                               (:name e)))))
                                    changed))))
      ;; the same nag-where-you-work grain, one level up: a namespace the
      ;; episode touched that never says what it is FOR. Whole-store is
      ;; review_scan's question, not this one's.
      ]
  ;; TWO verdicts, because a done answers two questions that are not the
  ;; same question. :test-status grades the STORE — commit_point and
  ;; session_brief read it, and a red store must not milestone.
  ;; :episode-status grades THIS EPISODE's work, and the land reads it.
  ;; They differ exactly when the store is red for reasons that provably
  ;; exercise nothing this episode touched, which is the case that used to
  ;; freeze every agent's thread behind one agent's red.
  ;;
  ;; Lint errors — which INCLUDE dead public surface, folded in as ERROR
  ;; rows by with-unused-gate — count toward BOTH: they are part of "is this
  ;; codebase good?", and they are episode-scoped, so they are also this
  ;; episode's. They were absent here while commit-point! kept its own
  ;; dead-surface scan; with that removed, omitting them let a store with
  ;; dead surface milestone green.
  ;;
  ;; :none is judged AFTER red, never before it. An error-grade finding that
  ;; fires on a DELTA rather than on code — tier-governance,
  ;; http-dangling-route-refs — can be the only thing that happened in an
  ;; episode, and while :none came first it swallowed exactly those.
  (cond-> {:test-status    (cond store-red?      :red
                                 nothing-judged? :none
                                 :else           :green)
           :episode-status (cond episode-red?    :red
                                 nothing-judged? :none
                                 :else           :green)
           :failures    failures
           :lint-errors lint-errors
           ;; what the oracle could not load and therefore could not judge —
           ;; subtracted from the suite scope above, REPORTED here. A boot
           ;; failure names a store invalidated from OUTSIDE (a framework
           ;; rename, a dep bump, a platform declaration); the fix is one
           ;; edit to the named namespace, which the write path now verifies
           ;; against its POST-edit state.
           :unloadable-namespaces (vec (:image-load-failures @session))
           ;; done runs the WHOLE in-image suite but not the full external
           ;; tier. Say so EVERY time: an unstated omission reads as coverage,
           ;; and that is how a green status comes to mean less than the agent
           ;; thinks it does.
           ;; done is EPISODE-scoped: the whole in-image suite plus impacted
           ;; ^:external tests, but lint and dead-surface cover only what this
           ;; episode touched, and the full external + integration tiers do
           ;; not run. Say so EVERY time: an unstated omission reads as
           ;; coverage, and that is how a green status comes to mean less than
           ;; the agent thinks it does.
           ;; a KEYWORD, not the paragraph. The scope was 600 characters of
           ;; teaching on every done — 2,062 chars average result, measured —
           ;; and read carefully twice in a session. The teaching lives in the
           ;; `done` tool description now (read once); the fact rides here.
           :scope :episode}
    ;; ADVISORY, and named as such: kondo findings slopp's config
    ;; deliberately does not block on, because each is routinely true of a
    ;; form mid-edit. Listed so the agent can judge them, never counted.
    ;; whose red, named: {:mine :foreign :untraced :unseen}. Present
    ;; whenever anything is red, because the split is what makes
    ;; :episode-status auditable rather than something to take on trust.
    attribution       (assoc :red-attribution attribution)
    (seq lint-warns)  (assoc :lint-warnings lint-warns)
    (:pending iso)    (assoc :external-pending (:pending iso))
    (seq missing-doc) (assoc :missing-doc missing-doc)
    
    (seq advisories)  (merge advisories)
    (seq (:unused unused-rep)) (assoc :unused-public (:unused unused-rep))
    (seq (:stale unused-rep))  (assoc :stale-unused-ok (:stale unused-rep))
    ;; friction #10: the host-currency record existed and only ever reached
    ;; session_brief — an orientation surface read once a session — so a
    ;; verdict produced by a process running superseded code said nothing
    ;; about it, and the investigation that followed eliminated four correct
    ;; mechanisms in rt first. Nil unless there is genuinely something to
    ;; doubt, so it never becomes noise the reader learns to skip.
    (host-warning-now session st*) (assoc :host-stale (host-warning-now session st*))
    ;; what the done-point COST, persisted on the boundary delta. done is the
    ;; most frequently called verdict, so its cost dominates by repetition
    ;; rather than by any single call being slow — a product the log could
    ;; not compute while no delta carried a duration.
    true (assoc :ms (- (System/currentTimeMillis) t0))))
        cid (if standing
              (:done standing)
              (let [v (volatile! nil)]
                (engine/commit-appended! session
                                  (fn [base]
                                    (let [[st2 c] (store/record-done base label
                                                                     :agent agent
                                                                     :findings findings)]
                                      (vreset! v c)
                                      st2))
                                  [])
                (swap! session assoc :done @v)
                @v))
;; THE LAND. A branch only ever contains done work, so this is the one
        ;; place work leaves an agent's thread — rebasing onto whatever landed
        ;; while it worked, then advancing the branch under CAS. nil when the
        ;; session is not on a thread or nothing was written to it.
        ;;
        ;; AFTER the boundary delta on purpose: the done itself is part of the
        ;; episode, so it lands with the work it grades rather than being
        ;; stranded on a line nobody will read again.
        ;;
        ;; A done that is red ON THIS EPISODE'S WORK lands nothing and the
        ;; thread survives, holding what is not finished yet. That is the
        ;; whole bargain — the verdict is what decides, so a branch cannot
        ;; come to contain something no verdict ever stood behind.
        ;;
        ;; The bar is the EPISODE's verdict rather than the store's, and the
        ;; difference is only ever a red whose failing tests provably
        ;; exercise nothing this episode touched. Reading the store's verdict
        ;; here froze every agent's thread the moment the trunk went red for
        ;; anyone's reason — including the thread carrying the fix, which is
        ;; a deadlock two agents reached in one night and could not clear
        ;; between them. Innocence has to be PROVEN, never assumed: an
        ;; untraced failing test, or a failure the run counted and did not
        ;; show, keeps the red this episode's and the thread stays put.
        land (when-not (= :red (:episode-status findings))
               ;; entries a replay or a merge left stale are brought current
               ;; and persisted here, before the view lands — the one cadence
               ;; a stale entry can accumulate at
               (ops/refresh-index! session)
               (branch/land-thread! session))
        ;; #14: the verdict above was earned against the THREAD image, which
        ;; held the whole episode. The land rebases and re-mints ids, so
        ;; "green" and "on the branch" are two different facts and nothing
        ;; joined them — measured with two forms, one of which landed and one
        ;; of which did not, after which every request served 200 while the
        ;; milestone read green. A red that lies costs an investigation; a
        ;; GREEN that lies ships.
        ;;
        ;; Read from the BRANCH, never from this session: checking a landing
        ;; against the store that produced it is one reader answering twice,
        ;; which is the mistake being caught. ~0.4s, and only on a done that
        ;; actually landed something.
        gap (when (and (:landed land) (seq touched-q) (:db @session))
              (done/landed-gap
               (into #{} (map (fn [q] [(symbol (namespace q)) (name q)])) touched-q)
               (db/load-elements (:db @session)
                                 (engine/session-branch-line session))))
        ;; the DECLARATION twin. A `module_dep` is not a form — it is a
        ;; `:module-edge` delta folded into the manifest — so the check above
        ;; cannot see one go missing, and one going missing is measured rather
        ;; than hypothetical: three edges declared and landed were gone from
        ;; the trunk hours later while still present in the declaring session's
        ;; store, which left that agent GREEN and another agent's milestone
        ;; blocked by twenty undeclared edges it could not repair.
        ;;
        ;; Gated on the episode having declared any at all, which is rare, so
        ;; the branch read this needs costs nothing on an ordinary done.
        declared (when (and (:landed land) (:db @session))
                   (into [] (comp (filter #(and (#{:module-edge :module-test-edge} (:op %))
                                                (= :add (:action %))
                                                (= agent (:agent %))))
                                  (map (fn [d]
                                         (cond-> {:from (:from d) :to (:to d)}
                                           (= :module-test-edge (:op d))
                                           (assoc :test-only true))))
                                  (distinct))
                         (history/episode-span st agent)))
        edge-gap (when (seq declared)
                   (let [branch (db/load-store (:db @session)
                                               (engine/session-branch-line session))]
                     (done/declared-edge-gap
                      declared
                      (edit.modules/modules-manifest branch)
                      (edit.modules/module-test-manifest branch))))]
    ;; the STANDING verdict, verbatim, when nothing was written — carrying its
    ;; :note, so a caller cannot read an inherited verdict as a fresh one
    (if standing standing (cond-> {:done cid
             :normalized (count rewrites)
             :rewrites   (mapv #(select-keys % [:form :applied]) rewrites)
             :lint       lint-new
             :findings   findings}
      (seq carried)       (assoc :lint-carried
                                 {:count (count carried)
                                  :forms (vec (sort (distinct (keep :form carried))))})
      summary             (assoc :test summary)
      (seq pruned-reqs)   (assoc :pruned-requires pruned-reqs)
      (:status iso)       (assoc :external iso)
      land                (assoc :land land)
      (seq gap)           (assoc :landed-gap
                                 {:forms (mapv (fn [[ns* nm]] (symbol (str ns*) nm)) gap)
                                  :note (str "this done's verdict covered "
                                             (count gap) " form(s) that are NOT on"
                                             " the branch it just landed onto. The"
                                             " green is honest and does not describe"
                                             " what shipped — re-apply them and call"
                                             " done again. Read from the branch, not"
                                             " from this session.")})
      (seq edge-gap)      (assoc :declared-gap
                                 {:edges edge-gap
                                  :note (str "this episode declared " (count edge-gap)
                                             " module edge(s) that are NOT on the branch"
                                             " it just landed onto. Re-declare them —"
                                             " and note that `module_dep` will answer"
                                             " :already-declared from THIS session,"
                                             " which still holds them; thread_drop puts"
                                             " you on the branch where the repair can"
                                             " take. Until then another agent's"
                                             " full_check is red on your edges and"
                                             " cannot milestone.")})))))

(defn ^:export commit-point!
  "Record a MILESTONE (P4-m7): run the full done pipeline (normalize,
  declare hygiene, verify) for `:agent`, then append a `:commit` marker
  pointing at the resulting state with a human `description`.

  THE MILESTONE HAS NO GATES OF ITS OWN. It runs `done!` and gates on that
  verdict — nothing is re-judged here, and nothing whole-store is forced.
  `full_check` (every namespace, every tier) is the agent's call, before a
  commit or any other time; a milestone records what the done point verified. Two enforcement points DRIFT: this
  function used to recompute status from raw test counts and so never saw
  the `:error` done-advisories at all, and it carried its own copies of the
  dead-surface and lint scans. `done` means done, which only holds if done
  is the single bar; a second bar is somewhere to accidentally put a check
  that then does not apply at done.

  GREEN-GATED: a red verification refuses the milestone (the done still
  stands — fix and retry) unless `:force true`, which records `:status :red`
  honestly. Re-requesting a milestone on an UNCHANGED store returns the
  existing marker instead of minting an empty one. With `:target` (a past
  delta id) it is a pure retroactive marker: no done runs, status is
  derived from the log at that spot. No milestone captures a tree at all now;
  the projection folds the journal, so a retroactive marker gets the exact
  state it names rather than a lossy reconstruction of it. `:extra` merges
  op-specific payload into the marker delta
  (P4-m8 uses it for `:git-sha` on imported commits)."
  [session description & {:keys [agent force target extra]}]
  (let [mark! (fn [target status result-extra delta-extra]
                (let [v (volatile! nil)]
                  (engine/commit-appended!
                   session
                   (fn [base]
                     (let [[st2 d] (store/record-commit base description
                                                        :agent agent
                                                        :target target
                                                        :status status
                                                        :extra (if-let [au (author-identity session)]
                                                                 (assoc delta-extra :author au)
                                                                 delta-extra))]
                       (vreset! v d)
                       st2))
                   [])
                  ;; the marker is a statement about the BRANCH, so it has to reach one.
                  ;; done landed the work a moment ago and left this session on a
                  ;; FRESH thread, which is exactly where the marker delta just
                  ;; went — so without this a milestone records itself onto a line
                  ;; nobody will ever read, and the projection folds a branch whose
                  ;; last delta is the one before the milestone.
                  ;;
                  ;; Unconditional, `:force` included. Forcing is an explicit
                  ;; request to record a red state as a milestone, and a milestone
                  ;; naming work the branch does not contain is not honest, it is
                  ;; unreadable.
                  (let [land (branch/land-thread! session)
                        ;; A REFUSED land is the one case the milestone must not
                        ;; smooth over. The delta is recorded by now, but it was
                        ;; recorded onto the same thread the work is stranded on,
                        ;; so nothing reached the branch — and returning
                        ;; `:status :green` for that is the failure observed on
                        ;; `d32474`: the branch did not contain what the
                        ;; milestone named, and everything downstream reads the
                        ;; stamp rather than the branch.
                        ;;
                        ;; The value used to be discarded here, which is the
                        ;; whole mechanism: a `{:landed false :reason …}` was
                        ;; indistinguishable from a landing that worked.
                        refused? (false? (:landed land))
                        ;; #17, and the same shape one artifact over. A
                        ;; milestone is the announcement OTHER PEOPLE act on,
                        ;; and it made a claim about the store while saying
                        ;; nothing about the jar that carries the store to
                        ;; them. Announcement → artifact → process are three
                        ;; states and nothing joined them; twice in one night a
                        ;; consumer caught a green milestone whose jar had
                        ;; never been rebuilt, and caught it by reading the
                        ;; artifact rather than by believing the announcement.
                        ;;
                        ;; Nil unless there is something to doubt — no jar, a
                        ;; foreign one, or one built from this head all report
                        ;; nothing.
                        jar-stale (orient/jar-warning
                                   (ops/jar-currency session (:jar-head (boot/current-boot-info))))]
                    (cond-> (merge {:commit (:id @v) :target target
                                    :status (if refused? :unlanded status)
                                    :description description}
                                   result-extra)
                      land      (assoc :land land)
                      jar-stale (assoc :jar-stale jar-stale)))))]
    (cond
      (str/blank? (str description))
      {:error "a commit point needs a human-facing :description"}

      target
      (if (db/on-line? (:db @session) (engine/session-line session) target)
        (mark! target (history/status-at (:store @(ops/with-history session)) target) {} extra)
        {:error (str "no delta " target " in this branch's history")})

      :else
      (let [;; the newest entry on this session's line, from the journal — the
            ;; value carries its head's ID, not the delta
            last-d (db/head-delta (:db @session) (engine/session-line session))]
        (if (= :commit (:op last-d))
          (merge {:commit (:id last-d) :target (:target last-d)
                  :status (:status last-d)
                  :description (:description last-d)
                  :note "nothing changed since this milestone — returning it"})
          (let [cp     (done! session :label description :agent agent)
                ;; done runs the impacted ^:external slice itself (:external?
                ;; defaults true), so the milestone's done is a REAL done — not
                ;; one weakened to skip the tier the in-image suite already
                ;; skips. The milestone still runs no WHOLE-store check (that is
                ;; `full_check`, the agent's call, per D-full-check): a red
                ;; ^:external test the episode never TOUCHED does not stop it,
                ;; but one this episode touched does — exactly what a standalone
                ;; done catches. :force skips straight to an honest red.
                st     (:store @session)
                head   (:head st)
                ;; done's OWN verdict — it already accounts for failures, the
                ;; :error advisories, store-wide lint and store-wide dead
                ;; surface. Believe it rather than re-deriving a weaker answer.
                ;; :none means this done judged NOTHING (no writes since the last
                ;; one) — so the previous real verdict stands. Otherwise a red
                ;; done is laundered by committing without changing anything.
                ;; the findings this milestone is judged on: THIS done's when it
                ;; judged something, otherwise the last done that did.
                verdict (if (#{:red :green} (get-in cp [:findings :test-status]))
                          (:findings cp)
                          (ops/last-judged-done st))
                status  (or (:test-status verdict)
                            (history/status-at (:store @(ops/with-history session)) head))
                status (if (= :unknown status) :green status) ; nothing ever ran red
                ;; NO tree is captured. A milestone used to carry a byte-exact
                ;; snapshot of every namespace, because comments lived
                ;; positionally in the elements table — CURRENT state only —
                ;; and so could not be re-derived. That cost 82 MB here, 39% of
                ;; the journal, and by the end it was already a diff chain
                ;; against the previous milestone. Comments are form-owned
                ;; content now, so the log is a complete account and
                ;; `git/project-journal!` folds it to render the tree it needs.
                ;; a SUMMARY of done's findings, not a second implementation:
                ;; name the findings that actually fired so the refusal is
                ;; actionable without re-deriving anything
                ;; :scope and :lint-warnings are INFORMATIONAL — always present,
                ;; never a reason. Listing them as things that fired made a
                ;; refusal say "scope" instead of "unused-public".
                wrong  (->> (dissoc verdict :test-status
                                    :scope :lint-warnings :failures)
                            (remove (fn [[_ v]] (or (and (number? v) (zero? v))
                                                    (and (coll? v) (empty? v)))))
                            (map (comp name key))
                            sort vec)]
            (if (and (= :red status) (not force))
              {:error (str "verification is RED — milestone refused"
                           (when (seq wrong)
                             (str " — " (str/join ", " wrong)))
                           ". Your work is at its done-point; the full"
                           " list is in :findings — and if this done"
                           " judged nothing (no writes since the last"
                           " one), the RED verdict of that earlier done"
                           " still stands. Fix and retry, or :force"
                           " true to record a red milestone honestly.")
               :status :red :done (:done cp) :test (:test cp)
               :findings verdict}
              (mark! head status {:done (:done cp)}
                     (cond-> (or extra {})
                       (seq (:deps st))  (assoc :deps (:deps st))
                       (seq (:files st)) (assoc :files (:files st))
                       (or (seq (:config st)) (read.modules/modules-config-entry st))
                            (assoc :config (cond-> (:config st)
                                             (read.modules/modules-config-entry st)
                                             (assoc "modules" (read.modules/modules-config-entry st)))))))))))))

(defn ^:export spot-run!
  "The tier-aware SPOT-CHECK behind test_run {ns ..}/{only ..}: each named
  target runs in ITS tier — in-image members through the traced, diagnosed
  in-image runner, ^:external members through ONE serial external JVM
  (build + cognitect -v), which is the targeted fresh run the red/green
  loop on an external test needs (naming one used to match 0 tests
  in-image and teach a manual whole-ns detour). No external member named →
  exactly the in-image run of api/test-run!. Entries that cannot be
  tier-resolved (unqualified without :ns, unknown names) stay on the
  in-image side, where the 0-matched teaching still applies."
  [session & {:keys [ns only fresh]}]
  (let [st       (:store @session)
        ns-sym   (some-> ns symbol)
        tiers    (memoize (fn [tns] (engine/test-var-tiers st tns)))
        qual     (fn [o] (let [s (str o)]
                           (if (str/includes? s "/")
                             (symbol s)
                             (when ns-sym (symbol (str ns-sym) s)))))
        ext?     (fn [q] (let [tns (symbol (namespace q))
                               nm  (symbol (name q))]
                           (boolean (some #(= nm %) (:external (tiers tns))))))
        pairs    (map (fn [o] [o (qual o)]) only)
        ext      (cond
                   (seq only) (vec (for [[_ q] pairs :when (and q (ext? q))] q))
                   ns-sym     (mapv #(symbol (str ns-sym) (str %))
                                    (:external (tiers ns-sym)))
                   :else      [])
        img-only (seq (for [[o q] pairs :when (not (and q (ext? q)))] o))
        img?     (cond
                   (seq only) (boolean img-only)
                   ns-sym     (boolean (seq (:image (tiers ns-sym))))
                   :else      true)]
    (cond
      (empty? ext)
      (ops/test-run! session ns-sym :only only :fresh fresh)

      (not img?)
      (assoc (external-test-run! session :only ext)
             :note "external-tier spot-check — ran in one fresh serial JVM")

      :else
      (let [img (ops/test-run! session ns-sym :only img-only :fresh fresh)
            ex  (external-test-run! session :only ext)]
        ;; the external members RAN — the in-image side's pending note about
        ;; them would contradict the result beside it
        {:image    (dissoc img :note :external-pending)
         :external ex
         :status   (if (or (pos? (:fail img 0)) (pos? (:error img 0))
                           (not= :green (:status ex)))
                     :red
                     :green)}))))

(defn ^:export currency-now
  "The CURRENCY half of a whole-store verdict — `:host-stale`, `:bundle` and
  `:app` — each present only when it has something to say.

  These are the fields a check REPORTS rather than EARNS, and the distinction
  decides whether a verdict may be reused. Everything else in a verdict is a
  function of store CONTENT — lint, layering, the rule sweep, the test results
  — so for unchanged content it stays true however much later it is read. These
  three describe artifacts OUTSIDE the store: the running host's image, the
  compiled browser bundle, the served app. They go stale with no delta at all,
  because serving an app or rebuilding a bundle is not a write.

  So this is a function rather than three clauses inline, and the reason is
  concrete: `full-check!` hands back a verdict that still STANDS without
  re-running anything, and must overlay these fresh or it describes a world
  that has moved on. It shipped without doing so, and the report went nil the
  first time an app server appeared between two checks — the guard was right
  and the payload was stale. Computing currency in two places is how the two
  answers drift, which is what this exists to make impossible.

  Every count here is a journal read by index (`db/code-deltas-after`,
  `db/last-artifact-put`, `db/ops-after`) — the value no longer carries the
  history a count over the whole line would need."
  [session st]
  (let [conn   (:db @session)
        line   (engine/session-line session)
        app    (ops/app-behind session (:app-server @session))
        art    (db/last-artifact-put conn line "public/cljs/main.js")
        bundle (orient/bundle-currency st art (when art (db/ops-after conn line (:id art))))
        host   (host-warning-now session st)]
    (cond-> {}
      host (assoc :host-stale host)
      ;; The BROWSER's artifact, third after the host and the jar and the only
      ;; one that had no report. Reported only when BEHIND, unlike :app: a
      ;; store with no client code has no bundle and must not be told about
      ;; one, and `bundle-currency` answers nil there rather than 0 for the
      ;; same reason. A store once took a green done, a green commit_point, a
      ;; green whole-store check AND :app {:behind 0} while the page served a
      ;; bundle from before ten screens were rewritten — nothing was wrong,
      ;; because :app measures the IMAGE and its zero was honest about a
      ;; different artifact.
      (and bundle (pos? (:behind bundle)))
      (assoc :bundle
             (assoc bundle :note
                    (str (:behind bundle) " CLIENT code change(s) since the browser"
                         " bundle was compiled — the page is serving JavaScript"
                         " from before them. compile_client rebuilds it. Nothing"
                         " else here can tell you: :app tracks the IMAGE, and a"
                         " green there is honest about a different artifact.")))
      ;; slopp-ui friction #5, bitten twice: a restyled page passed the
      ;; whole-store check, compile_client and a bundle copy, and the SERVED
      ;; stylesheet was still the old one. Markup that has moved on from its
      ;; stylesheet does not render as an old page, it renders as a broken one
      ;; — and nothing said so, because `done` fixes it silently. `app` is 0
      ;; rather than nil when current, deliberately: silence would put the
      ;; reader back to curling the endpoint, which is the friction itself.
      app (assoc :app
                 (cond-> {:behind app
                          :url (:url (:app-server @session))}
                   (pos? app)
                   (assoc :note
                          (str app " code change(s) since the app"
                               " image was built. It is rebuilt at"
                               " DONE grain, so call done to"
                               " re-serve — until then the browser"
                               " is showing an older store than"
                               " this verdict describes")))))))

(def ^:export default-branch-image-ttl-ms
  "How long an idle per-branch image is held before `reap-idle-images!` stops
  it. Ten minutes, chosen when a session was assumed to be alone on the box.

  It is a MEMORY LEASE, and that is what makes it worth naming rather than
  inlining: every branch a session visits leaves a JVM behind for this long,
  so the cost is per-writer times per-branch, and it grows exactly when a host
  runs many writers at once. A server reads a host override
  (`SLOPP_BRANCH_IMAGE_TTL_MS`) over it; this is the answer for everyone who
  passes nothing."
  600000)

(defn ^:export ^{:live-handle true
        :malli/schema
        [:=> {:throws [[:map]]}
         [:cat [:? [:map
                    [:slopp.ops/dir {:optional true} [:maybe :some]]
                    [:slopp.ops/warm-spare? {:optional true} [:maybe :boolean]]
                    [:slopp.ops/async-image? {:optional true} [:maybe :boolean]]
                    [:slopp.ops/branch-image-ttl-ms {:optional true} [:maybe :int]]
                    [:slopp.ops/agent-id {:optional true} [:maybe :string]]
                    [:slopp.ops/read-only? {:optional true} [:maybe :boolean]]]]]
         :any]}
  open!
  "Start a session: the owned image + the store — loaded from `<dir>/.slopp/`
  when `:slopp.ops/dir` is given and it has history, empty otherwise.
  `:slopp.ops/warm-spare? true` keeps a spare image warming in the background
  so restarts are near-instant. `:slopp.ops/agent-id` (default:
  session-identity) keys every delta/turn/episode this session writes.

  `:slopp.ops/async-image? true` returns as soon as the store VALUE is
  loaded (fast) and boots the image on a BACKGROUND thread — the MCP server
  uses this so its `initialize` handshake completes without waiting for N
  namespaces to load into a child JVM (which, under load, raced the client's
  connect timeout and left a concurrent session with zero tools). Read-only
  store tools serve immediately; oracle/write tools `api/await-image!` the
  boot. The DEFAULT stays synchronous — every existing caller gets a
  fully-loaded image on return, unchanged.

  The option keys are QUALIFIED — `{:slopp.ops/dir …}` — and the schema, the
  destructure, and every call site agree. (The schema once documented bare
  `:dir` while the destructure required the qualified key, so a caller
  trusting it silently opened an EMPTY store — on the busiest entry point in
  the store.)

  The `:=>` schema is DOCUMENTATION, not a verified claim: this fn boots a
  JVM, so `analyzer-pure?` excludes it from the generative oracle-check.

  `:throws` is non-empty but SHAPELESS, and both halves are the honest claim.
  Non-empty because a failed SQLite open or image boot propagates — the catch
  below releases what came up and rethrows, so a caller must handle it. Shapeless
  because this fn MINTS no ex-data: what arrives is whatever `db/open!` or
  `boot-image!` raised, and naming a map of keys here would invent a contract
  no code upholds. `[]` would be the worse lie of the two — it declares that
  nothing is signalled by throwing, and nothing checks that here.

  The session atom is built FIRST and every resource lands in it as it comes
  up, so the single failure path is `close!` — which is per-resource safe.
  Before this, a throw during the image-load loop abandoned the booted image,
  the warming spare, the reaper timer, and the SQLite connection: the atom
  never reached the caller, so nothing could ever release them."
  ([] (open! {}))
  ([{:slopp.ops/keys [agent-id branch-image-ttl-ms dir warm-spare? async-image? read-only?]}]
   (let [;; EVERY session has a journal. A named dir is served as a question
         ;; (no store → nil, never an adoption); a dirless open gets a PRIVATE
         ;; one in a temp dir that `close!` removes. History is a db read now,
         ;; and a session whose deltas lived only in the value would need a
         ;; second code path over an in-memory list, kept alive for tests.
         ephemeral (when-not dir
                     (str (java.nio.file.Files/createTempDirectory
                           "slopp-session"
                           (make-array java.nio.file.attribute.FileAttribute 0))))
         dir     (or dir ephemeral)
         conn    (if ephemeral (db/open! dir) (db/open! dir {:create? false}))
         ;; ONE identity, minted once. `session-identity` generates a fresh
         ;; random id per call, so computing it twice would key this session's
         ;; THREAD to one id and its deltas to another.
         me      (or agent-id (engine/session-identity))
         ;; Adopt EAGERLY only when the identity is already settled, which now
         ;; means exactly one thing: the CALLER named it. The MCP server reads
         ;; the driving harness's conversation id at its entry point and passes
         ;; it here, so the ordinary session adopts its thread before its first
         ;; write and loads the store from the right line to begin with.
         ;; A session that names no agent gets a generated id and adopts
         ;; lazily through `engine/adopt-line!` — that is the fallback path
         ;; for a harness slopp does not know, and it costs a store reload and
         ;; a rebuilt image when the thread turns out to hold work.
         stable? (boolean agent-id)
         session (atom {:db conn :dir dir :branch "main" :lines {}
                        :ephemeral-dir? (some? ephemeral)
                        ;; a session that only READS answers from the branch
                        ;; and never adopts a thread — decided HERE, before the
                        ;; boot below resolves the session line for the first time
                        :read-only-line? (boolean read-only?)})]
     (try
       (let [line  (when (and conn stable?)
                     (db/adopt-thread! conn (db/trunk-line-id! conn) me))
             ;; loaded from the session's OWN line, so the image below boots
             ;; the code this session is going to work on rather than the
             ;; branch's — which are the same until a thread holds un-landed
             ;; work, and silently different afterwards
             t0    (System/nanoTime)
             store (or (some-> conn (db/load-store
                                     (or line (db/trunk-line-id! conn))))
                       (store/empty-store))
             ;; EVERY open is an observation. `load-store` was 7.6 s on one
             ;; store and nobody knew until it was timed by hand; a row per
             ;; open — the journal's length, the head, the milliseconds — is
             ;; what turns that into a chart. A measurement, never a delta:
             ;; nothing about what an open cost may move the head.
             _     (when conn
                     (db/record-measurement!
                      conn "open" nil
                      {:deltas  (:line-pos store 0)
                       :head    (:head store)
                       :load-ms (quot (- (System/nanoTime) t0) 1000000)}))
             ttl   (or branch-image-ttl-ms default-branch-image-ttl-ms)]
         ;; SYNC phase: the store value + everything reads need, no image
         (swap! session assoc
                :store store
                :line line
                :data-version (some-> conn db/data-version)
                :test-map (or (engine/load-trace conn store) {})
                :observed (engine/load-observations conn)
                :agent-id me
                ;; the caller PINNED this identity, so nothing may reassign it
                ;; later — the same fact `stable?` adopted the thread on, named
                ;; once. It was `:env-agent?` while SLOPP_AGENT was the only
                ;; way to settle one, and that name outlived its reason.
                :pinned-agent? stable?
                :branch-image-ttl-ms ttl
                :warm-spare? (boolean warm-spare?))
         ;; #134: kondo's cross-ns cache follows the STORE, not the process cwd.
         ;; Unset, kondo resolves it from cwd — so cross-ns findings existed only
         ;; where a .clj-kondo/ happened to sit beside the process, and a user
         ;; project's :carried stale-caller gate silently found nothing. A dirless
         ;; session gets an owned temp dir rather than inheriting whatever is there.
         (reset! index/kondo-cache-dir
                 (if conn
                   (str (io/file dir ".slopp" "kondo-cache"))
                   (str (java.nio.file.Files/createTempDirectory
                         "slopp-kondo"
                         (make-array java.nio.file.attribute.FileAttribute 0)))))
         ;; image boot: inline (sync default) or on a daemon thread (async),
         ;; which arms the ready-promise await-image! blocks on
         (if async-image?
           (do (swap! session assoc :image-ready (promise))
               (doto (Thread. ^Runnable #(boot-image! session store conn me ttl)
                              "slopp-image-boot")
                 (.setDaemon true)
                 (.start))
               session)
           (boot-image! session store conn me ttl)))
       (catch Throwable t
         (ops/close! session)
         (throw t))))))

(defn ^:export record-or-keep!
  "Run `record!` to journal `res`, and return `res` EITHER WAY — marked
  `:recorded false` when the append lost its compare-and-swap.

  **A completed answer must outlive its bookkeeping.** `full_check` spends
  three to four minutes computing a whole-store verdict and then appends one
  delta saying it happened. That append is a CAS against the branch head, and
  on a busy branch it can lose twelve times and throw. It used to throw
  THROUGH the verdict: the caller asked whether the store was green, the
  answer was computed and correct, and it was discarded because a note about
  it could not be written. Measured on this store in one evening: four
  refusals, about nine minutes of real verification thrown away, and the
  caller told only *call again* — which meant re-running the same four
  minutes into the same contention.

  **Only RETRYABLE failures are absorbed**, and the narrowness is the whole
  design. `engine/commit-appended!` marks ordinary head contention
  `{:retryable true}`; anything else — a corrupt store, a bug in the append
  path — propagates untouched. Swallowing those would turn a broken journal
  into a cheerful green verdict, which is worse than the problem this fixes.

  `:recorded false` rides the result rather than being logged and forgotten,
  because a reader counting whole-store checks in the journal would otherwise
  be quietly short one, with nothing to say so."
  [res record!]
  (try
    (record!)
    res
    (catch clojure.lang.ExceptionInfo e
      (if (:retryable (ex-data e))
        (assoc res
               :recorded false
               :record-note (str "this verdict is CORRECT and was not journaled:"
                                 " the branch head moved under every attempt to"
                                 " append it, which happens when another writer"
                                 " is landing continuously. Nothing about the"
                                 " check itself is in doubt — only the record"
                                 " that it ran. Ask again later if the journal"
                                 " needs the entry; do not re-run for the"
                                 " verdict, which you already have."))
        (throw e)))))

(defn- record-full-check!
  "Stamp the whole-store verdict with its wall cost and land it in the journal
  as a `:verify` delta scoped `:full-check`.

  Two things were missing and they are the same thing. `full_check` is the
  most expensive operation slopp performs — ~190s on a 125-namespace store,
  almost entirely the external tier's fresh-JVM boots — and it wrote NOTHING,
  so the only after-the-fact attribution was the gap before whatever delta
  landed next. It is also the verdict most worth standing behind, and
  \"when did this store last pass a whole-store check, and was it green?\" had
  no answer in the log either.

  Only the SHAPE of the verdict is recorded, never the finding lists: the
  journal is append-only and a red full_check's lint rows can be large."
  [res session nses t0]
  (let [res (assoc res :ms (- (System/currentTimeMillis) t0))]
    ;; through [[record-or-keep!]], because the ANSWER is what was expensive.
    ;; This append is a CAS against the branch head; losing it used to throw
    ;; through the verdict and discard three to four minutes of whole-store
    ;; verification over a note that could not be written.
    (record-or-keep!
     res
     #(engine/commit-appended!
       session
       (fn [st]
         (store/record-verification
          st (vec nses)
          (assoc (select-keys res [:status :ms :namespaces :lint-errors :lint-warnings])
                 :scope :full-check)))
       []))))

(defn ^:export run-full-check!
  "The WHOLE-STORE check, on demand: kondo over every namespace, the
  dead-public-surface report over every namespace, BOTH layering graphs —
  purity tiers and the module architecture — the RULE CATALOG swept over every
  form, and every test in every tier: the in-image suite, `^:integration`, and
  the external `^:external` tier.

  Deliberately NOT forced anywhere, not by `done` and not by `commit_point`.
  `done` is episode-scoped: it answers whether the work you just did is good,
  which is the question you can act on. This answers whether the STORE is
  good, which is a different and much slower question — and one only the
  agent can judge the right moment for. `done` names this tool in its result
  so the choice is visible rather than forgotten.

  It also retires any need for an integration-only or lint-only tool: one
  call, everything, no tier flags to get wrong.

  Returns {:lint [...] :lint-errors n :lint-warnings n :unused [...] :stale
  [...] :tier-layering [...] :module-violations {...} :rules {...} :test {...}
  :external {...} :status :green|:red}.

  `:rules` is ALWAYS present and carries `:swept` / `:not-swept` beside its
  `:findings`, because roughly a third of the advisory registry compares
  against the episode's baseline and cannot answer a whole-store question at
  all — naming them is what stops a green from claiming coverage it never had.

  `:checked` is the same argument, applied to every OTHER whole-store read.
  Each of those is folded in only when it has something to say, so a clean
  store and a read that never ran produced identical output — the exact
  failure this check exists to prevent, sitting inside the check. It maps each
  read to the POPULATION it examined rather than merely naming it, because a
  name only claims it ran: a read reporting 0 is visibly broken, where an
  absent key was indistinguishable from clean. Found by slopp-ui, who could
  verify one regrade from `:swept` and had to hand-build a control for the
  other.

  Plus, when this project has a managed app server up, `:app {:behind n
  :url}` — how many code changes the SERVED image is behind the store it just
  called green. `0` is reported rather than omitted: the question is \"is the
  page I am about to look at built from what I just wrote?\", and staying
  silent on yes leaves the reader curling the endpoint by hand.

  And `:bundle {:sha :behind :note}` when the compiled browser bundle is behind
  CLIENT code — the third artifact that can be stale, after the host and the
  jar, and the only one that had no report. A store took a green `done`, a green
  `commit_point`, a green check here AND `:app {:behind 0}` while the browser
  served a bundle from before ten screens were rewritten; nothing was wrong,
  because `:app` measures the IMAGE and its zero was honest about the image.
  Reported only when BEHIND, unlike `:app`: a store with no client code has no
  bundle and must not be told about one."
  [session & {:keys [affected]}]
  (let [t0    (System/currentTimeMillis)
        st    (:store @session)
        nses  (sort (keys (:namespaces st)))
        ;; The whole-store gate must not inherit incremental kondo state. kondo
        ;; reads cross-ns facts from a disk cache that each lint TEACHES, so a
        ;; cache predating recent vars makes whatever is linted early get judged
        ;; against yesterday's facts — once four phantom `invalid-arity` ERRORS
        ;; and eleven unresolved vars, on a store whose every test passed. A
        ;; STALE fact lies confidently; an ABSENT one is benign.
        _     (index/reset-kondo-cache!)
        ;; and teach it callees-first — the same "deps first" order every loader
        ;; uses — so nothing is judged against a fact not yet refreshed
        lint  (vec (for [n (store/ns-dependency-order st)
                         :let [src (store.render/render-ns st n)]
                         f (index/lint src (store/kondo-lang st n))]
                     (-> f (dissoc :row :col) (assoc :ns n))))
        rep   (read.modules/unused-report st nses)
        ;; tier LAYERING — a whole-graph property, so it lives here rather
        ;; than at a declaration: core must not depend on shell. This is the
        ;; check effect-reachability cannot make, since that sees a cross-ns
        ;; effect only when the callee is `!`-named.
        layer (vec (for [n nses
                         :when (not (str/ends-with? (str n) "-test"))
                         :let [t (tiers/tier-for st n)]
                         v (tiers/layering-violations st n t)]
                     ;; :external by ABSENCE and :external by DECLARATION read identically
                     ;; in the row, and only the first has a one-call fix. The
                     ;; finding names the namespace whose CLAIM breaks, which is
                     ;; the one that did not change; say which case this is so
                     ;; the reader is not sent to restructure code when a
                     ;; missing declaration is the whole story.
                     (cond-> {:ns n :tier t
                              :requires (:requires v) :requires-tier (:tier v)}
                       (not (tiers/tier-declared? st (:requires v)))
                       (assoc :requires-undeclared true))))
        ;; MODULE layering — the architecture graph, and a DIFFERENT graph
        ;; from the tiers above, so a green there says nothing about this.
        ;; The module rules are WRITE gates: they see only code written
        ;; THROUGH them, and a rename rewrites its own callers, which never
        ;; pass a gate. This fold is the only thing that asks again — the
        ;; operation most likely to drift the architecture being exactly the
        ;; one the per-write check cannot see. Four real visibility
        ;; violations stood on this store through a green check before it was
        ;; wired in (friction #19); `module-debt` itself already existed and
        ;; was asked only by the graph view and by `module_dep`.
        mods  (read.modules/module-debt st)
        ;; the edges the PIPELINE declared on writes' behalf and nobody
        ;; retracted. Each was the refusal the agent would have obeyed; the
        ;; refusal was also the one moment anybody asked whether the call
        ;; should exist, and that moment is gone by design — so the
        ;; accumulation is reported here, always, as a count a reader can
        ;; branch on (the wire keeps the edges behind verbose)
        autoedges (read.modules/auto-declared-edges (ops/journal session))
        ;; the RULE CATALOG, and the identical argument one layer up. A
        ;; `:grain :done` rule fires over forms an EPISODE changed, so a
        ;; violation older than the rule is invisible to `done` — and stays
        ;; invisible, because no later episode changes that form either.
        ;; slopp-ui carried two `direct-http` violations through a green check
        ;; here for exactly that reason (friction #27): two violations, three
        ;; tools, no report. `sweep-store!` names what it could NOT ask as well
        ;; as what it did, since a third of the registry compares against the
        ;; episode's baseline and running one of those over every form reports
        ;; nothing in the same shape as clean.
        sweep (rules/sweep-store! session st)
;; a namespace holding nothing but its own ns form. Reported HERE
        ;; because it can be reported nowhere else: every advisory is
        ;; addressed by changed FORM IDS and sweep-store! builds its
        ;; whole-store population the same way, so a namespace with zero
        ;; forms is in neither and no rule can reach it however it is
        ;; written. slopp.http-rules-test survived two days and a green
        ;; check here after the R6 rules move emptied it.
        husks (read.modules/empty-namespaces st)
        aliasdrift (read.modules/alias-drift st)
        ;; WHAT RAN, always — the same argument `:rules` already makes with
        ;; `:swept`, applied to the reads that had no equivalent. Every read
        ;; below is folded into the result only when it has something to say,
        ;; so a clean store and a read that never ran produced byte-identical
        ;; output. That is the exact failure this check exists to prevent,
        ;; sitting in the check itself.
        ;;
        ;; The POPULATION rather than the name, because a name only claims it
        ;; ran. A read that examined 0 namespaces is visibly broken, where
        ;; "alias-drift: absent" was indistinguishable from clean — which is
        ;; how slopp-ui came to verify a regrade by hand-building a control.
        checked {:lint             (count nses)
                 :dead-surface     (count nses)
                 :tier-layering    (count (remove #(str/ends-with? (str %) "-test") nses))
                 :module-debt      (count nses)
                 :empty-namespaces (count nses)
                 :alias-drift      (count nses)
                 :crossings        (count nses)
                 :rule-sweep       (:forms sweep)}
        errs  (filterv #(= :error (:level %)) lint)
        warns (filterv #(= :warning (:level %)) lint)
        tests (engine/run-verification! session (vec nses) nil
                                         :include-integration? true
                                         :boundary? true)
        ;; ONLY this tier narrows. Measured: the external suite is ~187s of a
        ;; ~190s full_check (299 image boots), while lint + dead surface +
        ;; layering + the in-image suite together are ~5-7s. Narrowing the
        ;; cheap half would buy nothing and cost exactly the coverage
        ;; full_check exists for.
        iso   (when (seq (engine/external-test-nses
                          st (filter #(engine/test-ns? st %) nses)))
                (external-test-run! session :affected affected))
        red?  (or (seq errs) (seq (:unused rep)) (seq (:stale rep))
                  (seq layer)                ; core→shell is a failure, not a note
                  mods                       ; and so is a standing module
                                             ; violation: the per-write gates
                                             ; REFUSE these, so one still
                                             ; standing got in by a path around
                                             ; the gate. Same rule, one bar —
                                             ; advisory here would make the
                                             ; write gate the stricter of the
                                             ; two, which is backwards for a
                                             ; whole-store check
                  ;; and the rule catalog grades through the SAME predicate
                  ;; `done` uses, so a rule dialed :error is :error in both
                  ;; places and an :advisory is reported in both without
                  ;; flipping. One rule, one check, one bar; the sweep is a
                  ;; different POPULATION, never a different standard.
                  (rules/status-affecting-fired? st (:findings sweep))
                  (pos? (+ (:fail tests 0) (:error tests 0)))
                  (contains? #{:red :error} (:status iso)))]
    (cond-> {:namespaces (count nses)
             :lint-errors (count errs)
             :lint-warnings (count warns)
             :checked checked
             :rules sweep
             :modules {:auto-declared (count autoedges) :edges autoedges}
             :test tests
             :status (if red? :red :green)}
      affected (assoc :scope (str "lint, dead surface, tier layering, module"
                                  " layering, the rule sweep and the in-image"
                                  " suite covered ALL "
                                  (count nses) " namespaces;"
                                  " the ^:external tier was narrowed to the tests"
                                  " that changes since the last milestone can"
                                  " reach. Drop :affected for the whole tier"))
      (seq errs)          (assoc :lint errs)
      (seq warns)         (assoc :warnings warns)
(seq husks)         (assoc :empty-namespaces husks
                                 :empty-namespaces-note
                                 (str (count husks) " namespace(s) holding nothing"
                                      " but their own ns form. A move that carries"
                                      " a namespace's whole contents elsewhere"
                                      " leaves one, and nothing else reports it:"
                                      " there is no form to be dead, undocumented"
                                      " or uncovered, and namespace-purpose exempts"
                                      " an empty namespace because a NEWBORN one has"
                                      " nothing to describe yet. ns_delete retires a"
                                      " husk; adding a form is the other honest"
                                      " answer, and it is why this is reported"
                                      " rather than refused"))
      (image.currency/broken (:image @session))
      (assoc :currency-broken (image.currency/broken (:image @session))
             :currency-broken-note
             (str "the verification image's currency record stopped being"
                  " maintained, so every currency answer about it is now"
                  " \"not measured\" rather than a claim. Bookkeeping is caught"
                  " on the write path deliberately — a stamp that throws must"
                  " not veto a load that succeeded — so this is the report that"
                  " would otherwise be a silent gap. `restart` builds a fresh"
                  " image and clears it; the message names what threw"))
      (seq aliasdrift)    (assoc :alias-drift aliasdrift
                                 :alias-drift-note
                                 (str (count aliasdrift) " require(s) name a store"
                                      " namespace by something other than its"
                                      " canonical alias — the shortest trailing"
                                      " segments naming exactly one namespace here."
                                      " Harmless to the TOOLS, because the reference"
                                      " graph is alias-blind and renames and"
                                      " query_depends were never confused by it."
                                      " Costly to a READER: when one alias names two"
                                      " namespaces, x/f in a slice is two different"
                                      " functions and the page does not say which."
                                      " It is also the one way a hand sweep can be"
                                      " wrong where the graph is right. ns_realias"
                                      " is the remedy, one namespace at a time;"
                                      " reported and never refused, because most of"
                                      " it is residue from renames that moved a"
                                      " namespace and left the :as behind"))
      (seq layer)         (assoc :tier-layering layer
                                 :tier-layering-note
                                 (str (count layer) " core→shell dependency(ies):"
                                      " a namespace depends on one at a LOOSER"
                                      " tier. Either move what it needs into a"
                                      " core namespace, or its own tier is a"
                                      " claim it does not earn"
                                      (when (some :requires-undeclared layer)
                                        (str ". Rows marked :requires-undeclared"
                                             " name a dependency that is"
                                             " :external only because nothing"
                                             " DECLARED it — usually a namespace"
                                             " a move or split just created;"
                                             " module_purity on that namespace"
                                             " may be the whole fix"))))
      mods                (assoc :module-violations mods
                                 :module-violations-note
                                 (str (:count mods) " module rule violation(s)"
                                      " standing in the store. Each is what a"
                                      " write gate would REFUSE, so each got"
                                      " here by a path around the gate —"
                                      " usually a rename, which rewrites its"
                                      " own callers. Declare the edge"
                                      " (module_dep), hoist the target"
                                      " (^:export), or restructure the call"))
      (seq (:findings sweep))
      (assoc :rules-note
             (str (count (:findings sweep)) " rule(s) with standing findings over "
                  (:forms sweep) " form(s). `done` is EPISODE-scoped, so any of"
                  " these older than the rule itself is invisible to every done"
                  " there will ever be — this sweep is the only thing that asks"
                  " again. :error-severity findings flipped this check red;"
                  " :advisory ones are reported and did not. query_rules names"
                  " each rule's escape"))
      (seq (:unused rep)) (assoc :unused-public (:unused rep))
      (seq (:stale rep))  (assoc :stale-unused-ok (:stale rep))
      iso                 (assoc :external iso)
      ;; The three fields this check REPORTS rather than EARNS — the host's
      ;; image (friction #10), the browser bundle, and the served app (slopp-ui
      ;; friction #5). A whole-store green is exactly the verdict an agent
      ;; commits on, so an artifact serving superseded code has to say so HERE.
      ;; Shared with `full-check!` rather than written twice, and that sharing
      ;; is the point: none of the three is a function of store content, so a
      ;; verdict handed back because it still STANDS would otherwise describe
      ;; the world as it was when the verdict was earned. Serving an app is not
      ;; a write, and nothing else would ever refresh them.
      true                  (merge (currency-now session st))
      ;; Verification stops at the boundary: everything above is an edge INSIDE
      ;; the store. A green here
      ;; says nothing about what LEAVES it, and reads as though it did — so
      ;; name the exits nothing checks, right where the green is about to be
      ;; believed. Advisory: these are standing documented holes, not
      ;; regressions.
      (crossings/finding st) (assoc :crossings (crossings/finding st))
      ;; last, so the recorded verdict is the one actually returned
      true                  (record-full-check! session nses t0))))

(defn ^:export full-check!
  "The WHOLE-STORE check — `run-full-check!`, except that a verdict which
  STILL STANDS is returned instead of re-earned.

  When nothing since the last whole-store check could have changed what it
  says, this hands back that verdict with `:standing true` and the delta that
  recorded it, in about a millisecond. `{force true}` runs it anyway.

  **Why the courtesy is worth having here specifically.** This is the most
  expensive operation slopp performs — ~236s on this store, almost entirely
  fresh JVM boots in the external tier — and its own journal says it was
  asked twice for one answer constantly: 325 runs, 117 of them REPEATS inside
  a single ask, 7.6 hours. `commit_point` has always returned an unchanged
  milestone rather than re-minting one, and the argument is the same, only
  the number is four hundred times larger.

  It REPORTS rather than refuses, which is the same stance `done` takes. An
  agent that asks again gets an answer, promptly, plus the fact that it did
  not need to ask — so the habit corrects itself instead of being blocked.

  **A standing verdict is not replayed wholesale.** It reuses what it EARNED
  — lint, layering, the rule sweep, the test results, all functions of store
  content — and RECOMPUTES what it merely reported about live artifacts, via
  `currency-now`. `:app`, `:bundle` and `:host-stale` describe things outside
  the store and go stale with no delta at all: serving an app is not a write,
  so nothing retires the verdict and nothing else would refresh them. Shipped
  without that overlay, this reported `:app nil` the first time an app server
  appeared between two checks — the guard was right and the payload was stale.

  What counts as a change is deliberately generous: see
  `read.history/verdict-inert-ops`. A config write can arm a capability's
  rules and a module edge changes the layering graph, neither of which
  touches a form, so only provably inert bookkeeping is ignored and an
  unclassified op means re-run. The failure mode is a check nobody needed
  rather than a green nobody earned."
  [session & {:keys [affected force]}]
  (if-let [standing (and (not force)
                         (let [conn (:db @session)
                               line (engine/session-line session)
                               chk  (db/last-full-check conn line)]
                           (history/standing-full-check
                            chk (when chk (db/ops-after conn line (:id chk))))))]
    (assoc (merge (dissoc standing :app :bundle :host-stale)
                  (currency-now session (:store @session)))
           :standing true
           :note (str "nothing since this verdict could have changed it, so it"
                      " STANDS — no check was run. This is the whole-store"
                      " answer, "
                      (when-let [ms (:ms standing)] (str "earned in " ms "ms, "))
                      "and it is current: a forced re-run cannot say more, so hand this"
                      " verdict over as it stands. A write of any kind retires it on its own."))
    (run-full-check! session :affected affected)))

(defn ^:export compact-store!
  "Reclaim the views settled lines still carry and vacuum the file — the
  DELIBERATE step for a store that grew before `land-thread!` learned to drop a
  landed thread's rows. Returns `{:rows-dropped :bytes-before :bytes-after}`
  plus `:reclaimed` in bytes, or a `:note` when nothing is on disk yet.

  Sits beside [[store-health]] on purpose: that one answers what the store
  COSTS, this one gives some of it back. It is a tool rather than a side effect
  of the next land because a consumer said so in as many words — a shrink they
  run on purpose and can read in numbers beats a file that got smaller when
  they were not looking, and `VACUUM` on a multi-GB file holds the lock long
  enough that it should never surprise a concurrent writer."
  [session]
  (let [{:keys [db]} @session]
    (if db
      (let [r (db/compact! db)]
        (assoc r :reclaimed (- (:bytes-before r) (:bytes-after r))))
      {:note "no durable store on disk yet — nothing to compact"})))
