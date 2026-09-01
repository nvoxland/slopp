(ns slopp.eval-findings-test
  "Fixes for what the symmetric eval surfaced (S-series)."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.read.query :as query] [slopp.ops.external :as external]))

(deftest ^:external s1-non-compiling-forms-are-rejected-not-silently-committed
  (let [sess (external/open!)
        n-deltas #(count (ops/journal sess))]
    (try
      (ops/ingest! sess 's1.core
                   (str "(ns s1.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] x)\n"))
      (testing "an add whose form doesn't compile returns {:error}, nothing committed"
        (let [n (n-deltas)
              r (ops/add-form! sess 's1.core "(defn bad [] (undefined-fn 1))")]
          (is (:error r))
          (is (re-find #"compile" (:error r)))
          (is (= n (n-deltas)))
          (is (not (re-find #"bad" (query/query-source sess 's1.core))))))
      (testing "the sonnet case: a test referencing an undefined fn is LOUD, never {:ok :ran 0}"
        ;; what this guards against is SILENCE — landed, nothing ran, nothing
        ;; said. A refusal was the only loud outcome when this was written;
        ;; red-first's second source (s13) made a louder one: the spec lands
        ;; as :red-first, the stub throws, and the test RUNS red.
        (let [r (ops/add-form! sess 's1.core "(deftest ghost-t (is (= 1 (ghost 1))))")]
          (is (or (:error r)
                  (and (seq (:red-first r))
                       (pos? (+ (:fail (:test r) 0) (:error (:test r) 0)))))
              (pr-str (select-keys r [:error :red-first :test])))))
      (testing "a replace that doesn't compile leaves the old form intact everywhere"
        (let [r (ops/edit-replace! sess 's1.core 'f "(defn f [x] (nope x))")]
          (is (:error r))
          (is (re-find #"\(defn f \[x\] x\)" (query/query-source sess 's1.core)))
          (is (= [7] (ops/query-eval sess "(s1.core/f 7)")))))
      (testing "a group with a non-compiling step commits nothing and the image stays faithful"
        (let [n (n-deltas)
              r (ops/edit-group-once! sess
                                 [{:action :replace :ns 's1.core :name 'f
                                   :source "(defn f [x] (* 2 x))"}
                                  {:action :add :ns 's1.core
                                   :source "(defn g [] (missing))"}]
                                 :prompt "should fail atomically")]
          (is (:error r))
          (is (= n (n-deltas)))
          ;; first step's compile succeeded in the image before step 2 failed —
          ;; the image must be restored to match the (unchanged) store
          (is (= [7] (ops/query-eval sess "(s1.core/f 7)")))))
      (finally (ops/close! sess)))))

(deftest ^:external s2-forward-refs-are-rejected-at-write-time-and-order-is-derived
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 's2.core "(ns s2.core)\n")
      (testing "S1 makes dependency order self-enforcing: a caller added before
                its callee is rejected on the spot (use (declare x) for mutual
                recursion)"
        (is (:error (ops/add-form! sess 's2.core "(defn caller [x] (helper x))"))))
      (ops/add-form! sess 's2.core "(defn helper [x] (* 2 x))")
      (ops/add-form! sess 's2.core "(defn caller [x] (helper x))")
      (ops/add-form! sess 's2.core "(defn util [] :u)")
      (testing "the arrangement is derived: a definition precedes its callers,
                and there is no tool to say otherwise — `edit_move` and the
                `:move` op are gone"
        (let [src ^String (query/query-source sess 's2.core)]
          (is (< (.indexOf src "defn helper") (.indexOf src "defn caller"))))
        (is (empty? (filter #(= :move (:op %)) (ops/journal sess)))
            "nothing about the order was written to the log"))
      (testing "a FRESH load loads in that order; everything still works"
        (ops/restart! sess)
        (is (= [10] (ops/query-eval sess "(s2.core/caller 5)")))
        (is (= [:u] (ops/query-eval sess "(s2.core/util)"))))
      (finally (ops/close! sess)))))

(deftest ^:external x3-image-loads-follow-dependency-order
  ;; 12 chained namespaces: >8 entries puts the store's ns map in hash order,
  ;; which used to drive restart loads -> silent half-loaded images (round 3).
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'x3.n1)
      (ops/add-form! sess 'x3.n1 "(defn f1 [x] (inc x))")
      (doseq [i (range 2 13)]
        (let [ns-sym  (symbol (str "x3.n" i))
              prev    (str "x3.n" (dec i))]
          (ops/module-dep! sess (str "x3.n" i) prev :prompt "chain link")
          (ops/create-ns! sess ns-sym
                          :requires [(str "[" prev " :as p]")])
          (ops/add-form! sess ns-sym
                         (format "(defn f%d [x] (p/f%d x))" i (dec i)))))
      (ops/restart! sess)
      (testing "after restart, EVERY namespace in the 12-deep chain is live"
        ;; f2..f12 delegate down to f1 (a single inc): f12(1) = 2
        (is (= [2] (ops/query-eval sess "(x3.n12/f12 1)"))))
      (finally (ops/close! sess)))))

(deftest ^:external x2-rename-loads-the-definition-first
  ;; many cross-ns callers -> pre-fix, hash-ordered changeset loads could
  ;; reload a caller before the renamed def existed (destructive failure).
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'x2.m)
      (ops/add-form! sess 'x2.m "(defn f [x] (* 2 x))")
      (doseq [i (range 1 10)]
        (let [ns-sym (symbol (str "x2.c" i))]
          (ops/module-dep! sess (str "x2.c" i) "x2.m" :prompt "caller")
          (ops/create-ns! sess ns-sym :requires ["[x2.m :as m]"])
          (ops/add-form! sess ns-sym (format "(defn call%d [x] (m/f x))" i))))
      (let [r (ops/rename! sess 'x2.m 'f 'g :prompt "x2 regression")]
        (is (nil? (:error r)))
        (is (= 10 (get-in r [:renamed :forms]))))
      (testing "image consistent immediately and after a fresh restart"
        (is (= [14] (ops/query-eval sess "(x2.c9/call9 7)")))
        (ops/restart! sess)
        (is (= [14] (ops/query-eval sess "(x2.c9/call9 7)"))))
      (finally (ops/close! sess)))))

(deftest ^:external reload-of-a-store-namespace-is-a-no-op-not-a-file-error   ; self-host eval finding
  ;; Store namespaces have no .clj on the classpath (loaded via load-ns!), so the
  ;; muscle-memory `(require 'the.ns :reload)` threw FileNotFoundException. In the
  ;; owned image there are no source files to reload, so query-eval strips
  ;; :reload/:reload-all — the require becomes the intended no-op.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'rl.core "(ns rl.core)\n(defn f [x] (inc x))\n")
      (testing "plain require of the loaded store ns works (baseline)"
        (is (= [4] (ops/query-eval sess "(do (require 'rl.core) (rl.core/f 3))"))))
      (testing ":reload no longer errors — it's stripped in the image"
        (is (= [4] (ops/query-eval sess "(do (require 'rl.core :reload) (rl.core/f 3))"))))
      (testing ":reload-all is stripped too"
        (is (= [4] (ops/query-eval sess "(do (require 'rl.core :reload-all) (rl.core/f 3))"))))
      (finally (ops/close! sess)))))

(deftest ^:external remove-require-is-symmetric
  (let [sess (external/open!)]
    (try
      (ops/create-ns! sess 'rr.core :requires ["[clojure.string :as str]"
                                               "[clojure.set :as cset]"])
      (let [r (ops/remove-require! sess 'rr.core 'clojure.set)]
        (is (nil? (:error r)))
        (is (not (re-find #"clojure\.set" (query/query-source sess 'rr.core))))
        (is (re-find #"clojure\.string" (query/query-source sess 'rr.core))))
      (is (:error (ops/remove-require! sess 'rr.core 'clojure.set)))  ; already gone
      (finally (ops/close! sess)))))
