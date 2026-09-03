---
name: slopp-setup
description: "Set up, sync, and ship a slopp-managed repo: adopt slopp in a project, import a published slopp repo from its git checkout, the branch-ownership model (slopp owns the 'slopp' branch, humans own main), store config, and the one-shot CLI for scripts and CI."
---

# Setting up and shipping a slopp repo

The store is a SQLite delta journal at `<project>/.slopp/store.db` — gitignore
it. What git tracks is a **projection**: at every commit point (`commit_point`)
slopp can render the store as ordinary `.clj` files and push them as a git
commit. Day-to-day editing happens through the MCP tools (see the `slopp`
skill); this skill covers everything around that — onboarding, sync, config,
and CI.

## Local mirror (automatic)

In a git checkout, every `commit_point` mirrors the store's history into
LOCAL git as `slopp/<store-branch>` (e.g. `slopp/main`) — the repo
durably carries the slopp history with zero ceremony; inspect it with
normal git. Publishing to a REMOTE stays explicit: `git_push` sends your
`slopp/<branch>` mirrors up from a checkout (first `url` becomes the
saved default; one-off urls never rewrite it); a FILELESS store (no
`.git`) publishes its projection directly. `git_pull` fetches the
remote's mirrors down (fast-forward only) and absorbs remote store
history.

## Starting fresh

Nothing to do: the plugin's server runs in your project directory and creates
the store on your first WRITE. Add `.slopp/` to `.gitignore`.

**Serving a directory does not adopt it.** With the plugin enabled, the server
starts in every project you open — and in one that has no store it writes
NOTHING to disk: no `.slopp/`, no git listener, no session-pause checkpoints.
So an unadopted repo stays untouched, and you can leave the plugin enabled
globally without it turning up in unrelated projects.

In a brand-new project the prompt hook has no store to record intent against
yet; nothing to do about it — a write carrying `prompt` opens its own turn,
so the first write creates the store and its turn in one call, and from then
on the hook records each ask.

## Importing a repo that's published this way

A slopp-published repo has a `slopp` branch (the store's projection) alongside
a human-owned `main`. To work on it:

```sh
git clone <url> && cd <repo>        # main checked out — normal working dir
slopp --main slopp.sync/-main import .
```

`import` builds `.slopp/store.db` from the repo's `slopp` branch (found via
local or remote-tracking refs) and records `git-remote "."` — slopp then
pushes/pulls the **local repo's** `slopp` branch, and you do all origin
interaction with regular git, on both branches. (`slopp` here is the plugin's
bundled CLI, on your PATH; it's the same jar the MCP server runs.)

**After ANY adoption — this import, `git_clone`, or pointing slopp at an
existing codebase — run `store_doctor` once.** Every gate slopp has runs at the
WRITE, so code that arrived another way has never met one. The doctor is the
only surface that looks for what the current rules would never have let in:
hand-written `(declare …)` the ordering pipeline cannot see, two elements in
one namespace defining ONE name (a form-addressed edit cannot say which you
mean, and the last wins at load), and metadata that LOOKS like one of slopp's
dials but is not (`^:unusedok` waives nothing while reading as though it does).
Each finding names the call that fixes it.

It is a different question from the other two, and all three are worth knowing
apart: `full_check` asks whether the store is CORRECT, `store_health` what it
COSTS in bytes, `store_doctor` what it CARRIES that no longer belongs. A store
written entirely through slopp comes back clean — which is exactly why it is
worth running on one that wasn't.

## The branch-ownership model

slopp owns exactly ONE branch — store config `git-branch`, default `"slopp"`.
- `git_push {url?, branch?}` publishes commit points to that branch of the
  configured remote (`git-remote`; a relative value like `"."` resolves
  against the store dir). It refuses to move the checked-out branch of a
  non-bare local repo.
- `git_pull` absorbs remote commits on that branch — form-granular 3-way
  merge; same-form divergence is quarantined, listed by `git_conflicts`,
  resolved with `git_resolve` + a normal edit.
- `git_clone {url, dir}` rebuilds a fileless store from a published repo.
- `import_dir {dir}` absorbs a DIRECTORY the same way, with no git on either
  side — a zip, a scratch tree, another tool's output. Same 3-way merge, same
  conflict handling, same `done` gate; the base is your last commit point. Git is
  a *caller* of import, not a requirement for it.
- Humans own `main` (and everything else) with regular git; merge
  `slopp ↔ main` yourself when you want code to cross the boundary.
- Commit point authorship: `config {key: "user.name"|"user.email", value}`
  (value `"<git>"` defers to git config).

## Source lives in slopp; everything else lives in git

slopp manages the STORE — your application/source code plus the deps
manifest (`deps.edn` is GENERATED by `build!` from the store's `:deps`;
change it with `deps_add`/`deps_remove`, never by hand). The other files a
repo needs — READMEs, project docs, CI config, editor config, scripts —
are NORMAL git files: edit them with your usual tools and commit them with
git yourself. slopp's per-write verification, history, and reverts protect
STORE code only — a shell one-liner that clobbers a plain file has no such
safety net, so edit those carefully (prefer append/patch over whole-file
rewrites) and lean on git for their history and recovery. This split is
intentional: people keep managing the non-source parts of their repo as
ordinary git.

## Store config that ships with the code

- `config_file {path, key, value, format}` — semantic key/value config,
  history-tracked like code and rendered into every projection. Format
  `manifest` covers `META-INF/MANIFEST.MF`: set `Main-Class`/`X-Slopp-Main`
  once and the repo's uberjar boots with a zero-arg `java -jar slopp.jar`.
- The `capabilities` path is the project's app manifest — `app.name`,
  `app.version`, `app.main` (the entry point `build` uses when no `:main`
  arg is passed). Every key is registry-declared: writes validate with
  teaching, and `query_capabilities` lists each setting with its type,
  default, and effective value.
- `file_put {path, content}` — genuinely opaque files that must ride the
  projection (build scripts). Keep external-system config (CI workflows,
  READMEs) on the human branch instead — only config the app itself consumes
  belongs in the store.
- **A file you can REGENERATE does not belong on that manifest.** Compiled
  bundles and downloaded libraries are `:artifacts`: the bytes live on disk
  under `.slopp/artifacts/<sha>` (gitignored), and the journal carries only
  the sha and a recipe for getting them back. `compile_client` and `js_dep`
  register them for you — the split matters because putting one on the files
  manifest inlines its bytes into a delta on every single write of it. In
  this store that was 30.5 MB of journal, 99.8% of it fifteen copies of one
  bundle; as an artifact the same bundle costs 300 bytes. The line is
  recoverability, not authorship: **an artifact that is deleted is rebuilt, a
  file that is deleted is lost.** A clone with an empty cache reports what is
  missing and how to get it, rather than failing.
- `store_health` is where cost shows up, artifacts included — its `:orphaned`
  figure is cache nothing references any more. Superseded artifacts are
  reclaimed automatically when a new one replaces them.
- **Check `:missing-artifacts` on a `build` result.** A fresh clone has the
  manifest and an empty cache, which is the designed state, not an error —
  each entry names the file and the call that refills it. `build` reports
  instead of refusing, because `compile_client` builds on its way to
  regenerating the very artifact that may be missing.
- `deps_add {lib, version}` — the store's dependency manifest (hot-loads
  into the live image; no restart).

## The one-shot CLI (scripts, CI, no MCP session)

`slopp --call <tool> [args]` runs ONE tool call against the store in the
current directory and prints the result — args as JSON, EDN, or `@file`.
Pass `agent` to give a script one identity across invocations (turn state
lives in the store, so a `turn_begin` under that agent covers later calls;
omit it and each invocation is its own session). Every tool takes it, and a
script that means to finish what it started must pass the SAME one to each
call. Useful shapes:

```sh
slopp --call query_project
slopp --call commit_point '{"description":"release 1.2","agent":"ci"}'
slopp --main slopp.sync/-main test .    # isolated suite from a store build
```

CI for a slopp repo is usually: checkout → `import` (or checkout the `slopp`
branch directly) → `test`. GitHub only runs push-triggered workflows from the
pushed ref's tree, so workflows living on `main` reach the `slopp` branch via
`workflow_dispatch`/`schedule` with `checkout ref: slopp`.

## Several writers on one box

Each agent runs its own MCP server, and each server owns JVMs: the image that
verifies your writes, plus — by default — a pre-warmed spare and one parked
image per branch line you visit. That is the right trade for one agent, where
the cost is memory you weren't using and the benefit is never waiting ~830 ms
for a JVM to boot. Run eight agents on one box and it inverts: the idle images
multiply by eight while the boots they avoid do not get any more painful.

Three host settings turn it down. All are environment variables read by the
server at startup, and **all default to the single-writer behaviour**, so a
normal setup needs none of them.

| Variable | Effect |
|---|---|
| `SLOPP_WARM_SPARE=0` | Stop holding a pre-warmed spare image per server. The biggest single saving: one whole idle JVM per agent. |
| `SLOPP_BRANCH_IMAGE_TTL_MS` | How long an idle per-branch image is held before it is reaped (default `600000` — ten minutes). Shorten it when agents move between branches. Must read as a positive number; anything else keeps the default rather than being obeyed as zero. |
| `SLOPP_SERVER_JVM_OPTS` | JVM options for the SERVER process, space-separated. **Empty by default**, and the one knob here that is a real trade rather than a free win — see below. |
| `SLOPP_IMAGE_JVM_OPTS` | JVM options for every image, space-separated. Defaults to `-XX:+UseSerialGC -Xms32m` (see below). An explicitly empty value means none — set it if your project turns out to prefer the throughput collector. |
| `SLOPP_NO_RECYCLE` | Switch off image reuse entirely. Costs speed; set it only when you suspect a reused image of carrying something between tenants. |

A reasonable swarm profile is `SLOPP_WARM_SPARE=0` plus a shorter lease:

```sh
SLOPP_WARM_SPARE=0 SLOPP_BRANCH_IMAGE_TTL_MS=120000 slopp <dir> --live
```

**What you are buying, and with what.** You buy memory with image-boot
latency, and only on the paths that would have found a warm image waiting —
`restart`, `deps_add`, a branch switch. Nothing about verification changes: an
image booted on demand grades your code exactly as a pre-warmed one does.

**What this does not do is give the agents separate REPLs by itself** — they
already have them. Two agents on one store are two servers, two images and two
private threads, and that is the model whether or not you tune any of this.
What multiplies is the *idle* JVMs, which is what these settings are for.

### The image collector

Images ship with `-XX:+UseSerialGC -Xms32m`, which is worth knowing about
because it is unusual and because you may want to turn it off.

Measured against a 279-namespace store, it cuts committed memory per image
**722 MB -> 538 MB (-25.6%)**, reproducible to 0.07%. The saving is not the heap
alone: the serial collector drops GC bookkeeping from 62 MB to 0.6 MB, takes
~35 MB of collector worker stacks with it, and sizes the heap *smaller*
(278 -> 192 MB) rather than larger.

**Throughput does not pay for it, which is the surprising half.** Twelve runs
of a whole in-image suite per arm, inside one settled image: median 2738 ms on
the default collector, 2639 ms on this one. The difference is not significant,
so the honest claim is a bound rather than a win — a regression of at most
about 5%, with the point estimate slightly in this configuration's favour, and
half the run-to-run spread. Every assertion passes identically; no verdict
changes.

**The two flags are a pair and neither belongs without the other.** `-Xms32m`
alone measured 2.3% WORSE — an image loading a real store allocates past 32m
before it is ready, so the heap is demand-sized either way. `-XX:+UseSerialGC`
alone is far worse still: it commits its ergonomic initial heap at startup and,
unlike G1, never gives it back. Told what heap to start with, it holds far less.

**When to turn it off.** This is one project's workload. A far more
allocation-heavy one could find single-threaded collection costs it real time,
and the symptom is slower tests rather than wrong ones — so if your suite slows
down noticeably after an upgrade, this is the first thing to try:

```sh
SLOPP_IMAGE_JVM_OPTS="" slopp <dir> --live      # the previous collector
```

Nothing here reaches the external test tier, whose shard JVMs are launched
separately; these options apply to the images that verify writes.

### The server process is the bigger JVM, and its budget is opt-in

Measured on this repo's own store, the server is **~2.6 GB committed** — close
to four times an image, and the largest single thing a writer costs. It is also
the piece that is identical in every writer, since every server runs the same
tool code.

`-XX:+UseSerialGC -Xms32m` on the server measured **2.62 GB -> 2.17 GB (~17%,
two independent runs)**. Unlike the image, it is **not** on by default, because
its throughput gate did not come back clean: boot-to-ready went from ~50s to
~65s across four paired rounds (+31%, point estimate — the spread is wide
enough that the interval still touches zero, so treat it as "a real cost of
uncertain size" rather than a precise 15 seconds).

That is a genuine trade and which way it falls depends on you:

- **One agent** pays the slower start on every session and never notices the
  memory. Leave it off.
- **A box running several writers** is the other way round — half a gigabyte
  each, against a wait nobody is watching.

```sh
SLOPP_SERVER_JVM_OPTS="-XX:+UseSerialGC -Xms32m" slopp <dir>
```

Combined with `SLOPP_WARM_SPARE=0`, that is the swarm profile: it takes roughly
half a gigabyte off the server and a whole idle JVM off the images, per writer.

**Why the images ship this on and the server does not** is worth stating,
because the asymmetry looks arbitrary: they were measured separately and the
answers differed. The image's throughput gate bounded the cost at about 5% with
the point estimate slightly in its favour. The server's did not. Same flags,
same method, different workload — the server loads and indexes the whole store,
which is far more allocation-heavy than running a suite in an image.
