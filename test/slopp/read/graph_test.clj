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
