(ns slopp.ops.testrun
  "Running the store's tests in processes OUTSIDE this one.

  The `^:external` tier exists because those tests spawn their own images, so
  running them in-image would recurse. That makes this the one namespace whose
  subject is a process tree rather than a value — it shells a runner JVM per
  shard, and each of those spawns a fresh image JVM per test.

  Owning that tree is the recurring problem here, not a detail. Every runner
  is BOUNDED (a hung test used to wedge `done` and the milestone gate
  forever), and killing one now takes its whole subtree with it (`reap!`) —
  because destroying the runner alone leaves exactly the processes that do the
  damage, orphaned and reachable by nothing. Two abandoned runs once took a
  machine to load average 20 that way.

  The rest is shard arithmetic: how many JVMs to use, which namespaces go in
  which shard, and how to merge their summaries into one honest verdict —
  including refusing to call a run green when the JVM printed green and then
  died."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [slopp.image.repl :as repl] [slopp.image.testmain :as testmain] [clojure.java.io :as io] [clojure.edn :as edn] [slopp.index.refs :as refs]))

(defn ^{:export "slopp.verification"} parse-test-summary
  "Parse a clojure.test runner's terminal summary into
  {:ran :assertions :failures :errors :status}, or nil if none is present."
  [output]
  (when-let [[_ t a f e] (re-find
                          #"Ran (\d+) tests containing (\d+) assertions\.\s+(\d+) failures?, (\d+) errors?"
                          (str output))]
    (let [f (parse-long f) e (parse-long e)]
      {:ran (parse-long t) :assertions (parse-long a)
       :failures f :errors e
       :status (if (and (zero? f) (zero? e)) :green :red)})))

(defn ^{:export "slopp.verification"} parse-test-failures
  "The FAIL/ERROR blocks from a clojure.test runner's output:
  [{:test name :detail block}] (up to `limit` blocks, each capped ~500 chars)
  — so an external run NAMES its failures instead of making the caller
  rebuild the project and rerun the suite just to see them (Q2)."
  [output & {:keys [limit] :or {limit 5}}]
  (->> (str/split (str output) #"\n(?=(?:FAIL|ERROR) in )")
       (keep (fn [b]
               (when-let [[_ nm] (re-find #"^(?:FAIL|ERROR) in \(([^)\s]+)\)" b)]
                 (let [block (-> (->> (str/split-lines b)
                                     (take-while (complement str/blank?))
                                     (str/join "\n"))
                                 ;; strip the VFS coordinate — the test is
                                 ;; NAMED in :test; file:line is unconsumable
                                 (str/replace #"\s*\([\w/._-]+\.clj:\d+(?::\d+)?\)" ""))]
                   {:test nm
                    :detail (if (< 500 (count block))
                              (str (subs block 0 500) " …")
                              block)}))))
       (take limit)
       vec))

(defn ^{:export "slopp.verification"} failing-test-rollup
  "EVERY failing test name from a runner's output, grouped by file:
  {file [test-names]} — the :failing detail blocks are capped, so without
  this a many-failure run needs fix-rerun loops just to enumerate its
  fallout classes (measured: 50 failures × 5-block cap = four reruns)."
  [output]
  (->> (str/split (str output) #"\n(?=(?:FAIL|ERROR) in )")
       (keep (fn [b]
               (when-let [[_ nm] (re-find #"^(?:FAIL|ERROR) in \(([^)\s]+)\)" b)]
                 [(or (second (re-find #"\(([^()\s]+\.clj):" b)) "?") nm])))
       distinct
       (reduce (fn [m [f nm]] (update m f (fnil conj []) nm)) (sorted-map))))

(defn ^{:export "slopp.verification"} failure-themes
  "Heuristic ROOT-CAUSE clusters for a red run: word 3-grams from the
  QUOTED strings inside each failure block (error messages carry the
  cause; expected/actual scaffolding is noise), ranked by how many
  distinct tests mention them (>=3), subset-covered grams dropped —
  '38 failures say does-not-declare' in one read instead of an
  enumerate-classify loop. Advisory; the blocks stay authoritative."
  [output]
  (let [blocks (keep (fn [b]
                       (when-let [[_ nm] (re-find #"^(?:FAIL|ERROR) in \(([^)\s]+)\)" b)]
                         [nm b]))
                     (str/split (str output) #"\n(?=(?:FAIL|ERROR) in )"))
        grams  (fn [b]
                 (let [quoted (map second (re-seq #"\"([^\"]+)\"" b))
                       ws     (mapcat #(re-seq #"[A-Za-z][A-Za-z-]{2,}" %) quoted)]
                   (distinct (map #(str/join " " %) (partition 3 1 ws)))))
        counts (reduce (fn [m [nm b]]
                         (reduce #(update %1 %2 (fnil conj #{}) nm) m (grams b)))
                       {} blocks)
        ranked (sort-by (fn [[g ts]] [(- (count ts)) g])
                        (filter #(>= (count (val %)) 3) counts))]
    (loop [rs ranked, seen [], out []]
      (if (or (empty? rs) (>= (count out) 5))
        out
        (let [[g ts] (first rs)]
          (if (some #(set/subset? ts %) seen)
            (recur (rest rs) seen out)
            (recur (rest rs) (conj seen ts)
                   (conj out {:phrase g :tests (count ts)}))))))))

(defn ^{:export "slopp.verification"} auto-parallel
  "Default shard count for an external run over `n` test namespaces on a
  `cores`-core box. Each shard reloads the WHOLE materialized store, so
  sharding only pays at real scale: 1 below ~8 test nses (boot overhead
  beats the gain), then n/8 shards, capped at 4 and at half the cores."
  [n cores]
  (max 1 (min 4 (quot cores 2) (quot n 8))))

(def shard-timeout-ms
  "Upper bound for one test-runner JVM. A hung ^:external test used to block
  sh/sh forever — wedging done! and the milestone gate with it. Test failures
  PARSE; the only thing this deadline ever kills is a JVM that stopped
  talking."
  (* 20 60 1000))

(defn ^{:export "slopp.verification"} reap!
  "Kill `proc` and everything it spawned. Returns nil once they are GONE.

  `.destroy` reaches the CHILD only. A test-runner JVM's whole job is to spawn
  a fresh image JVM per test, so killing it without its subtree leaves exactly
  the processes that do the damage — now orphaned, reparented, and reachable
  by nothing slopp holds. Measured once: two runs' worth of abandoned shards
  each spawning an image every few seconds, load average past 20, a trivial
  `query_search` hanging, recovery by hand with `pkill`.

  **Order is the whole design.** Snapshot the descendants first, because a
  dead handle reports none. Then kill the PARENT, so it stops spawning more
  while the sweep runs. Then the subtree, politely and then forcibly.

  **And then WAIT for them.** Signal delivery is asynchronous:
  `destroyForcibly` returns before the kernel has reaped anything, so this
  used to promise a quiet machine and return while the subtree was still
  running. Harmless when the caller was about to block anyway, and not
  harmless at all when the reason for reaping is that the machine is already
  overloaded — precisely when the window is widest. It showed up as a
  full-suite run failing `killing-a-runner-kills-the-processes-it-spawned`
  under four shards, and passing every time in isolation.

  Bounded, because a wait that can hang is a worse failure than an orphan:
  five seconds for the parent, five for the subtree as a whole. What is still
  alive after that was unkillable, and blocking longer would not change it.

  It is safe on a process that has already exited: the snapshot is empty and
  every destroy is a no-op."
  [^Process proc]
  (let [kids (vec (.toList (.descendants (.toHandle proc))))]
    (.destroy proc)
    (when-not (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)
      (.destroyForcibly proc))
    (doseq [^java.lang.ProcessHandle h kids] (.destroy h))
    (doseq [^java.lang.ProcessHandle h kids]
      (when (.isAlive h) (.destroyForcibly h)))
    ;; onExit futures rather than a poll: the JDK already has the notification,
    ;; and one deadline covers the whole subtree instead of N sequential ones
    (let [deadline (+ (System/currentTimeMillis) 5000)]
      (doseq [^java.lang.ProcessHandle h kids]
        (let [left (- deadline (System/currentTimeMillis))]
          (when (pos? left)
            (try
              (.get (.onExit h) left java.util.concurrent.TimeUnit/MILLISECONDS)
              (catch java.util.concurrent.TimeoutException _ nil)
              (catch java.lang.IllegalStateException _ nil))))))  ; not a child of ours
    nil))

(defn ^:export run-cmd!
  "Run `cmd` (a seq of strings) in `dir`, sh-shaped {:exit :out :err}, killed
  at `timeout-ms` WITH EVERYTHING IT SPAWNED (`reap!`): :exit 124 and no
  parseable summary, which the shard-death retry treats honestly as a dead
  JVM. Output is drained on its own thread so a chatty child cannot fill the
  pipe and deadlock the wait.

  The subtree is the point. This used to `.destroy` the child alone, which
  for a test-runner JVM means killing the one process that was NOT the
  problem — its per-test image JVMs survived, orphaned, and kept being
  spawned right up to the moment the runner died. Two abandoned runs took a
  machine to load average 20 that way."
  ([cmd dir] (run-cmd! cmd dir shard-timeout-ms))
  ([cmd dir timeout-ms]
   (let [pb   (doto (ProcessBuilder. ^java.util.List (mapv str cmd))
                (.directory (io/file dir))
                (.redirectErrorStream true))
         proc (.start pb)
         out  (future (slurp (.getInputStream proc)))]
     (if (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
       {:exit (.exitValue proc) :out (deref out 10000 "") :err ""}
       (do (reap! proc)
           {:exit 124
            :out (str (deref out 1000 "")
                      "\n[slopp] test runner exceeded " timeout-ms
                      "ms — killed, with every process it had spawned")
            :err ""})))))

(defn ^:export balance-shards
  "Split `nses` into `n` shards balanced by IMAGE BOOTS rather than by index.

  Shards run concurrently, so the external tier's wall time is its SLOWEST
  shard, not the average — and a shard's cost is dominated by how many fresh
  image subprocesses its tests boot (~1.15s of Clojure loading each, and that
  boot IS the isolation the tier exists for). Round-robin by index ignored
  that. Measured on slopp's own suite: 402 boots across 52 test namespaces
  split `[139 100 90 73]`, so one shard still had 66 boots to go after the
  fastest had finished. Longest-first gives `[101 101 100 100]` — 27% off the
  critical path for the same work on the same cores.

  This is NOT the warm-pool dead end — keeping images alive to skip boot — which
  rescheduled boot work into CPU that was not idle and measured zero gain. It
  removes idle time that already exists, and adds no concurrency.

  The weights come from THE reference graph, not a source scan, so they track
  the code and cannot drift. The order is total (weight, then name), so the
  split is deterministic — a shard assignment that varied between runs would
  make a flake unreproducible.

  **Balanced boots are not balanced time, and a better-looking proxy made it
  WORSE.** This split produces `[132 133 133 133]` boots — textbook — while the
  shards hold `[78 15 14 13]` namespaces and run `[43.3s 108.1s 134.0s
  217.1s]`. A namespace that boots nothing weighs ZERO and packs for free,
  which looks like the bug.

  It is not. Pricing a BASE per namespace plus a heavy term for tests that
  shell a whole project was built and measured TWICE: `264s` and `275s` against
  this weight's `217s`. Those 78 light namespaces really are nearly free —
  43.3s across all of them, ~0.55s each — so pricing them at a boot apiece
  spread them into the shards already carrying the expensive tests.

  **The spread is not a packing failure.** It is a few namespaces costing
  enormously more than the rest, and no split of four shards goes below the
  single most expensive one. Fixing it needs per-namespace MEASUREMENT, which
  the store does not record today (`:observe` deltas carry status, not time) —
  not a cleverer proxy. Read `:cost` on an external result for what the current
  split achieves."
  [store nses n]
  (let [w    (frequencies (map :from-ns
                               (concat (refs/refs-to store 'slopp.ops.external/open!)
                                       (refs/refs-to store 'slopp.ops/open!))))
        cost (fn [grp] (reduce + 0 (map #(get w % 0) grp)))]
    (reduce (fn [shards x]
              (let [i (apply min-key #(cost (nth shards %)) (range (count shards)))]
                (update shards i conj x)))
            (vec (repeat n []))
            (sort-by (juxt #(- (get w % 0)) str) nses))))

(defn ^:export only-shards
  "Split `only` — qualified test vars — into at most `n` shards, along
  NAMESPACE lines and weighted the same way whole-namespace shards are.

  A namespace cannot straddle two shards. The shard command passes `-n` per
  namespace alongside `-v` per var, and cognitect's var filter resolves a name
  only within a DISCOVERED namespace, so splitting one namespace's vars across
  shards would silently drop tests.

  **Why this exists.** Narrowing and sharding used to be mutually exclusive:
  with `:only` set, `full-set` was nil, `par` fell to 1, and the run was one
  serial JVM. So a narrowed run of 130 tests cost more than the sharded full
  suite, and `done` deferred instead — measured, on 37.5% of recent calls,
  with six of fifteen deferrals in the 52–136 range. The trace map had
  identified those tests correctly; the runner simply could not act on the
  answer. This is the runner catching up to the index."
  [store only n]
  (let [by-ns  (group-by #(symbol (namespace (symbol (str %)))) only)
        shards (balance-shards store (keys by-ns) n)]
    (filterv seq (mapv #(vec (mapcat by-ns %)) shards))))

(defn ^{:export "slopp.verification"} shard-cost
  "The external tier's cost broken into the parts that decide whether NARROWING
  it would help — or nil when there were no shards to measure.

  `:ms` alone says the tier was slow. It cannot say whether that is tests or
  JVM boots, so it cannot answer the only question anyone asks of it: *would
  `{affected true}` save me anything?*

  **The FASTEST shard is the floor.** It ran the least work and still paid a
  whole `clojure -M` boot plus dependency resolution, and no narrowed run goes
  below one of those. So `slowest - fastest` is the most any narrowing can
  ever return, and it is readable without instrumenting the runner at all.

  A single shard reports a ceiling of ZERO rather than a saving: one shard is
  one boot, and there is nothing for narrowing to remove.

  Written because the guess was made and published before the measurement. The
  skill said `{affected true}` was the gear to reach for by default; measured
  on this store, full = 1297 external tests in ~223s and affected = 839 in
  ~229s — no saving, because the cost is four fresh JVMs rather than the tests
  inside them. A number nobody can decompose invites exactly that guess."
  [build-ms shard-ms]
  (when (seq shard-ms)
    (let [sorted  (vec (sort shard-ms))
          floor   (first sorted)
          slowest (peek sorted)
          ceiling (- slowest floor)
          secs    (fn [ms] (format "%.1fs" (/ (double ms) 1000.0)))
          ;; IMBALANCE is a different saving from narrowing, and the two want
          ;; opposite remedies. The tier costs its slowest shard, so a run whose
          ;; shards finish far apart is paying for the spread rather than for
          ;; the work — and running FEWER tests does not address that at all.
          ;; Measured here the day this landed: [43.6s 131.7s 135.8s 217.5s],
          ;; roughly 85s per run lost to the spread, on every full_check and
          ;; every milestone.
          others  (butlast sorted)
          mean    (when (seq others) (/ (double (reduce + others)) (count others)))
          uneven? (boolean (and mean (> slowest (* 1.5 mean))))]
      {:build-ms build-ms
       :shards   (count sorted)
       :shard-ms sorted
       :floor-ms floor
       :slowest-ms slowest
       :narrowing-ceiling-ms ceiling
       :unbalanced? uneven?
       :note (str "this tier costs its SLOWEST shard (" (secs slowest) "), not the sum."
                  " The FASTEST (" (secs floor) ") is one JVM boot plus dependency"
                  " resolution, which EVERY run pays — narrowed or not. So narrowing"
                  (if (zero? ceiling)
                    " cannot return anything here: one shard is one boot."
                    (str " can return at most " (secs ceiling) " of it, and only when"
                         " your changes are local enough to drop whole test"
                         " namespaces. Reach for {affected true} on a LOCAL episode;"
                         " a change to a core namespace is reachable from nearly"
                         " everything and narrows to almost the same set."))
                  (when uneven?
                    (str " The shards are UNBALANCED (" (secs floor) "…"
                         (secs slowest) "): this tier costs its slowest, so part"
                         " of that is the SPREAD rather than the work, and running"
                         " fewer tests does not address it. A PERFECTLY divisible"
                         " split would land near " (secs (long mean)) " — but the"
                         " work is not divisible below one namespace, so if a"
                         " single test namespace costs more than that, this is"
                         " already near its floor and re-balancing cannot help."
                         " Re-weighting the split was tried against this spread"
                         " and measured WORSE, twice."))
                  " Materializing the project cost " (secs build-ms) " on top.")})))

(defn ^{:export "slopp.verification"} run-shard!
  "Shell one test shard: a fresh `clojure -M<alias>` over `grp`'s namespaces
  in the materialized `dir`, bounded by `shard-timeout-ms` via `run-cmd!`.
  The seam the shard-death retry rides.

  With `only` (qualified test vars), the shard runs just those — `-n` per
  namespace AND `-v` per var, because cognitect's var filter resolves a name
  only within a namespace it has discovered. `grp` must still name every
  namespace `only` mentions; `only-shards` builds both halves together."
  ([alias dir grp] (run-shard! alias dir grp nil))
  ([alias dir grp only]
   (run-cmd! (concat [repl/clojure-bin (str "-M" alias)]
                     (mapcat #(vector "-n" (str %)) grp)
                     (mapcat #(vector "-v" (str %)) only))
             dir)))

(defn ^{:export "slopp.verification"} read-traces
  "Merge the form traces this run's shards wrote into the built `dir` (#121):
  {qualified-test-sym #{qualified-form-sym ...}}, or **nil** when none were
  written.

  nil, not {}: 'the external tier traced nothing' and 'the external tier did
  not trace' are different claims, and only the second is true of a store
  whose build carries no trace runner. An empty map would absorb as evidence.

  `merge-with into` because the run is round-robin SHARDED across concurrent
  JVMs in one dir — each shard emits a partial map, and a test seen by two of
  them must union its forms rather than have half of them dropped."
  [dir]
  (let [fs (->> (.listFiles (io/file dir))
                (filter #(str/starts-with? (.getName ^java.io.File %)
                                           testmain/trace-file-prefix)))]
    (when (seq fs)
      (->> fs
           (map #(edn/read-string (slurp %)))
           (apply merge-with into)))))

(defn anchor-output
  "Runner output made boundary-safe: file.clj:LINE coordinates lose the line
  suffix (a bare file name is not a coordinate and passes the response
  audit; agents anchor by name + snippet, and a crash tail's value is the
  MESSAGE, not the line number)."
  [s]
  (str/replace (str s) #"(\.clj[cx]?):\d+(?::\d+)?" "$1"))
