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
`query_history` routes by args ({} episodes · {ns name} a form's life ·
{ns name at} time-travel · {ns name effort true} what that form COST to get
green · {at} was-green-at · {contains} which asks
touched X · {dead_ends true} the SCRAPPED explorations, {dead_ends "some.ns"}
those that touched it); `report {since, contains}` for summaries and handoffs.
The number to read in `effort` is `:cycles` — red→green RECOVERIES, i.e. things
that had to be fixed. A form with two versions and two cycles was harder than
one with twenty and none, and nothing else tells you that. It carries
`:measured` because verification only started timing itself recently, so a
recorded total covers part of a long form's life and says which part.

**When the answer is for a HUMAN, hand over a URL rather than a wall of
pasted source** — your tools answer questions, a page lets someone LOOK.
There is a browsable view of the store: a commit-point timeline, a per-commit-point
change review (module → namespace → form, with each form's recorded ask, its
line diff and its blast radius), form permalinks by ID with callers above and
callees inlined below, and the namespace index.

**Hand over `session_brief`'s `:hub`, not its `:ui`.** Those are different
things and giving out the wrong one wastes someone's time:

- `:ui` is THIS project's own listener, and it serves `/api/*` — JSON, plus
  its own surface as EDN, one document per capability: `/api/rest/paths`,
  `/api/http/paths` and `/api/webapp/paths` (the last two are usually empty). It is already running (the server starts
  it at boot), it runs on your live session so warranty and observed examples
  are the ones you actually have, and it has no pages in it at all. A human
  opening it sees JSON.
- `:hub` is this project's own page on the hub — a separate application, one
  per machine, that renders every screen and fronts each project at
  `/p/<slug>/`. That is the address a person wants, and the screens hang off
  it: `<hub>/change/<from>..<to>`, `<hub>/store`,
  `<hub>/store/form/<id>`.

**`:hub` is present only while a hub is ANSWERING**, because the slug in it
comes back on each heartbeat and cannot be fabricated. If no hub is running you
get `:hub-note` instead, naming the address that is silent — a hub is
optional, so its absence is an ordinary state rather than an error. Don't paper
over the note by handing out the bare hub root: nothing is serving it.

`ui_serve` controls your own listener (port, restart, `{stop: true}`); serving
again evicts the previous server rather than moving the port. It does not
start the hub, which is not slopp's to start.

**On a machine running several slopp projects, hand over the HUB's url
instead.** Your server serves this project's `/api/*` and binds a port derived
from its store dir — it must serve its own, because warranty and observed
examples are only CURRENT in the session doing the work, and another process
reading the same store sees the last verified run rather than the one you are
changing. Those derived ports are not addresses a human should have to collect.

The hub is a SEPARATE APPLICATION (the `slopp-ui` project) that a user starts
once per machine. It needs no store and never opens one: it holds a registry
fed by heartbeats, renders every page a human looks at, and proxies
`/p/<slug>/api/*` to whichever project owns that slug. Every project
registers itself every few seconds, and one that stops answering is greyed out
rather than dropped. Configure with the `slopp.hub.port` capability (`0` = don't
register); the interval comes back on the registration response, so the two
sides share no compiled-in number and can be different releases.

That split is worth knowing about even if you never touch the hub, because it
is the shape a slopp app takes when it consumes another one: the hub generates
its typed client from each project's published `/api/rest/paths` and talks to
a store it cannot open. See "Consuming someone else's API" above.

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

