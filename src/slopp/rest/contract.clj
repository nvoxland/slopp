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
  "Decode `value` to the types `schema` declares, then judge it —
  `{:value v}` or `{:error <teaching string>}`.

  **Decoding is not a nicety, it is the difference between a contract that can
  be honoured and one that always fails.** A request arrives as JSON and path
  and query params arrive as text, so a contract saying `[:map [:id :int]]`
  describes a value the wire cannot carry: `\"7\"` is what shows up. A boundary
  that only VALIDATED would refuse every correct request against every typed
  contract, which is why nothing here validated before this existed.

  So the schema is read in both directions: `malli.transform`'s
  json-transformer decodes what the wire can express into what the author
  declared, and only then is the result judged. The handler receives typed
  data having written no parsing — that is the whole of what this capability
  gives an author, and it is also why the parsing cannot be re-implemented per
  endpoint and get it slightly different each time.

  **A refusal is a VALUE, never a throw.** The caller is a dispatcher holding
  an open request: it needs something to put in a 400 body, and an exception
  there becomes a 500 about the server for a fault that is the client's.

  **A nil schema passes the value through UNTOUCHED.** A `:get` declares no
  `:web/request` and has no body; that is an absence, not a violation, and
  decoding against a schema nobody wrote would be inventing one. Same
  distinction `:cli/args [:catn]` draws on the other side — \"takes nothing\"
  and \"never said\" are different statements."
  [schema value]
  (if (nil? schema)
    {:value value}
    (let [decoded (m/decode schema value mt/json-transformer)]
      (if (m/validate schema decoded)
        {:value decoded}
        {:error (str "request does not match the declared contract: "
                     (pr-str (me/humanize (m/explain schema decoded))))}))))

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
