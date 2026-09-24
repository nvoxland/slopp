(ns slopp-server.dev
  "`slopp dev [dir]` — a project's DEV INSTANCE served from its store and kept
  current with every landing, with no slopp server, no registry and no agent
  needed to trip the first boot.

  The one loop the machine server runs for a managed child, on its own: a
  read-only reader on the store, the app image `slopp.webdev.live/refresh!`
  boots from the store's own declaration (`app.main` or `http.enabled`), and
  a poll that re-serves it — in place when the changed namespaces can be
  pushed, by reboot when they cannot — each time main advances. The child's
  own output is relayed to the terminal, so the startup banner a human waits
  for is the instance's, not one written on its behalf. A human asked for
  it, so it lives until stopped, not until an agent leaves. For slopp's own
  checkout the instance IS a slopp server on the ordinary port, and every
  agent on the box attaches to it."
  (:require [slopp.ops.external :as external]
            [slopp.ops :as ops]
            [slopp.ops.engine :as engine]
            [slopp.webdev.live :as live]
            [slopp.store.db :as db]))

(defn- tick!
  "One poll: when the store's data version moved and MAIN advanced, bring the
  reader current and re-serve the instance — hot when [[slopp.webdev.live/hot-refresh!]]
  can push what moved, else a reboot through [[slopp.webdev.live/refresh!]] —
  and record what happened on `state`. A commit that did not move main (a
  thread write, a pin) only advances the recorded version, so un-landed work
  never reaches the instance. The decision is [[slopp.webdev.live/reserve-decision]],
  the same one the machine server's poll makes for a managed child."
  [reader dir state say]
  (let [conn (:db @reader)
        dv   (db/data-version conn)
        {:keys [served-version served-head]} @state]
    (when (not= dv served-version)
      (let [head (db/line-head conn (engine/session-line reader))]
        (case (live/reserve-decision served-version served-head dv head)
          :reserve
          (do (engine/refresh-cache! reader)
              (let [store (:store @reader)
                    r     (or (live/hot-refresh! reader store (:app-server @reader))
                              (live/refresh! reader store dir))]
                ;; a reboot is a new child with a new pipe; a hot reload kept both
                (when (and (:serving? r) (not (:hot? r)))
                  (live/relay-output! r say))
                (swap! state assoc
                       :served-version dv :served-head head
                       :last (select-keys r [:serving? :hot? :reason :url :reloaded])
                       :refreshes (inc (:refreshes @state)))
                (say (cond (:hot? r)     (str "slopp dev: reloaded in place (" (count (:reloaded r)) " namespace(s))")
                           (:serving? r) "slopp dev: rebooted on the landed store"
                           :else         (str "slopp dev: the landed store did not come up — " (:reason r)
                                              " — the previous version keeps answering")))))
          (swap! state assoc :served-version dv))))))

(defn ^:export start!
  "Serve the dev instance of the project at `dir` and keep it current:
  `{:serving? true :url :port :state :stop!}`, or `{:serving? false :reason}`
  when the store declares no instance or it did not come up. `:state` is an
  atom `{:refreshes :last :served-head …}` — what the poll has done — and
  `:stop!` takes the instance down and closes the reader. `:poll-ms` is how
  often the store is asked whether main moved (a `PRAGMA data_version` read,
  microseconds); `:say` receives each line worth printing.

  What the machine server does for a managed child, without the machine
  server: the reader is the one it would open, the boot is the one
  [[slopp.webdev.live/refresh!]] does for it — the first serve and every
  later one are one code path there too — and the poll is [[tick!]]. The
  child is told its role exactly as a managed one is (`slopp.managed-for`,
  through the plan), so an instance that is itself a slopp server manages no
  app of its own."
  [dir & {:keys [poll-ms say] :or {poll-ms 250 say (fn [_])}}]
  (let [dir    (.getCanonicalPath (java.io.File. (str dir)))
        reader (external/open! {:slopp.ops/dir dir
                                :slopp.ops/read-only? true
                                :slopp.ops/lazy-image? true})
        r      (live/refresh! reader (:store @reader) dir)]
    (if-not (:serving? r)
      (do (ops/close! reader)
          {:serving? false :reason (:reason r)})
      (let [conn  (:db @reader)
            state (atom {:served-version (db/data-version conn)
                         :served-head    (db/line-head conn (engine/session-line reader))
                         :refreshes      0
                         :running?       true})
            poll  (doto (Thread. ^Runnable
                                 (fn []
                                   (while (:running? @state)
                                     (Thread/sleep (long poll-ms))
                                     (when (:running? @state)
                                       (try (tick! reader dir state say)
                                            (catch Throwable t
                                              (say (str "slopp dev: refresh failed — " (ex-message t))))))))
                                 "slopp-dev-refresh")
                    (.setDaemon true))]
        (.start poll)
        ;; the child's own voice — its startup banner names the address, the
        ;; pid and the record file; nothing here restates them
        (live/relay-output! r say)
        (say (str "slopp dev: serving " dir " — re-served at every landing"))
        {:serving? true
         :url      (:url r)
         :port     (:port r)
         :state    state
         :stop!    (fn []
                     (swap! state assoc :running? false)
                     (live/stop! (:app-server @reader))
                     (ops/close! reader)
                     nil)}))))

^:unsafe (defn -main
  "Run a project's dev instance: `slopp dev [dir]` (the current directory
  when none is given). Prints the address, re-serves at every landing, and
  blocks until stopped; stopping takes the instance down with it. Exits 1
  with the reason when the store declares no instance or it does not come
  up. The launcher boots this from the neutral dir, so the jar's own code is
  the runner and the project's store is what it serves."
  [& [dir]]
  (let [h (start! (or dir ".") :say #(.println System/err ^String %))]
    (if-not (:serving? h)
      (do (.println System/err (str "slopp dev: " (:reason h)))
          (System/exit 1))
      (do (.addShutdownHook (Runtime/getRuntime)
                            (Thread. ^Runnable (fn [] ((:stop! h)))))
          @(promise)))))
