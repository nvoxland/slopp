(ns slopp-server.build-test
  "`slopp build [dir] [--out DIR] [--main ns/fn] [--name NAME]`: the build op as a one-shot command-line entry that needs NO slopp server. Building is how a jar or a binary gets cut, and the process that would serve a routed `build` call is often the very thing being rebuilt — so the entry opens the…"
  (:require [slopp-server.build :as build-cli]
            [slopp.ops.external :as external]
            [slopp.ops :as ops]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(deftest the-build-arguments-are-parsed-or-refused-with-a-sentence
  ;; `slopp build` is typed by a person at a shell, so a wrong argument gets a
  ;; sentence naming it — never a stack trace, never a silent default.
  (is (= {:dir "/w/p"} (build-cli/parse-args ["/w/p"])))
  (is (= {} (build-cli/parse-args [])) "no dir is not an error: the caller defaults it")
  (is (= {:dir "/w/p" :out "/w/o" :main "calc.core/run" :name "calc"}
         (build-cli/parse-args ["/w/p" "--out" "/w/o" "--main" "calc.core/run" "--name" "calc"])))
  (is (= {:out "/w/o" :dir "."} (build-cli/parse-args ["--out" "/w/o" "."]))
      "options may come before the dir")
  (testing "the artifact modes"
    (is (= {:dir "." :mode :native} (build-cli/parse-args ["." "--native"])))
    (is (= {:dir "." :mode :jar} (build-cli/parse-args ["--jar" "."])))
    (is (= {:mode :tree} (build-cli/parse-args ["--tree"])))
    (is (nil? (:mode (build-cli/parse-args ["."]))) "no mode named: the caller applies the default")
    (is (re-find #"one of" (:error (build-cli/parse-args ["--jar" "--native"])))
        "two modes is a contradiction, not a preference"))
  (is (re-find #"--out" (:error (build-cli/parse-args ["/w/p" "--out"])))
      "an option with no value names itself")
  (is (re-find #"--out" (:error (build-cli/parse-args ["--out" "--main" "a/b"])))
      "another option is not a value")
  (is (re-find #"--wat" (:error (build-cli/parse-args ["/w/p" "--wat"])))
      "an unknown option is named")
  (is (re-find #"one project" (:error (build-cli/parse-args ["/w/p" "/w/q"])))
      "two directories is a mistake, not a choice"))

(deftest a-finished-build-reports-where-it-landed-and-what-comes-next
  (testing "a plain library: the tree, one line"
    (is (= ["slopp build: /w/out"] (build-cli/report-lines {:built "/w/out"}))))
  (testing "a native recipe names the script and the binary — the compile is the reader's next step, not this one"
    (let [lines (build-cli/report-lines {:built "/w/out"
                                         :native {:binary "calc" :launcher "src/native/main.clj"
                                                  :script "build-native.sh"
                                                  :warnings "no GraalVM reachability metadata for: x/y"}})]
      (is (some #(re-find #"/w/out/build-native\.sh" %) lines) (pr-str lines))
      (is (some #(re-find #"calc" %) lines))
      (is (some #(re-find #"reachability" %) lines) "build!'s warnings reach the shell")))
  (testing "a tree that is not the store says so"
    (let [lines (build-cli/report-lines {:built "/w/out" :unpruned ["src/old"]
                                         :missing-artifacts [{:path "public/app.js"}]})]
      (is (some #(re-find #"src/old" %) lines) (pr-str lines))
      (is (some #(re-find #"public/app\.js" %) lines) (pr-str lines)))))

(deftest a-directory-with-no-store-is-refused-before-anything-opens
  ;; `open!` on a dir with no store answers an EMPTY store rather than an
  ;; error, so without this check the build would succeed at producing
  ;; nothing — a tree with a deps.edn and no code, reported as built.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-build-nostore" (make-array java.nio.file.attribute.FileAttribute 0)))
        r   (build-cli/build! dir)]
    (is (:error r))
    (is (re-find #"store\.db" (:error r)) (pr-str r))
    (is (not (.exists (io/file dir "target"))) "nothing was written")))

(deftest ^:external a-project-builds-from-its-store-with-no-server-running
  ;; The routed `build` call needs a server, and the server is often the
  ;; thing being rebuilt. This is the same materialization with the store
  ;; opened by the entry itself: what has LANDED, into the directory `uber`
  ;; reads, and un-landed thread work stays out exactly as a release should.
  (let [dir   (str (java.nio.file.Files/createTempDirectory
                    "slopp-build-runner" (make-array java.nio.file.attribute.FileAttribute 0)))
        entry (fn [v] (str "(ns worker.core)\n\n(defn -main \"Runs.\" [& _] (println " v "))\n"))
        agent (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! agent 'worker.core (entry ":v1") :agent "a")
      (ops/config-file! agent "capabilities" :key "app.main" :value "worker.core/-main" :prompt "the entry")
      (let [d (external/done! agent :label "v1" :agent "a")]
        ;; a fixture that did not land is the FIXTURE's failure, named here —
        ;; the build below would otherwise report an empty branch as its own
        (is (not= :red (get-in d [:findings :episode-status])) (pr-str (:findings d)))
        (is (get-in d [:land :landed]) (pr-str (:land d))))
      (finally (ops/close! agent)))
    (testing "the default: the tree lands in target/jar-src and the native recipe runs there"
      (let [calls (atom [])
            r     (build-cli/build! dir :sh (fn [cmd d] (swap! calls conj [cmd d]) {:exit 0})
                                    :which (constantly true))]
        (is (nil? (:error r)) (pr-str r))
        (is (= (.getCanonicalPath (io/file dir "target" "jar-src")) (:built r)) (pr-str r))
        (is (.exists (io/file dir "target" "jar-src" "src" "worker" "core.clj")))
        (is (.exists (io/file dir "target" "jar-src" "deps.edn")))
        (is (.exists (io/file dir "target" "jar-src" "build-native.sh")))
        (is (= [["bash" "build-native.sh"] (:built r)] (first @calls)) (pr-str @calls))
        (is (= (str (:built r) "/worker") (:binary r)) "app.main worker.core/-main names the binary `worker`")
        (is (some #(re-find #"/worker" %) (build-cli/report-lines r)) (pr-str (build-cli/report-lines r)))))
    (testing "--tree with --out puts the bare tree elsewhere, a relative path resolving against the project; un-landed work stays out"
      (let [writer (external/open! {:slopp.ops/dir dir})]
        (try (ops/ingest! writer 'worker.core (entry ":v2") :agent "b")
             (finally (ops/close! writer))))
      (let [r (build-cli/build! dir :out "elsewhere" :mode :tree)]
        (is (= (.getCanonicalPath (io/file dir "elsewhere")) (:built r)) (pr-str r))
        (is (nil? (:binary r)) "--tree runs no recipe")
        (let [src (slurp (io/file dir "elsewhere" "src" "worker" "core.clj"))]
          (is (re-find #":v1" src))
          (is (not (re-find #":v2" src)) "a thread's un-landed write is not what the branch holds"))))))

(deftest the-native-artifact-runs-the-emitted-recipe-in-the-tree
  ;; The tree carries the recipe (`build-native.sh`, from build!); the verb's
  ;; job is to run it where it lives and say where the binary landed. The
  ;; subprocess is injected so this proves the WIRING — which script, which
  ;; directory, what is reported — without GraalVM, which build-native-test
  ;; already declines to require for the same reason.
  (let [tree  {:built "/w/out" :native {:binary "calc" :script "build-native.sh" :launcher "src/native/main.clj"}}
        calls (atom [])
        sh    (fn [cmd dir] (swap! calls conj [cmd dir]) {:exit 0})]
    (testing "the happy path: the script runs in the tree and the binary is named"
      (let [r (build-cli/artifact! tree :native :sh sh :which (constantly true))]
        (is (nil? (:error r)) (pr-str r))
        (is (= [["bash" "build-native.sh"] "/w/out"] (first @calls)) (pr-str @calls))
        (is (= "/w/out/calc" (:binary r)))
        (is (some #(re-find #"/w/out/calc" %) (build-cli/report-lines r))
            (pr-str (build-cli/report-lines r)))))
    (testing "a missing tool is named BEFORE anything runs"
      (reset! calls [])
      (let [r (build-cli/artifact! tree :native :sh sh :which #{"clojure"})]
        (is (re-find #"native-image" (:error r)) (pr-str r))
        (is (re-find #"GraalVM" (:error r)) "the refusal says what to install")
        (is (empty? @calls) "nothing was run")))
    (testing "a script that fails says so, with its exit"
      (let [r (build-cli/artifact! tree :native :sh (fn [_ _] {:exit 3}) :which (constantly true))]
        (is (re-find #"build-native\.sh" (:error r)) (pr-str r))
        (is (re-find #"3" (:error r)))))
    (testing "a store with no entry cannot go native, and is told what would"
      (let [r (build-cli/artifact! {:built "/w/out"} :native :sh sh :which (constantly true))]
        (is (re-find #"app\.main" (:error r)) (pr-str r))
        (is (re-find #"--jar" (:error r)) "the way out is named")))
    (testing "--tree is the bare materialization, untouched"
      (is (= {:built "/w/out"} (build-cli/artifact! {:built "/w/out"} :tree :sh sh :which (constantly true)))))))

(deftest the-jar-artifact-runs-the-trees-recipe-and-lands-the-jar-in-the-project
  ;; A jar needs tools.build, which the slopp jar does not carry, so the verb
  ;; runs the RECIPE the tree carries (build.clj, a store-tracked file build!
  ;; materializes) with tools.build supplied inline — the same call the
  ;; release lane makes on a projection checkout, so there is no :build alias
  ;; and no build.clj on main. The jar it writes is copied up to the
  ;; project's target/, where every doc and SLOPP_JAR point.
  (let [mk!   (fn [] (str (java.nio.file.Files/createTempDirectory
                           "slopp-build-jar" (make-array java.nio.file.attribute.FileAttribute 0))))
        calls (atom [])
        sh    (fn [cmd dir]
                (swap! calls conj [cmd dir])
                (let [t (io/file dir "target")] (.mkdirs t) (spit (io/file t "app.jar") "jar"))
                {:exit 0})
        with-recipe! (fn [tree] (io/make-parents (io/file tree "build.clj")) (spit (io/file tree "build.clj") "(ns build)") tree)]
    (testing "the recipe runs in the tree, tools.build inline, over the tree's own src; the jar lands in the project"
      (let [proj (mk!) tree (with-recipe! (str proj "/target/jar-src"))
            r    (build-cli/artifact! {:built tree} :jar :proj proj :sh sh :which (constantly true))
            [cmd dir] (first @calls)]
        (is (nil? (:error r)) (pr-str r))
        (is (= tree dir) "run where the recipe lives")
        (is (= ["clojure" "-Sdeps" build-cli/jar-recipe-deps "-M" "-e"] (take 5 cmd)) (pr-str cmd))
        (is (re-find #"build/uber" (last cmd)))
        (is (re-find #":src \"src\"" (last cmd)) "over the tree's own src, never a path outside it")
        (is (= (str proj "/target/app.jar") (:jar r)) (pr-str r))
        (is (.exists (io/file proj "target" "app.jar")) "copied up to the project's target/")
        (is (= "jar" (slurp (io/file proj "target" "app.jar"))) "the bytes, whole")
        (is (not-any? #(re-find #"\.building$" (.getName ^java.io.File %)) (.listFiles (io/file proj "target")))
            "landed by rename: no staging file is left beside it")
        (is (some #(re-find #"target/app\.jar" %) (build-cli/report-lines r)) (pr-str (build-cli/report-lines r)))))
    (testing "a tree with no recipe is a refusal that names what to track"
      (reset! calls [])
      (let [proj (mk!)
            r    (build-cli/artifact! {:built (str proj "/out")} :jar :proj proj :sh sh :which (constantly true))]
        (is (re-find #"build\.clj" (:error r)) (pr-str r))
        (is (re-find #"file_put" (:error r)) "the way out is the files manifest")
        (is (empty? @calls))))
    (testing "a recipe that wrote no jar is named, never reported as built"
      (let [proj (mk!) tree (with-recipe! (str proj "/out"))
            r    (build-cli/artifact! {:built tree} :jar :proj proj :sh (fn [_ _] {:exit 0}) :which (constantly true))]
        (is (re-find #"no \.jar" (:error r)) (pr-str r))))
    (testing "a recipe that failed says so, with its exit"
      (let [proj (mk!) tree (with-recipe! (str proj "/out"))
            r    (build-cli/artifact! {:built tree} :jar :proj proj :sh (fn [_ _] {:exit 2}) :which (constantly true))]
        (is (re-find #"exited 2" (:error r)) (pr-str r))))
    (testing "the clojure CLI is checked first"
      (let [proj (mk!) tree (with-recipe! (str proj "/out"))]
        (is (re-find #"clojure" (:error (build-cli/artifact! {:built tree} :jar :proj proj :sh sh :which #{}))))))))
