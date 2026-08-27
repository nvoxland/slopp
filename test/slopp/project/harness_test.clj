(ns slopp.project.harness-test
  "Holds the rule that decides which id a session works under.

  The subject is a table and a pure fold, so these are fast in-image tests
  with no fixture: `conversation-id` takes `getenv` as a parameter, and a map
  is a function of its keys, so a literal map stands in for a process
  environment. That is the point of the parameter — process environment is
  state a test cannot set, and a precedence rule nobody can assert is one
  that drifts silently until two agents are sharing a thread.

  What is deliberately NOT here: whether the right process reads the table.
  That is a property of `slopp.mcp/-main` and of every caller of
  `ops.external/open!`, and it is held by the mcp tests — which is where it
  failed, loudly, when the read was briefly put at the wrong depth."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.project.harness :as harness]))

(deftest a-harness-names-the-conversation-and-an-unknown-one-does-not
  ;; `getenv` is a parameter, so a plain map stands in for the process
  ;; environment — no fixture, no mutation of state a test cannot own. That is
  ;; the point of the fold being pure: the precedence rule is the thing most
  ;; likely to drift, and it is now the thing easiest to assert.
  (testing "a known harness's variable IS the conversation id"
    (is (= "sess-abc"
           (harness/conversation-id {"CLAUDE_CODE_SESSION_ID" "sess-abc"}))))
  (testing "an unset or blank variable is not an identity"
    ;; blank matters as much as absent: an empty env var would otherwise
    ;; become an agent named "", shared by every session that saw one
    (is (nil? (harness/conversation-id {})))
    (is (nil? (harness/conversation-id {"CLAUDE_CODE_SESSION_ID" ""}))))
  (testing "a variable no harness declares is ignored"
    ;; a harness slopp does not know does not get to name threads by accident
    (is (nil? (harness/conversation-id {"SOME_OTHER_AGENT_ID" "x"}))))
  (testing "every row carries what the resolver needs"
    (is (seq harness/harness-catalog))
    (is (every? (comp string? :session-env) harness/harness-catalog))
    (is (every? (comp keyword? :harness) harness/harness-catalog))
    (is (every? (comp seq :doc) harness/harness-catalog))))
