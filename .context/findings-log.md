# Findings log

Historical findings from user tests, eval rounds, probe sessions, and
self-dogfooding turns. **This is a record of what was OBSERVED, not what was
decided** — settled decisions live in `.context/decisions.md`, and open
frictions live in `ideas/`.

Kept because the observations are evidence: several slopp rules exist
because of a specific failure recorded here, and re-reading them is how we
check whether a rule still earns its cost. Nothing here is authoritative
about current behaviour — a finding describes the system on the day it was
written.

## Review sweep 2026-07-20 — adversarial whole-store review + fixes

A six-agent adversarial review of the whole store (each reader verifying its
findings against the live image), then fixed across eight milestones
(d9432..d9660). What it surfaced, grouped by where the defect lived:

- **Vocabulary migration was half-done and it crashed a live tool.** The
  d9077/d9157 tier rename (`:reads`/`:effects` → `:internal`/`:external`)
  migrated the write gates but not the reporting arm: `purity-standing` still
  ranked with the retired table, so `query_depends {modules true}` NPE'd on any
  store carrying a canonical tier. A shared `canonical-tier` normalizer now
  backs every reader, and `module-tier!` stores canonically. LESSON: a rename
  enforced by some consumers but read by others drifts silently — the ones that
  only READ a value (a keyword in a rank map, a filter set) are invisible to the
  gate that renamed it. This is the keyword-as-second-class-reference problem
  (`ideas/correspondence/keywords-are-second-class-references.md`) as a live incident.
- **The functional-core gate had reachable holes.** `:pure` admitted console IO
  (`println`), watch mutation (`add-watch`), a var-quote CALL `(#'f x)` (kondo
  sets no `:arity`, so it read as a data carrier), and a `(store/late-ref …)`
  into the shell (invisible to both the require graph and effect derivation — and
  the dialect gate ROUTES agents to late-ref). The D3 dialect denylist matched
  whole symbols, so `(clojure.core/eval x)` sailed past while `(eval x)` was
  refused, and banned symbols hid in literal metadata. All closed; the
  read-only `query_store` sandbox also admitted static interop
  (`Files/delete`), now refused.
- **The milestone gate was quietly weaker than a plain done.** `commit-point!`
  called `done!` with `:external? false`, so a milestone skipped the impacted
  `^:external` slice a standalone done runs — reproduced laundering a touched red
  external test green. Fixed to a real done; `decisions.md` D-full-check now
  spells out that "touched" is load-bearing. (A pinned test had drifted to pin
  the bug; rewritten to pin the decision.)
- **The edit pipeline could corrupt name-addressing and lie about reverts.** A
  replace that RENAMED a form onto an existing name landed two definitions of
  one name; the group path skipped the ambiguity refusal; deleting a `(ns …)`
  form was allowed (and `undo!` of an ingest generated exactly that); `undo!`
  reported success on a conflicted group. All guarded; every lost-race conflict
  now heals the image (the loser's code was hot-loaded, and a two-writer store
  kept the losing image answering with rejected code).
- **The process shell leaked JVMs.** The parent-death watchdog (d9279) installed
  too late — any throw in the spawn→connect→inject window abandoned an nrepl JVM
  that outlives parent death; it now boards the child's launch command line.
  `read-port`'s timeout wasn't enforced during a blocking read; the external test
  runner was unbounded and watchdog-less; `close!`/`open!` leaked on a partial
  failure. All fixed.
- **Two false-positive fixes I DIDN'T make, and why.** Defaulting `done!`'s agent
  to the session id (to close a nil-agent "footgun") broke 42 tests — the
  direct-API convention is nil-consistent by design; reverted. The nil-agent
  laundering is unreachable via the wire. LESSON: `full_check` caught both the
  chunk-1 stale-test regression and this over-fix — a broad change to shared
  machinery is exactly when the whole-store gate earns its cost.

The friction of doing all this THROUGH slopp (where it slowed vs helped, and
larger rethinks) is logged in `ideas/logs/dogfooding-agent-frictions.md`.

## F — user-test findings (status)

F1 failure-details in results ✅ · F2 atomic edit groups ✅ (calculator bench
−49% wall) · F3 `{:error}` on unparseable source ✅ · F4 `create-ns!`/
`ns_create` ✅ · F5 `add-require!`/`ns_add_require` ✅ (structural, dup-checked)
· F6 VFS-mapped stack traces ✅ (nREPL load-file + row padding; frames cite the
exact lines `query-source` shows) · F7 ✅ **decided (user): `!` = mutation only**, per Clojure convention —
stdout/console IO is NOT a `!` trigger; if IO tracking ever matters it becomes
a separate `:effects` fact, never a naming rule · F8 ✅ (ingest tidy-return; `:untested`
flag on edits no test exercises; `build!` emits `src/` + minimal `deps.edn`).
Details: `projects/calculator/REPORT.md` (untracked) and `.context/dogfooding.md`.

## T — tasker user-test findings (round 2, through the MCP wire)

T1 ✅ deftests exempt from the `!` rule · T2 ✅ orientation queries
(`query_namespaces`, `query_outline`) · T3 ✅ edits report only NEW `!`
violations + `:existing-warnings` count · T4 ✅ ingest/ns-create load the image
FIRST and commit only on success — a failed require/compile returns `{:error}`
with no store/image drift · T5 ✅ `query_eval` is observe-only by construction
(`edit/observe-gate` rejects def/in-ns/ns-unmap/alter-var-root/...; calling
effectful fns remains allowed — that's observation) · (obs.) hot-editing a
`(def x (atom ...))` form resets its in-image state — tests re-seed so verify
is unaffected; D5's defonce-preservation opt covers it if it ever matters.
Details: `projects/tasker/REPORT.md` (untracked).

## S/E — symmetric-eval findings (fresh agents driving slopp per model)

S1 ✅ **every write must compile**: all hot-loads checked against the candidate
store before commit; forward refs rejected at write time ((declare) is the
mutual-recursion escape); partial group loads restore a fresh image. This
transformed weak-model runs (haiku: +114% vs Go → beat Go outright on
inventory). S2 ✅ `edit_move` (stylistic/structural reorder; `:move` delta) ·
✅ `ns_remove_require` + unknown-tool errors list available tools (agents
invented both names) · E1 ✅ edit_rename arg aliases + clear missing-arg
errors (every sonnet/opus run guessed name/to first) · E2 ✅ SKILL.md teaches
the two-write red-first TDD shape (fn+test in one group → honest red →
replace). Full data: `benchmarks/results.md` symmetric-eval sections.

## R — eval round 2 (modify-and-extend, seeded codebase)

**Honest result: files won at ~60-line scale for all models** (+32..98% tok,
2.3–3.2× wall). Cause ranking: batching (files cover clustered changes in 2–4
whole-file writes; slopp paid ~10–20 verified round trips), per-write
verification wall (kondo re-runs now memo-cached ✅), schema guessing (arg
aliases + validation messages ✅), redundant test_runs (SKILL guidance ✅).
Correctness/safety all held: rename flawless for every model, checkpoint lint
caught a real ordering mistake, zero wrong-behavior incidents. **Fork partially resolved — W1 (user decision):** whole-namespace batch
writes are allowed for BRAND-NEW namespaces only (never overwrite): `ingest`
is that path, now with the standard verified-write tail (side benefit: it
seeds the trace map, so narrowing works from the first edit).
**W1 follow-up (tool consolidation, 2026-07):** the separate `ingest` MCP tool
was folded into `ns_create` as an optional `:source` mode — `ns_create` was
already `ingest!` of an empty ns, so two creation doors were one primitive with
a "which do I use?" fork. Now ONE door, two mutually-exclusive modes:
`:requires` scaffolds an empty ns to grow with red-first TDD (the default for
new behavior — ingesting finished code skips red→green); `:source` lands a
whole namespace at once (ported/reference/data). The `ingest!` engine fn stays
internal (git-import, seeds); "ingest" is gone from the agent-facing surface. No
alias kept (slopp has no installed base; an alias would re-introduce the fork).
Deferred
verification / whole-ns overwrite remain off the table. The scale side of the
fork (10+-namespace eval, too big to read whole) is the next experiment. Data: benchmarks/results.md; report: projects/eval2/REPORT.md.

## X/N — eval round 3 (scale) findings

X2 ✅ rename hot-loads the renamed DEF first (hash-order destroyed cross-ns
renames) · X3 ✅ image loads follow `store/ns-dependency-order` (topological;
map-key order went hash past 8 nses and silently half-loaded images from
`open!`) and failures throw loudly · X4 ✅ `build!` guarded (absolute paths
only, never a dir enclosing the running process, never clobber an existing
deps.edn — an eval agent built into the host repo) · N1 ✅ `!`-named callees
count as effectful anchors (cross-ns effect propagation).
**Round-3b verdict (the crossover, measured):** at 12-ns scale, slopp beat
files on aggregate tokens (−9%) and tool calls (104 vs 155); sonnet −42%
tokens vs its files baseline; files' costs grew +54% avg with scale while
slopp's stayed flat-to-down. Full data: benchmarks/results.md,
projects/eval3/RUNS.md.

## B — benchmark/baseline findings

B1 ✅ **terse green responses** (from the Go-baseline comparison): MCP write
results compress to `{:ok true :delta id :tests {:ran n :pass n} :affected n}`
when green-and-quiet; full detail on :error / red / NEW warnings / :untested /
explicit `:verbose true`. Measured: output tokens −32–38% across all three
benchmark apps (calculator 906→590, inventory 502→311, wordstats 542→370).


## P — probe-session findings (the dev agent as user, 2026-07)

Observed while building G8–G10 through slopp's own tools (MCP + stdin
probes). Same instrument as the eval rounds — self-reports from real usage.

P1 ✅ **The E2 multi-form guard is pair-blind.** Matching one `case`
branch, one `let` binding, or one map entry trips the "match spans multiple
forms" refusal — pairs ARE two forms, but they're one logical unit. Fired
three separate times this round; workaround each time was matching only the
value form (loses the key/binding as an anchor). Fixed: a TWO-form match
landing on a pair boundary of a paired container (map literal, binding
vector, case/cond clauses) addresses the pair as a unit — the whole pair
span is replaced (splice still applies, so one entry can become two);
misaligned or non-pair multi-form matches keep the hard error, and extract
still refuses pairs (a pair is not an expression).

P2 ✅ **Change-signature is a mined workaround now performed by the
maintainer.** Changing `commit-paths` from 3 to 4 args form-by-form was
(correctly) refused by the lint gate — callers momentarily had invalid
arity; the resolution (ONE atomic edit_group: defn + all callers, plus
`ns_add_require` first for the new alias) had to be discovered, not
suggested. Built, both parts: (a) invalid-arity refusals now append the
resolution hint (defn + callers in ONE edit_group, or change_signature);
(b) `change_signature` op — `source` replaces the defn, every CALL site's
arg list is rebuilt from a `calls` template ($1..$9 = the site's existing
arg sources; the callee stays as written, so aliases survive), executed
through edit-group! so every gate applies in one pass. Higher-order
references aren't rewritten (returned under :manual); nested self-call
sites and template/arity misses are hard errors pointing at edit_group.
The demand rule worked as designed: instrument fired → deferred op built.

P3 ✅ **No one-shot CLI for tool calls.** When the session's MCP server
died, the fallback was hand-rolled JSON-RPC over `--snapshot` stdin with
EDN payloads staged as files to survive shell quoting. Built:
`slopp.kernel.boot --call <tool> [args]` (args = JSON, EDN, or `@file`) — sugar
for `--main slopp.mcp/call-main!`; `mcp/call!` opens a durable
turn-enforced session for ONE dispatch. P1/P2 were built through it.

Ideas parked (not demanded yet): per-push CI via pass-through grafting of
main's workflows into the projected slopp tree at push time (source of
truth stays main — revisits the G10 trade-off, user call); more
`render-config` formats (`:properties`, `:edn`) when a consumer exists;
`import!` also wiring `.mcp.json` for zero-config onboarding.


## Q — self-dogfood findings (rocks 1-2 build turn, 2026-07-13)

Friction measured on the maintainer while building through the live
plugin; staged in cost order.

Q1 (✅ 2026-07-13) **:untested defeats the terse result shape.** Any write to an
untested form returns FULL verbose — including the delta's complete
:sources, echoing multi-KB forms the agent just sent (the tools registry
and call-tool came back whole six times in one turn). Fix: :untested is a
flag ON the terse shape; delta :sources never ride write results;
:untested doesn't fire for deftests or pure-data defs.

Q2 (✅ 2026-07-13) **Isolated-suite debugging is a five-minute blind loop.**
test_run {:isolated true} returns counts only — finding WHICH test failed
means rebuilding jar-src and running clojure -M:test by hand. Fix:
isolated runs accept :ns/:only selectors and return failing test names +
messages, like in-image runs.

Q3 (✅ 2026-07-13) **The trace map dies with the session** — every cross-process
write reports {:ran 0, :affected :all}. Persist it in the store: fixes
CLI/probe verification and is the prerequisite for lifetime warm
narrowing (Rock 6's terrain).

Q4 (✅ 2026-07-13, first pass) **slopp.mcp's monoliths fight the form-is-the-unit thesis.**
The tools registry and call-tool dispatch are the hottest-edited spots
and the worst-shaped: decompose to per-tool defs + a handler map.

Q5 (✅ 2026-07-13) **edit_subform fragment errors teach nothing.** "Unexpected
EOF" on a match that ends mid-expression cost three round trips this
turn. Say where the fragment breaks and suggest the enclosing form/pair.

Q6 (✅ 2026-07-13) **Eval instrumentation gaps:** the transcript miner must be a
checked-in script with run-window scoping (an ad-hoc version silently
summed rounds sharing a path); a `slopp doctor` CLI self-check (jar
cache, hooks, turn automation, serving) would have collapsed two
hook-debugging detours.

Q7 (✅ 2026-07-13) **Image-spawning tests are a silent trap for fresh agents.** A
deftest that opens sessions/spawns JVMs (api/open!, repl/start!,
http/start-server!) without ^:isolated would be run IN-IMAGE by per-write
verification — recursion/hang the author can't diagnose. The maintainer
avoids it by knowing the rule; nothing enforces it. Gate it at write
time: detect the pattern, refuse with the fix named ("tag it ^:isolated —
it runs in the external suite").

Q8 (✅ 2026-07-13) **A no-trace verification looks like success.** {:ran 0
:status :green} reads as "verified" when it means "nothing ran" — an
agent in a fresh/CLI session could ship unverified work believing it
green. Until the trace map persists (Q3), such results must say
:coverage :none plus the one-line fix (run test_run once); green must
never be inferable from a zero-test run.

Q9 (✅ 2026-07-13, shared missing-form-error + fragment refusal; audit continues opportunistically) **Every {:error} should meet the cold-load bar.** The
cold-load refusal names its three resolutions inline ("define earlier,
edit_move, or (declare ...)") and cost zero follow-up; the edit_subform
fragment errors taught nothing and cost three round trips. Audit every
error string in api/mcp/sync/edit: each names the next ACTION, in tool
vocabulary. Errors are the only teaching that arrives exactly when the
agent needs it (G12 corollary: richer results beat more instructions).

Q-series resolution notes (same day): Q1/Q8 in `summarize` (strip
:source/:sources everywhere; :untested terse; :coverage :none) + deftest
never :untested; Q2 `isolated-test-run!` :ns/:only + :failing via
`parse-test-failures`; Q3 `persist-trace!`/`load-trace` on store meta;
Q4 tools registry → 6 per-group defs + concat, call-tool tail → 3
handler maps (hot clauses stay in the case); Q5 fragment refusal in
`find-unique-subform`; Q7 `edit/isolation-refusal` on every replace/add
path; Q6 `benchmarks/mine_transcripts.py` + `slopp --doctor`. Suite
263/1351 green.

Q10 (✅ 2026-07-13) **Milestone publish gap** (eval8 P5): five plugin sessions
produced four milestones that never reached the repo's slopp branch —
nothing at commit_point prompts a git_push or syncs the LOCAL slopp
branch, so a files-path teammate gets the seed. The eval agent caught it
by luck of a thorough P5. Fix direction: commit_point on a git-cloned
store offers/performs the local slopp-branch sync (G12: just do the
series), or at minimum the result names the unpublished delta.

Q11 (✅ 2026-07-13) **Bulk rename UX** (eval8 step 3): the one slopp loss (1.49×
controllable vs files) — a global rename is one sed sweep for files but
edit_rename-per-form for us. A pattern/set-accepting rename (or
edit_plan step) closes it.

Q10/Q11 resolution (same day): commit_point at the MCP layer publishes
the projection when git-remote meta is set (green milestones only;
publish trouble rides as :published {:error} without failing the
milestone); rename! results carry :mentions (word-boundary hits of the
old name that the structural rename could not rewrite — docstrings,
strings, comments) + a hint. Rock 3 scoping: edit_group grew :subform
(structural or :text) and :require steps — rename + prose fixes +
threshold change is now ONE call (eval8 step 3's measured overhead).
Full edit_plan (interleaved reads, :when-green barriers) stays deferred
until demand observation shows sequences groups can't express. Suite
266/1359 green.

Rock 4 (✅ 2026-07-13): query_flow + query_impact shipped. Flow is a
boundary-guarded textual keyword scan (kondo keyword analysis deferred
until false hits show up in demand); impact reuses kondo var-usages —
:arity marks a call site, its absence marks a value/higher-order ref
that no template rewrite reaches — plus trace-map coverage. Suite
267/1363 green.

Rock 5 (✅ 2026-07-13): draft_test shipped — deftest drafts from
OBSERVED calls (rt/observe via the query_observe machinery; each capture
becomes an assertion) or a signature skeleton with named TODO holes.
Suggestions only (red-first dogma intact). Known v1 limit: observe's
200-char arg truncation makes big-arg drafts need hand-editing — the
:note says to read each assertion. Suite 268/1368 green.

Stale-dump hazard (lesson, 2026-07-13): the Q4 bulk split sliced tool
descriptors from a dump captured BEFORE a same-session edit and silently
reverted it (test_run's Q2 description). Bulk rewrites from a dump must
re-dump immediately before slicing, or diff the dump against the store
first. Caught by re-reading the descriptor; re-applied.

Q12 (✅ 2026-07-13) **The handoff trust spiral** (eval8 r2, step 5): given a
high-stakes "summarize everything for a teammate" ask, the agent got the
full answer from the history views in 5 calls, then burned ~15 more
re-verifying through side channels (raw sqlite over store.db, a git
worktree re-running the suite, duplicate test_run) — against the skill's
explicit instruction. Q10's git milestones gave it a THIRD source to
cross-check. Direction: results that PROVE alignment instead of asserting
it — query_commits/query_git rows carrying "slopp branch @<sha> == this
milestone's projection" so one call answers the cross-check the agent
will otherwise perform by hand. G12 corollary: richer results close trust
gaps; instructions alone don't.

Q12 resolution (same day): sync/alignment resolves the configured local
remote's slopp-branch head and compares it to the latest milestone's
minted sha (m8) — query_commits returns {:commits rows :alignment
{:aligned bool :note}} whenever a git remote is configured; the note
says explicitly that no worktree/sqlite cross-check is needed (or that
git_push publishes). http remotes stay rows-only (no network in a
query). Suite 269/1371 green.

Ratio push (2026-07-13, from the eval8 n=3 decomposition — orientation
18%, reads 19%, history views 18%, Bash escapes 14%, actual code 15%):
session_brief = one-call orientation (names-only project, counts when
large; milestones; alignment; the loop) targeting ~600 tok/session;
report {since contains} = the handoff composite (milestones + changes
with recorded asks + last verification + alignment) replacing the
history fan-out; OPTIMISTIC EDITS = missed/ambiguous subform matches
return :source-now (the form's current text) so agents compose writes
from the brief and correct from the error instead of pre-reading.
Skill loop rewritten around brief -> optimistic writes -> one report.
Deliberately skipped: tool-description diet (2.1k tok total exposure,
~100 tok/session — teaching value beats the saving); rename-in-group
(multi-ns group surgery, not a measured cost — step 3 already 0.72x).
Suite 272/1383 green. Target: eval8 r4 lifetime ratio ~0.5x.

G13 (standing, from eval8 r4): **composites must snip their prose.** A
composite that carries verbatim descriptions/asks GROWS with history and
gives back every token it saved (session_brief 751->1,773/session;
report ~2.1k/call). Caps: brief = 5 milestones x 110 chars; report asks
= 3 x 140 chars; full text stays one query away. Any future composite
(dossier, plan result, hint) ships with a size budget and a spec
asserting it.

G14 (standing, 2026-07-14): **adoption is not a strategy — defaults
are.** Every measured win came from changing what happens WITHOUT the
agent's cooperation (auto-import, auto-turns, auto-publish, trims, caps,
outline-by-default reads, the hook micro-brief); every disappointment
came from offering a better tool and hoping (optimistic edits: 0 uses;
query_brief: 487 lifetime tokens). New capability ships as the DEFAULT
path or with the default path routing into it — never as an optional
improvement beside the expensive one.

Defaults wave (same day): query_source {ns} returns the OUTLINE unless
full:true (the wire default; api/query-source stays the raw VFS read for
internals); report self-fits under the trim gate via fit-report
(progressive: 1 ask/row, then take-20 with an honest count — the capped
batch double-paid ~2k twice re-fetching 8.1k-char reports); the
UserPromptSubmit hook (now hooks/prompt-hook.py) injects a ~40-token
micro-brief (store present, ns count, last milestone) so the ls/git/
README scouting ritual has nothing left to discover. Suite 274/1392.

Cards wave (2026-07-14): **opacity with a warranty.** form-card = the
interface view of a form (sig, doc line, effect marker, the recorded WHY,
covering-test warranty) at ~10x under source; query_slice = full source
for ONE entry point + cards for its reachable neighborhood (BFS over
query-deps, depth 2 default, capped 8 + :omitted). The warranty is
mechanical, not rhetorical: edits re-run covering tests, violated
assumptions turn red with :implicated — that's why trusting cards is safe
here and hopeful in files. history-stitch hint (2 history calls -> report,
once/session, track-hint! machinery). Plugin ships agents/slopp-reader.md
(read-only comprehension subagent returning CONCLUSIONS — the context-
carry answer for large codebases; unmeasurable at eval8 scale, ship it
for the scaling terrain). Card gap noted: no observed input->output
examples yet (needs persisted observe captures) — the strongest possible
behavior line; revisit when demand fires. Suite 276/1404.

Q13 (✅ 2026-07-14) **The isolated runner REPLs out on inline-test projects**
(eval9): build!'s generated deps.edn only mints the :test alias for
suffix-convention/test-dir tests, so `clojure -M:test` on an inline-test
project starts a REPL and isolated-test-run! reports {:status :error}
with a Clojure banner. Fix: has-tests? should count inline deftests (the
index knows), and the alias's runner should require+run ALL namespaces'
tests; failing that, refuse with the fix named (Q9 bar).

Q14 (✅ 2026-07-14) **Bulk rename at scale is the measured loss** (eval9 step 3:
13.6k tokens / 37 calls / ~7 errors / one restart vs sed's one pass at
5.8k; the run left an empty ns shell). ns_rename + edit_rename + key
subform edits compose per-form; a docs-team-style rename (ns + fns +
keys + prose, 40+ dependent nses) needs ONE intent-level op:
rename_sweep {from "zone" to "region" :kinds [ns fn key prose]} planned
store-wide, executed as one group, one verification. Q11's :mentions and
group subform steps were the small-scale versions; this is the at-scale
completion.

Q13/Q14 resolution (same day): generated :test alias runs `-d test -d src
-r .*` (inline deftests count via a source scan; build! mkdirs test/ so
the runner never REPLs out); rename_sweep {from to} = ns-renames first
(requires rewritten along), then every still-matching form rewritten in
ONE dependency-ordered group (definitions hot-load before callers), one
verification — boundary-guarded segment match, prose and keywords
included by design. Root-cause bonus: rewrite-symbols returned z/root's
:forms wrapper, so EVERY changeset-rewritten form (var-rename callers,
ns-rename decls) silently lost its :name — dependency ordering broke on
the next image rebuild; latent since the changeset machinery landed,
surfaced only when the sweep renamed an alphabetically-early consumer.
Suite 279/1413 green.

Store-integrity pair (2026-07-14, found BY eval9's sweep cells — the
fresh-session-per-step protocol is a persistence fuzzer no single-session
spec replicates):
1. **Resurrection**: try-commit! filtered touched nses to those present
   in the new store, so a renamed-away ns never reached append!'s
   delete — its rows lingered and the NEXT session loaded both old and
   new namespaces. db/persist! had the same skip. Any durable store that
   ever ns_renamed and reopened was affected.
2. **Ghost vars**: a replace that renames (single edit_replace or group
   step) loaded the new var but never ns-unmapped the old one — stale
   vars then failed as noise in later verifications (the sweep's own red
   came from a ghost zone-t, not from the sweep).
Lesson recorded: durable-session specs must include a CLOSE + REOPEN leg
when they mutate namespace identity; single-session green is not
persistence green. Suite 281/1418.

G15 (standing, 2026-07-14): **the knowledge-differential stance.** The
server models what the session has been told (told-tracking: cacheable
views hash-keyed per session; identical re-reads return an :unchanged
stub, so re-fetching is FREE and agents need not hoard context) and what
the task needs (the verbatim ask, kept as :last-intent, is mined
deterministically against form names; the brief arrives with :relevant
interface cards). Composites scale by AGGREGATION, never amputation
(fit-report rolls changes up by namespace; briefs roll up >=5-sibling
namespace FAMILIES) — eval9 proved take-N amputation CAUSES fan-out:
agents hunt for what was dropped. New front door: query_depends {on X}
answers what-depends-on for a namespace / var / :keyword by
classification + delegation (user-requested; ns-level had no tool at
all). Suite 286/1438 green.

Consolidation wave 2 (2026-07-14, user decision): ONE dependency door —
query_depends {on, direction} (dependents: ns -> required-by + refs,
var -> blast radius, :kw -> flow; dependencies: var -> callee tree,
ns -> requires) — and ONE history door — query_history routed by args
({} episodes, {ns name} form life, {ns name at} time-travel, {at}
was-green-at, {contains} ask search). Twelve wire tools retired
(references/deps/flow/impact/outline/symbol/namespaces/form_history/
form_at/status_at/search_history/lineage); every api fn stays; the wire
surface is 58 tools. Evidence: the specialized quartet had ~5 calls
TOTAL across the whole eval campaign — taxonomy-choice was the barrier,
not capability; the outline gate had already shown removal converts
where offering never does. Deferred deliberately: modal write clusters
(files/branches/deps/git) — distinct verbs are safety-relevant and the
traffic is negligible. Suite 286/1441 green.

G16 (standing, 2026-07-14): **smells are data.** Deterministic bad-usage
detection lives in slopp.mcp/smell-registry — one [key fires?-pred msg]
entry per smell over bump-smell-counts; adding a smell is one entry, no
plumbing. Fire policy is anti-spam BY CONSTRUCTION: once per session +
once per 30 minutes per store (db-meta cooldown), suggestions never
refusals, one line naming the better tool. Current catalog: test_run
streaks, scattered singles, history stitching, whole-ns dump streaks
(-> query_slice), rename streaks (-> rename_sweep). Standing practice:
smells observed in dogfooding (mine or eval transcripts) get an entry
the same day. Two bugs fixed en route: hints now ride STRING results
(source dumps dropped them), and some-over-registry replaced a cond
whose first fired smell shadowed all later ones forever.

Bash-smell hook + card examples (2026-07-14): the smell system reaches
the channel the server can't see — a PostToolUse(Bash) hook injects
one-line redirections via hookSpecificOutput.additionalContext (the ONLY
PostToolUse output that reaches the agent — verified empirically; plain
stdout does not). Smells: raw store.db reads, git log/diff/show/blame in
store dirs, grep/cat of .clj files, clojure -e shell evals; 30-min
per-smell per-store cooldown (.slopp/bash-hint-cooldowns.json), silent
on any failure, active only where .slopp/store.db exists. And interface
cards now carry :examples — query_observe captures persist to store meta
(remember-observation!, called at the MCP layer to keep the api fn's
read-only contract clean) and cards surface up to two real
input->output pairs. Suite 289/1452 green.

Push-default regression (2026-07-14, bit the USER): git_push {url} saved
the url unconditionally, so a one-off push to "." silently repointed the
store's default remote away from slopp3 and broke the normal push flow.
Fixed: first-save only — an existing default is never rewritten by a
one-off push; results carry :default-remote so the standing default is
always visible. Also verified and worth knowing: local and remote slopp
branches are the SAME minted lineage (deterministic projection commits
via git_map), so a local push is never a divergent copy; the local
branch now tracks origin/slopp. Suite 290/1457.

D-local-mirror (user decision, 2026-07-14): **every milestone mirrors
into local git automatically** as refs/heads/slopp/<store-branch>
(slopp/main, slopp/my-branch) — local git durably carries the slopp
history with zero ceremony; REMOTE publishing stays explicit (git_push,
which never rewrites the saved default). sync/publish-local! never
touches git-remote meta; alignment (Q12) now proves against the local
mirror, so it works with no remote configured at all. Naming note: a
flat local branch named `slopp` blocks the slopp/* namespace — the dev
repo's old flat branch was deleted (identical to origin/slopp) and the
local listener remote renamed slopp -> slopp-store to clear ref
ambiguity. Remote publication branches (e.g. slopp3's `slopp`) are
unchanged. Suite 290/1459.

Mirror sync verbs (user design, 2026-07-14): git_push/git_pull have ONE
meaning each — push = send local slopp/<branch> mirrors to the remote
(sync/mirror-push!: ff-only, collision with a legacy FLAT `slopp` branch
refused with the migration taught, {:migrate true} performs it — same
minted lineage); pull = fetch remote mirrors into local slopp/* (ff-only)
AND absorb remote store history (existing 3-way pull!). Direction lives
in the verb, not a mode flag (the user's call: familiar git vocabulary
beats a unified modal tool). No-git dirs never fail: milestones stay
store-durable with no :published leg, and the verbs teach `git init`.
Ecosystem: slopp-branch? marker + clone!'s default resolution accept
slopp/main alongside legacy flat `slopp`; slopp3's branch is migrated to
slopp/main (the old flat sync/push! remains api/CLI-level for legacy
flat remotes). Suite 291/1471.

No-compat rule (user directive, 2026-07-14): **there is no slopp but
this slopp** — never write backwards-compatibility code; when a design
changes, migrate everything (code, specs, seeds, remotes) in the same
wave and delete the old path. Applied immediately: flat `slopp` branch
support removed everywhere (marker/clone/pull/push all speak
slopp/<branch>; clone's legacy-main fallback gone; git-branch config
key retired from config!/push!/clone!), mirror-push!'s flat-collision
migrate machinery deleted (flat is extinct: slopp3 + both eval seeds
migrated to slopp/main, READMEs and accept scripts updated), and the
stale-schema :where string-coercion removed (the restart protocol owns
schema staleness). Kept deliberately, NOT compat: agent arg-forgiveness
aliases (:old/:from etc. — they serve live agents guessing, not old
versions) and push! itself (fileless stores publish projections — a
capability, not a legacy path; git_push routes checkouts to mirrors and
fileless stores to projection publishing). Suite 291/1470.

Module system (user design, 2026-07-14): **enforced architectural
boundaries as a module system.** Module = first two ns segments
(`-test` folds into the subject's module); RECURSIVE VISIBILITY — deeper
namespaces are package-private to their parent prefix, `^:export` on a
defn's name hoists that var into the module surface (definition-site,
no var copying); cross-module calls require a DECLARED edge; a declared
edge that CLOSES a cycle is refused (the check is LOCAL — reachability
to→from — because -test folding makes some adopted cycles legitimate,
e.g. slopp.api↔slopp.db via each other's tests; a pre-existing cycle
never blocks an unrelated declaration); public-surface defns without docstrings warn
(per written form, transition-only — never a ns-wide nag). The manifest
ALWAYS exists (user: "we shouldn't have to explicitly init — start
modifying it from empty"): fresh stores are born enforcing with zero
edges; pre-module dbs adopt at open! (manifest derived from the actual
kondo-resolved graph = zero violations by construction); clone! ingests
gate-off then adopts what landed. Tracked CRDT-WAY with SEMANTIC calls
(user directive): the edge is the unit — one `:module-edge` delta per
declare/retract carrying its why; merges fold edges (adds union, never
a conflict; a cycle the union closes is surfaced as a note); writes go
through `module_dep` only (config_file "modules" is refused and
teaches); reads through `query_depends {modules true}`; the fold
projects as a `modules` file into git commits for transparency.
ns-rename/rename_sweep re-key manifest entries when a module's last ns
renames away. Suite 296/1520.

Inferred episodes — REPL-native flow (user design, 2026-07-15): agents
must NOT plan groups or worry about wire limits; they work like a REPL
developer and the SYSTEM infers the unit of work. STANDARD TERMS (use
everywhere): WRITE (delta) = one gated, hot-loaded, verified form-level
change; CHANGESET = internal atomic multi-form op (rename,
change_signature, normalize) — implementation detail, never an agent
decision; EPISODE = one agent's writes between done-points — inferred,
per-agent, the history unit; DONE-POINT = the boundary (`done {label}`,
or the turn-end hook): normalize + declare hygiene + lint + AFFECTED
TESTS over everything the episode touched + findings recorded on the
boundary delta; TURN = the user-ask bracket; MILESTONE = commit_point,
green-gated, spans turns. Decisions: verb renamed checkpoint→done
(journal op migrated one-off, 85 rows, user-approved); tests never
implicitly close an episode (explicit done + turn-end only; spot-check
test_runs stay in-progress, and a redundant pre-done test_run earns a
hint); edit_group + staging REMOVED from the wire (api-level
edit-group!/changesets stay internal); per-write verification stays
automatic. The invalid-arity lint gate is scoped to the WRITTEN form:
own-form errors refuse (with the change_signature hint), stale-caller
errors ride :carried-errors until done re-checks them hard. done always
verifies (not only when normalize rewrote); findings resurface in
session_brief :last-done + the prompt-hook heads-up. Concurrency note:
sub-agent isolation was never the group's job — form-grain CRDT
rebasing is; episodes are per-agent derived.

Self-hosting lesson (2026-07-15, cost one rescue): changing the
SIGNATURE of a write-pipeline function (edit/lint-refusals 3→4 args)
via a single write deadlocks the pipeline — the hot-loaded new fn
breaks the still-committed old callers, and no write can land to fix
them (there is no fallback pipeline; the jar is only a boot kernel).
Rescue: user-approved hand-minted append!-equivalent delta installing a
dual-arity bridge, then normal writes. RULE: pipeline-critical
signature changes go through the internal atomic changeset
(edit-group!/change_signature), never incremental writes — exactly the
carve-out the inferred-episode design keeps changesets for.

Module-graph honesty (2026-07-16): the module manifest folds `-test`
deps into the subject module (so TDD needs no ceremony), but that
pollutes the architecture graph — test fixtures calling `slopp.api`
manufactured a false 7-module cycle in slopp's adopted store. Decision
(user): the graph VIEWS (`query_depends {modules true}` :layers/:cycles)
compute over PRODUCTION edges only (non-`-test` namespaces;
`api/production-manifest`); the DECLARED `:manifest` and the write GATE
are unchanged (fixtures still declare their edges, enforcement intact).
slopp's own graph went from one condensed 7-module cycle to a clean
8-layer DAG with slopp.api alone at layer 4. Note: the false cycle only
arises via ADOPTION (module_dep's own cycle guard prevents declaring it
fresh).

The first deep-module split (2026-07-16): slopp.api's 8 pure history/status
helpers moved to package-private `slopp.read.history` via `edit_extract_ns` —
the first depth-3 namespace in slopp itself, proving the deep-module
machinery on real code. Dogfooding surfaced and fixed, red-first:
(1) hot-load-all!'s heal path boots from the COMMITTED store, so a
candidate-created namespace vanished mid-heal (FileNotFound) — the heal now
replays the candidate's touched nses first, and the pre-heal error rides out
as :first-err; (2) forms moved across a namespace boundary are PUBLICIZED
(refactor/publicize strips defn-/^:private) — module-grain visibility is the
boundary now, not var privacy; (3) the module gate was blind to
fully-qualified un-required calls (kondo emits no var-usage row for them) —
edit/qualified-usage-rows synthesizes those rows, quote-pruned; (4) -test
namespaces share the subject's prefix for deep visibility (fold-test-ns), so
package-private helpers stay unit-testable; (5) JGit transports now carry a
30s socket timeout after a half-dead fetch froze the single-threaded server
for 40+ minutes mid-publish (the milestone was already durable — journal
first); (6) summary-less test shards (JVM death under fork pressure) retry
once serially (:shard-retries). Remaining slopp.api clusters (testrun, deps,
branch, build; verify/session LAST, via changesets) are a follow-on decision.

The session-engine split (2026-07-17): slopp.api's pipeline substrate —
image lifecycle + journal commit + verification, 27 forms — moved into
package-private `slopp.ops.engine` via move-forms, live, with the server
executing the very pipeline being relocated (safe because a MOVE rewrites
all addresses atomically and the server hot-reloads only after the
consistent commit; contrast the D-series signature-change deadlock).
The two-way refusals DISCOVERED the layering: branch/deps plumbing
couldn't move before the substrate they call. Deep packages now:
history, testrun, session, deps, branch; the public verbs stay on
slopp.api (the surface). Engine specs live with the engine
(slopp.ops.engine-test); reap-idle-images! stayed a public verb so
branch specs stay honestly placed. move-forms hardening the campaign
forced: de-qualify refs into the target, moved sets carry their own
(declare ...), publicize/export under form-level meta wrappers, per-move
export levels compose across steps (the hook moved alone with
^{:export "slopp.concurrency"}).

slopp.api decomposition complete at the internals grain (2026-07-16):
seven deep packages — history, testrun, session (the engine), deps,
branch, modules, orient — hold the implementation; slopp.api keeps only
the public verbs and query composites (187KB from 231KB). Placement
rules that emerged: a PRODUCTION cross-module caller makes a helper
public API (adopt-modules! stayed for sync/clone!); specs live with the
machinery they exercise (session/modules/orient -test namespaces);
public verbs stay on the surface even when thin. The debt view caught a
real gate-vs-reality gap: a move's export pre-check trusted intent while
export-mark silently skipped a meta-wrapped name (^:dynamic) — fixed;
the gate reads declared plans, the debt view reads reality, keep both.

Tier-blind verification (2026-07-16, user decision): the in-image vs
^:isolated split is an IMPLEMENTATION DETAIL that must not leak into the
agent's contract. `done` now routes impacted ^:isolated tests to the
external tier automatically (require-closure slice keyed to the episode
boundary, capped at 4 nses; a larger slice defers LOUDLY via findings
:isolated-pending — resurfaced by the brief); `commit_point` green-gates
on the FULL isolated suite it runs itself (:force skips to honest red;
stores with no isolated tests skip the tier). The wave-end ritual is now
just commit_point. Explicitly rejected: auto remote push (the user asks
for mirror pushes), per-wave jar rebuilds (the jar is only the boot
kernel — rebuild on kernel/deps change only). Roadmap: warm isolated
runner + incremental build! make the tier cheap enough to remove the cap.

query_store — the store-value oracle (2026-07-16, user-approved surface):
read-only eval of one `(fn [store] ...)` over the immutable store value,
in the server process. Rationale: the image answers questions OF the
code; there was no oracle for questions ABOUT the codebase-as-data, so
one-off analysis kept becoming canned tools (review_scan) or tempting
raw db reads. Boundaries: pure-eval gate (no effects/defs/interop/IO),
fn-of-store shape only, worker + timeout, pr-str-capped output. The
stance "raw REPL eval may observe but never redefines" extends to the
store value: observation of an immutable pointer, never mutation.
Repeatedly-asked query_store questions are evidence for the next canned
tool.

The unused-public gate (2026-07-16, user decision): dead public surface
fails verification instead of riding as an ignorable advisory. done →
ERROR-grade lint + findings for touched namespaces; commit_point →
GLOBAL whole-store sweep (standing dead surface refuses the milestone
regardless of whose episode left it — episode-scoping provably leaked).
The escape is `^:unused-ok` on the name, and it is self-policing: a
stale marker (var now called) fails symmetrically, so the dial never
rots. Known blind spot the dial exists for: string-eval'd and
runtime-resolved entries (rt/observe, rt/traced-run, mcp/call-main!)
are kondo-invisible by nature. Kondo's unused-private-var already
covers privates per-namespace.

Designated reference carriers (2026-07-16, user decision — thesis-level):
references must not hide in strings or naked quoted symbols; they live in
BLESSED CARRIER positions the tooling reads as real edges, or in
DECLARATIONS. The carrier set: `#'var` literals for in-process references
in data (already hard edges — the preferred form); `store/late-ref 'ns/nm`
for load-cycle late binding (the ONLY sanctioned runtime resolution;
replaced the naked requiring-resolve in git/ensure-projected!);
`api/query-call` / wire `query_call {sym args}` for the oracle's common
invoke case (query_eval stays the escape hatch for arbitrary expressions);
`^:entry-point` on the NAME declares outside-world invocation (CLI, wire,
eval-injected — rt/observe, rt/traced-run, mcp/call-main! migrated from
^:unused-ok; no stale symmetry — the outside world is statically
unverifiable). KEYWORDS REJECTED as the replacement: a keyword names a
table slot, not a var — statically worse than a string. unused-report and
review_scan read carriers and declarations; a naked quoted symbol stays
data. Phase 2 (parked with ideas/): the dialect LINT that detects
var-shaped strings / naked requiring-resolve outside carriers and teaches
the sanctioned form; per-library carrier adapters (kondo-hook style) for
framework config; the unified reference graph builds over these so
references CANNOT hide rather than being tolerated. Scope note: carriers
relieve markers only for SAME-STORE drivers — slopp's own nested-store
test fixtures keep ^:unused-ok (cross-store references are invisible by
construction, and ordinary apps' tests call statically anyway).

Resolver denylist (2026-07-16, completes the carrier decision's teeth):
requiring-resolve, resolve, ns-resolve, find-var, and intern join the D3
denylist. Blocking var-shaped STRINGS is the wrong enforcement point —
docstrings mention vars and tests hold quoted symbols as data, both
legitimately inert. The gate blocks where a mention could BECOME a var;
refusals teach the carriers (store/late-ref, #'var literals) and the
^:unsafe owned-obligation escape. Sanctioned resolver homes: late-ref
itself and slopp.kernel.boot/-main (the OS boundary). Consequence: in gated
store code, a string can no longer become a reference — strings are
inert by construction, which is stronger than any string lint.

Metadata-mutator denylist (2026-07-18, dialect-prunes-human-conveniences
wave — the template's next cut after `declare`): alter-meta! and reset-meta!
join the D3 denylist. slopp reads every load-bearing marker (^:export,
^:unsafe, ^:reads, ^:auto-declare, :malli/schema) straight off the STORED
node, so a form's metadata IS its contract and must be SOURCE-only truth;
mutating a reference's metadata at runtime is invisible to that read (store
says one contract, the live var carries another). Fits the prune template:
helpful for humans, hard for static analysis, cheap for an agent to give up
(write the metadata inline for the same keystrokes). Unlike `declare` this
lives in dialect-check, so it binds BOTH the edit path and the dialect-scan
import path; the refusal teaches "write the metadata ON the form" and names
the ^:unsafe escape (host/slopp.kernel.rt legitimately mutate metadata as
instrumentation). with-meta/vary-meta return NEW values and are untouched —
the cut is exactly the two in-place mutators. Zero usage in slopp's own
store when added, so no adoption break. Orthogonal to the sandbox
(pure-eval-refusal), which is a separate property and was not touched.

THE reference graph (2026-07-16, user decision — single source of truth):
slopp.index.refs is the ONE place "who references what" lives. Producers
normalize in at the source (kondo statics + un-required qualified calls,
carrier positions, marker declarations as edges from :external); consumers
query refs/refs-to and never fuse sources privately — unused-report,
module-usage-rows (debt/drift), and review_scan ported this wave, which
also FIXED review_scan's scoped-scan caller counts (whole-store graph →
the :unused flag now works under :ns scoping too). Records anchor to
form-ids (semantic, stable), never line/column; rewriters re-derive
positions per-form at rewrite time. The graph is DERIVED (content-memoized
via the kondo cache), never stored — refs are an index of source; the
journal owes them no consistency, so merge/replay/branching need no new
machinery. Remaining consumers to port (recorded): move-plan's usage
assembly, rename's mention sweep, query-depends/query-impact blast, the
module gate row builders; plus the :observed producer (trace map — session
grain, needs an optional arg) and keyword/class targets. Convention going
forward: a tool reading kondo var-usages directly for a REFERENCE question
is a bug — add a producer or consume the graph.

Wire uniformity rule (2026-07-16, follow-up to the codec): the wire speaks
NAMES, always — one output dialect (the grouped-qsym form); short handles
are never an alternative output format. Rationale: reading is ~99% of
traffic and qsyms are self-describing (no resolution round-trip); opaque
ids are a hallucination hazard (a mistyped f4448 can silently resolve to a
REAL other reference, while a mistyped qsym fails loudly — names fail
safe); and handles are form-PAIR grain, not edge grain (static + carrier
between the same forms share one handle). Handles remain an ACCEPTED INPUT
convenience for future ref-consuming tools — which must equally accept
qsym pairs, so agents never need to convert — and are EMITTED only
alongside an actionable follow-up operation, never as noise columns.

Epic close — agent-native addressing end to end (2026-07-16): every
reference consumer ports to THE graph (gates via the ns-refs slice,
move-plan direction/caller analysis, query_impact blast radius — which
now shows agents carrier refs and outside-world declarations; the trace
map joined as observed-refs, giving :via :observed a real consumer in
coverage). qualified-usage-rows deleted — its job became a producer.
Rewriters (rename-changeset) and planner node-walks (imports-for) are
stance-compliant NON-consumers: positions and class names are rewrite-
time derivations inside single forms, not reference questions; external-
lib require selection stays kondo (outside the store graph's domain).
Diagnostics speak anchors: compile errors translate at the hot-load
chokepoint (edit/anchor-error → {:form :at}), lint rows carry :at
snippets, row/col never crosses the wire. kondo-cache bound 64→256 (a
101-ns store reset a 64-entry cache mid-sweep). The graph surfaced and
retired real debt on the way: store-test's undeclared render use became
an element-order assertion (better test, no cross-module reach).

Near-term scope: simple, dependency-lite projects (2026-07-16, user
decision). The target for slopp-built applications right now is SIMPLE and
DEPENDENCY-LIGHT. Deferred as explicit FUTURE projects, not near-term work:
HTTP-server support and how that integrates; framework dependencies
(routers, component/DI systems) and the carrier/kondo-hook ADAPTERS they'd
need; the dogfood PROBE APP (was proposed to gather arbitrary-app
evidence — hold until dependency-heavy support exists to probe). Consequence
for the roadmap: prioritize agent-ergonomics and gate-FIT for simple
projects (diagnostics-anchors, auto-avoid-declare, markers-carry-why,
functional-core-gate, fast-cold-truth) over anything justified mainly by
framework/dependency/HTTP complexity. The reference-carrier phase-2
"per-library carrier adapters for framework config" is part of the deferred
set. Don't build framework/HTTP support speculatively — revisit when the
project explicitly turns to it.

## W — the plugin colonised every project it was enabled in (2026-07-21, user report)

Reported by the user as "other projects are getting `.slopp` dirs created and
stuff maybe changing in there." The plugin was enabled in the user's GLOBAL
`enabledPlugins`, so its MCP server and hooks loaded in every project opened.
Measured across their checkouts before the fix:

| dir | state |
|---|---|
| `metabase/metabase` | 0 elements, 0 namespaces, **128** `"session pause"` checkpoint deltas |
| `metabase/metabase-4` | 0 elements, **31** checkpoints |
| `metabase/metabase-3` | 0 elements, **10** checkpoints |
| `bundlebase` | pristine auto-created store (4096-byte db), never written |
| `metabase-2`, `oxplow` | bare `.slopp/` holding only `pending-intent` |

Six repos, ~9 months of accumulation, and **not one byte of code in any of
them** — every delta was a session-pause checkpoint written by the `Stop`
hook into a store that existed only because serving had created it.

Three findings worth carrying:

1. **A footprint bug hides from its own tests.** Every store-level test passed
   throughout: they all ran on temp dirs where creating `.slopp/` was
   invisible and expected. The invariant nothing asserted was the ABSENCE of a
   write. The regression test now asserts exactly that (`db-test/
   a-storeless-dir-materializes-on-the-first-write`), and it went red on
   `(not (.exists sdir))` — the one claim the old suite had no way to make.
2. **A probe that creates what it probes.** `sync/empty-store?` opens the db
   to ask whether a dir's store is empty — and creating on open meant the
   question wrote the answer. It survived only because its caller
   short-circuits on `.exists` first. Asking should never be a write; that is
   now enforced by `{:create? false}`.
3. **The docs were right and the code was wrong.** `slopp-setup` had promised
   "creates an empty store on the first write" since it was written. Nobody
   checked the claim against the implementation, and the gap ran for months.
   Shipped prose is a testable assertion about behaviour — the P1 self-
   description gates check that prose names real tools, but not yet that it
   describes real behaviour.

## 2026-07-22 — #8 re-diagnosed: the host reloads its own main-line writes; branches were the blindness

Probed on the live dev server (which predated the day's changes): a
freshly-edited serving-machinery path (spot-run!) answered on the running
host WITHOUT a restart — the host hot-reloads its own session's commits.
SQLite `PRAGMA data_version` is per-connection and the watcher's connection
sees the session connection's commits, same process or not. The three #8
incidents all happened on the web BRANCH: branch writes land in
`.slopp/branches/<name>/`'s mini-journal, which `watch-live!` (watching
only `<dir>/.slopp/store.db`) never opens. Corrected operational rule:
**host code = the main journal, live (self-writes included); a branch
line's writes reload the image only — verify branch serving behavior in
the image or a fresh JVM.** The `:host` section of session_brief now
states mode, staleness, failed reloads, and the branch teaching, so the
next currency question is one read instead of a debugging arc.

## 2026-07-22 — adversarial code review of the friction waves + D-web, all fixed

Four independent read-only reviewers (store core, verification loop, web,
host/surface), each VERIFYING findings in the live image, plus a triage
pass. Every finding below landed with a red test modelling the defect.

**One HIGH, mine this session:** the fold-field registry refactor routed
state-carrying merge deltas (config/deps/tier/file) through `replay-delta`,
which re-conj'd them with THEIR verbatim id. Both lines of a fork allocate
from the same counter, so a durable branch merge that touched
capabilities/deps/tiers on both sides produced a duplicate delta id →
`db/append!`'s UNIQUE constraint failed the merge PERMANENTLY with a
misleading "store changed during merge". Ephemeral merge tests never reach
`append!`, so nothing caught it. Fixed by re-minting each crossed state
delta (fresh id + `:merged-from`), guarded by a pure journal-integrity test
AND a durable end-to-end branch merge.

**Verification-loop false-greens (mine):** `inert-ns-require-change?`
classified two behaviour-changing edits as inert (a method-free lib whose
require-CLOSURE loads defmethods; an ns-form metadata edit `edn/read-string`
silently dropped) → `done` skipped external tests. Now walks the closure,
reads metadata-preserving, and diffs against the last-done baseline.

**Data-loss (mine):** `ns_delete` identified the ns decl by NAME at three
sites, so a `(def x)` in ns `x` was mistaken for it → the namespace dropped
with a form still in it. Structural `body-forms` helper now used everywhere.

**Other regressions (mine):** `edit_subform :after` + `:match` duplicated
the neighbor (now refuses the combination); a late `^:breaking-ok` stopped
discharging across an intervening done (now discharges vs any wider unmarked
baseline); host-brief note precedence + delta counting.

**D-web security (pre-existing, all fixed):** `[:all]` empty-policy
auth-bypass; runtime effect-declaration not enforced; error-body ex-data
leak; unsalted git-projected password hashes + non-constant-time compares;
mandatory-OIDC-audience gap; static reader traversal (latent, contained
today by the single-segment router); `web.max-body-bytes` never enforced
(DoS); proxy-header casing. See `.context/decisions.md` D-web hardening.

**Non-finding recorded:** the registry data-defs read `:covered 0` in
review_scan (callable-data-invisible trace limit) — coverage is structural
via the generated merge harness, not absent. The module-edge nil-marker
clobber is unreachable (open! adopts before any write).

**A server restart can fake 4 lint ERRORS (2026-07-23).** A `full_check` came
back red with `slopp.index/lint is called with 2 args but expects 1` in four
namespaces, plus eleven `Unresolved var:` warnings — every one of them naming a
var added earlier the same day (`client-build-deps`, `client-signature`,
`compiler-coord`, `kondo-lang`, `maybe-recompile-client!`). All of it was false:
`lint` demonstrably has both arities, every "unresolved" var exists and passes
its own tests, and the external tier was fully green (691 tests). The cause is
the one `lint`'s own docstring names — "findings are NOT a function of `source`
alone; kondo reads cross-ns facts (arities, var existence) from its cache". The
MCP server had restarted into a fresh process, and its empty in-process memo no
longer matched a `.slopp/kondo-cache` predating those vars. `rm -rf
.slopp/kondo-cache` (pure cache, rebuilds) → green.

Worth knowing because the failure mode is maximally misleading: a red gate
accusing you of arity errors in code you did not touch, on a store whose entire
test suite passes. Suspect the cache when lint errors name recently-added vars
and the tiers are green — especially right after a restart.

**Frozen-form trap (D-web-contracts part 2):** `edit.modules/
missing-doc-warning` classified heads with a `'#{defn defmacro}` DATA literal —
but D4 bans `defmacro` *wherever it appears*, quoted data included
(`dialect-check` flags `'defmacro`). So the form had landed once (pre-strictness
or via a skip) and was then FROZEN: every later edit re-ran the whole-form
dialect scan and refused on its own body. The tell is a dialect refusal naming a
symbol you didn't touch. Fix that also unfreezes: compare the head by
NAME-STRING (`#{"defn" "defmacro"}`) so the form carries no banned literal. A
form that legitimately needs a banned symbol as data wants `^:unsafe`; a form
that only needs to MATCH on one wants the string compare.

## 2026-07-24 — the reviewer UI, checked for coverage; the speed claim was NOT tested

The wave-2 plan called for a task-based eval: ~8 real questions about this
codebase, five reviewer-shaped and three orientation-shaped, timed with the
UI against `query_search` / `query_depends` / `query_changes`, with the
explicit calibration that *"the defensible claim is better orientation and
structural recall, NOT faster lookup."*

**Half of that was run, and the other half cannot be run by me.** Recorded
plainly so nobody later reads a coverage check as a speed result.

### What was run: does the UI answer the questions at all

Five reviewer-shaped questions against the live store, milestone
`d13382..d13458`:

| Question | Answered by | Result |
|---|---|---|
| What changed in the last milestone, and where? | `/change/<range>` | 19 forms, `slopp.ui` 15 / `slopp.web` 4 |
| Why does `slopp.web.router/query-params` exist? | same page, per-form `:why` | the authored ask, verbatim |
| What breaks if I change `slopp.store/prompt-by-form`? | `/store/form/<id>` | 4 static callers, named, each a permalink |
| Is `slopp.ui.model/change-view` tested? | same page, warranty | 3 covering tests |
| Is the diff readable per form? | `/change/<range>` | 64 del / 310 add lines, marked |

All five answered. Two observations worth keeping:

- **The warranty count is only right because the UI runs on the LIVE
  session.** Served from a fresh session those reads would have been STALE —
  the warranty as of the last verified run, not the one being changed. (This
  entry originally said "session-grain and unpersisted … would have said
  zero"; corrected 2026-07-27 — `session/persist-trace!` persists the trace and
  `open!` reloads it, so the failure mode is staleness, not emptiness. The
  observation itself stands.) This is the single design decision the eval
  actually validated.
- **One answer looked wrong and was not.** A scraping script grabbed the
  wrong `<em>` and attributed another form's ask to `query-params`;
  checking the page directly showed the correct value. Worth recording
  because it is the failure mode this whole surface is FOR — a wrong answer
  that reads as plausible — and it came from my extraction, not the page.

### What was NOT run, and why

**The timed comparison.** Its subject is a human reviewer; I am not one,
and my costs are not theirs. An agent asking these questions has
`query_impact`, `query_changes` and `form-card` in one call each — I would
be measuring a population the page was not built for, and any number I
produced would be an argument dressed as evidence. The plan's own
calibration says the claim is orientation and structural recall, and
neither is measurable by proxy.

**So the UI currently has a coverage result and no efficacy result.** The
honest state: it answers the reviewer-shaped questions it was designed for,
against the real store, at ~0.9s for a 36-form milestone page. Whether it
makes a human faster or better-oriented is untested, and should stay
untested rather than assumed — a human review session is the experiment,
and it has not happened.

---

## 2026-07-25 — the first read of the turn instrument, and what it cost to trust

Nine turns had recorded `:timing` since rotation landed. Reading them was the
whole point of building it, and the reading changed the instrument twice.

**The aggregate, and why it was wrong.** 492 calls, 74,490s elapsed, 4,660s of
it slopp — a 6% share. Two of the nine turns were sessions nobody was in: one
recorded 46 calls, 224s of work and 45,501s elapsed, reporting `:slopp-share
"0%"`. A true division and a false statement.

**Excluding those two, the seven active turns are 22% slopp / 78% outside** —
the same 78% measured session-wide before the instrument existed. The figure
was right; the instrument could not state it, because turns rotate on the
WRITE-tool gate (deliberately — rotating on any call would break the
`readOnlyHint` read-only tools advertise) and so a turn straddles a human going
to bed, with the gap landing in `:outside-ms` beside agent reasoning.

**Refusals: 57 of 492 calls, 11.6%. Forty-one of the 57 were writes; 23 were
`edit_subform` alone.** The rate is actionable and the CAUSE was recorded
nowhere, so the only advice the number could ever support was "read that tool's
contract" — the guess, not the finding.

**What the reading is worth as a method.** Both defects were invisible from the
inside: the code was correct, the tests were green, and each number was a
correct computation over the wrong population. Neither would have been found by
building more instrument. The generalisable rule — *an instrument's first real
reading is a test of the instrument, and should be budgeted as one* — sits
alongside the older lesson from the same file that a tight cluster of numbers
feels like precision and is not.

**And the discipline the fix followed:** no classification table for the
refusals. Writing one from source greps would have invented a taxonomy rather
than derived it, which is what got `:positional-form-access` withdrawn at 4-5
false positives out of 5. The turn record carries the verbatim messages,
bounded, so the classes come from data on the next read. That read has not
happened; it needs turns, not analysis.

---

## 2026-07-25 (later) — the refusal samples, first read: 22 bounces, and the classes come out of data

The instrument's own follow-up. One turn has run under the version that keeps
verbatim refusal messages, and the point of the exercise was to check that the
CLASSES could be derived rather than invented — the reason no taxonomy was
written when the counting shipped.

They can. 22 refusals, `edit_subform` 10, `edit_add_form` 5, `test_run` 5,
`edit_replace_form` 4, `query_store` 2, `query_eval` 1. Reading the messages,
four shapes:

1. **A write naming an alias the ns form does not have yet** (`No such
   namespace: kernel`, `No such namespace: str`). Every one is the same
   two-step: add the require, resend the identical form. The largest
   mechanically-avoidable class in the sample.
2. **`edit_subform` matching a FRAGMENT that opens a delimiter it does not
   close** — trying to wrap existing code in a new `let`. The refusal already
   teaches the fix and hands back `:suggestion`; the cost is the round trip,
   not the recovery.
3. **`edit_subform` matching text that occurs twice** — a snippet present in
   both a fixture and the assertion about it. Recoverable from `:source-now`.
4. **Probe iteration** (`query_store` / `query_eval` refusing `read-string`,
   an unknown var). Not waste in the same sense: these are the cost of asking
   the store a question that turned out to be malformed, and each answer
   taught the next.

**n=1, and the sample is one agent on one kind of work**, so nothing should be
built off it yet — the ordering above is a hypothesis, not a ranking. What IS
established is the method: the classes were read off recorded messages instead
of guessed from source greps, which is exactly what the design refused to
short-cut. Another few turns and class 1 is worth acting on.

**Worth noting against the earlier reading:** these 22 came from a turn whose
refusals were mostly MY errors of composition, not slopp's contract being
unclear. A refusal rate is not automatically a defect rate, and the samples are
what make that distinguishable.

## 2026-07-31 — a mid-merge heal silently rewound the image, then lied about why

**Observed:** merging a branch that introduced a new namespace (`slopp.web.client`)
was refused four times with

```
Could not locate slopp/web/client__init.class, slopp/web/client.clj … on classpath
```

raised while `slopp.mcp-test` — a namespace that requires it — loaded. It read
exactly like a merge-ordering bug: dependents loading before a namespace the
merge had just created.

**It was not.** Every ordering hypothesis was measured and refuted:

- a minimal repro (branch adds `zz.newthing`, an existing ns requires it, merge)
  **passed** — new namespaces merge fine;
- `ns-dependency-order` on the merged store put `slopp.web.client` at index 85
  and `slopp.mcp-test` at 180, and simulating the merge's action walk put the
  `:load-ns` at position 8 against the dependent's `:hot-load` at 18;
- the topological sort never stalled (191/191 ordered), so its
  cycle-fallback-to-alphabetical never fired;
- `platform-for` was `:jvm`, so `load-ns!`'s `:cljs` skip was not involved;
- `slopp.ops.branch`, `slopp.store`, `slopp.image` and `slopp.store.merge`
  rendered BYTE-IDENTICAL on both lines, and the jar carried the interleaved
  pass — no vintage confound.

**The actual cause, in `session/hot-load-all!`:** its heal calls `fresh-image!`,
which boots from the **committed** store. Mid-merge, nothing is committed yet,
so the fresh image is the pre-merge world — every namespace the merge had
already loaded is gone. `replay!` then restored only the namespaces owning
THIS call's `form-ids`, and `merge-into-session!` calls `hot-load-all!` once
per namespace. So a namespace created EARLIER in the merge was never replayed,
and the dependent's `:require` hit a classpath that legitimately did not have it.

The docstring claimed this case was handled — *"a candidate that CREATES a
namespace (extract_ns) dies with FileNotFound when a survivor requires it"* —
and the covering test (`heal-path-replays-candidate-namespaces`) passes the new
namespace's ids IN THE SAME CALL. The fix was real and the test was honest;
both were **one call wide**, and a merge is many calls wide.

**Fix:** `replay!` now restores every namespace the CANDIDATE has that the
COMMITTED store lacks — precisely the set `fresh-image!` cannot know about —
in addition to this call's. Pinned by
`heal-replays-a-new-namespace-this-call-did-not-touch`, whose only difference
from its sibling is that it does NOT pass the new namespace's ids.

### The second defect, and the expensive one

`hot-load-all!` returns `{:err <post-heal> :first-err <pre-heal>}`, and every
caller read only `:err`. So the reported error was **an artifact of the
recovery** — a classpath problem that never existed — while the real compile
failure sat in a key nobody looked at. That is a D-surface-honesty violation of
the exact shape Core 1 exists to prevent: not "could not check" wearing the
face of "checked", but a MANUFACTURED diagnosis wearing the face of a real one.

It cost hours and four refused merges chasing an ordering bug that was never
there. `session/load-error-message` now composes both, labelled, and the two
merge call sites go through it.

**The generalisation worth keeping:** a recovery path that re-runs the failing
operation can produce a SECOND, different error, and that one is about the
recovery, not the fault. Any error surfaced from after a retry has to say which
attempt it came from — otherwise the recovery becomes the story and the bug
hides behind it.

## A move can silently TIGHTEN an undeclared purity tier (2026-08-02, restructure wave)

Measured while moving three namespaces out of `slopp.api`/`slopp.edit`/
`slopp.store`. The recorded belief going in was "a namespace move breaks its
purity tier" — from the `slopp.api.artifacts` → `slopp.store.artifacts` move,
where `done` reported the namespace landing under a `:pure` prefix while
supporting only `:external`.

That belief is wrong, and the correction matters because it points at a
different fix. **`ns_rename` carries an EXPLICIT declaration intact** —
verified on three renames in one episode (`slopp.edit.refs` `:internal` →
`slopp.index.refs`, `slopp.api.crossings` `:pure` → `slopp.index.crossings`,
`slopp.store.build` `:pure` → `slopp.build`); all three arrived declared and
the old keys were gone.

What it cannot carry is a declaration that was never made. `slopp.api.artifacts`
was UNDECLARED. Undeclared is `:external` only because nothing more specific
claims it — it is the ABSENCE of a claim, not a claim of `:external` (the same
distinction the skill draws for `remove: true` vs declaring the permissive
value). Tiers inherit by namespace prefix with most-specific-wins, so moving an
unclaimed namespace under a module that DOES claim something re-tiers it. It
landed under `slopp.store`, which is declared `:pure`, and inherited `:pure`.

**So the class is: absence-of-claim is positional, and a move changes
position.** Anything inherited rather than stated is re-decided by wherever it
lands, and `module_purity` does not check layering, so the contradiction
surfaces at the next `full_check` rather than at the move.

The rule shipped in `plugins/slopp/skills/slopp/SKILL.md`: declare the tier
before the move, so it survives by being stated instead of inherited.

Worth noting how the wrong version got recorded — the observed symptom (a
tier was wrong after a move) was generalised into a mechanism (moves break
tiers) without checking whether the tier had ever been declared. One
`query_depends {modules true}` read on the next three moves settled it. This
is the third claim in this wave that failed on contact with measurement, all
three from trusting prose over tool output.

## 2026-08-05 — adversarial review of the fake browser (d23523..d24127), day one of the feature

Two slopp-reader agents attacked the browser core and the gates/tool halves;
every finding image-verified before reporting. 24 findings + 3 from the main
pass; the clean list mattered as much (seq children, ambiguity refusals,
controlled inputs, the livelock fix, MCP schema↔handler honesty all held).

What recurred, for the pattern file more than the fix list:

- **Three of the four worst bugs were second producers of an existing
  derivation**: sugar parsing existed in slopp.web.html and not in the screen
  reader (a legal view rendered as NOTHING while staying fillable); two text
  functions with different join rules made the screen name a button the click
  refused; the drive-code cause chain drifted from read.query's on day one,
  exactly as its own comment predicted it might.
- **The gate graded the shape its author imagined** (defn-shaped): def /
  defmethod / defn- carriers all passed, and the defn- case produced the
  worst answer in the feature — the tool saying "no ^:web/page in this store"
  over a gate-approved page. Core 2's reach tell, fifth instance.
- **Every untested refusal path was broken; every tested one held.** drive!'s
  refusal crashed (keys of the VECTOR) — found independently by all three
  reviewers, in the one form with zero direct tests, whose ^:unused-ok note
  claimed a test that did not exist.
- **The prefix-and-its-length class again**: framework-injection's
  "slopp.web" prefix matched slopp.website (suppressing) and slopp.webhooks
  (spurious).
- **A dissolved namespace was never declared in the vocabulary**
  (slopp.web.screen), so its docstring kept telling apps to read
  :slopp.web.screen/document — a key no code writes — and no guard could
  fire. The declare-the-rename rule, violated by the author who wrote it up.

Format v2 (D-screen-format-v2) came out of the review's escaping finding plus
the user's design call; the fix wave landed same-day with red tests first.

## 2026-08-05/06 — consumer measurements that DECLINED features (slopp-ui, v2 evaluation round)

Worth filing precisely because they are not bugs: measured answers to "should
slopp build X", from the consumer who would use it.

- **A text stripper: already exists.** `{:detail :prose}` measured against a
  hostile page — inter-element whitespace collapses exactly as CSS renders
  it, `<pre>` stays verbatim, alt text is text, region-scopable. Building a
  stripper would be a second producer of one shape. "This surface is
  complete, do not extend it" should be findable the next time it is
  proposed.
- **A has-text?/does-it-contain? predicate: declined.** A boolean destroys
  the evidence channel that found `rate</a>[kg zone]` — the screen was IN the
  failure's :actual only because the assertion took a STRING. And in
  exploration, a predicate is an assertion offered back as if it were the
  readout; the readout's value is showing what you did NOT think to check.
  If a convenience is ever wanted: return the matched text or the whole
  region on a miss, never a bare boolean.
- **The real gap behind both asks was addressing grain**, closed as `:within`
  (element-level scoping through the click matcher's vocabulary, widened to
  the line-owning block). The stripper and the predicate were both attempts
  to compensate for pane-grain scoping.
- **Mechanism worth keeping: skill-vs-jar confirmability.** A fix landing in
  a SKILL file is confirmable the moment it lands (`--live` reads files); a
  fix in the JAR is invisible until a process restart, so one message's fixes
  legitimately carry different statuses on the same day.

## 2026-08-06 — the readout's first-week ratio, measured by its consumer

Four markup defects reached slopp-ui's green suite in one week; all four were
found by LOOKING at a screen, none by an assertion failing: `demo.orderstatic`
(form page), `rate[kg zone]` (outline row), `web⚡1 ns` and `core2 ns` (nav
rail heads). Two are wrong WORDS rather than missing spaces — the expensive
kind, because the result is plausible. One instance (the rail's namespace
rows) was invisible to every fixture they had — it renders only under an
external module and their fixture module is :pure — and was fixed by shape
recognition, THEN pinned by visiting a different namespace: the assertion came
from the fix, not the fix from the assertion. That ordering is the strongest
evidence yet for the readout's founding claim (look first, assert second),
and it is unreachable by an assertion-first habit by construction.

Same letter, the mode rule worth keeping: "use prose for text facts" is the
wrong generalisation — a control's CONTENTS are a structured fact, adjacency
is a prose fact, and neither mode is the default answer.

## 2026-08-06 — the search endpoint's agreed shape, and two rules from settling it

The /api/search contract is agreed in advance of any build (slopp-ui's
`ideas/reader-api-gaps.md` #9 holds the schema; the talk dir 2026-08-06
07:30–08:15 holds the reasoning). Both stores hold: typed hits, slopp-side
:rank, :matched naming why a row is listed (with `matched "source"` as a
finding about the store, not a good result), :address shipped IN the declared
contract so a route-scheme change is a contract change both sides see, search
over name/doc/why as first-class corpora (never a wrapped query_search), and
per-kind totals.

Two rules that outlive the endpoint:

- **"If it cannot be derived from what was RETURNED, it is not
  presentation."** (slopp-ui) Decides schema-vs-presentation boundaries by
  test rather than taste: per-kind totals are facts about the full result set
  (in the schema); highlight offsets are derivable from :doc/:why (out).
  Same test, opposite answers — which is what makes it a test.
- **When a wave closes, diff its asks against the talk dir, not against
  memory.** (both) The finished wave's plan recorded search as "asked for"
  and the ask never crossed; a plan is a derived claim about FUTURE work the
  way "measured, not assumed" was about PAST work. The habit is the cheap
  fix; machinery for two currently-careful agents is the case already
  declined, correctly.

## 2026-08-06 — standing gate debt measured at zero, and the obvious re-derivation is wrong by 18

Task #33 proposed a whole-store WRITE-GATE sweep, the gate-shaped twin of the
advisory sweep (#27). Measured before building, and the numbers re-scoped it.

- **`gate-check` over all 2,521 named forms: zero refusals, zero advisories.**
  Not a clean-because-unasked zero — both opt-in gates are already ON in this
  store (config `gates`: `require-boundary-schemas`, `require-namespaced-keys`),
  so the strictest available configuration is the one that reported clean. The
  friction that filed the task (an unrelated rename refused over `open!`'s
  years-old missing `:throws`) had already been fixed.
- **The same question asked the obvious way returns 18 findings, all false.**
  Looping the 14 gate FNS directly over every form — which is what anyone
  reaches for, and what I reached for first — reports 15 `tier-refusal` and 3
  `web-endpoint-schema`, every one of them in a `-test` namespace.
  `slopp.edit.gates/gate-check` filters them through per-store severity,
  `:rule/applies-to :production` and the web opt-in; a hand-rolled loop knows
  none of that.

**The finding is the second bullet, not the first.** A debt list nobody needs
is a feature to park; a canonical answer that is not exposed, next to a wrong
re-derivation that is one `for` loop away, is Core 2's *what would make the
wrong route unavailable* — the same shape `relocation-debt` was deliberately
built to avoid by reading `store-violations` instead of simulating it.

Recorded rather than built: zero instances here, and the user-facing case (a
project enabling an opt-in gate over code that predates it — the thing the
gate's own docstring calls "OFF by default so nothing retro-breaks") cannot be
measured from a store where both are already on and clean.

**Method note, and it is the reusable half:** the first number came from
re-deriving a rule instead of calling it. The correction cost one query and
changed the answer from "18 forms of standing debt" to "zero, and your
measurement is the bug." Whenever a check exists, measure THROUGH it — a
re-derivation that disagrees with the canonical route is not a measurement of
the store, it is a measurement of the re-derivation.

## 2026-08-25 — the read tier gets a meter, and its first reading is "unmeasured"

`call-timing` had recorded a call's edges and whether it was refused since the
turn-timing work — which answers what slopp SPENT and never what it SENT. The
standing claim on that tier (P8: reads are 52% of the token bill and got the
least optimization) had no per-tool number behind it, and the one lever that
did ship there — the 8000-char response gate in `slopp.mcp/text!` — had been
measured exactly once, by hand, off a single transcript.

- **That one measurement is why the re-fetch is the number, not the size.**
  eval9: an 8,367-char trimmed history read plus a 21,676-char `query_detail`,
  where sending the payload whole would have cost 21,676. The trim spent 30k
  to save nothing. From outside, that case and the case where the agent never
  came back looked identical — the system recorded neither the trim nor the
  retrieval, so it could not tell a gate that paid from one paid twice.
- **The wire is the only layer that sees both facts, and neither is in a
  return value.** `text!` knows a payload was cut, `told!` knows one was
  withheld as an `:unchanged` stub, the `query_detail` branch knows which id
  it went back for — all several frames below `handle!`, which is the only
  layer that records a call at all. A dynamic per-call recorder was the
  smallest channel; the alternative is a second return value threaded through
  every tool branch that nothing else reads.
- **First reading, taken the moment the fold existed: 321 turn-end deltas,
  276 carrying timing, 0 carrying a read record.** That is the answer the
  `:unmeasured` column exists to be able to give. A ledger that quietly folded
  only the rows carrying the new key would have reported a confident small
  number over whatever accumulated first — the same correction
  `slopp.lab.verdicts/reuse-rate` needed for `:without-closure`, hit again on
  the next instrument built, which suggests it is the default failure of
  adding a field to a record that already has history.

**The reusable half is the direction rule.** Both instruments now built here
are read once, by a human, to authorize spending effort — a verdict cache, a
budget-aware trim gate. Both can lie in two directions, and only one of them
gets checked: the direction that argues FOR building agrees with wanting the
feature. So the tests are written against that direction specifically — a
`nil` rate rather than `0.0` when nothing was withheld, a re-fetch charged to
the tool that withheld rather than the tool that fetched, unmeasured rows kept
in the population. None of those is an arithmetic check.

The gate this was built to open is NOT open. `the-trim-gate-is-a-constant-not-
a-budget.md` stays gated: the instrument moved the question from unanswerable
to unanswered, and a `nil` re-fetch rate is not permission.

## 2026-08-26 — a four-step retirement, run and measured end to end

The first complete run of `ship the seam WORKING, announce it, then refuse`
with a real consumer on the other side, reported by slopp-ui after restarting
onto the retirement jar:

```
seam shipped WORKING     jar N — /api/rest/paths live, /api/contracts still serving
announced                the shapes plus a byte diff of the rows
migrated and VERIFIED    producer and consumer both green, and reported so
retired                  a no-op: /api/contracts 404, nothing here noticed
```

**The middle step is what paid, and it paid in a way reading could not have.**
`generate_client` refused the new document — it reads `:slopp/contract-version`
as a literal — and it was a consumer NEITHER side had listed, because it never
names the URL: it is handed one. On the two-step plan that would have surfaced
after the old address was gone, with no supported way to rebuild a generated
client. It surfaced in the one release where fixing it was cheap.

Worth separating from the story: the plan as first written WAS two steps, and
it was the consumer who pointed out that the three-step rule was already
recorded here and being walked past. A discipline in `.context/` is not a
mechanism, and this is the second time in a month that the thing which enforced
one was somebody downstream reading it back.

**What the consumer could NOT do, and it is the open item.** Their closing
measurement was `/api/webapp/routes` answering 404 on their process, correctly
diagnosed as jar skew — but only because they knew their own revision (d36828)
and the endpoint's (d36877). From outside a process, "on an older jar" and
"its route table is stale" are still indistinguishable, which is the constraint
they attached to `a-served-route-table-outlives-the-store-it-came-from.md`: a
listener that named the store revision its table came from would end the
ambiguity for whoever is standing outside it.

## 2026-08-27 — a fixture page reached a consumer's published surface

`/api/webapp/paths` on slopp's own store published
`slopp.cljnx-test/fixture-things-page` — a test fixture written that morning to
prove `driver-for` finds a marked page. Found by a consumer fetching the
document, not by any check here.

`rules.webapp/page-routes` iterated every namespace. `api.reads/app-namespaces`
states the rule for every other publisher and names the reason in its own
docstring — *TEST namespaces are excluded there, which is right here too: a
fixture endpoint is not surface* — and the page traversal was written without
it. Both `page-routes` and `page-calls` fixed; `cljnx/marked-pages`, the
image-side reader of the same marker, got the same exclusion.

**The published document was the mild half.** `page-routes` is what
`build/webapp-launcher-source` reads to generate a browser app's route table,
so a fixture page would have shipped an address into a consuming application,
rendering a test's hiccup at a url nobody wrote. Nothing would have reported it:
the address routes, the page renders.

The same measurement found the listener staleness recorded in
`.context/design-disciplines.md` — one consumer fetch, two defects, neither
reachable from inside this repo's own checks.


## 2026-08-27 — two live sessions on one store merged identities and THREADS

Found while two Claude sessions worked this store at once — the first time
that has been done deliberately here. `session_brief` told the second session
that eleven of the first's un-landed changes were "private to this thread…
nothing outside this session can see them until it does". They were not its
changes. Seven code writes to `cljnx`, `webdev.cljs` and `mcp.tools` were
recorded under the OTHER session's id, bracketed by turns carrying that
session's verbatim asks — so `report` would attribute a fixture-page fix to a
performance investigation.

**The mailbox was per-STORE.** `plugins/slopp/hooks/prompt-hook.py` wrote
`.slopp/pending-intent` at a fixed path; `mcp/absorb-pending-intent!` consumed
whatever it found on the next tool call. Two sessions, one slot, first server
to call a tool wins.

**The severity is not provenance.** Absorbing also calls `engine/adopt-line!`,
so the session that consumed a stranger's intent did not merely mis-stamp a
delta — it moved onto that agent's THREAD and resynced its store and image
from that line. `absorb-pending-intent!`'s own docstring promised the opposite
in as many words: *every delta of one Claude session shares a key and
concurrent sessions never merge episodes*. The one invariant threads exist to
hold was stated, documented, and unguarded.

Fixed both halves: a session that has claimed an id leaves a foreign intent on
disk, and the hook writes `pending-intent.<sid>` alongside the legacy path so
an unread ask cannot be overwritten by the next session's prompt either.
Pinned by `mcp-test/an-intent-from-another-session-is-left-for-its-owner` and
`a-claimed-session-reads-its-own-mailbox-not-the-shared-one`.

**What remains, honestly:** a session with no id yet must read the unscoped
file, so two sessions starting at the same moment can still swap identities.
That is strictly better than merging — each stays self-consistent afterward —
but it is not nothing, and it is filed rather than papered over.

**The general lesson is the routing one.** Every mechanism here was designed
for concurrency — lock-free CAS, WAL, threads keyed by `(agent, branch)`,
async startup so a second session need not race the first. The single
non-concurrent component was a hook writing a file with a constant name, and
it was enough to defeat all of it. Concurrency held everywhere it was
designed; it failed at the one seam nobody modelled as shared state.

## 2026-08-27 — Move A shipped a white page, and a green suite could not see it

The first store to migrate to the page-function model got a blank hub: document
served, bundle loaded, `wiring` throwing `:webapp/routes is a declared TABLE,
not a function` at startup.

Cause: the store OWNED its browser mount (for a `:boot` that read `data-base`
off the DOM), so `build!` skipped generating the entry — and the generated
entry is what supplies the route table from the pages' `:webapp/path` markers.
The skip notice said *no browser entry generated* and did not say *and the
table went with it*.

**The message it threw was about the wrong thing.** `:webapp/routes` was
ABSENT, and the guard it reached said "a table of rows", which reads as a type
error. A real app spent that message looking for a type it had not passed.

**Their whole suite was green**, because `cljnx/driver-for` fills the table by
scanning loaded vars. Recorded as a discipline in
`.context/design-disciplines.md`: one reader silently repairing what another
requires.

They fixed it by deleting the hand-written mount — the DOM read was redundant,
since the value was a route capture on every address the app answers — which
removed their last `:cljs` namespace entirely. The capability's stated goal
arrived by way of its worst failure.


## 2026-08-27 — a schema migration and its readers ship by different mechanisms

The thread-lease change (`d44442`) added two columns to `lines` and code that
reads them, in ONE `done`. Within minutes the other agent working this store
reported `no such column: owner_pid` taking their tools down.

**Landing them together was not the fix, and could not have been.** The
`ALTER TABLE` lives in `store.db/open!`, and nothing makes an already-open
session re-run `open!`. Meanwhile the `--live` host hot-reloads store code into
every running process within seconds. So the reader arrives everywhere at once
and the migration arrives nowhere — the gap is not about landing order, it is
about two deploy mechanisms with different reach.

It cleared only because a one-shot `slopp --call` was run in response to the
alarm: that opens a fresh session, which runs `open!`, which ran the ALTER.
The other agent concluded from a stale `query_search` that the code had been
"transient and superseded" — it had not; it was landed, and left alone the
outage would have persisted. **A resolution nobody performed is worth
distrusting: something fixed it, and knowing what is the difference between a
closed hole and a recurring one.**

The rule, which generalises past this incident:

> On a self-hosting store, a schema migration and its readers are not deployed
> by the same mechanism. Code hot-reloads into every live process; DDL runs
> only at `open!`. A reader must TOLERATE its column being absent — it cannot
> assume the migration shipped alongside it has run.

Fixed at `d46470` by making it structural rather than procedural:
`adopt-thread!` selects `*` and reads the lease off the normalized row instead
of naming the columns in SQL, so a store whose `open!` has not run reads nil —
unheld — which is exactly how adoption behaved before the lease existed. The
owner stamp is wrapped for the same reason: failing to record a lease must not
fail the adoption.

**The wider pattern this is the third instance of today.** Shared per-store
mailbox, file-global id counter with per-line writers, and now a migration
whose reach differs from its readers'. Each is one obligation with more than
one mechanism behind it, and each was invisible until a second agent existed.

## 2026-08-27 — why a session kept getting wedged: five whys to one root cause

Two agents on one store wedged four times in a day. Each looked different and
all four had one cause.

1. **Why did the write fail?** `commit-appended!` exhausted twelve retries.
2. **Why did every retry fail?** The id it minted was already in `deltas.id`.
3. **Why was it minting taken ids?** It drew from the shared `meta.next-id`
   floor. `refresh-cache!` lifted it to the floor, minted, collided, refreshed
   to the SAME floor, minted the SAME id. Livelock, not contention — retrying
   could not make progress because nothing distinguished the two writers.
4. **Why was it on the shared floor rather than a reserved block?**
   `reserve-id-block!` runs at `open!`. That server opened at 02:17; the
   allocator landed at 11:12. **It never reserved one.** Same for the other
   agent's server, running since 22 August. The allocator was landed and
   almost nobody was using it.
5. **Why does a session run new code against old initialization?**

> **Code is PUSH** — hot-reloaded into every live process within seconds.
> **Session state is PULL-AT-OPEN** — established once, and nothing re-runs it.

**Root cause: every change that adds per-session state creates a population of
live sessions running the new code against the old state, and nothing detects
or repairs it.** Three of the day's four incidents are that sentence:

| symptom | new code arrived | state that never did |
|---|---|---|
| wedged on append | block allocator | no reserved block |
| `no such column: owner_pid` | lease reader | the `ALTER` never ran |
| work stranded on a thread | lease divert | `:pinned-agent?` semantics |

**The fourth was different and worth separating**: the thread lease abandoned
a line holding un-landed work, because it read "not my pid" as "not mine" and
the Stop hook legitimately runs `done` from a one-shot process under the
session's own agent id. That one was a wrong model, not a deploy gap.

**What was done about it.** Not more initialization. `ensure-id-block!`
acquired the block lazily at the point of USE — immune to the root cause,
because a session that never reserved and one that ran out are the same case —
and then the counter was deleted outright (`D-id-allocation`), which removes
the state rather than migrating it.

**The rule worth keeping, which generalises past ids:**

> A value or step with a FILE-GLOBAL obligation must have exactly one owner,
> and that owner must be the file — never a session, and never the moment a
> session happened to start.

**And one about error messages.** "commit contention on append" named a race
you can win. The failure was deterministic. That single word sent two agents
chasing head-mismatches for about an hour each, on separate occasions, in
opposite directions. An error that names the wrong mechanism costs more than a
slow one, and the fix was to distinguish *the head moved* from *the id was
taken* from *this session predates a change it needs*.

**Why none of this was reachable before.** Every mechanism DESIGNED for
concurrency held — lock-free CAS, WAL, per-line rebase, threads keyed by
`(agent, branch)`, async startup. What broke was a mailbox, a counter, an
initialization step: things that assumed a single sequential session and had
never met a second one.

## 2026-08-27 — a gate measured on the wrong population, and why it inverted

`ideas/observation/verdict-cache.md` was filed with a stated bar: build the
content-keyed verdict cache when reuse exceeds **40% at done-grain**. On
2026-08-27 `slopp.lab.verdicts/reuse-rate` read **46.3%** and the gate looked
met. It was not: the number was computed over 217 closure-carrying
observations that were almost entirely WHOLE-TIER `full_check` runs, because
`ops.external/external-test-run!` recorded an empty `:scope` on the narrowed
`:only` path — so done's own runs, the population the gate names, contributed
nothing to the fraction that was supposed to authorize the build.

Fixed at `d6f6686853cb6` (a narrowed run now records the scope it covered,
pinned by `slopp.verification-test/a-NARROWED-run-records-the-scope-it-
actually-covered`). Split properly, the two populations disagree completely:

```
whole-tier   19,945 ns-runs   45.8% already-green
done-grain        91 ns-runs    1.1% already-green
```

**The done-grain number is ~0 by construction, not by sample size.** The cache
key and the impact selector are computed from the same require-closure —
`closure-hashes` says so in its own docstring, as a soundness property. A
namespace is selected by `done` because the change is in its closure; its hash
digests that closure; so a selected namespace is a changed-hash namespace. The
two can only come apart when content returns to a prior hash, which is what the
one hit in 91 is.

**The general lesson, which is a repeat.** A gate is a claim about a
POPULATION, and a fraction cannot report that its denominator is the wrong
one — it reports a number either way, and a plausible number reads as an
answer. This is the same shape as the earlier entry about "commit contention
on append": the measurement was honest and the thing it was measuring was not
what anyone believed. Both times the tell was available and unread — here, the
451-of-668 `:without-closure` count that the instrument deliberately reports
rather than hides, which was carried forward as a caveat instead of being
treated as the finding.

Two corollaries worth keeping:

- **A gate should name its denominator, not just its threshold.** ">40%" was
  unfalsifiable in practice because nothing checked that the runs being counted
  were the runs being gated.
- **The reuse is real, but it lives where the design forbade looking.** 9,143
  of 19,945 namespace-runs inside `full_check` re-verified already-green
  content. Whether the oracle may use that is an ORACLE decision, recorded as
  the open question on the idea file — not an optimization to slip in.

## 2026-08-27 — the refusal meter counted green test runs, and it named the wrong remedy

`query_cost` reported 1,465 refusals over 14,654 calls (9%), with **`test_run`
the most-refused tool by a wide margin at 461**. That number reached a
performance plan as *"`test_run` being #1 is a guidance bug, not an agent bug —
agents reach for a manual test ritual `AGENTS.md` says isn't needed; the refusal
message should name the tool to use instead."*

There is no such habit. The meter was reading its own prefix.

`slopp.mcp/refusal-text` is the single derivation of both refusal facts, and it
tested `(str/starts-with? t "{:error")`. Every external test-run result opens
its map with `:errors`:

```
{:errors 0, :exit 0, :external true, :status :green, :ran 2}
 ^^^^^^^ starts with "{:error"
```

So **every external test run was recorded as a refused call — green ones
included.** Re-classifying the 1,121 recorded samples: 327 false positives,
29% of all sampled refusals, every one `test_run`.

Corrected picture: the real refusal rate is about **7%**, not 9%, and the
ranking changes completely. `test_run` drops out (its genuine refusals are a
handful of mistyped arguments). The actual top is `edit_subform` (299),
`edit_add_form` (263), `query_eval` (112) — **edit-tool match failures**, which
`:source-now` already exists to answer and which want match precision, not a
message naming a different tool. The planned remedy would have been work on a
problem nobody had.

Fixed at `daeb8dd105c73`: the predicate matches `"{:error "` **with the space**
`pr-str` always emits after the key. Pinned by two cases in
`mcp-test/refusal-text-is-the-one-derivation-of-both-refusal-facts` — a green
run and a red run, neither of which is a refusal. Note the fix is
forward-only: turns already recorded carry the wrong counts, so a window
spanning today still reads high.

**The pattern, and it is the third instance today.** Each time a measurement
was correct about its NUMBER and wrong about the THING, and each time the error
pointed work at something that was not broken:

- the verdict cache's 46.3% gate — measured on whole-tier runs, while the
  population it gated (done-grain) was structurally incapable of hitting;
- the shard spread's "already at its floor" — the right conclusion for the
  wrong reason, which made the reason falsifiable and the conclusion look
  falsified;
- this one — a prefix test matching a longer key.

The common shape: **a plausible number reads as an answer, and nothing in a
metric announces its own denominator or its own predicate.** The defence that
worked all three times was the same — look at the raw rows the number was
computed from before acting on it. That cost minutes each time and would have
cost days of misdirected work.

## 2026-08-30 — eval10, the half-the-time wave (lifetime terrain, opus/sonnet medium)

Matched cells against plain files (`projects/eval10-matched/RUNS.md`, the
same five asks; plain baseline opus 52 turns / 0.94M tokens, sonnet 85 /
2.5M). slopp round 1: opus 159 turns / 11.5M, sonnet 151 / 11.0M. After
each step, one grid:

- s1 (skill → one page, terse bookkeeping, self-opening turn): tokens ~0.5×,
  turns flat — context halved, nothing changed what a turn does.
- s2 (+ `edit_group`, auto-declared edges): opus 158 / 5.1M, sonnet 122 /
  3.4M; single-form writes 43 → 22; a green `full_check` re-fetched whole
  through `query_detail` (76k chars) → terse full_check.
- s3 (+ fourteen families): sonnet 104 / 3.8M; `ToolSearch` still 11 —
  Claude Code defers MCP tools whenever tool search is on (the default);
  `alwaysLoad` in `.mcp.json` is the exemption. Opus aborted on a harness
  "tool call could not be parsed" (unlogged payload; a flake — passed in s4).
- s4 (+ alwaysLoad, form ledger, search cards, orient with source): opus 140
  / 4.8M, sonnet 121 / 3.7M; zero `ToolSearch`; `module_dep` still 9× → the
  retry ran once per write and skipped `ns_create` (fixed for s5).
- Census reads that named each mechanism: the handoff ask (p5) drove five
  per-namespace histories after `report` → `:by-ask`; opus's
  outline-then-targets on ten-form namespaces → small namespace is one read;
  the read after one's own write → the ledger.

Wall is not comparable in any cell: my own external test JVMs ran beside
every grid. Per-call floor measured: refs ~0.5 s once per new store value,
cached after; slices 1.7 s (card assembly), writes 3–4 s (verification).

- s7/s7b (THE MOONSHOT: ask-arrives-with-its-map bundle, query_batch,
  accept+finisher, 2-4-call skill loop): opus 108 / 4.31M / $5.06, sonnet
  114 / 4.18M / $1.87, both 11/11. Tier-1 bundle injected in all 10 steps.
  Turns -16%/-7%; tokens flat/up — the bundle's ~1.7k-token per-request
  rent outweighs the reads it replaced while requests stay 100+. Uptake
  split by model: opus edit_group x11 but accept x0; sonnet accept x2 but
  edit_group x3 vs 37 single-form writes. 0.5x of plain not reached; the
  measured floor is write grain + ritual calls + the ~42% thinking-turn
  harness constant. Ops lesson: editing run10.sh while a cell's bash was
  mid-loop clobbered its RUNS row writer — bash reads scripts
  incrementally; never edit a script a running job is executing.

- s8 (one write door + :test-src + bundle diet, milestone d7f99567287a1):
  opus 91 / 3.32M / $4.91, sonnet 81 / 2.98M / $1.56, both 11/11 — sonnet
  is the FIRST cell under plain on turns (81 vs 85). The surface change
  did what prose could not: zero single-form writes in either cell.
  Bundle rent halved (1-3.3k chars/step). Opus residue is ritual
  (query_history x10, full_check x4, test_run x4); sonnet residue is
  reads (query_source x16). accept rarely fires now that :test-src hands
  the failing test over — the finisher is for deliberate shifts, not a
  routine path. The opus step-2 harness flake recurred; run10's one-retry
  absorbed it.

- Consumer-side confirmation of the s8 finding (slopp-ui, 2026-08-31
  00:50): in their store, every prose-only rule in a ~500-line AGENTS.md
  eventually failed (once with the correct warning DIRECTLY ABOVE the
  wrong affordance, read and lost — 28 calls to undo) while every
  test/gate-enforced rule held. Their phrasing is the durable version:
  "an argument competes with an affordance and loses, even when the
  argument is right there and the reader has read it." Design consequence
  already applied in s8 (surface over prose); the frame generalizes to
  every future guidance decision. They will exercise `resend` deliberately
  post-restart — the grid cannot reach it by construction (no compaction
  inside a 15-turn step), so their session is its first real validation.

- s9 (standing-verdict hook line, report :story, edit_group example):
  sonnet 71 / 2.32M / $1.35 (0.84x plain turns, ~token parity); opus s9c
  98 / 3.11M / $4.23 (cost/tokens down vs s8, turns within variance).
  Ritual shrank (full_check 4->3, test_run 4->2). :story fired only after
  two field-failure fixes — line-scoped changes missed imported/cloned
  history, and a <=3-match gate withheld on topic-shaped contains ("fuel")
  — the general lesson: a narrow-answer feature must resolve against the
  STORE (names), not the session's recent deltas, and rank-and-cap rather
  than withhold. Infra: Claude Code caches an MCP connect failure ~15 min
  across -p sessions in one cwd; one slow first-boot import de-tooled
  three consecutive eval steps (the cell still went 11/11 via slopp --call
  and file edits — the CLI door is a real resilience layer).

- eval11-scale (150-ns terrain, all cells 11/11): the slopp:plain ratio
  IMPROVES with size on turns and tokens for both models (slopp grew
  +11-14% turns vs plain's +19%; +28-32% tokens vs +33-45%), sonnet cost
  crossed UNDER plain (0.82x), but WALL is the weak axis (slopp-opus
  2.2x plain — the verification bill). Bundle precision on fresh-import
  stores: implementation forms rank, their TESTS don't — no coverage
  edges exist until tests run; seeding static test->subject edges at
  import is the fix. Prewarm import: 8-12s at 150 ns, sub-linear.
  Caveat recorded: templated padding is kind to grep+sed, so plain's
  measured growth is a lower bound on real-world plain.

- CORRECTION to the eval11 entry above (same day, after the wall
  decomposition): the wall gap is NOT the verification bill. Tools took
  217s of slopp-opus's 1002s wall (edit_group ~3s/write); per-turn model
  time matches plain (~7s vs ~6.4s). The gap is request count x model
  time, inflated by OUTPUT volume: slopp-opus emitted 67.5k output
  tokens vs plain-opus 32.5k (2.1x) — the whole-form retype tax
  (:replace retypes a form to change two lines). Sonnet, at output
  parity (33.8k vs 34.9k), has a wall ratio of just 1.28x. Wall and
  token levers are therefore the SAME: fewer requests + delta-grained
  emission. Async verification would buy at most ~15% and is deprioritized.

- s10 grid (intent/:patch/seed-tests, both terrains, all 11/11): intent
  used 5x and :patch 0x across four cells — wire-correct and unadopted;
  net effect noise-to-slight-regression (sonnet-41 71->96 turns is the
  outlier, attribution unresolved at n=1). Third confirmation that
  advertising an op beside a familiar sibling does not change behavior;
  only surface forcing has (s8). Output tokens unchanged (~53k opus) —
  the retype tax stands while patch goes unused. Also: one real network
  outage invalidated two cells (rerun clean), and the opus step-2 parse
  flake survived to 3-attempt depth once; the malformed emission is
  structurally unloggable (it never parses).

- s10 sonnet-41 regression confirmed by clean rerun (90 vs 96 vs s9's
  71): real, ~+20 turns, step1 doubled (8->16) — prime suspect the
  wave-1 seed-tests-in-bundle change (appended past budget = added rent
  + changed first-sight), not intent itself (x0 both takes). Lesson: a
  bundle change is an ORIENTATION change and moves every step's opening;
  measure bundle deltas with a single-cell canary before a full wave.

## 2026-08-31 — s11 grid (two-verb surface, 4 cells, all 11/11)

Fourth confirmation of the forcing law, now with its limit measured:
de-advertising moved NAME adoption to 100% immediately (zero legacy
write/read-batch calls in any cell; opus emitted the first :patch steps
ever, x8, plus explore x8-14 and check x1-2 per cell) — and moved the
GRAIN not at all. Turns: sonnet 41ns 117 (s9 71), sonnet XL 112 (xl 81),
opus 41ns 113 (s10 91), opus XL 96 (BEST yet; xl 109, s10 103). Where
the turns went: sonnet writes ~2 steps/change and reads one form per
query_source call (16-28 singles vs 1-2 explores); step5 history asks
cost 10-13 turns on both models (query_history :ns misses x4); opus
output volume rose to 69k (retype tax intact despite patch existing).
Conclusion recorded in ideas/product/half-the-time-half-the-tokens.md:
surface shaping is exhausted as a turns lever; server-side levers
(pre-compute, one-explore-cheapest-read, composed handoff answers) are
next. Action inference (s11 wave A) carried 55-143 actionless steps per
cell — the grid would have been a refusal storm without it.

## 2026-09-01 — s12 grid (anticipation, surface frozen; 4 cells + 4 step5 isolations, all 11/11)

Server-side anticipation is the first lever that moved turns without
touching the surface: require-expansion on whole-ns reads (the only
change s11->s12) improved every cell — sonnet 117->94 / 112->90 (XL
back UNDER plain, $1.57 vs $1.67), opus 113->92 / 96->93 (best opus-XL
ever). Online mechanism evidence matches the offline replay: ~110
attached require-sources across the grid, 1-2 re-asks total. The
handoff-report injection, measured in isolation (s12b step5-only
reruns, injection confirmed fired): no reliable benefit — the eval's
handoff ask explicitly instructs cited record-consultation, which
pre-composition cannot substitute. Law refined: pre-emption fills
unfelt needs; it does not override explicit gather-it-yourself
instructions. Full detail: projects/eval12-anticipation/WAVE-A.md and
the RUNS rows; running narrative in
ideas/product/half-the-time-half-the-tokens.md.

## 2026-09-01 — s12c (citable report, step5 isolations x3 jars)

Report rows now cite their journal ids at every grain, and the result
names them as citations. Sonnet's handoff turns improved monotonically
across the three jars (13->10->9 and 16->15->12) with the XL answer
quoting ids — cited pre-composition satisfies an instructed-archaeology
ask for sonnet. Opus stayed in its variance band; its one outlier
(26t/813s) was self-inflicted CLI verification: `slopp --call test_run`
from a fresh shell boots a JVM, loads the store, and runs the suite
silently for minutes — a real friction a human teammate would hit too,
filed as an s13 candidate (route --call through the live server via
.slopp/ui-port, or emit progress).

## 2026-09-01 — s13 grid (CLI carrier, 4 cells + 1 poisoned attempt)

The transport thesis is dead, measured on its own pre-registered rule:
CLI cells ran +8-30% turns and DOUBLED output tokens in all four cells
(96-120k vs s12's 46-63k) while the schema-rent claim it was built on
HELD (input/request dropped to ~36k) and was swamped. Refined law: an
MCP schema is not only rent, it is the argument TEACHING — without it
the model re-derives call shapes by --help and retypes whole heredocs
on every refusal. One new failure class: a CLI cell shipped a feature
that never landed (no done; work stranded on its thread) — the first
functional failure since s7. Keeps: the routed /api/call fast path
(169ms vs 5-15s JVM boots, token-guarded, heartbeat), SLOPP_CLI as an
opt-in mode, and the harvest of deep fixes the probe surfaced (same-ns
red-first through change, the inferred-step verification-scope hole,
multi-form blobs, the ui-port clobber guard) — all retro-benefiting
the MCP surface.

## 2026-09-01 — s14 grid (schema diet, adopted)

The s13 law's constructive half, measured: relocating the op-index
prose into ten registry-derived bundle cards (~0.7k tokens) while the
advertised surface keeps schemas and enums made every cell faster or
equal — sonnet-41 83t/$1.61 (best since s9), opus-41 76t (all-time
best), opus-XL 87t (best ever); output fell 26-31%. Teaching placement
matters more than teaching volume: cards at the top of the ask beat
prose inside tool descriptors. Adopted as the default surface, pinned
by the fourteen-families test (advertised < 15k chars). The one
regression (sonnet-XL) was an auto-require gap ("No such namespace"
after a scaffold not caught on the group door) that cost an 18-call
require rebuild — filed with two fixes named.

## 2026-09-01 — s15 grid (standing pre-emption, require upgrade, spool remainder)

The sweep: all four cells 11/11, three records, every pre-registered
clause met. Sonnet-41 62t/$1.35 (0.73x plain's turns), sonnet-XL 71t
(churn regression fully reversed — the bare-require upgrade fix),
opus-41 65t/$3.34 (full_check 5->3, test_run 4->0 from the done-carried
:whole-store line; cache-read halved), opus-XL 92t (within band).
Cumulative: sonnet is now decisively faster than plain on both
terrains with verification and provenance included; opus's 41ns gap is
1.25x turns from 2.2x at s11. Levers that did it, in one sentence
each: answer-shaped pre-emption works where instruction never did;
repair paths must accept the inputs models actually produce (bare
requires); and never make a reader re-buy what it already holds.

## 2026-09-01 — s16 concurrency (eval16): the wall divider holds; three land-path bugs found by probes

- Two concurrent `claude -p` sessions on ONE store: both land, suite
  green, zero lost edits (probes + 3 measured pairs, sonnet+opus).
- Pre-registered rule wall(par)<=0.65x wall(seq): opus 0.50x PASS;
  sonnet 0.77x/0.39x (pooled 0.52x PASS). Par wall = max(sessions) and
  was 139s in BOTH sonnet runs while seq (=sum) swung 180-352s:
  parallelism stabilizes wall, not only divides it. Tokens: par
  CHEAPER in 3/3 pairs (0.60-0.90x) — no orientation penalty.
- Wave A probe bugs, all red-first pinned + landed (d865e49f8883f,
  full_check green 1527 external):
  1. terse-done rendered a REFUSED land as a bare green done (agent
     told user "Done — green" with work unlanded). Pin:
     mcp-test/a-refused-land-is-never-rendered-as-a-bare-green-done.
  2. MV-conflict deadlock: the conflict recomputed from the fork point
     on every land retry — "resolve, then call done again" could not
     succeed; only thread_drop escaped (40 turns, $0.82). Fix:
     merge-logs treats a conflict a prior merge SURFACED whose form
     ours rewrote SINCE as resolved (record-merge already persisted
     the fid-keyed conflicts). Pin:
     thread-test/a-conflicted-land-is-resolvable-in-the-thread.
     After: conflict -> one rewrite -> landed (8 calls, 48s).
  3. Namespace births were promptless (ingest! never recorded the ask;
     the merge replay compounded it): :prompt now rides store/ingest,
     ops/ingest!, create-ns!, and merge-logs' :ingest arm. Pin:
     merge-test/an-ingested-namespaces-prompt-survives-birth-and-replay.
- Recorded limitation (not a fix): turn markers deliberately don't
  cross a land (fields/markers), so a rebased session's ask is absent
  from report{}'s :by-ask even though every content delta carries
  prompt+agent. See D-concurrency.

## 2026-09-01 — s17 wave A: refusals were the residue; repair before refuse

- Census (projects/eval17-repair/census17.py, 12 cells s14xl/s15/
  s15xl/s16): refusals 24–38% of tool calls; the largest classes were
  argument SHAPES whose refusal text named the fix, retried (18x in one
  session — the s16 352s sequential outlier was refusal thrash, not
  model variance). Teaching did not move them; accepting does (D-repair).
- Group-compile seam, two bugs under "No such namespace: X" (19 XL
  refusals, both models): the auto-require write was refused by the
  module gate (the require IS the first crossing) and returned the group
  untouched; and the two self-repairs ran in a fixed order where a new
  module needs them alternated per namespace. Diagnosed only by making
  the repair SAY when it could not happen (:auto-require-refused) and
  stamping the loop's exit — five red iterations before the alternation
  landed; the pins were red against the transcript's exact shape each
  time.
- Read-to-edit census: written/read source 0.33–0.63; source read per
  cell 34–57k chars — a modest token lever, not taken (s12's turn risk).
- Landed d83bdb922d9d7 + d370eeab252a7; full_check green (1533
  external). Wave B pre-registered in eval17-repair/RUNS.md.

## 2026-09-02 — s17 wave B verdict: mixed; opus-XL hit, sonnet turns up on the handoff step

- opus-XL 92->67 turns, $5.02->$3.40, wall 887->583s (all pre-registered
  clauses met); opus-41 flat. Refusals 24-38% -> 8%.
- sonnet: cost down everywhere (41ns mean $1.23 vs 1.35; XL $1.40-1.49 vs
  2.02) and the all-time best run (57t/$1.00/338s), but turn MEANS above
  s15 (41ns 65.7 vs 62; XL 79-90 vs 71): every extra turn is step 5 —
  the handoff — 12-22 turns vs s15's 6 and 1, with or without the report
  verb tool (measured out mid-wave, pre-declared). Repairs were not
  re-issued (0 redo after a `;; repaired` line). Open for s18: why the
  handoff step got heavier (does the s12c injection still fire?), the XL
  require staircase, the new-spelling tail.
- Law confirmed a fourth time, from the other side: removing a refusal
  removes the retry, but a REACHABLE verb (report as a tool) invites a
  drill-down the bundle already pre-empts — surface presence moves
  behaviour both ways.

## 2026-09-02 — s18 forensics: three layers under the heavy handoff step

- `change` was never in tools/write-tools (its alias `intent` was), so
  the dispatcher skipped the turn gate/rotation/read-ring flush for
  every `change` since s11: no eval since then recorded a turn for its
  writes; by-ask was structurally empty. Found by a one-ask harness
  repro (write, land, mailbox consumed, zero turn-begins) after the
  in-process served-flow pin passed — the pin wrote through ns_create/
  edit_add_form. Pinned: mcp-test/a-change-opens-the-asks-turn.
- The intent mailbox was unowned: a pinned one-shot (the Stop hook's
  `done`) consumed the next session's ask. Pinned:
  a-pinned-session-never-eats-another-sessions-ask. Explains s16's
  cross-attribution too.
- The injected handoff was a 3800-char pr-str snip that cut before
  :by-ask in every s17 run (report grew 3991->4898 chars after s16's
  prompt carry), with a "(deeper: …)" tail teaching the drill-down.
  Replaced by orient/handoff-text (asks first, whole rows, closing
  "this IS the record").
- Correction to earlier records: s15-XL's "1-turn" step 5 ran ~25 calls
  inside an Agent subagent; num_turns counts none of them.
- Usage analysis (cells vs long sessions; context rent and verification
  wall dominate real use): ideas/product/real-usage-vs-the-benchmark.md.

## 2026-09-02 — s18 verdict: provenance fixed, handoff turns not moved

- by-ask correct on every store built this wave (4/4 asks; probe 2/2)
  — `change` in write-tools + mailbox ownership; the injection present
  in all 17 step-5 sessions (verified per transcript).
- Step-5 isolation: sonnet 10.0 -> 8.2 calls, opus 8.8 -> 9.5; costs
  down 11-20%; zero journal-id citations in all 16 (the "quote the ids"
  instruction moves nothing). Lifetime sonnet-41 n=1: 95 turns — miss.
- The residue, measured: the require staircase on a rename ask (15 of
  28 calls in step 4) and the suite ritual on the handoff (4-6 calls,
  two invited by slopp's own text: a green result's "N of M keys shown"
  marker and the standing note's `force true` sentence — the latter
  fixed).
- Usage analysis (long sessions: context rent + verification wall):
  ideas/product/real-usage-vs-the-benchmark.md.

## 2026-09-02 — s19: the verdict-cache gate was broken, and it says BUILD (44.6%)

- `slopp.lab.verdicts/reuse-rate` folded `(:deltas store)` — empty on a
  durable store (journal in SQLite; `standing-run` reads `:recent`). Its
  recorded 2026-08-08 reading ("not measurable yet") was the instrument
  describing an empty list, and it parked the verdict cache for six weeks.
  Fixed (`:source :journal|:recent-window` + a one-shot `-main`), then
  measured over the whole journal: 1114 observations, 28,639 namespace-runs,
  12,786 already green at exactly that content = **44.6%**, against a
  pre-registered threshold of >40%. Recorded in the idea file.
- Prerequisite landed: `observation-of` now records `:ns-status`
  (per-namespace green/red), so one red namespace no longer spoils the fifty
  that passed beside it. Conservative by design — a failure that cannot be
  attributed clears NOBODY, because a cache hit runs nothing.
- Alias resolution: the store's OWN requires now decide what an alias means
  (unanimous/dominant), then the name match, then well-known. Fixes the 59
  unrepairable "No such namespace" refusals measured on dev sessions
  (`n` → rewrite-clj.node had no candidate at all).
- TWO CORRECTIONS to the 2026-09-02 usage analysis, both from grouping
  refusals by TOOL instead of by MESSAGE: the "test_run ×462" lever is
  frozen rows from a since-fixed `refusal-text` predicate (a green external
  run matched `"{:error"` without the trailing space), and the "×282
  single-form alias hole" was all-kinds, not that message. Group by message;
  window `query_cost :refused` past the predicate fix.

## 2026-09-02 — s19b: the verdict cache, built at done grain (and one hole closed same-day)

- `reusable-verdicts` + `done!` wiring: a test whose namespace closure hash
  matches a prior GREEN observation covering it does not re-run. Whole-ns
  greens clear a namespace; narrowed (`:only`) greens clear exactly the
  tests they named; red or other-content evidence clears nothing. Reuse
  count rides the result; terse done shows it; full_check untouched.
- HOLE CLOSED, in code landed hours earlier the same day: `:ns-status` was
  derived from the namespaces that RAN, so a NARROWED run — done's own
  slice — would have marked its namespaces green and authorized skipping
  tests that never executed. A narrowed run now records no `:ns-status`.
  The lesson is the reason the pin exists: a cache HIT RUNS NOTHING, so
  every rule has to be the conservative one.
- Red-first across a namespace boundary needs the subject to exist: the
  seam landed first as a conservative stub (reuse nothing), the pin was
  watched failing against it, then the rules landed. That sequence is the
  honest form of red-first when the callee is in another namespace.
- Value caveat recorded in the idea file: the 44.6% mixes grains, and exact
  repeats (already free via `standing-run`) and full_check (uncached by
  choice) are not this build's to claim. Every done now reports `:reused`,
  so the done-grain hit rate is measured continuously rather than replayed.
  If it reads ~0 over a week, the build should come out.

## 2026-09-02 — s19c: the oracle's absent capability, and refusals classified by message

- 121 query_eval refusals on this store reached for a session var. There is
  none and there should not be — a session in eval lets a write bypass the
  delta pipeline (T5) — so this is the case D-repair does NOT cover: the
  intent is real, the capability is deliberately absent, and the refusal now
  names the three doors that answer it (query_store, the tools, check).
  Verified live against the exact failing call.
- read.telemetry/refusal-shape + :by-shape/:retried in call-timing, folded
  by turn-cost. A tool is not a class: grouping by tool produced two wrong
  levers in one session. The shape is a mechanical normalization (not a
  taxonomy) so it works on any store; :retried counts a refusal answered by
  the same tool again, whatever that call's outcome.
- Both recorded going FORWARD, like the classifier fix before them.
- Also: a deliberate mention of a non-existent var tripped the
  stale-reference rule, correctly. Fixed by composing the qualified spelling
  rather than writing it — a pin that permanently trips a good rule teaches
  everyone to ignore the rule.

## 2026-09-02 — s19d: tracking items 1 and 2, and the population bugs they exposed

- Context rent (`call-timing :rent`): an answer's size times how long it
  rides. A within-turn lower bound, said so. `query_cost {by "milestone"}`
  gives the series.
- `query-turn-cost` had been dropping the per-call census the wire passed
  (destructured `[since otel]`), so every tool reading came from the
  turn-top lower bound. Connected: reads are ~72% of chars on the wire, and
  query_detail costs ~16k chars a call — the trim-then-re-buy pattern.
- Connecting it introduced a mixed-population rate (turn-derived refusals
  over census calls: 57% where the rate is 9%). Fixed; each half names its
  :basis. Both are the same class of error as the test_run artefact: every
  number true, the ratio meaningless.
- The milestone series independently confirms s18's root cause: every
  segment from s10/s11 on reports zero turns, because turn rotation fires on
  write tools and `change` was not one. The wall numbers in the usage
  analysis describe a window ending at s11; corrected there.

## 2026-09-02 — s19e: the verdict cache measured at its own grain, and removed

- reuse-by-grain over the whole journal: narrowed (done-grain) 228 runs,
  12.1% of tests already green, and 5 runs of 228 (2.2%) fully avoidable —
  the only number that maps to wall time, since the tier's floor is a JVM
  boot. Whole-suite: 44.8%, which is where the 44.6% headline came from and
  the grain that stays uncached by choice.
- Threshold was >40% at done grain. Measured 12%/2%. The cache is OUT,
  one day after landing: reusable-verdicts and its done! wiring deleted,
  the seam carries a comment with the numbers.
- Kept: :ns-status, reuse-rate, reuse-by-grain — so the question is
  re-measurable in one command on any store rather than folklore.
- The lesson is about the MEASUREMENT, not the cache: a fraction that mixes
  grains cannot say no. Split by the grain the mechanism runs at, and it
  said no in one reading.

## 2026-09-02 — s20: round trips are the unit of rent

- The reframing: at p50 485k context one avoidable model request re-reads
  ~485k cached tokens (~60 trimmed 8k payloads). The size gate optimized
  chars while provoking round trips: query_source trims were re-bought 69%
  of the time in a real session, and query_detail already returned only the
  remainder — the chars were never the waste, the round trip was. The
  sonnet-41 eval cell has zero query_detail: a real-usage-only lever.
- The bundle injection measured at ~5% of slopp chars: NOT a rent source.
- Landed: :op and :chars-in on every measurement row (the census had known
  only the family since s11 — every s20 lever had to be ranked from a
  transcript); re-buy rate per op in query_cost; explicit reads sent whole
  to 32k; map trims keep the most keys that fit; a green map says
  :withheld in-band with no invitation (an existing pin held the
  "what was cut is still named" line and was right); a no-match slice of a
  huge form cut under 16k; the second prompt of a session gets the delta
  bundle without a tool call; a one-home fragment match lands as a text
  replace (:repaired); a milestone reports :ms {:done :land :publish}.
- refresh-app! was suspected as commit_point's 20s; it returns nil
  immediately without a managed server. Measured: nothing to take. D2
  (incremental git projection) waits on D1's numbers from real milestones.
- Verdict basis (user's choice): real usage — query_cost {since <s20
  milestone>} after >= 300 calls, rules in projects/eval20-rent/RUNS.md.
- Process: three pins landed in a group whose impl failed and so were
  never run against the old code; the red was recovered by reverting the
  three impl forms, running, and re-applying. The group mechanics make
  "tests land first" true and "watched failing" not — worth a mechanism.
- D1's first reading, milestone de67f1178adc4: `:ms {:done 3017 :land 262
  :publish 189104}`. The publish — `ensure-projected!` re-folding every
  journal before the push — is 98% of the milestone's wall. D2 is the
  target, now as a fact.

## 2026-09-02 — the "milestone" → "commit point" sweep, and what rename_sweep got wrong

- Vocabulary: "milestone" retired for "commit point" (D-vocabulary-commit-point).
  An audit of every swept usage — store and files — found NONE that meant
  `done`; `done` stays the other grain.
- rename_sweep is TOKEN-EXACT: the singular sweep left `milestones` (and
  with it the `:milestones` wire key on `report` and `/api/timeline`),
  `Milestones`, `Milestone` and `MILESTONE` untouched. Four sweeps for one
  word. A concept rename should offer case/plural variants, or at least
  REPORT the variants it saw and did not touch.
- rename_sweep REPORTS `:metadata-lost` (^:export, ^:external) on renamed
  forms that in fact kept their metadata — the renamed external tests still
  ran in the external tier, and `:source-now` showed `^:export` intact. A
  false alarm in the drift report, which cost a restore attempt.
- A sweep turns VERBS into the new noun: "must not milestone" → "must not
  commit-point" (twice), "what it milestones" → "what it commit points".
  Hand-fixed; a sweep cannot know a word is a verb, but the report could
  flag hits followed by a period or "not".
- The docs page `done-and-milestones.md` was renamed with `git mv`; the
  sweep rewrote its link TEXT to "done-and-commit points.md" — a broken
  link had the file not been renamed to match.

## 2026-09-02 — D2: the projection repo persists, and a commit point is 30s instead of 200s

- Root cause was not the replay: `open-repo!` built an `InMemoryRepository`
  and `publish-local!` opens a fresh context per publish, so the pinned-sha
  short-circuit in `ensure-projected!` could never fire — every publish
  re-rendered and re-inserted the tree of EVERY commit point in the journal
  (270+ full-store renders) to mint one commit.
- Fix: the same bare repo, persisted at `.slopp/git-cache` (a rebuildable
  cache — a pin deletes it and gets byte-identical shas back). A scratch
  repo with no dir (clone!'s remote fetch) stays in-memory. 30 MB on this
  store.
- Measured on real commit points: publish 199,105 ms (the one warm-up
  walk) → 26,984 ms. Commit point wall ~200s → ~30s. Rule was ≥40%.
- What the remaining 27s is: the walk still folds every delta from an
  empty store (linear in the journal) before rendering the one new tree.
  The incremental fold — resume from the last projected head per line — is
  the next lever, now with its own number.
- Process: the on-disk cache broke `clone!` on first try (nil dir → a path
  at the filesystem root) — caught by sync-test, fixed by keeping the
  no-dir case in-memory. The in-memory design's rationale was "nothing
  touches disk", not correctness.

## 2026-09-02 — D2b: the head commit is minted from the head state; a commit point is 5.5s

Review of the remaining commit logic, with numbers on this store's main line:
39,862 deltas (5.2s just to load and parse), 603 markers, `load-store`
3.1s, render 0.7s, 0 named branch lines (the 1,097 lines are threads).
- Finding 1 (fixed): the projection folded the whole journal from empty on
  every publish to render ONE new tree, while the head was materialized in
  `elements` and the session held it. `ensure-projected!` is three-way per
  line now — :current (nothing loaded), :head (materialized head + pinned
  parent, no replay), :walk (grafts, adoptions, retroactive targets) — and
  returns `:via`. Measured: publish 26,984 → 2,541 ms (`:via :head`).
- Finding 2 (fixed): the unchanged case loaded the whole ancestry to learn
  there was nothing to do; now two rows and a ref resolve.
- Finding 3 (OPEN, ideas/projection/refs-drift.md): historical trees are
  arranged with today's reference index, so "delete the cache and get the
  same shas back" holds only while refs have not changed; pinned shas hide
  it and the rebuild pin cannot see it.
- Finding 4 (OPEN, ideas/product/thread-lines-and-journal-size.md): 1,097
  thread lines never collected; a 2.1 GB journal that makes parsing it 5s.
- Also fixed: commit-point!'s refusal listed `ms` and `episode-status` as
  things that fired — informational keys, now excluded like `:scope`.
- Sound and kept: marker-first ordering, deterministic commits, the
  Slopp-Commit trailer, the fallback walk, publish trouble beside a green
  commit point.

## 2026-09-03 — the instruments were blind, and the store was 85% dead copies

- TURN BRACKET: `turn-open?` read the store's `:recent` window — the deltas
  since the last COMMIT POINT — so a commit point taken inside an ask
  severed its turn (its docstring called that a harmless extra marker). No
  `:turn-end`, the ask's timing ring discarded at the next begin. One day
  on this store: 7 commit points, 9 asks opened turns, 2 closed. Every
  per-turn metric (wall split, rent, refusal shapes, retries) had been
  blind since threads landed on 2026-08-15 — the s19/s20 instruments were
  built on a broken bracket. Fixed: the session remembers its open turn
  (:open-turn), the window is the fallback for a session with no memory.
- CENSUS WINDOW: `tool-call-measurements` and `otel-measurements` ignored
  `:since`, so `query_cost {since}` sat a windowed header over all-time
  totals. Fixed: rows window by their own timestamp against the delta's :at.
- THREAD VIEWS: 138 open threads each held a full materialized copy of the
  store — 466,128 element rows vs main's 3,539; `elements` was 1.16 GB and
  `form_refs` 477 MB of a 2.1 GB file; the journal (`deltas`) only 225 MB.
  Most owners had died days ago (the s16 probes, eval sessions).
  `compact-store!` now settles open threads whose owner is gone and
  untouched >24h (view dropped, deltas + row kept), then vacuums. Run on
  this store: 2.10 GB → 322 MB, 134 settled, 1.78 GB reclaimed.
- GUARD CELL on the D2b jar (sonnet-41 lifetime): 54 turns, 11/11, $1.53,
  537s — best on record (s15 62, s17 57, s18 95). Refusals 10%: store-wide
  `query_history {}` ×4 is the standing residue.
- The s20 verdict's first honest window (census now windowed): re-buy 0/4
  (from 69%); `edit/change` sends 4x the chars it receives (77.6k in vs
  18.4k out over 23 calls) — the first send-side number the store has
  produced; model p50 context 668k, ~656k cached tokens per request; commit
  point publish 821 ms. Rent, refusal shapes and retries accumulate from
  the bracket fix onward.

## 2026-09-03 — correction (user): a dead owner is not "nobody will finish this"

- The first thread GC settled open threads on owner-process death alone.
  Wrong rule: a returning agent is meant to pick its thread back up
  (thread_list / thread_drop exist for exactly that decision). Of the 134 it
  settled on this store, ~110 carried un-landed content. Deltas and rows are
  intact, but `abandoned` is not resumable today.
- Rule now: compaction auto-settles only a thread with NOTHING TO RESUME —
  zero un-landed content since its fork (the fresh thread a done leaves,
  then orphaned). Every thread with work stays open however dead its owner.
- What would make idle-but-resumable threads cheap WITHOUT settling them:
  treat the VIEW as a cache for open threads too — drop it when idle,
  rematerialize on adopt by replaying the thread's un-landed deltas over its
  parent's view. That path does not exist yet; filed in ideas/ (it would also
  be the resurrect path for the ~110 already settled).

## 2026-09-03 — fork on write

- Root cause of the 138 copies: not un-landed work but the FRESH thread every
  done leaves a session on, copying the branch's view before writing anything.
  A thread now materializes its view on its first write (D-fork-on-write).
- Semantics pinned: a rowless thread reads its branch and re-forks when the
  branch moves; a thread with work stays pinned; a fork reads its parent's
  index and owns one after its first write; a settled thread never resolves
  to its parent. Three store.db pins moved with the decision.
- Process: seven patches were refused as fragments before landing (a `do`
  body, a `let` header); the matcher's span rule made two of the fixes
  one-line matches on complete forms.
- The impacted slice (147 tests) caught a real bug in the first re-fork rule:
  it compared the thread's HEAD to the branch head, so a rowless thread
  carrying only a turn_begin looked "moved" and was re-forked, orphaning its
  open turn — `one-shot-call` (turn_begin in one process, the write in the
  next: the Stop hook's shape) failed. Moved means the thread's BASE is
  behind the branch head; and when a branch really has moved under a
  rowless thread with an open turn, the turn is re-recorded on the
  re-forked line so the ask's bracket survives.
- The verification image reported a never-loaded form in slopp.ops.engine
  after the land; `restart` cleared it before full_check.

## 2026-09-03 — eval21: the wall gap was the harness's permission classifier

- eval21 (matched lifetime, task B, sonnet-5 medium, n=3 interleaved pairs,
  jar at d2c2cb1023de6), medians: WALL MISS 571 vs 348 s (+64%); COST MISS
  $1.42 vs $1.31 (+8%); TURNS PASS 77 vs 95 (−19%); acceptance 11/11 in all
  six cells. Recorded as pre-registered in `projects/eval21-matched/RUNS.md`.
- Attribution, transcript spans joined call-by-call to the cell store's own
  `:ms` rows: every slopp MCP call had a ~1.3–1.9 s floor harness-side
  whatever the op (a `query_source` the server finished in 1 ms took 3.5 s);
  100 / 165 / 225 s per slopp cell, a quarter to a third of each wall.
  Plain's Read 0.01 s, Edit 0.01 s, Bash 0.05 s. Model time was not the gap
  (m1: slopp 308 s vs plain 341 s). Hooks ruled out: the plugin's match Bash
  only, the global workmux PostToolUse hook is 50 ms.
- Raw JSON-RPC over stdio against the same jar and store, JFR attached
  (`bin/mcp-roundtrip.py`): every call 0.00–0.38 s. The cost was between
  Claude Code and the server, not in it.
- Cause: `--permission-mode auto` sends every tool call no rule allows
  through a classifier — a billed model call — and built-in tools skip it
  while MCP tools do not. Controlled experiment, two `store` calls: auto
  3.1 s non-API / $0.19; tool pre-allowed 0.1 s / $0.05 (per call 1.60 s
  and 1.43 s → 0.02 s and 0.01 s); the whole-server rule
  `mcp__plugin_slopp_slopp` works too. So the COST miss carries the same
  artifact, unquantified (modelUsage does not list the classifier).
- With the tax removed the wall median would have been ~346 vs 348 s.
  eval22 (`projects/eval22-allowed/`) is pre-registered to test exactly
  that: eval21's design with the harness allowing the server.
- Process: the ask "maybe add in a profiler to actually see where time is
  spent — it is often not where you expect" was right. Three rounds of
  reasoning about hooks, the server loop and `tools-note!` preceded the
  ten-minute measurement that settled it, and the answer was outside every
  candidate. The driver stays as `bin/mcp-roundtrip.py`; DEV.md says to run
  it first.
- Fixed the same day: the harness allows the server for the slopp cohort;
  the `slopp-setup` skill, README and the install page tell users to. The
  edit adding the rule to this repo's `.claude/settings.json` was refused by
  this session's own classifier and is left to the user.

## 2026-09-03 — eval22: the same design with the server pre-allowed passes every rule

- eval22 = eval21 exactly (same jar, day, task, model, n=3 interleaved
  pairs) with one change: the harness allows `mcp__plugin_slopp_slopp`.
  Medians: WALL PASS 362 vs 401 s (−10%); COST PASS $1.21 vs $1.42 (−15%);
  TURNS PASS 71 vs 97 (−27%); acceptance 11/11 in all six cells. Pairwise,
  slopp's wall beat its concurrently-run plain cell in all three pairs
  (362/430, 363/381, 358/401), cost in two, turns in three.
- The rule held: slopp MCP calls per cell 62/70/56 at median 0.02–0.08 s,
  11–16 s harness-side per cell, against 100–225 s in eval21.
- Plain's median moved too between the runs (348 → 401 s, same day), so
  the −10% is not the claim; the pairwise wins are. This is the first
  matched lifetime run where slopp is ahead on wall AND cost AND turns
  with every cell accepted — the session goal, on the measurement the
  goal was stated against.
- What remains is per-session, not per-call: step 2 is 27–30 slopp turns
  in every cell, level with plain; the other four steps are where slopp's
  turn advantage lives. Record: `projects/eval22-allowed/RUNS.md`.

## 2026-09-03 — eval22 step 2: six detours, and a stale thread served as main

- Turn-by-turn over the three slopp step-2 transcripts (27/29/30 turns):
  the same six detours in every cell. (A) history hunt after
  `query_history` answered `:op :ingest :prompt nil`, 7–8 turns —
  `clone!` never passed a prompt to `ingest!` though its sibling writes
  do, and the versions carried no origin though the store holds
  `git-base-sha`/`git-remote`. (B) one `query_source {ns}` per namespace
  the ask names, 7–9 turns — the bundle arrived (9k chars, 107 ms) but the
  hook sent `prompt[:500]` and the namespace list sat at byte 703;
  `ask-seeds` matched form names only, no stemming, and the bundle inlined
  two sources. (C) `ns_create` then `change {tests}` → "Unable to resolve
  symbol: deftest" → read → `ns_add_require` → retry, 3 turns — and worse,
  the same-ns stubber interned a var NAMED `deftest`, so the next error
  named the test itself. (D) a four-namespace `change {tests impl}` refused
  "group failed to compile: apply-eco-discount" — the stub loop's `or`
  starved the load-error source whenever the graph source (which repeats
  itself) had anything. (E) `query_slice {ns}` and `query_commits
  {contains}` refused, 1–2 turns. (F) done + full_check, 2 turns, left.
  Plain spends its 26–30 the same way on reads and one-file edits.
- All five fixed red-first as separate dones; records: `D-orient-namespaces`,
  D-repair's list, `operation-api.md`'s red-first paragraph, the results
  and setup references. Measurement pre-registered in
  `projects/eval23-step2/RUNS.md`: median step-2 turns ≤ 18.
- Found on the way, and worse than any of the six: after a server
  restart this session's live server ran code 1,238 deltas behind. A
  thread minted 2026-09-01 (before fork on write, so with a full copied
  view) was left open with nothing of its own; the restart re-adopted it
  (the newer threads had landed; the agent id had flipped across a
  resume), and the re-fork rule fired only for ROWLESS threads. Every
  read answered from the copy; `session_brief` said "main, 0 un-landed".
  Fixed: a thread with no un-landed CONTENT follows its branch at
  adoption (`follow-branch-if-idle!`, called from `open!`, `adopt-line!`
  and `refresh-cache!`), `refork-thread!` drops a stale view, and a
  bookkeeping append no longer materializes a rowless line's view (it
  copied the whole store onto the thread at the first marker after a
  done — the 138-copies problem through the side door). Rows are not a
  pin; work is.
- Also on the way: two projection contexts on the on-disk repo met a
  `LOCK_FAILURE` on `refs/heads/main` that three back-to-back retries
  could not clear (`concurrent-projection-converges` red); the ref update
  now backs off. And the group auto-require retry added a require to the
  FIRST namespace in the group rather than the one the error anchored
  to (`slopp.store.db` briefly required the read layer); it asks the
  anchored namespace first now.
- Process: three external pins were landed in the same `change` as their
  impl, so the change could not watch them fail; each was re-run red by
  undoing the impl once (D) or by landing the spec first (C, A, B, E).
  A red `change` reports "nothing landed" while its impl IS on the thread
  — the note means the branch, and it misled once here.

## 2026-09-03 — eval23-step2: 27–30 turns → 19 / 20 / 17, rule missed by one

- Step-2-only cells from one post-step-1 snapshot, new jar (d7d115d4b431d):
  19 / 20 / 17 turns (median 19; eval22 median 29, −34%), $0.34 / 0.40 /
  0.31, 120 / 118 / 90 s, p1+p2 acceptance and a green suite in all three.
  Pre-registered rule was median ≤ 18: MISS by one; no lifetime run.
- What the mechanical fixes removed removed cleanly: zero deftest
  refusals, zero stub-loop refusals, `query_commits {contains}` repaired in
  every cell, history hunt 7–8 → 3–4.
- What is left is model habit meeting a cap: every cell hand-read
  booking, billing and invoice — the three namespaces the whole section
  dropped because the four-namespace cap filled in MENTION order; one cell
  re-read three namespaces it had been sent whole, at full size, because a
  whole-namespace read does not consult the ledger. Levers filed in
  `projects/eval23-step2/RUNS.md` and the real-usage backlog: size-ordered
  selection under a cap of six; `:source-already-sent` for a whole-ns read
  of a bundled namespace; an origin line the model stops at.
- Harness note: accept9's single probe cannot compile at step 2 (it names
  step-3/4 vars) and its mode detection keys on a step-4 name; the
  step-2 half lives in `eval23-step2/accept23.sh`.

## 2026-09-03 — why agents read namespaces, and the loader hole under a qualified ref

- Census over the six slopp step-2 sessions (eval22 + eval23): 41
  whole-namespace reads (median 867 chars), 9 searches, 5 slices, ZERO
  `query_depends` or `orient`. The questions behind the reads were flows
  (the path from `quote-breakdown` to the fuel surcharge; how a carrier
  travels through booking, billing, invoicing) and one style question
  (what a test here looks like). Nobody needed a namespace; it was the only
  shape the agent had a habit for, cheap enough that nothing corrected it.
  The second reason to read one: its ns form, for the aliases.
- Built (D-agent-reads, D-canonical-refs): cards-by-default namespace
  read with `:v` and one example test; `query_flow` (path / reach, bodies on
  the way); versioned `targets` rows; the bundle back to seeds plus cards
  (the morning's whole-namespace section retired the same day); qualified
  references canonicalized on write with requires added; aliases handed
  once; the cold-load gate's third shape.
- Found on the way: a fully-qualified reference to a store namespace the
  form's ns does not require landed GREEN (the live image has every ns
  loaded; kondo's `:unresolved-namespace` is a warning; load order reads
  ns forms alone) and failed on a fresh boot into `:image-load-failures`,
  never refused. Pinned red before the fix (`ops/restart!` after such a
  write reported the failure); this store held zero such references.
- Process: `slopp.edit` is a pure tier — the first canonicalize draft used
  atoms and was refused by the purity gate; `slopp.edit` cannot reach
  `edit.refactor/rewrite-symbols` without a cycle, so the fn lives beside
  the rewriter. Two text patches broke a string literal by carrying raw
  quotes (a JSON `\"` decodes to `"`); the group failed to compile with
  `No such namespace: ns` — the tell for that mistake.

## 2026-09-03 — eval24 canary: cards by default is cards-then-fetch, twice

**Setup.** Three step-2 cells from the eval23 post-step-1 snapshot, the
reads/writes wave's jar, eval23-step2's rules (median turns ≤ 17; whole-ns
reads ≤ 1; one `query_flow`), pre-registered with a revisit clause: if the
median regressed past 19 the cards default would be revisited before the
lifetime run. Records: `projects/eval24-reads/RUNS.md`.

**e24s (jar d131e0cc7da47, `full true` advertised in the hint and the
descriptor): 22 / 14 / 24.** Every namespace read was `{full true}` — the
cards were bypassed, not fetched after. Cell 2's 14 is the best step 2 on
record: the history question answered in one call from the import
`:origin` line, the feature written in one change. Cell 3 lost six turns to
a BUG: the group's alias-repair loop, anchoring on the first namespace in
the group when the compile error had lost its coordinate, added
`[logi.discount :as discount]` to two namespaces that never mention it.
Fixed (anchor on the error's own `:form`), with a second fix beside it: a
change step naming a namespace that does not exist creates it instead of
refusing ("ingest it first" cost cell 1 a turn).

**e24t (jar d771ce3ecd036, escape unadvertised): 24 / 29 / 18.** Eight
card reads and three `targets` fetches in cell 1; zero `query_flow` in any
cell. The eval10 shape exactly: the habit walks every namespace whatever
the answer looks like, and cards make each walk two calls.

**Verdict and change.** MISS on turns twice; the cards default is reverted
to whole-when-small (≤ 6k) with the hint riding along, and the bundle's
whole-namespace section is restored smallest-first under a cap of six.
`:v`, `query_flow`, versioned `targets` and canonical refs stay. The
revisit re-runs as e24u under the same rules before Part 2 (all models).

**Method note.** The external runner's `only` takes qualified names
(`ns/test`); a bare name fails with `Could not resolve var`, naming one of
them, whether or not it exists.

## 2026-09-03 — eval24: whole-when-small passes on sonnet, opus pays the ritual floor, haiku is below the task

**Canary e24u** (jar df7a7c9287e82: `query_source {ns}` whole when ≤ 6k
with the flow hint, cards above; the bundle's whole-namespace section back,
smallest named namespaces first under a cap of six): step 2 in 15 / 11 / 11
turns against eval23's 19 / 20 / 17 and e24t's 24 / 29 / 18, ONE namespace
read per cell against eval23's 6 / 3 / 6, acceptance 3/3, zero `query_flow`.
The bundle carried every namespace the ask named, so the reads collapsed
and the flow read had nothing left to answer.

**Part 2, the matched lifetime (task B, n=3 pairs per model, medium):**

- **sonnet-5: PASS every rule.** WALL 340 vs 342 s (−1%), COST $1.04 vs
  $1.29 (−19%), TURNS 56 vs 89 (−37%), 6/6 cells 11/11. Better than eval22
  this morning on every axis (slopp 71 / $1.21 / 362 s → 56 / $1.04 /
  340 s). Wall is the rule slopp barely holds: a third fewer turns bought
  one percent of wall, so a slopp turn is still slower than a plain one.
- **opus-5: MISS on wall (+24%), cost (+32%), turns (+22%); 6/6 accept.**
  Plain opus finishes the lifetime in 54–60 turns; slopp opus in 61–71.
  Per step (plain → slopp medians) 12 → 12, 19 → 12, 9 → 18, 7 → 8,
  9 → 17. Census of a slopp cell's last step: 21 calls — `report` ×5,
  `query_changes` ×3, `explore` ×3, `full_check` ×2, `done` ×2,
  `test_run` ×2. Opus follows every ritual the skill names, every step,
  and the floor that adds is above its plain habit. The reads change
  holds for opus (step 2: 19 → 12); the next lever for opus is the
  ritual floor — how many verification calls a step is told to make.
- **haiku-4.5: no verdict.** 1/11 in every plain cell and two of three
  slopp cells (the rename step never happened; the tree would not load
  afterwards); one slopp cell reached 9/11. The task is above the model
  in both cohorts.

**Method notes.** `accept23.sh`/`accept9.sh` take a cell PATH; handed a
cell name they `cd` nowhere and score the project directory (every check
fails with a nil connection). `compare21.py`'s ACCEPT rule wants the word
`PASS` in the accept column. `query_cost {since <turn delta>}` is the
per-step census; `report`'s `:by-ask` gives the turn ids.

## 2026-09-03 — eval25: the opus wave lands turns and wall; cost is prefix rent

**What was measured first.** The eval24 opus cells' transcripts
(`~/.claude-metabase.com/projects/-private-tmp-slopp-eval18-B-slopp-claude-opus-5-medium-e24o*/*.jsonl`),
turn by turn, with the model's own text between calls. Four sinks, in its
words: a green `full_check` that "withheld part of its result" and "neither
call reported test counts" (four calls per verdict, every cell); a report
that "truncated" the ask texts (eight history calls, two argument refusals
on the way); the sweep that walked past the README (five turns of grep,
file_get, cat, sed, file_put in all three cells); and done advisories
repeated with their teaching every step, which opus obeys at a change and a
second done each. Fixes: D-answers-carry-their-counts.

**eval25 (jar df81b38f9f4f8, three pairs per model, records in
`projects/eval25-opus/RUNS.md`).** opus-5: TURNS 49 vs 59 (−17%; eval24
+22%), WALL 417 vs 449 s (−7%; eval24 +24%), ACCEPT 6/6, COST $2.92 vs
$2.54 (+15%; eval24 +32%) — MISS on cost. Per step 12 / 10 / 14 / 5 / 9
(eval24 12 / 12 / 18 / 8 / 17). sonnet-5: TURNS 52 vs 99 (−47%), COST $1.14
vs $1.27 (−10%), ACCEPT 6/6, WALL 375 vs 330 s (+14%) — MISS on wall, and
$1.14 misses the pre-registered $1.04 no-regression bar while 52 turns
holds the 56.

**The opus cost is rent.** Usage fields at the first turn of every step:
19,365 cached prefix tokens under slopp, 10,005 under plain — +9.4k on
every turn of 49. A one-shot probe (empty dir, plugin on/off) shows +6.9k.
Neither cohort carries a CLAUDE.md. The plugin's fixed prefix — how Claude
Code renders the fourteen family schemas, the skill listings — is the next
lever, and turns can no longer pay for it: opus is already 17% under plain.

**Residue seen on the new jar.** Two cells grepped the WORKING TREE after
the sweep rewrote the store's README and sed'd the human branch's copy; the
sweep's note now says which copy it rewrote (landed after the run). Opus
still calls `full_check` once a step (3–5 a cell) and sends a write through
`explore` once a cell. Sonnet wrote more output per turn (26–35k against
25–30k) and its per-turn wall rose from 6.1 to 7.2 s; unmeasured which
answer grew.

**Method.** `accept24.sh` scores by cell PATH; the transcript ledger script
(python over the jsonl, per assistant message) is the census tool now — the
store's `query_cost` counts calls, the transcript counts TURNS and shows
what the model said between them, which is what named every sink here.

## 2026-09-04 — eval26: the one-call unit; opus passes every rule; a stranded thread

**eval26 (jar dc4e2614c8fab; `change {done commit}` closes the unit,
cheap whole-store inside the close, forgiving require ops, a report that
never snips an ask). opus-5: TURNS 43 vs 63 (−32%), WALL 401 vs 452 s
(−11%), COST $2.45 vs $2.57 (−5%), ACCEPT 6/6 — every rule, for the first
time.** Slopp opus across the waves: 67 → 49 → 43. The ≤ 35 target was
missed; what is left is step 1 (11–14 turns, first-touch exploration and
two changes before the close) and step 2 (the records question sends the
model to bash/git and the README beside the report). Closes in one call
4–5 a cell, almost all `done {commit}`; `full_check` after a close fell
from 3–5 to 2; `commit_point` calls to 0; step-5 history reads after the
report from 14 to 0–2.

**sonnet-5: TURNS 33 vs 97 (−66%), COST $0.94 vs $1.31 (−28%), WALL 310
vs 360 s (−14%) — and ACCEPT MISS, e26s1 9/11.** Not a wrong
implementation: the cell's step-2 work never reached the branch. The
closing change was red on a wrong expectation and closed nothing (right);
the fix was a tests-only change carrying `done` and `commit`, which the
tests-only path ignored; the model ran `full_check` (green on its
thread), answered, and the one-shot session exited with sixteen writes on
its thread. The plugin's Stop hook — an async `slopp --call done` in a
separate process — landed nothing: the session it would have reached had
exited. Steps 3–5 started from a branch without the eco work.

**Two fixes, landed after the run.** A tests-only change closes too when it
is green (and says the impl is next when red). The landing floor moves
into the server: `mcp/land-on-exit!` runs the done for a thread that
still holds green content writes when the stdio loop ends, before the
teardown. The Stop hook stays as a second chance for an editor session.
The mechanism that hid this for three evals: every earlier cell called
`done` explicitly; the one-call unit made the close implicit, and an
implicit close needs a floor that is in the process that owns the thread.

**Residual refusal shapes (opus, 3–7 a cell), for the next wave:** a change
step with `action ns_create` or `{ns requires}` and no source (a namespace
creation sent as a step), `ns_create` with both `source` and `requires`
(refused as exclusive), `ns_create` on an existing namespace when the
model wanted its requires, `full_check {run_in_background}`, `query_git
{limit}`; and a bundle that answers a records question ("why is X
computed this way — what do the records say") with the seed's version
story, so step 2 stops going to git.

## 2026-09-04 — eval27: both models pass every rule; no thread stranded

**eval27 (jar d22a663d292de; a tests-only change closes, the server lands
its thread at exit, creation shapes repaired, the records in the bundle).**
opus-5: TURNS 41 vs 59 (−31%), WALL 390 vs 433 s (−10%), COST $2.41 vs
$2.50 (−4%), ACCEPT 6/6. sonnet-5: TURNS 32 vs 96 (−67%), COST $0.83 vs
$1.30 (−36%), WALL 295 vs 343 s (−14%), ACCEPT 6/6. `thread_list` on all
six slopp cell stores: nothing un-landed. Records:
`projects/eval27-floor/RUNS.md`.

**Where opus's remaining turns are.** Steps 4 and 5 sit at the floor
(5 and 3–7). Step 1 is 8–10 (first touch: one or two explores, a
namespace, two changes, the close). Step 2 is 10–12 and still opened with
file_list / file_get / git log for the records question — the bundle's
records section ranked the WHOLE two-topic ask and told the eco-carrier
forms' story instead of the fuel surcharge's; fixed after the run (ranked
on the sentences that ask the question). Step 3 is 11–15: the sweep's two
calls, reads around its string hits, the fee change, and in one cell three
bash calls and a file_get around the README again. Refusals 2–3 a cell
(was 3–7). Each step ends in a one-call close now (4 a cell).

**The trend, four waves.** slopp opus 67 → 49 → 43 → 41 turns against a
plain that ranges 51–66; slopp sonnet 71 → 56 → 52 → 33 → 32 against
88–109. Cost followed turns once the rent stopped growing: opus +32% →
+15% → −5% → −4%, sonnet −19% → −10% → −28% → −36%. What is NOT done: the
prefix rent (the plugin's ~7–9k tokens a turn), which bounds how far cost
can fall below plain for opus, and steps 1–3's remaining reads.

## 2026-09-04 — eval28: opus at 30 turns (−47%), the floor in sight

**eval28 (jar d9ca25e8a659a; the sweep preview rides in explore, string
hits carry their forms and the run its rewrites, the bundle's whole-ns
caps at 8k/14k, the close carries :verify, the records rank on the
question's sentences and add the pre-import git log, four descriptions
cut).** opus-5: TURNS 30 vs 57 (−47%), WALL 339 vs 452 s (−25%), COST
$2.10 vs $2.54 (−17%), ACCEPT 6/6 — every rule and the ≤ 38 target.
sonnet-5: TURNS 32 vs 96 (−67%), COST $0.87 vs $1.34 (−35%), WALL 296 vs
354 s (−16%), ACCEPT 6/6. No un-landed thread on any cell store. Records:
`projects/eval28-cut/RUNS.md`.

**Per step, opus (medians): 5 / 7 / 7 / 7 / 4** — eval24 was 12 / 12 / 18
/ 8 / 17. Step 2 no longer goes to git: the records section names the
imported form, its docstring-only reasoning and the one pre-import commit,
and the model quotes it. Step 3 is the sweep's two calls, a search, the
renamed namespace read once, the fee change and close. What is left:
`help change` once a step in most cells (seven calls over three cells) —
the dieted family description points at the help topic and the op card
names arguments but not the step shape, so each new session reads it;
and the read-then-write habit (one explore, one or two changes) that IS
the floor.

**The prefix rent is not ours.** After cutting the four longest
descriptions (~3k chars) the on/off probe moved from +6.9k to +6.8k
tokens; the harness's per-tool rendering is the rent, and the fourteen
families are already the smallest surface that names every op.

## 2026-09-04 — eval29: opus at 28 turns (−59%), sonnet at 24 (−74%)

**eval29 (jar d121278eab9c4; the change card carries the step shape,
ns_create takes doc, the whole-ns caps at 9k/22k).** opus-5: TURNS 28 vs
68 (−59%), WALL 305 vs 469 s (−35%), COST $1.85 vs $2.71 (−32%), ACCEPT
6/6 — the ≤ 27 target missed by one; e29o1's 22 is the lowest lifetime on
record. sonnet-5: TURNS 24 vs 94 (−74%), COST $0.86 vs $1.38 (−38%), WALL
290 vs 413 s (−30%), ACCEPT 6/6. No un-landed thread anywhere. `help`
calls 0 (was 2–3 a cell), separate `ns_create` calls 0. Records:
`projects/eval29-card/RUNS.md`.

**What the card did.** Seven `help change` turns over three cells came from
a card that named the change's arguments and not the step shape; every
fresh session read the topic. The card now carries the shape, and says a
`{ns requires doc}` step creates the namespace inside the change — which
also retired the separate `ns_create` call in every step that creates one.

**What is left, opus per step 5 / 6 / 8 / 4 / 4.** Step 3 (the rename):
after the sweep the model read the renamed namespace to patch a value in a
form the sweep had just rewritten, and grepped the working tree for the
README twin (the store's copy was rewritten; the disk copy is the human
branch's). Landed after the run: the run's `:rewritten` carries EVERY
rewritten form (cap twelve, 10k chars), and a tracked file's disk twin
that still names the word is rewritten in place (`:files-on-disk`) — the
rename means everything named that, and the model was applying sed to it
by hand. Steps 1, 2, 4, 5 are at the floor: one explore, one or two
changes, the answer.

## 2026-09-04 — eval30: the cuts have reached the noise floor

**eval30 (opus only, jar d9d0eb5d3fe70: the sweep's run carries every
rewritten form and rewrites a tracked file's disk twin).** TURNS 30 vs 57
(−47%), WALL 360 vs 487 s (−26%), COST $2.23 vs $2.55 (−13%), ACCEPT 6/6;
no un-landed thread. The step-3 target (≤ 6) and the lifetime target
(≤ 26) both MISSED: step 3 was 8 / 8 / 13. The rewritten forms rode the
result and the model searched for the fee value anyway; the disk-twin
rewrite showed up in `git diff` in one cell and cost five turns of
investigation. Records: `projects/eval30-step3/RUNS.md`.

**The noise floor.** Slopp opus lifetimes over the last three runs: 30 /
28 / 30 (medians), cells 22–33. Plain opus: 57 / 68 / 57, cells 51–70.
A single cut moves one to two turns a cell; n=3 resolves about three. The
per-step shape is 4–6 turns except step 3 (the rename), whose residue is
the model's own verification habit around a store-wide rewrite: a search
for the value it is about to patch, a read of the namespace it is about
to touch. What would move opus further is not another answer shape: it is
either a different eval (a codebase where reads dominate) or the rent,
which is the harness's.

**Where this leaves the two models against plain (last run each).**
opus-5: turns −47% to −59%, cost −13% to −32%, wall −26% to −35%; sonnet-5:
turns −74%, cost −38%, wall −30%; acceptance 6/6 in every run since the
landing floor moved into the server.

## 2026-09-04 — eval31: slopp on its own codebase (read-heavy), opus-5

**Design.** Three asks on THIS store (247 namespaces, 3,629 forms; 243
files / 8.6 MB as the plain seed, from the projection's main tree with its
test alias): how a `done` lands work (six checkable facts), make
`query_cost {turns N}` work with a test (a probe through the wire), and
the tool-result size gate (six facts). Three interleaved pairs.
Records: `projects/eval31-self/RUNS.md`.

**Result.** slopp 48 turns / $3.72 / 700 s (medians) against plain 107 /
$4.87 / 1,461 s — turns −55%, cost −24%, wall −52%. Read questions: both
cohorts correct in every cell; slopp 14–23 turns against plain 28–40 for
the path question, 6 against 6–9 for the numbers question. The change:
slopp 19–30 turns and 5–9 minutes; plain 60–73 turns and 15–29 minutes,
almost all of it running the whole suite through the CLI.

**Two harness facts worth keeping.** A plain cell's background test run
was terminated at the harness's ceiling and its JSON undercounts that
step's turns; and a slopp cell died on "the model's tool call could not
be parsed (retry also failed)" with its tests landed red — a red episode
correctly left on the thread, scored as a miss. The landing floor was
also found to lose a race on a big store: the harness kills the server as
the session ends, and the exit landing runs an 880-test suite. It runs as
a JVM shutdown hook now as well (`mcp/exit-landing-hook!`), idempotent
with the stdio loop's finally.

**Against the dogfooding record.** The store's own census (352 asks, $2,980,
median context 489k, 39% re-fetch on trimmed reads, `verify` 3.1 h of
wall) is the same shape: reads are the characters, verification is the
wall. What this eval could not test is the long-session rent — three
one-shot sessions never reach a 489k context.

## 2026-09-04 — eval32: a real backlog item on slopp itself, slopp vs plain, opus-5

**The task.** `query_cost {by "model"}` (per-model requests, tokens by
kind, cost, context distribution) and per-ask attribution of the
telemetry requests the store already records — a genuine gap found while
answering "do we know how opus works on this repo". Same seeds as eval31.
Records: `projects/eval32-bymodel/RUNS.md`.

**Result.** slopp 26 / 20 / 31 turns, $2.17 / 1.69 / 2.54, 442 / 353 /
468 s — every cell landed, committed, and passed the in-process probe
with a test present. plain 64 / — / 53 turns, $4.74 / — / 3.13, 1,190 /
1,800 / 1,384 s — one cell hit the harness's 30-minute ceiling with its
work unfinished. Against the two plain cells that finished: turns −52%,
cost −40%, wall −68%.

**As a development-performance comparison this shape works.** A backlog
item that spans four namespaces and needs tests, graded by an in-process
probe both trees can run, separates the cohorts on the things that
matter here: the reads (slopp's targeted reads against grep-and-cat over
243 files) and the verification (per-write and in the close, against the
whole suite through the CLI). Its weakness is the same as eval31's: three
one-shot sessions never reach the long-session rent the dogfooding record
shows (median context 489k).

**The feature itself** exists now in three cell stores (e32o1's commit
db7c68ece2e1b is the cleanest: `telemetry/cost-by-model`,
`telemetry/cost-by-ask`, `query-turn-cost :by`, the descriptor, five
tests). It is not in this store; landing it here is a separate decision.

## 2026-09-04 — where slopp stands against plain Claude Code (the state after evals 21–32)

**The matched lifetime (task B: five features on a 41-namespace store, three
interleaved pairs, medium effort, pre-registered rules).** Last run per model:

| model | turns | cost | wall | acceptance |
|---|---|---|---|---|
| sonnet-5 (eval29) | 24 vs 94 (−74%) | $0.86 vs $1.38 (−38%) | 290 vs 413 s (−30%) | 6/6 |
| opus-5 (eval30) | 30 vs 57 (−47%) | $2.23 vs $2.55 (−13%) | 360 vs 487 s (−26%) | 6/6 |
| opus-5 (eval29) | 28 vs 68 (−59%) | $1.85 vs $2.71 (−32%) | 305 vs 469 s (−35%) | 6/6 |
| haiku-4.5 (eval24) | no verdict: the task is above the model in both cohorts | | | 0/6 both |

Where it came from, opus: eval24 +22% turns / +32% cost / +24% wall against
plain; the waves in between were named turn by turn from the transcripts
(a green verdict that withheld its counts, a report that snipped its asks,
a sweep that missed the README, done advisories obeyed, the three-call
close, the require ops' two vocabularies, the help topic each fresh
session read). Slopp opus lifetimes: 67 → 49 → 43 → 41 → 30 → 28 → 30.
Cell-to-cell variance (22–33) is now larger than what one cut moves.

**On slopp's own codebase (247 namespaces, read-heavy; opus-5).** eval31,
three asks: 48 vs 107 turns (−55%), $3.72 vs $4.87 (−24%), 700 vs 1,461 s
(−52%); both cohorts answered the two whole-codebase questions correctly.
eval32, one real backlog item (`query_cost {by "model"}` and per-ask
attribution): 26 vs 64 turns (against the two plain cells that finished;
one hit the 30-minute ceiling), $2.17 vs $4.74 (−40%), 442 vs 1,384 s
(−68%). The difference on this codebase is reads (targeted against
grep-and-cat over 243 files) and verification (per write and in the
close, against the whole suite through the CLI).

**Where the remaining cost is.** The plugin's fixed prefix: 19.4k cached
tokens at the first turn of every step against plain's 10.0k, measured
from transcripts and by an on/off probe; cutting our own descriptions by
3k chars moved it 150 tokens — the rent is the harness's per-tool
rendering, and it bounds how far opus's cost can fall below plain on a
short task. Long-session rent (this store's median context is 489k) is
what no eval here has measured.

**What acceptance found on the way.** Two stranded threads (a tests-only
close ignored; the async Stop hook losing the race with a one-shot
session's exit; then the harness's SIGTERM beating the stdio finally on a
big store) — the landing floor is in the server now, twice over, and every
cell store since shows nothing un-landed.


## 2026-09-06 — daemon census, lazy images (P5-1 item 4a)

Setup: one `slopp daemon` (`--live` from the slopp2 checkout, jar built the
same evening), sessions attached through the stdio pipe (`SLOPP_DAEMON=1`),
`ps` by parent pid.

- Two sessions attached and reading (`tools/list`, `query_search`,
  `session_brief`): the daemon JVM at ~605 MB RSS, **zero child JVMs**. The
  same two sessions as per-session servers were two ~1.6 GB processes plus
  an image each (the 2026-09-06 morning census: 2.4 GB + 530 MB on slopp2).
- One `query_eval`: exactly one image child boots, 127 MB RSS, for the
  session that asked. The other session stays image-less.
- Detach: the image is PARKED (`ops/close!` → `repl/park!`), so the child
  survives the session and is handed to the next tenant with the same deps.
  That is the process-wide recycling that already existed; under one JVM it
  is finally shared across agents.
- Attach through the pipe: `initialize` answered 4.6–5.1 s after launch,
  which includes the daemon loading slopp's own store under `--live`; from a
  neutral dir on the snapshot jar the daemon binds in 4.6 s too (the kernel's
  jar load dominates). Inside the stdio client's window; outside the HTTP
  client's ~7 s with a cold start, which is one of the two reasons the pipe
  exists.
- Found on the way: a session opened before its dir had a store never got a
  connection and stayed blind to everything landed afterwards. Fixed in
  `sync-with-journal!` (attach when the file appears).

Not yet measured: readers sharing ONE image per (project, branch) — deferred
until a workload shows image-backed reads dominating; today the lazy boot
already removes the idle JVM that was the whole of the 2.6 GB/agent cost.

## 2026-09-07 — the two-session check through the daemon (P5-0/P5-1 stop point)

Two `claude -p` sessions launched together in a scratch project whose own
`.claude/settings.json` set `SLOPP_DAEMON=1` (nothing in slopp2's wiring
changed); each told to `thread_open`, write one namespace on that thread,
and `done` with it.

- Both finished in 31 s; distinct minted threads (`t-6b112d06`,
  `t-9b230b50`); the daemon showed one project, two sessions, two images.
- The journal: each `done` under its own thread; the second to land
  re-ran the suite over BOTH namespaces (its rebase onto the first), the
  first over its own; both threads at `:unlanded 0` afterwards; `report`
  carries both verbatim asks. Zero cross-attribution.
- Read back through the CLI door (`slopp report`, `slopp thread_list`
  from the scratch dir), which opened its own CLI session on the daemon.
- The whole-store `full_check` on slopp itself after all daemon
  increments: green on every tier (896 in-image, 1653 external, 3739 forms
  swept); alias-drift rose 17 → 21 with the daemon's four new requires.

## 2026-09-07 — the daemon on slopp2: per-session cost before and after sharing

Measured on the live daemon serving slopp2 (`--live`), attaching extra
reading sessions through the pipe and reading the daemon's RSS.

- Before: a second reading session cost **+~450 MB** and 3.1 s (its own
  `load-store` of slopp2); detach returned nothing to the OS.
- The reader opened at attach cost a copy too (2.7 GB for one session
  right after the restart); made lazy — opened on the first API request or
  app refresh, and the first-attach app start gated on a managed store.
- After `load-elements` memoizes the latest materialization per (file, view
  line) through `cache/cached-latest`: first session after a land **+1125
  MB** (a fresh load, plus JVM slack), second **+27 MB**, third **+19 MB**.
  Attach latency stays 2.7–3.4 s: refs, the trace map and prompts are still
  loaded per session; namespaces were the bulk.
- The daemon's RSS reached 5.6 GB across a working day of writes and
  loads: retained garbage, not live data — the JVM has no reason to return
  it under the default 25%-of-RAM heap. A heap ceiling for the daemon
  (`SLOPP_SERVER_JVM_OPTS`) is the lever; sharing does not address it.
- `done` now reloads the touched namespaces and their dependents whole
  into the image before the verdict; on a done touching `slopp.ops.engine`
  (most of slopp depends on it) the closing done showed no visible cost.

## 2026-09-07 — the "live on the daemon" block: what the dev instance's cold boot found

- **The replay defect.** A session that absorbs another's writes by
  incremental replay (a session ON the branch line — the daemon's reader)
  appended a replayed `:add` and rendered the namespace in that order, while
  the writer had arranged the form before its callers. Every warm check
  agreed with the writer; only a process that had never seen the namespace
  disagreed — the dev instance's boot, three times in one evening (`db-path`,
  `enabled-runnables`, `app-managed?`, each "Unable to resolve symbol"). An
  idle-thread session never showed it because it re-forks and reloads from
  rows on sync, which is why the first two reproductions passed. Fixed:
  `engine/arrange-replayed` after every incremental advance. The dev
  instance's boot is the honest cold-load oracle and it was the standard
  app machinery that surfaced it.
- **The done-time reload is not a cold load.** Whole-namespace reload into
  the warm image re-evaluates derived values (the 2026-09-04 shape) but
  cannot see a forward reference: the var is already interned. Kept, since
  it catches what it catches; the dev instance's boot after a done is the
  cold oracle for the rest, and its failure still reaches the agent as an
  app note rather than a verdict (friction filed).
- **Restart path.** `slopp daemon stop` from the session: the old pipe died
  on the refused connection, Claude Code restarted the stdio server, the
  new pipe (carrying the new retry code) started a daemon, the session's
  next call answered from it, and slopp2 re-opened from rows with its dev
  instance up on 7358 within ~15 s. The pipe now re-ensures a daemon and
  retries once on a refused connection, so future restarts cost one late
  answer rather than a dead pipe.
- **slopp's dev instance through the standard machinery.** `dev` config
  `run.daemon.main = slopp.daemon/-main`, `args 7358`, `url …:7358/slopp/`;
  `managed?` counts declared entries; the dev daemon records itself under
  `~/.slopp/daemon-7358.json`. A pipe with `SLOPP_DAEMON_URL` pointed at it
  dogfoods the tooling under development without touching the sessions on
  the stable daemon.

## 2026-09-07 — after the deliberate daemon restart: what the idle reap and the shared map taught

- **A stdio client never re-initializes.** After the restart, this session's
  pipe was replaced by the harness (fine), and two hours later the daemon
  reaped the idle MCP session and closed the project with it (by design);
  the pipe's next request carried a session id the daemon no longer held,
  the spec's answer (404, client re-initializes) went nowhere, and the
  session was dead until `/mcp`. Fixed on both sides: the daemon ATTACHES a
  request whose session it does not hold — or that carries none — when it
  names its dir (the same trust as an initialize), answering under a new
  id the pipe adopts; and the pipe replays the client's initialize itself
  when a daemon comes back. Between them, a restart or a reap costs one
  late answer.
- **The shared materialization needed a stronger digest.** Keyed on the
  elements digest, it served yesterday's ranks after the rank-column
  backfill: a permutation of ranks (0 2 1 → 0 1 2) keeps every plain sum,
  and so does a permutation of rows. The digest's rank and size terms are
  weighted by position now (`slopp.store.db-test/
  a-forms-rank-round-trips-and-older-rows-take-their-position` is the
  test that caught it, through the external tier of a whole-store check).
  The "same-length substitution slips past" floor stands; it is covered by
  the head, which every content write moves.
- **Telemetry drops while nobody is attached.** `/slopp/status` showed 13
  dropped records after the reap: the sink routes a record by an OPEN
  thread it can see through an attached session's connection, and a project
  with no session has none. Records from a session whose project has just
  closed are lost. Filed for the sink to route through the reader or a
  short-lived connection instead (open).

## 2026-09-07 — the daemon's three open defects, closed

- **The cold oracle is a verdict.** `refresh-app!` records a dev-instance
  boot failure on the session (mirrored from the app owner), `done!` counts
  it against the STORE — a commit point is refused until the instance boots
  — and not against the episode, so the fix lands and the reboot from the
  landed state clears it. The whole-namespace reload at done stays: it sees
  a derived value that no longer evaluates; only a fresh process sees a
  forward reference, and the dev instance is that process.
- **A change that closes its unit never ran the refresh.** The closing path
  now runs it and carries the note like a plain done. Its test arranges a
  stop on purpose (no note by design) and proves the refresh ran by the
  server being gone; a boot-failure note itself is only observable with a
  managed store and a JVM.
- **Telemetry after a project closes** routes through a dir the daemon
  remembers for the thread, over a connection opened for the batch. The
  added assertions were not watched red (the done said so): the closed-
  project route was written with its test in one change.
- **Two-hour idle reap.** It closed this session's project and its dev
  instance while the human was away; correct, and it was what exposed the
  stdio client's inability to re-initialize. Nothing to change there.

## 2026-09-07 (night) — the per-session listener retired

- **What went.** `start-ui!`, `start-heartbeat!`, `ui_serve` (handler,
  descriptor, family row), `slopp.api.server`'s listener half (`serve!`,
  `stop!`, `current`, `running`, `serving`, `derived-port`,
  `preferred-port`), the whole of `slopp.hub` and `slopp.hub-test`, the
  `slopp.hub.port` capability, the brief's `:ui`/`:ui-stale`/`:hub`/
  `:hub-note`, `.slopp/ui-port` and its two readers (`bin/slopp`, the
  prompt hook). 26 tests deleted with them; the consumer-contract test
  serves its producer over `slopp.http` directly, which is all `serve!`
  ever did. Whole in-image suite green at the done; `full_check` run after.
- **What had to be kept, and was nearly missed.** `:op-cards` was set
  ONLY by `start-ui!` and read by the ask bundle's `?diet=1` — under the
  daemon every session would have shed the argument-teaching block
  silently. Now a public `slopp.mcp/op-cards`, set by `attach!`,
  `cli-session!` and `-main`. The reader agent's inventory caught it; a
  grep for `ui_serve` would not have.
- **A first-session defect the new test found.** `session_brief` on a
  daemon session whose dir has no store yet threw from `last-done` (a
  db call on a nil connection). The thread and host clauses were already
  guarded; `last-done` was not. The brief is the FIRST call an agent makes
  on a fresh project, so this was a real hole, closed by the guard.
- **The in-group delete gate is in-group.** Deleting `start-ui!` and the
  `start-heartbeat!` it called in ONE change worked, as did the seven
  `api.server` deletes with their internal call chain — the gate orders
  within the group; only callers OUTSIDE it must be edited in an earlier
  change. Earlier notes here said otherwise; they were wrong about that.
- **A managed `(declare …)` follows its forms.** Patching the auto-declare
  form by hand is refused; it was rewritten at `done` once `start-ui!` was
  gone, so the stale name in it between the delete and the done is not a
  finding.
- **Patching inside a docstring means matching the whole string.** A
  patch match must be a complete form; a phrase inside a docstring is not
  one. The docstring literal itself is, so `{match "<whole doc>" source
  "<new doc>"}` works and a 79-line docstring is not the retype the rule
  guards against — the form's code is untouched.
