(ns slopp.cli
  "The command-line shell slopp gives an app that opts into `cli` — the ONE
  namespace an app requires.

  An app without this capability gets argv as a raw vector, `*out*` as ambient
  state, and `System/exit` as its own problem. With it, a command is a `defn`
  carrying its whole contract in name metadata, and slopp does the rest:
  resolves the name, validates the arguments against their declared schemas,
  injects the streams, generates the usage text, and turns the returned status
  into a process exit.

  **What slopp does NOT do is format the answer.** A command writes to
  `:cli/out` and returns an exit status — output is the app's, all of it. An
  earlier design had a command return data for slopp to render, which read
  well for the one command whose answer is a table and had no shape at all for
  the ordinary one: a summary line, then rows, then a warning. It also made a
  test an `=` on a map no user of the program ever sees, which is the defect
  `slopp.rest/call` exists to remove for HTTP. **For a command line, stdout IS
  the wire**, and there is one channel to it.

  What that costs — and it is worth stating, because it was the argument for
  the old design — is that a test asserts on text rather than on data. That is
  the right trade and not merely an acceptable one: the text is what a user
  gets and what a script pipes, and it stays assertable with no process because
  `fake-context` captures it.

  **It mirrors `slopp.web` deliberately.** `context` builds an invocation from
  declared namespaces the way `slopp.web/context` builds a request pipeline;
  `run` is `handle!`; `commands-in` is `performers-from-namespaces`. An author
  who has met one should recognise the other, and the shapes that are the same
  should look the same.

  Neighbour: `slopp.cli.spec` decides what a command line MEANS and this
  performs it. There is no dev-side driver beside them and none is needed —
  `fake-context` plus `run` already runs a command headlessly, which is the
  thing `slopp.webdev.live` has to exist to do for a server."
  (:require [slopp.cli.spec :as spec]))

(defn ^:export commands-in
  "Every command declared in `ns-syms`, as `{name command-map}`.

  The vocabulary is DERIVED from `:cli/command` markers on vars, never
  registered — the same choice `slopp.web.routes/performers-from-namespaces`
  makes for effect kinds, down to a namespace that is not loaded contributing
  nothing rather than throwing. Adding a command is writing one `defn`; there
  is no list to also remember, which removes this codebase's most frequent bug
  class by construction rather than by discipline.

  The whole declaration travels WITH the handler, so nothing downstream re-reads
  the var: `run`, `help-text` and the surface report all read one map. A
  consumer that went back to the var would be a second reader of the same
  metadata, free to disagree about what it found."
  [ns-syms]
  (into {}
        (for [ns-sym ns-syms
              :let   [nsx (find-ns (symbol (str ns-sym)))]
              :when  nsx
              v      (vals (ns-publics nsx))
              :let   [m (meta v)]
              :when  (:cli/command m)]
          [(str (:cli/command m))
           (assoc (select-keys m [:cli/command :cli/doc :cli/args :cli/opts])
                  :cli/handler @v)])))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map
                          [:cli/name {:optional true} [:maybe :string]]
                          [:cli/commands {:optional true} [:maybe [:map-of :string :any]]]
                          [:cli/in {:optional true} :any]
                          [:cli/out {:optional true} :any]
                          [:cli/err {:optional true} :any]]]
                   [:map
                    [:cli/name :string]
                    [:cli/commands [:map-of :string :any]]
                    [:cli/in :any] [:cli/out :any] [:cli/err :any]]]}
  context
  "The invocation context an app's commands are run against — the ADAPTER half
  of the cli port.

  `{:cli/name :cli/commands :cli/in :cli/out :cli/err}`. `:cli/in` is a reader,
  `:cli/out` and `:cli/err` are writers, and by default they are the real
  process streams.

  **These are how a command answers.** It writes to `:cli/out`, reads from
  `:cli/in`, and returns only its exit status. Injecting them rather than
  letting a command reach for `*out*` is the whole of what makes an invocation
  testable without a process: `fake-context` supplies string writers and the
  same `run` produces the same characters. `cli-direct-stdio` refuses the
  ambient stream for that reason and no other.

  Commands arrive as `{name command-map}` rather than being discovered here, so
  this stays a value with no scanning in it: `commands-in` does the discovery
  and this does the wiring. Two functions because the discovery needs loaded
  namespaces and the wiring does not, which is what lets a test build a context
  out of literal maps — and a test that had to load a namespace to check an
  exit code would be paying process cost for a decision that is pure."
  [{:cli/keys [name commands in out err]}]
  {:cli/name     (or name "app")
   :cli/commands (or commands {})
   :cli/in       (or in *in*)
   :cli/out      (or out *out*)
   :cli/err      (or err *err*)})

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map
                          [:cli/name {:optional true} [:maybe :string]]
                          [:cli/commands {:optional true} [:maybe [:map-of :string :any]]]
                          [:cli/stdin {:optional true} [:maybe :string]]]]
                   [:map
                    [:cli/name :string]
                    [:cli/commands [:map-of :string :any]]
                    [:cli/in :any] [:cli/out :any] [:cli/err :any]]]}
  fake-context
  "A context whose streams are strings — the FAKE half of the cli port.

  `:cli/stdin` is the text a command reading input will see; the two writers
  capture, and `run` returns what they captured on `:cli/out` / `:cli/err`.
  Since a command WRITES its answer, that captured text is the whole of what an
  invocation produced — a test asserts on the characters a user would see.

  **It is the same shape `context` returns and goes through the same `run`.**
  That is the whole property: a test drives the code path a process drives, and
  the only difference is where the characters come from. A fake that took a
  different door would be evidence about the fake.

  **One context is one PROCESS, not one invocation.** The writers accumulate,
  exactly as a real stdout does, so `run`ning twice against the same context
  gives the second call the first call's output too. Build a fresh one per
  invocation. Resetting between runs would be easier to use and would make this
  a fake that lies about the thing it stands in for — the same reason
  `slopp.web.screen` refuses to keep a session between scripts.

  Exactness is why the cli port is a better fake than the HTTP one — a stream
  of characters can be reproduced completely, where a network can only be
  approximated. `cli-contract` runs the same suite against this and against
  real streams anyway, because \"the fake is obviously exact\" is what everyone
  believes about their fake — and it catches the one thing a StringWriter
  cannot express, which is a write that was never FLUSHED."
  [{:cli/keys [name commands stdin]}]
  (context {:cli/name     name
            :cli/commands commands
            :cli/in       (java.io.PushbackReader. (java.io.StringReader. (or stdin "")))
            :cli/out      (java.io.StringWriter.)
            :cli/err      (java.io.StringWriter.)}))

(defn ^:export run
  "Perform one whole invocation of `argv` against `ctx` — the cli port's
  `handle!`.

  Returns `{:cli/exit :cli/out :cli/err}` rather than exiting, so a test and a
  process see the same answer and only the process acts on it. The generated
  launcher is the one place `System/exit` is called.

  **Order is the guarantee**, exactly as it is in `slopp.web.dispatch/handle!`:

  1. no command named → LIST what this program can do. A bare invocation is a
     question, and a usage error alone makes the reader run a second command to
     learn anything.
  2. resolve the command; an unknown name refuses and NAMES the ones that exist.
  3. `--help` → that command's generated usage, exit 0. Asking for help is not
     an error, and exiting non-zero here breaks `cmd --help` in any script that
     checks status.
  4. parse argv against the declared schemas. A refusal writes to err, leaves
     OUT CLEAN, and never reaches the handler — a caller piping stdout gets
     nothing rather than half an answer.
  5. call the handler with `[ctx args]`. **The handler writes its own answer to
     `:cli/out` / `:cli/err`; its RETURN is the exit status and nothing else.**

  An INTEGER return is the status; `nil` and every other value are 0. Slopp
  used to render a returned map instead, which read well for the one command
  whose answer is a table and had no shape at all for the ordinary one — a
  summary line, then rows, then a warning. Worse, it made a test an `=` on a
  map that no user of the program ever sees, which is the same defect
  `slopp.rest/call` exists to remove for HTTP: the pre-wire value is not what
  the far side receives. **For a command line, stdout IS the wire**, and there
  is now exactly one channel to it.

  The accepted cost is stated rather than designed around: a body whose last
  expression happens to be a number exits with it. Only an integer can be a
  status, so there is no way to tell a deliberate 3 from an incidental one, and
  guessing would be worse than the rule. End on `nil` when the value is
  incidental.

  Both streams are FLUSHED after the handler returns. A handler writes with
  `.write`, which buffers, and an unflushed real stdout loses the answer at
  exit — the fake would not have shown it, since a StringWriter has nothing to
  flush."
  [ctx argv]
  (let [{:cli/keys [commands out err]} ctx
        argv (vec argv)
        emit (fn [w s] (.write ^java.io.Writer w (str s "\n")) (.flush ^java.io.Writer w))
        done (fn [exit]
               (cond-> {:cli/exit exit}
                 (instance? java.io.StringWriter out) (assoc :cli/out (str out))
                 (instance? java.io.StringWriter err) (assoc :cli/err (str err))))
        cmd  (first argv)]
    (cond
      (nil? cmd)
      (do (emit out (spec/program-help ctx)) (done 0))

      (not (contains? commands cmd))
      (do (emit err (spec/unknown-command-error ctx cmd)) (done 2))

      :else
      (let [command (get commands cmd)
            parsed  (spec/parse command (subvec argv 1))]
        (cond
          (:help? parsed)
          (do (emit out (spec/help-text command)) (done 0))

          (:errors parsed)
          (do (doseq [e (:errors parsed)] (emit err e))
              (emit err (spec/help-text command))
              (done 2))

          :else
          (let [status ((:cli/handler command) ctx (:args parsed))]
            (.flush ^java.io.Writer out)
            (.flush ^java.io.Writer err)
            (done (if (integer? status) status 0))))))))
