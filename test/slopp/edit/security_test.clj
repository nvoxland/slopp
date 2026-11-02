(ns slopp.edit.security-test
  "slopp-6u3.5: tests for the security write gates."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.edit.security :as security]
            [slopp.edit.gates :as gates]
            [slopp.store :as store]))

(deftest starting-a-process-is-a-port-in-shipped-code
  ;; Measured 2026-11-02: none of these was refused on the edit or import
  ;; path — the tier gate catches shell and exec only in a namespace that
  ;; declares a tier, and ObjectInputStream was refused nowhere.
  (let [st     (fn [body]
                 (store/ingest (store/empty-store) 'app.core
                               (str "(ns app.core (:require [clojure.java.shell :as sh]))\n" body)))
        launch (fn [body] (security/process-launch (st body) 'app.core 'f))]
    (testing "each way to start a process is refused, naming the adapter way out"
      (doseq [body ["(defn f [] (sh/sh \"git\" \"status\"))"
                    "(defn f [] (clojure.java.shell/sh \"ls\"))"
                    "(defn f [] (sh \"ls\"))"
                    "(defn f [] (.start (ProcessBuilder. [\"ls\"])))"
                    "(defn f [] (.start (java.lang.ProcessBuilder. [\"ls\"])))"
                    "(defn f [] (.exec (Runtime/getRuntime) \"ls\"))"]]
        (let [err (launch body)]
          (is (re-find #"adapter \"process" (str err)) body)
          (is (re-find #"VECTOR" (str err)) "and says to build arguments as data"))))
    (testing "the form marked as the process adapter is the sanctioned place"
      (is (nil? (launch "(defn ^{:adapter \"process — git; a test passes a fake runner\"} f [] (sh/sh \"git\" \"status\"))"))))
    (testing "an http adapter is not a process adapter"
      (is (some? (launch "(defn ^{:adapter \"http — the client\"} f [] (sh/sh \"git\"))"))))
    (testing "what is not a launch passes"
      (is (nil? (launch "(defn f [] (.availableProcessors (Runtime/getRuntime)))"))
          "Runtime is not the hole, exec is")
      (is (nil? (launch "(def f '#{clojure.java.shell/sh})"))
          "a quoted name is data, not a call")
      (is (nil? (launch "(defn f [] (str \"clojure.java.shell/sh\"))"))))))

(deftest java-deserialization-has-no-escape-but-the-dial
  (let [st  (fn [body] (store/ingest (store/empty-store) 'app.core (str "(ns app.core)\n" body)))
        des (fn [body] (security/java-deserialization (st body) 'app.core 'f))]
    (doseq [body ["(defn f [in] (.readObject (java.io.ObjectInputStream. in)))"
                  "(defn f [in] (ObjectInputStream. in))"
                  "(defn ^{:adapter \"process — x\"} f [in] (ObjectInputStream. in))"]]
      (is (re-find #"clojure.edn" (str (des body))) body))
    (is (nil? (des "(defn f [s] (clojure.edn/read-string s))")))))

(deftest both-gates-are-production-only-registered-and-run-on-import
  (doseq [g [#'security/process-launch #'security/java-deserialization]]
    (is (= :production (:rule/applies-to (meta g))) (str g " exempts tests: they do not ship"))
    (is (some #{g} gates/per-form-write-gates) (str g " is a registered write gate")))
  (let [src "(ns app.core (:require [clojure.java.shell :as sh]))\n(defn f [] (sh/sh \"ls\"))\n(defn g [in] (java.io.ObjectInputStream. in))\n"
        err (security/security-scan (store/ingest (store/empty-store) 'app.core src) 'app.core)]
    (is (re-find #"app.core/f" (str err)) "the import scan names every offending form")
    (is (re-find #"app.core/g" (str err))))
  (is (nil? (security/security-scan
             (store/ingest (store/empty-store) 'app.core-test
                           "(ns app.core-test (:require [clojure.java.shell :as sh]))\n(defn f [] (sh/sh \"rm\" \"-rf\" \"x\"))\n")
             'app.core-test))
      "a test namespace is not scanned"))
