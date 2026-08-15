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
  ;; changes that. Both halves matter — a land that worked against a branch
  ;; which could already see the work would prove nothing about isolation.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-land-" (System/nanoTime))]
    (try
      (let [sess (external/open! {:slopp.ops/dir dir})]
        (try
          (ops/ingest! sess 'th.core seed)
          (let [conn   (:db @sess)
                trunk  (db/trunk-line-id! conn)
                thread (db/adopt-thread! conn trunk "agent-1")]
            ;; 4b does this on the session's behalf. Until it does, the test
            ;; puts the session on its thread by hand — which is exactly the
            ;; state the land has to handle, and doing it here is what keeps
            ;; the land buildable while the default is still off.
            (swap! sess assoc :line thread)
            (ops/edit-replace! sess 'th.core 'f "(defn f [x] (+ x 10))"
                               :prompt "thread work")

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
  ;; The assertion that matters is the one about the OTHER agent's work: a
  ;; land implemented as "point the branch at my head" would pass every
  ;; assertion about the lander and silently discard everything that landed
  ;; while it worked.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-rebase-" (System/nanoTime))]
    (try
      (let [setup (external/open! {:slopp.ops/dir dir})]
        (ops/ingest! setup 'th.core seed)
        (ops/close! setup))
      (let [a (external/open! {:slopp.ops/dir dir})
            b (external/open! {:slopp.ops/dir dir})]
        (try
          (let [conn  (:db @a)
                trunk (db/trunk-line-id! conn)
                ta    (db/adopt-thread! conn trunk "agent-a")
                tb    (db/adopt-thread! conn trunk "agent-b")]
            (is (not= ta tb) "fixture: two agents, two threads")
            (swap! a assoc :line ta)
            (swap! b assoc :line tb)

            (ops/edit-replace! a 'th.core 'f "(defn f [x] (+ x 10))" :prompt "a works")
            (ops/ingest! b 'th.other "(ns th.other)\n\n(defn g [] :from-b)\n")

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
      (let [sess (external/open! {:slopp.ops/dir dir})]
        (try
          (let [me     (:agent-id @sess)
                _      (ops/ingest! sess 'th.core seed :agent me)
                conn   (:db @sess)
                trunk  (db/trunk-line-id! conn)
                thread (db/adopt-thread! conn trunk me)]
            (swap! sess assoc :line thread)
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
