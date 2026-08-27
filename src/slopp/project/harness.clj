(ns slopp.project.harness
  "WHO is driving slopp, and what they call the conversation they are driving.

  Exists so that nothing else has to know. Identity keys a thread, so slopp
  needs an id that belongs to a conversation rather than to a process — and
  the only party that can supply one is the harness on the other side of the
  stdio pipe. Every harness names it differently, so the alternative to this
  table is a growing `or` of environment lookups in whichever function
  happens to need identity, which is how the core ends up knowing the name of
  a product it does not depend on.

  Expect a declaration and a pure fold over it: `harness-catalog` is the
  table, `conversation-id` reads an environment through it. No IO, no state.

  **Read it at an ENTRY POINT, never in a library function.** A child process
  inherits its parent's environment, and slopp spawns images and test-runner
  JVMs constantly — so a conversation id read at any depth is adopted by
  every subprocess, each claiming the driving agent's thread. That is
  measured rather than feared: it went in once at `session-identity` and four
  tests went red because every test session had become the developer's own.
  `slopp.mcp/-main` reads this and passes an explicit `:slopp.ops/agent-id`
  down; that process is the only one a harness actually spawned.

  Sits beside `slopp.project.capabilities`, and for the same reason: both are
  declarations a project makes about itself that the rest of the system
  derives from rather than duplicates."
  )

(def ^:export harness-catalog
  "Every HARNESS slopp knows by name, and the variable each one sets to name
  the CONVERSATION it is driving.

  **A harness never owns a generic name.** Support for one lives in a table
  named for the concept, and the pattern must work for harness #2 without
  renaming harness #1 — so the identity function reads this and contains no
  harness name of its own. That is the whole reason a table exists here
  rather than an `or` of environment lookups inside the core.

  **What a row IS.** A harness qualifies when it can answer one question
  before slopp's first write — *which conversation is this?* — with an id
  that is:

  - PER-CONVERSATION, so two agents on one store get separate threads
    instead of trampling one;
  - STABLE ACROSS PROCESS EXIT, so resuming resumes the thread rather than
    stranding its un-landed work under a name nobody will use again;
  - AVAILABLE AT PROCESS START, because an identity that arrives later has
    already let the session load its store and image from the wrong line, and
    fixing that costs a reload and a rebuilt image rather than a lookup.

  A harness that cannot supply all three gets no row and falls through to a
  generated id. That is the correct outcome rather than a shortfall: an
  unnamed conversation is better than one wearing somebody else's name.

  `:session-env` is an environment variable because that is what survives the
  process boundary slopp sits behind. The harness spawns the server, so
  whatever it exports is already present before the first tool call — which
  is the only way to meet the third requirement above."
  [{:harness :claude-code
    :session-env "CLAUDE_CODE_SESSION_ID"
    :doc "Claude Code, over stdio MCP. The id names the TRANSCRIPT rather than the process: measured across one transcript spanning five CLI versions, which cannot happen without restarts, so it survives exit and resume — and a new conversation arrives with a new server process rather than mutating a running one. UNDOCUMENTED as of CLI 2.1.247, so treat its absence as ordinary: slopp generates an id and nothing breaks"}])

(defn ^:export conversation-id
  "The conversation this process is serving, from whichever known harness
  named it, or nil when none did.

  `getenv` is passed IN rather than read here so this stays a pure fold over
  the catalog. Process environment is state a test cannot set, and a
  precedence rule nobody can test is one that drifts — which is the whole
  failure mode this table exists to prevent.

  A BLANK value is not an identity. An empty variable would otherwise become
  an agent named \"\", shared by every session that happened to see one, which
  is the merge-episodes failure wearing a different hat.

  First hit wins, in catalog order. That only matters in the case that should
  not happen: two harnesses claiming one process."
  [getenv]
  (->> harness-catalog
       (keep #(not-empty (getenv (:session-env %))))
       first))
