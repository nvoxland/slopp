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
  binary or the jar — then where the tree landed; the native recipe's
  script when the tree was the goal (the compile is then the reader's next
  step); and everything `build!` raised, because a tree that is a superset
  of the store, or is missing an artifact, is a fact the shell has to hear
  or nobody does. `r` is [[build!]]'s result map, read by key: it is that
  fn's contract, not a boundary of this one."
  [r]
  (let [native   (:native r)
        artifact (or (:binary r) (:jar r))]
    (cond-> []
      artifact
      (conj (str "slopp build: " artifact))

      true
      (conj (str (if artifact "  tree: " "slopp build: ") (:built r)))

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

(def jar-recipe-deps
  "The -Sdeps a jar recipe runs under: tools.build, supplied INLINE so no
  deps.edn alias has to exist anywhere — the generated deps.edn in a tree has
  none, and main carries no deps.edn at all. The same map the release lane
  passes on a projection checkout, so the two builds cannot disagree."
  "{:deps {io.github.clojure/tools.build {:mvn/version \"0.10.5\"}} :paths [\".\"]}")

(defn artifact!
  "Cut the artifact `mode` names from a materialized tree — `r` is
  `slopp.ops.external/build!`'s result, read by key — and answer it with the
  artifact added, or `{:error}`.

  `:native` runs the `build-native.sh` slopp emitted into the tree, in the
  tree, and adds `:binary`; a store that declares no entry has no recipe and
  is told what would work. `:jar` runs the `build.clj` the tree carries (a
  file tracked on the store's files manifest, so `build!` wrote it there),
  in the tree, with tools.build supplied inline ([[jar-recipe-deps]]) and
  `build/uber {:src \"src\"}` called directly — the release lane's own call
  — then copies the newest jar under the tree's `target/` up to the
  project's `target/` and adds `:jar`, its path; a tree with no recipe is a
  refusal naming the files manifest. `:tree` answers `r` untouched.

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
      (let [recipe (io/file (str tree) "build.clj")
            cmd    ["clojure" "-Sdeps" jar-recipe-deps "-M" "-e"
                    "((requiring-resolve (quote build/uber)) {:src \"src\"})"]
            newest-jar (fn []
                         (->> (.listFiles (io/file (str tree) "target"))
                              (filter (fn [^java.io.File f]
                                        (and (.isFile f) (str/ends-with? (.getName f) ".jar"))))
                              (sort-by (fn [^java.io.File f] (.lastModified f)))
                              last))]
        (or (need "clojure" "a jar recipe runs under the clojure CLI (clojure.org/guides/install_clojure)")
            (when-not (.exists recipe)
              {:error (str "no jar recipe: " tree "/build.clj does not exist. A jar is cut by a"
                           " build.clj with an `uber` fn (tools.build) tracked on the store's"
                           " files manifest (file_put {path \"build.clj\" …}), which build!"
                           " materializes into the tree; slopp generates the native recipe"
                           " (--native), a jar recipe it does not yet.")})
            (run cmd (str tree) "build/uber")
            (if-let [^java.io.File jar (newest-jar)]
              (let [dest    (io/file (str (or proj tree)) "target" (.getName jar))
                    staging (io/file (str (or proj tree)) "target" (str (.getName jar) ".building"))]
                (io/make-parents dest)
                ;; written beside the destination, then RENAMED over it: a
                ;; running server keeps the old inode and the next launch
                ;; gets this one — overwriting in place truncates the inode
                ;; that server has open and its lazy classloads read a
                ;; shifted zip (the jar-swap corruption uber itself avoids)
                (io/copy jar staging)
                (java.nio.file.Files/move
                 (.toPath staging) (.toPath dest)
                 (into-array java.nio.file.CopyOption
                             [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                              java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
                (assoc r :jar (.getPath dest)))
              {:error (str "build/uber ran in " tree " and wrote no .jar under " tree
                           "/target — its output above says what it did instead")}))))))

(defn build!
  "Build the project at `dir`: materialize its store into `out` —
  `<dir>/target/jar-src` by default; a relative `out` resolves against the
  project — then cut the artifact `mode` names from it ([[artifact!]];
  `:native` by default). Answers `build!`'s own map (`:built`, `:native`
  when the store declares an entry, every warning it raises) with `:binary`
  or `:jar` added, or `{:error}`.

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
