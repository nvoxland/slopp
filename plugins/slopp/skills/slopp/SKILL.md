---
name: slopp
description: "Work efficiently with a slopp codebase over MCP: form-addressed reads/writes with built-in verification, provenance, and a live REPL oracle. Read this before your first slopp tool call."
---

# Working with slopp

<!-- skill-written-against: d077b2e3837dc -->

This page is ONE screen on purpose. The chapters it used to carry (web, rest,
cli, capabilities, the long form of everything below) are `help {topic}` —
the same files as `skills/slopp/reference/<topic>.md` — read when the task
needs one, not up front. The stamp above is the store head this text was
written against; `session_brief` `:host :jar :head` is what the running
server was built from. The tools win either way: a refusal comes from the
jar and cannot be stale, so if this text and a refusal disagree, the
refusal is right.

**The tools are fourteen families; an operation is called through its
family with `op`.** `edit {op edit_group steps prompt}`,
`read {op query_slice ns name}`. Every name below is an op; its family is
in the family's own index (its description) and in `help {topic tools}`.
`done` and `commit_point` take no `op`. Under Claude Code the fourteen load
up front (the plugin marks its server `alwaysLoad`); if they ever show as
deferred, load them ONCE, in one call —
`ToolSearch {query "select:mcp__plugin_slopp_slopp__orient,mcp__plugin_slopp_slopp__read,mcp__plugin_slopp_slopp__edit,mcp__plugin_slopp_slopp__verify,mcp__plugin_slopp_slopp__declare,mcp__plugin_slopp_slopp__history,mcp__plugin_slopp_slopp__depends,mcp__plugin_slopp_slopp__refactor,mcp__plugin_slopp_slopp__eval,mcp__plugin_slopp_slopp__done,mcp__plugin_slopp_slopp__commit_point,mcp__plugin_slopp_slopp__store,mcp__plugin_slopp_slopp__build,mcp__plugin_slopp_slopp__slopp" max_results 14}`
— an op name (`query_slice`, `edit_subform`) is never a tool name, so
searching for one finds nothing.

**What slopp is.** Code lives in a **store**, not files; the unit of
everything is the **top-level form**; a **live JVM image** runs your code
continuously. Address code as `ns` + `name` — never a path or a line. Every
write hot-reloads, runs exactly the tests that exercise the touched forms,
and records your stated intent (`prompt`). Form ORDER is derived
(definitions before callers) — write forms in any order and never ask where
one goes. Requires are added for you when exactly one namespace can supply
an alias (`:auto-require` says so). Turns and identity are automatic: the
prompt hook records your ask, and a write carrying `prompt` opens its own
turn; never call `turn_begin`.

## The loop — an ask is typically 2–4 calls

1. **Your ask ARRIVES with its map.** The context above your ask carries
   the bundle: the forms that matter, ranked, with the sources of what the
   ask names — current, not to be re-read. Orientation is done; start
   thinking. Read more only for a second question, and batch it:
   `read {op query_batch ops [{op query_source targets […]} {op
   query_depends on "x/y"} …]}` — several questions, ONE call.
2. **ONE `edit_group` carries the intent**: the code, its tests, the
   caller it changes — and `accept ["ns/test" …]` for the tests whose
   literal expectations your change is MEANT to move. Say the WHY in
   `prompt`; it is the recorded intent forever.
3. **The result is the verdict.** `:test {:ran :pass :status}` — the
   covering tests by name; `:finisher {:applied …}` — the expectation
   shifts you declared, already applied and re-verified; requires and
   module edges declared for you (`:auto-require`, `:auto-module-dep`);
   a test naming a var you haven't written yet lands `:red-first`. A
   residual red is yours to think about — `:failures` carries
   `:implicated`, `:expected`/`:actual`, and `:proposed` when the fix is
   mechanical. Do not re-read what you wrote; do not `restart` or
   `test_run` after a green.
4. **Stop.** `done` runs itself when your session stops. Call `done
   {label}` yourself only to read the verdict mid-way. `full_check` and
   `commit_point` are the human's grain — not the loop's.

## Which write

| you want | call |
|---|---|
| ANY write — one intent, one or many steps | `edit_group {steps [...] prompt accept}` — steps carry `action` (add\|replace\|subform\|delete\|require) plus ns/name/source/match; a ONE-STEP group is the single-form write; `accept` names the tests whose literal expectations the change should move |
| new form(s) | `edit_group` — one `:add` step per form |
| replace a whole form | `edit_group` — a one-step `:replace` |
| a change INSIDE a big form | `edit_subform {ns name match source}` — `match` is ONE exact subform; `text: true` for strings; `where: {key value}` for a registry row; `after: <neighbour>` to insert behind it |
| rename a var (callers follow) | `edit_rename {ns from to}` |
| rename a concept store-wide (vars, keywords, prose) | `rename_sweep {from to dry_run true}` first, then without |
| extract a subform into a fn | `edit_extract {ns from name match}` (or `at` for a large one) |
| new namespace | `ns_create {ns source}` — whole source, or `requires` to scaffold |
| require / module edge | usually automatic; else `ns_add_require {ns require}`, `module_dep {from to}` (a refusal names the edge) |
| delete | `edit_delete_form {ns name}` — callers first; a delete with a caller is refused and names it |
| undo my last writes | `undo {deltas n}` |
| a tracked non-code file (README, config) | `slopp {op file_get path}` / `slopp {op file_put path content prompt}` — the store tracks it; an edit made with `sed` on the projection is drift |

## Tests — the two rules that are yours

- **Write the failing test first and watch it fail.** A form naming a var
  that does not exist yet lands as a `:red-first` stub; implement it and the
  same test goes green. A green you never saw red proves nothing — `done`
  flags it (`assertions-never-red`).
- **Assert the behaviour, not the fixture.** A repro can be too minimal; a
  test whose expected value was copied from the actual value is not a test.

## Reading a result

`:ok true` — landed and green. `:test` — what ran. `:affected` — how many
forms/tests the change reaches. `:failures` — `[{:test :implicated
:attribution :expected :actual}]`; a failure with a literal delta carries
`:proposed {match source}` — the one `edit_subform {text true}` that accepts
the new behaviour, if the change was deliberate. `:warnings` — new lint, with `:suggest`.
`:red-first` — stubs you now owe. `:auto-require` — a require added for
you. `:hint` — one line slopp wants you to read. `:already-sent` — you
already hold this payload in this ask; `:source-already-sent true` on a row —
you hold that form's text at this version (a read you made, or your own
write), so it was not resent. `:truncated {:shown :of}` — what a
long answer cut; `query_detail {id}` fetches the rest ONLY when the missing
part changes what you do next.

## One question, one call

| you want to know | call |
|---|---|
| what matters for this ask, with source for what it names | `orient {ask}` |
| the namespaces you are about to work in, whole | `read {op query_source targets ["x.a" "x.b" "x.c"] full true}` — SEVERAL in one call; one call per namespace is the file habit in new clothes |
| what changed here, why, and what was asked — a handoff | `history {op report}` (`{contains "x"}` narrows; `query_changes` for per-form diffs) |
| who calls X / what X reaches / the module graph | `depends {op query_depends on "ns/x"}` (`modules true`) |
| what a fn really does with real inputs | `eval {op query_observe ns name code}` |
| is the whole store green, and what artifact is behind | `verify {op full_check}` — the human's grain, not the loop's |
| how the teammate runs the suite | `slopp --call test_run '{"external":true}'` from the project dir |

## Refusals teach

A refusal names the rule and the way out. Do what it says; never route
around it (no Bash/`sqlite3` against the store, no hand-written `declare`,
no file edits — the store is the code).

## When you need more

`help {topic}` — `tools` (the families and every op), `web` (pages, routes, client code), `rest` (typed APIs),
`cli`, `capabilities`, `running` (dev servers, the UI), `writing` (every
write tool in depth), `results`, `effects` (deps, purity tiers, escape
hatches), `threads` (what `done` lands), `agreement` (lists that must
agree), `oracle` (`query_eval`, `query_observe`, `query_store`), `tools`.
Design and idiom before non-trivial code: the `slopp-style` skill. Setup,
sync, shipping: `slopp-setup`. Reviewing: `slopp-review`.
