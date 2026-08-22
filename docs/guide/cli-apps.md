# Command-line apps

You write commands. slopp writes the program.

There is no `-main` to write, no argument parser to wire up, and no list to
register a command in. A command is one `defn` whose name metadata carries its
whole contract, and `build` generates the entry point from every command in the
store.

!!! note "Not the same page as [CLI and CI](cli.md)"

    That page is about driving *slopp itself* from a shell (`slopp --call`).
    This page is about building a command-line program *with* slopp.

## Opt in once

```clj
config_file {path "capabilities" key "cli.enabled" value "true"}
```

Every cli rule, and `query_surface`'s `:cli` section, is inert until that is
true.

## A command is a defn

```clojure
(defn ^{:cli/command "greet"
        :cli/doc     "Greet someone by name."
        :cli/args    [:catn [:who :string]]
        :cli/opts    [:map [:loud {:optional true} :boolean]]}
  greet "Greet." [ctx args]
  (.write (:cli/out ctx) (str "hi " (:who args) "\n"))
  nil)
```

`:cli/args` and `:cli/opts` are [malli](https://github.com/metosin/malli)
schemas — the same language `rest` declares an endpoint contract in, so one
shape can serve a command and an endpoint without being written twice.
Positionals are a `:catn` because argv is ordered *and* named, and that is the
one malli schema which is both. Options are a `:map`.

A command that genuinely takes nothing says `[:catn]`. "Takes none" and "never
said" are different statements, and only the first satisfies the gate.

## A command writes its answer

This is the decision the rest follows from, and it is deliberately narrow:
**slopp owns argv, the usage text, the streams and the exit status. You own
every character of output.**

The context carries three streams. `(:cli/out ctx)` and `(:cli/err ctx)` are
writers; `(:cli/in ctx)` is a reader. Write whatever your program should print
— a line, a table you formatted, JSON, nothing at all.

The return value is the **exit status**: an integer becomes the process's
status, and `nil` (or anything that is not an integer) is 0.

!!! warning "A number at the end of a body is an exit code"

    `(count xs)` as the last expression of a command exits with 3. Only an
    integer can be a status, so there is no way to tell a deliberate one from
    an incidental one — end on `nil` when the value is incidental.

`run` *returns* the status rather than exiting; the generated launcher is the
only place `System/exit` is called, and it flushes first because
`System/exit` does not drain a buffered stdout.

### Testing it takes no process

`fake-context` swaps the three streams for strings and goes through the same
`run`, so a whole invocation — resolve, parse, call, exit — is an ordinary
in-image assertion:

```clj
query_eval {code "(cli/run (cli/fake-context
                             {:cli/commands (cli/commands-in '[myapp.commands])})
                           [\"greet\" \"world\"])"}
;; {:cli/exit 0 :cli/out "hi world\n" :cli/err ""}
```

You assert on **text**, and that is the point rather than the price. slopp used
to render a returned map so a test could be an `=` on data — but that map is a
shape no user of your program ever sees, which is exactly the defect
[`slopp.rest/call`](web/typed-apis.md) exists to remove for HTTP. For a command
line, stdout *is* the wire. The text is what a person reads and what a script
pipes, and `fake-context` keeps it assertable without a process.

### Reach for the injected stream, never the ambient one

`println` and `*out*` are a *different* stream from the one your context
carries: no test captures it, no driver redirects it, and the fake cannot stand
in for it, so the output simply escapes. `System/exit` is worse — it ends the
process from inside business logic, so the launcher's own exit, a driver and a
test never run. The `cli-direct-stdio` gate refuses both, and refuses nothing
else: writing is expected.

## What you get without writing it

- **Usage text**, generated from the same schemas the parser validates against,
  so help cannot drift from behaviour.
- **`--help` exits 0.** Asking for help is not an error, and a non-zero
  `--help` breaks any script that checks status.
- **A bare invocation lists the commands** instead of erroring. A bare call is a
  question; a usage error alone would make you run a second command to learn
  anything.
- **An unknown command names the ones that exist.**
- **A parse failure writes to stderr and leaves stdout clean**, so a caller
  piping the output gets nothing rather than half an answer.
- **Exit codes**: 0 for success and help, 2 for a usage error.

## The three gates

Enabling `cli` arms three write gates. `query_capabilities` shows them before
you opt in, which is the point of showing them:

| Rule | Refuses |
|---|---|
| `cli-args-schema` | a `:cli/command` with no declared `:cli/args`. argv is untrusted input; what a command accepts is declared, not parsed in the body. |
| `cli-command-collision` | two forms claiming the same command name. |
| `cli-direct-stdio` | a command body that reaches for an *ambient* stream (`println`, `*out*`, `read-line`) or calls `System/exit`. Writing to `(:cli/out ctx)` is expected. |

## Building it

```clj
build {dir "/tmp/greet"}
```

`build` writes `src/native/main.clj` — the launcher — plus `build-native.sh`
and a `deps.edn` with the `:native` alias. The launcher requires every
namespace that declares a command and hands argv to slopp's own `run`.

The require matters: commands are discovered with `find-ns`, and a namespace
that never loaded contributes no commands rather than failing. A launcher that
listed a namespace without requiring it would produce a program with no
commands and no error.

`app.name` names the binary **and** the program in its own usage text. That is
one setting deliberately: if the two could differ, generated help would teach a
command the shell does not have.

!!! warning "`app.main` and `cli.enabled` are refused together"

    Both declare an entry, and a build has room for one. `cli.enabled` means
    slopp generates the entry from your commands; `app.main` means your own fn
    is handed argv and owns the parsing, the streams and the exit code itself.
    Keep whichever you meant.

## What ships with your app

slopp vendors the framework source into the built tree — `slopp/cli.clj` and
`slopp/cli/spec.clj` — and declares what that source needs in the generated
`deps.edn`. Nothing resolves against a repository, and the tree is
self-contained.

**Only the capabilities you use.** A cli app gets no `slopp/web/**` and none of
the HTTP framework's dependencies, so `(require 'slopp.http)` in it fails. What
slopp vendors follows what your code actually reaches for — its requires and its
entry markers — rather than what your config enables, which is deliberate: a
project part-way through a config migration still boots, and its tools can still
tell it what is wrong.
