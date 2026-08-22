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

  Neighbours: `slopp.web.dispatch` calls these through the context and never
  requires this namespace — which is what keeps malli out of an HTML app that
  enabled no typed API."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [cheshire.core :as json]))

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
          merged (merge q b p)]
      (if (m/validate schema merged)
        {:value {:path-params p :query-params q :body b}}
        {:error (str "request does not match the declared contract: "
                     (pr-str (me/humanize (m/explain schema merged))))}))))

(defn ^:export check-response
  "nil when `value` HONOURS `schema` for the consumer, else a teaching string.

  **Judged on what the client receives, which means a real serialize/parse.**
  A contract is a promise to somebody who never sees Clojure data, so checking
  the in-image value answers a different question — and gets it wrong in both
  directions. Measured:

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

  **A value that cannot be serialized is reported, not thrown.** The caller is
  a dispatcher holding a response it is about to send, and an exception here
  would turn a contract problem into a mystery 500 that never mentions the
  contract.

  A nil schema is an absence rather than a violation — see [[decode-request]]."
  [schema value]
  (when schema
    (let [arrived (try {:ok (json/parse-string (json/generate-string value) true)}
                       (catch Exception e {:err (ex-message e)}))]
      (cond
        (:err arrived)
        (str "response cannot be serialized for the wire, so its contract"
             " cannot be honoured: " (:err arrived))

        (not (m/validate schema (:ok arrived)))
        (str "response does not match the declared contract once serialized: "
             (pr-str (me/humanize (m/explain schema (:ok arrived)))))))))
