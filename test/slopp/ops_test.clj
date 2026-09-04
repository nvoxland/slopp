(ns slopp.ops-test
  "Tests for the operation surface as a SESSION sees it, rather than for any
  one function.

  `slopp.api` is where the store, the image and the filesystem meet, and the
  bugs that live here are bugs of composition: a materialization that serves
  two-day-old truth because nothing recorded what it was built from, a
  recycled session that can still see the previous tenant, an async boot that
  defers the connection along with the oracle. None of those are visible from
  inside a single function, so these tests open a real session, drive it
  through the public verbs, and assert on what it ends up holding.

  Mostly `^:external` for that reason. The narrower units — the artifact
  cache, history, deps, queries — have their own test namespaces under
  `slopp.api`; what lands here is what needs the whole thing running."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.ops.testrun :as testrun] [clojure.java.io :as io] [clojure.edn :as edn] [slopp.read.query :as query] [slopp.ops.external :as external] [slopp.store :as store] [clojure.java.shell] [slopp.image.repl :as repl] [slopp.store.artifacts :as artifacts] [slopp.kernel.boot :as boot] [clojure.string :as str] [slopp.image :as image] [slopp.ops.engine :as engine] [slopp.project.capabilities :as capabilities] [slopp.read.history :as history] [slopp.read.graph :as graph] [slopp.webdev.cljs :as cljs] [slopp.rules.webapp :as rules.webapp] [slopp.store.render :as store.render] [slopp.read.telemetry :as telemetry] [slopp.store.db :as db] [slopp.currency :as slopp.currency] [slopp.project.dev :as dev] [slopp.index.refs :as refs] [next.jdbc :as jdbc])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest ^:external create-ns-modes
  (let [sess (external/open!)]
    (try
      (testing ":source lands a whole namespace in one verified call (folded-in ingest)"
        (let [r (ops/create-ns! sess 'cn.core
                                :source "(ns cn.core)\n(defn f [x] (* 2 x))\n(defn g [x] (+ 1 x))\n"
                                :agent "alice")]
          (is (nil? (:error r)))
          (is (= 3 (:forms r)))
          (is (re-find #"defn f" (query/query-source sess 'cn.core)))
          (is (= [10] (ops/query-eval sess "(cn.core/f 5)")))))
      (testing ":source carries provenance via :agent"
        (is (some #(= "alice" (:agent %))
                  (history/query-lineage (ops/with-history sess) 'cn.core 'f))))
      (testing ":requires still scaffolds an empty namespace"
        (let [r (ops/create-ns! sess 'cn.util :requires ["[clojure.string :as str]"])]
          (is (nil? (:error r)))
          (is (re-find #"clojure.string" (query/query-source sess 'cn.util)))))
      (testing ":source with :requires merges the requires into the source's ns form (eval26 opus: the refusal cost a turn)"
        (let [r (ops/create-ns! sess 'cn.both
                                :source "(ns cn.both)\n(defn ^:unused-ok g [] 1)\n"
                                :requires ["[clojure.string :as str]"])]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #"\[clojure\.string :as str\]" (query/query-source sess 'cn.both)))))
      (finally (ops/close! sess)))))

(deftest ^:external operation-surface
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'demo
                   (str "(ns demo)\n"
                        "(defn add [x y] (+ x y))\n"
                        "(defn tainted [a] (swap! a inc))\n"))
      (testing "query.source renders current source from the store (VFS read)"
        (is (re-find #"defn add" (query/query-source sess 'demo))))
      (testing "query.symbol reports effectfulness (D6)"
        (is (false? (:effectful? (query/query-symbol sess 'demo 'add))))
        (is (true? (:effectful? (query/query-symbol sess 'demo 'tainted)))))
      (testing "query.references finds callers"
        ;; tainted is defined AFTER add — the caller must be the later form,
        ;; or the write is (correctly) refused by the cold-load gate (S1b)
        (let [r (ops/edit-replace! sess 'demo 'tainted
                                   "(defn tainted [a] (add (swap! a inc) 1))"
                                   :prompt "call add")]
          (is (nil? (:error r)) (pr-str r)))
        (is (seq (graph/query-references sess 'demo 'add))))
      (testing "a cycle (add calls tainted, which already calls add) AUTO-DECLARES"
        ;; mutual recursion has no legal order — the pipeline inserts a marked
        ;; declare instead of refusing; the agent writes none
        (let [r (ops/edit-replace! sess 'demo 'add
                                   "(defn add [x y] (tainted (atom (+ x y))))"
                                   :prompt "call tainted")]
          (is (nil? (:error r)) (pr-str r))
          (is (re-find #":auto-declare" (query/query-source sess 'demo)))))
      (testing "query.eval asks the live image (the oracle)"
        (is (= [7] (ops/query-eval sess "(+ 3 4)"))))
      (testing "edit.replace-form updates store + hot-reloads image"
        (let [r (ops/edit-replace! sess 'demo 'tainted "(defn tainted [a] a)"
                                   :prompt "defang")]
          (is (nil? (:error r)))
          (is (= [42] (ops/query-eval sess "(demo/tainted 42)")))))
      (testing "query.lineage shows provenance (ingest + replaces, with prompts)"
        (let [lin (history/query-lineage (ops/with-history sess) 'demo 'tainted)]
          (is (contains? (set (map :op lin)) :ingest))
          (is (contains? (set (map :op lin)) :replace))
          (is (some #(= "defang" (:prompt %)) lin))))
      (testing "build materializes .clj on demand (C1/C6 explicit build)"
        (let [dir (str (Files/createTempDirectory "slopp-build"
                                                  (make-array FileAttribute 0)))]
          (external/build! sess dir)
          (is (= (query/query-source sess 'demo) (slurp (str dir "/src/demo.clj"))))
          (is (.exists (clojure.java.io/file dir "deps.edn")))
          (testing "X4 guard: never into the running system, absolute only, no deps.edn clobber"
            (is (:error (external/build! sess ".")))
            (is (:error (external/build! sess (System/getProperty "user.dir"))))
            (spit (str dir "/deps.edn") "{:paths [\"src\"] :custom true}\n")
            (external/build! sess dir)
            (is (re-find #":custom" (slurp (str dir "/deps.edn")))))))
      (finally (ops/close! sess)))))

(deftest parse-test-summary-reads-the-runner-line
  (testing "a green clojure.test summary"
    (is (= {:ran 46 :assertions 1200 :failures 0 :errors 0 :status :green}
           (testrun/parse-test-summary
            "Testing slopp.foo\n\nRan 46 tests containing 1200 assertions.\n0 failures, 0 errors.\n"))))
  (testing "a red summary (singular/plural both parse)"
    (let [r (testrun/parse-test-summary
             "Ran 5 tests containing 10 assertions.\n2 failures, 1 error.\n")]
      (is (= :red (:status r)))
      (is (= 2 (:failures r)))
      (is (= 1 (:errors r)))))
  (testing "no summary present -> nil"
    (is (nil? (testrun/parse-test-summary "boom — the JVM died before any test ran")))))

(deftest ^:external build-routes-test-namespaces-to-test-dir
  ;; a normal Clojure layout: production under src/, tests under test/, off the
  ;; default classpath (a :test alias makes them runnable).
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'proj.core "(ns proj.core)\n(defn f [x] (inc x))\n")
      (ops/create-ns! sess 'proj.core-test
                      :requires ["[clojure.test :refer [deftest is]]"
                                 "[proj.core :as c]"])
      (ops/add-form! sess 'proj.core-test "(deftest f-t (is (= 2 (c/f 1))))")
      (let [dir (str (Files/createTempDirectory "slopp-testdir"
                                                (make-array FileAttribute 0)))
            f   #(clojure.java.io/file dir %)]
        (external/build! sess dir)
        (testing "production ns under src/, test ns under test/ (not src/)"
          (is (.exists (f "src/proj/core.clj")))
          (is (.exists (f "test/proj/core_test.clj")))
          (is (not (.exists (f "src/proj/core_test.clj")))))
        (testing "deps.edn puts test/ on a runnable :test extra-path"
          (let [m (clojure.edn/read-string (slurp (f "deps.edn")))]
            (is (= ["src"] (:paths m)))
            (is (= ["test"] (get-in m [:aliases :test :extra-paths]))))))
      (finally (ops/close! sess)))))

(deftest parse-test-failures-extracts-blocks
  (let [out (str "\nRunning tests in #{\"test\"}\n\nTesting foo.bar-test\n\n"
                 "FAIL in (my-test) (foo/bar_test.clj:12)\n"
                 "rush orders double\n"
                 "expected: (= 1 2)\n"
                 "  actual: (not (= 1 2))\n\n"
                 "ERROR in (other-test) (foo/bar_test.clj:20)\n"
                 "expected: nil\n"
                 "  actual: java.lang.ArithmeticException: boom\n"
                 " at foo (bar.clj:1)\n\n"
                 "Ran 5 tests containing 9 assertions.\n2 failures, 1 errors.\n")
        fs  (testrun/parse-test-failures out)]
    (testing "each FAIL/ERROR block becomes {:test :detail}"
      (is (= ["my-test" "other-test"] (mapv :test fs)))
      (is (re-find #"expected: \(= 1 2\)" (:detail (first fs))))
      (is (re-find #"boom" (:detail (second fs)))))
    (testing "blocks are capped and limited"
      (is (every? #(<= (count (:detail %)) 520) fs))
      (is (= 1 (count (testrun/parse-test-failures out :limit 1)))))))

(deftest ^:external inline-test-stores-build-a-runnable-suite
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'il.core
                   (str "(ns il.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (let [r (external/external-test-run! sess)]
        (is (= :green (:status r)) (pr-str r))
        (is (= 1 (:ran r)) (pr-str r)))
      (finally (ops/close! sess)))))

(deftest ^:external build-routes-tests-through-the-trace-runner-when-present
  ;; #121: the external tier can only trace if the built project carries the
  ;; trace runner. PRESENCE in the store is the condition — a store without it
  ;; must still build a deps.edn that runs, so it stays on plain cognitect.
  (let [sess (external/open!)
        tmp  #(str (java.nio.file.Files/createTempDirectory
                    % (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (ops/ingest! sess 'tb.core
                   (str "(ns tb.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (testing "no trace runner in the store — the build stays on cognitect"
        (let [dir (tmp "slopp-trace-build")]
          (external/build! sess dir)
          (let [d (slurp (java.io.File. dir "deps.edn"))]
            (is (re-find #"\"-m\" \"cognitect\.test-runner\"" d))
            (is (not (re-find #"slopp\.image\.testmain" d))))))
      (testing "the store provides one — both aliases route through it"
        (ops/create-ns! sess 'slopp.image.testmain
                        :source (str "(ns slopp.image.testmain \"Stub: presence is"
                                     " the condition build! reads.\")\n"
                                     "(defn -main [& _args] nil)\n"))
        (let [dir (tmp "slopp-trace-build2")]
          (external/build! sess dir)
          (let [d (slurp (java.io.File. dir "deps.edn"))]
            (is (= 2 (count (re-seq #"\"-m\" \"slopp\.image\.testmain\"" d))) d)
            (is (not (re-find #"\"-m\" \"cognitect\.test-runner\"" d))))))
      (finally (ops/close! sess)))))

(deftest ^:external query-commits-rows-carry-title-lines
  ;; frictions #7: needing ONE sha fetched five multi-paragraph commit-point
  ;; descriptions — the TOP rung already carried the whole story, inverting
  ;; the ladder. Rows carry the title line (+ :more-lines); the full prose
  ;; is one drill-down away via {commit "dN"}.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'qc.core "(ns qc.core)\n(defn ^:unused-ok f [x] x)\n")
      (external/done! sess :label "w")
      (external/commit-point! sess
                              "The title line\n\nThe body paragraph that must not ride the list.")
      (let [rows (ops/query-commits sess)
            row  (first rows)]
        (testing "the list rung is title lines"
          (is (= "The title line" (:description row)) (pr-str row))
          (is (pos? (:more-lines row 0)) (pr-str row)))
        (testing "the drill-down rung is one full commit-point"
          (let [full (ops/query-commits sess :commit (:commit row))]
            (is (map? full))
            (is (re-find #"body paragraph" (:description full)) (pr-str full)))))
      (finally (ops/close! sess)))))

(deftest ^:external ns-delete-retires-an-empty-unreferenced-namespace
  ;; frictions #10: there was NO ns deletion — a mistaken scaffold rode
  ;; every projection and build forever, plus lint noise from its unused
  ;; requires. Retirement mirrors creation: refuse while forms remain
  ;; (naming them), refuse while required (naming the requirers), then one
  ;; :ns-delete delta and the husk is gone from store, image, and rows.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'nd.gone "(ns nd.gone)\n(defn ^:unused-ok f [x] x)\n")
      (ops/ingest! sess 'nd.user (str "(ns nd.user (:require [nd.gone :as g]))\n"
                                      "(defn ^:unused-ok h [x] x)\n"))
      (testing "forms remaining → refused, named"
        (let [r (ops/delete-ns! sess 'nd.gone)]
          (is (re-find #"\bf\b" (str (:error r))) (pr-str r))
          (is (re-find #"edit_delete_form" (str (:error r))))))
      (ops/delete-form! sess 'nd.gone 'f :prompt "clear the husk")
      (testing "still required → refused, requirer named"
        (let [r (ops/delete-ns! sess 'nd.gone)]
          (is (re-find #"nd\.user" (str (:error r))) (pr-str r))
          (is (re-find #"ns_remove_require" (str (:error r))))))
      (let [rr (ops/remove-require! sess 'nd.user 'nd.gone :prompt "unwire")]
        (is (nil? (:error rr)) (pr-str rr)))
      (testing "a self-named def still blocks deletion (structural, not by-name)"
        (ops/ingest! sess 'nd.self "(ns nd.self)\n(def nd.self 1)\n")
        (let [r (ops/delete-ns! sess 'nd.self)]
          (is (re-find #"still holds" (str (:error r))) (pr-str r))))
      (testing "empty and unreferenced → deleted everywhere, delta id returned"
        (let [r (ops/delete-ns! sess 'nd.gone :prompt "retire the scaffold")]
          (is (= "nd.gone" (:deleted r)) (pr-str r))
          (is (string? (:delta r)) (pr-str r)))
        (is (nil? (get-in (:store @sess) [:namespaces 'nd.gone])))
        (is (= :ns-delete (:op (last (ops/journal sess))))))
      (finally (ops/close! sess)))))

(deftest await-image-is-a-noop-when-sync-and-surfaces-a-boot-failure-when-async
  ;; the async-boot contract: a synchronously-opened session has no
  ;; ready-promise, so await is instant; an async session whose background
  ;; boot FAILED surfaces that failure at await (not by hanging, not
  ;; silently) — the connection is already up, so the error rides the first
  ;; oracle call.
  (testing "no ready-promise (the sync default) → await returns immediately"
    (let [s (atom {:image :live})]
      (is (identical? s (ops/await-image! s)))))
  (testing "a delivered :ok returns the session"
    (let [p (promise) s (atom {:image-ready p :image :live})]
      (deliver p :ok)
      (is (identical? s (ops/await-image! s)))))
  (testing "a delivered boot error is rethrown at await"
    (let [p (promise) s (atom {:image-ready p})]
      (deliver p (ex-info "image boot failed" {}))
      (is (thrown-with-msg? Exception #"image boot failed" (ops/await-image! s))))))

(deftest ^:external async-image-boot-defers-the-oracle-not-the-connection
  ;; the server-startup fix: with :async-image?, open! returns as soon as the
  ;; store VALUE is loaded (the MCP handshake can complete instantly) while
  ;; the image boots on a background thread. Reads work at once; the oracle
  ;; is awaited on first use. Modelled as the real concurrent scenario: a
  ;; second session opens async onto a first session's live store.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-async-" (System/nanoTime))
        ;; both sessions are the same agent: the claim is about WHEN the second
        ;; one can read, not about what it is allowed to see
        s1  (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "async"})]
    (try
      (ops/ingest! s1 'async.core "(ns async.core)\n(defn twice [x] (* 2 x))\n")
      (let [s (external/open! {:slopp.ops/dir dir :slopp.ops/async-image? true
                              :slopp.ops/agent-id "async"})]
        (try
          (testing "async mode arms a ready-promise; the store reads immediately"
            (is (some? (:image-ready @s)) "async mode set a ready-promise")
            (is (contains? (:namespaces (:store @s)) 'async.core))
            (is (seq (:project (ops/session-brief s)))))
          (testing "await-image! brings the oracle up and it answers"
            (ops/await-image! s)
            (is (some? (:image @s)))
            (is (= [10] (ops/query-eval s "(async.core/twice 5)"))))
          (finally (ops/close! s))))
      (finally
        (ops/close! s1)
        (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest client-build-deps-injects-slopp-toolchain-for-client-stores
  ;; slopp self-provisions its client toolchain at BUILD time — never user
  ;; manifest deltas (D-web-contracts dogfood finding): malli into the runtime
  ;; channel, the configured compiler into the client channel — but only when the
  ;; store carries client code, so a non-client build stays byte-identical.
  (testing "a :cljs store gets malli (runtime) + the compiler (client)"
    (let [st (-> (store/empty-store)
                 (store/ingest 'app.view "(ns app.view)\n(defn ^:export main [] 1)\n"))
          st (first (store/record-module-platform st "app.view" :cljs))
          provided (external/client-build-deps st)]
      (is (contains? (:runtime provided) 'metosin/malli) (pr-str provided))
      (is (contains? (:client provided) 'org.clojure/clojurescript) (pr-str provided))))
  (testing "a non-client store injects nothing"
    (let [st (store/ingest (store/empty-store) 'app.core "(ns app.core)\n(defn f [] 1)\n")]
      (is (= {} (:runtime (external/client-build-deps st))))
      (is (= {} (:client (external/client-build-deps st)))))))

(deftest ^:external build-injects-slopp-client-toolchain-without-manifest-deps
  ;; the payoff of the two-config split: a store with client code and an EMPTY
  ;; user manifest still builds a deps.edn carrying slopp's compiler + malli.
  ;; The agent added nothing — slopp provisions its own plumbing at build time,
  ;; versioned centrally, with no :deps-add delta in the user's history.
  (let [sess (external/open!)
        dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-toolchain"
                   (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (ops/create-ns! sess 'cbt.view
                      :source "(ns cbt.view)\n\n(defn ^:export main \"Entry.\" [] 1)\n"
                      :platform "cljs")
      (is (nil? (:error (external/build! sess dir))))
      (let [d (edn/read-string (slurp (io/file dir "deps.edn")))]
        (is (contains? (:deps d) 'metosin/malli) (pr-str d))
        (is (contains? (get-in d [:aliases :cljs :extra-deps]) 'org.clojure/clojurescript)
            (pr-str d)))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (rm! (io/file dir)))
        (ops/close! sess)))))

(deftest ^:external build-stamps-the-head-it-was-materialized-from
  ;; Derived artifacts serve old truth silently: `uber` jarred a two-day-old
  ;; materialization and printed success. The mtime heuristic that first guarded
  ;; it was wrong twice over — a directory's mtime does not move when nested
  ;; files change, and a live session touches store.db constantly. slopp already
  ;; has the exact provenance token, the head delta id, so the materialization
  ;; states what it was built FROM and the check stops being a guess.
  ;;
  ;; It sits UNDER THE SOURCE ROOT, as a classpath resource, for the reason
  ;; tiers-resource-path gives: a build jars `src`, so a stamp outside it is
  ;; read by whoever ran the build and by nobody afterwards — which is exactly
  ;; how six "which slopp am I running" incidents went. Inside, the ARTIFACT
  ;; can be asked, and slopp.kernel.boot/jar-head is what asks it.
  (let [sess (external/open!)
        dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-stamp" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (ops/create-ns! sess 'stamp.core :source "(ns stamp.core)\n\n(defn f \"F.\" [x] x)\n")
      (external/build! sess dir)
      (let [stamp (io/file dir "src" boot/head-resource-path)
            head  (:head (:store @sess))]
        (is (.exists stamp)
            "the materialization records its provenance ON THE CLASSPATH")
        (is (= {:head head} (clojure.edn/read-string (slurp stamp)))
            "and it is exactly the head delta the store stood at"))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (rm! (io/file dir)))
        (ops/close! sess)))))

(deftest ^:external prune-requires-drops-dead-and-keeps-load-bearing
  ;; The done-point prunes a require that is genuinely dead, but KEEPS (marked
  ;; ^:side-effect) one whose target registers something a cold load would lose
  ;; — the in-image suite can't see that break, because the registration is
  ;; already loaded in the live image. So the decision is static, not just the
  ;; in-image verdict: an orphaned registering target is load-bearing.
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'pz.pure :source "(ns pz.pure)\n(defn g [] 1)\n")
      (ops/create-ns! sess 'pz.reg
                      :source "(ns pz.reg)\n(defmulti area identity)\n(defmethod area :sq [_] 42)\n")
      (ops/create-ns! sess 'pz.app
                      :source "(ns pz.app (:require [pz.pure :as p] [pz.reg :as r]))\n(defn f [] 1)\n")
      (let [r (ops/prune-requires! sess 'pz.app :agent "t")]
        (testing "the dead pure require is pruned; the registering one is kept"
          (is (= '[pz.pure] (:pruned r)) (pr-str r))
          (is (= '[pz.reg]  (:kept r))   (pr-str r)))
        (testing "the ns form drops the dead require and marks the kept one"
          (let [ns-src (:source (query/query-brief sess 'pz.app 'pz.app))]
            (is (not (re-find #"pz\.pure" (str ns-src))) (str ns-src))
            (is (re-find #":side-effect" (str ns-src)) (str ns-src))
            (is (re-find #"pz\.reg" (str ns-src)) (str ns-src)))))
      (testing "a second prune is a no-op: the kept require is marked, not re-tried"
        (let [r2 (ops/prune-requires! sess 'pz.app :agent "t")]
          (is (= [] (:pruned r2)) (pr-str r2))
          (is (= [] (:kept r2)) (pr-str r2))))
      (finally (ops/close! sess)))))

(deftest ^:external a-recycled-session-cannot-see-the-previous-tenant
  ;; The isolation an image gives is the whole reason the external tier exists,
  ;; and reuse is only acceptable if it survives intact. The previous attempt
  ;; at cheaper images (the warm pool) also had to prove this, and its test —
  ;; pooled-open-stays-isolated — is the shape being repeated here.
  ;;
  ;; The :reuses assertion is the load-bearing one. Without it this test would
  ;; pass trivially the day recycling silently stopped happening, which is the
  ;; vacuous-guard failure this codebase has been bitten by before.
  ;;
  ;; ISOLATION HAS TWO DIRECTIONS, and only one of them was checked here for a
  ;; long time. Nothing of the first tenant may leak INTO the second — and the
  ;; second must also lose no CAPABILITY the first happened to use. The second
  ;; half was the live bug: an episode that runs the schema oracle lazily
  ;; requires `malli.generator`, `reset-to-baseline!` unmapped it with
  ;; `remove-ns` without retracting it from `*loaded-libs*`, and the next
  ;; tenant's `require` became a silent no-op — so every honest schema in ITS
  ;; episode was reported as drift. A sweep that only removes leaves an image
  ;; claiming a lib it no longer has.
  (repl/drain-parked!)
  (let [a (external/open!)]
    (ops/ingest! a 'tenant.one
                 (str "(ns tenant.one)\n(defn secret \"S.\" [] 42)\n"
                      "(defn ^{:malli/schema [:=> [:cat :int] :string]} liar \"L.\" [x] (inc x))\n"))
    (is (= 42 (first (repl/eval! (:image @a) "(tenant.one/secret)")))
        "the first tenant really did load into its image")
    ;; CONTROL for the verdict assertion far below: the first tenant's oracle
    ;; must actually RUN, or it never loads the lib whose loss is under test
    ;; and the second tenant passes for no reason.
    (is (= '[tenant.one/liar]
           (mapv :form (get-in (external/done! a :label "tenant one") [:findings :schema-drift])))
        "the first tenant's schema oracle ran, which is what loads it")
    (ops/close! a))
  (let [b (external/open!)]
    (try
      (is (pos? (:reuses (:image @b) 0))
          "the second session must actually REUSE an image, or this proves nothing")
      (is (nil? (first (repl/eval! (:image @b) "(find-ns 'tenant.one)")))
          "and must not be able to see the previous tenant's namespaces")
      (is (nil? (first (repl/eval! (:image @b) "(resolve 'tenant.one/secret)"))))
      (ops/ingest! b 'tenant.two
                   (str "(ns tenant.two)\n"
                        ;; the tenant only needs these vars to EXIST — declared,
                        ;; so the lint assertion below guards against a surprise
                        ;; rather than against a known fixture smell
                        "(defn ^{:unused-ok \"fixture surface\"} v \"V.\" [] 7)\n"))
      (is (= 7 (first (repl/eval! (:image @b) "(tenant.two/v)")))
          "a recycled image is fully WORKING, not merely empty")
      (testing "and the recycled tenant's own VERDICT is unaffected"
        (ops/add-form! b 'tenant.two
                       (str "(defn ^{:malli/schema [:=> [:cat :int] :int]"
                             " :unused-ok \"fixture surface\"}"
                             " honest \"H.\" [x] (inc x))")
                       :prompt "an honest schema the oracle must still be able to check")
        (let [r (external/done! b :label "tenant two")]
          (is (nil? (get-in r [:findings :schema-drift]))
              (str "an honest schema must draw no finding — a checker that could not"
                   " run reports here too: " (pr-str (:findings r))))
          (is (empty? (:lint r)) (pr-str (:lint r)))))
      (finally (ops/close! b) (repl/drain-parked!)))))

(deftest ^:external store-health-counts-the-artifact-cache
  ;; store_health exists because uncounted bytes accumulate — a tree snapshot
  ;; reached 94% of a 344MB journal across 239 commit-points with nothing
  ;; measuring it. Moving the compiled bundle out of the delta log and into a
  ;; directory no tool reported would have been that same mistake with a
  ;; better hiding place.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-health" (make-array FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/create-ns! sess 'health.core :source "(ns health.core)\n\n(defn f \"F.\" [x] x)\n")
      (let [kept   (artifacts/put! dir (.getBytes "referenced\n" "UTF-8")
                                   {:kind :build :tool "compile_client"})
            _      (artifacts/put! dir (.getBytes "left behind\n" "UTF-8") {:kind :build})
            _      (let [base (:store @sess)]
                     (engine/try-commit! sess base
                                         (first (store/record-artifact base "public/x.js" kept))
                                         []))
            health (external/store-health sess)]
        (testing "the journal is still reported"
          (is (pos? (get-in health [:deltas :n]))))
        (testing "and so is the cache the journal no longer carries"
          (is (= 2 (get-in health [:artifacts :n])))
          (is (= (:bytes kept) (get-in health [:artifacts :live :bytes]))
              "measured against the SESSION's store, not an empty one")
          (is (= 1 (get-in health [:artifacts :orphaned :n]))
              "and the reclaimable half is called out separately")))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (ops/close! sess)
          (rm! (io/file dir)))))))

(deftest ^:external build-reports-artifacts-it-could-not-materialize
  ;; The materialization loop swallowed misses: `when-let` on :bytes skipped
  ;; the file and the build returned {:built …} regardless. That is the
  ;; failure the recipe exists to make legible, reproduced one line under a
  ;; comment saying so — a cold clone would compile against a tree quietly
  ;; missing a file and hit it much later as something unrelated.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-build-miss" (make-array FileAttribute 0)))
        out  (str (Files/createTempDirectory
                   "slopp-build-out" (make-array FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/create-ns! sess 'miss.core :source "(ns miss.core)\n\n(defn f \"F.\" [x] x)\n")
      (let [entry (artifacts/put! dir (.getBytes "compiled bytes\n" "UTF-8")
                                  {:kind :build :tool "compile_client"})]
        (let [base (:store @sess)]
          (engine/try-commit! sess base
                              (first (store/record-artifact base "public/cljs/main.js" entry))
                              []))
        (testing "cache hit: the file lands and the build stays quiet"
          (let [r (external/build! sess out)]
            (is (empty? (:missing-artifacts r)) (pr-str r))
            (is (.exists (io/file out "public/cljs/main.js")))))
        (testing "cache cleared: the build REPORTS the gap rather than omitting it silently"
          (.delete (artifacts/cache-file dir (:sha entry)))
          (.delete (io/file out "public/cljs/main.js"))
          (let [r (external/build! sess out)
                m (first (:missing-artifacts r))]
            (is (= 1 (count (:missing-artifacts r))) (pr-str r))
            (is (= "public/cljs/main.js" (:path m)))
            (is (re-find #"compile_client" (str (:refill m))) (pr-str m))
            (is (not (.exists (io/file out "public/cljs/main.js")))
                "the file really is absent — the report is describing reality"))))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (ops/close! sess)
          (rm! (io/file dir))
          (rm! (io/file out)))))))

(deftest ^:external creating-a-namespace-that-shadows-a-classpath-one-warns
  ;; Found by bricking a real project. `slopp-ui` created `slopp.review.views`
  ;; holding two of its own views; a project's MCP server runs the FULL slopp
  ;; jar and `slopp.kernel.boot` loads store namespaces FIRST, so at the next boot
  ;; slopp's own `slopp.review.pages` died on `No such var: views/module-graph`.
  ;; The store was then unopenable by the only tool that could remove the
  ;; namespace again.
  ;;
  ;; It WARNS rather than refuses, and that distinction is the whole design:
  ;; overriding a slopp namespace is a supported capability, not an accident.
  ;; `slopp.image.testmain` is exactly how a store supplies its own trace
  ;; runner — `verification-test/external-tier-trace-absorbs-into-the-session`
  ;; does it on purpose — so a guard that refused would have broken a
  ;; documented extension point to prevent a naming mistake.
  (let [sess (external/open!)]
    (try
      (testing "a name slopp itself owns is created, and SAYS it will shadow"
        ;; `slopp.http.html` rather than the `slopp.review.views` of the incident:
        ;; that namespace does not exist here any more — the reviewer UI moved
        ;; out — and a fixture naming a namespace slopp no longer owns asserts
        ;; nothing while still passing today, because the CHECK is classpath
        ;; ownership. Pick one that is load-bearing and going nowhere.
        (let [r (ops/create-ns! sess 'slopp.http.html :source "(ns slopp.http.html)\n")
              w (first (filter #(= :shadows-classpath-ns (:kind %)) (:warnings r)))]
          (is (nil? (:error r)) "overriding is legitimate — it must still be possible")
          (is (some? w) (pr-str r))
          (is (re-find #"slopp\.http\.html" (str (:message w)))
              "name it: the agent chose the name and has to know which one bites")
          (is (re-find #"(?i)shadow" (str (:message w))))))
      (testing "a dependency's namespace warns for the same reason"
        (let [r (ops/create-ns! sess 'clojure.string :source "(ns clojure.string)\n")]
          (is (some #(= :shadows-classpath-ns (:kind %)) (:warnings r)) (pr-str r))))
      (testing "a name the project owns warns about nothing"
        ;; the root slopp-ui settled on, and the reason it is safe: `slopp-ui`
        ;; is a different SEGMENT from `slopp`, so it cannot collide
        (let [r (ops/create-ns! sess 'slopp-ui.views :source "(ns slopp-ui.views)\n")]
          (is (nil? (:error r)) (pr-str r))
          (is (empty? (filter #(= :shadows-classpath-ns (:kind %)) (:warnings r)))
              (pr-str r))))
      (finally (ops/close! sess)))))

(deftest a-whole-store-check-supersedes-a-stale-episode-verdict
  ;; friction 14. `done` reports :test-status :none whenever the episode's
  ;; changed forms have no covering tests — a rename, a docstring, a :cljs
  ;; edit. commit_point then reaches back to the last done that DID judge,
  ;; which can be arbitrarily old, and no amount of new work supersedes it:
  ;; each new done judges nothing either, so the store gets greener while the
  ;; commit-point stays refused. full_check ALREADY records its verdict as a
  ;; :verify delta scoped :full-check; nothing read it.
  (let [red   (first (store/record-done (store/empty-store) "r"
                                        :findings {:test-status :red :failures 2}))
        write (store/record-verification red '[some.ns] {:status :green})
        full  (store/record-verification write '[a.b]
                                         {:status :green :scope :full-check
                                          :namespaces 3 :lint-errors 0})]
    (is (= :red (:test-status (ops/last-judged-done red))))

    (testing "a per-write :verify is not a whole-store judgement"
      ;; record-verification lands on ordinary writes too, and those are
      ;; form-scoped. Only :scope :full-check judged the whole store.
      (is (= :red (:test-status (ops/last-judged-done write)))))

    (testing "a green full_check supersedes the stale episode verdict"
      (is (= :green (:test-status (ops/last-judged-done full))))
      (is (= :full-check (:scope (ops/last-judged-done full)))))

    (testing "informational counts stay OUT of the verdict"
      ;; commit_point derives its refusal reason from whichever keys are
      ;; present and non-zero, so a namespace count would make a refusal say
      ;; "namespaces" as though that were the thing that fired.
      (is (nil? (:namespaces (ops/last-judged-done full)))))

    (testing "and a later done supersedes the full_check in turn"
      (let [after (first (store/record-done full "r2"
                                            :findings {:test-status :red :failures 1}))]
        (is (= :red (:test-status (ops/last-judged-done after))))))))

(deftest slopp-supplies-the-framework-to-stores-that-USE-it
  ;; D-framework-injection. A store does not declare the framework and slopp
  ;; supplies it, the way it already supplies nREPL and malli
  ;; (repl/inherent-deps) and the cljs compiler (client-build-deps).
  ;;
  ;; Part 2: what is supplied is FILES, not a coord — the framework is never
  ;; published, so a coord names something only one machine can resolve.
  ;;
  ;; **Part 3 (capabilities): the files are keyed BY CAPABILITY and a store is
  ;; given only the families it USES.** What that buys is the payload: a
  ;; command-line app carries no `slopp/http/**` and inherits none of http's
  ;; deps. What it deliberately does NOT buy is enforcement of the opt-in —
  ;; `used-families` reads requires and entry markers, not `*.enabled`, so a
  ;; store whose requires outlive its config still loads the framework. That is
  ;; the case a migration is, and withholding the framework there would turn a
  ;; config error into a store that cannot boot to be repaired.
  ;;
  ;; The two CONDITIONS survive, each load-bearing in a different direction:
  ;;
  ;;   USES but does not DEFINE. slopp's own store CONTAINS slopp.http.*, so
  ;;   vendoring into it would put a second copy on the classpath ahead of the
  ;;   materialized one — `src` is the FIRST entry, so the copy would win and
  ;;   slopp would test its shipped framework instead of the code being edited.
  ;;
  ;;   USES, not merely exists. A store with no code for a family needs none of
  ;;   its files, and writing them into every image would cost every fixture
  ;;   boot for nothing.
  (let [uses  (-> (store/empty-store)
                  (store/ingest 'app.web
                                (str "(ns app.web (:require [slopp.http :as web]))\n"
                                     "(defn handler \"H.\" [req] (web/handle! req))\n")))
        plain (-> (store/empty-store)
                  (store/ingest 'app.core "(ns app.core)\n(defn f [x] x)\n"))
        defines (-> (store/empty-store)
                    (store/ingest 'slopp.http "(ns slopp.http)\n(defn handle! [r] r)\n")
                    (store/ingest 'app.web
                                  (str "(ns app.web (:require [slopp.http :as web]))\n"
                                       "(defn g \"G.\" [r] (web/handle! r))\n")))
        ;; a CLI app as slopp actually generates one: commands and nothing
        ;; else. It never requires slopp.cli, because slopp writes the launcher.
        cli-app (-> (store/empty-store)
                    (store/ingest 'app.cmds
                                  (str "(ns app.cmds)\n"
                                       "(defn ^{:cli/command \"add\" :cli/args [:catn]} add \"A.\" [ctx args] args)\n")))
        files {"_"    {"slopp/lang.cljc" "(ns slopp.lang)"}
               "http" {"slopp/http.clj" "(ns slopp.http)"}
               "cli"  {"slopp/cli.clj" "(ns slopp.cli)"}}]
    (testing "a store that USES a family is given THAT family and the syntax"
      (let [got (engine/framework-injection uses files)]
        (is (contains? got "slopp/http.clj") (pr-str (keys got)))
        (is (contains? got "slopp/lang.cljc") "the dialect's own helpers always ride")
        (is (not (contains? got "slopp/cli.clj"))
            (str "a web app must not be handed cli — vendoring everything makes"
                 " the opt-in advisory at runtime: " (pr-str (keys got))))))
    (testing "a cli app is detected by its MARKER, not by a require"
      ;; the only usage signal there is for cli: with a generated entry the app
      ;; writes commands and slopp writes the launcher, so nothing in the store
      ;; ever names slopp.cli. Same shape as ^:app/entry, which is why the
      ;; uses-not-merely-requires condition already had this door.
      (let [got (engine/framework-injection cli-app files)]
        (is (contains? got "slopp/cli.clj") (pr-str (keys got)))
        (is (not (contains? got "slopp/http.clj")) (pr-str (keys got)))))
    (testing "a store with no framework code at all gets nothing"
      (is (nil? (engine/framework-injection plain files))))
    (testing "and a store that DEFINES a family gets none of THAT family —
              vendoring there would shadow the very code being edited"
      (is (nil? (engine/framework-injection defines files))))
    (testing "a host that cannot supply the framework (a checkout, a clojure -M
              run) vendors nothing rather than half of it"
      (is (nil? (engine/framework-injection uses nil)))
      (is (nil? (engine/framework-injection uses {}))))))

(deftest ^:external capabilities-config-validates-at-write
  (let [sess (external/open!)]
    (try
      (testing "an unknown capability key is refused with teaching"
        (let [r (ops/config-file! sess "capabilities" :key "web.prot" :value "8080"
                                  :prompt "typo'd key")]
          (is (re-find #"web\.prot" (str (:error r))) (pr-str r))
          (is (re-find #"query_capabilities" (str (:error r))) (pr-str r))))
      (testing "a bad value is refused with the type teaching"
        (let [r (ops/config-file! sess "capabilities" :key "http.port" :value "banana"
                                  :prompt "bad port")]
          (is (re-find #"integer" (str (:error r))) (pr-str r))
          (is (nil? (get-in (:store @sess) [:config "capabilities" :values "http.port"]))
              "the refused value never landed")))
      (testing "a good value lands and takes effect"
        (let [r (ops/config-file! sess "capabilities" :key "http.port" :value "7357"
                                  :prompt "real port")]
          (is (nil? (:error r)) (pr-str r))
          (is (= 7357 (capabilities/effective (:store @sess) "http.port")))))
      (testing "a wildcard-governed key is known, not alien"
        (let [r (ops/config-file! sess "capabilities" :key "http.auth.groups.admin.members" :value "alice,bob"
                                  :prompt "a group")]
          (is (nil? (:error r)) (pr-str r))
          (is (= #{"alice" "bob"} (capabilities/effective (:store @sess) "http.auth.groups.admin.members")))))
      (testing "a key under an undeclared owner is refused like any unknown key"
        (let [r (ops/config-file! sess "capabilities" :key "groups.admin.members" :value "alice"
                                  :prompt "the retired spelling")]
          (is (re-find #"is not a capability" (str (:error r))) (pr-str r))))
      (testing "unset returns to the default"
        (ops/config-file! sess "capabilities" :key "http.port" :unset true
                          :prompt "back to default")
        ;; http.port's declared default is nil now — serve! owns the 8080 and the
      ;; dev server derives, so "returns to the default" means returns to unset
      (is (nil? (capabilities/effective (:store @sess) "http.port"))))
      (finally (ops/close! sess)))))

(deftest ^:external config-writes-say-whether-anything-validated-them
  ;; A config write records the key and value as given, so a caller cannot tell
  ;; a checked write from an unchecked one unless the result says which
  ;; happened. Saying so is D-surface-honesty at config grain.
  ;;
  ;; TWO paths are checked now. `rules` gained a registry after this very
  ;; admission was probed: the note named `capabilities` as the only validated
  ;; path, which is how the reader learned a mistyped rule dial and a renamed
  ;; one were the same event — accepted, governing nothing, unreported. The
  ;; honest absence is what got it fixed, which is the argument for printing it.
  (let [sess (external/open!)]
    (try
      (testing "a capabilities write was checked against the registry"
        (let [r (ops/config-file! sess "capabilities" :key "http.port" :value "7357"
                                  :prompt "real port")]
          (is (= [:registry] (:verified r)) (pr-str r))
          (is (= [] (:unverified r)) (pr-str r))))
      (testing "and so was a rules write — the second registry, same claim"
        (let [r (ops/config-file! sess "rules" :key "key-typos" :value "off"
                                  :prompt "quiet that rule")]
          (is (= [:registry] (:verified r)) (pr-str r))
          (is (= [] (:unverified r)) (pr-str r))))
      (testing "a path with no registry is recorded UNVALIDATED, and says so"
        (let [r (ops/config-file! sess "client" :key "anything.at.all" :value "x"
                                  :prompt "nothing governs this")]
          (is (= [] (:verified r)) (pr-str r))
          (is (= [:schema] (:unverified r)) (pr-str r))
          (is (and (re-find #"capabilities" (str (:note r)))
                   (re-find #"rules" (str (:note r))))
              (str "the note must name the paths that ARE checked — a bare "
                   "'unvalidated' tells a reader nothing to do about it: "
                   (pr-str (:note r))))))
      (finally (ops/close! sess)))))

(deftest ^:external a-store-with-a-broken-namespace-stays-editable
  ;; slopp-ui, wedged for real (2026-08-06): the vendored framework renamed a
  ;; var, one stored form stopped compiling, and the ORACLE boot refused on
  ;; first failure — the Throwable parked in :image-ready, await-image!
  ;; rethrew it in front of every non-read tool, and the edit that would fix
  ;; the namespace was blocked by the brokenness it would fix. The kernel
  ;; HOST boot already notes-and-continues, and its note PROMISES "the store
  ;; stayed open on purpose so you can FIX them"; the oracle layer broke the
  ;; promise. The wedge population (their correction, sharper than their
  ;; report): code verified-good at write time and invalidated from OUTSIDE —
  ;; a jar rename, a dep bump, or exactly this, a :cljs declaration stranding
  ;; a JVM caller. Per-write verification means nothing INSIDE a store can
  ;; produce this state.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'lib.core "(ns lib.core)\n\n(defn f \"F.\" [x] x)\n")
      (ops/module-dep! sess "app.core" "lib.core" :prompt "consumer edge")
      (ops/ingest! sess 'app.core
                   "(ns app.core (:require [lib.core :as l]))\n\n(defn g \"G.\" [x] (l/f x))\n")
      (ops/ingest! sess 'other.core "(ns other.core)\n\n(defn h \"H.\" [x] x)\n")
      (let [r (ops/module-platform! sess "lib.core" "cljs"
                                    :prompt "the stranding move — lib.core leaves the JVM")]
        (is (nil? (:error r)) (pr-str r)))

      (testing "a fresh oracle NOTES the failed namespace and keeps the session alive"
        (is (some? (ops/restart! sess))
            "restart completes instead of throwing the first load failure")
        (is (= '[app.core] (mapv :ns (:image-load-failures @sess)))
            "recorded where the engine can consult it — not thrown, not a parked Throwable"))

      (testing "work in an unrelated namespace continues"
        (let [r (ops/edit-replace! sess 'other.core 'h
                                   "(defn h \"H.\" [x] (inc x))"
                                   :prompt "unrelated work while app.core is broken")]
          (is (nil? (:error r)) (pr-str r))))

      (testing "done EXCLUDES the unloadable namespace and REPORTS it, rather than dying"
        (let [r (external/done! sess :label "wedge check")]
          (is (map? r) (pr-str r))
          (is (= '[app.core] (mapv :ns (get-in r [:findings :unloadable-namespaces])))
              "excluded-and-reported, the traced-run! :external-pending pattern — never silent")))

      (testing "the edit that FIXES the broken namespace verifies against the POST-edit state"
        (let [r (ops/edit-group-once! sess
                                 [{:action :replace :ns 'app.core :name 'app.core
                                   :source "(ns app.core)"}
                                  {:action :replace :ns 'app.core :name 'g
                                   :source "(defn g \"G.\" [x] x)"}]
                                 :prompt "drop the stranded require and call — the one-edit unwedge")]
          (is (nil? (:error r)) (pr-str r)))
        (is (empty? (:image-load-failures @sess))
            "a namespace that loads again leaves the failure set"))
      (finally (ops/close! sess)))))

(deftest ^:external a-materialization-equals-the-store-not-a-superset-of-it
  ;; `build!` spits one file per namespace the store HAS. It never removed one
  ;; the store no longer has, so the tree accumulated: `slopp.mcp.http` was
  ;; deleted, green and milestoned, and `slopp/mcp/http.clj` still shipped in
  ;; the jar hours later, sitting beside freshly-rewritten siblings.
  ;;
  ;; A jarred namespace is not dead weight — it is on the classpath, it can be
  ;; required, and with `:web/` metadata a served store ROUTES to it. A build
  ;; from a store that deleted a login handler still shipped the login handler.
  ;;
  ;; `uber`'s staleness guard cannot catch this: it discriminates on presence
  ;; and mtime, and materializing ON TOP of an older tree leaves the directory
  ;; present with a fresh timestamp on every file anyone would think to check.
  ;; The stale one is the file nobody rewrote.
  (let [sess (external/open!)
        dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-prune" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (ops/create-ns! sess 'pr.keep :source "(ns pr.keep)\n\n(defn k \"K.\" [] 1)\n")
      (ops/create-ns! sess 'pr.gone :source "(ns pr.gone)\n\n(defn g \"G.\" [] 2)\n")
      (external/build! sess dir)
      (is (.exists (io/file dir "src/pr/gone.clj")) "fixture: it was materialized")

      ;; `delete-ns!` retires an EMPTY namespace, so the form goes first — and
      ;; both results are checked, because a fixture that silently failed to
      ;; delete anything would make the assertion below pass for the wrong
      ;; reason and then fail for the right-looking one
      (is (nil? (:error (ops/delete-form! sess 'pr.gone 'g :prompt "retire it"))))
      (is (nil? (:error (ops/delete-ns! sess 'pr.gone :prompt "retire it"))))
      (is (nil? (get (:namespaces (:store @sess)) 'pr.gone))
          "fixture: the store really no longer has it")
      (external/build! sess dir)
      (is (not (.exists (io/file dir "src/pr/gone.clj")))
          "a namespace the store no longer has must not survive in the tree")
      (testing "and everything the store DOES have is still there"
        ;; the whole risk of pruning is over-deleting, so this is the half that
        ;; a too-eager fix fails
        (is (.exists (io/file dir "src/pr/keep.clj")))
        (is (.exists (io/file dir "src" boot/head-resource-path))
            "including the generated resources that live under the same root")
        (is (.exists (io/file dir "deps.edn"))))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (rm! (io/file dir)))
        (ops/close! sess)))))

(deftest ^:external full-check-says-which-whole-store-reads-it-RAN
  ;; From slopp-ui, who hit it verifying two regrades on a fresh jar and needed
  ;; DIFFERENT evidence for each. `http-dangling-route-refs` appears in the
  ;; rule sweep's `:swept` list, which distinguishes *ran and found nothing*
  ;; from *did not run*. `alias-drift` has no such list, so they had to build a
  ;; control by hand: introduce a deliberate non-canonical alias, watch it
  ;; report, revert.
  ;;
  ;; `full-check!`'s own docstring already makes the argument, for one half of
  ;; its output: `:rules` is always present with `:swept`/`:not-swept` because
  ;; "naming them is what stops a green from claiming coverage it never had".
  ;; Every OTHER whole-store read was `cond->`'d in only when non-empty, so a
  ;; clean store and a read that never ran produced identical output — on the
  ;; one surface whose entire job is to be believed.
  ;;
  ;; `:checked` carries the POPULATION each read examined, not just its name.
  ;; A name alone would say "it ran"; the number is the positive control, and a
  ;; read reporting 0 is visibly broken rather than quietly clean.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'fc.core "(ns fc.core)\n\n(defn ^:export f \"F.\" [] 1)\n")
      (let [r (external/run-full-check! sess)
            c (:checked r)]
        (testing "the fixture is a real store — every count below is 0 on an
                  empty one, which would satisfy the shape while proving nothing"
          (is (pos? (:namespaces r)) (pr-str r)))
        (testing ":checked is ALWAYS present, on a store with nothing to report"
          (is (map? c) (str "expected :checked on a green result: " (pr-str r))))
        (testing "it names every whole-store read that vanishes when clean"
          (is (every? c [:dead-surface :tier-layering :module-debt
                         :empty-namespaces :alias-drift])
              (str "reads missing from :checked: "
                   (pr-str (remove c [:dead-surface :tier-layering :module-debt
                                      :empty-namespaces :alias-drift])))))
        (testing "and each says how big a population it examined"
          (is (every? #(and (number? %) (pos? %)) (vals c))
              (str "a read that examined nothing is not a clean read: "
                   (pr-str c)))))
      (finally (ops/close! sess)))))

(deftest ^:external enabling-a-capability-enables-what-it-requires
  ;; A capability graph is only half a mechanism if it merely reads.
  ;; `:requires` has to MOVE the config, or every project meets the same
  ;; puzzle: turning on `webapp` looks like it worked and then nothing serves,
  ;; because `http` — which a browser app cannot function without — was never
  ;; set.
  ;;
  ;; Both directions, and the second is the one easy to skip: if a prerequisite
  ;; can be turned off underneath a dependent, the config reaches a state the
  ;; catalog says is impossible, and the consequence surfaces somewhere else
  ;; entirely.
  (let [sess (external/open!)]
    (try
      (testing "enabling a capability writes its prerequisites and NAMES them"
        (let [r (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                                  :prompt "opt into a browser app")]
          (is (nil? (:error r)) (pr-str r))
          (is (true? (capabilities/enabled? (:store @sess) "webapp")))
          (is (true? (capabilities/enabled? (:store @sess) "http"))
              "webapp requires http — a browser app has to be served")
          (is (= ["http.enabled"] (:implied r))
              (str "the implied writes are REPORTED, not silent: a config change"
                   " nobody was told about is one nobody can undo. Got: " (pr-str r)))))
      (testing "and NOT what it merely tends to be used with"
        ;; the whole reason the graph is not a chain. rest is a COMPANION of
        ;; webapp, not a parent, so opting into a browser app must not arm the
        ;; contract gates of an API this project may not even have.
        (is (false? (capabilities/enabled? (:store @sess) "rest")))
        (is (false? (capabilities/enabled? (:store @sess) "cli"))))
      (testing "turning off a prerequisite something still stands on REFUSES"
        (let [r (ops/config-file! sess "capabilities" :key "http.enabled" :value "false"
                                  :prompt "try to pull the floor out")]
          (is (:error r) (pr-str r))
          (is (re-find #"webapp" (str (:error r)))
              "the refusal names what is standing on it, so the fix reads off it")
          (is (true? (capabilities/enabled? (:store @sess) "http"))
              "and the refusal did not half-land")))
      (testing "turning off the dependent first, then the prerequisite, works"
        (is (nil? (:error (ops/config-file! sess "capabilities" :key "webapp.enabled"
                                            :value "false" :prompt "drop the browser app"))))
        (is (nil? (:error (ops/config-file! sess "capabilities" :key "http.enabled"
                                            :value "false" :prompt "and then the server"))))
        (is (false? (capabilities/enabled? (:store @sess) "http"))))
      (testing "an enable that implies nothing says nothing"
        ;; absence means none, the way the module manifest's :debt does — an
        ;; empty :implied on every write would train the reader to skip the key.
        (let [r (ops/config-file! sess "capabilities" :key "cli.enabled" :value "true"
                                  :prompt "a command-line shell needs nothing beneath it")]
          (is (nil? (:implied r)) (pr-str r))))
      (finally (ops/close! sess)))))

(defn classpath-build-failure?
  "True when a `clojure.java.shell/sh` result is the Clojure CLI failing to
  build a classpath — the LAUNCHER giving up before any application code ran.

  `Error building classpath` is the CLI's own prefix, emitted while resolving
  dependencies, so nothing an app can do produces it. That is what makes this
  separable from the failure these built-app tests exist to catch: a tree
  missing a vendored framework raises a `FileNotFoundException` from the app's
  OWN require, after the launcher succeeded.

  Keeping the two apart is the whole value. Measured once: a shard-parallel
  `full_check` went red here and green alone, and the evidence read exactly
  like the vendoring claim these tests make — which would have sent a reader
  to audit `framework-deps` for a defect that was not there.

  A zero exit is never a failure however stderr reads, or an ordinary run that
  merely printed the phrase would be retried forever."
  [{:keys [exit err]}]
  (boolean (and exit (not (zero? exit))
                (re-find #"Error building classpath" (str err)))))

(deftest a-CLASSPATH-build-failure-is-not-an-application-failure
  ;; One `full_check` run went red on
  ;; `a-built-rest-app-ENFORCES-its-contract-outside-slopp-entirely` with
  ;;
  ;;   Error building classpath. class java.util.HashMap$Node cannot be cast
  ;;   to class java.util.HashMap$TreeNode
  ;;
  ;; and passed alone seconds later, unchanged. That message comes from the
  ;; Clojure CLI, BEFORE any application code runs — four shards resolving
  ;; dependencies for freshly-built trees at once, against shared caches.
  ;;
  ;; **The cost is not the flake, it is that the flake is indistinguishable
  ;; from the thing these tests exist to prove.** They assert that a built tree
  ;; can resolve its own vendored framework's deps, which is exactly the claim
  ;; `framework-deps` was built to make — so a reader seeing this red has every
  ;; reason to go and audit vendoring, and the evidence would not say otherwise.
  ;;
  ;; The two are categorically separable, and that is what makes retrying one
  ;; of them honest rather than a mask: a real vendoring failure is a
  ;; `FileNotFoundException` raised by the APP's own require, and this is the
  ;; launcher failing before the app exists to require anything.
  (testing "the CLI's own classpath failure is recognised"
    (is (classpath-build-failure?
         {:exit 1 :out ""
          :err (str "Error building classpath. class java.util.HashMap$Node "
                    "cannot be cast to class java.util.HashMap$TreeNode")})))

  (testing "a REAL vendoring failure is NOT — this is the whole point"
    ;; the app loaded, looked for a namespace nobody vendored, and said so.
    ;; If this ever returns true, the retry starts hiding the defect these
    ;; tests exist to catch.
    (is (not (classpath-build-failure?
              {:exit 1 :out ""
               :err (str "Execution error (FileNotFoundException) at native.main/eval.\n"
                         "Could not locate slopp/rest__init.class, slopp/rest.clj "
                         "or slopp/rest.cljc on classpath.")}))))

  (testing "and neither is a plain non-zero exit from the app itself"
    (is (not (classpath-build-failure?
              {:exit 2 :out "" :err "hello is not a command of this program"}))))

  (testing "a SUCCESS is never a failure, whatever it printed"
    ;; only a non-zero exit is a failure; the phrase appearing in ordinary
    ;; output must not be enough, or a passing run could be retried forever
    (is (not (classpath-build-failure?
              {:exit 0 :out "hi world"
               :err "WARNING: Error building classpath appears in this log line"})))))

(defn sh-outside-slopp!
  "`clojure.java.shell/sh`, retried ONCE when the Clojure CLI fails to build a
  classpath rather than when the program fails.

  The built-app tests shell out to a freshly written tree, so every invocation
  resolves dependencies from cold — and `full_check` runs four shards at once,
  which is where a shared-cache race becomes reachable. Observed once, green
  alone seconds later.

  **A retry is normally the dishonest fix and here it is not, because the two
  outcomes are categorically different events.** [[classpath-build-failure?]]
  matches the LAUNCHER giving up before the app exists; the failure these tests
  are for is the app's own require finding no vendored framework, which the
  launcher reaching that point already proves it got past. So this cannot
  swallow the defect under test — and if the second attempt fails the same way,
  the result is returned with the cause named IN the stderr the assertion will
  print, rather than left to read as a vendoring failure."
  [& args]
  (let [r (apply clojure.java.shell/sh args)]
    (if-not (classpath-build-failure? r)
      r
      (let [r2 (apply clojure.java.shell/sh args)]
        (cond-> r2
          (classpath-build-failure? r2)
          (update :err str
                  "\n\n[slopp test-support] The Clojure CLI failed to build a"
                  " classpath TWICE. This is the launcher, not the application:"
                  " no app code ran, so it says nothing about whether the"
                  " framework was vendored. Suspect concurrent dependency"
                  " resolution across full_check's shards; re-run this test"
                  " alone to confirm."))))))

(deftest ^:external a-built-cli-app-RUNS-outside-slopp-entirely
  ;; The cli counterpart of the web run-it test below, and it exists for the
  ;; same reason: every other build! assertion is about SHAPE — a file is
  ;; present, deps.edn contains X — and the whole class of bug this wave was
  ;; about passes every shape assertion and dies on first require.
  ;;
  ;; It carries three claims no shape check can make.
  ;;
  ;; (1) The framework is the REAL `slopp.cli` / `slopp.cli.spec`, read off the
  ;; classpath rather than faked, because the property under test is that
  ;; `slopp.cli.spec` requires malli and a consuming tree must be told so. A
  ;; hand-written stub would be a stub that happens to agree today.
  ;;
  ;; (2) The generated entry is EXECUTED. `build!` writes a launcher the author
  ;; never wrote; a test asserting that file exists proves nothing about whether
  ;; the program parses argv, sets an exit code, or has any commands at all —
  ;; and `commands-in` finds commands with `find-ns`, so a launcher that failed
  ;; to require its own namespaces would run fine and simply know nothing.
  ;;
  ;; (3) The vendor boundary holds AT RUNTIME. Two families are declared and one
  ;; is USED, so a store that reaches for neither the namespaces nor the markers
  ;; of `http` must not end up able to load `slopp.http`.
  ;;
  ;; Note what this does and does not claim, because the first version of the
  ;; docstring next door got it wrong and slopp-ui traced it: vendoring follows
  ;; USE, not enablement. It does not stop a store whose requires already name
  ;; `slopp.http` from loading it with `http.enabled` false — and it must not,
  ;; since that store is one mid-migration and withholding the framework would
  ;; turn a diagnosable config error into a store that cannot boot to be fixed.
  ;; What is asserted here is narrower and is the part that pays: an app gets
  ;; the families it reaches for and no others.
  (let [src-of (fn [p] (some-> (io/resource p) slurp))
        files  {"cli"  {"slopp/cli.clj"      (src-of "slopp/cli.clj")
                        "slopp/cli/spec.clj" (src-of "slopp/cli/spec.clj")}
                "http" {"slopp/http.clj" "(ns slopp.http)\n(defn handle! \"H.\" [r] r)\n"}}]
    ;; guard the guard: nil source vendors an empty family, and every assertion
    ;; below would then be about a tree with no framework in it
    (is (every? some? (vals (get files "cli")))
        "the real cli framework must be readable from the classpath here")
    (with-redefs [boot/framework-files (constantly files)
                  boot/framework-deps  (constantly '{"cli"  {metosin/malli {:mvn/version "0.20.1"}}
                                                     "http" {garden/garden {:mvn/version "1.3.10"}}})]
      (let [sess (external/open!)
            dir  (str (Files/createTempDirectory "slopp-cli-runs"
                                                 (make-array FileAttribute 0)))
            ;; NOT named run!, which is clojure.core's and is used by the cleanup
            ;; below — a local of that name shadows it and the temp trees leak
            sh!  (fn [& args] (apply sh-outside-slopp!
                                     "clojure" "-M" "-m" "native.main"
                                     (concat args [:dir dir])))]
        (try
          (ops/config-file! sess "capabilities" :key "cli.enabled" :value "true"
                            :prompt "this app is a command-line program")
          (ops/ingest! sess 'greet.commands
                       (str "(ns greet.commands)\n\n"
                            "(defn ^{:cli/command \"hello\"\n"
                            "        :cli/doc \"Greet someone by name.\"\n"
                            "        :cli/args [:catn [:who :string]]}\n"
                            "  hello \"Greet.\" [ctx args]\n"
                            "  (.write ^java.io.Writer (:cli/out ctx)\n"
                            "          (str \"hi \" (:who args) \"\\n\"))\n"
                            "  nil)\n"))
          (is (nil? (:error (external/build! sess dir))))

          (testing "the cli family is IN the tree and the http family is NOT"
            (is (.exists (io/file dir "src" "slopp" "cli" "spec.clj")))
            (is (not (.exists (io/file dir "src" "slopp" "web.clj")))
                "vendoring every family would make (require 'slopp.http) succeed
                 in a project that never enabled http"))

          (testing "and only the used family's deps are declared"
            (let [d (edn/read-string (slurp (io/file dir "deps.edn")))]
              (is (contains? (:deps d) 'metosin/malli)
                  (str "slopp.cli.spec requires malli: " (pr-str (:deps d))))
              (is (nil? (get (:deps d) 'garden/garden))
                  (str "a cli app must not be handed http's deps: " (pr-str (:deps d))))))

          (testing "the GENERATED entry runs in a JVM that has never heard of slopp"
            (let [r (sh! "hello" "world")]
              (is (zero? (:exit r))
                  (str "exit " (:exit r) "\nout: " (:out r) "\nerr: " (:err r)))
              (is (str/includes? (:out r) "hi world")
                  (str "what the command WROTE reaches a real stdout — the"
                       " launcher flushes before System/exit, which does not"
                       " drain it.\nout: " (:out r) "\nerr: " (:err r)))))

          (testing "a bare invocation LISTS what the program can do"
            ;; a usage error alone would make the reader run a second command to
            ;; learn anything, so a bare call answers the question it implies
            (let [r (sh!)]
              (is (zero? (:exit r)) (:err r))
              (is (str/includes? (:out r) "hello") (:out r))))

          (testing "argv is a real boundary and the STATUS CODE says so"
            (let [r (sh! "hello")]
              (is (= 2 (:exit r))
                  (str "a missing positional must not reach the handler.\nout: "
                       (:out r) "\nerr: " (:err r)))
              (is (str/blank? (:out r))
                  (str "a refusal leaves stdout CLEAN — a caller piping it gets"
                       " nothing rather than half an answer: " (:out r))))
            (let [r (sh! "nope")]
              (is (= 2 (:exit r)))
              (is (str/includes? (:err r) "hello")
                  (str "an unknown command names the ones that exist: " (:err r)))))

          (testing "asking for help is not an error"
            ;; exiting non-zero here breaks `cmd --help` in any script that
            ;; checks status, which is most of them
            (let [r (sh! "hello" "--help")]
              (is (zero? (:exit r)) (:err r))
              (is (str/includes? (:out r) "who") (:out r))))

          (testing "and the vendor boundary holds at RUNTIME, not just on disk"
            (let [r (clojure.java.shell/sh
                     "clojure" "-M" "-e" "(require 'slopp.http)" :dir dir)]
              (is (not (zero? (:exit r)))
                  (str "a store that never enabled http must not be able to load"
                       " the http framework: " (:out r) (:err r)))))

          (testing "negative control: without the framework's deps the same tree fails"
            ;; a green run above proves nothing unless the red one is reachable.
            ;; This is the exact failure the deps half of the mechanism exists
            ;; for — vendored source whose own requires nobody declared.
            (let [dir2 (str (Files/createTempDirectory
                             "slopp-cli-nodeps" (make-array FileAttribute 0)))]
              (try
                (with-redefs [boot/framework-deps (constantly nil)]
                  (external/build! sess dir2))
                (let [r (clojure.java.shell/sh
                         "clojure" "-M" "-e" "(require 'native.main)" :dir dir2)]
                  (is (not (zero? (:exit r)))
                      "vendored cli source with no deps declared must NOT load")
                  (is (str/includes? (:err r) "malli")
                      (str "and it must fail on the framework's own require: "
                           (:err r))))
                (finally
                  (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f)))
                            (.delete f))]
                    (rm! (io/file dir2)))))))
          (finally
            (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
              (rm! (io/file dir)))
            (ops/close! sess)))))))

(deftest ^:external the-retry-CONSULTS-the-decision-and-only-for-that-cause
  ;; The join. `classpath-build-failure?` is pinned above without a process,
  ;; which is the right place to pin a decision — and it is exactly how a seam
  ;; goes unwatched: every assertion about the decision passes while the
  ;; performer ignores it. Asserted here by COUNTING invocations, because
  ;; "retried" and "did not retry" are otherwise the same observation.
  (let [dir     (str (Files/createTempDirectory "slopp-sh-retry"
                                                (make-array FileAttribute 0)))
        script  (str dir "/probe.sh")
        counter (str dir "/count")
        runs    #(if (.exists (io/file counter))
                   (count (str/split-lines (slurp counter)))
                   0)]
    ;; $1 is the counter file, $2 the stderr text to emit on the FIRST run
    (spit script (str "#!/bin/sh\n"
                      "echo x >> \"$1\"\n"
                      "n=$(wc -l < \"$1\" | tr -d ' ')\n"
                      "if [ \"$n\" -eq 1 ]; then echo \"$2\" 1>&2; exit 1; fi\n"
                      "echo ok\n"))

    (testing "a classpath failure is retried, and the retry's success is returned"
      (let [r (sh-outside-slopp! "sh" script counter
                                 "Error building classpath. class java.util.HashMap$Node")]
        (is (= 2 (runs)) "the performer must actually call again")
        (is (zero? (:exit r)) (pr-str r))
        (is (str/includes? (:out r) "ok") (pr-str r))))

    (testing "an APPLICATION failure is returned as-is, with no second attempt"
      ;; the arm that matters: a retry here would re-run a program that may
      ;; have had effects, and would paper over the defect under test
      (spit counter "")
      (let [r (sh-outside-slopp! "sh" script counter
                                 "Execution error (FileNotFoundException): Could not locate slopp/rest.clj")]
        (is (= 1 (runs)) "a real failure must not be retried")
        (is (= 1 (:exit r)) (pr-str r))))

    (testing "and a second classpath failure NAMES the cause in the stderr an assertion prints"
      ;; the honest end state: it still fails, and the message says the app
      ;; never ran rather than leaving it to read as a vendoring failure
      (let [always (str dir "/always.sh")]
        (spit always (str "#!/bin/sh\n"
                          "echo \"Error building classpath. boom\" 1>&2\nexit 1\n"))
        (let [r (sh-outside-slopp! "sh" always)]
          (is (= 1 (:exit r)) (pr-str r))
          (is (str/includes? (:err r) "launcher, not the application") (pr-str r))
          (is (str/includes? (:err r) "TWICE") (pr-str r)))))))

(deftest a-store-whose-BROWSER-owns-routing-uses-the-webapp-family
  ;; Wave 4: `webapp` ships a family, and the entry MARKER is the only usage
  ;; signal — `used-families` reads requires and markers, and slopp mounts the
  ;; loop so a browser app never names `slopp.webapp`. Third capability, third
  ;; time.
  ;;
  ;; **The marker is `:webapp/client-routes`, not `:app/entry`, and the distinction is the one
  ;; that kept `screen` in `http`.** `:app/entry` declares *here is an entry a
  ;; reader can open* — inspectability. A server-rendered HTML app marks a page
  ;; to be LOOKED AT, and it has no browser code at all. `:webapp/client-routes` declares
  ;; *the browser owns these paths*, which is the capability's own definition.
  ;;
  ;; Caught by slopp-ui in a pre-flight, and the blast radius was small — one
  ;; extra `.cljc` in a tree that would not require it — while the principle was
  ;; not, because it is `framework-injection`'s own: handing a store a family it
  ;; never opted into lets `(require 'slopp.webapp)` succeed in a project that
  ;; never enabled `webapp`, which is the opt-in holding in the config file and
  ;; not at runtime.
  ;;
  ;; Second consequence of this marker set in two days, after `:http/path`
  ;; turned out to be MISSING from http's. The set is the least visible
  ;; declaration in the catalog and nothing fails when it is wrong — it just
  ;; vendors the wrong thing.
  (let [browser-app  (str "(ns shop.ui)\n\n"
                  "(defn ^{:http/method :get :http/path \"/\" :http/auth :public\n"
                  "        :rest/response :string :webapp/client-routes [\"/things\"]}\n"
                  "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})\n")
        page (str "(ns shop.server)\n\n"
                  "(defn ^:app/entry app \"A server-rendered app, for a reader.\" []\n"
                  "  {:http/routes []})\n")]

    (testing "declaring client-side routing IS using the browser framework"
      (let [st (store/ingest (store/empty-store) 'shop.ui browser-app)]
        (is (contains? (engine/used-families st) "webapp")
            (pr-str (engine/used-families st)))))
(testing "and so is a PAGE declaring its own address"
      ;; the signal the page-function dialect actually leaves. A browser app's
      ;; own namespaces are pages; the served shell may live in a namespace
      ;; this store does not hold at all (a hub serves it), in which case
      ;; `:webapp/client-routes` appears nowhere and the family would be
      ;; withheld from the one store that needs all of it
      (let [st (store/ingest (store/empty-store) 'shop.pages
                             (str "(ns shop.pages)\n\n"
                                  "(defn ^{:webapp/path \"/things\"} things \"T.\" [_] [:ul])\n"))]
        (is (contains? (engine/used-families st) "webapp")
            (pr-str (engine/used-families st)))))

    (testing "but marking a page for the READER is not"
      ;; the case that made this wrong: a server-rendered app declares a page so
      ;; `screen` can open it, and gets no browser code for saying so
      (let [st (store/ingest (store/empty-store) 'shop.server page)]
        (is (not (contains? (engine/used-families st) "webapp"))
            (str "a page is inspectability, not browser-owned routing: "
                 (pr-str (engine/used-families st))))))

    (testing "and a store with neither gets neither"
      (let [st (store/ingest (store/empty-store) 'shop.plain
                             "(ns shop.plain)\n\n(defn f \"F.\" [x] x)\n")]
        (is (not (contains? (engine/used-families st) "webapp"))
            (pr-str (engine/used-families st)))))

    (testing "the capability declares a prefix, which is what makes it shippable"
      ;; build.clj reads :ns-prefix out of this catalog rather than keeping its
      ;; own list, so declaring it here is the whole of making the family ship
      (let [row (first (filter #(= "webapp" (:capability %))
                               capabilities/capability-catalog))]
        (is (= "slopp.webapp" (:ns-prefix row)) (pr-str row))
        (is (= [:webapp/path :webapp/shell :webapp/client-routes] (:entry-markers row))
            (str "and :app/entry is NOT among them, deliberately: " (pr-str row)))))))

(deftest the-vendored-tree-tracks-the-STORE-not-the-process-start
  ;; A consumer's notes said, in bold, "the tree is materialized from the jar at
  ;; PROCESS START" — and then they enabled `webapp` mid-session and found
  ;; `slopp/webapp.cljc` in a tree that supposedly could not contain it. They
  ;; reported it as impossible, correctly, because the model they had been given
  ;; made it so.
  ;;
  ;; Two axes, and the sentence conflated them:
  ;;
  ;;   WHICH families   re-derived from the STORE at every image launch
  ;;   WHAT IS in one   read from THIS PROCESS's jar, frozen until restart
  ;;
  ;; So a slopp fix needs a rebuild and a restart, and a capability you just
  ;; turned on does not. Half of their sentence was load-bearing and true; the
  ;; other half sent them looking for a bug.
  (let [files {"_"      {"slopp/lang.cljc" "(ns slopp.lang)"}
               "webapp" {"slopp/webapp.cljc" "(ns slopp.webapp)"}
               "cli"    {"slopp/cli.clj" "(ns slopp.cli)"}}
        before (store/ingest (store/empty-store) 'shop.core
                             "(ns shop.core)\n\n(defn total \"T.\" [x] x)\n")]

    (testing "a store using no family is vendored nothing"
      (is (nil? (engine/framework-injection before files))))

    (testing "the SAME store, after gaining a client-routed document, is vendored webapp"
      ;; nothing about the process changed — only the store did
      (let [after (store/ingest before 'shop.ui
                                (str "(ns shop.ui)\n\n"
                                     "(defn ^{:http/method :get :http/path \"/\"\n"
                                     "        :webapp/client-routes [\"/things\"]}\n"
                                     "  doc \"D.\" [_] {:status 200 :body \"<html>\"})\n"))
            got   (engine/framework-injection after files)]
        (is (contains? got "slopp/webapp.cljc")
            (str "the decision is a function of the store, so it answers"
                 " differently the moment the store does: " (pr-str (keys got))))
        (is (contains? got "slopp/lang.cljc") "the common family rides along")
        (is (not (contains? got "slopp/cli.clj"))
            "and a family this store does not use is still withheld")))))

(deftest ^:external the-read-ring-flushes-on-its-OWN-schedule-not-the-turns
  ;; The read record rode `:turn-end` and inherited the rotation gate: a turn
  ;; closes only when a user PROMPT arrived and a WRITE followed. A read-only
  ;; ask closes none, and an event-driven session closes none — the two shapes
  ;; where reads dominate. So the ring gets its own life and its own citizen.
  (let [sess  (external/open!)
        rows  (fn [n] (vec (for [i (range n)]
                             {:tool "query_slice" :chars 10 :start i :end i})))
        spans (fn [] (filter #(= :read-cost (:op %)) (ops/journal sess)))]
    (try
      (testing "nothing recorded → no delta and no record, rather than an
                empty one that would read as a span that cost nothing"
        (is (nil? (ops/flush-reads! sess)))
        (is (nil? (ops/flush-reads! sess :force? true)))
        (is (empty? (spans))))

      (testing "below the threshold nothing is written — a delta per write
                would bury the journal under telemetry"
        (swap! sess assoc :slopp.read.telemetry/reads
               (rows (dec telemetry/read-flush-calls)))
        (is (nil? (ops/flush-reads! sess)))
        (is (empty? (spans)))

        (testing "...but a WORK BOUNDARY forces one, because done and
                  commit_point are the spans an agent actually has, and
                  unlike a turn they need nobody to have typed anything"
          (let [r (ops/flush-reads! sess :force? true)]
            (is (= (dec telemetry/read-flush-calls) (:calls r)) (pr-str r))
            (is (= 1 (count (spans)))))))

      (testing "at the threshold a span lands on its own, so a session that
                never rotates a turn still records"
        (swap! sess assoc :slopp.read.telemetry/reads
               (conj (rows telemetry/read-flush-calls)
                     {:tool "query_rules" :chars 8000 :trimmed? true :spooled "r1"}
                     {:tool "query_detail" :chars 21676 :detail-asked "r1"})
               :slopp.read.telemetry/calls
               [{:tool "query_rules" :start 0 :end 10}])
        (let [r (ops/flush-reads! sess)
              d (last (spans))]
          (is (= (+ 2 telemetry/read-flush-calls) (:calls r)) (pr-str r))
          (is (= 1 (:refetched r))
              (str "and the fold is the same one, so a span answers the"
                   " question a turn used to: " (pr-str r)))
          (is (= (:reads d) r)
              (str "the delta carries exactly what the flush returned: "
                   (pr-str d)))

          (testing "the READ ring is cleared and the TIMING ring is not —
                    they have different owners now, and a flush that took
                    both would bill this ask's clock to whatever turn closes
                    next"
            (is (empty? (:slopp.read.telemetry/reads @sess)))
            (is (= 1 (count (:slopp.read.telemetry/calls @sess)))
                (pr-str (:slopp.read.telemetry/calls @sess))))

          (testing "and flushing again writes nothing — a span already
                    recorded must not be counted twice by a ledger that sums
                    rows"
            (is (nil? (ops/flush-reads! sess :force? true)))
            (is (= 2 (count (spans)))
                "a third delta here would double every total"))))
      (finally (ops/close! sess)))))

(deftest a-family-arrives-with-the-families-it-REQUIRES
  ;; `used-families` reads what a store REACHES FOR, and that is the whole
  ;; signal it had: a browser app requires `slopp.webapp` and nothing else, so
  ;; it was handed the webapp family alone. But a vendored family has requires
  ;; of its own, and `slopp.webapp` requires `slopp.http.endpoint` — one
  ;; capability over, in a family the store never named.
  ;;
  ;; The failure is the one `framework-injection` already describes and could
  ;; not previously reach: **the framework lands intact and fails inside
  ;; itself**. It surfaced as `No such namespace: slopp.http.endpoint` from the
  ;; ClojureScript compiler, which reads exactly like the app's own mistake.
  ;;
  ;; The answer is not a hand-kept list of cross-family requires. The catalog
  ;; already states this: `webapp :requires ["http"]` because a browser app has
  ;; to be SERVED. What that edge MEANT — the config write turns http on with
  ;; it — now also decides what is vendored, so a family that may legitimately
  ;; reach another is a family that arrives with it.
  (let [files {"_"      {"slopp/lang.cljc" "(ns slopp.lang)"}
               "http"   {"slopp/http.clj" "(ns slopp.http)"
                         "slopp/http/endpoint.cljc" "(ns slopp.http.endpoint)"}
               "webapp" {"slopp/webapp.cljc" "(ns slopp.webapp)"}
               "cli"    {"slopp/cli.clj" "(ns slopp.cli)"}}
        browser (-> (store/empty-store)
                    (store/ingest 'shop.ui
                                  (str "(ns shop.ui (:require [slopp.webapp :as webapp]))\n"
                                       "(defn ^{:webapp/path \"/things\"} things \"T.\" [_] [:ul])\n")))
        cli-app (-> (store/empty-store)
                    (store/ingest 'app.cmds
                                  (str "(ns app.cmds)\n"
                                       "(defn ^{:cli/command \"add\" :cli/args [:catn]} add \"A.\" [ctx args] args)\n")))]
    (testing "a browser app is handed http as well, because webapp requires it"
      (let [got (engine/framework-injection browser files)]
        (is (contains? got "slopp/webapp.cljc") (pr-str (keys got)))
        (is (contains? got "slopp/http/endpoint.cljc")
            (str "slopp.webapp requires it, so an app vendored webapp alone"
                 " compiles against a namespace nobody gave it: "
                 (pr-str (keys got))))
        (is (contains? got "slopp/lang.cljc") "the dialect still rides")))
    (testing "and a capability that requires nothing still carries nothing"
      ;; the prerequisite walk must not become \"vendor everything\" — cli
      ;; declares `:requires []` and a command-line app has no server in it
      (let [got (engine/framework-injection cli-app files)]
        (is (contains? got "slopp/cli.clj") (pr-str (keys got)))
        (is (not (contains? got "slopp/http.clj"))
            (str "a prerequisite nobody declared: " (pr-str (keys got))))))))

(deftest ^:external a-whole-store-verdict-is-returned-rather-than-re-earned
  ;; The fold behind this is unit-tested in read.history-test; what is pinned
  ;; here is that `full_check` CONSULTS it, and that the two escapes work —
  ;; because an escape nothing exercises is the same as no escape.
  ;;
  ;; Worth the JVM boots this test costs: over this store's own journal,
  ;; `full_check` ran 325 times and 117 of those were repeats inside a single
  ;; ask, which is 7.6 hours of re-earning an answer that already stood.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'sc.core "(ns sc.core)\n\n(defn ^:unused-ok f \"F.\" [x] x)\n")

      (testing "the first ask is a real run"
        (let [r (external/full-check! sess)]
          (is (nil? (:standing r)) (pr-str (dissoc r :lint :warnings)))
          (is (some? (:status r)))))

      (testing "asked again with nothing changed, the verdict STANDS"
        (let [r (external/full-check! sess)]
          (is (true? (:standing r)) (pr-str (dissoc r :lint :warnings)))
          (is (some? (:status r)) "and it is a verdict, not an excuse")
          (is (re-find #"STANDS" (str (:note r)))
              "the reader is told why no check ran")))

      (testing "force re-earns it"
        (let [r (external/full-check! sess :force true)]
          (is (nil? (:standing r)) "a forced ask is a real run")))

      (testing "and any write retires it"
        ;; the whole predicate in one assertion: after a change, the next ask
        ;; is a real run again rather than a stale green
        (ops/ingest! sess 'sc.more "(ns sc.more)\n\n(defn ^:unused-ok g \"G.\" [x] x)\n")
        (let [r (external/full-check! sess)]
          (is (nil? (:standing r)) (pr-str (dissoc r :lint :warnings)))))
      (finally (ops/close! sess)))))

(deftest ^:external the-brief-says-when-the-served-ui-is-behind-the-store
  ;; The `:app-behind` precedent, one listener over. A brief that hands a
  ;; reader a url is the place they find out anything about it at all — a
  ;; consumer read this brief through a twenty-minute window in which their
  ;; app served old code and it said nothing, which is why `:app-behind` is
  ;; here. The reviewer listener had the same hole and no counter: its route
  ;; table is assembled once at serve time, so a route added afterwards 404s
  ;; and the brief keeps announcing the url as though nothing were owed.
  (let [dir  (str (System/getProperty "java.io.tmpdir") "/slopp-uistale-" (System/nanoTime))
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            st    (store/ingest (store/empty-store) 'brief.one "(ns brief.one)\n\n(def a 1)\n")
            _     (db/append! conn st (store/deltas st) ['brief.one] trunk nil)
            stamp (slopp.currency/of conn trunk)
            sess  (atom {:store st :db conn :line trunk
                         :ui-url "http://127.0.0.1:1234/" :ui-stamp stamp})]

        (testing "a listener serving the current store earns no remark"
          (let [b (ops/session-brief sess)]
            (is (= "http://127.0.0.1:1234/" (:ui b)) (pr-str b))
            (is (nil? (:ui-stale b))
                (str "silence is for nothing-to-doubt, or the line becomes noise: " (pr-str b)))))

        (testing "and once the line moves under it, the brief says so beside the url"
          (let [st2 (store/ingest st 'brief.two "(ns brief.two)\n\n(def b 2)\n")
                new (vec (drop (count (store/deltas st)) (store/deltas st2)))]
            (is (true? (db/append! conn st2 new ['brief.two] trunk (:head stamp)))
                "fixture: the line really did move")
            (let [b (ops/session-brief sess)]
              (is (= "http://127.0.0.1:1234/" (:ui b)) "the url is still handed over")
              (is (some? (:ui-stale b)) (pr-str b))))))
      (finally (.close conn)))))

(deftest ^:external the-model-side-is-read-beside-the-writer-not-in-the-query-door
  ;; `record-otel!` writes the measurements TABLE rather than the journal — a
  ;; statistic must not move the head every writer compare-and-swaps against.
  ;; Its read twin belongs in the same place, and that is not a tidiness
  ;; preference: `slopp.read.query` is declared `:pure`, so a query front door
  ;; that opens the journal makes its own tier a claim it does not earn.
  ;; full_check grades exactly that, and it went red on this the first time
  ;; anything asked.
  (let [dir  (str (System/getProperty "java.io.tmpdir") "/slopp-otelread-" (System/nanoTime))
        conn (db/open! dir)
        sess (atom {:store (store/empty-store) :db conn})]
    (try
      (is (= 1 (ops/record-otel! sess [{:model "claude-opus-5" :input 2 :output 4}]))
          "fixture: one request recorded")
      (testing "the read twin hands back what the writer put there"
        (let [ms (ops/otel-measurements sess)]
          (is (= 1 (count ms)) (pr-str ms))
          (is (= 1 (count (:requests (first ms)))) (pr-str ms))
          (is (= "claude-opus-5" (:model (first (:requests (first ms))))) (pr-str ms))))
      (testing "and the query front door folds what it is HANDED"
        (is (map? (query/query-turn-cost sess :otel (ops/otel-measurements sess)))))
      (testing "a session with no journal simply has none"
        (is (empty? (ops/otel-measurements (atom {:store (store/empty-store)})))))
      (finally (.close conn)))))

(deftest ^:external dev-config-validates-at-write
  ;; `dev` has a registry, so it gets the same bar `capabilities` and `rules`
  ;; get. Without this the registry would be documentation: `config_file`
  ;; only refuses on paths it is told to check, so an unregistered path
  ;; records whatever it is handed and a typo'd key governs nothing, silently
  ;; — the exact hole the `rules` registry was wired in to close.
  (let [sess (external/open!)]
    (try
      (testing "an unknown dev key is refused, and the refusal names it"
        (let [r (ops/config-file! sess "dev" :key "run.app.prot" :value "8080"
                                  :prompt "typo'd field")]
          (is (re-find #"run\.app\.prot" (str (:error r))) (pr-str r))
          (is (nil? (get-in (:store @sess) [:config "dev" :values "run.app.prot"]))
              "the refused key landed anyway")))

      (testing "a value that fails its type is refused with the teaching"
        (let [r (ops/config-file! sess "dev" :key "run.app.main" :value "unqualified"
                                  :prompt "not an entry point")]
          (is (re-find #"qualified symbol" (str (:error r))) (pr-str r))))

      (testing "a good declaration lands and reads back as a runnable"
        (is (nil? (:error (ops/config-file! sess "dev" :key "run.app.main"
                                            :value "shop.core/-main"
                                            :prompt "the app"))))
        (is (nil? (:error (ops/config-file! sess "dev" :key "run.app.args"
                                            :value "--port,8080"
                                            :prompt "on this port"))))
        (let [runs (dev/runnables (:store @sess))]
          (is (= 'shop.core/-main (get-in runs ["app" :main])) (pr-str runs))
          (is (= ["--port" "8080"] (get-in runs ["app" :args])) (pr-str runs))))

      (testing "and the write reports that a registry stood behind it"
        ;; D-surface-honesty: an unchecked write records what it is handed, so
        ;; a caller cannot tell the two apart unless the result says which
        ;; happened. `dev` must not report itself as unverified now that it
        ;; has a registry.
        (let [r (ops/config-file! sess "dev" :key "run.worker.main"
                                  :value "shop.jobs/-main" :prompt "a worker")]
          (is (not-any? #{:schema} (:unverified r)) (pr-str r))))

      (finally (ops/close! sess)))))

(deftest ^:external store-compact-keeps-the-open-view-and-reports-in-bytes
  ;; store_compact is the operator's write beside store_health's read. What
  ;; it must never do is touch the view a live line holds; what it must always
  ;; do is answer in numbers, because the consumer who asked for it asked to
  ;; run it deliberately and read the result rather than find a smaller file.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-compact" (make-array FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/create-ns! sess 'compact.core :source "(ns compact.core)\n\n(defn f \"F.\" [x] x)\n")
      (let [r      (external/compact-store! sess)
            health (external/store-health sess)]
        (testing "a store with nothing settled has nothing to drop, and says so in numbers"
          (is (= 0 (:rows-dropped r)) (pr-str r))
          (is (= (:reclaimed r) (- (:bytes-before r) (:bytes-after r))) (pr-str r))
          (is (pos? (:bytes-after r))))
        (testing "and the open line's view is untouched"
          (is (pos? (get-in health [:elements :by-status "open" :n])) (pr-str (:elements health)))))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (ops/close! sess)
          (rm! (io/file dir)))))))

(deftest ^:external opening-a-store-records-what-the-open-cost-beside-the-journal
  ;; `load-store` was 7.6 s on this store and nobody knew until it was timed by
  ;; hand. Every open is now an observation: the journal's length and the
  ;; milliseconds it took, as a row in `measurements`. The last assertion is
  ;; the point of the whole item — telemetry in the JOURNAL is what made the
  ;; store unwritable this week, so a measurement must never move the head.
  ;;
  ;; Both opens NAME the agent: a session's writes go to its thread, and a
  ;; second open under the same identity adopts that thread and loads it. An
  ;; unnamed second open loads the trunk — which holds nothing until a done —
  ;; and the row honestly said `{:deltas 0 :head nil}`.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-open-cost" (make-array FileAttribute 0)))
        s1   (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "oc"})]
    (try
      (ops/create-ns! s1 'oc.core :source "(ns oc.core)\n\n(def a 1)\n")
      (ops/close! s1)
      (let [s2 (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "oc"})]
        (try
          (let [conn   (:db @s2)
                line   (:line @s2)
                head   (db/line-head conn line)
                rows   (db/measurements conn "open" nil)
                open-r (:payload (last rows))]
            (is (= 1 (count rows)) "one row per open of a store with history")
            (is (pos? (:deltas open-r)) (pr-str open-r))
            (is (number? (:load-ms open-r)) (pr-str open-r))
            (is (= head (:head open-r)) "the row names the head it loaded")
            (is (= head (db/line-head conn line))
                "and the head did not move — a measurement is not a delta")
            (testing "and the image boot that followed is a row of its own"
              ;; the JVM budget A/B was one careful experiment; every boot is
              ;; now an observation — what it cost, how much it loaded, and
              ;; whether it was a recycled image or a real launch
              (let [boot (:payload (last (db/measurements conn "image-boot" nil)))]
                (is (number? (:ms boot)) (pr-str boot))
                (is (= 1 (:namespaces boot)) (pr-str boot))
                (is (contains? boot :recycled?) (pr-str boot))
                (is (= head (:head boot)) "the boot names the head it loaded"))))
          (finally (ops/close! s2))))
      (finally
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (rm! (io/file dir)))))))

(deftest ^:external an-external-run-leaves-a-test-run-row-beside-the-journal
  ;; The shard-balancing finding — one namespace measured 0.8 s and 27.1 s —
  ;; was reached by RE-RUNNING. A row per run, with the per-namespace time the
  ;; shards already measure, answers variance-vs-drift longitudinally, and is
  ;; what predictive selection will train on. The `:observe` delta keeps
  ;; carrying the verdict; this row is the cost, beside the journal.
  (let [dir  (str (Files/createTempDirectory
                   "slopp-run-row" (make-array FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "rr"})]
    (try
      (ops/ingest! sess 'rr.core
                   (str "(ns rr.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (let [r    (external/external-test-run! sess)
            rows (db/measurements (:db @sess) "test-run" nil)
            row  (:payload (last rows))]
        (is (= :green (:status r)) (pr-str r))
        (is (= 1 (count rows)) "one row per external run")
        (is (= :external (:tier row)) (pr-str row))
        (is (= :green (:status row)))
        (is (= 1 (:ran row)))
        (is (number? (:ms row)))
        (is (contains? row :ns-ms) "the per-namespace time rides along when the runner measured it"))
      (finally
        (ops/close! sess)
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (rm! (io/file dir)))))))

(deftest ^:external a-dirless-session-still-has-a-journal-of-its-own
  ;; History is leaving the store value: every reader of it becomes a db
  ;; read. A session opened without a dir used to have NO db — its deltas
  ;; lived only in the value — which would leave every such reader with a
  ;; second code path over an in-memory list, kept alive for tests alone.
  ;; One path instead: a dirless open gets a private journal in a temp dir,
  ;; and `close!` removes it. Nothing about the session's behaviour changes
  ;; except that its history is queryable the same way every other's is.
  (let [sess (external/open!)]
    (try
      (is (some? (:db @sess)) "a journal, even with no dir")
      (is (some? (:dir @sess)) "and the dir it lives in")
      (ops/create-ns! sess 'dl.core :source "(ns dl.core)\n\n(def a 1)\n")
      (is (pos? (count (db/deltas-after (:db @sess) (:line @sess) 0)))
          "the write reached the journal, on the session's own line")
      (let [dir (io/file (:dir @sess))]
        (ops/close! sess)
        (is (not (.exists dir)) "close! removes the private journal with the session"))
      (finally (try (ops/close! sess) (catch Exception _))))))

(defn- land-unloaded!
  "Land `source` as `ns-sym` in `sess` WITHOUT hot-loading it — the fixture
  shape for a store whose code requires a framework this session's image
  never vendored (it booted on an empty store), where `ingest!`'s load would
  fail on the require. Goes through `try-commit!` so the journal and the
  value advance together: these fixtures used to `swap!` the value directly,
  which was harmless while a dirless session had no journal and is fatal now
  that every session has one — the value's head runs ahead of the line's,
  and every later write loses its CAS twelve times and gives up."
  [sess ns-sym source]
  (let [base (:store @sess)]
    (when-not (engine/try-commit! sess base (store/ingest base ns-sym source) [ns-sym])
      (throw (ex-info "fixture could not land" {:ns ns-sym})))))

(deftest ^:external build-supplies-the-framework-a-store-no-longer-declares
  ;; The build half of D-framework-injection. Once a store stops declaring
  ;; io.github.nvoxland/slopp-web, the BUILT app still has to get it — otherwise
  ;; removing the declaration produces an app with no framework at all, which is
  ;; why the halves are staged in this order.
  ;;
  ;; Part 2: it arrives as FILES, not a coord. slopp-web is never published to a
  ;; remote, so a coord in the generated deps.edn would name something only the
  ;; machine that ran slim-install can resolve — a build that ships broken to
  ;; anywhere else, and one that looks fine here.
  ;;
  ;; The manifests are FAKED rather than branched on. This suite runs from a
  ;; materialized tree, where the real readers look for a jar resource that does
  ;; not exist there and answer nil — so the vendoring assertions used to sit
  ;; inside an `if` that never took its true arm in the only environment this
  ;; test ever executes in. A fake also buys an assertion the real manifest
  ;; cannot: declare TWO families, use ONE, and the tree can be checked for what
  ;; did NOT arrive. That is where the opt-in is either real or is only a line
  ;; in a config file.
  (let [files '{"_"    {"slopp/fixture_syntax.cljc" "(ns slopp.fixture-syntax)\n"}
                "cli"  {"slopp/cli.clj" "(ns slopp.cli)\n"}
                "http" {"slopp/http.clj" "(ns slopp.http)\n(defn handle! \"H.\" [r] r)\n"
                        "slopp/http/router.clj" "(ns slopp.http.router)\n"}}
        ;; the "_" path is deliberately NOT slopp/lang.cljc, which is what
        ;; production files there. Vendoring writes into `src`, and `src` is the
        ;; FIRST classpath entry of the image running this test — so a stub at a
        ;; real path shadows the real namespace and breaks the machinery under
        ;; the assertions. The property being checked is that "_" travels
        ;; whichever capability is used, and a neutral path checks it safely.
        deps  '{"cli"  {metosin/malli {:mvn/version "0.20.1"}}
                "http" {garden/garden {:mvn/version "1.3.10"}}}]
    (with-redefs [boot/framework-files (constantly files)
                  boot/framework-deps  (constantly deps)]
      (let [sess (external/open!)
            dir  (str (java.nio.file.Files/createTempDirectory
                       "slopp-framework"
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
        (try
          ;; into the store VALUE, exactly as `a-framework-using-store-survives-a-RESTART`
          ;; does and for the reason recorded there: a store cannot INGEST code
          ;; requiring a framework its current image cannot load, and this image
          ;; booted before the store named anything to vendor. Going through
          ;; `create-ns!` here silently left the store EMPTY — which the previous
          ;; version of this test did, and never noticed, because its vendoring
          ;; assertions sat behind a branch that never ran.
          (land-unloaded! sess 'fw.app
                          (str "(ns fw.app (:require [slopp.http :as web]))\n\n"
                               "(defn ^:export handler \"H.\" [req] (web/handle! req))\n"))
          (is (= #{"http"} (engine/used-families (:store @sess)))
              "guard the guard: this store must USE http and nothing else, or
               every assertion below is about an empty derivation")
          (is (nil? (:error (external/build! sess dir))))
          (let [d  (edn/read-string (slurp (io/file dir "deps.edn")))
                at #(io/file dir "src" %)]
            (testing "the framework is IN the tree, so the app needs no repository"
              (is (.exists (at "slopp/http.clj")) (str "expected " (at "slopp/http.clj")))
              (is (str/includes? (slurp (at "slopp/http.clj")) "(ns slopp.http")
                  "the vendored file must be the real source, not a stub")
              (is (.exists (at "slopp/http/router.clj"))
                  "the whole family travels, not just the facade")
              (is (.exists (at "slopp/fixture_syntax.cljc"))
                  "and \"_\" with it — the dialect's helpers belong to the syntax
                   rather than to any one capability, so every user gets them"))
            (testing "and ONLY the family this store uses"
              ;; all-or-nothing vendoring would put slopp/cli.clj here and this
              ;; assertion is what notices. `(require 'slopp.cli)` succeeding in
              ;; an app that never enabled cli is the capability model holding in
              ;; the config file and not at runtime, which is the one place a
              ;; consumer would actually meet it.
              (is (not (.exists (at "slopp/cli.clj")))
                  "fw.app names nothing in cli, so no cli framework may be resolvable in it")
              (is (nil? (get-in d [:deps 'metosin/malli]))
                  (str "nor may it inherit cli's deps: " (pr-str (:deps d)))))
            (testing "and it is never a COORD — that would resolve only where
                      slim-install has run"
              (is (nil? (get-in d [:deps 'io.github.nvoxland/slopp-web]))
                  (pr-str (:deps d))))
            (testing "but what the framework itself REQUIRES is declared, or the app
                      ships source it cannot load — the build path has the same hole
                      the image had, found by slopp-ui removing the coord"
              (is (contains? (:deps d) 'garden/garden)
                  (str "garden missing from the built deps: " (pr-str (:deps d))))))
          (finally
            (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
              (rm! (io/file dir)))
            (ops/close! sess)))))))

(deftest ^:external a-framework-using-store-survives-a-RESTART
  ;; Two bugs, one test, and the second was invisible to the first version of it.
  ;;
  ;; (1) Vendoring was wired into session creation alone, so every image AFTER
  ;; the first booted with an empty src/ — and `fresh-image!` is on the path of
  ;; restart, deps_add/deps_remove, branch switch, ns_rename and the D5
  ;; staleness heal.
  ;;
  ;; (2) Vendoring copies SOURCE and discards the pom. The framework's own
  ;; requires — garden, hiccup, cheshire, http-kit — came from that pom, so the
  ;; files landed correctly and then failed INSIDE them. Reported from slopp-ui:
  ;; "Could not locate garden/core.clj", raised at slopp.http.css.
  ;;
  ;; Both stayed invisible because a store still declaring the coord got source
  ;; AND deps from ~/.m2 regardless.
  ;;
  ;; So the fake framework REQUIRES something external, and the assertion is
  ;; that the store LOADS. slopp-ui's correction, and it is the whole point: the
  ;; "resolves from the image dir, no ~/.m2" property was fully satisfied at the
  ;; moment their store was unloadable. Where a file comes from is not whether
  ;; the framework works. An earlier cut of this test used a dependency-free
  ;; fake and passed green against exactly bug (2).
  (let [fake-web (str "(ns slopp.http (:require [garden.core :as garden]))\n"
                      "(defn handle! \"H.\" [r] (garden/css [:a {:x 1}]) r)\n")]
    (with-redefs [boot/framework-files (constantly {"http" {"slopp/http.clj" fake-web}})
                  boot/framework-deps  (constantly '{"http" {garden/garden {:mvn/version "1.3.10"}}})]
      (let [sess (external/open!)
            vendored? (fn [] (.exists (io/file (:dir (:image @sess))
                                               "src" "slopp" "http.clj")))]
        (try
          ;; into the store VALUE: a store cannot ingest code requiring a
          ;; framework its current image cannot load, and priming a RUNNING
          ;; image's dir does nothing — a JVM caches a relative classpath dir
          ;; that did not exist at launch. Which is also why vendoring has to
          ;; precede process start.
          (land-unloaded! sess 'fw.app
                          (str "(ns fw.app (:require [slopp.http :as web]))\n"
                               "(defn h \"H.\" [r] (web/handle! r))\n"))
          (testing "the store really does use the framework"
            (is (some? (engine/framework-injection
                        (:store @sess) (boot/framework-files)))))
          (testing "a RESTART gives the new image the framework AND what the
                    framework itself requires — the store LOADS, which is the
                    property; the file being present is not"
            (ops/restart! sess)
            (is (vendored?) "the restarted image booted without the framework")
            (is (nil? (image/load-ns! (:image @sess) (:store @sess) 'fw.app))
                "loading is the real property — a vendored file that cannot
                 resolve its own requires is a dead store"))
          (testing "and again — one image is a fluke, two is the property"
            (ops/restart! sess)
            (is (vendored?))
            (is (nil? (image/load-ns! (:image @sess) (:store @sess) 'fw.app))))
          (finally (ops/close! sess)))))))

(deftest ^:external a-built-web-app-RUNS-outside-slopp-entirely
  ;; SHAPE was never the question. Every build! test here asserted the tree
  ;; materializes — files present, deps.edn contains X — and all of them passed
  ;; while a built app died on its first require, because vendoring copies
  ;; source and the pom that carried garden/hiccup/cheshire/http-kit was
  ;; discarded with the coord.
  ;;
  ;; The consumer found it, by running the tree. That is the wrong dependency:
  ;; slopp-ui is ONE app, the next one will not report this well, and slopp's
  ;; correctness should not rest on a consumer happening to look. So slopp owns
  ;; a minimal web app of its own and RUNS it.
  ;;
  ;; A fresh JVM with no slopp on the classpath — `clojure -M` in the tree — is
  ;; the point: an image would prove nothing, since the image is the OTHER path
  ;; and vendors separately. This is what a user's deployment sees.
  ;;
  ;; The framework is FAKED, and the fake requires something external, because
  ;; this suite runs from a checkout where boot/framework-files is nil. A first
  ;; cut branched on that and skipped the run — leaving a behaviour test that
  ;; asserted shape in the only environment it ever executes in, which is the
  ;; defect it exists to catch. The stand-in has the property under test:
  ;; requires of its own that the built deps.edn must declare.
  (with-redefs [boot/framework-files
                (constantly
                 {"http"
                  {"slopp/http/css.clj"
                   (str "(ns slopp.http.css (:require [garden.core :as garden]))\n"
                        "(defn css-response \"C.\" [rules]\n"
                        "  {:status 200 :body (garden/css rules)})\n")}})
                boot/framework-deps
                (constantly '{"http" {garden/garden {:mvn/version "1.3.10"}}})]
    (let [sess (external/open!)
          dir  (str (Files/createTempDirectory "slopp-runs"
                                               (make-array FileAttribute 0)))]
      (try
        ;; into the store VALUE: this session's image was booted on an empty
        ;; store and so vendored nothing, and create-ns! hot-loads — it would
        ;; fail on the require and the ns would never land. build! reads the
        ;; store, which is what is under test here.
        (land-unloaded! sess 'runs.app
                        (str "(ns runs.app\n"
                             "  (:require [slopp.http.css :as css]))\n\n"
                             "(defn ^:export stylesheet \"S.\" []\n"
                             "  (css/css-response [[:body {:color \"red\"}]]))\n"))
        (is (nil? (:error (external/build! sess dir))))
        (testing "the framework source is IN the tree"
          (is (.exists (io/file dir "src" "slopp" "http" "css.clj"))))
        (testing "and what the framework itself requires is declared, or the
                  tree carries source it cannot load"
          (is (contains? (:deps (edn/read-string (slurp (io/file dir "deps.edn"))))
                         'garden/garden)))
        (testing "so it LOADS in a fresh JVM that has never heard of slopp —
                  the assertion shape assertions cannot make"
          (let [r (sh-outside-slopp!
                   "clojure" "-M" "-e" "(require 'runs.app) (println :LOADED-OK)"
                   :dir dir)]
            (is (zero? (:exit r))
                (str "a built app must run outside slopp.\nexit " (:exit r)
                     "\nout: " (:out r) "\nerr: " (:err r)))
            (is (str/includes? (:out r) ":LOADED-OK")
                (str "out: " (:out r) "\nerr: " (:err r)))))
        (testing "and it FIRES — without the framework's deps the same tree
                  fails, which is the bug slopp-ui hit. A green run here proves
                  nothing unless the red one is reachable, and every defect this
                  wave was hidden by a check that could not fail"
          (let [dir2 (str (Files/createTempDirectory
                           "slopp-runs-nodeps" (make-array FileAttribute 0)))]
            (try
              (with-redefs [boot/framework-deps (constantly nil)]
                (external/build! sess dir2))
              (let [r (clojure.java.shell/sh
                       "clojure" "-M" "-e" "(require 'runs.app)" :dir dir2)]
                (is (not (zero? (:exit r)))
                    "vendored source with no deps declared must NOT load")
                (is (str/includes? (:err r) "garden")
                    (str "and it must fail on the framework's own require: "
                         (:err r))))
              (finally
                (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f)))
                          (.delete f))]
                  (rm! (io/file dir2)))))))
        (finally
          (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
            (rm! (io/file dir)))
          (ops/close! sess))))))

(deftest ^:external the-warm-spare-is-ADOPTED-and-carries-the-framework
  ;; slopp-ui measured this and slopp should assert it — the same principle as
  ;; the build-and-run test. Their instrument, kept because it is better than
  ;; anything timing-based: compare the image JVM's ABSOLUTE start time against a
  ;; mark taken before the restart was requested.
  ;;
  ;;   negative → the JVM existed before I asked → a warmed spare was adopted
  ;;   positive → it booted during the restart → no spare
  ;;
  ;; Immune to round-trip latency, which is what makes uptime-based timing
  ;; useless here. Their before/after on the two jars: +2696 (fresh boot) then
  ;; -6288 / -11256 / -8643 (adopted), three for three.
  ;;
  ;; Adoption alone is worth nothing — an adopted image MISSING the framework
  ;; trades a restart bug for a subtler one, so this asserts both. That pairing
  ;; is the actual claim the one-door change makes.
  (with-redefs [boot/framework-files
                (constantly {"http" {"slopp/http.clj"
                                     "(ns slopp.http)\n(defn handle! \"H.\" [r] r)\n"}})
                boot/framework-deps (constantly nil)]
    (let [sess (external/open! {:slopp.ops/warm-spare? true})]
      (try
        (land-unloaded! sess 'sp.app
                        (str "(ns sp.app (:require [slopp.http :as web]))\n"
                             "(defn h \"H.\" [r] (web/handle! r))\n"))
        ;; First restart warms a spare from a store that NOW needs the framework.
        ;; The spare standing at open was warmed from an empty store and must be
        ;; discarded rather than adopted — that mismatch is its own hazard, and
        ;; asserting adoption on this restart would be asserting the bug.
        (ops/restart! sess)
        ;; WAIT for the spare's JVM to exist before marking t0. start-spare!
        ;; returns a future, so the boot happens in the BACKGROUND — and their
        ;; samples were 6-11s apart by human pacing, which hid this. In a tight
        ;; loop the mark lands before the spare has launched, and a correctly
        ;; ADOPTED image then reads as +29ms: the right answer to the wrong
        ;; question. Deref blocks until the handle is real.
        (some-> (:spare @sess) deref)
        (let [t0 (System/currentTimeMillis)]
          (ops/restart! sess)
          (let [started (first (repl/eval!
                                (:image @sess)
                                (str "(.getStartTime (java.lang.management."
                                     "ManagementFactory/getRuntimeMXBean))")))]
            (testing "the spare was ADOPTED — its JVM predates the request"
              (is (number? started) (pr-str started))
              (is (< started t0)
                  (str "image JVM started " (- started t0) "ms relative to the"
                       " restart request; negative means adopted, positive"
                       " means it booted during the restart and the spare was"
                       " lost")))))
        (testing "and the adopted image CARRIES the framework — adoption without
                  it is the worse bug, not the fix"
          (is (nil? (image/load-ns! (:image @sess) (:store @sess) 'sp.app))))
        (finally (ops/close! sess))))))

(deftest ^:external a-built-rest-app-ENFORCES-its-contract-outside-slopp-entirely
  ;; The third of these, and each one exists because SHAPE assertions cannot
  ;; make the claim: the web one because vendored source failed inside itself on
  ;; a missing garden, the cli one because a generated entry can be present and
  ;; do nothing. This one because a boundary that is not reachable in a
  ;; consumer's tree is a boundary that protects slopp and nobody else.
  ;;
  ;; What is NOT re-proved here: the socket. That is the adapter's job and
  ;; `a-built-web-app-RUNS-outside-slopp-entirely` covers it. The claim this
  ;; makes is the one only rest can make — the vendored family loads, malli
  ;; resolves from the generated deps.edn, and a body that breaks its contract
  ;; is REFUSED by code running in a tree that has never heard of slopp.
  (let [src-of  (fn [p] (some-> (io/resource p) slurp))
        subtree (fn [top dir-name]
                  (let [f   (io/file (.toURI (io/resource top)))
                        dir (io/file (.getParentFile f) dir-name)
                        n   (inc (count (.getPath dir)))]
                    (into {top (slurp f)}
                          (for [x (file-seq dir)
                                :when (and (.isFile x) (.endsWith (.getName x) ".clj"))]
                            [(str (subs top 0 (- (count top) 4)) "/" (subs (.getPath x) n))
                             (slurp x)]))))
        http-fs (subtree "slopp/http.clj" "http")
        rest-fs (subtree "slopp/rest.clj" "rest")
        common  {"slopp/lang.cljc"  (src-of "slopp/lang.cljc")
                 "slopp/cache.clj"  (src-of "slopp/cache.clj")}
        files   {"_" common "http" http-fs "rest" rest-fs}
        deps    '{"cli"  {metosin/malli {:mvn/version "0.20.1"}}
                  "rest" {metosin/malli {:mvn/version "0.20.1"}}
                  "http" {cheshire/cheshire {:mvn/version "5.13.0"}
                          hiccup/hiccup     {:mvn/version "2.0.0"}
                          garden/garden     {:mvn/version "1.3.10"}
                          http-kit/http-kit {:mvn/version "2.8.0"}}}]
    ;; guard the guard: an empty family vendors nothing and every assertion
    ;; below would pass or fail for the wrong reason
    (is (contains? rest-fs "slopp/rest/contract.clj") (pr-str (keys rest-fs)))
    (is (contains? http-fs "slopp/http/dispatch.clj") (pr-str (keys http-fs)))
    (is (every? some? (vals common)))

    (with-redefs [boot/framework-files (constantly files)
                  boot/framework-deps  (constantly deps)]
      (let [sess (external/open!)
            dir  (str (Files/createTempDirectory "slopp-rest-runs"
                                                 (make-array FileAttribute 0)))
            ;; the probe an author would write: assemble the app's own context WITH
            ;; the boundary on it, and call an endpoint with a bad body. Not
            ;; two steps any more: `context` refuses to assemble a declared
            ;; contract it cannot honour, so there is no unvalidated context
            ;; left for a wrap to arrive too late for
            probe (str "(require 'slopp.http 'slopp.rest 'shop.api)\n"
                       "(let [ctx (slopp.http/context\n"
                       "            {:http/namespaces '[shop.api]\n"
                       "             :http/wrap-context slopp.rest/validating})\n"
                       "      r   (slopp.rest/call ctx {:method :post :path \"/api/orders\"\n"
                       "                                :body {:sku 42}})]\n"
                       "  (println :STATUS (:status r)))")]
        (try
          ;; into the store VALUE: this session's image booted on an empty store
          ;; and vendored nothing, so a hot-loaded require of slopp.rest would
          ;; fail. build! reads the store, which is what is under test.
          (land-unloaded! sess 'shop.api
                          (str "(ns shop.api)\n\n"
                               "(defn ^{:http/method :post :http/path \"/api/orders\"\n"
                               "        :http/auth :public\n"
                               "        :rest/request [:map [:sku :string]]\n"
                               "        :rest/response [:map [:id :int]]}\n"
                               "  create! \"Place an order.\" [req] {:status 200 :body {:id 1}})\n"))
          ;; through the real write, not a hand-built map: a config entry carries a
          ;; :format its serializer needs, and assoc-in'ing the values alone
          ;; produces a store that cannot be materialized. The code above has to
          ;; bypass the image (it requires a framework this image cannot load);
          ;; a config write has no such problem.
          (ops/config-file! sess "capabilities" :key "rest.enabled" :value "true"
                            :prompt "publish a typed API")
          (is (nil? (:error (external/build! sess dir))))

          (testing "the rest family is IN the tree and its dep is DECLARED"
            (is (.exists (io/file dir "src" "slopp" "rest" "contract.clj")))
            (let [d (edn/read-string (slurp (io/file dir "deps.edn")))]
              (is (contains? (:deps d) 'metosin/malli)
                  (str "slopp.rest.contract requires malli: " (pr-str (:deps d))))))

          (testing "and the boundary REFUSES a bad body in a JVM that never heard of slopp"
            (let [r (sh-outside-slopp! "clojure" "-M" "-e" probe :dir dir)]
              (is (zero? (:exit r))
                  (str "the vendored rest framework must load.\nout: " (:out r)
                       "\nerr: " (:err r)))
              (is (str/includes? (:out r) ":STATUS 400")
                  (str "a body declaring :sku 42 against [:sku :string] must be"
                       " refused THERE, not only here.\nout: " (:out r)
                       "\nerr: " (:err r)))))

          (testing "negative control: without the framework's deps the same tree fails"
            ;; a green run above proves nothing unless the red one is reachable
            (let [dir2 (str (Files/createTempDirectory
                             "slopp-rest-nodeps" (make-array FileAttribute 0)))]
              (try
                (with-redefs [boot/framework-deps (constantly nil)]
                  (external/build! sess dir2))
                (let [r (clojure.java.shell/sh
                         "clojure" "-M" "-e" "(require 'slopp.rest)" :dir dir2)]
                  (is (not (zero? (:exit r)))
                      "vendored rest source with no deps declared must NOT load")
                  (is (str/includes? (:err r) "malli")
                      (str "and it must fail on the framework's own require: "
                           (:err r))))
                (finally
                  (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f)))
                            (.delete f))]
                    (rm! (io/file dir2)))))))
          (finally
            (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
              (rm! (io/file dir)))
            (ops/close! sess)))))))

(deftest ^:external a-built-WEBAPP-COMPILES-and-its-app-code-declares-no-cljs
  ;; The wave's claim, in the one place it can be false: **an app that opts into
  ;; `webapp` writes NO ClojureScript.** `a-REALISTIC-browser-app-is-DRIVEN-…`
  ;; shows such an app runs headlessly, which is the half a JVM can see. This is
  ;; the other half — that the same app, vendored the framework and handed to
  ;; the ClojureScript compiler, produces a bundle.
  ;;
  ;; Those are genuinely different questions. Every screen here is `:cljc`, so
  ;; the in-image drive exercises the JVM branch of every one of them; a form
  ;; that loads on a JVM and does not compile to JS passes that test and fails
  ;; this one.
  ;;
  ;; **The framework is vendored from the REAL rendered source**, not a
  ;; stand-in. A checkout has no jar resources, so `framework-files` answers nil
  ;; and nothing would vendor — but the substitute here IS `slopp.webapp` and
  ;; `slopp.webapp.dom` as this store holds them, so what compiles is the code
  ;; that ships.
  (let [
        ;; each under its OWN platform's extension. `ns-path`'s 1-arity is :jvm,
        ;; and a framework vendored as `.clj` is invisible to the ClojureScript
        ;; compiler — which fails as "No such namespace: slopp.webapp" and reads
        ;; exactly like the family not being vendored at all
        ;; read off the BUILT TREE at the paths it actually occupies, rather
        ;; than re-derived from a store. `built-store` re-ingests source and
        ;; says so — module platforms do not survive that round trip — so
        ;; `ns-path` over it answers `.clj` for every one of these, and a
        ;; framework vendored as `.clj` is INVISIBLE to the ClojureScript
        ;; compiler. That failure reads as "No such namespace: slopp.webapp",
        ;; which is indistinguishable from the family never being vendored
        find!  (fn [rel]
                 (or (first (filter #(.exists ^java.io.File %)
                                    [(io/file "src" rel) (io/file "cljs-src" rel)]))
                     (throw (ex-info (str "the built tree has no " rel
                                          " — this fixture vendors the framework"
                                          " from the ARTIFACT, so a moved or"
                                          " renamed file fails here loudly rather"
                                          " than compiling to nothing")
                                     {:path rel}))))
        vend   (fn [rels] (into {} (for [rel rels] [rel (slurp (find! rel))])))
        family (vend ["slopp/lang.cljc" "slopp/webapp.cljc"
                      "slopp/webapp/dom.cljs"])
        ;; webapp REQUIRES http, so a browser app is vendored that family too —
        ;; and it has to be, because `slopp.webapp` requires
        ;; `slopp.http.endpoint`. Keyed here as the real manifest keys it
        ;; rather than folded into the webapp map: a stub that hands a file
        ;; under the wrong family is green while production is not
        served (vend ["slopp/http/endpoint.cljc"])
        app    (str "(ns shop.ui\n"
                    "  (:require [slopp.webapp :as webapp]))\n\n"
                    ;; a PAGE is one function of one map that declares its own
                    ;; address. The table used to hold spec maps — `{:render …
                    ;; :request …}` — in a value only the running app could
                    ;; read; a marker is a fact a build can see
                    "(defn ^{:webapp/path \"/things\"} things \"The list.\"\n"
                    "  [{:keys [state]}]\n"
                    "  [:ul (for [t (webapp/load-value state :main)] [:li (:name t)])])\n\n"
                    "(defn ^{:http/method :get :http/path \"/\" :http/auth :public\n"
                    "        :rest/response :string :webapp/client-routes [\"/things\"]}\n"
                    "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})\n\n"
                    ;; the DECLARATION, not `(webapp/wiring …)`. Both entries
                    ;; derive from this one value — `cljnx/driver-for`
                    ;; headlessly, `dom/mount!` in the browser — and a
                    ;; pre-wired map is refused, because it already carries the
                    ;; derived :webapp/view
                    "(defn ^:app/entry app \"The application.\" []\n"
                    "  {:webapp/state (atom {})})\n")]
    (testing "the fixture really vendors what it claims to"
      ;; without this the compile below could be green having compiled nothing
      ;; of the framework at all
      (is (= 3 (count family)) (pr-str (keys family)))
      (is (every? #(re-find #"\(ns slopp\." %) (concat (vals family) (vals served)))
          "a rendered namespace that is not source would make this vacuous")
      (is (= #{"slopp/http/endpoint.cljc"} (set (keys served)))
          (str "the http half is what `slopp.webapp` requires — vendored under"
               " the wrong family key it is absent, and the compile fails as"
               " the app's own mistake: " (pr-str (keys served))))
      (is (= #{"slopp/lang.cljc" "slopp/webapp.cljc" "slopp/webapp/dom.cljs"}
             (set (keys family)))
          (str "vendored under the wrong EXTENSION the compiler simply does not"
               " see them, and the error reads as the family being absent: "
               (pr-str (keys family)))))

    (with-redefs [boot/framework-files (constantly {"webapp" family "http" served})
                  boot/framework-deps
                  (constantly '{"webapp" {no.cjohansen/replicant {:mvn/version "2026.07.1"}}})]
      (let [sess (external/open!)]
        (try
          (ops/deps-add! sess 'org.clojure/clojurescript {:mvn/version "1.11.132"}
                         :client true :prompt "the cljs compiler")
          (ops/module-platform! sess "shop.ui" :cljc :prompt "an app's own code is portable")
          (land-unloaded! sess 'shop.ui app)

          (testing "the app declares NO ClojureScript of its own"
            ;; the goal stated as an assertion rather than as a property of the
            ;; fixture. If a realistic browser app cannot be written without a
            ;; `:cljs` namespace, this is the line that goes red
            (let [st (:store @sess)]
              (is (= [] (filterv #(= :cljs (store/platform-for st %))
                                 (keys (:namespaces st))))
                  "an app's own code reached for the browser")))

          (testing "and it COMPILES — the half no in-image drive can reach"
            (let [r (cljs/compile-client! sess)]
              (is (nil? (:error r)) (pr-str r))
              (is (pos? (or (:bytes r) 0)) (pr-str r))))

          (finally (ops/close! sess)))))

    (testing "and it FIRES: without the family vendored the same store fails"
      ;; a green compile proves nothing unless the red one is reachable, and
      ;; every defect this wave was hidden by a check that could not fail
      (with-redefs [boot/framework-files (constantly {})
                    boot/framework-deps  (constantly {})]
        (let [sess (external/open!)]
          (try
            (ops/deps-add! sess 'org.clojure/clojurescript {:mvn/version "1.11.132"}
                           :client true :prompt "the cljs compiler")
            (ops/module-platform! sess "shop.ui" :cljc :prompt "an app's own code is portable")
            (land-unloaded! sess 'shop.ui app)
            (let [r (cljs/compile-client! sess)]
              (is (some? (:error r))
                  (str "an app requiring slopp.webapp compiled without it: " (pr-str r)))
              (is (str/includes? (str (:error r)) "slopp.webapp")
                  (str "and it must fail on the FRAMEWORK's require — a red for"
                       " any other reason makes the green above prove nothing: "
                       (pr-str r))))
            (finally (ops/close! sess))))))))

(deftest ^:external a-built-BROWSER-app-gets-its-entry-generated
  ;; The thing the whole cljnx wave existed to unblock. `webapp-launcher-source`
  ;; was written, tested, and had NO CALLER for a wave: the entry marker was
  ;; asked for two incompatible shapes, so a generated `(mount! (entry))`
  ;; refused at page load for any app that could also be opened headlessly.
  ;;
  ;; With one declaration and one public derivation the fork is gone, and this
  ;; is the assertion that the capability's stated goal — **an app that opts
  ;; into `webapp` writes no ClojureScript** — is finally true rather than true
  ;; except for the two forms every app hand-wrote.
  (let [app (str "(ns shop.ui\n"
                 "  (:require [slopp.webapp :as webapp]))\n\n"
                 "(defn ^{:webapp/path \"/things\"} things \"The list.\"\n"
                 "  [{:keys [state]}]\n"
                 "  [:ul (for [t (webapp/load-value state :main)] [:li (:name t)])])\n\n"
                 "(defn ^{:http/method :get :http/path \"/\" :http/auth :public\n"
                 "        :rest/response :string :webapp/client-routes [\"/things\"]}\n"
                 "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})\n\n"
                 ;; no `:webapp/routes` — the pages carry their own addresses,
                 ;; and the build is what joins them into a table
                 "(defn ^:app/entry app \"The application.\" []\n"
                 "  {:webapp/state (atom {})})\n")
        dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-browser-entry-" (System/nanoTime))
        sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "a browser app")
      (ops/module-platform! sess "shop.ui" :cljc :prompt "an app's own code is portable")
      (land-unloaded! sess 'shop.ui app)

      (testing "the fixture is a webapp with exactly one marked entry"
        ;; population control: with no entry there is nothing to generate, and
        ;; every assertion below would pass by describing an empty build
        (let [st (:store @sess)]
          (is (= 1 (count (rules.webapp/page-rows st)))
              (pr-str (rules.webapp/page-rows st)))))

      (let [r (external/build! sess dir)]
        (is (nil? (:error r)) (pr-str r))

        (testing "the browser entry is GENERATED, under the client source root"
          ;; cljs-src/, not src/ — the ClojureScript compiler has to see it and
          ;; the JVM classpath must not
          (let [f (io/file dir "cljs-src" "native" "client.cljs")]
            (is (.exists f)
                (str "no generated browser entry — every browser app is still"
                     " hand-writing the two forms this capability exists to"
                     " remove. :client-entry was " (pr-str (:client-entry r))))
            (when (.exists f)
              (let [src (slurp f)]
                (testing "it mounts the app's own entry"
                  (is (str/includes? src "shop.ui/app") src)
                  (is (str/includes? src "dom/mount!") src))
                (testing "and REQUIRES what that entry reaches"
                  ;; naming a page without requiring its closure is a call to a
                  ;; var that does not exist, which reaches a reader as a blank
                  ;; page and reads like a rendering bug rather than a wiring one
                  (is (str/includes? src "[shop.ui]") src))
                (testing "and carries the table the PAGES declared"
                  ;; the entry fn declares no `:webapp/routes` at all — the
                  ;; address lives on the page, and the build is the reader
                  ;; that joins them. Without this the app mounts and every
                  ;; url renders not-found, which looks like a routing bug in
                  ;; an app whose routing was never wired
                  (is (str/includes? src "\"/things\" shop.ui/things") src)
                  (is (str/includes? src ":webapp/routes") src))))))

        (testing "and the result SAYS it emitted one"
          (is (= "cljs-src/native/client.cljs" (:client-entry r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external a-store-that-mounts-its-OWN-browser-entry-gets-no-second-one
  ;; Reported by a consuming store, which compiled the bundle and COUNTED the
  ;; mounts rather than reasoning about them: two top-level bootstraps, its own
  ;; and a generated one, over the same element.
  ;;
  ;; The guard this replaces asked whether a namespace was NAMED `native.client`.
  ;; That is the collision the generator can cause with itself, not the one that
  ;; happens: a store's hand-written entry is called whatever the store calls it,
  ;; and every other name passed clean. Nothing downstream could catch it either
  ;; — a bundle with two mounts compiles exactly as clean as one with one, so
  ;; `compile_client` reported success and the only symptom was a page rendering
  ;; twice.
  ;;
  ;; So the signal is BEHAVIOUR: a store form that calls `dom/mount!` already
  ;; mounts this app, whatever its namespace is called.
  (let [app  (str "(ns shop.ui\n"
                  "  (:require [slopp.webapp :as webapp]))\n\n"
                  "(defn things \"The list.\" [s]\n"
                  "  [:ul (for [t (webapp/load-value s :main)] [:li (:name t)])])\n\n"
                  "(defn ^{:http/method :get :http/path \"/\" :http/auth :public\n"
                  "        :rest/response :string :webapp/client-routes [\"/things\"]}\n"
                  "  doc \"The document.\" [_] {:status 200 :body \"<html></html>\"})\n\n"
                  "(defn ^:app/entry app \"The application.\" []\n"
                  "  {:webapp/state  (atom {})\n"
                  "   :webapp/routes [[\"/things\" things]]})\n")
        ;; the hand-written entry, named ANYTHING but native.client — which is
        ;; the whole point, since that is the only name the old guard knew
        own  (str "(ns shop.client.app\n"
                  "  (:require [slopp.webapp.dom :as dom]\n"
                  "            [shop.ui :as ui]))\n\n"
                  "(defn ^:export main \"Mount the app.\" []\n"
                  "  (dom/mount! (ui/app)))\n")
        dir  (str (System/getProperty "java.io.tmpdir")
                  "/slopp-own-entry-" (System/nanoTime))
        sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "a browser app")
      (ops/module-platform! sess "shop.ui" :cljc :prompt "an app's own code is portable")
      (land-unloaded! sess 'shop.ui app)
      (ops/module-platform! sess "shop.client.app" :cljs :prompt "a browser entry is cljs")
      (land-unloaded! sess 'shop.client.app own)

      (testing "the fixture declares an entry AND mounts it by hand"
        ;; population control, both halves: without the entry there is nothing
        ;; to generate, and without the hand-written mount there is no collision
        ;; — either way every assertion below would pass by describing nothing
        (let [st (:store @sess)]
          (is (= 1 (count (rules.webapp/page-rows st)))
              (pr-str (rules.webapp/page-rows st)))
          ;; checked against the RENDERED source rather than through the
          ;; detection this test is about, which would be circular — and the
          ;; reference graph cannot answer it at all: `refs` records only
          ;; edges whose target is IN the store, and `slopp.webapp.dom` is
          ;; framework, vendored at build. That was the first signal tried
          ;; here, and it reported an empty graph for a fixture that plainly
          ;; mounts.
          (is (str/includes? (store.render/render-ns st 'shop.client.app) "dom/mount!")
              "the fixture does not actually mount, so there is no collision")))

      (let [r (external/build! sess dir)]
        (is (nil? (:error r)) (pr-str r))

        (testing "no second entry is generated beside the store's own"
          (is (nil? (:client-entry r))
              (str "a second browser entry was generated beside the store's"
                   " own mount — this bundle double-mounts: "
                   (pr-str (:client-entry r))))
          (is (not (.exists (io/file dir "cljs-src" "native" "client.cljs")))
              "the generated launcher file was written anyway"))

        (testing "and the build SAYS so, because a silent non-emission is the other failure"
          ;; the store that asked for a generated entry and got none must not
          ;; have to diff the tree to find out
          (is (string? (:client-entry-skipped r)) (pr-str r))
          (is (str/includes? (str (:client-entry-skipped r)) "shop.client.app")
              (str "the reason does not name the form that already mounts: "
                   (pr-str (:client-entry-skipped r))))))
      (finally (ops/close! sess)))))

(deftest ^:external closing-a-session-twice-releases-nothing-twice
  ;; `close!` PARKS the image for the next session. A second `close!` on the
  ;; same session found the same image still in the atom, reset it again and
  ;; parked it AGAIN — one process in the pool twice, handed to two later
  ;; tenants, and the second of those met a closed socket the moment the
  ;; first stopped it. Found as `create-ns-modes` dying with "Socket closed"
  ;; only when a test that closes in its body AND in its finally ran before
  ;; it. A close leaves nothing behind for a second close to release.
  (let [sess (external/open!)]
    (ops/create-ns! sess 'tw.core :source "(ns tw.core)\n\n(def a 1)\n")
    (ops/close! sess)
    (is (nil? (:image @sess)) "the image was handed on — the session no longer holds it")
    (is (nil? (:db @sess)) "the connection was closed — the session no longer holds it")
    (is (nil? (ops/close! sess)) "a second close is a no-op, not a second park")))

(deftest ^:external the-app-and-the-jar-are-placed-against-the-journal
  ;; Two of the three artifact currencies. `app-behind` is slopp-ui's
  ;; friction #5 — a served page whose stylesheet was still the old one while
  ;; every surface said fine — and the rule it fixed: a counter that reads 0
  ;; both when you are current and when nobody updated it is not a counter.
  ;; `jar-currency` is friction #17 — a green commit-point whose jar had never
  ;; been rebuilt, caught twice by a consumer reading the artifact. Both used
  ;; to fold the whole delta list the value carried; both are one indexed
  ;; read now, so the value need not carry it.
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'cur.core :source "(ns cur.core)\n\n(defn ^:unused-ok f \"F.\" [x] x)\n")
      (testing "app-behind: 0 for an image served after the last code delta, and 0 is an ANSWER"
        (is (= 0 (ops/app-behind sess {:serving? true
                                       :served-at (+ 1000 (System/currentTimeMillis))}))))
      (testing "and it COUNTS the code deltas an image was served before"
        (is (pos? (ops/app-behind sess {:serving? true :served-at 0}))
            "an image from before every delta is behind by all of them"))
      (testing "nothing serving is a different answer from a current image"
        ;; nil, not 0: a store slopp runs no app for must not be told its app
        ;; is up to date — the brief omits the key rather than reassuring
        (is (nil? (ops/app-behind sess nil)))
        (is (nil? (ops/app-behind sess {:serving? false :served-at 0})))
        (is (nil? (ops/app-behind sess {:serving? true}))
            "a running map that never recorded WHEN it served cannot answer"))
      (testing "jar-currency: no stamp — nothing to report, and no invented zero"
        (is (nil? (ops/jar-currency sess nil))))
      (testing "a head this store has never seen is reported WITHOUT a count"
        (let [r (ops/jar-currency sess "d-from-another-store")]
          (is (= "d-from-another-store" (:head r))
              "the identity still travels — it is what a human compares by hand")
          (is (not (contains? r :behind))
              "and the count is absent, not zero: this store cannot place that head")))
      (testing "a head this store HAS is placed, and counted the one way"
        (let [head (:head (:store @sess))
              r    (ops/jar-currency sess head)]
          (is (= head (:head r)))
          (is (= 0 (:behind r)) "built from the head — behind by nothing")
          (ops/create-ns! sess 'cur.more :source "(ns cur.more)\n\n(defn ^:unused-ok g \"G.\" [x] x)\n")
          (is (pos? (:behind (ops/jar-currency sess head)))
              "a write after the jar's head is what it is behind by")))
      (finally (ops/close! sess)))))

(deftest ^:external a-write-keeps-the-reference-index-current-and-a-reopen-finds-it
  ;; The graph used to be rebuilt whole after every write — 1.8 s on slopp's
  ;; own store, and 9.3 s cold at every open. The write path now refreshes
  ;; exactly the namespaces it rewrote (`refs/refresh` in `try-commit!`), the
  ;; entries land beside the elements, and the next process reads them.
  ;; Both namespaces live in ONE module (`ri.core`), so the module gate has
  ;; no say here.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-refidx" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "refidx"})
        edge (fn [rows] (some #(and (= 'ri.core.two (:from-ns %)) (= 'f (:to-name %))) rows))]
    (try
      (is (nil? (:error (ops/ingest! sess 'ri.core "(ns ri.core)\n\n(defn f [x] x)\n"))))
      (is (nil? (:error (ops/ingest! sess 'ri.core.two
                                     "(ns ri.core.two (:require [ri.core :as c]))\n\n(defn g [x] (c/f x))\n"))))
      (is (nil? (:error (ops/edit-replace! sess 'ri.core.two 'g "(defn g [x] (c/f (c/f x)))"
                                           :prompt "twice"))))
      (let [st (:store @sess)]
        (testing "every namespace the session wrote has a current entry"
          (doseq [nsx ['ri.core 'ri.core.two]]
            (is (= (slopp.index.refs/ns-key st nsx) (get-in st [:refs nsx :key]))
                (str nsx " — the write path did not refresh its entry; index holds "
                     (pr-str (update-vals (:refs st) #(select-keys % [:key])))
                     " over namespaces " (pr-str (keys (:namespaces st)))))))
        (is (edge (get-in st [:refs 'ri.core.two :rows])) "and the rows are the edges"))
      (finally (ops/close! sess)))
    (let [sess2 (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "refidx"})]
      (try
        (let [st (:store @sess2)]
          (is (= (slopp.index.refs/ns-key st 'ri.core.two)
                 (get-in st [:refs 'ri.core.two :key]))
              "a fresh process reads the index it was left")
          (is (edge (slopp.index.refs/refs st))))
        (finally (ops/close! sess2))))))

(deftest ^:external the-done-point-brings-stale-index-entries-current
  ;; The write path refreshes what it rewrote; a journal replay of another
  ;; agent's deltas, a merge, or a store written before the index existed
  ;; leave entries missing or keyed on an older source. `ns-refs` recomputes
  ;; those on read, correctly and every time — `refresh-index!` is what makes
  ;; that stop happening: it recomputes exactly the entries that do not match,
  ;; persists them, and leaves the current ones alone.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-refidx2" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "refidx2"})]
    (try
      (is (nil? (:error (ops/ingest! sess 'rx.core "(ns rx.core)\n\n(defn f [x] x)\n"))))
      (is (nil? (:error (ops/ingest! sess 'rx.core.two
                                     "(ns rx.core.two (:require [rx.core :as c]))\n\n(defn g [x] (c/f x))\n"))))
      ;; the shape a replay leaves: one entry gone, one keyed on an older source
      (swap! sess update :store
             #(-> % (update :refs dissoc 'rx.core)
                    (assoc-in [:refs 'rx.core.two :key] "an-older-source")))
      (let [r  (ops/refresh-index! sess)
            st (:store @sess)]
        (is (= #{'rx.core 'rx.core.two} (set (:refreshed r))) (pr-str r))
        (doseq [nsx ['rx.core 'rx.core.two]]
          (is (= (slopp.index.refs/ns-key st nsx) (get-in st [:refs nsx :key])) (str nsx)))
        (testing "and it is on disk — a reopen reads exactly these entries"
          (let [conn   (:db @sess)
                loaded (db/load-store conn (:line @sess))]
            (is (= (:refs st) (:refs loaded)))))
        (testing "a second pass finds nothing to do"
          (is (empty? (:refreshed (ops/refresh-index! sess))))))
      (finally (ops/close! sess)))))

(deftest ^:external a-test-run-repeated-with-nothing-landed-stands
  ;; 26% of slopp's own wall time was a tool repeated inside ONE ask —
  ;; test_run 510 extra runs on this store — re-answering a question nothing
  ;; had changed. The same courtesy full_check and done already extend.
  (let [sess (external/open!)
        verifies #(count (filter (fn [d] (= :verify (:op d))) (ops/journal sess)))]
    (try
      (ops/ingest! sess 'sr.core
                   (str "(ns sr.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] x)\n"
                        "(deftest f-t (is (= 1 (f 1))))\n"
                        "(deftest ^:external f-ext-t (is (= 2 (f 2))))\n"))
      (let [r1 (ops/test-run! sess 'sr.core)
            n  (verifies)
            r2 (ops/test-run! sess 'sr.core)]
        (is (nil? (:standing r1)) (pr-str r1))
        (is (true? (:standing r2)) (pr-str r2))
        (is (= n (verifies)) "the repeat records no second verify")
        (is (= (:pass r1) (:pass r2)) "and answers with the run it made")
        (testing "a narrower selection is a different question"
          (is (nil? (:standing (ops/test-run! sess 'sr.core :only ['f-t])))))
        (testing "the verify a WRITE records does not stand in for a run"
          (ops/edit-replace! sess 'sr.core 'f "(defn f [x] (identity x))" :prompt "touch")
          (is (nil? (:standing (ops/test-run! sess 'sr.core))))
          (is (true? (:standing (ops/test-run! sess 'sr.core)))))
        (testing "fresh runs it anyway"
          (is (nil? (:standing (ops/test-run! sess 'sr.core :fresh true))))))
      (testing "the external tier stands the same way, on its observation"
        (let [r1 (external/spot-run! sess :only ["sr.core/f-ext-t"])
              r2 (external/spot-run! sess :only ["sr.core/f-ext-t"])]
          (is (= :green (:status r1)) (pr-str r1))
          (is (nil? (:standing r1)))
          (is (true? (:standing r2)) (pr-str r2))
          (is (= :green (:status r2)))))
      (finally (ops/close! sess)))))

(deftest ^:external report-groups-the-changes-by-the-ask-that-made-them
  ;; eval10 p5 ("a rundown of everything that changed and why, from the
  ;; project's own records"): `report` answered with changes rolled up by
  ;; NAMESPACE, each ask snipped to a line, and the agent then read five
  ;; per-namespace histories to attribute forms to asks. The record already
  ;; holds the attribution — a turn-begin opens an ask and the content deltas
  ;; after it are its — so the report says it: per ask, verbatim, what was
  ;; added, changed, deleted and renamed. Newest first, asks that changed
  ;; nothing omitted.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ba.core "(ns ba.core)\n(defn ^:unused-ok h [] 1)\n(defn ^:unused-ok k [] 2)\n")
      (ops/turn-begin! sess :agent "t" :intent "add the f feature")
      (is (nil? (:error (ops/add-form! sess 'ba.core "(defn ^:unused-ok f [] 1)" :prompt "f"))))
      (ops/turn-end! sess :agent "t")
      (ops/turn-begin! sess :agent "t" :intent "k is now g; drop h; f returns 2")
      (is (nil? (:error (ops/edit-replace! sess 'ba.core 'f "(defn ^:unused-ok f [] 2)" :prompt "f is 2"))))
      (is (nil? (:error (ops/delete-form! sess 'ba.core 'h :prompt "drop h"))))
      (is (nil? (:error (ops/rename! sess 'ba.core 'k 'g :prompt "k is g"))))
      (let [by-ask (:by-ask (ops/report sess))]
        (is (= ["k is now g; drop h; f returns 2" "add the f feature"] (mapv :ask by-ask)) (pr-str by-ask))
        (is (= {:added ['ba.core/f]} (select-keys (second by-ask) [:added :changed :deleted :renamed])) (pr-str (second by-ask)))
        (let [latest (first by-ask)]
          (is (= ['ba.core/f] (:changed latest)) (pr-str latest))
          (is (= ['ba.core/h] (:deleted latest)) (pr-str latest))
          (is (= [{:from 'ba.core/k :to 'ba.core/g}] (:renamed latest)) (pr-str latest))
          (is (nil? (:added latest)) "nothing was added in the second ask")))
      (finally (ops/close! sess)))))

(deftest ^:external the-census-is-windowed-by-since
  ;; s20 tracking: query_cost {since <delta>} windowed turns and read
  ;; records but not the per-call census, so a windowed header sat over
  ;; all-time tool totals. A measurement row carries its own timestamp; a
  ;; delta carries :at; rows after the delta are the window.
  (let [dir  (str (java.nio.file.Files/createTempDirectory "cw" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      ;; the db exists once something has been written
      (ops/ingest! sess 'cw.core "(ns cw.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n")
      (ops/record-tool-call! sess {:tool "read" :op "query_source" :start 1 :end 3 :chars 10})
      (Thread/sleep 5)
      (ops/add-form! sess 'cw.core "(defn ^:unused-ok g \"G.\" [] 2)" :prompt "the mark")
      (let [mark (:head (:store @sess))]
        (Thread/sleep 5)
        (ops/record-tool-call! sess {:tool "edit" :op "change" :start 10 :end 12 :chars 5})
        (is (= 2 (count (ops/tool-call-measurements sess))))
        (is (= ["edit"] (mapv :tool (ops/tool-call-measurements sess :since mark)))
            "only the rows after the delta"))
      (finally (ops/close! sess)))))

(deftest ^:external compact-settles-the-open-threads-nobody-will-finish
  ;; On slopp's own store: 138 open threads, each holding a full materialized
  ;; copy of the store — 466,128 element rows against main's 3,539, 1.16 GB
  ;; of a 2.1 GB file. Fork on write removed the cause (a fresh thread holds
  ;; no copy now), and compaction settles what is left: an open thread with
  ;; NOTHING TO RESUME — no un-landed content since its fork — untouched for
  ;; a day, its owner process gone.
  ;;
  ;; But \"the owner process died\" is not \"nobody will finish this\": a
  ;; returning agent picks its thread back up, and its un-landed work IS the
  ;; thread. A thread WITH work stays open however dead its owner. Deltas and
  ;; rows stay either way.
  (let [dir (str (java.nio.file.Files/createTempDirectory "gc" (make-array java.nio.file.attribute.FileAttribute 0)))
        a   (external/open! {:slopp.ops/dir dir})
        _   (ops/ingest! a 'gc.a "(ns gc.a)\n(defn ^:unused-ok fa \"A.\" [] 1)\n")
        b   (external/open! {:slopp.ops/dir dir})
        week-ago (- (System/currentTimeMillis) (* 8 86400000))]
    (try
      ;; b lands its work: it is now on a fresh thread with nothing to resume
      (is (nil? (:error (ops/ingest! b 'gc.core "(ns gc.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n"))))
      (is (nil? (:error (external/done! b :label "land" :agent "b"))))
      ;; c opens on the moved branch and has un-landed work
      (let [c (external/open! {:slopp.ops/dir dir})]
        (try
          (is (nil? (:error (ops/ingest! c 'gc.other "(ns gc.other)\n(defn ^:unused-ok g \"G.\" [] 2)\n")))
              "c's write lands on c's thread")
          (let [conn   (:db @a)
                b-line (slopp.ops.engine/session-line b)
                c-line (slopp.ops.engine/session-line c)
                rows   (fn [line] (:n (jdbc/execute-one! conn ["SELECT COUNT(*) AS n FROM elements WHERE line = ?" line])))
                status (fn [line] (:status (first (filter #(= line (:id %)) (db/lines conn)))))]
            (is (zero? (rows b-line)) "b's fresh thread holds NO copy — fork on write")
            (is (pos? (rows c-line)) "c's thread holds its own view: it wrote")
            (is (pos? (db/unlanded-count conn c-line slopp.read.history/content-ops)) "c has un-landed work")
            (is (zero? (db/unlanded-count conn b-line slopp.read.history/content-ops)) "b has none")
            (testing "live owners: nothing is touched"
              (let [r (external/compact-store! a)]
                (is (zero? (:threads-settled r 0)) (pr-str r))
                (is (= "open" (status b-line)))
                (is (pos? (rows c-line)))))
            (doseq [line [b-line c-line]]
              (jdbc/execute! conn ["UPDATE lines SET owner_pid = 999999, used_at = ? WHERE id = ?" week-ago line]))
            (testing "dead owners: the thread with nothing to resume is settled, its deltas kept"
              (let [r (external/compact-store! a)]
                (is (= 1 (:threads-settled r)) (pr-str r))
                (is (= "abandoned" (status b-line)))
                (is (seq (db/line-deltas conn b-line)) "its deltas are still walkable")))
            (testing "and the thread WITH un-landed work stays open for whoever comes back, however dead its owner"
              (is (= "open" (status c-line)))
              (is (pos? (rows c-line)) "its view — its work — is untouched")))
          (finally (ops/close! c))))
      (finally (ops/close! b) (ops/close! a)))))

(deftest ^:external a-standing-verdict-carries-what-the-fresh-one-did
  ;; eval24 opus, every cell: full_check (green, terse) → full_check {verbose}
  ;; answered the STANDING verdict, which had neither :checked nor a test
  ;; count — so the model went to test_run for the number. The recorded
  ;; verdict keeps the populations and the counts, and hands them back.
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'sv.core :source "(ns sv.core (:require [clojure.test :refer [deftest is]]))\n(defn f \"F.\" [x] x)\n(deftest f-t (is (= 1 (f 1))))\n" :prompt "seed")
      (let [fresh    (external/full-check! sess)
            standing (external/full-check! sess)]
        (is (= :green (:status fresh)) (pr-str (dissoc fresh :rules)))
        (is (:standing standing) (pr-str (keys standing)))
        (is (= (:checked fresh) (:checked standing)) "the populations it examined")
        (is (= (select-keys (:test fresh) [:test :pass :fail :error])
               (select-keys (:test standing) [:test :pass :fail :error]))
            "and the in-image counts")
        (is (= :green (:status standing))))
      (finally (ops/close! sess)))))

(deftest ^:external a-report-carries-each-ask-whole-with-its-deltas-and-the-suite-command
  ;; eval24 opus step 5: \"the report truncated [the ask texts]\" — eight
  ;; history calls to recover four asks, and test_run twice for a number
  ;; the last verdict already held. The handoff quotes the ask, cites the
  ;; deltas under it, and hands over the suite's counts with the command.
  (let [sess (external/open!)
        ask  (str "Back again, two things. First, a question before you change anything: why is"
                  " the fuel surcharge computed off the weight price rather than the whole quote? "
                  (apply str (repeat 12 "I want what this project's own records say, not a guess. ")))]
    (try
      (ops/turn-begin! sess :intent ask)
      (ops/ingest! sess 'rh.core "(ns rh.core (:require [clojure.test :refer [deftest is]]))\n(defn f \"F.\" [x] x)\n(deftest f-t (is (= 1 (f 1))))\n")
      (ops/edit-replace! sess 'rh.core 'f "(defn f \"F!\" [x] x)" :prompt "f, again")
      (external/done! sess :label "rh")
      (let [r   (ops/report sess)
            row (first (filter #(re-find #"Back again" (str (:ask %))) (:by-ask r)))
            ids (into #{} (map :id) (ops/journal sess))]
        (is (some? row) (pr-str (:by-ask r)))
        (is (= ask (:ask row)) "the ask arrives WHOLE — a handoff quotes what was asked")
        (is (seq (:deltas row)) (pr-str row))
        (is (every? ids (:deltas row)) "and cites the change deltas under it")
        (is (number? (get-in r [:suite :tests])) (pr-str (:suite r)))
        (is (re-find #"full_check" (str (get-in r [:suite :command]))) (pr-str (:suite r)))
        (is (= (map :ask (:by-ask r)) (map :ask (:by-ask (ops/report sess :since "start"))))
            "since \"start\" is the lifetime, not a refusal"))
      (finally (ops/close! sess)))))

(deftest ^:external advisories-carried-unchanged-from-the-previous-done-compress
  ;; eval24 opus: the same namespace-purpose rows, each with the same 300
  ;; chars of teaching, on every done of the lifetime — and a model that
  ;; obeys spent a change and a second done per step on them. The first
  ;; done teaches; a row the previous done already carried, unchanged, is a
  ;; count.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ca.core "(ns ca.core)\n(defn ^:unused-ok f \"F.\" [x] x)\n")
      (let [d1 (external/done! sess :label "one")
            rows1 (get-in d1 [:findings :namespace-purpose])]
        (is (= ['ca.core] (mapv :ns rows1)) (pr-str (:findings d1)))
        (is (string? (:teach (first rows1))) "the first time, the teaching rides")
        (ops/edit-replace! sess 'ca.core 'f "(defn ^:unused-ok f \"F!\" [x] x)" :prompt "again")
        (let [d2 (external/done! sess :label "two")]
          (is (nil? (get-in d2 [:findings :namespace-purpose])) (pr-str (:findings d2)))
          (is (= 1 (get-in d2 [:findings :carried-advisories :namespace-purpose])) (pr-str (:findings d2)))
          (is (not (re-find #"states no purpose" (pr-str d2))) "and no teaching rides the second time")))
      (finally (ops/close! sess)))))

(deftest a-marked-require-clause-is-never-wrapped
  ;; the bracket wrapper met the done-point's own `^:side-effect [lib :as r]`
  ;; and wrapped it into `[^:side-effect [lib :as r]]`, losing the require it
  ;; meant to keep. Only a bare lib followed by options is wrapped.
  (is (= "[clojure.set :as set]" (#'ops/bracketed-require "clojure.set :as set")))
  (is (= "clojure.set" (#'ops/bracketed-require "clojure.set")))
  (is (= "[clojure.set :as set]" (#'ops/bracketed-require "[clojure.set :as set]")))
  (is (= "^:side-effect [pz.reg :as r]" (#'ops/bracketed-require "^:side-effect [pz.reg :as r]")))
  (is (= "(quote x)" (#'ops/bracketed-require "(quote x)"))))
