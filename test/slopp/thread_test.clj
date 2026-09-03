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
            [slopp.store.render :as store.render] [next.jdbc :as jdbc] [slopp.store :as store]))

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

          (testing "and dropping your OWN — no id needed — takes the work off your store"
            (let [mine (engine/session-line a)
                  ;; nil id means MINE. The start-over case must not require a
                  ;; thread_list round trip to name the thing you are sitting in.
                  r    (branch/thread-drop! a nil)]
              (is (= mine (:dropped r)) (str "no argument dropped the thread you are on: "
                                             (pr-str r)))
              (is (not= mine (:thread r)) "you are on a fresh thread")
              (is (= (:thread r) (engine/session-line a)))
              (is (re-find #"\(inc x\)" (store.render/render-ns (:store @a) 'th.core))
                  "the session renders the branch again, not the work it just dropped")))

          (testing "a branch is not a thread, and the refusal says which it is"
            (let [r (branch/thread-drop! a (db/trunk-line-id! (:db @a)))]
              (is (re-find #"branch" (:error r)) (pr-str r))))
          (finally (ops/close! a) (ops/close! b))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external a-rebase-re-earns-the-whole-verdict-not-just-the-merged-namespaces
  ;; The failure this rules out is the one the design called invisible if got
  ;; wrong: a green that describes a state which never existed on the branch.
  ;;
  ;; A's work is green against the code A forked from. B changes that code and
  ;; lands. When A lands, the rebase brings B's change in — and the test that
  ;; breaks is in A's OWN namespace, which the merge never touches. Verifying
  ;; only what the merge carried would report green and advance the branch to a
  ;; state where nothing passes.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-reearn-" (System/nanoTime))]
    (try
      (let [setup (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "setup"})]
        (try
          (ops/ingest! setup 'th.core seed :agent "setup")
          (is (= "main" (:landed (branch/land-thread! setup))) "fixture: the seed reached main")
          (finally (ops/close! setup))))

      (let [a (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-a"})
            b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-b"})]
        (try
          ;; A builds on f as it stands, and is green about it
          (ops/module-dep! a "th.dep" "th.core" :prompt "fixture edge" :agent "agent-a")
          (is (pos? (:forms (ops/ingest! a 'th.dep
                                         (str "(ns th.dep\n"
                                              "  (:require [clojure.test :refer [deftest is]]\n"
                                              "            [th.core :as c]))\n\n"
                                              "(defn g [x] (c/f x))\n\n"
                                              "(deftest g-t (is (= 2 (g 1))))\n")
                                         :agent "agent-a")))
              "fixture: A's namespace really landed in A's thread")

          ;; B changes f underneath, in a namespace A's test does not live in
          (ops/edit-replace! b 'th.core 'f "(defn f [x] (+ x 10))"
                             :prompt "b changes the meaning of f" :agent "agent-b")
          (is (= "main" (:landed (branch/land-thread! b))) "fixture: B landed first")

          (testing "A's land rebases, finds its own test red against B's change, and refuses"
            (let [r (branch/land-thread! a)]
              (is (false? (:landed r)) (pr-str r))
              (is (pos? (+ (:fail (:test r) 0) (:error (:test r) 0))) (pr-str r))))

          (testing "so the branch still holds only what was verified"
            (let [conn (:db @a)
                  main (db/load-store conn (db/trunk-line-id! conn))]
              (is (re-find #"\(\+ x 10\)" (store.render/render-ns main 'th.core))
                  "B's landed work is there")
              (is (nil? (get-in main [:namespaces 'th.dep]))
                  "and A's is not — it never earned a verdict against B's code")))

          (testing "and A's thread survives, holding the rebased state to fix"
            (is (some? (get-in (:store @a) [:namespaces 'th.dep])))
            (is (re-find #"\(\+ x 10\)" (store.render/render-ns (:store @a) 'th.core))
                "the rebase DID happen — A is looking at B's change, which is what
                 makes the red actionable rather than mysterious"))
          (finally (ops/close! a) (ops/close! b))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external a-session-re-adopts-when-something-else-SETTLED-its-line
  ;; THE orphaning root cause, in one test.
  ;;
  ;; `engine/session-line` caches the line id on the session and never
  ;; re-checks it — "the row is touched once per session rather than once per
  ;; call". `adopt-thread!` is careful never to hand back a settled line, but
  ;; nothing re-asks once the answer is cached.
  ;;
  ;; And another process settles it routinely. `adopt-thread!`'s own docstring
  ;; names it: the plugin's Stop hook runs `done` through a ONE-SHOT process
  ;; carrying this session's own agent id, so on every session pause a second
  ;; process adopts this line, lands it, and settles it. The long-lived server
  ;; then keeps writing to a line the registry has finished with.
  ;;
  ;; Observed live: `session_brief` reporting 20 un-landed while `thread_list`
  ;; reported 0 for the line it named and `thread_drop` refused both with
  ;; "already landed" — work that could go neither forward nor back.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-settled" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'th.settled "(ns th.settled)\n(defn a \"A.\" [] 1)\n")
      (let [conn (:db @sess)
            line (engine/session-line sess)
            status-of (fn [id] (:lines/status
                                (first (jdbc/execute!
                                        conn ["SELECT status FROM lines WHERE id = ?" id]))))]

        (testing "fixture: the session has adopted an open line"
          (is (some? line))
          (is (= "open" (status-of line))))

        ;; what the Stop hook's one-shot does, minus the JVM
        (jdbc/execute! conn ["UPDATE lines SET status = 'landed' WHERE id = ?" line])

        (testing "the session does NOT keep writing to the settled line"
          (let [now (engine/session-line sess)]
            (is (not= line now)
                (str "still on the settled line " line
                     " — every write from here lands nowhere and cannot be dropped"))
            (is (= "open" (status-of now)) "re-adopted a line that is not open")))

        (testing "and the session's cache now names the line it actually writes to"
          ;; the three-readers symptom: session value, registry and drop path
          ;; each resolving `my thread` differently is what made this invisible
          (is (= (:line @sess) (engine/session-line sess)))))
      (finally (ops/close! sess)))))

(deftest ^:external another-agents-red-does-not-hold-my-thread
  ;; The deadlock this closes, measured over one night of two agents on one
  ;; store: the trunk goes red for agent A's reasons, and from that moment
  ;; NOBODY can land — including the agent carrying the fix. Twenty changes
  ;; sat on one thread, held by one red test, with the fix for that red
  ;; inside the twenty.
  ;;
  ;; The bargain is unchanged where it is load-bearing: the store is red,
  ;; done SAYS red, and no commit-point can be taken. What changes is the
  ;; attribution — a failing test whose trace is disjoint from everything
  ;; this episode touched is evidence about somebody else's work, and it
  ;; is not a verdict on mine.
  ;;
  ;; `g` is ^:unused-ok because the fixture needs exactly ONE red: the
  ;; unused-public gate would otherwise fail B's done on B's own form, and
  ;; the test would pass or fail for a reason that has nothing to do with
  ;; whose red it is.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-foreign-red-" (System/nanoTime))]
    (try
      (let [setup (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "setup"})]
        (try
          (ops/ingest! setup 'fr.core
                       (str "(ns fr.core (:require [clojure.test :refer [deftest is]]))\n"
                            "(defn f \"F.\" [x] (inc x))\n"
                            "(deftest f-t (is (= 2 (f 1))))\n")
                       :agent "setup")
          (ops/ingest! setup 'fr.other
                       "(ns fr.other)\n\n(defn ^:unused-ok g \"G.\" [] :ok)\n"
                       :agent "setup")
          (ops/edit-replace! setup 'fr.core 'f "(defn f \"F.\" [x] (+ x 2))"
                             :prompt "break f — this red is setup's" :agent "setup")
          ;; landed directly rather than through a done, because a red done
          ;; landing nothing is precisely the thing under test
          (is (= "main" (:landed (branch/land-thread! setup)))
              "fixture: the red really did reach main")
          (finally (ops/close! setup))))

      (let [b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-b"})]
        (try
          (ops/edit-replace! b 'fr.other 'g "(defn ^:unused-ok g \"G.\" [] :still-ok)"
                             :prompt "work that touches nothing f-t reaches" :agent "agent-b")
          (let [r    (external/done! b :label "b finishes" :agent "agent-b")
                fnd  (:findings r)
                attr (:red-attribution fnd)]
            (testing "the red is still reported as a red — this launders nothing"
              (is (= :red (:test-status fnd)) (pr-str fnd)))
            (testing "and done says whose it is"
              (is (= ['fr.core/f-t] (:foreign attr)) (pr-str attr))
              (is (empty? (:mine attr)) (pr-str attr))
              (is (empty? (:untraced attr)) (pr-str attr)))
            (testing "so B's work lands instead of waiting for someone else's fix"
              (is (= "main" (:landed (:land r))) (pr-str r))
              (let [main-store (db/load-store (:db @b) (db/trunk-line-id! (:db @b)))]
                (is (re-find #":still-ok" (store.render/render-ns main-store 'fr.other))
                    "B's work is on the branch"))))
          (finally (ops/close! b))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external a-module-edge-survives-another-agents-landing
  ;; Measured on this store 2026-08-28: three `module_dep` edges were declared
  ;; and landed, and hours later were GONE from the trunk while still present
  ;; in the declaring session's store. The other 52 modules survived, so it was
  ;; not the manifest vanishing — it was those rows.
  ;;
  ;; It is silent in both directions at once. The declaring agent's
  ;; `full_check` reads its OWN session and stays green; another agent's reads
  ;; the trunk and goes red on twenty undeclared edges belonging to somebody
  ;; who cannot see the loss. `commit_point` gates on the whole-store verdict,
  ;; so the second agent is blocked by a fact the first one's tools deny.
  ;;
  ;; The fold is already edge-grained and `merge-logs` already unions
  ;; concurrent declarations (`modules-test/module-edges-are-crdt-grain`), so
  ;; this exercises the path those two tests do not: a real LAND, across two
  ;; sessions on one journal.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-medge-" (System/nanoTime))]
    (try
      (let [setup (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "setup"})]
        (try
          (ops/ingest! setup 'md.one "(ns md.one)\n\n(defn ^:unused-ok f \"F.\" [] 1)\n"
                       :agent "setup")
          (ops/ingest! setup 'md.two "(ns md.two)\n\n(defn ^:unused-ok g \"G.\" [] 2)\n"
                       :agent "setup")
          (is (= "main" (:landed (branch/land-thread! setup)))
              "fixture: the seed reached main")
          (finally (ops/close! setup))))

      (let [a (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-a"})
            b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-b"})]
        ;; both forked from the same head, which is what makes them concurrent
        (try
          (ops/module-dep! a "md.one" "md.two" :prompt "a declares an edge" :agent "agent-a")
          (let [r (external/done! a :label "a declares an edge"
                                :agent "agent-a" :external? false)]
            (is (= "main" (:landed (:land r)))
                (str "fixture: A's declaration reached main: " (pr-str (:land r))))
            (testing "done CONFIRMS the declaration reached the branch"
              ;; the declaration twin of :landed-gap, and it reads the BRANCH.
              ;; A session that declared an edge reports it present whether or
              ;; not it landed — which is exactly how the measured loss stayed
              ;; invisible to the agent who caused it.
              (is (nil? (:declared-gap r))
                  (str "the edge landed, so nothing is owed: " (pr-str r)))))

          (testing "the edge is on the branch once A lands"
            (let [conn (:db @a)
                  main (db/load-store conn (db/trunk-line-id! conn))]
              (is (contains? (get (:modules main) "md.one") "md.two")
                  (pr-str (:modules main)))))

          ;; B forked BEFORE A's declaration existed and now lands its own work
          (ops/edit-replace! b 'md.two 'g "(defn ^:unused-ok g \"G.\" [] 22)"
                             :prompt "b works, touching nothing of A's" :agent "agent-b")
          (is (= "main" (:landed (branch/land-thread! b)))
              "fixture: B's work reached main")

          (testing "and A's edge is STILL on the branch after B lands over it"
            (let [conn (:db @b)
                  main (db/load-store conn (db/trunk-line-id! conn))]
              (is (contains? (get (:modules main) "md.one") "md.two")
                  (str "B's landing dropped an edge it never touched — this is the"
                       " loss, and nothing reports it: " (pr-str (:modules main))))))
          (finally (ops/close! a) (ops/close! b))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external a-conflicted-land-is-resolvable-in-the-thread
  ;; s16 overlap probe, measured: two sessions replaced the same form; B's
  ;; land refused with the MV conflict (right so far), B rewrote the form to
  ;; incorporate both sides — the refusal's own instruction, "resolve, then
  ;; call done again" — and the next land recomputed the IDENTICAL conflict
  ;; from the fork point. And the next, and the next: four refusals citing
  ;; the same theirs-delta while :ours already carried the resolution. The
  ;; only exit an agent found was thread_drop and a manual replay. A refusal
  ;; whose recovery path cannot succeed is a deadlock wearing a helpful
  ;; sentence: once the agent writes a version that post-dates the refused
  ;; merge, the conflict IS resolved, and the land must say so.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slopp-mv-resolve-" (System/nanoTime))]
    (try
      (let [setup (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "setup"})]
        (try
          (ops/ingest! setup 'mv.core
                       (str "(ns mv.core (:require [clojure.test :refer [deftest is]]))\n"
                            "(defn f \"F.\" [x] {:base x})\n"
                            "(deftest f-t (is (= {:base 1} (f 1))))\n")
                       :agent "setup")
          (is (= "main" (:landed (branch/land-thread! setup))) "fixture: seed reached main")
          (finally (ops/close! setup))))
      (let [a (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-a"})
            b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "agent-b"})]
        (try
          (ops/edit-replace! a 'mv.core 'f "(defn f \"F.\" [x] {:base x :fees 1})"
                             :prompt "a: add :fees" :agent "agent-a")
          (ops/edit-replace! a 'mv.core 'f-t "(deftest f-t (is (= {:base 1 :fees 1} (f 1))))"
                             :prompt "a: cover :fees" :agent "agent-a")
          (is (= "main" (:landed (branch/land-thread! a))) "A lands first")
          (ops/edit-replace! b 'mv.core 'f "(defn f \"F.\" [x] {:base x :grams 2})"
                             :prompt "b: add :grams" :agent "agent-b")
          (ops/edit-replace! b 'mv.core 'f-t "(deftest f-t (is (= {:base 1 :grams 2} (f 1))))"
                             :prompt "b: cover :grams" :agent "agent-b")
          (let [r1 (branch/land-thread! b)]
            (is (false? (:landed r1)) "B's land refuses: same-form divergence")
            (is (seq (:conflicts r1)) (pr-str (dissoc r1 :reason)))
            (ops/edit-replace! b 'mv.core 'f "(defn f \"F.\" [x] {:base x :fees 1 :grams 2})"
                               :prompt "b: resolve — both sides" :agent "agent-b")
            (ops/edit-replace! b 'mv.core 'f-t "(deftest f-t (is (= {:base 1 :fees 1 :grams 2} (f 1))))"
                               :prompt "b: cover both" :agent "agent-b")
            (let [r2 (branch/land-thread! b)]
              (is (= "main" (:landed r2))
                  (str "the refusal's own instruction must be followable: " (pr-str (dissoc r2 :reason))))
              (let [main-store (db/load-store (:db @b) (db/trunk-line-id! (:db @b)))
                    src (store.render/render-ns main-store 'mv.core)]
                (is (re-find #":fees 1" src) "A's side survives")
                (is (re-find #":grams 2" src) "B's resolution is what landed"))))
          (finally (ops/close! a) (ops/close! b))))
      (finally (clojure.java.shell/sh "rm" "-rf" dir)))))

(deftest ^:external a-fresh-thread-holds-no-copy-until-it-writes
  ;; The fresh thread a done leaves a session on copied its branch's whole
  ;; view at fork — thousands of rows, a full copy of the store — before it
  ;; had written anything. On slopp's own store 138 such threads were 85% of
  ;; a 2.1 GB file. Now the view is the thread's on its first write: until
  ;; then it has no rows and reads its branch's; after, it holds the WHOLE
  ;; value, not just the namespace it touched, so a resume sees everything.
  (let [dir  (str (java.nio.file.Files/createTempDirectory "fw" (make-array java.nio.file.attribute.FileAttribute 0)))
        a    (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "alice"})
        rows (fn [sess line] (:n (next.jdbc/execute-one! (:db @sess) ["SELECT COUNT(*) AS n FROM elements WHERE line = ?" line])))
        nss  (fn [sess line] (:n (next.jdbc/execute-one! (:db @sess) ["SELECT COUNT(DISTINCT ns) AS n FROM elements WHERE line = ?" line])))]
    (try
      (is (nil? (:error (ops/ingest! a 'fw.core "(ns fw.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n"))))
      (is (nil? (:error (ops/ingest! a 'fw.util "(ns fw.util)\n(defn ^:unused-ok u \"U.\" [] 2)\n"))))
      (is (nil? (:error (external/done! a :label "land" :agent "alice"))))
      (let [fresh (slopp.ops.engine/session-line a)]
        (testing "after the land, the fresh thread holds NO copy"
          (is (zero? (rows a fresh)) "no element rows for a thread that has written nothing"))
        (testing "and still reads its branch whole"
          (is (some? (get-in (:store @a) [:namespaces 'fw.core])))
          (is (some? (get-in (:store @a) [:namespaces 'fw.util]))))
        (testing "a BOOKKEEPING delta is not a write: a turn marker leaves it rowless"
          ;; the first marker after a done used to copy the whole store onto
          ;; the thread, which is the 138-copies problem back through the side door
          (slopp.ops.engine/commit-appended! a #(first (slopp.store/record-turn % :turn-begin :agent "alice" :intent "a look")) [])
          (is (not= (slopp.store.db/line-head (:db @a) fresh) (slopp.store.db/line-base (:db @a) fresh)) "fixture: the marker moved the head")
          (is (zero? (rows a fresh)) "still no element rows"))
        (testing "a returning session adopts the rowless thread and sees everything"
          (let [a2 (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "alice"})]
            (try
              (is (some? (get-in (:store @a2) [:namespaces 'fw.util])))
              (finally (ops/close! a2)))))
        (testing "its FIRST write gives it the whole view, not only the namespace it touched"
          (is (nil? (:error (ops/add-form! a 'fw.core "(defn ^:unused-ok g \"G.\" [] 3)" :prompt "first write" :agent "alice"))))
          (is (= 2 (nss a fresh)) "both namespaces materialized for the thread")
          (is (pos? (rows a fresh))))
        (testing "and it lands as any thread does"
          (is (nil? (:error (external/done! a :label "land again" :agent "alice"))))))
      (finally (ops/close! a)))))

(deftest ^:external a-rowless-thread-follows-its-branch-until-it-writes
  ;; A thread with no writes has nothing to pin: when the branch moves under
  ;; it, it follows — the same state a freshly minted thread would start
  ;; from — and its first write then lands cleanly on the moved branch. A
  ;; thread WITH writes keeps its pinned view exactly as before.
  (let [dir (str (java.nio.file.Files/createTempDirectory "fb" (make-array java.nio.file.attribute.FileAttribute 0)))
        a   (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "alice"})]
    (try
      (is (nil? (:error (ops/ingest! a 'fb.core "(ns fb.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n"))))
      (is (nil? (:error (external/done! a :label "land" :agent "alice"))))
      ;; alice is on a rowless thread; bob moves the branch
      (let [b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "bob"})]
        (try
          (is (nil? (:error (ops/ingest! b 'fb.other "(ns fb.other)\n(defn ^:unused-ok o \"O.\" [] 2)\n"))))
          (is (nil? (:error (external/done! b :label "bob lands" :agent "bob"))))
          (finally (ops/close! b))))
      (testing "alice's rowless thread follows the branch"
        (ops/sync-with-journal! a)
        (is (some? (get-in (:store @a) [:namespaces 'fb.other])) "bob's namespace is visible without a write of alice's own"))
      (testing "and her first write lands cleanly on the moved branch"
        (is (nil? (:error (ops/add-form! a 'fb.core "(defn ^:unused-ok g \"G.\" [] 3)" :prompt "after the move" :agent "alice"))))
        (let [r (external/done! a :label "alice lands" :agent "alice")]
          (is (nil? (:error r)) (pr-str (select-keys r [:error :findings])))
          (is (= "main" (get-in r [:land :landed])) (pr-str (:land r)))))
      (finally (ops/close! a)))))

(deftest ^:external a-thread-with-a-stale-copied-view-and-no-work-follows-its-branch
  ;; Found 2026-09-03 on slopp's own store. A thread minted BEFORE fork on
  ;; write holds a full copied view. Left open with nothing of its own but the
  ;; Stop hook's done markers, it was re-adopted after a restart and its
  ;; two-day-old copy was served as "main": the live server ran code 1,238
  ;; deltas behind, and every read was answered from it. Rows are not a pin;
  ;; WORK is. A thread with no un-landed content follows its branch whether
  ;; or not it carries rows.
  (let [dir (str (java.nio.file.Files/createTempDirectory "sv" (make-array java.nio.file.attribute.FileAttribute 0)))
        a   (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "alice"})
        line   (atom nil)
        branch (atom nil)]
    (try
      (is (nil? (:error (ops/ingest! a 'sv.core "(ns sv.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n"))))
      (is (nil? (:error (external/done! a :label "land" :agent "alice"))))
      ;; give alice's fresh thread the pre-fork-on-write shape: a copied view
      ;; of the branch with no write of its own in it
      (reset! line (engine/session-line a))
      (reset! branch (engine/session-branch-line a))
      (db/copy-view! (:db @a) @branch @line)
      (is (db/line-has-view? (:db @a) @line) "fixture: the thread carries a copied view")
      ;; bob moves the branch
      (let [b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "bob"})]
        (try
          (is (nil? (:error (ops/ingest! b 'sv.other "(ns sv.other)\n(defn ^:unused-ok o \"O.\" [] 2)\n"))))
          (is (nil? (:error (external/done! b :label "bob lands" :agent "bob"))))
          (finally (ops/close! b))))
      (finally (ops/close! a)))
    ;; alice returns in a new process and is re-adopted onto the same thread
    (let [a2   (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "alice"})
          conn (:db @a2)]
      (try
        (is (= @line (engine/session-line a2)) "fixture: the same thread was re-adopted")
        (ops/sync-with-journal! a2)
        (is (some? (get-in (:store @a2) [:namespaces 'sv.other]))
            "bob's namespace is visible: the stale copy was not served as the branch")
        (is (= (db/line-head conn @branch) (db/line-base conn @line))
            "the thread re-forked at the branch head")
        (is (not (db/line-has-view? conn @line)) "and its stale rows are gone — it reads the branch until it writes")
        (finally (ops/close! a2))))))
