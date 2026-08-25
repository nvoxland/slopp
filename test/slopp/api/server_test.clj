(ns slopp.api.server-test
  "The project listener's two promises: it serves the CALLER's session (the
  reason it is not the MCP transport), and its address is derived rather than
  fixed, so two projects on one machine never fight for a port."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api.server :as server]
            [slopp.store :as store]
            [slopp.http :as slopp.http] [clojure.edn :as edn] [slopp.http.client :as http.client] [clojure.set :as set] [clojure.string :as str]))

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
  ;; perform-ctx: forget it and /api/contracts answers 200 with zero endpoints,
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
                     {:http/url (str "http://127.0.0.1:" (:port r) "/api/contracts")})))
              paths (set (map :path (:endpoints doc)))]
          (is (= 2 (:slopp/contract-version doc)))
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
                     {:http/url (str "http://127.0.0.1:" (:port r) "/api/contracts")})))]
          (is (= 2 (:slopp/contract-version doc)))
          (is (empty? (:endpoints doc))
              (str "the listener's own surface is the MCP server's, not this"
                   " project's: " (pr-str (map :path (:endpoints doc))))))
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
