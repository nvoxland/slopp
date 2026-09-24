(ns slopp-server.build
  "The one-shot build entry: `slopp build [dir] [--native|--jar|--tree]
  [--out DIR] [--main ns/fn] [--name NAME]` builds the project at `dir` into
  its ARTIFACT from the command line, with no slopp server running. The
  materialized tree (what the `build` op makes for an agent) is the
  intermediate every artifact is cut from: `--native` (the default) runs the
  native-image recipe slopp emits into it, `--jar` runs a jar recipe, and
  `--tree` stops at the tree. Building is how a binary or a jar gets cut,
  and the process that would serve a routed call is often the one being
  rebuilt, so this entry opens the store itself, read-only, and builds what
  has LANDED on the branch. The launcher boots it from the neutral dir, like
  `slopp-server.dev`: the jar's own code is the builder and the project's
  store is what it builds."
  (:require [slopp.ops.external :as external]
            [slopp.ops :as ops]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse-args
  "`[dir] [--native|--jar|--tree] [--out DIR] [--main ns/fn] [--name NAME]`
  as a map, or `{:error}` naming the one argument that was wrong — an
  unknown option, an option with no value, a second directory, a second
  mode. No dir is not an error, and no mode is not either: the caller
  defaults them (the current directory; native). Options and the dir may
  come in any order, because a shell user reaches for both spellings."
  [args]
  (loop [[a & more :as remaining] (seq args)
         m {}]
    (cond
      (empty? remaining) m

      (#{"--native" "--jar" "--tree"} a)
      (if (:mode m)
        {:error (str "one of --native, --jar or --tree — got --" (name (:mode m)) " and " a)}
        (recur more (assoc m :mode (keyword (subs a 2)))))

      (#{"--out" "--main" "--name"} a)
      (if (or (empty? more) (str/starts-with? (first more) "--"))
        {:error (str a " needs a value: slopp build [dir] [--native|--jar|--tree] [--out DIR] [--main ns/fn] [--name NAME]")}
        (recur (rest more) (assoc m (keyword (subs a 2)) (first more))))

      (str/starts-with? a "--")
      {:error (str "unknown option " a ": slopp build [dir] [--native|--jar|--tree] [--out DIR] [--main ns/fn] [--name NAME]")}

      (:dir m)
      {:error (str "one project directory at a time — got " (:dir m) " and " a)}

      :else (recur more (assoc m :dir a)))))

(defn report-lines
  "What a finished build prints, one line each: the artifact first — the
  binary, or where the jar recipe ran — then where the tree landed; the
  native recipe's script when the tree was the goal (the compile is then
  the reader's next step); and everything `build!` raised, because a tree
  that is a superset of the store, or is missing an artifact, is a fact the
  shell has to hear or nobody does. `r` is [[build!]]'s result map, read by
  key: it is that fn's contract, not a boundary of this one."
  [r]
  (let [native (:native r)]
    (cond-> []
      (:binary r)
      (conj (str "slopp build: " (:binary r)))

      (:jar-recipe r)
      (conj (str "slopp build: jar recipe ran in " (:jar-recipe r) " — its output above names the jar"))

      true
      (conj (str (if (or (:binary r) (:jar-recipe r)) "  tree: " "slopp build: ") (:built r)))

      (and native (not (:binary r)))
      (conj (str "  native recipe: " (:built r) "/" (:script native)
                 " compiles the binary `" (:binary native) "`"))

      (:warnings native)
      (conj (str "  " (:warnings native)))

      (:client-entry r)
      (conj (str "  browser entry: " (:client-entry r)))

      (:client-entry-skipped r)
      (conj (str "  " (:client-entry-skipped r)))

      (seq (:unpruned r))
      (conj (str "  unpruned (the tree is a superset of the store): "
                 (str/join ", " (map str (:unpruned r)))))

      (seq (:missing-artifacts r))
      (conj (str "  missing artifacts: "
                 (str/join ", " (map :path (:missing-artifacts r))))))))

^:reads (defn- on-path?
  "Whether an executable named `tool` is on this process's PATH — the
  question asked BEFORE a recipe runs, so a missing GraalVM is one sentence
  up front rather than a script dying after a JVM boot."
  [tool]
  (boolean (some (fn [d] (let [f (io/file d tool)] (and (.isFile f) (.canExecute f))))
                 (str/split (or (System/getenv "PATH") "") (re-pattern java.io.File/pathSeparator)))))

^:unsafe (defn- run-in!
  "Run `cmd` (a vector) with `dir` as its working directory, its output
  flowing straight to this process's own — a native-image compile takes
  minutes and the reader wants to watch it — and answer `{:exit}`. The shape
  [[artifact!]] takes as `:sh`, so a test hands in a recorder instead."
  [cmd dir]
  (let [p (-> (ProcessBuilder. ^java.util.List (vec cmd))
              (.directory (io/file (str dir)))
              (.inheritIO)
              (.start))]
    {:exit (.waitFor p)}))

(defn artifact!
  "Cut the artifact `mode` names from a materialized tree — `r` is
  `slopp.ops.external/build!`'s result, read by key — and answer it with the
  artifact added, or `{:error}`.

  `:native` runs the `build-native.sh` slopp emitted into the tree, in the
  tree, and adds `:binary`; a store that declares no entry has no recipe and
  is told what would work. `:jar` runs a jar recipe — the project's own
  `build.clj` (slopp's case: it writes target/slopp.jar and takes `:src`, so
  it runs in the project over the tree's src), else one the tree carries
  (run there over `src`), else a refusal naming the gap — and adds
  `:jar-recipe`, the directory it ran in. `:tree` answers `r` untouched.

  Tools are checked by name first (`:which`, a fn of a tool name; the PATH
  by default): a script that fails four minutes in, after a JVM boot, on a
  missing compiler is the failure a command-line entry exists to prevent.
  `:sh` (a fn of command-vector and directory answering `{:exit}`) runs the
  recipe; the default streams its output to the terminal."
  [r mode & {:keys [proj sh which] :or {sh run-in! which on-path?}}]
  (let [tree    (:built r)
        need    (fn [tool install]
                  (when-not (which tool)
                    {:error (str tool " is not on PATH — " install)}))
        run     (fn [cmd dir what]
                  (let [{:keys [exit]} (sh cmd dir)]
                    (when-not (zero? (or exit 1))
                      {:error (str what " exited " exit " in " dir " — its output above says why")})))]
    (case mode
      :tree r

      :native
      (let [native (:native r)]
        (or (when-not native
              {:error (str "nothing to compile: this store declares no entry point, so"
                           " no native recipe was written. Set app.main (config_file"
                           " {path \"capabilities\" key \"app.main\" value \"my.ns/-main\"})"
                           " or cli.enabled, or build the tree (--tree) or a jar (--jar).")})
            (need "clojure" "the recipe AOT-compiles with the clojure CLI (clojure.org/guides/install_clojure)")
            (need "native-image" "the recipe needs GraalVM 21+ (native-image ships inside the JDK: mise install java@graalvm-community-21, or graalvm.org)")
            (run ["bash" (:script native)] tree (:script native))
            (assoc r :binary (str tree "/" (:binary native)))))

      :jar
      (let [own  (when proj (io/file (str proj) "build.clj"))
            tree-recipe (io/file (str tree) "build.clj")]
        (or (need "clojure" "a jar recipe runs under the clojure CLI (clojure.org/guides/install_clojure)")
            (cond
              (and own (.exists own))
              (or (run ["clojure" "-T:build" "uber" ":src" (pr-str (str tree "/src"))] (str proj) "clojure -T:build uber")
                  (assoc r :jar-recipe (str proj)))

              (.exists tree-recipe)
              (or (run ["clojure" "-T:build" "uber" ":src" (pr-str "src")] (str tree) "clojure -T:build uber")
                  (assoc r :jar-recipe (str tree)))

              :else
              {:error (str "no jar recipe: neither " (when proj (str proj "/build.clj nor "))
                           tree "/build.clj exists. A build.clj with an `uber` fn (tools.build)"
                           " is what a jar is cut with; the native recipe (--native) slopp"
                           " generates, a jar recipe it does not yet.")}))))))

(defn build!
  "Build the project at `dir`: materialize its store into `out` —
  `<dir>/target/jar-src` by default, where `clojure -T:build uber` reads; a
  relative `out` resolves against the project — then cut the artifact `mode`
  names from it ([[artifact!]]; `:native` by default). Answers `build!`'s
  own map (`:built`, `:native` when the store declares an entry, every
  warning it raises) with `:binary` or `:jar-recipe` added, or `{:error}`.

  The store is opened read-only and lazily: no image boots, no thread is
  adopted, nothing is written. The tree is what the BRANCH holds — a
  thread's un-landed work stays out, exactly as a release should. A dir
  with no store is refused before anything opens, because `open!` on such a
  dir answers an empty store and the build would succeed at producing
  nothing. `:sh` and `:which` pass through to [[artifact!]]."
  [dir & {:keys [out main name mode sh which] :or {mode :native}}]
  (let [proj (.getCanonicalFile (io/file dir))
        o    (if out (io/file out) (io/file proj "target" "jar-src"))
        out  (.getCanonicalPath (if (.isAbsolute o) o (io/file proj (str out))))]
    (if-not (.exists (io/file proj ".slopp" "store.db"))
      {:error (str "no .slopp/store.db in " proj " — slopp build materializes a project's store")}
      (let [sess (external/open! {:slopp.ops/dir (str proj)
                                  :slopp.ops/lazy-image? true
                                  :slopp.ops/read-only? true})
            r    (try
                   (external/build! sess out :main (some-> main symbol) :name name)
                   (finally (ops/close! sess)))]
        (if (:error r)
          r
          (artifact! r mode :proj (str proj)
                     :sh (or sh run-in!) :which (or which on-path?)))))))

^:unsafe (defn -main
  "Run a build from the command line: `slopp build [dir] [--native|--jar|--tree]
  [--out DIR] [--main ns/fn] [--name NAME]` (the current directory and a
  native binary when nothing says otherwise). Streams the recipe's output,
  then prints where the artifact and the tree landed; exits 1 with the
  reason on a refusal. A one-shot: the JVM exits when the build does."
  [& args]
  (let [{:keys [dir out main name mode error]} (parse-args args)
        r (if error
            {:error error}
            (build! (or dir ".") :out out :main main :name name :mode (or mode :native)))]
    (if (:error r)
      (do (.println System/err (str "slopp build: " (:error r)))
          (System/exit 1))
      (do (doseq [line (report-lines r)] (println line))
          (flush)
          (shutdown-agents)
          (System/exit 0)))))
