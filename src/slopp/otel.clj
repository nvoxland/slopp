(ns slopp.otel
  "What the HARNESS knows and slopp cannot: tokens, cost, and how full the
  conversation is.

  slopp measures its own side of a session well — `slopp.read.telemetry` folds
  per-turn wall time into slopp-time, outside-time and idle. What it can never
  see from inside an MCP server is the conversation its payloads land in: how
  many tokens a request carried, what it cost, whether the context is nearly
  full. Those facts exist only in the agent's harness, and Claude Code emits
  them as OpenTelemetry.

  So this namespace is a READER of someone else's schema, and that shapes it:
  the attribute names here belong to a product rather than a standard, and
  nothing warns you when they change. Everything is parsed from a payload
  passed in — no transport, no IO, no globals — so the mapping is testable
  against a captured fixture and a version bump fails a test rather than a
  server.

  Two facts are worth knowing before using it. **Context size is DERIVED**
  (`input + cache-read + cache-creation`) because the CLI sends no such field,
  and it is the quantity that blocked several context-aware ideas. And **every
  record carries the operator's email address**, so records are built from a
  named list of wanted keys rather than by removing unwanted ones — a new
  identity attribute in a future version is then ignored by default instead of
  persisted until somebody notices."
  )

(defn ^:export attr-value
  "The Clojure value inside one OTLP attribute `value` map, or nil.

  OTLP wraps every attribute in a single-key map naming its type. Four shapes
  matter here: `:stringValue`, `:intValue`, `:doubleValue`, `:boolValue`.

  **`:intValue` is accepted as a NUMBER as well as a STRING, and that is not
  defensiveness.** The spec permits int64 to be encoded as a string, because
  JSON has one number type and cannot carry int64 exactly; Claude Code 2.1.250
  was observed sending a plain number. A parser written to either encoding
  alone reads every token count as nil on the other, and a nil token count is
  indistinguishable from a request that used none — the wrong answer arriving
  silently, which is the failure this whole namespace exists to avoid.

  Unknown shapes (`:bytesValue`, `:arrayValue`, `:kvlistValue`) answer nil
  rather than a guess: nothing here needs them, and inventing a reading for a
  shape nobody has seen is how a schema drifts without anyone noticing."
  [v]
  (when (map? v)
    (cond
      (contains? v :stringValue) (:stringValue v)
      (contains? v :doubleValue) (:doubleValue v)
      (contains? v :boolValue)   (:boolValue v)
      (contains? v :intValue)    (let [i (:intValue v)]
                                   (if (string? i) (parse-long i) i))
      :else nil)))

(defn ^:export api-requests
  "The `api_request` records inside a decoded OTLP `/v1/logs` payload, as
  `{:session :prompt :model :input :output :cache-read :cache-creation
    :context :cost-usd :duration-ms :effort :query-source :at-ns :sequence}`.

  One record per model round trip — the unit that costs money. `session.id`
  joins straight onto slopp's own agent identity and `prompt.id` brackets one
  ask, so a turn's model cost and its tool cost are comparable without any new
  correlation.

  **`:context` is DERIVED and is the point of this function.** The CLI sends no
  context-size field, but every request ships the whole conversation split into
  what was cached and what was not, so
  `input + cache-read + cache-creation` is it. That is the quantity slopp
  structurally cannot observe for itself — the MCP server sees its own payloads
  and never the conversation they land in — and several context-aware ideas
  were blocked on exactly this number being unavailable.

  **The identity attributes are dropped by CONSTRUCTION, not filtered.** Every
  record carries `user.email`, `user.id`, `user.account_id`,
  `user.account_uuid` and `organization.id`. This builds the map from a named
  list of wanted keys, so a new identity attribute in a future CLI version is
  ignored by default rather than persisted until someone notices. A denylist
  would have the opposite failure.

  Unknown event kinds are skipped rather than partially read: six other event
  names share this envelope and none of them carries these fields."
  [payload]
  (for [rl (:resourceLogs payload)
        sl (:scopeLogs rl)
        lr (:logRecords sl)
        :when (= "claude_code.api_request" (get-in lr [:body :stringValue]))
        :let [a (into {} (map (juxt :key (comp attr-value :value)))
                      (:attributes lr))
              n (fn [k] (get a k))
              in-t  (or (n "input_tokens") 0)
              cr    (or (n "cache_read_tokens") 0)
              cc    (or (n "cache_creation_tokens") 0)]]
    {:session      (n "session.id")
     :prompt       (n "prompt.id")
     :model        (n "model")
     :input        in-t
     :output       (n "output_tokens")
     :cache-read   cr
     :cache-creation cc
     :context      (+ in-t cr cc)
     :cost-usd     (n "cost_usd")
     :duration-ms  (n "duration_ms")
     :effort       (n "effort")
     :query-source (n "query_source")
     :sequence     (n "event.sequence")
     :at-ns        (:timeUnixNano lr)}))
