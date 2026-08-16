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
            [slopp.cli.spec :as spec] [clojure.string :as str]))

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

(deftest a-list-of-ROWS-renders-as-a-table
  ;; Dogfooding the cli capability produced this: `talk owed …` printed 36 KB of
  ;; raw EDN — every field of every row, one line each — which is correct,
  ;; machine-readable, and not something a person can read.
  ;;
  ;; The decision that a command returns DATA is right and is what makes its
  ;; tests `=` on a map. The gap was never the decision; it was that ONE shape —
  ;; a sequence of maps, which is what every `list` command returns — had no
  ;; rendering beyond pr-str per row.
  ;;
  ;; This stays deliberately small. `render`'s docstring drew a line at "not a
  ;; formatting library", and that line is right: no colour, no wrapping, no
  ;; configuration. A command needing more returns the string it wants.
  (let [rows [{:file "a.md" :from "ann" :subject "the first one"}
              {:file "bb.md" :from "bo" :subject "another"}]
        out  (spec/render rows)
        [header & body] (str/split-lines out)]
    (testing "the keys become a header, once, rather than repeating per row"
      (is (re-find #"file" header) out)
      (is (re-find #"from" header) out)
      (is (re-find #"subject" header) out)
      (is (= 2 (count body)) out))

    (testing "columns line up, so a reader can scan ONE field down the page"
      ;; the whole difference between a table and a wall: `from` starts at the
      ;; same offset on every line
      (is (= (str/index-of header "from") (str/index-of (first body) "ann"))
          (str "column offsets must agree:\n" out)))

    (testing "a value keeps its own text rather than being pr-str'd into quotes"
      (is (re-find #"the first one" out) out)
      (is (not (re-find #"\"ann\"" out))
          (str "a string cell reads as itself — quotes are noise a reader looks "
               "past on every row: " out))))

  (testing "a long cell is TRUNCATED, because a table wider than the terminal is a wall again"
    (let [out (spec/render [{:k (apply str (repeat 200 "x"))}])]
      (is (< (count out) 200) out)
      (is (re-find #"…" out) out)))

  (testing "a sequence of scalars is untouched — it was never the problem"
    (is (= "a\nb" (spec/render ["a" "b"]))))

  (testing "and a single map still renders as key/value lines"
    ;; one row is not a table; a header above a single line of values is worse
    ;; than the pair, because the reader looks twice to read once
    (is (= "a  1\nb  2" (spec/render {:a 1 :b 2})))))

(deftest a-MISSING-positional-is-taught-the-way-a-surplus-already-is
  ;; Dogfooding produced these two sentences from ONE program, one write apart:
  ;;
  ;;   me end of input
  ;;   --nope is not an option of this command (options: --from, --to)
  ;;
  ;; The second names what is wrong, what the command accepts, and therefore
  ;; what to type next. The first is malli's internal vocabulary leaking to a
  ;; user who never wrote a schema — "end of input" describes the PARSER's
  ;; situation, not the caller's.
  ;;
  ;; The surplus case was already taught, in `extra`, and for the same reason.
  ;; A shortfall is the identical event from the other side and had no branch.
  (let [spec {:cli/args [:catn [:from :string] [:text :string]]}]
    (testing "the shortfall is named as a shortfall, in the command's own words"
      (let [errs (:errors (spec/parse spec ["ann"]))]
        (is (= 1 (count errs))
            (str "one sentence, not one per unfilled slot — a caller who typed "
                 "too few arguments made ONE mistake: " (pr-str errs)))
        (is (re-find #"text" (first errs)) errs)
        (is (re-find #"(?i)missing" (first errs)) errs)
        (is (not (re-find #"end of input" (first errs)))
            (str "malli's vocabulary must not reach a caller who never wrote a "
                 "schema: " (pr-str errs)))))

    (testing "and it says what the command DOES take, which is the next thing typed"
      (let [errs (:errors (spec/parse spec []))]
        (is (re-find #"from" (first errs)) errs)
        (is (re-find #"text" (first errs)) errs)))

    (testing "a surplus still reads the way it did — this changes one side only"
      (is (re-find #"got 3" (first (:errors (spec/parse spec ["a" "b" "c"]))))))

    (testing "a WRONG value is still reported per-argument, because it is per-argument"
      ;; the shortfall branch must not swallow real per-slot problems: two
      ;; positionals present and one of them bad is a different event
      (let [errs (:errors (spec/parse {:cli/args [:catn [:from :string] [:n :int]]}
                                      ["ann" "banana"]))]
        (is (some #(re-find #"^n " %) errs) errs)))))
