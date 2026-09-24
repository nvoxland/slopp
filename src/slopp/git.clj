(ns slopp.git
  "P4-m8: the git compatibility layer. Two faces over one bare JGit
  repo persisted at `.slopp/git-cache` (`store.db` is the source of truth
  and the git repo a REBUILDABLE CACHE — delete it and it is regenerated;
  it persists so a publish mints only what the journal gained, D2/s20):

  - PROJECTION: the journal's :commit commit-points generated as git objects.
    Serving these to a git client over local smart-HTTP was removed — it
    forced exact-project handling for less than it bought — so the
    projection now exists to be PUSHED rather than browsed in place.
  - CLIENT: the same projection pushed to a NORMAL external remote (GitHub
    etc.) — the remote holds real .clj files; fetch reads a remote's tip and
    tree back (the clone/pull side lives in `slopp.sync`). A cloned store
    records `git-base-sha`, and the projection GRAFTS onto it so local
    commit-points extend the remote's history — pushes stay fast-forward.

  Ids: a git commit id IS the hash of its bytes, so stability comes from
  DETERMINISM — each commit is a pure function of its marker delta (:agent,
  :at, :description), its parent, and the tree DERIVED by folding the journal
  up to that marker. `git_map` (main store.db)
  pins delta→sha at first projection: query surfaces read it, and it lets
  re-projection skip a commit whose object is already live in the repo.

  Ordering: journal marker → git objects (content-addressed, idempotent) →
  git_map row (INSERT OR IGNORE + read-back) → ref update (CAS);
  `ensure-projected!` rebuilds the whole thing from the journal on demand."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [slopp.build :as build]
            [slopp.store.db :as db]
            [slopp.store.render :as store.render] [slopp.store :as store] [slopp.index.refs :as refs] [slopp.store.fields :as fields] [slopp.store.artifacts :as artifacts])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant ZoneOffset]
           [org.eclipse.jgit.dircache DirCache DirCacheEntry]
           [org.eclipse.jgit.internal.storage.dfs DfsRepositoryDescription
            InMemoryRepository$Builder]
           [org.eclipse.jgit.storage.file FileRepositoryBuilder]
           [org.eclipse.jgit.lib CommitBuilder Constants FileMode
            ObjectId ObjectInserter PersonIdent Repository]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.revwalk.filter RevFilter]
           [org.eclipse.jgit.treewalk TreeWalk]
           [org.eclipse.jgit.util FS]))

;; ---------------------------------------------------------------------------
;; repo + mapping table
(defn ^:live-handle open-repo!
  "The projection repo. With a store `dir`: a bare JGit `FileRepository` at
  `<dir>/.slopp/git-cache`, created on first use and PERSISTED on purpose
  (D2, s20) — it was an `InMemoryRepository`, and since `publish-local!`
  opens a fresh context per publish, every publish re-rendered and
  re-inserted the tree of every commit point in the journal: the pinned-sha
  short-circuit in `ensure-projected!` can never fire on an object database
  born empty a moment ago. Measured at 98% of a commit point's wall (189s,
  then 210s a day later). On disk, `.has` is true for everything already
  minted, and a publish renders only what the journal gained.

  With NO dir (nil): an in-memory SCRATCH repo — `clone!` fetches a remote
  into one before any store exists, and there is nothing to persist.

  Still a CACHE either way: `store.db` is the source of truth, shas are
  deterministic, and deleting the directory regenerates byte-identical
  objects. Both are built with a real FS handle because `TransportLocal`
  resolves file-path remotes through the LOCAL repo's FS (a DFS repo has
  none by default — NPE without it)."
  ^Repository [dir]
  (if (clojure.string/blank? (str dir))
    (let [repo (.. (InMemoryRepository$Builder.)
                   (setRepositoryDescription (DfsRepositoryDescription. "slopp"))
                   (setFS FS/DETECTED)
                   (build))]
      (-> repo (.updateRef Constants/HEAD) (.link "refs/heads/main"))
      repo)
    (let [git-dir (java.io.File. (java.io.File. (str dir) ".slopp") "git-cache")
          fresh?  (not (.exists (java.io.File. git-dir "HEAD")))
          repo    (.. (FileRepositoryBuilder.)
                      (setGitDir git-dir)
                      (setBare)
                      (setFS FS/DETECTED)
                      (build))]
      (when fresh?
        (.create repo true)
        (-> repo (.updateRef Constants/HEAD) (.link "refs/heads/main")))
      repo)))

(defn ensure-map!
  "Create the git_map pinning table (delta↔sha) if absent; returns conn.
  Keyed (delta_id, fingerprint): branch journals share main's prefix by
  VALUE, so a shared marker resolves to one row with no fork-point math;
  colliding post-fork ids disambiguate by fingerprint. `line` is informative."
  [conn]
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS git_map (
                          delta_id    TEXT NOT NULL,
                          fingerprint TEXT NOT NULL,
                          sha         TEXT NOT NULL,
                          line        TEXT,
                          PRIMARY KEY (delta_id, fingerprint))"])
  conn)

(defn ^:live-handle open-ctx!
  "The projection context over a slopp store dir: bare repo handle, git_map
  connection (main store.db), and the per-process projection lock."
  [dir]
  {:slopp.git/dir      (str dir)
   :slopp.git/repo     (open-repo! dir)
   :slopp.git/map-conn (ensure-map! (db/open! dir))
   :slopp.git/lock     (Object.)})

(defn close-ctx!
  "Close a git context's JGit repo and its git_map connection, and return
  nil. The repo is a rebuildable CACHE of the journal's commit-points — the
  store is the source of truth — so closing one loses nothing; it persists
  at `.slopp/git-cache` between contexts, and `ensure-projected!` rebuilds
  whatever is missing on demand.

  `ctx` is an OPAQUE handle from `open-ctx!`: it carries a live JGit
  `Repository`, a JDBC `Connection` and a lock, so no caller builds one and no
  schema can usefully describe one. Destructuring it in the arglist would
  advertise a shape callers must not depend on — the same shape-divergence
  that broke live REPL handles."
  [ctx]
  (let [^Repository repo             (:slopp.git/repo ctx)
        ^java.sql.Connection map-conn (:slopp.git/map-conn ctx)]
    (.close repo)
    (.close map-conn)
    nil))

^:reads (defn- lookup-sha [conn delta-id fp]
  (:git_map/sha (jdbc/execute-one!
                 conn ["SELECT sha FROM git_map
                        WHERE delta_id = ? AND fingerprint = ?" delta-id fp])))

(defn- record-sha!
  "Pin delta→sha for the projection AS IT CURRENTLY MINTS. Last writer wins.

  This was first-writer-wins, on the argument that determinism makes re-writes
  ties. They are ties — right up until a projection DIVERGES, and then
  `INSERT OR IGNORE` preserves precisely the wrong value, because the
  correcting one always arrives second. Measured 2026-08-14: a refused push
  left the store pinning a sha for a commit that exists nowhere, and no later
  projection could replace it. Determinism is the reason the change is safe,
  not a reason it was unnecessary.

  A pin is a record of what this projection produces, which is refreshable by
  definition. It is NOT evidence that the commit was ever published, and
  nothing may read it as such — `sync/alignment` asks the branch head's own
  `Slopp-Commit:` stamp instead."
  [conn delta-id fp sha line]
  (jdbc/execute! conn ["INSERT INTO git_map (delta_id, fingerprint, sha, line)
                        VALUES (?,?,?,?)
                        ON CONFLICT(delta_id, fingerprint)
                          DO UPDATE SET sha = excluded.sha, line = excluded.line"
                       delta-id fp sha line])
  (lookup-sha conn delta-id fp))

;; ---------------------------------------------------------------------------
;; trees
(defn- commit-paths
  "{path content} for a commit-point's tree: the rendered namespaces at the paths
  the CALLER resolved (`render/source-path` over the store as it stood — so
  production under `src/`, tests under `test/`, instruments under
  `instruments/`, cljs under `cljs-src/`, same layout as build!), the
  generated deps.edn, every non-code file from the `files` manifest — BINARY
  entries ({:sha …}) resolved to real bytes via `blob-of` (sha → bytes; a
  missing blob projects its entry EDN, visible rather than silent) — and every
  structured CONFIG entry rendered to its format (they all ride EVERY projected
  tree, so a slopp push never deletes them).

  The layout matching build! is load-bearing rather than tidy: build.clj's CI
  flow is `build/uber {:src \"src\"} (what `slopp build --jar` and the release lane run)` against a CHECKOUT of the published
  repo, so a projection that roots a namespace differently produces a different
  jar from the same store.

  deps.edn's `test?` and `instruments?` are read off the TREE, not off the
  store, so the file cannot describe a layout other than the one it ships
  beside."
  [path-map deps files configs blob-of]
  (let [under? (fn [& prefixes]
                 (boolean (some (fn [p] (some #(str/starts-with? p %) prefixes))
                                (keys path-map))))]
    (into (sorted-map)
          (concat [["deps.edn" (build/deps-edn false deps
                                               (under? "test/" "cljs-test/")
                                               false {}
                                               (under? "instruments/"))]]
                  path-map
                  (map (fn [[p entry]]
                         [p (if (map? entry)
                              (or (blob-of (:sha entry)) (pr-str entry))
                              entry)])
                       files)
                  ;; every config entry EXCEPT the locally-declared ones. The
                  ;; rest ride every projected tree so a push never deletes
                  ;; them — `dev` included, since it is the project's shared
                  ;; dev setup and a clone needs it; `dev.local` must not ride
                  ;; any, because it is one developer's override on one
                  ;; machine, and projecting it pushes that port at everyone
                  ;; who pulls.
                  (map (fn [[p entry]] [p (store/render-config entry)])
                       (remove (comp store/local-config-paths key) configs))))))

;; ---------------------------------------------------------------------------
;; commits + refs
(defn- author-email ^String [agent]
  (let [s (str/replace (str agent) #"[^A-Za-z0-9._-]" ".")]
    (str (if (str/blank? s) "slopp" s) "@slopp")))

(defn- insert-tree!
  "Blobs + git tree for a {path content} map (content: string or BYTES —
  binary assets project as real bytes); returns the tree ObjectId."
  [^ObjectInserter ins paths]
  (let [dc (DirCache/newInCore)
        b  (.builder dc)]
    (doseq [[^String path content] paths]
      (let [^bytes bs (if (bytes? content)
                        content
                        (.getBytes ^String content StandardCharsets/UTF_8))
            blob (.insert ins Constants/OBJ_BLOB bs)]
        (.add b (doto (DirCacheEntry. path)
                  (.setFileMode FileMode/REGULAR_FILE)
                  (.setObjectId blob)))))
    (.finish b)
    (.writeTree dc ins)))

(defn- set-branch-ref!
  "Point refs/heads/<nm> at `sha` (CAS; the journal is authoritative, so a
  lost race is retried against the moved ref — convergence, not failure).

  A LOCK_FAILURE is another projector holding the ref's lock file: since the
  projection repo lives on disk, two contexts (two processes, or one test's
  two futures) share one repo and meet here. Three immediate retries all met
  the same held lock; the retry now WAITS, longer each time, and gives the
  other side the milliseconds a ref write takes."
  [^Repository repo nm sha]
  (let [ref-name (str "refs/heads/" nm)
        new-id   (ObjectId/fromString sha)]
    (loop [n 0]
      (let [cur (.resolve repo ref-name)]
        (when-not (= cur new-id)
          (let [ru  (doto (.updateRef repo ref-name)
                      (.setExpectedOldObjectId (or cur (ObjectId/zeroId)))
                      (.setNewObjectId new-id)
                      (.setForceUpdate true))
                res (.name (.update ru))]
            (cond
              (#{"NEW" "FORCED" "FAST_FORWARD" "NO_CHANGE"} res) nil
              (and (= "LOCK_FAILURE" res) (< n 10))
              (do (Thread/sleep (long (* 25 (inc n))))
                  (recur (inc n)))
              :else (throw (ex-info (str "git ref update failed: " res)
                                    {:ref ref-name :result res})))))))))

(defn- branch-journals
  "[[name line-id]] for every NAMED line other than main — the branches a
  projection advertises.

  This used to list `.slopp/branches/` and open each branch's own store.db.
  A branch is a row in the ONE journal now, so this reads the connection the
  caller already holds: no directory to scan, nothing to open, and a branch
  another process created is projected without either of them touching a
  file."
  [conn]
  (for [l (db/lines conn)
        :when (and (:name l) (not= "main" (:name l)))]
    [(:name l) (:id l)]))

;; ---------------------------------------------------------------------------
;; import: git push → slopp (M3)
;;
;; The net content change lands as ingests (new files) + ONE verified edit
;; group; each incoming commit is preserved as a :commit marker carrying its
;; original sha. Conservative by design — git is a guest writer, and guests
;; don't get the ambiguous cases (anonymous forms, ns-decl edits, deletions
;; of whole files): those reject with the reason on the pusher's terminal.

;; ---------------------------------------------------------------------------
;; smart-HTTP server (M2: clone/fetch; M3 adds receive-pack)
;;
;; The protocol endpoints, verbatim from the smart-http spec:
;;   GET  /slopp.git/info/refs?service=git-upload-pack   → refs advertisement
;;   POST /slopp.git/git-upload-pack                     → pack negotiation
;; JGit's UploadPack owns the wire format (setBiDirectionalPipe false =
;; stateless RPC); we only route bytes. v0 protocol — the Git-Protocol:
;; version=2 header is deliberately ignored (spec-legal fallback).
^:reads (defn tree-at
  "{path text} for the whole tree of commit `sha` — UTF-8 blobs, sorted.
  Works on any repo handle (the in-memory projection or an on-disk remote)."
  [^Repository repo sha]
  (with-open [rw (RevWalk. repo)]
    (let [tree (.getTree (.parseCommit rw (ObjectId/fromString sha)))]
      (with-open [tw (TreeWalk. repo)]
        (.addTree tw tree)
        (.setRecursive tw true)
        (loop [m (sorted-map)]
          (if (.next tw)
            (recur (assoc m (.getPathString tw)
                          (String. (.getBytes (.open repo (.getObjectId tw 0)))
                                   StandardCharsets/UTF_8)))
            m))))))

^:reads (defn merge-base
  "The merge base of two commits in `repo`, or nil when the histories are
  unrelated — standard git ancestry (pull uses it to isolate remote-only
  changes: diff merge-base→remote-tip, never touching local-only work)."
  [^Repository repo sha-a sha-b]
  (with-open [rw (RevWalk. repo)]
    (.setRevFilter rw RevFilter/MERGE_BASE)
    (.markStart rw (.parseCommit rw (ObjectId/fromString sha-a)))
    (.markStart rw (.parseCommit rw (ObjectId/fromString sha-b)))
    (some-> (.next rw) (.name))))

(defn commit-author
  "The projected commit's author identity for marker `d`: the `:author`
  captured at commit-point time ({:name :email} — G5 config), else the legacy
  agent-based identity, so pre-G5 markers re-mint byte-identically."
  [d]
  (or (:author d)
      {:name  (str (or (:agent d) "slopp"))
       :email (author-email (:agent d))}))

(defn stamped-commit-point
  "The commit-point id a projected commit MESSAGE stamps itself with — the
  `Slopp-Commit:` trailer `commit-message` writes — or nil for a commit this
  projection did not mint (an ADOPTED remote commit, from a pull, carries no
  trailer).

  One producer, one reader, deliberately adjacent. It lets a caller ask the
  COMMIT which commit-point it is rather than trust a sha recorded when the commit
  was minted, and those are different facts: minting happens whether or not the
  push that follows it succeeds. On 2026-08-14 a refused push left a pinned sha
  naming a commit nobody had, and because the pin is first-writer-wins no later
  projection could correct it. A stamp rides the artifact, so it cannot
  disagree with the artifact."
  [message]
  (when message
    (second (re-find #"(?m)^Slopp-Commit:[ \t]*(\S+)[ \t]*$" message))))

^:reads (defn message-of
  "The full message of commit `sha` in `repo`, or nil when the repo does not
  have that object — which an in-memory projection routinely does not, since
  it mints its own chain and fetches nothing it was not asked to.

  nil is a real answer here rather than an error: every caller is asking a
  commit to describe itself, and \"the object is not here\" is one of the
  outcomes they have to handle."
  [^Repository repo sha]
  (when (and repo sha)
    (let [id (ObjectId/fromString sha)]
      (when (.has (.getObjectDatabase repo) id)
        (with-open [rw (RevWalk. repo)]
          (.getFullMessage (.parseCommit rw id)))))))

(defn- ancestor?
  "Is `sha-a` reachable from `sha-b` in `repo`? Both objects must be present —
  ask `message-of` first when that is in doubt."
  [^Repository repo sha-a sha-b]
  (with-open [rw (RevWalk. repo)]
    (.isMergedInto rw
                   (.parseCommit rw (ObjectId/fromString sha-a))
                   (.parseCommit rw (ObjectId/fromString sha-b)))))

(defn- commits-past
  "How many commits `from` reaches that `base` does not, in `repo`. A nil
  `base` counts the whole reachable history — the honest answer when two
  chains share nothing."
  [^Repository repo from base]
  (with-open [rw (RevWalk. repo)]
    (.markStart rw (.parseCommit rw (ObjectId/fromString from)))
    (when base
      (.markUninteresting rw (.parseCommit rw (ObjectId/fromString base))))
    (loop [n 0] (if (.next rw) (recur (inc n)) n))))

^:reads (defn divergence
  "Why a fast-forward push was refused, as a VALUE with declared clauses
  rather than a status string.

  A refused push answers `REJECTED_NONFASTFORWARD` and nothing else, and the
  objects that would explain it live in an in-memory projection that dies with
  the process. Deciding whether ONE such refusal was benign has cost folding
  the journal to a commit-point, re-rendering every path, re-minting the commit
  and pushing to a scratch repo to reproduce the conditions — for an answer
  that was one fact. This is that fact, computed where the refusal happens:

      {:projected {:sha … :commit-point …}
       :mirror    {:sha … :commit-point …}
       :base … :ahead n :behind n :contains-mirror-tip? bool :cause …}

  `:cause` is the clause that separates the two stories one status cannot:

  | cause | what happened | remedy |
  |---|---|---|
  | `:mirror-ahead` | the destination builds ON this projection — someone else wrote the ref, or this store is behind | pull, or re-project |
  | `:remint` | both tips stamp the SAME commit-point and are different commits: one journal, two mints | the projection is not reproducing itself — investigate before resetting |
  | `:diverged` | a common base, neither side contains the other | decide which history wins |
  | `:unrelated` | no common base at all | the destination is a different project's history |
  | `:no-divergence` | this projection already contains the destination's tip, so ancestry did not cause this refusal | read the status and git's own message |
  | `:unreadable` | the destination's objects are not in this repo, so only the tips are known | fetch and ask again |

  ABSENCE IS AN ANSWER for containment and for nothing else. An ancestor is
  reachable, so an object this repo does not have cannot be one — that much is
  sound with no fetch. The base, the counts and the destination's own stamp all
  need the object, which is why a caller fetches before asking and why
  `:unreadable` exists for when it could not."
  [^Repository repo projected mirror]
  (let [pm (stamped-commit-point (message-of repo projected))]
    (if-let [mmsg (message-of repo mirror)]
      (let [mm       (stamped-commit-point mmsg)
            ours?    (ancestor? repo mirror projected)
            theirs?  (ancestor? repo projected mirror)
            base     (merge-base repo projected mirror)]
        {:projected {:sha projected :commit-point pm}
         :mirror    {:sha mirror    :commit-point mm}
         :base      base
         :ahead     (commits-past repo projected base)
         :behind    (commits-past repo mirror base)
         :contains-mirror-tip? ours?
         :cause     (cond ours?               :no-divergence
                          theirs?             :mirror-ahead
                          (nil? base)         :unrelated
                          (and pm mm (= pm mm)) :remint
                          :else               :diverged)})
      {:projected {:sha projected :commit-point pm}
       :mirror    {:sha mirror}
       :contains-mirror-tip? false
       :cause :unreadable})))

^:reads (defn source-tree
  "{path source} for every namespace in `store`, at the paths the projection
  roots them under — production `src/`, tests `test/`, instruments
  `instruments/`, cljs `cljs-src/`, the same layout `build!` writes.

  PATHS, not namespace names, and only a folded store can answer: platform and
  role are both properties of the store as it stood. Extracted from
  `project-journal!` so that a caller needing this tree WITHOUT a repo — a
  directory import computing its merge base — cannot resolve paths a second
  way. Two derivations of one layout is how the mirror and `build!` once
  produced different jars from the same store."
  [store]
  (into (sorted-map)
        (map (fn [n] [(store.render/source-path n
                                                (store/platform-for store n)
                                                (store/role-for store n))
                      (store.render/render-ns store n)]))
        (keys (:namespaces store))))

(defn- ops-since
  "The ops of the deltas AFTER `marker-id` on `line-id`, newest first, walked
  parent by parent from the line's head — or nil when the marker is not within
  `cap` hops (a line that moved far past its newest marker is the walk's
  case, not this one). One primary-key read per hop, never the ancestry."
  [map-conn line-id marker-id cap]
  (loop [id (db/line-head map-conn line-id) acc [] n 0]
    (cond
      (nil? id)         nil
      (= id marker-id)  acc
      (>= n cap)        nil
      :else (if-let [d (db/delta-by-id map-conn id)]
              (recur (:parent d) (conj acc (:op d)) (inc n))
              nil))))

^:reads (defn ^:export recent-commits
  "The newest `n` commits reachable from `sha` in `repo`, newest first, as
  `[{:sha (12 chars) :at \"yyyy-MM-dd\" :subject} …]` — the shape a records
  answer quotes. Empty when the repo lacks the object (an in-memory
  projection routinely does). A clone keeps this as the `git-log` meta, so
  \"what did git have before the import\" is answered from the store rather
  than by a shell (eval27 opus: git log twice per cell for it)."
  [^Repository repo sha n]
  (if-not (and repo sha)
    []
    (let [id (ObjectId/fromString sha)]
      (if-not (.has (.getObjectDatabase repo) id)
        []
        (with-open [rw (RevWalk. repo)]
          (.markStart rw (.parseCommit rw id))
          (vec (for [^org.eclipse.jgit.revwalk.RevCommit c (take n (iterator-seq (.iterator rw)))]
                 {:sha (subs (.getName c) 0 12)
                  :at (.format (java.text.SimpleDateFormat. "yyyy-MM-dd")
                               (java.util.Date. (* 1000 (long (.getCommitTime c)))))
                  :subject (.getShortMessage c)})))))))

^:reads (defn artifact-paths
  "`{path bytes}` for every artifact `store` registers whose bytes `dir`'s
  cache holds — the compiled client bundle, a vendored js library — so a
  projected tree carries what a checkout has to BUILD with. CI jars a checkout
  of this tree, and the bundle can come from nowhere else: a release built
  from a projection without it served pages whose script 404'd.

  An artifact's bytes live outside the journal by design (the sha and the
  recipe are in the store), so a projection minted where the cache is empty
  carries no such file, and the same commit point re-minted where it is full
  does. That is the one place a projected tree is not a pure function of the
  journal. The pinned sha `git_map` records keeps a once-minted commit
  stable, and the cache is full on the machine that produced the artifact,
  which is the machine that projects."
  [dir store]
  (into (sorted-map)
        (keep (fn [[path _]]
                (when-let [^bytes bs (:bytes (artifacts/fetch dir store (str path)))]
                  [(str path) bs])))
        (:artifacts store)))

(defn ^:export commit-point-tree
  "{path content} for the tree the LAST commit-point in `deltas` projects — folded
  from the journal and rendered, **with no git repo anywhere**. nil when there
  is no commit-point yet. `deltas` is the line's journal, oldest first
  (`slopp.store.db/line-deltas`); the value does not carry it, and an import
  is asked for rarely enough to read it then.

  This is the merge BASE for an import that did not come through git. Export
  is one-way; import is the narrow case where an external tool changed an
  export and the change should come back as ordinary tracked form edits, and
  nothing about that requires the other tool to have used git — it requires a
  tree of files and a base to diff against. Git supplies a merge-base commit;
  a directory supplies nothing, and this is the answer the store already had.

  It shares `source-tree` and `commit-paths` with `project-journal!` rather
  than recomputing them, and that is the whole correctness argument: a base
  differing from the projection by so much as the generated `deps.edn` would
  report phantom changes on paths nobody touched, on every import, forever.

  A marker normally targets the delta immediately before it; a retroactive
  `commit_point {:target …}` names an earlier one, and the fold stops there."
  [deltas blob-of & {:keys [dir]}]
  (let [marker (last (filter #(= :commit (:op %)) deltas))]
    (when marker
      (let [upto (or (:target marker) (:id marker))
            st   (reduce (fn [st d]
                           (let [st' (or (store/replay-delta st d) st)]
                             (if (= (:id d) upto) (reduced st') st')))
                         (store/empty-store) deltas)]
        (commit-paths (cond-> (source-tree (refs/arrange-all st))
                        ;; the artifacts too, when the caller can name the
                        ;; store dir whose cache holds them — the projection
                        ;; carries them, so a base without them would report
                        ;; the bundle as a change on every import
                        dir (merge (artifact-paths dir st)))
                      (:deps marker) (:files marker)
                      (:config marker) blob-of)))))

(defn marker-identity
  "The id a :commit marker was FIRST minted under — its own, or the
  `:origin-id` a rebase-land's re-minted copy carries. The copy is the same
  commit point on a new chain, so everything that keys on a commit point keys
  on this: the git_map pin, the `Slopp-Commit:` stamp, the sha a query row
  surfaces. Keyed on the copy's own id, the projection minted a second commit
  for a state it had already published, and the mirror refused the push that
  followed (2026-09-23)."
  [d]
  (or (:origin-id d) (:id d)))

(defn fingerprint
  "Line-independent identity of a :commit marker: SHA-256 of the canonical
  tuple [identity at description] (NOT the whole map — map print order is
  not canonical across EDN round-trips). `identity` is [[marker-identity]].
  Neither the marker's own id nor its :target is in the tuple: a rebase-land
  re-mints the branch's markers under fresh ids and re-points their targets
  at its own copies, and both change with the copy while the commit point
  does not. Two markers minted apart never share a millisecond."
  [d]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes (pr-str [(marker-identity d) (:at d) (:description d)])
                                StandardCharsets/UTF_8))
         (map #(format "%02x" %))
         (apply str))))

(defn- commit-message [d]
  (str (:description d)
       ;; the IDENTITY, not the id: a rebase-land's carried copy is the same
       ;; commit point, and the commit it names must say so
       "\n\nSlopp-Commit: " (marker-identity d) "\n"
       (when (and (:author d) (:agent d))
         ;; G5: the author field is the configured human; keep the agent
         ;; visible (new-style markers only — old messages must not change)
         (str "Slopp-Agent: " (:agent d) "\n"))
       (when (= :red (:status d)) "Slopp-Status: red\n")))

(defn- insert-commit!
  "Build blobs + tree + commit for marker `d` and return the sha. Pure
  function of (parent-sha, d, tree-map) — determinism is what makes the
  projection rebuildable (which is why the author identity, the files
  manifest, and the structured config live ON the marker, never in ambient
  state)."
  [^Repository repo parent-sha d tree-map blob-of]
  (with-open [ins (.newObjectInserter repo)]
    (let [tree-id (insert-tree! ins (commit-paths tree-map (:deps d) (:files d) (:config d) blob-of))
          at      (Instant/ofEpochMilli (long (:at d)))
          who     (commit-author d)
            ;; reflection-free ctors matter: reflective JGit calls resolve
            ;; classes per-thread and break on server dispatch threads
          cb      (doto (CommitBuilder.)
                    (.setTreeId tree-id)
                    (.setAuthor (PersonIdent. ^String (:name who) ^String (:email who)
                                              at ^java.time.ZoneId ZoneOffset/UTC))
                    (.setCommitter (PersonIdent. "slopp" "slopp@slopp"
                                                 at ^java.time.ZoneId ZoneOffset/UTC))
                    (.setMessage (commit-message d)))]
      (when parent-sha
        (.setParentId cb (ObjectId/fromString parent-sha)))
      (let [cid (.insert ins cb)]
        (.flush ins)
        (.name cid)))))

(defn- live-sha!
  "The sha `git_map` pins for marker `d`, when its object is actually in the
  repo — nil otherwise. A pin alone is a record of what was once minted; the
  object being present is what lets a projection reuse it. Keyed on the
  marker's IDENTITY: a carried copy finds the commit its original minted."
  [^Repository repo map-conn d]
  (let [sha (lookup-sha map-conn (marker-identity d) (fingerprint d))]
    (when (and sha (.has (.getObjectDatabase repo) (ObjectId/fromString sha)))
      sha)))

;; ---------------------------------------------------------------------------
;; projection
(defn project-journal!
  "Walk one journal's deltas in order, minting a git commit in the in-memory
  repo for every :commit marker whose object isn't already present. Parent =
  the previous marker's sha (journal order IS the chain); `:base` seeds the
  chain — a cloned store grafts its first commit-point onto the remote commit it
  was cloned at. A marker carrying `:git-sha` (a pull/import) is ADOPTED, not
  minted: the remote commit itself becomes the chain node (its object arrives
  by fetch; the remote durably holds its own history). A pinned sha is reused
  only when its object is live in this repo; on a fresh repo the object is
  re-inserted deterministically (same sha). Pins are keyed on the marker's
  IDENTITY (`marker-identity`): a rebase-land's carried copy of a marker is
  the same commit point and reuses the commit its original minted rather
  than minting a second one. Returns the tip sha (= base when no markers)
  or nil.

  **Each commit-point's tree is DERIVED, not stored.** The store is folded from
  the journal as this walk proceeds, so reaching a marker means holding the
  store as it stood there, and the tree is `render-ns` over it. Commit-points
  used to carry a byte-exact snapshot of every namespace instead — 82 MB
  across 272 of them here, 39% of the journal — because comments lived
  positionally and could not be reconstructed. They are form-owned content
  now, so the log is a complete account and the snapshot has no job.

  ONE pass matters: folding from empty per marker is quadratic in the journal.

  A marker normally targets the delta immediately before it, which is exactly
  where the fold stands when the walk reaches it. `commit_point {:target ...}`
  can mark an EARLIER spot, so those positions are rendered as the walk passes
  them and held until their marker arrives — the only trees kept in memory.

  A delta that will not replay (a retired `:trivia`) is SKIPPED rather than
  fatal: it edited `:sep` elements the renderer no longer reads, so the state
  it would rebuild is state nothing consults.

  `ctx` is an OPAQUE handle from `open-ctx!` — see `close-ctx!`."
  [ctx line-label deltas & {:keys [base refs]}]
  (let [map-conn         (:slopp.git/map-conn ctx)
        ^Repository repo (:slopp.git/repo ctx)
        dv       (vec deltas)
        retro    (into #{}
                       (keep (fn [i]
                               (let [d (nth dv i)]
                                 (when (and (= :commit (:op d))
                                            (:target d)
                                            (not= (:target d)
                                                  (:id (get dv (dec i)))))
                                   (:target d)))))
                       (range (count dv)))
        ;; PATHS, not namespace names. The fold holds the store as it stood at
        ;; this commit-point, which is the only point where a namespace's platform
        ;; and role are both known — and the projection has to root them the
        ;; way build! does, because CI jars a checkout of this tree.
        ;; `source-tree`, aliased locally: the fold holds the store as it stood
        ;; at this commit-point, which is the only point where a namespace's
        ;; platform and role are both known.
        ;; ARRANGED before it is rendered: the journal records creation order
        ;; and content, never an arrangement, so the fold derives the order
        ;; from the forms exactly as every write did. `refs` — the store's
        ;; persisted reference index — makes that a lookup for every
        ;; namespace the commit-point holds in its live state.
        tree-of  (fn [st] (merge (source-tree (refs/arrange-all st :refs refs))
                                 ;; and the artifacts' bytes, from this store's
                                 ;; cache — see [[artifact-paths]]
                                 (artifact-paths (:slopp.git/dir ctx) st)))]
    (:parent
     (reduce
      (fn [{:keys [parent store held]} d]
        (let [store' (or (store/replay-delta store d) store)
              held'  (cond-> held
                       (retro (:id d)) (assoc (:id d) (tree-of store')))]
          (if-not (= :commit (:op d))
            {:parent parent :store store' :held held'}
            (let [sha (if-let [gsha (:git-sha d)]
                        (do (record-sha! map-conn (marker-identity d) (fingerprint d)
                                         gsha line-label)
                            gsha)
                        (let [fp     (fingerprint d)
                              pinned (lookup-sha map-conn (marker-identity d) fp)]
                          (if (and pinned
                                   (.has (.getObjectDatabase repo)
                                         (ObjectId/fromString pinned)))
                            pinned
                            (let [tree (or (get held' (:target d)) (tree-of store'))
                                  s    (insert-commit! repo parent d tree
                                                       #(db/get-blob map-conn %))]
                              (record-sha! map-conn (marker-identity d) fp s line-label)
                              s))))]
              ;; NOT dissoc'd: two markers can name the same target — a commit-point's
              ;; own target is the delta before it, which is exactly what an
              ;; earlier retroactive marker also points at. Releasing it at the
              ;; first reader left the second rendering the CURRENT state.
              {:parent sha :store store' :held held'}))))
      {:parent base :store (store/empty-store) :held {}}
      dv))))

(defn- project-line!
  "Bring one line's ref up to date and say HOW: `{:sha :via}` with `:via` one of

  - `:current` — the newest marker is minted and its object is live; nothing is
    loaded and nothing is minted.
  - `:head` — only the newest marker is unminted, it targets the delta right
    before it, nothing but bookkeeping follows it on the line, and its parent
    (the previous marker, or the graft base, or nothing for a first commit) is
    live. Its tree is the MATERIALIZED head — a caller's `head-stores` entry
    when its head is this line's head, else `db/load-store` — arranged and
    rendered once, inserted once, with the pinned parent. No journal is read.
  - `:walk` — everything else: a fresh repo with several unminted markers, a
    retroactive `:target`, a `:git-sha` adoption, a missing graft base. That
    is [[project-journal!]], unchanged, which fetches the graft objects it
    needs first.

  The projection folded the line's whole journal from an empty store on every
  publish to render ONE new tree (39,862 deltas here) while the head state
  was materialized in `elements` all along; the fast path is the projection
  finally reading what the store already holds. `commit-point-tree` and the
  walk render through the same `source-tree`, so all three cases mint the
  same sha — a pin rebuilds from nothing and compares."
  [ctx nm line-id base head-stores]
  (let [map-conn         (:slopp.git/map-conn ctx)
        ^Repository repo (:slopp.git/repo ctx)
        has?             (fn [sha] (and sha (.has (.getObjectDatabase repo)
                                                 (ObjectId/fromString sha))))
        walk!            (fn []
                           (let [ds       (db/deltas-after map-conn line-id 0)
                                 need     (cond-> (into [] (keep :git-sha) ds) base (conj base))]
                             (when (some (complement has?) need)
                               (when-let [url (db/get-meta map-conn "git-remote")]
                                 ;; late-bound: git.client requires THIS ns, so a
                                 ;; static require back would cycle
                                 (try ((store/late-ref 'slopp.git.client/fetch-remote!) repo url)
                                      (catch Exception _ nil))))
                             {:sha (project-journal! ctx nm ds :base base
                                                     :refs (db/load-refs map-conn line-id))
                              :via :walk}))
        [m p]            (db/newest-commit-markers map-conn line-id 2)
        m-sha            (when m (live-sha! repo map-conn m))
        after            (when m (ops-since map-conn line-id (:id m) 64))
        parent           (cond p    (live-sha! repo map-conn p)
                               base (when (has? base) base)
                               :else nil)
        parent-ok?       (cond p    (some? parent)
                               base (some? parent)
                               :else true)]
    (cond
      (nil? m) (walk!)

      m-sha {:sha m-sha :via :current}

      (and (nil? (:git-sha m))
           (= (:target m) (:parent m))
           after
           (every? fields/bookkeeping-ops after)
           parent-ok?)
      (let [line-head (db/line-head map-conn line-id)
            hinted    (get head-stores nm)
            store     (if (and hinted (= (:head hinted) line-head))
                        hinted
                        (db/load-store map-conn line-id))
            tree      (merge (source-tree (refs/arrange-all store :refs (db/load-refs map-conn line-id)))
                             ;; the artifacts' bytes too, exactly as the walk
                             ;; merges them — see [[artifact-paths]]
                             (artifact-paths (:slopp.git/dir ctx) store))
            sha       (insert-commit! repo parent m tree #(db/get-blob map-conn %))]
        (record-sha! map-conn (marker-identity m) (fingerprint m) sha nm)
        {:sha sha :via :head})

      :else (walk!))))

(defn ensure-projected!
  "Bring the projection repo up to date with the journals — main + every named
  branch line — advancing refs/heads/* to each line's newest commit-point.
  Per line, [[project-line!]] decides how: `:current` (already minted, nothing
  loaded), `:head` (the newest marker minted from the materialized head with
  its pinned parent — no replay), or `:walk` (the full fold from the journal:
  grafts, adoptions, retroactive targets). A cloned store (`git-base-sha`
  meta) grafts every line onto that base commit; pull markers (`:git-sha`)
  adopt remote commits as chain nodes; objects the repo lacks are fetched
  from `git-remote` on the walk. Reads the dbs directly (always-current, no
  session needed — `:head-stores {line store}` is an optional hint from a
  caller that already holds a line's head), deterministic and idempotent:
  safe to call before every refs advertisement. Returns
  `{:refs {name sha-or-nil} :via {name :current|:head|:walk}}`.

  `ctx` is an OPAQUE handle from `open-ctx!` — see `close-ctx!`."
  [ctx & {:keys [head-stores]}]
  (let [map-conn         (:slopp.git/map-conn ctx)
        ^Repository repo (:slopp.git/repo ctx)]
    (locking (:slopp.git/lock ctx)
      (let [base    (db/get-meta map-conn "git-base-sha")
            lines   (into [["main" (db/trunk-line-id! map-conn)]]
                          (branch-journals map-conn))
            results (into {} (map (fn [[nm line-id]]
                                    [nm (project-line! ctx nm line-id base head-stores)]))
                          lines)
            refs    (into {} (map (fn [[nm r]] [nm (:sha r)])) results)]
        (doseq [[nm sha] refs :when sha]
          (set-branch-ref! repo nm sha))
        {:refs refs
         :via  (into {} (map (fn [[nm r]] [nm (:via r)])) results)}))))
