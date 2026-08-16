(ns slopp.build-native-test
  "O4: build! with :main emits a GraalVM native-image recipe alongside the
  sources — a generated gen-class launcher, a :native deps alias, and an
  executable build script. The actual native-image compile needs GraalVM and
  minutes of wall time, so these tests assert the emitted recipe, not the
  compile (that path is exercised manually)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops]
            [slopp.build :as build] [slopp.ops.external :as external])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest arg-style-t
  (testing "a single fixed arity of 1 receives the CLI args as ONE vector"
    (is (= :vector (build/arg-style {:fixed-arities #{1}}))))
  (testing "varargs and every other shape is applied -main style"
    (is (= :apply (build/arg-style {:varargs-min-arity 0})))
    (is (= :apply (build/arg-style {:fixed-arities #{2}})))
    (is (= :apply (build/arg-style {:fixed-arities #{1} :varargs-min-arity 1})))
    (is (= :apply (build/arg-style {:fixed-arities #{0 1}})))))

(deftest launcher-source-t
  (let [src (build/launcher-source 'calc.core/run-cli :vector)]
    (is (re-find #"\(ns native\.main" src))
    (is (re-find #":gen-class" src))
    (is (re-find #"\[calc\.core\]" src))
    (is (re-find #"\(calc\.core/run-cli \(vec args\)\)" src))
    (is (re-find #"shutdown-agents" src)))
  (is (re-find #"\(apply calc\.core/run-cli args\)"
               (build/launcher-source 'calc.core/run-cli :apply))))

(deftest cli-launcher-source-t
  ;; A cli app's entry is GENERATED, which is the whole reason `cli` is a
  ;; capability rather than a library: the author writes commands and slopp
  ;; writes the launcher, so argv parsing, stream wiring and the exit code are
  ;; infrastructure nobody re-implements per app.
  (let [src (build/cli-launcher-source "greet" '[greet.commands greet.admin])]
    (is (re-find #"\(ns native\.main" src))
    (is (re-find #":gen-class" src))

    (testing "every command namespace is REQUIRED, not merely named"
      ;; `commands-in` finds commands with `find-ns`, and a namespace that is
      ;; not loaded contributes nothing rather than throwing. So a launcher that
      ;; passes a namespace it never required produces a program with NO
      ;; commands and no error — a bare invocation prints an empty command list
      ;; and every real invocation says "unknown command". The require is what
      ;; makes the list non-empty, and it has no other job.
      (is (re-find #"\[greet\.commands\]" src))
      (is (re-find #"\[greet\.admin\]" src))
      (is (re-find #"\[slopp\.cli\]" src)))

    (testing "and the same namespaces are what it scans"
      (is (re-find #"commands-in '\[greet\.commands greet\.admin\]" src)
          "one list, quoted — two lists could disagree about which namespaces exist"))

    (is (re-find #":cli/name \"greet\"" src)
        "the program names itself, so usage says `greet ...` and not the ns")

    (testing "the process is the ONE place a status code is acted on"
      ;; `run` returns {:cli/exit …} rather than exiting, so a test and a
      ;; process see the same answer. This is where that answer becomes a
      ;; process's answer, and the flush has to precede it — stdout is buffered
      ;; and System/exit does not drain it.
      (is (re-find #"\(flush\)[\s\S]*System/exit" src)))

    (testing "it reads as Clojure"
      ;; edn rather than the reader: this asserts the generated text PARSES,
      ;; and the reader would also resolve, which is a different claim and one
      ;; that cannot be made about namespaces this test never defines.
      (let [forms (edn/read-string (str "[" src "]"))]
        (is (= 'ns (ffirst forms)))
        (is (= 'native.main (second (first forms))))
        (is (some #(= '-main (second %)) forms)
            "a gen-class entry with no -main compiles and then does nothing")))))

(deftest recipe-content-t
  (testing "native deps.edn parses and carries the :native alias"
    (let [d (edn/read-string (build/deps-edn true))]
      (is (= ["src"] (:paths d)))
      (is (some? (get-in d [:aliases :native :extra-deps
                            'com.github.clj-easy/graal-build-time])))
      (is (some #{"-Dclojure.compiler.direct-linking=true"}
                (get-in d [:aliases :native :jvm-opts])))))
  (testing "plain deps.edn is unchanged without native"
    (is (= {:paths ["src"]} (edn/read-string (build/deps-edn false)))))
  (testing "the script AOT-compiles the launcher, then native-images it"
    (let [s (build/native-script "calc" [])]
      (is (re-find #"compile 'native\.main" s))
      (is (re-find #"native-image" s))
      (is (re-find #"--no-fallback" s))
      ;; graal-build-time's Feature is NOT auto-discovered (its jar carries no
      ;; META-INF/native-image properties) — the flag is load-bearing
      (is (re-find #"--features=clj_easy\.graal_build_time\.InitClojureClasses" s))
      (is (re-find #"-o \"calc\"" s)))))

(deftest ^:external build-native-t
  (let [sess (external/open!)
        dir  (str (Files/createTempDirectory "slopp-native"
                                             (make-array FileAttribute 0)))]
    (try
      (ops/ingest! sess 'calc.core
                   (str "(ns calc.core)\n"
                        "(defn run-cli [args]\n"
                        "  (doseq [a args] (println a)))\n"))
      (testing "an unknown entry fn is rejected before anything is written"
        (is (:error (external/build! sess dir :main 'calc.core/nope)))
        (is (:error (external/build! sess dir :main 'nope.core/run-cli)))
        (is (:error (external/build! sess dir :main 'unqualified))))
      (testing "build! with :main emits src + launcher + executable script"
        (let [r (external/build! sess dir :main 'calc.core/run-cli)]
          (is (nil? (:error r)))
          (is (:built r))
          (is (= "calc" (get-in r [:native :binary])))
          (is (.exists (io/file dir "src" "calc" "core.clj")))
          ;; run-cli takes ONE seq of args — the launcher must pass a vector
          (is (re-find #"\(calc\.core/run-cli \(vec args\)\)"
                       (slurp (io/file dir "src" "native" "main.clj"))))
          (let [script (io/file dir "build-native.sh")]
            (is (.exists script))
            (is (.canExecute script)))
          (is (contains? (:aliases (edn/read-string
                                    (slurp (io/file dir "deps.edn"))))
                         :native))))
      (testing "rebuilding into the same dir (our own deps.edn) is allowed"
        (is (= "mycalc" (get-in (external/build! sess dir :main 'calc.core/run-cli
                                            :name "mycalc")
                                [:native :binary]))))
      (testing "a FOREIGN deps.edn blocks the native build (X4 no-clobber)"
        (let [dir3 (str (Files/createTempDirectory "slopp-foreign"
                                                   (make-array FileAttribute 0)))]
          (spit (io/file dir3 "deps.edn") "{:paths [\"lib\"]}\n")
          (is (:error (external/build! sess dir3 :main 'calc.core/run-cli)))
          (is (= "{:paths [\"lib\"]}\n" (slurp (io/file dir3 "deps.edn"))))))
      (testing "plain build! emits no native artifacts"
        (let [dir2 (str (Files/createTempDirectory "slopp-plain"
                                                   (make-array FileAttribute 0)))
              r    (external/build! sess dir2)]
          (is (:built r))
          (is (nil? (:native r)))
          (is (not (.exists (io/file dir2 "src" "native"))))
          (is (= {:paths ["src"]}
                 (edn/read-string (slurp (io/file dir2 "deps.edn")))))))
      (finally (ops/close! sess)))))

(deftest ^:external a-cli-app-gets-a-generated-entry
  ;; Nothing about a cli app names an entry fn. The author writes commands and
  ;; slopp writes the launcher — which is the whole difference between `cli` as
  ;; a capability and `cli` as a library, because it is what makes argv parsing,
  ;; stream wiring and exit codes infrastructure that gets reviewed once rather
  ;; than per-app code that is wrong differently every time.
  (let [sess (external/open!)
        mk!  #(str (Files/createTempDirectory "slopp-cli" (make-array FileAttribute 0)))]
    (try
      (ops/config-file! sess "capabilities" :key "cli.enabled" :value "true"
                        :prompt "this app is a command-line program")

      (testing "cli.enabled with no commands REFUSES"
        ;; a shell over nothing: the binary would build, run, and be able to do
        ;; exactly nothing — and `commands-in` cannot tell that from a namespace
        ;; that failed to load, so the program would not say so either
        (let [r (external/build! sess (mk!))]
          (is (:error r))
          (is (re-find #":cli/command" (:error r))
              (str "the refusal must name what is missing: " (pr-str r)))))

      (ops/ingest! sess 'greet.commands
                   (str "(ns greet.commands)\n\n"
                        "(defn ^{:cli/command \"hello\"\n"
                        "        :cli/doc \"Greet someone by name.\"\n"
                        "        :cli/args [:catn [:who :string]]}\n"
                        "  hello \"Greet.\" [_ctx args] {:greeting (str \"hi \" (:who args))})\n"))

      (testing "a cli store builds an entry it never declared"
        (let [dir (mk!)
              r   (external/build! sess dir)]
          (is (nil? (:error r)) (pr-str r))
          (is (= "greet" (get-in r [:native :binary]))
              "unset app.name falls back to the command namespaces' own family")
          (let [launcher (io/file dir "src" "native" "main.clj")]
            (is (.exists launcher))
            (let [src (slurp launcher)]
              (is (re-find #"\[greet\.commands\]" src)
                  "the store's OWN command namespace is required, or the binary has no commands")
              (is (re-find #"commands-in '\[greet\.commands\]" src))
              (is (re-find #":cli/name \"greet\"" src)
                  "usage names the binary the user typed, not a namespace")))
          (let [script (io/file dir "build-native.sh")]
            (is (.exists script))
            (is (.canExecute script)))
          (is (contains? (:aliases (edn/read-string (slurp (io/file dir "deps.edn"))))
                         :native)
              "the :native alias used to key off app.main alone, so a cli app
               got no AOT path and its own launcher could not compile")))

      (testing "app.name names the binary AND the usage line"
        ;; deliberately ONE setting rather than a `cli.name` beside it: if the
        ;; two could differ, usage would teach a command the shell does not
        ;; have, which is the single worst thing a help text can do.
        (let [dir (mk!)]
          (ops/config-file! sess "capabilities" :key "app.name" :value "hi"
                            :prompt "the binary is `hi`")
          (is (= "hi" (get-in (external/build! sess dir) [:native :binary])))
          (is (re-find #":cli/name \"hi\""
                       (slurp (io/file dir "src" "native" "main.clj"))))))

      (testing "app.main BESIDE cli.enabled refuses"
        ;; They collide concretely — both want src/native/main.clj and the
        ;; reserved native.main namespace — but the silent version is worse:
        ;; `native?` used to be `(boolean main)`, so a cli app that also set
        ;; app.main got a launcher calling the author's fn directly, with no
        ;; parsing, no injected streams and no exit code. That is exactly the
        ;; bare `-m` the capability exists to replace, produced by a store that
        ;; had opted INTO the capability.
        (ops/ingest! sess 'greet.core
                     "(ns greet.core)\n\n(defn -main \"M.\" [& args] (count args))\n")
        (ops/config-file! sess "capabilities" :key "app.main" :value "greet.core/-main"
                          :prompt "watch the two entries collide")
        (let [r (external/build! sess (mk!))]
          (is (:error r))
          (is (and (re-find #"app\.main" (:error r))
                   (re-find #"cli\.enabled" (:error r)))
              (str "the refusal names BOTH halves, or the reader fixes the wrong one: "
                   (pr-str r)))))
      (finally (ops/close! sess)))))

(deftest ^:external build-reads-the-app-manifest
  (let [sess (external/open!)
        dir  (str (Files/createTempDirectory "slopp-appmain"
                                             (make-array FileAttribute 0)))]
    (try
      (ops/ingest! sess 'calc.core
                   (str "(ns calc.core)\n"
                        "(defn run-cli [args]\n"
                        "  (doseq [a args] (println a)))\n"))
      (testing "with app.main + app.name set, build! needs no arguments"
        (ops/config-file! sess "capabilities" :key "app.main" :value "calc.core/run-cli"
                          :prompt "persist the entry point")
        (ops/config-file! sess "capabilities" :key "app.name" :value "mycalc"
                          :prompt "persist the app name")
        (let [r (external/build! sess dir)]
          (is (nil? (:error r)) (pr-str r))
          (is (= "mycalc" (get-in r [:native :binary])) (pr-str r))
          (is (.exists (io/file dir "src" "native" "main.clj")))))
      (testing "explicit arguments still override the configured manifest"
        (let [dir2 (str (Files/createTempDirectory "slopp-appmain2"
                                                   (make-array FileAttribute 0)))
              r (external/build! sess dir2 :main 'calc.core/run-cli :name "other")]
          (is (= "other" (get-in r [:native :binary])) (pr-str r))))
      (finally (ops/close! sess)))))
