(ns slopp-server.build
  "The one-shot build entry: `slopp build [dir] [--out DIR] [--main ns/fn]
  [--name NAME]` materializes the project at `dir` into a runnable tree from
  the command line — the same materialization the `build` op makes for an
  agent, with no slopp server running. Building is how a jar or a binary
  gets cut, and the process that would serve a routed call is often the one
  being rebuilt, so this entry opens the store itself, read-only, and builds
  what has LANDED on the branch. The launcher boots it from the neutral dir,
  like `slopp-server.dev`: the jar's own code is the builder and the
  project's store is what it builds."
  (:require [slopp.ops.external :as external]
            [slopp.ops :as ops]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse-args
  "`[dir] [--out DIR] [--main ns/fn] [--name NAME]` as a map, or `{:error}`
  naming the one argument that was wrong — an unknown option, an option
  with no value, a second directory. No dir is not an error: the caller
  defaults it to the current directory. Options and the dir may come in
  any order, because a shell user reaches for both spellings."
  [args]
  (loop [[a & more :as remaining] (seq args)
         m {}]
    (cond
      (empty? remaining) m

      (#{"--out" "--main" "--name"} a)
      (if (or (empty? more) (str/starts-with? (first more) "--"))
        {:error (str a " needs a value: slopp build [dir] [--out DIR] [--main ns/fn] [--name NAME]")}
        (recur (rest more) (assoc m (keyword (subs a 2)) (first more))))

      (str/starts-with? a "--")
      {:error (str "unknown option " a ": slopp build [dir] [--out DIR] [--main ns/fn] [--name NAME]")}

      (:dir m)
      {:error (str "one project directory at a time — got " (:dir m) " and " a)}

      :else (recur more (assoc m :dir a)))))

(defn build!
  "Build the project at `dir` into `out` — `<dir>/target/jar-src` by default,
  where `clojure -T:build uber` reads; a relative `out` resolves against the
  project — and answer `build!`'s own map (`:built`, `:native` when the
  store declares an entry, every warning it raises) or `{:error}`.

  The store is opened read-only and lazily: no image boots, no thread is
  adopted, nothing is written. The tree is what the BRANCH holds — a
  thread's un-landed work stays out, exactly as a release should. A dir
  with no store is refused before anything opens, because `open!` on such a
  dir answers an empty store and the build would succeed at producing
  nothing."
  [dir & {:keys [out main name]}]
  (let [proj (.getCanonicalFile (io/file dir))
        o    (if out (io/file out) (io/file proj "target" "jar-src"))
        out  (.getCanonicalPath (if (.isAbsolute o) o (io/file proj (str out))))]
    (if-not (.exists (io/file proj ".slopp" "store.db"))
      {:error (str "no .slopp/store.db in " proj " — slopp build materializes a project's store")}
      (let [sess (external/open! {:slopp.ops/dir (str proj)
                                  :slopp.ops/lazy-image? true
                                  :slopp.ops/read-only? true})]
        (try
          (external/build! sess out :main (some-> main symbol) :name name)
          (finally (ops/close! sess)))))))

(defn report-lines
  "What a finished build prints, one line each: where the tree landed; the
  native recipe's script and binary when one was written (the compile is
  the reader's next step, not this one — native-image needs GraalVM and
  minutes); and everything `build!` raised, because a tree that is a
  superset of the store, or is missing an artifact, is a fact the shell has
  to hear or nobody does. `r` is `slopp.ops.external/build!`'s result map,
  read by key: it is that fn's contract, not a boundary of this one."
  [r]
  (let [native (:native r)]
    (cond-> [(str "slopp build: " (:built r))]
      native
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

^:unsafe (defn -main
  "Run a build from the command line: `slopp build [dir] [--out DIR] [--main
  ns/fn] [--name NAME]` (the current directory when no dir is given). Prints
  where the tree landed and what comes next; exits 1 with the reason on a
  refusal. A one-shot: the JVM exits when the build does."
  [& args]
  (let [{:keys [dir out main name error]} (parse-args args)
        r (if error {:error error} (build! (or dir ".") :out out :main main :name name))]
    (if (:error r)
      (do (.println System/err (str "slopp build: " (:error r)))
          (System/exit 1))
      (do (doseq [line (report-lines r)] (println line))
          (flush)
          (shutdown-agents)
          (System/exit 0)))))
