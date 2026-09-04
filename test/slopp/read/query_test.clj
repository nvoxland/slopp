(ns slopp.read.query-test
  "query-brief / query-impact honour declared (^{:covers}) coverage — the
   dispatch/data path the trace and static reach can't see — the same way
   review-scan and affected-tests do. In-image because declared coverage is
   static (no live trace needed)."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.read.query :as query] [slopp.read.graph :as graph]))

(defn- covers-store []
  (-> (store/empty-store)
      (store/ingest 'qi.core "(ns qi.core)\n(defn target [x] x)\n")
      (store/ingest 'qi.core-test
                    (str "(ns qi.core-test (:require [clojure.test :refer [deftest is]]))\n"
                         "(deftest ^{:covers \"qi.core/target — via dispatch\"} cover-t (is true))\n"))))

(deftest query-impact-reports-declared-coverage
  ;; query-impact's :covered-by is the canonical covered-by, filtered to the
  ;; tests that EXERCISE or CLAIM the form (observed ∪ declared). A ^{:covers}
  ;; test names a dispatch path the trace never records, so it must appear.
  (let [sess (atom {:store (covers-store) :test-map {}})
        r    (graph/query-impact sess 'qi.core 'target)]
    (is (= {:count 1 :tests ['qi.core-test/cover-t]} (:covered-by r)) (pr-str r))))

(deftest query-brief-honours-declared-coverage
  ;; query-brief must not call a ^{:covers}-declared form :untested, and the
  ;; declaring test surfaces in :reached-by tagged :via #{:declared} (no hops —
  ;; a claim, not a measured reach).
  (let [;; a non-empty trace map so the :untested branch is live (it needs
        ;; SOME evidence in the session before it will flag anything)
        sess (atom {:store (covers-store) :test-map {'unrelated/t #{'unrelated/x}}})
        b    (query/query-brief sess 'qi.core 'target)]
    (testing "a declared-covered form is not untested"
      (is (not (:untested b)) (pr-str b)))
    (testing "the declaring test is reported in :reached-by via :declared"
      (is (= [{:test 'qi.core-test/cover-t :via #{:declared}}] (:reached-by b))
          (pr-str b)))))

(deftest the-effectful-set-of-a-namespace-has-ONE-spelling
  ;; slopp-ui, 2026-08-03: they asked for a per-form effect badge on the HTTP
  ;; ns-outline. That would have been the THIRD place spelling
  ;; `(effectful-vars (analyze (render-ns st ns)))` — query-outline,
  ;; query-symbol, and the new one. Three spellings of one fact is how the
  ;; fact starts differing, so it gets a producer before it gets a caller.
  ;;
  ;; The transitive case is the reason the badge cannot be a name check:
  ;; `report` carries no `!` and reaches one, and that is precisely the form
  ;; a reader needs marked.
  (let [st  (store/ingest (store/empty-store) 'eff.demo
                          (str "(ns eff.demo)\n"
                               "(defn pure [x] (inc x))\n"
                               "(defn save! [a] (swap! a inc))\n"
                               "(defn report [a] (save! a))\n"))
        eff (query/ns-effectful-vars st 'eff.demo)]
    (is (contains? eff 'eff.demo/save!))
    (is (contains? eff 'eff.demo/report)
        "reaching an effect IS being effectful — the ! convention seeds the
         fixpoint, it is not the test")
    (is (not (contains? eff 'eff.demo/pure)))
    (testing "qualified by the full namespace, which is what a caller looks up by"
      (is (every? #(= "eff.demo" (namespace %)) eff)))))

(deftest a-thrown-query-carries-its-CAUSE-not-just-its-outermost-frame
  ;; slopp-ui: `query_store` answered "query_store threw: Syntax error
  ;; compiling at (0:0)." on a form that is plainly valid, which reads as an
  ;; accusation against the caller's input and names nothing.
  ;;
  ;; It is `ex-message` on a CompilerException — that class's own message IS
  ;; "Syntax error compiling at (line:col)", and the sentence a reader needs is
  ;; one cause down. Same defect the screen driver had two hours earlier,
  ;; where the outermost frame said "Syntax error macroexpanding at." and the
  ;; cause said "Could not locate cheshire/core".
  ;;
  ;; A surface that reports the outer frame has answered nothing and looks like
  ;; it answered, which is strictly worse than saying it does not know.
  (testing "the chain travels, outermost first"
    (let [e (ex-info "outer" {} (ex-info "middle" {} (ex-info "root cause" {})))]
      (is (= "ExceptionInfo: outer <- ExceptionInfo: middle <- ExceptionInfo: root cause"
             (query/cause-chain e)))))

  (testing "a lone exception is just itself — no decoration for a chain of one"
    (is (= "ArithmeticException: Divide by zero"
           (query/cause-chain (ArithmeticException. "Divide by zero")))))

  (testing "a message-less exception still names its CLASS"
    (is (= "NullPointerException" (query/cause-chain (NullPointerException.)))
        "the class is the only thing left, and it is more than nothing — with no trailing colon promising a message that is not coming")))

(deftest a-search-hit-is-a-card-not-a-bare-line
  ;; eval10 census: 6–7 searches per lifetime cell, each followed by a
  ;; query_source of the forms it named — a bare line says WHERE, and the
  ;; agent then reads to learn WHAT. A hit carrying the form's signature and
  ;; doc line answers the usual next question in the same call, and a form
  ;; matching on several lines is ONE row that counts them.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'sq.core
                               (str "(ns sq.core)\n"
                                    "(defn price\n  \"Price in cents.\"\n  [x]\n  (* 100 x))\n"
                                    "(defn ^:unused-ok twice\n  \"Twice the price.\"\n  [x]\n  (price (price x)))\n")))
        sess (atom {:store st :test-map {}})
        hits (query/query-search sess "price")]
    (is (= ['price 'twice] (mapv :form hits)) (pr-str hits))
    (testing "one row per form, counting the lines that matched"
      (is (nil? (:matches (first hits))) "one line matched in price — no count to report")
      (is (= 2 (:matches (second hits))) (pr-str (second hits)))
      (is (= "(defn price" (:line (first hits))) "the first matching line rides"))
    (testing "the row is a card"
      (is (= '[x] (:sig (first hits))))
      (is (= "Price in cents." (:doc (first hits))))
      (is (= "Twice the price." (:doc (second hits)))))))

(deftest query-cost-splits-by-model-and-by-ask-and-refuses-an-unknown-split
  ;; The front door stays PURE: the measurements are read beside their writer
  ;; (slopp.ops/otel-measurements) and handed in, so every split here is a
  ;; fold over a store VALUE and an argument. Opening the journal here is what
  ;; made this namespace's :pure tier a claim it had not earned, once.
  (let [store   {:deltas [{:op :turn-begin :id "d1" :at 1000 :agent "a" :intent "the ask"}
                          {:op :turn-end   :id "d2" :at 2000 :agent "a"}]}
        session (atom {:store store})
        otel    [{:requests [{:at-ns "1500000000" :prompt "p1" :model "claude-opus-5"
                              :input 1 :output 1 :cache-read 8 :cache-creation 0
                              :context 10 :cost-usd 0.25}]}]]

    (testing "by model"
      (let [r (query/query-turn-cost session :by "model" :otel otel)]
        (is (= :model (:by r)))
        (is (= ["claude-opus-5"] (mapv :model (:rows r))))))

    (testing "by ask"
      (let [r (query/query-turn-cost session :by "ask" :otel otel)]
        (is (= :ask (:by r)))
        (is (= ["the ask"] (mapv :intent (:rows r))))
        (is (= 1 (:requests (first (:rows r)))) (pr-str (:rows r)))))

    (testing "no :by is still the whole window"
      (is (contains? (query/query-turn-cost session :otel otel) :wall)))

    (testing "an unrecognized split is REFUSED, not silently the default"
      ;; a mistyped `by` that quietly answered the whole-window fold reads as
      ;; "there is nothing to split by" — the wrong answer arriving silently,
      ;; which is the failure every reader of someone else's schema here is
      ;; written to avoid
      (is (thrown-with-msg? Exception #"unknown :by"
                            (query/query-turn-cost session :by "modle"))))))
