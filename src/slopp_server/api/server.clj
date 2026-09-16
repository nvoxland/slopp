(ns slopp-server.api.server
  "The ROUTE TABLE of a project's own read API — what a session serves about
  the store it holds, assembled by [[serving-opts]] and bound by whoever
  listens. Nothing here binds a port: the daemon (`slopp-server.daemon`) mounts
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
            [slopp-server.api.reads] [slopp-server.api.endpoints] [slopp.rest :as slopp.rest]))

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
  own (`slopp-server.ui.*`), served beside its registry from the daemon's assembly
  and reading this API through the mount — never from a project's context."
  ['slopp-server.api.reads 'slopp-server.api.endpoints])

(defn ^:export context
  "The served API context over a SINGLE session, for tests and in-process
  tools. The daemon serves this SAME surface over MANY projects — resolving
  each request's reader from its `:slug` — so here the `:open-reader` the
  resolve phase consults answers `session` for every slug: one project, named
  anything. Drives the endpoints at `/api/projects/<any-slug>/<resource>`, the
  address they declare.

  `slopp.rest/validating` wraps it because these endpoints publish typed
  contracts and anything serving them unvalidated answers 200s nobody checked;
  `slopp.http/context` REFUSES a route declaring a contract with no validator,
  so this cannot drift back."
  [session]
  (slopp.http/context
   {:http/namespaces served-namespaces
    :http/wrap-context slopp.rest/validating
    :http/perform-ctx {:open-reader (constantly session)}}))
