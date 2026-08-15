(ns slopp.thread-test
"A thread is private until `done`, and `done` is what lands it.

  The session grain of the line model, and the only grain where isolation is
  observable at all: `slopp.store.db-test` can show that two lines materialize
  independently, but whether an AGENT's writes reach a branch before the agent
  says they are finished is a question about sessions, verdicts and the land.
  So every test here drives a real session and then asks the JOURNAL what the
  branch can see — read separately on purpose, because a session asked about
  its own work only ever agrees with itself.

  `slopp.branch-test` is the named half of the same model, and the split is
  the one the model makes: a branch is a line somebody named and merges by
  asking, a thread is a line nobody named and lands by finishing."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [slopp.ops :as ops]
            [slopp.ops.branch :as branch]
            [slopp.ops.engine :as engine]
            [slopp.ops.external :as external]
            [slopp.store.db :as db]
            [slopp.store.render :as store.render]))

(def ^:private seed "(ns th.core)\n\n(defn f [x] (inc x))\n")

(deftest ^:external landing-carries-a-threads-work-onto-its-branch
  ;; The whole point of a thread stated as an observation: the branch renders
  ;; the OLD source while the thread holds the new one, and the land is what
  ;; changes that.
  ;;
  ;; The seed is landed through a done rather than written straight to main,
  ;; and that is not ceremony — nothing can write straight to main any more.
  ;; It is also what makes the assertion below mean something: main renders
  ;; `(inc x)`, so "the branch cannot see the edit" is a contrast rather than
  ;; a statement about an empty store.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-land-" (System/nanoTime))]
    (try
      (let [setup (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "setup"})]
        (try
          (ops/ingest! setup 'th.core seed :agent "setup")
          ;; landed directly rather than through a done: the fixture is establishing
          ;; a starting state on main, not claiming a verdict about it. A done
          ;; here would run the dead-surface gate over a one-function namespace
          ;; and go red on it, which says nothing about anything below.
          (is (= "main" (:landed (branch/land-thread! setup)))
              "fixture: the seed really did reach main")
          (finally (ops/close! setup))))

      (let [sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-1"})]
        (try
          (let [conn   (:db @sess)
                trunk  (db/trunk-line-id! conn)
                thread (engine/session-line sess)]
            (is (not= trunk thread) "fixture: the session adopted a thread of its own")
            (ops/edit-replace! sess 'th.core 'f "(defn f [x] (+ x 10))"
                               :prompt "thread work" :agent "agent-1")

            (testing "the branch cannot see un-landed work"
              (is (re-find #"\(inc x\)"
                           (store.render/render-ns (db/load-store conn trunk) 'th.core))))

            (let [r (branch/land-thread! sess)]
              (is (= "main" (:landed r)))
              (is (re-find #"\(\+ x 10\)"
                           (store.render/render-ns (db/load-store conn trunk) 'th.core))
                  "and after the land it can")

              (testing "the thread is settled, and the session is on a fresh one"
                (is (= "landed" (:status (first (filter #(= thread (:id %))
                                                        (db/lines conn))))))
                (is (not= thread (:thread r)))
                (is (= (:thread r) (engine/session-line sess)))))

            (testing "and a second land with nothing written since is not a land"
              (is (nil? (branch/land-thread! sess)))))
          (finally (ops/close! sess))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external a-thread-rebases-onto-work-that-landed-while-it-worked
  ;; Two agents, one branch, one file. Both fork at the same point and work
  ;; blind to each other, which is the point of a thread; the second to reach
  ;; done finds the branch somewhere it was not, and rebases ONCE, at its own
  ;; done, with every consequence arriving together.
  ;;
  ;; Two sessions with different agent ids is the whole setup — each takes its
  ;; own thread at open, because that is what an agent id IS here.
  ;;
  ;; The assertion that matters is the one about the OTHER agent's work: a
  ;; land implemented as "point the branch at my head" would pass every
  ;; assertion about the lander and silently discard everything that landed
  ;; while it worked.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-rebase-" (System/nanoTime))]
    (try
      (let [setup (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "setup"})]
        (try
          (ops/ingest! setup 'th.core seed :agent "setup")
          ;; landed directly, not through a done — see the note in
          ;; landing-carries-a-threads-work-onto-its-branch
          (is (= "main" (:landed (branch/land-thread! setup)))
              "fixture: the seed really did reach main")
          (finally (ops/close! setup))))

      (let [a (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-a"})
            b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-b"})]
        (try
          (let [conn  (:db @a)
                trunk (db/trunk-line-id! conn)]
            (is (not= (engine/session-line a) (engine/session-line b))
                "fixture: two agents, two threads")

            (ops/edit-replace! a 'th.core 'f "(defn f [x] (+ x 10))"
                               :prompt "a works" :agent "agent-a")
            (ops/ingest! b 'th.other "(ns th.other)\n\n(defn g [] :from-b)\n" :agent "agent-b")

            (testing "B lands first — a plain fast-forward, nothing to reconcile"
              (let [r (branch/land-thread! b)]
                (is (= "main" (:landed r)))
                (is (nil? (:rebased r))
                    "the control: a fast-forward reports NO rebase, so the assertion
                     below distinguishes the two paths instead of merely observing
                     that both are green")))

            (testing "A lands second and has to rebase"
              (let [r (branch/land-thread! a)]
                (is (= "main" (:landed r)) (pr-str r))
                (is (pos? (:merged (:rebased r) 0))
                    (str "A's land RECONCILED rather than fast-forwarded — without"
                         " this the test passes for a land that never merged: "
                         (pr-str r)))
                (let [main-store (db/load-store conn trunk)]
                  (is (re-find #"\(\+ x 10\)"
                               (store.render/render-ns main-store 'th.core))
                      "A's work is on the branch")
                  (is (re-find #":from-b"
                               (store.render/render-ns main-store 'th.other))
                      "and B's work is still there — the land reconciled, it did not overwrite")))))
          (finally (ops/close! a) (ops/close! b))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external done-is-what-lands-and-a-red-one-lands-nothing
  ;; The decision this pins: a red done lands NOTHING, and the thread survives
  ;; so the work is still there to fix. Told as one narrative because the two
  ;; halves only mean something together — "nothing landed" is also true of a
  ;; land that never works, and the green done two lines later is what rules
  ;; that out.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-done-" (System/nanoTime))]
    (try
      (let [setup (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "setup"})]
        (try
          (ops/ingest! setup 'th.core seed :agent "setup")
          ;; landed directly, not through a done — see the note in
          ;; landing-carries-a-threads-work-onto-its-branch. Doubly so here:
          ;; the done under test is the one below, and a fixture that also
          ;; dones would put the interesting verdict second.
          (is (= "main" (:landed (branch/land-thread! setup)))
              "fixture: the seed really did reach main")
          (finally (ops/close! setup))))

      (let [me   "agent-1"
            sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id me})]
        (try
          (let [conn   (:db @sess)
                trunk  (db/trunk-line-id! conn)
                thread (engine/session-line sess)]
            (ops/ingest! sess 'th.core-test
                         (str "(ns th.core-test\n"
                              "  (:require [clojure.test :refer [deftest is]]\n"
                              "            [th.core :as core]))\n\n"
                              "(deftest f-adds-ten (is (= 11 (core/f 1))))\n")
                         :agent me)

            (testing "f still increments, so the done is red"
              (let [d (external/done! sess :label "red" :agent me :external? false)]
                (is (= :red (:test-status (:findings d))) (pr-str (:findings d)))
                (is (nil? (:land d)) "a red done lands nothing")
                (is (= thread (engine/session-line sess)) "and the thread survives it")
                (is (nil? (get-in (db/load-store conn trunk) [:namespaces 'th.core-test]))
                    "so the branch never saw the work at all")))

            (ops/edit-replace! sess 'th.core 'f "(defn f [x] (+ x 10))"
                               :prompt "make the test pass" :agent me)

            (testing "and the green done lands everything the thread holds"
              (let [d (external/done! sess :label "green" :agent me :external? false)]
                (is (= :green (:test-status (:findings d))) (pr-str (:findings d)))
                (is (= "main" (:landed (:land d))) (pr-str (:land d)))
                (let [main-store (db/load-store conn trunk)]
                  (is (re-find #"\(\+ x 10\)" (store.render/render-ns main-store 'th.core)))
                  (is (some? (get-in main-store [:namespaces 'th.core-test]))
                      "including the test that was written during the red episode"))
                (is (not= thread (engine/session-line sess))
                    "and the session is on a fresh thread"))))
          (finally (ops/close! sess))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external thread-list-sees-every-agent-and-a-drop-takes-the-work-with-it
  ;; The listing exists to answer "is there work here nobody is going to
  ;; finish", so it has to see across agents — an agent-scoped version could
  ;; only ever say yes about itself.
  ;;
  ;; Dropping your OWN thread is the case worth pinning. Settling the row is
  ;; the easy half; the session has to stop SHOWING the work too, or the store
  ;; goes on rendering code that no line holds — a worse state than the one
  ;; being cleaned up.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-drop-" (System/nanoTime))]
    (try
      (let [setup (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "setup"})]
        (try
          (ops/ingest! setup 'th.core seed :agent "setup")
          (is (= "main" (:landed (branch/land-thread! setup))) "fixture: the seed reached main")
          (finally (ops/close! setup))))

      (let [a (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-a"})
            b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-b"})]
        (try
          (ops/edit-replace! a 'th.core 'f "(defn f [x] (+ x 10))" :prompt "a" :agent "agent-a")
          (ops/edit-replace! b 'th.core 'f "(defn f [x] (+ x 20))" :prompt "b" :agent "agent-b")

          (testing "the listing carries every agent, and marks which one is yours"
            (let [l  (branch/thread-list a)
                  by (into {} (map (juxt :agent identity)) (:threads l))]
              (is (= "main" (:branch l)))
              (is (= #{"setup" "agent-a" "agent-b"} (set (keys by))))
              (is (true? (:mine (by "agent-a"))))
              (is (nil? (:mine (by "agent-b")))
                  "and does NOT mark somebody else's — the flag is the whole point")
              (is (every? pos? (map :unlanded [(by "agent-a") (by "agent-b")]))
                  "each writer has written since it forked")
              (is (zero? (:unlanded (by "setup")))
                  "and the session that LANDED left an empty thread behind: a land
                   settles one line and adopts another, so zero-unlanded is the
                   ordinary resting state rather than a leak")))

          (testing "dropping somebody else's leaves yours where it was"
            (let [bid (engine/session-line b)
                  r   (branch/thread-drop! a bid)]
              (is (= bid (:dropped r)) (pr-str r))
              (is (= "agent-b" (:agent r)))
              (is (not (contains? (set (mapv :id (:threads (branch/thread-list a)))) bid))
                  "B's thread is off the live listing")
              (is (contains? (set (mapv :id (:threads (branch/thread-list a))))
                             (engine/session-line a))
                  "and yours is still on it — the control, since an empty listing
                   would satisfy the assertion above")))

          (testing "and dropping your OWN takes the work off your store"
            (let [mine (engine/session-line a)
                  r    (branch/thread-drop! a mine)]
              (is (= mine (:dropped r)) (pr-str r))
              (is (not= mine (:thread r)) "you are on a fresh thread")
              (is (= (:thread r) (engine/session-line a)))
              (is (re-find #"\(inc x\)" (store.render/render-ns (:store @a) 'th.core))
                  "the session renders the branch again, not the work it just dropped")))

          (testing "a branch is not a thread, and the refusal says which it is"
            (let [r (branch/thread-drop! a (db/trunk-line-id! (:db @a)))]
              (is (re-find #"branch" (:error r)) (pr-str r))))
          (finally (ops/close! a) (ops/close! b))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))
