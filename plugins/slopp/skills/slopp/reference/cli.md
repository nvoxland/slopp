<!-- reference topic `cli` — served whole by `help {topic "cli"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## Command-line applications (`cli`)

**You write commands; slopp writes the program.** Turn it on with `config_file
{path "capabilities" key "cli.enabled" value "true"}`, then a command is one
`defn` carrying its whole contract in name metadata:

```clojure
(defn ^{:cli/command "greet"
        :cli/doc     "Greet someone by name."
        :cli/args    [:catn [:who :string]]
        :cli/opts    [:map [:loud {:optional true} :boolean]]}
  greet "Greet." [ctx args]
  (.write (:cli/out ctx) (str "hi " (:who args) "\n"))
  nil)
```

There is no list to register it in and no `-main` to write. `build!` generates
the entry, over every namespace in your store that declares a command.

**A command WRITES its answer and RETURNS its exit status.** Output is yours,
all of it — slopp formats nothing. What slopp owns is argv, the usage text, the
streams and the exit: `(:cli/out ctx)` and `(:cli/err ctx)` are writers,
`(:cli/in ctx)` is a reader, and an INTEGER return becomes the process status
(`nil` and anything else are 0).

Watch the one hazard, because there is no way for slopp to see it: **a body
whose last expression is a number exits with it.** `(count xs)` at the end of a
command is exit code 3. End on `nil` when the value is incidental.

Testing needs no process — `fake-context` gives string writers and you assert on
the characters a user would see:

```clojure
(cli/run (cli/fake-context {:cli/commands (cli/commands-in '[myapp.commands])})
         ["greet" "world"])
;; => {:cli/exit 0 :cli/out "hi world\n" :cli/err ""}
```

Asserting on TEXT is the point rather than a cost. slopp used to render a
returned map and let a test be an `=` on data — which is a shape no user of the
program ever sees, the same defect `slopp.rest/call` exists to remove for HTTP.
For a command line, stdout IS the wire.

Reaching for the AMBIENT stream — `println`, `*out*`, `System/exit` — is what
`cli-direct-stdio` refuses: that is a different stream, which no test captures,
no driver redirects and no fake stands in for, so the output escapes.

**Arguments are declared in malli**, the same language `rest` declares a
contract in, so one shape can serve a command and an endpoint. Positionals are
a `:catn` because argv is ordered AND named and that is the one malli schema
which is both; options are a `:map`. `[:catn]` is how a command says it takes
nothing — "takes none" and "never said" are different statements, and only the
first discharges `cli-args-schema`.

What you get for free, and never write: usage text generated from the same
schemas the parser validates against; `--help` exiting 0 (a non-zero `--help`
breaks any script that checks status); a bare invocation LISTING the commands
rather than erroring; an unknown name naming the ones that exist; a parse
failure writing to stderr and leaving **stdout clean**, so a caller piping the
output gets nothing rather than half an answer.

**Two settings, and the second one is a trap you cannot fall into.**
`app.name` names the binary AND the program in its usage — one string
deliberately, since help that teaches a command the shell does not have is
worse than no help. And `app.main` beside `cli.enabled` is REFUSED: both
declare an entry, and the version that used to happen silently gave you a
launcher calling your fn directly, with none of the parsing, streams or exit
codes you turned the capability on to get.

`query_surface` reports the `:cli` section: every command, its doc, and its
declared arguments — the same metadata the gates enforce, so the report and the
refusals cannot disagree.

