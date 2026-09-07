(ns slopp.daemon
  "ONE slopp per machine. A project is opened on the first agent's attach (an
  MCP session at its endpoint) and closed on the last detach; the daemon
  itself stays up with nothing loaded. It is the registry (`/slopp/projects`)
  and the owner of every surface under `/slopp/`."
  (:require [clojure.string :as str]
            [slopp.http :as http]
            [slopp.mcp.http :as mcp.http]
            [slopp.ops :as ops]
            [slopp.ops.external :as external] [cheshire.core :as json]))

(defonce ^:private state
  ;; `:projects` {dir {:slug :dir :opened-at :sessions #{sid}}} — a project
  ;; exists exactly while some session is attached to it. `:sessions`
  ;; {sid {:dir :session :started :last-seen}} — one slopp session per MCP
  ;; session, so a thread switch reloads one agent's view and not another's.
  ;; `:server` is the listener. One atom, mutated under `locking`, because an
  ;; attach opens a store between reading the map and writing it.
  (atom {:projects {} :sessions {} :server nil}))

(defn ^:export detach!
  "End MCP session `sid`: its slopp session closes (image reaped, connection
  released), and when it was the project's last, the project closes too —
  it leaves the registry and everything it held goes. `{:ended sid}`, plus
  `:closed dir` when the project went; nil for a session this daemon does
  not hold.

  Nothing is landed here. Work lives on the agent's THREAD, which the
  registry keeps whether or not any process holds it; the Stop hook's done
  is what lands, as it always was."
  [sid]
  (locking state
    (when-let [{:keys [dir session]} (get-in @state [:sessions sid])]
      (swap! state #(-> %
                        (update :sessions dissoc sid)
                        (update-in [:projects dir :sessions] disj sid)))
      (try (ops/close! session) (catch Throwable _ nil))
      (if (empty? (get-in @state [:projects dir :sessions]))
        (do (swap! state update :projects dissoc dir)
            {:ended sid :closed dir})
        {:ended sid}))))

(def ^:export default-port
  "Where a daemon listens unless told otherwise (`SLOPP_DAEMON_PORT`, or the
  argument to `-main`). One per machine, so one number rather than a
  per-directory formula: the derived-port formula existed so N per-session
  listeners would not collide, and there is one listener now."
  7357)

(defn- slug-for
  "The display name for a project: `wanted` — what the client put in its
  path, else the dir's basename — suffixed `-2`, `-3`, … when a project
  already open here has taken it. The dir is the identity; the slug is
  what a path and a listing show."
  [wanted dir taken]
  (let [base (or (not-empty (str wanted))
                 (last (remove str/blank? (str/split (str dir) #"/")))
                 "root")]
    (if-not (contains? taken base)
      base
      (first (remove taken (map #(str base "-" %) (iterate inc 2)))))))

(defn- project-dir
  "The project a request names: `X-Slopp-Dir` — an existing absolute
  directory, canonicalized — else the slug in the path, which resolves only
  to a project already open. `{:dir}` or `{:error}`.

  By DIR, because `.mcp.json` is written before any daemon exists and a
  slug minted at attach time cannot be known then; the header is what the
  plugin writes, literally (env expansion in headers is unreliable)."
  [req]
  (let [h    (get-in req [:headers "x-slopp-dir"])
        slug (get-in req [:path-params :slug])]
    (if-not (str/blank? (str h))
      (let [f (java.io.File. (str h))]
        (if (and (.isAbsolute f) (.isDirectory f))
          {:dir (.getCanonicalPath f)}
          {:error (str "X-Slopp-Dir must name an existing absolute directory: " h)}))
      (if-let [p (first (filter #(= slug (:slug %)) (vals (:projects @state))))]
        {:dir (:dir p)}
        {:error (str "no open project " slug
                     " — send X-Slopp-Dir: <absolute project dir> to open one")}))))

(defn ^:export attach!
  "Open an MCP session on the project at `dir`, opening the project itself
  when this is its first — under the display name `slug` the client asked
  for, or the dir's basename: `{:sid :session}`.

  ONE slopp session per MCP session, on the shared store. A session holds a
  thread, a line, a store value and a read ledger, and a write naming a
  different thread reloads all of it — which is one agent's business, not
  every agent's on the project. What the project shares — its app server,
  its registry row, later its oracle pool — lives on the project record.

  Under the lock for the whole open, deliberately: two first-attaches on
  one dir must not both mint the project."
  [dir slug]
  (locking state
    (let [now     (System/currentTimeMillis)
          sid     (str (java.util.UUID/randomUUID))
          proj    (or (get-in @state [:projects dir])
                      {:slug      (slug-for slug dir (set (map :slug (vals (:projects @state)))))
                       :dir       dir
                       :opened-at now
                       :sessions  #{}})
          session (external/open! {:slopp.ops/dir          dir
                                   :slopp.ops/async-image? true
                                   ;; the session's own label; the THREAD an
                                   ;; agent writes on is what it passes
                                   :slopp.ops/agent-id     (str "mcp-" (subs sid 0 8))})]
      (swap! session assoc :require-turns? true :daemon? true)
      (swap! state #(-> %
                        (assoc-in [:projects dir] (update proj :sessions conj sid))
                        (assoc-in [:sessions sid] {:dir dir :session session
                                                   :started now :last-seen now})))
      {:sid sid :session session})))

(defn ^:export lookup!
  "The slopp session behind MCP session `sid`, touching its `:last-seen`;
  nil when this daemon holds no such session."
  [sid]
  (when-let [s (get-in @state [:sessions sid])]
    (swap! state assoc-in [:sessions sid :last-seen] (System/currentTimeMillis))
    (:session s)))

^:reads (defn ^:export projects
  "THE REGISTRY: every project open on this daemon, oldest first — its
  slug, dir, when it opened and how many sessions are attached. A project
  is listed exactly while some agent is attached to it."
  []
  (->> (vals (:projects @state))
       (sort-by :opened-at)
       (mapv (fn [p] {:slug (:slug p) :dir (:dir p)
                      :opened-at (:opened-at p)
                      :sessions (count (:sessions p))}))))

^:unsafe (defn- mcp-endpoint
  "`/slopp/projects/:slug/mcp` — the envelope, lent this daemon's doors."
  [req]
  (mcp.http/endpoint {:slopp.mcp.http/lookup  lookup!
                      :slopp.mcp.http/attach! (fn [req]
                                                (let [{:keys [dir error]} (project-dir req)]
                                                  (if error
                                                    {:error error}
                                                    (attach! dir (get-in req [:path-params :slug])))))
                      :slopp.mcp.http/detach! detach!}
                     req))

(defn- projects-endpoint
  "`GET /slopp/projects` — the registry, as data."
  [_req]
  {:status 200 :body (projects)})

(defn ^:export routes
  "Every route under `/slopp/`: the registry, and each project's MCP
  endpoint by method. Route rows as data with a var per handler, so a live
  reload reaches the running listener."
  []
  [{:method :get    :path "/slopp/projects"           :auth :public :handler #'projects-endpoint}
   {:method :post   :path "/slopp/projects/:slug/mcp" :auth :public :handler #'mcp-endpoint}
   {:method :get    :path "/slopp/projects/:slug/mcp" :auth :public :handler #'mcp-endpoint}
   {:method :delete :path "/slopp/projects/:slug/mcp" :auth :public :handler #'mcp-endpoint}])

(defn ^:export context
  "The assembled dispatch context for every route under `/slopp/` — what the
  listener serves, and what a test drives without a port."
  []
  (http/context {:http/namespaces [] :http/routes (routes)}))

(defn ^:export start!
  "Bind the daemon's listener on `port` (loopback; nil = [[default-port]])
  and answer immediately: `{:url :port}`. Nothing is loaded until an agent
  attaches — binding first is what fits a client's startup window, which a
  JVM that opened a store before listening would miss. A taken port throws
  with the bind diagnosis leading, as every slopp listener does."
  [port]
  (locking state
    (when (:server @state)
      (throw (ex-info "this process already runs a daemon" {:port (:port (:server @state))})))
    (let [srv (http/serve! {:http/namespaces [] :http/routes (routes)
                            :http/host "127.0.0.1" :http/port (or port default-port)})]
      (swap! state assoc :server srv)
      {:url (str "http://127.0.0.1:" (:port srv) "/slopp/") :port (:port srv)})))

(defn ^:export reset-all!
  "Close every session (and so every project) and stop the listener."
  []
  (locking state
    (doseq [sid (keys (:sessions @state))]
      (detach! sid))
    (when-let [srv (:server @state)]
      (try (http/stop! srv) (catch Throwable _ nil)))
    (swap! state (constantly {:projects {} :sessions {} :server nil}))
    nil))

(defn ^:export daemon-file
  "Where a daemon records itself for the machine: `~/.slopp/daemon.json`,
  `{url port pid started}` — one writer by construction, since there is one
  daemon. What the plugin reads to find it and what a second daemon reads
  to name the first."
  []
  (java.io.File. (System/getProperty "user.home") ".slopp/daemon.json"))

^:unsafe (defn -main
  "Run the daemon: `slopp daemon [port]` — which is `slopp <dir> [--live]
  --main slopp.daemon/-main [port]`. The dir is what the kernel loads
  slopp's OWN code from, every namespace of that dir's store, so it is a
  NEUTRAL dir (`~/.slopp`, no store) in use and slopp's own checkout with
  `--live` in development, where the daemon's tooling hot-reloads; never a
  user's project, whose store would be loaded as if it were slopp. The
  projects it serves are whatever attaches. Records itself in
  [[daemon-file]] and blocks.

  A second daemon on the port refuses and names the live one from that
  file, so two never race for one machine's projects."
  [& [port]]
  (let [p (long (or (some-> port str Long/parseLong)
                    (some-> (System/getenv "SLOPP_DAEMON_PORT") Long/parseLong)
                    default-port))
        f (daemon-file)]
    (try
      (let [r (start! p)]
        (.mkdirs (.getParentFile f))
        (spit f (json/generate-string
                 {:url (:url r) :port (:port r)
                  :pid (.pid (java.lang.ProcessHandle/current))
                  :started (System/currentTimeMillis)}))
        (.println System/err (str "slopp daemon: " (:url r)
                                  " (pid " (.pid (java.lang.ProcessHandle/current)) ")"))
        @(promise))
      (catch clojure.lang.ExceptionInfo e
        (let [live (try (json/parse-string (slurp f) true) (catch Exception _ nil))]
          (.println System/err
                    (str "slopp daemon: cannot bind port " p " — "
                         (if live
                           (str "a daemon is already live at " (:url live) " (pid " (:pid live) ")")
                           (ex-message e))))
          (System/exit 1))))))
