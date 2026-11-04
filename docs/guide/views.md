# Browsing a store: the views

The code lives in a store, not in files, so there is no tree to open in an
editor. slopp serves a set of web pages instead. They exist for one job: let a
person understand a system an agent wrote, and check what the agent did,
without reading all of it.

The pages are read-only. You cannot edit through them, and that is on
purpose (see [what the views do not do](#what-the-views-deliberately-do-not-do)).
All edits go through the agent's tools, so there is one way to write and many
ways to look.

## The reviewer's path

Review starts at a milestone, not at a file.

1. **Timeline** (`/`). Commit points, newest first, each linking to its own
   change screen. Below them is the working set: what has changed since the
   last commit point, with a capped listing and an exact count.
2. **Change screen** (`/change/<from>..<to>`). One range of history, read
   module, then namespace, then form. Every changed form shows three things in
   this order:
    - the **recorded ask**: the reason the agent gave when it wrote the form,
      and the user's verbatim request it was working under (see
      [History and provenance](history.md));
    - a **per-line diff**;
    - the **blast radius**: what calls the form, and which tests cover it.

   Above the forms, the screen says what it did not see (changed forms no test
   was observed running, housekeeping writes) and lists any test an agent
   flagged in the range with `flag_test`: the agent was refused an edit to a
   test grading its own change and recorded its objection instead. Each flag
   names the test, the reason and the agent, and stands until a person edits
   or deletes the test.
3. **Form permalink** (`/store/form/<id>`). Each form on the change screen links
   here. The page is laid out for someone who arrives cold from a link: a
   breadcrumb, the callers above, the form itself, and the callees below with
   their signature and docstring inlined. You read what a form depends on
   without leaving the page.

The permalink is keyed on the form's stable id, so a link to a form survives
renames and moves. Source is syntax-highlighted from the same parse tree the
store already holds. Joining the highlighted tokens back together gives the
original source byte for byte, and a test pins that.

Going from one commit point to the next is cheap. On slopp's own store, a
range touching 36 forms across 4 modules rendered in under a second.

## Reading by behaviour

A package tree answers "what is here". Reviewing a system usually needs
"what does it do". The Code section adds screens for that. Each is computed
from data the store already has, and each picture has a table or text twin.

- **Ways in** (`/store`, under the module map). Every door the store declares:
  the app's main entry, HTTP routes, commands, web screens, and forms marked as
  entry points. Each one links to its sequence.
- **Sequence** (`/store/form/<id>/sequence`) and **path**
  (`/store/flow?from=&to=`). A static trace of what a form calls, in the order
  the body is written, depth first. It is drawn with one lane per module, not
  per form, so a boundary crossing is easy to see. An indented step list sits
  next to the picture. Cycles, callees already drawn, and cuts for depth or
  fan-out are marked, never dropped silently. The path screen asks you for a
  start and an end; with either blank it answers with a note.
- **Data dictionary** (`/store/data`). Keys ranked by how many modules use
  them. Opening a key lists its users by module, split into forms that name the
  key and forms that destructure it. It does not claim to say which form
  produces a key, because the index cannot tell yet.
- **Conformance** (`/store/conformance`). Every module-to-module dependency in
  production code, compared with the manifest the project declared. Each edge
  is convergent (declared and used), divergent (used, not declared), absent
  (declared, not used), or test-only. This is the screen for "does the
  architecture match what we said it was".
- **Overlays and treemap** (`/store?overlay=...`, `/store/treemap`). The module
  map can be tinted by size, effects, unwarranted forms, churn, or risk. The
  boxes do not move, so you compare dials on one picture. Churn and risk cost
  more to compute (they read history and run the review scan), so they run only
  when you ask. The treemap shows where the weight of the code sits.
- **Tests as the spec.** A namespace, module, or form page lists the tests that
  cover it, shown as the sentences their names make. For a module this is
  often the fastest answer to "what is this for".
- **Story** (`<subject>/story`). The commit points that touched a subject, with
  the asks behind them.

## Deterministic, and honest about what it did not see

Nothing on these pages is written by a model at view time. They are computed
from the store the way `render` computes source: the same store gives the same
page. Two readers of one commit point see the same thing, and a page does not
change because someone asked again.

The reference graph is built by static analysis, and static analysis misses
things (dynamic dispatch, calls through data). So the pages never present it as
complete. Every caller and callee edge says how it was found, and callers on
the form page are grouped by that method. Where the tooling is guessing or
cannot see, the page labels it. Read a missing caller as "none found", not as
"none exist".

The same goes for coverage. Warranty counts come from the live server's trace
of which tests reach which forms, so they are the real numbers for that
session, not a cached summary.

## What the views deliberately do not do

- **No model-written summaries.** A paraphrase of code is wrong often enough
  that a reviewer cannot trust it. The pages show the code, the recorded ask,
  and computed facts.
- **No editable projection.** Nothing on a page round-trips back into the
  store. A second editable copy of the code drifts from the first.
- **No claim that the reference graph is complete.** See above.
- **No picture without a twin.** Every diagram has a table or text version, so
  the page works for a screen reader, a grep, and anyone who does not read
  diagrams well.
- **No readability score.** Published measurements find that such scores do not
  predict whether people understand code. The pages report counts and facts
  instead.
- **No reordering for narrative.** Forms appear in store and dependency order,
  and sequences in the order the body is written.

## Opening them

The slopp server serves them for each project it hosts:

```
http://127.0.0.1:7357/p/<project>
```

The agent should hand you the link. `session_brief` reports it as `:pages`,
and the `slopp-review` skill has the agent give you
`<pages>/change/<from>..<to>` alongside its findings. For a finding about
behaviour it links the form's sequence screen; for a layering finding it links
conformance. The agent's report says what it concluded, and the page lets you
check.

Do not use the brief's `:api` address for this. That is the project's read API
and returns JSON only, so a person opening it gets a 404.

For how an agent drives review, see [History and provenance](history.md#reviewing).
For the module manifest that the conformance screen checks against, see
[Modules and purity tiers](modules-and-tiers.md).
