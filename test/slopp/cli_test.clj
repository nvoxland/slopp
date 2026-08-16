(ns slopp.cli-test
  "Whole invocations, driven through the FAKE.

  Every test here runs argv end to end — resolve, parse, call, render, exit —
  and none of them starts a process, opens a stream or touches a temp
  directory. That is the capability's claim, asserted rather than described: if
  these ever need a process to run, an app's tests will need one too, and the
  reason to opt into `cli` has gone.

  The fake is the same shape `context` returns and goes through the same `run`,
  so what is exercised here is the code path a real invocation takes. What that
  cannot prove is that the fake's streams behave like real ones — `cli-contract`
  is the suite that runs both, for the same reason `requester-contract` exists
  for the HTTP port.

  Neighbours: `slopp.cli.spec-test` covers what a command line MEANS, with no
  invocation at all."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [slopp.cli :as cli]))

(deftest a-whole-invocation-runs-without-a-process
  ;; The property the capability exists to give an app: argv in, exit code and
  ;; captured output out, with no process, no stream and no temp directory.
  ;; Everything below is an ordinary in-image assertion, which is only possible
  ;; because a command RETURNS DATA and slopp does the rendering.
  ;;
  ;; **One context per invocation**, and that is not tidiness. A context's
  ;; writers ACCUMULATE, exactly as a real process's stdout does — one context
  ;; is one process, not one command. Sharing one across runs is what made the
  ;; refusal case below see the output of the three invocations before it, and
  ;; a fake that reset itself between runs would be a fake that lies about the
  ;; thing it stands in for.
  (let [commands
        {"add"  {:cli/command "add"
                 :cli/doc  "Add a task"
                 :cli/args [:catn [:text :string]]
                 :cli/opts [:map [:priority {:optional true :default 1}
                                  [:int {:min 1 :max 5}]]]
                 :cli/handler (fn [_ctx args]
                                {:added (:text args) :priority (:priority args)})}
         "fail" {:cli/command "fail"
                 :cli/doc  "Always refuses"
                 :cli/args [:catn]
                 :cli/handler (fn [_ _] {:cli/exit 3 :reason "nope"})}}
        run  (fn [& argv]
               (cli/run (cli/fake-context {:cli/name "todo" :cli/commands commands})
                        (vec argv)))]
    (testing "a command is resolved by name and handed PARSED arguments"
      (let [r (run "add" "buy milk" "--priority" "4")]
        (is (= 0 (:cli/exit r)) (pr-str r))
        (is (= {:added "buy milk" :priority 4} (:cli/value r))
            (str "the handler's return travels as DATA — that is what makes"
                 " this an = rather than a parse of captured text: " (pr-str r)))))
    (testing "and the framework rendered it to the captured stream"
      (is (re-find #"buy milk" (:cli/out (run "add" "buy milk")))))
    (testing "a handler may set its own exit code by returning one"
      (is (= 3 (:cli/exit (run "fail")))))
    (testing "a REFUSED command line never reaches the handler and exits non-zero"
      (let [r (run "add" "x" "--priority" "99")]
        (is (not= 0 (:cli/exit r)) (pr-str r))
        (is (re-find #"priority" (:cli/err r)) (pr-str r))
        (is (str/blank? (:cli/out r))
            (str "a refusal writes to err and leaves out CLEAN, so a caller"
                 " piping stdout gets nothing rather than half an answer: "
                 (pr-str (:cli/out r))))))
    (testing "an unknown command names the ones that exist"
      (let [r (run "addd" "x")]
        (is (not= 0 (:cli/exit r)))
        (is (re-find #"addd" (:cli/err r)) (pr-str r))
        (is (re-find #"add" (:cli/err r)) (pr-str r))))
    (testing "no command at all lists what this program can do"
      ;; a bare invocation is a question, and answering it with a usage error
      ;; alone makes the reader run a second command to learn anything
      (let [r (run)]
        (is (re-find #"add" (:cli/out r)) (pr-str r))
        (is (re-find #"Add a task" (:cli/out r)) "with each command's own doc")
        (is (re-find #"fail" (:cli/out r)))))
    (testing "--help on a command prints ITS usage and exits clean"
      ;; asking for help is not an error; exiting non-zero here breaks `cmd
      ;; --help` in any script that checks status
      (let [r (run "add" "--help")]
        (is (= 0 (:cli/exit r)) (pr-str r))
        (is (re-find #"--priority" (:cli/out r)) (pr-str r))))
    (testing "a command reads stdin through the context, never through *in*"
      (let [echo {"echo" {:cli/command "echo" :cli/doc "Echo stdin."
                          :cli/args [:catn]
                          :cli/handler (fn [ctx _] {:said (slurp (:cli/in ctx))})}}
            r    (cli/run (cli/fake-context {:cli/name "todo" :cli/commands echo
                                             :cli/stdin "from a pipe"})
                          ["echo"])]
        (is (= {:said "from a pipe"} (:cli/value r)) (pr-str r))))))

(deftest the-command-vocabulary-is-derived-from-markers
  ;; Adding a command is writing ONE defn. There is no list to add it to, which
  ;; is the same choice `slopp.web.routes/performers-from-namespaces` makes for
  ;; effect kinds and for the same reason: a registry beside the definitions is
  ;; a second place to keep in step, and this codebase's most frequent bug is a
  ;; registry that drifted from its consumer.
  (let [found (cli/commands-in ['slopp.cli-test.fixture])]
    (testing "a marked defn is found under its declared name"
      (is (contains? found "greet") (pr-str (keys found))))
    (testing "and carries its whole declaration, so nothing re-reads the var"
      (let [c (get found "greet")]
        (is (= "greet" (:cli/command c)))
        (is (string? (:cli/doc c)))
        (is (some? (:cli/args c)))
        (is (fn? (:cli/handler c)))))
    (testing "an unmarked public fn in the same namespace is NOT a command"
      ;; the marker is the declaration; being public is not
      (is (not (contains? found "helper")) (pr-str (keys found)))
      (is (= 1 (count found)) (pr-str (keys found))))
    (testing "a namespace that declares nothing contributes nothing, rather than throwing"
      ;; an app naming a namespace with no commands yet is ordinary, not an error
      (is (= {} (cli/commands-in ['slopp.cli-test]))))
    (testing "and the found commands run"
      ;; the join between discovery and dispatch, which is where a marker-scan
      ;; usually breaks: found-but-uncallable looks identical to found
      (let [r (cli/run (cli/fake-context {:cli/name "t" :cli/commands found})
                       ["greet" "ada"])]
        (is (= 0 (:cli/exit r)) (pr-str r))
        (is (= {:greeting "hello ada"} (:cli/value r)) (pr-str r))))))
