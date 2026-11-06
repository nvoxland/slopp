(ns slopp-server.mcp.runs-test
  "slopp-r2y: tests for run ids."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp-server.mcp.runs :as runs]))

(deftest a-quick-call-just-answers
  (is (= {:answer :x} (runs/in-time! (atom {}) [:t {}] (constantly :x)))))

(deftest a-slow-call-answers-with-a-run-id-and-its-answer-arrives-later
  (let [s    (atom {:answer-within-ms 20})
        gate (promise)
        id   (:running (runs/in-time! s [:t {}] #(do @gate :answer)))]
    (is (string? id))
    (testing "not finished: the status says so"
      (is (= id (:running (runs/status s id 10)))))
    (deliver gate true)
    (testing "finished: the status is the answer, and asking again gets it again"
      (is (= {:answer :answer} (runs/status s id 5000)))
      (is (= {:answer :answer} (runs/status s id 5000))))
    (testing "an id nobody issued is nil"
      (is (nil? (runs/status s "run-nope" 10))))))

(deftest the-same-call-joins-the-run-and-does-not-run-twice
  (let [s     (atom {:answer-within-ms 20})
        gate  (promise)
        calls (atom 0)
        work  #(do (swap! calls inc) @gate :answer)
        a     (runs/in-time! s [:done {:label "x"}] work)
        b     (runs/in-time! s [:done {:label "x"}] work)
        c     (runs/in-time! s [:done {:label "y"}] work)]
    (is (= (:running a) (:running b)))
    (is (not= (:running a) (:running c)) "different arguments are a different call")
    (deliver gate true)
    (is (= {:answer :answer} (runs/status s (:running a) 5000)))
    (is (= {:answer :answer} (runs/status s (:running c) 5000)))
    (is (= 2 @calls))
    (testing "once it has finished, the same call is a new call"
      (is (= {:answer :answer} (runs/in-time! (doto s (swap! assoc :answer-within-ms 5000)) [:done {:label "x"}] work)))
      (is (= 3 @calls)))))

(deftest finished-runs-do-not-pile-up-but-a-running-one-is-kept
  (let [s     (atom {:answer-within-ms 1})
        never (promise)
        slow  (:running (runs/in-time! s [:forever] #(deref never)))
        late  (fn [i] (let [g (promise)
                            id (:running (runs/in-time! s [:t i] #(deref g)))]
                        (deliver g i)
                        (:answer (runs/status s id 5000))
                        id))
        ids   (mapv late (range 12))]
    (is (= 10 (count (runs/run-ids s))) "eight finished, plus the one that just finished, plus the running one")
    (is (some #{slow} (runs/run-ids s)))
    (is (= (last ids) (last (filter (set ids) (runs/run-ids s)))))
    (deliver never true)))
