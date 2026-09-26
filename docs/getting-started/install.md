# Install

slopp needs **Java 21+** and nothing else. Everything below runs the same jar.

!!! note
    slopp is experimental. On-disk shapes and tool signatures still change
    between releases. The store is a SQLite journal in your project -- back it
    up the way you would back up a database, and push commit points to git.

## Claude Code plugin (recommended)

```
/plugin marketplace add nvoxland/slopp
/plugin install slopp@slopp
```

That gives you the workflow skills (`slopp`, `slopp-setup`, `slopp-style`,
`slopp-review`), a `slopp-reader` subagent, a `slopp` CLI on the session PATH,
and an MCP entry pointed at the machine's **slopp server**: one slopp process
serving every project you open, which you start yourself:

```sh
slopp server
```

The first run downloads the release jar (~27MB), checksum-verifies it, and
boots; the slopp server then stays up until `slopp server stop`. Nothing in the
plugin starts one for you -- a server nobody started is a server nobody knows
to stop or upgrade -- so with no slopp server on the configured port the MCP entry
fails with a sentence saying so, and `/mcp` reconnects once you have started
one. The slopp server serves whatever project directory each session is in.

A project that declares a dev instance (`app.main`, or `http.enabled`) can
also be served on its own, with no slopp server: `slopp dev .` boots it from
the store and re-serves it at every `done`, until you stop it.

If you run Claude Code in `auto` permission mode, allow the server once, in
the project's or your user `.claude/settings.json`:

```json
{"permissions": {"allow": ["mcp__plugin_slopp_slopp"]}}
```

Otherwise every slopp call is judged by the permission classifier before it
runs -- a billed model call and about 1.5 s per call, which built-in tools
skip and MCP tools do not. Measured over real sessions that was 100--225 s
of a session's wall, with the server itself answering in milliseconds.

Check the wiring end to end:

```sh
slopp --doctor
```

It reports on java, the cached jar, the hook and skill files, curl (the
hooks and the CLI reach the slopp server with it), and a live store probe. Exit 0 means a session has everything
it needs.

### Offline or no marketplace

Unpack a checkout of `plugins/slopp/` into `~/.claude/skills/slopp/` -- it
loads as a plugin from there. If the server does not come up, add
`"slopp@skills-dir": true` under `enabledPlugins` in `~/.claude/settings.json`.

## Any MCP client

Point your client at the jar:

```json
{
  "mcpServers": {
    "slopp": {
      "command": "java",
      "args": ["-jar", "/path/to/slopp.jar", "."]
    }
  }
}
```

The server loads its code once at boot and serves it until restarted — it
does not hot-reload its own namespaces. With no directory argument,
`java -jar slopp.jar` boots the current working directory -- the jar's entry
point is itself store-tracked config (`META-INF/MANIFEST.MF` on the files
manifest names the launcher and the fn it delegates to).

Grab the jar from the [releases
page](https://github.com/nvoxland/slopp/releases).

Without the Claude Code plugin you lose the prompt hooks, which record the
user's verbatim ask as the turn. A write carrying `prompt` opens its own
turn, so nothing is refused; the turn's intent is then the write's prompt
rather than the ask. See [history and provenance](../guide/history.md).

## Starting a new project

Nothing to set up. The server runs in your project directory and creates
`.slopp/store.db` on your first **write**. Add `.slopp/` to `.gitignore`.

Serving a directory does not adopt it. In a project with no store, the server
writes nothing to disk at all -- no `.slopp/`, no session-pause
checkpoints -- so leaving the plugin enabled globally does not
turn unrelated repos into slopp projects.

In a brand-new project the prompt hook has no store to record intent against
yet; the first write creates the store and opens its own turn from its
`prompt`, and from then on the hook records each ask.

## Working on a repo that is already published this way

A slopp-published repo has a `slopp` branch (the store's projection) alongside
a human-owned `main`:

```sh
git clone <url> && cd <repo>          # main is checked out -- a normal working dir
slopp --main slopp.sync/-main import .
```

`import` builds `.slopp/store.db` from the repo's `slopp` branch and records
`git-remote "."`, so slopp pushes and pulls the *local* repo's `slopp` branch
and you do all origin interaction with regular git, on both branches.

To build a store with no working tree at all, clone straight from the remote:

```sh
slopp --call git_clone '{"url":"https://github.com/you/proj.git","dir":"proj"}'
```

## From a source checkout

```sh
git clone https://github.com/nvoxland/slopp.git && cd slopp
slopp --main slopp.sync/-main import .    # build .slopp/store.db from the slopp branch
slopp dev .                               # serve the checkout's in-progress slopp
```

The working tree is fileless: docs, the plugin and CI config are the only
real files — no `src/`, no `deps.edn`, no `build.clj` (both are produced
from the store into every built tree). The jar's boot kernel loads
every namespace's byte-exact source out of `store.db` into the JVM in
dependency order and invokes the entry point, so a plain `-m slopp-server.mcp`
finds nothing. To cut your own jar from the store: `slopp build . --jar`.

Next: [your first session](first-session.md).
