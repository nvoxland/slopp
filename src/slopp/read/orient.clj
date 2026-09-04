(ns slopp.read.orient
  "What a session needs to know before it does anything — assembled purely.

  `session_brief` is the one call a fresh context is told to make, so this is
  where the answer to \"where am I, and what should I distrust?\" is built. The
  sections are pure functions of already-recorded facts: the store value, the
  kernel's boot-info record, the delta counts. Nothing here queries; the
  effectful callers gather, and this shapes.

  The bias throughout is toward saying what is WRONG or STALE rather than what
  is present. A brief that lists everything is a brief nobody reads; a brief
  that names the one namespace whose reload failed is what stopped three
  debugging arcs from starting. Notes COMPOSE rather than replace each other,
  because a host can be stale for more than one reason at once and picking a
  winner hides the rest."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store] [slopp.store.fields :as fields] [slopp.edit.modules :as edit.modules] [slopp.index.crossings :as crossings] [slopp.index.refs :as refs] [slopp.store.render :as store.render]))

(defn ^:export snip
  "Cap `s` at `n` chars with an ellipsis — composites (brief/report) carry
  MANY prose fields and must never give back the tokens they save; the
  full text stays one query away (report {contains}, query_history)."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 n) "…"))))

(def ^:private doc-summary-cap
  "The character budget for a doc summary. Composites carry MANY of these, so
  one verbose docstring must not eat the result."
  120)

(defn doc-summary
  "The first SENTENCE of `doc`, capped — what a card or a module surface row
  shows.

  Replaces a raw character cut, which ended mid-word and produced fragments
  that look like content: `slopp.http.css/render`'s card used to read
  \"Garden rules → a minified CSS string. Every string in the rule data — a\".
  A trailing fragment is worse than a clean stop, because it reads as though
  the thought finished.

  A sentence ends at `.`/`!`/`?` followed by whitespace and a capital or an
  opening bracket — NOT at every period, or `1.5 KB` and `e.g.` split in half
  and the result reads worse than the cut it replaces.

  Over the cap it still truncates, but on a WORD boundary with an ellipsis:
  the budget is the point, and the full text is one `query_slice` away.

  nil for nil or blank — never `\"\"` or a bare ellipsis, which would put an
  empty `:doc` key on a card and read as \"documented, with nothing to say\"."
  [doc]
  (let [s (some-> doc str str/trim (str/replace #"\s+" " "))]
    (when-not (str/blank? s)
      (let [end (some-> (re-find #"^(.*?[.!?])(?=\s+[A-Z(\[])" s) second)
            one (or end s)]
        (if (<= (count one) doc-summary-cap)
          one
          (let [cut (subs one 0 doc-summary-cap)
                sp  (str/last-index-of cut " ")]
            (str (str/trimr (if (and sp (< 40 sp)) (subs cut 0 sp) cut)) "…")))))))

^:reads (defn ^:export form-card
  "The INTERFACE view of a form (opacity with a warranty): signature,
  doc line, effect marker, the recorded WHY (last ask), and the warranty
  (covering tests from the trace map) — what a CALLER needs, at ~10x less
  than source. Trusting it is mechanical, not hopeful: every edit re-runs
  the covering tests, so a violated contract turns red with :implicated.

  Exported: the reviewer UI inlines a callee's card beside the caller
  rather than linking to it, and assembling sig/doc/why/warranty a second
  time is how the two views drift apart."
  [session ns-sym nm]
  (when-let [e (store/form-named (:store @session) ns-sym nm)]
    (let [q       (symbol (str ns-sym) (str nm))
          s       (try (n/sexpr (:node e)) (catch Exception _ nil))
          body    (when (seq? s) s)
          doc     (some #(when (string? %) %) (take 3 (drop 2 (or body ()))))
          ;; through the SHARED all-arities extraction, and only for forms that
          ;; have arities at all. This was "the first vector anywhere after the
          ;; head", which reads `(def rates [0.07 0.20])` as a parameter list —
          ;; 32 defs in this store would have reported one, including
          ;; `capabilities/registry`, whose whole 19-entry vector drew as a
          ;; signature. A card is ^:export'ed so a consumer can inline a callee
          ;; INSTEAD of reading it, which makes a confident wrong sig the worst
          ;; kind of wrong here.
          ;;
          ;; Single arity stays unwrapped (`[cents rate]`, not `[[cents rate]]`)
          ;; because that is what every reader of this key already renders.
          sig     (let [as (when (#{"defn" "defn-" "defmacro"} (str (first (or body ()))))
                             (edit.modules/fn-arglists body))]
                    (cond (= 1 (count as)) (first as)
                          (seq as)         (vec as)))
          why     (get (store/prompt-by-form (:store @session)) (:id e))
          ;; the TRAP, if the author declared one. Separate from :doc on
          ;; purpose: a doc's first line says what the form does, and the
          ;; thing that stops a caller misusing it is never in the first
          ;; line. Same :teach vocabulary every rule uses for
          ;; explain-at-point-of-use.
          ;; a STRING only. Metadata is read as data and never evaluated, so
          ;; ^{:teach (str "a" "b")} is a LIST — rendering it would put
          ;; `(str "a" "b")` on the card, confident-looking and useless.
          teach   (let [t (:teach (meta (second (or body ()))))]
                    (when (string? t) (not-empty t)))
          covered (let [ks (store/form-trace-keys ns-sym e)]
                    ;; any name the form defines can carry its evidence (#129):
                    ;; a defprotocol's card counts tests that called m or n
                    (count (keep (fn [[t fs]] (when (some fs ks) t))
                                 (:test-map @session))))
          examples (when-let [raw (get (:observed @session)
                                       (str "observed/" ns-sym "/" nm))]
                     (try
                       (->> (edn/read-string raw)
                            (take 2)
                            (mapv (fn [{:keys [args ret threw]}]
                                    (snip (str "(" nm " " (str/join " " args)
                                               ") → " (or ret threw))
                                          90)))
                            not-empty)
                       (catch Exception _ nil)))]
      (cond-> {:form q :warranty {:covered covered}}
        sig  (assoc :sig sig)
        doc  (assoc :doc (doc-summary doc))
        (str/ends-with? (str nm) "!") (assoc :effectful true)
        why   (assoc :why (snip why 90))
        teach (assoc :teach teach)
        examples (assoc :examples examples)))))

(defn ^:export fit-report
  "G13 at the gate boundary — by AGGREGATION, never amputation, and the
  DUPLICATES go first: an over-budget report drops `:intents` (the asks a
  second time), trims per-form asks to 1/row, ROLLS CHANGES UP by namespace
  ({:ns :forms :ops :asks} — `report {contains}` expands any group), and only
  then snips a `:by-ask` ask to 300 chars; amputation (take 20 of the
  rollup) is the last resort for pathological stores. (eval9: the take-20
  amputation CAUSED the handoff fan-out — agents went hunting for what the
  report dropped. eval24 opus: the asks were snipped while their two
  duplicates rode whole, and the model spent eight calls recovering them —
  the ask is the why a handoff is asked for, so it is cut last.)"
  [r]
  (let [fits? #(<= (count (pr-str %)) 6500)
        rollup (fn [cs]
                 (->> cs
                      (group-by :ns)
                      (mapv (fn [[nsx rows]]
                              {:ns nsx :forms (count rows)
                               :ops (vec (distinct (mapcat :ops rows)))
                               ;; a POINTER to the ask, whose whole text is under :by-ask
                               :asks (vec (take 1 (map #(snip % 80) (distinct (mapcat :asks rows)))))}))
                      (sort-by (comp str :ns))
                      vec))
        steps  [[#(dissoc % :intents) nil]
                [#(update % :changes (fn [cs] (mapv (fn [c] (update c :asks (comp vec (partial take 1)))) cs)))
                 "asks trimmed to 1/row — report {contains} narrows"]
                [#(assoc % :changes (rollup (:changes %)))
                 "changes rolled up by namespace — report {contains <ns or word>} expands a group"]
                [#(update % :by-ask (fn [as] (mapv (fn [a] (update a :ask snip 300)) as)))
                 "asks snipped to 300 chars — query_history {ns name} or the :turn id expands one"]
                [#(update % :changes (fn [cs] (vec (take 20 cs))))
                 (str "rolled up by namespace, showing 20 of " (count (distinct (map :ns (:changes r))))
                      " — {contains} narrows")]]]
    (loop [r r, steps steps]
      (cond
        (fits? r)      r
        (empty? steps) r
        :else          (let [[f note] (first steps)
                             r'       (f r)]
                         (recur (cond-> r' note (assoc :note note)) (rest steps)))))))

(def stuck-reload-attempts
  "Consecutive failed reload polls after which a namespace is STUCK rather
  than retrying — three.

  Low on purpose, and it can be: a reload is deterministic over the same
  source, so the second identical failure already tells you the third is
  coming. Three is one more than the argument needs, which leaves room for a
  genuine transient (a half-written db page, a contended read) without leaving
  room for false hope."
  3)

(defn- one-cause
  "The single edit every drifted row agrees on, as a clause — nil when they
  disagree or none is recorded.

  One write reloads a whole namespace, so every value captured from it falls
  behind TOGETHER. That is what makes naming one edit for a whole list honest
  rather than a guess, and it is also why the guard matters: rows that
  disagree get no cause named, instead of the note confidently blaming
  whichever sorted first.

  Shared because `host-brief` and `host-warning` are one producer aimed at two
  readers. Writing the clause into whichever surface you happened to be
  looking at is how a staleness line ends up answering \"what\" in one place
  and \"what and why\" in the other."
  [drift]
  (let [es (distinct (keep :behind-edit drift))]
    (when (= 1 (count es))
      (let [{:keys [delta prompt]} (first es)]
        (str "they went behind at " delta
             (when prompt (str " (\"" prompt "\")")))))))

(defn ^:export host-brief
  "The serving host's code-currency section for session_brief, as data —
  pure assembly over the kernel's boot-info record (`info`), the count of
  CODE-affecting deltas landed after the host booted, whether the session is
  on a branch line, and the measured `drift` between the image and the store
  (`slopp.rules.currency/drift`, or nil when it was not computed). Nil `info`
  (a process that didn't boot from a store) → nil, the section simply absent.

  The stances it teaches: a :snapshot host serves LAUNCH-time code, so
  post-boot deltas are inert until restart; a :live host tracks the MAIN
  journal only (a branch line's writes reload the image, never the host);
  a reload failure is NAMED, because a silently held-back namespace is how
  three debugging arcs started. The notes COMPOSE — a snapshot host ON a
  branch gets both stances (review V-F3, the branch caveat used to be
  unreachable in snapshot mode).

  **A reload failure carries its REASON and its age.** It used to say the
  failure was \"in the server log\" — a file nothing here exposes, which on a
  system whose claim is that the store answers everything is the wrong answer
  twice over. And it promised \"the next poll retries\" identically for many
  minutes: a reload is deterministic over the same source, so repeated failure
  is not a transient to wait out. Past `stuck-reload-attempts` the note stops
  reassuring and says what actually fixes it.

  **And it no longer claims staleness it has not measured (friction 20a).** A
  failed reload used to read \"the host still runs their previous code\", which
  is an assertion about this process made without looking at it — and it was
  WRONG in the case that cost the most: five namespaces reported stale while
  the process already held every one of them at the store's current source.

  **Nor does it claim the opposite.** The first correction over-swung: a clean
  `drift` was read as proof the failure was only a stuck watcher. But `drift`
  measures the child ORACLE — `currency/stamp!` runs here, yet records what
  was pushed INTO that separate JVM — while the reload that failed is THIS
  process's. A failed host reload is exactly when the two diverge, so the
  oracle comparing clean is no evidence at all about the host.

  **So the host now measures ITSELF.** `slopp.kernel.boot/host-drift` compares what
  this process actually loaded — recorded by every door into this JVM,
  `load-store!` and the live watcher — against the store's current sources,
  kernel rendering on both sides. It arrives on `info` as `:host-drift`, and a
  failed reload finally has three honest answers instead of one guess:

  - `[]` — measured current, so the failure is the WATCHER being stuck, which
    is the claim friction 20a needed and could not earn;
  - a list — measured behind, and it NAMES the namespaces;
  - absent — nobody looked, and it says so rather than picking a side.

  The two subjects read side by side and cannot be confused: `:host-drift` /
  `:host-verified` for this process, `:oracle-drift` / `:oracle-verified` for
  the image that runs the tests. Same quantity, each named for who it is about.

  A comparison also cannot get STUCK the way a failure record can: 20a's
  watcher retried a renamed-away namespace forever, but a namespace the store
  no longer has is simply absent from the comparison.

  **`:jar` says which ARTIFACT is answering**, and arrives on `info` the same
  way `:host-drift` does — both are facts about this process that only a
  caller holding the store can finish. `slopp.kernel.boot/jar-head` supplies
  the identity and `jar-currency` places it; absent means the process cannot
  say, which is a checkout or a jar built before the stamp existed. Until it
  existed, `:host` reported which MODE this process runs in and never which
  CODE, so \"am I running the slopp that has the fix\" was not answerable from
  the brief at all — it was answered six times by hand, once wrongly.

  (`host-warning` is a different reader and is correct using oracle drift: a
  VERDICT is produced by the oracle, so the oracle's currency is precisely
  what should qualify it.)"
  [info deltas-since-boot on-branch? drift]
  (when info
    (let [failed  (seq (:failed info))
          why     (:failed-why info)
          checked (some? drift)
          clean?  (and checked (empty? drift))
          hdrift  (:host-drift info)
          hcheck  (some? hdrift)
          hclean  (and hcheck (empty? hdrift))
          reason  (fn [ns-sym]
                    (when-let [w (get-in why [ns-sym :why])]
                      (str ns-sym ": " w)))
          tries   (apply max 0 (keep #(get-in why [% :attempts]) failed))
          stuck?  (>= tries stuck-reload-attempts)
          parts   (cond-> []
                    failed
                    (conj (str "live-reload FAILED for " (str/join ", " failed)
                               (cond
                                 hclean
                                 (str " — but THIS process was COMPARED to the"
                                      " store and holds every namespace at its"
                                      " current source, so this is the watcher"
                                      " stuck, not stale code")

                                 hcheck
                                 (str " — and this process IS behind on "
                                      (str/join ", " hdrift))

                                 :else
                                 (str " — whether THIS process still holds the"
                                      " previous code has not been measured"))
                               (when-let [rs (seq (keep reason failed))]
                                 (str " (" (str/join "; " rs) ")"))
                               (if stuck?
                                 (str "; this has failed " tries
                                      " polls running and a reload is"
                                      " deterministic over the same source, so"
                                      " it will not fix itself — restart the"
                                      " server")
                                 "; the next poll retries")))

                    (and (not failed) (seq hdrift))
                    (conj (str (count hdrift) " namespace(s) in THIS process are"
                               " behind the store and no reload failed, so"
                               " nothing said so: " (str/join ", " (take 5 hdrift))
                               " — restart the server"))

                    (and (not failed) (seq drift))
                    ;; This is the ONE staleness line here the reader can act on, and it
                    ;; was the only one with no verb in it — the two beside it,
                    ;; both about the serving process, end in an instruction.
                    ;; slopp-ui read that arrangement and concluded the image
                    ;; drift was the unfixable one.
                    (conj (str (count drift) " form(s) in the VERIFICATION image"
                               " differ from the store — no reload failed there,"
                               " so nothing said so: "
                               (str/join ", "
                                         (map #(str (:ns %) "/" (:form %)
                                                    " (" (name (:why %)) ")")
                                              (take 5 drift)))
                               "."
                               (when-let [c (one-cause drift)] (str " All " c "."))
                               " The `restart` tool builds a fresh verification"
                               " image and clears this"))

                    (and (not failed) (= :snapshot (:mode info))
                         (pos? (or deltas-since-boot 0)))
                    (conj (str deltas-since-boot " code delta(s) landed after this"
                               " server booted — snapshot mode serves launch-time"
                               " code, so serving-machinery changes are inert until"
                               " restart"))

                    (seq (:load-failures info))
                    (conj (str (count (:load-failures info))
                               " namespace(s) did NOT load at boot and this"
                               " process is running without them: "
                               (str/join ", " (map :ns (:load-failures info)))
                               ". The store stayed open on purpose so you can"
                               " FIX them — a broken namespace you can edit"
                               " beats a store you cannot reach. Restart once"
                               " they compile"))

                    on-branch?
                    (conj (str "host code tracks the MAIN journal — this branch"
                               " line's writes hot-reload the image only; verify"
                               " branch serving behavior there or in a fresh JVM")))
          note    (when (seq parts) (str/join " " parts))]
      (cond-> {:mode (:mode info) :booted-at (:booted-at info)}
        (:last-reload-at info) (assoc :last-reload-at (:last-reload-at info))
        failed                 (assoc :failed (vec failed))
        (seq why)              (assoc :failed-why why)
        (seq drift)            (assoc :oracle-drift (vec (take 20 drift))
                                      :oracle-drift-count (count drift))
        clean?                 (assoc :oracle-verified true)
        (seq hdrift)           (assoc :host-drift (vec hdrift))
        hclean                 (assoc :host-verified true)
        (:jar info)            (assoc :jar (:jar info))
        note                   (assoc :note note)))))

(defn ^:export host-warning
  "The host code-currency record for a VERDICT — nil unless the process
  producing that verdict is running code the store has moved past.

  `host-brief` answers the same question for ORIENTATION, and this is the same
  producer aimed at the other reader: session_brief is read once at the start
  of a session, while `done` / `full_check` / `test_run` are read after every
  unit of work and their entire output is a claim about the code. A host that
  failed to reload said nothing on any of them, which is how one investigation
  spent four ruled-out hypotheses inside `rt` before finding a stale process.

  What warrants doubting a verdict:
  - **measured drift** — forms this image does not hold as the store describes
    them, whether or not anything reported a failure. This is the direction
    that used to be entirely silent: a `def` holding a value captured from a
    form re-evaluated since, or a namespace written to the store and never
    loaded, threw nothing and so warned nobody;
  - a **failed reload that has NOT been checked against the image** (`drift`
    nil) — the honest cautious default when nothing looked;
  - a **:snapshot host with code deltas since boot** — it serves launch-time
    code by design, so every one of those deltas is inert in it.

  What does NOT: a failed reload on an image that COMPARES CLEAN. That was
  friction 20a — five namespaces reported stale, the image holding every one
  of them at the store's current source, and every verdict from that host
  marked suspect until a commit-point was routed through a fresh JVM to escape a
  problem that was not there. A watcher that cannot reload is worth saying out
  loud in the brief; it is not a reason to distrust the tests.

  A clean `:live` host is SILENT even with deltas outstanding: the watcher
  polls, so lagging by up to one interval is normal operation, and a warning
  on every done is a warning nobody reads."
  [info deltas-since-boot drift]
  (when info
    (let [drifted? (seq drift)
          hdrift   (:host-drift info)
          behind?  (seq hdrift)
          ;; it is the HOST's reload that failed, so only a HOST measurement
          ;; can retire the doubt — the oracle used to stand in for it
          failed?  (and (seq (:failed info)) (nil? hdrift))
          stale?   (and (= :snapshot (:mode info)) (pos? (or deltas-since-boot 0)))]
      (when (or drifted? behind? failed? stale?)
        (assoc (host-brief info deltas-since-boot false drift)
               :verdict-note
               ;; Every branch names `restart` — the TOOL, in its own spelling. These
               ;; said "restart the server", and slopp-ui read that as the MCP
               ;; process a human owns, which is the one act they believed an
               ;; agent cannot perform. They reported the staleness as a WALL and
               ;; sized a mechanism around it; two calls later `restart` cleared
               ;; it. The mechanism was right the whole time and the sentence
               ;; sent them somewhere else, so the remedy has to be spelled as
               ;; something the reader can call, not as an object they cannot
               ;; reach.
               ;; TWO images can be stale here and only one of them is the reader's.
               ;; `slopp.ops/restart!` calls `session/fresh-image!`: it replaces
               ;; the VERIFICATION image and does not touch the JVM serving MCP.
               ;; So the drift branch names the tool and the host branches say
               ;; plainly that it will not help — both halves matter, and each
               ;; was got wrong in turn. slopp-ui read the old "restart the
               ;; server" as the process a human owns, reported a two-call fix
               ;; as a wall, and started sizing a mechanism around it; the first
               ;; attempt at this comment then said `restart` everywhere, which
               ;; would have spent a call to hand back an equally suspect
               ;; verdict. A remedy the reader cannot run and a remedy that does
               ;; not work fail the same way.
               (cond
                 behind?
                 (str "this verdict was produced by a host that is behind the"
                      " store on " (str/join ", " (take 5 hdrift))
                      " — it runs the code that ORCHESTRATES verification, so"
                      " treat the verdict as suspect. This is the SERVING"
                      " process, not the verification image, so the `restart`"
                      " tool does not clear it — the MCP server has to come up"
                      " again")

                 drifted?
                 ;; Name the EDIT when the rows agree on one, because "why is
                 ;; this stale" is the question and the answer is usually a
                 ;; write the reader just made to a namespace they were not
                 ;; thinking about. One distinct :behind-edit is the common
                 ;; case: one write reloads a namespace and every value
                 ;; captured from it falls behind together.
                 (str "this verdict was produced against a verification image"
                      " holding " (count drift) " form(s) the store has moved"
                      " past — treat it as suspect."
                      (when-let [c (one-cause drift)] (str " All " c "."))
                      " The `restart` tool builds a"
                      " fresh verification image and clears this; the"
                      " :oracle-drift list names each form, what it is behind,"
                      " and the edit that re-evaluated it")

                 :else
                 (str "this verdict was produced by a host whose live-reload"
                      " failed, and whether it still runs the store's current"
                      " code has NOT been measured — treat it as suspect until"
                      " the host is current. That is the SERVING process, so the"
                      " `restart` tool does not settle it; the next poll retries"
                      " and the MCP server coming up again is the certain fix")))))))

(defn ^:export
  ^{:breaking-ok "the 2-arity walked the store's whole delta list twice to find the newest compile and count the client writes after it; the value no longer carries the list, so the two reads are handed in from slopp.store.db and this keeps the discrimination. Its one caller moved in the same write."}
  bundle-currency
  "What the compiled BUNDLE is, placed against `store` — `{:sha … :behind n}`
  — or nil when nothing has compiled one. `artifact` is the newest
  `:artifact-put` delta for the bundle's path (`slopp.store.db/last-artifact-put`,
  nil when there is none) and `after` the `{:id :op :ns}` rows the line gained
  after it (`slopp.store.db/ops-after`); both are the caller's to read,
  because this is the pure half and the journal is not in the value.

  **The third artifact that can be stale, and until now the only one with no
  report.** The host has `:app {:behind n}`, the jar has its currency, and
  the browser had nothing — so a store could take a green `done`, a green
  `commit_point`, a green `full_check` AND `:app {:behind 0}` while the browser
  was being served a bundle from before the work started. Reported by the app it
  happened to, after rewriting ten screens and finding the page unchanged.

  **Only CLIENT deltas count**, and that discrimination is what keeps the number
  worth reading. A `:jvm` namespace cannot stale a browser bundle; counting
  server-side edits would make the figure move constantly for reasons the
  browser does not care about, which trains a reader to ignore it — the failure
  mode of every number that is nearly always non-zero.

  **nil rather than 0 when no bundle exists.** Zero would claim one is present
  and current, which is the stronger form of the mistake: a store that has never
  compiled and a store that just compiled must not read the same.

  The platform consulted is each namespace's CURRENT one, so a namespace that
  became `:cljs` after the compile counts from the whole window rather than from
  its declaration. That over-counts in one narrow case and under-counts in
  none — the safe direction for a staleness number.

  **Counted by POSITION in the delta log, not by timestamp.** The first cut
  compared `:at` and was green alone and red in the suite: a compile and the
  write after it can land in the same millisecond, so the comparison dropped the
  write and the answer was a confident zero. The artifact IS a delta in the log,
  so `after` is what follows it and ties cannot exist."
  [store artifact after]
  (when artifact
    (let [client (fn [nsx] (contains? #{:cljc :cljs}
                                      (store/platform-for store nsx)))]
      {:sha    (:sha (:entry artifact))
       :behind (count (filter #(and (not (contains? fields/markers (:op %)))
                                    (client (:ns %)))
                              after))})))

(defn ^:export unread-declarations
  "Markers this store DECLARES that this slopp no longer READS — or nil, which
  is the ordinary case.

  `{:markers {:web/path {:count 10 :generated 9 :now :http/path}} :note \"…\"}`,
  and `:gone` in place of `:now` for a marker that was RETIRED rather than
  renamed.

  **The join, not the finding.** `unknown-marker` already reports the per-form
  half, at done grain, phrased as *nothing reads it* about ONE marker. Nobody
  adds ten of those up, and a store with ZERO readable endpoints is a different
  fact from ten forms carrying unread markers — different enough that a
  consuming store diagnosed the second while the first was what had happened.

  What it cost there: they restarted onto a new jar, the hub refused its own
  check-in with 404, five tests went red, and the brief's `:hub-note` pointed
  at beat-contract drift. It was not drift. Their routes declared `:web/path`,
  the jar reads `:http/path`, so not one endpoint registered and everything
  404d.

  **Two ledgers, because a marker can leave two ways.**
  [[slopp.index.crossings/retired-markers]] maps old→new; a RENAME can always
  say what the current spelling is. [[slopp.index.crossings/deleted-markers]]
  holds the ones that went outright, and reading only the first is what made a
  retirement report NOTHING — which is an all-clear. Found by a consuming store
  at the boot after `:rest/client` went: at one boot it carried a renamed
  marker (reported) and nine forms declaring a deleted one (silent), with a
  docstring still explaining why the dead flag was necessary.

  The deleted half is the worse one. A renamed marker usually breaks something
  visible; a deleted one quietly stops meaning anything, and every reader
  afterwards reasons from a declaration nothing reads.

  **The remedy has to be one the reader can RUN.** `rename_sweep` refuses
  `^:generated` forms, correctly — so on a store whose markers are written by a
  generator, naming the sweep is naming a refusal. Reported by a consumer for
  whom the suggestion was 0-for-9. And a DELETED marker gets no sweep at all,
  because there is nothing to rename it to.

  **Silent when clean**, which is what makes it worth printing: a line every
  brief carries is a line nobody reads."
  [store]
  (let [retired  crossings/retired-markers
        deleted  crossings/deleted-markers
        hits     (for [nsx (keys (:namespaces store))
                       e   (slopp.store/forms store nsx)
                       :when (:name e)
                       :let [m (slopp.store/form-name-meta e)]
                       k   (keys m)
                       :when (qualified-keyword? k)
                       :let [spelling (str (namespace k) "/" (name k))
                             now      (get retired spelling)
                             gone     (get deleted spelling)]
                       :when (or now gone)]
                   [k now (boolean (:generated m)) gone])
        by-mark  (reduce (fn [m [k now gen? gone]]
                           (-> m (assoc-in [k :now] now)
                               (assoc-in [k :gone] gone)
                               (update-in [k :count] (fnil inc 0))
                               (update-in [k :generated] (fnil + 0) (if gen? 1 0))))
                         {} hits)]
    (when (seq by-mark)
      (let [;; the CONSEQUENCE, per current spelling: how many forms carry the
            ;; live marker these retired ones map to. A deleted marker has no
            ;; successor, so there is nothing to count
            live-counts (into {}
                              (for [[_ {:keys [now]}] by-mark
                                    :when now]
                                [now (count (for [nsx (keys (:namespaces store))
                                                  e   (slopp.store/forms store nsx)
                                                  :when (and (:name e)
                                                             (contains? (slopp.store/form-name-meta e) now))]
                                              e))]))
            ;; report the WORST one in the sentence — the rest are in :markers.
            ;; One marker with ten forms is the fact; a list of five is a lint
            ;; report, and the reader is trying to find out why nothing serves.
            worst  (first (sort-by (comp - :count val) by-mark))
            mk     (key worst)
            n      (:count (val worst))
            gen    (:generated (val worst) 0)
            hand   (- n gen)
            now    (:now (val worst))
            gone   (:gone (val worst))
            live   (get live-counts now 0)
            sweep  (str "rename_sweep {from \"" mk "\" to \"" now "\"}")]
        {:markers by-mark
         :note
         (if gone
           ;; RETIRED OUTRIGHT. No sweep is named because there is nothing to
           ;; rename it to, and the ledger's own sentence says what to do
           ;; instead — which can be more than a rename could ever hand over,
           ;; since the reason it went is known.
           (str n " form(s) in this store declare " mk
                ", which this slopp RETIRED — it is not a rename, so there is no"
                " current spelling and no sweep to run. " gone
                ". Until they go, every reader of those forms is reasoning from"
                " a declaration nothing reads, which is quieter than a rename"
                " and lasts longer.")

           (str n " form(s) in this store declare " mk
                ", which this slopp does not read — "
                (if (zero? live)
                  (str "and NONE of them are readable, so whatever "
                       mk " configures, this store is not doing it. ")
                  (str "while " live " form(s) use the live spelling, so this"
                       " store is doing it partly. "))
                "The current spelling is " now
                ". A rename moved it and these were left behind; "
                (cond
                  (zero? gen) (str sweep " moves them.")

                  (zero? hand)
                  ;; the sweep CALL is deliberately not printed here, not even
                  ;; to warn against. A reader skimming for something to run
                  ;; finds the command and runs it; the negation around it is
                  ;; what a skim drops.
                  (str "Every one of them is ^:generated, so no sweep can move"
                       " them — a sweep refuses a generated form. Re-run"
                       " whatever writes them.")

                  :else
                  (str sweep " moves the " hand " hand-written one(s); the other "
                       gen " are ^:generated and a sweep refuses those, so"
                       " re-run whatever writes them. Both halves, or the"
                       " sweep's own count reads as finished."))
                " Every input to this was here at boot — the store says what it"
                " declares and this slopp says what it reads, and nothing"
                " joined the two."))}))))

(defn ^:export
  
  jar-warning
  "A sentence when the jar is behind the store, else nil — given `currency`,
  the `{:head :behind}` map `slopp.ops/jar-currency` derives from the
  journal.

  The reader-facing half of that derivation, phrased for somebody deciding
  whether to act. Nil unless there is genuinely something to doubt — a
  warning that fires every time is one a reader learns to skip, and this one
  has to be read on the rare occasion it appears.

  Nil covers three different silences on purpose, and none of them is a claim
  that the artifact is current:

  - no currency at all — a checkout, a bare `-M` run, no artifact to be stale
  - a FOREIGN head, so the map carries no `:behind`: slopp's jar serves
    projects that are not slopp, and counting this log's deltas after another
    store's head would measure how fast the reader has been writing and report
    it as the tool's age
  - a jar built from the head, which is the ordinary good case

  Announcement, artifact and process are three states and nothing joined
  them: a commit-point said the store was green while the jar carrying that store
  to everyone else had never been rebuilt. It happened twice in one night and
  a CONSUMER caught it both times, by reading the artifact rather than by
  believing the announcement."
  [currency]
  (when (pos? (:behind currency 0))
    (str "the jar this process is running was built from " (:head currency)
         ", which is " (:behind currency) " code delta"
         (when (not= 1 (:behind currency)) "s")
         " behind this store — anyone reading the ARTIFACT rather than the"
         " store will not see this work until it is rebuilt")))

(defn ^:export handoff-text
  "The handoff, rendered for INJECTION: `r` (an `ops/report` map) as plain
  text in the order a handoff ask reads — the asks oldest first, each with
  its :turn id and the forms it added/changed/deleted; commit-points; the
  changes rolled up by namespace with one recorded ask and a delta each;
  the suite verdict with the command that re-runs it — fitted to `budget`
  by dropping WHOLE rows (the rollup's tail first, then the oldest asks),
  never cutting mid-row. Measured (s18): a pr-str snip put teaching prose
  and twelve alphabetical per-form rows ahead of :by-ask and cut it off in
  every s17 handoff cell, and the \"(deeper: …)\" tail taught the 5+7-call
  drill-down that followed. The closing line says this IS the record."
  [r budget]
  (let [asks     (vec (reverse (:by-ask r)))
        forms    (fn [k a] (when (seq (get a k))
                             (str (name k) ": " (str/join " " (take 8 (get a k)))
                                  (when (< 8 (count (get a k))) " …"))))
        ask-line (fn [i a]
                   (str (inc i) ". [turn " (:turn a) "] \"" (snip (:ask a) 160) "\""
                        (when-let [ps (seq (keep #(forms % a) [:added :changed :deleted :renamed]))]
                          (str " — " (str/join "; " ps)))))
        ms-lines (mapv #(str "  " (:commit %) " " (snip (:description %) 90) " @" (:at %))
                       (:commit-points r))
        rollup   (->> (:changes r)
                      (group-by :ns)
                      (sort-by (comp str key))
                      (mapv (fn [[nsx rows]]
                              (str "  " nsx ": " (count rows) " form(s) "
                                   (pr-str (vec (distinct (mapcat :ops rows))))
                                   (when-let [a (first (mapcat :asks rows))]
                                     (str " — \"" (snip a 80) "\""))
                                   " " (pr-str (vec (take 3 (distinct (mapcat :deltas rows)))))))))
        suite    (str "suite: " (name (or (get-in r [:suite :status]) :unknown))
                      (when-let [as-of (get-in r [:suite :as-of])] (str " as of " as-of))
                      " — run it: slopp --call test_run '{\"external\":true}';"
                      " commit-points: slopp --call query_commits")
        head     (str "--- the composed handoff — the store's OWN records; the ids ARE the"
                      " citations (:turn per ask, :deltas per change, :commit per commit-point) ---")
        close    (str "This IS the record — quote the :turn and :deltas ids as your citations;"
                      " the suite command above is the one to hand over.")
        render   (fn [n-asks n-roll]
                   (let [shown (vec (take-last n-asks asks))
                         from  (- (count asks) (count shown))]
                     (str/join "\n"
                               (concat [head (str "asks, oldest first (" (count asks) "):")]
                                       (when (pos? from)
                                         [(str "  … " from " earlier ask(s) — report {} lists them")])
                                       (map-indexed (fn [i a] (ask-line (+ from i) a)) shown)
                                       (when (seq ms-lines) (cons "commit-points:" ms-lines))
                                       (when (pos? n-roll) (cons "changes by namespace:" (take n-roll rollup)))
                                       [suite close]))))]
    (loop [n-asks (count asks), n-roll (count rollup)]
      (let [t (render n-asks n-roll)]
        (cond
          (<= (count t) budget) t
          (pos? n-roll)         (recur n-asks (dec n-roll))
          (< 1 n-asks)          (recur (dec n-asks) 0)
          :else                 (subs t 0 budget))))))

(def ^:private stop-words
  "Function words an ask is full of that name nothing — shared by the two
  seeders so they agree on what a word is."
  #{"with" "that" "this" "must" "have" "from" "when" "will" "your"
    "tell" "every" "should" "their" "them" "than" "then" "they"
    "what" "where" "which" "been" "back" "also" "only" "into"
    "make" "return" "returns" "call" "calls" "form" "forms"
    "the" "and" "for" "its" "not" "but" "can" "are" "was" "one"
    "all" "any" "each" "once" "several" "accept" "does" "use"
    "everywhere" "anywhere" "class" "creating" "accepted" "work"})

(defn- ask-seeds
  "The forms an `ask` names, scored. Two signals:

  - WORDS: each word of three letters or more in the ask (split on spaces,
    hyphens, underscores and dots, minus function words) matched against
    the segments of every form's name, scoring the RARITY of each match —
    1/√(forms whose name carries the word) — so `done`, in three hundred
    names, moves a form less than `slice`, in nine.
  - PHRASES: two consecutive ask words that are two consecutive segments of
    a name — `add form` is `add-form!`, `edit_add_form` is too — score a
    full point each, over and above the words. This is how a tool name in
    the ask reaches the form behind it.

  A test form scores HALF: its long descriptive name matches more words
  than the form it tests, and the form is what the ask is about. Returns
  `[[qsym score] …]`, best first, for the forms that matched at all.
  Names, deliberately, not docstrings: a docstring mentions its neighbours,
  and seeding on those turns every ask into the whole module. The
  NAMESPACES an ask names are `ask-namespaces`' answer, beside this one."
  [st ask]
  (let [raw    (into [] (comp (map str/lower-case) (filter #(<= 3 (count %))))
                     (re-seq #"[A-Za-z][A-Za-z0-9]*" (str ask)))
        words  (into #{} (remove stop-words) raw)
        grams  (into #{} (map vector raw (rest raw)))
        named  (for [nsx (keys (:namespaces st))
                     e   (store/forms st nsx)
                     :when (and (:name e) (not= (:name e) nsx))
                     :let [segs (vec (remove str/blank?
                                             (str/split (str/lower-case (str (:name e)))
                                                        #"[-_.!?]")))]]
                 [(symbol (str nsx) (str (:name e))) segs])
        df     (reduce (fn [m [_ segs]]
                         (reduce (fn [m w] (if (words w) (update m w (fnil inc 0)) m))
                                 m (distinct segs)))
                       {} named)
        rarity (fn [w] (/ 1.0 (Math/sqrt (double (get df w 1)))))
        test?  (fn [q] (str/ends-with? (namespace q) "-test"))]
    (when (seq raw)
      (->> (for [[q segs] named
                 :let [hit     (filter words (distinct segs))
                       phrases (count (filter grams (map vector segs (rest segs))))
                       s       (+ (reduce + 0.0 (map rarity hit)) phrases)]
                 :when (pos? s)]
             [q (* (if (test? q) 0.5 1.0) s)])
           (sort-by (fn [[q s]] [(- s) (str q)]))
           vec))))

(defn ^:export ask-namespaces
  "The namespaces an `ask` names by WORD, in order of mention: a word of four
  letters or more that IS a namespace's last segment, allowing for a plural
  or an -ing/-ed ending — `invoices` names `logi.invoice`, `quoting` names
  `logi.quoting`, `carriers` names `logi.carrier`. Equality on a handful of
  variants, never a prefix: `bill` must not name `logi.billable`. Test
  namespaces are never named this way.

  eval22 step 2: \"everywhere carriers are accepted (quoting, booking,
  billing, invoices)\" seeded nothing, because seeds came from form names
  alone — and the agent then read each of the four by hand."
  [st ask]
  (let [variants (fn [w]
                   (let [n (count w)]
                     (cond-> #{w}
                       (str/ends-with? w "ies") (conj (str (subs w 0 (- n 3)) "y"))
                       (str/ends-with? w "es")  (conj (subs w 0 (- n 2)))
                       (str/ends-with? w "s")   (conj (subs w 0 (- n 1)))
                       (str/ends-with? w "ing") (conj (subs w 0 (- n 3))
                                                      (str (subs w 0 (- n 3)) "e"))
                       (str/ends-with? w "ed")  (conj (subs w 0 (- n 2))
                                                      (subs w 0 (- n 1))))))
        words    (->> (re-seq #"[A-Za-z][A-Za-z0-9]*" (str ask))
                      (map str/lower-case)
                      (filter #(<= 4 (count %)))
                      (remove stop-words)
                      distinct)
        nses     (->> (keys (:namespaces st))
                      (remove #(str/ends-with? (str %) "-test"))
                      (sort-by str))
        last-seg (fn [nsx] (last (str/split (str nsx) #"\.")))]
    (->> (for [w   words
               :let [vs (variants w)]
               nsx nses
               :when (contains? vs (last-seg nsx))]
           nsx)
         distinct
         vec)))

(defn ^:export orient-map
  "THE orientation call for an ask: the forms that matter for it, ranked,
  fitted to a token budget, each row a CARD (`form-card`: sig, doc line, the
  recorded why, the test warranty) plus `:via` — the edge that made it
  relevant: `seed`, `called by X`, `calls X`, `covered by T`, `covers F`.

  Seeds are the forms the ask names (`ask-seeds`) and/or `seeds` given as
  \"ns/name\" strings. From them a personalized PageRank walks the graph the
  store already holds — every reference in `refs` (calls, carriers,
  declarations) plus the trace map's coverage edges (a test that exercised
  a form is an edge to it), both directions — so a callee two hops down
  outranks an unrelated hub, and the test that covers what you are about
  to touch arrives beside it. With nothing to seed on, the walk is plain
  PageRank: what the graph turns on, which is the right answer to a fresh
  context with no ask yet.

  The budget is honest: rows are taken best-first while their estimated
  tokens fit `tokens` (default 1500, ~4 chars per token), and `:more` says
  how many ranked forms were cut. Returns
  `{:seeds [qsym …] :rows [{:form :via :sig :doc :why :warranty …} …]
    :tokens n :budget n [:more k]}`.

  Why a walk and not a text search: measured on this store, 48 tool calls
  per ask, most of them one more `query_slice` with the next name — the
  agent doing by hand what a ranked map over the graph does in one call.
  Pure over the value and the session's trace map; nothing here queries."
  [session & {:keys [ask seeds tokens] :or {tokens 1500}}]
  (let [st     (:store @session)
        tmap   (or (:test-map @session) {})
        nodes  (into {}
                     (for [nsx (keys (:namespaces st))
                           e   (store/forms st nsx)
                           :when (and (:name e) (not= (:name e) nsx))]
                       [(symbol (str nsx) (str (:name e))) [nsx e]]))
        node?  #(contains? nodes %)
        ;; edges: [from to kind], kind names the relation from `from`'s side
        edges  (concat
                (for [r (refs/refs st)
                      :when (and (:from-var r) (symbol? (:from-ns r)))
                      :let [from (symbol (str (:from-ns r)) (str (:from-var r)))
                            to   (symbol (str (:to-ns r)) (str (:to-name r)))]
                      :when (and (node? from) (node? to) (not= from to))]
                  [from to (if (= :covers (:marker r)) :covers :calls)])
                (for [[t fs] tmap, f fs
                      :when (and (node? t) (node? f) (not= t f))]
                  [t f :covers]))
        ;; undirected adjacency for the walk; the directed edge kept for :via
        adj    (reduce (fn [m [a b kind]]
                         (-> m
                             (update a (fnil conj []) [b kind :out])
                             (update b (fnil conj []) [a kind :in])))
                       {} edges)
        ;; a call edge carries the structure; a coverage edge carries a
        ;; test, and a test touches many forms — weighted equally the walk
        ;; drifts into the test suite and out of the code the ask is about
        weight (fn [[_ kind _]] (if (= :calls kind) 1.0 0.3))
        named  (for [s seeds
                     :let [q (symbol (str s))]
                     :when (node? q)]
                 [q 1])
        found  (take 5 (ask-seeds st ask))
        ;; the namespaces the ask names by WORD: their forms enter the walk
        ;; at a low weight, so "everywhere carriers are accepted (quoting,
        ;; booking, billing, invoices)" maps those four without a read
        ;; apiece (eval22 step 2). A form the ask names by NAME outranks them.
        nss    (if (str/blank? (str ask)) [] (ask-namespaces st ask))
        ns-forms (for [nsx nss
                       e   (store/forms st nsx)
                       :when (and (:name e) (not= (:name e) nsx))]
                   [(symbol (str nsx) (str (:name e))) 0.4])
        seedv  (into {} (concat ns-forms found named))
        seed-order (vec (distinct (concat (map first named) (map first found))))
        ;; personalized PageRank, 20 iterations at d = 0.85; the teleport
        ;; vector is the seeds (uniform when there are none)
        all    (vec (keys nodes))
        tele   (if (seq seedv)
                 (let [z (reduce + (vals seedv))]
                   (into {} (map (fn [[q w]] [q (/ w z)])) seedv))
                 (let [n (count all)] (into {} (map (fn [q] [q (/ 1.0 n)])) all)))
        d      0.85
        score  (loop [p tele, i 0]
                 (if (= i 20)
                   p
                   (let [spread (reduce (fn [m [q pq]]
                                          (let [ns* (get adj q)]
                                            (if (seq ns*)
                                              (let [total (reduce + 0.0 (map weight ns*))]
                                                (reduce (fn [m [nb :as edge]]
                                                          (update m nb (fnil + 0)
                                                                  (* d pq (/ (weight edge) total))))
                                                        m ns*))
                                              ;; a dead end hands its mass back to the seeds
                                              (reduce (fn [m [t tw]] (update m t (fnil + 0) (* d pq tw)))
                                                      m tele))))
                                        {} p)
                         p'     (reduce (fn [m [t tw]] (update m t (fnil + 0) (* (- 1 d) tw)))
                                        spread tele)]
                     (recur p' (inc i)))))
        ;; the seeds lead — the ask named them — then everything else by the
        ;; walk, and a form the walk never reached still ranks (last) rather
        ;; than vanishing: absence would read as "not in the store"
        ranked (concat seed-order
                       (->> all
                            (remove (set seed-order))
                            ;; with seeds, a form the walk never reached is
                            ;; not an answer to the ask and stays out; with
                            ;; none, everything ranks — the unreached last
                            (filter (if (seq seedv) #(pos? (get score % 0)) (constantly true)))
                            (sort-by (fn [q] [(- (get score q 0)) (str q)]))))
        rank-of (into {} (map-indexed (fn [i q] [q i])) ranked)
        seed?  (set seed-order)
        via    (fn [q]
                 (if (seed? q)
                   "seed"
                   ;; the best-ranked neighbour is the edge that pulled this
                   ;; row in; a coverage edge rides along when there is one,
                   ;; because it answers the next question ("is it tested?")
                   (let [nbs   (sort-by (fn [[nb _ _]] (get rank-of nb Long/MAX_VALUE)) (get adj q))
                         phrase (fn [[nb kind dir]]
                                  (case [kind dir]
                                    [:calls :in]   (str "called by " nb)
                                    [:calls :out]  (str "calls " nb)
                                    [:covers :in]  (str "covered by " nb)
                                    [:covers :out] (str "covers " nb)))
                         best  (first nbs)
                         cover (first (filter (fn [[_ kind dir]] (and (= :covers kind) (= :in dir))) nbs))]
                     (str/join "; " (distinct (keep phrase (remove nil? [best cover])))))))
        row    (fn [q]
                 (let [[nsx e] (get nodes q)]
                   (assoc (or (form-card session nsx (:name e)) {:form q})
                          :via (via q))))
        est    (fn [r] (quot (+ 3 (count (pr-str r))) 4))
        fitted (loop [qs ranked, acc [], used 0]
                 (if (empty? qs)
                   [acc used 0]
                   (let [q (first qs)
                         r (row q)
                         t (est r)]
                     (if (<= (+ used t) tokens)
                       ;; a SEED is the form the ask names — the one about to
                       ;; be edited — so its source rides the row when the
                       ;; budget allows; a map that names it without its
                       ;; text is followed by a read (eval10: eleven of them
                       ;; before the first write). Too tight: the card stays.
                       (let [[r t] (or (when (seed? q)
                                         (let [[_ e] (get nodes q)
                                               s     (some-> (:node e) n/string)
                                               rs    (when s (assoc r :source s))
                                               ts    (when rs (est rs))]
                                           (when (and rs (<= (+ used ts) tokens))
                                             [rs ts])))
                                       [r t])]
                         (recur (rest qs) (conj acc r) (+ used t)))
                       [acc used (count qs)]))))
        [rows used more] fitted
        ;; eval11's precision miner: a seed's TEST fell below the budget
        ;; cut — one caller among many — while being the next thing the ask
        ;; touches. With trace evidence the coverage edge carries it;
        ;; statically (a fresh import has no trace map) the deftest that
        ;; references a seed rides GUARANTEED, appended past the budget and
        ;; marked for what it is.
        deftest? (fn [q]
                   (when-let [[_ e] (get nodes q)]
                     (let [sx (try (n/sexpr (:node e)) (catch Exception _ nil))]
                       (and (seq? sx)
                            (contains? #{'deftest 'clojure.test/deftest} (first sx))))))
        seed-test (into {}
                        (keep (fn [q]
                                (when-let [t (first (for [r (refs/refs st)
                                                          :when (:from-var r)
                                                          :let [from (symbol (str (:from-ns r)) (str (:from-var r)))]
                                                          :when (and (= q (symbol (str (:to-ns r)) (str (:to-name r))))
                                                                     (not= from q)
                                                                     (node? from)
                                                                     (deftest? from))]
                                                      from))]
                                  [t q])))
                        (take 1 seed-order))
        have     (into #{} (map :form) rows)
        rows     (let [rows    (mapv (fn [r]
                                       (if-let [q (get seed-test (:form r))]
                                         (assoc r :via (str "tests " q))
                                         r))
                                     rows)
                        missing (remove (comp have key) seed-test)]
                    ;; DISPLACE, never append: the s10 grid measured appended
                    ;; rows reshaping step-1 orientation (8->16 turns) — the
                    ;; guarantee frees tail cards until the test row fits the
                    ;; budget, and seeds are never popped
                    (reduce (fn [rows [t q]]
                              (let [tr (assoc (row t) :via (str "tests " q))
                                    tc (est tr)]
                                (loop [rows rows freed 0]
                                  (if (or (<= (+ used tc) (+ tokens freed))
                                          (<= (count rows) (count seed-order)))
                                    (conj rows tr)
                                    (recur (pop rows) (+ freed (est (peek rows))))))))
                            rows missing))]
    (cond-> {:seeds seed-order :rows rows :tokens used :budget tokens}
      (seq nss)   (assoc :ns-seeds (vec nss))
      (pos? more) (assoc :more more))))

^:reads (defn ^:export form-version
  "`[form-id hash-of-text]` for `ns-sym/nm` on the session's store value —
  the ONE version fact: what the form ledger keys on, and what a card or
  a row shows as `:v`. The form id is stable across edits, so the text hash
  is the version; nil when there is no such form. Public so every read that
  shows a form can stamp it, and \"which version do I hold\" and \"is it
  already sent\" are the same fact rather than two that drift."
  [session ns-sym nm]
  (when-let [e (store/form-named (:store @session) ns-sym nm)]
    [(:id e) (hash (n/string (:node e)))]))

(defn ^:export aliases-line
  "The project's alias table as ONE line for the first bundle: `aliases
  (write lib/fn fully qualified if unsure — stored as these): fuel=logi.fuel
  str=clojure.string …`, sorted by alias, at most `cap` entries. The other
  reason an agent read a namespace was its ns form; this is that answer,
  once per session."
  ([aliases] (aliases-line aliases 80))
  ([aliases cap]
   (when (seq aliases)
     (str "aliases (write lib/fn fully qualified if unsure — stored as these): "
          (str/join " " (take cap (sort (map (fn [[lib a]] (str a "=" lib)) aliases))))))))

(def ^:export whole-ns-caps
  "How much of the NAMED namespaces the first bundle sends whole: a namespace
  up to `:max-chars` of source, the smallest first, at most `:max-nses`,
  `:max-total` chars between them. Fixed caps rather than a share of the
  budget, so a project of two hundred namespaces gets the same ceiling as
  one of ten. Six, because the step-2 ask names seven and all but the big
  one fit; the earlier four, in mention order, dropped exactly the three
  the model then read by hand."
  {:max-chars 2000 :max-nses 6 :max-total 6000})

(defn ^:export bundle
  "The ASK BUNDLE with its ledger half: `{:text \"…\" :sent [[form-id hash] …]}`.
  `:text` is `orient-map` rendered as ONE plain-text block sized for prompt
  injection — a header that orients and names the reads that answer a
  task (`query_flow` for how forms connect, `targets` for bodies), the
  ranked rows as one-line cards with their `:via` and `:v`, full sources
  for at most `sources` seed rows, and the SMALLEST namespaces the ask
  names by word sent WHOLE (`:whole-ns?`; caps in `whole-ns-caps`) —
  smallest first, never mention order, which dropped exactly the ones the
  model then read by hand. The cap is the diet: measured, the sources
  section was 85% of the bundle, and the injected text rides EVERY later
  request of the session as context rent — rows past the cap stay cards,
  in rank order. The whole section was retired one afternoon on principle
  and restored the same evening on measurement: cards-then-fetch cost 24
  and 29 turns where whole cost 19. Forms sent whole are dropped from the
  cards. `:sent` is the form versions whose text was emitted, for the
  caller to stash toward the session's form ledger — the bundle, the write
  results and the reads share one ledger. A blank ask is the plain ranking,
  never an error — same stance as the search endpoint."
  [session ask & {:keys [tokens sources cli? versions? whole-ns?]
                  :or {tokens 1100 sources 2 versions? true whole-ns? true}}]
  (let [st      (:store @session)
        m       (orient-map session :ask (str ask) :tokens tokens)
        {:keys [max-chars max-nses max-total]} whole-ns-caps
        wholes  (when whole-ns?
                  (->> (:ns-seeds m)
                       (map (fn [nsx] [nsx (store.render/render-ns st nsx)]))
                       (filter (fn [[_ src]] (and (pos? (count src)) (<= (count src) max-chars))))
                       (sort-by (fn [[_ src]] (count src)))
                       (reduce (fn [{:keys [acc total]} [nsx src]]
                                 (if (and (< (count acc) max-nses) (<= (+ total (count src)) max-total))
                                   {:acc (conj acc [nsx src]) :total (+ total (count src))}
                                   {:acc acc :total total}))
                               {:acc [] :total 0})
                       :acc))
        whole?  (into #{} (map first) wholes)
        in-whole? (fn [q] (whole? (symbol (namespace q))))
        withs   (vec (take sources (filter #(and (:source %) (not (in-whole? (:form %)))) (:rows m))))
        keep?   (into #{} (map :form) withs)
        cards   (into [] (comp (remove (comp keep? :form))
                               (remove (comp in-whole? :form))
                               (map #(dissoc % :source)))
                      (:rows m))
        v-of    (fn [q] (form-version session (symbol (namespace q)) (symbol (name q))))
        line    (fn [{:keys [form sig doc via]}]
                  (str "  " form
                       (when sig (str " " (pr-str sig)))
                       (when (seq (str doc)) (str " — " (snip doc 70)))
                       "  [" via "]"
                       (when-let [v (and versions? (v-of form))] (str " :v " (pr-str v)))))
        sent    (vec (concat
                      (for [r withs
                            :let [q (:form r)
                                  e (store/form-named st (symbol (namespace q))
                                                      (symbol (name q)))]
                            :when e]
                        [(:id e) (hash (:source r))])
                      (for [[nsx _] wholes
                            e (store/forms st nsx)]
                        [(:id e) (hash (n/string (:node e)))])))]
    {:sent sent
     :text (str "[slopp] " (count (:namespaces st)) (if cli? " namespaces; live store — drive it" " namespaces; live store — work")
                (if cli?
                  (str " with the slopp CLI — fast, routed to this running server:"
                       " slopp <op> '{…json…}' · slopp add <ns> <<'EOF' …forms… EOF ·"
                       " slopp replace <ns/name> <<'EOF' · slopp change --prompt '…'"
                       " <<'EOF' with ;;;tests <ns> / ;;;impl <ns> sections ·"
                       " slopp done '{}' when a unit is finished. There are NO MCP tools"
                       " for this store — the shell IS the interface, so never"
                       " search for tools. Every write"
                       " verifies itself and reports.")
                  " through the slopp tools (the store is the source, not the files).")
                " The forms below are ranked for THIS ask; their sources, when"
                " present, are current — no need to re-read them. How they connect:"
                " query_flow {from to} or {on reach} (bodies on the way); the bodies"
                " you will edit: query_source {targets [\"ns/name\" …]}.\n"
                (when (seq (:seeds m))
                  (str "seeds: " (str/join " " (:seeds m)) "\n"))
                (when (seq cards)
                  (str (str/join "\n" (map line cards)) "\n"))
                (when (seq withs)
                  (str "--- the forms the ask names, in full ---\n"
                       (str/join "\n\n" (map #(str ";; " (:form %) " [" (:via %) "]\n" (:source %))
                                             withs))
                       "\n"))
                (when (seq wholes)
                  (str "--- the namespaces the ask names, whole — current, no need to read them ---\n"
                       (str/join "\n\n" (map (fn [[nsx src]] (str ";; " nsx "\n" src)) wholes)))))}))

(defn ^:export read-hint
  "The one line a namespace read ends with, whichever shape it took: `lead`
  says what this answer is (\"whole, small.\" or \"cards, not bodies.\"), the
  tail names the questions a namespace read was standing in for — flow,
  the bodies to edit, blast radius. One def, so the two shapes cannot drift
  into different advice."
  [lead]
  (str lead " How these connect: query_flow {from to} (the call"
       " path, bodies on it) or {on reach} (callers and callees). The bodies"
       " you will edit: query_source {targets [\"ns/name\" …]}. Blast radius:"
       " query_depends {on \"ns/name\"}."))

(defn ^:export ns-cards
  "A namespace as its INTERFACE — the answer to `query_source {ns}` past the
  whole-read size (a small namespace is one read, whole; cards by default
  measured as cards-then-fetch, one more call per namespace):
  `{:ns :whole false :forms [{:form :v :sig :doc [:effectful] [:test?]} …]
  [:example src] :hint}`. Per form a slim card — name, signature, first doc
  sentence, and `:v` (`form-version`) so the agent can hold and cite a
  version — never the body; ONE example whole, the namespace's first
  `deftest`, because \"what does a test here look like\" was a real reason
  to read a namespace; and a hint naming the questions a namespace read was
  standing in for.

  Why: six step-2 eval sessions made 41 whole-namespace reads (median 867
  chars) and zero graph questions — the questions behind them were flows
  and a style question, and the namespace was the only shape the agent had
  a habit for. Cheap enough that nothing corrected it. The bodies an ask is
  ABOUT still arrive whole where they are asked for: the bundle's seeds, a
  `query_flow` path, a `targets` read."
  [session ns-sym]
  (let [st       (:store @session)
        forms    (filter #(and (:name %) (not= (:name %) ns-sym)) (store/forms st ns-sym))
        deftest? (fn [e] (let [s (try (n/sexpr (:node e)) (catch Exception _ nil))]
                           (and (seq? s) (contains? #{'deftest 'clojure.test/deftest} (first s)))))
        card     (fn [e]
                   (let [c (form-card session ns-sym (:name e))]
                     (cond-> {:form (:form c) :v (form-version session ns-sym (:name e))}
                       (:sig c)       (assoc :sig (:sig c))
                       (:doc c)       (assoc :doc (:doc c))
                       (:effectful c) (assoc :effectful true)
                       (deftest? e)   (assoc :test? true))))
        example  (some #(when (deftest? %) (n/string (:node %))) forms)]
    (cond-> {:ns ns-sym
             :whole false
             :forms (mapv card forms)
             :hint (read-hint "cards, not bodies.")}
      example (assoc :example example))))
