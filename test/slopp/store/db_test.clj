(ns slopp.store.db-test
  "Tests for the SQLite layer: what the journal keeps, and what it costs.

  The store is a delta log, so these tests are about durability rather than
  behaviour — a store that round-trips wrong loses work, and one that round-
  trips right but grows without bound eventually stops opening. Both failures
  have happened here, which is why both are pinned: byte-exactness of what is
  written and read back, and the SHAPE of what gets written in the first place.

  The recurring lesson is that a store can rot by GROWING. A byte-exact tree
  snapshot in every milestone reached 94% of a 344MB journal, unnoticed across
  239 of them, and was re-parsed at every session open. What came of that —
  the tree in its own column, read on demand, stored as a diff against the
  previous milestone — is most of what is tested here."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.store.render :as store.render]
            [slopp.store.db :as db]
            [slopp.ops :as ops] [slopp.read.query :as query] [slopp.ops.external :as external] [clojure.java.io :as io] [next.jdbc :as jdbc] [rewrite-clj.node :as n] [slopp.read.history :as history])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-db-test" (make-array FileAttribute 0))))

(def corpus
  ["(ns foo)\n\n(defn add [x y]\n  (+ x y))\n\n;; a comment\n(def z 1)\n"
   "(ns bar\n  (:require [clojure.string :as str]))\n\n(def ^:private secret 42)\n"
   ";; leading comment\n(def a 1)(def b 2)\n\n\n"])

(deftest ^:external db-round-trip-is-exact
  (testing "persist -> load reconstructs the store exactly (render + deltas + ids)"
    (doseq [src corpus]
      (let [dir  (temp-dir)
            conn (db/open! dir)
            s    (store/ingest (store/empty-store) 'ns src)]
        (db/persist! conn s (last (store/deltas s)))
        (.close conn)
        (let [conn2  (db/open! dir)
              loaded (db/load-store conn2 (slopp.store.db/trunk-line-id! conn2))]
          ;; against the STORE's render, not the raw source: spacing is normalized
          ;; at ingest now, so comparing to `src` would be testing the
          ;; renderer's retired byte-exact contract rather than persistence
          (is (= (store.render/render-ns s 'ns) (store.render/render-ns loaded 'ns))
              (str "render round-trip failed for: " (pr-str src)))
          (is (= (store/deltas s) (store/deltas loaded)))
          (is (= (map :id (store/forms s 'ns)) (map :id (store/forms loaded 'ns))))
          (is (= (:next-id s) (:next-id loaded)))
          (.close conn2))))))

(deftest ^:external session-survives-restart
  (let [dir (temp-dir)
        target (str "(ns demo\n  (:require [clojure.test :refer [deftest is]]))\n"
                    "(defn add [x y] (+ x y))\n"
                    "(deftest t (is (= 6 (add 2 3))))\n")
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'demo target)
      (ops/edit-replace! sess 'demo 'add "(defn add [x y] (+ x y 1))" :prompt "off-by-one")
      (ops/test-run! sess 'demo)
      (finally (ops/close! sess)))
    ;; process "restarts": a brand-new session over the same dir
    (let [sess2 (external/open! {:slopp.ops/dir dir})]
      (try
        (testing "source is reconstructed from the db"
          (is (re-find #"\(\+ x y 1\)" (query/query-source sess2 'demo))))
        (testing "the image was reloaded from the store"
          (is (= [6] (ops/query-eval sess2 "(demo/add 2 3)"))))
        (testing "lineage (incl. prompt and verification) survives"
          (let [lin (history/query-lineage sess2 'demo 'add)]
            (is (some #(= "off-by-one" (:prompt %)) lin))
            (is (contains? (set (map :op lin)) :ingest)))
          (is (= :verify (:op (last (store/deltas (:store @sess2)))))))
        (testing "new edits continue cleanly (no id collisions with history)"
          (let [r (ops/edit-replace! sess2 'demo 'add "(defn add [x y] (* x y))"
                                     :prompt "mul")]
            (is (nil? (:error r)))
            (is (= [6] (ops/query-eval sess2 "(demo/add 2 3)")))))
        (finally (ops/close! sess2))))))

(deftest ^:external module-tiers-survive-persist-and-reload
  (testing "declared purity tiers reconstruct through persist! -> load-store"
    (let [dir     (temp-dir)
          conn    (db/open! dir)
          [s1 d1] (store/record-module-tier (store/empty-store) "app.core" :pure
                                             :prompt "core is pure")]
      (db/persist! conn s1 d1)
      (.close conn)
      (let [conn2  (db/open! dir)
            loaded (db/load-store conn2 (slopp.store.db/trunk-line-id! conn2))]
        (is (= {"app.core" :pure} (:module-tiers loaded)))
        (.close conn2)))))

(deftest ^:external a-storeless-dir-materializes-on-the-first-write
  ;; The MCP server is launched in whatever dir the editor has open, so
  ;; opening a session must not COLONISE a project that never asked for
  ;; slopp. The store appears on the first real write and not before —
  ;; which is what the slopp-setup skill has always promised.
  (let [dir  (temp-dir)
        sdir (io/file dir ".slopp")
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (testing "opening a session on a storeless dir writes nothing to disk"
        (is (not (.exists sdir))
            ".slopp/ must not be created just by serving a dir"))
      (testing "the first real write materializes the store"
        (ops/ingest! sess 'demo "(ns demo)\n(defn add [x y] (+ x y))\n")
        (is (.exists (io/file sdir "store.db"))))
      (finally (ops/close! sess)))
    (testing "and that write is durable — a fresh session reads it back"
      (let [sess2 (external/open! {:slopp.ops/dir dir})]
        (try
          (is (re-find #"\(\+ x y\)" (query/query-source sess2 'demo)))
          (finally (ops/close! sess2)))))))

(deftest ^:external legacy-tier-spellings-normalize-at-load
  (testing "a pre-canonicalization db row (:effects) loads as :external"
    (let [dir     (temp-dir)
          conn    (db/open! dir)
          [s1 d1] (store/record-module-tier (store/empty-store) "app.core" :pure
                                            :prompt "core is pure")]
      (db/persist! conn s1 d1)
      ;; simulate an old store: the meta row carries a retired spelling
      (jdbc/execute! conn ["INSERT INTO meta (k,v) VALUES ('module-tiers', ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (pr-str {"app.core" :pure "app.shell" :effects})])
      (.close conn)
      (let [conn2  (db/open! dir)
            loaded (db/load-store conn2 (slopp.store.db/trunk-line-id! conn2))]
        (is (= {"app.core" :pure "app.shell" :external} (:module-tiers loaded)))
        (.close conn2)))))

(deftest blobs-are-not-pulled-into-memory-at-open
  ;; The same "don't read it at open" lever as the commit trees: all-blobs
  ;; pulled EVERY blob's bytes into the store value at every session open, and
  ;; a compiled JS bundle is ~1.8MB. Nothing at open needs them. This is safe by
  ;; construction, not by luck: :blobs is a PARTIAL cache by design (file-content
  ;; documents the miss and defers to the db), and put-blobs! is INSERT OR
  ;; IGNORE, so an empty cache on the next write is a no-op and can never prune.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-blobs" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)
        png  (byte-array [(byte -119) 80 78 71 13 10 26 10])
        b64  (.encodeToString (java.util.Base64/getEncoder) png)
        [s1 _] (store/record-file-put (store/empty-store) "public/logo.png" b64
                                      :encoding "base64" :content-type "image/png")
        sha  (get-in s1 [:files "public/logo.png" :sha])]
    (try
      (is (true? (db/append! conn s1 [] [] (db/trunk-line-id! conn) nil)))
      (let [loaded (db/load-store conn (slopp.store.db/trunk-line-id! conn))]
        (testing "the manifest entry loads, the BYTES do not"
          (is (contains? (:files loaded) "public/logo.png"))
          (is (empty? (:blobs loaded))))
        (testing "and a later write cannot prune what was never loaded"
          (is (true? (db/append! conn loaded [] [] (db/trunk-line-id! conn) nil)))
          (is (java.util.Arrays/equals png ^bytes (db/get-blob conn sha)))))
      (testing "the bytes are still there, on demand"
        (is (java.util.Arrays/equals png ^bytes (db/get-blob conn sha))))
      (finally (.close conn)))))

(deftest a-bad-statement-surfaces-instead-of-looking-like-contention
  ;; append! caught EVERY SQLException and returned false, which the caller's
  ;; rebase loop reads as "the head moved, retry" — so a malformed statement
  ;; (a column that does not exist, a constraint violation) was reported as
  ;; "commit contention: too many concurrent writes". That happened for real:
  ;; a write-path change referencing a not-yet-migrated column killed every
  ;; write, and the message sent the diagnosis hunting phantom writers while
  ;; the store was unwritable. Only a genuine writer collision is retryable;
  ;; anything else must SURFACE.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-append" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)]
    (try
      (testing "a constraint violation throws rather than masquerading as contention"
        (is (thrown? java.sql.SQLException
                     (db/append! conn (store/empty-store) [{:id nil :op :add :ns 'x.core}] [] (db/trunk-line-id! conn) nil))))
      (testing "a genuine writer collision is still a retryable false"
        (is (false? (db/append! conn (store/empty-store) [{:id "d1" :op :add :ns 'x.core}] [] (db/trunk-line-id! conn) "a-head-that-never-existed"))))
      (finally (.close conn)))))

(deftest journal-stats-reports-what-the-store-carries
  ;; Nothing measured the COST of what the store holds, so a byte-exact :tree
  ;; snapshot inline in every :commit payload grew to 94% of a 344MB journal —
  ;; unnoticed across 239 milestones, against a design note that estimated
  ;; "tens of KB". full_check counts namespaces and tests; nothing counted
  ;; bytes. The cheapest guard against the next one is a number nobody has to
  ;; go looking for.
  ;;
  ;; That snapshot is gone — the projection derives each tree from the log —
  ;; so there is no :tree-bytes any more. What has to keep working is the
  ;; habit: per-op bytes, heaviest FIRST, so an outlier is the first thing
  ;; read rather than something you find by scrolling.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-health" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)
        st   (store/ingest (store/empty-store) 'sh.core
                           "(ns sh.core)\n\n(defn f \"F.\" [x] x)\n")]
    (try
      (is (true? (db/append! conn st [{:id "d1" :op :ingest :ns 'sh.core :prompt "seed"
                               :sources {"f1" "(ns sh.core)"}}
                              {:id "d2" :op :commit :ns '*session* :target "d1"
                               :description (apply str (repeat 400 "m"))}] ['sh.core] (db/trunk-line-id! conn) nil)))
      (let [s (db/journal-stats conn)]
        (testing "the journal is measured"
          (is (= 2 (get-in s [:deltas :n])))
          (is (pos? (get-in s [:deltas :payload-bytes]))))
        (testing "per-op rows, heaviest first, so the outlier is the first thing read"
          (let [ops (map :op (get-in s [:deltas :by-op]))]
            (is (= #{"ingest" "commit"} (set ops)))
            (is (= "commit" (first ops)) "the heaviest op leads")))
        (testing "state is measured too, so history-vs-state is visible"
          (is (pos? (get-in s [:elements :n])))
          (is (pos? (get-in s [:elements :source-bytes])))
          (is (= 0 (get-in s [:blobs :n])))))
      (finally (.close conn)))))

(deftest a-forms-comment-survives-persist-and-reload
  ;; A comment is CONTENT owned by its form. If it lives only in memory the
  ;; store forgets it on restart, which is the same failure as storing it
  ;; positionally — just later.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-comment" (make-array FileAttribute 0)))
        conn (db/open! dir)
        st   (store/ingest (store/empty-store) 'cmt.core
                           "(ns cmt.core)\n\n(defn f [] 1)\n")
        [st' d] (store/set-comment st 'cmt.core 'f
                                   ";; --- section divider ---\n;; second line")]
    (try
      (db/persist! conn st' d)
      (let [back (db/load-store conn (slopp.store.db/trunk-line-id! conn))
            f-el (first (filter #(= 'f (:name %))
                                (get-in back [:namespaces 'cmt.core :elements])))]
        (testing "the comment comes back attached to its form"
          (is (= ";; --- section divider ---\n;; second line" (:comment f-el))))
        (testing "and renders identically to before the round trip"
          (is (= (store.render/render-ns st' 'cmt.core)
                 (store.render/render-ns back 'cmt.core)))))
      (finally (.close conn)))))

(deftest loading-folds-a-positional-comment-onto-the-form-it-describes
  ;; Migration, at load, so it applies to every store rather than being a
  ;; one-off. Idempotent: after folding there are no comment seps left.
  ;;
  ;; The blank line BETWEEN comment and form has to be absorbed. In this
  ;; store, 0 of 67 comments sit directly above their form — every one is
  ;; followed by a "\n" sep — so a fold that only removes the comment leaves
  ;; a stray gap where there used to be none.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-fold" (make-array FileAttribute 0)))
        conn (db/open! dir)
        src  (str "(ns fc.core)\n\n"
                  ";; --- section ---\n"
                  ";; second line\n"
                  "\n"
                  "(defn f [] 1)\n")
        st   (store/ingest (store/empty-store) 'fc.core src)]
    (try
      (db/persist! conn st (last (store/deltas st)))
      (let [back  (db/load-store conn (slopp.store.db/trunk-line-id! conn))
            elems (get-in back [:namespaces 'fc.core :elements])
            f-el  (first (filter #(= 'f (:name %)) elems))]
        (testing "the comment is now owned by the form below it"
          (is (= ";; --- section ---\n;; second line" (:comment f-el))))
        (testing "and no comment-carrying sep survives"
          (is (not-any? #(and (= :sep (:kind %))
                              (re-find #"\S" (n/string (:node %))))
                        elems)))
        (testing "rendering keeps the gap ABOVE the comment and drops the one below"
          (is (= "(ns fc.core)\n\n;; --- section ---\n;; second line\n(defn f [] 1)\n"
                 (store.render/render-ns back 'fc.core))))
        (testing "it is idempotent — loading again changes nothing"
          (is (= (store.render/render-ns back 'fc.core)
                 (store.render/render-ns (db/load-store conn (slopp.store.db/trunk-line-id! conn)) 'fc.core)))))
      (finally (.close conn)))))

(deftest ^:external a-new-store-dir-ignores-itself
  ;; The store is the source, but it is NOT git content — it reaches git as the
  ;; projected `slopp` branch at each commit_point. Every project that adopts
  ;; slopp therefore has to ignore `.slopp/`, and making each one edit its own
  ;; root .gitignore is a step everyone forgets once and then debugs as "why is
  ;; a 60MB sqlite file in my diff".
  ;;
  ;; A gitignore INSIDE the dir ignores everything including itself, so the
  ;; directory becomes invisible to git without the project's own .gitignore
  ;; ever mentioning slopp — the same promise `sync/import!` already makes:
  ;; only `.slopp/` is created, the working dir stays the human's.
  (let [dir (temp-dir)]
    (testing "creating a store writes a gitignore inside the store dir"
      (let [conn (db/open! dir)]
        (try
          (let [gi (io/file dir ".slopp" ".gitignore")]
            (is (.exists gi))
            (is (re-find #"(?m)^\*$" (slurp gi))
                "an unqualified * ignores every file in the dir, the gitignore
                 included — anything narrower leaves the dir visible to git"))
          (finally (.close conn)))))

    (testing "a gitignore already there is left alone"
      ;; someone may have customised it; adoption must not clobber a file it
      ;; did not write.
      (let [gi (io/file dir ".slopp" ".gitignore")]
        (spit gi "# mine\n")
        (let [conn (db/open! dir)]
          (try (is (= "# mine\n" (slurp gi)))
               (finally (.close conn))))))))

(deftest ^:external an-observation-names-what-it-OBSERVED-as-data
  ;; The deltas table has ONE `ns` column, written `(str (:ns d))` and read
  ;; back `(symbol ...)`. Every consumer treats it as ONE namespace —
  ;; replay-delta, merge-logs, query-outline — and the markers that are not
  ;; about a namespace say so honestly: `:done`, `:commit` and `:turn-begin`
  ;; all put `*session*` there.
  ;;
  ;; `record-observation` was handed the LIST of namespaces a run covered and
  ;; put it in that column, so it round-tripped as one symbol whose NAME is the
  ;; printed list. Measured on this store: all 27 recorded observations, the
  ;; largest 2293 characters.
  ;;
  ;; This is not tidiness. An observation exists to be a KEY — *these tests
  ;; ran, and this is what happened* — and a scope nothing can read
  ;; per-namespace cannot answer "was this namespace covered by that run",
  ;; which is the only question anything downstream wants to ask of it.
  (let [dir  (temp-dir)
        conn (db/open! dir)
        s1   (store/record-observation (store/empty-store)
                                       '[a.one-test a.two-test a.three-test]
                                       {:tier :external :status :green :ran 7 :failures []})]
    (db/persist! conn s1 (last (:deltas s1)))
    (.close conn)
    (let [conn2 (db/open! dir)
          d     (last (:deltas (db/load-store conn2 (slopp.store.db/trunk-line-id! conn2))))]
      (testing "the scope survives as a VECTOR of namespace symbols"
        (is (= '[a.one-test a.two-test a.three-test] (:scope d)) (pr-str d)))
      (testing "so a reader can ask about ONE namespace without parsing a symbol"
        (is (contains? (set (:scope d)) 'a.two-test)))
      (testing "and the ns column carries the sentinel the other whole-session
                markers already use, rather than a list pretending to be a name"
        (is (= '*session* (:ns d)) (pr-str d)))
      (testing "the result rides along unchanged — this moves the scope, it
                does not touch what was observed"
        (is (= {:tier :external :status :green :ran 7 :failures []} (:result d))))
      (.close conn2))))

(deftest ^:external the-journal-is-a-walkable-dag
  ;; The DAG has always been in the data: every delta carries :parent, the
  ;; writer's head at write time. It was unreachable from SQL because :parent
  ;; sat inside the pr-str'd payload — so no query could walk a history, and
  ;; there was no way to name a second line at all. That is why a branch had
  ;; to be a whole separate db file with a full journal copy.
  ;;
  ;; This lifts :parent to a column and names the trunk in a `lines` table.
  ;; Nothing behaves differently yet; the point is only that a line's history
  ;; becomes walkable, which everything after this rests on.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [s  (-> (store/empty-store)
                   (store/ingest 'dag.one "(ns dag.one)\n\n(defn f [x] (inc x))\n")
                   (store/ingest 'dag.two "(ns dag.two)\n\n(def z 1)\n")
                   (store/ingest 'dag.three "(ns dag.three)\n\n(def q 2)\n"))
            ds (store/deltas s)]
        ;; a one-delta log satisfies every assertion below by accident — the
        ;; walk, the order and the head all collapse to the same single id
        (is (< 2 (count ds)) "fixture must produce a CHAIN")
        (is (true? (db/append! conn s ds ['dag.one 'dag.two 'dag.three] (db/trunk-line-id! conn) nil)))

        (testing "the trunk is a line, and it points at the journal head"
          (let [ls (db/lines conn)]
            (is (= 1 (count ls)))
            (is (= "main" (:name (first ls))))
            (is (= "branch" (:kind (first ls))))
            (is (= (:id (last ds)) (:head (first ls))))))

        (testing "every delta but the root records its parent as a column"
          (let [rows (jdbc/execute! conn ["SELECT id, parent FROM deltas ORDER BY seq"])]
            (is (= (count ds) (count rows)))
            (is (nil? (:deltas/parent (first rows))) "the root has no parent")
            (is (every? some? (map :deltas/parent (rest rows))))))

        (testing "walking the trunk's ancestry reproduces the log EXACTLY"
          ;; the ORDER, not the count: a walk that returns the right NUMBER of
          ;; ids while mis-linking two of them is precisely the defect this
          ;; guards, and a count assertion is green for it
          (is (= (mapv :id ds) (db/ancestry conn (:id (last ds)))))))
      (finally (.close conn)))))

(deftest ^:external a-write-contends-only-with-its-own-line
  ;; The CAS read the GLOBAL journal head — `SELECT id FROM deltas ORDER BY seq
  ;; DESC LIMIT 1`. That is correct exactly while one line owns a file, which
  ;; is why branches had to BE separate files. Once many lines share one
  ;; journal it is not a wrong answer, it is a dead system: every agent's write
  ;; fails whenever ANY other agent writes anywhere in the file.
  ;;
  ;; So the CAS moves onto the line. Two writers on two lines never contend;
  ;; two writers on ONE line still do, and that is correct — it is the existing
  ;; rebase path, and it is what the last case below pins.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [s1    (store/ingest (store/empty-store) 'ln.one "(ns ln.one)\n\n(def a 1)\n")
            trunk (db/trunk-line-id! conn)]
        (is (true? (db/append! conn s1 (store/deltas s1) ['ln.one] trunk nil)))

        (let [head1 (:head (first (filter #(= trunk (:id %)) (db/lines conn))))
              other (db/create-line! conn {:kind "thread" :base head1 :agent "agent-2"})
              s2    (store/ingest s1 'ln.two "(ns ln.two)\n\n(def b 2)\n")
              new2  (vec (drop (count (store/deltas s1)) (store/deltas s2)))]
          (is (= head1 (:id (last (store/deltas s1)))) "fixture: the trunk head is the last delta")
          (is (seq new2) "fixture: the second line must actually have work")

          (testing "a line writes at ITS OWN head"
            ;; `other` forked at head1 and writes there. Under a global-head CAS
            ;; this passes anyway — head1 IS the journal head at this moment —
            ;; so it is setup, not evidence. The next block is the evidence.
            (is (true? (db/append! conn s2 new2 ['ln.two] other head1))))

          (testing "and the trunk still writes at ITS head, which the other line moved past"
            ;; THE discriminating case. The journal head is now `other`'s delta,
            ;; so a global-head CAS refuses this write — while the trunk's own
            ;; head never moved and there is nothing for it to rebase onto.
            (let [s3   (store/ingest s2 'ln.three "(ns ln.three)\n\n(def c 3)\n")
                  new3 (vec (drop (count (store/deltas s2)) (store/deltas s3)))]
              (is (true? (db/append! conn s3 new3 ['ln.three] trunk head1))
                  "the trunk's head did not move, so its write must land")))

          (testing "a stale head on the SAME line still loses, as it must"
            (let [s4   (store/ingest s2 'ln.four "(ns ln.four)\n\n(def d 4)\n")
                  new4 (vec (drop (count (store/deltas s2)) (store/deltas s4)))]
              (is (false? (db/append! conn s4 new4 ['ln.four] trunk head1))
                  "head1 is stale for the trunk now — this is the rebase path")))))
      (finally (.close conn)))))

(deftest ^:external two-lines-materialize-one-namespace-independently
  ;; `elements` is the journal MATERIALIZED — it is why opening a store costs
  ;; ~410ms instead of folding 23,560 deltas — and it was keyed (ns, pos), so
  ;; one file could hold exactly ONE view. That is the last remaining reason a
  ;; branch had to BE a separate db file.
  ;;
  ;; A line-scoped read that returns the right rows BECAUSE only one line
  ;; exists proves nothing, so the control is two lines carrying DIFFERENT
  ;; source at the same (ns, pos), each reading back its own. The final block
  ;; is the one that matters most: `write-snapshot!` DELETEs a namespace's rows
  ;; before it re-inserts them, and a DELETE that forgets its line predicate is
  ;; not a wrong answer — it is one agent's write erasing another agent's
  ;; namespace, which looks exactly like work that was never written.
  ;;
  ;; Every `load-store` below MUST name its line as a local. Resolving the
  ;; trunk at each call reads the same and asserts nothing.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [sa    (store/ingest (store/empty-store) 'lv.view "(ns lv.view)\n\n(def v :a)\n")
            trunk (db/trunk-line-id! conn)]
        (is (true? (db/append! conn sa (store/deltas sa) ['lv.view] trunk nil)))

        (let [head-a (:head (first (filter #(= trunk (:id %)) (db/lines conn))))
              other  (db/create-line! conn {:kind "thread" :base head-a :agent "agent-2"})
              ;; re-ingesting the SAME namespace: same (ns, pos), different
              ;; source, and ids that continue sa's counter rather than
              ;; colliding with it in the UNIQUE deltas.id
              sb     (store/ingest sa 'lv.view "(ns lv.view)\n\n(def v :b)\n")
              newb   (vec (drop (count (store/deltas sa)) (store/deltas sb)))]
          (is (not= (store.render/render-ns sa 'lv.view)
                    (store.render/render-ns sb 'lv.view))
              "fixture: the two views must actually differ, or every pair below agrees for free")
          (is (not= trunk other) "fixture: two lines, not one read twice")
          (is (true? (db/append! conn sb newb ['lv.view] other head-a)))

          (testing "each line reads back ITS OWN materialization of the same namespace"
            (is (= (store.render/render-ns sa 'lv.view)
                   (store.render/render-ns (db/load-store conn trunk) 'lv.view)))
            (is (= (store.render/render-ns sb 'lv.view)
                   (store.render/render-ns (db/load-store conn other) 'lv.view))))

          (testing "and a write on one line leaves the other's rows for that namespace intact"
            (let [head-b (:head (first (filter #(= other (:id %)) (db/lines conn))))
                  sc     (store/ingest sb 'lv.only "(ns lv.only)\n\n(def w 9)\n")
                  newc   (vec (drop (count (store/deltas sb)) (store/deltas sc)))]
              (is (true? (db/append! conn sc newc ['lv.only] other head-b)))
              (is (= (store.render/render-ns sa 'lv.view)
                     (store.render/render-ns (db/load-store conn trunk) 'lv.view))
                  "the DELETE is line-scoped, so line B's write cannot erase line A's rows")
              (is (nil? (get-in (db/load-store conn trunk) [:namespaces 'lv.only]))
                  "and a namespace born on line B is not visible from line A")))))
      (finally (.close conn)))))

(deftest ^:external a-split-copies-the-materialization-not-the-journal
  ;; A line that has written nothing must READ exactly as the line it split
  ;; from. Getting there by folding its ancestry would cost the whole journal
  ;; every time — 23,560 deltas on this store against ~2,481 form rows — so a
  ;; split copies the MATERIALIZATION instead, one INSERT … SELECT. That is the
  ;; economy of the whole model, and pinning is what keeps it correct: the base
  ;; never moves under the new line, so the copy stays valid for its life.
  ;;
  ;; The copy is keyed on the parent LINE, not on `:base`: `:base` names a
  ;; delta, and a delta cannot say whose view of it to duplicate.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [sa    (store/ingest (store/empty-store) 'sp.one "(ns sp.one)\n\n(def a 1)\n")
            sa    (store/ingest sa 'sp.two "(ns sp.two)\n\n(def b 2)\n")
            trunk (db/trunk-line-id! conn)]
        (is (true? (db/append! conn sa (store/deltas sa) ['sp.one 'sp.two] trunk nil)))

        (let [head-a (:head (first (filter #(= trunk (:id %)) (db/lines conn))))
              forked (db/create-line! conn {:kind "thread" :base head-a
                                            :parent trunk :agent "agent-2"})
              loaded (db/load-store conn forked)]
          (testing "the fork reads as its parent did, having written nothing"
            (is (= (store.render/render-ns sa 'sp.one)
                   (store.render/render-ns loaded 'sp.one)))
            (is (= (store.render/render-ns sa 'sp.two)
                   (store.render/render-ns loaded 'sp.two)))
            (is (= #{'sp.one 'sp.two} (set (keys (:namespaces loaded))))
                "every namespace, not merely the one that was asked for"))

          (testing "and a line with NO parent copies nothing — a fork is the only inheritance"
            ;; the discriminating half: if `create-line!` copied from the trunk
            ;; unconditionally, the assertion above would hold for a line that
            ;; forked from nowhere, and the copy would be a global default
            ;; rather than a split.
            (let [orphan (db/create-line! conn {:kind "thread" :base head-a :agent "agent-3"})]
              (is (empty? (:namespaces (db/load-store conn orphan))))))))
      (finally (.close conn)))))

(deftest ^:external an-unlined-store-migrates-its-materialization-to-the-trunk
  ;; The one path in this phase that NO fresh store exercises: `elements` keyed
  ;; (ns, pos), with no line at all. Every other test here opens a store that
  ;; was born line-scoped, so the migration would be graded by nothing — and
  ;; the only two stores that actually run it are the two real ones, where a
  ;; lost row is lost work rather than a red test.
  ;;
  ;; SQLite can neither add nor drop a PRIMARY KEY in place, so this is a table
  ;; COPY rather than an ALTER, and a copy is exactly the operation that drops
  ;; a column quietly.
  (let [dir (temp-dir)
        _   (.mkdirs (io/file dir ".slopp"))
        raw (jdbc/get-connection
             (jdbc/get-datasource
              {:dbtype "sqlite" :dbname (str (io/file dir ".slopp" "store.db"))}))]
    (jdbc/execute! raw ["CREATE TABLE elements (ns TEXT NOT NULL, pos INTEGER NOT NULL,
                          kind TEXT NOT NULL, form_id TEXT, name TEXT,
                          source TEXT NOT NULL, comment TEXT,
                          PRIMARY KEY (ns, pos))"])
    (jdbc/execute! raw ["INSERT INTO elements (ns,pos,kind,form_id,name,source,comment)
                         VALUES ('un.core',0,'form','f1','un.core','(ns un.core)',NULL),
                                ('un.core',1,'form','f2','a','(def a 1)',';; why a')"])
    (.close raw)
    (let [conn (db/open! dir)]
      (try
        (let [trunk (db/trunk-line-id! conn)
              rows  (jdbc/execute! conn ["SELECT line, ns, pos, source, comment
                                          FROM elements ORDER BY pos"])]
          (is (= 2 (count rows)) "both rows came across")
          (is (= #{trunk} (set (map :elements/line rows)))
              "backfilled to the trunk — the only line those rows could have belonged to")
          (is (= ["(ns un.core)" "(def a 1)"] (mapv :elements/source rows)))
          (is (= ";; why a" (:elements/comment (second rows)))
              "and the comment came with them, which a hand-written column list is how you lose")
          (is (empty? (jdbc/execute! conn ["SELECT name FROM sqlite_master
                                            WHERE type='table' AND name='elements_unlined'"]))
              "the scratch table is dropped, so re-opening is a no-op rather than a second copy"))
        (finally (.close conn))))))

(deftest ^:external a-lines-journal-is-its-ancestry-not-the-file
  ;; Phase 2 scoped the materialization; the journal was still read whole,
  ;; which holds exactly while one line is written to. `try-commit!` takes its
  ;; CAS head from the store VALUE — `(:id (last (store/deltas base)))` — so a
  ;; line whose log carries another line's deltas does not get a wrong answer,
  ;; it gets a head that can never match again and a line nobody can write to.
  ;;
  ;; The discriminating fact is the ORDER, not the count: line B's delta is
  ;; appended before line A's and therefore has a LOWER seq while being no part
  ;; of A's history. Two lines whose logs merely differ in length would agree
  ;; with an unscoped read for as long as one stayed a prefix of the other.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [sa    (store/ingest (store/empty-store) 'aj.trunk "(ns aj.trunk)\n\n(def a 1)\n")
            trunk (db/trunk-line-id! conn)]
        (is (true? (db/append! conn sa (store/deltas sa) ['aj.trunk] trunk nil)))

        (let [head-a (:head (first (filter #(= trunk (:id %)) (db/lines conn))))
              other  (db/create-line! conn {:kind "thread" :base head-a
                                            :parent trunk :agent "agent-2"})
              ;; B's work, appended to the file FIRST — lower seq, and descended
              ;; from head-a rather than from anything A writes next
              sb     (store/ingest sa 'aj.thread "(ns aj.thread)\n\n(def b 2)\n")
              newb   (vec (drop (count (store/deltas sa)) (store/deltas sb)))
              _      (is (true? (db/append! conn sb newb ['aj.thread] other head-a)))
              ;; A forks from the same base, and `:next-id` is drawn forward
              ;; from B on purpose: the id counter is GLOBAL while the line is
              ;; not, so two lines minting from their own copy of it collide on
              ;; the UNIQUE deltas.id. That is real and it is 3b's to answer —
              ;; here it would only stop this test reaching its subject.
              sa2    (store/ingest (assoc sa :next-id (:next-id sb))
                                   'aj.later "(ns aj.later)\n\n(def c 3)\n")
              newa   (vec (drop (count (store/deltas sa)) (store/deltas sa2)))]
          (is (true? (db/append! conn sa2 newa ['aj.later] trunk head-a)))

          (let [log-a (mapv :id (store/deltas (db/load-store conn trunk)))
                log-b (mapv :id (store/deltas (db/load-store conn other)))
                b-own (mapv :id newb)
                a-own (mapv :id newa)]
            (is (= 1 (count a-own)) "fixture: one new delta per line")
            (is (= 1 (count b-own)))
            (is (not= (first a-own) (first b-own)) "fixture: distinct ids, not a collision")

            (testing "each line's log carries the history they share"
              (is (every? (set log-a) (map :id (store/deltas sa))))
              (is (every? (set log-b) (map :id (store/deltas sa)))))

            (testing "and neither line carries the other's work, whatever its seq"
              (is (not-any? (set log-a) b-own)
                  "line B's delta was written FIRST and must still be absent from A")
              (is (not-any? (set log-b) a-own)))

            (testing "the head each line would CAS against is its own last delta"
              (is (= (last a-own) (last log-a)))
              (is (= (last b-own) (last log-b))))

            (testing "and the shared history is neither duplicated nor reordered"
              (is (= (count log-a) (count (distinct log-a))))
              (is (= log-a (vec (db/ancestry conn (last log-a))))
                  "the fold and the walk are one answer, not two")))))
      (finally (.close conn)))))

(deftest ^:external a-duplicate-delta-id-is-a-lost-race-not-a-fault
  ;; Ids are minted from a store VALUE and `deltas.id` is UNIQUE across the
  ;; whole journal, so two lines counting from the same place mint the same id.
  ;; Per-line CAS deliberately stopped serializing lines against each other —
  ;; that is the feature — and this is its residue. It is a lost race by every
  ;; property that matters: another writer took the id, and the loser has to
  ;; refresh and rebase, which is the path that already exists.
  ;;
  ;; It must not be swallowed as a generic SQL fault. `append!` surfaces every
  ;; other SQLException on purpose, because returning false for a bad statement
  ;; once told an agent "commit contention" for what was really a broken query.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [s0    (store/ingest (store/empty-store) 'dup.base "(ns dup.base)\n\n(def z 0)\n")
            trunk (db/trunk-line-id! conn)]
        (is (true? (db/append! conn s0 (store/deltas s0) ['dup.base] trunk nil)))

        (let [head  (:head (first (filter #(= trunk (:id %)) (db/lines conn))))
              other (db/create-line! conn {:kind "thread" :base head
                                           :parent trunk :agent "agent-2"})
              ;; both lines count from the SAME store value, which is the
              ;; collision by construction rather than by timing
              sa    (store/ingest s0 'dup.one "(ns dup.one)\n\n(def a 1)\n")
              sb    (store/ingest s0 'dup.two "(ns dup.two)\n\n(def b 2)\n")
              na    (vec (drop (count (store/deltas s0)) (store/deltas sa)))
              nb    (vec (drop (count (store/deltas s0)) (store/deltas sb)))]
          (is (= (mapv :id na) (mapv :id nb))
              "fixture: the two lines really did mint the same delta id")
          (is (true? (db/append! conn sa na ['dup.one] trunk head)))

          (testing "the second line loses the race rather than throwing"
            (is (false? (db/append! conn sb nb ['dup.two] other head))))

          (testing "and the loser left nothing behind — the whole write rolled back"
            (is (nil? (get-in (db/load-store conn other) [:namespaces 'dup.two])))
            (is (= head (:head (first (filter #(= other (:id %)) (db/lines conn)))))
                "its head did not move, so the rebase has somewhere to stand"))

          (testing "and rebasing past the file's counter lands"
            (let [sb2 (store/ingest (assoc s0 :next-id (db/next-id-floor conn))
                                    'dup.two "(ns dup.two)\n\n(def b 2)\n")
                  nb2 (vec (drop (count (store/deltas s0)) (store/deltas sb2)))]
              (is (not= (mapv :id nb) (mapv :id nb2)) "fixture: fresh ids this time")
              (is (true? (db/append! conn sb2 nb2 ['dup.two] other head)))))))
      (finally (.close conn)))))

(deftest ^:external an-agent-gets-one-thread-per-branch-and-finds-it-again
  ;; Adoption is keyed by (agent, branch), and every clause of that key is
  ;; load-bearing. "An agent gets a thread" would pass for a system with ONE
  ;; global thread, so the assertions that carry weight are the ones that
  ;; DISCRIMINATE: two agents on one branch must not share a line, and one
  ;; agent on two branches must not either — the second is what decides
  ;; whether switching branches drags your un-done work across with you.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            side  (db/create-line! conn {:name "side" :kind "branch"
                                         :base (db/line-head conn trunk)
                                         :parent trunk})
            a1    (db/adopt-thread! conn trunk "agent-1")
            a2    (db/adopt-thread! conn trunk "agent-1")
            b1    (db/adopt-thread! conn trunk "agent-2")
            a-oth (db/adopt-thread! conn side "agent-1")]
        (is (= a1 a2) "the same agent on the same branch re-adopts its own thread")
        (is (not= a1 b1) "a second agent on the same branch gets its own")
        (is (not= a1 a-oth) "the same agent on another branch gets another thread")

        (testing "and the row says what it is"
          (let [row (first (filter #(= a1 (:id %)) (db/lines conn)))]
            (is (= "thread" (:kind row)))
            (is (nil? (:name row)) "a thread is the anonymous case")
            (is (= trunk (:parent row)) "its branch is its parent LINE")
            (is (= "agent-1" (:agent row)))
            (is (= "open" (:status row))))))
      (finally (.close conn)))))

(deftest ^:external a-settled-thread-is-never-re-entered
  ;; A landed thread's writes are already on the branch and an abandoned one
  ;; was discarded deliberately, so both have a settled meaning. Re-adopting
  ;; either resurrects a line whose story is over — and in the landed case
  ;; stages the same deltas to land a second time.
  ;;
  ;; The status is set with SQL rather than through a verb because nothing
  ;; sets it yet: landing belongs to `done` and abandoning to the drop verb,
  ;; both later. This is the db test, so the schema is within its remit.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)]
        (doseq [settled ["landed" "abandoned"]]
          (let [agent  (str "agent-" settled)
                opened (db/adopt-thread! conn trunk agent)
                row-of (fn [id] (first (filter #(= id (:id %)) (db/lines conn))))]
            (is (= opened (db/adopt-thread! conn trunk agent))
                "fixture: an OPEN thread IS re-adopted, so the contrast below is the status")
            (jdbc/execute! conn ["UPDATE lines SET status = ? WHERE id = ?" settled opened])
            (let [after (db/adopt-thread! conn trunk agent)]
              (is (not= opened after) (str "a " settled " thread is not re-entered"))
              (is (= "open" (:status (row-of after))) "the replacement is open")
              (is (= settled (:status (row-of opened)))
                  "and the settled row is untouched — nothing was reopened")))))
      (finally (.close conn)))))

(deftest ^:external a-thread-forks-at-the-branch-head-and-then-stays-pinned
  ;; Two claims that only look alike. A thread opened after the branch moved
  ;; must start from where the branch is NOW — otherwise a returning agent
  ;; begins behind work that has already landed. And a thread already open
  ;; must NOT move when it is re-adopted: it is pinned at its fork point for
  ;; its whole life, rebasing exactly once, at its own done. That is what
  ;; keeps its view stable and its verdict meaningful mid-work, and it is why
  ;; every conflict arrives together at the end instead of arriving one at a
  ;; time under an agent that is trying to finish.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [row-of (fn [id] (first (filter #(= id (:id %)) (db/lines conn))))
            s1     (store/ingest (store/empty-store) 'th.one "(ns th.one)\n\n(def a 1)\n")
            trunk  (db/trunk-line-id! conn)]
        (is (true? (db/append! conn s1 (store/deltas s1) ['th.one] trunk nil)))
        (let [h1    (db/line-head conn trunk)
              early (db/adopt-thread! conn trunk "agent-early")
              s2    (store/ingest s1 'th.two "(ns th.two)\n\n(def b 2)\n")
              new2  (vec (drop (count (store/deltas s1)) (store/deltas s2)))]
          (is (= h1 (:base (row-of early))) "a thread forks at the branch head")
          (is (true? (db/append! conn s2 new2 ['th.two] trunk h1)))

          (let [h2   (db/line-head conn trunk)
                late (db/adopt-thread! conn trunk "agent-late")]
            (is (not= h1 h2) "fixture: the branch really moved")
            (is (= h2 (:base (row-of late)))
                "a thread opened later starts from where the branch is NOW")
            (is (= #{'th.one 'th.two} (set (keys (:namespaces (db/load-store conn late)))))
                "so it reads the work that landed before it existed"))

          (testing "and re-adoption does not rebase — the pin holds"
            (is (= early (db/adopt-thread! conn trunk "agent-early")))
            (is (= h1 (:base (row-of early))) "its base did not follow the branch")
            (is (= #{'th.one} (set (keys (:namespaces (db/load-store conn early)))))
                "and neither did its view"))))
      (finally (.close conn)))))

(deftest ^:external open-threads-are-this-branchs-live-lines-most-recent-first
  ;; The listing a human or a GC reads to answer "who is working here, and
  ;; what has been sitting untouched". Every exclusion below is a row the
  ;; fixture really contains — a landed thread, another branch's thread, and
  ;; a named BRANCH forked from this one, which shares the `parent` column
  ;; with every thread and is separated only by `kind`.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk  (db/trunk-line-id! conn)
            side   (db/create-line! conn {:name "side" :kind "branch"
                                          :base (db/line-head conn trunk)
                                          :parent trunk})
            t-one  (db/adopt-thread! conn trunk "agent-1")
            t-two  (db/adopt-thread! conn trunk "agent-2")
            landed (db/adopt-thread! conn trunk "agent-3")
            other  (db/adopt-thread! conn side "agent-1")]
        (jdbc/execute! conn ["UPDATE lines SET status = 'landed' WHERE id = ?" landed])
        ;; A millisecond-resolution clock cannot separate four rows minted in
        ;; one breath, so the order is STATED rather than raced for.
        (jdbc/execute! conn ["UPDATE lines SET used_at = 100 WHERE id = ?" t-one])
        (jdbc/execute! conn ["UPDATE lines SET used_at = 200 WHERE id = ?" t-two])
        (let [open (db/open-threads conn trunk)
              ids  (set (map :id open))]
          (is (= [t-two t-one] (mapv :id open))
              "this branch's open threads, most recently used first")
          (is (= ["agent-2" "agent-1"] (mapv :agent open))
              "each row says whose it is — what makes an idle one attributable")
          (is (not (ids landed)) "a landed thread is not live")
          (is (not (ids other)) "another branch's thread is not on this branch")
          (is (not (ids side)) "and a branch forked from here is not a thread")))
      (finally (.close conn)))))

(deftest ^:external adopting-a-thread-marks-it-current
  ;; `used_at` is the only thing that can say a thread is being worked in, and
  ;; a session that is orienting and reading has not written anything yet. A
  ;; clock that moved only on writes would report a thread idle for exactly as
  ;; long as somebody was thinking in it — which is when reaping it costs the
  ;; most.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            id    (db/adopt-thread! conn trunk "agent-1")
            used  (fn [] (:used-at (first (filter #(= id (:id %)) (db/lines conn)))))]
        (jdbc/execute! conn ["UPDATE lines SET used_at = 1 WHERE id = ?" id])
        (is (= 1 (used)) "fixture: the clock really was set back")
        (is (= id (db/adopt-thread! conn trunk "agent-1")) "fixture: the same thread, re-adopted")
        (is (< 1 (used)) "adoption is a use"))
      (finally (.close conn)))))

(deftest ^:external landing-a-thread-moves-the-branch-and-its-view-together
  ;; The land is three facts that must not come apart: the branch's head
  ;; advances, its `elements` follow, and the thread is settled so it is never
  ;; re-entered. A head that moved without its view following is not a partial
  ;; success — it is a line that renders source its journal disagrees with,
  ;; which reads as a corrupt store rather than as an interrupted write.
  ;;
  ;; The thread REWRITES a namespace the branch already has, not merely adds
  ;; one. A copy that inserts without deleting first leaves the branch's old
  ;; rows in place, and every assertion about the namespace the thread ADDED
  ;; would still pass.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [row-of (fn [id] (first (filter #(= id (:id %)) (db/lines conn))))
            s1     (store/ingest (store/empty-store) 'ld.one "(ns ld.one)\n\n(def a 1)\n")
            trunk  (db/trunk-line-id! conn)]
        (is (true? (db/append! conn s1 (store/deltas s1) ['ld.one] trunk nil)))
        (let [h1     (db/line-head conn trunk)
              thread (db/adopt-thread! conn trunk "agent-1")
              s2     (-> s1
                         (store/ingest 'ld.two "(ns ld.two)\n\n(def b 2)\n")
                         (store/ingest 'ld.one "(ns ld.one)\n\n(def a 99)\n"))
              new2   (vec (drop (count (store/deltas s1)) (store/deltas s2)))]
          (is (true? (db/append! conn s2 new2 ['ld.two 'ld.one] thread h1)))
          (is (nil? (get-in (db/load-store conn trunk) [:namespaces 'ld.two]))
              "before the land, the branch cannot see the thread's work")

          (let [t1 (db/line-head conn thread)]
            (testing "a stale expectation lands nothing at all"
              (is (false? (db/land-thread! conn thread trunk "d-not-the-branch-head")))
              (is (= h1 (db/line-head conn trunk)) "the branch did not move")
              (is (= "open" (:status (row-of thread))) "and the thread was not settled"))

            (is (true? (db/land-thread! conn thread trunk h1)))
            (is (= t1 (db/line-head conn trunk)) "the branch points at the thread's head")
            (is (= #{'ld.one 'ld.two} (set (keys (:namespaces (db/load-store conn trunk)))))
                "and its view followed")
            (is (= (store.render/render-ns s2 'ld.one)
                   (store.render/render-ns (db/load-store conn trunk) 'ld.one))
                "including a namespace the thread REWROTE — the copy replaces, never merges")
            (is (= "landed" (:status (row-of thread))))
            (testing "and a landed thread is not handed back to its agent"
              (is (not= thread (db/adopt-thread! conn trunk "agent-1")))))))
      (finally (.close conn)))))
