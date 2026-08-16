(ns slopp.rules.cli
  "The `cli` capability's STORE-SIDE reading: what a store declares it can be
  told to do, derived from the same markers the write gates enforce.

  Its one job today is `commands-report`, the `:cli` section of
  `query_surface`. Reading and refusing are deliberately split across two
  namespaces: `slopp.edit.cli` refuses at the WRITE, where an author can still
  fix it, and this describes what stands — the same split `slopp.edit.http` and
  `slopp.rules.http` already make.

  **Named for the capability, and that is a rule rather than a habit** (R6). A
  check or a report that reads one app type's vocabulary from a GENERIC
  namespace has no app type to disagree with, so the naming guard cannot grade
  it — which is how five web-only checks once sat in `slopp.rules` unnoticed.
  Moving a report out of here is the only way to change who owns it.

  Neighbours: `slopp.cli` is the shipped runtime, `slopp.cli.spec` decides what
  a command line means, and this reads what the store DECLARES without running
  anything."
  (:require [slopp.edit.cli :as edit.cli]
            [slopp.cli.spec :as spec]
            [slopp.project.capabilities :as capabilities]))

(defn ^:export commands-report
  "The `cli` section of `query_surface`: one row per declared command.

  Empty until `cli.enabled` — the reading side of the same inertness the gates
  have, and for the same reason: a project that is not a command-line app must
  not be DESCRIBED as one.

  Rows are self-describing. Every one carries `:kind :command`, so a renderer
  that knows nothing about this capability can still draw it beside an
  endpoint — which is what stops each new capability needing a page written for
  it before a human can see anything.

  **The shape is NAMES, not schemas.** A malli schema pretty-prints to several
  lines, and this surface is already the one at risk of crossing the response
  gate: one tool answers for every capability, so its payload grows with the
  app rather than with the question. The names answer what the surface is FOR —
  what can I pass — and `query_slice` on the handler answers what it accepts
  exactly.

  Read through `slopp.cli.spec/schema-entries`, the same total accessor
  `help-text` uses, so the report and the usage text cannot disagree about what
  a declaration says.

  Sorted by name, because declaration order is an accident of which namespace
  loaded first."
  [store]
  (if-not (capabilities/enabled? store "cli")
    []
    (vec (for [{:keys [ns name meta]} (sort-by #(str (:cli/command (:meta %)))
                                               (edit.cli/command-rows store))]
           (cond-> {:kind    :command
                    :command (str (:cli/command meta))
                    :handler (symbol (str ns) (str name))}
             (:cli/doc meta)  (assoc :doc (:cli/doc meta))
             (:cli/args meta) (assoc :args (mapv first (spec/schema-entries (:cli/args meta))))
             (:cli/opts meta) (assoc :opts (mapv first (spec/schema-entries (:cli/opts meta)))))))))
