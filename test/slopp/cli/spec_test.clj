(ns slopp.cli.spec-test
  "The command-line boundary, asserted as data.

  Every test here is an ordinary in-image `=` — no process, no captured
  output, no temp directory — and that is the POINT rather than a convenience.
  argv is untrusted input, so the cases worth covering are the wrong ones: a
  value that fails its type, an option nobody declared, a missing positional,
  `--help` on a line that is otherwise broken. Those are the cases a shell
  makes expensive to test and are exactly where a hand-rolled parser goes
  wrong.

  If these ever need a stream to run, the split between `slopp.cli.spec` and
  `slopp.cli` has moved and the capability has lost the property it was built
  for."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.cli.spec :as spec]))

(deftest argv-parses-against-a-declared-spec
  ;; The whole reason a command declares its arguments as DATA rather than
  ;; destructuring a vector: argv is UNTRUSTED INPUT. A handler that pulls
  ;; `(first args)` and calls `Long/parseLong` on it has put the boundary
  ;; inside the business logic, where nothing checks it and every command
  ;; re-implements it slightly differently.
  ;;
  ;; So the parser answers with `{:args …}` or `{:errors […]}` and never both,
  ;; and a handler runs only in the first case.
  ;;
  ;; **Positionals are a `:catn` and options are a `:map`**, both ordinary
  ;; malli — the same language `rest` declares an endpoint's contract in, so a
  ;; shape can serve a command and an endpoint without being written twice.
  ;; `:catn` is the fit for argv specifically because it is ordered AND named,
  ;; which is the one thing a positional argument is.
  (let [add {:cli/args [:catn [:text :string]]
             :cli/opts [:map
                        [:priority {:optional true :default 1} [:int {:min 1 :max 5}]]
                        [:done? {:optional true :default false} :boolean]]}]
    (testing "a positional lands under its declared name, typed"
      (is (= {:text "buy milk" :priority 1 :done? false}
             (:args (spec/parse add ["buy milk"])))))
    (testing "options arrive in either spelling, and a flag needs no value"
      (is (= 5 (:priority (:args (spec/parse add ["x" "--priority" "5"])))))
      (is (= 5 (:priority (:args (spec/parse add ["x" "--priority=5"])))))
      (is (true? (:done? (:args (spec/parse add ["x" "--done?"]))))))
    (testing "a declared default fills in, and is NOT confused with the value being absent"
      ;; the four-state load model's lesson one layer over: a caller reading
      ;; :priority gets a number either way, and `(:priority args)` is never
      ;; the place where "unset" leaks out as nil.
      (is (= 1 (:priority (:args (spec/parse add ["x"])))))
      (is (contains? (:args (spec/parse add ["x"])) :priority)))
    (testing "a value that fails its type REFUSES, naming the option and what it takes"
      (let [{:keys [args errors]} (spec/parse add ["x" "--priority" "high"])]
        (is (nil? args) "no partial parse reaches a handler")
        (is (= 1 (count errors)) (pr-str errors))
        (is (re-find #"priority" (first errors)) (pr-str errors))))
    (testing "a value outside its declared RANGE refuses too — the schema is the boundary"
      (let [{:keys [args errors]} (spec/parse add ["x" "--priority" "99"])]
        (is (nil? args))
        (is (re-find #"priority" (first errors)) (pr-str errors))))
    (testing "an unknown option refuses rather than being ignored"
      ;; ignoring it is the nil-pun: the reader typed something meaning to
      ;; change behaviour and nothing changed, with no signal.
      (let [{:keys [errors]} (spec/parse add ["x" "--proirity" "5"])]
        (is (seq errors))
        (is (re-find #"proirity" (first errors)) (pr-str errors))
        (is (re-find #"priority" (first errors))
            (str "and the teaching names the option it was probably meant to"
                 " be — a refusal that only says \"unknown\" makes the reader"
                 " diff two strings by eye: " (pr-str errors)))))
    (testing "a missing required positional refuses"
      (let [{:keys [args errors]} (spec/parse add [])]
        (is (nil? args))
        (is (re-find #"text" (first errors)) (pr-str errors))))
    (testing "extra positionals refuse rather than being dropped"
      (let [{:keys [args errors]} (spec/parse add ["a" "b"])]
        (is (nil? args))
        (is (seq errors))))
    (testing "--help is answered, not parsed"
      ;; asking for help must work even when the rest of the line is wrong —
      ;; the reader asking what the arguments ARE is exactly the reader who
      ;; has them wrong.
      (is (:help? (spec/parse add ["--help"])))
      (is (:help? (spec/parse add ["--priority" "nonsense" "--help"])))
      (is (nil? (:errors (spec/parse add ["--help"])))))
    (testing "a command with no declared options still parses its positionals"
      ;; the degenerate spec has to work, or every command grows an empty :map
      (is (= {:text "x"} (:args (spec/parse {:cli/args [:catn [:text :string]]} ["x"])))))))

(deftest help-is-generated-from-the-schema-that-validates
  ;; Usage text and validation are the same facts read twice, so writing them
  ;; twice is the registry-and-consumer shape at its most visible: help that
  ;; teaches a command line the parser then refuses is worse than no help,
  ;; because the reader trusts it.
  ;;
  ;; So `help-text` derives from `:cli/args` / `:cli/opts` and nothing else. A
  ;; command cannot carry a hand-written usage string, which means it cannot
  ;; carry a stale one.
  (let [add {:cli/command "add"
             :cli/doc  "Add a task to the list"
             :cli/args [:catn [:text :string]]
             :cli/opts [:map
                        [:priority {:optional true :default 1
                                    :doc "urgency, 1-5"} [:int {:min 1 :max 5}]]
                        [:done? {:optional true :default false} :boolean]]}
        h   (spec/help-text add)]
    (testing "it names the command and says what it is for"
      (is (re-find #"add" h) h)
      (is (re-find #"Add a task to the list" h) h))
    (testing "every declared positional appears, in order"
      (is (re-find #"text" h) h))
    (testing "every declared option appears, with its default"
      (is (re-find #"--priority" h) h)
      (is (re-find #"--done\?" h) h)
      (is (re-find #"1" h) "the default is shown, so the reader knows what happens if they omit it"))
    (testing "an entry's :doc rides through — it is the only place a reason can live"
      (is (re-find #"urgency" h) h))
    (testing "a command with no options says so rather than showing an empty section"
      ;; an empty heading reads as a section that failed to render
      (let [bare (spec/help-text {:cli/command "ls" :cli/doc "List." :cli/args [:catn]})]
        (is (re-find #"ls" bare) bare)
        (is (not (re-find #"(?i)options" bare)) bare)))))
