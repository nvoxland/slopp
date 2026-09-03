(ns slopp.store.db-test
  "Tests for the SQLite layer: what the journal keeps, and what it costs.

  The store is a delta log, so these tests are about durability rather than
  behaviour — a store that round-trips wrong loses work, and one that round-
  trips right but grows without bound eventually stops opening. Both failures
  have happened here, which is why both are pinned: byte-exactness of what is
  written and read back, and the SHAPE of what gets written in the first place.

  The recurring lesson is that a store can rot by GROWING. A byte-exact tree
  snapshot in every commit-point reached 94% of a 344MB journal, unnoticed across
  239 of them, and was re-parsed at every session open. What came of that —
  the tree in its own column, read on demand, stored as a diff against the
  previous commit-point — is most of what is tested here."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.store.render :as store.render]
            [slopp.store.db :as db]
            [slopp.ops :as ops] [slopp.read.query :as query] [slopp.ops.external :as external] [clojure.java.io :as io] [next.jdbc :as jdbc] [rewrite-clj.node :as n] [slopp.read.history :as history] [slopp.index.refs :as refs] [rewrite-clj.parser :as p])
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
          (is (= (store/deltas s)
                 (db/line-deltas conn2 (slopp.store.db/trunk-line-id! conn2)))
              "the journal reads back exactly what the value recorded")
          (is (= (map :id (store/forms s 'ns)) (map :id (store/forms loaded 'ns))))
          (is (= (:next-id s) (:next-id loaded)))
          (.close conn2))))))

(deftest ^:external session-survives-restart
  (let [dir (temp-dir)
        target (str "(ns demo\n  (:require [clojure.test :refer [deftest is]]))\n"
                    "(defn add [x y] (+ x y))\n"
                    "(deftest t (is (= 6 (add 2 3))))\n")
        ;; the SAME agent id across the restart, because that is what a restart
        ;; is: the process goes, the agent does not. Without it the second
        ;; session is a different agent, takes its own thread, and correctly
        ;; sees only what was landed — which is nothing, since this test
        ;; never calls done.
        sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "restart"})]
    (try
      (ops/ingest! sess 'demo target)
      (ops/edit-replace! sess 'demo 'add "(defn add [x y] (+ x y 1))" :prompt "off-by-one")
      (ops/test-run! sess 'demo)
      (finally (ops/close! sess)))
    ;; process "restarts": a brand-new session over the same dir
    (let [sess2 (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "restart"})]
      (try
        (testing "source is reconstructed from the db"
          (is (re-find #"\(\+ x y 1\)" (query/query-source sess2 'demo))))
        (testing "the image was reloaded from the store"
          (is (= [6] (ops/query-eval sess2 "(demo/add 2 3)"))))
        (testing "lineage (incl. prompt and verification) survives"
          (let [lin (history/query-lineage (ops/with-history sess2) 'demo 'add)]
            (is (some #(= "off-by-one" (:prompt %)) lin))
            (is (contains? (set (map :op lin)) :ingest)))
          (is (= :verify (:op (last (ops/journal sess2))))))
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
        ;; one agent across both sessions: the durability claim is about the
        ;; STORE, and a second identity would read a line this write never
        ;; reached rather than an empty disk
        sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "storeless"})]
    (try
      (testing "opening a session on a storeless dir writes nothing to disk"
        (is (not (.exists sdir))
            ".slopp/ must not be created just by serving a dir"))
      (testing "the first real write materializes the store"
        (ops/ingest! sess 'demo "(ns demo)\n(defn add [x y] (+ x y))\n")
        (is (.exists (io/file sdir "store.db"))))
      (finally (ops/close! sess)))
    (testing "and that write is durable — a fresh session reads it back"
      (let [sess2 (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "storeless"})]
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
  ;; unnoticed across 239 commit-points, against a design note that estimated
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

      (testing "elements are split by LINE STATUS, so dead rows are visible"
        ;; This tool exists because a store can rot by growing — and it
        ;; reported 2,112,267 element rows / 3.75 GB as a flat total while
        ;; 2,010,559 of them belonged to 663 LANDED threads that should have
        ;; held none. A total cannot distinguish a store with 30 open lines
        ;; from a store with one and a leak. The split is what makes the
        ;; number readable.
        (let [trunk  (db/trunk-line-id! conn)
              h      (db/line-head conn trunk)
              thread (db/adopt-thread! conn trunk "agent-h")
              st2    (store/ingest st 'sh.more "(ns sh.more)\n\n(def g 1)\n")]
          (is (true? (db/append! conn st2 [{:id "d3" :op :ingest :ns 'sh.more
                                            :sources {"g1" "(ns sh.more)"}}]
                                 ['sh.more] thread h)))
          (let [before (get-in (db/journal-stats conn) [:elements :by-status])]
            (is (pos? (get-in before ["open" :n]))
                (str "an open thread's rows must count as open: " (pr-str before))))
          (is (true? (db/land-thread! conn thread trunk h)))
          (let [after (get-in (db/journal-stats conn) [:elements :by-status])]
            (is (= 0 (get-in after ["landed" :n] 0))
                (str "a landed thread holds no rows, and the split says so: " (pr-str after)))
            (is (pos? (get-in after ["open" :n]))
                "the branch itself is an open line and still holds its view"))))
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
          d     (last (db/line-deltas conn2 (slopp.store.db/trunk-line-id! conn2)))]
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

          (let [log-a (mapv :id (db/line-deltas conn trunk))
                log-b (mapv :id (db/line-deltas conn other))
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
  ;; Three claims that only look alike. A thread opened after the branch
  ;; moved must start from where the branch is NOW — otherwise a returning
  ;; agent begins behind work that has already landed. A thread WITH WORK
  ;; must NOT move when it is re-adopted: it is pinned at its fork point for
  ;; its whole life, rebasing exactly once, at its own done — that is what
  ;; keeps its view stable and its verdict meaningful mid-work, and why every
  ;; conflict arrives together at the end. And a thread with NO work has
  ;; nothing to pin (fork on write: it holds no view of its own), so when it
  ;; is re-adopted after the branch moved it re-forks where a fresh thread
  ;; would start — which is also where its returning agent expects to be.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [row-of (fn [id] (first (filter #(= id (:id %)) (db/lines conn))))
            nses   (fn [line] (set (keys (:namespaces (db/load-store conn line)))))
            s1     (store/ingest (store/empty-store) 'th.one "(ns th.one)\n\n(def a 1)\n")
            trunk  (db/trunk-line-id! conn)]
        (is (true? (db/append! conn s1 (store/deltas s1) ['th.one] trunk nil)))
        (let [h1     (db/line-head conn trunk)
              early  (db/adopt-thread! conn trunk "agent-early")
              idle   (db/adopt-thread! conn trunk "agent-idle")
              ;; early WRITES: its view is now its own, pinned at h1
              s1e    (store/ingest s1 'th.mine "(ns th.mine)\n\n(def m 1)\n")
              new-e  (vec (drop (count (store/deltas s1)) (store/deltas s1e)))
              s2     (store/ingest s1 'th.two "(ns th.two)\n\n(def b 2)\n")
              new2   (vec (drop (count (store/deltas s1)) (store/deltas s2)))]
          (is (= h1 (:base (row-of early))) "a thread forks at the branch head")
          (is (= h1 (:base (row-of idle))))
          (is (true? (db/append! conn s1e new-e ['th.mine] early h1)))
          (is (true? (db/append! conn s2 new2 ['th.two] trunk h1)))

          (let [h2   (db/line-head conn trunk)
                late (db/adopt-thread! conn trunk "agent-late")]
            (is (not= h1 h2) "fixture: the branch really moved")
            (is (= h2 (:base (row-of late)))
                "a thread opened later starts from where the branch is NOW")
            (is (= #{'th.one 'th.two} (nses late))
                "so it reads the work that landed before it existed")

            (testing "a thread WITH work is re-adopted where it was — the pin holds"
              (is (= early (db/adopt-thread! conn trunk "agent-early")))
              (is (= h1 (:base (row-of early))) "its base did not follow the branch")
              (is (= #{'th.one 'th.mine} (nses early))
                  "and neither did its view: its own work, not the branch's later work"))

            (testing "a thread WITHOUT work has nothing to pin: re-adopted after the move, it re-forks where the branch is now"
              (is (= idle (db/adopt-thread! conn trunk "agent-idle")))
              (is (= h2 (:base (row-of idle))) "its base followed the branch")
              (is (= #{'th.one 'th.two} (nses idle)) "and it reads the branch as a fresh thread would")))))
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
              (is (not= thread (db/adopt-thread! conn trunk "agent-1"))))

            (testing "and the thread's OWN view is gone — a landed line keeps its
                      history and loses its materialization, exactly as an
                      abandoned one does"
              ;; `abandon-thread!` says why: the elements rows are the space, a
              ;; thread's view is a full copy of its branch's, and they are pure
              ;; derivation. That argument holds identically after a land — and
              ;; the delete was missing here. Measured on this store: 663
              ;; landed threads, 2,010,559 rows, 3.4 GB, 64% of the file.
              (let [rows (fn [line] (:n (jdbc/execute-one!
                                         conn ["SELECT count(*) AS n FROM elements WHERE line = ?" line])))]
                (is (zero? (rows thread))
                    (str "the landed thread still holds " (rows thread) " element rows"))
                (is (pos? (rows trunk))
                    "the branch, which the land was FOR, must still hold the view"))))))
      (finally (.close conn)))))

(deftest ^:external an-abandoned-thread-keeps-its-history-and-loses-its-view
  ;; What a drop actually costs and what it deliberately does not. The
  ;; materialization goes — that is the space, ~2,700 rows per thread on a
  ;; store this size — and the DELTAS stay, so the work is still findable by
  ;; anyone who goes looking. Nothing in this system deletes history; a drop
  ;; says "nobody is going to finish this", not "this never happened".
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [row-of (fn [id] (first (filter #(= id (:id %)) (db/lines conn))))
            s1     (store/ingest (store/empty-store) 'ab.one "(ns ab.one)\n\n(def a 1)\n")
            trunk  (db/trunk-line-id! conn)]
        (is (true? (db/append! conn s1 (store/deltas s1) ['ab.one] trunk nil)))
        (let [h1     (db/line-head conn trunk)
              thread (db/adopt-thread! conn trunk "agent-1")
              s2     (store/ingest s1 'ab.two "(ns ab.two)\n\n(def b 2)\n")
              new2   (vec (drop (count (store/deltas s1)) (store/deltas s2)))]
          (is (true? (db/append! conn s2 new2 ['ab.two] thread h1)))

          (testing "the count is what makes a listing actionable"
            (is (= (count new2) (db/unlanded-count conn thread history/content-ops))
                "work written since the fork, not the whole journal")
            (is (zero? (db/unlanded-count conn
                                          (db/adopt-thread! conn trunk "agent-idle")
                                          history/content-ops))
                "a line still sitting on its own base has written nothing —
                 without this the count could be reporting journal length"))

          (db/abandon-thread! conn thread)

          (testing "it is settled, and never adopted again"
            (is (= "abandoned" (:status (row-of thread))))
            (is (not= thread (db/adopt-thread! conn trunk "agent-1")))
            (is (not (contains? (set (map :id (db/open-threads conn trunk))) thread))
                "and it is off the live listing"))

          (testing "its view is gone and its history is not"
            (is (empty? (:namespaces (db/load-store conn thread))))
            (is (= (count new2) (db/unlanded-count conn thread history/content-ops))
                "the deltas it wrote are still walkable from its head"))))
      (finally (.close conn)))))

(deftest ^:external unlanded-counts-work-not-bookkeeping
  ;; Reported by slopp-ui from `:unlanded`'s first hour: a `full_check` with no
  ;; source written left the count reading 2. Correct as a delta count, and
  ;; wrong for every reader of it — a number that is non-zero when nothing is
  ;; pending is the badge nobody reads, and then the once it matters nobody
  ;; looks.
  ;;
  ;; What decides it is not noise, though. `api.model/timeline` already filters
  ;; `:working` by `content-ops`, and the two numbers are designed to be read
  ;; TOGETHER — written-not-landed beside landed-not-committed. One filtered
  ;; and one not makes the pair incoherent, so the caller passes the same set
  ;; and the store stays ignorant of what "content" means, which is policy.
  (let [dir  (temp-dir)
        conn (db/open! dir)
        ops  history/content-ops]
    (try
      (let [trunk  (db/trunk-line-id! conn)
            thread (db/adopt-thread! conn trunk "agent-1")
            base   (db/line-head conn thread)
            sa     (store/ingest (store/empty-store) 'uc.one "(ns uc.one)\n\n(def a 1)\n")
            [sb _] (store/record-done sa "a boundary" :agent "agent-1")]
        (is (true? (db/append! conn sb (store/deltas sb) ['uc.one] thread base)))

        (testing "fixture: the thread really holds both kinds"
          (is (= 2 (count (store/deltas sb))) (pr-str (mapv :op (store/deltas sb))))
          (is (= #{:ingest :done} (set (map :op (store/deltas sb))))))

        (is (= 1 (db/unlanded-count conn thread ops))
            "the ingest counts and the done boundary does not")

        (testing "and a thread holding ONLY bookkeeping has nothing unlanded"
          (let [t2     (db/adopt-thread! conn trunk "agent-2")
                b2     (db/line-head conn t2)
                [sc _] (store/record-done (store/empty-store) "nothing but a verdict"
                                          :agent "agent-2")]
            (is (true? (db/append! conn sc (store/deltas sc) [] t2 b2)))
            (is (pos? (count (store/deltas sc))) "fixture: it really wrote something")
            (is (zero? (db/unlanded-count conn t2 ops))
                "a thread nobody wrote code in reads as empty, which is what a
                 reader and a reaper both need it to say"))))
      (finally (.close conn)))))

(deftest ^:external a-threads-lease-is-recorded-and-never-acted-on
  ;; The lease says which process last adopted a line. `thread_list` reports
  ;; it as `:held`, and NOTHING branches on it — a version that did was
  ;; reverted on 2026-08-27, the day it shipped, and this test is what keeps
  ;; it reverted.
  ;;
  ;; It diverted a second LIVE process to a fresh line. The case that defends
  ;; against is not happening: every live server on this store carries a
  ;; distinct conversation id, and the duplicate pids that prompted it were a
  ;; reconnect where the old process had not yet exited. The case that happens
  ;; on EVERY session pause is the plugin's Stop hook, which runs `done` from a
  ;; one-shot process carrying the session's own agent id — a legitimate holder
  ;; that is not the server. Diverting there stranded ten changes on an
  ;; abandoned line, silently, because every write had already reported success.
  ;;
  ;; Sharing a line costs CONTENTION, which per-line CAS arbitrates. Abandoning
  ;; one costs WORK. That trade is one-sided, so it is made here once and not
  ;; re-argued at a call site.
  (let [dir   (temp-dir)
        conn  (db/open! dir)
        ;; genuinely running, and genuinely not us
        other (.start (ProcessBuilder. ["sleep" "30"]))]
    (try
      (let [trunk (db/trunk-line-id! conn)
            mine  (db/adopt-thread! conn trunk "agent-x")
            them  {:pid (.pid other) :started nil}]
        (testing "a live FOREIGN holder does not push anyone onto a new line"
          (is (= mine (db/adopt-thread! conn trunk "agent-x" them))
              "same conversation, same thread — the lease is not a gate")
          (is (= mine (db/adopt-thread! conn trunk "agent-x"))
              "and this process comes back to the same one after them"))

        (testing "but the holder IS recorded, because :held is a diagnostic"
          (db/adopt-thread! conn trunk "agent-x" them)
          (let [row (first (filter #(= mine (:id %)) (db/lines conn)))]
            (is (= (.pid other) (long (:owner-pid row)))
                "the last adopter is on the row, where thread_list can read it")))

        (testing "one thread, not three"
          (is (= 1 (count (filter #(and (= "thread" (:kind %))
                                        (= "agent-x" (:agent %))
                                        (= "open" (:status %)))
                                  (db/lines conn))))
              "three adoptions across two processes minted nothing extra")))

      (testing "liveness still answers honestly — thread_list's :held reads it"
        (is (true? (db/process-live? (:pid (db/this-process))
                                     (:started (db/this-process)))))
        (is (false? (db/process-live? 2147483646 1))
            "a pid nothing is running is not live")
        (is (false? (db/process-live? (:pid (db/this-process)) 1))
            "a MISMATCHED start time means the pid was reused"))
      (finally
        (.destroyForcibly other)
        (.close conn)))))

(deftest ^:external a-measurement-does-NOT-move-the-head
  ;; The property this table exists for. Harness telemetry arrived on an
  ;; exporter's interval and appended a delta every few seconds, so the head
  ;; was never quiescent: `full_check` computed a whole-store answer for three
  ;; to four minutes and lost the CAS to a telemetry row, four times running,
  ;; and ordinary writes began failing behind it.
  ;;
  ;; A number about what something cost is not an entry in the history. It must
  ;; be writable without touching the chain everything else races against.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-measure" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-open [conn (db/open! dir)]
      (db/record-measurement! conn "otel" nil {:context 17548 :cost-usd 0.08})
      (db/record-measurement! conn "otel" nil {:context 510 :cost-usd 0.01})

      (testing "the rows are there"
        (let [ms (db/measurements conn "otel" nil)]
          (is (= 2 (count ms)) (pr-str ms))
          (is (= 17548 (:context (:payload (first ms)))) (pr-str ms))
          (is (every? :at ms) "each carries when it was taken")))

      (testing "and NOTHING was appended to the journal"
        ;; the whole point: no delta, so no head movement, so no verdict loses
        ;; a race to a statistic
        (is (empty? (jdbc/execute! conn ["SELECT 1 FROM deltas LIMIT 1"]))
            "a measurement wrote a delta — it is back in the chain"))

      (testing "kind SEPARATES them, so one reader cannot see another's rows"
        (db/record-measurement! conn "read-cost" nil {:chars 100})
        (is (= 2 (count (db/measurements conn "otel" nil))))
        (is (= 1 (count (db/measurements conn "read-cost" nil)))))

      (testing "since windows by seq, which is how a fold reads only new rows"
        (let [all   (db/measurements conn "otel" nil)
              after (db/measurements conn "otel" (:seq (first all)))]
          (is (= 1 (count after)) (pr-str after)))))))

(deftest only-the-newest-commit-point-keeps-its-files-manifest-in-memory
  ;; The same "don't read it at open" lever as the blobs above, and the largest
  ;; instance of it. `commit_point!` snapshots the WHOLE files manifest into
  ;; every commit-point marker. Measured on this repo: 554 commit-points carrying
  ;; 73.7 MB of payload, of which **:files alone is 70.8 MB (96%)** — a single
  ;; marker reaching 2.1 MB beside ~3.8 KB of everything else.
  ;;
  ;; A loaded value carries NO delta list any more, so nothing parses these at
  ;; open. The lever now sits on `line-deltas` — the history a VIEW hydrates
  ;; with — which must not hand a view every commit-point's manifest either.
  ;; Nothing reads the older ones: `slopp.git/insert-commit!` reads deltas
  ;; straight from the db on its own connection, and `slopp.git/commit-point-tree`
  ;; takes `(last (filter #(= :commit (:op %)) ds))` — only ever the NEWEST.
  ;;
  ;; So the newest keeps its manifest and the rest drop it. Everything else
  ;; about an older commit-point — description, status, target, agent — is small
  ;; and IS read (query_commits, the timeline), so only :files goes.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-commits" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)
        f1   {"a.md" {:sha "sha-a" :size 1}}
        f2   {"a.md" {:sha "sha-a" :size 1} "b.md" {:sha "sha-b" :size 2}}
        mk   (fn [st id files]
               (store/record-delta st {:id id :parent (:head st)
                                       :op :commit :ns '*session* :at 1
                                       :description (str "commit-point " id)
                                       :status "green" :files files}))
        s2   (-> (store/empty-store) (mk "dc1" f1) (mk "dc2" f2))]
    (try
      (is (true? (db/append! conn s2 (:pending s2) [] (db/trunk-line-id! conn) nil)))
      (testing "a loaded value carries no list at all"
        (is (nil? (:deltas (db/load-store conn (db/trunk-line-id! conn))))))
      (let [cs (filterv #(= :commit (:op %)) (db/line-deltas conn (db/trunk-line-id! conn)))]
        (is (= 2 (count cs)) (pr-str (mapv :id cs)))

        (testing "the newest commit-point keeps its manifest — commit-point-tree needs it"
          (is (= f2 (:files (last cs)))))

        (testing "older commit-points do not carry theirs into a hydrated history"
          (is (nil? (:files (first cs)))))

        (testing "and everything else about an older commit-point survives intact"
          (is (= "commit-point dc1" (:description (first cs))))
          (is (= "green" (:status (first cs))))
          (is (= :commit (:op (first cs))))))
      (finally (.close conn)))))

(deftest compaction-reclaims-what-settled-lines-left-behind
  ;; `land-thread!` now drops a landed thread's view; it did not for 663
  ;; landings, and those rows do not disappear because the bug did. A store
  ;; carrying them needs a DELIBERATE step — run once, reported in numbers —
  ;; rather than a surprise at the next land, which is what a consumer asked
  ;; for in so many words: "I would rather run it deliberately than discover
  ;; the file size later."
  ;;
  ;; Reproduces the leak by hand: a thread is landed, then its rows are put
  ;; back under the landed line exactly as the old `land-thread!` left them.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [s1     (store/ingest (store/empty-store) 'cp.one "(ns cp.one)\n\n(def a 1)\n")
            trunk  (db/trunk-line-id! conn)
            _      (db/append! conn s1 (store/deltas s1) ['cp.one] trunk nil)
            h1     (db/line-head conn trunk)
            thread (db/adopt-thread! conn trunk "agent-c")
            s2     (store/ingest s1 'cp.two "(ns cp.two)\n\n(def b 2)\n")
            _      (db/append! conn s2 (vec (drop (count (store/deltas s1)) (store/deltas s2)))
                               ['cp.two] thread h1)
            _      (db/land-thread! conn thread trunk h1)
            rows   (fn [line] (:n (jdbc/execute-one!
                                   conn ["SELECT count(*) AS n FROM elements WHERE line = ?" line])))]
        ;; the leak, re-created: copy the branch's rows back under the LANDED line
        (jdbc/execute! conn ["INSERT INTO elements
                                (line,ns,pos,kind,form_id,name,source,comment)
                              SELECT ?, ns, pos, kind, form_id, name, source, comment
                              FROM elements WHERE line = ?" thread trunk])
        (is (pos? (rows thread)) "fixture: the landed line holds leaked rows")

        (let [;; the server's connection is SHARED, and some other thread is always
              ;; mid-statement on it — a poll, a read, a lease refresh. Run on the
              ;; real store, the first compaction dropped two million rows and
              ;; then died at VACUUM with "SQL statements in progress". A
              ;; ResultSet left open on the caller's connection is that condition.
              held (.executeQuery (.prepareStatement conn "SELECT id FROM deltas"))
              _    (.next held)
              r    (db/compact! conn)]
          (testing "settled lines lose their rows and the branch keeps its own"
            (is (zero? (rows thread)) "leaked rows on a landed line were not reclaimed")
            (is (pos? (rows trunk)) "the open branch's view must survive compaction"))
          (testing "and the report says what moved, in numbers"
            (is (pos? (:rows-dropped r)) (pr-str r))
            (is (number? (:bytes-before r)) (pr-str r))
            (is (number? (:bytes-after r)) (pr-str r)))
          (testing "and the file was vacuumed — no free pages left behind"
            (is (= 0 (:freelist_count (jdbc/execute-one! conn ["PRAGMA freelist_count"])))))))
      (finally (.close conn)))))

(deftest journal-stats-tells-housekeeping-from-authored-work
  ;; Some ops record a fact the pipeline recomputes anyway — a :move puts a
  ;; form where cold-load ordering would have put it. When the PIPELINE writes
  ;; one it is marked `:system true`; when an AGENT writes one, that is a turn
  ;; spent transcribing a program. The number to watch is the agent's share,
  ;; and the number that should fall as more of this moves into the pipeline.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-hk" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! dir)
        st   (store/ingest (store/empty-store) 'hk.core "(ns hk.core)\n\n(def a 1)\n")
        trunk (db/trunk-line-id! conn)]
    (try
      (is (true? (db/append! conn st
                             [{:id "d1" :op :ingest :ns 'hk.core :sources {"f1" "(ns hk.core)"}}
                              ;; turn one: the agent moves a form by hand, the pipeline moves one too
                              {:id "d2" :op :turn-begin :ns '*session* :agent "a"}
                              {:id "d3" :op :move :ns 'hk.core :form-id "f1" :agent "a"}
                              {:id "d4" :op :move :ns 'hk.core :form-id "f1" :agent "a" :system true}
                              {:id "d5" :op :turn-end :ns '*session* :agent "a"}
                              ;; turn two: authored work only
                              {:id "d6" :op :turn-begin :ns '*session* :agent "a"}
                              {:id "d7" :op :replace :ns 'hk.core :form-id "f1" :agent "a"}
                              {:id "d8" :op :turn-end :ns '*session* :agent "a"}]
                             ['hk.core] trunk nil)))
      (let [hk (:housekeeping (db/journal-stats conn))]
        (testing "housekeeping ops are counted by who wrote them"
          (is (= {:agent 1 :system 1} (get-in hk [:by-op "move"])) (pr-str hk)))
        (testing "and as a share of the journal, agent-written only"
          (is (= 1 (:agent hk)) (pr-str hk))
          (is (= 12.5 (:agent-pct hk)) (pr-str hk)))
        (testing "and as the share of turns that spent a write on one"
          (is (= {:n 2 :with-agent-housekeeping 1} (:turns hk)) (pr-str hk))))
      (finally (.close conn)))))

(deftest every-delta-is-indexed-by-the-forms-it-touched
  ;; The journal is indexed by namespace and by parent; history BY FORM is
  ;; not — `form_id` lives inside the EDN payload, so "which deltas touched
  ;; this form" meant parsing every payload, which is why the store value
  ;; still carries all 34k deltas in RAM. `delta_forms(delta_id, form_id)`,
  ;; written at append from every key a delta names a form by, is what makes
  ;; that a query — and the first graph edge the store persists.
  (let [dir  (temp-dir)
        conn (db/open! dir)
        st   (store/ingest (store/empty-store) 'df.core "(ns df.core)\n\n(def a 1)\n")
        trunk (db/trunk-line-id! conn)
        rows  (fn [did] (->> (jdbc/execute! conn ["SELECT form_id FROM delta_forms WHERE delta_id = ? ORDER BY form_id" did])
                             (map #(or (:delta_forms/form_id %) (:form_id %)))
                             vec))]
    (try
      (is (true? (db/append! conn st
                             [{:id "d1" :op :ingest :ns 'df.core :sources {"f1" "(ns df.core)" "f2" "(def a 1)"}}
                              {:id "d2" :op :replace :ns 'df.core :form-id "f2" :source "(def a 2)"}
                              {:id "d3" :op :move-forms :ns 'df.core :form-ids ["f1" "f2"]}
                              {:id "d4" :op :turn-begin :ns '*session* :agent "x"}]
                             ['df.core] trunk nil)))
      (testing "one row per (delta, form), from whichever key the delta names forms by"
        (is (= ["f1" "f2"] (rows "d1")) "the keys of :sources")
        (is (= ["f2"] (rows "d2")) ":form-id")
        (is (= ["f1" "f2"] (rows "d3")) ":form-ids"))
      (testing "and a delta that touches no form has no row — the index is not padded"
        (is (= [] (rows "d4"))))
      (testing "so history by form is one indexed read"
        (is (= ["d1" "d2" "d3"] (db/delta-ids-touching conn ["f2"]))))
      (finally (.close conn)))))

(deftest a-journal-older-than-the-form-index-is-indexed-on-open
  ;; 34k deltas were written before `delta_forms` existed. The index has to
  ;; cover them or "which deltas touched X" would silently start at the day
  ;; the table appeared — and a backfill that waits for an operator to run
  ;; it is a backfill that runs on one store. So `open!` does it: when the
  ;; journal has entries and the index has none, parse each payload once
  ;; and write the rows. A cheap existence check on every later open.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      ;; rows written the old way — straight into `deltas`, no index rows
      (jdbc/execute! conn ["INSERT INTO deltas (id, op, ns, parent, payload) VALUES (?,?,?,?,?)"
                           "o1" "ingest" "old.core" nil (pr-str {:sources {"f1" "(ns old.core)"}})])
      (jdbc/execute! conn ["INSERT INTO deltas (id, op, ns, parent, payload) VALUES (?,?,?,?,?)"
                           "o2" "replace" "old.core" "o1" (pr-str {:form-id "f1" :source "x"})])
      (jdbc/execute! conn ["INSERT INTO deltas (id, op, ns, parent, payload) VALUES (?,?,?,?,?)"
                           "o3" "done" "*session*" "o2" (pr-str {:label "l"})])
      (is (= [] (db/delta-ids-touching conn ["f1"])) "fixture: nothing indexed yet")
      (.close conn)
      (let [conn2 (db/open! dir)]
        (try
          (is (= ["o1" "o2"] (db/delta-ids-touching conn2 ["f1"]))
              "the next open indexed the old journal")
          (finally (.close conn2))))
      (finally (try (.close conn) (catch Exception _))))))

(deftest a-line-answers-its-own-history-from-the-db
  ;; Every reader of history today folds the WHOLE delta list held in RAM:
  ;; `prompt-by-form` walks 34k deltas after every write to find the `:why`
  ;; on a card, `last-write-on` is an uncached `(last (filter …))`, and
  ;; `sources-at` folds from the root. These four are the bounded reads that
  ;; replace them — each one indexed, each one scoped to ONE line's ancestry,
  ;; because a thread's un-landed delta is not the branch's history and a
  ;; reader that cannot tell them apart hands one agent another's work.
  ;;
  ;; The fixture chains its deltas by `:parent`, as every real write does: a
  ;; line's history IS that walk, and a delta without one is a root it stops
  ;; at — the first cut of this test left them off and every line answered
  ;; only its head.
  (let [dir   (temp-dir)
        conn  (db/open! dir)
        st    (store/ingest (store/empty-store) 'lh.core "(ns lh.core)\n\n(def a 1)\n")
        trunk (db/trunk-line-id! conn)]
    (try
      (is (true? (db/append! conn st
                             [{:id "h1" :op :ingest :ns 'lh.core :parent nil :sources {"f1" "(ns lh.core)" "f2" "(def a 1)"} :prompt "seed"}
                              {:id "h2" :op :replace :ns 'lh.core :parent "h1" :form-id "f2" :source "(def a 2)" :prompt "bump a" :agent "a"}
                              {:id "h3" :op :done :ns '*session* :parent "h2" :agent "a" :label "first"}
                              {:id "h4" :op :replace :ns 'lh.other :parent "h3" :form-id "f9" :source "(def z 1)" :prompt "elsewhere"}
                              {:id "h5" :op :done :ns '*session* :parent "h4" :agent "b" :label "second"}]
                             ['lh.core 'lh.other] trunk nil)))
      (let [thread (db/adopt-thread! conn trunk "agent-t")]
        (is (true? (db/append! conn st
                               [{:id "t1" :op :replace :ns 'lh.core :parent "h5" :form-id "f2" :source "(def a 3)" :prompt "on the thread" :agent "agent-t"}]
                               ['lh.core] thread (db/line-head conn trunk))))
        (testing "deltas-touching: the line's deltas that touched any of the forms, in order, with :since"
          (is (= ["h1" "h2"] (map :id (db/deltas-touching conn trunk ["f2"]))))
          (is (= ["h1" "h2" "t1"] (map :id (db/deltas-touching conn thread ["f2"])))
              "the thread sees its own write; the trunk above did not")
          (is (= ["h2"] (map :id (db/deltas-touching conn trunk ["f2"] :since "h1")))
              ":since excludes the named delta and everything before it")
          (is (= :replace (:op (first (db/deltas-touching conn trunk ["f2"] :since "h1"))))
              "full delta maps, not ids"))
        (testing "last-delta-on-ns: the newest delta on a namespace, from the line's view"
          (is (= "h2" (:id (db/last-delta-on-ns conn trunk 'lh.core))))
          (is (= "t1" (:id (db/last-delta-on-ns conn thread 'lh.core))))
          (is (nil? (db/last-delta-on-ns conn trunk 'lh.nowhere))))
        (testing "last-marker: the newest delta of an op, optionally by agent"
          (is (= "h5" (:id (db/last-marker conn trunk :done))))
          (is (= "h3" (:id (db/last-marker conn trunk :done :agent "a"))))
          (is (nil? (db/last-marker conn trunk :commit))))
        (testing "prompt-for-forms: the intent behind each form, from its NEWEST prompt-carrying delta"
          (is (= {"f2" "bump a" "f9" "elsewhere"} (db/prompt-for-forms conn trunk ["f2" "f9"])))
          (is (= {"f2" "on the thread"} (db/prompt-for-forms conn thread ["f2"])))
          (is (= {} (db/prompt-for-forms conn trunk ["nope"])))))
      (finally (.close conn)))))

(deftest the-prompt-behind-a-form-is-the-authors-not-the-pipelines
  ;; The auto-reorder writes `:move` deltas with a prompt of its own, marked
  ;; `:system true`. `prompt-by-form` learned to skip them after they had
  ;; overwritten the author's ask on 7% of forms — the last prompt naming a
  ;; form wins, and the pipeline writes last. The db read keeps that rule,
  ;; and takes the legacy text as `:ignoring` for deltas written before the
  ;; mark existed, because the storage layer cannot see the constant that
  ;; names it.
  (let [dir   (temp-dir)
        conn  (db/open! dir)
        st    (store/ingest (store/empty-store) 'pp.core "(ns pp.core)\n\n(def a 1)\n")
        trunk (db/trunk-line-id! conn)]
    (try
      (is (true? (db/append! conn st
                             [{:id "p1" :op :replace :ns 'pp.core :parent nil :form-id "f1" :prompt "the authored ask"}
                              {:id "p2" :op :move :ns 'pp.core :parent "p1" :form-id "f1" :prompt "put it where it belongs" :system true}
                              {:id "p3" :op :move :ns 'pp.core :parent "p2" :form-id "f1" :prompt "legacy reorder text"}]
                             ['pp.core] trunk nil)))
      (is (= {"f1" "legacy reorder text"} (db/prompt-for-forms conn trunk ["f1"]))
          "unmarked, the newest prompt wins — which is the legacy problem")
      (is (= {"f1" "the authored ask"}
             (db/prompt-for-forms conn trunk ["f1"] :ignoring #{"legacy reorder text"}))
          "the :system delta is skipped by its mark, the legacy one by its text")
      (finally (.close conn)))))

(deftest a-loaded-store-carries-its-head-position-prompts-and-last-writes
  ;; `record-delta` keeps its derived facts current on every append. A store
  ;; LOADED from the journal has to arrive with the same ones, or the first
  ;; read after an open answers from an empty map while a write would have
  ;; answered correctly — the two paths must agree. They are read by index
  ;; (the line's head, its ancestry count, `prompt-for-forms` over the forms
  ;; the materialization holds, the newest delta per namespace, the window
  ;; from the done before the last commit-point), never by folding the
  ;; payloads, because folding them is the cost this whole item exists to
  ;; remove.
  (let [dir   (temp-dir)
        conn  (db/open! dir)
        st    (-> (store/empty-store)
                  (store/ingest 'ld.core "(ns ld.core)\n\n(def a 1)\n")
                  (store/ingest 'ld.other "(ns ld.other)\n\n(def b 1)\n"))
        fid   (fn [ns-sym] (:id (first (filter :id (store/elements st ns-sym)))))
        trunk (db/trunk-line-id! conn)
        _     (db/append! conn st (store/deltas st) ['ld.core 'ld.other] trunk nil)
        h0    (db/line-head conn trunk)
        st2   (-> (store/committed st)
                  (store/record-delta {:id "x1" :parent h0 :op :replace :ns 'ld.core :form-id (fid 'ld.core) :prompt "the ask"})
                  (store/record-delta {:id "x2" :parent "x1" :op :move :ns 'ld.core :form-id (fid 'ld.core) :prompt "pipeline" :system true})
                  (store/record-delta {:id "x3" :parent "x2" :op :done :ns '*session* :label "l"})
                  (store/record-delta {:id "x4" :parent "x3" :op :commit :ns '*session* :description "m"})
                  (store/record-delta {:id "x5" :parent "x4" :op :turn-begin :ns '*session* :agent "a"}))
        _     (db/append! conn st2 (:pending st2) ['ld.core] trunk h0)]
    (try
      (let [loaded (db/load-store conn trunk)]
        (testing "head and position"
          (is (= "x5" (:head loaded)))
          (is (= (:line-pos st2) (:line-pos loaded))))
        (testing "the prompt per form, the author's — the :system move did not overwrite it"
          (is (= "the ask" (get (:prompts loaded) (fid 'ld.core))) (pr-str (:prompts loaded))))
        (testing "the last write per namespace, session markers excluded"
          (is (= "x2" (get-in loaded [:last-write 'ld.core :id])) (pr-str (:last-write loaded)))
          (is (some? (get-in loaded [:last-write 'ld.other :id])))
          (is (nil? (get-in loaded [:last-write '*session*]))))
        (testing "the recent window, cut at the commit-point to the done that earned it"
          (is (= ["x3" "x4" "x5"] (map :id (:recent loaded))) (pr-str (map :id (:recent loaded)))))
        (testing "and the two paths agree: a load answers what the writes built"
          (is (= (:prompts st2) (:prompts loaded)))
          (is (= (:last-write st2) (:last-write loaded)))
          (is (= (map :id (:recent st2)) (map :id (:recent loaded))))
          (is (= [] (:pending loaded)))))
      (finally (.close conn)))))

(deftest a-line-can-name-its-head-delta-and-say-whether-an-id-is-on-it
  ;; `commit-point!` asked two things of the in-RAM list: "is the newest
  ;; delta already a commit-point" (`(last deltas)`) and "is this target in this
  ;; branch's history" (`(some #(= target (:id %)) deltas)`). Both are one
  ;; indexed read over the line's ancestry.
  (let [dir   (temp-dir)
        conn  (db/open! dir)
        st    (store/ingest (store/empty-store) 'hd.core "(ns hd.core)\n\n(def a 1)\n")
        trunk (db/trunk-line-id! conn)]
    (try
      (is (true? (db/append! conn st
                             [{:id "h1" :op :ingest :ns 'hd.core :parent nil :sources {"f1" "(ns hd.core)"}}
                              {:id "h2" :op :commit :ns '*session* :parent "h1" :description "m" :status :green}]
                             ['hd.core] trunk nil)))
      (let [thread (db/adopt-thread! conn trunk "agent-h")]
        (is (true? (db/append! conn st
                               [{:id "t1" :op :replace :ns 'hd.core :parent "h2" :form-id "f1" :source "x"}]
                               ['hd.core] thread (db/line-head conn trunk))))
        (testing "the head delta, as a delta map"
          (is (= :commit (:op (db/head-delta conn trunk))))
          (is (= "m" (:description (db/head-delta conn trunk))))
          (is (= "t1" (:id (db/head-delta conn thread)))))
        (testing "membership in a line's history"
          (is (true? (db/on-line? conn trunk "h1")))
          (is (true? (db/on-line? conn thread "h1")) "the thread inherits the trunk's history")
          (is (false? (db/on-line? conn trunk "t1")) "the trunk does not see the thread's write")
          (is (false? (db/on-line? conn trunk "nope")))))
      (finally (.close conn)))))

(deftest the-journal-counts-code-deltas-after-a-point-by-index
  ;; Three currency numbers — the host's, the jar's, the bundle's `:behind` —
  ;; counted the whole in-RAM list. The count is a query when `at` is a
  ;; COLUMN: written at append, backfilled once on open for a journal older
  ;; than the column, indexed. Markers never count; only code moves an
  ;; artifact behind.
  (let [dir   (temp-dir)
        conn  (db/open! dir)
        st    (store/ingest (store/empty-store) 'cd.core "(ns cd.core)\n\n(def a 1)\n")
        trunk (db/trunk-line-id! conn)]
    (try
      (is (true? (db/append! conn st
                             [{:id "c1" :op :ingest :ns 'cd.core :parent nil :at 1000 :sources {"f1" "(ns cd.core)"}}
                              {:id "c2" :op :done :ns '*session* :parent "c1" :at 2000}
                              {:id "c3" :op :replace :ns 'cd.core :parent "c2" :at 3000 :form-id "f1" :source "x"}
                              {:id "c4" :op :verify :ns 'cd.core :parent "c3" :at 4000 :result {}}
                              {:id "c5" :op :add :ns 'cd.core :parent "c4" :at 5000 :form-id "f2" :sources {"f2" "(def b 1)"}}]
                             ['cd.core] trunk nil)))
      (testing "after a delta id — markers excluded"
        (is (= 2 (db/code-deltas-after conn trunk {:id "c1"})) "c3 and c5")
        (is (= 1 (db/code-deltas-after conn trunk {:id "c3"})))
        (is (= 0 (db/code-deltas-after conn trunk {:id "c5"}))))
      (testing "after a time — the host's boot has no position in the log, only a clock"
        (is (= 2 (db/code-deltas-after conn trunk {:at 1500})))
        (is (= 1 (db/code-deltas-after conn trunk {:at 3000})) "strictly after")
        (is (= 3 (db/code-deltas-after conn trunk {:at 0}))))
      (testing "a journal older than the column is backfilled on open"
        (jdbc/execute! conn ["UPDATE deltas SET at = NULL"])
        (is (= 0 (db/code-deltas-after conn trunk {:at 0})) "fixture: the column is empty")
        (.close conn)
        (let [conn2 (db/open! dir)]
          (try
            (is (= 3 (db/code-deltas-after conn2 trunk {:at 0})) "the open filled it from the payloads")
            (finally (.close conn2)))))
      (finally (try (.close conn) (catch Exception _))))))

(deftest a-line-names-its-newest-artifact-and-the-ops-after-a-delta
  ;; `bundle-currency` walked the whole list twice: once to find the newest
  ;; `:artifact-put` for a path, once to count the client writes after it.
  ;; The first is one indexed row; the second needs only (id, op, ns) per
  ;; delta after a position — no payload — because the platform filter is
  ;; the store value's to apply.
  (let [dir   (temp-dir)
        conn  (db/open! dir)
        st    (store/ingest (store/empty-store) 'ap.core "(ns ap.core)\n\n(def a 1)\n")
        trunk (db/trunk-line-id! conn)]
    (try
      (is (true? (db/append! conn st
                             [{:id "a1" :op :ingest :ns 'ap.core :parent nil :at 1 :sources {"f1" "(ns ap.core)"}}
                              {:id "a2" :op :artifact-put :ns '*session* :parent "a1" :at 2 :path "public/x.js" :entry {:sha "old"}}
                              {:id "a3" :op :artifact-put :ns '*session* :parent "a2" :at 3 :path "public/x.js" :entry {:sha "new"}}
                              {:id "a4" :op :replace :ns 'ap.core :parent "a3" :at 4 :form-id "f1" :source "x"}
                              {:id "a5" :op :verify :ns 'ap.core :parent "a4" :at 5 :result {}}
                              {:id "a6" :op :artifact-put :ns '*session* :parent "a5" :at 6 :path "public/x.js" :action :remove}]
                             ['ap.core] trunk nil)))
      (testing "the newest artifact-put for a path that is not a removal"
        (is (= "a3" (:id (db/last-artifact-put conn trunk "public/x.js"))))
        (is (= "new" (get-in (db/last-artifact-put conn trunk "public/x.js") [:entry :sha])))
        (is (nil? (db/last-artifact-put conn trunk "public/none.js"))))
      (testing "the op rows after a delta — id, op, ns; no payload"
        (is (= [{:id "a4" :op :replace :ns 'ap.core}
                {:id "a5" :op :verify :ns 'ap.core}
                {:id "a6" :op :artifact-put :ns '*session*}]
               (db/ops-after conn trunk "a3")))
        (is (= [] (db/ops-after conn trunk "a6"))))
      (finally (.close conn)))))

(deftest sources-at-a-point-folds-only-the-deltas-that-touched-the-forms-asked-for
  ;; `store/sources-at` folded the WHOLE log from the root to answer what a
  ;; form looked like at the last done — on every done, for every rule that
  ;; compares against a baseline. The callers ever ask about a handful of
  ;; forms; `delta_forms` makes those a dozen deltas out of tens of thousands.
  (let [dir   (temp-dir)
        conn  (db/open! dir)
        st    (store/ingest (store/empty-store) 'sa.core "(ns sa.core)\n\n(def a 1)\n")
        trunk (db/trunk-line-id! conn)]
    (try
      (is (true? (db/append! conn st
                             [{:id "s1" :op :ingest :ns 'sa.core :parent nil :at 1 :form-ids ["f1" "f2"]
                               :sources {"f1" "(ns sa.core)" "f2" "(def a 1)"}}
                              {:id "s2" :op :replace :ns 'sa.core :parent "s1" :at 2 :form-id "f2" :sources {"f2" "(def a 2)"}}
                              {:id "s3" :op :done :ns '*session* :parent "s2" :at 3}
                              {:id "s4" :op :replace :ns 'sa.core :parent "s3" :at 4 :form-id "f2" :sources {"f2" "(def a 3)"}}
                              {:id "s5" :op :add :ns 'sa.core :parent "s4" :at 5 :form-id "f3" :sources {"f3" "(def b 1)"}}
                              {:id "s6" :op :delete :ns 'sa.core :parent "s5" :at 6 :form-id "f1"}]
                             ['sa.core] trunk nil)))
      (testing "as of the done: f2 is its second version, f3 does not exist yet"
        (is (= {"f2" "(def a 2)"} (db/sources-at conn trunk "s3" ["f2" "f3"]))))
      (testing "as of the head: f2 is current, f3 exists, f1 is deleted"
        (is (= {"f2" "(def a 3)" "f3" "(def b 1)"} (db/sources-at conn trunk "s6" ["f1" "f2" "f3"]))))
      (testing "before the delete, f1 is still there"
        (is (= {"f1" "(ns sa.core)"} (db/sources-at conn trunk "s5" ["f1"]))))
      (testing "nothing asked, nothing answered; a nil point is before any delta"
        (is (= {} (db/sources-at conn trunk "s6" [])))
        (is (= {} (db/sources-at conn trunk nil ["f2"]))))
      (finally (.close conn)))))

(deftest a-line-names-its-newest-whole-store-verdict
  ;; `standing-full-check` walked the whole list for the newest `:verify`
  ;; scoped `:full-check`. The scope lives in the payload, so the read is a
  ;; text prefilter on verify rows newest-first, confirmed on the parse.
  (let [dir   (temp-dir)
        conn  (db/open! dir)
        st    (store/ingest (store/empty-store) 'fc.core "(ns fc.core)\n\n(def a 1)\n")
        trunk (db/trunk-line-id! conn)]
    (try
      (is (true? (db/append! conn st
                             [{:id "v1" :op :ingest :ns 'fc.core :parent nil :at 1 :sources {"f1" "(ns fc.core)"}}
                              {:id "v2" :op :verify :ns 'fc.core :parent "v1" :at 2 :result {:status :green}}
                              {:id "v3" :op :verify :ns '*session* :parent "v2" :at 3 :result {:scope :full-check :status :red :ms 1}}
                              {:id "v4" :op :verify :ns '*session* :parent "v3" :at 4 :result {:scope :full-check :status :green :ms 2}}
                              {:id "v5" :op :verify :ns 'fc.core :parent "v4" :at 5 :result {:status :green}}]
                             ['fc.core] trunk nil)))
      (is (= "v4" (:id (db/last-full-check conn trunk))) "the newest whole-store one, not the newest verify")
      (is (= :green (get-in (db/last-full-check conn trunk) [:result :status])))
      (let [thread (db/adopt-thread! conn trunk "agent-f")]
        (is (= "v4" (:id (db/last-full-check conn thread))) "a thread inherits the branch's verdicts"))
      (finally (.close conn)))))

(deftest ^:external line-deltas-narrows-to-the-ops-a-reader-asks-for
  ;; `session_brief` and the reviewer's landing page want the MILESTONES —
  ;; a few dozen rows — and hydrating the whole log to find them costs the
  ;; same seconds and the same hundred megabytes as a genuine history view.
  ;; The filter is the journal's, in SQL, so the payloads never leave the db.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            s1    (-> (store/empty-store)
                      (store/ingest 'ld.one "(ns ld.one)\n\n(def a 1)\n")
                      (store/record-observation '[ld.one-test]
                                                {:tier :external :status :green :ran 1 :failures []}))]
        (is (true? (db/append! conn s1 (store/deltas s1) ['ld.one] trunk nil)))
        (is (= [:ingest :observe] (mapv :op (db/line-deltas conn trunk)))
            "fixture: two kinds on the line")
        (is (= [:observe] (mapv :op (db/line-deltas conn trunk :ops [:observe])))
            "only the asked-for kind comes back")
        (is (= '[ld.one-test]
               (:scope (first (db/line-deltas conn trunk :ops [:observe]))))
            "and it is the whole delta, not a projection")
        (is (empty? (db/line-deltas conn trunk :ops [:commit]))
            "a kind the line never wrote is an empty answer, not an error"))
      (finally (.close conn)))))

(deftest ^:external the-reference-index-persists-beside-the-elements
  ;; A fresh process paid 9.3 s of kondo over 244 namespaces before it could
  ;; answer "who calls this" (measured on slopp's own store). The index the
  ;; value carries (`:refs`, one entry per namespace keyed on its source) is
  ;; written with the elements of the namespaces a write touched and read
  ;; back at open — as rows, one per edge, so a reader outside the value can
  ;; ask the same question in SQL.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            st    (-> (store/empty-store)
                      (store/ingest 'ri.core "(ns ri.core)\n(defn f [x] x)\n")
                      (store/ingest 'ri.two
                                    "(ns ri.two (:require [ri.core :as c]))\n(defn g [x] (c/f x))\n"))
            st    (refs/refresh st ['ri.core 'ri.two])]
        (is (true? (db/append! conn st (store/deltas st) ['ri.core 'ri.two] trunk nil)))
        (let [loaded (db/load-store conn trunk)]
          (is (= (:refs st) (:refs loaded)) "the index reloads exactly as it was written")
          (is (= (refs/refs st) (refs/refs loaded)) "and the graph over it is the same graph"))
        (testing "a namespace persisted WITHOUT an entry has none on disk either"
          ;; the index is written from the value, never invented by the db
          (let [st2 (store/ingest st 'ri.three "(ns ri.three)\n(def a 1)\n")]
            (is (true? (db/append! conn st2
                                   (vec (drop (count (store/deltas st)) (store/deltas st2)))
                                   ['ri.three] trunk (:head st))))
            (let [loaded (db/load-store conn trunk)]
              (is (nil? (get-in loaded [:refs 'ri.three])))
              (is (= (get-in st [:refs 'ri.two]) (get-in loaded [:refs 'ri.two]))
                  "an untouched namespace's entry is untouched"))))
        (testing "rewriting a namespace replaces its rows rather than adding to them"
          (let [st2 (db/load-store conn trunk)
                st3 (refs/refresh
                     (first (store/replace-node st2 'ri.two 'g
                                                (p/parse-string "(defn g [x] x)")
                                                :prompt "t"))
                     ['ri.two])]
            (is (true? (db/append! conn st3 (:pending st3) ['ri.two] trunk (:head st2))))
            (is (empty? (get-in st3 [:refs 'ri.two :rows])) "fixture: g no longer calls anything")
            (is (= (get-in st3 [:refs 'ri.two])
                   (get-in (db/load-store conn trunk) [:refs 'ri.two]))))))
      (finally (.close conn)))))

(deftest ^:external the-reference-index-follows-a-lines-view
  ;; `elements` is materialized per line — on a thread's first write (fork
  ;; on write), replaced at a land, dropped at an abandon. The index derived
  ;; from those rows has to travel with them: a rowless thread READS its
  ;; branch's index (not a cold rebuild), a thread that has written OWNS one,
  ;; and a landed branch never keeps an index computed from source it no
  ;; longer holds.
  (let [dir  (temp-dir)
        conn (db/open! dir)
        rows (fn [line] (mapv :form_refs/to_name
                              (jdbc/execute! conn ["SELECT to_name FROM form_refs WHERE line = ? ORDER BY seq" line])))
        keyed (fn [line] (mapv :refs_keys/ns
                               (jdbc/execute! conn ["SELECT ns FROM refs_keys WHERE line = ? ORDER BY ns" line])))]
    (try
      (let [trunk (db/trunk-line-id! conn)
            st    (-> (store/empty-store)
                      (store/ingest 'lv.core "(ns lv.core)\n(defn f [x] x)\n")
                      (store/ingest 'lv.core.two
                                    "(ns lv.core.two (:require [lv.core :as c]))\n(defn g [x] (c/f x))\n"))
            st    (refs/refresh st ['lv.core 'lv.core.two])]
        (is (true? (db/append! conn st (store/deltas st) ['lv.core 'lv.core.two] trunk nil)))
        (is (= ["f"] (rows trunk)) "fixture: the trunk carries one edge")
        (let [head   (db/line-head conn trunk)
              thread (db/create-line! conn {:kind "thread" :base head :parent trunk :agent "a"})]
          (testing "a fork owns no rows yet, and reads its parent's index whole"
            (is (= [] (rows thread)))
            (is (= [] (keyed thread)))
            (is (= #{'lv.core 'lv.core.two} (set (keys (db/load-refs conn thread))))
                "the parent's index, through the rowless thread"))
          (testing "its first write gives it an index of its own, and a land moves it onto the branch"
            (let [st2 (refs/refresh
                       (first (store/replace-node (store/committed st) 'lv.core.two 'g
                                                  (p/parse-string "(defn g [x] x)")
                                                  :prompt "t"))
                       ['lv.core.two])]
              (is (true? (db/append! conn st2 (:pending st2) ['lv.core.two] thread head)))
              (is (= ["lv.core" "lv.core.two"] (keyed thread)) "the whole index is the thread's now")
              (is (= [] (rows thread)) "fixture: g calls nothing now")
              (is (true? (db/land-thread! conn thread trunk head)))
              (is (= [] (rows trunk)) "the branch's index is the thread's")
              (is (= ["lv.core" "lv.core.two"] (keyed trunk)))
              (is (empty? (keyed thread)) "and the settled thread holds none")))
          (testing "an abandoned thread releases its index too"
            (let [t2  (db/create-line! conn {:kind "thread" :base (db/line-head conn trunk)
                                             :parent trunk :agent "b"})
                  st3 (refs/refresh
                       (first (store/replace-node (store/committed st) 'lv.core 'f
                                                  (p/parse-string "(defn f [x] (inc x))")
                                                  :prompt "t"))
                       ['lv.core])]
              (is (true? (db/append! conn st3 (:pending st3) ['lv.core] t2 (db/line-head conn trunk))))
              (is (seq (keyed t2)) "fixture: the write gave it an index")
              (is (true? (db/abandon-thread! conn t2)))
              (is (empty? (keyed t2)))))))
      (finally (.close conn)))))

(deftest ^:external a-red-run-is-credited-to-the-forms-its-episode-changed
  ;; The journal has always known which tests went red after which forms
  ;; changed — a `:verify`/`:observe` delta names its red tests, and the code
  ;; deltas since the last `:done` name their forms — but only as a parse of
  ;; every payload. `form_reds` keeps that as a count per (form, test), written
  ;; in the same transaction as the red, so "what usually breaks when this
  ;; changes" is one indexed read. The episode is the grain: a form changed
  ;; BEFORE the last done is not blamed for a red after it.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            st0   (-> (store/empty-store)
                      (store/ingest 'rb.core "(ns rb.core)\n(defn f [x] x)\n(defn g [x] x)\n"))
            fid   (:id (store/form-named st0 'rb.core 'f))
            gid   (:id (store/form-named st0 'rb.core 'g))
            st1   (store/record-delta st0 {:id "done-1" :op :done :ns '*session*
                                          :parent (:head st0) :at 1})
            st2   (first (store/replace-node st1 'rb.core 'f
                                             (p/parse-string "(defn f [x] (inc x))")
                                             :prompt "off by one"))
            st3   (store/record-verification st2 'rb.core
                                             {:status :red
                                              :failures [{:test 'rb.core-test/t}
                                                         {:test 'bare-name}]})]
        (is (true? (db/append! conn st3 (store/deltas st3) ['rb.core] trunk nil)))
        (is (= [{:form-id fid :test 'rb.core-test/t :n 1 :last (:head st3)}]
               (db/reds-for conn [fid gid]))
            "f changed this episode and t went red: credited once; g did not change since the done; a bare test name is not evidence")
        (testing "a second red in a later episode counts up"
          (let [st4 (store/record-delta st3 {:id "done-2" :op :done :ns '*session*
                                            :parent (:head st3) :at 2})
                st5 (first (store/replace-node st4 'rb.core 'f
                                               (p/parse-string "(defn f [x] (+ x 2))")
                                               :prompt "off by two"))
                st6 (store/record-observation st5 ['rb.core-test]
                                              {:tier :external :status :red :ran 1
                                               :failures [{:test 'rb.core-test/t}]})]
            (is (true? (db/append! conn st6 (vec (drop (count (store/deltas st3)) (store/deltas st6)))
                                   ['rb.core] trunk (:head st3))))
            (is (= [{:form-id fid :test 'rb.core-test/t :n 2 :last (:head st6)}]
                   (db/reds-for conn [fid])))))
        (testing "a journal written before the index existed is indexed once at open"
          (jdbc/execute! conn ["DELETE FROM form_reds"])
          (is (empty? (db/reds-for conn [fid])) "fixture: the index is gone")
          (is (pos? (db/index-journal-reds! conn)))
          (is (= 2 (:n (first (db/reds-for conn [fid])))))
          (is (zero? (db/index-journal-reds! conn)) "and never twice")))
      (finally (.close conn)))))

(deftest ^:external a-forms-rank-round-trips-and-older-rows-take-their-position
  ;; `elements.rank` is the creation order the derived load order breaks ties
  ;; by. It rides beside `pos` (the derived position, which the kernel reads
  ;; at boot without a reference graph to derive from). A store written
  ;; before the column existed has rows with no rank; `open!` fills them
  ;; from `pos` once, which is exactly the tiebreak those rows were
  ;; arranged by.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            st    (-> (store/empty-store)
                      (store/ingest 'rr.core "(ns rr.core)\n(defn a [] 1)\n(defn b [] 2)\n"))
            a     (:id (store/form-named st 'rr.core 'a))
            b     (:id (store/form-named st 'rr.core 'b))
            nsf   (:id (store/form-named st 'rr.core 'rr.core))
            st    (store/order-forms st 'rr.core [nsf b a])]
        (is (true? (db/append! conn st (store/deltas st) ['rr.core] trunk nil)))
        (let [loaded (db/load-store conn trunk)]
          (is (= '[[rr.core 0] [b 2] [a 1]]
                 (mapv (juxt :name :rank) (store/forms loaded 'rr.core)))
              "the arranged order comes back by pos, the rank by its own column"))
        (testing "rows from before the column take their position as their rank"
          (jdbc/execute! conn ["UPDATE elements SET rank = NULL WHERE line = ?" trunk])
          (with-open [c2 (db/open! dir)]
            (is (= '[[rr.core 0] [b 1] [a 2]]
                   (mapv (juxt :name :rank) (store/forms (db/load-store c2 trunk) 'rr.core)))))))
      (finally (.close conn)))))

(deftest ^:external a-connection-older-than-the-schema-heals-on-its-first-failed-write
  ;; Three schema changes in one wave (form_refs, form_reds, elements.rank)
  ;; each broke the running host the same way: it hot-reloaded code that
  ;; writes the new column against a connection opened before the column
  ;; existed, and every write failed until someone ran `open!` by hand.
  ;; `open!` is the only thing that runs the DDL, and a live connection
  ;; never re-runs it. So the writer heals itself: a schema-shaped SQL
  ;; failure ("no such table", "no column named") runs `ensure-schema!` on
  ;; the connection and retries once — the same idempotent DDL `open!` ran.
  (let [dir  (temp-dir)
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            st    (store/ingest (store/empty-store) 'hs.core "(ns hs.core)\n(defn f [] 1)\n")]
        (is (true? (db/append! conn st (store/deltas st) ['hs.core] trunk nil)))
        ;; the connection's schema falls behind the code: the column the
        ;; writer inserts is gone (what an older store looks like to newer code)
        (jdbc/execute! conn ["ALTER TABLE elements DROP COLUMN rank"])
        (let [st2 (first (store/append-form (store/committed st) 'hs.core
                                            (p/parse-string "(defn g [] 2)")))]
          (is (true? (db/append! conn st2 (:pending st2) ['hs.core] trunk (:head st)))
              "the write heals the schema and lands, instead of failing on a column the code knows about")
          (is (= [0 1 2] (mapv :rank (store/forms (db/load-store conn trunk) 'hs.core)))
              "and the healed column carries what the write put there")))
      (finally (.close conn)))))
