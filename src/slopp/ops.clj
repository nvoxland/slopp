(ns slopp.ops
  "The agent-facing operation surface (the tools an MCP adapter exposes). A
  session is an atom holding the evolving store + the owned image. Everything is
  form-addressed (ns/name), never file+line: the agent *sees* code via
  `query-source` (the VFS) and *edits* only through `edit-replace!` (tracked
  deltas). `query-eval` lets it observe the live image (the oracle) without
  mutating code.

  With `{:dir ...}` the session is durable (C7): the store is write-through to
  SQLite at `<dir>/.slopp/store.db`, and `open!` reconstructs both the store and
  the live image from it. Without `:dir` the session is ephemeral (tests,
  scratch)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.store.render :as store.render]
            [slopp.image.repl :as repl]
            [slopp.image :as image]
            [slopp.edit :as edit]
            [slopp.edit.refactor :as refactor]
            [slopp.index.normalize :as normalize]
            [slopp.store.db :as db] [rewrite-clj.parser :as p] [slopp.read.history :as history] [slopp.project.deps :as project.deps] [slopp.ops.engine :as engine] [slopp.read.modules :as read.modules] [slopp.read.orient :as orient] [slopp.edit.modules :as edit.modules] [slopp.rules :as rules] [slopp.ops.done :as done] [slopp.rules.shape :as shape] [slopp.index.analyze :as analyze] [slopp.edit.lintgate :as lintgate] [slopp.project.capabilities :as capabilities] [clojure.edn :as edn] [slopp.store.fields :as fields] [slopp.index.refs :as refs] [slopp.read.telemetry :as telemetry] [slopp.store.artifacts :as artifacts] [clojure.java.io :as io] [slopp.rules.currency :as rules.currency] [slopp.image.currency :as image.currency] [slopp.kernel.boot :as boot] [slopp.edit.tiers :as tiers] [slopp.edit.gates :as gates] [slopp.rules.catalog :as catalog] [slopp.rules.webapp :as rules.webapp] [slopp.currency :as slopp.currency] [slopp.project.dev :as dev]))

^{:auto-declare "mutual recursion: add-form!, add-require!, auto-require-retry, canonical-source!, create-ns!, edit-group!, edit-replace!, edit-subform!, prune-requires!, remove-require!, revert-form!"}
(declare add-form! add-require! auto-require-retry canonical-source! create-ns! edit-group! edit-replace! edit-subform! prune-requires! remove-require! revert-form!)

(defn reap-idle-images!
  "Stop parked branch images idle past the session TTL (the session's reaper
  timer calls this periodically; callable directly). Returns {:reaped n}."
  [session]
  (let [ttl     (:branch-image-ttl-ms @session 600000)
        now     (System/currentTimeMillis)
        victims (volatile! #{})]
    (swap! session update :lines
           (fn [lines]
             (into {}
                   (map (fn [[nm line]]
                          (if (and (:image line)
                                   (> (- now (:last-used line 0)) ttl))
                            (do (vswap! victims conj (:image line))
                                [nm (dissoc line :image)])
                            [nm line])))
                   lines)))
    (doseq [img @victims] (repl/stop! img))
    {:reaped (count @victims)}))

(defn adopt-modules!
  "ADOPTION (internal — never a tool, never explicit): derive the module
  manifest from the CURRENT actual dependency graph — kondo-resolved, so
  :refer'd calls count — and record it as one delta per edge. Called once by
  open! for a populated store whose db predates the module system (:modules
  nil); the result has zero VIOLATIONS by construction, so adoption never
  breaks working code — the gate then blocks DRIFT until the agent declares
  new edges (module_dep).

  It is NOT acyclic by construction, and `:cycles` reports what it derived.
  A module is the first two segments, so `pb.app` calling `pa.core` while
  `pa.core.impl` calls back into `pb.app` closes a module cycle with no
  namespace cycle anywhere — a codebase Clojure loads happily. Since
  module_dep cycle-checks every add, a store grown under the gate cannot
  tangle, which makes adoption the one moment a knot can enter and the one
  moment anybody is looking at the manifest.

  Judged on PRODUCTION edges through the same `module-layers` the module
  graph reads, so a second derivation cannot drift from what the graph
  shows. `:cycles` is [] when clean, never nil; `:note` rides along only
  when there is something to say.

  An edge only `-test` namespaces cross is adopted as a TEST-ONLY edge, so
  the manifest a project inherits does not open its production graph on a
  fixture's behalf. Adopting those as ordinary edges is how slopp's own
  manifest came to claim `slopp.index` and `slopp.store` depend on
  `slopp.api`."
  [session & {:keys [agent]}]
  (let [{:keys [production test]} (edit.modules/derive-module-edges (:store @session))
        record (fn [s [m deps] test-only]
                 (reduce (fn [s2 dep]
                           (first (store/record-module-edge
                                   s2 m dep :add
                                   :test-only test-only
                                   :prompt (str "module adoption: "
                                                (if test-only
                                                  "edge crossed by -test namespaces ONLY"
                                                  "edge derived from the actual dependency graph"))
                                   :agent agent)))
                         s (sort deps)))]
    (engine/commit-appended!
     session
     (fn [base]
       (as-> (update base :modules #(or % {})) $
         (reduce #(record %1 %2 nil) $ (sort production))
         (reduce #(record %1 %2 true) $ (sort test))))
     [])
    (let [cycles (vec (:cycles (store/module-layers
                                (into {} (map (fn [[m ds]] [m (vec ds)])) production))))]
      (cond-> {:modules (count production)
               :edges   (reduce + 0 (map count (vals production)))
               :test-edges (reduce + 0 (map count (vals test)))
               :cycles cycles}
        (seq cycles)
        (assoc :note
               (str (count cycles)
                    (if (= 1 (count cycles)) " module cycle" " module cycles")
                    " came in with the code: "
                    (str/join "; " (map #(str/join " ⇄ " %) cycles))
                    ". Nothing is broken and nothing loads in a circle — a"
                    " module is the first two segments, so this is a"
                    " cross-module call in each direction. It cannot be"
                    " introduced later, since declaring an edge that closes a"
                    " cycle is refused, so untangling means moving what"
                    " crosses and then module_dep {from … to … remove true}."))))))

(defn await-image!
  "Block until the session's image is live, then return the session. A
  synchronously-opened session (the default) carries no ready-promise and
  returns immediately; an async open's promise is derefed here, and a boot
  FAILURE delivered to it is RETHROWN — with the async-boot server path the
  MCP connection is already up by the time the image loads, so a boot error
  surfaces on the first oracle/write call instead of killing the server at
  startup (which is what let a slow store race the MCP connect timeout).

  A LAZY session (`:boot-image!` on the atom, no image yet) boots here, once,
  under the session lock, at the store's current head — this is the first
  call that needed one. Before it boots, `:image-permit` — a fn the daemon
  lends, answering nil or a refusal — is asked: a machine-wide budget lives
  with the one process that sees every image, and the refusal names the
  fix. A boot failure throws to the caller as a sync open's would; the
  thunk stays, so the next call tries again."
  [session]
  (when (and (nil? (:image @session)) (:boot-image! @session))
    (locking session
      (when (nil? (:image @session))
        (when-let [why (some-> (:image-permit @session) (apply []))]
          (throw (ex-info why {:image-budget true})))
        ((:boot-image! @session)))))
  (when-let [p (:image-ready @session)]
    (let [r (deref p)]
      (when (instance? Throwable r) (throw r))))
  session)

(defn close! "Release everything the session owns and return nil: its image, a warm spare
  still booting, the SQLite connection, every per-branch line's image and
  connection, and the idle-image reaper timer.

  Always call it — an owned image is a JVM subprocess, so a dropped session
  leaks one. `(try … (finally (api/close! sess)))` is the shape every test and
  entry point uses. Safe on a partially-built session: each resource is
  released only if present, and each release is ISOLATED — a throwing close
  (broken transport, a spare whose boot failed) must not leak everything
  after it. The spare deref is bounded: boot itself is bounded by start!'s
  timeout, so the cap only guards a wedged future thread.

  IDEMPOTENT, and that is load-bearing: the session FORGETS each handle as it
  releases it. Before that, a second `close!` on one session found the same
  image still in the atom, reset it again and PARKED IT AGAIN — one process
  in the pool twice, handed to two later tenants, and the second met a closed
  socket the moment the first stopped it. It surfaced as an unrelated test
  dying with `Socket closed`, only when a test that closed in its body and in
  its `finally` had run before it."
  [session]
  (letfn [(safely! [f] (try (f) (catch Throwable _ nil)))]
    ;; PARK, not stop: the image's Clojure runtime is identical to the one the
    ;; next session would spend ~830ms rebuilding. Keyed by the store's OWN
    ;; dependency manifest — that is what put the jars on this image's
    ;; classpath, so it is the honest key, and it lets a real project with
    ;; deps recycle exactly as well as an empty one. park! verifies the image
    ;; back to its boot baseline and STOPS it whenever it cannot, so the worst
    ;; case here is exactly the old behaviour.
    (safely! #(repl/park! (:image @session) (:deps (:store @session))))
    (safely! #(when-let [spare (:spare @session)]
                (repl/stop! (deref spare 65000 nil))))   ; reap even if still booting
    (safely! #(when-let [^java.sql.Connection conn (:db @session)]
                (.close conn)))
    ;; a dirless session's journal was minted for it alone; it goes with it
    (safely! #(when (:ephemeral-dir? @session)
                (letfn [(rm! [^java.io.File f]
                          (when (.isDirectory f) (run! rm! (.listFiles f)))
                          (.delete f))]
                  (rm! (java.io.File. ^String (:dir @session))))))
    (doseq [[_ line] (:lines @session)]
      (safely! #(when-let [img (:image line)] (repl/stop! img)))
      (safely! #(when-let [^java.sql.Connection c (:conn line)]
                  (.close c))))
    (safely! #(when-let [^java.util.Timer t (:reaper @session)] (.cancel t)))
    (swap! session dissoc :image :spare :db :reaper :lines :ephemeral-dir?))
  nil)

(defn sync-with-journal!
  "m5b: absorb commits made by OTHER servers sharing this store dir. Cheap
  when nothing changed (one PRAGMA read). On foreign commits: refresh the
  cached store from the journal, reload every namespace whose source changed
  into the LOCAL image — when this session holds one; a lazy session that
  has not booted its image keeps its CACHE current here all the same, which
  is what lets it read what others land — and drop trace entries touching
  the changed namespaces (conservative — narrowing rebuilds). Returns
  `{:synced n :changed [ns …]}` or nil when already current — `:changed`
  names what moved under this session, which is what the next answer tells
  the agent. The MCP dispatch calls this before every tool, so servers
  converge continuously.

  A session opened on a dir with NO store yet has no connection, and the
  store can appear afterwards — somebody else's first durable write creates
  it, which under the daemon is the ordinary shape: attach, then write.
  Such a session ATTACHES here once the file exists: a connection, its line
  adopted on it, its value reloaded. Without this it stayed blind to
  everything that ever landed, while reporting an empty store as current."
  [session]
  (when (and (nil? (:db @session))
             (:dir @session)
             (not (:ephemeral-dir? @session))
             (.exists (java.io.File. (str (:dir @session)) ".slopp/store.db")))
    (when-let [conn (db/open! (:dir @session) {:create? false})]
      (swap! session assoc :db conn :data-version nil)
      (engine/adopt-line! session)))
  (when-let [conn (:db @session)]
    (let [v (db/data-version conn)]
      (when (not= v (:data-version @session))
        (let [old (:store @session)]
          (engine/refresh-cache! session)
          (swap! session assoc :data-version v)
          (let [new (:store @session)]
            (if (identical? old new)
              {:synced 0 :changed []}
              (let [changed (filterv #(not= (store.render/render-ns old %)
                                            (store.render/render-ns new %))
                                     (store/ns-dependency-order new))
                    stale   (into #{}
                                  (mapcat (fn [n]
                                            (map #(symbol (str n)
                                                          (str (or (:name %) (:id %))))
                                                 (store/forms new n))))
                                  changed)]
                (when-let [image (:image @session)]
                  (doseq [n changed]
                    (image/load-ns! image new n)))
                (swap! session update :test-map
                       (fn [tm]
                         (into {}
                               (remove (fn [[t forms]]
                                         (or (contains? stale t)
                                             (seq (set/intersection forms stale)))))
                               tm)))
                (engine/persist-trace! session)
                {:synced (count changed) :changed changed}))))))))

(defn ingest!
  "The batch write for BRAND-NEW namespaces (W1, user decision): land a whole
  namespace's source in one call. Compile-gated like every write (the image
  loads it FIRST; a failed load commits nothing — T4), then verified and
  recorded like every write. Overwriting an existing namespace is NOT allowed
  — edit its forms instead. Returns {:ns :forms :test} or {:error msg}.

  `:prompt` is the ask that created the namespace; it rides the :ingest
  delta, because a batch-born form's history otherwise answers \"why does
  this exist\" with silence.

  A :cljs (non-jvm-loadable) namespace is ingested the same way, but its code
  references js/* / the DOM and cannot load into the JVM oracle, so ingest
  SKIPS the hot-load and defers verification to the ClojureScript compiler
  (compile_client) — reporting `cljs-deferred-summary`. D-web-cljs."
  [session ns-sym source & {:keys [agent prompt]}]
  (if-let [pre (or (when (get-in (:store @session) [:namespaces ns-sym])
                     (str ns-sym " already exists — edit its forms instead"
                          " (whole-namespace overwrite is not allowed)"))
                   ;; parse-form guards the per-FORM door; this is the other one
                   (edit/control-char-refusal source))]
    {:error pre}
    (try
      (let [base      (:store @session)
            ;; arranged before it is loaded: a whole namespace pasted in any
            ;; order loads in its derived order (definitions before callers),
            ;; and a genuine cycle gets its marked declare here like any write
            candidate (let [c (store/ingest base ns-sym source :agent agent :prompt prompt)]
                        (or (:store (edit/resolve-cold-load c ns-sym :agent agent)) c))
            load?     (store/jvm-loadable? base ns-sym)]
        (if-let [derr (or (edit/dialect-scan candidate ns-sym)
                          ;; bulk imports (clone) land reality first and derive
                          ;; the manifest after — the gate blocks DRIFT, not adoption
                          (when-not (:adopting? @session)
                            (edit.modules/module-scan candidate ns-sym)))]
          ;; same D3/D4 gate the edit path enforces — a host form can only enter
          ;; the store already ^:unsafe, so imported code is never frozen and the
          ;; image is never touched by a rejected namespace.
          {:error derr}
          (let [load!   #(repl/load-checked! (:image @session)
                                             (store.render/render-ns candidate ns-sym)
                                             (store.render/ns-path ns-sym
                                                             (store/platform-for base ns-sym)))
                ;; :cljs code never loads into the JVM oracle — skip the hot-load
                ;; and let the cljs compiler be its gate (below).
                res     (if load? (load!) {})
                ;; the generic red-first seam, ingest face: a spec ns naming
                ;; not-yet-written vars stubs + retries instead of refusing
                ;; stub and retry until the namespace loads or nothing new
                ;; was stubbed: the graph names aliased/qualified/referred
                ;; missing vars at once, the load error names an unqualified
                ;; one at a time
                [res stubbed]
                (if (and load? (:err res))
                  (loop [res res, acc [], n 0]
                    (if (or (nil? (:err res)) (<= 12 n))
                      [res (not-empty acc)]
                      (let [;; both sources every round — the graph source repeats itself, and
                          ;; behind an `or` it starved the load error's symbol
                          s   (into (vec (engine/stub-missing-test-vars! (:image @session) candidate [ns-sym]))
                                    (engine/stub-unresolved-test-symbol! (:image @session) candidate ns-sym (:err res)))
                            new (remove (set acc) s)]
                        (if (seq new)
                          (recur (load!) (into acc new) (inc n))
                          [res (not-empty acc)]))))
                  [res nil])
                res     (if (and stubbed (nil? (:err res))) {} res)]
            (cond
              (:err res)
              ;; through the same door every other write's compile failure
              ;; takes: coordinate stripped, form anchored, alias hint appended
              (edit/compile-error candidate (:err res) "namespace failed to load: " ns-sym)

              (not (engine/try-commit! session base candidate [ns-sym]))
              {:conflict {:reason "store changed during ingest — retry"}}

              :else
              (do
                (when load?
                  (repl/eval! (:image @session)
                              (format "(dosync (commute (deref #'clojure.core/*loaded-libs*) conj '%s))"
                                      ns-sym))
                  ;; ingest loads through `load-checked!` directly rather than
                  ;; `image/load-ns!`, so it is a THIRD door and has to stamp
                  ;; what it put there. Missing this, every ingested namespace
                  ;; read as :never-loaded forever — and nothing derived from
                  ;; it could ever be judged stale, because staleness is only
                  ;; meaningful for a form the image is known to hold.
                  ;; bookkeeping, caught at the CALL — see `hot-load-form!` for why the
                  ;; catch cannot live inside `stamp!`
                  (try (doseq [e (store/elements candidate ns-sym)]
                         (image.currency/stamp! (:image @session) (:id e)
                                                (n/string (:node e))))
                       (catch Throwable t
                         (image.currency/note-failure! (:image @session) t))))
                (let [edited  (into #{}
                                    (keep (fn [e]
                                            (when (:name e)
                                              (symbol (str ns-sym) (str (:name e))))))
                                    (store/forms candidate ns-sym))
                      summary (if load?
                                (engine/run-verification! session ns-sym nil :edited edited)
                                engine/cljs-deferred-summary)
                      recompiled (engine/after-write! session ns-sym)]
                  (engine/commit-appended! session
                                            #(store/record-verification % ns-sym summary)
                                            [])
                  (cond-> {:ns ns-sym
                           :forms (count (store/forms candidate ns-sym))
                           :warnings (vec (edit/ns-warnings candidate ns-sym))
                           :test summary}
                    stubbed (assoc :red-first stubbed
                                   :note (str "these vars don't exist yet — stubbed"
                                              " in-image as failing (red-first);"
                                              " implement them to go green."))
                    recompiled (merge recompiled))))))))
      (catch Exception e
        {:error (str "unparseable source (unbalanced?): " (ex-message e))}))))

(defn flush-reads!
  "Fold whatever the read ring has accumulated onto a `:read-cost` delta and
  clear it. Returns the record, or nil when nothing was written.

  Flushes when the ring has reached `telemetry/read-flush-calls`, or on any
  `force?`. **The POLICY is here and the PERMISSION is at the caller** — the
  wire knows whether writing a delta is legal at this moment and knows nothing
  about spans; this knows the reverse. Splitting it the other way put the
  threshold in the wire, where a second caller would have had to know the
  number too.

  **The read ring is not the timing ring, and the difference is the point.**
  `:slopp.read.telemetry/calls` is cleared at every `turn-begin!` so an ask
  measures only its own clock. The read rows must NOT share that lifecycle:
  turns rotate only when a user prompt arrived and a write tool followed, so a
  read-only ask closes no turn and an event-driven session closes no turn —
  and those are exactly the spans where reads dominate. Measured the day the
  read record shipped riding `:turn-end`: two stores, 321 and 118 closed
  turns, zero read records between them. So the rows accumulate across turns
  and leave only here.

  **Call this only from a path that already writes.** Every read tool declares
  `readOnlyHint` on the wire and a harness may run it unprompted on that
  promise; appending a journal delta from one would break it. That leaves one
  span genuinely unrecordable — a session that never calls a write tool at all
  — and the honest handling is to say so rather than to record it anyway. It
  is named in [[slopp.lab.reads/read-ledger]], where someone reading a number
  needs to know it.

  Nothing to flush → nil and no delta, for the reason the fold itself returns
  nil on an empty ring: an empty record reads as a span that cost nothing."
  [session & {:keys [force?]}]
  (let [ring (:slopp.read.telemetry/reads @session)]
    (when (or force? (<= telemetry/read-flush-calls (count ring)))
      (when-let [reads (telemetry/read-cost ring)]
        (engine/commit-appended! session #(store/record-read-cost % reads) [])
        (swap! session dissoc :slopp.read.telemetry/reads)
        reads))))

(defn turn-end!
  "Close `agent`'s turn (stable or not — a red turn is still history), and
  record where the turn's WALL CLOCK went.

  The wire accumulates one `{:tool :start :end}` per call into the session
  (`:slopp.read.telemetry/calls`); this folds it with
  `telemetry/call-timing` onto the `:turn-end` delta and clears the ring, so
  each ask measures only itself. Nothing called → no `:timing` key, rather
  than a zeroed record that would read as measured. The session's record of
  the open agent (:open-turn) is cleared when it is this agent's, or a
  sub-agent riding it.

  The turn is the right grain because it is the USER-ASK bracket the prompt
  hook already maintains: the question worth answering is \"what did this ask
  cost, and how much of it was slopp\", and until now the second half had no
  producer at all. This call's own time is not in its own total — it is still
  in flight."
  [session & {:keys [agent note]}]
  (let [timing (telemetry/call-timing (:slopp.read.telemetry/calls @session))]
    (engine/commit-appended! session
                      #(first (store/record-turn % :turn-end
                                                 :agent agent :note note
                                                 :timing timing))
                      [])
    (swap! session (fn [s]
                     (let [held (:open-turn s)]
                       (cond-> (dissoc s :slopp.read.telemetry/calls)
                         (and held agent
                              (or (= held agent)
                                  (clojure.string/starts-with? (str held) (str agent "/"))))
                         (dissoc :open-turn)))))
    ;; and the same rollup as an `ask` MEASUREMENT, anchored to the turn-end
    ;; it describes: the delta carries `:timing` for the fold, the row is what
    ;; a per-ask chart reads without folding the log — and, once the deltas
    ;; leave the store value, the only place it is read from at all.
    (when timing
      (when-let [conn (:db @session)]
        (db/record-measurement! conn "ask"
                                (:head (:store @session))
                                timing)))
    (cond-> {:turn :closed :agent agent}
      timing (assoc :timing timing))))

(defn turn-open?
  "Does `agent-label` (or any of its path ancestors — sub-agents ride the
  root agent's turn) have an open :turn-begin?

  The SESSION is asked first: `turn-begin!` records the open agent on it and
  `turn-end!` clears it, and that record survives everything the journal
  view does not. The store's `:recent` window is the fallback — a session
  with no memory of its own (a one-shot process) has only the journal — and
  it is the deltas SINCE THE LAST COMMIT POINT, so on its own a commit point
  taken inside an ask read the ask's turn as closed: no :turn-end, and the
  ask's timing ring discarded at the next begin. Measured on slopp's own
  store, one day: seven commit points, nine asks opened turns, two closed."
  [session agent-label]
  (let [ds    (:recent (:store @session))
        held  (:open-turn @session)
        open? (fn [lbl]
                (or (= lbl held)
                    (let [marks (filter #(and (contains? #{:turn-begin :turn-end}
                                                         (:op %))
                                              (= lbl (:agent %)))
                                        ds)]
                      (= :turn-begin (:op (last marks))))))
        roots (when agent-label
                (let [parts (clojure.string/split agent-label #"/")]
                  (map #(clojure.string/join "/" (take (inc %) parts))
                       (range (count parts)))))]
    (boolean (some open? (or roots [agent-label])))))

(defn turn-begin!
  "Open `agent`'s turn, recording the VERBATIM user ask as the root intent of
  everything until turn-end. A new begin supersedes an unclosed one. The
  intent also stays on the session (:last-intent) — orientation mines it so
  the brief arrives task-shaped — and so does the open agent (:open-turn),
  which is how `turn-open?` still knows the turn is open after a commit
  point inside the ask has scrolled its begin out of the recent window.

  Also resets the wall-clock ring (`:slopp.read.telemetry/calls`) so this ask
  measures only itself. The wire records a call AFTER it returns — otherwise
  `turn_end` would read a half-finished entry for itself — which leaves the
  previous turn's closing bracket in the ring. Clearing here is what keeps one
  ask's cost from bleeding into the next."
  [session & {:keys [agent intent user]}]
  ;; A previous ask that never called turn_end is CLOSED here, before its ring
  ;; is cleared. This is the only moment that has both halves: the knowledge
  ;; that the ask is over (a new one is starting) and the wall-clock ring,
  ;; which lives in THIS process's memory. The Stop hook cannot do it — a
  ;; one-shot `--call turn_end` finds an empty ring and would write a boundary
  ;; with no :timing, balancing the counts while still measuring nothing.
  ;; Measured before this existed: 393 turn-begins against 346 turn-ends, so
  ;; 11% of asks contributed to no cost fold and nothing said so.
  (when (turn-open? session agent)
    (turn-end! session :agent agent :note "superseded by a new ask"))
  (when intent (swap! session assoc :last-intent intent))
  (swap! session dissoc :slopp.read.telemetry/calls)
  (engine/commit-appended! session
                    #(first (store/record-turn % :turn-begin
                                               :agent agent :intent intent
                                               :user user))
                    [])
  (swap! session assoc :open-turn agent)
  {:turn :open :agent agent :intent intent})

^:reads (defn query-observe
  "Run `driver-code` (observe-gated) while capturing the args and return value
  of up to `:limit` calls to `ns-sym/nm` — the oracle's direct answer to 'what
  flows through this function?' (D2: observe, don't declare)."
  [session ns-sym nm driver-code & {:keys [limit] :or {limit 10}}]
  (if-let [err (edit/observe-gate driver-code)]
    {:error err}
    (first (repl/eval! (:image @session)
                       (format "(slopp.kernel.rt/observe '%s/%s (fn [] %s) %d)"
                               ns-sym nm driver-code limit)))))

^:reads (defn query-macroexpand
  "Expand a form (built-in macros are part of the dialect; expansion is how
  the oracle explains them). Returns {:expand-1 str :full str} or {:error}."
  [session code]
  (try
    (let [{:keys [error]} (edit/parse-form code)]
      ;; parse-form also dialect-checks; for expansion we only care that it READS
      (if (and error (re-find #"unparseable" error))
        {:error error}
        {:expand-1 (first (repl/eval! (:image @session)
                                      (format "(pr-str (macroexpand-1 '%s))" code)))
         :full     (first (repl/eval! (:image @session)
                                      (format "(pr-str (macroexpand '%s))" code)))}))
    (catch Exception e {:error (ex-message e)})))

(defn restart!
  "D5 escape hatch: the agent-callable fresh-image restart."
  [session]
  (engine/fresh-image! session)
  session)

(defn delete-form!
  "Delete the form named `nm` from `ns-sym`: `:delete` delta, `ns-unmap` in the
  image, verification, provenance.

  **Refuses while anything still CALLS it** (`edit/live-callers-error`), the
  same stance `delete-ns!` has always taken for a namespace something still
  requires. This docstring used to promise the opposite — that a still-
  referenced delete was fine because \"tests that exercised it will go red, the
  honest signal\". There is no such signal: the namespace fails to RELOAD before
  any test runs, so verification reported zero tests and nothing wrong while
  the store stopped booting entirely (frictions 3f/19).

  An agent's way through is ORDER: delete the callers first and the callee
  last, one call each. The refusal says so and names `query_depends` for the
  list.

  `edit-group!` (`edit_group` on the wire) is deliberately NOT guarded this
  way: its steps apply in order to one store value and
  verify once at the end, so a mid-sequence state holding a dangling reference
  is legitimate. That is what keeps `undo!` / `revert-episode!` /
  `change-signature!` / the rename sweeps working: they replay
  MACHINE-ordered deltas, where insisting on a never-dangling intermediate
  would refuse correct work. A human-ordered sequence has no such warrant,
  which is why the guard stays on the per-form tool.

  Refuses when `nm` addresses TWO elements (a legacy `(declare nm)` beside its
  definition): resolving by position deletes whichever happens to come first,
  which is silent destruction of live code.

  A defmethod needs more than ns-unmap (#131): its name is its form id and its
  registration lives in the MULTI's method table, so ns-unmap is a no-op and
  the deleted method KEPT ANSWERING — tests stayed green after the delete, and
  green-when-red is the direction the staleness diagnostics never cross-check.
  The dispatch value is evaled in the form's own namespace, exactly where
  defmethod evaled it; if that fails the eval is skipped and the stale method
  survives until restart — conservative, and only reachable from a dispatch
  expression that itself no longer evaluates."
  [session ns-sym nm & {:keys [prompt agent]}]
  (or
   (edit/ns-form-delete-error ns-sym nm)
   (edit/ambiguous-form-error (:store @session) ns-sym nm)
   (edit/live-callers-error (:store @session) ns-sym nm)
   (let [victim  (store/form-named (:store @session) ns-sym nm)
         vsexpr  (when victim (try (n/sexpr (:node victim)) (catch Exception _ nil)))
         unregister
         (when (and (seq? vsexpr) (= 'defmethod (first vsexpr)) (> (count vsexpr) 2))
           (format "(when-let [v (ns-resolve '%s '%s)]
                     (when (instance? clojure.lang.MultiFn @v)
                       (remove-method @v
                         (binding [*ns* (find-ns '%s)] (eval '%s)))))"
                   ns-sym (second vsexpr) ns-sym (pr-str (nth vsexpr 2))))
         r (engine/rebased-write!
            session
            (fn [base]
              (if-let [[st' d] (store/remove-form base ns-sym nm
                                                  :prompt prompt :agent agent)]
                {:store st' :delta d}
                (edit/missing-form-error base ns-sym nm)))
            (fn [base] (:node (store/form-named base ns-sym nm)))
            (symbol (str ns-sym) (str nm))
            ns-sym
            :load? false)]
     (if (or (:error r) (:conflict r))
       r
       (let [affected (engine/affected-tests session ns-sym nm)]
         (repl/eval! (:image @session) (format "(ns-unmap '%s '%s)" ns-sym nm))
         (when unregister (repl/eval! (:image @session) unregister))
         (let [summary (engine/run-verification! session ns-sym affected
                                                  :edited #{(symbol (str ns-sym) (str nm))})]
           (engine/commit-appended! session
                                     #(store/record-verification % ns-sym summary)
                                     [])
           {:delta (:delta r) :test summary :affected (or affected :all)}))))))

(defn- apply-group-step
  "Apply one edit-group step to a store VALUE. Returns {:store :delta :hot ...}
  or {:error msg}. `:hot` is the hot-reload action for the commit phase;
  `:post-eval` (when present) is image code the commit phase must run AFTER
  hot-load — a replaced or deleted defmethod's OLD dispatch stays registered
  in the MULTI's method table unless removed (#131, reached through every
  door); `:handle-shift` reports a ^:live-handle constructor changing KEY
  SHAPE so the commit phase can rebuild the image before anything reads the
  stale handle. Actions: :replace, :add, :delete, :subform (:match + :source,
  `:text true` for raw-text matches — a small change INSIDE a big form without
  re-transcribing it), and :require (one require clause into the ns form).
  There is no :move and no :before: a form's place is derived from what it
  references at commit (`edit/resolve-cold-load`), so an arrangement is not
  a step an author can take. Subform/require compute the new source and
  reduce to :replace, so every gate (dialect, Q7 isolation, Q9 teaching
  errors) rides along. :replace and :delete carry the same destructive-write
  guards as the single-form paths — ambiguity, rename-collision, ns-form
  protection — since undo!/revert-episode!/sweeps all ride through here."
  [st gid prompt agent {:keys [action ns name source match text] :as step}]
  (case action
    :replace (let [{:keys [node error]} (edit/parse-form source)
                   iso (when node
                         (edit/isolation-refusal (edit/require-aliases st ns) node))
                   nm' (some-> node store/form-symbol)
                   old (store/form-named st ns name)
                   old-node (:node old)
                   old-s (when old-node
                           (try (n/sexpr old-node) (catch Exception _ nil)))
                   new-s (when node
                           (try (n/sexpr node) (catch Exception _ nil)))
                   post (when (and (seq? old-s) (= 'defmethod (first old-s)) (> (count old-s) 2)
                                   (not (and (seq? new-s) (= 'defmethod (first new-s))
                                             (= (second old-s) (second new-s))
                                             (= (nth old-s 2) (nth new-s 2)))))
                          (format "(when-let [v (ns-resolve '%s '%s)]\n                     (when (instance? clojure.lang.MultiFn @v)\n                       (remove-method @v\n                         (binding [*ns* (find-ns '%s)] (eval '%s)))))"
                                  ns (second old-s) ns (pr-str (nth old-s 2))))
                   hs (when (and old-node node)
                        (edit/live-handle-shape-change old-node node))
                   ambiguous (edit/ambiguous-form-error st ns name)
                   collision (when (and nm' (not= nm' name))
                               (let [hit (store/form-named st ns nm')]
                                 (when (and hit (not= (:id hit) (:id old)))
                                   {:error (str nm' " already exists in " ns
                                                " — a replace may not RENAME "
                                                name " onto an existing form"
                                                " (two definitions would answer"
                                                " to one name). Delete or rename"
                                                " one of them first.")})))]
               (cond
                 ambiguous ambiguous
                 error {:error error}
                 iso   {:error iso}
                 collision collision
                 :else
                 (if-let [[st' d] (store/replace-node st ns name node
                                                      :prompt prompt :group gid
                                                      :agent agent)]
                   (if-let [merr (gates/gate-refusal st' ns (or nm' name))]
                     {:error merr}
                     (cond-> {:store st' :delta d
                              :hot (if (and nm' (not= nm' name))
                                     [:load-unmap (:form-id d) ns name]
                                     [:load (:form-id d)])}
                       post (assoc :post-eval post)
                       hs   (assoc :handle-shift hs)))
                   (edit/missing-form-error st ns name))))
    :add     (let [{:keys [node error]} (edit/parse-form source)
                   nm (some-> node store/form-symbol)
                   iso (when node
                         (edit/isolation-refusal (edit/require-aliases st ns) node))]
               (cond
                 error {:error error}
                 iso   {:error iso}
                 (and nm (= nm ns) (store/form-named st ns nm))
                 ;; the ns form of a namespace that already exists, sent as an
                 ;; add (the blob re-sent right after ns_create — s17 census):
                 ;; it is the replace of that one form
                 (apply-group-step st gid prompt agent
                                   (assoc step :action :replace :name nm))
                 (and nm (store/form-named st ns nm))
                 {:error (str nm " already exists in " ns)}
                 :else
                 (if-let [[st' d] (store/append-form st ns node
                                                     :prompt prompt :group gid
                                                     :agent agent)]
                   (if-let [merr (when nm (gates/gate-refusal st' ns nm))]
                     {:error merr}
                     {:store st' :delta d :hot [:load (:form-id d)]})
                   {:error (str "no namespace " ns " (ingest it first)")})))
    :subform (let [plan (cond
                          (seq (:where step))
                          (refactor/keyed-replace-plan st ns name (:where step) source)
                          text (refactor/text-replace-plan st ns name match source)
                          :else (refactor/subform-replace-plan st ns name match source))]
               (if (:error plan)
                 plan
                 (apply-group-step st gid prompt agent
                                   {:action :replace :ns ns :name name
                                    :source (:new-form-src plan)})))
    :require (if-let [f (store/form-named st ns ns)]
               (let [r (edit/add-require-source (n/string (:node f)) (:require step))]
                 (if (:error r)
                   r
                   (apply-group-step st gid prompt agent
                                     {:action :replace :ns ns :name ns
                                      :source (:src r)})))
               {:error (str "no namespace " ns " (ingest it first)")})
    :delete  (or (edit/ns-form-delete-error ns name)
                 (edit/ambiguous-form-error st ns name)
                 (let [victim (store/form-named st ns name)
                       vs     (when victim
                                (try (n/sexpr (:node victim)) (catch Exception _ nil)))
                       post   (when (and (seq? vs) (= 'defmethod (first vs)) (> (count vs) 2))
                                (format "(when-let [v (ns-resolve '%s '%s)]\n                     (when (instance? clojure.lang.MultiFn @v)\n                       (remove-method @v\n                         (binding [*ns* (find-ns '%s)] (eval '%s)))))"
                                        ns (second vs) ns (pr-str (nth vs 2))))]
                   (if-let [[st' d] (store/remove-form st ns name
                                                       :prompt prompt :group gid
                                                       :agent agent)]
                     (cond-> {:store st' :delta d :hot [:unmap ns name]}
                       post (assoc :post-eval post))
                     (edit/missing-form-error st ns name))))
    (if (and (nil? action) (:source step))
      ;; a step with no action admits exactly one reading: :replace when
      ;; the named form exists, :add when it does not. The s11 canary
      ;; watched a model send {ns name source} steps and a refusal
      ;; fragment ONE write into a six-turn recovery cascade — the same
      ;; self-repair family as auto-require: the server does the
      ;; mechanical work.
      (let [nm (or name
                   (some-> (edit/parse-form source) :node store/form-symbol))]
        (apply-group-step st gid prompt agent
                          (if (and nm (store/form-named st ns nm))
                            (assoc step :action :replace :name nm)
                            (assoc step :action :add))))
      (cond
        (and (nil? action) (:code step))
        {:error (str "step has :code but no :source — a write step's text goes"
                     " in :source (:code is check's argument, over in explore);"
                     " rename the key")}
        (nil? action)
        {:error "step has no :action and no :source — a step is {action?, ns, name, source, …}"}
        :else {:error (str "unknown action: " action)}))))

(defn forms-changed-since
  "Ids of forms touched by deltas after `since-id` (nil = since the beginning
  of the recent window) that still exist in the store.

  Read from `:recent` — everything since the last commit-point plus the done
  that earned it. A `since-id` older than the window (a done from before the
  last commit-point) means everything in the window changed since it, which is
  what the window's cut guarantees: nothing between that done and the
  commit-point is un-judged."
  [store since-id]
  (let [ds   (:recent store)
        tail (if (and since-id (some #(= since-id (:id %)) ds))
               (rest (drop-while #(not= since-id (:id %)) ds))
               ds)]
    (->> tail
         (mapcat (fn [d] (if (:form-id d) [(:form-id d)] (:form-ids d))))
         distinct
         (filter #(store/ns-of-form-id store %)))))

(defn standing-run
  "The STANDING verdict for a test run of `scope` (a namespace symbol, or
  the vector of namespaces a whole-project or narrowed run covered) with
  `only` (the named tests, nil for all), when nothing has happened since it:
  the most recent `op` marker (`:verify` for an in-image run, `:observe` for
  the external tier) of the same scope and selection, provided every delta
  after it is bookkeeping (`fields/bookkeeping-ops`). Returns that marker's
  result with `:standing true` and `:recorded <delta id>`, or nil.

  Measured on this store: 26% of slopp's own wall time was a tool repeated
  inside ONE ask — `test_run` 510 extra runs, `done` 362, `full_check` 121
  — each re-answering a question nothing had changed. `full_check` and
  `done` already answer from their standing verdict; this is the same
  courtesy for a test run. Only a run `test_run` made itself counts (its
  result carries `:test-run true`): the verify a WRITE records covers the
  tests the write reached, which is a narrower question than the one being
  repeated. `:fresh true` runs anyway."
  [st op scope only]
  (let [back  (reverse (:recent st))
        same? (fn [d]
                (and (= op (:op d))
                     (:test-run (:result d))
                     (= scope (case op :verify (:ns d) :observe (:scope d) nil))
                     (= only (:only (:result d)))))
        tail  (take-while (complement same?) back)
        prior (first (filter same? back))]
    (when (and prior
               (every? #(contains? fields/bookkeeping-ops (:op %)) tail))
      (assoc (dissoc (:result prior) :test-run)
             :standing true
             :recorded (:id prior)
             :note (str "nothing has landed since this run (" (:id prior)
                        ") — its verdict stands and no second run was made."
                        " test_run {fresh true} runs it anyway.")))))

(defn test-run!
  "Traced, diagnosed run of `ns-sym`'s tests (all, or just those in `:only`
  — plain names within `ns-sym`, or ns-qualified names which auto-scope);
  refreshes the test→form map and records the result (C4). `ns-sym` nil =
  the WHOLE project in one image eval, instrumentation paid once (F-3c1 —
  per-ns sweeps were 12 calls and 12 instrumentation passes). D5.1: reds
  are judged against the forms changed since the last verification;
  `:fresh true` restarts first for a guaranteed-faithful single run.

  Repeated with nothing landed since — same scope, same selection — it
  answers from the run it already made (`standing-run`): `:standing true`
  and the recorded verdict, no image eval, no second `:verify`. `:fresh`
  always runs."
  [session ns-sym & {:keys [only fresh]}]
  (let [t0          (System/nanoTime)
        st          (:store @session)
        only        (seq only)
        qual        (filter #(str/includes? (str %) "/") only)
        ns-sym      (or ns-sym
                        (when (seq qual)
                          (vec (sort (distinct (map #(symbol (namespace (symbol (str %))))
                                                    qual)))))
                        (vec (sort (keys (:namespaces st)))))
        only'       (seq (map #(let [s (str %)]
                                 (if (str/includes? s "/")
                                   (symbol (name (symbol s)))
                                   %))
                              only))
        selection   (when only' (vec only'))]
    (or (when-not fresh
          (some-> (standing-run st :verify ns-sym selection)
                  (engine/with-ms t0)))
        (let [last-verify (:id (db/last-marker (:db @session) (engine/session-line session) :verify))
              edited      (into #{}
                                (keep (fn [id]
                                        (when-let [e (store/form-by-id st id)]
                                          (symbol (str (store/ns-of-form-id st id))
                                                  (str (or (:name e) (:id e)))))))
                                (forms-changed-since st last-verify))
              summary     (cond-> (engine/diagnosed-run! session ns-sym only'
                                                         :edited edited :fresh fresh
                                                         :include-integration? true)  ; M5: explicit run
                            ;; what this run WAS, so a repeat can find it
                            true      (assoc :test-run true)
                            selection (assoc :only selection))]
          (engine/commit-appended! session
                                   #(store/record-verification % ns-sym summary) [])
          ;; the marker is for the RECORD (a repeat finds it there); the caller
          ;; sees the run
          (engine/with-ms (cond-> (dissoc summary :test-run)
                            (and only' (zero? (:test summary 0)))
                            (assoc :note (str "0 tests matched :only " (vec only)
                                              " — check the names (a named ^:external test"
                                              " routes to the external tier automatically)")))
                          t0)))))

(defn- require-orphaned-registrar?
  "After a require to `lib` has been dropped, would a COLD LOAD lose a
   registration? True when `lib` is an in-store namespace whose require-closure
   registers something (defmethod / extend-* / deftype / defrecord …) AND
   nothing else in the store still requires it — so dropping this require
   orphans it and its registrations never run again. The in-image suite cannot
   see this break: the registration is already loaded in the live image, so a
   green in-image verdict is not enough to prove the require dead."
  [st lib]
  (boolean
   (and (contains? (:namespaces st) lib)
        (not (some (fn [nsx] (contains? (set (store/ns-requires st nsx)) lib))
                   (keys (:namespaces st))))
        (some store/method-carrying?
              (mapcat #(store/forms st %) (store/ns-closure st lib))))))

(defn fix-declares!
  "Declare hygiene at the done-point. The write pipeline OWNS form ordering, so
  this no longer reorders anything itself (it used to carry a second,
  conservative single-form mover that gave up on cases the topological sort
  handles). It DROPS `ns-sym`'s declares and lets `edit/resolve-cold-load`
  re-establish what is genuinely needed — a topological reorder (Kahn over THE
  reference graph), or the pipeline's own MARKED auto-declare for a real cycle.
  Net effect: a satisfied declare vanishes, PHANTOM names (a var an earlier
  move lifted out — they mint unbound vars) vanish with it, a legacy
  hand-written declare MIGRATES to a pipeline-owned marked one that says why,
  and a stale auto-declare disappears once its cycle breaks. No-ops when the
  rendered namespace would be unchanged. One atomic group, verified."
  [session ns-sym & {:keys [prompt agent]}]
  (let [st    (:store @session)
        decls (filter (fn [f]
                        (and (nil? (:name f))
                             (= 'declare (try (first (n/sexpr (:node f)))
                                              (catch Exception _ nil)))))
                      (store/forms st ns-sym))]
    (if (empty? decls)
      {:removed 0 :note "no declares"}
      (let [[gid st0] (store/alloc-id st "g")
            stripped  (reduce (fn [s d]
                                (or (first (store/remove-form s ns-sym (:id d)
                                                              :prompt (or prompt "fix-declares")
                                                              :group gid :agent agent))
                                    s))
                              st0 decls)
            rz  (edit/resolve-cold-load stripped ns-sym
                                        :prompt (or prompt "fix-declares: pipeline owns ordering")
                                        :agent agent)
            st' (or (:store rz) stripped)]
        (cond
          ;; the pipeline could not make it load without the declares — leave
          ;; the namespace exactly as it was
          (edit/cold-load-errors st' [ns-sym])
          {:removed 0 :note "declares still required — left as-is"}

          ;; nothing would actually change: don't churn the journal
          (= (store.render/render-ns st ns-sym) (store.render/render-ns st' ns-sym))
          {:removed 0 :note "already tidy"}

          :else
          (if-not (engine/try-commit! session st st' [ns-sym])
            {:conflict {:reason "store changed during fix-declares — retry"}}
            (let [summary (engine/run-verification! session ns-sym nil)]
              (engine/commit-appended! session
                                        #(store/record-verification % ns-sym summary) [])
              {:removed (count decls) :test summary})))))))

(defn cleanup!
  "Run the done-point's TIDY over one namespace, on demand: normalize every
  form (conservative, behavior-preserving rewrites), then declare hygiene via
  `fix-declares!` — definitions reordered above their callers, a legacy or
  stale `(declare …)` retired, phantom names pruned. One verified pass.

  You should rarely need this. The write pipeline owns ordering and declares
  from the FIRST write, and `done` runs the same tidy over everything you
  touched — so code written through slopp arrives clean. Reach for it on code
  that predates those invariants (an ingested file-based namespace), or when a
  legacy declare is blocking you mid-episode: two elements then share a name,
  which the name-addressed edit tools cannot resolve.

  Returns `{:ns :normalized n :rewrites [{:form :applied}] :declares n}`, or
  `{:error …}` — nothing is committed unless the tidied namespace compiles."
  [session ns-sym & {:keys [prompt agent]}]
  (let [st       (:store @session)
        rewrites (vec (for [f (store/forms st ns-sym)
                            :let [{:keys [node applied]}
                                  (normalize/normalize-form (:node f))]
                            :when (seq applied)]
                        {:form-id (:id f)
                         :form    (symbol (str ns-sym) (str (or (:name f) (:id f))))
                         :node    node
                         :applied applied}))
        normed   (when (seq rewrites)
                   (let [changeset (into {} (map (juxt :form-id :node)) rewrites)
                         [st' _]   (store/apply-changeset
                                    st :normalize ns-sym changeset
                                    :prompt (or prompt "cleanup: normalize")
                                    :agent agent)]
                     (if-let [err (:err (engine/hot-load-all! session st'
                                                               (keys changeset)))]
                       {:error (str "cleanup: normalization would not compile — "
                                    err)}
                       (if-not (engine/try-commit! session st st' [ns-sym])
                         {:conflict {:reason "store changed during cleanup — retry"}}
                         {:ok true}))))]
    (cond
      (:error normed)    normed
      (:conflict normed) normed
      :else
      (let [d (fix-declares! session ns-sym
                             :prompt (or prompt "cleanup: declare hygiene")
                             :agent agent)]
        (cond-> {:ns         ns-sym
                 :normalized (count rewrites)
                 :rewrites   (mapv #(select-keys % [:form :applied]) rewrites)
                 :declares   (:removed d 0)
                 :purity     (tiers/tier-report (:store @session) ns-sym)
                 ;; the done-time advisories, re-run over the WHOLE namespace.
                 ;; They already fired for anything written through slopp since
                 ;; the rule existed — what they have never seen is code that
                 ;; PREDATES the rule (ingested, or written before the advisory
                 ;; was added). That is exactly this tool's job.
                 :advisories (let [st* (:store @session)]
                               (rules/run-done-advisories!
                                session st* (mapv :id (store/forms st* ns-sym))))
                 ;; the rest of the enforcement surface, replayed over EXISTING
                 ;; code: kondo lint, dead public surface, undocumented public
                 ;; surface, and the per-form WRITE gates (module / tier /
                 ;; schema / namespaced-keys). Each normally fires only as code
                 ;; is written, so a form predating a rule was never subject to
                 ;; it. Reported, never auto-applied — every one needs judgment.
                 :lint       (let [st* (:store @session)]
                               (vec (done/anchored-lint
                                     session (mapv :id (store/forms st* ns-sym)))))
                 :unused     (vec (:unused (read.modules/unused-report
                                            (:store @session) [ns-sym])))
                 :undocumented
                 (let [st* (:store @session)]
                   (vec (keep #(:var (edit.modules/missing-doc-warning st* ns-sym (:name %)))
                              (filter :name (store/forms st* ns-sym)))))
                 :gates
                 (let [st* (:store @session)]
                   (vec (for [f (store/forms st* ns-sym)
                              :when (:name f)
                              :let [g (gates/gate-check st* ns-sym (:name f))
                                    hits (remove nil? (cons (:refuse g) (:advisories g)))]
                              :when (seq hits)]
                          {:form (symbol (str ns-sym) (str (:name f)))
                           :teach (vec hits)})))}
          (:conflict d) (assoc :conflict (:conflict d))
          (:test d)     (assoc :test (:test d)))))))

(defn cleanup-all!
  "Run `cleanup!` over EVERY namespace in the store — the MIGRATION surface.

  Per-namespace is the wrong grain for a migration, because you do not know
  which namespaces predate a rule. Two cases need this: adopting slopp on an
  existing codebase (nothing in it was ever subject to any gate), and landing
  a slopp upgrade that ADDS a rule (every existing form predates it).

  Applies the tidy everywhere it is needed, and aggregates what tidying cannot
  fix. Returns `{:namespaces n :normalized n :declares n :findings [{:ns …}]}`
  — `:findings` carries only namespaces with something to report, each with
  whichever of `:lint :unused :undocumented :gates :advisories` fired, so a
  clean store returns an empty vector rather than 100 empty rows.

  Reports; it never auto-fixes a finding. Dead surface, a missing docstring, a
  gate violation and an ambient atom each need a human decision — and the last
  is often correct as written."
  [session & {:keys [prompt agent]}]
  (let [nses (sort (keys (:namespaces (:store @session))))
        rs   (mapv #(cleanup! session %
                              :prompt (or prompt "cleanup-all: migration sweep")
                              :agent agent)
                   nses)]
    (if-let [bad (first (filter :error rs))]
      bad
      {:namespaces (count rs)
       :normalized (reduce + 0 (map #(:normalized % 0) rs))
       :declares   (reduce + 0 (map #(:declares % 0) rs))
       :findings
       (vec (keep (fn [r]
                    (let [hit (cond-> {}
                                (seq (:lint r))         (assoc :lint (:lint r))
                                (seq (:unused r))       (assoc :unused (:unused r))
                                (seq (:undocumented r)) (assoc :undocumented (:undocumented r))
                                (seq (:gates r))        (assoc :gates (:gates r))
                                (seq (:advisories r))   (assoc :advisories (:advisories r)))]
                      (when (seq hit) (assoc hit :ns (:ns r)))))
                  rs))})))

(defn deps-remove!
  "Drop external dependency `lib` from the manifest. A jar can't be unloaded,
  so this always restarts the image. Returns {:removed lib :restarted true}
  or {:error}."
  [session lib & {:keys [agent prompt]}]
  (if-not (contains? (:deps (:store @session)) lib)
    {:error (str lib " is not a declared dependency")}
    (do
      (engine/commit-appended! session
                        #(first (store/record-deps-remove % lib
                                                          :agent agent :prompt prompt))
                        [])
      (engine/fresh-image! session)
      {:removed lib :restarted true})))

(defn deps-list
  "The store's external dependency manifest: {lib coord}."
  [session]
  (:deps (:store @session)))

(defn deps-manifest
  "The dependency manifest as an AGENT should read it: `{:deps {lib coord}}`,
  plus `:host-override` naming any declaration slopp's own server process
  cannot honor because it bundles that library itself.

  Separate from `deps-list` on purpose. `deps-list` is the accessor — the
  store's data, which other code and tests build on, and the host's classpath
  is not part of it. This is the SURFACE, and the surface carries an
  obligation the accessor does not: `deps_list` is the one answer an inherited
  store ever gives about its dependencies, so a declaration that is inert in
  the running server has to be visible here or nowhere.

  It briefly also carried `:framework-drift`, for a store pinning
  `io.github.nvoxland/slopp-web` at a version other than the slopp serving it.
  That finding is retired with the coord it described: slopp supplies the
  framework itself now (D-framework-injection), no store declares it, and
  slopp-web was never published so none ever can. A report for a state that
  cannot occur is worse than no report — it teaches a shape of problem that
  does not exist."
  [session]
  (let [deps (deps-list session)
        over (boot/host-lib-divergence deps (boot/bundled-libs))]
    (cond-> {:deps deps}
      (seq over)
      (assoc :host-override over
             :host-override-note
             (str "slopp's own server process bundles these at the :in-force"
                  " version and cannot displace them. Your declarations still"
                  " govern the oracle image, the test suite and anything"
                  " build! produces")))))

(defn- record-pure!
  "Mark each of `syms` pure/un-pure in ONE appended commit (N :deps-pure deltas)."
  [session syms pure? {:keys [agent prompt]}]
  (engine/commit-appended! session
                    (fn [base]
                      (reduce (fn [s x]
                                (first (store/record-deps-pure s x pure?
                                                               :agent agent :prompt prompt)))
                              base syms))
                    []))

(defn- adopt-published-tiers!
  "Adopt the purity tiers the libraries on the image's classpath PUBLISHED,
  returning the namespaces newly marked pure (a vector, possibly empty).

  A tier is a declaration in the producer's store and nothing in the code, so
  before this a consumer saw every namespace of a published library as
  undeclared — hence `:external` — and its own correct functions were flagged
  effectful for calling them. Worse than the warning was its SUGGESTION:
  rename `picker` to `picker!`, which would have mislabelled four correct pure
  functions to compensate for a declaration that never shipped.

  Only `:pure` is adopted. `:external` is already the default, and `:internal`
  is a statement about in-process state that means nothing across a jar
  boundary — the consumer cannot reset a dependency's caches.

  Adopting the producer's word is better founded than the alternative the
  consumer has otherwise: `deps_pure` asks them to assert purity about code
  they did not write and cannot check, while the producer's tier was VERIFIED
  against the forms when it was declared. Reported as `:adopted-pure` either
  way — a silent change to what the effect gate flags is the kind of thing
  someone should be able to see happen.

  Read through the IMAGE, which is where the new jar actually landed: the
  server never added it to its own classpath."
  [session {:keys [agent]}]
  (let [code  (str "(mapv slurp (enumeration-seq (.getResources"
                   " (clojure.lang.RT/baseLoader) \""
                   read.modules/tiers-resource-path "\")))")
        res   (try (first (repl/eval! (:image @session) code))
                   (catch Throwable _ nil))
        tiers (reduce (fn [acc s]
                        (if (string? s)
                          (merge acc (try (edn/read-string s)
                                          (catch Throwable _ nil)))
                          acc))
                      {}
                      (when (coll? res) res))
        known (:dep-pure (:store @session))
        fresh (vec (sort (distinct (for [[path tier] tiers
                                         :when (= :pure tier)
                                         :let  [nsx (symbol (str path))]
                                         :when (not (contains? known nsx))]
                                     nsx))))]
    (when (seq fresh)
      (record-pure! session fresh true
                    {:agent  agent
                     :prompt (str "adopted from a dependency's published purity"
                                  " tiers (" read.modules/tiers-resource-path ")")}))
    fresh))

(defn- shadowed-dep-namespaces!
  "Of `nses`, those provided by MORE than one place on the image's classpath —
  `{ns [url …]}`, or nil. The declared coord did not win those.

  `add-libs` appends to a `DynamicClassLoader`, which delegates to its PARENT
  first, and everything the host jar carries lives in that parent. So a
  dependency can resolve perfectly and still not govern: measured on slopp's
  own store, the manifest declares metosin/malli 0.16.4 while
  `malli/core.cljc` resolves out of slopp.jar both before AND after a
  successful add of exactly that coord.

  Fixing that is a packaging change — shading, a slim launcher, or a
  child-first loader — and none of those belong in `deps_add`. Saying it does:
  a manifest that reads as satisfied while a different version is in force is
  precisely what D-surface-honesty forbids, and it is invisible from every
  surface a consumer has. The FIRST url is the one in force."
  [session nses]
  (when (seq nses)
    (let [code (str "(into {} (for [n '" (pr-str (vec nses))
                    " :let [b (clojure.string/replace"
                    " (clojure.string/replace (str n) \".\" \"/\") \"-\" \"_\")"
                    " us (mapcat #(enumeration-seq"
                    " (.getResources (clojure.lang.RT/baseLoader) (str b %)))"
                    " [\".clj\" \".cljc\"])]"
                    " :when (> (count us) 1)] [n (mapv str us)]))")
          res  (try (first (repl/eval! (:image @session) code))
                    (catch Throwable _ nil))]
      (when (and (map? res) (seq res)) res))))

(defn deps-add!
  "Declare external dependency `lib` (a symbol like `org.clojure/data.json`)
  at `coord` (a deps.edn coordinate map, e.g. `{:mvn/version \"2.5.0\"}`).
  Records a `:deps-add` delta (materialized to the store's manifest), then
  HOT-adds the jar to the running image via add-libs — no restart; on failure
  it restarts. Returns {:added lib :coord :hot true|:restarted true} | {:error}.

  A coord carrying `:exclusions` RESTARTS rather than hot-adds. `add-libs`
  silently ignores exclusions and a fresh JVM honors them, so hot-adding leaves
  the oracle running a classpath no fresh JVM can reproduce: the in-image suite
  goes green with the excluded jar present while every external shard, `build!`
  and native fail to load. An image that can run what a fresh JVM cannot LOAD is
  the cold-load failure class, and the cheapest place to not have it is here.

  `:host-override` names a library slopp's OWN process bundles at a different
  version. It is not a warning about this store — the declaration governs the
  oracle, the test suite and every built artifact — but the server process
  cannot honor it, because a jar its parent classloader already holds cannot be
  displaced. Saying so is the whole point: the alternative is two processes
  quietly running different code with every surface reporting agreement.

  With `:client true` the dep is BUILD-ONLY (the ClojureScript compiler): it
  records to the separate `:client-deps` manifest, is NOT analyzed and NOT
  hot-loaded, and routes to the `:cljs` alias in the generated deps.edn — so it
  never enters the running oracle nor ships in the jar (D-web-cljs)."
  [session lib coord & {:keys [agent prompt client]}]
  (cond
    (not (symbol? lib))
    {:error "dependency lib must be a symbol like org.clojure/data.json"}
    (not (and (map? coord) (seq coord)))
    {:error "dependency coord must be a non-empty map like {:mvn/version \"1.2.3\"}"}

    :else
    (let [coord (fields/canonical-coord coord)]      ; JSON has no symbol type
      (if client
        (do (engine/commit-appended! session
                                      #(first (store/record-client-dep
                                               % lib coord :agent agent :prompt prompt))
                                      [])
            {:added lib :coord coord :client true})
        (let [surf (project.deps/analyze-dep! session lib coord)]             ; M4: API surface
          (engine/commit-appended! session
                                    #(first (store/record-deps-add
                                             % lib coord :agent agent :prompt prompt
                                             :namespaces (:namespaces surf)))  ; M3: dep-ns index
                                    [])
          (let [base (cond-> {:added lib :coord coord}
                       surf (assoc :namespaces (vec (:namespaces surf))
                                   :vars (count (:vars surf))))
                res  (if (seq (:exclusions coord))
                       (do (engine/fresh-image! session)
                           (assoc base :restarted true
                                  :note (str "restarted rather than hot-added: add-libs ignores"
                                             " :exclusions but a fresh JVM honors them, so the"
                                             " image would have run a classpath no build or"
                                             " external shard could reproduce")))
                       (if-let [hot (repl/add-libs! (:image @session) {lib coord})]
                         (do (engine/fresh-image! session) ; hot add failed → faithful restart
                             (assoc base :restarted true :note (:err hot)))
                         (assoc base :hot true)))
                ;; friction 2: only now is the jar on the image's classpath,
                ;; so only now can its published tiers be read
                adopted (adopt-published-tiers! session {:agent agent})
                ;; friction 15a: it RESOLVED — but did it win? Anything the host
                ;; jar already provides sits earlier on the classpath.
                shadowed (shadowed-dep-namespaces! session (:namespaces surf))
                overridden (boot/host-lib-divergence {lib coord} (boot/bundled-libs))]
            (cond-> res
              (seq adopted) (assoc :adopted-pure adopted)
              (seq overridden)
              (assoc :host-override overridden
                     :host-override-note
                     (str "slopp's own server process bundles this library at"
                          " the :in-force version and cannot displace it — a jar"
                          " the parent classloader already holds stays. Your"
                          " declaration still governs the oracle image, the test"
                          " suite and anything build! produces, so the two can"
                          " run different code; pin to the bundled version if"
                          " that matters here"))
              (seq shadowed)
              (assoc :shadowed shadowed
                     :shadowed-note
                     (str "these namespaces are provided by something EARLIER"
                          " on the classpath, so the version you declared is"
                          " NOT the one in force — the first url listed is."
                          " add-libs appends to a classloader that delegates"
                          " to its parent first, and the host jar is that"
                          " parent")))))))))

(defn deps-pure!
  "Assert a dependency is PURE — narrowing M3's effectful-by-default boundary so
  callers aren't flagged effectful. `target` lands at THREE granularities: a
  fully-qualified var (`clojure.data.json/write-str`), a whole NAMESPACE
  (`clojure.data.json`, every var in it), or a manifest LIB
  (`org.clojure/data.json`, which expands to every namespace the dep provides —
  the ergonomic default for a wholesale-pure library like rewrite-clj). Returns
  {:pure sym}, or {:lib sym :namespaces [...]} for a lib."
  [session target & {:keys [agent prompt]}]
  (let [st   (:store @session)
        lib? (contains? (:deps st) target)
        nses (when lib? (vec (get (:dep-ns st) target)))]
    (record-pure! session (or nses [target]) true {:agent agent :prompt prompt})
    (if lib? {:lib target :namespaces nses} {:pure target})))

(defn deps-unpure!
  "Undo `deps-pure!` for `target` (a var, namespace, or manifest lib — the same
  granularities as `deps-pure!`). Calls into it are effectful again."
  [session target & {:keys [agent prompt]}]
  (let [st   (:store @session)
        lib? (contains? (:deps st) target)
        nses (when lib? (vec (get (:dep-ns st) target)))]
    (record-pure! session (or nses [target]) false {:agent agent :prompt prompt})
    (if lib? {:lib target :namespaces nses} {:unpure target})))

(defn rename!
  "Rename `ns-sym/old-name` to `new-name` everywhere: ONE coordinated delta over
  the def + every reference across all namespaces (position-based via the index,
  so shadowed locals are untouched — see slopp.refactor). Hot-reloads every
  rewritten form, drops the old var (`ns-unmap`), re-verifies the affected
  tests, and records the outcome. Returns {:delta :renamed :test :affected} or
  {:error msg}."
  [session ns-sym old-name new-name & {:keys [prompt agent]}]
  (let [st   (:store @session)
        qold (symbol (str ns-sym) (str old-name))
        qnew (symbol (str ns-sym) (str new-name))]
    (cond
      (and (nil? (store/form-named st ns-sym old-name))
           (store/form-named st ns-sym new-name))
      ;; already renamed (a retried/duplicated intent) — state, not refusal
      {:renamed {:old old-name :new new-name :forms 0 :already true}}

      (nil? (store/form-named st ns-sym old-name))
      (edit/missing-form-error st ns-sym old-name)

      (store/form-named st ns-sym new-name)
      {:error (str new-name " already exists in " ns-sym)}

      :else
      (let [code-cs      (refactor/rename-changeset st ns-sym old-name new-name)
            ;; QUALIFIED references in prose follow mechanically — `a.b/c` in a
            ;; docstring can only mean that var. BARE mentions stay a :mentions
            ;; hint below, since `fee`/`zone` are usually domain words too.
            changeset    (merge code-cs
                                (refactor/qualified-mention-changeset
                                 st {qold qnew} code-cs))
            [st' delta]  (store/apply-changeset st :rename ns-sym changeset
                                                :prompt prompt :agent agent
                                                :extra {:old old-name :new new-name})
            touched-nses (distinct (map #(store/ns-of-form-id st' %) (keys changeset)))
            ;; affected tests, judged against the PRE-rename trace map
            changed-syms (into #{qold qnew}
                               (keep (fn [id]
                                       (let [e (store/form-by-id st' id)]
                                         (when (:name e)
                                           (symbol (str (store/ns-of-form-id st' id))
                                                   (str (:name e)))))))
                               (keys changeset))
            tmap         (:test-map @session)
            affected     (when (seq tmap)
                           (let [hits (->> tmap
                                           (keep (fn [[t forms]]
                                                   (when (or (contains? changed-syms t)
                                                             (seq (set/intersection forms changed-syms)))
                                                     (if (= t qold) qnew t))))
                                           sort vec)]
                             (when (seq hits) hits)))
            ;; X2: the renamed DEFINITION must reload before its callers —
            ;; hash-map key order destroyed cross-ns renames at scale
            def-id       (:id (store/form-named st' ns-sym new-name))
            ordered-ids  (into [def-id] (remove #{def-id} (keys changeset)))]
        (if-let [err (:err (engine/hot-load-all! session st' ordered-ids))]
          (edit/compile-error st' err "rename failed to compile: ")
          (if-not (engine/try-commit! session st st' (vec touched-nses))
            (do (engine/fresh-image! session)
              {:conflict {:reason "store changed during rename — retry"}})
            (do
              (swap! session update :test-map engine/rename-in-trace qold qnew)
              (engine/persist-trace! session)
              (repl/eval! (:image @session)
                          (format "(ns-unmap '%s '%s)" ns-sym old-name))
              (let [summary (engine/run-verification! session ns-sym affected
                                               :edited changed-syms)]
                (engine/commit-appended! session
                                  #(store/record-verification % ns-sym summary)
                                  [])
                (let [pat      (refactor/symbol-mention-re old-name)
                      mentions (->> (for [nsx (sort (keys (:namespaces st')))
                                          e   (store/forms st' nsx)
                                          :when (some #(re-find pat %)
                                                      (str/split-lines (n/string (:node e))))]
                                      {:ns nsx :form (or (:name e) (:id e))})
                                    (take 10) vec)]
                  (cond-> {:delta    delta
                           :renamed  {:old qold :new qnew :forms (count changeset)}
                           :test     summary
                           :affected (or affected :all)}
                    (seq mentions)
                    (assoc :mentions mentions
                           :hint (str "prose/string mentions of " old-name
                                      " remain in these forms — edit_subform"
                                      " {text: true} rewrites them if the docs"
                                      " should follow"))))))))))))

(defn extract!
  "Phase-3 structural op: extract a UNIQUE subform of `from` into a new fn
  `new-name` — params are the free locals in first-use order (computed from
  the index's local analysis), the new fn is appended and the derived order places it
  before `from` (compile order), and the subform becomes the call. One atomic
  intent: two grouped deltas (add, replace), compile-checked before commit,
  verified once."
  [session ns-sym from new-name subform-src & {:keys [prompt at]}]
  (let [st   (:store @session)
        plan (refactor/extract-plan st ns-sym from subform-src new-name :at at)]
    (cond
      (:error plan) plan

      (store/form-named st ns-sym new-name)
      {:error (str new-name " already exists in " ns-sym)}

      :else
      (let [pd (edit/parse-form (:new-defn-src plan))
            pf (edit/parse-form (:new-from-src plan))]
        (cond
          (:error pd) pd
          (:error pf) pf
          :else
          (let [[gid st0] (store/alloc-id st "g")
                [st1 d1]  (store/append-form st0 ns-sym (:node pd)
                                             :prompt prompt :group gid)
                [st3 d3]  (store/replace-node st1 ns-sym from (:node pf)
                                              :prompt prompt :group gid)
                ;; no move: `from` now calls the new fn, and arranging the
                ;; namespace puts the definition before its caller
                st3       (or (:store (edit/resolve-cold-load st3 ns-sym)) st3)]
            (if-let [err (:err (engine/hot-load-all! session st3
                                              [(:form-id d1) (:form-id d3)]))]
              (edit/compile-error st3 err "extract failed to compile: ")
              (if-not (engine/try-commit! session st st3 [ns-sym])
                {:conflict {:reason "store changed during extract — retry"}}
                (let [affected (engine/affected-tests session ns-sym from)
                          summary  (engine/run-verification! session ns-sym affected
                                                      :edited
                                                      #{(symbol (str ns-sym) (str from))
                                                        (symbol (str ns-sym) (str new-name))})]
                      (engine/commit-appended! session
                                        #(store/record-verification % ns-sym summary)
                                        [])
                      {:extracted {:new    (symbol (str ns-sym) (str new-name))
                                   :params (:params plan)}
                       :group    gid
                       :test     summary
                       :affected (or affected :all)})))))))))

(defn- add-require-node
  "Candidate-store helper: add require `spec-str` to `nsx`'s ns form,
  returning the updated store (unchanged when the spec can't land — the
  compile gate downstream reports honestly)."
  [st nsx spec-str & {:keys [prompt group agent]}]
  (let [decl (store/form-named st nsx nsx)
        r    (when decl (edit/add-require-source (n/string (:node decl)) spec-str))]
    (if (or (nil? r) (:error r))
      st
      (or (first (store/replace-node st nsx nsx
                                     (first (n/children (p/parse-string-all (:src r))))
                                     :prompt prompt :group group :agent agent))
          st))))

(defn- remove-require-node
  "Candidate-store helper, symmetric with `add-require-node`: drop `lib`'s
  require spec from `nsx`'s ns form, returning the updated store (unchanged
  when the spec can't be dropped — the compile gate downstream reports
  honestly)."
  [st nsx lib & {:keys [prompt group agent]}]
  (let [decl (store/form-named st nsx nsx)
        r    (when decl (edit/remove-require-source (n/string (:node decl)) lib))]
    (if (or (nil? r) (:error r))
      st
      (or (first (store/replace-node st nsx nsx
                                     (first (n/children (p/parse-string-all (:src r))))
                                     :prompt prompt :group group :agent agent))
          st))))

(defn move-forms!
  "Move `form-names` from `from-ns` into `to-ns` — NEW or EXISTING — the
  general relocation refactor (clj-surgeon's :extract!, slopp-grade, v2).
  Callers EVERYWHERE (production + tests) are
  rewritten to alias-qualified calls and gain the require — addressed by
  FORM ID, because a defmethod body defines no var and a caller set keyed on
  a name dropped those silently; `:callers-unrewritten` reports any the pass
  did not change, so the count is readable against its population; the moved defs are publicized (module-grain
  visibility replaces var privacy); the target gets only the requires the
  moved code uses. Dependency direction is analyzed: stay→moved adds the
  require back to from-ns, moved→stay qualifies stay refs instead, a
  two-way split refuses (real cycle). Cross-module edges the move's own
  rewires necessitate are AUTO-DECLARED (the move's prompt rides each
  :module-edge delta — the move IS the declared intent), refusing only a
  cycle-closer; `:export true` marks moved vars ^:export when the deep
  target must stay callable from outside its subtree. One atomic group +
  changeset, compile-gated, verified across every touched namespace.
  The one thing that verification structurally cannot see is REPORTED
  instead: `:shadowed` names each moved call dequalified onto a LOCAL of the
  same name — valid Clojure that calls the local, so it compiles and stays
  green while the behaviour changes."
  [session from-ns form-names to-ns & {:keys [prompt agent export]}]
  (let [st   (:store @session)
        plan (refactor/move-plan st from-ns form-names to-ns {:export export})]
    (if (:error plan)
      (select-keys plan [:error])
      (let [manifest (edit.modules/modules-manifest st)
            rows     (map (fn [r]
                            (assoc r :to-export
                                   (if (= (symbol (str to-ns)) (:to r))
                                     ;; PER VAR, and the option WIDENS rather
                                     ;; than replaces. A var that is already
                                     ;; ^:export keeps that level through the
                                     ;; move — its node carries its own
                                     ;; metadata — so claiming otherwise made
                                     ;; the move refuse on visibility until the
                                     ;; flag was re-passed for something
                                     ;; already true, and passing it then
                                     ;; exported every moved var alike. Read
                                     ;; off FROM-NS, where the var still lives:
                                     ;; to-ns does not hold it until this lands.
                                     (or export
                                         (edit.modules/export-level
                                          st (symbol (str from-ns)) (:to-name r)))
                                     (edit.modules/export-level
                                      st (:to r) (:to-name r)))))
                          (:module-rows plan))
            ;; edges the move's rewires necessitate are part of its intent
            tmanif   (edit.modules/module-test-manifest st)
            ;; WHICH crossings still need declaring is asked of the canonical
            ;; rule, not re-derived here. The re-derivation this replaces read
            ;; the PRODUCTION manifest alone, so a crossing declared
            ;; {test-only true} looked undeclared, was charged to the move, and
            ;; refused as a cycle — module_dep's own docstring says a test-only
            ;; edge "is NOT a production edge, so no cycle question applies to
            ;; it". It fired hardest on the move that cannot possibly need an
            ;; edge: a WITHIN-module one, where source and target share a module
            ;; and every crossing is exactly what it was before.
            edges    (->> (edit.modules/module-violations manifest tmanif rows)
                          (filter #(= :undeclared-edge (:rule %)))
                          (map (fn [v] [(edit.modules/module-of (:from-ns v))
                                        (edit.modules/module-of (:target-ns v))]))
                          distinct vec)
            cyclic   (seq (filter (fn [[a b]] (store/module-path manifest b a))
                                  edges))
            manifest' (reduce (fn [m [a b]] (update m a (fnil conj #{}) b))
                              manifest edges)
            viols    (edit.modules/module-violations manifest' tmanif rows)
            refusal  (cond
                       cyclic
                       (str "the move would close a module dependency cycle ("
                            (str/join ", " (map (fn [[a b]] (str a " → " b))
                                                cyclic))
                            ") — move the shared piece the other way, or"
                            " restructure the callers first")

                       viols
                       (str (str/join "; " (map :error viols))
                            (when (and (not export)
                                       (every? #(= :visibility (:rule %)) viols))
                              " — or pass export: true to hoist the moved vars")))]
        (if refusal
          {:error refusal}
          (let [[gid st-g] (store/alloc-id st "g")
                ;; 0. the auto-declared edges, each carrying the move's why
                st0 (reduce (fn [s [a b]]
                              (first (store/record-module-edge
                                      s a b :add
                                      :prompt (or prompt (str "move-forms: " from-ns " → " to-ns))
                                      :agent agent)))
                            st-g edges)
                ;; 0b. a namespace born from a move is born UNDECLARED, and
                ;; undeclared is :external by absence of a claim — so forms
                ;; that just left a :pure core arrive in the shell, and the
                ;; split invents a core→shell dependency out of nothing. The
                ;; honest default is what was true one delta ago: the source's
                ;; governing tier. Recorded BEFORE the ingest, so the moved
                ;; forms are verified against it on the way in.
                ;;
                ;; Only from a source that CARRIES a claim, and only when the
                ;; target would otherwise be governed differently. An
                ;; undeclared source mints nothing — stamping :external there
                ;; would defeat a deliberate move INTO a pure subtree, where
                ;; the right outcome is the gate refusing impure forms.
                ;; `ns_rename` has no equivalent gap: it RELOCATES a
                ;; declaration rather than copying it, and the asymmetry
                ;; between the two relocation verbs was invisible until a
                ;; whole-store check named the namespace that did NOT change.
                st0 (let [src (symbol (str from-ns))]
                      (if (and (:new-ns? plan)
                               (tiers/tier-declared? st src)
                               (not= (tiers/tier-for st src)
                                     (tiers/tier-for st to-ns)))
                        (first (store/record-module-tier
                                st0 (str to-ns) (tiers/tier-for st src)
                                :prompt (or prompt
                                            (str "move-forms: " from-ns " → " to-ns))
                                :agent agent))
                        st0))
                ;; 1. the target: ingest new, or append + requires to existing
                st1 (if (:new-ns? plan)
                      (store/ingest st0 to-ns (:new-src plan) :agent agent)
                      (let [st* (reduce (fn [s node]
                                          (or (first (store/append-form
                                                      s to-ns node
                                                      :prompt prompt :group gid
                                                      :agent agent))
                                              s))
                                        st0 (:append plan))]
                        (reduce (fn [s spec]
                                  (add-require-node s to-ns spec
                                                    :prompt prompt :group gid
                                                    :agent agent))
                                st* (:to-require-adds plan))))
                ;; 2. callers gain [to-ns :as alias]
                st2 (reduce (fn [s [nsx spec]]
                              (add-require-node s nsx spec
                                                :prompt prompt :group gid
                                                :agent agent))
                            st1 (:require-adds plan))
                ;; 3. every rewritten caller, ONE changeset (multi-ns)
                [st3 _] (if (seq (:rewrites plan))
                          (store/apply-changeset
                           st2 :move-forms from-ns
                           (into {} (map (fn [[fid m]] [fid (:node m)]))
                                 (:rewrites plan))
                           :prompt prompt :agent agent)
                          [st2 nil])
                ;; 4. the moved forms leave home
                st4 (reduce (fn [s nm]
                              (or (first (store/remove-form s from-ns nm
                                                            :prompt prompt
                                                            :group gid :agent agent))
                                  s))
                            st3 (:removals plan))
                ;; 4b. the requires the move just orphaned leave with them.
                ;; A sequential move is what surfaced this: a caller rewritten
                ;; to `external/author-identity` moved in the NEXT batch and
                ;; took the reference with it, and the cold-load gate then
                ;; REFUSED a state the move itself had created.
                st4 (reduce (fn [s lib]
                              (remove-require-node s from-ns lib
                                                   :prompt prompt :group gid
                                                   :agent agent))
                            st4 (:from-require-drops plan))
                ;; 4c. and the mirror: a rewritten caller left referencing
                ;; nothing in from-ns drops that require. Left behind, a
                ;; :pure caller keeps inheriting from-ns's TIER for a
                ;; dependency it no longer has.
                st4 (reduce (fn [s nsx]
                              (remove-require-node s nsx from-ns
                                                   :prompt prompt :group gid
                                                   :agent agent))
                            st4 (:caller-require-drops plan))
                ;; the PIPELINE owns ordering. The planner no longer mints a
                ;; declare for the moved set: a source ns may have ordered
                ;; caller-before-callee behind a declare that STAYS BEHIND, so
                ;; the target can land with a forward ref — resolve-cold-load
                ;; reorders it (or inserts the pipeline's own MARKED declare
                ;; 4d. the PROSE follows the move: a docstring naming
                ;; from-ns/x must become to-ns/x. Qualified references only —
                ;; a bare name may be a domain word. This is the d9077 class
                ;; at its source: `analyze` moved namespaces and two guidance
                ;; surfaces kept naming its pre-move address.
                st4 (let [cs (refactor/qualified-mention-changeset
                              st4
                              (into {} (for [nm (:moved plan)]
                                         [(symbol (str from-ns) (str nm))
                                          (symbol (str to-ns) (str nm))]))
                              {})]
                      (if (seq cs)
                        (first (store/apply-changeset st4 :move-forms from-ns cs
                                                      :prompt prompt :agent agent))
                        st4))
                ;; for a genuine cycle). Same one call fix-declares! makes.
                st4 (if-let [rz (edit/resolve-cold-load
                                 st4 to-ns
                                 :prompt (or prompt (str "move-forms: " from-ns
                                                         " → " to-ns))
                                 :agent agent)]
                      (:store rz)
                      st4)
                touched (vec (distinct
                              (into [from-ns to-ns]
                                    (concat (map :ns (vals (:rewrites plan)))
                                            (keys (:require-adds plan))))))
                ;; defs before callers (X2): target forms, then decls, then rewrites
                ordered (distinct
                         (concat (map :id (store/forms st4 to-ns))
                                 (keep #(:id (store/form-named st4 % %))
                                       (keys (:require-adds plan)))
                                 (keys (:rewrites plan))))
                hl       (engine/hot-load-all! session st4 ordered)
                ;; the COLD-load gate, which a move needs more than any other write:
                ;; it is the one operation that reorders NAMESPACE dependencies,
                ;; and hot-loading structurally cannot see a require cycle
                ;; because the vars already exist in the image. A move once
                ;; committed `[slopp/api] -> slopp/api/external -> [slopp/api]`
                ;; and verified GREEN over it.
                load-err (or (:err hl) (edit/cold-load-errors st4 touched))]
            (if load-err
              (do (engine/fresh-image! session)
                  (edit/compile-error st4 load-err "move failed to compile: "))
              (if-not (engine/try-commit! session st st4 touched)
                (do (engine/fresh-image! session)
                    {:conflict {:reason "store changed during move — retry"}})
                (do (doseq [nm (:removals plan)]
                      (repl/eval! (:image @session)
                                  (format "(ns-unmap '%s '%s)" from-ns nm)))
                    (let [moved-q (into (set (map #(symbol (str from-ns) (str %))
                                                  (:moved plan)))
                                        (map #(symbol (str to-ns) (str %)))
                                        (:moved plan))
                          summary (engine/run-verification!
                                   session touched nil
                                   :edited (into moved-q
                                                 (map (fn [[_ m]]
                                                        (symbol (str (:ns m)) (str (:name m)))))
                                                 (:rewrites plan)))]
                      (engine/commit-appended! session
                                        #(store/record-verification
                                          % touched summary)
                                        [])
                      (let [;; POSTCONDITION, read back from the store this move actually
                            ;; COMMITTED rather than from its plan. The gate pre-check
                            ;; consults the PLANNED export, so a marker pass that skips a
                            ;; name — it once skipped every meta-wrapped one, leaving
                            ;; `^:dynamic` vars unexported — passes the gate and surfaces
                            ;; a session later, somewhere else.
                            miss (read.modules/unlanded-exports (:store @session) rows)]
                        (cond-> {:moved-to to-ns
                                 :moved (:moved plan)
                                 :rewrote (count (:rewrites plan))
                                 :callers (vec (sort (distinct (map :ns (vals (:rewrites plan))))))
                                 :group gid
                                 :test summary}
                          (seq edges) (assoc :edges-declared edges)
                          ;; the population beside :rewrote. Every row is a
                          ;; form the reference graph says calls a moved name
                          ;; and this pass did not change — sometimes right (a
                          ;; quoted target is left whole on purpose), never
                          ;; something the count alone would have said.
                          (seq (:callers-unrewritten plan))
                          (assoc :callers-unrewritten (:callers-unrewritten plan))
                          ;; the one thing this move did that NOTHING
                          ;; downstream can catch: the compile gate is happy,
                          ;; the suite is green, and the call reaches a
                          ;; different thing than it did before.
                          (seq (:shadowed plan))
                          (assoc :shadowed (:shadowed plan)
                                 :shadowed-note
                                 (str "a dequalified call landed on a LOCAL of"
                                      " the same name. This COMPILES and stays"
                                      " green — the call now reaches the local"
                                      " rather than the var, so the behaviour"
                                      " changed and no test can see it. Rename"
                                      " the local, or the moved var, in each"
                                      " row above."))
                          (seq miss)
                          (assoc :export-not-landed miss
                                 :export-note
                                 (str "the move planned an export for "
                                      (str/join ", " miss)
                                      " and the committed store carries no marker —"
                                      " mark the name(s) ^:export directly; callers"
                                      " outside the subtree will not resolve them"))))))))))))))

(defn ns-rename!
  "Rename a WHOLE namespace: its ns decl, every require clause, and every
  fully-qualified reference across the store; the namespaces map rekeys; the
  image rebuilds fresh (old name gone); everything re-verifies."
  [session old new & {:keys [prompt agent defer-verify]}]
  (let [st  (:store @session)
        old (symbol (str old)) new (symbol (str new))]
    (cond
      (nil? (get-in st [:namespaces old]))
      {:error (str "no namespace " old)}

      (get-in st [:namespaces new])
      {:error (str new " already exists")}

      :else
      (let [code-cs   (refactor/ns-rename-changeset st old new)
            ;; every form of the renamed ns is re-addressed, so prose naming
            ;; old/x must follow to new/x — qualified references only; a bare
            ;; name may be a domain word
            changeset (merge code-cs
                             (refactor/qualified-mention-changeset
                              st
                              (into {} (for [e (store/forms st old)
                                             :when (:name e)]
                                         [(symbol (str old) (str (:name e)))
                                          (symbol (str new) (str (:name e)))]))
                              code-cs))
            [st1 delta] (store/apply-changeset st :rename-ns old changeset
                                               :prompt (or prompt (str "rename ns "
                                                                       old " → " new))
                                               :agent agent
                                               :extra {:old old :new new})
            st2 (update st1 :namespaces
                        (fn [m] (-> m (dissoc old) (assoc new (get m old)))))
            touched (vec (distinct (concat [old new]
                                           (keep #(store/ns-of-form-id st2 %)
                                                 (keys changeset)))))]
        (if-not (engine/try-commit! session st st2 touched)
          {:conflict {:reason "store changed during ns-rename — retry"}}
          (do
            ;; trace map: rewrite every old/... qsym
            (swap! session update :test-map
                   (fn [tm]
                     (let [fix #(if (= (str old) (namespace %))
                                  (symbol (str new) (name %)) %)]
                       (into {} (map (fn [[t fs]]
                                       [(fix t) (into #{} (map fix) fs)]))
                             tm))))
            ;; the manifest follows: module names are ns prefixes, so when the
            ;; LAST ns of a module renames away, its edges re-key (semantic
            ;; :module-edge removes+adds — the journal shows the follow). BOTH
            ;; module-grained registers, from the one list of them: this arm
            ;; used to name :modules and nothing named :module-test-edges, so
            ;; which failure a rename produced depended on whether the edge
            ;; happened to be test-only
            (let [old-mod (edit.modules/module-of old)
                  new-mod (edit.modules/module-of new)
                  why     (str "manifest follows ns rename " old " → " new)]
              (when (and (not= old-mod new-mod)
                         (not-any? #(= old-mod (edit.modules/module-of %))
                                   (keys (:namespaces (:store @session)))))
                (engine/commit-appended!
                 session
                 #(store/rekey-module-registers % old-mod new-mod
                                                :prompt why :agent agent)
                 [])))
            ;; and so do the namespace-grained registers. A tier or platform describes
            ;; a NAME: orphaned, it both lists a namespace that no longer exists and
            ;; leaves the renamed code ungated. Deep namespaces follow by prefix.
            (let [st*     (:store @session)
                  why     (str "declaration follows ns rename " old " → " new)
                  moved   (fn [reg]
                            (vec (for [[k v] (get st* reg)
                                       :when (or (= k (str old))
                                                 (str/starts-with? k (str old ".")))]
                                   [k (str new (subs k (count (str old)))) v])))
                  ;; every ns-grained register, from the ONE list of them —
                  ;; these arms were hand-written per register and identical
                  ;; apart from the record fn, so a new register was forgotten
                  ;; by construction rather than by oversight
                  by-reg  (into {} (for [[reg _] store/ns-grained-registers
                                         :let [rows (moved reg)]
                                         :when (seq rows)]
                                     [reg rows]))]
              (when (seq by-reg)
                (engine/commit-appended!
                 session
                 (fn [base]
                   (reduce-kv
                    (fn [s reg rows]
                      (let [record (get store/ns-grained-registers reg)]
                        (reduce (fn [s [k k' v]]
                                  (-> s
                                      (record k' v :prompt why :agent agent) first
                                      (record k nil :action :remove
                                              :prompt why :agent agent) first))
                                s rows)))
                    base by-reg))
                 [])))
            (engine/fresh-image! session)          ; the old ns must NOT linger
            (let [verify-nses (vec (remove #{old} touched))
                  ;; `defer-verify` hands the verification to the COMPOSITE that owns
                  ;; this rename (module_extract). Mid-batch the store has
                  ;; namespaces renamed and callers not yet rewritten, so
                  ;; verifying here is both expensive and meaningless — the only
                  ;; bar a batch can meet is that its END STATE is green. One
                  ;; logical change used to pay N verifications purely because
                  ;; each step was spelled as a verb.
                  summary (when-not defer-verify
                            (engine/run-verification!
                             session verify-nses nil
                             :edited (into #{}
                                           (keep (fn [id]
                                                   (when-let [e (store/form-by-id st2 id)]
                                                     (symbol (str (store/ns-of-form-id st2 id))
                                                             (str (or (:name e) (:id e)))))))
                                           (keys changeset))))]
              (when summary
                (engine/commit-appended! session
                                  #(store/record-verification % verify-nses summary)
                                  []))
              ;; frictions #7/#13: symbols are rewritten perfectly and everything else
              ;; — a name inside a string, the `-test` sibling's own name — is
              ;; left, correctly and SILENTLY. Silence reads as "there was
              ;; nothing to carry". Scanned AFTER the write over the OLD name, so
              ;; whatever is still there was, by construction, not rewritten.
              (let [occ (group-by :via (refs/occurrences-of (:store @session) old))
                    ;; the residue with no occurrence to find: the rename
                    ;; rewrote the lib symbol beside the `:as` and left the
                    ;; alias, so nothing spelling `old` remains for the scan
                    ;; above to catch. It takes BOTH names because an alias is
                    ;; stale only relative to what replaced the old one
                    aliases (refactor/stranded-aliases (:store @session) old new)
                    left (cond-> occ (seq aliases) (assoc :alias aliases))
                    ;; same reality-check stance as `left`, one system over:
                    ;; read the store the rename actually produced rather than
                    ;; simulating what it meant to do
                    debt (edit.modules/relocation-debt (:store @session) new)]
                (cond-> {:renamed {:old old :new new :forms (count changeset)}
                         :delta (:id delta)}
                  summary      (assoc :test summary)
                  debt         (assoc :module-debt debt)
                  ;; hand the scope UP: the owning transaction verifies this
                  ;; namespace set once, after the whole batch has landed
                  defer-verify (assoc :deferred-verification true
                                      :verify-nses verify-nses)
                  ;; a rename can strand an alias while leaving NO occurrence to
                  ;; find: the lib symbol beside the `:as` is exactly what it
                  ;; rewrote. Gating this on the occurrence scan would mean the
                  ;; cleanest renames report nothing and are the ones that strand
                  (or (seq occ) (seq aliases))
                  (assoc :left-behind left
                         :note
                         (str
                          (when (seq occ)
                            (str (count (apply concat (vals occ))) " occurrence(s) of "
                                 old " were NOT rewritten"
                                 (when-let [ss (seq (remove :prose (:string left)))]
                                   (str " — " (count ss) " in TOKEN strings (a path, a"
                                        " main-ns, a require target: these BREAK, they do"
                                        " not merely read wrong)"))
                                 (when-let [src (seq (:string-source left))]
                                   (str "; " (count src) " string(s) carry SOURCE"
                                        " declaring " old " — code rather than text, and"
                                        " the quoted symbol beside each one WAS rewritten"
                                        " (it is a token), so those two halves have come"
                                        " apart: the fixture now ingests source declaring"
                                        " one namespace under the name of another, and"
                                        " stays green if it asserts nil"))
                                 (when (:test-sibling left)
                                   (str "; the -test sibling still carries the old name,"
                                        " which files its tests under the old module"))
                                 (when-let [kw (seq (:keyword left))]
                                   (str "; " (count kw) " qualified KEYWORD(s) ("
                                        (str/join ", " (sort (distinct (map :text kw))))
                                        ") — the silent class, and the only one with no"
                                        " second chance: a broken token string turns"
                                        " something red, while a keyword stays green and"
                                        " merely starts naming a namespace that is gone"))
                                 (when-let [rx (seq (:regex left))]
                                   (str "; " (count rx) " REGEX literal(s) spell it ("
                                        (str/join ", " (sort (distinct (map :text rx))))
                                        ") — a pattern is data, so no rewrite reaches it"
                                        " and the escaped dots hide it from the text"
                                        " sweep too. Rank these first: a PRESENCE"
                                        " assertion built on one turns red, while an"
                                        " ABSENCE assertion becomes permanently true and"
                                        " guards nothing, which is how two guards in"
                                        " this store shipped green over an empty search"))
                                 ". Judge each: rewriting is deliberately conservative"
                                 " here — a qualified keyword can be a wire or storage"
                                 " key something outside the store already holds — so"
                                 " this list is the whole signal."))
                          (when (seq aliases)
                            (str (when (seq occ) " ")
                                 (count aliases) " caller(s) still ALIAS this namespace"
                                 " by a spelling taken from " old " ("
                                 (str/join ", " (sort (distinct (map #(str (:alias %))
                                                                     aliases))))
                                 ") — a rename rewrites the lib in a require clause and"
                                 " never the `:as` beside it, so their call sites go on"
                                 " reading the old module's name for code that has left"
                                 " it. Harmless only until that name is REUSED, after"
                                 " which the alias points at something real and"
                                 " different. ns_realias fixes each; :suggest carries"
                                 " the alias the convention would produce, and is"
                                 " absent where that caller already spells another lib"
                                 " that way.")))))))))))))

(defn affected-test-nses
  "The PROVABLE verification slice: test namespaces (any ns holding a
  deftest) whose require-closure reaches a form changed since the last
  COMMIT-POINT — a test can only exercise code it can load. Returns
  {:changed-nses [...] :selected [...]}; empty :selected = nothing since
  the commit-point can affect any test. Full-suite confidence stays the
  commit-point gate's job."
  [session]
  (let [st      (:store @session)
        last-c  (:id (db/last-marker (:db @session) (engine/session-line session) :commit))
        changed (into #{}
                      (keep #(store/ns-of-form-id st %))
                      (forms-changed-since st last-c))]
    {:changed-nses (vec (sort changed))
     :selected     (engine/test-nses-reaching st changed)}))

(defn last-judged-done
  "The most recent verdict that actually JUDGED something (`:test-status`
  `:red` or `:green`), or nil — from a `:done` delta or from a whole-store
  `full_check`, whichever came last.

  Exists because `done` is episode-scoped: calling it twice with no writes
  between yields `:none` the second time — nothing changed, so nothing was
  checked. Without this a RED done could be laundered by simply committing
  afterwards: the commit-point runs its own done, gets `:none`, and publishes.
  The last real verdict stands until new work supersedes it.

  **A green `full_check` is such a verdict (friction 14).** Reading only
  `:done` deltas left a trap with no way out: an episode whose changed forms
  have no covering tests — a rename, a docstring, a `:cljs` edit — judges
  `:none`, so `commit_point` reaches back to a verdict that can be arbitrarily
  old, and every subsequent done judges `:none` too. The store got greener
  while the commit-point stayed refused, and the whole-store check — broader,
  slower, more authoritative — could not clear what the narrower one left
  behind. Now it can, and a later `done` supersedes it in turn: most recent
  judgement wins, whichever kind it is.

  Only `:scope :full-check` counts. `record-verification` also lands on
  ordinary writes, and those are form-scoped — a per-write green says nothing
  about the store.

  Returns the whole findings map rather than the status alone so a standing
  red can still NAME what was wrong — a refusal that cannot say why is a
  refusal an agent cannot act on. The full_check verdict carries only what its
  delta recorded, and deliberately not its namespace count or wall time:
  `commit_point` builds its refusal reason from whichever keys are present and
  non-zero, so an informational count would make a refusal say `namespaces`
  as though that were the thing that fired."
  [store]
  (->> ;; the RECENT window: since the last commit-point, plus the done that
       ;; earned it — which is as far back as a standing verdict can be
       (:recent store)
       (keep (fn [d]
               (cond
                 (and (= :done (:op d))
                      (#{:red :green} (:test-status (:findings d))))
                 (:findings d)

                 (and (= :verify (:op d))
                      (= :full-check (:scope (:result d)))
                      (#{:red :green} (:status (:result d))))
                 (let [r (:result d)]
                   (cond-> {:test-status (:status r) :scope :full-check}
                     (pos? (:lint-errors r 0))
                     (assoc :lint-errors (:lint-errors r)))))))
       last))

(defn file-put!
  "Track a NON-CODE file on the store's files manifest — it rides every
  projected tree, so slopp pushes never delete it.

  Content comes from `content` inline, or from `:source`, a path on disk.
  Both are AUTHORED — tracked, versioned, and part of the projected tree.
  They differ only in how the bytes are stored:

  - `content` inline: text the agent wrote. Stored in the delta itself, so
    it is diffable and time-travellable with `file_get :at`.
  - `:source`: a file that already exists — an image, a font, something
    imported. Stored CONTENT-ADDRESSED, so the journal carries a sha and
    the bytes live once in `:blobs` rather than inline in every delta.

  Measured cause: 30.5 MB of this store's delta log is inline file content,
  99.8% of it one regenerable bundle re-appended on every build. Sniffing
  the content-type was worse than useless here — it called
  `application/javascript` text and put a vendored library straight into a
  delta. Provenance decides, not file type.

  What does NOT belong on this manifest at all is a file that can be
  REGENERATED — a compiled bundle, a downloaded library. Those are
  `:artifacts`: bytes on disk, sha and recipe in the journal, written by
  `compile_client` and `js_dep`. The distinction is recoverability, not
  authorship: an artifact that is deleted is rebuilt, a file that is
  deleted is lost.

  `:encoding \"base64\"` still decodes `content` explicitly.
  Returns {:path :bytes} (+ :sha when content-addressed)."
  [session path content & {:keys [prompt agent encoding content-type source]}]
  (let [from-src (when (and source (nil? content))
                   (let [f (java.io.File. (str source))]
                     (if-not (.exists f)
                       {:error (str "no file at " (str source))}
                       ;; ALWAYS content-addressed: a file read off disk is an
                       ;; artifact, whether or not its bytes happen to be text
                       {:content  (.encodeToString (java.util.Base64/getEncoder)
                                                   (java.nio.file.Files/readAllBytes
                                                    (.toPath f)))
                        :encoding "base64"})))
        content  (or content (:content from-src))
        encoding (or encoding (:encoding from-src))]
    (cond
      (str/blank? (str path))
      {:error "file_put needs a :path"}

      (:error from-src)
      {:error (:error from-src)}

      (nil? content)
      {:error "file_put needs :content, or :source naming a file to read it from"}

      (and encoding (not= "base64" (str encoding)))
      {:error (str "unknown :encoding " encoding " — omit it for text, or"
                   " \"base64\" for binary")}

      :else
      (let [st' (engine/commit-appended! session
                                          #(first (store/record-file-put % path content
                                                                         :prompt prompt :agent agent
                                                                         :encoding encoding
                                                                         :content-type content-type))
                                          [])
            e   (get (:files st') (str path))]
        (if (map? e)
          {:path (str path) :bytes (:bytes e) :sha (:sha e)}
          {:path (str path) :bytes (count (str content))})))))

(defn file-remove!
  "Drop `path` from the files manifest. Returns {:removed path} | {:error}."
  [session path & {:keys [prompt agent]}]
  (if-not (contains? (:files (:store @session)) (str path))
    {:error (str path " is not on the files manifest")}
    (do (engine/commit-appended! session
                          #(first (store/record-file-remove % path
                                                            :prompt prompt :agent agent))
                          [])
        {:removed (str path)})))

^:reads (defn files-list
  "The files manifest: {path byte-count} (content via `file_get`, the git
  projection, or a build).

  The two entry SHAPES answer the size question differently and only one of
  them fails loudly. A text entry IS its content, so counting it is its
  length; a binary entry is a content-address map that records `:bytes`, and
  counting THAT returns the number of keys — a 7-byte asset listed as 3,
  under this docstring. Derived artifacts are a separate manifest and are not
  listed here; `store_health` counts those."
  [session]
  {:files (into (sorted-map)
                (map (fn [[p e]] [p (if (map? e) (:bytes e) (count e))]))
                (:files (:store @session)))})

(defn set-comment!
  "Set (or clear, with blank `text`) the comment rendered above form `nm`.

  A different shape from the positional predecessor it replaces: trivia was
  placed BEFORE a form and owned by nobody, a comment is owned BY the form.
  That removes the positional question entirely — no anchor, no run to
  replace, and a merge reconciles it as ordinary content on a form identity.

  No image work and no verification: a comment changes what a namespace
  RENDERS, never what it means. Returns {:delta :ns :name} | {:error} |
  {:conflict}."
  [session ns-sym nm text & {:keys [prompt agent]}]
  (let [base (:store @session)
        r    (store/set-comment base ns-sym nm text :prompt prompt :agent agent)]
    (if (:error r)
      r
      (let [[st' d] r]
        (if-not (engine/try-commit! session base st' [ns-sym])
          {:conflict {:reason "store changed concurrently — retry"}}
          {:delta (:id d) :ns (str ns-sym) :name (str nm)})))))

^:reads (defn file-get
  "A manifest file's content — current, or as of a past delta via `:at`
  (a delta id or commit-point id resolves through its :target). Text →
  {:path :content}; binary → {:path :content <base64> :encoding \"base64\"
  :content-type :sha} (bytes from the in-memory cache, else the db — a
  foreign-synced entry). Returns {:error} for unknown paths."
  [session path & {:keys [at]}]
  (let [st (:store @session)
        b64 (fn [^bytes bs] (.encodeToString (java.util.Base64/getEncoder) bs))
        resolved (fn [entry]
                   (if (map? entry)
                     (let [bs (or (get (:blobs st) (:sha entry))
                                  (some-> (:db @session)
                                          (db/get-blob (:sha entry))))]
                       (if bs
                         {:path (str path) :content (b64 bs)
                          :encoding "base64"
                          :content-type (:content-type entry) :sha (:sha entry)}
                         {:error (str path " is a binary entry whose blob "
                                      (:sha entry) " is not in this store")}))
                     {:path (str path) :content entry}))]
    (if at
      (let [conn  (:db @session)
            line  (engine/session-line session)
            ;; a commit-point id resolves through its :target — one row by id
            at-d  (db/delta-by-id conn at)
            at-id (if (= :commit (:op at-d)) (:target at-d) at)
            c     (store/file-at (db/line-deltas conn line) (str path) at-id)]
        (if (some? c)
          (assoc (resolved c) :at at)
          {:error (str path " has no content at " at)}))
      (if-let [e (get (:files st) (str path))]
        (resolved e)
        {:error (str path " is not on the files manifest")}))))

^:reads (defn file-history!
  "Every tracked version of a manifest file, oldest first, with provenance —
  the file counterpart of query_history {ns name}. The line's journal is read
  here, when asked; the value does not carry it."
  [session path]
  (let [h (store/file-history (db/line-deltas (:db @session) (engine/session-line session))
                              (str path))]
    (if (seq h)
      {:path (str path) :versions h}
      {:error (str path " has never been tracked")})))

(defn config-file!
  "Structured config files: the store holds SEMANTIC key/values per path
  (per-key delta history, like forms); the projection serializes them into
  the file format (`:manifest` → sorted `K: V` lines). Set a key
  (`:key`+`:value`, `:format` on first touch, default :manifest), remove one
  (`:key`+`:unset true`), or read (path only: values + rendered preview).
  The module manifest is NOT a config file — module_dep is its verb.

  TWO paths validate before any delta lands: `capabilities` through the
  capability registry and `rules` through the rule catalog. Unknown keys and
  type-failing values are refused with teaching. Every other path records what
  it was given, and the result SAYS so rather than looking like a checked
  write."
  [session path & {:keys [key value unset format prompt agent]}]
  (let [entry (get-in (:store @session) [:config (str path)])]
    (cond
      (= "modules" (str path))
      {:error "the module manifest is edge-grain, not a file — declare or retract one dependency at a time: module_dep {from \"x.y\" to \"a.b\"} (add) or module_dep {from \"x.y\" to \"a.b\" remove true}; read it via query_depends {modules true}"}

      (and key unset)
      (if-not (get-in entry [:values (str key)])
        {:error (str key " is not set on " path)}
        (do (engine/commit-appended! session
                              #(first (store/record-config-unset % path key
                                                                 :prompt prompt
                                                                 :agent agent))
                              [])
            {:path (str path) :unset (str key)}))

      (and key (some? value))
      ;; THREE registries now. The `rules` one closed a real hole: with nothing
      ;; to disagree with, a renamed dial and a MISTYPED dial were the same
      ;; event — both accepted, both governing nothing, neither reported. The
      ;; catalog always knew every rule; this path simply was not asking.
      ;; `dev` arrives with its registry already written, so it is asked from
      ;; the start rather than after somebody loses an afternoon to a field
      ;; named `prot`.
      (if-let [refusal (case (str path)
                         "capabilities"
                         (or (capabilities/config-refusal (str key) (str value))
                             (capabilities/disable-refusal (:store @session)
                                                           (str key) (str value)))
                         "rules"
                         (catalog/config-refusal (str key) (str value))
                         "dev"
                         (dev/config-refusal (str key) (str value))
                         nil)]
        {:error refusal}
        (let [fmt      (or (some-> format clojure.core/keyword)
                           (:format entry) :manifest)
              caps?    (= "capabilities" (str path))
              ;; whether a REGISTRY stood behind this write, which is a
              ;; different question from which path it was — and stopped being
              ;; the same question the moment `rules` gained one
              checked? (contains? #{"capabilities" "rules" "dev"} (str path))
              ;; the prerequisites this write turns on WITH it, computed
              ;; against the PRE-write store so the report names only what
              ;; actually changed rather than restating the graph
              implied  (when caps?
                         (seq (capabilities/implied-puts (:store @session)
                                                         (str key) (str value))))]
          (engine/commit-appended!
           session
           (fn [st]
             (reduce (fn [s k*]
                       (first (store/record-config-put s path fmt k* "true"
                                                       :prompt (str "implied by " key "=" value)
                                                       :agent agent)))
                     (first (store/record-config-put st path fmt key value
                                                     :prompt prompt
                                                     :agent agent))
                     implied))
           [])
          ;; A path with no registry records the key and value AS GIVEN, so a
          ;; caller cannot tell a checked write from an unchecked one unless the
          ;; result says which happened (D-surface-honesty). That admission is
          ;; what made the `rules` gap findable: slopp-ui probed a bogus key and
          ;; the note named the registry it was not using.
          (cond-> {:path (str path) :key (str key) :value (str value) :format fmt
                   :verified (if checked? [:registry] [])
                   :unverified (if checked? [] [:schema])}
            (not checked?)
            (assoc :note (str "recorded as given — no registry governs the "
                              path " config, so neither the key nor the value"
                              " was validated. `capabilities` (query_capabilities),"
                              " `rules` (query_rules) and `dev` are the checked"
                              " paths."))

            ;; ABSENT when nothing was implied, the way the module manifest's
            ;; :debt is: an empty vector on every write would train the reader
            ;; to skip a key that has to be read when it IS there.
            implied
            (assoc :implied (vec implied)
                   :implied-note (str "set with it, because " key
                                      " requires them — a capability turned on"
                                      " without its prerequisites looks enabled"
                                      " and does nothing")))))

      key
      (if-let [v (get-in entry [:values (str key)])]
        {:path (str path) :key (str key) :value v}
        {:error (str key " is not set on " path)})

      :else
      (if entry
        {:path (str path) :format (:format entry) :values (:values entry)
         :rendered (store/render-config entry)}
        {:error (str path " has no structured config")}))))

^:reads (defn draft-test
  "Rock 5: a ready-to-EDIT deftest draft for `ns-sym/nm`. With `:code` (a
  driver expression) it OBSERVES real calls and turns each capture into an
  assertion — tests grown from observed behavior, not invented values.
  Without :code, a signature-shaped skeleton with TODO holes. The draft is
  a SUGGESTION in the result — nothing is written; adopt it with
  edit_add_form after reading each assertion (red-first still applies)."
  [session ns-sym nm & {:keys [code limit] :or {limit 5}}]
  (if-let [e (store/form-named (:store @session) ns-sym nm)]
    (let [qname (str ns-sym "/" nm)
          obs   (when code
                  (if-let [err (edit/observe-gate code)]
                    {:error err}
                    (first (repl/eval! (:image @session)
                                       (format "(slopp.kernel.rt/observe '%s (fn [] %s) %d)"
                                               qname code limit)))))]
      (cond
        (:error obs) obs

        (seq (:calls obs))
        {:draft    (str "(deftest " nm "-t\n"
                        (str/join "\n"
                                  (map (fn [{:keys [args ret threw]}]
                                         (if threw
                                           (str "  (is (thrown? Exception ("
                                                qname " " (str/join " " args) ")))")
                                           (str "  (is (= " ret " ("
                                                qname " " (str/join " " args) ")))")))
                                       (:calls obs)))
                        ")")
         :observed (count (:calls obs))
         :note     (str "grown from OBSERVED calls — read each assertion before"
                        " adopting; edit_add_form lands it (the ns needs"
                        " [clojure.test :refer [deftest is]])")}

        :else
        (let [params (or (some #(when (vector? %) %) (drop 2 (n/sexpr (:node e))))
                         '[args])]
          {:draft (str "(deftest " nm "-t\n  (is (= :TODO-expected (" qname " "
                       (str/join " " (map #(str ":TODO-" %) params)) "))))")
           :note  (str "no examples — pass :code (a driver expression) to observe"
                       " real calls and get value-true assertions instead of holes")})))
    (edit/missing-form-error (:store @session) ns-sym nm)))

(defn remember-observation!
  "Persist what an observation SAW (up to two {:args :ret} captures) under
  store meta observed/<ns>/<name> — interface cards surface them as
  :examples, the strongest behavior line a card can carry (examples don't
  lie; prose can). Called by the MCP layer after query_observe; a no-op
  for ephemeral sessions or empty captures.

  Writes THROUGH: the db row is the durable copy (reloaded by
  `session/load-observations` at open) and the session's `:observed` map is
  the working one that `orient/form-card` reads. Both, or a card in this
  session would not see what was just observed."
  [session ns-sym nm observe-result]
  (when-let [calls (seq (:calls observe-result))]
    (let [k   (str "observed/" ns-sym "/" nm)
          raw (pr-str (vec (take 2 calls)))]
      (swap! session assoc-in [:observed k] raw)
      (when-let [conn (:db @session)]
        (try
          (db/set-meta! conn k raw)
          (catch Exception _ nil)))))
  nil)

(defn module-role!
  "Declare a module's ROLE — what KIND of code this is, which decides whether
  it ships: :product (the default) is code the system runs, materialized under
  `src/` and carried into the jar; :instrument is code a HUMAN runs by hand — a
  benchmark, a seeding script, a mining CLI — materialized under `instruments/`
  instead, so any build that jars `src` leaves it out, and excluded from the
  architecture view so a harness cannot sit at the apex of what it measures
  (R5). One :module-role delta carrying its why (:prompt); last write per
  module wins. Namespace grain, like module_purity — the most-specific
  declaration governs. Read roles via query_depends {modules true}.

  `remove: true` RETIRES a declaration: absent is not the same claim as
  :product, and the rename and delete paths both need the difference."
  [session module role & {:keys [prompt agent remove]}]
  (let [module (str module)
        ;; every surface spells roles WITH the colon, and MCP/JSON carries a
        ;; string, so accept both rather than minting a bad keyword
        role   (fields/canonical-role (or role "product"))
        modish (re-matches #"[^.\s]+(\.[^.\s]+)*" module)]
    (cond
      (not modish)
      {:error (str "modules are the first TWO segments of a namespace"
                   " (\"logi.parcel\", not \"logi.parcel.impl\") — got "
                   (pr-str module))}

      remove
      (if (contains? (:module-roles (:store @session)) module)
        (let [st' (engine/commit-appended!
                   session
                   #(first (store/record-module-role % module nil :action :remove
                                                    :prompt prompt :agent agent))
                   [])]
          {:module module :action :removed :roles (:module-roles st')})
        {:error (str module " has no role declaration — nothing to remove."
                     " Undeclared already means :product.")})

      (not (#{:product :instrument} role))
      {:error (str "role must be :product or :instrument — got " (pr-str role)
                   ". :product = the system runs it and it ships (the default);"
                   " :instrument = a HUMAN runs it by hand, so it is"
                   " materialized outside src/ and never reaches the jar.")}

      :else
      ;; a role is an ASSERTION ABOUT THE CODE, so check it against the code —
      ;; the same bar module_purity meets. :instrument MOVES the namespaces out
      ;; of src/, so the one thing that must not be true is that product code
      ;; requires them. Unchecked, the break surfaces at a CONSUMER's load
      ;; time, naming a namespace this store plainly has.
      (let [st      (:store @session)
            members (filter #(or (= module (str %))
                                 (str/starts-with? (str %) (str module ".")))
                            (keys (:namespaces st)))
            needed  (when (= :instrument role)
                      (vec (sort (distinct
                                  (for [other (keys (:namespaces st))
                                        :when (and (not (store.render/test-ns? other))
                                                   (not= :instrument
                                                         (store/role-for st other))
                                                   (not (some #{other} members)))
                                        :when (some (set members)
                                                    (store/ns-requires st other))]
                                    (str other))))))]
        (if (seq needed)
          {:error (str "cannot declare " module " :instrument — "
                       (str/join ", " needed)
                       (if (= 1 (count needed)) " requires" " require")
                       " it, and product code cannot depend on code that does"
                       " not ship. Move what they need into a product module,"
                       " or declare those callers :instrument too."
                       " (A -test requirer would be fine: a test does not ship"
                       " either.)")
           :required-by needed}
          (let [st' (engine/commit-appended!
                     session
                     #(first (store/record-module-role % module role
                                                       :prompt prompt :agent agent))
                     [])]
            {:module module :role role
             :roles (:module-roles st')
             :verified (if (= :instrument role) [:no-product-requirer] [])
             :unverified [:human-runs-it]
             :note (if (= :instrument role)
                     (str "no product namespace requires " module
                          ", so moving it out of src/ breaks no load. That a"
                          " HUMAN rather than the system runs it is the part"
                          " nothing here can check — it is your claim.")
                     (str module " is :product, which is also what an absent"
                          " declaration means. Declare it only to overrule a"
                          " broader :instrument above it."))}))))))

(defn module-platform!
  "Declare a module's target PLATFORM — the client wave's router (D-web-cljs):
  :jvm (Clojure on the JVM, the default), :cljc (portable — loads on the JVM AND
  compiles to JS), or :cljs (ClojureScript only — compiled to JS, never loaded
  into the JVM oracle). One :module-platform delta carrying its why (:prompt);
  last write per module wins. Namespace grain, like module_purity — the
  most-specific declaration governs. Read platforms via query_depends
  {modules true}.

  A `:cljs` declaration additionally reports the `^:app/entry` entries it
  STRANDS (`:stranded-pages` + `:warning`): stranding happens with no write to
  the page, so the page's own done has nothing to hang the finding on, and
  the write that does the stranding is the one surface that can name it at
  the moment it happens."
  [session module platform & {:keys [prompt agent remove]}]
  (let [module   (str module)
        ;; every surface spells platforms WITH the colon, and MCP/JSON carries
        ;; a string, so accept both (nil defaults to :jvm) rather than minting
        ;; a bad keyword
        platform (keyword (str/replace (name (or platform :jvm)) #"^:" ""))
        modish   (re-matches #"[^.\s]+(\.[^.\s]+)*" module)]
    (cond
      (not modish)
      {:error (str "modules are the first TWO segments of a namespace"
                   " (\"logi.parcel\", not \"logi.parcel.impl\") — got "
                   (pr-str module))}

      ;; RETIRE, matching module_dep and module_purity. Declaring :jvm is not
      ;; the same statement as making no claim: an explicit :jvm on a
      ;; namespace nobody compiles is noise a register view has to carry.
      remove
      (if (contains? (:module-platforms (:store @session)) module)
        (let [st' (engine/commit-appended!
                   session
                   #(first (store/record-module-platform % module nil :action :remove
                                                        :prompt prompt :agent agent))
                   [])]
          {:module module :action :removed :platforms (:module-platforms st')})
        {:error (str module " has no platform declaration — nothing to remove."
                     " Undeclared already means :jvm.")})

      (not (#{:jvm :cljc :cljs} platform))
      {:error (str "platform must be :jvm, :cljc, or :cljs — got "
                   (pr-str platform)
                   ". :jvm = Clojure on the JVM (default); :cljc = portable"
                   " (loads on the JVM AND compiles to JS); :cljs = ClojureScript"
                   " only (compiled to JS, never loaded into the oracle).")}

      :else
      ;; `already` is read BEFORE the commit advances the session — bound
      ;; after it, the diff compares the new store to itself and the report
      ;; is empty forever (caught in review of this very change).
      (let [already  (when (= :cljs platform)
                       (set (map :page (rules.webapp/stranded-pages (:store @session)))))
            st'      (engine/commit-appended!
                      session
                      #(first (store/record-module-platform % module platform
                                                            :prompt prompt :agent agent))
                      [])
            ;; only pages THIS declaration stranded — a standing stranding
            ;; belongs to the declaration that caused it, and re-reporting it
            ;; on every later one buries the new fact under the old (B-F6)
            ;; NOT clojure.core/remove — the :remove kwarg SHADOWS it here, and
            ;; calling the shadow is an NPE on every :cljs declaration; the
            ;; whole external tier went red on it in one theme
            stranded (when (= :cljs platform)
                       (seq (filter #(not (contains? already (:page %)))
                                    (rules.webapp/stranded-pages st'))))]
        ;; This verb checks NOTHING about the code — it records a routing fact.
        ;; Whether a :cljc/:cljs namespace actually compiles for its declared
        ;; platform is compile_client's answer, and it can arrive much later.
        ;; An empty :verified is the honest shape (D-surface-honesty).
        (cond-> {:module module :platform platform
                 :platforms (:module-platforms st')
                 :verified []
                 :unverified [:compilation]
                 :note (str "the platform is RECORDED, not verified: nothing here checks"
                            " that " module " compiles for :" (name platform)
                            ". compile_client is what proves it, and its warnings anchor"
                            " to the owning form.")}
          stranded (assoc :stranded-pages (vec stranded)
                          :warning (str "this declaration leaves "
                                        (count stranded)
                                        " ^:app/entry entr"
                                        (if (= 1 (count stranded)) "y" "ies")
                                        " unreachable from a JVM — the closure now"
                                        " reaches :cljs, so the screen tool and every"
                                        " headless test fall back to lookalikes."
                                        " Move or split what the page reaches.")))))))

(defn- shadow-warning
  "A warning when `ns-sym` names a namespace a CLASSPATH resource already owns
   — slopp's own code, or a dependency's — and the store does not yet define it.

   Why it warns and does not refuse: overriding a slopp namespace is a
   supported capability. `slopp.image.testmain` is how a store supplies its own
   trace runner, and `build!` materializes the store's version over slopp's. A
   refusal would break a documented extension point to prevent a naming
   mistake.

   Why it warns at all: `slopp.kernel.boot` loads store namespaces BEFORE slopp's own,
   so a shadowing namespace that does not define everything the real one does
   breaks the server at its next boot — and the store is then unopenable by the
   only tool that could remove it. That happened: a project defined
   `slopp.review.views` with two of its own views, and the next boot died on
   `No such var: views/module-graph`.

   The check is CLASSPATH ownership, not `find-ns`: a namespace an earlier
   store hot-loaded into this process is interned here too, and treating that
   as a collision false-flags a fresh store reusing a name."
  [store ns-sym]
  (when-not (contains? (:namespaces store) ns-sym)
    (let [base (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/"))]
      (when (some #(io/resource (str base %)) [".clj" ".cljc" ".cljs"])
        {:kind :shadows-classpath-ns
         :ns ns-sym
         :message (str ns-sym " SHADOWS a namespace already on the classpath "
                       "(slopp's own, or a dependency's). Your store's version "
                       "loads FIRST, so anything the real one defines and yours "
                       "does not will break at the next server boot — and a "
                       "store that cannot boot cannot be edited. Deliberate "
                       "overrides are fine (slopp.image.testmain is one); if "
                       "this was not deliberate, pick a name your project owns.")}))))

;; --- query.* (read) ---

;; --- verification (D1 tracing + D5 restart-as-diagnostic) ---

;; --- edit.* / runtime ---

;; ---------------------------------------------------------------------------
;; External dependencies (Tier 1) — the per-store manifest

;; --- Phase 4 m3: branches within one repo -------------------------------
(defn module-tier!
  "Declare a module's purity TIER — the functional-core gate's dial (D9):
  :pure (referentially transparent), :internal (may mutate IN-PROCESS state
  only — a memo, a registry), :external (IO — the periphery; unrestricted).
  Legacy spellings :reads/:effects are accepted and stored canonically as
  :internal/:external. One :module-tier delta carrying its why (:prompt); last
  write per module wins. Declaring :external (or never declaring) leaves a
  module ungated. Read tiers via query_depends {modules true}."
  [session module tier & {:keys [prompt agent remove]}]
  (let [module (str module)
        ;; every surface — this docstring, the tool description, query_depends'
        ;; output — spells tiers WITH the colon, so accept that spelling too
        ;; rather than turning ":pure" into ::pure and refusing it
        tier   (tiers/canonical-tier
                (keyword (str/replace (name (or tier "")) #"^:" "")))
        ;; namespace grain, not just module grain: a pure CORE routinely lives
        ;; one level below an effectful module (slopp.api holds seven fully-pure
        ;; namespaces). At module grain that core cannot be named, so nothing
        ;; enforces it — and the tier's whole job is to make agents MOVE code
        ;; into core/shell shape, which it cannot do if it cannot describe it.
        modish (re-matches #"[^.\s]+(\.[^.\s]+)*" module)]
    (cond
      (not modish)
      {:error (str "modules are the first TWO segments of a namespace"
                   " (\"logi.parcel\", not \"logi.parcel.impl\") — got "
                   (pr-str module))}

      ;; RETIRE, matching module_dep's `remove: true`. The store op has always
      ;; supported this (ns_rename needs it, so an orphaned declaration does
      ;; not outlive its namespace); nothing exposed it, so a mis-declared
      ;; tier could be overwritten but never withdrawn — and overwriting with
      ;; :external is not the same statement as making no claim at all.
      remove
      (if (contains? (:module-tiers (:store @session)) module)
        (let [st' (engine/commit-appended!
                   session
                   #(first (store/record-module-tier % module nil :action :remove
                                                    :prompt prompt :agent agent))
                   [])]
          {:module module :action :removed :tiers (:module-tiers st')})
        {:error (str module " has no tier declaration — nothing to remove."
                     " Undeclared already means :external (ungated).")})

      (not (#{:pure :internal :external} tier))
      {:error (str "tier must be :pure, :internal, or :external — got "
                   (pr-str tier)
                   ". :pure = referentially transparent; :internal = may mutate"
                   " IN-PROCESS state (a memo, a registry) but touches nothing"
                   " outside; :external = IO (files, subprocesses, network, db)."
                   " (:reads and :effects are legacy spellings of :internal and"
                   " :external.)")}

      ;; a tier is an ASSERTION ABOUT THE CODE, so check it against the
      ;; code. Gating only future writes let :pure land on a module full of
      ;; effects — a marker that lies, which is worse than no marker.
      :else
      (let [bad (tiers/tier-violations (:store @session) module tier)]
        (if (seq bad)
          {:error (str "cannot declare " module " :" (name tier) " — "
                       (count bad) " existing form(s) already exceed it: "
                       (str/join ", " (map (comp str :form) (take 5 bad)))
                       (when (> (count bad) 5)
                         (str " (+" (- (count bad) 5) " more)"))
                       ". Move the effects to a periphery namespace, or declare"
                       " a looser tier. The first: " (:why (first bad)))
           :violations (mapv :form bad)}
          (let [st' (engine/commit-appended!
                     session
                     #(first (store/record-module-tier % module tier
                                                       :prompt prompt :agent agent))
                     [])]
            ;; D-surface-honesty at declaration grain. This call checked the FORMS
            ;; in the governed namespaces and nothing else. Layering — does this
            ;; namespace REQUIRE a looser tier? — is deliberately not checked
            ;; here (its verdict changes as legitimate work continues, which is
            ;; the D-rule-grain test for a check that does not belong at write
            ;; grain). The omission is fine; being silent about it is not.
            {:module module :tier tier
             :tiers (:module-tiers st')
             :verified (if (= :external tier) [] [:forms])
             :unverified [:layering]
             :note (if (= :external tier)
                     (str ":external asserts nothing about the code, so nothing"
                          " was checked. Layering — whether these namespaces"
                          " require a LOOSER tier — is a whole-graph property"
                          " and is reported by full_check.")
                     (str "the forms already in " module " were checked against :"
                          (name tier) ". Layering — whether they require a LOOSER"
                          " tier — is a whole-graph property reported by"
                          " full_check, not at write grain."))}))))))

(defn module-dep!
  "Declare or retract ONE module dependency edge — the semantic verb behind
  the module manifest (there is no file to edit): each call is one
  :module-edge delta carrying its why (:prompt). Adds are refused when the
  resulting graph would contain a cycle; the response carries the module's
  folded dep set and, when any exists, the store's remaining :violations
  debt.

  The cycle question is asked of PRODUCTION edges — the same graph
  query_depends draws its layers from. A `-test` namespace folds into its
  subject's module, so a fixture require manufactures an edge no production
  namespace has, and judging against those refused architecture that is
  genuinely acyclic while the architecture view showed a clean DAG.

  `test-only` declares the edge for the module's `-test` namespaces ONLY —
  a separate relation, so production code under `from` is still refused. It
  is not a production edge, so it is not cycle-checked: an advisory's test
  has to write code and call `done!` to see the advisory fire, which means
  the fixture necessarily calls the operation surface that calls the rules.
  Without this the only options were to move the test away from its subject
  or to carry a violation forever."
  [session from to & {:keys [remove prompt agent test-only]}]
  (let [st       (:store @session)
        manifest (or (if test-only
                       (edit.modules/module-test-manifest st)
                       (edit.modules/modules-manifest st))
                     {})
        from     (str from)
        to       (str to)
        modish   #(re-matches #"[^.\s]+(\.[^.\s]+)?" %)
        action   (if remove :remove :add)]
    (cond
      (not (and (modish from) (modish to)))
      {:error (str "modules are the first TWO segments of a namespace"
                   " (\"logi.parcel\", not \"logi.parcel.impl\") — got "
                   (pr-str [from to]))}

      (= from to)
      {:error "a module never declares itself"}

      (and remove (not (contains? (get manifest from #{}) to)))
      {:error (str from " does not declare " to
                   (when test-only " as a test-only edge")
                   " — nothing to remove")}

      (and (not remove) (contains? (get manifest from #{}) to))
      (cond-> {:from from :to to :action action :already-declared true
               :deps (vec (sort (get manifest from)))}
        test-only (assoc :test-only true))

      :else
      (if-let [back (and (not remove)
                         (not test-only)     ; a test edge is not a production
                                             ; edge, so it cannot close a
                                             ; production cycle — that is the
                                             ; whole reason the relation is
                                             ; separate
                         ;; PRODUCTION edges — the same graph query_depends draws
                         ;; its layers from. A `-test` namespace folds into its
                         ;; subject's module, so a fixture require manufactures an
                         ;; edge no production namespace has; judging against those
                         ;; refused architecture that is genuinely acyclic while the
                         ;; architecture view showed a clean DAG.
                         (store/module-path
                          (read.modules/production-manifest
                           (:store @session)
                           (edit.modules/module-usage-rows (:store @session)))
                          to from))]
        {:error (str "that edge CLOSES a dependency cycle: "
                     (clojure.string/join " → " (conj back to))
                     ;; The generic advice — extract the shared piece — cannot be
                     ;; followed when the only thing reaching across is a TEST,
                     ;; and that case is common enough to name: a regroup moves a
                     ;; `-test` namespace into a new module while its fixture
                     ;; still drives the operation surface. Say what is actually
                     ;; in the way, computed rather than guessed.
                     (let [reachers
                           (sort (distinct
                                  (for [r (edit.modules/module-usage-rows (:store @session))
                                        :when (and (= from (edit.modules/module-of (:from-ns r)))
                                                   (= to (edit.modules/module-of (:to r))))]
                                    (:from-ns r))))]
                       (if (and (seq reachers)
                                (every? #(clojure.string/ends-with? (str %) "-test")
                                        reachers))
                         (str " — but every namespace under " from " that reaches "
                              to " is a TEST ("
                              (clojure.string/join ", " reachers) "), so declare"
                              " it {test-only true}: that binds the fixtures"
                              " WITHOUT licensing production code under " from
                              " to cross, and a test-only edge is not a"
                              " production edge, so it closes no cycle")
                         (str " — point the dependency one way (usually by"
                              " extracting the shared piece into a module both"
                              " sides may depend on)"))))}
        (let [st'  (engine/commit-appended!
                    session
                    #(first (store/record-module-edge % from to action
                                                      :prompt prompt
                                                      :agent agent
                                                      :test-only test-only))
                    [])
              debt (read.modules/module-debt st')
              ;; what this call checked, and what it did not: the cycle
              ;; question is real and was asked, but whether anything USES the
              ;; edge is a different question with a different owner.
              axes (str (if test-only
                          (str "a test-only edge is NOT a production edge, so no"
                               " cycle question applies to it — and production"
                               " code under " from " is still refused. ")
                          "cycles were judged over PRODUCTION edges. ")
                        "Whether anything USES this edge is not checked here —"
                        " query_depends {modules true} reports :unused-edges.")]
          (cond-> {:from from :to to :action action
                   :verified (if test-only [] [:cycles])
                   :unverified [:usage] :note axes
                   :deps (vec (sort (get-in st' [(if test-only
                                                   :module-test-edges
                                                   :modules)
                                                 from])))}
            test-only (assoc :test-only true)
            debt
            (assoc :violations debt
                   :note (str "existing debt under this manifest — writes"
                              " touching these forms stay blocked until the"
                              " edge is declared or the call restructured. "
                              axes))))))))

(defn- sweep-left-behind
  "Forms still binding `kname` through a `from-ns`-qualified `:keys`
  destructuring — what a keyword sweep did not reach.

  Read off the store AFTER the write, over the OLD key: whatever still names
  it was, by construction, not rewritten. That is a reality check rather than
  a claim about what the changeset meant to do, and it is the same discipline
  `ns_rename`'s `:left-behind` runs on.

  Text-prefiltered on the entry's own spelling before parsing, because this
  runs over every form in the store and the entry cannot be bound without
  being written."
  [st kname from-ns]
  (let [entry (str (refactor/keys-entry from-ns))]
    (vec (for [nsx (sort (keys (:namespaces st)))
               e   (store/forms st nsx)
               :when (:name e)
               :let [src (n/string (:node e))]
               :when (and (str/includes? src entry)
                          (str/includes? src kname)
                          (refactor/destructures-key? src kname from-ns))]
           {:ns nsx :form (:name e) :via :destructuring
            :text (str "{" entry " [" kname "]}")}))))

(defn delete-ns!
  "Remove namespace `ns-sym` from the store — the retirement half creation
  never had (a mistaken scaffold used to ride every projection and build
  forever). Refuses while the namespace holds any form beyond its ns decl
  (delete those first — each deletion individually verified) or while any
  other namespace still requires it (ns_remove_require first). On success:
  one :ns-delete delta (id returned), element rows cleared by the persist,
  and the image drops the namespace so a stale require fails fast."
  [session ns-sym & {:keys [prompt agent]}]
  (let [st (:store @session)]
    (cond
      (nil? (get-in st [:namespaces ns-sym]))
      {:error (str "no namespace " ns-sym)}

      ;; STRUCTURAL emptiness (review S-F2): a def whose name equals the ns
      ;; symbol is not the ns decl and must still block deletion
      (seq (store/body-forms st ns-sym))
      (let [held (keep :name (store/body-forms st ns-sym))]
        {:error (str ns-sym " still holds " (count (store/body-forms st ns-sym))
                     " form(s)"
                     (when (seq held) (str " (" (str/join ", " held) ")"))
                     " — delete them first (edit_delete_form); ns_delete"
                     " removes only an empty namespace")})

      :else
      (let [requirers (vec (sort (for [other (keys (:namespaces st))
                                       :when (and (not= other ns-sym)
                                                  (some #{ns-sym}
                                                        (store/ns-requires st other)))]
                                   other)))]
        (if (seq requirers)
          {:error (str ns-sym " is still required by " (str/join ", " requirers)
                       " — ns_remove_require them first")}
          (let [st'  (engine/commit-appended!
                      session
                      #(first (store/record-ns-delete % ns-sym :prompt prompt :agent agent))
                      [ns-sym])
                did  (:head st')
                ;; A tier or platform describes a NAME — ns-rename! carries them
                ;; across for exactly this reason. Left behind by a DELETE, one
                ;; names a namespace that no longer exists and query_depends
                ;; lists it as though it governed something. The exception is a
                ;; declaration a deeper namespace still lives under: that one
                ;; governs live code by prefix, and retiring it would ungate it.
                path    (str ns-sym)
                governs (some #(str/starts-with? (str %) (str path "."))
                              (keys (:namespaces st')))
                orphans (when-not governs
                          (vec (for [[reg record] store/ns-grained-registers
                                     :when (contains? (get st' reg) path)]
                                 [reg record])))]
            (when (seq orphans)
              (engine/commit-appended!
               session
               (fn [base]
                 (reduce (fn [s [_ record]]
                           (first (record s path nil :action :remove
                                          :prompt (str "declaration retired with namespace " ns-sym)
                                          :agent agent)))
                         base orphans))
               []))
            (repl/eval! (:image @session)
                        (format "(remove-ns '%s)" ns-sym))
            (cond-> {:deleted (str ns-sym) :delta did}
              (seq orphans) (assoc :retired (mapv first orphans)))))))))

(defn module-extract!
  "Pull `ns-syms` (each with its subtree and `-test` siblings) under
  `to-prefix` — the module-grain regroup, as ONE intent.

  Order is the design, not a detail. A namespace that moves from two segments
  to three becomes package-private, so every caller outside the new parent
  becomes a module violation the instant the rename lands. The vars the plan
  named are therefore hoisted FIRST; only then do the renames run, and the
  edges are declared LAST from what the store actually references — by then
  `ns-rename!` has already re-keyed the manifest for whole modules that moved,
  so replaying the plan's pre-rename edge list would re-declare them.

  `dry-run` returns the plan and writes nothing — what can be extracted, what
  must be exported and who forces it, which edges appear. Refuses when the
  regroup would leave a production module cycle."
  [session ns-syms to-prefix & {:keys [prompt agent dry-run]}]
  (let [st   (:store @session)
        plan (refactor/module-extract-plan st ns-syms to-prefix)]
    (cond
      (:error plan) (select-keys plan [:error])

      dry-run {:plan plan}

      (empty? (:renames plan))
      {:error (str "no namespace matches " (pr-str ns-syms)
                   " — name the namespaces to pull under " to-prefix
                   " (subtrees and -test siblings follow on their own)")}

      :else
      (let [why (or prompt (str "extract " (str/join ", " ns-syms)
                                " under " to-prefix))
            inv (into {} (map (fn [[k v]] [v k])) (:renames plan))
            cur (mapv (fn [e] {:ns (get inv (:ns e) (:ns e)) :name (:name e)})
                      (:exports plan))
            cs  (refactor/export-changeset st cur)]
        ;; 1. hoist first — never leave an intermediate store the gate refuses
        (when (seq cs)
          (let [[st1 _] (store/apply-changeset st :module-extract
                                               (symbol (str to-prefix)) cs
                                               :prompt why :agent agent)]
            (engine/try-commit! session st st1
                                 (vec (distinct (map :ns cur))))))
        ;; 2. the renames, each carrying its own manifest follow + verification
        ;; every rename DEFERS its verification to this transaction. Mid-batch the
        ;; store has namespaces renamed and callers not yet rewritten, so a
        ;; verification between steps is meaningless as well as expensive — the
        ;; only bar a batch can meet is that its END STATE is green. A
        ;; three-namespace extraction used to run past 465s paying N of them.
        (let [done (reduce (fn [acc [o v]]
                             (let [r (ns-rename! session o v
                                                 :prompt why :agent agent
                                                 :defer-verify true)]
                               (if (:error r)
                                 (reduced {:error (str "renaming " o " → " v ": "
                                                       (:error r))
                                           :landed (:pairs acc)})
                                 (cond-> (-> acc
                                             (update :pairs conj [o v])
                                             (update :nses into (:verify-nses r)))
                                   (:left-behind r)
                                   (update :left-behind (fnil conj [])
                                           (select-keys r [:renamed :left-behind :note]))))))
                           {:pairs [] :nses #{}}
                           (sort-by first (:renames plan)))]
          (if (:error done)
            done
            ;; 3. the edges reality now requires, derived from the moved store
            (let [st'      (:store @session)
                  manifest (or (edit.modules/modules-manifest st') {})
                  tmanif   (edit.modules/module-test-manifest st')
                  derived  (edit.modules/derive-module-edges st')
                  ;; a fixture's crossing gets declared FOR FIXTURES — deriving
                  ;; it as a production edge is how a regroup silently widens
                  ;; the graph it was meant to tidy
                  gap      (fn [kind have]
                             (vec (sort (for [[m deps] (get derived kind)
                                              d deps
                                              :when (not (contains? (get have m #{}) d))]
                                          [m d]))))
                  missing   (gap :production manifest)
                  missing-t (gap :test tmanif)]
              (when (or (seq missing) (seq missing-t))
                (engine/commit-appended!
                 session
                 (fn [base]
                   (as-> base $
                     (reduce (fn [s [a b]]
                               (first (store/record-module-edge s a b :add
                                                                :prompt why :agent agent)))
                             $ missing)
                     (reduce (fn [s [a b]]
                               (first (store/record-module-edge s a b :add
                                                                :test-only true
                                                                :prompt why :agent agent)))
                             $ missing-t)))
                 []))
              ;; 4. ONE verification, strictly after the whole set has landed
              ;; AND after the edges are declared — verifying earlier would
              ;; judge a store the gate itself would refuse.
              (let [vn      (vec (sort (remove (set (map first (:pairs done)))
                                               (:nses done))))
                    summary (engine/run-verification! session vn nil)]
                (engine/commit-appended!
                 session #(store/record-verification % vn summary) [])
                (cond-> {:extracted {:to (symbol (str to-prefix))
                                     :renames (into (sorted-map) (:renames plan))
                                     :exported (count cs)
                                     :edges-declared missing}
                         :test summary}
                  (seq missing-t)
                  (assoc-in [:extracted :test-edges-declared] missing-t)
                  (seq (:left-behind done))
                  (assoc :left-behind (:left-behind done)
                         :note (str (count (:left-behind done)) " of the renamed"
                                    " namespaces left occurrences of their old name"
                                    " behind — strings and -test siblings the symbol"
                                    " rewrite cannot reach. Judge each.")))))))))))

(defn js-dep!
  "Vendor and declare a JavaScript library `js-name` (a string like
  \"roughjs\"), or retract it with `:remove`.

  The third dependency world. Nothing is resolved — there is no npm client
  here — so `:source` names the bytes on disk and this does the rest in one
  act: writes them to the content-addressed cache, records a `:download`
  recipe built from the npm coordinate, and declares the library.

  It is one call rather than two (`file_put` then declare) because a library
  is DERIVED, and the recipe is what makes it recoverable. That also deletes
  a refusal: you can no longer declare a library whose bytes were never
  vendored, because declaring IS vendoring. This mirrors importmap-rails'
  `pin --download`.

  `spec` wants `{:version :format :global :file}` plus provenance — `:npm`
  (`\"roughjs@4.6.6\"`), `:npm-path` (which file inside the package),
  `:integrity` (the registry's own hash) and `:license`. Anchor to the
  REGISTRY, not to a CDN url: npm versions are immutable, so the coordinate
  is re-fetchable and verifiable, where a delivery url only records how the
  bytes arrived once.

  `:format` is `:iife`/`:umd` (concatenated into the bundle via `deps.cljs`
  `:foreign-libs`) or `:esm` (loaded by the page). A typo must fail here
  rather than as a silent no-op at compile time.

  Returns {:declared name :sha ...} | {:error}."
  [session js-name spec & {:keys [agent prompt remove source]}]
  (cond
    (not (string? js-name))
    {:error "js dependency name must be a string like \"roughjs\""}

    remove
    (do (engine/commit-appended!
         session
         #(first (store/record-js-dep % js-name nil :agent agent :prompt prompt
                                      :remove true))
         [])
        {:retracted js-name})

    (not (contains? #{:iife :umd :esm} (:format spec)))
    {:error (str "js dependency format must be :iife, :umd or :esm — got "
                 (pr-str (:format spec))
                 ". :iife/:umd concatenate into the bundle; :esm is loaded by"
                 " the page and shimmed to a global.")}

    (and (not= :esm (:format spec)) (not (seq (str (:global spec)))))
    {:error (str ":iife/:umd libraries must name the :global they set"
                 " (roughjs sets \"rough\") — that is what :global-exports"
                 " maps a require onto.")}

    (not (and source (.exists (java.io.File. (str source)))))
    {:error (str "js_dep needs :source — a path to the library's bytes on disk."
                 " Declaring IS vendoring: there is no npm client here, so the"
                 " bytes have to come from somewhere you already fetched them.")}

    (str/blank? (str (:file spec)))
    {:error "js dependency needs a :file — where the library sits in the project tree"}

    :else
    (let [bs     (java.nio.file.Files/readAllBytes
                  (.toPath (java.io.File. (str source))))
          recipe (cond-> {:kind :download}
                   (:npm spec)       (assoc :npm (:npm spec))
                   (:npm-path spec)  (assoc :npm-path (:npm-path spec))
                   (:integrity spec) (assoc :integrity (:integrity spec))
                   (:source-url spec) (assoc :source-url (:source-url spec)))
          entry  (artifacts/put! (:dir @session) bs recipe
                                 :content-type "application/javascript")
          spec'  (assoc spec :sha (:sha entry))]
      (let [prior (get-in @session [:store :artifacts (str (:file spec)) :sha])]
        (engine/commit-appended!
         session
         (fn [s] (-> s
                     (as-> s' (first (store/record-artifact s' (:file spec) entry
                                                            :agent agent :prompt prompt)))
                     (as-> s' (first (store/record-js-dep s' js-name spec'
                                                          :agent agent :prompt prompt)))))
         [])
        ;; re-vendoring at a new version strands the old bytes exactly as a
        ;; recompile does
        (artifacts/prune-superseded! (:dir @session) (:store @session) prior))
      {:declared js-name :version (:version spec) :format (:format spec)
       :file (:file spec) :sha (:sha entry) :bytes (alength bs)})))

(defn- unwritten-requires
  "The namespaces `requires` names that this store does not have YET and that
  belong to it — same root segment as `ns-sym`.

  This is the NAMESPACE grain of the red-first seam. `add-form!` interns a
  throwing stub for an unimplemented VAR so a spec lands as an honest red; a
  spec requiring an unimplemented NAMESPACE had no equivalent and failed to
  LOAD, which is a refusal rather than a failing test. On a new project every
  namespace is the first one, so the discipline broke exactly where it matters
  most, and the workaround was hand-written throwing stubs — the thing the seam
  exists to remove.

  An empty namespace is that stub, and it is a real store write rather than an
  image trick: the author is going to create it anyway, and its EXISTENCE was
  never the thing under test.

  **The root-segment test is what makes inventing a namespace safe**, and it is
  the whole guard. A require naming a LIBRARY must never conjure an empty
  namespace over it — the real one would be shadowed and every call would
  resolve to nothing, which is a worse failure than the refusal this replaces,
  and a silent one. A require sharing this namespace's own root is code the
  author is about to write; anything else belongs to somebody else.

  A clause names its namespace FIRST, in every shape `:require` accepts, so the
  name is taken by pattern rather than by reading — a clause string is data
  arriving from a caller and parsing it should not be able to run anything."
  [store ns-sym requires]
  (let [root  (fn [n] (first (str/split (str n) #"\.")))
        mine  (root ns-sym)
        known (set (keys (:namespaces store)))]
    (vec (distinct
          (for [c requires
                :let [m (re-find #"^\s*\[?\s*([A-Za-z][A-Za-z0-9_.*+!?<>=$%&|'-]*)" (str c))
                      n (some-> m second symbol)]
                :when (and n
                           (not (contains? known n))
                           (= mine (root n)))]
            n)))))

(defn- sweep-patterns-left-behind
  "Forms holding a regex literal that names `from` with escaped dots — what a
  textual sweep could not see, let alone rewrite.

  Read off the store over EVERY form rather than only the ones the sweep
  touched, because a form whose sole occurrence is inside a pattern was never
  in the changeset at all — which is precisely how one of these went a whole
  wave unnoticed while its siblings were caught by tests.

  Text-prefiltered on `#\\\"` before parsing, since this runs store-wide and a
  regex literal cannot exist without being written."
  [st from pat]
  (vec (for [nsx (sort (keys (:namespaces st)))
             e   (store/forms st nsx)
             :when (:name e)
             :let [src (n/string (:node e))]
             :when (str/includes? src "#\"")
             txt (refactor/patterns-not-swept src from pat)]
         {:ns nsx :form (:name e) :via :regex :text txt})))

(defn- sweep-note
  "One note for everything a sweep did not do, so a reader gets a single
  sentence per cause rather than whichever one happened to be merged last.

  Composed rather than branched because the causes are independent — a sweep
  can leave a destructuring AND a pattern AND rewrite prose in the same call —
  and the previous shape put all three under one `:note` key where the last
  writer won."
  [from left strings?]
  (let [via (set (map :via left))]
    (not-empty
     (str/join " "
               (remove nil?
                       [(when strings?
                          (str "string-literal hits REVIEW FIRST: a sweep rewrites"
                               " text inside strings, so a test fixture can be left"
                               " self-inconsistent."))
                        (when (via :destructuring)
                          (str "these destructurings still name " from " and were"
                               " NOT rewritten: a :keys entry binds the key's NAME"
                               " as a local the body reads, so only the QUALIFIER"
                               " can be moved for you."))
                        (when (via :regex)
                          (str "these REGEX literals still name " from " and were"
                               " NOT rewritten: a pattern spells the name with"
                               " escaped dots, which shares no literal text with"
                               " the token — and whether a `.` in one is a"
                               " separator or a wildcard is a question about what"
                               " the author meant, not something to guess. Read"
                               " each one."))])))))

(defn ^:export otel-measurements
  "Every harness-telemetry batch this store has recorded, as the payloads
  [[record-otel!]] wrote — `[{:requests [...]}, ...]`, oldest first. Empty for
  a session with no journal, because an ephemeral store keeps no
  measurements. `:since` (a delta id) windows to batches recorded AFTER that
  delta, by the row's timestamp against the delta's `:at` — a measurement
  has no position in the journal.

  An id the journal does not carry is REFUSED. It used to leave the floor
  nil, and a nil floor reads as no-window-asked-for — every batch in the
  store. The journal half of the same call windows to nothing, so the reply
  said zero turns beside an all-time bill and nothing in it looked wrong.

  The read TWIN of the writer, and it lives here for the same reason the
  writer does: measurements are beside the journal rather than in it, and
  opening the journal is IO. `slopp.read.query` is the `query_*` front door
  and is declared `:pure`, so reading this there made its tier a claim it did
  not earn — invisible to every done, because a tier is a whole-store
  question and `full_check` is the only thing that asks. It asked once and
  said so."
  [session & {:keys [since]}]
  (if-let [conn (:db @session)]
    (let [floor (when since
                  (:at (or (db/delta-by-id conn since)
                           (throw (ex-info (str "unknown :since " (pr-str since)
                                                " — no delta in this journal carries"
                                                " that id, and an unknown window is"
                                                " not the same fact as no window")
                                           {:since since})))))]
      (into []
            (comp (filter #(or (nil? floor) (> (:at %) floor)))
                  (map :payload))
            (db/measurements conn "otel" nil)))
    []))

(defn ^:export record-otel!
  "Record `requests` — already normalized by [[slopp.otel/api-requests]] — as
  MEASUREMENTS beside the journal. Returns the count recorded.

  **Not a delta, and this is the whole lesson.** It was one, for about an hour,
  and the store became unwritable: an exporter posts on its own interval from
  an HTTP handler that is not an agent and never pauses, so every few seconds a
  telemetry row moved the head that every writer compare-and-swaps against.
  `full_check` lost first because it is slowest — three to four minutes of
  whole-store verification computed and then discarded, four times — and
  ordinary writes started failing behind it.

  The interval was never the bug. ANY interval makes the head non-quiescent;
  a shorter one only finds out sooner. A number about what something cost is
  not an entry in the history, and writing one must not be able to make a
  verdict lose a race.

  One row per received batch, the grain the exporter already batches at.
  Nothing is recorded for an empty batch: an exporter posts whether or not
  anything happened, and a row per empty tick would fill the table with
  evidence that nothing occurred.

  Silent when the session has no db — an ephemeral store keeps no measurements,
  and telemetry is the last thing that should fail somebody's request."
  [session requests]
  (let [rs (vec requests)]
    (when (seq rs)
      (when-let [conn (:db @session)]
        (db/record-measurement! conn "otel" nil {:requests rs})))
    (count rs)))

(defn ^:export record-tool-call!
  "Record one tool call as a MEASUREMENT beside the journal: `{:tool :op :ms
  :chars :chars-in :refused? :agent}`, one row per call. Returns nil.

  The session ring already sees every call, but `turn-end!` folds only a
  turn's five costliest tools onto its delta, so per-call cost was lossy and
  lived in the journal. This is the census — every call, its wall, and
  `:chars`, the characters it put on the wire, which is what every later
  request re-reads and so what a tool COSTS rather than what it spent.
  `:op` is what a cost is ranked by since the surface became families (a row
  that knew only `read` could not rank one read against another), and
  `:chars-in` is the SEND side — the request's size, the output tokens that
  are the wall-side cost (s20).

  A measurement and never a delta, for the reason the table exists: a read
  that moved the head could make a verdict lose its compare-and-swap. Silent
  when the session has no db — an ephemeral store keeps no measurements, and
  accounting is the last thing that should fail somebody's call."
  [session {:keys [tool op start end chars chars-in refused? agent]}]
  (when-let [conn (:db @session)]
    (db/record-measurement! conn "tool-call" nil
                            (cond-> {:tool     tool
                                     :ms       (- (or end 0) (or start 0))
                                     :chars    (or chars 0)
                                     :refused? (boolean refused?)
                                     :agent    (or agent (:agent-id @session))}
                              op       (assoc :op op)
                              chars-in (assoc :chars-in chars-in))))
  nil)

(defn ^:export tool-call-measurements
  "Every `tool-call` row this store has recorded, as the payloads
  [[record-tool-call!]] wrote — `[{:tool :op :ms :chars :chars-in :refused?
  :agent :at} …]`, oldest first, `:at` being the row's own timestamp. Empty
  for a session with no journal.

  `:since` (a delta id) windows to rows recorded AFTER that delta — by the
  row's timestamp against the delta's `:at`, because a measurement is not in
  the journal and has no position in it (s20: `query_cost {since}` windowed
  turns and read records and sat a windowed header over all-time tool
  totals).

  An id the journal does not carry is REFUSED, for the same reason and after
  the same bug one layer down: a nil floor reads as no-window-asked-for,
  which is every row ever recorded.

  The read twin of the writer, here rather than in `slopp.read.query` for
  the reason [[otel-measurements]] is: the front door is declared `:pure`,
  and reading a table is IO."
  [session & {:keys [since]}]
  (if-let [conn (:db @session)]
    (let [floor (when since
                  (:at (or (db/delta-by-id conn since)
                           (throw (ex-info (str "unknown :since " (pr-str since)
                                                " — no delta in this journal carries"
                                                " that id, and an unknown window is"
                                                " not the same fact as no window")
                                           {:since since})))))]
      (into []
            (comp (filter #(or (nil? floor) (> (:at %) floor)))
                  (map #(assoc (:payload %) :at (:at %))))
            (db/measurements conn "tool-call" nil)))
    []))

(defn ^:export jar-currency
  "What the running ARTIFACT is, placed against this session's store —
  `{:head id}` always, plus `:behind n` when this store is the one it came
  from. Nil `head` → nil.

  `head` is `slopp.kernel.boot/jar-head`'s answer: the store head the jar was
  built from, or nil in a process that is not running one.

  **`:behind` is ABSENT rather than 0 when the head is foreign**, and this is
  the case the obvious version gets wrong. slopp's jar serves projects that are
  not slopp, so a head from one store and a delta log from another share
  nothing; counting deltas after that head in THIS log would measure how fast
  the reader has been writing and report it as the tool's age. The identity
  still travels, because it is exactly what a human compares by hand across
  two stores — which is how the six incidents behind this were eventually
  solved, expensively.

  Two indexed reads — is the head on this line, and how many code deltas
  followed it — and the count is `slopp.store.db/code-deltas-after`, the one
  spelling every currency number shares."
  [session head]
  (when head
    (let [conn (:db @session)
          line (engine/session-line session)]
      (cond-> {:head head}
        (db/on-line? conn line head)
        (assoc :behind (db/code-deltas-after conn line {:id head}))))))

(defn ^:export app-behind
  "How many CODE changes the SERVED app image is behind the store — `0` when
  it is current, `nil` when there is no answer. `running` is the session's
  app-server map.

  This is the HOST-CURRENCY question one image over, so it is deliberately
  the same count — `slopp.store.db/code-deltas-after`, by the clock the image
  was served at. Markers never count: they are 8383 of one store's ~17400
  deltas and `:verify` alone 6441, because every write appends one; a raw
  count would report roughly twice the changes anyone made, and a number that
  overstates is a number people stop reading.

  **`0` is an answer and must be reported.** The question is \"is the page I
  am about to look at built from what I just wrote?\", and staying silent when
  the answer is yes puts the reader back to hand-checking. Silence is reserved
  for \"nothing is serving\", which is most stores.

  **A running map with no `:served-at` answers nil.** The caller has already
  established something IS serving, so an absent stamp is a slopp bug rather
  than a stale app — and reporting a freshly-served app as maximally behind
  would send someone to re-serve a thing that is already right. Reported by
  slopp-ui, twice; the second bite was a restyled page whose served
  stylesheet was still the old one."
  [session running]
  (when (:serving? running)
    (when-let [at (:served-at running)]
      (db/code-deltas-after (:db @session) (engine/session-line session) {:at at}))))

(defn ^:export with-history
  "`session` with its store value HYDRATED: `:deltas` is the line's whole
  journal (`slopp.store.db/line-deltas`), read now — or, with `:ops`, only
  the deltas of those kinds (`[:commit]` for a commit-point list), which is the
  difference between a few dozen rows and the whole log for readers that
  want one kind of marker — and `:git-origin` `{:sha :remote}` when the store
  was imported from git (the `git-base-sha` / `git-remote` meta rows), so an
  imported form's first version can say where it came from. Returns a NEW
  atom over a copy of the session — the live session never carries the
  list. A session with no journal (a bare test fixture over a value built by
  writes) is returned as it is: its value's own `:deltas` is all the history
  there is.

  The history views (`query_history`, `query_changes`, a commit-point's status,
  an undo span) are pure over a store value and read `store/deltas`. The
  value stopped carrying the log — it was 94% of every session's memory and
  the cost of every open, read for two scalars and a bounded window — so the
  few readers that genuinely walk all of it are handed a value that has it,
  at the moment they are asked, and only then."
  [session & {:keys [ops]}]
  (let [s @session]
    (if-let [conn (:db s)]
      (atom (update s :store assoc
                    :deltas (db/line-deltas conn (engine/session-line session) :ops ops)
                    :git-origin (when-let [sha (db/get-meta conn "git-base-sha")]
                                  {:sha sha :remote (db/get-meta conn "git-remote")})))
      session)))

^:reads (defn query-commits
  "Commit-points, newest first:
  [{:commit :description :target :status :agent :at :sha}]. The list rung
  carries each description's TITLE LINE only (+ :more-lines when a body
  follows) — needing one sha used to fetch five whole commit-point essays;
  `:commit \"dN\"` returns that ONE commit-point with its full description.
  Commit `:target` ids plug straight into query-changes :from/:to for
  between-commit-point diffs. `:sha` (P4-m8) is the commit-point's git commit id —
  present once the git projection has minted it (imported markers carry
  theirs from birth).

  This is `history/commit-point-rows` — a pure fold over the delta log — plus
  the one thing that needs the db: joining the shas the git projection
  pinned. The split is why the reviewer UI can read commit-points at :pure."
  [session & {:keys [commit]}]
  (let [{:keys [dir]} @session
        st   (:store @(with-history session :ops [:commit]))
        shas (when dir
               (try (with-open [conn (db/open! dir)]
                      (db/commit-shas conn))
                    (catch Exception _ nil)))
        join (fn [row]
               (if-let [s (or (:sha row) (get shas (:commit row)))]
                 (assoc row :sha s)
                 row))]
    (if commit
      (some #(when (= (str commit) (:commit %)) (join %))
            (history/commit-point-rows st))
      (mapv join (history/commit-point-rows st :titles-only true)))))

^:reads (defn session-brief
  "THE one-call orientation, task-shaped (knowledge-differential stance):
  breadth stays CHEAP — namespace FAMILIES (≥5 same-prefix siblings) roll
  up to one row, form names ride only for solo nses on small stores — and
  depth arrives WHERE THE ASK POINTS: the session's :last-intent (the
  user's verbatim words, via the prompt hook or turn_begin) seeds the same
  walk `orient` makes over the reference graph, and the top rows ride as
  interface CARDS under :relevant, each with its :via. The agent starts
  working instead of orienting. :host is the serving process's code-currency
  record (orient/host-brief over the kernel's boot-info, reached through the
  late-ref carrier — absent when this process didn't boot from a store):
  which code the host actually runs, and what a restart would change.
  :module-cycles rides only when the manifest has one — impossible to create
  under the gate, so it was inherited at import, and this is the only place
  outside the web UI that says so."
  [session]
  (let [st       (:store @session)
        nss      (sort (keys (:namespaces st)))
        names    (into {} (map (fn [n] [n (vec (remove #{n} (keep :name (store/forms st n))))])) nss)
        total    (reduce + 0 (map (comp count val) names))
        fams     (group-by #(first (str/split (str %) #"\.")) nss)
        project  (vec (mapcat (fn [[seg members]]
                                (if (<= 5 (count members))
                                  [{:family (str seg ".*") :nses (count members)
                                    :forms (reduce + 0 (map (comp count names) members))}]
                                  (for [n members]
                                    (if (< 200 total)
                                      {:ns n :forms (count (names n))}
                                      {:ns n :forms (names n)}))))
                              (sort-by key fams)))
        ms       (->> (query-commits session)
                      (take 2)
                      (mapv #(-> (select-keys % [:commit :description :at :status])
                                 (update :description orient/snip 110))))
        last-done (let [d (db/last-marker (:db @session) (engine/session-line session) :done)]
                    (when (and d (or (= :red (get-in d [:findings :test-status]))
                                     (pos? (get-in d [:findings :lint-errors] 0))))
                      (-> (select-keys d [:label :at :findings])
                          (assoc :note (str "the last done-point left problems —"
                                            " address them or tell the user why not")))))
        ;; the kernel ns exists only in a process that booted from a store
        ;; (the dev server, a jar launch) — reach it through the carrier and
        ;; treat any failure as absence, never an error
        host     (when-let [info (try ((store/late-ref 'slopp.kernel.boot/current-boot-info))
                                      (catch Throwable _ nil))]
                   (orient/host-brief
                    ;; :jar-head is the ARTIFACT's identity; placing it against
                    ;; THIS store is the caller's job, because the store a jar
                    ;; runs against is often not the one it was built from.
                    (cond-> info
                      (:jar-head info)
                      (assoc :jar (jar-currency session (:jar-head info))))
                    ;; ONE spelling of the code-delta count. This was a second
                    ;; copy of code-deltas-since — identical today, and the
                    ;; docstring one namespace over already called itself "the
                    ;; ONLY spelling of it" while this stood beside it. Three
                    ;; artifacts now report staleness with it.
                    (db/code-deltas-after (:db @session) (engine/session-line session)
                                          {:at (:booted-at info 0)})
                    (boolean (when-let [b (:branch @session)]
                               (not= "main" (str b))))
                    ;; MEASURED, not inferred: without this the
                    ;; brief repeats whatever the reload counter
                    ;; believes, which is how it once announced
                    ;; five stale namespaces to a process that
                    ;; held every one of them current.
                    (rules.currency/drift (:image @session) st)))
        ;; DERIVED, never remembered. A cycle is standing debt rather than an
        ;; event, so reading the manifest each time means it survives a
        ;; restart, covers import as well as adoption, and cannot disagree
        ;; with the module graph — one `module-layers`, one answer.
        cycles   (vec (:cycles (store/module-layers (:modules st))))
        ;; one store-wide scan, not two — the cond-> below tests and reports the
        ;; same value
        unread   (orient/unread-declarations st)
        ;; the line this session WRITES to, when it is a private one. Read from
        ;; the session rather than resolved, deliberately: resolving ADOPTS,
        ;; and orientation must not be the thing that creates a workspace.
        ;; The count is measured from the thread's own base, which is the one
        ;; delta guaranteed to be in its log however far the branch has moved.
        thread   (when-let [conn (:db @session)]
                   (when-let [row (and (:line @session)
                                       (first (filter #(= (:line @session) (:id %))
                                                      (db/lines conn))))]
                     (when (= "thread" (:kind row))
                       ;; ONE producer for this number, shared with thread_list and
                       ;; the write hint. It used to be re-derived here by counting
                       ;; the store's deltas past the base, which was a second
                       ;; derivation of the same question AND the same defect: it
                       ;; counted verification records, so a check with nothing
                       ;; written left it non-zero.
                       (let [n (db/unlanded-count conn (:id row) history/content-ops)]
                         (cond-> {:on (:branch @session) :unlanded n}
                           (pos? n)
                           (assoc :note
                                  (str n " change(s) are private to this thread. A green"
                                       " done lands them on " (:branch @session)
                                       "; nothing outside this session — the running"
                                       " host included — can see them until it does.")))))))
        intent   (:last-intent @session)
        ;; the ask's MAP, at a small budget: the same walk `orient` makes over
        ;; the reference graph and the coverage edges, so the brief's
        ;; :relevant is the first six rows of what `orient {ask}` would say
        ;; — seeds first, then what they pull in, each with its :via
        relevant (when (seq (str/trim (str intent)))
                   (->> (:rows (orient/orient-map session :ask intent :tokens 700))
                        (take 6)
                        vec
                        not-empty))]
    ;; no :loop line: it was 472 chars byte-identical in every session of a
    ;; lifetime, re-teaching what the skill said. The brief carries what
    ;; CHANGED and what needs the agent, nothing that is true every time.
    (cond-> {:project project
             ;; what the code means by each lib — the ns-form question, answered once
             :aliases (read.modules/project-aliases (:store @session))}
      (seq ms)   (assoc :commit-points ms)
      last-done  (assoc :last-done last-done)
      ;; A tangle can only have been INHERITED — `module_dep` cycle-checks
      ;; every add, so nothing a store does under the gate can create one.
      ;; That makes this the rarest thing in the brief and the one nobody
      ;; else will mention: adoption reports it once at open and the report
      ;; is discarded, leaving the web UI's module page as the only surface.
      (seq cycles)
      (assoc :module-cycles cycles
             :module-cycles-note
             (str "inherited at import — nothing loads in a circle and the code"
                  " is not broken. A module is the first two segments, so this"
                  " is a cross-module call in each direction. Nothing can add"
                  " to it (an edge that closes a cycle is refused), so it is"
                  " one-time debt: move what crosses, then module_dep"
                  " {from … to … remove true}."))
      host       (assoc :host host)
      thread     (assoc :thread thread)
      ;; what this store DECLARES that this slopp no longer reads. The brief is
      ;; where it belongs because the moment it becomes true is a RESTART onto
      ;; a different artifact — no write happened, so no write-time gate could
      ;; have said it, and the store did not change.
      ;;
      ;; It is the JOIN rather than the finding: `unknown-marker` reports the
      ;; per-form half at done grain, and nobody adds ten of those up. A
      ;; consuming store hit exactly this — every route declaring a retired
      ;; spelling, so nothing registered and everything 404d — and diagnosed
      ;; beat-contract drift from this brief's own `:hub-note`, because the
      ;; fact it needed was not here to read.
      unread     (assoc :unread-declarations unread)
      ;; the reviewer UI, when the server brought one up. It is for a HUMAN,
      ;; and its only other announcement is a line on the server's stderr —
      ;; which most clients never show anyone. Hand the url over when asked
      ;; what is going on, rather than making them know to ask for it.
      (:ui-url @session) (assoc :ui (:ui-url @session))
      ;; …and whether the table behind that url is still the store's. The
      ;; route table and both performer vocabularies are assembled ONCE at
      ;; serve time, so a route added afterwards answers 404 — correctly, for
      ;; the table that listener holds, and indistinguishably from a path that
      ;; does not exist. Same hole `:app-behind` two clauses down was added
      ;; for, on the listener that had no counter.
      ;;
      ;; Nil unless there is genuinely something to doubt: a line announcing
      ;; that everything is fine every time is one a reader learns to skip,
      ;; and this one has to be read on the rare occasion it appears.
      (false? (:current? (slopp.currency/report (:db @session) (:ui-stamp @session))))
      (assoc :ui-stale
             (str "that listener's route table was built at "
                  (:head (:ui-stamp @session))
                  " and this line has moved since — a route added after it came"
                  " up answers 404 until you ui_serve again"))
      ;; the APP slopp is running for this project, when it is running one.
      ;; Its only other announcement is a line on the server's stderr, which
      ;; most clients never show anyone — so an agent asked "what is going
      ;; on" is where a human finds out the app has an address at all.
      (:url (:app-server @session)) (assoc :app (:url (:app-server @session)))
      ;; and what the image cost to come up. It rides HERE rather than only on
      ;; the banner because the comment two lines up is the whole reason: an
      ;; agent asked "what is going on" is where a human finds out. The first
      ;; app to want this number had to watch for the child process and diff
      ;; its bind against its start time — hand-measuring a figure slopp had
      ;; already computed, because the only place it was written was stderr.
      (:boot-ms (:app-server @session))
      (assoc :app-boot-ms (:boot-ms (:app-server @session)))
      ;; and whether that image is built from what you just wrote. `full_check`
      ;; has carried this for a while and the BRIEF is where a reader looks —
      ;; a consumer read this brief through a twenty-minute window in which
      ;; their app served old code, and it said nothing, because the counter
      ;; lived in a different call. Two docstrings meanwhile claimed it was
      ;; here.
      ;;
      ;; 0 is REPORTED, not silenced: the question is "is the page I am about
      ;; to look at built from what I just wrote", and silence on yes puts the
      ;; reader back to hand-checking something slopp knows. Silence is for
      ;; nothing-is-serving, which `behind` answers nil for.
      (some? (app-behind session (:app-server @session)))
      (assoc :app-behind (app-behind session (:app-server @session)))
      ;; and a managed app server that FAILED is not the same as one nobody
      ;; asked for. Silence on both is how "the dev server is broken" reads
      ;; as "this project has no dev server", which sends the reader nowhere.
      (and (:app-server @session) (not (:serving? (:app-server @session))))
      (assoc :app-note (str "slopp is running this project's app server and it"
                            " is DOWN: " (:reason (:app-server @session))))
    ;; the HUB's url when this project registered with one — that is the
    ;; address to hand a human on a machine running several projects, and
    ;; the per-project one above is a derived port nobody should type
    ;; the project's own page ON the hub, and ONLY while a hub is answering:
    ;; the slug in it is minted by the hub and returned on every beat, so
    ;; holding one is the proof we are registered rather than a guess. This
    ;; used to be the configured hub root, set when the beat STARTED and never
    ;; revisited — so a machine with no hub had orientation hand a human a
    ;; connection refused. A hub is optional; absence is an ordinary state and
    ;; has to be sayable.
    (:hub @session) (assoc :hub (:hub @session))
    ;; a hub that REFUSED our beat is a third state, and it must not read as
    ;; the second. The hub validates each check-in against its own copy of the
    ;; beat contract — a hand-maintained twin of ours, because neither store
    ;; can read the other — so this 400 IS the notification that the two
    ;; copies diverged. Called "no hub is answering" it sends someone to check
    ;; whether a hub is running, the one thing that is not wrong.
    (and (not (:hub @session)) (:hub-refused @session))
    (assoc :hub-note
           (str "the hub at " (:hub-configured @session) " REFUSED this"
                " project's check-in with "
                (:hub/refused (:hub-refused @session))
                " — it is running and it rejected what we sent, so this is"
                " ours to fix, not a missing hub. Its explanation: "
                (pr-str (:hub/explain (:hub-refused @session)))
                ". The beat contract crosses the split by COPY"
                " (slopp.hub/project-beat here, its twin over there),"
                " so a refusal is where drift between them surfaces"))

    ;; NOT the refused case — cond-> tests every clause in order, so without
    ;; this guard both fire and the generic note overwrites the specific one
    (and (not (:hub @session))
         (not (:hub-refused @session))
         (:hub-configured @session))
    (assoc :hub-note
           (str "no hub is answering at " (:hub-configured @session)
                " — this project keeps beating, so it appears within one"
                " interval of a hub starting. Start one (the slopp-ui"
                " project) or set the slopp.hub.port capability to 0. Until"
                " then :ui is all there is, and it serves JSON"))
      relevant   (assoc :relevant relevant))))

(defn ^:export journal
  "`session`'s line's whole delta log, oldest first, read now — the list the
  value no longer carries. A session with no journal answers its value's own
  list. For a caller that wants the deltas THEMSELVES (a test counting
  them, a fixture pinning an order); the views over them live in
  `slopp.read.history`, and the write path never needs this."
  [session]
  (store/deltas (:store @(with-history session))))

(defn ^:export refresh-index!
  "Bring the session's reference index current: recompute the `:refs` entry
  of every namespace whose entry is missing or keyed on an older source,
  persist those rows beside the elements (`db/persist-index!`), and leave the
  live value carrying them. Returns `{:refreshed [ns …]}`.

  The write path keeps the namespaces IT rewrote current; what this catches
  is everything else that changes a value — a journal replay of another
  agent's deltas, a merge, a store written before the index existed.
  `ns-refs` already recomputes a stale entry on every read, correctly; this
  is what makes it stop paying for that. Called at the done-point, which is
  the cadence a stale entry can accumulate at. Not a journal write: nothing
  here moves the head, so the value is swapped in place the way
  `refresh-cache!` swaps a re-read materialization."
  [session]
  (let [st    (:store @session)
        stale (vec (for [nsx (sort (keys (:namespaces st)))
                         :when (not= (get-in st [:refs nsx :key]) (refs/ns-key st nsx))]
                     nsx))]
    (when (seq stale)
      (let [fresh (refs/refresh st stale)]
        (when-let [conn (:db @session)]
          (db/persist-index! conn fresh stale (engine/session-line session)))
        (swap! session update :store assoc :refs (:refs fresh))))
    {:refreshed stale}))

(defn red-after
  "What usually breaks when `on` (\"ns/name\") changes: the tests that went red
   in episodes where the form changed, most often first, as `[{:test :n
   :last}]` — read from the index the reds themselves wrote (`db/reds-for`).
   nil when there is no evidence, or no durable store to hold any: a caller
   leaves the key OFF rather than sending an empty list that reads as safe."
  [session on]
  (when-let [conn (:db @session)]
    (let [[nsx nm] (str/split (str on) #"/" 2)]
      (when-let [fid (and nm (:id (store/form-named (:store @session)
                                                     (symbol nsx) (symbol nm))))]
        (not-empty (mapv #(dissoc % :form-id) (db/reds-for conn [fid])))))))

(defn- delete-callers-refusal
  "The callers gate for a GROUP: `{:error \"step i: …\" :step i}` for the first
  `:delete` step whose form something OUTSIDE the group still calls, or nil.
  A single `delete-form!` refuses at the write; inside a group that gate is
  deliberately off, since a caller a later step removes is a legitimate
  mid-sequence state. So the question is asked of the FINAL shape: the
  callers `base` knows (the reference graph resolves a target only while it
  exists, so the final value `st` cannot answer it), minus the forms the
  group deletes, minus the forms it replaces whose final source no longer
  mentions the callee. What is left is a real dangling reference, named by
  step and caller rather than surfacing as a compile failure."
  [base st steps]
  (let [named    (fn [{:keys [action ns source] :as step}]
                   (when-let [nm (if (= :add action)
                                   (some-> (edit/parse-form source) :node store/form-symbol)
                                   (:name step))]
                     (symbol (str ns) (str nm))))
        of       (fn [actions] (into #{} (keep #(when (actions (:action %)) (named %))) steps))
        deleted  (of #{:delete})
        replaced (of #{:replace :subform})
        mentions? (fn [caller nm qsym]
                    (when-let [e (store/form-named st (symbol (namespace caller))
                                                   (symbol (name caller)))]
                      (some #(and (symbol? %) (or (= % qsym) (= (name %) (str nm))))
                            (tree-seq coll? seq (n/sexpr (:node e))))))]
    (some (fn [[i {:keys [action ns] :as step}]]
            (when (= :delete action)
              (let [nm      (:name step)
                    qsym    (symbol (str ns) (str nm))
                    callers (->> (refs/refs-to base qsym)
                                 (filter #(= :static (:via %)))
                                 (map #(symbol (str (:from-ns %)) (str (:from-var %))))
                                 (remove #(= % qsym))
                                 (remove deleted)
                                 (remove #(and (replaced %) (not (mentions? % nm qsym))))
                                 distinct sort vec)]
                (when (seq callers)
                  {:error (str "step " i ": " qsym " is still called by "
                               (str/join ", " (take 8 callers))
                               " — outside this group. Delete or update the caller"
                               " in the same group (any order), or first.")
                   :step i}))))
          (map-indexed vector steps))))

(defn- auto-module-dep-retry!
  "The write path's repair for the second-commonest mechanical refusal, the
  mirror of `auto-require-retry`: `r` was refused because its form's first
  call across a module boundary named an edge nothing had declared, and the
  refusal itself names the edge (`module_dep {from \"a\" to \"b\"}`). Declare
  it with the pipeline's own prompt, run `retry` — the same write once more
  — and stamp `:auto-module-dep {:from :to}` on the result (every edge
  under `:auto-module-deps` when there were several). A write can cross
  SEVERAL boundaries at once — a group's steps, a new namespace's forms —
  so this loops over DISTINCT edges, bounded, declaring each the refusal
  names in turn. eval10 measured the two-step this replaces at 7–11 turns
  per lifetime cell, every one of them the agent doing exactly what the
  refusal said.

  A CYCLE is a real question and stays one: when the declaration is itself
  refused, `r` comes back with the cycle explanation appended so the reader
  learns both facts from one result. Any other refusal, or an edge named
  twice, returns the refusal untouched. The caller passes
  `:no-auto-module-dep true` on the retry so this runs once per write."
  [session r retry & {:keys [agent]}]
  (let [edge (fn [r] (when-let [msg (:error r)]
                       ;; the call the refusal spells, or the sentence it makes —
                       ;; either names the edge
                       (when-let [[_ from to] (or (re-find #"module_dep \{from \"([^\"]+)\" to \"([^\"]+)\"\}" msg)
                                                  (re-find #"module (\S+) does not declare (\S+?)(?:\s|—|$)" msg))]
                         {:from from :to to})))]
    (loop [r r, declared [], n 0]
      (let [e (edge r)]
        (cond
          (nil? e)                       (cond-> r
                                           (and (nil? (:error r)) (seq declared))
                                           (assoc :auto-module-dep (first declared))
                                           (and (nil? (:error r)) (next declared))
                                           (assoc :auto-module-deps declared))
          (or (some #{e} declared) (<= 6 n)) r
          :else
          (let [md (module-dep! session (:from e) (:to e)
                                :prompt fields/auto-module-dep-prompt :agent agent)]
            (if (:error md)
              (update r :error str " The edge could not be declared for you: " (:error md))
              (recur (retry) (conj declared e) (inc n)))))))))

(defn- by-ask-rows
  "The line's content deltas grouped by the ASK that made them: a
  `:turn-begin` opens an ask (its verbatim intent), and every add / replace
  / delete / rename after it is its until the next one. Per ask:
  `{:ask :turn :at :added :changed :deleted :renamed :deltas}` (forms as
  `ns/name`; renames as `{:from :to}`; `:deltas` the change ids under the
  ask, the citations a handoff quotes), newest first, asks that changed
  nothing omitted, bounded by `limit`. Writes before any turn (an ingest, a
  script) belong to no ask and are not here.

  The ask arrives WHOLE (to 1200 chars). eval10 p5 asked for a rundown of
  what changed and why from the records; `report` rolled changes up by
  namespace with each ask snipped to a line, and the agent read five
  per-namespace histories to attribute forms to asks. eval24 opus paid
  eight history calls to recover asks snipped to 200 chars — the ask IS the
  why a handoff is asked for, so it is the last thing `fit-report` cuts."
  [st after limit]
  (let [name-of (fn [d fid]
                  (symbol (str (:ns d))
                          (str (or (:name (store/form-by-id st fid)) (:name d) fid))))
        step    (fn [{:keys [asks cur] :as acc} d]
                  (case (:op d)
                    :turn-begin
                    {:asks (cond-> asks cur (conj cur))
                     :cur  (cond-> {:ask (orient/snip (:intent d) 1200)}
                             (:id d) (assoc :turn (:id d))
                             (:at d) (assoc :at (history/human-time (:at d))))}

                    (:add :replace :delete :rename)
                    (if-not cur
                      acc
                      (let [k (case (:op d) :add :added :replace :changed :delete :deleted :rename :renamed)
                            v (if (= :rename (:op d))
                                [{:from (symbol (str (:ns d)) (str (:old d)))
                                  :to   (symbol (str (:ns d)) (str (:new d)))}]
                                (mapv #(name-of d %)
                                      (or (:form-ids d) (some-> (:form-id d) vector))))]
                        (assoc acc :cur (-> cur
                                            (update k (fnil into []) v)
                                            (cond-> (:id d) (update :deltas (fnil conj []) (:id d)))))))

                    acc))
        {:keys [asks cur]} (reduce step {:asks [] :cur nil} after)]
    (->> (cond-> asks cur (conj cur))
         (filter #(some % [:added :changed :deleted :renamed]))
         (map (fn [a] (reduce (fn [m k] (cond-> m (contains? m k) (update k #(vec (distinct %)))))
                              a [:added :changed :deleted :renamed])))
         (map (fn [a] (cond-> a (:deltas a) (update :deltas #(vec (take 12 (distinct %)))))))
         reverse
         (take limit)
         vec)))

^:reads (defn report
  "The handoff/summary composite (ratio push): commit-points, net form-level
  changes with their recorded ASKS, and the last verification state — the
  history fan-out (query_history + query_history {contains} + query_changes +
  query_commits + git diffs) as ONE deterministic read. `:since` = a
  delta/commit-point id; `:contains` filters asks/descriptions — and carries
  `:story` for the most-storied matching forms (version rows: the recorded
  ask, op, time, verification state; ranked, capped at 3, never withheld),
  because the provenance question is exactly what a narrow report is asked
  for and the fan-out re-derived those rows piecewise. Sources stay one
  call away.

  A history read: the line's journal is read here, when asked (a handoff is
  written a few times a day), rather than carried in the value."
  [session & {:keys [since contains limit] :or {limit 50}}]
  (let [st        (:store @session)
        conn      (:db @session)
        line      (engine/session-line session)
        ;; \"start\" is the lifetime — the same anchor `query_changes`
        ;; takes, and the shape a handoff ask sends (eval24 opus: refused)
        since     (when-not (contains? #{"start" ":start" :start} since) since)
        after     (vec (db/line-deltas conn line :since since))
        after-ids (into #{} (map :id) after)
        content   #{:add :replace :delete :rename :move}
        changes   (->> after
                       (filter (comp content :op))
                       (mapcat (fn [d]
                                 (for [fid (or (:form-ids d)
                                               (some-> (:form-id d) vector))]
                                   {:ns (:ns d) :fid fid :op (:op d)
                                    :ask (:prompt d) :delta (:id d)})))
                       (group-by (juxt :ns :fid))
                       (map (fn [[[nsx fid] es]]
                              {:ns nsx
                               :form (let [e (store/form-by-id st fid)]
                                       (or (:name e) fid))
                               :ops (vec (distinct (map :op es)))
                               :asks (vec (take 3 (distinct (map #(orient/snip % 140) (keep :ask es)))))
                               :deltas (vec (take-last 2 (distinct (keep :delta es))))}))
                       (filter (fn [row]
                                 (or (nil? contains)
                                     (some #(str/includes? (str %) (str contains))
                                           (cons (str (:form row)) (:asks row))))))
                       (sort-by (juxt (comp str :ns) (comp str :form)))
                       (take limit)
                       vec)
        ms        (->> (query-commits session)
                       (filter #(and (or (nil? since) (after-ids (:commit %)))
                                     (or (nil? contains)
                                         (str/includes? (str (:description %))
                                                        (str contains)))))
                       (take 20)
                       (mapv #(-> (select-keys % [:commit :description :at :status])
                                  (update :description orient/snip 110))))
        verify*   (db/last-marker conn line :verify)
        ;; the whole-store verdict when one stands, else the last episode
        ;; verify; either way the COUNTS and the command — the two things
        ;; a handoff quotes (eval24 opus: test_run twice for the number)
        fc        (db/last-full-check conn line)
        suite     (let [v   (or fc verify*)
                        res (:result v)]
                    (when v
                      (into {}
                            (remove (comp nil? val))
                            {:status   (cond (:status res) (:status res)
                                             (and (number? (:fail res)) (number? (:error res)))
                                             (if (zero? (+ (:fail res) (:error res))) :green :red)
                                             :else :unknown)
                             :scope    (if fc :whole-store :episode)
                             :as-of    (:id v)
                             :tests    (:test res)
                             :pass     (:pass res)
                             :fail     (:fail res)
                             :error    (:error res)
                             :external (:ran (:external res))
                             :command  (str "slopp --call full_check '{}' — the whole store, every"
                                            " tier, from a shell with no session; in a session,"
                                            " verify {op full_check}")})))
        ;; where the store was seeded from — \"since the original version\"
        ;; has its anchor here rather than in a git diff (eval24 opus: four
        ;; turns of `git diff` against the seed sha)
        origin    (when-let [sha (db/get-meta conn "git-base-sha")]
                    (into {} (remove (comp nil? val))
                          {:sha sha :remote (db/get-meta conn "git-remote")
                           :note (str "the seeded version: every form present at import carries"
                                      " an :ingest delta citing this sha (an imported form's history"
                                      " shows it as :origin); everything under :by-ask is since")}))
        dead      (->> after
                       (filter #(= :revert (:op %)))
                       (mapv (fn [d] (cond-> {:why (:why d)
                                              :forms (vec (:forms d))}
                                       (:at d) (assoc :at (history/human-time (:at d)))))))
        ;; the USER's verbatim asks, recorded on turn-begin. A handoff's first
        ;; question is \"what was I asked to do?\", and per-form :asks answer a
        ;; different one (what each write intended). Without these, handoffs
        ;; read the journal by hand — eval9 shelled out to sqlite3 on
        ;; .slopp/store.db to get exactly this.
        by-ask    (by-ask-rows st after limit)
        intents   (->> after
                       (filter #(= :turn-begin (:op %)))
                       (keep :intent)
                       distinct
                       ;; newest first, and bounded by the same `limit` as the
                       ;; changes: `limit 1` used to return every ask ever, trimmed
                       ;; at the wire gate
                       reverse
                       (take limit)
                       (mapv #(orient/snip % 160)))
        ;; the provenance rows the s8 fan-out re-derived piecewise. Candidates
        ;; come from form NAMES as well as the line's changes — imported or
        ;; pre-`since` history has no line delta, and that is exactly when
        ;; provenance gets asked (sonnet s9, step 2). RANKED, most-storied
        ;; first — the deep history is the form being asked about — and capped
        ;; at 3, never withheld: a topic word matching several forms is the
        ;; question's normal shape (opus s9c, \"fuel\").
        story     (when contains
                    (let [named (->> (concat
                                      (for [nsx (keys (:namespaces st))
                                            e   (store/forms st nsx)
                                            :when (and (:name e) (not= (:name e) nsx)
                                                       (str/includes? (str (:name e))
                                                                      (str contains)))]
                                        {:ns nsx :form (:name e)})
                                      (->> changes
                                           (filter (comp symbol? :form))
                                           ;; the ns FORM (name == namespace)
                                           ;; collects require-edit versions
                                           ;; and outranks the answer — it is
                                           ;; bookkeeping, not a story (s9c)
                                           (remove #(= (str (:form %)) (str (:ns %))))))
                                     (map #(select-keys % [:ns :form]))
                                     distinct
                                     (take 8))]
                      (when (seq named)
                        (let [hs (with-history session)]
                          (->> named
                               (keep (fn [{:keys [ns form]}]
                                       (when-let [vs (seq (history/query-form-history hs ns form))]
                                         {:form (symbol (str ns) (str form)) :all vs})))
                               (sort-by (comp - count :all))
                               (take 3)
                               (mapv (fn [{:keys [form all]}]
                                       {:form form
                                        :versions (->> all
                                                       (take-last 8)
                                                       (mapv (fn [v]
                                                               (cond-> {:delta (:delta v)
                                                                        :op (:op v)
                                                                        :at (:at v)
                                                                        :ask (orient/snip (or (:prompt v) (:turn-intent v) "") 140)}
                                                                 (:status v) (assoc :status (:status v))))))}))
                               not-empty)))))]
    (orient/fit-report
     (cond-> {:records (str "every row cites the journal: :turn on an ask, :deltas on a"
                           " change, :delta on a story version, :commit on a commit-point"
                           " — the ids ARE the citations a handoff can quote;"
                           " query_history {ns … name …} expands any one form")
             :commit-points ms
             :origin origin
             :changes changes
             :suite suite
             ;; the report is names + asks; the CODE lives one call away.
             ;; Say so here, or a handoff goes hunting in `git diff` (eval9
             ;; measured ~20k chars of it) for something slopp already has.
             :code "query_changes {from \"start\"} = every form's :was/:now across this lifetime (or from \"last-commit\"); format=text for line diffs"
             :verify (str "writes self-verify; test_run {all true} re-runs the "
                          "whole in-image suite (bare {} only returns guidance); "
                          "test_run {:external true} = the full external suite. "
                          "HANDOFF one-shots (humans/scripts, no session needed): "
                          "`slopp --call test_run '{\"external\":true}'` and "
                          "`slopp --call query_commits` — quote these in handoff "
                          "docs; no need to read skill files for the CLI forms")}
       (seq intents) (assoc :intents intents)
       (seq by-ask)  (assoc :by-ask by-ask)
       story         (assoc :story story)
       (seq dead)    (assoc :dead-ends dead)))))

(defn- rename-callers-refusal
  "The rename gate for a GROUP: `{:error … :step i}` for the first :replace
  step that RENAMES its form while callers OUTSIDE the group still reference
  the old name, or nil. A single replace refuses at the write; inside a group
  the question is asked of the FINAL shape — a caller the group itself
  updates, replaces or deletes is not stranded, which is what lets a rename
  and its callers land as one intent. Callers come from `base` (the reference
  graph resolves a target only while it exists, so the final value `st`
  cannot answer it), and each is kept only while its form in `st` still
  mentions the old name."
  [base st steps]
  (some
   (fn [[i {:keys [action ns source] :as step}]]
     (when (and (= :replace action) (:name step) source)
       (let [nm  (:name step)
             nm' (some-> (edit/parse-form source) :node store/form-symbol)]
         (when (and nm' (not= (str nm') (str nm)))
           (let [qsym    (symbol (str ns) (str nm))
                 callers (->> (refs/refs-to base qsym)
                              (filter #(= :static (:via %)))
                              (map #(symbol (str (:from-ns %)) (str (:from-var %))))
                              (remove #(= % qsym))
                              (remove (fn [c]
                                        (let [e (store/form-named st (symbol (namespace c))
                                                                  (symbol (clojure.core/name c)))]
                                          (or (nil? e)
                                              (not (some #(and (symbol? %)
                                                               (or (= % qsym)
                                                                   (= (clojure.core/name %) (str nm))))
                                                         (tree-seq coll? seq (n/sexpr (:node e)))))))))
                              distinct sort vec)]
             (when (seq callers)
               {:error (str "step " i ": this replace RENAMES " nm " → " nm'
                            " but callers outside this group still reference "
                            qsym ": " (str/join ", " (take 8 callers))
                            " — update or delete them in the SAME group, or"
                            " edit_rename rewrites every caller atomically")
                :step i}))))))
   (map-indexed vector steps)))

(defn edit-group-once!
  "Apply several form writes as ONE atomic intent (F2). All steps are validated
  and applied to a store value first — any error rejects the WHOLE group with
  nothing committed (store, deltas, image untouched). On success: all deltas
  (sharing a `:group` id) commit and persist, every change hot-reloads, and
  verification runs ONCE at the end — no meaningless mid-refactor red, no
  wasted diagnostic restart. Steps: [{:action :replace|:add|:delete
  :ns sym :name sym :source str} ...].

  The single pass. `edit-group!` wraps it with the write path's auto-require
  and is what the wire (`edit_group`) and the tool-derived multi-form ops
  (`change-signature!`, `rename-sweep!`, `revert-episode!`, `undo!`,
  `sync/apply-ns!`) both call; their intermediate states are invalid by
  construction, which is exactly what one store value verified once allows.
  Reported per step under `:steps` so a caller knows which form each step
  landed as without reading anything back.

  Carries the single-form path's image repairs, because a one-step group
  must BE the single-form write: a replaced or deleted defmethod's OLD
  dispatch is unregistered after hot-load (the steps' `:post-eval`),
  namespaces that CAPTURED a value from an edited form are reloaded before
  verification (`:image-reloaded` — tests must run against a repaired image,
  not a half-stale one), a ^:live-handle constructor changing KEY SHAPE
  rebuilds the image before anything reads the stale handle
  (`:image-rebuilt`; a mid-migration rebuild failure keeps the working image
  and reports), a rename with callers stranded OUTSIDE the group refuses
  against the FINAL shape (`rename-callers-refusal`), and a :replace no
  runtime evidence reaches is flagged `:untested`."
  [session steps & {:keys [prompt agent]}]
  (if (empty? steps)
    {:error "edit-group needs at least one step"}
    (let [t0 (System/nanoTime)
          base0 (:store @session)
          pre-warned (into #{}
                           (mapcat (fn [ns-sym]
                                     (map :var (edit/ns-warnings (:store @session) ns-sym))))
                           (distinct (map :ns steps)))
          [gid st0] (store/alloc-id base0 "g")]
      (loop [st st0, remaining steps, deltas [], hots [], posts [], shifts [], i 0]
        (if-let [step (first remaining)]
          (let [r (apply-group-step st gid prompt agent step)]
            (if (:error r)
              (cond-> {:error (str "step " i ": " (:error r)) :step i}
                (:source-now r) (assoc :source-now (:source-now r)))
              (recur (:store r) (rest remaining)
                     (conj deltas (:delta r)) (conj hots (:hot r))
                     (if (:post-eval r) (conj posts (:post-eval r)) posts)
                     (if (:handle-shift r) (conj shifts (:handle-shift r)) shifts)
                     (inc i))))
          ;; commit phase — checked loads FIRST (S1), commit only if all compile
          (let [st       (reduce (fn [s ns-sym]
                                   (if-let [rz (edit/resolve-cold-load
                                                s ns-sym
                                                :prompt "auto-reorder: define before use"
                                                :agent agent)]
                                     (:store rz) s))
                                 st (distinct (map :ns steps)))
                dangling (delete-callers-refusal base0 st steps)
                stranded (rename-callers-refusal base0 st steps)
                lr       (lintgate/lint-refusals base0 st (distinct (map :ns steps))
                                             (keep :form-id deltas))
                load-res (if-let [gate (or (edit/cold-load-errors st (distinct (map :ns steps)))
                                           (:refuse lr))]
                           {:err gate}
                           (merge (engine/hot-load-all! session st
                                                 (keep (fn [[k a]] (when (#{:load :load-unmap} k) a))
                                                       hots))
                                  (select-keys lr [:carried])))]
            (cond
              ;; a deleted form something OUTSIDE the group still calls — named
              ;; by step and caller, not surfaced as a compile failure
              dangling dangling

              ;; a RENAMED form whose callers the group left behind — the same
              ;; question the single-form replace asks, asked of the final shape
              stranded stranded

              (:err load-res)
              (edit/compile-error st (:err load-res) "group failed to compile: ")

              (not (engine/try-commit! session base0 st
                                (vec (distinct (map :ns steps)))))
              (do ;; the group's forms are already hot-loaded — never leave the loser's
      ;; code answering for the winner's store
      (engine/fresh-image! session)
      {:conflict {:reason "store changed during multi-form op — retry"}})

              :else
              (let [image    (:image @session)
                    _        (doseq [[kind a b c] hots]
                               (cond
                                 (= :unmap kind)
                                 (repl/eval! image (format "(ns-unmap '%s '%s)" a b))
                                 (= :load-unmap kind)
                                 (repl/eval! image (format "(ns-unmap '%s '%s)" b c))))
                    ;; a replaced/deleted defmethod's old dispatch — after
                    ;; hot-load, exactly as the single-form paths do
                    _        (doseq [code posts]
                               (repl/eval! image code))
                    ;; forms holding a value computed from an edited form's OLD
                    ;; source: reload the capturing namespaces through the same
                    ;; load-ns! every other loader uses, BEFORE verification —
                    ;; rare by measurement, so an ordinary group pays nothing
                    jvm-deltas (filterv (fn [d] (store/jvm-loadable? (:store @session) (:ns d))) deltas)
                    captured (vec (distinct (mapcat #(rules.currency/stale-after (:image @session) (:store @session) (:form-id %))
                                                    jvm-deltas)))
                    reloaded (when (seq captured)
                               (vec (sort (distinct (map (comp symbol namespace) captured)))))
                    reload-errs
                    (when (seq reloaded)
                      (not-empty
                       (into {}
                             (keep (fn [nsx]
                                     (when-let [e (image/load-ns! (:image @session)
                                                                  (:store @session)
                                                                  nsx)]
                                       [nsx e])))
                             reloaded)))
                    stale    (when (seq reloaded)
                               (not-empty (vec (distinct (mapcat #(rules.currency/stale-after (:image @session) (:store @session) (:form-id %))
                                                                 jvm-deltas)))))
                    ;; a live-handle constructor changed shape somewhere in the
                    ;; group: the handle in the session was built by the OLD
                    ;; code — rebuild before verification reads it, and keep
                    ;; the working image if a mid-migration rebuild fails
                    shift    (when (seq shifts)
                               (reduce (fn [a b]
                                         {:added   (into (or (:added a) #{}) (:added b))
                                          :removed (into (or (:removed a) #{}) (:removed b))})
                                       shifts))
                    rebuild-err
                    (when shift
                      (swap! session assoc :spare nil)
                      (try (engine/fresh-image! session) nil
                           (catch Throwable t (ex-message t))))
                    ;; per-step names double as the D5.1 edited set
                    step-nms (map (fn [{:keys [action ns name source]} d]
                                    ;; the DELTA is the truth about what landed:
                                    ;; an inferred step (nameless, actionless —
                                    ;; action inference and the blob split both
                                    ;; produce them) derived nil here and
                                    ;; poisoned :affected to :unknown, so the
                                    ;; verification fallback ran against nothing
                                    ;; in inline-test projects (spec-run 0/0)
                                    (let [nm  (or name
                                                  (:name (store/form-by-id (:store @session) (:form-id d)))
                                                  (some-> (edit/parse-form source)
                                                          :node store/form-symbol))
                                          act (or action (:op d))]
                                      (when nm [act ns nm])))
                                  steps deltas)
                    edited   (into #{}
                                   (keep (fn [x]
                                           (when-let [[_ ns nm] x]
                                             (symbol (str ns) (str nm)))))
                                   step-nms)
                    ;; affected = union across steps; unknown → conservative full
                    per-step (map (fn [x]
                                    (if-let [[action ns nm] x]
                                      (let [a (engine/affected-tests session ns nm)]
                                        (cond
                                          ;; a NEW deftest has no trace yet and
                                          ;; is its own covering test — and this
                                          ;; outranks the trace, which answers a
                                          ;; non-nil EMPTY set for a form it has
                                          ;; never seen (spec-run 0/0, s13). The
                                          ;; form's own head decides, not the
                                          ;; namespace suffix: inline-test
                                          ;; projects keep specs beside code.
                                          (and (= action :add)
                                               (or (str/ends-with? (str ns) "-test")
                                                   (= 'deftest
                                                      (try (some-> (store/form-named (:store @session) ns nm)
                                                                   :node n/sexpr first)
                                                           (catch Exception _ nil)))))
                                          #{(symbol (str ns) (str nm))}
                                          (some? a)       (set a)
                                          (= action :add) #{}
                                          :else           :unknown))
                                      :unknown))
                                  step-nms)
                    affected (when (not-any? #{:unknown} per-step)
                               (vec (sort (apply set/union per-step))))
                    ;; no runtime evidence reaches any replaced form and the
                    ;; group adds no test of its own — the single-form flag,
                    ;; carried through the group door
                    untested (and (nil? affected) (seq (:test-map @session))
                                  (boolean (some #(#{:replace :subform} (:action %)) steps))
                                  (not-any? #(and (:source %)
                                                  (re-find #"^\(\s*(?:clojure\.test/)?deftest\b"
                                                           (str/triml (:source %))))
                                            steps))
                    ;; F-3c5: with no/partial trace info the fallback run must
                    ;; cover EVERY touched namespace, not just the first step's
                    ;; the fallback scope is a GRAPH question: tests that REACH the
                    ;; touched namespaces. Running tests IN the production
                    ;; namespaces found none, so a group write with incomplete
                    ;; trace evidence verified nothing at all.
                    touched  (vec (distinct (map :ns steps)))
                    main-ns  (or (seq (engine/covering-test-nses
                                       (:store @session) touched))
                                 touched)
                    summary  (engine/run-verification! session main-ns
                                                (when (seq affected) affected)
                                                :edited edited)]
                (engine/commit-appended! session
                                  #(store/record-verification % main-ns summary)
                                  [])
                (let [all-w    (->> (map :ns steps) distinct
                                    (mapcat #(edit/ns-warnings (:store @session) %)))
                      existing (count (filter (comp pre-warned :var) all-w))]
                  (engine/with-ms
                    (cond-> {:group    gid
                             :deltas   deltas
                             :changed-nses (vec (distinct (map :ns steps)))
                             ;; per step: which form it landed as and its
                             ;; delta — the report that makes a read-back
                             ;; after a group visibly redundant
                             :steps    (vec (map-indexed
                                             (fn [i [[_ ns nm :as x] d]]
                                               (cond-> {:step i :action (or (:action (nth steps i)) (:op d))}
                                                 x    (assoc :form (symbol (str ns) (str nm)))
                                                 true (assoc :delta (:id d))))
                                             (map vector step-nms deltas)))
                             :warnings (vec (remove (comp pre-warned :var) all-w))
                             :test     summary
                             :affected (or (not-empty affected) :all)
                             ;; drift for the WHOLE group, read off the deltas —
                             ;; every step kind (including :subform, which
                             ;; computes its own source) records its final
                             ;; source there, so one place covers them all.
                             ;; Detecting it per-step would need a loop arity
                             ;; change; the deltas already carry the answer.
                             :drift
                             (vec (for [d     deltas
                                        :when (= :replace (:op d))
                                        :let  [fid (:form-id d)
                                               e   (store/form-by-id base0 fid)
                                               nu  (some-> (get (:sources d) fid)
                                                           edit/parse-form :node)]
                                        :when (and (:node e) nu)
                                        x     (edit/contract-drift (:node e) nu)]
                                    (assoc x :form (symbol (str (:ns d))
                                                           (str (or (:name e) fid))))))}
                      (:healed load-res) (assoc :image-healed true)
                      (:stubbed load-res) (assoc :red-first (:stubbed load-res)
                                                 :note (str "these vars don't exist yet —"
                                                            " stubbed in-image as failing"
                                                            " (red-first); implement them to"
                                                            " go green."))
                      (:carried load-res) (assoc :carried-errors (:carried load-res))
                      (seq reloaded)     (assoc :image-reloaded reloaded)
                      reload-errs        (assoc :image-reload-failed reload-errs)
                      (seq stale)        (assoc :stale-in-image stale)
                      shift              (assoc :image-rebuilt
                                                (cond-> (assoc shift :reason :live-handle-shape-change)
                                                  rebuild-err
                                                  (assoc :rebuild-failed rebuild-err
                                                         :note (str "kept the working image — normal"
                                                                    " MID-MIGRATION, when the constructor"
                                                                    " has changed but its callers have"
                                                                    " not. Update them and the next write"
                                                                    " rebuilds cleanly."))))
                      untested           (assoc :untested true)
                      (pos? existing)    (assoc :existing-warnings existing))
                    t0))))))))))

(defn- add-forms!
  "Several NEW forms in one write: an atomic `edit-group!` of `:add` steps —
  one delta per form, every gate per form, ONE verification over the batch,
  nothing landed if any form fails — reported per form as `:forms`. The
  batch face of `add-form!`, which routes here when `source` holds more
  than one top-level form."
  [session ns-sym nodes & {:keys [prompt agent]}]
  (let [r (edit-group-once! session
                       (mapv (fn [node] {:action :add :ns ns-sym :source (n/string node)})
                             nodes)
                       :prompt prompt :agent agent)]
    (if (or (:error r) (:conflict r))
      r
      (let [st (:store @session)]
        (assoc r :forms
               (mapv (fn [d]
                       (let [e (store/form-by-id st (:form-id d))]
                         (symbol (str ns-sym) (str (or (:name e) (:form-id d))))))
                     (:deltas r)))))))

(defn change-signature!
  "P2: change `ns-sym/fn-name`'s signature as ONE atomic intent — replace
  the defn with `new-source` (keep the name; the lint gate is the oracle if
  you don't) and mechanically rewrite every call site's argument list from
  `args-template` ($1..$9 = the site's existing arg sources; the callee
  stays as written, so aliases survive — see refactor/change-signature-plan).
  Executes through edit-group! (one gate pass, one verification).
  References that can't be rewritten come back under :manual."
  [session ns-sym fn-name new-source args-template & {:keys [prompt agent]}]
  (let [st (:store @session)]
    (if (nil? (store/form-named st ns-sym fn-name))
      (edit/missing-form-error st ns-sym fn-name)
      (let [plan (refactor/change-signature-plan st ns-sym fn-name args-template)]
        (if (:error plan)
          plan
          (let [steps (into [{:action :replace :ns ns-sym :name fn-name
                              :source new-source}]
                            (:caller-steps plan))
                r     (edit-group-once! session steps
                                   :prompt (or prompt
                                               (str "change signature: " fn-name))
                                   :agent agent)]
            (cond-> (assoc r :rewrote (count (:caller-steps plan)))
              (seq (:manual plan)) (assoc :manual (:manual plan)))))))))

(defn requalify-boundary-keys!
  "Namespace a module-external fn's OPTION KEYS in one verified intent: its
  arglist destructuring AND the map literals its callers pass, together.

  This exists because `require-namespaced-keys` was otherwise UNDISCHARGEABLE.
  Its last violation, `api/open!`, has 60 call sites; a store-wide
  `rename_sweep` is unsafe whenever the key means more than one thing (`:dir`
  names three different things here), and 60 hand edits is worse. A rule
  nobody can discharge trains people to ignore the channel — the rule's own
  docstring says so.

  `to-ns` defaults to the target's namespace. The keys are DERIVED — every
  unqualified key its first arg destructures — so the caller cannot namespace
  half a contract and leave the rest reading nil.

  A call site counts only when its head RESOLVES to the target: the defining
  ns's own name, the caller's alias for it, or the fully-qualified symbol.
  Matching by bare name instead silently included `slopp.db/open!` alongside
  `slopp.ops.external/open!` — caught by a dry-run reporting 62 forms and 24 unknowns
  where the caller graph said 60 and 4.

  Reports `:unknown-shape`: callers passing a non-literal (`(open! opts)`),
  which no syntactic reader can rewrite. Those are left untouched and NAMED,
  never silently skipped — the count is the part you still owe by hand. Call
  sites OUTSIDE the store (the kernel's own .clj files) are invisible to this
  and to every store-based analysis; check them yourself.
  `:dry-run true` previews without writing."
  [session ns-sym nm & {:keys [to-ns prompt agent dry-run]}]
  (let [st     (:store @session)
        ns-sym (symbol (str ns-sym))
        nm     (symbol (str nm))
        form   (store/named-sexpr st ns-sym nm)]
    (if-not form
      (edit/missing-form-error st ns-sym nm)
      (let [tons (str (or to-ns ns-sym))
            ks   (vec (sort (remove namespace (:destructured (shape/read-keys form)))))]
        (if (empty? ks)
          {:error (str ns-sym "/" nm " destructures no unqualified keys —"
                       " nothing to requalify")}
          (let [why     (or prompt (str "namespace " ns-sym "/" nm "'s option keys"
                                        " under " tons))
                heads   (fn [nsx]
                          (cond-> #{(str ns-sym "/" nm)}
                            (= nsx ns-sym) (conj (str nm))
                            true (into (for [[alias lib] (edit/require-aliases st nsx)
                                             :when (= (symbol (str lib)) ns-sym)]
                                         (str alias "/" nm)))))
                rewrite (fn [src nsx target?]
                          (reduce (fn [s k]
                                    (let [s' (refactor/requalify-call-args
                                              s (heads nsx) (name k) tons)]
                                      (if target?
                                        (refactor/requalify-keys s' (name k) nil tons)
                                        s')))
                                  src ks))
                steps   (vec (for [nsx (store/ns-dependency-order st)
                                   e   (store/forms st nsx)
                                   :when (:name e)
                                   :let [src  (n/string (:node e))
                                         tgt? (and (= nsx ns-sym) (= (:name e) nm))
                                         src' (rewrite src nsx tgt?)]
                                   :when (not= src src')]
                               {:action :replace :ns nsx :name (:name e) :source src'}))
                opaque? (fn [nsx e]
                          (let [hs (heads nsx)]
                            (some (fn [node]
                                    (and (seq? node)
                                         (symbol? (first node))
                                         (contains? hs (str (first node)))
                                         (next node)
                                         (not (map? (second node)))))
                                  (tree-seq coll? seq (store/form-sexpr (:node e))))))
                unknown (vec (sort (for [nsx (keys (:namespaces st))
                                         e   (store/forms st nsx)
                                         :when (and (:name e) (opaque? nsx e))]
                                     (symbol (str nsx) (str (:name e))))))
                report  (cond-> {:keys ks :to-ns tons :forms (count steps)
                                 ;; a preview that only COUNTS is not a preview: you
                                 ;; cannot check 62 rewrites against a caller graph
                                 ;; you are not shown. The bare-name bug looked
                                 ;; exactly like a correct run until the numbers
                                 ;; were compared.
                                 :in-code (vec (sort (map #(symbol (str (:ns %))
                                                                   (str (:name %)))
                                                          steps)))}
                          (seq unknown)
                          (assoc :unknown-shape unknown
                                 :note (str (count unknown) " call site(s) pass a"
                                            " non-literal map — no syntactic reader"
                                            " can see through a binding, so those"
                                            " are UNTOUCHED and yours to check")))]
            (cond
              (empty? steps) {:error (str "no call site or arglist to rewrite for "
                                          ns-sym "/" nm)}
              dry-run        (assoc report :dry-run true)
              :else          (let [r (edit-group-once! session steps :prompt why :agent agent)]
                               (if (:error r) r (merge r report))))))))))

(defn realias!
  "Rename ONE namespace's require alias as a single atomic intent: the `:as`
  in its `ns` form and every `alias/sym` in its bodies, through `edit-group!`
  — one gate pass, one verification.

  This exists because the two halves cannot be written separately. Between
  them sits a namespace whose ns form and bodies disagree about what the
  qualifier is, which does not load — so the three-step add-both / migrate /
  drop dance was the only hand-safe route, and at 62 call sites across a
  468-line dispatch the retyping was a worse risk than the stale alias it
  removed. Both stayed wrong for two phases for exactly that reason.

  Scoped to `ns-sym`, because an alias is a name ONE namespace chose. Two
  namespaces calling a lib by different names is not drift.

  Returns the edit-group result plus `:sites` (qualified references rewritten)
  and, when the alias is also named inside STRING literals, `:left-behind` —
  fixture source and prose a symbol rewriter cannot reach. See
  `refactor/realias-plan` for why those are reported rather than rewritten."
  [session ns-sym old new & {:keys [prompt agent]}]
  (let [ns-sym (symbol (str ns-sym))
        plan   (refactor/realias-plan (:store @session) ns-sym old new)]
    (if (:error plan)
      plan
      (let [r (edit-group-once! session (:steps plan)
                           :prompt (or prompt (str "realias " ns-sym ": "
                                                   old " → " new))
                           :agent agent)]
        (cond-> (assoc r :sites (:sites plan) :lib (:lib plan))
          (seq (:left-behind plan)) (assoc :left-behind (:left-behind plan)))))))

(defn rename-sweep!
  "Q14: the docs-team rename as ONE intent — every namespace, var, keyword,
  and prose occurrence of `from` (as a whole word/segment, boundary-guarded)
  becomes `to`, store-wide: matching namespaces rename first (requires
  rewrite along), then every still-matching form rewrites in ONE atomic
  group with ONE verification, and every tracked TEXT FILE that names it
  (a README, a config) is rewritten the same way. The textual segment match
  is deliberate: a sweep means 'everything named that', locals and prose
  included; the dialect/isolation gates and the test run judge the result.
  eval9's measured loss (13.6k tokens / 37 calls / one restart for
  zone->region across 41 nses vs sed's one pass) is this op's demand signal.

  A BARE lowercase word sweeps its Capitalized and UPPER spellings too
  (`Zone`→`Region`, `ZONE`→`REGION`), reported under `:case-variants` when
  any matched: prose and headings spell a concept every way, and a rename
  that leaves \"Zone fees\" in a docstring is the one the reader then greps
  for. A keyword or a dotted name has no such variants. The run ends with
  `:remaining` — a case-insensitive census of every form and tracked file
  that still names `from` — and the note says so when it is empty. eval24
  opus (three cells): the sweep walked past the README, and five turns of
  grep, file_get, cat, sed and file_put followed, after a case-insensitive
  search to check the sweep's coverage; the census is that search, answered.

  A KEYWORD rename (both sides starting `:`) carries a structural half the
  text pass cannot see: `{:a/keys [x]}` names its key as a SYMBOL, with the
  qualifier written in the entry beside it. That entry is matched on the FROM
  qualifier and only on it — `{:keys [x]}` names `:x` and survives a rename of
  `:a/x` untouched. Two reports come out of it, because neither half is a text
  substitution and both were silent once:

  - `:requalified` — destructurings this call restructured. A keyword rename's
    diff should not contain a semantic change without naming it.
  - `:left-behind` — what it DECLINED, each row tagged with `:via`. For
    `:destructuring`: changing a key's NAME rather than its qualifier cannot
    move the symbol, since the symbol is a local binding the body still reads,
    so the rename is yours to finish.

  **REGEX literals move too, and that is a reversal.** A pattern spells a
  dotted name `web\\.static`, which shares no literal text with `web.static`,
  so the text pass walks past every one. Measured at seven in a single wave,
  two of them surviving every write and three green done-points: one rule then
  refused EVERY declared auth group as unknown, teaching the author to
  configure the key it was already reading past. slopp owns the dialect, and a
  dot in a dotted name it governs is a SEPARATOR — no pattern legitimately
  means `web<any>static`. Only the NAME moves; the rest of the pattern is the
  author's own matching. The rewrite is REPORTED under `:patterns-rewritten`
  for the reason `:requalified` is: a rename's diff must not contain a change
  to what a predicate MATCHES without naming it. `:left-behind :via :regex`
  survives as the RESIDUE — what the rewrite did not reach."
  [session from to & {:keys [prompt agent dry-run]}]
  (let [from (str from)
        to   (str to)
        ;; what ENDS the name differs by what is being swept — a keyword is a
        ;; complete token, a bare name is a concept that carries its compounds
        cls  (refactor/name-boundary-class from)
        pat  (re-pattern (str "(?<![" cls "])"
                              (java.util.regex.Pattern/quote from)
                              "(?![" cls "])"))
        ;; the case variants of a BARE word — prose spells a concept every way
        word?    (fn [s] (boolean (re-matches #"[a-z][a-z0-9-]*" s)))
                bare?    (and (word? from) (word? to))
        ;; the PLURAL is the one compound the boundary class cannot express:
        ;; `-` is not a letter so `zone-fee` rides along, `s` IS one so `zones`
        ;; does not. Swept as its own spelling or left behind entirely.
        from-pl  (when bare? (refactor/plural-of from))
        to-pl    (when bare? (refactor/plural-of to))
        variants (when bare?
                   (cond-> [{:from (str/capitalize from) :to (str/capitalize to) :axis :case}
                            {:from (str/upper-case from) :to (str/upper-case to) :axis :case}]
                     (and (not= from-pl from) (not= to-pl to))
                     (into [{:from from-pl :to to-pl :axis :plural}
                            {:from (str/capitalize from-pl) :to (str/capitalize to-pl) :axis :plural}
                            {:from (str/upper-case from-pl) :to (str/upper-case to-pl) :axis :plural}])))
        to-of    (into {from to} (map (juxt :from :to)) variants)
                pat*     (re-pattern (str "(?<![" cls "])(?:"
                                  (str/join "|" (map #(java.util.regex.Pattern/quote %)
                                                     ;; LONGEST first: `commit-points` must be
                                                     ;; offered before `commit-point`, which is
                                                     ;; its own prefix
                                                     (sort-by (comp - count)
                                                              (cons from (map :from variants)))))
                                  ")(?![" cls "])"))
        sweep    (fn [s] (str/replace s pat* (fn [m] (get to-of m m))))
        ;; the census pattern: the same boundary, any case
                pat-i    (re-pattern (str "(?i)(?<![" cls "])"
                                  (java.util.regex.Pattern/quote from)
                                  ;; for a BARE word this deliberately does NOT
                                  ;; close at the end: a census reusing the
                                  ;; sweep's own boundary could only ever find
                                  ;; what the sweep already rewrites, which is
                                  ;; how :mentions came to certify its own
                                  ;; blind spot. It must see what the sweep cannot
                                  (if bare? "" (str "(?![" cls "])"))))
        why  (or prompt (str "sweep " from " -> " to))]
    (cond
      (or (str/blank? from) (str/blank? to))
      {:error "rename_sweep needs :from and :to"}

      (= from to)
      {:error ":from and :to are identical"}

      :else
      (let [nses (filterv #(re-find pat (str %))
                          (keys (:namespaces (:store @session))))
            ;; namespace renames WRITE, so a preview must not run them — it
            ;; reports what they would be instead
            nsr  (if dry-run
                   {:renamed-namespaces
                    (mapv (fn [nsx]
                            [nsx (symbol (str/replace (str nsx) pat to))])
                          (sort nses))}
                   (reduce (fn [acc nsx]
                             (if (:error acc)
                               acc
                               (let [new-ns (str/replace (str nsx) pat to)
                                     r (ns-rename! session (str nsx) new-ns
                                                   :prompt why :agent agent)]
                                 (if (:error r)
                                   {:error (str "renaming " nsx ": " (:error r))}
                                   (update acc :renamed-namespaces conj
                                           [nsx (symbol new-ns)])))))
                           {:renamed-namespaces []}
                           (sort nses)))]
        (if (:error nsr)
          nsr
          (let [st      (:store @session)
                kw?     (and (str/starts-with? from ":")
                             (str/starts-with? to ":"))
                qual    (fn [k] (let [b (subs k 1)]
                                  (when (str/includes? b "/")
                                    (first (str/split b #"/")))))
                lname   (fn [k] (last (str/split (subs k 1) #"/")))
                kname   (when kw? (lname from))
                from-ns (when kw? (qual from))
                to-ns   (when kw? (qual to))
                ;; only a rename that leaves the key's NAME alone can move the
                ;; symbol — it is a local binding, not a keyword
                requal? (and kw? (= kname (lname to)) (not= from-ns to-ns))
                from-k  (when kw? (str (refactor/keys-entry from-ns)))
                ;; select on the REWRITE, not the pattern: a form whose only
                ;; occurrence is a :keys destructuring holds no keyword literal
                rows    (vec (for [nsx (store/ns-dependency-order st)
                                   e   (store/forms st nsx)
                                   :when (:name e)
                                   :let [src  (n/string (:node e))
                                         txt0 (sweep src)
                                         ;; the ESCAPED-dot spelling, which a
                                         ;; regex literal uses and the text pass
                                         ;; above shares no literal text with
                                         txt  (refactor/rewrite-patterns txt0 from to)
                                         src' (if (and requal?
                                                       (str/includes? txt from-k)
                                                       (str/includes? txt kname))
                                                (refactor/requalify-keys
                                                 txt kname from-ns to-ns)
                                                txt)]
                                   :when (not= src src')]
                               {:ns nsx :name (:name e) :source src' :orig src
                                :patterns? (not= txt0 txt)
                                :requalified? (not= txt src')}))
                steps   (mapv #(-> (select-keys % [:ns :name :source])
                                   (assoc :action :replace))
                              rows)
                ;; tracked TEXT files that name it — a README, a config;
                ;; binary entries are content-address maps and have no text
                fline   (fn [s] (when-let [l (first (filter #(re-find pat* %) (str/split-lines s)))]
                                  (let [t (str/trim l)]
                                    (if (> (count t) 120) (str (subs t 0 117) "…") t))))
                file-hits (vec (for [[p e] (sort-by key (:files st))
                                     :when (string? e)
                                     :let [e' (sweep e)]
                                     :when (not= e e')]
                                 {:path p :content e' :orig e :match (fline e)}))
                hit?    (fn [s v] (re-find (re-pattern (str "(?<![" cls "])"
                                                            (java.util.regex.Pattern/quote (:from v))
                                                            "(?![" cls "])"))
                                           s))
                                variants-hit (vec (for [v variants
                                        :when (or (some #(hit? (:orig %) v) rows)
                                                  (some #(hit? (:orig %) v) file-hits))]
                                    v))
                ;; two AXES, reported apart because they fail apart: the case
                ;; axis was always swept, the plural axis never was
                axis-hits (fn [ax] (vec (for [v variants-hit :when (= ax (:axis v))]
                                          (dissoc v :axis))))
                case-hits (axis-hits :case)
                plur-hits (axis-hits :plural)
                ;; every spelling of the concept in the store the sweep will
                ;; NOT rewrite — the answer to the grep a caller would
                ;; otherwise run by hand, and used to have no reason to
                unswept (fn [st*]
                          (refactor/unswept-spellings
                           (concat (for [nsx (keys (:namespaces st*))
                                         e   (store/forms st* nsx)
                                         :when (:name e)]
                                     (n/string (:node e)))
                                   (for [[_ e] (:files st*) :when (string? e)] e))
                           from cls pat*))
                requal  (vec (for [r rows :when (:requalified? r)]
                               {:ns (:ns r) :form (:name r)}))
                ;; REPORTED even though it is now done for you, and for the
                ;; reason `:requalified` is: moving what a pattern MATCHES is a
                ;; semantic change, and a rename's diff must not contain one
                ;; without naming it
                pats    (vec (for [r rows :when (:patterns? r)]
                               {:ns (:ns r) :form (:name r)}))
                ;; the census, read off the store AFTER the writes: what still
                ;; names `from`, in any case, in code or a tracked file
                census  (fn [st*]
                          {:forms (vec (for [nsx (sort (keys (:namespaces st*)))
                                             e   (store/forms st* nsx)
                                             ;; the ns form's own name carries the word and the
                                             ;; namespace rename covers it — forms only
                                             :when (and (:name e) (not= (:name e) nsx)
                                                        (re-find pat-i (n/string (:node e))))]
                                         (symbol (str nsx) (str (:name e)))))
                           :files (vec (for [[p e] (sort-by key (:files st*))
                                             :when (and (string? e) (re-find pat-i e))]
                                         p))})]
            (cond
              (and (empty? steps) (empty? file-hits) (empty? (:renamed-namespaces nsr)))
              {:error (str "nothing named " from
                           " in the store — query_search shows what exists")}

              ;; PREVIEW: a sweep is store-wide and rewrites string literals as
              ;; well as code. Sweeping prose is intended; rewriting a test
              ;; FIXTURE is not, and does it silently. Separate the two so the
              ;; string hits get an eye before anything lands.
              dry-run
              (let [classify (fn [{:keys [ns name]}]
                               (let [src (n/string (:node (store/form-named
                                                           (:store @session) ns name)))
                                     s?  (refactor/match-in-strings? src pat*)
                                     ;; SHOW the matched text, not just the form
                                     ;; name: this is the one bucket a sweep asks
                                     ;; a human to read, and a list of names
                                     ;; cannot be triaged — a frozen manifest once
                                     ;; went through a name-only review and was
                                     ;; rewritten into a claim about a past that
                                     ;; never happened
                                     line (when s? (fline src))]
                                 (cond-> {:form (symbol (str ns) (str name))
                                          :strings? s?}
                                   line (assoc :match line)
                                   ;; the form itself, for the one bucket a reader
                                   ;; has to judge — or the whole namespace gets
                                   ;; read for it (eval27 opus, 6.5k, every cell)
                                   s?   (assoc :source src))))
                    rows'    (mapv classify steps)
                    left     (vec (concat (when kw? (sweep-left-behind st kname from-ns))
                                          (sweep-patterns-left-behind st from pat)))
                    note     (sweep-note from left (some :strings? rows'))
                    miss     (unswept st)]
                (merge nsr
                       {:dry-run true
                        :forms (count steps)
                        :in-code (filterv (complement :strings?) rows')
                        :in-strings (filterv :strings? rows')
                        ;; the census BEFORE the run: every form and tracked
                        ;; file that names it, in any case — the coverage
                        ;; search the model ran beside the preview, answered
                                                :mentions (let [c (census st)]
                                    ;; a preview may not certify a coverage it does
                                    ;; not have: two previews of one concept, 190
                                    ;; forms and 54, over different sets, both said
                                    ;; nothing lay outside them
                                    {:forms (count (:forms c))
                                     :files (count (:files c))
                                     :note (if (seq miss)
                                             (str "every form and file naming " from
                                                  " in any case — but this sweep rewrites only"
                                                  " the spellings under :case-variants and"
                                                  " :plural-variants. :not-swept lists the ones"
                                                  " it will LEAVE; those are what to grep for"
                                                  " afterwards.")
                                             (str "every form and file naming " from
                                                  " in any case, and every spelling of it here"
                                                  " is one this sweep rewrites."))})}
                                              (when (seq miss) {:not-swept miss})
                       (when (seq file-hits) {:in-files (mapv #(select-keys % [:path :match]) file-hits)})
                                              (when (seq case-hits) {:case-variants case-hits})
                       (when (seq plur-hits) {:plural-variants plur-hits})
                       (when (seq requal) {:requalified requal})
                       (when (seq left) {:left-behind left})
                       (when note {:note note})))

              :else
              (let [r (if (seq steps)
                        (edit-group-once! session steps :prompt why :agent agent)
                        {:ok true})]
                (if (:error r)
                  ;; THE TEXT ROLLED BACK; THE NAMESPACE RENAMES DID NOT.
                  ;; They ran above as ordinary writes, one per namespace, and
                  ;; the atomic group covers only the form rewrites — so a
                  ;; refusal here leaves a store that LOOKS renamed and is not:
                  ;; `:export \"old.prefix\"` strings name a subtree that no
                  ;; longer exists, and the module rules inherit from the NAME.
                  ;; A bare refusal reads as \"the sweep did nothing\" — it was
                  ;; read that way, and reported that way, while 24 namespaces
                  ;; had moved.
                  (cond-> r
                    (seq (:renamed-namespaces nsr))
                    (-> (merge nsr)
                        (assoc :note
                               (str (count (:renamed-namespaces nsr))
                                    " namespace rename(s) are STILL APPLIED — they ran"
                                    " before the atomic group and were NOT rolled back"
                                    " with it, so this store is half-migrated. They are"
                                    " un-landed, so thread_drop takes them off and puts"
                                    " you back where the branch is. Or fix the refusal"
                                    " above and run the same sweep again: it is a no-op"
                                    " for the namespaces and applies only the text."))))
                  (do
                    ;; the tracked files, after the code landed: one file-put
                    ;; delta each, carrying the sweep's own prompt
                    (doseq [{:keys [path content]} file-hits]
                      (engine/commit-appended!
                       session
                       #(first (store/record-file-put % path content :prompt why :agent agent))
                       []))
                    ;; read off the store AFTER the writes, over the OLD token:
                    ;; whatever still names it was, by construction, not rewritten
                    (let [st*  (:store @session)
                          left (vec (concat (when kw?
                                              (sweep-left-behind st* kname from-ns))
                                            (sweep-patterns-left-behind st* from pat)))
                                                    rem  (census st*)
                          miss-after (unswept st*)
                          disk-twins (when-let [dir (:dir @session)]
                                       (vec (for [{:keys [path]} file-hits
                                                  :let [f (java.io.File. ^String dir ^String path)]
                                                  :when (.isFile f)
                                                  :let [txt (slurp f)
                                                        txt' (sweep txt)]
                                                  :when (not= txt txt')]
                                              (do (spit f txt') path))))
                          n-rem (+ (count (:forms rem)) (count (:files rem)))
                          note (str/join " "
                                         (remove nil?
                                                 [(sweep-note from left false)
                                                  ;; WHICH copy: a grep of the working tree finds
                                                  ;; the human branch's file untouched and reads
                                                  ;; it as a sweep that missed (eval25 opus)
                                                  (when (seq file-hits)
                                                    (str (count file-hits) " tracked file(s) rewritten in the STORE ("
                                                         (str/join ", " (map :path file-hits))
                                                         "); file_get shows the new text."
                                                         (if (seq disk-twins)
                                                           (str " Their working-tree copies (" (str/join ", " disk-twins)
                                                                ") were rewritten in place too — `git diff` will show"
                                                                " that change; it is this sweep's, nothing to investigate.")
                                                           (str " The working-tree copy is the human branch's until a"
                                                                " commit_point projects it — a sed on the disk copy is"
                                                                " drift, not a fix."))))
                                                                                                    (if (zero? n-rem)
                                                    (str "nothing named " from " remains — code,"
                                                         " strings, docstrings and tracked files,"
                                                         " in any case; there is nothing left to grep for.")
                                                    (str n-rem " mention(s) of " from " remain (:remaining)"
                                                         (if (seq miss-after)
                                                           (str " — as the spelling(s) "
                                                                (str/join ", " miss-after)
                                                                ", which this sweep does not rewrite."
                                                                " Sweep them as their own concept if they"
                                                                " are the same one.")
                                                           (str " — a spelling the boundary rule kept,"
                                                                " or prose no case rule covers;"
                                                                " read them."))))]))]
                      (cond-> (merge r (assoc nsr :forms (count steps)) {:remaining rem :note note})
                        ;; the string-hit forms AS REWRITTEN: a longer word can
                        ;; break a column a string was aligning, and the fix
                        ;; should need no read (eval27 opus)
                        (seq rows)         (assoc :rewritten
                                                  ;; EVERY rewritten form as it now reads — string hits
                                                  ;; first — under a cap; a value to patch inside one
                                                  ;; should need no read (eval29 opus: the renamed
                                                  ;; namespace read to bump a fee, every cell)
                                                  (let [hit? #(refactor/match-in-strings? (:orig %) pat*)
                                                        ordered (concat (filter hit? rows) (remove hit? rows))]
                                                    (loop [acc [] used 0 more (seq ordered)]
                                                      (if-let [row (first more)]
                                                        (let [nsx (symbol (str/replace (str (:ns row)) pat to))
                                                              ;; the form's name was swept too
                                                              nm  (symbol (sweep (str (:name row))))
                                                              e   (or (store/form-named st* nsx nm)
                                                                      (store/form-named st* nsx (:name row)))
                                                              src (when e (n/string (:node e)))]
                                                          (if (and src (< (count acc) 12) (<= (+ used (count src)) 10000))
                                                            (recur (conj acc {:form (symbol (str nsx) (str (:name e))) :source src})
                                                                   (+ used (count src)) (next more))
                                                            (recur acc used (next more))))
                                                        acc))))
                        ;; the working-tree TWIN of a rewritten tracked file, when it
                        ;; still names the word: rewritten in place. The rename means
                        ;; everything named that, and the model was doing this by
                        ;; hand with sed after a grep (eval27–29, every cell)
                        (seq disk-twins)   (assoc :files-on-disk disk-twins)
                        (seq file-hits)    (assoc :files (mapv :path file-hits))
                                                (seq case-hits)    (assoc :case-variants case-hits)
                        (seq plur-hits)    (assoc :plural-variants plur-hits)
                        (seq miss-after)   (assoc :not-swept miss-after)
                        (seq requal)       (assoc :requalified requal)
                        (seq pats)         (assoc :patterns-rewritten pats)
                        (seq left)         (assoc :left-behind left)))))))))))))

(defn revert-episode!
  "Scrap the agent's episode: roll every form it changed since its last
  done back to the boundary state — as ONE atomic verified group
  (honest provenance, not history erasure). Forms that OTHER agents also
  touched since the boundary are SKIPPED and reported in :skipped-shared,
  never stomped.

  This is the whole-episode grain. To walk back one write, or a short chain
  that went off the rails, without losing the rest of the episode, use
  `undo!` — same inverse, addressed by delta.

  The views that decide WHAT to revert walk the log, read from the journal
  once onto a copy (`with-history`); the group itself writes to the live
  session."
  [session & {:keys [agent prompt]}]
  (let [;; no explicit agent means \"MY episode\" — the session's own id, what
        ;; every live write carries. Left nil, `others` counted every
        ;; real-agent delta as someone else's and skipped the session's own
        ;; forms.
        agent   (or agent (:agent-id @session))
        hs      (with-history session)
        changes (history/query-changes hs :agent agent)
        others  (into #{}
                      (mapcat history/delta-fids)
                      (filter #(and (contains? history/content-ops (:op %))
                                    (not= agent (:agent %)))
                              (history/episode-span (:store @hs) agent)))
        {:keys [steps shared]} (history/revert-steps changes others)]
    (cond
      (empty? (:forms changes))
      {:reverted 0 :note "episode is empty — already at the last done"}

      (empty? steps)
      {:reverted 0 :skipped-shared shared
       :note "every changed form is shared with other agents"}

      :else
      (let [r (edit-group-once! session steps
                           :prompt (or prompt
                                       (str "revert episode"
                                            (when agent (str " of " agent))))
                           :agent agent)]
        (if (or (:error r) (:conflict r))
          r
          (let [reverted (vec (remove (set shared) (map :form (:forms changes))))]
            (engine/commit-appended!
             session
             (fn [base] (first (store/record-revert base :why prompt
                                                    :forms reverted
                                                    :agent agent)))
             [])
            (assoc r
                   :reverted (count steps)
                   :skipped-shared shared)))))))

(defn undo!
  "Walk back your own recent writes — the reach-for-it-without-thinking undo.
  Addressed by DELTA, not by name: `:deltas n` (default 1) undoes your last `n`
  content writes, `:to \"d123\"` undoes everything of yours after that delta.
  `:to` also takes a NAMED anchor — `:last-commit` (scrap everything since the
  last commit-point — the usual dead-end rollback) or `:last-done` (back to your
  last done point) — as a keyword or the same string over the wire. One atomic
  verified group, recorded as honest provenance rather than erased.

  Delta addressing is the point. `revert-form!` looks a form up by name, so it
  can never undo a DELETE — there is no name left to find. The log still holds
  the source, so undo puts it back. Forms another agent also wrote in the span
  are SKIPPED and reported in `:skipped-shared`, never stomped, which is what
  makes this safe to reach for while others are working.

  **`:deltas n` counts over the LOG, and REFUSES rather than reaching past.**
  Markers are transparent, but a delta whose op undo cannot invert
  (`:rename-ns`, `:move-forms`, `:ns-delete`, `:config-put`, a `module_*`
  declaration, anything that changes more than form sources) stops it, named
  in `:blocked` with nothing reverted. It used to count over the FILTERED
  sequence, so *the last one* meant *the last one that passed the filter*: it
  stepped over the head and reverted an older write while returning
  `:reverted 1` and `:skipped-shared []`. Reported by a consumer whose
  already-shipped CSS fix was rolled back underneath them and reached their
  user as a screenshot of a bug that had been fixed. Measured afterwards: 20
  op types and 1218 deltas in slopp's own store were steppable, so an
  ordinary `ns_rename` was enough to arm it.

  The conservative op set is not the bug and is unchanged: undo inverts form
  SOURCES, and a `:rename-ns` also re-keys the namespace, so inverting it here
  would restore the text under the new name. Not being able to undo something
  is fine; reaching past it is not.

  `:to` still walks forward from an anchor the caller named explicitly, which
  is a different request: there the address is right and the coverage may be
  partial.

  The log is read from the journal when asked (`with-history`, once, on a
  copy); the writes at the end go to the live session.

  Returns `{:reverted n :undid [delta-ids] :skipped-shared [...]}`, or
  `{:reverted 0 :blocked [{:delta :op :why}] :note ...}`."
  [session & {:keys [deltas to agent prompt]}]
  (let [;; undo means \"walk back MY writes\"; with no explicit agent that is the
        ;; session's own id — what every live write is tagged with. Left nil,
        ;; `mine?` counted every delta as mine while `others` counted every
        ;; real-agent delta as someone else's, so a session's own forms were
        ;; skipped as :skipped-shared, leaving the store red. One agent, one
        ;; ownership test.
        agent    (or agent (:agent-id @session))
        ;; the history views below walk the whole log — hydrated once, here,
        ;; on a COPY: the writes at the end go to the real session
        hs       (with-history session)
        all      (store/deltas (:store @hs))
        ;; :last-commit / :last-done name the two anchors worth rolling back to
        ;; without knowing a delta id — accepted as keyword or wire string.
        commit-anchor? (contains? #{:last-commit "last-commit" ":last-commit"} to)
        done-anchor?   (contains? #{:last-done "last-done" ":last-done"} to)
        to       (cond
                   commit-anchor?
                   (:id (last (filter #(= :commit (:op %)) all)))
                   done-anchor?
                   (:id (last (filter #(= :done (:op %)) all)))
                   :else to)
        mine?    (fn [d] (and (contains? history/content-ops (:op d))
                              (= agent (:agent d))))
        ;; POSITIONAL addressing counts over the LOG. `(take-last n (filter
        ;; mine? all))` counted over the SURVIVORS, so "the last one" meant
        ;; "the last one that passed the filter" — a different delta, chosen
        ;; silently, then reverted while reporting `:reverted 1` and
        ;; `:skipped-shared []`. Markers are transparent, because nobody means
        ;; "undo my :verify"; a marker that also carries content — :merge — is
        ;; not.
        transparent? (fn [d] (and (contains? fields/markers (:op d))
                                  (not (contains? history/content-ops (:op d)))))
        recent   (when-not to
                   (take-last (max 1 (or deltas 1)) (remove transparent? all)))
        blocked  (vec (for [d recent :when (not (mine? d))]
                        {:delta (:id d) :op (:op d)
                         :why (if (= agent (:agent d))
                                (str "undo inverts form SOURCES, and " (:op d)
                                     " changes more than sources — reverting it"
                                     " here would half-undo it")
                                (str "written by "
                                     (or (:agent d) "another agent")))}))
        target   (if to
                   (first (filter mine? (rest (drop-while #(not= to (:id %)) all))))
                   (first recent))]
    (cond
      (and (or commit-anchor? done-anchor?) (nil? to))
      {:reverted 0
       :note (str "no " (if commit-anchor? "commit" "done")
                  " to roll back to")}

      ;; BEFORE the not-target case: a blocked head is not "nothing to undo",
      ;; it is "the thing you named cannot be undone". Reported as a refusal
      ;; because the ADDRESS is what is wrong — you asked for the last n
      ;; writes, and reaching past them to revert something OLDER is doing
      ;; something adjacent to what was asked. `undo` is the tool an agent
      ;; reaches for at the moment it knows it made a mistake; that is the one
      ;; behaviour it must never have.
      (seq blocked)
      {:reverted 0
       :blocked blocked
       :note (str "refused, nothing was reverted: the last "
                  (count recent) " delta(s) include "
                  (count blocked) " that undo cannot invert — "
                  (str/join ", " (map #(str (:delta %) " (" (name (:op %)) ")")
                                      blocked))
                  ". Reverting past them would have restored an OLDER write"
                  " while reporting success, which is what this refusal"
                  " exists to prevent. To reach the earlier work anyway,"
                  " name the span explicitly with :to <delta>; to address one"
                  " form, edit_revert it by name.")}

      (not target)
      {:reverted 0
       :note (if to
               (str "nothing of yours after " to)
               "no writes of yours to undo")}

      :else
      (let [from    (:id target)
            changes (history/query-changes hs :agent agent :from from)
            span    (drop-while #(not= from (:id %)) all)
            others  (into #{}
                          (mapcat history/delta-fids)
                          (filter #(and (contains? history/content-ops (:op %))
                                        (not (mine? %)))
                                  span))
            {:keys [steps shared]} (history/revert-steps changes others)]
        (cond
          (empty? (:forms changes))
          {:reverted 0 :note (str "nothing of yours changed since " from)}

          (empty? steps)
          {:reverted 0 :skipped-shared shared
           :note "every changed form is shared with other agents"}

          :else
          (let [r (edit-group-once! session steps
                               :prompt (or prompt (str "undo back to " from))
                               :agent agent)]
            (if (or (:error r) (:conflict r))
              r
              (let [undid-ids (mapv :id (filter mine? span))
                    reverted  (vec (remove (set shared) (map :form (:forms changes))))]
                ;; mark the dead end so it stays findable: what was scrapped
                ;; and (if given) why. A vanished exploration teaches nothing.
                (engine/commit-appended!
                 session
                 (fn [base] (first (store/record-revert base :why prompt
                                                        :forms reverted
                                                        :undid undid-ids
                                                        :agent agent)))
                 [])
                (assoc r
                       :reverted (count steps)
                       :undid undid-ids
                       :skipped-shared shared)))))))))

(defn ^:export session-var-hint
  "The line a `query_eval` failure owes a caller who reached for a SESSION —
  or nil for any other error.

  Measured (s19): 121 refusals on one store named a `*session*` var (written
  unqualified deliberately — spelled with a namespace it reads as prose
  naming a form that exists, which is the one thing this is about), an
  agent trying to call session-taking fns from the oracle. There is no such var and there should not be one: a live session
  in eval would let a write bypass the delta pipeline, which is exactly what
  the observe gate refuses (T5). So unlike an argument SHAPE this cannot be
  repaired — the intent is real, the capability is deliberately absent — and
  the honest answer is to name the doors that do exist."
  [err]
  (when (re-find #"\*session\*" (str err))
    (str "there is no session var in the oracle, deliberately: eval observes,"
         " it never writes (a session here would let a write bypass the delta"
         " pipeline). The three doors that answer what you were asking:"
         " query_store {code \"(fn [store] …)\"} for anything about the STORE"
         " VALUE; the tools themselves for anything a session does (they hold"
         " it for you); explore {ops [{op check code …}]} to run assertions in"
         " the image. For a pure fn, call it directly — query_eval already has"
         " every namespace loaded.")))

^:reads (defn query-eval
  "Observe-only eval against the live image (the oracle): call anything —
  including effectful fns — but (re)defining code is rejected (T5); writes go
  through the edit tools so provenance stays airtight. `:gate` swaps the
  scanner for a caller that has already gated the untrusted part of `code`
  itself (`check` gates the question, then wraps it in trusted scaffolding).

  Returns the values, or `{:error msg}` when the eval actually FAILED — nREPL's
  `eval-error` status, not merely something reaching stderr. A library that
  prints at load (`WARNING: abs already refers to …`) is output, not failure:
  its warning is dropped here, as in any REPL, and the values come back. It used
  to come back as `{:error …}` with the values discarded, and since a second
  eval finds the namespace loaded and prints nothing, the failure vanished on
  retry.

  A failure that reached for a SESSION carries [[session-var-hint]]: that
  miss was the commonest eval refusal measured on a real store, and it is
  one the pipeline cannot repair — the capability is absent on purpose."
  [session code & {:keys [gate]}]
  (if-let [err ((or gate edit/observe-gate) code)]
    {:error err}
    ;; strip :reload in the owned image — no source files exist to reload, so it
    ;; would only throw FileNotFoundException (store ns) or waste a jar re-read
    (let [r (repl/eval-checked! (:image @session) (edit/strip-image-reload code))]
      (if (:err r)                                  ; F-3c2: never a silent []
        {:error (if-let [h (session-var-hint (:err r))]
                  (str (:err r) " — " h)
                  (:err r))}
        (:values r)))))

^:reads (defn query-call
  "Observe-only INVOKE of one var in the live image: `(query-call session
  'app.core/f 1 2)` — the structured face of the common query-eval case.
  The var reference is CARRIED (a quoted symbol in a designated position —
  renames, moves, and the unused gate all see it) instead of hidden in an
  eval string; args must be printable data (they cross the nREPL boundary
  as pr-str). query-eval remains the escape hatch for genuinely arbitrary
  expressions."
  [session qsym & args]
  (query-eval session
              (str "(" qsym (apply str (map #(str " " (pr-str %)) args)) ")")))

(defn- canonicalize-steps
  "Canonicalize every add/replace step's source against the store's aliases
  (`refactor/canonicalize-refs`) and prepend the requires those rewrites owe
  as `:require` steps, one per [ns spec], so the group verifies them together
  and a form's hot-load sees its alias. Returns `{:steps :canonicalized}` —
  the second empty when nothing moved. A step whose source does not parse is
  left for the group's own refusal."
  [session steps]
  (let [st      (:store @session)
        aliases (read.modules/project-aliases st)
        out     (reduce (fn [{:keys [steps owed rewrites]} step]
                          (let [{:keys [action ns source]} step
                                nsx (some-> ns (as-> n (symbol (str n))))]
                            (if (and nsx source (contains? #{nil :add :replace} action)
                                     (get-in st [:namespaces nsx]))
                              (let [pf (edit/parse-form source)]
                                (if-let [node (:node pf)]
                                  (let [c (refactor/canonicalize-refs st nsx node aliases)]
                                    {:steps    (conj steps (if (seq (:rewrites c))
                                                             (assoc step :source (n/string (:node c)))
                                                             step))
                                     :owed     (into owed (map (fn [spec] [nsx spec])) (:requires c))
                                     :rewrites (into rewrites (map #(assoc % :ns nsx) (:rewrites c)))})
                                  {:steps (conj steps step) :owed owed :rewrites rewrites}))
                              {:steps (conj steps step) :owed owed :rewrites rewrites})))
                        {:steps [] :owed [] :rewrites []}
                        steps)
        reqs    (mapv (fn [[nsx spec]] {:action :require :ns nsx :require spec})
                      (distinct (:owed out)))]
    {:steps (into reqs (:steps out))
     :canonicalized (vec (:rewrites out))
     :requires (mapv (fn [[nsx spec]] {:added spec :ns nsx}) (distinct (:owed out)))}))

(defn- purpose-doc
  "The ns docstring a `prompt` becomes when a namespace is born without one:
  the ask, first paragraph, capped at 300 chars — or nil for a blank prompt.
  The ask says why the namespace exists, which is exactly what the
  namespace-purpose rule asks for and what no tool can derive."
  [prompt]
  (let [p (some-> prompt str str/trim)]
    (when (and p (not (str/blank? p)))
      (let [para (str/trim (first (str/split p #"\n\s*\n")))]
        (if (> (count para) 300) (str (subs para 0 297) "…") para)))))

(defn- with-purpose
  "`source` (a whole namespace's text) with `prompt` inserted as the ns
  form's docstring when the ns form has none and the prompt says something;
  unchanged otherwise. Textual on purpose: the ns form is the first form
  and its docstring, when present, is the string right after the name."
  [source ns-sym prompt]
  (let [d (purpose-doc prompt)
        m (re-matcher (re-pattern (str "^\\s*\\(ns\\s+" (java.util.regex.Pattern/quote (str ns-sym)) "(?![^\\s)])")) (str source))]
    (if (and d (.find m))
      (let [end  (.end m)
            rest (subs (str source) end)]
        (if (re-find #"^\s*\"" rest)
          source
          (str (subs (str source) 0 end) "\n  " (pr-str d) rest)))
      source)))

(defn- bracketed-require
  "A require clause string with its brackets: `clojure.set :as set` — a bare
  lib followed by its options, no outer vector — becomes `[clojure.set :as
  set]`; a bare lib name, an already-bracketed clause, or one carrying
  metadata (`^:side-effect [lib :as r]`, the done-point's own spelling) is
  returned as it is. The shape is unambiguous and it failed to load (eval25
  opus)."
  [s]
  (let [t (str/trim (str s))]
    (if (re-matches #"[A-Za-z][A-Za-z0-9_.*+!?<>=$%&|'-]*\s+:.*" t)
      (str "[" t "]")
      t)))

(defn ^:export note-event!
  "Queue an EVENT for this session's agent — `{:kind :note}` — to ride the
  next tool answer as one leading line. This is push in the only form that
  reaches a model: an MCP notification goes to the client's log and never
  to the conversation, while the next answer always does. Bounded to the
  last five; an agent that has not called in a while gets the recent ones,
  not a history."
  [session event]
  (swap! session update :events
         (fn [evs] (vec (take-last 5 (conj (or evs []) event)))))
  nil)

(defn edit-replace!
  "Replace the form `nm` in `ns-sym` with `new-source` (O1 whole-form replace):
  pipeline + hot-reload, then re-verify — only the tests the trace map says
  exercise this form (D1), cross-checked on a fresh image if red (D5) — and
  record the outcome as provenance (C4). A replace that RENAMES the form
  refuses while committed callers still reference the old name — edit_rename
  is the atomic path (the store must keep cold-loading).

  A write refused only because it named an alias the ns form lacks, when
  exactly one namespace can supply it, gets the require added as a `:system`
  write and is retried once (`auto-require-retry`); the result then carries
  `:auto-require`. `:system true` marks a replace the pipeline itself makes
  (that require) on its delta."
  [session ns-sym nm new-source & {:keys [prompt agent system no-auto-require]}]
  (let [t0 (System/nanoTime)
        ;; qualified in, alias on store — the single-form door's half; the
        ;; pipeline's own :system writes (a require) are already canonical
        new-source (if system new-source (canonical-source! session ns-sym new-source))
        pf       (edit/parse-form new-source)
        ;; a replaced defmethod leaves its OLD dispatch registered unless the
        ;; replacement re-registers the same [multi dispatch] (#131): hot-load
        ;; evals the new form, but nothing removes the old method, so the image
        ;; answers BOTH dispatches while the store says one — green-when-red.
        old-node (some-> (store/form-named (:store @session) ns-sym nm) :node)
        old-s    (when old-node
                   (try (n/sexpr old-node) (catch Exception _ nil)))
        new-s    (when-not (:error pf)
                   (try (n/sexpr (:node pf)) (catch Exception _ nil)))
        unregister
        (when (and (seq? old-s) (= 'defmethod (first old-s)) (> (count old-s) 2)
                   (not (and (seq? new-s) (= 'defmethod (first new-s))
                             (= (second old-s) (second new-s))
                             (= (nth old-s 2) (nth new-s 2)))))
          (format "(when-let [v (ns-resolve '%s '%s)]
                     (when (instance? clojure.lang.MultiFn @v)
                       (remove-method @v
                         (binding [*ns* (find-ns '%s)] (eval '%s)))))"
                  ns-sym (second old-s) ns-sym (pr-str (nth old-s 2))))
        new-name (when-not (:error pf) (store/form-symbol (:node pf)))
        stranded (when (and new-name (not= new-name (symbol (str nm)))
                            (store/form-named (:store @session) ns-sym nm))
                   (let [st    (:store @session)
                         known (set (keys (:namespaces st)))]
                     (vec (distinct
                           (for [nsx known
                                 u   (:var-usages (analyze/analyze (store.render/render-ns st nsx)))
                                 :when (and (= (symbol (str ns-sym)) (:to u))
                                            (= (symbol (str nm)) (:name u))
                                            (not (and (= nsx (symbol (str ns-sym)))
                                                      (= (symbol (str nm)) (:from-var u)))))]
                             (symbol (str nsx) (str (:from-var u))))))))]
    (if (seq stranded)
      {:error (str "this replace RENAMES " nm " → " new-name " but committed"
                   " callers still reference " ns-sym "/" nm ": " stranded
                   " — edit_rename rewrites every caller atomically (or land"
                   " the callers in this same change)")}
      (let [pre-warned (set (map :var (edit/ns-warnings (:store @session) ns-sym)))
            load? (store/jvm-loadable? (:store @session) ns-sym)
            r (engine/rebased-write!
               session
               (fn [base] (edit/replace-form base ns-sym nm new-source
                                             :prompt prompt :agent agent
                                             :system system))
               (fn [base] (:node (store/form-named base ns-sym nm)))
               (symbol (str ns-sym) (str nm))
               ns-sym
               :load? load?)]
        (if (or (:error r) (:conflict r))
          (if (or no-auto-require system)
            r
            ;; a missing require, then a missing module edge — one retry flag
            ;; covers both, so each runs at most once
            (let [retry #(edit-replace! session ns-sym nm new-source
                                        :prompt prompt :agent agent
                                        :no-auto-require true)
                  r1    (auto-require-retry session ns-sym r retry)]
              (if (:error r1)
                (auto-module-dep-retry! session r1 retry :agent agent)
                r1)))
          (let [qform    (symbol (str ns-sym) (str nm))
                new-nm   (:name (store/form-by-id (:store r)
                                                  (:form-id (:delta r))))
                edited   (into #{qform}
                               (when new-nm [(symbol (str ns-sym) (str new-nm))]))
                affected (let [a (or (engine/affected-tests session ns-sym nm)
                                     ;; an alias-only require addition is semantically
                                     ;; inert — verify NOTHING rather than the whole
                                     ;; namespace reach (frictions #2); [] is honest
                                     ;; (:coverage :none), never a claimed green
                                     (when (engine/inert-ns-require-change?
                                            (:store @session) (:form-id (:delta r))
                                            (engine/prior-source session (:form-id (:delta r))))
                                       []))]
                             ;; …and re-point it through the rename, exactly as
                             ;; `edited` is one binding above. The affected set was
                             ;; asked for under the OLD name, so when this write
                             ;; renames the form it comes back naming something the
                             ;; same write just retired, and the run resolves to
                             ;; nothing. Only a renamed TEST notices: renaming an
                             ;; implementation form leaves its covering tests' names
                             ;; alone. Reported by slopp-ui 2026-08-02, where two
                             ;; renamed deftests were red and neither said so.
                             (if (and a new-nm (not= new-nm nm))
                               (mapv #(if (= % qform)
                                        (symbol (str ns-sym) (str new-nm))
                                        %)
                                     a)
                               a))
                untested (and (nil? affected) (seq (:test-map @session))
                               (not (re-find #"^\(\s*(?:clojure\.test/)?deftest\b"
                                             (str/triml new-source))))
                _        (when (and new-nm (not= new-nm nm))
                           (repl/eval! (:image @session)
                                       (format "(ns-unmap '%s '%s)" ns-sym nm)))
                _        (when unregister
                           (repl/eval! (:image @session) unregister))
                ;; ROOT of frictions 1 and 17. A per-form hot-load is equivalent to
                ;; LOADING the form only when the form's whole contribution to
                ;; the image is its own var binding. Anything that CAPTURED a
                ;; value from it — a derived def, an evaluated metadata schema —
                ;; still holds the old one, and re-evaluating one form replays
                ;; none of that. `--live` never had this bug because it reloads
                ;; whole NAMESPACES; the oracle did because it reloads forms.
                ;;
                ;; So: keep the fast path, and when something captured, repair
                ;; through the same `load-ns!` every other loader uses. This is
                ;; measured to be rare — 35 of 2202 forms in this store capture
                ;; at load at all — so an ordinary defn write pays nothing.
                ;; Before verification deliberately: tests must run against a
                ;; repaired image, not a half-stale one.
                captured (when load?
                           (rules.currency/stale-after (:image @session) (:store @session) (:form-id (:delta r))))
                reloaded (when (seq captured)
                           (vec (sort (distinct (map (comp symbol namespace)
                                                     captured)))))
                reload-errs
                (when (seq reloaded)
                  (not-empty
                   (into {}
                         (keep (fn [nsx]
                                 (when-let [e (image/load-ns! (:image @session)
                                                              (:store @session)
                                                              nsx)]
                                   [nsx e])))
                         reloaded)))
                ;; what the repair could NOT reach — normally nothing, and
                ;; reported rather than assumed away when it happens
                stale    (when (seq reloaded)
                           (not-empty (rules.currency/stale-after (:image @session) (:store @session) (:form-id (:delta r)))))
                ;; a ^:live-handle constructor changed shape: the map already in
                ;; the session was built by the OLD code and no write can
                ;; reach it. Rebuild BEFORE verification, which would
                ;; otherwise be the first thing to read the stale handle —
                ;; and discard the warm spare, which was built under the old
                ;; code too (that is how it broke a second time).
                handle-shift (when (and old-node (:node pf))
                               (edit/live-handle-shape-change old-node (:node pf)))
                ;; The rebuild can FAIL mid-migration and that is normal: a shape
                ;; change lands on the constructor BEFORE its callers are
                ;; updated, so the fresh image may launch mis-configured (a
                ;; renamed option key read as nil). Keep the working image and
                ;; report it — the episode continues, and the next write once
                ;; the callers catch up rebuilds cleanly. Throwing here would
                ;; make a legitimate in-progress migration look like a broken
                ;; write.
                rebuild-err
                (when handle-shift
                  (swap! session assoc :spare nil)
                  (try (engine/fresh-image! session) nil
                       (catch Throwable t (ex-message t))))
                ;; no trace evidence → fall back to the tests that REACH this
                ;; namespace, not to tests named after it (there are none)
                scope    (if affected
                           ns-sym
                           (or (seq (engine/covering-test-nses
                                     (:store @session) [ns-sym]))
                               ns-sym))
                summary  (if load?
                             (engine/run-verification! session scope affected
                                                        :edited edited)
                             engine/cljs-deferred-summary)
                existing (count (filter (comp pre-warned :var) (:warnings r)))
                recompiled (engine/after-write! session ns-sym)]
            (engine/commit-appended! session
                              #(store/record-verification % ns-sym summary) [])
            (engine/with-ms
              (cond-> {:delta    (:delta r)
                       ;; T3: only NEW violations; pre-existing ones as a count
                       :warnings (vec (remove (comp pre-warned :var) (:warnings r)))
                       :test     summary
                       :affected (or affected :all)}
                (:image-healed r) (assoc :image-healed true)
                ;; what this write changed BEYOND what was asked — a lost type
                ;; hint, docstring or arity. Reported, never refused.
                (seq (:drift r)) (assoc :drift (:drift r))
                ;; friction 1: forms this write left holding a value computed
                ;; from the OLD source. Not :drift — that key is taken, and it
                ;; means something else on this very map.
                ;; the repair, reported: a namespace reload is a real cost and a
                ;; silent one reads as an unexplained slow write
                (seq reloaded) (assoc :image-reloaded reloaded)
                reload-errs    (assoc :image-reload-failed reload-errs)
                (seq stale)    (assoc :stale-in-image (vec stale))
                ;; say WHY the image was replaced — a silent rebuild is a
                ;; surprising cost, and the reason is the teaching
                handle-shift (assoc :image-rebuilt
                                    (cond-> (assoc handle-shift
                                                   :reason :live-handle-shape-change)
                                      rebuild-err
                                      (assoc :rebuild-failed rebuild-err
                                             :note (str "kept the working image — normal"
                                                        " MID-MIGRATION, when the constructor"
                                                        " has changed but its callers have"
                                                        " not. Update them and the next write"
                                                        " rebuilds cleanly."))))
                (pos? existing)   (assoc :existing-warnings existing)
                untested          (assoc :untested true)
                (:red-first r)    (assoc :red-first (:red-first r)
                                         :note (str "these vars don't exist yet — stubbed"
                                                    " in-image as failing (red-first);"
                                                    " implement them to go green."))
                (:carried-errors r) (assoc :carried-errors (:carried-errors r))
                recompiled          (merge recompiled))
              t0)))))))

(defn add-require!
  "F5: add one require clause to `ns-sym`'s ns form — structural edit through
  the normal replace pipeline (delta, hot-reload, verification). A clause
  already present in the same spelling is a SUCCESS with nothing written
  (`{:ok true :already true}`): the state asked for holds; a different
  spelling REPLACES the clause (`:replaced`). A clause sent without its
  brackets (`clojure.set :as set`) is wrapped — the shape was unambiguous
  and it failed to load (eval25 opus). A clause naming a namespace of YOURS
  that does not exist yet creates it empty first (`:also-created`), the same
  red-first seam `ns_create` has: a require of an unwritten namespace was a
  compile error, not a failing test.

  Forwards `:agent` (#132): without it the delta landed agent-nil and the edit
  never entered ANY agent's episode — `done` never linted, normalized, or
  verified an ns_add_require at the boundary. Found by the collapse fix's own
  e2e: the ns-form change it staged simply never arrived.

  Attaches `:tier-note` when a TIERED namespace gains an in-store dep no
  declaration covers: undeclared defaults :external, so the consumer's tier
  claim dies at the next full_check's layering pass — and the write that
  creates the dependency is the one moment the declaration is cheap and the
  author's context is loaded (frictions #4: the signal used to arrive two
  gates late)."
  [session ns-sym require-str & {:keys [prompt agent system]}]
  (if-let [f (store/form-named (:store @session) ns-sym ns-sym)]
    (let [require-str (bracketed-require require-str)
          also    (unwritten-requires (:store @session) ns-sym [require-str])
          sub-err (some (fn [n]
                          (:error (ingest! session n (str "(ns " n ")\n")
                                           :agent agent :prompt prompt)))
                        also)
          r       (if sub-err {:error sub-err}
                      (edit/add-require-source (n/string (:node f)) require-str))]
      (cond
        (:error r)   r
        (:already r) {:ok true :already true :ns ns-sym :require require-str}
        :else
        (let [res (edit-replace! session ns-sym ns-sym (:src r)
                                 :prompt (or prompt (str "add require " require-str))
                                 :agent agent
                                 :system system)
              st  (:store @session)
              lib (try (let [spec (edn/read-string (str require-str))]
                         (cond (vector? spec) (first spec)
                               (symbol? spec) spec))
                       (catch Exception _ nil))
              note (when (and (nil? (:error res)) lib
                              (contains? (:namespaces st) lib)
                              (tiers/tier-declared? st ns-sym)
                              (contains? #{:pure :internal}
                                         (tiers/tier-for st ns-sym))
                              (not (tiers/tier-declared? st lib)))
                     (str ns-sym " is declared " (tiers/tier-for st ns-sym)
                          " and now depends on UNDECLARED " lib " (defaults"
                          " :external — full_check's tier-layering will flag"
                          " this). Declare it while the context is loaded:"
                          " module_purity {module \"" lib "\" tier \"...\"} —"
                          " a new ns's tier is cheapest at creation."))]
          (cond-> res
            note           (assoc :tier-note note)
            (:replaced r)  (assoc :replaced (:replaced r))
            (:upgraded r)  (assoc :upgraded (:upgraded r))
            (:merged-refer r) (assoc :merged-refer (:merged-refer r))
            (seq also)     (assoc :also-created (vec also))))))
    {:error (str "no namespace " ns-sym " (create it first)")}))

(defn- auto-require-retry
  "The write path's repair for the commonest mechanical refusal: `r` failed
  to compile because it named an alias the ns form lacks and exactly ONE
  namespace can supply it (`edit/missing-alias-require`). Add that require
  as a `:system` write with the pipeline's own prompt, then run `retry` —
  the same write once more — and stamp what happened on its result as
  `:auto-require {:added spec :ns ns-sym}`. The require itself is often the
  namespace's FIRST crossing into an undeclared module, so the module gate
  refuses the require write and nothing repaired the group at all (s17: the
  \"No such namespace\" class that survived s15, nineteen refusals in the XL
  cells) — that refusal names the edge, so it is declared and the require
  added again, stamped `:auto-module-dep` beside. Any other refusal, an
  ambiguous alias (`missing-alias-hint` names the candidates in the
  message), or a require that still fails to land, returns `r` with
  `:auto-require-refused {:ns :spec :error}` — a repair that could not
  happen says so rather than vanishing. The caller passes
  `:no-auto-require true` on the retry so this runs once."
  [session ns-sym r retry & {:keys [agent]}]
  (if-let [spec (and (:error r)
                     (edit/missing-require (:store @session) (:error r)))]
    (let [add (fn [] (add-require! session ns-sym spec
                                   :prompt fields/auto-require-prompt :system true
                                   :agent agent))
          ar  (add)
          ar  (cond
                (:error ar)   (auto-module-dep-retry! session ar add :agent agent)
                ;; already there — this namespace is not the one the alias is
                ;; missing from; reported as a refusal so the caller moves on
                (:already ar) (assoc ar :error "already required")
                :else         ar)]
      (if (:error ar)
        (assoc r :auto-require-refused {:ns ns-sym :spec spec :error (:error ar)})
        (let [r2 (retry)]
          ;; the require LANDED whatever the retry then says — stamp it, so a
          ;; group that next trips the module gate (and lands via THAT
          ;; retry) still reports both repairs
          (cond-> (assoc r2 :auto-require {:added spec :ns ns-sym})
            (:auto-module-dep ar) (assoc :auto-module-dep (:auto-module-dep ar))))))
    r))

(defn add-form!
  "Add a new top-level form to `ns-sym` (O1 base write): dialect gate, `:add`
  delta, hot-reload into the image, verification, provenance. Appended; its
  place in the namespace is derived at commit (definitions before callers),
  so there is nothing to say about WHERE. Returns {:delta :warnings :test
  :affected} or {:error msg}.

  `source` holding SEVERAL top-level forms is the batch write (`add-forms!`):
  one atomic group, verified once, reported per form as `:forms` — growing a
  namespace no longer costs one round trip per form.

  A write refused only because it named an alias the ns form lacks, when
  exactly one namespace can supply it, gets the require added as a `:system`
  write and is retried once (`auto-require-retry`); the result then carries
  `:auto-require`.

  A :cljs (non-jvm-loadable) namespace is authored the same way but its form
  references js/* / the DOM and cannot load into the JVM oracle, so the write
  SKIPS the per-form hot-load (`:load? false`) and defers verification to the
  ClojureScript compiler — reporting `cljs-deferred-summary` (:unverified,
  reason :cljs-deferred-to-compile) rather than running the suite. D-web-cljs."
  [session ns-sym source & {:keys [prompt agent no-auto-require]}]
  (let [t0 (System/nanoTime)
        ;; qualified in, alias on store — the single-form door's half
        source (canonical-source! session ns-sym source)
        pfs (edit/parse-forms source)]
    (if (and (nil? (:error pfs)) (< 1 (count (:nodes pfs))))
      (add-forms! session ns-sym (:nodes pfs) :prompt prompt :agent agent)
      (let [{:keys [node error]} (edit/parse-form source)
            nm (some-> node store/form-symbol)
            iso (when node
                  (edit/isolation-refusal
                   (edit/require-aliases (:store @session) ns-sym) node))]
        (cond
          error {:error error}

          iso {:error iso}

          (and nm (store/form-named (:store @session) ns-sym nm))
          {:error (str nm " already exists in " ns-sym)}

          :else
          (let [pre-warned (set (map :var (edit/ns-warnings (:store @session) ns-sym)))
                load?      (store/jvm-loadable? (:store @session) ns-sym)
                r (engine/rebased-write!
                   session
                   (fn [base]
                     (if (and nm (store/form-named base ns-sym nm))
                       {:error (str nm " already exists in " ns-sym)}
                       (if-let [[st' d] (store/append-form base ns-sym node
                                                           :prompt prompt :agent agent)]
                         ;; nameless forms too: a defmethod names its TARGET, so nm is nil
                         ;; here — and `(when nm …)` skipped the whole chassis for
                         ;; exactly the carrier whose ^:app/entry marker can only be
                         ;; caught by a gate's nameless arm. Named gates no-op on nil.
                         (if-let [merr (gates/gate-refusal st' ns-sym nm)]
                           {:error merr}
                           {:store st' :delta d})
                         {:error (str "no namespace " ns-sym " (ingest it first)")})))
                   (fn [base] (when nm (:node (store/form-named base ns-sym nm))))
                   (symbol (str ns-sym) (str (or nm "anonymous")))
                   ns-sym
                   :load? load?)]
            (if (or (:error r) (:conflict r))
              (if no-auto-require
                r
                ;; the two mechanical refusals the write path repairs itself:
                ;; a missing require, then a missing module edge. One retry
                ;; flag covers both, so each runs at most once.
                (let [retry #(add-form! session ns-sym source
                                        :prompt prompt :agent agent
                                        :no-auto-require true)
                      r1    (auto-require-retry session ns-sym r retry)]
                  (if (:error r1)
                    (auto-module-dep-retry! session r1 retry :agent agent)
                    r1)))
              (let [edited     (if nm #{(symbol (str ns-sym) (str nm))} #{})
                    affected   (when (and load? nm) (engine/affected-tests session ns-sym nm))
                    summary    (if load?
                                 (engine/run-verification! session ns-sym affected
                                                            :edited edited)
                                 engine/cljs-deferred-summary)
                    all-w      (edit/ns-warnings (:store @session) ns-sym)
                    existing   (count (filter (comp pre-warned :var) all-w))
                    advisories (when nm (:advisories (gates/gate-check
                                                      (:store @session) ns-sym nm)))
                    recompiled (engine/after-write! session ns-sym)]
                (engine/commit-appended! session
                                          #(store/record-verification % ns-sym summary)
                                          [])
                (engine/with-ms
                  (cond-> {:delta    (:delta r)
                           ;; T3: only NEW violations; pre-existing as a count
                           :warnings (vec (remove (comp pre-warned :var) all-w))
                           :test     summary
                           :affected (or affected :all)}
                    (:image-healed r) (assoc :image-healed true)
                    (pos? existing)   (assoc :existing-warnings existing)
                    (:red-first r)    (assoc :red-first (:red-first r)
                                             :note (str "these vars don't exist yet —"
                                                        " stubbed in-image as failing"
                                                        " (red-first); implement them to"
                                                        " go green."))
                    (:carried-errors r) (assoc :carried-errors (:carried-errors r))
                    (:red-first-arity r)
                    ;; as-> rather than assoc: a test can BOTH name a missing var
                    ;; and call a known one at a new arity, and a plain assoc would
                    ;; drop the stub note that the other clause just wrote
                    (as-> m (assoc m :red-first-arity (:red-first-arity r)
                                   :note (str (when (:note m) (str (:note m) " "))
                                              "this calls an existing var at an arity"
                                              " it does not have yet — the write landed"
                                              " (red-first) and the call will throw"
                                              " ArityException until you implement that"
                                              " arity. That throw IS the red you asked"
                                              " for, not a bug.")))
                    (seq advisories)    (assoc :advisories advisories)
                    recompiled          (merge recompiled))
                  t0)))))))))

(defn remove-require!
  "Symmetric counterpart of add-require!: structurally remove `lib`'s require
  spec from `ns-sym`'s ns form, through the normal replace pipeline.
  Forwards `:agent` (#132) for the same reason add-require! does — an
  agent-nil delta never enters any episode."
  [session ns-sym lib & {:keys [prompt agent]}]
  (if-let [f (store/form-named (:store @session) ns-sym ns-sym)]
    (let [r (edit/remove-require-source (n/string (:node f)) lib)]
      (if (:error r)
        r
        (edit-replace! session ns-sym ns-sym (:src r)
                       :prompt (or prompt (str "remove require " lib))
                       :agent agent)))
    {:error (str "no namespace " ns-sym)}))

(defn prune-requires!
  "Done-point require hygiene — the agent never manages unused requires; done
   does, and there is deliberately no MCP tool for it. For each require kondo
   reports unused (`done/unused-requires`), TRY removing it and re-verify.

   Removing a kondo-unused require cannot break COMPILATION — nothing used it —
   so the only ways it can break are (1) a test the removal's own affected set
   catches, or (2) a load effect a cold load would lose: an orphaned in-store
   target whose closure REGISTERS something (a defmethod the reference graph
   can't see). The live image already has that registration loaded, so a green
   in-image verdict does NOT prove the require dead — `require-orphaned-registrar?`
   is the static backstop.

   Genuinely dead → drop it. Load-bearing (or the removal went red) → restore it
   WITH a `^:side-effect` marker, so it no longer reads as unused and done never
   re-tries it. Returns `{:pruned [lib …] :kept [lib …]}`."
  [session ns-sym & {:keys [prompt agent]}]
  (reduce
   (fn [acc {:keys [lib marked]}]
     (let [r    (remove-require! session ns-sym lib
                                 :prompt (or prompt (str "done: try pruning unused require " lib))
                                 :agent agent)
           red? (let [t (:test r)]
                  (boolean (and t (or (pos? (:fail t 0)) (pos? (:error t 0))))))]
       (cond
         ;; couldn't remove it at all (conflict/refusal) — leave it untouched
         (or (:error r) (:conflict r))
         (update acc :kept conj lib)

         ;; removing it broke a test, or would lose a registration on cold load:
         ;; restore it, marked, so it is not reported unused or re-tried
         (or red? (require-orphaned-registrar? (:store @session) lib))
         (do (add-require! session ns-sym marked
                           :prompt (str "done: keep load-bearing require " lib
                                        " (removing it breaks a cold load) — marked ^:side-effect")
                           :agent agent)
             (update acc :kept conj lib))

         :else
         (update acc :pruned conj lib))))
   {:pruned [] :kept []}
   (done/unused-requires (:store @session) ns-sym)))

(defn edit-subform!
  "Item 5 — paredit's invariant, agent-shaped: replace the UNIQUE structural
  occurrence of `match` inside form `form-name` with `new-src`
  (content-addressed). With `:text true` the match is RAW TEXT instead — the
  escape hatch for string literals and docstrings. With `:where {k v ...}` the
  target is the unique MAP containing those entries (registry-style edits
  by key, no exact text needed) and `match` is ignored. `:where` ADDRESSES
  a row rather than asserting a value: both sides are compared by the
  spelling they answer to, so `\"stored-name\"` reaches a row stored as
  `:stored-name` — registry rows are keyed by keywords and the wire this
  arrives over has none. Rides the full
  replace pipeline: dialect gate on the RESULTING form, rebase/conflict
  commit, verification, provenance.

  With `:wrap true`, `new-src` is a TEMPLATE and `$1` is the matched form —
  `(let [n 1] $1)` nests what was there inside what you wrote. This docstring
  used to say wrap was 'just a new subform containing the old', which was true
  and not cheap: expressing it meant retyping the matched form inside the
  replacement, so a two-line change to a large form became a large paste.
  `$1` is the same template mechanism `change_signature` uses for call sites.

  A match that is a FRAGMENT — it opens a delimiter it does not close — is
  REPAIRED as a text replace when it appears exactly once in the form, and
  the result says `:repaired {:text true}`. The refusal already computed
  the fragment's one home; that is the unambiguous-intent condition a repair
  needs, and a replacement that unbalances the form is refused by the parse
  the way any text edit is. Measured (s20): 16 such refusals in one real
  session, every one a round trip."
  [session ns-sym form-name match new-src & {:keys [prompt agent text where wrap]}]
  (let [store (:store @session)
        plan  (cond
                (seq where) (refactor/keyed-replace-plan store ns-sym form-name where new-src)
                text        (refactor/text-replace-plan store ns-sym form-name match new-src)
                :else       (refactor/subform-replace-plan store ns-sym form-name
                                                           match new-src (boolean wrap)))
        [plan repaired] (if (and (:error plan) (:suggestion plan)
                                 (not text) (empty? where) (not wrap))
                          (let [tp (refactor/text-replace-plan store ns-sym form-name match new-src)]
                            (if (:error tp) [plan nil] [tp {:text true}]))
                          [plan nil])]
    (if (:error plan)
      plan
      (let [r (edit-replace! session ns-sym form-name (:new-form-src plan)
                             :prompt (or prompt (str "subform edit in " form-name))
                             :agent agent)]
        (cond-> r
          (and repaired (nil? (:error r)))
          (assoc :repaired repaired
                 :note (str "the match was a fragment (it opened a delimiter it did"
                            " not close) with one home in " form-name " — landed as a"
                            " TEXT replace; `text: true` says so up front next time")))))))

(defn create-ns!
  "F4: bring a brand-new namespace into being — two modes:
   - **scaffold** (`:requires`, clause strings like \"[clojure.string :as str]\"):
     build an empty `(ns …)` to grow form-by-form with red-first TDD. The default.
   - **content** (`:source`, the whole namespace text incl. its own `(ns …)`):
     land the entire namespace in one verified call — forward refs within the
     file resolve as a unit, like a real `.clj` load. For ported/reference/data
     code that isn't subject to red→green.
   Both together: the requires are MERGED into the source's ns form (eval26
   opus: refused as exclusive, one turn). On a namespace that already EXISTS,
   `:requires` alone adds those requires and overwrites nothing
   (`:already-exists true`); `:source` on an existing namespace is still the
   overwrite `ingest!` refuses.
   `:platform` (:jvm/:cljc/:cljs) declares the namespace's target platform
   (module_platform grain = this namespace) BEFORE the source lands, so a
   client ns is BORN :cljs — its first js/* form defers to the cljs compiler
   instead of failing to load into the JVM oracle (the inherited-default
   footgun). A bad platform refuses the whole create.

   **A scaffold may require a namespace that does not exist yet**, which is how
   red-first works across a namespace boundary: each such require is created
   EMPTY and reported in `:also-created`. Without it a spec-first write does not
   land red, it fails to load — a refusal, not a failing test. `unwritten-requires`
   holds the rule for which requires qualify and why a library never does.

   Delegates to `ingest!` (the shared engine); overwrite is refused there.
   `:prompt` — the ask — rides every ingest it delegates, so the namespace's
   birth answers \"why does this exist\"; a namespace born without a docstring
   stores the prompt as one."
  [session ns-sym & {:keys [requires source agent platform prompt doc]}]
  (let [requires (mapv bracketed-require requires)
        exists?  (contains? (:namespaces (:store @session)) ns-sym)]
    (if (and exists? (seq requires) (nil? source))
      ;; the requires were the whole ask: add them, overwrite nothing
      (let [rs (mapv #(add-require! session ns-sym % :prompt prompt :agent agent) requires)]
        (if-let [e (some :error rs)]
          {:error e}
          (cond-> {:ok true :ns ns-sym :already-exists true :requires-added requires}
            (seq (mapcat :also-created rs)) (assoc :also-created (vec (distinct (mapcat :also-created rs)))))))
      (let [;; both given: the requires belong in the source's ns form, so put
            ;; them there rather than refuse
            source   (if (and source (seq requires))
                       (reduce (fn [src r]
                                 (let [x (edit/add-require-source src r)]
                                   (if (:error x) src (:src x))))
                               source requires)
                       source)
            requires (if source [] requires)
            ;; platform must be declared FIRST: ingest reads it to decide whether to
            ;; hot-load, so a :cljs source with js/* would fail to load otherwise
            perr (when platform
                   (:error (module-platform! session (str ns-sym) platform
                                             :prompt (or prompt "platform declared at namespace creation")
                                             :agent agent)))
            ;; computed BEFORE the write, while the store still lacks the name
            shadow (shadow-warning (:store @session) ns-sym)
            also   (when-not perr
                     (unwritten-requires (:store @session) ns-sym requires))]
        (cond
          perr {:error perr}

          :else
          (let [;; the subjects come into being BEFORE the spec that requires
                ;; them, or the spec's own load is the failure again
                sub-err (some (fn [n]
                                (:error (ingest! session n (str "(ns " n ")\n")
                                                 :agent agent :prompt prompt)))
                              also)
                r (if sub-err
                    {:error sub-err}
                    (if source
                      ;; a whole namespace is the write most likely to cross a
                      ;; boundary for the first time; declare its edges as a
                      ;; single form's write would
                      (let [source (with-purpose source ns-sym (or doc prompt))
                            once   #(ingest! session ns-sym source :agent agent :prompt prompt)]
                        (auto-module-dep-retry! session (once) once :agent agent))
                      (ingest! session ns-sym
                               (str "(ns " ns-sym
                                    ;; the ask IS the purpose — the docstring the
                                    ;; namespace-purpose advisory would otherwise
                                    ;; ask for at the done (eval24 opus: a change
                                    ;; and a second done per created namespace)
                                    (when-let [d (purpose-doc (or doc prompt))]
                                      (str "\n  " (pr-str d)))
                                    (when (seq requires)
                                      (str "\n  (:require " (str/join "\n            " requires) ")"))
                                    ")\n")
                               :agent agent :prompt prompt)))]
            (cond-> r
              (seq also) (assoc :also-created (vec also))
              (and shadow (not (:error r)))
              (update :warnings (fnil conj []) shadow))))))))

(defn revert-form!
  "One-call rollback (item 4): replace `nm` with an earlier version of itself —
  by default the previous one, or the version at delta `:to` (see
  query-form-history). Rides the standard replace pipeline, so the revert is
  itself compile-gated, verified, and recorded provenance."
  [session ns-sym nm & {:keys [to prompt agent]}]
  (let [hist (history/query-form-history (with-history session) ns-sym nm)]
    (cond
      (nil? hist)
      (edit/missing-form-error (:store @session) ns-sym nm)

      (< (count hist) 2)
      {:error (str nm " has no earlier version to revert to")}

      :else
      (let [target (if to
                     (first (filter #(= to (:delta %)) hist))
                     (nth hist (- (count hist) 2)))]
        (if-not target
          {:error (str "no version of " nm " at delta " to)}
          (edit-replace! session ns-sym nm (:source target)
                         :prompt (or prompt
                                     (str "revert to " (:delta target)))
                         :agent agent))))))

(defn edit-group!
  "One INTENT as one atomic write — `edit-group-once!` with the write path's
  two self-repairs, ALTERNATING until the group lands or the error stops
  changing. Auto-require: when the group fails to compile because a step
  named an alias its namespace lacks and exactly one namespace can supply
  it, the require is added (a `:system` write, as for a single form) and
  the group runs once more — for every touched namespace the alias is
  missing from, since one feature reaching three namespaces needs the same
  require three times. Auto-module-dep: a step refused for its first call
  across a module boundary gets the edge declared and the group rerun.
  The order is not fixed: a feature crossing into a NEW module meets the
  module gate first, and the compile names the missing alias only after
  the edge exists (s17: nineteen \"No such namespace\" refusals in the XL
  cells, each a whole group re-sent, because the require repair had already
  run and nothing ran it again). Bounded; every require that landed is
  stamped (`:auto-require` the first, `:auto-requires` all), a require that
  could not land rides as `:auto-require-refused`, and edges as
  `:auto-module-dep(s)`; a cycle stays a refusal.

  On the wire as `edit_group` since 2026-08-30 — the reversal of a position
  this function used to argue in its docstring. The grain an agent thinks in
  is the intent: the fn, its test, the caller it changes, the require it
  needs. Measured one form per call on that grain (eval10), it cost a model
  request per form with reads between them, because no single result was
  trusted to stand for the group. A group is the intent verified once and
  reported per step; the completeness judgement is still `done`'s, and a
  whole feature in one call meets the same gates a whole feature in one form
  does."
  [session steps & {:keys [prompt agent no-auto-require]}]
  (let [;; qualified in, alias on store: the rewrites and their requires are
        ;; part of the group, and the result says what moved
        {steps :steps, canon :canonicalized, owed :requires} (canonicalize-steps session steps)
        once   (fn [] (cond-> (edit-group-once! session steps :prompt prompt :agent agent)
                        (seq canon) (assoc :canonicalized canon)
                        (seq owed)  (assoc :auto-require (first owed) :auto-requires (vec owed))))
        r      (once)
        nses   (vec (distinct (map :ns steps)))
        stamps [:auto-require :auto-requires :auto-require-refused
                :auto-module-dep :auto-module-deps]
        carry  (fn [from to] (merge (select-keys from stamps) to))
        ;; the require repair, over every touched namespace the alias is
        ;; missing from; `tried` keeps a namespace from being asked twice for
        ;; one alias across the outer alternation
        requires!
        (fn [r tried]
          (loop [r r, tried tried, n 0]
            (let [added  (vec (:auto-requires r))
                  spec   (when (:error r)
                           (edit/missing-require (:store @session) (:error r)))
                  ;; the namespace the error ANCHORS to first — the require is
                  ;; missing THERE. Asking the first namespace in the group
                  ;; added a require where nothing used it (2026-09-03: the
                  ;; db layer gained a require of the read layer)
                  anchored (when spec
                             ;; `compile-error` strips the coordinate from the
                             ;; message and puts the anchor on the result as
                             ;; :form — read THAT first; the text is a fallback
                             (or (some-> (:form r) namespace symbol)
                                 (some-> (edit/anchor-error (:store @session) (:error r))
                                         :form namespace symbol)))
                  ns-sym (when spec
                           (or (when (and anchored (some #{anchored} nses)
                                          (not (tried [anchored spec])))
                                 anchored)
                               (first (remove #(tried [% spec]) nses))))]
              (if (or (nil? spec) (nil? ns-sym) (< (* 4 (count nses)) n))
                [r tried]
                (let [r2    (auto-require-retry session ns-sym r once :agent agent)
                      tried (conj tried [ns-sym spec])
                      r2    (if-let [a (:auto-require r2)]
                              (let [added (conj added a)]
                                (assoc r2 :auto-require (first added) :auto-requires added))
                              (carry r r2))]
                  (if (nil? (:error r2))
                    [r2 tried]
                    (recur r2 tried (inc n))))))))]
    (if (or no-auto-require (nil? (:error r)))
      r
      (loop [r r, tried #{}, n 0]
        (let [[ra tried] (requires! r tried)
              rb         (if (:error ra)
                           (carry ra (auto-module-dep-retry! session ra once :agent agent))
                           ra)]
          (if (or (nil? (:error rb))
                  ;; no progress: no require landed this pass and the module
                  ;; retry left the error as it was. The error TEXT recurring is
                  ;; not the test — the next namespace fails with the identical
                  ;; \"No such namespace\" sentence once the previous one is repaired
                  (and (= (count (:auto-requires ra)) (count (:auto-requires r)))
                       (= (:error rb) (:error ra)))
                  (< 8 n))
            rb
            (recur rb tried (inc n))))))))

(defn- canonical-source!
  "The single-form doors' half of qualified-in, alias-on-store: rewrite
  `source` (one form of `ns-sym`) to the aliases the store speaks
  (`refactor/canonicalize-refs`) and add the requires it owes as `:system`
  writes BEFORE the form lands, so its hot-load sees the alias. Best effort:
  a require the gates refuse (an undeclared module edge, a cycle) leaves
  the source as written, and the write's own refusal says why. Returns the
  source to write — unchanged when nothing moved, the form does not parse
  on its own (a multi-form blob takes the group door), or the namespace
  does not exist yet."
  [session ns-sym source]
  (let [st   (:store @session)
        node (when (get-in st [:namespaces ns-sym]) (:node (edit/parse-form source)))]
    (if-not node
      source
      (let [c (refactor/canonicalize-refs st ns-sym node (read.modules/project-aliases st))]
        (if (and (empty? (:rewrites c)) (empty? (:requires c)))
          source
          (let [landed? (every? (fn [spec]
                                  (nil? (:error (add-require! session ns-sym spec
                                                              :prompt fields/auto-require-prompt
                                                              :system true))))
                                (:requires c))]
            (if (and landed? (seq (:rewrites c)))
              (n/string (:node c))
              source)))))))
