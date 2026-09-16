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
            [slopp.build :as build] [slopp.ops.external :as external] [clojure.string :as str])
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

(deftest webapp-launcher-source-t
  ;; A browser app's entry is GENERATED, for the same reason a cli app's is —
  ;; and for one more that only shows up here. In a cli app the author's
  ;; alternative to a generated launcher is a `-main` they write once. In a
  ;; browser app the alternative is TWO wirings: the `:cljs` shell that mounts,
  ;; and whatever the headless driver is handed. slopp-ui had both, plus an
  ;; adapter between them, and their scars are what this generator removes.
  ;;
  ;; The entry stays THIN on purpose: require, mount, bootstrap. Everything
  ;; browser-shaped — the render loop, click delegation, popstate, reading the
  ;; mount point — belongs in `slopp.webapp/mount!`, which is slopp's `:cljs`
  ;; code and gets slopp's tests. A fat generated entry is a second place for
  ;; browser logic to live, in a namespace whose only verification is that it
  ;; compiled.
  (let [src (build/webapp-launcher-source 'shop.ui/app '[shop.ui shop.views])]

    (testing "the page namespace is REQUIRED, not merely named"
      ;; the cli lesson, one process out: a generated entry that names a
      ;; namespace it never required calls a var that does not exist. There the
      ;; symptom was a program with no commands; here it is a blank page, which
      ;; is worse because it looks like a rendering bug rather than a wiring one
      (is (re-find #"\[shop\.ui\]" src))
      (is (re-find #"\[shop\.views\]" src))
      (is (re-find #"\[slopp\.webapp" src)))

    (testing "it calls the DECLARED page entry, by its own name"
      (is (re-find #"shop\.ui/app" src)))

    (testing "the bootstrap is a TOP-LEVEL form in the bundle"
      ;; slopp-ui raised this before it was decided, and it is a security
      ;; property rather than a style: `compile_client` emits a bundle whose
      ;; top-level forms run when the <script> loads, so the document starts the
      ;; app with NO inline JS and the page stays script-src-only. An inline
      ;; starter would put `unsafe-inline` in the CSP of every app built on this
      ;; capability — in a programme whose stated motivation is that agents
      ;; should not be able to build insecure things by accident.
      (is (re-find #"defonce" src)
          "and defonce, so a bundle evaluated twice does not mount twice")
      (is (not (re-find #"(?i)<script" src))
          "the entry must not emit markup at all — the document already has it"))

    (testing "it waits for the document when the document is not ready"
      ;; a bundle in <head> runs before the mount point exists, and mounting
      ;; into nothing is a blank page with no error
      (is (re-find #"readyState" src))
      (is (re-find #"DOMContentLoaded" src)))

    (testing "it reads as Clojure"
      ;; edn rather than the reader, for cli-launcher-source-t's reason: this
      ;; asserts the text PARSES, where the reader would also resolve — a
      ;; different claim, and one that cannot be made about namespaces this
      ;; test never defines
      (let [forms (edn/read-string (str "[" src "]"))]
        (is (= 'ns (ffirst forms)))
        (is (some #(and (seq? %) (= 'defonce (first %))) forms)
            "a bundle whose entry is not a top-level form never runs")))))

(deftest a-generated-browser-entry-carries-the-table-its-PAGES-declared
  ;; The other half of the marker becoming the declaration. A page carries
  ;; `^{:webapp/path "/things"}`; the entry fn used to carry `[["/things"
  ;; things]]` as well, and the two could disagree with nothing to notice.
  ;;
  ;; So the build reads the markers and writes the table into the generated
  ;; browser entry. `slopp.cljnx/marked-pages` does the same job for the
  ;; headless drive by scanning loaded vars — two readers of ONE marker, which
  ;; is a different thing from two declarations.
  (let [routes '[["/things" shop.ui/things] ["/things/:id" shop.views/thing]]
        src    (build/webapp-launcher-source 'shop.ui/app
                                             '[shop.ui shop.views]
                                             routes)]

    (testing "every declared address is in the table, with the page that renders it"
      (is (re-find #"\"/things\" shop\.ui/things" src) src)
      (is (re-find #"\"/things/:id\" shop\.views/thing" src) src))

    (testing "and the app's OWN table still wins when it declares one"
      ;; generated as the default rather than as an override: an app with a
      ;; reason to build its table at runtime keeps it, and does not have to
      ;; also stop slopp generating one
      (is (re-find #"or t" src)
          (str "an assoc here would silently replace a table the author wrote: "
               src)))

    (testing "a store with no marked pages gets the entry it always got"
      ;; the table is absent rather than empty — an empty literal would route
      ;; nothing while looking like a declaration
      (let [bare (build/webapp-launcher-source 'shop.ui/app '[shop.ui] nil)]
        (is (re-find #"\(dom/mount! \(shop\.ui/app\)\)" bare) bare)
        (is (not (re-find #":webapp/routes" bare)) bare)))

    (testing "it still reads as Clojure"
      (let [forms (edn/read-string (str "[" src "]"))]
        (is (= 'ns (ffirst forms)))
        (is (some #(and (seq? %) (= 'defonce (first %))) forms))))))

(deftest ^:external a-LOCAL-config-path-never-reaches-a-BUILT-tree
  ;; `build!` writes every `:config` entry as a file at its own path, with no
  ;; filter — which is right for `capabilities`, since a built app reads the
  ;; rendered file, and wrong for `dev`, which OVERRIDES capabilities for the
  ;; dev instance. A developer's dev port has no business in a jar.
  ;;
  ;; Asserted against the TREE rather than against the filter, because the
  ;; tree is what ships and the filter is one line that could be correct while
  ;; the entry arrives by another route.
  (let [sess (external/open!)
        dir  (str (Files/createTempDirectory "slopp-localcfg"
                                             (make-array FileAttribute 0)))]
    (try
      (ops/ingest! sess 'calc.core
                   (str "(ns calc.core)\n"
                        "(defn run-cli [args] (doseq [a args] (println a)))\n"))
      (ops/config-file! sess "capabilities" :key "app.main" :value "calc.core/run-cli"
                        :prompt "the product's entry point")
      (ops/config-file! sess "dev" :key "http.port" :value "7399"
                        :prompt "a dev port this developer chose")

      (let [r (external/build! sess dir)]
        (is (nil? (:error r)) (pr-str r))

        (testing "the product's config still materializes"
          (is (.exists (io/file dir "capabilities"))
              "capabilities stopped shipping — the filter is too wide"))

        (testing "and the dev section does NOT"
          (is (not (.exists (io/file dir "dev")))
              "a dev entry was written into the built tree"))

        (testing "nor does its content arrive in any other file"
          (let [hits (for [^java.io.File f (file-seq (io/file dir))
                           :when (.isFile f)
                           :when (try (str/includes? (slurp f) "7399")
                                      (catch Exception _ false))]
                       (str f))]
            (is (empty? hits)
                (str "dev content shipped inside: " (pr-str (vec hits)))))))

      (testing "and it is still IN THE STORE — local means unshipped, not unsaved"
        (is (= "7399"
               (get-in (:store @sess) [:config "dev" :values "http.port"]))))

      (finally (ops/close! sess)))))
