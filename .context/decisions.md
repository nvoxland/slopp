# Decision log

Settled decisions. Don't re-litigate silently — revisit explicitly and record
the change here (same commit).

## D — dialect & verification philosophy

- **D1 — No `@examples` / "deterministic choke point".** Old slopp's mechanism
  presumed an untrusted black-box compile step; slopp2's agent authors real,
  readable forms with a live oracle. Verification = tests + REPL observation.
  Form-granularity comes from **runtime tracing** (which forms did each test
  exercise), not co-located examples.
- **D2 — Contracts: data-dynamism by default; instrumented open schemas MAY be
  enforced at the module-external boundary (amended 2026-07-17, see D9).** Shape
  vs. behavior are different lanes; tests+REPL own behavior/requirements, and
  internal/private code leans INTO Clojure's data dynamism — the live oracle is
  what makes loose args safe for a limited-context agent, so schemas are NEVER
  required there. The original D2 read *"nothing contract-library-specific is
  built in (no Malli/spec coupling); never enforced by the system, anywhere."*
  That clause is RELAXED for ONE locus — the exported / module-external boundary
  (the single place a slice-limited agent can't see producer and consumer
  together) — under four conditions that make a schema help rather than tax:
  boundary-scoped (exports only, privates stay schema-free), open by default
  (accretion preserved — Hickey's require-less/provide-more), oracle-instrumented
  (drift becomes a RED test, not a silent lie — why the `^:covers` marker was
  rejected but this is safe), and generative. Malli coupling accepted because
  malli schemas are plain EDN data — they round-trip through the form store like
  any value. Full rationale + roadmap: D9 +
  `ideas/research/agent-native-best-practice-gates.md`. **SHIPPED 2026-07-17, both
  channels** (see `.context/dialect.md` § Schema oracle-check): a written `:=>`
  `:malli/schema` (on the defn name) is generatively oracle-checked against its
  live impl at `done!` — drift is a red `:schema-drift` finding, never a silent
  lie (`slopp.rules.schema`); and an opt-in per-form write gate
  (`edit.modules/schema-refusal`, off by default) can REQUIRE that schema on a
  module-external map-arg fn. Verify shipped before require, so a required schema
  is always one the oracle checks. Schemas stay optional to write, verified once
  written.
- **D3 — Dialect = allow-by-default with a denylist** (analysis defeaters:
  `eval`, `alter-var-root`, `binding`, `gen-class`, `definline`,
  `read-string`; extended with the resolvers 2026-07-16 and the metadata
  mutators `alter-meta!`/`reset-meta!` 2026-07-18 — both amendments below).
  Keep data dynamism; constrain metaprogramming dynamism.
- **D3.1 (user, 2026-08-06) — the reader-conditional ban STANDS everywhere,
  and the framework pays the cost that ban creates.** Revisited explicitly
  rather than silently, on slopp-ui's ask: a `:cljc` namespace targets two
  platforms, so the refusal's own advice — *write the one branch this store
  targets* — has no answer there, and they paid ~35 lines of hand-rolled UTF-8
  percent-decoding, compile-verified on the ClojureScript side only.

  The ban stays because a branch nothing compiles is a branch no oracle
  reaches. **What changes is whose problem the platform call is:** when an app
  hits a limitation that would make it want a branch, SLOPP grows a helper
  carrying that branch — or, better, a portable implementation with no branch
  at all — and the app calls it. One implementation, verified once, instead of
  the same bit math in every project.

  Two obligations that come with it, both load-bearing. **Say so when you
  refuse:** a refusal that names the ban and not the helper leaves the author
  exactly where slopp-ui was. And **re-flag a helper that turns out to be
  APP-SPECIFIC** rather than quietly absorbing one project's needs into the
  framework — that is a decision to make with a real example in hand, not a
  default. UTF-8 percent-decoding is the worked case of a genuinely shared one:
  `slopp.web.router/query-params` already does it JVM-side, so the framework
  needed the portable version anyway and only the client half was missing.

- **D4 — User macros banned** (`defmacro` rejected). Built-in macros fine;
  runtime `macroexpand` remains the oracle for those.
- **D5 — No purity rule; refresh-vs-restart on an owned process.** Refresh is
  the fast path; restart = always-faithful backstop. Warm spare keeps restarts
  off the critical path. Detection is sampling; external side effects are out
  of scope.
- **D5.1 (user-flagged) — Smart red diagnosis, not restart-on-every-red.**
  A red cross-checks on a fresh image ONLY when staleness is plausible:
  (a) reload-signature failures (unbound var / unbound fn / no protocol impl /
  same-named-class CCE); (b) an unexplained flip — a failing test whose traced
  form-set doesn't intersect the just-edited forms (also catches value-capture
  staleness, since captured calls bypass the trace); (c) missing trace info or
  truncated failures. Otherwise `{:diagnosis :genuine}` — one run, no restart.
  Compile-gate failures heal the same way: refresh + one retry (`:image-healed`).
  `test_run {:fresh true}` forces a faithful single run; `restart` remains.
- **P1 — The oracle stays OUT-of-process (asked and answered).** Subprocess
  isolation is load-bearing for agent-generated code: guaranteed kills for
  runaway/OOM evals, `System/exit` containment (no SecurityManager on 21+),
  and D5's "fresh process = faithful by construction" purity (classloaders
  leak statics/hooks/natives). Loopback nREPL RTT is not a measured cost;
  spawn cost is amortized by the warm spare. Revisit only if we ever want
  fleets of parallel throwaway read-only oracles (isolated-classloader mode).
- **D6 — `!` naming enforced as a static effect-marker.** A fn must be
  `!`-named iff it transitively reaches an effectful leaf (call-graph
  propagation via clj-kondo; sound for first-order code; HOFs are the known
  leak, covered by runtime observation). Scope = **modification** (in-process
  mutation + external writes), NOT reads/non-determinism. Open question F7:
  stdout (`println`) is currently unflagged — matches Clojure convention, but
  needs an explicit scope call.
- **D7 — Hand-written `(declare …)` is banned; the pipeline owns form order**
  (2026-07-16). A same-ns forward ref is resolved by REORDERING definitions
  above their callers (Kahn over THE reference graph); a genuine cycle gets a
  MARKED `^{:auto-declare "<why>"}` declare the pipeline inserts itself. So a
  hand-written declare is always redundant → refused with teaching. Lives in
  `parse-form`, NOT `dialect-check`, so it binds the EDIT path only: imports
  (`dialect-scan`) keep their declares, and the pipeline's own inserts (raw
  parser, via `edit/declare-node`) don't trip the gate they enforce. Unlike
  D3/D4 this isn't an analysis defeater — it's the first prune of a human
  convenience the pipeline can fully own (`ideas/research/dialect-prunes-human-conveniences.md`).
  Mechanics: `.context/verification.md` S1b + `.context/dialect.md`.

## C — storage core

- **C1 — Purely virtual: no on-disk `.clj` by default.** VFS renders from the
  store; explicit `build!` materializes. No reconciliation loop exists.
- **C2 — Identity = opaque synthetic stable ids** (survive rename/edit;
  monotonic counter now, globally-unique ids when multi-agent arrives).
- **C3 — Form-version value = rewrite-clj CST**; canonical serialization is
  the source text (lossless re-parse).
- **C4 — Delta-log-first**: event-sourced log now; concurrent-merge CRDT
  algorithm deferred to Phase 4.
- **C5 — Same-form concurrency = MV-register** (surface conflicts), Phase 4.
- **C6 — External tools: in-process + explicit build; no FUSE dependency.**
- **C7 — Persistence = SQLite** (`.slopp/store.db`, WAL, one tx per mutation:
  delta row + touched namespaces' element rows + id counter). EDN remains the
  value representation (delta payload column). The store IS the source code —
  it gets a real storage engine, not hand-rolled EDN files.

## O — operation API

- **O1 — Write model = whole-form replace** + structural ops layered
  (rename; extract/inline/move later). No sub-form patch language.
- **O2 — Edits auto-run affected tests** (trace-map narrowed; conservative
  full-ns fallback), result recorded on the delta.
- **O3 — Query = static index + runtime oracle from day one** (`query-eval`).
- **O4 — Native-binary build target.** `build!` with `:main` emits a GraalVM
  native-image recipe alongside the sources: a generated gen-class launcher
  (`src/native/main.clj`), a `:native` deps alias (graal-build-time +
  direct linking), and an executable `build-native.sh`. The compile itself
  stays an explicit user-run step — slopp never shells out to GraalVM. The
  launcher's `gen-class` is host-generated scaffolding, NOT authored store
  code, so it sits outside the D3 gate (same standing as `slopp.kernel.rt`'s
  instrumentation). The dialect is what makes the target reliable: D3/D4's
  bans (eval, read-string, gen-class, user macros) are exactly native-image's
  closed-world assumptions. Launcher arg passing is arity-aware via the
  index: a single fixed arity of 1 receives the CLI args as one vector;
  anything else is `apply`'d -main style.

## P4 — Phase 4 (multi-agent) — MOVED

See `.context/roadmap.md` § Phase 4. The `P4-m*` / `P4-deps` milestone
names other docs cite still resolve there.
## H — host

- **H1 — slopp itself is Clojure/JVM** (same runtime as image + tooling; no
  serialization wall to the oracle; in-process clj-kondo/rewrite-clj). CRDT
  will be built in Clojure; **Rust FFI is a last-resort escape hatch only,
  never planned**. Distribution concerns → GraalVM/babashka later if needed.

## F/T/S-E/R/X-N/B — user-test & eval findings — MOVED

See `.context/findings-log.md`.
## R — run-from-store (`slopp.kernel.boot`)

R1 ✅ **A store's program runs directly from `store.db`, no exported source.**
`slopp.kernel.boot` reads every ns's byte-exact source via raw next.jdbc
(`SELECT ns, source FROM elements ORDER BY ns, pos` — the same bytes
`render-ns` emits), computes dependency order by parsing each ns form's
internal requires (a self-contained mirror of `store/ns-dependency-order` —
it can't use the store value, that's the code it's loading), then
`load-string`s each ns into THIS jvm with a `*loaded-libs*` stamp (the
in-process twin of `image/load-ns!`; the stamp is load-bearing — store nses
have no `.clj` on the classpath, so an un-stamped internal `(require …)`
would `FileNotFoundException`). Then it invokes the entry (`--main`, default
`slopp.mcp/-main`). `build!` (files) and `boot` (run) are the two exits from
the store; boot is the general counterpart — **the store is executable, not
just materializable.**

R2 ✅ **Two modes behind a switch.** `--snapshot` (default, safe) freezes a
version at startup; restart to advance. `--live` spawns a watcher that polls
`data_version` (the exact foreign-commit detector `sync-with-journal!` uses)
and, on a bump, `load-string`s the namespaces whose rendered source changed
into the running process — the host tracks its own store. Chosen because
slopp's core is protocol/record/multimethod-free plain-fn-over-immutable-map
code (var-indirection makes redefinition safe) and the green-gate guarantees
only compiling code is ever in the store to load. Documented residual
hazards (live only): the three long-lived instances (reaper `TimerTask`,
two git `HttpHandler`s) keep old closure code until re-created, and an
in-flight request finishes on the old fn body. Snapshot has none of these.

## G — git bridge (forms-as-truth, files-in-git, in-memory client)

G1 ✅ **The shared repo holds FILES; the local dir holds FORMS.** A normal
git remote (GitHub etc.) carries real `.clj` files + a generated `deps.edn`
— browsable, PR-able, useful to non-slopp users; the local working dir holds
`.slopp/store.db` and NO project source (nothing for an agent to hand-edit,
nothing to drift). `git_push` publishes the milestone projection;
`git_clone`/`slopp.sync/clone!` rebuilds a fileless store from a remote
(verified dependency-ordered `ingest!`; manifest restored from the remote
deps.edn). Cross-person merges are GIT-NATIVE (file-level, PR flow) — the
form-level CRDT merge stays a local capability (branches/forks): the merge
engine is journal-to-journal and file trees carry no journal. Incoming
non-slopp-valid files → Phase-2 pull surfaces them as CONFLICTS backed by an
off-log quarantine table (raw file kept for reference, never in the journal).

G2 ✅ **The graft: clones chain onto the remote's real history.** `clone!`
records `git-remote` + `git-base-sha` meta; `project-journal!` seeds its
parent chain with the base, so a clone's first local milestone parents onto
the remote commit it was cloned at and `push!` is a plain fast-forward
(verified: push → clone → edit → milestone → push; the new tip's parent IS
the pre-clone tip). Without this, every clone would mint unrelated history
and could never push back. Push is fast-forward ONLY — a diverged remote is
an honest error, never a force. `push-to-remote!` fetches the remote's
objects first when the graft base isn't in the (per-process) in-memory repo;
the remote itself is the durable object store for foreign history.

G3 ✅ **slopp is a git CLIENT in memory — no on-disk git state returns.**
JGit `Transport` push/fetch runs against the same `InMemoryRepository` as the
local read-only listener (built with `FS/DETECTED` — TransportLocal NPEs on
an FS-less DFS repo; scheme-less remote urls are absolutized). `slopp.git`
stays byte-moving (no `slopp.api` dep); `slopp.sync` owns the store side
(ingest/deps/session). Only MILESTONES cross the wire — a clone reproduces
the last commit_point, not un-milestone'd live state. Auth: token param or
SLOPP_GIT_TOKEN/GIT_TOKEN env. Verified end-to-end by slopp itself: push →
plain `git clone` (normal 6-commit repo) → `slopp.sync` clone (30 nses,
zero `.clj` files) → `slopp.kernel.boot` boots and serves it.

G4 ✅ **Pull is a 3-way merge at FORM granularity; conflicts quarantine
off-log.** `sync/pull!` fetches, takes git `merge-base(ours, tip)`, and
diffs base→tip trees: the remote wins wherever WE are clean (our form still
equals the base's — applied as verified `edit-group!`/`ingest!` in remote
dependency order, then `move-form!` fixup to the remote's form order so
trees byte-converge); anything BOTH sides touched, whole-file deletions,
anonymous-form files, and files failing the gates become CONFLICTS: our
version stays live, the raw remote file lands in the `quarantine` table
(off the journal — the journal only ever holds slopp-valid forms), and
**`push!` is refused until the agent resolves** (merge via edit tools →
`git_resolve`) — git's conflicted-merge semantics, one file coarser.
Comment/whitespace-only remote changes are surfaced as notes, not applied
(trivia isn't form-addressable). Each pull ends with a `:git-sha` chain
marker (`commit-point!` `:target` head + `:extra`): `project-journal!`
ADOPTS that remote commit as the chain node (never mints), so the next
local milestone parents on the remote tip and pushes stay fast-forward —
verified bidirectionally (A→B→A round-trip, byte-exact convergence).
Un-milestone'd local work rides through a pull untouched; unpushed local
MILESTONES fold into the next post-pull milestone (documented squash).

## T — the fileless flip (this repo eats its own dogfood completely)

T1 ✅ **The working tree holds NO project source.** Every namespace — the 33
system nses AND all 51 test nses — lives in `.slopp/store.db`; on disk remain
only the boot kernel (`src/slopp/boot.clj`, `src/slopp/rt.clj`),
`deps.edn` (slopp-the-tool's coordinates), docs, and benchmarks. Development
goes exclusively through slopp's MCP tools; the file↔store drift class is
gone by construction. The server runs `slopp.kernel.boot --live` so committed edits
hot-reload into the running host.

T2 ✅ **Third test tier: `^:isolated` (tag on the deftest name).** Tests that
spawn their own images/JVMs NEVER run in-image (recursion) — `traced-run`
unconditionally removes them; only the isolated runner executes them.
`isolated-test-run!` no longer shells `clojure -M:test` in the repo dir — it
**build!s the store into a throwaway dir and runs there** (the generated
deps.edn now carries a runnable `:test` alias with the cognitect runner, so
ANY built project is `clojure -M:test`-able out of the box). The suite is
store-sourced end to end: 207 tests / 1136 assertions green from a built
tree, byte-identical numbers to the old file suite. Import mechanics: 42
file-only test nses ingested through a second slopp server over HTTP (m5b
multi-server), deftests auto-tagged `^:isolated`; two forms needed
`^:unsafe` (`binding`/`read-string` boundary tests).

## S — the gate

S2 ✅ **Writes refuse NEW error-level lint** (`edit/lint-refusals`, wired
beside the cold-load check in `rebased-write!`/`edit-group!`). Kondo `:error`
findings are ~never false positives (two "invalid-arity" errors once
dismissed as noise were real ArityExceptions in shipped handlers). Diffed
candidate-vs-base per ns, keyed (type, message): pre-existing errors don't
block (no legacy deadlock), warnings stay advisory. Live-fired: an
arity-breaking add was refused by the very server that had hot-loaded the
gate minutes earlier (live mode, T1 — the running host now serves its own
just-edited code without restart, proven via the cheat-sheet edit).

S1b ✅ **The compile gate proves COLD-load, not just hot-load.** Found while
building git pull: an edit_group replaced `-main` (mid-file) to call `pull!`
(appended at the tail) and committed GREEN — the gate hot-loads into the
live image, where every var already exists and definition order is
invisible; a fresh load (boot, restart, new image) threw "Unable to resolve
symbol". The green gate's promise was violated in the cold-load sense, and
the failure surfaces far from its cause (the next restart). Fix:
`index/forward-refs` (same-ns var usage positioned before the var's first
def/declare, (row, col) lexicographic, from the kondo analysis already run
per write) + `edit/cold-load-errors`, enforced BEFORE the image is touched
in `rebased-write!` (both branches), `edit-group!`, and `move-form!` — a
move can CREATE the forward ref. Validated against the whole store: 0
findings across 33 namespaces (boot cold-loads them all). Over-approximation
accepted: syntax-quoted own-ns symbols count (declare satisfies); quoted
symbols and cross-ns usages are correctly ignored. `ingest!` exempt (a
brand-new ns cold-loads for real). Merge replay gated too
(`merge-into-session!` covers branch_merge + merge_from): replay interleaves
two individually-legal lines and CAN mint a forward ref (proven: ours
tidies away a satisfied declare, theirs adds a forward use — every per-line
write passed the gate, the merge would not cold-load; refused before the
image is touched). Wiring this surfaced that kondo's two "invalid-arity"
lint ERRORS on mcp's branch_merge/merge_from handlers were REAL — both
tools threw ArityException on every call (`:agent` kwarg their api fns
never had); fixed, checkpoint lint now clean.

S1b ✅ **Update — auto-avoid-declare (2026-07-16): the pipeline orders forms,
the agent never writes `(declare …)`.** The cold-load gate refused a forward
ref and told the agent to `edit_move` or add a declare — a mechanical
ordering chore the store (order is an element property, not text) can do
itself. Now `rebased-write!` wraps the pure transform in
`edit/resolve-cold-load`: on a forward ref it computes a topological order
(`refs/cold-load-order`, Kahn over THE reference graph) and realizes it via
`store/reorder-to` (minimal replayable `:move` deltas) BEFORE the gate. The
gate then passes; the write proceeds. Wrapping the *transform* (not patching
each branch's gate) means the reorder rides both the durable append-CAS loop
and the ephemeral in-swap rerun consistently, and all three callers
(`add-form!`, `edit-replace!`, `delete-form!`) inherit it. Only a genuine
cycle (mutual recursion — no legal order) falls through to the original
refusal, which still teaches the `declare`. **The reorder is SILENT to the
agent** — deliberately no `:reordered`/`:moved` result key: form ordering is
a file-oriented concept, and surfacing it would re-anchor the agent to the
"think about the file" model the boundary audit deletes (same category as a
`file:line` leak). Provenance is NOT lost — it lives in the move-deltas'
`"auto-reorder: define before use"` prompt, queryable by a human/tool. The
`fix_declares` **MCP tool was removed** in the same change: with writes
auto-reordered and `done!` running `fix-declares!` internally (declare
hygiene for any cycle-declare that outlives its need), there is no reason for
an agent to ever reach for it. `api/fix-declares!` stays as the internal
cleanup. Friction found + logged: the edit tools' INLINE `:test` summary
reported a green that SILENTLY omitted the `^:isolated` test just written
(the isolated run was red). [Corrected 2026-07-16: I first recorded this as
"ran it in-image and false-greened" — wrong. `slopp.kernel.rt/traced-run` has
dropped `^:isolated` unconditionally since d980; they never ran in-image. The
bug was the SILENT skip, not the execution. See `.context/verification.md` §7.]

S1b ✅ **Update 2 — full declare ownership (2026-07-16, same day): cycles
auto-declare, hand-written declares are banned.** Update 1 above left ONE case
where the agent still had to think about ordering: a genuine cycle (mutual
recursion) has no legal order, so the write refused and taught the `declare`.
The user's call: "auto-inserting declares that are needed is a good idea …
then we can fully own the declare pipeline." Now `resolve-cold-load`'s cycle
branch INSERTS the declare itself — a MARKED `^{:auto-declare "<why>"}
(declare …)` for the cycle members, built via the raw parser (bypassing the
edit gate) and appended before the first member. The marker's value is the
why — the first concrete instance of `markers-carry-their-why`; it records
provenance and lets `fix-declares!` (at `done`) remove the declare once the
cycle breaks. `edit-group!` got the same reorder/declare pass (an intra-group
forward ref shouldn't wall a batch a sequence of single writes wouldn't).
With the pipeline owning every declare, **hand-written `(declare …)` is now
REFUSED on the edit path** (`parse-form`, NOT `dialect-check` — so imports via
`dialect-scan` keep their declares, and the pipeline's own raw-parser inserts
are unaffected). Teaching: "slopp orders forms itself — drop the declare."
Consequences: the auto-declare, like the reorder, is SILENT (no `:declared`
result key); `done!`'s declare hygiene stops reporting `:declares-fixed`
(cleanup runs for effect only — the agent never manages declares). Left
refusing (deliberately, for now): `move-form!` (an explicit ordering command)
and merge replay — the agent/merge explicitly commanded that order, so an
illegal one is honest feedback, not a hidden chore.

S1b ✅ **Update 3 — ONE ordering algorithm; phantom names can't freeze a
declare (2026-07-16).** Found dogfooding the slopp.api split. TWO orderers had
grown up: `refs/cold-load-order` (Kahn over THE reference graph, the write
pipeline's) and `fix-declares!`'s bespoke conservative single-form mover (a
clj-surgeon port from when AGENTS minted declares). The Kahn sort strictly
dominates — the mover's giving-up is why `(declare isolated-test-run!)` sat in
slopp.api indefinitely. `fix-declares!` now DROPS a namespace's declares and
delegates to `edit/resolve-cold-load`: reorder, or the pipeline's own MARKED
auto-declare for a live cycle (so a legacy hand-written declare MIGRATES to a
pipeline-owned one that says why); it no-ops when the rendered ns wouldn't
change. It no longer reorders anything itself. Result: ZERO declares remain
anywhere in `slopp.api*`.
Also fixed: a PHANTOM declared name (declared here, defined nowhere — an
earlier `move-forms!` lifted the var out) classified as `:skip`, and removal
required no skips, so ONE phantom froze its declare FOREVER. slopp.api's
`f463` had 7 of 17: they minted unbound vars (a typo'd unqualified call
resolves silently instead of failing loudly) AND appeared as phantom FORMS in
`query_source`'s outline — the agent was told about forms that don't exist,
and no tool could address the anonymous declare to fix it (`query_search`
reports it by form-id; the edit tools only take a name). Phantoms are now
`:phantom`: dead, never a reason to keep a declare. Root cause still open:
`move-forms!` mints its own unmarked declares
(`ideas/move-forms-mints-unmanaged-declares.md`) — it is the last declare
writer outside the pipeline.

S1b ✅ **Update 4 — the pipeline is now the ONLY declare writer (2026-07-16).**
`refactor/move-plan` was minting its own UNMARKED `(declare …)` for the moved
set, UNCONDITIONALLY on `(> (count moved) 1)` — at both emission sites
(`:new-src` for a new target, `:append` for an existing one). That was the
FACTORY for the phantom debt Update 3 cleaned: a move plants a declare naming
what it moved; a later move lifts one of those vars out; the name becomes a
phantom (declared here, defined nowhere, minting an unbound var). Both sites
removed; `move-forms!` now calls `edit/resolve-cold-load` on the target — the
same single call `fix-declares!` makes. `edit/declare-node` (which carries the
`^{:auto-declare "<why>"}` marker) is now the ONLY declare builder in the
store; the sweep for construction outside it is clean.
Worth recording because it corrected a wrong assumption: a moved subsequence
CAN carry a forward ref. Moved nodes keep source order and the source ns
cold-loads, so I reasoned a subsequence must be ordered too — but a source may
order caller-before-callee behind a declare that STAYS BEHIND (declares are
anonymous, never part of the moved set). The planner's declare compensated for
exactly that; the right answer is to REORDER, which resolve-cold-load does.
Also fixed: the agent-facing `mcp.tools/cheat-sheet` still taught the banned
rule ("define callees first; (declare x) for cycles"). Consequence noted:
with the `fix_declares` tool removed, hygiene is LAZY (done sweeps only the
episode's changed namespaces) — 2 legitimate legacy declares (slopp.render,
slopp.repl, zero phantoms) persist until those nses are next touched.

R3 ✅ **Not slopp-special — the kernel is slopp-the-tool.** `slopp.kernel.boot` +
`slopp.kernel.rt` + the dep coordinates are part of slopp's distribution (bundled in
the jar when packaged), NOT per-project source; `rt` is the runtime slopp
injects into every owned image it spawns. So ANY store runs from its db with
zero project source files; slopp running itself is just the self-host
instance. In THIS repo the on-disk kernel is `src/slopp/boot.clj` +
`src/slopp/rt.clj` + `deps.edn`; the rest of `src/` is no longer needed to
RUN (still needed to run the file-based system-test suite — separate concern).
`--at <commit>` (boot a past milestone) is a noted future refinement.

## E — edit-surface ergonomics (friction-log fixes)

E1 ✅ **Anchored adds**: `edit_add_form` (and group add steps) take
`before=<form-name>` — insert immediately before the anchor instead of the
tail. The `:add` delta records the anchor's form-ID so foreign replay
converges on the same position (merge replay falls back to append).
`edit_group` gains a `move` action (batch reordering, one atomic commit).
The append-at-tail class that warped three designs and caused the S1b
incident is closed.

E2 ✅ **Subform matcher correctness**: matching is structural OR
whitespace-normalized-textual (fn literals gensym their args and regex
Patterns never compare equal — sexpr-only matching could NEVER match them);
a multi-form match string is a hard error (it used to silently match its
FIRST form — corrupted a case dispatch once; the compile gate caught it);
SPLICE (one match → several replacement forms) is a documented guarantee.
String CONTENT and trivia remain non-addressable (open).

E3 note: the isolated suite caught a G5 regression (a projection test
asserted the legacy agent author; the "<git>" fallback now resolves the
machine's git identity) — fixed by pinning store config in the test. Lesson
relearned: run the FULL suite after every feature, not targeted probes.

E4 ✅ **Required source args are validated, not silently dropped**
(2026-07-17, dogfooding friction). `slopp.mcp/call-tool` validated missing
SYMBOL args (`sym` → "missing required argument :ns") but passed source args
raw (`(:source a)`), so a misnamed key (`new_source` instead of `source`)
became nil and fell through to a confusing `expected exactly one top-level
form, got 0` parse error — reading like a paren bug, not a bad parameter. Now
a `src` validator mirrors `sym` on `edit_replace_form`/`edit_add_form`/
`change_signature`: it demands a non-blank source and, if it finds a
near-miss alias (`new_source`, `new-source`, `src`, …) in the args, names it
("you passed :new_source; the form source goes in :source"). Guarded by
`mcp-test/source-arg-friction`.

## G6 — files manifest + slopp3 is the permanent repo (for now)

G6 ✅ **Non-code files ride the store.** `:files` {path → text} manifest
(state-carrying `:file-put`/`:file-remove` deltas, meta row, replay — the
deps-manifest pattern), snapshotted onto milestone markers so the projection
stays a pure fn of the marker. `commit-paths` merges them into EVERY
projected tree — a slopp push never deletes the remote's README/workflows.
clone captures remote extras; pull 3-ways them (remote wins where we're
clean). Tools: `file_put`/`file_remove`/`file_list`. Motivation: CI —
`.github/workflows/test.yml` now lives on the manifest and runs the full
suite on every push to https://github.com/nvoxland/slopp3, which is the
PERMANENT published repo for now (`git-remote` meta points there).

## R4/E4 — release pipeline + trivia/string addressability

R4 ✅ **v0.1.0 shipped.** The release artifact is the UBERJAR
(`java -jar slopp.jar -m slopp.kernel.boot <dir> …`, :main clojure.main — nothing
AOT'd, the store loader keeps runtime load-string; needs Java + the Clojure
CLI for owned images). Built by `build.clj` (kernel-side + on the files
manifest so the tag-triggered release workflow can build from a checkout),
smoke-tested on a FRESH dir in CI (which caught two boot bugs: missing
make-parents and a schema-less-db crash), attached to the GitHub Release.
CI green across the board: test-files, test-via-slopp (the pushed code
imports itself into a store through every gate, then runs the store-built
suite), native-proof (a sample app built through slopp → GraalVM binary →
executed — O4's first real verification). Native remains apps-only; slopp
itself is uberjar-only. boot's --live watcher is a daemon thread (a live
server used to hang its JVM after stdin EOF).

E4 ✅ **Trivia and string content are addressable.** *(Revised 2026-07-27 —
see D-comments-are-content below; the trivia half of this is superseded.)*
`edit_trivia` replaced the ENTIRE comment/blank-line run before a named form
(or the ns tail) — `:trivia` delta anchored on the form-id, foreign replay
converges, forms untouched by construction (no image work, like move). Text
normalized to start/end with a newline; empty = delete; code forms refused. And
`edit_subform {text: true}` does RAW-TEXT replace inside a form (unique
occurrence, result must reparse to ONE form) — docstrings and string
literals, riding the full gated replace pipeline. Live-fired: the stale
"import: git push → slopp (M3)" banners in slopp.git (unremovable since
b0511ea) are finally gone, and sync/-main's docstring caught up via text
mode. The last friction-log design item is closed.

## G7 — MANIFEST.MF is tracked jar config (generic file system completed)

G7 ✅ `java -jar slopp.jar` needs NO args: the entry point is CONFIG on the
files manifest — `META-INF/MANIFEST.MF` (tracked like any file: history via
`file_history`, time travel via `file_get {at}`) carries `Main-Class` and
`X-Slopp-Main`. build.clj GENERATES the named launcher class at build time
(host scaffolding, gen-class never enters the store) delegating through
requiring-resolve, so only that one class is AOT'd. `build!` materializes
manifest files (the projection already did). Other config
files/formats ride the same generic system — it's just tracked text.

## G8/G9 — mixed ownership + structured config

G8 ✅ **The branch is the ownership boundary.** slopp pushes/pulls exactly
ONE remote branch — config `git-branch`, default **"slopp"** — and never
touches anything else; humans own `main` (docs, README, hand-managed files)
with regular git and merge across at will. `push-to-remote!` disentangles
the local projection line (`:branch`, default main) from the remote dest
(`:remote-branch`); clone finds `slopp` then falls back to legacy `main`
and records `git-branch`; pull fetches the configured branch. Local-repo
workflow: `git-remote "."` pushes into the checkout's own `.git` as the
slopp branch (guarded: never onto a branch a working tree has checked out —
JGit would move the ref under it). slopp3 migrated: slopp owns `slopp`,
`main` is human-owned (README lives there now, committed via the GitHub
API), CI triggers follow the slopp branch.

G9 ✅ **Execution config is SEMANTIC, not raw text.** The store's `:config`
({path {:format :values}}) holds key/values with per-key delta history
(:config-put/:config-unset — the non-code analog of form edits); the
projection serializes each entry into its format at tree-build time
(`store/render-config`; :manifest = sorted `K: V` lines; new formats add a
serializer case). `config_file` is the tool. META-INF/MANIFEST.MF migrated
from a raw tracked file to two semantic keys — the rendered output is
byte-identical, and `java -jar slopp.jar` still boots bare. The raw files
manifest remains for things that genuinely are opaque files (CI workflows,
until a YAML serializer exists); README and human docs belong on main.

## G10 — the onboarding flow: import into a main checkout

G10 ✅ Working dir = the HUMAN's main checkout; the store = the slopp
branch. `slopp.sync/import!` (CLI `import <dir>`, jar:
`java -jar slopp.jar --main slopp.sync/-main import .`) builds
`.slopp/store.db` inside a plain git clone from the repo's slopp branch —
`fetch-remote!` now also maps the source's remote-tracking refs, so a fresh
clone (slopp only as origin/slopp) imports directly. The store records
`git-remote "."` (relative remotes resolve against the STORE dir, not the
CWD): slopp pushes/pulls refs/heads/slopp of the SAME local repo; the human
does all origin interaction with regular git, on both branches. No
bootstrap files needed on main — the jar carries the kernel, and import
runs from the jar's bundled code before any store exists. External-system
config (CI workflows) lives on main and checks out the slopp branch on
schedule/dispatch (GitHub only runs push-triggered workflows from the
pushed ref — the honest trade for the boundary); the store keeps ONLY
config slopp consumes (MANIFEST.MF semantics, deps).

## P — probe-session findings — MOVED

See `.context/findings-log.md`.
## G11 — plugin packaging (Claude Code first)

G11 ✅ slopp ships as a Claude Code plugin: `.claude-plugin/marketplace.json`
(the repo IS the marketplace — `/plugin marketplace add nvoxland/slopp3`) +
`plugins/slopp/` bundling the MCP server entry, two skills (`slopp` = the
working loop, updated for change_signature/pair-matching/trivia;
`slopp-setup` = onboarding/sync/config/one-shot CLI), and `bin/`
(`slopp`, `slopp-server`) on the session PATH. The binary question is
solved Homebrew-style: git carries only the recipe; the launcher fetches
the VERSIONED release jar on first run (sha256-pinned, cached under
`${CLAUDE_PLUGIN_DATA}`), so bumping VERSION+SHA alongside the plugin
version is the whole upgrade story. The canonical skill home is the plugin
dir (installs are cache-copied and must be self-contained); `.agents/skills/*`
symlinks expose the cross-agent Agent Skills standard location. Parked:
Codex plugin / Gemini extension wrappers around the same jar + skills,
Clojars publication (would enable a package-manager launch path).

## G12 — the automation principle (user decision, 2026-07-13)

**Anything the system can *just do* from a high-level agent signal, it
should — but only at natural workflow boundaries, never fighting the
agent.** The pattern every automation must follow: fire on a signal the
agent already emits (serving a dir → auto-import; the first write of a
prompt → auto-turn with the verbatim ask; session pause → auto-checkpoint
pipeline: normalize, declare hygiene, re-verify, boundary), keep a manual
fallback that still works (import CLI, turn_begin, checkpoint), return to
the agent only what it must care about (terse greens, :implicated reds,
:forms confirmations, state-not-error responses). Corollaries: never
surprise mid-flight (normalize only at boundaries), never block on the
automation failing (auto-import/hook failures degrade to the manual path),
and prefer richer RESULTS over more instructions — results that carry the
reasoning close the trust gap that skill exhortations can't.

## Q — self-dogfood findings — MOVED

See `.context/findings-log.md`.
## D8 — a form defines a SET of names (2026-07-17, user decision)

**Decision:** the store's form identity is `:names`, a set of every symbol the
form defines — possibly empty. `:name` stays as the primary name for labels.
`form-named` matches any of `:names`, or a form ID.

**Why:** the previous premise — one form ↔ one name, via `form-symbol`'s
`(second s)` — is wrong in both directions, probed against kondo:

- **Registrations define nothing.** `(defmethod area :square …)` names its
  TARGET. `defmethod` sat in `def-heads` beside its own siblings
  `extend-type`/`extend-protocol`, which were never there. Result: three forms
  named `area` in one ns; `form-named` returns the first; the methods were
  unreachable by every name-keyed tool; `refs/cold-load-order` silently DROPPED
  forms including the defmulti; `static-refs` resolved `:from-form` last-wins
  against `:to-form` first-wins. Meanwhile `api/add-form!` already refuses
  duplicate names, so **ingest was admitting a state the edit layer considers
  illegal**.
- **Some definitions define several.** `defrecord` → `R`, `->R`, `map->R`;
  `deftype` → `T`, `->T`; `defprotocol` → `P` + each method var. Those were real
  public vars with **no form** — invisible to `form-named`, `:covered`, and the
  unused-public gate. Broken today, independently of multimethods.

**Rejected — a compound name (`area:square`):** `:` is legal inside a Clojure
symbol, so `(defn area:square [x] x)` is a real fn a user can write (probed: it
evaluates). Every ASCII-punctuation separator is likewise legal. The name space
is flat and user-owned — you cannot reserve a corner of it. Any scheme encoding
structure into a flat name has this bug.

**Rejected — refuse `defmulti` at the gate:** honest and close to today's de
facto behaviour, but it rules out idiomatic Clojure and fixes nothing for
`defrecord`/`defprotocol`.

**Consequences / still open:**
- Registrations are addressable only by form ID. That is their only handle, and
  the ID match also fixes `qform`'s label/address asymmetry.
- **Latent ID/name collision:** a user could write `(defn f4 …)` while some
  form's id is `f4`. `form-named` prefers names, so the name wins; `qform` has
  labelled forms `ns/f4` all along, so this predates D8. Not fixed.
- `cold-load-order` does not order anonymous forms, so a forward reference from
  inside a `defmethod` body is not auto-resolved (it was not before either — the
  form was dropped entirely). Not a regression; not fixed.
- The unused-public gate and `review_scan` filter heads to `#{defn def}`, so a
  dead `defmulti` is invisible to `done!` AND `commit_point`. Separate, unfixed.
- ~~The tracer still cannot see method bodies~~ **RESOLVED same day (C-wave):**
  `instrument!` wraps the MultiFn's METHOD TABLE — every dispatched call records
  the multi (both tiers) plus the method's form key in-image. The narrowing rule
  is in `store/method-carrying?`: defmethod/defrecord/deftype/extend-*/defprotocol
  forms never narrow (their evidence is structurally partial — including
  defprotocol, whose inline-impl call sites bypass the wrapped var via the
  protocol inline cache; found red). Static tracking sees method bodies too:
  nil-`:from-var` kondo usages attribute to the owning form by rendered span.
  Still true: defrecord/deftype method BODIES are unobservable at runtime, and
  callable data (`(def valid? #{...})`) reads `:covered 0` forever.

## D9 — Best-practice gates & skills: encoding agent-native architecture (2026-07-17, user decision)

**Decision:** slopp encodes the Clojure best practices that fit LLM agents
through TWO channels over one chassis:
- **Skills** (guidance a machine can't decide) shipped in `plugins/slopp/skills/`
  so a *user's* agent gets them, not just slopp's — a coding-best-practice skill
  and a code-review skill (`ideas/ship-review-and-best-practice-skills.md`).
- **Deterministic gates** (single-form-checkable rules) wired into the write path
  / `done`, each a client of a per-store-configurable **rule registry**
  (`ideas/rule-registry.md`) — the chassis that ends the hand-wire-into-three-
  surfaces pattern (`done!`/`commit_point!`/`review_scan`).

New architectural gates target **hard-refuse-at-write** enforcement, built on the
proven module-gate template: every hard gate ships (a) an escape marker
(`^:unsafe`/`^:reads`-style discharge), (b) an adoption story (derive initial
state from reality so enabling it never retro-breaks working code — the bootstrap
catch-22), and (c) a teaching refusal that names the exact next tool call. Scope
is the FULL checkable menu; the sequenced roadmap is
`ideas/research/agent-native-best-practice-gates.md`.

**Why:**
- **slopp already enforces much of "the Clojure way" mechanically** — the dialect
  gate (D3/D4/D7), `!`-effect labeling (D6), the module system (declared edges,
  no cycles, recursive visibility, cohesion-decides-location). This program
  extends that spine rather than starting fresh.
- **Research validates slopp's bets.** The REPL-as-oracle is the most-corroborated
  reason practitioners find Clojure good for agents (clojure-mcp/Hauman, Willig,
  Bille, Nubank); form-addressed editing is the documented cure for the #1
  failure mode (the paren death-loop). The ONE *measured* negative — a training-
  data deficit that pulls models toward imperative, non-idiomatic Clojure (Nubank
  MultiPL-E, Clojure/conj 2024) — is exactly what idiom-enforcing gates +
  REPL-first skills exist to counter.
- **Agents are cheap where humans resent ceremony.** A carrier/annotation is the
  same keystroke count for a generator; only humans feel the tax — so the
  cost/benefit of "extra structure that aids analysis" flips relative to a
  human-centric language, PROVIDED the structure is oracle-checked so it can't
  drift into a lie (`dialect-prunes-human-conveniences.md`).

**Status:** IN PROGRESS. Skills shipped (`slopp-style`, `slopp-review`). THREE
gates SHIPPED 2026-07-17: **(1) functional-core purity tiers** (`module_purity`
verb/tool, `edit.modules/tier-refusal`, hard-refuse across add/replace/group; see
`.context/dialect.md` § Purity tiers — `:pure` also forbids NON-DETERMINISM
(`rand`/`slurp`, `index/nondeterministic-vars`) so it means referential
transparency, not just mutation-freedom); **(2) schema-at-boundary** — the generative
oracle-check (`slopp.rules.schema` → `done!`'s `:schema-drift`) plus the opt-in
`edit.modules/schema-refusal` require-gate (see D2 and `.context/dialect.md` §
Schema oracle-check); **(3) key hygiene** — a DERIVED attribute inventory
(`slopp.rules.keywords/keyword-inventory`) + a near-duplicate-key advisory
(`near-duplicate-keys` → `done!`'s `:key-typos`), the program's FIRST done-time /
advisory-grade rule (the per-form write gates are hard-refuse; this is a heuristic
that never flips status). See § Attribute inventory below and `.context/dialect.md`;
**(4) contract-breakage advisory** (`slopp.rules.breakage` → `done!`'s
`:breaking-changes`) — a module-external fn whose fixed-arity surface NARROWS vs
the last-done baseline is flagged (Spec-ulation: growth is safe, breakage must be
visible to the external callers a slice can't see). Advisory, v1 = arity narrowing. The rule-registry chassis has its FIRST SEED (2026-07-17):
`edit.modules/gate-refusal` runs an ordered `per-form-write-gates` registry, held
as VARS so hot-reload is picked up, and the four write sites now call ONE
`gate-refusal` dispatch. The seed's thesis is **confirmed**: `schema-refusal`
joined as a ONE-LINE addition (`[#'module-refusal #'tier-refusal
#'schema-refusal]`) — a second new gate, one edit, not four. The registry now
spans a **SECOND GRAIN** (2026-07-17): `slopp.rules/done-advisories`, an
ordered `{:key :severity :check}` registry for the DONE-time findings
(schema-drift, key-typos, breaking-changes) — `done!` collapsed three hand-wired
advisory bindings + clauses + a status term into one `run-done-advisories!` loop,
and **`:severity`** (`:error` flips test-status; `:advisory` never does) is now
formalized. **PER-STORE SEVERITY CONFIG SHIPPED 2026-07-17** — the dial that makes
the hard-refuse program adoptable: `edit.modules/rule-severity` reads a per-store
override from a `rules` config file (`config_file {path "rules" key <rule> value
<severity>}`, git-projecting), else the rule's default. `gate-refusal` SKIPS a
write gate dialed `:off`; the done grain skips `:off` advisories and uses the
EFFECTIVE severity for status, so a project dials `key-typos` up to `:error` or
`schema-drift` down to `:advisory`. This is what lets a project turn off a gate it
can't live with instead of fighting a wall — and unblocks the opinionated gates
(require-namespaced, hard-refuse-breakage) by making them tunable. **UNIFIED
CATALOG + `query_rules` SHIPPED 2026-07-17**: `slopp.rules/rule-catalog` is
one declarative `{:rule :grain :severity :escape :teach}` catalog of every rule
across both grains; `query_rules` projects it with each rule's effective per-store
severity (the queryable "what's enforced here, at what grade, how to discharge"
surface), drift-guarded against both registries via `edit.modules/write-gate-names`.
**Follow-ups SHIPPED 2026-07-18** (post-review): write-gate **advisory-downgrade**
(`edit.modules/gate-check` → `{:refuse :advisories}`; an `:advisory` write gate
warns-but-proceeds, teaching on the write result's `:advisories`); a **DRY** of the
boundary/arglist logic into shared `module-external?` + `fn-arglists` (fixing a
first-arity-only blind spot); the **carrier-taint** D6 fix (`call-graph` adds an
edge for every usage EXCEPT a `#'var` carrier — a non-call ref at a var-quote
position, via `analyze`'s `:var-quotes` — so a var held in data doesn't propagate
effects while a call / bare alias / higher-order value-arg still does; dropped the
`^:reads` workarounds); and two more done-advisories
(`:ambient-state` — a global `(def _ (atom …))`; `:bare-throw` — a boundary fn
throwing a constructed non-`ex-info` exception). Nine rules now ride the two
registries. **Rule telemetry** (`slopp.read.telemetry/rule-telemetry` →
`query_rule_telemetry`) makes the severity dial measurable: read-only over the
delta log, per-rule fire-rate + discharge (`:discharged` vs `:persisted`) +
escape-marker density + dials — the demand signal the plan wanted, with no new
instrumentation (diagnostic only, not an auto-tuning control loop). Still ahead:
the EXECUTION-level shape-unification of the two
registries and the `commit_point!`/`review_scan` grains (`ideas/rule-registry.md`).
Design note (D6 interaction, RESOLVED): a data registry carrying a `#'bang-fn`
check ref used to be flagged effectful (carrier-taint), which forced `^:reads`
onto `done-advisories` + `status-affecting-fired?`; the carrier-taint fix (above)
removed both — the analyzer now distinguishes a `#'var` carrier position from a
call.
Remaining waves are picked on demand, each with its own red/green TDD through the
slopp tools. The named gates (purity tiers ✓, schema-at-boundary) have the user
as the demand instrument; the wider menu should still earn hard-refuse via
dogfooding (watch force-rate + marker-density — climbing metrics mean agents are
fighting a gate, the signal to soften severity per store). Design note learned by
building the first gate: **default new gates to permissive + opt-in** (absent =
ungated) — it makes the adoption/migration step disappear (purity tiers needed no
adoption, unlike the on-from-birth module manifest).

**Relation to prior decisions:**
- **Amends D2** — schemas become enforceable at the module-external boundary
  (open, instrumented, generative). See D2's amended text.
- **Extends D6** — the `!` naming gate generalizes to `*earmuffs*` (dynamic vars)
  and `?` (predicates).
- **Compatible with D5** — D5's "no purity rule" governs the RELOAD strategy
  (refresh-vs-restart), NOT architecture. The functional-core purity-tier gate is
  a per-module *locale* rule for where effects may live; it neither requires
  purity for reload nor contradicts D5. Recorded here so the two aren't conflated.
- **Builds on** the module system (the `^:export`/recursive-visibility predicate
  is the public/private cut every boundary rule rides) and `index/effectful-vars`
  (the effect closure the purity gate consumes).

## Inherent deps — slopp ships them, separate from the project manifest (2026-07-17, user decision)

**Decision:** libraries that slopp-the-tool needs for its OWN image-side features
are **inherent deps** (`repl/inherent-deps` — currently `nrepl` + `malli`), merged
into every image's `-Sdeps` in `repl/default-cmd` **after** the project manifest
(so slopp's versions win a collision). They are NOT project dependencies: never in
`deps_list`, never `deps_remove`-able, centrally versioned (an upgrade reaches
every existing install with no per-store migration).

**Why:** malli belongs to slopp, not the user — putting it in the manifest
(`deps_add`) was wrong: it showed in `deps_list`, was removable by accident, and
got pinned per-store. Inherent deps fix all three, and the mechanism generalizes
to any future slopp-owned image-side library.

**The load-bearing constraint (the two-process split):** the image is ALWAYS a
`clojure -Sdeps` subprocess (`start!` → `default-cmd` → `clojure-bin`), whether
slopp ships as a jar or a native binary — so inherent deps resolve from maven at
image launch exactly like manifest deps; it is real-deployment-correct, not a
self-host hack. But the **server/boot JVM runs on kernel deps only** — a store ns
that `:require`d an inherent lib would fail `load-store!`. So any feature needing
an inherent lib must run **image-side** (eval-injected / feature-detected), never
server-side. This is why the schema oracle-check is a self-contained eval-string
and the write-time require-gate stays structural. Litmus for every future gate
that reaches for a library: **can it run image-side? If not, it costs a kernel
dep.** Full finding: `ideas/inherent-deps-and-the-self-host-classpath.md`.

### The host DECLARES what it bundles; a manifest cannot displace it (2026-07-29)

**Decision:** the server/boot JVM states what it carries instead of pretending
the manifest can override it. `build.clj` writes the basis that produced the
uberjar to `META-INF/slopp/bundled-libs.edn`, `slopp.kernel.boot/ensure-bundled-libs!`
seeds it into the runtime basis at startup, and `deps_add` reports
`:host-override {lib {:declared :in-force}}` when the store declares a different
version of something the host already has.

**Why:** `java -jar` gives a process no basis, so `add-libs` believed the JVM was
bare. Two consequences, both measured in a real jar process:

- a coord the uberjar already carried was "added" and then LOST to the parent
  classloader (`add-libs` appends to a child that delegates parent-first), so the
  manifest read as satisfied at a version that was never in force — malli
  declared 0.16.4, running 0.17.0;
- every add resolved its transitive graph from nothing: adding one small library
  re-added `org.clojure/clojure` itself plus ten others already present.

Seeding fixes both at the source, because `add-libs` drops any lib already in the
basis's `:libs` by SYMBOL, ignoring version, and passes the rest to resolution as
`:existing`. After: the bundled coord is skipped outright, and data.json's add
went from 11 libs to 1.

**What it deliberately does NOT fix.** The declaration still cannot govern the
host — a jar the parent classloader holds cannot be displaced at runtime. It does
govern the oracle image, the test suite and `build!` output, and the image is a
separate `clojure -Sdeps` JVM (the split this decision's parent calls
load-bearing), so **the server and the tests can run different versions of the
same library**. That is now sayable rather than silent, which is the whole change.
The three ways to actually close it all cost more than the problem does: shading
slopp's copies (store code names those libs directly), a child-first loader
(breaks class identity across the boundary), or a thin launcher resolving slopp's
own deps at startup — the real fix, and a change to the install story, since the
release is today one uberjar with a pinned sha. D-surface-honesty forbids the
silent version, not the bundled one.

**Correction it forced:** `add-libs-code` and its test both claimed the image has
no basis and cited a measurement for it. Re-measured against a live oracle, it
has one — the CLI supplies it. The repo-seeding they justify is still right as a
GUARD (`start!` accepts a custom `:cmd`, and a bare `java -cp` image has no
basis), but a reason that describes a process nobody runs is worse than none,
because nobody thinks to check it.

## Attribute inventory — a DERIVED index, not folded state (2026-07-17, user-guided)

**Decision:** the domain-keyword/attribute inventory (`slopp.rules.keywords/keyword-inventory`,
`{namespaced-kw -> #{form-ids}}`) that backs the key-hygiene gate (D9 §(3)) is a
**derived view** — a pure function of the stored forms, recomputed when needed —
NOT a folded, persisted store field.

**Why (the reusable litmus):** the deciding constraint was that it must work with
the history-accessible / CRDT / multi-branch model. Because `inventory = f(forms)`:
- **CRDT merge** already reconciles FORMS (`store.merge/merge-logs` + `replay-delta`);
  `inventory(merged) = f(merged-forms)` follows for free. A folded index would need
  a bespoke CRDT merge (union per-form-id sets to match `f`), and any mismatch
  silently drifts.
- **History** is free: `inventory-at-N = f(forms-at-N)`. A folded index would need
  per-delta snapshots.
- There is **no single form-mutation chokepoint** (~7 store fns + every
  `replay-delta` case + merge), so incremental folding is a large, drift-prone
  surface.

So the litmus for any future index: **derivable from the forms the store already
versions ⇒ DERIVE it** (optionally cache behind a store-version key — a pure
optimization, never independent CRDT/history state). Contrast `:module-tiers` /
`:deps` / `:files` / `:config`, which ARE folded+persisted — correctly, because
they are independent DECLARATIONS, not derivable from the forms. SQLite fuzzy
matching (spellfix1/editdist3/soundex) was considered for the near-match and
rejected: loadable native extensions the bundled driver lacks, wrong dataflow
(the check reads the in-memory value, not the db), and no scale need — a
pure-Clojure Damerau-1 suffices. Full reasoning:
`ideas/derived-indexes-and-crdt-safety.md`.

---

## D-gates-required (2026-07-19) — every rule is blocking; zero advisories

**Decision.** All nine rules in the registry are now REQUIRED: 4 write gates at
`:refuse` (module, tier, schema, namespaced-keys) and 5 done-time checks at
`:error` (schema-drift, key-typos, breaking-changes, ambient-state, bare-throw).
No rule sits at `:advisory`. Milestone d7763.

**Why, and the rule it establishes:** an advisory an agent can scroll past is
not a rule — it is documentation with a nag. But a rule cannot be made blocking
until it is DISCHARGEABLE, and two were not:

- **`breaking-changes`** was dialled back to `:advisory` because privatising a
  fn with no outside callers — a correct change — was flagged with no escape.
  Now escapable via `^:breaking-ok` on the name. Like the other markers it
  polices itself (a marker on a changed form that narrowed NOTHING reports
  `:stale-marker`), and that self-check sits deliberately OUTSIDE the
  "was a boundary at baseline" filter: once a narrowing lands the new baseline
  is already private, so a boundary-guarded check would never see it again.
- **`require-namespaced-keys`** had one violation, `api/open!`, with 60 call
  sites. A store-wide `rename_sweep` could not do it (`:dir` names three
  different things in this store) and 60 hand edits was worse. Discharged by
  building the missing capability — `edit_requalify` / `api/requalify-boundary-keys!`
  — which rewrites a boundary fn's arglist destructuring AND every caller's map
  literal as one intent.

**The litmus this leaves:** before making a rule blocking, ask what a person who
hits it LEGITIMATELY is supposed to do. If the answer is "an edit nobody can
reasonably perform", the missing tool is the actual work — the severity is a
one-line config change afterwards. The rule's own docstring already warned that
an undischargeable rule trains people to ignore the channel.

**Two things the enabling exposed, both worth remembering:**

1. **A gate does not verify what it does not inspect.** `require-namespaced-keys`
   reads ARGLISTS, so a stale CALL SITE is invisible to it. After the requalify,
   the check that actually mattered was an independent scan for any map literal
   still handing `open!` an unqualified key. A clean gate would have felt like
   proof and been none.
2. **Syntactic rewriters cannot see through a binding, and must say so.**
   `mcp/-main` and `http/-main` build their options with `cond->`; both were
   correctly reported as `:unknown-shape` and patched by hand. Unqualified there
   would have started the server EPHEMERAL with no store directory — silently.
   Any operation of this kind must NAME what it could not reach rather than
   report a clean sweep over a partial one.

**Effectful boundary schemas are unverified by construction.** `analyzer-pure?`
excludes anything reaching an effect or non-determinism from the generative
`mg/check`, so `repl/start!` and `api/open!` carry `:=>` schemas nothing tests —
required by a gate that cannot validate them. Their docstrings say so. Keeping
them honest is a discipline, not a guarantee.

---

## D-rule-grain (2026-07-19, REVISED 2026-07-20) — two grains, and `done` means done

**Superseded design (recorded because it was wrong in an instructive way):** an
earlier version of this decision described THREE checking grains — write, done,
and milestone — and moved warning-level lint to the milestone. That was wrong,
and the evidence arrived within one session: with two enforcement points, five
`:error` advisories were blocking at `done` and completely invisible at the
milestone, because `commit-point!` recomputed status from raw test counts and
never read `:findings`. **Two bars drift. They drifted here in hours.**

### The design

There are **two** places a rule may live.

| grain | asks | mechanism |
|---|---|---|
| **write** | is this FORM well-formed? | REFUSES — the write does not land |
| **done** | is this CODEBASE good? | REPORTS — records the boundary with findings |

A check belongs at the WRITE grain only if it is decidable from the form
ITSELF and its verdict cannot change as legitimate work continues: a macro
def, a denylisted symbol, an undeclared cross-module call, a boundary contract
with no schema. Nothing else. The write grain must never treat
work-in-progress as a defect — **a red test can never be a done point**, and
red-first TDD lands specs referencing vars that do not exist yet.

Everything else is `done`, and `done` is STORE-WIDE and absolute: it runs the
entire in-image unit suite, all impacted integration and isolated tests, and
scans every namespace for lint and dead public surface. Not the episode's
namespaces — the whole store. You cannot outrun a standing problem by editing
somewhere else.

**`done` REPORTS; it does not refuse.** It records the episode boundary
honestly with its findings and says, in effect, *"no, you are not done — fix
these and call done again."* An agent that genuinely cannot fix a finding is
not deadlocked: the boundary is still recorded, red, in history. The real
block is `commit_point`, which refuses to PUBLISH a red done. You may record
where you got to; you may not ship it.

### The milestone is not a grain

`commit_point` has **no checks of its own**. It runs `done!`, adds the one
thing done deliberately skips (the full isolated tier, which spawns JVMs), and
gates on done's verdict. Nothing is re-judged. Keeping a second set of scans
there is what produced the drift above, and a second bar is somewhere to
accidentally put a check that then does not apply at `done` — which is the
failure this design exists to prevent.

`done` therefore states its one omission in EVERY result
(`:isolated-suite "not run at done …"`). An unstated omission reads as
coverage, and that is how a green status comes to mean less than the agent
thinks it does.

### How the write/done split was learned (2026-07-19)

Warning-level clj-kondo lint was flipped to REFUSE at the write grain, having
measured zero warnings across all 115 namespaces. It turned 33 assertions red
across 14 tests. Two causes:

1. **Wrong population measured.** The production store had zero, but a write
   gate applies to every write — including the fixture stores tests BUILD AT
   RUNTIME, which legitimately carry `unused-referred-var` (a three-line
   fixture doing `:refer [deftest is testing]`) and `unused-binding`.
2. **Wrong grain.** `unused-binding` is a WORK-IN-PROGRESS property. It is
   routinely, correctly true of a form mid-edit.

Same rule at the done grain: green, zero collisions.

**Two reusable lessons:**

- **Measure the population the rule will actually apply to**, not the one that
  is convenient to count. Every earlier flip in this sweep happened to have a
  production-only population, so this failure mode stayed hidden until a rule
  applied to writes-in-general.
- **A gate broad enough to catch everything will catch the tests that verify
  the gates.** `rules-test/done-surfaces-ambient-state-and-bare-throw` exists
  precisely to write rule-violating code. Targeted gates coexist with that; a
  blanket write-grain bar does not.

### Consequences accepted

- `done` is slower — it runs the whole in-image suite (166 tests here, vs ~68
  for the impacted slice). Deliberate: impacted-only answered the weaker
  question *"does what I touched still work"*, which is not what the word
  claims. Impacted SELECTION was also itself a source of misses (one untraced
  form used to collapse the whole narrowing, on 54.4% of real episodes), and
  running everything retires that machinery.
- Absolute rather than diffed, because this repo is the only slopp codebase
  and there is no legacy to deadlock. A store adopting slopp with pre-existing
  findings would be red at every `done` until clean — acceptable, because
  `done` reports rather than refuses, so nothing is blocked meanwhile.

---

## D-rule-grounding (2026-07-23) — a rule's predicate is a ROLE, never a coincidence

**Decision.** A rule may only fire on a property that is *constitutive* of the
thing it claims to have found. Ranked, best first:

1. **Declared** — the author said so (`:web/path`, `^:generated`, a module
   edge). No false positive is possible.
2. **Grammatical role** — the value sits where a real grammar fixes its
   meaning (an HTML element's URL attribute; a hiccup attr map's position).
   Precision is bounded by the grammar's own ambiguity, which is small.
3. **Presence of a name somewhere in the form** — a COINCIDENCE test. Not
   allowed. This is the one that keeps shipping, because it is the easy one
   to write and it looks right on the fixture you wrote it against.

**The evidence.** `web-dangling-route-refs` extracted route references by
finding any map anywhere in a form with an `:href`/`:action` key. On the real
store that made `{:op :add :action :replace}` — a sync plan step — a route
reference: 16 findings, zero of them dischargeable by anyone. Two successive
tightenings (attribute position, then value type) each shrank the residue
without ever being able to reach zero, because each was a better guess rather
than a different KIND of predicate. The fix that ended it was grade 2: `:href`
names a URL on `<a>`, `<link>`, `<area>`, `<base>` and `:action` on `<form>`,
per HTML, and nowhere else. There is no residue to shave because the question
is no longer being approximated.

This is the same failure as the withdrawn `:positional-form-access` advisory
(4–5 false positives out of 5, `ideas/correspondence/the-patterns-behind-every-failure.md`
Pattern 1), whose recorded lesson — *"positional access is not the real
predicate"* — is this decision generalized. Twice is a class.

**A rule with a hidden finding class has an unmeasurable precision.** The
`:unresolved` half of this rule was deliberately kept OUT of the findings so it
could not flip status — the only lever available before per-finding severity —
and its 16 false positives were therefore invisible for as long as it shipped.
The `:info` grade (`status-affecting-fired?`) exists so a rule never has to
choose between *blocking everyone* and *being unmeasurable*. **Ship every
finding; grade the ones that should not flip.**

**Consequence for review.** Before registering a rule, run it over the whole
real store and read the finding list. The bar is this project's existing one —
*a metric must only count findings someone can discharge* — and a rule that
cannot state which grammar or declaration grounds it has not met it yet.

---

## D-components (2026-07-24) — components are REAL namespace prefixes, not a label alongside them

**Decision.** slopp's 28 flat modules are regrouped into nine components by
RENAMING namespaces under component prefixes (`slopp.db` → `slopp.store.db`,
`slopp.repl` → `slopp.image.repl`, …). The alternative — a declared component
label layered over the existing module grain — was considered and rejected.

**Why renaming, given it is the more expensive option.**
`slopp.edit.modules/module-of` hardcodes *module = first two namespace
segments*. So a prefix is not cosmetic here: it IS the enforced unit. Renaming
makes each component one module, which means cross-component calls require
declared `module_dep` edges and a component's deep namespaces become
package-private with `^:export` as their declared public surface. A label
alongside the module grain would have been a second, advisory naming of
structure that nothing checks — precisely the "one relationship is first-class,
the rest rot" core (`.context/design-disciplines.md` Core 2). We would have
shipped a component map that could drift from the architecture it described.

**Anchored, not abstract.** Each component is anchored on the namespace it
already owns (`slopp.store.*`, not a new `slopp.storage.*`). Measured: 16
production renames and 72 new `^:export` markers, against ~80 renames and 223
markers for fresh abstract prefixes, with a shallower tree and no change to the
published CLI entry point.

**Exempt, and each for a stated reason — a published interface is not
taxonomy's to spend.**
- `slopp.kernel.boot` / `slopp.kernel.rt` — the on-disk kernel AND the published entry point
  (`clojure -M -m slopp.kernel.boot`, `java -jar slopp.jar`) appearing in `deps.edn`,
  `build.clj`, `README.md`, `DEV.md`, the docs site and a shipped release blog
  post. They sit outside the component map, which `.context/architecture.md`
  already frames as the honest place for "slopp-the-tool, not project source."
- `slopp.sync` — its `-main` appears in users' CI recipes.

**The framework/app boundary this exists to protect.** `slopp.web.*` is the
framework slopp SHIPS to users (the slim `io.github.nvoxland/slopp-web` jar);
`slopp.ui.*` is slopp's own webapp built on it. The restructure absorbs nothing
into `slopp.web`, and the module gate enforces the direction: `slopp.web`
declares zero outgoing edges and sits at layer 0, so a call from the framework
into slopp's core refuses at the write.

**Accepted cost, stated so it is not rediscovered as a bug.** ~80
namespace→namespace edges enforced today (`db → store`, `render → store`)
become intra-component and stop being checked. Layering WITHIN a component is
no longer a gate. If that bites, an intra-component layering rule is a later,
additive change — do not pre-build it.

**Unanticipated cost, found by dogfooding the fold.** Purity tiers are
namespace-grained and inherit by PREFIX, so folding a namespace under a
component silently subjects it to that component's tier. `slopp.db` and
`slopp.mine` were undeclared (= `:external` = ungated) standalone modules;
under `:pure` `slopp.store` they inherited `:pure`, and `full_check` stayed
GREEN with both violations resident because the functional-core gate fires on
WRITE, over the forms a write touches. Declaring the three deep namespaces
explicitly (`slopp.store.db`/`.mine` `:external`, `.merge` `:pure`) fixed this
store. **The class is open**: a tier acquired by inheritance is never verified
against the population it newly governs. See `ideas/logs/ui-wave-frictions.md` #11.

**Consequence for wave-scale renames generally.** A namespace name lives in
strings as often as in symbols — generated `deps.edn` main-ns, `(ns …)` source
in test fixtures, child-JVM program text, CLI usage lines in docstrings.
`ns_rename` rewrites symbols perfectly and reports NO string hits, which is the
confident-partial-answer shape **D-surface-honesty** names. Until it reports
them, a rename wave owes a `rename_sweep` dry-run pass per retired name.

---

## D-surface-honesty (2026-07-24) — the read/analysis layer inherits the write layer's discipline

**Decision.** A surface that reports on code may never conflate *"I checked and
found nothing"* with *"I could not check."* Every analysis/report/read result
that can be incomplete says so in the SAME breath — `:unverified`, `:partial
true`, `:coverage :none`, `:via :static|:observed|:declared`, a `:reason`. The
write pipeline already lives this (a failed gate refuses; `:status` names which
tier ran; `:red-first`/`:carried-errors` mark interim states). The analysis
layer did not, and that is where this project's characteristic failure lives: of
~20 recorded failures, roughly two threw — the rest returned a confident,
well-formed, WRONG answer (`ideas/correspondence/the-patterns-behind-every-failure.md`
Pattern 4).

**Why it is decision-grade, not hygiene.** The write gates earn justified trust,
and an agent spends it on every slopp surface — acting on a read WITHOUT
double-checking (measured repeatedly). So an incomplete read in slopp is worse
than the same read on files, where the agent would have verified. A read surface
that lies by omission spends trust the write boundary earned.

**Consequences.**
- Absence-of-finding and absence-of-check get DIFFERENT representations, always.
- A composite that gains a field must NAME it where the reader looks (skill +
  result) or it goes undiscovered — capability existing ≠ capability found (P9).
- An unknown/typo'd tool argument is REFUSED, not silently dropped (the `dry-run`
  flag that evaporated and ran the real op — the most dangerous friction logged).
  SHIPPED d12549: `slopp.mcp.tools/unknown-arg-keys` refuses at the `call-tool!`
  chokepoint (wire calls only; the api layer stays lenient).
- This is **D-rule-grounding**'s finding-grade `:info` rule (ship every finding;
  grade the ones that shouldn't flip) raised to SURFACE grade. Same stance, two
  granularities.

Full lens + the other three cores: `.context/design-disciplines.md`.

---

## D-kondo-config (2026-07-20) — slopp owns the linter config, and `:level` means "can this be legitimate mid-edit?"

**Decision.** `slopp.index/kondo-config` is a static def, shipped with slopp,
passed EXPLICITLY to `kondo/run!`. It is not a file in the user's repo, not a
per-store knob, and not something a project configures — linter levels are
part of slopp's definition of clean code, like the dialect and the rule
registry.

**Why explicit rather than letting kondo resolve it:** kondo otherwise reads
config relative to the process CWD. That is the bug #134 fixed for the
*cache* — findings that varied by which directory the process happened to
start in. The tree is fileless, so a store cloned elsewhere must lint
identically or `done` means different things on different machines.

**The tiering rule — one question decides everything:**

> Could a form legitimately look like this MID-EDIT, on the way to something
> correct?

- **No → `:error`.** Blocks at every grain. Refusing immediately saves a
  wasted episode.
- **Yes → `:warning`.** `done` LISTS it (`:lint-warnings`) for the agent to
  judge; nothing refuses it.
- **Measured worthless → `:off`,** with the numbers recorded in place.

This puts the write/done grain split (D-rule-grain) into ONE declaration.
Both consumers simply read `:level` — `edit/lint-refusals` gates writes on
`:error`, `done!` counts `:error` and lists `:warning`. An earlier attempt
introduced a separate `write-blocking-lint` TYPE set alongside the levels;
it was deleted, because a second declaration of "what blocks" is a second
place for the two to disagree — the exact failure mode that hid five
`:error` advisories from the milestone.

**The tiering was chosen by the SUITE, not by taste.** Promoting every linter
to `:error` broke 13 assertions across 7 tests, in three distinct ways, and
each failure named a linter that is legitimately true mid-edit:

| broke | linter | why it is legitimate mid-edit |
|---|---|---|
| red-first TDD | `:unused-binding` etc. | a spec names a not-yet-written fn |
| module lifecycle | `:unresolved-namespace` | a legitimate forward reference |
| carried-lint compression | `:redundant-let` | pre-existing, untouched forms |

Those failures map one-to-one onto the warning tier. When a promoted linter
breaks a test, the test is usually right: it is demonstrating a legitimate
intermediate state.

**Two linters were measured and rejected outright**, recorded in the config
so the rationale is not re-derived:

- `:shadowed-var` — 156 findings, **81 of them the parameter `agent`**
  shadowing `clojure.core/agent`, which this codebase never calls. The rule
  an agent actually wants is "a symbol means one thing in one form", but the
  linter cannot distinguish "shadows something unused" from "shadows a fn
  used in this very form", and the volume is slopp's own domain vocabulary.
  Enforcing it renames the RIGHT words to prevent a hazard that has not bitten.
- `:unsorted-required-namespaces` — 62 findings, and the wrong TOOL. Require
  order is mechanical: the normalizer should SORT it. **Never warn about what
  a tool can fix.**

**Two were added for slopp-specific reasons, both measuring zero:**

- `:unused-value` — a value computed and DISCARDED in non-tail position is
  the language-level form of the bug that bit this codebase four times at the
  wire layer (computed, then dropped by an allow-list).
- `:missing-else-branch` — `(if x y)` returns an implicit nil, and this
  project's signature failure is a plausible wrong value rather than a crash.

**Caching:** the lint memo now includes a config fingerprint (`:cfg`).
Without it, editing a linter level silently did nothing until restart — the
config is part of the world a finding is true under, alongside the cache dir
and the dependency fingerprint.

---

## D-full-check (2026-07-20) — `done` is episode-scoped; the whole store is one explicit call

**Supersedes the store-wide half of D-rule-grain.** That decision made `done`
absolute — every namespace linted, dead surface store-wide. This walks that
back: `done` answers *did the work I just did come out clean*, which is the
question an agent can act on. Whether the STORE is clean is a different and
much slower question, and it is `full_check`'s.

| | scope | forced? |
|---|---|---|
| write | this form's coherence (`edit/write-coherence-lint`) | yes, refuses |
| `done` | the episode: whole in-image suite + impacted `^:external`; lint + dead surface over TOUCHED namespaces | automatic, REPORTS |
| `full_check` | every namespace, every tier (in-image, `^:integration`, `^:external`) | **never** — agent's call |
| `commit_point` | nothing of its own; gates on done's verdict | — |

`full_check` also retires any need for an integration-only or lint-only
tool: one call, everything, no tier flags to get wrong.

**`done` states its scope in EVERY result** (`:scope`), naming what it did
NOT cover and pointing at `full_check`. An unstated omission reads as
coverage — that is how a green status comes to mean less than the agent
thinks it does.

### The cost, accepted explicitly

Nothing automatically verifies the whole store before publishing. A red
`^:external` test the episode never TOUCHED will not stop a
milestone, and `commit-test/the-milestone-forces-no-whole-store-check` PINS
that, so it is a test someone must consciously change rather than a silent gap.

The word **touched** is load-bearing. "Gates on done's verdict" means the
milestone's `done!` is a *real* done — `:external? true`, so it runs the
impacted `^:external` slice exactly as a standalone `done` does. A milestone
therefore DOES stop over a red `^:external` test this episode touched
(`commit-test/a-milestone-catches-a-touched-red-external-test`); it is only the
UNTOUCHED corner that rides through to `full_check`. A 2026-07-20 review found
`commit-point!` had regressed to calling `done!` with `:external? false` — a
milestone weaker than a plain done, laundering a touched red external green.
Fixed; the two commit-tests above pin both halves.

The trigger worth internalising is **"you deleted a caller"**: dead public
surface appears in namespaces the episode never touched, which is the one
thing episode scope structurally cannot see. That is in the `:scope` reminder
and the tool description.

### The hole this opened, and the fix

Episode scope plus a milestone with no checks of its own meant **a red done
could be laundered by committing**: `done` reports red → ignore it →
`commit_point` runs a fresh `done` → nothing changed since → `:test-status
:none` → publishes green. Found by `episode-test/unused-publics-gate-the-done`.

`api/last-judged-done` fixes it: an empty done judges NOTHING, so the last
real verdict stands until new work supersedes it. It returns the whole
findings map rather than a status, so a standing red can still NAME what was
wrong — a refusal that cannot say why is one an agent cannot act on.

### Two calibration notes, both learned by breaking the suite

- **`:unresolved-var` must NOT block a write.** It is kondo-default
  `:warning`; promoting it into the write-coherence set broke red-first TDD,
  because a spec naming a fn in another namespace that does not exist yet is
  exactly what red-first IS.
- **Never warn about what a tool can fix — and check whether one already
  does.** A test asserting `missing-else-branch` counts at `done` kept failing
  with zero errors: the NORMALIZER rewrites `(if y 2)` → `(when y 2)` before
  lint runs. The linter is harmless but redundant there. Worth auditing
  `:redundant-fn-wrapper` and `:single-key-in` for the same overlap.

---

## D-tiers-internal-external (2026-07-20) — the axis is internal/external, not read/write

**Decision.** Purity tiers are **`:pure` / `:internal` / `:external`**.
`:reads` is RETIRED. (`:reads`/`:effects` remain accepted as legacy spellings
of `:internal`/`:external`.)

- **`:pure`** — referentially transparent. No mutation, no non-determinism.
  This is what lets the generative schema oracle (`analyzer-pure?` +
  `mg/check`) run on a form at all.
- **`:internal`** — may mutate IN-PROCESS state (a memo, a registry); touches
  nothing outside the process.
- **`:external`** — IO: files, subprocesses, network, the database.

**The evidence that decided it.** Measured across the whole store on the old
read/write axis: **6 `:pure`, 0 `:reads`, 19 `:effects`.** The middle tier had
ZERO members, because read/write puts a memo `swap!` in the same class as a
`git push`. On the internal/external axis the middle is populated
immediately — `render`, `edit.refs` and `cache` moved out of the same tier as
`db` and `repl`.

**Why this axis and not that one:** it is the axis that decides how a thing
must be TESTED, which is the whole reason the tiers exist.

| tier | test strategy |
|---|---|
| `:pure` | plain unit test, no setup; generatively checkable |
| `:internal` | in-image test plus a cache/state reset |
| `:external` | isolation — fresh JVM, temp dirs, cleanup |

Read/write cannot do this: an external READ needs the same isolation as an
external write (`slurp` needs the file to exist), so the distinction buys
nothing at the point where it would have to pay.

**Layering keys on EXTERNALITY, not tier ordering.** A non-`:external`
namespace may not require an `:external` one — but `:pure` MAY depend on
`:internal`, because an in-process memo is observationally pure from outside.
Forbidding that would mean the pure core could use no memoized helper, which
in this codebase means no pure core at all. The coupling is real but bounded:
a `:pure` namespace is then only as transparent as its dependency's cache
KEYS are correct — which is exactly why caches must go through one construct.

### The blessed cache (`slopp.cache`)

Every memo goes through `cache/cached` (value-keyed) or `cache/cached-last`
(identity-keyed). Hand-rolled memo atoms are what this replaces. It buys four
things, and only the last one is about tiers:

1. **Testability** — `reset-all!` clears everything; `without-caching!` makes
   every call recompute, so a test proves the COMPUTATION rather than a
   previous call's answer.
2. **Staleness** — the key is a value you pass, in one place. slopp's lint
   memo silently served findings computed under an old linter config until a
   config hash was added to its key; its validity check is FOUR
   hand-maintained terms, each a place to be wrong.
3. **One eviction policy** — not hand-rolled per site (kondo's memo cleared
   itself at 256 inside a `swap!`).
4. **Mechanically checkable tiers** — "does this namespace mutate only through
   `slopp.cache`?" is DECIDABLE. The alternative considered was a `^:memo`
   marker, rejected because "is my memo semantically transparent?" is an
   unverifiable author claim, and this session has been punished repeatedly
   for those.

**Two strategies, both real** — this was nearly missed:

- `cached` — value-keyed map with eviction. For small keys.
- `cached-last` — memoize-LAST keyed on `identical?`. For keys too large to
  hash: the whole-store reference graph is memoized on the STORE, and hashing
  that map every call would cost more than the computation saves. Sound
  because the store is immutable — a new value appears only on a write, so
  same identity means same content BY CONSTRUCTION. Wrong for anything
  rebuilt per call: two `=` values that are not `identical?` miss every time
  and the cache silently never hits.

**`cached` is deliberately NOT `!`-named.** D6 propagates effects across
namespaces only through `!`-named callees, so `cached!` would make every
memoizing caller effectful and defeat the entire point. `without-caching!` IS
`!`-named — it flips a global, and its callers are tests.

**Tiers are namespace declarations, never form metadata.** They are
`:module-tier` deltas carrying their `:prompt` (why) with full history — not
`^:meta` on a form, which has no provenance. And there is deliberately NO
per-form escape: if one `defn` could opt out, "this namespace is core" would
be unverifiable without reading every form, which destroys the only property
that makes the claim useful. The escape is to MOVE the form. That is the
pressure that produces the shape.

### Standing debt, deliberately not reclassified away

Remaining layering violations are genuine core→edge dependencies, not
classification noise: `edit.modules`/`refactor` → `index` (kondo writes a
cache DIRECTORY on disk), `api.orient` → `db`, `api.telemetry` → `api.rules`
(evals in the image). `slopp.index`'s two remaining hand-rolled caches stay
hand-rolled for now; it is `:external` regardless because of that directory.

---

## D-external-test-tier (2026-07-20) — `^:isolated` is `^:external`; the marker names the REASON

**Decision.** The test marker is `^:external`, matching the namespace tier.
368 forms swept in one intent; the gate accepts ONE spelling.

`^:isolated` named the MECHANISM (runs in a separate JVM) rather than the
reason (it touches the world outside the process). That is why nobody could
answer "should this test be isolated?" — the name did not say. With
`^:external`, the question is mechanical: **a test is external when it
exercises an `:external` namespace.**

Worth noting the codebase was already inconsistent: `isolated-test-run!`'s
docstring said "a FRESH EXTERNAL JVM" and the refusal said "external tests
run in the external suite". The tier was already called external while the
marker was called isolated, which is probably what made the old name
confusing in the first place.

### Two migration hazards, both general

**1. A live gate cannot be renamed atomically with the marker it enforces.**
The first sweep was REFUSED at step 6: `isolation-refusal` requires tests
calling `repl/start!` to carry the marker, and it runs from the OLD compiled
code while the group rewrites it — so a one-shot sweep is refused at the
first test it re-tags. It needs two phases: accept both spellings, sweep,
then tighten. This applies to any self-hosting system where a rule and the
code it governs share a store.

**2. A sweep rewrites prose DESCRIBING the sweep.** The transitional comment
explaining `:isolated -> :external` came out reading `:external ->
:external`.

**Tightening to one spelling was NOT optional.** Tolerating `^:isolated` as
well would be worse than rejecting it: the runner (`test-var-tiers`) reads
`:external`, so a tolerated old marker would pass the gate and then run
in-image and RECURSE. Two checks disagreeing is this codebase's recurring
failure; a compatibility shim would have manufactured a fresh instance.

### Demoting the mislabeled tests

**22 tests** dropped from the external tier to plain in-image units. The
criterion was the codebase's OWN: `edit/spawning-vars` defines what would
recurse in-image, and `isolation-refusal` REFUSES a write that drops the
marker from a test reaching one — so the gate arbitrated every demotion
rather than my judgement. The clearest case: `build-native-test/arg-style-t`
was spawning a JVM to test `arg-style`, a pure function over a clj-kondo map.

**Seven candidates were deliberately left external** (`git-client-test`,
`multiproc-test`, `commit-test`, `mcp-test`). They pass the no-spawning-var
check, but the check sees only DIRECT symbols — a fixture helper could spawn
without the test naming it, and an in-image recursion HANGS rather than
failing cleanly.

**The honest bound on the criterion:** it is sound for REFUSING (the gate
sees what a form directly calls) but not complete for PERMITTING. Demote on
it; do not trust it to clear the last few.

---

## D-api-decomposition (2026-07-20) — what `slopp.api` actually is, measured

**Status: IN PROGRESS, and the remaining step is a DESIGN CALL, not a refactor.**

`slopp.api` was 105 forms / 4030 lines and read as "the god namespace".
Measured on the internal/external axis it is less wrong than that suggested,
and the wrongness is specific.

### What the measurement showed

| | count | |
|---|---|---|
| thin pass-throughs (<15 lines AND delegating) | **10** | it is NOT a facade today |
| self-contained (no delegation to a deep ns) | **50** | implementation living at the top |
| over 40 lines | 34 | |

So `slopp.api` was not a facade being mistaken for a namespace — it was a
namespace doing implementation work. That settled the sequencing: push
implementations DOWN first, and only then ask the facade question.

### Extracted so far

- **`slopp.read.query`** (`:pure`) — 33 forms: the 25 pure query operations
  plus every pure helper they use.
- **`slopp.ops.review`** (`:pure`) — `review-scan`, 122 lines of analysis.

`slopp.api`: 105 → 71 forms.

**`edit_move_forms` found the seam, not judgement.** The first attempt
proposed 25 forms and was REFUSED with a two-way-split analysis naming
exactly what was missing (seven pure helpers), then refused again
(`label-ancestors`). The direction it enforces is the correct one:
`revert-episode!`/`undo!`/`done!` calling INTO the moved set is shell→core
and fine; the moved set calling back out is a cycle. **Propose a cluster and
let the tool close it transitively** — guessing leaves a cycle.

### The remaining state, and the open question

`slopp.api` is now **71 forms: 11 pure, 51 internal, 9 external**.

The 51 are `:internal` because they mutate the **session atom** — in-process,
not IO. That is what a functional core with an imperative shell is SUPPOSED
to look like: decisions pure, orchestration mutating one owned piece of
state, IO at nine named entry points. The "63/105 effectful" reading was an
artifact of the read/write axis conflating a memo with a subprocess.

**THE OPEN QUESTION** — and it is the facade question in another form:
should the **9 external** forms (`open!`, `done!`, `commit-point!`, `build!`,
`full-check!`, `external-test-run!`, `config!`, `author-identity`,
`git-config-value`) live apart from the 51 orchestration operations?

- **For:** `slopp.api` becomes declarable `:internal` — the gate would then
  apply to the largest namespace in the store.
- **Against:** `open!`/`done!`/`commit_point` are THE primary operations;
  moving them decides what `slopp.api` *is*. Mechanically safe (the tool
  rewrites every caller), but it is a product decision, not a cleanup.

**Do not infer this one.** Decide it, then execute.

**RESOLVED 2026-07-20 (user decision: push the external usage out).** All
ten IO forms now live in `slopp.ops.external`. The stated payoff did NOT
materialise, and the measurement is the useful part: `cleanup` reports
`slopp.api` still `{:tier :external, :supports :effects}`, with 50 of its
62 remaining forms blocking `:internal`. The IO was never those ten forms —
it is the SESSION. Every mutating operation writes a delta to sqlite and
drives the image subprocess, so `slopp.api` is an imperative shell by
construction. That is the correct shape, and the pure core already exists
beneath it (`.query`, `.shape`, `.modules`, `.review`, `.history`,
`.breakage`, `.attrs`, `.orient`). Do not re-attempt this for tier reasons.

### Not yet touched (readability, not architecture)

`edit-group!` (156), `move-forms!` (152), `edit-replace!` (134) — long
internal forms. And `done!` at 200 lines, worth its own pass: it is the
most-changed function in the codebase.

## D-analysis-not-io (2026-07-20) — `:analysis` and `:findings` want different worlds

The tier-layering check reported **9 core→shell dependencies** and nothing
enforced them, which violates the standing "never just warn" rule. The cause
was one shared kondo pass.

`slopp.index` produced `:analysis` and `:findings` from a single cached run
against kondo's on-disk cache dir. The two have different honest keys, and
the store already said so: `run-kondo` was keyed on SOURCE alone *"because
that is the honest key for `:analysis`"*, while findings carry `:cache-dir`
and `:fp` precisely because they depend on cross-namespace cache state.
Sharing the pass made analysis IO — and `analyze` is what nearly the whole
pure core reaches for, so every one of those namespaces inherited an
external dependency it did not actually have.

**Verified before restructuring around the claim** (the premise was
checkable and therefore had to be checked): a warm-cache run and a
`:cache false` run differ ONLY in `:fixed-arities` on cross-namespace
var-USAGES. Nothing in slopp reads that — every arity reader
(`query-outline`, `deps/surface`, `build/arg-style`) takes it from
var-DEFINITIONS, which are same-source.

Three namespaces now:

| | tier | holds |
|---|---|---|
| `slopp.index` | `:external` | the kondo run, cache dir, `lint`, the atoms |
| `slopp.index.analyze` | `:internal` | cache-free analysis, blessed memo |
| `slopp.index.derive` | `:pure` | 15 forms deriving facts from analysis |

**The false start is the transferable part.** The first attempt split only
the pure derivers out — defensible structure, and it discharged **zero** of
the 9, because callers depend on `slopp.index` for `analyze`, not for the
derived facts. The split that looks principled is not always the one that
moves the number. Re-run the check; do not inspect the new shape and infer.

**Cost, measured both sides.** The shared pass existed to hold per-write
kondo cost at one run; this trades it for two. Benchmarks: calculator
504→460ms, inventory 114→109, wordstats 147→144, identical token counts —
no regression. The test that guarded the old invariant was rewritten rather
than deleted, and now guards the concern that was actually load-bearing:
neither pass may recompute for the same source.

Layering went **9 → 5**. The remaining five are genuine judgement calls, not
artifacts: `slopp.ops.review` really uses `lint`, `.orient` really uses
`slopp.db`, `.query`/`.telemetry` really use `slopp.rules`.

**Corollary, and it caught four namespaces here:** a stale require is not
cosmetic. A namespace inherits the TIER of everything it requires, so a
require left behind after a move makes a `:pure` namespace report as
depending on the shell for something it no longer uses. `move-forms!` now
prunes orphaned requires on BOTH sides — source and rewritten caller —
scoped to the move's own damage, never a blanket prune (a require kept for
`defmethod` registration is indistinguishable from a dead one).

## D-serving-is-not-adoption (2026-07-21) — the store is created by the first WRITE, never by serving

The MCP server is launched by the editor in whatever directory the user has
open. `api/open!` called `db/open!` unconditionally, and `db/open!` creates —
so **every project a user opened got a `.slopp/store.db`**, whether or not
they had ever heard of slopp. Three writers compounded it: the server created
the store, the plugin's `UserPromptSubmit` hook did `makedirs(".slopp")`
unconditionally to drop `pending-intent`, and the `Stop` hook — correctly
gated on the store existing, which the first two guaranteed — then wrote a
`"session pause"` checkpoint at every session end, forever, into a store with
no code in it. Measured damage in `findings-log.md`.

**The decision: opening a session ASKS whether a dir is slopp-managed; it
never answers yes on the dir's behalf.** `db/open!` takes `{:create? false}`
and returns nil when there is no store; `external/open!` uses it, and a
session on an unadopted dir runs cache-only with `:db` nil — which is the
already-tested ephemeral path, not a new one. `api.session/ensure-db!` on the
commit path materializes the store at the first real write. That is the ONLY
place a directory becomes slopp-managed implicitly.

Three things had to follow the store rather than the dir, and each was a
separate leak:
- the kondo cache dir (`external/open!` keyed it on `dir`, so an unadopted
  dir still got a `.slopp/kondo-cache`) — now keyed on `conn`;
- the git smart-HTTP listener (`git/open-ctx!` opens its own connection, and
  would have recreated exactly the store being avoided) — `mcp/-main` now
  starts it only for a session that has one;
- `turn/append-marker!`, the hook-driven CLI path, which opened its own
  connection too. A marker is provenance ABOUT a store; with no store it is a
  no-op, never an adoption.

**This makes the code match its own shipped documentation.** The `slopp-setup`
skill has always told users "the server creates an empty store on the first
write" — the behaviour it described was the intended one all along, and only
the implementation disagreed.

**Accepted cost, and it is the honest trade.** In a genuinely fresh dir the
prompt hook writes no `pending-intent` (it now requires a store too), so the
first write is refused with `no open turn — call turn_begin {intent: …}`. One
extra explicit call, once per new project, and the provenance is *better* for
it: the agent supplies the user's verbatim ask rather than the hook inferring
it. Rejected the alternative of auto-opening a turn for storeless sessions —
it would silently attribute a project's very first write to nothing.

## D-web (2026-07-22) — web applications: the boundary program applied to HTTP

The deferral in `ideas/deferred-framework-and-http.md` is lifted; the design
center is a THIRD-PARTY developer building an arbitrary web app through slopp
(slopp's own endpoints are the dogfood, never the design driver). Settled, to
be built in waves on the `web` store branch (plan of record: the approved
web-applications plan; frictions log: `ideas/logs/web-wave-frictions.md`):

- **Capability registry (wave 0, SHIPPED 2026-07-22).** A store declares what
  kind of application it is in a `capabilities` config file riding the
  EXISTING `:config` CRDT (G9 — no new fold-field). What is new is the
  DECLARED registry (`slopp.api.capabilities/registry`): one entry per key —
  `{:key :type :default :doc}` — from which the validator, the effective-value
  read, and `query_capabilities` all derive (the rule-catalog shape, applied
  to configuration). Types are a small STRUCTURAL vocabulary, not malli:
  this ns loads in the server/boot JVM, kernel deps only (the two-process
  split; the schema-refusal precedent). Write-time validation: `config_file`
  on the capabilities path REFUSES unknown keys (a typo'd capability that
  silently does nothing is the nil-pun failure the registry exists to kill)
  and type-failing values, with teaching. `app.main`/`app.name` persist the
  app manifest — `build!` falls back to them, so the entry point is store
  state, not a tool argument. `web.enabled` (default false) is the master
  opt-in every web rule will be gated on: a store that never opts in is
  untouched (the purity-tier adoption lesson).
- **An endpoint is a `defn` with `:web/*` metadata** (method, path, auth,
  schema) — no macro, no dialect change, no central route table. Metadata is
  read straight off the stored node like `^:export`/`:malli/schema`, so every
  existing gate applies and routes enumerate with zero eval.
  **Wave 1 SHIPPED 2026-07-22:** the `:web/*` markers are declared edges in
  the reference graph (`declared-refs` — `:web-endpoint`/`:web-effect`/
  `:web-read`, so endpoints and performers never trip the unused gate);
  `query_routes` (via `slopp.rules.web`, `:pure`) reports the surface from the
  SAME shared derivations (`edit.modules/web-endpoint-rows`/`web-performers`)
  the four new write gates check — `web-auth-refusal` (default-deny),
  `web-route-collision`, `web-undeclared-effect`, `web-unsafe-get` (a safe
  method may neither declare effect kinds nor reach a mutation) — registered
  in `per-form-write-gates`, cataloged, severity-dialable, and all inert
  until `web.enabled`. Design note learned landing it: the route-row keys
  are `:web/effects`/`:web/reads` NAMESPACED — bare `:effects`/`:reads` trips
  the retired-tier-vocabulary advisory, and slopp namespacing what it adds is
  the standing §2 rule anyway.
  **Merged to main 2026-07-22** — after the branch dogfood fixed the merge
  pipeline itself: `merge-logs` replays `:move` deltas (order is
  load-bearing since D7, the "cosmetic" premise predated it);
  `merge-into-session!` loads new namespaces and existing namespaces'
  changed forms in ONE interleaved dependency-ordered pass (a new
  downstream ns must compile against just-merged upstream forms); dead
  changed-ids (added-then-deleted on the branch) are pruned and
  `hot-load-form!` guards vanished forms; the one-shot CLI's errors carry
  their cause chain and stack. Full friction narrative:
  `ideas/logs/web-wave-frictions.md`.
- **Request/response maps stay RING-shaped**; slopp namespaces only what it
  ADDS (`:web/identity`, `:web/effects`, `:web/reads`, …). The training-data
  prior is an asset, not a hazard; the novelty budget is spent on
  effects/reads-as-data and declared auth only.
- **Handlers are pushed pure**: reads declared as data (`:web/reads` naming
  app-defined `^{:web/read k}` performers), writes returned as data
  (`:web/effects` naming `^{:web/effect k}` performers), the dispatcher
  interprets both — slopp never learns domain vocabulary. Escape ladder:
  declared reads → read capability → `^:web/effectful` (`:external` ns).
- **Auth is declared on the form** (`:web/auth`, default-deny via the
  `web-auth-refusal` gate) and enforced by the dispatcher before the handler
  is reachable; providers/groups live in the capabilities config; secrets are
  `env:NAME` indirections.
  **Wave 3 core SHIPPED 2026-07-22:** `slopp.web.auth` (`:internal`) —
  three providers (bearer, static basic-auth with sha-256 verify,
  proxy-header gated on trusted `:remote-addr`), first-claim-wins in
  declared order, config group augmentation, env-indirect secrets through
  an injectable `getenv` seam; `dispatch/handle!` resolves identity through
  `:web/auth-config` when the request carries none (a pre-resolved identity
  is respected); `config-from-values` is the ONE parser from capabilities
  strings to runtime config; `web-unknown-group` gates the policy
  vocabulary (a typo'd group is the authz nil-pun) and the capabilities
  gate refuses credential literals (`web-secret-literal` behavior — the
  config is git-projected). Proven 401/403/200 over the wire on http-kit.
  DEFERRED, deliberately: OIDC (last per plan, with its own native-image
  proof) and the `web-public-mutation` done-advisory. ALSO: the resync
  surfaced a merge bug minting a DUPLICATE form name via the rename
  interplay (frictions #19 — image ran the stale shadow while reads showed
  the fresh form; repaired by id-addressed delete, engine fix owed with
  #16).
- **Server: http-kit default adapter** (native-proven, ring-compatible,
  WebSockets), `:jdk` kept as the zero-dep fallback, Helidon/hirundo the
  named in-process-TLS upgrade path, ring-jetty rejected (native-image
  breakage). The adapter is a capability key behind a one-function seam.
  **Wave 2 SHIPPED 2026-07-22:** the `slopp.web` module — `router`
  (`:pure`), `routes` (var-metadata scan, `:internal` — the universal route
  source: live store, jar, and native binary all answer from var meta),
  `dispatch` (`:external`, the shell by declaration: route → policy →
  declared reads → handler → all-or-nothing effect interpretation, failures
  as data, ex-info `:web/status` mapping), the facade (`context`/`handle!`/
  `enforce`/`authorized?`/`serve!`/`stop!`), and BOTH adapters behind the
  seam. `slopp.http`'s `/call`, `/mcp`, `/metrics` are declared endpoints
  served through the facade (wire-compatible — the old transport tests pass
  unchanged), and slopp's own store runs `web.enabled = true`. Design
  corrections learned landing it: `enforce` not `require!` (a throwing
  guard mutates nothing; a bang would falsely taint every pure handler
  doing row-level authz); test namespaces' endpoint-shaped forms are
  FIXTURES (excluded from rows so they neither report nor claim paths);
  the `:web/effectful` marker must live ON THE NAME with the rest of the
  contract; http-kit rides both the store manifest AND kernel deps.edn
  (mirrored — boot must load `server.httpkit`).
- **Static assets: content-addressed blob table** (sha256 → bytes), `:files`
  values polymorphic, deltas carry the sha (wave 4).
  **Wave 4 SHIPPED 2026-07-22 (on main directly — bidirectional branch
  merges are paused until the #16 causality redesign):** `record-file-put`
  takes `:encoding "base64"` + `:content-type`; the manifest entry becomes
  `{:sha :content-type :bytes}` with bytes in the `:blobs` cache and the
  `blobs` db table (same tx via `put-blobs!`, INSERT OR IGNORE); the delta
  carries the sha only. `store/file-content` is the ONE polymorphic
  accessor; `file_put`/`file_get` speak base64 on the wire; the git tree
  and `build!` emit real bytes (`insert-tree!` accepts byte arrays, the
  projection threads a blob reader); `merge-logs` unions theirs' blobs.
  Serving: `slopp.web.static/mount-routes` (mounts + a reader → `:public`
  GET rows answering `:web/raw` responses both adapters write verbatim —
  no JSON wrapping), wired in `slopp.http` from `web.static.*`
  capabilities over the store, so an edited asset hot-serves under
  `--live`. LATENT BUG FIXED en route (frictions #21): merge-logs' unknown-
  op default silently SKIPPED every `:config-put`/`:file-put` — main had
  lost its whole capabilities config across three wave merges; state-
  carrying non-code ops now replay through `store/replay-delta`
  (key/path-grain last-writer-wins), guarded by
  `merge-test/config-and-files-cross-the-merge`. Deferred: single-segment
  mount files only (the router's declared param scope), blob pruning via
  `cleanup` (blobs are immortal-by-default, the git model), and
  `-H:IncludeResources` (wave 5).
- **Wave 5 partial SHIPPED 2026-07-22:** the native recipe carries assets —
  `native-script` copies manifest paths into `classes/` (resources on the
  compile classpath) and passes `-H:IncludeResources`, so a binary serves
  its own files; `static/file-or-resource-reader` is the built-app reader
  (filesystem under the app root first, classpath resource fallback,
  extension-derived types) — one `mount-routes` reader works for jar and
  native alike. THE WAVE-5 TAIL, deliberately together: the slim
  `io.github.nvoxland/slopp-web` jar (user apps' dep), the native-proof CI
  extension that consumes it (a sample web app with an asset, compiled and
  curled), and `slopp.kernel.boot` add-libs for store manifests — plus wave 3's
  OIDC. Each needs the release surface the others define; shipping them as
  one arc beats four stubs.
- **RELEASE TAIL SHIPPED 2026-07-22 — the program is complete.**
  `web-public-mutation` registered+cataloged (the rule table is whole).
  OIDC as a RESOURCE SERVER, zero new deps: JDK RSA verifies RS256 JWTs
  against configured/fetched JWKS (kid lookup, iss/exp/aud), claims →
  identity via a configurable groups claim; `fetch-jwks!` does discovery
  at serve-time; the browser login flow stays the IdP's/proxy's job
  (recorded, not implied). Providers now take an opts seam
  ({:getenv :now}). `build.clj slim`/`slim-install` cut
  `io.github.nvoxland/slopp-web` (slopp/web.clj + slopp/web/** — TWO
  globs, the root facade is a sibling file) with clojure+cheshire+http-kit
  deps; PROVEN end to end locally: a scratch store `deps_add`'d 0.1.2 from
  ~/.m2, declared an endpoint + a content-addressed asset, built with
  app.main, and served — /hello answered, the asset's exact bytes and
  image/png came back. `native-proof.yml` gained a `native-web-app` job
  running that exact flow through GraalVM. `slopp.kernel.boot` resolves the store
  manifest via add-libs at load (DynamicClassLoader installed — the
  documented non-REPL constraint), so `java -jar slopp.jar <dir>` boots
  ANY app; proven against the scratch store from the bare kernel. Known
  small gaps, recorded: HEAD requests don't route (a HEAD-as-GET mapping
  is a one-liner when wanted); the jar needs a rebuild for the kernel
  change (deferred past the #16 arc to dodge the #17 jar-swap).

**Security hardening (2026-07-22, from an adversarial code review).** The
write gates prove a route's contract statically; these close the RUNTIME
gaps the static analyzer can't see, each with a red test modelling the
hole:
- **Auth fails closed at every degenerate point.** An empty `[:all]`
  policy authorized everyone (`(every? pred '())` is vacuously true) — it
  now denies, like `[:any]`/`[:group]`/nil already did.
- **Effects are bounded at runtime by the route's declaration.** The
  static `web-unsafe-get`/`web-undeclared-effect` gates see only the
  handler body; the dispatcher now refuses a response effect kind the
  route's `:web/effects` never declared (a `:get` returning a write is
  stopped even when a performer exists).
- **Error bodies are redacted.** An `ex-info` with `:web/status` surfaces
  its message plus ONLY a `:web/public` allowlist; any other exception is
  a generic 500 with the detail logged, never returned (no `ex-message` /
  `ex-data` disclosure).
- **Bodies are bounded.** Both adapters read at most `:web/max-body-bytes`
  (default 1 MiB, the `web.max-body-bytes` capability) and answer 413 —
  the unbounded slurp was a heap-exhaustion DoS, and the configured limit
  had been dead.
- **Crypto.** Static passwords are salted, iterated PBKDF2
  (JDK/native-safe, `web/hash-password`/`verify-password`) not unsalted
  SHA-256 — the hash rides the git-projected config, so it must resist
  offline cracking; bearer and password compares are constant-time
  (`MessageDigest/isEqual`). **OIDC audience is mandatory**: an unset
  `web.auth.oidc.audience` denies every token (a resource server must reject
  cross-audience tokens); a set one must match `:aud`.
- **Static traversal is contained** in the reader (canonical path under
  root + a `..`-segment refusal), not left to the router's single-segment
  accident, which the reader's own docstring flagged as temporary.
- **Proxy header lookup is case-insensitive** — adapters lowercase header
  keys, so a canonically-cased `web.auth.proxy.user-header` config now matches.

## D-merge-causality (2026-07-22) — round trips are causal

The branch dogfood's standing hazard (frictions #16/#19, three failure
modes on record: false conflicts, silent drops, duplicate-name corruption)
is closed in `merge-logs` itself:

- **Returning work converges.** A theirs-suffix delta whose `:merged-from`
  names one of OUR OWN deltas — content-matched by the same sorted-sources
  comparison the imposter guard uses — is our work coming back from a
  prior merge of us, and is skipped silently at suffix construction. A
  copy they EDITED after receiving is a separate, untagged delta and still
  replays normally.
- **Fids resolve to live forms.** Ping-pong accumulates `:id-map` entries
  whose targets die while the original id lives on; every arm now resolves
  through `live-fid` (the mapped id first, then the INVERSE of their own
  recorded `:id-map` — an edit they made to their copy of our form lands
  back on our original, since the id spaces bifurcate at the first remap
  and never re-join — then the original id, each checked against a live
  form) so a stale mapping cannot silently drop an edit as "we deleted it;
  they edited it".
- **Duplicate names refuse.** A merge candidate holding two same-named
  forms in one namespace is corrupt by construction (last-definition-wins
  shadows silently: the image runs one form while every name-keyed read
  shows the other) — the merge returns `{:error}` naming the collisions
  instead of landing.
- **Edits fast-forward when we haven't moved.** The both-touched conflict
  arm compares our current content against `their-base` — the last source
  THEIR log held for the form before the edit. Equal means their edit
  builds on exactly what we have (a creation-touch or a converged round
  trip), so it applies instead of conflicting. Only genuine divergence —
  both sides moved past the shared base — conflicts.
- **Partial replays are not imposters.** The recreated-source guard
  compares by SUBSET, not equality: our replayed copy of a delivered delta
  may hold fewer sources (forms that didn't resolve here), which is honest
  history; recreation means the copy carries content the original never
  had. The identity-mismatch error carries `:fork-point` so callers can
  tell it from "no shared history" — one masked the other once.
- **Conflicts coalesce per form.** Successive theirs-ops on one diverged
  form land as ONE conflict (fid-keyed `note-conflict`, newest theirs
  wins) — a form edited sixteen times upstream is one decision for the
  reader, not sixteen rows burying the real signal.

Each fix is guarded by a merge-test modeling the production failure that
motivated it. Acceptance on the real web←main resync: 33 residual
conflicts became 2, both genuine divergence. Bidirectional branch flow is
unblocked. Deliberately NOT done: lineage-aware conflict prose ("their
edit builds on your v2") and milestone-marker travel (#9) — both
recorded, neither load-bearing.

## D-fold-field-registry (2026-07-22) — one declaration site per store op

Every cross-cutting store concept gets exactly ONE declaration site
(`slopp.store.fields`), and the sites that used to hand-copy it now derive:

- **`field-registry`** — fold-field → `:init` (seeds `empty-store`; every
  field now seeded, closing the :files/:config/:blobs absent-until-first-
  write nil-pun), `:meta-key` (persist!/append!/load-store's row),
  `:normalize` (applied at load — retired vocabulary canonicalizes),
  `:absent-nil?` (the :modules pre-module adoption marker is never
  defaulted or overwritten).
- **`op-registry`** — delta op → `:fold` (THE fold: `record-*`,
  `replay-delta`, and merge replay all call it, so in-memory, foreign-sync
  and merge state can never drift), `:merge` strategy (`:replay` =
  last-writer-wins through the fold; `:bespoke` = merge-logs keeps a
  semantic arm — deps version resolution, module-edge union), and a
  `:sample`/`:crossed` pair.
- **The harness is GENERATED.** `fields-test/every-registered-op-crosses-
  a-merge` runs each op's sample through a real fork + `merge-logs` and
  asserts `:crossed` — registering an op without merge semantics is
  structurally impossible, which permanently closes the class where merges
  silently dropped every config/file delta for three waves (frictions #21:
  the unknown-op default was a quiet skip).
- **Unknown ops REFUSE the merge** with teaching naming the registry —
  never guess with, or silently drop, someone's state. `markers` and
  `element-ops` classify everything else; a new marker op registers once
  or foreign sync full-reloads on every sighting.
- **Retired tier spellings are dead in fold state** (frictions #5): the
  one `canonical-tier` mapping lives in the registry
  (`edit.modules/canonical-tier` delegates), the `:module-tier` fold and
  the db load both normalize through it, and the surviving `:effects` row
  (`slopp.lab`) was re-declared canonically. Deltas keep the author's
  spelling verbatim — history is honest, state is canonical.
- **`db/write-snapshot!`** is the one transaction tail `persist!` and
  `append!` share — the copy-pasted meta-row blocks whose silent-miss
  failure mode (survives tests, vanishes on the live server's restart) the
  registry idea file documented are gone.

Element/delta/blob storage stays bespoke (the registry covers the
meta-row folds, per the idea file's scope). `ideas/store-fold-field-
registry.md` is closed by this entry.

## D-episode-grain (2026-07-22) — discharge windows look past the baseline; milestones do not travel

Two decisions from the frictions-architecture program's episode wave:

- **A NEWLY-added `^:breaking-ok` discharges against history.** The
  breaking-changes advisory fires at the done that first sees a narrowing,
  and the baseline advances AT that done — so a marker added one episode
  late (the only possibility when the narrowing arrives via a merge)
  compared against the already-narrowed source and read as stale; the
  escape was unusable exactly when it was needed (frictions #15). Now: a
  marker ABSENT at the last-done baseline earns a bounded walk of prior
  done baselines (newest first, ≤12, skipping marked sightings — the
  marker cannot vouch for itself); if the form narrowed since it was last
  seen unmarked, the marker discharges silently. A marker ALREADY present
  at the baseline keeps the remove-the-flag discipline unchanged — the
  self-policing that stops markers decaying into permanent opt-outs.
- **Milestone markers deliberately do NOT travel through merges**
  (frictions #9, now settled): a branch merge is a squash — main's history
  stays at main's own milestone cadence, the branch line keeps its fine
  grain while it lives, and merge-logs' `{:skipped :commit}` note is the
  honest record. Replaying `:commit` markers would mint mid-merge git
  commits whose `:tree` snapshots never existed on the receiving line.
  Revisit only if branch lines become long-lived archives rather than
  integrate-and-delete work lines.

Not built, recorded in `ideas/research/episode-grain.md`: promoting the episode to
a first-class journal object (changeset atomicity in merges, episode-grain
provenance queries). The concrete asks that motivated it are served — 
edit-group!/edit_move_forms are the changeset grain for writes, and the
discharge window above.

## D-web-html (2026-07-22) — server-rendered UI: hiccup pages, UI→route integrity

The HTML story for D-web apps. The deciding question was representation:
hiccup vs "actual HTML". It collapses on inspection — slopp pages are
functions of data (`:web/reads` → response), and raw HTML cannot express a
loop or a conditional, so native HTML storage really means HTML **plus a
template language**: a third language living in strings, invisible to the
refs graph, unevaluable by the oracle, with its own injection surface.
Hiccup is the only representation where a page IS a top-level form and
inherits the whole machinery — form-grain deltas, provenance, rename
sweeps, the dialect gate, covering tests, `web/handle!` as the render
oracle — for free. Two supporting facts: HTML5 parsing is error-RECOVERING
by spec, so "malformed HTML" does not exist as a write-time signal (the
Clojure reader gives hiccup balance-checking free), and the LLM failure
modes in hiccup are narrow and mechanically checkable, where template-
in-string errors are caught by nothing. htmx was deliberately dropped from
the wave: it is not a representation (it is an optional JS enhancement via
attributes), nothing in this design depends on it, and plain links + forms
+ full-page renders are the simpler model. Decisions:

- **hiccup 2.0.0 as a dependency, wrapped by an owned strictness layer**
  (user call — reuse the dep's logic rather than own a renderer).
  `slopp.web.html/render` = validation walk → `[:html/raw]` transform →
  hiccup serialization. The slim jar goes 3→4 deps (`build.clj`
  slim-deps + the store manifest). The security tests PIN the dep's
  escaping contract so a hiccup upgrade that changes behavior turns red.
  Probing the dep first (query_eval) surfaced why the wrapper is
  load-bearing: hiccup renders crafted tag/attr NAMES verbatim (a real
  injection door), silently `str`s a map in child position, and does no
  URL-scheme checking.
- **The strictness layer**: tag/attr names validated (throw, not escape);
  `javascript:`/`data:` refused in `href src action formaction` (escaping
  cannot neutralize a URL scheme); teaching errors for map-in-child-
  position (→ `cond->`) and vector-as-grouping (→ seqs); `[:html/raw s]`
  string-only, kept as DATA in forms and converted to hiccup's raw wrapper
  only at render, so pages stay `=`-testable and the ref walker sees
  literals. Escaping tests are labeled SECURITY.
- **`page` is RETIRED (2026-08-23), and nothing replaced it.** It assembled a
  document from named parts (`:html/title`, `:html/lang`, `:html/head`), so the
  parts needed names. Once `:http/path` names a stored `def` whose value is the
  whole document, there are no parts: an app writes `[:html [:head [:title …]]
  …]` and title, lang, stylesheets and meta are hiccup it already holds.
  `render` prepends the doctype to a top-level `[:html …]`, which is the only
  thing `page` did that a hand-written document could not.

  **The `:http/title` / `:http/head` markers that looked necessary were an
  inherited assumption, not a requirement** — they existed only to carry
  `page`'s parameter list forward. `:http/head` in particular would have put
  arbitrary hiccup in NAME metadata, where every other marker is a scalar or a
  schema, and nothing could have checked it. The absence is the design.

  The strict-CSP property survives unchanged, and now by construction rather
  than by a helper's restraint: nothing emits inline script or style, because
  nothing emits anything. Apps still set their own CSP header.
- **UI→route referential integrity, analysis-side** (`slopp.rules.web`):
  `ui-route-refs` (a derived pure fn of forms — the keyword-inventory
  litmus, so correct across branches/merges/history) classifies literal
  `:href`/`:action` values `:exact`/`:prefix`/`:unresolved`;
  `dangling-route-refs` joins through `slopp.web.router/match` (scoped
  `^{:export "slopp.api"}` + the new `module_dep slopp.api → slopp.web`
  edge — one matcher truth) plus `web.static.*` mounts with file
  EXISTENCE. `query_routes` rows gain `:rendered-by`. The key set covers
  href/action today; hx-* verbs slot in if htmx is ever adopted.
- **`web-dangling-route-refs` done-advisory, `:error` default,
  store-wide** — like dead surface, because deleting a route dangles an
  UNCHANGED form's link. Inert until `web.enabled`; discharged by fixing
  the path, adding the route/asset, or `^{:web/external-path "why"}` on
  the rendering form. Dynamic refs are NAMED (`:unresolved` via
  `api.web/dangling-route-refs`) but never findings — an `:error` key has
  no non-flipping finding lane (friction #4 in
  `ideas/logs/html-wave-frictions.md`).
- **`web-react-attrs` write gate, refuse-grade** — `:className`,
  `:htmlFor`, `on[A-Z]*`, `:dangerouslySetInnerHTML` in a literal map in
  position 2 of a keyword-tag vector. The silent-failure class: browsers
  ignore unknown attributes. Scoping to element position keeps JSON-ish
  payload maps out; the per-store dial is the escape.
- **Fragment auth stance carried forward**: every endpoint declares
  `:web/auth`; the browser dogfood's `/store*` endpoints are `:public`
  WITH the recorded justification (the co-hosted `/call`+`/mcp` expose
  strictly more).
- **CSS is the same story, via garden** (`slopp.web.css`, added when the
  question "something similar for CSS?" came up). CSS-as-Clojure-data is
  to stylesheets what hiccup is to HTML: a stylesheet is a `defn` GET
  endpoint returning `css-response` (text/css, `:web/raw`), rules are
  garden data. Same shape as the HTML side: garden 2nd dep-with-owned-
  strictness-layer (`render` refuses `{ } <` in any selector/value string
  — garden renders them verbatim, the identical injection door hiccup
  had; `;` is deliberately NOT refused because data URIs
  (`data:…;base64,…`) use it and, absent a `}`, a stray `;` only appends
  a declaration to the same rule). The garden dep excludes the optional
  YUI-compressor (drags Rhino). No new integrity machinery: a
  `[:link {:href "/styles/app.css"}]` is a literal `:href`, so the
  EXISTING `web-dangling-route-refs` advisory covers CSS links for free —
  verified live, `/css/style.css`'s route reports `:rendered-by`
  `[slopp.http.browse/shell]`. Raw/vendored CSS uses the static-asset
  path, not the renderer. `render` rides the slim jar (`slopp/web/**`).
- **Dogfood**: `slopp.http.browse` — the read-only store browser
  (`/store`, `/store/ns/:ns`, `/store/source/:ns/:name`, and
  `/css/style.css` — its own stylesheet as garden data with a
  dark-mode `@media` block) in the server module (never rides the slim
  jar), plain links, full-page renders, arbitrary store source through
  the escaper as a standing security exercise; wired into
  `start-server!`'s `:web/namespaces`.

Deferred, deliberately: htmx (additive hx-* attributes + one static blob
when an app wants partial updates; `ideas/product/htmx-hiccup-ui.md` keeps that
half), build-time static generation (`:web/static true` materialization —
a serving strategy, not a representation change), an image-side
html→hiccup conversion tool (build on pasted-HTML friction), an
oversized-page-defn advisory, a CSP capability key, and ClojureScript
(`ideas/clojurescript-client-code.md`, escalation after htmx). Wave
frictions: `ideas/logs/html-wave-frictions.md`.

## D-web-cljs (2026-07-22) — client-side ClojureScript: one store, one dialect, compiled on the JVM

The CLIENT half of D-web: browser-side logic authored the way Clojure is —
form-grain edits, merges, provenance, write-time verification — by writing it
in ClojureScript/`.cljc` in the SAME store, edited by the SAME tools, gated by
the SAME dialect, and compiled to JS as a build step whose output rides the
existing content-addressed blob store. The alternative (a `src/js/` directory,
or npm/JS tooling) is a second world none of slopp's machinery can see into.
The deciding realizations, all settled with the user: real
`org.clojure/clojurescript` compiles cljs→JS **entirely on the JVM** (Google
Closure is Java — no Node to compile), and a `.cljc` form's `:clj` branch is
ordinary JVM Clojure, so the EXISTING oracle already verifies the majority of
client logic. GraalJS/Node were considered and dropped: running the compiled
JS is browser territory (Cypress/Playwright someday), not the write-verify
loop. Decisions:

- **The `:platform` register** (`module_platform` → `:module-platforms`),
  namespace-grained, most-specific-wins, absent = `:jvm` (nothing changes for
  existing code). `:jvm` = `.clj`, loads on the JVM, never compiled (default).
  `:cljc` = `.cljc`, loads on the JVM (`:clj` branch) AND compiles to JS —
  shared schemas/logic. `:cljs` = `.cljs`, NEVER loaded into the oracle
  (references `js/*`/DOM), ONLY compiled. It routes: which extension
  `render/source-path` emits, which tree `build!` materializes to (`cljs-src/`),
  and whether the image loader / cljs compiler sees the ns. Mirrors the
  `:module-tiers` purity register exactly; landed almost free on the
  D-fold-field-registry (one field row + one op row auto-generated the merge
  proof, empty-store seed, and db persistence).
- **The oracle is JVM + compile-error — NO GraalJS, NO Node in the inner
  loop.** `.cljc` platform-neutral logic → the existing JVM oracle (free, and
  trustworthy: real-cljs semantics are very close to JVM Clojure). Compile-error
  -as-oracle: the real cljs compiler runs on the JVM at build time, so "does it
  compile?" is a genuine form-anchored red/green (analyzer warnings via
  `*cljs-warning-handlers*` + hard-error `ExceptionInfo`, both anchored to the
  owning store form, name-addressed, no file:line). Running compiled JS is
  OUT OF SCOPE. The one honest tradeoff — a cljs-ONLY fn (touches `js/*`) is
  verified only by "it compiled" until browser tests exist — is mitigated by a
  discipline good regardless: **keep platform-neutral logic in `.cljc`
  (JVM-verified); keep `.cljs` thin, at the genuinely browser-bound edge.** The
  dogfood proved it — `slopp.client.nsfilter/matches?` (`.cljc`) red/greened on
  the JVM like any fn; only the DOM glue (`slopp.client.nsview`) is `.cljs`.
- **The compiler is pluggable, per-project config** (user call). Real
  `org.clojure/clojurescript` now; the stored source and the `:platform` marker
  are compiler-agnostic (just Clojure(Script) source), so the compile step
  DISPATCHES on a `compile-client*` multimethod keyed on the `client`/`compiler`
  config (default `:clojurescript`). cherry/squint slot in later as new
  `defmethod`s **without re-authoring a single form** — swap the config, not the
  code. Only `:clojurescript` is implemented; the others are registered
  deferrals.
- **A build-only dependency channel** (`deps_add {client true}` → `:client-deps`
  manifest → a `:cljs` alias in generated `deps.edn`), following the `:native`
  alias precedent. It carries `org.clojure/clojurescript`; it is NEVER
  hot-loaded into the running oracle and NEVER enters the shipped native binary
  or slim jar. `compile-client!` (an `:external` op) materializes the store,
  shells `clojure -M:cljs` in a fresh JVM (reusing `run-cmd!`/`clojure-bin`
  verbatim), and `file_put`s the JS — served by the EXISTING static mount with
  zero new serving code (`public/cljs/main.js` → `/js/main.js`).
- **The write-path enabler**: a `:cljs` form references `js/*` and can't load
  into the oracle, so the base write ops (`add-form!`/`edit-replace!`/`ingest!`/
  `create-ns!`; `delete-form!` already) pass `:load? (store/jvm-loadable? …)` to
  `rebased-write!` (skipping the per-form JVM hot-load) and report
  `session/cljs-deferred-summary` — `:unverified`, reason
  `:cljs-deferred-to-compile` — instead of running the oracle. `ns_create
  {platform}` declares the platform BEFORE the source lands, so a client ns is
  BORN `:cljs` and its first `js/*` form defers instead of failing to load.
  `query_depends {modules true}` surfaces `:platforms` alongside `:purity`.
- **Platform-scoped rules** (user call: "everywhere / clojurescript / clojure").
  Every write gate carries a `:rule/platform` scope (`:everywhere` default /
  `:clojure` / `:clojurescript`); `gate-check` reads the form's platform and
  skips a rule the scope excludes — and a `.cljc` form satisfies BOTH worlds
  (it compiles to both). The M3 JVM-dep effect boundary re-scoped to `:clojure`
  (cljs's dep world is npm/JS, analyzed differently). This governs slopp's OWNED
  rules; kondo's own per-lang linting is a separate, still-open item (below).
- **Effect/dialect fit**: the dialect gate is MORE at home in cljs (D3 no-eval,
  D4 no-user-macros are already law). But D6 `!`-effect naming assumes JVM
  effects — in the browser everything touches the DOM — so a `:cljs` module
  stays the permissive `:effects` default and the D6 `!`-warning is ADVISORY
  (it fires as a false positive on idiomatic `^:export main`; left the names
  idiomatic and logged it). A platform-aware `:client` effect tier is deferred.

- **Follow-ons shipped (Wave 4 a/b/c).** (b) A **shared `.cljc` malli schema**
  proves a real validation LIBRARY crosses the boundary: `slopp.client.nsschema/
  ns-row` is JVM-verified AND compiled into the client bundle. malli was a
  kernel-only dep absent from the store manifest, so the cljs compile couldn't
  resolve it — added to the build-only `:client` channel (the bundle now
  carries malli's cljs code; the JVM keeps using the kernel copy). (a)
  **Platform-aware kondo linting** — `index/lint` gained a `:lang` arg threaded
  from the ns platform (`store/kondo-lang`), so a `:cljs` form's `js/*` and
  `cljs.core` resolve instead of a false unresolved-namespace finding; the memo
  keys on `[source lang]`. The lint pipeline is SELF-HOSTING (it lints slopp's
  own `:clj` source), so `kondo-pass`/`run-kondo`/`lint` keep an arity-1 default
  of `:clj` — and a mid-change arity-2-only `kondo-pass` deadlocked every write
  through the gate (recovered by temporarily marking `slopp.index` `:cljs` to
  skip the gate; frictions #10). (c) An **opt-in recompile-on-write dev loop** —
  `client`/`auto-compile` config on → a client-ns write recompiles the bundle
  (`session/maybe-recompile-client!`, decoupled via `store/late-ref` because
  `slopp.api.cljs → api.external → slopp.api` would cycle) so `--live` serves
  fresh JS. Default off; the common `:jvm` write is a nil no-op.

Deferred, deliberately: running compiled JS in the write-verify loop (browser/
Cypress/Playwright — the eventual app-level layer); cherry/squint backends (the
multimethod is built for them); the npm/JS dependency world (its own later
record); a `:client` effect tier refining D6 for browser code; **typed API
contracts gated + shared with the client** (a schema-per-endpoint gate whose
`.cljc` schema checks front-end usage — the deeper LoB payoff beyond a
hand-shared schema; scoped in `.context/roadmap.md`, user ask 2026-07-23). Supersedes
`ideas/clojurescript-client-code.md`. Wave frictions:
`ideas/logs/cljs-wave-frictions.md`.

**Shipped after the milestone (user ask, 2026-07-23):** (1) the REFACTOR ops
handle `:cljs` forms — every compiling refactor op (`edit_rename`/
`edit_move_forms`/`edit_extract`/`change_signature`/`edit_requalify`/
`rename_sweep`) funnels through `slopp.ops.engine/hot-load-all!`, guarded once
to skip a `form-id` whose ns isn't `jvm-loadable?` (per-form-id, so a multi-ns
move loads the `:jvm`/`:cljc` ids and skips the `:cljs` in one pass; `ns_rename`
and `ns_delete` were already safe). (2) the recompile dev loop is now ASYNC —
`session/maybe-recompile-client!` schedules a single-flight, coalescing daemon
compile (a client write returns `:client-recompiling` immediately; the served
blob updates when the background compile commits; the previous compile's outcome
rides `:client-recompile-prev` on a later write) instead of blocking the write.

## D-web-contracts (2026-07-23) — typed API contracts, gated and shared with the client

The correctness half of the client story: D-web-cljs gave the client CODE; this
gives the client a CONTRACT. The user's framing — a gate requiring a schema on
every web endpoint, that schema `.cljc` so the front-end is checked against the
SAME contract, so a mismatch is a write-time / boundary error, not a runtime
surprise. It extends the existing route-integrity index (which ties a literal
`:href`/`:action` to a route) from ROUTES to DATA SHAPES. Decisions (user calls):

- **Front-end usage is checked by a GENERATED TYPED CLIENT, not a lint.** cljs
  has no static types, so "the FE uses the API correctly" is enforced either at
  runtime (a validating wrapper) or at write-time (a fuzzy static lint). Chosen:
  slopp GENERATES a `.cljs` `fetch` wrapper per endpoint from its
  `:web/request`/`:web/response` schemas — it validates params out and the
  response in, so the FE literally cannot call the endpoint with the wrong shape,
  and the wrapper cannot drift from the server contract (regenerated on change,
  rides the compile step). The client-usage lint was rejected as fuzzy/evadable
  (dynamic payloads/URLs); "generate + lint the bypasses" is a later escalation.
- **The gate REFUSES a schema-less NEW/edited endpoint** (like `:web/auth` is
  mandatory), grandfathering existing endpoints until touched — not
  advisory-first, not refuse-everywhere. Refuse-for-new forces the contract from
  day one without a store-wide up-front sweep; there are ZERO production
  endpoints today, so the only cost was three test endpoints (updated).
- **Named vs inline schemas: both allowed, named is the paved road, an advisory
  guides.** A named `.cljc` malli schema var (`contracts/order`) earns its name
  when the shape is shared / composed / domain vocabulary — one source of truth,
  reusable, and (being a var) it rides slopp's refs graph so rename/blast-radius
  just work, and it is the clean input to the generated client. Inline
  (`^{:web/response [:map …]}`) is better for a genuine one-off. The gate
  requires A schema, not a spelling; a named schema referenced by
  `:web/request`/`:web/response` must live in a `:cljc` ns (so it compiles into
  the client); and a DRY done-advisory flags a structurally-duplicated inline
  schema across endpoints, nudging inline→named exactly when reuse appears.

**Shipped (part 1): the endpoint-schema gate.** `slopp.edit.modules/
web-endpoint-schema` (refuse-grade, `^{:rule/applies-to :production}`, inert
until `web.enabled`, mirroring `web-auth-refusal`): a `:web/path` endpoint with
no `:web/response` — and a BODY method (`:post`/`:put`/`:patch`) with no
`:web/request` — is refused, teaching both the named-var and inline paths.
Registered in `per-form-write-gates` after `web-auth-refusal` (auth refuses
first) and cataloged in `rule-catalog`.

**Shipped (part 2): the generated typed client.** `generate_client`
(`slopp.webdev.cljs/generate-client!`, sibling of `compile_client`) reads every web
endpoint (`edit.modules/web-endpoint-rows`), resolves each `:web/request`/
`:web/response` to a shippable schema, and writes a stored `:cljs` namespace
(default `app.client.api`, `client`/`generated-ns`-configurable) of typed `fetch`
wrappers — one per endpoint, validating params OUT and the response IN against
the SAME malli schema the server enforces (`malli.transform` at the JSON
boundary; path `:segments` interpolated). Load-bearing sub-decisions:

- **Delivery = a stored, edit-PROTECTED namespace, not a blob.** The FE
  `(:require)`s and CALLS the wrappers as cljs fns AND their references
  (`api/create-order!`, and each wrapper's `contracts/order`) must RESOLVE in the
  store to pass the reference / cold-load gates — a blob can't. Being stored also
  makes it INSPECTABLE like any code (query_source, blast-radius): the chain
  schema → endpoint → wrapper → FE call site is connected by REAL references, so
  "edit a schema → every affected client call" falls out of the graph.
- **The `^{:generated "<endpoint>"}` marker does TRIPLE duty:** (a) the
  `generated-ns` write gate (`slopp.edit.modules`, in `per-form-write-gates` +
  `rule-catalog`, runs even for `:cljs` writes since `gate-refusal` is inside the
  rebased-write transform, before the `load?` skip) REFUSES hand-edits, teaching
  regenerate-or-strip-the-marker; (b) inspection exemptions — `unused-report`,
  `missing-doc-warning`, and `review-scan` all skip a generated form (a wrapper
  no FE calls yet is "available", not dead; documented BY the generator; never
  hand-tested); (c) provenance — the marker VALUE is the source endpoint. The
  generator writes via `store/ingest`+`commit-appended!` (BELOW the per-form
  gates), so regeneration overwrites wholesale and is the ONLY writer.
- **Regeneration = EXPLICIT `generate_client` + a staleness ADVISORY** (user,
  2026-07-23). A write to the generated ns couples the generator to the write
  path and risks churn; an explicit step mirrors `compile_client`/`build!`. The
  safety net: `generate_client` records a contract fingerprint
  (`edit.modules/client-signature`, a hash of every endpoint's raw
  method/path/request/response) on `client`/`generated-sig`; the `web-stale-client`
  done-advisory (`slopp.rules/client-stale-check`) compares recorded vs
  current and nudges "run generate_client" on drift — firing ONLY once a client
  has been generated. Composes with the dev loop: the generate WRITE, with
  `client`/`auto-compile` on, triggers the async recompile so the bundle
  refreshes off an explicit generate.
- **`:cljc`-placement check + DRY advisory (both shipped).** A referenced schema
  VAR must live in a `:cljc` ns (so it compiles into the client AND is the same
  one the server validates); `resolve-schema-ref` tags a non-`:cljc` or missing
  var as a `:problem` and SKIPS that wrapper (the ns always compiles). The
  `web-inline-schema-dup` done-advisory flags a structurally-duplicated STRUCTURED
  inline schema across 2+ endpoints, nudging inline→named.
- **Side-fix:** `missing-doc-warning` compared heads with a `'#{defn defmacro}`
  literal — D4 bans `defmacro` even as quoted DATA, so any edit re-froze the form
  (frozen-form trap, `.context/findings-log.md`). Now compares head by
  name-string (`#{"defn" "defmacro"}`), which unfreezes it.
- **Toolchain self-provisioning — TWO dep configs (part-3 dogfood finding,
  user-directed, 2026-07-23).** The dogfood hit it before the app even built: an
  agent had to hand-`deps_add` ClojureScript and malli. Those are slopp's OWN
  plumbing — the compiler it dispatches to, and the schema library its gate +
  GENERATED code mandate (the generated client emits `[malli.core :as m]`, so a
  store without malli produces code that won't compile — a correctness gap, not
  just ergonomics). **A first attempt provisioned them via `deps-add!` and was
  REJECTED by the user for the right reason: that records `:deps-add` DELTAS, so
  slopp's plumbing lands in the USER's manifest** — provenance noise, versions
  frozen per-store at provisioning time (no upgrade path), riding branches/merges
  as user history. The user named the correct model: two separately-managed dep
  configs, ours and theirs. slopp already had it for the image tier
  (`repl/inherent-deps` — nREPL + malli, "never in `deps_list`, never removable,
  versioned centrally"); the gap was that it never reached the BUILD tier, which
  is why D-web-cljs had originally shoved malli into the user manifest as a
  workaround. Fixed at the single materialization point: `external/build!`
  (which `external-test-run!` AND `compile-client!` both go through) merges
  `external/client-build-deps` — malli from `repl/inherent-deps`, the compiler
  from `build/compiler-coord` — into the generated deps.edn, and ONLY when the
  store carries client code, so a non-client build stays byte-identical. No
  deltas, no manifest entries, versions central; the agent adds only APPLICATION
  deps. Generalizable rule: **a dep slopp itself requires is injected, never
  delta-recorded.**

Deferred / next: a dogfood on real endpoints (part 3, held by the user until
part 2 landed); a client-usage lint of hand-written bypasses of the generated
wrappers; auto-declaring the generated ns's cross-module edges to the schema
namespaces (today `store/ingest` bypasses the module gate, so a `done`/
`full_check` module analysis over a generated ns in a real store is the open
question part 3 will surface). Program roadmap: `.context/roadmap.md` (typed API
contracts).

## D-webapp (2026-07-25, RENAMED 2026-08-16) — REST API plus a client-side SPA is the supported architecture

**Renamed from `D-spa`.** `spa` had become a second name for a thing that
already had one: the capability is `webapp`, its config keys are `webapp.*`, its
namespaces are `slopp.webapp*` and all its rules are `webapp-*` (enforced both
directions by `rules-test/a-rule-owned-by-an-app-type-is-named-for-it`). Two
words for one concept is the drift this log exists to prevent, and the decision
name was the oldest of them. Swept in the same pass: `:web/spa` →
`:web/client-routes`, the crossing kinds `:spa/*` → `:webapp/*`,
`web.routes/spa-rows` → `client-route-rows`, and the advisory
`webapp-spa-consequences` → `webapp-client-routes-consequences`.

*SPA* survives in PROSE below, where it is the industry term for the
architectural style rather than a name for anything slopp ships. That
distinction is the whole rule: name our things once, and use English for
everything else.

The question the user asked behind a UI request: what shape of web application
does slopp support as a NON-TRIVIAL app grows? Answer, a user call: **a REST
API with declared contracts, consumed by a client-side SPA.** Server-rendered
HTML and static content stay fully supported; they stop being the assumption.

The user's argument, and the reason it is the right one: SPA is not about
"fatness", it is about responsiveness and dynamic behaviour that a
server-rendered page cannot reach. And the organising property for an AGENT
rather than a human: **the API is an explicit, versioned, independently
testable boundary.** One place to ask what the app can do (`query_routes`),
each endpoint testable as `=` on data, and a frontend consuming a GENERATED
contract instead of sharing implicit server internals. That is what lets an
agent keep reasoning about an app as it grows past the size where it can hold
the whole thing at once.

**This is not a new bet — it is the best-built ground slopp already has.**
D-web-contracts shipped the contracts, the generated typed client and the
`web-stale-client` advisory; D-web-cljs shipped one store, one dialect, compiled on
the JVM. Declaring SPA the supported architecture mostly names what those
already imply and closes the gaps around them.

### The rule that keeps the frontend verifiable: `:cljc` except the DOM

A `:cljs` namespace cannot load into the JVM oracle; a `:cljc` one can
(`store/jvm-loadable?`, `image/load-ns!`). That single fact sets the
discipline:

> **Views, state transitions and formatting are `:cljc` pure functions. Only
> mounting and event binding are `:cljs`.**

Then a view is an ORDINARY in-image test — ~0.5 ms, against the ~368 ms
anything in the external tier costs — asserting on returned hiccup DATA. No
browser, no headless Chrome, no cljs test runner, no new test tier. This
generalises a pattern that was already working locally:
`slopp.ui.client.nsfilter` and `.nsschema` are `:cljc` with `-test` siblings
while `.nsview` is `:cljs` glue.

**The check on the whole discipline: if UI tests land in the external tier,
the split is wrong.** That is the observable, and it is why the rule is stated
as a tier property rather than a style preference.

### Renderer: Replicant, chosen for testability rather than taste

A Replicant view is `(fn [state] hiccup)` — data in, data out — so it is
trivially `:cljc` and `=`-testable on the JVM, and the same views render to
strings on the JVM, which keeps SSR and static available without a second code
path. It has zero dependencies. A React component (Reagent/UIx) returns a
component object bound to the React runtime, which is exactly what pushes its
tests into a JS runtime and out of the cheap tier.

**Honest cost, recorded rather than glossed:** no component ecosystem. An app
needing a heavyweight third-party React component (a data grid, a charting
library) is not served by this road, and the right answer then is to say so.
Reagent/UIx stay usable at the cost of the in-image test property.

### Rejected, with reasons

- **HTML-over-the-wire** — Ripley, LiveView/Blazor-style, htmx. They blur the
  API boundary this decision is organised around: the server computes
  presentation and there is no contract to point at. Websockets remain
  addable later for push WITHOUT changing the model — a socket carrying JSON
  events is not a socket carrying markup.
- **Electric Clojure.** NOT because of macros: an earlier draft of this
  argument claimed Electric forces app authors to write custom macros, and
  that was simply wrong — authors use ITS macros (`e/defn`, `e/server`,
  `e/client`) and slopp handles library macros routinely (garden's `at-media`
  sits in `ui.pages/store-stylesheet` today). The real objection is that
  `e/defn` does not expand to Clojure. It is compiled by Electric's own
  compiler, which INFERS where each expression runs — and that is precisely
  the decision slopp's purity tiers and gates exist to make. Both cannot hold
  it. It also puts views outside the in-image test property, makes
  `query_macroexpand` stop teaching, and adds a second hot-reload path.
  Currently `v3-alpha-SNAPSHOT`. Reasonable as a deliberate opt-out for one
  app; wrong as the road.
- **Any `app-shell`/nav primitive in `slopp.web`** (user constraint, explicit).
  A framework carrying an opinion about navigation has stopped being a
  framework. The three-pane shell lives in `slopp.ui`, the application.

### The correction this is built on

The first instinct recorded here was to argue AGAINST a client framework
because slopp's gates can see server-rendered hiccup and cannot see React.
That is backwards and is rejected: **capability first, then make it
verifiable.** Choosing an architecture by what the current gates happen to
inspect is how a tool's limitations become a product's ceiling.

### Shipped so far

- **SPA deep-link fallback** — a DECLARED fallback for a path prefix
  (`:web/client-routes`, `slopp.web.routes/client-route-rows`, ordered exact → static → fallback)
  so a refreshed client route serves the app document. Declared rather than a
  catch-all precisely so the gates still read it and a genuinely missing path
  still 404s — a fallback that swallows 404s is worse than none.
- **The bundle served without ceremony**, at `/js/main.js` with `:web/raw`,
  and `api.cljs/served-by-a-mount?` so a compiled bundle nothing serves is a
  finding rather than a 404 discovered in a browser.
- **`url-attrs` extended to `:src`**, which immediately caught a real shipped
  bug: `/assets/cljs/main.js` had 404'd on every page since the wave that
  added it, behind a 200 for the page itself.
- **The three-pane reviewer UI** on `slopp.ui.views` (`:cljc`, `:pure`) as the
  first dogfood of the `:cljc` rule.
- **The `/api/*` surface and the first real consumer of `generate_client`.**
  `slopp.ui.contracts` (`:cljc`, `:pure`) holds named malli schema vars;
  `slopp.ui.api` (`:pure`) declares `GET /api/namespaces` and
  `GET /api/ns/:ns` against them, REUSING the page's `:web/read` performers
  (reads resolve by vocabulary, so the JSON and the HTML cannot disagree —
  one answer, two representations). `generate_client` emits
  `slopp.ui.client.api`, and `slopp.ui.client.nsview` calls it: a left-nav
  click fetches the outline and re-renders `views/ns-outline-main`, the SAME
  `:cljc` view the server renders into the same pane. Renderer: Replicant.
- **Endpoint tests validate the response against the schema var the client is
  generated from**, so a handler drifting from its own contract is a red in
  the in-image tier rather than a runtime surprise in a browser.

### Wave 4 (2026-08-16/17): the LOOP became the framework's, not the app's

Everything above made a browser app *verifiable*. Wave 4 took the parts every
browser app was writing identically and made them declarations. The measure is
`query_surface`'s `:webapp/cljs` count, and the target is zero.

Four decisions settled here, each replacing a shape an app had to write:

- **Routes are a declared TABLE, and a routing function is refused.** A
  function answers only when called, with a path, at runtime — so nothing can
  list an app's screens, join a link to one, or compare the client's routes to
  the paths the server answers for. Every report and gate that exists for
  `^{:web/path}` was impossible on the client side for exactly that reason, and
  the two `:checked-by nil` rows in `index.crossings` said so.

  The table is **ADDRESSES, not screens** — one screen answers at several URLs
  the moment an app has a lens bar or a print view, so anything counting one
  against the other is wrong. Learned from the consuming app, which had a test
  asserting a literal `11` that ratcheted to `14`.

- **The view is DERIVED and declaring one is refused.** A hand-written view is
  what made `:screen` a keyword agreeing in three places — the router returned
  it, the view cased on it, the fetch received it, with nothing checking any of
  the three, so a rename produced a blank pane at a URL that looked right.

  The framework also renders the three states a screen cannot render against
  (`not-found`, `loading`, `failed`) and the app's `chrome` PLACES two of them.
  The line is structural, and the first version of it was wrong: "slopp owns
  what is true, the app owns what is seen" does not survive `not-found`, which
  is equally presentation and which slopp defaults. What holds is that
  not-found replaces the WHOLE page while loading and failed replace one pane
  — so rendering those requires knowing where they go, and placement is layout.

- **A screen is a VALUE that names its own request.** `{:render :request
  :derive}`, with a bare `(fn [state] hiccup)` as the shorthand for a screen
  with no data. `:request` is pure and `:cljc`, so WHICH call a URL makes is a
  fact an in-image test reads rather than a `js/fetch` in a namespace whose
  only verification is that it compiled.

  This retired `:webapp/fetch` and `:webapp/derive`, which were app-supplied
  and received the SCREEN — the same three-place agreement the route table
  removed, one seam along.

- **The performer is slopp's, and the browser decides nothing.** The shim may
  not branch (`ops.selfcheck-test`), so the URL, the method and headers, which
  encoder a body wants, which decoder an answer wants, and whether a response
  is data or a failure are all `:cljc` functions with ordinary tests. What is
  left in `slopp.webapp.dom` is one `fetch` and three lookups.

  The security half is in `request-url`: substitution is segment-wise rather
  than `str/replace` (a parameter whose name prefixes another corrupts the
  path, and the corrupted result looks like a URL), and every value is
  percent-encoded, so a value cannot break out of its segment.

**`fetch` rejects only on a NETWORK error**, so a 500 resolves and a performer
that hands every resolved response to its success callback renders the error
page's body as data. `response-outcome` is that check, and the same defect was
then found in `generate_client`'s emitted wrappers — where it was worse:
contract validation exists to detect DRIFT, so a transport failure wearing its
clothes makes a real drift and a 502 produce identical words.

**Constraint on where a breaking change may live**, named by the consumer:
every `webapp` retirement refuses inside `wiring`, which is a function an app
CALLS. So a store on the new jar with the old keys goes red with every message
naming the fix, and still LOADS — the repair tools keep working. Compare
`screen/open` → `open!`, which wedged this store, because the write that would
have repaired the namespace was verified against that namespace's own broken
state. A runtime refusal is recoverable in band; a load-time break is not.

### What the dogfood immediately found

Building on the model is what surfaced these; all four are fixed, and the
first is the one worth remembering:

- **`web/context` accepted a namespace list it could not perform the reads
  of.** Because reads resolve by VOCABULARY store-wide, an endpoint reusing
  another namespace's performer is correct and encouraged — which means a
  half-complete `:web/namespaces` assembles fine and answers **500, not 404**,
  at request time. `context` now computes the set difference at assembly and
  refuses, naming each unservable kind with the route that declared it.
  Generalizable: *when resolution is by NAME across a scope the caller
  assembles by hand, the assembly step is where it must be checked.*
- **The generated client appended a second `!`** to endpoints already named
  with one — the dialect's own convention for an effectful fn. `call-endpoint!`
  generated `call-endpoint!!`.
- **Transport endpoints were wrapped by default.** `/mcp`, `/call` and
  `/metrics` would have shipped as typed browser `fetch` wrappers; they now
  declare `:web/client false` and carry honest contracts.
- **`compile_client`'s `:serve-with` hint claimed nothing served the bundle**
  while `/js/main.js` was returning it with a 200 — it only ever asked about
  static mounts, and an endpoint that reads the file serves it too. Fixed by
  making the message state the question it asked rather than a conclusion it
  had not earned (D-surface-honesty).

~~Deferred: extending client-route integrity to the client-side route table;
`:web/client-routes` is built but nothing declares it, because the reviewer UI
has real server routes for every client route and is progressively enhanced
rather than a hard SPA.~~

**DONE and the reason had stopped holding — both clauses, measured 2026-08-17
by the consuming app** (`hub/project-root` declares three
`:web/client-routes` prefixes; 9 server routes against 14 client addresses;
their `spa` and `nav` namespaces deleted). So it is a hard SPA on this loop,
there is no longer a server route per client route, and something does declare
the marker. The WORK shipped separately in wave 4 —
`webapp-client-routes-are-served` joins the client table to the prefixes the
document answers for, and `webapp-request-paths-are-served` joins a screen's
request to the endpoints the store serves.

**The entry did not notice any of that**, which is the failure mode this log
should be watched for: a deferral records a blocker AND a reason, the blocker
clears, and nothing re-reads the entry. Their words for the same thing on their
side, an hour earlier: *a queue entry inherits the blocker of the item above it
unless someone re-checks.* Same shape here — a stale reason reads as a live one,
and it is the sentence a later reader trusts.

Remaining frictions: `ideas/logs/spa-wave-frictions.md` for this wave's own,
`ideas/logs/webapp-wave-frictions.md` for wave 4's.

## D-module-view (2026-07-26) — the Code screen is a module map, and the diagram is DATA

The Code section opened on a flat alphabetical list of every namespace — on
this store 186 rows, **103 of them tests**. That reads as generated API docs,
not as a code explorer, and it contradicts slopp's own high-to-low stance:
modules → namespaces → forms. User ask. What was settled:

### The picture is computed here and rendered as hiccup SVG

**Rejected: Excalidraw-the-library**, after measuring it. 0.18 removed UMD
(ESM-only, React 19 peer, and React 19 removed UMD too); `exportToSvg` from the
main package is NOT React-free — it re-exports from a chunk with a top-level
`import … from "react"`; and `@excalidraw/utils`, which genuinely is React-free,
is **19.6 MB raw / 14 MB gzipped** because it inlines 230 base64 WOFF2 font
subsets. Decisive on its own: **Excalidraw does no layout at all** — every
x/y/w/h is supplied by the caller, which is why
`@excalidraw/mermaid-to-excalidraw` exists (Mermaid computes positions via
dagre/ELK and hands Excalidraw finished coordinates).

**Rejected: mermaid.** It is a document-embedding tool: a string in, opaque SVG
out. That forfeits the three properties D-webapp is built on — views as `:cljc`
pure fns `=`-testable in-image at ~0.5 ms, Replicant owning its subtree, and
interaction as data. Hover-to-dim or click-to-select would mean mutating a
foreign library's output DOM inside a Replicant tree.

**Chosen:** `slopp.ui.graph` (`:cljc`, `:pure`) computes the geometry;
`slopp.ui.views/module-graph` emits SVG as hiccup. The seam is a data
structure, so nodes are real elements carrying `data-module`, styling is class
names the stylesheet owns, and the whole diagram is an ordinary in-image test
instead of a screenshot. The hand-drawn look, if wanted, is **rough.js** —
27.7 KB raw / 8.9 KB gzip, a plain IIFE setting `window.rough`, no DOM needed
for the generator, deterministic given a nonzero seed (MINSTD). It is also
what Excalidraw itself uses; the `seed` field on every Excalidraw element
exists to seed it.

### Substrate banding: the honest way to draw fewer edges

Auto-layout draws every edge, and 33 arrows over 14 boxes is busy no matter how
good the router is. `slopp.ui.graph/substrate` computes a FOUNDATION band —
sinks that at least two modules depend on, plus one level of hub promotion
(fan-in ≥ a quarter of the graph, all of whose own deps are banded sinks). On
this store that is `boot`/`cache`/`web`/`store`, and it removes 16 of 33 edges.

Two calibration decisions, both measured against this store and both load-bearing
for a graph that is NOT this store:

- **Promotion is one level deep.** Unbounded, it cascades through `store` into
  `git` and `image`, which are components by any reading.
- **A sink with ONE dependent is not foundation.** `rt` stays an ordinary node
  because its single edge from `image` is the informative kind; banding it
  would move it away from its only consumer.

A graph with no sinks yields the empty set and every edge is drawn. **That
degradation is the point** — this view ships to stores that are cyclic and
lopsided, and "there is no foundation to name" is the honest picture. Cycles
are surfaced as a CALLOUT above the diagram, not left to be spotted in it: on a
tangled store that is the most useful sentence on the screen.

### Layout is hand-rolled, with a stated trigger to stop

`store/module-layers` supplies SCC-condensed topological layers, so only
within-layer ordering and coordinates are written here. **Recorded honestly:
there is NO crossing reduction, and edge routing is a bow heuristic** (a
sideways offset scaled by layer span) rather than dummy-node routing. Both are
invisible on this store because no layer exceeds two nodes — the diagram looking
good here is evidence slopp's architecture is tidy, NOT that the layout is.

**The trigger:** point it at a genuinely messy store (many modules, wide layers,
cycles). If it is a hairball, adopt ELK rather than growing the heuristic. The
seam makes that contained — positions are data, so only the geometry stage
changes, not `substrate`, the view, the contract, the export, or any test.
Costs, already measured: elkjs is client-side and async, which loses the
in-image property; Eclipse ELK on the JVM keeps it but drags EMF + Guava onto
the KERNEL classpath (every `:jvm`/`:cljc` store ns must resolve there) and
forces a jar rebuild.

### Tests are counted, never listed — and the count is by REACH

Test namespaces are gone from the nav. They fold into their subject's module,
so listing them puts two things at one rung that are not peers. They resurface
on the namespace page as `:tested-by`, linked, with an explicit "no tests"
rather than silence — silence reads the same as a page that does not show
coverage.

**The count is by reach, not by folding**, and the bug that forced it is worth
keeping: folding reported `slopp.git` as having NO tests, because its three test
namespaces are top-level (`slopp.git-projection-test`) and fold into modules of
their own. A zero is meant to be a finding; one wrong zero devalues every zero
on the screen. Direct requires only — an unbounded closure on a real store
reaches most of the suite and distinguishes nothing, the same reason
`covered-by` bounds its static reach.

### Export is SVG, not `.excalidraw`

Getting the diagram OUT is a real goal (user). `GET /api/modules.svg` renders
the SAME view function the screen does — a second drawing routine is how the
two quietly diverge — and INLINES the stylesheet, because an SVG carrying class
names and no CSS opens as black rectangles, which reads as a broken export.

`.excalidraw` was considered and dropped. Its advantage over SVG is real objects
with bound arrows that stay attached when a node moves — but bindings are the
fiddliest part of the schema (`startBinding`/`endBinding` with `focus`/`gap`,
and master has already migrated to `mode: "inside"|"orbit"` + `fixedPoint` while
published 0.18.1 has not), so the version we would realistically ship is boxes
plus UNBOUND arrows: barely better than SVG for editing. That would mean
tracking a 25-field element schema we do not consume, with no library to run a
contract suite against — the unverified-fake problem, volunteered for.

### Still open

The external-JS dependency capability (`js_dep`), which D-web-cljs already
defers as "the npm/JS dependency world (its own later record)". Scoped in
`ideas/product/external-js-dependencies.md`.

## D-comments-are-content (2026-07-27) — whitespace is rendering; the form is the only unit

Came out of asking why a milestone needs a byte-exact tree snapshot, which
was the wrong question. The right one is why slopp stored whitespace at all.

### Byte-exactness was never a real requirement

`byte-exact` appeared in three places in the store and they were all one
decision: `commit-point!`'s tree snapshot. The reasoning was that a git sha
hashes bytes, so the projection must be a pure function of the marker delta.
True, and irrelevant — **`git_map` already records each milestone's sha.** You
do not recompute an answer you wrote down.

Settled with the user: **git is an EXTERNAL INTERFACE between slopp repos, not
something slopp reproduces.** Push a milestone, record its sha, use it as the
parent next time; pull and map incoming shas back onto slopp commits. Nothing
needs slopp to re-derive a historic tree. The only surviving requirement is
that rendering be *stable* — same store, same output — which is the contract
`gofmt` has, and slopp already normalizes forms on write, so it already did
not preserve incoming formatting.

### So: an element is a form

A namespace was an ordered vector of `:form` (addressable, has an id) and
`:sep` (whitespace, blank lines, comments — positional, idless). Lumping
whitespace and comments together as "trivia" is what forced byte
preservation: a comment stored positionally is content the delta log never
recorded, so the only way to keep it across time was to snapshot bytes.

Split them and the requirement dissolves:

- **Whitespace between forms is RENDERING.** `render-ns` joins forms with one
  blank line. Never stored, never diffed, never merged.
- **Comments are CONTENT**, owned by the form they describe, carried in that
  form's delta (`:comment` op, `:comments` on `:ingest`) like anything else.
- **`#_` discards fold like comments.** A discard is code; dropping it
  silently would be the one genuinely bad outcome.

Nothing in a namespace is then outside the journal, which makes a commit point
DERIVABLE and the snapshot jobless.

### What it cost and what it bought

Measured on this store: 4,509 element rows of which **2,300 were seps**, 97%
of them the literal `"\n\n"`; all sep bytes 7,780; 67 rows carried a comment,
3,893 bytes. **82 MB of tree snapshots existed to preserve 7.8 KB of
information that was not in the journal.** Normalizing every namespace to one
rule cost **+345 bytes** across all 191 — `place-form` had been giving a
tail-appended form a single newline, so most forms rendered jammed together
(`slopp.ops.engine` alone: 33 single-newline separators against 11
blank-line ones).

Deleted: `edit_trivia` and the `:trivia` op (superseding E4's trivia half),
`store/replace-trivia`, `place-form`'s whitespace juggling, the trailing-sep
handling in `remove-form`/`move-form`/`:delete` replay, and every sep row.
Still to delete (`ideas/whitespace-is-rendering.md` step 4): the tree
snapshot, the `tree-diff`/`tree-apply`/`tree-at` chain built the day before
this, `backfill-tree`, and the `tree` column.

### The rendering rule now has FOUR implementations

`store.render/render-ns` (reference), `store.db/rendered-sources` (the git wip
ref), `slopp.kernel.boot/store-sources` (the kernel — architecturally forbidden to
call the others, and the module gate enforces it), and `render/element-offsets`
which must SIMULATE the rendering to map clj-kondo positions back to forms.
Three of the four were found disagreeing, none by reasoning and all by a red
suite; `element-offsets` fails SILENTLY, returning clean `change_signature`
and `rename` plans with no call sites in them. Change one, check all four.

### Open

Trailing content at the end of a namespace has no form to own it. It is kept
as an inert `:sep` rather than destroyed, and is not rendered; a namespace-level
`:comment` field is the suggested home. Zero cases in this store. Blank-line
grouping *within* a namespace is deliberately lost.

### Step 4 (2026-07-27) — the snapshot goes, and the journal has to earn it

Deleting the tree snapshot was not a deletion. `project-journal!` needs a tree
per milestone, and the only honest replacement is to DERIVE it — which means
the journal has to actually be a complete account rather than be described as
one. Two things had to become true first.

**`replay-delta` is TOTAL.** Six ops returned nil, meaning "reload from the
elements table": `:ingest`, `:move`, `:rename-ns`, `:move-forms`,
`:extract-ns`, `:module-extract` — 861 deltas here. A nil used to cost a slow
reload; it now costs a milestone whose bytes cannot be reconstructed. All six
already carried what they needed. Four are `apply-changeset`, which rewrites
nodes BY FORM-ID wherever they live, so they collapse into the `:replace`
case — the relocation people assume they carry rides separate
`:add`/`:delete`/`:ingest` deltas. Note the trap: a refactor's delta names the
SOURCE namespace, so inferring "these forms now live in `:ns`" from the shape
gives a plausible, wrong replay. Read the writer.

**`project-journal!` folds the log as it walks**, one pass — per-marker folding
is quadratic in the journal. A marker normally targets the delta before it,
which is where the fold stands on arrival; a retroactive `:target` is rendered
as the walk passes it and HELD. A held tree must survive its first reader:
a milestone's own target is the delta before it, which is what an earlier
retroactive marker also points at, and releasing it there left the retroactive
commit projecting the current state instead.

**Result:** 87,154,688 bytes reclaimed, `store.db` 230 MB → 143 MB (537 MB at
the start of this arc). Gone: `commit-point!`'s capture, `db/tree-diff` /
`tree-apply` / `tree-at` / `delta-tree` / `max-tree-chain`, `git/backfill-tree`
and its lossy-reconstruction problem, the `tree` column, `:tree-bytes`.

**Accepted consequence:** historic milestones re-render, so their shas change
(the trial projection moved the local tip from `9880a5c` to `30b60f0`). That is
the settled trade — `git_map` records what was already pushed, and git is an
interface between slopp repos rather than something slopp reproduces. The next
push rewrites that branch's history once.

**The method, which is the part worth keeping.** The 272 stored trees were an
independent record of the same thing the fold computes — usable exactly once,
before deletion. Checking against all of them (not a sample) found what the
whole green suite could not: **zero `:comment` deltas existed in the entire
journal**, because `fold-comments` migrates at LOAD and a load writes nothing.
All 30 comments lived only in the elements table. Deleting the trees at that
moment would have silently dropped every comment from every future git
projection — the precise failure this design was supposed to make impossible.
Run the check that can only be run once BEFORE you delete what makes it
possible, and run it over the whole population: a sample would have found the
renderer drift and missed this entirely.

## D-hub (2026-07-27, user decision) — one UI process per machine; each project serves its own data

`slopp.mcp/-main` autostarted the reviewer UI on `slopp.api.server/derived-port`, a capability
whose default is the fixed 7359. That works for exactly one project. Run two
MCP servers on a machine and the second one's UI reports `port 7359 is not
available` — and the fix that suggests itself, giving each project its own
port, trades a conflict for a worse problem: an address nobody can guess.
The user named both halves: *"they will either conflict ports, or have
random ports and so hard to know where to look."*

### The hub cannot own the data plane, and that is not a preference

`ui.server/serve!` takes the session as an ARGUMENT.

**Corrected 2026-07-27.** This section originally said `:test-map` and
`:observed` were "session-grain and never persisted", so a fresh session would
render every form as covered by nothing. That is FALSE:
`session/persist-trace!` writes the trace map to store meta at every verified
run and `open!` loads it back, with `load-observations` the same for
`:observed`. A fresh session is not blank.

The conclusion is unchanged, and the true reason is sharper. A fresh session is
**behind**: it shows the warranty as of the last verified run, not the one
being changed, which is wrong exactly when someone is watching it change. And
being behind is not free — it costs a second booted image, that project's
classpath, and that project's version. A hub fronting N projects would need N
of each. Whatever else the hub is, it is not the thing that answers questions
about a store.

Recorded rather than quietly patched because a decision defended by a reason
that does not hold is a decision someone will undo the moment they check.

**The rule: the hub knows only what a heartbeat tells it; everything else it
proxies.** It never opens a db and never renders a form. The consequence
worth having: the hub is version-DUMB, so the UI code ships with each
project's jar and a hub from another release still works.

### The split

- **Hub** — `slopp.ui.hub`, started by a human: `slopp --main
  slopp.ui.hub/-main --port 7359`. No kernel change is needed; `boot/parse-args`
  already trampolines any store CLI, the jar bundles slopp's own source, and a
  CWD with no store simply has nothing extra to load, so the hub runs from
  anywhere. It owns the registry, the project picker, the top-nav dropdown,
  and a reverse proxy at `/p/<slug>/*`.
- **Project** — every MCP process keeps its own listener and serves ALL of it:
  pages, `/api/*`, the client bundle, over the LIVE session exactly as today.
  Only its address changes.

### Ports: derived, because a conflict should be impossible rather than configured away

The project listener binds a port DERIVED from the store dir, falling back to
an ephemeral one when taken. This is `git.server/derived-port` +
`bind-localhost!` verbatim, already proven for the git remote: stable across
restarts, "a preference, not a guarantee", and the ACTUAL bound port is what
gets reported. Nobody needs to know it — the hub is the address you remember.

**What stops autostarting is the thing on the well-known port.** 7359 becomes
the hub's, and the hub is user-started. The per-project listener still comes
up with the MCP, because the alternative — up only on `ui_serve` — means the
dropdown can only list projects someone already activated by hand, which is
the zero-ceremony property the autostart existed to buy.

### Registration IS the heartbeat

One idempotent `POST /api/register` every 10s carrying
`{:name :dir :url :pid :version :started-at :status}`. Not "register once,
then keep alive": the same call for both, because that single choice buys
three things a register/heartbeat split does not. The hub may be started
AFTER the projects. The hub may be RESTARTED without bouncing every MCP. And
there is one code path to get right instead of two. Recovery time is one
interval.

The hub marks an entry stale at 3 missed beats (30s) and **greys it in the
dropdown rather than dropping it** — a project you were just looking at
should not vanish from the list when its editor closes. Clean shutdown
deregisters for the fast path. The registry is in-memory only, so a hub
restart forgets genuinely dead projects and heartbeats repopulate the live
ones; nothing needs pruning.

`:status` is the seam for "working / idle" later. A heartbeat is already a
periodic push from the process that knows, and the hub renders the string
without interpreting it — which is what keeps the hub version-dumb as slopp
grows things to report.

### Reaching a project: proxy, not redirect

`/p/<slug>/*` forwards to the registered project's loopback URL. One origin,
so no CORS and no ephemeral port in the address bar; URLs stay bookmarkable
across a derived-port fallback; and an unavailable project is a RENDERED
PAGE rather than a failed fetch in a console. `web.router` already ranks
trailing splats below static segments and single captures, so adding the
catch-all steals no route, and `java.net.http` is already in production use
(`web.auth/fetch-jwks!`).

The cost, accepted: the cljs client needs a base path, injected by
`ui.pages/app-document` rather than assumed to be `/`.

The slug is the store's `app.name` when set, else the dir basename,
disambiguated by a short dir hash when two projects collide. The registry is
keyed on the canonical dir, which is the identity; the slug is only an
address.

### Config

- `ui.port` keeps its name and changes meaning: the port THIS project's own
  listener binds. Default becomes derived-from-dir; an explicit value is an
  override for someone who wants a fixed address.
- `slopp.hub.port` (new, default 7359): the hub this project registers with.
  `0` disables registration — a project that wants no hub simply doesn't beat.

### What this revises

The `start-ui!` bargain recorded in its own docstring: *"the UI dies with the
server by design … starting it here is the other half of that bargain."* The
accuracy half is UNCHANGED and is in fact the reason for this whole shape —
the UI still serves the live session and still dies with it. What is revised
is only the address: it stops being a fixed well-known port that a second
project cannot have.

### Part 2 (2026-07-27) — a slopp app can be served under a path prefix

Part 1 shipped a hub whose links reached a page that did not boot: the
project's document and its generated client emit ROOT-ABSOLUTE urls, so
behind `/p/<slug>/` every one of them resolved at the hub. The defect is
general — no slopp app could be served under a prefix — and the hub was
merely the first thing to notice.

- **`slopp.ui.basepath`** (`:cljc`, `:pure`) holds the whole rule:
  `normalize`, `prefixed`, `strip`. Both ends need it — the server builds
  urls, the browser takes them apart — and a second implementation is how
  the two quietly disagree. Pure, so the BROWSER half is covered by in-image
  tests. The match is on a segment boundary: `starts-with?` would hand
  `/p/slopp22/x` to `/p/slopp2`'s router.
- **The prefix travels per REQUEST**, as `X-Slopp-Base` set by the proxy, not
  as configuration. The same server answers directly on its own port AND
  through a hub, so a configured value would be wrong for one of them.
- **The generated client gained an exported `base`** (`slopp.webdev.cljs`) that
  every wrapper's path routes through. Default `""` is exactly what every
  existing app already emitted. This is a general capability — any slopp app
  behind a reverse proxy wants it — not a hub special case.
- **`data-base` appears only when there IS a prefix**, so an app at the root
  renders the document it always did, byte for byte.
- **`views/project-switcher`** is the in-project dropdown. Absent when there
  is no hub (the fetch 404s), which is the single-project case rather than a
  failure; a silent project stays listed and labelled. Its fetch is the one
  that deliberately does NOT carry the base — it addresses the hub at the
  origin root, because `/p/<slug>/api/projects` would proxy back to a project
  that does not serve it. Switching is a full page load: the target is a
  different application in a different process.

**What only the socket test could find.** `ui.hub/forward` already bound a
local named `base` to the project's own url, so the new parameter was
shadowed and every project was told it was mounted at
`http://127.0.0.1:<port>`. Every in-image test passed. The wire test asserted
what the project ACTUALLY received and failed immediately — the same lesson as
the served-namespaces list: a value that crosses a process boundary has to be
checked on the far side of it.

Open, and cosmetic only: `ideas/hub-styling.md`.

### D-hub part 3 (2026-07-27, user decision) — the UI becomes its own slopp project, and talks HTTP

Part 1 made the hub a separate PROCESS; its code still lived in slopp's store.
That left one store holding two applications, which is what made the
route-collision gate refuse `ui.hub/picker` claiming `:get /` — right for one
app, wrong for two — and made the dev process stand in for a deployment that
will never exist. It also meant the UI was not a dogfood at all: it reached
straight into `slopp.read.query`, `slopp.index.refs` and `slopp.store`, which no
user's app can do.

**Decided:** the reviewer UI moves to its OWN slopp project (`../slopp-ui`,
separate repo, separate store, separate `--live` session). It never opens a
store; it calls project APIs over HTTP. **Two real projects, not a
"subproject" concept** — a concept invented for one consumer would re-fake the
thing the split exists to stop faking. **SPA only**: the server-rendered no-JS
path goes away rather than being maintained as a second way everything works.
Versioning and packaging are explicitly DEFERRED (one person controls both
sides and can restart at will).

**The API shape is published as malli forms over EDN, NOT OpenAPI.** malli →
JSON Schema is the supported direction, so an OpenAPI view stays derivable from
an EDN source of truth; leading with OpenAPI would force a lossy JSON-Schema →
malli importer into the critical path permanently. EDN also preserves what a
malli schema is made of — JSON renders `:string` and `"string"` identically.

**Two measurements reshaped the design mid-flight, and both are worth keeping:**

- **`slopp.web` cannot read the store.** No `slopp.web.*` namespace requires
  anything outside `slopp.web.*`; that self-containment is exactly what lets it
  ship as the slim jar. So the publisher is a pure derivation over route rows,
  and it ships — any slopp-web app can publish its own contract. The rejected
  alternative (a store-aware publisher that preserved schema NAMES) would have
  made publishing a privilege of slopp-the-tool, which is the insider shape
  this split exists to remove.
- **Var metadata carries the EVALUATED schema.** `(:web/response (meta
  #'slopp.ui.api/timeline))` is a vector `=` to `slopp.ui.contracts/timeline`,
  already flattened. The author's schema names never exist at runtime, so a
  consumer names schemas from ENDPOINTS (`timeline-response`) and a shared
  schema arrives inlined at each use. Names are a source-level convenience the
  wire never had.

`generate_client {from <url>}` writes TWO generated namespaces in the consuming
store: a `:cljc` contracts ns of the published schemas, and the `:cljs` client
pointing at it. `render-client-ns` and `render-wrapper` needed NO changes —
`resolve-schema-ref` already emitted `{:kind :var}` refs, so the remote path
renders through the existing generator.

**The proof this rests on** is a fixed point, not an assertion: a store that has
never seen `slopp.ui.contracts` generates, from HTTP alone, a client with the
same wrapper names and schemas equal value-for-value to the vars the server
validates its own responses against (`slopp.ui.api-test`, `^:external`).

**What only the socket test could find, again.** `serve!` initially did not
thread its served-namespace list into `:web/perform-ctx`, so `/api/contracts`
answered **200 with zero endpoints** — a consumer would have generated an empty
client and nothing would have looked broken. In-image tests cannot catch this:
they build `perform-ctx` themselves and pass either way. Same lesson as part
2's `base` shadowing, now twice: **a value that crosses a process boundary has
to be checked on the far side of it.**

Consequence to accept: once the split lands, a project's own port serves JSON
only, so "start the MCP, open the URL" stops working without the UI process.
`X-Slopp-Base` also stops being load-bearing for the hub path (the UI server
knows its own base natively); it remains valid as a general reverse-proxy
capability.

Frictions found while building: `ideas/logs/ui-split-frictions.md` — notably that a
`def` computed from another form stays STALE after an in-image edit, which made
a new tool parameter silently unusable in the session that added it.

### D-hub part 4 — as built (2026-07-28)

The split is standing and verified across two processes and two stores.
`slopp-ui` is its own git repo, its own `.slopp/store.db`, and depends on
`io.github.nvoxland/slopp-web` + malli + replicant. It never opens a store.

**The topology landed differently from the plan, and better.** The plan had
each project serving its own document and the hub proxying it. What was built
has **the hub serving every page** — picker, the application document per
project, the stylesheet, the compiled bundle — and proxying only
`/p/:slug/api/*path`. Three consequences:

- **`X-Slopp-Base` is gone from the hub.** It existed so a project could emit
  urls that resolved at its mount point; with the document rendered in the
  process that OWNS the mount point, nothing downstream emits a url and the
  question does not arise. The header remains a valid `slopp.web` capability
  for a proxy that genuinely fronts someone else's pages.
- **Asset urls are absolute.** One stylesheet and one bundle for every project
  fronted, at `/css/style.css` and `/assets/cljs/main.js`, because the hub is
  always at the root. Pinned by `hub-test/the-documents-asset-urls-are-absolute-and-stay-that-way`.
- **`:web/client-routes` prefixes carry the slug** — `["/p/:slug/store" "/p/:slug/change"]`.
  `client-route-rows` concatenates onto the declared prefix and the router reads the
  capture like any other, so a per-project fallback needed no new matching
  rule. Scoped, not a root catch-all: `/p/slopp2/nonsense` still 404s.

**The fixed point, live.** slopp2 publishes malli-EDN at `/api/contracts`;
slopp-ui generated `client.contracts` + `client.api` from that URL; and what
slopp2 actually sends for timeline / modules / namespaces / ns-outline
validates against those generated schemas, fetched through the hub's proxy.
The wire format is lossless in practice, not only in a unit test.

**Two bugs the split found in slopp itself**, both fixed with tests, both the
same genre — the documentation was the trap:

- A static mount could not serve `compile_client`'s output. The bundle is an
  ARTIFACT (sha + recipe; inlining it cost 30MB of delta log) and every mount
  consumer went through `store/file-content`, which read `:files` only — so
  the tool's own "add an `web.static.*` mount" advice was impossible to
  follow. `file-content` now answers from the manifest first, then artifacts.
- The capability registry's example was `web.static./assets = public/`, and
  both the handler and the check append their own separator. `public//app.css`
  matches nothing in a manifest — so the documented form worked against a
  filesystem reader and silently served nothing against a store-backed one.

Two items were open at part 4 and are settled in part 5: a stale `done` verdict
that could not be superseded (friction 14), and layout sitting on the wrong
side of the line.

### D-hub part 5 — the framework sheds presentation (2026-07-28)

Stage 4 landed at milestone `d17437`. The framework store now serves data and
nothing else.

**Layout is the consumer's business — decided and done.** `/api/modules` used
to ship a fully laid-out `:picture` (nodes with `:x :y :w :h`, edges with
`x1/y1/x2/y2`, a canvas `:width`/`:height`). That is presentation in a data
contract, and the proof was that `slopp-ui.graph` ported cleanly and then
nothing called it — the consumer could not draw its own diagram if it wanted
one. The endpoint now ships `:modules` (rows carrying `:deps`), `:layers` and
`:cycles`; `slopp.ui.graph` is deleted and its one genuine analysis,
`substrate`, moved to `slopp.read.modules`. `slopp-ui.graph/picture-of` takes
the response whole and lays it out client-side.

That change was also the first real exercise of the regenerate loop, which is
the check the versioning story defers to: change the producer's schema → touch
the endpoint so its var metadata is re-evaluated → regenerate the consumer's
client → restart its image → validate live. It held (10 nodes, 4 foundation,
17 edges through the hub's proxy).

**`slopp.ui` is renamed `slopp.review`.** (Superseded 2026-08-01: renamed
again to `slopp.api` — see D-ui-api-distinct below. `review` collided with
`slopp.ops.review`, which is review_scan.) A module called `slopp.ui` that
serves no UI is a lie. Eleven namespaces moved by `rename_sweep`; `ui.pages`
became `review.reads`, because what it holds is read performers and it had not
served a page since the route forms left.

**The contract endpoint stays per-app — settled, not deferred again.** Part 3
left open whether `slopp.web` should declare `/api/contracts` itself, with
apps opting in via `:web/namespaces`, rather than each publisher writing its
own endpoint + performer + perform-ctx key (~15 lines). The plan said decide
it when a second publisher made the cost measurable. **There is no second
publisher.** slopp-ui consumes a contract and publishes none — its own client
is generated in-store by plain `generate_client {}`, because its SPA and its
endpoints share a store. So the measurement is a sample of one, and building a
framework-reserved perform-ctx convention on that is the guess the deferral
existed to avoid. Revisit when an app publishes for a real consumer.

**What the framework kept:** the seven read performers, `review.model`,
`review.api`, `review.server`, `review.heartbeat` — and `slopp.mcp → slopp.review`
stays, because the MCP still binds its own listener and beats to the hub.
`review.heartbeat` now takes its interval from the registration response
rather than a shared constant, so the two sides can be different releases.

### D-hub part 6 — an address is only claimable while something answers (2026-07-29)

Found by reviewing the split rather than by using it, which is worth noting:
three surfaces described the pre-split world and every check was green.

**`ui_serve` and `:ui` are an API, and the prose said otherwise.** The tool
description promised "a browsable HTML view of THIS store for a human: the
namespace index, form source, … the milestone timeline and per-milestone change
review" and told the caller to hand that url over, for a listener answering
`404 {"error":"no route"}` at `/`. `slopp-review/SKILL.md` sent reviewers to
`<ui_serve url>change/<from>..<to>`; `docs/reference/tools.md` carried a whole
"store browser" section. The main `slopp` skill had been updated correctly, so
the sweep AGENTS.md rule 4 asks for had been done once and stopped one surface
short — three times.

**The guard is anchored to a FACT, not a wording.**
`mcp-test/the-project-listeners-description-promises-only-what-it-serves` first
asserts that every route in `review.server/served-namespaces` is under `/api`,
and only then that the description promises no pages. A page endpoint coming
back retires the guard instead of having to argue with it. Sibling of
`slopp-prose-never-names-a-tool-that-does-not-exist`, and the same class: gates
see var references, never a promise made in prose.

**Decided: `:hub` is the project's own PAGE, and it exists only while a hub
is answering.** It was the configured hub root, set when the heartbeat started
and never revisited — so a machine with no hub had orientation hand a human a
connection refused, in the field the skill tells agents to hand over. No probe
was needed: registration and keepalive being one call means every reply carries
the slug the hub minted, so holding one IS proof of registration and the deep
link at once. `post!` was reading that reply and discarding everything but
`:beat-ms`; `register!`'s own docstring claimed the project "learns its own
public address" while the project threw it away.

So the beat reports every answer outward (`start!`'s third arity), nil
included, and `:hub` is rewritten per beat — a departed hub takes the claim
with it. Where we BEAT is now a separate key, `:hub-configured`, and when it
is set with nothing answering the brief says so in `:hub-note` rather than
going quiet or lying. Core 1 applied to orientation: a hub is optional, so
absence is an ordinary state that has to be sayable.

**The test that hid it asserted `assoc` works.** It did
`(swap! sess assoc :hub "…7359/")` and read the value back, which passes
whatever `start-ui!` does. Fixed to assert what the session actually holds —
and since no hub runs in that test, what it holds is nothing.

## D-evaluation-unit (2026-07-29) — the form is the editing unit, the namespace is the repair unit

**Decision.** A per-form hot-load stays the fast path for every write. When a
write leaves something holding a value CAPTURED from the edited form, the
namespaces holding those forms are reloaded through `image/load-ns!` — the same
loader every other path uses — before verification runs.

**Why this was a class, not a run of incidents.** Per-form hot-load was treated
as equivalent to loading the form. That equivalence holds only when a form's
whole contribution to the image is its own var binding and nothing else read it
at load time. Clojure's load semantics are order-dependent: evaluating a form
can snapshot another var into a value, evaluate metadata, or mutate a registry.
Re-evaluating one form replays none of that.

The tell was that slopp already had the correct behaviour in one place and the
broken one in another. `--live` reloads whole NAMESPACES and never had this
bug; the oracle reloads FORMS and had all of these:

| instance | what stayed stale |
|---|---|
| friction 1 | `(def tools (concat … env-tools …))` after editing `env-tools` |
| friction 17 | `^{:web/response schema}` metadata after editing the schema |
| #131 (twice) | a defmethod's registration in the multi's method table |
| `defonce` | nothing re-evaluates it, by its own contract |

Two of those had already been patched INDIVIDUALLY — `edit-replace!` and
`delete-form!` each carry a hand-written `unregister` special case for
defmethod. Two hand-patches of one class is the signal that the class is the
thing to fix.

**Why not the alternatives.** Always reloading the namespace is simplest and
provably matches the semantics we already trust, but taxes every write with a
full namespace load and re-runs top-level effects each time. Cascading
form-by-form re-evaluation is the most precise, but needs a `defonce`
exclusion, a cycle policy, and a rule for side-effecting defs — new machinery
to get right, where namespace reload is machinery already trusted. Detect-and
-report alone leaves the class open.

Measured on this store: 35 of 2202 forms capture at load at all (28 computed
`def`s, 7 metadata-carrying), so the repair path is rare and an ordinary `defn`
write pays nothing.

**Scope, honestly.** The repair rides `edit-replace!`, so `edit_subform`,
`ns_add_require`, `ns_remove_require` and `edit_revert` inherit it.
`edit_add_form` needs none — nothing captured a brand-new form — and
`edit_delete_form` now refuses while anything still references the form.
**`edit_group` does NOT repair**: its steps go through `apply-group-step` with
their own hot actions, and `undo` / `episode_revert` / the rename sweeps ride
that path, where a mid-sequence reload could fight machine-ordered replay.
That is a known gap, not an oversight.

**What this does NOT retire.** The defmethod `unregister` special cases stay. A
namespace reload re-runs a `defmethod` form, but nothing REMOVES a method whose
dispatch value changed or was deleted — removal is a different problem from
refreshing a captured value, and reloading cannot do it.

**Reported, because a namespace reload is a real cost:** `:image-reloaded`
names what was reloaded, `:image-reload-failed` carries a reload that errored,
and `:stale-in-image` survives only for what the repair could not reach.

## D-framework-injection (2026-07-30, user decision) — slopp supplies its own framework; the app does not declare it

**Decision.** `io.github.nvoxland/slopp-web` stops being something a store
`deps_add`s and becomes something slopp INJECTS, at the version the slopp doing
the work actually is. The published maven artifact stays — it is how a
non-slopp-built app consumes the framework — but a slopp-managed store no longer
names it.

**The problem it removes, which is measured rather than hypothetical.**
`slopp-ui` pinned `slopp-web 0.1.3`. The trailing-slash fix in
`slopp.web.static/mount-routes` — made BECAUSE of slopp-ui — shipped in slopp's
store, reached the uberjar, and never reached slopp-ui, because the declaration
is what loads. Green everywhere for a day. `framework-drift` (D-hub part 6's
tail) reports that now; this removes the ability to be in that state at all.

**Why injection and not shading/vendoring**, which was the first suggestion.
Shading relocates namespaces to dodge a version CONFLICT, and there is no
conflict here — there is one `slopp.web`, and the question is which copy wins.
Vendoring the files into the built app would work and costs three things a coord
keeps: reproducibility (`0.1.4` means one thing forever), provenance (what
framework is this app running, answerable without inspecting bytes), and the
third-party path — `slopp.web.contract/contract-document` exists specifically so
publishing a contract is "something any slopp-web app can do, not a privilege of
the tool", and an app built without slopp needs a maven coord.

**The precedent this follows, rather than invents.** slopp already injects its
own toolchain in exactly this shape, and both docstrings state the same
rationale:

- `image.repl/inherent-deps` (nREPL, malli) — "versioned centrally HERE so an
  upgrade reaches existing installs with no per-store migration", merged into
  every image's `-Sdeps` AFTER the manifest so slopp controls the version.
- `api.external/client-build-deps` (malli, the ClojureScript compiler) — "slopp
  versions these centrally so an upgrade reaches every store with no migration;
  the agent adds only APPLICATION deps".

`slopp-web` is the one piece of slopp's toolchain that is NOT on that list. It
is the anomaly, not the pattern, and it is the only one that has drifted.

**Two injection points, because there are two classpaths and only one of them
was ever the problem.**

- **The oracle image** (`inherent-deps` → `default-cmd`'s `-Sdeps`). THIS is the
  half that bit: slopp-ui's tests and `query_eval` ran 0.1.3 while the host jar
  had the fix. A store's oracle is a `clojure -M` process built from its
  manifest and does NOT include the uberjar.
- **The built app** (`client-build-deps`' sibling → the generated `deps.edn`).
  Without this the app would build with no framework at all once the manifest
  stops naming it.

**The version comes from `X-Slopp-Web-Version`** on the tracked
`META-INF/MANIFEST.MF`, which `uber` writes into the jar as
`META-INF/slopp/framework-version.edn`. That fact was added the day before for
`framework-drift` to report against; it becomes this design's source of truth,
and `framework-drift` retires once no store declares the coord.

**The subtlety, and the thing most likely to be broken later: injection must be
CONDITIONAL on the store not defining `slopp.web.*` itself.** slopp's own store
contains those namespaces; injecting the maven coord there would put a second
copy on the classpath behind the materialized one and make correctness depend on
classpath order — the exact ambiguity this decision exists to end. `nil` from
the version resource (a checkout, a `clojure -M` run) must also inject nothing.

**A property to preserve deliberately.** A store's oracle image gets the
manifest plus injected deps — never the uberjar. That is what physically
enforces slopp-ui's rule that the only slopp namespaces it may use are
`slopp.web.*`: reaching for `slopp.api` fails to compile in the image. Injection
keeps that (the image still receives only the framework). It would be lost by
"simplifying" this later into putting the uberjar on the image classpath, which
would silently make all of slopp reachable from every app.

**Staged, because the two halves live in different repos and are owned by
different sessions.** slopp adds the injection first and keeps honoring an
explicit manifest declaration; a store that still declares `slopp-web` is
unaffected, since `inherent-deps` merges last and wins on version. Only then
does slopp-ui drop the coord from its manifest. Nothing breaks in between, and
the order cannot be reversed — dropping the declaration first would leave that
store with no framework at all.

### D-framework-injection part 2 (2026-07-30, user decision) — slopp-web is never published, so the coord has to go

**Decided:** `io.github.nvoxland/slopp-web` will NEVER be published to a real
remote. It is not something anyone should depend on outside what slopp
automatically includes in slopp builds.

That invalidates the reasoning in part 1, and the invalidation is worth keeping
rather than editing away. Part 1 rejected vendoring to preserve three properties
a maven coord has over copied files. Under this constraint:

- **Third-party consumption** — explicitly ruled out. This was the load-bearing
  argument and it is now the opposite of the goal.
- **Reproducibility** — illusory. A coord resolvable only from one machine's
  `~/.m2` is WORSE than vendored files, because it looks portable and is not.
- **Provenance** — the only one that survives, and vendoring can carry it just as
  well by stamping the version into the built tree.

**The defect this leaves in what part 1 shipped**, stated plainly because it is
live: a built app's generated `deps.edn` names a coord nothing can resolve
(latent — nobody has deployed one), and a fresh machine cannot boot a slopp-ui
oracle image until someone runs `slim-install` in slopp2 (real today). Evidence
that it was only ever local: every version 0.1.0–0.1.4 carries a
`_remote.repositories` with an empty repo id and the directory metadata is
`maven-metadata-local.xml`.

**So the framework should be VENDORED, not coordinated.** Two halves, matching
the two classpaths part 1 identified:

- **The built app** — `build!` writes `slopp/web/**` into the materialized tree
  from its own jar resources, and the generated `deps.edn` declares only the leaf
  libs (clojure, hiccup, cheshire, garden, http-kit). Self-contained and portable
  with no repository at all.
- **The oracle image** — the files extracted once to a cache directory, attached
  as a `:local/root`. NOT the uberjar itself: that would put all of slopp on the
  image classpath and destroy the property part 1 exists to protect — that a
  store's image receives only the framework, so reaching for `slopp.api` fails to
  compile.

`framework-injection`'s CONDITIONS survive unchanged (uses-but-does-not-define,
uses-not-merely-exists); only the payload changes from a coord to files. So does
`X-Slopp-Web-Version`, which stops being a maven version and becomes the stamp
that says which framework a built tree carries.

Open: whether `slim` / `slim-install` are deleted outright or kept as a local
convenience. Nothing depends on them once vendoring lands.

### D-mcp-stdio-only (2026-08-01, user decision) — MCP is stdio, and the reviewer API is a distinct custom API

Two rulings in one, and they pull apart a conflation that had been in the code
since Phase 4.

**MCP is served over stdio.** "We don't need/want multiple agents in the same
mcp server. So stdio is good for mcp." One agent, one server, one session.
That removes the entire reason `slopp.mcp.http` existed: it was P4-m1's
"shared-session multi-agent", N clients on one store/image over native MCP on
`POST /mcp`.

So the namespace is RETIRED, with its tests, including
`phase4-test/two-agents-one-store` — the scenario itself. Nothing else
depended on it: the benchmark calls `mcp/handle!` in-process, `--call` is a
one-shot CLI through `slopp.kernel.boot`, and its only other consumers were its own
tests. `phase4-test`'s surviving tests (per-agent attribution, fork/edit/merge
end to end) never used the transport, because attribution rides the DELTA — it
holds whoever wrote it and however they connected.

**The reviewer UI's APIs are separate and custom, and are NOT this project's
"web" project.** "Those have to go off http, you can use whatever web
infrastructure you want that makes them easiest to write. But they aren't part
of whatever counts as the 'web' project so code reuse is fine, but keep those
APIs distinct."

So: reusing `slopp.web` to write them is right and stays. What is not right is
serving them from the same listener as MCP, which
`slopp.mcp.http/start-server!` did (it mounted `ui-server/served-namespaces`
alongside `/call` and `/mcp`). That mixing goes with the transport.
`review.server/serve!` on the derived `slopp.api.server/derived-port` is the one place the reviewer
API is served, over the CALLER's live session.

**What this does NOT change.** `web.enabled` on slopp's own store stays: it is
what turns the web write gates and `query_routes` on for slopp's own
endpoint-shaped code, which is genuine value. And `web.port` still means one
thing — the port a web app's server binds (see the 2026-08-01 note under
`dev.server`). slopp simply has no web APP for it to describe now, which is
the honest reading and was always the shape of it.

**Consequence recorded, not fixed:** slopp's remaining `:web/`-declared
endpoints (the reviewer API) are still seen by `query_routes`, the cljs client
generator and `webdev.live/serving-namespaces` as "slopp's web app surface".
Nothing runs on it — `dev.server false` — and no other store has this problem,
so it is a self-hosting wrinkle rather than a feature request. Revisit only if
a second store grows tooling endpoints it does not want counted as its app.

**Salvage:** `store-reader` — the LIVE-store adapter behind
`static/mount-routes`, one of the two implementations held to
`slopp.web-test/reader-contract` — was only ever HOUSED in the transport. It
moved to `api.web/store-reader` with its contract run, because it is the
pattern the dev server will need for `web.static.*` mounts.

### D-ui-api-distinct (2026-08-01, user decision) — the API that feeds slopp-ui is `slopp.api`

Two naming corrections, both resolving collisions this repo made itself.

**"The dev server" was two things.** `DEV.md` used it for the MCP server —
slopp's own development surface. Since the framework-managed app server
landed it also meant the thing that serves a USER'S app, which mcp is what
STARTS. One of them serves your code and the other serves you, and the
sentence "you will get a dev server you did not ask for" pointed at the wrong
one.

Resolved by user decision: **slopp's own surface is called "mcp"**, which is
what it is called everywhere else anyway. "The dev server" keeps the meaning
web developers already expect — the thing serving an app under development —
so `slopp.webdev.live` and the `dev.server` capability stay as they are.

**`review` was two things.** `slopp.ops.review` is `review_scan`, risk triage
over the store. `slopp.review.*` was the HTTP API slopp-ui consumes: a
different concern, a different module, its own layer.

That collision was manufactured by an earlier fix. The module was
`slopp.ui`, renamed to `slopp.review` (D-hub part 5) on the correct
objection that a module named for a UI it no longer contains is a claim a
reader trusts. True — but the replacement landed on a word `slopp.ops.review`
already owned.

**`slopp.api` is what that rename was reaching for**: not the UI, the API
*for* it. Six namespaces plus their tests:

    slopp.review.api        → slopp.ui-api.endpoints
    slopp.review.reads      → slopp.ui-api.reads
    slopp.review.model      → slopp.ui-api.model
    slopp.review.contracts  → slopp.ui-api.contracts
    slopp.review.server     → slopp.ui-api.server
    slopp.review.heartbeat  → slopp.ui-api.heartbeat

(Those `slopp.ui-api.*` names were themselves renamed to `slopp.api.*` in
phase 2, once the old occupant of `slopp.api` had moved to `slopp.ops`.)

`.api` became `.endpoints` because `slopp.api.api` stutters, and endpoints
is what the namespace holds — one fn per route. `slopp.ops.review` is
untouched; that one is genuinely review.

**What `ns_rename` does not carry, recorded because the next rename will hit
it too.** The tool rewrites declarations, requires, qualified refs and even
quoted symbols (`served-namespaces` came through correctly). It reports
everything else as `:left-behind`, and three kinds needed hands:

- **A module-scoped export.** `api.modules/production-manifest` carried
  `^{:export "slopp.review"}` — a string naming a module. After the rename
  that granted visibility to a module that did not exist. Nothing failed; the
  export simply stopped meaning anything.
- **A manifest FIXTURE.** `api.modules-test/slopp-production` asserts slopp's
  real module graph as strings.
- **Prose.** `rename_sweep` handles this in one verified group, and it must be
  previewed: six of its twenty-four hits were HISTORICAL — incident records
  naming namespaces that really were called `slopp.review.views` /
  `slopp.review.pages`, and references to the hub, which is slopp-ui's project
  and never had a name in this store. Sweeping those forward invents history.
  They were repaired immediately after.

### D-web-context (2026-08-01) — the app declares its perform-ctx with a marker, and a gate enforces it

The managed app server WRITES the `serve!` call, so it has to know how to
build `:web/perform-ctx` — the map a handler receives as `:web/deps` and every
performer receives as its first argument. That map is app-specific by
definition (a registry, a pool, a connection), so the app must say.

**A marker (`^{:web/context true}` on a zero-arg fn), not a capability naming
a qualified symbol.** The reason is the gate, and it is the whole reason:
with the declaration in the store, both halves are visible statically — the
handlers that read `:web/deps`, and whether anything claims to build it — so
"this store reads deps and declares no builder" refuses at the WRITE. A
capability is a string in config, checkable for resolvability at boot at the
earliest, which is after the browser has already seen the 500. It also splits
the declaration from the thing declared.

**A SINGLETON**, unlike performers, which are keyed by kind because there are
many. Two declarations is a refusal rather than a pick: choosing silently is
how an app runs on deps it did not mean, surfacing as a missing key three
layers away.

**It cannot be a performer**, and the idea is circular rather than merely
wrong — performers already RECEIVE the perform-ctx, so the context is strictly
upstream of that vocabulary. Recorded because it is the obvious suggestion.

The gate is `web-undeclared-context`, refuse-grade, the sibling of
`web-undeclared-effect`: an effect kind needs a marked performer, the context
needs a marked builder. Two scoping choices, both deliberate:

- **`:web/path` endpoints only**, not every form naming the keyword. slopp's
  own `slopp.web.dispatch/handle!` assigns `:web/deps` onto the request; a
  keyword-anywhere gate would refuse writes to the framework's own dispatcher.
- **Armed by `web.enabled`, not by `dev.server`.** Whether a handler's deps
  have a declared source is a property of the APP, not of who runs it, and
  `dev.server` is a dev-lifecycle knob. The consequence is intended: an app
  that runs its own `serve!` and builds its own context is asked to mark the
  builder it already has and call it, because two definitions of one store's
  context agree right up until one gains a key.

**What is NOT decided: what a refresh means for state the builder allocates.**
Today there is no continuity at all — `refresh!` boots a fresh image at every
done point, so the builder runs again and its atoms are new. That is
documented rather than fixed. If hot-loading a refresh into the RUNNING image
is built, the contract has to be settled BEFORE it ships, since apps get
written against whichever is true when they are written, and changing it later
breaks them in the direction of state that unexpectedly persists — harder to
notice than state that vanishes. See
`ideas/product/how-slopp-learns-the-apps-perform-ctx.md`.

## D-git-push-pull-only (2026-08-02, user decision) — slopp publishes to git; it is not itself a git remote

**Decided:** the git feature slopp supports is **push/pull to a repo slopp does
not own**. The ability for a git client to talk to slopp and browse its history
*as* a remote is REMOVED.

Gone: `slopp.git.server` (refs advertisement, upload-pack, localhost lifecycle,
CLI entry), the embedded listener `slopp.mcp/-main` opened on a durable dir,
`query_git`'s `:git-url`, the `:git-server` session key, and the test
namespaces `slopp.git-server-test` / `slopp.git-embedded-test`.

Kept, and unaffected: `slopp.git` (the projection), `slopp.git.client`
(transport out), `slopp.sync` (the store side) — `git_push`, `git_pull`,
`git_clone`, `git_conflicts`, `git_resolve`.

**Why:** serving the store as a remote forced exact-project handling that got
overly complex for what it bought. It was also believed already gone, which is
its own evidence about how much it was used.

### What this revises

**G3** said JGit push/fetch "runs against the same `InMemoryRepository` as the
local read-only listener." The repo and the `FS/DETECTED` construction stay —
TransportLocal still NPEs on an FS-less DFS repo — but there is no listener to
share it with.

**The store-adoption leak list** (three things that had to follow the store
rather than the dir) loses its second entry: `mcp/-main` no longer starts a git
listener at all, so it cannot recreate the store that serving-without-adopting
exists to avoid.

**`D-hub`'s salt.** `api.server/derived-port` is salted specifically to
avoid landing on the git listener's port for the same dir. That listener is
gone and the salt now distinguishes it from nothing. **It stays anyway**, for a
reason that has nothing to do with git: the derivation IS the address, so
changing it relocates every project's UI and strands every saved url.
`webdev.live/derived-port`'s salt is still genuinely load-bearing — one MCP
process still binds both it and the API listener.

**`D-hub` amended 2026-08-03 (phase 2): `slopp.api.port` is RETIRED, and the
derivation is not.** The restructure plan had these as one move — "becomes an
output: bind an OS-assigned free port, report the number" — and they are not
one move. The paragraph above was written after that plan and argues the
formula must not change; going ephemeral changes it in the strongest way, so
only the KNOB went. The capability is gone from the registry (19 entries → 18)
and `preferred-port` is `explicit → derived → 0`.

The distinction worth keeping, because it is easy to state backwards: this
port is an OUTPUT in the sense of *unconfigured*, not *unpredictable*. Nobody
sets it; it is nonetheless the same number every restart.

What made the knob removable was measurement, not principle: slopp-ui, the only
external consumer, sets three capability values and this was never among them,
and `ui_serve {port}` already covers the case a pin was for. What it BOUGHT is
larger than one registry row — `slopp.api → slopp.project` left the module
graph with it, because that key was the only reason a generic listener read a
project's configuration at all.

### Three things fell out that the plan did not anticipate

1. **`ensure-wip!` was dead the moment the listener went.** `refs/heads/wip/<line>`
   held a throwaway commit of live un-milestone'd state so a client could
   `git diff origin/main..origin/wip/main`. Its own docstring records that wip
   refs are never pinned in `git_map`, never a milestone parent, and rejected
   on push — so the advertisement was the only reader. It was minted into the
   IN-MEMORY projection repo, which `mirror-push!` never touches (that uses the
   on-disk `.git`), and `push-to-remote!` pushes named branches only. Deleted,
   along with `delete-ref!`, whose only caller it was. This was not free to
   keep: minting one rendered every source in the store and inserted a commit
   object on **every** projection — every push, pull and milestone.

2. **`slopp.sync/pull!` got simpler, not harder.** It read the listener's shared
   jgit ctx and fell back to opening its own. The plan treated preserving that
   context as a required untangle; in fact removing the special case makes
   `pull!` the same shape as `push!` and `publish-local!`, which already
   open-and-close their own. Three sync operations, one shape.

3. **`slopp.mcp → slopp.git` is now unused** and retired. The listener was the
   MCP transport's only direct use of git projection; everything git-shaped
   goes through `slopp.sync`, which is where publish/absorb belongs.

**Also found, unrelated but adjacent:** `slopp.edit/reentrant-vars` listed
`slopp.git/start-server!`. That var never existed — it was
`slopp.git.server/start-server!` — so the exclusion had never matched anything.
A quoted symbol set is not checked against the store, which is how a name can
sit in a registry looking exactly like coverage.

## D-module-role (2026-08-04) — a module declares whether it SHIPS, and an instrument does not

**Decided:** a third namespace-grained register, `:module-roles`, beside the
purity tier and the platform. Two values: **`:product`** (the default — the
system runs this code, it materializes under `src/`, it goes in the jar) and
**`:instrument`** (a HUMAN runs it by hand — a benchmark, a seeding script, a
mining CLI). Tool: `module_role {module role prompt}`, `remove: true` retires.

This is R5's second clause — *"and that module does not ship"* — which had
been stated since the restructure began and enforced by nothing. Naming
`slopp.lab` (task #9) gave the instruments an honest module and changed
nothing about what the jar contained.

**The mechanism is a source ROOT, not a marker.** An `:instrument`
materializes under `instruments/` instead of `src/`. The argument for that
over a flag the build reads is the same one that makes `test/` a directory:
**the exclusion has to work with a build script that has never heard of
roles.** `build.clj` copies `src` by name; so does essentially every Clojure
build. Move the file and the exclusion is free everywhere; add a flag and
every build script in every project using slopp has to learn about it.
`deps-edn` puts `instruments` on `:paths` so the code is still RUNNABLE from a
materialized tree — it is excluded from the jar, not from the project.

**Declaring `:instrument` is refused while product code requires the module,**
naming the callers. Unchecked, the failure is a jar carrying a require to a
namespace it does not contain, surfacing at a *consumer's* load time against a
store that plainly has the code. A `-test` requirer is fine: a test does not
ship either. This is the same bar `module_purity` meets by checking tier
violations against the forms already present — a declaration is an assertion
about the code, so it is checked against the code. What cannot be checked is
the claim itself, that a human rather than the system runs this; nothing in
the store distinguishes a benchmark from a scheduled job, so `:instrument` is
the one register value that is purely the author's assertion, and
`module_role` says so in its `:unverified`.

**It also leaves the architecture view.** `production-manifest` excludes
instruments exactly as it excludes `-test` namespaces, and for one reason
rather than two: neither is product code. Measured before and after —
`slopp.lab` sat at **layer 8, the apex of the product layer map**, so every
layering statement about slopp had a benchmark harness on top of it. The apex
is now `slopp.mcp`, the transport, which is what a layer map should say.

Two things fell out that the decision did not plan.

1. **The git PROJECTION ignored path resolution entirely** — `commit-paths`
   called `source-path` with neither platform nor role, so a `:cljc` namespace
   projected as `.clj`. Benign today (one namespace, and `:cljc` loads on the
   JVM either way) and not benign in principle: build.clj's documented CI flow
   is `clojure -T:build uber :src src` against a CHECKOUT of the mirror, so a
   projection that roots a namespace differently from `build!` produces a
   different jar from the same store. Both now resolve through the same call,
   made where the fold holds the store.

2. **The register set is now DATA** (`store/ns-grained-registers`).
   `ns-rename!` and `delete-ns!` each hand-enumerated the registers, two arms
   apiece and identical apart from which `record-*` they called — so a third
   register would have been forgotten by construction, and the failure mode is
   silent (an orphaned declaration names a namespace that no longer exists
   while the code that moved goes ungated; that is how fifteen orphans
   accumulated in one wave of deletions). A fourth register joins by adding a
   row.

**Measured:** four instrument namespaces, 36 KB, previously in every published
jar — `lab/evalseed.clj` alone is 15 KB of code that seeds slopp's own eval
rounds, downloaded by every user and usable by none. Namespaces under `src/`
after the change: 84. `slopp.image.testmain` is deliberately NOT swept in: it
looks like an instrument and is a built project's traced test entry point, so
it ships on purpose.

### D-screen-format-v2 (2026-08-05, user decision) — the readout speaks escaped text plus a whitelisted tag channel

The first screen format was an invented marker grammar — `#` headings,
`[click]`/`[fill]`, `§ region`, `<ul ×5>`, `+N more`. Its fatal flaw was
measured in review the day after it shipped: **the markers shared an alphabet
with page text**, so a page containing `# xyz` or `[click]` was unfalsifiable
— and the first real consumer renders CLOJURE SOURCE, a domain made of `#`,
`[…]` and angle brackets. Three other findings shared one root with it: tag
sugar unparsed (a classed input vanished while staying fillable), form-control
state invisible (checked/options/textarea), and elision eating assertion
targets (`text` defaulted to the tool's cap, so a test asserting row 5 of 5
failed while the row existed).

The settled design, in one sentence each:

- **Plain text stays plain, HTML-escaped** — the only raw angle brackets in
  the output are the reader's own tag channel, so the format is falsifiable
  over any page.
- **A tag keeps its brackets only where it carries a fact an agent acts on or
  asserts**: interactive controls (with the state a browser shows), structure
  (headings, tables, pre, img alt, the svg class census), enumeration.
- **The provenance rule**: an UNPREFIXED tag/attr was really on the page;
  `slopp:*` is derived — `<slopp:region>`, `slopp:count`, `<slopp:elided/>`,
  and `slopp:on` (the one fact HTML has no attr for: what handles an event).
- **`class`/`style`/`id` never reach the output** (class survives on svg only,
  as census vocabulary) — which is also what makes sugar verifiable: `:h1.big`
  and `[:h1 {:class "big"}]` must render identically, and dropping class is
  how you can see that they do.
- **Elision splits by caller**: the tool caps at 3 with a machine-visible
  `<slopp:elided count/>`; the test path (`drive!`/`text`/`lines`) elides
  NOTHING by default — a test's tokens are cheap, its false failure is not.
- **Prose mode is unchanged and unescaped** — it makes no structural claims,
  so it has nothing to collide with.

Considered and rejected: full HTML-ish rendering with structural tags for
everything (token cost with no capability gained — the user's call: "keep
things as purely simple text as possible"); escaping the invented markers
(bespoke escaping doubles the teaching load and models already know HTML's);
`id` in the attr whitelist beyond form controls (addressing is by what a
person says — text, href, aria-label; fields keep `id` because `fill!`
addresses by it, and what you see must always be drivable).

Landed with the format: one hiccup normalizer (sugar parsed in the accessors
both the renderer and driver read), one text function (browser-faithful
concatenation — the shown label IS the clickable label), bubbling as DOM
semantics, aria-label as a click address, disabled refusing, select
constraining to its options, browser-faithful url splitting, `open`
validating its page, `drive!` refusing in words, and `module_platform`
reporting the `^:web/page` entries a `:cljs` declaration strands — the write
that does the stranding being the only surface that can name it at the moment
it happens.

## D-where-addresses (2026-08-09) — `where` names a row; it does not assert a value

**Decided:** `edit_subform`'s `where` is an ADDRESSING mechanism. Both sides of
every entry — key and value alike — are compared by the spelling they answer
to, not by `=`. So `:stored-name`, `'stored-name`, `"stored-name"` and
`":stored-name"` are four spellings of one name and all four reach the same
row, and a map stored with string keys is reachable from a wire that
keywordizes every key it carries.

**The measurement that forced it.** Every registry in this store is keyed by
keyword VALUES — the rule catalog, the done-advisories, `crossings/kinds`, the
fold-field registry, `refs/mention-kinds` — and `where` arrives as a JSON
object, whose values are strings. So the single most common thing `where`
exists to address was the one thing a caller could not express. It was filed
five times as three different bugs, and the third instance landed against a
registry built the same episode, which settles the "old data shapes" reading:
a keyword is what a registry row IS keyed by.

**And the no-hit answer names the store's state**, not the caller's: *"`:key`
takes `:schema-drift`, `:key-typos`, …"*, or, when nothing carries the key at
all, the keys that are there. A refusal that echoes the input reads as "no
such row" when it means "wrong value".

**The rule lives in the PLAN, not at the transport**, and that is the part
worth stating because there was a precedent pulling the other way.
`slopp.mcp/file-handlers!` keywordizes `js_dep`'s `:format` by hand at the
boundary, with a comment saying why. That is a different act: `:format`'s
CONSUMER requires a keyword, so the boundary converts a type for a consumer.
`where` is compared against store data the boundary cannot see, and
`"stored-name"` / `":stored-name"` are both plausible spellings of one name —
a boundary rule has to pick one and would still miss the other. Putting it in
`keyed-replace-plan` also means every caller gets it: `apply-group-step`
accepts `:where` and nothing builds one today, so the day something does it
addresses rather than asserts, with no second hand-coercion.

**Not back-compat-breaking in any measurable way**: the exact-value spelling
that worked before still works, and the widening can only turn a refusal into
a hit or — if two rows collide once spelling-normalized — into the ambiguity
refusal, which names the count and asks for another entry.

### D-lines (2026-08-15) — one file, many lines; a branch is a NAMED one

**Settled by the user**, as the substrate for agent threads: *"I don't think we
want branches to be separate files. And neither should threads. We want a
single file which all the agents work against and which holds all the history
across all the branches and threads"*, and *"the single tree of history should
be tracking all the splits and joins."*

A **line** is a pointer to a head delta, in a `lines` table. A named line is a
BRANCH; an anonymous one is an agent's THREAD. One row shape, because they are
one thing — a thread is a branch nobody named, and separate tables would mean
every question about history had to be asked twice.

A branch used to BE a db file under `.slopp/branches/<name>/` with the whole
store snapshotted into it. Four separate facts forced that, and the line work
removed each one:

| What forced a file | What replaced it |
|---|---|
| the write CAS read the GLOBAL journal head | `UPDATE lines SET head=? WHERE id=? AND head IS ?` — per line |
| `elements` was keyed `(ns, pos)` — one view per file | keyed `(line, ns, pos)` — one view per line |
| `:parent` lived inside the pr-str'd payload, so the DAG was unreachable from SQL | `deltas.parent` is a column, and the column wins on read |
| a branch's identity was a `meta` row, i.e. a fact only its own store could state | `lines.id`, stated by the journal about all of them |

**What this buys, beyond tidiness.** Lines SHARE history instead of copying it,
so main's log is a genuine PREFIX of a branch made from it; a split costs one
`INSERT … SELECT` over the form rows rather than a snapshot of the journal; two
servers see each other's branches without either touching the filesystem; and
the git projection folds a LINE's ancestry, so work that has not landed is
structurally unreachable from a branch's projection rather than filtered out of
it.

**Two things are load-bearing and easy to get wrong.**

1. `write-snapshot!`'s DELETE must carry `AND line = ?`. Without it a write does
   not return a wrong answer — it ERASES another line's namespace, and what is
   left looks exactly like a write that never happened.
2. A line's `:deltas` must be its ancestry. `try-commit!` takes its CAS head
   from `(last (store/deltas base))`, so a store value carrying another line's
   deltas yields a head that can never match again: a line nobody can write to.

**And one consequence nobody predicted.** The id counter belongs to the FILE
(`deltas.id` is UNIQUE journal-wide) while the value minting from it belongs to
one line, so two lines counting from the same place mint the same id. This was
unreachable while a branch was a separate file, and the equivalent case for two
servers on ONE line had always been covered by the CAS serializing them — which
is precisely the serialization per-line CAS removes on purpose. **The
protection was a side effect of the thing the feature deliberately removed.**
Answered by `db/next-id-floor` plus two raisers (`line-view` on adoption,
`refresh-cache!` unconditionally) and `duplicate-delta-id?`, which makes the
residual race a `false` from `append!` rather than a throw — kept as narrow as
`writer-collision?`, naming one constraint on one column.

**No legacy handling ships** (user: *"Keep it clean, we don't want 'legacy
management' code"*). The `(ns,pos)` → `(line,ns,pos)` migration is idempotent
DDL inside `db/open!` and runs at most once per store; there is no second read
path. The two real stores were handled by hand.

### D-threads (2026-08-15, user decision) — every agent works in an anonymous line, and `done` is what lands it

*"Independent threads for agents to automatically be working in until they hit
'done' … basically like an unnamed/anonymous branch that is automatically
managed for the agent between 'done' calls so that only done things show in the
actual branches."*

A THREAD is a line with no name and an `agent`. Every session adopts one — no
option, no tool — and its writes go there until a green `done` lands them onto
the branch. A branch therefore only ever contains work some verdict stood
behind.

**Adopt-or-create, keyed by `(agent, branch)`.** Nothing is remembered between
sessions: a returning agent asks the same question and gets the same row, which
is what makes un-landed work survive a process restart. A landed or abandoned
thread is never re-entered — its meaning is settled.

**Pinned, and rebasing exactly once.** A thread forks at the branch's head as it
stands and stays there; when the branch has moved by the time it lands, the
branch is merged INTO the thread first, through the same pipeline
`branch_merge` uses. The view and the verdict are stable while work is in
progress, and every conflict arrives together, at a moment the agent chose. A
conflicting or red rebase lands NOTHING and leaves the thread open.

**The order inside the landing was forced, and it is the part worth
remembering.** `kernel.boot/store-sources` reads the trunk, so once a session
writes to a thread its edits stop reaching the running host until they land —
and `done` is host code. Landing adoption before the land existed would have
stranded the edit that adds the land on a thread nothing could move: the fix
for the wedge unreachable from inside it. So the land shipped first, complete
and inert (a session's line was still a branch, so it no-opped), and the
adoption flip was the last write. Same shape as the phase-2 migration hazard,
one layer up.

**What is NOT an agent gets no thread of its own to hide in.** A clone lands
what it ingested, because a project whose `main` is empty is not a clone of
anything — the next serve would decide the store was still empty and import
again. A milestone lands its marker, because a milestone naming work the branch
does not contain is unreadable. A turn marker is written on the agent's line
rather than the branch, because it is part of that agent's episode and the
session that it describes reads its own thread.

**Two servers share a line by sharing an identity.** Per-line CAS still
serializes writers on one thread — that is the rebase path — so an orchestrator
running two processes for one agent gets exactly the contention behaviour that
existed before threads, and two different agents get isolation instead.

## D-capabilities (2026-08-15) — a capability is the unit a project opts into, and it is five things

The user's scope call: slopp owns much more of an application's infrastructure
than it did, and the reason is security. An agent writing its own argument
parsing, its own click routing, its own request plumbing is writing security
surface nobody reviews — because in a user's project, nobody does. Pull that
into slopp's layer, where it is built once, gated, and tested against real
sockets and real streams, and hand the consumer a pure function plus a stub.

The unit of opt-in is a **capability**. Four to start: `cli`, `http`, `rest`,
`webapp` — `web` renamed to `http`, and a browser-side layer added.

### A capability is five things, and a row that cannot supply all five is a setting

- a **PORT** — the abstraction application code is written against;
- an **ADAPTER** — the real IO behind it, which is slopp's code and slopp's
  tests rather than the consumer's;
- a **FAKE** — shipped beside the port, so a consumer's tests need no socket,
  no browser and no subprocess, and stay in the fast in-image tier;
- **GATES** — refusing reaching AROUND the port, since a fake nobody has to use
  buys nothing;
- a **SURFACE REPORT** — one derivation with three readers: the agent, a
  consuming tool, and the human, who does not read the code and needs a
  rendered picture of what the application is.

The first four are a pattern slopp had already proved once and could not
generalize. `slopp.web.client/request` + `fake-requester` is the port and its
fake; the `direct-http` rule forces callers through it; and that rule's own
teaching says why it stopped there — *"a gate may only demand a port that
EXISTS — slopp ships one for HTTP and none for files or subprocesses."* Each
capability ships a port, which is what lets the gate widen.

**`html` is deliberately NOT a capability**, and it is the useful negative
example: hiccup and garden are pure functions that throw on unsafe input, so
there is no IO to own and nothing to fake. A capability exists where there is
IO to take away from the consumer.

### The catalog is the source, and `owners` is derived from it

`capabilities/capability-catalog` declares each capability and what it
`:requires`. `owners` — the vocabulary of legal key prefixes — is computed from
it, so a capability cannot exist without an owner segment and an owner cannot
drift from its capability.

This closes a gap worth naming: **the owner-segment rule had no decision record
at all.** It lived only in shipped prose (`docs/reference/config.md`, the
skill), which is to say the single most load-bearing statement about how a
second app type arrives was written down only for users.

Three keys the derivation makes possible, all `^:export` because they replace
reaching into `[:config "capabilities" :values]`: `enabled?`, `prerequisites`,
`dependents`.

### The graph is NOT a chain, and the reason is mechanical

First draft: `cli → http → rest → webapp`. Landed:

```
cli        http
            ↙   ↘
      webapp     rest
```

`webapp` requires `http` because a browser app must be SERVED. It does not
require `rest`: client routing, state and event dispatch have nothing to do
with typed contracts, and an app may talk to a third-party API, a socket, an
API older than slopp, or to no server data at all. `cli` is nobody's parent —
a `-main` is a PACKAGING fact, and an embedded or library-hosted server would
carry argv parsing it never uses.

Raised by slopp-ui, who argued the capabilities are unrelated in kind. True but
soft on its own; plenty of frameworks bundle things that are merely
usually-together. **The argument that settles it is mechanical.** `:requires`
drives TWO things — what an enable turns on, and what a DISABLE is refused for.
Under the chain, `rest.enabled false` would have been REFUSED on any store with
`webapp` on, including one whose browser app talks to an API slopp does not
serve. A relationship that is not real becomes a refusal that is, and the
person meeting it has done nothing wrong.

### Enable ACTS, disable ASKS

`implied-puts` — enabling a capability writes its prerequisites in the same
commit and REPORTS them as `:implied`. Without it every project meets the same
puzzle once: opting into `webapp` appears to work and then nothing serves.

`disable-refusal` — turning off something a dependent stands on refuses,
naming what holds it up.

The asymmetry is deliberate. Turning something ON has one safe answer, because
a prerequisite is exactly what the capability cannot work without. Turning
something OFF does not: silently disabling `webapp` because you disabled `http`
would be slopp removing a feature the author never mentioned.

`:implied` is ABSENT when nothing was implied, the way the module manifest's
`:debt` is — an empty vector on every write trains the reader to skip a key
that has to be read when it IS there.

### Gate inertness is DERIVED, not written per gate

Nine gates each opened with `(when (web-enabled? candidate) …)`. That is a rule
every gate author has to know, which nothing reminds them of, and whose
omission fires an HTTP rule on a project that never asked for HTTP — the
adoption story breaking for projects that will never read that file.

Inertness moved into dispatch. `gate-capability` derives a gate's owner from
the namespace implementing it (`slopp.edit.http` → `http`), and `gate-check`
skips it unless the store declares that capability — a third skip reason beside
the two already there. All nine wrappers deleted, and `web-enabled?` with them.

The seam this creates is worth stating: **a gate is a pure question about the
FORM, and dispatch answers whether this store asked it.** Calling a gate var
directly on an opted-out store correctly still returns its teaching.

### No backwards compatibility, and `:orphaned` is why that is affordable

`web.*` → `http.*` renames every key. There is no alias. `report`'s `:orphaned`
path — built earlier, never yet exercised — names every stored key this build
does not recognise, WITH its value, so the report is a migration instruction
rather than a prompt to go and look. This store's own migration was done by
reading it.

### What the rename cost, recorded because it will happen again

- **Five escaped-dot REGEX literals** survived the sweep: `#"web\.auth\.groups…"`,
  `#"web\.static\..+"` twice, and two more. `rename_sweep`'s text pass cannot
  see through `\.`, and the `stale-pattern` rule only grades patterns naming a
  NAMESPACE, not a config key. One of them made `http-unknown-group` refuse
  EVERY declared group as unknown while its own teaching told the author to
  configure the key it was reading past.
- **Sweeping a config family renamed NAMESPACES too.** `web.auth` matched
  `slopp.web.auth`, moving a file out of the slim framework jar. Caught by
  `the-web-framework-never-reaches-back-into-slopp` — but the `web.static`
  instance made that same guard go GREEN, because its population is derived by
  a `slopp.web.*` prefix and the namespace simply left the set. **A guard's
  population shrinking silently is not a pass.**
- **A test FIXTURE is data, not prose.** The sweep rewrote the retired
  spellings that `orphaned-stored-keys-are-named-rather-than-dropped` used as
  its subject, leaving it asserting that VALID keys were orphaned — the one
  test whose whole topic is a rename, broken by a rename.

### A capability whose population here is ZERO cannot be validated here

Raised by slopp-ui, 2026-08-16, and it is a standing constraint rather than a
wave-1 detail.

slopp's own store has **no `:cljs` namespace at all** — `module_platform`
declares six platforms and none of them is `:cljs`. So every future `webapp`
rule has a population of zero on the codebase that would ship it. A green run
here is green over an empty set, and `full_check`'s `:checked` field would say
so if anyone read it.

The same asymmetry runs the other way for the in-process end-to-end driver:
slopp-ui's hub never opens a store by its own first rule, so ten of its eleven
fixtures are cross-process and the driver cannot help there. They measured it —
154 of 6887 bytes, ~2% — and told us not to measure the feature on their store.

**So the two stores have opposite blind spots and for `webapp` they do not
overlap.** slopp-ui is not a second opinion on something already exercised
here; for that capability it is the only place it is exercised at all. The bar
for shipping a `webapp` rule is a run THERE, reporting hits, misses and false
positives — which is exactly how the `:cljs`-holds-choices candidate got
corrected from "a `case` or a literal map" to "a `case`", on a measurement of
1 true positive / 0 false against 0 / 12.

Two rule-design lessons came out of that measurement and both generalise:

- **A rule that fires on a tier's PURPOSE is inverted.** The `:cljs` tier
  exists to hold browser effects, so a literal map handed to a browser API is
  the tier SUCCEEDING. Test for the next candidate: does this fire on the thing
  the tier is for?
- **A rule must not demand an impossible remedy.** Some choices are correctly
  stranded — rough.js tuning constants cannot become `:cljc` at any price — so
  the teaching can only say *this is a decision nothing checks*, discharged by
  a marker carrying the reason. A rule whose fix cannot be performed gets
  dialled off, and then it protects nothing, including the cases that could
  have moved.

### Wave 2 (2026-08-15) — `cli`, and what it took to make a capability REACH a project

Wave 1 built the mechanism; `cli` was the first capability built on it, and
building a second one is what showed which parts of the mechanism only worked
for the first.

**The five things, for `cli`.** PORT `slopp.cli/run` (argv + context in, `{:cli/exit
:cli/value :cli/out :cli/err}` out — it returns rather than exiting, so a test
and a process see the same value). ADAPTER `slopp.cli/context` (the real process
streams). FAKE `slopp.cli/fake-context` (writers a test reads back). GATES
`cli-args-schema`, `cli-command-collision`, `cli-direct-stdio`. SURFACE REPORT
the `:cli` section of `query_surface`.

**One sectioned tool, not one tool per capability.** `query_routes` became
`query_surface` with `:cli` and `:http` sections rather than gaining a sibling
`query_commands`. With two tools, ABSENCE is ambiguous — an empty answer cannot
distinguish "this app has no commands" from "you asked the wrong tool" — and a
section that is present and empty says which. Discoverability points the same
way: one tool with options beats several tools an agent has to know about.

**The entry is GENERATED, and that is the capability.** A cli app declares no
`app.main`: the author writes `^{:cli/command …}` forms and `build!` writes
`native.main`. Three consequences, each of which broke something:

- **Marker detection stopped being optional.** `framework-injection`'s second
  condition — usage is not only requiring — existed because a `^:web/page` app
  is opened by slopp on its behalf. A cli app is stronger: with a generated
  entry, *nothing in the store ever requires `slopp.cli`*, so `:cli/command` is
  the only usage signal there is.
- **`native?` stopped being `(boolean main)`.** It is now "an entry exists".
  The silent failure it replaced is the sharp one: a store with BOTH `app.main`
  and `cli.enabled` got a launcher calling the author's fn directly — no
  parsing, no injected streams, no exit code, i.e. exactly the bare `-m` the
  capability exists to replace, handed to a store that had opted in. `build!`
  now refuses that pair before any main-specific check, because it is a
  contradiction in the config whether or not the named fn exists.
- **The binary name and the usage name are ONE string.** A `cli.name` beside
  `app.name` was planned and dropped: if the two could differ, generated help
  would teach a command the shell does not have.

**`lib-providing` REFUSES instead of skipping, and malli came back to
`deps.edn`.** The deps derivation's stated purpose is that it cannot drift from
what shipped; silently dropping a require it could not resolve is that purpose
failing quietly. It would have shipped a cli framework with no malli — the
exact `slopp.web.css`/garden failure the mechanism was built after — and the
existing guard could not catch it, because it iterates the map the derivation
PRODUCED and so asserts what was found, never what was missed. malli's earlier
removal (336 KB, 8 jars) was right at the time; the premise changed when
`slopp.cli.spec` started SHIPPING.

### The manifests are per-capability, and `"_"` is not a capability

`framework-files.edn` and `framework-deps.edn` became `{capability {…}}`, and
a store is handed the families it USES. Asserted by running it:
`ops-test/a-built-cli-app-RUNS-outside-slopp-entirely` builds a CLI-only tree
from two declared families and checks that `slopp/web.clj` is absent and
unloadable.

**Use, not enablement — and the first version of this entry got that wrong.**
It claimed per-capability vendoring stops `(require 'slopp.web)` succeeding in a
project that never enabled `http`, i.e. that it makes the opt-in hold at
runtime. slopp-ui disproved it by tracing it against their own store:
`used-families` reads requires and entry markers, so a store whose requires
outlive its config loads the framework whatever `http.enabled` says. Withholding
a family from a store that neither requires nor marks it withholds it from the
one store that was never going to require it.

**Keying on enablement instead would be actively worse, which is why it stays.**
That is precisely a store mid-migration — retired config keys, new registry,
requires unchanged — and it would boot with the framework missing, fail to load,
and lose the `config_file` calls that repair it. The 2026-08-06 shape exactly.
What per-capability vendoring buys is the PAYLOAD: a cli app carries no
`slopp/web/**` and inherits none of http's deps. The opt-in is enforced where it
can answer for itself — the write gates, and the capability-driven behaviour.

`"_"` is the family every store gets. It holds `slopp.lang` (D3.1) and — found
by the guard below — `slopp.cache`. Membership has a test: **a namespace belongs
in `"_"` when slopp's own rules tell an author to USE it.**

### A rule may only name a namespace it also ships

`ideas/refusal/a-rule-naming-a-namespace-must-check-it-is-vendored.md`, opened
by slopp-ui on 2026-07-31 and closed here. `:direct-http` tells an author to
call `slopp.web.client/request`; that is satisfiable only because the namespace
happens to sit under `slopp/web/`, which the vendor derivation happens to
cover. A coincidence of naming, not a guarantee.

`modules-test/no-rule-names-a-namespace-that-does-not-ship` scans every rule's
`:teach` and `:escape` for `slopp.*` and asserts the vendored set contains it.
**It found a live one on its first run:** `tier-refusal`'s escape names
`slopp.cache`, the shipped skill states the every-cache-goes-through-it rule,
and the namespace reached no consuming project at all. Fixed by shipping it —
it has zero requires — not by editing the advice, because the advice was right.
It also found a `:teach` citing `slopp.hub/post!` as an illustration; same
principle as the store never citing documents that do not ship, and the lesson
survived being stated without the name.

The general shape, and it is the third instance: **does the slim published
surface actually contain what we told people to use?** The first two were the
framework source and its transitive deps. All three were invisible until
something outside slopp tripped them.

### Two build-time artifacts are second copies, and both drifted in one wave

Recorded together because they are one class and neither is catchable by a test:
in every context a test runs, only one of the two copies exists.

- **`build.clj` has a store MIRROR.** A human owns the file on `main` and
  `clojure -T:build uber` runs that copy; the store carries a mirror that
  `build!` materializes, which is therefore what the EXTERNAL TEST TIER reads.
  The whole capability rewrite landed on disk and never reached the store, so
  the jar built from new code while every test about `build.clj` asserted
  against old — including the guard-the-guard written precisely so "the claim
  holds" and "I read the wrong file" could not look alike. It passed, because
  the wrong file was the old right one. Closed by a `tracked-file-parity` CI
  lane comparing `main:build.clj` to `slopp/main:build.clj`, byte-identical.
- **The generated manifests are resources in the JAR.** Under `--live` the
  reader hot-reloads from the store while the resource stays whatever the jar
  was built with. When the shape changed, a host on a 560-delta-old jar
  destructured a path STRING as `[cap paths]` and died with "Don't know how to
  create ISeq from: java.lang.Character" — a sentence naming no jar, no
  resource and no remedy, which took `build`, `restart`, the test tier and then
  every WRITE down with it. `boot/by-capability` now refuses a shape it cannot
  speak and names both the rebuild and the checkout escape
  (`clojure -M -m slopp.kernel.boot . --call build`, which works precisely
  because a checkout has no META-INF). Not compatibility: the old shape is
  still refused.

### D-capabilities, wave 3 (2026-08-15) — `rest`, and a contract that is HONOURED

`rest` was declared in wave 1 and armed zero rules. Its registry doc promised
"request/response contracts, boundary validation, and generated clients derived
from the same schemas". Two of the three existed.

**Measured before building anything, and the measurement is the decision.**
`slopp.web.dispatch/handle!` ran identity → route → policy → reads → handler →
effects and never looked at `:web/request` or `:web/response`. The schemas were
not even on the route row. **No namespace in the shipped `slopp.web*` family
required malli at all** — validation existed only in slopp's own app tests, by
hand. And `slopp.index.crossings/kinds` claimed the `:wire/json` crossing was
`:checked-by "the dispatcher validates against the same schema var the client
ships"`, which was false, in the one field whose job is to separate checked from
unchecked. So: gate-enforced at write time, published to consumers, used to
generate typed clients, and honoured by nobody.

That row was corrected FIRST and separately, before any of the rest was built —
a specification that lies about the current state is worse than none. It has
since moved back to checked, and the test assertion moved with it rather than
being deleted. A crossing moving between the two lists is the only honest way
that inventory changes.

**A response is judged on what the CLIENT receives, which costs a real
serialize/parse.** The cheap idea — `m/encode` through the json-transformer —
fixes neither failing case, and both directions are wrong:

| value | schema | in-image | arrives |
|---|---|---|---|
| `{:x :foo}` | `[:x :string]` | INVALID | `{:x "foo"}` VALID |
| `{:tags #{"a"}}` | `[:tags [:set :string]]` | VALID | `["a"]` INVALID |

So an in-image check would 500 a response the consumer receives exactly as
promised, AND pass one it receives broken. The round-trip is the only thing
that answers the question the contract asks. Those measurements live in the
test rather than here, because they are what would tempt the next reader to
make it cheaper.

**A request is DECODED and then judged, and the asymmetry is deliberate.** JSON
cannot carry a keyword, so `[:tag :keyword]` describes a value the wire cannot
express and decoding is what makes the contract honourable at all. JSON CAN
carry a number, so `"7"` against `[:id :int]` is a client error and coercing it
would publish a contract the server does not actually require. Leniency there is
how "it worked when I tried it" and "it accepts anything" become the same system.

### The validators are functions ON THE CONTEXT, and that is what keeps malli out of http

`handle!` looks for `:rest/decode-request` and `:rest/check-response` and calls
whatever it finds, exactly as it treats `:web/read-performers`. `slopp.rest`
requires malli; `slopp.web.*` still does not, so an app serving HTML never pays
for a validation library it has no contracts to use. The module system enforced
this rather than the author remembering it: `slopp.web` → `slopp.rest` was
REFUSED and had to be declared test-only.

slopp generates the `serve!` call for a managed app, so a capability adding to
the context has no call site of its own. `:web/wrap-context` is the generic
seam — a fn applied between assembly and serving — and `slopp.rest/validating`
is its first user.

### `slopp.rest/call` — the e2e loop without the e2e cost

The framework already KNEW about this gap and made it the author's problem:
`web.client/fake-requester`'s docstring says it does not model the server's
parsing and sends you to a real server, and `handle!`'s teach marker told you to
round-trip through JSON by hand. `call` drives the app's own endpoints in
process through the real encoding both ways, so a keyword arrives as a string
and a set as an array — with the boundary REAL, because a fake that skipped
validation would be a second implementation of the server.

Named without a `!` to mirror `slopp.cli/run`, which `slopp.cli`'s docstring
commits to. Both are equally effectful; `cli/run` was simply never flagged (its
writes are interop, which effectfulness does not propagate through), so the pair
had been split by DETECTION rather than judgement.

### The five contract rules moved from `http` to `rest`, and an HTML app is loosened

`http-endpoint-schema` → `slopp.edit.rest/rest-endpoint-schema`, plus four done
advisories → `slopp.rules.rest`, keys `:http-*` → `:rest-*`. Ownership is
derived — from the implementing namespace for a form gate, from the key prefix
for a done rule — so moving the code IS the change of owner, and the naming
guard fired in BOTH directions the moment the checks moved and the keys had not.

**Accepted consequence, and it is the point rather than the cost:** an app that
serves HTML and publishes no typed API is no longer required to declare
`:web/response` on every page. That was http demanding a JSON contract from a
document — one app type's vocabulary applied to every project, which is the R6
mistake in the gate that most looked like a general rule.

`http` now arms 12 rules and `rest` 5. slopp itself enables `rest`: it publishes
`/api/contracts` and generates clients, so unlike `webapp` this store is a real
bed rather than an empty room.

### Two bugs the store found that the plan did not anticipate

- **`:web/request` on a GET describes PARAMS, not a body.** `/api/ns/:ns`
  declares `[:map [:ns :string]]` for its path segment and the generated client
  reads it as the wrapper's argument list. Judging that against a nil body 400'd
  four of slopp's own endpoints the moment the boundary was pointed at them. The
  boundary now honours the documented meaning — body methods only. Params stay
  unvalidated and that is a real gap, filed rather than closed, because covering
  it means deciding what `:web/request` means for the client generator too and
  the two must not answer differently.
- **`:web/path` was missing from http's entry markers.** `:web/page` covers the
  app slopp OPENS on its behalf and misses the app slopp SERVES: an endpoint's
  `serve!` call is generated, so a store can declare a whole API, never name
  `slopp.web`, and have the framework vendor nothing into its built tree. Found
  by building a rest app, whose endpoints are all `:web/path` and none a page.
  Same marker-detection lesson cli taught, arriving a third time — which is
  enough instances to say it plainly: **usage-by-require is not a signal for any
  capability whose entry slopp generates.**

### Sharpening (2026-08-16): a rule its capability declined must SAY so

slopp-ui read their own `full_check` before taking the jar and found the wave's
migration note backwards: the five contract rules were enforcement they had
TODAY, moving to a capability off by default.

Checking which five actually stop found the picture wrong in both directions,
and the second half is the defect:

- **One stops.** `rest-endpoint-schema` is a write gate, and `gate-capability`
  derives its owner from the namespace, so it goes inert. Real, and the loss
  they identified.
- **Four never consulted a capability at all.** The done-grain contract checks
  ran whatever `http.enabled` said before the move and whatever `rest.enabled`
  says after it — while `query_capabilities` listed them under `rest`'s
  `:arms`, the list that says what opting in WOULD arm. **A capability claiming
  rules it does not control is the model failing at the one thing it is for.**

Two fixes, and the first is what made the second possible. Ownership is derived
ONCE (`capabilities/rule-owner`, read off the rule's own name); the arms report
and the sweep now consult the same answer instead of each running their own
`starts-with?`. And `sweep-plan` — extracted pure, so the DECISION is assertable
without a session, which is what had been wrong — reports a rule whose
capability is off in `:not-swept`, with the reason and the remedy.

slopp-ui's argument, kept because it generalises past this instance: **a
declined rule has to make a claim that can be FALSE.** Absence and a clean run
read identically, so a silence cannot be a detector — the same reasoning that
made the fourth app-server state worth building. It also makes the capability
model self-teaching: the sweep report becomes where you find out which checks
you are declining.

Filed alongside, not fixed: the `rules` config has no registry, so a renamed
dial and a mistyped one are the same event and both are accepted at the write.

### D-capabilities, wave 3 closures (2026-08-16) — the four gaps, and two a consumer found

The wave shipped with four gaps named in its own log. All four are closed, and
the last paragraph above is superseded: **the `rules` config has a registry
now.** `rules.catalog/config-refusal` grades a dial against every rule the
catalog knows and refuses a near miss by shared name SEGMENTS, so
`http-unsafe-get` and `http-unsafe-gets` stop being the same event.
`sweep-plan` gained `:orphaned-dials` — a stored dial that governs nothing,
with its VALUE carried, which makes it a migration instruction rather than a
complaint. `config_file` now reports `:verified [:registry]` for two paths
rather than one, and the docstring says so; the honest `:unverified [:schema]`
admission on the remaining paths is what got this fixed, which is the argument
for printing it.

**A command's returned value renders a sequence of MAPS as a table.** Measured:
a `list` command in a real tool printed 36 KB of raw EDN, one `pr-str` per row.
The decision that a command returns DATA is right and is what makes its tests an
`=` on a map; the gap was that the ONE shape every `list` command returns had no
rendering. `render`'s docstring drew a line at "not a formatting library" and
that line is still drawn, one step further out and said so: header once, aligned
columns, a fixed truncation width because a table wider than a terminal is a
wall again, and then nothing. No colour, no wrapping, no `--format`, no column
selection. A command that wants more returns the string it wants — the escape
hatch already existed and is still the honest answer for anything a table cannot
say. The `:cli/render` hint and a slopp-owned `--format` were both considered
and are not built.

**A scaffold may require a namespace that does not exist yet.** `ns_create`
creates it EMPTY and names it in `:also-created`. Red-first worked within a
namespace — `add-form!` interns a throwing stub for an unimplemented var — and
not across one, where a spec requiring an unwritten namespace failed to LOAD:
a refusal, not a failing test, on a brand-new project where every namespace is
the first one. An empty namespace is the namespace-grained stub, and it is a
real store write rather than an image trick because the author is going to
create it anyway and its EXISTENCE was never what the test was about.

**The root-segment test is the entire guard and is not negotiable.** A require
naming a LIBRARY must never conjure an empty namespace over it: the real one
would be shadowed and every call would resolve to nothing — a worse failure than
the refusal this replaces, and a silent one. A require sharing the new
namespace's own root is code the author is about to write; anything else belongs
to somebody else. `failed-namespace-load-is-not-silently-committed` kept its
invariant and changed its fixture, with a control arm pinning the two cases
apart, because retiring a trigger is not the same as retiring a subject.

**`:went-green` is derived from the complete set of failing test NAMES, never
from the failure blocks.** The blocks are capped for response size, so a test
past the cap has no block, which is indistinguishable from having passed — and
a write announced a test green while an immediate re-run showed it failing with
the message it had before. `traced-run` now carries `:failed-tests` unbounded
(symbols, so completeness is nearly free); when a summary carries no names and
the detail was demonstrably capped, no green is claimed and `:reds-uncertain`
says why. That degraded path is the state of every session between a kernel edit
and a rebuild, because the injected runtime is read off the reading process's
classpath. Reasoning: `.context/design-disciplines.md`, "a BOUNDED observation
cannot carry an UNBOUNDED conclusion".

**`rename_sweep` REPORTS regex literals it cannot see and does not rewrite
them.** A pattern spells a dotted name `web\.static`, which shares no literal
text with `web.static`; seven survived one wave and two of those survived three
green done-points, during which one rule refused every declared auth group as
unknown while teaching the author to configure the key it was already reading
past. They arrive under `:left-behind` with `:via :regex`, in the preview and
after the write. Rewriting was rejected on principle rather than on difficulty:
a regex is an INTENT, whether a `.` in one separates or matches anything is a
question about what the author meant, and a sweep that guessed would be wrong
silently — in the direction where a predicate quietly matches nothing.

**Two defects a consumer found, both from this wave's code.** `query_surface`
threw on any contract that was not a map, taking nine endpoints down for a
tenth; the schema accessor was total over malformed input and not over SHAPES,
and now degrades to the schema's own type keyword rather than to `[]`, which
would be a claim about the contract instead of about the report. And
`sweep-store!` destructured two keys out of `sweep-plan`'s four, so a `:note`
computed for a reader reached nobody — every test of that decision asserted on
the pure plan, correctly, which is exactly how the join went unwatched. Both are
recorded as disciplines: "a report over a POPULATION must not be hostage to one
member" and "splitting a DECISION from its PERFORMANCE creates a join nothing
watches".

### D-cli-output (2026-08-16) — REVISITED: a command writes its answer, and the return is only a status

**This reverses the headline decision of D-capabilities wave 2**, one day old,
on the user's argument. Recorded as a revisit rather than edited into the
original, because the original was reasoned and the reasoning is what has to be
answered.

**What wave 2 decided.** A command RETURNS DATA; slopp renders the value and
sets the exit code. The argument was testability: a command becomes an ordinary
in-image `=` on a map, so a wrong flag, a missing argument and a non-zero status
are assertable with no process and no captured text.

**What is decided now.** A command writes to `(:cli/out ctx)` / `(:cli/err ctx)`
and returns an exit status — an integer, or `nil` for 0. slopp formats nothing.
`run` returns `{:cli/exit :cli/out :cli/err}`; `:cli/value` is gone, and its
removal is the point rather than a side effect.

Three arguments, and the second is the one that settles it:

1. **Real CLI output is not a data structure.** The return-data model has a
   shape for the one command whose answer is a table and none at all for the
   ordinary one — a summary line, then rows, then a warning. An app hitting that
   returns a pre-rendered string, at which point the framework's rendering is
   doing nothing and the promise has quietly become "return a string".
2. **It made a test assert on a shape no user of the program ever sees.** That
   is precisely the defect `slopp.rest/call` was built to remove for HTTP one
   day earlier: asserting on a handler's return value checks the pre-wire value,
   not what the far side receives. **For a command line, stdout IS the wire.**
   Building the opposite thing for `cli` in the same wave was an inconsistency,
   not a difference between the two ports.
3. **The evidence was already on file and read the wrong way.** The 36 KB of raw
   EDN a dogfooded `list` command printed was the first real app hitting the
   wall; the response was to add a table renderer, which moved the line
   `render`'s own docstring said not to cross rather than asking whether the
   line was in the right place. `render` is deleted.

**What it costs, stated because it was the whole case for the old design:** a
test asserts on text rather than on data. That is the right trade and not merely
a tolerable one — the text is what a person reads and what a script pipes, and
`fake-context` keeps it assertable with no process, no socket and no temp
directory. The capability's claim is unchanged; only what is being asserted
moved to the observable side.

**The accepted hazard, stated rather than designed around.** A body whose last
expression is a number exits with it: `(count xs)` at the end of a command is
exit code 3. Only an integer can be a status, so nothing can distinguish a
deliberate 3 from an incidental one, and a cleverer rule (a sentinel, a
`{:cli/exit n}` map, a throw) would trade a documented rule for a second
vocabulary. End on `nil` when the value is incidental.

**What did NOT change, and why the gate survives intact.** `cli-direct-stdio`
refused `println` and `System/exit` before and refuses them now — with the same
behaviour and a different reason. It was about *whether a command has two ways
of answering*; it is now about *which stream*. `*out*` is not the stream the
context carries: no test captures it, no driver redirects it, and the fake
cannot stand in for it, so the output escapes. Injection is what makes an
invocation testable without a process, and the gate defends the injection.

**Two phantom citations found while doing it, and one built.** `slopp.cli`'s
docstring named `cli-contract` as the suite running both halves of the port —
it did not exist, in the docstring of the thing it was supposed to check. It
exists now, and it earned its place immediately: a command doing its own writing
means `run` must FLUSH, and a `StringWriter` has nothing to flush, so the fake
half cannot fail that assertion. Only the real-stream half can, and it is the
one test in the store that would notice. The other citation, `slopp.clidev`,
named a dev-side driver that never existed and is not needed; the claim was
dropped rather than built.

A store-wide scan then found 58 backticked citations of `slopp.*` names that no
namespace answers to. Most are legitimate family prefixes (`slopp.api`), but the
residue includes retired spellings from the `web.*` → `http.*` rename. Filed as
its own item, not fixed here.

## D-cljnx (2026-08-21, user decision) — the fake browser is its own component, the framework family is `http`, and a marker is prefixed by whoever READS it

Three decisions from one observation, taken together because each is the
reason the next is possible.

**The observation.** `^:web/page` is asked for two incompatible shapes —
`slopp.web.screen/open!` wants a driver, `slopp.webapp.dom/mount!` wants the
wiring declaration — so a browser app hand-writes its entry and
`slopp.build/webapp-launcher-source` has no caller. That was recorded as a
FORK to settle (`webapp-test/a-PAGE-cannot-be-both-the-inspection-entry-and-the-browser-entry`).
It is not a fork. It is one function holding two capabilities' adapters, and
the ambiguity is what happens when a third is needed.

### The layering that dissolves it

| layer | owner | depends on |
|---|---|---|
| hiccup → readable text | nobody | `clojure.string` |
| the session — `open!` `visit!` `click!` `fill!` `drive!` | nobody | the neutral contract |
| a server ctx → that contract | `http` | `web.dispatch/handle!` |
| a webapp declaration → that contract | `webapp` | `slopp.webapp` |

The code had already split itself along that line and nobody named it:
`screen.hiccup` and `screen.render` require nothing but `clojure.string`, and
only the top namespace reaches `slopp.web.dispatch` — which IS the server-ctx
adapter, inlined in `visit!`'s `cond` instead of being a function with a name.
`webapp/driver` is the same layer, already written, already named.

**So `open!` stops branching on shape.** One contract, two producers, each
living with the capability that owns it, each vendored exactly when its family
is. `^:app/entry` returns the app's own declaration; whoever opens it asks that
capability for its adapter.

**The vendoring blocker that proves the placement.** `open!` cannot simply
learn the third shape: `used-families` vendors per capability by `:ns-prefix`,
following USE, so a server-rendered store gets `slopp.web.*` and NOT
`slopp.webapp.*`. A static require of `slopp.webapp` from inside the http
family would make the `screen` tool fail to load for every http-only store —
the majority case, and the shape `open!` was built for. The dependency has to
run the other way, which is the same thing as saying the fake browser does not
belong to http.

### Decision 1 — the fake browser is `cljnx`

`slopp.web.screen` becomes its own component. The name is deliberate on two
axes: it is not `browser`, which must keep exactly one meaning because a real
one is a thing you may also be testing with; and it is not `screen`, which is
already three things (this namespace, the MCP tool, and a webapp PANE — the
route-table vocabulary, which is load-bearing and stays). A coined name is
unmistakably one thing, which is the entire problem with an overloaded one.

Nothing else in the system can ever be called cljnx. That is the point.

### Decision 2 — a marker is prefixed by the capability whose code READS it

`:cli/command` already follows this rule; the web family is the exception.
slopp's own `crossings/kinds` table has been classifying them this way the
whole time while the spellings disagreed:

| marker | its `:kind` | reads it |
|---|---|---|
| `:web/path` `:web/method` | `:http/route` | http's router |
| `:web/request` `:web/response` | `:wire/json` | rest's contract layer |
| `:web/client-routes` | **`:webapp/client-routing`** | webapp |

`:web/client-routes` is classified as webapp's and spelled as http's. And
webapp already lives the confusion from the other end: its REQUEST maps use
`:webapp/path` today while its entry marker says `:web/`. One capability, two
vocabularies, no rule saying which to write next time.

**The rule is "who reads it", not "where the data comes from".** That is what
makes a stale marker detectable, and it settles `:web/request` /
`:web/response` against the tempting answer: they become `:rest/*`, not
`:http/*`. They mark a TYPED CONTRACT — with `rest.enabled` false they are
inert while HTTP keeps working — and a request travelling over HTTP no more
makes the marker http's than JSON makes it the encoder's.

**`:web/page` is the one genuine exception**, and its ambiguity is a symptom
rather than a counterexample: it is read by cljnx, by `build!`'s launcher and
by the edit gates, across both http and webapp apps. It becomes **`:app/entry`**
— `app` is already the always-on owner row, and "the zero-arg fn that builds
this app" is what it has always meant. That also makes the resolution of the
fork read correctly: the entry returns the declaration, and each capability's
adapter knows how to drive it.

### Decision 3 — `slopp.web.*` becomes `slopp.http.*`

`http` is the only capability whose name and `:ns-prefix` disagree
(`cli`→`slopp.cli`, `rest`→`slopp.rest`, `webapp`→`slopp.webapp`,
`http`→**`slopp.web`**). It is not cosmetic: `used-families` and
`framework-injection` key vendoring off `:ns-prefix`, so the one capability
whose name does not match its code is also the one where "what does
`http.enabled` actually turn on" cannot be answered by looking.

**This is a rename already half-done, twice.** The capability CONFIG keys went
`web.*` → `http.*` on 2026-08-15 (its costs are recorded above), and slopp's
own tooling already reads `slopp.rules.http` and `slopp.edit.http`. What is
left is the shipped framework family and the markers — the two halves a
consumer actually holds, which is why they were deferred and why they are
worth finishing rather than carrying.

### The three waves, and why in this order

| wave | scope | slopp store | slopp-ui | docs |
|---|---|---|---|---|
| 1. cljnx + the entry | extract the fake browser; adapters to their owners; `^:app/entry` returns the declaration; wire the launcher | ~50 forms | 17 tests + the page | moderate |
| 2. markers | `:web/*` → the capability that reads each | 322 forms | 46 | 292 mentions |
| 3. namespaces | `slopp.web.*` → `slopp.http.*` | 196 forms | 41 | 98 mentions |

**1 is one wave, not two.** The adapter split IS what dissolves the fork;
doing them apart means doing the fork twice.

**2 before 3.** The marker rename is the one that can fail SILENTLY in a
consumer store, so it lands while it is the only thing moving. A namespace
rename breaks loudly; a marker rename does not break at all.

**3 last, and alone.** It is a mechanical 196-form sweep, and running it
beside a structural change means a red cannot say which caused it.

### What must not be missed, in every wave

**A renamed marker is silent, not broken.** `:web/spa` → `:web/client-routes`
left stores serving fine, clicking fine, and 404ing on every refresh and every
shared link, because the catch-all rows were generated from a key nothing read
any more; the one app that hit it caught it by luck.
`rules/unknown-marker-check` exists for exactly that — **but only for keyword
namespaces in its hand-kept `ours` set**. So `"web"` STAYS in that set
permanently after wave 2, or every stale `:web/path` in a consumer store goes
quiet again. Retiring the old spelling from `ours` is the one edit that undoes
the safety net.

**Rebuild the rename ledger for these waves, and retire it at the end.** The
declared old→new table plus the two checks reading it was retired on
2026-08-06 with "rebuild something like it if a rename of that scale happens
again; do not carry it between times". 322 + 196 forms across two stores and
390 doc mentions is that scale.

**The four classes no sweep reaches**, each already measured here: escaped-dot
REGEX literals (five survived the config rename; one made `http-unknown-group`
refuse every declared group while its own teaching named the key it was
reading past); qualified KEYWORDS, which never break and simply start lying;
token STRINGS, which do break; and test FIXTURES, which are data rather than
prose — the sweep once rewrote the retired spellings a rename test used as its
SUBJECT, leaving it asserting that valid keys were orphaned.

**And a guard's population shrinking silently is not a pass** — the
`web.static` instance made `the-web-framework-never-reaches-back-into-slopp` go
green by removing a namespace from the set the guard derives.

### D-cljnx addendum (2026-08-22, user decision) — a rename MOVES the patterns that spell the name

**Revises the decision `patterns-not-swept` recorded**: report a regex literal,
never rewrite it, "because a regex is an INTENT, not a name: whether a `.` in it
is a separator or a wildcard is a question about what the author meant, and a
sweep that guessed would be wrong silently."

The reasoning was sound and its conclusion was too broad. **slopp owns the
dialect, and a dot in a dotted name it governs is a SEPARATOR** — there is no
pattern that legitimately means `web<any>static`, so there was never an intent
to guess at. What stood between the author and a correct rewrite was a report
somebody had to act on by hand, which is exactly what the two measured
survivors did not get: seven literals in one wave, two surviving every write
and three green done-points, one of them leaving a rule that refused EVERY
declared auth group as unknown while teaching the author to configure the key
it was already reading past.

**The detection needed no work, which is the tell that the decision was the
only thing in the way.** `patterns-not-swept` already computed exactly the
right set — it matches an optional backslash per separator and then EXCLUDES
everything the text pass caught, so its answer was always the
unambiguously-literal residue. The guessing problem was solved when that
function was written; the report just stopped one step short of acting.

**What moves and what does not.** Only the NAME. The rest of a pattern is the
author's own matching and means nothing to a rename — `#"web\.static\..+"`
becomes `#"http\.static\..+"`, the trailing `\..+` untouched. The author's dot
SPELLING is preserved rather than normalised: rewriting an unescaped dot to an
escaped one would narrow what the pattern matches, which is a change to their
matching rather than to the name.

**It is REPORTED under `:patterns-rewritten`**, for the reason `:requalified`
is reported: a rename's diff must not contain a change to what a predicate
MATCHES without naming it. `:left-behind :via :regex` survives as the RESIDUE —
what the rewrite did not reach — and should now be empty, so a row there is a
finding rather than a chore.

**Scope, corrected by the consumer.** The exposure was never wave-shaped. It is
three VERBS applied to a store's own code — `edit_rename`, `edit_move_forms`,
`ns_rename`, the only three callers of `qualified-mention-changeset` — which is
ordinary work rather than a migration. A keyword sweep reaches none of it: it
matches no namespace name, so it runs no `ns_rename` and stays pure text
substitution.


### D-cljnx, wave 1 as BUILT (2026-08-22)

The fork is dissolved and the launcher has a caller. What landed, and the two
things that turned out differently from the plan:

- **`slopp.web/driver`**, the mirror of `slopp.webapp/driver`. One neutral
  driving contract, two producers, each owned by the capability whose code it
  needs. `:document (fn [path] hiccup)` replaced the served-ctx branch that had
  lived inside the fake browser.
- **`slopp.cljnx`** (+ `.hiccup`, `.render`) — the fake browser, belonging to no
  capability, vendored to every store by DECLARATION rather than by the
  coincidence of sitting under http's prefix.
- **`cljnx/driver-for`** — the one PUBLIC derivation, resolving each capability
  late inside the branch that matched. The consumer's argument is what decided
  it: an internal derivation means the tool derives and their tests spell their
  own, which is two wirings of one app that each pass against their own
  reconstruction. The `screen` tool now points at this rather than holding a
  copy.
- **`:web/page` → `:app/entry`**, 42 forms. It belongs to the always-on `app`
  owner because it names an app's entry across BOTH app types — and because
  `:web/` would become actively wrong at wave 3.
- **`build!` emits the browser entry** for any webapp store with a marked entry,
  under `cljs-src/native/client.cljs`, plus the collision refusal for a store
  namespace of that name.

**What the plan got wrong, recorded because the correction is the useful part.**

The migration was described as costing the consumer their canned performer.
It does not: `dom/mount!` merges its own plug-ins OVER the declaration, so
fixture data can stay in the entry and the browser overrides it. That made
"generate the launcher" and "stop the tool showing canned data" separable
questions, and only the first was in this wave.

And the exposure of the escaping bug found en route was described as belonging
to a WAVE. It does not: it belongs to three VERBS — `edit_rename`,
`edit_move_forms`, `ns_rename` — applied to a store's own code, which is
ordinary work rather than a migration. A keyword sweep reaches none of them.
That correction came from the consumer, who verified it against their own jar
rather than accepting it.

---

## D-cljnx-address (2026-08-23) — the browser's external interface is an ADDRESS, and the response survives the trip

**Decision.** `slopp.cljnx` takes a starting url (`(open! app "/things/42")`,
`screen {url}`), keeps the http response's STATUS as a field, and follows
redirects. The guiding rule stated once so later additions can be judged
against it: **the closer the interface an agent drives is to a browser's, the
better it drives it.** A session that opens at no url, reachable only through
a `{:visit …}` step, is a state a browser is never in.

**What was actually wrong was that http's adapter threw the response away.**
`slopp.http/driver` kept `(:body resp)` and rendered anything non-hiccup as its
status. That discarded two facts the caller needed, and each failure is a
different shape:

- **The status became a SENTENCE.** `HTTP 404` on the screen is right for a
  reader and useless for an assertion, so "does this 404" was a whole-page
  `str/includes?` — one keystroke from asserting nothing in particular, which
  is the exact failure `lines` was split into two faces to prevent. A screen
  fact that has a number should be readable as a number.
- **`Location` was dropped, so no redirect was ever followed.**
  Post-redirect-get did not work at all, and `:path` reported the url asked for
  rather than the one arrived at — the one thing a browser's address bar is
  never wrong about. `dispatch/handle!` had been passing redirects through the
  whole time; nothing downstream could see them.

**`:document` widened to hiccup-OR-response, and the discrimination is
`vector?` vs `map?`.** Top-level hiccup is never a map, so the two shapes
cannot be confused — which matters because the OTHER producer of a document
(`slopp.webapp`'s driver, and any hand-written page) must keep answering plain
hiccup and knowing nothing about http. That is the property that lets the fake
browser belong to no capability, and widening a contract is exactly where it
would be lost by accident. A map without `:status` refuses rather than
rendering blank.

**The non-hiccup fallback MOVED from the adapter into the browser.** It renders
a status, so it belongs where the status is known; and a hiccup document has no
status to render, so applying it there would invent a `200` nobody asked for.

**Loop detection precedes the hop cap, and this is the load-bearing part.**
Two urls pointing at each other is what a misconfigured sign-in redirect looks
like and is far and away the common case. A cap alone reports it as "too many
hops", which sends the reader looking for a long chain that does not exist —
the same class as a checker naming a symptom instead of the thing it found. So
the cycle is named (`/a -> /b -> /a`), and the cap below it says explicitly
that these are all different urls.

**Deliberately NOT built, each with its reason.**

- **A session held across tool calls.** `drive!` refuses one on purpose: a
  session kept between scripts makes the same call answer differently depending
  on what ran before it, and re-running the script buys that a screen you
  looked at is one you can PIN, because the pin IS the script. This was first
  written up as the biggest shortfall and it is not a shortfall — the thing
  that should look like a browser is the ADDRESS, not a session handle.
- **Identity/headers on a session.** `http/driver` sends no headers and
  `slopp.http.auth` resolves identity FROM headers, so every driven request is
  anonymous and no auth-gated screen is reachable. Real, open, and a separate
  change.
- **A back button.** Revisit once the url stack has a use.

**Two stale docstrings found and fixed en route, both the same shape as the
change itself.** `screen!` claimed `:trace` did "prefix re-drives" while
`drive-code` explained at length why it deliberately does not — a docstring
describing a design that had been replaced. And the `screen` tool's own
description pointed at `slopp.http.screen/drive!`, a namespace wave 1 retired.
Neither could be caught by anything: prose is not checked against the code it
describes.

### Addendum (2026-08-23) — the consumer measured the BEFORE, and it was worse than described

slopp-ui measured the old behaviour on a real 302 before taking the fix, in the
one window where that was possible (their jar behind, the new one on disk).
Both corrections are theirs, and both changed something.

**The body was not kept either.** The account above said the driver kept
`(:body resp)`. What it actually did was `pr-str` a non-hiccup body, so a string
body arrived DOUBLE-escaped — `pr-str`'s quotes and escapes, then the reader's
own HTML-escaping:

```
HTTP 302
<pre>"&lt;a href=\"/p/demo/\"&gt;continue&lt;/a&gt;"</pre>
```

That is a stronger fact than the one it replaces. The old behaviour did not
degrade a redirect to "a page you follow by hand" — it degraded it to a page
whose only actionable element was **not clickable**, so there was no manual
workaround at all. `document-body` now prints a STRING as itself; `pr-str`
stays for data, which has a shape worth keeping.

**And it is the whole non-2xx class**, not redirects — a 404 rendered the same
way — which is consistent with the fix moving the whole response rather than
special-casing `Location`.

**An UNASSEMBLED context answered 404 for every path.** Handing
`{:http/namespaces … :http/routes …}` — the shape `serve!` documents — straight
to `driver` or `handle!` produced a correct answer to a question nobody asked:
no derived route table means every path genuinely misses. It cost three
attempts and reads as a bug in the CALLER'S routes, which is where they looked.

`dispatch/handle!` now refuses it. **The tell is a declaration, not a guess** —
`context` READS `:http/namespaces` and does not pass it on, so a ctx carrying
one provably never went through it, and a hand-built `{:http/routes […]}` stays
legitimate. That is the difference between this and the coincidence tests
`D-rule-grounding` forbids.

**The shape both of these share, stated because it is now the fourth instance
in two days.** The consumer's own words, on the stale-jar build that printed a
STALE warning and exited 0:

> An exit code that cannot distinguish "built what you meant" from "built
> something" is the same thing as a check whose output cannot vary. The warning
> line was the only varying part of that output and it lost to the part that
> never varies.

Same family as: a bind result that cannot distinguish a mounted app from an
empty one; a status that could only be asserted by searching prose; a 404 that
cannot distinguish "your route is wrong" from "your context is not a context".
**When a signal cannot vary with the thing it is supposed to report, the
invariant part is what gets read.**

Open and unfixed: `uber` knows the head it is jarring and knows `store.db`
moved after it, prints the comparison, and still exits 0. Left alone
deliberately — `build.clj` is a file humans own here, and turning a warning
into a failure is not a change to make silently.

---

## D-rest-path (2026-08-23, user decision) — a REST api and general HTTP content are two kinds of route, and an endpoint never says who may call it

Two decisions from one root cause, taken together because the second is only
reachable once the first exists.

**The root cause.** `:http/path` was slopp's only path declaration, so a REST
endpoint and a stylesheet were the same kind of thing to every gate. Once a
store enabled `rest`, `rest-endpoint-schema` asked EVERY route for a
`:rest/response` — including the pages. So a page declared `:rest/response
:string`, which is a lie about a `text/css` body, and then `:rest/client false`
to suppress the typed fetch wrapper that followed. Measured in the consuming
store: **11 endpoints, 10 carrying the flag, 1 recording why.** Nine bare
copies of one flag in a store where an `^:unused-ok` carries a paragraph — the
shape that gets copied forward without anyone re-deciding it, and how
`/api/projects` and `/api/deregister` came to be flagged beside a stylesheet.

### Decision 1 — `:rest/path` declares an API; `:http/path` declares content

```clj
^{:rest/path "/api/orders" :http/method :get :http/auth :public
  :rest/response contracts/order-list}          ; an API

^{:http/path "/css/app.css" :http/method :get :http/auth :public}   ; content
```

**`rest.prefix`**, a capability key, default `"/api"`. Three refusals at the
write, all grounded in a DECLARATION rather than a coincidence: both markers on
one form; `:rest/path` outside the prefix; `:http/path` inside it.

**The prefix rule earns its place beyond the marker.** It makes `/api/*` mean
something to a proxy, a CSP or a reader WITHOUT consulting metadata — the
consuming store's hub proxies `/p/:slug/api/*path` on exactly that assumption,
and nothing was enforcing it. Configurable rather than hard-coded because a
real API may live at `/v1` or `/graphql`, and one answer per store keeps the
partition total.

**Which gate asks what.** `:rest/response` is a `:rest/path` question alone —
that is the whole point. Everything about being REACHABLE stays on both:
`:http/auth` (default-deny), route collision, safe-GET, declared effects,
declared context, unknown group. `edit.http/route-path` is the ONE accessor
every reachability gate asks, because a gate reading one marker leaves the
other kind ungated — a gap for most of them and a HOLE for auth.

**Accepted cost, stated so it is not rediscovered as a bug.** An http-only
store may serve `/api/foo` as a page today, and enabling `rest` later refuses
it. That is the partition becoming total.

**What could not be verified here.** slopp's own store is 100% REST — ten
endpoints, all under `/api/`, ZERO content endpoints. So every page-side
assertion is fixture-only, and the consuming store's five content endpoints are
the only real ones. Building a page-versus-API distinction in a store with no
pages is the *works on exactly the store it was developed on* hazard pointed
the other way, and the milestone says so rather than implying coverage.

### Decision 2 — `:rest/client` is retired; `:rest/media-type` says what an endpoint ANSWERS

**The principle, stated by the user and load-bearing for the rest of this
entry:** *the goal of an API is to be usable by anyone, anywhere.* An endpoint
does not know who will call it, so whether to generate a client is the
generating CONSUMER's question, asked at generation time against a document.

`:rest/client false` put one consumer's answer on the producer's declaration —
and reached every OTHER consumer too, including out of the published document.
A public, schema'd, JSON-answering endpoint went missing from its own app's API
documentation because one consumer wanted no wrapper. That half was never asked
for.

**Its three real uses each dissolved rather than needing a replacement:**

| endpoint | claimed reason | what it actually was |
|---|---|---|
| `/api/projects` | "a wrapper would prefix to `/p/<slug>/`" | one client namespace serving TWO APIs; two namespaces, two bases, no conflict |
| `/api/deregister` | "our browser does not call it" | not a fact about the endpoint |
| `/api/contracts` | "describing the describer is circular" | it answers EDN, and every wrapper called `.json` |

The third is the only producer-side fact in the set, and "no client" was the
wrong name for it. **`:rest/media-type`** — default `application/json` — is
about the endpoint, is checkable, and is positive rather than an opt-out. A
non-JSON endpoint's wrapper reads `.text` and validates the body as it stands;
there is no JSON boundary, so nothing for the json-transformer to cross.

It rides the spec, the published document AND both generators, because the
local and remote producers must not disagree about how to read a body — the
parity a test already pins.

**And it inverts the justification it replaced.** `/api/contracts` is now
generated, which deletes the two request paths the consuming store hand-writes
*because it could not be generated*.

### Two process notes worth keeping

**Order removed the need for migration scaffolding.** A gate enforcing a marker
cannot be renamed atomically with the marker — the gate runs from OLD compiled
code while the sweep rewrites. The last wave hand-rolled a both-spellings
tolerance for that, which rotted into `(or (:http/path m) (:http/path m))`.
This wave needed none: the new gate was built FIRST, knowing both kinds, and
the sweep followed. Same for `:rest/media-type` before retiring `:rest/client`.
The general fix is filed as *a self-enforcing rename judges its END state*.

**A silent wrong answer that the whole suite was green about.**
`rules.rest/contracts-report` still read `:http/path` after the migration, so
`query_surface`'s `:rest` section reported `:path ""` for every endpoint. 1320
external tests passed, because no test asserted the path in that report. It was
caught by LOOKING at `query_surface` — the only thing that could have.

---

## D-content-value (2026-08-23, user decision) — `:http/path` serves a stored VALUE, and the framework completes an SPA shell

**The driver, in the user's words: "A big driver behind the static work is so
you aren't confused about APIs vs. other dynamic content. It's just APIs at
this point."** Dynamic means API. Nothing else is dynamic. This is a CATEGORY
split, not a preference for static serving — `D-rest-path` separated the two
markers and this decides what the content half actually is.

**A content endpoint is a `def`, not a `defn`.** Its value is what gets served:
hiccup renders as `text/html`, anything else is `str`'d and served as it
stands, and `:http/media-type` is used verbatim when declared. Two consequences
follow necessarily and are worth stating rather than discovering: content
cannot take path params (one value cannot vary by `:slug` — that is what client
routing is for), and it cannot declare `:http/reads` (a def has no request).

**What this buys is that a document is DATA.** A handler returning a page is
opaque; a stored hiccup value can be looked inside. That is what makes the
shell possible at all, and it is why the model is worth more than tidiness.

### The shell: the framework adds exactly two things

`:webapp/shell "<bundle url>"` on a content def declares it the SPA shell.
`slopp.http.html/complete-shell` injects a `<script>` at the end of the
`[:head …]` the app wrote, and stamps the mount prefix on the mount point as
`data-base`.

**Those two and no more, because those are the only two facts a stored value
CANNOT hold** — where the compiled bundle is served, and what prefix this app
is mounted under, which an app served at `/p/x/store` cannot tell from its own
url. Everything else about the document — title, lang, meta, stylesheets — is
hiccup the app already holds, which is why `page` retired with nothing
replacing it and why the `:http/title` / `:http/head` markers that looked
necessary were never built.

**Refusal, at ASSEMBLY.** A shell with no `[:head …]` or no mount point
refuses when `slopp.http/context` is built, not on the first request. The
symptom of getting it wrong is a blank page, and a blank page implicates
everything — the bundle, the compile, the router, the app's own code. So it
refuses where somebody is watching, naming the shape.

Every shell is completed once at assembly and the answer thrown away; it is
completed AGAIN per request. That is deliberate and is not a missing cache: a
value frozen at assembly would not see a redefined content def, and this store
hot-reloads.

### What is NOT joined, and is known not to be

`slopp.http.html` writes `id="app"` and `data-base`; `slopp.webapp.dom/mount!`
reads exactly those two names. Nothing joins them, and nothing can here:
`slopp.webapp.dom` is `:cljs` and slopp has no runner for it. A rename on
either side compiles, serves, and renders a blank page. Registered as the
`:webapp/shell` crossing's `:blind`, with the bundle url beside it — that value
is a marker, not hiccup, so `http-dangling-route-refs` does not check it and a
typo is a script tag pointing at a 404.

The alternative — http requiring webapp so the convention has one home — was
rejected: it would make the general HTTP capability depend on the browser one.
There is precedent for the direction taken: `slopp.http.routes/from-namespaces`
already reads `:webapp/client-routes`, so route assembly reading a `:webapp/*`
declaration is the established shape rather than a new one.

### The transition hazard, and why the gate is not built yet

A `defn` under `:http/path` is now a value slopp serves, so it renders as the
string of its own function object. This store's own `t-mine` fixture did
exactly that, silently, the moment the dispatcher branch landed. **The gate
that refuses `:http/path` on a `defn` is deliberately LAST** — it must land
after serving works, or every existing content `defn` in every store refuses
before it has anywhere to go.

An earlier attempt at that gate was deleted rather than kept (`d35493`): it
refused `:http/reads`/`:http/effects`, which is the right check for "a handler
that computes" and the wrong one for "content is a stored value" — under this
model both are impossible by construction, so the check could never fire.

## D-closed-request (2026-08-24, user decision) — a request contract is CLOSED, and this REVERSES a stated decision

**Nathan: "I want the query maps to be closed so we aren't sending along or
allowing invalid keys."**

`decode-request` now judges the carrier merge against a top-level-closed
schema. A key `:rest/request` does not name is a 400 that names the key.

### What it reverses, and why the old reason had expired

`contract-test/a-contract-covers-everything-the-caller-SENDS` asserted the
opposite, with a reason:

> the contract says what is REQUIRED, not what is forbidden, and a link
> carrying a tracking parameter must not 400

**That reason was about PAGES, and `D-rest-path` retired the case a day
earlier.** A page is general HTTP content now: it declares no contract, so
`decode-request` sees a nil schema and passes every carrier through. `?utm=x`
on a link never reaches this function. What is left under it is a typed API,
where an undeclared key is not a tracking parameter — it is a caller sending
something the contract does not describe.

So this is a construct outliving its reason rather than a decision being
overruled, which is the distinction `A construct that survives its own reason`
in the disciplines exists to make. The old reason is quoted in the test that
now asserts the opposite, so a reader meets both.

### The measured case

A generated builder forwarded its consumer's own route param — `?slug=demo` —
to a service that never asked for it. Correct response, correct render, and the
only witness was the other service's access log. Reported by the consuming
store, which found it in its own keystone test.

### SCOPED to an API REQUEST — Nathan, 2026-08-24

> the malli schemas should only be closed for APIs. The previous decision about
> what is REQUIRED is still true for all other schemas.

So the reversal is narrow, and the narrowness is the decision rather than a
side effect of where the code sits. Three places it deliberately does NOT
reach, named in `closed-map`'s docstring so nobody generalises from the one
that changed:

- **RESPONSES.** `check-response` judges the server's own answer. A server
  sending a field it did not advertise is a different question from a caller
  sending one nobody asked for, and this does not settle it.
- **`:malli/schema` on ordinary functions.** A function's schema describes
  arguments, and a map argument is the caller's own data structure.
- **content.** A page declares no contract, so the schema is nil and every
  carrier passes through. This is what makes closing a REQUEST safe and it was
  not true before `:rest/path` and `:http/path` split.

Pinned by `contract-test/closing-is-scoped-to-a-REQUEST-and-the-boundary-is-DELIBERATE`,
because a boundary stated only in prose is one the next reader argues past.

**Measured while confirming that test can fail:** closing RESPONSES too breaks
`api.endpoints-test/every-endpoint-HONOURS-its-contract-once-SERIALIZED` —
slopp's own `/api/form/:id` answers with fields its `:rest/response` does not
declare. So the response question is not academic: closing there would surface
real under-declared contracts, starting with ours. Also worth knowing before
anyone tries it: malli's `humanize` renders a closed-schema failure as `nil`, so
the refusal would arrive with an empty explanation.

### Closed at the TOP LEVEL only

`closed-map` closes the merge and nothing nested. `malli.util/closed-schema`
closes every nested map too, which would refuse the keys of a free-form blob an
author declared on purpose — a strictness nobody wrote, applied where they had
already said what they meant. The question here is only about the carriers:
path, query and body merge into one map, and that map is what the contract
enumerates.

### The nesting question, asked and answered NO

Nathan asked whether the flat params map should become
`{:path-params … :query-params … }` plus room for headers and auth, since the
builder currently splits by leftover.

**The split is not the defect; the openness was.** `decode-request`'s own
contract is the reason: *"`:rest/request` describes what the caller sends, not
where it travels … One schema, three possible carriers, judged as one map."*
The server merges path, query and body and judges the merge. A nested client
argument would make the two ends disagree about what `:rest/request` means, and
the carrier for a key is already derivable — the path pattern says which keys
are segments, the method says body or query.

**Headers and auth are a different question and the answer there may well be
yes.** They are not "what the caller sends" as data; they are transport and
identity, they belong on the REQUEST map rather than inside the params, and
nothing accommodates them today.

**One real asymmetry surfaced while checking this and is NOT fixed:** the query
branch emits `(dissoc params <segments>)` and the body branch emits `params`
whole, so a POST to `/api/things/:id` sends `:id` in the path AND in the body.
`decode-request` merges with path last so the value is right, but its own
docstring says overlap should not arise. Filed, not built.

## D-doc-markdown (2026-08-24, user decision) — docstrings are MARKDOWN, and slopp ships no renderer

**Nathan: "You just define that it is markdown."** And, separately and as
firmly: *"We shouldn't ship a renderer in slopp, it should be up to slopp-ui to
do that as part of its UI."*

### It is a declaration of practice, not a permission

Reported by the consuming store after a three-paragraph endpoint docstring
rendered as one wall of text on their API page. Their measurement over 189
public docstrings: 158 backticks, 154 paragraph breaks, 92 bold, 91 wikilinks,
8 bullet lists. Five of six were already markdown, transmitted by imitation of
slopp's own register, declared nowhere.

Measured here, the decisive case: **seven forms carry markdown TABLES** —
`cljnx/lines`, `cljnx/driver-for`, `git/divergence`, `index.refs/mention-kinds`,
`webapp/request-init`, `webapp.dom/mount!`, `api.model/graph-node-cap`. A table
is unreadable as plain text, so the convention was already load-bearing.

The undeclared state costs in both directions and that is why the answer had to
be one or the other: a renderer treating it as plain text loses the author's
structure, and one rendering it when the author meant literal text mangles a
`*` in prose.

### Where it is declared

The SKILL, because that is what reaches a consumer. A rule about how to write a
docstring belongs where every store's agent reads it; recording it only here
would be the routing failure this repo has a museum of.

### NO RENDERER, and the line is not about cost

markdown→hiccup is `:cljc`, is wanted by every store that displays another's
contract, and is exactly the shape of the percent-decoding helper this project
DID adopt. The consuming store made that argument well and it was refused
anyway, which is the part worth recording: **slopp publishes data and declines
to decide what it looks like.** Displaying a docstring is the displaying
application's job. A store rendering another store's contract owns that end.

The percent-decoding precedent does not transfer because decoding has ONE
correct answer and rendering does not — a UI decides what a table, a bullet
list and a code span should look like in its own design, and a shipped renderer
would be slopp making that decision for every consumer.

### The `[[wikilink]]` extension — kept, declared, NOT swept

`[[name]]` is not markdown; CommonMark renders it as literal `[[name]]`. It is
the Clojure ecosystem's docstring cross-reference convention (cljdoc), and
**nothing in slopp reads it** — no rule, no index, no derivation. It is prose
with no machine reader, so the format is free to change and changing it is a
pure text sweep across every docstring in every store.

Asked for a markdown-compatible alternative, the honest answer is
`[name](#slopp.ns/name)`: standard link syntax, a FRAGMENT target so there is no
scheme for a sanitizer to strip and no URL for slopp to invent, and it degrades
to a correctly-labelled link that goes nowhere. The consumer maps the fragment
to its own URL space, which is the same split as the no-renderer decision.

Not adopted, and the reason is that the benefit is small and named: today a
strict renderer shows `[[call!]]` — the name is visible and readable, only the
link is missing. Trading that for a sweep of every docstring in every store buys
a link. **If a store's UI wants links, it can resolve `[[name]]` itself; the
declaration now tells it that this is the one non-markdown construct to expect.**

**Expiry:** if a second consumer appears that renders contracts and cannot
resolve `[[…]]`, the fragment form is the migration and this decision is what to
revisit.

## D-outbound-rest (2026-08-24, user decision) — a server calls an upstream through the request map generation already produces

**Nathan: "now do the server-outbound REST framework."** Behind it, his earlier
statement of the ask: *"We DO want the framework to be providing a way for you
to make http requests to other rest services without having to rely on raw http
access yourself. Slopp should be providing the framework that makes that easy
for you to do and safe (it handles security aspects of it)."*

### The gap, as the consuming store measured it

```
slopp.http.client/request + fake-requester   the ENTIRE outbound surface
generate_client                              :cljs / :cljc — browser only
```

So slopp generated a typed client for the BROWSER and handed the SERVER a
socket. Their hub renders an upstream's contract on a screen and forwards to
that same upstream untyped — about forty lines of url building, error mapping
and passthrough, none of it about their application, and nothing at all for
security.

### The shape, and why it is small

**The typed halves already existed.** `render-request` emits a `:cljc` builder
requiring nothing; `render-check` emits a `:cljc` response check. Both run on
the JVM today. What was missing was a PERFORMER — the server's analogue of
`:webapp/call`.

So `slopp.rest.client/call!` is glue over decisions already made:
`webapp/request-url` builds the path (segment-wise, percent-encoded),
`webapp/request-init` names the encoder, `http.client/request` performs.
**One request shape, two performers** — the same split `slopp.cljnx` made for
drivers, and the reason the module edge `slopp.rest → slopp.webapp` is
deliberate: a second copy of those decisions on this side would agree until the
first change.

### What "safe" was answered with, and what it was NOT

Handled: a timeout ALWAYS set (the port makes it optional, so the default was
wait-forever); a path that would name another origin REFUSED rather than
cleaned; redirects not followed (the JDK default, stated so it stays true); EDN
read with `clojure.edn/read-string` so an upstream cannot evaluate reader tags
in this process; no header in any `ex-data`.

**Not handled, and named in the docstring so nobody assumes it:** retry,
backoff, circuit breaking, a host allowlist beyond the base, and redaction of a
credential a caller puts in a query parameter. Each is a policy with more than
one right answer and no instance behind it. Inventing them would be the
framework guessing for every store.

### Two failure lines, both preserved rather than re-decided

**An answered request RETURNS whatever its status; an unanswered one THROWS.**
That is `http.client/request`'s rule and its docstring defends it at length: a
far side that refused and a far side that was never there are different facts.
The consuming store's narrow catch exists because a broad one once reported
THEIR bug as "the project has probably stopped" — so wrapping the port's
`:http/error :unreachable` in a second shape would make that catch wrong again.

**A failed `:check` THROWS**, typed `:rest/error :contract`. Not the caller's
judgement: the upstream broke a promise it publishes, and there is no branch to
take that is not "this is broken". A non-2xx is deliberately NOT checked — a
500's body is an error document, not the shape the endpoint published, and
reporting a contract violation for it would send a reader to the wrong end of
the wire.

### What this retires, and it is the better kind

`:rest/unconstrained-ok` on a forwarding endpoint says the response cannot be
named because another service's bytes pass through. With a typed outbound call
that is no longer true: the response is constrained by the upstream's contract,
which the forwarding store has already fetched. The escape becomes UNNECESSARY
rather than discharged — the consuming store's own argument, and the same one
that retired `:rest/client`.

**Expiry:** if a store needs retry or an allowlist, this decision is what to
revisit — the answer will be a declared policy on the upstream value, not a
default invented here.

### The paragraph repaired a caller that will never call it

The consuming store checked whether `call!` could replace their `forward` and
answered NO, correctly, on two grounds that stand on slopp's own code: a proxy
for `*path` has no BUILDER (the endpoint is a wildcard the browser chose), and
`call!` DECODES while a proxy must not (`:http/raw` exists to stop exactly that
round trip). **A proxy's correctness is that it does not understand what it
carries**, so this was never a typed call missing a client.

They also corrected a claim I had repeated from their own earlier message —
that the hub "already has the upstream's contract". `generate_client` fetches a
contract at DEV time into a store; the RUNNING hub fetches nothing. So
`:rest/unconstrained-ok` on that endpoint STAYS: its cause is not removed, and
the retirement I announced was two, not three.

Then, chasing the timeout paragraph through their side, they found their only
outbound call site had no `:http/timeout-ms` at all. Checking slopp: **two of
ours had none either.** `slopp.webdev.cljs/fetch-contract` (a dev tool that
would hang with nothing said) and `slopp.http.jwks/fetch-jwks!` — which runs at
server STARTUP on the auth path, and whose docstring promises "a misconfigured
issuer should fail loudly at startup, not 401 mysteriously forever". A hang is
the loudest failure to experience and the quietest to read.

So `slopp.http.client/default-timeout-ms` is now PUBLISHED and still not
applied. Published, because three call sites in two stores independently
invented no number. Not applied, because `request`'s no-policy stance is
defended at length and reaching into a caller's map to add a key they did not
write is exactly what it refuses. **Its own expiry is written down**: if callers
keep forgetting, make the port apply it and accept the policy — decided once,
with the call sites visible, rather than by each of them separately.

Worth keeping as a shape: **the finding travelled the wrong way to be luck.** It
did not come from the fix; it came from a paragraph explaining a fix that store
cannot use. Stating a default out loud repaired callers that will never call the
thing the default belongs to.

## D-response-document (2026-08-24, user decision) — `:rest/response` describes the DOCUMENT, and `:rest/media-type` owns the envelope

**Nathan: "now do the /api/contracts :rest/response :string problem."**

Reported by the consuming store, who declined to propose a fix and were right
not to — the answer was one layer under where the symptom appeared.

### The symptom, measured through a real consumer

`/api/contracts` declared `:rest/response :string`. It answers EDN and
serializes its own body (`:http/raw`, `pr-str`), so:

```
the check on the raw body      -> holds
the check on the decoded map   -> "should be a string"
```

and the decoded map is what a consumer works with. **So the check either failed
on what the consumer had, or passed by asserting that text is text.** Their
words for it: `rest-unconstrained-contract` one level up — a type that says
nothing, and invisible to the rule that reports exactly that, because
`unconstrained-contract-fields` looks for `:map` and `:any` and `:string` is
neither.

### The cause, which is not the endpoint

Their diagnosis was right: *the schema language was describing two different
things at once — the envelope and the document.* And `:rest/media-type` already
owns the envelope.

**For every ORDINARY endpoint the schema already described the document.**
`check-response` round-trips a handler's return through JSON before judging,
precisely so it judges what arrives. Only an endpoint that serializes its OWN
body put the bytes under the schema — so `:string` was not a bad declaration on
a good design, it was the only true declaration available in a design that had
never distinguished the two.

### The fix

`slopp.rest.contract/arrived` — one function that says what a consumer ends up
holding, given how the endpoint answers:

| how it answers | what arrives |
|---|---|
| ordinary | the handler's value through a real JSON round trip |
| raw + `application/edn` | the envelope read with `clojure.edn/read-string` |
| raw + `application/json` | the envelope parsed |
| raw + anything else | the string as it stands |

That last row is what makes `:string` honest again: an endpoint that really
answers `text/plain` says `:string` and is really checked.

`check-response` gained an optional third argument for it; `dispatch` passes
`{:raw? :media-type}` from the route row, because that is the only place
holding both the schema and the envelope.

**The client half moved with it.** `render-check` transformed through
`mt/json-transformer` unconditionally — right for JSON, a second opinion about
types for EDN, which never suffered the damage the transformer exists to undo.
`render-wrapper` had already learned this when `:rest/media-type` landed; the
check had not, and **the mistake stayed invisible precisely because the one EDN
endpoint declared `:string`** — a string survives any transformer, so the
question was unreachable until the endpoint got a real schema. Fixing the
declaration is what exposed the generator.

### `:rest/unconstrained-ok` on it, and it is PERMANENT

Three fields are `:any`: `:request` and `:response` carry malli SCHEMAS as
values, and `:auth` is an app's own policy grammar. The tighter check a reader
reaches for is a predicate — *is this a schema malli can build?* — and **a
predicate cannot be PUBLISHED.** This document is data a consumer reads and
generates from, so a `[:fn …]` in it arrives as something they cannot evaluate
or trust. The constraint is the publishing, and publishing is the point. No
expiry, per `D-doc-markdown`'s neighbouring rule about inventing one.

Every other entry carries a `:doc`, because `rest-undocumented-contract` asked
and was right to: this document is all a consumer generating a client can read,
and a type says shape rather than meaning.

## D-contract-v2 (2026-08-25, user decision) — the version moves, and there is no compatibility path

**Nathan: "there is no consumer besides slopp-ui at this point, and I'd rather
manually update them than add in compatibility code."**

### What version 2 contains, and why it is ONE bump

1. `:handler`, `:doc`, `:media-type` and `:auth` become REQUIRED. All four
   arrived while the version stayed 1, so a version-1 document was never one
   shape and a consumer could not tell which it was about to get.
2. `:effectful?` — the fact a consumer wants before calling anything.

One bump for both, because every bump costs a consumer a migration and there is
no reason to charge them twice.

### `:effectful?` is DERIVED, and that decision is the interesting one

Publishing the `:http/effectful` marker verbatim would have shipped `false`
everywhere: **slopp's own ten endpoints declare it zero times.** A consuming
store's arming gate would have gone from inert-on-nil to inert-on-FALSE, which
is worse — false looks like an answer.

So it is `(or (:http/effectful m) (not (safe-method? method)))`. Over-warns on a
POST that only searches, which is the safe direction for a gate whose job is to
make someone press twice.

**`:http/effects` is deliberately not a third arm**: `http-unsafe-get` refuses a
`:get`/`:head` that declares any, so effects imply a non-safe method already and
that arm could never decide anything. Caught before writing it, which is the
`a check whose output cannot vary` shape one layer earlier than usual.

### No compatibility path, deliberately

`supported-contract-version` stays a single number. A document at any other
version yields no wrappers and a problem naming both — **which is the field
finally doing its job.** Its expiry is written into it: the day a second
consumer exists, or a producer that cannot be upgraded in step, it becomes a
set and `contract->plan` grows the defaults. Not before.

### Two things the consuming store taught after it shipped

**A version bump is the one change that cannot be discovered by regenerating,
because regenerating is what it blocks.** Their generator refused —
`{:issue :unsupported-contract-version :version 2 :supported 1}` — and refused
WITHOUT writing, leaving their three `wire.*` namespaces intact. A generator
that had half-written would have been much worse. But it means a bump has an
ordering constraint slopp did not have before: every consumer's generator
refuses until they take the new jar.

**Prefer deriving from a field that has been there since version 1 over
consuming a new one.** They fixed their arming gate from `:method` before this
landed. Had they waited for `:effectful?`, the gate would work against slopp2
and still be inert for every version-1 producer — including their own hub,
whose only POST endpoints are on it. Their sentence: *the derivation works on
documents older than the decision.* That is a better answer than negotiating a
version, and it is available more often than it looks.

### And a process failure worth recording, since it is mine

I told the consuming store that publishing `:effectful?` needed a conversation
BECAUSE they were the affected consumer — then had that conversation with
Nathan, shipped, and did not tell them. Because the dev host is `--live`, the
landed change published version 2 from slopp's own server within minutes, and
they found out from a tool refusing to run.

**Landing a wire-format change on a `--live` host is a deploy.** `done` lands to
the trunk, the host reloads from the trunk, and any consumer pointed at it sees
the new shape immediately — with no announcement in between and no step that
looks like one.


## D-clean-over-compatible (2026-08-25, user decision) — a standing priority

**Nathan: "don't pay too much attention to backwards compatibility and keeping
them going efficiently. The big goal is clean code."**

A steer about how to WEIGH, so it is written down rather than re-derived per
change. It settles a tension this repo had been resolving the other way by
default, one accommodation at a time.

### What it licenses

- Delete a mechanism whose cause is gone, rather than keeping it working while
  its users migrate.
- Move a wire version rather than adding optional keys to avoid moving it.
- One supported version rather than a set, until a second consumer exists.
- Refuse rather than tolerate, when the tolerant path is a shim.

### What it does NOT license, and the line matters

**Silence.** A consuming store must still be TOLD what changed — that is what
`deleted-markers`, `retired-markers` and the refusal messages are for, and none
of them is a compatibility path. Breaking cleanly and announcing it is the
target; breaking quietly is a different failure that this steer does not excuse.
`D-contract-v2` records the version bump reaching a consumer through the live
host with no announcement, and that stays a mistake under this decision rather
than becoming acceptable.

The distinction: **compatibility code lets old callers keep working; a ledger
lets them find out.** Drop the first, keep the second.

### Applied immediately

`:webapp/from-origin` — deleted, not deprecated. It was an escape from
`:webapp/base` being one scalar per APP; once a REQUEST names its own base the
escape has no cause. It had been kept "until its users migrate", which is
exactly the weighting this decision reverses. Nothing reads it now, so a request
still carrying it is addressed under the app's base like any other, and
`deleted-markers` carries the instruction.

**Expiry:** this is about a codebase with ONE consumer, updated by hand. When
slopp has adopters who cannot be told to upgrade in step, the weighting changes
and this decision is what to revisit — not silently, since that is the failure
mode it is currently guarding against in the other direction.
