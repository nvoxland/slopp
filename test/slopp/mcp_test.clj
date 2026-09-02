(ns slopp.mcp-test
  "Cover for the WIRE — `slopp.mcp`, the JSON-RPC boundary agents actually
  reach slopp through.

  The distinction from the api-level test namespaces is the whole point: those
  ask whether an operation is correct, this asks whether the protocol surface
  in front of it is. Argument validation, trimming and spooling, refusal
  shapes, the tool schemas matching what the tools accept, turn rotation, and
  what the session ring records — all things an operation can be perfectly
  correct behind.

  Most of it is `^:external`: a wire test wants a real session, and several
  want a second process. The handful of in-image tests here are the pure
  helpers the boundary derives (payload shaping, refusal extraction), and
  those belong in-image precisely because they run ~700x cheaper there."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [slopp.ops :as ops]
            [slopp.mcp :as mcp] [clojure.java.io :as io] [slopp.store :as store] [slopp.store.db :as db] [clojure.java.shell :as sh] [slopp.sync :as sync] [clojure.string :as str] [slopp.mcp.tools :as tools] [slopp.read.query :as query] [slopp.ops.review :as review] [slopp.ops.external :as external] [rewrite-clj.node :as n] [slopp.mcp.smells :as smells] [slopp.api.server :as server] [slopp.http.client :as http.client] [slopp.read.history :as history] [slopp.ops.branch :as branch] [slopp.rules.webapp :as rules.webapp] [slopp.read.telemetry :as telemetry] [slopp.edit :as edit] [slopp.http :as http]))

(deftest ^:external protocol-handshake
  (let [sess (atom {})]
    (testing "initialize returns serverInfo named slopp"
      (let [r (mcp/handle! sess {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})]
        (is (= "slopp" (get-in r [:result :serverInfo :name])))
        (is (contains? (:result r) :protocolVersion))))
    (testing "tools/list returns schemas with MCP-legal names"
      (let [tools (get-in (mcp/handle! sess {:id 2 :method "tools/list"}) [:result :tools])]
        (is (seq tools))
        (is (every? #(re-matches #"[a-zA-Z0-9_-]+" (:name %)) tools))
        ;; D-families: the names are families; the ops are their enums
        (is (contains? (set (map :name tools)) "read"))
        (is (contains? (set (map :name tools)) "edit"))
        (is (some #{"query_source"} (mapcat #(get-in % [:inputSchema :properties :op :enum]) tools)))
        (is (not-any? #{"edit_replace_form"} (mapcat #(get-in % [:inputSchema :properties :op :enum]) tools))
            "the single-form aliases are de-advertised (s8): they dispatch, but no enum names them")))
    (testing "notifications (no id) produce no response"
      (is (nil? (mcp/handle! sess {:method "notifications/initialized"}))))
    (testing "unknown method -> JSON-RPC error"
      (is (= -32601 (get-in (mcp/handle! sess {:id 9 :method "bogus"}) [:error :code]))))))

(defn- call! [sess tool args]
  (get-in (mcp/handle! sess {:id 1 :method "tools/call"
                            :params {:name tool :arguments args}})
          [:result :content 0 :text]))

(deftest ^:external tools-call-end-to-end
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "demo" :source "(ns demo)\n(defn add [x y] (+ x y))\n"})
      (testing "query_source (VFS read)"
        (is (re-find #"defn add" (call! sess "query_source" {:ns "demo" :full true}))))
      (testing "query_eval hits the oracle"
        (is (re-find #"\b5\b" (call! sess "query_eval" {:code "(demo/add 2 3)"}))))
      (testing "edit_replace_form over the wire (JSON round-trip) hot-reloads"
        (let [wire-req (json/generate-string
                        {:jsonrpc "2.0" :id 4 :method "tools/call"
                         :params {:name "edit_replace_form"
                                  :arguments {:ns "demo" :name "add"
                                              :source "(defn add [x y] (* x y))"
                                              :prompt "mul"}}})
              resp (mcp/handle! sess (json/parse-string wire-req true))
              wire-resp (json/parse-string (json/generate-string resp) true)]
          (is (nil? (:error wire-resp)))
          (is (re-find #"\b6\b" (call! sess "query_eval" {:code "(demo/add 2 3)"})))))
      (finally (ops/close! sess)))))

(deftest ^:external help-and-hints                                ; item 3: weak-model guidance
  (let [sess (external/open!)]
    (try
      (testing "the help tool exists (agents invented the name twice)"
        (let [h (call! sess "help" {})]
          (is (re-find #"change" h))
          (is (re-find #"help \{topic\}" h) "the cheat-sheet indexes the chapters")))
      (call! sess "ns_create" {:ns "hint" :source "(ns hint (:require [clojure.test :refer [deftest is]]))\n(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n"})
      (testing "redundant test_runs earn a hint; a write resets the counter"
        (call! sess "test_run" {:ns "hint"})
        (call! sess "test_run" {:ns "hint"})
        (let [r3 (call! sess "test_run" {:ns "hint"})]
          (is (re-find #"rarely needed" r3)))
        (call! sess "edit_replace_form" {:ns "hint" :name "f" :source "(defn f [x] (identity x))"})
        (is (not (re-find #"rarely needed" (call! sess "test_run" {:ns "hint"})))))
      (testing "a hint fires ONCE per session — a fresh streak stays quiet"
        (call! sess "test_run" {:ns "hint"})
        (call! sess "test_run" {:ns "hint"})
        (let [r6 (call! sess "test_run" {:ns "hint"})]
          (is (not (re-find #"rarely needed" r6)))))
      (testing "a write between test_run and done keeps done QUIET (spot-check flow)"
        (call! sess "edit_replace_form" {:ns "hint" :name "f" :source "(defn f [x] x)"})
        (is (not (re-find #"pre-flight" (call! sess "done" {:label "quiet"})))))
      (testing "an ISOLATED run before done stays quiet — it is the milestone gate"
        (call! sess "test_run" {:ns "hint" :external true})
        (is (not (re-find #"pre-flight" (call! sess "done" {:label "gated"})))))
      (testing "an in-image test_run immediately before done earns the redundancy hint"
        (call! sess "test_run" {:ns "hint"})
        (is (re-find #"pre-flight" (call! sess "done" {:label "noisy"}))))
      (finally (ops/close! sess)))))

(deftest ^:external green-responses-are-terse                     ; B1
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "b1" :source "(ns b1 (:require [clojure.test :refer [deftest is]]))\n(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n"})
      (call! sess "test_run" {:ns "b1"})
      (testing "a quiet green edit returns the terse shape"
        (let [r (edn/read-string (call! sess "edit_replace_form"
                                       {:ns "b1" :name "f"
                                        :source "(defn f [x] (identity x))"}))]
          (is (true? (:ok r)))
          (is (nil? (:failures r)))
          (is (< (count (pr-str r)) 160) (pr-str r))))
      (testing ":verbose true forces the full shape"
        (let [r (edn/read-string (call! sess "edit_replace_form"
                                       {:ns "b1" :name "f"
                                        :source "(defn f [x] x)" :verbose true}))]
          (is (map? (:delta r)))
          (is (map? (:test r)))))
      (testing "a red edit returns full detail incl. :failures"
        (let [r (edn/read-string (call! sess "edit_replace_form"
                                       {:ns "b1" :name "f"
                                        :source "(defn f [x] (inc x))"}))]
          (is (seq (get-in r [:test :failures])))))
      (finally (ops/close! sess)))))

(deftest parse-call-args-shapes
  (testing "nil/blank → {}"
    (is (= {} (mcp/parse-call-args nil)))
    (is (= {} (mcp/parse-call-args "  "))))
  (testing "JSON and EDN both parse, keys keywordized"
    (is (= {:ns "demo" :limit 5} (mcp/parse-call-args "{\"ns\":\"demo\",\"limit\":5}")))
    (is (= {:ns "demo" :limit 5} (mcp/parse-call-args "{:ns \"demo\" :limit 5}"))))
  (testing "@file reads the file first"
    (let [f (java.io.File/createTempFile "callargs" ".json")]
      (spit f "{\"ns\":\"demo\"}")
      (is (= {:ns "demo"} (mcp/parse-call-args (str "@" f))))))
  (testing "non-map input is a clear error"
    (is (thrown-with-msg? Exception #"JSON or EDN map"
                          (mcp/parse-call-args "[1 2 3]")))))

(deftest ^:external one-shot-call
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-call" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (testing "a query works with no MCP connection and returns the wire shape"
      (let [r (mcp/call! dir "query_project" {})]
        (is (not (:isError r)))
        (is (string? (get-in r [:content 0 :text])))))
    (testing "writes stay turn-gated: no open turn → tool error, not a write"
      (let [r (mcp/call! dir "ns_create" {:ns "demo" :source "(ns demo)"
                                          :agent "probe"})]
        (is (:isError r))
        (is (re-find #"turn" (get-in r [:content 0 :text])))))
    (testing "turn_begin in one call!, the write in the NEXT (turns are durable)"
      (mcp/call! dir "turn_begin" {:agent "probe" :intent "one-shot test"})
      (let [r (mcp/call! dir "ns_create" {:ns "demo"
                                          :source "(ns demo)\n(defn f [x] x)\n"
                                          :agent "probe"})]
        (is (not (:isError r)) (get-in r [:content 0 :text]))))))

(deftest ^:external pending-intent-opens-the-turn
  ;; the plugin's UserPromptSubmit hook drops {session-id, prompt} JSON in
  ;; .slopp/pending-intent; the turn gate opens the turn from it and the
  ;; session ADOPTS the harness session id as its identity — no agent
  ;; field anywhere on the wire
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-turnhook"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (swap! sess assoc :require-turns? true)
      ;; open! no longer creates .slopp/ just by serving — the store is
      ;; materialized by the first write. The real hook only writes
      ;; pending-intent into an already-adopted dir, so make the dir it
      ;; would have found rather than relying on open! to leave one.
      (io/make-parents (io/file dir ".slopp" "pending-intent"))
      (spit (io/file dir ".slopp" "pending-intent")
            "{\"session-id\":\"sess-abc123\",\"prompt\":\"add a widget feature\"}")
      (let [r (edn/read-string
               (call! sess "ns_create" {:ns "pi.core"
                                       :source "(ns pi.core)\n(defn f [] 1)\n"}))]
        (is (nil? (:error r)) (pr-str r)))
      (is (false? (.exists (io/file dir ".slopp" "pending-intent"))))
      (testing "the session adopted the harness id; the write is stamped with it"
        (is (= "sess-abc123" (:agent-id @sess)))
        (is (= "sess-abc123"
               (->> (ops/journal sess)
                    (filter #(= :ingest (:op %)))
                    first :agent))))
      (testing "the turn carries the verbatim prompt"
        (is (seq (history/query-search-history (ops/with-history sess) "add a widget feature"))))
      (testing "with no pending intent and no turn, the gate still refuses"
        (call! sess "turn_end" {})
        (let [r (call! sess "edit_add_form" {:ns "pi.core"
                                            :source "(defn g [] 2)"})]
          (is (re-find #"no open turn" r))))
      (finally (ops/close! sess)))))

(deftest ^:external two-sessions-never-merge-episodes
  ;; the P4 invariant, now free of wire labels: two sessions on ONE store
  ;; get DISTINCT generated identities, so episode_revert scopes to the
  ;; session that calls it (under a constant label these episodes MERGED)
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-iso"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        sa  (external/open! {:slopp.ops/dir dir})
        sb  (external/open! {:slopp.ops/dir dir})]
    (try
      (is (not= (:agent-id @sa) (:agent-id @sb)))
      (call! sa "ns_create" {:ns "iso.a" :source "(ns iso.a)\n(defn fa [] 1)\n"})
      (call! sb "ns_create" {:ns "iso.b" :source "(ns iso.b)\n(defn fb [] 2)\n"})
      (let [r (edn/read-string (call! sb "episode_revert" {}))]
        (is (nil? (:error r)) (pr-str r)))
      (testing "B's work is gone, A's survives"
        (is (not (re-find #"defn fb" (call! sb "query_source" {:targets [{:ns "iso.b" :name "fb"}]}))))
        (is (re-find #"defn fa" (call! sa "query_source" {:targets [{:ns "iso.a" :name "fa"}]}))))
      (finally (ops/close! sa) (ops/close! sb)))))

(deftest ^:external terse-results-carry-forms
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "tf.core" :source "(ns tf.core)\n(defn f [x] x)\n"})
      (testing "replace names its form"
        (is (re-find #":forms \[\"tf.core/f\"\]"
                     (call! sess "edit_replace_form" {:ns "tf.core" :name "f"
                                                     :source "(defn f [x] (identity x))"}))))
      (finally (ops/close! sess)))))

(deftest ^:external trimmed-responses-spool-the-full-version
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "sp.core" :source "(ns sp.core)\n(defn f [] 1)\n"})
      (testing "a response over the size gate is trimmed, and retrieval completes
                it WITHOUT re-buying the head (the remainder diet, s15: re-buys
                averaged 18.8k chars across the s14 grid)"
        (let [r  (call! sess "query_eval" {:code "(apply str (repeat 9000 \"x\"))"})
              id (second (re-find #"query_detail \{:id \"(r\d+)\"\}" r))]
          (is (some? id) r)
          (let [rest* (call! sess "query_detail" {:id id})]
            (is (re-find #"REMAINDER" rest*) (subs rest* 0 (min 120 (count rest*))))
            (is (< (count rest*) 8000) "the spooled half is smaller than the whole")
            (is (re-find #"x{500}" rest*) "the withheld tail is all there")
            (is (not (re-find #"query_detail \{:id" rest*))))))
      (testing "giant failure strings never reach the agent whole
                (upstream capture truncates actuals; the text! heuristic
                covers the other fields)"
        (let [r (call! sess "ns_create"
                      {:ns "sp.red"
                       :source "(ns sp.red (:require [clojure.test :refer [deftest is]]))\n(deftest big-t (is (= \"a\" (apply str (repeat 3000 \"z\")))))\n"})]
          (is (re-find #"fail 1" r))
          (is (not (re-find #"z{1000}" r)))))
      (testing "an unknown id is an honest error"
        (is (re-find #"no spooled response" (call! sess "query_detail" {:id "r999"}))))
      (finally (ops/close! sess)))))

(deftest ^:external untested-writes-stay-terse-and-honest
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "ut.core" :source "(ns ut.core)\n(defn f [x] x)\n(defn g [x] x)\n"})
      (call! sess "ns_create" {:ns "ut.core-test" :source "(ns ut.core-test (:require [clojure.test :refer [deftest is]] [ut.core :as c]))\n(deftest f-t (is (= 1 (c/f 1))))\n"})
      (call! sess "test_run" {:ns "ut.core-test"})
      (testing "an untested green write is terse — flagged, no source echo"
        (let [r (call! sess "edit_replace_form" {:ns "ut.core" :name "g"
                                                :source "(defn g [x] (identity x))"})]
          (is (re-find #":ok true" r) r)
          (is (re-find #":untested true" r) r)
          (is (not (re-find #"identity" r)) r)
          (testing "…but the covering namespace's suite still RUNS (graph fallback)"
            ;; no test covers `g` specifically, so :untested holds. It used to
            ;; also verify NOTHING, because the fallback ran tests in ut.core
            ;; — a production ns holding none. It now runs ut.core-test, found
            ;; through the require graph. Emptiness itself is asserted by
            ;; unverified-says-why-it-verified-nothing.
            (is (re-find #":status :green" r) r)
            (is (not (re-find #":coverage :none" r)) r))))
      (testing "a new deftest is not 'untested' — it IS a test"
        (call! sess "edit_add_form" {:ns "ut.core-test"
                                    :source "(deftest g-t (is (= 2 (c/g 2))))"})
        (let [r (call! sess "edit_replace_form" {:ns "ut.core-test" :name "g-t"
                                                :source "(deftest g-t (is (= 3 (c/g 3))))"})]
          (is (not (re-find #":untested" r)) r)))
      (finally (ops/close! sess)))))

(deftest ^:external rename-names-leftover-prose-mentions
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create"
            {:ns "pm.core"
             :source (str "(ns pm.core)\n"
                          "(defn bulk-rate [n] (if (>= n 10) 0.1 0.0))\n"
                          "(defn describe\n  \"Applies the bulk-rate tier.\"\n  [n]\n"
                          "  (str \"bulk-rate applies: \" (bulk-rate n)))\n")})
      (testing "the rename result points at docstring/string mentions of the old name (Q11)"
        (let [r (call! sess "edit_rename" {:ns "pm.core" :from "bulk-rate" :to "volume-rate"})]
          (is (re-find #":mentions" r) r)
          (is (re-find #":ns pm.core, :form describe" r) r)))
      (testing "a clean rename carries no :mentions"
        (call! sess "edit_replace_form"
              {:ns "pm.core" :name "describe"
               :source "(defn describe [n] (str \"tier: \" (volume-rate n)))"})
        (let [r (call! sess "edit_rename" {:ns "pm.core" :from "volume-rate" :to "tier-rate"})]
          (is (not (re-find #":mentions" r)) r)))
      (finally (ops/close! sess)))))

(deftest ^:external milestones-publish-themselves
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-pub" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" dir)
        _    (sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t"
                    "commit" "--allow-empty" "-m" "root")
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (call! sess "ns_create" {:ns "pub.core" :source "(ns pub.core)\n(defn ^:unused-ok f [x] x)\n"})
      (testing "a milestone mirrors into LOCAL git as slopp/<store-branch> (user decision 2026-07-14)"
        (let [r (call! sess "commit_point" {:label "first"})]
          (is (re-find #":published" r) r)
          (is (re-find #"slopp/main" r) r))
        (let [head (:out (sh/sh "git" "-C" dir "rev-parse" "refs/heads/slopp/main"))]
          (is (= 40 (count (clojure.string/trim head))) head)))
      (testing "no REMOTE is touched or saved — remote publishing stays explicit"
        (is (nil? (db/get-meta (:db @sess) "git-remote"))))
      (finally (ops/close! sess)))))

(deftest ^:external commits-prove-git-alignment
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-align" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" dir)
        _    (sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t"
                    "commit" "--allow-empty" "-m" "root")
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (call! sess "ns_create" {:ns "al.core" :source "(ns al.core)\n(defn ^:unused-ok f [x] x)\n"})
      (call! sess "commit_point" {:label "first"})
      (testing "query_commits carries the alignment PROOF against the local mirror (Q12)"
        (let [r (call! sess "query_commits" {})]
          (is (re-find #":aligned true" r) r)
          (is (re-find #":branch-head" r) r)
          (is (re-find #"no worktree" r) r)))
      (finally (ops/close! sess)))))

(deftest ^:external whole-ns-source-is-outline-by-default
  ;; for a LARGE namespace: a small one (≤6k chars) is one read and comes
  ;; back whole (`a-small-namespace-is-one-read`); the dump stays opt-in
  ;; only where dumping would cost more than the outline it replaces
  (let [sess (external/open!)
        pad  (apply str (for [i (range 80)]
                          (str "(defn ^:unused-ok p" i " \"Padding form " i ", long enough that the whole namespace is over the size where an outline is the answer.\" [x] (+ x " i "))\n")))]
    (try
      (call! sess "ns_create" {:ns "gt.core" :source (str "(ns gt.core)\n(defn f [x] (* x 2))\n(defn g [x] (+ x 1))\n" pad)})
      (testing "a bare {ns} read of a big namespace returns the outline + the way in, NOT the dump"
        (let [r (call! sess "query_source" {:ns "gt.core"})]
          (is (not (re-find #"\(\* x 2\)" r)) r)
          (is (re-find #":whole false" r) r)
          (is (re-find #"f" r) r)
          (is (re-find #"full" r) r)))
      (testing "named targets stay a cheap direct read"
        (is (re-find #"\(\* x 2\)"
                     (call! sess "query_source" {:targets [{:ns "gt.core" :name "f"}]}))))
      (testing "full: true is the explicit whole-namespace dump"
        (is (re-find #"\(\* x 2\)" (call! sess "query_source" {:ns "gt.core" :full true}))))
      (finally (ops/close! sess)))))

(deftest ^:external rename-sweep-is-one-intent
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create"
            {:ns "sw.zone"
             :source (str "(ns sw.zone (:require [clojure.test :refer [deftest is]]))\n"
                          "(def zone-fees {1 500, 2 900})\n"
                          "(defn zone-fee \"The zone fee table lookup.\" [z] (get zone-fees z 0))\n"
                          "(deftest zone-t (is (= 500 (zone-fee 1))))\n")})
      (call! sess "module_dep" {:from "sw.core" :to "sw.zone" :prompt "fixture edge"})
      (call! sess "ns_create"
            {:ns "sw.core"
             :source (str "(ns sw.core (:require [clojure.test :refer [deftest is]] [sw.zone :as zone]))\n"
                          "(defn total \"Base plus the zone fee.\" [z] (+ 100 (zone/zone-fee z)))\n"
                          "(deftest total-t (is (= 600 (total 1))))\n")})
      (testing "one call sweeps namespaces, vars, keys, and prose (Q14)"
        (let [r (call! sess "rename_sweep" {:from "zone" :to "region"})]
          (is (re-find #":renamed-namespaces" r) r)
          (is (not (re-find #":error" r)) r)))
      (testing "the sweep is total"
        (is (= "[]" (call! sess "query_search" {:pattern "zone"}))))
      (testing "behavior survives under the new names"
        (is (re-find #"600" (call! sess "query_eval" {:code "(sw.core/total 1)"})))
        (is (re-find #"500" (call! sess "query_eval" {:code "(sw.region/region-fee 1)"}))))
      (finally (ops/close! sess)))))

(deftest ^:external repeated-reads-are-free
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create"
            {:ns "tk.core"
             ;; big enough to be an OUTLINE — a small namespace is one read
             ;; (its source rides), and this test is about the outline's stub
             :source (apply str "(ns tk.core)\n"
                            (for [i (range 1 91)]
                              (str "(defn f" i " \"Form " i ", padded so the namespace is long enough that the outline is the answer.\" [x] (+ x " i "))\n")))})
      (testing "an identical re-read returns an :already-sent stub, not the payload"
        (let [a (call! sess "query_source" {:ns "tk.core"})
              b (call! sess "query_source" {:ns "tk.core"})]
          (is (re-find #":outline" a) a)
          (is (re-find #":already-sent true" b) b)
          (is (< (count b) (count a)))))
      (testing "a body edit leaves the OUTLINE honestly identical — and the stub says so"
        ;; THE measured trap, and the reason the key is not called
        ;; `:unchanged`. An outline names forms, not their source, so a body
        ;; edit cannot move it. An agent read `:unchanged` as "your edit did
        ;; not apply", re-applied it on top of itself, stacked a duplicate
        ;; malli key and 500'd a live endpoint. The payload hash was right
        ;; about the view the whole time; the KEY made a claim about the store
        ;; that only the reader could have made about themselves.
        (call! sess "edit_replace_form" {:ns "tk.core" :name "f1"
                                        :source "(defn f1 [x] (* x 9))"})
        (let [b (call! sess "query_source" {:ns "tk.core"})]
          (is (re-find #":already-sent true" b) b)
          (is (re-find #"NOT whether the store changed" b)
              (str "the stub has to say whose fact this is, or it reads as"
                   " \"your write did not land\": " b))))
      (testing "a change the view can SEE invalidates it"
        (call! sess "edit_add_form" {:ns "tk.core" :source "(defn g [x] x)"})
        (is (re-find #":outline" (call! sess "query_source" {:ns "tk.core"}))))
      (finally (ops/close! sess)))))

(deftest ^:external usage-smells-hint-once
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "sm.a" :source "(ns sm.a)\n(defn f [x] x)\n(defn g [x] x)\n"})
      (call! sess "ns_create" {:ns "sm.b" :source "(ns sm.b)\n(defn h [x] x)\n"})
      (testing "a second whole-namespace dump earns the slice hint, ONCE"
        (call! sess "query_source" {:ns "sm.a" :full true})
        (let [r2 (call! sess "query_source" {:ns "sm.b" :full true})]
          (is (re-find #"query_slice" r2) r2))
        (call! sess "ns_create" {:ns "sm.c" :source "(ns sm.c)\n(defn i [x] x)\n"})
        (let [r3 (call! sess "query_source" {:ns "sm.c" :full true})]
          (is (not (re-find #"query_slice" r3)) r3)))
      (testing "a rename streak earns the sweep hint"
        (call! sess "edit_rename" {:ns "sm.a" :from "f" :to "f2"})
        (let [r (call! sess "edit_rename" {:ns "sm.a" :from "g" :to "g2"})]
          (is (re-find #"rename_sweep" r) r)))
      (finally (ops/close! sess)))))

(deftest ^:external one-off-pushes-keep-the-default-remote
  (let [dir   (str (java.nio.file.Files/createTempDirectory
                    "slopp-oop" (make-array java.nio.file.attribute.FileAttribute 0)))
        barea (str (java.nio.file.Files/createTempDirectory
                    "slopp-oop-a" (make-array java.nio.file.attribute.FileAttribute 0)))
        bareb (str (java.nio.file.Files/createTempDirectory
                    "slopp-oop-b" (make-array java.nio.file.attribute.FileAttribute 0)))
        _     (sh/sh "git" "init" "--bare" barea)
        _     (sh/sh "git" "init" "--bare" bareb)
        sess  (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'pr.core "(ns pr.core)\n(defn ^:unused-ok f [x] x)\n")
      (external/commit-point! sess "seed" :agent "t")
      (testing "the FIRST url is saved as the default"
        (is (nil? (:error (sync/push! dir :url barea))))
        (is (= barea (db/get-meta (:db @sess) "git-remote"))))
      (testing "a one-off push elsewhere succeeds but the default STAYS (user regression)"
        (let [r (sync/push! dir :url bareb)]
          (is (nil? (:error r)) (pr-str r))
          (is (= barea (db/get-meta (:db @sess) "git-remote")))
          (is (= barea (str (:default-remote r))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external mirror-push-and-pull-sync-slopp-branches
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-mir" (make-array java.nio.file.attribute.FileAttribute 0)))
        bare (str (java.nio.file.Files/createTempDirectory
                   "slopp-mir-remote" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" dir)
        _    (sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t"
                    "commit" "--allow-empty" "-m" "root")
        _    (sh/sh "git" "init" "--bare" bare)
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (call! sess "ns_create" {:ns "mr.core" :source "(ns mr.core)\n(defn ^:unused-ok f [x] x)\n"})
      (call! sess "commit_point" {:label "first"})
      (testing "git_push mirrors local slopp/* to the remote (and saves the first url)"
        (let [r (call! sess "git_push" {:branches ["main"] :url bare})]
          (is (re-find #":mirrored" r) r))
        (is (re-find #"refs/heads/slopp/main"
                     (:out (sh/sh "git" "ls-remote" "--heads" bare))))
        (is (= bare (db/get-meta (:db @sess) "git-remote"))))
      (testing "git_pull brings the mirror into another clone (auto-import keys on slopp/*)"
        (let [dir2 (str (java.nio.file.Files/createTempDirectory
                         "slopp-mir2" (make-array java.nio.file.attribute.FileAttribute 0)))]
          (sh/sh "git" "clone" bare dir2)
          (sh/sh "git" "-C" dir2 "checkout" "-q" "-b" "work")
          (is (some? (sync/maybe-auto-import! dir2)) "marker must accept slopp/main")
          (let [s2 (external/open! {:slopp.ops/dir dir2})]
            (try
              (call! s2 "ns_create" {:ns "mr.extra" :source "(ns mr.extra)\n(defn ^:unused-ok g [x] x)\n"})
              (let [r (call! s2 "git_pull" {:branches ["main"] :url bare})]
                (is (re-find #":pulled" r) r)
                (is (re-find #"slopp/main" r) r))
              (finally (ops/close! s2))))))
      (finally (ops/close! sess)))
    (testing "a FILELESS store (no .git) still publishes — the projection goes directly"
      (let [d2   (str (java.nio.file.Files/createTempDirectory
                       "slopp-nogit" (make-array java.nio.file.attribute.FileAttribute 0)))
            bare2 (str (java.nio.file.Files/createTempDirectory
                        "slopp-nogit-remote" (make-array java.nio.file.attribute.FileAttribute 0)))
            _    (sh/sh "git" "init" "--bare" bare2)
            s3   (external/open! {:slopp.ops/dir d2})]
        (try
          (call! s3 "ns_create" {:ns "ng.core" :source "(ns ng.core)\n(defn ^:unused-ok f [x] x)\n"})
          (let [r (call! s3 "commit_point" {:label "no git here"})]
            (is (re-find #":commit" r) r)
            (is (not (re-find #":published" r)) r))
          (is (re-find #"url" (call! s3 "git_push" {})) "no remote: helpful error names :url")
          (let [r (call! s3 "git_push" {:url bare2})]
            (is (re-find #":pushed" r) r))
          (is (re-find #"refs/heads/slopp/main"
                       (:out (sh/sh "git" "ls-remote" "--heads" bare2))))
          (finally (ops/close! s3)))))))

(deftest ^:external red-first-rides-the-wire
  ;; the api carried :red-first but the wire's select-keys dropped it —
  ;; an agent would never have seen WHY its spec landed red
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "rw.core"
                              :source "(ns rw.core)\n(defn seed \"S.\" [x] x)\n"})
      (call! sess "ns_create"
            {:ns "rw.core-test"
             :source (str "(ns rw.core-test (:require [rw.core :as c]\n"
                          "                           [clojure.test :refer [deftest is]]))\n"
                          "(deftest seed-t (is (= 1 (c/seed 1))))\n")})
      (let [r (call! sess "edit_add_form"
                    {:ns "rw.core-test"
                     :source "(deftest dbl-t (is (= 4 (c/dbl 2))))"
                     :prompt "red first over the wire"})]
        (is (re-find #":red-first \[rw\.core/dbl\]" r) r)
        (is (re-find #"stubbed in-image" r) r))
      (finally (ops/close! sess)))))

(deftest ^:external read-tools-declare-readonly-on-the-wire
  ;; MCP readOnlyHint: without it, plan-mode clients must treat every tool
  ;; as potentially mutating and prompt — even for query_source
  (let [sess (external/open!)]
    (try
      (let [tools   (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                            [:result :tools])
            by-name (into {} (map (juxt :name identity)) tools)]
        ;; D-families: the hint rides a family only when EVERY op in it is read-only
        (is (true? (get-in by-name ["read" :annotations :readOnlyHint])))
        (is (true? (get-in by-name ["eval" :annotations :readOnlyHint]))
            "the oracle is observe-only by gate — clients may trust it")
        (is (true? (get-in by-name ["orient" :annotations :readOnlyHint])))
        (is (nil? (get-in by-name ["edit" :annotations]))
            "writes carry NO read-only claim")
        (is (nil? (get-in by-name ["declare" :annotations]))))
      (finally (ops/close! sess)))))

(deftest ^:external the-wire-speaks-done-not-groups
  (let [sess (external/open!)]
    (try
      (let [names (into #{} (map :name)
                        (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                                [:result :tools]))]
        (is (contains? names "done"))
        ;; episodes are still inferred — done is the completeness judgement. A
        ;; GROUP is a smaller thing: one intent's steps as one verified write
        ;; (see an-intent-lands-as-one-verified-group)
        (is (contains? names "edit") "the edit family indexes edit_group (D-families)")
        (is (not (contains? names "checkpoint"))))
      (finally (ops/close! sess)))))

(deftest ^:external test-run-wire-guards-the-whole-suite
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "tg.core"
                              :source (str "(ns tg.core (:require [clojure.test :refer [deftest is]]))\n"
                                           "(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n")})
      (testing "bare test_run gives GUIDANCE, does not silently run everything"
        (let [r (call! sess "test_run" {})]
          (is (re-find #":guidance" r) r)
          (is (re-find #"done runs the affected" r))
          (is (not (re-find #":pass" r)) "no suite actually ran")))
      (testing "a named spot-check runs"
        (is (re-find #":pass" (call! sess "test_run" {:ns "tg.core"}))))
      (testing "all:true runs the in-image suite AND warns done covers it"
        (let [r (call! sess "test_run" {:all true})]
          (is (re-find #":pass" r) r)
          (is (re-find #"rarely needed" r))))
      (finally (ops/close! sess)))))

(deftest ^:external review-scan-is-on-the-wire-and-read-only
  (let [sess (external/open!)]
    (try
      (let [tools   (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                            [:result :tools])
            by-name (into {} (map (juxt :name identity)) tools)]
        (is (some #{"review_scan"} (get-in by-name ["verify" :inputSchema :properties :op :enum]))
            "indexed by the verify family (D-families)")
        (is (contains? tools/read-only-tools "review_scan")
            "read-only in the registry; the verify family mixes reads and writes, so the hint rides the op, not the family"))
      (call! sess "ns_create" {:ns "rw.io" :source "(ns rw.io)\n(defn zap! [x] (spit \"/dev/null\" x))\n"})
      (let [r (call! sess "review_scan" {})]
        (is (re-find #":flagged" r) r)
        (is (re-find #"rw.io/zap!" r) "the effectful undocumented fn is flagged"))
      (finally (ops/close! sess)))))

(deftest tool-registry-changes-notify-the-client
  ;; a live reload can rename/add tools (edit_move_forms replaced
  ;; edit_extract_ns mid-session and no client could see it) — the server
  ;; must declare tools.listChanged and emit the notification when the
  ;; registry drifts from what it last advertised.
  (let [sess (atom {})]
    (testing "the capability is declared"
      (is (true? (get-in (mcp/handle! sess {:id 1 :method "initialize"})
                         [:result :capabilities :tools :listChanged]))))
    (testing "no baseline advertised → nothing to invalidate"
      (is (nil? (#'mcp/tools-note! sess))))
    (testing "tools/list records the advertised baseline"
      (mcp/handle! sess {:id 2 :method "tools/list"})
      (is (some? (:slopp.mcp/tools-hash @sess)))
      (is (nil? (#'mcp/tools-note! sess)) "freshly advertised → current"))
    (testing "a drifted registry emits the notification, once"
      (swap! sess assoc :slopp.mcp/tools-hash -1)
      (let [n (#'mcp/tools-note! sess)]
        (is (= "notifications/tools/list_changed" (:method n)))
        (is (nil? (:id n)) "a notification carries no id"))
      (is (nil? (#'mcp/tools-note! sess)) "baseline updated after emitting"))))

(deftest query-store-rides-the-wire-read-only
  (is (some #(= "query_store" (:name %)) tools/registry)
      "the store oracle is a tool")
  (is (contains? @#'tools/read-only-tools "query_store")
      "plan mode may call it without prompts"))

(deftest the-boundary-refuses-file-line-coordinates
  ;; agents NEVER think in files: no agent-facing response may carry a
  ;; source file:line coordinate or a :row/:col key. The strict-boundary
  ;; audit (on across the wire test suite) turns that invariant into a
  ;; throw, so any tool — current or future — that leaks a coordinate
  ;; fails a test the moment it is exercised.
  (testing "the scanner catches coordinates and :row/:col keys, spares clean data"
    (is (#'mcp/boundary-leak {:error "boom at (foo/bar.clj:12:3)"}))
    (is (#'mcp/boundary-leak {:lint [{:type :redundant-do :row 5 :col 2}]}))
    (is (#'mcp/boundary-leak ["ok" {:at "(defn f [] (g))" :nested {:col 1}}]))
    (is (nil? (#'mcp/boundary-leak {:form 'a.b/c :at "(defn c [] (d))"
                                    :error "No matching method"})))
    (is (nil? (#'mcp/boundary-leak {:built "/tmp/build-xyz/src/a.clj"}))
        "a bare build path is not a coordinate"))
  (testing "text! throws under the audit when a response leaks"
    (reset! @#'mcp/strict-boundary? true)
    (try
      (is (thrown-with-msg? Exception #"boundary leak"
                            (#'mcp/text! {:error "at (x/y.clj:9)"})))
      (is (map? (#'mcp/text! {:form 'a.b/c :at "(defn c [])"})) "clean passes")
      (finally (reset! @#'mcp/strict-boundary? false)))))

(use-fixtures :once
  (fn [run]
    (reset! @#'mcp/strict-boundary? true)
    (try (run) (finally (reset! @#'mcp/strict-boundary? false)))))

(deftest ^:external a-compile-failure-crosses-the-wire-anchored
  ;; drives a real compile error THROUGH the wire under the boundary audit:
  ;; the response must anchor (form + snippet) and carry NO coordinate —
  ;; the audit would throw otherwise, so this pins the compile-error path.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "wce.core" :source "(ns wce.core)\n(defn f [x] x)\n"})
      (let [r (call! sess "edit_replace_form"
                    {:ns "wce.core" :name "f"
                     :source "(defn f [x] (String/noSuchStaticThing x))"})]
        (is (re-find #"wce\.core/f" r) "the owning form is named")
        (is (re-find #"noSuchStaticThing" r) "a match-ready snippet rides")
        (is (not (re-find #"\.clj:\d" r)) "no file:line in the wire text"))
      (finally (ops/close! sess)))))

(deftest ^:external module-purity-rides-the-wire
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create"
            {:ns "wcore" :source "(ns wcore)\n(defn add \"A.\" [x y] (+ x y))\n"})
      (testing "module_purity declares a tier on the wire"
        (let [r (call! sess "module_purity" {:module "wcore" :tier "pure"
                                            :prompt "core stays pure"})]
          (is (re-find #":pure" r) r)
          (is (not (re-find #":error" r)) r)))
      (testing "an effectful write into the pure module is refused on the wire"
        (let [r (call! sess "edit_add_form"
                      {:ns "wcore" :source "(defn tick! \"T.\" [a] (swap! a inc))"
                       :prompt "mutation"})]
          (is (re-find #"functional-core" r) r)))
      (finally (ops/close! sess)))))

(deftest boundary-leak-tolerates-non-keyword-keyed-maps
  ;; a sorted-map with STRING keys: (contains? v :row)/(get v :row) compares the
  ;; probe keyword against the string keys via the tree comparator and throws
  ;; "String cannot be cast to Keyword" — boundary-leak must not (D9 found it in
  ;; module_purity's :tiers result).
  (is (nil? (#'mcp/boundary-leak {:tiers (into (sorted-map) {"a.b" :pure})})))
  ;; and it still catches a genuine coordinate leak
  (is (re-find #"row/col" (str (#'mcp/boundary-leak {:row 5 :col 2}))))
  (is (re-find #"\.clj" (str (#'mcp/boundary-leak {:at "foo.clj:42"})))))

(deftest ^:external source-arg-friction
  ;; The de-advertised aliases have no schema, so the unknown-argument gate
  ;; no longer runs for them — the `src` helper is the guard now, and its
  ;; refusal names the bad key and the right one, which is what this test
  ;; actually cares about: a misnamed source arg must never surface as a
  ;; paren/parse error.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "sa" :source "(ns sa)\n(defn f [x] x)\n"})
      (testing "a misnamed :new_source is refused, naming the bad key and :source — not a paren/parse error"
        (let [r (call! sess "edit_replace_form"
                      {:ns "sa" :name "f" :new_source "(defn f [x] (inc x))"})]
          (is (re-find #"missing required argument :source" r))
          (is (re-find #":new_source" r))
          (is (re-find #"the form source goes in :source" r) "the refusal points at :source")
          (is (not (re-find #"got 0" r)))))
      (testing "a genuinely missing source is a clear message too"
        (let [r (call! sess "edit_replace_form" {:ns "sa" :name "f"})]
          (is (re-find #"missing required argument :source" r) r)))
      (testing "edit_add_form guards its source arg the same way"
        (let [r (call! sess "edit_add_form" {:ns "sa" :new_source "(defn g [x] x)"})]
          (is (re-find #"missing required argument :source" r))
          (is (re-find #":new_source" r))))
      (testing "a correctly-named source still lands"
        (let [r (edn/read-string
                 (call! sess "edit_replace_form"
                       {:ns "sa" :name "f" :source "(defn f \"D.\" [x] (inc x))"}))]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external query-vocabulary-rides-the-wire
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create"
            {:ns "voc"
             :source (str "(ns voc)\n"
                          "(defn a [m] {:user/email (:x m)})\n"
                          "(defn b [m] {:user/email (:y m) :order/id 1})\n")})
      (let [r (edn/read-string (call! sess "query_vocabulary" {}))]
        (is (= 2 (:count r)) (pr-str r))
        (is (= {:kw :user/email :uses 2} (first (:attributes r))) (pr-str r)))
      (testing "ns narrows by keyword namespace"
        (let [r (edn/read-string (call! sess "query_vocabulary" {:ns "order"}))]
          (is (= [:order/id] (mapv :kw (:attributes r))) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external query-rules-rides-the-wire
  (let [sess (external/open!)]
    (try
      (let [raw (call! sess "query_rules" {})
            rs  (edn/read-string raw)]
        (is (>= (count rs) 9) (pr-str rs))
        (is (= :refuse (:severity (first (filter #(= :schema-refusal (:rule %)) rs))))
            (pr-str rs))

        (testing "what rides the wire is a PREFIX, and it says so"
          ;; The catalog is ~23k characters against `text!`'s 8000-char gate,
          ;; so `fit-payload` drops whole rows and roughly a third of them
          ;; arrive. That degradation is deliberate and the response announces
          ;; it — but in a trailing LINE, outside the edn, which anything
          ;; parsing the response reads straight past.
          ;;
          ;; Asserted rather than worked around, because this test used to name
          ;; `:schema-drift` and pass by luck: it sat inside the cut. Three cli
          ;; rules joined the catalog, the cut moved, and a test whose subject
          ;; is severity started failing on a rule's ABSENCE. A named rule is a
          ;; bet on where 7800 characters happen to land.
          (is (re-find #"\d+ of \d+ shown" raw)
              (str "no trim marker, so either the gate moved or the catalog"
                   " shrank — and a reader is now entitled to believe this is"
                   " every rule: " (subs raw (max 0 (- (count raw) 200)))))
          (let [[_ kept total] (re-find #"(\d+) of (\d+) shown" raw)]
            (is (< (parse-long kept) (parse-long total))
                "a marker claiming everything was shown would be worse than none")))

        (testing "a per-store severity override is reflected"
          ;; the subject is picked OUT of what arrived rather than named ahead
          ;; of time — the point is that an override rides the wire, and which
          ;; rule carries it is incidental
          (let ;; NOT already advisory, and that qualifier is load-bearing: dialing a
          ;; rule to the severity it already has leaves the payload
          ;; byte-identical, `told!`'s knowledge differential answers with the
          ;; :unchanged stub, and filtering a MAP for a :rule yields nothing —
          ;; so this reads as "the override was ignored" when what happened is
          ;; "nothing changed, and the wire said so".
          [victim (:rule (first (remove #(= :advisory (:severity %)) rs)))]
            (is (some? victim) (pr-str rs))
            (ops/config-file! sess "rules" :key (name victim) :value "advisory"
                              :prompt (str "dial " victim " down"))
            (let [rs2 (edn/read-string (call! sess "query_rules" {}))
                  row (first (filter #(= victim (:rule %)) rs2))]
              (is (= :advisory (:severity row)) (pr-str row))))))
      (finally (ops/close! sess)))))

(deftest ^:external query-rule-telemetry-rides-the-wire
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "tl" :source "(ns tl)\n(defn seed \"S.\" [x] x)\n"})
      (call! sess "edit_add_form" {:ns "tl" :source "(defn bare [x] x)" :prompt "undocumented public"})
      (call! sess "done" {:label "d"})
      (let [t (edn/read-string (call! sess "query_rule_telemetry" {}))]
        (is (map? (:fire-rate t)) (pr-str t))
        (is (>= (get-in t [:window :dones]) 1) (pr-str t))
        (is (every? #(contains? (:escape-markers t) %) [:unsafe :reads :unused-ok]) (pr-str t))
        (is (contains? t :dials) (pr-str t)))
      (finally (ops/close! sess)))))

(deftest ^:external cleanup-is-reachable-over-the-wire
  ;; The done-point tidy has to be callable for ONE namespace, because a legacy
  ;; declare is otherwise unaddressable: it and the defn share a name, so
  ;; edit_delete_form / edit_replace_form cannot resolve it. Exposed as a
  ;; general `cleanup` rather than a declare-specific tool on purpose —
  ;; declares are auto-managed, and the tool surface should not teach an agent
  ;; that it owns them.
  (let [sess (external/open!)]
    (try
      (let [by-name (into {} (map (juxt :name identity))
                          (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                                  [:result :tools]))]
        (let [ops (set (get-in by-name ["refactor" :inputSchema :properties :op :enum]))]
          (is (contains? ops "cleanup") "indexed by the refactor family (D-families)")
          (is (not (contains? ops "fix_declares"))
              "superseded — one general tidy, not a declare-specific tool"))
        (is (nil? (get-in by-name ["refactor" :annotations]))
            "it writes — no read-only claim"))
      (ops/ingest! sess 'fd.wire
                   (str "(ns fd.wire)\n\n"
                        "(declare b)\n\n"
                        "(defn a [] (b))\n\n"
                        "(defn b [] 2)\n"))
      (testing "calling it retires a declare the pipeline can satisfy by ordering"
        (is (re-find #"1" (call! sess "cleanup" {:ns "fd.wire"})))
        (is (not (re-find #"declare"
                          (call! sess "query_source" {:ns "fd.wire" :full true})))))
      (finally (ops/close! sess)))))

(deftest ^:external undo-is-reachable-over-the-wire
  ;; undo must be on the wire to do its job: it is only reached for reflexively
  ;; if it is one call away the moment a write turns out wrong.
  (let [sess (external/open!)]
    (try
      (let [by-name (into {} (map (juxt :name identity))
                          (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                                  [:result :tools]))]
        (is (some #{"undo"} (get-in by-name ["edit" :inputSchema :properties :op :enum]))
            "one call away, in the edit family's index (D-families)")
        (is (nil? (get-in by-name ["edit" :annotations]))
            "it writes — no read-only claim"))
      (call! sess "ns_create" {:ns "un.wire"
                              :source "(ns un.wire)\n(defn keep-me [] 1)\n"})
      (call! sess "edit_add_form" {:ns "un.wire" :source "(defn oops [] 2)"
                                  :prompt "a write that turns out wrong"})
      (testing "one call takes the bad write back"
        (is (re-find #"1" (call! sess "undo" {:prompt "that was wrong"})))
        (let [src (call! sess "query_source" {:ns "un.wire" :full true})]
          (is (not (re-find #"oops" src)))
          (is (re-find #"keep-me" src) "unrelated work survives")))
      (finally (ops/close! sess)))))

(deftest ^:external module-purity-accepts-the-spelling-the-docs-use
  ;; Every surface writes the tier WITH the colon — the tool description says
  ;; ":pure (may reach NO effect…)", the skill says `tier :pure`, and
  ;; query_depends reports :pure back. So an agent naturally sends ":pure",
  ;; which (keyword ":pure") turned into ::pure and got refused. Both
  ;; spellings must land on the same tier.
  (let [sess (external/open!)]
    (try
      (doseq [spelling ["pure" ":pure"]]
        (let [r (call! sess "module_purity" {:module "mp.core" :tier spelling
                                            :prompt "a pure core"})]
          (is (not (re-find #"tier must be" r)) (str spelling " → " r))
          (is (re-find #":pure" r) (str spelling " → " r))))
      (finally (ops/close! sess)))))

(deftest ^:external review-scan-reports-a-size-distribution-not-just-a-count
  ;; ":large 3" is honest but misleading as a progress signal: DECOMPOSING a
  ;; god-form ADDS forms, so the count can rise while the codebase improves —
  ;; it did, 50 -> 54, during this sweep. Large forms are a distribution to
  ;; flatten, not a quantity to minimize, so report the shape.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'rs.core
                   (str "(ns rs.core)\n\n"
                        "(defn small [x] (inc x))\n\n"
                        "(defn big [x]\n"
                        (apply str (repeat 60 "  (println x)\n"))
                        "  x)\n"))
      (let [r (review/review-scan sess)]
        (is (= 'rs.core/big (get-in r [:loc :largest])) (pr-str (:loc r)))
        (is (>= (get-in r [:loc :max]) 60) (pr-str (:loc r)))
        (testing "the median is the honest counterweight to the max"
          (is (< (get-in r [:loc :median]) (get-in r [:loc :max]))
              (pr-str (:loc r)))))
      (finally (ops/close! sess)))))

(deftest ^:external untested-does-not-flag-plain-defs
  ;; :untested means "no runtime evidence reaches this form". A plain (def x
  ;; <data>) has no invocation to trace, so it can NEVER acquire evidence —
  ;; flagging it is a finding no one can ever discharge, which is worse than
  ;; not flagging it: it pads the number the sweep is trying to drive to zero.
  ;; defn/defmulti stay flaggable; they are callable.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ut.core
                   (str "(ns ut.core)\n\n"
                        "(def threshold 42)\n\n"
                        "(defn untouched [x] (+ x threshold))\n"))
      (let [flags (into {} (map (juxt :form :flags))
                        (:top (review/review-scan sess :ns 'ut.core :limit 50)))]
        (is (not (contains? (set (get flags 'ut.core/threshold)) :untested))
            (str "a plain def cannot be traced: " (pr-str flags)))
        (is (contains? (set (get flags 'ut.core/untouched)) :untested)
            (str "a callable fn with no evidence still flags: " (pr-str flags))))
      (finally (ops/close! sess)))))

(deftest ^:external a-zero-test-verification-is-unverified-not-green
  ;; The single most expensive dishonesty in the response shape. A write whose
  ;; verification ran NOTHING reported :status :green with :coverage :none
  ;; beside it — two fields saying opposite things, and :green is the one an
  ;; agent acts on. A rename_sweep across 11 forms reported green having run
  ;; zero tests, which is how it shipped code that read nil at runtime.
  ;;
  ;; Green must mean "tests ran and passed". Nothing ran is UNVERIFIED — the
  ;; agent's cue to write a test or name one, rather than a habit of running
  ;; test_run manually after every write because the status cannot be trusted.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'uv.core "(ns uv.core)\n")
      (let [r (call! sess "edit_add_form"
                     {:ns "uv.core" :source "(defn ^:unused-ok f [x] (inc x))"
                      :prompt "no covering test exists"})]
        (is (re-find #":status :unverified" r) r)
        (is (not (re-find #":status :green" r)) r)
        (is (re-find #":coverage :none" r) r))
      (testing "a run that DID execute tests still reports green"
        (ops/ingest! sess 'uv.core-test
                     (str "(ns uv.core-test\n"
                          "  (:require [clojure.test :refer [deftest is]]\n"
                          "            [uv.core :as c]))\n\n"
                          "(deftest t (is (= 2 (c/f 1))))\n"))
        (let [r (call! sess "edit_replace_form"
                       {:ns "uv.core" :name "f"
                        :source "(defn ^:unused-ok f [x] (inc x))"
                        :prompt "touch it so its test runs"})]
          (is (not (re-find #":unverified" r)) r)))
      (finally (ops/close! sess)))))

(deftest ^:external unverified-says-why-it-verified-nothing
  ;; :unverified alone repeats the original sin at one remove. "No test covers
  ;; this yet" and "the fallback looked in the wrong place" are different
  ;; facts: the first is the agent's to fix by writing a test, the second is a
  ;; slopp bug. They were indistinguishable, which is exactly how the empty
  ;; fallback hid — it looked like an ordinary untested form for months.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'uw.core "(ns uw.core)\n")
      (let [r (call! sess "edit_add_form"
                     {:ns "uw.core" :source "(defn ^:unused-ok f [x] (inc x))"
                      :prompt "genuinely nothing covers this"})]
        (is (re-find #":status :unverified" r) r)
        (is (re-find #":reason :no-covering-tests" r)
            (str "an :unverified must name its cause: " r)))
      (finally (ops/close! sess)))))

(deftest ^:external dry-run-is-honored-over-the-wire
  ;; A preview that silently performs the operation is far worse than no
  ;; preview. api/rename-sweep! gained :dry-run, but the MCP tool schema and
  ;; dispatch did not — and the layer IGNORES unknown arguments, so asking for
  ;; a preview ran a real store-wide sweep. Caught only because I read the
  ;; result and saw :deltas 5 where :in-code should have been.
  ;;
  ;; Silently dropping an unrecognised SAFETY flag turns it into a no-op with
  ;; the opposite meaning of what was asked.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'dw.core "(ns dw.core)\n(defn f [] {:dw/target 1})\n")
      (let [before (count (ops/journal sess))
            r      (call! sess "rename_sweep" {:from ":dw/target"
                                               :to ":dw/renamed"
                                               :dry_run true})]
        (is (re-find #":dry-run true" r) r)
        (is (= before (count (ops/journal sess)))
            "a preview over the wire must append NO delta")
        (is (re-find #":dw/target" (query/query-source sess 'dw.core))
            "and must not rewrite anything"))
      (finally (ops/close! sess)))))

(deftest ^:external a-write-cannot-green-what-the-tier-never-ran
  ;; Writing an ^:external deftest returned :status :green — a green earned by
  ;; OTHER tests in the namespace, for a form the in-image tier structurally
  ;; cannot run. traced-run! computes :external-pending correctly; summarize's
  ;; terse path rebuilds :test from a fixed key list and dropped it, the same
  ;; way :dry-run's payload and :drift were dropped before it.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-isogreen"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      ;; MIXED tiers: a fast test runs, an external one defers
      (call! sess "ns_create" {:ns "iso.core" :source "(ns iso.core)\n(defn f [] 1)\n"})
      (call! sess "ns_create"
             {:ns "iso.core-test"
              :source (str "(ns iso.core-test\n"
                           "  (:require [clojure.test :refer [deftest is]]\n"
                           "            [iso.core :as c]))\n"
                           "(deftest quick-t (is (= 1 (c/f))))\n")})
      (let [r (edn/read-string
               (call! sess "edit_add_form"
                      {:ns "iso.core-test"
                       :prompt "an external spec"
                       :source "(deftest ^:external slow-t (is (= 1 (c/f))))"}))
            t (:test r)]
        (testing "the deferral survives to the wire"
          (is (some #{'slow-t} (:tests (:external-pending t))) (pr-str r)))
        (testing "and the write does not claim green for what that tier never ran"
          (is (= :partial (:status t)) (pr-str r))))
      ;; ISOLATED ONLY: nothing can run in-image, which is design, not a bug
      (call! sess "ns_create" {:ns "only.core" :source "(ns only.core)\n(defn g [] 1)\n"})
      (call! sess "ns_create"
             {:ns "only.core-test"
              :source (str "(ns only.core-test\n"
                           "  (:require [clojure.test :refer [deftest is]]\n"
                           "            [only.core :as c]))\n"
                           "(deftest ^:external slow-only-t (is (= 1 (c/g))))\n")})
      (testing "all-external scope is named as design, not blamed on a scope bug"
        (let [raw (call! sess "edit_replace_form"
                         {:ns "only.core"
                          :name "g"
                          :prompt "touch a form only external tests cover"
                          :source "(defn g [] (inc 0))"})
              t   (try (:test (edn/read-string raw)) (catch Exception _ nil))]
          (is (map? t) raw)
          (is (= :unverified (:status t)) raw)
          (is (= :all-impacted-external (:reason t)) raw)))
      (finally (ops/close! sess)))))

(deftest ^:external a-previews-payload-survives-the-wire
  ;; The fourth silent loss on this path. :dry-run's payload, :drift and
  ;; :external-pending were each computed correctly and dropped by an
  ;; allow-list in the dispatch; the fourth was edit_requalify's :in-code,
  ;; dropped while building the tool meant to be careful about the third.
  ;;
  ;; An api-level test cannot catch this — the api was always right. The
  ;; invariant belongs where the agent reads it: over the wire.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'wp.core
                   (str "(ns wp.core)\n"
                        "(defn opts \"O.\" [{:keys [dir]}] dir)\n"
                        "(defn ^:unused-ok a \"A.\" [] (opts {:dir \"x\"}))\n"
                        "(defn ^:unused-ok b \"B.\" [m] (opts m))\n"))
      (let [r (edn/read-string
               (call! sess "edit_requalify" {:ns "wp.core" :name "opts"
                                            :dry_run true :verbose true}))]
        (testing "the preview reports what it WOULD rewrite, by name"
          (is (some #{'wp.core/opts} (:in-code r)) (pr-str r))
          (is (some #{'wp.core/a} (:in-code r)) (pr-str r)))
        (testing "and what it could NOT reach"
          (is (= '[wp.core/b] (:unknown-shape r)) (pr-str r)))
        (testing "a preview writes nothing — the property, not the report"
          (is (re-find #"\{:keys \[dir\]\}"
                       (get-in (query/query-slice sess 'wp.core 'opts)
                               [:target :source]))
              "the arglist must be untouched after a dry run")))
      (finally (ops/close! sess)))))

(deftest a-trimmed-payload-stays-parseable-and-says-what-it-dropped
  ;; The trim used to be (subs s 0 8000) — a blind mid-structure cut. For a
  ;; collection response (query_history rows, changes, commits) that leaves
  ;; UNPARSEABLE edn, so the agent's only recovery is query_detail for the
  ;; whole thing: the trim COSTS more than not trimming. Drop whole ITEMS
  ;; instead, and say how many, so the body is usable as-is.
  (let [rows (vec (for [i (range 200)]
                    {:id (str "d" i) :op :replace :ns 'app.core
                     :prompt (str "a reasonably wordy recorded intent number " i)}))]
    (testing "a sequential payload keeps COMPLETE rows and parses back"
      (let [{:keys [body note]} (#'mcp/fit-payload rows 2000)
            back (edn/read-string body)]
        (is (vector? back) body)
        (is (pos? (count back)))
        (is (< (count back) 200) "it must actually drop rows")
        (is (every? #(= #{:id :op :ns :prompt} (set (keys %))) (butlast back))
            "every kept row must be WHOLE — no half-cut map")
        (is (:truncated (last back))
            "and the LAST element is the in-band marker, which is the only
             thing in this payload that is not a row — a consumer reading
             the response as data sees the trim rather than a short list")
        (is (<= (count body) 2000))
        (is (re-find #"200" (str note)) note)))
    (testing "a map payload keeps whole entries and parses back"
      (let [m (into {} (for [i (range 200)]
                         [(keyword (str "k" i)) (str "value number " i)]))
            {:keys [body]} (#'mcp/fit-payload m 1000)]
        (is (map? (edn/read-string body)) body)
        (is (<= (count body) 1000))))
    (testing "a scalar has no items to drop — caller falls back"
      (is (nil? (#'mcp/fit-payload 42 100))))))

(deftest ^:external
  ^{:correspondence "tool names spelled in slopp's own PROSE vs mcp.tools/tools — the side that cannot be derived, so it is checked forever; this is the guard that spent its whole life scanning an empty store"}
  slopp-prose-never-names-a-tool-that-does-not-exist
  ;; The self-description half of P1. Gates see var references; they do not see
  ;; a TOOL NAME in a docstring, a tool description, or an error message — so a
  ;; consolidated or removed tool leaves its name behind in the very surfaces
  ;; agents read to learn what to call.
  ;;
  ;; Measured before this test existed: 11 dead names in production prose,
  ;; including query_impact/query_flow/query_references inside query_depends'
  ;; OWN description, two in the cheat-sheet, and query_outline in a
  ;; missing-form ERROR — which fires exactly when someone is already lost.
  ;;
  ;; **It then spent its whole life scanning NOTHING.** It opened a session on
  ;; "." — which in the external tier is the materialized build dir, source
  ;; but no `.slopp/store.db` — so the store was empty, `bad` was empty, and
  ;; the assertion passed on a population of zero. `built-store` reconstructs
  ;; the store from the code actually present, which is what this always
  ;; needed; the non-empty assertions below are what stops it regressing to
  ;; vacuous a second time.
  (let [st     (external/built-store)
        known  (into (into #{} (map :name) tools/registry)
                     ;; the de-advertised single-form aliases DISPATCH — prose
                     ;; naming them is guidance an agent can follow, which is
                     ;; the point of de-advertising without renaming (s8)
                     tools/single-write-tools)
        ;; ONE exclusion by name: git_map is a SQLITE TABLE, not a tool (every
        ;; use is in the sha-mapping code; the prefix alone lies here).
        exempt #{"git_map"}
        ;; edit_group is deliberately off the wire (see
        ;; edit-group-stays-off-the-wire-on-purpose), and prose SAYING SO is
        ;; correct. Exactly one production form is entitled to say it: its own
        ;; definition. This used to exempt the NAME instead, which waived
        ;; "use edit_group with the caller's delete step FIRST" in
        ;; edit_delete_form's refusal, its tool description, the shipped skill
        ;; and two doc pages — an instruction to call a tool that is not on the
        ;; wire, in the surface an agent reads at the moment it is blocked.
        ;; An exemption keyed on the NAME cannot tell "this does not exist"
        ;; from "call this", and only one of those is worth waiving.
        ;;
        ;; The convention that makes one exemption enough: the UNDERSCORE
        ;; spelling is a tool you can call, the HYPHEN spelling is a var. Prose
        ;; explaining that the seam is off-wire refers to `edit-group!`, which
        ;; this pattern does not match and should not. Only the form arguing
        ;; what the TOOL would be needs to spell it with an underscore.
        off-wire {} ; edit_group joined the wire 2026-08-30; the shape stays for the next off-wire seam
        pat    #"\b((?:query|edit|ns|module|deps|branch|git|turn|config|file)_[a-z0-9_]+)"
        prod   (remove #(str/ends-with? (str %) "-test") (keys (:namespaces st)))
        bad    (vec (distinct
                     (for [nsx  prod
                           e    (store/forms st nsx)
                           :let [s    (try (n/sexpr (:node e)) (catch Exception _ nil))
                                 here (symbol (str nsx) (str (:name e)))]
                           text (filter string? (tree-seq coll? seq s))
                           [_ nm] (re-seq pat text)
                           :when (not (known nm))
                           :when (not (exempt nm))
                           :when (not= here (get off-wire nm))]
                       (str nm " named by " here))))]
    (testing "there is a POPULATION — this guard scanned an empty store for its whole life"
      (is (< 50 (count prod))
          (str "expected slopp's production namespaces, got " (count prod)))
      (is (some #(= 'slopp.mcp.tools %) prod)
          "the namespace that DEFINES the tool descriptions must be in scope"))
    (testing "the detector still fires — a check that cannot fail is not a check"
      ;; the discipline done-advisories enforce with :fires-on. This guard
      ;; went green by FIXING 11 real names; without a positive case it
      ;; would look identically healthy if the pattern or the registry
      ;; lookup silently broke.
      (is (re-find pat "see query_nonexistent_thing {x} for details"))
      (is (not (known "query_nonexistent_thing")))
      (is (known "query_depends") "the registry lookup must recognise a REAL tool"))
    (is (empty? bad)
        (str "prose names " (count bad) " tool(s) that do not exist — an"
             " agent following this guidance pays a failed call to find"
             " out: " (pr-str bad)))))

(deftest ^:external query-capabilities-rides-the-wire
  (let [sess (external/open!)]
    (try
      (let [rep (edn/read-string (call! sess "query_capabilities" {}))
            row (fn [rep k] (some #(when (= k (:key %)) %) (:settings rep)))]
        (testing "an untouched store reports every setting at its default"
          (is (false? (:effective (row rep "http.enabled"))) (pr-str (row rep "http.enabled")))
          ;; a key that HAS a default demonstrates the claim; http.port declares
      ;; none any more, so it would only demonstrate nil
      (is (= 1048576 (:effective (row rep "http.max-body-bytes"))))
      (is (nil? (:effective (row rep "http.port"))))
          (is (not (:set (row rep "http.port"))))
          (is (some #(= "http.static.*" (:key %)) (:patterns rep))))
        (testing "a config_file set is reflected as effective + set"
          (call! sess "config_file" {:path "capabilities" :key "http.port" :value "7357"
                                     :prompt "port for the wire test"})
          (let [rep (edn/read-string (call! sess "query_capabilities" {}))
                port (row rep "http.port")]
            (is (= 7357 (:effective port)) (pr-str port))
            (is (true? (:set port)))
            (is (= "7357" (:value port))))))
      (testing "the FEATURE view says what opting in would arm"
        ;; the half of the bargain that was invisible: the settings said what
        ;; could be configured, and nothing said which rules would start
        ;; refusing writes. A reader deciding whether to turn `http` on could
        ;; not see the ten gates that come with it.
        ;;
        ;; ONE call, and the config write above it is load-bearing rather than
        ;; scene-setting: `told!` returns an :unchanged stub for an identical
        ;; re-read within the same ask, so a second query_capabilities over an
        ;; unmoved store answers with no payload at all. That is the mechanism
        ;; working — and it is exactly how this test first failed, reporting
        ;; :capabilities nil while the view itself was correct.
        (call! sess "config_file" {:path "capabilities" :key "cli.enabled" :value "true"
                                   :prompt "a shell needs nothing beneath it"})
        (let [rep    (edn/read-string (call! sess "query_capabilities" {}))
              cap    (fn [c] (some #(when (= c (:capability %)) %) (:capabilities rep)))
              http   (cap "http")
              slopp* (cap "slopp")]
          (is (seq (:capabilities rep)) (pr-str (keys rep)))
          (is (false? (:enabled http)) (pr-str http))
          (is (true? (:enabled (cap "cli"))) "the write above is reflected")
          (is (= [] (:requires http)) "how a server is launched is packaging")
          (is (= ["rest" "webapp"] (sort (:required-by http)))
              (str "both siblings stand on http, and neither on the other: "
                   (pr-str (:required-by http))))
          (testing "and it names the rules, at both grains"
            ;; guard the guard: an empty :arms satisfies any weaker assertion
            ;; here, and an empty join is exactly what a broken
            ;; namespace-to-capability derivation produces.
            (is (seq (:arms http)) (pr-str http))
            (is (some #(= 'http-auth-refusal (:rule %)) (:arms http))
                (str "the write gate an unsecured route trips: " (pr-str (:arms http))))
            (is (some #(= :http-public-mutation (:rule %)) (:arms http))
                (str "and a done-grain advisory, so both registries are joined: "
                     (pr-str (:arms http))))
            (is (= #{:form :done} (set (map :grain (:arms http))))))
          (testing "an owner that is not an opt-in has no bargain to state"
            (is (:reserved slopp*))
            (is (nil? (:arms slopp*)))
            (is (nil? (:enabled slopp*))
                "there is no switch, so reporting one would invite throwing it"))))
      (testing "the tool is advertised read-only"
        (is (contains? tools/read-only-tools "query_capabilities")))
      (finally (ops/close! sess)))))

(deftest ^:external branch-milestones-mirror-the-branch-line
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-bpub" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" dir)
        _    (sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t"
                    "commit" "--allow-empty" "-m" "root")
        sess (external/open! {:slopp.ops/dir dir})
        rev  (fn [ref] (clojure.string/trim (:out (sh/sh "git" "-C" dir "rev-parse" ref))))]
    (try
      (call! sess "ns_create" {:ns "bp.core" :source "(ns bp.core)\n(defn ^:unused-ok f [x] x)\n"})
      (call! sess "commit_point" {:label "trunk milestone"})
      (call! sess "branch_create" {:name "feature"})
      (call! sess "edit_add_form" {:ns "bp.core" :source "(defn ^:unused-ok g [x] x)"
                                   :prompt "branch work"})
      (let [r      (call! sess "commit_point" {:label "branch milestone"})
            pushed (:pushed (:published (edn/read-string r)))
            trunk  (rev "refs/heads/slopp/main")
            head   (rev "refs/heads/slopp/feature")]
        (is (re-find #"slopp/feature" r) r)
        (testing "the mirrored ref carries the BRANCH milestone, not the fork point"
          (is (not= trunk head) (str "slopp/feature stuck at the fork-point sha " head))
          (is (= pushed head) (str ":published claims " pushed " but git has " head))
          (is (re-find #"defn \^:unused-ok g"
                       (:out (sh/sh "git" "-C" dir "show"
                                    "refs/heads/slopp/feature:src/bp/core.clj")))
              "the branch work must be IN the mirrored tree")))
      (finally (ops/close! sess)))))

(deftest ^:external query-surface-rides-the-wire
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "wr.api" :source "(ns wr.api)\n(defn seed \"S.\" [x] x)\n"})
      (testing "no capability enabled: TEACHING, not an empty map"
        ;; "nothing declared" and "nothing enabled" are different answers and
        ;; only the second has an action attached. An empty map would read as
        ;; the first while meaning the second.
        (let [rep (edn/read-string (call! sess "query_surface" {}))]
          (is (re-find #"capabilit" (str (:note rep))) (pr-str rep))
          (is (nil? (:http rep)) (pr-str rep))
          (is (nil? (:cli rep)) (pr-str rep))))
      (testing "a CLI-only app gets a cli section and no http one"
        ;; the shape of the answer says what kind of application this is
        (call! sess "config_file" {:path "capabilities" :key "cli.enabled" :value "true"
                                   :prompt "opt into a shell"})
        (call! sess "edit_add_form"
               {:ns "wr.api"
                :source (str "(defn ^{:cli/command \"ping\" :cli/doc \"Ping it.\""
                             " :cli/args [:catn]} ping-cmd \"P.\" [ctx args] args)")
                :prompt "a command"})
        (let [rep (edn/read-string (call! sess "query_surface" {}))
              row (first (:cli rep))]
          (is (nil? (:http rep)) (str "http is not enabled, so it has no section: " (pr-str rep)))
          (is (= "ping" (:command row)) (pr-str rep))
          (is (= :command (:kind row)) "every row says what kind it is")
          (is (= 'wr.api/ping-cmd (:handler row)))
          (is (= "Ping it." (:doc row)))))
      (testing "enabling http adds its section beside the first"
        (call! sess "config_file" {:path "capabilities" :key "http.enabled" :value "true"
                                   :prompt "opt in"})
        (call! sess "edit_add_form"
               {:ns "wr.api"
                :source "(defn ^{:http/method :get :rest/path \"/api/ping\" :http/auth :public :rest/response :map} ping \"P.\" [req] req)"
                :prompt "a public endpoint"})
        (let [rep (edn/read-string (call! sess "query_surface" {}))
              row (first (:http rep))]
          (is (seq (:cli rep)) (str "and the first section is still there: " (pr-str rep)))
          (is (= "/api/ping" (:path row)) (pr-str rep))
          (is (= :public (:auth row)))
          (is (= 'wr.api/ping (:handler row)))))
      (testing "a declared static MOUNT is part of the surface"
        ;; slopp-ui's finding, from a migration: they set http.static./assets,
        ;; it was effective, and query_surface's answer had ten endpoint rows
        ;; and no mount — while the tool description promised mounts.
        ;;
        ;; It matters more than a doc nit because **the mount is the
        ;; declaration whose absence is SILENT**: orphan it and the document
        ;; still serves while the <script> it names 404s. If this is where
        ;; someone checks their surface after a migration, a correctly
        ;; configured mount missing from the answer teaches them to go look
        ;; somewhere else — and somewhere else is an ^:external test most
        ;; projects do not have.
        (call! sess "config_file" {:path "capabilities" :key "http.static./assets"
                                   :value "public" :prompt "serve the bundle"})
        (let [rep (edn/read-string (call! sess "query_surface" {}))]
          (is (= {"/assets" "public"} (:http/static rep))
              (str "a declared mount is surface: " (pr-str rep)))))

      (testing "enabling rest adds the TYPED surface beside the routes"
        ;; three sections now, and the shape of the answer is still what says
        ;; what kind of application this is
        (call! sess "config_file" {:path "capabilities" :key "rest.enabled" :value "true"
                                   :prompt "publish a typed API"})
        (let [rep (edn/read-string (call! sess "query_surface" {}))
              row (first (:rest rep))]
          (is (seq (:cli rep)) (str "and the earlier sections survive: " (pr-str rep)))
          (is (seq (:http rep)))
          (is (= :contract (:kind row)) (pr-str rep))
          (is (= "/api/ping" (:path row)))
          (is (= 'wr.api/ping (:handler row)))
          (is (not (contains? row :published))
              (str "every REST endpoint is part of the published API, so there"
                   " is no per-row publishedness left to report — :published"
                   " was :rest/client inverted, and could only read true once"
                   " that flag was retired"))))

      (testing "the tool is advertised read-only"
        (is (contains? tools/read-only-tools "query_surface")))
      (finally (ops/close! sess)))))

(deftest ^:external spot-check-runs-external-tests-in-their-tier
  ;; frictions #1: red/green on ONE ^:external test used to cost a manual
  ;; whole-ns fresh-JVM detour — test_run {only [...]} matched 0 tests and
  ;; taught it. A spot-check that NAMES its targets now runs each target in
  ;; its own tier: in-image members in-image, ^:external members in ONE
  ;; serial external JVM.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create"
             {:ns "spot"
              :source "(ns spot (:require [clojure.test :refer [deftest is]]))\n(defn f [x] x)\n(deftest f-t (is (= 1 (f 1))))\n(deftest ^:external f-ext (is (= 2 (f 2))))\n"})
      (testing "an :only naming an ^:external test runs it externally, for real"
        (let [r (call! sess "test_run" {:only ["spot/f-ext"]})]
          (is (re-find #":external" r) r)
          (is (re-find #":ran 1" r) r)
          (is (not (re-find #"0 tests matched" r)) r)))
      (testing "a mixed-ns spot-check runs BOTH tiers and reports both"
        (let [r (call! sess "test_run" {:ns "spot"})]
          (is (re-find #":image" r) r)
          (is (re-find #":external" r) r)))
      (finally (ops/close! sess)))))

(deftest ^:external edit-subform-after-does-not-combine-with-match
  ;; review host-F1: the :after INSERT anchor composed src as (str after "\n"
  ;; source) keyed on :after's mere PRESENCE, while the anchor preferred
  ;; :match — so {:match M :after A :source S} replaced M with "A\nS", a
  ;; duplicate of A spliced at M's site (legal shadowing, so verification
  ;; stayed green). Combining the two must refuse.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create"
             {:ns "sf" :source "(ns sf)\n(defn f [x]\n  (let [a 1]\n    (+ a x)))\n"})
      (testing ":after combined with :match refuses instead of duplicating"
        (let [r (call! sess "edit_subform"
                       {:ns "sf" :name "f"
                        :match "(+ a x)" :after "[a 1]" :source "b 2"})]
          (is (re-find #"(?i)after" (str r)) r)
          (is (re-find #"(?i)refus|combine|one or the other|ambiguous" (str r)) r)))
      (testing "the form was NOT mutated by the refused call — a still binds once"
        (let [src (call! sess "query_source" {:ns "sf" :targets [{:ns "sf" :name "f"}]})]
          (is (= 1 (count (re-seq #"\[a 1\]" (str src)))) src)))
      (finally (ops/close! sess)))))

(deftest ^:external ns-create-platform-rides-the-wire
  (let [sess (external/open!)]
    (try
      (testing "ns_create carries a platform on the wire — born :cljs"
        (let [r (call! sess "ns_create"
                       {:ns "wcw.client" :source "(ns wcw.client)\n"
                        :platform "cljs" :prompt "browser code"})]
          (is (not (re-find #":error" r)) r)))
      (testing "a js/* form then lands unverified, deferred to the cljs compiler"
        (let [r (call! sess "edit_add_form"
                       {:ns "wcw.client" :source "(defn boom [] (js/alert \"hi\"))"
                        :prompt "client handler"})]
          (is (re-find #":cljs-deferred-to-compile" r) r)
          (is (not (re-find #"form failed to compile" r)) r)))
      (finally (ops/close! sess)))))

(deftest ^:external one-shot-call-errors-stay-readable
  ;; The turn gate on --call is DELIBERATE (see one-shot-call: reads are free,
  ;; writes carry provenance, and turns are durable across one-shot processes,
  ;; so a script opens ONE turn then writes). What was broken is the error path:
  ;; call! reported stack frames, and every result flows through text!, whose
  ;; boundary-leak guard refuses a file:line coordinate — so a refused write
  ;; blew up with "boundary leak — a file/line coordinate reached an agent
  ;; response" INSTEAD of the actual reason. The gate's teaching has to survive
  ;; its own trip through the wire.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-oneshot" (make-array java.nio.file.attribute.FileAttribute 0)))
        r   (mcp/call! dir "ns_create"
                       {:ns "oc.core" :source "(ns oc.core)\n" :agent "probe"})
        txt (get-in r [:content 0 :text])]
    (is (:isError r) "an unturned write is still refused")
    (is (re-find #"turn" txt) "and says WHY, in words the caller can act on")
    (is (not (re-find #"boundary leak" txt))
        "the refusal must not be replaced by the guard that its own frames tripped")
    (is (not (re-find #"\.clj:\d+" txt))
        "no file:line coordinate survives to the caller")))

(deftest a-hint-fires-only-on-the-call-that-earned-it
  (testing "a stale streak does not attach to a call that could not have earned it"
    (let [sess (atom {:slopp.mcp.smells/stats {:searches 5}})]
      (is (nil? (smells/track-hint! sess "deps_add" {}))
          "deps_add is not a search — it must not carry the search-streak hint")))
  (testing "the streak still fires on the search that completes it"
    (let [sess (atom {:slopp.mcp.smells/stats {:searches 2}})]
      (is (re-find #"search streak" (str (smells/track-hint! sess "query_search" {}))))))
  (testing "a write CLEARS the streak — it counts CONSECUTIVE searches"
    (is (zero? (:searches (smells/bump-smell-counts {:searches 3} "edit_add_form" {})))))
  (testing "a search still accumulates, and an unrelated read still clears"
    (is (= 4 (:searches (smells/bump-smell-counts {:searches 3} "query_search" {}))))
    (is (zero? (:searches (smells/bump-smell-counts {:searches 3} "query_slice" {})))))
  (testing "the rename streak likewise stays on rename calls"
    (let [sess (atom {:slopp.mcp.smells/stats {:renames 4}})]
      (is (nil? (smells/track-hint! sess "query_slice" {}))))))

(deftest ^:external unknown-argument-is-refused
  ;; The MCP dispatch used to DROP an unrecognised argument — a typo'd flag
  ;; silently ran a real sweep (dry-run-is-honored-over-the-wire is the
  ;; incident). Strict validation REFUSES an unknown key, naming it, so a flag
  ;; cannot evaporate into the opposite of what was asked. The accepted set is
  ;; exactly the schema: there is no alias table behind it.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'uk.core "(ns uk.core)\n(defn f [] {:uk/target 1})\n")
      (testing "an unknown key is refused, names itself and the accepted keys, and NOTHING runs"
        (let [before (count (ops/journal sess))
              r      (call! sess "rename_sweep" {:from ":uk/target"
                                                 :to ":uk/renamed"
                                                 :bogus true})]
          (is (re-find #"unknown argument" r) r)
          (is (re-find #":bogus" r) r)
          (is (re-find #":dry_run" r) "the refusal lists the accepted keys")
          (is (= before (count (ops/journal sess)))
              "a refused call appends NO delta — the sweep must not run")
          (is (re-find #":uk/target" (query/query-source sess 'uk.core))
              "and rewrites nothing")))
      (testing "a spelling the schema does not carry is unknown, even a once-accepted one"
        (call! sess "ns_create" {:ns "uk2" :source "(ns uk2)\n(defn f [x] (+ x x 1))\n"})
        (let [r (call! sess "edit_extract" {:ns "uk2" :from "f" :name "doubled"
                                            :subform "(+ x x 1)"})]
          (is (re-find #"unknown argument :subform" r) r)))
      (finally (ops/close! sess)))))

(deftest ^:external module-extract-dry-run-rides-the-wire
  ;; The dry-run IS the safety story: an agent reads the plan before a rename
  ;; that would otherwise land a pile of module-gate violations. So the wire
  ;; test asserts the plan arrives WITH its forced-by attribution, and that
  ;; nothing moved.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'mx.helper "(ns mx.helper)\n(defn shared \"S.\" [x] x)\n")
      (ops/module-dep! sess "mx.other" "mx.helper" :prompt "other uses helper")
      (ops/ingest! sess 'mx.other
                   (str "(ns mx.other (:require [mx.helper :as h]))\n"
                        "(defn b \"B.\" [x] (h/shared x))\n"))
      (let [out (call! sess "module_extract"
                       {:namespaces ["mx.helper"] :to "mx.core" :dry_run true})]
        (is (re-find #"mx\.core\.helper" out) out)
        (testing "the plan names WHO forces each hoist, not just that one is due"
          (is (re-find #"mx\.other/b" out) out))
        (testing "a dry-run writes nothing"
          (is (some? (get-in @sess [:store :namespaces 'mx.helper])))
          (is (nil? (get-in @sess [:store :namespaces 'mx.core.helper])))))
      (testing "an unsupported argument is refused, not silently dropped"
        (is (re-find #"(?i)unknown|unsupported"
                     (call! sess "module_extract"
                            {:namespaces ["mx.helper"] :to "mx.core" :dryrun true}))))
      (finally (ops/close! sess)))))

(deftest ^:external ui-serve-rides-the-wire-and-is-not-read-only
  (let [sess (external/open!)]
    (try
      (testing "advertised, and NOT read-only — it binds a port"
        (is (some #(= "ui_serve" (:name %)) tools/registry))
        (is (not (contains? tools/read-only-tools "ui_serve"))
            "a readOnlyHint would let plan mode auto-permit binding a port"))
      (testing "it answers with the bound port and a url that serves this session's API"
        (call! sess "ns_create" {:ns "us.only" :source "(ns us.only)\n(defn f \"F.\" [x] x)\n"})
        (let [r (edn/read-string (call! sess "ui_serve" {:port 0}))]
          (is (pos? (:port r)) (pr-str r))
          (is (= (str "http://127.0.0.1:" (:port r) "/") (:url r)))
          (is (re-find #"us\.only" (:http/body (http.client/request
                                    {:http/url (str (:url r) "api/namespaces")})))
              "the served session is THIS one — a fresh session would not have
               us.only. Asserted against the API rather than the document,
               because the document is now an empty mount point and carries
               no store content at all.")))
      (finally
        (call! sess "ui_serve" {:stop true})
        (ops/close! sess)))))

(deftest ^:external a-turn-records-where-its-wall-clock-went
  ;; slopp measured what its own verification cost and nothing else, so a
  ;; session's wall clock had no producer: 1,703s elapsed against 390s
  ;; recorded. The wire sees both edges of every call, and the TURN is
  ;; already the user-ask bracket the prompt hook maintains — so the split
  ;; belongs on the :turn-end delta, where it is durable and per-ask.
  (let [sess (external/open!)
        last-turn-end #(last (filter (fn [d] (= :turn-end (:op d))) (ops/journal sess)))]
    (try
      (mcp/handle! sess {:jsonrpc "2.0" :id 1 :method "tools/call"
                         :params {:name "turn_begin"
                                  :arguments {:intent "measure the turn"}}})
      (mcp/handle! sess {:jsonrpc "2.0" :id 2 :method "tools/call"
                         :params {:name "query_project" :arguments {}}})
      (mcp/handle! sess {:jsonrpc "2.0" :id 3 :method "tools/call"
                         :params {:name "session_brief" :arguments {}}})
      (mcp/handle! sess {:jsonrpc "2.0" :id 4 :method "tools/call"
                         :params {:name "turn_end" :arguments {}}})
      (let [d (last-turn-end)
            t (:timing d)]
        (is (some? t) (str "the turn-end delta must carry the split: " (pr-str d)))
        (is (<= 3 (:calls t)) (pr-str t))
        (is (number? (:slopp-ms t)))
        (is (number? (:outside-ms t)))
        (is (= (:elapsed-ms t) (+ (:slopp-ms t) (:outside-ms t)))
            "the split is exhaustive — an unexplained remainder is the bug")
        (is (seq (:top t)) "and it names the tools that cost")
        (is (every? :tool (:top t)) (pr-str (:top t)))

        (testing "and NOT what the answers cost to send — that left this delta"
          ;; The read fold rode `:timing` first and inherited the rotation
          ;; gate with it: a turn closes only when a user PROMPT arrived and a
          ;; WRITE followed, so a read-only ask and an event-driven session
          ;; both recorded nothing. It is `:read-cost` now, on its own
          ;; schedule, and `the-read-record-lands-in-the-JOURNAL-not-only-in-the-ring`
          ;; is what asserts it lands.
          (is (not (contains? t :reads))
              (str "two homes at two grains is where the two disagree: "
                   (pr-str t)))))
      (testing "turns do not bleed — a new one measures only its own calls"
        ;; the wire records a call AFTER it returns (so turn_end never reads a
        ;; half-finished entry for itself), which means turn_end's own entry
        ;; lands in the ring just after it cleared it. turn_begin clears again,
        ;; so the leftover bracket cannot be billed to the next ask.
        (mcp/handle! sess {:jsonrpc "2.0" :id 5 :method "tools/call"
                           :params {:name "turn_begin"
                                    :arguments {:intent "a second ask"}}})
        (mcp/handle! sess {:jsonrpc "2.0" :id 6 :method "tools/call"
                           :params {:name "query_project" :arguments {}}})
        (mcp/handle! sess {:jsonrpc "2.0" :id 7 :method "tools/call"
                           :params {:name "turn_end" :arguments {}}})
        (let [t (:timing (last-turn-end))]
          (is (= 2 (:calls t))
              (str "turn_begin + query_project, and nothing from turn one: "
                   (pr-str t)))))
      (finally (ops/close! sess)))))

(deftest ^:external a-new-ask-rotates-the-turn
  ;; A turn is supposed to be ONE USER ASK — that is what makes it the unit
  ;; the verbatim prompt is recorded against, and the unit wall-clock timing
  ;; is folded onto. It was neither: the gate opened a turn only when none was
  ;; open, and nothing ever closed one, so a single turn spanned an entire
  ;; session. Measured on slopp's own store: five :turn-begin deltas across a
  ;; session with roughly fifteen asks, and ZERO :turn-end.
  ;;
  ;; Two things were lost by that, and the second is the worse one. The timing
  ;; never landed, because it rides turn-end. And every ask after the first
  ;; was absent from the journal entirely — for a system whose whole claim is
  ;; recorded provenance.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-turnrotate"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})
        ask! (fn [prompt]
               (spit (io/file dir ".slopp" "pending-intent")
                     (str "{\"session-id\":\"sess-rot\",\"prompt\":\"" prompt "\"}")))
        turns (fn [op] (filter #(= op (:op %)) (ops/journal sess)))]
    (try
      (swap! sess assoc :require-turns? true)
      (io/make-parents (io/file dir ".slopp" "pending-intent"))
      (ask! "first ask: add a widget")
      (call! sess "ns_create" {:ns "rot.core" :source "(ns rot.core)\n(defn f [] 1)\n"})
      (is (= 1 (count (turns :turn-begin))) "the first ask opens a turn")
      (testing "a SECOND ask closes the first turn and opens its own"
        (ask! "second ask: rename the widget")
        (call! sess "edit_add_form" {:ns "rot.core" :source "(defn g \"G.\" [] 2)"})
        (is (= 2 (count (turns :turn-begin))) "two asks, two turns")
        (is (= 1 (count (turns :turn-end))) "and the first one was closed"))
      (testing "the journal carries BOTH asks, not just the first"
        (is (seq (history/query-search-history (ops/with-history sess) "first ask")))
        (is (seq (history/query-search-history (ops/with-history sess) "second ask"))))
      (testing "the closed turn carries where its wall clock went"
        (let [t (:timing (first (turns :turn-end)))]
          (is (some? t) "timing rides turn-end, which is why it never landed")
          (is (pos? (:calls t)))
          (is (= (:elapsed-ms t) (+ (:slopp-ms t) (:outside-ms t))))
          (is (= 0 (get-in t [:refused :count]))
              "nothing bounced in that turn, and it says zero rather than
               omitting the key")))
      (testing "a REFUSED call is counted, by the shape it really arrives in"
        ;; not a hand-built fixture: this drives an actual refusal through the
        ;; wire, so the heuristic that reads it is pinned against the real
        ;; payload rather than against my idea of the payload
        (ask! "third ask: try something that will not work")
        (call! sess "edit_add_form" {:ns "rot.core" :source "(defn h \"H.\" [] 3)"})
        (call! sess "edit_add_form" {:ns "rot.core" :source "(defn h \"dup.\" [] 4)"})
        (ask! "fourth ask: close the third turn")
        (call! sess "edit_add_form" {:ns "rot.core" :source "(defn k \"K.\" [] 5)"})
        (let [t (:timing (last (turns :turn-end)))]
          (is (pos? (get-in t [:refused :count]))
              (str "a duplicate add is refused and must be counted: " (pr-str t)))
          (is (some #(= "edit_add_form" (:tool %)) (get-in t [:refused :by-tool]))
              (pr-str t))))
      (finally (ops/close! sess)))))

(deftest ^:external the-ui-comes-up-with-the-server-and-never-blocks-it
  ;; The reviewer UI died with every server restart and nothing brought it
  ;; back, so a human who wanted it had to know to ask for it. It should just
  ;; be there.
  ;;
  ;; The constraint that shapes this: the UI is OPTIONAL and the MCP server is
  ;; not. An optional listener starts beside the server, and a failure prints
  ;; a sentence to stderr and is otherwise ignored. A UI that threw on a busy
  ;; port would take the whole server down over a browser page.
  (let [sess (external/open!)]
    (try
      (testing "a port someone else holds FALLS BACK rather than costing this
                project its UI — the autostart's port is a preference derived
                from the dir, not an address anyone asked for (D-hub), and
                the url it reports is whatever was actually bound"
        (let [;; 127.0.0.1 EXPLICITLY, not the wildcard: a ServerSocket on 0.0.0.0
              ;; does not stop http-kit binding 127.0.0.1 on the same port, so
              ;; a wildcard occupier makes this pass while proving nothing —
              ;; which is exactly what it did on the first run.
              sock (java.net.ServerSocket.
                    0 50 (java.net.InetAddress/getByName "127.0.0.1"))
              busy (.getLocalPort sock)]
          (try
            (let [r (mcp/start-ui! sess busy)]
              (is (some? (:url r)) (pr-str r))
              (is (not= busy (:port r)) (pr-str r)))
            (finally (.close sock) (server/stop!)))))
      (testing "a free port comes up and reports where it is"
        ;; The port is picked by opening an ephemeral socket and CLOSING it, so
        ;; between that close and start-ui!'s bind anything on the machine may
        ;; take it — and under the 4-shard external run something regularly
        ;; does (observed 2026-08-02: asked 52361, bound 52363, so both
        ;; intervening ports went in the window too). That race is in the
        ;; SETUP, not in the behaviour under test, so it is RETRIED rather
        ;; than asserted around: relaxing this to "some port came up" would
        ;; pass just as well on a start-ui! that ignored its argument
        ;; entirely, which is the one thing this block exists to rule out.
        (let [attempt (fn []
                        (let [p (let [s (java.net.ServerSocket. 0)
                                      p (.getLocalPort s)]
                                  (.close s) p)]
                          [p (mcp/start-ui! sess p)]))
              [free r] (loop [tries 4
                              [p res] (attempt)]
                         (if (or (= p (:port res)) (zero? tries))
                           [p res]
                           (do (server/stop!)
                               (recur (dec tries) (attempt)))))]
          (is (= free (:port r)) (pr-str r))
          (is (re-find (re-pattern (str ":" free "/")) (str (:url r))) (pr-str r))
          (testing "and the url reaches the HUMAN, not just the server's log"
            ;; the stderr banner goes to the MCP server's log, which most
            ;; clients never show anyone — so without this, autostart is a
            ;; feature that cannot be found
            (is (= (:url r) (:ui-url @sess)))
            (is (= (:url r) (:ui (ops/session-brief sess)))))
          (testing "starting the UI does NOT make the brief claim a hub — a
                    :hub in the brief is a claim that something ANSWERS
                    there. This assertion used to be `(swap! sess assoc
                    :hub …)` followed by reading it back, which proved only
                    that assoc works; the claim it stood in for was false,
                    because `:hub` was set from the CONFIGURED port whether
                    or not anything answered. It then asserted `nil`, which
                    is only true on a machine with no hub up — and a hub
                    running here (slopp-ui's, most days) turned every
                    whole-store check red for a fact about the machine. So:
                    absent, or answering."
            (let [b (ops/session-brief sess)
                  h (:hub b)
                  answers? (fn [url]
                             (let [u (java.net.URI. (str url))]
                               (with-open [s (java.net.Socket.)]
                                 (try (.connect s (java.net.InetSocketAddress.
                                                   (.getHost u) (.getPort u)) 500)
                                      true
                                      (catch java.io.IOException _ false)))))]
              (is (or (nil? h) (answers? h))
                  (str "the brief claims a hub nothing answers at: "
                       (pr-str (select-keys b [:ui :hub]))))))
          (server/stop!)))
      (finally
        (server/stop!)
        (ops/close! sess)))))

(deftest targets-accepts-the-shapes-a-reader-would-try
  ;; The reporting half of a boundary crossing: a refusal must speak the
  ;; AUTHOR's vocabulary.
  ;; `query_source {targets}` accepted only [{:ns … :name …}] and died on
  ;; ["ns/name"] — the shape the tool index's own `{targets}` shorthand
  ;; suggests — with `no conversion to symbol`, naming an internal call the
  ;; caller never made and saying nothing about what IS accepted.
  ;;
  ;; Being liberal here is the better fix than a better error: "ns/name" is
  ;; unambiguous, so refusing it taught a rule that did not need to exist.
  (testing "the map shape, unchanged"
    (is (= [{:ns 'a.b :name 'c}] (mcp/normalize-targets [{:ns "a.b" :name "c"}])))
    (is (= [{:ns 'a.b}] (mcp/normalize-targets [{:ns "a.b"}]))))
  (testing "a qualified string is the same thing, and now lands"
    (is (= [{:ns 'a.b :name 'c}] (mcp/normalize-targets ["a.b/c"])))
    (is (= [{:ns 'a.b}] (mcp/normalize-targets ["a.b"])))
    (testing "including names that are legal Clojure and awkward to parse"
      (is (= [{:ns 'a.b :name 'swap!}] (mcp/normalize-targets ["a.b/swap!"])))
      (is (= [{:ns 'a.b :name '->rec}] (mcp/normalize-targets ["a.b/->rec"])))))
  (testing "symbols too — an agent writing EDN reaches for those first"
    (is (= [{:ns 'a.b :name 'c}] (mcp/normalize-targets ['a.b/c]))))
  (testing "a shape it cannot use is REFUSED, naming what is accepted"
    ;; not silently dropped: a target that vanishes reads as \"that form has
    ;; no source\", which is a different and false answer
    (let [e (try (mcp/normalize-targets [42]) nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (re-find #"\{:ns" (ex-message e)) (ex-message e))
      (is (re-find #"\"[a-z.]+/[a-z]+\"" (ex-message e)) (ex-message e))
      (is (re-find #"42" (ex-message e)) "and what it actually got"))))

(deftest refusal-text-is-the-one-derivation-of-both-refusal-facts
  ;; `:refused?` and the message the turn record samples are the same
  ;; question asked twice, and Pattern 2 in the failure log is four instances
  ;; of exactly that shape drifting apart. So the predicate IS the extraction:
  ;; a refusal is a call that has text to show for it.
  (testing "slopp's own refusal-as-data — the payload opens with {:error"
    (is (= "{:error \"no match for `(inc x)` in my.app.orders/place!\"}"
           (#'mcp/refusal-text
            {:content [{:text "{:error \"no match for `(inc x)` in my.app.orders/place!\"}"}]}))))
  (testing "a thrown exception, which the dispatcher already marked"
    (is (= "error: boom"
           (#'mcp/refusal-text {:isError true :content [{:text "error: boom"}]}))))
  (testing "a result whose first key merely STARTS with :error is not a refusal"
    ;; `{:errors 0` starts with `{:error`. Every external test run opens its
    ;; map with `:errors`, so a prefix test on `{:error` counted all of them —
    ;; GREEN ones included. Measured on this store before the fix: 461 of 1465
    ;; recorded refusals were `test_run`, making it the most-refused tool by a
    ;; wide margin and putting "agents reach for a redundant test ritual" into
    ;; a performance plan. There was no such habit; the meter was reading its
    ;; own prefix. A waste metric that invents waste sends someone to fix a
    ;; thing that is not broken, which is worse than under-counting.
    (is (nil? (#'mcp/refusal-text
               {:content [{:text (str "{:errors 0, :exit 0, :external true,"
                                      " :status :green, :ran 2}")}]}))
        "a GREEN external run was being recorded as a refused call")
    (is (nil? (#'mcp/refusal-text
               {:content [{:text "{:errors 1, :exit 1, :failures 3}"}]}))
        "a RED run is a test failure, not a refused call — nothing bounced"))
  (testing "a clean result is nil, so the predicate is the extraction"
    (is (nil? (#'mcp/refusal-text {:content [{:text "{:ok true, :delta \"d1\"}"}]})))
    (is (nil? (#'mcp/refusal-text {}))))
  (testing "an :isError with no text still reads as refused, never as clean"
    ;; the under-count is deliberate elsewhere; losing a KNOWN refusal
    ;; because it happened to be quiet is not the same thing
    (is (some? (#'mcp/refusal-text {:isError true})))))

(deftest an-already-sent-stub-must-not-outlive-the-reader-it-is-about
  ;; `told!`'s guarantee — "identical to what this session already received" —
  ;; is true of the SERVER session and false of the reader. The server session
  ;; lives for the process; the reader resets on /clear, on automatic
  ;; compaction, and on every subagent. Hit twice for real: six read-only
  ;; calls came back as stubs to a context that had just been cleared, and
  ;; again mid-build after an automatic /compact — so it is not something a
  ;; user can avoid by not typing a command.
  ;;
  ;; This is not staleness (that part of the docstring holds); it is
  ;; WITHHOLDING. Absence-of-payload shares a representation with
  ;; absence-of-change: "I am not telling you" presented as "you already
  ;; know".
  ;;
  ;; The ASK is the right boundary and the turn is not: turns rotate on the
  ;; write-tool gate, so a read-only planning ask never rotates one — and a
  ;; read-only planning ask is exactly where this was first hit.
  (let [sess    (atom {})
        ;; big enough that withholding it is a real saving — see the sibling
        ;; test: the stub is only used when it is smaller than the payload
        payload {:routes (vec (range 200))}]
    (testing "a re-read inside ONE ask still stubs — that saving is the point"
      ;; reads are 52% of all output and stable whole-store views are the fat;
      ;; the fix must narrow the withholding, not delete it
      (is (= payload (#'mcp/told! sess "query_surface" {} payload)))
      (is (:already-sent (#'mcp/told! sess "query_surface" {} payload))))
    (testing "a NEW ASK re-tells it, because the reader may be new"
      (swap! sess update :slopp.mcp/ask (fnil inc 0))
      (is (= payload (#'mcp/told! sess "query_surface" {} payload))))
    (testing "and it stubs again within that new ask"
      (is (:already-sent (#'mcp/told! sess "query_surface" {} payload))))))

(deftest an-already-sent-stub-carries-a-way-back-to-the-payload
  ;; Expiring at the ask boundary covers /clear and compaction, but not a
  ;; SUBAGENT: it shares the server session, runs inside the parent's ask, and
  ;; has seen nothing. So there must still be an in-band way out — and today
  ;; there is none, because `query_detail` only spools TRIMMED responses, not
  ;; stubs. A read-only tool's payload was reachable only by calling a
  ;; write-capable one (`config_file`), which prompts for permission in plan
  ;; mode: the detour that made this cost a whole planning turn.
  (let [sess    (atom {})
        ;; big enough that withholding it is a real saving — the stub is only
        ;; used when it is SMALLER than what it withholds, so a toy payload
        ;; now (correctly) comes back in full
        payload {:routes (vec (range 200))}]
    (#'mcp/told! sess "query_surface" {} payload)
    (let [stub (#'mcp/told! sess "query_surface" {} payload)]
      (is (:already-sent stub))
      (is (string? (:detail stub)) "the stub names its own escape")
      (is (= (pr-str payload)
             (get-in @sess [:slopp.mcp/spool :entries (:detail stub)]))
          "and the id resolves in the spool query_detail already reads"))))

(deftest ^:external a-new-ask-re-tells-a-view-the-session-already-sent
  ;; The in-image cover pins `told!`'s scoping; this pins the SEAM — that the
  ;; ask counter is actually bumped by the thing that records the prompt. A
  ;; rule implemented at the wrong call site is this codebase's Pattern 2, and
  ;; the tell is always that two surfaces disagree while each looks right
  ;; alone.
  ;;
  ;; Read-only on purpose. Turns rotate on the write-tool gate, so nothing
  ;; here rotates a turn — and the withholding was first hit in a read-only
  ;; planning ask, which is the case a turn-scoped fix would have missed.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-retell"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})
        ask! (fn [prompt]
               (spit (io/file dir ".slopp" "pending-intent")
                     (str "{\"session-id\":\"sess-retell\",\"prompt\":\"" prompt "\"}")))]
    (try
      (io/make-parents (io/file dir ".slopp" "pending-intent"))
      (ask! "first ask: what rules are on")
      (let [one (call! sess "query_rules" {})]
        (is (not (str/includes? one "already-sent")) "the first read is the payload")
        (testing "a re-read inside the SAME ask is the stub — the saving stands"
          (is (str/includes? (call! sess "query_rules" {}) "already-sent"))))
      (testing "after a NEW ask the same read is the payload again"
        (ask! "second ask, fresh context: what rules are on")
        (let [again (call! sess "query_rules" {})]
          (is (not (str/includes? again "already-sent"))
              "a cleared or compacted reader has seen none of this")
          (is (str/includes? again "rules") (subs again 0 (min 200 (count again))))))
      (testing "and the stub, when it does appear, names the door out"
        (let [stub (call! sess "query_rules" {})]
          (is (str/includes? stub "already-sent"))
          (is (str/includes? stub ":detail")
              "a read-only tool's withheld payload must not need a write-capable one")))
      (finally (ops/close! sess)))))

(deftest ^:external store-doctor-rides-the-wire-read-only
  ;; The legacy sweep. Its population is an ADOPTED store — code that came in
  ;; through git_clone/import and predates every rule slopp has — so the
  ;; detectors are pinned on fixtures in `api.doctor-test`, and what this
  ;; asserts is the wiring plus the honest baseline.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'sd.core "(ns sd.core)\n(defn ^:unused-ok f \"F.\" [x] x)\n")
      (let [r (edn/read-string (call! sess "store_doctor" {}))]
        (testing "it answers, and says what it looked at"
          (is (pos? (:scanned r)) (pr-str r))
          (is (true? (:healthy r)) (pr-str r)))
        (testing "a store written THROUGH slopp is clean, which is the point"
          ;; every form here went through the write gates, so none of these
          ;; shapes could have got in — a clean result is the expected one and
          ;; is why the detectors cannot be validated from this store
          (is (= [] (:unmanaged-declares r)))
          (is (= [] (:duplicate-names r)))
          (is (= [] (:unknown-markers r)))))
      (testing "and it is read-only, so plan mode can auto-permit it"
        (is (contains? tools/read-only-tools "store_doctor")))
      (finally (ops/close! sess)))))

(deftest ^:external
  ^{:correspondence "call-tool!'s result-key selection vs mcp.tools/wire-keys — a literal select-keys list is a fresh chance to drop a key silently; four keys were built, tested and correct one layer down while the agent saw the old behaviour"}
  no-tool-keeps-its-own-result-key-allowlist
  ;; Four keys have been built, tested and correct one layer down while the
  ;; agent saw the old behaviour — `:dry-run`'s payload, `:drift`,
  ;; `:external-pending`, and a `:fix` hint. Each looked like a missing
  ;; feature rather than a dropped one, which is the worst shape a failure can
  ;; have. `summarize`'s docstring records three of them and asks for this.
  ;;
  ;; The rule: `call-tool!` selects result keys from ONE registry
  ;; (`tools/wire-keys`) and never from a literal list. A literal list is a
  ;; fifteenth chance to disagree about what reaches an agent.
  (let [st  (external/built-store)
        src (n/string (:node (store/form-named st 'slopp.mcp 'call-op!)))
        literal-lists (re-seq #"select-keys \[[^\]]+\]" src)]
    (testing "there is a population — this reads the real dispatch"
      (is (< 1000 (count src)) "call-op! source not found")
      (is (re-find #"wire-keys" src)
          "the dispatch does not mention the registry at all"))
    (testing "no hand-maintained allowlist survives"
      (is (empty? literal-lists)
          (str (count literal-lists) " tool(s) still select from a literal list"
               " instead of tools/wire-keys — each is a fresh chance to drop a"
               " key silently: " (pr-str (take 3 literal-lists)))))
    (testing "and the registry is not empty, which would pass vacuously"
      (is (< 20 (count tools/wire-keys)))
      (is (contains? tools/wire-keys :error)))))

(deftest ^:external the-project-listeners-description-promises-only-what-it-serves
  ;; ui_serve's description outlived the surface it described. It said "a
  ;; browsable HTML view of THIS store for a human: the namespace index, form
  ;; source, and … the milestone timeline and per-milestone change review",
  ;; and told the caller to hand that url to a human — for a listener that
  ;; answers 404 {"error":"no route"} at `/`. The pages moved to the hub with
  ;; D-hub part 4 and the description did not follow.
  ;;
  ;; Sibling of slopp-prose-never-names-a-tool-that-does-not-exist, and the
  ;; same class: a gate sees var references, never a promise made in prose.
  ;; Anchored to the FACT rather than to a wording, so the day a page endpoint
  ;; comes back this guard stops firing instead of having to be argued with.
  (doseq [n server/served-namespaces] (require n))
  (let [paths   (->> server/served-namespaces
                     (mapcat (comp vals ns-publics))
                     (keep (comp #(or (:rest/path %) (:http/path %)) meta))
                     sort vec)
        non-api (remove #(str/starts-with? % "/api") paths)
        desc    (:description (first (filter #(= "ui_serve" (:name %)) tools/registry)))]
    (testing "the FACT this rests on: the listener serves /api and nothing else"
      (is (seq paths) "served-namespaces declared no endpoints at all")
      (is (empty? non-api)
          (str "a non-/api route means the listener DOES serve a page again,"
               " and then this guard should be DELETED rather than satisfied: "
               (pr-str non-api))))
    (testing "so the description may not promise a human a page"
      (is (some? desc) "ui_serve is not in the registry")
      (is (empty? (re-seq #"(?i)HTML view|browsable|namespace index|form source|milestone timeline|change review"
                          desc))
          (str "ui_serve's description promises pages this listener does not"
               " serve: " (pr-str desc)))
      (is (re-find #"(?i)hub" desc)
          "and it must name the hub, which is where those pages actually are"))))

(deftest the-terse-path-drops-nothing-the-registry-routed
  ;; The sibling of no-tool-keeps-its-own-result-key-allowlist, one layer
  ;; later and with the same defect. call-tool! selects from wire-keys, and
  ;; then summarize REBUILDS the terse map from a literal key list — so a key
  ;; can pass the registry and die anyway, which is the fifteenth chance to
  ;; disagree that the sibling's comment warns about.
  ;;
  ;; Found by using it: edit_move_forms rewrites every caller in the store,
  ;; and reports {:ok true :group …} whether it rewrote twelve call sites or
  ;; none. :callers is computed, is in wire-keys, and never arrives. So "it
  ;; found nothing to rename" and "it never looked" are the same output, and
  ;; the only way to tell was to go read the implementation.
  ;;
  ;; The rule this pins is the registry's own argument: what a LAYER chooses
  ;; to return is the layer's decision, and the wire's job is to shape it, not
  ;; to re-decide it. An empty vector is a finding ("looked, none"); an absent
  ;; key reads as unexamined.
  ;; Looked up at RUNTIME, not compiled as `mcp/terse-elided`. A symbol
  ;; reference is resolved when this namespace loads, and on a COLD load that
  ;; happens before slopp.mcp has interned the var — which is what the merge
  ;; gate refused, twice. ns-publics asks when the test RUNS, by which point
  ;; everything is loaded. (`resolve` would be the obvious spelling and is
  ;; denylisted by the dialect gate.)
  (let [elided (deref (get (ns-publics 'slopp.mcp) 'terse-elided))
        routed (apply disj tools/wire-keys :error elided) ; these force the verbose path
        shaped {:test {:test 1 :pass 1 :fail 0 :error 0 :type :summary}
                :delta {:id "d1"} :deltas [{:id "d2"}] :affected [1 2]}
        ;; a COLLECTION sentinel: summarize seqs several of these, and a
        ;; keyword would throw rather than report the key missing
        result (into shaped
                     (map (fn [k] [k [::present]]))
                     (apply disj routed (keys shaped)))
        terse  (#'mcp/summarize result false)]
    (testing "the TERSE path ran — :ok is set only there, so this is not vacuous"
      (is (true? (:ok terse)))
      (is (< 20 (count routed)))
      (is (>= 2 (count elided))
          (str "terse-elided grew to " (pr-str elided)
               " — each addition is a key an agent silently stops seeing")))
    (testing "every key the registry routed survives the shaping"
      (is (empty? (remove #(contains? terse %) routed))
          (str "dropped: " (pr-str (vec (sort (remove #(contains? terse %) routed)))))))
    (testing "shaping still happens — the big payloads compress, not vanish"
      (is (= "d1" (:delta terse)))
      (is (= 1 (:deltas terse)))
      (is (= [1 2] (:affected terse))))))

(deftest the-app-server-comes-up-only-for-a-store-that-asked-for-it
  ;; This is the assertion that keeps the feature from being a menace. The
  ;; ^:external tier is four shards; if a session opened during a test run
  ;; auto-bound a derived port, they would fight over it, and the failure
  ;; would look like anything except a dev server nobody asked for.
  (let [web  (first (store/record-config-put (store/empty-store)
                                             "capabilities" :manifest
                                             "http.enabled" "true"))
        plain (atom {:store (store/empty-store) :dir "/tmp/slopp-no-such-dir"})]
    (testing "a store that serves no HTTP starts nothing"
      (is (nil? (mcp/start-app! plain)))
      (is (nil? (:app-server @plain))))
    (testing "an EPHEMERAL session starts nothing even when the store serves
              HTTP — there is no dir, and the address is derived from one"
      (let [eph (atom {:store web})]
        (is (nil? (mcp/start-app! eph)))
        (is (nil? (:app-server @eph)))))
    (testing "a store THIS PROCESS already serves starts nothing — the one
              exemption, and it is derived rather than configured"
      ;; slopp's own store is this case and the only one: its surface is the
      ;; reviewer API the live session already serves, so a managed copy
      ;; would be a second, staler one. It used to be the `dev.server`
      ;; capability, which asked every project a question only this store
      ;; should answer — and the one adopter that answered it did so to work
      ;; around 404ing assets, which the switch then hid.
      (let [off (atom {:store (store/ingest
                               web 'slopp.api.reads
                               (str "(ns slopp.api.reads)\n\n"
                                    "(defn ^{:http/method :get :http/path \"/api/x\"\n"
                                    "        :malli/schema [:=> [:cat :map] :map]\n"
                                    "        :rest/response :map} x \"X.\" [req] {:ok true})\n"))
                       :dir "/tmp/slopp-no-such-dir"})]
        (is (nil? (mcp/start-app! off)))
        (is (nil? (:app-server @off)))))
    (testing "and a done point on an unmanaged store refreshes nothing, so
              the gate is not something only the startup path applies"
      ;; the same predicate at both call sites: a gate that guards the start
      ;; and not the refresh is a gate that opens on the second done
      (is (nil? (mcp/refresh-app! plain))))))

(deftest opting-out-stops-a-server-that-is-already-running
  ;; Measured by slopp-ui, 2026-08-01: after opting out (verified in the
  ;; config at the time), `session_brief` still reported
  ;; `:app http://127.0.0.1:51614/` and the url still answered. The config
  ;; said the managed server did not exist and the surface advertised it
  ;; anyway.
  ;;
  ;; The cause is that the gate guarded the wrong verb: `refresh-app!`
  ;; checked `managed?` and returned, which stops RE-SERVING and never stops
  ;; SERVING. Ceasing to be managed has to produce an ACTION, not the
  ;; absence of one — and that is MORE true now the exemption is derived,
  ;; because a store can become self-served by a write rather than by
  ;; someone deliberately flipping a switch.
  (let [put  (fn [st k v] (first (store/record-config-put st "capabilities"
                                                          :manifest k v)))
        off  (-> (store/empty-store)
                 (put "http.enabled" "true")
                 (store/ingest 'slopp.api.reads
                               (str "(ns slopp.api.reads)\n\n"
                                    "(defn ^{:http/method :get :http/path \"/api/x\"\n"
                                    "        :malli/schema [:=> [:cat :map] :map]\n"
                                    "        :rest/response :map} x \"X.\" [req] {:ok true})\n")))
        ;; no :image on the handle — live/stop! tolerates a handle with
        ;; no process, which is what keeps this test in-image instead of
        ;; costing a JVM to assert bookkeeping
        sess (atom {:store off :dir "/tmp/slopp-no-such-dir"
                    :app-server {:serving? true :url "http://127.0.0.1:51614/"}})
        r    (mcp/refresh-app! sess)]
    (testing "the running server is stopped and the session stops carrying it"
      ;; session_brief reads :app off this key, so a stale one IS the wrong
      ;; url being advertised
      (is (nil? (:app-server @sess))))
    (testing "and it SAYS so, because a silent stop reads as 'this project
              never had a managed server'"
      (is (false? (:serving? r)))
      (testing "naming WHICH of the two reasons applied — `managed?` is false
                for a store that serves no HTTP and for one already served
                here, and a stop that names the wrong one sends someone to
                change the wrong thing"
        (is (re-find #"already serves" (str (:reason r))) (pr-str r))))
    (testing "a store that was never managed and has nothing running stays
              silent — most stores are not web projects, and a line at every
              done point saying so is how a report stops being read"
      (is (nil? (mcp/refresh-app! (atom {:store (store/empty-store)
                                         :dir "/tmp/slopp-no-such-dir"})))))))

(deftest done-says-when-the-re-serve-it-triggered-broke-the-app
  ;; slopp-ui: "an agent can run `done`, get a clean report, and have just
  ;; taken its own app server down without being told." The re-serve happens
  ;; AT the done point and the failure appeared only in session_brief — a
  ;; different call an agent has no reason to make. Action and announcement
  ;; living in separate calls.
  (testing "a failed re-serve is news, and carries the reason"
    (let [n (#'mcp/app-note-for {:serving? false
                                 :reason "port 7999 is already in use — free it, or set http.port to another"})]
      (is (some? n))
      (is (str/includes? n "port 7999 is already in use") n)
      (is (str/includes? n "DOWN") (str "the state has to be unmissable: " n))))
  (testing "a healthy re-serve says nothing"
    (is (nil? (#'mcp/app-note-for {:serving? true :port 7359 :url "http://127.0.0.1:7359/"}))))
  (testing "a store slopp does not run says nothing — most stores are not web projects"
    (is (nil? (#'mcp/app-note-for nil))))
  (testing "a DELIBERATE stop is not a failure and must not read as one"
    ;; the two opt-out branches return :serving? false with a reason that
    ;; describes a correct outcome. Reporting those as breakage would train
    ;; the reader to skim the line that matters.
    (is (nil? (#'mcp/app-note-for
               {:serving? false :stopped true
                :reason "http.enabled is false for this store — the managed app server was stopped"})))))

(deftest ^:external
  ^{:correspondence "mcp.tools/tools (advertised at initialize) vs the dispatch keys in slopp.mcp — related by nothing but a string literal; an advertised tool with no handler answers \"unknown tool: X\" naming X itself"}
  every-advertised-tool-has-a-handler-that-names-it
  ;; The other half of `slopp-prose-never-names-a-tool-that-does-not-exist`.
  ;; That guard catches prose naming a tool that is not on the wire; this one
  ;; catches the reverse — a tool ADVERTISED in `tools/tools` with nothing in
  ;; `slopp.mcp` to run it. `call-tool!`'s fallthrough turns that into
  ;; "unknown tool: X. Available: …" listing X itself, which is the least
  ;; helpful error in the system, and it fires only when someone calls it.
  ;;
  ;; Nothing else checks this: the tool LIST and the dispatch are two literals
  ;; in two namespaces, related by a string. Adding a tool is two writes, and
  ;; a green suite after the first one says nothing is wrong.
  ;;
  ;; Matched on EXACT string equality, not on mention: a case branch and a
  ;; handler-map key are both the bare literal `"ns_realias"`, while prose
  ;; explaining a tool spells it inside a sentence. So this asks "is this name
  ;; used as a dispatch key here", not "is it talked about".
  (let [st       (external/built-store)
        literals (into #{}
                       (for [e    (store/forms st 'slopp.mcp)
                             :let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                             x    (tree-seq coll? seq s)
                             :when (string? x)]
                         x))
        advertised (into #{} (map :name) tools/registry)
        orphans    (vec (sort (remove literals advertised)))]
    (testing "there is a POPULATION — the sibling guard above spent its whole
              life scanning an empty store, and the fix is the same one line"
      (is (< 40 (count advertised))
          (str "expected slopp's tool list, got " (count advertised)))
      (is (< 100 (count literals))
          (str "expected slopp.mcp's string literals, got " (count literals))))
    (testing "the detector still fires — a check that cannot fail is not a check"
      (is (contains? literals "ns_rename") "a real dispatch key must be found")
      (is (not (contains? literals "ns_rename_nonexistent"))
          "and a name nothing dispatches must not be"))
    (is (empty? orphans)
        (str "tools/tools advertises " (count orphans) " tool(s) slopp.mcp"
             " does not dispatch — calling one returns \"unknown tool\" naming"
             " the tool itself: " (pr-str orphans)))))

(deftest a-tool-declares-whether-it-writes-where-it-is-DEFINED
  ;; `read-only-tools` was a hand-kept set of 32 name strings, three hundred
  ;; lines from the descriptors it classified. Measured before the change: 90
  ;; advertised, 32 read-only, and ZERO stale entries — the list happened to be
  ;; correct, and nothing made it stay that way.
  ;;
  ;; The cost was the second write. Adding a read-only tool meant naming it in
  ;; its group AND in the set, and forgetting the second is the quietest
  ;; failure in the system: the tool works, no test goes red, and the client
  ;; prompts for permission forever — which is indistinguishable from normal
  ;; behaviour. Guarded, before this, by one test per tool: 5 tests for 35
  ;; members.
  ;;
  ;; The group already carries the fact. Measured: orientation 20/20 read-only,
  ;; history 3/3, edit 0/23, and NINE exceptions across the other three. So the
  ;; classification lives where the tool is defined, and the derived set
  ;; replaces the list.
  (testing "a group resolves its default onto every entry"
    (is (= [true true]   (map :read-only (tools/classify [{:name "a"} {:name "b"}] :read-only true))))
    (is (= [false false] (map :read-only (tools/classify [{:name "a"} {:name "b"}] :read-only false)))))
  (testing "an entry that states its own classification overrides the group default"
    ;; the nine exceptions — help, the file/deps reads, store_health/doctor,
    ;; query_commits/query_git — sit in write-shaped groups
    (is (= [true]  (map :read-only (tools/classify [{:name "help" :read-only true}] :read-only false))))
    (is (= [false] (map :read-only (tools/classify [{:name "x" :read-only false}] :read-only true)))))
  (testing "read-only-tools is DERIVED, so it cannot fall behind the descriptors"
    (is (= tools/read-only-tools
           (into #{} (comp (filter :read-only) (map :name)) @#'tools/classified))))
  (testing "every advertised tool carries a resolved boolean — none is unclassified"
    (is (every? #(boolean? (:read-only %)) @#'tools/classified))
    (is (= (count tools/registry) (count @#'tools/classified))))
  (testing "and the marker NEVER reaches the wire — tools/tools is serialized as-is"
    (is (not-any? #(contains? % :read-only) tools/registry)
        "an MCP descriptor must carry no key the protocol does not define")
    (is (some #(= {:readOnlyHint true} (:annotations %)) tools/registry)
        "the annotation the marker exists to produce is still set"))
  (testing "the classification itself did not change — this is a refactor"
    ;; positive control on the refactor: same answer, different home
    (is (= 36 (count tools/read-only-tools)))
    (is (contains? tools/read-only-tools "query_store"))
    (is (contains? tools/read-only-tools "store_doctor"))
    (is (not (contains? tools/read-only-tools "ui_serve")))
    (is (not (contains? tools/read-only-tools "edit_add_form")))))

(deftest a-tool-declares-whether-it-needs-the-image-where-it-is-DEFINED
  ;; `image-free-tools` is the SECOND of the four registries the tool surface
  ;; was: 25 name strings sitting three hundred lines from the descriptors they
  ;; classify, with nothing checking the two agree. `read-only-tools` was the
  ;; first and collapsed the same way.
  ;;
  ;; Its failure modes are ASYMMETRIC, and its own docstring says so: a tool
  ;; wrongly EXCLUDED merely waits for the async image boot — silent and slow —
  ;; while one wrongly INCLUDED touches a not-yet-live image. So the dangerous
  ;; direction is loud and the quiet direction is the one a second write is
  ;; likely to forget, which is exactly the read-only shape one notch milder.
  ;;
  ;; Measured: the GROUP carries this fact too — orientation 15/20 image-free,
  ;; history 3/3, edit 0/23 — with TWELVE exceptions. The five in orientation
  ;; are the oracle tools the docstring already names, because they eval IN the
  ;; image.
  ;;
  ;; The two facts share defaults per group and diverge per entry (`query_eval`
  ;; is read-only and NOT image-free), so they stay two dials. Collapsing them
  ;; into one because today's defaults coincide would be the same false economy
  ;; this exercise exists to undo.
  (testing "a group resolves its default onto every entry, per FACT"
    (is (= [true true]   (map :image-free (tools/classify [{:name "a"} {:name "b"}] :image-free true))))
    (is (= [false false] (map :image-free (tools/classify [{:name "a"} {:name "b"}] :image-free false))))
    (is (= [true] (map :read-only (tools/classify [{:name "a"}] :read-only true)))
        "the same classifier carries the other fact — one mechanism, not two"))
  (testing "an entry that states its own classification overrides the group"
    (is (= [false] (map :image-free (tools/classify [{:name "query_eval" :image-free false}] :image-free true)))))
  (testing "image-free-tools is DERIVED, so it cannot fall behind the descriptors"
    (is (= tools/image-free-tools
           (into #{} (comp (filter :image-free) (map :name)) @#'tools/classified))))
  (testing "every advertised tool carries a resolved boolean for BOTH facts"
    (is (every? #(boolean? (:image-free %)) @#'tools/classified))
    (is (every? #(boolean? (:read-only %))  @#'tools/classified)))
  (testing "and neither marker reaches the wire"
    (is (not-any? #(contains? % :image-free) tools/registry))
    (is (not-any? #(contains? % :read-only) tools/registry)))
  (testing "the classification did not change — this is a refactor"
    ;; positive control: same answer, different home
    (is (= 27 (count tools/image-free-tools)))
    (is (contains? tools/image-free-tools "session_brief"))
    (is (contains? tools/image-free-tools "query_git") "a sync-group exception")
    (is (not (contains? tools/image-free-tools "query_eval"))
        "the oracle tools eval IN the image, which is the whole distinction")
    (is (not (contains? tools/image-free-tools "edit_add_form")))))

(deftest ^:external a-refused-auto-publish-carries-its-diagnosis
  ;; The publish inside commit_point is the AUTOMATIC one — it is where a
  ;; refusal is met by someone who did not ask for a push and has no idea what
  ;; a mirror is. On 2026-08-14 one of these cost a full investigation because
  ;; the report said REJECTED_NONFASTFORWARD and stopped. The diagnosis exists
  ;; now; this pins that it survives the trip to the caller, which is the half
  ;; that is easy to lose — the report is assembled with select-keys.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-pubdiag" (make-array java.nio.file.attribute.FileAttribute 0)))
        _    (sh/sh "git" "init" dir)
        _    (sh/sh "git" "-C" dir "-c" "user.name=t" "-c" "user.email=t@t"
                    "commit" "--allow-empty" "-m" "root")
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (call! sess "ns_create" {:ns "pub.core" :source "(ns pub.core)\n(defn ^:unused-ok f [x] x)\n"})
      (call! sess "commit_point" {:label "first"})
      ;; someone else's history under the mirror ref: the checkout's own root
      ;; commit, which the projection never minted and does not build on
      (let [root (clojure.string/trim
                  (:out (sh/sh "git" "-C" dir "rev-parse" "HEAD")))]
        (sh/sh "git" "-C" dir "branch" "-f" "slopp/main" root)
        (call! sess "ns_create" {:ns "pub.two" :source "(ns pub.two)\n(defn ^:unused-ok g [] 2)\n"})
        (let [r (call! sess "commit_point" {:label "second"})]
          (is (re-find #"REJECTED_NONFASTFORWARD" r)
              (str "the refusal itself still leads: " r))
          (is (re-find #":divergence" r)
              (str "and the diagnosis rides with it rather than dying in the process: " r))
          (is (re-find #":cause :unrelated" r)
              (str "named, not merely dumped — the mirror holds a history sharing no"
                   " base with the projection: " r))
          (is (re-find (re-pattern root) r)
              (str "including the tip that is about to become unreachable: " r))))
      (finally (ops/close! sess)))))

(deftest ^:external a-write-says-when-your-work-is-still-private
  ;; The gap threads left: `session_brief` names your thread, and nothing else
  ;; does — so between orienting and done, the fact that nobody can see your
  ;; work is true and unstated. A long episode is exactly where that matters
  ;; and exactly where the brief has scrolled away.
  ;;
  ;; The shape has to earn its noise: once when it BECOMES true, then only as
  ;; the stake grows, and never for a call that changed nothing.
  ;;
  ;; Every check below has a REAL write in front of it. The first version of
  ;; this test asked twice in a row with nothing in between, so "the second
  ;; write stays quiet" was really "no second write happened" — a true
  ;; assertion about nothing, and it would have stayed green against a rule
  ;; that fires on every single write.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-hint-" (System/nanoTime))]
    (try
      (let [sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "hinted"})
            add! (fn [i] (ops/add-form! sess 'hi.core
                                        (str "(defn ^:unused-ok g" i " [] " i ")")
                                        :agent "hinted"))]
        (try
          (ops/ingest! sess 'hi.core "(ns hi.core)\n(defn ^:unused-ok f [] 1)\n"
                       :agent "hinted")

          (testing "the write that makes the work private says so"
            (let [h (mcp/thread-hint! sess "edit_add_form")]
              (is (string? h) "a hint fired")
              (is (re-find #"done" h) "and it names the way out")))

          (testing "the next write does not — one line per stake, not per call"
            (add! 0)
            (is (nil? (mcp/thread-hint! sess "edit_add_form"))))

          (testing "and a READ stays quiet for the honest reason: nothing changed"
            (is (nil? (mcp/thread-hint! sess "query_source"))))

          (testing "it returns as the stake grows"
            (dotimes [i 30] (add! (inc i)))
            (let [h (mcp/thread-hint! sess "edit_add_form")]
              (is (string? h) "a long episode is reminded again")
              (is (re-find #"\d" h) "and it says how much is riding on it")))

          (testing "a landed thread has nothing to say"
            (is (= "main" (:landed (branch/land-thread! sess))))
            (add! 99)
            (is (nil? (mcp/thread-hint! sess "edit_add_form"))
                "the count restarted with the fresh thread, so the next reminder
                 is a real one rather than a leftover"))
          (finally (ops/close! sess))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external a-milestone-re-serves-the-app-the-way-a-done-does
  ;; slopp-ui, 2026-08-16, with the ordering measured at the time: they enabled
  ;; `http` in a session that had booted with it OFF, restarted, ran
  ;; `full_check`, then `commit_point`. Afterwards nothing was listening —
  ;; connection refused, `lsof` showing no process on the port at all. Only a
  ;; process restart brought the app up.
  ;;
  ;; The cause is one call site. `refresh-app!` runs on the `done` TOOL;
  ;; `commit_point` PERFORMS a done — it runs the whole done pipeline and
  ;; appends a milestone — and its handler never refreshed. So a session that
  ;; migrates config and goes straight to a milestone, which is the natural
  ;; order and the one they took, never re-serves.
  ;;
  ;; This is the SAME defect `refresh-app!`'s own docstring already records one
  ;; call site over: *"a gate on the startup path only would let the second done
  ;; point start what the first one declined to"*. Gating the verb in one place
  ;; and not the other is how the feature arrives — or fails to — by accident.
  ;;
  ;; Asserted on the CALL rather than on a socket: whether re-serving works is
  ;; `live/refresh!`'s business and is tested there. What was missing here was
  ;; that it is invoked at all.
  (let [sess  (external/open!)
        calls (atom [])]
    (try
      (with-redefs [mcp/refresh-app! (fn [_] (swap! calls conj :refreshed) nil)]
        (call! sess "done" {:label "the grain that already worked"})
        (is (= [:refreshed] @calls)
            "guard the guard: the done tool refreshes, so a miss below is the
             milestone path and not the redef")

        (reset! calls [])
        (call! sess "commit_point" {:label "a milestone is a done point too"})
        (is (= [:refreshed] @calls)
            "a milestone runs a done and must re-serve like one — otherwise the
             app a project exists to serve is left behind by the very call that
             says the work is finished"))
      (finally (ops/close! sess)))))

(deftest a-trimmed-SEQUENCE-says-so-inside-the-payload
  ;; The response gate degrades rather than refusing, which is right — a big
  ;; read is still a useful read, and `query_project` on a 200-namespace store is
  ;; over the gate by construction. The defect was never the degrading. It was
  ;; that the degrading announced itself only in a trailing LINE, outside the
  ;; edn, so anything reading the response as DATA got a well-formed value with
  ;; items missing and no in-band signal.
  ;;
  ;; Measured: `query_rules` is ~23k against an 8000-char gate and delivers 17 of
  ;; 43 rules. An agent asking "what is enforced here" was answered with 40% of
  ;; the catalog, and the only thing that said so was a line a parser skips.
  ;;
  ;; slopp-ui's fix, taken almost verbatim. My objection had been that a marker
  ;; ELEMENT poisons `(map :rule …)`; their counter is the half I had not
  ;; weighed — it poisons it with ONE nil at the end, which is more visible than
  ;; silently losing 26 rows, and the comparison is against today rather than
  ;; against a clean answer.
  (let [rows (vec (for [i (range 400)]
                    {:rule (keyword (str "r" i))
                     :teach "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy"}))
        fit  (#'mcp/fit-payload rows 2000)]
    (testing "the body still parses, and its LAST element says what was dropped"
      (let [parsed (edn/read-string (:body fit))
            marker (:truncated (last parsed))]
        (is (vector? parsed))
        (is (some? marker)
            (str "a consumer reading this as data has to be able to SEE the "
                 "trim: " (pr-str (take-last 2 parsed))))
        (is (= 400 (:of marker)))
        (is (= (dec (count parsed)) (:shown marker))
            "the count counts ROWS, not the marker — a marker that counted
             itself would be off by one in the direction that hides a loss")))

    (testing "and it still fits the budget it was given"
      ;; the marker costs characters, so reserving room for it is part of the
      ;; fit rather than an afterthought — otherwise announcing the trim is
      ;; what pushes the payload back over the gate
      (is (<= (count (:body fit)) 2000)
          (str "body was " (count (:body fit)) " chars")))

    (testing "the trailing note survives too, for a human skimming"
      (is (re-find #"\d+ of \d+ shown" (str (:note fit))) (:note fit))))

  (testing "a payload that FITS gains no marker"
    ;; the guard against over-reach: a marker on every response would train the
    ;; reader to skip it, which is exactly how the trailing line came to be
    ;; ignored. Nothing dropped, nothing to say.
    (let [f (#'mcp/fit-payload [{:a 1} {:b 2}] 2000)]
      (is (= [{:a 1} {:b 2}] (edn/read-string (:body f)))
          (str "an untrimmed payload is itself, unmarked: " (:body f)))))

  (testing "a MAP payload keeps its trailing-note behaviour"
    ;; a map cannot carry a marker element, and its keys are not a sequence a
    ;; consumer maps over, so the shapes are treated differently on purpose
    (let [m (into {} (for [i (range 400)] [(keyword (str "k" i)) "vvvvvvvvvvvvvvvvvvvv"]))
          f (#'mcp/fit-payload m 2000)]
      (is (map? (edn/read-string (:body f))))
      (is (re-find #"keys shown" (str (:note f))) (:note f)))))

(deftest ^:external one-SECTION-cannot-take-the-whole-surface-down
  ;; `webapp-report`'s docstring already says no MEMBER may take a report down,
  ;; citing `rules.rest/contracts-report`, where one contract that threw made
  ;; nine endpoints unreadable. It happened one level up: a declaration naming a
  ;; var instead of a literal threw inside the webapp section, and took `:cli`,
  ;; `:http` and `:rest` with it — capabilities that have nothing to do with
  ;; browser apps.
  ;;
  ;; That specific bug is fixed and guarded at its own grain. This is the
  ;; ISOLATION, which is a different claim and the one that survives the next
  ;; bug: a section that cannot be read costs its own rows and names itself.
  ;;
  ;; Broken deliberately rather than by finding another defect, because a test
  ;; that needs a real bug to demonstrate resilience stops testing resilience
  ;; the moment the bug is fixed.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "this store serves")
      (ops/ingest! sess 'shopq.api
                   (str "(ns shopq.api)\n\n"
                        "(defn ^{:http/method :get :http/path \"/api/things\"\n"
                        "        :http/auth :public :rest/response :string}\n"
                        "  things \"T.\" [_] {:status 200 :body \"[]\"})\n"))

      (testing "the control: with nothing broken, http is reported and nothing complains"
        (let [r (query/query-surface sess)]
          (is (seq (:http r)) (pr-str r))
          (is (nil? (:unreadable r)) (pr-str r))))

      (testing "a section that THROWS costs its own rows and nothing else"
        (with-redefs [slopp.rules.webapp/webapp-report
                      (fn [_] (throw (ex-info "deliberate" {})))]
          (let [r (query/query-surface sess)]
            (is (seq (:http r))
                (str "one capability's failure erased another's surface: "
                     (pr-str r)))
            (is (seq (:unreadable r))
                (str "and it did so SILENTLY, which is worse than the throw — a"
                     " section quietly absent reads as a store that declares"
                     " nothing there: " (pr-str r)))
            (is (re-find #"(?i)webapp" (str (:unreadable r)))
                (str "the report must name WHICH section it could not read: "
                     (pr-str (:unreadable r)))))))

      (finally (ops/close! sess)))))

(deftest ^:external a-one-shot-CALL-says-when-another-thread-holds-work-it-cannot-see
  ;; `--call` opens its own durable session, so it reads the BRANCH. A session
  ;; writing through MCP reads its own THREAD. When the thread holds un-landed
  ;; work the two disagree, and nothing said so — the CLI answer looks
  ;; authoritative because it IS authoritative, about a different store.
  ;;
  ;; Measured cost, this session: roughly an hour. A write-path gate refused a
  ;; form; I reproduced the gate over the CLI, got CLEAN, and filed the
  ;; contradiction as a mystery. The gate was judging a half-renamed session and
  ;; the CLI was judging the branch. Both readings were correct. The consuming
  ;; store said it would have made the identical mistake and had avoided it only
  ;; by habit.
  ;;
  ;; SILENT AT ZERO on purpose: a CI run or a fresh clone has no other threads
  ;; and gets nothing, so the note appears exactly when it is the answer.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-callnote-" (System/nanoTime))]
    (try
      (let [sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "holder"})]
        (try
          (ops/ingest! sess 'cn.core "(ns cn.core)\n\n(defn f \"F.\" [x] x)\n")
          (testing "the fixture really is un-landed work on a thread"
            ;; population control: with nothing un-landed there is no
            ;; disagreement to warn about, and the assertion below would be
            ;; asserting the absence of a note for the wrong reason
            (is (pos? (:unlanded (first (filter :mine (:threads (branch/thread-list sess))))))))

          (let [out (str/join "\n" (map :text (:content (mcp/call! dir "query_project" {:agent "other"}))))]
            (testing "the one-shot read SAYS it cannot see that thread"
              (is (re-find #"(?i)un-?landed" out)
                  (str "a one-shot read gave no sign that another thread holds work"
                       " it cannot see, so it reads as the whole truth: " out))))
          (finally (ops/close! sess))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest a-re-serve-that-did-not-FINISH-says-so-rather-than-nothing
  ;; slopp-ui, 2026-08-23: a done went green and the app image was not
  ;; replaced, and the store served old code for twenty minutes while every
  ;; surface said fine. The cause of the miss is n=1 and they correctly refused
  ;; to promote their correlation to one. **The defect is that the miss is
  ;; invisible**, and that half is reproducible by reading the code.
  ;;
  ;; `done` waits on the re-serve with a 20s bound and reports `nil` on
  ;; expiry — "say nothing rather than guess". But nil is ALSO what "this store
  ;; is not one slopp runs" and "it came back up" report, so three outcomes
  ;; share one silence and two of them are fine. A reader cannot tell a
  ;; re-serve that did not happen from one that did.
  ;;
  ;; Guessing was never the alternative. Saying WHICH question went unanswered
  ;; is: the same `:not-swept` move as everywhere else — name what you declined
  ;; to report, so an absence is a statement instead of a gap.
  (testing "the bound expiring is NEWS, and names where the answer will appear"
    (let [n (#'mcp/app-note-for ::mcp/refresh-timed-out)]
      (is (some? n) "silence here is indistinguishable from a clean re-serve")
      (is (re-find #"(?i)still running|did not finish" (str n)) (str n))
      (is (re-find #"session_brief" (str n))
          (str "the future keeps going and lands the truth in the session, so"
               " the note has to say where to read it: " n))))

  (testing "and the three outcomes that were ALREADY silent stay silent"
    ;; nothing to do, and a clean re-serve. A line at every done point is how
    ;; a report stops being read, and that reasoning is unchanged — what was
    ;; wrong was one more case hiding inside it
    (is (nil? (#'mcp/app-note-for nil)))
    (is (nil? (#'mcp/app-note-for {:serving? true :url "http://127.0.0.1:7359/"})))
    (is (nil? (#'mcp/app-note-for {:serving? false :stopped true
                                   :reason "http.enabled is false for this store"}))
        "a deliberate stop is not a failure")))

(deftest ^:external the-ring-records-what-the-RESPONSE-did-not-only-what-it-cost-in-time
  ;; The ring recorded a call's edges and whether it was refused, which
  ;; answers what slopp SPENT and never what it SENT. Reads are 52% of the
  ;; token bill, so a ledger with no response size in it cannot rank a single
  ;; tool by what it actually costs — and the one lever on that tier, the
  ;; size gate, silently trims and spools with nothing recording either.
  ;;
  ;; The re-fetch is the fact worth having: a trim that the agent immediately
  ;; opens cost MORE than sending the payload whole. That has been measured
  ;; exactly once, by hand, off one transcript. Recorded here it is a fold.
  (let [sess (external/open!)]
    (try
      ;; the rule catalog is ~23k against an 8000-char gate, so this one is
      ;; trimmed by construction rather than by a bet on a payload's size
      (call! sess "query_rules" {})
      (let [ring (:slopp.read.telemetry/calls @sess)
            row  (last (filter #(= "query_rules" (:tool %)) ring))]
        (is (pos-int? (:chars row))
            (str "every answer carries what it cost to send: " (pr-str row)))
        (is (true? (:trimmed? row)) (pr-str row))
        (is (string? (:spooled row))
            (str "and the retrieval id, so a later query_detail can be tied"
                 " back to the tool that withheld this: " (pr-str row)))

        (testing "the retrieval names what it went back FOR, which is the
                  only way the trim can be scored against its own cost"
          (call! sess "query_detail" {:id (:spooled row)})
          (let [ring2 (:slopp.read.telemetry/calls @sess)
                back  (last (filter #(= "query_detail" (:tool %)) ring2))
                cost  (telemetry/read-cost ring2)]
            (is (= (:spooled row) (:detail-asked back)) (pr-str back))
            (is (< (:chars row) (:chars back))
                (str "the re-fetch is bigger than the trimmed answer — which"
                     " is the whole finding: " (pr-str [row back])))
            (is (= 1 (:refetched cost)) (pr-str cost))
            (is (= 1 (:refetched (first (filter #(= "query_rules" (:tool %))
                                                (:by-tool cost)))))
                (str "charged to the tool that withheld, not to the tool that"
                     " fetched: " (pr-str cost))))))
      (finally (ops/close! sess)))))

(deftest ^:external the-read-record-lands-in-the-JOURNAL-not-only-in-the-ring
  ;; The ring filling is covered next door and the fold is covered in
  ;; telemetry-test; this is the JOIN, and the join is what was broken. The
  ;; record used to ride `:turn-end`, which rotates only when a user PROMPT
  ;; arrived and a WRITE followed — so this exact scenario, work with nobody
  ;; typing, wrote nothing at all. Measured that way in two stores: 321 and
  ;; 118 closed turns, zero read records between them.
  ;;
  ;; So the shape of the test is the shape of the bug: no pending intent is
  ;; ever written here, no turn rotates, and the span must land anyway.
  (let [sess (external/open!)
        of   (fn [op] (filter #(= op (:op %)) (ops/journal sess)))]
    (try
      (call! sess "query_rules" {})               ; ~23k against an 8000 gate
      (call! sess "query_project" {})
      (is (empty? (of :read-cost))
          "nothing is due yet — a delta per read would bury the journal")

      ;; `done` is a WRITE and a work boundary, so it forces the flush. That
      ;; it is also excluded from turn rotation is the point: the read record
      ;; no longer needs a turn to exist.
      (call! sess "done" {})
      (let [d (last (of :read-cost))
            rd (:reads d)]
        (is (some? d)
            (str "a work boundary passed and the span was never written: "
                 (pr-str (take-last 3 (ops/journal sess)))))
        (is (= '*session* (:ns d)) (pr-str d))
        (is (pos-int? (:calls rd)) (pr-str rd))
        (is (pos-int? (:chars rd)) (pr-str rd))
        (is (seq (:by-tool rd)) (pr-str rd))
        (is (= (:chars rd) (reduce + 0 (map :chars (:by-tool rd))))
            (str "the per-tool rows must account for the whole total — a row"
                 " quietly dropped is a tool that reads as free: " (pr-str rd)))
        (is (some #(= "query_rules" (:tool %)) (:by-tool rd))
            (str "and the reads from before any turn existed are IN it: "
                 (pr-str rd)))

        (testing "no turn closed to make that happen"
          (is (empty? (of :turn-end))
              "the whole point is that this no longer depends on a turn")))
      (finally (ops/close! sess)))))

(deftest ^:external an-intent-from-another-session-is-left-for-its-owner
  ;; Two Claude sessions on one store share ONE mailbox: the prompt hook
  ;; writes .slopp/pending-intent at a fixed path and whichever server calls a
  ;; tool next consumes it. Absorbing does not merely mis-stamp a delta — it
  ;; calls adopt-line!, so the thief SWITCHES ONTO THE OTHER AGENT'S THREAD
  ;; and resyncs its store and image from that line. That defeats the whole
  ;; point of threads, whose docstring promises "concurrent sessions never
  ;; merge episodes".
  ;;
  ;; Observed live 2026-08-27 with two sessions on this store: seven writes by
  ;; one session recorded under the other's id, bracketed by turns carrying
  ;; the other's verbatim asks, and session_brief reporting the stranger's
  ;; un-landed work as "private to this thread".
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-two-sessions"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})
        pi   (io/file dir ".slopp" "pending-intent")]
    (try
      (swap! sess assoc :require-turns? true)
      (io/make-parents pi)
      ;; this session's own ask — it adopts sess-A as its identity
      (spit pi "{\"session-id\":\"sess-A\",\"prompt\":\"A's own ask\"}")
      (call! sess "ns_create" {:ns "two.core"
                              :source "(ns two.core)\n(defn f [] 1)\n"})
      (is (= "sess-A" (:agent-id @sess)) "precondition: this session is A")
      ;; now ANOTHER live session's hook drops ITS ask in the shared mailbox
      (spit pi "{\"session-id\":\"sess-B\",\"prompt\":\"B's own ask\"}")
      (call! sess "query_brief" {})
      (testing "a stranger's intent does not re-identify this session"
        (is (= "sess-A" (:agent-id @sess))))
      (testing "and is left on disk for the session it belongs to"
        (is (true? (.exists pi))))
      (testing "and never becomes this session's turn intent"
        (is (not= "B's own ask" (:last-intent @sess))))
      (finally (ops/close! sess)))))

(deftest ^:external a-claimed-session-reads-its-own-mailbox-not-the-shared-one
  ;; Leaving a stranger's intent alone is only half the fix. The other half is
  ;; that the shared file is a single slot: once B's ask sits there unread,
  ;; A's next prompt OVERWRITES it. Losing an ask is quieter than stealing one
  ;; and no less wrong, so the hook also writes pending-intent.<sid> and a
  ;; session that has claimed an id reads that first.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-own-mailbox"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})
        pi   (io/file dir ".slopp" "pending-intent")
        mine (io/file dir ".slopp" "pending-intent.sess-A")]
    (try
      (swap! sess assoc :require-turns? true)
      (io/make-parents pi)
      (spit pi "{\"session-id\":\"sess-A\",\"prompt\":\"A's first ask\"}")
      (call! sess "ns_create" {:ns "own.core"
                              :source "(ns own.core)\n(defn f [] 1)\n"})
      (is (= "sess-A" (:agent-id @sess)) "precondition: this session claimed A")
      ;; A's hook writes both mailboxes for A's SECOND ask; B's hook then
      ;; overwrites the shared slot before A's server gets to it.
      (spit mine "{\"session-id\":\"sess-A\",\"prompt\":\"A's second ask\"}")
      (spit pi   "{\"session-id\":\"sess-B\",\"prompt\":\"B's own ask\"}")
      ;; a cheap call with NO required keys: the required-key refusal runs
      ;; before the mailbox is read, so a refused call reads nothing
      (call! sess "session_brief" {})
      (testing "A's own ask still reaches A"
        (is (= "A's second ask" (:last-intent @sess))))
      (testing "A's mailbox is emptied once read"
        (is (false? (.exists mine))))
      (testing "B's ask is still there for B"
        (is (true? (.exists pi)))
        (is (re-find #"B's own ask" (slurp pi))))
      (finally (ops/close! sess)))))

(deftest ^:external a-server-whose-CLIENT-IS-GONE-says-so-and-EXITS
  ;; EOF on stdin is the ordinary end of an MCP server's life: the editor
  ;; closed the pipe, or nobody was on the other end at all. `-main` handles it
  ;; correctly — the `finally` deregisters from the hub and stops the UI
  ;; listener, which is right, because the UI serves the LIVE session and dies
  ;; with it by design.
  ;;
  ;; Two things were wrong with it and both cost a full evening across three
  ;; agents on 2026-08-27:
  ;;
  ;; 1. **It said nothing.** A manual or scripted launch prints
  ;;    `slopp UI: http://127.0.0.1:PORT/` during boot, then withdraws that
  ;;    listener on EOF without a word. Everyone who curled the announced url
  ;;    got nothing and concluded the code was broken — a consumer twice, and
  ;;    this store's own agents three times between them.
  ;; 2. **It lingered ~60s.** `(future (start-app! session))` runs on Clojure's
  ;;    send-off pool, whose worker threads are NON-DAEMON with a 60s keepalive,
  ;;    and nothing called `shutdown-agents`. A thread dump caught it exactly:
  ;;    `DestroyJavaVM` RUNNABLE — main had already returned — held open by
  ;;    `clojure-agent-send-off-pool-0` parked in `SynchronousQueue.poll`, the
  ;;    same stack and the same `cpu=365.70ms` twelve seconds apart. An idle
  ;;    pool worker waiting for a task that was never coming.
  ;;
  ;; So: alive, announced, serving nothing, silent. This test is the one
  ;; nobody had.
  ;;
  ;; An EMPTY dir on purpose — the boot path is identical and there is no store
  ;; to load, so this costs a JVM rather than a JVM plus 279 namespaces.
  (let [dir  (str (System/getProperty "java.io.tmpdir") "/slopp-eof-" (System/nanoTime))
        cp   (System/getProperty "java.class.path")
        pb   (ProcessBuilder. ["java" "-cp" cp "clojure.main" "-e"
                               (str "(require 'slopp.mcp) (slopp.mcp/-main \"" dir "\")")])
        _    (.mkdirs (io/file dir))
        proc (.start pb)]
    (try
      ;; close stdin immediately: this IS the condition under test
      (.close (.getOutputStream proc))

      (testing "it exits rather than lingering on an idle pool thread"
        ;; 40s: comfortably past a correct exit and comfortably short of the
        ;; 60s keepalive that used to hold it, so this discriminates rather
        ;; than merely waiting long enough for anything to finish.
        (let [done? (.waitFor proc 40 java.util.concurrent.TimeUnit/SECONDS)]
          (when-not done? (.destroyForcibly proc))
          (is done?
              (str "the server was still alive 40s after its client went away."
                   " A process that has torn down its listener and returned from"
                   " -main is holding the JVM open on a non-daemon pool thread"))))

      (testing "and it SAYS why, so a launcher is not left guessing"
        ;; the announced url is on stderr from boot; the withdrawal has to be
        ;; there too, or the last word anyone reads is a url that stopped
        ;; working without comment
        (let [err (slurp (.getErrorStream proc))]
          (is (re-find #"(?i)stdin|client" err)
              (str "nothing on stderr names the client going away — the last"
                   " thing a launcher reads is the url that just stopped"
                   " working. stderr was: " (pr-str err)))))

      (finally
        (.destroyForcibly proc)
        (sh/sh "rm" "-rf" dir)))))

(deftest ^:external ui-serve-keeps-the-address-session-brief-reports-TRUE
  ;; `session_brief` reads `:ui-url` off the session, and only `start-ui!` ever
  ;; wrote it — so re-serving moved the listener and left the brief announcing
  ;; the port it came up on at boot. Measured live: the brief said 49283 while
  ;; the listener held 53610 and 49283 was bound by nobody.
  ;;
  ;; It is the same defect as a host announcing a url it never binds, one layer
  ;; up, and it lands on whoever is trying to find the port — which is exactly
  ;; what the brief is for.
  (let [sess (external/open!)]
    (try
      (let [r   (call! sess "ui_serve" {})
            url (second (re-find #"(http://127\.0\.0\.1:\d+/)" (str r)))]
        (testing "the tool reports an address"
          (is (some? url) (str r)))
        (testing "and the SESSION carries the one just served"
          (is (= url (:ui-url @sess))
              (str "session_brief would announce " (pr-str (:ui-url @sess))
                   " while the listener is on " (pr-str url))))
        (call! sess "ui_serve" {:stop true})
        (testing "stopping CLEARS it rather than leaving an address nothing binds"
          (is (nil? (:ui-url @sess)) (pr-str (:ui-url @sess)))))
      (finally (ops/close! sess)))))

(deftest a-host-can-turn-down-the-idle-images-a-server-holds
  ;; A writer does not cost one JVM. It costs the active image, a warm spare
  ;; that `-main` switches on for every server, and one parked image per branch
  ;; line held for the reap TTL. Scaling writers multiplies the IDLE ones, and
  ;; idle is where the waste is: the spare exists only to keep a JVM boot off
  ;; the critical path, which is a latency trade nobody asked for when the
  ;; binding constraint is memory.
  ;;
  ;; Both knobs are already `open!` options; only `-main` hardcoded them. The
  ;; DEFAULTS do not move — they are what was measured — so a single-writer
  ;; host sees no change and a swarm operator gets a dial.
  (let [opts #'slopp.mcp/host-image-options]
    (testing "unset: exactly today's behaviour"
      (is (= {:slopp.ops/warm-spare? true :slopp.ops/branch-image-ttl-ms 600000}
             (opts (constantly nil)))))

    (testing "a host running many writers drops the spare and shortens the lease"
      (is (= {:slopp.ops/warm-spare? false :slopp.ops/branch-image-ttl-ms 60000}
             (opts {"SLOPP_WARM_SPARE" "0"
                    "SLOPP_BRANCH_IMAGE_TTL_MS" "60000"}))))

    (testing "off is spelled the two ways people spell it, and nothing else is off"
      (is (false? (:slopp.ops/warm-spare? (opts {"SLOPP_WARM_SPARE" "false"}))))
      (is (true? (:slopp.ops/warm-spare? (opts {"SLOPP_WARM_SPARE" "1"})))))

    (testing "a TTL that is not a number keeps the default instead of becoming zero"
      ;; The failure this refuses: a typo'd lease parsed as 0 reaps every branch
      ;; image the instant it is parked, which reads as "branch switching got
      ;; slow" and never as "the variable was misspelt". An unreadable setting
      ;; must not be obeyed as its most destructive reading.
      (is (= 600000 (:slopp.ops/branch-image-ttl-ms
                     (opts {"SLOPP_BRANCH_IMAGE_TTL_MS" "soon"})))))))

(deftest ^:external restart-can-reach-the-APP-server-not-only-the-oracle
  ;; `restart` re-images the ORACLE — the image verification runs in. It has
  ;; never touched the app server, which `webdev.live`'s notes record as a
  ;; known gap, and it matters more now than it did.
  ;;
  ;; A declared entry answers `:started` and nothing else. If it came up half
  ;; dead — threw on its second line, bound nothing — there is no health
  ;; signal to notice it and no way to ask for another attempt short of making
  ;; an unrelated write to trigger a `done`. "Reload in place, restart on
  ;; demand" needs the second half to exist.
  (let [sess (external/open!)]
    (try
      (testing "the plain call still answers, as every existing caller expects"
        (let [r (str (#'mcp/call-op! sess {:name "restart" :arguments {}}))]
          (is (str/includes? r "restarted") (pr-str r))))

      (testing "asking for the app says what happened rather than throwing"
        ;; most stores are not web projects, and an escape hatch that throws
        ;; on them is one nobody reaches for
        (let [r (str (#'mcp/call-op! sess {:name "restart"
                                            :arguments {:app true}}))]
          (is (or (str/includes? r "run.")
                  (str/includes? r "http.enabled"))
              (str "it restarted no app and did not name what would give this"
                   " store one: " r))))

      (finally (ops/close! sess)))))

(deftest ^:external every-tool-call-leaves-a-measurement-row-and-a-read-moves-no-head
  ;; The session ring keeps a turn's calls and `turn-end!` folds the top five
  ;; onto a delta — so per-call cost was lossy (five tools per turn) and lived
  ;; in the journal. A row per call in `measurements` is the census: which
  ;; tool, how long, how many characters it put on the wire (what the next
  ;; request re-reads), and whether it was a refusal. The last assertion is
  ;; the whole reason the table exists: a measurement must never move the
  ;; head, or a read could make a verdict lose its CAS race.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-mcp-rows" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "rows"})]
    (try
      (ops/turn-begin! sess :agent "rows" :intent "measure the calls")
      (call! sess "ns_create" {:ns "rows.core" :source "(ns rows.core)\n\n(def a 1)\n"})
      (let [conn   (:db @sess)
            trunk  (db/trunk-line-id! conn)
            head   (db/line-head conn (:line @sess))
            before (count (db/measurements conn "tool-call" nil))
            _      (call! sess "query_search" {:pattern "rows"})
            _      (call! sess "query_search" {:bogus "x"})
            rows   (db/measurements conn "tool-call" nil)
            [ok bad] (map :payload (take-last 2 rows))]
        (is (= (+ before 2) (count rows)) "one row per call, refusals included")
        (testing "the row says what the call was and what it cost"
          (is (= "query_search" (:tool ok)) (pr-str ok))
          (is (number? (:ms ok)) (pr-str ok))
          (is (pos? (:chars ok)) "the characters on the wire are the cost the next request pays")
          (is (false? (:refused? ok))))
        (testing "a refusal is a row too, and says so"
          (is (true? (:refused? bad)) (pr-str bad)))
        (testing "and two reads moved no head, on the thread or the branch"
          (is (= head (db/line-head conn (:line @sess))))
          (is (= (db/line-head conn trunk) (db/line-head conn trunk))))
        (testing "closing the turn writes the ask's rollup as a row of its own, naming its turn-end"
          ;; the turn-end delta carries `:timing` already; the row beside the
          ;; journal is what a per-ask chart reads without folding the log
          (ops/turn-end! sess :agent "rows")
          (let [ask (last (db/measurements conn "ask" nil))]
            (is (some? ask) "one ask row per closed turn")
            (is (= 3 (get-in ask [:payload :calls])) (pr-str (:payload ask)))
            (is (= (db/line-head conn (:line @sess)) (:delta ask))
                "the row is ABOUT the turn-end delta, and says so"))))
      (finally
        (ops/close! sess)
        (letfn [(rm! [f] (when (.isDirectory f) (run! rm! (.listFiles f))) (.delete f))]
          (rm! (io/file dir)))))))

(deftest ^:external one-argument-one-spelling
  ;; The wire used to carry ALIASES — :old/:new beside :from/:to, :subform and
  ;; :source beside :match — because the names were inconsistent across tools
  ;; and every eval run guessed a different one first. The fix was to make
  ;; the names consistent, not to accept every guess: a form is ns + name, a
  ;; rename goes from → to, a needle is match, and the schema is the whole
  ;; accepted set. A retired spelling is an unknown argument now, refused by
  ;; name, and a missing one is named by the tool rather than surfacing as a
  ;; conversion error.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "ra" :source "(ns ra)\n(defn f [x] (+ x x 1))\n(defn g [x] (f x))\n"})
      (testing "the canonical spellings work"
        (is (nil? (:error (edn/read-string
                           (call! sess "edit_extract"
                                  {:ns "ra" :from "f" :name "doubled"
                                   :match "(+ x x 1)"})))))
        (is (nil? (:error (edn/read-string
                           (call! sess "edit_rename"
                                  {:ns "ra" :from "g" :to "h"}))))))
      (testing "a retired alias is an unknown argument, named"
        (let [r (call! sess "edit_rename" {:ns "ra" :old "f" :new "f2"})]
          (is (re-find #"unknown arguments? :old" r) r))
        (let [r (call! sess "edit_extract" {:ns "ra" :from "f" :name "z"
                                            :source "(+ x x 1)"})]
          (is (re-find #"unknown argument :source" r) r)))
      (testing "a missing argument is named by the tool, not by a conversion error"
        (is (re-find #"needs :from and :to"
                     (call! sess "edit_rename" {:ns "ra"})))
        (is (re-find #"needs :match"
                     (call! sess "edit_extract" {:ns "ra" :from "f" :name "z"})))
        (is (re-find #"missing required argument :ns"
                     (call! sess "query_source" {}))))
      (finally (ops/close! sess)))))

(deftest ^:external a-var-answers-what-usually-breaks-when-it-changes
  ;; The one thing a developer re-explains every session — "when I touch this,
  ;; THAT test goes red" — is in the journal and was never handed back.
  ;; query_depends on a var now carries :red-after — the tests that went red
  ;; in episodes where the form changed, most often first — read from the
  ;; index the red itself wrote.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "rq.core" :source "(ns rq.core)\n(defn f [x] x)\n(defn g [x] x)\n"})
      (call! sess "done" {:label "fixture"})
      (testing "before any red, the key is absent — no evidence is not evidence"
        (let [r (edn/read-string (call! sess "query_depends" {:on "rq.core/f"}))]
          (is (not (contains? r :red-after)) (pr-str r))))
      ;; one episode: f changes AND a test that watches it goes red
      (call! sess "edit_replace_form" {:ns "rq.core" :name "f" :source "(defn f [x] (* 2 x))"
                                       :prompt "double"})
      (call! sess "ns_create" {:ns "rq.core-test"
                               :source (str "(ns rq.core-test (:require [clojure.test :refer [deftest is]] [rq.core :as c]))\n"
                                            "(deftest t (is (= 3 (c/f 1))))\n")})
      (testing "after it, the var names the test that went red beside it"
        (let [r (edn/read-string (call! sess "query_depends" {:on "rq.core/f"}))]
          (is (= ['rq.core-test/t] (mapv :test (:red-after r))) (pr-str r))
          (is (pos? (:n (first (:red-after r)))))))
      (testing "a form that did not change in that episode is not blamed"
        (let [r (edn/read-string (call! sess "query_depends" {:on "rq.core/g"}))]
          (is (not (contains? r :red-after)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest a-budgeted-read-is-never-trimmed-and-a-green-result-never-offers-the-spool
  ;; eval10 (2026-08-29): `orient` fits its own `tokens` budget and was
  ;; still cut by the 8k gate to 247 chars, after which the agent fetched
  ;; the 12k spool; `full_check` came back GREEN with "N of M keys shown —
  ;; query_detail returns all" and the agent dutifully fetched 19k chars of
  ;; a verdict it already had. 77k chars per cell (36% of all result text)
  ;; were those re-fetches. Two rules at the one exit: a result that already
  ;; carries a budget is sent whole, and a green result never advertises
  ;; the spool — the in-band :truncated marker stays, the invitation goes.
  (let [sess (atom {})
        text (fn [x & opts]
               (with-bindings {#'mcp/*spool-session* sess}
                 (get-in (apply #'mcp/text! x opts) [:content 0 :text])))
        big-rows (vec (for [i (range 400)] {:form (symbol (str "app.core/f" i))
                                            :doc (str "a docstring long enough to matter " i)
                                            :via "seed"}))]
    (testing "a budgeted payload over the gate is sent whole"
      (let [out (text {:rows big-rows :more 3} :budgeted? true)]
        (is (> (count out) 8000))
        (is (not (re-find #"query_detail" out)))
        (is (= 400 (count (:rows (edn/read-string out)))))))
    (testing "an unbudgeted payload over the gate is still trimmed, and says so"
      (let [out (text {:rows big-rows})]
        (is (<= (count out) 8200))
        (is (re-find #"query_detail" out))))
    (testing "a GREEN result over the gate keeps the marker and drops the invitation"
      (let [out (text (into {:status :green}
                            (for [i (range 1200)]
                              [(keyword (str "k" i)) (str "a value long enough to need the gate " i)])))]
        (is (<= (count out) 8200))
        (is (re-find #"keys shown" out) "what was cut is still named")
        (is (not (re-find #"query_detail" out)) "but nothing invites the re-fetch")))))

(deftest ^:external the-thread-hint-counts-once-per-head-not-once-per-call
  ;; Every non-done call ran `db/unlanded-count` — a recursive CTE over the
  ;; line — to decide whether to say "N changes are on your thread". The
  ;; count can only move when the head moves, so a burst of reads paid the
  ;; walk N times for one answer. Keyed on the head, it is paid once per
  ;; write.
  (let [sess  (external/open!)
        calls (atom 0)]
    (try
      (ops/ingest! sess 'th.core "(ns th.core)\n(defn f [] 1)\n")
      (with-redefs [db/unlanded-count (let [orig db/unlanded-count]
                                        (fn [& args] (swap! calls inc) (apply orig args)))]
        (mcp/thread-hint! sess "query_slice")
        (mcp/thread-hint! sess "query_slice")
        (mcp/thread-hint! sess "query_source")
        (is (= 1 @calls) "three reads at one head: one walk")
        (ops/edit-replace! sess 'th.core 'f "(defn f [] 2)" :prompt "two")
        (mcp/thread-hint! sess "query_slice")
        (is (= 2 @calls) "a write moved the head: one more walk"))
      (finally (ops/close! sess)))))

(deftest ^:external bookkeeping-results-are-the-size-of-their-answer
  ;; eval10: `done` averaged 2k chars, `session_brief` 2–3.5k, `report` was
  ;; unbounded by its own `limit`, and the brief's `:loop` line was 472
  ;; chars byte-identical in every session of a lifetime — orientation that
  ;; re-taught what the skill had said. A bookkeeping result says what
  ;; CHANGED and what needs the agent; a green done is a line.
  (let [sess (external/open!)
        call (fn [tool args]
               (get-in (mcp/handle! sess {:id 1 :method "tools/call"
                                          :params {:name tool :arguments args}})
                       [:result :content 0 :text]))]
    (try
      (call "ns_create" {:ns "bk.core" :source "(ns bk.core \"a fixture with nothing to advise about\")\n(defn ^{:unused-ok \"fixture\"} f [] 1)\n"})
      (call "turn_begin" {:intent "make f" :agent (:agent-id @sess)})
      (call "edit_replace_form" {:ns "bk.core" :name "f" :source "(defn ^{:unused-ok \"fixture\"} f [] 2)" :prompt "two"})
      (testing "a green done is one line: the id, the verdict, what landed"
        (let [r (call "done" {:label "bk"})
              m (edn/read-string r)]
          (is (= :green (:status m)) r)
          (is (:done m))
          (is (< (count r) 400) (str (count r) " chars: " r))
          (is (not (contains? m :host-stale)) "a current host says nothing")))
      (testing "the brief carries what changed, not the loop the skill teaches"
        (let [r (call "session_brief" {})
              m (edn/read-string r)]
          (is (not (contains? m :loop)) r)
          (is (not (contains? m :alignment)) "git alignment is a question, not orientation")
          (is (< (count r) 1500) (str (count r) " chars"))))
      (testing "report's limit bounds its intents"
        (dotimes [i 4]
          (call "turn_begin" {:intent (str "ask number " i) :agent (:agent-id @sess)})
          (call "edit_replace_form" {:ns "bk.core" :name "f" :source (str "(defn ^{:unused-ok \"fixture\"} f [] " (+ 10 i) ")") :prompt (str "ask number " i)}))
        (let [m (edn/read-string (call "report" {:limit 2}))]
          (is (<= (count (:intents m)) 2) (pr-str (:intents m)))))
      (finally (ops/close! sess)))))

(deftest ^:external help-serves-a-topic-from-the-plugins-reference
  ;; The skill is a page; everything it used to carry (2,900 lines, 70k
  ;; tokens loaded into every session) lives in the plugin's
  ;; skills/slopp/reference/<topic>.md and is read WHEN NEEDED — by the
  ;; agent's own Read, or by `help {topic}` here, which serves the same file
  ;; so there is one source of truth. No topic → the index of topics.
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "slopp-help" (make-array java.nio.file.attribute.FileAttribute 0)))
        ref  (io/file root "skills" "slopp" "reference")]
    (.mkdirs ref)
    (spit (io/file ref "web.md") "# Web applications\n\nA page is a function.\n")
    (spit (io/file ref "cli.md") "# Command-line applications\n\nOne entry.\n")
    (with-redefs [mcp/plugin-root (constantly root)]
      (let [sess (external/open!)
            call (fn [args] (get-in (mcp/handle! sess {:id 1 :method "tools/call"
                                                        :params {:name "help" :arguments args}})
                                    [:result :content 0 :text]))]
        (try
          (testing "a topic returns that reference file, whole"
            (is (re-find #"A page is a function" (call {:topic "web"}))))
          (testing "no topic returns the index, naming every topic on disk"
            (let [r (call {})]
              (is (re-find #"web" r))
              (is (re-find #"cli" r))
              (is (not (re-find #"A page is a function" r)) "the index is not the content")))
          (testing "an unknown topic is refused with the index"
            (is (re-find #"(?i)no topic.*nope" (call {:topic "nope"}))))
          (finally (ops/close! sess)))))))

(deftest ^:external a-group-delete-refuses-a-caller-OUTSIDE-the-group-by-step-and-name
  ;; slopp-ui, 2026-08-30, on edit_group's announcement: "delete a
  ;; namespace's forms" is exactly where the callers gate stops being a
  ;; nuisance and starts being the thing that saves you. Inside a group the
  ;; single-form gate is deliberately off — a mid-sequence dangling reference
  ;; is legitimate when a later step removes the caller — so the question has
  ;; to be asked of the FINAL store value: whatever still calls a deleted form
  ;; after every step applied is a real dangling caller, and the refusal names
  ;; the step and the caller rather than surfacing as a compile failure.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "gd.core"
                               :source "(ns gd.core)\n(defn helper [x] x)\n(defn ^:unused-ok user [x] (helper x))\n(defn ^:unused-ok other [] 1)\n"})
      (testing "a caller left OUTSIDE the group refuses, naming the step and the caller"
        (let [r (call! sess "edit_group" {:prompt "drop helper (but user still calls it)"
                                          :steps [{"action" "delete" "ns" "gd.core" "name" "other"}
                                                  {"action" "delete" "ns" "gd.core" "name" "helper"}]})]
          (is (re-find #"step 1:" r) r)
          (is (re-find #"gd\.core/user" r) r)
          (is (not (re-find #"failed to compile" r)) r)
          (is (not (re-find #":error" (call! sess "query_source" {:targets [{:ns "gd.core" :name "other"}]})))
              "nothing landed — the group is all-or-nothing")))
      (testing "the caller INSIDE the group is fine: callee and caller go together, in either order"
        (let [r (call! sess "edit_group" {:prompt "drop helper and its only caller"
                                          :steps [{"action" "delete" "ns" "gd.core" "name" "helper"}
                                                  {"action" "delete" "ns" "gd.core" "name" "user"}]})]
          (is (re-find #":group" r) r)
          (is (re-find #":error" (call! sess "query_source" {:targets [{:ns "gd.core" :name "helper"}]})))))
      (finally (ops/close! sess)))))

(deftest ^:external a-first-cross-module-call-declares-its-edge-like-a-require
  ;; eval10: module_dep was 7–11 turns per lifetime cell, every one of them
  ;; the agent doing what the refusal told it to — declare the edge, then
  ;; resend the write. The same shape auto-require already answers: when the
  ;; only thing between a write and landing is one declaration the refusal
  ;; itself can name, make it and stamp the result. A CYCLE is a real
  ;; question and stays a refusal.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "am.util.core" :source "(ns am.util.core)\n(defn ^:export twice [x] (* 2 x))\n"})
      (call! sess "ns_create" {:ns "am.app.core" :source "(ns am.app.core)\n(defn ^{:export true :unused-ok \"fixture\"} one [] 1)\n"})
      (testing "the edge is declared for the write and the result says so"
        (let [r (call! sess "edit_add_form" {:ns "am.app.core" :prompt "app uses util"
                                             :source "(defn ^:unused-ok quad [x] (am.util.core/twice (am.util.core/twice x)))"})]
          (is (re-find #":auto-module-dep \{:from \"am\.app\", :to \"am\.util\"\}" r) r)
          (is (re-find #":ok true" r) r))
        (is (re-find #"am\.util" (call! sess "query_depends" {:modules true}))
            "the manifest carries the edge"))
      (testing "a cycle stays a refusal, and says which"
        ;; am.util → am.app would close util ↔ app
        (let [r (call! sess "edit_add_form" {:ns "am.util.core" :prompt "util calls back into app"
                                             :source "(defn ^:unused-ok back [] (am.app.core/one))"})]
          (is (re-find #":error" r) r)
          (is (re-find #"cycle" r) r)
          (is (not (re-find #":auto-module-dep" r)) r)))
      (finally (ops/close! sess)))))

(deftest ^:external the-write-paths-self-repairs-are-stamped-on-the-wire
  ;; Found 2026-08-30 while wiring auto-module-dep: `:auto-require` had been
  ;; stamped by add-form!/edit-replace! since it existed and DROPPED by the
  ;; wire's select-keys — three tool descriptions and the skill promised
  ;; ":auto-require says so" to agents who could never see it. A repair the
  ;; result does not report is a write the agent re-checks by hand.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "ar.core.util" :source "(ns ar.core.util)\n(defn g [] 1)\n"})
      (call! sess "ns_create" {:ns "ar.core" :source "(ns ar.core)\n"})
      (let [r (call! sess "edit_add_form" {:ns "ar.core" :prompt "core uses util"
                                           :source "(defn ^:unused-ok f [] (util/g))"})]
        (is (re-find #":auto-require \{:added \"\[ar\.core\.util :as util\]\"" r) r)
        (is (re-find #":ok true" r) r))
      (finally (ops/close! sess)))))

(deftest ^:external the-advertised-surface-is-fourteen-families-and-every-op-keeps-its-name
  ;; eval10: 96 advertised tools (~64k chars of descriptions) — Claude Code
  ;; DEFERRED every one, and the agent paid 19–22 ToolSearch turns per
  ;; lifetime cell to find them. The cut that cannot rot the record: every
  ;; operation KEEPS ITS NAME as the family's `op`, so refusals, docstrings
  ;; and skill lines stay right; only the packaging moves. Direct dispatch
  ;; by op name survives for `--call` and the hooks.
  ;;
  ;; s11 made the surface TWO VERBS — explore (questions) and change
  ;; (writes) — after three measurements agreed that only surface forcing
  ;; moves behavior (s7 prose, s8 de-advertising, s10 advertisement beside
  ;; a familiar sibling). The former write ops and old verb names live on
  ;; as dispatchable aliases, pinned below.
  (let [sess (external/open!)]
    (try
      (let [advertised (get-in (mcp/handle! sess {:id 2 :method "tools/list"}) [:result :tools])
            names      (into #{} (map :name) advertised)]
        (testing "fourteen families, and done/commit_point keep their own names"
          (is (= #{"orient" "read" "depends" "history" "eval" "edit" "refactor" "declare"
                   "verify" "build" "store" "slopp" "done" "commit_point"}
                 names)
              (pr-str (sort names))))
        (testing "every op lives in exactly one family, and the families cover the registry"
          (let [placed (frequencies (mapcat :ops tools/families))]
            (is (every? #(= 1 %) (vals placed)) (pr-str (filter #(not= 1 (val %)) placed)))
            (is (= (set (keys placed)) (into #{} (map :name) tools/registry))
                "the families cover the registry exactly")))
        (testing "the edit family advertises the ONE write verb"
          (let [edit (some #(when (= "edit" (:name %)) %) advertised)
                enum (get-in edit [:inputSchema :properties :op :enum])]
            (is (some #{"change"} enum))
            (is (nil? (some #{"edit_group" "edit_subform" "edit_delete_form"
                              "edit_add_form" "edit_replace_form" "intent"} enum))
                "the former write ops are de-advertised aliases")
            (is (re-find #"argument cards ride the \[slopp\] block" (:description edit))
                (:description edit))
            (is (= ["op"] (get-in edit [:inputSchema :required])))
            ;; :text now comes from edit_comment alone — the multi-op union
            ;; collapsed with the subform descriptor
            (is (= #{"string"} (set (flatten [(get-in edit [:inputSchema :properties :text :type])]))))))
        (testing "the read family leads with the question verb"
          (let [rd   (some #(when (= "read" (:name %)) %) advertised)
                enum (get-in rd [:inputSchema :properties :op :enum])]
            (is (some #{"explore"} enum))
            (is (some #{"check"} enum))
            (is (nil? (some #{"query_batch"} enum)) "query_batch is the compat alias")))
        (testing "the whole advertised surface is small enough never to be deferred —
                  and DIETED: the op-index prose rides the bundle as cards (s14)"
          (is (< (count (pr-str advertised)) 15000) (str (count (pr-str advertised))))))
      (call! sess "ns_create" {:ns "fam.core" :source "(ns fam.core)\n(defn ^:unused-ok f [x] x)\n"})
      (testing "a family call is the op call"
        (let [r (call! sess "edit" {:op "change" :prompt "via the family"
                                    :impl [{:action "add" :ns "fam.core"
                                            :source "(defn ^:unused-ok g [x] (f x))"}]})]
          (is (re-find #":ok true" r) r))
        ;; the write held g's text in the ask's ledger, so the read through
        ;; the read family answers with a reference (D-form-ledger)
        (is (re-find #":name g, :source-already-sent true"
                     (call! sess "read" {:op "query_source" :targets [{:ns "fam.core" :name "g"}]}))))
      (testing "the op's own validation, by name"
        (is (re-find #"unknown op frobnicate for edit — ops: change edit_comment" (call! sess "edit" {:op "frobnicate"})))
        (is (re-find #"unknown argument :nom for change" (call! sess "edit" {:op "change" :nom "g" :prompt "p" :impl []})))
        (is (re-find #"change needs :impl" (call! sess "edit" {:op "change" :prompt "p"}))))
      (testing "the de-advertised aliases still dispatch by name (the --call door, old scripts)"
        (is (re-find #":ok true" (call! sess "edit_replace_form" {:ns "fam.core" :name "g" :prompt "direct"
                                                                    :source "(defn ^:unused-ok g [x] (f (f x)))"})))
        (is (re-find #":ok true" (call! sess "edit_group" {:prompt "alias group"
                                                           :steps [{:action "replace" :ns "fam.core" :name "g"
                                                                    :source "(defn ^:unused-ok g [x] (f x))"}]})))
        (is (re-find #":ok true" (call! sess "edit_subform" {:ns "fam.core" :name "g" :prompt "alias subform"
                                                             :match "(f x)" :source "(f (f x))"}))))
      (testing "help {topic op} is the op's full card"
        (let [h (call! sess "help" {:topic "change"})]
          (is (re-find #"THE write door" h))
          (is (re-find #":required" h) h)))
      (finally (ops/close! sess)))))

(deftest a-green-full-check-is-the-size-of-its-verdict
  ;; eval10 s2 census: an agent called full_check twice mid-loop, each answer
  ;; (~19k chars, green) was trimmed at the 8k gate and re-fetched WHOLE via
  ;; query_detail — 76k chars of result for a verdict that fits in one line.
  ;; Same rule as `terse-done`: green means the verdict, the populations
  ;; checked, and anything that is NOT bookkeeping; red keeps the full map.
  (let [green {:status :green :namespaces 244 :lint-errors 0 :lint-warnings 0
               :checked {:lint 244 :rule-sweep 3402} :ms 300000
               :rules {:swept [:a :b] :not-swept [{:rule :c :why "…"}] :forms 3402
                       :findings {:http-dangling-route-refs {:info 1 :rows "full_check {verbose true}"}}
                       :note "…"}
               :rules-note "1 rule(s) with standing findings …"
               :test {:test 838 :pass 5591 :fail 0 :error 0 :type :summary :ms 2986}
               :external {:status :green :ran 1475 :failures 0 :errors 0 :ns-ms {'x 1} :cost {:shards 4}}
               :alias-drift [{:ns 'a :lib 'b :as 'c}] :alias-drift-note "16 require(s) …"
               :modules {:auto-declared 0}
               :bundle {:sha "abc" :behind 1 :note "…"}}
        t     (#'mcp/terse-full-check green)]
    (testing "green: the verdict, what was checked, and the facts a reader branches on"
      (is (= :green (:status t)))
      (is (= {:lint 244 :rule-sweep 3402} (:checked t)))
      (is (= {:ran 1475 :status :green} (:external t)))
      (is (= {:auto-declared 0} (:modules t)))
      (is (= {:http-dangling-route-refs {:info 1 :rows "full_check {verbose true}"}} (:findings t))
          "standing findings survive, folded; their scaffolding does not")
      (is (= 1 (get-in t [:bundle :behind])) "an artifact behind the store is news")
      (is (= 1 (:alias-drift t)) "a count, not the rows and a paragraph")
      (is (not (contains? t :rules-note)))
      (is (not (contains? t :test)))
      (is (< (count (pr-str t)) 600) (pr-str t)))
    (testing "red keeps the full map"
      (is (= (assoc green :status :red) (#'mcp/terse-full-check (assoc green :status :red)))))
    (testing "verbose keeps the full map"
      (is (= green (#'mcp/terse-full-check green :verbose? true))))))

(deftest a-form-source-the-ask-already-holds-is-a-reference
  ;; `told!` stubs a whole payload the same call already returned; it knows
  ;; nothing about forms, so orient → slice → query_source of the same form
  ;; sends its text three times, and a read after the agent's OWN write sends
  ;; back what the agent typed. The ledger keys on [form-id, hash of text]:
  ;; every read that sends a source records it, every write whose full source
  ;; the agent sent records the stored text, and a later read of a form held
  ;; at that version says so instead. Ask-scoped, like told! — a stub must
  ;; not outlive the reader it is about.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'lg.core "(ns lg.core)\n(defn f \"F.\" [x] x)\n(defn g \"G.\" [x] (f x))\n"))
        sess (atom {:store st :test-map {}})
        src  (fn [nm] (n/string (:node (store/form-named (:store @sess) 'lg.core nm))))
        rows (fn [] {:rows [{:form 'lg.core/f :via "seed" :source (src 'f)}
                            {:form 'lg.core/g :via "calls lg.core/f"}]})
        items (fn [] [{:ns 'lg.core :name 'f :source (src 'f)}
                      {:ns 'lg.core :name 'g :source (src 'g)}])]
    (testing "the first send is whole and is remembered; the second is a reference"
      (is (= (rows) (#'mcp/dedupe-sources! sess (rows))))
      (let [r (#'mcp/dedupe-sources! sess (rows))]
        (is (nil? (get-in r [:rows 0 :source])) (pr-str r))
        (is (true? (get-in r [:rows 0 :source-already-sent])))
        (is (= "seed" (get-in r [:rows 0 :via])) "the rest of the row is untouched")))
    (testing "every read shape that carries source: query_source items and a brief"
      (let [r (#'mcp/dedupe-sources! sess (items))]
        (is (true? (:source-already-sent (first r))) "f was sent by the orient row")
        (is (string? (:source (second r))) "g never was"))
      (is (true? (:source-already-sent (#'mcp/dedupe-sources! sess {:ns 'lg.core :name 'g :source (src 'g) :callers []})))))
    (testing "a changed form is sent whole again"
      (let [st' (first (store/replace-node (:store @sess) 'lg.core 'f
                                           (:node (edit/parse-form "(defn f \"F2.\" [x] (inc x))"))
                                           :prompt "changed"))]
        (swap! sess assoc :store st')
        (is (string? (get-in (#'mcp/dedupe-sources! sess (rows)) [:rows 0 :source])))))
    (testing "a write whose full source the agent sent is held: the read after it is a reference"
      (#'mcp/ledger-written! sess "edit_replace_form" {:ns "lg.core" :name "g" :source (src 'g)})
      (is (true? (:source-already-sent (#'mcp/dedupe-sources! sess {:ns 'lg.core :name 'g :source (src 'g)})))))
    (testing "a new ask starts over"
      (swap! sess update :slopp.mcp/ask (fnil inc 0))
      (is (string? (get-in (#'mcp/dedupe-sources! sess (rows)) [:rows 0 :source]))))))

(deftest ^:external the-read-after-your-own-write-is-a-reference
  ;; eval10 s1/s2: `query_source` 13–17 per lifetime cell, a good share of
  ;; them re-reading a form the agent had just written in full. The ledger
  ;; holds what a full-source write landed; the read comes back as a
  ;; reference; a form the ask never held, or one that changed since, is
  ;; sent whole.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "lw.core" :source "(ns lw.core)\n(defn f [x] x)\n(defn ^:unused-ok g [x] (f x))\n"})
      (call! sess "edit_replace_form" {:ns "lw.core" :name "f" :prompt "held"
                                       :source "(defn f \"F.\" [x] (inc x))"})
      (let [r (call! sess "query_source" {:targets [{:ns "lw.core" :name "f"} {:ns "lw.core" :name "g"}]})]
        (is (re-find #":name f, :source-already-sent true" r) r)
        (is (re-find #":name g, :source \"\(defn" r) "g was never sent"))
      (testing "sent once, a form is a reference on the next read of ANY shape"
        (is (re-find #":source-already-sent true" (call! sess "query_brief" {:ns "lw.core" :name "g"}))))
      (testing "changed since: sent whole again"
        (call! sess "edit_subform" {:ns "lw.core" :name "g" :match "(f x)" :source "(f (f x))" :prompt "changed"})
        (is (re-find #":name g, :source \"\(defn" (call! sess "query_source" {:targets [{:ns "lw.core" :name "g"}]}))))
      (finally (ops/close! sess)))))

(deftest a-red-with-a-literal-delta-proposes-the-assertion-update
  ;; eval10: after a deliberate behaviour change the agent read the failing
  ;; test, found the literal, and rewrote it — three calls for "1400 is now
  ;; 1600". clojure.test already reports the assertion form and the actual
  ;; value; when the expected side is a literal and the actual is one too,
  ;; the update is mechanical, and the result says exactly what one
  ;; edit_subform {text true} would send. Anything else — a computed
  ;; expected, a thrown error, a non-equality assertion — proposes nothing.
  (let [propose #'mcp/propose-assertion]
    (testing "a literal expected against a literal actual"
      (is (= {:match "(= 1400 (quote-cents p c))" :source "(= 1600 (quote-cents p c))"
              :note "accept the new behaviour with edit_subform {ns name match source text true}; or the change is wrong and the test is right"}
             (propose {:test 'logi.quoting-test/quote-t
                       :expected "(= 1400 (quote-cents p c))"
                       :actual "(not (= 1400 1600))"}))))
    (testing "string and keyword literals, and the actual on either side"
      (is (= {:match "(= \"a\" (f))" :source "(= \"b\" (f))"}
             (dissoc (propose {:expected "(= \"a\" (f))" :actual "(not (= \"a\" \"b\"))"}) :note)))
      (is (= {:match "(= (f) :old)" :source "(= (f) :new)"}
             (dissoc (propose {:expected "(= (f) :old)" :actual "(not (= :new :old))"}) :note))))
    (testing "nothing to propose"
      (is (nil? (propose {:expected "(= (g x) (f x))" :actual "(not (= 1 2))"})) "no literal to move")
      (is (nil? (propose {:expected "(= 1400 (f))" :actual "java.lang.ArithmeticException: Divide by zero"})) "an error is not a delta")
      (is (nil? (propose {:expected "(pos? (f))" :actual "(not (pos? -1))"})) "not an equality")
      (is (nil? (propose {:expected "(= 1400 (f))" :actual "(not (= 1400 [1 2 3]))"})) "a collection is not a literal the agent should accept blind"))))

(deftest ^:external a-group-and-a-new-namespace-declare-their-first-crossing-too
  ;; eval10 s4 (the jar with auto-declared edges): module_dep was still
  ;; called nine times — after an edit_group step's refusal ("step 0: x/f
  ;; uses y/g but module x does not declare y") and after ns_create's. The
  ;; single-form writes repair the edge; these two paths must too, or the
  ;; agent learns the two-step from whichever door it happens to use.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "mg.util.core" :source "(ns mg.util.core)\n(defn ^:export twice [x] (* 2 x))\n"})
      (call! sess "ns_create" {:ns "mg.app.core" :source "(ns mg.app.core)\n(defn ^{:export true :unused-ok \"fixture\"} one [] 1)\n"})
      (testing "a group whose step crosses a module boundary"
        (let [r (call! sess "edit_group" {:prompt "app uses util, as a group"
                                          :steps [{"action" "add" "ns" "mg.app.core"
                                                   "source" "(defn ^:unused-ok quad [x] (mg.util.core/twice (mg.util.core/twice x)))"}]})]
          (is (re-find #":auto-module-dep \{:from \"mg\.app\", :to \"mg\.util\"\}" r) r)
          (is (re-find #":group" r) r)))
      (testing "a new namespace whose source crosses a module boundary"
        (let [r (call! sess "ns_create" {:ns "mg.web.core" :prompt "web uses app"
                                         :source "(ns mg.web.core)\n(defn ^:unused-ok page [] (mg.app.core/one))\n"})]
          (is (re-find #":auto-module-dep \{:from \"mg\.web\", :to \"mg\.app\"\}" r) r)
          (is (not (re-find #"\{:error" r)) r)))
      (testing "the eval's shape: aliased requires, TWO crossings, and a deftest as the caller"
        (let [r (call! sess "ns_create" {:ns "mg.ship.core" :prompt "ship uses util and app"
                                         :source (str "(ns mg.ship.core (:require [clojure.test :refer [deftest is]] [mg.util.core :as util] [mg.app.core :as app]))\n"
                                                      "(defn ^:unused-ok ship [x] (util/twice x))\n"
                                                      "(deftest ship-t (is (= 1 (app/one))))\n")})]
          (is (re-find #":auto-module-dep" r) r)
          (is (re-find #":auto-module-deps \[\{:from \"mg\.ship\", :to \"mg\.(util|app)\"\}" r) r)
          (is (not (re-find #"\{:error" r)) r)))
      (testing "a TWO-segment namespace — the namespace is its own module — with a deftest crossing"
        (let [r (call! sess "ns_create" {:ns "mg.two" :prompt "two uses util"
                                         :source (str "(ns mg.two (:require [clojure.test :refer [deftest is]] [mg.util.core :as util]))\n"
                                                      "(defn ^:unused-ok f [x] x)\n"
                                                      "(deftest two-t (is (= 4 (util/twice 2))))\n")})]
          (is (re-find #":auto-module-dep \{:from \"mg\.two\", :to \"mg\.util\"\}" r) r)
          (is (not (re-find #"\{:error" r)) r)))
      (testing "the eval's exact case: red-first — the deftest names a var not written yet — AND a crossing"
        (let [r (call! sess "ns_create" {:ns "mg.red" :prompt "test first, crossing util"
                                         :source (str "(ns mg.red (:require [clojure.test :refer [deftest is]] [mg.util.core :as util]))\n"
                                                      "(deftest red-t (is (= 8 (quad (util/twice 1)))))\n")})]
          (is (not (re-find #"does not declare" r)) (str "the edge question must be answered, not asked: " r))
          (is (re-find #":auto-module-dep \{:from \"mg\.red\", :to \"mg\.util\"\}" r) r)))
      (finally (ops/close! sess)))))

(deftest ^:external a-small-namespace-is-one-read
  ;; eval10 opus s4: 13 query_source calls per lifetime cell on namespaces of
  ;; ~10 forms — the outline first, then the forms it named. Plain-files opus
  ;; `cat`s the file once. When the whole namespace fits comfortably, the
  ;; outline is a detour: answer with the source. A big namespace keeps the
  ;; outline (and says why), and `full true` still forces the source.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "sm.core" :source "(ns sm.core)\n(defn ^:unused-ok a [x] x)\n(defn ^:unused-ok b [x] (a x))\n"})
      (call! sess "ns_create" {:ns "big.core"
                               :source (apply str "(ns big.core)\n"
                                              (for [i (range 120)]
                                                (str "(defn ^:unused-ok f" i " \"Form number " i ", padded out to make the namespace long enough that an outline is the right answer.\" [x] (+ x " i "))\n")))})
      (testing "small: the source, and a note saying it is the whole namespace"
        (let [r (call! sess "query_source" {:ns "sm.core"})]
          (is (re-find #":source \"\(ns sm\.core\)" r) r)
          (is (re-find #":whole true" r) r)
          (is (not (re-find #":outline" r)) r)))
      (testing "big: the outline, with the size that decided it"
        (let [r (call! sess "query_source" {:ns "big.core"})]
          (is (re-find #":outline" r) r)
          (is (re-find #"over 6k" r) r)
          (is (re-find #":whole false" r) "the shape names itself in both branches; nobody branches on an absent key")
          (is (not (re-find #"defn \^:unused-ok f1 " r)) "no source rode along")))
      (testing "full true is still the whole thing, whatever the size"
        (is (re-find #"defn \^:unused-ok f1 " (call! sess "query_source" {:ns "big.core" :full true}))))
      (finally (ops/close! sess)))))

(deftest ^:external a-test-first-namespace-with-its-tests-beside-the-code-lands-red
  ;; eval10 s5, step 1: the agent wrote `logi.oversize` test-first — a
  ;; deftest naming `oversize?` before writing it — and ns_create answered
  ;; "namespace failed to load: Unable to resolve symbol: oversize?", so the
  ;; agent wrote stubs by hand and tried twice more. The red-first seam only
  ;; knew `-test` namespaces; this terrain keeps its tests beside the code.
  (let [sess (external/open!)]
    (try
      (let [r (call! sess "ns_create" {:ns "rf.core" :prompt "test first"
                                       :source (str "(ns rf.core (:require [clojure.test :refer [deftest is]]))\n"
                                                    "(defn ^:unused-ok helper [x] x)\n"
                                                    "(deftest rf-t (is (= 4 (quad 2))))\n")})]
        (is (re-find #":red-first \[rf\.core/quad\]" r) r)
        (is (not (re-find #"failed to load" r)) r)
        (is (re-find #":fail 1|:error 1" r) "the spec lands as an honest red naming the stub"))
      (testing "a production form's genuine unresolved symbol is still the compile error it is"
        (let [r (call! sess "ns_create" {:ns "rf.broken" :prompt "not a test"
                                         :source "(ns rf.broken)\n(defn ^:unused-ok f [x] (nowhere x))\n"})]
          (is (re-find #"failed to load" r) r)
          (is (not (re-find #":red-first" r)) r)))
      (finally (ops/close! sess)))))

(deftest ^:external one-read-call-carries-several-ops
  ;; Measured on every eval cell (s1–s6, both cohorts): not ONE assistant
  ;; message carried two tool_use blocks — "issue independent reads in one
  ;; turn" has been in the skill since step 1 and no model does it. So the
  ;; batch lives inside the call: `read {op explore ops [...]}` runs
  ;; several READ ops and answers them as one result vector, each entry
  ;; through the same told!/ledger door as the single call. A write op in
  ;; the batch is refused by name — reads compose, writes have their own
  ;; grain (change).
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "qb.core" :source "(ns qb.core)\n(defn f \"F.\" [x] x)\n(defn g \"G.\" [x] (f x))\n"})
      (testing "several reads, one call, one result per op"
        (let [r (call! sess "read" {:op "explore"
                                    :ops [{"op" "query_source" "targets" [{"ns" "qb.core" "name" "f"}]}
                                          {"op" "query_search" "pattern" "G\\."}
                                          {"op" "query_depends" "on" "qb.core/f"}]})]
          (is (re-find #"\(defn f" r) r)
          (is (re-find #":form g" r) "the search hit came back as a card")
          (is (re-find #":op \"query_depends\", :result" r) "the depends answer rode along")
          (is (not (re-find #"unknown (op|argument)" r)) r)))
      (testing "the ledger reaches inside the batch: a source the ask holds is a reference"
        (let [r (call! sess "read" {:op "explore"
                                    :ops [{"op" "query_source" "targets" [{"ns" "qb.core" "name" "f"}]}]})]
          (is (re-find #":source-already-sent true" r) r)))
      (testing "a write op is refused by name; nothing runs"
        (let [r (call! sess "read" {:op "explore"
                                    :ops [{"op" "query_source" "targets" [{"ns" "qb.core" "name" "f"}]}
                                          {"op" "edit_delete_form" "ns" "qb.core" "name" "f"}]})]
          (is (re-find #"edit_delete_form is a write" r) r)
          (is (re-find #"\(defn f|:source-already-sent true"
                       (call! sess "query_source" {:targets [{:ns "qb.core" :name "f"}]}))
              "f still exists — the refused batch ran nothing")))
      (finally (ops/close! sess)))))

(deftest ^:external an-accepted-expectation-shift-finishes-in-the-same-call
  ;; The finisher, v1, fully deterministic: the judgment "is this red the
  ;; intended consequence?" is the AGENT's, made at write time — `accept`
  ;; on the write names the tests whose literal expectations the change is
  ;; supposed to move. When such a test fails with a literal→literal delta
  ;; (:proposed), slopp applies the proposed update as its own delta,
  ;; re-verifies once, and reports :finisher in the SAME result — the
  ;; read-the-red/patch-the-test/rerun loop (three calls per deliberate
  ;; change in every eval cell) collapses into the write that caused it.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "fin.core"
                               :source (str "(ns fin.core (:require [clojure.test :refer [deftest is]]))\n"
                                            "(defn price [x] (* 100 x))\n"
                                            "(deftest price-t (is (= 200 (price 2))))\n"
                                            "(defn ^:unused-ok other [] 7)\n"
                                            "(deftest other-t (is (= 7 (other))))\n")})
      (testing "accepted: the shift lands, the test follows, one call, green"
        (let [r (call! sess "edit_group"
                       {:prompt "prices go up 10%: 100 -> 110 per unit"
                        :accept ["fin.core/price-t"]
                        :steps [{"action" "replace" "ns" "fin.core" "name" "price"
                                 "source" "(defn price [x] (* 110 x))"}]})]
          (is (re-find #":finisher \{:applied \[\{:test fin\.core/price-t, :was \"\(= 200 \(price 2\)\)\", :now \"\(= 220 \(price 2\)\)\"\}\], :status :green\}" r) r)
          (is (re-find #":status :green" r) r)
          (is (re-find #"= 220" (call! sess "query_source" {:targets [{:ns "fin.core" :name "price-t"}]}))
              "the assertion moved to the new literal")))
      (testing "NOT accepted: the red rides the result untouched, :proposed as a draft"
        (let [r (call! sess "edit_group"
                       {:prompt "prices go up again"
                        :steps [{"action" "replace" "ns" "fin.core" "name" "price"
                                 "source" "(defn price [x] (* 120 x))"}]})]
          (is (re-find #":fail 1" r) "red keeps the full map, which counts rather than labels")
          (is (re-find #":proposed" r) r)
          (is (not (re-find #":finisher" r)) r)
          (is (re-find #"= 220|:source-already-sent true" (call! sess "query_source" {:targets [{:ns "fin.core" :name "price-t"}]}))
              "the test was not touched — still the text the ask already holds")))
      (testing "a single accepted test with SEVERAL moving literals is skipped and named"
        (call! sess "edit_group" {:prompt "a second pinned view of price"
                                  :steps [{"action" "replace" "ns" "fin.core" "name" "price"
                                           "source" "(defn price [x] (* 120 x))"}
                                          {"action" "replace" "ns" "fin.core" "name" "price-t"
                                           "source" "(deftest price-t (is (= 240 (price 2))) (is (= 360 (price 3))))"}]})
        (let [r (call! sess "edit_group"
                       {:prompt "prices up again — but two pins moved, judge them yourself"
                        :accept ["fin.core/price-t"]
                        :steps [{"action" "replace" "ns" "fin.core" "name" "price"
                                 "source" "(defn price [x] (* 130 x))"}]})]
          (is (re-find #":skipped-multi \[fin\.core/price-t\]" r) r)
          (is (re-find #"= 240|:source-already-sent true"
                       (call! sess "query_source" {:targets [{:ns "fin.core" :name "price-t"}]}))
              "neither literal was touched — still the text the ask already holds")))
      (testing "an acceptance that never fired is information"
        (let [r (call! sess "edit_group"
                       {:prompt "rename a docstring word; other-t was never going to move"
                        :accept ["fin.core/other-t"]
                        :steps [{"action" "replace" "ns" "fin.core" "name" "price"
                                 "source" "(defn price \"Cents.\" [x] (* 120 x))"}]})]
          (is (re-find #":accept-unused \[fin\.core/other-t\]" r) r)))
      (finally (ops/close! sess)))))

(deftest ^:external a-group-write-is-flagged-untested-like-any-other
  ;; :untested means "no runtime evidence reaches this form". The
  ;; single-form write says so; a group write of the same untested form
  ;; said nothing — and the group is about to become the only write door.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "utg.core" :source "(ns utg.core)\n(defn f [x] x)\n(defn g [x] x)\n"})
      (call! sess "ns_create" {:ns "utg.core-test" :source "(ns utg.core-test (:require [clojure.test :refer [deftest is]] [utg.core :as c]))\n(deftest f-t (is (= 1 (c/f 1))))\n"})
      (call! sess "test_run" {:ns "utg.core-test"})
      (let [r (call! sess "edit_group" {:steps [{:action "replace" :ns "utg.core" :name "g"
                                                 :source "(defn g [x] (identity x))"}]
                                        :prompt "touch the untested fn through a group"})]
        (is (re-find #":untested true" r) r)
        (is (not (re-find #"identity" r)) r))
      (finally (ops/close! sess)))))

(deftest ^:external a-red-write-carries-the-failing-tests-source
  ;; Result-carried orientation: when a write goes red, the next call should
  ;; be the fix, not a read. Each NEWLY red failure carries :test-src — the
  ;; failing test's current source, sent through the same ledger door as any
  ;; other source, so a later read of that test is a reference, not a copy.
  ;; Sibling of :source-now (the match-miss that returns the form's current
  ;; text) and :proposed (the literal fix itself).
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "rc.core" :source "(ns rc.core)\n(defn f [x] (* 2 x))\n"})
      (call! sess "ns_create" {:ns "rc.core-test" :source "(ns rc.core-test (:require [clojure.test :refer [deftest is]] [rc.core :as c]))\n(deftest f-doubles (is (= 4 (c/f 2))))\n"})
      (call! sess "test_run" {:ns "rc.core-test"})
      (testing "a newly red failure carries the failing test's source"
        (let [r (call! sess "edit_group" {:steps [{:action "replace" :ns "rc.core" :name "f"
                                                   :source "(defn f [x] (* 3 x))"}]
                                          :prompt "triple it — f-doubles goes red"})]
          (is (re-find #":test-src" r) r)
          (is (re-find #"deftest f-doubles" r)
              (str "the test's SOURCE rides the failure: " r))
          (testing "…and entered the form ledger: the read-back is a reference"
            (let [q (call! sess "query_source" {:targets ["rc.core-test/f-doubles"]})]
              (is (re-find #":source-already-sent true" q) q)))))
      (testing "a green write carries nothing extra"
        (let [r (call! sess "edit_group" {:steps [{:action "replace" :ns "rc.core" :name "f"
                                                   :source "(defn f [x] (* 2 x))"}]
                                          :prompt "back to doubling — green"})]
          (is (not (re-find #":test-src" r)) r)))
      (finally (ops/close! sess)))))

(deftest ^:external the-bundle-enters-the-form-ledger
  ;; Bundle diet 3a: what the bundle injected, the session HOLDS. The
  ;; endpoint stashes the versions it emitted (:pending-bundle-held); the
  ;; next absorbed ask — the very prompt the bundle rode in with — drains
  ;; them into the form ledger under the NEW ask. A read of a
  ;; bundle-carried form is then a reference, not a second copy: the
  ;; bundle, the write results and the reads share ONE ledger.
  ;;
  ;; The claim comes FIRST: absorbing an intent adopts that id's thread
  ;; line, so a namespace created before the claim would be left behind
  ;; on the pre-claim identity's line.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-bundleledger"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})
        GET  (fn [q] (http/handle! (server/context sess)
                                   {:request-method :get
                                    :uri "/api/bundle"
                                    :query-string q}))]
    (try
      ;; ask 1 claims the session for sid-bl…
      (io/make-parents (io/file dir ".slopp" "pending-intent"))
      (spit (io/file dir ".slopp" "pending-intent")
            "{\"session-id\":\"sid-bl\",\"prompt\":\"look around\"}")
      ;; …and the namespace is created under that identity
      (call! sess "ns_create" {:ns "bl.core" :source "(ns bl.core)\n(defn ^:unused-ok pick [x] x)\n"})
      ;; the hook's GET at the next prompt: the server emits pick's source
      ;; and stashes what it sent
      (let [r (GET "ask=why%20does%20pick%20behave&session-id=sid-bl")
            b (:bundle (:body r))]
        (is (re-find #"defn .:unused-ok pick" (str b)) (str b))
        (is (seq (:versions (:pending-bundle-held @sess)))
            "the emission was stashed for the ledger"))
      ;; the prompt the bundle rode in with arrives; absorbing it drains
      ;; the stash into the ledger
      (spit (io/file dir ".slopp" "pending-intent")
            "{\"session-id\":\"sid-bl\",\"prompt\":\"why does pick behave\"}")
      (call! sess "query_project" {})
      (let [q (call! sess "query_source" {:targets ["bl.core/pick"]})]
        (is (re-find #":source-already-sent true" q)
            (str "a bundle-carried form re-read is a reference: " q)))
      (finally (ops/close! sess)))))

(deftest ^:external a-compacted-reader-can-ask-for-a-resend
  ;; :source-already-sent is a claim about the READER's context, made by the
  ;; server's ledger — and compaction (a summary replacing the transcript,
  ;; invisible on the wire) makes it false precisely for source text, which
  ;; is what a summarizer drops. The dedup stays the default; resend true is
  ;; the reader saying I LOST IT, the one fact only the reader can know.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "rs.core" :source "(ns rs.core)\n(defn ^:unused-ok keeper [x] x)\n"})
      ;; the first read sends the text and holds it…
      (is (re-find #"defn .:unused-ok keeper"
                   (call! sess "query_source" {:targets ["rs.core/keeper"]})))
      ;; …so the re-read is a reference — the default stands
      (is (re-find #":source-already-sent true"
                   (call! sess "query_source" {:targets ["rs.core/keeper"]})))
      (is (re-find #"defn .:unused-ok keeper"
                   (call! sess "query_source" {:targets ["rs.core/keeper"] :resend true}))
          "resend true bypasses the ledger: the full text comes back")
      (finally (ops/close! sess)))))

(deftest ^:external a-change-is-one-call
  ;; s11: two verbs. `change` = the s10 intent pipeline MINUS the done —
  ;; tests land first and are WATCHED going red, impl lands, one
  ;; verification, ONE result — and done stays the agent's SEPARATE move
  ;; when the unit of work is finished: a unit may span change -> explore
  ;; -> change, and the Stop hook is the landing floor. The red path needs
  ;; no special case any more: nothing was landing per call anyway.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "ch.core" :source "(ns ch.core (:require [clojure.test :refer [deftest is]]))\n(defn rate [x] (* 2 x))\n"})
      (testing "one call: tests red-first, impl, verify — and NO done inside"
        (let [r (call! sess "change"
                       {:prompt "rate doubles then adds a fixed fee of 7"
                        :tests [{:action "add" :ns "ch.core"
                                 :source "(deftest rate-fee-t (is (= 27 (rate 10))))"}]
                        :impl  [{:action "patch" :ns "ch.core" :name "rate"
                                 :replace [{:match "(* 2 x)" :source "(+ (* 2 x) 7)"}]}]})
              m (edn/read-string r)]
          (is (re-find #":went-red \[ch.core/rate-fee-t\]" r) r)
          (is (= :green (:status m)) (pr-str (select-keys m [:status :test])))
          (is (nil? (:done m)) "done is the agent's move, not the call's")
          (is (nil? (:land m)) (pr-str (select-keys m [:land :done])))))
      (testing "… and done, called separately, closes and lands the unit"
        (is (re-find #":landed \"main\"" (call! sess "done" {:label "fee unit"}))))
      (testing "a red change reports, carries :test-src, and lands nothing"
        (let [r (call! sess "change"
                       {:prompt "the fee becomes 9 — but this impl gets it wrong"
                        :impl  [{:action "patch" :ns "ch.core" :name "rate"
                                 :replace [{:match "(+ (* 2 x) 7)" :source "(+ (* 2 x) 8)"}]}]})
              m (edn/read-string r)]
          (is (re-find #":test-src" r) r)
          (is (= :red (:status m)) (pr-str (select-keys m [:status])))
          (is (nil? (:land m)) (pr-str (select-keys m [:land :status])))))
      (testing "intent — the announced name — still dispatches as an alias"
        (let [r (call! sess "intent"
                       {:prompt "put the fee back to 7"
                        :impl  [{:action "patch" :ns "ch.core" :name "rate"
                                 :replace [{:match "(+ (* 2 x) 8)" :source "(+ (* 2 x) 7)"}]}]})]
          (is (re-find #":ok true" r) r)))
      (finally (ops/close! sess)))))

(deftest ^:external explore-answers-questions-without-writing
  ;; s11: the question-side verb. Many ops, one call, server does the work
  ;; — the promoted query_batch (the only new op ever adopted unforced) —
  ;; plus `check`: assertion code run in the image with clojure.test's
  ;; reporting CAPTURED, nothing written. The diagnostic red becomes an
  ;; ANSWER (slopp-ui's find-region case): no landed test to clean up, no
  ;; temptation to fix working code against a wrong assertion.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "ex.core" :source "(ns ex.core)\n(defn ^:unused-ok f \"F.\" [x] (* 2 x))\n"})
      (let [before (count (ops/journal sess))]
        (testing "check answers a WRONG assertion with the red, as data"
          (let [r (call! sess "check" {:code "(clojure.test/is (= 5 (ex.core/f 2)))"})]
            (is (re-find #":fail 1" r) r)
            (is (re-find #":actual" r) "the red carries expected/actual — it is the answer")))
        (testing "and a RIGHT one with the green"
          (is (re-find #":pass 1" (call! sess "check" {:code "(clojure.test/is (= 4 (ex.core/f 2)))"}))))
        (testing "explore carries several questions — a check and a read — in ONE call"
          (let [r (call! sess "explore"
                         {:ops [{:op "check" :code "(clojure.test/is (= 5 (ex.core/f 2)))"}
                                {:op "query_source" :targets ["ex.core/f"]}]})]
            (is (re-find #":fail 1" r) r)
            (is (re-find #"defn .:unused-ok f" r) r)))
        (testing "nothing was written — the journal is untouched"
          (is (= before (count (ops/journal sess))))))
      (testing "query_batch — the adopted name — still dispatches as an alias"
        (is (re-find #"ex\.core" (call! sess "query_batch"
                                        {:ops [{:op "query_search" :pattern "unused-ok f"}]}))))
      (finally (ops/close! sess)))))

(deftest ^:external a-step-without-an-action-is-inferred-not-refused
  ;; The s11 canary watched sonnet send {ns name source} steps with no
  ;; action — the only readings the payload admits are :replace (form
  ;; exists) and :add (it does not) — eat \"unknown action:\", and fragment
  ;; ONE write into three groups, a manual module_dep and a second
  ;; full_check. The server does the mechanical work: infer it.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "ia.core" :source "(ns ia.core)\n(defn f \"F.\" [x] x)\n"})
      (let [r (call! sess "edit_group"
                     {:prompt "replace f and add g — no actions stated"
                      :steps [{:ns "ia.core" :name "f"
                               :source "(defn f \"F.\" [x] (inc x))"}
                              {:ns "ia.core"
                               :source "(defn ^:unused-ok g \"G.\" [x] (f x))"}]})]
        (is (re-find #":ok true" r) r)
        (is (re-find #":action :replace" r) (str "f existed — inferred replace: " r))
        (is (re-find #":action :add" r) (str "g did not — inferred add: " r)))
      (finally (ops/close! sess)))))

(deftest ^:external a-refusal-teaches-the-code-vs-source-confusion
  ;; s11 grid, sonnet 41ns step4: a change whose test steps carried :code
  ;; (check's argument) was refused with "unknown action: " — a message that
  ;; named nothing the model had actually done; it self-corrected a turn
  ;; later by luck. The refusal must name the mistake and the key.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "teach.core" :source "(ns teach.core)\n(defn f \"F.\" [x] x)\n"})
      (let [r (call! sess "edit" {:op "change" :prompt "p"
                                  :impl [{:ns "teach.core" :name "g"
                                          :code "(defn g [x] x)"}]})]
        (is (re-find #":code" r) r)
        (is (re-find #":source" r) r)
        (is (re-find #"check" r) r))
      (finally (ops/close! sess)))))

(deftest ^:external a-store-wide-history-ask-is-pointed-at-report
  ;; s11 grid, both models, step5: 10-13 turns of history spelunking, with
  ;; query_history {} retried up to FOUR times against "missing required
  ;; argument :ns" — a refusal that names the gap but not the door. The
  ;; store-wide ask HAS doors (report {since}, query_commits); say so. The
  ;; throwing shape is {name x} with no :ns — the grid models fed turn ids
  ;; as :name; a bare {} answers [] and never reaches the throw.
  (let [sess (external/open!)]
    (try
      (let [r (call! sess "query_history" {:name "fuel-t"})]
        (is (re-find #"missing required argument :ns" r) r)
        (is (re-find #"report" r) r)
        (is (re-find #"query_commits" r) r))
      (finally (ops/close! sess)))))

(deftest ^:external a-read-carries-what-it-requires-so-the-next-read-is-never-asked
  ;; eval12 wave A: replayed against six real transcripts, models read WHOLE
  ;; namespaces and then read their REQUIRES, one edge per turn — 46-61% of
  ;; sonnet's read calls were answerable from the previous read's require
  ;; set at ~3k tokens per lifetime. So the answer to a whole-ns read
  ;; carries those sources up front, marked, and never twice.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "ant.b" :source "(ns ant.b)\n(defn b \"B.\" [x] x)\n"})
      (call! sess "ns_create" {:ns "ant.a" :source "(ns ant.a (:require [ant.b :as b]))\n(defn a \"A.\" [x] (b/b x))\n"})
      (call! sess "ns_create" {:ns "ant.c" :source "(ns ant.c (:require [ant.b :as b]))\n(defn c \"C.\" [x] (b/b x))\n"})
      (testing "a whole-ns read attaches its direct requires' sources, marked"
        (let [r (call! sess "query_source" {:ns "ant.a"})]
          (is (re-find #"defn a" r) r)
          (is (re-find #"defn b" r) "ant.b rode along")
          (is (re-find #":anticipated true" r) r)))
      (testing "what one read attached, a later read does not attach again"
        (let [r (call! sess "query_source" {:ns "ant.c"})]
          (is (re-find #"defn c" r) r)
          (is (not (re-find #":anticipated true" r))
              (str "ant.b is already in the reader's hands: " r))))
      (finally (ops/close! sess)))))

(deftest ^:external a-handoff-shaped-ask-arrives-with-the-report-composed
  ;; eval12: step5-shaped asks ("summarize what changed…") cost 10-13 turns
  ;; of query_history spelunking per cell — with 4 retries against a missing
  ;; :ns in one — while ops/report held the composed answer one call away.
  ;; The bundle is where pre-emption WORKS (the one measured success), so
  ;; the report rides the bundle when the ask reads like a handoff. Through
  ;; the REAL dispatcher: this endpoint is what the prompt hook calls.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "hd.core" :source "(ns hd.core)\n(defn f \"F.\" [x] x)\n"
                               :prompt "the feature the handoff will describe"})
      (let [ctx  (server/context sess)
            ask! (fn [ask]
                   (:body (slopp.http/handle!
                           ctx {:request-method :get :uri "/api/bundle"
                                :query-string (str "ask=" (java.net.URLEncoder/encode (str ask) "UTF-8"))})))]
        (testing "a handoff-shaped ask carries the composed report, marked"
          (let [txt (str (ask! "summarize what changed since the last handoff"))]
            (is (re-find #"composed report" txt) txt)
            (is (re-find #"hd\.core" txt) "the change row reached the injected story")))
        (testing "the phrasings real handoffs use — the first live one matched NOTHING"
          ;; s12 grid, step5 verbatim shapes: "I'm handing this project to a
          ;; teammate… a factual rundown of everything that has changed…"
          (doseq [ask ["I'm handing this project to a teammate tomorrow — give me a factual rundown"
                       "a rundown of everything that has changed here since the original seeded version"
                       "walk me through the changes"]]
            (is (re-find #"composed report" (str (ask! ask))) ask)))
        (testing "an ordinary ask pays no report rent"
          (let [txt (str (ask! "add a discount to quoting"))]
            (is (not (re-find #"composed report" txt)) txt))))
      (finally (ops/close! sess)))))

(deftest ^:external the-cli-door-routes-to-the-running-server
  ;; s12c: opus reached for `slopp --call` unprompted and found the cold
  ;; path — JVM boot + whole-store load + silent minutes — and spent 8
  ;; turns babysitting it. The daemon was right there: the ui listener
  ;; serves the LIVE session. POST /api/call is the routed door: same
  ;; call-op!, same result text (anticipation rows included), token-guarded.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "cd.b" :source "(ns cd.b)\n(defn b \"B.\" [x] x)\n"})
      (call! sess "ns_create" {:ns "cd.a" :source "(ns cd.a (:require [cd.b :as b]))\n(defn a \"A.\" [x] (b/b x))\n"})
      (swap! sess assoc :call-token "tok-1")
      (let [post! (fn [body]
                    (#'mcp/http-call! {:body body :http/deps {:session sess}}))]
        (testing "a wrong or missing token is refused, and runs nothing"
          (is (= 403 (:status (post! {:tool "query_source"
                                      :arguments {:ns "cd.a"}}))))
          (is (= 403 (:status (post! {:tool "query_source" :token "nope"
                                      :arguments {:ns "cd.a"}})))))
        (testing "a routed read answers exactly what MCP answers — anticipation included"
          (let [r (post! {:tool "query_source" :token "tok-1"
                          :arguments {:ns "cd.a"}})
                t (str (:body r))]
            (is (= 200 (:status r)))
            (is (re-find #"defn a" t) t)
            (is (re-find #"anticipated" t) "cd.b rode along, same door as MCP")))
        (testing "a routed WRITE lands with provenance — the prompt opens its turn"
          (let [r (post! {:tool "edit_replace_form" :token "tok-1"
                          :arguments {:ns "cd.b" :name "b"
                                      :source "(defn b \"B!\" [x] x)"
                                      :prompt "via the routed door"}})]
            (is (= 200 (:status r)))
            (is (re-find #":ok true" (str (:body r))) (str (:body r)))))
        (testing "a refusal crosses as isError text, never a stack trace"
          (let [r (post! {:tool "frobnicate" :token "tok-1" :arguments {}})
                t (str (:body r))]
            (is (= 200 (:status r)))
            (is (re-find #"\"isError\":true" t) t)
            (is (re-find #"unknown tool" t) t))))
      (finally (ops/close! sess)))))

(deftest ^{:external true
           :adapter "http — stands in for the shim's python urllib: the proof is that a FOREIGN client opens the door with nothing but .slopp/ui-port and raw HTTP, no slopp facade in the loop"}
  the-cli-door-is-mounted-with-its-token
  ;; the mount half of the routed door: start-ui! passes the /api/call row
  ;; down as data, mints the per-boot token, and writes it into
  ;; .slopp/ui-port beside the address — the file the shim (and the prompt
  ;; hook before it) already trusts. A real POST through the bound port
  ;; answers a routed op from the WARM image — the s12c cold path took
  ;; silent minutes; this asserts the routed one is interactive.
  (let [sess (external/open!)]
    (try
      (let [r (mcp/start-ui! sess 0)]
        (is (:url r) (pr-str r))
        (let [pf    (slurp (str (:dir @sess) "/.slopp/ui-port"))
              token (second (re-find #"\"token\":\"([^\"]+)\"" pf))
              url   (second (re-find #"\"url\":\"([^\"]+)\"" pf))]
          (is (some? token) pf)
          (let [client (java.net.http.HttpClient/newHttpClient)
                post!  (fn [body]
                         (.send client
                                (-> (java.net.http.HttpRequest/newBuilder
                                     (java.net.URI/create (str url "api/call")))
                                    (.header "Content-Type" "application/json")
                                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                                    (.build))
                                (java.net.http.HttpResponse$BodyHandlers/ofString)))
                t0     (System/nanoTime)
                resp   (post! (str "{\"tool\":\"query_project\",\"arguments\":{},\"token\":\"" token "\"}"))
                ms     (quot (- (System/nanoTime) t0) 1000000)]
            (is (= 200 (.statusCode resp)))
            (is (re-find #"\"isError\":false" (.body resp)) (.body resp))
            (is (< ms 2000) (str "routed call took " ms "ms — the warm image must answer interactively"))
            (testing "and the token in the file is the ONLY key that opens it"
              (is (= 403 (.statusCode (post! "{\"tool\":\"query_project\",\"arguments\":{},\"token\":\"wrong\"}"))))))))
      (finally (ops/close! sess)))))

(deftest ^:external a-step-carrying-several-forms-splits-and-infers
  ;; opus's native write grain is the whole blob — 8 heredoc FILES per plain
  ;; lifetime — and the one-form-per-step rule fragmented that into 22
  ;; calls. A nameless, actionless step whose source parses to several
  ;; top-level forms now splits into per-form inferred steps: new names
  ;; add, existing names replace, one atomic group, one verification.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "blob.core" :source "(ns blob.core)\n(defn keep-me \"K.\" [x] x)\n"})
      (testing "a multi-form blob lands as per-form steps, add and replace inferred"
        (let [r (call! sess "edit" {:op "change" :prompt "the whole feature as one blob"
                                    :impl [{:ns "blob.core"
                                            :source "(defn keep-me \"K!\" [x] x)\n\n(defn ^:unused-ok fresh \"F.\" [x] (keep-me x))\n\n(defn ^:unused-ok also \"A.\" [x] (fresh x))"}]})]
          (is (re-find #":ok true" r) r)
          (is (re-find #":replace" r) "keep-me existed — replaced")
          (is (re-find #":add" r) "fresh/also are new — added")))
      (testing "the landed store agrees"
        (let [r (call! sess "query_source" {:ns "blob.core"})]
          (is (re-find #"K!" r) r)
          (is (re-find #"defn \^:unused-ok also" r) r)))
      (testing "a blob whose text does not parse refuses whole, nothing lands"
        (let [r (call! sess "edit" {:op "change" :prompt "p"
                                    :impl [{:ns "blob.core" :source "(defn broken \"B.\" [x"}]})]
          (is (re-find #"error" r) r)
          (is (not (re-find #"broken" (call! sess "query_source" {:ns "blob.core"}))))))
      (finally (ops/close! sess)))))

(deftest ^:external cli-mode-advertises-no-tools
  ;; SLOPP_CLI cells: the surface is the CLI door; the MCP connection stays
  ;; (hooks, lifecycle, the routed /api/call) but advertises NOTHING — the
  ;; ~8k tokens of family schema stop riding every request. De-advertised is
  ;; not closed: dispatch still answers by name, exactly the s8 alias stance.
  (let [sess (external/open!)]
    (try
      (swap! sess assoc :cli-mode? true)
      (is (= [] (get-in (mcp/handle! sess {:id 2 :method "tools/list"})
                        [:result :tools])))
      (testing "dispatch stays open — the Stop hook's done and the routed door ride it"
        (is (re-find #"namespaces" (call! sess "query_project" {}))))
      (finally (ops/close! sess)))))

(deftest ^:external a-blob-leading-with-its-ns-form-creates-the-namespace
  ;; the whole-file gesture: every model writes a NEW file as one blob that
  ;; starts with (ns …). Probed live (s13 wave B): that heredoc refused with
  ;; "no namespace — ingest it first". A change step that IS an ns form for
  ;; an absent namespace now creates it (create-ns! under the hood) before
  ;; the group lands the rest.
  (let [sess (external/open!)]
    (try
      (testing "one blob, new namespace: created, split, verified"
        (let [r (call! sess "edit" {:op "change" :prompt "a new namespace as one heredoc"
                                    :impl [{:ns "nsx.core"
                                            :source "(ns nsx.core (:require [clojure.test :refer [deftest is]]))\n\n(defn triple \"T.\" [x] (* 3 x))\n\n(deftest triple-t (is (= 9 (triple 3))))"}]})]
          (is (re-find #":ok true" r) r)
          (is (re-find #"triple" (call! sess "query_source" {:ns "nsx.core"})))))
      (testing "the same gesture on an EXISTING namespace replaces through the group"
        (let [r (call! sess "edit" {:op "change" :prompt "the same file, edited whole"
                                    :impl [{:ns "nsx.core"
                                            :source "(ns nsx.core (:require [clojure.test :refer [deftest is]]))\n\n(defn triple \"T!\" [x] (* 3 x))\n\n(deftest triple-t (is (= 9 (triple 3))))"}]})]
          (is (re-find #":ok true" r) r)
          (is (re-find #"T!" (call! sess "query_source" {:ns "nsx.core"})))))
      (finally (ops/close! sess)))))

(deftest ^:external a-cli-cells-bundle-speaks-the-cli-loop
  ;; SLOPP_CLI cells advertise no MCP tools, so a bundle telling the reader
  ;; to \"work through the slopp tools\" points at doors that are not there.
  ;; ?cli=1 respells the PREAMBLE only — the ranked map is the same map.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "cv.core" :source "(ns cv.core)\n(defn f \"F.\" [x] x)\n"})
      (let [ctx (server/context sess)
            get! (fn [qs] (str (:body (slopp.http/handle!
                                       ctx {:request-method :get :uri "/api/bundle"
                                            :query-string qs}))))]
        (testing "cli voice: the preamble teaches the CLI verbs"
          (let [t (get! "ask=extend+f&cli=1")]
            (is (re-find #"slopp add" t) t)
            (is (re-find #"slopp change" t) t)
            (is (re-find #"cv\.core" t) "the ranked map is still the map")))
        (testing "without the flag, the MCP voice is unchanged"
          (let [t (get! "ask=extend+f")]
            (is (re-find #"slopp tools" t) t)
            (is (not (re-find #"slopp add" t)) t))))
      (finally (ops/close! sess)))))

(deftest ^:external a-same-ns-red-first-spec-lands-and-is-watched-failing
  ;; THE canonical red-first change — spec first, impl second, ONE call,
  ;; both in the SAME namespace (how inline-test projects, both eval
  ;; terrains, and the CLI heredoc envelope all write) — had no wire cover,
  ;; and it did not work: the tests group failed to compile on the
  ;; unqualified not-yet-written symbol. The reference graph cannot see an
  ;; unqualified same-ns ref; only the load error names it, and the group
  ;; path never consulted that second source (ingest! has, since eval10 s5).
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "rs.core" :source "(ns rs.core (:require [clojure.test :refer [deftest is]]))\n"})
      (let [r (call! sess "change"
                     {:prompt "triple, spec watched red first"
                      :tests [{:ns "rs.core"
                               :source "(deftest triple-t (is (= 9 (triple 3))))"}]
                      :impl  [{:ns "rs.core"
                               :source "(defn triple \"T.\" [x] (* 3 x))"}]})]
        (is (re-find #":ok true" r) r)
        (is (re-find #":went-red \[rs.core/triple-t\]" r)
            (str "the spec was WATCHED failing — the whole point of red-first: " r))
        (is (re-find #":status :green" r) r))
      (finally (ops/close! sess)))))

(deftest ^:external a-live-listeners-address-is-never-clobbered
  ;; s13 cell autopsy: `slopp --help` fell through to serve mode, whose
  ;; start-ui! overwrote the REAL server's .slopp/ui-port with its own pid,
  ;; then died on stdin EOF — 31 dead-pid fallbacks followed, each a silent
  ;; JVM boot. A live listener's file belongs to the process that is alive.
  (let [sess (external/open!)
        pf   (str (:dir @sess) "/.slopp/ui-port")]
    (try
      (clojure.java.io/make-parents pf)
      (testing "a file naming a LIVE pid survives a second server's start-ui!"
        (spit pf "{\"port\":1,\"url\":\"http://127.0.0.1:1/\",\"pid\":1,\"started\":1,\"token\":\"keep-me\"}")
        (mcp/start-ui! sess 0)
        (is (re-find #"keep-me" (slurp pf))
            "the live owner's address stayed; the newcomer must not clobber"))
      (testing "a file naming a DEAD pid is stale and is replaced"
        (spit pf "{\"port\":1,\"url\":\"http://127.0.0.1:1/\",\"pid\":999999999,\"started\":1,\"token\":\"stale\"}")
        (mcp/start-ui! sess 0)
        (is (not (re-find #"stale" (slurp pf))) (slurp pf)))
      (finally (ops/close! sess)))))

(deftest ^:external the-cli-door-speaks-both-vocabularies
  ;; the skill teaches families (read {op …}), the CLI preamble teaches bare
  ;; ops, and the first cell used BOTH — `slopp read '{…}'` answered
  ;; \"unknown tool\". A door that refuses a vocabulary the store elsewhere
  ;; teaches manufactures errors; /api/call resolves ops first, families too.
  (let [sess (external/open!)]
    (try
      (swap! sess assoc :call-token "t2")
      (let [post! (fn [body] (#'mcp/http-call! {:body body :http/deps {:session sess}}))]
        (testing "a FAMILY call with {op} routes exactly like the wire"
          (let [r (post! {:tool "read" :token "t2"
                          :arguments {:op "query_project"}})]
            (is (= 200 (:status r)))
            (is (re-find #"\"isError\":false" (str (:body r))) (str (:body r)))))
        (testing "an op call keeps working as before"
          (let [r (post! {:tool "query_project" :token "t2" :arguments {}})]
            (is (re-find #"\"isError\":false" (str (:body r)))))))
      (finally (ops/close! sess)))))

(deftest ^:external the-cli-door-carries-the-wire-s-hints
  ;; s13 autopsy: a CLI cell's step4 work never landed — 13 writes stranded
  ;; on the thread with nothing saying so, because /api/call invoked
  ;; call-op! without the wire's bindings and every routed result was
  ;; hint-blind. The thread reminder speaks ONCE per session (anti-noise),
  ;; so the routed write here is the session's FIRST private-making change.
  (let [sess (external/open!)]
    (try
      (swap! sess assoc :call-token "t3")
      (let [post! (fn [body] (#'mcp/http-call! {:body body :http/deps {:session sess}}))
            r1 (post! {:tool "ns_create" :token "t3"
                       :arguments {:ns "ht.core"
                                   :source "(ns ht.core)\n(defn f \"F.\" [x] x)\n"
                                   :prompt "the private-making write"}})
            r2 (post! {:tool "edit_replace_form" :token "t3"
                       :arguments {:ns "ht.core" :name "f"
                                   :source "(defn f \"F!\" [x] x)"
                                   :prompt "a second change on the same thread"}})]
        (is (= 200 (:status r2)))
        (is (some #(re-find #"on your thread" (str (:body %))) [r1 r2])
            (str "a routed write must carry the thread reminder: "
                 (pr-str [(:body r1) (:body r2)]))))
      (finally (ops/close! sess)))))

(deftest op-cards-carry-the-argument-teaching
  ;; s13's law: a schema is prepaid argument TEACHING. The cards are that
  ;; teaching at a fraction of the rent — ten ops covering ~95% of measured
  ;; calls, exact argument names, required marked, derived from the
  ;; registry so they cannot drift from what validates.
  (let [cards tools/op-cards]
    (testing "every census op has a card"
      (doseq [op ["change" "query_source" "explore" "done" "ns_create"
                  "rename_sweep" "test_run" "report" "full_check" "query_search"]]
        (is (or (str/starts-with? cards (str op " {"))
                (str/includes? cards (str "\n" op " {")))
            op)))
    (testing "a card teaches the REQUIRED arguments by name"
      (is (re-find #"change \{impl, prompt" cards) cards))
    (testing "the whole block stays bundle-sized"
      (is (< (count cards) 4500) (str (count cards) " chars")))))

(deftest ^:external a-dieted-surface-sheds-prose-and-keeps-every-door
  ;; SLOPP_DIET relocates the op indexes to bundle cards: the advertised
  ;; surface keeps all fourteen families, every op enum, and every schema —
  ;; only the description PROSE shrinks. Dispatch is untouched.
  (let [sess (external/open!)]
    (try
      
      (let [ts (get-in (mcp/handle! sess {:id 2 :method "tools/list"}) [:result :tools])]
        (testing "all fourteen families, op enums intact"
          (is (= 14 (count ts)))
          (is (some #{"change"} (some #(when (= "edit" (:name %))
                                         (get-in % [:inputSchema :properties :op :enum])) ts))))
        (testing "the prose is gone; the pointer to the cards replaces it"
          (is (< (count (pr-str ts)) 15000) (str (count (pr-str ts))))
          (is (re-find #"\[slopp\] block|help \{topic" (str (:description (first ts)))))))
      (testing "dispatch is untouched"
        (is (re-find #"namespaces" (call! sess "query_project" {}))))
      (testing "the bundle carries the cards when the hook asks with diet=1"
        (swap! sess assoc :op-cards tools/op-cards)
        (let [ctx (server/context sess)
              txt (str (:body (slopp.http/handle!
                               ctx {:request-method :get :uri "/api/bundle"
                                    :query-string "ask=extend+the+quote"})))]
          (is (re-find #"op cards" txt) txt)
          (is (re-find #"change \{impl, prompt" txt))))
      (finally (ops/close! sess)))))

(deftest ^:external an-aliased-require-upgrades-a-bare-one
  ;; s14 sonnet-XL, measured: a scaffold with BARE requires + a form using
  ;; the alias = \"No such namespace\", and every repair path refused with
  ;; \"already required\" — 18 remove/add calls to rebuild the ns form.
  ;; Adding an alias to a bare clause is an UPGRADE in place.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "upq.core" :source "(ns upq.core)\n(defn g \"G.\" [x] x)\n"})
      (call! sess "ns_create" {:ns "upq.user" :requires ["upq.core"]
                               :prompt "a consumer scaffolded with a BARE require"})
      (testing "the aliased spec upgrades the bare clause — no remove, no staircase"
        (let [r (call! sess "ns_add_require" {:ns "upq.user" :require "[upq.core :as core]"
                                              :prompt "the upgrade"})]
          (is (re-find #":ok true" r) r))
        (let [src (call! sess "query_source" {:ns "upq.user"})]
          (is (re-find #"\[upq\.core :as core\]" src) src)
          (is (not (re-find #"upq\.core\)?\s+upq\.core" src)) "one clause, not two")))
      (testing "an identical spec keeps the honest refusal"
        (is (re-find #"already required"
                     (call! sess "ns_add_require" {:ns "upq.user" :require "[upq.core :as core]"
                                                   :prompt "again"}))))
      (testing "a DIFFERENT alias refuses and names both spellings"
        (let [r (call! sess "ns_add_require" {:ns "upq.user" :require "[upq.core :as c2]"
                                              :prompt "conflict"})]
          (is (re-find #":as core" r) r)))
      (finally (ops/close! sess)))))

(deftest ^:external the-group-door-self-repairs-a-bare-require-into-its-alias
  ;; the s14 loop, end to end: scaffold with a bare require, a change whose
  ;; form speaks the alias — auto-require now UPGRADES the clause and the
  ;; group lands stamped :auto-require, instead of an 18-call rebuild.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "fixq.core" :source "(ns fixq.core)\n(defn quote-cents \"Q.\" [x] (* 100 x))\n"})
      (call! sess "ns_create" {:ns "fixq.ship" :requires ["fixq.core"]
                               :prompt "scaffolded bare, like the real cell"})
      (let [r (call! sess "change"
                     {:prompt "a form that speaks the alias the scaffold never declared"
                      :impl [{:ns "fixq.ship"
                              :source "(defn ^:unused-ok total \"T.\" [x] (core/quote-cents x))"}]})]
        (is (re-find #":ok true" r) r)
        (is (re-find #":auto-require" r) r)
        (is (re-find #"\[fixq\.core :as core\]"
                     (call! sess "query_source" {:ns "fixq.ship"}))
            "the bare clause was upgraded in place"))
      (finally (ops/close! sess)))))

(deftest ^:external done-pre-empts-the-ritual-closing-full-check
  ;; s14 opus census: full_check x5 per cell, one per step as a closing
  ;; ritual after done — each a whole-store re-run plus a fat payload that
  ;; compounds as rent. The two facts that make it redundant exist at done
  ;; time; done now states them. Answer-shaped, never instructive — and
  ;; only when a GREEN whole-store verdict exists to cite.
  (let [sess (external/open!)]
    (try
      (call! sess "ns_create" {:ns "ws.core" :source "(ns ws.core)\n(defn ^:unused-ok f \"F.\" [x] x)\n"})
      (testing "no whole-store verdict yet — done claims nothing about one"
        (is (not (re-find #":whole-store" (call! sess "done" {:label "first"})))))
      (call! sess "full_check" {})
      (call! sess "edit_replace_form" {:ns "ws.core" :name "f"
                                       :source "(defn ^:unused-ok f \"F!\" [x] x)"
                                       :prompt "a change after the whole-store check"})
      (testing "after a green full_check, done carries the two facts"
        (let [r (call! sess "done" {:label "second"})]
          (is (re-find #":whole-store" r) r)
          (is (re-find #"was green" r) r)))
      (finally (ops/close! sess)))))

^:unsafe (deftest the-spool-holds-the-remainder-not-a-second-copy
  ;; s14 payload audit: query_detail retrievals averaged 18.8k chars — the
  ;; model re-buying the half it already held. The spool entry for an
  ;; item-trimmed response is the REMAINDER: dropped items only, headed by
  ;; where they continue from. ^:unsafe: rebinds *spool-session* exactly as
  ;; the wire layer does — that binding IS the seam under test.
  (let [rows (mapv (fn [i] {:i i :pad (apply str (repeat 200 "x"))}) (range 80))
        f    (#'mcp/fit-payload rows 7800 "rT")]
    (is (some? (:dropped f)) (pr-str (keys f)))
    (is (str/includes? (:dropped f) "REMAINDER"))
    (is (not (str/includes? (:dropped f) ":i 0")) "the shown head is not re-spooled")
    (is (str/includes? (:dropped f) ":i 79") "the tail is all there"))
  (testing "and text! swaps the remainder into the spool"
    (let [sess (atom {})
          rows (mapv (fn [i] {:i i :pad (apply str (repeat 300 "y"))}) (range 60))]
      (binding [mcp/*spool-session* sess]
        (#'mcp/text! rows))
      (let [entry (first (vals (get-in @sess [:slopp.mcp/spool :entries])))]
        (is (string? entry))
        (is (str/includes? entry "REMAINDER") (subs (str entry) 0 (min 120 (count (str entry)))))
        (is (not (str/includes? entry ":i 0")))))))

(deftest a-refused-land-is-never-rendered-as-a-bare-green-done
  ;; s16 overlap probe, measured: two sessions replaced the same form; the
  ;; second session's land refused with conflicts — and the wire rendered
  ;; the SAME bare green one-liner a landed done gets, minus only the
  ;; :landed key. The agent read success, told its user "done and green",
  ;; and the work sat unlanded on its thread. A refusal is findings, not
  ;; decoration: the full report comes back, refusal and recovery path
  ;; included, exactly as a red done keeps its findings. A done with no
  ;; :land at all (nothing to land) still terses — absence and refusal are
  ;; different facts.
  (let [green   {:done "d1"
                 :findings {:episode-status :green :test-status :green
                            :lint-errors 0 :unloadable-namespaces []}}
        refused (assoc green :land
                       {:landed false
                        :conflicts [{:form 'x.y/f}]
                        :reason "main moved while you worked, and rebasing onto it conflicts — resolve, then call done again"})
        out     (#'mcp/terse-done refused)]
    (is (= false (get-in out [:land :landed]))
        "the refusal reaches the agent")
    (is (re-find #"call done again" (str (get-in out [:land :reason])))
        "with its recovery path, not just a flag")
    (is (some? (:findings out))
        "a refused land keeps the full report, like a red done")
    (let [ok (#'mcp/terse-done (assoc green :land {:landed "main" :head "dX"}))]
      (is (= "main" (:landed ok)) "a landed green still terses")
      (is (nil? (:findings ok)) "to the one-liner"))))
