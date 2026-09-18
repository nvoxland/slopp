(ns slopp-server.ui.callgraph
  "Judgement about a call neighbourhood: what to show first, and which single
  path through it to draw.

  The store owns the GRAPH — which form calls which, and how confidently. This
  namespace owns the QUESTIONS a reader asks of it: *which of these forty
  callers do I read first*, and *how does control get from there to here*. That
  line matters, because getting it wrong in either direction is a real cost.
  Pull the graph over the wire and index it here, and this project is
  reimplementing slopp's reference index badly, over HTTP. Push the ranking
  into the API, and every consumer inherits one opinion about importance that
  it cannot see or change.

  So: the API sends a bounded neighbourhood — nodes once, edges with `:via` —
  and everything here is a pure function of it. That is what lets every screen
  built on it be an ordinary in-image assertion instead of a screenshot.

  Two shapes, because developers ask two shapes of question. [[ego]] answers
  *what is around this* — one hop, both directions, ranked. [[spine]] answers
  *how does control get from here to here* — one path, arbitrary depth, with
  the alternatives at each rung counted rather than drawn. Neither subsumes the
  other: the first spends its space on breadth, the second on depth, and the
  question a reader has decides which is useless."
  (:require [slopp-server.ui.importance :as importance]))

(defn neighbours
  "Direct callers and callees of `focus` in neighbourhood `nbhd`, each node
  merged with the facts of the edge that found it (`:via`, `:calls`).

  **Both sides come back the same shape**, which is the point. The wire sends
  callees with their card and callers as bare names; a view that renders
  callers above and callees below wants to give them equal weight, and the
  asymmetry was a wire bug (since fixed on the wire), not a fact about
  callers. Merging here means the views never learn which side was thin.

  An edge whose far end is not among `:nodes` is DROPPED. That is not
  defensiveness — it is the depth boundary: at `?depth=2` the response
  legitimately carries edges pointing one hop further out than the nodes it
  sends. Rendering a name with no card would be worse than not rendering it,
  and `spine` reads the same absence as \"the walk stops here\"."
  [{:keys [nodes edges]} focus]
  (let [by-id (into {} (map (juxt :form-id identity)) nodes)
        side  (fn [near far]
                (into [] (comp (filter #(= focus (near %)))
                               (keep (fn [e]
                                       (when-let [n (by-id (far e))]
                                         (assoc n :via (:via e) :calls (:calls e))))))
                      edges))]
    {:callers (side :to :from)
     :callees (side :from :to)}))

(def confidence
  "Reference kinds, most trustworthy first — slopp's `:via`, as an ORDER.

  A static call is a fact the compiler could have told you. A `carrier` is a
  quoted symbol in a position known to be called. `declared` is an author's
  claim. `observed` is \"it happened once while something was watching\".

  Kept as a vector rather than a map of scores so the ordering is the
  definition and nothing has to agree about magnitudes. An unrecognised kind
  sorts after every known one: a `:via` this project has not heard of is
  exactly the case where it should not be asserting confidence."
  ["static" "carrier" "declared" "observed"])

(defn- flow
  "Adjacency pointing AWAY from the focus: for `:callers`, each node to its own
  callers; for `:callees`, each node to its own callees.

  Seeded with every node as a key, because [[slopp-server.ui.importance/reach]] drops
  edges to names its map does not hold — which is the behaviour wanted at the
  depth boundary and the wrong behaviour for a node that simply has no edges."
  [{:keys [nodes edges]} direction]
  (let [[k v] (if (= :callers direction) [:to :from] [:from :to])]
    (reduce (fn [g e] (update g (k e) (fnil conj []) (v e)))
            (into {} (map (juxt :form-id (constantly []))) nodes)
            edges)))

(defn rank
  "Order `rows` — one side of [[neighbours]] — most worth reading first.

  Four keys, and the first one is the argument:

  1. **`:via` confidence.** An `observed` edge sinks below a `static` one even
     when it carries more. Ordering by importance across confidence levels
     would silently mix \"the compiler knows this\" with \"we saw it happen
     once\", which is the one thing slopp's own anti-goals say never to do with
     the reference graph. Weak edges stay in the list, badged — sunk, not
     hidden.
  2. **Reach away from the focus.** For a caller, how much transitively
     reaches IT — two paths converging on one caller make it more of the story
     than a caller nothing feeds. For a callee, how much it drags in behind it:
     what you take on by following.
  3. **Call sites.** Called twice from here is more entangled than once.
  4. **`[ns form]`.** Less a tiebreak than a guarantee: the same neighbourhood
     must render the same way every time, or a permalink is a lie.

  Reach is computed once per row rather than inside the comparator, because
  `sort-by` calls its key function per comparison and a hot form at depth 3
  brings hundreds of nodes."
  [nbhd direction rows]
  (let [g       (flow nbhd direction)
        conf    (into {} (map-indexed (fn [i v] [v i])) confidence)
        reach-n (into {} (map (fn [r] [(:form-id r) (count (importance/reach g (:form-id r)))])) rows)
        key-of  (fn [r] [(get conf (:via r) (count confidence))
                         (- (reach-n (:form-id r) 0))
                         (- (or (:calls r) 0))
                         (or (:ns r) "")
                         (or (:form r) "")])]
    (vec (sort-by key-of rows))))

(defn ego
  "The one-hop neighbourhood of `focus`, ranked and capped:
  `{:callers {:shown [row] :more n} :callees {…}}`.

  **`:more` is never dropped.** A caller list that truncates in silence lets a
  reader conclude a function has no other callers, which is the specific way a
  comprehension tool produces a confident false belief — worse than showing
  nothing, because the reader does not know to go looking.

  The cap defaults to 6. Above roughly that, a neighbour list stops carrying
  signal: the one study that measured a call-hierarchy tool against no tool at
  all found it changed nothing, and the models that predict where a developer
  goes next flatten out once a node has more than about six neighbours, because
  an unranked list ranks nothing. Six is not a magic number, it is the point
  past which the list is doing the reader's sorting for them badly. Ranking is
  what buys the right to show even that many.

  Both sides always come back as vectors, empty rather than nil. A view should
  render \"nothing calls this\" as a sentence, and it cannot tell the
  difference between absent and empty if this hands back nil."
  ([nbhd focus] (ego nbhd focus 6))
  ([nbhd focus cap]
   (let [{:keys [callers callees]} (neighbours nbhd focus)
         side (fn [direction rows]
                (let [ranked (rank nbhd direction rows)]
                  {:shown (vec (take cap ranked))
                   :more  (max 0 (- (count ranked) cap))}))]
     {:callers (side :callers callers)
      :callees (side :callees callees)})))

(defn spine
  "One whole path through `nbhd` containing `focus`: root caller at the top,
  leaf callee at the bottom. `prefer` is a set of form-ids the walk takes when
  they are available, which is how a reader swaps one rung for another.

  ```
  {:rungs [row…]   ; each a node row + :via :alternatives :choice
   :focus  i       ; where `focus` sits in :rungs
   :cycle? bool    ; the walk stopped because it came back on itself
   :weakest via}   ; the least trustworthy edge on the path, or nil
  ```

  **Why a path and not a tree.** A tree spends its space on breadth AND depth
  and bounds neither, so a frequently-called helper drags its whole subtree in
  at every occurrence — one measured example went from 8 rows to 97. This
  spends everything on depth and shows ONE option per position, with the others
  counted. That makes it the only shape here whose size does not move when a
  form has two hundred callers: it renders `1 of 200` and stays one line.

  **A rung's `:via` is the call it makes to the rung BELOW it.** So the list
  reads top-to-bottom as a chain of calls, and the last rung has none.

  **`:weakest` is the honest headline.** A chain that is `static` end to end is
  a claim you can lean on; one `observed` rung makes the whole path a weaker
  claim, and a reader who is not told that will lean on it anyway. slopp's own
  rule is never to present the reference graph as complete — for a multi-hop
  view, this is what keeping that promise looks like.

  **`prefer` is a SET rather than a position→choice map** because positions
  move: reroute through a caller with a shorter chain above it and every index
  below shifts. A form-id still means the same thing after the path changes
  shape, so a swap survives the swap that follows it.

  Both walks share ONE visited set, so a form appears at most once. Without
  that, `a` and `b` calling each other renders as `b/a/b` and a reader reads a
  loop as a chain."
  ([nbhd focus] (spine nbhd focus #{}))
  ([nbhd focus prefer]
   (let [via-of (into {} (map (juxt (juxt :from :to) :via)) (:edges nbhd))
         conf   (into {} (map-indexed (fn [i v] [v i])) confidence)
         step   (fn [direction at seen]
                  (let [open (remove #(seen (:form-id %))
                                     (rank nbhd direction (direction (neighbours nbhd at))))]
                    ;; a preference only ever REORDERS what was already reachable —
                    ;; it can never put a form on a path that does not call through
                    (or (first (filter #(prefer (:form-id %)) open))
                        (first open))))
         walk   (fn [direction seen0]
                  (loop [at focus, seen seen0, acc [], cycled? false]
                    (let [opts (rank nbhd direction (direction (neighbours nbhd at)))
                          nxt  (step direction at seen)]
                      (cond
                        nxt        (recur (:form-id nxt) (conj seen (:form-id nxt))
                                          (conj acc (:form-id nxt)) cycled?)
                        (seq opts) [acc seen true]     ; options existed, all visited
                        :else      [acc seen cycled?]))))
         [up   seen-up   up-cycle]   (walk :callers #{focus})
         [down _         down-cycle] (walk :callees seen-up)
         by-id (into {} (map (juxt :form-id identity)) (:nodes nbhd))
         ids   (vec (concat (reverse up) [focus] down))
         i     (count up)
         rung  (fn [p id]
                 (let [[direction anchor] (cond (< p i) [:callers (nth ids (inc p))]
                                                (> p i) [:callees (nth ids (dec p))]
                                                :else   [nil nil])
                       ;; the focus is its own only option — a view then renders
                       ;; every rung the same way instead of special-casing the
                       ;; middle of the path
                       opts (if direction
                              (rank nbhd direction (direction (neighbours nbhd anchor)))
                              [(by-id id)])]
                   (assoc (by-id id)
                          :via          (via-of [id (get ids (inc p))])
                          :options      (mapv #(select-keys % [:form-id :form :ns :module :via])
                                              opts)
                          :alternatives (count opts)
                          :choice       (or (first (keep-indexed
                                                    #(when (= id (:form-id %2)) %1) opts))
                                            0))))
         rungs (vec (map-indexed rung ids))]
     {:rungs   rungs
      :focus   i
      :cycle?  (boolean (or up-cycle down-cycle))
      :weakest (->> rungs (keep :via) (sort-by #(conf % (count confidence))) last)})))

(defn from-form-view
  "The neighbourhood implied by a depth-1 `GET /api/form/:id` response.

  Two shapes for one fact, and this is the seam between them. The wire groups
  callers by the `:via` that found them and sends callees flat; [[ego]] and
  [[spine]] read nodes-once-plus-edges. Adapting here rather than in a view
  means the one-hop screens ship on the endpoint that exists today, and the day
  `?depth=N` lands they read the richer response with nothing above this line
  changing.

  **A caller group's `:via` becomes a property of the EDGE, not of the caller.**
  It always was: `:via` says how the reference was found, and the same function
  can call another statically in one place and through a quoted symbol in
  another. Grouping was the wire's compression, not a fact about the caller.

  **Both sides carry the whole card, and this paragraph used to say they did
  not.** The wire's caller/callee asymmetry was real, was reported to slopp
  as a reader-API gap, and slopp fixed it at `d23203`. Measured on the live
  listener 2026-08-06 rather than assumed:

      GET /api/form/f13093?depth=2
        caller row keys: [calls doc form form-id module ns sig warranty why]
        callee row keys: [calls doc form form-id module ns sig via warranty why]

  So a view may give the two sides equal weight, which is what [[neighbours]]
  says it wants. The dead sentence is worth its space: it cited a log entry
  that had already closed, and it was still shaping what the rail rendered."
  [{:keys [form-id name ns module sig doc why warranty callers callees graph]}]
  (let [focus  {:form-id form-id :form name :ns ns :module module
                :sig sig :doc doc :why why :warranty warranty}
        in     (for [g callers, f (:forms g)] (assoc f :via (:via g)))
        out    (vec callees)
        ;; the one-hop rows carry the full card; a graph node is lean. Index
        ;; the cards so a graph node can be enriched by the row describing the
        ;; same form — depth from the graph, detail from the lists, one fetch.
        cards  (into {} (map (juxt :form-id identity)) (concat [focus] in out))]
    (if (seq (:nodes graph))
      (cond-> {:focus form-id
               :nodes (mapv #(merge % (cards (:form-id %))) (:nodes graph))
               :edges (vec (:edges graph))}
        (:truncated graph) (assoc :truncated (:truncated graph)))
      {:focus form-id
       :nodes (into [focus] (concat in out))
       :edges (vec (concat (for [c in]  {:from (:form-id c) :to form-id
                                         :via (:via c) :calls (:calls c)})
                           (for [c out] {:from form-id :to (:form-id c)
                                         :via (:via c) :calls (:calls c)})))})))
