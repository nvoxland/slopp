<!-- reference topic `running` — served whole by `help {topic "running"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## Running the project so a human can watch it

**When someone asks to "start the dev server", "see it running", "start the
app", or "give me a URL", this is the answer** — and the first call is
`session_brief`: `:app` is the running instance's url, and when there is
none `:app-note` says WHY (nothing declared, declared but not started, boot
failed, or a declaration that exists only as a blobbed file — see
`:config-blobbed`). Read that before touching anything; the answer is
usually one config write or one daemon restart away.

A slopp project declares what it wants RUN while somebody is working on it,
and slopp keeps that running and refreshes it at every `done`. The
declaration is the `app.main` CAPABILITY — the same entry a built app runs:

```clojure
config_file {path "capabilities" key "app.main"  value "shop.core/-main"}
```

A project that serves HTTP (`http.enabled`) and declares no entry gets the
server slopp derives instead. Either is reason enough for a dev instance;
`app.main` is what a worker, a scheduler, or slopp's own daemon declares.

**The `dev` config is an OVERLAY of the capability keys for the dev
instance**, in two layers:

| path | what it is | where it goes |
|---|---|---|
| `dev` | the project's SHARED dev setup — `config_file {path "dev" key "http.port" value "7358"}` moves the dev instance off the production `http.port`; any capability key works the same way | projects to git with the code, so a clone serves without a step; never into a built jar |
| `dev.local` | THIS machine's override of `dev`, same keys — a port this box has free, an entry point being tried | stays in the db; reaches no tree |

Precedence, highest first: a per-process `SLOPP_DEV_<KEY>` env override
(`SLOPP_DEV_HTTP_PORT=7360`), then `dev.local`, then `dev`, then the
capability's own value. Both paths validate at the write against the
capabilities registry, so a mistyped key refuses rather than governing
nothing. Keep `dev` COMPLETE — it is what anyone who clones the project gets,
and the normal setup should need no local override at all.

**This is why the dev port is a `dev` setting and not `http.port`:**
`http.port` is the PRODUCTION address (for slopp's own store, the machine
daemon's), and the in-progress copy must not take it.

### Four things worth knowing before you set this up

**ONE process per project.** A built app is one jar and one `-main`; the dev
supervisor starts that one entry. (`run.<name>.*` entries are retired —
declare the entry as `app.main`.)

**The URL is DECLARED or DERIVED, never observed.** For a project with no
`dev` entries slopp GENERATES the `serve!` call and reads the bound port back,
so it knows the address. A declared entry is an arbitrary function and hands
nothing back, so slopp derives `http://<host>:<port>/` from the dev-overlaid
`http.host`/`http.port`. A worker with no port has no url, and absent is
honest.

**What the manager tells a declared entry.** Before any entry runs, the child
is handed what only the manager knows, as system properties: `slopp.managed-for`
(the store dir it is the declared entry OF), `slopp.static-dir` (where the
mounts' bytes were materialized) and `slopp.app-port` (the dev-overlaid
port). A process that finds itself
`slopp.managed-for` a store never manages that store's app server — it would
be booting a child of itself onto its own port.

**Declared REPLACES derived.** A store with `http.enabled` and no declared
entry gets the server slopp derives, exactly as before. Declare one and slopp
stops generating that call — otherwise the reader would get two servers, one
at an address nobody gave them.

**What actually re-serves, and when nothing does.** The refresh runs at each
`done` point — that grain is deliberate, because mid-episode the store is
intentionally incomplete and a browser reloading into a half-written red state
teaches its reader to ignore it. But it runs only for a store slopp MANAGES,
and that is two conditions, not one:

- the project declares `http.enabled`, and
- this process does not ALREADY serve everything the store would.

Fail either and `done` does not re-serve — it STOPS a managed server it had
started, on purpose, and says so. So a browser-only project that never
declared `http.enabled` is never managed, and no number of `done` calls will
move what a browser is showing.

**A server is only ever STARTED for a store slopp manages**, because the first
serve goes through the same call as every later one — deliberately, so a start
that differed from a swap could not drift where nobody looks. That has a
consequence worth reasoning from: **if a server is running and slopp started
it, the store was managed at boot.** "It starts one and then declines to keep
it current" is not a state this code can reach.

**So a `:behind` figure that only rises has two possible causes, and they are
told apart by whether a server is running at all:**

- **Nothing is serving** (or it was stopped and said so): the store is not
  managed. Check `http.enabled` and `self-served?`. No number of `done` calls
  will move a browser, and the second one is not going to work either.
- **Something IS serving and is stale**: the store is managed and the refresh
  is FAILING. Do not reach for the managed-ness question — it is already
  answered by the running process. Look for a re-serve that could not
  complete: a bind clash with an orphaned child from a previous session
  holding the port, an image that would not reload, or the 20s wait expiring.
  `done` reports each of those; `session_brief` carries the number.

A restart zeroes the counter in BOTH cases, which is why it proves nothing
about which one you had.

**Starting is not health.** A declared entry reports `:started` once its
namespace loads and its thread spawns. An entry that throws on its second line
reports `:started` and is dead. Do not read it as "the app is up"; open the
url, or drive it.

### A project that is not a web project

`http.enabled` answers *is this a web project*. It cannot answer *does this
project want a worker running*, so a declared entry is reason enough on its
own — a CLI project with a background job needs no `http` capability to have
that job running while somebody works on it.

### One slopp for the machine: `slopp daemon`

`slopp daemon [port]` runs ONE slopp process for every project on the box.
Its port is the argument, else `SLOPP_PORT`, else 7357 — ONE knob,
because the plugin's MCP entry is a URL Claude Code expands from the
environment, and a settings file would be a second source it cannot read.
It binds first and
loads nothing until something attaches; a project opens on its first
attachment and closes on its last. Its typed surface is under one prefix,
and the pages a human opens sit beside it:

| path | what |
|---|---|
| `GET /api/projects` | the registry: every open project — slug, dir, sessions, `:app {:url :branch}` |
| `GET /api/status` | the daemon about itself |
| `POST /api/projects/<slug>/mcp` | MCP over streamable HTTP; `X-Slopp-Dir: <absolute dir>` names the project on `initialize` |
| `POST /api/projects/<slug>/call` | the write door `slopp <op>` uses: `{tool arguments token}`; slug `_` + `X-Slopp-Dir` resolves by dir |
| `GET /api/projects/<slug>/<resource>` | the project's typed read API, mounted: `/api/<resource>` in its own contract, with the mount replacing that prefix rather than nesting under it |
| `POST /api/otel/v1/logs` | the one telemetry sink: `OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:7357/api/otel` |
| `GET /` | the picker: every open project, linked |
| `GET /p/<slug>/**` | that project's pages — timeline, change review, form permalinks, the namespace index — a browser app over the mounted API; `session_brief` reports it as `:pages`; 404 off the declared page table |
| `GET /css/style.css`, `GET /assets/**` | the stylesheet and the compiled bundle, from the daemon's own store under `--live` or from the jar |

It records itself in `~/.slopp/daemon.json` (`url pid token`, readable by
its owner only — the token is the write door's secret); `slopp
<op>` and the prompt hook route there. **The daemon is the user's to start
and stop** (`slopp daemon`, `slopp daemon stop`): nothing else starts one,
and a call or a session with nothing live on the configured port fails with
a sentence saying to start it — a server nobody started is a server nobody
knows to stop, look at, or upgrade. A second `slopp daemon` on the same
port refuses with the bind diagnosis. The daemon boots slopp's OWN code from the dir it
is given, so the verb passes a neutral one (`~/.slopp`). A write through the door with
no `thread` is refused, like a one-shot; a project's dev app server is
started once by the daemon, on the first branch attached, and every
session's `done` refreshes that one server.

**This is the only server, and the plugin talks to it directly.** The
plugin's MCP entry is the daemon's URL
(`http://127.0.0.1:${SLOPP_PORT:-7357}/api/mcp`, the
project named by an `X-Slopp-Dir: ${CLAUDE_PROJECT_DIR}` header Claude Code
expands — the door is slug-free) — no process per session at all. A session id the daemon no
longer holds (a restart, an idle reap) is re-attached UNDER THAT ID when
the request names its dir, so a daemon restart costs one late answer, not
a reconnect. The hooks and `slopp <op>` are one `curl` each onto the
daemon's `hook` and `cli` doors; the rules they used to carry (which ask to
record, which Bash command routes around the store, how a heredoc splits
into steps) live in `slopp-server.process.hooks`, tested. The plugin needs bash,
curl and java, nothing else. The per-session JVM is retired (2026-09-08):
there is no `SLOPP_DAEMON=0`, no stdio loop in the jar, and `java -jar
slopp.jar <dir>` IS `slopp daemon`. A shell call fails without a daemon —
the one-shot JVM that used to open the store on its own is gone with the
writes it stranded.

**Working ON slopp is working through a released slopp.** The machine daemon
is a release; slopp's own checkout is a project like any other, and its dev
config declares the in-progress version as a dev instance: `app.main =
slopp-server.process/-main`, with `http.port` overlaid to 7358 in the `dev`
overlay. The machine daemon boots that
child from the store, refreshes it at every `done`, and the child — told its
role — serves whatever attaches to IT and never manages its own project's app
server. To drive the in-progress version, give a second agent
`SLOPP_PORT=7358`: the MCP url, the hooks and the CLI then all name
that port (the record is `~/.slopp/daemon-7358.json`) and never START a
daemon there — a dev instance is the machine daemon's to run. A new release replaces the base;
until one is cut, a jar built from a commit point is the base. Two daemons of
different versions will hold one store, so the store format must stay readable
by the previous release, or the release ships first.

Under the daemon three things are shared that used to be per session. Your
oracle image boots on the FIRST call that needs one (an eval, a write, a
test run), not at start — reads never boot it — and past
`SLOPP_DAEMON_MAX_IMAGES` (default 6 across the machine) that boot is
refused with the fix named while reads keep answering. A `full_check` at
the same content as one already running on the project JOINS it
(`:joined true`) and stands on your line afterwards. And what another
session lands on your project reaches you as ONE leading line on your next
answer (`;; since your last call: …`): your view followed if your thread
was idle, your `done` rebases otherwise. That line is the whole of push —
an MCP notification never reaches the model, so nothing is sent that way.
