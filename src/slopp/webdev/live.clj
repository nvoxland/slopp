(ns slopp.webdev.live
  "The app server slopp runs FOR you, so a project under development always
  has a live version up.

  An app should not have to hold a `serve!` call, a namespace list, or a port
  to be reachable while it is being written. Everything needed is already in
  the store — `http.enabled` says it is a web project, the endpoint and
  performer surface says what to serve, the capability registry says where.
  This namespace turns those into a launch, and keeps it current.

  **The deciding is separate from the running.** `serve-plan` is pure data
  from the store, so what a project would serve is answerable without
  spawning anything; the lifecycle spawns. That split is why the interesting
  rules here have ordinary in-image tests instead of needing a JVM apiece.

  **The app gets its OWN image, not the oracle's.** `session/fresh-image!` is
  on the path of `edit-replace!`, `rename!`, `move-forms!`, `deps-add!` and
  `merge-into-session!`, so an app served from the oracle would be killed by
  a refactor that never touched it. The two want opposite things — the oracle
  CURRENT and disposable, the app STABLE and pinned — and the oracle's
  requirement is the one that cannot move. A dedicated image also runs on the
  store's OWN dependency manifest rather than slopp's, which is what makes
  the dev server a check on the published surface rather than only a
  convenience.

  Serves at DONE grain, not per write: mid-episode the store is intentionally
  incomplete, and reloading a browser into a red half-written state trains
  the author to ignore it."
  (:require [slopp.project.capabilities :as capabilities]
            [slopp.rules.http :as rules.http] [slopp.store :as store] [slopp.ops.engine :as engine] [slopp.image :as image] [slopp.image.repl :as repl] [clojure.string :as str] [clojure.java.io :as io] [slopp.store.artifacts :as artifacts] [slopp.http :as slopp.http] [slopp.project.dev :as dev] [slopp.currency :as currency] [slopp.kernel.boot :as boot] [slopp.store.render :as store.render] [slopp.rules.webapp :as rules.webapp]))

(defn derived-port
  "A localhost port DERIVED from the store dir for this project's APP server —
  stable across restarts, different for every project on the machine.

  SALTED from when three derivations of this shape shared one process and
  had to disagree; the git listener's and the per-session reviewer
  listener's both went with their listeners (the daemon binds ONE configured
  port for everything slopp itself serves), so this is the derivation that
  remains. The salt stays: changing the formula moves every project's app
  address, and a developer keeps the tab.

  A PREFERENCE that can be refused. Nobody remembers an address for a
  reviewer API, but a developer types the app's into a browser and keeps
  it — so a taken port is REPORTED (`start!` says so, and `http.port` is the
  fix, named in the message) rather than answered with a url that moves
  each time.

  The realistic collision is with our OWN previous server, which `refresh!`
  handles by stopping it before binding. A foreign holder is rare, and a
  ladder — explicit, configured, derived, ephemeral — is not built here
  until something is actually colliding.

  The reported url always carries the port actually BOUND, which is what
  `serve!` hands back, not what was asked for."
  [dir]
  (+ 49152 (mod (hash (str "slopp-app:" dir)) 16384)))

(defn load-order
  "The store namespaces to load into the app image, dependencies first.

  The transitive closure of the web surface over the store's require graph
  — NOT the whole store. The app image exists to run the app: loading
  everything would make its boot cost grow with the codebase and would put
  code in a serving process that nothing serving can reach.

  Dependency order is not a nicety here. A store namespace has no classpath
  presence, so a dependent loaded first would `:require` its way out to the
  classpath and fail (`image/load-ns!` marks `*loaded-libs*` for exactly this
  reason).

  **`slopp.http` is seeded when the STORE holds it.** slopp's own store does;
  an ordinary app gets the framework from its declared `slopp-web` coord,
  already on the child's classpath. Both must work without the app saying
  which, so this asks the store rather than requiring an answer — and its
  absence is a fact, not an error."
  [store]
  (let [builder (rules.http/context-builder store)
        seeds   (cond-> (set (rules.http/serving-namespaces store))
                  (contains? (:namespaces store) 'slopp.http) (conj 'slopp.http)
                  ;; the context builder is NOT part of the served surface —
                  ;; it declares no route and performs no kind — so nothing
                  ;; else pulls its namespace in, and the generated call would
                  ;; require its way out to a classpath the child lacks
                  builder (conj (symbol (namespace builder))))
        ;; and every DECLARED entry, for exactly the builder's reason: an
        ;; entry point declares no route and performs no kind, so nothing in
        ;; the served surface reaches it and the generated call would require
        ;; its way out to a classpath the child does not have. Measured — the
        ;; first declared entry that crossed the wire failed with "Could not
        ;; locate worker/core__init.class", and every pure test above it was
        ;; green.
        seeds   (into seeds
                      (map (comp symbol namespace :main val))
                      (dev/runnables store))
        want    (into #{} (mapcat #(store/ns-closure store %)) seeds)]
    (filterv want (store/ns-dependency-order store))))

(defn serve-code
  "The expression the app image evaluates to start serving `plan`, as a
  STRING — it crosses an nREPL wire, which carries text.

  This is where the directive actually lands: the app writes no `serve!`
  call, so slopp writes it, from the plan. Generating it rather than asking
  the app for it is what makes `serve-plan`'s derivations binding — a
  hand-written call could disagree with them, and the running server would be
  the one that disagreed.

  **It must carry what the app NEEDS, not only where to serve.** The first
  cut generated four of `serve!`'s options — namespaces, host, port, adapter
  — and dropped `:http/perform-ctx`, `:http/auth-config`, `:http/routes` and
  `:http/max-body-bytes`, which is every option describing the application
  rather than its address. Measured on a real app: handlers taking
  `:http/deps` received nil, which either 500s (loud) or answers 200 with an
  empty body (silent, and worse — a client generated against that surface
  comes back with zero endpoints and looks successful).

  `:http/perform-ctx` is a CALL to the app's `^{:http/context true}` builder,
  which is why the opts can no longer be one flat quoted map: the address
  fields stay quoted (a namespace symbol in evaluated position is read as a
  class name) and the context is evaluated.

  **The context is built ONCE per app image — and the app image is REPLACED
  at every refresh.** So state accumulated in it does not survive a `done`:
  an atom the builder creates is a new atom each time, and an app that keeps
  a registry there will find it empty after any done point, silently. Say so
  to anyone building one; the alternative — hot-loading a refresh into the
  RUNNING image instead of booting a new one — is the change that would fix
  it, and it is not made yet.

  `:http/routes` USED to be dropped for a reason that sounded structural —
  static mounts need the store's bytes and the child image has no store. It
  was not structural: `mount-routes` takes a READER, so materializing the
  bytes and handing the child a file reader serves them without the child
  ever seeing a store. That is what `materialize-static!` does, and it is why
  a UI's stylesheet and cljs bundle now reach a managed server.

  **`:http/auth-config` is still dropped, and it is the last one.** An app
  with auth gets a managed server on which identity does not resolve. There
  is no switch to turn the managed server off — [[managed?]] is derived — so
  this is a gap to close, not a case to configure around.

  **It evaluates to the BOUND port — an integer.** `repl/eval!` hands a throw
  back as a STRING, so an integer is unambiguous evidence a socket is open;
  anything else is the failure, in its own representation."
  [plan]
  (let [builder (:context-builder plan)
        mounts  (not-empty (:static plan))
        opts    (cond-> {:http/namespaces (list 'quote (vec (:namespaces plan)))
                         :http/host       (:host plan)
                         :http/port       (:port plan)
                         :http/adapter    (:adapter plan)}
                  (:max-body-bytes plan)
                  (assoc :http/max-body-bytes (:max-body-bytes plan))
                  ;; a shell declares THAT it is one; this is the url it
                  ;; injects. Dropping it would make every app with a shell
                  ;; refuse to assemble on the managed server — the loud
                  ;; failure, but a failure the dev server would own
                  (:bundle plan)
                  (assoc :webapp/bundle (:bundle plan))
                  ;; where `rest.enabled` stops being a line in a config file.
                  ;; The app writes no serve! call, so the capability switch has
                  ;; to reach the GENERATED one or the boundary exists and
                  ;; nothing ever invokes it.
                  ;;
                  ;; A symbol rather than the validators inline: the plan stays
                  ;; data, and the child resolves it — which is why the require
                  ;; below is not optional.
                  (:validate? plan)
                  (assoc :http/wrap-context 'slopp.rest/validating)
                  builder
                  (assoc :http/perform-ctx (list builder))
                  mounts
                  (assoc :http/routes
                         (list 'slopp.http.static/mount-routes
                               mounts
                               (list 'slopp.http.static/file-or-resource-reader
                                     (:static-dir plan))))
                  ;; the client route table, QUOTED — page symbols are
                  ;; addresses here, not calls; dispatch only matches the
                  ;; patterns to derive a shell's status (200 on a routed
                  ;; address, 404 with the same shell bytes on one the
                  ;; client table does not know)
                  (seq (:page-routes plan))
                  (assoc :webapp/routes
                         (list 'quote (mapv vec (:page-routes plan)))))]
    (pr-str (list* 'do
                   (list 'require ''slopp.http)
                   (concat
                    (when mounts
                      [(list 'require ''slopp.http.static)])
                    ;; the child resolves slopp.rest/validating only if it
                    ;; REQUIRED the namespace. A qualified symbol in the opts
                    ;; would look right and throw at serve time — the same trap
                    ;; the static mount hit, which is why both are asserted as
                    ;; require FORMS rather than as substrings.
                    (when (:validate? plan)
                      [(list 'require ''slopp.rest)])
                    (when builder
                      [(list 'require (list 'quote (symbol (namespace builder))))])
                    [(list :port (list 'slopp.http/serve! opts))])))))

(defn ^:export stop!
  "Stop a running app server — whatever `start!` returned. Idempotent, and
  safe on a `{:serving? false …}` that never had an image.

  There is exactly ONE thing to kill, and that is the point of the dedicated
  image: the listener, the loaded namespaces and the process are the same
  object, so there is no half-stopped state where a port stays bound because
  a handle was dropped. `repl/stop!` already tolerates a partially-built
  handle and destroys the process before touching the transport."
  [running]
  (when-let [img (:image running)]
    (repl/stop! img))
  nil)

(def ^:export unserved-options
  "Options `slopp.http/serve!` and `slopp.http/context` accept that the generated
  call deliberately does NOT carry, and why each is missing.

  This exists so the gap is CLASSIFIED rather than merely absent, and
  `live-test/the-generated-serve-call-accounts-for-every-option-it-could-carry`
  holds the classification total: a new option on either function fails that
  test until it is generated or listed here. Without it `serve-code` was four
  of eight, and the four it dropped were every option describing the APP —
  found by a real app measuring a live server rather than by anything here.

  A partial classification would be worse than none, which is why each entry
  carries a REASON — the same discipline `slopp.index.crossings/internal-markers`
  follows. An entry whose reason is \"not done yet\" is a worklist item that
  cannot be lost; an entry with no reason is an omission wearing a decision's
  clothes."
  {
   :http/auth-config "not threaded yet. Nobody has hit it only because the apps
                     measuring the managed server have no auth; one that did
                     would deny everything, or fail resolving identity"
   :webapp/base      "the managed server serves at the ROOT of its own port, so
                     the mount prefix is empty and stamping it would say the
                     same thing at more length. It is not the server's fact at
                     all: an app reached through a proxy that mounts it at
                     /p/<slug> is served by THIS code at /, and only the proxy
                     knows the prefix. Threading it would mean the live server
                     accepting a claim about where someone else publishes it"})

(defn ^:export materialize-static!
  "Write every file the `mounts` cover into `out-dir` — a fresh temp dir when
  none is given — and return its path; nil when there are no mounts, so an app
  without assets allocates nothing. `store-dir` is the STORE's directory, the
  root of the on-disk artifact cache.

  The managed app image is a separate JVM with NO store, which is the whole
  reason static mounts went unserved: `mount-routes` takes a reader, and the
  only reader the child could have used wanted a store. Materializing turns
  that into the reader it CAN use, `file-or-resource-reader`, pointed at a
  dir. Paths keep their manifest shape under the dir, so one mount serves a
  tree rather than a flat list.

  **The four-arity SYNCS an existing dir, and that is what a hot refresh
  needs.** `boot!` wrote a fresh dir on the recorded assumption that the image
  is replaced at every refresh; `hot-refresh!` keeps the image — and so kept
  the dir, which is how a recompiled client bundle sat on disk for 23 hours
  while the child served the bytes it booted with. The child reads per
  request, so rewriting the files in place is the whole fix. Each file goes
  through a sibling temp and an ATOMIC move, so a request landing mid-write
  reads the old bytes or the new ones and never half of each; what the store
  no longer covers is pruned, so a deleted asset 404s instead of lingering.

  **An ARTIFACT keeps its bytes OUT OF LINE, so `store/file-content` answers
  with metadata and a nil `:content` — that is the ordinary case, not an
  edge one.** `compile_client` writes the cljs bundle as an artifact, so for
  a UI this is every asset it has: the first real app to try this had an
  EMPTY `:files` and two artifacts, and got a materialized dir containing
  nothing. `artifacts/fetch` is the accessor that reads
  `<store-dir>/.slopp/artifacts/<sha>`; the blob table is a different store
  and does not hold these.

  **`store-dir` is deliberately not called `dir` here.** The output dir is
  also a dir, and the first cut of this fetched artifacts from the temp
  directory it was writing INTO — which resolves, returns nothing, and skips
  exactly the files it was added to rescue.

  A path nothing can supply is SKIPPED rather than fatal: a missing asset is
  a 404, and a throw would take down a server serving every other path. That
  skip is why this shipped broken and silent once already, so anything that
  reaches it is worth suspecting before it is trusted."
  ([store mounts store-dir]
   (when (seq mounts)
     (materialize-static! store mounts store-dir
                          (str (java.nio.file.Files/createTempDirectory
                                "slopp-static"
                                (make-array java.nio.file.attribute.FileAttribute 0))))))
  ([store mounts store-dir out-dir]
   (when (seq mounts)
     (let [covered (vals mounts)
           root    (io/file (str out-dir))
           wanted  (into #{}
                         (for [path (concat (keys (:files store)) (keys (:artifacts store)))
                               :when (some #(str/starts-with? (str path) (str %)) covered)]
                           (str path)))]
       (doseq [path wanted
               :let  [entry   (store/file-content store path)
                      content (or (:content entry)
                                  (when store-dir
                                    (:bytes (artifacts/fetch store-dir store path))))]
               :when (some? content)]
         (let [f   (io/file root path)
               tmp (io/file root (str path ".slopp-tmp"))]
           (io/make-parents f)
           (if (bytes? content)
             (io/copy content tmp)
             (spit tmp content))
           (java.nio.file.Files/move
            (.toPath tmp) (.toPath f)
            (into-array java.nio.file.CopyOption
                        [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                         java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))
       ;; prune: a file the store no longer covers must stop being served,
       ;; or a deleted asset outlives its deletion for as long as the image
       (doseq [f (file-seq root)
               :when (.isFile ^java.io.File f)
               :let  [rel (str (.relativize (.toPath root) (.toPath ^java.io.File f)))]
               :when (not (contains? wanted rel))]
         (io/delete-file f true))
       (str root)))))

(defn- boot!
  "Bring up an app image for `store` and load its web surface into it —
  WITHOUT serving. `{:image :plan}`, or `{:reason …}` and no live process.

  Separable from serving because that is what makes a safe swap possible:
  `refresh!` must know the new version is good BEFORE it kills the one
  currently answering, and \"good\" at done grain means IT LOADS. A red
  half-written store is the ordinary mid-episode state, and it fails here,
  with the old server untouched.

  **Launched through `session/start-image!`, never `repl/start!`.** Its own
  docstring calls it \"THE door: every owned image is launched here\", and the
  door carries two things a local launch does not: `image-deps` (the store's
  manifest PLUS what the vendored framework requires) and `framework-dir!`
  (the materialized framework on the classpath). `image-with-deps!` records
  what a second door bought last time — \"a spare launched in its own dir has
  nothing vendored, and a JVM cannot pick up a relative classpath directory
  after launch\" — and this codebase has already paid for an unenumerated
  door twice.

  **Loaded with `load-ns!`, like every other image.** This used to need a
  second, non-stamping loader: the currency record was one process-global atom
  describing THE ORACLE, so stamping this image's loads into it would report
  forms as current in a process the oracle never saw. The record is now the
  image's own, so stamping here records what THIS image holds — which is a
  question worth being able to ask of an app server, and was previously
  unanswerable.

  The image is stopped on every failing path: a child JVM that outlives the
  attempt to use it is the worst of both outcomes."
  [session store plan]
  (let [t0  (System/nanoTime)
        img (engine/start-image! session store)]
    (try
      (if-let [err (first (keep (fn [n]
                                  (when-let [e (image/load-ns! img store n)]
                                    (str n ": " e)))
                                (load-order store)))]
        (do (repl/stop! img)
            {:reason (str "the app image could not load " err)})
        ;; the bytes are materialized HERE because this is the last place that
        ;; holds the store — `serve-in!` has only the plan, and the child
        ;; image has neither. A fresh dir per boot rather than a cached one:
        ;; the image is replaced at every refresh, so assets that changed
        ;; mid-episode would otherwise serve stale from a dir nobody rewrote.
        {:image img
         ;; WHAT THIS IMAGE HOLDS, so a later refresh can push only what moved
         ;; instead of replacing the JVM. Without it there is nothing to diff
         ;; against and the only safe answer is a full re-boot — which is what
         ;; loses the app's context state at every done.
         :loaded (into {} (map (juxt identity #(store.render/render-ns store %)))
                       (load-order store))
         :plan  (assoc plan :static-dir (materialize-static! store (:static plan) (:dir @session)))
         :boot-ms (quot (- (System/nanoTime) t0) 1000000)
         ;; the HEAD DELTA's timestamp, not the wall clock: a boot takes
         ;; seconds, and anything written during it is not in this image —
         ;; stamping "now" would count those as already served
         :served-at (:head-at store)})
      (catch Throwable t
        (repl/stop! img)
        {:reason (str "the app image did not come up: " (ex-message t))}))))

(defn ^:export self-served?
  "Whether the calling process ALREADY serves everything `store` would —
  `already-served` being the namespaces it has mounted itself.

  The one true case for not managing a store's app server, and the reason
  it is derived rather than declared. Managing this store would boot a
  second image and serve a SNAPSHOT of the very surface you are looking at,
  one done point behind the page in front of you — not a port conflict, a
  staler copy. slopp's own store is that case: its web surface is the
  reviewer API the live session already serves, over the LIVE store.

  It replaced the `dev.server` capability, which asked every project a
  question only one store on earth should answer. That misfired exactly as
  a footgun does: the second project to meet it set it false because its
  static ASSETS were 404ing, and the switch then presented a bug as a
  preference for a week. Computing it means a project cannot answer wrong,
  and cannot use the answer to paper over something else.

  **A store serving NOTHING is not self-served**, though every one of its
  zero namespaces is trivially already served. Vacuous truth here would
  exempt every non-web project for a reason that has nothing to do with
  them — the emptiness guard, in the position where it actually bites."
  [store already-served]
  (let [nses (set (rules.http/serving-namespaces store))]
    (boolean (and (seq nses)
                  (every? (set already-served) nses)))))

(defn ^:export managed?
  "Whether slopp should run this store's dev instance while someone works on
  it — `already-served` being what the calling process has mounted itself.

  Two reasons, either sufficient. A DECLARED entry (`run.<name>.main` in
  the dev config, enabled) is the project saying what its dev instance is
  — a worker, a daemon, a main — and that is reason enough whatever its
  HTTP surface: `serve-plan` already knew so, and the gate in front of it
  did not, so such a store was never started at all. Otherwise
  `http.enabled` says the project SERVES HTTP — that is what makes the web
  rules and `query_surface` exist, and production reads it — unless this
  process already serves that surface itself ([[self-served?]]): a store
  whose surface this process already serves must not get a second, staler
  copy of it.

  **Nothing a web project configures decides this any more, and that is the
  point.** A project under development should not have to think about
  turning its server on, or keeping it current — those are slopp's job, and
  a per-project switch is an invitation to answer a question nobody should
  be asked. The evidence it was one: the only adopter that ever set the
  switch set it to work around 404ing assets, and the switch then made a
  bug look like a preference.

  Deliberately NOT folded into `serve-plan`. That answers \"what would this
  store serve, and where\", which production asks too, and a dev-only
  exemption in it would be an answer to a question it was not asked."
  [store already-served]
  (boolean (or (some (comp :enabled? val) (dev/runnables store))
               (and (capabilities/effective store "http.enabled")
                    (not (self-served? store already-served))))))

(defn- bind-failure
  "The sentence a failed bind should REPORT, given the `port` asked for and
  the `raw` failure the app image handed back.

  A failure at this point is a bind failure by construction — [[boot!]] has
  already proved the code loads — so the common case is knowable and worth
  saying plainly.

  **The diagnosis leads and the next step follows it.** What the JVM produces
  is `class java.net.BindException: Execution error (BindException) at
  sun.nio.ch.Net/bind0 (Net.java:-2).` and then, after a newline, `Address
  already in use` — the one clause that matters, arriving last, behind a class
  name, a stack frame and a `-2` line number. The reader is an agent deciding
  what to do next, and it had to parse three pieces of noise to reach the
  answer and then still did not know what was expected of it. Reported by the
  first consumer to hit this in anger.

  **The diagnosis is the framework's, the next step is this caller's.** Only
  what to DO about a taken port differs between listeners, and only this one
  can say `http.port` — `slopp.http` also serves operators who set the port some
  other way, and the UI listener's answer is a different number entirely. So
  `framework/bind-diagnosis` writes the shared half and each caller appends
  its own, rather than three listeners each recognising the failure and
  phrasing it.

  **An unmodelled failure keeps every byte.** A privileged port, an
  unresolvable host, something not thought of — a confident wrong sentence is
  worse than a verbose right one, so anything the recogniser does not know
  falls through with its raw text intact rather than being squeezed into the
  shape of the case that IS understood.

  `raw` is TEXT, not a Throwable: it crossed an nREPL wire from the child
  image, which is why the recogniser takes both representations."
  [port raw]
  (if-let [d (slopp.http/bind-diagnosis port raw)]
    (str d " — free it, or set http.port to another")
    (str "the app image would not serve on port " port ": " raw)))

(defn ^:export run-code
  "The expression the app image evaluates to start a DECLARED entry, as a
  STRING — it crosses an nREPL wire, which carries text.

  The sibling of [[serve-code]], and the difference is who decides.
  `serve-code` GENERATES a `slopp.http/serve!` call from the store, which is
  what makes the plan's derivations binding. This one calls what the project
  DECLARED, because a derived call cannot know the flags a developer wants
  and a worker is not a `serve!` call at all.

  Two things about the WIRE rather than the app:

  **The namespace is REQUIRED, as a form.** The child loads the store's
  namespaces, but a qualified symbol in evaluated position resolves only if
  its namespace is loaded — the trap `serve-code` hit twice, with
  `slopp.rest/validating` and the static mount.

  **The call runs on a DAEMON THREAD and this expression answers
  immediately.** A server's `-main` usually does not return; that is what
  makes it a server. Called inline it would wedge the nREPL reply that is
  slopp's only evidence the start happened, and a wedged wire is
  indistinguishable from a slow image. Daemon, so it cannot outlive the child
  — which already dies with its parent through the watchdog.

  **What `:started` proves, and what it does not.** It proves the namespace
  loaded and the thread spawned. It says nothing about whether the app came
  up: a declared entry that throws on its second line reports `:started` and
  is dead. That is the cost of calling an arbitrary fn instead of generating
  one whose return value is a bound socket, and it is why a declared entry's
  address is DECLARED rather than observed — slopp has nothing to observe it
  from. Whether it is HEALTHY is the currency stamp's question, not this
  expression's.

  Positional rather than a map: two fields at a module boundary would buy a
  schema and a key-naming rule for nothing."
  [main args]
  (pr-str
   (list 'do
         (list 'require (list 'quote (symbol (namespace main))))
         (list 'doto (list 'Thread. (list 'fn [] (list* main args)))
               (list '.setDaemon true)
               (list '.start))
         :started)))

(defn- enabled-runnables
  "The declared entries a reader asked to be RUNNING — `dev/runnables` minus
  the silenced ones.

  `runnables` keeps a silenced entry so it is visibly still declared; the
  PLAN is what gets launched, and launching something declared off is the one
  reading of `:enabled? false` that would be plainly wrong."
  [store]
  (into {} (filter (comp :enabled? val)) (dev/runnables store)))

(defn serve-plan
  "What to launch for this store's app server, as data — `{:enabled? :mode
  :namespaces :host :port :adapter}`, or `{:enabled? false :reason …}`.

  Pure, and separate from the launching on purpose: everything worth getting
  wrong here (is this a web project, what does it serve, on what address) is
  decidable from the store, and deciding it inside a function that also
  spawns a JVM would make it testable only by spawning one.

  `:namespaces` is DERIVED (`web/serving-namespaces`) — the app never hands
  over a list it can get wrong.

  `:port` prefers an explicitly SET `http.port` and otherwise DERIVES
  ([[derived-port]]). The registry default of 8080 stands for production,
  where a known number is the point; a dev session wants collision-freedom
  instead, because two projects on one machine both taking the default is
  not a rare case — it is the second project: a fixed default \"worked for
  exactly one project and collided for the second\".

  `:mode` is `:dev`. It rides the plan so nothing downstream reads a dev plan
  as the shipped one: the two serve the same routes from different stores at
  different grains, and an unlabelled plan is a stand-in for whichever the
  reader assumed."
  [store dir]
  (if-not (or (capabilities/effective store "http.enabled")
              (seq (enabled-runnables store)))
    {:enabled? false
     :reason (str "http.enabled is false — config_file {path \"capabilities\" "
                  "key \"http.enabled\" value \"true\"} opts this store into web."
                  " A project that is not a web project can still declare what"
                  " to run: config_file {path \"dev\" key \"run.<name>.main\""
                  " value \"my.ns/-main\"}")}
    {:enabled?   true
     :mode       :dev
     ;; what this project SAID to run, which derivation cannot know. Empty for
     ;; a store that declares nothing — which is every store that worked before
     ;; this existed, so the derived server below is unchanged for them.
     :runnables  (enabled-runnables store)
     :namespaces (rules.http/serving-namespaces store)
     :host       (capabilities/effective store "http.host")
     :port       (if (capabilities/stored? store "http.port")
                   (capabilities/effective store "http.port")
                   (derived-port dir))
     :adapter    (capabilities/effective store "http.adapter")
     ;; what the app NEEDS, not only where it answers. Dropping these is what
     ;; made a managed server 500 on any app that took slopp's own advice to
     ;; receive its dependencies as :http/deps.
     :max-body-bytes  (capabilities/effective store "http.max-body-bytes")
     :context-builder (rules.http/context-builder store)
     ;; the app's own assets. A UI's stylesheet and cljs bundle ARE the
     ;; product, so a managed server that 404s them is not a lesser version
     ;; of the app — it is an unusable one, and the project it happened to
     ;; switched the managed server off rather than reading it as a bug.
     :static          (rules.http/static-mounts store)
     ;; the url a shell injects, JOINED from the compile output and the
     ;; mounts above rather than typed on every shell route. nil when no
     ;; mount reaches the bundle, which `slopp.http/context` refuses at
     ;; assembly if anything declares itself a shell — the honest failure,
     ;; where a blank page on every route is the alternative
     :bundle          (rules.http/bundle-url store)
     ;; the CLIENT route table, derived from the same page markers the
     ;; build bakes into the browser entry — [[pattern page] …]. Without it
     ;; the managed server cannot derive a shell's status and answers 200
     ;; to every address (the compatibility default), which slopp-ui
     ;; measured on the one surface humans actually browse. Pure store
     ;; derivation; the child needs no image scan.
     :page-routes     (mapv (juxt :path :page) (rules.webapp/page-routes store))
     ;; whether the served app HONOURS its declared contracts. Read here rather
     ;; than in serve-code for the same reason every other derivation is: the
     ;; plan is what production and the dev server both answer from, and a
     ;; switch consulted at code-generation time would be a second reader of the
     ;; config that could disagree with this one.
     :validate?       (capabilities/enabled? store "rest")
     ;; WHAT IT WILL ACTUALLY SERVE, counted from the store before anything is
     ;; spawned. `serve-in!` reports health on the BIND, and a bind succeeds
     ;; whether or not anything is mounted behind it — so an app whose markers
     ;; this slopp no longer reads comes up, answers 404 to every path, and is
     ;; advertised by `session_brief` and `start-app!` as a healthy url. That is
     ;; what a consuming store experienced the day a marker family moved: the
     ;; server was up, the url was right, and nothing was behind it.
     :endpoints       (count (rules.http/endpoints store))
     ;; ...and the sentence, when it will serve NOTHING. Static-only is not
     ;; nothing: a store may legitimately serve just its assets, and calling
     ;; that empty would turn a working configuration into a warning. Both
     ;; empty is the case where the port answers and every path 404s.
     :serves-nothing  (when (and (empty? (rules.http/endpoints store))
                                 (empty? (rules.http/static-mounts store)))
                        (str "this app will bind its port and answer 404 to"
                             " EVERY path: the store declares no endpoint this"
                             " slopp can read, and no static mount. A bind"
                             " succeeds either way, so the url reported after"
                             " this is not evidence that anything is behind it."
                             " If the store was written against an older slopp,"
                             " its endpoint markers may be a retired spelling —"
                             " session_brief's :unread-declarations says so and"
                             " names the current one."))}))

(defn ^:export startup-code
  "The expressions the app image evaluates to come up, in order — a vector of
  STRINGS, because they cross an nREPL wire.

  Two answers, and which applies is a property of the PLAN:

  - **nothing declared** → one [[serve-code]], the generated
    `slopp.http/serve!` call. Unchanged for every store that predates the
    `dev` config, which is most of them.
  - **entries declared** → one [[run-code]] each, and NO generated call.

  **Declared REPLACES derived, rather than joining it.** A generated `serve!`
  running beside a declared entry would bind a port the project never asked
  for, and a reader who declared one server would have two — with the derived
  one answering at an address they were never given. A project that wants
  both says so by declaring both.

  Decided here rather than inside `serve-in!` for the reason `serve-plan`
  exists at all: everything worth getting wrong is decidable from the store,
  and deciding it inside the function that also spawns a JVM makes it
  testable only by spawning one."
  [plan]
  (if-let [declared (seq (:runnables plan))]
    (mapv (fn [[_ {:keys [main args]}]] (run-code main args))
          (sort-by key declared))
    [(serve-code plan)]))

(defn- serve-in!
  "Bring the app up inside an already-loaded app image (`boot!`'s result) and
  return the running map — `{:serving? true :image :plan …}`, or
  `{:serving? false :reason …}` with the image stopped.

  What it evaluates is [[startup-code]]'s decision, not this function's.

  **Two shapes of success, because there are two shapes of start.** A
  generated `serve!` answers the port it BOUND, and the reported `:url`
  carries that rather than the one asked for — an integer is unambiguous
  evidence a socket is open. A declared entry answers `:started`, which is
  weaker on purpose: it proves the namespace loaded and a thread spawned, and
  nothing about whether the app came up. So a declared plan reports the url
  the project DECLARED, or none, and never invents one.

  For the derived path a failure here is a BIND failure by construction —
  `boot!` already proved the code loads — so the reason is narrow enough to
  act on. For a declared entry it is whatever the entry threw on its way out
  of `require` or its first line."
  [{:keys [image plan boot-ms served-at loaded]}]
  ;; `:boot-ms` rides through rather than being measured here: hot-loading a
  ;; refresh would remove the BOOT and not the bind, so folding the two into
  ;; one number would make a contended port read as a slow image.
  (try
    (let [declared? (seq (:runnables plan))
          results   (mapv (fn [code] (first (repl/eval! image code)))
                          (startup-code plan))
          bad       (first (remove #(or (integer? %) (= :started %)) results))]
      (cond
        bad (do (repl/stop! image)
                {:serving? false :plan plan
                 :reason (bind-failure (:port plan) bad)})

        declared? (let [url (some :url (vals (:runnables plan)))]
                    (cond-> {:serving? true :image image :plan plan
                             :boot-ms boot-ms :served-at served-at :loaded loaded
                             ;; NAMES, so a reader can see which entries this
                             ;; process is carrying — there may be several and
                             ;; only one of them has an address
                             :started (vec (sort (keys (:runnables plan))))}
                      ;; only when the project DECLARED one. Absent beats a
                      ;; url nobody can be sure answers.
                      url (assoc :url url)))

        :else (let [v (first results)]
                {:serving? true :image image :plan plan :port v
                 :boot-ms boot-ms :served-at served-at :loaded loaded
                 :url (str "http://" (:host plan) ":" v "/")})))
    (catch Throwable t
      (repl/stop! image)
      {:serving? false :plan plan
       :reason (bind-failure (:port plan) (ex-message t))})))

(defn start!
  "Bring this store's app server up in a DEDICATED image and return
  `{:serving? true :image :plan :port :url}` — or `{:serving? false :reason …}`.

  `dir` is the store's directory, and it is only ever hashed (`derived-port`).

  Boot-and-load (`boot!`) then bind (`serve-in!`), which is the same pair
  `refresh!` uses in a different order. One implementation between them is
  the point: a swap that booted differently from a start would be a second
  lifecycle, and the two would drift exactly where it is hardest to notice.

  **A failure is a SENTENCE, not a throw.** Nothing the caller can do about a
  taken port is expressed by a stack trace, and this runs from the dev
  lifecycle rather than from a user's call — a throw there takes down more
  than the app server.

  **A taken port is reported, not routed around** — see `derived-port` for
  why this diverges from the UI listener, which falls back to an ephemeral
  one. Reporting keeps the decision with the caller: this returns the fact,
  and a wiring layer that wants a fallback ladder can build one on top
  without this function having an opinion baked in."
  [session store dir]
  (let [plan (serve-plan store dir)]
    (if-not (:enabled? plan)
      {:serving? false :reason (:reason plan) :plan plan}
      (let [booted (boot! session store plan)]
        (if (:reason booted)
          (assoc booted :serving? false :plan plan)
          ;; STAMPED at the start, because it cannot be asked afterwards —
          ;; see [[currency]]. nil when there is no connection or no line,
          ;; which `report` keeps as its third state rather than reading as
          ;; stale.
          (let [r (serve-in! booted)]
            (cond-> r
              (:serving? r)
              (assoc :currency (currency/of (:db @session)
                                            (:line @session))))))))))

(defn ^:export refresh!
  "Re-serve `store` on this session's app server and return the running map.
  The version that was up is stopped only once the new one has PROVED it
  loads; the result is held on the session as `:app-server`.

  Called at DONE grain, not per write. Mid-episode the store is intentionally
  incomplete — a red test written before its implementation is the normal
  state, not a fault — and reloading a browser into that shows the author a
  broken app repeatedly and trains them to ignore it. `done` is the point
  someone says \"I think this is finished\", which is exactly when they want
  to look.

  **The swap is verified on LOADING, not on binding**, and the asymmetry is
  the design. A boot that fails at done grain almost always fails because the
  code does not compile, and that is decided before a socket is involved —
  so the check that protects the running app is cheap and happens first. A
  bind failure means a foreign process holds the port, which no ordering can
  prevent and which is reported instead.

  So a red store leaves the previous version answering and the session's
  `:app-server` untouched: \"always up\" and \"up to date\" only conflict when
  a boot fails, and this is the answer to that conflict. A red `done` still
  refreshes — `done` REPORTS rather than refuses and a red one STANDS, so
  \"finished\" and \"green\" are different questions, and seeing the app is
  part of how you find out you were not finished.

  The old image is stopped BEFORE the new one binds, because they want the
  same derived port. That is a real gap in service, and it is the price of a
  stable url — the alternative, binding the new one somewhere else first,
  keeps the app up under an address nobody was given."
  [session store dir]
  (let [plan (serve-plan store dir)]
    (if-not (:enabled? plan)
      {:serving? false :reason (:reason plan) :plan plan}
      (let [booted (boot! session store plan)]
        (if (:reason booted)
          (assoc booted :serving? false :plan plan)
          (do (stop! (:app-server @session))
              ;; RE-stamped, not inherited. A refresh is a NEW version of the
              ;; app, and carrying the previous stamp forward would report the
              ;; new process as current to a store it never saw — the precise
              ;; failure the stamp exists to end.
              (let [served (serve-in! booted)
                    now    (cond-> served
                             (:serving? served)
                             (assoc :currency
                                    (currency/of (:db @session)
                                                 (:line @session))))]
                (swap! session assoc :app-server now)
                now)))))))

(defn ^:export currency
  "Whether the app this session is running was started from the store as it
  stands — `slopp.currency/report` over the stamp the running map carries.

  **A declared entry cannot be asked.** It answers `:started` and nothing
  else: no bound socket, no health, no version. So \"is this process running
  current code\" is not inferable after the fact and has to be RECORDED at the
  start, which is what [[start!]] and [[refresh!]] do.

  Reusing `slopp.currency` rather than counting deltas here is deliberate,
  and the reason is its third state. `:current?` is true, false, or **nil**
  when nothing could be measured — an unstamped process, or no connection to
  compare against. A process nobody stamped is not a stale one, and
  collapsing those two is exactly how a derived value comes to report
  confidently about a store it never looked at.

  The report rides the running map rather than living in a checker somebody
  has to remember to call."
  [conn running]
  (currency/report conn (:currency running)))

(defn ^:export hot-reload-set
  "The namespaces to push into an ALREADY-RUNNING app image, dependencies
  first: what changed between `loaded` (what the image holds, `{ns source}`)
  and `now`, plus everything that requires them.

  **Reusing `kernel.boot/with-dependents` rather than deciding again here.**
  It answers exactly this question for `--live`, it is tested, and it carries
  the reason a source diff alone is not enough: a value captured at `def`
  time — a schema in var metadata, a `def` built from another namespace's
  data — does not move when only its own namespace does, so a dependent whose
  own text is byte-identical goes on holding the old one.

  This store already has two implementations of that failure class
  (`with-dependents` over the require graph, `rules.currency/stale-after` over
  currency stamps) and five reload paths between them. A third answer to the
  same question, in the namespace that spawns app servers, is not what the
  count needs.

  Empty when nothing changed — a `done` that touched no code must not churn
  the app image."
  [loaded now]
  (let [edited (into #{} (filter #(not= (get loaded %) (get now %))) (keys now))]
    (if (empty? edited)
      []
      (boot/with-dependents now edited))))

(defn ^:export hot-refresh!
  "Push what MOVED into the already-running app image and keep it serving —
  `{:hot? true …}` on success, `nil` when this refresh cannot be done in
  place and the caller should fall back to [[refresh!]].

  **This is the change `serve-code` names and nobody had made.** A full
  re-boot replaces the child JVM, so `:http/perform-ctx` is rebuilt and any
  state the app kept there — a cache, a registry, a pool — is silently gone at
  every `done`. Hot-loading leaves the context alone because it never re-runs
  the generated call.

  Returns nil rather than throwing for the cases in-place cannot serve, and
  each is a real one:

  - **nothing running**, or a running map from before this existed and so
    carrying no `:loaded` — there is nothing to diff against.
  - **the LOAD ORDER changed** — a namespace appeared or disappeared. A new
    namespace may need requiring in a dependency order this image never had,
    and a departed one leaves vars answering that the store no longer defines.
    `--live` handles the second with `departed-vars`; an app image has no
    equivalent, so a boot is the honest answer.
  - **a reload FAILED** — the running version is left untouched and the caller
    re-boots, which is `refresh!`'s existing safe-swap and is already tested.

  **Two things are retaken on success, and the first one was missed for a
  month.** `:served-at` is what `app-behind` counts from; leaving it at boot
  made the count rise forever while `done` stayed honestly silent about a
  refresh that had worked. And the static dir the child serves from is
  RE-SYNCED, because `boot!` wrote it on the assumption that a refresh
  replaces the image — true until this function existed. A recompiled bundle
  reached the store and never the child: 23 hours, 57 changes, one
  consumer's browser. The child reads per request, so syncing in place is
  the whole fix."
  [session store running]
  (let [was (:loaded running)
        now (into {} (map (juxt identity #(store.render/render-ns store %)))
                  (load-order store))]
    (when (and (:serving? running) (seq was)
               ;; same POPULATION, or an in-place reload cannot be honest
               (= (set (keys was)) (set (keys now))))
      (let [todo   (hot-reload-set was now)
            ;; the dir the child is serving from — assets that moved since
            ;; boot are written INTO it, not beside it
            plan   (:plan running)
            synced (when-let [sd (:static-dir plan)]
                     (materialize-static! store (:static plan) (:dir @session) sd))
            ;; recorded on the session exactly as `refresh!` does, or the
            ;; session goes on holding the pre-reload map and every later
            ;; refresh diffs against a `:loaded` that has moved on
            keep!  (fn [r] (swap! session assoc :app-server r) r)
            stamp  (fn [r]
                     (cond-> (assoc r :hot? true
                                    ;; the head this image NOW serves, not the
                                    ;; one it booted on: what app-behind counts from
                                    :served-at (:head-at store)
                                    :currency (currency/of (:db @session) (:line @session)))
                       synced (assoc-in [:plan :static-dir] synced)))]
        (if (empty? todo)
          ;; nothing moved in code: still a successful refresh, and re-stamping
          ;; is what makes \"current\" true rather than merely unchanged
          (keep! (stamp (assoc running :reloaded [])))
          ;; a failed reload falls out as nil — the running version is
          ;; untouched and the caller re-boots, which is the safe swap that
          ;; already exists and is already tested
          (let [failed (first (keep (fn [n] (image/load-ns! (:image running) store n))
                                    todo))]
            (when-not failed
              (keep! (stamp (assoc running :reloaded (vec todo) :loaded now))))))))))
