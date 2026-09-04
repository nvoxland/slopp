(ns slopp.store.db
  "Durable system of record (C7): SQLite at `<dir>/.slopp/store.db`. Because of
  C1 there are no `.clj` files on disk — this database IS the source code — so
  it gets a real storage engine rather than hand-rolled EDN files.

  Layout:
  - `deltas`   — the append-only log (the history). Op-specific fields live in
                 an EDN `payload` column; EDN stays the value representation,
                 SQLite supplies the durability mechanics.
  - `elements` — the materialized current form-state, kept transactionally
                 in-step with the log (open = read rows, no log replay).
  - `meta`     — the id counter, so a reopened store keeps minting unique ids.

  Every mutation lands in ONE transaction: delta row + its namespace's element
  rows + next-id, atomically. WAL mode for crash safety."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n] [slopp.store.fields :as fields] [slopp.store :as store] [clojure.string :as str]))

^:reads (defn ^:export data-version
  "SQLite's cheap foreign-commit detector: this value changes when ANOTHER
  connection (thread or process) has committed to the database since we last
  looked — our own writes through this connection don't bump it."
  [conn]
  (:data_version (jdbc/execute-one! conn ["PRAGMA data_version"])))

(defn- parse-node
  "Re-parse one element's canonical serialization (its source text) back to its
  CST node. Lossless by rewrite-clj's parse/print round-trip."
  [source]
  (let [nodes (n/children (p/parse-string-all source))]
    (assert (= 1 (count nodes))
            (str "element source did not reparse to one node: " (pr-str source)))
    (first nodes)))

(defn- row->element [row]
  (let [kind (keyword (:elements/kind row))
        node (parse-node (:elements/source row))]
    (if (= :form kind)
      (cond-> {:id   (:elements/form_id row) :kind :form
               :name (some-> (:elements/name row) symbol) :node node
               ;; the creation rank the derived order breaks ties by; a row
               ;; older than the column has had it filled from pos at open!
               :rank (or (:elements/rank row) (:elements/pos row))}
        (:elements/comment row) (assoc :comment (:elements/comment row)))
      {:kind :sep :node node})))

(defn- row->delta [row]
  (merge {:id (:deltas/id row)
          :op (keyword (:deltas/op row))
          :ns (symbol (:deltas/ns row))}
         (edn/read-string (:deltas/payload row))
         ;; the COLUMN wins over the payload's copy, and the order of this
         ;; merge is the whole point. 240 :ingest deltas were written with
         ;; :parent nil in their payload — a root each, in a log with one root.
         ;; The column is the repaired one, so reading it last means the
         ;; in-memory delta says what a traversal would find. Payload-first
         ;; would leave the value and the walk disagreeing on exactly the rows
         ;; that were wrong, which is worse than either being wrong alone.
         (when-let [p (:deltas/parent row)] {:parent p})))

^:reads (defn ^:export deps
  "The store's external-dependency manifest, read straight from meta — for
  the git/native/launch paths that need it without opening a session."
  [conn]
  (or (some-> (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = 'deps'"])
              :meta/v edn/read-string)
      {}))

^:reads (defn ^:export get-dep-surface
  "The cached analysis surface for a dependency `id` (\"lib@version\"), or nil."
  [conn id]
  (some-> (jdbc/execute-one! conn ["SELECT surface FROM dep_surface WHERE id = ?" id])
          :dep_surface/surface edn/read-string))

(defn ^:export put-dep-surface!
  "Cache `surface` (an EDN-able map) for dependency `id`. Content-addressed by
  coord@version — computed once, reused forever."
  [conn id surface]
  (jdbc/execute! conn ["INSERT INTO dep_surface (id, surface) VALUES (?,?)
                        ON CONFLICT(id) DO UPDATE SET surface = excluded.surface"
                       id (pr-str surface)]))

^:reads (defn ^:export get-dep-native
  "The cached native-image verdict for a dependency `id`, or nil (P4-deps M6)."
  [conn id]
  (some-> (jdbc/execute-one! conn ["SELECT native FROM dep_surface WHERE id = ?" id])
          :dep_surface/native edn/read-string))

(defn ^:export put-dep-native!
  "Cache the native-compat `verdict` (EDN map) for dependency `id`."
  [conn id verdict]
  (jdbc/execute! conn ["INSERT INTO dep_surface (id, native) VALUES (?,?)
                        ON CONFLICT(id) DO UPDATE SET native = excluded.native"
                       id (pr-str verdict)]))

^:reads (defn ^:export commit-shas
  "P4-m8: {delta-id git-sha} from the projection's pinning table (created and
  written by slopp.git; this is read-only convenience for query surfaces).
  Nil when nothing has been projected. Only UNAMBIGUOUS rows: a delta id
  that collides across lines (post-fork id reuse) is omitted, never guessed."
  [conn]
  (when (seq (jdbc/execute! conn ["SELECT name FROM sqlite_master
                                   WHERE type='table' AND name='git_map'"]))
    (into {}
          (keep (fn [row]
                  ;; aggregates come back unqualified; plain columns may not
                  (when (= 1 (or (:n row) (:git_map/n row)))
                    [(or (:delta_id row) (:git_map/delta_id row))
                     (or (:sha row) (:git_map/sha row))])))
          (jdbc/execute! conn ["SELECT delta_id, MIN(sha) AS sha, COUNT(*) AS n
                                FROM git_map GROUP BY delta_id"]))))

^:reads (defn ^:export get-meta
  "Read a meta row's value (nil when absent) — the k/v side-table for
  config the journal doesn't track (e.g. `git-remote`, `git-base-sha`)."
  [conn k]
  (:meta/v (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = ?" k])))

(defn ^:export set-meta!
  "Upsert a meta row — the write side of `get-meta`."
  [conn k v]
  (jdbc/execute! conn ["INSERT INTO meta (k,v) VALUES (?,?)
                        ON CONFLICT(k) DO UPDATE SET v = excluded.v" k (str v)])
  nil)

(defn ^:export quarantine-put!
  "Record a git-pull conflict for `path` (upsert): the raw remote `source`
  (nil for deletions), the remote `sha` it came from, and the human `reason`.
  Off-log by design — never touches the journal."
  [conn {:keys [path ns source sha reason]}]
  (jdbc/execute! conn ["INSERT INTO quarantine (path, ns, source, sha, reason, at)
                        VALUES (?,?,?,?,?,?)
                        ON CONFLICT(path) DO UPDATE SET
                          ns = excluded.ns, source = excluded.source,
                          sha = excluded.sha, reason = excluded.reason,
                          at = excluded.at"
                       path (some-> ns str) source sha reason
                       (System/currentTimeMillis)])
  nil)

^:reads (defn ^:export quarantine-list
  "Every unresolved git-pull conflict, oldest first:
  [{:path :ns :source :sha :reason :at}]."
  [conn]
  (mapv (fn [row]
          {:path   (:quarantine/path row)
           :ns     (some-> (:quarantine/ns row) symbol)
           :source (:quarantine/source row)
           :sha    (:quarantine/sha row)
           :reason (:quarantine/reason row)
           :at     (:quarantine/at row)})
        (jdbc/execute! conn ["SELECT * FROM quarantine ORDER BY at, path"])))

(defn ^:export quarantine-clear!
  "Resolve one conflict (`path`) — or ALL of them when path is nil."
  [conn path]
  (if path
    (jdbc/execute! conn ["DELETE FROM quarantine WHERE path = ?" path])
    (jdbc/execute! conn ["DELETE FROM quarantine"]))
  nil)

^:reads (defn ^:export meta-with-prefix
  "Every meta row whose key starts with `prefix`, as `{k v}`. The k/v
  side-table has no other way to be enumerated, and observations are stored
  one row per form (`observed/<ns>/<name>`) — they load in one scan at
  session open, like the trace map, so the card view can read them from
  session state instead of the db."
  [conn prefix]
  (into {}
        (map (fn [r] [(:meta/k r) (:meta/v r)]))
        (jdbc/execute! conn ["SELECT k, v FROM meta WHERE k LIKE ?"
                             (str prefix "%")])))

(defn put-blobs!
  "Write `blobs` ({sha → bytes}) INSERT OR IGNORE — content-addressed, so
  rewriting an existing sha is a no-op. Callable inside a transaction."
  [tx blobs]
  (doseq [[sha ^bytes bs] blobs]
    (jdbc/execute! tx ["INSERT OR IGNORE INTO blobs (sha, bytes) VALUES (?,?)"
                       (str sha) bs])))

^:reads (defn ^:export get-blob
  "The bytes stored under `sha`, or nil — the sessionless read (git
  projection, build) and the session cache's fallback."
  [conn sha]
  (some-> (jdbc/execute-one! conn ["SELECT bytes FROM blobs WHERE sha = ?" (str sha)])
          :blobs/bytes))

(defn writer-collision?
  "Is this SQLException SQLite's WRITER COLLISION (busy / locked) — the only
  kind a refresh-and-rebase can fix? Everything else (a missing column, a
  constraint violation) is a real fault and must surface.

  This distinction is load-bearing: `append!` used to treat every SQLException
  as a lost race, so a malformed statement came back as `false`, the caller
  retried it twelve times, and the agent was told \"commit contention: too many
  concurrent writes\" while the store was actually unwritable. An error may only
  name a cause it checked."
  [^java.sql.SQLException e]
  (let [m (.toLowerCase (str (.getMessage e)))]
    (or (.contains m "busy") (.contains m "locked"))))

(def ^:private store-dir-gitignore
  "What lands in `.slopp/.gitignore`. The `*` is the whole mechanism: it
   ignores every file in the directory INCLUDING itself, so `.slopp/` holds no
   tracked file, and git — which tracks files, not directories — stops seeing
   the directory at all."
  (str "# slopp's store lives here. It is the source, but it is not git\n"
       "# CONTENT: the store reaches git as the projected `slopp` branch at\n"
       "# each commit_point, never as files on main.\n"
       "#\n"
       "# Self-ignoring on purpose, so a project that adopts slopp never has\n"
       "# to mention it in its own .gitignore.\n"
       "*\n"))

(defn- ensure-gitignore!
  "Write `.slopp/.gitignore` if it isn't there, so the store dir excludes
   itself from git.

   Lives here rather than at the callers because this is where the directory
   is MADE — `import!`, the MCP launch, the CLI and every test reach the
   filesystem through `open!`, and a rule maintained at four call sites is a
   rule that is right at three of them.

   Never overwrites: a file we did not write may have been customised, and
   adoption is not licence to edit the working tree. Failure is swallowed —
   a read-only or exotic filesystem is not a reason to refuse a store."
  [dir]
  (try
    (let [f (io/file dir ".slopp" ".gitignore")]
      (when-not (.exists f)
        (spit f store-dir-gitignore)))
    (catch java.io.IOException _ nil)))

(defn- row->line
  "A `lines` row as a value. next.jdbc qualifies plain columns by their table,
  so the keys are normalized once here instead of at every reader.

  The whitelist is deliberate — a `SELECT *` reader should not start carrying
  whatever the schema gains — so a new column has to be added HERE to be
  visible anywhere. `:owner-pid`/`:owner-started` are the write LEASE, nil on
  a row written before it existed."
  [row]
  (let [r (into {} (map (fn [[k v]] [(keyword (name k)) v])) row)]
    {:id            (:id r)
     :name          (:name r)
     :kind          (:kind r)
     :head          (:head r)
     :base          (:base r)
     :parent        (:parent r)
     :agent         (:agent r)
     :created-at    (:created_at r)
     :used-at       (:used_at r)
     :status        (:status r)
     :owner-pid     (:owner_pid r)
     :owner-started (:owner_started r)}))

^:reads (defn ^:export lines
  "Every line in this store, most-recently-used first.

  A LINE is a pointer to a head delta. A named line is a BRANCH; an anonymous
  one (name NULL) is an agent's THREAD. One row shape, because they are one
  thing — a thread is a branch nobody named, and giving them separate tables
  would mean every question about history had to be asked twice."
  [conn]
  (mapv row->line
        (jdbc/execute! conn ["SELECT * FROM lines ORDER BY used_at DESC"])))

(defn- advance-trunk!
  "Keep the trunk line's head in step with the journal head.

  Deliberately a FOLLOWER for now: the write CAS still runs on the global
  journal head (`append!`), and moving it onto this row is the next step. Both
  at once would hide a CAS defect behind a behaviour change, and a CAS defect
  here is not a wrong answer — it is every agent's write failing whenever any
  other agent writes anywhere in the file."
  [tx head]
  (let [now (System/currentTimeMillis)]
    (jdbc/execute! tx ["INSERT INTO lines
                          (id,name,kind,head,base,parent,agent,created_at,used_at,status)
                        VALUES (?,'main','branch',?,NULL,NULL,NULL,?,?,'open')
                        ON CONFLICT(name) DO UPDATE SET head    = excluded.head,
                                                        used_at = excluded.used_at"
                       (str (java.util.UUID/randomUUID)) head now now])))

(defn- one-col
  "The single selected column of a single-row query, whatever next.jdbc
  qualified it with. A CTE's columns are not qualified the way a table's are,
  and guessing wrong produces the same value as no rows at all — which is one
  debugging round already spent."
  [row]
  (when row (val (first row))))

(def ^:private elements-ddl
  "The `elements` schema, in ONE place because the migration in `open!` builds
  the same table a second time.

  `elements` is the journal MATERIALIZED — it is why opening a store costs
  ~410ms instead of folding 23,560 deltas. Keyed (ns, pos) it could hold
  exactly ONE view per file, which is the last reason a branch had to BE a
  separate db file. Keyed (line, ns, pos) it holds every open line's view at
  once, and a split costs one INSERT … SELECT over the form rows instead of a
  fold of the whole log."
  "CREATE TABLE IF NOT EXISTS elements (
     line    TEXT NOT NULL,
     ns      TEXT NOT NULL,
     pos     INTEGER NOT NULL,
     kind    TEXT NOT NULL,
     form_id TEXT,
     name    TEXT,
     source  TEXT NOT NULL,
     comment TEXT,
     PRIMARY KEY (line, ns, pos))")

(def ^:private ancestry-cte
  "The recursive walk from a delta back to the root, as a CTE named `anc`,
  taking the head as its ONE parameter.

  Shared because three readers ask the same question of a line — the id list,
  its journal, and its incremental suffix — and a hand-kept second copy of a
  recursive query is how two readers come to disagree about what a line's
  history is. That disagreement would not read as a bug: each answer is
  internally consistent.

  A nil head matches nothing, which is the right answer for a line that exists
  and has never been written to."
  "WITH RECURSIVE anc(id, parent) AS (
     SELECT id, parent FROM deltas WHERE id = ?
     UNION ALL
     SELECT deltas.id, deltas.parent FROM deltas
       JOIN anc ON deltas.id = anc.parent)")

^:reads (defn ^:export ancestry
  "The delta ids reaching `head`, OLDEST first — one line's whole history.

  Walks the `parent` column recursively, so it costs the LINE's length rather
  than the journal's. That is the whole reason parent became a column: the
  same question used to be answerable only by loading every delta and folding
  it, which is why a second line had to be a separate db file. Measured on
  slopp's own store: 23,719 deltas walked in 69 ms.

  [] for a nil head — a line that exists but has never been written to."
  [conn head]
  (if-not head
    []
    (vec
     (reverse
      (map (fn [row]
             ;; the query selects exactly ONE column, so the row has exactly
             ;; one entry — read it positionally. next.jdbc's qualification of
             ;; a CTE's columns is not the same as a table's, and guessing it
             ;; wrong failed the way an empty result does. (`rseq` was the
             ;; other half of that: it returns NIL on an empty vector, so a
             ;; wrong key and no rows produced an identical silent [].)
             (val (first row)))
           (jdbc/execute! conn [(str ancestry-cte " SELECT id FROM anc") head]))))))

^:reads (defn ^:export line-head
  "The delta `line-id` currently points at, or nil if it points at nothing yet.

  A line IS a pointer to a head, so this is the whole of what distinguishes
  one line's history from another's: every journal read below turns a line id
  into a head and walks back from there."
  [conn line-id]
  (one-col (jdbc/execute-one! conn ["SELECT head FROM lines WHERE id = ?" line-id])))

^:reads (defn ^:export
  ^{:breaking-ok
    (str "the 2-arity is REMOVED rather than defaulted: a suffix that does not "
         "name its line hands a caller another line's work to replay into its "
         "own cache. All four call sites moved in the SAME coordinated write, "
         "and nothing outside slopp calls the storage layer directly.")}
  deltas-after
  "ONE LINE's journal suffix past its first `n` deltas (incremental sync).

  `n` counts along the LINE and not along the file. Seq order is a global
  interleaving once many lines share one journal, so \"the first n deltas\" of
  it is nobody's history — and the caller passing its own delta count would be
  handed another line's work, which `refresh-cache!` replays directly into the
  cached store."
  [conn line-id n]
  (mapv row->delta
        (jdbc/execute! conn
                       [(str ancestry-cte
                             " SELECT * FROM deltas WHERE id IN (SELECT id FROM anc)
                                ORDER BY seq LIMIT -1 OFFSET ?")
                        (line-head conn line-id) (long n)])))

(defn duplicate-delta-id?
  "Is this SQLException the UNIQUE violation on `deltas.id` — another writer
  having already taken an id this one minted?

  A lost race by every property that matters, and the same cure: refresh, pick
  up the file's counter, rebase. Ids come from a store VALUE while the UNIQUE
  index spans the whole journal, so two lines counting from the same place mint
  the same id. That could not happen while a branch was a separate file, and
  per-line CAS removed the serialization that covered the equivalent case for
  two servers on ONE line — so this is the residue of the feature, not a defect
  behind it.

  Deliberately as narrow as `writer-collision?`, and for the same reason: it
  names ONE constraint on ONE column. Widening it to constraint violations
  generally would hand back `false` for a real defect, and the caller would
  retry it twelve times and report contention."
  [^java.sql.SQLException e]
  (let [m (.toLowerCase (str (.getMessage e)))]
    (and (.contains m "unique constraint failed")
         (.contains m "deltas.id"))))

^:reads (defn ^:export open-threads
  "Every open thread on `branch-line-id`, most-recently-used first.

  Across ALL agents, deliberately: the question this answers is \"who is
  working here, and what has been sitting untouched\", and an agent-scoped
  version could only ever say yes about itself. `used_at` is the age, so an
  idle thread is a row near the end of this list rather than a separate
  concept.

  `kind` does the real filtering, not `parent`. A named branch forked from
  this one carries the same `parent`, so a listing keyed on the fork alone
  would report a branch as somebody's private workspace."
  [conn branch-line-id]
  (mapv row->line
        (jdbc/execute! conn ["SELECT * FROM lines
                               WHERE kind = 'thread' AND parent = ? AND status = 'open'
                             ORDER BY used_at DESC" branch-line-id])))

^:reads (defn ^:export line-id-by-name
  "The id of the line named `nm`, or nil if nothing answers to that name.

  Only a BRANCH can be returned: a thread is the anonymous case, so it has no
  name to be found by. That is the property that makes this safe to use as
  \"which line does this session's work land on\"."
  [conn nm]
  (one-col (jdbc/execute-one! conn ["SELECT id FROM lines WHERE name = ?" nm])))

^:reads (defn ^:export
  
  unlanded-count
  "How many of `ops` `line-id` has written since it forked — its head walked
  back to its own base, exclusive.

  **Counts WORK, not deltas**, and the caller says which ops are work. Reported
  by slopp-ui from this field's first hour: a `full_check` with no source
  written left the count reading 2, because a verification records deltas. True
  of the journal and wrong for every reader — a number that is non-zero when
  nothing is pending is the badge nobody reads, and then the once it means
  something nobody looks.

  What settles it is sharper than noise, though. `api.model/timeline` already
  filters its `:working` set by the same `content-ops`, and the two numbers are
  meant to be read TOGETHER — written-not-landed beside landed-not-committed.
  One filtered and one not makes the pair incoherent.

  The op set is a PARAMETER because it is policy: which ops constitute a change
  is `read.history`'s answer, and a copy of it down here would be a second list
  that has to agree with the first. An empty set is answered without a query —
  `IN ()` is not valid SQL, and 'no ops count' has an obvious answer anyway.

  The walk STOPS at the base rather than subtracting two ancestries, so it is
  proportional to the line's own work instead of to the journal. `IS NOT`
  rather than `<>` because a line with no base is a real case — the first line
  of a store forks from nothing — and `<>` against NULL is NULL, which would
  end the recursion immediately and report every such line as having written
  zero."
  [conn line-id ops]
  (if (empty? ops)
    0
    (let [base  (one-col (jdbc/execute-one!
                          conn ["SELECT base FROM lines WHERE id = ?" line-id]))
          names (mapv name ops)
          holes (apply str (interpose "," (repeat (count names) "?")))]
      (or (one-col
           (jdbc/execute-one!
            conn (into [(str "WITH RECURSIVE anc(id, parent, op) AS (
                                SELECT id, parent, op FROM deltas WHERE id = ?
                                UNION ALL
                                SELECT deltas.id, deltas.parent, deltas.op FROM deltas
                                  JOIN anc ON deltas.id = anc.parent
                                 WHERE anc.id IS NOT ?)
                              SELECT COUNT(*) FROM anc
                               WHERE id IS NOT ? AND op IN (" holes ")")
                        (line-head conn line-id) base base]
                       names)))
          0))))

^:reads
(defn ^:export this-process
  "This OS process, as `{:pid :started}` — the identity a thread lease is
  held under.

  Derived rather than minted. A lease has to answer \"is the holder still
  running\", and only a real process handle can; a UUID in a table can be
  asked whether it is present, never whether it is alive. `:started` is
  carried because pids are reused, so the pair identifies a process where
  the number alone identifies a slot. It is nil on a JVM that will not
  report a start time, and a nil there makes the lease unverifiable rather
  than false — see `process-live?`."
  []
  (let [h  (java.lang.ProcessHandle/current)
        si (.orElse (.startInstant (.info h)) nil)]
    {:pid     (.pid h)
     :started (some-> ^java.time.Instant si .toEpochMilli)}))

^:reads
(defn ^:export process-live?
  "Is the process recorded as `pid`/`started` still running?

  This is what lets a lease be a FACT rather than a timeout. `thread_list`
  promises that nothing reaps a thread on a timer, and a lease that expired
  after N idle minutes would quietly break that promise for the only thing
  anybody would notice — the right to write. Asking the operating system
  costs one syscall and cannot be wrong about a process that is gone.

  A recorded `started` that does not match the live process of that pid means
  the pid was REUSED and the original is gone, which is the case a bare pid
  check gets backwards.

  **An unrecorded `started` (nil) counts as LIVE when the pid is present.**
  Absence of evidence is not evidence the holder died, and the two mistakes
  are not symmetric: treating a live holder as dead hands two processes one
  thread, which is the whole defect. Treating a dead one as live costs a
  fresh thread nobody needed."
  [pid started]
  (boolean
   (when pid
     (when-let [h (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
       (and (.isAlive ^java.lang.ProcessHandle h)
            (or (nil? started)
                (let [si (.orElse (.startInstant (.info ^java.lang.ProcessHandle h)) nil)]
                  (or (nil? si)
                      (= (long started)
                         (.toEpochMilli ^java.time.Instant si))))))))))

(defn ^:export record-measurement!
  "Record one MEASUREMENT — a number about what something cost — beside the
  journal. Returns nil.

  Deliberately not a delta. Appending to `deltas` moves the head that every
  writer compare-and-swaps against, and a statistic must never be the reason a
  verdict loses that race. This is a plain INSERT into its own table: no
  parent, no chain, no CAS, and nothing for `merge-logs` to be taught to skip.

  `kind` names the reader ('otel', 'read-cost', …) so one fold cannot see
  another's rows. `delta` is an optional FK to the entry this is ABOUT — a
  turn's timing names its `:turn-end`, an external run names its `:observe` —
  and is nil for a measurement about a span of wall-clock time rather than an
  entry. Inventing an anchor to make the column look full would make the FK
  mean two different things.

  `payload` is stored as EDN, the same way a delta's is, so the two are read
  back by the same reader and a measurement that outgrows a flat map does not
  need a migration."
  [conn kind delta payload]
  (jdbc/execute! conn
                 ["INSERT INTO measurements (at, kind, delta, payload)
                   VALUES (?, ?, ?, ?)"
                  (System/currentTimeMillis) (str kind)
                  (some-> delta str) (pr-str payload)])
  nil)

(defn ^:export measurements
  "The measurements of `kind`, oldest first, as
  `[{:seq :at :kind :delta :payload}]`. `since` (a `:seq`) windows to rows
  AFTER it; nil reads them all.

  The window is by `seq` rather than by time because that is what makes a
  reader idempotent: wall-clock ties are possible and a fold that re-read a
  row would double-count what it cost. `seq` is the table's own primary key,
  monotonic, and never reused.

  Kept OUT of the store value on purpose. `load-store` already spends most of
  its four seconds EDN-parsing delta payloads, and folding every measurement
  ever taken into every session's store would put the cost of the telemetry on
  the price of opening the project. A reader that wants numbers asks for them."
  [conn kind since]
  (mapv (fn [r] {:seq     (:measurements/seq r)
                 :at      (:measurements/at r)
                 :kind    (:measurements/kind r)
                 :delta   (:measurements/delta r)
                 :payload (edn/read-string (:measurements/payload r))})
        (jdbc/execute! conn
                       (if since
                         ["SELECT * FROM measurements WHERE kind = ? AND seq > ?
                           ORDER BY seq" (str kind) since]
                         ["SELECT * FROM measurements WHERE kind = ?
                           ORDER BY seq" (str kind)]))))

(defn ^:export line-status
  "`line-id`'s status — `\"open\"`, `\"landed\"`, `\"abandoned\"` — or **nil** when
  the registry has no such line.

  A cached line id has to keep asking this, because a DIFFERENT PROCESS can
  settle the line underneath the one holding the id. The plugin's Stop hook is
  the routine case: on every session pause it runs `done` through a one-shot
  process carrying the session's own agent id, which adopts this agent's line,
  lands it, and settles it — while the long-lived server goes on holding the
  id it cached at first use.

  **nil and \"landed\" are different answers and must not be collapsed.** A
  settled line is one the system finished with on purpose, and moving off it
  is self-healing. A line the registry does not have AT ALL is a broken
  invariant, and healing that quietly is how work goes missing with nobody
  told — `slopp.ops.branch/land-thread!` reports that case deliberately, and a
  boolean here would take away its ability to. Learned by writing the boolean
  first: it turned a reported failure into a silent one, and
  `branch-test/a-session-that-cannot-FIND-its-thread-says-so-instead-of-landing-quietly`
  said so immediately.

  A primary-key lookup, so asking on every resolution costs a fraction of what
  the write it precedes costs."
  [conn line-id]
  (one-col (jdbc/execute-one!
            conn ["SELECT status FROM lines WHERE id = ?" line-id])))

(defn ^:export thin-commit-manifests
  "`ds` with `:files` dropped from every `:commit` delta but the NEWEST.

  **The largest single thing a session used to hold.** `commit_point!`
  snapshots the whole tracked-files manifest into every commit-point marker
  (`(seq (:files st)) (assoc :files (:files st))`), and this loader parsed all
  of them into every session's store value. Measured on slopp's own store: 554
  commit-points carrying 73.7 MB of payload, of which **`:files` alone was 70.8 MB
  — 96%** — one marker reaching 2.1 MB beside ~3.8 KB of everything else. As
  parsed Clojure structure that was the dominant object in a live server's
  heap, and it grew with every commit-point forever.

  Safe by construction rather than by luck, which is the same argument
  `:blobs` makes two doors down:

  - `slopp.git/insert-commit!` — the projection, and the reader that needs
    EVERY marker's manifest — takes its deltas straight from the db on its own
    connection. `ensure-projected!` says so in its docstring: *reads the dbs
    directly (always-current, no session needed)*. It never sees this value.
  - `slopp.git/commit-point-tree` is the one store-VALUE reader, and it resolves
    `(last (filter #(= :commit (:op %)) ds))` — the newest, which is kept.

  Only `:files` goes. A commit-point's `:description`, `:status`, `:target` and
  `:agent` are small and ARE read from the store value (`query_commits`, the
  reviewer timeline), so thinning the whole payload would break them for a few
  more kilobytes.

  **Both constructors of a store value must call this, and that is why it is
  exported.** Thinning at load alone fixes a session at OPEN and does nothing
  for its life: `slopp.ops.engine/refresh-cache!` advances INCREMENTALLY in the
  common case — `store/replay-delta` over the journal suffix, deliberately
  avoiding a full re-parse — and a foreign `:commit` delta arrives from
  `deltas-after` carrying its whole manifest. Every commit-point landed during a
  server's life would add one back. Measured: a fresh server holds ~515 MB
  post-GC, a worked-in one 2.37 GB."
  [ds]
  (let [newest (->> ds
                    (keep-indexed (fn [i d] (when (= :commit (:op d)) i)))
                    last)]
    (if (nil? newest)
      ds
      (into []
            (map-indexed (fn [i d]
                           (if (and (= :commit (:op d)) (not= i newest))
                             (dissoc d :files)
                             d)))
            ds))))

(defn ^:export compact!
  "Reclaim what settled lines left behind and hand back the space: delete every
  `elements` row whose line is `landed` or `abandoned`, then `VACUUM`. Returns
  `{:rows-dropped n :bytes-before b :bytes-after b'}`.

  A settled line's view is dead weight — nothing opens a landed thread again,
  and `land-thread!` / `abandon-thread!` now drop it as they settle the line.
  They did not always: 663 landings before the fix left 2,010,559 rows (3.4 GB,
  64% of one file) behind, and a bug's fix does not un-write what it wrote.
  This is the deliberate step for that — run once by an operator who asked for
  it, reported in numbers, rather than a surprise hidden inside the next land.
  `VACUUM` cannot run inside a transaction or beside an open statement, so the
  delete commits first and the vacuum runs on a connection of its own — the
  caller's is the server's shared one, and it is never idle."
  [conn]
  (let [size (fn [] (let [{:keys [page_count page_size]}
                          (merge (jdbc/execute-one! conn ["PRAGMA page_count"])
                                 (jdbc/execute-one! conn ["PRAGMA page_size"]))]
                      (* page_count page_size)))
        before (size)
        dropped (let [settled "WHERE line IN (SELECT id FROM lines WHERE status IN ('landed','abandoned'))"
                      n (-> (jdbc/execute-one! conn [(str "DELETE FROM elements " settled)])
                            :next.jdbc/update-count)]
                  ;; the index rows a settled line left are the same dead weight
                  (jdbc/execute! conn [(str "DELETE FROM form_refs " settled)])
                  (jdbc/execute! conn [(str "DELETE FROM refs_keys " settled)])
                  n)]
    ;; VACUUM refuses while ANY statement is open on its connection, and the
    ;; server's connection is shared — some other thread is always mid-read on
    ;; it. A connection of its own, to the same file, is the only one that is
    ;; guaranteed idle. Its busy timeout is long because a vacuum of a
    ;; multi-GB file waits behind whatever write is in flight.
    (let [file (:file (jdbc/execute-one! conn ["PRAGMA database_list"]))]
      (with-open [own (jdbc/get-connection
                       (jdbc/get-datasource {:dbtype "sqlite" :dbname file}))]
        (jdbc/execute! own ["PRAGMA busy_timeout=600000"])
        (jdbc/execute! own ["VACUUM"])))
    {:rows-dropped dropped :bytes-before before :bytes-after (size)}))

^:reads (defn housekeeping-stats
          "How much of the journal is HOUSEKEEPING — ops that record a fact the
  pipeline recomputes anyway (`:move` places a form where cold-load ordering
  would; `:normalize` rewrites what `done` would) — and who wrote it. A
  pipeline-written one carries `:system true` and costs nothing; an
  agent-written one is a turn spent transcribing a program. Returns
  `{:by-op {op {:agent n :system n}} :agent n :share-of-deltas ratio
  :turns {:n n :with-agent-housekeeping n}}` — the agent share is the number
  that should FALL as more of this moves into the pipeline, and the turn count
  is what it costs while it does not.

  `:system` lives inside the EDN payload, so the split is a `LIKE` over the
  housekeeping rows only — a few thousand, never the whole journal."
          [conn total]
          (let [ops   "('move','normalize')"
                col   (fn [r k] (or (get r (keyword "deltas" (name k))) (get r k)))
                by-op (into (sorted-map)
                            (map (fn [r] (let [n (or (col r :n) 0) s (or (col r :sys) 0)]
                                           [(col r :op) {:agent (- n s) :system s}])))
                            (jdbc/execute!
                             conn [(str "SELECT op, COUNT(*) AS n,
                                          SUM(CASE WHEN payload LIKE '%:system true%'
                                                   THEN 1 ELSE 0 END) AS sys
                                   FROM deltas WHERE op IN " ops " GROUP BY op")]))
                agent (reduce + 0 (map :agent (vals by-op)))
                marks (jdbc/execute!
                       conn ["SELECT seq, op FROM deltas
                              WHERE op IN ('turn-begin','turn-end') ORDER BY seq"])
                hk    (mapv #(col % :seq)
                            (jdbc/execute!
                             conn [(str "SELECT seq FROM deltas WHERE op IN " ops
                                        " AND payload NOT LIKE '%:system true%' ORDER BY seq")]))
                ;; a turn spans its begin to the next end (or the next begin,
                ;; when one was never closed); it counts if an agent-written
                ;; housekeeping delta fell inside
                spans (->> marks
                           (partition-all 2 1)
                           (keep (fn [[a b]]
                                   (when (= "turn-begin" (col a :op))
                                     [(col a :seq) (if b (col b :seq) Long/MAX_VALUE)]))))
                spent (count (filter (fn [[s e]] (some #(< s % e) hk)) spans))]
            {:by-op by-op
             :agent agent
             ;; a percentage to one decimal, not a ratio: 227/12139 is exact and
             ;; unreadable, 1.9 is what an operator compares across weeks
             :agent-pct (if (pos? total) (/ (Math/round (* 1000.0 (/ agent total))) 10.0) 0.0)
             :turns {:n (count spans) :with-agent-housekeeping spent}}))

^:reads (defn ^:export journal-stats
          "What the store CARRIES, in bytes: the journal (per op, heaviest
  first), the materialized state, and the blob table. A pure read straight off
  SQLite's LENGTH — nothing is parsed, so it stays cheap on a large journal.

  This exists because nothing measured cost. A byte-exact `:tree` snapshot in
  every `:commit` reached 94% of a 344MB journal — ~1.35MB per commit-point
  against a design note estimating \"tens of KB\" — and went unnoticed across
  239 commit-points while `full_check` happily counted namespaces and tests. It
  was NAMED here and still grew to 82MB before it was removed. A store can rot
  by GROWING, and only a number catches that.

  The snapshot is gone (the projection derives each tree from the log), so
  there is no longer a `:tree-bytes` figure. The habit it taught is the point:
  when a delta starts carrying something big, count it here first.

  `:elements` counts EVERY line, deliberately. A store's materialization is no
  longer one view: each open line keeps its own, so an abandoned thread costs
  a full copy of the form rows and this is the number that shows it. Per-line
  attribution belongs with the thread listing, not here — what this answers is
  what the FILE carries."
          [conn]
          (let [rows (jdbc/execute!
                      conn ["SELECT op,
                                    COUNT(*)             AS n,
                                    SUM(LENGTH(payload)) AS pbytes
                             FROM deltas GROUP BY op"])
                by-op (->> rows
                           ;; next.jdbc qualifies a real column by its table (:deltas/op) while a
                           ;; computed alias comes back bare — read both rather than betting
                           (map (fn [r] {:op (or (:deltas/op r) (:op r))
                                         :n (or (:n r) 0)
                                         :payload-bytes (or (:pbytes r) 0)}))
                           (sort-by #(- (:payload-bytes %)))
                           vec)
                els  (jdbc/execute-one!
                      conn ["SELECT COUNT(*) AS n, SUM(LENGTH(source)) AS b FROM elements"])
                ;; BY LINE STATUS, because the total cannot say what it is
                ;; made of. It reported 2,112,267 rows / 3.75 GB flat while
                ;; 2,010,559 of them sat on 663 LANDED threads that should
                ;; have held none — a leak this tool was built to catch and
                ;; could not see. `landed` and `abandoned` should read zero;
                ;; anything else there is reclaimable and says so.
                by-st (into (sorted-map)
                            (map (fn [r]
                                   [(or (:lines/status r) (:status r) "unknown")
                                    {:n (or (:n r) 0) :source-bytes (or (:b r) 0)}]))
                            (jdbc/execute!
                             conn ["SELECT l.status, COUNT(e.form_id) AS n,
                                           SUM(LENGTH(e.source)) AS b
                                    FROM elements e JOIN lines l ON l.id = e.line
                                    GROUP BY l.status"]))
                bl   (jdbc/execute-one!
                      conn ["SELECT COUNT(*) AS n, SUM(LENGTH(bytes)) AS b FROM blobs"])]
            {:deltas   {:n (reduce + 0 (map :n by-op))
                        :payload-bytes (reduce + 0 (map :payload-bytes by-op))
                        :by-op by-op}
             :elements {:n (or (:n els) 0) :source-bytes (or (:b els) 0)
                        :by-status by-st}
             :blobs    {:n (or (:n bl) 0) :bytes (or (:b bl) 0)}
             :housekeeping (housekeeping-stats conn (reduce + 0 (map :n by-op)))}))

(defn delta-form-ids
  "Every form id delta `d` touches, distinct, in the order the delta names
  them: `:form-id` (a single write), `:form-ids` (a group — rename, move,
  changeset), and the keys of `:sources` (ingest and the whole-namespace
  shapes). A delta with none — a marker, a commit-point, a config write —
  answers `[]`. The index writer and its backfill both read this, so a new
  shape that names forms is taught here once."
  [d]
  (into [] (distinct)
        (concat (when-let [f (:form-id d)] [f])
                (:form-ids d)
                (keys (:sources d)))))

^:reads (defn ^:export delta-ids-touching
          "The ids of every delta that touched any of `fids`, in journal order —
  one indexed read over `delta_forms`, never a payload parse. Empty for no
  forms. Line-agnostic on purpose: it answers over the whole journal, and a
  caller that wants one line's view intersects with that line's ancestry."
          [conn fids]
          (if (empty? fids)
            []
            (let [marks (apply str (interpose "," (repeat (count fids) "?")))]
              (mapv #(or (:deltas/id %) (:id %))
                    (jdbc/execute!
                     conn (into [(str "SELECT DISTINCT d.id, d.seq FROM delta_forms f
                                       JOIN deltas d ON d.id = f.delta_id
                                       WHERE f.form_id IN (" marks ") ORDER BY d.seq")]
                                fids))))))

(defn index-journal-forms!
  "Backfill `delta_forms` for a journal written before the index existed:
  when the journal has entries and the index has none, parse each candidate
  payload once and write its rows. Returns the number of deltas indexed, 0
  when there was nothing to do — which is the answer on every open after the
  first, at the cost of one existence query.

  Candidates are prefiltered in SQL on the text of the payload: only one
  that mentions `:form-id`, `:form-ids` or `:sources` can name a form, so the
  73 MB of `:commit` manifests and 33 MB of `:file-put` bodies in one real
  journal are never read into the process for this. The filter is a
  superset of `delta-form-ids`' keys, never a substitute — the parse decides."
  [conn]
  (let [empty-index? (nil? (jdbc/execute-one! conn ["SELECT 1 FROM delta_forms LIMIT 1"]))
        has-journal? (some? (jdbc/execute-one! conn ["SELECT 1 FROM deltas LIMIT 1"]))]
    (if-not (and empty-index? has-journal?)
      0
      (jdbc/with-transaction [tx conn]
        (reduce
         (fn [n {:keys [id payload] :as row}]
           (let [id   (or id (:deltas/id row))
                 d    (edn/read-string (or payload (:deltas/payload row)))
                 fids (delta-form-ids d)]
             (doseq [fid fids]
               (jdbc/execute! tx ["INSERT OR IGNORE INTO delta_forms (delta_id, form_id)
                                   VALUES (?,?)" id (str fid)]))
             (if (seq fids) (inc n) n)))
         0
         (jdbc/execute! tx ["SELECT id, payload FROM deltas
                             WHERE payload LIKE '%:form-id%' OR payload LIKE '%:sources%'
                             ORDER BY seq"]))))))

^:reads (defn ^:export deltas-touching
          "ONE LINE's deltas that touched any of `fids`, oldest first, as full delta
  maps. `:since <delta id>` narrows to those AFTER it along the line. Empty
  for no forms.

  Two indexes and no fold: the line's ancestry (`ancestry-cte`) intersected
  with `delta_forms`. This is what `sources-at` and every form-level history
  question read instead of walking the whole list the store value used to
  carry — a form's history is typically a dozen deltas out of tens of
  thousands, and the fold read all of them to find those."
          [conn line-id fids & {:keys [since]}]
          (if (empty? fids)
            []
            (let [marks (apply str (interpose "," (repeat (count fids) "?")))]
              (mapv row->delta
                    (jdbc/execute!
                     conn (-> [(str ancestry-cte
                                    " SELECT * FROM deltas
                                      WHERE id IN (SELECT id FROM anc)
                                        AND id IN (SELECT delta_id FROM delta_forms
                                                   WHERE form_id IN (" marks "))"
                                    (when since
                                      " AND seq > (SELECT seq FROM deltas WHERE id = ?)")
                                    " ORDER BY seq")
                               (line-head conn line-id)]
                              (into (map str fids))
                              (cond-> since (conj since))))))))

^:reads (defn ^:export last-delta-on-ns
          "The newest delta on namespace `ns-sym` from `line-id`'s view, as a delta
  map, or nil when the line has never touched it. The staleness question
  (`last-write-on`) asked this with an uncached `(last (filter …))` over the
  whole list; here it is the line's ancestry joined to the `deltas(ns)`
  index, newest first, one row."
          [conn line-id ns-sym]
          (some-> (jdbc/execute-one!
                   conn [(str ancestry-cte
                              " SELECT * FROM deltas
                                WHERE id IN (SELECT id FROM anc) AND ns = ?
                                ORDER BY seq DESC LIMIT 1")
                         (line-head conn line-id) (str ns-sym)])
                  row->delta))

^:reads (defn ^:export last-marker
          "The newest delta of `op` (a keyword — `:done`, `:commit`, `:turn-begin`)
  on `line-id`, or nil. `:agent` narrows to that agent's own — a done is an
  agent's boundary, and another agent's done on the same line is not it.

  This is where every bounded-suffix reader starts: `forms-changed-since`,
  `last-judged-done`, `turn-open?`, `unchanged-since-done` all ask \"where
  was the last X\" and then read forward. The agent lives inside the payload,
  so it is prefiltered by text and CONFIRMED on the parsed delta — a LIKE is
  a superset, never the answer."
          [conn line-id op & {:keys [agent]}]
          (let [head (line-head conn line-id)
                base (str ancestry-cte
                          " SELECT * FROM deltas
                            WHERE id IN (SELECT id FROM anc) AND op = ?")]
            (if agent
              (->> (jdbc/execute!
                    conn [(str base " AND payload LIKE ? ORDER BY seq DESC")
                          head (name op) (str "%:agent " (pr-str agent) "%")])
                   (map row->delta)
                   (filter #(= agent (:agent %)))
                   first)
              (some-> (jdbc/execute-one!
                       conn [(str base " ORDER BY seq DESC LIMIT 1") head (name op)])
                      row->delta))))

^:reads (defn ^:export prompt-for-forms
          "`{form-id prompt}` for each of `fids` that `line-id`'s history explains:
  the `:prompt` of the NEWEST delta on the line that touched the form and
  carried one. Forms with no prompt-carrying delta are absent.

  `prompt-by-form` folded every delta in the store value to build this for
  ALL 3,281 forms, and was rebuilt after every write because it was cached on
  store identity — to answer the `:why` on eight cards. This asks for the
  eight: the line's ancestry, `delta_forms`, a text prefilter on `:prompt`,
  newest first, stopping as soon as every asked-for form has its answer.

  A `:system true` delta is pipeline HOUSEKEEPING (the auto-reorder), not an
  intent about the form, and is skipped — it used to overwrite the author's
  ask on 7% of forms, because the last prompt naming a form wins and the
  pipeline writes last. `:ignoring` is a set of prompt strings to skip as
  well: the legacy text those writes carried before the mark existed, which
  the storage layer cannot name because the constant lives above it."
          [conn line-id fids & {:keys [ignoring]}]
          (if (empty? fids)
            {}
            (let [wanted (set (map str fids))
                  marks  (apply str (interpose "," (repeat (count wanted) "?")))
                  rows   (jdbc/execute!
                          conn (into [(str ancestry-cte
                                           " SELECT * FROM deltas
                                             WHERE id IN (SELECT id FROM anc)
                                               AND id IN (SELECT delta_id FROM delta_forms
                                                          WHERE form_id IN (" marks "))
                                               AND payload LIKE '%:prompt %'
                                               AND payload NOT LIKE '%:system true%'
                                             ORDER BY seq DESC")
                                      (line-head conn line-id)]
                                     wanted))]
              (reduce (fn [acc row]
                        (if (= (count acc) (count wanted))
                          (reduced acc)
                          (let [d (row->delta row)
                                p (:prompt d)]
                            (if (and (string? p) (seq p)
                                     (not (:system d))
                                     (not (contains? ignoring p)))
                              (reduce (fn [a fid]
                                        (if (and (wanted fid) (not (contains? a fid)))
                                          (assoc a fid p)
                                          a))
                                      acc (map str (delta-form-ids d)))
                              acc))))
                      {} rows))))

^:reads (defn ^:export last-write-per-ns
          "`{ns {:id :prompt}}` — the newest delta on each namespace from `line-id`'s
  view, session markers (`*session*`) excluded. The same map `record-delta`
  keeps current on every append, rebuilt at load by one grouped query over
  the line's ancestry so a loaded store answers exactly what a written one
  would. The payload is parsed only for the winning row per namespace."
          [conn line-id]
          (let [head (line-head conn line-id)]
            (into {}
                  (map (fn [row]
                         (let [d (row->delta row)]
                           [(:ns d) (cond-> {:id (:id d)}
                                      (:prompt d) (assoc :prompt (:prompt d)))])))
                  (jdbc/execute!
                   conn [(str ancestry-cte
                              " SELECT * FROM deltas
                                WHERE seq IN (SELECT MAX(seq) FROM deltas
                                              WHERE id IN (SELECT id FROM anc)
                                                AND ns <> '*session*'
                                              GROUP BY ns)")
                         head]))))

^:reads (defn ^:export line-length
          "How many deltas `line-id` has along its own history — the size of its
  ancestry walk, 0 for a line never written to. This is the `:line-pos` a
  loaded store carries and `record-delta` advances: the count the write path
  used to take from `(count (store/deltas …))`, now one indexed walk that
  fetches no payload."
          [conn line-id]
          (or (:n (jdbc/execute-one!
                   conn [(str ancestry-cte " SELECT COUNT(*) AS n FROM anc")
                         (line-head conn line-id)]))
              0))

^:reads (defn ^:export head-delta
          "The delta `line-id` currently points at, as a delta map — or nil for a
  line never written to. One row by primary key. `commit-point!` reads it
  to ask whether the newest entry is already a commit-point; it used to take
  `(last deltas)` off the list the value carried."
          [conn line-id]
          (when-let [h (line-head conn line-id)]
            (some-> (jdbc/execute-one! conn ["SELECT * FROM deltas WHERE id = ?" h])
                    row->delta)))

^:reads (defn ^:export on-line?
          "True when delta `id` is on `line-id`'s history — reachable from its head
  by the parent walk. False for an id on another line only, and for an id the
  journal has never seen. A retroactive commit-point (`commit_point {target}`)
  asks this before marking a spot; it used to scan the whole in-RAM list."
          [conn line-id id]
          (some? (jdbc/execute-one!
                  conn [(str ancestry-cte " SELECT 1 FROM anc WHERE id = ? LIMIT 1")
                        (line-head conn line-id) id])))

^:reads (defn ^:export recent-window
          "The `:recent` window for `line-id`, as delta maps oldest first: every
  delta from the `:done` immediately before the line's newest `:commit`
  (or the commit itself when no done precedes it) to the head — the same
  window `record-delta` keeps current on every append, so a loaded value and
  a written one answer the recent past identically. The whole line when it
  has no commit-point yet."
          [conn line-id]
          (let [head   (line-head conn line-id)
                seq-of (fn [sql & args]
                         (:s (jdbc/execute-one!
                              conn (into [(str ancestry-cte sql) head] args))))
                commit (seq-of " SELECT MAX(seq) AS s FROM deltas
                                 WHERE id IN (SELECT id FROM anc) AND op = 'commit'")
                start  (when commit
                         (or (seq-of " SELECT MAX(seq) AS s FROM deltas
                                       WHERE id IN (SELECT id FROM anc) AND op = 'done'
                                         AND seq < ?" commit)
                             commit))]
            (mapv row->delta
                  (jdbc/execute!
                   conn (cond-> [(str ancestry-cte
                                      " SELECT * FROM deltas WHERE id IN (SELECT id FROM anc)"
                                      (when start " AND seq >= ?")
                                      " ORDER BY seq")
                                 head]
                          start (conj start))))))

^:reads (defn ^:export code-deltas-after
          "How many CODE deltas `line-id` gained after a point — `{:id delta-id}`
  (by position along the line) or `{:at epoch-ms}` (by clock, strictly
  after) — the ONE spelling of a currency count: the host's, the jar's and
  the bundle's `:behind` all read it. Markers (`store.fields/markers`:
  verify, done, commit, the turn pair…) never count — a host that has not
  \"loaded\" a done is not behind on anything. Both shapes are one indexed
  query over the line's ancestry; neither parses a payload."
          [conn line-id {:keys [id at]}]
          (let [marks (apply str (interpose "," (repeat (count fields/markers) "?")))
                ops   (map name fields/markers)
                base  (str ancestry-cte
                           " SELECT COUNT(*) AS n FROM deltas
                             WHERE id IN (SELECT id FROM anc)
                               AND op NOT IN (" marks ")")]
            (or (:n (jdbc/execute-one!
                     conn (cond
                            id (into [(str base " AND seq > (SELECT seq FROM deltas WHERE id = ?)")
                                      (line-head conn line-id)]
                                     (concat ops [id]))
                            :else (into [(str base " AND at > ?") (line-head conn line-id)]
                                        (concat ops [(long (or at 0))])))))
                0)))

(defn backfill-at!
  "Fill the `at` column for every delta still missing it — a journal written
  before `at` was a column. Parses each such payload once for its `:at`;
  returns the number of rows filled, 0 on every later open at the cost of
  one indexed existence query. A delta whose payload carries no `:at` (the
  earliest ones) is left NULL: a count by clock cannot place it, and it
  should not be placed by guessing."
  [conn]
  (if (nil? (jdbc/execute-one! conn ["SELECT 1 FROM deltas WHERE at IS NULL LIMIT 1"]))
    0
    (jdbc/with-transaction [tx conn]
      (reduce (fn [n row]
                (let [id (or (:id row) (:deltas/id row))
                      at (:at (edn/read-string (or (:payload row) (:deltas/payload row))))]
                  (if (number? at)
                    (do (jdbc/execute! tx ["UPDATE deltas SET at = ? WHERE id = ?" (long at) id])
                        (inc n))
                    n)))
              0
              (jdbc/execute! tx ["SELECT id, payload FROM deltas
                                  WHERE at IS NULL AND payload LIKE '%:at %'"])))))

^:reads (defn ^:export last-artifact-put
          "The newest `:artifact-put` delta for `path` on `line-id` that is not a
  removal, as a delta map — or nil when nothing has recorded one. The
  browser's currency is measured from it. Prefiltered in SQL on op and on the
  path's text in the payload; the parse confirms both."
          [conn line-id path]
          (->> (jdbc/execute!
                conn [(str ancestry-cte
                           " SELECT * FROM deltas
                             WHERE id IN (SELECT id FROM anc) AND op = 'artifact-put'
                               AND payload LIKE ?
                             ORDER BY seq DESC")
                      (line-head conn line-id)
                      (str "%:path " (pr-str (str path)) "%")])
               (map row->delta)
               (filter #(and (= (str path) (str (:path %)))
                             (not= :remove (:action %))))
               first))

^:reads (defn ^:export ops-after
          "`[{:id :op :ns} …]` for every delta on `line-id` after delta `id`,
  oldest first — the three indexed columns and no payload, for a reader that
  only needs to know what KIND of thing happened and to which namespace.
  The bundle's currency counts client writes this way; fetching payloads to
  answer it would parse megabytes to read three symbols."
          [conn line-id id]
          (mapv (fn [r] {:id (:deltas/id r)
                         :op (keyword (:deltas/op r))
                         :ns (symbol (:deltas/ns r))})
                (jdbc/execute!
                 conn [(str ancestry-cte
                            " SELECT id, op, ns FROM deltas
                              WHERE id IN (SELECT id FROM anc)
                                AND seq > (SELECT seq FROM deltas WHERE id = ?)
                              ORDER BY seq")
                       (line-head conn line-id) id])))

^:reads (defn ^:export delta-by-id
          "The delta with `id`, as a delta map, or nil. One row by primary key —
  for the reader that holds an id (a commit-point's `:target`, a `:since`
  argument) and needs the entry behind it, without scanning a list for it."
          [conn id]
          (some-> (jdbc/execute-one! conn ["SELECT * FROM deltas WHERE id = ?" id])
                  row->delta))

^:reads (defn ^:export line-deltas
          "ONE LINE's whole journal as delta maps, oldest first — or only the part
  after delta `since` when given, or only the deltas whose op is in `ops`
  (a commit-point list wants `[:commit]` and nothing else). This is the read
  the history VIEWS (`query_history`, `query_changes`, a commit-point's tree,
  an episode diff) hydrate with, at the moment they are asked: a session no
  longer carries its line's history in the value, because the value read it
  for two scalars and a bounded window, and the views that genuinely need
  all of it are asked a few times a day. The op filter is SQL, so a reader
  that wants a few dozen markers never parses the payloads it does not want.
  Commit-point `:files` manifests are thinned exactly as `load-store` thins
  them."
          [conn line-id & {:keys [since ops]}]
          (thin-commit-manifests
           (mapv row->delta
                 (jdbc/execute!
                  conn (into [(str ancestry-cte
                                   " SELECT id, op, ns, payload FROM deltas
                                     WHERE id IN (SELECT id FROM anc)"
                                   (when since
                                     " AND seq > (SELECT seq FROM deltas WHERE id = ?)")
                                   (when (seq ops)
                                     (str " AND op IN ("
                                          (str/join "," (repeat (count ops) "?"))
                                          ")"))
                                   " ORDER BY seq")
                              (line-head conn line-id)]
                             (concat (when since [since])
                                     (map name ops)))))))

^:reads (defn ^:export sources-at
          "`{form-id source}` for each of `fids` as of delta `at-id` (inclusive) on
  `line-id` — the content view a baseline comparison needs. A form deleted by
  then is absent; `at-id` nil (before any delta) is `{}`.

  Folds only the deltas that TOUCHED those forms (`delta_forms`), up to the
  point asked for. `store/sources-at` folded the whole log from the root for
  every form, on every done, for every rule that compares against the last
  done — and the callers ever ask about the handful of forms an episode
  changed."
          [conn line-id at-id fids]
          (if (or (nil? at-id) (empty? fids))
            {}
            (let [wanted (set (map str fids))
                  ;; next.jdbc qualifies a plain column's alias by its table (:deltas/s) and
                  ;; leaves an aggregate's bare — read both rather than betting
                  upto   (let [r (jdbc/execute-one! conn ["SELECT seq AS s FROM deltas WHERE id = ?" at-id])]
                           (or (:s r) (:deltas/s r)))
                  marks  (apply str (interpose "," (repeat (count wanted) "?")))]
              (if (nil? upto)
                {}
                (reduce (fn [acc row]
                          (let [d   (row->delta row)
                                acc (into acc (filter (comp wanted key)) (:sources d))]
                            (if (and (= :delete (:op d)) (wanted (str (:form-id d))))
                              (dissoc acc (str (:form-id d)))
                              acc)))
                        {}
                        (jdbc/execute!
                         conn (into [(str ancestry-cte
                                          " SELECT * FROM deltas
                                            WHERE id IN (SELECT id FROM anc)
                                              AND seq <= ?
                                              AND id IN (SELECT delta_id FROM delta_forms
                                                         WHERE form_id IN (" marks "))
                                            ORDER BY seq")
                                     (line-head conn line-id) upto]
                                    wanted)))))))

^:reads (defn ^:export last-full-check
          "The newest `:verify` delta on `line-id` whose result is scoped
  `:full-check` — the whole-store verdict — as a delta map, or nil. An
  episode-scoped verify (the kind every write appends) is not one, and the
  scope lives in the payload, so verify rows are read newest first behind a
  text prefilter and the first whose parsed result says `:full-check` wins."
          [conn line-id]
          (->> (jdbc/execute!
                conn [(str ancestry-cte
                           " SELECT * FROM deltas
                             WHERE id IN (SELECT id FROM anc) AND op = 'verify'
                               AND payload LIKE '%:full-check%'
                             ORDER BY seq DESC")
                      (line-head conn line-id)])
               (map row->delta)
               (filter #(= :full-check (get-in % [:result :scope])))
               first))

^:reads (defn ^:export recent-markers
          "The newest `n` deltas of `op` on `line-id`, newest first, as delta
  maps. `breaking-changes` looks back through the last dozen dones for the
  baseline a late `^:breaking-ok` may be discharging; that look-back is
  bounded here rather than by walking the whole list."
          [conn line-id op n]
          (mapv row->delta
                (jdbc/execute!
                 conn [(str ancestry-cte
                            " SELECT * FROM deltas
                              WHERE id IN (SELECT id FROM anc) AND op = ?
                              ORDER BY seq DESC LIMIT ?")
                       (line-head conn line-id) (name op) (long n)])))

(defn persist-refs!
  "Replace ONE LINE's reference-index rows for `nses` from `store`'s `:refs`
  entries: `form_refs` (one row per edge) and `refs_keys` (the key each
  namespace's rows were computed from). A namespace the value has no entry
  for has none on disk afterwards — the db never invents an index, so a
  stale one cannot outlive the value's. Runs inside the caller's
  transaction; `write-snapshot!` calls it beside the elements, and the
  done-point calls it alone for entries a journal replay left stale."
  [tx store nses line-id]
  (doseq [ns-sym nses]
    (jdbc/execute! tx ["DELETE FROM form_refs WHERE line = ? AND ns = ?" line-id (str ns-sym)])
    (jdbc/execute! tx ["DELETE FROM refs_keys WHERE line = ? AND ns = ?" line-id (str ns-sym)])
    (when-let [{:keys [key rows]} (get-in store [:refs ns-sym])]
      (jdbc/execute! tx ["INSERT INTO refs_keys (line, ns, refs_key) VALUES (?,?,?)"
                         line-id (str ns-sym) key])
      (doseq [[i r] (map-indexed vector rows)]
        (jdbc/execute! tx ["INSERT INTO form_refs (line,ns,seq,from_form,to_ns,to_name,row)
                            VALUES (?,?,?,?,?,?,?)"
                           line-id (str ns-sym) i (:from-form r)
                           (str (:to-ns r)) (str (:to-name r)) (pr-str r)])))))

(defn ^:export persist-index!
  "Replace ONE LINE's reference-index rows for `nses` from `store`'s `:refs`
  entries, in a transaction of its own — the done-point's path for entries
  a journal replay left stale, where no element changed and nothing should
  be rewritten but the index. `write-snapshot!` writes the same rows beside
  the elements through `persist-refs!`."
  [conn store nses line-id]
  (jdbc/with-transaction [tx conn]
    (persist-refs! tx store nses line-id)))

(defn ^:export copy-view!
  "Copy line `from`'s VIEW onto line `to`: its `elements`, and the reference
  index computed from them (`form_refs`, `refs_keys`). The three are one
  thing — a materialization and what was derived from it — and a fork or a
  land that copied one without the others would hand a line a view whose
  index belonged to somebody else's source. `to`'s existing rows are the
  caller's to drop first (`drop-view!`).

  Public so a test can build the shape every thread had BEFORE fork on
  write — a full copy with no write of its own in it — which is the shape
  that served a two-day-old view as the branch after a restart."
  [tx from to]
  (jdbc/execute! tx ["INSERT INTO elements
                        (line,ns,pos,kind,form_id,name,source,comment,rank)
                      SELECT ?, ns, pos, kind, form_id, name, source, comment, rank
                      FROM elements WHERE line = ?" to from])
  (jdbc/execute! tx ["INSERT INTO form_refs (line,ns,seq,from_form,to_ns,to_name,row)
                      SELECT ?, ns, seq, from_form, to_ns, to_name, row
                      FROM form_refs WHERE line = ?" to from])
  (jdbc/execute! tx ["INSERT INTO refs_keys (line,ns,refs_key)
                      SELECT ?, ns, refs_key FROM refs_keys WHERE line = ?" to from]))

(defn ^:export create-line!
  "Mint a line and return its id.

  `:kind` is \"branch\" or \"thread\" — a thread is the anonymous case and has
  no `:nm`. `:base` is the delta it SPLITS FROM, and the head starts there: a
  line that has written nothing sits exactly where its base sat, with nothing
  copied out of the JOURNAL. That is the whole economy of the model — a split
  costs a row.

  `:parent` is the parent LINE's id (a thread's branch), not a delta, and it
  is where the new line inherits its VIEW from. A BRANCH copies the parent's
  rows in one INSERT … SELECT — ~2,481 form rows here against 23,560 deltas
  to fold for the same answer. A THREAD copies nothing (fork on write): it
  reads its branch's view until its first write, which persists its whole
  value. Every `done` leaves a session on a fresh thread, and a session that
  landed and then ended used to leave a full copy of the store behind with
  not one write in it — 138 of those held 85% of a 2.1 GB file.

  The copy follows `:parent` rather than `:base` because a delta cannot say
  whose view of it to duplicate. A line with no parent starts EMPTY — correct
  for a store's first line, wrong for a fork, so a fork must name its parent.

  The row lands BEFORE the copy on purpose: interrupted between them leaves a
  line with an empty view, which reads as unwritten. The other order would
  leave rows belonging to a line that does not exist."
  [conn {:keys [kind base parent agent] nm :name}]
  (let [id  (str (java.util.UUID/randomUUID))
        now (System/currentTimeMillis)
        k   (or kind "thread")]
    (jdbc/execute! conn ["INSERT INTO lines
                            (id,name,kind,head,base,parent,agent,created_at,used_at,status)
                          VALUES (?,?,?,?,?,?,?,?,?,'open')"
                         id nm k base base parent agent now now])
    (when (and parent (not= "thread" k))
      (copy-view! conn parent id))
    id))

^:reads (defn ^:export trunk-line-id!
  "The trunk line's id, minting the row when a store predates the lines table.

  A store written before lines existed has all its history and no line naming
  it, so the row is created with its base at the CURRENT journal head — the
  whole log is behind the trunk immediately, with nothing moved or copied.

  The bang is the mint; reading an existing store's trunk is a plain read."
  [conn]
  (or (line-id-by-name conn "main")
      (create-line! conn
                    {:name "main" :kind "branch"
                     :base (one-col (jdbc/execute-one!
                                     conn ["SELECT id FROM deltas ORDER BY seq DESC LIMIT 1"]))})))

(defn- drop-view!
  "Drop line `line-id`'s VIEW — `elements` and the reference index derived
  from them — leaving its journal untouched. The one door every settling
  path (`land-thread!`, `abandon-thread!`, `delete-line!`) goes through, so
  the rows that are the space are always released together."
  [tx line-id]
  (doseq [table ["elements" "form_refs" "refs_keys"]]
    (jdbc/execute! tx [(str "DELETE FROM " table " WHERE line = ?") line-id])))

(defn ^:export delete-line!
  "Drop a line: its row and its materialized `elements` rows. Returns true if
  a line was there to drop.

  **The DELTAS stay.** They become unreachable from any head, which is what
  history means here — a line is a pointer, and dropping the pointer is not
  the same as claiming the work never happened. It also makes dropping cheap
  and safe to do casually, which is the whole point of an anonymous line that
  gets abandoned when an agent stops."
  [conn line-id]
  (drop-view! conn line-id)
  (pos? (or (:next.jdbc/update-count
             (jdbc/execute-one! conn ["DELETE FROM lines WHERE id = ?" line-id]))
            0)))

(defn ^:export abandon-thread!
  "Settle `thread-line-id` as `abandoned` and drop its materialization.

  The `elements` rows are the space — a thread's view is a full copy of its
  branch's, thousands of rows on a real store — and they are also the only
  part that is pure derivation, so dropping them costs nothing that cannot be
  recomputed from the journal.

  The DELTAS stay, and the row stays. A drop says \"nobody is going to finish
  this\", not \"this never happened\": the work is still walkable from the
  line's head by anyone who goes looking, which is the difference between
  abandoning a line and rewriting history."
  [conn thread-line-id]
  (jdbc/with-transaction [tx conn]
    (drop-view! tx thread-line-id)
    (jdbc/execute! tx ["UPDATE lines SET status = 'abandoned', used_at = ?
                        WHERE id = ?"
                       (System/currentTimeMillis) thread-line-id])
    true))

(defn red-failures
  "The QUALIFIED test symbols delta `d` reports red — a `:verify` or
  `:observe` whose `:result :failures` names them. A bare name (one the
  producer could not resolve to a namespace) is dropped: it cannot be
  matched back to a test, so counting it would credit evidence nobody can
  read. Any other delta answers `[]`."
  [d]
  (if-not (#{:verify :observe} (:op d))
    []
    (into [] (comp (keep :test)
                   (filter #(str/includes? (str %) "/"))
                   (distinct))
          (get-in d [:result :failures]))))

^:reads (defn- episode-form-ids
  "The form ids touched between the last `:done` on `parent-id`'s ancestry
   and `parent-id` itself — the EPISODE a red at `parent-id`'s child belongs
   to. The recursive walk stops at the first done rather than going to the
   root, so a red on a 30,000-delta line costs a walk of the dozens since
   its last done, not the whole history. No done at all means the whole
   ancestry, which is right for a store's first episode."
  [tx parent-id]
  (mapv #(or (:delta_forms/form_id %) (:form_id %))
        (jdbc/execute!
         tx ["WITH RECURSIVE anc(id, parent, op) AS (
                SELECT id, parent, op FROM deltas WHERE id = ?
                UNION ALL
                SELECT d.id, d.parent, d.op FROM deltas d
                  JOIN anc ON d.id = anc.parent
                  WHERE anc.op <> 'done')
              SELECT DISTINCT form_id FROM delta_forms
              WHERE delta_id IN (SELECT id FROM anc WHERE op <> 'done')"
             parent-id])))

(defn credit-reds!
  "Write what a red delta `d` is evidence OF: for every qualified test it
   names red and every form its episode changed, one more count on
   (form, test), stamped with `d`'s id. Runs inside the transaction that
   appends `d`, so the index and the journal cannot disagree about a red.
   A delta with no red failures writes nothing. Returns the rows written."
  [tx d]
  (let [tests (red-failures d)]
    (if (empty? tests)
      0
      (let [fids (episode-form-ids tx (:parent d))]
        (doseq [fid fids, t tests]
          (jdbc/execute! tx ["INSERT INTO form_reds (form_id, test, n, last_delta)
                              VALUES (?,?,1,?)
                              ON CONFLICT(form_id, test) DO UPDATE
                              SET n = n + 1, last_delta = excluded.last_delta"
                             fid (str t) (:id d)]))
        (* (count fids) (count tests))))))

(defn index-journal-reds!
  "Backfill `form_reds` for a journal written before the index existed: when
   the journal has red verifies or observations and the index has no rows,
   credit each one once, oldest first. Returns the number of reds indexed;
   0 on every open after the first, at the cost of one existence query.
   Candidates are prefiltered on the payload text — only a result that
   carries `:failures [{` can name a red test — so the manifests and file
   bodies in a large journal are never parsed for this."
  [conn]
  (let [empty-index? (nil? (jdbc/execute-one! conn ["SELECT 1 FROM form_reds LIMIT 1"]))
        has-reds?    (some? (jdbc/execute-one!
                             conn ["SELECT 1 FROM deltas
                                    WHERE op IN ('verify','observe')
                                      AND payload LIKE '%:failures [{%' LIMIT 1"]))]
    (if-not (and empty-index? has-reds?)
      0
      (jdbc/with-transaction [tx conn]
        (reduce
         (fn [n row]
           (let [id (or (:deltas/id row) (:id row))
                 op (or (:deltas/op row) (:op row))
                 d  (assoc (edn/read-string (or (:deltas/payload row) (:payload row)))
                           :id id :op (keyword op))]
             (if (pos? (credit-reds! tx d)) (inc n) n)))
         0
         (jdbc/execute! tx ["SELECT id, op, payload FROM deltas
                             WHERE op IN ('verify','observe')
                               AND payload LIKE '%:failures [{%'
                             ORDER BY seq"]))))))

^:reads (defn ^:export reds-for
  "What usually breaks when these forms change: every (form, test) the index
   has credited for `form-ids`, as `{:form-id :test :n :last}` rows, the most
   often red first. `:test` is a qualified symbol; `:last` the red delta that
   counted most recently. Empty when nothing has ever gone red beside them —
   which is the absence of evidence, and a reader should say so rather than
   read it as safety."
  [conn form-ids]
  (if (empty? form-ids)
    []
    (mapv (fn [r]
            {:form-id (or (:form_reds/form_id r) (:form_id r))
             :test    (symbol (or (:form_reds/test r) (:test r)))
             :n       (or (:form_reds/n r) (:n r))
             :last    (or (:form_reds/last_delta r) (:last_delta r))})
          (jdbc/execute!
           conn (into [(str "SELECT form_id, test, n, last_delta FROM form_reds
                             WHERE form_id IN ("
                            (str/join "," (repeat (count form-ids) "?"))
                            ") ORDER BY n DESC, last_delta DESC")]
                      (map str form-ids))))))

(defn ensure-schema!
  "Bring `conn`'s schema up to what this code writes — every table, column,
  index and one-time backfill, all idempotent: `CREATE … IF NOT EXISTS`, an
  `ALTER … ADD COLUMN` whose throw is the no-op (SQLite has no IF NOT EXISTS
  there), a backfill that answers 0 on every run after the first. `open!`
  runs it on a fresh connection; `append!` runs it again on a connection
  that turns out to be OLDER than the code — a host that hot-reloaded a
  write path naming a column its connection had never seen. Three schema
  changes in one wave each broke the running host that way, and each time
  the cure was a human running `open!` by hand; this is that cure with the
  human removed."
  [conn]
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS meta (
                              k TEXT PRIMARY KEY, v TEXT NOT NULL)"])
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS deltas (
                              seq     INTEGER PRIMARY KEY AUTOINCREMENT,
                              id      TEXT UNIQUE NOT NULL,
                              op      TEXT NOT NULL,
                              ns      TEXT NOT NULL,
                              payload TEXT NOT NULL)"])
  ;; a `tree` column used to hold each commit-point's byte-exact snapshot of
  ;; every namespace — 94% of a 344MB journal at its worst, still 82MB
  ;; (39%) when it was removed. An older store has the column and its
  ;; rows; nothing reads or writes them, and DROP COLUMN rewrites the
  ;; whole table, so it is left where it is rather than paid for at every
  ;; open. `git/project-journal!` derives the tree by folding the log.
  (jdbc/execute! conn ["CREATE INDEX IF NOT EXISTS deltas_ns ON deltas(ns)"])
  ;; :parent has been on every delta since the beginning — the writer's head at
  ;; write time — but it lived inside the pr-str'd payload, where no query
  ;; could reach it. So the log was walkable only by loading all of it, and
  ;; a second line had to be a whole separate db FILE with its own copy of
  ;; the journal. As a column it is an index away from being a real DAG.
  ;; ALTER rather than an inline column so a fresh store and an existing one
  ;; take exactly one path; SQLite has no ADD COLUMN IF NOT EXISTS, so the
  ;; throw IS the no-op (same idiom as elements.comment below).
  (try (jdbc/execute! conn ["ALTER TABLE deltas ADD COLUMN parent TEXT"])
       (catch java.sql.SQLException _ nil))
  (jdbc/execute! conn ["CREATE INDEX IF NOT EXISTS deltas_parent ON deltas(parent)"])
  ;; MEASUREMENTS are about the journal, not in it. A delta is a link in
  ;; the chain every writer CASes against, so appending one MOVES THE
  ;; HEAD — and a number describing what something cost has no business
  ;; making a verdict lose that race. Learned the expensive way: harness
  ;; telemetry arriving on an exporter's interval appended a delta every
  ;; few seconds from an HTTP receiver that is not an agent and never
  ;; stops, and the store became unwritable — `full_check` computed a
  ;; whole-store answer for three to four minutes and then lost the
  ;; commit to a telemetry row, four times, and ordinary writes began
  ;; failing behind it. The interval was never the bug; ANY interval
  ;; makes the head non-quiescent.
  ;;
  ;; Two more costs the same rows were quietly paying: every such op has
  ;; to be registered as a merge MARKER purely so `merge-logs` will skip
  ;; it, and they sit on the load path, where the measured gap between
  ;; `load-elements` (~410ms) and `load-store` (~4s) is EDN-parsing delta
  ;; payloads.
  ;;
  ;; `delta` is a nullable FK: a measurement ABOUT a specific delta (a
  ;; turn's timing, a run's cost) names it; one that is about a span of
  ;; wall-clock time rather than an entry does not, and must not invent
  ;; an anchor to look tidy.
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS measurements (
                              seq     INTEGER PRIMARY KEY AUTOINCREMENT,
                              at      INTEGER NOT NULL,
                              kind    TEXT NOT NULL,
                              delta   TEXT REFERENCES deltas(id),
                              payload TEXT NOT NULL)"])
  (jdbc/execute! conn ["CREATE INDEX IF NOT EXISTS measurements_kind
                              ON measurements(kind, seq)"])
  ;; HISTORY BY FORM. The journal is indexed by namespace and by
  ;; parent; the form a delta touched lived only inside its EDN
  ;; payload, so "which deltas touched this form" meant parsing every
  ;; payload — which is the reason the whole log has been kept in RAM.
  ;; One row per (delta, form), written at append from every key a
  ;; delta names a form by (`delta-form-ids`), and the first graph edge
  ;; the store persists rather than recomputes.
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS delta_forms (
                              delta_id TEXT NOT NULL,
                              form_id  TEXT NOT NULL,
                              PRIMARY KEY (delta_id, form_id)) WITHOUT ROWID"])
  (jdbc/execute! conn ["CREATE INDEX IF NOT EXISTS delta_forms_form
                              ON delta_forms(form_id)"])
  ;; a journal older than the index is indexed HERE, once — a backfill
  ;; that waits for an operator runs on exactly one store
  (index-journal-forms! conn)
  ;; THE REFERENCE GRAPH, persisted. One row per edge leaving a form,
  ;; per line — the `:refs` index the value carries, written beside the
  ;; elements of a namespace whenever those are (`write-snapshot!`) and
  ;; read back at open (`load-refs`). `row` is the canonical record as
  ;; EDN; `from_form`/`to_ns`/`to_name` are lifted out so a reader that
  ;; is not the value — the reviewer UI, an orientation query — can ask
  ;; "who references X" in SQL. `refs_keys` carries the key each
  ;; namespace's rows were computed from, which is what lets a reader
  ;; tell a current entry from a stale one without recomputing it.
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS form_refs (
                              line      TEXT NOT NULL,
                              ns        TEXT NOT NULL,
                              seq       INTEGER NOT NULL,
                              from_form TEXT,
                              to_ns     TEXT NOT NULL,
                              to_name   TEXT NOT NULL,
                              row       TEXT NOT NULL,
                              PRIMARY KEY (line, ns, seq)) WITHOUT ROWID"])
  (jdbc/execute! conn ["CREATE INDEX IF NOT EXISTS form_refs_target
                              ON form_refs(line, to_ns, to_name)"])
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS refs_keys (
                              line     TEXT NOT NULL,
                              ns       TEXT NOT NULL,
                              refs_key TEXT NOT NULL,
                              PRIMARY KEY (line, ns)) WITHOUT ROWID"])
  ;; THE RED-AFTER INDEX: how many times test T went red in an episode
  ;; that changed form F, and the red that counted last. Written by
  ;; `credit-reds!` inside `append!` and read by `reds-for`. Not per
  ;; line: it is evidence about the CODE, and a red on a thread is as
  ;; true after the thread lands (or dies) as before. A journal older
  ;; than the index is indexed once, here, like `delta_forms`.
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS form_reds (
                              form_id    TEXT NOT NULL,
                              test       TEXT NOT NULL,
                              n          INTEGER NOT NULL,
                              last_delta TEXT NOT NULL,
                              PRIMARY KEY (form_id, test)) WITHOUT ROWID"])
  (index-journal-reds! conn)
  ;; `at` as a COLUMN: the currency counts ask how much code landed
  ;; after a clock time, and inside the EDN payload that was a parse
  ;; of every row. Same ADD COLUMN idiom as `parent` — the throw is
  ;; the no-op — and a journal older than the column is filled once.
  (try (jdbc/execute! conn ["ALTER TABLE deltas ADD COLUMN at INTEGER"])
       (catch java.sql.SQLException _ nil))
  (jdbc/execute! conn ["CREATE INDEX IF NOT EXISTS deltas_at ON deltas(at)"])
  (backfill-at! conn)
  ;; A LINE is a pointer to a head delta. A named line is a branch; an
  ;; anonymous one (name NULL) is an agent's thread. They are the same row
  ;; because they are the same thing — a thread is a branch nobody named.
  ;; `base` is the delta the line split from, which is what makes a split
  ;; findable from either side.
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS lines (
                              id         TEXT PRIMARY KEY,
                              name       TEXT UNIQUE,
                              kind       TEXT NOT NULL,
                              head       TEXT,
                              base       TEXT,
                              parent     TEXT,
                              agent      TEXT,
                              created_at INTEGER NOT NULL,
                              used_at    INTEGER NOT NULL,
                              status     TEXT NOT NULL)"])
  ;; The LEASE: which live PROCESS is writing this thread right now.
  ;; A thread is keyed by (agent, branch) so that one conversation
  ;; resumed later finds its own un-landed work — which is right, and
  ;; which also means two processes resuming the SAME conversation key
  ;; to one row and write it at once, the contention threads exist to
  ;; abolish. Identity answers "whose work is this"; it cannot answer
  ;; "who may write it now", and one value could never do both.
  ;;
  ;; pid PLUS start time, because pids are reused: the pair identifies
  ;; a process rather than a slot. Same ADD COLUMN idiom as `parent`
  ;; and `comment` — SQLite has no IF NOT EXISTS here, so the throw IS
  ;; the no-op, and an older store simply has nil owners, which reads
  ;; as unheld and behaves exactly as it did before.
  (try (jdbc/execute! conn ["ALTER TABLE lines ADD COLUMN owner_pid INTEGER"])
       (catch java.sql.SQLException _ nil))
  (try (jdbc/execute! conn ["ALTER TABLE lines ADD COLUMN owner_started INTEGER"])
       (catch java.sql.SQLException _ nil))
  (jdbc/execute! conn [elements-ddl])
  ;; a form OWNS the comment rendered above it (whitespace-is-rendering).
  ;; Same story as `tree` above: SQLite has no ADD COLUMN IF NOT EXISTS,
  ;; so adding it to an existing store throws and that is the no-op.
  (try (jdbc/execute! conn ["ALTER TABLE elements ADD COLUMN comment TEXT"])
       (catch java.sql.SQLException _ nil))
  ;; A FORM'S CREATION RANK, beside its derived position. `pos` is the
  ;; arrangement — definitions before callers — which the kernel reads
  ;; at boot, when there is no reference graph yet to derive it from;
  ;; `rank` is when the form arrived, the tiebreak that arrangement was
  ;; derived WITH. A store older than the column has rows whose only
  ;; ordering fact is their position, so it becomes their rank once.
  (try (jdbc/execute! conn ["ALTER TABLE elements ADD COLUMN rank INTEGER"])
       (catch java.sql.SQLException _ nil))
  (jdbc/execute! conn ["UPDATE elements SET rank = pos WHERE rank IS NULL"])
  ;; …and `line` is the one column that idiom cannot add: it belongs to the
  ;; PRIMARY KEY, and SQLite can neither add nor drop a key in place. So an
  ;; existing table is COPIED into the new shape with every row backfilled
  ;; to the trunk — the only line those rows could ever have belonged to.
  ;; It runs at most once per store, and afterwards a reader with no line
  ;; predicate still sees exactly what it saw before, which is what lets
  ;; the readers move one at a time instead of in lockstep with the schema.
  ;;
  ;; In a transaction because the half-done state is indistinguishable
  ;; from the finished one: a crash between the rename and the copy leaves
  ;; an empty `elements` that already HAS a line column, so the probe
  ;; below would skip it forever and the rows would be gone.
  (when (try (jdbc/execute! conn ["SELECT line FROM elements LIMIT 0"]) false
             (catch java.sql.SQLException _ true))
    (jdbc/with-transaction [tx conn]
      (let [trunk (trunk-line-id! tx)]
        (jdbc/execute! tx ["ALTER TABLE elements RENAME TO elements_unlined"])
        (jdbc/execute! tx [elements-ddl])
        (jdbc/execute! tx ["INSERT INTO elements
                              (line,ns,pos,kind,form_id,name,source,comment)
                            SELECT ?, ns, pos, kind, form_id, name, source, comment
                            FROM elements_unlined" trunk])
        (jdbc/execute! tx ["DROP TABLE elements_unlined"]))))
  ;; content-addressed dependency analysis (P4-deps M4/M6), keyed by
  ;; "lib@version" — a surface/native verdict is a pure fn of the coord
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS dep_surface (
                              id      TEXT PRIMARY KEY,
                              surface TEXT,
                              native  TEXT)"])
  ;; git-pull conflicts, held OFF the journal (G-series): the raw remote
  ;; file + provenance, kept until the agent resolves — the journal only
  ;; ever holds slopp-valid forms
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS quarantine (
                              path    TEXT PRIMARY KEY,
                              ns      TEXT,
                              source  TEXT,
                              sha     TEXT NOT NULL,
                              reason  TEXT NOT NULL,
                              at      INTEGER NOT NULL)"])
  ;; content-addressed binary assets (D-web wave 4): bytes live HERE,
  ;; the journal carries only shas — a large asset costs the log ~60B
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS blobs (
                              sha   TEXT PRIMARY KEY,
                              bytes BLOB NOT NULL)"])
  conn)

(defn ^:export open!
  "Open (creating if needed) the store db under `dir`; returns the connection,
  brought up to this code's schema by `ensure-schema!`.

  `{:create? false}` returns NIL instead of creating one when `dir` has no
  store yet — for callers that merely ASK whether a dir is slopp-managed.
  The MCP server is launched in whatever directory the editor has open, so
  an unconditional create colonises every project a user opens: an empty
  `.slopp/store.db` appears, and from then on the session-pause hook has
  something to write checkpoints into. Serving is a question, not an
  adoption; the store is materialized by the first real write."
  (^java.sql.Connection [dir] (open! dir nil))
  (^java.sql.Connection [dir {:keys [create?] :or {create? true}}]
   (let [f (io/file dir ".slopp" "store.db")]
     (when (or create? (.exists f))
       (io/make-parents f)
       (ensure-gitignore! dir)
       (let [conn (jdbc/get-connection
                   (jdbc/get-datasource {:dbtype "sqlite" :dbname (str f)}))]
         (jdbc/execute! conn ["PRAGMA journal_mode=WAL"])
         (jdbc/execute! conn ["PRAGMA busy_timeout=5000"])
         (ensure-schema! conn))))))

(defn schema-behind?
  "Is this SQLException the shape of a connection whose schema is OLDER than
  the code writing through it — a table or column the code names and the
  connection has never seen? The one SQL fault `append!` can cure itself,
  by running `ensure-schema!` and trying once more. As narrow as
  `writer-collision?`, and for the same reason: a fault this does not name
  must surface, or a real defect would retry as a schema repair."
  [^java.sql.SQLException e]
  (let [m (.toLowerCase (str (.getMessage e)))]
    (or (.contains m "no such table")
        (.contains m "no such column")
        (.contains m "has no column named"))))

^:reads (defn ^:export newest-commit-markers
  "The newest `n` `:commit` markers on ONE LINE, newest first, as delta maps
  with their full payload (the manifest included — this reads rows, not the
  thinned value).

  The question the projection's fast path asks before loading anything: is
  the newest marker already minted, and what is its parent? Two rows answer
  it; the line's whole ancestry (39,862 deltas on slopp's own store, 5s to
  parse) used to be loaded to find out."
  [conn line-id n]
  (mapv row->delta
        (jdbc/execute! conn
                       [(str ancestry-cte
                             " SELECT * FROM deltas WHERE id IN (SELECT id FROM anc)
                                AND op = 'commit' ORDER BY seq DESC LIMIT ?")
                        (line-head conn line-id) (long n)])))

^:reads (defn ^:export
  ^{:breaking-ok "the 2-arity settled a thread on owner death alone — the wrong rule, corrected the same day; its one caller (compact-store!) moved in the same write"}
  stale-open-threads
  "The open THREAD lines that have NOTHING TO RESUME and nobody minding them:
  no un-landed content since their fork (`content-ops` says which ops are
  content), untouched for longer than `max-age-ms`, and their owner process
  no longer alive (a thread with no recorded owner counts as unowned). The
  usual case is the fresh thread a `done` leaves a session on, then orphaned
  when the process ends: it holds a full materialized copy of the branch and
  not one write of its own. On slopp's own store 138 open threads held
  466,128 element rows against main's 3,539.

  A thread WITH un-landed work is never in this list, however dead its
  owner: a returning agent is meant to pick its thread back up, and its
  un-landed work IS the thread. Whether that one is finished is a person's
  or the agent's call (`thread_drop`), not compaction's. The age is a safety
  margin over the liveness test, not the signal: a pid can be reused."
  [conn max-age-ms content-ops]
  (let [cutoff (- (System/currentTimeMillis) max-age-ms)
        alive? (fn [pid]
                 (boolean
                  (when pid
                    (let [h (java.lang.ProcessHandle/of (long pid))]
                      (and (.isPresent h)
                           (.isAlive ^java.lang.ProcessHandle (.get h)))))))]
    (filterv (fn [l]
               (and (= "thread" (:kind l))
                    (= "open" (:status l))
                    (some-> (:used-at l) (< cutoff))
                    (not (alive? (:owner-pid l)))
                    (zero? (unlanded-count conn (:id l) content-ops))))
             (lines conn))))

^:reads (defn ^:export line-has-view?
  "Does `line-id` hold materialized `elements` rows of its own? A thread is
  minted WITHOUT a view (fork on write): until its first write it reads its
  branch's rows, and the rows it then writes are its own copy. The one
  question every reader of a line's view asks first."
  [conn line-id]
  (some? (jdbc/execute-one! conn ["SELECT 1 FROM elements WHERE line = ? LIMIT 1" line-id])))

(defn- write-snapshot!
  "The shared tail of persist!/append!: ONE LINE's element rows for the touched
  namespaces, their reference-index rows, the id counter, every registry meta
  row, and the blob table — ONE loop over slopp.store.fields/meta-fields, so a
  new fold-field persists by registration instead of by editing two
  near-identical transactions (the copy-paste this replaces silently lost any
  field a hand missed in ONE of them — surviving tests, vanishing on the live
  server's restart).

  `line-id` scopes both statements, and the DELETE is the one that matters.
  `elements` holds every open line's view of the store at once, so a delete
  naming only the namespace is not a wrong answer — it erases another agent's
  work, and what is left looks exactly like a write that never happened.

  FORK ON WRITE: a thread's first write finds it ROWLESS — it has been
  reading its branch's view — and must leave it holding its WHOLE value,
  elements and index for every namespace, not only the one it touched; the
  next load of this line would otherwise see one namespace and call that the
  store. A WRITE, not any append: a marker, a verify or a read-cost touches
  no namespace and leaves the line rowless — the first marker after a done
  used to copy the whole store back onto the thread.

  The reference index (`form_refs` + `refs_keys`) follows the same rule per
  namespace: its rows are REPLACED from the value's `:refs` entry, and a
  namespace the value has no entry for has none on disk afterwards — the db
  never invents an index, so a stale one cannot outlive the value's."
  [tx store nses line-id]
  (let [;; a BOOKKEEPING append (no namespace touched) materializes nothing:
        ;; a rowless line stays rowless, reading its branch. Without this the
        ;; first marker after a done copied the whole store onto the thread.
        nses (if (or (empty? nses) (line-has-view? tx line-id))
               nses
               (keys (:namespaces store)))]
    (doseq [ns-sym nses]
      ;; delete ALWAYS: a ns absent from the store (renamed away) must have
      ;; its rows purged, not linger for the next reopen
      (jdbc/execute! tx ["DELETE FROM elements WHERE line = ? AND ns = ?"
                         line-id (str ns-sym)])
      (doseq [[pos e] (map-indexed vector
                                   (get-in store [:namespaces ns-sym :elements]))]
        (jdbc/execute! tx ["INSERT INTO elements (line,ns,pos,kind,form_id,name,source,comment,rank)
                            VALUES (?,?,?,?,?,?,?,?,?)"
                           line-id (str ns-sym) pos (name (:kind e)) (:id e)
                           (some-> (:name e) str) (n/string (:node e))
                           (:comment e) (:rank e)])))
    ;; the reference index, beside the elements it was computed from
    (persist-refs! tx store nses line-id))
  ;; No id counter is persisted. It was the one statement here with a
  ;; FILE-wide obligation carried by a per-LINE value, and every attempt to
  ;; reconcile those two facts — last-writer-wins, then a monotonic MAX, then
  ;; per-session reserved blocks — reconciled them incompletely. Ids are
  ;; random names now, minted per call from nothing the value carries, so
  ;; there is no number here to get wrong.
  ;;
  ;; The `meta-fields` loop below is last-writer-wins and stays that way:
  ;; those are per-store values, where the last writer IS the right answer.
  ;; It was never the same obligation, which is why they were never one loop.
  (doseq [{:keys [field meta-key init absent-nil?]} (fields/meta-fields)]
    (let [v (get store field)]
      ;; :absent-nil? fields (the :modules pre-module adoption marker) are
      ;; never written while nil — a default here would destroy the marker
      ;; before open! ever sees it
      (when-not (and absent-nil? (nil? v))
        (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES (?, ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           meta-key (pr-str (if (nil? v) init v))]))))
  (put-blobs! tx (:blobs store {})))

(defn ^:export persist!
  "Write one mutation atomically: the delta, then the full snapshot tail
  (element rows of the touched namespaces, id counter, registry meta rows,
  blobs) via write-snapshot!. Namespaces are small; rewriting a ns's rows per
  edit keeps the write-through trivially correct. Multi-ns mutations (e.g. a
  cross-ns rename) pass the touched `nses` explicitly."
  ([conn store delta] (persist! conn store delta [(:ns delta)]))
  ([conn store delta nses]
   (jdbc/with-transaction [tx conn]
     (jdbc/execute! tx ["INSERT INTO deltas (id, op, ns, parent, payload)
                        VALUES (?,?,?,?,?)"
                        (:id delta) (name (:op delta)) (str (:ns delta))
                        (:parent delta)
                        (pr-str (dissoc delta :id :op :ns))])
     (when-let [head (:id delta)] (advance-trunk! tx head))
     (write-snapshot! tx store nses (trunk-line-id! tx)))
   nil))

(defn ^:export append!
  "Conditionally append `new-deltas` (+ the full snapshot tail via
  write-snapshot!) in ONE transaction, iff `line-id`'s head still equals
  `expected-head` (nil for a line with no writes yet). Returns true on commit;
  false = the head moved, and the caller refreshes its cache and rebases.

  The CAS is on the LINE, not the journal. It used to read the global journal
  head — correct exactly while one line owned a file, which is why a branch had
  to BE a separate file. Once many lines share one journal a global head is not
  a wrong answer but a dead system: every agent's write fails whenever ANY
  other agent writes anywhere in it. Two writers on two lines now never
  contend; two on ONE line still do, which is correct — that is the rebase
  path, and it is what makes concurrent agents on a shared branch work rather
  than merely coexist.

  The conditional UPDATE is BOTH the check and the advance in one statement,
  so no window exists between testing the head and moving it. SQLite (WAL)
  serializes writers across threads AND processes, which is what makes the
  shared-storage multi-server split possible.

  `line-id` is REQUIRED and deliberately has no default. A write that does not
  say which line it is on is exactly how a thread's work would silently land on
  main; the caller resolves the trunk where a reader can see it.

  A connection whose schema is OLDER than this code — a host that hot-reloaded
  a write path naming a column its connection never saw — fails the first
  statement that names it; that one fault (`schema-behind?`) is cured here by
  `ensure-schema!` and the append is tried once more. Every other SQL fault
  still surfaces."
  [conn store new-deltas nses line-id expected-head]
  (letfn [(attempt []
            (try
              (jdbc/with-transaction [tx conn]
                ;; an append with nothing new still VERIFIES the head (a caller that
                ;; raced and lost must hear so), it just leaves it where it is
                (let [new-head (if (seq new-deltas) (:id (last new-deltas)) expected-head)
                      moved    (:next.jdbc/update-count
                                (jdbc/execute-one!
                                 tx ["UPDATE lines SET head = ?, used_at = ?
                                      WHERE id = ? AND head IS ?"
                                     new-head (System/currentTimeMillis) line-id expected-head]))]
                  ;; `IS` rather than `=` so a first write (both sides NULL) matches;
                  ;; `=` is never true against NULL and would refuse every new line's
                  ;; first write forever
                  (when-not (pos? (or moved 0))
                    (throw (ex-info "line head moved" {::head-moved true})))
                  (doseq [d new-deltas]
                    ;; :parent stays in the payload too — the column is a denormalization
                    ;; for traversal, so row->delta and every reader below it are
                    ;; untouched.
                    ;; `at` is a COLUMN as well as a payload field: the currency counts
                    ;; ask "how much code landed after this clock time", and a compare
                    ;; inside EDN text is a parse of every row
                    (jdbc/execute! tx ["INSERT INTO deltas (id, op, ns, parent, at, payload)
                                        VALUES (?,?,?,?,?,?)"
                                       (:id d) (name (:op d)) (str (:ns d)) (:parent d) (:at d)
                                       (pr-str (dissoc d :id :op :ns))])
                    ;; and the form index, in the same transaction: one row per form
                    ;; the delta names, so history by form is a read and not a parse
                    (doseq [fid (delta-form-ids d)]
                      (jdbc/execute! tx ["INSERT OR IGNORE INTO delta_forms (delta_id, form_id)
                                          VALUES (?,?)" (:id d) (str fid)]))
                    ;; and the red-after index: a red verify/observe credits the forms
                    ;; its episode changed, here, so a later "what usually breaks when
                    ;; this changes" is a read of the same transaction's facts
                    (credit-reds! tx d))
                  (write-snapshot! tx store nses line-id)
                  true))
              (catch clojure.lang.ExceptionInfo e
                (if (::head-moved (ex-data e)) false (throw e)))
              ;; ONLY a writer collision is a retryable lost race. Any other SQL fault
              ;; must SURFACE: swallowing it returned false, the caller retried, and the
              ;; agent was told "commit contention" for what was really a bad statement.
              (catch java.sql.SQLException e
                (if (or (writer-collision? e) (duplicate-delta-id? e)) false (throw e)))))]
    (try
      (attempt)
      (catch java.sql.SQLException e
        (if (schema-behind? e)
          (do (ensure-schema! conn) (attempt))
          (throw e))))))

(defn ^:export land-thread!
  "Move `branch-line-id` onto `thread-line-id`'s head, iff the branch is still
  at `expected-branch-head`. Returns true on commit; false = somebody else
  landed first, and the caller reconciles and retries.

  Three facts, ONE transaction, because they are not independently useful. The
  branch's head advances; its `elements` are replaced by the thread's; the
  thread is settled `landed` so it is never handed back to its agent. A head
  that moved without its view following is not a partial success — it is a
  line that renders source its own journal disagrees with, which reads as a
  corrupt store rather than as an interrupted write.

  The copy DELETEs first. The branch's rows are its whole view, and a thread
  that rewrote a namespace the branch already had would otherwise leave the
  branch's older rows in place beside the newer ones — under a primary key
  that permits it, since `(line, ns, pos)` says nothing about which write a
  row came from.

  Nothing is appended and nothing is verified. A fast-forward land carries
  content that is byte-identical to what the caller just graded, so re-running
  the suite here would grade the same store twice; when the branch HAS moved,
  reconciling is the caller's job and it happens before this is called.

  The CAS is the same shape as `append!`'s and for the same reason — check and
  advance in one statement, `IS` rather than `=` so a branch with no writes yet
  matches on NULL."
  [conn thread-line-id branch-line-id expected-branch-head]
  (jdbc/with-transaction [tx conn]
    (let [now   (System/currentTimeMillis)
          head  (line-head tx thread-line-id)
          moved (:next.jdbc/update-count
                 (jdbc/execute-one!
                  tx ["UPDATE lines SET head = ?, used_at = ?
                       WHERE id = ? AND head IS ?"
                      head now branch-line-id expected-branch-head]))]
      (if-not (pos? (or moved 0))
        false
        (do (when (line-has-view? tx thread-line-id)
              ;; a rowless thread (fork on write, nothing written) has no view
              ;; to hand over — and copying an empty one would erase the branch's
              (drop-view! tx branch-line-id)
              (copy-view! tx thread-line-id branch-line-id))
            ;; and the thread's OWN view goes, for the reason `abandon-thread!`
            ;; gives: the `elements` rows are the space, a thread's view is a
            ;; full copy of its branch's, and they are pure derivation. The
            ;; branch holds the copy now, so nothing is lost that the journal
            ;; cannot recompute. This DELETE was missing: 663 landed threads
            ;; left 2,010,559 rows and 3.4 GB behind — 64% of the store file —
            ;; and `load-elements` slowed from 410 ms to over a second on the
            ;; B-tree bloat alone.
            (drop-view! tx thread-line-id)
            (jdbc/execute! tx ["UPDATE lines SET status = 'landed', used_at = ?
                                WHERE id = ?" now thread-line-id])
            true)))))

(defn- view-line!
  "The line whose rows ARE `line-id`'s view: itself when it holds rows, else
  — for an OPEN thread — its parent branch: a rowless open thread has
  written nothing, so its view is the branch's (fork on write). A landed or
  abandoned thread has no view by design and resolves to itself, empty;
  showing a settled line its branch's rows would make it read as live.
  Every reader of a view resolves through here, so an open thread that has
  not written cannot be shown an empty store."
  [conn line-id]
  (if (line-has-view? conn line-id)
    line-id
    (or (one-col (jdbc/execute-one!
                  conn ["SELECT parent FROM lines
                          WHERE id = ? AND kind = 'thread' AND status = 'open'" line-id]))
        line-id)))

^:reads (defn ^:export
  
  load-elements
  "The `:namespaces` map for ONE LINE, rebuilt from its materialized
  `elements` rows — its own, or its branch's while it is a rowless thread
  ([[view-line!]]: fork on write).

  Split out of `load-store` because it is the half a foreign write can
  invalidate ALONE: `elements` is the journal materialized, and a migration or
  repair that rewrites rows without appending a delta leaves the journal
  correct and this stale. Measured on slopp's own store (2678 rows): ~410 ms
  here against ~4 s for `load-store`, whose cost is parsing 23k delta
  payloads — none of which changed in that case.

  `line-id` is what lets many lines share one file: every line that has
  written keeps its own materialization, and a read that omitted the
  predicate would fold every agent's private work into one incoherent
  namespace map."
  [conn line-id]
  (update-vals
   (reduce (fn [m row]
             (update-in m [(symbol (:elements/ns row)) :elements]
                        (fnil conj []) (row->element row)))
           {}
           (jdbc/execute! conn ["SELECT * FROM elements WHERE line = ?
                                 ORDER BY ns, pos" (view-line! conn line-id)]))
   (fn [nsm] (update nsm :elements store/fold-comments))))

^:reads (defn ^:export
  
  elements-digest
  "A cheap CHANGE DETECTOR over ONE LINE's materialized `elements` rows —
  counts and sizes, deliberately NOT a checksum. A same-length substitution
  slips past it, and that is the accepted floor for something on the path of
  every foreign commit. Resolves through [[view-line!]]: a rowless thread's
  digest is its branch's, so the branch moving is what changes it.

  It exists because `data_version` answers a different question than anyone
  wants. SQLite moves it when ANY other connection commits, and in ordinary
  multi-server operation that is routinely something with no bearing on the
  code: a `git_map` pin from a projection, the trace map, the dep-surface
  cache, a saved remote. Measured on slopp's own store — ~3 ms here, ~410 ms
  to rebuild the namespaces, ~4 s for a full `load-store`. Reloading on every
  bump would make two idle servers re-read each other's bookkeeping forever.

  `line-id` is the same argument one level in. Once many lines share a file,
  an unscoped aggregate moves whenever ANY line is written, so every agent
  would rebuild its namespaces on every other agent's edit — the digest would
  answer `data_version`'s question again, having been built to stop doing so."
  [conn line-id]
  (jdbc/execute-one!
   conn ["SELECT COUNT(*) n, COUNT(DISTINCT ns) nss, SUM(pos) p,
                 SUM(LENGTH(source)) src, SUM(LENGTH(COALESCE(comment,''))) cmt
          FROM elements WHERE line = ?" (view-line! conn line-id)]))

^:reads (defn ^:export load-refs
  "ONE LINE's persisted reference index, in the shape the value carries:
  `{ns-sym {:key refs-key :rows [record …]}}`, rows in the order they were
  written. Only namespaces with an `refs_keys` row are present — a namespace
  written without an entry has none here either, and `slopp.index.refs`
  recomputes it on first read. Resolves through [[view-line!]], as the
  elements do: a rowless thread's index is its branch's.

  Exported for the git projection, which folds a journal into a store that
  has no index of its own and seeds it with this one, so a namespace the
  commit-point holds in its live state is arranged from a lookup rather than an
  analysis."
  [conn line-id]
  (let [line    (view-line! conn line-id)
        keys-of (into {}
                      (map (fn [r] [(symbol (:refs_keys/ns r)) (:refs_keys/refs_key r)]))
                      (jdbc/execute! conn ["SELECT ns, refs_key FROM refs_keys WHERE line = ?"
                                           line]))
        rows    (group-by :ns
                          (map (fn [r] {:ns (symbol (:form_refs/ns r))
                                        :row (edn/read-string (:form_refs/row r))})
                               (jdbc/execute! conn ["SELECT ns, row FROM form_refs
                                                     WHERE line = ? ORDER BY ns, seq"
                                                    line])))]
    (into {}
          (map (fn [[nsx k]] [nsx {:key k :rows (mapv :row (get rows nsx))}]))
          keys-of)))

^:reads (defn ^:export load-store
  "Reconstruct the full in-memory store from ONE LINE of the db, or nil if
  empty. Every registry meta row loads through ONE loop (default from :init
  unless :absent-nil?, :normalize applied — retired vocabulary canonicalizes
  here, so an old db stops re-minting it into fold state); only the bespoke
  element/delta/blob storage is hand-read.

  `line-id` selects BOTH halves: the materialization comes from that line's
  `elements` rows, and `:deltas` is that line's ANCESTRY rather than the file's
  journal. History is shared and a line is a POINTER into it, so the deltas are
  not copied — they are the ones reachable from this line's head, ordered by
  seq, which is a valid causal order because a parent is always inserted before
  its child.

  Scoping the journal is not tidiness. `try-commit!` takes its CAS head from
  the line's head, so a store value carrying another line's deltas yields a
  head that can never match again — a line nobody can write to.

  What `record-delta` keeps current on every append is rebuilt here BY INDEX,
  so a loaded value answers exactly what a written one would: `:head`,
  `:line-pos`, the author's `:prompts` per form the materialization holds, and
  `:last-write` per namespace. None of it folds the payloads — that fold is
  the cost this layer is shedding.

  There is deliberately no line-less arity. A default would answer for the
  trunk without saying so, which is the failure this whole layer exists to
  prevent; every caller resolves its line where a reader can see it.

  **No id counter is loaded.** \"Or nil if empty\" used to be decided by the
  presence of the `next-id` meta row, which was quietly doing two jobs: it
  carried the counter AND marked the store as having been persisted at all.
  Ids are random names now, so the counter is gone and the marker is stated
  directly — ANY meta row means `write-snapshot!` has run against this file,
  because it writes the whole field registry on every persist."
  [conn line-id]
  (when (seq (jdbc/execute! conn ["SELECT 1 FROM meta LIMIT 1"]))
    (let [nss  (load-elements conn line-id)
          fids (into [] (comp (mapcat :elements) (keep :id)) (vals nss))]
      (into
       {:namespaces nss
        ;; …and having named the columns, drop the one that is still huge: every
        ;; commit-point's `:files` snapshot but the newest. See thin-commit-manifests
        ;; — measured at 70.8 MB of 73.7 MB of commit payload on slopp's own store.
        ;; NO delta list. It was 94% of the value (111 MB of 118 on one store,
        ;; ~162 MB live) and 4.5 s of every 7.6 s open — EDN-parsing every
        ;; payload the line had ever written to carry a history the value
        ;; read for two scalars and a bounded window. Those are the keys
        ;; below; the views that walk the whole log read `line-deltas`
        ;; when asked.

        ;; NOT loaded at open. :blobs is a partial cache by design — file-content
        ;; documents the miss and the db fallback owns it, and put-blobs! is
        ;; INSERT OR IGNORE so an empty cache never prunes. Reading every blob's
        ;; bytes here cost a compiled JS bundle (~1.8MB) on every session open.
        :blobs      {}
        :head       (line-head conn line-id)
        :pending    []
        :head-at    (:at (head-delta conn line-id))
        :recent     (recent-window conn line-id)
        :line-pos   (line-length conn line-id)
        :prompts    (prompt-for-forms conn line-id fids
                                      :ignoring #{fields/auto-reorder-prompt})
        :last-write (last-write-per-ns conn line-id)
        ;; the reference graph, as it was persisted — never recomputed here
        :refs       (load-refs conn line-id)}
       (map (fn [{:keys [field meta-key init absent-nil? normalize]}]
              (let [raw (some-> (jdbc/execute-one!
                                 conn ["SELECT v FROM meta WHERE k = ?" meta-key])
                                :meta/v edn/read-string)
                    v   (if (and (nil? raw) (not absent-nil?)) init raw)]
                [field (if (and normalize (some? v)) (normalize v) v)])))
       (fields/meta-fields)))))

^:reads (defn ^:export load-store-with-history
          "`load-store` plus the line's whole journal as `:deltas` — the shape the
  merge needs for the side it is merging from, read at merge time. Nothing
  keeps a value like this: the merge's result is committed through
  `store/committed`, which drops the list again."
          [conn line-id]
          (some-> (load-store conn line-id)
                  (assoc :deltas (line-deltas conn line-id))))

(defn ^:export refork-thread!
  "Re-point a thread WITHOUT WORK at its branch's current head: base and head
  both. A thread that has written nothing has nothing to pin — a fresh
  fork would start from exactly here — so when the branch moves under it,
  following is the only state that is not stale. Bookkeeping deltas the
  thread wrote meanwhile (a verify from a spot-check, a done that landed
  nothing) stay in the journal, reachable by id, no longer from this line.

  A view the thread carries is DROPPED, not kept: a thread minted before
  fork on write holds a full copy of its branch as of the fork, and a copy
  with no write of its own in it is exactly the stale state this exists to
  leave behind. Kept, it was served as the branch after a restart and the
  live server ran two days behind. Rows are not a pin; work is, and the
  caller decides work (`unlanded-count` over its content ops)."
  [conn thread-line-id branch-line-id]
  (let [head (line-head conn branch-line-id)]
    (jdbc/with-transaction [tx conn]
      (drop-view! tx thread-line-id)
      (jdbc/execute! tx ["UPDATE lines SET base = ?, head = ?, used_at = ? WHERE id = ?"
                         head head (System/currentTimeMillis) thread-line-id]))
    head))

^:reads (defn ^:export line-base
  "The delta `line-id` FORKED from — its pin. Nil for a line that forked
  from nothing (a store's first line). `line-head` is where the line is;
  this is where it began, and the two differ by exactly the line's own
  deltas."
  [conn line-id]
  (one-col (jdbc/execute-one! conn ["SELECT base FROM lines WHERE id = ?" line-id])))

(defn ^:export adopt-thread!
  "The thread `agent` is working in on `branch-line-id`, minting one at the
  branch's HEAD when the agent has none open here.

  Adopt-or-create, keyed by (agent, branch), because that pair is what a
  private workspace IS. Two agents on one branch must not share a line — that
  is the isolation the model exists for — and one agent on two branches must
  not either, or switching branches would drag un-done work across with it.
  The key is also why nothing needs remembering between sessions: a returning
  agent asks the same question and gets the same row back.

  It forks at the branch's HEAD, not at the agent's last one, so a thread
  opened after the branch moved starts from what the branch says NOW. That
  point is then PINNED for the thread's whole life — the base never moves
  underneath it, which is what keeps its view stable and its verdict
  meaningful while work is in progress.

  Only an `open` row is adopted. A landed thread's writes are already on the
  branch and an abandoned one was discarded deliberately, so re-entering
  either would resurrect a line whose meaning is settled; the agent gets a
  fresh one instead.

  **The owner is RECORDED and deliberately NOT acted on.** `owner_pid` /
  `owner_started` say which process last adopted this line, and `thread_list`
  reports it as `:held`. Nothing here reads them, and a version that did was
  reverted on 2026-08-27 the day it shipped.

  It diverted a second LIVE process to a fresh line, to stop two processes
  resuming one conversation from sharing a thread. Two things were wrong with
  that. The case it defended is not happening — every live server on this
  store carries a distinct conversation id, and the duplicate pids that
  prompted it were a reconnect where the old process had not yet exited. And
  the case that happens on EVERY session pause is the plugin's Stop hook,
  which runs `done` through a one-shot process carrying the session's own
  agent id: a legitimate holder that is not the server, alive for up to its
  timeout. Diverting there minted a fresh line and left ten changes stranded
  on the old one, silently, because every write had already reported success.

  The trade was one-sided and pointed the wrong way. Sharing a line costs
  CONTENTION, which per-line CAS already arbitrates and which this store
  survived for its whole life. Abandoning one costs WORK, and the agent finds
  out at a `done` that lands nothing. Keeping the columns costs nothing and
  `:held` is what made the incident diagnosable at all — so the record stays
  and the behaviour goes.

  Adoption TOUCHES `used_at`. A thread being worked in is current whether or
  not this session has written to it yet, and `used_at` is the only thing
  that can say so — a thread that only ever moved on writes would look idle
  for exactly as long as someone was reading in it.

  The 4-arity takes the owner explicitly so a test can be a second process
  without spawning a JVM; the 3-arity — every caller in the store — means
  \"this process\"."
  ([conn branch-line-id agent]
   (adopt-thread! conn branch-line-id agent (this-process)))
  ([conn branch-line-id agent owner]
   (let [id   (one-col (jdbc/execute-one!
                        conn ["SELECT id FROM lines
                                 WHERE kind = 'thread' AND parent = ? AND agent = ?
                                   AND status = 'open'
                               ORDER BY used_at DESC LIMIT 1"
                              branch-line-id agent]))
         mine (if id
                (do (jdbc/execute!
                     conn ["UPDATE lines SET used_at = ? WHERE id = ?"
                           (System/currentTimeMillis) id])
                    ;; a ROWLESS thread has nothing to pin (fork on write): if
                    ;; the branch moved since it was minted, it re-forks here,
                    ;; where a fresh one would start
                    (when (and (not (line-has-view? conn id))
                               ;; nothing of its own at all — not even a turn marker
                               ;; (a thread with markers is the engine's to re-fork,
                               ;; carrying its open turn across)
                               (= (line-head conn id) (line-base conn id))
                               (not= (line-base conn id) (line-head conn branch-line-id)))
                      (refork-thread! conn id branch-line-id))
                    id)
                (create-line! conn {:kind   "thread"
                                    :base   (line-head conn branch-line-id)
                                    :parent branch-line-id
                                    :agent  agent}))]
     ;; best-effort: a store whose `open!` has not run since the columns
     ;; shipped has nowhere to write this, and failing to RECORD a lease must
     ;; never fail the adoption — the record is a diagnostic, not a gate.
     (try (jdbc/execute! conn ["UPDATE lines SET owner_pid = ?, owner_started = ? WHERE id = ?"
                               (:pid owner) (:started owner) mine])
          (catch java.sql.SQLException _ nil))
     mine)))
