(ns slopp-server.mcp.runs
  "A tool call that outlives the client's patience. A client gives a call about 60 s and then reports a timeout, while the work carries on here: the caller is left not knowing whether a write landed. So the wire waits a little less than that, and past it answers with a run id; the run keeps going an…")

(def ^:private default-answer-within-ms
  "How long a call may hold the wire. Claude Code gives a tool call 60 s, so this
  leaves room for the answer to travel."
  50000)

(defn run-ids
  "The runs this session remembers, oldest first."
  [session]
  (mapv key (sort-by (comp :n val) (::runs @session))))

(defn- remember!
  "Record a run past the wire's wait. A run still going is always kept; of those
  that finished only the newest eight are, so a session cannot accumulate answers."
  [session id entry]
  (swap! session update ::runs
         (fn [runs]
           (let [runs     (assoc (or runs {}) id entry)
                 finished (->> runs
                               (filter (comp realized? :fut val))
                               (sort-by (comp :n val))
                               (map key))]
             (apply dissoc runs (drop-last 8 finished))))))

(defn in-time!
  "Run `work` for the call named `call-key` and wait for it within the session's
  limit: `{:answer value}` when it finished, else `{:running id}` while it carries
  on. The same call still running is JOINED rather than started again, because a
  caller told only that its write is slow will send it again, and running a write
  twice is the thing that must not happen."
  [session call-key work]
  (let [within (:answer-within-ms @session default-answer-within-ms)
        joined (some (fn [[id {:keys [key fut]}]]
                       (when (and (= key call-key) (not (realized? fut))) [id fut]))
                     (::runs @session))
        fut    (or (second joined) (future (work)))
        r      (deref fut within ::late)]
    (if (= ::late r)
      (if joined
        {:running (first joined)}
        (let [n  (::next-run (swap! session update ::next-run (fnil inc 0)))
              id (str "run-" n)]
          (remember! session id {:key call-key :fut fut :n n})
          {:running id}))
      {:answer r})))

(defn status
  "What became of run `id`, waiting up to `wait-ms`: `{:answer value}`,
  `{:running id}` when it has not finished, nil when no such run is remembered."
  [session id wait-ms]
  (when-let [{:keys [fut]} (get-in @session [::runs id])]
    (let [r (deref fut wait-ms ::late)]
      (if (= ::late r) {:running id} {:answer r}))))
