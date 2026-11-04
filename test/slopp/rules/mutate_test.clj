(ns slopp.rules.mutate-test
  "Cover for mutation testing: the mutant generator (pure, over source text), the image-side runner string (evaluated right here, since in-image tests run inside an image), the backlog fold, and one end-to-end run against a booted image."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [slopp.rules.mutate :as mutate]
            [slopp.store :as store]
            [slopp.image.repl :as repl] [rewrite-clj.node :as n] [slopp.edit.hotload :as hotload]))

(deftest a-mutant-is-one-small-fault-in-the-body
  (let [src "(defn f\n  \"Doc.\"\n  [x]\n  (if (< x 10) (inc x) false))"
        ms  (mutate/mutants src 50)
        by  (into {} (map (juxt :mutation identity)) ms)]
    (testing "each operator in the body yields one mutant naming what it changed"
      (is (= #{"if → if-not" "< → <=" "10 → 11" "inc → dec" "false → true"}
             (set (map :mutation ms)))))
    (testing "the mutant is the same source with exactly that one change"
      (is (= "(defn f\n  \"Doc.\"\n  [x]\n  (if (<= x 10) (inc x) false))"
             (:source (by "< → <=")))))
    (testing "it says where by the expression it sits in, never by a coordinate"
      (is (= "(< x 10)" (:at (by "< → <="))))
      (is (not-any? #(contains? % :line) ms)))
    (testing "a cap of k picks k sites spread over the body, in source order"
      (is (= ["if → if-not" "10 → 11"] (mapv :mutation (mutate/mutants src 2)))))))

(deftest declarations-and-data-are-not-mutated
  (testing "metadata, quoted data and the arglist are not behaviour"
    (is (= #{"= → not=" "if → if-not"}
           (set (map :mutation (mutate/mutants "(defn ^{:level 3} g [x] (if (= x '(< 1 2)) x nil))" 50))))))
  (testing "a form wrapped in reader metadata keeps it in every mutant"
    (is (= ["^:unsafe (defn h [x] (dec x))"]
           (mapv :source (mutate/mutants "^:unsafe (defn h [x] (inc x))" 50))))))

(deftest the-backlog-is-what-the-budget-did-not-reach-in-the-current-version
  (let [s   (store/ingest (store/empty-store) 'demo.b
                          "(ns demo.b)\n\n(defn a [x] (inc x))\n\n(defn b [x] (dec x))\n\n(defn c [x] x)\n")
        id  #(:id (store/form-named s 'demo.b %))
        sig #(mutate/form-sig s (id %))
        row (fn [nm sg status] {:payload {:fid (id nm) :sig sg :form (symbol "demo.b" (str nm)) :status status}})]
    (testing "unchecked, and not checked since in the same version"
      (is (= '[demo.b/a]
             (mapv :form (mutate/backlog s [(row 'a (sig 'a) :unchecked)
                                           (row 'b (sig 'b) :unchecked)
                                           (row 'b (sig 'b) :killed)
                                           (row 'c "an older version" :unchecked)])))))
    (testing "a version is a hash of the form's source, so an edit changes it"
      (is (string? (sig 'a)))
      (is (not= (sig 'a) (sig 'b))))))

(deftest ^:external mutants-the-tests-notice-die-and-the-rest-survive
  (let [src (str "(ns demo.mut)\n\n"
                 "(defn strong \"S.\" [x] (if (< x 10) :small :big))\n\n"
                 "(defn weak \"W.\" [x] (if (< x 10) :small :big))\n")
        s   (store/ingest (store/empty-store) 'demo.mut src)
        fid #(:id (store/form-named s 'demo.mut %))
        h   (repl/start!)]
    (try
      (repl/eval! h (str src
                         "(ns demo.mut-test (:require [clojure.test :refer [deftest is]] [demo.mut :as m]))\n"
                         "(deftest strong-t (is (= :small (m/strong 9))) (is (= :big (m/strong 10))))\n"
                         "(deftest weak-t (is (keyword? (m/weak 3))))\n"
                         "(deftest red-t (is false))\n"
                         "(deftest ^:external ext-t (is false))\n"
                         "(deftest slow-t (Thread/sleep 3000))\n"
                         "(in-ns 'user)"))
      (testing "the runner stops at the first test that notices and skips what it may not run"
        (let [run #(first (repl/eval! h (mutate/run-string %1 %2)))]
          (is (= '{:survived true :ran 1 :skipped 1}
                 (run '[demo.mut-test/ext-t demo.mut-test/weak-t] 2000)))
          (is (= '{:killed-by demo.mut-test/red-t :ran 2}
                 (run '[demo.mut-test/weak-t demo.mut-test/red-t demo.mut-test/strong-t] 2000)))
          (is (= {:timeout true} (run '[demo.mut-test/slow-t] 100)))))
      (let [rs (mutate/mutate! h s [{:fid (fid 'strong) :form 'demo.mut/strong :tests '[demo.mut-test/strong-t]}
                                    {:fid (fid 'weak) :form 'demo.mut/weak :tests '[demo.mut-test/weak-t]}
                                    {:fid (fid 'weak) :form 'demo.mut/untested :tests []}]
                               :budget-ms 30000)
            by (into {} (map (juxt :form identity)) rs)]
        (testing "a test that asserts the boundary kills every mutant"
          (is (= :killed (:status (by 'demo.mut/strong))))
          (is (= 3 (:killed (by 'demo.mut/strong)))))
        (testing "a test that only checks the type lets them all survive, and says which"
          (is (= :survived (:status (by 'demo.mut/weak))))
          (is (= #{"if → if-not" "< → <=" "10 → 11"}
                 (set (map :mutation (:survivors (by 'demo.mut/weak)))))))
        (testing "a form with no test to run is reported as untested, not as clean"
          (is (= :untested (:status (by 'demo.mut/untested)))))
        (testing "the real code is back in the image afterwards"
          (is (= [:big] (repl/eval! h "(demo.mut/strong 10)")))
          (is (= [:big] (repl/eval! h "(demo.mut/weak 10)")))))
      (testing "a spent budget checks nothing and says so"
        (is (= [:unchecked]
               (mapv :status (mutate/mutate! h s [{:fid (fid 'strong) :form 'demo.mut/strong
                                                   :tests '[demo.mut-test/strong-t]}]
                                             :budget-ms 0)))))
      (finally (repl/stop! h)))))

(deftest only-changed-production-functions-with-their-observed-tests-are-targets
  (let [s   (-> (store/empty-store)
                (store/ingest 'demo.t "(ns demo.t)\n\n(def data 1)\n\n(defn f [x] (inc x))\n\n(defn g [x] x)\n")
                (store/ingest 'demo.t-test "(ns demo.t-test)\n\n(defn helper [] 1)\n"))
        id  (fn [nsx nm] (:id (store/form-named s nsx nm)))
        tmap '{demo.t-test/f-t     #{demo.t/f}
               demo.t-test/other-t #{demo.t/g demo.t/f}
               demo.t-test/data-t  #{demo.t/data}}
        ts  (mutate/targets s tmap [(id 'demo.t 'data) (id 'demo.t 'f) (id 'demo.t 'g)
                                    (id 'demo.t-test 'helper)])]
    (testing "a data def and a test-namespace helper are not targets; each defn carries its observed tests"
      (is (= '[[demo.t/f [demo.t-test/f-t demo.t-test/other-t]] [demo.t/g [demo.t-test/other-t]]]
             (mapv (juxt :form :tests) ts))))
    (testing "each target names the version it is about"
      (is (= (mutate/form-sig s (id 'demo.t 'f)) (:sig (first ts)))))))

(deftest a-survivor-names-the-fault-and-a-spent-budget-is-owed-not-clean
  (let [fs (mutate/findings [{:form 'a/f :status :killed}
                             {:form 'a/g :status :survived
                              :survivors [{:mutation "< → <=" :at "(< x 10)" :tests '[a-test/t]}]}
                             {:form 'a/h :status :unchecked}
                             {:form 'a/u :status :untested}])
        g  (first (filter #(= 'a/g (:form %)) fs))
        owed (first (filter :unchecked fs))]
    (testing "a form whose mutants all died says nothing"
      (is (not-any? #(= 'a/f (:form %)) fs)))
    (testing "a survivor is a finding on its form naming the fault, where it is, and the tests"
      (is (str/includes? (:teach g) "< → <= in (< x 10)"))
      (is (str/includes? (:teach g) "a-test/t")))
    (testing "what the budget did not reach is one formless :info finding naming it"
      (is (= '[a/h] (:unchecked owed)))
      (is (= :info (:severity owed)))
      (is (nil? (:form owed))))
    (testing "a function with no test to mutate against is said, not hidden"
      (is (= '[a/u] (:untested (first (filter :untested fs))))))))

(deftest a-long-owed-list-is-cut-at-five-names-and-says-so
  (let [owed (fn [n] (:teach (first (mutate/findings (mapv #(hash-map :form (symbol "a" (str "f" %)) :status :unchecked)
                                                       (range n))))))]
    (testing "five names fit and nothing is marked as cut"
      (is (str/includes? (owed 5) "a/f4"))
      (is (not (str/includes? (owed 5) "…"))))
    (testing "a sixth is left out and the cut is marked"
      (is (str/includes? (owed 6) "a/f4, …"))
      (is (not (str/includes? (owed 6) "a/f5"))))))

(deftest an-attribute-map-is-a-declaration-not-behaviour
  (is (= ["inc → dec"] (mapv :mutation (mutate/mutants "(defn g {:added 1} [x] (inc x))" 50)))))

(deftest two-faults-in-one-place-are-told-apart-by-their-expression
  (let [ms (mutate/mutants "(defn f [x] (take 5 (range 5)))" 50)]
    (is (= [["5 → 6" "(take 5 (range 5))"] ["5 → 6" "(range 5)"]] (mapv (juxt :mutation :at) ms))))
  (testing "a long expression is cut, and the cut is marked"
    (let [at (:at (first (mutate/mutants (str "(defn f [x] (+ 1 " (apply str (repeat 40 "x ")) "))") 50)))]
      (is (<= (count at) 61))
      (is (str/ends-with? at "…")))))

(deftest the-cut-keeps-exactly-the-first-sixty-characters
  (let [at      (fn [expr] (:at (first (mutate/mutants (str "(defn f [x] " expr ")") 50))))
        exactly (str "(inc " (apply str (repeat 54 "y")) ")")
        longer  (str "(inc " (apply str (repeat 60 "y")) ")")]
    (testing "sixty characters fit whole"
      (is (= 60 (count exactly)))
      (is (= exactly (at exactly))))
    (testing "past sixty, the first sixty are kept and the cut is marked"
      (is (= (str (subs longer 0 60) "…") (at longer))))))

(deftest an-equivalent-mutant-is-discharged-by-naming-it
  ;; 4np: an equivalent mutant re-reported at every done that touched its
  ;; form, and the only way to quiet it was to rewrite the code into a shape
  ;; the mutator does not touch, which is gaming the tool
  (let [st  (store/ingest (store/empty-store) 'eq.core
                          (str "(ns eq.core)\n\n"
                               "(defn ^{:equivalent-mutants {\"= -> not=\" \"the filter only drops work; both arms return the same set\"}}"
                               " f [x] x)\n\n(defn g [x] x)\n"))
        rs  [{:form 'eq.core/f :status :survived
              :survivors [{:mutation "= -> not=" :at "(= a b)"} {:mutation "and -> or" :at "(and a b)"}]}
             {:form 'eq.core/g :status :survived
              :survivors [{:mutation "= -> not=" :at "(= a b)"}]}
             {:form 'eq.core/f2 :status :killed}]
        out (into {} (map (juxt :form identity)) (mutate/discharge st rs))]
    (is (= ["and -> or"] (mapv :mutation (:survivors (out 'eq.core/f)))) "only the named mutation is discharged")
    (is (= :survived (:status (out 'eq.core/g))) "another form's marker discharges nothing here")
    (let [all (first (mutate/discharge st [{:form 'eq.core/f :status :survived
                                           :survivors [{:mutation "= -> not=" :at "(= a b)"}]}]))]
      (is (= :discharged (:status all)) "every survivor named: nothing left to report"))
    (is (re-find #":equivalent-mutants" (:teach (first (mutate/findings [(out 'eq.core/g)]))))
        "and the finding names the way to say it")))

(deftest a-timed-out-mutant-says-its-thread-may-still-run
  ;; A run past the per-mutant limit is cancelled, and counts as a kill: the
  ;; fault changed behaviour enough to stop the tests finishing. But a mutant
  ;; that loops without checking for interruption keeps its thread in the
  ;; image, burning CPU under every later verification. Nothing can stop that
  ;; thread from outside, so the honest result is to say it and name the fix.
  (let [fs  (mutate/findings [{:form 'a/f :status :killed :timeout 2}
                              {:form 'a/g :status :killed :timeout 0}
                              {:form 'a/h :status :survived :timeout 1
                               :survivors [{:mutation "< → <=" :at "(< x 10)" :tests '[a-test/t]}]}])
        tim (first (filter :timed-out fs))]
    (testing "one formless :info finding names every form with a timed-out mutant"
      (is (= '[a/f a/h] (:timed-out tim)))
      (is (= :info (:severity tim)))
      (is (nil? (:form tim))))
    (testing "it says the thread may still run and that restart clears it"
      (is (str/includes? (:teach tim) "3 mutant(s)"))
      (is (str/includes? (:teach tim) "restart")))
    (testing "no timeout, no finding"
      (is (not-any? :timed-out (mutate/findings [{:form 'a/g :status :killed :timeout 0}]))))))

(deftest a-token-in-a-long-form-is-placed-by-its-neighbour
  ;; `:at` is where a reader looks for the fault. A token that sits directly
  ;; in a cond or let has the whole cond as its enclosing expression: many
  ;; lines, cut at sixty characters to something that does not contain the
  ;; token at all. Its left neighbour — the test of a cond pair, the name of
  ;; a let binding — is what places it.
  (let [src (str "(defn f [up x]\n  (cond (nil? up) false\n        (pos? x) (+ x 1000000)\n"
                 "        :else (str \"a long tail that pushes the cond past sixty\")))")
        ms  (mutate/mutants src 50)
        by  (into {} (map (juxt :mutation :at)) ms)]
    (testing "a token directly in the long cond is shown with its left neighbour"
      (is (= "(nil? up) false" (by "false → true"))))
    (testing "a short enclosing expression is still shown whole"
      (is (= "(+ x 1000000)" (by "1000000 → 1000001")))))
  (testing "every :at is one line"
    (let [ms (mutate/mutants "(defn f [x]\n  (+ x\n     1))" 50)]
      (is (= "(+ x 1)" (:at (first ms)))))))

(deftest a-behaviour-change-is-more-than-a-docstring-or-a-name
  (testing "a different body is a behaviour change"
    (is (true? (mutate/behaviour-change? "(defn f \"D.\" [x] x)" "(defn f \"D.\" [x] (inc x))"))))
  (testing "a docstring-only edit is not"
    (is (false? (mutate/behaviour-change? "(defn f \"Old.\" [x] x)" "(defn f \"New words.\" [x] x)")))
    (is (false? (mutate/behaviour-change? "(defn f [x] x)" "(defn f \"Now documented.\" [x] x)"))))
  (testing "a rename is not: the old name is not this function"
    (is (false? (mutate/behaviour-change? "(defn f [x] (inc x))" "(defn g [x] (dec x))"))))
  (testing "source that does not parse counts as a change, so it is checked rather than skipped"
    (is (true? (mutate/behaviour-change? "(defn f [x" "(defn f [x] x)")))))

(deftest ^:external a-change-is-proven-by-a-test-that-fails-on-its-old-version
  (let [src (str "(ns demo.prove)\n\n"
                 "(def k 2)\n\n"
                 "(defn fixed \"F.\" [x] (inc x))\n\n"
                 "(defn same \"S.\" [x] (inc x))\n\n"
                 "(defn fresh \"N.\" [x] x)\n\n"
                 "(defn lonely \"L.\" [x] x)\n\n"
                 "(defn extonly \"E.\" [x] x)\n\n"
                 "(defn left \"half of a fix\" [x] (max x 5))\n\n"
                 "(defn right \"half of a fix\" [x] (max x 5))\n\n"
                 "(defn uses-k \"U.\" [x] (* k x))\n")
        s   (store/ingest (store/empty-store) 'demo.prove src)
        fid #(:id (store/form-named s 'demo.prove %))
        h   (repl/start!)
        t   (fn [nm tests old] {:fid (fid nm) :form (symbol "demo.prove" (str nm)) :tests tests :old old})]
    (try
      (repl/eval! h (str src
                         "(ns demo.prove-test (:require [clojure.test :refer [deftest is]] [demo.prove :as p]))\n"
                         "(deftest fixed-t (is (= 2 (p/fixed 1))))\n"
                         "(deftest same-t (is (= 2 (p/same 1))))\n"
                         "(deftest fresh-t (is (= 1 (p/fresh 1))))\n"
                         "(deftest ^:external ext-t (is (= 1 (p/extonly 1))))\n"
                         "(deftest spread-t (is (= 5 (max (p/left 0) (p/right 0)))))\n"
                         "(deftest k-t (is (= 4 (p/uses-k 2))))\n"
                         "(in-ns 'user)"))
      (let [rs (mutate/prove! h s [(t 'fixed '[demo.prove-test/fixed-t] "(defn fixed \"F.\" [x] x)")
                                   (t 'same '[demo.prove-test/same-t] "(defn same \"S.\" [x] (+ x 1))")
                                   (t 'fresh '[demo.prove-test/fresh-t] nil)
                                   (t 'lonely [] "(defn lonely \"L.\" [x] 0)")
                                   (t 'extonly '[demo.prove-test/ext-t] "(defn extonly \"E.\" [x] 0)")
                                   (t 'left '[demo.prove-test/spread-t] "(defn left \"half of a fix\" [x] x)")
                                   (t 'right '[demo.prove-test/spread-t] "(defn right \"half of a fix\" [x] x)")
                                   (t 'uses-k '[demo.prove-test/k-t] "(defn uses-k \"U.\" [x] (* x k))")]
                           :also-old {(fid 'k) "(def k 1)"}
                           :budget-ms 30000)
            by (into {} (map (juxt :form identity)) rs)]
        (testing "a test that fails against the old version proves the change, and names itself"
          (is (= :proven (:status (by 'demo.prove/fixed))))
          (is (= 'demo.prove-test/fixed-t (:by (by 'demo.prove/fixed)))))
        (testing "tests that pass against both versions prove nothing"
          (is (= :unproven (:status (by 'demo.prove/same)))))
        (testing "a new function's test really goes red: in the red state it is a stub that throws"
          (is (= :proven (:status (by 'demo.prove/fresh))))
          (is (= 'demo.prove-test/fresh-t (:by (by 'demo.prove/fresh)))))
        (testing "a fix spread over two functions is proven, though reverting either alone still passes"
          (is (= :proven (:status (by 'demo.prove/left))))
          (is (= :proven (:status (by 'demo.prove/right)))))
        (testing "a changed def goes back with the rest"
          (is (= :proven (:status (by 'demo.prove/uses-k)))))
        (testing "a changed function no test runs is uncovered"
          (is (= :uncovered (:status (by 'demo.prove/lonely)))))
        (testing "what only the external tier runs cannot be judged in the image"
          (is (= :unprovable (:status (by 'demo.prove/extonly)))))
        (testing "the current code is back in the image afterwards, stubs and all"
          (is (= [2] (repl/eval! h "(demo.prove/fixed 1)")))
          (is (= [1] (repl/eval! h "(demo.prove/extonly 1)")))
          (is (= [7] (repl/eval! h "(demo.prove/fresh 7)")))
          (is (= [5] (repl/eval! h "(demo.prove/left 0)")))
          (is (= [2] (repl/eval! h "demo.prove/k")))))
      (testing "an old version that no longer loads cannot be judged, and the real one comes back"
        (let [[r] (mutate/prove! h s [(t 'fixed '[demo.prove-test/fixed-t] "(defn fixed [x] (no-such-fn x))")])]
          (is (= :unprovable (:status r)) (pr-str r))
          (is (= [2] (repl/eval! h "(demo.prove/fixed 1)")))))
      (testing "a spent budget checks nothing and says so"
        (is (= [:unchecked]
               (mapv :status (mutate/prove! h s [(t 'fixed '[demo.prove-test/fixed-t] "(defn fixed [x] x)")]
                                            :budget-ms 0)))))
      (finally (repl/stop! h)))))

(deftest a-def-of-a-fn-and-a-defmethod-are-functions-too
  ;; slopp-s8x.4: only defn and defn- were checked, so logic written as a
  ;; defmethod or (def f (fn ...)) was outside both red-proof and mutation.
  (testing "the shapes that hold behaviour"
    (is (mutate/defn-source? "(defn f [x] x)"))
    (is (mutate/defn-source? "(def f (fn [x] x))"))
    (is (mutate/defn-source? "(def f \"doc\" (fn [x] x))"))
    (is (mutate/defn-source? "(def f #(inc %))"))
    (is (mutate/defn-source? "(defmethod m :k [x] x)")))
  (testing "and the ones that do not"
    (is (not (mutate/defn-source? "(def data 1)")))
    (is (not (mutate/defn-source? "(def data {:f 1})")))
    (is (not (mutate/defn-source? "(defmulti m :k)"))))
  (testing "a mutant of each lands in the body, not on the dispatch value"
    (is (= ["inc → dec"] (mapv :mutation (mutate/mutants "(def f (fn [x] (inc x)))" 50))))
    (is (= ["inc → dec"] (mapv :mutation (mutate/mutants "(defmethod m 1 [x] (inc x))" 50)))))
  (testing "a nameless registration is a target, found by the key its trace uses"
    (let [s   (store/ingest (store/empty-store) 'dm.core
                            "(ns dm.core)\n\n(defmulti m :k)\n\n(defmethod m :a [x] (inc x))\n\n(def g (fn [x] x))\n")
          es  (store/forms s 'dm.core)
          mth (first (filter #(re-find #"defmethod" (str (n/string (:node %)))) es))
          g   (store/form-named s 'dm.core 'g)
          q   (symbol "dm.core" (:id mth))
          ts  (mutate/targets s {'dm.core-test/t #{q} 'dm.core-test/u #{'dm.core/g}} [(:id mth) (:id g)])]
      (is (= [[q ['dm.core-test/t]] ['dm.core/g ['dm.core-test/u]]]
             (mapv (juxt :form :tests) ts))))))

(deftest ^:external a-new-defmethod-goes-red-by-being-absent
  ;; slopp-s8x.4: a new function is a throwing stub in the red state, and that
  ;; stub is an `intern`: over a defmethod's multi it would replace the multi
  ;; itself, and the restore would not bring it back.
  (let [src (str "(ns demo.dm)\n\n(defmulti m :k)\n\n"
                 "(defmethod m :a [_] 1)\n\n(defmethod m :b [_] 2)\n")
        s   (store/ingest (store/empty-store) 'demo.dm src)
        mth (filter #(re-find #"defmethod" (str (n/string (:node %)))) (store/forms s 'demo.dm))
        a-id (:id (first mth))
        h   (repl/start!)]
    (try
      (repl/eval! h (str src
                         "(ns demo.dm-test (:require [clojure.test :refer [deftest is]] [demo.dm :as d]))\n"
                         "(deftest a-t (is (= 1 (d/m {:k :a}))))\n"
                         "(deftest b-t (is (= 2 (d/m {:k :b}))))\n"
                         "(in-ns 'user)"))
      (let [[r] (mutate/prove! h s [{:fid a-id :form (symbol "demo.dm" a-id)
                                     :tests '[demo.dm-test/a-t] :old nil}]
                               :budget-ms 30000)]
        (is (= :proven (:status r)) (pr-str r))
        (is (= 'demo.dm-test/a-t (:by r))))
      (testing "the method is back, and the multi and its other method were never disturbed"
        (is (= [1] (repl/eval! h "(demo.dm/m {:k :a})")))
        (is (= [2] (repl/eval! h "(demo.dm/m {:k :b})"))))
      (finally (repl/stop! h)))))

(deftest a-form-whose-mutants-all-failed-to-load-is-owed-not-killed
  ;; slopp-s8x.4: an invalid mutant only incremented a counter, and no survivor
  ;; read as :killed, so a form nothing tested came back clean and the
  ;; false-green rate counted it. A clean row nobody earned.
  (let [s   (store/ingest (store/empty-store) 'mi.core "(ns mi.core)\n\n(defn f [x] (inc x))\n")
        fid (:id (store/form-named s 'mi.core 'f))
        t   {:fid fid :form 'mi.core/f :tests '[mi.core-test/t]}
        [r] (with-redefs [hotload/hot-load-form! (constantly "it does not compile")]
              (mutate/mutate! :no-image s [t]))]
    (is (= :unchecked (:status r)) (pr-str r))
    (is (= 1 (:invalid r)))
    (testing "and the finding says why, not that a budget ran out"
      (let [teach (str/join " " (map :teach (mutate/findings [r])))]
        (is (re-find #"could not be loaded" teach) teach)
        (is (not (re-find #"budget ran out" teach)) teach)))))

(deftest a-blank-reason-discharges-nothing
  ;; slopp-s8x.5: flag_test refuses an empty reason; the two exits that quiet a
  ;; finding took one, so a marker with an empty reason was a free pass.
  (let [st (store/ingest (store/empty-store) 'bl.core
                         (str "(ns bl.core)\n\n"
                              "(defn ^{:equivalent-mutants {\"< → <=\" \"  \"}} f [x] x)\n"))
        [r] (mutate/discharge st [{:form 'bl.core/f :status :survived
                                   :survivors [{:mutation "< → <=" :at "(< a b)"}]}])]
    (is (= :survived (:status r)) (pr-str r))
    (is (= 1 (count (:survivors r))))))

(deftest a-failed-restore-still-restores-the-rest
  ;; slopp-s8x.5: the restore was (first (keep hot-load ...)), lazy, so once a
  ;; reload failed the forms past its chunk stayed at their OLD versions while
  ;; the message said the image MAY hold one.
  (let [src (apply str "(ns rs.core)\n\n" (for [i (range 40)] (str "(defn f" i " [x] x)\n\n")))
        s   (store/ingest (store/empty-store) 'rs.core src)
        ids (mapv #(:id (store/form-named s 'rs.core (symbol (str "f" %)))) (range 40))
        ts  (vec (for [[i id] (map-indexed vector ids)]
                   {:fid id :form (symbol "rs.core" (str "f" i)) :tests '[rs.core-test/t]
                    :old (str "(defn f" i " [x] 0)")}))
        calls (atom {})
        rs  (with-redefs [hotload/hot-load-form!
                          (fn [_ _ fid]
                            (let [n (get (swap! calls update fid (fnil inc 0)) fid)]
                              (when (and (= 2 n) (= fid (first ids))) "boom")))
                          repl/eval! (fn [& _] [{:failed [] :passed '[rs.core-test/t]}])]
              (mutate/prove! :no-image s ts))]
    (is (every? #(= :restore-failed (:status %)) rs))
    (is (every? #(= 2 (get @calls %)) ids)
        (str "forms never reloaded: " (count (remove #(= 2 (get @calls %)) ids))))))
