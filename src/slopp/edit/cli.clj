(ns slopp.edit.cli
  "The `cli` capability's WRITE gates — what a store may not land once it has
  opted into a command-line shell.

  Three refusals, and each is a boundary rather than a style rule: a command
  must declare what argv may contain, one command name has one owner, and a
  command answers by RETURNING rather than by printing.

  **Being in this namespace is what arms them.** `edit.gates/gate-capability`
  derives a gate's owning capability from the last segment of the namespace
  defining it, so these are inert until `cli.enabled` with nothing written here
  to say so. That is deliberate: the http gates carried nine hand-written
  `(when (web-enabled? candidate) …)` wrappers, which is a rule every gate
  author had to know and nothing reminded them of. Moving a gate out of here is
  the only way to change who owns it.

  A gate is a pure question about the FORM; whether this store asked the
  question is dispatch's answer. So calling one directly on a store that never
  enabled `cli` correctly still returns its teaching.

  Neighbours: `slopp.cli` is the shipped runtime these gates protect;
  `slopp.rules.cli` holds the done-grain advisories, which ask rather than
  refuse."
  (:require [slopp.store :as store]
            [rewrite-clj.node :as n]))

(defn ^:export command-rows
  "Every `:cli/command` form in `store`: `{:ns :name :form-id :meta}` rows.

  THE single traversal. The collision gate compares rows against each other,
  the schema gate reads one row's `:meta`, and the surface report renders them
  — all from here, so no two of them can disagree about what counts as a
  command. Same shape and same reason as `slopp.edit.http/web-endpoint-rows`."
  [store]
  (vec (for [nsx (keys (:namespaces store))
             e   (store/forms store nsx)
             :let [m (store/form-name-meta e)]
             :when (:cli/command m)]
         {:ns nsx :name (:name e) :form-id (:id e) :meta m})))

(defn ^:export ^{:rule/applies-to :production} cli-args-schema
  "The argument-contract gate: a `:cli/command` must declare `:cli/args`.

  argv is UNTRUSTED INPUT. A command with no declared contract has moved the
  boundary into its own body, where nothing checks it, every command
  re-implements it slightly differently, and the first wrong value reaches
  business logic as a string.

  The empty schema `[:catn]` discharges it — a command that genuinely takes no
  arguments has to be able to SAY so, and refusing that would push authors to
  declare a fake argument to satisfy a gate. Absence and \"takes nothing\" are
  different statements, which is the same distinction the four-state load model
  makes one layer up.

  Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [m (store/form-name-meta e)]
      (when (and (:cli/command m) (not (contains? m :cli/args)))
        (str ns-sym "/" form-name " declares the command "
             (pr-str (:cli/command m)) " but no :cli/args — argv is untrusted"
             " input, so what this command accepts is declared, not parsed in"
             " the body. Add a malli schema to the name metadata:"
             " :cli/args [:catn [:name :string]], or [:catn] if it genuinely"
             " takes none. Options go in :cli/opts [:map …].")))))

(defn ^:export ^{:rule/applies-to :production} cli-command-collision
  "The name-uniqueness gate: a `:cli/command` whose name another FORM already
  claims is refused at the write.

  `commands-in` builds a map keyed by name, so a duplicate does not error at
  startup — it SILENTLY wins or loses depending on which namespace loaded
  first, and the losing command is simply never reachable. That is the
  route-collision failure with a worse ending, because an unreachable command
  looks exactly like one nobody has tried yet.

  The same form re-landing is not a collision (the replace case). Returns a
  teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [m (store/form-name-meta e)]
      (when-let [cmd (:cli/command m)]
        (when-let [other (some #(when (and (not= (:form-id %) (:id e))
                                           (= (str cmd) (str (:cli/command (:meta %)))))
                                  %)
                               (command-rows candidate))]
          (str ns-sym "/" form-name " declares the command " (pr-str cmd)
               " but " (:ns other) "/" (:name other) " already does — one name"
               " has one owner. commands-in keys by name, so a duplicate does"
               " not fail at startup: whichever namespace loads first wins and"
               " the other command is simply unreachable, which looks exactly"
               " like one nobody has tried. Rename one, or extend the existing"
               " command (query_surface lists every claim, under :cli)."))))))

(defn ^:export ^{:rule/applies-to :production} cli-direct-stdio
  "The port gate: a command body may not print, read a line, or exit.

  A command's answer is what it RETURNS — slopp renders it and sets the exit
  code. A `println` in the body is a second output channel nobody renders,
  interleaved with the first by accident, and invisible to a test asserting on
  the return value. `System/exit` is worse: it ends the process from inside
  business logic, so nothing downstream — rendering, a driver, a test — ever
  runs.

  **The escape is the injected stream, not a dial.** `(.write (:cli/out ctx) …)`
  is fine and is what the context is FOR: streaming progress is the one thing a
  return value cannot express. So this gate does not refuse writing to a
  stream, it refuses reaching for the AMBIENT one.

  **Scoped to command bodies, and that scope is load-bearing.** Printing is
  already classified elsewhere — `slopp.index.derive/console-leaves` blocks it
  in `:pure`, allows it in `:internal`, and deliberately does not demand a `!`
  name. This is a different axis: not \"is printing an effect\" but \"does this
  command have two ways of answering\". A non-command in the same namespace
  prints freely.

  Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (when (:cli/command (store/form-name-meta e))
      (let [denied {'println "return the value instead — slopp renders it"
                    'print   "return the value instead — slopp renders it"
                    'prn     "return the value instead — slopp renders it"
                    'printf  "return the value instead — slopp renders it"
                    'pr      "return the value instead — slopp renders it"
                    'read-line "read from (:cli/in ctx), the injected stream"
                    'System/exit "return {:cli/exit n} — the launcher owns the exit, so a driver and a test can see the code instead of dying with the process"}
            sx  (try (n/sexpr (:node e)) (catch Exception _ nil))
            hit (first (for [v (tree-seq coll? seq sx)
                             :when (and (seq? v) (symbol? (first v)))
                             :let [f (first v)]
                             :when (denied f)]
                         f))]
        (when hit
          (str ns-sym "/" form-name " is a command and calls " hit
               " — a command answers by RETURNING; slopp renders the value and"
               " sets the exit code. A second output channel is interleaved"
               " with the first by accident and is invisible to a test"
               " asserting on the return. Instead: " (denied hit)
               ". Writing to the INJECTED stream ((.write (:cli/out ctx) …)) is"
               " fine — that is what it is for when work streams."))))))
