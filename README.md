# slopp

An **agent-native codebase system**: the unit of editing, storage, hot-reload,
verification, and history is the **top-level form** — not the file. Code lives
in a SQLite-backed delta journal (`.slopp/store.db`); a working directory
using slopp holds **no source files at all**. Agents (and people) edit through
a tool surface where every write is compile-gated, test-verified, and
provenance-tracked before it lands.

**This repository is a projection.** Every commit here was generated and
pushed by slopp itself from its own store — slopp is written *in* slopp. The
files are real and browsable, CI runs them, and edits made here (PRs, direct
commits) flow back into stores via `git_pull`'s form-granular 3-way merge.

## Quick start

**As a Claude Code plugin** (easiest — needs Java 21+ only):

```
/plugin marketplace add nvoxland/slopp
/plugin install slopp@slopp
```

The plugin bundles the workflow skills (`slopp`, `slopp-setup`), a `slopp`
CLI on the session PATH, and an MCP entry pointed at **one daemon for the
machine**, which you start yourself and which serves whatever project each
session is in. Beyond Java, the plugin needs only bash and curl:

```sh
slopp daemon        # fetches the versioned release jar (~27MB, checksum-verified) on first run, then stays up
slopp daemon stop   # when you want it gone; `slopp daemon status` asks it
```

The plugin never starts a daemon behind you: with none running on the
configured port, the server entry fails and says so — start one, then
reconnect with `/mcp`. Offline/no-marketplace install: unpack a checkout of
`plugins/slopp/` into `~/.claude/skills/slopp/` — it loads as a plugin from
there (add `"slopp@skills-dir": true` under `enabledPlugins` in
`~/.claude/settings.json` if the server doesn't come up).

If you run Claude Code in `auto` permission mode, allow the server once
(`"permissions": {"allow": ["mcp__plugin_slopp_slopp"]}` in
`.claude/settings.json`): otherwise every slopp call is judged by the
permission classifier first — a billed model call and ~1.5 s per call
that built-in tools never pay. The `slopp-setup` skill has the details.

**From the release jar** (needs Java 21+ and the [Clojure CLI](https://clojure.org/guides/install_clojure)):

```sh
# the onboarding flow: clone normally (main = this branch, yours), then
# IMPORT the slopp branch into a store — the working dir stays your checkout
git clone https://github.com/nvoxland/slopp.git proj && cd proj
java -jar slopp.jar --main slopp.sync/-main import .
java -jar slopp.jar ~/.slopp # the daemon: one slopp for the machine (the plugin starts it for you)

# slopp then syncs against refs/heads/slopp of THIS repo (git-remote ".");
# you push/pull origin — both branches — with regular git
```

The jar's entry point is itself slopp-tracked config — `META-INF/MANIFEST.MF`
on the store's files manifest names the launcher and the fn it delegates to.

**As an MCP server** for Claude Code or any MCP client (`.mcp.json`):

```json
{"mcpServers": {"slopp": {"command": "java", "args": ["-jar", "slopp.jar", "."]}}}
```

The server loads its code once at boot and serves it until restarted — it
does not hot-reload its own namespaces. From a checkout of this repo,
`clojure -M -m slopp.kernel.boot .` boots the same way. (Working ON slopp,
the in-progress version runs as a dev instance the daemon re-serves at each
`done`; the machine daemon stays on its released jar.)

## The model

- **The store is the source.** Namespaces are ordered forms in a delta
  journal; a VFS renders `.clj` text on demand. `build` materializes files
  only when tooling needs them.
- **Every write is gated.** Dialect check (no `eval`/`read-string`/user
  macros), compile into a live subprocess image, a **cold-load check** (a
  form may not reference a later-defined one — hot-loading can't see that,
  a fresh boot dies on it), **error-level lint refusal**, then the affected
  tests run automatically (a trace map knows which tests exercise which
  forms). A failed gate commits nothing.
- **Every write is provenance.** Deltas carry the agent, the prompt, and the
  enclosing *turn* (the user's verbatim ask). `query_form_history` replays
  any form's life; `query_form_at` time-travels; `file_history`/`file_get`
  do the same for tracked non-code files (the CI workflow rides the files
  manifest; the jar manifest is structured config).
- **Commit points are git commits.** `commit_point` (green-gated) snapshots a
  byte-exact tree onto the marker; the git projection deterministically
  mints these as the commits you see here, authored per the store's
  `user.name`/`user.email` config (`"<git>"` defers to your git config).

## Working with git (this repo)

- `git_push` — publish the commit point history to a normal remote
  (fast-forward only, never force).
- `git_clone` — rebuild a **fileless** store from a remote; the clone grafts
  onto the remote's history so its pushes fast-forward.
- `git_pull` — absorb remote changes by a 3-way merge **at form
  granularity**: the remote wins where your store is clean; anything both
  sides touched becomes a conflict (your version stays live, the remote file
  is quarantined, push blocks until you resolve with the edit tools and
  `git_resolve`). Exactly git's model, one file coarser.

Two stores collaborating through this repo — including edits made directly
on GitHub — is the tested workflow.

## Tests

Two tiers, by tag on the test name: plain/`^:integration` tests run
**in-image** on every affected write; `^:external` tests (they exercise IO
and spawn their own JVMs) run via `test_run {external true}`, which
materializes the store into a temp dir and runs `clojure -M:test` there.
`done` runs the impacted external tests automatically; `full_check` runs
every tier over the whole store. CI runs both faces:

- **test-files** — the suite straight from this repo's files.
- **test-via-slopp** — the pushed code imports *itself* into a fresh store
  (every namespace through every gate) and runs the store-built suite.
- **native-proof** — a sample app built through slopp, compiled to a GraalVM
  native binary, executed. (slopp itself ships as an uberjar, not a native
  binary — the store loader compiles code at runtime by design.)
- **release** — manual dispatch with a version input: build the uberjar
  from the slopp branch, smoke it bare, tag it, attach it to a Release.

The workflows live on `main` (human-owned — external-system config never
enters the store) and check out the `slopp` branch; since GitHub only runs
push-triggered workflows from the pushed ref, they run on a daily schedule
and on demand rather than per push.


## Mixed ownership: slopp owns ONE branch

slopp pushes and pulls exactly one branch — the store config `git-branch`,
**default `slopp`** — and never touches anything else. `main` (this branch)
is *human-owned*: this README, docs, and anything you manage with regular
git live here; merge `slopp` into `main` (or cherry-pick) whenever you want
the code side updated, and merge `main` edits back for slopp to absorb via
`git_pull`'s form-granular 3-way.

The local variant: set `config git-remote "."` and slopp pushes into your
checkout's own `.git` as the `slopp` branch — you do all origin interaction
with regular git. Pushing onto a branch your working tree has checked out is
refused (the ref would move under you).

Execution config (like the jar's `META-INF/MANIFEST.MF`) isn't a tracked
text file either: the store holds semantic key/values per config path
(`config_file` — per-key history, like forms) and the projection serializes
them into the right format on every push.

## Docs and development

User-facing documentation lives in `docs/` (MkDocs Material) — concepts,
guides, the tool index, and a release blog. See [DEV.md](./DEV.md) for
running mcp, the test tiers, benchmarks, and a one-liner that
builds and serves the docs site locally.

## Status

Experimental and self-hosted — slopp has been developed through slopp since
the store flip, and this repo is its published face. Design decisions and
their reasoning live in `.context/decisions.md` in the development repo;
the tool cheat-sheet is one `help` call away on any running server.
