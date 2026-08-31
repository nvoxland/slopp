<!-- reference topic `agreement` — served whole by `help {topic "agreement"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

### When you write a list that must agree with something else

This is the most common way a green check comes to mean nothing, and there are
exactly four right answers. Picking by feel is how a codebase ends up with the
same defect solved seven different ways.

**First ask: are BOTH ends things slopp can address** — a namespace, a var, a
keyword, a module? `query_depends {on X}` answers relations between those, so if
both ends qualify you are on the easy path.

**Never enumerate call sites with a search — ask `query_depends {on "ns/var"}`.** Its `:red-after` is the other half of a var's blast radius: the tests that went red in episodes where the form changed, most often first — what usually breaks when you touch it. The key is ABSENT when nothing has ever gone red beside it; read that as no evidence, not as safety.
It is alias-blind because the reference graph is kondo-resolved, and a text
search is not: one namespace is routinely required under several `:as` names,
so a grep for `alias/name` silently covers a subset. Measured on this codebase:
five aliases each resolve to more than one namespace (one to THREE), and a
signature change swept by grep twice reported every caller migrated while two
were still broken — found minutes later by the whole-store check. The wrong
method LOOKS thorough, because a search that misses a hit is shaped exactly
like a search that found them all.

**The graph answers about the STORE, and an empty answer is not "nobody calls
this."** Only edges whose TARGET is a store form are recorded, so a call into
framework or library code — anything vendored at build rather than stored —
resolves to nothing. Asking `query_depends {on "slopp.webapp.dom/mount!"}` in
an app store returns empty for a store that plainly mounts, and empty is
exactly the shape of "no callers". Before you read a `[]` as absence, check
that the target is a form the store actually holds; when it is not, the
question has to be answered another way (the analyzer resolves the aliases, a
completeness test derives both sides).

| both ends addressable? | what you need | do this |
|---|---|---|
| **yes** | a rename must rewrite it | nothing — the reference graph already carries it. `rename_sweep` / `ns_rename` move it, and `:left-behind` reports what they could not |
| **yes** | *every A must have a B* | a test over `query_depends` / a store read. `done` already does this for dead public surface |
| **no** — one end is a string literal, a set of names, generated text, prose, or a source shape | *every A must have a B* | a **completeness test** — derive both sides and take the set difference, naming the members |
| **no**, and one end is expensive to rebuild (a jar, an image, a deployed artifact) | *is this current?* | **stamp it** — the artifact carries which store head produced it |

**The last row is the one people get wrong.** The test is one sentence: *you
cannot rebuild the jar to check the jar.* If re-deriving a side is expensive, or
that side lives outside this process, stop trying to compare and make the
artifact carry its origin instead. slopp does this for its own jar
(`META-INF/slopp/head.edn`), and `build` gives your artifacts the same seam.

**For the third row, `slopp.ops.external/built-store` is the seam.** It
reconstructs a store value from a materialized project, so an `^:external` test
can ask whole-store questions. It **refuses** on a directory with no source
rather than returning an empty store — because a guard scanning nothing is
indistinguishable from a guard finding nothing wrong, and that distinction is
the whole point of the check.

**Before you write any of them, ask whether the second list should exist.** If
the describing fact can live ON the thing it describes, move it there and delete
the list — a collapse always beats a check. A set of names sitting three hundred
lines from the things it names is the shape to look for. Only a real boundary
justifies the copy: a protocol, a subprocess, a rendering, prose for a human.
Those consumers cannot see the original, which is *why* the second copy exists
and why it has to be checked rather than removed.

**And the tell is not "a guard that enumerates" — it is a guard whose population
is written somewhere other than where the population lives.** A guard that
iterates the real registry grows by itself when you add a member. A guard that
restates the members goes stale in one direction and red for no reason in the
other. Where the population is derivable, deriving it costs one line:

```clj
;; grows by itself
(doseq [t (vals my.app/handlers)] (is (registered? t)))
;; goes stale silently — and looks identical in a diff
(doseq [t [:create :update :delete]] (is (registered? t)))
```

The general form is worth memorizing, because it costs one line and it catches
a class you cannot otherwise see: **an empty result standing in for a verified
one.** Ask it of any check whose pass is a silence.

**The mirror image, and the more tempting one: a check that PASSES while
answering a narrower question than the one you asked.** The cheap check and the
expensive check are not the same check, and the cheap one is the one that gets
run. Worked example: a fix was reported as shipped on the evidence
`slopp/rules/catalog.clj  direct-http  1` — a grep of the built artifact,
returning a true fact. It proves the rule is DEFINED. It does not prove the
sweep RUNS, and the rule had been defined all along; the wiring was the entire
bug. The one artifact fact that distinguishes a shipped fix from an unshipped
one was the only one the grep could not see.

So when you verify that a change reached an artifact, **grep for the CALL SITE,
not the definition.** `sweep-store!` appearing in `rules.clj` says someone wrote
a function; `rules/sweep-store!` appearing in `external.clj` says something
calls it. The second is one character longer to type and it is the claim you
are actually making. The same asymmetry runs through this whole section: "the
symbol is in the jar" and "the code path runs" are different claims, and the
first is much easier to check, which is exactly why it gets checked.

**A metric and its test, written in the same session, cannot validate each
other.** The fixture gets derived from the metric's own definition, so it can
only ever confirm that definition — including the part that is wrong. A layout
tool grew a `crossings` count with an adversarial 3-crossings-→-0 test beside
it; the count was textbook and it only counted crossings between ADJACENT
layers, so on the real graph — where every crossing came from a layer-SKIPPING
edge — it reported 1 where 8 were on the screen. The fixture could not have
caught it. What did was asserting the property a reader actually cares about
(*no drawn edge passes through any box*) against REAL data, which fails
immediately and cannot be satisfied by a wrong metric because it does not go
through the metric. So: whatever you measure with, assert the user-visible
property against the real store at least once, even when it is harder to
phrase.

**And a measurement that reports "no change" is a suspect, not a result.** The
failure mode is not that an instrument lies — it is that it answers a narrower
question in the wider question's voice, and the tell is a suspiciously boring
answer. Before believing "correct, tested, no improvement here", check the
instrument. This is the same class one level up: *"none"* and *"none that I
looked at"* are different claims that share a spelling, and here the thing with
the two meanings is your own yardstick.

**And the version with a precaution attached: "I did X and the problem never
appeared" is not evidence that X worked.** It is equally evidence that the
problem does not exist. Those two produce identical observations, and only one
of them is a reason to keep paying for X. If a practice earns its keep by
making something NOT happen, watch it happen once without the practice —
deliberately, under conditions you control. A causal claim nobody tested reads
exactly like a measurement for as long as nobody checks the implementation, and
it propagates: into a backlog item, into a docs page, into the next agent's
habits.

**Say less between calls.** Results are structured and self-describing —
never restate a result's contents in prose (eval9 measured: agents wrote
2× the commentary plain-file agents did, and it was ALL of the remaining
overhead — the tool traffic itself is cheaper than files). Between
calls: nothing, unless a decision changed. Final summary: short —
name what shipped and quote result keys (`:test`, `:done`,
`:findings`); don't re-describe what the tools already said.

**Every write must compile — AND must still cold-load.** Form ORDER is not
your job: write forms in any order — the pipeline moves definitions above
their callers, and inserts a marked `(declare …)` itself for genuine mutual
recursion.

The distinction worth carrying: your work hot-loads into a LIVE image where
the vars already exist, so the image happily runs code that a FRESH load
(boot, restart, a clone, the external test tier) cannot load at all. That is
what the cold-load gate is for, and it refuses two shapes — a form
referencing a later form in the same namespace, and a require CYCLE between
namespaces (`would not cold-load — require CYCLE: a -> b -> a`). Both are
invisible to in-image verification by construction, which is why a write can
be refused while every test passes. A cycle usually means a require that is
no longer referenced: drop it, or move the shared code somewhere both sides
can depend on. `edit_move_forms` drops the requires IT orphans on BOTH
sides — the source namespace whose last user of a lib just left, and a
rewritten caller left referencing nothing in the source namespace. Any OTHER
unused require — one an ordinary delete or refactor stranded — you leave
alone: **`done` prunes it for you, and there is no tool to check or remove
one by hand.** At every done point it TRIES removing each require kondo
reports unused; a genuinely dead one is dropped, and one that turns out to be
load-bearing — removing it would break a cold load, a `defmethod`/registration
the reference graph can't see — is restored with a `^:side-effect` marker so
it is never flagged or re-tried. `done` reports what it did in
`:pruned-requires`. (A stale require is not merely untidy: a namespace inherits
the TIER of everything it requires, so one left behind makes a `:pure`
namespace report as depending on the shell for something it no longer uses —
which is why done clears them.)

**Moving a form re-resolves its `::auto-keywords`.** `::foo` is read as
`:current-namespace/foo`, so the same text means something DIFFERENT after
`edit_move_forms` — `::analysis` in `a.b` silently becomes `:a.b.c/analysis`
in its new home. Harmless for a local marker; a live bug when the keyword is
a persisted key, a map key another namespace reads, a `defmethod` dispatch
value, or a cache id. Write the keyword out in full when it has to survive
relocation, and check `::` in anything you move. Hand-written
`(declare …)` is refused; you never need one. Mutating fns end in `!` (rename
with the `:suggest` if warned); `^:reads` marks read-only dep calls;
`^:unsafe` is the dialect escape hatch.

**Modules are enforced.** A module is the first two ns segments
(`logi.parcel`; `x.y-test` belongs to `x.y`). Calling ACROSS modules
needs a declared edge — the refusal names the exact
`module_dep {from to}` call; DECLARE THEN USE (design the dependency,
then write the code). Deeper namespaces (`x.y.z`) are package-private
to `x.y.*`; the `:export` dial on a defn widens it — `^:export` hoists
it into the module's public surface, `^{:export "x.y.z"}` exposes it to
that subtree only. An edge that closes a cycle is refused (the cycle is
named) — judged on PRODUCTION edges, so a `-test` namespace's fixture
require never vetoes an architecture decision, even though it does show
up in the declared manifest. An `:instrument` module (`module_role`) is
out of that view for the same reason and stays in the manifest the same
way, so its edges are still gated while it cannot distort the layers. **A store grown through the tools therefore cannot tangle — but one you
IMPORT can arrive tangled**, and that is the one moment a cycle enters.
`clone` and adoption both report `:cycles`; read it. This is normal in
existing code rather than a defect to panic about: a module is the first
two segments, so `b.app` calling `a.core` while `a.core.impl` calls back
into `b.app` closes a module cycle with no namespace cycle anywhere — a
codebase that loads perfectly well, and what a utility reaching back into
its caller looks like. It is one-time debt: nothing can add to it later,
so untangle by moving what crosses and then `module_dep {from to remove
true}`. A cycle among namespaces INSIDE one module is a DIFFERENT
finding, and `:cycles` will not carry it: both ends are in the same
module, so there is no cross-module edge and the module view reports a
clean store. No agent tool reports that one today — it surfaces on the
module page of the web UI. **Red-first specs targeting a package-private ns go in a
SAME-PACKAGE test ns** (`x.y.z` spec → `x.y.z-test` or another `x.y.*`
test ns): an outside spec naming not-yet-existing deep vars hits the
visibility gate before its stubs can land, and the escape it teaches
(mark the target `^:export`) is impossible for a var that doesn't exist
yet. Read the whole architecture in one call: `query_depends {modules
true}` — manifest, topological :layers, :cycles, :unused-edges (dead
declarations), :overstated-edges (production edges only tests cross),
standing debt; browse what a module OFFERS (public fns +
exports, deps, consumers) before calling into it: `query_depends
{modules true, on "x.y"}`. Public-surface fns warn once when a
write leaves them undocumented — add the docstring.

**DOCSTRINGS ARE MARKDOWN.** Paragraph breaks, `` `code` ``, `**bold**`,
bullet lists and tables all mean what they look like, and a renderer is
entitled to treat them that way. This is a declaration of an existing
convention rather than a new permission — slopp's own docstrings carry markdown
tables, which are unreadable as plain text, and a consuming store measured
markdown in five of six of its own before anything said it was allowed.

Two consequences worth having up front. **A `*` you meant literally needs
escaping**, because a renderer will not guess. And **slopp ships no renderer**:
displaying a docstring is the displaying application's job, so a store that
renders another store's contract owns that end — which is the same split as
every other place slopp publishes data and declines to decide what it looks
like.

**Cohesion decides WHERE code lives; the export dial decides WHO sees it —
they are independent.** Put forms that serve one concern in one namespace (a
deep `x.y.z` for a cluster inside a module); if one has legitimate outside
callers, mark it `^:export` and move on. Never park a form in a grab-bag
namespace — or drag unrelated forms along with it — just to dodge an export
marker: the marker is cheap, a god-namespace is not. Conversely, `^:export`
ASSERTS "this is public surface", so it is not a substitute for putting a form
where it belongs. `edit_move_forms` relocates a cluster in one verified
intent (callers everywhere rewritten, requires added, `export: true` for a
deep target with outside callers).

