(ns slopp.sync
  "Phase-1 git bridge orchestration: `push!` a store's projection to a normal
  git remote (GitHub, a bare repo, …) and `clone!` a remote back into a
  FILELESS store — the working dir gets `.slopp/store.db` and NO `.clj`
  files. The remote holds real files (the interchange artifact); the store
  holds forms (the local representation agents edit). `slopp.git` moves the
  bytes; this namespace owns the store side — which is why it, not slopp.git,
  depends on `slopp.api`.

  A clone records `git-remote` + `git-base-sha` meta, so its projection
  GRAFTS onto the remote's history and later pushes are plain fast-forwards.
  Non-slopp-source paths on the remote (README, CI config, …) are ignored on
  clone; a `.clj` that fails slopp's gates (dialect, compile) fails the clone
  with the reason — the quarantine/conflict flow is the Phase-2 pull."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [slopp.ops :as ops]
            [slopp.kernel.boot :as boot]
            [slopp.store.db :as db]
            [slopp.git :as git] [rewrite-clj.node :as n] [rewrite-clj.parser :as p] [slopp.store :as store] [slopp.git.client :as git.client] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.store.render :as store.render] [slopp.edit.modules :as edit.modules] [slopp.store.merge :as merge] [slopp.ops.branch :as branch]))

(defn path-ns
  "`src/foo/bar_baz.clj` → `foo.bar-baz`; nil for anything that is not a
  slopp-written source path — those remote files are not slopp's to ingest.

  Roots come from `store.render/source-roots`, the same set `source-path`
  writes into, so a clone takes exactly what a build produces. This used to
  hard-code `(?:src|test)/(.+)\\.clj`, which is a second and narrower answer to
  a question already answered once — and it was wrong in two directions at
  once: every `.cljc` namespace and every `instruments/` file was dropped.

  **The failure was silent, which is the part worth remembering.** `clone!`
  reports `:namespaces n` for what it decided to take, so a lossy import
  announces success with a smaller number and nothing downstream can tell. It
  surfaced only because a survivor happened to require a casualty, three weeks
  after it started: `slopp.api.endpoints` could not find `slopp.api.contracts`,
  which reads as a load-order bug and is not one.

  `.cljs` is matched too, so a clone SEES such a file rather than dropping it
  silently — but a `:cljs` namespace cannot load into a JVM image, and its
  platform is declared in config `clone!` restores only after ingesting.
  Taking one is a real ordering question rather than a regex."
  [path]
  (let [roots (str/join "|" (sort store.render/source-roots))
        re    (re-pattern (str "(?:" roots ")/(.+)\\.clj[cs]?"))]
    (when-let [[_ rel] (re-matches re (str path))]
      (symbol (-> rel (str/replace "/" ".") (str/replace "_" "-"))))))

(defn form-entries
  "Ordered [{:name sym-or-nil :src str}] for each top-level FORM in `source`
  (trivia dropped — the pull diff/merge granularity is the form)."
  [source]
  (into []
        (comp (filter n/sexpr-able?)
              (map (fn [node]
                     (let [s (n/string node)]
                       {:name (store/name-of-source s) :src s}))))
        (n/children (p/parse-string-all (str source)))))

(defn ns-change-plan
  "PURE 3-way merge plan for one namespace at form granularity. `old` = the
  file at the merge base, `new` = at the remote tip, `cur` = our current
  render. Remote wins wherever WE are clean (our form still equals the
  base's); anything both sides touched is a conflict for the agent — exactly
  git's model, one file coarser. Returns
    {:noop true}  |  {:noop true :trivia true}   (only trivia differs)
    {:conflict reason}
    {:steps [edit-group steps] :order [names]}   (order = remote file order)."
  [ns-sym old new cur]
  (if (= cur new)
    {:noop true}
    (let [oldf (form-entries (or old ""))
          newf (form-entries (or new ""))
          curf (form-entries (or cur ""))
          anon? (fn [fs] (some #(nil? (:name %)) fs))
          dup?  (fn [fs] (some (fn [[_ c]] (> c 1)) (frequencies (map :name fs))))]
      (cond
        (or (anon? oldf) (anon? newf) (anon? curf))
        {:conflict "anonymous top-level form — not addressable at form granularity"}

        (or (dup? oldf) (dup? newf) (dup? curf))
        {:conflict "duplicate form names"}

        :else
        (let [om (into {} (map (juxt :name :src)) oldf)
              nm (into {} (map (juxt :name :src)) newf)
              cm (into {} (map (juxt :name :src)) curf)
              verdicts
              (for [nm-sym (distinct (concat (map :name oldf) (map :name newf)))
                    :let [o (om nm-sym) n' (nm nm-sym) c (cm nm-sym)]
                    :when (not= o n')]
                (cond
                  (= c n') nil                                   ; already have it
                  (nil? n') (cond                                ; deleted remotely
                              (nil? c) nil
                              (= c o)  {:step {:action :delete :ns ns-sym :name nm-sym}}
                              :else    {:conflict (str "remote deleted " nm-sym " which we edited")})
                  (nil? o)  (if (nil? c)                         ; added remotely
                              {:step {:action :add :ns ns-sym :source n'}}
                              {:conflict (str "both sides added " nm-sym)})
                  :else     (cond                                ; changed remotely
                              (= c o)  {:step {:action :replace :ns ns-sym :name nm-sym :source n'}}
                              (nil? c) {:conflict (str "remote edited " nm-sym " which we deleted")}
                              :else    {:conflict (str "both sides edited " nm-sym)})))
              verdicts (remove nil? verdicts)]
          (if-let [c (first (filter :conflict verdicts))]
            {:conflict (:conflict c)}
            (if (empty? verdicts)
              {:noop true :trivia true}
              {:steps (mapv :step verdicts) :order (mapv :name newf)})))))))

(defn- apply-deps!
  "Absorb remote deps.edn changes (base→tip) into the manifest: applied when
  OUR coord still equals the base's (clean), conflict when all three
  diverge — the deps.edn analogue of the per-form 3-way."
  [session treeM treeT conflict! agent]
  (let [dm  (or (:deps (edn/read-string (or (get treeM "deps.edn") "{}"))) {})
        dt  (or (:deps (edn/read-string (or (get treeT "deps.edn") "{}"))) {})
        cur (:deps (:store @session))]
    (doseq [lib (distinct (concat (keys dm) (keys dt)))
            :let [mv (get dm lib) tv (get dt lib) cv (get cur lib)]
            :when (not= mv tv)]
      (cond
        (= cv tv) nil
        (= cv mv) (let [r (if tv
                            (ops/deps-add! session lib tv :agent agent
                                           :prompt "pull: remote deps change")
                            (ops/deps-remove! session lib :agent agent
                                              :prompt "pull: remote deps removal"))]
                    (when (:error r)
                      (conflict! "deps.edn" nil (str lib ": " (:error r)))))
        :else (conflict! "deps.edn" nil
                         (str "deps diverged for " lib ": base " (pr-str mv)
                              ", remote " (pr-str tv) ", ours " (pr-str cv)))))))

(defn- apply-ns!
  "Apply one namespace's remote change (base→tip) to the store: ingest new
  namespaces, edit-group the form-level diff (remote wins where we're
  clean), quarantine everything that needs an agent. Whole-file deletions
  are conservatively a conflict (an agent should confirm destruction)."
  [session ns-sym path old new conflict! applied! note! agent]
  (let [have (get-in (:store @session) [:namespaces ns-sym])
        cur  (when have (query/query-source session ns-sym))]
    (cond
      (nil? new)
      (when have
        (conflict! path ns-sym
                   "remote deleted this namespace — delete its forms locally, then git_resolve"))

      (nil? old)
      (cond
        (= cur new) nil
        (not have)  (let [r (ops/ingest! session ns-sym new :agent agent)]
                      (if (:error r)
                        (conflict! path ns-sym (:error r))
                        (applied! ns-sym)))
        :else       (conflict! path ns-sym "both sides added this namespace"))

      :else
      (if-not have
        (conflict! path ns-sym "remote edited a namespace we deleted")
        (let [plan (ns-change-plan ns-sym old new cur)]
          (cond
            (:conflict plan) (conflict! path ns-sym (:conflict plan))
            (:noop plan)     (when (:trivia plan)
                               (note! (str path ": comment/whitespace-only remote change — "
                                           "not representable as a form edit, skipped")))
            :else
            (let [r (ops/edit-group-once! session (:steps plan)
                                     :prompt (str "pull: " path) :agent agent)]
              (if (:error r)
                (conflict! path ns-sym (str "failed to apply: " (:error r)))
                ;; no reorder to the remote's file order: the arrangement is derived
                ;; from the forms at the write (a remote projection was derived
                ;; the same way, so the trees converge; a hand-arranged file
                ;; yields to the derivation and the note below says so)
                (do (when (not= (query/query-source session ns-sym) new)
                      (note! (str path ": applied, but the arrangement or trivia"
                                  " differs from the remote")))
                    (applied! ns-sym))))))))))

^:reads (defn conflicts
  "Unresolved pull conflicts for the store at `dir` — each row carries the
  raw remote file so the agent can merge it: [{:path :ns :source :sha
  :reason :at}]."
  [dir]
  (with-open [conn (db/open! dir)]
    (db/quarantine-list conn)))

(defn resolve!
  "Mark a pull conflict resolved — the agent has merged/adapted the remote
  content through the edit tools (or decided against it). nil `path` clears
  everything. Returns the remaining {:conflicts [...]}."
  [dir path]
  (with-open [conn (db/open! dir)]
    (db/quarantine-clear! conn path)
    {:conflicts (db/quarantine-list conn)}))

(defn- apply-files!
  "Absorb remote changes to NON-CODE files (base→tip), dispatching on what
  the path IS rather than treating every one as an opaque blob:

  - a projected CONFIG rendering is not a file and is never absorbed
    (`store/projected-config-paths`, plus anything this store already holds
    `:config` for). Those are outputs of the store's own declarations, and
    writing one into `:files` would leave two copies of the same fact in
    fields the projection reads BOTH of. NOTED rather than skipped silently,
    naming the verb that would make the change for real.
  - TEXT three-way merges (`store.merge/merge-text`), so edits to different
    parts of one file both land. Only genuinely overlapping hunks stop, and
    the quarantined payload shows the OVERLAP rather than the whole file.
  - BINARY cannot be merged and does not pretend to: ours-unchanged takes
    theirs, and divergence is a conflict. Line-merging bytes would produce a
    corrupt file while reporting success, which is the worst failure
    available here — so the test is explicit rather than incidental.

  Whole-file is the right grain for the first two: `:files` is path→text with
  no sub-file addressing, so the whole file IS the store's unit. What changed
  is that a DIVERGENCE inside that unit no longer costs an agent a manual
  merge it was gaining nothing from."
  [session treeM treeT changed conflict! note! agent]
  (let [st         (:store @session)
        ;; LOCAL first, and it is not a stylistic ordering. The fallback below
        ;; treats a path as projected when the store merely HOLDS `:config`
        ;; for it — which is true of every `dev` entry anybody has set, so
        ;; read the other way round the fallback claims precisely the paths
        ;; that must never be projected. A local path is not in a remote tree
        ;; to begin with, so there is nothing here to refuse absorbing.
        projected? (fn [path]
                     (and (not (contains? store/local-config-paths path))
                          (or (contains? store/projected-config-paths path)
                              (some? (get-in st [:config path])))))
        ;; a NUL byte, spelled without putting one in this source: a stored
        ;; control byte makes the form read as BINARY to grep and it goes
        ;; invisible to every text sweep
        nul?       (fn [s] (and (string? s) (boolean (some #(zero? (int %)) s))))]
    (doseq [path changed
            :when (and (nil? (path-ns path)) (not= "deps.edn" path))
            :let [mv (get treeM path)
                  tv (get treeT path)
                  cv (get-in @session [:store :files path])
                  ;; ours is content-addressed ({:sha …}), or any side carries
                  ;; a NUL — either way there are no lines to merge
                  binary? (or (map? cv) (boolean (some nul? [mv tv cv])))]]
      (cond
        (projected? path)
        (note! (str path ": the remote changed a projected declaration, not a"
                    " file — not imported. This path is rendered FROM the"
                    " store, so absorbing it would leave two copies of the"
                    " same fact. If the change was meant, make it here: "
                    (if (= "modules" path)
                      "module_dep {from … to …}"
                      (str "config_file {path \"" path "\" key … value …}"))))

        (= cv tv) nil

        (= cv mv)
        (let [r (if (some? tv)
                  (ops/file-put! session path tv :agent agent
                                 :prompt (str "pull: " path))
                  (ops/file-remove! session path :agent agent
                                    :prompt (str "pull: removed " path)))]
          (if (:error r)
            (conflict! path nil (str "file change failed: " (:error r)))
            (note! (str path ": file " (if (some? tv) "updated" "removed")))))

        ;; all three differ. A DELETION on either side is not mergeable text —
        ;; an agent should confirm destruction, the same stance a whole-namespace
        ;; deletion takes.
        (or binary? (nil? tv) (nil? cv))
        (conflict! path nil
                   (str "file diverged: base/remote/ours all differ"
                        (cond binary?   " — binary, so there is nothing to merge"
                              (nil? tv) " — the remote DELETED it while we changed it"
                              :else     " — we deleted it while the remote changed it")))

        :else
        (let [{:keys [merged conflict]} (merge/merge-text mv cv tv)]
          (if merged
            (let [r (ops/file-put! session path merged :agent agent
                                   :prompt (str "pull: " path
                                                " — three-way merged with the remote"))]
              (if (:error r)
                (conflict! path nil (str "file change failed: " (:error r)))
                (note! (str path ": file merged with the remote (both sides"
                            " changed, no overlapping lines)"))))
            (conflict! path nil
                       (str "file diverged and the changes OVERLAP — the merged"
                            " copy below carries conflict markers; resolve the"
                            " marked hunks, file_put the result, then"
                            " git_resolve:\n" conflict))))))))

(defn- checked-out-branch
  "The branch checked out at local WORKING repo `url` (nil for bare repos,
  real remotes, or no repo at all) — read from .git/HEAD. JGit will happily
  move a checked-out ref under a live working tree; push must refuse it."
  [url]
  (let [s (str url)]
    (when-not (re-find #"^[a-z+]+://" s)
      (let [head (io/file s ".git" "HEAD")]
        (when (.exists head)
          (second (re-find #"ref: refs/heads/(\S+)" (slurp head))))))))

(defn- resolve-remote
  "A store's saved remote may be RELATIVE (import! saves \".\": the store's
  own containing repo) — resolve it against the store dir, not the CWD."
  [dir target]
  (let [s (str target)]
    (if (or (str/blank? s)
            (re-find #"^[a-z+]+://" s)
            (.isAbsolute (io/file s)))
      target
      (str (io/file (str dir) s)))))

(defn push!
  "Push the store at `dir`'s projection to a git remote: `:url` the first
  time (saved as `git-remote` meta), the saved remote thereafter. The DEST
  branch is `slopp/<:branch>` (default slopp/main) — the
  ownership boundary: slopp owns that ONE branch; humans keep main (and
  everything else) with regular git and merge across. Refused while pull
  conflicts stand, and refused onto the checked-out branch of a local
  working repo. Returns {:pushed sha :status s :remote url :remote-branch b}
  | {:error msg}."
  [dir & {:keys [url token branch]}]
  (let [ctx (git/open-ctx! dir)]
    (try
      (let [conn    (:slopp.git/map-conn ctx)
            target  (resolve-remote dir (or url (db/get-meta conn "git-remote")))
            rbranch (str "slopp/" (or branch "main"))
            q       (db/quarantine-list conn)]
        (cond
          (str/blank? (str target))
          {:error "no remote configured — pass :url once (it is saved as git-remote)"}

          (seq q)
          {:error (str "unresolved git conflicts (" (count q) ": "
                       (str/join ", " (map :path q))
                       ") — inspect with git_conflicts, merge via edit tools, then git_resolve")}

          (= rbranch (checked-out-branch target))
          {:error (str "refs/heads/" rbranch " is checked out in the working repo at "
                       target " — pushing would move the ref under a live working"
                       " tree. Check out a different branch there, or push from a"
                       " checkout (git_push mirrors slopp/* without touching the"
                       " working tree).")}

          :else
          (let [r     (git.client/push-to-remote! ctx target
                                           :token token :remote-branch rbranch)
                saved (db/get-meta conn "git-remote")]
            ;; save the FIRST url as the default; a one-off push elsewhere
            ;; must never silently rewrite it (it broke the user's normal
            ;; push flow, 2026-07-14)
            (when (and (not (:error r)) (nil? saved))
              (db/set-meta! conn "git-remote" (str target)))
            (assoc r :remote (str target)
                   :default-remote (or saved (str target))))))
      (finally (git/close-ctx! ctx)))))

^:reads
(defn- empty-store?
  "True when `dir`'s existing store.db holds NOTHING — no delta, no element.
  The MCP server auto-creates exactly this when it serves a fresh dir;
  clone/import must treat it as fresh, not refuse it. Asked on EVERY project
  open, so it is two `LIMIT 1` probes ([[slopp.store.db/store-empty?]]) and
  not a fold of the store it is about to decide against loading."
  [dir]
  (with-open [conn (db/open! dir)]
    (db/store-empty? conn)))

^:reads (defn- slopp-branch?
  "Does the git checkout at `dir` carry any slopp/* mirror branch (local or
  origin-tracking)? The marker of a slopp-published repo — auto-import keys
  on it so plain git repos are never touched."
  [dir]
  (let [repo (-> (org.eclipse.jgit.storage.file.FileRepositoryBuilder.)
                 (.setGitDir (io/file dir ".git"))
                 (.build))]
    (try
      (boolean (or (seq (.getRefsByPrefix (.getRefDatabase repo) "refs/heads/slopp/"))
                   (seq (.getRefsByPrefix (.getRefDatabase repo) "refs/remotes/origin/slopp/"))))
      (finally (.close repo)))))

(defn publish-local!
  "Mirror the store's commit-point history into THIS checkout's local git as
  refs/heads/slopp/<store-branch> (user decision 2026-07-14): every
  commit_point lands in local git automatically, so the repo durably
  carries the slopp history; REMOTE publishing stays explicit (git_push).
  ONE projection algorithm: `ensure-projected!` (inside `push-to-remote!`)
  already projects main + every on-disk branch line onto this ctx with the
  store's graft base — so a non-main `store-branch` just names its OWN
  projected ref as the push SOURCE (`:branch`). (Two earlier bugs here:
  omitting `:branch` pushed main's ref onto every mirror; a line-dir ctx
  re-projected the branch without the main db's graft meta and minted a
  divergent, non-fast-forward chain.) `:head-store` is the caller's already-
  folded store for `store-branch` — the commit point that just landed holds
  it, and handing it over lets the projection mint the new commit from it
  without reading the journal (D2b). Never reads or writes the git-remote
  default. nil when `dir` isn't a git checkout; push refusals surface as
  {:error} (e.g. the mirror branch is checked out)."
  [dir store-branch & {:keys [head-store]}]
  (when (.exists (io/file dir ".git"))
    (let [ctx     (git/open-ctx! dir)
          line    (or store-branch "main")
          mirror  (str "slopp/" line)]
      (try
        (if (= mirror (checked-out-branch (str dir)))
          {:error (str "refs/heads/" mirror " is checked out — cannot mirror onto"
                       " a live working tree")}
          (assoc (git.client/push-to-remote! ctx (str dir)
                                         :branch line
                                         :remote-branch mirror
                                         ;; this push never leaves the repo —
                                         ;; the \"remote\" is `dir` itself. Saying
                                         ;; so is what stops a refusal here
                                         ;; naming a remote nobody has and
                                         ;; advising a pull that cannot happen
                                         :mirror? true
                                         :head-stores (when head-store {line head-store}))
                 :branch mirror))
        (finally (git/close-ctx! ctx))))))

(defn- working-repo
  "The CHECKOUT's own git repo at `dir` (mirror ops act on refs/heads/slopp/*
  there, not on the store's projection repo). nil when not a checkout."
  [dir]
  (let [gd (io/file dir ".git")]
    (when (.exists gd)
      (-> (org.eclipse.jgit.storage.file.FileRepositoryBuilder.)
          (.setGitDir gd)
          (.build)))))

(defn mirror-push!
  "Push local MIRROR branches (refs/heads/slopp/<b>) from a git CHECKOUT to
  a git remote. `branches` = STORE branch names (default [\"main\"]).
  Fast-forward only. The FIRST url is saved as the default remote; one-off
  urls never rewrite it. Fileless stores (no checkout) publish via `push!`
  (the projection) instead."
  [dir & {:keys [url token branches] :or {branches ["main"]}}]
  (if-let [repo (working-repo dir)]
    (try
      (let [saved  (with-open [conn (db/open! dir)]
                     (db/get-meta conn "git-remote"))
            target (resolve-remote dir (or url saved))]
        (if (str/blank? (str target))
          {:error "no remote — pass :url once (it becomes the saved default)"}
          (let [uri     (org.eclipse.jgit.transport.URIish.
                         ^String (if (re-find #"^[a-z+]+://" (str target))
                                   (str target)
                                   (.getAbsolutePath (io/file (str target)))))
                updates (vec (for [b branches
                                   :let [r (str "refs/heads/slopp/" b)]]
                               (org.eclipse.jgit.transport.RemoteRefUpdate. repo r r false nil nil)))
                missing (vec (remove #(.resolve repo (str "refs/heads/slopp/" %)) branches))]
            (if (seq missing)
              {:error (str "no local mirror branch for "
                           (str/join ", " (map #(str "slopp/" %) missing))
                           " — a commit_point creates it")}
              (let [res (with-open [tn (org.eclipse.jgit.transport.Transport/open repo uri)]
                          (when-let [creds (git.client/remote-credentials token)]
                            (.setCredentialsProvider tn creds))
                          (.push tn org.eclipse.jgit.lib.NullProgressMonitor/INSTANCE updates))
                    rows (vec (for [^org.eclipse.jgit.transport.RemoteRefUpdate u
                                    (.getRemoteUpdates ^org.eclipse.jgit.transport.PushResult res)]
                                {:ref (.getRemoteName u) :status (str (.getStatus u))
                                 :message (.getMessage u)}))]
                (if (every? #(contains? #{"OK" "UP_TO_DATE"} (:status %)) rows)
                  (do (when (nil? saved)
                        (with-open [conn (db/open! dir)]
                          (db/set-meta! conn "git-remote" (str target))))
                      {:mirrored rows :remote (str target)
                       :default-remote (or saved (str target))})
                  {:error (str "mirror push rejected: " (pr-str rows)
                               " — fast-forward only; git_pull first if the remote moved")}))))))
      (finally (.close repo)))
    {:error (str dir " has no .git — the store IS durable without one (commit-points"
                 " live in .slopp/store.db); to ALSO mirror history into git,"
                 " run `git init` there and the next commit_point creates"
                 " slopp/<branch> automatically")}))

(defn mirror-pull!
  "Fetch the remote's slopp/<b> mirror branches into local
  refs/heads/slopp/<b> (fast-forward only — divergence is an honest
  per-branch :status, never a force). Store absorption of remote history
  stays git_pull's plain form. `branches` = STORE branch names."
  [dir & {:keys [url token branches] :or {branches ["main"]}}]
  (if-let [repo (working-repo dir)]
    (try
      (let [target (with-open [conn (db/open! dir)]
                     (resolve-remote dir (or url (db/get-meta conn "git-remote"))))]
        (if (str/blank? (str target))
          {:error "no remote — pass :url (or configure one via git_push {url})"}
          (let [uri   (org.eclipse.jgit.transport.URIish.
                       ^String (if (re-find #"^[a-z+]+://" (str target))
                                 (str target)
                                 (.getAbsolutePath (io/file (str target)))))
                specs (mapv #(org.eclipse.jgit.transport.RefSpec.
                              (str "refs/heads/slopp/" % ":refs/heads/slopp/" %))
                            branches)]
            (with-open [tn (org.eclipse.jgit.transport.Transport/open repo uri)]
              (when-let [creds (git.client/remote-credentials token)]
                (.setCredentialsProvider tn creds))
              (.fetch tn org.eclipse.jgit.lib.NullProgressMonitor/INSTANCE specs)
              {:pulled (vec (for [b branches]
                              {:branch (str "slopp/" b)
                               :head (some-> (.resolve repo (str "refs/heads/slopp/" b))
                                             (.name))}))
               :remote (str target)}))))
      (finally (.close repo)))
    {:error (str dir " has no .git — nothing to fetch mirrors into; `git init`"
                 " (or clone the published repo) first, then git_pull brings"
                 " slopp/<branch> down")}))

(defn path-declarations
  "The declarations a projected TREE carries in its PATHS — `{:platforms
  {ns-str platform} :roles {module-str :instrument}}` over `paths`.

  `store.render/source-path` read backwards, and deliberately derived from
  its rules rather than restated: it roots an `:instrument` under
  `instruments/`, a `:cljs` namespace under `cljs-src/` with a `.cljs`
  extension, and a `:cljc` one under `src/` with `.cljc`. Those are the only
  two declarations an export carries that an importer could recover, because
  they are the only two the projection spends part of the FILENAME on.

  Everything else a store declares — purity tiers, structured config, the
  module manifest — is absent by design rather than by oversight. An export
  is one-way; import exists so an external tool's edits return as ordinary
  form changes, and on that path a store keeps its own declarations because
  nothing removed them.

  PLATFORM is namespace-grain (`slopp.api.contracts :cljc` is a real
  declaration) so it reads straight off the path. ROLE is module-grain,
  because that is the grain `source-path` looks it up at, and a
  namespace-grain role would simply not be found — so it folds through
  `module-of`. A test namespace never contributes a role: `source-path` asks
  `test?` FIRST, so an instrument's test sits under `test/` and its path
  carries no role to read."
  [paths]
  (reduce
   (fn [acc p]
     (if-let [ns-sym (path-ns p)]
       (let [s    (str p)
             root (first (str/split s #"/"))
             ext  (last (str/split s #"\."))
             pf   (cond (= "cljs" ext) :cljs
                        (= "cljc" ext) :cljc)]
         (cond-> acc
           pf (assoc-in [:platforms (str ns-sym)] pf)
           (= "instruments" root)
           (assoc-in [:roles (str (edit.modules/module-of ns-sym))] :instrument)))
       acc))
   {:platforms {} :roles {}}
   paths))

(defn clone!
  "Clone git remote `url` into `dir` as a fileless slopp store: fetch the
  slopp-owned branch (`:branch`, else \"slopp\", else the legacy \"main\"),
  restore the deps manifest from the remote deps.edn, ingest every namespace
  through the verified write path (dependency order — reuses slopp.kernel.boot's
  require-graph sort), and record `git-remote`/`git-base-sha`
  so the projection grafts onto the remote's history and later syncs use the
  same branch. Ingest is byte-exact, so a fresh clone's live tree equals the
  remote tree (no phantom wip).
  Returns {:dir :namespaces n :base sha :branch b} | {:error msg}."
  [url dir & {:keys [token agent branch]}]
  (if (and (.exists (io/file dir ".slopp" "store.db"))
       (not (empty-store? dir)))
    {:error (str dir " already has a store — clone into a fresh dir")}
    (let [repo (git/open-repo! nil)]
      (try
        (let [want (or branch "slopp/main")
              {:keys [tip]} (git.client/fetch-remote! repo url :token token :branch want)
              used want]
          (if-not tip
            {:error (str "remote has no " want " branch to clone: " url)}
            (let [tree    (git/tree-at repo tip)
                  sources (into {}
                                (keep (fn [[path text]]
                                        (when-let [ns-sym (path-ns path)]
                                          [ns-sym text])))
                                tree)
                  deps    (or (some-> (get tree "deps.edn")
                                      edn/read-string :deps)
                              {})]
              (if (empty? sources)
                {:error (str "nothing to ingest at " url
                             " — no src/**.clj or test/**.clj on " used)}
                (let [sess (external/open! {:slopp.ops/dir dir})]
                  (try
                    (doseq [[lib coord] (sort-by (comp str key) deps)]
                      (let [r (ops/deps-add! sess lib coord :agent agent
                                             :prompt (str "clone: dep from " url))]
                        (when (:error r)
                          (throw (ex-info (str "dep " lib ": " (:error r)) {})))))
                    ;; The two declarations the tree's PATHS carry, replayed
                    ;; through the ordinary verbs so they land as the deltas an
                    ;; agent's own declaration would — and replayed BEFORE the
                    ;; ingests, because each ingest asks `jvm-loadable?` of its
                    ;; namespace: a `.cljs` one declared afterwards was handed
                    ;; to the JVM oracle as Clojure, and the clone died inside
                    ;; it on `goog.object` (CI's via-slopp lane, importing
                    ;; slopp's own tree). `path-ns` captures the name and drops
                    ;; the root and the extension, which is exactly where role
                    ;; and platform live, so without this a cloned `.cljc`
                    ;; renders as `.clj` and an instrument materializes into
                    ;; `src/` and ships.
                    (let [{:keys [platforms roles]} (path-declarations (keys tree))]
                      (doseq [[n pf] (sort platforms)]
                        (ops/module-platform! sess n pf :agent agent
                                              :prompt (str "clone: " n " is ." (name pf)
                                                           " in the imported tree")))
                      (doseq [[m _] (sort roles)]
                        (ops/module-role! sess m :instrument :agent agent
                                          :prompt (str "clone: " m
                                                       " materializes under instruments/"))))
                    (swap! sess assoc :adopting? true)
                    (doseq [ns-sym (boot/dependency-order sources)]
                      (let [r (ops/ingest! sess ns-sym (get sources ns-sym)
                                           :agent agent
                                           ;; the ask that created it — the one
                                           ;; write here that had none, so an
                                           ;; imported form's first version answered
                                           ;; \"why does this exist\" with silence
                                           :prompt (str "imported from git "
                                                        (subs tip 0 (min 12 (count tip)))
                                                        " (" url ")"))]
                        (when (:error r)
                          (throw (ex-info (str ns-sym ": " (:error r)) {})))))
                    (swap! sess dissoc :adopting?)
                    (ops/adopt-modules! sess :agent agent)
                    (let [conn (:db @sess)]
                      (db/set-meta! conn "git-remote" (str url))
                      (doseq [[path text] tree
                              :when (and (nil? (path-ns path))
                                         (not= "deps.edn" path)
                                         ;; a RENDERING of store state is not a
                                         ;; file: `:config` entries are restored
                                         ;; below and the module manifest is
                                         ;; derived by adoption. Blobbing one
                                         ;; writes a second copy of a fact the
                                         ;; store holds, in a field the
                                         ;; projection reads beside the first —
                                         ;; and left `:config` EMPTY, so a fresh
                                         ;; clone of slopp's own repo declared no
                                         ;; app and its dev instance never came
                                         ;; up, silently.
                                         (not (store/projected-config-paths path)))]
                        (ops/file-put! sess path text :agent agent
                                       :prompt (str "clone: file from " url)))
                      (doseq [[path text] tree
                              :when (and (store/projected-config-paths path)
                                         (not= "modules" path))]
                        (ops/config-restore! sess path (store/parse-config :manifest text)
                                             :agent agent
                                             :prompt (str "clone: config from " url)))
                      (db/set-meta! conn "git-base-sha" tip)
                      ;; what git had before the import — the records answer
                      ;; an agent otherwise shells out for (eval27 opus)
                      (db/set-meta! conn "git-log" (pr-str (git/recent-commits repo tip 20)))
                      ;; A clone establishes the PROJECT, so it lands on the
                      ;; branch. Everything above went through the ordinary
                      ;; verified write path, which means it went to this
                      ;; session's thread — and a store whose main is empty is
                      ;; not a clone of anything: the next serve would decide
                      ;; the store was still empty and import all over again.
                      (branch/land-thread! sess))
                    ;; ACCOUNT for what was left behind. `:namespaces` counts what the clone
                    ;; decided to take, so on its own a lossy import announces success
                    ;; with a smaller number and nothing downstream can tell — which is
                    ;; how `path-ns` dropped every .cljc and every instrument for three
                    ;; weeks. A file slopp did not write is ordinary (build.clj, a
                    ;; README); saying which ones is what makes the ordinary case
                    ;; checkable instead of indistinguishable from the bug.
                    (let [ignored (vec (sort (remove path-ns (keys tree))))
                          ;; and for the DECLARATIONS it restored — a config path
                          ;; that blobbed is invisible in `:namespaces` and the
                          ;; store looks complete while every capability reads nil
                          restored (into (sorted-map)
                                         (for [[path text] tree
                                               :when (and (store/projected-config-paths path)
                                                          (not= "modules" path))]
                                           [path (count (:values (store/parse-config :manifest text)))]))
                          ;; ACCOUNT for the shape of what came in, not just the
                          ;; count. Adoption derives the manifest from the code
                          ;; as it stands, and an imported codebase can arrive
                          ;; tangled — a module cycle needs only a cross-module
                          ;; call in each direction, which is a codebase Clojure
                          ;; loads happily. Since declaring an edge that closes a
                          ;; cycle is refused, a clone is the one moment a knot
                          ;; can enter, and reporting `:namespaces n` alone lets
                          ;; it enter silently.
                          cycles (vec (:cycles (store/module-layers
                                                (:modules (:store @sess)))))]
                      (cond-> {:dir (str dir) :namespaces (count sources)
                               :base tip :branch used}
                                                (seq ignored) (assoc :ignored ignored)
                        (seq restored) (assoc :config restored)
                        (seq cycles) (assoc :cycles cycles)))
                    (catch clojure.lang.ExceptionInfo e
                      {:error (str "clone failed at " (ex-message e)
                                   " — partial store left at " dir
                                   "; delete it to retry")})
                    (finally (ops/close! sess))))))))
        (catch Exception e
          {:error (str "clone failed: " (ex-message e))})
        (finally (.close repo))))))

(defn import!
  "THE onboarding command: inside a git checkout (main checked out, the
  human's files on disk), build `.slopp/store.db` from the repo's slopp
  BRANCH — found on local heads or the checkout's remote-tracking refs — and
  configure the store to sync against the LOCAL repo (`git-remote \".\"`,
  resolved relative to the store dir). Only `.slopp/` is created; the
  working dir stays the human's checkout, and origin interaction stays with
  regular git. Returns {:dir :namespaces :base :branch :remote} | {:error}."
  [dir & {:keys [token branch agent]}]
  (if-not (.exists (io/file dir ".git"))
    {:error (str dir " is not a git checkout — clone the repo first"
                 " (or use clone <url> <dir> for a fresh fileless store)")}
    (let [r (clone! (str dir) (str dir) :token token :branch branch :agent agent)]
      (if (:error r)
        r
        (do (with-open [conn (db/open! dir)]
              (db/set-meta! conn "git-remote" "."))
            (assoc r :remote "."))))))

(defn maybe-auto-import!
  "Serve-time onboarding: when `dir` is a git checkout carrying a slopp
  branch and its store is absent or EMPTY, import the branch into the
  store — the zero-ceremony path for `git clone` then serve. Anything
  else (plain repos, stores with content, import failures) is a nil
  no-op; serving must never be blocked by this."
  [dir]
  (try
    (when (and (.exists (io/file dir ".git"))
               (or (not (.exists (io/file dir ".slopp" "store.db")))
                   (empty-store? dir))
               (slopp-branch? dir))
      (let [r (import! dir)]
        (when-not (:error r) r)))
    (catch Exception _ nil)))

(defn- apply-trees!
  "Absorb the difference between two trees into the live session: diff
  `treeM`→`treeT` (both plain {path content}), deps first, then namespaces in
  the incoming tree's dependency order, then close the episode with a
  commit-point. Conflicts land in quarantine (push blocks until resolved).

  `opts`: `:agent`, `:origin` (what the quarantined copy came FROM — a commit
  sha for a pull, a directory for an import), `:label` (the commit-point
  description), `:extra` (extra marker fields — a pull chains `:git-sha`), and
  `:retry` (the verb to name when the done gate refuses).

  **This is the whole of import, and none of it is git.** `store.merge` is
  three strings in and one out, `apply-ns!`/`apply-deps!`/`apply-files!` take
  plain maps, and the gate below is the ordinary one. A pull is one caller —
  it produces its two trees with `git/tree-at` — and a directory is another,
  which is the point: git became a CONSUMER of import rather than its
  definition."
  [session treeM treeT {:keys [agent origin label extra retry]}]
  (let [conn    (:db @session)
        changed (into []
                      (comp (distinct)
                            (filter #(not= (get treeM %) (get treeT %))))
                      (concat (keys treeM) (keys treeT)))
        results (volatile! {:applied [] :conflicts [] :notes []})
        conflict! (fn [path ns-sym reason]
                    (db/quarantine-put! conn {:path path :ns ns-sym
                                              :source (get treeT path)
                                              :sha origin :reason reason})
                    (vswap! results update :conflicts conj
                            {:path path :reason reason}))
        applied!  (fn [n] (vswap! results update :applied conj n))
        note!     (fn [s] (vswap! results update :notes conj s))]
    (when (not= (get treeM "deps.edn") (get treeT "deps.edn"))
      (apply-deps! session treeM treeT conflict! agent))
    (apply-files! session treeM treeT changed conflict! note! agent)
    (let [by-ns (into {} (keep (fn [p] (when-let [n' (path-ns p)] [n' p]))) changed)
          srcs  (into {} (map (fn [[n' p]] [n' (or (get treeT p) (get treeM p) "")])) by-ns)]
      (doseq [ns-sym (boot/dependency-order srcs)
              :let [path (by-ns ns-sym)]]
        (apply-ns! session ns-sym path (get treeM path) (get treeT path)
                   conflict! applied! note! agent)))
    ;; NO :target. `commit_point :target` is a pure retroactive marker that runs
    ;; no done at all, so imported work — the one kind that never had to
    ;; satisfy a single gate on its way in — was the only work here closing
    ;; without the episode check. An external tool can write something that
    ;; loads and is still not valid slopp, and each form compiling is exactly
    ;; what the per-write verification already told us.
    ;;
    ;; The changes STAY applied on the branch on a red verdict, which is the
    ;; same place an agent's own red work sits: they arrived through the
    ;; ordinary verbs and are ordinary form edits. What is withheld is the
    ;; COMMIT-POINT, because that is what a push projects and nothing downstream
    ;; re-judges it — `push!` refuses unresolved conflicts and a checked-out
    ;; branch, and does not look at status at all.
    (let [m   (external/commit-point! session label :agent agent :extra extra)
          out {:pulled    (:applied @results)
               :conflicts (:conflicts @results)
               :notes     (:notes @results)}]
      (if (= :red (:status m))
        (assoc out
               :status   :red
               :findings (:findings m)
               :error
               (str "the imported changes ARE applied — "
                    (count (:applied @results))
                    " namespace(s), as ordinary form edits on this branch — and"
                    " the done gate REFUSED them, so no commit-point was recorded"
                    " and the import is not closed. " (:error m)
                    " Fix them here the way you would fix your own work, then"
                    " run " retry " again: the diff is already applied so it"
                    " re-applies nothing, and the commit-point it mints then"
                    " carries the marker this one could not."))
        (assoc out :marker (:commit m))))))

(defn- apply-pull!
  "The pull body once fetch/merge-base decided there IS something to absorb:
  produce the two trees from git and hand them to `apply-trees!`. The remote
  tip becomes a `:git-sha` chain node, so our next commit-point parents on it and
  pushes stay fast-forward.

  This is the git ADAPTER, and it is deliberately this thin: `tree-at` twice
  and a label. Everything import actually does is below it and touches no
  repository."
  [session ctx url mb tip agent]
  (let [repo (:slopp.git/repo ctx)]
    (assoc (apply-trees! session
                         (git/tree-at repo mb)
                         (git/tree-at repo tip)
                         {:agent  agent
                          :origin tip
                          :label  (str "pull " (subs tip 0 8) " from " url)
                          :extra  {:git-sha tip}
                          :retry  "git_pull"})
           :base tip)))

(defn pull!
  "Absorb the remote's changes since the last common point into the LIVE
  session: fetch, merge-base against our projected tip, 3-way apply at form
  granularity (remote wins where we're clean; both-touched → quarantined
  CONFLICT, our version stays live, push blocks until git_resolve), then
  record the remote tip as a `:git-sha` chain marker. Returns
  {:pulled [nses] :conflicts [{:path :reason}] :notes [..] :base tip :marker id}
  | {:up-to-date true} | {:error msg}."
  [session & {:keys [token agent]}]
  (let [dir (:dir @session)]
    (if-not dir
      {:error "pull needs a durable session (a store dir)"}
      (let [ctx (git/open-ctx! dir)]
        (try
          (let [url (resolve-remote dir (db/get-meta (:slopp.git/map-conn ctx) "git-remote"))]
            (if (str/blank? (str url))
              {:error "no remote configured — git_push with :url (or clone) first"}
              (let [ours (get-in (git/ensure-projected! ctx) [:refs "main"])
                    tip  (:tip (git.client/fetch-remote! (:slopp.git/repo ctx) url :token token
                                              :branch (str "slopp/" (:branch @session "main"))))]
                (cond
                  (nil? tip)   {:error (str "remote has no slopp/"
                                        (:branch @session "main")
                                        " branch: " url)}
                  (nil? ours)  {:error "nothing to pull onto — no local commit-points or clone base"}
                  (= tip ours) {:up-to-date true}
                  :else
                  (let [mb (git/merge-base (:slopp.git/repo ctx) ours tip)]
                    (cond
                      (nil? mb)  {:error "unrelated histories — was the remote rewritten? re-clone"}
                      (= mb tip) {:up-to-date true}
                      :else      (apply-pull! session ctx url mb tip agent)))))))
          (finally (git/close-ctx! ctx)))))))

(defn import-dir!
  "Absorb a DIRECTORY of files into the live session as ordinary tracked form
  edits — three-way against the store's last commit-point, through the same
  appliers and the same `done` gate a `git_pull` faces. **No git anywhere**:
  not in the source directory, not in the store.

  For the case export was always for — an agent handed a zip, a scratch tree,
  or another tool's output, which otherwise has no path into a store except
  re-ingesting namespaces by hand, losing both the three-way merge and the
  gate. `git_pull` is now one CALLER of this machinery rather than its
  definition.

  The BASE is the store's own last commit-point (`git/commit-point-tree`), which is
  \"the state this directory was exported from\" in the common case and the
  conservative answer otherwise: work the store did since that commit-point is
  ours-only and survives, where taking the CURRENT rendering as the base would
  make a stale directory silently revert it.

  Only paths the base already carries, or that resolve to a namespace, are
  read. A directory contains things an export never put there — editor
  droppings, build output, a `.git` — and absorbing them would write a second
  copy of facts the store holds semantically. Skipped paths are NOTED rather
  than silently dropped.

  Returns {:pulled [nses] :conflicts [...] :notes [...] :marker id} |
  {:error msg}."
  [session dir & {:keys [agent]}]
  (let [root (io/file (str dir))]
    (if-not (.isDirectory root)
      {:error (str dir " is not a directory")}
      (let [base (git/commit-point-tree
                  ;; the line's journal, read when asked — the value no longer
                  ;; carries it, and an import is asked for rarely
                  (db/line-deltas (:db @session)
                                  (or (:line @session) (db/trunk-line-id! (:db @session))))
                  #(db/get-blob (:db @session) %)
                  :dir (:dir @session))]
        (if (nil? base)
          {:error (str "nothing to import ONTO — this store has no commit-points,"
                       " so there is no base to merge against. commit_point"
                       " first, or use clone/import for a fresh store.")}
          (let [files    (filter #(.isFile ^java.io.File %) (file-seq root))
                rel      (fn [^java.io.File f]
                           (str/replace (.toString (.relativize (.toPath root)
                                                                (.toPath f)))
                                        java.io.File/separator "/"))
                keep?    (fn [p] (and (not (str/starts-with? p ".git/"))
                                      (not (str/starts-with? p ".slopp/"))
                                      (or (contains? base p) (path-ns p))))
                [in out] (reduce (fn [[in out] f]
                                   (let [p (rel f)]
                                     (if (keep? p)
                                       [(assoc in p (slurp f)) out]
                                       [in (conj out p)])))
                                 [(sorted-map) []] files)
                r        (apply-trees! session base in
                                       {:agent  agent
                                        :origin (.getAbsolutePath root)
                                        :label  (str "import " (.getAbsolutePath root))
                                        :retry  "import_dir"})]
            (cond-> r
              (seq out)
              (update :notes (fnil conj [])
                      (str (count out) " path(s) in the directory are neither in"
                           " the exported base nor a namespace, and were left"
                           " alone: " (str/join ", " (take 5 (sort out)))
                           (when (> (count out) 5) " …"))))))))))

(defn alignment-note
  "The sentence [[alignment]] reports, from facts already measured:
  `{:stamp :latest :aligned :projected?}`.

  Pure and separate because the interesting part is a DECISION, not the git
  plumbing that gathers it — and because the decision was wrong in a way no
  amount of testing the plumbing would have found.

  `:projected?` is the fact that was missing. `git_push` publishes a
  projection that EXISTS; it does not build one. On this store the note said
  *git_push publishes it*, the push ran with a real token, reported OK over
  133 commits, and alignment came back byte-identical — because the commit-point
  it names had no projection anywhere, and the sha it compares against was in
  no object database on the machine. \"Behind, and a push will catch it up\"
  and \"behind, and the commits it would need were never created\" are
  different facts that shared one sentence, and the sentence is
  instruction-shaped, so the reader acts on it.

  Three values, not two: `nil` means nothing could look, and is reported as
  neither built nor missing. Answering false would invent a claim; answering
  true is the confident report this whole class of bug is made of."
  [branch {:keys [stamp latest aligned projected?]}]
  (cond
    (and aligned stamp)
    (str "the " branch " branch head STAMPS commit-point " latest
         " — read off the commit itself, not a recorded sha; no"
         " worktree/sqlite cross-check needed")

    aligned
    (str "the " branch " branch head IS commit-point " latest
         "'s adopted commit; no worktree/sqlite cross-check needed")

    (false? projected?)
    (str "the " branch " branch head is commit-point " (or stamp "an earlier state")
         "'s projection, and commit-point " latest "'s commit is in NO reachable"
         " object database — so there is nothing for git_push to publish. A"
         " sha is pinned when a commit is MINTED, which says nothing about"
         " whether one was ever built here. git_push from a CHECKOUT mirrors"
         " the slopp/* branches that exist; it does not create a projection.")

    stamp
    (str "the " branch " branch head is commit-point " stamp
         "'s projection, not the latest (" latest ") — git_push publishes it")

    :else
    (str "the " branch " branch head carries no commit-point stamp and is not"
         " commit-point " latest "'s adopted commit — git_push publishes it")))

^:reads (defn alignment
  "Q12: PROOF that the published slopp branch is the store's latest
  commit-point — {:branch :branch-head :latest-commit-point :commit-point-sha
  :head-commit-point :aligned :note} — or nil when there is no resolvable LOCAL
  remote, branch, or commit-point. One call answers the cross-check agents
  otherwise perform by hand (throwaway worktrees, raw sqlite, duplicate
  test runs). `commits` = query-commits rows, newest first.

  It asks the branch HEAD which commit-point it is — `git/stamped-commit-point`
  reads the `Slopp-Commit:` trailer every projected commit carries — rather
  than comparing against the sha the store recorded. Those are different
  facts, and the difference is not hypothetical: a sha is pinned when a commit
  is MINTED, which says nothing about what was published. On 2026-08-14 a
  refused push left a pin naming a commit nobody had; the pin is
  first-writer-wins, so no later projection could correct it, and this read
  went permanently false against a mirror that WAS the latest commit-point's
  projection — while advising a `git_push` that could not fix it. A stamp
  rides the artifact and cannot disagree with the artifact.

  A head with NO stamp was not minted here: it is an ADOPTED remote commit
  from a pull, and for those the commit-point's `:sha` is the remote's own commit
  id — an observation rather than a mint record — so sha equality is the
  right question there, and only there."
  [dir remote branch commits]
  (try
    (when-let [target (and remote (resolve-remote dir remote))]
      (let [f    (io/file (str target))
            gitd (if (.exists (io/file f ".git")) (io/file f ".git") f)]
        (when (.exists (io/file gitd "HEAD"))
          (let [repo (-> (org.eclipse.jgit.storage.file.FileRepositoryBuilder.)
                         (.setGitDir gitd)
                         (.build))
                b    (or branch "slopp")]
            (try
              (when-let [head (.resolve repo (str "refs/heads/" b))]
                ;; the LATEST commit-point, not the latest one that happens to
                ;; carry a sha: a commit-point the projection never minted is
                ;; genuinely unaligned, and skipping to an older row reported
                ;; alignment against a commit-point nobody asked about.
                (when-let [latest (first commits)]
                  (let [head-sha (.name head)
                        stamp    (git/stamped-commit-point (git/message-of repo head-sha))
                        aligned  (if stamp
                                   (= stamp (:commit latest))
                                   (= head-sha (:sha latest)))
                        ;; Does the commit this is comparing against EXIST?
                        ;; `git_push` publishes a projection; it does not build
                        ;; one, so a commit-point nobody projected has nothing to
                        ;; publish and advising a push means a real push to a
                        ;; public repository that changes nothing. nil when
                        ;; there is no sha to ask about — "not measured" is not
                        ;; the same answer as "not there".
                        projected?
                        (when-let [sha (:sha latest)]
                          (try (.has (.getObjectDatabase repo)
                                     (org.eclipse.jgit.lib.ObjectId/fromString sha))
                               (catch Exception _ nil)))]
                    {:branch b :branch-head head-sha
                     :latest-commit-point (:commit latest)
                     :commit-point-sha (:sha latest)
                     ;; what the verdict was actually based on — a reader who
                     ;; cannot see WHICH commit-point the head claims to be has to
                     ;; go and do the cross-check this exists to replace
                     :head-commit-point stamp
                     ;; and whether the thing the remedy would publish is even
                     ;; there. Carried as data beside the sentence, so a reader
                     ;; who wants the fact does not have to parse prose for it.
                     :latest-projected projected?
                     :aligned aligned
                     :note (alignment-note b {:stamp stamp
                                              :latest (:commit latest)
                                              :aligned aligned
                                              :projected? projected?})}))) 
              (finally (.close repo)))))))
    (catch Exception _ nil)))

(defn test-args
  "The shard count `slopp.sync test <dir> [shards]` was given, as a number,
  or nil for the runner's own choice. Refuses a value that is not a positive
  integer rather than silently running serial: the whole reason the argument
  exists is that the runner's choice on a two-core CI box was one shard,
  and a typo that reproduced that would be indistinguishable from it."
  [_dir shards]
  (when shards
    (let [n (try (Long/parseLong (str shards)) (catch NumberFormatException _ nil))]
      (when-not (and n (pos? n))
        (throw (ex-info (str "slopp.sync test <dir> [shards]: " (pr-str shards)
                            " is not a shard count (a positive integer)")
                        {:shards shards})))
      n)))

(defn -main
  "clojure -M -m slopp.sync clone <url> <dir> | import <dir> | import-dir <store-dir> <from-dir> | push <dir> [url] | pull <dir> | test <dir>"
  [& [cmd a b]]
  (let [usage (str "usage: clone <url> <dir> | import <dir>"
                   " | import-dir <store-dir> <from-dir> | push <dir> [url]"
                   " | pull <dir> | test <dir> [shards]")
        r (case cmd
            "clone"  (clone! a b)
            "import" (import! (or a "."))
            ;; the git-free doorway, from a shell: the tool that PRODUCED the
            ;; directory is often a script, and it should not have to make a
            ;; repo to hand its work back
            "import-dir" (let [sess (external/open! {:slopp.ops/dir a})]
                           (try (import-dir! sess b)
                                (finally (ops/close! sess))))
            "push"   (push! a :url b)
            "pull"   (let [sess (external/open! {:slopp.ops/dir a})]
                       (try (pull! sess)
                            (finally (ops/close! sess))))
            "test"   (let [sess (external/open! {:slopp.ops/dir a})]
                       (try (external/external-test-run! sess :parallel (test-args a b))
                            (finally (ops/close! sess))))
            {:error usage})]
    (println (pr-str r))
    (shutdown-agents)
    (when (or (:error r) (false? (:ok r)) (= :red (:status r))) (System/exit 1))))
