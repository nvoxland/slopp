# Store configuration

## Store settings

Read with `config {key}`, set with `config {key value}`.

| Key | Default | Meaning |
|---|---|---|
| `user.name` | -- | Commit point author name. `"<git>"` defers to git config. |
| `user.email` | -- | Commit point author email. `"<git>"` defers to git config. |
| `git-remote` | unset | Where `git_push` publishes. A relative value like `"."` resolves against the store directory. |
| `git-branch` | `slopp` | The one branch slopp owns. |

`git_push {url}` saves the first url it is given as the default. One-off urls
never rewrite it.

## Rule severity

Every write gate and done-time advisory in the [rule
catalog](../guide/verification.md#rules) has a per-store severity dial:

```clj
config_file {path "rules" key "<rule-id>" value "advisory"}
```

Severities are `off`, `advisory`, `error`, and `refuse`. `query_rules` lists
every rule with its current effective severity and how to discharge it;
`query_rule_telemetry` shows which ones actually fire and whether findings get
fixed or ignored.

**Both halves are checked against the catalog**, so a dial that governs nothing
is refused at the write rather than stored. That matters more than it sounds: a
dial is set once and never re-read, so a mistyped one is silent twice — the rule
it meant to name returns to its default, and the store goes on carrying a line
that reads like configuration. A near miss names the rule you meant.

A rule that gets RENAMED cannot be refused that way, because the dial was valid
when it was written. Those turn up as `:orphaned-dials` in `full_check`, with
the value carried across so the report is a migration instruction rather than a
complaint.

slopp's own store runs the catalog blocking. A rule an agent can walk past does
not change behaviour, and a store with no legacy code has no reason to tolerate
one. A project adopting slopp on an existing codebase is the case for dialing
things down while it migrates -- `cleanup {all: true}` is the sweep that tells
you how much there is.

## The capabilities file

`config_file {path "capabilities"}` is the project's app manifest and opt-in
surface: what the application is called, its entry point, and whether it serves
HTTP and how.

Unlike a free-form config file, every `capabilities` key is declared in a
registry with a type, a default, and a doc line. That buys two things:

- **Writes validate.** An unknown key or a value that fails its type is
  refused at the write, with teaching -- a typo'd setting can never silently
  do nothing.
- **Reads never nil-pun.** `query_capabilities` lists every setting with its
  default and its effective value; a registered key always has an answer.
- **Names this build no longer knows are reported, not dropped.** Capability
  keys get renamed and there is no back-compat, so a store can hold values
  under retired names. Those come back under `:orphaned`, with their values
  and what to do about them -- otherwise a store mid-rename reads as
  unconfigured, which is how the reason your app server will not start ends
  up sitting in a config the tool declined to mention.

```clj
config_file {path "capabilities" key "app.main" value "myapp.core/-main"}
query_capabilities {}
```

| Key | Default | Meaning |
|---|---|---|
| `app.name` | the store directory name | Application name, at build time. For a `cli` project it is also the program's name in its own usage text — one string deliberately, since help that teaches a command the shell does not have is worse than no help. |
| `app.main` | unset | The entry fn (`myapp.core/-main`). `build` falls back to it when given no `main` argument. |
| `cli.enabled` | `false` | Whether this project is a command-line program. With it, slopp GENERATES the entry from your `:cli/command` forms and supplies argument parsing, injected streams and exit codes; without it an app's main runs with none of that. Refused alongside `app.main` — both declare an entry. |
| `http.enabled` | `false` | Whether this project serves HTTP. The master opt-in: every http rule and `query_surface`'s `:http` section exists only when true. |
| `http.adapter` | `:http-kit` | `:jdk` is the zero-dependency fallback. |
| `http.host` | `127.0.0.1` | Bind address. Widen deliberately. |
| `http.port` | unset | The port the app's server binds. Unset means 8080 in production (`serve!` defaults it) and DERIVED from the store directory for the dev server, so two projects on one machine cannot collide. Set it to pin one address for both. |
| `http.max-body-bytes` | `1048576` | Largest accepted request body. |
| `rest.enabled` | `false` | Whether this project publishes a typed API. With it, a request that breaks its declared `:rest/request` is a 400 before your handler runs, what JSON cannot carry is decoded to the types you declared, and a response that breaks its own `:rest/response` is a 500 with the explain logged rather than sent. Implies `http`. |
| `http.auth.providers` | none | Enabled identity providers, comma-separated, tried in order. |
| `http.auth.default-policy` | `:deny` | For an endpoint with no `:http/auth`, which only happens if `http-auth-refusal` is dialed down. |

!!! note "There is no `dev.server` setting"

    Whether slopp runs your app server while you work is **derived, not
    configured**. It manages one unless the calling process already serves
    every namespace that store would -- which is true of exactly one store on
    earth, slopp's own, whose web surface *is* the API the live session
    already serves.

    It used to be a `dev.server` capability, and that asked every project a
    question only one of them should answer. It misfired the way footguns do:
    the second project to meet it set `false` because its static assets were
    404ing, and the switch then presented a bug as a preference for a week.
    Computing it means a project cannot answer wrong, and cannot use the
    answer to paper over something else. See
    [running](../guide/web/running.md).

Some keys are *families* whose tail is part of the setting:

| Pattern | Meaning |
|---|---|
| `http.static.<url-prefix>` | A static mount. The value is a files-manifest path prefix: `http.static./assets` = `public`. |
| `http.auth.static.users.<name>` | `{:password-hash "pbkdf2$..." :groups [...]}` |
| `http.auth.bearer.tokens.<name>` | `{:secret "env:NAME" :groups [...]}` |
| `http.auth.proxy.*` / `http.auth.oidc.*` | Provider settings. Secrets are `env:NAME` indirections. |
| `http.auth.groups.<name>.members` | Comma-separated members of a named group, for `:http/auth [:group ...]`. |

`query_capabilities` is the current list for the version you are on. The web
keys are covered in [auth and security](../guide/web/auth.md).

### The first segment names the capability that owns the key

Every key belongs to someone, and the name says who. `query_capabilities`
reports the owner per row, with the vocabulary beside it:

| Segment | Whose | Requires |
|---|---|---|
| `slopp.` | slopp itself. **Reserved** — your app can never own a key here. | — |
| `app.` | Any project, whatever kind of application it is. Always on. | — |
| `cli.` | A command-line shell: argument parsing, injected streams, exit codes. | — |
| `http.` | An HTTP server: routing, static mounts, identity and authorization. | — |
| `rest.` | A typed API: contracts, boundary validation, generated clients. | `http` |
| `webapp.` | An app whose BROWSER owns routing and state, and its build. | `http` |

A key under no declared owner is not a capability and refuses at the write.
That is what keeps one capability's settings from spreading into the generic
pool under names that do not say whose they are: auth is the HTTP server's, so
it is `http.auth.*`, and a new capability arrives as its own segment rather
than as more keys in the middle of this table.

### Opting in, and what comes with it

`<name>.enabled` is the switch, and every capability is off by default. Two
things happen that are worth knowing before you throw one.

**Enabling writes what it requires, and says so.** `webapp` needs serving, so
turning it on turns on `http` in the same delta and names it back to you:

```
config_file {path "capabilities" key "webapp.enabled" value "true"}
→ {:implied ["http.enabled"] :implied-note "set with it, because …"}
```

The reverse ASKS instead of acting: turning off something a dependent stands on
refuses and names what is holding it up. Turning something on has one safe
answer; turning something off does not, and slopp will not remove a feature you
never mentioned.

**Opting in ARMS that capability's rules.** This is the half that used to be
invisible — the settings said what you could configure and nothing said what
would start refusing your writes. `query_capabilities` now reports it per
capability:

```
{:capability "http" :enabled false :requires [] :required-by ["rest" "webapp"]
 :arms [{:rule http-auth-refusal :grain :form} … {:rule :http-public-mutation :grain :done}]}
```

`:grain :form` refuses at the write; `:grain :done` reports at a done point. A
project that never enables a capability is untouched by all of it.

Note what is NOT a capability: hiccup rendering and CSS are always available.
A capability exists to own IO and ship you a fake to test against, and pure
functions that throw on unsafe input have neither.

## The client config file

`config_file {path "client"}` holds the ClojureScript build settings:

| Key | Default | Meaning |
|---|---|---|
| `compiler` | `:clojurescript` | The compile backend. |
| `auto-compile` | on when the store runs a dev instance | Recompile the bundle in the background after a client-namespace write. Unset: yes when `http.enabled` or `app.main` is set, else no. `false` opts out, `true` forces it. |
| `generated-ns` | `app.client.api` | Where `generate_client` writes the typed client. |

## Structured config files

`config_file` stores semantic key/values with per-key history and serializes
them into every projection:

```clj
config_file {path "META-INF/MANIFEST.MF"
             key "Main-Class"    value "slopp.launcher"
             format "manifest"}
config_file {path "META-INF/MANIFEST.MF"
             key "X-Slopp-Main"  value "myapp.core/-main"
             format "manifest"}
```

`path` alone reads back everything set for that file. `unset: true` removes a
key.

Prefer this over `file_put` for anything key-shaped: you get per-key history
and a merge that resolves at key grain instead of line grain.

## The dev overlay — how the project runs while you work on it

What a project RUNS while somebody works on it is the `app.main` capability —
the same entry a built app runs. The `dev` config OVERLAYS the capability
keys for that dev instance, so it can sit on a different port from production:

```clj
config_file {path "capabilities" key "app.main"  value "shop.core/-main"}
config_file {path "dev"          key "http.port" value "7358"}
```

Two layers, same keys, both validated against the capabilities registry:

| path | what it is | where it goes |
|---|---|---|
| `dev` | the project's shared dev setup — complete, the normal way to run it | every git projection, so a clone serves without a step; never a built tree |
| `dev.local` | this machine's override of `dev` — a port this box has free | the database only; reaches no tree |

Precedence is **env override → `dev.local` → `dev` → the capability**. Keep
`dev` complete: it is what anyone who clones the project gets, and the
ordinary case needs no `dev.local` at all. `session_brief` reports the
running instance under `:app`, and under `:app-note` why there is none.

`dev` and `dev.local` are `slopp.store/unbuilt-config-paths`: `build!` writes
neither into a built tree — a jar has no use for a dev port. `dev.local` is
also `slopp.store/local-config-paths`, so `slopp.git/commit-paths` leaves it
out of every projected tree. Every other config path ships and always has.

### Overriding a config value per process

An environment variable overrides a config value for THAT process only, above
the store value and the registry default:

```sh
SLOPP_DEV_HTTP_PORT=7360 slopp server
```

The name is `SLOPP_` + the config path (file, then key) UPPERCASED with every
dot and dash as `_` — so `SLOPP_DEV_HTTP_PORT` overrides the `dev` file's
`http.port`, and `SLOPP_CAPABILITIES_HTTP_PORT` a served port.
Precedence is **override → store → default**; an override that fails the key's
type check falls back like any bad value. It is per-PROCESS, so two slopp servers
sharing one `store.db` can run their dev instances on different ports (the
store holds one value; each slopp server's environment overrides it independently).

Scoped to runtime/serving config: `capabilities` (`effective`) and `dev`
(`runnables`). Rule and gate severities are deliberately NOT overridable this
way — correctness stays in the store, not switchable by an env var. And the
slopp server's OWN listen port is the separate `SLOPP_PORT` (it is not a `:config`
value); `SLOPP_DEV_RUN_SERVER_PORT` is the port of the dev instances it
manages.

## The dependency manifest

`deps_add`, `deps_remove`, `deps_list`. It is a tracked delta stream, reaches
every image launch, hot-adds to the running one, and generates `deps.edn` at
build time.

Do not hand-edit `deps.edn`. It is output.

## Files manifest

`file_put {path content}` tracks an opaque file so it rides every projected
tree; `file_list`, `file_get`, `file_remove` and `file_history` complete the
surface.

Keep external-system config off it. A CI workflow belongs on the human-owned
git branch, because GitHub reads it and slopp does not.

## Environment

| Variable | Effect |
|---|---|
| `SLOPP_JAR` | Point the plugin's `slopp` wrapper at a local jar instead of the pinned release. Useful when developing slopp itself. |
| `CLAUDE_PLUGIN_DATA` | Where the plugin caches the downloaded jar. Falls back to `$XDG_CACHE_HOME/slopp` or `~/.cache/slopp`. |
| `SLOPP_WARM_SPARE` | `0` or `false` stops the server holding a pre-warmed spare image. One whole idle JVM per agent — the biggest saving when several agents share a box. |
| `SLOPP_BRANCH_IMAGE_TTL_MS` | How long an idle per-branch image is held before it is reaped. Default `600000` (ten minutes). Must read as a positive number; anything else keeps the default rather than being obeyed as zero. |
| `SLOPP_SERVER_JVM_OPTS` | JVM options for the server process, space-separated. Empty by default. `-XX:+UseSerialGC -Xms32m` measured 2.62 GB -> 2.17 GB committed (~17%) but cost ~31% on boot-to-ready, so it is offered for hosts running several writers rather than shipped on. |
| `SLOPP_IMAGE_JVM_OPTS` | JVM options for every owned image, space-separated. Defaults to `-XX:+UseSerialGC -Xms32m`, which cuts committed memory per image 25.6% (722 -> 538 MB) at no measurable throughput cost. The two flags are a pair — either alone is worse than neither. Set it empty to restore the previous collector. |
| `SLOPP_NO_RECYCLE` | Switch off image reuse entirely. Costs speed; set it only when a reused image is suspected of carrying state between tenants. |

The last four matter when **several agents write on one box**. Each agent runs
its own server, and each server owns image JVMs; the active one is doing work
and the rest are latency insurance that multiplies by the number of agents.
Turning them down buys memory with image-boot latency and changes nothing
about verification.

## Server flags

```sh
slopp server [port]      # the slopp server: one slopp for the machine — yours to start; nothing starts it for you
slopp server stop        # stop the recorded one; `status` asks it
slopp <op> [json]        # one tool call, routed to the slopp server (fails, and says so, when none answers)
slopp --doctor           # self-check java, jar, hooks, skills, store probe (through the slopp server)
```

The slopp server's port is the argument, else `SLOPP_PORT`, else 7357 —
one knob, read by the slopp server, the plugin's MCP url, the hooks and `slopp
<op>` alike, so they cannot disagree. The slopp server records itself in
`~/.slopp/server.json` (the default port) or `server-<port>.json`; setting
`SLOPP_PORT` names a different one, such as a project's dev instance.
Neither is ever started by a call or by the plugin:
the slopp server is yours (`slopp server`), and a dev instance is run by a
project's dev config. A slopp server loads its code once at boot and serves it
until restarted; the in-progress version of a project runs as its dev
instance, which the slopp server re-serves at each done.
