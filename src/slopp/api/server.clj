(ns slopp.api.server
  "The ROUTE TABLE of a project's own read API — what a session serves about
  the store it holds, assembled by [[serving-opts]] and bound by whoever
  listens. Nothing here binds a port: the daemon (`slopp.daemon`) mounts
  each open project's table at `/api/projects/<slug>/<resource>` — the
  `/api/<resource>` declared here, with the mount replacing that prefix —
  through a declared read that delegates, so one configured port serves
  every project on the machine and the address story this namespace used
  to tell — derived from the store dir, then deliberately not configurable,
  then a hub's to remember — ended with the per-session listener it
  belonged to.

  What the table answers about is the SESSION it was assembled over.
  Warranty and observed examples ARE persisted (`session/persist-trace!` at
  every verified run, reloaded by `open!`), so a session opened fresh is not
  blank; it is BEHIND, seeing the last snapshot. The daemon answers from the
  project's reader — the landed branch — which is what a consumer of the
  project's API wants; an agent's un-landed work is its own to read through
  its thread."
  (:require [slopp.http :as slopp.http]
            [slopp.api.reads] [slopp.api.endpoints] [slopp.rest :as slopp.rest]))

(def ^:export served-namespaces
  "Every namespace this project's API serves — endpoints AND read performers.

  ONE list, exported. It once had two mounts — a per-session listener and
  an MCP-over-HTTP transport — and a literal repeated at both is how a
  namespace ends up served by nobody: the compiled client bundle 404'd on
  every page for two waves behind a 200 for the page itself, because one
  list got a new entry and the other did not.

  There is one mount again — the daemon, which assembles [[serving-opts]]
  per project and delegates into it — and that is the POINT rather than an
  excuse to inline it: one exported list is what makes a second mount a
  visible choice rather than a copied literal.

  Both halves of a request live here. `slopp.api.endpoints` declares the
  `/api/*` routes; `slopp.api.reads` declares the `:http/read` performers they resolve
  through. Reads are addressed by VOCABULARY rather than by var, so an
  endpoint names a KIND and the performer for it is found — but that also
  means a list carrying only one of the two namespaces answers 500 rather
  than 404, which is a much worse way to find out.

  It is a short list now and stays that way: a project serves JSON and the
  EDN contract, nothing else. The pages a human looks at are the daemon's
  own (`slopp.ui.*`), served beside its registry from the daemon's assembly
  and reading this API through the mount — never from a project's context."
  ['slopp.api.reads 'slopp.api.endpoints])

(defn ^:export serving-opts
  "Everything the reviewer API's opts say about the APPLICATION, with nothing
  about its address — which is exactly the half that was duplicated.

  A listener adds host and port; [[context]] adds nothing. Splitting it here
  is what makes the two impossible to disagree: the listener and the
  in-process context described the same app by hand until one of them
  stopped, and the one that stopped was the one no browser was pointed at.

  Exported for the daemon, which assembles one such context PER PROJECT
  over a reader session and delegates each project's requests into it —
  the same app, the same validation, mounted at `/api/projects/<slug>/`
  in place of the `/api/` declared here. The harness's telemetry sink is
  NOT here: it is the daemon's own endpoint, one per machine, routing each
  record to the project holding its thread."
  [session]
  {:http/namespaces served-namespaces
   ;; the reviewer API publishes typed contracts; anything serving them
   ;; unvalidated answers 200s nobody checked
   :http/wrap-context slopp.rest/validating
   :http/perform-ctx {:session session
                      :served-namespaces served-namespaces}})

(defn ^:export context
  "The reviewer API's dispatch context, assembled ONE way — over `session`.

  **This exists because there were seventeen.** Every test that drove these
  endpoints built its own `slopp.http/context` inline, and `serve!` built a
  third; none of them wrapped, so the whole typed surface was served and
  exercised with nothing honouring a single declared contract. One of those
  tests opens by claiming *\"the response is validated against the SAME schema
  var the generated client validates with\"*, which was false for as long as it
  had been written.

  `slopp.rest/validating` is what makes that claim true, and it is here rather
  than at each call site for the reason a consuming store measured: an app that
  can be stood up two ways will eventually be stood up both, and only one of
  them will validate. Theirs was — production wrapped, the test path did not,
  and a test that started a real server and asserted 200 passed while the
  served endpoint was answering 500.

  `slopp.http/context` now REFUSES a route that declares a contract with no
  validator, so this cannot silently drift back; what this adds is that there
  is one place to keep right."
  [session]
  (slopp.http/context (serving-opts session)))
