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
            [rewrite-clj.node :as n] [slopp.store.fields :as fields] [slopp.store :as store]))

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

(defn- one-col
  "The single selected column of a single-row query, whatever next.jdbc
  qualified it with. A CTE's columns are not qualified the way a table's are,
  and guessing wrong produces the same value as no rows at all — which is one
  debugging round already spent."
  [row]
  (when row (val (first row))))

(defn ^:export create-line!
  "Mint a line and return its id.

  `:kind` is \"branch\" or \"thread\" — a thread is the anonymous case and has
  no `:nm`. `:base` is the delta it SPLITS FROM, and the head starts there: a
  line that has written nothing sits exactly where its base sat, with nothing
  copied out of the JOURNAL. That is the whole economy of the model — a split
  costs a row.

  `:parent` is the parent LINE's id (a thread's branch), not a delta, and it
  is where the new line inherits its VIEW from. `elements` is materialized per
  line, so the fork copies the parent's rows in one INSERT … SELECT — ~2,481
  form rows here against 23,560 deltas to fold for the same answer. Pinning is
  what keeps the copy valid for the line's whole life: the base never moves
  under it, so the view can never go stale beneath its own writes.

  The copy follows `:parent` rather than `:base` because a delta cannot say
  whose view of it to duplicate. A line with no parent starts EMPTY — correct
  for a store's first line, wrong for a fork, so a fork must name its parent.

  The row lands BEFORE the copy on purpose: interrupted between them leaves a
  line with an empty view, which reads as unwritten. The other order would
  leave rows belonging to a line that does not exist."
  [conn {:keys [kind base parent agent] nm :name}]
  (let [id  (str (java.util.UUID/randomUUID))
        now (System/currentTimeMillis)]
    (jdbc/execute! conn ["INSERT INTO lines
                            (id,name,kind,head,base,parent,agent,created_at,used_at,status)
                          VALUES (?,?,?,?,?,?,?,?,?,'open')"
                         id nm (or kind "thread") base base parent agent now now])
    (when parent
      (jdbc/execute! conn ["INSERT INTO elements
                              (line,ns,pos,kind,form_id,name,source,comment)
                            SELECT ?, ns, pos, kind, form_id, name, source, comment
                            FROM elements WHERE line = ?" id parent]))
    id))

(defn ^:export delete-line!
  "Drop a line: its row and its materialized `elements` rows. Returns true if
  a line was there to drop.

  **The DELTAS stay.** They become unreachable from any head, which is what
  history means here — a line is a pointer, and dropping the pointer is not
  the same as claiming the work never happened. It also makes dropping cheap
  and safe to do casually, which is the whole point of an anonymous line that
  gets abandoned when an agent stops."
  [conn line-id]
  (jdbc/execute! conn ["DELETE FROM elements WHERE line = ?" line-id])
  (pos? (or (:next.jdbc/update-count
             (jdbc/execute-one! conn ["DELETE FROM lines WHERE id = ?" line-id]))
            0)))

^:reads (defn ^:export trunk-line-id!
  "The trunk line's id, minting the row when a store predates the lines table.

  A store written before lines existed has all its history and no line naming
  it, so the row is created with its base at the CURRENT journal head — the
  whole log is behind the trunk immediately, with nothing moved or copied.

  The bang is the mint; reading an existing store's trunk is a plain read."
  [conn]
  (or (one-col (jdbc/execute-one! conn ["SELECT id FROM lines WHERE name = 'main'"]))
      (create-line! conn
                    {:name "main" :kind "branch"
                     :base (one-col (jdbc/execute-one!
                                     conn ["SELECT id FROM deltas ORDER BY seq DESC LIMIT 1"]))})))

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

(defn ^:export open!
  "Open (creating if needed) the store db under `dir`; returns the connection.

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
         (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS meta (
                              k TEXT PRIMARY KEY, v TEXT NOT NULL)"])
         (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS deltas (
                              seq     INTEGER PRIMARY KEY AUTOINCREMENT,
                              id      TEXT UNIQUE NOT NULL,
                              op      TEXT NOT NULL,
                              ns      TEXT NOT NULL,
                              payload TEXT NOT NULL)"])
         ;; a `tree` column used to hold each milestone's byte-exact snapshot of
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
         ;; throw IS the no-op (same idiom as elements.comment above).
         (try (jdbc/execute! conn ["ALTER TABLE deltas ADD COLUMN parent TEXT"])
              (catch java.sql.SQLException _ nil))
         (jdbc/execute! conn ["CREATE INDEX IF NOT EXISTS deltas_parent ON deltas(parent)"])
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
         (jdbc/execute! conn [elements-ddl])
         ;; a form OWNS the comment rendered above it (whitespace-is-rendering).
         ;; Same story as `tree` above: SQLite has no ADD COLUMN IF NOT EXISTS,
         ;; so adding it to an existing store throws and that is the no-op.
         (try (jdbc/execute! conn ["ALTER TABLE elements ADD COLUMN comment TEXT"])
     (catch java.sql.SQLException _ nil))
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
         conn)))))

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
               :name (some-> (:elements/name row) symbol) :node node}
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

^:reads (defn ^:export
  ^{:breaking-ok
    (str "the 1-arity is REMOVED rather than defaulted: an unscoped digest "
         "moves whenever ANY line is written, so it would answer data_version's "
         "question again — the very thing it was built to stop doing. Its one "
         "call site moved in the SAME coordinated write, and nothing outside "
         "slopp calls the storage layer directly.")}
  elements-digest
  "A cheap CHANGE DETECTOR over ONE LINE's materialized `elements` rows —
  counts and sizes, deliberately NOT a checksum. A same-length substitution
  slips past it, and that is the accepted floor for something on the path of
  every foreign commit.

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
          FROM elements WHERE line = ?" line-id]))

^:reads (defn ^:export
  ^{:breaking-ok
    (str "the 1-arity is REMOVED rather than defaulted: a read that does not "
         "name its line silently answers for the trunk, which is how an agent "
         "would be shown the branch instead of its own thread and never know. "
         "Both call sites moved in the SAME coordinated write, and nothing "
         "outside slopp calls the storage layer directly.")}
  load-elements
  "The `:namespaces` map for ONE LINE, rebuilt from its materialized
  `elements` rows.

  Split out of `load-store` because it is the half a foreign write can
  invalidate ALONE: `elements` is the journal materialized, and a migration or
  repair that rewrites rows without appending a delta leaves the journal
  correct and this stale. Measured on slopp's own store (2678 rows): ~410 ms
  here against ~4 s for `load-store`, whose cost is parsing 23k delta
  payloads — none of which changed in that case.

  `line-id` is what lets many lines share one file: every open line keeps its
  own materialization, and a read that omitted the predicate would fold every
  agent's private work into one incoherent namespace map."
  [conn line-id]
  (update-vals
   (reduce (fn [m row]
             (update-in m [(symbol (:elements/ns row)) :elements]
                        (fnil conj []) (row->element row)))
           {}
           (jdbc/execute! conn ["SELECT * FROM elements WHERE line = ?
                                 ORDER BY ns, pos" line-id]))
   (fn [nsm] (update nsm :elements store/fold-comments))))

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
  `(last (store/deltas base))`, so a store value carrying another line's
  deltas yields a head that can never match again — a line nobody can write to.

  There is deliberately no line-less arity. A default would answer for the
  trunk without saying so, which is the failure this whole layer exists to
  prevent; every caller resolves its line where a reader can see it."
  [conn line-id]
  (when-let [next-id (some-> (jdbc/execute-one!
                              conn ["SELECT v FROM meta WHERE k = 'next-id'"])
                             :meta/v Long/parseLong)]
    (into
     {:namespaces (load-elements conn line-id)
      :deltas     (mapv row->delta
                        ;; EXPLICIT columns, not SELECT * — an older store still has a dead
                        ;; `tree` column holding ~1.35MB per :commit marker, and naming the
                        ;; columns is what keeps it from being fetched and parsed at every open.
                        (jdbc/execute! conn
                                       [(str ancestry-cte
                                             " SELECT id, op, ns, payload FROM deltas
                                                WHERE id IN (SELECT id FROM anc)
                                                ORDER BY seq")
                                        (line-head conn line-id)]))
      :next-id    next-id
      
      ;; NOT loaded at open. :blobs is a partial cache by design — file-content
      ;; documents the miss and the db fallback owns it, and put-blobs! is
      ;; INSERT OR IGNORE so an empty cache never prunes. Reading every blob's
      ;; bytes here cost a compiled JS bundle (~1.8MB) on every session open.
      :blobs      {}}
     (map (fn [{:keys [field meta-key init absent-nil? normalize]}]
            (let [raw (some-> (jdbc/execute-one!
                               conn ["SELECT v FROM meta WHERE k = ?" meta-key])
                              :meta/v edn/read-string)
                  v   (if (and (nil? raw) (not absent-nil?)) init raw)]
              [field (if (and normalize (some? v)) (normalize v) v)])))
     (fields/meta-fields))))

^:reads (defn ^:export get-meta
  "Read a meta row's value (nil when absent) — the k/v side-table for
  config the journal doesn't track (e.g. `git-remote`, `git-base-sha`)."
  [conn k]
  (:meta/v (jdbc/execute-one! conn ["SELECT v FROM meta WHERE k = ?" k])))

^:reads (defn ^:export next-id-floor
  "The id counter the FILE has reached, or nil for a store with no history.

  Ids are minted from the store VALUE (`store/gen-id` counts `:next-id`), and
  `deltas.id` is UNIQUE across the whole journal — so the counter is a
  property of the FILE while the value holding it belongs to one line. Any
  value that stopped counting re-mints ids another line has already used, and
  that is not a lost race: it throws.

  This was unreachable while a branch was a separate db file, because two
  lines could not share a UNIQUE index. It became reachable the moment they
  shared a journal, and the protection that used to cover the equivalent case
  — two SERVERS on one line, serialized by the write CAS — is exactly what
  per-line CAS deliberately removed between lines."
  [conn]
  (some-> (get-meta conn "next-id") Long/parseLong))

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

(defn- write-snapshot!
  "The shared tail of persist!/append!: ONE LINE's element rows for the touched
  namespaces, the id counter, every registry meta row, and the blob table —
  ONE loop over slopp.store.fields/meta-fields, so a new fold-field persists by
  registration instead of by editing two near-identical transactions (the
  copy-paste this replaces silently lost any field a hand missed in ONE of
  them — surviving tests, vanishing on the live server's restart).

  `line-id` scopes both statements, and the DELETE is the one that matters.
  `elements` holds every open line's view of the store at once, so a delete
  naming only the namespace is not a wrong answer — it erases another agent's
  work, and what is left looks exactly like a write that never happened."
  [tx store nses line-id]
  (doseq [ns-sym nses]
    ;; delete ALWAYS: a ns absent from the store (renamed away) must have
    ;; its rows purged, not linger for the next reopen
    (jdbc/execute! tx ["DELETE FROM elements WHERE line = ? AND ns = ?"
                       line-id (str ns-sym)])
    (doseq [[pos e] (map-indexed vector
                                 (get-in store [:namespaces ns-sym :elements]))]
      (jdbc/execute! tx ["INSERT INTO elements (line,ns,pos,kind,form_id,name,source,comment)
                          VALUES (?,?,?,?,?,?,?,?)"
                         line-id (str ns-sym) pos (name (:kind e)) (:id e)
                         (some-> (:name e) str) (n/string (:node e))
                         (:comment e)])))
  (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('next-id', ?)
                      ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                     (str (:next-id store))])
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

(defn- row->line
  "A `lines` row as a value. next.jdbc qualifies plain columns by their table,
  so the keys are normalized once here instead of at every reader."
  [row]
  (let [r (into {} (map (fn [[k v]] [(keyword (name k)) v])) row)]
    {:id         (:id r)
     :name       (:name r)
     :kind       (:kind r)
     :head       (:head r)
     :base       (:base r)
     :parent     (:parent r)
     :agent      (:agent r)
     :created-at (:created_at r)
     :used-at    (:used_at r)
     :status     (:status r)}))

^:reads (defn ^:export lines
  "Every line in this store, most-recently-used first.

  A LINE is a pointer to a head delta. A named line is a BRANCH; an anonymous
  one (name NULL) is an agent's THREAD. One row shape, because they are one
  thing — a thread is a branch nobody named, and giving them separate tables
  would mean every question about history had to be asked twice."
  [conn]
  (mapv row->line
        (jdbc/execute! conn ["SELECT * FROM lines ORDER BY used_at DESC"])))

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

  Adoption TOUCHES `used_at`. A thread being worked in is current whether or
  not this session has written to it yet, and `used_at` is the only thing
  that can say so — a thread that only ever moved on writes would look idle
  for exactly as long as someone was reading in it."
  [conn branch-line-id agent]
  (if-let [id (one-col (jdbc/execute-one!
                        conn ["SELECT id FROM lines
                                 WHERE kind = 'thread' AND parent = ? AND agent = ?
                                   AND status = 'open'
                               ORDER BY used_at DESC LIMIT 1"
                              branch-line-id agent]))]
    (do (jdbc/execute! conn ["UPDATE lines SET used_at = ? WHERE id = ?"
                             (System/currentTimeMillis) id])
        id)
    (create-line! conn {:kind   "thread"
                        :base   (line-head conn branch-line-id)
                        :parent branch-line-id
                        :agent  agent})))

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
  main; the caller resolves the trunk where a reader can see it."
  [conn store new-deltas nses line-id expected-head]
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
          (jdbc/execute! tx ["INSERT INTO deltas (id, op, ns, parent, payload)
                              VALUES (?,?,?,?,?)"
                             (:id d) (name (:op d)) (str (:ns d)) (:parent d)
                             (pr-str (dissoc d :id :op :ns))]))
        (write-snapshot! tx store nses line-id)
        true))
    (catch clojure.lang.ExceptionInfo e
      (if (::head-moved (ex-data e)) false (throw e)))
    ;; ONLY a writer collision is a retryable lost race. Any other SQL fault
    ;; must SURFACE: swallowing it returned false, the caller retried, and the
    ;; agent was told "commit contention" for what was really a bad statement.
    (catch java.sql.SQLException e
      (if (or (writer-collision? e) (duplicate-delta-id? e)) false (throw e)))))

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

^:reads (defn ^:export journal-stats
          "What the store CARRIES, in bytes: the journal (per op, heaviest
  first), the materialized state, and the blob table. A pure read straight off
  SQLite's LENGTH — nothing is parsed, so it stays cheap on a large journal.

  This exists because nothing measured cost. A byte-exact `:tree` snapshot in
  every `:commit` reached 94% of a 344MB journal — ~1.35MB per milestone
  against a design note estimating \"tens of KB\" — and went unnoticed across
  239 milestones while `full_check` happily counted namespaces and tests. It
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
                bl   (jdbc/execute-one!
                      conn ["SELECT COUNT(*) AS n, SUM(LENGTH(bytes)) AS b FROM blobs"])]
            {:deltas   {:n (reduce + 0 (map :n by-op))
                        :payload-bytes (reduce + 0 (map :payload-bytes by-op))
                        :by-op by-op}
             :elements {:n (or (:n els) 0) :source-bytes (or (:b els) 0)}
             :blobs    {:n (or (:n bl) 0) :bytes (or (:b bl) 0)}}))
