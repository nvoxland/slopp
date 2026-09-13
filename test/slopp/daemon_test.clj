(ns slopp.daemon-test
  "The daemon: one process, every project, attached exactly while some agent
  is. Driven through the socket-free dispatch, so what is tested is the
  envelope and the lifecycle rather than a port."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [slopp.daemon :as daemon]
            [slopp.http :as slopp.http] [slopp.ops.external :as external] [slopp.ops :as ops] [slopp.cache :as cache] [slopp.mcp :as mcp] [slopp.http.routes :as routes] [clojure.string :as str] [slopp.sync :as sync] [slopp.store :as store] [clojure.java.shell :as sh]))

(defn- tmp-dir!
  "A fresh empty directory: a project nobody has written to yet. Canonical,
  because that is what the registry holds (a temp dir on macOS is a symlink
  away from itself)."
  []
  (let [f (java.io.File/createTempFile "slopp-daemon" "")]
    (.delete f)
    (.mkdirs f)
    (.getCanonicalPath f)))

(defn- projects!
  "What /api/projects lists, as data."
  [ctx]
  (:body (slopp.http/handle! ctx {:request-method :get :uri "/api/projects"})))

(deftest ^:external an-agent-attaches-by-dir-and-the-daemon-serves-its-project
  ;; The client names its project by DIR (a header written into .mcp.json
  ;; before any daemon exists); the slug in the path is the display name. A
  ;; session is minted on initialize and echoed back by the client; it is
  ;; the attachment. Two dirs are two projects. The last DELETE closes one.
  (let [d1   (tmp-dir!)
        d2   (tmp-dir!)
        ctx  (daemon/context)
        post (fn [dir slug sid msg]
               (slopp.http/handle! ctx {:request-method :post
                                        :uri (str "/api/projects/" slug "/mcp")
                                        :headers (cond-> {"x-slopp-dir" dir}
                                                   sid (assoc "mcp-session-id" sid))
                                        :body msg}))
        body (fn [r] (json/parse-string (:body r) true))
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-03-26" :capabilities {}
                       :clientInfo {:name "t" :version "0"}}}]
    (try
      (let [r   (post d1 "one" nil init)
            sid (get-in r [:headers "Mcp-Session-Id"])]
        (is (= 200 (:status r)) (pr-str r))
        (is (string? sid) (pr-str r))
        (is (= "slopp" (get-in (body r) [:result :serverInfo :name])) (pr-str r))
        (testing "the project is open and listed, with its one attachment"
          (let [ps (projects! ctx)]
            (is (= [d1] (mapv :dir ps)) (pr-str ps))
            (is (= 1 (:sessions (first ps))) (pr-str ps))
            (is (= "one" (:slug (first ps))) (pr-str ps))))
        (testing "a call on the session reaches the store: thread_open answers"
          (let [r   (post d1 "one" sid {:jsonrpc "2.0" :id 2 :method "tools/call"
                                        :params {:name "thread_open"
                                                 :arguments {:thread "t-http"}}})
                txt (get-in (body r) [:result :content 0 :text])]
            (is (= 200 (:status r)) (pr-str r))
            (is (re-find #"t-http" (str txt)) (pr-str r))))
        (testing "a notification is accepted with no body"
          (let [r (post d1 "one" sid {:jsonrpc "2.0" :method "notifications/initialized"})]
            (is (= 202 (:status r)) (pr-str r))))
        (testing "a second dir is a second project with its own session"
          (let [r2 (post d2 "two" nil init)
                ps (projects! ctx)]
            (is (= 200 (:status r2)) (pr-str r2))
            (is (= #{d1 d2} (set (map :dir ps))) (pr-str ps))
            (daemon/detach! (get-in r2 [:headers "Mcp-Session-Id"]))))
        (testing "an unknown session that names its dir is re-attached under a new id"
          (let [r (post d1 "one" "nope" {:jsonrpc "2.0" :id 3 :method "ping"})
                new (get-in r [:headers "Mcp-Session-Id"])]
            (is (= 200 (:status r)) (pr-str r))
            (is (and (string? new) (not= "nope" new)) (pr-str (:headers r)))
            (daemon/detach! new)))
        (testing "a standalone stream is declined, not broken"
          (let [r (slopp.http/handle! ctx {:request-method :get :uri "/api/projects/one/mcp"
                                           :headers {"mcp-session-id" sid}})]
            (is (= 405 (:status r)) (pr-str r))))
        (testing "a body that is not one JSON-RPC message is the contract's 400"
          (let [r (post d1 "one" sid "{not json")]
            (is (= 400 (:status r)) (pr-str r))))
        (testing "DELETE detaches, and the last detach closes the project"
          (let [r (slopp.http/handle! ctx {:request-method :delete :uri "/api/projects/one/mcp"
                                           :headers {"mcp-session-id" sid}})]
            (is (= 200 (:status r)) (pr-str r)))
          (is (empty? (projects! ctx)) (pr-str (projects! ctx)))))
      (finally (daemon/reset-all!)))))

(deftest ^:external a-projects-api-and-telemetry-are-served-under-the-one-prefix
  ;; One root: the project's typed read API answers at /api/projects/<slug>/
  ;; <resource> — its contract declares /api/<resource>, and the mount
  ;; replaces that prefix rather than nesting under it; the machine's one
  ;; OTLP sink routes a batch by session id — which is the THREAD id since
  ;; Phase 0 — to the project holding that thread, and says what it could
  ;; not place. And it keeps routing after the project closed: an exporter
  ;; posts on its own clock, and a project that just closed was where most
  ;; of its records were going.
  (let [d    (tmp-dir!)
        ctx  (daemon/context)
        post (fn [slug sid msg]
               (slopp.http/handle! ctx {:request-method :post
                                        :uri (str "/api/projects/" slug "/mcp")
                                        :headers (cond-> {"x-slopp-dir" d}
                                                   sid (assoc "mcp-session-id" sid))
                                        :body msg}))
        rec  (fn [sid] {:body {:stringValue "claude_code.api_request"} :timeUnixNano "1"
                        :attributes [{:key "session.id" :value {:stringValue sid}}
                                     {:key "model" :value {:stringValue "m"}}
                                     {:key "input_tokens" :value {:intValue "10"}}
                                     {:key "output_tokens" :value {:intValue "5"}}]})
        batch {:resourceLogs [{:scopeLogs [{:logRecords [(rec "t-tel") (rec "nobody")]}]}]}
        otel! (fn [] (slopp.http/handle! ctx {:request-method :post :uri "/api/otel/v1/logs"
                                               :body batch}))
        otel-status (fn [] (:otel (:body (slopp.http/handle! ctx {:request-method :get :uri "/api/status"}))))]
    (try
      (let [r   (post "one" nil {:jsonrpc "2.0" :id 1 :method "initialize"
                                 :params {:protocolVersion "2025-03-26" :capabilities {}
                                          :clientInfo {:name "t" :version "0"}}})
            sid (get-in r [:headers "Mcp-Session-Id"])]
        ;; a write, so the project has a store and an open thread
        (let [w (post "one" sid {:jsonrpc "2.0" :id 2 :method "tools/call"
                                 :params {:name "ns_create"
                                          :arguments {:ns "ot.core" :thread "t-tel" :prompt "telemetry fixture"
                                                      :source "(ns ot.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n"}}})]
          (is (= 200 (:status w)) (pr-str w)))
        (testing "the read API answers under the project's mount, with no second prefix"
          (let [r (slopp.http/handle! ctx {:request-method :get :uri "/api/projects/one/namespaces"})]
            (is (= 200 (:status r)) (pr-str r)))
          (let [r (slopp.http/handle! ctx {:request-method :get :uri "/api/projects/one/api/namespaces"})]
            (is (= 404 (:status r)) "the contract's own /api is replaced by the mount, not nested under it")))
        (testing "an unknown project is a 404, not a 500"
          (let [r (slopp.http/handle! ctx {:request-method :get :uri "/api/projects/nope/namespaces"})]
            (is (= 404 (:status r)) (pr-str r))))
        (testing "an OTLP batch routes by session id to the project holding that thread"
          (let [r  (otel!)
                st (otel-status)]
            (is (= 200 (:status r)) (pr-str r))
            (is (= 1 (:routed st)) (pr-str st))
            (is (= 1 (:dropped st)) (pr-str st))))
        (testing "garbage is 400, the non-retryable answer"
          (let [r (slopp.http/handle! ctx {:request-method :post :uri "/api/otel/v1/logs" :body "{not json"})]
            (is (= 400 (:status r)) (pr-str r))))
        (testing "with the project closed, a remembered thread still routes"
          (daemon/detach! sid)
          (is (empty? (projects! ctx)) "the last detach closed the project")
          (let [r  (otel!)
                st (otel-status)]
            (is (= 200 (:status r)) (pr-str r))
            (is (= 2 (:routed st)) (pr-str st))
            (is (= 2 (:dropped st)) (pr-str st)))))
      (finally (daemon/reset-all!)))))

(deftest ^:external every-session-on-a-project-shares-its-one-app-owner
  ;; Two agents on one project: one app server, owned by the project's
  ;; reader, which both sessions reach through their `:app-owner` — a delay
  ;; each, onto the one reader — so a done in either refreshes the same
  ;; server. Different projects, different owners.
  (let [d1   (tmp-dir!)
        d2   (tmp-dir!)
        ctx  (daemon/context)
        init (fn [dir slug]
               (get-in (slopp.http/handle! ctx {:request-method :post
                                                :uri (str "/api/projects/" slug "/mcp")
                                                :headers {"x-slopp-dir" dir}
                                                :body {:jsonrpc "2.0" :id 1 :method "initialize"
                                                       :params {:protocolVersion "2025-03-26" :capabilities {}
                                                                :clientInfo {:name "t" :version "0"}}}})
                       [:headers "Mcp-Session-Id"]))]
    (try
      (let [a (daemon/lookup! (init d1 "one"))
            b (daemon/lookup! (init d1 "one"))
            c (daemon/lookup! (init d2 "two"))]
        (is (some? (:app-owner @a)) (pr-str (keys @a)))
        (is (identical? (force (:app-owner @a)) (force (:app-owner @b))) "one project, one owner")
        (is (not (identical? (force (:app-owner @a)) (force (:app-owner @c)))) "another project, another owner")
        (testing "the registry says what each project serves"
          (let [ps (projects! ctx)]
            (is (every? #(contains? % :app) ps) (pr-str ps)))))
      (finally (daemon/reset-all!)))))

(deftest ^:external the-call-door-opens-a-project-on-demand-and-reaps-it-when-idle
  ;; `slopp <op>` from a shell routes here: the project is named by dir
  ;; (the CLI knows its cwd and no slug), opened if nobody is attached, and
  ;; the call runs on a CLI session the daemon keeps for it. The token is
  ;; the daemon's; a write with no thread is refused at the door exactly as
  ;; a one-shot is — as an ANSWER (200, isError), because a 4xx reads to the
  ;; CLI as a failed route and it falls back to booting a JVM to be refused
  ;; again; and a CLI session nobody uses is reaped, closing the project.
  (let [d     (tmp-dir!)
        ctx   (daemon/context)
        token (daemon/token)
        call  (fn [body]
                (slopp.http/handle! ctx {:request-method :post
                                   :uri "/api/projects/_/call"
                                   :headers {"x-slopp-dir" d}
                                   :body body}))]
    (try
      (testing "a call with the token runs on a project nobody attached, which opens it"
        (let [r (call {:tool "thread_open" :arguments {:thread "t-cli"} :token token})
              b (json/parse-string (:body r) true)]
          (is (= 200 (:status r)) (pr-str r))
          (is (re-find #"t-cli" (str (:text b))) (pr-str b))
          (let [ps (projects! ctx)]
            (is (= [d] (mapv :dir ps)) (pr-str ps))
            (is (:cli (first ps)) (pr-str ps)))))
      (testing "without the token, nothing runs"
        (let [r (call {:tool "thread_list" :arguments {}})]
          (is (= 403 (:status r)) (pr-str r))))
      (testing "a write naming no thread is refused as an answer, naming the door"
        (let [r (call {:tool "ns_create" :arguments {:ns "x.y" :source "(ns x.y)" :prompt "p"}
                       :token token})
              b (json/parse-string (:body r) true)]
          (is (= 200 (:status r)) (pr-str r))
          (is (:isError b) (pr-str b))
          (is (re-find #"thread_open" (str (:text b))) (pr-str b))))
      (testing "idle, the CLI session is reaped and the project closes"
        (daemon/reap-idle! (+ (System/currentTimeMillis) (* 60 60 1000)))
        (is (empty? (projects! ctx)) (pr-str (projects! ctx))))
      (finally (daemon/reset-all!)))))

(deftest ^:external a-daemon-session-boots-no-image-until-something-needs-one
  ;; The memory the daemon exists to save is the idle child JVM every session
  ;; used to hold from its first second. Reads answer from the store value;
  ;; a session that only reads never needs an image — and it still sees what
  ;; others land, because the cache refresh no longer waits on an image it
  ;; does not have. The first eval or write boots one, for that session.
  (let [d    (tmp-dir!)
        ctx  (daemon/context)
        post (fn [sid msg]
               (slopp.http/handle! ctx {:request-method :post
                                  :uri "/api/projects/one/mcp"
                                  :headers (cond-> {"x-slopp-dir" d}
                                             sid (assoc "mcp-session-id" sid))
                                  :body msg}))
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-03-26" :capabilities {}
                       :clientInfo {:name "t" :version "0"}}}
        call (fn [sid n args]
               (get-in (json/parse-string
                        (:body (post sid {:jsonrpc "2.0" :id 9 :method "tools/call"
                                          :params {:name n :arguments args}}))
                        true)
                       [:result :content 0 :text]))]
    (try
      (let [a  (get-in (post nil init) [:headers "Mcp-Session-Id"])
            b  (get-in (post nil init) [:headers "Mcp-Session-Id"])
            sa (daemon/lookup! a)
            sb (daemon/lookup! b)]
        (testing "reading boots nothing"
          (call a "query_search" {:pattern "defn"})
          (is (nil? (:image @sa)) "a read needs no image")
          (is (nil? (:image @sb))))
        (testing "a write in one session boots its image, and its landed work reaches the other without one"
          (call b "ns_create" {:ns "lz.core" :thread "t-b" :prompt "lazy fixture"
                               :source "(ns lz.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n"})
          (is (some? (:image @sb)) "the write booted the writer's image")
          (call b "done" {:thread "t-b" :label "land it"})
          (let [seen (call a "query_search" {:pattern "unused-ok f" :branch "main"})]
            (is (re-find #"lz\.core" (str seen)) (str seen))
            (is (nil? (:image @sa)) "still no image on the reader")))
        (testing "an eval boots the reader's image, once, at the current head"
          (call a "query_eval" {:code "(+ 1 1)"})
          (is (some? (:image @sa)))))
      (finally (daemon/reset-all!)))))

(deftest ^:external two-sessions-asking-for-a-full-check-at-one-content-share-one-run
  ;; full_check is the most expensive thing slopp does — almost all of it
  ;; fresh JVM boots — and two agents on one project asking at once used to
  ;; boot two of everything. Under the daemon every session on a project
  ;; shares one queue: the first asker runs, the second waits on the same
  ;; promise, records the verdict on its own line (so it stands there), and
  ;; says it joined. Driven on two sessions directly, because through the
  ;; wire a done on a small store already leaves a standing verdict behind
  ;; and there is nothing left to race.
  (let [d    (tmp-dir!)
        ctx  (daemon/context)
        post (fn [sid msg]
               (slopp.http/handle! ctx {:request-method :post
                                  :uri "/api/projects/one/mcp"
                                  :headers (cond-> {"x-slopp-dir" d}
                                             sid (assoc "mcp-session-id" sid))
                                  :body msg}))
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-03-26" :capabilities {}
                       :clientInfo {:name "t" :version "0"}}}
        call (fn [sid n args]
               (post sid {:jsonrpc "2.0" :id 9 :method "tools/call"
                          :params {:name n :arguments args}}))
        flags (fn [r] (select-keys r [:joined :standing :status]))]
    (try
      ;; a landed store, made through the daemon like any other
      (let [w (get-in (post nil init) [:headers "Mcp-Session-Id"])]
        (call w "ns_create" {:ns "fc.core" :thread "t-w" :prompt "queue fixture"
                             :source "(ns fc.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n"})
        (call w "done" {:thread "t-w" :label "land it"}))
      (daemon/reset-all!)
      ;; opened WITH their images: this drives full-check! below the dispatch
      ;; that would otherwise await one
      (let [q (atom {})
            a (external/open! {:slopp.ops/dir d :slopp.ops/agent-id "q-a"})
            b (external/open! {:slopp.ops/dir d :slopp.ops/agent-id "q-b"})]
        (try
          (swap! a assoc :check-queue q)
          (swap! b assoc :check-queue q)
          (let [ra (future (external/full-check! a))
                rb (future (external/full-check! b))
                results [(deref ra 600000 {:status :timeout}) (deref rb 600000 {:status :timeout})]
                joined (filter :joined results)]
            (is (= 1 (count joined)) (pr-str (mapv flags results)))
            (is (every? #(= :green (:status %)) results) (pr-str (mapv flags results)))
            (testing "and the joiner's verdict STANDS on its own line afterwards"
              (let [again (external/full-check! (if (:joined (first results)) a b))]
                (is (:standing again) (pr-str (flags again)))))
            (testing "and a write that touches no FORM still retires it for the next asker"
              ;; a tier declaration changes what the layering check says and
              ;; touches no elements row, so the digest the queue keyed on did
              ;; not move — and the delivered promise was never removed, so
              ;; the next asker at that digest was handed the old verdict as
              ;; :joined, recorded on its line as if earned
              (let [joiner? (:joined (first results))
                    s       (if joiner? a b)]
                (ops/module-tier! s "fc.core" :external :agent (if joiner? "q-a" "q-b"))
                (let [after (external/full-check! s)]
                  (is (not (:joined after)) (pr-str (flags after)))
                  (is (not (:standing after)) (pr-str (flags after)))
                  (is (= :green (:status after)) (pr-str (flags after)))))))
          (finally (ops/close! a) (ops/close! b))))
      (finally (daemon/reset-all!)))))

(deftest ^:external another-sessions-landing-is-announced-on-your-next-answer
  ;; Push, in the only form that reaches the model: an MCP notification goes
  ;; to the client's log, not the conversation, so what another session
  ;; landed on your project rides the NEXT answer you get as one leading
  ;; line. Two agents, one project: B lands, A's next read says so.
  (let [d    (tmp-dir!)
        ctx  (daemon/context)
        post (fn [sid msg]
               (slopp.http/handle! ctx {:request-method :post
                                  :uri "/api/projects/one/mcp"
                                  :headers (cond-> {"x-slopp-dir" d}
                                             sid (assoc "mcp-session-id" sid))
                                  :body msg}))
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-03-26" :capabilities {}
                       :clientInfo {:name "t" :version "0"}}}
        call (fn [sid n args]
               (get-in (json/parse-string
                        (:body (post sid {:jsonrpc "2.0" :id 9 :method "tools/call"
                                          :params {:name n :arguments args}}))
                        true)
                       [:result :content 0 :text]))]
    (try
      (let [a (get-in (post nil init) [:headers "Mcp-Session-Id"])
            b (get-in (post nil init) [:headers "Mcp-Session-Id"])]
        (call a "query_search" {:pattern "defn"})
        (call b "ns_create" {:ns "ev.core" :thread "t-b" :prompt "event fixture"
                             :source "(ns ev.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n"})
        (call b "done" {:thread "t-b" :label "land it"})
        (let [answer (str (call a "query_search" {:pattern "defn"}))]
          (is (re-find #"^;; since your last call" answer) answer)
          (is (re-find #"landed" answer) answer)
          (testing "said once: the next answer is clean"
            (is (not (re-find #"^;; since your last call" (str (call a "query_search" {:pattern "defn"}))))))))
      (finally (daemon/reset-all!)))))

(deftest ^:external the-image-budget-refuses-a-boot-past-the-cap-and-names-the-fix
  ;; A machine-wide budget lives where it can: with the one process that
  ;; sees every image. v1 is a cap with a refusal that says what to do, not
  ;; a scheduler.
  (let [d    (tmp-dir!)
        ctx  (daemon/context)
        post (fn [sid msg]
               (slopp.http/handle! ctx {:request-method :post
                                  :uri "/api/projects/one/mcp"
                                  :headers (cond-> {"x-slopp-dir" d}
                                             sid (assoc "mcp-session-id" sid))
                                  :body msg}))
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-03-26" :capabilities {}
                       :clientInfo {:name "t" :version "0"}}}
        call (fn [sid n args]
               (get-in (json/parse-string
                        (:body (post sid {:jsonrpc "2.0" :id 9 :method "tools/call"
                                          :params {:name n :arguments args}}))
                        true)
                       [:result :content 0 :text]))
        was  @daemon/image-cap]
    (try
      (reset! daemon/image-cap 1)
      (let [a (get-in (post nil init) [:headers "Mcp-Session-Id"])
            b (get-in (post nil init) [:headers "Mcp-Session-Id"])]
        (is (re-find #"\[3\]" (str (call a "query_eval" {:code "(+ 1 2)"}))) "the first image is within the cap")
        (let [r (str (call b "query_eval" {:code "(+ 1 2)"}))]
          (is (re-find #"cap" r) r)
          (is (re-find #"SLOPP_DAEMON_MAX_IMAGES" r) r)
          (is (nil? (:image @(daemon/lookup! b))) "nothing booted past the cap")))
      (finally (reset! daemon/image-cap was) (daemon/reset-all!)))))

(deftest ^:external attaching-opens-no-reader-until-something-reads-through-it
  ;; The reader is a second full session on the project's store — a second
  ;; copy of a large store's value — and it exists for two consumers: the
  ;; read API and the app server. A project that has neither in play pays
  ;; for neither: attaching opens no reader; the first API request does.
  (let [d    (tmp-dir!)
        ctx  (daemon/context)
        post (fn [sid msg]
               (slopp.http/handle! ctx {:request-method :post
                                        :uri "/api/projects/one/mcp"
                                        :headers (cond-> {"x-slopp-dir" d}
                                                   sid (assoc "mcp-session-id" sid))
                                        :body msg}))
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-03-26" :capabilities {}
                       :clientInfo {:name "t" :version "0"}}}]
    (try
      (post nil init)
      (is (not (daemon/reader-open? d)) "attaching alone opens no reader")
      (slopp.http/handle! ctx {:request-method :get :uri "/api/projects/one/namespaces"})
      (is (daemon/reader-open? d) "the first API request opens it")
      (finally (daemon/reset-all!)))))

(deftest ^:external sessions-at-one-content-share-one-namespaces-map
  ;; Under the daemon every attached session loaded its own copy of the
  ;; store's namespaces — ~450 MB each on slopp2, for the same rows. Two
  ;; idle threads at one branch head read the same view, so they hold ONE
  ;; map; a landed write moves the head and the next load is fresh; with
  ;; caching bypassed nothing is shared, which is how the test proves the
  ;; computation and not the cache.
  (let [d (tmp-dir!)
        w (external/open! {:slopp.ops/dir d :slopp.ops/agent-id "w"})]
    (try
      (ops/ingest! w 'sh.core "(ns sh.core)\n")
      (let [r (ops/add-form! w 'sh.core "(defn ^:unused-ok f \"F.\" [] 1)" :prompt "fixture" :agent "w")]
        (is (nil? (:error r)) (pr-str r)))
      (external/done! w :label "land" :agent "w")
      (let [a (external/open! {:slopp.ops/dir d :slopp.ops/agent-id "a" :slopp.ops/lazy-image? true})
            b (external/open! {:slopp.ops/dir d :slopp.ops/agent-id "b" :slopp.ops/lazy-image? true})]
        (try
          (is (contains? (get-in @a [:store :namespaces]) 'sh.core) (pr-str (keys (get-in @a [:store :namespaces]))))
          (testing "two idle threads at one head hold the same namespaces map"
            (is (identical? (:namespaces (:store @a)) (:namespaces (:store @b)))))
          (testing "a landed write moves the head, and the next open is fresh"
            (let [r (ops/add-form! w 'sh.core "(defn ^:unused-ok g \"G.\" [] 2)" :prompt "more" :agent "w")]
              (is (nil? (:error r)) (pr-str r)))
            (external/done! w :label "land again" :agent "w")
            (let [c (external/open! {:slopp.ops/dir d :slopp.ops/agent-id "c" :slopp.ops/lazy-image? true})]
              (try
                (is (not (identical? (:namespaces (:store @a)) (:namespaces (:store @c)))))
                (is (= 3 (count (get-in @c [:store :namespaces 'sh.core :elements]))) "ns form, f and g")
                (finally (ops/close! c)))))
          (testing "with caching bypassed, nothing is shared"
            (cache/without-caching!
             (fn []
               (let [e (external/open! {:slopp.ops/dir d :slopp.ops/agent-id "e" :slopp.ops/lazy-image? true})
                     f (external/open! {:slopp.ops/dir d :slopp.ops/agent-id "f" :slopp.ops/lazy-image? true})]
                 (try
                   (is (not (identical? (:namespaces (:store @e)) (:namespaces (:store @f)))))
                   (finally (ops/close! e) (ops/close! f)))))))
          (finally (ops/close! a) (ops/close! b))))
      (finally (ops/close! w)))))

(deftest a-daemon-on-another-port-records-itself-under-its-own-file
  ;; `~/.slopp/daemon.json` names THE daemon of the machine — what every
  ;; pipe and the CLI route to. A dev daemon (slopp's own dev instance,
  ;; run from its store on another port) must not take that over on boot.
  ;; And the machine's daemon is the one on the machine's CONFIGURED port,
  ;; not on the literal default: a machine that moved its daemon to 7400
  ;; still has one daemon.json, and a dev instance that happens to sit on
  ;; 7357 there is the other one.
  (is (= "daemon.json" (.getName (daemon/daemon-file daemon/default-port daemon/default-port))))
  (is (= "daemon-7358.json" (.getName (daemon/daemon-file 7358 daemon/default-port))))
  (is (= "daemon.json" (.getName (daemon/daemon-file 7400 7400))))
  (is (= "daemon-7357.json" (.getName (daemon/daemon-file 7357 7400))))
  (is (= (daemon/daemon-file) (daemon/daemon-file (daemon/machine-port)))))

(deftest ^:external a-stale-session-id-is-re-attached-not-refused
  ;; A stdio client never re-initializes on its own: after the daemon
  ;; restarts or reaps an idle session, the pipe's next request carries an
  ;; id the daemon does not hold, and a 404 there is a dead session until a
  ;; human reconnects. A request that names its dir can be re-attached
  ;; where it stands — the answer carries the NEW id, which the pipe
  ;; adopts, and the agent sees one late answer.
  (let [d    (tmp-dir!)
        ctx  (daemon/context)
        post (fn [sid msg]
               (slopp.http/handle! ctx {:request-method :post
                                        :uri "/api/projects/one/mcp"
                                        :headers (cond-> {"x-slopp-dir" d}
                                                   sid (assoc "mcp-session-id" sid))
                                        :body msg}))
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-03-26" :capabilities {}
                       :clientInfo {:name "t" :version "0"}}}]
    (try
      (let [sid (get-in (post nil init) [:headers "Mcp-Session-Id"])]
        (daemon/detach! sid)
        (is (empty? (projects! ctx)) "the project closed with its last session")
        (let [r (post sid {:jsonrpc "2.0" :id 2 :method "tools/call"
                           :params {:name "thread_open" :arguments {:thread "t-back"}}})
              new (get-in r [:headers "Mcp-Session-Id"])]
          (is (= 200 (:status r)) (pr-str r))
          (is (and (string? new) (not= sid new)) (pr-str (:headers r)))
          (is (re-find #"t-back" (str (:body r))) (pr-str r))
          (is (= [d] (mapv :dir (projects! ctx))) "re-attached, the project is open again")))
      (testing "no id at all, but a dir: attached where it stands"
        (let [r (post nil {:jsonrpc "2.0" :id 4 :method "tools/call"
                           :params {:name "thread_list" :arguments {}}})]
          (is (= 200 (:status r)) (pr-str r))
          (is (string? (get-in r [:headers "Mcp-Session-Id"])) (pr-str (:headers r)))))
      (testing "a stale id with no dir to re-attach by is still a 404"
        (let [r (slopp.http/handle! ctx {:request-method :post
                                         :uri "/api/projects/nowhere/mcp"
                                         :headers {"mcp-session-id" "nope"}
                                         :body {:jsonrpc "2.0" :id 3 :method "ping"}})]
          (is (= 404 (:status r)) (pr-str r))))
      (finally (daemon/reset-all!)))))

(deftest ^:external a-daemon-session-carries-the-tools-argument-cards
  ;; Only the retired start-ui! ever set :op-cards on a session, and the
  ;; ask bundle's ?diet=1 reads it off the session (there is no api->mcp
  ;; edge). Under the daemon every session has to carry it, or the diet
  ;; bundle silently sheds the argument-teaching block.
  (let [d (tmp-dir!)]
    (try
      (let [{:keys [session]} (daemon/attach! d "cards")]
        (is (= mcp/op-cards (:op-cards @session)))
        (is (seq (:op-cards @session))))
      (finally (daemon/reset-all!)))))

(deftest ^:external the-brief-names-the-projects-read-api-on-the-daemon
  ;; :ui used to be a per-session listener's address; the daemon serves
  ;; every project's API under /api/projects/<slug>, and the brief is
  ;; where an agent finds that address to hand to a program.
  (let [d (tmp-dir!)]
    (try
      (let [{:keys [port]} (daemon/start! 0)
            {:keys [session]} (daemon/attach! d "brief")
            want (str "http://127.0.0.1:" port "/api/projects/brief")]
        (is (= want (:api-url @session)))
        (let [brief (ops/session-brief session)]
          (is (= want (:api brief)))
          (is (= (str "http://127.0.0.1:" port "/p/brief") (:pages brief))
              "the brief names the pages a HUMAN opens, beside the API a program reads")))
      (finally (daemon/reset-all!)))))

(deftest the-daemons-surface-is-declared-not-hand-routed
  ;; The daemon is slopp itself, so its routes go through the same component
  ;; every project's do: declared endpoints and declared content, assembled
  ;; from the served namespaces' public vars, contracts enforced by the same
  ;; validator. The one hand-written thing is the static mount, which is what
  ;; a mount is — a tree of files, not a declaration — and it is the same
  ;; `mount-routes` every project's app server uses.
  (let [declared (routes/from-namespaces ['slopp.daemon 'slopp.ui.shell 'slopp.ui.styles])
        served   (:http/routes (daemon/context))
        mounted  (filter #(str/starts-with? (:path %) "/assets/") served)]
    (is (= #{"/api/projects" "/api/status"
             "/api/projects/:slug/mcp" "/api/projects/:slug/call"
             "/api/projects/:slug/**" "/api/otel/v1/logs"
             "/**" "/css/style.css"}
           (set (map :path declared)))
        (pr-str (map (juxt :method :path) declared)))
    (is (= ["/assets/**"] (mapv :path mounted)) "one static mount, the bundle's")
    (is (= (set (map (juxt :method :path) declared))
           (set (map (juxt :method :path) (remove (set mounted) served))))
        "what the daemon serves is exactly what it declares, plus the mount")
    (testing "the project mount is a GET: the reader behind it is read-only"
      (is (= [:get] (mapv :method (filter #(= "/api/projects/:slug/**" (:path %)) declared)))))
    (testing "the shell derives its status from the declared pages, which the daemon hands the context"
      (is (seq (:webapp/routes (daemon/context))))
      (is (some #(= "/p/:slug" (first %)) (:webapp/routes (daemon/context)))))))

(deftest ^:external a-checkout-carrying-a-slopp-branch-is-imported-on-first-attach
  ;; Zero-ceremony onboarding used to ride the stdio server's start: a git
  ;; clone carrying a slopp branch with no store was imported before serving.
  ;; The daemon opens a project on its first attach, so that is where the
  ;; import happens now — once per open, never again for a second attach.
  (let [d    (tmp-dir!)
        seen (atom [])]
    (try
      (with-redefs [sync/maybe-auto-import! (fn [dir] (swap! seen conj dir) nil)]
        (daemon/attach! d "imp")
        (daemon/attach! d "imp")
        (is (= [d] @seen) "imported on the first attach and not the second"))
      (finally (daemon/reset-all!)))))

(deftest ^:external the-daemon-serves-the-pages-beside-the-api
  ;; The pages a human looks at are the daemon's now, built on slopp's own
  ;; components and served through the same context assembly as its typed
  ;; endpoints: the shell at every page address with the bundle script
  ;; injected, a derived 404 where no page is declared, the stylesheet as
  ;; content, the asset mount refusing what it does not hold.
  (let [ctx (daemon/context)
        get (fn [uri] (slopp.http/handle! ctx {:request-method :get :uri uri}))]
    (try
      (testing "the shell answers at the root and under a project, carrying the bundle's address"
        (let [r (get "/")]
          (is (= 200 (:status r)) (pr-str r))
          (is (re-find #"/assets/cljs/main\.js" (str (:body r))) (pr-str (:body r))))
        (is (= 200 (:status (get "/p/any-slug/store"))) "a declared page address, whether or not the project is open"))
      (testing "an address no page claims is the shell's DERIVED 404 — same bytes, honest status"
        (is (= 404 (:status (get "/p/x/nope/deeper")))))
      (testing "the stylesheet is served content"
        (let [r (get "/css/style.css")]
          (is (= 200 (:status r)) (pr-str r))
          (is (re-find #"text/css" (str (get-in r [:headers "Content-Type"]))) (pr-str (:headers r)))))
      (testing "the asset mount answers 404 for what it does not hold, and never leaks"
        (is (= 404 (:status (get "/assets/nope.js"))))
        (is (= 404 (:status (get "/assets/../deps.edn")))))
      (testing "the typed api is untouched beside the pages"
        (is (= 200 (:status (get "/api/status")))))
      (finally (daemon/reset-all!)))))

(deftest ^:external a-managed-child-running-the-daemon-holds-what-it-serves
  ;; The dev instance is `slopp.daemon/-main` DECLARED in slopp's own dev
  ;; config, so it runs in a managed child that loads the daemon's require
  ;; closure and nothing more, and has no store. Two things the kernel-booted
  ;; daemon gets for free, that child does not — and both were measured on
  ;; the dev instance while the stable daemon beside it was right:
  ;;
  ;; - the PAGES. A page declares no route the http layer scans, so nothing
  ;;   in the served surface pulls its namespace in; the child's route table
  ;;   was empty and every address answered 200.
  ;; - the BUNDLE's bytes. The child has no store and no boot record, so the
  ;;   reader fell back to a classpath with no `public/` on it: 404.
  (let [st (external/built-store)]
    (testing "the daemon's require closure reaches EVERYTHING it lists as served, so a child — or a jar boot — loads it"
      ;; the shell and the stylesheet are the same class one step over: named
      ;; in serving-opts, required by nothing, and a route builder reads
      ;; loaded vars. The v0.3.0 jar booted from a neutral dir and answered
      ;; 404 to every page and to /css/style.css; the fix that reached
      ;; slopp.ui.pages had stopped one namespace short, twice over
      (let [closure (store/ns-closure st 'slopp.daemon)]
        (doseq [n '[slopp.ui.pages slopp.ui.shell slopp.ui.styles]]
          (is (contains? closure n)
              (str "slopp.daemon does not require " n " — a process that loads only its closure serves nothing from it"))))))
  (testing "assets come from the dir a manager materialized, when it says where"
    (let [dir (tmp-dir!)
          f   (java.io.File. ^String dir "public/cljs/main.js")]
      (.mkdirs (.getParentFile f))
      (spit f "// the bundle a manager wrote")
      (try
        (System/setProperty "slopp.static-dir" dir)
        (let [ctx (daemon/context)
              r   (slopp.http/handle! ctx {:request-method :get :uri "/assets/cljs/main.js"})
              b   (:body r)]
          (is (= 200 (:status r)) (pr-str r))
          (is (re-find #"a manager wrote" (if (string? b) b (slurp b))) (pr-str r)))
        (finally
          (System/clearProperty "slopp.static-dir")
          (daemon/reset-all!))))))

(deftest ^:external a-request-for-an-asset-opens-no-project
  ;; The static mount read the daemon's OWN store through `api!`, which
  ;; registered a project record for the daemon's dir with nothing in it but
  ;; the reader: no slug, no sessions, no check queue. One browser hit on the
  ;; bundle, then an attach to that dir within the minute before the reaper
  ;; swept the husk, adopted the half-record — and detach, reap and reset then
  ;; threw on its nil session set, which the reaper swallowed forever after.
  ;; The daemon's own assets are read through a reader of their own now.
  (let [told (System/getProperty "slopp.static-dir")]
    (try
      (System/clearProperty "slopp.static-dir")
      (let [ctx (daemon/context)
            r   (slopp.http/handle! ctx {:request-method :get :uri "/assets/cljs/main.js" :headers {}})]
        (is (contains? #{200 404} (:status r)) (pr-str (dissoc r :body)))
        (is (empty? (projects! ctx)) "an asset request is not an attachment")
        (is (map? (daemon/reap-idle! (System/currentTimeMillis))) "and the reaper still runs"))
      (finally
        (when told (System/setProperty "slopp.static-dir" told))
        (daemon/reset-all!)))))

(deftest ^:external a-detach-during-the-app-boot-stops-what-the-boot-started
  ;; The first attach boots the project's app server in the background, and
  ;; the last detach stops "the app server the reader holds" — which is nil
  ;; until the boot returns. A client that attached and disconnected within
  ;; seconds (a health check, a one-shot hook) left a child JVM running that
  ;; nothing listed and nothing would ever stop. The boot is pretended here:
  ;; what matters is the order, not the JVM.
  (let [d     (tmp-dir!)
        ctx   (daemon/context)
        stops (atom [])
        post  (fn [msg]
                (slopp.http/handle! ctx {:request-method :post
                                         :uri "/api/projects/one/mcp"
                                         :headers {"x-slopp-dir" d}
                                         :body msg}))
        init  {:jsonrpc "2.0" :id 1 :method "initialize"
               :params {:protocolVersion "2025-03-26" :capabilities {}
                        :clientInfo {:name "t" :version "0"}}}]
    (try
      (with-redefs [mcp/app-managed? (constantly true)
                    mcp/start-app!   (fn [owner]
                                       (Thread/sleep 500)
                                       (swap! owner assoc :app-server {:image :pretend})
                                       {:serving? true})
                    mcp/stop-app!    (fn [owner]
                                       (swap! stops conj (:app-server @owner))
                                       (swap! owner dissoc :app-server)
                                       {:stopped true})]
        (let [sid (get-in (post init) [:headers "Mcp-Session-Id"])]
          (is (string? sid))
          (Thread/sleep 100)
          (daemon/detach! sid)
          (is (empty? (projects! ctx)) "the project closed on its last detach")
          (Thread/sleep 1500)
          (is (some some? @stops)
              (str "the server the boot brought up was stopped once it existed — stop-app! saw "
                   (pr-str @stops)))))
      (finally (daemon/reset-all!)))))

(deftest ^:external a-token-less-call-opens-nothing
  ;; The door checked the token INSIDE http-call!, after cli-session! had
  ;; already opened the project and minted a CLI session for it: a 403 that
  ;; left a project open for ten minutes on a dir nobody authenticated for.
  (let [d   (tmp-dir!)
        ctx (daemon/context)]
    (try
      (let [r (slopp.http/handle! ctx {:request-method :post
                                       :uri "/api/projects/_/call"
                                       :headers {"x-slopp-dir" d}
                                       :body {:tool "thread_list" :arguments {}}})]
        (is (= 403 (:status r)) (pr-str r)))
      (is (empty? (projects! ctx)) "nothing ran, so nothing opened")
      (finally (daemon/reset-all!)))))

(deftest the-daemon-file-is-readable-by-its-owner-only
  ;; The file carries the write door's token. `spit` wrote it under the
  ;; umask, -rw-r--r--, so every local USER could read the secret that exists
  ;; so that loopback alone does not hand every local process the store.
  (let [f (java.io.File/createTempFile "slopp-daemon-file" ".json")]
    (try
      ;; a world-readable file already there, as a previous daemon left it
      (.setReadable f true false)
      (daemon/spit-private! f "{}")
      (is (= "{}" (slurp f)))
      (when (contains? (.supportedFileAttributeViews (java.nio.file.FileSystems/getDefault)) "posix")
        (is (= "rw-------"
               (java.nio.file.attribute.PosixFilePermissions/toString
                (java.nio.file.Files/getPosixFilePermissions
                 (.toPath f) (make-array java.nio.file.LinkOption 0))))))
      (finally (.delete f)))))

(deftest the-port-argument-is-parsed-or-refused-with-a-sentence
  ;; `slopp daemon seven` was an uncaught NumberFormatException — a stack
  ;; trace where the one fact that matters is which value was wrong. And the
  ;; port has four sources now, in an order worth pinning: the argument, the
  ;; environment, what the MANAGER told a declared entry, the machine's
  ;; setting, the default.
  (is (= {:port 7358} (daemon/daemon-port "7358" nil nil nil)))
  (is (= {:port 7400} (daemon/daemon-port nil "7400" nil nil)) "the environment, when no argument")
  (is (= {:port 7358} (daemon/daemon-port "7358" "7400" nil nil)) "the argument wins")
  (is (= {:port 7358} (daemon/daemon-port nil nil "7358" nil))
      "the manager's word: slopp's dev instance is told its port, not passed it")
  (is (= {:port 7400} (daemon/daemon-port nil "7400" "7358" nil)) "the environment beats the manager")
  (is (= {:port 7401} (daemon/daemon-port nil nil nil 7401)) "the machine's setting, when nothing else says")
  (is (= {:port daemon/default-port} (daemon/daemon-port nil nil nil nil)) "the default otherwise")
  (is (re-find #"not a port" (:error (daemon/daemon-port "seven" nil nil nil))))
  (is (re-find #"not a port" (:error (daemon/daemon-port "70000" nil nil nil)))))

(deftest ^:external a-daemon-that-loads-only-its-closure-still-serves-its-pages
  ;; The jar's shape, driven for real: a fresh JVM that requires slopp.daemon
  ;; and nothing else, assembles the context, and asks for the picker, a
  ;; project page and the stylesheet. The unit test above says the closure is
  ;; right; this says the served surface is, which is what a release smoke
  ;; that only asked /api/status could not see.
  (let [code (str "(require 'slopp.daemon 'slopp.http)"
                  " (let [ctx (slopp.daemon/context)"
                  "       at (fn [p] (:status (slopp.http/handle! ctx {:request-method :get :uri p :headers {}})))]"
                  "   (println :picker (at \"/\") :page (at \"/p/x\") :css (at \"/css/style.css\") :nope (at \"/nope\")))")
        r    (sh/sh "sh" "-c" (str "( sleep 60 ) | clojure -M -e " (pr-str code)))]
    (is (re-find #":picker 200" (str (:out r))) (pr-str r))
    (is (re-find #":page 200" (str (:out r))) (pr-str r))
    (is (re-find #":css 200" (str (:out r))) (pr-str r))
    (is (re-find #":nope 404" (str (:out r))) "and an address no page claims is still refused")))
