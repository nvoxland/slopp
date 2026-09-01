(ns slopp.mcp
  "Minimal MCP transport (JSON-RPC 2.0 over stdio) exposing `slopp.ops` as tools.
  The pure `handle` dispatch is the core (fully testable with plain maps);
  `serve!`/`-main` are the thin newline-delimited-JSON stdio loop.

  Tool names use underscores (MCP restricts names to [A-Za-z0-9_-]). This is the
  agent-facing surface — everything is form-addressed (ns/name), never file+line."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [slopp.ops :as ops]
            [slopp.store.db :as db] [slopp.sync :as sync] [clojure.edn :as edn] [slopp.mcp.tools :as tools] [slopp.mcp.smells :as smells] [slopp.ops.branch :as branch] [slopp.read.query :as query] [slopp.ops.review :as review] [slopp.ops.external :as external] [slopp.webdev.cljs :as cljs] [slopp.rules :as rules] [slopp.api.server :as server] [slopp.project.capabilities :as capabilities] [slopp.rules.doctor :as doctor] [slopp.hub :as hub] [slopp.webdev.live :as live] [slopp.read.history :as history] [slopp.read.graph :as graph] [slopp.webdev.screen :as webdev.screen] [slopp.ops.engine :as engine] [slopp.project.harness :as harness] [slopp.read.orient :as orient] [slopp.store :as store] [rewrite-clj.node :as n] [slopp.edit :as edit] [slopp.read.anticipate :as anticipate]))

(def ^:private protocol-version "2024-11-05")

(def ^:private ^:dynamic *hint*
  "Optional one-line workflow hint, attached to map results (item 3)." nil)

(defn- red? [t]
  (and t (pos? (+ (:fail t 0) (:error t 0)))))

(defn parse-call-args
  "Tool arguments for the one-shot --call CLI: nil/blank → {}; \"@path\"
  reads the file first; the text parses as JSON or EDN (agents emit both)
  and must yield a map."
  [s]
  (let [s (if (and s (str/starts-with? s "@")) (slurp (subs s 1)) s)]
    (if (str/blank? s)
      {}
      (let [v (or (try (json/parse-string s true) (catch Exception _ nil))
                  (try (edn/read-string s) (catch Exception _ nil)))]
        (if (map? v)
          v
          (throw (ex-info (str "--call args must be a JSON or EDN map (or @file): "
                               s)
                          {})))))))

(def ^:private ^:dynamic *spool-session*
  "Bound to the session during tools/call so `text` can spool full
  payloads it trims (the headroom pattern: agents get the gist, the full
  version stays retrievable via query_detail for a short while)." nil)

(def ^:private spool-cap 20)

(defn- spool!
  "Keep `full` retrievable for a while; returns its id. Session-scoped,
  FIFO-capped — short-term memory, not history (that's the store)."
  [session full]
  (swap! session update-in [::spool :n] (fnil inc 0))
  (let [n  (get-in @session [::spool :n])
        id (str "r" n)]
    (swap! session update-in [::spool :entries]
           (fn [es]
             (let [es (assoc (or es {}) id full)]
               (if (> (count es) spool-cap)
                 (dissoc es (str "r" (- n spool-cap)))
                 es))))
    id))

(defn- trim-failure-strings
  "Tool-specific heuristic: test-failure :expected/:actual/:message beyond
  700 chars carry their head + a size marker — the diagnosis-relevant part
  is virtually always at the front."
  [x]
  (if-not (and (map? x) (seq (get-in x [:test :failures])))
    x
    (update-in x [:test :failures]
               (fn [fs]
                 (mapv (fn [f]
                         (reduce (fn [f k]
                                   (let [v (get f k)]
                                     (if (and (string? v) (> (count v) 700))
                                       (assoc f k (str (subs v 0 700)
                                                       " …[+" (- (count v) 700) " chars]"))
                                       f)))
                                 f [:expected :actual :message]))
                       fs)))))

(defn- tools-note!
  "The notifications/tools/list_changed message when the tool registry has
  DRIFTED from what this session last advertised (a live reload renamed or
  added a tool — edit_move_forms replaced an earlier extract-to-namespace tool mid-session and no
  client could see it), else nil. Emitting updates the baseline, so each
  drift notifies exactly once. No baseline (tools/list never served) → nil."
  [session]
  (let [h    (hash tools/tools)
        last (:slopp.mcp/tools-hash @session)]
    (when (and last (not= last h))
      (swap! session assoc :slopp.mcp/tools-hash h)
      {:jsonrpc "2.0" :method "notifications/tools/list_changed"})))

(def ^:private ^{:ambient-ok "process-global TEST state with no dynamic-var alternative — binding is dialect-banned, and the flag has to reach a boundary crossed on threads other than the one that would bind it; a fixture flips it for the across-the-wire suite and it is off in production"} strict-boundary?
  "When true, the response boundary (text!) THROWS on any file/line
  coordinate leak — the invariant 'agents never think in files' made
  mechanical. On across the wire test suite (a fixture flips it), off in
  production (zero cost). An atom, not a dynamic var: `binding` is
  dialect-banned, and the flag is process-global test state."
  (atom false))

(defn- boundary-leak
  "The FIRST filesystem-COORDINATE leak in agent-facing response `x` — a
  source `file.clj:line` in any string, or a `:row`/`:col` map key — else
  nil. Agents address by NAME + paste-ready snippet, never file/line, so a
  coordinate crossing the wire is a boundary bug. A bare build path (no
  `:line`) is not a coordinate and passes."
  [x]
  (letfn [(scan [v]
            (cond
              (string? v) (re-find #"[\w./-]*\.clj[cx]?:\d+" v)
              (map? v)    (or (when (some #{:row :col} (keys v))
                                (str "row/col key: "
                                     (pr-str (select-keys v [:row :col]))))
                              (some scan (keys v))
                              (some scan (vals v)))
              (coll? v)   (some scan v)
              :else       nil))]
    (scan x)))

(defn- fit-payload
  "Shrink `x` to at most `budget` characters by dropping whole ITEMS from the
  top level, returning `{:body <edn string> :note \"kept of total\"}` — or nil
  when `x` has no items to drop (a scalar/string; the caller falls back).

  Why item-wise: the old trim was `(subs s 0 budget)`, a blind cut through the
  middle of a structure. For a collection response — history rows, changes,
  commits — that yields UNPARSEABLE edn, so the agent's only recovery is
  `query_detail` for the whole payload. Measured in eval9: an 8,367-char
  trimmed history read plus a 21,676-char re-fetch, i.e. the trim cost 30k
  chars where returning the full payload would have cost 21k. A prefix of
  COMPLETE items parses, is immediately usable, and lets the follow-up be
  narrow instead of total.

  **A trimmed SEQUENCE says so inside the payload**, as a final
  `{:truncated {:shown n :of m :detail id}}` element. The trailing note the
  caller adds is read by a human and skipped by everything else: anything
  consuming the response as DATA reads the first form and stops, so it got a
  well-formed value with items missing and no in-band signal. Measured on
  `query_rules` — 23k against an 8000-char gate, delivering 17 of 43 rules to
  an agent asking what is enforced.

  slopp-ui's design, and their argument for it over my objection. A marker
  element does poison `(map :rule …)` — with ONE nil at the end, which is more
  visible than silently losing 26 rows. The comparison is against today, not
  against a clean answer.

  Room for the marker is RESERVED before items are kept, so announcing the trim
  cannot be the thing that pushes the payload back over the gate.

  A MAP keeps the trailing note alone: it cannot carry a marker element, and
  its entries are not a sequence a consumer maps over, so the two shapes are
  treated differently on purpose."
  ([x budget] (fit-payload x budget nil))
  ([x budget detail-id]
   (let [fit (fn [items open close cap]
               ;; longest prefix of whole items that fits, +2 for the delimiters
               (loop [kept [], used (+ 2 (count open) (count close)), more (seq items)]
                 (if-let [it (first more)]
                   (let [s (+ 1 (count (pr-str it)))]
                     (if (> (+ used s) cap)
                       kept
                       (recur (conj kept it) (+ used s) (next more))))
                   kept)))]
     (cond
       (and (sequential? x) (seq x))
       (let [total  (count x)
             mark   (fn [n] (cond-> {:truncated {:shown n :of total}}
                              detail-id (assoc-in [:truncated :detail] detail-id)))
             ;; reserved up front: the marker's own characters are part of the
             ;; fit, not an overflow added after it
             reserve (+ 1 (count (pr-str (mark total))))
             kept    (fit x "[" "]" (- budget reserve))]
         (when (seq kept)
           {:body (pr-str (if (= (count kept) total)
                            (vec kept)
                            (conj (vec kept) (mark (count kept)))))
            :note (str (count kept) " of " total " shown")}))

       (and (map? x) (seq x))
       (let [kept (fit (seq x) "{" "}" budget)]
         (when (seq kept)
           {:body (pr-str (into {} kept))
            :note (str (count kept) " of " (count x) " keys shown")}))

       :else nil))))

(defn normalize-targets
  "Normalize `query_source`'s `targets` into `[{:ns sym :name sym?} …]`.

  Accepts every UNAMBIGUOUS spelling, because refusing one taught a rule that
  did not need to exist: `{:ns \"a.b\" :name \"c\"}`, the qualified string
  `\"a.b/c\"`, a bare `\"a.b\"`, and the symbol `a.b/c` an agent writing EDN
  reaches for first.

  Previously only the map worked. A string went `(:ns \"a.b/c\")` → nil →
  `(symbol nil)`, and the caller got `no conversion to symbol` — a message
  naming an internal call they never made, with no statement of what WAS
  accepted. Being liberal at the boundary is the better fix than a better
  error message.

  A shape it cannot use is REFUSED rather than dropped: a target that
  silently vanishes reads as \"that form has no source\", which is a different
  and false answer."
  [targets]
  (mapv
   (fn [t]
     (cond
       (map? t)
       (cond-> {:ns (symbol (str (:ns t)))}
         (:name t) (assoc :name (symbol (str (:name t)))))

       (or (string? t) (symbol? t))
       (let [s (str t)
             i (str/index-of s "/")]
         (if (and i (pos? i) (< (inc i) (count s)))
           {:ns (symbol (subs s 0 i)) :name (symbol (subs s (inc i)))}
           {:ns (symbol s)}))

       :else
       (throw (ex-info (str "query_source targets: cannot read " (pr-str t)
                            " — a target is {:ns \"a.b\"} or {:ns \"a.b\" :name \"c\"},"
                            " or the string/symbol form \"a.b/name\" (\"a.b\" alone"
                            " gives that namespace's outline)")
                       {:target t}))))
   targets))

(defn- refusal-text
  "The message a REFUSED call answered with, or nil if it was not a refusal.

  A refusal arrives in two shapes: a thrown exception, which `handle!` has
  already marked `:isError`, or slopp's own refusal-as-data, which `text!`
  pr-strs so the payload opens with `{:error ` — **including the space**, which
  is what keeps `{:errors 0` (how every external test-run result opens) from
  matching. This docstring said `{:error` for a year and the code agreed with
  it, which is how the meter came to count green test runs as refusals. This is the whole predicate as
  well as the message — `:refused?` is `(some? (refusal-text r))` — because
  asking the same question at two call sites is how they drift, and the
  failure log has four instances of that shape already.

  It UNDER-counts, deliberately: a result carrying `:error` behind another key
  reads as clean. That is the safe direction for a waste metric — it will
  never invent a problem, only miss one. What it must not do is lose a
  refusal it has already been told about, so an `:isError` with no text still
  answers non-nil."
  [r]
  (let [t (:text (first (:content r)))]
    (cond
      ;; the SPACE is load-bearing. `pr-str` of the refusal shape `{:error
      ;; "..."}` always puts one after the key, and `{:errors 0` — the opening
      ;; of every external test-run result — does not have it. Without the
      ;; space this matched all of them: 461 of 1465 recorded refusals on this
      ;; store were `test_run`, GREEN runs included, which made it the
      ;; most-refused tool by a wide margin and put a nonexistent "agents reach
      ;; for a redundant test ritual" habit into a performance plan.
      (and t (str/starts-with? t "{:error ")) t
      (:isError r) (or t "error")
      :else nil)))

^:unsafe (defn start-heartbeat!
  "Start this project checking in with a hub, and record the handle on
  the session. Never throws; returns the hub url it beats to, or nil.

  `slopp.hub.port` 0 means \"no hub\", and a hub that simply is not running is the
  ordinary case rather than a failure — the beat retries forever and costs
  nothing, so the project appears in the picker within one interval of a hub
  starting later. Registering and keeping alive are the same call, deliberately
  (D-hub).

  Two session keys, and the split between them is the point.
  `:hub-configured` is where we BEAT — known immediately, true whether or
  not anyone is listening. `:hub` is this project's own page on the hub, and
  it exists only while a hub is answering, because the slug in it comes back on
  the reply and cannot be fabricated. Every beat rewrites it, so a hub that
  goes away takes the claim with it.

  One key used to carry both meanings: `:hub` was set here, once, from the
  configured port. Orientation then advertised an address nobody was serving —
  and the skill tells an agent to hand that address to a human, so the cost
  landed on the human every time. The reply had the answer all along; nothing
  was reading it.

  The banner names the HUB's address, not this project's derived port, because
  the hub url is the one a human is meant to remember and the derived one is an
  implementation detail they should never have to type."
  [session dir url]
  (try
    (let [port (capabilities/effective (:store @session) "slopp.hub.port")]
      (when (and dir port (pos? (long port)))
        (let [hub    (hub/hub-url port)
              handle (hub/start! hub
                                #(hub/payload (:store @session) dir url)
                                #(let [at (hub/hub-address hub %)]
                                   (cond
                                     at (swap! session assoc :hub at
                                               :hub-refused nil)
                                     ;; a hub that answered and said NO is the
                                     ;; drift alarm — keep it, or the brief
                                     ;; reports absence about a running hub
                                     (hub/refused? %)
                                     (swap! session assoc :hub-refused %
                                            :hub nil)
                                     :else (swap! session assoc :hub nil
                                                  :hub-refused nil))))]
          (swap! session assoc :hub-heartbeat handle :hub-configured hub)
          (.println System/err ^String (str "slopp hub: " hub
                                            " (open this to switch projects)"))
          hub)))
    (catch Throwable t
      (.println System/err ^String (str "slopp hub registration unavailable: "
                                        (.getMessage t)))
      nil)))

(def terse-elided
  "The only routed keys the TERSE path drops, and the reason each is not a
  finding.

  `wire-keys`' own docstring draws this line: routing is the registry's job,
  SHAPING is this layer's, and a size concern 'belongs where the shaping
  happens'. These two are the shaping, and they are unlike every other key in
  one specific way — they ride EVERY result regardless of what the operation
  did, so they answer nothing about whether it examined anything.

  - `:ms` — cost telemetry. Real, and never an answer to a question the agent
    asked. `:verbose true` still carries it.
  - `:warnings` — its PRESENCE is the signal. Unlike `:callers []` ('looked,
    none'), an empty warning list is not a finding, and a non-empty one never
    reaches here at all: it routes to the verbose path above.

  Deliberately two. This set existing at all is a re-decision of what an agent
  sees, which is the defect the registry exists to prevent — so it stays small
  enough to read, and `mcp-test/the-terse-path-drops-nothing-the-registry-routed`
  fails if it grows."
  #{:ms :warnings})

^:unsafe (defn refresh-app!
  "Re-serve this project's app on the CURRENT store, or stop a managed server
  the store has opted out of. nil when there is nothing to do. NEVER throws.

  Called at each `done` point, which is the grain the whole feature is built
  around: mid-episode the store is intentionally incomplete, and a browser
  reloading into a half-written red state teaches the author to ignore it.

  **Opting out is an ACTION, not the absence of one.** The first cut gated on
  `managed?` and returned, which stops RE-SERVING and never stops SERVING —
  so after `http.enabled false` the old image kept answering and
  `session_brief` kept advertising its url, while the config said no managed
  server existed. Found by slopp-ui, who checked the surface against the
  config rather than against the page.

  **The same gate as `start-app!`, and that is not redundancy.** A gate on
  the startup path only would let the second done point start what the first
  one declined to — the feature would arrive by accident, in a test run,
  minutes after everything looked fine.

  A store that was never managed and has nothing running reports NOTHING, not
  even a failure. Most stores are not web projects, and a line at every done
  point saying so is how a report stops being read.

  `locking` because two done points close together would otherwise both boot,
  both stop the same predecessor, and race for the port. They queue instead,
  and nobody waits on them: the call site backgrounds this so `done` returns
  at its own speed."
  [session]
  (let [dir (:dir @session)]
    (if (and dir (live/managed? (:store @session) server/served-namespaces))
      (locking session
        ;; IN PLACE first. A re-boot replaces the child JVM, so the app's
        ;; `:http/perform-ctx` is rebuilt and any state it kept there — a
        ;; cache, a registry, a pool — is silently gone at every done point.
        ;; `hot-refresh!` answers nil for everything in-place cannot serve (a
        ;; changed load order, a failed reload, nothing running), and the
        ;; re-boot below is the fallback rather than the default.
        (try (or (live/hot-refresh! session (:store @session)
                                    (:app-server @session))
                 (live/refresh! session (:store @session) dir))
             (catch Throwable t
               {:serving? false :reason (or (.getMessage t) (str t))})))
      (when-let [running (:app-server @session)]
        (locking session
          (try (live/stop! running) (catch Throwable _))
          (swap! session dissoc :app-server))
        {:serving? false
         ;; STOPPED ON PURPOSE, and that has to be legible to the caller:
         ;; `done` reports a re-serve that BROKE and must not report this,
         ;; which is indistinguishable without the flag — same :serving?
         ;; false, same shape of reason, opposite meaning.
         :stopped true
         ;; `managed?` is false for two different reasons now, and a stopped
         ;; server that names the wrong one sends someone to change the
         ;; wrong thing
         :reason (if (live/self-served? (:store @session) server/served-namespaces)
                   (str "this session already serves this store's surface — the"
                        " managed app server was stopped, because a second one"
                        " would serve a staler copy of the same pages")
                   (str "http.enabled is false for this store — the managed app"
                        " server was stopped"))}))))

^:unsafe (defn start-app!
  "Bring this project's app server up beside the MCP server, or nil when
  slopp does not run this store's server. NEVER throws.

  The stance is the UI listener's, for the same reason: **the app server is
  OPTIONAL and MCP is not.** A busy port, a
  store that will not load, anything at all — it reports a sentence on
  stderr (stdout is the JSON-RPC channel) and the server carries on. Nothing
  about a page in a browser should be able to stop the thing the editor is
  talking to.

  It goes through `refresh-app!` rather than `live/start!`, so the
  first serve and every later one are the same code path. A start that
  differed from a swap would be a second lifecycle, and the two would drift
  exactly where nobody looks — the first boot of a session is the one nobody
  re-tests.

  A store that is not managed reports NOTHING, not even a failure. Most
  stores are not web projects, and a line on every startup saying so is how
  a banner stops being read."
  [session]
  (let [r (refresh-app! session)]
    (when r
      (.println System/err
                ^String (if (:serving? r)
                          (str "slopp app: " (:url r)
                               (when-let [ms (:boot-ms r)]
                                 (str " (image up in " ms "ms)"))
                               ;; a url with nothing behind it is worse than no
                               ;; url: the human opens it, gets 404, and has no
                               ;; reason to suspect the SERVER is fine
                               (when-let [empty-note (get-in r [:plan :serves-nothing])]
                                 (str " — but " empty-note)))
                          (str "slopp app unavailable: " (:reason r)))))
    r))

(defn- app-note-for
  "The line `done` should carry about the app server it just re-served, given
  [[refresh-app!]]'s result — or nil, which is the usual answer.

  Three outcomes and only one of them is news. **Nothing to do** (nil: this
  store is not one slopp runs, which is most stores) and **it came back up**
  are both silent, because a line at every done point is how a report stops
  being read. A re-serve that FAILED is the opposite case: it is news, this
  done caused it, and this is the only moment the causing change is still in
  the author's hand.

  **A deliberate stop is not a failure**, and the distinction is the whole
  reason `:stopped` exists. Opting out of a managed server, or this session
  already serving the surface itself, both end with `:serving? false` and a
  reason describing a CORRECT outcome. Reporting those as breakage would put
  a scary line in front of someone who got exactly what they asked for, and
  train them to skim the one that matters.

  Reported by the first consumer to lose an app server this way: `done`
  returned a clean report, the failure appeared only in `session_brief`, and
  an agent has no reason to make that second call. Action and announcement
  belonged in the same one."
  [refreshed]
  (cond
    ;; THE BOUND EXPIRED. This used to report nil — "say nothing rather than
    ;; guess" — which put a re-serve that had not happened into the same
    ;; silence as a store slopp runs nothing for and a re-serve that worked.
    ;; Three outcomes, one silence, and two of them fine: a consumer served
    ;; twenty minutes of old code with every surface reading clean.
    ;;
    ;; Guessing was never the alternative. Naming WHICH question went
    ;; unanswered is, and it costs one line at the rare moment it is true.
    (= ::refresh-timed-out refreshed)
    (str "the app re-serve did not finish inside this done's wait — it is"
         " STILL RUNNING, and this done cannot say whether the image was"
         " replaced. session_brief reports the outcome: :app-behind 0 means it"
         " landed, a positive count means the browser is still on the older"
         " store. Not a failure by itself; a done that stayed silent here"
         " would have been indistinguishable from one that re-served cleanly.")

    (and (map? refreshed)
         (false? (:serving? refreshed))
         (not (:stopped refreshed))
         (:reason refreshed))
    (str "the app server slopp runs for this project is DOWN after this done: "
         (:reason refreshed))

    ;; SERVING AND EMPTY is the fourth outcome, and it hid behind the first
    ;; three because it looks exactly like success: the port bound, the url is
    ;; right, and every path 404s. It is news at a done for the same reason a
    ;; failure is — this done is when it became true, and the change that did
    ;; it is still in the author's hand.
    (and (map? refreshed)
         (:serving? refreshed)
         (get-in refreshed [:plan :serves-nothing]))
    (str "the app server slopp runs for this project came back up at "
         (:url refreshed) " and " (get-in refreshed [:plan :serves-nothing]))))

(def ^:private thread-hint-every
  "Un-landed changes between reminders that this session's work is private.

  Small enough that a long episode hears it more than once, large enough that
  an ordinary task — orient, read, a handful of writes, done — hears it
  exactly once: on the write that made the work private in the first place."
  25)

(defn thread-hint!
  "One line saying this session's work is still private, or nil.

  `session_brief` names the thread and nothing else does, so between orienting
  and `done` the fact that nobody can see your work is true and unstated —
  and a long episode, where it matters most, is exactly where the brief has
  scrolled out of context.

  Fires when the un-landed count MOVES: on the change that makes the work
  private, then every [[thread-hint-every]] changes after. Both halves are the
  anti-noise design — a reminder on every call is one a reader learns to skip,
  and one that never repeats is one a long session loses. Counting CHANGES
  rather than calls means it speaks in proportion to what is at stake instead
  of to how chatty the session is.

  **Whether a call wrote is asked of the journal, not of a list of tool
  names.** The first cut gated on `tools/write-tools` — a 17-entry set that
  does not contain `edit_subform`, the commonest write in the system — so the
  reminder never fired at all. A derived test cannot fall out of step with the
  thing it describes, and a read gets its silence for the honest reason:
  nothing became invisible.

  **The count is walked once per HEAD, not once per call.** It is a recursive
  CTE over the line, and it can only move when the head moves; every read
  used to pay it again for the same answer. Keyed on `[line head]` in the
  session, so a burst of reads costs one walk and a write costs the next.

  `done` and `commit_point` stay quiet by name, and that exclusion is about
  noise rather than detection: a `done` that is red on the episode's own work
  genuinely does leave everything private, and it says so itself, in the
  verdict the agent is already reading.

  Keyed to the LINE as well as the count, so a land resets both halves — the
  fresh thread starts at zero and the next reminder is a real one rather than
  a leftover measured against a line that no longer exists."
  [session tool]
  (when-not (#{"done" "commit_point"} tool)
    (when-let [conn (:db @session)]
      (when-let [line (:line @session)]
        (let [head            (:head (:store @session))
              [at cached]     (::thread-hint-count @session)
              n               (if (= at [line head])
                                cached
                                (let [n (db/unlanded-count conn line history/content-ops)]
                                  (swap! session assoc ::thread-hint-count [[line head] n])
                                  n))
              [seen-l seen-n] (::thread-hint-seen @session)
              prev            (if (= seen-l line) seen-n 0)
              [said-l said-n] (::thread-hint-at @session)
              base            (if (= said-l line) said-n 0)]
          (swap! session assoc ::thread-hint-seen [line n])
          (when (and (< prev n)
                     (or (not (::thread-hint-said? @session))
                         (<= (+ base thread-hint-every) n)))
            (swap! session assoc ::thread-hint-said? true ::thread-hint-at [line n])
            (str n (if (= 1 n) " change is" " changes are")
                 " on your thread and nobody else can see "
                 (if (= 1 n) "it" "them")
                 " — not another agent on this branch, not the git projection,"
                 " not the running server. `done` lands them.")))))))

(defn- foreign-unlanded-note
  "The line a ONE-SHOT process owes its caller when another thread holds
  un-landed work — or nil, which is the ordinary case.

  A `--call` process opens its own session and therefore reads the BRANCH. A
  session working through MCP reads its own THREAD. While that thread holds
  un-landed writes the two disagree, and nothing said so: the CLI answer looks
  authoritative because it IS authoritative, about a different store.

  Measured at roughly an hour on the wave that added this. A write-path gate
  refused a form; the gate was reproduced over the CLI, came back CLEAN, and
  the contradiction was filed as a mystery. The gate was judging a half-renamed
  session; the CLI was judging the branch. Both readings were correct, and
  nothing on either side named the difference.

  **Silent at zero**, which is what makes it worth printing at all: a CI run, a
  fresh clone, or any store nobody is mid-episode in has no foreign thread and
  gets no note."
  [session]
  (let [rows (->> (:threads (branch/thread-list session))
                  (remove :mine)
                  (filter #(pos? (:unlanded % 0))))]
    (when (seq rows)
      (str "NOTE — this is a ONE-SHOT read of the BRANCH. "
           (count rows) " other thread(s) hold "
           (reduce + (map :unlanded rows))
           " un-landed write(s) that this process cannot see, because it opened"
           " the store fresh. A session working through MCP reads its own"
           " thread, so its answer to this question can differ from this one and"
           " both be right. If you are diagnosing something a WRITE did, ask"
           " through that session rather than here."))))

(def ^:private ^:dynamic *response-facts*
  "Bound to an atom during tools/call so whoever shapes the answer can record
  what it DID: `text!` knows the size gate cut a payload, `told!` knows the
  knowledge differential withheld one, and `query_detail` knows which id it
  went back for. None of that is in the return value, and all of it happens
  several frames below `handle!`, which is the only layer that records a call
  at all.

  A recorder rather than a return value because the alternative is a second
  value threaded out of every tool branch that nothing else reads. It is
  write-only and per-call: a nil binding (a direct `text!` in a test) simply
  drops the note." nil)

(defn- note-response!
  "Record `m` about the response being shaped, if anyone is listening."
  [m]
  (when *response-facts* (swap! *response-facts* merge m)))

(defn- text!
  "The one exit every tool result takes. `:budgeted? true` says the payload
  already fitted a budget of its own (`orient`'s tokens, a slice's limit, the
  brief) and is sent whole whatever its size — the 8k gate cutting `orient`
  to 247 chars and then having the agent fetch the 12k spool was measured
  as the single largest waste of context in eval10. A GREEN result over the
  gate is still fitted, but never INVITES the re-fetch: the in-band
  `:truncated` marker says what was cut, and the trailing
  `query_detail … returns all` line — which agents followed on verdicts
  they already had, 77k chars per session — is reserved for a result whose
  missing part could change what the agent does next."
  [x & {:keys [budgeted?]}]
  (when @strict-boundary?
    (when-let [leak (boundary-leak x)]
      (throw (ex-info (str "boundary leak — a file/line coordinate reached an"
                           " agent response: " leak " (agents address by name +"
                           " snippet, never file:line — anchor it)")
                      {:leak leak}))))
  (let [;; FORCED, because one of the two hint sources can only be computed
        ;; AFTER the tool ran: a write's hint counts what is now un-landed, and
        ;; `*hint*` is bound before the call. A delay lets that one be decided
        ;; here, at render time, and memoizes so a second render cannot make it
        ;; speak twice. A plain string or nil passes through `force` unchanged.
        h       (force *hint*)
        x       (cond
                  (and h (map? x) (nil? (:hint x))) (assoc x :hint h)
                  (and h (string? x)) (str x "\n\n[hint] " h)
                  :else x)
        full    (if (string? x) x (pr-str x))
        slimmed (let [t (trim-failure-strings x)]
                  (if (string? t) t (pr-str t)))
        green?  (and (map? x)
                     (or (= :green (:status x))
                         (= :green (get-in x [:findings :test-status]))
                         (= :green (get-in x [:test :status]))
                         (true? (:ok x))))
        invite  (fn [id] (if green? "" (str " — query_detail {:id \"" id "\"} returns all")))
        trimmed (fn [id] (if green? "" (str "\n[trimmed — query_detail {:id \"" id
                                            "\"} returns the full response]")))
        out     (cond
                  budgeted? full

                  (and (= full slimmed) (<= (count full) 8000)) full

                  :else
                  (if-let [sess *spool-session*]
                    (let [id  (spool! sess full)
                          ;; every branch below withheld part of this answer,
                          ;; and the id is what a later query_detail names — so
                          ;; the trim can be scored against the re-fetch it
                          ;; provoked instead of assumed to have paid
                          _   (note-response! {:trimmed? true :spooled id})
                          ;; the spool id travels INTO the marker, so the in-band signal is
                          ;; actionable rather than only informative: a consumer that
                          ;; sees :truncated can fetch the rest without parsing the
                          ;; trailing line it was never going to read
                          fit (when (> (count slimmed) 8000)
                                (fit-payload (trim-failure-strings x) 7800 id))]
                      (cond
                        ;; slimming alone got it under the gate — send it whole
                        (<= (count slimmed) 8000)
                        (str slimmed (trimmed id))

                        ;; drop whole ITEMS: the body stays parseable and usable,
                        ;; so a follow-up can be narrow instead of a full re-fetch
                        fit
                        (str (:body fit) "\n[" (:note fit) (invite id) "]")

                        ;; nothing to drop (a single huge string/scalar)
                        :else
                        (str (subs slimmed 0 8000) (trimmed id))))
                    (if (<= (count slimmed) 8000) slimmed full)))]
    {:content [{:type "text" :text out}]}))

(def ^:private file-handlers!
  "call-tool dispatch \u2014 tracked files + config (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"config"
   (fn [session a _sym]
     (text! (external/config! session (:key a) (:value a))))
   "file_put"
   (fn [session a _sym]
     (text! (ops/file-put! session (:path a) (:content a)
                           :prompt (:prompt a) :agent (:agent a)
                           :encoding (:encoding a)
                           :content-type (:content_type a)
                           :source (:source a))))
"js_dep"
   (fn [session a _sym]
     ;; format crosses the wire as a string; keywordize HERE so the verb's
     ;; own check sees a keyword and can name the keywords it wants
     (text! (ops/js-dep! session (:name a)
                         {:version    (:version a)
                          :format     (some-> (:format a) not-empty keyword)
                          :global     (:global a)
                          :file       (:file a)
                          ;; registry-anchored provenance: npm versions are
                          ;; immutable, so this is re-fetchable and verifiable
                          ;; where a CDN url only records how the bytes arrived
                          :npm        (:npm a)
                          :npm-path   (:npm_path a)
                          :integrity  (:integrity a)
                          :source-url (:source_url a)
                          :license    (:license a)}
                         :prompt (:prompt a) :agent (:agent a)
                         :remove (:remove a) :source (:source a))))
   "file_remove"
   (fn [session a _sym]
     (text! (ops/file-remove! session (:path a)
                                           :prompt (:prompt a) :agent (:agent a))))
   "file_list"
   (fn [session _a _sym]
     (text! (ops/files-list session)))
   "file_get"
   (fn [session a _sym]
     (text! (ops/file-get session (:path a) :at (:at a))))
   "file_history"
   (fn [session a _sym]
     (text! (ops/file-history! session (:path a))))
   "config_file"
   (fn [session a _sym]
     (text! (ops/config-file! session (:path a)
                                           :key (:key a) :value (:value a)
                                           :unset (:unset a) :format (:format a)
                                           :prompt (:prompt a) :agent (:agent a))))
   "module_dep"
   (fn [session a _sym]
     (text! (ops/module-dep! session (:from a) (:to a)
                             :remove (:remove a)
                             :test-only (:test_only a)
                             :prompt (:prompt a) :agent (:agent a))))
   "module_purity"
   (fn [session a _sym]
     (text! (ops/module-tier! session (:module a) (:tier a)
                              :remove (:remove a)
                              :prompt (:prompt a) :agent (:agent a))))
"module_platform"
   (fn [session a _sym]
     (text! (ops/module-platform! session (:module a) (:platform a)
                                  :remove (:remove a)
                                  :prompt (:prompt a) :agent (:agent a))))
"module_role"
   (fn [session a _sym]
     (text! (ops/module-role! session (:module a) (:role a)
                              :remove (:remove a)
                              :prompt (:prompt a) :agent (:agent a))))})

(def ^:private sync-handlers!
  "call-tool dispatch \u2014 git publish/absorb + remotes (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"git_push"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
              (if (.exists (io/file dir ".git"))
                (sync/mirror-push! dir :url (:url a) :token (:token a)
                                   :branches (or (:branches a)
                                                 (some-> (:branch a) vector)
                                                 [(:branch @session "main")]))
                ;; fileless store: publish the projection directly
                (sync/push! dir :url (:url a) :token (:token a)
                            :branch (:branch @session "main")))
              {:error "git_push needs a durable session (a store dir)"})))
   "git_clone"
   (fn [_session a _sym]
     (text! (sync/clone! (:url a) (:dir a)
                                      :token (:token a) :agent (:agent a))))
   "git_pull"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
              (let [m (sync/mirror-pull! dir :url (:url a) :token (:token a)
                                         :branches (or (:branches a)
                                                       [(:branch @session "main")]))
                    p (when-not (:error m)
                        (try (sync/pull! session :token (:token a)
                                         :agent (:agent a))
                             (catch Exception e {:error (ex-message e)})))]
                (cond-> m p (assoc :absorbed p)))
              {:error "git_pull needs a durable session (a store dir)"})))
   "import_dir"
   (fn [session a _sym]
     (text! (if (:dir @session)
              (try (sync/import-dir! session (:dir a) :agent (:agent a))
                   (catch Exception e {:error (ex-message e)}))
              {:error "import_dir needs a durable session (a store dir)"})))
   "git_conflicts"
   (fn [session _a _sym]
     (text! (if-let [dir (:dir @session)]
              {:conflicts (sync/conflicts dir)}
              {:error "git_conflicts needs a durable session"})))
   "git_resolve"
   (fn [session a _sym]
     (text! (if-let [dir (:dir @session)]
                           (sync/resolve! dir (:path a))
                           {:error "git_resolve needs a durable session"})))
   "query_git"
   (fn [session _a _sym]
     (text! (let [ext (when-let [conn (:db @session)]
                           (when-let [r (db/get-meta conn "git-remote")]
                             {:git-remote   r
                              :git-base-sha (db/get-meta conn "git-base-sha")}))]
                       (if ext
                         {:external ext
                          :note (str "milestones (commit_point) are the commits; "
                                     "git_push publishes the projection to the "
                                     "remote and git_pull absorbs from it")}
                         {:error (str "no git remote configured — git_push {url}"
                                      " sets one, or git_clone rebuilds a store"
                                      " from one")}))))
   "query_commits"
   (fn [session a _sym]
     (text! (if (:commit a)
              ;; the drill-down rung: ONE milestone, full description
              (or (ops/query-commits session :commit (:commit a))
                  {:error (str "no milestone " (:commit a))})
              (let [rows (ops/query-commits session)
                    conn (:db @session)
                    al   (when (and conn (:dir @session))
                           (sync/alignment (:dir @session) "."
                                           (str "slopp/" (:branch @session))
                                           rows))]
                (if al
                  {:commits rows :alignment al}
                  rows)))))
   "merge_from"
   (fn [session a _sym]
     (text! (branch/merge! session (:dir a))))})

(defn- host-image-options
  "The idle-image budget this SERVER opens with, from the host environment.

  A writer costs several JVMs, not one: the active image, a warm spare, and one
  parked image per branch line held for the reap lease. Only the first is doing
  anything. The other two are latency trades — they exist to keep a JVM boot
  off the critical path — and a host running many concurrent writers is trading
  the wrong way, because its binding constraint is memory rather than the ~830
  ms a boot costs.

  Both were already `open!` options; only this server hardcoded them, so there
  was no way to say so without editing code. **The defaults do not move** —
  they are what was measured for a session alone on a box — so a single-writer
  host is unaffected and a swarm operator gets a dial.

  `SLOPP_WARM_SPARE` is off for `0` or `false` and on for anything else,
  including unset. `SLOPP_BRANCH_IMAGE_TTL_MS` must read as a POSITIVE number
  to be honoured: a typo parsed as zero would reap every branch image the
  instant it was parked, which presents as branch switching having got slow and
  never as a misspelt variable. An unreadable setting must not be obeyed as its
  most destructive reading.

  `getenv` is a parameter rather than a read, because the process environment
  is state a test cannot set."
  [getenv]
  (let [off?  #{"0" "false"}
        spare (some-> (getenv "SLOPP_WARM_SPARE") str/trim str/lower-case)
        ttl   (some-> (getenv "SLOPP_BRANCH_IMAGE_TTL_MS") str/trim parse-long)]
    {:slopp.ops/warm-spare?         (not (off? spare))
     :slopp.ops/branch-image-ttl-ms (if (and ttl (pos? ttl))
                                      ttl
                                      external/default-branch-image-ttl-ms)}))

(defn- terse-done
  "A green done is ONE LINE: the id, the verdict, where it landed — plus only
  what needs the agent (an external tier that ran, a deferral count, a host
  that drifted, a standing advisory, the app note). eval10 measured `done`
  at ~2k chars a call, six calls a session, byte-identical prose about
  episode scope and oracle currency riding every one; a red done keeps the
  full report, because there the findings are the answer."
  [r]
  (let [f (:findings r)]
    (if-not (and (= :green (:episode-status f))
                 (= :green (:test-status f))
                 (zero? (:lint-errors f 0))
                 (empty? (:unloadable-namespaces f)))
      r
      (let [advisory (into {}
                           (remove (fn [[k v]]
                                     (or (#{:episode-status :test-status :lint-errors :ms :failures
                                            :unloadable-namespaces :external-pending :host-stale
                                            :http-dangling-route-refs :red-attribution :scope} k)
                                         (and (coll? v) (empty? v))
                                         (nil? v))))
                           f)
            info-only? (every? #(= :info (:severity %)) (:http-dangling-route-refs f))]
        (cond-> {:done (:done r) :status :green}
          (:landed (:land r))            (assoc :landed (:landed (:land r)))
          (:external r)                  (assoc :external (select-keys (:external r) [:ran :status :failures]))
          (:external-pending f)          (assoc :external-pending (:count (:external-pending f)))
          (pos? (get-in f [:host-stale :oracle-drift-count] 0))
          (assoc :host-stale (select-keys (:host-stale f) [:oracle-drift :note]))
          (not info-only?)               (assoc :http-dangling-route-refs (:http-dangling-route-refs f))
          (seq advisory)                 (assoc :advisories advisory)
          (:app-note r)                  (assoc :app-note (:app-note r)))))))

(defn plugin-root
  "Where the plugin's files are, or nil: Claude Code sets CLAUDE_PLUGIN_ROOT for
  every process the plugin starts, and the MCP server is one. The skill and
  its reference topics ship there — a different channel from the jar this
  code runs in — so the one thing the server can do about them is READ them,
  and this is the seam a test redirects."
  []
  (not-empty (System/getenv "CLAUDE_PLUGIN_ROOT")))

(defn help-text
  "`help {topic}`: the plugin's `skills/slopp/reference/<topic>.md`, whole, or
  with no topic the index of topics on disk. One source of truth: the same
  file the agent could Read, served through the tool so a session that has
  only the one-page skill in context reaches the rest without leaving the
  loop. The skill is a page because the 2,900-line version cost ~70k tokens
  in every session that loaded it — 65% of all context the eval10 lifetime
  cells ever created — and an agent reads a REST or web chapter once per
  project, not once per turn."
  [topic]
  (let [dir    (some-> (plugin-root) (io/file "skills" "slopp" "reference"))
        topics (when (and dir (.isDirectory dir))
                 (->> (.listFiles dir)
                      (filter #(str/ends-with? (.getName %) ".md"))
                      (map #(subs (.getName %) 0 (- (count (.getName %)) 3)))
                      sort vec))
        index  (str "help topics: " (str/join ", " topics)
                    " — help {topic} returns one whole; each is also"
                    " skills/slopp/reference/<topic>.md in the plugin.")]
    (cond
      (nil? dir)
      (str tools/cheat-sheet "\n\n(no plugin root in this process — the reference"
           " topics ship in the plugin under skills/slopp/reference/)")

      (str/blank? (str topic))
      (str tools/cheat-sheet "\n\n" index)

      ;; an OP's full card — the family index carries one line per op, and
      ;; this is where the rest of its description and its schema live
      (some #(when (= (str topic) (:name %)) %) tools/registry)
      (pr-str (select-keys (some #(when (= (str topic) (:name %)) %) tools/registry)
                           [:name :description :inputSchema]))

      (some #{(str topic)} topics)
      (slurp (io/file dir (str topic ".md")))

      :else
      (str "no topic named " topic ". " index))))

(def ^:private env-handlers!
  "call-tool dispatch \u2014 deps/branches/build/help (Q4: the stable dispatch tail lives in\n  per-group handler maps of (fn [session a sym]); call-tool keeps only the\n  hot query/edit clauses)."
  {"deps_add"
   (fn [session a sym]
     (text! (ops/deps-add! session (sym :lib)
                          (or (:coord a)
                              (when (:version a)
                                {:mvn/version (:version a)}))
                          :agent (:agent a) :prompt (:prompt a)
                          :client (:client a))))
   "deps_remove"
   (fn [session a sym]
     (text! (ops/deps-remove! session (sym :lib)
                                           :agent (:agent a))))
   "deps_list"
   (fn [session _a _sym]
     (text! (ops/deps-manifest session)))
   "store_health"
   (fn [session _a _sym]
     (text! (external/store-health session)))
   "store_doctor"
   (fn [session _a _sym]
     (text! (doctor/diagnose (:store @session))))
   "store_compact"
   (fn [session _a _sym]
     (text! (external/compact-store! session)))
   "ui_serve"
   ;; `:ui-url` is what session_brief announces, and until this only
   ;; `start-ui!` wrote it — so re-serving moved the listener and left the
   ;; brief naming the port it came up on at BOOT. Observed live: the brief
   ;; said 49283 while the listener held 53610 and nobody held 49283. The
   ;; address a reader is handed has to be the one that was bound, and
   ;; stopping has to clear it rather than leave an address nothing answers.
   (fn [session a _sym]
     (text! (if (:stop a)
              (let [stopped (boolean (server/stop!))]
                (swap! session dissoc :ui-url :ui-stamp)
                {:stopped stopped})
              (let [r (server/serve! session
                                     (server/preferred-port (:dir @session) (:port a)))]
                ;; the stamp rides the SESSION beside the url, because the
                ;; brief is where a reader finds out anything about this
                ;; listener and slopp.ops cannot ask slopp.api — that edge
                ;; runs the other way.
                (when (:url r)
                  (swap! session assoc :ui-url (:url r)
                         :ui-stamp (:derived-from r)))
                r))))
"screen"
   (fn [session a _sym]
     (text! (webdev.screen/screen! session
                             :steps (:steps a)
                             :region (:region a)
                             :detail (:detail a)
                             :trace (:trace a)
                             :url (:url a))))
   "compile_client"
   (fn [session a _sym]
     (text! (if (:output a)
              (cljs/compile-client! session :output (:output a))
              (cljs/compile-client! session))))
   "generate_client"
   (fn [session a _sym]
     (text! (cond
              ;; a contract URL generates against an API this store CONSUMES —
              ;; two namespaces, and nothing reads the producer's store
              (:from a) (if (:ns a)
                          (cljs/generate-client-from! session (:from a) :ns (symbol (:ns a)))
                          (cljs/generate-client-from! session (:from a)))
              (:ns a)   (cljs/generate-client! session :ns (symbol (:ns a)))
              :else     (cljs/generate-client! session))))
   "deps_pure"
   (fn [session a sym]
     (text! (if (false? (:pure a))
                           (ops/deps-unpure! session (sym :target) :agent (:agent a))
                           (ops/deps-pure! session (sym :target) :agent (:agent a)))))
   "branch_create"
   (fn [session a _sym]
     (text! (branch/branch! session (:name a))))
   "branch_switch"
   (fn [session a _sym]
     (text! (branch/branch-switch! session (:name a))))
   "branch_merge"
   (fn [session a _sym]
     (text! (branch/branch-merge! session (:name a))))
   "branch_delete"
   (fn [session a _sym]
     (text! (branch/branch-delete! session (:name a))))
   "thread_list"
   (fn [session _a _sym]
     (text! (branch/thread-list session)))
   "thread_drop"
   (fn [session a _sym]
     (text! (branch/thread-drop! session (:id a))))
   "query_branches"
   (fn [session _a _sym]
     (text! (branch/query-branches session)))
   "restart"
   (fn [session a _sym]
     (ops/restart! session)
     ;; the ORACLE is what restart has always re-imaged, and it stays the
     ;; default. `app true` also re-serves this project's app server — the
     ;; second half of "reload in place, restart on demand", and it exists
     ;; because a declared entry answers `:started` once a namespace loads
     ;; and a thread spawns, so one that came up half dead reports exactly
     ;; what a healthy one does. Without this the only way to ask again is an
     ;; unrelated write, to trigger a done that re-serves as a side effect.
     (if-not (:app a)
       (text! "restarted")
       (let [r (refresh-app! session)]
         (text! (cond-> {:restarted true
                         :app-restarted (boolean (:serving? r))}
                  (:url r)     (assoc :app-url (:url r))
                  (:started r) (assoc :app-started (:started r))
                  (not (:serving? r))
                  (assoc :app-note
                         (or (:reason r)
                             (str "nothing to restart — this store has no"
                                  " managed app server. Declare what to run"
                                  " (config_file {path \"dev\" key"
                                  " \"run.<name>.main\" value \"my.ns/-main\"}),"
                                  " or enable http.enabled for the derived"
                                  " one."))))))))
   "build"
   (fn [session a _sym]
     (text! (external/build! session (:dir a)
                                    :main (some-> (:main a) symbol)
                                    :name (:name a))))
   "help"
   (fn [_session a _sym]
     (text! (help-text (:topic a)) :budgeted? true))})

(defn wire-steps
  "`edit_group`'s step maps as `ops/edit-group!` takes them: keys keywordized
  whether the transport left them strings or keywords, `action` a keyword,
  `ns`/`name` symbols, a `where` map's keys keywordized the way a single
  `edit_subform` sees them. Everything else rides through untouched.

  A `:patch` step — {action: patch, ns, name, replace: [{match, source,
  text?, where?} …]} — EXPANDS here into one :subform step per entry:
  several small changes inside one form cost the model one compact step
  instead of a whole-form retype (eval11: retypes were 2.1x plain's output
  volume, and output is the slowest, priciest token). The server does the
  mechanical work; the ops layer never sees :patch."
  [steps]
  (let [kw  (fn [m] (into {} (map (fn [[k v]] [(keyword (name k)) v])) m))
        one (fn [s]
              (cond-> s
                (:action s) (update :action #(keyword (name %)))
                (:ns s)     (update :ns symbol)
                (:name s)   (update :name symbol)
                (map? (:where s)) (update :where kw)))]
    (into []
          (mapcat (fn [s]
                    (let [s (kw s)]
                      (if (and (:action s) (= "patch" (name (:action s))))
                        (for [e (:replace s)]
                          (one (assoc (kw e)
                                      :action :subform
                                      :ns (:ns s) :name (:name s))))
                        (if (and (nil? (:action s)) (nil? (:name s))
                                 (string? (:source s)))
                          ;; the whole-blob gesture (opus's native grain:
                          ;; whole heredoc files, fragmented 3x by the
                          ;; one-form rule): several top-level forms in one
                          ;; nameless step become per-form inferred steps
                          (let [nodes (:nodes (try (edit/parse-forms (:source s))
                                                   (catch Exception _ nil)))]
                            (if (< (count nodes) 2)
                              [(one s)]
                              (mapv #(one (assoc s :source (n/string %))) nodes)))
                          [(one s)])))))
          steps)))

(defn- terse-full-check
  "A GREEN whole-store check as the reader needs it: the verdict, the
  populations it examined (`:checked` — a read that ran on zero is visibly
  broken), the external tier's count and status, and every fact a reader
  branches on — standing findings (folded), the auto-declared edge count,
  alias drift as a count, an artifact behind the store, a standing verdict.
  The scaffolding goes: notes, per-namespace timings, the sweep plan, the
  in-image summary. Red keeps the full map, and so does `verbose`.

  eval10 s2: two green checks of ~19k chars each were trimmed at the gate
  and re-fetched whole through query_detail — 76k chars for a verdict."
  [r & {:keys [verbose?]}]
  (if (or verbose? (not= :green (:status r)))
    r
    (cond-> {:status :green :namespaces (:namespaces r) :checked (:checked r)}
      (:external r)        (assoc :external (select-keys (:external r) [:ran :status]))
      (:modules r)         (assoc :modules (dissoc (:modules r) :edges))
      (seq (get-in r [:rules :findings])) (assoc :findings (get-in r [:rules :findings]))
      (seq (:alias-drift r)) (assoc :alias-drift (count (:alias-drift r)))
      (:bundle r)          (assoc :bundle (select-keys (:bundle r) [:sha :behind]))
      (:app r)             (assoc :app (:app r))
      (:standing r)        (assoc :standing (:standing r))
      (:scope r)           (assoc :scope (:scope r))
      (:currency-broken r) (assoc :currency-broken (:currency-broken r))
      (:empty-namespaces r) (assoc :empty-namespaces (:empty-namespaces r))
      (:crossings r)       (assoc :crossings (:crossings r)))))

(defn- form-version
  "`[form-id hash-of-text]` for `ns-sym/nm` on the current store value — the
  identity the ledger keys on — or nil when there is no such form. The form
  id is stable across edits, so the text hash is the version."
  [session ns-sym nm]
  (when-let [e (store/form-named (:store @session) ns-sym nm)]
    [(:id e) (hash (n/string (:node e)))]))

(defn- ledger-held?
  "Is this `[form-id text-hash]` held by the reader of the CURRENT ask? The
  ledger stores the ask number it was recorded under, so a new ask forgets
  everything without a sweep — a stub must not outlive the reader it is
  about, and `told!` scopes the same way."
  [session v]
  (and v (= (get-in @session [::ledger v]) (::ask @session 0))))

(defn- ledger-hold!
  "Record `[form-id text-hash]` as held by the current ask's reader."
  [session v]
  (when v (swap! session assoc-in [::ledger v] (::ask @session 0))))

(defn- absorb-pending-intent!
  "Consume this session's pending intent when the plugin's prompt hook has
  left one. The hook writes {\"session-id\": …, \"prompt\": …} (a bare string
  is accepted as prompt-only). The session ADOPTS the harness session id as
  its identity — unless the environment already named the conversation, which
  is now the ordinary case and makes this path the FALLBACK — so every delta of one
  Claude session shares a key and concurrent sessions never merge episodes;
  the prompt is stashed as the next auto-turn's intent.

  **The mailbox is per-STORE, and two live sessions share the directory.**
  So the id in the file is a claim of OWNERSHIP, not just a label. Once this
  session has claimed one, an intent naming a DIFFERENT session is left
  where it lies: consuming it would take the other agent's identity and,
  through `adopt-line!`, its THREAD — resyncing this session's store and
  image off somebody else's line. That is the exact failure this promise
  ('concurrent sessions never merge episodes') exists to prevent, and it was
  observed live on 2026-08-27: seven writes by one session recorded under
  another's id, bracketed by turns carrying the other's verbatim asks.

  A claimed session prefers its OWN `pending-intent.<sid>` file, which is
  what a current hook writes alongside the legacy path; the unscoped file is
  still read so an older hook keeps working. An intent carrying no id at all
  has no owner to offend and is always taken.

  Consuming an intent also DRAINS `:pending-bundle-held` — the form versions
  the bundle endpoint emitted for the prompt now being absorbed — into the
  form ledger, under the ask that just opened: what the bundle injected, the
  session holds, so a read of a bundle-carried form is a reference. A stash
  recorded for a DIFFERENT harness id is dropped unheld; its bundle went
  into somebody else's context."
  [session]
  (when-let [dir (:dir @session)]
    (let [claimed (:intent-sid @session)
          scoped  (when claimed
                    (io/file dir ".slopp" (str "pending-intent." claimed)))
          legacy  (io/file dir ".slopp" "pending-intent")
          f       (cond (and scoped (.exists scoped)) scoped
                        (.exists legacy)              legacy
                        :else                         nil)]
      (when f
        (let [raw (slurp f)
              {:keys [sid prompt]}
              (or (try (let [m (json/parse-string raw true)]
                         (when (map? m)
                           {:sid (:session-id m) :prompt (:prompt m)}))
                       (catch Exception _ nil))
                  {:prompt raw})]
          ;; Somebody else's ask: leave the file, change nothing. Their server
          ;; is the one that can answer it.
          (when-not (and sid claimed (not= sid claimed))
            (.delete f)
            (when sid (swap! session assoc :intent-sid sid))
            (when (and sid (not (:pinned-agent? @session)))
              (swap! session assoc :agent-id sid)
              ;; identity settled → the session takes THIS agent's thread.
              ;; It is the first moment it can: the session opened before the
              ;; harness id existed, so its store and image were loaded from
              ;; the branch. If this agent left un-landed work last time, both
              ;; came from the wrong line — and only the store heals on its
              ;; own, which would leave verification grading the branch's code
              ;; against the thread's store.
              (engine/adopt-line! session))
            (when-not (str/blank? (or prompt ""))
              ;; a new ask is a new READER, potentially: /clear and automatic
              ;; compaction both land here and neither is visible any other
              ;; way. `told!` scopes its sent-view hashes to this counter, so
              ;; the first read of a view in a fresh context is always a
              ;; payload. It bumps for READ-ONLY asks too — turns do not, and
              ;; a read-only planning ask is where the withholding was first
              ;; hit.
              (swap! session #(-> %
                                  (assoc :pending-intent prompt :last-intent prompt)
                                  (update ::ask (fnil inc 0)))))
            ;; the bundle that rode in WITH this prompt: hold what the
            ;; endpoint stashed, under the ask that just opened
            (when-let [pb (:pending-bundle-held @session)]
              (when (or (nil? (:sid pb)) (nil? sid) (= (:sid pb) sid))
                (doseq [v (:versions pb)] (ledger-hold! session v)))
              (swap! session dissoc :pending-bundle-held))))))))

(defn- dedupe-sources!
  "The one pass over a read's result before it goes out: every map carrying
  a form's identity (`:ns`+`:name`, or a qualified `:form`) and its `:source`
  is checked against the ledger — held at this version, the source is
  replaced by `:source-already-sent true` (everything else on the row
  stays); not held, it is sent and recorded. Walks the shapes that carry
  source today: a vector of items (`query_source`), `:rows` (`orient`),
  `:target` (`query_slice`), or the map itself (`query_brief`). A source
  whose text is not the form's current text — a window, an older version —
  is never a reference.

  Why: `told!` stubs a whole payload the same call already returned and
  knows nothing about forms, so orient → slice → query_source of one form
  sent its text three times, and the read after the agent's own write sent
  back what the agent had typed. eval10 measured reads at 52% of all output."
  [session x]
  (let [row (fn [m ns-sym nm]
              (let [s (:source m)
                    v (when (string? s) (form-version session ns-sym nm))]
                (cond
                  (nil? v)                    m
                  (not= (second v) (hash s))  m
                  (ledger-held? session v)    (-> m (dissoc :source) (assoc :source-already-sent true))
                  :else                       (do (ledger-hold! session v) m))))
        one (fn [m]
              (cond
                (not (map? m)) m
                (and (:ns m) (:name m) (contains? m :source))
                (row m (symbol (str (:ns m))) (symbol (str (:name m))))
                (and (symbol? (:form m)) (namespace (:form m)) (contains? m :source))
                (row m (symbol (namespace (:form m))) (symbol (name (:form m))))
                :else m))]
    (cond
      (vector? x) (mapv one x)
      (map? x)    (cond-> (one x)
                    (vector? (:rows x)) (update :rows #(mapv one %))
                    (map? (:target x))  (update :target one))
      :else       x)))

(defn- told!
  "Knowledge-differential reads: the session keeps a hash of every
  cacheable VIEW it has sent, SCOPED TO THE CURRENT ASK; an identical
  re-read within that ask returns a tiny :unchanged stub instead of the
  payload. Re-fetching becomes FREE, so agents never carry views in
  context 'just in case' — the whole don't-hoard stance depends on cheap
  re-asks, and reads are 52% of all output. Any store change alters the
  payload, so staleness is impossible by construction.

  **The ask scope is the correction, and it is about WHOSE knowledge this
  is.** The record lives in the SERVER session, which lasts for the
  process; the claim it makes is about the READER, which resets on
  `/clear`, on automatic compaction, and for every subagent. Unscoped, the
  two diverged and the stub said \"you already know\" to a context that had
  never seen it — measured twice, once mid-plan after a clear and once
  mid-build after an automatic compact. Not staleness: WITHHOLDING, with
  absence-of-payload wearing absence-of-change's clothes.

  The ASK is the boundary and the TURN is not: turns rotate on the
  write-tool gate, so a read-only planning ask never rotates one — and that
  is precisely where this was first hit. `absorb-pending-intent!` bumps
  `::ask` for every prompt the hook records, read-only included.

  `:detail` is the escape for the case the scope cannot cover: a subagent
  shares the session and runs inside the parent's ask, so it can be told
  \"you already know\" about something it has never seen. The payload is
  spooled and the id named, which is the same door `query_detail` already
  opens for trimmed responses — previously the only way to a read-only
  tool's withheld payload was a write-capable tool that prompts for
  permission in plan mode.

  `resend: true` is the reader's own escape, WITHIN an ask: compaction
  replaces the transcript with a summary MID-ASK, invisibly to this wire,
  and source text is exactly what a summary drops — so every claim above
  about what the reader holds can be false and the server cannot know.
  Only the reader knows it is reading a summary of itself. resend bypasses
  the stub AND the form-ledger references for THIS call and records
  nothing new; the dedup stays the default everywhere else."
  [session tool a payload]
  (if (:resend a)
    payload
    (let [;; the FORM ledger first: a source the ask already holds at this
          ;; version leaves as a reference, whichever view carries it
          payload (dedupe-sources! session payload)
          k [tool (select-keys a [:ns :name :targets :since :detail :depth
                                  :limit :contains :full :at :collapse :format
                                  :on :direction])]
          h     [(get @session ::ask 0) (hash payload)]
          p-str (pr-str payload)
          stub  {:already-sent true
                 :view (str tool (when (:ns a) (str " " (:ns a)))
                            (when (:name a) (str "/" (:name a))))
                 :note (str "already sent in this ask — about what YOU received,"
                            " NOT whether the store changed (an outline does not"
                            " move when a body does). query_detail {:detail}"
                            " re-opens it.")}]
      (if (and (= h (get-in @session [::told k]))
               ;; a stub bigger than what it withholds is not a saving, it is a
               ;; round trip for nothing. This was a constant (130 chars) chosen
               ;; when the stub was one short sentence; the note then grew and
               ;; the floor did not, so small views started costing MORE to
               ;; withhold than to send. Measuring the actual stub cannot drift
               ;; out of step with the stub the way a number written down
               ;; elsewhere can. The id is a representative one — they are all
               ;; the same length — because it cannot be minted before the
               ;; decision to spool.
               (< (count (pr-str (assoc stub :detail "s00000000000")))
                  (count p-str)))
        (let [id (spool! session p-str)]
          ;; a stub is a withholding, not a saving, until nobody opens it —
          ;; recorded through the same channel as a trim so one fold can ask
          ;; both paths the question
          (note-response! {:stub? true :spooled id})
          (assoc stub :detail id))
        (do (swap! session assoc-in [::told k] h)
            payload)))))

(defn- ledger-written!
  "After a write whose FULL source the agent sent landed (`edit_add_form`,
  `edit_replace_form`, a group's add/replace steps), hold the stored
  version of every form it wrote: the agent has that text in hand, and the
  read that used to follow a write to check it is now a reference. A
  subform edit sends a fragment, so it holds nothing."
  [session op a]
  (let [nm-of  (fn [src] (keep #(some-> % store/form-symbol)
                               (:nodes (edit/parse-forms (str src)))))
        forms  (case op
                 "edit_replace_form" [[(:ns a) (:name a)]]
                 "edit_add_form"     (for [nm (nm-of (:source a))] [(:ns a) nm])
                 "edit_group"        (for [s (wire-steps (:steps a))
                                           :when (#{:add :replace} (:action s))
                                           nm (if (= :add (:action s)) (nm-of (:source s)) [(:name s)])]
                                       [(:ns s) nm])
                 nil)]
    (doseq [[ns-sym nm] forms :when (and ns-sym nm)]
      (ledger-hold! session (form-version session (symbol (str ns-sym)) (symbol (str nm)))))
    nil))

(defn- held-after-write!
  "Threaded into a write branch: when the write LANDED, hold the forms its
  full source wrote (`ledger-written!`); returns `r` either way."
  [r session op a]
  (when (nil? (:error r)) (ledger-written! session op a))
  r)

(defn- propose-assertion
  "For a failure whose assertion is `(= literal expr)` (either order) and whose
  actual is `(not (= a b))` with both sides scalar literals, the one
  `edit_subform {text true}` that accepts the new behaviour: `{:match <the
  assertion as written> :source <the same with the literal replaced> :note}`.
  nil for anything else — a computed expected, an error, a non-equality
  assertion, a collection (the agent should not accept a collection blind).

  Why: after a deliberate behaviour change the agent read the failing test,
  found the literal and rewrote it — three calls for \"1400 is now 1600\".
  clojure.test already reports the assertion form and the actual value, and
  when both sides are literals the update is mechanical; saying it is what
  turns a red into an accept-or-refuse rather than a read-and-rewrite."
  [{:keys [expected actual]}]
  (let [scalar? (fn [x] (or (number? x) (string? x) (keyword? x) (boolean? x) (nil? x)))
        read    (fn [s] (try (clojure.edn/read-string (str s)) (catch Exception _ ::unreadable)))
        e       (read expected)
        a       (read actual)]
    (when (and (seq? e) (= '= (first e)) (= 3 (count e))
               (seq? a) (= 'not (first a)) (= 2 (count a))
               (let [inner (second a)] (and (seq? inner) (= '= (first inner)) (= 3 (count inner)))))
      (let [[_ x y]   e
            [_ p q]   (second a)
            lit-left? (and (scalar? x) (not (scalar? y)))
            lit-right? (and (scalar? y) (not (scalar? x)))
            old       (cond lit-left? x lit-right? y)
            new       (when (and (scalar? p) (scalar? q))
                        (cond (= p old) q (= q old) p))]
        (when (and (or lit-left? lit-right?) (some? new) (not= new old))
          {:match  (str expected)
           :source (pr-str (if lit-left? (list '= new y) (list '= x new)))
           :note   (str "accept the new behaviour with edit_subform {ns name match source text true};"
                        " or the change is wrong and the test is right")})))))

(defn- summarize
  "B1: a green-and-quiet edit result compresses to a terse shape (the Go
  baseline showed slopp's verbose green responses were the token loser).
  :error, NEW red failure detail, or NEW warnings return the full map — a
  red that carries only :still-red names (episode compression) stays
  TERSE. Source echoes are stripped EVERYWHERE (Q1); :untested is a terse
  FLAG; a zero-test verification says :coverage :none (Q8); the :type
  :summary tag is internal and never rides the wire.

  The terse path SHAPES what a layer returned; it does not re-decide it.
  Everything in `tools/wire-keys` passes through, and only the bulky keys
  are compressed (a delta to its id, a delta list and an affected set to
  their counts, a verification to the rebuilt `:test`). That direction is
  the registry's own argument, applied one layer later: an allowlist here
  is a SECOND independent guess at what an agent should see, and it lost
  the same way the fourteen per-tool lists did.

  Measured when this was fixed: of the 39 routed keys, 21 arrived and 21
  were dropped — among them `:callers` (so `edit_move_forms` reported the
  same result whether it rewrote twelve call sites or none),
  `:export-not-landed` (a postcondition that did NOT hold), and
  `:unknown-shape` (the call sites a rewrite could not reach, which are the
  caller's to check by hand). Each looked like a missing feature rather
  than a dropped one.

  So: an empty collection from a layer is a FINDING — it looked and found
  none — and it rides the wire as one. A key the layer omits is the layer
  saying nothing. Deciding which is which is not this function's job."
  [r verbose?]
  (let [strip (fn [d] (if (map? d) (dissoc d :source :sources :node) d))
        r     (cond-> r
                (:delta r)        (update :delta strip)
                (seq (:deltas r)) (update :deltas (partial mapv strip)))]
    (if (or verbose? (:error r) (seq (:warnings r))
            (and (red? (:test r)) (seq (:failures (:test r)))))
      (-> r
          (update :test #(if (map? %) (dissoc % :type) %))
          ;; a red with a literal delta names the one edit that accepts it
          (update :test (fn [t]
                          (if (and (map? t) (seq (:failures t)))
                            (update t :failures
                                    (fn [fs] (mapv #(if-let [p (propose-assertion %)]
                                                      (assoc % :proposed p)
                                                      %)
                                                   fs)))
                            t))))
      (let [t (:test r)]
        (cond-> (assoc (apply dissoc (select-keys r tools/wire-keys) terse-elided) :ok true)
          (:delta r)    (assoc :delta (get-in r [:delta :id]))
          (:deltas r)   (assoc :deltas (count (:deltas r)))
          (:untested r) (assoc :untested true)
          ;; a PREVIEW's payload is the whole point of asking for one —
          ;; dropping it here made dry-run look like a silent no-op
          (:dry-run r)  (assoc :dry-run true)
          ;; the covering tests by NAME when there are few — "verified by
          ;; base-t and quad-t" is what makes a re-run visibly redundant; a
          ;; count only says something ran
          (:affected r) (assoc :affected (let [a (:affected r)]
                                           (cond (= :all a)        :all
                                                 (<= (count a) 8)  (vec a)
                                                 :else             (count a))))
          t             (assoc :test (cond-> {:ran (:test t 0) :pass (:pass t 0)
                                              ;; a run that executed NOTHING is unverified, not green — green must
                                              ;; mean tests ran and passed, or an agent learns to distrust
                                              ;; the status and re-run them by hand
                                              :status (cond
                                                        (red? t)                    :red
                                                        (zero? (:test t 0))         :unverified
                                                        ;; impacted ^:external tests were DEFERRED — whatever
                                                        ;; passed here, it wasn't those. Writing an ^:external
                                                        ;; deftest reported :green off its neighbours in the same
                                                        ;; namespace: a red-first spec reporting success.
                                                        (seq (:external-pending t)) :partial
                                                        :else                       (:status t :green))
                                              :scope (:scope t)}
                                       (:staleness-detected t)  (assoc :staleness-healed true)
                                       (zero? (:test t 0))      ;; name the CAUSE. "no test covers this yet" is the agent's to fix;
                                       ;; "the scope ran nothing" is a slopp bug. Collapsing the
                                       ;; two is how an empty verification fallback hid, looking
                                       ;; like an ordinary untested form.
                                       (assoc :coverage :none
                                              :reason (cond
                                                        ;; nothing ran because everything impacted is
                                                        ;; ^:external — by DESIGN, and the done point
                                                        ;; will run them. Not a gap, not a bug.
                                                        ;; a lower layer already NAMED the reason (e.g. a :cljs write,
                                                        ;; :cljs-deferred-to-compile) — respect it over the generic guesses
                                                        (:reason t)                 (:reason t)
                                                        (seq (:external-pending t)) :all-impacted-external
                                                        (= :all (:affected r))      :no-covering-tests
                                                        :else                       :scope-ran-nothing))
                                       (red? t)                 (assoc :fail (+ (:fail t 0) (:error t 0)))
                                       (seq (:still-red t))     (assoc :still-red (:still-red t))
                                       (seq (:went-green t))    (assoc :went-green (:went-green t))
                                       ;; WHICH tests are deferred, not merely that some are — a
                                       ;; bare :partial an agent cannot act on becomes noise it
                                       ;; learns to skip
                                       (seq (:external-pending t))
                                       (assoc :external-pending (:external-pending t)))))))))

(defn- deep-kw
  "Keywordize map keys recursively — a batch entry's nested arguments
  (targets, where) arrive however the transport shaped them, exactly as
  `wire-steps` handles for a group's steps."
  [x]
  (cond
    (map? x)        (into {} (map (fn [[k v]] [(keyword (name k)) (deep-kw v)])) x)
    (sequential? x) (mapv deep-kw x)
    :else           x))

(defn- finish-accepted!
  "The deterministic finisher (v1): the write's `accept` names the tests
  whose LITERAL expectations the change was meant to move — the agent's
  own judgment, made at write time, recorded in the write. For each
  failing accepted test with exactly ONE literal→literal delta
  (`propose-assertion`), apply the proposed update as its own delta (one
  group, prompt = the acceptance + the write's prompt) and let that
  group's verification stand as the result's `:test`; report
  `:finisher {:applied [{:test :was :now}] :status …}` in the SAME result
  — a was/now pair, because a bare symbol says a literal changed without
  saying to what (slopp-ui). A test with SEVERAL literal deltas is skipped
  and named under `:skipped-multi`: one delta is the case the writer
  judged; several is the case they probably did not, and the assertion
  messages around them may rationalize the old literals. A failing test
  NOT accepted, or without a literal delta, rides untouched. An accepted
  test that did not fail is `:accept-unused`. The model-judge seam sits
  here; the action space stays proposed updates."
  [r session a]
  (let [accept (into #{} (map str) (:accept a))]
    (if (empty? accept)
      r
      (let [failures (get-in r [:test :failures])
            failing  (into #{} (map (comp str :test)) failures)
            by-test  (group-by :test (for [f failures
                                           :when (contains? accept (str (:test f)))
                                           :let  [p (propose-assertion f)]
                                           :when p]
                                       {:test (:test f) :proposed p}))
            multi    (vec (sort (keys (filter #(next (val %)) by-test))))
            eligible (mapcat val (remove #(next (val %)) by-test))
            unused   (vec (sort (map symbol (remove failing accept))))]
        (cond-> r
          (seq eligible)
          (as-> r*
            (let [steps (vec (for [{:keys [test proposed]} eligible]
                               {:action :subform
                                :ns     (symbol (namespace test))
                                :name   (symbol (clojure.core/name test))
                                :text   true
                                :match  (:match proposed)
                                :source (:source proposed)}))
                  fr    (ops/edit-group! session steps
                                         :prompt (str "accepted expectation shift declared by the write: "
                                                      (:prompt a))
                                         :agent (:agent a)
                                         :no-auto-require true)]
              (if (:error fr)
                (assoc r* :finisher {:applied [] :status :refused :error (:error fr)})
                (-> r*
                    (assoc :test (:test fr))
                    (update :deltas (fnil into []) (:deltas fr))
                    (assoc :finisher
                           (cond-> {:applied (vec (sort-by (comp str :test)
                                                           (map (fn [{:keys [test proposed]}]
                                                                  {:test test
                                                                   :was (:match proposed)
                                                                   :now (:source proposed)})
                                                                eligible)))
                                    :status  (if (red? (:test fr)) :red :green)}
                             (seq multi)
                             (assoc :skipped-multi multi
                                    :note (str "several literal deltas in one accepted test —"
                                               " one is the case you judged, several is the case"
                                               " you probably did not: re-read the test (its"
                                               " assertion messages may rationalize the old"
                                               " literals) and move them yourself"))))))))
          (and (empty? eligible) (seq multi))
          (assoc :finisher {:applied []
                            :skipped-multi multi
                            :status :skipped
                            :note (str "several literal deltas in one accepted test — one is"
                                       " the case you judged, several is the case you probably"
                                       " did not: re-read the test and move them yourself")})
          (seq unused)
          (assoc :accept-unused unused))))))

(defn- attach-red-context!
  "Result-carried orientation: a write that lands RED attaches, to each
  NEWLY red failure entry, `:test-src {:ns :name :source}` — the failing
  test's CURRENT source — so the next call can be the fix rather than a
  read. Sibling of `:source-now` (a match miss returns the form's current
  text) and `:proposed` (the literal fix itself). The source goes through
  `dedupe-sources!`, the same ledger door every read takes: a test this ask
  already holds arrives as `:source-already-sent`, and a later read of an
  attached test is a reference — write results and reads share ONE ledger.
  Failures are already capped upstream (`traced-run`) and episode-compressed
  (`shape-episode-reds!` collapses already-reported reds to names), so only
  fresh failures pay, and each at most once per ask."
  [r session]
  (if (empty? (get-in r [:test :failures]))
    r
    (update-in r [:test :failures]
               (fn [fs]
                 (mapv (fn [f]
                         (let [t (:test f)]
                           (if (and (symbol? t) (namespace t))
                             (let [ns-sym (symbol (namespace t))
                                   nm     (symbol (clojure.core/name t))
                                   e      (store/form-named (:store @session) ns-sym nm)]
                               (if e
                                 (assoc f :test-src
                                        (dedupe-sources! session
                                                         {:ns ns-sym :name nm
                                                          :source (n/string (:node e))}))
                                 f))
                             f)))
                       fs)))))

(defn- anticipated!
  "Append the require expansion to a read's answer: for each namespace in
  `nses` (just read whole), attach the sources of its direct requires the
  session has not been handed yet — `anticipate/expansion`, ~2k token
  budget — as rows marked `:anticipated true`, and remember what was
  attached so nothing rides twice. Returns the (possibly vectorized)
  answer; the attachment lives in the SAME vector `dedupe-sources!` walks.

  Why rows and not prose: the next read the model would have made is now
  already in context, and the ledger record (models do not re-ask for
  sources they hold — `:source-already-sent` measured this) is what turns
  an attachment into a deleted turn. Session-scoped memory, not ask-scoped:
  over-remembering only costs a skipped re-attachment, never a wrong one."
  [res session nses]
  (let [held (into (or (::anticipated @session) #{}) nses)
        st   (:store @session)
        rows (->> nses
                  (mapcat #(anticipate/expansion st % held 2000))
                  (map #(assoc % :whole true :anticipated true))
                  (reduce (fn [{:keys [seen out left]} r]
                            (if (or (seen (:ns r)) (< left (:tokens r)))
                              {:seen seen :out out :left left}
                              {:seen (conj seen (:ns r))
                               :out (conj out r)
                               :left (- left (:tokens r))}))
                          {:seen #{} :out [] :left 2000})
                  :out)]
    (swap! session update ::anticipated (fnil into #{})
           (into (set nses) (map :ns rows)))
    (if (empty? rows)
      res
      (let [rows (conj rows {:anticipation-note
                             (str "attached: what these require — already in"
                                  " hand, no need to read them")})]
        (if (vector? res) (into res rows) (into [res] rows))))))

(defn- create-leading-ns!
  "Steps whose source IS an `(ns …)` form for a namespace the store does not
  have yet: create each namespace (the same `create-ns!` a scaffold uses)
  and return `{:steps <the rest> :created [create-results]}` — or
  `{:error …}` when a create refuses. The whole-file heredoc gesture — how
  every model writes a NEW file — leads with its ns form; without this the
  blob refused with \"no namespace — ingest it first\" (probed live, s13).
  An ns form for an EXISTING namespace is not a create: it stays in the
  group and replaces — a require edit arriving by blob."
  [session steps a]
  (let [st       (:store @session)
        ns-step? (fn [s]
                   (and (nil? (:action s)) (string? (:source s)) (:ns s)
                        (nil? (get-in st [:namespaces (symbol (str (:ns s)))]))
                        (let [sx (some-> (edit/parse-form (:source s)) :node
                                         (as-> nd (try (n/sexpr nd)
                                                       (catch Exception _ nil))))]
                          (and (seq? sx) (= 'ns (first sx))
                               (= (symbol (str (:ns s))) (second sx))))))
        creates  (filter ns-step? steps)
        results  (reduce (fn [acc s]
                           (let [r (ops/create-ns! session (symbol (str (:ns s)))
                                                   :source (:source s)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a))]
                             (if (:error r) (reduced r) (conj acc r))))
                         [] creates)]
    (if (map? results)
      {:error (:error results)}
      {:steps (vec (remove ns-step? steps)) :created results})))

(def ^:private change-handlers!
  "The write VERB (s11) plus its aliases, and `check`. `change`: a whole
  unit of work as one call — tests land first and the result reports which
  went RED (watched, red-first honored), impl lands, accepted shifts
  finish, ONE verification, one result — and NO done inside: done is the
  agent's separate this-unit-is-finished move (a unit may span change ->
  explore -> change; the Stop hook is the landing floor), which also
  removes any red special case — a red change is a red result with
  :test-src, nothing landed, nothing lost. `check`: assertion code run in
  the image with clojure.test reporting CAPTURED — nothing written; the
  diagnostic red as an ANSWER. (`explore` dispatches as a case label on
  the batch branch — an entry here would close the load cycle
  call-op! -> tail-handlers! -> this map -> call-op!.)"
  (let [change-fn
        (fn [session a _sym]
          (let [tests0 (wire-steps (or (:tests a) []))
                impl0  (wire-steps (or (:impl a) []))
                born   (create-leading-ns! session (into tests0 impl0) a)
                keep?  (if (:error born) (constantly true) (set (:steps born)))
                tests  (vec (filter keep? tests0))
                impl   (vec (filter keep? impl0))]
            (if (or (:error born) (empty? impl))
              (cond
                (:error born)
                (text! {:error (:error born) :phase :create})

                (seq (:created born))
                ;; the blob WAS a namespace creation — create-ns! landed and
                ;; verified it; nothing is left for the group
                (text! {:ok true :status :green
                        :created (mapv #(select-keys % [:ns :forms :test :warnings])
                                       (:created born))})

                :else
                (text! {:error (str "change needs :impl steps — a question is explore;"
                                    " a test you are writing to FIND something out is"
                                    " explore {ops [{op check code …}]}")}))
              (let [rt (when (seq tests)
                         (ops/edit-group! session tests
                                          :prompt (str (:prompt a) " [tests first — expected red]")
                                          :agent (:agent a)))]
                (if (and rt (:error rt))
                  (text! (assoc (select-keys rt [:error :step :source-now]) :phase :tests))
                  (let [went-red (vec (:failed-tests (:test rt)))
                        _ (when rt (ledger-written! session "edit_group" {:steps tests}))
                        ri (ops/edit-group! session impl
                                            :prompt (:prompt a) :agent (:agent a))]
                    (if (:error ri)
                      (text! (cond-> (assoc (select-keys ri [:error :step :source-now])
                                            :phase :impl)
                               (seq tests) (assoc :tests {:landed (count tests)
                                                          :went-red went-red})))
                      (let [ri (-> ri
                                   (held-after-write! session "edit_group" {:steps impl})
                                   (finish-accepted! session a)
                                   (attach-red-context! session))]
                        (text!
                         (cond->
                          {:ok true
                           :impl (select-keys ri [:group :steps :warnings :drift
                                                  :red-first :note])
                           :test (:test ri)
                           :status (if (red? (:test ri)) :red :green)}
                           (seq tests)
                           (assoc :tests
                                  (cond-> {:landed (count tests) :went-red went-red}
                                    (empty? went-red)
                                    (assoc :note (str "the tests landed GREEN — the"
                                                      " spec was never watched failing;"
                                                      " a green you did not watch fail"
                                                      " proves nothing"))))
                           (:finisher ri)      (assoc :finisher (:finisher ri))
                           (:accept-unused ri) (assoc :accept-unused (:accept-unused ri))
                           (red? (:test ri))
                           (assoc :note (str "red — nothing landed, and nothing is"
                                             " LOST: the tests and impl are on your"
                                             " thread. Fix forward from :test-src;"
                                             " done is YOUR move when the unit is"
                                             " finished."))))))))))))]
    {"change" change-fn
     "intent" change-fn
     "check"
     (fn [session a _sym]
       (let [wrapped (str "(do (require (quote clojure.test))"
                          " (let [slopp-check-reports (atom [])]"
                          "  (binding [clojure.test/report"
                          "            (fn [m] (swap! slopp-check-reports conj"
                          "                      (select-keys m [:type :expected :actual :message])))]"
                          "   (let [slopp-check-value (do " (:code a) ")]"
                          "    {:value slopp-check-value"
                          "     :assertions (deref slopp-check-reports)}))))")
             r (ops/query-eval session wrapped)]
         (text!
          (if (map? r)
            r
            (let [{:keys [value assertions]} (first r)
                  types (frequencies (map :type assertions))]
              {:value value
               :pass (:pass types 0) :fail (:fail types 0)
               :errors (:error types 0)
               :assertions (vec assertions)
               :note (str "nothing was written — this red is an ANSWER; land"
                          " it as a test (change {tests […]}) only once it"
                          " says what you mean")})))))}))

(def ^:private tail-handlers!
  "Every handler-map entry (Q4) — call-tool checks here first."
  (merge env-handlers! file-handlers! sync-handlers! change-handlers!))

(defn- call-op! [session {:keys [name arguments]}]
  ;; async-image boot: the store loaded synchronously (this dispatch is live),
  ;; but the image may still be warming on a background thread. Oracle and
  ;; write tools wait for it here; store-value reads serve immediately.
  (when-let [bad (tools/unknown-arg-keys name arguments)]
    (throw (ex-info (str "unknown argument" (when (next bad) "s") " "
                         (str/join ", " (map #(str ":" (clojure.core/name %)) bad))
                         " for " name " — a mistyped or unsupported argument is"
                         " refused, not ignored. accepted: "
                         (str/join " " (sort (map #(str ":" (clojure.core/name %))
                                                  (tools/accepted-arg-keys name)))))
                    {:tool name :unknown (vec bad)})))
  ;; and a MISSING required key, by name: the family schema cannot carry an
  ;; op's required keys for the client, so the server says what to add
  (when-let [missing (tools/missing-required-keys name arguments)]
    (throw (ex-info (let [ks (map #(str ":" %) missing)]
                      (str name " needs "
                           (if (next ks)
                             (str (str/join ", " (butlast ks)) " and " (last ks))
                             (first ks))
                           " — required for this op"))
                    {:tool name :missing (vec missing)})))
  (when-not (contains? tools/image-free-tools name)
    (ops/await-image! session))
  (ops/sync-with-journal! session)      ; m5b: absorb other servers' commits      ; m5b: absorb other servers' commits
  (absorb-pending-intent! session)
  ;; A NEW ASK IS A NEW TURN. The gate used to open one only when none was
  ;; open, and nothing ever closed one, so a single turn spanned an entire
  ;; session: measured on slopp's own store, five :turn-begin deltas across
  ;; ~15 asks and ZERO :turn-end. Two things were lost by that — the turn's
  ;; wall-clock timing, which rides turn-end and therefore never landed, and
  ;; worse, EVERY ASK AFTER THE FIRST, which never reached the journal at all.
  ;; Rotating costs two marker deltas per ask.
  (when (and (:pending-intent @session)
             (:require-turns? @session)
             (contains? tools/write-tools name)
             (not (#{"done" "commit_point"} name)))
    (let [ag (or (:agent arguments) (:agent-id @session))]
      (when (ops/turn-open? session ag)
        (ops/turn-end! session :agent ag))))
  ;; THE READ RING, on its own schedule. This condition is about where a delta
  ;; may be WRITTEN and nothing else: a write tool is already writing, while
  ;; every read tool declares `readOnlyHint` on the wire and a harness may run
  ;; it unprompted on that promise. So the flush rides the next write rather
  ;; than the read that filled the ring, and `flush-reads!` decides whether a
  ;; span is due.
  ;;
  ;; Deliberately NOT folded into the rotation gate above, which is the whole
  ;; fix. That gate also wants a user PROMPT, so a session driven by
  ;; background events never passes it however much it writes — measured at
  ;; zero read records across 321 closed turns here and 118 in the consuming
  ;; store. `done` and `commit_point` force a flush because they are the work
  ;; boundaries an agent actually has, and unlike a turn they need nobody to
  ;; have typed anything.
  (when (contains? tools/write-tools name)
    (ops/flush-reads! session :force? (boolean (#{"done" "commit_point"} name))))
  (when (and (:require-turns? @session)
             (contains? tools/write-tools name)
             ;; done/commit_point CLOSE work; always allowed
             (not (#{"done" "commit_point"} name)))
    (let [ag (or (:agent arguments) (:agent-id @session))]
      (when-not (ops/turn-open? session ag)
        (if-let [intent (:pending-intent @session)]
          ;; the plugin's prompt hook captured the user's verbatim ask —
          ;; the turn opens itself (zero-ceremony turns)
          (do (swap! session dissoc :pending-intent)
              (ops/turn-begin! session :agent ag :intent intent))
          ;; NAME THE CAUSE. "no open turn" alone reads as "the turn did not
          ;; take", which sends you to look at turns; the usual real cause is
          ;; that no ask ever arrived FOR THIS STORE. The plugin's prompt hook
          ;; writes the pending intent into the store at the session's CWD, so a
          ;; session driving a SECOND store (a cross-wired .mcp.json — allowed,
          ;; since the server takes a dir) never gets one here and the turn
          ;; cannot open itself. Both facts are in hand: the dir, and that no
          ;; pending intent has arrived. Friction 4.
          ;; the write's own `prompt` IS the intent the gate asks for. Under a
          ;; harness whose prompt hook never ran (claude -p, a second store),
          ;; every first write used to be refused and the agent paid two turns
          ;; to open a turn by hand with the same words it had just sent.
          (if-let [p (not-empty (str (:prompt arguments)))]
            (ops/turn-begin! session :agent ag :intent p)
            (throw (ex-info (str "no open turn for agent " ag
                                 " — a write carrying `prompt` opens its own turn;"
                                 " otherwise call turn_begin {intent: <the user's"
                                 " verbatim ask>, agent: \"" ag "\"} first"
                                 (when-let [d (not-empty (str (:dir @session)))]
                                   (str " (store: " d ")"))
                                 ". No pending intent has arrived for this store:"
                                 " the prompt hook records the ask in the store at"
                                 " the session's WORKING DIRECTORY, so a session"
                                 " driving a second store has to open turns here"
                                 " by hand. And a turn belongs to an AGENT: a"
                                 " one-shot process (slopp --call) derives a fresh"
                                 " identity per process, so turn_begin and the"
                                 " write must be passed the SAME agent argument —"
                                 " otherwise the second call opens a second turn"
                                 " and this refusal repeats verbatim.")
                            {:dir (:dir @session) :agent ag})))))))
  (let [a   (assoc arguments :agent (or (:agent arguments)
                                        (:agent-id @session)))
        sym (fn [k]
              (if-let [v (get a k)]
                (symbol v)
                (throw (ex-info (str "missing required argument :"
                                     (clojure.core/name k) " for " name
                                     (get {["query_history" :ns]
                                           (str " — asking store-wide? report {since}"
                                                " composes the whole story;"
                                                " query_commits lists milestones")}
                                          [name k] ""))
                                {}))))
        ;; source args are passed raw (not through `sym`), so a misnamed key
        ;; (`new_source` for `source`) silently became nil and fell through to a
        ;; confusing "got 0 forms" parse error. `src` validates a required source
        ;; the way `sym` validates a symbol — and names the alias it caught.
        src (fn [k]
              (let [v (get a k)]
                (if (and v (not (str/blank? (str v))))
                  v
                  (let [alt (some (fn [k2]
                                    (when (and (not= k2 k)
                                               (not (str/blank? (str (get a k2)))))
                                      k2))
                                  [:new_source :new-source :new_src :newsource :src :source-code])]
                    (throw (ex-info (str "missing required argument :"
                                         (clojure.core/name k) " for " name
                                         (if alt
                                           (str " — you passed :" (clojure.core/name alt)
                                                "; the form source goes in :"
                                                (clojure.core/name k))
                                           " (the form source text)")
                                         ".")
                                    {}))))))]
    (if-let [h (tail-handlers! name)]
      (h session a sym)
      (case name
      "ns_create" (text! (ops/create-ns! session (sym :ns)
                                                :requires (:requires a)
                                                :source (:source a)
                                                :platform (:platform a)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "ns_add_require" (text! (-> (ops/add-require! session (sym :ns) (:require a)
                                                      :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "query_project" (text! (told! session name a
                                        (query/query-project session :since (:since a)
                                                          :detail (:detail a))))
      "query_search" (text! (query/query-search session (:pattern a)
                                                  :limit (or (:limit a) 30)))
      ("query_batch" "explore")
      ;; several READ questions, one call — THE question verb (explore;
      ;; query_batch is its compat alias). Measured on every eval cell of
      ;; both cohorts: no model ever emitted two tool_use blocks in one
      ;; message, so the batch lives INSIDE the call. Each entry recurses
      ;; through call-op! — validation, told!, the form ledger and the
      ;; per-entry size gate all apply — and a write op refuses before
      ;; anything runs.
      (let [ops (mapv deep-kw (:ops a))
            bad (some (fn [o]
                        (let [nm (some-> (:op o) clojure.core/name)]
                          (cond
                            (nil? nm) {:error "every explore entry needs :op — a read op name"}
                            (not (contains? tools/read-only-tools nm))
                            {:error (str nm " is a write — explore is READ questions only;"
                                         " writes have their own grain (change)")}
                            :else nil)))
                      ops)]
        (cond
          (empty? ops)         (text! {:error "explore needs ops — [{op …args} …]"})
          bad                  (text! bad)
          (< 6 (count ops))    (text! {:error (str (count ops) " entries — explore is at most 6;"
                                                   " past that the answer outgrows the reader")})
          :else
          (text! {:results (mapv (fn [o]
                                   (let [nm (clojure.core/name (:op o))]
                                     {:op nm
                                      :result (get-in (call-op! session {:name nm
                                                                         :arguments (dissoc o :op)})
                                                      [:content 0 :text])}))
                                 ops)}
                 :budgeted? true)))
      "query_source" (text! (told! session name a
                                        (let [full?   (:full a)
                                              gate    (fn [n]
                                                        ;; a SMALL namespace is one read — the
                                                        ;; outline-then-targets two-step cost opus
                                                        ;; 13 query_source calls per cell on
                                                        ;; namespaces of ten forms
                                                        (let [src   (query/query-source session n)
                                                              src   (if (string? src) src (:source src))
                                                              chars (count (str src))]
                                                          (if (and src (<= chars 6000))
                                                            {:ns n :source src :whole true}
                                                            ;; no size in the payload: an outline must
                                                            ;; stay identical across body edits, which is
                                                            ;; what makes its re-read a stub
                                                            {:ns n
                                                             :whole false
                                                             :outline (:forms (query/query-outline session n))
                                                             :note (str "outline — the namespace is over 6k"
                                                                        " chars; name the forms you need"
                                                                        " (targets [{ns name}]) or pass"
                                                                        " full: true for it all")})))]
                                          ;; whole-ns reads walk the require graph one edge
                                          ;; per turn (eval12 wave A) — hand the next
                                          ;; edge over with this one
                                          (anticipated!
                                           (if-let [ts (some-> (:targets a) normalize-targets seq)]
                                             (mapv (fn [t]
                                                     (if (or full? (:name t))
                                                       (first (query/query-sources session [t]))
                                                       (gate (:ns t))))
                                                   ts)
                                             (if full?
                                               (query/query-source session (sym :ns))
                                               (gate (sym :ns))))
                                           session
                                           (if-let [ts (some-> (:targets a) normalize-targets seq)]
                                             (set (keep :ns ts))
                                             #{(sym :ns)})))))
      "query_detail" (do
                           ;; WHAT it went back for, recorded whether or not the
                           ;; spool still has it: a retrieval is the evidence a
                           ;; withholding was paid for twice, and the tool that
                           ;; minted this id is the one it should be charged to
                           (note-response! {:detail-asked (:id a)})
                           (if-let [full (get-in @session [::spool :entries (:id a)])]
                             ;; the retrieval path must NOT re-trim its own payload
                             {:content [{:type "text" :text full}]}
                             (text! {:error (str "no spooled response " (:id a)
                                                 " — the spool keeps the last "
                                                 spool-cap " trimmed responses")})))
      "query_brief" (text! (told! session name a (query/query-brief session (sym :ns) (sym :name))))
      "orient" (text! (told! session name a
                            (orient/orient-map session
                                               :ask (:ask a)
                                               :seeds (:seeds a)
                                               :tokens (or (:tokens a) 1500)))
                     :budgeted? true)
      "query_slice" (text! (told! session name a
                                        (query/query-slice session (sym :ns) (sym :name)
                                                        :depth (or (:depth a) 2)
                                                        :limit (or (:limit a) 8)
                                                        :match (:match a)
                                                        :window (:window a)
                                                        :verbose (:verbose a)))
                                 :budgeted? true)
      "query_depends" (text! (told! session name a
                                        (let [r (graph/query-depends session (:on a)
                                                                     :modules (:modules a)
                                                                     :detail (:detail a)
                                                                     :direction (if (= "dependencies" (:direction a))
                                                                                  :dependencies :dependents))
                                              ;; the journal's own answer to "what usually
                                              ;; breaks when this changes" — a var's blast
                                              ;; radius is callers AND the tests that went
                                              ;; red beside it. Absent when there is no
                                              ;; evidence: an empty list would read as safe.
                                              reds (when (= :var (:kind r))
                                                     (ops/red-after session (:on a)))]
                                          (cond-> r reds (assoc :red-after reds)))))
      "session_brief" ;; git alignment is a QUESTION (query_git), not orientation: it rode on
                       ;; every brief as ~500 chars an agent never acted on
                       (text! (told! session name a (ops/session-brief session))
                              :budgeted? true)
      "review_scan" (text! (told! session name a
                                            (review/review-scan session
                                                             :ns (:ns a)
                                                             :limit (or (:limit a) 25))))
      "report" (text! (let [r    (ops/report session
                                                       :since (:since a)
                                                       :contains (:contains a)
                                                       :limit (or (:limit a) 50))
                                       conn (:db @session)
                                       al   (when (and conn (:dir @session))
                                              (sync/alignment
                                               (:dir @session) "."
                                               (str "slopp/" (:branch @session))
                                               (ops/query-commits session)))]
                                   (cond-> r al (assoc :alignment al))))
      "draft_test" (text! (ops/draft-test session (sym :ns) (sym :name)
                                                :code (:code a)
                                                :limit (or (:limit a) 5)))
      "turn_begin" (text! (ops/turn-begin! session :agent (:agent a)
                                                 :intent (:intent a)
                                                 :user (:user a)))
      "turn_end" (text! (ops/turn-end! session :agent (:agent a)
                                               :note (:note a)))
      "query_changes" (text! (history/query-changes (ops/with-history session)
                                                   :agent (:agent a)
                                                   :from (:from a) :to (:to a)
                                                   :format (:format a)))
      "episode_revert" (text! (-> (ops/revert-episode! session
                                                         :agent (:agent a)
                                                         :prompt (:prompt a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "query_history" (text! (told! session name a
                                        (let [nm      (:name a)
                                              ;; every history view below reads the
                                              ;; whole log — hydrated once, here
                                              session (ops/with-history session)]
                                          (cond
                                            (and nm (:at a))
                                            (assoc (history/query-form-at session (sym :ns) (sym :name)
                                                                     :at (:at a))
                                                   :kind :form-at)

                                            (and nm (:effort a))
                                            (assoc (history/query-form-history session (sym :ns) (sym :name)
                                                                             :effort true)
                                                   :kind :form-effort)

                                            nm
                                            {:kind :form-history
                                             :versions (history/query-form-history session (sym :ns) (sym :name)
                                                                              :format (:format a))}

                                            (:at a)
                                            (assoc (history/query-status-at session :at (:at a))
                                                   :kind :status-at)

                                            (:contains a)
                                            {:kind :prompts
                                             :hits (history/query-search-history session (:contains a)
                                                                            :limit (:limit a))}

                                            (:dead_ends a)
                                            {:kind :dead-ends
                                             :dead-ends (history/query-history
                                                         session :dead-ends (:dead_ends a))}

                                            :else
                                            (history/query-history session
                                                              :ns (some-> (:ns a) symbol)
                                                              :collapse (:collapse a)
                                                              :format (:format a)
                                                              :limit (or (:limit a) 20))))))
      "query_eval" (text! (ops/query-eval session (:code a)))
      "query_call" (text! (apply ops/query-call session
                                 (symbol (or (:sym a)
                                             (throw (ex-info "query_call needs :sym (a qualified var name)" {}))))
                                 (:args a)))
      "query_store" (text! (told! session name a
                                  (query/query-store session (:code a)
                                                   :timeout-ms (or (:timeout_ms a) 10000))))
      "query_observe" (text! (let [r (ops/query-observe session (sym :ns) (sym :name)
                                                          (:code a)
                                                          :limit (or (:limit a) 10))]
                                 (ops/remember-observation! session (sym :ns) (sym :name) r)
                                 r))
      "query_macroexpand" (text! (ops/query-macroexpand session (:code a)))
      "query_vocabulary" (text! (told! session name a (query/query-vocabulary session :ns (:ns a))))
      "query_rules" (text! (told! session name a (rules/query-rules session)))
      "query_capabilities" (text! (told! session name a (query/query-capabilities session)))
      "query_surface" (text! (told! session name a (query/query-surface session)))
      "query_rule_telemetry" (text! (told! session name a (query/query-rule-telemetry (ops/with-history session) :since (:since a))))
      "query_cost" (text! (told! session name a (query/query-turn-cost
                                               ;; the fold walks the whole log —
                                               ;; hydrated for this call only
                                               (ops/with-history session) :since (:since a)
                                               :otel (ops/otel-measurements session)
                                               ;; the per-call rows make :tools a
                                               ;; census with chars-out, not the
                                               ;; turn-end ring's top-five bound
                                               :tool-calls (ops/tool-call-measurements session))))
      "edit_replace_form" (text! (-> (ops/edit-replace! session (sym :ns) (sym :name)
                                                       (src :source) :prompt (:prompt a)
                                                       :agent (:agent a))
                                    (held-after-write! session name a)
                                    (finish-accepted! session a)
                                    (attach-red-context! session)
                                    (assoc :forms [(str (sym :ns) "/" (sym :name))])
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "edit_add_form" (text! (-> (ops/add-form! session (sym :ns) (src :source)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a))
                                    (held-after-write! session name a)
                                    (finish-accepted! session a)
                                    (attach-red-context! session)
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "edit_delete_form" (text! (-> (ops/delete-form! session (sym :ns) (sym :name)
                                                      :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "edit_rename" (let [old (:from a)
                                new (:to a)]
                            (when-not (and old new)
                              (throw (ex-info "edit_rename needs :from and :to" {})))
                            (text! (-> (ops/rename! session (sym :ns) (symbol old)
                                                   (symbol new) :prompt (:prompt a)
                                                   :agent (:agent a))
                                      (select-keys tools/wire-keys)
                                      (summarize (:verbose a)))))
      "ns_remove_require" (text! (-> (ops/remove-require! session (sym :ns) (sym :lib)
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "edit_group" (text! (-> (ops/edit-group! session (wire-steps (:steps a))
                                               :prompt (:prompt a) :agent (:agent a))
                              (held-after-write! session name a)
                              (finish-accepted! session a)
                              (attach-red-context! session)
                              (select-keys tools/wire-keys)
                              (summarize (:verbose a))))
      "full_check" (text! (-> (external/full-check! session :affected (:affected a)
                                                    :force (:force a))
                              ;; graded on rows (inside), reported as counts:
                              ;; an info-only rule's rows never change and
                              ;; never flip anything — slopp-ui read ~40 of
                              ;; them once and paid for them fifteen times
                              (update-in [:rules :findings]
                                         rules/fold-standing-info
                                         :verbose? (:verbose a))
                              ;; same rule for the edges the pipeline declared
                              ;; on writes' behalf: the count is what a reader
                              ;; branches on; the edges ride on verbose
                              (update :modules #(if (or (:verbose a) (nil? %))
                                                  %
                                                  (dissoc % :edges)))
                              (terse-full-check :verbose? (:verbose a))))
      "edit_requalify" (text! (-> (ops/requalify-boundary-keys!
                                   session (sym :ns) (sym :name)
                                   :to-ns (:to_ns a)
                                   :prompt (:prompt a)
                                   :agent (:agent a)
                                   :dry-run (:dry_run a))
                                  (select-keys tools/wire-keys)
                                  (summarize (:verbose a))))
      "rename_sweep" (let [{:keys [from to]} a]
                            (when-not (and from to)
                              (throw (ex-info "rename_sweep needs :from and :to (plain words/segments)" {})))
                            (text! (-> (ops/rename-sweep! session from to
                                                         :prompt (:prompt a)
                                                         :agent (:agent a)
                                                         :dry-run (:dry_run a))
                                      (select-keys tools/wire-keys)
                                      (summarize (:verbose a)))))
      "edit_subform" (let [after  (:after a)
                                anchor (:match a)
                                ;; :after is a distinct INSERT anchor — combining
                                ;; it with :match/:from/:where composed src on
                                ;; :after's mere PRESENCE while the anchor
                                ;; preferred :match, splicing a DUPLICATE of the
                                ;; neighbor at the match site (review host-F1)
                                _ (when (and after (or anchor (:where a)))
                                    (throw (ex-info "edit_subform: :after is an INSERT anchor — do not combine it with :match/:where (that would duplicate the neighbor). Use one or the other." {})))
                                match (or anchor after)
                                src   (if after
                                        ;; anchor mode: INSERT behind a complete
                                        ;; neighbor — the let-binding splice
                                        ;; without shaping a half-open match
                                        (str after "\n" (:source a))
                                        (:source a))]
                            (when-not (and (or match (:where a)) src)
                              (throw (ex-info "edit_subform needs :match (exact subform source) OR :where {key value} (the unique map containing it) OR :after (a complete neighboring form to insert behind), plus :source" {})))
                            (text! (-> (ops/edit-subform! session (sym :ns)
                                            (sym :name)
                                                         match src
                                                         :text (:text a)
                                                         :wrap (:wrap a)
                                                         :where (:where a)
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                      (select-keys tools/wire-keys)
                                      (summarize (:verbose a)))))
      "edit_revert" (text! (-> (ops/revert-form! session (sym :ns) (sym :name)
                                                      :to (:to a) :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "edit_comment" (text! (ops/set-comment! session (sym :ns) (sym :name)
                                                   (:text a)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a)))
      "edit_extract" (let [subform (:match a)]
                       (if-not (or subform (:at a))
                         (text! {:error (str "edit_extract needs :match (the exact subform"
                                             " text) or — better for anything large — :at,"
                                             " an ANCHOR: the subform's first line, which"
                                             " need not parse on its own")})
                         (text! (-> (ops/extract! session (sym :ns) (sym :from)
                                                  (sym :name) subform
                                                  :at (:at a)
                                                  :prompt (:prompt a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))))
      "done" (let [r (external/done! session :label (:label a)
                                            :agent (:agent a))
                   ;; the app server catches up to the store at DONE grain. A
                   ;; red done still refreshes: done REPORTS rather than
                   ;; refuses and a red one STANDS, so "finished" and "green"
                   ;; are different questions, and looking at the app is part
                   ;; of finding out you were not finished. refresh-app! never
                   ;; throws.
                   ;;
                   ;; This used to be fire-and-forget, so a re-serve that
                   ;; failed said nothing HERE and appeared only in
                   ;; session_brief — a different call an agent has no reason
                   ;; to make. An author could finish a unit of work, get a
                   ;; clean report, and have just taken their own app server
                   ;; down. So the result is waited for and reported: the
                   ;; action and its announcement belong in one call.
                   ;;
                   ;; It costs only a store that HAS a managed server —
                   ;; refresh-app! returns nil immediately for anything slopp
                   ;; does not run, which is most stores, and that is the same
                   ;; test as "could this line ever be news". The deref bound
                   ;; is a backstop, not a budget — and on expiry it now says
                   ;; SO, rather than nil. nil was already the answer for "no
                   ;; managed server" and for "re-served cleanly", so an
                   ;; expiring wait joined two silences that are both fine and
                   ;; became unreadable: a consumer served twenty minutes of
                   ;; old code with every surface clean. The future still lands
                   ;; the truth in the session for session_brief, which is what
                   ;; the note points at.
                   app (deref (future (refresh-app! session)) 20000 ::refresh-timed-out)]
               (text! (terse-done (if-let [note (app-note-for app)]
                                   (assoc r :app-note note)
                                   r))))
      "commit_point" (text! (let [r (external/commit-point! session (:label a)
                                                       :agent (:agent a)
                                                       :force (:force a)
                                                       :target (:target a))
                                  ;; A MILESTONE IS A DONE POINT. `commit-point!`
                                  ;; runs the whole done pipeline, so the app
                                  ;; server catches up here exactly as it does on
                                  ;; the `done` tool — same call, same bound,
                                  ;; same note.
                                  ;;
                                  ;; It did not, and the cost was measured:
                                  ;; slopp-ui enabled `http` in a session that
                                  ;; had booted with it off, restarted, ran
                                  ;; full_check, then commit_point — the natural
                                  ;; order — and afterwards nothing was listening
                                  ;; at all. Connection refused, no process on
                                  ;; the port. Only a process restart brought it
                                  ;; up.
                                  ;;
                                  ;; The same shape `refresh-app!`'s own
                                  ;; docstring already records about gating one
                                  ;; verb and not the other: a feature that
                                  ;; arrives, or fails to, by which call site you
                                  ;; happened to use.
                                  app (deref (future (refresh-app! session)) 20000 ::refresh-timed-out)
                                  r   (if-let [note (app-note-for app)]
                                        (assoc r :app-note note)
                                        r)]
                                    ;; Q10: the mechanical series is the system's job —
                                    ;; a green milestone on a git-configured store
                                    ;; publishes itself; publish trouble rides along
                                    ;; without failing the milestone
                                    (if (and (:commit r) (not= :red (:status r))
                                             (:dir @session))
                                      (if-let [p (try (sync/publish-local!
                                                       (:dir @session)
                                                       (:branch @session))
                                                      (catch Exception e
                                                        {:error (ex-message e)}))]
                                        (assoc r :published
                                               (select-keys p [:pushed :branch :error :status
                                                               ;; the diagnosis, or a refusal here says
                                                               ;; REJECTED_NONFASTFORWARD and stops —
                                                               ;; which cost a full investigation once
                                                               :divergence]))
                                        r)
                                      r)))
      "test_run" (text!
                       (cond
                         (:external a)
                         (external/external-test-run! session
                                                 :ns (some-> (:ns a) symbol)
                                                 :affected (:affected a)
                                                 :parallel (some-> (:parallel a) str parse-long)
                                                 :only (some->> (:only a) (mapv symbol)))
                         ;; surgical spot-checks name a target; bare test_run is
                         ;; almost always the redundant "confirm everything" the
                         ;; done-point already does — make ALL explicit, and teach
                         (or (:ns a) (seq (:only a)))
                         (external/spot-run! session
                                             :ns (when (:ns a) (sym :ns))
                                             :only (some->> (:only a) (mapv symbol))
                                             :fresh (:fresh a))

                         (:all a)
                         (assoc (ops/test-run! session nil :fresh (:fresh a))
                                :note (str "done runs the affected tests for everything"
                                           " you touched — a whole-suite in-image run is"
                                           " rarely needed mid-episode; the merge gate is"
                                           " test_run {external true}"))

                         :else
                         {:guidance (str "name what to spot-check: test_run {ns \"x.y-test\"}"
                                         " or {only [\"x.y-test/some-t\"]}. You do NOT need to"
                                         " run tests before done — done runs the affected"
                                         " tests itself. Whole suite in-image: {all true};"
                                         " external merge gate: {external true}.")}))
      
      "ns_delete" (text! (ops/delete-ns! session (sym :ns)
                                              :prompt (:prompt a)
                                              :agent (:agent a)))
      "ns_rename" (text! (ops/ns-rename! session (:from a) (:to a)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
"module_extract" (text! (ops/module-extract!
                               session
                               (mapv symbol (:namespaces a))
                               (symbol (str (:to a)))
                               :dry-run (:dry_run a)
                               :prompt (:prompt a)
                               :agent (:agent a)))
      "cleanup" (text! (if (:all a)
                        (ops/cleanup-all! session
                                          :prompt (:prompt a)
                                          :agent (:agent a))
                        (ops/cleanup! session (sym :ns)
                                      :prompt (:prompt a)
                                      :agent (:agent a))))
      "undo" (text! (ops/undo! session
                               :deltas (:deltas a)
                               :to (:to a)
                               :prompt (:prompt a)
                               :agent (:agent a)))
      "edit_move_forms" (text! (-> (ops/move-forms! session (sym :ns)
                                                     (mapv symbol (:forms a))
                                                     (symbol (:to a))
                                                     :export (:export a)
                                                     :prompt (:prompt a)
                                                     :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
      "change_signature" (text! (-> (ops/change-signature! session (sym :ns)
                                                           (sym :name)
                                                           (src :source) (:calls a)
                                                           :prompt (:prompt a)
                                                           :agent (:agent a))
                                    (select-keys tools/wire-keys)
                                    (summarize (:verbose a))))
"ns_realias" (text! (-> (ops/realias! session (sym :ns)
                                             (sym :from) (sym :to)
                                             :prompt (:prompt a)
                                             :agent (:agent a))
                              (select-keys tools/wire-keys)
                              (summarize (:verbose a))))
      (throw (ex-info (str "unknown tool: " name ". Available: "
                           (str/join ", " (map :name tools/registry)))
                      {}))))))

(defn- call-tool!
  "The wire entry. A FAMILY name (`tools/families`) with `op` resolves to the
  op's registry name and dispatches through `call-op!`; a single-op family
  needs no op; an op called by its own name dispatches directly (the
  `--call` door, the hooks). An unknown or missing op is refused with the
  family's op list, so the refusal is the index."
  [session {:keys [name arguments] :as req}]
  (if-let [fam (some #(when (= name (:name %)) %) tools/families)]
    (let [ops (:ops fam)
          op  (some-> (:op arguments) str)]
      (cond
        (= 1 (count ops))
        (call-op! session (assoc req :name (first ops) :arguments (dissoc arguments :op)))

        (nil? op)
        (throw (ex-info (str name " needs :op — ops: " (str/join " " ops))
                        {:tool name :ops ops}))

        (not (some #{op} ops))
        (throw (ex-info (str "unknown op " op " for " name " — ops: " (str/join " " ops))
                        {:tool name :op op :ops ops}))

        :else
        (call-op! session (assoc req :name op :arguments (dissoc arguments :op)))))
    (call-op! session req)))

^:unsafe (defn handle!
  "Dispatch a JSON-RPC request map; return a response map, or nil for
  notifications. Tool exceptions become an `isError` result (so the agent sees
  the message); protocol errors become JSON-RPC errors."
  [session {:keys [id method params]}]
  (case method
    "initialize" {:jsonrpc "2.0" :id id
                  :result {:protocolVersion protocol-version
                           :capabilities {:tools {:listChanged true}}
                           :serverInfo {:name "slopp" :version "0.1.0"}}}
    "notifications/initialized" nil
    "tools/list" (let [advertised (if (or (:cli-mode? @session)
                                          (some? (System/getenv "SLOPP_CLI")))
                                      ;; the CLI door is the surface; the MCP
                                      ;; connection stays for hooks/lifecycle
                                      ;; but pays no schema rent
                                      []
                                      tools/tools)]
                   (swap! session assoc :slopp.mcp/tools-hash (hash advertised))
                   {:jsonrpc "2.0" :id id :result {:tools advertised}})
    "tools/call"
    ;; BOTH edges of the call, recorded here because this is the only layer
    ;; that sees them. The gap between one answer and the next call is time
    ;; slopp was NOT working — agent reasoning, non-slopp tools, the harness —
    ;; and it had no producer at all: measured over one real session, 78% of
    ;; the wall clock was invisible. turn_end folds the ring onto its delta.
    (let [t0    (System/currentTimeMillis)
          facts (atom {})
          r     (binding [;; A smell is once-per-session and rarer, so it speaks first. The
                          ;; thread reminder is DEFERRED rather than computed here: it
                          ;; counts what the call is about to make un-landed, which does
                          ;; not exist yet. `text!` forces it.
                          *hint* (or (smells/track-hint! session
                                                         (:name params)
                                                         (:arguments params))
                                     (delay (thread-hint! session (:name params))))
                          *spool-session* session
                          ;; what the ANSWER did — the trim, the withheld stub,
                          ;; the id a retrieval went back for. Only the frames
                          ;; that shape a response know those, and none of them
                          ;; is on the path back to here.
                          *response-facts* facts]
                  (try (call-tool! session params)
                       (catch Exception e
                         (assoc (text! (str "error: " (ex-message e)))
                                :isError true))))
          why   (refusal-text r)]
      ;; after the call, so a tool that reads the ring (turn_end) never sees
      ;; its own half-finished entry
      (let [entry (merge
                   {:tool (:name params) :start t0 :end (System/currentTimeMillis)
                    ;; A REFUSAL and the reason it gave, from ONE derivation — see
                    ;; `refusal-text` for the two shapes it arrives in and for the
                    ;; deliberate under-count. The message rides along because a
                    ;; count with no cause can only ever support "read that tool's
                    ;; contract", which is the guess rather than the finding;
                    ;; `call-timing` bounds and truncates what reaches the delta.
                    :refused? (some? why)
                    :error    why
                    ;; WHAT IT SENT, taken off the response itself rather than from
                    ;; whoever built it — characters on the wire, which is the unit
                    ;; the size gate and the payload fitter are both written in.
                    ;; The ring had a call's edges and its refusal, so it could say
                    ;; what slopp SPENT and never what it COST, and reads are 52%
                    ;; of the bill.
                    :chars    (count (get-in r [:content 0 :text] ""))}
                   @facts)]
        ;; TWO rings, one entry — shared structurally, so the second costs a
        ;; pointer. They are separate because their LIFECYCLES are: the timing
        ;; ring is cleared at every `turn-begin!` so an ask measures only its
        ;; own clock, and the read rows must not inherit that. Turns rotate
        ;; only when a user prompt arrived and a write followed, so a
        ;; read-only ask and an event-driven session close none — which are
        ;; the spans where reads dominate. `ops/flush-reads!` empties the
        ;; second on its own schedule.
        (swap! session #(-> %
                            (update :slopp.read.telemetry/calls (fnil conj []) entry)
                            (update :slopp.read.telemetry/reads (fnil conj []) entry)))
        ;; and ONE ROW beside the journal, the census the rings cannot be:
        ;; the timing ring keeps a turn's top five and the read ring flushes
        ;; on its own schedule, so neither can answer "every call, and what
        ;; each sent". A measurement, never a delta — a read must not be able
        ;; to move the head a verdict is racing for.
        (ops/record-tool-call! session (assoc entry :agent (:agent params))))
      {:jsonrpc "2.0" :id id :result r})
    "ping" {:jsonrpc "2.0" :id id :result {}}
    (when id
      {:jsonrpc "2.0" :id id
       :error {:code -32601 :message (str "method not found: " method)}})))

(defn serve!
  "Newline-delimited-JSON stdio loop over `in-reader`/`out-writer`."
  [session in-reader out-writer]
  (doseq [line (line-seq in-reader) :when (not (str/blank? line))]
    (when-let [resp (handle! session (json/parse-string line true))]
      (.write out-writer (str (json/generate-string resp) "\n"))
      (.flush out-writer))
    ;; a live reload may have changed the tool registry — tell the client
    ;; to re-list (ordered: same writer, right after the response)
    (when-let [note (tools-note! session)]
      (.write out-writer (str (json/generate-string note) "\n"))
      (.flush out-writer)))
  nil)

(defn call!
  "One-shot tool invocation against the store at `dir` — the --call CLI's
  engine and the fallback when no MCP connection exists. Opens a durable
  session, dispatches ONE tool call, closes. Returns the wire result map
  ({:content [{:text …}]}; :isError true on tool errors), same as the
  server would send.

  Writes stay TURN-GATED here, deliberately: provenance is not optional just
  because the caller is a script. Turns are DURABLE across one-shot processes,
  so the scripted shape is `--call turn_begin` once, then the writes, then
  `--call turn_end` — not a turn per call. Reads need nothing.

  An unexpected throw reports its CAUSE CHAIN. It does NOT report stack frames:
  everything here flows through `text!`, whose boundary-leak guard refuses a
  file:line coordinate, so emitting frames replaced the real diagnostic with a
  guard exception."
  [dir tool arguments]
  (let [session (external/open!
                 (cond-> {:slopp.ops/dir (str dir)}
                   ;; a one-shot names its agent in the call, and that name is
                   ;; the SESSION's identity, not merely the delta's. Turns are
                   ;; durable across processes and so is the LINE one was opened
                   ;; on — a fresh identity per process would open a fresh
                   ;; thread per call, and the turn would be unfindable by the
                   ;; very write it was opened for.
                   (:agent arguments)
                   (assoc :slopp.ops/agent-id (str (:agent arguments)))))]
    (swap! session assoc :require-turns? true)
    (try
      (let [r (try (call-tool! session {:name tool :arguments arguments})
                   (catch Exception e
                     (let [chain (take 4 (iterate #(some-> ^Throwable % .getCause) e))
                           msgs  (into [] (comp (take-while some?)
                                                (map #(str (.getSimpleName (class %))
                                                           ": " (ex-message %))))
                                       chain)]
                       (assoc (text! (str "error: " (str/join " <- " msgs)))
                              :isError true))))]
        ;; ...and say what this process could not see. See
        ;; [[foreign-unlanded-note]]: a one-shot reads the BRANCH, and while
        ;; somebody's thread holds un-landed work that is a different store from
        ;; the one an MCP session answers from.
        (if-let [note (when-not (:isError r) (foreign-unlanded-note session))]
          (update r :content (fnil conj []) {:type "text" :text note})
          r))
      (finally (ops/close! session)))))

^:unsafe
(defn ^{:entry-point "resolved by NAME from the command line — boot's --call sugar and --main slopp.mcp/call-main!, so no reference inside the store reaches it"} call-main!
  "CLI entry for boot's --call sugar (or --main slopp.mcp/call-main!):
  <dir> <tool> [args] — one tool call, result text on stdout, exit 1 on a
  tool error. args is JSON, EDN, or @file (parse-call-args)."
  [& [dir tool args-str]]
  (when (str/blank? tool)
    (binding [*out* *err*]
      (println "usage: --call <tool> [<json/edn args or @file>]"))
    (System/exit 2))
  (let [r (call! (or dir ".") tool (parse-call-args args-str))]
    (println (clojure.string/join "\n" (map :text (:content r))))
    (flush)
    (System/exit (if (:isError r) 1 0))))

(defn- http-call!
  "`POST /api/call` — the CLI door onto the RUNNING server. Body:
  `{\"tool\" \"<op>\" \"arguments\" {…} \"token\" \"<from .slopp/ui-port>\"}`.
  Invokes [[call-op!]] on the live session — the same dispatch, turn gating,
  ledger and anticipation MCP calls get — and answers
  `{\"isError\" bool \"text\" \"…\"}` with the joined content text, the shape
  `--call` already prints. A thrown refusal crosses as isError text, never a
  stack trace: the caller is a terminal.

  The token is a per-boot secret written into `.slopp/ui-port` beside the
  address (the file is already the trust anchor the prompt hook reads).
  Loopback binding alone must not grant every local process write access to
  the store — 403 without it, and nothing runs. Why this exists (s12c,
  measured): the one-shot `--call` path boots a JVM and loads the whole
  store in silence; an agent reached for it unprompted, waited on the cold
  path for minutes, and spent eight turns babysitting the process — while
  this process held the warm image the whole time.

  FOR ANYONE PROXYING A SLOPP LISTENER: this write door shares the listener
  with the read endpoints — it differs by path and method, not by port. A
  reverse proxy MUST NOT forward POST /api/call unless it means to hand the
  store's editing surface to everything that can reach the proxy
  (slopp-ui's hub verified its GET-only stance at the wire, 2026-09-01)."
  [req]
  (let [session (:session (:http/deps req))
        body    (:body req)
        b       (cond
                  (map? body)    body
                  (nil? body)    {}
                  (string? body) (try (json/parse-string body true)
                                      (catch Exception _ {}))
                  :else          (try (json/parse-string (slurp body) true)
                                      (catch Exception _ {})))
        raw     (fn [status m]
                  {:status status :http/raw true
                   :headers {"Content-Type" "application/json"}
                   :body (json/generate-string m)})
        want    (some-> session deref :call-token)]
    (cond
      (nil? session)
      (raw 503 {:error "no live session behind this listener"})

      (or (nil? want) (not= (str (:token b)) (str want)))
      (raw 403 {:error "bad or missing token — read it from .slopp/ui-port"})

      (not (string? (:tool b)))
      (raw 400 {:error "call needs {tool arguments} — tool is the op name"})

      :else
      (let [args (cond-> (or (:arguments b) {})
                   (:agent b) (assoc :agent (:agent b)))
            r    (try (call-op! session {:name (:tool b) :arguments args})
                      (catch Exception e
                        {:isError true
                         :content [{:type "text"
                                    :text (or (ex-message e) (str e))}]}))]
        (raw 200 {:isError (boolean (:isError r))
                  :text (apply str (map :text (:content r)))})))))

^:unsafe (defn start-ui!
  "Bring this project's UI listener up beside the MCP server and start its
  heartbeat to the hub. Returns `ui/serve!`'s map — `{:url :port}`, or
  `{:error …}` — and NEVER throws.

  The listener still serves the LIVE session and still dies with the server.
  `:test-map` and `:observed` are persisted and reloaded, so a fresh session is
  not blank — it is STALE, showing the warranty as of the last verified run
  rather than the one being changed, and it would boot a second image to show
  it. That accuracy is what forces the whole hub design (D-hub): a hub
  cannot answer for a store, so every project answers for itself and the hub
  proxies.

  What changed is the ADDRESS. The port is derived from the store dir instead
  of defaulting to a fixed 7359, so projects on one machine never collide, and
  a taken port falls back to an ephemeral one — the registered url carries
  whatever was actually bound. Nobody needs to know this number; the address a
  human remembers is the hub's.

  The stance every optional listener here takes: the UI is OPTIONAL and MCP
  is not. A busy port, a missing hub, anything at all — it
  reports a sentence on stderr (stdout is the JSON-RPC channel) and the server
  carries on. Nothing about a browser page should be able to stop the thing the
  editor is talking to."
  ([session] (start-ui! session nil))
  ([session explicit-port]
   (let [dir  (:dir @session)
         ;; the CLI door's per-boot secret: written into ui-port below,
         ;; required by /api/call — loopback alone is not authorization
         token (str (java.util.UUID/randomUUID))
         want (server/preferred-port dir explicit-port)
         try! (fn [p] (try (server/serve! session p
                                          :routes [{:method :post :path "/api/call"
                                                    :auth :public :handler #'http-call!}])
                           (catch Throwable t {:error (or (.getMessage t) (str t))})))
         r0   (try! want)
         ;; a derived port is a PREFERENCE: something else already holding it
         ;; must not cost this project its UI, so fall back to whatever is free.
         r    (if (and (:error r0) (not (zero? (long want)))) (try! 0) r0)]
     ;; ON THE SESSION, so a reader can find it. The stderr
     ;; banner below goes to the MCP server's log, which most clients never
     ;; show a human — so autostart without this is a feature nobody can find.
     ;; session_brief surfaces it, which is where an agent looks and how the
     ;; human gets told.
     (when (:url r) (swap! session assoc :ui-url (:url r) :call-token token))
     ;; …and on DISK, for a process that is not this one: the prompt hook
     ;; fetches the ask bundle over HTTP and has ~2 s, so it reads the port
     ;; from a file instead of asking the hub. pid + started let it tell a
     ;; live listener from a dead session's leftover. Best-effort, silent —
     ;; nothing about the optional UI may cost the MCP loop anything.
     (when (:url r)
       (try (let [ph (java.lang.ProcessHandle/current)]
              (spit (str (:dir @session) "/.slopp/ui-port")
                    (format "{\"port\":%d,\"url\":\"%s\",\"pid\":%d,\"started\":%d,\"token\":\"%s\"}"
                            (long (:port r)) (:url r) (.pid ph)
                            (System/currentTimeMillis) token)))
            (catch Throwable _ nil)))
     (.println System/err
               ^String (if (:url r)
                         (str "slopp UI: " (:url r))
                         (str "slopp UI unavailable: " (:error r))))
     (when (:url r) (start-heartbeat! session dir (:url r)))
     r)))

^:unsafe
(defn -main
  "Start the stdio MCP server. An optional `dir` argument makes the session
  durable (store at <dir>/.slopp/store.db); without it the session is
  ephemeral. Serving a git checkout that carries a slopp BRANCH with an
  absent/empty store AUTO-IMPORTS it first (zero-ceremony onboarding).

  Serving a dir that is NOT slopp-managed writes NOTHING there: the server
  is launched in whatever directory the editor has open, so adoption has to
  be something you do, not something that happens to you. The store is
  created by the first real write (`slopp.ops.engine/ensure-db!`).

  Git is push/pull to a remote slopp does not own: `git_push` publishes the
  projection, `git_clone` rebuilds a fileless store from one (slopp.sync).
  Serving the store to a git client AS a remote was removed — it forced
  exact-project handling for less than it cost."
  [& [dir]]
  (when dir
    (when-let [r (sync/maybe-auto-import! dir)]
      (binding [*out* *err*]
        (println (str "slopp: auto-imported " (:namespaces r)
                      " namespaces from the repo's slopp branch")))))
  (let [session (external/open! (cond-> (merge {;; boot the image on a background thread
                                      ;; so the MCP handshake completes as soon
                                      ;; as the store loads — a slow/contended
                                      ;; boot no longer races the connect timeout
                                      :slopp.ops/async-image? true
                                      ;; WHO is driving this server. Read here
                                      ;; and nowhere deeper: a child JVM
                                      ;; inherits the variable, so a session
                                      ;; opened inside an image or a test
                                      ;; runner would otherwise claim this
                                      ;; conversation's thread. This process is
                                      ;; the only one a harness actually
                                      ;; spawned. nil when no known harness set
                                      ;; one, and open! generates an id as before.
                                      :slopp.ops/agent-id
                                      (harness/conversation-id
                                       #(System/getenv %))}
                                     ;; how many IDLE image JVMs this server
                                     ;; holds. The warm spare was hardcoded on
                                     ;; here: the right default for a session
                                     ;; alone on a box, the wrong one for eight
                                     ;; of them, because it buys latency with a
                                     ;; whole idle JVM per server. LAST, so the
                                     ;; host's answer wins over the defaults.
                                     (host-image-options #(System/getenv %)))
                             dir (assoc :slopp.ops/dir dir)))]
    (swap! session assoc :require-turns? true)   ; real servers enforce turns
    ;; the reviewer UI comes up with the server, always. It serves the LIVE
    ;; session and therefore dies with it — that is the trade that keeps its
    ;; warranty numbers honest — so nothing ever brought it back, and a human
    ;; who wanted it had to know to ask again after every restart.
    ;; start-ui! never throws: MCP must serve even when the UI cannot.
    (start-ui! session)
    ;; the app server comes up beside the UI, for a store that asked for it.
    ;; BACKGROUNDED: it boots a whole second JVM and loads the app's web
    ;; surface into it, and nothing about that should sit between the editor
    ;; and a completed MCP handshake — the same reason the oracle's own boot
    ;; is async here.
    (future (start-app! session))
    (try
      (serve! session (io/reader System/in) (io/writer System/out))
      (finally
        ;; SAY IT FIRST. The teardown below is deliberate and correct — a UI
        ;; belongs to the MCP session and must not outlive it — but boot
        ;; printed `slopp UI: <url>` a moment ago and that url is about to stop
        ;; working. Saying nothing is what made this expensive: a manual launch
        ;; (stdin from /dev/null, a finished pipe, any non-tty) reaches EOF
        ;; immediately, so the last thing a launcher reads is a url that is
        ;; already dead, with a live process behind it. Three agents spent an
        ;; evening diagnosing that, twice concluding the code was broken and
        ;; once that a jar had not shipped.
        ;;
        ;; stderr, because stdout is the JSON-RPC channel.
        (.println System/err
                  ^String (str "slopp: no MCP client on stdin — withdrawing the"
                               " UI listener and exiting. The UI belongs to the"
                               " MCP session and does not outlive it, so a url"
                               " printed above has stopped working. To keep one"
                               " alive, run slopp from an editor (which holds"
                               " stdin open) rather than launching it manually."))
        ;; deregister BEFORE the listener goes: the hub should learn we are
        ;; leaving from us, not by ageing us out thirty seconds later.
        (hub/stop! (:hub-heartbeat @session))
        (server/stop!)
        ;; the app image is a CHILD JVM. Its watchdog would reap it when we
        ;; die anyway, but leaving that to a watchdog means the port stays
        ;; bound for as long as the reap takes — and the next server to start
        ;; here wants exactly that port.
        (live/stop! (:app-server @session))
        (ops/close! session)
        ;; and GO. `(future (start-app! session))` above runs on Clojure's
        ;; send-off pool, whose workers are NON-DAEMON with a 60-second
        ;; keepalive — so `main` returns, `DestroyJavaVM` starts, and an IDLE
        ;; pool thread holds the JVM open for a further minute with every
        ;; listener already torn down. Caught by thread dump: DestroyJavaVM
        ;; RUNNABLE, held by `clojure-agent-send-off-pool-0` parked in
        ;; SynchronousQueue.poll — same stack and same cpu time twelve seconds
        ;; apart, waiting for a task that was never coming.
        ;;
        ;; A minute of a process that has announced a url, withdrawn it, and
        ;; still answers `ps` is exactly the state nobody could interpret.
        (shutdown-agents)))))
