(ns slopp-server.process.hooks
  "What the plugin's hooks decide, as pure functions the server's hook
  endpoint calls — so a Claude Code hook is one shell call that posts its
  payload and prints the answer, and the rules live here, tested, rather
  than in a script beside the plugin. Three hooks: the prompt hook (is
  this an ask, what goes in the mailbox, what context rides in with it),
  the Bash smell hook (a command that routes around the store), and the
  CLI's text frame (`slopp <op>` and the `add`/`replace`/`change` verbs
  ship a header block and a payload; this turns that into the call the
  write door takes)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]))

(defn system-turn?
  "A system continuation — a task notification, a hook wake-up — fires the
  prompt hook too. It is not an ask, and recording it once overwrote the
  human's ask in the mailbox (found 2026-09-04), so it is decided before
  anything is written."
  [prompt]
  (let [p (str/triml (str prompt))]
    (or (str/starts-with? p "<")
        (str/includes? p "task-notification"))))

(def max-chars
  "The most a prompt answer may carry: the bundle is seeds with source plus
  cards, never a whole namespace."
  10000)

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn tail-context
  "The lines that ride along with every prompt, from what the server knows of
  the project: the store line (namespaces, last commit point), the agent's
  thread — printed EVERY ask rather than once, because a compaction keeps
  whatever it keeps and this is the one line that must survive it — the
  recent asks (system turns and repeats dropped), the standing whole-store
  verdict when nothing has moved since it, and a red last done."
  [{:keys [namespaces last-commit sid recent-asks standing-verdict last-done-failures]}]
  (let [asks (->> recent-asks
                  (remove system-turn?)
                  (map #(clip % 110))
                  distinct
                  (take 5))]
    (cond-> [(str "[slopp] live store here: " namespaces " namespaces; last commit point: "
                  (clip (or last-commit "none yet") 80)
                  ". Work through the slopp tools — the store is the source, not the files.")]
      (not (str/blank? (str sid)))
      (conj (str "thread: " sid " — pass {thread \"" sid "\"} on every write; a read passing it"
                 " sees your un-landed work; thread_open mints another for a subagent or a"
                 " second line of work."))
      (seq asks)
      (conj (str "recent asks here: " (str/join " | " asks)))
      standing-verdict
      (conj (str "standing verdict: the whole-store full_check is "
                 (case standing-verdict :red "RED" :green "GREEN" "recorded")
                 " and STANDS — nothing has changed since. done re-verifies your episode"
                 " itself; no closing full_check or test_run is needed."))
      (pos? (or last-done-failures 0))
      (conj (str "HEADS-UP: the last done-point left " last-done-failures
                 " failing test(s) — session_brief :last-done has details.")))))

(defn prompt-answer
  "What the prompt hook prints: the store line, the bundle, the rest of the
  tail — capped. A bundle that leads with `[slopp]` carries its own store
  line and replaces the tail's."
  [bundle tail]
  (let [b     (when-not (str/blank? (str bundle)) bundle)
        parts (concat (if (and b (str/starts-with? b "[slopp]")) [] (take 1 tail))
                      (when b [b])
                      (rest tail))]
    (clip (str/join "\n" (remove str/blank? parts)) max-chars)))

(def raw-db-pattern #"sqlite3\s+[^|;&]*store\.db")

(def raw-db-reason
  (str "[slopp] Reading .slopp/store.db with sqlite3 is blocked. The journal is "
       "the record of truth and a direct read can see torn or uncommitted state. "
       "The store answers this itself: "
       "query_store {code \"(fn [store] ...)\"} for read-only analysis over the "
       "immutable store VALUE (deltas, namespaces, metadata sweeps); "
       "report for commit points + per-form changes with their recorded asks, the "
       "verbatim user :intents, and :code (the follow-up that carries source); "
       "query_history {contains X} / {dead_ends true} for the asks and the "
       "scrapped explorations. "
       "NO MCP TOOLS AVAILABLE (server not connected)? Those same tools run "
       "from the shell against the server: slopp query_store '{\"code\":\"...\"}', "
       "slopp report '{}'. That is the fallback — not sqlite3. "
       "If you truly need the raw file for store forensics, do it outside a "
       "slopp session."))

(def bash-smells
  "The advisory smells: [key pattern message]. These have legitimate uses —
  the git projection is real and a user may want it — so they nudge after
  the command ran rather than block it."
  [[:git-archaeology #"\bgit\s+(-\S+\s+)*(log|diff|show|blame)\b"
    (str "[slopp] history lives in the store: query_changes {from \"start\"} gives "
         "every form's :was/:now across the lifetime (or {from \"last-commit\"}), "
         "format=text for line diffs; report {since} composes the summary — the "
         "git slopp branch is only a projection")]
   [:file-source #"\b(cat|grep|rg|sed|awk|head|tail|less)\b[^|;&]*\.clj"
    (str "[slopp] the store is the source — query_search {pattern}, "
         "query_source {targets [{ns name}]}, or query_slice {ns name} read it; "
         ".clj files here may be stale projections")]
   [:shell-eval #"\b(clojure|clj)\b[^|;&]*\s-e\b"
    "[slopp] query_eval runs code against the LIVE image (namespaces pre-loaded, no JVM spin-up)"]])

(defn bash-verdict
  "What the Bash hook says about `command` at `event`: `{:verdict :deny
  :reason}` for a raw store read before it runs — the one smell with no
  legitimate in-session use, and a block costs the agent nothing where a
  hindsight hint arrived after the tokens were spent — `{:smell k :advise
  msg}` after an advisory one ran, else nil."
  [event command]
  (case event
    "PreToolUse"  (when (re-find raw-db-pattern (str command))
                    {:verdict :deny :reason raw-db-reason})
    "PostToolUse" (some (fn [[k p m]] (when (re-find p (str command)) {:smell k :advise m}))
                        bash-smells)
    nil))

(defn hook-json
  "The verdict as the JSON Claude Code reads on the hook's stdout, or nil
  when there is nothing to say."
  [event verdict]
  (cond
    (= :deny (:verdict verdict))
    (json/generate-string {:hookSpecificOutput {:hookEventName event
                                                :permissionDecision "deny"
                                                :permissionDecisionReason (:reason verdict)}})
    (:advise verdict)
    (json/generate-string {:hookSpecificOutput {:hookEventName event
                                                :additionalContext (:advise verdict)}})
    :else nil))

(def cooldown-ms
  "How long one session stays quiet about a smell it was told: thirty minutes."
  (* 30 60 1000))

(defn cooldown
  "Whether smell `k` fires for session `sid` at `now`, and the cooldowns
  after: `[fire? cools]`. Keyed by session, because two agents share a
  project and one session's hint must not silence a hint the other has
  never seen."
  [cools sid k now]
  (let [key  [(or sid "-") k]
        last (get cools key)]
    (if (and last (< (- now last) cooldown-ms))
      [false cools]
      [true (assoc cools key now)])))

(defn cli-frame
  "`slopp <op>` posts TEXT: header lines (`key: value`), a blank line, then
  the payload — so a shell never builds JSON around a raw source blob.
  `{:headers {k v} :payload s}`."
  [text]
  (let [text  (str text)
        lines (str/split-lines text)
        heads (take-while #(not (str/blank? %)) lines)
        i     (str/index-of text "\n\n")
        body  (if i (subs text (+ i 2)) "")]
    {:headers (into {} (keep (fn [l]
                               (when-let [[_ k v] (re-matches #"([A-Za-z-]+):\s*(.*)" l)]
                                 [k v]))
                             heads))
     :payload body}))

(defn- read-arguments
  "The op's arguments from the payload: JSON, else EDN, else nil."
  [s]
  (if (str/blank? s)
    {}
    (or (try (let [v (json/parse-string s true)] (when (map? v) v)) (catch Exception _ nil))
        (try (let [v (edn/read-string s)] (when (map? v) v)) (catch Exception _ nil)))))

(defn- change-sections
  "The `change` verb's blob: `;;;tests <ns>` / `;;;impl <ns>` markers open
  sections; each non-blank section is one step."
  [blob]
  (loop [lines (str/split-lines blob) section nil ns nil buf [] out {:tests [] :impl []}]
    (let [close (fn [out]
                  (if (and section ns (not (str/blank? (str/join "\n" buf))))
                    (update out section conj {:ns ns :source (str (str/join "\n" buf) "\n")})
                    out))]
      (if-let [line (first lines)]
        (let [w (str/split (str/trim line) #"\s+")]
          (if (and (= 2 (count w)) (#{";;;tests" ";;;impl"} (first w)))
            (recur (rest lines) (keyword (subs (first w) 3)) (second w) [] (close out))
            (recur (rest lines) section ns (conj buf line) out)))
        (close out)))))

(defn cli-call
  "The call the write door takes for a frame: `{:tool :arguments}`, or
  `{:error}`. `tool:` names an op whose arguments are the payload (JSON or
  EDN); `verb: add|replace` with `target: ns[/name]` is one `change` step
  of the raw payload; `verb: change` splits the payload's sections."
  [{:keys [headers payload]}]
  (let [{:strs [tool verb target prompt accept]} headers]
    (cond
      tool
      (if-let [args (read-arguments payload)]
        {:tool tool :arguments args}
        {:error (str "the arguments for " tool " must be a JSON or EDN map")})

      (#{"add" "replace"} verb)
      (if (str/blank? (str target))
        {:error (str "slopp " verb ": name the target namespace (slopp " verb " <ns>[/<name>] <<EOF)")}
        (let [[ns nm] (str/split target #"/" 2)]
          {:tool "change"
           :arguments {:prompt (or prompt (str "slopp " verb " " target))
                       :impl [(cond-> {:ns ns :source payload} nm (assoc :name nm))]}}))

      (= "change" verb)
      (let [{:keys [tests impl]} (change-sections payload)]
        (if (and (empty? tests) (empty? impl))
          {:error "slopp change: stdin needs ';;;tests <ns>' / ';;;impl <ns>' section markers"}
          {:tool "change"
           :arguments (cond-> {:prompt (or prompt "slopp change") :impl impl}
                        (seq tests) (assoc :tests tests)
                        (not (str/blank? (str accept)))
                        (assoc :accept (vec (remove str/blank? (str/split accept #",")))))}))

      :else
      {:error "the frame names no tool: or verb:"})))
