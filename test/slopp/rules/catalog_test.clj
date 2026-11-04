(ns slopp.rules.catalog-test
  "The rules dial's gate."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rules.catalog :as catalog]))

(deftest weakening-a-security-rule-needs-the-humans-word
  ;; slopp-s8x.6: `config_file {path rules key process-launch value off}` was
  ;; open to any agent, so the dial was a way around every dialable gate.
  (testing "off and advisory on a security rule are refused without the ask naming it"
    (doseq [r ["process-launch" "java-deserialization" "facet-reveal" "security-hazard"]
            v ["off" "advisory" ":off"]]
      (is (re-find (re-pattern r) (str (catalog/security-dial-refusal r v "make the report faster"))))
      (is (string? (catalog/security-dial-refusal r v nil)))))
  (testing "the human's ask naming the rule lifts it, as a whole name"
    (is (nil? (catalog/security-dial-refusal "process-launch" "off" "turn process-launch off here")))
    (is (string? (catalog/security-dial-refusal "process-launch" "off" "turn my-process-launch-thing off"))))
  (testing "raising a rule, or dialing any other rule, needs nothing"
    (is (nil? (catalog/security-dial-refusal "process-launch" "refuse" nil)))
    (is (nil? (catalog/security-dial-refusal "unproven-change" "off" nil)))))
