(ns slopp.multiproc-test
  "Phase 4 m5b: TWO servers, ONE store dir — the per-agent-server split.
  The journal (m5a) arbitrates commits; sync-with-journal! lets each server
  notice and absorb the other's work (cache refresh + image catch-up +
  trace invalidation)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [next.jdbc :as jdbc]
            [rewrite-clj.parser]
            [slopp.store :as store]
            [slopp.store.db :as db]
            [slopp.store.render]
            [slopp.ops :as ops] [slopp.ops.branch :as branch] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.read.history :as history]))

(deftest ^:external two-servers-one-store
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-m5b-" (System/nanoTime))
        ;; ONE agent id across both servers, deliberately. This test is about two
        ;; PROCESSES writing the same line — the stale-but-different-form
        ;; rebase, the same-form race — and a session's line is now its
        ;; thread, which is keyed by agent. Two ids would give them two lines
        ;; and every contention assertion below would pass by never meeting.
        ;; An orchestrator running two processes for one agent (SLOPP_AGENT)
        ;; is exactly this shape.
        s1  (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "m5b"})]
    (try
      (ops/ingest! s1 'tp.core
                   (str "(ns tp.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(defn h [x] (dec x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (let [s2 (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "m5b"})]           ; second server, same dir
        (try
          (testing "server 2 opens onto server 1's work"
            (is (= [2] (ops/query-eval s2 "(tp.core/f 1)"))))

          (testing "s1 commits; s2 absorbs it — cache AND image"
            (ops/edit-replace! s1 'tp.core 'f "(defn f [x] (+ x 10))"
                               :prompt "s1's change" :agent "server-1")
            (ops/edit-replace! s1 'tp.core 'f-t
                               "(deftest f-t (is (= 11 (f 1))))")
            (let [r (ops/sync-with-journal! s2)]
              (is (pos? (:synced r 0))))
            (is (= [11] (ops/query-eval s2 "(tp.core/f 1)")))
            (is (re-find #"\(\+ x 10\)" (query/query-source s2 'tp.core))))

          (testing "and the other direction"
            (ops/add-form! s2 'tp.core "(defn g [x] (* 2 (f x)))"
                           :prompt "s2's addition" :agent "server-2")
            (ops/sync-with-journal! s1)
            (is (= [22] (ops/query-eval s1 "(tp.core/g 1)"))))

          (testing "a STALE different-form write rebases and lands (no sync needed)"
            (ops/edit-replace! s1 'tp.core 'f "(defn f [x] (+ x 100))")
            (ops/edit-replace! s1 'tp.core 'f-t
                               "(deftest f-t (is (= 101 (f 1))))")
            ;; s2 has NOT synced; its base is stale, but it touches h only
            (let [r (ops/edit-replace! s2 'tp.core 'h "(defn h [x] (- x 5))"
                                       :prompt "stale but different form")]
              (is (nil? (:error r)) (pr-str r))
              (is (nil? (:conflict r))))
            (ops/sync-with-journal! s1)
            (is (re-find #"\(- x 5\)" (query/query-source s1 'tp.core)))
            (is (re-find #"\(\+ x 100\)" (query/query-source s1 'tp.core))))

          (testing "a cross-server same-form race surfaces the conflict"
            (ops/edit-replace! s1 'tp.core 'h "(defn h [x] :server-1)")
            ;; s2 unsynced: edits the SAME form from a stale base
            (let [r (ops/edit-replace! s2 'tp.core 'h "(defn h [x] :server-2)")]
              (is (some? (:conflict r)) (pr-str r)))
            (is (re-find #":server-1" (query/query-source s1 'tp.core))))

          (testing "provenance shows which server did what"
            (ops/sync-with-journal! s2)
            (let [hist (pr-str (history/query-history s2))]
              (is (re-find #"server-1" hist))
              (is (re-find #"server-2" hist))))
          (finally (ops/close! s2))))
      (finally
        (ops/close! s1)
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external private-checkouts-shared-branch-storage        ; m5c
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-m5c-" (System/nanoTime))
        ;; one agent id across both servers — see two-servers-one-store. What is
        ;; private here is the CHECKOUT (which branch each server is on), and
        ;; that is per-session state; the storage is shared, which is the
        ;; claim. Two ids would make the work private too, and every
        ;; assertion about seeing each other would be about nothing.
        s1  (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "m5c"})]
    (try
      (ops/ingest! s1 'pc.core
                   (str "(ns pc.core)\n(defn f [x] (inc x))\n"))
      ;; server 1 branches and works there; its checkout is ITS state
      (branch/branch! s1 "feature")
      (ops/edit-replace! s1 'pc.core 'f "(defn f [x] (+ x 50))"
                         :prompt "feature work" :agent "server-1")
      (let [s2 (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "m5c"})]          ; server 2: own checkout (main)
        (try
          (testing "checkouts are per-server: s2 is on main, unaffected"
            (is (= "main" (:current (branch/query-branches s2))))
            (is (= [2] (ops/query-eval s2 "(pc.core/f 1)"))))
          (testing "s2 can see and switch to s1's branch (shared storage)"
            (is (some #(= "feature" (:name %))
                      (:branches (branch/query-branches s2))))
            (branch/branch-switch! s2 "feature")
            (is (= [51] (ops/query-eval s2 "(pc.core/f 1)"))))
          (testing "both on feature: commits flow across servers via the journal"
            (ops/edit-replace! s1 'pc.core 'f "(defn f [x] (+ x 500))"
                               :prompt "more feature work")
            (ops/sync-with-journal! s2)
            (is (= [501] (ops/query-eval s2 "(pc.core/f 1)"))))
          (testing "meanwhile s1 can go back to main independently"
            (branch/branch-switch! s1 "main")
            (is (= [2] (ops/query-eval s1 "(pc.core/f 1)")))
            (is (= "feature" (:current (branch/query-branches s2)))))
          (finally (ops/close! s2))))
      (finally
        (ops/close! s1)
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external incremental-sync-replays-the-suffix-exactly    ; backlog: no full reload
  (let [b  (store/ingest (store/empty-store) 'ir.core
                         "(ns ir.core)\n(defn f [x] x)\n(defn g [x] x)\n")
        ;; the writer's side: a realistic suffix of ops
        w  (-> b
               (store/replace-node 'ir.core 'f
                                   (rewrite-clj.parser/parse-string
                                    "(defn f [x] (+ x 1))")
                                   :prompt "edit" :agent "w")
               first
               (store/append-form 'ir.core
                                  (rewrite-clj.parser/parse-string
                                   "(defn h [x] (f x))")
                                  :prompt "add" :agent "w")
               first
               (store/remove-form 'ir.core 'g :prompt "drop" :agent "w")
               first)
        ;; the reader replays the suffix onto its trailing copy of b
        suffix (drop (count (store/deltas b)) (store/deltas w))
        r      (reduce store/replay-delta b suffix)]
    (testing "replay reproduces the writer's store exactly"
      (is (some? r))
      (is (= (slopp.store.render/render-ns w 'ir.core)
             (slopp.store.render/render-ns r 'ir.core)))
      (is (= (:next-id w) (:next-id r)))
      (is (= (store/deltas w) (store/deltas r))))
    (testing ":ingest in the suffix replays too — it used to force a reload"
      ;; The fallback was honest while `:ingest` recorded no per-form sources:
      ;; the elements table was the only account of what a namespace held. It
      ;; carries `:sources` and `:comments` now, so a whole new namespace
      ;; arrives incrementally like everything else — and it HAS to, because
      ;; the git projection derives each milestone's tree by folding the log.
      (let [w2 (store/ingest w 'ir.extra "(ns ir.extra)\n\n(defn z [] 1)\n")
            r2 (store/replay-delta r (last (store/deltas w2)))]
        (is (some? r2))
        (is (= (slopp.store.render/render-ns w2 'ir.extra)
               (slopp.store.render/render-ns r2 'ir.extra)))))))

(deftest ^:external an-out-of-band-elements-change-reaches-a-running-server
  ;; The store is the journal; `elements` is its MATERIALIZED form. A change
  ;; to the rows that appends no delta — a one-off migration, a repair script,
  ;; anything store_doctor-shaped — moved `data_version` while
  ;; `deltas-after` stayed empty, and every branch of refresh-cache! was gated
  ;; on that suffix. So the server kept its cached store indefinitely and
  ;; re-persisted the OLD shape over the migrated rows at its next write.
  ;; `restart` does not help: the stale value is upstream of the image.
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-oob-" (System/nanoTime))
        s   (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! s 'oob.core "(ns oob.core)\n(defn ^:unused-ok f [x] (inc x))\n")
      (ops/sync-with-journal! s)

      (testing "a foreign commit that touches no elements does NOT rebuild the cache"
        ;; The control, and it is the half that decides the design: every
        ;; branch here would pass if the fix were \"reload whenever
        ;; data_version moves\". Measured on this store, that reload is ~4s
        ;; against a ~3ms check, and git_map pins, the trace map and the
        ;; dep-surface cache all move data_version without touching a form.
        (let [before (:store @s)]
          (with-open [conn (db/open! dir)]
            (db/set-meta! conn "some-out-of-band-key" "v"))
          (ops/sync-with-journal! s)
          (is (identical? before (:store @s))
              "nothing about the code changed, so nothing should have been re-read")))

      (testing "a direct edit to the materialized rows IS absorbed"
        (with-open [conn (db/open! dir)]
          (jdbc/execute! conn ["UPDATE elements SET source = ? WHERE ns = ? AND name = ?"
                               "(defn ^:unused-ok f [x] (+ x 99))" "oob.core" "f"]))
        (ops/sync-with-journal! s)
        (is (re-find #"\+ x 99" (query/query-source s 'oob.core))
            "the rows moved under the server and the journal did not")
        (is (= 2 (count (store/deltas (:store @s))))
            "and the journal is untouched — this was never a delta"))
      (finally
        (ops/close! s)
        (clojure.java.shell/sh "rm" "-rf" dir)))))
