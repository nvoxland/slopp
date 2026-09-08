<!-- reference topic `running` — served whole by `help {topic "running"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## Running the project so a human can watch it

**When someone asks to "see it running", "start the app", or "give me a URL",
this is the answer.** A slopp project declares what it wants RUN while
somebody is working on it, and slopp keeps that running and refreshes it at
every `done`.

Declare it once, in the `dev` config path:

```clojure
config_file {path "dev" key "run.app.main"  value "shop.core/-main"}
config_file {path "dev" key "run.app.args"  value "--port,8080"}
config_file {path "dev" key "run.app.url"   value "http://127.0.0.1:8080"}
```

That is the whole setup. From the next `done`, slopp starts `shop.core/-main`
with those arguments in a dedicated child image and re-serves it whenever work
lands.

| key | what it says |
|---|---|
| `run.<name>.main` | the entry fn. The NAME is the key's own middle segment — `run.admin.main` declares `admin` |
| `run.<name>.args` | arguments, comma-separated and **in order** (`--port,8080` → `["--port" "8080"]`) |
| `run.<name>.url` | where a human should open it — see below, this is DECLARED |
| `run.<name>.enabled` | `false` silences one without deleting its entry point |

**Named, because projects grow a second process.** A worker, an admin port, a
scheduler — declare each under its own name and they all start. There is no
anonymous single entry to redesign later.

### Four things worth knowing before you set this up

**`dev` never ships.** It is in `slopp.store/local-config-paths`, so it
reaches neither a built tree nor a git projection. A port a developer chose
stays that developer's business; `capabilities`, `rules` and `gates` still
travel with the product as they always did. This is why the port belongs here
and not in `http.port`.

**The URL is DECLARED, not observed.** For a project with no `dev` entries
slopp GENERATES the `serve!` call and reads the bound port back, so it knows
the address. A declared entry is an arbitrary function and hands nothing back,
so if you want a human to be given a link, say what it is. Absent is honest
for a worker.

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

`slopp daemon [port]` runs ONE slopp process for every project on the box
(default port 7357, `SLOPP_DAEMON_PORT` overrides). It binds first and
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

It records itself in `~/.slopp/daemon.json` (`url pid token`); `slopp
<op>` and the prompt hook route there, and `slopp <op>` starts a daemon
when that file names nothing alive. A second `slopp daemon` on the same
port refuses with the bind diagnosis. The daemon boots slopp's OWN code from the dir it
is given, so the verb passes a neutral one (`~/.slopp`); from a checkout of
slopp itself, `SLOPP_LIVE=1 SLOPP_DAEMON_DIR=$PWD slopp daemon` hot-reloads
the daemon's tooling as the store changes. A write through the door with
no `thread` is refused, like a one-shot; a project's dev app server is
started once by the daemon, on the first branch attached, and every
session's `done` refreshes that one server.

**This is the only server.** The plugin's stdio entry is a PIPE onto the
daemon — `bin/slopp-pipe.py`, one small process per session instead of a
JVM — which starts a daemon if none answers, names the project by its cwd,
and re-attaches by itself after a daemon restart or an idle reap. Nothing
about the tools changes; subagents share the pipe as they shared the
server. The per-session JVM is retired (2026-09-08): there is no
`SLOPP_DAEMON=0`, no stdio loop in the jar, and `java -jar slopp.jar <dir>`
starts a daemon. A shell call (`slopp <op> '{…}'`) routes to the daemon
too, starting one if none answers — the one-shot JVM that used to open the
store on its own is gone with the writes it stranded.

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
