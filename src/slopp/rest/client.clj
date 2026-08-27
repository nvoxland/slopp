(ns slopp.rest.client
  "Calling somebody ELSE'S REST service, typed — the server's half of a
  generated client.

  `slopp.rest.contract` answers what a contract MEANS for a value crossing the
  wire in either direction. This performs a crossing outward: a request a
  generated builder produced, sent to an upstream, decoded, and judged against
  the contract that upstream publishes.

  **It exists because generation always produced both typed halves and nothing
  performed them on this side.** `slopp.webdev.cljs` emits `:cljc` request
  builders and `:cljc` response checks, which run on the JVM perfectly well;
  what a server was handed instead was `slopp.http.client/request`, a socket.
  So a store holding an upstream's contract — rendering it on a screen, even —
  still built url strings by hand and decided for itself what a non-2xx meant.

  Neighbours, and the layering is the point:

  | | |
  |---|---|
  | `slopp.http.client` | the PORT. A socket with a schema and one typed throw, holding no policy at all |
  | here | the policy: url from the base, encode, decode, timeout, contract |
  | `slopp.rest/call` | the same idea pointed INWARD — your own endpoints, in process, through the real encoding |
  | `slopp.webapp` | where the request-shape decisions live, made once for both performers |

  The dependency on `slopp.webapp` is deliberate rather than incidental. A
  request is one shape with two performers — `fetch` in a browser, this on a
  server — and every decision between them (which segment is a parameter, what
  encodes a body, what a `Content-Type` means) is made once over there. A
  second copy here would agree until the first change."
  (:require [slopp.http.client :as http.client]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [cheshire.core :as json] [slopp.http.endpoint :as endpoint]))

(defn- checked-path
  "`path` when it is a path under the base, or a refusal naming what it would
  have left.

  **REFUSED rather than cleaned**, and the difference is the whole point: a
  path that names another origin is not a path with a mistake in it, it is a
  different request. Sanitizing one would send something the caller did not
  write, which is the shape that turns a bug into a silent redirection.

  This is the one part of an outbound call that an UPSTREAM's own data can
  reach — a slug, an id, a name carried over from a previous response — so if
  it can become a different host then the base was never a boundary. Segment
  VALUES cannot do it (`slopp.http.endpoint/request` percent-encodes each one,
  so a `/` inside a value is data); this guards the url it built, whose static
  half comes from the descriptor verbatim."
  [path]
  (let [p (str path)]
    (cond
      (str/includes? p "://")
      (throw (ex-info (str "a request path cannot leave the upstream: "
                           (pr-str p) " names a scheme, so it is a whole url"
                           " rather than a path under the base. Call the other"
                           " service through its own upstream value")
                      {:rest/error :escaping-path :rest/path p}))

      (str/starts-with? p "//")
      (throw (ex-info (str "a request path cannot leave the upstream: "
                           (pr-str p) " starts with // which is"
                           " protocol-relative — a browser and a server both"
                           " read that as another HOST, not as a path")
                      {:rest/error :escaping-path :rest/path p}))

      (not (str/starts-with? p "/"))
      (throw (ex-info (str "a request path must start with / — " (pr-str p)
                           " would be joined to the base with no separator and"
                           " reach a path nobody wrote")
                      {:rest/error :escaping-path :rest/path p}))

      :else p)))

(defn- encoded-body
  "The body as a STRING, encoded by what `request-init` decided — or nil when
  there is no body.

  The choice is not made here. `slopp.http.endpoint/request-init` reads the declared
  `Content-Type` and NAMES the encoder, for the browser shim's sake; the server
  performs the same named choice, so the two ends cannot drift about what a
  declared content type means."
  [{:keys [encode body]}]
  (case encode
    :none nil
    :json (json/generate-string body)
    :edn  (pr-str body)
    (str body)))

(defn- decoded-body
  "The response body as DATA, by the media type the far side declared —
  otherwise the string as it stands.

  **EDN is read with `clojure.edn/read-string`, never `read-string`.** The
  plain one evaluates reader tags, so an upstream could run code in this
  process by answering with one. That is a real remote-execution door, it is
  open by default in Clojure, and every store that hand-rolled an EDN upstream
  would have had to know — which is the kind of thing a framework exists to
  know once.

  A body slopp cannot decode comes back as the string it arrived as. Guessing
  JSON at something declared `text/csv` would hand the caller a parse error
  about a document that is fine."
  [content-type body]
  (case (endpoint/media-type content-type)
    "application/json" (json/parse-string (str body) true)
    "application/edn"  (edn/read-string (str body))
    body))

(defn ^:export ^{:http/effectful true}
  ^{:malli/schema
    [:=> {:throws [[:map [:rest/error [:enum :escaping-path :contract]]]]}
     [:cat [:map
            [:rest/base-url :string]
            [:rest/headers {:optional true} [:map-of :string :string]]
            [:rest/timeout-ms {:optional true} :int]]
           [:map
            [:http/url :string]
            [:http/method {:optional true} :keyword]]
           [:* :any]]
     [:map [:status :int] [:headers [:map-of :string :string]] [:body :any]]]}
  call!
  "Call an UPSTREAM REST service with a request a generated builder produced,
  and answer `{:status :headers :body}` with the body DECODED.

  `upstream` is `{:rest/base-url \"https://…\" :rest/headers {…}
  :rest/timeout-ms 5000}`. `request` is the map a `-request` builder returns —
  the same value a browser hands `:webapp/call`. Trailing options: `:check`
  (the generated `-check` fn for this endpoint) and `:requester` (the
  `slopp.http.client/request` port, so a test uses `fake-requester` and no
  socket opens).

  **The typed halves already existed and nothing performed them.** Generation
  emits `:cljc` request builders and `:cljc` response checks, and both run on
  the JVM today. What a server got instead was `slopp.http.client/request` — a
  socket — so a store holding an upstream's contract still built url strings by
  hand. Reported by a consuming store that renders that contract on a screen
  and forwards to it untyped: about forty lines, none of them about their
  application.

  **One request shape, two performers**, the same split `slopp.cljnx` made for
  drivers. A browser performs it with `fetch`; a server performs it here; and
  the decisions between — which segment is a parameter, what encodes the body,
  what a `Content-Type` means — are made once in `slopp.http.endpoint` and
  merely carried out on both sides. They used to be made in `slopp.webapp`,
  which meant a SERVER-side client required the browser's namespace to encode
  a body — and vendoring is per capability, so that require resolved in a
  store that had opted into `webapp` and nowhere else.

  ## What is handled, since \"safe\" was half the ask

  - **A timeout is ALWAYS set** (`slopp.http.client/default-timeout-ms` when
    nobody says). The port publishes that number rather than applying it, and
    the reason it is published at all is that two of slopp's OWN outbound call
    sites had none — found when a consuming store reported the same omission in
    theirs.
  - **The path cannot leave the base** — refused, not cleaned ([[checked-path]]).
  - **Redirects are not followed**, because `java.net.http.HttpClient` defaults
    to `NEVER` and nothing here changes it. An upstream that could redirect you
    would be choosing your next host — the same boundary, one hop along.
  - **EDN is read without evaluating it**, see [[decoded-body]].
  - **Credentials stay out of failures.** No header reaches `ex-data` here, and
    the port's own unreachable throw carries only the url.

  ## What is NOT handled, so nobody assumes it

  No retry, no backoff, no circuit breaker, no host allowlist beyond the base,
  and no redaction of a credential a caller puts in a QUERY parameter. Each is
  a policy with more than one right answer and no instance behind it yet;
  inventing them here would be this framework guessing for every store.

  ## The two failure lines, both deliberate

  **An answered request RETURNS whatever its status; an unanswered one
  THROWS** — the port's rule, preserved rather than re-decided. A 404 is data
  because deciding what a missing upstream resource means belongs to the
  caller, and the consuming store's narrow catch exists precisely because a
  broad one once reported THEIR bug as \"the project has probably stopped\".
  Wrapping the port's `:http/error :unreachable` in a second shape would make
  that catch wrong again.

  **A failed `:check` THROWS**, typed `:rest/error :contract`. That one is not
  the caller's judgement: the upstream broke a promise it publishes, and there
  is no branch to take that is not \"this is broken\". Returning it would let
  the wrong shape travel one more layer before anyone noticed."
  [{:rest/keys [base-url headers timeout-ms]} request & {:keys [check requester]}]
  (let [init (endpoint/request-init request)
        ;; the url arrives RESOLVED and encoded — `slopp.rest.endpoint/request`
        ;; built it — so this guards a finished address rather than a template
        path (checked-path (:http/url request))
        url  (str (str/replace (str base-url) #"/+$" "") path)
        resp ((or requester http.client/request)
              (cond-> {:http/url url
                       :http/method (:http/method request :get)
                       :http/headers (merge (:headers init) headers)
                       :http/timeout-ms (or timeout-ms http.client/default-timeout-ms)}
                (some? (:body init)) (assoc :http/body (encoded-body init))))
        body (decoded-body (get (:http/headers resp) "content-type")
                           (:http/body resp))]
    (when (and check (<= 200 (:http/status resp) 299))
      (when-let [violation (check body)]
        (throw (ex-info (str "the upstream's answer does not match the contract"
                             " it publishes: " violation)
                        {:rest/error :contract
                         :rest/url url
                         :rest/violation violation}))))
    {:status  (:http/status resp)
     :headers (:http/headers resp)
     :body    body}))
