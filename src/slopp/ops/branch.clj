(ns slopp.ops.branch
  "BRANCHES as slopp has them: one store, many lines, each with its own image.

  A branch here is not a checkout. The store is a delta log, so a line is a
  view of that log and switching is a matter of which deltas are in scope —
  there is no working tree to swap. What a line DOES need is an image of its
  own, because code is loaded state: two lines with different definitions of
  the same var cannot share one JVM.

  That is where the cost sits, and where the bugs are. A line image is a fourth
  image launcher alongside session open, `fresh-image!` and the warm spare, and
  it is the one nobody exercises casually — so anything that must be true of
  every image is least likely to be true here. It reaches
  `session/start-image!` for exactly that reason; a launch assembled locally
  would be correct on the day it was written and quietly wrong afterwards.

  Lines are reaped on idle (the session's timer), because an abandoned branch
  should not hold a JVM open indefinitely."
  (:require [clojure.java.io :as io]
            [slopp.ops.engine :as engine]
            [slopp.store.db :as db]
            [slopp.edit :as edit]
            [slopp.image :as image]
            [slopp.image.repl :as repl]
            [slopp.store :as store] [slopp.store.merge :as merge] [clojure.string :as str] [slopp.read.modules :as read.modules] [slopp.read.history :as history]))

(defn merge-into-session!
  "Shared merge pipeline (m2 forks + m3 branches): replay `theirs` onto the
  session store (store/merge-logs), hot-load what arrived (new namespaces in
  dependency order, then changed forms through the compile gate), commit,
  persist, verify every touched namespace, and record ONE `:merge` delta."
  [session theirs from-label]
  (let [t0   (System/nanoTime)
        ;; OUR side, hydrated: the merge folds both logs from the fork point,
        ;; and the live value carries none — the list is read here, for this
        ;; merge, and `committed` drops it from what lands
        base (assoc (:store @session) :deltas
                    (db/line-deltas (:db @session) (engine/session-line session)))
        r    (merge/merge-logs base theirs :from from-label)]
    (cond
      (:error r)
      ;; the engine's own error (identity mismatch) speaks for itself —
      ;; checking fork-point first once masked it as "no shared history"
      (select-keys r [:error :fork-point])

      (nil? (:fork-point r))
      {:error "stores share no history — not a fork/branch of this project"}

      (and (zero? (:merged r)) (empty? (:conflicts r)))
      {:merged 0 :conflicts [] :note "already converged — nothing to merge"}

      :else
      (let [touched  (filter #(contains? (:namespaces (:store r)) %)
                              (distinct (concat (keep :ns (:pending (:store r)))
                                                (:new-nses r))))
            ;; ARRANGE before judging: a merge replays content and ranks, never
            ;; an arrangement (there is no :move op), so every namespace the
            ;; merge touched is derived here exactly as a write would derive it
            ;; — and a genuine cycle two lines composed gets its declare here
            st'      (reduce (fn [s n] (or (:store (edit/resolve-cold-load s n)) s))
                             (:store r) touched)
            load-err (or ;; cold-load first (S1b): two individually-legal lines can
                      ;; interleave into a forward ref — refuse before the
                      ;; image is touched
                      (let [new-deps (reduce dissoc (:deps st')
                                             (keys (:deps base)))]
                        ;; deps the merge brings (a branch's deps_add): their
                        ;; jars must reach the image BEFORE the load phase, or
                        ;; every merged ns requiring them fails FileNotFound
                        ;; (the wave-2 http-kit merge)
                        (when (seq new-deps)
                          (when-let [r (repl/add-libs! (:image @session)
                                                       new-deps)]
                            (str "the merge brings dependencies the image"
                                 " could not hot-add: " (pr-str new-deps)
                                 " — " (:err r)))))
                      (edit/cold-load-errors st' touched)
                      ;; ONE dependency-ordered pass, new-ns loads and
                      ;; changed-form hot-loads INTERLEAVED: a new ns that
                      ;; calls forms the merge just added to an EXISTING
                      ;; upstream ns must compile against those forms, so
                      ;; the upstream's changes reach the image first
                      ;; (loading all new nses before any changed form is
                      ;; how a merged wave failed on its own new gates)
                      (let [new?  (set (:new-nses r))
                            ;; a form added AND deleted on their side leaves
                            ;; a DEAD id in changed-form-ids — nothing to
                            ;; load (the wave-1 merge NPEd on exactly this)
                            live  (filterv #(store/form-by-id st' %)
                                           (:changed-form-ids r))
                            by-ns (group-by #(store/ns-of-form-id st' %)
                                            live)]
                        (or (some (fn [ns-sym]
                                    (cond
                                      (new? ns-sym)
                                      (image/load-ns! (:image @session) st' ns-sym)

                                      (seq (get by-ns ns-sym))
                                      (engine/load-error-message
                                       (engine/hot-load-all! session st'
                                                              (get by-ns ns-sym)))

                                      :else nil))
                                  (store/ns-dependency-order st'))
                            ;; live ids whose ns lookup still missed keep
                            ;; the whole-batch path
                            (when-let [rest-ids (seq (get by-ns nil))]
                              (engine/load-error-message
                               (engine/hot-load-all! session st' rest-ids))))))]
        (if load-err
          (do (engine/fresh-image! session)
              (edit/compile-error st' load-err "merge failed to compile: "))
          (let [[st'' mdelta] (merge/record-merge st' from-label r)]
            (if-not (engine/try-commit! session base st''
                                 (vec (distinct
                                       (concat (keep :ns (:pending st''))
                                               (:new-nses r)))))
              {:conflict {:reason "store changed during merge — retry"}}
              (let [;; what the merge appended: the value's pending suffix, never a
                    ;; drop-by-count over two whole lists
                    new-deltas   (:pending st'')
                    touched-nses (vec (distinct
                                       (concat (keep :ns new-deltas)
                                               (:new-nses r))))
                    edited       (into #{}
                                       (keep (fn [id]
                                               (when-let [e (store/form-by-id st'' id)]
                                                 (symbol (str (store/ns-of-form-id st'' id))
                                                         (str (or (:name e) (:id e)))))))
                                       (:changed-form-ids r))
                    verify-nses  (vec (remove #{'*session*} touched-nses))
;; the cycle note merge-logs cannot make: it sees only the
                    ;; DECLARED manifest, where a -test namespace's fixture
                    ;; requires are edges, so it warned on every merge into
                    ;; main about a cycle no production code had.
                    mod-cycle    (read.modules/merge-production-cycle base st'')
                    notes        (cond-> (vec (:notes r))
                                   mod-cycle
                                   (conj {:modules-cycle mod-cycle
                                          :reason (str "the merged module graphs form a"
                                                       " cycle neither side saw — retract"
                                                       " an edge (module_dep {from .. to .."
                                                       " remove true})")}))
                    summary      (when (seq verify-nses)
                                   (engine/run-verification! session verify-nses nil
                                                      :edited edited))]
                (when summary
                  (engine/commit-appended! session
                                    #(store/record-verification % verify-nses summary)
                                    []))
                (engine/with-ms
                  (cond-> {:merged     (:merged r)
                           :conflicts  (:conflicts r)
                           :merge-delta (:id mdelta)}
                    (seq (:new-nses r)) (assoc :new-nses (:new-nses r))
                    (seq notes)         (assoc :notes notes)
                    summary             (assoc :test summary))
                  t0)))))))))

(defn boot-line-image!
  "A fresh image loaded with `store` (consumes the warm spare when ready).
  Returns {:image handle} or {:error msg}."
  [session store]
  (let [spare (:spare @session)
        ;; through the one door, and this path is why the door exists: it was the
        ;; fourth image launcher and the one nobody wired the framework into,
        ;; because nothing exercises branches and web code together. It would
        ;; have failed exactly as restart did.
        img   (engine/image-with-deps! session store spare)]
    (when spare
      (swap! session assoc :spare nil)
      (engine/start-spare! session))
    (if-let [err (some #(image/load-ns! img store %)
                       (store/ns-dependency-order store))]
      (do (repl/stop! img)
          {:error (str "branch image failed to load: " err)})
      {:image img})))

(defn ^:export merge!
  "Phase 4 m2: merge a DIVERGED COPY of this project back into the live
  session. A 'fork' is just a copied project dir edited by its own slopp
  server; `other-dir` is that copy. Their delta-log suffix replays onto our
  store: different-form work lands, identical changes converge, same-form
  divergence returns `:conflicts` (ours kept, theirs surfaced — resolve by
  hand with edit_replace_form)."
  [session other-dir]
  (let [f    (io/file (str other-dir))
        db-f (io/file f ".slopp" "store.db")]
    (cond
      (not (.isAbsolute f))
      {:error "merge needs an ABSOLUTE project-dir path"}

      (not (.exists db-f))
      {:error (str "no slopp store under " other-dir)}

      :else
      (let [conn   (db/open! (str f))
            theirs (try (db/load-store-with-history conn (slopp.store.db/trunk-line-id! conn))
                        (finally (.close ^java.sql.Connection conn)))]
        (merge-into-session! session theirs (str other-dir))))))

(defn ^:export branch!
  "Create branch `nm` from the CURRENT line and switch to it. O(1) in the
  journal — a row plus a copy of the materialization, never a copy of the
  history — and free in the image, which already holds identical content.

  A branch is a NAMED LINE. It used to be a separate db file under
  `.slopp/branches/<nm>` with the whole store snapshotted into it, because
  `elements` held one view per file and the write CAS ran on the global
  journal head. Neither is true any more, so the file was the last thing
  making a branch expensive.

  **The name race is settled by the db.** `lines.name` is UNIQUE, so two
  servers creating the same branch means one INSERT throws and one wins —
  replacing a mkdir used as a mutex, which is what claiming a PATH was doing.
  The in-process claim below still comes first, because two threads in ONE
  session must not both reach the db, and because an ephemeral session has no
  journal for a line to point into and the claim is all it has."
  [session nm]
  (let [nm   (str nm)
        conn (:db @session)
        {:keys [branch lines]} @session]
    (cond
      (str/blank? nm)
      {:error "branch needs a name"}

      (= nm "main")
      {:error "main is the trunk — branch FROM it"}

      (or (= nm branch) (contains? lines nm))
      {:error (str "branch " nm " already exists")}

      :else
      (let [[old _] (swap-vals! session
                                (fn [s]
                                  (if (or (= nm (:branch s))
                                          (contains? (:lines s) nm))
                                    s
                                    (update s :lines assoc nm ::claimed))))]
        (if (or (= nm (:branch old)) (contains? (:lines old) nm))
          {:error (str "branch " nm " already exists")}
          (let [cur (engine/session-line session)
                id  (try
                      (if conn
                        (db/create-line! conn {:name   nm
                                               :kind   "branch"
                                               :base   (db/line-head conn cur)
                                               :parent cur
                                               :agent  (:agent-id @session)})
                        ;; ephemeral: an id with no row, so a branch still has
                        ;; an identity distinct from its name
                        (str (java.util.UUID/randomUUID)))
                      (catch java.sql.SQLException _ nil))]
            (if-not id
              (do (swap! session update :lines dissoc nm)   ; release the claim
                  {:error (str "branch " nm " already exists")})
              (do (swap! session
                         (fn [s]
                           (-> s
                               (update :lines dissoc nm)    ; claim → active
                               (update :lines assoc (:branch s)
                                       {:store (:store s) :id cur})
                               (assoc :branch nm
                                      ;; a branch is somewhere to land, not
                                      ;; somewhere to write: the session takes
                                      ;; a thread ON the new branch, the same
                                      ;; way it holds one on main. An ephemeral
                                      ;; session has no journal to hold threads
                                      ;; in, so its line is the branch itself.
                                      :line (if conn
                                              (db/adopt-thread!
                                               conn id (:agent-id @session))
                                              id)))))
                  {:branch nm :from branch :id id}))))))))

(defn ^:export branch-delete!
  "Drop branch `nm` (never the one you are on): its parked image, its line row
  and its materialization.

  The DELTAS stay, unreachable from any head. Deleting a branch used to mean
  deleting a directory that held a whole copy of the store, so it destroyed
  history; now it drops a pointer, and the work it named remains in the
  journal for anything that still knows a delta id."
  [session nm]
  (let [nm   (str nm)
        conn (:db @session)
        {:keys [branch lines]} @session
        row  (when conn (first (filter #(= nm (:name %)) (db/lines conn))))]
    (cond
      (= nm branch)
      {:error "cannot delete the branch you are on"}

      (not (or (contains? lines nm) row))
      {:error (str "no branch named " nm)}

      :else
      (do (some-> (get-in lines [nm :image]) repl/stop!)
          (swap! session update :lines dissoc nm)
          (when row (db/delete-line! conn (:id row)))
          {:deleted nm}))))

(defn ^:export query-branches
  "Every line in the repo: the current one, this session's parked lines, and
  the named lines in the journal it has not loaded.

  The third group used to be a directory listing of `.slopp/branches/`. It is
  a SELECT now, which is why a branch another server created shows up here
  without either process touching the filesystem — they share one journal, and
  a line is a row in it."
  [session]
  (let [{:keys [branch lines store]} @session
        conn    (:db @session)
        rows    (when conn (filterv :name (db/lines conn)))
        by-name (into {} (map (juxt :name identity)) rows)
        info    (fn [nm st line]
                  (cond-> {:name nm}
                    st (assoc :head   (:head st)
                              :deltas (:line-pos st 0))
                    (:id line) (assoc :id (:id line))
                    (:image line) (assoc :image :parked)))]
    {:current  branch
     :branches (vec (concat
                     [(let [bid (engine/session-branch-line session)]
                        ;; the BRANCH's id and head, not the session's. Those
                        ;; are the same line until a thread is adopted, and
                        ;; after that reporting the session's would print a
                        ;; private line's identity under a branch's name — and
                        ;; a head containing work the branch does not have.
                        (cond-> (assoc (info branch store {:id bid}) :image :live)
                          conn (assoc :head (db/line-head conn bid))))]
                     (for [[nm line] (sort-by key lines) :when (map? line)]
                       (info nm (:store line) line))
                     (for [nm (sort (remove (set (conj (keys lines) branch))
                                            (keys by-name)))]
                       {:name nm :id (:id (by-name nm))})))}))

^:reads (defn line-view
  "A line's `{:store :id :image}` by NAME — from the session's parked entry if
  it has one, else loaded from its row in the journal. nil if no such line.

  This used to OPEN a db file under `.slopp/branches/<name>`. A line is a row
  now, so the lookup happens in the store the session already has open, and
  the id it carries is the branch's identity — which used to be a meta row
  inside the branch's own file, i.e. a fact a store could only state about
  itself.

  A parked value used to need the FILE's id counter grafted onto it here: ids
  were minted from the store value, so a parked one had stopped counting and
  adopting it unchanged re-minted ids another line had used since. Ids are
  random names now, minted per call from nothing the value carries, so a
  parked store and a freshly loaded one are the same kind of thing again and
  this is no longer the one place the two paths differ."
  [session nm]
  (let [conn   (:db @session)
        parked (get (:lines @session) nm)]
    (if (map? parked)                    ; ::claimed is a name mid-creation
      parked
      (when conn
        (when-let [row (first (filter #(= nm (:name %)) (db/lines conn)))]
          {:store (db/load-store conn (:id row)) :id (:id row)})))))

(defn ^:export branch-switch!
  "Checkout with LINE-OWNED images: the outgoing line PARKS its image intact
  (its REPL state included — inactive lines are immutable, so a parked image
  stays in step by construction); the target ADOPTS its parked image if it
  still has one, else BOOTS a fresh one on demand (the warm spare makes that
  cheap). Parked images retire after the session's idle TTL. The trace map
  resets — it described the other line.

  What moves is the session's LINE, not its connection. There is one db, so a
  checkout is now purely a question of which line this session reads and
  writes; `data-version` is deliberately not touched, because a switch cannot
  change the version of a connection it did not change.

  The line it moves to is the session's THREAD on that branch, not the branch
  itself — every branch gets the same isolation main has. The store has to
  come from the same place: a parked store describes the line it was parked
  FROM, and re-adoption hands that line back while the thread is still open,
  so the ordinary switch reuses everything. A thread that has been landed, or
  one another process advanced, is a different line, and its store is read
  rather than assumed."
  [session nm]
  (let [nm (str nm)]
    (if (= nm (:branch @session))
      {:switched nm :note "already on it"}
      (if-let [target (line-view session nm)]
        (let [conn    (:db @session)
              thread  (when conn
                        (db/adopt-thread! conn (db/line-id-by-name conn nm)
                                          (:agent-id @session)))
              same?   (or (nil? conn) (= thread (:id target)))
              store   (if same? (:store target) (db/load-store conn thread))
              adopted (when same? (:image target))
              booted  (when-not adopted (boot-line-image! session store))]
          (if (:error booted)
            booted
            (do (swap! session
                       (fn [s]
                         (-> s
                             (update :lines assoc (:branch s)
                                     {:store     (:store s)
                                      :id        (:line s)
                                      :image     (:image s)
                                      :last-used (System/currentTimeMillis)})
                             (update :lines dissoc nm)
                             (assoc :branch nm
                                    :line (or thread (:id target))
                                    :store store
                                    :image (or adopted (:image booted))
                                    :test-map {}))))
                (cond-> {:switched nm}
                  adopted       (assoc :adopted true)
                  (not adopted) (assoc :booted true)))))
        {:error (str "no branch named " nm)}))))

(defn ^:export branch-merge!
  "Merge branch `nm` into the CURRENT line (switch to main first to merge
  down). Same engine and semantics as fork merges, iterated merges included;
  the branch survives and can keep going.

  There is nothing to close afterwards: the other line lives in this file, so
  reading it opens no connection of its own."
  [session nm]
  (let [nm (str nm)]
    (if (= nm (:branch @session))
      {:error "cannot merge a branch into itself — switch to the target line first"}
      (if-let [target (line-view session nm)]
        (merge-into-session! session
                             ;; the merge folds their history — read for this
                             ;; merge; a line's loaded value carries none
                             (assoc (:store target) :deltas
                                    (db/line-deltas (:db @session) (:id target)))
                             (str "branch:" nm "#" (or (:id target) "unknown")))
        {:error (str "no branch named " nm)}))))

(defn ^:export land-thread!
  "Land the session's thread onto its branch, and put the session on a fresh
  thread forked at the new head. nil when there is nothing to land — an
  ephemeral session, a session that is not on a thread, or a thread nobody
  has written to.

  Two cases that compose into one loop:

  - **The branch has not moved** (its head is still the thread's fork point).
    A pure fast-forward: `db/land-thread!` advances the head, replaces the
    branch's view and settles the thread, all in one transaction. Nothing is
    merged and NOTHING IS RE-VERIFIED, because the content is byte-identical
    to what the caller just graded.
  - **The branch moved** — somebody else landed while this thread worked. The
    branch is merged INTO the thread first, through the same pipeline a
    `branch_merge` uses, and then the WHOLE in-image suite is re-run against
    the merged state. The thread's head then has the branch's head in its
    ancestry, so the second half is the fast-forward case. Conflicts or a red
    rebase land NOTHING and leave the thread open holding the merged state:
    the agent resolves and calls done again. Resolving is a REWRITE: each
    form under :conflicts shows both sides, and a new version written after
    the refusal is what the next land treats as the resolution (the merge
    remembers what it surfaced — the same conflict does not recompute).

  The re-run is the whole suite and not the merge's own scope, and the
  difference is the point. The merge verifies the namespaces it CARRIED; the
  test that breaks is normally in the namespace that CALLS them, which the
  merge never names. Verifying only what arrived reports green about a branch
  where nothing passes — the one failure here that a verdict would hide rather
  than show.

  Losing the CAS is a RETRY, not a failure — another agent landed between the
  reconcile and the advance, which is the ordinary shape of a shared branch
  rather than an error. Bounded, because a branch under continuous landing
  should say so rather than spin.

  A thread whose head still equals the branch's is not landed and not settled.
  Otherwise every done with nothing written would burn a thread and mint
  another, and `used_at` would stop meaning what it says."
  [session]
  (let [conn (:db @session)
        line (engine/session-line session)
        row  (when conn (first (filter #(= line (:id %)) (db/lines conn))))
        ;; The session names a line the registry does not have. Every other
        ;; quiet outcome here returns nil, and this one used to as well —
        ;; which is how work went missing twice in one wave: `done!` assocs
        ;; `:land` only when it is truthy, so a session that could not find
        ;; its thread reported a clean green with no `:land` key, exactly the
        ;; shape of a done with nothing to land.
        lost (boolean (and conn line (nil? row)))]
    (when (or lost (= "thread" (:kind row)))
      (let [branch-id (engine/session-branch-line session)
            branch-nm (:branch @session)]
        (loop [reconciled nil, tries 0, rebase nil]
          (let [bh (db/line-head conn branch-id)
                th (db/line-head conn line)]
            (cond
              lost
              {:landed false
               :reason (str "this session is on thread " line ", which is not in"
                            " the store's line registry — nothing can be landed"
                            " from it. The writes made on it are in the journal"
                            " but are NOT on " branch-nm ". Restart the slopp"
                            " server so it re-reads the registry, then re-apply"
                            " the writes made since the last successful done;"
                            " `query_changes` names them.")}

              (= th bh)
              nil

              (< 3 tries)
              {:landed false
               :reason (str branch-nm " moved under every attempt to land — it is"
                            " being written continuously; call done again")}

              (or (= bh (:base row)) (= bh reconciled))
              (if (db/land-thread! conn line branch-id bh)
                (let [fresh (db/adopt-thread! conn branch-id (:agent-id @session))]
                  (swap! session assoc :line fresh)
                  (cond-> {:landed branch-nm :head th :thread fresh}
                    rebase (assoc :rebased rebase)))
                (recur reconciled (inc tries) rebase))

              :else
              (let [;; The id counter belongs to the FILE, and this session stopped
                    ;; counting when the other agent started. The merge is about
                    ;; to mint deltas from a value that predates theirs, and
                    ;; `deltas.id` is UNIQUE across the journal — so without this
                    ;; every rebase onto somebody else's work loses its own commit
                    ;; as a duplicate id, retries with the same stale counter, and
                    ;; reports the bound as \"the branch is being written
                    ;; continuously\". Refreshing raises the floor. It is the hazard
                    ;; per-line CAS created, arriving at the one path whose whole
                    ;; job is to cross lines.
                    _ (engine/refresh-cache! session)
                    m (merge-into-session! session (db/load-store-with-history conn branch-id)
                                           (str "branch:" branch-nm "#" branch-id))]
                (cond
                  (:error m)
                  {:landed false :reason (:error m)}

                  (:conflict m)
                  (recur reconciled (inc tries) rebase)

                  (seq (:conflicts m))
                  {:landed false :conflicts (:conflicts m)
                   :reason (str branch-nm " moved while you worked, and rebasing onto"
                                " it conflicts. Each form under :conflicts shows"
                                " BOTH sides — REWRITE it to the version you"
                                " intend (usually a merge of both), then call"
                                " done again; your rewrite IS the resolution.")}

                  ;; RE-EARN the verdict over the WHOLE suite, not just what the merge
                  ;; carried. The two are almost never the same set, and the
                  ;; difference IS the failure: your code calls theirs, so THEIR
                  ;; namespace is what merged and YOURS is what breaks — a
                  ;; merge-scoped check runs their tests, which pass, and reports
                  ;; green about a branch where nothing does. `done`'s verdict was
                  ;; earned against the code this thread forked from, and that is no
                  ;; longer what the branch holds.
                  ;;
                  ;; In-image scope, said plainly rather than left to be discovered:
                  ;; this is the suite `done` runs, re-run against the merged state.
                  ;; The impacted ^:external slice is NOT repeated — done already ran
                  ;; it, and doubling the most expensive part of a done every time
                  ;; somebody else lands first would make a busy branch cost more to
                  ;; join than to work on.
                  :else
                  (let [st'  (:store @session)
                        dead (set (map :ns (:image-load-failures @session)))
                        nses (vec (sort (remove dead (keys (:namespaces st')))))
                        s    (engine/run-verification! session nses nil)]
                    (if (or (pos? (:fail s 0)) (pos? (:error s 0)))
                      {:landed false :test s
                       :reason (str branch-nm " moved while you worked, and your work"
                                    " is red against it — the rebase is IN your"
                                    " thread, so fix it there and call done again")}
                      (recur bh (inc tries)
                             (assoc (merge rebase
                                           (select-keys m [:merged :new-nses :merge-delta]))
                                    :test s)))))))))))))

^:reads (defn ^:export thread-list
  "The live threads on this session's branch — who holds one, how much they
  have written since forking, and how long it has been since anybody touched
  it. The session's own is marked `:mine`.

  Across ALL agents on purpose. The question a listing answers is \"is there
  work here nobody is going to finish\", and an idle thread is by definition
  somebody else's — an agent-scoped version could only ever say yes about
  itself.

  `:idle-ms` is age, not a verdict. Nothing reaps a thread automatically: a
  line holding un-landed work is the one thing in this system that no rule
  should be allowed to throw away on a timer, so the drop stays a decision
  somebody makes.

  `:held` is whether a LIVE process has the write lease on that row, which is
  a different question from `:idle-ms` and usually the one being asked. Idle
  says nobody has touched it lately; held says somebody still could. A thread
  that is idle AND unheld is the one nobody is coming back to — and that pair,
  rather than age alone, is what makes a drop an informed decision instead of
  a guess about somebody else's session."
  [session]
  (if-let [conn (:db @session)]
    (let [branch (engine/session-branch-line session)
          mine   (:line @session)
          now    (System/currentTimeMillis)]
      {:branch  (:branch @session)
       :threads (mapv (fn [t]
                        (cond-> {:id       (:id t)
                                 :agent    (:agent t)
                                 :unlanded (db/unlanded-count conn (:id t)
                                                              history/content-ops)
                                 :idle-ms  (- now (or (:used-at t) now))
                                 ;; a LIVE process is writing this one. Not the
                                 ;; same question as `:idle-ms`, and the more
                                 ;; useful one: idle says nobody has touched it
                                 ;; lately, held says somebody still could.
                                 :held     (db/process-live? (:owner-pid t)
                                                             (:owner-started t))}
                          (= (:id t) mine) (assoc :mine true)))
                      (db/open-threads conn branch))})
    {:threads [] :note "an ephemeral session has no journal, so it holds no threads"}))

(defn ^:export thread-drop!
  "Abandon a thread on this session's branch: its view is reclaimed, its
  status is settled, and its deltas stay walkable. No `id` means YOUR OWN —
  the start-over case, which is the usual reason to be here.

  This is NOT `undo` or `episode_revert`, and the difference is a guarantee
  worth choosing between. Those are forward-only: they append revert deltas,
  so the work stays in your line's history and stays findable. This appends
  nothing — the line is settled and its work becomes unreachable from any
  branch head. Same intent, different promise. And this one is not bounded by
  an episode: a thread holds everything since the last thing that LANDED, so
  it covers several red done points, which is exactly when \"start me over\"
  gets asked.

  Dropping your OWN thread is the interesting case, because it
  is not finished until the session stops showing the work: `adopt-line!`
  puts it on a fresh thread and reloads both the store and the image from it.
  Without that the store would go on rendering code no line holds, which is a
  worse state than the one being cleaned up.

  Every refusal names what the id actually IS. A branch reached through here
  is the likeliest mistake and it has its own verb; an already-settled thread
  is not an error worth stopping for, but saying which settlement it got is
  the difference between \"already gone\" and \"landed, and you are looking for
  the wrong thing\"."
  [session id]
  (if-let [conn (:db @session)]
    (let [;; No id means YOUR thread. "I have gone the wrong way, start me over"
          ;; is the common reason to reach for this, and making it cost a
          ;; thread_list call plus a copied UUID puts the friction exactly
          ;; where somebody is already frustrated.
          id     (or id (engine/session-line session))
          branch (engine/session-branch-line session)
          row    (first (filter #(= id (:id %)) (db/lines conn)))]
      (cond
        (nil? row)
        {:error (str "no line " id " in this store — thread_list shows what is here")}

        (not= "thread" (:kind row))
        {:error (str id " is the branch \"" (:name row) "\", not a thread"
                     " — branch_delete removes a branch")}

        (not= branch (:parent row))
        {:error (str "thread " id " is not on " (:branch @session)
                     " — switch to its branch to drop it")}

        (not= "open" (:status row))
        {:error (str "thread " id " is already " (:status row))}

        :else
        (let [n (db/unlanded-count conn id history/content-ops)]
          (db/abandon-thread! conn id)
          (if (= id (:line @session))
            (do (swap! session dissoc :line)
                {:dropped id :unlanded n :thread (engine/adopt-line! session)
                 :note (str "that was YOUR thread — you are on a fresh one, and those "
                            n " write(s) are off your store and image."
                            " The deltas are still in the journal")})
            {:dropped id :unlanded n :agent (:agent row)}))))
    {:error "an ephemeral session has no threads to drop"}))
