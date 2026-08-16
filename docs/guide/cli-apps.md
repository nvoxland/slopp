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
  {:greeting (str "hi " (:who args))})
```

`:cli/args` and `:cli/opts` are [malli](https://github.com/metosin/malli)
schemas — the same language `rest` declares an endpoint contract in, so one
shape can serve a command and an endpoint without being written twice.
Positionals are a `:catn` because argv is ordered *and* named, and that is the
one malli schema which is both. Options are a `:map`.

A command that genuinely takes nothing says `[:catn]`. "Takes none" and "never
said" are different statements, and only the first satisfies the gate.

## A command returns data

This is the decision the rest follows from. slopp renders the returned value
and sets the exit code, so a command is an ordinary function of data:

```clj
query_eval {code "(cli/run (cli/fake-context
                             {:cli/commands (cli/commands-in '[myapp.commands])})
                           [\"greet\" \"world\"])"}
;; {:cli/exit 0 :cli/value {:greeting "hi world"} :cli/out "…" :cli/err ""}
```

A wrong flag, a missing argument, a non-zero status — all `=` on a map, with no
process to spawn and no captured text to parse back. `run` *returns* the exit
code rather than exiting; the generated launcher is the only place
`System/exit` is called.

Return `:cli/exit` to choose the status yourself. Without one, a return is
success.

### How the value is rendered

A map prints as aligned `key value` lines. **A sequence of maps prints as a
table** — header once, columns aligned, long cells truncated:

```
file   from  subject
a.md   ann   the first one
bb.md  bo    another
```

That case is here because it is the one a `list` command returns, and one
`pr-str` per row is a wall of EDN: correct, machine-readable, unreadable.

And that is the whole of it. No colour, no wrapping, no column selection, no
`--format`. A command that wants more returns the string it wants — rendering
is the framework's job only for as long as it is doing a better job than you
would.

`:cli/in` and `:cli/out` are on the context for the two things a return value
cannot express: reading stdin, and reporting progress while work is still
happening. Writing to those is fine — it is what they are for. Reaching for the
*ambient* `*out*` is what the `cli-direct-stdio` gate refuses, because a
`println` in a command body is a second output channel nobody renders,
interleaved with the first by accident, and invisible to a test asserting on the
return value.

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
| `cli-direct-stdio` | a command body that prints, reads a line, or exits. Answer by returning. |

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
the HTTP framework's dependencies, so `(require 'slopp.web)` in it fails. What
slopp vendors follows what your code actually reaches for — its requires and its
entry markers — rather than what your config enables, which is deliberate: a
project part-way through a config migration still boots, and its tools can still
tell it what is wrong.
