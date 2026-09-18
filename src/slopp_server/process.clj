(ns slopp-server.process
  "ONE slopp per machine. A project is opened on the first agent's attach (an
  MCP session at its endpoint) and closed on the last detach; the daemon
  itself stays up with nothing loaded. It is the registry (`/api/projects`)
  and the owner of every surface under the one root `/api/` — each route a
  DECLARED rest endpoint on this namespace's public vars, assembled and
  validated by the same component every project's API goes through, with
  each open project's own API mounted at `/api/projects/<slug>/<resource>`."
  (:require [clojure.string :as str]
            [slopp.http :as slopp.http]
            [slopp-server.mcp.http :as mcp.http]
            [slopp.ops :as ops]
            [slopp.ops.external :as external] [cheshire.core :as json] [slopp-server.telemetry :as telemetry] [slopp.otel :as slopp.otel] [slopp.store.db :as db] [slopp-server.mcp :as mcp] [slopp.rest :as slopp.rest] [slopp.sync :as sync] [slopp.store :as store] [slopp.store.artifacts :as artifacts] [slopp.http.static :as static] [slopp.cljnx :as cljnx]
            ;; EVERYTHING this daemon serves, REQUIRED and not only named in
            ;; `serving-opts`: a route builder reads loaded vars, and a process
            ;; that loads only this namespace's closure — a managed child, a
            ;; jar booted from a neutral dir — serves nothing from a namespace
            ;; the closure does not reach. The kernel's load-everything boot
            ;; hides the gap, which is how it shipped twice: the pages first
            ;; (the dev instance answered 200 everywhere), then the shell and
            ;; the stylesheet (the v0.3.0 jar answered 404 everywhere).
            [slopp-server.ui.pages]
            [slopp-server.ui.shell]
            [slopp-server.ui.styles] [slopp-server.process.hooks :as hooks] [slopp.read.history :as history] [slopp.ops.engine :as engine] [clojure.java.io :as io] [slopp.rules.http :as rules.http] [slopp.rules.webapp :as rules.webapp] [slopp.project.capabilities :as capabilities] [slopp-server.api.reads :as api.reads] [slopp.webdev.live :as live] [slopp.http.client :as http.client]))

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
  "slopp's own `http.port`, BAKED — the port [[configured-port!]] falls back to
  when no store is readable, which is the released jar's neutral-dir boot. The
  self-host daemon reads `http.port` from its store instead, so this is the one
  place the number is spelled when there is no store to read it from. One per
  machine, so one number rather than a per-directory formula: the derived-port
  formula existed so N per-session listeners would not collide, and there is one
  listener now. SLOPP_PORT / the argument / the manager override it."
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
  "The project a request names, by `X-Slopp-Dir` — an existing absolute
  directory, canonicalized. `{:dir}` or `{:error}`.

  By DIR, because `.mcp.json` is written before any daemon exists and a
  slug minted at attach time cannot be known then; the header is what the
  plugin writes, literally (env expansion in headers is unreliable). The
  write/MCP doors are slug-free — one door, addressed by this header — so
  there is no path slug to fall back to."
  [req]
  (let [h (get-in req [:headers "x-slopp-dir"])]
    (if (str/blank? (str h))
      {:error "send X-Slopp-Dir: <absolute project dir> to name the project"}
      (let [f (java.io.File. (str h))]
        (if (and (.isAbsolute f) (.isDirectory f))
          {:dir (.getCanonicalPath f)}
          {:error (str "X-Slopp-Dir must name an existing absolute directory: " h)})))))

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

(defn ^{:export true
        :breaking-ok "the [port machine] arity is REMOVED with the machine setting: the default port's daemon is the machine's, and any other port records under its own name"}
  daemon-file
  "Where a daemon records itself: `~/.slopp/daemon.json` — `{url port token
  pid started}` — for the default port, `~/.slopp/daemon-<port>.json` for
  any other. The shell derives the same name from the same one knob
  (`SLOPP_PORT`), so the two never disagree; a dev instance on
  another port (slopp's own, run from its store by the same machinery every
  project's dev instance gets) never takes the default's file over on boot.
  One writer per file by construction."
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
  "The project at `dir`'s READER — a read-only session on its branch —
  opened on first use, and re-opened when a store has appeared since (the
  reader before it was reading nothing), as `{:reader}`. The reader answers
  about the BRANCH, which is what a consumer of a project's API wants, and it
  is what OWNS the project's app server: an agent's un-landed work is its own
  to read through its thread, and the served app is the landed state. It boots
  no oracle of its own unless something asks for one."
  [dir]
  (locking state
    (let [{:keys [reader] :as api} (get-in @state [:projects dir :api])]
      (if (and reader (or (:db @reader) (not (store-file? dir))))
        api
        (let [app (some-> reader deref :app-server)]
          (when reader (try (ops/close! reader) (catch Throwable _ nil)))
          (let [reader (external/open! {:slopp.ops/dir dir
                                        :slopp.ops/read-only? true
                                        :slopp.ops/lazy-image? true})
                _      (when app (swap! reader assoc :app-server app))
                api    {:reader reader}]
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
   [:jsonrpc {:doc "the JSON-RPC version, always \"2.0\""} :string]
   [:id {:optional true :doc "the request id; absent (or null) on a notification"} [:maybe [:or :int :string]]]
   [:method {:optional true :doc "the method, on a request or a notification"} :string]
   [:params {:optional true :doc "the method's parameters"} :map]
   [:result {:optional true :doc "a client's answer to a request the server made"} :any]
   [:error {:optional true :doc "a client's error answer to such a request"} :map]])

(def ^:private call-contract
  "What `slopp <op>` posts to the write door: the op, its arguments, and
  the daemon's token. The project is named by X-Slopp-Dir, not a path slug."
  [:map
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
  (let [r (telemetry/decode (:body req))]
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

(defn ^{:http/method :get :rest/path "/api/mcp" :http/auth :public
        :rest/response :any
        :rest/unconstrained-ok "a refusal with an Allow header, never a document"}
  mcp-get-endpoint
  "`GET /api/mcp` — declined: there is no standalone stream here, answers
  ride the POST responses. Its own var so the safe method stays safe: it
  touches nothing."
  [_req]
  {:status 405 :http/raw true
   :headers {"Allow" "POST, DELETE" "Content-Type" "application/json"}
   :body (json/generate-string {:error "no standalone stream here — answers ride the POST responses"})})

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

(defn- pages-url
  "Where project `proj`'s PAGES answer on THIS daemon —
  `http://127.0.0.1:<port>/p/<slug>` — the address to hand a HUMAN, beside
  [[api-url]] for a program; nil while the daemon has bound nothing."
  [proj]
  (when-let [p (:port (:server @state))]
    (str "http://127.0.0.1:" p "/p/" (:slug proj))))

(defn spit-private!
  "Write `content` to `f` readable and writable by its OWNER only, creating
  the parents. The daemon file carries the write door's token, and `spit`
  alone wrote it under the umask — `-rw-r--r--`, every local USER able to
  read the secret that exists so that loopback alone does not hand every
  local process the store's editing surface. Recreated from scratch, so no
  window serves the old mode; a file system without POSIX modes falls back
  to the `java.io.File` bits."
  [^java.io.File f ^String content]
  (.mkdirs (.getParentFile f))
  (.delete f)
  (try
    (java.nio.file.Files/createFile
     (.toPath f)
     (into-array java.nio.file.attribute.FileAttribute
                 [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                   (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))]))
    (catch UnsupportedOperationException _
      (.createNewFile f)
      (.setReadable f false false)
      (.setReadable f true true)
      (.setWritable f false false)
      (.setWritable f true true)))
  (spit f content))

(defn ^{:breaking-ok "the machine-setting source is REMOVED: ~/.slopp/config.json's daemon-port is retired for the one knob SLOPP_PORT, which the plugin's MCP url can read and a file cannot"}
  daemon-port
  "The port `slopp daemon [port]` listens on, as `{:port n}` or `{:error
  sentence}` for a value that is not one. Four sources, in order: the
  argument `arg`; the environment (`env`, `SLOPP_PORT` — the ONE knob,
  because the plugin's MCP entry is a URL Claude Code expands from the
  environment and a settings file would be a second source it cannot read);
  what the MANAGER told a declared entry (`told`, the `slopp.app-port`
  property — slopp's own dev instance is told its port rather than passed
  it); else `base` — slopp's OWN `http.port` capability, supplied by
  [[configured-port!]] (read from its store, or the baked [[default-port]]
  when no store is readable). The daemon is a normal app, so its resting
  port is `http.port` like any app's; the three sources above only override
  it. A bad value used to be an uncaught NumberFormatException: a stack
  trace where the one fact that matters is which value was wrong."
  [arg env told base]
  (let [raw (some->> [arg env told] (map #(some-> % str str/trim not-empty)) (some identity))]
    (cond
      (nil? raw)
      {:port base}

      :else
      (let [n (try (Long/parseLong raw) (catch NumberFormatException _ nil))]
        (if (and n (< 0 n 65536))
          {:port n}
          {:error (str (pr-str raw) " is not a port (1–65535) — slopp daemon [port],"
                       " SLOPP_PORT=<n>, or the http.port capability")})))))

(defn- own-reader!
  "A read-only reader on the daemon's OWN store at `dir`, for the assets the
  static mount serves under `--live` — opened on first use, kept on the state
  map beside the projects and NOT among them. It used to be the project
  reader `api!` opens, which registered a project record for the daemon's
  dir holding nothing but the reader: an attach to that dir then adopted the
  half-record, and detach, reap and reset threw on its nil session set. The
  reaper swallowed that throw, so one browser hit followed by one attach
  ended idle reaping for the life of the process. Its value is synced with
  the journal before each read (one PRAGMA when nothing moved), so the
  bundle `compile_client` just wrote is what the next request serves."
  [dir]
  (let [r (locking state
            (or (:own-reader @state)
                (let [r (external/open! {:slopp.ops/dir         dir
                                         :slopp.ops/read-only?  true
                                         :slopp.ops/lazy-image? true})]
                  (swap! state assoc :own-reader r)
                  r)))]
    (try (ops/sync-with-journal! r) (catch Throwable _ nil))
    r))

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

  The store is reached through a reader of the daemon's OWN ([[own-reader!]]),
  opened on first use, not at assembly — so listing the pages costs a daemon
  with no visitors nothing — and never through a PROJECT reader: an asset
  request is not an attachment, and the project record `api!` minted for it
  once was the half-record that broke the reaper."
  []
  (let [told (System/getProperty "slopp.static-dir")
        dir  (try (:dir ((store/late-ref 'slopp.kernel.boot/current-boot-info)))
                  (catch Throwable _ nil))]
    (cond
      told (static/file-or-resource-reader told)

      (and dir (store-file? dir))
      (fn [path]
        (let [st (:store @(own-reader! dir))
              {:keys [content content-type sha]} (store/file-content st path)]
          (when (or content sha)
            {:content (or content
                          (let [f (artifacts/cache-file dir sha)]
                            (when (.exists ^java.io.File f)
                              (java.nio.file.Files/readAllBytes (.toPath ^java.io.File f)))))
             :content-type content-type})))

      :else (static/file-or-resource-reader (or dir ".")))))

(defn- text
  "A text/plain answer: what a hook or a shell prints as it stands."
  [status s]
  {:status status :http/raw true
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (str s)})

(defn- mailbox!
  "Record an ask for the next write to open its turn from: this session's
  own `pending-intent.<sid>` and the unscoped `pending-intent` an older
  server reads — two files, so a second session's ask can never overwrite
  an unread first."
  [dir sid prompt]
  (let [payload (json/generate-string {:session-id sid :prompt prompt})
        safe    (str/replace (str sid) #"[^A-Za-z0-9_-]" "")]
    (spit (io/file dir ".slopp" "pending-intent") payload)
    (when-not (str/blank? safe)
      (spit (io/file dir ".slopp" (str "pending-intent." safe)) payload))))

(defn- tail-data
  "What [[slopp-server.process.hooks/tail-context]] prints, read off the project's
  reader on its line: the store's size, the last commit point, the recent
  asks, the whole-store verdict when it still stands, the last done."
  [reader sid]
  (let [conn (:db @reader)
        line (engine/session-line reader)
        fc   (db/last-full-check conn line)
        st   (when fc (history/standing-full-check fc (db/ops-after conn line (:id fc))))]
    {:namespaces         (count (:namespaces (:store @reader)))
     :last-commit        (:description (first (db/newest-commit-markers conn line 1)))
     :sid                sid
     :recent-asks        (keep :intent (db/recent-markers conn line :turn-begin 8))
     :standing-verdict   (when st (or (#{:red :green} (:status st)) :recorded))
     :last-done-failures (get-in (db/last-marker conn line :done) [:findings :failures])}))

(defn- bundle!
  "The ask's orientation bundle for project `dir`: the `:orient/bundle` read
  performed over the project's reader, exactly as the mounted /bundle endpoint
  performs it — the prompt hook and a consumer of the API get one answer. Calls
  the performer directly rather than over HTTP: the reader is the only handle it
  needs, and there is no per-project context to route through now that the
  daemon serves every project from one."
  [dir ask sid cli?]
  (api.reads/orient-bundle! (:reader (api! dir)) ask sid cli?))

(defn- prompt-hook!
  "The prompt hook's answer for project `dir`: the ask into the mailbox
  (unless it is a system continuation), then the map — the orientation
  bundle for the ask and the tail lines — as one text. A project nobody
  opened gets nothing written and nothing said: a read never opens one,
  and its MCP server attaches it before the first ask arrives."
  [dir {:keys [session_id prompt]} cli?]
  (let [sid    (not-empty (str session_id))
        system (hooks/system-turn? prompt)]
    (if-not (and (store-file? dir) (get-in @state [:projects dir]))
      ""
      (do (when-not system (mailbox! dir sid prompt))
          (let [reader (:reader (api! dir))
                bundle (when-not system
                         (bundle! dir (subs (str prompt) 0 (min 2000 (count (str prompt)))) sid cli?))]
            (hooks/prompt-answer bundle (hooks/tail-context (tail-data reader sid))))))))

(defn- bash-hook!
  "The Bash hook's answer: the verdict on the command, with an advisory
  held by a per-project, per-session cooldown so a session is told once
  per half hour. Nothing outside a project with a store."
  [dir {:keys [hook_event_name session_id tool_input]}]
  (if-not (store-file? dir)
    ""
    (let [v (hooks/bash-verdict hook_event_name (:command tool_input))]
      (or (when (:smell v)
            (let [now (System/currentTimeMillis)
                  [fire? cools] (hooks/cooldown (get-in @state [:hook-cooldowns dir]) session_id (:smell v) now)]
              (swap! state assoc-in [:hook-cooldowns dir] cools)
              (when fire? (hooks/hook-json hook_event_name v))))
          (when (:verdict v) (hooks/hook-json hook_event_name v))
          ""))))

(def ^:private hook-contract
  "What Claude Code hands a hook on stdin — the fields slopp reads; the rest
  ride along unread. The project is named by X-Slopp-Dir, not a path slug."
  [:map
   [:hook {:doc "Claude Code's hook payload, as it came on stdin; its shape is the harness's and stays open here"}
    [:map
     [:hook_event_name {:doc "UserPromptSubmit, PreToolUse, PostToolUse, Stop, …"} :string]
     [:session_id {:optional true :doc "the harness session — the agent's thread id"} :string]
     [:prompt {:optional true :doc "the verbatim ask, on UserPromptSubmit"} :string]
     [:tool_input {:optional true :doc "the tool's input, on PreToolUse/PostToolUse; `command` for Bash"} [:map [:command {:optional true :doc "the shell command the Bash tool ran or is about to run"} :string]]]]]])

(defn reserve-decision
  "What the app-refresh poll does for one served project, given the
  data-version and main head it was LAST served at and the pair read now.
  Pure — the effectful poll injects the two current values.

  - `:none` — nothing has committed since (data-version unchanged): skip, the
    cheap common case, and the poll does not even read the head.
  - `:touch` — something committed but MAIN did not move (a thread /
    mini-journal write, a git pin, the trace map): record the new version, do
    NOT re-serve.
  - `:reserve` — main advanced (a landing, by any writer on the shared
    store): re-serve the app and record both."
  [served-version served-head version head]
  (cond
    (= version served-version)          :none
    (and head (not= head served-head))  :reserve
    :else                               :touch))

^:unsafe (defn ^:export refresh-served-apps!
  "Re-serve every managed dev instance whose MAIN line has advanced since it
  was last served — regardless of WHO landed the done: a co-tenant session, a
  daemon in another process, or one on another machine sharing this store.

  Polled efficiently: SQLite has no cross-process push, so this gates on
  `PRAGMA data_version` (`db/data-version`) — a microsecond in-memory counter
  the engine bumps whenever ANOTHER connection commits — and only reads the
  line head, and only re-serves, when that moved. The store-READ side already
  absorbs foreign commits; [[slopp.mcp/reserve-owner!]] brings the served
  APP with it. Never throws; a project whose store has not moved costs one
  pragma read."
  []
  (doseq [[dir p] (:projects @state)
          :let [reader (get-in p [:api :reader])]
          :when (and reader (some-> reader deref :app-server))]
    (try
      (let [conn (:db @reader)
            dv   (db/data-version conn)]
        (when (not= dv (:served-version p))
          (let [head (db/line-head conn (engine/session-line reader))]
            (case (reserve-decision (:served-version p) (:served-head p) dv head)
              :reserve (do (mcp/reserve-owner! reader)
                           (swap! state update-in [:projects dir] assoc
                                  :served-head head :served-version dv))
              (swap! state assoc-in [:projects dir :served-version] dv)))))
      (catch Throwable _ nil))))

(defn- own-store!
  "slopp's OWN store value when this daemon booted from a slopp checkout — the
  self-host loop, where `serving-opts` DERIVES its surface from the same
  capabilities every app's dev server does — or nil, when a released jar booted
  from a neutral dir and serves its BAKED surface from the classpath and the
  loaded image instead. Guarded on the store actually being slopp's (it declares
  `slopp-server.process`) so a jar started inside some OTHER project's dir does not
  derive this daemon's surface from that project's web namespaces. Reached
  through the daemon's own reader ([[own-reader!]]), synced with the journal on
  each read, so a page or mount a `done` added is served on the next assembly."
  []
  (let [dir (try (:dir ((store/late-ref 'slopp.kernel.boot/current-boot-info)))
                 (catch Throwable _ nil))]
    (when (and dir (store-file? dir))
      (let [st (:store @(own-reader! dir))]
        (when (contains? (:namespaces st) 'slopp-server.process)
          st)))))

(defn- serving-opts
  "Everything the daemon serves, as the opts `slopp.http/context` and
  `slopp.http/serve!` both take — ONE assembly for the whole server: the
  daemon's management endpoints, the UI shell, AND every project's typed read
  API, which is now first-class here rather than delegated into a per-project
  context. A db-scoped endpoint carries `:http/resolve {:session
  [:project/reader [:path-params :slug]]}`, and the `:open-reader` fn in the
  perform-ctx is what turns that slug into a reader — so
  `/api/projects/<slug>/<resource>` is a normal route, not a mount.

  The namespaces are `serving-namespaces` over slopp's own store (the union of
  everything that declares an endpoint and every read/effect performer); the
  mount, bundle url, page routes and whether contracts are validated all DERIVE
  from slopp's own capabilities the way `slopp.webdev.live/serve-plan` derives
  them for every managed dev instance.

  Reads slopp's own store when it booted from a checkout ([[own-store!]]); falls
  back to the BAKED surface — spelled namespaces (the daemon, the UI, and the
  read API), the classpath mount, the image page scan — for the storeless
  neutral-dir boot a released jar makes.

  One map for the listener and for a test driving the context without a port,
  so the two cannot disagree."
  []
  (if-let [st (own-store!)]
    {:http/namespaces   (rules.http/serving-namespaces st)
     :http/routes       (static/mount-routes (rules.http/static-mounts st) (asset-reader))
     :webapp/bundle     (rules.http/bundle-url st)
     :webapp/base       ""
     :webapp/routes     (mapv (juxt :path :page) (rules.webapp/page-routes st))
     :http/perform-ctx  {:open-reader (fn [slug] (some-> (project-by-slug slug) :dir api! :reader))}
     :http/wrap-context (when (capabilities/enabled? st "rest") slopp.rest/validating)}
    {:http/namespaces   ['slopp-server.process 'slopp-server.ui.shell 'slopp-server.ui.styles
                         'slopp-server.api.endpoints 'slopp-server.api.reads]
     :http/routes       (static/mount-routes asset-mounts (asset-reader))
     :webapp/bundle     bundle-url
     :webapp/base       ""
     :webapp/routes     (cljnx/marked-pages)
     :http/perform-ctx  {:open-reader (fn [slug] (some-> (project-by-slug slug) :dir api! :reader))}
     :http/wrap-context slopp.rest/validating}))

(defn ^:export context
  "The assembled dispatch context for every route under `/api/` — what the
  listener serves, and what a test drives without a port."
  []
  (slopp.http/context (serving-opts)))

(defn- configured-port!
  "slopp's own listen port from the `http.port` capability — read from its store
  when this daemon booted from a slopp checkout ([[own-store!]]), else the baked
  [[default-port]] for the storeless neutral-dir boot a released jar makes. The
  base [[daemon-port]] rests at; the argument, `SLOPP_PORT` and the manager's
  `slopp.app-port` still override it. Reading `http.port` here is what turns the
  capability from an inert hand-kept duplicate of the port into the one place a
  slopp checkout declares it."
  []
  (or (when-let [st (own-store!)]
        (capabilities/effective st "http.port"))
      default-port))

(defn- boot-app-on-first-open!
  "Start the project's app server, backgrounded, when `first?` — this call
  is the one that opened the project — and the store runs one at all. ONE
  place for both doors: `attach!` (an MCP session) and `cli-session!` (the
  write door, which the prompt hook and every `slopp <op>` go through). The
  first open is decided by `ensure-project!` exactly once per project, so a
  door that opened without booting left the OTHER door's later open no
  longer first — a project the hook reached before the MCP attach never got
  its dev instance until some `done` refreshed it.

  The project may have CLOSED while the boot ran — its last detach stopped
  \"the server the reader holds\", which was nil until this returned.
  Nothing else will ever stop this child."
  [dir session owner first?]
  (when (and first? (mcp/app-managed? session))
    (future
      (mcp/start-app! @owner)
      (when-not (get-in @state [:projects dir])
        (mcp/stop-app! @owner)))))

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
  [dir slug & {:keys [sid agent]}]
  (locking state
    (let [now     (System/currentTimeMillis)
          sid     (or (not-empty (str sid)) (str (java.util.UUID/randomUUID)))
          [proj first?] (ensure-project! dir slug)
          owner   (delay (:reader (api! dir)))
          session (external/open! {:slopp.ops/dir         dir
                                   :slopp.ops/lazy-image? true
                                   ;; the session's own label; the THREAD an
                                   ;; agent writes on is what it passes
                                   :slopp.ops/agent-id    (str "mcp-" (subs sid 0 (min 8 (count sid))))})]
      (swap! session assoc :require-turns? true :daemon? true
             ;; this process may BE the store's dev instance (the manager told
             ;; it `slopp.managed-for` this dir); the brief says so rather than
             ;; reporting the app it embodies as not running
             :dev-instance-of (when (live/managed-child-of? dir) dir)
             :app-owner owner
             :op-cards mcp/op-cards
             :api-url (api-url proj)
             :pages-url (pages-url proj)
             :check-queue (:check-queue proj)
             :image-permit image-permit
             :on-landed (fn [land] (announce-landing! dir sid land)))
      (swap! state #(-> %
                        (update-in [:projects dir :sessions] (fnil conj #{}) sid)
                        (assoc-in [:sessions sid] {:dir dir :session session
                                                   :started now :last-seen now
                                                   ;; the harness session this MCP session belongs
                                                   ;; to, from X-Slopp-Agent — what its exit ends
                                                   :agent (not-empty (str agent))})))
            (boot-app-on-first-open! dir session owner first?)
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
          [proj first?] (ensure-project! dir slug)]
      (if-let [s (get-in @state [:projects dir :cli :session])]
        (do (swap! state assoc-in [:projects dir :cli :last-seen] now)
            s)
        (let [owner   (delay (:reader (api! dir)))
              session (external/open! {:slopp.ops/dir         dir
                                       :slopp.ops/lazy-image? true
                                       :slopp.ops/agent-id    (str "cli-" (subs (str (java.util.UUID/randomUUID)) 0 8))})]
          (swap! session assoc :require-turns? true :daemon? true
                 :dev-instance-of (when (live/managed-child-of? dir) dir)
                 :call-token (token)
                 :app-owner owner
                 :op-cards mcp/op-cards
                 :api-url (api-url proj)
                 :pages-url (pages-url proj)
                 :check-queue (:check-queue proj)
                 :image-permit image-permit
                 :on-landed (fn [land] (announce-landing! dir :cli land)))
          (swap! state assoc-in [:projects dir :cli] {:session session :last-seen now})
          ;; the write door is a first open as often as an MCP attach is — the
          ;; prompt hook reaches the project before the plugin's pipe does
          (boot-app-on-first-open! dir session owner first?)
          session)))))

(defn- door!
  "The write door's checks, then the call: the token BEFORE any work (a
  403 that had opened the project and minted a CLI session for it left
  both held for ten minutes on a dir nobody authenticated for), then a
  WRITE naming no thread refused as an ANSWER — 200 with `isError`, the
  shape every refusal on this door has — because it would land on the CLI
  session's own line, which nothing a shell knows about ever lands. `b` is
  `{tool arguments token agent}`; the answer is [[slopp.mcp/http-call!]]'s."
  [req dir b]
  (let [raw  (fn [status m]
               {:status status :http/raw true
                :headers {"Content-Type" "application/json"}
                :body (json/generate-string m)})
        tool (:tool b)
        args (or (:arguments b) {})]
    (cond
      (not= (str (:token b)) (str (token)))
      (raw 403 {:error "bad or missing token — read it from ~/.slopp/daemon.json"})

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

^:unsafe (defn ^{:http/method :post :rest/path "/api/call" :http/auth :public
                 :rest/request call-contract
                 :rest/response :any
                 :rest/unconstrained-ok "the op's own answer as the CLI prints it: {isError text}"}
  call-endpoint
  "`POST /api/call` — the write door: `{tool arguments token}` runs on the
  project named by `X-Slopp-Dir`, on its CLI session. The token is the
  server's and [[slopp-server.mcp/http-call!]] checks it. A WRITE naming no
  thread is refused here with the same words a one-shot uses, because the
  alternative is the same stranding: the call would land on the CLI session's
  own line, which nothing a shell knows about ever lands. Refused as an
  ANSWER — 200 with `isError`, the shape every refusal on this door has —
  because a 4xx reads to the CLI as a failed route, and it falls back to
  booting a one-shot JVM that refuses again after loading the whole store
  (the first end-to-end run did exactly that)."
  [req]
  (let [{:keys [dir error]} (project-dir req)]
    (if error
      {:status 400 :http/raw true
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error error})}
      (door! req dir (or (:body req) {})))))

^:unsafe (defn ^{:http/method :post :rest/path "/api/cli" :http/auth :public
                 :rest/request :string
                 :rest/media-type "text/plain"
                 :rest/response :string}
  cli-endpoint
  "`POST /api/cli` — `slopp <op>` from a shell: the TEXT frame
  [[slopp-server.process.hooks/cli-frame]] reads (header lines, a blank line,
  the payload — the op's arguments as JSON or EDN, or a verb's raw source
  blob), the token in `X-Slopp-Token`, the project by `X-Slopp-Dir`. It is
  [[call-endpoint]] for a client that builds no JSON and prints what it gets:
  the op's answer as text, 200 when it answered, 409 when it refused, 400 for
  a frame that is not a call, 403 without the token."
  [req]
  (let [{:keys [dir error]} (project-dir req)
        call (hooks/cli-call (hooks/cli-frame (:body req)))]
    (cond
      error         (text 400 error)
      (:error call) (text 400 (:error call))
      :else
      (let [r (door! req dir (assoc call :token (get-in req [:headers "x-slopp-token"])))
            b (try (json/parse-string (:body r) true) (catch Exception _ nil))]
        (if (= 200 (:status r))
          (text (if (:isError b) 409 200) (:text b))
          (text (:status r) (or (:error b) (:body r))))))))

(defn ^:export child-agents!
  "How many agents are attached to project `p`'s dev instance — the sessions
  the CHILD holds, which the manager cannot see any other way: a session on
  the dev instance is not an attachment to the manager, and closing the
  project on the manager's own count is what stopped a dev instance with
  four agents on it. Asked over the child's own `/api/status`, one local
  request on a short timeout; anything but a JSON answer carrying
  `:sessions` — an app that is not a slopp daemon, a child mid-boot, no app
  at all — counts as none."
  [p]
  (or (when-let [url (some-> (get-in p [:api :reader]) deref :app-server :url)]
        (try
          (let [r (http.client/request
                   {:http/method :get
                    :http/url (str (str/replace (str url) #"/+$" "") "/api/status")
                    :http/timeout-ms 1500})]
            (when (= 200 (:http/status r))
              (let [n (:sessions (json/parse-string (str (:http/body r)) true))]
                (when (number? n) (long n)))))
          (catch Throwable _ nil)))
      0))

(defn- held?
  "Whether project `p` is in use by an agent anywhere: an MCP session here,
  the hooks' CLI session, or one of the `agents` its own dev instance holds
  (the caller asks the child, [[child-agents!]]). A SERVING app by itself is
  not a hold any more — the 2026-09-17 rule that made it one kept a browser's
  project open forever after its agents left, and was applied on the CLI-reap
  path only, so the MCP-reap path still closed a project whose dev instance
  held agents. What holds a project is agents; when the last one is gone the
  app, the reader and every oracle go with it."
  [p agents]
  (boolean (or (seq (:sessions p)) (:cli p) (pos? (or agents 0)))))

(defn ^:export detach!
  "End MCP session `sid`: its slopp session closes (image reaped, connection
  released), and when no agent holds the project any more — no other MCP session,
  no CLI session, none on its dev instance ([[held?]]) — the project closes too: it leaves the registry and
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
                        (update-in [:projects dir :sessions] (fnil disj #{}) sid)))
      (try (ops/close! session) (catch Throwable _ nil))
      (let [p (get-in @state [:projects dir])]
        (if (held? p (child-agents! p))
          {:ended sid}
          (merge {:ended sid} (close-project! dir)))))))

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
    (when-let [r (:own-reader @state)]
      (try (ops/close! r) (catch Throwable _ nil)))
    (when-let [srv (:server @state)]
      (try (slopp.http/stop! srv) (catch Throwable _ nil)))
    (swap! state (constantly {:projects {} :sessions {} :server nil :token nil :reaper nil
                              :otel {:routed 0 :dropped 0} :otel-dirs {}}))
    nil))

(defn ^:export reap-idle!
  "Close what nothing has used: CLI sessions idle past [[cli-idle-ms]], MCP
  sessions idle past [[session-idle-ms]], and every project no agent holds
  any more ([[held?]]) — here, through its hooks' CLI session, or on its own
  dev instance, which the manager asks. The BACKSTOP: an agent's exit ends
  its sessions the moment the SessionEnd hook arrives ([[end-agent!]]), so
  what idles out here is an agent that crashed or a hook that never fired.
  A serving app by itself holds nothing — the rule that said otherwise kept
  a browser's project open forever after its agents left, and it was applied
  on the CLI path only, so an idle MCP session's detach still stopped a dev
  instance with agents attached to it. `now` is a parameter so a test can be
  the clock. Answers what it closed."
  [now]
  (locking state
    (let [st        @state
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
                              :when (not (held? p (child-agents! p)))]
                          (close-project! dir)))]
        {:sessions (mapv :ended ended)
         :cli      (vec stale-cli)
         :projects (mapv :closed (concat (filter :closed ended) closed))}))))

(defn- mcp-doors
  "This server's doors, lent to the MCP envelope: sessions by id, attach by
  the dir a request names (`X-Slopp-Dir`), detach. No path slug: the door is
  slug-free and the project's display name is the dir's basename."
  []
  {:slopp.mcp.http/lookup  lookup!
   :slopp.mcp.http/attach! (fn [req]
                             (let [{:keys [dir error]} (project-dir req)]
                               (if error
                                 {:error error}
                                 (attach! dir nil
                                          :sid   (get-in req [:headers "mcp-session-id"])
                                          :agent (get-in req [:headers "x-slopp-agent"])))))
   :slopp.mcp.http/detach! detach!})

^:unsafe (defn ^{:http/method :post :rest/path "/api/mcp" :http/auth :public
                 :rest/request jsonrpc-contract
                 :rest/response :any
                 :rest/unconstrained-ok "the answer is the JSON-RPC envelope's, whatever the message asked"}
  mcp-post-endpoint
  "`POST /api/mcp` — MCP over streamable HTTP: one JSON-RPC message in, its
  answer out, the session id riding a header and the project named by
  `X-Slopp-Dir`. The envelope is [[slopp-server.mcp.http/endpoint]], lent this
  server's doors."
  [req]
  (mcp.http/endpoint (mcp-doors) req))

^:unsafe (defn ^{:http/method :delete :rest/path "/api/mcp" :http/auth :public
                 :rest/response :any
                 :rest/unconstrained-ok "the envelope's own acknowledgement, or its 404"}
  mcp-delete-endpoint
  "`DELETE /api/mcp` — detach the session the header names; the last detach
  closes the project."
  [req]
  (mcp.http/endpoint (mcp-doors) req))

(defn ^:export start!
  "Bind the daemon's listener on `port` (loopback; nil = [[default-port]])
  and answer immediately: `{:url :port :token}`. Nothing is loaded until
  something attaches — binding first is what fits a client's startup
  window, which a JVM that opened a store before listening would miss. Two
  background loops start beside it: the idle reaper, and the app refresher
  ([[refresh-served-apps!]]) that keeps every managed dev instance current
  with landings by ANY writer on the shared store. A taken port throws with
  the bind diagnosis leading, as every slopp listener does."
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
                   (.setDaemon true))
          refresher (doto (Thread. ^Runnable
                                  (fn []
                                    (while (:server @state)
                                      (Thread/sleep 250)
                                      (try (refresh-served-apps!)
                                           (catch Throwable _ nil))))
                                  "slopp-daemon-refresh")
                      (.setDaemon true))]
      (swap! state assoc :server srv :reaper reaper :refresher refresher)
      (.start reaper)
      (.start refresher)
      {:url (str "http://127.0.0.1:" (:port srv) "/api/") :port (:port srv) :token (token)})))

^:unsafe (defn -main
  "Run the daemon: `slopp daemon [port]`. Normally its own code ships in the
  JAR and the directory it is launched in is a NEUTRAL working directory
  (`~/.slopp`, no project store): it serves whatever projects ATTACH, each
  with its own store, and holds no project of its own. (The self-host loop
  passes a checkout dir with `--live`, and then that store IS the code.) It
  records its address, pid and the write door's token in [[daemon-file]] —
  `~/.slopp/daemon.json`, or `daemon-<port>.json` for a non-default port —
  owner-readable only, and blocks.

  The port rests at slopp's own `http.port` capability ([[configured-port]]);
  a `slopp daemon [port]` argument, `SLOPP_PORT`, or the manager's
  `slopp.app-port` (for slopp's own dev instance) override it, in that order.

  slopp's own DEV instance is this same fn on another port (7358), declared in
  its store's dev config and run from the store by the machinery every
  project's dev instance gets: booted in a child image on first attach,
  refreshed at every done, replaced by `restart {app true}`. It records itself
  under its own file and leaves the machine's alone.

  A second daemon on the port refuses and names the live one from that
  file — after asking the OS whether that pid still runs, because a daemon
  that did not exit cleanly leaves its file behind, and naming a dead pid
  as the holder sends someone to kill the wrong thing."
  [& [port]]
  (let [{p :port err :error} (daemon-port port (System/getenv "SLOPP_PORT")
                                       (System/getProperty "slopp.app-port")
                                       (configured-port!))]
    (if err
      (do (.println System/err (str "slopp daemon: " err))
          (System/exit 2))
      (let [f (daemon-file p)]
        (try
          (let [r (start! p)]
            (spit-private! f (json/generate-string
                              {:url (:url r) :port (:port r) :token (:token r)
                               :pid (.pid (java.lang.ProcessHandle/current))
                               :started (System/currentTimeMillis)}))
            (.println System/err (str "slopp daemon: " (:url r)
                                      " (pid " (.pid (java.lang.ProcessHandle/current)) ")"
                                      " — address + token in " (str f)))
            @(promise))
          (catch clojure.lang.ExceptionInfo e
            (let [live   (try (json/parse-string (slurp f) true) (catch Exception _ nil))
                  alive? (when-let [pid (:pid live)]
                           (let [h (java.lang.ProcessHandle/of (long pid))]
                             (and (.isPresent h) (.isAlive (.get h)))))]
              (.println System/err
                        (str "slopp daemon: cannot bind port " p " — "
                             (cond
                               alive?
                               (str "a daemon is already live at " (:url live)
                                    " (pid " (:pid live) ")")

                               live
                               (str (ex-message e) ". " (str f) " names pid " (:pid live)
                                    ", which is not running — a daemon that did not exit"
                                    " cleanly — so something else holds the port")

                               :else (ex-message e))))
              (System/exit 1))))))))

(defn ^:export end-agent!
  "Agent `agent` — a harness session id — has EXITED: end every MCP session
  it held on the project at `dir`, and when no other agent's session
  remains there, its hooks' CLI session too; the project closes with the
  last hold ([[held?]]). `{:ended [sids] :closed dir}`, `:closed` only when
  the project went.

  Claude Code does not tell a daemon it left — measured on 2026-09-18: a
  one-shot agent's two MCP sessions and its CLI session sat in the registry
  after it exited, until the idle reaper's two hours — so the plugin's
  SessionEnd hook is the exit signal and this is its door. The sessions are
  the agent's because [[attach!]] recorded the harness id the `X-Slopp-Agent`
  header carried; an agent this daemon never saw ends nothing, and the
  reaper stays the backstop for one that crashed."
  [dir agent]
  (locking state
    (let [st     @state
          on-dir (filter (fn [[_ s]] (= dir (:dir s))) (:sessions st))
          mine   (map key (filter (fn [[_ s]] (= agent (:agent s))) on-dir))
          others (remove (set mine) (map key on-dir))]
      (when (and (seq mine) (empty? others))
        (when-let [s (get-in st [:projects dir :cli :session])]
          (try (ops/close! s) (catch Throwable _ nil)))
        (swap! state update-in [:projects dir] dissoc :cli))
      (let [rs (mapv detach! mine)]
        (cond-> {:ended (mapv :ended rs)}
          (some :closed rs) (assoc :closed (some :closed rs)))))))

^:unsafe (defn ^{:http/method :post :rest/path "/api/hook" :http/auth :public
                 :rest/request hook-contract
                 :rest/media-type "text/plain"
                 :rest/response :string}
  hook-endpoint
  "`POST /api/hook` — the plugin's hooks, one door: the Claude Code hook
  payload in, what the hook prints out, as text; the project by `X-Slopp-Dir`.
  On `UserPromptSubmit` the ask is recorded and the map returned; on
  `PreToolUse`/`PostToolUse` the Bash verdict, as the hook's JSON or
  nothing; on `Stop` the session-pause `done` on the agent's thread — a
  write, so it needs the server's token in `X-Slopp-Token` like every
  write from a shell; on `SessionEnd` the agent's sessions are ended
  ([[end-agent!]]), under the same token, and the last agent out closes the
  project. `X-Slopp-Cli: 1` asks for the CLI voice in the map.
  Any other event is an empty answer. The rules are
  [[slopp-server.process.hooks]]'s; the shell side has none to keep in step."
  [req]
  (let [{:keys [dir error]} (project-dir req)
        b     (or (:hook (:body req)) {})
        event (:hook_event_name b)
        sid   (not-empty (str (:session_id b)))]
    (cond
      error (text 400 error)

      (= "UserPromptSubmit" event)
      (text 200 (prompt-hook! dir b (= "1" (get-in req [:headers "x-slopp-cli"]))))

      (#{"PreToolUse" "PostToolUse"} event)
      (text 200 (bash-hook! dir b))

      (= "Stop" event)
      (cond
        (not (store-file? dir)) (text 200 "")
        (not= (str (get-in req [:headers "x-slopp-token"])) (str (token)))
        (text 403 "bad or missing token — read it from ~/.slopp/daemon.json")
        :else (do (door! req dir {:tool "done" :token (token)
                                  :arguments {:agent sid :thread sid :label "session pause"}})
                  (text 200 "")))

      ;; the agent EXITED. Ending what it held is a state change, so it needs
      ;; the token like the Stop hook's done; the hook script always sends it
      (= "SessionEnd" event)
      (if (not= (str (get-in req [:headers "x-slopp-token"])) (str (token)))
        (text 403 "bad or missing token — read it from ~/.slopp/daemon.json")
        (do (when sid (end-agent! dir sid))
            (text 200 "")))

      :else (text 200 ""))))
