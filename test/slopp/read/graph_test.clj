(ns slopp.read.graph-test
  "Cover for the PURE call-graph walks in `slopp.read.graph` — the path
  between two forms and the reach around one, over store values built in
  the test. The wire shape those walks feed (`query_flow`) is pinned beside
  the other wire ops in `slopp.mcp-test`; this namespace is about the graph
  answers themselves, so a wrong path is caught without a session."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.read.graph :as graph]))

(deftest a-call-path-between-two-forms-crosses-namespaces
  ;; The question behind most whole-namespace reads: how does A reach B?
  ;; Answered over the callee graph, across namespaces, as the path itself.
  (let [st (-> (store/empty-store)
               (store/ingest 'fp.b "(ns fp.b)\n(defn leaf \"L.\" [x] x)\n(defn other \"O.\" [x] x)\n")
               (store/ingest 'fp.a "(ns fp.a (:require [fp.b :as b]))\n(defn mid \"M.\" [x] (b/leaf x))\n(defn entry \"E.\" [x] (mid x))\n(defn aside \"A.\" [x] (b/other x))\n"))]
    (testing "the path, in call order"
      (is (= '[fp.a/entry fp.a/mid fp.b/leaf] (:path (graph/call-path st 'fp.a/entry 'fp.b/leaf))))
      (is (= '[fp.a/mid] (:path (graph/call-path st 'fp.a/mid 'fp.a/mid))) "a form reaches itself"))
    (testing "calls run one way, and a missing target is nil"
      (is (nil? (graph/call-path st 'fp.b/leaf 'fp.a/entry)))
      (is (nil? (graph/call-path st 'fp.a/entry 'fp.b/nope))))
    (testing "the reach around a form: callers AND callees, edges from the caller's side"
      (let [r (graph/call-reach st 'fp.a/mid 1)]
        (is (= #{'fp.a/entry 'fp.a/mid 'fp.b/leaf} (set (:nodes r))) (pr-str r))
        (is (= #{{:from 'fp.a/entry :to 'fp.a/mid} {:from 'fp.a/mid :to 'fp.b/leaf}}
               (set (map #(select-keys % [:from :to]) (:edges r)))) (pr-str r))))
    (testing "reach stops at the depth asked, and an unconnected form never appears"
      (let [r (graph/call-reach st 'fp.a/mid 2)]
        (is (contains? (set (:nodes r)) 'fp.a/entry))
        (is (not (contains? (set (:nodes r)) 'fp.a/aside)) (pr-str r))))))

(deftest callees-arrive-in-body-order
  ;; `callee-adjacency` sorts a form's callees, which is right for a path search
  ;; and wrong for reading: a sequence of calls told alphabetically is not the
  ;; sequence. `zed` is written first and sorts last, so an alphabetical answer
  ;; fails here.
  (let [st (-> (store/empty-store)
               (store/ingest 'fp.b "(ns fp.b)\n\n(defn zed [x] x)\n\n(defn leaf [x] x)\n")
               (store/ingest 'fp.a (str "(ns fp.a (:require [fp.b :as b]))\n\n"
                                        "(defn mid [x] (b/leaf x))\n\n"
                                        "(defn entry [x] (b/zed x) (mid x) (b/leaf x) (b/zed x))\n")))
        oc (graph/ordered-callees st)]
    (is (= '[fp.b/zed fp.a/mid fp.b/leaf] (mapv :to (get oc 'fp.a/entry)))
        "the written order, each callee once at its first call")
    (is (every? #{:static} (map :via (get oc 'fp.a/entry))))
    (is (= '[fp.b/leaf] (mapv :to (get oc 'fp.a/mid))))))

(deftest call-sequence-is-depth-first-bounded-and-honest-about-cycles
  ;; A static trace: what the code SAYS happens when `root` runs, depth first
  ;; in body order. Every place the walk stops descending says why — a cycle,
  ;; a callee already expanded, the depth bound — so a short trace can always
  ;; be told from a cut one.
  (let [st  (-> (store/empty-store)
                (store/ingest 'cs.a (str "(ns cs.a)\n\n"
                                         "(defn leaf [x] x)\n\n"
                                         "(defn d3 [x] (leaf x))\n\n"
                                         "(defn d2 [x] (d3 x))\n\n"
                                         "(defn d1 [x] (d2 x))\n\n"
                                         "(defn helper [x] (leaf x))\n\n"
                                         "(defn again [x] (again (dec x)))\n\n"
                                         "(defn root [x] (helper x) (d1 x) (helper x) (again x) (leaf x))\n")))
        row (fn [s] [(:depth s) (some-> (:to s) name)
                     (cond (:cycle? s) :cycle (:seen-at s) :seen (:deeper? s) :deeper
                           (:more s) :more :else :call)])
        {:keys [steps truncated]} (graph/call-sequence st 'cs.a/root :depth 3)]
    (testing "depth first, callees in body order, each form expanded once"
      (is (= [[1 "helper" :call] [2 "leaf" :call]
              [1 "d1" :call] [2 "d2" :call] [3 "d3" :deeper]
              [1 "again" :call] [2 "again" :cycle]
              [1 "leaf" :seen]]
             (mapv row steps)))
      (is (= (range (count steps)) (map :i steps)) "steps are numbered in order"))
    (testing "a callee drawn before points back at the step that drew it"
      (is (= 1 (:seen-at (last steps)))))
    (testing "the depth bound is REPORTED, not silent"
      (is (= {:depth true :steps false} truncated)))
    (testing "so is the step bound"
      (let [r (graph/call-sequence st 'cs.a/root :depth 3 :steps 3)]
        (is (= 3 (count (:steps r))))
        (is (:steps (:truncated r)))))
    (testing "a form calling more than `children` others says how many more"
      (let [r (graph/call-sequence st 'cs.a/root :depth 3 :children 2)]
        (is (= {:depth 1 :from 'cs.a/root :more 2}
               (dissoc (last (:steps r)) :i)))))))
