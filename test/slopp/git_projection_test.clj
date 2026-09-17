(ns slopp.git-projection-test
  "P4-m8: git compatibility layer, projection core. A commit point is a bare
  marker — slopp.git DERIVES its tree by folding the journal up to it, then
  projects deterministically into an in-memory repo (no on-disk repo): same
  journal, same shas, every time. Native d<n> ids stay authoritative; git_map
  pins each marker's sha at first projection.

  The marker used to carry a byte-exact rendered :tree instead, because
  comments lived positionally and could not be reconstructed. They are
  form-owned content now, so folding the log is exact and the snapshot — 39%
  of this journal — is gone."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [slopp.ops :as ops]
            [slopp.store.db :as db]
            [slopp.git :as git]
            [slopp.store :as store] [slopp.ops.branch :as branch] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.store.render :as store.render] [slopp.store.artifacts :as artifacts] [slopp.ops.engine :as engine])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.lib ObjectId Repository]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.treewalk TreeWalk]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-git-test" (make-array FileAttribute 0))))

(defn- rm-rf! [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
    (.delete f)))

(def seed
  (str "(ns gp.core (:require [clojure.test :refer [deftest is]]))\n"
       "\n"
       ";; top-level trivia must survive projection\n"
       "(defn f [x] (+ x 10))\n"
       "\n"
       "(deftest f-t (is (= 11 (f 1))))\n"))

(defn- commit-info [^Repository repo sha]
  (with-open [rw (RevWalk. repo)]
    (let [c (.parseCommit rw (ObjectId/fromString sha))
          a (.getAuthorIdent c)]
      {:message (.getFullMessage c)
       :author  (.getName a)
       :email   (.getEmailAddress a)
       :at-ms   (.toEpochMilli (.getWhenAsInstant a))
       :parents (mapv #(.name ^ObjectId %) (.getParents c))})))

(defn- blob-text [^Repository repo sha path]
  (with-open [rw (RevWalk. repo)]
    (let [c  (.parseCommit rw (ObjectId/fromString sha))
          tw (TreeWalk/forPath repo ^String path (.getTree c))]
      (when tw
        (String. (.getBytes (.open repo (.getObjectId tw 0))) "UTF-8")))))

(deftest ^:external record-commit-extra-round-trips
  ;; the schemaless payload carries op-specific extras — `:git-sha` on an
  ;; imported commit is the live one. It used to carry `:tree` too, and the
  ;; example is deliberately not that any more: a commit-point carries no tree.
  (let [st (store/ingest (store/empty-store) 'gp.core seed)
        [st2 d] (store/record-commit st "v1" :agent "alice"
                                     :extra {:git-sha "abc"
                                             :author {:name "Alice"
                                                      :email "a@example.com"}})]
    (is (= "abc" (:git-sha d)))
    (is (= {:name "Alice" :email "a@example.com"} (:author d)))
    (testing "still a no-content marker for foreign-journal sync"
      (is (some? (store/replay-delta st d))))
    (testing "the db round-trips the payload exactly"
      (let [dir  (temp-dir)
            conn (db/open! dir)]
        (try
          (db/persist! conn st2 d)
          (is (= d (first (db/deltas-after conn (slopp.store.db/trunk-line-id! conn) 0))))
          (finally (.close conn) (rm-rf! dir)))))))

(deftest ^:external a-commit-point-carries-no-tree
  ;; The inverse of what this used to assert, and the regression it guards is
  ;; expensive rather than subtle: a commit-point once snapshotted every
  ;; namespace's rendered source into its own delta, which reached 82 MB
  ;; across 272 markers here — 39% of the journal — and 94% of a 344 MB
  ;; journal in an earlier round, unnoticed across 239 commit-points. Nothing
  ;; measured it, so nothing complained.
  ;;
  ;; The tree is DERIVED now: `git/project-journal!` folds the log. What has
  ;; to stay true is that deriving it loses nothing, so this checks the
  ;; projected blob rather than the marker — including the comment, which is
  ;; the content that could not be reconstructed before and is the whole
  ;; reason the snapshot existed.
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'gp.core seed)
      (let [r (external/commit-point! sess "v1: f ships" :agent "alice")
            d (->> (ops/journal sess)
                   (filter #(= (:commit r) (:id %))) first)]
        (is (nil? (:error r)) (pr-str r))
        (testing "the marker delta carries no rendered source at all"
          (is (nil? (:tree d))))
        (testing "and the projection still renders it exactly, comment included"
          (let [ctx (git/open-ctx! dir)]
            (try
              (let [tip (get-in (git/ensure-projected! ctx) [:refs "main"])
                    src (blob-text (:slopp.git/repo ctx) tip "src/gp/core.clj")]
                (is (= (query/query-source sess 'gp.core) src))
                (is (str/includes? (str src) ";; top-level trivia")))
              (finally (git/close-ctx! ctx))))))
      (finally (ops/close! sess)))))

(deftest ^:external projection-mints-deterministic-shas
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'gp.core seed)
      ;; G5: commit-points stamp a configured author; pin it so the assertions
      ;; below don't depend on this machine's global git config
      (external/config! sess "user.name" "alice")
      (external/config! sess "user.email" "alice@slopp")
      (external/commit-point! sess "v1: f ships" :agent "alice")
      (ops/edit-replace! sess 'gp.core 'f "(defn f [x] (+ 10 x))"
                         :prompt "flip arg order" :agent "alice")
      (external/commit-point! sess "v2: flipped" :agent "alice")
      (let [ctx  (git/open-ctx! dir)
            tip  (get-in (git/ensure-projected! ctx) [:refs "main"])
            info (commit-info (:slopp.git/repo ctx) tip)
            cd   (->> (ops/journal sess)
                      (filter #(= :commit (:op %))) last)]
        (is tip)
        (testing "the tip is v2, chained on v1, authored by the agent at :at"
          (is (str/starts-with? (:message info) "v2: flipped"))
          (is (str/includes? (:message info) (str "Slopp-Commit: " (:id cd))))
          (is (= "alice" (:author info)))
          (is (= "alice@slopp" (:email info)))
          ;; git timestamps are second-granular; truncation is deterministic
          (is (= (quot (:at cd) 1000) (quot (:at-ms info) 1000)))
          (is (= 1 (count (:parents info))))
          (is (str/starts-with?
               (:message (commit-info (:slopp.git/repo ctx) (first (:parents info))))
               "v1: f ships")))
        (testing "blob bytes ARE the live render"
          (is (= (query/query-source sess 'gp.core)
                 (blob-text (:slopp.git/repo ctx) tip "src/gp/core.clj"))))
        (testing "the clone is a runnable project (deps.edn present)"
          (is (str/includes? (str (blob-text (:slopp.git/repo ctx) tip "deps.edn"))
                             ":paths")))
        (testing "re-projection is a no-op"
          (is (= tip (get-in (git/ensure-projected! ctx) [:refs "main"]))))
        (testing "query-commits surfaces the projected sha"
          (let [[c2 c1] (ops/query-commits sess)]
            (is (= tip (:sha c2)))
            (is (= (first (:parents info)) (:sha c1)))))
        (git/close-ctx! ctx)
        (testing "rebuild from scratch (a fresh in-memory repo) mints IDENTICAL shas"
          (let [ctx2 (git/open-ctx! dir)]
            (try
              (jdbc/execute! (:slopp.git/map-conn ctx2) ["DELETE FROM git_map"])
              (is (= tip (get-in (git/ensure-projected! ctx2) [:refs "main"])))
              (finally (git/close-ctx! ctx2))))))
      (finally (ops/close! sess)))))

(deftest ^:external branch-shares-prefix-shas
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'gp.core seed)
      (external/commit-point! sess "v1: f ships" :agent "alice")
      (branch/branch! sess "feature")
      (ops/edit-replace! sess 'gp.core 'f "(defn f [x] (int (+ x 10)))"
                         :prompt "tweak on feature" :agent "bob")
      (external/commit-point! sess "feature: tweak" :agent "bob")
      (let [ctx (git/open-ctx! dir)]
        (try
          (let [{:keys [refs]} (git/ensure-projected! ctx)
                main-tip (get refs "main")
                feat-tip (get refs "feature")]
            (is main-tip)
            (is feat-tip)
            (testing "the branch commit chains on main's commit-point"
              (is (= [main-tip] (:parents (commit-info (:slopp.git/repo ctx) feat-tip)))))
            (testing "ONE mapping row for the shared v1 marker (2 rows total)"
              (is (= 2 (:n (jdbc/execute-one!
                            (:slopp.git/map-conn ctx)
                            ["SELECT COUNT(*) AS n FROM git_map"]))))))
          (finally (git/close-ctx! ctx))))
      (finally (ops/close! sess)))))

(deftest ^:external retroactive-target-projects-the-state-it-names
  ;; `commit_point {:target ...}` marks a spot the journal has already walked
  ;; past. That tree used to be REBUILT by folding content deltas — right
  ;; state, but trivia-lossy, because comments lived positionally and were in
  ;; no delta. This test pinned that loss.
  ;;
  ;; Comments are form-owned content now, so the fold is exact and the
  ;; approximation is gone. What is still worth pinning is that a retroactive
  ;; marker gets the state it NAMES rather than the state at the end of the
  ;; walk — and it shares its target delta with the earlier commit-point, which
  ;; is the case that broke first: released after its first reader, the
  ;; retroactive tree silently became the CURRENT one.
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'gp.core seed)
      (let [r1 (external/commit-point! sess "v1" :agent "alice")]
        (ops/edit-replace! sess 'gp.core 'f "(defn f [x] (+ 10 x))"
                           :prompt "newer work" :agent "alice")
        (external/commit-point! sess "v2" :agent "alice")
        (external/commit-point! sess "v1.5 was actually here" :agent "alice"
                                :target (:target r1))
        (let [ctx (git/open-ctx! dir)]
          (try
            (let [tip  (get-in (git/ensure-projected! ctx) [:refs "main"])
                  info (commit-info (:slopp.git/repo ctx) tip)]
              (testing "the retroactive marker is the newest commit (journal order)"
                (is (str/starts-with? (:message info) "v1.5 was actually here")))
              (testing "its tree is the state at its TARGET, not at the walk's end"
                (let [src (blob-text (:slopp.git/repo ctx) tip "src/gp/core.clj")]
                  (is (str/includes? (str src) "(+ x 10)"))
                  (is (not (str/includes? (str src) "(+ 10 x)")))))
              (testing "and it is exact — the comment survives the reconstruction"
                (let [src (blob-text (:slopp.git/repo ctx) tip "src/gp/core.clj")]
                  (is (str/includes? (str src) ";; top-level trivia"))))
              (testing "re-projection returns the PINNED sha"
                (is (= tip (get-in (git/ensure-projected! ctx) [:refs "main"])))))
            (finally (git/close-ctx! ctx)))))
      (finally (ops/close! sess)))))

(deftest ^:external forced-red-commit-point-carries-status-trailer
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'gp.core seed)
      (ops/edit-replace! sess 'gp.core 'f-t "(deftest f-t (is (= 999 (f 1))))"
                         :prompt "deliberately red" :agent "bob")
      (let [r (external/commit-point! sess "broken but important" :agent "bob"
                                 :force true)]
        (is (= :red (:status r)))
        (let [ctx (git/open-ctx! dir)]
          (try
            (let [tip (get-in (git/ensure-projected! ctx) [:refs "main"])]
              (is (str/includes? (:message (commit-info (:slopp.git/repo ctx) tip))
                                 "Slopp-Status: red")))
            (finally (git/close-ctx! ctx)))))
      (finally (ops/close! sess)))))

(deftest ^:external the-projected-tree-is-laid-out-like-a-build
  ;; The mirror is not decoration: build.clj's CI flow is
  ;; `clojure -T:build uber :src src` against a CHECKOUT of the published
  ;; repo. So if the projection roots a namespace differently from `build!`,
  ;; the jar CI produces differs from the jar built here — and an instrument
  ;; routed out of src/ locally ships anyway.
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (is (pos? (:forms (ops/ingest! sess 'gl.core "(ns gl.core)\n(defn ^:unused-ok f [] 1)\n"))))
      (is (pos? (:forms (ops/ingest! sess 'gl.lab "(ns gl.lab)\n(defn ^:unused-ok -main [] 1)\n"))))
      (is (pos? (:forms (ops/ingest! sess 'gl.shared "(ns gl.shared)\n(defn ^:unused-ok s [] 1)\n"))))
      (is (nil? (:error (ops/module-role! sess "gl.lab" :instrument))))
      (is (nil? (:error (ops/module-platform! sess "gl.shared" :cljc))))
      (let [r (external/commit-point! sess "v1" :agent "alice")]
        (is (nil? (:error r)) (pr-str r)))
      (let [ctx (git/open-ctx! dir)]
        (try
          (let [tip  (get-in (git/ensure-projected! ctx) [:refs "main"])
                repo (:slopp.git/repo ctx)
                at   #(blob-text repo tip %)]
            (testing "product code is under src/ — the control, so the absences below mean something"
              (is (some? (at "src/gl/core.clj"))))
            (testing "an instrument is projected OUTSIDE src/, exactly where build! puts it"
              (is (some? (at "instruments/gl/lab.clj")))
              (is (nil? (at "src/gl/lab.clj"))))
            (testing "and the PLATFORM decides the extension here too"
              ;; the projection ignored platform entirely: a :cljc namespace
              ;; landed at .clj, so CI compiled a file the store does not
              ;; describe. Benign for :cljc, which loads on the JVM either
              ;; way; a :cljs namespace under src/ would break the build.
              (is (some? (at "src/gl/shared.cljc")))
              (is (nil? (at "src/gl/shared.clj")))))
          (finally (git/close-ctx! ctx))))
      (finally (ops/close! sess)))))

(deftest a-projected-commit-stamps-the-commit-point-it-came-from
  (testing "the message commit-message writes is the message stamped-commit-point reads"
    ;; ONE producer, ONE reader, asserted as a round trip. The trailer is the
    ;; only thing that lets a reader ask a commit WHICH commit-point it is instead
    ;; of trusting a sha recorded when it was minted -- and a sha recorded at
    ;; mint time says nothing about what was published.
    (let [msg (#'slopp.git/commit-message {:id "d27235" :description "a title"})]
      (is (= "d27235" (slopp.git/stamped-commit-point msg)) msg)))
  (testing "a red commit-point and an agent trailer do not confuse the reader"
    (let [msg (#'slopp.git/commit-message {:id "d42" :description "t" :status :red
                                           :author "a" :agent "ag"})]
      (is (= "d42" (slopp.git/stamped-commit-point msg)) msg)))
  (testing "a commit this projection did not mint carries no stamp"
    ;; An ADOPTED remote commit (a pull) is a real chain node with no trailer.
    ;; nil is the discriminator alignment uses to fall back, so it is asserted
    ;; rather than assumed.
    (is (nil? (slopp.git/stamped-commit-point "someone else's commit\n\nSigned-off-by: x\n")))
    (is (nil? (slopp.git/stamped-commit-point nil)))))

(deftest a-re-projection-corrects-a-pin-it-had-already-written
  ;; The pin was INSERT OR IGNORE on the argument that minting is deterministic,
  ;; so re-writes are ties. Ties they are -- until a projection DIVERGES, and
  ;; then first-writer-wins preserves exactly the wrong one: the correcting
  ;; value always arrives second. Measured 2026-08-14, where a refused push left
  ;; the store pinning a sha for a commit that exists nowhere, permanently.
  ;;
  ;; A pin says what the projection CURRENTLY mints. That is refreshable by
  ;; definition, and it is not evidence that anything was published -- nothing
  ;; may read it as such.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-pin" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-open [conn (slopp.store.db/open! dir)]
      (slopp.git/ensure-map! conn)
      (is (= "aaaaaaa" (#'slopp.git/record-sha! conn "d1" "fp1" "aaaaaaa" "main"))
          "fixture: the first pin must land, or the correction below proves nothing")
      (is (= "bbbbbbb" (#'slopp.git/record-sha! conn "d1" "fp1" "bbbbbbb" "main"))
          "a later projection corrects the pin instead of being ignored")
      (testing "a different fingerprint is a different row, not a correction"
        (is (= "ccccccc" (#'slopp.git/record-sha! conn "d1" "fp2" "ccccccc" "main")))
        (is (= "bbbbbbb" (#'slopp.git/record-sha! conn "d1" "fp1" "bbbbbbb" "main")))))))

(deftest ^:external the-store-can-render-its-own-merge-base-without-git
  ;; Import exists so an external tool's edits come back as ordinary tracked
  ;; form edits, and NOTHING about that story requires the external tool to
  ;; have used git — it requires a tree of files and a base to diff against.
  ;; The base is the hard half: git hands you a merge-base commit, and a
  ;; directory hands you nothing.
  ;;
  ;; The store already knows. Folding the journal to the last commit-point is
  ;; what `project-journal!` does on every projection, and the tree it renders
  ;; there IS "the state this directory was exported from" in the common case.
  ;;
  ;; What has to hold is that the two agree EXACTLY. A base that differs from
  ;; the projection by so much as a generated deps.edn would make every import
  ;; report phantom changes on paths nobody touched — and the two derivations
  ;; would drift silently, which is how the mirror and build! once produced
  ;; different jars from one store.
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'mb.core "(ns mb.core)\n\n(defn ^:unused-ok f [] 1)")
      (ops/ingest! sess 'mb.core-test
                   (str "(ns mb.core-test (:require [clojure.test :refer [deftest is]]))\n\n"
                        "(deftest t (is (= 1 (mb.core/f))))"))
      (external/commit-point! sess "v1" :agent "alice")
      (let [ctx (git/open-ctx! dir)]
        (try
          (let [tip  (git/ensure-projected! ctx)
                sha  (get-in tip [:refs "main"])
                from-git (git/tree-at (:slopp.git/repo ctx) sha)
                from-store (git/commit-point-tree
                            (db/line-deltas (:db @sess)
                                            (or (:line @sess) (db/trunk-line-id! (:db @sess))))
                            #(db/get-blob (:slopp.git/map-conn ctx) %))]
            (is (some? sha))
            (is (seq from-git) "positive control: the projection produced a tree")
            (is (contains? from-git "src/mb/core.clj"))
            (is (contains? from-git "test/mb/core_test.clj")
                "and the layout is the projection's, tests included")
            (is (= from-git from-store)
                "the store's own fold reproduces the projected tree exactly"))
          (finally (git/close-ctx! ctx))))
      (finally
        (ops/close! sess)
        (rm-rf! dir)))))

(deftest ^:external un-landed-work-never-reaches-the-projection
  ;; The isolation claim at its outermost edge. Everything else about threads
  ;; can be observed inside the store; this is the one place the answer leaves
  ;; the process entirely — a git mirror somebody else clones. The projection
  ;; folds the BRANCH's line, so a thread's deltas are not merely unpublished,
  ;; they are unreachable from the fold.
  ;;
  ;; The positive half is what makes the negative half a claim: the same file
  ;; is read for both, so ":un-landed is absent" is a contrast with ":landed is
  ;; present" rather than a statement about a path the projection never wrote.
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "proj"})]
    (try
      (is (pos? (:forms (ops/ingest! sess 'up.core
                                     "(ns up.core)\n(defn ^:unused-ok f [] :landed)\n"))))
      (let [r (external/commit-point! sess "v1" :agent "proj")]
        (is (nil? (:error r)) (pr-str r)))

      (ops/edit-replace! sess 'up.core 'f "(defn ^:unused-ok f [] :un-landed)"
                         :prompt "written, not finished" :agent "proj")
      (is (pos? (:unlanded (:thread (ops/session-brief sess)) 0))
          "fixture: there really is work sitting in the thread")

      (let [ctx (git/open-ctx! dir)]
        (try
          (let [tip (get-in (git/ensure-projected! ctx) [:refs "main"])
                src (blob-text (:slopp.git/repo ctx) tip "src/up/core.clj")]
            (is (re-find #":landed" src)
                "the projection carries what the commit-point landed")
            (is (nil? (re-find #":un-landed" src))
                "and nothing that is still in the thread"))
          (finally (git/close-ctx! ctx))))
      (finally (ops/close! sess)))))

(deftest a-LOCAL-config-path-never-reaches-a-projected-tree
  ;; `commit-paths` renders every config entry into every projected tree, and
  ;; its own docstring says why that is right: "they all ride EVERY projected
  ;; tree, so a slopp push never deletes them." True of `capabilities`,
  ;; `rules`, `gates` — and of `dev`, the project's SHARED dev setup, which a
  ;; clone must arrive with or it cannot serve.
  ;;
  ;; Not true of `dev.local`, one developer's override of that setup on one
  ;; machine. Projecting it pushes that developer's port at everyone who pulls.
  (let [configs {"capabilities" {:format :manifest :values {"http.port" "8080"}}
                 "dev"          {:format :manifest :values {"http.port" "7358"}}
                 "dev.local"    {:format :manifest :values {"http.port" "7399"}}}
        tree    (#'git/commit-paths {} {} {} configs (constantly nil))]

    (testing "the product's config still rides, as it always did"
      (is (contains? tree "capabilities"))
      (is (str/includes? (get tree "capabilities") "http.port")))

    (testing "the shared dev setup rides too"
      (is (= "http.port: 7358\n" (get tree "dev"))))

    (testing "and the local override does NOT"
      (is (not (contains? tree "dev.local"))
          (str "a dev.local entry reached the projected tree: "
               (pr-str (get tree "dev.local")))))

    (testing "nor does its content arrive under some other path"
      (is (not-any? #(str/includes? (str %) "7399") (vals tree))
          (str "dev.local content is in the tree under another path: "
               (pr-str (into {} (filter (fn [[_ v]]
                                          (str/includes? (str v) "7399"))
                                        tree))))))))

(deftest ^:external the-projection-orders-forms-exactly-as-the-live-store-does
  ;; The projection FOLDS the journal to render each commit-point's tree, and the
  ;; journal no longer carries a single ordering delta. So the fold and the
  ;; live store must derive the same arrangement from the same facts — forms,
  ;; references, ranks — or a checkout would hold a file the live store never
  ;; showed anyone, possibly one that does not cold-load.
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (is (pos? (:forms (ops/ingest! sess 'po.core "(ns po.core)\n(defn ^:unused-ok a [] 1)\n"))))
      (is (nil? (:error (ops/add-form! sess 'po.core "(defn ^:unused-ok c [] 2)" :prompt "c"))))
      ;; a now needs c, which was created AFTER it: the live write reorders
      (is (nil? (:error (ops/edit-replace! sess 'po.core 'a "(defn ^:unused-ok a [] (c))" :prompt "a calls c"))))
      (is (= '[po.core c a] (mapv :name (store/forms (:store @sess) 'po.core))))
      (let [r (external/commit-point! sess "ordered" :agent "alice")]
        (is (nil? (:error r)) (pr-str r)))
      (let [ctx (git/open-ctx! dir)]
        (try
          (let [tip  (get-in (git/ensure-projected! ctx) [:refs "main"])
                repo (:slopp.git/repo ctx)]
            (is (= (store.render/render-ns (:store @sess) 'po.core)
                   (blob-text repo tip "src/po/core.clj"))
                "the folded tree and the live store agree byte for byte"))
          (finally (git/close-ctx! ctx))))
      (finally (ops/close! sess)))))

(deftest ^:external the-projection-persists-across-contexts-and-stays-rebuildable
  ;; D2 (s20): the repo was InMemoryRepository and publish-local! opens a
  ;; fresh context per publish, so `.has` was false for every pinned sha
  ;; and every publish re-rendered and re-inserted the tree of EVERY
  ;; commit point in the journal — 270+ full-store renders to mint one
  ;; commit. Measured at 98% of a commit point's wall. The projection is a
  ;; cache; a cache that forgets on every use is a recomputation.
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})
        has? (fn [ctx sha] (.has (.getObjectDatabase ^org.eclipse.jgit.lib.Repository (:slopp.git/repo ctx))
                                 (org.eclipse.jgit.lib.ObjectId/fromString sha)))]
    (try
      (ops/ingest! sess 'gp.core seed)
      (external/config! sess "user.name" "alice")
      (external/config! sess "user.email" "alice@slopp")
      (is (nil? (:error (external/commit-point! sess "v1" :agent "alice"))))
      (ops/edit-replace! sess 'gp.core 'f "(defn f [x] (+ 10 x))" :prompt "v2" :agent "alice")
      (is (nil? (:error (external/commit-point! sess "v2" :agent "alice"))))
      (let [tip (let [ctx (git/open-ctx! dir)]
                  (try (get-in (git/ensure-projected! ctx) [:refs "main"])
                       (finally (git/close-ctx! ctx))))]
        (is (string? tip))
        (testing "a NEW context already holds the objects the last one minted"
          (let [ctx (git/open-ctx! dir)]
            (try
              (is (has? ctx tip) "the tip's object is live before any projection runs")
              (is (= tip (get-in (git/ensure-projected! ctx) [:refs "main"])))
              (finally (git/close-ctx! ctx)))))
        (testing "and it is still a CACHE: deleted, the same shas come back"
          (let [cache (java.io.File. (java.io.File. (str dir) ".slopp") "git-cache")]
            (run! #(.delete ^java.io.File %) (reverse (file-seq cache)))
            (let [ctx (git/open-ctx! dir)]
              (try
                (is (not (has? ctx tip)) "gone")
                (is (= tip (get-in (git/ensure-projected! ctx) [:refs "main"])) "rebuilt, byte-identical")
                (finally (git/close-ctx! ctx)))))))
      (finally (ops/close! sess)))))

(deftest ^:external the-head-commit-is-minted-from-the-head-state-not-a-replay
  ;; D2b: the projection re-derived state the system already holds — it
  ;; folded every delta of the line (39,862 here, 5.2s just to parse them)
  ;; from an empty store to render ONE new tree, while `load-store` hands
  ;; back the materialized head in 3s and the live session holds it for
  ;; free. The pinned parent, the head store, one render, one insert.
  ;; Byte-identical to the walk, which a full rebuild proves.
  ;;
  ;; Later commit points are :force true: a doc advisory that persists
  ;; across dones escalates and refuses the third one, and this pin is
  ;; about the projection, which mints a red marker the same way.
  (let [dir      (temp-dir)
        sess     (external/open! {:slopp.ops/dir dir})
        project! (fn [& opts]
                   (let [ctx (git/open-ctx! dir)]
                     (try (apply git/ensure-projected! ctx opts)
                          (finally (git/close-ctx! ctx)))))
        counting (fn [f]
                   ;; how many times the projection went to the journal
                   (let [n (atom 0) orig slopp.store.db/deltas-after]
                     (with-redefs [slopp.store.db/deltas-after (fn [& a] (swap! n inc) (apply orig a))]
                       [(f) @n])))
        rebuild! (fn []
                   ;; the full walk, from nothing: no cache, no pins
                   (let [cache (java.io.File. (java.io.File. (str dir) ".slopp") "git-cache")]
                     (run! #(.delete ^java.io.File %) (reverse (file-seq cache))))
                   (let [ctx (git/open-ctx! dir)]
                     (try (jdbc/execute! (:slopp.git/map-conn ctx) ["DELETE FROM git_map"])
                          (git/ensure-projected! ctx)
                          (finally (git/close-ctx! ctx)))))]
    (try
      (ops/ingest! sess 'gp.core seed)
      (external/config! sess "user.name" "alice")
      (external/config! sess "user.email" "alice@slopp")
      (is (nil? (:error (external/commit-point! sess "v1" :agent "alice"))))
      (ops/edit-replace! sess 'gp.core 'f "(defn f \"F.\" [x] (+ 10 x))" :prompt "v2" :agent "alice")
      (is (nil? (:error (external/commit-point! sess "v2" :agent "alice"))))
      (let [first-run (project!)]
        (is (= :walk (get-in first-run [:via "main"])) (pr-str (:via first-run)))
        (testing "nothing new since: nothing is loaded, nothing is minted"
          (let [[r n] (counting project!)]
            (is (= :current (get-in r [:via "main"])) (pr-str (:via r)))
            (is (= (get-in first-run [:refs "main"]) (get-in r [:refs "main"])))
            (is (zero? n) "the journal was not read")))
        (ops/edit-replace! sess 'gp.core 'f "(defn f \"F.\" [x] (+ 100 x))" :prompt "v3" :agent "alice")
        (is (nil? (:error (external/commit-point! sess "v3" :agent "alice" :force true))))
        (testing "a new head marker is minted from the materialized head, without the journal"
          (let [[r n] (counting project!)
                tip   (get-in r [:refs "main"])]
            (is (= :head (get-in r [:via "main"])) (pr-str (:via r)))
            (is (zero? n) "no replay")
            (is (= tip (get-in (rebuild!) [:refs "main"])) "the walk mints the same sha — byte-identical")))
        (ops/edit-replace! sess 'gp.core 'f "(defn f \"F.\" [x] (+ 1000 x))" :prompt "v4" :agent "alice")
        (is (nil? (:error (external/commit-point! sess "v4" :agent "alice" :force true))))
        (testing "a caller that already holds the head store hands it over, and it agrees with the walk too"
          (let [[r n] (counting #(project! :head-stores {"main" (:store @sess)}))
                tip   (get-in r [:refs "main"])]
            (is (= :head (get-in r [:via "main"])) (pr-str (:via r)))
            (is (zero? n))
            (is (= tip (get-in (rebuild!) [:refs "main"])) "byte-identical"))))
      (finally (ops/close! sess)))))

(deftest ^:external a-retroactive-target-still-takes-the-walk
  ;; D2b: the fast path is for the ordinary shape — a new marker at the
  ;; head whose target is the delta before it. A retroactive
  ;; `commit_point {:target …}` names an EARLIER state, which only the fold
  ;; can render, so it takes the walk and says so.
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'gp.core seed)
      (external/config! sess "user.name" "alice")
      (external/config! sess "user.email" "alice@slopp")
      (is (nil? (:error (external/commit-point! sess "v1" :agent "alice"))))
      (let [ctx (git/open-ctx! dir)] (try (git/ensure-projected! ctx) (finally (git/close-ctx! ctx))))
      (ops/edit-replace! sess 'gp.core 'f "(defn f \"F.\" [x] (+ 10 x))" :prompt "v2" :agent "alice")
      (is (nil? (:error (external/commit-point! sess "v2" :agent "alice"))))
      (let [ingest-id (:id (first (filter #(= :ingest (:op %)) (ops/journal sess))))]
        (is (string? ingest-id))
        (is (nil? (:error (external/commit-point! sess "back then" :agent "alice" :target ingest-id))))
        (let [ctx (git/open-ctx! dir)]
          (try
            (let [r (git/ensure-projected! ctx)]
              (is (= :walk (get-in r [:via "main"])) (pr-str (:via r)))
              (is (string? (get-in r [:refs "main"]))))
            (finally (git/close-ctx! ctx)))))
      (finally (ops/close! sess)))))

(deftest ^:external an-artifact-projects-as-real-bytes-at-its-manifest-path
  ;; An artifact keeps its bytes OUT of the journal: the store holds the sha
  ;; and the recipe, the on-disk cache holds the file. The projection wrote
  ;; authored files and nothing else, so a checkout of the projected tree had
  ;; no `public/cljs/main.js` — and CI jars exactly that checkout, so a release
  ;; built there served pages whose script 404'd. The bytes ride the tree now,
  ;; at the manifest path, from the cache of the machine that projects.
  (let [dir  (temp-dir)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (is (pos? (:forms (ops/ingest! sess 'ap.core "(ns ap.core)\n(defn ^:unused-ok f [] 1)\n"))))
      (let [bs    (.getBytes "console.log('bundle')" "UTF-8")
            entry (artifacts/put! dir bs {:kind :build :tool "compile_client"}
                                  :content-type "application/javascript")]
        (engine/commit-appended! sess #(first (store/record-artifact % "public/cljs/main.js" entry)) []))
      (let [r (external/commit-point! sess "v1" :agent "alice")]
        (is (nil? (:error r)) (pr-str r)))
      (let [ctx (git/open-ctx! dir)]
        (try
          (let [tip  (get-in (git/ensure-projected! ctx) [:refs "main"])
                repo (:slopp.git/repo ctx)]
            (is (= "console.log('bundle')" (blob-text repo tip "public/cljs/main.js"))
                "the bundle rides the tree at its manifest path, as its bytes")
            (is (some? (blob-text repo tip "src/ap/core.clj")) "the control: source still does"))
          (finally (git/close-ctx! ctx))))
      (testing "and the git-free merge base carries it too, so an import does not see it as a change"
        (let [conn (:db @sess)
              base (git/commit-point-tree (db/line-deltas conn (db/trunk-line-id! conn))
                                          #(db/get-blob conn %)
                                          :dir dir)]
          (is (= "console.log('bundle')" (String. ^bytes (get base "public/cljs/main.js") "UTF-8")))))
      (finally (ops/close! sess)))))
