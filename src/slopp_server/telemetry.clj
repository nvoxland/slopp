(ns slopp-server.telemetry
  "Where the HARNESS's telemetry crosses the transport: [[decode]] turns
  whatever shape a server handed the export body in into a batch or a
  refusal, and nothing else. The sink itself is the daemon's declared
  endpoint (`slopp-server.daemon/otel-endpoint`, one per machine), which routes
  each record to the project holding its thread; it used to be a route row
  on every project's own listener, and that listener is gone.

  The split from `slopp.otel` is the same one drawn everywhere else here:
  that namespace is PURE and knows the schema; this one knows the transport.
  Parsing lives there so it can be tested against a captured fixture with no
  server; recording lives in `slopp.ops` so a route never decides how a
  session mutates."
  (:require [cheshire.core :as json]))

(defn ^:export decode
  "An OTLP/HTTP JSON export body as `{:ok payload}` or `{:bad why}` —
  whatever shape the transport handed the body in (parsed data, a string,
  a stream, nothing). The one decision every sink shares: a body that
  parses is a batch, one that does not is the exporter's fault."
  [body]
  (let [parse (fn [s] (try {:ok (json/parse-string s true)}
                           (catch Exception e {:bad (or (ex-message e) "unparseable")})))]
    (cond
      (map? body)    {:ok body}
      (nil? body)    {:ok nil}
      (string? body) (parse body)
      ;; a server may hand the body over as a stream rather than a
      ;; string, and which one is the transport's business
      :else          (parse (slurp body)))))
