(ns slopp.http
  "The ONE namespace a web app requires — everything else under `slopp.http`
  is reached through here.

  Six functions, and the split between them is the point:

  - **`context` and `serve!`** assemble an app from its NAMESPACES. The route
    table and both performer vocabularies derive from var METADATA
    (`:http/path`, `:http/read`, `:http/effect`), so an app is declared where its
    code is rather than in a table that drifts from it. `serve!` is `context`
    plus a socket; `context` alone is what a test uses.
  - **`handle!`** runs a whole request with no socket anywhere — request map
    in, response map out. An app's tests live here, not against a port.
  - **`enforce` and `authorized?`** are the two shapes of row-level permission
    that route policy cannot express: refuse, or branch.

  The recurring difficulty this namespace exists to manage is that **assembly
  is where a web app fails silently.** Performers resolve by VOCABULARY
  store-wide, so a `:http/namespaces` list missing half the app assembles
  happily and then answers 500 — not 404 — at request time, with the detail
  server-side. That is the worst pairing available: the failure with no check
  is also the hardest one to read from outside. So `context` refuses an
  incomplete context up front; it costs a set difference and it is the only
  place holding everything the check needs.

  This module reaches back into NOTHING else in slopp — pinned by
  `slopp.modules-test/no-shipped-framework-family-reaches-back-into-slopp` — which
  is what lets `build.clj` ship it as the standalone
  `io.github.nvoxland/slopp-web` jar. A require of `slopp.store` from anywhere
  under here would pass every test in this repo and break at a USER's require
  time."
  (:require [slopp.http.routes :as routes]
            [slopp.http.dispatch :as dispatch]
            [slopp.http.server.jdk :as jdk] [slopp.http.server.httpkit :as httpkit] [clojure.string :as str] [slopp.http.html :as html]))

(defn enforce
  "In-handler guard for what route policy can't see (row-level authz: is
  this the owner?): a falsey `ok?` throws ex-info carrying {:http/status
  403}, which the dispatcher maps to the 403 response. Returns true when
  ok. Deliberately NOT bang-named — a throw mutates nothing, so handlers
  using it stay analyzer-pure."
  ([ok?] (enforce ok? "forbidden"))
  ([ok? msg]
   (when-not ok?
     (throw (ex-info (str msg) {:http/status 403})))
   true))

(defn authorized?
  "Does the resolved identity satisfy the `:http/auth` policy? The boolean
  twin of `enforce`, for handlers that BRANCH on permission rather than
  refuse (`dispatch/authorized?` — :public | :authenticated |
  [:group \"g\"] | [:any …] | [:all …])."
  [policy identity]
  (dispatch/authorized? policy identity))

(defn ^{:malli/schema [:=> {:throws [[:map
                       [:http/missing-performers [:vector :string]]
                       [:http/namespaces [:vector :symbol]]]]} [:cat [:map
                                  [:http/namespaces [:sequential :symbol]]
                                  [:http/routes {:optional true} [:vector :map]]
                                  [:http/perform-ctx {:optional true} :any]
                                  [:http/max-body-bytes {:optional true} :int]
                                  [:http/auth-config {:optional true} [:maybe :map]]]]
                       [:map
                        [:http/routes [:vector :map]]
                        [:http/read-performers :map]
                        [:http/effect-performers :map]]]}
  context
  "Assemble the dispatch context from `{:http/namespaces [ns-syms]
  :http/routes [extra rows — static mounts, programmatic routes]
  :http/perform-ctx <passed to every performer>
  :http/max-body-bytes <request-body cap, default 1 MiB — the http.max-body-bytes
  capability an app threads in>
  :http/auth-config <the provider config identity resolves through>}`: the
  route table and both performer vocabularies derive from the namespaces'
  VAR metadata — the same contract the store gates enforced at write time —
  with the explicit rows appended.

  REFUSES a context whose routes declare reads or effects no performer here
  can serve. Reads resolve by VOCABULARY store-wide, so an endpoint in one
  namespace legitimately reuses a performer declared in another — which means
  a `:http/namespaces` list missing half the app assembles happily and answers
  **500, not 404**, at request time, with the detail server-side and a
  generic error in the body. That is the worst pairing available: the failure
  with no check is also the one that is hardest to read from the outside.

  Everything the check needs is already in hand here, so it costs a set
  difference. Found by dogfooding: adding an `/api` namespace to this repo's
  own reviewer UI hit it immediately, because the endpoints and their read
  performers live in different namespaces on purpose."
  [{:http/keys [auth-config routes namespaces perform-ctx max-body-bytes wrap-context]
    :webapp/keys [base bundle]
    client-routes :webapp/routes}]
  (let [ctx ((or wrap-context identity)
             (cond-> {:http/routes (into (routes/from-namespaces namespaces) routes)
                      :http/read-performers (routes/performers-from-namespaces namespaces :http/read)
                      :http/effect-performers (routes/performers-from-namespaces namespaces :http/effect)
                      :http/perform-ctx perform-ctx
                      :http/max-body-bytes (or max-body-bytes 1048576)}
               auth-config (assoc :http/auth-config auth-config)
               base (assoc :webapp/base base)
               ;; the bundle a shell injects — carried on the CONTEXT beside
               ;; the mount point, because both are deployment facts and
               ;; neither is something a page can know about itself
               bundle (assoc :webapp/bundle bundle)
;; the app's OWN address table, carried for the dispatcher to derive a
               ;; status from: a `**` shell serves its document for everything
               ;; beneath it, so without this every typo under the mount answers
               ;; 200 and the app has no way to say "no such thing". Absent is
               ;; the honest default (the server cannot know), which is why this
               ;; is a cond-> clause and not a key that is always present.
               client-routes (assoc :webapp/routes client-routes)))
        ;; A route DECLARING a contract that nothing on this context honours.
        ;; The same question `missing` asks below of a declared read, and the
        ;; answer matters more: a missing performer answers 500, while a missing
        ;; validator answers 200s that were never checked — so the failure does
        ;; not merely go quiet, it looks like success. A consuming store had a
        ;; test start a real server and assert 200 while the served endpoint was
        ;; 500ing, because their two entry points wrapped differently.
        ;;
        ;; Asked with two lookups and no malli, which is what keeps it here:
        ;; whether a validator is PRESENT is http's business and what it does
        ;; is rest's.
        unhonoured (when-not (and (:rest/decode-request ctx)
                                  (:rest/check-response ctx))
                     (vec (for [row (:http/routes ctx)
                                :when (or (:rest/request row) (:rest/response row))]
                            (str (str/upper-case (name (:method row :get)))
                                 " " (:path row)))))
        missing (for [row (:http/routes ctx)
                      [decl performers] [[:http/reads (:http/read-performers ctx)]
                                         [:http/effects (:http/effect-performers ctx)]]
                      kind (let [d (get row decl)]
                             ;; :http/reads is {key [kind & path]}; :http/effects
                             ;; is a plain collection of kinds
                             (if (map? d) (map (comp first val) d) (seq d)))
                      :when (not (contains? performers kind))]
                  (str kind " (" (:method row) " " (:path row) ")"))]
    (when (seq missing)
      (throw (ex-info (str "this context declares "
                           (if (next missing) "kinds that no performer" "a kind that no performer")
                           " in :http/namespaces can serve: "
                           (str/join ", " (distinct missing))
                           " — a route whose performer is missing answers 500, not 404,"
                           " so the namespace list is checked here rather than at request time")
                      {:http/missing-performers (vec (distinct missing))
                       :http/namespaces (vec namespaces)})))
    ;; SECOND, deliberately. A route missing a performer cannot RUN at all, and
    ;; telling someone their contract is unvalidated when the endpoint would
    ;; 500 anyway sends them to the wrong end. Found by writing it first: an
    ;; existing test could no longer see its own error, because the new check
    ;; had masked the older one.
    (when (seq unhonoured)
      (throw (ex-info (str "this context serves "
                           (if (next unhonoured) "routes that DECLARE contracts"
                               "a route that DECLARES a contract")
                           " and carries nothing to honour "
                           (if (next unhonoured) "them" "it") ": "
                           (str/join ", " unhonoured)
                           " — so every request through "
                           (if (next unhonoured) "them" "it")
                           " is answered unvalidated, and a 200 that was never"
                           " checked looks exactly like one that was. Wrap the"
                           " context: :http/wrap-context slopp.rest/validating,"
                           " the one call that turns a declared contract into an"
                           " enforced one. Declaring no contract is the other"
                           " honest answer; what is refused is declaring one and"
                           " serving it unread.")
                      {:rest/unhonoured-contracts unhonoured})))
;; Every SHELL is completed once, here, and the answer thrown away. It is
    ;; completed AGAIN per request — a redefined content def has to reach the
    ;; next one, which a value frozen at assembly would not — so this is not a
    ;; cache. It is the only place a shell that CANNOT be completed can refuse
    ;; while somebody is watching: the alternative is a 500 to whoever loads
    ;; the page first, for an app that was already wrong when it came up.
    (doseq [row   (distinct (:http/routes ctx))
            :when (:webapp/shell row)]
      ;; a shell with no bundle serves a document whose script never loads —
      ;; a blank page on every route, and the app was already wrong when it
      ;; came up. The row declares THAT it is a shell; the bundle is the
      ;; app's, stated once here beside the mount point, so this is the only
      ;; place the pair can be checked against each other
      (when-not (string? bundle)
        (throw (ex-info (str (:path row) " declares :webapp/shell but this"
                             " context names no :webapp/bundle — a shell whose"
                             " script never loads is a blank page on every"
                             " route. Pass :webapp/bundle \"<url of the"
                             " compiled bundle>\" where you pass :webapp/base;"
                             " it is a deployment fact, which is why it is not"
                             " on the page")
                        {:webapp/no-bundle (:path row)})))
      (html/complete-shell (var-get (:handler row)) bundle base))
    ctx))

(defn handle!
  "Run one request through the FULL pipeline — route, policy, declared
  reads, handler, effect interpretation — with no socket anywhere: request
  map in, response map out. The app-test surface (`dispatch/handle!`)."
  [ctx req]
  (dispatch/handle! ctx req))

(defn stop!
  "Stop a `serve!` return."
  [srv]
  (case (:http/adapter srv)
    :http-kit (httpkit/stop! srv)
    :jdk (jdk/stop! srv)))

(defn bind-diagnosis
  "The leading sentence for a failed bind on `port`, or nil when `failure`
  is not a port clash. `failure` is either a Throwable or the TEXT one left
  behind after crossing a wire.

  **The diagnosis only, never the next step.** What to do about a taken port
  is not the framework's to say: slopp's dev server knows the answer is
  `http.port`, an operator running a built jar set the port some other way,
  and inventing advice for them would be a confident wrong sentence. So this
  writes the half every caller shares and each caller appends its own.

  **Both representations, because the callers genuinely hold different
  things.** In-process the failure is an exception, and http-kit wraps it, so
  the cause chain is walked. The dev server's failure comes back from a child
  image over nREPL as printed text with the class name already stringified —
  there is no Throwable left to interrogate. Two ways in, one answer out;
  before this there were three answers, phrased three ways, and \"the port is
  taken\" read differently depending on which listener you asked.

  **Anything unrecognized returns nil and the caller keeps every byte.** A
  privileged port, an unresolvable host, something not thought of — squeezing
  those into the shape of the case that IS understood is how a confident wrong
  sentence replaces a verbose right one."
  [port failure]
  (let [taken? (cond
                 (nil? failure) false
                 (instance? Throwable failure)
                 (loop [t failure]
                   (cond (nil? t) false
                         (instance? java.net.BindException t) true
                         :else (recur (.getCause ^Throwable t))))
                 :else (boolean (re-find #"(?i)address already in use" (str failure))))]
    (when taken?
      (str "port " port " is already in use"))))

(defn ^{:malli/schema [:=> {:throws [[:map
                       [:http/missing-performers [:vector :string]]
                       [:http/namespaces [:vector :symbol]]]
                      [:map
                       [:http/port :int]]]} [:cat [:map
                                  [:http/namespaces [:sequential :symbol]]
                                  [:http/adapter {:optional true} :keyword]
                                  [:http/host {:optional true} :string]
                                  [:http/port {:optional true} :int]
                                  [:http/perform-ctx {:optional true} :any]
                                  [:http/auth-config {:optional true} [:maybe :map]]]]
                       :map]}
  serve!
  "Assemble `context` from the opts and serve it: `{:http/namespaces […]
  :http/adapter :http-kit|:jdk :http/host \"127.0.0.1\" :http/port 8080
  :http/perform-ctx …}`. :http-kit is the production default (D-web §9);
  :jdk is the zero-dep fallback. Returns the adapter's handle
  (+ :http/adapter) for `stop!`. The adapter is a VALUE — the seam that
  keeps the server library choice a config key, not a rewrite.

  **A taken port THROWS, and the diagnosis leads.** It is never routed around
  — an address someone was handed must not quietly become a different one, so
  there is no port hunt here in production any more than in development. What
  changed is only what the operator reads: [[bind-diagnosis]]'s sentence
  first, the adapter's raw failure preserved behind it, and `:http/port` in the
  ex-data so a caller acts on the number rather than re-parsing the sentence."
  [{:http/keys [port host adapter] :or {adapter :http-kit host "127.0.0.1" port 8080} :as opts}]
  (let ;; `:http/wrap-context` is a fn applied to the ASSEMBLED context, between
        ;; assembly and serving. It exists because slopp GENERATES this call for
        ;; a managed app (`webdev.live/serve-code`), so a capability that must
        ;; add something to the context has no call site of its own to add it at.
        ;;
        ;; Generic on purpose: `slopp.rest/validating` is its first user and
        ;; this namespace does not learn that rest exists. That is what keeps a
        ;; validation library out of an app serving HTML — the dependency runs
        ;; rest -> web and never back.
        [ctx ((or (:http/wrap-context opts) identity) (context opts))]
    (try
      (case adapter
        :http-kit (assoc (httpkit/start! ctx {:host host :port port})
                         :http/adapter :http-kit)
        :jdk (assoc (jdk/start! ctx {:host host :port port})
                    :http/adapter :jdk))
      (catch Throwable t
        (if-let [d (bind-diagnosis port t)]
          (throw (ex-info (str d "\n" (ex-message t)) {:http/port port} t))
          (throw t))))))

(defn ^:export driver
  "This served app as a DRIVER — what the fake browser needs, derived from the
  context you already serve.

  The mirror of `slopp.webapp/driver`, and the pair is the design: ONE neutral
  driving contract, two producers, each owned by the capability whose code it
  needs. A server-rendered app's driver needs the dispatcher; a browser app's
  needs the client loop; and the fake browser needs neither, which is what lets
  it belong to nobody.

  It used to be a branch INSIDE the fake browser — `open!` took a ctx and
  `visit!` performed the request. That put http's adapter in a namespace that
  also has to drive browser apps, and vendoring is per FAMILY: an http-only
  store is handed no `slopp.webapp` source at all, so the reverse dependency
  could never be written and the two adapters could never sit together. Naming
  this one moves the dependency to the side that can hold it.

  `:document` is `(fn [path] response)` and it is a REAL request down the real
  pipeline — routing, auth policy, declared reads, the handler, effects — so a
  page that 401s here 401s when served.

  **The whole response travels, not just its body.** It used to keep `(:body
  resp)` and render anything non-hiccup as its status, which threw away two
  facts the caller needed: the STATUS, leaving `(= 404 …)` as a whole-page
  string search over a rendered sentence, and `Location`, so a redirect was
  never followed and post-redirect-get did not work at all. Rendering belongs
  to the reader and following belongs to the browser; producing the response is
  the only part that is http's.

  **A `:http/hiccup` body is swapped in, and `:document` is the BROWSER's
  contract rather than the socket's.** `slopp.http.html/html-response` renders
  to a string — which is what the socket writes — and carries the hiccup it
  came from. Without the swap, every page served through slopp's own documented
  helper drove as escaped markup in a `<pre>`, with no regions, nothing
  clickable, and a 200 labelled `HTTP 200`. Measured by a consumer on a real
  content page.

  Both views derive from ONE hiccup at one call site, so they cannot drift. The
  alternative — parsing the HTML back downstream — would have been a second
  derivation of the same page, which agrees until it does not.

  **The swap happens here rather than in the fake browser, and that placement
  is the adapter's whole job.** `:http/hiccup` is http's key, read by http's
  code. `slopp.cljnx` belongs to no capability, which is what lets it be
  vendored to every store; teaching it one prefix is how that would end. Same
  seam and same argument as splitting the url here.

  **The url is split the way a browser SENDS one**, which IS this adapter's
  business rather than the fake browser's, because only the side that knows the
  url is about to become an http request can split it: `:uri` never carries the
  `?`, the query string arrives as `:query-string`, and a `#fragment` never
  reaches the wire at all. Measured as `/search?q=web` 404ing on a mounted
  route, so every pagination link read as a broken route.

  A ctx carrying a page half — `:state` and `:view` for a mounted document that
  also has client logic — hands both through, so an app that is both is opened
  as both."
  [ctx]
  (assoc (select-keys ctx [:state :view :navigate :dispatch :boot])
         :document
         (fn [path]
           (let [base     (first (str/split path #"#" 2))
                 [uri qs] (str/split base #"\?" 2)
                 resp     (dispatch/handle! ctx (cond-> {:request-method :get :uri uri}
                                                  qs (assoc :query-string qs)))]
             ;; A page rendered by `slopp.http.html/html-response` has a STRING
             ;; body — that is what the socket writes — and carries the hiccup
             ;; it came from. Hand the reader the structure.
             ;;
             ;; The swap happens HERE and not in the fake browser, which is the
             ;; whole point of there being an adapter: `:http/hiccup` is http's
             ;; key, read by http's code, and `slopp.cljnx` belongs to no
             ;; capability. Teaching it one prefix is how it would stop being
             ;; vendorable to every store. Same seam, same argument, as
             ;; splitting the url here.
             (if-let [h (:http/hiccup resp)]
               (assoc resp :body h)
               resp)))))
