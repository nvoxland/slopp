(ns slopp-server.ui.graph-test
  "Tests for the module-graph analysis, built on two rules.

  First, prefer a REAL fixture: slopp's own production manifest is small
  enough to check by hand and is the only graph whose right answer can be
  verified by inspection. A synthetic three-node graph proves a rule holds;
  it does not prove the rule produces a sensible picture.

  Second, the degradation cases carry more weight than the happy path. This
  view ships to stores that are cyclic, lopsided, or far larger than slopp's
  own, and 'what does it do when there is no foundation to name' is the
  question a user actually hits.

  This namespace is :jvm, not :cljc like its subject. One test did NOT come
  across in the split: it derived its layering through slopp's own store API,
  which this app has no access to — the boundary working, not a gap. The
  layout logic is pure and stays covered from a literal manifest below, and
  the client never computes layers anyway: they arrive over the wire.

  The module names in the fixtures below are STRING DATA describing someone
  else's architecture, not references to anything here."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp-server.ui.graph :as graph]))

(deftest substrate-bands-sinks-that-many-modules-depend-on
  (testing "a sink two or more modules depend on is foundation"
    (is (= #{"lib"}
           (graph/substrate {"a" ["lib"] "b" ["lib"] "lib" []}))))
  (testing "a sink only ONE module depends on stays an ordinary node"
    (is (= #{}
           (graph/substrate {"a" ["lib"] "lib" []}))))
  (testing "a depended-on module that itself reaches a non-substrate module is not foundation"
    (is (= #{"lib"}
           (graph/substrate {"a" ["mid" "lib"] "b" ["mid"] "mid" ["lib" "a"] "lib" []})))))

(def ^:private slopp-production
  "A FROZEN SAMPLE of slopp's production module manifest, captured 2026-07-26 — 14 modules, 33 edges, 9 layers, no cycles. Frozen on purpose and it must stay that way: these tests assert that layout behaves sanely on a real, untidy graph, so the value has to be stable or the assertions drift with somebody else's refactor. It is NOT current — slopp has ~50 modules now, and `slopp-server.ui` here was renamed `slopp.review` in D-ui-hub part 5. Do not refresh it; capture a second sample under its own name if you want a bigger one. Small enough to check the answer by hand."
  {"slopp.api"   ["slopp.boot" "slopp.edit" "slopp.image" "slopp.index" "slopp.store" "slopp.web"]
   "slopp.bench" ["slopp.api" "slopp.mcp" "slopp.store"]
   "slopp.boot"  []
   "slopp.cache" []
   "slopp.edit"  ["slopp.cache" "slopp.image" "slopp.index" "slopp.store"]
   "slopp.git"   ["slopp.store"]
   "slopp.image" ["slopp.rt" "slopp.store"]
   "slopp.index" ["slopp.cache" "slopp.image"]
   "slopp.mcp"   ["slopp.api" "slopp.git" "slopp.store" "slopp.sync" "slopp-server.ui" "slopp.web"]
   "slopp.rt"    []
   "slopp.store" ["slopp.cache"]
   "slopp.sync"  ["slopp.api" "slopp.boot" "slopp.git" "slopp.store"]
   "slopp-server.ui"    ["slopp.api" "slopp.edit" "slopp.store" "slopp.web"]
   "slopp.web"   []})

(deftest substrate-on-a-real-manifest-names-the-foundation-and-nothing-else
  (let [band  (graph/substrate slopp-production)
        edges (for [[m ds] slopp-production d ds] [m d])]
    (testing "the foundation is the three widely-used sinks plus the store hub"
      (is (= #{"slopp.boot" "slopp.cache" "slopp.web" "slopp.store"} band)))
    (testing "store is banded despite an outgoing edge — everyone calls it, it calls almost nothing"
      (is (contains? band "slopp.store")))
    (testing "rt is a sink but NOT foundation: its one edge from image is the informative kind"
      (is (not (contains? band "slopp.rt")))
      (is (some (fn [[m d]] (and (= "slopp.image" m) (= "slopp.rt" d))) edges)
          "and that edge therefore survives into the drawn picture"))
    (testing "promotion stops at one level — these are components, not foundation"
      (is (empty? (filter band ["slopp.git" "slopp.image" "slopp.api" "slopp.edit"]))))
    (testing "banding is what makes the picture readable: 16 of 33 edges stop being drawn"
      (is (= 33 (count edges)))
      (is (= 17 (count (remove (fn [[_ d]] (band d)) edges)))))))

(deftest a-fully-entangled-graph-has-no-foundation-to-name
  (testing "no sinks means no band, and every edge gets drawn — the honest picture"
    (is (= #{} (graph/substrate {"a" ["b"] "b" ["c"] "c" ["a"]}))))
  (testing "a single god-module everything calls is still found"
    (is (= #{"god"}
           (graph/substrate {"a" ["god"] "b" ["god"] "c" ["god"] "god" []})))))

(deftest positions-stack-layers-bottom-up-with-the-foundation-beneath
  (let [{:keys [nodes band width height]}
        (graph/positions {:layers [["rt" "git"] ["image"] ["index" "edit"]]
                          :band   ["cache" "store"]})
        by-module (into {} (map (juxt :module identity)) nodes)
        y-of      #(:y (by-module %))]
    (testing "every module in every layer is placed, and none is invented"
      (is (= #{"rt" "git" "image" "index" "edit"} (set (map :module nodes)))))
    (testing "layer 0 sits BELOW layer 1, which sits below layer 2 — dependencies downward"
      (is (> (y-of "rt") (y-of "image")))
      (is (> (y-of "image") (y-of "index"))))
    (testing "modules in one layer share a baseline and occupy distinct columns"
      (is (= (y-of "rt") (y-of "git")))
      (is (not= (:x (by-module "rt")) (:x (by-module "git")))))
    (testing "the foundation band is beneath everything else"
      (is (seq band))
      (is (every? (fn [b] (every? #(> (:y b) (:y %)) nodes)) band)))
    (testing "the reported canvas contains everything drawn"
      (is (every? #(<= 0 (:x %)) (concat nodes band)))
      (is (every? #(<= (+ (:x %) (:w %)) width) (concat nodes band)))
      (is (every? #(<= (+ (:y %) (:h %)) height) (concat nodes band))))))

(deftest a-waypoint-is-placed-as-a-lane-not-as-another-box
  (let [w {:edge ["top" "bottom"] :layer 1}
        {:keys [nodes width]} (graph/positions {:layers [["bottom"] ["mid" w] ["top"]]
                                                :band   []})
        by (into {} (map (juxt :module identity)) nodes)]
    (testing "it is placed, so an edge has something to be routed through"
      (is (some? (by w))))
    (testing "with no width: a lane between boxes, not another box"
      (is (zero? (:w (by w))))
      (is (= (:node-w graph/geometry) (:w (by "mid")))))
    (testing "at the height of the row it joined — the edge crosses THERE"
      (is (= (:y (by "mid")) (:y (by w))))
      (is (= (:h (by "mid")) (:h (by w)))))
    (testing "clear of its neighbour, and inside the canvas it reports"
      (is (<= (+ (:x (by "mid")) (:w (by "mid"))) (:x (by w))))
      (is (<= 0 (:x (by w)) width)))))

(deftest edges-into-the-foundation-are-not-drawn
  (let [picture (graph/diagram {:manifest {"app" ["mid" "lib"] "mid" ["lib"] "lib" []}
                                :layers   [["mid"] ["app"]]
                                :band     #{"lib"}})
        drawn   (set (map (juxt :from :to) (:edges picture)))]
    (testing "only the edge between two placed nodes survives"
      (is (= #{["app" "mid"]} drawn)))
    (testing "edges into the band are omitted — position already says it"
      (is (not-any? (fn [[_ to]] (= "lib" to)) drawn)))
    (testing "each drawn edge starts on its source and ends on its target"
      (let [{:keys [nodes edges]} picture
            by-module (into {} (map (juxt :module identity)) nodes)
            e         (first edges)
            from      (by-module (:from e))
            to        (by-module (:to e))]
        (is (= (+ (:y from) (:h from)) (:y1 e)) "leaves the bottom of the source")
        (is (= (:y to) (:y2 e)) "arrives at the top of the target")
        (is (<= (:x from) (:x1 e) (+ (:x from) (:w from))))
        (is (<= (:x to) (:x2 e) (+ (:x to) (:w to))))))))

(deftest parallel-edges-fan-across-a-module-underside
  (testing "two edges out of one module leave from different points, not one pixel"
    (let [{:keys [edges]} (graph/diagram {:manifest {"app" ["a" "b"] "a" [] "b" []}
                                          :layers   [["a" "b"] ["app"]]
                                          :band     #{}})]
      (is (= 2 (count edges)))
      (is (apply not= (map :x1 edges))))))

(deftest edges-that-skip-layers-are-routed-through-the-rows-they-cross
  ;; Found by rendering the real store: slopp.api -> slopp.image was emitted as
  ;; a straight vertical at x=346 spanning three layers, passing through both
  ;; slopp.edit and slopp.index, whose boxes cover x 318-486.
  ;;
  ;; The first answer was to BEND it — one cubic with both control points
  ;; shoved sideways until the curve cleared the row. That trades a line
  ;; through a box for a line around the whole picture, which is what "very
  ;; swirly" meant. An edge is a POLYLINE now: it goes between the boxes.
  (let [picture (graph/diagram
                 {:manifest {"top" ["mid" "bottom"] "mid" ["bottom"] "bottom" []}
                  :layers   [["bottom"] ["mid"] ["top"]]
                  :band     #{}})
        pts     (into {} (map (juxt (juxt :from :to) :points)) (:edges picture))]
    (testing "an adjacent-layer edge is two points — a single segment"
      (is (= 2 (count (pts ["top" "mid"]))))
      (is (= 2 (count (pts ["mid" "bottom"])))))
    (testing "a crossed row costs TWO points, its top and its bottom, because
              the crossing has to be vertical: all the sideways travel belongs
              in the gaps between rows, where there is nothing to hit"
      (is (= 4 (count (pts ["top" "bottom"]))))
      (let [[[_ _] [ax ay] [bx by] [_ _]] (pts ["top" "bottom"])]
        (is (= ax bx) "the two are the same lane")
        (is (< ay by) "and they bracket the row, top then bottom")))
    (testing "one waypoint per layer crossed, so a longer span bends more often
              rather than further"
      (let [deep (graph/diagram
                  {:manifest {"a" ["d"] "b" [] "c" [] "d" []}
                   :layers   [["d"] ["c"] ["b"] ["a"]]
                   :band     #{}})]
        (is (= 6 (count (:points (first (:edges deep))))))))
    (testing "and every route descends: a point is never above the one before it"
      (is (every? (fn [ps] (apply <= (map second ps))) (vals pts))))))

(deftest the-picture-is-derived-from-the-wire-shape-not-received-on-it
  ;; The reason this namespace has a caller at all. /api/modules used to ship
  ;; a laid-out picture — boxes with coordinates, routed edges, a canvas
  ;; extent — so this project ported a layout engine and had nothing for it to
  ;; do. The API says what the modules ARE now, and drawing them is this
  ;; project's job, which is the right way round: a second consumer wanting a
  ;; different diagram gets to have one.
  ;;
  ;; So the entry point takes the RESPONSE SHAPE, not three hand-assembled
  ;; arguments. `client.app` should hand over what it fetched and get back
  ;; something renderable, with no chance to reassemble the inputs wrongly on
  ;; the way.
  (let [response {:modules [{:module "demo.app"  :namespaces ["demo.app.core"]
                             :tests 1 :tier "external" :foundation false
                             :deps ["demo.lib"]}
                            {:module "demo.lib"  :namespaces ["demo.lib.core"]
                             :tests 0 :tier "internal" :foundation false
                             :deps []}
                            {:module "demo.base" :namespaces ["demo.base.core"]
                             :tests 0 :tier "pure" :foundation true
                             :deps []}]
                  :layers [["demo.lib"] ["demo.app"]]
                  :cycles []}
        picture  (graph/picture-of response)]
    (testing "every non-foundation module is placed"
      (is (= #{"demo.app" "demo.lib"} (set (map :module (:nodes picture))))))
    (testing "the foundation goes in the band beneath, not among the nodes —
              `:foundation` on the row is what tells the consumer which"
      (is (= ["demo.base"] (mapv :module (:band picture)))))
    (testing "an edge is drawn from each row's own :deps, which is the field
              that had to be added for a consumer to draw anything at all"
      (is (= [["demo.app" "demo.lib"]]
             (mapv (juxt :from :to) (:edges picture)))))
    (testing "and the canvas has an extent, so a caller can size the svg"
      (is (pos? (:width picture)))
      (is (pos? (:height picture))))))

(deftest ordering-a-layer-cuts-the-crossings-a-naive-order-leaves
  ;; The layout placed each layer in whatever order it arrived — alphabetical,
  ;; as it happens — and nothing minimised crossings. That is the ordering step
  ;; of a layered drawing, and it was simply absent, so edges swept across the
  ;; middle of the picture and over boxes they had no business near.
  (let [;; deliberately adversarial: every edge inverts under this order
        manifest {"a" ["z"] "b" ["y"] "c" ["x"]
                  "x" [] "y" [] "z" []}
        naive    [["x" "y" "z"] ["a" "b" "c"]]
        ordered  (graph/order-layers naive manifest)]
    (testing "the naive order is as bad as it looks — every pair crosses"
      (is (= 3 (graph/crossings naive manifest))))
    (testing "ordering removes them"
      (is (zero? (graph/crossings ordered manifest))))
    (testing "it is a REORDERING, not a rewrite: same layers, same members"
      (is (= (count naive) (count ordered)))
      (is (= (map set naive) (map set ordered))))
    (testing "and it is deterministic — an identical store draws identically,
              which is what stops a re-render making the diagram shimmer"
      (is (= ordered (graph/order-layers naive manifest))))))

(deftest a-skip-edge-passes-BETWEEN-the-boxes-of-the-row-it-crosses
  ;; The complaint that started this: long edges sweeping across the middle of
  ;; the picture and over boxes. Bowing cleared the boxes and cost the reading
  ;; — an edge that leaves the picture on the left and comes back on the right
  ;; tells you nothing about layering, which was the whole complaint the second
  ;; time round. Clearance still has to be real; it is now a lane rather than
  ;; a detour.
  (let [manifest {"top" ["sink"] "mid" [] "other" [] "sink" []}
        d   (graph/diagram {:manifest manifest
                            :layers   [["sink"] ["mid" "other"] ["top"]]
                            :band     #{}})
        by  (into {} (map (juxt :module identity)) (:nodes d))
        e   (first (filter #(= "top" (:from %)) (:edges d)))
        [[_ _] [wx wtop] [_ wbot] [_ _]] (:points e)]
    (testing "the edge spans a layer, so it is routed rather than drawn direct"
      (is (some? e))
      (is (= 4 (count (:points e)))))
    (testing "the lane clears every box in the row it crosses"
      (doseq [m ["mid" "other"]]
        (let [b (by m)]
          (is (or (<= wx (:x b)) (>= wx (+ (:x b) (:w b))))
              (str "lane x " wx " sits inside " m
                   " [" (:x b) " " (+ (:x b) (:w b)) "]")))))
    (testing "and it crosses THAT row exactly — top to bottom, vertically —
              which is what makes it a route through the picture instead of a
              bend around it"
      (let [b (by "mid")]
        (is (= (:y b) wtop))
        (is (= (+ (:y b) (:h b)) wbot))))
    (testing "an adjacent hop crosses nothing and stays a single segment"
      (let [adj (first (filter #(= "mid" (:from %)) (:edges d)))]
        (is (or (nil? adj) (= 2 (count (:points adj)))))))))

(deftest a-skip-edge-becomes-a-chain-of-waypoints-one-per-layer-it-crosses
  ;; The missing half of a layered drawing. Ordering only ever looked at
  ;; ADJACENT layers, so an edge spanning three of them was invisible to the
  ;; thing whose job is to stop edges crossing — and placement had nowhere to
  ;; put it, which is why it was bent around whole rows instead of routed
  ;; between their boxes.
  (let [{:keys [layers manifest routes]}
        (graph/expand [["bottom"] ["mid"] ["top"]]
                      {"top" ["mid" "bottom"] "mid" ["bottom"] "bottom" []})]
    (testing "the skipping edge gets one waypoint, in the layer it crosses"
      (is (= 1 (count (routes ["top" "bottom"])))))
    (testing "adjacent edges get none — they had no problem to solve"
      (is (nil? (routes ["top" "mid"])))
      (is (nil? (routes ["mid" "bottom"]))))
    (testing "the waypoint JOINS the layer it crosses, which is the whole point:
              ordering now sees it, and placement gives it a column"
      (let [w (first (routes ["top" "bottom"]))]
        (is (contains? (set (nth layers 1)) w))
        (is (= 2 (count (nth layers 1))))))
    (testing "real modules are untouched — this adds, it does not move"
      (is (= [["bottom"] ["mid"] ["top"]]
             (mapv (fn [l] (vec (remove map? l))) layers))))
    (testing "no edge in the expanded graph spans more than one layer"
      (let [layer-of (into {} (for [[i l] (map-indexed vector layers), n l] [n i]))]
        (is (every? (fn [[m ds]]
                      (every? #(= 1 (- (layer-of m) (layer-of %))) ds))
                    manifest))))
    (testing "a three-layer span gets two waypoints, one per crossed layer"
      (let [{:keys [routes]} (graph/expand [["d"] ["c"] ["b"] ["a"]]
                                           {"a" ["d"] "b" [] "c" [] "d" []})]
        (is (= 2 (count (routes ["a" "d"]))))
        (is (= [2 1] (mapv :layer (routes ["a" "d"])))
            "ordered from the source downward, so the route reads as a path")))))

(deftest a-chain-that-could-be-drawn-straight-is-drawn-straight
  ;; The third missing step. Each row was centred in the canvas on its own, so
  ;; on this project's store a two-box row sat above a six-box row and EVERY
  ;; edge left at an angle — before any crossing, before any skip. Rows are
  ;; centred as a fallback now; a node with neighbours is pulled toward them.
  (let [manifest {"a" ["b"] "b" ["c"] "c" [] "x" [] "y" [] "z" [] "w" []}
        d   (graph/diagram {:manifest manifest
                            :layers   [["c" "x" "y" "z" "w"] ["b"] ["a"]]
                            :band     #{}})
        by  (into {} (map (juxt :module identity)) (:nodes d))
        ctr (fn [m] (+ (:x (by m)) (quot (:w (by m)) 2)))]
    (testing "a --> b --> c is one vertical line, not a zigzag across the canvas"
      (is (= (ctr "c") (ctr "b")))
      (is (= (ctr "b") (ctr "a"))))
    (testing "the modules with no edges keep their columns — straightening pulls
              on what is connected and leaves the rest where the row put it"
      (is (apply < (map ctr ["c" "x" "y" "z" "w"]))))
    (testing "and everything still fits the canvas it reports"
      (let [{:keys [nodes band width height]} d]
        (is (every? #(<= 0 (:x %)) (concat nodes band)))
        (is (every? #(<= (+ (:x %) (:w %)) width) (concat nodes band)))
        (is (every? #(<= (+ (:y %) (:h %)) height) (concat nodes band)))))))

(deftest no-edge-passes-through-a-box-on-this-project-s-own-manifest
  ;; The picture that was complained about, frozen as a fixture — this
  ;; project's manifest AS CAPTURED 2026-08-02, not as it is now. "Lines that
  ;; cross the boxes" was an impression of a screenshot; here it is a number,
  ;; and the number has to be zero.
  ;;
  ;; The name says "this project's own manifest", which is a present-tense
  ;; claim a frozen fixture cannot keep: nothing here notices when the real
  ;; manifest moves, so this can quietly become a picture of a store that no
  ;; longer exists while staying internally consistent and green. Kept anyway
  ;; — the geometry it pins is what matters and does not depend on the names —
  ;; but the date is the part a reader needs, and it was missing.
  ;;
  ;; A cubic never leaves the rectangle spanned by its four control points, and
  ;; these control points are the two endpoints displaced VERTICALLY only. So
  ;; [min x, max x] x [y1, y2] is exactly where the curve can be, and testing
  ;; that rectangle against the boxes is sound rather than approximate.
  ;;
  ;; Source and target boxes need no special case: a segment starts on the
  ;; source's bottom edge and ends on the target's top edge, so neither
  ;; overlaps in y.
  (let [pic (graph/picture-of
             {:modules [{:module "slopp-server.ui.basepath"  :foundation true  :deps []}
                        {:module "slopp-server.ui.client"    :foundation false
                         :deps ["slopp-server.ui.graph" "slopp-server.ui.spa" "slopp-server.ui.views"]}
                        {:module "slopp-server.ui.contracts" :foundation false :deps []}
                        {:module "slopp-server.ui.graph"     :foundation false :deps []}
                        {:module "slopp-server.ui.hub"       :foundation false
                         :deps ["slopp-server.ui.contracts" "slopp-server.ui.registry" "slopp-server.ui.views"]}
                        {:module "slopp-server.ui.nav"       :foundation false :deps []}
                        {:module "slopp-server.ui.nsfilter"  :foundation false :deps []}
                        {:module "slopp-server.ui.registry"  :foundation false :deps []}
                        {:module "slopp-server.ui.spa"       :foundation false :deps ["slopp-server.ui.nav"]}
                        {:module "slopp-server.ui.styles"    :foundation false :deps []}
                        {:module "slopp-server.ui.views"     :foundation false
                         :deps ["slopp-server.ui.nav" "slopp-server.ui.nsfilter"]}]
              :layers [["slopp-server.ui.contracts" "slopp-server.ui.graph" "slopp-server.ui.nav"
                        "slopp-server.ui.nsfilter" "slopp-server.ui.registry" "slopp-server.ui.styles"]
                       ["slopp-server.ui.spa" "slopp-server.ui.views"]
                       ["slopp-server.ui.client" "slopp-server.ui.hub"]]})
        hits (vec (for [e (:edges pic)
                        [[ax ay] [bx by]] (partition 2 1 (:points e))
                        :let [lo (min ax bx) hi (max ax bx)]
                        b (:nodes pic)
                        :when (and (< lo (+ (:x b) (:w b))) (> hi (:x b))
                                   (< ay (+ (:y b) (:h b))) (> by (:y b)))]
                    [(:from e) "->" (:to e) :through (:module b)]))]
    (testing "not one segment of one edge can reach any box"
      (is (= [] hits)))
    (testing "three of its nine edges skip a layer, so this is not vacuous"
      (is (= 3 (count (filter #(< 2 (count (:points %))) (:edges pic))))))
(testing "and no fan crosses itself: hub's three edges leave in the order
              their targets sit, so the one heading left is the one on the
              left. Naming them alphabetically put views — the leftmost — on
              hub's rightmost anchor, and it crossed both siblings getting out"
      (doseq [[from es] (group-by :from (:edges pic))]
        (is (apply <= (map #(first (second (:points %))) (sort-by :x1 es)))
            (str from "'s fan crosses itself under its own box"))))
    (testing "and ordering leaves nothing crossing either, once the skips are
              visible to it — they were not before, which is why the count it
              reported was 1 while the count actually drawn was 8"
      (let [manifest {"slopp-server.ui.client" ["slopp-server.ui.graph" "slopp-server.ui.spa" "slopp-server.ui.views"]
                      "slopp-server.ui.hub"    ["slopp-server.ui.contracts" "slopp-server.ui.registry" "slopp-server.ui.views"]
                      "slopp-server.ui.spa"    ["slopp-server.ui.nav"]
                      "slopp-server.ui.views"  ["slopp-server.ui.nav" "slopp-server.ui.nsfilter"]
                      "slopp-server.ui.contracts" [] "slopp-server.ui.graph" [] "slopp-server.ui.nav" []
                      "slopp-server.ui.nsfilter" [] "slopp-server.ui.registry" [] "slopp-server.ui.styles" []}
            layers   [["slopp-server.ui.contracts" "slopp-server.ui.graph" "slopp-server.ui.nav"
                       "slopp-server.ui.nsfilter" "slopp-server.ui.registry" "slopp-server.ui.styles"]
                      ["slopp-server.ui.spa" "slopp-server.ui.views"]
                      ["slopp-server.ui.client" "slopp-server.ui.hub"]]
            ex       (graph/expand layers manifest)]
        (is (= 1 (graph/crossings layers manifest))
            "what the old metric could see")
        (is (= 8 (graph/crossings (:layers ex) (:manifest ex)))
            "what was actually on the screen")
        (is (zero? (graph/crossings (graph/order-layers (:layers ex) (:manifest ex))
                                    (:manifest ex))))))))

(deftest a-fan-leaves-in-the-ORDER-ITS-TARGETS-SIT-not-in-name-order
  ;; slopp-server.ui.hub depends on contracts, registry and views. The fan handed out
  ;; anchors in dependency-list order — alphabetical — so `views`, which sits
  ;; furthest LEFT, got the RIGHTMOST anchor and travelled back across hub's
  ;; entire underside, crossing both siblings before it had gone anywhere.
  ;;
  ;; Nothing in the layout was wrong: the boxes were placed well and the rows
  ;; were ordered. The edges were simply attached in an order that has nothing
  ;; to do with geometry, which is a whole class of crossing that ordering
  ;; cannot remove because it is not between layers at all.
  (let [d  (graph/diagram {:manifest {"src" ["z" "a"] "a" [] "z" []}
                           :layers   [["a" "z"] ["src"]]
                           :band     #{}})
        by (into {} (map (juxt :module identity)) (:nodes d))
        x1 (into {} (map (juxt :to :x1)) (:edges d))]
    (testing "anchors run left to right in the same order as their targets do,
              whichever way ordering happened to place them"
      (is (= (< (:x (by "a")) (:x (by "z")))
             (< (x1 "a") (x1 "z")))))
    (testing "arrivals spread the same way, by where the edge comes FROM"
      (let [d2 (graph/diagram {:manifest {"p" ["t"] "q" ["t"] "t" []}
                               :layers   [["t"] ["p" "q"]]
                               :band     #{}})
            by2 (into {} (map (juxt :module identity)) (:nodes d2))
            x2  (into {} (map (juxt :from :x2)) (:edges d2))]
        (is (= (< (:x (by2 "p")) (:x (by2 "q")))
               (< (x2 "p") (x2 "q"))))))))

(deftest the-inside-of-a-module-is-drawn-by-the-same-engine-one-rung-down
  ;; The hierarchy used to stop at modules: /api/modules ships module→module
  ;; deps and nothing says how the eight namespaces inside `slopp.edit` relate
  ;; to each other. Descending is a second WIRE SHAPE, not a second layout
  ;; engine — `diagram` takes `{node → deps}` and does not care whether a node
  ;; is a module or a namespace, which is the whole reason this costs an
  ;; adapter rather than a rewrite.
  (let [response {:module "demo.thing" :tier "pure"
                  :namespaces [{:ns "demo.thing.core"  :forms 12 :tier "pure"
                                :deps ["demo.thing.util" "demo.thing.parse"]}
                               {:ns "demo.thing.parse" :forms 6 :tier "pure"
                                :deps ["demo.thing.util"]}
                               {:ns "demo.thing.web"   :forms 4 :tier "external"
                                :deps ["demo.thing.core"]}
                               {:ns "demo.thing.util"  :forms 3 :tier "pure"
                                :deps []}]
                  :boundary {:out [] :in []}
                  :layers [["demo.thing.util"] ["demo.thing.parse"]
                           ["demo.thing.core"] ["demo.thing.web"]]
                  :cycles []}
        picture  (graph/picture-within response)]
    (testing "the fixture has a shared sink, or the banding assertion is vacuous"
      (is (= 2 (count (filter #(some #{"demo.thing.util"} (:deps %))
                              (:namespaces response))))))
    (testing "a namespace two things rest on goes to the band, same rule as a module"
      (is (= ["demo.thing.util"] (mapv :module (:band picture))))
      (is (= #{"demo.thing.core" "demo.thing.parse" "demo.thing.web"}
             (set (map :module (:nodes picture))))))
    (testing "a layer emptied by banding does not leave a blank row"
      (is (every? seq (partition-by :y (sort-by :y (:nodes picture))))))
    (testing "edges come from each namespace's own :deps, and edges into the
              band are dropped exactly as they are at module grain"
      (is (= #{["demo.thing.core" "demo.thing.parse"] ["demo.thing.web" "demo.thing.core"]}
             (set (map (juxt :from :to) (:edges picture))))))
    (testing "the canvas has an extent, so a caller can size the svg"
      (is (pos? (:width picture)))
      (is (pos? (:height picture))))
    (testing "and the geometry property holds one rung down too: not one
              segment of one edge can reach any box"
      (let [hits (vec (for [e (:edges picture)
                            [[ax ay] [bx by]] (partition 2 1 (:points e))
                            :let [lo (min ax bx) hi (max ax bx)]
                            b (:nodes picture)
                            :when (and (< lo (+ (:x b) (:w b))) (> hi (:x b))
                                       (< ay (+ (:y b) (:h b))) (> by (:y b)))]
                        [(:from e) "->" (:to e) :through (:module b)]))]
        (is (= [] hits))))))

(deftest reading-order-runs-from-the-foundation-upward-and-drops-nobody
  ;; The table lens is a one-dimensional layout of the graph the picture draws
  ;; in two. So it derives its order from the same two facts — the band and the
  ;; layers — in the same namespace. Ordering it in the view instead would give
  ;; a store two views whose only agreement about what comes first is that
  ;; nobody checked.
  (let [response {:modules [{:module "demo.app"    :deps ["demo.lib"] :foundation false
                             :namespaces ["demo.app.core"] :tests 1}
                            {:module "demo.lib"    :deps [] :foundation false
                             :namespaces ["demo.lib.core"] :tests 0}
                            {:module "demo.base"   :deps [] :foundation true
                             :namespaces ["demo.base.core"] :tests 0}
                            {:module "demo.orphan" :deps [] :foundation false
                             :namespaces [] :tests 0}]
                  :layers  [["demo.lib"] ["demo.app"]]
                  :cycles  []}
        order    (graph/reading-order response)]
    (testing "the band first, then layer 0, then layer 1 — `positions` stacks
              the foundation beneath and layer 0 above it, so reading the
              picture upward from its base is this sequence"
      (is (= ["demo.base" "demo.lib" "demo.app" "demo.orphan"]
             (mapv :module order))))
    (testing "a module the layering placed nowhere is still listed, at the end.
              The picture can afford to leave it out of the drawing; a table
              that silently drops a row is a census that lies"
      (is (some #(= "demo.orphan" (:module %)) order)))
    (testing "every module exactly once — this is the whole population, not a
              selection from it"
      (is (= (frequencies (map :module (:modules response)))
             (frequencies (map :module order)))))
    (testing "rows come back WHOLE, so the table reads the wire's own numbers
              rather than being handed names and having to look them up"
      (is (= {:module "demo.app" :deps ["demo.lib"] :foundation false
              :namespaces ["demo.app.core"] :tests 1}
             (nth order 2))))))
