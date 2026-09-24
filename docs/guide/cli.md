# CLI and CI

The plugin puts a `slopp` script on your session PATH. It runs the same release
jar the MCP server runs, and it fetches and caches that jar (checksum-verified)
on first use.

!!! note "This is about driving slopp from a shell"

    If you want to *build* a command-line program with slopp, see
    [Command-line apps](cli-apps.md).

```sh
slopp <op> [json|edn|@file]             # one tool call, routed to the machine's slopp server
slopp server [port] | stop | status     # the slopp server: one slopp for the machine, yours to start
slopp dev [dir]                         # a project's dev instance on its own, re-served at every done
slopp --main slopp.sync/-main import .  # build a store from the repo's slopp branch
slopp --main slopp.sync/-main test .    # isolated suite from a store build
slopp --doctor                          # self-check the wiring end to end
```

The **pages** — the one address to remember when you run more than one slopp
project — are the slopp server's: `http://127.0.0.1:7357/` lists every open
project and `/p/<slug>` is one project's screens, served beside
`/api/projects/<slug>/` from the same process. They are built with slopp's
own components the way any project's app is, and reach a project only
through its published API; see
[the slopp server, and where the pages are](../reference/tools.md#the-slopp server-and-where-the-pages-are).

Without the plugin, `java -jar slopp.jar ...` takes the same arguments.

## One-shot tool calls

`--call` runs exactly one tool against the store in the current directory and
prints the result. Arguments are JSON, EDN, or `@file`:

```sh
slopp --call query_project
slopp --call test_run '{"external":true}'
slopp --call report '{"contains":"invoice"}'
slopp --call commit_point '{"description":"release 1.2","agent":"ci"}'
```

Every call routes to the machine's slopp server -- the one you started with
`slopp server`; with none answering, the call fails and says so -- and runs
on a session the slopp server keeps for the project in the current directory. A WRITE names its `thread` — the line it goes to — and a write
that names none is refused, naming `thread_open`; a script passes the same
id on every call so its writes share one line and its `done` lands them.
`agent` is an optional label and defaults to the thread. `--call <op>` is
the same call in its long-standing spelling.

This is the surface for scripts, CI steps, and for answering "how do I check
this myself" without an MCP client.

## Running your project

To have your own app running while you work on it — so you can open it in a
browser and watch it change as work lands — declare what to run:

```clojure
config_file {path "dev" key "run.app.main" value "shop.core/-main"}
config_file {path "dev" key "run.app.args" value "--port,8080"}
config_file {path "dev" key "run.app.url"  value "http://127.0.0.1:8080"}
```

slopp starts it in a dedicated image and re-serves it at every `done`. Declare
as many as you need — `run.worker.main`, `run.admin.main` — each under its own
name, each startable and silenceable on its own (`run.worker.enabled false`).

**The `dev` path never leaves the database.** It reaches no built tree and no
git projection, because what to run on a laptop is not a fact about the
program. `capabilities`, `rules` and `gates` still travel with the product.

There is no flag for this. Declaring an entry is the whole opt-in, and
freshness comes from the way slopp starts it rather than from something you
pass on a command line.

## Serving modes

These are about the slopp SERVER's own code, not about your app — a
distinction worth keeping, because one word covered both for a long time and
cost several confident wrong diagnoses.

There is only one: the slopp server — and any built app — loads its code once at
boot and serves it until restarted. There is no live/snapshot switch and no
flag; a snapshot is all a running process is.

Freshness of the IN-PROGRESS code is a property of a **dev instance**, not a
mode of the server. When a project declares a `dev` run entry, the slopp server
manages that entry as the project's app and **re-serves it at each `done`**,
booting it afresh from the store. So working on slopp itself, the machine
slopp server stays on its released jar while the in-progress version runs as the
project's dev instance and tracks every landed change.

That re-serve reloads the changed namespaces **plus everything that requires
them**, dependencies first — not thoroughness for its own sake. Clojure
evaluates a lot ONCE, at `def` time, and keeps the result: a response schema
referenced from a handler's metadata is a value captured when that `defn` ran.
Reload only what textually changed and the handler goes on publishing the old
schema forever, because its own source never moved; reloading its dependencies
first is what stops it re-capturing a value that is about to change.

The one layer nothing can reload is the boot kernel itself
(`slopp.kernel.boot`, `slopp.kernel.rt`) — it is the code doing the loading,
so a change there needs a rebuilt jar and a restart.

## CI

The usual shape for a slopp repo: checkout, import (or check out the `slopp`
branch directly), test.

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: slopp --main slopp.sync/-main import .
      - run: slopp --main slopp.sync/-main test .
```

Workflows live on the human-owned branch. GitHub only runs push-triggered
workflows from the pushed ref's tree, so reach the `slopp` branch with
`workflow_dispatch` or `schedule` plus `checkout ref: slopp`.

### What slopp's own CI runs

Four jobs, worth stealing the shape of:

- **test-files** -- the suite straight from the repo's files.
- **test-via-slopp** -- the pushed code imports *itself* into a fresh store,
  putting every namespace through every gate, then runs the store-built suite.
  This is the one that catches "it works in my warm image".
- **native-proof** -- a sample app built through slopp, compiled to a GraalVM
  native binary, executed.
- **release** -- manual dispatch with a version input: build the uberjar from
  the `slopp` branch, smoke it bare, tag it, attach it to a Release.

slopp itself ships as an uberjar rather than a native binary, because the store
loader compiles code at runtime by design. Apps *built with* slopp have no such
constraint -- `build {dir main}` emits a native-image recipe.

## Test tiers

Two faces, decided by tag on the test name:

- Plain and `^:integration` tests run **in-image** on every affected write.
- `^:external` tests spawn their own JVMs and temp directories. They run at
  done points (impacted ones only) and on demand via
  `test_run {external true}`, which materializes the store into a temp
  directory and runs the suite there.

`full_check` runs every tier over the whole store.
