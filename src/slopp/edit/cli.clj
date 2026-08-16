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
  "The port gate: a command body may not print to the AMBIENT stream, read a
  line from it, or exit.

  A command writes its answer to `(:cli/out ctx)` — the injected stream — and
  returns its exit status. `println` writes to `*out*` instead, which is a
  different stream that no test captures, no driver can redirect, and no fake
  can stand in for: the output simply escapes. `System/exit` is worse, ending
  the process from inside business logic so that nothing downstream — a driver,
  a test, the launcher's own exit — ever runs.

  **This gate is about WHICH stream, not about whether to write.** It was once
  about whether to write at all: slopp rendered a returned value and a
  `println` was a second output channel interleaved with the first by accident.
  That design is gone — output is the command's job now — and the gate survives
  it unchanged in behaviour and changed in reason. Writing is expected; reaching
  past the injected stream to the ambient one is what makes an app untestable.

  **Scoped to command bodies, and that scope is load-bearing.** Printing is
  already classified elsewhere — `slopp.index.derive/console-leaves` blocks it
  in `:pure`, allows it in `:internal`, and deliberately does not demand a `!`
  name. This is a different axis: not \"is printing an effect\" but \"does this
  command write where its caller can see\". A non-command in the same namespace
  prints freely.

  Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (when (:cli/command (store/form-name-meta e))
      (let [denied {'println "(.write (:cli/out ctx) …) — the injected stream"
                    'print   "(.write (:cli/out ctx) …) — the injected stream"
                    'prn     "(.write (:cli/out ctx) …) — the injected stream"
                    'printf  "(.write (:cli/out ctx) …) — the injected stream"
                    'pr      "(.write (:cli/out ctx) …) — the injected stream"
                    'read-line "read from (:cli/in ctx), the injected stream"
                    'System/exit "return the status instead — an integer return IS the exit code, and the launcher owns the exit, so a driver and a test can see it rather than dying with the process"}
            sx  (try (n/sexpr (:node e)) (catch Exception _ nil))
            hit (first (for [v (tree-seq coll? seq sx)
                             :when (and (seq? v) (symbol? (first v)))
                             :let [f (first v)]
                             :when (denied f)]
                         f))]
        (when hit
          (str ns-sym "/" form-name " is a command and calls " hit
               " — a command writes to the stream its CONTEXT carries, not to"
               " the ambient one. `*out*` is a different stream: no test"
               " captures it, no driver redirects it, and the fake cannot stand"
               " in for it, so the output escapes. Instead: " (denied hit)
               "."))))))
