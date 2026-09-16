(ns slopp-server.mcp
  "The MCP surface (JSON-RPC 2.0) exposing `slopp.ops` as tools, transport
  apart. The pure `handle!` dispatch is the core (fully testable with plain
  maps); the daemon (`slopp-server.process`, through `slopp.mcp.http`) is the one
  transport, MCP over HTTP with a stdio pipe in front of it on the client's
  side. There is no stdio loop here any more: a JVM per session was what
  stdio imposed, not what was chosen, and one slopp per machine replaced it.

  Tool names use underscores (MCP restricts names to [A-Za-z0-9_-]). This is the
  agent-facing surface — everything is form-addressed (ns/name), never file+line."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [slopp.ops :as ops]
            [slopp.store.db :as db] [slopp.sync :as sync] [clojure.edn :as edn] [slopp-server.mcp.tools :as tools] [slopp-server.mcp.smells :as smells] [slopp.ops.branch :as branch] [slopp.read.query :as query] [slopp.ops.review :as review] [slopp.ops.external :as external] [slopp.webdev.cljs :as cljs] [slopp.rules :as rules] [slopp-server.api.server :as server] [slopp.rules.doctor :as doctor] [slopp.webdev.live :as live] [slopp.read.history :as history] [slopp.read.graph :as graph] [slopp.webdev.screen :as webdev.screen] [slopp.ops.engine :as engine] [slopp.read.orient :as orient] [slopp.store :as store] [rewrite-clj.node :as n] [slopp.edit :as edit] [slopp.read.anticipate :as anticipate]))

^{:auto-declare "mutual recursion: call-op!, call-op-1!, call-tool!, handle!, http-call!"}
(declare call-op! call-op-1! call-tool! handle! http-call!)

(def ^:private protocol-version "2024-11-05")

(def ^:private ^:dynamic *hint*
  "Optional one-line workflow hint, attached to map results (item 3)." nil)

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
           (let [n (count kept)]
             (cond-> {:body (pr-str (if (= n total)
                                      (vec kept)
                                      (conj (vec kept) (mark n))))
                      :note (str n " of " total " shown")}
               (< n total)
               ;; the retrieval half of the diet: query_detail re-buying the
               ;; whole payload averaged 18.8k chars (s14 audit) when the
               ;; reader already held the head — the spool gets ONLY this
               (assoc :dropped
                      (str ";; the REMAINDER — items " (inc n) "–" total
                           " (the first " n " rode the trimmed response)\n"
                           (pr-str (vec (drop n x)))))))))

       (and (map? x) (seq x))
       (let [kept (fit (sort-by #(count (pr-str %)) (seq x)) "{" "}" budget)]
         (when (seq kept)
           (cond-> {:body (pr-str (into {} kept))
                    :note (str (count kept) " of " (count x) " keys shown")
                    ;; the map itself and the count it lost, for a caller that
                    ;; wants to say what was cut IN-BAND rather than in prose
                    ;; (a green result: the fact without the invitation)
                    :kept-map (into {} kept)
                    ;; NAMED, not counted: a count of missing keys is a question
                    ;; the reader has to re-fetch to answer; the key's name is
                    ;; a fact it can dismiss (eval24 opus: \"withheld part of
                    ;; its result; let me see the whole thing\")
                    :withheld (let [gone (vec (remove (set (map first kept)) (keys x)))]
                                ;; … up to a handful; hundreds of names would
                                ;; outgrow the gate they were cut for
                                (if (<= (count gone) 8)
                                  gone
                                  {:keys (count gone) :of (count x)}))}
             (< (count kept) (count x))
             (assoc :dropped
                    (str ";; the REMAINDER — the keys the trimmed response lacked\n"
                         (pr-str (apply dissoc x (map first kept))))))))

       :else nil))))

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
  missing part could change what the agent does next.

  `:ceiling` is the gate for THIS result, default 8000. An EXPLICIT read —
  one whose caller named its targets — passes a higher one: measured on a
  real session (s20), 69% of `query_source` trims were re-bought through
  `query_detail`, and every re-buy is a whole model request at p50 485k
  context, worth ~60 trimmed payloads. The gate was optimizing chars while
  provoking round trips. Unbounded ops (search, reports, explore bundles)
  keep the default. A green MAP over the gate is trimmed SILENTLY — the
  \"N of M keys shown\" note on a 227-char green full_check provoked verbose
  re-runs in every s18 handoff sample — and what it keeps is the most keys
  that fit ([[fit-payload]]), never hash order."
  [x & {:keys [budgeted? ceiling] :or {ceiling 8000}}]
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
        ;; the fit budget reserves room for the trailing note
        budget  (- ceiling 200)
        out     (cond
                  budgeted? full

                  (and (= full slimmed) (<= (count full) ceiling)) full

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
                          fit (when (> (count slimmed) ceiling)
                                (fit-payload (trim-failure-strings x) budget id))]
                      (cond
                        ;; slimming alone got it under the gate — send it whole;
                        ;; the spool keeps the FULL copy (what was withheld is
                        ;; failure-string tails a remainder cannot reconstruct)
                        (<= (count slimmed) ceiling)
                        (str slimmed (trimmed id))

                        ;; drop whole ITEMS: the body stays parseable, and the
                        ;; spool keeps only the REMAINDER — a retrieval was
                        ;; measured re-buying the half already in hand (18.8k
                        ;; chars average, s14 audit). A GREEN map goes without
                        ;; the note: the note was the invitation (s18).
                        fit
                        (do (when (:dropped fit)
                              (swap! sess assoc-in [::spool :entries id] (:dropped fit)))
                            (if (and green? (map? x) (:kept-map fit))
                              ;; the FACT in-band, as data, and no invitation:
                              ;; what was cut is still named, and nothing here
                              ;; is a command to run
                              (pr-str (assoc (:kept-map fit) :withheld (:withheld fit)))
                              (str (:body fit) "\n[" (:note fit) (invite id) "]")))

                        ;; nothing to drop (a single huge string/scalar): the
                        ;; spool continues from where the shown text stopped
                        :else
                        (do (swap! sess assoc-in [::spool :entries id]
                                   (str ";; the REMAINDER — continues from char " ceiling
                                        " of the shown response\n"
                                        (subs slimmed ceiling)))
                            (str (subs slimmed 0 ceiling) (trimmed id)))))
                    (if (<= (count slimmed) ceiling) slimmed full)))]
    {:content [{:type "text" :text out}]}))

(defn- red? [t]
  (and t (pos? (+ (:fail t 0) (:error t 0)))))

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
                          :note (str "commit-points (commit_point) are the commits; "
                                     "git_push publishes the projection to the "
                                     "remote and git_pull absorbs from it")}
                         {:error (str "no git remote configured — git_push {url}"
                                      " sets one, or git_clone rebuilds a store"
                                      " from one")}))))
   "query_commits"
   (fn [session a _sym]
     (text! (if (:commit a)
              ;; the drill-down rung: ONE commit-point, full description
              (or (ops/query-commits session :commit (:commit a))
                  {:error (str "no commit-point " (:commit a))})
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

  **The two-arity judges the OUTCOME, not the report.** `behind` is
  `app-behind` measured AFTER the refresh: a refresh that said it succeeded
  and left the served image behind the store is the fourth outcome, and it
  fooled every surface for 23 hours — the report was true, the stamp it
  reported from was not retaken. nil means unmeasured and makes no claim;
  0 is current and stays silent. The one-arity reads `::behind` off the map,
  which [[refresh-app!]] puts there, so neither door that calls this moved.

  Reported by the first consumer to lose an app server this way: `done`
  returned a clean report, the failure appeared only in `session_brief`, and
  an agent has no reason to make that second call. Action and announcement
  belonged in the same one."
  ([refreshed]
   (app-note-for refreshed (when (map? refreshed) (::behind refreshed))))
  ([refreshed behind]
   (cond
     ;; THE BOUND EXPIRED. This used to report nil — \"say nothing rather than
     ;; guess\" — which put a re-serve that had not happened into the same
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
          (:url refreshed) " and " (get-in refreshed [:plan :serves-nothing]))

     ;; SUCCEEDED AND STILL BEHIND — the report and the outcome disagree. The
     ;; only way to see it is to measure after acting, which is what `behind`
     ;; is. A positive count here is not \"wait for the refresh\": the refresh
     ;; has returned.
     (and (map? refreshed)
          (:serving? refreshed)
          (number? behind)
          (pos? behind))
     (str "the app server slopp runs for this project re-served without error"
          " and is STILL " behind " code change(s) BEHIND the store — the refresh"
          " reported success and the outcome disagrees. The browser at "
          (:url refreshed) " is showing an older store than this done describes;"
          " a re-boot (a done after a namespace is added or removed, or a"
          " restart) replaces the image outright."))))

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

(defn- terse-done
  "A green done is ONE LINE: the id, the verdict, where it landed — plus only
  what needs the agent (an external tier that ran, a deferral count, a host
  that drifted or failed a reload, a standing advisory, the app note).
  eval10 measured `done` at ~2k chars a call, six calls a session,
  byte-identical prose about episode scope and oracle currency riding every
  one; a red done keeps the full report, because there the findings are the
  answer."
  [r]
  (let [f (:findings r)]
    (if-not (and (= :green (:episode-status f))
                 (= :green (:test-status f))
                 (zero? (:lint-errors f 0))
                 (empty? (:unloadable-namespaces f))
                 ;; a land that REFUSED (the branch moved and the rebase
                 ;; conflicts, a lost thread) is the one fact the one-liner
                 ;; cannot carry: the work is still UNLANDED, so the refusal
                 ;; and its recovery path come back in full, exactly as a red
                 ;; done keeps its findings. No :land at all is a done with
                 ;; nothing to land — that stays terse; absence and refusal
                 ;; are different facts.
                 (let [land (:land r)]
                   (or (nil? land) (boolean (:landed land)))))
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
          ;; the number a handoff quotes, on the answer it reads first
          ;; (eval24 opus: without it, test_run {all} after every green done)
          (:test r)                      (assoc :suite (let [s (:test r)]
                                                         {:tests (:test s) :pass (:pass s)
                                                          :fail (:fail s) :error (:error s)}))
          (:external r)                  (assoc :external (select-keys (:external r) [:ran :status :failures]))
          (:external-pending f)          (assoc :external-pending (:count (:external-pending f)))
          ;; the host warning rides the one-liner WHENEVER there is one: a
          ;; reload the live host could not apply used to be dropped here
          ;; unless the oracle had drifted too, which is how a host serving
          ;; old definitions stayed invisible on every green done
          (:host-stale f)
          (assoc :host-stale (select-keys (:host-stale f) [:oracle-drift :failed :note]))
          (not info-only?)               (assoc :http-dangling-route-refs (:http-dangling-route-refs f))
          (seq advisory)                 (assoc :advisories advisory)
          (:app-note r)                  (assoc :app-note (:app-note r)))))))

(defn plugin-root
  "Where the plugin's files are, or nil: Claude Code sets CLAUDE_PLUGIN_ROOT for
  every process the plugin starts, and the daemon a pipe started is one. The
  skill and its reference topics ship there — a different channel from the
  jar this code runs in — so the one thing the server can do about them is
  READ them, and this is the seam a test redirects."
  []
  (not-empty (System/getenv "CLAUDE_PLUGIN_ROOT")))

(defn help-text
  "`help {topic}`: an OP's full card from the registry in this process, or
  the plugin's `skills/slopp/reference/<topic>.md`, whole, or with no topic
  the index of topics on disk. One source of truth: the same file the agent
  could Read, served through the tool so a session that has only the
  one-page skill in context reaches the rest without leaving the loop. The
  skill is a page because the 2,900-line version cost ~70k tokens in every
  session that loaded it — 65% of all context the eval10 lifetime cells ever
  created — and an agent reads a REST or web chapter once per project, not
  once per turn. An op's card never needed the plugin's files, so it is
  answered before the plugin root is asked for: a daemon started from a
  shell has none, and the card is the help most calls want."
  [topic]
  (let [dir    (some-> (plugin-root) (io/file "skills" "slopp" "reference"))
        topics (when (and dir (.isDirectory dir))
                 (->> (.listFiles dir)
                      (filter #(str/ends-with? (.getName %) ".md"))
                      (map #(subs (.getName %) 0 (- (count (.getName %)) 3)))
                      sort vec))
        index  (str "help topics: " (str/join ", " topics)
                    " — help {topic} returns one whole; each is also"
                    " skills/slopp/reference/<topic>.md in the plugin.")
        card   (some #(when (= (str topic) (:name %)) %) tools/registry)]
    (cond
      ;; an OP's full card — the family index carries one line per op, and
      ;; this is where the rest of its description and its schema live
      card
      (pr-str (select-keys card [:name :description :inputSchema]))

      (nil? dir)
      (str tools/cheat-sheet "\n\n(no plugin root in this process — the reference"
           " topics ship in the plugin under skills/slopp/reference/)")

      (str/blank? (str topic))
      (str tools/cheat-sheet "\n\n" index)

      (some #{(str topic)} topics)
      (slurp (io/file dir (str topic ".md")))

      :else
      (str "no topic named " topic ". " index))))

(defn wire-steps
  "`edit_group`'s step maps as `ops/edit-group!` takes them: keys keywordized
  whether the transport left them strings or keywords, `action` a keyword,
  `ns`/`name` symbols, a `where` map's keys keywordized the way a single
  `edit_subform` sees them, and a step's `:code` taken as its `:source`
  (`code` is check's argument; models generalize it — 26 refusals in the s17
  census, every one retried). Everything else rides through untouched.

  A `:patch` step — {action: patch, ns, name, replace: [{match, source,
  text?, where?} …]} — EXPANDS here into one :subform step per entry:
  several small changes inside one form cost the model one compact step
  instead of a whole-form retype (eval11: retypes were 2.1x plain's output
  volume, and output is the slowest, priciest token). The server does the
  mechanical work; the ops layer never sees :patch."
  [steps]
  (let [kw  (fn [m] (into {} (map (fn [[k v]] [(keyword (name k)) v])) m))
        src (fn [s] (if (and (:code s) (nil? (:source s)))
                      (dissoc (assoc s :source (:code s)) :code)
                      s))
        one (fn [s]
              (cond-> s
                (:action s) (update :action #(keyword (name %)))
                (:ns s)     (update :ns symbol)
                (:name s)   (update :name symbol)
                (map? (:where s)) (update :where kw)))]
    (into []
          (mapcat (fn [s]
                    (let [s (src (kw s))]
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
  broken), the in-image COUNTS and the external tier's count and status,
  and every fact a reader branches on — standing findings (folded), the
  auto-declared edge count, alias drift as a count, an artifact behind the
  store, a standing verdict. The scaffolding goes: notes, per-namespace
  timings, the sweep plan, the test run's timing. Red keeps the full map,
  and so does `verbose`.

  eval10 s2: two green checks of ~19k chars each were trimmed at the gate
  and re-fetched whole through query_detail — 76k chars for a verdict.
  eval24 opus: the counts had been dropped as scaffolding, and a verdict
  without a number to quote cost `test_run` twice after every green;
  `:crossings`, a section with a paragraph per exit kind, alone outgrew
  the gate and was CUT, which read as \"part of the result withheld\" and
  cost the verbose re-run. A section is summarized in place, never cut."
  [r & {:keys [verbose?]}]
  (if (or verbose? (not= :green (:status r)))
    r
    (cond-> {:status :green :namespaces (:namespaces r) :checked (:checked r)}
      (:test r)            (assoc :test (select-keys (:test r) [:test :pass :fail :error]))
      (:external r)        (assoc :external (select-keys (:external r) [:ran :status]))
      (:modules r)         (assoc :modules (dissoc (:modules r) :edges))
      (seq (get-in r [:rules :findings])) (assoc :findings (get-in r [:rules :findings]))
      (seq (:alias-drift r)) (assoc :alias-drift (count (:alias-drift r)))
      (:bundle r)          (assoc :bundle (select-keys (:bundle r) [:sha :behind]))
      (:app r)             (assoc :app (:app r))
      (:standing r)        (assoc :standing (:standing r))
      (:scope r)           (assoc :scope (:scope r))
      (:currency-broken r) (assoc :currency-broken (:currency-broken r))
      (seq (:empty-namespaces r)) (assoc :empty-namespaces (count (:empty-namespaces r)))
      (:crossings r)       (assoc :crossings {:unchecked (count (:unchecked (:crossings r)))
                                              :unclassified (count (:unclassified (:crossings r)))
                                              :rows "full_check {verbose true}"}))))

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

  **A PINNED identity is a claim too** (s18): a one-shot `--call done
  {agent <sid>}` — the Stop hook — opens pinned but had claimed nothing, so
  it read the NEXT session's ask as its own; that session then opened no
  turn and every form it wrote belonged to no ask. Measured: a five-ask
  lifetime reached main with one turn-begin. The claimed id is the
  intent-sid or, failing that, the pinned agent-id.

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
    (let [claimed (or (:intent-sid @session)
                      (when (:pinned-agent? @session) (:agent-id @session)))
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
  `:forms` (`query_flow`), `:target` (`query_slice`), or the map itself
  (`query_brief`). A source whose text is not the form's current text — a
  window, an older version — is never a reference.

  Why: `told!` stubs a whole payload the same call already returned and
  knows nothing about forms, so orient → slice → query_source of one form
  sent its text three times, and the read after the agent's own write sent
  back what the agent had typed. eval10 measured reads at 52% of all output."
  [session x]
  (let [row (fn [m ns-sym nm]
              (let [s (:source m)
                    v (when (string? s) (orient/form-version session ns-sym nm))]
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
                    (vector? (:rows x))  (update :rows #(mapv one %))
                    (vector? (:forms x)) (update :forms #(mapv one %))
                    (map? (:target x))   (update :target one))
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
                                  :on :direction :from :to :reach])]
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
      (ledger-hold! session (orient/form-version session (symbol (str ns-sym)) (symbol (str nm)))))
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
  "Namespaces a change's steps need that the store does not have yet. Three
  shapes: a step whose source IS an `(ns …)` form for a missing namespace
  creates it from that form and leaves the group (the whole-file heredoc
  gesture — how every model writes a NEW file — leads with its ns form;
  without this the blob refused with \"no namespace — ingest it first\",
  probed live, s13); a step that IS a creation — `{action ns_create ns
  requires?}`, or `{ns requires}` with no source — runs `create-ns!` and
  leaves the group (eval26 opus: refused twice as an unknown action and a
  step with no source, then the ns_create itself refused because a later
  step had created the namespace empty); and any other step naming a
  missing namespace creates it EMPTY and stays in the group (eval24
  canary). Returns `{:steps <the rest> :created [create-results]}` — or
  `{:error …}` when a create refuses. An ns form for an EXISTING namespace
  is not a create: it stays in the group and replaces — a require edit
  arriving by blob."
  [session steps a]
  (let [st       (:store @session)
        exists?  (fn [s] (some? (get-in (:store @session) [:namespaces (symbol (str s))])))
        ns-step? (fn [s]
                   (and (nil? (:action s)) (string? (:source s)) (:ns s)
                        (nil? (get-in st [:namespaces (symbol (str (:ns s)))]))
                        (let [sx (some-> (edit/parse-form (:source s)) :node
                                         (as-> nd (try (n/sexpr nd)
                                                       (catch Exception _ nil))))]
                          (and (seq? sx) (= 'ns (first sx))
                               (= (symbol (str (:ns s))) (second sx))))))
        create-step? (fn [s]
                       (and (:ns s)
                            (or (= :ns_create (:action s))
                                (and (nil? (:action s)) (nil? (:source s)) (:requires s)))))
        creates  (filter #(or (ns-step? %) (create-step? %)) steps)
        results  (reduce (fn [acc s]
                           (let [r (ops/create-ns! session (symbol (str (:ns s)))
                                                   :source (:source s)
                                                   :requires (:requires s)
                                                   :prompt (or (:prompt s) (:prompt a))
                                                   :agent (:agent a))]
                             (if (:error r) (reduced r) (conj acc r))))
                         [] creates)]
    (if (map? results)
      {:error (:error results)}
      ;; the other shape: a plain step for a namespace nobody has created
      (let [rest-steps (vec (remove #(or (ns-step? %) (create-step? %)) steps))
            missing    (->> rest-steps
                            (filter #(and (:ns %) (string? (:source %))
                                          (contains? #{nil :add :replace} (:action %))
                                          (not (exists? (:ns %)))))
                            (map #(symbol (str (:ns %))))
                            distinct)
            empties    (reduce (fn [acc nsx]
                                 (let [r (ops/create-ns! session nsx
                                                         :source (str "(ns " nsx ")\n")
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))]
                                   (if (:error r) (reduced r) (conj acc r))))
                               [] missing)]
        (if (map? empties)
          {:error (:error empties)}
          {:steps rest-steps :created (into results empties)})))))

(defn- advertised-tools
  "What tools/list would advertise to THIS session right now — the one
  selection both the list and the drift notifier read, so they can never
  disagree: empty in CLI mode (the shell is the surface), else the dieted
  fourteen (s14, adopted by measurement: prose rides the bundle as cards)."
  [session]
  (if (or (:cli-mode? @session) (some? (System/getenv "SLOPP_CLI")))
    []
    tools/dieted-tools))

(defn- view-session!
  "The session a READ answers from when it names a `branch`, or a `thread`
  that is not this session's own: a throwaway copy whose `:store` is that
  line's value — the branch's landed head, or the thread's un-landed view —
  with no image, so nothing it does moves this session. The value is cached
  on the real session by line, and a second look at a line whose head has
  not moved loads nothing.

  Reads are scoped by BRANCH and writes by THREAD. This session's own thread
  is where its store already sits, so naming it is the identity case and
  costs nothing; naming anything else is a view. A read that needs the image
  — an eval, an observe, a test run — cannot be served from a value alone and
  refuses, naming the verb that moves the session THERE rather than looks.

  A WRITE naming a thread is not a view but routing: `call-op-1!` switches
  the session onto it, and this returns the session untouched."
  [session name arguments]
  (let [conn       (:db @session)
        branch     (some-> (:branch arguments) str)
        thread     (some-> (:thread arguments) str)
        own-branch (:branch @session)
        wanted     (cond (and branch (not= branch own-branch))               [:branch branch]
                         (and thread (not= thread (engine/thread-key session))) [:thread thread])]
    (if-not (and conn wanted (contains? tools/read-only-tools name))
      session
      (let [[kind id] wanted]
        (when-not (contains? tools/image-free-tools name)
          (throw (ex-info (str name " answers from this session's image, which holds its"
                               " own line — a branch or thread on a read is a VIEW of"
                               " the store value only. To be there: "
                               (if (= :branch kind)
                                 (str "branch_switch {name \"" id "\"}")
                                 (str "thread_open {thread \"" id "\"}")))
                          {:tool name kind id})))
        (let [line (if (= :branch kind)
                     (db/line-id-by-name conn id)
                     (:id (first (filter #(= id (:agent %))
                                         (db/open-threads conn (engine/session-branch-line session))))))
              _    (when-not line
                     (throw (ex-info (if (= :branch kind)
                                       (str "no branch " id " — query_branches lists them")
                                       (str "no open thread " id " on " own-branch
                                            " — thread_list shows what is here"))
                                     {:tool name kind id})))
              head (db/line-head conn line)
              st   (or (let [c (get-in @session [::views line])]
                         (when (= head (:head c)) c))
                       (let [st (or (db/load-store conn line)
                                    (throw (ex-info (str id " has no persisted value yet") {})))]
                         ;; a few lines, not a history: a view is a look, and
                         ;; the values are whole stores
                         (swap! session update ::views
                                #(assoc (into {} (take 3 %)) line st))
                         st))]
          (atom (assoc @session :store st :line line :image nil ::view wanted)))))))

^:unsafe (defn stop-app!
  "Stop this session's app server, if it holds one, and forget it. NEVER
  throws. The third verb beside [[start-app!]] and [[refresh-app!]], so a
  caller above this layer — the daemon closing a project — never reaches
  web tooling itself: the app image is a CHILD JVM, and stopping it before
  its owner goes is what frees the port before the next server wants it."
  [session]
  ;; read INSIDE the lock: a refresh assigns the handle after its boot
  ;; returns, and a read taken before that saw nothing to stop
  (locking session
    (when-let [running (:app-server @session)]
      (try (live/stop! running) (catch Throwable _ nil))
      (swap! session dissoc :app-server)
      {:stopped true})))

^:reads (defn ^:export write-tool?
  "Whether tool `name` WRITES the store — the classification the turn gate
  and the thread rule key on, answered for a caller above the transport
  (the daemon's write door refuses a write that names no thread)."
  [name]
  (contains? tools/write-tools name))

^:reads (defn ^:export app-managed?
  "Whether slopp runs this store's app server at all — the gate
  [[start-app!]] and [[refresh-app!]] apply, answered for a caller above
  the transport: the daemon starts a project's app server on first attach
  only when there is one to start, and opens the reader that would own it
  only then.

  Never when THIS process is the store's own declared entry
  (`live/managed-child-of?`): slopp's in-progress daemon, booted from its
  store by the machine daemon, would otherwise boot a child of itself onto
  its own port the moment its own project attached to it."
  [session]
  (boolean (and (:dir @session)
                (not (live/managed-child-of? (:dir @session)))
                (live/managed? (:store @session) server/served-namespaces))))

^:unsafe (defn refresh-app!
  "Re-serve this project's app on the CURRENT store, or stop a managed server
  the store has opted out of. nil when there is nothing to do. NEVER throws.

  Called at each `done` point, which is the grain the whole feature is built
  around: mid-episode the store is intentionally incomplete, and a browser
  reloading into a half-written red state teaches the author to ignore it.

  **A boot that FAILED is recorded on the session as `:app-boot-failure`**
  (the reason), and cleared when the instance serves again or the store
  stops asking for one. The app image is booted by a process that has
  never seen the namespace — the cold-load oracle a warm image cannot be —
  and `done!` counts a recorded failure against the STORE's verdict, so a
  commit point is refused until the instance boots. Not against the
  episode: the episode carrying the fix has to land for the reboot from
  the landed state to clear it.

  **A session with an `:app-owner` refreshes the OWNER's server.** Under the
  daemon N sessions share one project, and the app server is the project's
  — one, on the branch, held by the project's reader. Every session names
  that reader as its owner — as a DELAY, forced here, so a project whose
  app nobody serves never opens the reader at all — the refresh runs there
  over the branch's current value, and the handle and the boot failure are
  mirrored back so `session_brief`, the done note and the verdict read the
  same server. Without this, each session held its own `:app-server` and
  the second one to land booted a second server onto the first one's port.
  A session whose store runs no app does not force the owner either:
  nothing to refresh, nothing to open.

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
  (if-let [owner (when-let [o (:app-owner @session)]
                   ;; a project whose app nobody serves: do not open the
                   ;; reader just to learn there is nothing to refresh
                   (when (or (not (delay? o)) (realized? o)
                             (app-managed? session) (:app-server @session))
                     (force o)))]
    (do ;; the owner reads the BRANCH; what just landed has to be in its
        ;; value before the server is re-served from it
        (when (:db @owner)
          (try (engine/refresh-cache! owner) (catch Throwable _ nil)))
        (let [r (refresh-app! owner)]
          (swap! session assoc
                 :app-server (:app-server @owner)
                 :app-boot-failure (:app-boot-failure @owner))
          r))
    (let [dir (:dir @session)
          r   (if (and dir
                   ;; never for the store this process is the declared entry
                   ;; OF — it would be booting a child of itself onto its own
                   ;; port. Nothing to stop below either: it never started one.
                   (not (live/managed-child-of? dir))
                   (live/managed? (:store @session) server/served-namespaces))
                (locking session
                  ;; IN PLACE first. A re-boot replaces the child JVM, so the app's
                  ;; `:http/perform-ctx` is rebuilt and any state it kept there — a
                  ;; cache, a registry, a pool — is silently gone at every done point.
                  ;; `hot-refresh!` answers nil for everything in-place cannot serve (a
                  ;; changed load order, a failed reload, nothing running), and the
                  ;; re-boot below is the fallback rather than the default.
                  (let [r (try (or (live/hot-refresh! session (:store @session)
                                                      (:app-server @session))
                                   (live/refresh! session (:store @session) dir))
                               (catch Throwable t
                                 {:serving? false :reason (or (.getMessage t) (str t))}))]
                    ;; the OUTCOME beside the report: measure app-behind AFTER the
                    ;; refresh, so a refresh that said it worked and left the image
                    ;; behind is news rather than the silence success is entitled to.
                    ;; Both doors that call this (done, commit_point) read it off the
                    ;; map. nil when it cannot be measured, which makes no claim
                    (if (and (map? r) (:serving? r))
                      (assoc r ::behind (try (ops/app-behind session r)
                                             (catch Throwable _ nil)))
                      r)))
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
                                  " server was stopped"))}))]
      ;; the verdict's half: a boot that failed is remembered until one
      ;; succeeds or the store stops asking; a stop on purpose is not a failure
      (swap! session assoc :app-boot-failure
             (when (and (map? r) (false? (:serving? r)) (not (:stopped r)))
               (:reason r)))
      r)))

(def ^:private env-handlers!
  "call-tool dispatch — deps/branches/build/help (Q4: the stable dispatch tail lives in
  per-group handler maps of (fn [session a sym]); call-tool keeps only the
  hot query/edit clauses)."
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
   "thread_open"
   (fn [session a _sym]
     (text! (branch/thread-open! session :thread (:thread a) :parent (:parent a))))
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

(def op-cards
  "The tools' argument-teaching cards, as SESSION data: every session the
  daemon opens carries them under `:op-cards`, and the ask bundle's `?diet=1`
  reads them off the session there — the read API cannot require this
  namespace (that edge runs the other way), so what it needs is handed down."
  tools/op-cards)

(defn- published-commit-point!
  "A commit point, PUBLISHED: `external/commit-point!` under `label`, then —
  when it landed green on a git-configured store — the mirror push into the
  checkout's own `slopp/<branch>` (`sync/publish-local!`), with the outcome
  under `:published` and its cost under `:ms :publish`. Publish trouble rides
  along without failing the commit point.

  THE one door. The publish sat inside the `commit_point` tool's handler, and
  the closing path (`change {commit …}`, `done {commit …}`) called the
  operation directly — so a commit point taken on the way out said green and
  moved nothing in git. Found by re-dispatching CI against a projection that
  did not carry the fix it was dispatched for. Q10: the mechanical series is
  the system's job, whichever door asked."
  [session label & {:keys [agent force target]}]
  (let [r (external/commit-point! session label :agent agent :force force :target target)]
    (if (and (:commit r) (not= :red (:status r)) (:dir @session))
      (let [tp (System/currentTimeMillis)
            p  (try (sync/publish-local! (:dir @session) (:branch @session)
                                         ;; the store this session holds IS the
                                         ;; head that just landed: the projection
                                         ;; mints from it without the journal (D2b)
                                         :head-store (:store @session))
                    (catch Exception e {:error (ex-message e)}))]
        (if p
          (-> r
              (assoc :published (select-keys p [:pushed :branch :error :status :divergence :via]))
              ;; the publish, timed: it re-folds every journal before the
              ;; push and was the commit point's unmeasured ninety seconds
              (update :ms assoc :publish (- (System/currentTimeMillis) tp)))
          r))
      r)))

(defn- close-extras!
  "What closing a unit can hand over BESIDE the done's own verdict, so the
  agent has no reason to call again: the WHOLE-STORE verdict when it is
  cheap (the last one cost under eight seconds, or the store is under sixty
  namespaces — a standing verdict costs a millisecond either way), and a
  COMMIT POINT when `a` asks for one (`:commit` true, or a label). Neither
  runs on a red episode: nothing to project, and a whole-store answer would
  only restate the red. `r` is the done result. eval25 opus: done →
  full_check → commit_point after every step, ten turns a lifetime."
  [session a r]
  (let [red?   (or (= :red (:status r)) (= :red (get-in r [:findings :episode-status])))
        conn   (:db @session)
        cheap? (and conn (:line @session)
                    (let [fc (db/last-full-check conn (:line @session))]
                      (or (and fc (< (get-in fc [:result :ms] 1e9) 8000))
                          (< (count (:namespaces (:store @session))) 60))))
        ws     (when (and cheap? (not red?))
                 (try (terse-full-check (external/full-check! session))
                      (catch Exception _ nil)))
        cp     (when (and (:commit a) (not red?))
                 (let [lbl (if (string? (:commit a))
                             (:commit a)
                             (or (:label a) (:done a) (:prompt a) "commit point"))]
                   (published-commit-point! session lbl :agent (:agent a))))]
    (cond-> {;; how a teammate re-runs it — the line the model went to the
             ;; README for (eval27 opus step 1)
             :verify "slopp --call full_check '{}' from a shell in this directory runs everything, every tier; in a session, verify {op full_check}"}
      ws (assoc :whole-store (select-keys ws [:status :test :external :standing]))
      cp (assoc :commit (select-keys cp [:commit :status :error :note :jar-stale :published])))))

(defn- close-unit-after-write!
  "Threaded after a change's result `ri`: when the call carried `done` (a
  label) or `commit` and the write is GREEN (`red?` says), close the unit
  here — the done, its landing and suite, the app refresh and its note, the
  whole-store verdict when cheap, the commit point — under `:closed`; a red
  write closes nothing and `:closed` says so. The write that finishes an
  ask is one turn, not four. Both write shapes take it: the impl change and
  the tests-only change (eval26 sonnet: a tests-only fix carrying done was
  ignored, the session ended, and the work stayed on its thread).

  The app refresh rides here exactly as it rides a plain done: this is the
  path most units close on, and it used to drop the one note that carries
  a cold-boot failure (2026-09-07, found by `restart {app true}`)."
  [ri session a red?]
  (if-not (or (:done a) (:commit a))
    ri
    (if red?
      (assoc ri :closed {:closed false
                         :why (if (seq (:impl a))
                                "the change is red — nothing lands until it is green; fix forward and close again"
                                "the tests landed red — the impl is the next change; close with it")})
      (let [lbl (or (and (string? (:done a)) (:done a))
                    (and (string? (:commit a)) (:commit a))
                    (:prompt a))
            d   (external/done! session :label lbl :agent (:agent a))
            app (deref (future (refresh-app! session)) 20000 ::refresh-timed-out)
            td  (terse-done (if-let [note (app-note-for app)]
                              (assoc d :app-note note)
                              d))]
        (assoc ri :closed
               (merge (select-keys td [:done :status :landed :suite :external :external-pending :advisories :app-note])
                      (when (= :red (get-in d [:findings :episode-status]))
                        {:closed false :why "the done is red — see :findings" :findings (:findings d)})
                      (close-extras! session (assoc a :label lbl) d)))))))

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
                (if (seq tests)
                  ;; TESTS ONLY: the red-first ritual's first half on its own
                  ;; — land the spec, watch it fail, and say the impl is the
                  ;; next change (s17 grid: the top residual refusal, five
                  ;; times in one cell, was exactly this gesture)
                  (let [rt (ops/edit-group! session tests
                                            :prompt (str (:prompt a) " [tests first — expected red]")
                                            :agent (:agent a))]
                    (if (:error rt)
                      (text! (assoc (select-keys rt [:error :step :source-now]) :phase :tests))
                      (let [went-red (vec (distinct (concat (:failed-tests (:test rt))
                                                            (keep :test (:failures (:test rt))))))]
                        (ledger-written! session "edit_group" {:steps tests})
                        (text! (-> {:ok true
                                    :status (if (seq went-red) :red :green)
                                    :tests (cond-> {:landed (count tests) :went-red went-red
                                                    :spec-run (select-keys (:test rt)
                                                                           [:test :pass :fail :error
                                                                            :failed-tests :status])}
                                             (:auto-require rt)
                                             (assoc :auto-require (:auto-require rt)))
                                    :note (if (seq went-red)
                                            (str "the tests landed RED and are watched — the"
                                                 " implementation is the next change {impl …}")
                                            (str "the tests landed GREEN — the spec was never"
                                                 " watched failing; a green you did not watch"
                                                 " fail proves nothing"))}
                                   ;; a tests-only change carrying done/commit closes
                                   ;; too — an expectation fix is often the last write
                                   (close-unit-after-write! session a (boolean (seq went-red))))))))
                  (text! {:error (str "change needs :impl steps — a question is explore;"
                                      " a test you are writing to FIND something out is"
                                      " explore {ops [{op check code …}]}")})))
              (let [rt (when (seq tests)
                         (ops/edit-group! session tests
                                          :prompt (str (:prompt a) " [tests first — expected red]")
                                          :agent (:agent a)))]
                (if (and rt (:error rt))
                  (text! (assoc (select-keys rt [:error :step :source-now]) :phase :tests))
                  (let [went-red (vec (distinct (concat (:failed-tests (:test rt))
                                                        ;; a red-first stub THROWS — the spec's
                                                        ;; first run is an :error row, not a
                                                        ;; :failed-tests entry, and it was watched
                                                        ;; failing all the same
                                                        (keep :test (:failures (:test rt))))))
                        _ (when rt (ledger-written! session "edit_group" {:steps tests}))
                        ri (let [r (ops/edit-group! session impl
                                                     :prompt (:prompt a) :agent (:agent a))]
                             ;; a require the TESTS phase added is this write's repair
                             ;; too — unreported, the agent learned nothing from it
                             (merge (select-keys rt [:auto-require :auto-requires
                                                     :auto-module-dep :auto-module-deps])
                                    r))]
                    (if (:error ri)
                      (text! (cond-> (assoc (select-keys ri [:error :step :source-now])
                                            :phase :impl)
                               (seq tests) (assoc :tests {:landed (count tests)
                                                          :went-red went-red})))
                      (let [ri (-> ri
                                   (held-after-write! session "edit_group" {:steps impl})
                                   (finish-accepted! session a)
                                   (attach-red-context! session)
                                   (close-unit-after-write! session a (red? (:test ri))))]
                        (text!
                         (cond->
                          {:ok true
                           :impl (select-keys ri [:group :steps :warnings :drift
                                                  :red-first :note :canonicalized
                                                  :auto-require :auto-requires
                                                  :auto-module-dep :auto-module-deps])
                           :test (:test ri)
                           :status (if (red? (:test ri)) :red :green)}
                           (seq tests)
                           (assoc :tests
                                  (cond-> {:landed (count tests) :went-red went-red
                                           :spec-run (select-keys (:test rt)
                                                                  [:test :pass :fail :error
                                                                   :failed-tests :status])}
                                    (empty? went-red)
                                    (assoc :note (str "the tests landed GREEN — the"
                                                      " spec was never watched failing;"
                                                      " a green you did not watch fail"
                                                      " proves nothing"))))
                           ;; a namespace this change had to create for a step is part of
                           ;; what it did
                           (seq (:created born)) (assoc :created (mapv #(select-keys % [:ns :forms]) (:created born)))
                           (:finisher ri)      (assoc :finisher (:finisher ri))
                           (:closed ri)        (assoc :closed (:closed ri))
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
       ;; the QUESTION is gated (check-gate: fixtures allowed, reaching out
       ;; refused), then evaluated in a scratch namespace — clojure.core and
       ;; clojure.test referred, symbols resolved there via eval — that is
       ;; removed with the answer. The scaffolding is ours and runs ungated.
       (let [code    (str "(do " (:code a) ")")
             wrapped (str "(do (require (quote clojure.test))"
                          " (let [slopp-check-ns (symbol (str \"slopp.check.scratch\" (System/nanoTime)))"
                          "       slopp-check-reports (atom [])]"
                          "  (try"
                          "   (binding [*ns* (create-ns slopp-check-ns)]"
                          "    (clojure.core/refer (quote clojure.core))"
                          "    (clojure.core/refer (quote clojure.test))"
                          "    (binding [clojure.test/report"
                          "              (fn [m] (swap! slopp-check-reports conj"
                          "                        (select-keys m [:type :expected :actual :message])))]"
                          "     (let [slopp-check-value (eval (read-string " (pr-str code) "))]"
                          "      {:value slopp-check-value"
                          "       :assertions (deref slopp-check-reports)})))"
                          "   (finally (remove-ns slopp-check-ns)))))")
             r (if-let [err (edit/check-gate (:code a))]
                 {:error err}
                 (ops/query-eval session wrapped :gate (constantly nil)))]
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

^:unsafe (defn ^:export reserve-owner!
  "Bring an app OWNER (a project's reader) current with its branch and
  re-serve its managed app from the advanced value. The daemon calls this to
  keep a dev instance current with a landing by ANY writer on the shared
  store: `refresh-app!` alone re-serves from the owner's CURRENT store value,
  so the owner must absorb the landing FIRST. Returns `refresh-app!`'s
  result."
  [owner]
  (engine/refresh-cache! owner)
  (refresh-app! owner))

(defn- call-op!
  "THE dispatch seam every route crosses — family dispatch, the bare `--call`
  door, and explore's recursive entries — so it is where a shape is
  REPAIRED before `call-op-1!` validates and runs it (`tools/remap-arguments`;
  s17: refusals on unambiguous argument shapes were 24–38% of eval calls,
  each one re-sent and retried). `query_eval`/`check {ns code}` resolves
  its symbols in that namespace: the RAW code is gated first, because the
  wrapped form quotes it and quoted data is inert to the observe gate. What
  was repaired rides the result as a leading `;; repaired …` line, so the
  accepted shape is learned for free."
  [session {:keys [name arguments] :as req}]
  (let [{n' :name a' :arguments repaired :repaired} (tools/remap-arguments name arguments)
        [a' repaired] (if (and (#{"query_eval" "check"} n') (:ns a') (string? (:code a'))
                               (nil? (edit/observe-gate (:code a'))))
                        [(assoc (dissoc a' :ns)
                                :code (str "(binding [*ns* (the-ns '" (:ns a') ")]"
                                           " (eval (read-string "
                                           (pr-str (str "(do " (:code a') ")"))
                                           ")))"))
                         (assoc (or repaired {}) :ns-bound (str (:ns a')))]
                        [a' repaired])
        r (let [s' (view-session! session n' a')
                r  (call-op-1! s' (assoc req :name n' :arguments a'))]
            ;; a stub or a trim spooled on the view must stay openable here
            (when-not (identical? s' session)
              (swap! session assoc ::spool (::spool @s')))
            r)]
    (if (and (seq repaired) (get-in r [:content 0 :text]))
      (update-in r [:content 0 :text] #(str ";; repaired " (pr-str repaired) "\n" %))
      r)))

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
    "tools/list" (let [advertised (advertised-tools session)]
                   (swap! session assoc ::tools-hash (hash advertised))
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
          why   (refusal-text r)
          ;; the EVENTS queued since the last answer — what landed under this
          ;; session, what another session landed on its project — as one
          ;; leading line: the push that reaches the model, since an MCP
          ;; notification goes to the client's log and never to the
          ;; conversation. Drained here, so it is said once.
          r     (if-let [evs (seq (:events @session))]
                  (do (swap! session dissoc :events)
                      (update-in r [:content 0 :text]
                                 #(str ";; since your last call: "
                                       (str/join " | " (map :note evs))
                                       "\n" %)))
                  r)]
      ;; after the call, so a tool that reads the ring (turn_end) never sees
      ;; its own half-finished entry
      (let [entry (merge
                   {:tool (:name params)
                    ;; the OP, when the call came through a family: what a
                    ;; cost is RANKED by (s20: the census knew only the family)
                    :op   (some-> (get-in params [:arguments :op]) str)
                    ;; WHAT IT WAS SENT: the request's size, the output tokens
                    ;; that are the wall-side cost. Only a transcript census
                    ;; could see this before.
                    :chars-in (count (pr-str (:arguments params))) :start t0 :end (System/currentTimeMillis)
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

(defn- call-tool!
  "The wire entry. A FAMILY name (`tools/families`) with `op` resolves to the
  op's registry name and dispatches through `call-op!`; a single-op family
  needs no op; an op called by its own name dispatches directly (the
  `--call` door, the hooks). An op that EXISTS but was asked of the wrong
  family (`history {op query_search}`) dispatches too — the agent named a
  real op, and which family it files under is our taxonomy, not a rule. An
  unknown op is refused with the family's op list, so the refusal is the
  index."
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

        (and (not (some #{op} ops))
             (not (tools/accepted-arg-keys op))
             (not (#{"explore" "check" "change" "report"} op)))
        (throw (ex-info (str "unknown op " op " for " name " — ops: " (str/join " " ops))
                        {:tool name :op op :ops ops}))

        :else
        (call-op! session (assoc req :name op :arguments (dissoc arguments :op)))))
    (call-op! session req)))

^:unsafe (defn ^{:export true
                 :breaking-ok "the [req] arity read its session from :http/deps for the retired per-session listener; the daemon, the only caller, passes the session explicitly"}
  http-call!
  "`POST /api/projects/<slug>/call` on the daemon — the CLI door onto a
  RUNNING slopp. Body: `{\"tool\" \"<op>\" \"arguments\" {…} \"token\" \"<secret>\"}`.
  Invokes [[call-op!]] on `session` — the same dispatch, turn gating,
  ledger and anticipation MCP calls get — and answers `{\"isError\" bool
  \"text\" \"…\"}` with the joined content text, the shape the CLI prints. A
  thrown refusal crosses as isError text, never a stack trace: the caller
  is a terminal. The daemon's declared door passes the project's CLI
  session explicitly.

  The token is a per-boot secret — written into `~/.slopp/daemon.json`
  beside the daemon's address — and it is the session's `:call-token`.
  Loopback binding alone must not grant every local process write access
  to the store — 403 without it, and nothing runs. Why this door exists
  (s12c, measured): the one-shot JVM path that preceded it booted a JVM and
  loaded the whole store in silence; an agent reached for it unprompted,
  waited on the cold path for minutes, and spent eight turns babysitting
  the process — while a live process held the warm image the whole time.
  That path is retired; this is the only door a shell has.

  FOR ANYONE PROXYING THE DAEMON: this write door shares the port with the
  read endpoints — it differs by path and method, not by port. A reverse
  proxy MUST NOT forward it unless it means to hand the store's editing
  surface to everything that can reach the proxy (slopp-ui's hub verified
  its GET-only stance at the wire, 2026-09-01)."
  [req session]
  (let [body    (:body req)
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
      (raw 503 {:error "no live session behind this door"})

      (or (nil? want) (not= (str (:token b)) (str want)))
      (raw 403 {:error "bad or missing token — read it from ~/.slopp/daemon.json"})

      (not (string? (:tool b)))
      (raw 400 {:error "call needs {tool arguments} — tool is the op name"})

      :else
      (let [args (cond-> (or (:arguments b) {})
                   (:agent b) (assoc :agent (:agent b)))
            ;; the SAME bindings the MCP wire gives every call: the hint
            ;; machinery (the thread reminder that would have saved the
            ;; stranded s13 cell), the spool for trimmed payloads, the
            ;; response-facts sink. Without these every routed result was
            ;; hint-blind — for scripts and humans, not only eval cells.
            r    (binding [*hint* (or (smells/track-hint! session (:tool b) args)
                                      (delay (thread-hint! session (:tool b))))
                           *spool-session* session
                           *response-facts* (atom {})]
                   (try (call-op! session {:name (:tool b) :arguments args})
                        (catch Exception e
                          ;; the skill teaches FAMILIES (read {op …}), the CLI
                          ;; preamble teaches bare ops, and real cells use both
                          ;; — resolve the family spelling before refusing
                          (or (when (re-find #"unknown tool" (str (ex-message e)))
                                (try (call-tool! session {:name (:tool b)
                                                          :arguments args})
                                     (catch Exception e2
                                       {:isError true
                                        :content [{:type "text"
                                                   :text (or (ex-message e2) (str e2))}]})))
                              {:isError true
                               :content [{:type "text"
                                          :text (or (ex-message e) (str e))}]}))))]
        (raw 200 {:isError (boolean (:isError r))
                  :text (apply str (map :text (:content r)))})))))

(defn- call-op-1! [session {:keys [name arguments]}]
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
  (when-let [s (ops/sync-with-journal! session)]
    ;; what MOVED under this session since its last call — another server's
    ;; or session's landing, absorbed just now — rides this answer's first
    ;; line. The sync always knew; the agent was never told.
    (when (seq (:changed s))
      (ops/note-event! session
                       {:kind :moved
                        :note (str (count (:changed s)) " namespace(s) changed under you"
                                   " since your last call (something landed): "
                                   (str/join ", " (take 8 (:changed s)))
                                   (when (< 8 (count (:changed s))) ", …"))})))      ; m5b: absorb other servers' commits
  (absorb-pending-intent! session)
  ;; A NEW ASK IS A NEW TURN. The gate used to open one only when none was
  ;; open, and nothing ever closed one, so a single turn spanned an entire
  ;; session: measured on slopp's own store, five :turn-begin deltas across
  ;; ~15 asks and ZERO :turn-end. Two things were lost by that — the turn's
  ;; wall-clock timing, which rides turn-end and therefore never landed, and
  ;; worse, EVERY ASK AFTER THE FIRST, which never reached the journal at all.
  ;; Rotating costs two marker deltas per ask.
  ;; THREAD ROUTING (D-daemon P5-0): a call naming a `thread` writes on THAT
  ;; line. The session's identity stays what the harness said; the thread is
  ;; the routing key and defaults to it. Switching re-adopts the line and
  ;; reloads the store and image when its head differs — correct, and the
  ;; expensive way; a daemon keeps a session per thread instead.
  (when-let [t (:thread arguments)]
    (when (and (not= (str t) (engine/thread-key session))
               ;; thread_open adopts for ITSELF, under a parent when asked.
               ;; Adopting here first minted the id top-level, and the op
               ;; then refused its own id as already open.
               (not= "thread_open" name)
               ;; a READ naming a thread is a VIEW (`view-session!`), not a move
               (not (contains? tools/read-only-tools name)))
      (swap! session assoc :thread (str t))
      ;; a store not yet materialized has no line to adopt: the first
      ;; durable write creates it and adopts by thread-key, which now
      ;; names this thread. Gating the RECORDING on the store lost the
      ;; thread of exactly that write, and the line was minted under the
      ;; session's own label.
      (when (:db @session)
        (engine/adopt-line! session))))
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
          ;; NAME THE CAUSE. \"no open turn\" alone reads as \"the turn did not
          ;; take\", which sends you to look at turns; the usual real cause is
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
                                 " shell call (slopp <op>) runs on a session of its own with a fresh"
                                 " identity per process, so turn_begin and the"
                                 " write must be passed the SAME agent argument —"
                                 " otherwise the second call opens a second turn"
                                 " and this refusal repeats verbatim.")
                            {:dir (:dir @session) :agent ag})))))))
  (let [a   (assoc arguments :agent (or (:agent arguments)
                                        ;; the label defaults to the THREAD, so
                                        ;; a write routed somewhere is credited
                                        ;; there unless it says otherwise
                                        (some-> (:thread arguments) str)
                                        (:agent-id @session)))
        sym (fn [k]
              (if-let [v (get a k)]
                (symbol v)
                (throw (ex-info (str "missing required argument :"
                                     (clojure.core/name k) " for " name
                                     (get {["query_history" :ns]
                                           (str " — asking store-wide? report {since}"
                                                " composes the whole story;"
                                                " query_commits lists commit-points")}
                                          [name k] ""))
                                {}))))
        ;; source args are passed raw (not through `sym`), so a misnamed key
        ;; (`new_source` for `source`) silently became nil and fell through to a
        ;; confusing \"got 0 forms\" parse error. `src` validates a required source
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
                                                :doc (:doc a)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "ns_add_require" (text! (let [r (ops/add-require! session (sym :ns) (:require a)
                                                     :prompt (:prompt a)
                                                     :agent (:agent a))]
                                 ;; :already is the whole answer when the clause was
                                 ;; there — it must survive the wire-key cut
                                 (cond-> (-> r (select-keys tools/wire-keys) (summarize (:verbose a)))
                                   (:already r) (assoc :already true))))
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
      ;; anything runs. `test_run` is allowed in: it writes no code, and
      ;; agents bundle it with their reads (s17 census).
      (let [ops (mapv deep-kw (:ops a))
            bad (some (fn [o]
                        (let [nm (some-> (:op o) clojure.core/name)]
                          (cond
                            (nil? nm) {:error "every explore entry needs :op — a read op name"}
                            (and (not (contains? tools/read-only-tools nm))
                                 (not= "test_run" nm)
                                 ;; a PREVIEW writes nothing: it is a read (eval27
                                 ;; opus: refused in every cell, then made alone)
                                 (not (and (= "rename_sweep" nm) (:dry_run o))))
                            {:error (str nm " is a write — explore is READ questions only;"
                                         " writes have their own grain (change)")}
                            :else nil)))
                      ops)]
        (cond
          (empty? ops)         (text! {:error "explore needs ops — [{op …args} …]"})
          bad                  (text! bad)
          (< 12 (count ops))   (text! {:error (str (count ops) " entries — explore is at most 12;"
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
                                                        ;; a SMALL namespace is one read, whole — cards
                                                        ;; by default measured as cards-then-fetch (eval24
                                                        ;; canary: 24/29 turns against 19 whole); the flow
                                                        ;; hint rides either way. A big one is cards with
                                                        ;; :v, so the read after an edit shows the edit.
                                                        (let [src (query/query-source session n)
                                                              src (if (string? src) src (:source src))]
                                                          (if (and src (<= (count (str src)) 6000))
                                                            {:ns n :source src :whole true
                                                             :hint (orient/read-hint "whole, small.")}
                                                            (orient/ns-cards session n))))]
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
                                           ;; anticipation (the requires' sources) rides the WHOLE
                                           ;; read only; a card read's next question is a flow or
                                           ;; a body, not its requires' dumps
                                           (if full?
                                             (if-let [ts (some-> (:targets a) normalize-targets seq)]
                                               (set (keep :ns ts))
                                               #{(sym :ns)})
                                             #{}))))
                                 ;; an EXPLICIT read: the caller named what it wanted, and
                                 ;; trimming it was measured as a tax — 69% re-bought, each
                                 ;; re-buy a whole model request (s20). 32k still walls a
                                 ;; typo naming fifty namespaces.
                                 :ceiling 32000)
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
                                              ;; the journal's own answer to \"what usually
                                              ;; breaks when this changes\" — a var's blast
                                              ;; radius is callers AND the tests that went
                                              ;; red beside it. Absent when there is no
                                              ;; evidence: an empty list would read as safe.
                                              reds (when (= :var (:kind r))
                                                     (ops/red-after session (:on a)))]
                                          (cond-> r reds (assoc :red-after reds)))))
      ;; HOW forms connect, with the bodies on the way — the question a
      ;; whole-namespace read was standing in for (eval22 step 2: 41 of
      ;; them, zero graph questions)
      "query_flow" (text! (told! session name a
                                     (query/flow-view session :from (:from a) :to (:to a)
                                                      :on (:on a) :reach (:reach a)))
                              :budgeted? true)
      "session_brief" ;; git alignment is a QUESTION (query_git), not orientation: it rode on
                       ;; every brief as ~500 chars an agent never acted on
                       (if (:db @session)
                         (text! (told! session name a (ops/session-brief session))
                                :budgeted? true)
                         ;; an unadopted directory has no journal to brief from —
                         ;; say so (it used to throw from a nil connection)
                         (text! {:error (str "no slopp store at " (:dir @session)
                                             " — serving here starts empty: the first write"
                                             " creates .slopp/store.db, or import a slopp"
                                             " branch first (slopp --main slopp.sync/-main"
                                             " import .)")}))
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
                                               :by (:by a)
                                               :limit (:limit a)
                                               :otel (ops/otel-measurements session :since (:since a))
                                               ;; the per-call rows make :tools a
                                               ;; census with chars-out, not the
                                               ;; turn-end ring's top-five bound
                                               :tool-calls (ops/tool-call-measurements session :since (:since a)))))
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
                   ;; refuses and a red one STANDS, so \"finished\" and \"green\"
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
                   ;; test as \"could this line ever be news\". The deref bound
                   ;; is a backstop, not a budget — and on expiry it now says
                   ;; SO, rather than nil. nil was already the answer for \"no
                   ;; managed server\" and for \"re-served cleanly\", so an
                   ;; expiring wait joined two silences that are both fine and
                   ;; became unreadable: a consumer served twenty minutes of
                   ;; old code with every surface clean. The future still lands
                   ;; the truth in the session for session_brief, which is what
                   ;; the note points at.
                   app (deref (future (refresh-app! session)) 20000 ::refresh-timed-out)]
               ;; the ritual closing full_check, pre-empted: state the two facts
               ;; it re-derives (s14 opus: x5 per cell, each a whole-store
               ;; re-run + a fat payload riding as rent). Only a GREEN
               ;; whole-store verdict is citable.
               (if-let [whole (when (not= :red (:status r))
                                (when-let [conn (:db @session)]
                                  (when-let [line (:line @session)]
                                    (when-let [fc (db/last-full-check conn line)]
                                      (when (= :green (get-in fc [:result :status]))
                                        (str "the last full_check (" (:id fc) ") was green"
                                             " and this done re-verified everything your"
                                             " episode touched — a full_check now re-runs"
                                             " the WHOLE store, usually the commit-point-time"
                                             " call (commit_point runs the same gate)."))))))]
                 (text! (merge (assoc (terse-done (if-let [note (app-note-for app)]
                                                    (assoc r :app-note note)
                                                    r))
                                      :whole-store whole)
                               ;; the cheap whole-store verdict and the commit point
                               ;; ride the same answer (eval25 opus: three calls to close)
                               (close-extras! session a r)))
                 (text! (merge (terse-done (if-let [note (app-note-for app)]
                                             (assoc r :app-note note)
                                             r))
                               (close-extras! session a r)))))
      "commit_point" (text! (let [r (published-commit-point! session (:label a)
                                                          :agent (:agent a)
                                                          :force (:force a)
                                                          :target (:target a))
                                  ;; A COMMIT-POINT IS A DONE POINT. `commit-point!`
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
                                  ;; happened to use. The PUBLISH had the same
                                  ;; defect one door over — see
                                  ;; `published-commit-point!`.
                                  app (deref (future (refresh-app! session)) 20000 ::refresh-timed-out)]
                              (if-let [note (app-note-for app)]
                                (assoc r :app-note note)
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
                         ;; almost always the redundant \"confirm everything\" the
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
