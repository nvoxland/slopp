(ns slopp.edit.cli-test
  "The cli write gates, and the claim underneath them.

  Two tests, and the second is the one that matters beyond this capability.
  `cli-gates-guard-the-declared-surface` covers what each gate refuses.
  `the-cli-gates-arm-themselves-with-the-capability` covers something else:
  that NOTHING in `slopp.edit.cli` mentions `cli.enabled`, and the gates are
  inert on a store that never opted in anyway.

  That is the capability mechanism being tested against a capability it was not
  written for. It shipped with `http`, where nine gates each carried their own
  `(when (web-enabled? candidate) …)` wrapper; if the derivation only worked
  for the case it was extracted from, this is where that would show."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.edit.cli :as edit.cli]
            [slopp.edit.gates :as gates]))

(deftest cli-gates-guard-the-declared-surface
  (let [land (fn [st src] (store/ingest st 'app.cmds (str "(ns app.cmds)\n\n" src "\n")))
        on   (first (store/record-config-put (store/empty-store) "capabilities"
                                             :manifest "cli.enabled" "true"))
        good "(defn ^{:cli/command \"add\" :cli/doc \"A.\" :cli/args [:catn [:t :string]]} add \"A.\" [ctx args] args)"]
    (testing "cli-args-schema: a command with no declared arguments refuses"
      ;; argv is UNTRUSTED INPUT, so the contract is not optional. A command
      ;; without one has moved the boundary into its own body, where nothing
      ;; checks it and every command re-implements it differently.
      (let [s (land on "(defn ^{:cli/command \"add\" :cli/doc \"A.\"} add \"A.\" [ctx args] args)")]
        (is (re-find #":cli/args" (str (edit.cli/cli-args-schema s 'app.cmds 'add))))
        (testing "and a declared one discharges it — including the empty schema"
          ;; a command that genuinely takes nothing must be able to say so
          (let [s2 (land on "(defn ^{:cli/command \"n\" :cli/doc \"N.\" :cli/args [:catn]} n \"N.\" [ctx args] args)")]
            (is (nil? (edit.cli/cli-args-schema s2 'app.cmds 'n)))))))
    (testing "cli-command-collision: one name has one owner"
      (let [one (land on good)
            two (store/ingest one 'app.more
                              (str "(ns app.more)\n\n"
                                   "(defn ^{:cli/command \"add\" :cli/doc \"D.\" :cli/args [:catn]} dupe \"D.\" [ctx args] args)\n"))]
        (is (re-find #"add" (str (edit.cli/cli-command-collision two 'app.more 'dupe))))
        (testing "and BOTH sides are told, because either is the one that can move"
          ;; the second claim is not privileged: whichever the author is
          ;; editing should learn about the other
          (is (re-find #"dupe" (str (edit.cli/cli-command-collision two 'app.cmds 'add)))))
        (testing "a form is never its own collision — the re-land case"
          ;; asserted on the store where `add` is the ONLY claim, or this
          ;; passes for the wrong reason the moment a duplicate exists
          (is (nil? (edit.cli/cli-command-collision one 'app.cmds 'add))))))
    (testing "cli-direct-stdio: a command prints by RETURNING, not by printing"
      ;; the command's answer is data and slopp renders it. A println in the
      ;; body is a second output channel nobody renders, ordered against the
      ;; first by accident, and invisible to a test asserting on the return.
      (let [s (land on (str "(defn ^{:cli/command \"add\" :cli/doc \"A.\" :cli/args [:catn]} add \"A.\"\n"
                            "  [ctx args] (println \"hi\") args)"))]
        (is (re-find #"println" (str (edit.cli/cli-direct-stdio s 'app.cmds 'add))))
        (is (re-find #"return" (str (edit.cli/cli-direct-stdio s 'app.cmds 'add)))
            "and the teaching says what to do instead"))
      (testing "System/exit too — the launcher owns the exit, a command returns :cli/exit"
        (let [s (land on (str "(defn ^{:cli/command \"q\" :cli/doc \"Q.\" :cli/args [:catn]} q \"Q.\"\n"
                              "  [ctx args] (System/exit 1))"))]
          (is (re-find #"(?i)exit" (str (edit.cli/cli-direct-stdio s 'app.cmds 'q))))))
      (testing "but writing to the INJECTED stream is exactly what it is for"
        ;; streaming progress is the case a return value cannot express, and
        ;; the ctx is how the capability supports it
        (let [s (land on (str "(defn ^{:cli/command \"p\" :cli/doc \"P.\" :cli/args [:catn]} p \"P.\"\n"
                              "  [ctx args] (.write (:cli/out ctx) \"tick\") args)"))]
          (is (nil? (edit.cli/cli-direct-stdio s 'app.cmds 'p)))))
      (testing "and a NON-command in the same namespace may print freely"
        ;; the rule is about a command's contract, not about purity — printing
        ;; is already classified elsewhere and this must not re-litigate it
        (let [s (land on "(defn shout \"S.\" [x] (println x))")]
          (is (nil? (edit.cli/cli-direct-stdio s 'app.cmds 'shout))))))))

(deftest the-cli-gates-arm-themselves-with-the-capability
  ;; Wave 1's claim, tested against a capability that did not exist when it was
  ;; built: a gate is inert until its capability is declared, and NOTHING in
  ;; the gate says so. Not one of the three functions in `slopp.edit.cli`
  ;; mentions `cli.enabled` — they are inert because of where they LIVE.
  ;;
  ;; This is the assertion that would have caught the nine hand-written
  ;; `(when (web-enabled? candidate) …)` wrappers being forgotten, and it costs
  ;; nothing for capability #3 to inherit.
  (let [src (str "(ns app.cmds)\n\n"
                 ;; every gate's violation at once: no :cli/args, and it prints
                 "(defn ^{:cli/command \"add\" :cli/doc \"A.\"} add \"A.\"\n"
                 "  [ctx args] (println \"hi\") args)\n")
        off (store/ingest (store/empty-store) 'app.cmds src)
        on  (first (store/record-config-put off "capabilities" :manifest
                                            "cli.enabled" "true"))]
    (testing "OFF: a store that never opted into cli lands a command with every violation"
      ;; the adoption story — a project that is not a cli app is untouched by
      ;; cli rules, including one that happens to use the marker for its own
      ;; reasons
      (let [{:keys [refusals advisories]} (gates/gate-check off 'app.cmds 'add)]
        (is (empty? refusals) (pr-str refusals))
        (is (empty? advisories) (pr-str advisories))))
    (testing "ON: the same form, the same store, now refuses"
      (let [{:keys [refuse refusals]} (gates/gate-check on 'app.cmds 'add)]
        (is (some? refuse) "declaring cli.enabled must arm the gates")
        (is (<= 2 (count refusals))
            (str "both violations are reported from one candidate, so the"
                 " author fixes them in one resend rather than learning about"
                 " the second after fixing the first: " (pr-str refusals)))))
    (testing "and the gates themselves carry no capability guard"
      ;; the property under test, stated as a property rather than as three
      ;; examples: dispatch owns inertness, so a gate answers about the FORM
      ;; wherever it is asked
      (is (some? (edit.cli/cli-args-schema off 'app.cmds 'add))
          "a gate asked directly still answers, because it is a question about the form")
      (is (= "cli" (gates/gate-capability #'edit.cli/cli-args-schema)))
      (is (= "cli" (gates/gate-capability #'edit.cli/cli-direct-stdio)))
      (is (= "cli" (gates/gate-capability #'edit.cli/cli-command-collision))))))
