## A red that proposes its own fix (added 2026-08-30)

A failure whose assertion is `(= literal expr)` (either order) and whose
actual is a scalar literal carries `:proposed {:match :source :note}` — the
exact patch entry (`{match source text true}`) that accepts the new
behaviour. It is a proposal: if the change was deliberate, accept it in one
call; if the test was right, the code is wrong and the proposal is the
thing to ignore. Computed expecteds, errors, non-equality assertions and
collections propose nothing.

## The finisher: declared shifts settle in the write (added 2026-08-30)

`:finisher {:applied [tests] :status :green|:red}` — the expectation
shifts you declared with `accept` were applied (each a normal delta with
its why) and re-verified; the result's `:test` is the AFTER state.
`:accept-unused [tests]` — you declared a shift that never fired; either
the change missed or the acceptance was stale. Both are information.

## References instead of re-sends (added 2026-08-30)

Within one ask, slopp remembers what it has sent you. `:already-sent true`
(with `:view` and a `:detail` id) replaces a whole payload the same call
already returned; `:source-already-sent true` replaces a form's `:source`
on any row — a `query_source` item, an `orient` seed row, a `query_slice`
target, a `query_brief` — when you already hold that form's text at its
current version, whether from a read, from your own group step, or from the ask's
injected bundle (what the bundle carried, the session holds). A windowed slice, a form that changed
since, or a new ask is sent whole. There is no spool for a form reference:
you hold the text — with ONE exception you control: if compaction replaced
your context with a summary and you no longer hold what the ledger says
you do, `resend: true` on the read bypasses the ledger for that call.

A RED write also carries, on each newly red failure entry, `:test-src` —
the failing test's CURRENT source, through the same ledger — so the next
call is the fix, not a read. When the bundle or an earlier read already
gave you that test, `:test-src` arrives as the reference instead.

<!-- reference topic `results` — served whole by `help {topic "results"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## Result keys

Green and quiet compresses to `{:ok true :delta "d42" :test {:ran 2 :pass 5
:status :green :scope :affected} :affected 2}`. `:status` is EXPLICIT — never
infer red/green from a result's SHAPE. `verbose: true` on any write returns the
full map.

- `:scope` — `:affected` (only the tests exercising your change ran) vs `:all`.
  `:affected` — how many re-ran; `:all` = no trace map yet.
- `:status :partial` — impacted `^:external` tests were DEFERRED, and
  `:external-pending` names them. `:unverified` — nothing ran; `:reason` says
  which kind, and a zero-test run also carries `:coverage :none`.
  **A `:partial` green says nothing about the deferred half, and some changes
  are only visible there** — anything touching a durable session, a projection,
  or a subprocess lives in `^:external` by definition. A keyword rename across
  an options map once came back `:partial` with 1412 assertions green, having
  left the busiest entry point in the store destructuring a key no caller
  passed; every test that could have seen it was deferred. So when the change
  is of that kind, `:partial` + a large `:external-pending` is a prompt to run
  `done` or `full_check` — not a result to move on from.
- `:failures` — expected/actual/exception per failure. Diagnose from the
  response; a follow-up `test_run` re-derives what you already have.
  `:implicated` — which of YOUR changes each failing test exercises.
- `:red-first` — the not-yet-written vars a new spec named (stubbed to fail
  honestly). `:also-created` (`ns_create`) — the not-yet-written NAMESPACES a
  scaffold's requires named, brought into being empty.
  `:carried-errors` — stale callers a signature change left behind.
  `:still-red` / `:went-green` — which reds persisted, which cleared. Greens come
  off the complete list of failing test NAMES, not off the failure blocks, which
  are capped — a test past the cap has no block and that reads exactly like a
  pass. `:reds-uncertain` is a run that could not supply the names saying so
  instead of guessing; treat `:still-red` as a floor when you see it.
- `:staleness-healed true` — the red was image staleness, already healed.
  `:image-healed true` — the image was rebuilt under you. `:fresh-confirmed
  true` (red path) — the red survived a fresh image, so it is real.
- `:untested true` — nothing exercises the form you changed; `draft_test`.
- `:warnings` — `!`-naming violations YOU introduced; fix with `edit_rename`
  per the `:suggest`. `:existing-warnings n` counts older ones.
- `:drift` — a finding surfaced on the WRITE precisely so you see it before
  calling `done`.
- `:manual` (change_signature) — references it could NOT rewrite (higher-order
  uses); handle those with a `:patch` step.
- `:dry-run` (rename_sweep) — `:in-code` / `:in-strings`, nothing written.
- `:left-behind` (ns_rename, rename_sweep, ns_realias) — occurrences no rewrite
  reaches, grouped by how each was found. Under `ns_rename` the `:alias` rows
  are the callers whose `:as` still spells the old name, each with a `:suggest`
  to hand `ns_realias`. Under `rename_sweep` each row carries `:via`:
  `:destructuring` (a `{:a/keys [x]}` whose key NAME changed) — not rewritten
  for you, because the symbol is a LOCAL BINDING the body reads — and `:regex`,
  which is now the RESIDUE after the rewrite rather than the whole population,
  so a row there is a finding. `:patterns-rewritten` names the regex literals it
  DID move. `:requalified`
  (rename_sweep) — destructurings it restructured, which is the half of a keyword
  rename's diff that is not a text substitution. Absence of any of them means
  checked-and-none, never unchecked.
- `:sites` (ns_realias) — qualified references rewritten. Zero is a real
  answer: the alias was declared and never used.
- `:callers-unrewritten` (edit_move_forms) — the caller POPULATION beside
  `:rewrote`. Every row is a form the reference graph says calls a moved name
  that the rewrite did not change. Usually empty; a row is not automatically a
  bug (a quoted target is left whole on purpose), but read it — a count with
  no population beside it is how "rewrote 2 of 4 callers" once reported `:ok`.
- `:shadowed` (edit_move_forms) — **the one report that green does not
  cover.** Moving a form INTO a namespace it calls makes those refs BARE, and
  if the moved form binds a LOCAL of that name the call now reaches the local:
  valid Clojure, different behaviour, compiles, suite green. Each row is
  `{:form :was :now}` and `:shadowed-note` says so. Rename the local (or the
  moved var) and re-run — this is the one place where "the write verified"
  and "the code still means what it did" come apart, so do not read past it.
- `:conflicts` (merge) — ours kept, theirs surfaced; the payload IS current
  live source, so resolve straight from it.
- `:source-now` — your match missed or was ambiguous. Correct from it and
  resend; no read needed.
- `:hint` — a one-line workflow nudge, at most once per session.

