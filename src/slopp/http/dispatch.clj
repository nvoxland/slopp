(ns slopp.http.dispatch
  "The request pipeline, and **the order IS the guarantee**: identity →
  route → policy → declared reads → handler → effects. A handler is
  unreachable un-authorized, and it cannot be reached by any other path,
  because there is exactly one.

  Everything here is in-process. Request map in, response map out; the socket
  belongs to an adapter. That is what makes an app's whole behaviour — auth,
  reads, effects, error mapping — testable without a port.

  Three properties this namespace owns, each of which exists because the
  static gates cannot see far enough:

  - **Effects are BOUNDED by the route's declaration.** `http-unsafe-get` and
    `http-undeclared-effect` read a handler BODY; a handler that computes its
    effects defeats them. So the dispatcher refuses an undeclared kind before
    running any of them, and validates every kind before the first one — a
    typo must not leave a partial write.
  - **A nil policy DENIES, and so does an empty composite.** `[:all]` over
    nothing is vacuously true, which would authorize everyone; default-deny
    here matches the write-time refusal rather than contradicting it.
  - **Failure is DATA, and disclosure is a decision.** An `ex-info` carrying
    `:http/status` surfaces its message plus only an explicit `:http/public`
    allowlist. Anything else is a generic 500 with the detail on stderr — the
    body never carries an internal.

  Query parsing happens once, here, so no app writes its own splitter — and a
  declared read addresses `[:query-params :view]` exactly as it addresses
  `[:path-params :id]`."
  (:require [slopp.http.router :as router] [slopp.http.auth :as auth] [clojure.string :as str] [slopp.lang :as lang] [slopp.http.html :as html]))

(defn authorized?
  "Does `identity` ({:http/sub … :http/groups #{…}} or nil) satisfy `policy`?
  The :http/auth grammar: :public | :authenticated | [:group \"g\"] |
  [:any p…] | [:all p…]. A nil policy DENIES — runtime default-deny,
  matching the http-auth-refusal write gate. An EMPTY composite ([:all] /
  [:any] with no sub-policies) also DENIES: a conjunction over nothing is
  vacuously true, so [:all] would otherwise authorize everyone (review W1).
  Pure set logic; no macro."
  [policy identity]
  (cond
    (= :public policy) true
    (= :authenticated policy) (some? identity)
    (and (vector? policy) (= :group (first policy)))
    (contains? (:http/groups identity #{}) (second policy))
    (and (vector? policy) (= :any (first policy)))
    (boolean (some #(authorized? % identity) (rest policy)))
    (and (vector? policy) (= :all (first policy)))
    (and (seq (rest policy))
         (every? #(authorized? % identity) (rest policy)))
    :else false))

(defn- run-effects!
  "Interpret a response's `:http/effects` ([[kind & args] …]) through
  `performers` ({kind → f}): every kind is validated BEFORE any effect
  runs (all-or-nothing precheck — a typo'd kind must not leave a partial
  write), then each performs in declared order as
  (f perform-ctx & args). Returns nil, or {:error …} naming the alien
  kind."
  [performers perform-ctx effects]
  (if-let [alien (some (fn [[kind]] (when-not (contains? performers kind) kind))
                       effects)]
    {:error (str "no performer for effect kind " alien
                 " — the dispatcher only runs kinds a ^{:http/effect <kind>}"
                 " form provides")}
    (do (doseq [[kind & args] effects]
          (apply (get performers kind) perform-ctx args))
        nil)))

(defn- decoded-input
  "Everything the caller SENT, as the handler should receive it —
  `{:value {:path-params … :query-params … :body …}}` with each carrier decoded
  to the declared types, or `{:error <teaching string>}` when the whole of it
  violates the endpoint's `:rest/request`.

  TWO conditions, and both are the capability model: the endpoint must have
  declared a contract, and the CONTEXT must carry a validator for it. An app
  serving HTML declares neither, supplies neither, and takes exactly the path it
  always did. The validator arrives as a FUNCTION rather than being called by
  name, which is what keeps malli out of this framework — `slopp.rest` requires
  it and `slopp.http.*` does not, so an app is never made to carry a validation
  library because a capability it did not enable needs one.

  **Every carrier, not just the body.** `:rest/request` describes what the caller
  SENDS, and the generated client reads the method to decide where each key
  travels — so a path segment and a query parameter are as much the contract as
  a body is, and just as untrusted. An earlier cut judged the body alone, which
  left `?depth=banana` against a declared `[:depth :int]` reaching the handler
  as a string."
  [ctx row req path-params query-params]
  (let [sent {:path-params path-params :query-params query-params :body (:body req)}]
    (if-let [f (and row (:rest/request row) (:rest/decode-request ctx))]
      (f (:rest/request row) sent)
      {:value sent})))

(defn- response-violation
  "A teaching string when `resp` breaks the endpoint's own `:rest/response`, else
  nil.

  **Only a SUCCESS body is judged against it.** `:rest/response` describes what
  a 200 carries; a 404's `{:error …}` does not match it and must not become a
  500 for failing to. Getting that wrong would make every deliberate error
  response look like a server fault, which is the opposite of the point.

  **The row says how the endpoint ANSWERS, and the checker needs that.** An
  endpoint that serialized its own body (`:http/raw`) hands back the ENVELOPE,
  so judging the schema against it asks about bytes rather than about the
  document a consumer reads. This is the only place holding both the schema and
  the declared media type, so it is where the two are joined — the checker then
  models arrival rather than guessing at it."
  [ctx row resp]
  (when-let [f (and row (:rest/response row) (:rest/check-response ctx))]
    (when (<= 200 (:status resp 200) 299)
      (f (:rest/response row) (:body resp)
         {:raw? (boolean (:http/raw resp))
          :media-type (or (get (:headers resp) "Content-Type")
                          (:rest/media-type row))}))))

(defn ^:export
  ^{:teach "the response :body comes back as Clojure DATA — the ADAPTER serializes, so this is NOT what a client receives. slopp.rest/call drives this same pipeline through the real encoding both ways and hands back the client's view; reach for that rather than round-tripping by hand."}
  handle!
  "The whole request pipeline, callable in-process — request map in,
  response map out; the socket is an adapter's concern. `ctx`:
  {:http/routes [rows] :http/read-performers {kind→f}
   :http/effect-performers {kind→f} :http/perform-ctx <passed to performers>
   :http/auth-config <the provider config identity resolves through>}.

  Order is the guarantee: IDENTITY (resolved through :http/auth-config when
  the request carries none — a pre-resolved :http/identity is respected) →
  ROUTE (404) → POLICY (401 unauthenticated / 403 unauthorized — the
  handler is unreachable un-checked) → the declared CONTRACT (400, and only
  when the context carries a validator) → declared :http/reads fetched via the
  app's read performers → the handler, with :path-params, :query-params
  (parsed from :query-string once, here, so no app writes its own
  splitter — and a declared read's path addresses it the same way, so
  [:query-params :view] works exactly like [:path-params :id]),
  :http/deps (the perform-ctx as a value) and the fetched :http/reads on
  the request → the
  response's :http/effects interpreted through the app's effect performers,
  BOUNDED by the route's declared :http/effects (a handler cannot emit a kind
  its route did not declare — the runtime half of http-unsafe-get /
  http-undeclared-effect, which see only the static handler body; review W4).
  Every failure is response DATA — an ex-info carrying :http/status maps to
  it and surfaces its message plus ONLY a :http/public allowlist; any other
  exception is a GENERIC 500 with the detail logged server-side, never in
  the body (review W3)."
  [ctx req]
  ;; An UNASSEMBLED context — the input map `context` takes, handed straight
  ;; here — has no derived route table, so every path misses and every path
  ;; answers 404. That is a correct answer to a question nobody asked, and it
  ;; implicates the CALLER'S ROUTES, which is where they look first. Reported
  ;; by a consumer who spent three attempts there.
  ;;
  ;; The tell is a declaration rather than a guess: `context` READS
  ;; :http/namespaces and does not pass it on, so a ctx carrying one has not
  ;; been through it. A hand-built {:http/routes […]} stays legitimate.
  (when (contains? ctx :http/namespaces)
    (throw (ex-info (str "this context has not been assembled — it still carries"
                         " :http/namespaces, which slopp.http/context reads and"
                         " does not pass on. Unassembled, there is no derived"
                         " route table, so EVERY path answers 404 and the 404"
                         " looks like a bug in your routes. Wrap it:"
                         " (slopp.http/handle! (slopp.http/context opts) req),"
                         " or (slopp.http/driver (slopp.http/context opts)) to"
                         " drive it headlessly")
                    {:http/namespaces (vec (:http/namespaces ctx))})))
  (let [req (if (or (:http/identity req) (nil? (:http/auth-config ctx)))
              req
              (assoc req :http/identity
                     (auth/resolve-identity (:http/auth-config ctx) req)))
        row (router/match (:http/routes ctx)
                          (:request-method req) (:uri req))
        ;; the params are derived HERE rather than inside the handler branch,
        ;; because they are part of the CONTRACT and so must exist before it is
        ;; judged. :query-string is parsed once, so no app writes its own
        ;; splitter and a declared read addresses a query param exactly as it
        ;; addresses a path param.
        pp   (:path-params row)
        qp   (lang/query-params (:query-string req))
        ;; decided ONCE, before the cond, so the refusal branch and the handler
        ;; branch cannot disagree about what the caller sent
        sent (decoded-input ctx row req pp qp)]
    (cond
      (nil? row)
      {:status 404 :body {:error "no route"}}

      (not (authorized? (:auth row) (:http/identity req)))
      (if (:http/identity req)
        {:status 403 :body {:error "forbidden"}}
        {:status 401 :body {:error "unauthenticated"}})

      ;; THE CONTRACT, and its position in this cond is the point: after
      ;; policy, BEFORE the declared reads and before the handler. A refusal
      ;; that arrives later has already done work on unvalidated input, which
      ;; is the thing a boundary exists to prevent.
      ;;
      ;; The explain is the CLIENT's own data described back to them, so it
      ;; travels — unlike a response violation, which is the server's fault and
      ;; says nothing.
      (:error sent)
      {:status 400 :body {:error (:error sent)}}

      ;; CONTENT, and it is served by DEREFERENCING the var rather than
      ;; calling it. `:http/path` declares a `def` whose value IS the page, so
      ;; there is no handler to invoke, no request to hand it, and nothing
      ;; below this branch applies: a stored value cannot declare reads,
      ;; cannot emit effects, and cannot violate a response contract it has no
      ;; way to declare. Placing it after POLICY and not before is the whole
      ;; point — content is as unreachable un-authorized as any handler.
      (= :content (:kind row))
      (html/content-response
       (let [value (var-get (:handler row))]
         (if-let [bundle (:webapp/shell row)]
           (html/complete-shell value bundle (:webapp/base ctx))
           value))
       (:http/media-type row))

      :else
      (let [;; DECODED IN PLACE: a handler reads :path-params, :query-params and
            ;; :body where it always did and finds them typed — a path segment
            ;; declared :int arrives an int. With no contract and no validator
            ;; these are exactly what they always were.
            req' (assoc req :path-params (:path-params (:value sent))
                        :query-params (:query-params (:value sent))
                        :http/deps (:http/perform-ctx ctx)
                        :body (:body (:value sent)))
            fetch (fn [[alias [kind path]]]
                    (if-let [f (get (:http/read-performers ctx) kind)]
                      [alias (f (:http/perform-ctx ctx) (get-in req' path))]
                      (throw (ex-info (str "no performer for read kind " kind)
                                      {:http/read kind}))))
            declared (set (:http/effects row))
            ;; the response contract is judged between the handler and the
            ;; return, so a violation never reaches the adapter. The explain
            ;; goes to the LOG and not the body: a response that breaks its own
            ;; contract is the server's fault, and the same rule already
            ;; governs an unexpected exception two branches down.
            check (fn [r] (if-let [err (response-violation ctx row r)]
                            (do (.println System/err
                                          (str "slopp.http: response contract violated at "
                                               (:method row) " " (:path row) " — " err))
                                {:status 500 :body {:error "internal server error"}})
                            r))
            resp (try
                   (let [reads (when-let [decl (:http/reads row)]
                                 (into {} (map fetch) decl))
                         resp  ((:handler row)
                                (cond-> req' reads (assoc :http/reads reads)))
                         effects (seq (:http/effects resp))
                         undeclared (seq (remove #(contains? declared (first %))
                                                 effects))]
                     (cond
                       ;; a kind the ROUTE never declared — the static gate
                       ;; can't see a handler that computes its effects, so
                       ;; the dispatcher refuses before running any of them
                       undeclared
                       {:status 500
                        :body {:error (str "endpoint emitted undeclared effect kind(s) "
                                           (str/join ", " (map first undeclared))
                                           " — declare them in :http/effects or drop them")}}

                       effects
                       (or (when-let [err (run-effects!
                                           (:http/effect-performers ctx)
                                           (:http/perform-ctx ctx) effects)]
                             {:status 500 :body err})
                           (check resp))

                       :else (check resp)))
                   (catch Exception e
                     (let [data (ex-data e)]
                       (if-let [status (:http/status data)]
                         ;; a DELIBERATE boundary error: its message and only
                         ;; an explicit :http/public allowlist reach the client
                         {:status status
                          :body (cond-> {:error (ex-message e)}
                                  (contains? data :http/public)
                                  (assoc :data (:http/public data)))}
                         ;; anything else is unexpected — log the detail,
                         ;; return nothing that discloses internals
                         (do (.println System/err
                                       (str "slopp.http: unhandled "
                                            (.getName (class e)) " — "
                                            (ex-message e)))
                             {:status 500 :body {:error "internal server error"}})))))]
        resp))))

(defn bounded-body-string
  "Read up to `max-bytes` from InputStream `in` as a UTF-8 string. Returns
  {:body s-or-nil} within the cap, or {:too-large true} the moment the
  stream exceeds it — the request-body DoS guard both adapters share
  (review W8: an unbounded slurp is bounded only by heap). A nil stream is
  an empty body."
  [in max-bytes]
  (if (nil? in)
    {:body nil}
    (let [buf (java.io.ByteArrayOutputStream.)
          arr (byte-array 8192)
          lim (long max-bytes)]
      (with-open [^java.io.InputStream in in]
        (loop []
          (let [n (.read in arr)]
            (cond
              (neg? n) {:body (when (pos? (.size buf))
                                (String. (.toByteArray buf) "UTF-8"))}
              (> (+ (.size buf) n) lim) {:too-large true}
              :else (do (.write buf arr 0 n) (recur)))))))))
