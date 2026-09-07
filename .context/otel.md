# Claude Code telemetry — the OBSERVED schema

**Captured 2026-08-27 from Claude Code `2.1.250`**, by pointing a real headless
run at a local OTLP receiver. Everything below is transcribed from that
capture, not from documentation. Re-capture before trusting it against a
different CLI version — the attribute names are the product's, not a standard's,
and nothing warns you when they move.

Reproduce with:

```
OTEL_METRICS_EXPORTER=otlp OTEL_LOGS_EXPORTER=otlp \
OTEL_EXPORTER_OTLP_PROTOCOL=http/json \
OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14318 \
OTEL_METRIC_EXPORT_INTERVAL=2000 OTEL_LOGS_EXPORT_INTERVAL=2000 \
CLAUDE_CODE_ENABLE_TELEMETRY=1 \
claude -p "..." --strict-mcp-config --mcp-config '{"mcpServers":{}}'
```

`OTEL_*_EXPORTER=console` prints the same content as JS object literals with no
network at all — easier to eyeball, wrong shape to parse.

## Transport

Two POSTs, standard OTLP/HTTP JSON: `/v1/logs` and `/v1/metrics`.

```
resourceLogs[] → resource{attributes[]} + scopeLogs[] → scope + logRecords[]
```

`scope` is `{name "com.anthropic.claude_code.events" version "2.1.250"}`.
A logRecord is `{timeUnixNano observedTimeUnixNano body attributes[]
droppedAttributesCount}`; `body` is `{stringValue "claude_code.<event>"}`.

Attributes are the usual OTLP list of `{key, value}` where value is a
single-key map — `stringValue` / `intValue` / `doubleValue`. **`intValue`
arrives as a JSON number here, not the string the spec permits**; a parser must
accept both.

## The five metrics

```
claude_code.session.count       COUNTER  sessions started
claude_code.token.usage         COUNTER  unit "tokens"
claude_code.cost.usage          COUNTER
claude_code.active_time.total   COUNTER
com.anthropic.claude_code.events         (the events scope, not a metric)
```

`token.usage` data points carry `type` = `input` | `output` | `cacheRead` |
`cacheCreation`, plus `model`, `query_source`, `effort`.

## Event names seen in one trivial session

`user_prompt`, `api_request`, `assistant_response`, `hook_registered`,
`hook_execution_start`, `hook_execution_complete`, `plugin_loaded`.

## `api_request` — the one that carries the money

```
model                  "claude-opus-5"
input_tokens           2
output_tokens          4
cache_read_tokens      9987
cache_creation_tokens  7559
cost_usd               0.0806935
cost_usd_micros        80693
duration_ms            1475
request_id             "req_011Ce..."
client_request_id      uuid
speed                  "normal"
query_source           "sdk" | "main"
effort                 "high"
```

**CONTEXT SIZE is derivable and is not a field:**

```
context ≈ input_tokens + cache_read_tokens + cache_creation_tokens
```

For the trivial prompt above that is 2 + 9987 + 7559 = **17,548 tokens for a
four-token answer** — the cache-read amplification, visible in one request.
This is the signal slopp structurally cannot see for itself, and the reason
several context-aware ideas were blocked on this capture.

## Join keys

- **`session.id`** is the same value slopp already uses as its agent identity
  (`CLAUDE_CODE_SESSION_ID`, read in `slopp.mcp/-main` via
  `slopp.project.harness/conversation-id`). Telemetry rows and store deltas
  join on it with no new correlation.
- **`prompt.id`** brackets ONE user ask — the same boundary `turn-begin` /
  `turn-end` already mark. A turn's slopp-side cost and its model-side cost are
  therefore comparable per ask.
- `event.sequence` orders events within a session; `event.timestamp` is ISO-8601
  and `timeUnixNano` is a nanosecond string.

## Two things to know before piping this anywhere

- **`user.email`, `user.id`, `user.account_id`, `user.account_uuid` and
  `organization.id` are on EVERY record.** A receiver that persists raw
  telemetry persists the operator's email address. Anything slopp stores should
  drop them at the boundary rather than filter them later.
- **`assistant_response` carries `response: "<REDACTED>"` by default** — prompt
  and response bodies are not exported unless explicitly enabled. Do not build
  anything that assumes their content is available.

## What it does NOT carry

No tool-level records. There is no event for "a tool was called", so the
`slopp-ms` / `outside-ms` split in `slopp.read.telemetry/call-timing` stays the
only source for that, and OTEL cannot replace it — the two are complementary:
OTEL knows tokens and cost, the turn record knows which tool burned the wall
clock.

## Receiving it into the store

The daemon accepts OTLP log exports at **`/slopp/otel/v1/logs`** — the base
is ours, the `/v1/logs` tail is OTLP's standard path, so the standard
variable works with no per-signal override:

```sh
CLAUDE_CODE_ENABLE_TELEMETRY=1 \
OTEL_LOGS_EXPORTER=otlp \
OTEL_EXPORTER_OTLP_PROTOCOL=http/json \
OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:7357/slopp/otel
```

One endpoint per machine. The daemon routes each record by its harness
session id (the thread) to the project that session is attached to, and
remembers the dir of a session that has since detached, so an export that
arrives after a project closed still lands in the right store. Before the
daemon, every session's own listener took the export on a port derived from
its store dir, and a running host answered 404 until that listener was
re-served, because its route table was assembled at serve time; the daemon
mounts the route once, machine-wide. Metrics are not ingested: every field
that matters is on the `api_request` LOG record, and the metric stream would
be a second copy of the same numbers under different names.

Each accepted export appends one `:otel` delta carrying the normalized
requests. `slopp.otel/api-requests` does the normalizing, which is where the
identity attributes are dropped — records are built from a named list of wanted
keys, so a new `user.*` attribute in a future CLI is ignored by default rather
than persisted until somebody notices.

### Three decisions worth not re-litigating

- **It is NOT a declared route.** `:rest/path` marks the typed API this project
  publishes — contract in, contract out, generated client, and a served list
  documented to stay short. Declaring the receiver tripped three guards at once
  (the exact published-path set, contract coverage, and served-list agreement)
  and every one was right: a foreign-schema intake is not something this
  project publishes. It mounts as an explicit route row in
  `api.server/serving-opts` instead, which is also what lets it keep OTLP's
  path rather than being forced under `/api/`.
- **A body that parses is 200 even when empty; one that does not is 400.** An
  exporter RETRIES a 5xx, so throwing on an unreadable batch buys the same
  unreadable batch on every interval, forever. 400 is OTLP's non-retryable
  answer. This threw a 500 until a malformed test fixture caught it.
- **`:otel` is a registered marker** (`slopp.store.fields/markers`). An
  unregistered op makes `merge-logs` refuse the WHOLE merge, so telemetry could
  otherwise stop everyone on a branch from landing. It does not travel across
  merges: telemetry describes a session on one line, and replaying it elsewhere
  would attribute one agent's cost to another.
