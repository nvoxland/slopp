(ns slopp.api.server-test
  "The project listener's two promises: it serves the CALLER's session (the
  reason it is not the MCP transport), and its address is derived rather than
  fixed, so two projects on one machine never fight for a port."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api.server :as server]
            [slopp.store :as store]
            [slopp.http :as slopp.http] [clojure.edn :as edn] [slopp.http.client :as http.client] [clojure.set :as set] [clojure.string :as str] [slopp.api.otel :as otel] [slopp.store.db :as db]))

(deftest ^:external ui-serve-serves-the-callers-own-session
  ;; The listener serves the CALLER's session rather than opening one. A
  ;; fresh session is not blank — :test-map and :observed persist and reload
  ;; — but it is BEHIND the session doing the work, and costs a second image
  ;; to be behind in. A page that showed the warranty as of the last write
  ;; instead of as of now would be wrong exactly when someone is watching it
  ;; change. The proof is a namespace that exists ONLY in the session handed
  ;; over: if the page can see it, the page is reading that session.
  ;;
  ;; (This used to be phrased against slopp.mcp.http/start-server!, which
  ;; opened a fresh one. That transport is retired; the reason stands on its
  ;; own, because it was always about staleness rather than about the other
  ;; server.)
  (let [st   (store/ingest (store/empty-store) 'demo.only.here
                           "(ns demo.only.here)\n\n(defn f [] 1)\n")
        sess (atom {:store st})]
    (try
      (let [r (server/serve! sess 0)]
        (testing "the BOUND port is reported, not the requested one"
          (is (pos? (:port r)) "port 0 means ephemeral — echoing 0 back would be a lie")
          (is (= (str "http://127.0.0.1:" (:port r) "/") (:url r))
              "the url lands on the reviewer's front door"))
        (testing "the served DATA comes from the handed-over session"
          ;; the document is an empty mount point now and carries no store
          ;; content at all, so the proof moved one layer down to the API the
          ;; client fetches. Same evidence: a namespace that exists ONLY in
          ;; this session appears, so this session is what is being served.
          (is (re-find #"demo\.only\.here"
                       (:http/body
                        (http.client/request
                         {:http/url (str "http://127.0.0.1:" (:port r)
                                         "/api/namespaces")}))))))
      (finally (server/stop!)))))

(deftest ^:external ui-serve-evicts-itself-and-names-a-taken-port
  ;; Two stances taken from Clerk, which learned both the hard way: never
  ;; port-hunt (the url you were told stops being the url that works), and
  ;; say "port N is not available" rather than surfacing a BindException.
  (let [sess (atom {:store (store/empty-store)})]
    (try
      (testing "serving again evicts the previous server — one UI, one port"
        (let [a (server/serve! sess 0)
              b (server/serve! sess 0)]
          (is (not= (:port a) (:port b)) "a second ephemeral bind is a different port")
          (is (= (:port b) (:port (server/running)))
              "the tracked server is the live one")
          (is (= :unreachable
                     (:http/error
                      (try (http.client/request
                            {:http/url (str "http://127.0.0.1:" (:port a) "/store")})
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))))
              "the evicted server is actually stopped, not merely forgotten —
               and asserting the port's :http/error rather than a bare
               IOException is the point: a stopped server and a server that
               said no are different facts, and only one of them is this")))
      (finally (server/stop!))))
  (testing "a port someone else holds is reported as a sentence, not a stack trace"
    (let [held (slopp.http/serve! {:http/namespaces [] :http/port 0})]
      (try
        (let [r (server/serve! (atom {:store (store/empty-store)}) (:port held))]
          ;; the same sentence every listener uses — slopp.http/bind-diagnosis writes
          ;; it once. Three listeners phrased this three ways ("is not available"
          ;; here, "is already in use" in the dev server, a raw BindException in
          ;; production), which is the disagreement this consolidates.
          (is (= (str "port " (:port held) " is already in use") (:error r)))
          (is (nil? (server/running)) "a failed bind leaves nothing tracked"))
        (finally (slopp.http/stop! held) (server/stop!))))))

(deftest the-ui-port-is-derived-from-the-dir-and-the-formula-is-frozen
  (testing "stable across calls, so a url that worked last session still does"
    (is (= (server/derived-port "/w/a") (server/derived-port "/w/a"))))
  (testing "inside the private range"
    (is (<= 49152 (server/derived-port "/w/a") 65535)))
  (testing "two projects on one machine get two ports — the collision a fixed
            default guaranteed, and the reason there is no port setting here
            at all any more"
    (is (not= (server/derived-port "/w/a") (server/derived-port "/w/b"))))
  ;; The salt was originally to dodge the git listener's port for the same
  ;; dir. That listener is gone, so this no longer separates it from
  ;; anything — it pins the formula instead, which is the property that
  ;; actually matters now: the derivation IS the address, so changing it
  ;; relocates every project's UI and strands every saved url.
  (testing "the formula is frozen — an unsalted hash is a DIFFERENT address"
    (is (not= (server/derived-port "/w/a")
              (+ 49152 (mod (hash "/w/a") 16384))))))

(deftest the-preferred-port-is-derived-and-never-configured
  ;; `slopp.api.port` was a CAPABILITY until phase 2 (2026-08-03). The knob
  ;; went; the derivation stayed. That combination is easy to state backwards,
  ;; so: the number is an OUTPUT — nobody sets it — but it is the SAME output
  ;; on every restart, because the formula IS the address and a port that
  ;; moves strands whatever held the url (D-hub). Unconfigured, not unstable.
  ;;
  ;; The knob was removable because nothing used it: the one external adopter
  ;; sets three capability values and this was not among them, and
  ;; `ui_serve {port}` already covers what a pin was for — wanting a specific
  ;; address for one run.
  ;;
  ;; There is no "a stale value is ignored" case to assert here any more, and
  ;; that is the strongest form of the guarantee rather than a gap in it: the
  ;; fn takes no store, so no reader can be left behind for one caller. What
  ;; a removed capability still owes is that the REGISTRY stopped governing
  ;; the key, which `slopp.project.capabilities-test` asserts.
  (testing "an explicit request wins — ui_serve {port} still means that port"
    (is (= 9000 (server/preferred-port "/w/a" 9000))))
  (testing "otherwise the derivation, which is now the only ordinary case"
    (is (= (server/derived-port "/w/a")
           (server/preferred-port "/w/a" nil))))
  (testing "an ephemeral session has no dir to derive from, so it takes
            whatever port is free rather than refusing to serve"
    (is (= 0 (server/preferred-port nil nil)))))

(deftest ^:external a-real-server-publishes-its-own-contract
  ;; This used to be about serve! threading its served-namespaces list into
  ;; perform-ctx: forget it and the document answers 200 with zero endpoints,
  ;; a consumer generates an empty client, and nothing looks broken until a
  ;; call that was never generated goes missing.
  ;;
  ;; That list is no longer the source. The document follows the STORE — what
  ;; this application DECLARES — because the reviewer listener serves slopp's
  ;; own API and describing that as the project's was the bug. So the failure
  ;; this guards moved with it: forget to thread the SESSION and the document
  ;; is empty for the same reason, with the same silence.
  ;;
  ;; In-image tests still cannot catch it — they build perform-ctx themselves,
  ;; so they pass whether or not the SERVER does. Only a real serve! can tell,
  ;; and that is why this one is ^:external.
  (testing "a store that DECLARES endpoints gets them published"
    ;; the declaration is a stub; the schemas come from the loaded vars, which
    ;; is the store-says-WHICH / image-says-WHAT split
    (let [sess (atom {:store (store/ingest
                              (store/empty-store) 'slopp.api.endpoints
                              (str "(ns slopp.api.endpoints)\n\n"
                                   "(defn ^{:http/path \"/api/timeline\" :http/method :get"
                                   " :http/auth :public}\n  timeline \"T.\" [_] {:status 200})\n"))})]
      (try
        (let [r   (server/serve! sess 0)
              doc (edn/read-string
                   (:http/body
                    (http.client/request
                     {:http/url (str "http://127.0.0.1:" (:port r) "/api/rest/paths")})))
              paths (set (map :path (:paths doc)))]
          (is (= #{:paths} (set (keys doc))) (pr-str (keys doc)))
          (is (contains? paths "/api/timeline")
              "a server that forgot to thread its session publishes nothing")
          (is (contains? paths "/api/modules")
              "and the whole namespace's surface arrives, read off the loaded vars"))
        (finally (server/stop!)))))

  (testing "and a store that declares NOTHING publishes nothing"
    ;; the property the change is for: this listener is serving the reviewer
    ;; API in order to answer at all, and must not describe it as the
    ;; application's. Before, an empty store published all nine reviewer paths
    (let [sess (atom {:store (store/empty-store)})]
      (try
        (let [r   (server/serve! sess 0)
              doc (edn/read-string
                   (:http/body
                    (http.client/request
                     {:http/url (str "http://127.0.0.1:" (:port r) "/api/rest/paths")})))]
          (is (= #{:paths} (set (keys doc))) (pr-str (keys doc)))
          (is (empty? (:paths doc))
              (str "the listener's own surface is the MCP server's, not this"
                   " project's: " (pr-str (map :path (:paths doc))))))
        (finally (server/stop!))))))

(deftest the-served-list-is-checked-against-what-declares-endpoints
  ;; `served-namespaces`' docstring argues at length that the list must be
  ;; SINGULAR — it had two mounts once, and a literal repeated at both is how
  ;; a namespace ends up served by nobody. That argument is correct and it
  ;; solved half the problem: the DUPLICATION half. The remaining single list
  ;; can still fall behind the code it stands for, and nothing compared them.
  ;;
  ;; Found by slopp-ui, who took the "hand-kept list vs something derivable"
  ;; shape, applied it to their own store, hit the identical defect in their
  ;; own `served-namespaces`, and handed back the heuristic that finds these:
  ;; **look for prose arguing that a list should be singular.** That argument
  ;; is made by an author who has noticed the list is load-bearing — which is
  ;; exactly when the derivation gap gets written and not seen.
  ;;
  ;; Derived from the IMAGE rather than a store: loaded vars carry their own
  ;; metadata, so this needs no store read — which matters, because nothing
  ;; in this tier can open slopp's own store.
  (let [declares? (fn [nsx] (some #(let [m (meta %)]
                                     (or (:rest/path m) (:http/path m) (:http/read m)))
                                  (vals (ns-publics nsx))))
        candidates (->> (all-ns) (map ns-name)
                        ;; the prefix is DATA — a rename rewrites code and
                        ;; walks straight past a string, so this scan can come
                        ;; to match nothing. That is why the liveness check
                        ;; below is not decoration: phase 2 broke this literal
                        ;; and the check turned it red. A sibling guard without
                        ;; one shipped green against an empty search.
                        (filter #(str/starts-with? (str %) "slopp.api."))
                        ;; endpoint-shaped forms in tests are fixtures and
                        ;; claim no route — query_surface scopes the same way
                        (remove #(str/ends-with? (str %) "-test")))
        derived    (set (filter declares? candidates))
        listed     (set server/served-namespaces)]
    (testing "the scan found something — two empty sets agree"
      ;; the same trap as `crossings/unclassified-markers` returning empty
      ;; while a marker went unclassified for a week: a check whose population
      ;; can silently become zero reports success for the wrong reason
      (is (seq derived) (str "scanned " (count candidates) " namespaces")))
    (testing "and the list is exactly what declares an endpoint or a read performer"
      (is (= derived listed)
          (str "declares :http/path or :http/read but is not served: "
               (set/difference derived listed)
               " / served but declares neither: "
               (set/difference listed derived))))))

(deftest ^:external the-listener-RECEIVES-harness-telemetry-and-records-it
  ;; The one thing slopp cannot observe about itself: how many tokens a request
  ;; carried, what it cost, and how full the conversation is. It arrives as
  ;; OTLP over the listener that already exists.
  ;;
  ;; ^:external and through a REAL serve! deliberately: the mount is an
  ;; explicit route row in `serving-opts`, and an in-image test that builds its
  ;; own context would pass whether or not the LISTENER carries the row — the
  ;; exact failure the sibling test above this one was written for.
  ;;
  ;; The session needs a JOURNAL because a measurement is not a delta. It was
  ;; one for about an hour and the store became unwritable: an exporter posts
  ;; on its own interval and moved the head every writer compare-and-swaps
  ;; against, so `full_check` lost the race four times running. This test
  ;; asserted the delta for as long as that was true and nothing ran it after
  ;; it stopped being true.
  (let [dir     (str (System/getProperty "java.io.tmpdir") "/slopp-otel-" (System/nanoTime))
        journal (db/open! dir)
        sess    (atom {:store (store/empty-store) :db journal})
        payload (str "{\"resourceLogs\":[{\"scopeLogs\":[{\"logRecords\":["
                     "{\"timeUnixNano\":\"1787881784700000000\","
                     "\"body\":{\"stringValue\":\"claude_code.api_request\"},"
                     "\"attributes\":["
                     "{\"key\":\"session.id\",\"value\":{\"stringValue\":\"s-abc\"}},"
                     "{\"key\":\"user.email\",\"value\":{\"stringValue\":\"someone@example.com\"}},"
                     "{\"key\":\"prompt.id\",\"value\":{\"stringValue\":\"p-1\"}},"
                     "{\"key\":\"model\",\"value\":{\"stringValue\":\"claude-opus-5\"}},"
                     "{\"key\":\"input_tokens\",\"value\":{\"intValue\":2}},"
                     "{\"key\":\"output_tokens\",\"value\":{\"intValue\":4}},"
                     "{\"key\":\"cache_read_tokens\",\"value\":{\"intValue\":9987}},"
                     "{\"key\":\"cache_creation_tokens\",\"value\":{\"intValue\":7559}},"
                     "{\"key\":\"cost_usd\",\"value\":{\"doubleValue\":0.08}},"
                     "{\"key\":\"duration_ms\",\"value\":{\"intValue\":1475}}"
                     "]}]}]}]}")]
    (try
      (testing "the receiver answers when called directly"
        ;; told apart from the transport on purpose: a 500 with a generic body
        ;; looks identical whether the handler threw or the mount is missing
        (is (= 200 (:status (otel/logs {:http/deps {:session sess} :body payload})))))
      (testing "an unparseable export is 400 — NEVER a 500 an exporter retries forever"
        ;; found by a fixture of mine that was missing two brackets: the handler
        ;; threw, the server answered 500, and an exporter treats 5xx as
        ;; retryable — so an unreadable batch would come back on every interval
        ;; for as long as the process lived. 400 is OTLP's non-retryable answer.
        (let [r (otel/logs {:http/deps {:session sess}
                            :body "{\"resourceLogs\":[{\"scopeLogs\":["})]
          (is (= 400 (:status r)) (pr-str r))
          (is (clojure.string/includes? (str (:body r)) "could not parse") (pr-str r))))
      (try
        (let [before (count (db/measurements journal "otel" nil))
              r      (server/serve! sess 0)
              url    (str (:url r) "v1/logs")
              http   (doto ^java.net.HttpURLConnection
                           (.openConnection (java.net.URL. url))
                       (.setRequestMethod "POST")
                       (.setRequestProperty "Content-Type" "application/json")
                       (.setDoOutput true))]
          (with-open [o (.getOutputStream http)]
            (.write o (.getBytes payload "UTF-8")))
          (testing "the exporter is answered with OTLP's success shape"
            (let [code (.getResponseCode http)]
              (is (= 200 code)
                  (str "server said " code ": "
                       (try (slurp (.getErrorStream http))
                            (catch Throwable t (str "no error stream: " t)))))))

          (let [ms (db/measurements journal "otel" nil)
                p  (:payload (last ms))]
            (testing "and the batch is recorded beside the journal, not IN it"
              (is (= (inc before) (count ms))
                  (str "the post over the wire must have added exactly one row: " (pr-str ms)))
              (is (empty? (filter #(= :otel (:op %)) (:deltas (:store @sess))))
                  "a measurement must never move the head a writer CASes against")
              (is (= 1 (count (:requests p))) (pr-str p)))

            (testing "carrying the CONTEXT SIZE the CLI never sends as a field"
              (is (= (+ 2 9987 7559) (:context (first (:requests p))))
                  (pr-str (first (:requests p)))))

            (testing "and NOT carrying the operator's email, which rides every raw record"
              (is (not (clojure.string/includes? (pr-str p) "someone@example.com"))
                  "an identity attribute reached the measurements table"))))
        (finally (server/stop!)))
      (finally (.close journal)))))

(deftest ^:external a-served-route-table-says-when-the-store-moved-under-it
  ;; Friction #12, and the shape of Cause 1: the route table and both
  ;; performer vocabularies are assembled ONCE at serve time and the running
  ;; listener holds them. Add an endpoint after that and it answers 404 —
  ;; correctly, for the table it has — while the store says the route exists.
  ;; `serving-opts` already documents the re-serve requirement in a comment,
  ;; which is a thing a reader has to have read; nothing MEASURED it.
  ;;
  ;; The listener cannot re-derive itself cheaply, and that is fine. What it
  ;; must not do is answer as though there were nothing to know.
  (let [dir  (str (System/getProperty "java.io.tmpdir") "/slopp-served-" (System/nanoTime))
        conn (db/open! dir)]
    (try
      (let [trunk (db/trunk-line-id! conn)
            st    (store/ingest (store/empty-store) 'demo.served
                                "(ns demo.served)\n\n(defn f [] 1)\n")
            _     (db/append! conn st (store/deltas st) ['demo.served] trunk nil)
            sess  (atom {:store st :db conn :line trunk})]
        (try
          (let [r (server/serve! sess 0)]
            (testing "a fresh listener says what store state its table came from"
              (is (true? (:current? r)) (pr-str r))
              (is (= trunk (:line (:derived-from r))) (pr-str r)))

            (testing "and WHICH PROCESS answers that url"
              ;; the costliest of the six ways two readers of one store
              ;; disagree, and the one neither agent considered for an
              ;; evening: `query_eval` runs in a child image JVM while a
              ;; served listener runs in the MCP host. Same store, same code,
              ;; two loading strategies — and the only reason it was ever
              ;; found is that an eval happened to print its own pid.
              (let [p (:process (server/serving sess))]
                (is (= (:pid (db/this-process)) (:pid p)) (pr-str p))
                (is (some? (:started p))
                    "pids are reused, so the pair is the identity")))

            (testing "and reports itself behind once the line moves under it"
              (let [st2 (store/ingest st 'demo.later "(ns demo.later)\n\n(defn g [] 2)\n")
                    new (vec (drop (count (store/deltas st)) (store/deltas st2)))]
                (is (true? (db/append! conn st2 new ['demo.later] trunk
                                       (:head (:derived-from r))))
                    "fixture: the line really did move under the listener")
                (let [s (server/serving sess)]
                  (is (= (:url r) (:url s)) "the same listener is still up")
                  (is (false? (:current? s)) (pr-str s))
                  (is (some? (:why s)) "and it says what to do about it")))))
          (finally (server/stop!))))
      (finally (.close conn)))))
