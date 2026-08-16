(ns slopp.rules.cli-test
  "The cli section of the surface — what an agent is told when it asks what
  this codebase can be made to do.

  Two properties carry more weight than the contents. First, the section is
  EMPTY until `cli.enabled`: a project that is not a command-line app must not
  be described as one, which is the reading side of the inertness the gates
  have. Second, every row says its own `:kind` and carries its doc rather than
  a pointer to it — that is what lets one renderer draw a capability nobody
  wrote a page for, and it is the difference between adding a capability and
  adding a capability plus a UI change.

  The shape is NAMES rather than schemas, and the test says so on purpose: this
  is the one payload that grows with the APP rather than with the question, so
  what it leaves out is a decision rather than an omission."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.rules.cli :as rules.cli]))

(deftest the-cli-section-reports-what-this-app-can-be-told-to-do
  (let [src (str "(ns app.cmds)\n\n"
                 "(defn ^{:cli/command \"add\" :cli/doc \"Add a task.\"\n"
                 "        :cli/args [:catn [:text :string]]\n"
                 "        :cli/opts [:map [:priority {:optional true :default 1} :int]]}\n"
                 "  add \"A.\" [ctx args] args)\n\n"
                 "(defn ^{:cli/command \"ls\" :cli/doc \"List them.\" :cli/args [:catn]}\n"
                 "  ls \"L.\" [ctx args] args)\n")
        off (store/ingest (store/empty-store) 'app.cmds src)
        on  (first (store/record-config-put off "capabilities" :manifest
                                            "cli.enabled" "true"))]
    (testing "OFF: a store that never opted in has no cli surface"
      ;; the reading side of the same inertness the gates have — a project that
      ;; is not a cli app must not be described as one
      (is (empty? (rules.cli/commands-report off))))
    (testing "ON: every declared command, in name order"
      ;; declaration order is an accident of which namespace loaded first
      (let [rows (rules.cli/commands-report on)]
        (is (= ["add" "ls"] (mapv :command rows)) (pr-str rows))))
    (testing "each row says what KIND it is, so a generic renderer can draw it"
      ;; the surface mixes kinds across capabilities, and a reader that had to
      ;; know which section it came from to interpret a row is a reader that
      ;; needs a page written per capability
      (is (every? #(= :command (:kind %)) (rules.cli/commands-report on))))
    (testing "a row carries its doc and its declared shape, not a pointer to them"
      (let [add (first (rules.cli/commands-report on))]
        (is (= "Add a task." (:doc add)))
        (is (= 'app.cmds/add (:handler add))
            "qualified, so a reader can go to the form without guessing the namespace")
        (is (= [:text] (:args add)) (pr-str add))
        (is (= [:priority] (:opts add)) (pr-str add))))
    (testing "the shape is NAMES rather than schemas"
      ;; a malli schema pretty-prints to several lines, and this surface is
      ;; already the one at risk of blowing the response gate. The names answer
      ;; "what can I pass"; query_slice answers "what exactly does it accept".
      (let [add (first (rules.cli/commands-report on))]
        (is (every? keyword? (:args add)) (pr-str add))))))
