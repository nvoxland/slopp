(ns slopp-server.daemon.hooks-test
  "Tests for slopp-server.daemon.hooks: the system-turn rule, the Bash verdicts and their JSON, the per-session cooldown, the CLI frame and the calls it becomes (JSON and EDN arguments, add/replace/change), the tail context lines and the prompt answer's assembly and cap"
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [slopp-server.daemon.hooks :as hooks]))

(deftest a-system-continuation-is-not-an-ask
  ;; a task notification or a hook wake-up fires the prompt hook too; it
  ;; used to overwrite the human's ask in the mailbox
  (is (hooks/system-turn? "<task-notification>…"))
  (is (hooks/system-turn? "  <system-reminder>x"))
  (is (hooks/system-turn? "the task-notification said"))
  (is (not (hooks/system-turn? "fix the bug in foo")))
  (is (not (hooks/system-turn? ""))))

(deftest the-bash-verdict-blocks-a-raw-store-read-and-only-advises-the-rest
  (testing "PreToolUse: sqlite3 against the store is the one hard deny, every time"
    (let [v (hooks/bash-verdict "PreToolUse" "sqlite3 .slopp/store.db 'select 1'")]
      (is (= :deny (:verdict v)))
      (is (re-find #"query_store" (:reason v)))))
  (testing "PreToolUse never advises — advice waits for the command to run"
    (is (nil? (hooks/bash-verdict "PreToolUse" "git log --oneline"))))
  (testing "PostToolUse: the advisory smells, keyed so a cooldown can hold them"
    (is (= :git-archaeology (:smell (hooks/bash-verdict "PostToolUse" "git log -3"))))
    (is (= :git-archaeology (:smell (hooks/bash-verdict "PostToolUse" "git --no-pager diff HEAD~1"))))
    (is (= :file-source (:smell (hooks/bash-verdict "PostToolUse" "grep -n defn src/a/b.clj"))))
    (is (= :shell-eval (:smell (hooks/bash-verdict "PostToolUse" "clojure -M -e '(+ 1 2)'"))))
    (is (nil? (hooks/bash-verdict "PostToolUse" "ls -la")))
    (is (nil? (hooks/bash-verdict "PostToolUse" "sqlite3 .slopp/store.db 'select 1'"))
        "the raw-db smell is the deny's, not an advisory")))

(deftest a-verdict-becomes-the-hooks-json
  (let [deny (json/parse-string (hooks/hook-json "PreToolUse" (hooks/bash-verdict "PreToolUse" "sqlite3 .slopp/store.db x")) true)
        adv  (json/parse-string (hooks/hook-json "PostToolUse" (hooks/bash-verdict "PostToolUse" "git log")) true)]
    (is (= "deny" (get-in deny [:hookSpecificOutput :permissionDecision])))
    (is (= "PreToolUse" (get-in deny [:hookSpecificOutput :hookEventName])))
    (is (string? (get-in deny [:hookSpecificOutput :permissionDecisionReason])))
    (is (= "PostToolUse" (get-in adv [:hookSpecificOutput :hookEventName])))
    (is (re-find #"query_changes" (get-in adv [:hookSpecificOutput :additionalContext])))
    (is (nil? (hooks/hook-json "PostToolUse" nil)) "nothing to say is no output at all")))

(deftest a-cooldown-holds-a-smell-per-session-for-thirty-minutes
  (let [now 1000000
        [fire1 c1] (hooks/cooldown {} "sid-a" :git-archaeology now)
        [fire2 _]  (hooks/cooldown c1 "sid-a" :git-archaeology (+ now 60000))
        [fire3 _]  (hooks/cooldown c1 "sid-b" :git-archaeology (+ now 60000))
        [fire4 _]  (hooks/cooldown c1 "sid-a" :git-archaeology (+ now hooks/cooldown-ms 1))]
    (is (true? fire1) "first time fires")
    (is (false? fire2) "a minute later, the same session stays quiet")
    (is (true? fire3) "another session has not seen it")
    (is (true? fire4) "and after the cooldown it fires again")))

(deftest the-cli-frame-is-header-lines-a-blank-line-and-the-payload
  (is (= {:headers {"tool" "query_project"} :payload "{}"}
         (hooks/cli-frame "tool: query_project\n\n{}")))
  (is (= {:headers {"verb" "change" "prompt" "add: a thing" "accept" "a,b"}
          :payload ";;;tests t\n(deftest x)\n;;;impl i\n(defn y [])\n"}
         (hooks/cli-frame "verb: change\nprompt: add: a thing\naccept: a,b\n\n;;;tests t\n(deftest x)\n;;;impl i\n(defn y [])\n")))
  (is (= {:headers {} :payload ""} (hooks/cli-frame ""))))

(deftest a-frame-becomes-the-call-the-write-door-takes
  (testing "a tool with JSON arguments"
    (is (= {:tool "query_project" :arguments {:ns "x"}}
           (hooks/cli-call (hooks/cli-frame "tool: query_project\n\n{\"ns\":\"x\"}")))))
  (testing "a tool with EDN arguments — the spelling the launcher always advertised"
    (is (= {:tool "query_slice" :arguments {:ns "a.b" :name "c"}}
           (hooks/cli-call (hooks/cli-frame "tool: query_slice\n\n{:ns \"a.b\" :name \"c\"}")))))
  (testing "no arguments is an empty map"
    (is (= {:tool "query_project" :arguments {}}
           (hooks/cli-call (hooks/cli-frame "tool: query_project\n\n")))))
  (testing "add and replace are one change step; the name rides when given"
    (is (= {:tool "change" :arguments {:prompt "slopp add a.b" :impl [{:ns "a.b" :source "(defn f [] 1)\n"}]}}
           (hooks/cli-call (hooks/cli-frame "verb: add\ntarget: a.b\n\n(defn f [] 1)\n"))))
    (is (= {:tool "change" :arguments {:prompt "why" :impl [{:ns "a.b" :name "f" :source "(defn f [] 2)\n"}]}}
           (hooks/cli-call (hooks/cli-frame "verb: replace\ntarget: a.b/f\nprompt: why\n\n(defn f [] 2)\n")))))
  (testing "change splits ;;;tests / ;;;impl sections into steps"
    (is (= {:tool "change"
            :arguments {:prompt "slopp change" :accept ["a" "b"]
                        :tests [{:ns "t.x" :source "(deftest x)\n"}]
                        :impl  [{:ns "i.y" :source "(defn y [])\n"}]}}
           (hooks/cli-call (hooks/cli-frame "verb: change\naccept: a,b\n\n;;;tests t.x\n(deftest x)\n;;;impl i.y\n(defn y [])\n")))))
  (testing "what cannot be a call says why"
    (is (re-find #"section markers" (:error (hooks/cli-call (hooks/cli-frame "verb: change\n\n(defn y [])")))))
    (is (re-find #"target" (:error (hooks/cli-call (hooks/cli-frame "verb: add\n\n(defn y [])")))))
    (is (re-find #"JSON or EDN" (:error (hooks/cli-call (hooks/cli-frame "tool: x\n\n{not")))))
    (is (re-find #"tool" (:error (hooks/cli-call (hooks/cli-frame "\n{}")))))))

(deftest the-tail-context-is-the-lines-the-prompt-hook-used-to-print
  (let [lines (hooks/tail-context {:namespaces 274
                                   :last-commit "One root, /api"
                                   :sid "s-1"
                                   :recent-asks ["fix x" "<task-notification>" "fix x" "add y"]
                                   :standing-verdict :green
                                   :last-done-failures 2})]
    (is (re-find #"274 namespaces; last commit point: One root, /api" (first lines)))
    (is (some #(re-find #"thread: s-1 — pass \{thread \"s-1\"\}" %) lines))
    (is (some #(= "recent asks here: fix x | add y" %) lines) "system turns and repeats drop out")
    (is (some #(re-find #"full_check is GREEN and STANDS" %) lines))
    (is (some #(re-find #"HEADS-UP: the last done-point left 2 failing" %) lines)))
  (testing "nothing standing, nothing red, no session: only the first line"
    (is (= 1 (count (hooks/tail-context {:namespaces 1 :last-commit nil :recent-asks []}))))
    (is (re-find #"none yet" (first (hooks/tail-context {:namespaces 1 :recent-asks []}))))))

(deftest the-prompt-answer-puts-the-bundle-after-the-store-line-and-caps-it
  (let [t (hooks/prompt-answer "the map" ["[slopp] live store here" "thread: s"])]
    (is (= "[slopp] live store here\nthe map\nthread: s" t)))
  (testing "a bundle that already leads with [slopp] replaces the store line"
    (is (= "[slopp] its own lead\nthread: s"
           (hooks/prompt-answer "[slopp] its own lead" ["[slopp] live store here" "thread: s"]))))
  (testing "no bundle is the tail alone"
    (is (= "[slopp] live store here" (hooks/prompt-answer nil ["[slopp] live store here"]))))
  (is (<= (count (hooks/prompt-answer (apply str (repeat 20000 "x")) [])) hooks/max-chars)))
