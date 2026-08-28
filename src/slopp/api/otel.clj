(ns slopp.api.otel
  "The one place the HARNESS's telemetry crosses into the store.

  It sits BESIDE the typed API rather than inside it, and the distinction is
  the whole reason this namespace exists separately from `slopp.api.endpoints`.
  Everything there is something this project PUBLISHES: a declared path, a
  contract in and out, a generated client, and a served list documented to stay
  short. This is the opposite shape — an intake for a schema slopp does not
  own, answering a fixed acknowledgement, for operators rather than consumers.
  Declaring it like an endpoint tripped three guards at once and each was
  right, so it mounts as an explicit route row on the listener instead.

  The split from `slopp.otel` is the same one drawn everywhere else here:
  that namespace is PURE and knows the schema; this one knows the transport and
  the session. Parsing lives there so it can be tested against a captured
  fixture with no server; recording lives in `slopp.ops` so a route never
  decides how a session mutates. What is left here is the boundary itself —
  decode, hand over, answer."
  (:require [slopp.otel :as otel]
            [slopp.ops :as ops]
            [cheshire.core :as json]))

(defn ^:export logs
  "Handle one OTLP/HTTP JSON log export — `POST /v1/logs`.

  **Deliberately not a DECLARED route.** `:rest/path` marks something this
  project publishes as its typed API: contract in, contract out, generated
  client, and a served list that is documented to stay short. This is none of
  those — it receives a schema slopp does not own, answers a fixed
  acknowledgement, and exists for operators rather than consumers. Declaring
  it dragged three guards in at once (an exact published-path set, a
  contract-coverage check, and the served-list agreement) and every one of them
  was right to fire.

  So it mounts as an explicit route row on the listener instead, which also
  lets it keep OTLP's own path — an exporter appends `/v1/logs` to whatever
  `OTEL_EXPORTER_OTLP_ENDPOINT` says, so owning that exact path is what makes
  the standard variable work with no per-signal override.

  **A body that parses is answered 200 even when it holds nothing we keep**;
  one that does NOT parse is answered 400. The distinction is about what an
  exporter does next: it RETRIES a 5xx, so throwing on an unreadable batch buys
  a redelivery of the same unreadable batch, forever. 400 is OTLP's
  non-retryable answer and ends it. An export carrying only event kinds we
  ignore is not an error at all — [[slopp.otel/api-requests]] skips them and
  the batch is simply empty.

  This threw a 500 until a malformed fixture caught it, which is the useful
  version of a broken test."
  [req]
  (let [session (:session (:http/deps req))
        body    (:body req)
        decode  (fn [s] (try {:ok (json/parse-string s true)}
                             (catch Exception e {:bad (or (ex-message e) "unparseable")})))
        r       (cond
                  (map? body)    {:ok body}
                  (nil? body)    {:ok nil}
                  (string? body) (decode body)
                  ;; a server may hand the body over as a stream rather than a
                  ;; string, and which one is the transport's business
                  :else          (decode (slurp body)))]
    (if (:bad r)
      {:status 400
       :http/raw true
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:partialSuccess {:errorMessage (str "could not parse this export: "
                                                   (:bad r))}})}
      (do (when session
            (ops/record-otel! session (otel/api-requests (:ok r))))
          {:status 200
           :http/raw true
           :headers {"Content-Type" "application/json"}
           :body "{}"}))))
