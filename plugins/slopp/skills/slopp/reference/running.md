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

**Starting is not health.** A declared entry reports `:started` once its
namespace loads and its thread spawns. An entry that throws on its second line
reports `:started` and is dead. Do not read it as "the app is up"; open the
url, or drive it.

### A project that is not a web project

`http.enabled` answers *is this a web project*. It cannot answer *does this
project want a worker running*, so a declared entry is reason enough on its
own — a CLI project with a background job needs no `http` capability to have
that job running while somebody works on it.

