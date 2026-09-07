(ns slopp.daemon-test
  "The daemon: one process, every project, attached exactly while some agent
  is. Driven through the socket-free dispatch, so what is tested is the
  envelope and the lifecycle rather than a port."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [slopp.daemon :as daemon]
            [slopp.http :as http] [slopp.ops.external :as external] [slopp.ops :as ops]))

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
  "What /slopp/projects lists, as data."
  [ctx]
  (:body (http/handle! ctx {:request-method :get :uri "/slopp/projects"})))

(deftest ^:external an-agent-attaches-by-dir-and-the-daemon-serves-its-project
  ;; The client names its project by DIR (a header written into .mcp.json
  ;; before any daemon exists); the slug in the path is the display name. A
  ;; session is minted on initialize and echoed back by the client; it is
  ;; the attachment. Two dirs are two projects. The last DELETE closes one.
  (let [d1   (tmp-dir!)
        d2   (tmp-dir!)
        ctx  (daemon/context)
        post (fn [dir slug sid msg]
               (http/handle! ctx {:request-method :post
                                  :uri (str "/slopp/projects/" slug "/mcp")
                                  :headers (cond-> {"x-slopp-dir" dir}
                                             sid (assoc "mcp-session-id" sid))
                                  :body (json/generate-string msg)}))
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
        (testing "an unknown session is told to initialize again"
          (let [r (post d1 "one" "nope" {:jsonrpc "2.0" :id 3 :method "ping"})]
            (is (= 404 (:status r)) (pr-str r))))
        (testing "a standalone stream is declined, not broken"
          (let [r (http/handle! ctx {:request-method :get :uri "/slopp/projects/one/mcp"
                                     :headers {"mcp-session-id" sid}})]
            (is (= 405 (:status r)) (pr-str r))))
        (testing "DELETE detaches, and the last detach closes the project"
          (let [r (http/handle! ctx {:request-method :delete :uri "/slopp/projects/one/mcp"
                                     :headers {"mcp-session-id" sid}})]
            (is (= 200 (:status r)) (pr-str r)))
          (is (empty? (projects! ctx)) (pr-str (projects! ctx)))))
      (finally (daemon/reset-all!)))))

(deftest ^:external a-projects-api-and-telemetry-are-served-under-the-one-prefix
  ;; One surface: the project's typed read API answers under its own prefix
  ;; (the contract paths, un-prefixed, are what a client is generated from);
  ;; the machine's one OTLP sink routes a batch by session id — which is the
  ;; THREAD id since Phase 0 — to the project holding that thread, and says
  ;; what it could not place.
  (let [d    (tmp-dir!)
        ctx  (daemon/context)
        post (fn [slug sid msg]
               (http/handle! ctx {:request-method :post
                                  :uri (str "/slopp/projects/" slug "/mcp")
                                  :headers (cond-> {"x-slopp-dir" d}
                                             sid (assoc "mcp-session-id" sid))
                                  :body (json/generate-string msg)}))
        rec  (fn [sid] {:body {:stringValue "claude_code.api_request"} :timeUnixNano "1"
                        :attributes [{:key "session.id" :value {:stringValue sid}}
                                     {:key "model" :value {:stringValue "m"}}
                                     {:key "input_tokens" :value {:intValue "10"}}
                                     {:key "output_tokens" :value {:intValue "5"}}]})]
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
        (testing "the read API answers under the project's prefix"
          (let [r (http/handle! ctx {:request-method :get :uri "/slopp/projects/one/api/namespaces"})]
            (is (= 200 (:status r)) (pr-str r))))
        (testing "an unknown project is a 404, not a 500"
          (let [r (http/handle! ctx {:request-method :get :uri "/slopp/projects/nope/api/namespaces"})]
            (is (= 404 (:status r)) (pr-str r))))
        (testing "an OTLP batch routes by session id to the project holding that thread"
          (let [batch {:resourceLogs [{:scopeLogs [{:logRecords [(rec "t-tel") (rec "nobody")]}]}]}
                r     (http/handle! ctx {:request-method :post :uri "/slopp/otel/v1/logs"
                                         :body (json/generate-string batch)})
                st    (:body (http/handle! ctx {:request-method :get :uri "/slopp/status"}))]
            (is (= 200 (:status r)) (pr-str r))
            (is (= 1 (get-in st [:otel :routed])) (pr-str st))
            (is (= 1 (get-in st [:otel :dropped])) (pr-str st))))
        (testing "garbage is 400, the non-retryable answer"
          (let [r (http/handle! ctx {:request-method :post :uri "/slopp/otel/v1/logs" :body "{not json"})]
            (is (= 400 (:status r)) (pr-str r)))))
      (finally (daemon/reset-all!)))))

(deftest ^:external every-session-on-a-project-shares-its-one-app-owner
  ;; Two agents on one project: one app server, owned by the project's
  ;; reader, which both sessions name as their `:app-owner` — so a done in
  ;; either refreshes the same server. Different projects, different owners.
  (let [d1   (tmp-dir!)
        d2   (tmp-dir!)
        ctx  (daemon/context)
        init (fn [dir slug]
               (get-in (http/handle! ctx {:request-method :post
                                          :uri (str "/slopp/projects/" slug "/mcp")
                                          :headers {"x-slopp-dir" dir}
                                          :body (json/generate-string
                                                 {:jsonrpc "2.0" :id 1 :method "initialize"
                                                  :params {:protocolVersion "2025-03-26" :capabilities {}
                                                           :clientInfo {:name "t" :version "0"}}})})
                       [:headers "Mcp-Session-Id"]))]
    (try
      (let [a (daemon/lookup! (init d1 "one"))
            b (daemon/lookup! (init d1 "one"))
            c (daemon/lookup! (init d2 "two"))]
        (is (some? (:app-owner @a)) (pr-str (keys @a)))
        (is (identical? (:app-owner @a) (:app-owner @b)) "one project, one owner")
        (is (not (identical? (:app-owner @a) (:app-owner @c))) "another project, another owner")
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
                (http/handle! ctx {:request-method :post
                                   :uri "/slopp/projects/_/call"
                                   :headers {"x-slopp-dir" d}
                                   :body (json/generate-string body)}))]
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
               (http/handle! ctx {:request-method :post
                                  :uri "/slopp/projects/one/mcp"
                                  :headers (cond-> {"x-slopp-dir" d}
                                             sid (assoc "mcp-session-id" sid))
                                  :body (json/generate-string msg)}))
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
               (http/handle! ctx {:request-method :post
                                  :uri "/slopp/projects/one/mcp"
                                  :headers (cond-> {"x-slopp-dir" d}
                                             sid (assoc "mcp-session-id" sid))
                                  :body (json/generate-string msg)}))
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
                (is (:standing again) (pr-str (flags again))))))
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
               (http/handle! ctx {:request-method :post
                                  :uri "/slopp/projects/one/mcp"
                                  :headers (cond-> {"x-slopp-dir" d}
                                             sid (assoc "mcp-session-id" sid))
                                  :body (json/generate-string msg)}))
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
               (http/handle! ctx {:request-method :post
                                  :uri "/slopp/projects/one/mcp"
                                  :headers (cond-> {"x-slopp-dir" d}
                                             sid (assoc "mcp-session-id" sid))
                                  :body (json/generate-string msg)}))
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
