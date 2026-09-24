(ns slopp-server.api.model
  "The reviewer UI's READ MODELS: JSON-shaped data assembled from the
  operation API's pure surfaces. No hiccup, no HTTP, no writes.

  Four models, one per screen — `timeline` (the landing page),
  `change-view` (what happened between two commit-points), `form-view`
  (one form's permalink) and `module-index` (the Code landing: the
  architecture as a drawable picture).

  **JSON-shaped is a rule, not a style.** Every value here survives a JSON
  round trip: keyword keys, vectors rather than lists or sets, and no
  symbols — a qualified symbol reads back as a string and silently stops
  being a reference, so symbols and argument vectors become text exactly
  once, here. That is what keeps a static sink (dump the models, render
  them somewhere else) a later addition rather than a rewrite, and it is
  pinned by `json-shaped?` in the specs rather than left to discipline.

  :pure, and it earns it: everything comes from the pure deep namespaces of
  slopp.api. Reaching for `slopp.api` itself — which opens the db — is a
  core→shell dependency full_check's tier-layering check refuses."
  (:require [slopp.store :as store]
            [slopp.read.query :as query]
            [slopp.read.history :as history] [slopp.edit.modules :as edit.modules] [slopp.index.refs :as refs] [slopp.read.orient :as orient] [rewrite-clj.node :as n] [clojure.string :as str]
            [slopp.read.modules :as read.modules] [slopp.edit.tiers :as tiers] [slopp.store.render :as store.render] [slopp.read.graph :as graph]))

(defn ^:export change-view
  "What changed between two commit-points, grouped module → namespace → form
  with a count at every rung so a collapsed row still says how much is
  under it. `from`/`to` are commit-point delta ids — exactly the pair
  `timeline` hands over in each row's `:range`.

  Two rungs, not three: wave 1 made components REAL namespace prefixes, so
  a component IS a module and `module-of` answers both.

  Per form: the recorded ask, the LINE diff (never whole sources — a
  reviewer reads changes), and how many distinct forms call it now. The
  caller count spans every `:via` the graph records, and the graph is a
  syntactic reader, so it is a floor rather than a census."
  [session from to]
  (let [st  (:store @session)
        ids (into #{} (map :id) (store/deltas st))]
    ;; a range arrives from a URL, so both ends are user input. "Nothing
    ;; changed here" and "that is not a range" are different answers, and
    ;; only the second one is a 404.
    (when (and (contains? ids from) (contains? ids to))
      (let [ch        (history/query-changes session :from from :to to)
            by-target (refs/refs-by-target st)
            prompts   (store/prompt-by-form st)
            rows      (for [{:keys [form form-id status was now]} (:forms ch)
                            :let [ns-sym (symbol (namespace form))]]
                        (cond-> {:form    (str form)
                                 :form-id form-id
                                 ;; keyword VALUES become strings here, for the same
                                 ;; reason `:form` is `(str form)`: a key round-trips
                                 ;; symmetrically through JSON and a value does not.
                                 ;; `diff-lines` keeps its keywords — it also feeds
                                 ;; the agent-facing text renderer, and shaping for
                                 ;; the wire is this layer's job, not its producer's.
                                 :status  (name status)
                                 :ns      (str ns-sym)
                                 :module  (edit.modules/module-of ns-sym)
                                 :diff    (mapv (fn [[k text]] [(name k) text])
                                                (history/diff-lines was now))
                                 :callers (count (distinct (map (juxt :from-ns :from-var)
                                                                (get by-target form []))))}
                          (get prompts form-id) (assoc :why (get prompts form-id))))]
        {:from    from
         :to      to
         :count   (count rows)
         :modules (->> rows
                       (group-by :module)
                       (sort-by key)
                       (mapv (fn [[m rs]]
                               {:module     m
                                :count      (count rs)
                                :namespaces (->> rs
                                                 (group-by :ns)
                                                 (sort-by key)
                                                 (mapv (fn [[n fs]]
                                                         {:ns    n
                                                          :count (count fs)
                                                          :forms (vec (sort-by :form
                                                                               (map #(dissoc % :ns :module) fs)))})))})))
         :arc     (vec (:verification-arc ch))}))))

(defn- json-card
  "A `form-card` as JSON-shaped data. Two fields cannot survive the trip:
  `:form` is a qualified symbol and `:sig` is a vector of symbols, and both
  read back as something that is no longer a reference. They become TEXT
  here, once, so no page has to know the difference."
  [card]
  (when card
    (cond-> (assoc (select-keys card [:doc :why :effectful :warranty :examples])
                   :form (str (:form card)))
      (:sig card) (assoc :sig (pr-str (:sig card))))))

(defn- snip
  "Cap `s` at `line-cap` characters with an ellipsis. A landing model is a
  SUMMARY: a commit-point whose title line is a whole paragraph, or twenty
  prompts at full length, turn the page into the thing it exists to save
  you from reading. Capped in the MODEL, not the page, so a JSON sink is
  bounded too."
  [s]
  (let [line-cap 110]
    (when s
      (if (<= (count s) line-cap) s (str (subs s 0 line-cap) "…")))))

(defn ^:export timeline
  "The reviewer landing model: commit-points newest first, each carrying the
  `from..to` range that addresses its own change screen, plus the WORKING
  SET — what has been written since the newest commit-point.

  The range is computed here rather than in the page so a template stays a
  template: a commit-point's range runs from the commit-point BEFORE it, and the
  oldest commit-point has no `:range` at all rather than an empty one.

  Deliberately not `query-changes`: this page shows counts and recorded
  asks, never sources, and `query-changes` reconstructs the before/after
  text of every touched form to answer a question nobody asked here.

  Commit-points come from `history/commit-point-rows`, the pure fold, not from
  `query-commits`: this namespace is :pure and query-commits opens the db
  to join the git projection's pinned shas. The cost is exactly that —
  `:sha` appears only for commit-points whose DELTA carries one. The fold
  needs the value's `:deltas` to hold the `:commit` markers and nothing
  more (`slopp.ops/with-history` with `:ops [:commit]`); the working set
  is the RECENT window the value always carries — everything since the
  newest commit-point plus the done that earned it, and the content filter
  drops that marker."
  [session]
  (let [st         (:store @session)
        rows       (history/commit-point-rows st :titles-only true)
        commit-points (vec (map-indexed
                         (fn [i row]
                           (let [prev (:commit (nth rows (inc i) nil))]
                             (cond-> (-> (select-keys row [:commit :description :more-lines
                                                           :status :at :agent :sha])
                                         (update :description snip)
                                         ;; a keyword VALUE reaches a consumer as a
                                         ;; string and nothing converts it back — the
                                         ;; same reason `:form` is `(str form)` and not
                                         ;; a symbol. Keys are symmetric; values are not.
                                         (update :status #(some-> % name)))
                               prev (assoc :range (str prev ".." (:commit row))))))
                         rows))
        last-commit (:commit (first rows))
        mine       (filter #(contains? history/content-ops (:op %)) (:recent st))
        asks       (vec (keep :prompt mine))
        shown      8]
    {:commit-points commit-points
     :working (cond-> {:since      (or last-commit :log-start)
                       :forms      (count (distinct (mapcat history/delta-fids mine)))
                       :namespaces (vec (distinct (keep #(some-> (:ns %) str) mine)))
                       ;; the COUNT above is exact; only the listing is capped,
                       ;; and what is left out is stated rather than dropped
                       :prompts    (mapv snip (take shown asks))}
                (> (count asks) shown)
                (assoc :more-prompts (- (count asks) shown)))}))

(def ^:private special-heads
  "Symbols the tokenizer renders as SPECIAL. Deliberately short: these are
  the forms whose head changes what the rest of the form means, so seeing
  them at a glance is what makes a page skimmable. Every other symbol is
  plain text — a long keyword list buys little and a wrong entry actively
  misleads, which is worse than no colour."
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol"
    "defrecord" "deftype" "defonce" "deftest" "ns" "let" "letfn" "fn" "if"
    "if-let" "if-not" "when" "when-let" "when-not" "cond" "condp" "case"
    "loop" "recur" "for" "doseq" "try" "catch" "finally" "throw" "do"
    "require" "testing" "is" "reify" "extend-type" "extend-protocol"})

(defn- leaf-class
  "The token class for a LEAF node: what the CST can tell apart without
  guessing. Anything unrecognised is `\"text\"` — carried, never dropped,
  and never coloured on a hunch."
  [node]
  (let [tag (n/tag node)]
    (cond
      (#{:comment} tag)                     "comment"
      (#{:whitespace :newline :comma} tag)  "ws"
      (#{:multi-line} tag)                  "string"
      (not= :token tag)                     "text"
      :else
      (let [v (try (n/sexpr node) (catch Exception _ ::unknown))]
        (cond
          (string? v)                       "string"
          (keyword? v)                      "keyword"
          (number? v)                       "number"
          (and (symbol? v)
               (special-heads (name v)))    "special"
          :else                             "text")))))

(defn- tokens-of
  "A CST node as `[[class text] ...]` — the highlight stream for a form,
  walked out of the tree the store ALREADY has. No lexer, no dependency,
  no client script, and no regex over text: a string containing a paren is
  one node here, so it stays one token.

  A branch's own delimiters (`(`, `#{`, `^`, the string quotes) are not
  separate nodes, so they are recovered as the difference between the
  branch's printed form and its children's — which stays correct for
  delimiter shapes nobody enumerated.

  The invariant the specs pin: concatenating the text reproduces the
  source exactly."
  [node]
  (if-not (n/inner? node)
    [[(leaf-class node) (n/string node)]]
    (let [s     (n/string node)
          kids  (n/children node)
          inner (apply str (map n/string kids))]
      (if (empty? inner)
        [["delim" s]]
        (let [at   (str/index-of s inner)
              open (subs s 0 at)
              shut (subs s (+ at (count inner)))
              mid  (vec (mapcat tokens-of kids))]
          (cond-> mid
            (seq open) (->> (into [["delim" open]]))
            (seq shut) (conj ["delim" shut])))))))

(def ^:private fidelities
  "The rendering FIDELITIES a form page can be asked for. One value today —
  literal Clojure — and it is a set rather than an assumption because the
  labeled notation is a live follow-up. Carrying the parameter now costs
  nothing; adding it later would mean every permalink already in the wild
  silently meant \"whatever the default became\"."
  #{"clojure"})

(defn tests-covering
  "The test namespaces that require `nsx` directly — what to open when the
  question is 'what tests this?'.

  This is the other half of taking tests out of the nav. Removing 103 rows
  from a listing is only an improvement if the names come back where they
  answer something, and the namespace page is that place.

  DIRECT requires only. A transitive closure on a real store reaches most of
  the suite and so distinguishes nothing, which is the same reason
  `covered-by` bounds its static reach. The trade is honest and worth
  naming: a test that exercises this namespace through an intermediary is
  not listed here. Form-granular coverage — with observed-versus-static
  provenance — is `slopp.index.refs/covered-by`'s job, and it answers a
  narrower question than a namespace page asks."
  [store nsx]
  (let [sym   (symbol (str nsx))
        test? #(str/ends-with? (str %) "-test")]
    (->> (keys (:namespaces store))
         (filter test?)
         (filter #(some #{sym} (store/ns-requires store %)))
         (map str)
         sort
         vec)))

(defn outline-metrics
  "The per-form facts a consumer needs to RANK a namespace's definitions,
  keyed by form name: `{:mass :calls :callers-out :callers-out-test
  :effectful? :exported?}`.

  Facts, not a score. slopp-ui asked for exactly this split and it is the
  same one `module-index` already makes by shipping layers and not a
  drawn `:picture`: the call graph and the size of a form have one right
  answer and only the store can see them; how to weight them into
  `importance` and how many perceptible steps that becomes is drawing,
  and a consumer must be able to tune it without a slopp release.

  **`:mass` is a node count over the SEXPR, never the CST.** rewrite-clj
  nodes carry whitespace and comments, so counting them would put
  formatting straight back into the metric that node-counting exists to
  escape — and lines and characters have the same bug, only louder: a
  40-line docstring over a 30-line body makes the documentation win. Over
  the sexpr a docstring is ONE node and the body's structure dominates.

  **`:calls` is same-namespace direct EDGES**, from THE reference graph,
  so a form reached through a carrier position counts as called — a
  `:calls` built from resolved calls alone draws dispatch targets as
  leaves, and those are the forms that matter most. Edges rather than
  the transitive closure because the walk is cheap and reusable, while a
  closure cannot be taken apart again. Empty rather than absent: a leaf
  is an answer.

  **Callers outside the namespace are TWO numbers, and this was measured
  rather than reasoned.** `:callers-out` counts production namespaces and
  `:callers-out-test` counts test ones. Run against `slopp-ui.views` while
  it was still a single integer, it was ranking by TEST COUNT: ten of the
  twelve cross-namespace callers were deftests, the top-ranked form held
  first place on four of them, and the entry point that IS the render sat
  fourth on its one production caller. The two add back up; one integer
  cannot be taken apart again. Outbound fan-in is the half that
  fan-in-alone gets wrong either way — an entry point is the most
  important form in its namespace and nothing there calls it.

  `:declared` edges are excluded from both directions. A `^{:covers}`
  marker is a declaration ABOUT a form, not a call to it, and counting it
  would make a well-marked helper look load-bearing."
  [store nsx]
  (let [sym      (symbol (str nsx))
        eff      (query/ns-effectful-vars store sym)
        inward   (refs/refs-by-target store)
        call?    #(not= :declared (:via %))
        test-ns? #(not= (str %) (str (edit.modules/fold-test-ns %)))
        outside  (fn [q from-ns?]
                   (into #{}
                         (comp (filter call?)
                               (filter :from-var)
                               (remove #(= sym (:from-ns %)))
                               (filter #(from-ns? (:from-ns %)))
                               (map (juxt :from-ns :from-var)))
                         (get inward q)))
        mass     (fn [node]
                   (if-some [s (store/form-sexpr node)]
                     (count (tree-seq coll? seq s))
                     0))
        calls    (reduce (fn [m r]
                           (if (and (call? r) (:from-var r) (= sym (:to-ns r)))
                             (update m (:from-var r) (fnil conj #{}) (str (:to-name r)))
                             m))
                         {}
                         (refs/ns-refs store sym))]
    (into {}
          (for [e (store/forms store sym)
                :when (:name e)
                :let [nm (:name e)
                      q  (symbol (str sym) (str nm))]]
            [(str nm)
             {:mass             (mass (:node e))
              :calls            (vec (sort (get calls nm)))
              :callers-out      (count (outside q (complement test-ns?)))
              :callers-out-test (count (outside q test-ns?))
              :effectful?       (contains? eff q)
              :exported?        (boolean (edit.modules/export-level store sym nm))}]))))

(def graph-node-cap
  "The most nodes a `neighbourhood` will return before the depth runs out.

  A DEPTH cap alone does not bound this, and the numbers are the argument.
  Measured over slopp's own store (2404 forms), breadth-first from one form,
  both directions:

  | seed                        | 1 hop | 2 hops | 3 hops |
  |-----------------------------|-------|--------|--------|
  | `store/form-by-id` (hot)    |    38 |    418 |   1231 |
  | `ops.engine/rebased-write!` |    13 |    276 |   1008 |
  | `render/test-ns?` (leaf)    |    10 |     99 |    805 |

  Three hops from a hot form is HALF THE STORE. So the ceiling belongs on
  nodes, and the depth stays what the caller asked for rather than doubling as
  the thing that protects the wire. 250 is ~10% of this store and comfortably
  more than a reader follows; the exact number matters less than that going
  over it is REPORTED rather than silently trimmed."
  250)

(defn neighbourhood
  "The call graph around `form-id` out to `depth` hops, BOTH directions, as
  `{:depth :nodes :edges :truncated}` — nil below depth 2, where the one-hop
  cards already ARE the answer.

  **Each node appears ONCE and the edges carry the repetition.** A tree is the
  obvious shape and the wrong one: slopp-ui measured an 8-row expansion
  becoming 97, because a frequently-called helper drags its whole subtree in
  at every occurrence. A node set plus an edge list says the same thing in the
  size of the GRAPH rather than the size of its unfolding — and a consumer
  that wants a tree can still build one, while the reverse is not true.

  **Every edge keeps its `:via`.** A chain that is `static` end to end is a
  claim you can lean on; one `observed` rung is not, and only `:via` says
  which you are holding. `form-view`'s standing note is that this graph is a
  floor rather than a census; carrying `:via` at every depth is how a
  multi-hop view keeps that promise instead of laundering it.

  Nodes are LEAN — identity plus signature and doc. At depth 1 you are
  READING, which is why `:callers`/`:callees` inline the whole card; past that
  you are NAVIGATING, and 250 full cards is a payload nobody reads.

  `:truncated` appears only when `graph-node-cap` stopped the walk, and names
  the depth actually reached — so a short graph is never mistaken for a small
  neighbourhood."
  [session form-id depth]
  (let [st    (:store @session)
        depth (min 3 (or depth 1))]
    (when (>= depth 2)
      (let [out   (group-by :from-form (refs/refs st))
            byq   (refs/refs-by-target st)
            id-of (fn [ns- nm] (:id (store/form-named st ns- nm)))
            q-of  (fn [id] (let [e (store/form-by-id st id)]
                             (when (:name e)
                               (symbol (str (store/ns-of-form-id st id)) (str (:name e))))))
            out-of (fn [id]
                     (concat
                      (for [r (get out id)
                            :let [t (id-of (:to-ns r) (:to-name r))]
                            :when t]
                        {:from id :to t :via (name (:via r))})
                      (for [q (keep q-of [id])
                            r (get byq q)
                            :when (and (:from-var r) (:from-form r))]
                        {:from (:from-form r) :to id :via (name (:via r))})))]
        (loop [seen #{form-id} es #{} d 0]
          (if (= d depth)
            (let [nodes (vec (sort-by :form
                                      (for [i seen
                                            :let [q (q-of i)]
                                            :when q]
                                        (merge {:form-id i :form (str q)
                                                :ns (namespace q)
                                                :module (edit.modules/module-of (symbol (namespace q)))}
                                               (select-keys
                                                (json-card
                                                 (orient/form-card session
                                                                   (symbol (namespace q))
                                                                   (symbol (name q))))
                                                [:sig :doc])))))]
              {:depth d
               :nodes nodes
               :edges (vec (sort-by (juxt :from :to :via)
                                    (filter #(and (seen (:from %)) (seen (:to %))) es)))})
            (let [new-es (into #{} (mapcat out-of) seen)
                  seen'  (into seen (mapcat (juxt :from :to)) new-es)]
              (cond
                ;; over the cap: keep the LAST complete level rather than a
                ;; half-expanded frontier, and say so
                (> (count seen') graph-node-cap)
                (assoc (neighbourhood session form-id d)
                       :truncated {:node-cap graph-node-cap :depth-reached d})

                ;; nothing new — the neighbourhood is genuinely this small, so
                ;; report the depth ASKED FOR rather than where the walk stopped
                (= seen' seen) (recur seen' (into es new-es) depth)

                :else (recur seen' (into es new-es) (inc d))))))))))

(defn ^:export form-view
  "One form's page model, addressed by form ID — ids are stable across
  edits and names are not, so the id is the permalink.

  Built for COLD arrival from a link (Debugger Canvas called the failure
  the \"lonely bubble\"): the breadcrumb says where this is, `:callers` and
  `:callees` are backlink CARDS grouped by the `:via` that found each edge,
  and both carry their own signature and doc INLINED rather than linked —
  Code Bubbles measured two-thirds of its win as concurrent visibility, and
  a link is not visibility. The two directions carry the SAME weight, because
  that argument does not care which way an edge points.

  `view` is the rendering FIDELITY (`:views` names the ones that exist).
  It carries one value on purpose: a labeled notation is a live follow-up,
  and adding the parameter later would mean every permalink already in the
  wild silently meant \"whatever the default became\". An unknown fidelity
  is nil — the same answer as an unknown id — never a quiet downgrade to
  the one that happens to exist.

  `depth` (2 or 3) adds `:graph`, the multi-hop NEIGHBOURHOOD — each node
  once with an edge list, never a tree. It is ADDITIVE: at depth 1 the
  response is what it always was, for the same reason the fidelity parameter
  was introduced rather than defaulted into.

  nil for an unknown id, so a page can 404 instead of rendering blank."
  ([session form-id] (form-view session form-id nil))
  ([session form-id view] (form-view session form-id view 1))
  ([session form-id view depth]
   (let [st (:store @session)
         e  (store/form-by-id st form-id)]
     (when (and e (:name e) (contains? fidelities (or view "clojure")))
       (let [ns-sym  (store/ns-of-form-id st form-id)
             nm      (:name e)
             qsym    (symbol (str ns-sym) (str nm))
             row     (fn [ns- var-]
                       (let [e (store/form-named st ns- var-)]
                         (cond-> {:form   (str (symbol (str ns-) (str var-)))
                                  :ns     (str ns-)
                                  :module (edit.modules/module-of ns-)}
                           ;; ids are the permalink, so every edge on the page
                           ;; is one — a name would break the moment it changes
                           e (assoc :form-id (:id e)))))
             callers (->> (refs/refs-to st qsym)
                          (filter :from-var)
                          (group-by :via)
                          (sort-by (comp str key))
                          (mapv (fn [[via rs]]
                                  (let [by (sort-by (comp str first)
                                                    (group-by (juxt :from-ns :from-var) rs))]
                                    {;; a STRING: that is what the contract says, JSON has no keyword
                                     ;; type, and a consumer generating a client from the schema is
                                     ;; entitled to take it literally
                                     :via   (name via)
                                     :count (count by)
                                     :forms (mapv (fn [[[fns fvar] us]]
                                                    ;; the CARD, same as a
                                                    ;; callee row: inlining
                                                    ;; rather than linking is
                                                    ;; what makes a cold page
                                                    ;; answerable, and that
                                                    ;; argument does not care
                                                    ;; which way the edge points
                                                    (merge (row fns fvar)
                                                           {:calls (count (keep :arity us))}
                                                           (dissoc (json-card
                                                                    (orient/form-card session fns fvar))
                                                                   :form)))
                                                  by)}))))
             callees (->> (refs/refs st)
                          (filter #(= form-id (:from-form %)))
                          (group-by (juxt :to-ns :to-name))
                          (sort-by (comp str first))
                          (mapv (fn [[[tns tnm] us]]
                                  (merge (row tns tnm)
                                         {:via   (name (:via (first us)))
                                          :calls (count (keep :arity us))}
                                         (dissoc (json-card (orient/form-card session tns tnm))
                                                 :form)))))
             graph   (neighbourhood session form-id depth)]
         (cond-> (merge {:form-id form-id
                         :form    (str qsym)
                         :name    (str nm)
                         :ns      (str ns-sym)
                         :module  (edit.modules/module-of ns-sym)
                         :view    (or view "clojure")
                         :views   (vec (sort fidelities))
                         :source  (n/string (:node e))
                         :tokens  (tokens-of (:node e))
                         :callers callers
                         :callees callees
                         ;; the tests that reach it BY NAME, observed runs
                         ;; first — the names say what it is promised to do,
                         ;; and `:via` says how each is known. Capped like
                         ;; every list on a form page; `:count` keeps the rest
                         ;; honest.
                         ;; the DATA it touches: its namespaced keys, linked to
                         ;; the dictionary, and a count of the plain ones
                         :keys    (->> (refs/keyword-refs st)
                                       (filter #(and (= form-id (:from-form %)) (namespace (:kw %))))
                                       (map (fn [r] {:kw (subs (str (:kw r)) 1) :via (name (:via r))}))
                                       distinct (sort-by (juxt :kw :via)) (take 30) vec)
                         :tests   (let [cov (refs/covered-by st (:test-map @session) qsym)]
                                    {:count (count cov)
                                     :shown (->> cov
                                                 (sort-by (fn [{:keys [via hops test]}]
                                                            [(if (contains? via :observed) 0 1)
                                                             (or hops 0) (str test)]))
                                                 (take 8)
                                                 (mapv (fn [{:keys [test via hops]}]
                                                         (cond-> {:test (str test)
                                                                  :via  (vec (sort (map name via)))}
                                                           hops (assoc :hops hops)))))})
                         :note    (str "edges come from a syntactic reader over the store, so this"
                                       " is a floor, not a census — a call reached through a"
                                       " binding or built at runtime is not here")}
                        (dissoc (json-card (orient/form-card session ns-sym nm)) :form))
           graph (assoc :graph graph)))))))

(defn gaps-by-ns
  "`{ns-sym {:forms :no-doc :no-why :uncovered}}` over PRODUCTION namespaces —
  the counts a consumer needs to show where a page is about to be thin.

  slopp-ui's framing, and it is the point of the whole thing: a form with no
  recorded why and no test renders identically to one with both, so a diagram
  cannot point at its own weak spots — silence reads the same as coverage.
  Counts rather than rows, so an overlay tints the EXISTING layout instead of
  reflowing it.

  **`:no-doc` is not the `missing-doc-warning` advisory, and the difference is
  deliberate.** That advisory asks *should this be NAGGED* — public module
  surface only, privates exempt, because nagging a private helper is noise.
  This asks *can a reader learn what this is without opening it*, which is
  every named form. Neither is the other's approximation; a consumer tinting by
  the advisory's number would draw a namespace of undocumented privates as
  fully documented.

  **`:no-why` is the WRITE PROMPT** (`prompt-by-form`) — the ask that produced
  the form, which is the thing a file-based codebase cannot show at all. A form
  with a doc and no why says what it does and not why it exists.

  **`:uncovered` inverts the trace map ONCE.** Asking per form, the way a form
  card does, is `forms × tests` set lookups — ~1.8M on this store. The union of
  covered keys is one pass and then a lookup per form. `tmap` is session state
  (`:test-map`), so a caller without one passes nil and `:uncovered` equals
  `:forms` — the honest answer for a process that has run nothing, rather than
  a zero that reads as coverage."
  [store tmap]
  (let [prompts (store/prompt-by-form store)
        covered (into #{} (mapcat val) tmap)]
    (into {}
          (for [n (keys (:namespaces store))
                :when (not (store.render/test-ns? n))]
            [n (reduce (fn [acc e]
                         (cond-> (update acc :forms inc)
                           (nil? (store/form-docstring (:node e))) (update :no-doc inc)
                           (nil? (get prompts (:id e)))            (update :no-why inc)
                           (not (some covered (store/form-trace-keys n e)))
                           (update :uncovered inc)))
                       {:forms 0 :no-doc 0 :no-why 0 :uncovered 0}
                       (filter :name (store/forms store n)))]))))

(defn module-index
  "The Code landing model: the architecture as FACTS a consumer can draw.

  Test namespaces are COUNTED, never listed. A `-test` namespace folds into
  its subject's module, so listing it puts two things at the same rung that
  are not peers — and on slopp's own store that means 103 of 186 rows are
  tests. The names are reachable from the namespace they cover, which is
  where 'what tests this?' actually gets asked.

  The count is by REACH, not by folding. Folding alone reported `slopp.git`
  as having no tests: its three test namespaces are top-level
  (`slopp.git-projection-test`), so they fold into modules of their own and
  none folds into `slopp.git`. A zero here is meant to be a FINDING, and one
  wrong zero devalues every other zero on the screen — so a test namespace
  counts for every module it requires into, as well as the one it folds into.

  **No picture.** This used to assemble one — placed boxes, routed edges, a
  canvas extent — on the reasoning that the layering comes from the store and
  the client should not analyse. The first half is right and the conclusion
  was wrong: LAYERING is analysis, PLACEMENT is drawing, and shipping
  coordinates meant the only consumer that could ever exist was one that
  wanted this exact diagram. It showed up the moment the UI became its own
  project — a layout namespace ported across, tests and all, with nothing
  left for it to do.

  So what crosses is `:layers` (a topological fact, and only the store can
  compute it) and each row's `:deps` (without which a consumer cannot draw an
  edge at all — their absence is precisely why the picture had to be built
  here). Where the boxes go is the consumer's business.

  `:deps` is the FOUNDATION-FREE manifest, matching `:layers`: an edge into
  the substrate is not drawn, and a consumer should not have to re-derive
  which those are when `:foundation` already says so."
  [session]
  (let [st         (:store @session)
        nses       (sort (keys (:namespaces st)))
        test?      #(str/ends-with? (str %) "-test")
        prod       (remove test? nses)
        by-module  (group-by edit.modules/module-of prod)
        home       (into {} (map (juxt identity edit.modules/module-of)) prod)
        ;; a test counts for every module it reaches into, plus its own
        reach      (fn [t] (conj (set (keep home (store/ns-requires st t)))
                                 (edit.modules/module-of t)))
        test-tally (frequencies (mapcat reach (filter test? nses)))
        tiers      (:module-tiers st)
        ;; summed over the module's OWN namespaces, which is exactly the list
        ;; each row carries — so a reader can check the rollup against the
        ;; rows below it rather than taking it on faith
        gaps       (gaps-by-ns st (:test-map @session))
        roll       (fn [ms] (reduce (fn [a n] (merge-with + a (get gaps n)))
                                    {:forms 0 :no-doc 0 :no-why 0 :uncovered 0}
                                    ms))
        manifest   (read.modules/production-manifest st)
        band       (read.modules/substrate manifest)
        ;; layer the graph WITHOUT the foundation: leaving it in stretches
        ;; every module above it a rung further from what it actually needs.
        reduced    (into {} (for [[m ds] manifest :when (not (band m))]
                              [m (vec (remove band ds))]))
        {:keys [layers cycles]} (store/module-layers reduced)
        ;; the DECLARED architecture beside the actual one — a reflexion model.
        ;; `manifest` is what production code uses; `declared` is what the store
        ;; says it may; an edge only tests use is the fourth, separate answer
        declared   (edit.modules/modules-manifest st)
        tests-only (set (read.modules/overstated-edges st))
        mods       (set (keys by-module))
        conform    (vec (for [m (sort mods)
                              d (sort (into (set (get manifest m)) (get declared m)))
                              :when (and (not= m d) (mods d))
                              :let [used? (contains? (get manifest m #{}) d)
                                    decl? (contains? (get declared m #{}) d)]]
                          {:from m :to d
                           :class (cond (and used? decl?)      "convergent"
                                        used?                  "divergent"
                                        (tests-only [m d])     "test-only"
                                        :else                  "absent")}))]
    {:modules (mapv (fn [m]
                      {:module     m
                       :namespaces (mapv str (sort (get by-module m)))
                       :tests      (get test-tally m 0)
                       :tier       (name (get tiers m :external))
                       :foundation (contains? band m)
                       :deps       (vec (sort (get reduced m)))
                       :gaps       (roll (get by-module m))
                       :declared   (vec (sort (get declared m #{})))})
                    (sort (keys by-module)))
     :layers  (mapv vec layers)
     :cycles  (mapv vec cycles)
     :conformance {:edges conform}}))

(def ^:export search-limits
  "`GET /api/search`'s row budget: the `:default` when a caller sends no
  `limit`, and the `:max` a larger one is clamped to.

  Data rather than two numbers spelled into a docstring, because slopp-ui's
  screen says \"showing 20 of 340\" and that sentence goes false the moment a
  silently-applied ceiling disagrees with the number the consumer thinks it
  asked for. The endpoint clamps from here and the contract quotes from here.

  Neither bound is a judgement about search quality — `:total` and `:totals`
  are counted BEFORE the cut, so a reader is never told a smaller number than
  matched."
  {:default 50 :max 200})

(defn ^:export search
  "`GET /api/search` — everything in the store whose NAME, DOCSTRING, recorded
  WHY or SOURCE contains `q`, ranked, sorted, and counted per kind.

  The door to the reader API. Every other read here answers a question you
  already know how to ask — this is the one that finds the address, and
  without it `/store` opens on a module diagram with no way in.

  **Rank is ONE scale across all three kinds**, not per-kind normalised, so a
  consumer can render a single ranked list of typed rows and have it mean
  something: an exactly-named module really does beat a form matched on a word
  in its docstring. The ladder is name-exact 1.0, name-prefix 0.9,
  name-substring 0.8, doc 0.5, why 0.4, source 0.2 — the field that earned the
  rank is the field a reader would have searched for, in that order.

  **`:matched` is not decoration.** A hit whose name says nothing about the
  query reads as a bug unless the row can say the docstring is what matched.
  Source is the escape hatch and is labelled as such, which is what lets it be
  included at all: unlabelled, a source hit looks like a ranking failure.

  **`:totals` counts per kind BEFORE the limit**, and that is the whole reason
  it exists rather than being left to the consumer: a limited hit list cannot
  know how many modules matched beyond the cut. `:total` is the same number
  summed. Both are honest when `:hits` is shorter than either.

  Sorted here, by rank then name. A consumer re-deriving the sort is a second
  opinion on the one thing it asked this side to own, and it goes stale the
  first time the ladder changes.

  A blank or absent `q` is the EMPTY STATE — same shape, zeroes, no hits —
  rather than an error: the screen is reachable by URL, and a reader who lands
  on it without a query has not done anything wrong."
  [store q limit]
  (let [q*  (str/trim (str q))
        lq  (str/lower-case q*)
        n   (max 1 (min (:max search-limits) (or limit (:default search-limits))))
        zero {:query q* :total 0 :totals {:modules 0 :namespaces 0 :forms 0} :hits []}]
    (if (str/blank? q*)
      zero
      (let [sentence (fn [d] (when d
                               (let [t (str/trim (first (str/split d #"(?<=\.)\s")))]
                                 (when-not (str/blank? t) t))))
            nm-rank  (fn [nm] (let [ln (str/lower-case (str nm))]
                                (cond (= ln lq)                1.0
                                      (str/starts-with? ln lq) 0.9
                                      (str/includes? ln lq)    0.8)))
            ;; a form is graded on its BARE name as well as its qualified one.
            ;; Graded only on `ns/name`, an exact match is unreachable for
            ;; EVERY form in the store — a reader typing "invoice" means the
            ;; form called invoice — while the qualified spelling stays
            ;; gradeable for the reader who pastes one.
            grade    (fn [names doc why src]
                       (let [in   (fn [s] (and s (str/includes? (str/lower-case s) lq)))
                             best (some->> (keep nm-rank names) seq (apply max))]
                         (cond
                           best     [best "name"]
                           (in doc) [0.5 "doc"]
                           (in why) [0.4 "why"]
                           (in src) [0.2 "source"]
                           :else    nil)))
            row      (fn [base nm doc why src]
                       (let [names (if (coll? nm) nm [nm])]
                         (when-let [[rank matched] (grade names doc why src)]
                           (cond-> (assoc base :name (str (last names))
                                          :matched matched :rank rank)
                             (sentence doc) (assoc :doc (sentence doc))
                             why            (assoc :why why)))))
            nses     (sort (keys (:namespaces store)))
            prompts  (store/prompt-by-form store)
            ;; modules come from PRODUCTION namespaces only: `module-of` takes
            ;; the first two segments, so a `-test` sibling would mint a module
            ;; nothing declares and nobody can descend into
            mods     (sort (distinct (for [ns-sym nses
                                           :when  (not (str/ends-with? (str ns-sym) "-test"))]
                                       (edit.modules/module-of ns-sym))))
            ns-doc   (fn [ns-sym]
                       (some #(when (= (str (:name %)) (str ns-sym))
                                (store/form-docstring (:node %)))
                             (store/forms store ns-sym)))
            hits     (concat
                      (keep #(row {:kind "module"} % nil nil nil) mods)
                      (keep #(row {:kind "namespace" :module (edit.modules/module-of %)
                                   :ns (str %)}
                                  % (ns-doc %) nil nil)
                            nses)
                      (for [ns-sym nses
                            e      (store/forms store ns-sym)
                            ;; the ns form is already a namespace hit; listing
                            ;; it again is one subject at two grains in one list
                            :when  (and (:name e) (not= (str (:name e)) (str ns-sym)))
                            :let   [sx  (store/form-sexpr (:node e))
                                    ;; `fn-arglists` is TOTAL — nil for a form
                                    ;; with no arities — so there is no head
                                    ;; guard here on purpose. This form carried
                                    ;; one for about an hour, which made it the
                                    ;; fifth place that knew which heads define
                                    ;; a fn; the knowledge moved to the one
                                    ;; function that has to know.
                                    sig (edit.modules/fn-arglists sx)
                                    r   (row (cond-> {:kind "form"
                                                      :module (edit.modules/module-of ns-sym)
                                                      :ns (str ns-sym)
                                                      :form-id (str (:id e))}
                                               (seq sig) (assoc :sig (mapv pr-str sig)))
                                             [(:name e) (str ns-sym "/" (:name e))]
                                             (store/form-docstring (:node e))
                                             (get prompts (:id e))
                                             (n/string (:node e)))]
                            :when  r]
                        r))
            sorted   (vec (sort-by (juxt (comp - :rank) :name) hits))]
        {:query  q*
         :total  (count sorted)
         :totals (let [f (frequencies (map :kind sorted))]
                   {:modules    (get f "module" 0)
                    :namespaces (get f "namespace" 0)
                    :forms      (get f "form" 0)})
         :hits   (vec (take n sorted))}))))

(defn tests-of
  "The deftests that reach namespaces `nses`, grouped by test namespace —
  `[{:ns :count :names :more}]`, sorted, `:names` capped at eight with `:more`
  saying how many were held back.

  The NAMES are the point. This store writes test names as sentences, so the
  tests reaching a namespace state what it does without opening any code.
  [[tests-covering]] says which namespace to open; this says what is in it.

  ONE hop, from two producers: a static reference written in the deftest's
  body, and the trace map's OBSERVED runs, which reach through intermediaries
  a syntactic reader cannot see. Two static hops is `refs/covered-by`'s
  default and the right reach for ONE form; at namespace grain it takes in
  most of a suite and so distinguishes nothing.

  Always a vector, empty rather than nil when nothing reaches the subject: an
  untested namespace is a finding, and an absent key would render as though
  nobody had asked."
  [store tmap nses]
  (let [cap     8
        targets (into #{} (map str) nses)
        test?   #(str/ends-with? (str %) "-test")
        hits    (into #{}
                      (comp (filter #(and (:from-var %)
                                          (test? (:from-ns %))
                                          (targets (str (:to-ns %)))))
                            (map (juxt (comp str :from-ns) (comp str :from-var))))
                      (concat (refs/refs store) (refs/observed-refs tmap)))]
    (->> (group-by first hits)
         (sort-by key)
         (mapv (fn [[tns rows]]
                 (let [names (vec (sort (map second rows)))]
                   {:ns    tns
                    :count (count names)
                    :names (vec (take cap names))
                    :more  (max 0 (- (count names) cap))}))))))

(defn module-detail
  "One module from the INSIDE: its production namespaces, the ns→ns edges
  among them, the layering those edges imply, and the edges crossing its
  boundary. nil for a module with no production namespaces, so a page can 404
  rather than render an empty frame.

  The level below `module-index`, and it makes the same split one rung down —
  `:layers` is analysis only the store can do, placement is the consumer's.

  Edges come from `module-usage-rows`, THE reference graph, which is the same
  producer `production-manifest` reads. That is the point: the descended view
  and the module view cannot disagree about what an edge is, and a second
  derivation here would be free to drift (the `:sig`-had-three-producers bug,
  one system over).

  An internal edge lands in a namespace's `:deps` and nowhere else. Repeating
  it under `:boundary` would draw every internal arrow twice, and `:boundary`
  answers a different question: which namespaces face OUT, and which of them
  anything outside actually reaches. A module whose `:in` names one namespace
  has a front door; one where `:in` names six does not, and that is a finding
  a reader should be able to see without opening anything."
  [session module]
  (let [st      (:store @session)
gaps    (gaps-by-ns st (:test-map @session))
        module  (str module)
        test?   #(str/ends-with? (str %) "-test")
        member? #(and (not (test? %)) (= module (edit.modules/module-of %)))
        members (into (sorted-set) (filter member?) (keys (:namespaces st)))]
    (when (seq members)
      (let [edges  (into #{} (comp (remove #(test? (:from-ns %)))
                                   (map (juxt :from-ns :to))
                                   (remove (fn [[f t]] (= f t))))
                         (edit.modules/module-usage-rows st))
            inside (filter (fn [[f t]] (and (members f) (members t))) edges)
            out    (sort-by (juxt :from :to)
                            (for [[f t] edges :when (and (members f) (not (members t)))]
                              {:from (str f) :to (str t)
                               :to-module (edit.modules/module-of t)}))
            in     (sort-by (juxt :from :to)
                            (for [[f t] edges :when (and (not (members f)) (members t))]
                              {:from (str f) :from-module (edit.modules/module-of f)
                               :to (str t)}))
            by-ns  (reduce (fn [m [f t]] (update m f (fnil conj #{}) t))
                           (into {} (map (juxt identity (constantly #{}))) members)
                           inside)
            {:keys [layers cycles]}
            (store/module-layers
             (into {} (map (fn [[k v]] [(str k) (into #{} (map str) v)])) by-ns))]
        {:module     module
         :tier       (name (tiers/tier-for st (symbol module)))
         :namespaces (vec (for [n members]
                            {:ns    (str n)
                             :forms (count (store/forms st n))
                             :tier  (name (tiers/tier-for st n))
                             :deps  (vec (sort (map str (get by-ns n))))
                             ;; so the descend can tint a namespace without an
                             ;; /api/ns/:ns per box — the N+1 this level exists
                             ;; to avoid at module grain, avoided here too
                             :gaps  (get gaps n)}))
         :boundary   {:out (vec out) :in (vec in)}
         ;; what the module's tests SAY it does, over every member at once —
         ;; the one view of a module that needs no code read at all
         :tests      (tests-of st (:test-map @session) members)
         :layers     (mapv vec layers)
         :cycles     (mapv vec cycles)}))))

(defn story
  "A namespace's or a module's STORY: its commit points newest first, each
  with the asks that shaped it — [[slopp.read.history/story-rows]] over the
  store's log, paged twenty at a time with `:more` counting the rest, plus
  `:working` for what touched it since the last commit point.

  `store` must carry its history (`slopp.ops/with-history` hydrates it; this
  namespace is pure and does not). `grain` is \"ns\" or \"module\"; anything
  else is nil so the endpoint can 404. A module's story is its PRODUCTION
  namespaces': a test folds into its module for navigation, but what was
  asked of a test is not the module's story."
  [store grain subject page]
  (let [subject (str subject)
        pred    (case (str grain)
                  "ns"     #(= subject (str %))
                  "module" #(and (some? %)
                                 (not (str/ends-with? (str %) "-test"))
                                 (= subject (edit.modules/module-of (symbol (str %)))))
                  nil)]
    (when pred
      (let [size 20
            page (max 0 (or page 0))
            {:keys [rows working]} (history/story-rows (store/deltas store) pred)]
        (cond-> {:grain   (str grain)
                 :subject subject
                 :page    page
                 :rows    (vec (take size (drop (* page size) rows)))
                 :more    (max 0 (- (count rows) (* (inc page) size)))}
          working (assoc :working working))))))

(defn entry-points
  "Every DOOR into the store, grouped by kind — what the code can be asked to
  do, which is the first question about a system and the one a file tree
  cannot answer. `{:kinds [{:kind :note :entries [{:kind :label :handler
  :module :form-id}]}] :unreadable}`.

  Kinds, in door order: `main` (the process entry, `main` as the caller read
  it from the `app.main` capability), `http` (every declared route — the typed
  REST contracts when no HTTP section is enabled), `cli` (commands), `webapp`
  (browser screens) and `entry-point` (forms marked `^:entry-point`: called
  from outside the code, by name). A kind with no doors is absent. Each entry
  carries the form it opens on, so a reader goes from what the system can do
  to what happens then in one step.

  Derived from `query-surface`, the store's own declaration of what it
  exposes, so a declaration that cannot be read is listed under
  `:unreadable` rather than passed over as though it did not exist."
  [session main]
  (let [st      (:store @session)
        surf    (query/query-surface session)
        form-of (fn [q] (store/form-named st (symbol (namespace q)) (symbol (name q))))
        door    (fn [kind label handler]
                  (let [h (some-> handler str symbol)]
                    (when (and h (namespace h))
                      (cond-> {:kind    kind
                               :label   (str label)
                               :handler (str h)
                               :module  (edit.modules/module-of (symbol (namespace h)))}
                        (form-of h) (assoc :form-id (:id (form-of h)))))))
        kinds   [["main" "the process entry — what runs when the app starts"
                  (when main [(door "main" "app.main" main)])]
                 ["http" "HTTP routes — each request enters the code here"
                  (for [r (or (:http surf) (:rest surf)) :when (:handler r)]
                    (door "http" (str (str/upper-case (name (or (:method r) :get))) " " (:path r))
                          (:handler r)))]
                 ["cli" "commands"
                  (for [r (:cli surf) :when (:handler r)]
                    (door "cli" (:command r) (:handler r)))]
                 ["webapp" "browser screens — each address renders here"
                  (for [r (:webapp surf) :when (:screen r)]
                    (door "webapp" (:path r) (:screen r)))]
                 ["entry-point" "marked ^:entry-point — called from outside the code, by name"
                  (for [r (refs/refs st)
                        :when (and (= :declared (:via r)) (= :entry-point (:marker r)))]
                    (door "entry-point" (str (:to-ns r) "/" (:to-name r))
                          (symbol (str (:to-ns r)) (str (:to-name r)))))]]]
    {:kinds      (vec (for [[k note es] kinds
                            :let [es (vec (sort-by :label (distinct (remove nil? es))))]
                            :when (seq es)]
                        {:kind k :note note :entries es}))
     :unreadable (mapv str (:unreadable surf))}))

(defn- sequence-doc
  "A call trace as a document a screen can draw: the root, one LANE per module
  in first-appearance order, and the steps with every symbol made text and
  every callee addressed by its form id. Shared by the trace and the path so
  the two draw the same way."
  [st root trace truncated note]
  (let [form-id (fn [q] (:id (store/form-named st (symbol (namespace q)) (symbol (name q)))))
        mod     (fn [q] (edit.modules/module-of (symbol (namespace q))))
        steps   (mapv (fn [{:keys [i depth from to via cycle? seen-at deeper? more]}]
                        (cond-> {:i i :depth depth :from (str from) :from-module (mod from)}
                          to                (assoc :to (str to) :to-module (mod to))
                          (and to (form-id to)) (assoc :to-form-id (form-id to))
                          via               (assoc :via (name via))
                          cycle?            (assoc :cycle? true)
                          seen-at           (assoc :seen-at seen-at)
                          deeper?           (assoc :deeper? true)
                          more              (assoc :more more)))
                      trace)]
    {:root      (cond-> {:form (str root) :module (mod root)}
                  (form-id root) (assoc :form-id (form-id root)))
     :lifelines (vec (distinct (cons (mod root) (keep :to-module steps))))
     :steps     steps
     :truncated truncated
     :note      note}))

(defn sequence-view
  "What happens when the form `form-id` runs, as far as the code SAYS — its
  calls in the order they are written, depth first
  ([[slopp.read.graph/call-sequence]]), as a document one lane per module.
  `depth` (1–8, default 4) and `steps` (1–500, default 200) bound it, and
  `:truncated` says which bound was reached. nil for an unknown form."
  ([session form-id] (sequence-view session form-id {}))
  ([session form-id {:keys [depth steps]}]
   (let [st (:store @session)
         e  (store/form-by-id st (str form-id))]
     (when (and e (:name e))
       (let [root (symbol (str (store/ns-of-form-id st (str form-id))) (str (:name e)))
             {trace :steps :keys [truncated]}
             (graph/call-sequence st root
                                  :depth (min 8 (max 1 (or depth 4)))
                                  :steps (min 500 (max 1 (or steps 200))))]
         (sequence-doc st root trace truncated
                       (str "the calls in the order they are WRITTEN, depth first — a static"
                            " reading of the code, not a recording of a run: a branch not"
                            " taken reads the same as one taken")))))))

(defn path-view
  "The shortest CALL PATH from `from` to `to`, drawn as a sequence document —
  each end a form id or a qualified `ns/name`, since a reader knows names and a
  link carries ids. Each hop carries the reference graph's own `:via` for that
  edge. No path is an answer — empty steps and a note saying so — and an
  unknown end is nil."
  [session from to]
  (let [st      (:store @session)
        named   (fn [x]
                  (let [x (str x)]
                    (if-let [e (store/form-by-id st x)]
                      (when (:name e)
                        (symbol (str (store/ns-of-form-id st x)) (str (:name e))))
                      (when (str/includes? x "/")
                        (let [q (symbol x)]
                          (when (store/form-named st (symbol (namespace q)) (symbol (name q)))
                            q))))))
        a       (named from)
        b       (named to)]
    (when (and a b)
      (let [path  (:path (graph/call-path st a b))
            via   (fn [x y]
                    (or (some #(when (and (= (namespace x) (str (:from-ns %)))
                                          (= (name x) (str (:from-var %))))
                                 (:via %))
                              (refs/refs-to st y))
                        :static))
            trace (vec (map-indexed (fn [i [x y]] {:i i :depth (inc i) :from x :to y :via (via x y)})
                                    (partition 2 1 path)))]
        (sequence-doc st a trace {:depth false :steps false}
                      (if path
                        (str "the shortest call path between the two, each hop as the code"
                             " writes it — static, like every edge here")
                        (str "no call path — " b " is not reachable from " a
                             " through calls the code writes")))))))

(defn size-by-ns
  "Namespace → how much code it holds, as the node count of its forms' sexprs —
  the same measure a form row's `:mass` is, summed. Nodes rather than lines,
  so a long docstring does not outweigh the body it describes."
  [store]
  (into {}
        (for [n (keys (:namespaces store))]
          [n (reduce + 0 (for [e (store/forms store n)
                               :let [s (store/form-sexpr (:node e))]
                               :when (some? s)]
                           (count (tree-seq coll? seq s))))])))

(defn effects-by-ns
  "Namespace → how many of its forms perform effects, as the effect analysis
  derives them — what the code DOES, where a tier says what it may."
  [store]
  (into {} (for [n (keys (:namespaces store))]
             [n (count (query/ns-effectful-vars store n))])))

(defn churn-by-ns
  "Namespace → how many DISTINCT forms changed since the `n`th-last commit
  point — where the work has been lately. Distinct forms rather than writes,
  so a form rewritten ten times counts once. `store` must carry its history;
  with fewer than `n` commit points the whole history counts."
  [store n]
  (let [ds      (vec (store/deltas store))
        commits (keep-indexed (fn [i d] (when (= :commit (:op d)) i)) ds)
        from    (if (>= (count commits) n) (inc (nth (reverse commits) (dec n))) 0)]
    (->> (subvec ds from)
         (filter #(and (history/content-ops (:op %)) (:ns %)))
         (mapcat (fn [d] (for [f (remove nil? (cons (:form-id d) (:form-ids d)))]
                           [(symbol (str (:ns d))) f])))
         distinct
         (map first)
         frequencies)))

(def dials
  "The OVERLAY dials — what the Code map can be tinted by — each with the kind
  of number it is and the sentence that says what the tint means. A share is
  read as value over forms, a count is ranked within the store; the note says
  which, because a colour with no sentence behind it is a guess."
  {"size"     {:kind "count" :label "size"
               :note (str "how much code, as the node count of its forms — ranked within"
                          " this store, so the darkest is the largest HERE, not large in general")}
   "effects"  {:kind "share" :label "effects"
               :note (str "the share of forms that perform effects — the imperative shell"
                          " reads dark and a pure core reads pale")}
   "warranty" {:kind "share" :label "unwarranted"
               :note (str "the share of forms no test has been OBSERVED running — measured"
                          " against this session's trace, so a process that has run little"
                          " reads dark everywhere")}
   "churn"    {:kind "count" :label "churn"
               :note (str "distinct forms changed across the last five commit points — where"
                          " the work has been; ranked within this store")}
   "risk"     {:kind "share" :label "risk"
               :note (str "the share of forms the review scan flags — untested, unused, high"
                          " blast radius, large, lint, undocumented or effectful")}})

(defn overlay-doc
  "One DIAL's tint facts for the Code map: `numerators` (namespace → n) over
  each namespace's named forms, per namespace and rolled up per module —
  `{:dial :kind :label :note :namespaces [{:ns :module :value :of}] :modules
  [{:module :value :of}]}`. Test namespaces are left out: a test is not the
  code being measured. nil for a dial [[dials]] does not name."
  [store dial numerators]
  (when-let [{:keys [kind label note]} (get dials (str dial))]
    (let [test? #(str/ends-with? (str %) "-test")
          rows  (vec (for [n (sort (remove test? (keys (:namespaces store))))]
                       {:ns     (str n)
                        :module (edit.modules/module-of n)
                        :value  (get numerators n 0)
                        :of     (count (filter :name (store/forms store n)))}))]
      {:dial       (str dial)
       :kind       kind
       :label      label
       :note       note
       :namespaces rows
       :modules    (vec (for [[m rs] (sort-by key (group-by :module rows))]
                          {:module m
                           :value  (reduce + 0 (map :value rs))
                           :of     (reduce + 0 (map :of rs))}))})))

(defn data-index
  "The DATA DICTIONARY: the keys the code passes around, ranked by how far each
  travels — modules, then namespaces, then forms — `{:keys [{:kw :modules
  :namespaces :forms :destructured}] :total :shown}`.

  A Clojure system's architecture is largely its map keys, and no file tree
  shows them. Namespaced keys only unless `bare?`: plain ones are dominated by
  schema and option vocabulary (`:map`, `:doc`) that says nothing about the
  domain. `prefix` filters by substring of the name; `limit` (1–500, 100)
  caps what is shown and `:total` says what was held back. `:destructured`
  counts the forms that destructure the key — the ones that demonstrably READ
  it; a mention (`:via :literal`) is a token, which may be a write, a read or
  a lookup key, and is not claimed to be any of them. Test namespaces are left
  out: a test is not the code being described."
  [store {:keys [prefix bare? limit]}]
  (let [limit (min 500 (max 1 (or limit 100)))
        test? #(str/ends-with? (str %) "-test")
        rows  (->> (refs/keyword-refs store)
                   (remove #(test? (:from-ns %)))
                   (filter #(or bare? (namespace (:kw %)))))
        all   (for [[kw rs] (group-by :kw rows)
                    :let [s (subs (str kw) 1)]
                    :when (or (str/blank? prefix) (str/includes? s prefix))]
                {:kw           s
                 :modules      (count (distinct (map #(edit.modules/module-of (:from-ns %)) rs)))
                 :namespaces   (count (distinct (map :from-ns rs)))
                 :forms        (count (distinct (map :from-form rs)))
                 :destructured (count (distinct (map :from-form (filter #(= :destructuring (:via %)) rs))))})
        ranked (sort-by (juxt (comp - :modules) (comp - :namespaces) (comp - :forms) :kw) all)]
    {:keys  (vec (take limit ranked))
     :total (count ranked)
     :shown (min limit (count ranked))}))

(defn key-view
  "One KEY and every production form that touches it, by module, each saying
  HOW — naming it (`literal`) or destructuring it — `{:kw :modules [{:module
  :forms [{:form :form-id :via}]}] :tests}`. Test namespaces are counted in
  `:tests` rather than listed. `kw` is the key's name without its colon;
  nil when no form touches it."
  [store kw]
  (let [k     (keyword (str/replace (str kw) #"^:" ""))
        test? #(str/ends-with? (str %) "-test")
        rs    (filter #(= k (:kw %)) (refs/keyword-refs store))
        prod  (remove #(test? (:from-ns %)) rs)]
    (when (seq rs)
      {:kw      (subs (str k) 1)
       :modules (vec (for [[m ms] (sort-by key (group-by #(edit.modules/module-of (:from-ns %)) prod))]
                       {:module m
                        :forms  (vec (for [[[fid fns fvar] us] (sort-by (fn [[[_ n v] _]] (str n "/" v))
                                                                       (group-by (juxt :from-form :from-ns :from-var) ms))]
                                       {:form    (str fns "/" fvar)
                                        :form-id fid
                                        :via     (vec (sort (distinct (map (comp name :via) us))))}))}))
       :tests   (count (distinct (map :from-ns (filter #(test? (:from-ns %)) rs))))})))
