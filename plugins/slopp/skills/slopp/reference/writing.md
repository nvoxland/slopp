## `change` — the write verb (s11, 2026-08-31)

`change {prompt tests? impl accept?}` is THE write: `tests` steps land
first and the result reports which went RED (watched failing — red-first
honored); `impl` steps land; accepted expectation shifts finish; ONE
verification; one result. `{prompt impl}` alone is the ordinary write — a
docstring, a comment, a one-form fix. There is NO done inside: `done` is
your separate this-unit-is-finished move, and a unit may span
change → explore → change. A red change lands NOTHING and loses nothing —
the tests and impl sit on your thread, `:test-src` carries the failing
test's source; fix forward from the result. A change past ~10 steps is two
changes.

Steps: `{action: add|replace|patch|delete|require, ns, name, source,
match, text, where, require}`. A step with `{ns name source}` and no
`action` is inferred — `:replace` when the form exists, `:add` when it
does not; the report states what landed. `patch` is the
small-change-inside-a-big-form action: `{action patch, ns, name, replace
[{match, source, text?, where?} …]}` — several exact-subform swaps as
deltas, never a whole-form retype (measured: whole-form retyping ran 2.1×
plain's output volume).

The impl (and tests) group is atomic: every per-step gate runs (dialect,
isolation, module, ambiguity, rename collision); the group is checked to
load, hot-loaded, and verified ONCE; the result carries `:steps
[{:step :action :form :delta}]`, `:affected` (the covering tests by name
when few), `:test`. All-or-nothing: a refused step returns
`{:error "step i: …" :step i}` and nothing lands. A missing alias exactly
one namespace can supply is required for you (`:auto-require`). A newly
added `deftest` is its own covering test and runs. A first call across a
module boundary declares the edge for you (`:auto-module-dep`); only a
call that would close a cycle is refused, and the refusal says which
cycle. A delete step whose form something OUTSIDE the change still calls
is refused by step and caller name; a caller inside the change is fine in
any order.

**When the test is a question, don't bundle it.** `change` is for when you
can state the test and the implementation with equal confidence. A test
you are writing to FIND something out is `explore {ops [{op check code
…}]}` — the red comes back as an ANSWER (`{:pass :fail :assertions}`)
with nothing written, and you land it as a real test only once it says
what you mean.

(`edit_group`, `edit_subform`, `edit_replace_form`, `edit_add_form`,
`edit_delete_form` and `intent` remain dispatchable by name — de-advertised
aliases for old scripts and the `--call` door; `change {impl […]}` is the
same atomic group write.)

**`accept` — declare the expectation shifts (added 2026-08-30).** A change
that deliberately moves behaviour breaks the tests that pinned the old
behaviour, and you know WHICH at write time. `accept ["ns/test" …]` on
`change` says so: when an
accepted test fails with a literal→literal delta, its `:proposed` update
is applied as its own delta and re-verified in the same call — the result
carries `:finisher {:applied […] :status …}`. An acceptance that never
fired is `:accept-unused`. A red you did NOT accept rides untouched:
tests keep guarding everything you didn't declare.

Why it is the grain: the agent thinks in whole asks — the fn, its test,
the caller, the require. One form per call on that grain was measured
(eval10) as a model request per form with reads between them. A change is
not a shopping list: a whole feature in one call meets the same gates a
whole feature in one form does, and `done` remains the completeness
judgement.

<!-- reference topic `writing` — served whole by `help {topic "writing"}`; the one-page SKILL.md points here. Written against dc66cb7a963c5. -->

## Choosing the write tool

| Situation | Tool |
|---|---|
| New namespace (grow with TDD) | `ns_create {ns, requires}` — create dependency nses first |
| New namespace, source ready | `ns_create {ns, source}` — whole text, one verified call |
| Require add/remove | `ns_add_require` / `ns_remove_require` |
| New form — or SEVERAL | `change` — one `:add` step per form: one atomic write, verified once, reported per step in `:steps` |
| Change a whole form | `change` — a one-step `:replace` (or `{ns name source}` with no action — inferred) |
| Small change INSIDE a big form | a `:patch` step — `{action patch, ns, name, replace [{match source} …]}`: each entry matches ONE subform or ONE pair; a missed match returns `:source-now` (correct + resend); `text: true` for strings/docstrings; `where: {key value}` addresses the unique MAP containing those entries (registry rows — no exact text needed) |
| Edit ONE ROW of a registry | a `:patch` entry with `{where: {key value}}` — **`where` ADDRESSES a row, it does not assert a value.** Both sides match on the spelling they answer to, so `{"key": "stored-name"}`, `":stored-name"` and `:stored-name` all reach a row stored as `{:key :stored-name …}`, and a string-keyed map is reachable too. This matters because registry rows are keyed by KEYWORDS and JSON has none — before it, the single most common thing `where` exists for was the one thing a caller could not express. A miss names the values that key DOES take (`:key takes :schema-drift, :key-typos, …`), so a wrong value says so instead of reading as a missing row |
| Put existing code INSIDE something new (a `let`, a `when`, a `try`) | the `edit_subform` alias — `{match <a COMPLETE form>, wrap: true, source "(let [n 1] $1)"}` — `$1` is the matched form, so what was there NESTS inside your template. Without it the only expressible edit is restating the whole enclosing form, since a match that opens a delimiter it doesn't close is refused. Same `$n` templating `change_signature` uses; a template with no `$1` is refused rather than deleting the match |
| Change a form's NAME METADATA (`^:export`, `^{:malli/schema …}`) | a `:patch` entry with `text: true` matching the `defn` head — `"^:live-handle open!"` → the new metadata. Structural matching can't address the head on its own, so without this you resend the whole form to change one marker |
| Edit a form that has NO NAME (`defmethod`, `use-fixtures`) | the `edit_subform` alias — `{form "<form-id>"}` — **the id addresses a form wherever the name does**, and some forms genuinely have no name: a `defmethod` has a dispatch value, `use-fixtures` defines nothing. Ids come from `query_search` hits (`:form` is the id when there is no name) and from any finding that names one. a `:replace` step takes a name, so the id route is the alias |
| A write names an alias the ns form lacks | **When exactly ONE namespace can supply it, the pipeline adds the require for you** (a `:system` write with its own prompt) and the write lands — the result carries `:auto-require {:added "[clojure.string :as str]"}`; nothing to resend. slopp resolves the alias against the store (a namespace whose last segment is the alias) and the common `clojure.*` ones. When SEVERAL could, the refusal names each `ns_add_require` call — pick one, then resend the form UNCHANGED. An alias nothing can supply gets no suggestion rather than a wrong one |
| A write refusal says `ALSO PENDING:` | **Fix every line before resending.** A refusal carries EVERY gate the candidate tripped, not just the first — they were all knowable from the same form, so satisfying them one per round-trip is pure waste |
| A subform edit refused with `unresolved-symbol`/`invalid-arity` | **Widen the match.** The change spans more of the form than you matched — a binding and its use, a loop and its `recur`, an arglist and its body. Match the enclosing form, or `:replace` the whole thing. **Two edits to ONE form is ONE edit**; this is NOT cross-form atomicity and there is no batch tool for it (the refusal says so too) |
| Change a fn's SIGNATURE | `change_signature {ns name source calls}` — new defn + `$1..$9` call-site template; never signature-change form-by-form |
| Several changes, one reason | just make the writes one at a time — episodes group them for you; interim reds/`:carried-errors` are normal until `done` |
| Rename ONE form | `edit_rename` (def + all references, shadow-safe); its result lists leftover prose `:mentions` |
| Rename a namespace ALIAS (`[a.b :as old]` -> `:as new`) | `ns_realias {ns old new}` — the `:as` in the ns form AND every `old/sym` in that namespace's bodies, one verified write. **There is no hand route**, and that is why this is a tool rather than two edits: between the two writes the ns form and the bodies disagree about the qualifier and the namespace does not load, so the alternative is the add-both / migrate / drop dance. **Reach for it right after `ns_rename`**, which rewrites namespaces and walks straight past the `:as` — the moved code keeps being called by its old module's name, and the day that name gets REUSED the alias starts pointing at a real, different module, which is worse than one naming nothing. You do not have to spot them: the rename lists them under `:left-behind :alias`, each with the `:suggest` to pass here. Scoped to one namespace by design: an alias is a name ONE namespace chose, so two namespaces calling a lib different things is not drift and there is no store-wide version. A BARE `old` is left alone — only `old/x` is the qualifier, and the same spelling is routinely a local or a parameter three tokens away. Read `:sites` (0 means the alias was unused, not that nothing happened) and `:left-behind` |
| Rename a CONCEPT ("zone is now region") | `rename_sweep {from to}` — namespaces + vars + keywords + prose, store-wide, ONE call, one verification; never form-by-form. **A bare name is swept as a CONCEPT, so its compounds travel with it** — `region` reaches `region-fee`, `region-fees` and `region-t`, which is what makes this one intent rather than a list of renames. Only letters end the name, so `regional` is untouched but `region-ish` is NOT: if you meant the narrower thing, sweep the compound. (A qualified KEYWORD is bounded differently -- see the row below.) **`dry_run` first and check the count against what you expected** — a mismatch means your pattern is catching something else. Two gotchas: it rewrites prose DESCRIBING the rename (a comment explaining `a -> b` comes out saying `b -> b`), and if a live GATE enforces the thing you are renaming, you need two phases — teach the gate to accept BOTH spellings, sweep, then tighten. A gate runs from the old compiled code while the group rewrites it, so a one-shot sweep is refused at the first form it re-tags. **And READ A FAILED SWEEP'S `:note`.** The namespace renames run BEFORE the atomic text group and are not part of it, so a refusal rolls back the text and leaves every namespace renamed — a store that looks renamed and is not, where `:export "old.prefix"` strings name a subtree that no longer exists and the module rules inherit from the new NAME. The result says so and names the recovery: `thread_drop` (the renames are un-landed), or fix the refusal and re-run the same sweep, which is a no-op for the namespaces. **Pick the most QUALIFIED name that still covers the live references** — a broad name reaches backwards into HISTORY (incident records and frozen fixtures naming what a thing really was called; sweeping those forward invents a past) while a narrow one cannot, and it also misses the unqualified TAIL (`slopp.a.b` as a segment does not match prose writing `b/thing`), so sweep that separately and check user-facing strings — teach strings and error text — for it. If the qualified form leaves a real reference uncovered, that reference wanted naming precisely anyway |
| Rename a QUALIFIED KEYWORD (`:a/x` -> `:b/x`) | `rename_sweep` — it moves the literals AND the `{:a/keys [x]}` destructuring, which names the key as a SYMBOL with the qualifier one position to the left and so is invisible to a text pass. The entry is matched on the FROM qualifier and only on it, so an unqualified `{:keys [x]}` — which names `:x` and has nothing to do with your rename — is left alone. **A keyword is bounded as a whole TOKEN**, so `-`, digits and `_` end it and sweeping `:a/x` leaves `:a/x-ray` alone — the opposite of the bare-name row above, because `:a/x-ray` is a different marker, usually read by a different rule. **Realias when the alias carries the RENAMED segment, not when it merely sits
under it.** After renaming `a.web.*` to `a.http.*`, an alias `web.client` for
`a.http.client` is now a lie and wants `ns_realias`; an alias `client` for the
same namespace is a last-segment name that stayed accurate and must be left
alone. Renaming the second kind is churn that makes every call site lie about
nothing. `full_check`'s `:alias-drift` reports both, so read it as a worklist
rather than a to-do list.

**A store can be immune to a rename's MECHANISM and still be broken by its
RESULT.** Those are independent questions and the first is the easy one, so it
is the one that gets checked. "A sweep run in that store renames none of its
namespaces" is about the mechanism; "that store requires the namespaces you
renamed" is about the result, and a consumer of a framework family is normally
in the second case while looking safe in the first. When you rename anything
consumers require, ask what BREAKS for them, not what the tool would do there —
and say which of the two you checked, because the sentence reads the same
either way.

**And the opposite failure: a spelling a sweep CAN reach and must NOT.** A
sweep is safe when every occurrence of a name means the CURRENT thing. A dated
artifact means the name AS IT WAS — a captured manifest, an incident record, a
migration note quoting the old spelling — and a rename cannot tell the two
apart, because they are the same characters. Rewriting one turns a record into
a claim about a past that never happened.

**It stays GREEN, which is why it needs saying.** When a frozen fixture and the
assertion over it are both text, one sweep moves both and they agree
afterwards; nothing fails. The only signal is `dry_run`'s `:in-strings` bucket,
which is what that REVIEW FIRST note is for — read it as "which of these mean
the name as it is TODAY?", and expect a `[]` there to be the normal case, so a
non-empty one deserves the time. Measured: one such fixture in this codebase
has been swept and reverted three times, its own docstring saying not to.

**A NAMESPACE rename does not reach the spellings that are not the name.** A
sweep matches the dotted token, so three shapes survive it, and each one fails
only when something is BUILT or LOADED — never at the write:

- **slash paths** — `slopp/http/css.clj` in a vendoring manifest or a resource
  lookup. Sweep the slash form as its own pass (`slopp/web` → `slopp/http`);
  the letter boundary still protects `slopp/webapp`.
- **a bare directory segment** — `(io/file parent "web")`, where the segment
  alone carries the name.
- **a path spelled as separate arguments** — `(io/file dir "src" "slopp" "web"
  "css.clj")`. Nothing can match this; only a build that loads the tree finds it.

All three were found here by the external suite, after a green done and a green
whole-store lint. **Run the tier that actually builds and loads something before
believing a rename is finished.**

**Verify a family migration by RECONCILING, not by inspecting.** Take a census
before you start (how many occurrences, across how many names), then check the
after-counts balance: moved + deliberately-held = the original total, and every
name in the result is one you expected. Checking "the pairs that look risky"
cannot be trusted, because the pairs you enumerate are the ones you already
thought of — and a sweep that eats a short name into a long one necessarily
produces a name OUTSIDE the expected set, which reconciliation catches without
your having to guess where. Its honest limit: a corruption that preserved every
count and produced a name already in your expected set would pass. **Order a family migration so the CONSTITUTIVE marker goes LAST**: the one that declares the thing (`:http/path` declares an endpoint) turns every form into a half-migrated endpoint the moment it moves, and the per-endpoint gates then fire on the siblings that have not moved yet. Sweep the describing markers first and the refusal never happens -- it reads like a missing declaration, and the obvious fix (adding the missing marker by hand) is wrong. **Read `:requalified` and `:left-behind`; absence of either means checked-and-none.** `:requalified` is the half of the diff that is not a text substitution, and worth an eye for that reason alone. `:left-behind` is the half the tool DECLINED: changing the key's NAME (`:a/x` -> `:a/y`) rather than its qualifier cannot be applied to a destructuring, because the symbol is a LOCAL BINDING the body reads — so sweep the qualifier and rename the name as two steps, or finish the named forms by hand. A stranded destructuring presents as nil arriving silently rather than as an error, so the only tests that can catch one are the ones exercising the value END-TO-END — which for a session, a projection or a subprocess means `^:external`, and those are exactly the ones a write DEFERS. Do not read the write's green as coverage here |
| Rename a CONFIG KEY family (`a.b.*` -> `x.a.b.*`) | **Not `rename_sweep`** — a dotted key is a STRING, and the sweep's whole-word/segment matching is wrong for it in both directions: a segment of the key is usually also a segment of a NAMESPACE and of keys inside the config's own VALUES, so it rewrites things that are not the key, while missing the places the key really lives. Do it by hand and go looking for the three hiding places, none of which a text pass reports: **regex literals** (`#"a\\.b\\..+"` — the sweep REWRITES these now and names them under `:patterns-rewritten`; a row under `:left-behind :via :regex` is the residue it could not reach, and is a finding. Measured at seven in one wave before this was automated, two of which survived three green done-points), **length constants** (`(subs k 19)` standing in for `(count "<the prefix>")` — take the tail from the prefix you matched, so the two cannot disagree), and **a second branch of the same `cond`** a few lines below the one you just fixed. Then `config_file {path "vocabulary" key <old> value <new>}` so the retired spelling is declared. **Do not spell what you can delimit**: split on `=` or whitespace and take the field, rather than writing a character class for what an identifier may contain. A class written from the characters you can call to mind omits the ones you cannot, and in a codebase with naming conventions the UNUSUAL character is the significance marker — `!` marks the effectful vars, `/` marks the wildcard-family key — so the loss is not a random third, it is exactly the marked category. Measured twice in one week: `[a-z.*<>]*` dropped `http.static./assets`, the one mount whose absence is silent. Grep to check yourself with a pattern you did NOT use while editing — a verification grep written from the same assumption as the edit shares its blind spot — and if the new name CONTAINS the old one, anchor the search at a segment boundary or every corrected line reads as a violation |
| Extract helper / move forms to another ns | `edit_extract` / `edit_move_forms` (new OR existing target; callers everywhere rewritten; `export: true` for a deep target with outside callers). **Propose the cluster you want and let it close the set for you** — it refuses a two-way split and NAMES the forms that would leave a cycle ("the moved set calls [x y] (staying)"). Add those and retry. Guessing the seam leaves a cycle; the refusal IS the analysis. `export: true` WIDENS per var — a var already `^:export` keeps its level without the flag, so you never pass it just to restate something already true, and passing it does not silently widen the rest. Read `:export-not-landed` on the result: the move checks its own POSTCONDITION against the committed store and names the VAR, so a planned export the store did not actually get is reported rather than discovered later. **And read `:shadowed`, which is the one finding a green write does not cover** — refs INTO the target go bare, so a moved form that binds a LOCAL of that name now calls the local: it compiles, the suite passes, and the behaviour changed |
| Regroup whole namespaces under one prefix | `module_extract {namespaces to}` — the MODULE-grain move, for a namespace that grew into its own component or a set that wants one owning prefix. Each named ns takes its subtree and `-test` sibling. **`dry_run` first, always**: going from two segments to three makes a namespace package-private, so every outside caller breaks at once, and the plan is the only place you see WHICH vars must be hoisted and WHICH CALLERS force each. The write order is the design — hoist (`^:export`), then rename, then declare the edges the moved store actually references — so no intermediate state is one the gate would refuse. Refuses a regroup that would leave a production cycle; a `-test` back-edge is not one |
| Delete / undo | `edit_delete_form` / `edit_revert`. (There is no reorder: a form's place is DERIVED — definitions before callers, ties by creation order — at every write, so write forms in any order and never ask where one goes.) **A delete whose form still has a caller is REFUSED**, naming every caller — the same stance `ns_delete` takes for a namespace something still requires. Only `:static` references count (a quoted symbol or a `^{:covers}` marker names a form without needing it), and a recursive function is not its own caller. To remove a caller and its callee together, delete in REVERSE DEPENDENCY ORDER — callers first, callee last, one call each; every step verifies and every intermediate state loads. Two forms that call EACH OTHER have no valid order: `:replace` one (a one-step group) to drop the call, then delete both. `query_depends {on "ns/name"}` still answers the question BEFORE you write, and is worth asking when you are planning a removal rather than discovering its size from a refusal. Recovery for any write is `undo {deltas 1}` — but `undo` walks back only YOUR OWN writes, so a delete made under a different agent (a `--call` script, another session) answers `no writes of yours to undo` while looking straight at it; that case needs `episode_revert` |
| Comment on a form | `edit_comment {ns name text}` — the block rendered above it. A comment BELONGS to a form; there is no such thing as a comment between forms |
| Risky experiment | `branch_create` → work → `branch_switch` + `branch_merge` |
| Declare a module dependency | `module_dep {from to prompt}` — one edge, say why; `remove: true` retracts |
| Retire a declaration | `remove: true` on `module_dep`, `module_purity`, `module_platform`, `module_role` — all four. Retiring is not the same as declaring the permissive value: `:external`/`:jvm`/`:product` is a CLAIM, absence is no claim |
| Declare a namespace's purity tier | `module_purity {module tier prompt}` — `:pure` (referentially transparent) / `:internal` (mutates in-process only) / `:external` (IO). Namespace PATH, most-specific wins; declaring verifies the FORMS already there. Undeclared = `:external` = ungated |
| Keep a benchmark / script / one-off CLI out of what ships | `module_role {module "instrument" prompt}` — for code a HUMAN runs by hand, as opposed to `:product` (the default: the system runs it). It is not a label, it MOVES the code: an instrument materializes under `instruments/` rather than `src/`, so any build that jars `src` excludes it **without knowing what a role is**, and it drops out of the architecture view so a harness cannot sit on top of what it measures. Namespace PATH, most-specific wins, so one call covers a subtree. **Declaring is REFUSED while product code requires the module**, naming the callers — otherwise the jar carries a require to a namespace it does not contain and you find out at a consumer's load time. A `-test` requirer is fine: a test does not ship either |

**A declaration tells you which axes it checked.** Every register write —
`module_purity`, `module_platform`, `module_role`, `module_dep`, `config_file` — returns
`:verified` and `:unverified` axis lists plus a `:note` saying where an
unchecked axis IS judged. **Read `:unverified`, because a clean declaration is
not a clean bill of health:**

- `module_purity` checks the forms; it does NOT check layering (does this
  namespace require a LOOSER tier?). That is a whole-graph property and only
  `full_check` reports it — so a tier can be accepted and stand for many writes
  before anything contradicts it. Declaring `:external` verifies nothing at
  all, because `:external` asserts nothing.
- `module_platform` verifies NOTHING about the code; `compile_client` is what
  proves a `:cljc`/`:cljs` namespace actually compiles.
- `module_role` checks the one thing that would BREAK — that no product
  namespace requires an `:instrument`. What it cannot check is the claim
  itself: that a human rather than the system runs this code. Nothing in the
  store distinguishes a benchmark from a scheduled job, so `:instrument` is
  the one register value that is purely your assertion.
- `module_dep` checks cycles over PRODUCTION edges; whether anything uses the
  edge is `query_depends {modules true}`'s `:unused-edges`, and whether only
  TESTS use it is the same call's `:overstated-edges` — a production edge no
  production code crosses. Fix those when you see them: the cycle check reads
  declared edges, so an overstated one can refuse a legitimate declaration in
  a module that has nothing to do with it. `test_only: true`
  declares an edge for the module's `-test` namespaces alone — production
  under that module is still refused, and a test-only edge is not a production
  edge so it is never a cycle. **Reach for it when a fixture must drive a
  surface that calls back into its own module** — a done-time advisory can
  only be tested by writing code and calling `done`, so the fixture
  necessarily calls the operation surface. The cycle refusal offers this
  itself when every namespace crossing is a test.

**Declare a tier BEFORE you move a namespace, or its destination declares it
for you.** `ns_rename` carries an EXPLICIT tier along with the namespace — the
declaration is re-keyed to the new name and nothing changes. It cannot carry
one that was never made. An undeclared namespace is `:external` only because
nothing more specific claims it, so moving it under a prefix that DOES claim
something silently re-tiers it: move an undeclared IO namespace into a module
declared `:pure` and it inherits `:pure`, which is a tightening nobody wrote
and nobody reviewed. The next `done` names it (`:tier-governance`), but as a
finding to clean up rather than a decision you made. One `module_purity` call
before the rename costs nothing and makes the tier survive the move by being
stated rather than inherited.

`edit_move_forms` into a NEW namespace does carry the tier for you — the
target is seeded from the source's, since the forms satisfied it one delta
ago — but only when the SOURCE declared one, and only when the target would
otherwise be governed differently. So the rule is the same rule: an undeclared
source has nothing to carry, and its forms land in whatever the new name
inherits. Declare the tier and the relocation verbs both keep it.

**A relocation is the one path around every write gate — so expect the next
`done` to have opinions.** Both the purity tiers and the module rules are
inherited from a namespace's NAME and enforced when a form is WRITTEN; a
rename or a move changes the name without writing the forms, and `ns_rename`
rewrites its own callers, which then never pass a gate either. `done` closes
that hole from both sides — `:tier-governance` for a namespace whose new
prefix it cannot satisfy, `:module-governance` for a call a relocation put
outside a module rule — and `full_check` reports whatever stands store-wide
(`:tier-layering`, `:module-violations`). Both are error-grade: they are what
a write gate would have refused outright.

**On the module side the namespace that MOVED is usually not the one
reported.** Taking a target from two segments to three makes it
package-private to its parent subtree, so it is the CALLER — which did not
move — that is suddenly reaching in. Fix it at the target (`^:export` on the
defn name hoists it into the module's surface; `^{:export "prefix"}` exposes
it to one subtree) or at the call. `module_extract` does this hoisting for you
and reports which caller forces each export; `ns_rename` does not.

**And a scoped `^{:export "prefix"}` breaks from the OTHER end.** The string
names the CALLER's subtree, so relocating the caller invalidates an export in
a namespace nothing touched — often in a module you are not working on. Same
blind spot, mirrored: re-point the string, or widen it to plain `^:export` if
the var really is module surface. Moving the CALLEE breaks it too, and more
quietly: a scoped export names exactly ONE subtree, so a var reached by two
callers that used to share a module needs plain `^:export` the moment the
regroup separates them.

**A cross-module rename creates architecture debt NO GATE CAN SEE — so read
the `:module-debt` it hands back.** `ns_rename` rewrites every caller's require
and qualified refs through a path that runs no write gates at all. A crossing
that would be refused outright if you typed it is therefore created without a
murmur: nothing turns red, and the first thing to mention it is a `done` some
time later, reported against the unmoved CALLER — which never moved and does
not name the rename. So the rename reports it itself:

- **`:edges-needed`** — grouped to the `module_dep` calls you have to make, not
  to the hundreds of call sites the rule speaks in. Two things the count gets
  wrong by hand: every CALLER's module needs an edge, not just the module you
  are moving; and `:test-only` is read off who ACTUALLY crosses, so a crossing
  only `-test` namespaces make comes back `test_only true` even if the old edge
  was declared production. Declaring the production version there would
  overstate the architecture — the same judgement `:overstated-edges` makes
  after the fact, offered before it instead.
- **`:visibility`** — calls that now reach a package-private namespace, because
  going from two segments to three makes one. Each row's `:error` names the
  options.
- **`:cycles`** — the edges `module_dep` is about to REFUSE. This is the one
  worth reacting to immediately: it means the regroup as drawn cannot be
  declared, and finding out at rename ten instead of rename one is the
  difference between a rethink and an unpick.

The loop is rename → read `:module-debt` → `module_dep` what it names → next
rename. Do not carry the debt across several renames: the reports stay
correct, but you lose which rename caused what. If `:cycles` fires, stop and
`undo` rather than declaring around it.
- `config_file` validates two paths: `capabilities` against the capability
  registry and `rules` against the rule catalog, so a dial that governs nothing
  is refused at the write with the rule you probably meant. Every other path —
  `gates`, `client` — is recorded as given, key and value unchecked, and the
  result SAYS which happened (`:verified` / `:unverified`). A dial is set once
  and never re-read, which is why an unchecked one is silent twice: the thing it
  meant to name keeps its default, and the store carries a line that reads like
  configuration. A dial orphaned by a later RENAME was valid when written and so
  cannot be refused; it turns up as `:orphaned-dials` in `full_check`.

**A rename tells you what it did NOT rewrite.** `ns_rename` rewrites every
SYMBOL — including quoted ones inside data literals — and deliberately leaves
strings alone, because a namespace name inside a string might be prose, a
path, or a generated program. It now returns `:left-behind`, grouped by how
each occurrence was found, plus a `:note`. **Read it.** The dangerous rows are
TOKEN strings (`:prose false`): a path, a `:main-opts` namespace, a `(require
'ns)` inside a program string — those BREAK, where a docstring mention merely
reads wrong. `:test-sibling` means the `-test` namespace still carries the old
name, which files its tests under the old module. Absence of `:left-behind`
means checked-and-none, not unchecked.

**SOURCE inside a string comes back under `:string-source`, and it is the one
row whose sibling half DID move.** A fixture written as `(store/ingest st
'acme.billing "(ns acme.billing)\n…")` names the namespace twice, three tokens
apart. The quoted symbol is a token and gets rewritten; the `(ns …)` inside the
string does not. What you are left with is a fixture that ingests source
declaring one namespace under the name of another — and it stays green, because
a fixture shaped like that usually asserts `nil`. Rank these above ordinary
token strings: nothing else in `:left-behind` is a pair that has come apart.

**REGEX literals are REWRITTEN for you now, and reported under
`:patterns-rewritten`.** A pattern escapes its dots, so `acme\.billing` shares
no literal text with `acme.billing` and the text pass walks past it. That used
to be reported and left to you, on the reasoning that a pattern is an INTENT
rather than a name — but slopp owns the dialect, and a dot in a dotted name it
governs is a SEPARATOR, so there was never an intent to guess at. Only the NAME
moves; the rest of the pattern is your own matching.

It is reported rather than done silently for the reason `:requalified` is: a
rename's diff must not contain a change to what a predicate MATCHES without
naming it. Read the rows. `:left-behind :via :regex` still exists as the
RESIDUE — what the rewrite could not reach — and should be empty; a row there
is a finding.

Why it earned automation: the failure is asymmetric. A PRESENCE assertion on a
stale pattern turns red and you go fix it, while an ABSENCE assertion — `(is
(not (re-find #"acme\.billing" src)))` — becomes permanently true and guards
nothing, forever, with no symptom. Measured at seven literals in one wave, two
surviving three green done-points.

**Qualified KEYWORDS come back under `:keyword`, and they are the silent
class.** `:acme.billing/customer-id` survives `acme.billing` → `acme.invoice`
intact, because a keyword is not a reference — nothing breaks, no test turns
red, and the name simply starts lying. A broken token string at least turns
something red; this one has no second chance, which is why it is listed with
the keyword text spelled out. Whether to rewrite each is a JUDGEMENT, and that
is exactly why the rename does not: a qualified keyword can be a wire or
storage key that something outside your store already holds, and re-spelling
it there breaks a consumer slopp cannot see.

**Stranded ALIASES come back under `:alias`, and each row carries the fix.** A
rename rewrites the lib symbol in every caller's require clause and never the
`:as` beside it, so `[acme.billing :as billing]` becomes `[acme.invoice :as
billing]` — syntactically perfect, and every call site in that namespace goes on
reading `billing/total` for a namespace called invoice. Harmless while the old
name means nothing; the day it is REUSED for something else, the alias points at
a real and different module, which reads identically and is the worse failure.
`:suggest` is the alias to hand `ns_realias` — omitted where that caller already
spells another lib that way, because then the realias would be refused.

An alias that reads correctly for BOTH names is not reported: a namespace moving
between modules under the same last segment (`acme.api.query` →
`acme.read.query`, aliased `query`) strands nothing, and that is the ordinary
rename. What the report cannot see is an ABBREVIATION — `caps` for
`…capabilities` is derived from nothing readable, so a rename that makes it
wrong makes it wrong silently. Prefer aliases spelled from the namespace.

**Red-first is native:** a spec in a `-test` ns may reference store fns
that don't exist yet — it lands as a REAL red (`:red-first` names the
missing vars, stubbed in-image as failing); implement them to go green.
It works across a NAMESPACE boundary too: `ns_create {ns "acme.core-test"
requires ["[acme.core :as c]"]}` creates `acme.core` empty and says so in
`:also-created`, so the spec lands red rather than failing to load. Only
requires sharing your root are invented — a library is never conjured over.

**References never hide in strings:** in-process references in data use
`#'var` literals; late binding across a load cycle uses
`(store/late-ref 'ns/name)`; vars invoked from OUTSIDE (CLI, wire, eval
injection) declare `^:entry-point` on the name. These carriers are what
renames, moves, and the unused gate can see — a naked quoted symbol or a
var name in a string is invisible to all three.

**Dead surface fails the gate:** a public `defn`/`def` nothing in the
store calls is an ERROR at `done` and refuses milestones (globally).
Deliberate? Mark the NAME: `(defn ^:unused-ok f ...)` — external surface,
string-eval'd or runtime-resolved entries. The dial polices itself: a
marker on a var that IS called fails with "remove the flag". Fixture
namespaces in tests follow the same rule (and edits must KEEP the marker).

**Every escape marker takes a WHY, and should carry one.**
`^{:unused-ok "library surface for external consumers"}` discharges exactly
as the bare `^:unused-ok` does — a string is as truthy as `true` — and the
dial stops being a mute flag. Same for `^:entry-point` (invoked by WHAT?),
`^:ambient-ok`, `^:breaking-ok`, `^:foreign-keys`, `^:legacy-ok`,
`^:side-effect`. A bare one on a form you touched draws the `marker-why`
advisory. The exception is `^{:export "x.y.z"}`, whose string already means
the subtree it widens to. **A marker slopp does not know waives nothing while
reading exactly as though it does** — `^:unusedok` is not `^:unused-ok`, and
nothing fails; `store_doctor` is what finds those.

**Before you write the WHY, check that the escape still applies.** A marker
is a CLAIM about the code around it, and claims go stale — the caller it was
waived for arrives later, and nothing revisits the waiver.
`query_depends {on "ns/name"}` settles it in one call: it prints `:callers`
beside `:declared [:unused-ok]`, which is the claim and its evidence side by
side. If there are callers, DELETE the marker; do not annotate it. Measured
on slopp's own store while clearing this advisory: 3 `^:unused-ok`, one of
them stale on a population somebody had curated by hand. Annotating that one
would have bought a well-written sentence explaining why a rule that cannot
fire is permanently waived — which reads exactly like the true ones, and is
worse than the bare marker it replaced.

**Put the MECHANISM in the reason — then fork on whether it can end.** A
justification says why something is forced; a reader needs to know whether that
can stop being true.

- **A PERMANENT mechanism gets no expiry.** A bundle entry point can never
  acquire a caller; a proxy can never name the shapes it forwards. Bolting a
  review date onto one of those is writing a lie, and the next reader has to
  disprove it.
- **A CURRENT LIMITATION names what would end it, in both directions.**
  `"no caller while the plug-in seam does not exist — if the seam lands this
  has a caller again; if slopp decides against it, this goes"` converts the
  question from a JUDGEMENT into a CHECK. A reader does not weigh whether the
  escape is dead; they look at whether the seam landed.

This matters more than it sounds, because **the better the argument, the longer
it survives.** A bare marker is wrong for as long as nobody looks; a
well-defended one is wrong for as long as everybody who looks is satisfied. The
second is the more expensive habit, and naming the expiry is the counterweight —
a well-argued limitation is still a limitation, and the argument is not the
check.

Measured across two stores in one week: an endpoint whose docstring argued
across three revisions that its declaration was a limitation rather than a
description. It was still wrong — but it had named the field that would have to
exist, so it converted the day that field landed rather than being re-defended.

**Tiers are not your problem:** `done` runs the WHOLE in-image suite plus
the `^:external` tests your changes impact (in a separate JVM,
automatically; a large slice defers and rides findings as
`:external-pending`). `commit_point` has NO checks of its own — it runs
done and gates on that verdict. There is exactly ONE bar, and it is
`done`. The whole-store answer is `full_check`, and nothing forces it. **A write's `:status` says which tier actually ran**:
`:green` = the impacted tests ran and passed · `:partial` = some ran, but
impacted `^:external` ones were DEFERRED (`:external-pending` names them —
a green here would be earned by other tests) · `:unverified` = nothing ran,
with `:reason` distinguishing `:all-impacted-external` (by design, the
done point runs them) from `:no-covering-tests` (yours to fix) and
`:scope-ran-nothing` (a slopp bug — report it). Writing an `^:external`
test is `:partial` or `:unverified`, never green — **to see it go red-first,
`test_run {only ["ns/the-test"]}`: a named `^:external` target runs in its
own tier automatically (one serial fresh JVM).** You never run `test_run`
as a ritual — it's for spot-checking one namespace or test mid-flight. A
run repeated with nothing landed since answers from the run it already made
(`:standing true`, no image eval, no JVM); `fresh: true` runs anyway. Red runs return
`:all-failing {file [tests]}` and `:themes` (clustered causes) — read
those before drilling into blocks.

**A repro can be too minimal.** Red-first protects you only if the test is
red for the REASON you think. Stripping a bug down to its smallest case can
strip out the very thing that triggers it, and then a green test reads as
"not the cause" when it means "not reproduced". A real one from this
codebase: a crash in a `sort` was minimised to a single-element collection —
and sorting one element never calls `compare`, so the test passed over a
live bug and sent the diagnosis in the wrong direction. When a repro comes
back green, that is a RESULT to explain, not a fact to accept: check that
the mechanism is still present before concluding the cause is elsewhere.
Reach for `query_eval` to look at what the code actually sees rather than
bisecting features by intuition — measuring the analysis found this one
after four wrong guesses.

**Every assertion must be observed failing at least once.** The load-bearing
part of red-first is not "test before code" — it is that each `is` was seen
red before it was trusted green. ADDING an assertion to an already-green test
skips that, and nothing downstream notices: `(is (empty? (:unused r)))` where
`full-check!` never returns `:unused` is `(empty? nil)` → passes no matter what
the code does. A green you never watched fail proves nothing. When you extend a
passing test, break the subject once and confirm the NEW assertions go red — or
you have written coverage theatre that reads as verification. **`done` asks
about this now** (`assertions-never-red`): a test that gained assertions and
never bounced this episode comes back as an advisory, because a rule that
relies on you remembering is not a rule.

**And watch its NEIGHBOUR stay green in the same run.** The third clause,
and the one that separates "the assertion is real" from "the test ran at
all": a red you caused deliberately proves the assertion is wired to the
subject only if something ELSE in that same run was green at that moment. A
fixture that failed to build fails every assertion downstream of it, so it
produces the red you were expecting for a reason that has nothing to do with
what you are testing — and it reads identically. Assert the fixture BUILT
(`:forms`, a count, the value you actually got) beside what you are proving,
and read both. Two writes in a row went red here for fixture reasons that
looked exactly like the defect under test.

**A filter used as evidence needs a positive control.** The sibling of the
above, and the cheapest habit on this page: before believing a filter found
nothing, assert its POPULATION was non-empty. Two empty sets compare equal, and
a scan that silently found nothing passes every comparison you make against it.

```clj
(is (seq found)                          ; ← the positive control
    "no namespace declares an endpoint — the scan found nothing, which
     would make the comparison below pass by being empty on both sides")
(is (= expected (set (map :ns found))))
```

**The population starts at the FIXTURE, and that is where this gets missed.** A
setup step that failed builds an empty population, and an empty population
satisfies every absence assertion below it — so the test is green, cheap, and
about nothing. `ingest!` and every other write verb RETURN `{:error …}` rather
than throwing, and a fixture is where a return value is least likely to be read.
The specific trap in a store: `ingest!` runs the module gate, so a second
namespace one module over from the first is refused, and the two-namespace
fixture you thought you built is one namespace.

```clj
(api/ingest! sess 'acme.core.thing  "…")
(api/ingest! sess 'acme.core.caller "…")   ; same module — or this is refused
(let [r (api/ns-rename! sess 'acme.core.thing 'acme.moved.thing …)]
  (is (= 2 (:forms (:renamed r))))         ; ← the fixture's own control
  (is (nil? (:alias (:left-behind r)))))   ; ← meaningless without the line above
```

**A fixture carries a BRANCH to make it reachable, and one instance is the
right number.** Adding a second instance of a branch the fixture already covers
buys nothing and costs representativeness — but the sharp half is the other
direction: **removing the last one is something you do by accident while
improving the fixture.** Give an undocumented endpoint its real docstring, give
every form a second callee, and the branch that existed for the no-doc case or
the one-callee case stops being reachable — every test of it still passes, for
the wrong reason, and nothing says so.

So before you tidy a fixture, ask what each oddity in it is CARRYING. If the
answer is "a branch", the oddity is the test. Where it is not obvious from the
fixture itself, say so where the fixture lives rather than where the branch is
implemented.

Assert the fixture, not your intention for it: something that counts what the
setup actually produced, before anything is read off it.

**Read the MESSAGE, not the colour.** A red test discharges red-first only if
the failure is the one you set out to reproduce. A fixture broken in some other
way can fail the same assertion, with the same count, and the difference is
visible only in the text — one bug reproduced by hand went red on the right
assertion for a completely unrelated reason (a var the fixture never defined,
throwing inside an ARGUMENT before the code under test was reached). Aim a fix
at that and it lands green over an untouched defect. This is the same trap as
the fixture control above with the sign flipped: there a broken fixture
satisfied an ABSENCE assertion, here it satisfied a PRESENCE one.

**And put the population count in every ad-hoc `query_store` too.** A scan
returning `{:offenders []}` is indistinguishable from a scan that read nothing
— a guessed arity, a filter that matched no namespace, a key that is spelled
differently than you remember. Return `{:namespaces-scanned n :forms-scanned n
:offenders []}` and the empty answer becomes evidence. Compare the count
against something you already know (`full_check` reports the store's form
count) and it becomes proof.

**And if you are building the FILTER, probe it both ways.** A detector needs an
input it must flag and an input it must NOT — verify only that it can fire and
every false-positive mode goes untested. A guard shipped here with a can-fire
probe and no can-stay-silent one; the first rename it met was one whose new
name contained the old (`a.b.` → `x.a.b.`), the match was an unanchored
substring, and it reported every freshly-corrected line as a violation. A check
that flags everything is exactly as uninformative as one that flags nothing,
and it teaches its reader to stop looking.

**And a control on the POPULATION says nothing about your PATTERN.** These are
different claims and it is easy to have the first while believing you have the
second. A guard here asserted that one namespace's rendered source does NOT
mention another, and carried two population controls — 50+ namespaces in the
store, and a `re-find` proving the right source had rendered. Then the named
namespace was RENAMED. Both controls stayed green and the absence assertion went
on searching for a string that could no longer occur anywhere.

A search pattern is DATA: `ns_rename` rewrites requires, qualified references,
quoted symbols and prose, while `rename_sweep` matches text and a regex escapes
its dots — so no verb REWRITES it. Pair the absence with a match against
something you KNOW contains the name:

```clj
(is (seq (re-seq #"acme\.client" (render/render-ns st 'acme.client))))  ; the needle still bites
(is (= [] (vec (re-seq #"acme\.client" src))))                          ; …and it is absent HERE
```

slopp handles this one for you now: `ns_rename` and `rename_sweep` REWRITE
every pattern spelling the old name and report it under `:patterns-rewritten`.
The **`stale-pattern`** advisory remains as the backstop, flagging a regex
naming a name in your store's OWN root family that is neither a namespace nor
a prefix of one — which is what catches a pattern that entered by some other
route than a rename.

The two are not redundant, and the gap between them is worth knowing: the
advisory can only see a pattern naming a namespace that no longer EXISTS. A
rename that frees a name for something else to reuse leaves a pattern that
still resolves and now matches the WRONG code, and no existence check can ever
say so. Only the rename's report catches that one.

The advisory's scoping is deliberate and it is the fixture rule read
backwards — *a fixture that names no real production code is exactly a fixture
this check cannot see*. One more reason to name fixtures after nothing real.

**And when you SAMPLE a collection, the sample is a population too.** Reading
the keys off the first row of a sequence whose keys are OPTIONAL tells you what
that row has, and nothing about the shape. Measured both ways in one exchange
here: a consumer read `[form form-id module ns]` off one graph node and
reported that the endpoint had stopped sending `:sig`; over the whole set, 32
of 58 carried it, and the ones without were forms with no arglists. Take the
UNION across rows, or say "this row has" rather than "the endpoint sends".
It is the fixture trap wearing different clothes — **what is absent from the
sample reads as absent from the contract.**

**Two fields that coincide in the common case are ONE field for testing
purposes.** A boundary report carries `:from` (a namespace) and `:from-module`
(its module). At MODULE grain those hold the same string, so a consumer counted
one and labelled it the other, and every test over its module-grain fixture
agreed — the fixture could not distinguish the two readings, so no assertion
over it could. The mislabel surfaced the instant real namespace-grain data
arrived. The rule: **test at the grain where the two differ, or you have tested
neither.** Suspect any pair where one value is derived from the other — a
qualified symbol and its namespace, a path and its root, a form and its
container.

**And a comment asserting that two functions AGREE is a test nothing runs.**
Worse than no comment, because a bare duplicate invites suspicion while a
documented parity disarms it — for exactly the reader checking whether both
paths were covered. Two producers here built the same thing, one was fixed,
and the line above the other said *"the same split X makes locally"*: true when
written, and the commit that made it false was the commit that made that code
wrong. Nothing about fixing the first draws your eye to prose in the second.

The tell is cheap and greppable: **a comment naming another function as the
reason THIS code is correct is a candidate for being that function's test
instead.** Then it is redundant rather than wrong. When you write the test,
assert **the part that must not vary, not the whole output** — a parity test
over everything gets deleted the first time a legitimate difference appears.

**This applies to a shell command exactly as it does to an `is`.** A rename was
verified with `query_capabilities | grep ':set true'` → no output → read as "the
rename landed". Empty meant two things at once: *nothing is set*, and *the tool
cannot see what is set* — and it was the second. The fix is one more command:
count the population first (`| grep -c ':key'`), because a non-zero count is
what makes the zero from the real filter mean anything.

