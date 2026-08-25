(ns slopp.rest.contract
  "What a declared request/response contract MEANS for a value crossing the
  wire — the pure half of the `rest` capability.

  Every decision the boundary makes lives here, as functions of a schema and a
  value: no server, no dispatch context, no socket. That is what lets an author
  ask \"would this body be accepted?\" as an `=` on a map, and it is the same
  split `slopp.cli.spec` makes against argv for the same reason — the
  interesting cases at a boundary are all decidable from data, and a boundary
  that could only be exercised by starting a process is one nobody exercises.

  The governing idea, and it is why these two functions are not symmetric: a
  contract is a promise to a consumer who never sees Clojure data. So a REQUEST
  is decoded before it is judged (JSON cannot carry a keyword), and a RESPONSE
  is judged on what actually arrives (JSON cannot carry a set). Checking the
  in-image value instead is wrong in both directions, which the response
  docstring records with the measurements.

  Neighbours: `slopp.http.dispatch` calls these through the context and never
  requires this namespace — which is what keeps malli out of an HTML app that
  enabled no typed API."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [cheshire.core :as json] [clojure.edn :as edn] [clojure.string :as str]))

(defn- closed-map
  "`schema` with its TOP LEVEL closed, or `schema` unchanged when it is not a
  map. Existing properties are kept.

  **Scoped to an API REQUEST, and the scope is the decision rather than a side
  effect of where this happens to live.** Everywhere else a malli schema means
  what it has always meant — what is REQUIRED, not what is forbidden — and that
  reading is still right for the same reasons it always was. Three places this
  deliberately does NOT reach, so nobody generalises from the one that changed:

  - **RESPONSES.** [[check-response]] judges the server's own answer, and a
    server sending a field it did not advertise is a different question from a
    caller sending one nobody asked for. Whether that should close too is open,
    not settled by this.
  - **`:malli/schema` on ordinary functions.** A function's schema describes
    arguments, and a map argument is the caller's own data structure.
  - **content.** A page declares no contract at all, so a nil schema arrives
    here and every carrier passes through untouched. `?utm=x` on a link never
    meets this function — which is what makes closing a REQUEST safe, and was
    not true before `:rest/path` and `:http/path` split.

  Top level only, within that scope. `malli.util/closed-schema` closes every
  nested map as well, which would refuse the keys of a free-form blob an author
  declared on purpose — a strictness nobody wrote, applied where they had
  already said what they meant. The question this answers is only about the
  carriers: path, query and body merge into one map, and that map is what the
  contract enumerates."
  [schema]
  (let [s (m/schema schema)]
    (if (= :map (m/type s))
      (m/into-schema :map (assoc (m/properties s) :closed true) (m/children s))
      s)))

(defn ^:export decode-request
  "Decode everything the caller SENT to the types `schema` declares, then judge
  the whole of it — `{:value {:path-params … :query-params … :body …}}` with
  each carrier decoded in place, or `{:error <teaching string>}`.

  **`:rest/request` describes what the caller sends, not where it travels.** The
  codebase said so before a boundary existed to act on it — `api.contracts/form-request`:
  *\"a GET sends a query string for the same reason a POST sends a body\"* — and
  the generated client reads the METHOD to decide which carrier each key takes.
  One schema, three possible carriers, judged as one map.

  An earlier cut judged the BODY alone and scoped itself to `:post`/`:put`/`:patch`,
  because a params schema tested against a nil body 400s every correct GET. That
  was the right stopgap and the wrong contract: it left path segments and query
  strings — untrusted input, in every link and every crawler's history —
  checked by nobody.

  **Each carrier is decoded by what its WIRE can express**, which is why this
  cannot be one transformer:

  - path and query params are ALWAYS text, so `[:id :int]` against `\"7\"` is
    the contract being honoured, not repaired — a query string has no other way
    to carry a number;
  - a JSON body carries real numbers and booleans, so `\"7\"` there is a client
    error, and coercing it would publish a contract the server does not require.

  Decoded IN PLACE rather than into a new key: a handler goes on reading
  `:path-params` and `:body` where it always did and finds them typed. A second
  home for the same values would be a second thing to teach and a second thing
  to disagree.

  The merge is what gets validated, so a key the contract requires and no
  carrier sent is refused once, naming the key rather than the carrier — the
  caller does not know which of the three we expected it in either.

  A nil schema passes every carrier through untouched: absence is not a
  violation, and decoding against a schema nobody wrote would be inventing one.

  **A refusal is a VALUE, never a throw.** The caller is a dispatcher holding an
  open request: it needs something to put in a 400 body, and an exception there
  becomes a 500 about the server for a fault that is the client's."
  [schema {:keys [path-params query-params body]}]
  (if (nil? schema)
    {:value {:path-params path-params :query-params query-params :body body}}
    (let [text (fn [m] (when m (m/decode schema m mt/string-transformer)))
          json (fn [m] (when m (m/decode schema m mt/json-transformer)))
          p    (text path-params)
          q    (text query-params)
          b    (json body)
          ;; path LAST: it was extracted from the URL the router matched, so it
          ;; is the carrier we are surest about. Overlap should not arise — a
          ;; key travels one way for a given endpoint — but a rule beats a
          ;; coincidence.
          merged (merge q b p)
          ;; CLOSED at the top level, and only there. `:rest/request` names what
          ;; the caller sends; a key it does not name is not something the
          ;; caller sends, and carrying it means an undeclared value crossed the
          ;; boundary this function exists to be. The measured case is sharper
          ;; than tidiness: a client leaking its OWN routing state into the
          ;; query string gets a correct response, renders correctly, and shows
          ;; up only in somebody else's access log.
          judged (closed-map schema)]
      ;; CLOSED at the top level, and only there. `:rest/request` names what the
      ;; caller sends; a key it does not name is not something the caller sends,
      ;; and carrying it means an undeclared value crossed the boundary this
      ;; function exists to be. The consuming case is sharper than tidiness: a
      ;; client leaking its OWN routing state into the query string gets a
      ;; correct response, renders correctly, and shows up only in somebody
      ;; else's access log.
      (if (m/validate judged merged)
        {:value {:path-params p :query-params q :body b}}
        {:error (str "request does not match the declared contract: "
                     (pr-str (me/humanize (m/explain judged merged))))}))))

(defn- arrived
  "The value a CONSUMER ends up holding, given what the handler returned and
  how this endpoint answers. Throws whatever serialization throws, so
  [[check-response]] can report an unserializable value as the contract problem
  it is.

  Two ways an endpoint can answer, and they arrive differently:

  - **ORDINARY** — the handler returns data and the adapter serializes it. The
    consumer's value is that data through a REAL JSON round trip, which is why
    this pays for one: a keyword arrives a string and a set arrives an array,
    and neither is visible in the in-image value.
  - **RAW** (`:http/raw`) — the handler serialized it ITSELF, so what arrives
    here is already the envelope. The consumer's value is that envelope DECODED
    by the declared media type. Round-tripping it through JSON would model a
    journey it never takes, and would turn an EDN document's keywords into
    strings on the way to a check about keywords.

  **This is where the envelope and the document stopped being one question.**
  `:rest/response` describes the DOCUMENT — which it always did for an ordinary
  endpoint. A raw one put the bytes under the schema instead, so `:string` was
  true of the envelope and silent about everything a consumer reads.

  A media type with no decoder leaves the value as it stands, and then
  `:string` is an honest description of an endpoint that really does answer
  text. `clojure.edn/read-string`, never `read-string`: this models a wire, and
  the plain reader evaluates tags."
  [value {:keys [media-type raw?]}]
  (if raw?
    (case (-> (str media-type) str/lower-case (str/split #";") first str/trim)
      "application/edn"  (edn/read-string (str value))
      "application/json" (json/parse-string (str value) true)
      value)
    (json/parse-string (json/generate-string value) true)))

(defn ^:export check-response
  "nil when `value` HONOURS `schema` for the consumer, else a teaching string.

  **Judged on what the client receives**, which is what [[arrived]] models —
  and for an ordinary endpoint that means a real serialize/parse. A contract is
  a promise to somebody who never sees Clojure data, so checking the in-image
  value answers a different question, and gets it wrong in both directions.
  Measured:

      {:x :foo}      vs [:map [:x :string]]        in-image INVALID,
                                                   arrives {:x \"foo\"} VALID
      {:tags #{\"a\"}} vs [:map [:tags [:set :string]]]
                                                   in-image VALID,
                                                   arrives [\"a\"] INVALID

  So an in-image check would 500 a response the consumer receives exactly as
  promised, AND pass one it receives broken. The second is the crossing this
  framework has recorded as blind since the row was written.

  `m/encode` through the json-transformer was the cheap idea and does not
  work — it leaves the keyword a keyword and the set a set. Nothing short of
  the round-trip answers the question, so this pays for one. That cost is the
  honest price of the promise: an app that would rather not pay it declares no
  contract, and then there is nothing to check.

  **`opts` says how the endpoint ANSWERS** — `{:media-type … :raw? true}` for
  one that serialized its own body. Optional, and absent means an ordinary
  endpoint, so every existing caller is unchanged. `slopp.http.dispatch` passes
  it from the route row, which is the only place that knows both.

  **A value that cannot be serialized is reported, not thrown.** The caller is
  a dispatcher holding a response it is about to send, and an exception here
  would turn a contract problem into a mystery 500 that never mentions the
  contract. A raw body that does not PARSE lands in the same arm, and for the
  same reason: an endpoint that declares EDN and emits something else has
  broken its contract in the one way a consumer cannot work around.

  A nil schema is an absence rather than a violation — see [[decode-request]]."
  ([schema value] (check-response schema value nil))
  ([schema value opts]
   (when schema
     (let [got (try {:ok (arrived value opts)}
                    (catch Exception e {:err (ex-message e)}))]
       (cond
         (:err got)
         (str "response cannot be read as the consumer would read it, so its"
              " contract cannot be honoured: " (:err got))

         (not (m/validate schema (:ok got)))
         (str "response does not match the declared contract once serialized: "
              (pr-str (me/humanize (m/explain schema (:ok got))))))))))
