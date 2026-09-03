<!-- reference topic `threads` — served whole by `help {topic "threads"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

### You write in a thread; `done` lands it

Every session works in its own private line — a THREAD — and a branch only
ever contains work that reached a `done` with nothing red attributable to it.
Nothing about this is a mode you turn on or a tool you call; it is where your
writes already go.

`session_brief` names it: `:thread {:on "main" :unlanded 7}` means seven
writes are yours alone. Writes carry a `:hint` saying the same thing — once
when the work first becomes private, then every 25 un-landed changes, so a
long episode is reminded without every call repeating it. What follows from that is worth having straight,
because each part surprises somebody:

- **Another agent on the same branch cannot see your work, and you cannot
  see theirs**, until whoever finishes first calls `done`. That is the
  point: you both forked from the same place and neither of you is reading
  the other's half-finished code.
- **When you land second, your work is rebased onto theirs**, once, at your
  own `done` — so every conflict arrives together at a moment you chose,
  instead of arriving one at a time while you are trying to finish. The
  result says so (`:land {:landed "main" :rebased {:merged n}}`). If the
  rebase conflicts or goes red on your work, NOTHING lands and your thread
  is still there: fix it and call `done` again.
- **Another agent's red does not hold your thread.** If the branch is red
  for reasons that exercise nothing you touched, `done` still says
  `:test-status :red` — the store IS red and no commit point can be taken —
  but it names the failing tests as `:foreign` under `:red-attribution`,
  sets `:episode-status :green`, and lands. Innocence has to be proven, so
  a failing test with no trace (`:untraced`), or one the run counted but
  did not show (`:unseen`), keeps the red yours and your thread stays put.
- **A running server serves the branch**, so if slopp is hosting your app or
  reloading its own code, that process keeps running the last landed state
  until your `done` moves it. Your verification image is a different thing
  and always holds your thread's code — which is why your tests are right
  about your work while the server is still right about the branch.
- **`commit_point` lands too**, so a commit point always names a branch that
  contains what it marks. When that land is REFUSED — the branch moved
  and the rebase conflicts, or the thread is unreachable — the commit point comes
  back `:status :unlanded` and carries the refusal in `:land`, because the one
  thing worse than a refused commit point is a green one naming a branch that
  does not hold the work. Resolve what `:land` names and call it again.
- **Identity comes from your harness, before your first write.** Your thread
  is keyed by `(agent, branch)`, and the agent id is the CONVERSATION your
  harness is driving — read once when the server starts. So two agents on one
  store get separate threads without either of them arranging it, and you
  need set nothing. A harness slopp does not recognise falls back to a
  generated id, which is correct rather than degraded: that session is
  nobody's continuation and says so.
- **Your thread survives the process.** Come back with the same agent
  identity and you resume the same thread, un-landed work and all. Come back
  as somebody else and you correctly see only what has landed. What persists
  is the IDENTITY, not the id: landing settles one thread and opens another,
  so the id changes every time and nothing should be keyed on it.
- **`:unlanded` counts WORK, not deltas.** A verification or a done boundary
  is a delta on your thread and not something anyone would call pending, so
  they do not count. It is the same set `query_changes` reports on, which is
  what lets the two be compared.
- **A fresh PROCESS is not a current VIEW, and this is the one that fools
  people.** An external test run spawns a real JVM against a materialization of
  YOUR thread — genuinely isolated, genuinely fresh, and still answering about
  whatever your line holds. If your thread was forked before someone else
  landed, that run is a correct answer about an old store. So when a form you
  know landed reads as missing, "the work is gone" is the LAST explanation to
  reach for. Four cheaper ones present identically: your session's store value
  is behind, your verification image is behind, your THREAD was forked from an
  older branch head, or you are looking in the wrong namespace. `session_brief`
  shows your thread and whether the branch has moved; a one-shot read from
  outside any session (`slopp --call query_store …`) answers about the BRANCH
  and is the tie-breaker. **If your thread holds nothing you need
  (`:unlanded 0`), `thread_drop` and re-fork from the current head** — that has
  beaten fighting the merge every time it has been tried here.

**Gone down a wrong path and want to start over?** `thread_drop` with no
argument abandons your own thread and puts you back where the branch is, with
the work off your store and your image. Three verbs overlap here and they
differ in ways worth knowing before you pick:

| | scope | what happens to the work |
|---|---|---|
| `undo` | the last write, or back to a point | reverted forward — still in your history |
| `episode_revert` | since your last `done` | reverted forward — still in your history |
| `thread_drop` | everything since the last thing that **landed** | the line is settled; unreachable from any branch head |

The scope column is the one that decides it. A `done` that went red landed
nothing, so a thread can hold several episodes — and "I have been going the
wrong way for a while" is exactly the case `episode_revert` is too small for.
Nothing is deleted in any of the three: a dropped thread's deltas stay
walkable, they just stop being on anybody's way forward.

