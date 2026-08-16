(ns slopp.web.dispatch
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
    `:web/status` surfaces its message plus only an explicit `:web/public`
    allowlist. Anything else is a generic 500 with the detail on stderr — the
    body never carries an internal.

  Query parsing happens once, here, so no app writes its own splitter — and a
  declared read addresses `[:query-params :view]` exactly as it addresses
  `[:path-params :id]`."
  (:require [slopp.web.router :as router] [slopp.web.auth :as auth] [clojure.string :as str]))

(defn authorized?
  "Does `identity` ({:web/sub … :web/groups #{…}} or nil) satisfy `policy`?
  The :web/auth grammar: :public | :authenticated | [:group \"g\"] |
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
    (contains? (:web/groups identity #{}) (second policy))
    (and (vector? policy) (= :any (first policy)))
    (boolean (some #(authorized? % identity) (rest policy)))
    (and (vector? policy) (= :all (first policy)))
    (and (seq (rest policy))
         (every? #(authorized? % identity) (rest policy)))
    :else false))

(defn- run-effects!
  "Interpret a response's `:web/effects` ([[kind & args] …]) through
  `performers` ({kind → f}): every kind is validated BEFORE any effect
  runs (all-or-nothing precheck — a typo'd kind must not leave a partial
  write), then each performs in declared order as
  (f perform-ctx & args). Returns nil, or {:error …} naming the alien
  kind."
  [performers perform-ctx effects]
  (if-let [alien (some (fn [[kind]] (when-not (contains? performers kind) kind))
                       effects)]
    {:error (str "no performer for effect kind " alien
                 " — the dispatcher only runs kinds a ^{:web/effect <kind>}"
                 " form provides")}
    (do (doseq [[kind & args] effects]
          (apply (get performers kind) perform-ctx args))
        nil)))

(defn- decoded-body
  "The request body as the handler should receive it — `{:value v}`, or
  `{:error <teaching string>}` when it violates the endpoint's declared
  `:web/request`.

  THREE conditions, and each is load-bearing in a different direction.

  The endpoint must have declared a contract, and the CONTEXT must carry a
  validator for it. An app serving HTML declares neither, supplies neither, and
  takes exactly the path it always did. The validator arrives as a FUNCTION
  rather than being called by name, which is what keeps malli out of this
  framework — `slopp.rest` requires it and `slopp.web.*` does not, so an app is
  never made to carry a validation library because a capability it did not
  enable needs one.

  **And the method must be one that HAS a body**, which slopp's own API is what
  taught. `:web/request` is documented as required on `:post`/`:put`/`:patch` —
  but a GET may declare one too, and when it does it describes the PATH AND
  QUERY PARAMS rather than a body: `/api/ns/:ns` declares `[:map [:ns :string]]`
  for its path segment, and the generated client reads it as the wrapper's
  argument list. Validating that against a nil body 400s every correct request,
  which is what the first version of this did to four of slopp's own endpoints
  the moment it was pointed at them.

  So this honours the documented meaning and no more. **Params are untrusted
  input too and are NOT covered here** — a real gap, filed rather than closed,
  because covering it means deciding what `:web/request` means for the
  generated client as well, and the server and the client must not answer that
  differently."
  [ctx row req]
  (if-let [f (and row
                  (contains? #{:post :put :patch} (:method row))
                  (:web/request row)
                  (:rest/decode-request ctx))]
    (f (:web/request row) (:body req))
    {:value (:body req)}))

(defn- response-violation
  "A teaching string when `resp` breaks the endpoint's own `:web/response`, else
  nil.

  **Only a SUCCESS body is judged against it.** `:web/response` describes what
  a 200 carries; a 404's `{:error …}` does not match it and must not become a
  500 for failing to. Getting that wrong would make every deliberate error
  response look like a server fault, which is the opposite of the point."
  [ctx row resp]
  (when-let [f (and row (:web/response row) (:rest/check-response ctx))]
    (when (<= 200 (:status resp 200) 299)
      (f (:web/response row) (:body resp)))))

(defn ^:export
  ^{:teach "the response :body comes back as Clojure DATA — the ADAPTER serializes, so this is NOT what a client receives. slopp.rest/call drives this same pipeline through the real encoding both ways and hands back the client's view; reach for that rather than round-tripping by hand."}
  handle!
  "The whole request pipeline, callable in-process — request map in,
  response map out; the socket is an adapter's concern. `ctx`:
  {:web/routes [rows] :web/read-performers {kind→f}
   :web/effect-performers {kind→f} :web/perform-ctx <passed to performers>
   :web/auth-config <the provider config identity resolves through>}.

  Order is the guarantee: IDENTITY (resolved through :web/auth-config when
  the request carries none — a pre-resolved :web/identity is respected) →
  ROUTE (404) → POLICY (401 unauthenticated / 403 unauthorized — the
  handler is unreachable un-checked) → declared :web/reads fetched via the
  app's read performers → the handler, with :path-params, :query-params
  (parsed from :query-string once, here, so no app writes its own
  splitter — and a declared read's path addresses it the same way, so
  [:query-params :view] works exactly like [:path-params :id]),
  :web/deps (the perform-ctx as a value) and the fetched :web/reads on
  the request → the
  response's :web/effects interpreted through the app's effect performers,
  BOUNDED by the route's declared :web/effects (a handler cannot emit a kind
  its route did not declare — the runtime half of http-unsafe-get /
  http-undeclared-effect, which see only the static handler body; review W4).
  Every failure is response DATA — an ex-info carrying :web/status maps to
  it and surfaces its message plus ONLY a :web/public allowlist; any other
  exception is a GENERIC 500 with the detail logged server-side, never in
  the body (review W3)."
  [ctx req]
  (let [req (if (or (:web/identity req) (nil? (:web/auth-config ctx)))
              req
              (assoc req :web/identity
                     (auth/resolve-identity (:web/auth-config ctx) req)))
        row (router/match (:web/routes ctx)
                          (:request-method req) (:uri req))
        ;; decided ONCE, before the cond, so the refusal branch and the
        ;; handler branch cannot disagree about what the body is
        body (decoded-body ctx row req)]
    (cond
      (nil? row)
      {:status 404 :body {:error "no route"}}

      (not (authorized? (:auth row) (:web/identity req)))
      (if (:web/identity req)
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
      (:error body)
      {:status 400 :body {:error (:error body)}}

      :else
      (let [req' (assoc req :path-params (:path-params row)
                        ;; parsed ONCE here, so no app writes its own
                        ;; splitter over the :query-string the adapters carry
                        :query-params (router/query-params (:query-string req))
                        :web/deps (:web/perform-ctx ctx)
                        ;; the DECODED body: what the wire could not carry —
                        ;; a keyword, a date, a uuid — arrives as the author
                        ;; declared it, so the handler parses nothing. With no
                        ;; contract and no validator this is the body it
                        ;; always got.
                        :body (:value body))
            fetch (fn [[alias [kind path]]]
                    (if-let [f (get (:web/read-performers ctx) kind)]
                      [alias (f (:web/perform-ctx ctx) (get-in req' path))]
                      (throw (ex-info (str "no performer for read kind " kind)
                                      {:web/read kind}))))
            declared (set (:web/effects row))
            ;; the response contract is judged between the handler and the
            ;; return, so a violation never reaches the adapter. The explain
            ;; goes to the LOG and not the body: a response that breaks its own
            ;; contract is the server's fault, and the same rule already
            ;; governs an unexpected exception two branches down.
            check (fn [r] (if-let [err (response-violation ctx row r)]
                            (do (.println System/err
                                          (str "slopp.web: response contract violated at "
                                               (:method row) " " (:path row) " — " err))
                                {:status 500 :body {:error "internal server error"}})
                            r))
            resp (try
                   (let [reads (when-let [decl (:web/reads row)]
                                 (into {} (map fetch) decl))
                         resp  ((:handler row)
                                (cond-> req' reads (assoc :web/reads reads)))
                         effects (seq (:web/effects resp))
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
                                           " — declare them in :web/effects or drop them")}}

                       effects
                       (or (when-let [err (run-effects!
                                           (:web/effect-performers ctx)
                                           (:web/perform-ctx ctx) effects)]
                             {:status 500 :body err})
                           (check resp))

                       :else (check resp)))
                   (catch Exception e
                     (let [data (ex-data e)]
                       (if-let [status (:web/status data)]
                         ;; a DELIBERATE boundary error: its message and only
                         ;; an explicit :web/public allowlist reach the client
                         {:status status
                          :body (cond-> {:error (ex-message e)}
                                  (contains? data :web/public)
                                  (assoc :data (:web/public data)))}
                         ;; anything else is unexpected — log the detail,
                         ;; return nothing that discloses internals
                         (do (.println System/err
                                       (str "slopp.web: unhandled "
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
