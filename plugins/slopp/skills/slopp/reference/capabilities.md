<!-- reference topic `capabilities` — served whole by `help {topic "capabilities"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## Capabilities: what your project opts into

**A slopp project declares which capabilities it wants, and gets nothing it
does not ask for.** `query_capabilities` is the whole picture; each is off by
default and turned on with `config_file {path "capabilities" key
"<name>.enabled" value "true"}`.

| Capability | What your app gets | Requires |
|---|---|---|
| `cli` | argument parsing, injected stdin/stdout/stderr, exit codes | — |
| `http` | the HTTP server, routing, static mounts, identity + authorization | — |
| `rest` | typed request/response contracts, validation, generated clients | `http` |
| `webapp` | client-side routing, state, event dispatch, the ClojureScript build | `http` |

A capability is a bargain, and `query_capabilities` shows both halves — the
settings you may configure AND **the rules opting in will arm**:

```
{:capability "http" :enabled false :requires [] :required-by ["rest" "webapp"]
 :arms [{:rule http-auth-refusal :grain :form} …
        {:rule :http-public-mutation :grain :done}]}
```

`:grain :form` refuses at the write; `:grain :done` reports at a done point.
Read that BEFORE you opt in — it is what tells you an unsecured route is about
to stop being writable.

**Enabling writes what it requires and NAMES it.** `webapp` has to be served,
so it turns on `http` with it and reports `:implied ["http.enabled"]`. The
reverse asks rather than acts: turning off something a dependent stands on
refuses, naming what holds it up. Turning a thing on has one safe answer;
turning it off does not, and slopp will not remove a feature you never
mentioned.

**A capability key's FIRST SEGMENT names the capability that owns it**, and
`query_capabilities` reports it per row: `slopp.*` is the framework's and is
RESERVED — your app can never own a key there — `app.*` is any project's, and
the rest belong to the capability they name. So everything the HTTP server
needs, auth included, sits under `http.`: `http.port`, `http.static.<prefix>`,
`http.auth.providers`, `http.auth.groups.<name>.members`. A key belonging to no
declared owner is not a capability and refuses at the write.

**Some things are NOT capabilities, deliberately.** Hiccup rendering and CSS
are always available. A capability exists to take IO away from you — it ships a
port, the real adapter behind it, a FAKE so your tests need no socket or
browser, and gates that refuse reaching around the port. Pure functions that
throw on unsafe input have no IO to own and nothing to fake, so they just ship.

If `query_capabilities` reports `:orphaned`, those are keys stored under names
this slopp no longer knows — a rename you have not migrated. The rows carry the
VALUE, so the report is the migration instruction: set the current key, then
`config_file {path "capabilities" key <old> unset true}`.

### slopp VENDORS the framework, and two axes decide what you get

You never declare slopp's own framework in your deps. slopp copies the source
into every image it launches for you, and **two different things decide what
lands there — keep them apart, because conflating them predicts the wrong
thing:**

- **WHICH families** — re-derived from YOUR STORE at every image launch. Enable
  a capability, add a require, mark an entry, and the next image has it. You do
  not restart for this.
- **WHAT IS IN a family** — read from the slopp jar this process is running, and
  frozen until that process restarts. A fix to slopp itself reaches you through
  a new jar and a restart, and no amount of editing your store will produce it.

A real report from one project's notes: *"the tree is materialized from the jar
at PROCESS START."* Half true, and the wrong half sent them hunting a bug when
a capability they enabled mid-session turned up working.

**Vendoring follows USE, not enablement** — requires and entry markers. That is
deliberate and it is what keeps a store mid-migration repairable: your requires
outlive your config, so a store whose config is half-moved still boots. The
opt-in is enforced where it can answer for itself — the write gates, and the
behaviour (a server that does not start, rules that stay inert).

**It only ever writes, never removes.** Within one session the tree is the union
of every family you have used at any point in it, so a family you stop using
keeps resolving until the session ends and is gone in the next one. If you are
checking that removing a capability really removed its reach, check in a fresh
session.

