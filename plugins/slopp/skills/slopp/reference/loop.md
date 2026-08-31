<!-- reference topic `loop` — served whole by `help {topic "loop"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## The loop — and the budget

A whole task should be ~10–20 tool calls: orient (1) → read (1) → write
REPL-style (small individual writes) → `done` → ONE milestone. `done` is
not once-per-session: call it at every point you believe a piece of work is
complete, before starting the next. Each extra
call re-reads your entire context; the patterns below are where sessions
measurably bleed tokens.

**That re-read is per TURN, not per call — so independent calls go in ONE
turn.** Two calls issued together cost one context pass and one model
round-trip; the same two in sequence cost two of each. This is the largest
measured lever on the whole loop and it is left on the table by habit rather
than by any rule here: across two stores, **98–100% of turns issued exactly
one tool**. Send together anything whose inputs you already have — reads of
two different forms, a `query_depends` beside a `query_search`, and **writes
to DIFFERENT forms**, which do not contend because slopp rebases per form.
Keep sequential only what is genuinely dependent, and three things are:
a read whose ANSWER picks your next write; two edits to ONE form (that is one
edit, not a batch — see "Choosing the write tool"); and a failing test with
its implementation, because red-first means you have to SEE the red. `done`
ends a turn's batch by definition — its verdict is what decides the next move.

1. **Orient with ONE small call per session: `session_brief`.** Form
   names, recent milestones with their asks, git alignment, the loop —
   everything a fresh session needs to start working. **Then ONE call per
   ask: `orient {ask "<the ask, verbatim>"}`** — the forms that matter for
   it, ranked by a walk over the reference graph and the tests that cover
   them, fitted to `tokens` (default 1500). Every row is a card (sig, doc
   line, recorded why, test warranty) and carries `:via` — the edge that put
   it there (`seed` / `called by X` / `calls X` / `covered by T`) — so it
   names the entry point AND its neighbourhood in one read; `:more` counts
   what the budget cut. Add `seeds ["ns/name"]` for forms you already know.
   Skip `query_project` unless you need arities/flags for a specific ns
   (`query_source {ns}` = the outline). `query_search {pattern}` is for
   text you cannot name.
2. **Read only what the brief can't tell you — and prefer NOT reading.**
   About to edit a function? `query_slice {ns name}` (add `match`+`window` on giant forms — the neighborhood, not the whole thing) is THE read: full
   source of that one form + interface CARDS (sig, doc, why, test
   warranty) for everything it reaches. TRUST the cards — you don't need
   a callee's body to call it; if an assumption is wrong, the write turns
   red with `:implicated` (the covering tests re-run on every edit).
   Writes are OPTIMISTIC: compose `edit_subform` matches from the
   brief/slice; a missed or ambiguous match returns the form's CURRENT
   source in `:source-now` — correct from the error and resend. Batched
   named reads: `query_source {targets: [{ns name}…]}`; whole-namespace
   dumps are outline-by-default (`full: true` = the rare escape). Never
   re-read what you just wrote. In a LARGE codebase, delegate broad
   comprehension questions to the `slopp-reader` subagent — it returns
   conclusions; your context should hold decisions, not source.
   **For summaries/handoffs/audits: `report` is TERMINAL, not a starting
   point.** One read already carries `:intents` (the USER's verbatim asks,
   recorded per turn), `:milestones`, `:changes` with their recorded `:asks`,
   `:dead-ends`, the suite state, and `:code` — the follow-up that carries
   source. Narrow it with `report {contains "eco"}`; do NOT re-ask
   `query_history {contains …}` once per feature (measured: four such calls
   at ~6k each, re-deriving what one report already held). The code itself is
   `query_changes {from "start"}` — every form's `:was`/`:now` across the
   lifetime, `format: "text"` for line diffs — never `git diff`, and never
   raw `store.db`.
3. **Write with intent; trust the verification.** Every write takes a
   one-line `prompt`: ONE logical change per write, and say WHY — history
   quality is intent quality. The response carries the affected tests' result —
   `test_run` after an edit is redundant. Red results carry `:failures`
   inline plus `:implicated` (which of YOUR changes each failing test
   exercises) — start debugging there. **Your work is already verified —
   never re-run the suite externally, never clone/worktree the repo to
   double-check yourself.** When the user asks how to verify, GIVE them
   the commands (`slopp --call test_run`, `query_commits`) — don't
   execute a dry run.
4. **Work like a REPL — one small write at a time.** Mid-episode reds are
   normal TDD state: a spec naming a not-yet-written fn lands as an honest
   red (`:red-first` names the stubs); changing a signature lands with the
   stale callers riding `:carried-errors` — catch them up in your next
   writes. Nothing asks you to pre-plan groups. Red-first TDD: write the
   failing test FIRST, then implement. An `:untested` flag? `draft_test
   {ns name code}` drafts a deftest from OBSERVED calls.
5. **Say `done {label}` at EVERY point you think you're finished with
   something — not once at the end.** It is also what makes your work
   VISIBLE: you write in a private thread of your own, and a green `done`
   LANDS that thread onto the branch. Until then nobody else sees it — not
   another agent on the same branch, not `commit_point`, not the running
   server. A `done` that is red ON YOUR OWN WORK lands nothing and your
   thread survives, holding the work that is not finished yet — but a red
   that provably exercises nothing you touched does not hold you: done
   reports it under `:red-attribution` as `:foreign` and lands anyway. Two
   verdicts, and they answer different questions: `:test-status` grades the
   STORE (a red one still cannot milestone), `:episode-status` grades YOUR
   work and is what the land turns on. Finished a unit of work and about to
   start the next? That's a done point. Call it, read the findings, and
   find out whether you were actually done before you move on. Multiple
   `done`s per session is the normal shape, not an exception: each one is
   cheap, each marks a boundary you can revert to, and each catches a
   problem while the work is still fresh in your context rather than three
   tasks later. (A turn ending is merely one such moment — the hook fires
   it for you then.) It runs the
   WHOLE in-image suite plus the `^:external` tests your changes impact,
   normalizes, marks the episode boundary, and reports findings; address
   them. A finding marked `:severity :info` is deliberately
   INFORMATIONAL — reported for you to eyeball, never status-flipping —
   so a rule can carry both a hard failure and an observation only you
   can judge. It is not a lesser failure; there is nothing to discharge.
   A pre-flight `test_run` is redundant. **`done` REPORTS, it does
   not refuse** — it records the boundary honestly and tells you "not done
   yet", so you are never deadlocked by a finding you cannot fix.
   `commit_point` is what refuses to PUBLISH a red done — and a red done
   STANDS until new work supersedes it, so you cannot clear it by
   committing without changing anything.
   **`done` is EPISODE-scoped** (`:scope :episode` on every result — the
   keyword, not a paragraph; the `done` tool description carries the
   teaching once): lint and dead-surface cover only the namespaces you
   touched, and the full `^:external`/`^:integration` tiers do not run.
   `full_check` answers the whole-store question — see below.
   **Read `:external-pending` for whether the external slice actually RAN.**
   done runs the impacted `^:external` tests, but above a cap it defers the
   whole lot and runs NONE — and it still returns `:green`, because the
   tests that ran passed. `:external-pending :note` says so. The inversion is
   worth internalising: the deferral gets likelier as your change gets
   BROADER, since narrowing saves nothing once the impacted set approaches
   the whole suite. So a sweeping edit earns LESS external evidence than a
   narrow one, and that is exactly when `full_check` stops being optional. If your session pauses
   first, the hook fires done for you and the findings greet the next
   session's brief.
   **`:host-stale` means DOUBT THE VERDICT.** `done` and `full_check` carry
   it when the process that produced the result is knowingly running code the
   store has moved past — a hot-reload that failed, or a `--snapshot` host
   with code deltas since it booted. The tests may have passed against the
   wrong code. Restart the server (or fix the reload failure named in
   `:failed`) and re-run before believing a green. It is absent unless there
   is something to doubt, so when you see it, act on it.
   **`:oracle-drift` says WHY, and the `restart` tool clears that half.** A
   `:derived-stale` row is a form holding a value it captured from another
   form that has since been re-evaluated — the class a source comparison
   cannot see, since both sources are current. It names `:behind` (the
   qualified form it fell behind) and `:behind-edit {:delta :prompt}`, and the
   note says the edit outright when every row agrees on one. **Expect
   `:behind` to be a form you never touched**: a write reloads its WHOLE
   namespace, so what stales you is usually a SIBLING of the form you are
   behind, which is why the edit is the useful half and the form alone sends
   you looking at innocent code.
   **`:host :jar` says which ARTIFACT is answering** — `{:head <delta>
   :behind N}`, the store head the running jar was BUILT from and how many
   code deltas have landed since. `:host` otherwise reports the process's
   MODE, never its code, which leaves "am I running the slopp that has the
   fix?" answerable only by unzipping a file. A `:head` with **no `:behind`**
   is not zero — it means the jar came from a DIFFERENT store than the one you
   are reading (slopp's own jar serves other projects), so the count would be
   meaningless and the identity is what to compare. The whole section is absent
   when the process cannot say: a checkout, a `clojure -M` run.
   **`restart` does not change the jar.** It rebuilds the images INSIDE this
   JVM, which is the remedy for `:host-stale` and for verification drift, and
   is no remedy at all for a stale artifact — a running JVM holds the classes
   it loaded at boot. A jar `:behind` by hundreds needs a rebuild and a
   PROCESS restart, and the tool named `restart` will look like it should work.
   **THREE states, and only the last one decides what you can call:** what you
   were TOLD shipped, what the jar on disk CARRIES
   (`unzip -p <jar> META-INF/slopp/head.edn`), and what this process has
   LOADED — `:host :jar :head` above, with `:behind` counting between them.
   The middle one looks like success and is not: a consumer read a correct jar
   off disk, ran `restart`, was told `restarted`, and found the framework
   function still carrying its old arglist. Ask the IMAGE what it has
   (`query_eval (:arglists (meta #'slopp.webapp/load-key))`) rather than asking
   the disk what exists.
   **And when you tell someone ELSE the fix landed, name the artifact it landed
   in.** "Shipped" and "running where you are" are two facts, and an instruction
   in the present tense — *revert your workaround* — collapses them: you cannot
   see their jar, and they can. Write the precondition into the sentence
   (*"once you are on d25770"*) so the reader can evaluate it, or they act on a
   fix they do not have.
   **`:ms` says what verification cost.** Every write's `:test`, every `done`,
   every `full_check` and every external run reports its own wall time, and
   the number persists on the delta — so "where is my time going?" is a query
   over the journal, not a guess. Per-write verification is normally free
   (median 0ms on a 170-namespace store); if yours is not, that is the signal.
   **And each TURN records where its whole wall clock went**: `turn_end`'s
   delta carries `:timing {:slopp-ms :outside-ms :idle-ms :elapsed-ms :top
   :refused}`. One turn is one USER ASK — a new ask closes the open turn and
   opens its own. `:outside-ms` is time slopp was NOT working — your reasoning
   plus every non-slopp tool — and it is usually the majority (measured on
   slopp's own development: 78%). Use it before optimizing a tool: if
   `:slopp-ms` is a fifth of the turn, a faster tool is not what you are
   missing. `:idle-ms` is separate and is the session nobody was in: turns
   rotate on the write-tool gate, so one can straddle a human going away, and
   `:slopp-share` is taken against ACTIVE time so a pause never reads as slopp
   being slow.
   **`:refused` is the one to act on.** It counts the calls that bounced — a
   malformed `edit_subform` match, a lint error in the form you were writing,
   an arity break — as a rate with the tools named, plus `:samples`, the
   verbatim messages the bounced calls answered with. Each is a whole round
   trip that produced nothing, and they land in the half of the clock nothing
   else measures. Read the samples before changing anything: a high rate on one
   tool is a prompt to read its contract, not to retry harder.
   **What the answers COST is a SEPARATE record** — `:read-cost` deltas, not
   the turn — because it must not depend on a turn closing. Each carries
   total `:chars` on the wire, `:calls`, per-tool rows so you can see which
   read is actually expensive, and the part worth knowing: whether a response
   you were handed in trimmed form got re-fetched with `query_detail` anyway.
   `:withheld` counts the answers that arrived incomplete (a trim, or an
   `:already-sent` stub); `:refetched` counts the ones you went back for, charged
   to the tool that withheld rather than to `query_detail`. A re-fetch costs
   MORE than the whole answer would have, so a high `:refetch-rate` means the
   trimming is losing you tokens, not saving them — narrow the read (name the
   forms, use `match`) rather than fetching the payload twice. `:refetch-rate`
   is nil when nothing was withheld: that is unmeasured, not free.
   **Tier vocabulary** (namespaces AND tests): `:pure` (referentially
   transparent) · `:internal` (mutates in-process state only — a memo via
   `slopp.cache`) · `:external` (IO: files, subprocesses, network, db).
   `module_purity {module tier}` declares a namespace's; `^:external` marks a
   test that exercises one. The axis is what decides how a thing is TESTED —
   external needs a separate JVM and temp dirs, internal needs a cache reset,
   pure needs nothing.
6. **`full_check` when the episode's scope isn't the question.** Every
   namespace linted, dead surface store-wide, every test in every tier.
   NOTHING forces it — not `done`, not `commit_point`. Reach for it when a
   change was broad, when you DELETED A CALLER (dead surface appears in
   namespaces you never touched — the one thing episode scope structurally
   cannot see), or before a commit you want to stand behind.
   **A verdict that still STANDS is handed back, not re-earned.** When nothing
   since the last whole-store check could have changed what it says, you get
   that verdict with `:standing true` in about a millisecond and no check runs
   — the same courtesy `commit_point` has always shown an unchanged milestone.
   Any write of any kind retires it; `{force true}` re-runs regardless. So
   asking again is cheap and honest rather than something to ration: the
   reason this exists is that over one store's journal, 117 of 325 runs were
   repeats INSIDE A SINGLE ASK, at about four minutes each.
   **It takes minutes, so your harness may move it to the BACKGROUND and hand
   you a task id. When it does, keep working and let the result arrive — do
   NOT poll for it.** Backgrounding is the good outcome: the call is no longer
   blocking you. Polling gives that back and adds round trips on top, and it
   is the single most expensive habit measured on this store — 227 polls at
   ~112s each, 7.1 hours spent waiting for results that were already coming.
   Read something, draft the next edit, write a note; the notification will
   interrupt you when it lands.
   **`{affected true}` is the MIDDLE GEAR, and what it saves depends entirely
   on WHERE you changed things.** Lint, dead surface, layering and the in-image
   suite still cover every namespace; only the `^:external` tier narrows, to
   the tests your changes since the last milestone can REACH. So it is a
   reachability filter, not a discount: change a leaf namespace and it runs a
   handful of tests; change `slopp.ops`, `slopp.mcp` or `slopp.rules` and
   nearly everything reaches you.
   Measured here, both gears on the same store: full = 1297 external tests in
   ~223s; `affected` after a change to `ops`/`mcp`/`rules` = 839 tests in
   ~229s — **no saving at all**, because most of the cost is the four fresh
   JVMs, not the tests they run. Reach for the middle gear when your episode
   was LOCAL, and do not expect it to rescue a core change.
   **You no longer have to guess: the external result carries `:cost`.** It
   gives the per-shard wall times, the FLOOR (the fastest shard — one JVM boot
   plus dependency resolution, which every run pays, narrowed or not), and the
   most narrowing could therefore return. It also flags an UNBALANCED run,
   which is a different problem with a different remedy: the tier costs its
   slowest shard, so a lopsided split is paying for the spread rather than the
   work, and running fewer tests does not touch it. Read `:cost` before
   choosing a gear.
   **It is NOT a superset of `done`, and the axis it loses on is
   ISOLATION.** `done` puts every impacted `^:external` test in ONE serial
   JVM; `full_check` shards the suite across four. So two tests that only
   fail TOGETHER — through a recycled image, a temp dir, any process-global
   — fail under the narrower check and can pass under the broader one. **A
   red `done` beside a green `full_check` is not `done` being wrong**; it is
   the pair being co-scheduled in one place and split in the other, and the
   reproduction is `test_run {external true affected true parallel 1}`.
   Read the two as differing in COVERAGE and there is no other conclusion
   available than "done is flaky", which is how a real interference gets
   waved through.
   **`:rules` is the rule catalog asked about the WHOLE store, and it is the
   only thing that ever asks.** A `:grain :done` rule fires over the forms an
   episode CHANGED, so a violation older than the rule is invisible to `done`
   — and stays invisible, because no later episode changes that form either.
   Every episode is honestly clean and the store is not. This is why a store
   that has never once seen a rule finding is not evidence the rules are
   holding: **turning a rule on does not check the code already there.** Run
   `full_check` after adding or dialing up a rule, or you have gated the
   future and left the past.
   `:error`-grade findings flip the status; `:advisory` ones are reported and
   do not — the same bar `done` grades on, so a rule means one thing in both
   places. `:swept` and `:not-swept` name the rules it ran and the ones it
   could not: about a third of the registry compares against the episode's
   BASELINE (`key-typos`, `breaking-changes`, `assertions-never-red` …), and
   running one of those over every form reports nothing in the same shape as
   clean, so they say so instead of quietly padding the green.
   **`:empty-namespaces` names HUSKS — and this is the only grain that can.**
   A move that carries a namespace's whole contents elsewhere leaves an `ns`
   form with nothing after it. Nothing else reports one: there is no form to
   be dead, undocumented or uncovered, and every advisory is addressed by
   changed FORM IDS (the whole-store rule sweep included), so a namespace with
   zero forms is in no population any rule can reach. Advisory — it never
   flips the status, because a namespace you just created is empty for one
   write. `ns_delete` retires a genuine husk; adding a form is the other
   honest answer.
   **`:crossings` is the one section a green does NOT cover.** Everything else
   full_check reports is an edge inside the store; this names the exits — a
   contract becoming JSON, form metadata becoming a route table, `.cljc` going
   to the ClojureScript compiler — that nothing checks. It is ADVISORY and
   never flips the status, because these are standing holes rather than
   regressions. It is there so a whole-store green is not read as ruling out
   what it never examined; if your change crossed one of those edges, that
   edge is yours to test.
   **`:app {:behind n}` is the browser's view, not the store's.** If slopp
   runs an app server for this project, this says how many code changes the
   SERVED image is behind the store you just called green — it is rebuilt at
   DONE grain, so between done points a browser is looking at an older store.
   `0` is reported too, so the current case is an answer rather than a gap.
   Any `n > 0` means the page you are about to screenshot is not the code you
   just wrote, and a partly-updated page renders BROKEN rather than merely
   old. `done` re-serves.
7. **Close ONCE.** Exactly ONE `commit_point {label}` at the end
   (it runs `done` and gates on that verdict — it has no checks of its own)
   unless the user asks for more.

