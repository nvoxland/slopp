(ns slopp.daemon
  "ONE slopp per machine. A project is opened on the first agent's attach (an
  MCP session at its endpoint) and closed on the last detach; the daemon
  itself stays up with nothing loaded. It is the registry (`/api/projects`)
  and the owner of every surface under the one root `/api/` — each route a
  DECLARED rest endpoint on this namespace's public vars, assembled and
  validated by the same component every project's API goes through, with
  each open project's own API mounted at `/api/projects/<slug>/<resource>`."
  (:require [clojure.string :as str]
            [slopp.http :as slopp.http]
            [slopp.mcp.http :as mcp.http]
            [slopp.ops :as ops]
            [slopp.ops.external :as external] [cheshire.core :as json] [slopp.api.otel :as api.otel] [slopp.otel :as slopp.otel] [slopp.store.db :as db] [slopp.api.server :as server] [slopp.mcp :as mcp] [slopp.rest :as slopp.rest] [slopp.sync :as sync] [slopp.store :as store] [slopp.store.artifacts :as artifacts] [slopp.http.static :as static] [slopp.cljnx :as cljnx]
            ;; the pages this daemon serves, REQUIRED and not only named in
            ;; `serving-opts`: a page declares no route the http layer scans
            ;; (`:webapp/path` is the browser's table), so nothing in the
            ;; served surface pulls its namespace in. A managed child running
            ;; this daemon (slopp's own dev instance) loads the daemon's require
            ;; closure and nothing more — without this line its route table was
            ;; empty and every address answered 200, beside a kernel-booted
            ;; daemon that had every namespace and was right
            [slopp.ui.pages]))

(defonce ^:private state
  ;; `:projects` {dir {:slug :dir :opened-at :sessions #{sid}
  ;;                   :api {:reader :ctx} :cli {:session :last-seen}
  ;;                   :check-queue atom}}
  ;; — a project exists exactly while something is attached to it: an MCP
  ;; session, or the CLI session the write door keeps for it. `:api` is its
  ;; read API, assembled lazily over a read-only reader that also owns the
  ;; app server. `:sessions` {sid {:dir :session :started :last-seen}} — one
  ;; slopp session per MCP session, so a thread switch reloads one agent's
  ;; view and not another's. `:server` is the listener, `:token` the
  ;; per-boot secret the write door checks, `:reaper` the idle sweep's
  ;; thread, `:otel` what the sink placed and what it could not, and
  ;; `:otel-dirs` {thread dir} — where a thread's telemetry last routed, so
  ;; it still routes after that project closed. One atom, mutated under
  ;; `locking`, because an attach opens a store between reading the map and
  ;; writing it.
  (atom {:projects {} :sessions {} :server nil :token nil :reaper nil
         :otel {:routed 0 :dropped 0} :otel-dirs {}}))

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

(defn ^:export lookup!
  "The slopp session behind MCP session `sid`, touching its `:last-seen`;
  nil when this daemon holds no such session."
  [sid]
  (when-let [s (get-in @state [:sessions sid])]
    (swap! state assoc-in [:sessions sid :last-seen] (System/currentTimeMillis))
    (:session s)))

^:reads (defn ^:export projects
  "THE REGISTRY: every project open on this daemon, oldest first — its
  slug, dir, when it opened, how many MCP sessions are attached, whether
  the write door holds a CLI session for it, and `:app`: what its dev
  server serves (`{:url :branch}`), or nil when it serves nothing. A
  project is listed exactly while something is attached to it."
  []
  (->> (vals (:projects @state))
       (sort-by :opened-at)
       (mapv (fn [p]
               (let [reader (get-in p [:api :reader])
                     app    (some-> reader deref :app-server)]
                 {:slug (:slug p) :dir (:dir p)
                  :opened-at (:opened-at p)
                  :sessions (count (:sessions p))
                  :cli (some? (:cli p))
                  :app (when (:url app)
                         {:url (:url app)
                          :branch (some-> reader deref :branch str)})})))))

(defn ^:export daemon-file
  "Where a daemon records itself: `~/.slopp/daemon.json` — `{url port token
  pid started}`, THE daemon of the machine, what every pipe and the CLI
  route to — for the default port; `~/.slopp/daemon-<port>.json` for any
  other. A daemon on another port is a DEV instance (slopp's own, run from
  its store on 7358 by the same machinery every project's dev instance
  gets), and it must not take the machine's file over on boot. One writer
  per file by construction."
  ([] (daemon-file default-port))
  ([port]
   (java.io.File. (System/getProperty "user.home")
                  (if (= (long port) (long default-port))
                    ".slopp/daemon.json"
                    (str ".slopp/daemon-" port ".json")))))

(defn- store-file?
  "Whether `dir` has a store yet — a project's first durable write creates
  it, so a reader opened before that holds no connection and has to be
  re-opened once it exists."
  [dir]
  (.exists (java.io.File. (str dir) ".slopp/store.db")))

(defn- api!
  "The project at `dir`'s READER — a read-only session on its branch — and
  the read-API context assembled over it, as `{:reader :ctx}`: opened on
  first use, and re-opened when a store has appeared since (the reader
  before it was reading nothing). The reader answers about the BRANCH,
  which is what a consumer of a project's API wants, and it is what OWNS
  the project's app server: an agent's un-landed work is its own to read
  through its thread, and the served app is the landed state. It boots no
  oracle of its own unless something asks for one."
  [dir]
  (locking state
    (let [{:keys [reader ctx] :as api} (get-in @state [:projects dir :api])]
      (if (and ctx (or (:db @reader) (not (store-file? dir))))
        api
        (let [app (some-> reader deref :app-server)]
          (when reader (try (ops/close! reader) (catch Throwable _ nil)))
          (let [reader (external/open! {:slopp.ops/dir dir
                                        :slopp.ops/read-only? true
                                        :slopp.ops/lazy-image? true})
                ;; a server the old reader held carries over: the store
                ;; appearing is no reason to drop what is serving
                _      (when app (swap! reader assoc :app-server app))
                ctx    (slopp.http/context (server/serving-opts reader))
                api    {:reader reader :ctx ctx}]
            (swap! state assoc-in [:projects dir :api] api)
            api))))))

^:reads (defn- project-by-slug
  "The open project a path's slug names, or nil."
  [slug]
  (first (filter #(= slug (:slug %)) (vals (:projects @state)))))

(defn ^:export token
  "The per-boot secret the write door checks — minted on first ask, written
  beside the daemon's address so a shell on this machine can read it, and
  never served: loopback alone must not hand every local process the
  store's editing surface."
  []
  (locking state
    (or (:token @state)
        (let [t (str (java.util.UUID/randomUUID))]
          (swap! state assoc :token t)
          t))))

(def ^:export cli-idle-ms
  "How long the write door's CLI session for a project outlives its last
  call: ten minutes. A shell's calls come in bursts; between bursts the
  session is an idle image, and a project nobody is attached to should
  not stay open on its account."
  (* 10 60 1000))

(def ^:export session-idle-ms
  "How long an MCP session may go without a request before it is reaped:
  two hours. A client that ends without its DELETE (a killed process, a
  dropped laptop lid) would otherwise hold a project open forever. Generous
  because reaping is SAFE: the client's next request meets a 404, it
  re-initializes, the project re-opens, and the agent's thread was in the
  store the whole time."
  (* 2 60 60 1000))

(defn- ensure-project!
  "The project record for `dir`, minted under the display name `slug` (or
  the dir's basename) when this is the first thing to reach for it:
  `[proj first?]`. The record carries the project's CHECK QUEUE — the atom
  every session on it shares, so two full_checks at one content are one
  run. Under the lock; the caller holds it.

  The first open is also where zero-ceremony onboarding happens: a git
  checkout carrying a slopp branch whose store is absent or empty is
  imported before anything reads it (`sync/maybe-auto-import!`, a no-op
  for everything else). It rode the stdio server's start once; a project
  is opened here now."
  [dir slug]
  (if-let [p (get-in @state [:projects dir])]
    [p false]
    (let [_ (sync/maybe-auto-import! dir)
          p {:slug        (slug-for slug dir (set (map :slug (vals (:projects @state)))))
             :dir         dir
             :opened-at   (System/currentTimeMillis)
             :sessions    #{}
             :check-queue (atom {})}]
      (swap! state assoc-in [:projects dir] p)
      [p true])))

(defn- close-project!
  "Close the project at `dir`: its app server stopped, its CLI session and
  reader closed, its record dropped. Under the lock; the caller holds it
  and has already closed the MCP sessions. Nothing is landed — work lives
  on threads the store keeps."
  [dir]
  (let [p (get-in @state [:projects dir])]
    (when-let [cli (get-in p [:cli :session])]
      (try (ops/close! cli) (catch Throwable _ nil)))
    (when-let [reader (get-in p [:api :reader])]
      ;; the app image is a CHILD JVM: stop it before its owner goes,
      ;; or the port stays bound for as long as the reap takes
      (mcp/stop-app! reader)
      (try (ops/close! reader) (catch Throwable _ nil)))
    (swap! state update :projects dissoc dir)
    {:closed dir}))

(defn ^:export detach!
  "End MCP session `sid`: its slopp session closes (image reaped, connection
  released), and when nothing else holds the project — no other MCP session,
  no CLI session — the project closes too: it leaves the registry and
  everything it held goes. `{:ended sid}`, plus `:closed dir` when the
  project went; nil for a session this daemon does not hold.

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
      (if (and (empty? (get-in @state [:projects dir :sessions]))
               (nil? (get-in @state [:projects dir :cli])))
        (merge {:ended sid} (close-project! dir))
        {:ended sid}))))

(defn ^:export reset-all!
  "Close every session (and so every project), stop the listener, forget the
  token and the telemetry memory. The reaper thread ends itself on seeing
  no server."
  []
  (locking state
    (doseq [sid (keys (:sessions @state))]
      (detach! sid))
    (doseq [dir (keys (:projects @state))]
      (close-project! dir))
    (when-let [srv (:server @state)]
      (try (slopp.http/stop! srv) (catch Throwable _ nil)))
    (swap! state (constantly {:projects {} :sessions {} :server nil :token nil :reaper nil
                              :otel {:routed 0 :dropped 0} :otel-dirs {}}))
    nil))

(defn ^:export reap-idle!
  "Close what nothing has used: CLI sessions idle past [[cli-idle-ms]], MCP
  sessions idle past [[session-idle-ms]], and every project that is left
  holding nothing. `now` is a parameter so a test can be the clock.
  Answers what it closed."
  [now]
  (locking state
    (let [st       @state
          stale-mcp (for [[sid s] (:sessions st)
                          :when (< session-idle-ms (- now (:last-seen s)))] sid)
          stale-cli (for [[dir p] (:projects st)
                          :when (and (:cli p) (< cli-idle-ms (- now (get-in p [:cli :last-seen]))))] dir)]
      (doseq [dir stale-cli]
        (when-let [s (get-in @state [:projects dir :cli :session])]
          (try (ops/close! s) (catch Throwable _ nil)))
        (swap! state update-in [:projects dir] dissoc :cli))
      (let [ended  (vec (keep detach! stale-mcp))
            closed (vec (for [[dir p] (:projects @state)
                              :when (and (empty? (:sessions p)) (nil? (:cli p)))]
                          (close-project! dir)))]
        {:sessions (mapv :ended ended)
         :cli      (vec stale-cli)
         :projects (mapv :closed (concat (filter :closed ended) closed))}))))

(defonce ^:export image-cap
  ;; the machine-wide budget for verification images: how many child JVMs
  ;; the daemon's sessions may hold at once, across every project.
  ;; `SLOPP_DAEMON_MAX_IMAGES` sets it at boot; a test resets it. v1 is a
  ;; cap with a refusal that names the fix, not a scheduler.
  (atom (or (some-> (System/getenv "SLOPP_DAEMON_MAX_IMAGES") Long/parseLong) 6)))

^:reads (defn- images-up
  "How many verification images the daemon's sessions hold right now — MCP
  sessions, CLI sessions and readers alike."
  []
  (let [st @state]
    (count (filter #(some-> % deref :image)
                   (concat (map :session (vals (:sessions st)))
                           (keep #(get-in % [:cli :session]) (vals (:projects st)))
                           (keep #(get-in % [:api :reader]) (vals (:projects st))))))))

(defn- image-permit
  "The answer a lazy boot asks for: nil when an image may boot, else the
  refusal — which names the cap, what holds it, and the two ways past it."
  []
  (let [up  (images-up)
        cap @image-cap]
    (when (<= cap up)
      (str "the daemon holds " up " verification image(s) already, which is its cap (" cap
           ") — no image boots for this session until one is released: a session's"
           " image goes when it detaches or after " (quot session-idle-ms 60000)
           " min idle, a CLI session's after " (quot cli-idle-ms 60000)
           " min. SLOPP_DAEMON_MAX_IMAGES raises the cap for the next daemon."
           " Store-value reads need no image and keep working."))))

(defn- announce-landing!
  "What session `from` landed on the project at `dir`, queued as an event on
  every OTHER session there — MCP and CLI — so their next answer says so. A
  thread with work in flight learns its done will rebase; an idle one
  learns its view has moved."
  [dir from land]
  (let [st   @state
        note (str "thread " (or (:thread land) "?") " landed onto " (:landed land)
                  " (head " (:head land) ") while you work — your view follows if idle;"
                  " your done rebases onto it otherwise")]
    (doseq [[sid s] (:sessions st)
            :when (and (= dir (:dir s)) (not= sid from))]
      (ops/note-event! (:session s) {:kind :landed :note note}))
    (when-let [cli (get-in st [:projects dir :cli :session])]
      (when (not= from :cli)
        (ops/note-event! cli {:kind :landed :note note})))))

^:reads (defn ^:export reader-open?
  "Whether the project at `dir` has opened its reader — the second session
  on its store that the read API and the app server share. Attaching alone
  does not open one; the first API request or app refresh does."
  [dir]
  (some? (get-in @state [:projects dir :api :reader])))

^:reads (defn- holds-thread?
  "Whether the store behind `conn` has an OPEN thread by `thread` — the
  agent's id, which is what its harness telemetry carries as `session.id`."
  [conn thread]
  (boolean (some #(and (= "thread" (:kind %)) (= "open" (:status %))
                       (= thread (:agent %)))
                 (db/lines conn))))

^:reads (defn- session-holding
  "Where a record for `thread` goes: `{:session s}` — a session on the
  project whose store holds that thread open, read through an attached
  session's connection or the project's opened reader — else `{:dir d}`,
  the dir this daemon remembers routing that thread to before its project
  closed, else nil. A hit teaches the memory."
  [thread]
  (let [st   @state
        with (fn [s dir]
               (when (and s (some-> s deref :db) (holds-thread? (:db @s) thread))
                 (swap! state assoc-in [:otel-dirs thread] dir)
                 {:session s}))]
    (or (some (fn [p]
                (or (some (fn [sid] (with (get-in st [:sessions sid :session]) (:dir p)))
                          (:sessions p))
                    (with (get-in p [:cli :session]) (:dir p))
                    (with (get-in p [:api :reader]) (:dir p))))
              (vals (:projects st)))
        (when-let [d (get-in st [:otel-dirs thread])]
          {:dir d}))))

(defn- api-url
  "Where project `proj`'s read API answers on THIS daemon —
  `http://127.0.0.1:<port>/api/projects/<slug>` — or nil while the daemon
  has bound nothing (a test attaching straight to the registry)."
  [proj]
  (when-let [p (:port (:server @state))]
    (str "http://127.0.0.1:" p "/api/projects/" (:slug proj))))

(def ^:private registry-contract
  "What `/api/projects` answers: one row per open project, the shape
  [[projects]] builds."
  [:sequential
   [:map
    [:slug {:doc "the display name the project answers under in every path"} :string]
    [:dir {:doc "the project's absolute directory — what it is resolved by"} :string]
    [:opened-at {:doc "when this daemon opened it, epoch milliseconds"} :int]
    [:sessions {:doc "MCP sessions attached right now"} :int]
    [:cli {:doc "whether the write door holds a CLI session for it"} :boolean]
    [:app {:doc "what its dev server serves, or nil when it serves nothing"}
     [:maybe [:map
              [:url {:doc "the app server's address"} :string]
              [:branch {:doc "the branch the app is served from"} [:maybe :string]]]]]]])

(defn ^{:http/method :get :rest/path "/api/projects" :http/auth :public
        :rest/response registry-contract}
  projects-endpoint
  "`GET /api/projects` — THE REGISTRY, as data: every project this daemon
  holds. What the picker page and every page's project switcher read to
  know what there is to render."
  [_req]
  {:status 200 :body (projects)})

(def ^:private status-contract
  "What `/api/status` answers: the daemon about itself."
  [:map
   [:projects {:doc "open projects"} :int]
   [:sessions {:doc "attached MCP sessions, across every project"} :int]
   [:images {:doc "verification images up, against the machine-wide cap"}
    [:map [:up {:doc "child JVMs alive right now"} :int]
          [:cap {:doc "the most this daemon will boot (SLOPP_DAEMON_MAX_IMAGES)"} :int]]]
   [:otel {:doc "telemetry records placed into a store, and dropped for naming no thread"}
    [:maybe [:map [:routed {:doc "records placed"} :int]
                  [:dropped {:doc "records nobody could own"} :int]]]]
   [:port {:doc "the port bound, or nil before the daemon listens"} [:maybe :int]]])

(defn ^{:http/method :get :rest/path "/api/status" :http/auth :public
        :rest/response status-contract}
  status-endpoint
  "`GET /api/status` — the daemon about itself: how many projects and
  sessions it holds, how many images are up against the cap, what the
  telemetry sink placed and dropped."
  [_req]
  (let [st @state]
    {:status 200
     :body {:projects (count (:projects st))
            :sessions (count (:sessions st))
            :images   {:up (images-up) :cap @image-cap}
            :otel     (:otel st)
            :port     (:port (:server st))}}))

(def ^:private jsonrpc-contract
  "One JSON-RPC message, as the MCP endpoint takes it. One per POST: the
  2025-06-18 spec dropped batching, the pipe never sent one, and a batch
  is the contract's 400 rather than a second shape to carry."
  [:map
   [:slug {:doc "the project's display name, from the path"} :string]
   [:jsonrpc {:doc "the JSON-RPC version, always \"2.0\""} :string]
   [:id {:optional true :doc "the request id; absent (or null) on a notification"} [:maybe [:or :int :string]]]
   [:method {:optional true :doc "the method, on a request or a notification"} :string]
   [:params {:optional true :doc "the method's parameters"} :map]
   [:result {:optional true :doc "a client's answer to a request the server made"} :any]
   [:error {:optional true :doc "a client's error answer to such a request"} :map]])

(def ^:private call-contract
  "What `slopp <op>` posts to the write door: the op, its arguments, and
  the daemon's token."
  [:map
   [:slug {:doc "an open project's display name, or `_` to name the project by X-Slopp-Dir"} :string]
   [:tool {:doc "the op to run"} :string]
   [:arguments {:optional true :doc "the op's arguments"} :map]
   [:token {:optional true :doc "the daemon's per-boot secret, from ~/.slopp/daemon.json; refused without it"} :string]
   [:agent {:optional true :doc "a provenance label for the call"} :string]])

(def ^:private otel-contract
  "An OTLP/HTTP JSON logs export — the spec's ExportLogsServiceRequest,
  whose one key holds batches slopp reads but does not own the shape of."
  [:map
   [:resourceLogs {:optional true :doc "the export's resource batches, in OTLP's own shape"} [:sequential :any]]])

^:unsafe (defn ^{:http/method :post :rest/path "/api/otel/v1/logs" :http/auth :public
                 :rest/request otel-contract
                 :rest/response :any
                 :rest/unconstrained-ok "OTLP's own acknowledgement: an empty object"}
  otel-endpoint
  "`POST /api/otel/v1/logs` — the machine's ONE telemetry sink. An
  exporter given `OTEL_EXPORTER_OTLP_ENDPOINT=…/api/otel` appends the
  spec's `/v1/logs` itself. Each `api_request` record routes by its
  `session.id` — the agent's thread id — to the project holding that
  thread: through an attached session, the project's reader, or — once the
  project has closed — the dir this daemon remembers for the thread,
  through a connection opened for the batch and closed after it. What
  names no thread this daemon has ever placed is counted and dropped,
  never guessed at. A body that is not an OTLP export is the contract's
  400, the non-retryable answer; anything that is answers 200 — an
  exporter retries on anything else, and a batch this daemon could not
  place is not one it wants again."
  [req]
  (let [r (api.otel/decode (:body req))]
    (if (:bad r)
      {:status 400 :http/raw true
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:partialSuccess {:errorMessage (str "could not parse this export: " (:bad r))}})}
      (do (doseq [[thread recs] (group-by :session (slopp.otel/api-requests (:ok r)))]
            (let [{:keys [session dir]} (session-holding thread)
                  placed (cond
                           session (do (ops/record-otel! session recs) true)
                           dir     (when-let [conn (try (db/open! dir {:create? false})
                                                        (catch Throwable _ nil))]
                                     (try (ops/record-otel! (atom {:db conn}) recs) true
                                          (finally (try (.close ^java.sql.Connection conn)
                                                        (catch Throwable _ nil)))))
                           :else   false)]
              (swap! state update-in [:otel (if placed :routed :dropped)] + (count recs))))
          {:status 200 :http/raw true
           :headers {"Content-Type" "application/json"}
           :body "{}"}))))

(defn- project-of
  "The OPEN project a request names — by the slug in its path, or by
  `X-Slopp-Dir` under slug `_` (the prompt hook knows its dir and no
  slug) — or nil. A read never opens one."
  [req]
  (or (project-by-slug (get-in req [:path-params :slug]))
      (when-let [dir (:dir (project-dir req))]
        (get-in @state [:projects dir]))))

(defn ^{:http/read :daemon/project-api} delegate!
  "Read performer: answer a project-API request from the project's OWN
  assembled context. The mount `/api/projects/<slug>` is replaced by the
  `/api` the project's contract declares, so every contract, validator and
  performer applies unchanged. The value is the WHOLE request — the read
  [[project-api-endpoint]] declares has an empty path — because the project
  is named by the slug in the path or by a header, and a read addresses one
  value. Opens the project's reader on first use; an unknown project is 404."
  [_ctx req]
  (if-let [p (project-of req)]
    (slopp.http/handle! (:ctx (api! (:dir p)))
                        (-> req
                            (assoc :uri (str "/api/" (get-in req [:path-params :*] "")))
                            (dissoc :path-params :query-params :http/deps :http/reads)))
    {:status 404 :body {:error (str "no open project " (get-in req [:path-params :slug]))}}))

(defn ^{:http/method :get :rest/path "/api/projects/:slug/mcp" :http/auth :public
        :rest/response :any
        :rest/unconstrained-ok "a refusal with an Allow header, never a document"}
  mcp-get-endpoint
  "`GET /api/projects/:slug/mcp` — declined: there is no standalone
  stream here, answers ride the POST responses. Its own var so the safe
  method stays safe: it touches nothing."
  [_req]
  {:status 405 :http/raw true
   :headers {"Allow" "POST, DELETE" "Content-Type" "application/json"}
   :body (json/generate-string {:error "no standalone stream here — answers ride the POST responses"})})

(defn ^{:http/method :get :rest/path "/api/projects/:slug/**" :http/auth :public
        :http/reads {:answer [:daemon/project-api []]}
        :rest/response :any
        :rest/unconstrained-ok "whatever the project's own contract answers — validated there, by the same validator"}
  project-api-endpoint
  "`GET /api/projects/:slug/**` — the project's typed read API, mounted:
  `/api/projects/<slug>/<resource>` is `/api/<resource>` in the project's
  own contract, answered from its own assembled context. The whole answer
  is a declared READ (the performer opens the project's reader and
  delegates) so this var does nothing but hand it back — a GET must stay
  safe, and it is. A GET only: the reader behind it is read-only."
  [req]
  (:answer (:http/reads req)))

(def ^:private asset-mounts
  "The static mounts the daemon serves slopp's own pages with — `{url-prefix
  manifest-prefix}`, the same line slopp's store declares as
  `http.static./assets`, so the bundle `compile_client` writes at
  `public/cljs/main.js` answers at `/assets/cljs/main.js`. Spelled here as
  data rather than read off a capability because the daemon may have no
  store to read one from: started from a neutral dir it serves the bundle
  the jar carries."
  {"/assets" "public"})

(def ^:private bundle-url
  "Where the shell's script tag points: `compile_client`'s default output,
  `public/cljs/main.js`, joined against [[asset-mounts]] — the same join
  `slopp.rules.http/bundle-url` makes for a project from its own
  capabilities."
  (let [[url-prefix path-prefix] (first asset-mounts)]
    (str url-prefix (subs "public/cljs/main.js" (count path-prefix)))))

(defn- asset-reader
  "The reader behind the static mount — `(fn [path] {:content :content-type})`
  or nil. Three sources, decided once at assembly, first match wins:

  - the dir a MANAGER materialized, named in the `slopp.static-dir` system
    property: a managed child (slopp's own dev instance runs this daemon as
    a declared entry) has no store and no boot record, and its manager
    writes the mounts' bytes to a dir and re-syncs them at every refresh —
    reading that dir per request is what keeps a recompiled bundle current
    there. Without it the child fell through to the classpath below, which
    has no `public/`, and 404'd its own bundle;
  - the daemon's OWN store, when it booted from a dir that has one (the
    self-host loop: slopp's checkout under `--live`): the bundle
    `compile_client` just wrote is served on the next request, an
    artifact's bytes fetched from the store dir's cache by sha, exactly as
    a managed app server's assets are;
  - the CLASSPATH otherwise: a daemon started from a neutral dir has no
    store, and the jar carries `public/` for exactly this.

  The store is reached through the project READER, which is the same lazy
  reader every project's API answers from — opened on first use, not at
  assembly — so listing the pages costs a daemon with no visitors nothing."
  []
  (let [told (System/getProperty "slopp.static-dir")
        dir  (try (:dir ((store/late-ref 'slopp.kernel.boot/current-boot-info)))
                  (catch Throwable _ nil))]
    (cond
      told (static/file-or-resource-reader told)

      (and dir (store-file? dir))
      (fn [path]
        (let [st (:store @(:reader (api! dir)))
              {:keys [content content-type sha]} (store/file-content st path)]
          (when (or content sha)
            {:content (or content
                          (let [f (artifacts/cache-file dir sha)]
                            (when (.exists ^java.io.File f)
                              (java.nio.file.Files/readAllBytes (.toPath ^java.io.File f)))))
             :content-type content-type})))

      :else (static/file-or-resource-reader (or dir ".")))))

(defn- serving-opts
  "Everything the daemon serves, as the opts `slopp.http/context` and
  `slopp.http/serve!` both take — the same assembly every project's app
  goes through:

  - the endpoints DECLARED on this namespace's public vars, under the rest
    validator, and the read performer [[delegate!]] declares;
  - the PAGES: the shell (`slopp.ui.shell`, one document at every page
    address) and the stylesheet (`slopp.ui.styles`), declared content on
    namespaces listed here;
  - the client route table, read off the loaded page markers the way the
    headless driver reads them, so the shell's status is DERIVED — 404 for
    an address no page claims — and the two readers of one marker cannot
    disagree;
  - the static mount for the compiled bundle, read from this daemon's own
    store or the jar ([[asset-reader]]).

  One map for the listener and for a test driving the context without a
  port, so the two cannot disagree."
  []
  {:http/namespaces ['slopp.daemon 'slopp.ui.shell 'slopp.ui.styles]
   :http/routes (static/mount-routes asset-mounts (asset-reader))
   :webapp/bundle bundle-url
   :webapp/base ""
   :webapp/routes (cljnx/marked-pages)
   :http/wrap-context slopp.rest/validating})

(defn ^:export context
  "The assembled dispatch context for every route under `/api/` — what the
  listener serves, and what a test drives without a port."
  []
  (slopp.http/context (serving-opts)))

(defn ^:export start!
  "Bind the daemon's listener on `port` (loopback; nil = [[default-port]])
  and answer immediately: `{:url :port :token}`. Nothing is loaded until
  something attaches — binding first is what fits a client's startup
  window, which a JVM that opened a store before listening would miss. The
  idle reaper starts beside it. A taken port throws with the bind
  diagnosis leading, as every slopp listener does."
  [port]
  (locking state
    (when (:server @state)
      (throw (ex-info "this process already runs a daemon" {:port (:port (:server @state))})))
    (let [srv    (slopp.http/serve! (assoc (serving-opts)
                                           :http/host "127.0.0.1"
                                           :http/port (or port default-port)))
          reaper (doto (Thread. ^Runnable
                               (fn []
                                 (while (:server @state)
                                   (Thread/sleep 60000)
                                   (try (reap-idle! (System/currentTimeMillis))
                                        (catch Throwable _ nil))))
                               "slopp-daemon-reaper")
                   (.setDaemon true))]
      (swap! state assoc :server srv :reaper reaper)
      (.start reaper)
      {:url (str "http://127.0.0.1:" (:port srv) "/api/") :port (:port srv) :token (token)})))

^:unsafe (defn -main
  "Run the daemon: `slopp daemon [port]` — which is `slopp <dir> [--live]
  --main slopp.daemon/-main [port]`. The dir is what the kernel loads
  slopp's OWN code from, every namespace of that dir's store, so it is a
  NEUTRAL dir (`~/.slopp`, no store) in use; never a user's project, whose
  store would be loaded as if it were slopp. The projects it serves are
  whatever attaches. Records itself — address, pid, the write door's token
  — in [[daemon-file]] for its port and blocks.

  slopp's own DEV instance is this same fn on another port (7358),
  declared in its store's dev config as `run.daemon.main` and run from the
  store by the machinery every project's dev instance gets: booted in a
  child image on first attach, refreshed at every done, replaced by
  `restart {app true}`. It records itself under its own file and leaves
  the machine's alone.

  A second daemon on the port refuses and names the live one from that
  file, so two never race for one machine's projects."
  [& [port]]
  (let [p (long (or (some-> port str Long/parseLong)
                    (some-> (System/getenv "SLOPP_DAEMON_PORT") Long/parseLong)
                    default-port))
        f (daemon-file p)]
    (try
      (let [r (start! p)]
        (.mkdirs (.getParentFile f))
        (spit f (json/generate-string
                 {:url (:url r) :port (:port r) :token (:token r)
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

(defn- pages-url
  "Where project `proj`'s PAGES answer on THIS daemon —
  `http://127.0.0.1:<port>/p/<slug>` — the address to hand a HUMAN, beside
  [[api-url]] for a program; nil while the daemon has bound nothing."
  [proj]
  (when-let [p (:port (:server @state))]
    (str "http://127.0.0.1:" p "/p/" (:slug proj))))

(defn ^:export attach!
  "Open an MCP session on the project at `dir`, opening the project itself
  when this is its first — under the display name `slug` the client asked
  for, or the dir's basename: `{:sid :session}`.

  ONE slopp session per MCP session, on the shared store. A session holds a
  thread, a line, a store value and a read ledger, and a write naming a
  different thread reloads all of it — which is one agent's business, not
  every agent's on the project. What the project shares lives on the
  project record: its registry row, its check queue (so two whole-store
  checks at one content are one run), and its READER — the session the
  read API answers from and the app server's owner, which every session
  names as its `:app-owner` as a DELAY: a project whose app nobody serves
  and whose API nobody reads never opens it, and a large store is not
  loaded twice for nothing. The FIRST attach starts the app server,
  backgrounded, when the store runs one at all: one per project, on the
  branch the project opened on, as decided.

  The session's oracle is LAZY: nothing boots until a call needs an image,
  so a session that only reads — most of them — costs no child JVM; and
  when one does boot it asks the machine-wide budget first. What this
  session lands is announced to the project's other sessions.

  Under the lock for the whole open, deliberately: two first-attaches on
  one dir must not both mint the project."
  [dir slug]
  (locking state
    (let [now     (System/currentTimeMillis)
          sid     (str (java.util.UUID/randomUUID))
          [proj first?] (ensure-project! dir slug)
          owner   (delay (:reader (api! dir)))
          session (external/open! {:slopp.ops/dir         dir
                                   :slopp.ops/lazy-image? true
                                   ;; the session's own label; the THREAD an
                                   ;; agent writes on is what it passes
                                   :slopp.ops/agent-id    (str "mcp-" (subs sid 0 8))})]
      (swap! session assoc :require-turns? true :daemon? true
             :app-owner owner
             :op-cards mcp/op-cards
             :api-url (api-url proj)
             :pages-url (pages-url proj)
             :check-queue (:check-queue proj)
             :image-permit image-permit
             :on-landed (fn [land] (announce-landing! dir sid land)))
      (swap! state #(-> %
                        (update-in [:projects dir :sessions] conj sid)
                        (assoc-in [:sessions sid] {:dir dir :session session
                                                   :started now :last-seen now})))
      (when (and first? (mcp/app-managed? session))
        (future (mcp/start-app! @owner)))
      {:sid sid :session session})))

(defn- cli-session!
  "The CLI session the write door runs a project's calls on, opened on
  first use — opening the project under `slug` if nothing is attached —
  and touched on every call so the reaper knows it is in use. Writable,
  turn-gated like every real session, carrying the daemon's token as its
  `:call-token`, the project's reader as its app owner (a delay, like every
  session's) and the project's check queue; its oracle is lazy and
  budgeted, like every daemon session's, and what it lands is announced."
  [dir slug]
  (locking state
    (let [now (System/currentTimeMillis)
          [proj _] (ensure-project! dir slug)]
      (if-let [s (get-in @state [:projects dir :cli :session])]
        (do (swap! state assoc-in [:projects dir :cli :last-seen] now)
            s)
        (let [session (external/open! {:slopp.ops/dir         dir
                                       :slopp.ops/lazy-image? true
                                       :slopp.ops/agent-id    (str "cli-" (subs (str (java.util.UUID/randomUUID)) 0 8))})]
          (swap! session assoc :require-turns? true :daemon? true
                 :call-token (token)
                 :app-owner (delay (:reader (api! dir)))
                 :op-cards mcp/op-cards
                 :api-url (api-url proj)
                 :pages-url (pages-url proj)
                 :check-queue (:check-queue proj)
                 :image-permit image-permit
                 :on-landed (fn [land] (announce-landing! dir :cli land)))
          (swap! state assoc-in [:projects dir :cli] {:session session :last-seen now})
          session)))))

^:unsafe (defn ^{:http/method :post :rest/path "/api/projects/:slug/call" :http/auth :public
                 :rest/request call-contract
                 :rest/response :any
                 :rest/unconstrained-ok "the op's own answer as the CLI prints it: {isError text}"}
  call-endpoint
  "`POST /api/projects/:slug/call` — the write door: `{tool arguments
  token}` runs on the project named by `X-Slopp-Dir` (slug `_`) or by an
  open project's slug, on its CLI session. The token is the daemon's and
  [[slopp.mcp/http-call!]] checks it. A WRITE naming no thread is refused
  here with the same words a one-shot uses, because the alternative is the
  same stranding: the call would land on the CLI session's own line, which
  nothing a shell knows about ever lands. Refused as an ANSWER — 200 with
  `isError`, the shape every refusal on this door has — because a 4xx reads
  to the CLI as a failed route, and it falls back to booting a one-shot JVM
  that refuses again after loading the whole store (the first end-to-end
  run did exactly that)."
  [req]
  (let [{:keys [dir error]} (project-dir req)
        raw (fn [status m]
              {:status status :http/raw true
               :headers {"Content-Type" "application/json"}
               :body (json/generate-string m)})
        b    (or (:body req) {})
        tool (:tool b)
        args (or (:arguments b) {})]
    (cond
      error
      (raw 400 {:error error})

      (and (string? tool)
           (mcp/write-tool? tool)
           (nil? (:thread args)) (nil? (:agent args)) (nil? (:agent b)))
      (raw 200 {:isError true
                :text (str "error: a write through the daemon names no thread — it would land"
                           " on a line nothing you know about ever lands. Pass {thread \"…\"}:"
                           " the id from your [slopp] block, or one minted by"
                           " thread_open {} and carried on every later call.")})

      :else
      (mcp/http-call! (assoc req :body b)
                      (cli-session! dir (let [slug (get-in req [:path-params :slug])]
                                          (when (not= "_" slug) slug)))))))

(defn- mcp-doors
  "This daemon's doors, lent to the MCP envelope: sessions by id, attach
  by the dir a request names, detach."
  []
  {:slopp.mcp.http/lookup  lookup!
   :slopp.mcp.http/attach! (fn [req]
                             (let [{:keys [dir error]} (project-dir req)]
                               (if error
                                 {:error error}
                                 (attach! dir (get-in req [:path-params :slug])))))
   :slopp.mcp.http/detach! detach!})

^:unsafe (defn ^{:http/method :post :rest/path "/api/projects/:slug/mcp" :http/auth :public
                 :rest/request jsonrpc-contract
                 :rest/response :any
                 :rest/unconstrained-ok "the answer is the JSON-RPC envelope's, whatever the message asked"}
  mcp-post-endpoint
  "`POST /api/projects/:slug/mcp` — MCP over streamable HTTP: one JSON-RPC
  message in, its answer out, the session id riding a header. The
  envelope is [[slopp.mcp.http/endpoint]], lent this daemon's doors."
  [req]
  (mcp.http/endpoint (mcp-doors) req))

^:unsafe (defn ^{:http/method :delete :rest/path "/api/projects/:slug/mcp" :http/auth :public
                 :rest/response :any
                 :rest/unconstrained-ok "the envelope's own acknowledgement, or its 404"}
  mcp-delete-endpoint
  "`DELETE /api/projects/:slug/mcp` — detach the session the header names;
  the last detach closes the project."
  [req]
  (mcp.http/endpoint (mcp-doors) req))
