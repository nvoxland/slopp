(ns build
  "Uberjar for slopp-the-tool (kernel-side, like boot.clj — not store code).

  The jar's entry point is CONFIG, not code: the store tracks
  META-INF/MANIFEST.MF (a plain file on the files manifest, full history like
  any form). `Main-Class` names the launcher class this script GENERATES and
  AOT-compiles at build time (host scaffolding, same standing as the O4
  native launcher — gen-class never enters the store); `X-Slopp-Main` names
  the slopp entry fn it delegates to via requiring-resolve, so nothing else
  is AOT'd and the store loader keeps runtime load-string. Result:

    java -jar slopp.jar                    ; boots the store in the CWD
    java -jar slopp.jar <dir> --live
    java -jar slopp.jar --main slopp.sync/-main push <dir> <url>

  Local flow (fileless tree): materialize the store (the `build` MCP tool →
  target/jar-src) then `clojure -T:build uber`. CI flow (checkout of the
  published repo): `clojure -T:build uber :src src`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file "target/slopp.jar")

(defn- parse-manifest
  "{attr value} from <root>/META-INF/MANIFEST.MF, or nil when untracked."
  [root]
  (let [f (io/file root "META-INF" "MANIFEST.MF")]
    (when (.exists f)
      (into {}
            (keep (fn [line]
                    (let [[_ k v] (re-matches #"([A-Za-z0-9-]+):\s*(.*)"
                                              (str/trim line))]
                      (when k [k v]))))
            (str/split-lines (slurp f))))))

(defn- ns-requires-of
  "Every namespace symbol `file`'s ns form requires, read as data."
  [file]
  (let [form (try (read-string (slurp file)) (catch Exception _ nil))]
    (when (and (seq? form) (= 'ns (first form)))
      (for [clause form
            :when (and (seq? clause) (= :require (first clause)))
            spec (rest clause)]
        (if (vector? spec) (first spec) spec)))))

(defn- lib-providing
  "The basis lib whose resolved jars contain `ns-path` (e.g. \"garden/core.clj\"),
  or nil. Asks the ARTIFACTS rather than mapping namespace prefixes to lib
  names by convention — `hiccup2.core` ships in `hiccup/hiccup`, and no rule
  over the symbol would get that right."
  [libs ns-path]
  (some (fn [[lib coord]]
          (when (some (fn [p]
                        (and (.endsWith (str p) ".jar")
                             (.exists (io/file (str p)))
                             (with-open [jf (java.util.jar.JarFile. (io/file (str p)))]
                               (boolean (.getEntry jf ns-path)))))
                      (:paths coord))
            lib))
        libs))

(defn- framework-families
  "`{capability ns-prefix}` for every capability that ships a namespace family,
  read out of the MATERIALIZED SOURCE's own capability catalog.

  Read rather than written here, and that is the point. `build.clj` sits
  outside the store, so a list kept in this file would be a fourth place that
  has to learn about capability #3 — beside the catalog, the injection
  predicate and the leak guard, all of which already derive. The store is the
  source of truth about what slopp ships; this script is a consumer of it.

  Parsed textually rather than by loading `slopp.project.capabilities`: this
  runs under `-T:build`, where the store's namespaces are not on the classpath
  and loading one would drag the kernel in. The catalog is a literal vector of
  literal maps precisely so it can be read as data.

  Falls back to `{\"http\" \"slopp.web\"}` when no catalog is found, which is the
  pre-capability shape — a CHECKOUT of an older tree still builds."
  [root]
  (let [f (io/file root "slopp" "project" "capabilities.clj")]
    (or (when (.exists f)
          (not-empty
           (into {} (for [[_ cap prefix] (re-seq #"\{:capability\s+\"([^\"]+)\"[^}]*?:ns-prefix\s+\"([^\"]+)\""
                                                 (slurp f))]
                      [cap prefix]))))
        {"http" "slopp.web"})))

(defn- framework-common
  "The paths that ship with EVERY capability — the `\"_\"` family — read out of
  the materialized source's own `shipping-common` map.

  Read for the same reason the families are, and the reason is not symmetry:
  this list was a hardcoded `slopp/lang.cljc` here, a hardcoded `slopp.lang` in
  the leak guard, and NOWHERE in the place that decides what a rule may tell an
  author to use. The consequence was live — `tier-refusal`'s escape named
  `slopp.cache`, the shipped skill stated the rule, and the namespace reached no
  consuming project at all. One derivation, in the store, is what stops the next
  member being added to two of the three places.

  Parsed textually rather than by loading the namespace: this runs under
  `-T:build`, where the store's code is not on the classpath. The map is a
  literal of `symbol \"path\"` pairs precisely so it can be read as data.

  Falls back to slopp.lang alone, which is the pre-`shipping-common` shape — a
  CHECKOUT of an older tree still builds."
  [root]
  (let [f (io/file root "slopp" "project" "capabilities.clj")]
    (or (when (.exists f)
          (some->> (re-find #"(?s)shipping-common.*?'\{(.*?)\}" (slurp f))
                   second
                   (re-seq #"\"([^\"]+)\"")
                   (map second)
                   not-empty
                   (vec)))
        ["slopp/lang.cljc"])))

(defn- framework-deps
  "What the vendored framework needs from OUTSIDE, as {lib coord}.

  Vendoring `slopp/web/**` copies source and discards the pom that used to pull
  garden/hiccup/cheshire/http-kit in transitively. This re-derives that set from
  the files themselves so it cannot go stale: read their requires, drop
  `clojure.*` and `slopp.*`, and ask the basis which lib ships each remaining
  namespace. Versions are the basis's own, so what a consumer is handed is what
  this jar was built against."
  [root files basis]
  (let [libs (:libs basis)]
    (into (sorted-map)
          (keep (fn [nsym]
                  (let [s (str nsym)]
                    (when-not (or (str/starts-with? s "clojure.")
                                  (str/starts-with? s "slopp."))
                      (let [path (-> s (str/replace "-" "_") (str/replace "." "/"))
                            ;; REFUSE rather than skip. This derivation's whole
                            ;; stated purpose is that it "cannot go stale", and
                            ;; a require the basis cannot resolve used to fall
                            ;; silently out of the map — shipping a framework
                            ;; whose dependency list is missing exactly the
                            ;; entry nobody knew to look for. The failure then
                            ;; lands in a CONSUMER's repo at require time,
                            ;; which is the discovery path this whole mechanism
                            ;; exists to close (it was built after the vendored
                            ;; framework died inside slopp.web.css on garden).
                            ;;
                            ;; The fix for a refusal is real work, not a
                            ;; suppression: put the lib on the build basis, so
                            ;; the version a consumer is handed is the one this
                            ;; jar was built against.
                            ;; .cljs too, and it is not hypothetical: a browser
                            ;; family's renderer ships as `replicant/dom.cljs`,
                            ;; so a two-extension lookup finds nothing and the
                            ;; refusal below fires on a lib that is sitting in
                            ;; the basis. Second instance of this blindness in
                            ;; two days — the family GLOB had it too and shipped
                            ;; a family with zero files. An extension check
                            ;; written when one extension existed is a proxy for
                            ;; "is this source", and it reports on the proxy.
                            lib  (or (lib-providing libs (str path ".clj"))
                                     (lib-providing libs (str path ".cljc"))
                                     (lib-providing libs (str path ".cljs")))]
                        (when-not lib
                          (throw (ex-info
                                  (str "the vendored framework requires " s
                                       " and no lib on the build basis provides it."
                                       " A consumer would vendor the source and fail at"
                                       " require time. Add the lib to deps.edn's :deps —"
                                       " what ships must be resolvable from what builds it.")
                                  {:namespace s :path path})))
                        (when lib
                          ;; :mvn/version ONLY. A resolved coord carries
                          ;; :deps/manifest and friends, which are the
                          ;; resolver's bookkeeping — harmless to tools.deps
                          ;; (proven: a built app resolves and serves), but this
                          ;; lands in a generated deps.edn that people READ and
                          ;; hand-edit, where `:deps/manifest :mvn` in a
                          ;; declared position only invites "what is that for?".
                          [lib (select-keys (get libs lib) [:mvn/version])]))))))
          (mapcat #(ns-requires-of (io/file root %)) files))))

(defn- gen-launcher!
  "Write the delegating launcher ns for `main-class` under target/launcher."
  [main-class slopp-main]
  (let [path (str "target/launcher/"
                  (-> (str main-class)
                      (str/replace "." "/")
                      (str/replace "-" "_"))
                  ".clj")]
    (io/make-parents path)
    (spit path (str "(ns " main-class " (:gen-class))\n"
                    "(defn -main [& args]\n"
                    "  (apply (requiring-resolve '" slopp-main ") args))\n"))))

(defn uber
  "Build target/slopp.jar. :src = the source tree to bundle (default
  target/jar-src/src, the local materialization; pass \"src\" on a checkout).
  Entry point comes from the tracked META-INF/MANIFEST.MF next to :src's
  parent; without one the jar falls back to clojure.main (-m slopp.boot)."
  [{:keys [src] :or {src "target/jar-src/src"}}]
  ;; The tree is FILELESS: `src` is a MATERIALIZATION of the store, produced by
  ;; the `build` tool. `uber` alone re-jars whatever is sitting there — which
  ;; may be days old — and still prints "built target/slopp.jar" in a few
  ;; seconds, so a stale jar ships silently (it did: a whole debugging cycle
  ;; chasing a fix that was never in the artifact). Refuse instead. CI passes an
  ;; explicit :src from a real checkout and is unaffected.
  (when (= src "target/jar-src/src")
    (let [srcd (io/file src)]
      ;; MISSING is unambiguous — refuse.
      (when-not (.exists srcd)
        (throw (ex-info (str "no materialized source at " src " — materialize the"
                             " store first (the `build` MCP tool, or:"
                             " slopp --call build '{\"dir\":\""
                             (.getAbsolutePath (io/file "target/jar-src")) "\"}')")
                        {:src src})))
      ;; STALE can only be a hint, never a refusal: a live session touches
      ;; store.db constantly, so "db is newer" is often off by seconds and
      ;; failing on it would block legitimate builds. Compare the newest FILE
      ;; under src — a directory's mtime does NOT move when nested files are
      ;; rewritten, which is what made the first version of this check misfire.
      (let [newest (->> (file-seq srcd)
                        (filter #(.isFile ^java.io.File %))
                        (map #(.lastModified ^java.io.File %))
                        (reduce max 0))
            db     (io/file ".slopp" "store.db")
            stamp  (io/file srcd "META-INF" "slopp" "head.edn")
            fmt    #(.format (java.text.SimpleDateFormat. "HH:mm:ss") (java.util.Date. ^long %))]
        ;; NEVER be silent about what is being shipped. `build!` stamps the
        ;; materialization with the head delta it was built from; print it so a
        ;; stale jar is visible rather than inferred. (This tool runs under -T,
        ;; whose deps replace the project's, so it has no sqlite driver to read
        ;; the current head itself — comparing is the caller's one glance.)
        ;;
        ;; The stamp is UNDER src/, so it is the same file the jar carries and
        ;; slopp.kernel.boot/jar-head reads back at runtime. It used to sit
        ;; beside the tree, where this print was its only reader and the
        ;; artifact could not answer for itself.
        (println (str "jarring a materialization of "
                      (if (.exists stamp)
                        (str "head " (:head (edn/read-string (slurp stamp))))
                        "UNKNOWN head")
                      ", written " (fmt newest)))
        (when (and (.exists db) (> (.lastModified db) newest))
          (println (str "WARNING: .slopp/store.db changed at " (fmt (.lastModified db))
                        ", after that materialization — this jar may be STALE."
                        " Re-run the `build` tool if you expect recent store"
                        " changes in it."))))))
  (b/delete {:path class-dir})
  (b/delete {:path "target/launcher"})
  (let [root  (or (.getParent (io/file (str src))) ".")
        mf    (or (parse-manifest root) {})
        main  (get mf "Main-Class" "clojure.main")
        smain (get mf "X-Slopp-Main")
        extra (not-empty (dissoc mf "Main-Class" "Manifest-Version"))
        basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs [(str src)] :target-dir class-dir})
    ;; the tracked manifest is build INPUT — b/uber generates the real one
    (b/delete {:path (str class-dir "/META-INF/MANIFEST.MF")})
    ;; Declare what this jar BUNDLES, from the basis that is producing it.
    ;; `java -jar` gives the runtime no basis, so `add-libs` believes the JVM
    ;; is bare: it "adds" coords the uberjar already carries (and loses to the
    ;; parent classloader), and resolves every transitive graph from nothing.
    ;; slopp.boot/ensure-bundled-libs! seeds this back in at startup. Generated
    ;; rather than hand-listed so it cannot drift from what actually shipped;
    ;; :paths are dropped because they name THIS machine's ~/.m2, which is not
    ;; where the code is once it is inside the jar.
    (let [f (io/file class-dir "META-INF" "slopp" "bundled-libs.edn")]
      (io/make-parents f)
      (spit f (pr-str (into (sorted-map)
                            (map (fn [[lib coord]] [lib (dissoc coord :paths)]))
                            (:libs basis)))))
    ;; THE FRAMEWORK slopp vendors into the stores it serves
    ;; (D-framework-injection part 2). Two facts, both generated so neither can
    ;; drift from what shipped:
    ;;
    ;;   framework-version.edn — which slopp-web this jar's slopp/web/** IS,
    ;;     authored once in the tracked manifest. NOT a maven version any more;
    ;;     slopp-web is never published, so this is a STAMP saying what a built
    ;;     tree carries.
    ;;   framework-files.edn   — the file list, because a jar cannot glob its
    ;;     own resources and the vendoring has to enumerate them at runtime.
    ;;     Derived from the tree being jarred rather than hand-listed: a
    ;;     hand-list is a claim that goes stale the first time a namespace is
    ;;     added to slopp.web.
    ;;   framework-deps.edn    — what those files REQUIRE from outside. Vendoring
    ;;     the source discards the pom, and the pom was what pulled garden,
    ;;     hiccup, cheshire and http-kit onto the classpath. Found the hard way:
    ;;     the vendored framework landed correctly and then failed INSIDE
    ;;     slopp.web.css, which requires garden.core.
    ;;
    ;;     DERIVED, for the same reason the file list is: read each vendored
    ;;     file's requires, drop clojure.*/slopp.*, and ask the BASIS which lib
    ;;     ships each remaining namespace by looking inside the jars it resolved.
    ;;     A hand-written vector would go stale the first time slopp.web gains a
    ;;     require — and silently, since the coord that used to mask it is gone.
    (when-let [v (get mf "X-Slopp-Framework-Version")]
      (let [f (io/file class-dir "META-INF" "slopp" "framework-version.edn")]
        (io/make-parents f)
        (spit f v)))
    ;; BOTH manifests are keyed BY CAPABILITY, and that is what makes the
    ;; capability opt-in hold at runtime rather than only in a config file:
    ;; a store that never enabled `http` is vendored no `slopp/web/**`, so
    ;; `(require 'slopp.web)` there FAILS. Vendoring everything would leave the
    ;; boundary advisory.
    ;;
    ;; The families come from the store's own capability catalog
    ;; (`shipping-families`), read out of the materialized source rather than
    ;; written here: build.clj is outside the store and a list kept here would
    ;; be a fourth place that has to learn about capability #3. `"_"` comes from
    ;; the same source — `shipping-common`, the namespaces that belong to the
    ;; DIALECT and its disciplines rather than to any one kind of application,
    ;; so a command-line app and a web app both get them.
    (let [root     (io/file (str src))
          families (framework-families root)
          ;; .clj AND .cljc AND .cljs. A family whose namespaces are PORTABLE is
          ;; not exotic — `webapp`'s loop has to load into the JVM oracle and
          ;; compile to JS, which is the whole reason a browser app can be
          ;; driven headlessly — and a `.clj`-only glob shipped it as a family
          ;; with zero files. The catalog declared the prefix, `shipping-families`
          ;; derived it, the leak guard covered it, and this reader alone could
          ;; not see the namespace: caught by reading `framework-files.edn` out
          ;; of the built artifact rather than trusting that the three readers
          ;; agreed.
          src-ext? (fn [^String n] (or (.endsWith n ".clj")
                                       (.endsWith n ".cljc")
                                       (.endsWith n ".cljs")))
          in-fam   (fn [prefix]
                     (let [path (str/replace prefix "." "/")
                           dir  (io/file root path)
                           tops (filter #(.exists ^java.io.File %)
                                        (map #(io/file root (str path %))
                                             [".clj" ".cljc" ".cljs"]))]
                       (into (vec (sort (for [f (file-seq dir)
                                              :when (and (.isFile f)
                                                         (src-ext? (.getName f)))]
                                          (str path "/"
                                               (subs (.getPath f)
                                                     (inc (count (.getPath dir))))))))
                             (map #(str path (subs (.getName ^java.io.File %)
                                                   (.lastIndexOf (.getName ^java.io.File %) ".")))
                                  tops))))
          common   (vec (sort (filter #(.exists (io/file root %))
                                      (framework-common root))))
          by-cap   (cond-> (into (sorted-map)
                                 (for [[cap prefix] families
                                       :let [fs (in-fam prefix)]
                                       :when (seq fs)]
                                   [cap (vec (sort fs))]))
                     (seq common) (assoc "_" common))
          f        (io/file class-dir "META-INF" "slopp" "framework-files.edn")]
      (io/make-parents f)
      (spit f (pr-str by-cap))
      ;; deps keyed the same way, so a web app is not handed cli's malli and a
      ;; cli app is not handed garden. Derived PER FAMILY rather than from the
      ;; flat union of the file set: one merged map would look correct and would
      ;; make every store pay for every capability.
      (spit (io/file class-dir "META-INF" "slopp" "framework-deps.edn")
            (pr-str (into (sorted-map)
                          (for [[cap fs] by-cap]
                            [cap (framework-deps root fs basis)])))))
    (when (and smain (not= main "clojure.main"))
      (gen-launcher! main smain)
      ;; the launcher dir must be ON the compile basis classpath (src-dirs
      ;; alone isn't, in this tools.build)
      (b/compile-clj {:basis      (b/create-basis
                                   {:project "deps.edn"
                                    :extra   {:paths ["target/launcher"]}})
                      :src-dirs   ["target/launcher"]
                      :class-dir  class-dir
                      :ns-compile [(symbol main)]}))
    (b/uber (cond-> {:class-dir class-dir
                     :uber-file (str jar-file ".building")
                     :basis     basis
                     :main      (symbol main)}
              extra (assoc :manifest extra)))
    ;; ATOMIC swap: writing the final path directly TRUNCATES the inode a
    ;; running server has open — its lazy classloads then read a shifted zip
    ;; and every server-side op fails (the jar-swap corruption). A rename
    ;; replaces the PATH while the old inode survives for whoever holds it:
    ;; the running server keeps serving its jar; the next launch gets this one.
    (java.nio.file.Files/move
     (.toPath (io/file (str jar-file ".building")))
     (.toPath (io/file jar-file))
     (into-array java.nio.file.CopyOption
                 [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                  java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    (println "built" jar-file "Main-Class:" main)))
