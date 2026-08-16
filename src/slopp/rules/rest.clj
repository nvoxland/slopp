(ns slopp.rules.rest
  "The `rest` capability's STORE-SIDE reading: what a project declares its typed
  API to be, derived from the same markers the write gates enforce.

  Reading and refusing are split across two namespaces on purpose — the gates
  refuse at the WRITE, where an author can still fix it, and this describes what
  stands. The same split `slopp.edit.http`/`slopp.rules.http` and
  `slopp.edit.cli`/`slopp.rules.cli` already make.

  **Named for the capability, and that is a rule rather than a habit** (R6). A
  report that reads one app type's vocabulary from a GENERIC namespace has no
  app type to disagree with, so the naming guard cannot grade it — which is how
  five web-only checks once sat in `slopp.rules` unnoticed. Moving a report out
  of here is the only way to change who owns it.

  Note this does NOT ship: it is under `slopp.rules`, slopp's own tooling, not
  under the `slopp.rest` family a consumer is vendored. Neighbours: `slopp.rest`
  is the shipped runtime, `slopp.rest.contract` decides what a contract means,
  and this reads what the store DECLARES without running anything."
  (:require [slopp.edit.http :as edit.http]
            [slopp.project.capabilities :as capabilities]
            [slopp.cli.spec :as spec]))

(defn ^:export contracts-report
  "The `rest` section of `query_surface`: one row per endpoint that declares a
  typed contract.

  Empty until `rest.enabled` — the reading side of the same inertness the gates
  have, and for the same reason: a project that publishes no typed API must not
  be DESCRIBED as having one.

  **The shape is NAMES, not schemas**, the same choice `rules.cli/commands-report`
  makes and for a sharper reason here. A malli schema pretty-prints to several
  lines, and this surface is already the one whose payload grows with the APP
  rather than with the question — `query_rules` is what that looks like when it
  goes wrong, returning 17 of its 43 rules over the response gate with the
  truncation announced outside the payload. The names answer what an endpoint
  is FOR (what may I send, what comes back); `query_slice` on the handler
  answers what it accepts exactly.

  Read through `slopp.cli.spec/schema-entries`, the same TOTAL accessor the cli
  surface uses, so a malformed declaration degrades to nil in both reports
  rather than throwing in one of them. A bad schema is caught at the WRITE,
  where the author can fix it.

  `:published` is `:web/client false` inverted, and it is a field rather than an
  omission because a reader asking what consumers can CALL needs the exclusion
  visible. An HTML document is a `:web/path` form like any other and a generated
  fetch wrapper over it would be nonsense — but \"excluded on purpose\" and
  \"forgot to declare a schema\" must not look the same.

  Rows carry `:kind :contract`, so a renderer that knows nothing about this
  capability can draw one beside a command or an endpoint."
  [store]
  (if-not (capabilities/enabled? store "rest")
    []
    (vec (for [{:keys [ns name meta]} (sort-by #(str (:web/path (:meta %)))
                                               (edit.http/web-endpoint-rows store))
               :when (or (:web/request meta) (:web/response meta)
                         (false? (:web/client meta)))]
           (cond-> {:kind      :contract
                    :method    (:web/method meta)
                    :path      (str (:web/path meta))
                    :handler   (symbol (str ns) (str name))
                    :published (not (false? (:web/client meta)))}
             (:web/request meta)
             (assoc :request (mapv first (spec/schema-entries (:web/request meta))))
             (:web/response meta)
             (assoc :response (mapv first (spec/schema-entries (:web/response meta)))))))))
