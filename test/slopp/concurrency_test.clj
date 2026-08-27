(ns slopp.concurrency-test
  "Item 4: CRDT-aligned concurrent commits — the granularity dodge in action.
  Different-form concurrent writes both land; same-form contention surfaces a
  conflict (the Phase-1 face of C5's MV-register)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [slopp.store :as store]
            [slopp.ops :as ops] [slopp.ops.engine :as engine] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.read.history :as history] [slopp.store.db :as db]))

(def seed
  (str "(ns cc.core)\n"
       "(defn a [x] x)\n(defn b [x] x)\n(defn c [x] x)\n(defn d [x] x)\n"))

(deftest ^:external parallel-different-form-edits-all-land
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'cc.core seed)
      (let [results (doall
                     (pmap (fn [nm]
                             (ops/edit-replace! sess 'cc.core nm
                                                (format "(defn %s [x] (+ x %s))"
                                                        nm (int (first (str nm))))
                                                :prompt (str "bump " nm)))
                           '[a b c d]))]
        (testing "every concurrent different-form write succeeded"
          (is (every? #(nil? (:error %)) results))
          (is (every? #(nil? (:conflict %)) results)))
        (testing "no lost updates: all four changes present in the store"
          (let [src (query/query-source sess 'cc.core)]
            (doseq [nm '[a b c d]]
              (is (re-find (re-pattern (format "defn %s \\[x\\] \\(\\+ x" nm)) src)
                  (str nm " lost")))))
        (testing "all four :replace deltas recorded"
          (is (= 4 (count (filter #(= :replace (:op %))
                                  (store/deltas (:store @sess)))))))
        (testing "the image agrees with the store"
          (is (= [(+ 5 97)] (ops/query-eval sess "(cc.core/a 5)")))))
      (finally (ops/close! sess)))))

^:unsafe (deftest ^:external same-form-contention-surfaces-a-conflict
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'cc.core seed)
      ;; deterministic contention: between this write's hot-load and its
      ;; commit, a competing write to the SAME form lands (one-shot: the hook
      ;; must not re-fire on the rebase retry)
      (let [fired (atom false)
            r (binding [engine/*pre-commit-hook*
                        (fn [] (when (compare-and-set! fired false true)
                                 (binding [engine/*pre-commit-hook* nil]
                                   (ops/edit-replace! sess 'cc.core 'a
                                                      "(defn a [x] :competitor)"
                                                      :prompt "raced in first"))))]
                (ops/edit-replace! sess 'cc.core 'a "(defn a [x] :loser)"
                                   :prompt "should conflict"))]
        (is (some? (:conflict r)))
        (is (re-find #"changed concurrently" (str (:conflict r))))
        (testing "the competitor's write is what survived"
          (is (re-find #":competitor" (query/query-source sess 'cc.core)))
          (is (not (re-find #":loser" (query/query-source sess 'cc.core))))))
      (testing "but a DIFFERENT-form competitor rebases cleanly instead"
        (let [fired (atom false)
              r (binding [engine/*pre-commit-hook*
                          (fn [] (when (compare-and-set! fired false true)
                                   (binding [engine/*pre-commit-hook* nil]
                                     (ops/edit-replace! sess 'cc.core 'b
                                                        "(defn b [x] :other)"
                                                        :prompt "raced, different form"))))]
                  (ops/edit-replace! sess 'cc.core 'c "(defn c [x] :mine)"
                                     :prompt "should rebase and land"))]
          (is (nil? (:error r)))
          (is (nil? (:conflict r)))
          (let [src (query/query-source sess 'cc.core)]
            (is (re-find #":other" src))
            (is (re-find #":mine" src)))))
      (finally (ops/close! sess)))))

(deftest ^:external revert-restores-a-prior-version
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'rv.core "(ns rv.core)\n(defn f [x] x)\n")
      (ops/edit-replace! sess 'rv.core 'f "(defn f [x] (inc x))" :prompt "v2")
      (ops/edit-replace! sess 'rv.core 'f "(defn f [x] (+ 2 x))" :prompt "v3")
      (let [r (ops/revert-form! sess 'rv.core 'f)]     ; default: previous
        (is (nil? (:error r)))
        (is (re-find #"\(inc x\)" (query/query-source sess 'rv.core)))
        (is (= [6] (ops/query-eval sess "(rv.core/f 5)")))
        (testing "the revert is itself provenance"
          (is (re-find #"revert to"
                       (str (:prompt (last (history/query-lineage sess 'rv.core 'f))))))))
      (testing "revert to a specific delta from form history"
        (let [v1 (first (history/query-form-history sess 'rv.core 'f))
              r  (ops/revert-form! sess 'rv.core 'f :to (:delta v1))]
          (is (nil? (:error r)))
          (is (= [5] (ops/query-eval sess "(rv.core/f 5)")))))
      (testing "errors"
        (is (:error (ops/revert-form! sess 'rv.core 'nope)))
        (is (:error (ops/revert-form! sess 'rv.core 'f :to "d99999"))))
      (finally (ops/close! sess)))))

(deftest ^:external durable-concurrent-writers-share-the-journal   ; m5a storage inversion
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-m5a-" (System/nanoTime))]
    (try
      (let [sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "m5a"})]
        (try
          (ops/ingest! sess 'cc.core seed)
          (let [results (doall
                         (pmap (fn [nm]
                                 (ops/edit-replace! sess 'cc.core nm
                                                    (format "(defn %s [x] (+ x %s))"
                                                            nm (int (first (str nm))))
                                                    :prompt (str "bump " nm)))
                               '[a b c d]))]
            (is (every? #(and (nil? (:error %)) (nil? (:conflict %))) results)
                (pr-str (mapv #(select-keys % [:error :conflict]) results))))
          (finally (ops/close! sess))))
      ;; the journal is the record: a fresh session sees all four writes
      ;; the same agent returns — the journal records its line, and a fresh
      ;; identity would open a thread none of these writes reached
      (let [sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "m5a"})]
        (try
          (let [src (query/query-source sess 'cc.core)]
            (doseq [nm '[a b c d]]
              (is (re-find (re-pattern (format "defn %s \\[x\\] \\(\\+ x" nm)) src)
                  (str nm " lost from the journal"))))
          (finally (ops/close! sess))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

^:unsafe (deftest ^:external a-lost-race-does-not-leave-the-loser-in-the-image
  ;; Every conflict return runs AFTER hot-load. With TWO writers on one store,
  ;; the loser's own image holds its rejected code while the journal holds the
  ;; winner's — so the loser session's next verification runs against code the
  ;; store threw away. A lost race must heal the losing image back to what the
  ;; store holds.
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-heal-" (System/nanoTime))]
    (try
      (let [;; ONE agent id, so both writers are on one line. A race needs two
            ;; writers reaching for the same head; two identities would give
            ;; them a thread each and there would be no race to lose.
            a (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "heal"})]
        (try
          (ops/ingest! a 'ch.core
                       "(ns ch.core)\n(defn ^:unused-ok f \"D.\" [x] :original)\n")
          (let [b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "heal"})]
            (try
              (let [fired (atom false)
                    r (binding [engine/*pre-commit-hook*
                                (fn [] (when (compare-and-set! fired false true)
                                         (binding [engine/*pre-commit-hook* nil]
                                           (ops/edit-replace! b 'ch.core 'f
                                                              "(defn ^:unused-ok f \"D.\" [x] :winner)"
                                                              :prompt "raced in first"))))]
                        (ops/edit-replace! a 'ch.core 'f
                                           "(defn ^:unused-ok f \"D.\" [x] :loser)"
                                           :prompt "loses the race"))]
                (is (some? (:conflict r)) (pr-str r))
                (testing "the losing session's image answers with the winner"
                  (is (= [:winner] (ops/query-eval a "(ch.core/f 1)"))
                      "the image kept the loser's code after the conflict")))
              (finally (ops/close! b))))
          (finally (ops/close! a))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external two-sessions-on-one-store-MINT-FROM-DISJOINT-BLOCKS
  ;; The allocator, wired in. `reserve-id-block!` made disjointness possible;
  ;; this is the assertion that a real session actually uses it.
  ;;
  ;; Before it, every session read one shared counter out of `meta`, minted
  ;; from it, and wrote its own value back. Two sessions therefore started from
  ;; the same number, and the only thing between them and a collision was the
  ;; UNIQUE index on `deltas.id` — which is a guard firing, not a design
  ;; holding. When the shared number went STALE the guard fired forever and
  ;; locked both live sessions out of writing at once.
  ;;
  ;; A reservation removes the sharing instead of guarding it.
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-idblock-" (System/nanoTime))]
    (try
      ;; the store has to EXIST before two sessions can share it —
      ;; `external/open!` passes `{:create? false}`, so a dir with no db
      ;; yields a DIRLESS session with nothing to reserve from. That is the
      ;; real shape too: a second session cannot arrive at a store the first
      ;; has not materialized.
      (.close (db/open! dir))
      (let [a (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "blk-a"})]
        (try
          (let [b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "blk-b"})]
            (try
              (let [na (:next-id (:store @a))
                    nb (:next-id (:store @b))]

                (testing "each session opens holding its own range"
                  (is (not= na nb)
                      (str "both sessions opened on the same counter: " na))
                  (is (>= (Math/abs (long (- nb na))) db/id-block-size)
                      (str "the ranges overlap: " na " " nb)))

                (testing "and a session that WRITES after a foreign write stays in its block"
                  ;; the real path: a write refreshes the cache first, and the
                  ;; refresh falls back to a full `load-store` for ops it
                  ;; cannot replay. A loaded line view carries whatever the
                  ;; file's counter happens to be, so a session adopting it
                  ;; would silently begin minting inside another session's
                  ;; block — the collision this abolishes, through the back
                  ;; door.
                  (ops/ingest! b 'blk.other "(ns blk.other)\n\n(defn q \"Q.\" [x] x)\n")
                  (ops/ingest! a 'blk.mine "(ns blk.mine)\n\n(defn p \"P.\" [x] x)\n")
                  (let [now (:next-id (:store @a))]
                    (is (> now na) "the session minted nothing at all")
                    (is (< now (+ na db/id-block-size))
                        (str "this session's counter left its own block: " now
                             " started at " na))))

                (testing "and no id appears twice in the journal it can see"
                  (let [ids (mapv :id (store/deltas (:store @a)))]
                    (is (= (count ids) (count (distinct ids))) (pr-str ids)))))
              (finally (ops/close! b))))
          (finally (ops/close! a))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))
