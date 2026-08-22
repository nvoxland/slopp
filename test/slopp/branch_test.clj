(ns slopp.branch-test
  "Phase 4 m3: branches WITHIN one repo/session. A branch is an O(1) snapshot
  of the store (values are cheap); the single live image gets checkout
  semantics; merging down to main rides the m2 causal-delivery engine."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [slopp.ops :as ops] [slopp.ops.branch :as branch] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.store :as store] [slopp.store.db :as db] [slopp.store.render :as store.render]))

(def seed
  (str "(ns br.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn f [x] (inc x))\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

(deftest ^:external branch-edit-switch-isolation
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'br.core seed)
      (testing "branching is instant and starts identical (no image work)"
        (let [r (branch/branch! sess "feature")]
          (is (= "feature" (:branch r)))
          (is (= "main" (:from r))))
        (is (= [2] (ops/query-eval sess "(br.core/f 1)"))))
      (testing "branch edits are verified writes like any other"
        (let [r (ops/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 10))"
                                   :prompt "feature behavior")]
          (is (= 1 (:fail (:test r))))          ; honest red: f-t expects inc
          (is (= :genuine (:diagnosis (:test r)))))
        (ops/edit-replace! sess 'br.core 'f-t
                           "(deftest f-t (is (= 11 (f 1))))"
                           :prompt "test the feature behavior")
        (is (= [11] (ops/query-eval sess "(br.core/f 1)"))))
      (testing "switching back to main restores main's code AND image"
        (let [r (branch/branch-switch! sess "main")]
          (is (= "main" (:switched r))))
        (is (= [2] (ops/query-eval sess "(br.core/f 1)")))
        (is (re-find #"\(inc x\)" (query/query-source sess 'br.core))))
      (testing "and forward again"
        (branch/branch-switch! sess "feature")
        (is (= [11] (ops/query-eval sess "(br.core/f 1)"))))
      (finally (ops/close! sess)))))

(deftest ^:external merge-branch-down-to-main
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'br.core seed)
      (branch/branch! sess "feature")
      (ops/add-form! sess 'br.core "(defn g [x] (* 2 (f x)))"
                     :prompt "feature work" :agent "brancher")
      (ops/add-form! sess 'br.core "(deftest g-t (is (= 4 (g 1))))"
                     :agent "brancher")
      (branch/branch-switch! sess "main")
      ;; main did its own (different-form) work meanwhile
      (ops/add-form! sess 'br.core "(defn h [x] (- x 1))" :prompt "main work")
      (testing "merge lands the branch's work on main, verified"
        (let [r (branch/branch-merge! sess "feature")]
          (is (nil? (:error r)) (pr-str r))
          (is (empty? (:conflicts r)))
          (is (= 2 (:merged r)))
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
          (is (= [4] (ops/query-eval sess "(br.core/g 1)")))
          (is (re-find #"defn h" (query/query-source sess 'br.core)))))
      (testing "the branch can keep going and merge again exactly"
        (branch/branch-switch! sess "feature")
        (ops/edit-replace! sess 'br.core 'g "(defn g [x] (* 3 (f x)))"
                           :prompt "round 2")
        (ops/edit-replace! sess 'br.core 'g-t "(deftest g-t (is (= 6 (g 1))))")
        (branch/branch-switch! sess "main")
        (let [r (branch/branch-merge! sess "feature")]
          (is (empty? (:conflicts r)))
          (is (= 2 (:merged r)))
          (is (= [6] (ops/query-eval sess "(br.core/g 1)")))))
      (testing "same-form divergence surfaces the MV conflict; main stays live"
        (branch/branch-switch! sess "feature")
        (ops/edit-replace! sess 'br.core 'g "(defn g [x] :branch-version)")
        (branch/branch-switch! sess "main")
        (ops/edit-replace! sess 'br.core 'g "(defn g [x] :main-version)")
        (let [r (branch/branch-merge! sess "feature")]
          (is (= 1 (count (:conflicts r))))
          (is (re-find #":main-version" (query/query-source sess 'br.core)))))
      (finally (ops/close! sess)))))

(deftest ^:external branch-guards-and-listing
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'br.core seed)
      (branch/branch! sess "feature")
      (testing "listing shows every line and where we are"
        (let [b (branch/query-branches sess)]
          (is (= "feature" (:current b)))
          (is (= #{"main" "feature"} (set (map :name (:branches b)))))))
      (testing "guards"
        (is (:error (branch/branch! sess "feature")))          ; taken
        (is (:error (branch/branch! sess "main")))             ; reserved
        (is (:error (branch/branch-switch! sess "nope")))      ; unknown
        (is (:error (branch/branch-merge! sess "feature")))    ; into itself
        (is (:error (branch/branch-delete! sess "feature"))))  ; current
      (testing "delete after switching away"
        (branch/branch-switch! sess "main")
        (is (nil? (:error (branch/branch-delete! sess "feature"))))
        (is (= ["main"] (mapv :name (:branches (branch/query-branches sess))))))
      (finally (ops/close! sess)))))

(deftest ^:external branches-survive-restart-of-a-durable-session
  ;; The same AGENT reopens, which is what a restart is: the harness session
  ;; id outlives the process. That makes this test say something stronger than
  ;; it used to — the work below is never landed, so what survives the restart
  ;; is an open THREAD, resumed by adoption, with its store and image both
  ;; loaded from it rather than from the branch.
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-br-" (System/nanoTime))
        me  "br-restart"]
    (try
      (let [sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id me})]
        (try
          (ops/ingest! sess 'br.core seed)
          (branch/branch! sess "feature")
          (ops/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 10))"
                             :prompt "feature work")
          (ops/edit-replace! sess 'br.core 'f-t
                             "(deftest f-t (is (= 11 (f 1))))")
          (branch/branch-switch! sess "main")
          (finally (ops/close! sess))))
      (let [sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id me})]
        (try
          (testing "the branch is still there after reopen"
            (is (some #(= "feature" (:name %))
                      (:branches (branch/query-branches sess)))))
          (testing "switching to it restores its content and image — from the THREAD,
                    since none of this was ever landed"
            (branch/branch-switch! sess "feature")
            (is (= [11] (ops/query-eval sess "(br.core/f 1)"))))
          (finally (ops/close! sess))))
      (finally
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external per-branch-images-park-adopt-and-reap          ; m4
  (let [sess (external/open! {:slopp.ops/branch-image-ttl-ms 200})]
    (try
      (ops/ingest! sess 'br.core seed)
      (branch/branch! sess "feature")
      (ops/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 10))"
                         :prompt "feature behavior")
      (ops/edit-replace! sess 'br.core 'f-t "(deftest f-t (is (= 11 (f 1))))")
      (let [feature-port (:port (:image @sess))]
        (testing "switching away PARKS the branch image; main boots its own"
          (branch/branch-switch! sess "main")
          (is (not= feature-port (:port (:image @sess))))
          (is (= [2] (ops/query-eval sess "(br.core/f 1)")))
          (is (some? (get-in @sess [:lines "feature" :image]))))
        (testing "switching back ADOPTS the parked image — same process"
          (let [r (branch/branch-switch! sess "feature")]
            (is (:adopted r)))
          (is (= feature-port (:port (:image @sess))))
          (is (= [11] (ops/query-eval sess "(br.core/f 1)"))))
        (testing "idle parked images get reaped after the TTL"
          (branch/branch-switch! sess "main")
          (Thread/sleep 400)                       ; > ttl
          (ops/reap-idle-images! sess)
          (is (nil? (get-in @sess [:lines "feature" :image])))
          (testing "...and switching back just boots a fresh one, correct code"
            (let [r (branch/branch-switch! sess "feature")]
              (is (:booted r))
              (is (not= feature-port (:port (:image @sess))))
              (is (= [11] (ops/query-eval sess "(br.core/f 1)")))))))
      (finally (ops/close! sess)))))

(deftest ^:external branches-have-identity-beyond-their-name
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'br.core seed)
      (branch/branch! sess "feature")
      (let [id1 (:id (first (filter #(= "feature" (:name %))
                                    (:branches (branch/query-branches sess)))))]
        (is (string? id1))
        (ops/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 10))")
        (ops/edit-replace! sess 'br.core 'f-t "(deftest f-t (is (= 11 (f 1))))")
        (branch/branch-switch! sess "main")
        (branch/branch-merge! sess "feature")
        (branch/branch-delete! sess "feature")
        (testing "a RECREATED branch with the same name is a fresh identity"
          (branch/branch! sess "feature")
          (let [id2 (:id (first (filter #(= "feature" (:name %))
                                        (:branches (branch/query-branches sess)))))]
            (is (not= id1 id2)))
          ;; and it merges cleanly as its own line of work
          (ops/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 20))")
          (ops/edit-replace! sess 'br.core 'f-t "(deftest f-t (is (= 21 (f 1))))")
          (branch/branch-switch! sess "main")
          (let [r (branch/branch-merge! sess "feature")]
            (is (nil? (:error r)) (pr-str r))
            (is (empty? (:conflicts r)))
            (is (= [21] (ops/query-eval sess "(br.core/f 1)"))))))
      (finally (ops/close! sess)))))

(deftest ^:external concurrent-branch-creation-races-yield-one-winner
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'br.core seed)
      (let [results (doall (pmap (fn [_] (branch/branch! sess "contested"))
                                 (range 2)))
            wins    (filter #(= "contested" (:branch %)) results)
            errs    (filter :error results)]
        (is (= 1 (count wins)))
        (is (= 1 (count errs)))
        (is (re-find #"already exists" (:error (first errs)))))
      (finally (ops/close! sess)))))

(deftest ^:external merge-loads-changed-upstream-before-new-downstream
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'up.core "(ns up.core)\n\n(defn f \"F.\" [x] x)\n")
      (branch/branch! sess "feat")
      (let [r (ops/add-form! sess 'up.core "(defn g \"G.\" [x] (inc x))"
                             :prompt "upstream gains g on the branch")]
        (is (nil? (:error r)) (pr-str r)))
      (let [r (ops/add-form! sess 'up.core "(defn doomed \"D.\" [x] x)"
                             :prompt "added then deleted on the branch")]
        (is (nil? (:error r)) (pr-str r)))
      (let [r (ops/delete-form! sess 'up.core 'doomed :prompt "gone again")]
        (is (nil? (:error r)) (pr-str r)))
      (ops/module-dep! sess "up.web" "up.core" :prompt "the downstream web module calls the upstream core")
      (let [r (ops/create-ns! sess 'up.web
                              :source "(ns up.web (:require [up.core :as core]))\n\n(defn h \"H.\" [x] (core/g x))\n")]
        (is (nil? (:error r)) (pr-str r)))
      (branch/branch-switch! sess "main")
      (testing "the merge compiles the new downstream against the merged upstream"
        (let [r (branch/branch-merge! sess "feat")]
          (is (nil? (:error r)) (pr-str r))
          (is (some #{'up.web} (:new-nses r)) (pr-str r))
          (is (some? (store/form-named (:store @sess) 'up.core 'g)))
          (is (some? (store/form-named (:store @sess) 'up.web 'h)))
          (is (= [2] (ops/query-eval sess "(up.web/h 1)")))))
      (finally (ops/close! sess)))))

(deftest ^:external merge-hot-adds-the-branch-deps-before-loading
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'dp.core "(ns dp.core)\n\n(defn ^:unused-ok f \"F.\" [x] x)\n")
      (branch/branch! sess "deps-feat")
      (let [r (ops/deps-add! sess 'http-kit/http-kit {:mvn/version "2.8.0"})]
        (is (nil? (:error r)) (pr-str r)))
      (let [r (ops/create-ns! sess 'dp.web
                              :source "(ns dp.web (:require [org.httpkit.server :as hk]))\n\n(defn ^:unused-ok server-fn? \"S.\" [] (fn? hk/run-server))\n")]
        (is (nil? (:error r)) (pr-str r)))
      (branch/branch-switch! sess "main")
      (testing "the merge hot-adds the branch's deps before loading its namespaces"
        (let [r (branch/branch-merge! sess "deps-feat")]
          (is (nil? (:error r)) (pr-str r))
          (is (= {:mvn/version "2.8.0"}
                 (get-in @sess [:store :deps 'http-kit/http-kit])))
          (is (= [true] (ops/query-eval sess "(dp.web/server-fn?)")))))
      (finally (ops/close! sess)))))

(deftest ^:external a-durable-merge-with-config-on-both-sides-persists
  ;; H1 end to end: the pure merge test proves the journal has no dup id;
  ;; this proves the DURABLE path — db/append!'s UNIQUE-id insert — actually
  ;; commits when both lines set a capabilities key from the same fork (the
  ;; exact shape that failed permanently before the re-mint fix).
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'cb.core seed)
      (branch/branch! sess "feature")
      (ops/config-file! sess "capabilities" :key "http.port" :value "9090"
                        :prompt "branch sets a port")
      (branch/branch-switch! sess "main")
      (ops/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "main enables http")
      (testing "the merge COMMITS (no permanent 'store changed during merge')"
        (let [r (branch/branch-merge! sess "feature")]
          (is (nil? (:error r)) (pr-str r))
          (is (nil? (:conflict r)) (pr-str r))))
      (testing "both keys survive the durable round trip"
        (is (= "9090" (:value (ops/config-file! sess "capabilities" :key "http.port"))))
        (is (= "true" (:value (ops/config-file! sess "capabilities" :key "http.enabled")))))
      (finally (ops/close! sess)))))

(deftest ^:external a-branch-is-a-line-in-the-one-file
  ;; A branch used to BE a separate db under .slopp/branches/<name>, with the
  ;; whole store snapshotted into it — because `elements` could hold one view
  ;; per file and the write CAS ran on the global journal head. Both of those
  ;; are gone, so a branch is what it always meant: a NAME for a line, and a
  ;; line is a pointer to a head in the one journal.
  ;;
  ;; What that buys is the point of the assertions below. The two lines share
  ;; their history rather than copying it, so main's log is a PREFIX of the
  ;; branch's — a snapshot would have produced two independent logs of equal
  ;; length, which is the shape this replaces.
  ;;
  ;; Each land is what puts work on a branch AT ALL: a session writes to its
  ;; own thread, so without them both lines would be empty and every claim
  ;; below would be about a store nobody wrote to.
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-brline-" (System/nanoTime))]
    (try
      (let [sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "brline"})]
        (try
          (ops/ingest! sess 'br.core seed)
          (is (= "main" (:landed (branch/land-thread! sess))) "fixture: the seed reached main")
          (branch/branch! sess "feature")
          (ops/edit-replace! sess 'br.core 'f "(defn f [x] (+ x 10))"
                             :prompt "feature work")
          (is (= "feature" (:landed (branch/land-thread! sess)))
              "fixture: the feature work reached the feature branch")
          (finally (ops/close! sess))))

      (testing "no per-branch db file is written"
        (is (not (.exists (java.io.File. (str dir "/.slopp/branches"))))))

      (let [conn (db/open! dir)]
        (try
          (let [ls    (db/lines conn)
                by-id (into {} (map (juxt :id identity)) ls)
                feat  (first (filter #(= "feature" (:name %)) ls))
                trunk (first (filter #(= "main" (:name %)) ls))]
            (testing "the branch is a row in the ONE store, forked from main"
              (is (some? feat) (pr-str (mapv :name ls)))
              (is (= "branch" (:kind feat)))
              (testing "and it records WHICH line it split from — the agent's THREAD on
                        main, because a branch is created from the line you are on and
                        that is where any un-landed work would be"
                (let [from (by-id (:parent feat))]
                  (is (= "thread" (:kind from)) (pr-str from))
                  (is (= (:id trunk) (:parent from))))))

            (testing "each line holds its own view of the same namespace"
              (is (re-find #"\(\+ x 10\)"
                           (store.render/render-ns (db/load-store conn (:id feat)) 'br.core))
                  "the branch has the feature work")
              (is (re-find #"\(inc x\)"
                           (store.render/render-ns (db/load-store conn (:id trunk)) 'br.core))
                  "and main still has what it had"))

            (testing "and they SHARE history rather than copying it"
              (let [log-f (db/ancestry conn (:head feat))
                    log-m (db/ancestry conn (:head trunk))]
                (is (seq log-m) "fixture: main has history to share")
                (is (< (count log-m) (count log-f))
                    "the branch moved past main, so a prefix is a real claim here")
                (is (= log-m (subvec log-f 0 (count log-m)))
                    "main's log is a PREFIX of the branch's — a snapshot would have
                     produced two independent logs instead"))))
          (finally (.close conn))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external a-session-that-cannot-FIND-its-thread-says-so-instead-of-landing-quietly
  ;; Hit twice in one wave, and both times the recovery was a server restart
  ;; and a re-application of orphaned deltas. The session's line and the store's
  ;; line registry disagree: `land-thread!` looks its line up in `db/lines`,
  ;; finds no row, and falls out of `(when (= "thread" (:kind row)) …)`
  ;; returning nil.
  ;;
  ;; nil is ALSO what it correctly returns for an ephemeral session, a session
  ;; not on a thread, and a thread nobody wrote to. So `done!` — which only
  ;; assocs `:land` when the value is truthy — reported a green verdict with no
  ;; `:land` key at all, which is exactly the shape of a done that had nothing
  ;; to land. The work stayed on a thread nobody could reach, and the verdict
  ;; said everything was fine.
  ;;
  ;; The bar: a broken invariant must not share a return value with an ordinary
  ;; quiet outcome.
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-lostthread-" (System/nanoTime))]
    (try
      (let [sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "lost"})]
        (ops/ingest! sess 'lost.core seed)

        (testing "the session lands normally while its thread is findable"
          ;; population control: without this the assertions below could pass
          ;; against a session that was never able to land in the first place
          (is (= "main" (:landed (branch/land-thread! sess)))))

        (ops/edit-replace! sess 'lost.core 'f "(defn f [x] (+ x 99))"
                           :prompt "work that must not be lost")

        ;; the disagreement, made directly: the session names a line the
        ;; registry does not have. This is the state observed twice, reached
        ;; here in one write instead of by whatever race produces it.
        (swap! sess assoc :line "00000000-0000-0000-0000-000000000000")

        (let [r (branch/land-thread! sess)]
          (testing "landing REPORTS the broken invariant"
            (is (some? r)
                (str "land-thread! returned nil for a session whose line is"
                     " not in the registry — indistinguishable from having"
                     " nothing to land, which is how the work went missing"))
            (is (false? (:landed r)) (pr-str r))
            (is (string? (:reason r)) (pr-str r)))

          (testing "and DONE cannot report this as fine, which is where an agent reads it"
            ;; the end of the failure that actually happened: the tests were
            ;; green and the verdict said so, and nothing in the result said
            ;; the work was still sitting on an unreachable thread.
            ;;
            ;; Deliberately NOT asserting which way it comes out. Recording the
            ;; boundary delta on a line that is not there throws from the
            ;; append, so today this is loud rather than reported — and the
            ;; property worth pinning is the one that was violated: a session
            ;; that cannot land must not come back looking like a done with
            ;; nothing to land. Pinning the throw instead would freeze a
            ;; diagnostic that deserves to improve.
            (let [outcome (try {:result (external/done! sess :label "nowhere to land")}
                               (catch Exception e {:threw (or (.getMessage e) "throw")}))]
              (is (or (some? (:threw outcome))
                      (false? (:landed (:land (:result outcome)))))
                  (str "done reported a verdict that reads as clean for work"
                       " that cannot land: "
                       (pr-str (select-keys (:result outcome) [:land :done :findings]))))))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))
