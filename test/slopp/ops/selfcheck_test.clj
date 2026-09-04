(ns slopp.ops.selfcheck-test
  "Invariants about slopp's OWN store — the questions that can only be asked
  of the whole codebase at once.

  These are not ordinary tests of a subject. Each asks something structural
  that no gate sees at write grain: is every public write verb reachable from
  outside, does any prose name a tool that does not exist. They live here
  rather than beside the surfaces they judge because they need the reference
  graph, and `slopp.api` is the module that already declares that dependency.

  **Their characteristic failure is passing on NOTHING**, and it is not
  hypothetical: the first such guard scanned an empty store for its entire
  life, because `open!` in the external tier's build dir hands back a store
  with no code in it. `slopp.ops.external/built-store` is the seam that fixed
  that. Every test here asserts its own POPULATION first, and asserts that its
  detector still fires — a whole-store check that has only ever been observed
  green is indistinguishable from one that cannot fail."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [slopp.store :as store]
            [slopp.index.refs :as refs]
            [slopp.ops.external :as external] [slopp.project.capabilities :as capabilities] [rewrite-clj.node :as n]))

(deftest ^:external
  ^{:correspondence "the public WRITE verbs in the operation surface vs what the MCP wire dispatches — a verb nobody can reach is surface an agent is told about and cannot use"}
  every-public-write-verb-is-reachable-from-the-wire
  ;; "The API gained something the wire did not" — a public `!` verb on
  ;; `slopp.api` that no tool, CLI or entrypoint can reach. It is dead surface
  ;; that the dead-surface gate cannot see, because the gate asks whether
  ;; ANYTHING references a var, and a write verb referenced only by another
  ;; write verb clears that bar while being unreachable from outside.
  ;;
  ;; Measured at 0 unreached and never guarded, because the guard needs the
  ;; WHOLE STORE and the external tier had no way to reach one — the temp dir
  ;; `build!` fills has source and no db, so `open!` there hands back an empty
  ;; store and any such assertion passes on nothing. `built-store` is that
  ;; missing seam, and the population assertions below are what stop this
  ;; going the way the prose guard did for its entire life.
  (let [st      (external/built-store)
        prod    (set (remove #(str/ends-with? (str %) "-test")
                             (keys (:namespaces st))))
        ;; THE WRITE SURFACE: public, bang-named defns on the two api faces
        surface (vec (for [nsx  '[slopp.ops slopp.ops.external]
                           e    (store/forms st nsx)
                           :let [s  (store/form-sexpr (:node e))
                                 nm (store/form-symbol (:node e))]
                           :when (and s nm
                                      (= 'defn (first s))
                                      (str/ends-with? (str nm) "!")
                                      (not (:private (meta (second s)))))]
                       {:sym  (symbol (str nsx) (str nm))
                        ;; the escape, and it is the marker that already means
                        ;; "reached from outside the store" — a runtime-resolved
                        ;; or string-eval'd entry. A blocking check with no way
                        ;; out for a legitimate case is the standing rule this
                        ;; repo set and then had to dial `breaking-changes` back
                        ;; for.
                        :ok?  (boolean (:unused-ok (meta (second s))))}))
        ;; the production call graph, forward
        out     (reduce (fn [m r]
                          (if (and (contains? prod (:from-ns r)) (:from-var r))
                            (update m (symbol (str (:from-ns r)) (str (:from-var r)))
                                    (fnil conj #{})
                                    (symbol (str (:to-ns r)) (str (:to-name r))))
                            m))
                        {} (refs/refs st))
        ;; every var the OUTSIDE can start at: the wire dispatch and the mains
        roots   (into #{'slopp.mcp/call-tool!}
                      (for [nsx  prod
                            e    (store/forms st nsx)
                            :let [nm (store/form-symbol (:node e))]
                            :when (= '-main nm)]
                        (symbol (str nsx) "-main")))
        reached (loop [seen #{} todo (vec roots)]
                  (if-let [v (peek todo)]
                    (if (seen v)
                      (recur seen (pop todo))
                      (recur (conj seen v) (into (pop todo) (get out v))))
                    seen))
        unreached (vec (sort (for [{:keys [sym ok?]} surface
                                   :when (and (not ok?) (not (reached sym)))]
                               sym)))]
    (testing "there is a POPULATION — the failure mode this seam exists to end"
      (is (< 50 (count prod)) (str "expected slopp's namespaces, got " (count prod)))
      (is (< 40 (count surface))
          (str "expected the api write surface, got " (count surface)))
      (is (< 1 (count roots)) (str "expected the wire plus the mains, got " roots)))
    (testing "the reachability actually traverses — a check that cannot fail is not a check"
      (is (contains? reached 'slopp.ops/add-form!)
          "the wire dispatch reaches the base write, or the graph is not being walked")
      (is (not (contains? reached 'slopp.ops/definitely-not-a-real-verb!))))
    (is (empty? unreached)
        (str "public write verb(s) no wire or CLI entrypoint can reach: "
             (pr-str unreached)
             " — either route it, or mark the name ^:unused-ok saying it is"
             " reached from outside the store"))))

(deftest ^:external
  ^{:correspondence "every key in project.capabilities/registry vs the production forms that MENTION it — a registry row nothing reads is either dead or an unimplemented feature, and today the two are indistinguishable: query_capabilities advertises it to every project and setting it does nothing"}
  no-capability-key-goes-unmentioned-by-production-code
  ;; A registry row nothing reads is worse than an absent one: absent prompts a
  ;; question, present-and-inert produces silence. `query_capabilities`
  ;; advertises every key to every project as something it can set.
  ;;
  ;; **This measures MENTIONS, not reads, and the name says so.** The right-hand
  ;; side is a PROXY: a key named only in a docstring counts as mentioned, and a
  ;; key assembled at runtime would be missed. A check computed over a proxy
  ;; must be NAMED for the proxy rather than for the thing it stands in for —
  ;; `every-capability-is-read` would make a claim this cannot support.
  ;;
  ;; The obvious stronger fix — route every read through a declared accessor, so
  ;; the readers become a derivable population — is REFUTED by measurement and
  ;; must not be re-attempted without reading this: `http.auth/config-from-values`
  ;; is a `reduce-kv` over the WHOLE values map by design, because one parser
  ;; serves both slopp's serving (store values) and a built app (the rendered
  ;; capabilities file). EIGHT of the keys are read that way. A per-key accessor
  ;; would break the built-app path or need a fake lookup existing only to
  ;; satisfy a check.
  ;;
  ;; Matching is by BASE for a pattern key (`http.auth.static.*` → the literal
  ;; prefix `http.auth.static.` the parser actually spells), which is why a
  ;; family read by prefix is correctly seen. It lives here rather than beside
  ;; the registry because `project.capabilities` requires only `clojure.string`,
  ;; and a store-wide check there costs a slopp edge out of a low-layer module.
  (let [st       (external/built-store)
        base     (fn [k] (str/replace k #"\.\*.*$" ""))
        prod     (vec (for [n (keys (:namespaces st))
                            :when (not (str/ends-with? (str n) "-test"))
                            :when (not= 'slopp.project.capabilities n)
                            f (store/forms st n)]
                        (str (:node f))))
        mentions (fn [k] (boolean (some #(str/includes? % (base k)) prod)))
        orphans  (vec (remove mentions (map :key capabilities/registry)))]
    (testing "there is a population on BOTH sides"
      (is (< 10 (count capabilities/registry)) "the registry")
      (is (< 100 (count prod)) "the production forms this scans"))
    (testing "the detector bites — an empty orphan list must not be its only mode"
      (is (not (mentions "web.nosuch.invented.key")))
      (is (mentions "http.port") "and a key that IS mentioned is seen"))
    (testing "every declared capability is mentioned by some production form"
      (is (= [] orphans)
          (str "declared and mentioned nowhere in production code: "
               (pr-str orphans)
               " — either it is an unimplemented feature advertised as a"
               " setting (delete the row, or implement it), or it is read by a"
               " key assembled at runtime, which this cannot see: say so in the"
               " reader's docstring and it will be counted")))))

(deftest ^:external no-form-cites-a-document-that-does-not-ship
  ;; The helper directories that exist for whoever works ON slopp are NOT part
  ;; of the product, and store prose must not lean on them.
  ;;
  ;; The store SHIPS: every form is materialized into `src/` and jarred, so a
  ;; docstring is read by people who have neither directory. One of the two is
  ;; gitignored, so its paths resolve for literally nobody else. A docstring
  ;; that defers its reasoning to an unreachable file has no reasoning in it.
  ;;
  ;; **This is not hypothetical and the evidence is the reorganization that
  ;; prompted the guard.** Every such path in the store was ALREADY DEAD when
  ;; this was written — four docstrings citing files that had been moved into
  ;; subdirectories, pointing at nothing, with no tool able to notice because
  ;; nothing connects a stored form to an untracked local directory.
  ;;
  ;; The numbered-principle tags are the same defect one notch softer: the
  ;; sentence beside them usually says the thing, so the tag is a dangling
  ;; label rather than lost reasoning — but it still means nothing to a reader
  ;; without the file, and "say it or drop it" costs nothing either way.
  ;;
  ;; The patterns are BUILT rather than spelled, so this form does not report
  ;; itself. That is cheaper and more honest than an exemption list, which
  ;; would be a hand-kept carve-out in a check whose whole point is that prose
  ;; and code drift apart when nothing holds them together.
  (let [st       (external/built-store)
        dirs     [(str "." "context" "/") (str "idea" "s" "/")]
        tag-rx   (re-pattern (str "\\b" "Cor" "e \\d+"))
        cites    (fn [src]
                   (vec (distinct (concat (filter #(str/includes? src %) dirs)
                                          (re-seq tag-rx src)))))
        rows     (vec (for [n (keys (:namespaces st))
                            f (store/forms st n)
                            :let [hits (cites (str (:node f)))]
                            :when (seq hits)]
                        {:form (symbol (str n) (str (:name f))) :cites hits}))]
    (testing "there is a population — the scan reached real source"
      (is (< 2000 (count (for [n (keys (:namespaces st))
                               f (store/forms st n)] f)))))
    (testing "the detector bites, on both shapes"
      (is (= [(first dirs)] (cites (str "see " (first dirs) "architecture.md"))))
      (is (seq (cites (str "the " "Cor" "e 1 rule"))))
      (is (= [] (cites "an ordinary docstring naming no helper document"))))
    (testing "no stored form cites a document that does not ship with it"
      (is (= [] rows)
          (str (count rows) " form(s) cite a helper doc the reader will not have"
               " — state the reasoning inline instead: "
               (pr-str (mapv :form (take 8 rows))))))))

(deftest ^:external the-browser-SHIM-cannot-branch-and-so-cannot-decide
  ;; slopp has no ClojureScript test runner — no node, no doo, no karma — so a
  ;; `:cljs` namespace's only verification is that it compiled. slopp-ui's two
  ;; worst bugs both lived in exactly such a namespace, and this capability
  ;; exists to end that condition rather than relocate it into slopp.
  ;;
  ;; A `:cljs` namespace is not merely untested, it is outside the LOOP: every
  ;; edit costs a compile to learn anything, which is the slow path this whole
  ;; project exists to avoid.
  ;;
  ;; So the shim is allowed interop and nothing else. **If it cannot branch, it
  ;; cannot decide** — every judgement a browser app makes is then in
  ;; `slopp.webapp`, `:cljc`, driven headlessly by an ordinary test. That is a
  ;; real checkable property standing in for the tests that cannot exist.
  ;;
  ;; **It is a PROXY and this is the place to say so.** It proves this code
  ;; makes no decisions; it says nothing about whether the interop is correct. A
  ;; typo in a property name compiles and fails in a browser, and nothing here
  ;; will catch it. Read as coverage it would be worse than absent.
  (let [st        (external/built-store)
        branching '#{if if-not when when-not cond condp case
                     when-let if-let when-some if-some when-first
                     cond-> cond->> some-> some->> or and}
        ;; sexprs rather than TEXT, deliberately: every docstring in the shim
        ;; discusses the branch it is not allowed to write — `click-data`'s says
        ;; a `when-let` here would move the judgement into the unverifiable
        ;; namespace — and a text scan would report the explanation as the crime
        branches  (fn [sx]
                    (vec (distinct (for [v (tree-seq coll? seq sx)
                                         :when (and (seq? v) (symbol? (first v))
                                                    (branching (first v)))]
                                     (first v)))))
        scan      (fn [ns-sym]
                    (vec (for [f (store/forms st ns-sym)
                               :let [sx (try (n/sexpr (:node f)) (catch Exception _ nil))
                                     hits (branches sx)]
                               :when (seq hits)]
                           {:form (symbol (str ns-sym) (str (:name f))) :branches hits})))]

    (testing "there is a shim to scan"
      (is (<= 3 (count (store/forms st 'slopp.webapp.dom)))
          "no forms means this check has been passing on nothing"))

    (testing "the detector bites"
      (is (= '[when] (branches '(defn f [x] (when x 1)))))
      (is (= '[or] (branches '(defn f [x] (str (or x ""))))))
      (is (= [] (branches '(defn f [x] {:a (.getAttribute x "href")}))))
      (is (= [] (branches '(defn f [] "a docstring naming when-let and cond")))
          "prose about a branch is not a branch"))

    (testing "and it bites on the SAME SCAN over the namespace beside it"
      ;; the positive control, and without it a clean answer above is
      ;; indistinguishable from a scan that never reached any source.
      ;; `slopp.webapp` is nothing but decisions — it should be thick with these
      (is (<= 5 (count (scan 'slopp.webapp)))
          "the :cljc half decides, so a scan finding nothing there is broken"))

    (testing "the shim itself branches nowhere"
      (let [hits (scan 'slopp.webapp.dom)]
        (is (= [] hits)
            (str "a branch in the browser shim is a decision in the one"
                 " namespace nothing can check — move it into slopp.webapp,"
                 " where a JVM test drives it: " (pr-str hits)))))))

(deftest ^:external the-whole-store-SEAM-can-see-browser-code
  ;; `built-store` reconstructs the store from materialized source, and it is
  ;; the seam every guard in this namespace stands on. Its file filter is
  ;; `\.cljc?$` — `.clj` and `.cljc`, **not `.cljs`** — so a `:cljs` namespace
  ;; is invisible to all of them, and each reports clean on a population that
  ;; silently excludes it.
  ;;
  ;; **Fourth instance of one shape in three days**, after the family glob (a
  ;; family shipped with zero files), the deps resolver (a refusal on a lib
  ;; sitting in the basis) and the `goog.*` exemption. An extension check
  ;; written when two extensions existed is a proxy for "is this source", and it
  ;; reports on the proxy. This is the worst-behaved of the four because it
  ;; fails SILENTLY and upward: the guard above it goes green.
  ;;
  ;; And it landed inside the mechanism built to end exactly this. `built-store`
  ;; exists because a whole-store guard scanned an empty store for its entire
  ;; life; it now has a blind spot of its own, one file extension wide. Which is
  ;; why every check here asserts its own population first — that discipline is
  ;; what turned this up, on the run that should have gone green.
  (let [st (external/built-store)]
    (testing "a .cljs namespace is in the reconstructed store, under its own name"
      (is (contains? (:namespaces st) 'slopp.webapp.dom)
          (str "the browser shim is missing, so every whole-store guard is"
               " blind to it: "
               (pr-str (sort (filter #(str/starts-with? (str %) "slopp.webapp")
                                     (keys (:namespaces st)))))))
      (is (nil? (get (:namespaces st) 'slopp.webapp.dom.cljs))
          "stripping the extension has to know about .cljs too, or the ns is
           named after its own file suffix"))))

(deftest ^:external
  ^{:correspondence "the descriptor vectors slopp.mcp.tools/classified composes vs the shape its classifier requires — related by nothing but the assumption that every entry is a map, and the load failure it causes is invisible to a running image"}
  every-tool-descriptor-is-a-map-with-a-name
  ;; `classified` resolves a classification onto EVERY entry of the descriptor
  ;; groups, so an entry that is not a map takes the whole namespace down at
  ;; load — and the failure hides exactly where it matters most. A live host
  ;; keeps serving the value it already holds, the verification image keeps
  ;; the value IT already holds, and every test about the advertised surface
  ;; goes on passing while a FRESH boot cannot start the server at all.
  ;;
  ;; Not hypothetical. A descriptor edit dropped one entry's `{:name …` head
  ;; and left its `:inputSchema` keyword and schema map loose in the vector;
  ;; the store carried a commit-point marked green while nothing could boot
  ;; from it, and the reload had been failing on every poll for hours.
  ;;
  ;; Read from the STORE rather than from the loaded vars — the stale var is
  ;; the thing that hid it — and take the groups from `classified`'s own
  ;; source, so a seventh group added later is covered without being listed.
  (let [st     (external/built-store)
        forms  (store/forms st 'slopp.mcp.tools)
        sexpr  (fn [nm] (some (fn [e] (when (= nm (store/form-symbol (:node e)))
                                        (store/form-sexpr (:node e))))
                              forms))
        walk   (fn walk [x] (cond (coll? x)   (mapcat walk x)
                                  (symbol? x) [x]
                                  :else       []))
        groups (vec (distinct (filter #(str/ends-with? (str %) "-tools")
                                      (walk (sexpr 'classified)))))
        defs   (vec (for [g groups
                          :let [s (sexpr g)
                                v (when (seq? s) (last s))]]
                      {:form g :rows (if (vector? v) v [])}))
        bad    (vec (for [{:keys [form rows]} defs
                          [i row] (map-indexed vector rows)
                          :when (not (and (map? row) (:name row)))]
                      {:form form :index i :entry (pr-str row)}))]
    (testing "there is a population — the scan reached the descriptor groups"
      (is (<= 6 (count groups)) (pr-str groups))
      (is (some #{'orientation-tools} groups) (pr-str groups))
      (is (< 50 (reduce + 0 (map (comp count :rows) defs)))
          (pr-str (mapv (juxt :form (comp count :rows)) defs))))
    (testing "the detector bites, on both shapes"
      (is (not (map? :inputSchema)) "a loose keyword is not a descriptor")
      (is (nil? (:name {:type "object"})) "a headless schema map is not one either")
      (is (:name {:name "query_cost"})))
    (is (= [] bad)
        (str "tool descriptor entr(ies) that are not a map with a :name: "
             (pr-str bad)
             " — the classifier assoc's onto every entry, so a loose keyword"
             " or a headless schema map makes slopp.mcp.tools unloadable on a"
             " fresh boot while a running image keeps serving the value it"
             " already has"))))
