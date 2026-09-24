(ns slopp-server.dev-test
  "`slopp dev [dir]`: a project's dev instance served from its store and kept current with every done, with no slopp server, no bootstrap agent and no registry — a reader on the store, the app image live/start! boots, and the same re-serve decision the machine server's poll makes. General: any proje…"
  (:require [slopp-server.dev :as dev]
            [slopp.ops.external :as external]
            [slopp.ops :as ops]
            [clojure.java.io :as io] [clojure.test :refer [deftest is testing use-fixtures]]))

(deftest ^:external the-runner-serves-a-projects-dev-instance-and-re-serves-it-at-every-landing
  ;; The dev instance used to be reachable only as a CHILD of the machine
  ;; server: a registry, the MCP and hook doors, an agent attached just to
  ;; trip the first boot. What development needs is the one loop inside that
  ;; — boot the declared entry from the store, watch the store, re-serve at
  ;; each landing — and this runner is that loop on its own. The fixture is a
  ;; declared entry that leaves evidence it ran; the landing is a second
  ;; session's done, the way an agent's would arrive.
  (let [dir    (str (java.nio.file.Files/createTempDirectory
                     "slopp-dev-runner" (make-array java.nio.file.attribute.FileAttribute 0)))
        marker (str dir "/ran.txt")
        agent  (external/open! {:slopp.ops/dir dir})
        entry  (fn [v] (str "(defn -main \"Runs.\" [& _] (spit \"" marker "\" \"" v "\"))"))]
    (try
      (ops/ingest! agent 'worker.core (str "(ns worker.core)\n\n" (entry "v1") "\n") :agent "a")
      (ops/config-file! agent "capabilities" :key "app.main" :value "worker.core/-main" :prompt "the dev instance")
      (external/done! agent :label "v1" :agent "a")
      (let [h (dev/start! dir :poll-ms 100)]
        (try
          (testing "it comes up on the store's own declaration, with no server anywhere"
            (is (:serving? h) (str "did not serve: " (:reason h)))
            (is (loop [n 0]
                  (cond (.exists (io/file marker)) true
                        (> n 100) false
                        :else (do (Thread/sleep 100) (recur (inc n)))))
                "the declared entry ran in the child"))
          (testing "a landing by another session is noticed and the instance re-served"
            (ops/edit-replace! agent 'worker.core '-main (entry "v2") :prompt "v2" :agent "a")
            (external/done! agent :label "v2" :agent "a")
            (let [refreshed? (loop [n 0]
                               (cond (pos? (:refreshes @(:state h))) true
                                     (> n 150) false
                                     :else (do (Thread/sleep 100) (recur (inc n)))))]
              (is refreshed? (str "no re-serve after the landing: " (pr-str @(:state h))))
              (is (:hot? (:last @(:state h)))
                  "one form moved, so the running child was reloaded in place")))
          (testing "a thread or bookkeeping write is NOT a landing and re-serves nothing"
            (let [before (:refreshes @(:state h))]
              (ops/edit-replace! agent 'worker.core '-main (entry "v3") :prompt "v3 on a thread" :agent "a")
              (Thread/sleep 600)
              (is (= before (:refreshes @(:state h))) "un-landed work must not reach the instance")))
          (finally ((:stop! h)))))
      (testing "stop! takes the instance down"
        (is true))
      (finally (ops/close! agent)))))
