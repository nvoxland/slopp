# Developing slopp

Working **on** slopp itself. For working **through** slopp — authoring code in
a store — read `plugins/slopp/skills/slopp/SKILL.md`, or the published
[docs site](#documentation-site).

## The one thing to understand first

**The working tree is fileless.** slopp's own code, system and tests both,
lives in `.slopp/store.db`. There are no project `.clj` files to edit. Only
these are real files:

- `deps.edn` — the kernel's own dependency coordinates. Project code declares
  its deps in the store manifest (`deps_add`); `build!` generates a project
  `deps.edn` from that.
- `build.clj` — the uberjar recipe.
- Docs, CI config, this file, and everything else humans own.

**`src/slopp/kernel/boot.clj` and `src/slopp/kernel/rt.clj` are NOT in that list**, though
they sit on disk and look like it. The boot kernel lives in the store with
everything else; `build!` materializes it into `target/jar-src/src` and the
uberjar is built from THERE, so those two files are projections. Edit them
with the MCP tools. A hand-edit on disk is overwritten by the next `build!`
and produces the exact signature of a stale jar — your fix runs nowhere and
nothing says why. (`deps.edn` puts `src` on the classpath, so a plain REPL
from a checkout does load the disk copy; that is the only thing it is for.)

A slopp server runs a snapshot with no live-reload of its own code
(`D-no-daemon-live`). That is why development does not happen against the
server you installed: it happens against the checkout's DEV INSTANCE, a
second slopp booted from this store and refreshed at every `done` — see the
next section. The installed server only needs a rebuilt jar when the base
itself must move (a kernel change, a store format the release cannot read);
the kernel is special only in that it is the code doing the loading, so it
must come from the jar to exist at all.

**"Restart" means two different events, and the `restart` TOOL is the one that
does NOT pick up a new jar.** Worth stating because a consumer spent ten
minutes on the distinction, having done everything right:

| | what it does | picks up a new jar? |
|---|---|---|
| the `restart` **tool** | rebuilds the live/verification image INSIDE the running JVM and reloads the store's forms — this is what clears `:oracle-drift` | **no** |
| a **process** restart | kills and relaunches `java -jar …/slopp.jar` | yes |

A running JVM holds the classes it loaded at boot, so a newer jar on disk is
invisible to it forever. This matters most for a CONSUMER of the jar: every
slopp TOOL runs from it, so a fix that is real in your store and verified live
is still absent from `generate_client`, `done`, or any other tool until the
process that hosts them is relaunched. The store is hot; the toolchain is not.

All code changes go through slopp's MCP tools. There is no file-to-store
reconciliation by construction, so a hand-edited `.clj` would simply be
ignored.

## Prerequisites

- **Java 21+** and the **Clojure CLI**. `mise.toml` pins temurin-21 and
  clojure 1.12.5 — `mise install` picks both up.
- **Docker**, only if you want to preview the docs site.
- **curl**, which the plugin's hooks and CLI use to reach the slopp server (python3 only for the dev scripts under `bin/`).

`mise.toml` also sets `SLOPP_CLOJURE=clojure`: `slopp.image.repl` probes homebrew
paths for the owned-image launcher before trusting PATH, and forcing the bare
name makes child images inherit mise's pinned clojure.

## Developing slopp with slopp

slopp develops itself the way any project does — through a running slopp —
with one twist: the code under development IS a slopp. So the slopp you work
through is this checkout's **dev instance**: a slopp server booted from this
store, re-served at every `done`, on the ordinary port, 7357. Two commands:

```sh
slopp dev .     # in one terminal: boots the dev instance from the store and keeps it current
claude          # every agent, here or in any other project: attaches to it like any slopp server
```

`slopp dev` reads the store's own declaration of its instance (`app.main =
slopp-server.process/-main` and `http.port 7357` in `capabilities` — both
project to git, so a clone has them), boots it in a child JVM, prints its
address, and then watches the store: each `done` that lands on main is
pushed into the running child when the changed namespaces allow it, and
reboots it when they do not. It runs until you stop it. There is no machine
server in the loop, no registry, and nothing has to attach to make the
instance come up.

The instance IS the machine's slopp server while it runs: every agent on the
box attaches to it and every other project's dev instance is managed by it,
all on the in-progress code. In this checkout, an agent's reads, writes,
oracle, `screen` and the pages at `http://127.0.0.1:7357/p/slopp` run the
code in this store, and `session_brief`'s `:app-note` says so. A fix to a
tool you are using is in the tool at your next `done`. A reboot of the
instance is a few seconds in which 7357 does not answer; sessions re-attach
on their next request.

`slopp server` — a released slopp on the same port — is the alternative for
a box that should not run in-progress code: stop the runner first, since the
two must not both serve 7357. To keep both up, give the dev instance another
port for this machine only: `config_file {path "dev.local" key "http.port"
value "7358"}`, and start agents for this checkout with `SLOPP_PORT=7358`.

What you see when it comes up is the instance's OWN startup banner — the
child's output is relayed to your terminal — followed by one line from the
runner:

```
slopp server: http://127.0.0.1:7357/api/ (pid 20317) — address + token in /Users/you/.slopp/server.json
slopp dev: serving /Users/you/src/slopp — re-served at every landing
```

The pages are at `http://127.0.0.1:7357/p/slopp` once an agent has attached.
Every later line the child prints — a landing re-served, a boot that failed
and left the previous version answering — arrives the same way.

### Bootstrapping from a local jar instead of the release

`slopp dev` runs as ITS OWN code — the runner, the kernel that loads the
store — from the jar the launcher fetched: the pinned release. The project
being served is a separate thing and is always this store, current at every
landing. So a change to slopp that lands in the store reaches the *instance*
at once and the *runner* only at the next release. When you need the runner
itself to carry a change — a kernel change, a change to the runner or the
launcher, a dependency change, a store format the release cannot read — build
a jar from a commit point and run the launcher against it:

```sh
slopp --call build '{"dir":"'$PWD'/target/jar-src"}'   # materialize the FILELESS store (or the build MCP tool)
clojure -T:build uber                                  # -> target/slopp.jar, refusing a stale materialization

SLOPP_JAR=~/src/nvoxland/slopp/target/slopp.jar ~/.claude/skills/slopp/bin/slopp dev .
```

`SLOPP_JAR` is honoured by the plugin's `bin/slopp` and skips the fetch and
the checksum. The path to the launcher is spelled out because a plain
terminal has no `slopp` on PATH: Claude Code puts a plugin's `bin/` on the
PATH of the shells it runs, and `~/.claude/skills/slopp` is where this
machine's plugin is loaded from (a symlink to a checkout's `plugins/slopp`).
Stop the running instance first; the new runner takes the same port.

Never do this as part of ordinary development, which the instance already
tracks. `session_brief`'s `:host` section says what jar the process you are
talking to was built from and how far behind the store it is.

From a checkout with no server at all, `clojure -M -m slopp.kernel.boot .`
boots slopp from the store and serves it once, with no refresh of its own
code — useful for a smoke test, not for development.

**Startup is async (concurrent sessions).** The MCP server completes its
`initialize` handshake as soon as the store VALUE loads and boots the image
(the child JVM that loads every namespace — the slow part) on a background
thread (`open!`'s `:slopp.ops/async-image?`, on for the server). Read-only
store tools serve immediately; oracle and write tools `await` the boot on
first use. This is what keeps a second session on the same store dir from
racing the client's MCP connect timeout while the first session is busy (e.g.
mid-`full_check`) — the store is SQLite-WAL + append-CAS multi-process by
design, so two concurrent sessions share it and each picks up the other's
commits (via journal sync, not a code reload — there is none). If a startup still fails under heavy load, bump `MCP_TIMEOUT` (ms)
in `.claude/settings.json`.

### Building the jar

**`uber` alone REFUSES rather than shipping a stale jar**, and writes nothing
when it does. It bundles whatever is under `target/jar-src/src`, which if you
skip the materialize step can be days old; if `.slopp/store.db` is newer than
that tree, the build stops before touching `target/slopp.jar`, names the head
and both timestamps, and gives you the two commands. `:stale true` jars it
anyway — legitimate for reproducing an old artifact or bisecting — and prints
that it did.

This was a WARNING plus exit 0 until 2026-08-23, and it failed exactly as a
warning does: it printed, and the invariant part of the output — "built
target/slopp.jar", exit 0 — is what got read. The consumer who caught it said
it best: *an exit code that cannot distinguish "built what you meant" from
"built something" is the same thing as a check whose output cannot vary.*

**Refusing BEFORE the write is the load-bearing half**, not failing after.
Nothing downstream reads an exit code — a restart reads the file, a consumer's
pre-flight reads `META-INF/slopp/head.edn`, this task's own success line names
a path — so a non-zero exit that left a plausible jar behind would convert a
loud failure into a silent one. A missing new jar is unambiguous; a present
wrong one is not.

Two other things still report it, and the second is the one that matters:

- `uber` PRINTS the head it is jarring (`build!` writes
  `src/META-INF/slopp/head.edn`), refusal or not.
- **the running process reports it back** — `session_brief`'s `:host :jar
  {:head :behind}`. That question is almost never asked while building; it is
  asked two days later by whoever is wondering why a fix they can see in the
  store is not in the tool.

The old check was `ls -la` plus `unzip -p … | grep` for a symbol, and it was
expensive AND wrong once: mtime and size agreed with a build that had not
finished writing. A head id cannot race that way.

Note `src` specifically, not the whole materialization. `test/`, `cljs-src/`
and `instruments/` are siblings of `src/` in that tree and none of them is
jarred — which is exactly how `module_role :instrument` keeps `slopp.lab` out
of the jar (`D-module-role`): the role moves the file, and this line is the
build script that has never heard of a role.

**The slopp server is a release; this checkout is a project** (`D-release-base`,
2026-09-12). The setup above is the whole of that decision in practice:
the base is the jar `slopp dev` runs as its own code, and
everything an agent develops lives in the dev instance it serves until a
release moves the base.

Rebuild the jar for kernel or dependency changes, and to cut a base: `uber`
builds aside and atomically renames, so a live process keeps its old jar inode
and the next launch gets the new one. Note `slopp.kernel.boot` is file AND
store namespace (like `slopp.kernel.rt`) and the jar bundles the STORE copy —
kernel edits go to both. session_brief's `:host` section states what the
server is actually running.

### Profiling the server — which side of the wire

"slopp is slow" has two possible homes, and the transcript cannot tell them
apart: a tool call's span there is the harness's view and includes whatever
the harness does around the call. Measure the server alone first:

```sh
bin/mcp-roundtrip.py /path/to/project
jcmd <slopp server pid> JFR.start filename=out.jfr        # profile the slopp server from outside, if it was the cost
jfr print --events jdk.ExecutionSample out.jfr
```

It speaks MCP over HTTP to the slopp server's endpoint — exactly what Claude
Code's plugin entry does — and prints each call's round trip. On 2026-09-03 the transcripts showed a ~1.3 s floor under every
slopp call while this measured 0.00–0.38 s for the same ops: the gap was
Claude Code's auto-mode permission classifier (a model call per unallowed
MCP tool call), fixed by allowing the server in `.claude/settings.json`,
not by anything in the JVM. Had it been the JVM, `SLOPP_SERVER_JVM_OPTS`
takes any `-XX:StartFlightRecording=…` flag for the plugin-launched server
too.

## Test

There is **no `:test` alias**, and `clojure -M:test` does not work here: the
tree is fileless, so there is no source for a file-based runner to find.

Two tiers, by tag on the deftest name:

| Tier | Runs |
|---|---|
| untagged / `^:integration` | in-image, on every affected write |
| `^:external` | its own JVM; never in-image |

The in-image runner filters `^:external` tests **out** and reports them
pending rather than running them there and false-greening them.

You mostly do not run tests by hand. Every write runs the tests a trace map
says exercise the touched forms, and:

- `done {label}` — the episode bar: whole in-image suite plus the `^:external`
  tests your changes impact, lint and dead surface over touched namespaces.
  Reports, never refuses.
- `full_check` — the whole store, every namespace, every tier. Nothing forces
  it; reach for it on a broad change or after deleting a caller.
- `test_run {external true}` — materializes the store into a temp dir and runs
  `clojure -M:test` **there**, against the generated `deps.edn`.
- From a shell: `slopp --main slopp.sync/-main test .`

Image-spawning tests must be `^:external` and must `close!`/`stop!` in a
`finally` — leaked child JVMs are a bug (`ps aux | grep nrepl.cmdline`).

## Client code (ClojureScript)

The store now carries client code (`slopp.client.*`, the store-browser
namespace filter). A namespace's target is the `:platform` register — `:jvm`
(default), `:cljc` (portable: JVM-verified AND compiled), `:cljs` (client-only:
compiled, never loaded into the oracle). Declare it with `module_platform`, or
at birth with `ns_create {platform}`.

- **Verify it** the usual way: `:cljc`/`:jvm` logic red/greens on the JVM
  oracle like any Clojure (keep testable logic in `.cljc`); a `:cljs` write
  lands `:unverified :cljs-deferred-to-compile` — its gate is the compiler.
- **Compile it** with the `compile_client` tool → one `:simple` JS bundle
  recorded as a served blob (`public/cljs/main.js` → `/assets/cljs/main.js`).
  It shells **real ClojureScript on the JVM — no Node** — via the generated
  `:cljs` deps.edn alias. **Two dep configs, and slopp owns one of them:**
  the manifest (`deps_add`, delta-tracked, yours) and slopp's own toolchain,
  which `build!` INJECTS at materialization time (`external/client-build-deps`)
  whenever the store has client code — the configured compiler into the
  build-only `:cljs` alias, and malli into the build's `:deps` (inherited by
  `:cljs`, so it covers the external tier and the compile). slopp's deps are
  versioned centrally (malli from `repl/inherent-deps`, the compiler from
  `build/compiler-coord`), never enter the manifest or `deps_list`, and leave
  no `:deps-add` deltas — so an upgrade reaches every store with no migration.
  `build!` is the single materialization point (`external-test-run!` and
  `compile-client!` both go through it), so one injection covers every tier. `compile_client` doesn't run
  automatically by default — it's a build/serve step, not part of the
  write-verify loop.
- **The dev loop is ON for a store that runs a dev instance** (`http.enabled`
  or a declared `app.main`): a client-ns write recompiles the bundle in the
  background (async, single-flight), so the served page gets fresh JS without
  a manual `compile_client`; the write returns `:client-recompiling` and the
  bundle lands with the next `done`. A store with no dev instance compiles
  nothing on a write. `config_file {path "client" key "auto-compile" value
  "false"}` opts out; `"true"` forces it for a store that serves nothing.
- Running the compiled JS against a real DOM is out of scope (browser/Cypress
  someday), not the inner loop.

## Documentation site

`docs/` + `mkdocs.yml`, built with MkDocs Material. These are ordinary git
files on the human-owned branch, not store content — edit them with normal
tools, and note that slopp's per-write verification does not cover them.

MkDocs is Python and this project is otherwise JVM-only, so the toolchain
lives in a container. Nothing to install but Docker:

```sh
docker build -q -f Dockerfile.docs -t slopp-docs . && \
  docker run --rm -p 8000:8000 -v "$PWD:/docs" slopp-docs
```

Then open **<http://127.0.0.1:8000/slopp/>** — not the bare root. `mkdocs
serve` mounts the site under `site_url`'s path so local paths match
production; the bare root just redirects.

It live-reloads as you edit. To check the build the way CI would:

```sh
docker run --rm -v "$PWD:/docs" slopp-docs build --strict
```

`--strict` promotes broken internal links and bad config to errors. Output
goes to `site/`, which is gitignored.

The image prints an upstream advisory banner from the Material team about
MkDocs 2.0 in red. It is not about this repo and not an error.

Hosting is not wired up: there is no GitHub Pages workflow yet, deliberately.

Two rules for writing:

- Tone and the AI-trope checklist: `.context/writing-style.md`.
- The site is **derived** from the shipped skills. A rule belongs in the skill
  first; the site is what goes stale. When a tool or a result key changes,
  grep the old name across `docs/` and `plugins/` in the same pass.

## Benchmarks

At commit points:

```sh
clojure -M -m slopp.kernel.boot . --main slopp.lab.benchmark/-main
```

The tree is fileless, so a plain `-m slopp.lab.benchmark` finds nothing.
History appends to `benchmarks/results.md`, which is **gitignored**: it is a
local record, not a committed one, so rows only ever compare against other rows
from the same machine. Don't commit it and don't reinstate it in CI.
Background: `.context/dogfooding.md`.

## CI

Three workflows, all on the human-owned branch, all checking out `slopp/main`:

- `test.yml` — **in-image**: every non-`^:external` test through slopp's own
  runner (`slopp.image.testmain`) from the projected tree, ~21s. The
  `^:external` tier is EXCLUDED in CI for now (2026-09-13): sharded four ways
  it still runs past 25 minutes on a two-core runner; its cost is measured in
  `ideas/product/external-tier-cost.md`. It stays `full_check`'s tier, run by
  hand before a commit point. **via-slopp** — the pushed code importing itself
  through every gate, then the full sharded tier — runs only on dispatch with
  `external: true`, and checks out full history because the clone grafts onto
  the branch's commits. Bare `clojure -M:test` is not the suite: two
  namespaces assume slopp's runner, and the watchdog used to read the runner's
  `/dev/null` stdin as a dead parent and exit 0 mid-run.
- `release.yml` is gated on the same in-image suite before the jar is built.
- `native-proof.yml` — a sample app built through slopp, compiled to a GraalVM
  native binary, executed.
- `release.yml` — manual dispatch with a version input: build the uberjar,
  smoke it as the slopp server it is (boot from a neutral dir, answer
  `/api/status`), tag it, attach it to a Release. It builds from `slopp/main`,
  so the projection carries the compiled bundle as bytes (artifacts project
  at their manifest paths) — a release without it serves pages whose script
  404s. After the release, bump `VERSION` and `SHA256` in
  `plugins/slopp/bin/slopp` and the plugin manifest's version by hand.

GitHub only runs push-triggered workflows from the pushed ref's tree, so these
run on `workflow_dispatch` and a schedule rather than per push.

### Local checks (not CI, because their subject is not in the repo)

- `bin/check-shipped-prose.sh` — shipped prose must not document a capability
  key the registry does not declare. Has a CI lane; reads `slopp/main`.
- `bin/check-ideas-backlog.py` — the `ideas/` worklist must not carry its own
  history, and its links must resolve. **No CI lane, deliberately**: `ideas/`
  is gitignored, so CI has nothing to look at. Exits 0 with a note when the
  directory is absent, which is a fresh clone's normal state. Run it after any
  backlog sweep; the reasoning is in its header.

## Commits

- **Both ledgers, every commit point**: `commit_point` (green-gated store
  commit point — what `git_push` publishes) *and* a git commit of kernel/docs
  changes, with plain descriptive messages.
- **Never credit Claude or any AI** — no `Co-Authored-By`, no "Generated
  with" footers.
- Update the relevant `.context/` doc in the same commit as the change it
  documents.
- slopp owns exactly one branch (`git-branch`, default `slopp`). `main` is
  human-owned: docs, CI config, this file.

## Where knowledge goes

Three homes, and the routing matters more than it looks:

| Home | Holds | Audience |
|---|---|---|
| `.context/` | why slopp is built this way; design decisions, mechanics, gotchas | whoever works ON slopp |
| `plugins/slopp/skills/` | how to WORK with slopp — **these ship** | every agent driving slopp anywhere |
| `docs/` | the same rules re-aimed at a human evaluating or adopting slopp | readers without the tools in front of them |

The test: *would this help someone using slopp on a completely different
codebase?* If yes it belongs in a skill. A lesson that lives only in
`.context/` helps exactly one repo — this one.

`.context/decisions.md` holds **decisions only**. Observations go elsewhere:
findings from evals and dogfooding to `.context/findings-log.md`, open
frictions to `ideas/`, forward plans to `.context/roadmap.md`.

Subsystem docs and what to read before touching what: the doc map in
[AGENTS.md](./AGENTS.md), the shared instruction set every agent harness
reads (`CLAUDE.md` is a one-line import of it plus Claude-specific wiring).
