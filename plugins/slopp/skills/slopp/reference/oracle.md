<!-- reference topic `oracle` — served whole by `help {topic "oracle"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## Questions → the oracle

Run code instead of reading callers: `query_call {sym "my.ns/f", args [X]}`
(the reference is CARRIED — renames/moves/the unused gate see it; args are
printable data) · `query_eval "(...)"` for arbitrary expressions (read-only
REPL, image pre-loaded — questions OF the code) · `query_store
"(fn [store] ...)"` (read-only analysis over the immutable store VALUE —
questions ABOUT the codebase: counts, metadata sweeps, custom aggregation
no canned query covers; fully-qualify, no effects/interop)
· `query_observe` (capture args/returns at runtime)
· `query_depends {on X, direction?}` — THE dependency question, any
kind: a namespace, a var (`ns/name`), or a `:keyword`; `:dependents`
(default) = who uses X (callers, refs, field flow, affected tests);
`:dependencies` = what X reaches. On a var taking or passed a MAP it also
returns `:shape` — the keys the form READS (destructured, body, `:=>`
schema, `:or`-optional) against the literal keys its callers PASS, grouped
by key-set, with the diff in `:mismatch`. **Renaming a key, or asking who
supplies one, is this read — never a grep.** `:unknown-shape` names the
callers passing a non-literal: trust `:mismatch` only as far as that list
is empty · `query_brief` (the edit dossier) ·
`query_macroexpand`. Re-reads are FREE: a view you already received THIS ASK
returns a tiny `:already-sent` stub — re-fetch instead of carrying source in
context. The stub is scoped to the ask, so after a context clear or a
compaction the next ask gets payloads again; when one does appear and you have
not in fact seen it (a subagent shares the session), its `:detail` id is a
`query_detail` away.
History is ONE door:
`query_history` routes by args ({} episodes · {ns name} a form's life — an
imported form's first version carries `:origin {:git-sha :remote}`, and
that IS the recorded reasoning when no ask preceded it ·
{ns name at} time-travel · {ns name effort true} what that form COST to get
green · {at} was-green-at · {contains} which asks
touched X · {dead_ends true} the SCRAPPED explorations, {dead_ends "some.ns"}
those that touched it); `report {since, contains}` for summaries and handoffs — `since "start"` is the lifetime, and the asks arrive whole with their deltas.
The number to read in `effort` is `:cycles` — red→green RECOVERIES, i.e. things
that had to be fixed. A form with two versions and two cycles was harder than
one with twenty and none, and nothing else tells you that. It carries
`:measured` because verification only started timing itself recently, so a
recorded total covers part of a long form's life and says which part.

**When the answer is for a HUMAN, hand over a page, not a port.** Your tools
answer questions; a page lets someone LOOK. The pages are slopp-ui's — a
separate slopp application, one per machine — and they cover every project
the daemon holds: a commit-point timeline, a per-commit-point change review
(module → namespace → form, with each form's recorded ask, its line diff and
its blast radius), form permalinks by ID with callers above and callees
inlined below, and the namespace index.

**What `session_brief` reports is `:api`** — this project's own read API on
the daemon, `http://127.0.0.1:7357/slopp/projects/<slug>/api`. It serves
JSON, plus the project's own surface as EDN, one document per capability:
`/rest/paths`, `/http/paths` and `/webapp/paths` under that base (the last
two are usually empty). It has no pages in it: a human opening it sees JSON,
so it is the address to hand a PROGRAM (a client generator, a script,
slopp-ui itself), never a person.

**One daemon, every project.** The daemon (`slopp daemon`, port 7357) serves
every open project under one prefix — `/slopp/projects` lists them, and each
answers under `/slopp/projects/<slug>/…` from the moment an agent attaches
until the last one detaches. There is no per-project listener and no port to
collect: slopp-ui reads that registry directly and fronts each project by
slug. Nothing registers, nothing beats and nothing goes stale — the daemon
that holds a project is the one that lists it. The API answers about the
project's landed branch, so warranty and observed examples are the branch's;
your un-landed work is yours to read through your thread.

That split is worth knowing about even if you never touch slopp-ui, because
it is the shape a slopp app takes when it consumes another one: slopp-ui
generates its typed client from each project's published `/rest/paths` and
talks to a store it cannot open. See "Consuming someone else's API" above.

**When you hit a dead end, revert cleanly and say WHY.** `undo` walks back
your OWN writes by delta — `{deltas n}` for the last n, or `{to :last-commit}`
to scrap everything since the last commit point (the usual "this whole approach
was wrong" move) / `{to :last-done}` to your last done. Always pass a
`prompt` naming *why* you're abandoning it: that records the revert as a
searchable **dead-end**, so a later session (or you) running
`query_history {dead_ends "some.ns"}` finds "someone tried X here and dropped
it because Y" instead of re-walking it. `episode_revert` scraps the whole
episode. Reverting before a `commit_point` leaves the commit point history clean
— the dead end shows in `dead_ends`, not in the commit log.

