(ns slopp-server.ui.graph
  "Turns a module manifest into a picture worth looking at.

  The Code view's job is to help someone understand a system they did not
  build, and most systems are not tidy. So everything here is COMPUTED from
  the graph rather than configured: which modules are foundation, which are
  entangled, what collapses when there are too many nodes to draw. A store
  with cycles and one god-module must get an honest picture, not a hairball
  with an apology.

  Pure and :cljc by design — `GET /api/modules` hands over the expensive half
  (SCC-condensed topological layers, computed where the store is), this adds
  the judgement and the geometry, and `slopp-server.ui.views` renders the result as
  hiccup SVG. The layers arrive over the WIRE: the pages reach a project
  only through its API and must not reach `slopp.store` — which is why the
  laid-out `:picture` was taken OUT of the API: layering is analysis and only
  the store can do it, placement is drawing and only a consumer should.
  Keeping the whole chain data-in/data-out is what lets the diagram be an
  ordinary in-image test instead of a screenshot."
  (:require [clojure.set :as set]))

(defn substrate
  "The modules to draw as a FOUNDATION BAND rather than as nodes with edges.

  The band exists to stop drawing edges that carry no information: \"everything
  rests on the store\" reads better as position than as eight arrows. Two ways
  in, both computed, so this generalises to a store nothing like slopp's own:

  - a SINK (nothing in the graph it depends on) that at least TWO modules use.
    One dependent is not enough — that single edge is informative, and banding
    it would move the module away from its only consumer.
  - a HUB whose own dependencies are all banded sinks and whose fan-in reaches
    a quarter of the graph (minimum 3). That is what catches a `store`-shaped
    module: everyone calls it, it calls almost nothing.

  Promotion is ONE level deep on purpose. Cascading walks up the graph and
  swallows real components — on slopp's own manifest an unbounded rule reaches
  `git` and `image`, which are foundation by no reading.

  A graph with no sinks (everything mutually entangled) yields the empty set
  and every edge gets drawn. That degradation is correct: there is no
  foundation to name, and saying so is the honest picture."
  [manifest]
  (let [nodes     (set (keys manifest))
        deps-of   (fn [m] (filter nodes (get manifest m)))
        sinks     (into #{} (filter #(empty? (deps-of %))) nodes)
        fan-in    (frequencies (mapcat deps-of nodes))
        banded    (into #{} (filter #(and (sinks %) (<= 2 (get fan-in % 0)))) nodes)
        threshold (max 3 (quot (+ (count nodes) 3) 4))
        hub?      (fn [m] (and (not (sinks m))
                               (<= threshold (get fan-in m 0))
                               (every? banded (deps-of m))))]
    (set/union banded (into #{} (filter hub?) nodes))))

(def geometry
  "Drawing constants, in user units. One map so the view and the tests agree
   on the canvas without either hardcoding numbers the other cannot see."
  {:node-w 168 :node-h 54 :gap-x 28 :gap-y 46 :band-h 56 :pad 24})

(defn crossings
  "How many pairs of edges cross, for `layers` under `manifest`.

  Two edges between the same pair of layers cross exactly when their endpoints
  are in opposite orders — no geometry needed, which is why this is a property
  of the ORDERING rather than of the drawing. That makes \"is this layout
  readable\" a number a test can assert on, instead of something only a
  screenshot can tell you.

  Public because it is the measurement [[order-layers]] exists to move, and a
  reordering whose effect nothing reports is a reordering nobody can defend."
  [layers manifest]
  (let [nodes    (set (apply concat layers))
        idx      (into {} (for [l layers, [i m] (map-indexed vector l)] [m i]))
        layer-of (into {} (for [[li l] (map-indexed vector layers), m l] [m li]))
        edges    (vec (for [l layers, m l, d (get manifest m) :when (nodes d)]
                        [m d]))]
    (count (for [i (range (count edges))
                 j (range (inc i) (count edges))
                 :let [[u1 v1] (edges i)
                       [u2 v2] (edges j)]
                 :when (and (= (layer-of u1) (layer-of u2))
                            (= (layer-of v1) (layer-of v2))
                            (neg? (* (- (idx u1) (idx u2))
                                     (- (idx v1) (idx v2)))))]
             1))))

(defn expand
  "`layers` and `manifest` with every layer-SKIPPING edge split into per-layer
  segments through waypoints.

  The step that was missing, and the reason the two things built on top of it
  did not work. [[order-layers]] compares adjacent layers only, so an edge
  spanning three of them was invisible to the one function whose job is to
  stop edges crossing — measured on this project's own store, ordering moved
  the crossing count not at all, because every crossing in it was a skip. And
  [[positions]] gave a skip edge no column of its own, so it had to be bent
  around whole rows instead of routed between their boxes.

  A waypoint is `{:edge [from to] :layer i}` — a map, so it is distinguishable
  from a module (a string) by `map?`, and hashable, so it can be a key
  everywhere a module is. It joins its layer as an ordinary member with zero
  width: ordering sorts it into place like anything else, and placement gives
  it a lane one gap wide.

  Returns `{:layers :manifest :routes}`. `:routes` is `{[from to] [waypoint …]}`
  ordered from the SOURCE downward, so a caller reads it as a path; adjacent
  edges are absent from it rather than present-and-empty, because \"this edge
  needed no routing\" and \"this edge's route is empty\" would then look the
  same. `:manifest` contains only adjacent-layer edges, which is the invariant
  everything downstream now gets to assume.

  Deterministic: waypoints are generated over sorted module names, so the
  initial layer membership does not depend on hash order. That matters because
  ordering only breaks ties by existing position."
  [layers manifest]
  (let [layers   (mapv vec layers)
        nodes    (set (apply concat layers))
        layer-of (into {} (for [[i l] (map-indexed vector layers), n l] [n i]))
        skips    (vec (distinct (for [m (sort (keys manifest))
                                      :when (nodes m)
                                      d (get manifest m)
                                      :when (and (nodes d)
                                                 (< 1 (- (layer-of m) (layer-of d))))]
                                  [m d])))
        routes   (into {} (for [[m d :as e] skips]
                            [e (mapv (fn [li] {:edge e :layer li})
                                     (range (dec (layer-of m)) (layer-of d) -1))]))
        by-layer (group-by :layer (mapcat routes skips))
        hop      (fn [m d] (if-let [r (routes [m d])] (first r) d))
        adjacent (into {} (for [[m ds] manifest :when (nodes m)]
                            [m (mapv #(hop m %) (filter nodes ds))]))
        chained  (into {} (for [[[_ d] r] routes
                                [i w] (map-indexed vector r)]
                            [w [(get r (inc i) d)]]))]
    {:layers   (mapv (fn [i l] (into l (get by-layer i []))) (range) layers)
     :manifest (merge adjacent chained)
     :routes   routes}))

(defn order-layers
  "`layers` reordered WITHIN each layer so fewer edges cross.

  The ordering step of a layered drawing, which was missing entirely: layers
  were placed in whatever order they arrived and edges were left to sweep
  wherever that put them. The `:bow` in [[diagram]] is a patch over the same
  wound — bending an edge around a box it should not have been aimed at.

  The barycentre heuristic, swept both ways: a module wants to sit above the
  average position of what it depends on, and below the average position of
  what depends on it. Those pull against each other, so the passes alternate
  and repeat; four rounds is where movement stops on graphs this size.

  **A module with no neighbours in the reference layer keeps its own index**
  rather than sorting to the front. Falling back to zero would collect every
  unconnected module on the left edge, which is a worse picture than the one
  this replaces — and `slopp-server.ui.styles` in this very store is exactly that
  module.

  Deterministic: the sort is stable, ties keep their previous order, and the
  pass count is fixed. An identical store draws identically, which is the
  property that keeps a re-render from making the diagram shimmer."
  [layers manifest]
  (let [layers (mapv vec layers)
        nodes  (set (apply concat layers))
        deps   (fn [m] (filter nodes (get manifest m)))
        above  (reduce (fn [acc m]
                         (reduce (fn [a d] (update a d (fnil conj []) m)) acc (deps m)))
                       {} nodes)
        index  (fn [layer] (into {} (map-indexed (fn [i m] [m i])) layer))
        sweep  (fn [layer neighbours ref]
                 (let [own (index layer)]
                   (vec (sort-by (fn [m]
                                   (let [ns (keep ref (neighbours m))]
                                     (if (seq ns)
                                       (/ (double (reduce + ns)) (count ns))
                                       (double (own m)))))
                                 layer))))
        down   (fn [ls] (reduce (fn [acc i]
                                  (assoc acc i (sweep (nth acc i) deps
                                                      (index (nth acc (dec i))))))
                                ls (range 1 (count ls))))
        up     (fn [ls] (reduce (fn [acc i]
                                  (assoc acc i (sweep (nth acc i)
                                                      #(get above % [])
                                                      (index (nth acc (inc i))))))
                                ls (reverse (range 0 (max 0 (dec (count ls)))))))]
    (reduce (fn [ls _] (-> ls down up)) layers (range 4))))

(defn positions
  "Place `:layers` and the foundation `:band` on a canvas.

  Layer 0 goes at the BOTTOM and later layers stack upward, so an edge from a
  module to something it depends on points DOWN — the layer-cake reading.
  (This inverts the order layers arrive in, and the order architecture.md's
  table lists them; reading order and visual foundation disagree, and the
  picture should follow the picture.)

  A layer member is either a MODULE or a WAYPOINT from [[expand]], and the
  only difference here is width: a waypoint is zero-wide, so it consumes one
  gap rather than a box. That is what gives a long edge a lane between the
  boxes of the row it crosses, instead of a bend around the outside of it.
  Everything else — the shared baseline, the height, the centring — is the
  same, because a waypoint IS an ordinary member of its layer as far as
  ordering and spacing are concerned.

  The band is one strip beneath layer 0. It carries no edges by construction:
  that is the whole point of computing `substrate` — position says 'everything
  rests on these' more legibly than sixteen arrows do.

  Returns {:nodes [{:module :layer :x :y :w :h}] :band [{:module :x :y :w :h}]
  :width :height}. Coordinates only — no colours, no labels, no strokes; the
  view owns all of that."
  [{:keys [layers band]}]
  (let [{:keys [node-w node-h gap-x gap-y band-h pad]} geometry
        depth   (count layers)
        w-of    (fn [m] (if (map? m) 0 node-w))
        row-w   (fn [ms] (+ (reduce + 0 (map w-of ms))
                            (* (max 0 (dec (count ms))) gap-x)))
        widest  (reduce max 0 (map row-w layers))
        width   (+ (* 2 pad) (max widest (row-w (vec band))))
        height  (+ (* 2 pad) (* depth node-h) (* (max 0 (dec depth)) gap-y)
                   (if (seq band) (+ gap-y band-h) 0))
        row-y   (fn [i] (+ pad (* (- depth 1 i) (+ node-h gap-y))))
        place   (fn [ms y h]
                  (first (reduce (fn [[acc x] m]
                                   [(conj acc {:module m :x x :y y :w (w-of m) :h h})
                                    (+ x (w-of m) gap-x)])
                                 [[] (quot (- width (row-w ms)) 2)]
                                 ms)))]
    {:nodes  (vec (mapcat (fn [i ms]
                            (map #(assoc % :layer i) (place ms (row-y i) node-h)))
                          (range) layers))
     :band   (vec (place (vec band) (- height pad band-h) band-h))
     :width  width
     :height height}))

(defn straighten
  "`placed` with every node nudged toward the x its neighbours suggest.

  The third missing step of a layered drawing, and the one that shows up
  before any crossing does. [[positions]] centres each row in the canvas
  independently, so on this project's own store a two-box row sat above a
  six-box row and EVERY edge left at an angle — not because anything crossed,
  but because the two rows had been centred against each other rather than
  against their edges.

  The rule is the standard one: a node wants to sit at the average x of what
  it depends on, and at the average x of what depends on it. Those pull
  against each other, so passes alternate down and up and repeat. A node with
  no neighbours in the reference row keeps its column, which is what leaves an
  unconnected module where the row put it instead of dragging it to the left
  edge.

  Order and spacing are INVARIANTS, not preferences: within a row the packing
  pass takes each node's wish and gives it the closest x that still clears its
  left neighbour, so ordering's work is never undone by straightening's. Since
  a left-to-right pack can only ever push right, the whole row is then shifted
  back by the mean it drifted — otherwise every row would creep rightward one
  sweep at a time.

  Waypoints are zero-width and take part like anything else. That is what
  straightens a long edge: its waypoints are pulled into line with its two
  ends, so the route becomes a near-vertical lane rather than a stagger.

  Recomputes `:width` from what was actually placed, and re-centres the band
  in it — the band has no edges to be straightened by, so centring stays the
  right answer for it."
  [placed manifest]
  (let [{:keys [gap-x pad]} geometry
        rows0 (into (sorted-map)
                    (map (fn [[li ns]] [li (vec (sort-by :x ns))]))
                    (group-by :layer (:nodes placed)))
        depth (inc (reduce max -1 (keys rows0)))
        above (reduce (fn [acc [m ds]]
                        (reduce (fn [a d] (update a d (fnil conj []) m)) acc ds))
                      {} manifest)
        ctr   (fn [n] (+ (:x n) (quot (:w n) 2)))
        mean  (fn [xs] (/ (double (reduce + 0 xs)) (count xs)))
        pack  (fn [row want]
                (if (empty? row)
                  []
                  (let [seps  (mapv (fn [a b] (+ (quot (+ (:w a) (:w b)) 2) gap-x))
                                    row (rest row))
                        fixed (reduce (fn [acc i]
                                        (conj acc (max (nth want i)
                                                       (+ (peek acc) (nth seps (dec i))))))
                                      [(first want)] (range 1 (count want)))]
                    (mapv #(+ % (long (- (mean want) (mean fixed)))) fixed))))
        sweep (fn [rows li ref-li nbrs]
                (let [row  (get rows li [])
                      ref  (into {} (map (juxt :module ctr)) (get rows ref-li []))
                      want (mapv (fn [n]
                                   (let [cs (keep ref (nbrs (:module n)))]
                                     (if (seq cs)
                                       (quot (reduce + cs) (count cs))
                                       (ctr n))))
                                 row)]
                  (assoc rows li (mapv (fn [n c] (assoc n :x (- c (quot (:w n) 2))))
                                       row (pack row want)))))
        down  (fn [rows] (reduce (fn [rs li] (sweep rs li (dec li) #(get manifest % [])))
                                 rows (range 1 depth)))
        up    (fn [rows] (reduce (fn [rs li] (sweep rs li (inc li) #(get above % [])))
                                 rows (range (- depth 2) -1 -1)))
        final (reduce (fn [rs _] (-> rs down up)) rows0 (range 3))
        nodes (vec (mapcat val final))
        all   (concat nodes (:band placed))]
    (if (empty? all)
      placed
      (let [dx    (- pad (reduce min (map :x all)))
            moved (mapv #(update % :x + dx) nodes)
            b0    (mapv #(update % :x + dx) (:band placed))
            width (+ pad (reduce max 0 (map #(+ (:x %) (:w %)) (concat moved b0))))
            band  (if (empty? b0)
                    b0
                    (let [lo (reduce min (map :x b0))
                          hi (reduce max (map #(+ (:x %) (:w %)) b0))
                          bx (- (quot (- width (- hi lo)) 2) lo)]
                      (mapv #(update % :x + bx) b0)))]
        (assoc placed :nodes moved :band band :width width)))))

(defn diagram
  "The whole picture as data: placed nodes, the foundation band, and the edges
  actually worth drawing.

  Four steps, in this order, and each of the last three depends on the one
  before it:

  1. [[expand]] — every layer-skipping edge becomes a chain of adjacent-layer
     segments through waypoints.
  2. [[order-layers]] — barycentre ordering, which now SEES those skips
     because they are ordinary layer members. Without step 1 it did not:
     measured on this project's own store, ordering changed the crossing count
     from 1 to 1, since every crossing in it was a skip.
  3. [[positions]] — boxes get a column, waypoints get a zero-width lane.
  4. [[straighten]] — nodes are pulled toward their neighbours' x instead of
     each row being centred against nothing.

  An edge into the band is DROPPED — that is what `substrate` bought, and on
  slopp's own manifest it removes 16 of 33 arrows. Parallel edges out of one
  module FAN across its underside instead of stacking on one anchor, and
  arrivals spread the same way, so a heavily-depended-on node does not collect
  every arrow at one pixel.

  **An edge is a POLYLINE — `:points`, at least two `[x y]` pairs.** It used
  to be one cubic with a `:bow`: both control points shoved sideways until the
  curve cleared the row it crossed. That cleared the boxes and cost the
  reading, because an edge which leaves the picture on the left and returns on
  the right says nothing about layering — which is what \"very swirly\" meant,
  and it was the second complaint about the same line. Now the route goes
  BETWEEN the boxes, through the lane its waypoint was given.

  `:x1 :y1 :x2 :y2` are the first and last of `:points`, for a reader who only
  wants the ends. They are the same numbers, computed once.

  An edge whose target is not placed at all (band member, or absent from the
  manifest) is silently omitted rather than drawn to nowhere.

  Still only coordinates — colour, stroke, arrowheads and labels are the
  view's.

  The band is placed from a SORTED set for the same reason ordering exists at
  all: a set's iteration order is unspecified, so passing it straight through
  would let the foundation strip rearrange between renders of one store."
  [{:keys [manifest layers band]}]
  (let [band     (set band)
        nodes    (set (apply concat layers))
        drawable (into {} (for [m nodes]
                            [m (vec (remove #(or (band %) (not (nodes %)))
                                            (get manifest m)))]))
        ex       (expand layers drawable)
        placed   (straighten (positions {:layers (order-layers (:layers ex)
                                                               (:manifest ex))
                                         :band   (vec (sort band))})
                             (:manifest ex))
        by-mod   (into {} (map (juxt :module identity)) (:nodes placed))
;; Where an edge HEADS on leaving, and where it comes FROM on arriving.
        ;; Both are a node in the ADJACENT row — the target itself, or the
        ;; waypoint standing in for it — so the two are always comparable.
        heads-at (fn [m d] (let [r (get (:routes ex) [m d])]
                             (:x (by-mod (if (seq r) (first r) d)))))
        comes-at (fn [m d] (let [r (get (:routes ex) [m d])]
                             (:x (by-mod (if (seq r) (peek r) m)))))
        raw      (vec (for [m     (sort (keys drawable))
                            :when (by-mod m)
                            :let  [outs (vec (sort-by #(heads-at m %) (get drawable m)))]
                            [i d] (map-indexed vector outs)]
                        {:from m :to d :out i :fan (count outs)}))
        ;; arrival slot per target, assigned by grouping the finished list —
        ;; deterministic, and pure where an accumulator would not have been.
        arrival  (into {} (for [[d es] (group-by :to raw)]
                            [d (into {} (map-indexed (fn [j e] [(:from e) j]))
                                     (sort-by #(comes-at (:from %) d) es))]))
        inbound  (frequencies (map :to raw))
        ;; n targets -> n evenly-spaced points, each centred in its own slice
        anchor   (fn [{:keys [x w]} i n] (+ x (quot (* w (inc (* 2 i))) (* 2 n))))
        waypoint (fn [w]
                   ;; TWO points, top and bottom of the row. The crossing is
                   ;; then strictly vertical and all the sideways travel
                   ;; happens in the GAPS between rows, where there is nothing
                   ;; to hit. A single mid-row point leaves the segment
                   ;; entering diagonally, so an edge whose source sits far
                   ;; from its lane would still sweep through the boxes on the
                   ;; way in — the bug this replaced, at half the amplitude.
                   (let [n (by-mod w)]
                     [[(:x n) (:y n)] [(:x n) (+ (:y n) (:h n))]]))]
    (assoc placed
           :nodes (vec (remove #(map? (:module %)) (:nodes placed)))
           :edges (mapv (fn [{:keys [from to out fan]}]
                          (let [f      (by-mod from)
                                t      (by-mod to)
                                head   [(anchor f out fan) (+ (:y f) (:h f))]
                                tail   [(anchor t (get-in arrival [to from])
                                                (get inbound to 1))
                                        (:y t)]
                                points (-> [head]
                                           (into (mapcat waypoint (get (:routes ex) [from to])))
                                           (conj tail))]
                            {:from from :to to :points points
                             :x1 (first head) :y1 (second head)
                             :x2 (first tail) :y2 (second tail)}))
                        raw))))

(defn picture-of
  "The drawable picture for a `GET /api/modules` response.

  The namespace's entry point, and the reason it has a caller. The API sends
  FACTS — one row per module with its own `:deps` and `:foundation`, plus the
  `:layers` only the store can compute — and turning those into boxes and
  routed edges is this project's job. It used to be the producer's, which
  meant the diagram slopp drew was the only diagram anyone could have.

  Takes the response WHOLE rather than a manifest, a layer list and a band.
  Those three are all derivable from it, and derivable exactly one way; three
  arguments would be three chances for a caller to disagree about what
  `:foundation` means, in the one namespace where a mistake shows up as a
  picture that looks plausible."
  [{:keys [modules layers]}]
  (diagram {:manifest (into {} (map (juxt :module :deps)) modules)
            :layers   layers
            :band     (into #{} (comp (filter :foundation) (map :module)) modules)}))

(defn reading-order
  "The modules of a `GET /api/modules` response, ordered the way the picture
  stacks — foundation band first, then each layer from the base upward.

  The table lens is a one-dimensional layout of the graph [[picture-of]] draws
  in two, so it derives its order from the same two facts, here, rather than in
  the view. Two arrangements of one graph agreeing about what comes first is
  not something to leave to a coincidence of sort keys.

  Rows come back WHOLE. A caller handed names would have to index back into
  `:modules` to say anything about them, which is the lookup this returns
  already done.

  **Within a layer the API's own order is kept.** `order-layers` reorders a
  layer to cut edge crossings, and that position means nothing off the canvas —
  copying it here would import a drawing artifact into a table as if it were a
  fact about the code.

  A module in neither the band nor any layer goes last. The picture can leave
  such a module undrawn; a table cannot leave it out, because a table is read
  as the whole population and a missing row is a census that lies."
  [{:keys [modules layers]}]
  (let [by-module (into {} (map (juxt :module identity)) modules)
        band      (sort (into [] (comp (filter :foundation) (map :module)) modules))
        placed    (into (vec band) cat layers)
        seen      (set placed)]
    (into (into [] (keep by-module) placed)
          (remove #(seen (:module %)))
          modules)))

(defn picture-within
  "The drawable picture for a `GET /api/module/:m` response — the namespaces
  INSIDE one module, and how they depend on each other.

  Sibling to [[picture-of]], and deliberately the only other entry point.
  [[diagram]] takes `{node → deps}` and has no opinion about whether a node is
  a module or a namespace, so descending a level costs an adapter from a second
  wire shape rather than a second layout engine. That is the argument for
  splitting the geometry out of the API in the first place, arriving one rung
  later than expected.

  **The band is COMPUTED here, not read off the response.** At module grain the
  API sends `:foundation` because only the store can compute a store-wide
  substrate; within one module there is no such flag and there should not be —
  which namespaces read as foundation is a property of the picture being drawn,
  and [[substrate]] already answers it from the manifest alone. A module whose
  namespaces are mutually entangled yields the empty set and every edge gets
  drawn, which is the honest picture of a module with no foundation.

  Layers arrive from the API carrying every namespace, so banded ones are
  removed here — [[diagram]] draws a box for everything in `:layers` and would
  otherwise place a namespace twice, once in a row and once in the strip. A
  layer emptied by banding is dropped rather than left as a blank row."
  [{:keys [namespaces layers]}]
  (let [manifest (into {} (map (juxt :ns :deps)) namespaces)
        band     (substrate manifest)]
    (diagram {:manifest manifest
              :band     band
              :layers   (into [] (comp (map #(vec (remove band %))) (remove empty?))
                              layers)})))
