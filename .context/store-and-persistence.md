# Store & persistence

## In-memory model (`slopp.store` — pure functions over values)

- A namespace = ordered vector of **forms**:
  `{:id :kind :form :name :names :node}`, plus an optional `:comment` — the
  block rendered directly above that form. `:node` is a rewrite-clj CST node.
  That is the whole model.
- **There used to be a second element kind, `{:kind :sep :node}`** — whitespace,
  blank lines and comments, positional and idless, kept so rendering could be
  lossless. Lumping those together forced byte preservation: a comment stored
  positionally is content the delta log never recorded, which is why a
  commit point had to snapshot every namespace's bytes to keep it. Splitting them
  dissolved the requirement. **The space between forms is RENDERING** —
  `render-ns` supplies one blank line, nothing stores it — and **a comment is
  CONTENT owned by a form**, travelling in that form's delta like anything
  else. `store/fold-comments` is the single place the old shape becomes the
  new one; it runs on `ingest` AND on db load, so any store migrates itself
  the first time it is opened. A `#_` discard folds too: it is code, and
  dropping it silently would be the one genuinely bad outcome.
  Rationale and measurements: `ideas/done/whitespace-is-rendering.md`.
- **A form defines a SET of names (`:names`, via `form-symbols`) — #128.**
  `:name` (via `form-symbol` + `def-heads`) is the PRIMARY name, for labels;
  `:names` is what addressing uses. They differ for most def forms, and the old
  one-form-↔-one-name premise was wrong in BOTH directions:

  | form | `:name` | `:names` |
  |---|---|---|
  | `(defn f …)` / `(defmulti area …)` | `f` / `area` | `#{f}` / `#{area}` |
  | `(defmethod area :square …)` | **nil** | **`#{}`** |
  | `(extend-type String P …)` | nil | `#{}` |
  | `(defprotocol P (m …) (n …))` | `P` | `#{P m n}` |
  | `(defrecord R [x])` | `R` | `#{R ->R map->R}` |
  | `(deftype T [x])` | `T` | `#{T ->T}` |

  **`defmethod` is NOT a def-head.** Its second element is the multimethod it
  REGISTERS ONTO, not a name it defines — it is a registration, like
  `extend-type`/`extend-protocol`, which were never in the set. Including it put
  three forms named `area` in one ns, and everything downstream broke silently:
  `form-named` returns the FIRST (so methods were unreachable by every
  name-keyed tool); `refs/cold-load-order` **DROPPED** forms, the defmulti among
  them (`{:order ["f0" "f3"]}` for a 4-form ns); `static-refs` resolved
  `:from-form` last-wins against `:to-form` first-wins in adjacent lines. Note
  the edit layer ALREADY refuses duplicate names (`api/add-form!`, twice), so
  ingest was the only door and it admitted a state the rest of slopp considers
  illegal.
  **No compound name (`area:square`) could have worked**: `:` is legal in a
  symbol, so `(defn area:square …)` is a real fn a user can write — and every
  other ASCII-punctuation spelling is legal too (probed). The name space is flat
  and user-owned; you cannot reserve a corner of it.
- **`form-named` matches any of `:names`, or a form ID.** The id match is what
  makes registrations addressable (they define nothing, so an id is their only
  handle) and it fixes a live round-trip bug: `qform` has always LABELLED
  unnamed forms `ns/f4`, and `form-named` could not fetch one back.
- `form-symbols` is **syntactic, per head — deliberately not kondo**: it runs per
  form on every ingest and replay, where a kondo pass costs ~285ms on a large ns.
  Every writer of `:name` must write `:names` too — there are six, all in
  `slopp.store` (`ingest`, `replace-node`, `append-form`, `apply-changeset`,
  `replay-delta` ×2). **Missing `replay-delta` is the sharp one**: the store
  rebuilds from the journal on `open!`, so `->R` would stop being addressable
  after a reopen.
- **`deftest` is a named form on purpose** (tests are addressable/editable).
- Ids: `"f…"` forms, `"d…"` deltas — a prefix plus 48 random bits, minted per
  call by `store/gen-id`. NO counter: a store value carries no allocation
  state, so disjointness across sessions is structural rather than arranged.
  The DB has a UNIQUE constraint on delta ids as a collision backstop.
- Deltas: `{:id :parent :op :ns :prompt ...}` — ops today: `:ingest`,
  `:replace`, `:add`, `:delete`, `:rename` (multi-form, `:form-ids`),
  `:verify` (result attached). `:parent` = previous delta id (linear now,
  DAG-ready). There is no ordering op: see *ORDER IS DERIVED* below.
- Multi-form coordinated edits go through `apply-changeset` → ONE delta over
  N forms (used by rename).
- **ORDER IS DERIVED, never journaled (2026-08-29, `D-derived-order`).** A
  form carries `:rank` — its creation order in the namespace, assigned by
  `ingest` (source position) and `append-form` (one past the highest), never
  rewritten, persisted as `elements.rank` beside `pos`. Its PLACE is
  `slopp.index.refs/derive-order`: the ns form, then any `(declare …)`, then
  the named forms in `cold-load-order` (Kahn over the intra-ns reference
  graph, ties by rank), each followed by the unnamed forms — defmethods,
  registrations — created after it. `store/order-forms` arranges the vector
  to that sequence and writes NOTHING; `engine/try-commit!` arranges every
  namespace a commit rewrites, so `pos` (what the kernel boots by, with no
  graph to derive from) is always the derived position. The `:move` op,
  `move-form`, `reorder-to`, `edit_move` and `edit_add_form before` are gone;
  a `:move` in an older journal (1,048 here) replays as a no-op and crosses a
  merge as applied. Why the rank tiebreak matters: the git projection FOLDS
  the journal to render a commit point's tree, and a tiebreak by "where the
  form sits today" made the derivation depend on the vector's history, so a
  fold (creation order) and the live store (arranged at every write) could
  disagree; rank is the same fact on both sides. `refs/ns-key` hashes the
  forms in rank order rather than the rendered text for the same reason.
- **`:system true` marks a delta the PIPELINE wrote, not the agent** — today
  the auto-require (`ops/auto-require-retry`); it was born for the cold-load
  auto-reorder, which wrote `:move` deltas until order became derived and
  writes nothing now. It exists because a derived view otherwise cannot tell
  housekeeping from intent: `prompt-by-form` takes the last prompt naming a
  form, so the reorder's constant prompt became the recorded WHY of 142 of
  1,898 forms (7%) — on `form-card`, `query_slice`'s cards and the reviewer
  UI alike. The op is not the discriminator (`edit_move` is the same op with a
  real intent) and neither is the absence of `:agent` (measured: 75 genuine
  hand-written asks carry none). It has to be recorded as a fact.
  The log is append-only, so already-written reorders cannot be re-stamped;
  `prompt-by-form` also recognises `store/auto-reorder-prompt`, ONE constant
  owned here and used by the one writer so the two cannot drift.
  **A writer acting on the agent's behalf must mark what it writes**, or it is
  indistinguishable from the agent in every view derived from the log.
- **The value carries NO history (2026-08-29, `D-history-off-the-value`).**
  Measured before the change: `:deltas` was 111.8 MB of a 118 MB store value
  (94%), ~162 MB of live heap growing with every write forever, and 4.6 s of
  the 7.6 s `load-store` — for a list the write path read two scalars from.
  The value now carries `:head` (the line's head delta id), `:line-pos`
  (how many deltas the line holds, the cache cursor), `:recent` (the deltas
  since the last `:commit` plus the `:done` that earned it — the bounded
  window every episode-grain reader needs: `forms-changed-since`,
  `impacted-tests`, `unchanged-since-done`, `session-brief`), `:pending`
  (un-appended deltas), `:prompts` (`{form-id prompt}`, the WHY per form,
  maintained at `record-delta` instead of folded from the log), `:last-write`
  and `:refs` (below). `replay-delta` advances the scalars instead of
  `conj`ing. A value built by `empty-store` still accumulates `:deltas` —
  tests fold small journals by hand — and `store/deltas` on a value that
  has none THROWS, naming the doors, rather than answering `[]` as if the
  line had no history. The doors: `ops/with-history` (a copy of the
  session hydrated from `db/line-deltas`, `:ops [:commit]` when only the
  commit points are wanted), `ops/journal`, and `db/line-deltas` itself, whose
  op filter is SQL so a reader of forty markers never parses the payloads it
  did not ask for. `slopp.read.history` is `:pure` and takes a hydrated
  session; a bare one reaches the throw. After: `load-store` ~3 s (3.5 s
  with the reference index below).
- **Purity is load-bearing:** transactional/atomic behaviors at the api layer
  (e.g. group validation) work by applying store fns to a value and only
  committing the result on success.

## Persistence (`slopp.store.db`, decision C7)

- SQLite at `<dir>/.slopp/store.db`, WAL mode. **ONE file holds every line** —
  see *Lines* below. Tables:
  - `deltas(seq, id UNIQUE, op, ns, parent, payload)` — append-only log;
    everything except id/op/ns/parent lives in the EDN `payload` column
    (exact-reconstruction rule:
    `(merge {:id :op :ns} (edn/read-string payload)) = original`).
    `parent` is a COLUMN as well as a payload key, and the column wins on
    read — it was payload-only until 2026-08-15, which made the DAG
    unreachable from SQL and is why a second line had to be a separate FILE.
  - `elements(line, ns, pos, kind, form_id, name, source, comment, rank)` —
    materialized current state, PER LINE; `source` is the CST's canonical
    serialization (re-parsed on load; must reparse to exactly ONE node —
    asserted). `pos` is the DERIVED position (the kernel boots by it);
    `rank` the creation order it was derived with (filled from `pos` once at
    `open!` for rows older than the column).
  - `lines(id, name, kind, head, base, parent, agent, created_at, used_at,
    status)` — a line is a POINTER to a head delta. A named line is a
    BRANCH; an anonymous one is an agent's THREAD. One row shape, because
    they are one thing.
  - `delta_forms(delta_id, form_id)` (2026-08-29) — one row per form a
    delta names (`db/delta-form-ids`: `:form-id`, `:form-ids`, the keys of
    `:sources`), written in `append!`'s transaction and backfilled once by
    `index-journal-forms!`. History BY FORM is a join, not a parse:
    `sources-at` narrows to the forms asked about, `prompt-for-forms` reads
    the last non-`:system` ask per form.
  - `form_refs(line, ns, seq, from_form, to_ns, to_name, row)` +
    `refs_keys(line, ns, refs_key)` (2026-08-29) — THE REFERENCE GRAPH,
    persisted. The `:refs` index the value carries (`slopp.index.refs`: one
    entry per namespace, keyed on the SHA-256 of its rendered source, rows =
    the edges leaving its forms) is written beside a namespace's elements
    whenever those are (`write-snapshot!` → `persist-refs!`) and read back
    at open (`load-refs`). Per line, like `elements`, and it follows a
    line's view (`copy-view!` / `drop-view!` on fork, land, abandon,
    compact). `ns-refs` uses an entry whose key matches the current source
    and recomputes otherwise, so a stale entry costs one kondo pass and never
    a wrong answer; `done` runs `ops/refresh-index!` before landing so the
    trunk's rows are current. Measured: `refs` cold 9.3 s → 0.43 s; per
    write 1.8 s → 66 ms.
  - `form_reds(form_id, test, n, last_delta)` (2026-08-29) — the RED-AFTER
    index: how many times test T went red in an EPISODE that changed form F.
    `credit-reds!` writes it inside `append!` for every `:verify`/`:observe`
    delta with qualified red failures, finding the episode by a recursive
    walk from the red's parent that stops at the first `:done`; backfilled
    once by `index-journal-reds!`. NOT per line — a red on a thread is
    evidence about the code whether the thread lands or dies. Read by
    `reds-for`, surfaced as `query_depends :red-after`. On this store the
    backfill took 380 ms for 19,500 rows.
  - `measurements(seq, at, kind, delta, payload)` — telemetry OFF the journal
    (`tool-call`, `otel`, …): a measurement never moves a line's head.
  - `meta(k,v)` — git pins, saved remotes, the fold-field registry. No id
    counter: any row here also marks the store as persisted, which is what
    `load-store`'s "nil if empty" reads.
- `persist!` = ONE transaction: delta row + full element rows of the touched
  namespace(s) (multi-ns arity for cross-ns ops like rename) + counter.
  Namespaces are small; full-ns row rewrite keeps write-through trivially
  correct.
- `load-store conn line-id` reconstructs the in-memory store for ONE LINE
  (returns nil if empty). `external/open! {:slopp.ops/dir ...}` loads the
  trunk's AND replays every namespace into a fresh image.

### Lines (2026-08-15) — one file, many views

A **line** is a pointer to a head delta. Branches and threads are the same
row with a different `kind`. Four things follow, and each replaced a reason a
branch used to need its own db file:

- **The write CAS is on the LINE**, not the journal head:
  `UPDATE lines SET head=?, used_at=? WHERE id=? AND head IS ?` — the check
  and the advance in ONE statement, `IS` not `=` so a new line's first write
  (NULL on both sides) matches. Two writers on two lines never contend; two
  on ONE line still do, which is correct — that is the rebase path.
- **`elements` is keyed `(line, ns, pos)`**, so every open line keeps its own
  materialization. `write-snapshot!`'s DELETE carries `AND line = ?`; without
  it, one line's write does not return a wrong answer, it ERASES another
  line's namespace and the result looks like a write that never happened.
- **A line's HISTORY is its ANCESTRY**, walked from its head through the
  `parent` column (23,719 deltas in 69 ms — the walk is not the cost;
  EDN-parsing them was, which is why the value no longer holds them: see
  *The value carries NO history* above). `try-commit!` takes its CAS head
  from the value's `:head`, so a store value loaded for another line yields
  a head that can never match — a line nobody can write to.
- **A split copies the MATERIALIZATION, never the history** — one
  `INSERT … SELECT` over the form rows (~2,481 here) against 23,719 deltas to
  fold. Pinning is what keeps that valid: the base never moves under the new
  line.

**Writes say when the work is still private** (`mcp/thread-hint!`, user ask
2026-08-15). `session_brief` names the thread and nothing else did, so between
orienting and `done` the fact was true and unstated — worst in a long episode,
which is exactly where the brief has scrolled out of context. It fires on the
change that makes the work private, then every 25 un-landed changes, riding the
existing `*hint*` channel.

Two things it got right only on the second try, both worth keeping:

- **Whether a call WROTE is asked of the journal, not of a list of tool
  names.** The first cut gated on `tools/write-tools` — a curated 17-entry set
  that contains `edit_replace_form` and not `edit_subform`. So it fired for
  some writes and not others, which is worse than never firing: it looks like
  it works. The count moving IS the write, and a derived test cannot fall out
  of step with the thing it describes.
- **`*hint*` is bound BEFORE the call and this hint depends on what the call
  did**, so it is bound as a DELAY and `text!` forces it. Memoization makes a
  second render unable to make it speak twice.

**A thread is adopted, not remembered** (2026-08-15). `db/adopt-thread!` is a
SELECT with a create fallback keyed by `(agent, branch)`, so a returning agent
asks the same question and gets the same row — nothing has to be persisted
between sessions beyond the `lines` table itself. Three properties, each of
which a test discriminates rather than merely exercises:

- **It forks at the branch's HEAD and then pins there.** A thread opened after
  the branch moved starts from where the branch is NOW; a thread already open
  does NOT move when re-adopted. Rebasing once, at its own done, is what keeps
  a thread's view stable and its verdict meaningful mid-work.
- **Only an `open` row is adopted.** A landed thread's writes are already on
  the branch and an abandoned one was discarded deliberately; re-entering
  either resurrects a line whose meaning is settled.
- **`kind` is what separates a thread from a branch in a listing, not
  `parent`.** A named branch forked from this one carries the same `parent` as
  every thread on it, so `db/open-threads` filtering on the fork alone would
  report a branch as somebody's private workspace.

Adoption touches `used_at`, which is the store layer's business for a reason
worth stating: a session that is orienting and reading has written nothing, so
a clock that moved only on writes reports a thread idle for exactly as long as
somebody is thinking in it.

**The land is one transaction and two cases** (2026-08-15).
`db/land-thread!` advances the branch's head, replaces the branch's `elements`
from the thread's, and settles the thread `landed` — all three or none, because
a head that moved without its view following is a line that renders source its
own journal disagrees with. The copy DELETEs first: `(line, ns, pos)` says
nothing about which write a row came from, so a thread that REWROTE a namespace
would otherwise leave the branch's older rows beside the newer ones.

`ops.branch/land-thread!` is the loop above it, and the two cases compose:

- **The branch has not moved** — a pure fast-forward, and nothing is
  re-verified, because the content is byte-identical to what `done` just
  graded.
- **The branch moved** — merge the branch INTO the thread first, through the
  same pipeline `branch_merge` uses, then **re-earn the whole in-image
  verdict** against the merged state. The thread's head then has the branch's
  head in its ancestry, so the rest is the fast-forward case. Conflicts or a
  red rebase land NOTHING and leave the thread open holding the merged state.

  **The re-run is the whole suite and not the merge's own scope, and that
  difference is the whole point.** The merge verifies the namespaces it
  CARRIED; the test that breaks is normally in the namespace that CALLS them,
  which the merge never names. Shipped narrow first and caught by a test
  written for a different question: A's namespace called B's, B landed a
  behaviour change, the merge re-verified B's namespace (green — B tested it),
  and the land advanced main to a state where A's test failed. A verdict that
  hides its own failure, which is the one shape here worth paying for.

  **A rebase must refresh before it merges.** Ids are minted from the store
  VALUE, and this session stopped counting when the other agent started — so
  the merge mints deltas the branch already used, `append!` returns false as a
  lost race, and the loop retries with the same stale counter until it hits its
  bound and reports CONTENTION. Same phase-3b hazard, arriving at the one path
  whose whole job is to cross lines; `refresh-cache!` raises the floor
  unconditionally, which is why it is the fix.

Losing the CAS is a RETRY, not a failure: another agent landed between the
reconcile and the advance, which is the ordinary shape of a shared branch.
Bounded, so a branch under continuous landing says so rather than spinning.

**Adoption is where the model becomes the default** (2026-08-15).
`engine/session-line` resolves the session's thread and caches it, so every
write, cache refresh and materialization below resolves through one answer and
the thread is not a mode the rest of the system knows about.

It resolves EAGERLY whenever the caller names an identity, which since
2026-08-27 includes the MCP server: `slopp.mcp/-main` reads the driving
harness's conversation id from `slopp.project.harness` and passes
`:slopp.ops/agent-id`, so `open!` adopts the thread there and loads the store
from it — which means the image boots from the code the session will actually
work on. A session that names NO agent (a test, a one-shot CLI read) gets a
generated id and adopts lazily, and `engine/adopt-line!` resynchronizes when
`absorb-pending-intent!` learns the harness session id.

**That resync exists for the half that does not heal itself.** A session that
resumes a thread holding un-landed work loaded its store AND its image from the
branch. The store heals on its own — the write CAS fails, the cache refreshes,
the write lands — and the image does not, which would leave verification
grading the branch's code against the thread's store: a wrong verdict rather
than a slow one. So `adopt-line!` reboots the image, gated on the one question
that separates the cases: does the thread's head match what the session holds?
A freshly minted thread sits exactly on the branch head, so the ordinary path
costs two SELECTs and no reboot.

The land REPORTS whether it reconciled (`:rebased`), and that is not decoration
— a fast-forward and a rebase are the same green from outside, and the agent
whose work was just replayed onto somebody else's is the one who most needs to
know which happened. It is also what lets the test tell the two paths apart:
without it, a land that never merged passes every assertion about the lander.

**Ids are RANDOM NAMES, and this used to be the sharp edge.** `deltas.id` is
UNIQUE across the whole journal, so an id counter was a property of the FILE —
while the value carrying it belonged to ONE LINE, which meant two lines
counting from the same place minted the same id. Unreachable while a branch was
a separate file; per-line CAS then removed the serialization that had covered
the equivalent case for two servers on one line. **The protection was a side
effect of the thing the feature deliberately removed.**

Three answers were built and all are gone: a monotonic floor, per-session
reserved blocks, and the deletion of counter inference from replay. What ended
them was a measurement rather than a better mechanism — nothing in the store
compares an id by magnitude, so the whole apparatus maintained a total order no
reader consumes. `store/gen-id` now mints a prefix plus 48 random bits per
call and returns the store unchanged, so there is no counter to persist,
reload, fork, park or fall behind with, and `deltas.id UNIQUE` turns the rare
collision into one honest retry. Full reasoning and costs: `D-id-allocation`
in `decisions.md`.

**Why it exists is the same argument as the fold-field registry, learned
twice.** A name in code is a reference and the CST rewrite reaches it; a name
in a register is a DECLARATION, which no rewrite walks, so a re-addressing verb
has to move it and missing one is silent — the declaration ends up naming a
namespace that no longer exists while the code that moved goes ungated. This
store accumulated fifteen orphans in one wave of deletions before
`ns-grained-registers` existed.

That fix did not generalise, and the gap is worth recording because it stood
through every rename slopp ever ran: the two MODULE-grained registers were
re-keyed by a hand-written arm inside `ns-rename!` that named `:modules` and
never `:module-test-edges`. Which failure a rename produced depended on nothing
a reader could see — whether the edge happened to be test-only. Now:

- `ns-grained-registers` is DERIVED from this map (`:grain :namespace` + a
  `:record` fn), so the two cannot disagree; they already had, over
  `:module-roles`.
- `store/rekey-module-registers` handles the module grain from the same list,
  with each row's `:edge-opts` carrying `:test-only` — the test relation is an
  option rather than a branch somebody has to remember to write.
- `refs/occurrences-of` scans the `:kind :declaration` rows, keys AND values,
  so a register that did NOT follow a rename is REPORTED rather than silent.
- `rename-test/a-rename-leaves-no-name-keyed-register-naming-the-old-name`
  derives its population from the store value (every string-keyed map is a
  name-keyed register; `:namespaces` is keyed by symbols), so a sixth register
  is graded by existing rather than by being added to the test.

ADDING A REGISTER = one row here. Nothing else.
- **The two halves cost two orders of magnitude apart, and it decides designs.**
  Measured 2026-08-15 on slopp's own store (2678 elements / 4.3 MB, 23,464
  deltas): `db/load-elements` — the elements→`:namespaces` read, split out so
  `load-store` and a cache refresh share one producer — is **~410 ms**, while
  the whole `load-store` is **~4 s**. The difference is EDN-parsing 23k delta
  payloads; raw SQL reads are 9 ms and ~150 ms. So "just reload the store" is
  never a cheap fallback, and anything on a per-tool-call path needs a gate.
  `db/elements-digest` is that gate at ~3 ms — counts and sizes, explicitly
  not a checksum.
- **The `ns` column holds ONE namespace, and it will not tell you otherwise**
  (2026-08-08). It is written `(str (:ns d))` and read back `(symbol …)`, and
  every consumer reads it as one namespace — `replay-delta`, `merge-logs`,
  `query-outline`. Hand it a COLLECTION and nothing errors: the write
  stringifies it and the read hands back a single symbol whose *name* is the
  printed list, which prints bare and therefore looks exactly like the vector
  you put in. `record-observation` did this for 27 runs, the largest a
  2293-character symbol, and the defect was invisible until something tried to
  ask which namespaces a run had covered.

  A marker that is not about one namespace uses the **`*session*` sentinel** —
  `:done`, `:commit` and `:turn-begin` all do — and puts its real subject in
  the EDN payload, where structure survives. `:observe` now carries `:scope`
  there. **`:verify` still puts its namespace list in `ns` and has the same
  flattening**; nothing reads that list today and 7918 of them exist, so it is
  a separate change rather than a fold-in.

  The general shape, and the reason this went unnoticed: **a column typed for
  one thing, handed many, does not fail — it stringifies.** The journal has no
  schema over payload, so a marker op can put any shape in any field and get
  *something* back. When you add a marker, decide what its `ns` is before you
  decide what it carries.
- **`db/open!` creates; `db/open! dir {:create? false}` returns nil instead**
  (D-serving-is-not-adoption). The MCP server is launched in whatever dir the
  editor has open, so a caller that merely ASKS whether a dir is slopp-managed
  must not answer yes on its behalf. `external/open!` uses `{:create? false}`
  and a session on an unadopted dir carries `:db` nil — the pre-existing
  ephemeral path. `api.session/ensure-db!`, on the commit path, materializes
  the store at the first real write; it is the ONLY implicit adoption in the
  system. When you add a caller of `db/open!`, decide which one it is: a
  question takes `{:create? false}`, a write takes the default.



## Rendering (`slopp.store.render`)

- `render-ns` = forms joined by ONE BLANK LINE, each preceded by its own
  `:comment` if it has one, one trailing newline. It does not round-trip
  ingestion byte-for-byte and is not supposed to: it NORMALIZES, the way
  `gofmt` does, which is what makes a namespace's bytes a pure function of the
  store rather than of whoever typed them.
- **There are FOUR implementations of that one rule**, and they have each
  disagreed at least once:
  `store.render/render-ns` (the reference), `store.db/rendered-sources` (rows →
  the git wip ref), `slopp.kernel.boot/store-sources` (the kernel — cannot call the
  others, since its whole property is booting a store with no slopp code
  loaded), and `element-offsets` below, which must SIMULATE the rendering
  rather than reproduce it. Change one and check all four; the compiler will
  not tell you.
- `element-offsets` = each form's [row col] start within the rendered source.
  This is the bridge from clj-kondo positions to store elements — rename
  correctness depends on it. **It fails silently**: when it went on assuming
  concatenation after the renderer started synthesizing, `change_signature` and
  `rename` returned clean plans containing NO call sites.
- **Placement is just position** (`store/place-form`): a tail append or a
  `:before` insert is a plain vector insert, because the blank line comes from
  the renderer. It stays SHARED by `append-form` (live write) and
  `replay-delta`'s `:add` (journal replay); the two MUST agree on POSITION or a
  reopen / foreign-sync would render differently from the write that produced
  it (`multiproc-test/incremental-sync-replays-the-suffix-exactly` is the
  guard). This used to also juggle whitespace — absorbing trailing trivia,
  preserving a trailing comment, choosing one newline or two — and got it
  wrong: `slopp.ops.engine` alone carried 33 single-newline separators against
  11 blank-line ones, the dogfooding papercut where added forms jammed together
  (`ideas/git-bridge-friction.md` 1b). One rule in one place cost 345 bytes
  across the whole store and removed the class.

## The fold-field registry (`slopp.store.fields`, D-fold-field-registry)

ONE declaration site per store op / fold-field; the old seven hand-edited
sites derive from it:

- `field-registry`: field → `:init` (seeds `empty-store`), `:meta-key`
  (the db meta row `write-snapshot!` writes and `load-store` reads),
  `:normalize` (load-time canonicalization — retired tier spellings die
  here), `:absent-nil?` (:modules' pre-module marker: never defaulted,
  never written while nil).
- `op-registry`: op → `:fold` (THE fold — `record-*`, `replay-delta`, and
  merge replay all call it; they cannot drift), `:merge` (`:replay` =
  last-writer-wins through the fold; `:bespoke` = merge-logs keeps a
  semantic arm), `:sample`/`:crossed` (the GENERATED merge round-trip test
  in `slopp.store.fields-test` — an op cannot register without proving it
  crosses a merge).
- `markers` / `element-ops` classify the rest. merge-logs REFUSES an op no
  set knows (never a silent skip — that once cost three waves of dropped
  config); replay-delta full-reloads it (safe: load-store reads meta rows).
- ADDING AN OP = one registry entry (+ a `record-*` writer that calls
  `fields/fold`). Nothing else: persistence, replay, merge, and the
  round-trip proof all follow from the entry.
- `db/write-snapshot!` is the single transaction tail `persist!`/`append!`
  share — element rows + next-id + registry meta rows + blobs.

## The name-keyed registers (`store/name-keyed-registers`, 2026-08-06)

The registers keyed by a NAME rather than by form id — the total account of
where a namespace or module name appears OUTSIDE the code that spells it.
Five: `:module-tiers`, `:module-platforms`, `:module-roles` (namespace grain)
and `:modules`, `:module-test-edges` (module grain, and both name modules in
their VALUES as well as their keys). `:namespaces` is declared here too, as
`:kind :primary` — it is the index rather than a claim about a name, and
`ns-rename!` re-keys it directly, but a row nobody wrote and a row handled
elsewhere are indistinguishable from outside.

**Why it exists is the same argument as the fold-field registry, learned
twice.** A name in code is a reference and the CST rewrite reaches it; a name
in a register is a DECLARATION, which no rewrite walks, so a re-addressing verb
has to move it and missing one is silent — the declaration ends up naming a
namespace that no longer exists while the code that moved goes ungated. This
store accumulated fifteen orphans in one wave of deletions before
`ns-grained-registers` existed.

That fix did not generalise, and the gap is worth recording because it stood
through every rename slopp ever ran: the two MODULE-grained registers were
re-keyed by a hand-written arm inside `ns-rename!` that named `:modules` and
never `:module-test-edges`. Which failure a rename produced depended on nothing
a reader could see — whether the edge happened to be test-only. Now:

- `ns-grained-registers` is DERIVED from this map (`:grain :namespace` + a
  `:record` fn), so the two cannot disagree; they already had, over
  `:module-roles`.
- `store/rekey-module-registers` handles the module grain from the same list,
  with each row's `:edge-opts` carrying `:test-only` — the test relation is an
  option rather than a branch somebody has to remember to write.
- `refs/occurrences-of` scans the `:kind :declaration` rows, keys AND values,
  so a register that did NOT follow a rename is REPORTED rather than silent.
- `rename-test/a-rename-leaves-no-name-keyed-register-naming-the-old-name`
  derives its population from the store value (every string-keyed map is a
  name-keyed register; `:namespaces` is keyed by symbols), so a sixth register
  is graded by existing rather than by being added to the test.

ADDING A REGISTER = one row here. Nothing else.

## Gotchas

- Delta payloads must stay plain EDN data (no CST nodes, no objects).
- If you add a delta op with a new key, nothing else is needed for
  persistence (payload column is schemaless) — but decide whether
  `query-lineage` should match it (it matches `:form-id` and `:form-ids`),
  and register the op in `slopp.store.fields` (a marker op joins `markers`,
  else foreign-journal sync falls through to a full reload and a merge
  REFUSES it). `:commit` (P4-m7 commit points) is a marker carrying only its
  description, target and status — plus `:git-sha` on imports.
- **`replay-delta` is TOTAL, and that is load-bearing rather than tidy.** A nil
  return means "the journal is not enough, reload from the elements table",
  and the git projection now derives each commit point's tree by folding the log
  — so an op that cannot replay is a commit point whose bytes cannot be
  reconstructed. Six ops used to return nil (`:ingest`, `:move`, `:rename-ns`,
  `:move-forms`, `:extract-ns`, `:module-extract`); all six carry what they
  need and now replay. The four `apply-changeset` ops are pure node rewrites
  BY FORM-ID — the relocation people assume they carry rides separate
  `:add`/`:delete`/`:ingest` deltas, and the delta names the SOURCE namespace,
  so "these forms now live in `:ns`" is a plausible and wrong reading.
- **External dependency manifest (P4-deps):** `:deps-add`/`:deps-remove` are
  STATE-carrying deltas (not pure markers) — `replay-delta` assoc/dissoc's
  `(:deps store)` (lib→coord) so foreign-sync reconstructs the manifest
  incrementally; `merge-logs` lands foreign deps and, on same-lib version
  divergence, auto-resolves to the NEWER coord (numeric compare via
  `slopp.store.semver/newer?`) with a resolution `:note` — only truly incomparable
  coords (mvn vs git sha, etc.) stay a `:conflict`. The current manifest is materialized
  to a `meta` row `'deps'` (written by `persist!`/`append!` from
  `(:deps store)`, read by `load-store` into `:deps`) so launch/git/native
  read it O(1) without replaying — `db/deps [conn]` is the session-free read.
  Branch propagation is free (snapshot goes through persist!). `:deps` is on
  the store VALUE (like `:line-id`).
- `.slopp/` is gitignored; what users commit to VCS is an open Phase-4
  question (the delta DAG is meant to BE the history).

## Git bridge (P4-m8 + G-series, `slopp.git` + `slopp.sync`) — in-memory, projection + transport-out

- **No on-disk git repo, ever.** `open-repo!` builds a JGit in-memory
  `InMemoryRepository` (DFS backend, built with `FS/DETECTED` — TransportLocal
  needs an FS to resolve file-path remotes); the whole projection is
  **generated from the journal on demand**. `store.db` is the source of truth;
  the git repo is a pure, rebuildable cache.
- **Everything served is a pure function of the journal, and now literally
  so.** `project-journal!` folds the log as it walks, so reaching a `:commit`
  marker means holding the store as it stood there, and the tree is `render-ns`
  over it. `insert-commit!` is deterministic, so a fresh in-memory repo mints
  identical shas — `project-journal!` inserts a commit whenever its object
  isn't already live in the repo (insert-if-absent, keyed on the `git_map`
  pin).
  - Each marker used to carry a byte-exact `:tree` snapshot instead: 82 MB
    across 272 markers (39% of the journal), and 94% of a 344 MB journal in an
    earlier round. It existed because comments lived positionally in the
    elements table — CURRENT state only — so a past commit point's bytes were
    genuinely unreconstructible. Once comments became form-owned content the
    log was complete and the snapshot had no job.
  - **ONE pass matters.** Folding from empty per marker is quadratic in the
    journal; threading the store through the existing walk is not.
  - A marker normally targets the delta immediately before it, which is exactly
    where the fold stands on arrival. `commit_point {:target ...}` can name an
    EARLIER delta, so those positions are rendered as the walk passes them and
    held. **A held tree is not released at its first reader** — a commit point's
    own target is the delta before it, which is what an earlier retroactive
    marker also points at, and dropping it there left the retroactive commit
    silently projecting the CURRENT state.
- `git_map` (main store.db) pins each `:commit` delta → sha at first projection,
  keyed `(delta_id, fingerprint)` (fingerprint = SHA-256 of `[id at description
  target]`); query surfaces read it, and it's the insert-skip key above.
- **The local SERVER face is GONE (2026-08-02).** Commit points used to be served
  over localhost smart-HTTP so a git client could clone/fetch the store as a
  remote. It forced exact-project handling that got complex for what it bought,
  and it carried the third `derived-port` implementation. Removed with
  `slopp.git.server`, its two test namespaces, and `ensure-wip!` — the wip refs
  (`refs/heads/wip/<line>`) existed only to be advertised to such a client, and
  minting one rendered every source in the store on every projection for a
  reader that no longer existed.
- **CLIENT face (external, G-series):** `git/push-to-remote!` pushes the same
  projection to a NORMAL remote (GitHub, any bare repo) — fast-forward only,
  never force; `git/fetch-remote!` + `git/tree-at` read a remote tip and tree
  back. `slopp.sync/clone!` rebuilds a **fileless store** from a remote (verified
  dependency-ordered `ingest!`, deps manifest restored from the remote's
  generated deps.edn); `slopp.sync/push!` saves the remote as `git-remote` meta.
- **The graft:** a cloned store records `git-base-sha` (the remote tip it was
  cloned at); `project-journal!` seeds its parent chain with it, so the clone's
  first local commit point chains onto the remote's REAL history and its pushes
  fast-forward. `push-to-remote!` fetches the remote's objects first when the
  base object isn't in the in-memory repo (fresh process). Serving the LOCAL
  listener for a cloned store offline (base objects unfetched) degrades with an
  error — push/pull paths always fetch first.
- The remote is a normal file repo: only COMMIT POINTS cross the wire (a clone
  gets the last commit_point's tree, not un-commit point'd live state); non-source
  files on a remote (README, CI) are ignored by clone/pull.
- **Pull (G4):** `sync/pull!` = fetch → `merge-base(ours, tip)` → 3-way diff
  applied at form granularity (remote wins where we're clean; verified
  writes, remote dependency order, form-order fixup). Both-touched forms,
  file deletions, and gate-failing files → the **`quarantine` table**
  (path/ns/raw source/sha/reason — OFF the journal); `push!` refuses while
  rows exist; the agent merges via edit tools then `git_resolve`. The pull
  ends with a `:commit` marker carrying `:git-sha <tip>`, which
  `project-journal!` ADOPTS as the chain node (never mints) — the next
  commit point parents on the remote tip, keeping pushes fast-forward.
  `ensure-projected!` lazily fetches chain objects it doesn't hold
  (`requiring-resolve` of `fetch-remote!` — append-order forced the late
  bind); offline, ref updates throw and callers degrade.
- Projection ordering: journal marker → git objects (content-addressed,
  idempotent) → git_map row (INSERT OR IGNORE + read-back) → ref CAS;
  `ensure-projected!` rebuilds the in-memory repo from the journal on demand.

## m5a: journal-first commits (storage inversion)

Durable sessions commit through `db/append!`: new deltas + full element rows
of the touched namespaces + the id counter, in ONE transaction, conditional
on the journal head still matching the commit's base. On head-moved (or
SQLITE_BUSY) the writer refreshes its cached store from the db
(`api/refresh-cache!`, advance-only) and rebases. The in-memory store is a
cache of the journal, never ahead of it; there is NO async persist queue —
the append is the persist. `db/persist!` remains for unconditional writes
that have no line to race on (test fixtures, an import) — it no longer
snapshots a branch, because a branch no longer copies anything. This is the
substrate for multi-process
servers sharing one store dir (m5b/c): SQLite WAL serializes writers across
processes, and the same append-CAS protocol arbitrates them.

**`data_version` is a foreign-COMMIT detector, not a foreign-CODE-CHANGE
detector, and the gap between those is where a stale cache lived** (closed
2026-08-15). SQLite moves it when any other connection commits, and in ordinary
operation that is routinely bookkeeping with no bearing on a form: a `git_map`
pin from a projection, the trace map, the dep-surface cache, a saved remote.
`persist-trace!` runs inside `sync-with-journal!` itself, so two idle servers
would have re-read each other's bookkeeping forever if a bump alone triggered a
reload.

So `refresh-cache!` reads the JOURNAL suffix first — but every branch used to
be gated on it, which made a change to the materialized `elements` with no
delta append (a migration, a repair, any external process) invisible
indefinitely, and worse: the next write re-persisted the cached shape over the
migrated rows. `restart` cannot help — the stale value is upstream of the
image. The suffix-empty branch now compares `db/elements-digest` and rebuilds
`:namespaces` alone when it moved. **An unrecorded digest counts as changed** —
absence is not agreement, so a session's first foreign bump rebuilds once,
which is what removes the need to seed the digest at `open!`, `branch!` and
`branch-switch!` all three.
