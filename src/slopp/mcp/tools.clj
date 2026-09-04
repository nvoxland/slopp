(ns slopp.mcp.tools
  "The tool descriptors — what an agent sees before it calls anything.

  This is the highest-leverage prose in the system and the easiest to
  under-weight: most agents never read a docstring, a doc page or a skill.
  They read this. A description that omits an argument means nobody finds it;
  one that names a tool that no longer exists costs a failed call to discover.

  The schemas are ENFORCED, not advisory. `call-tool!` refuses any argument a
  schema does not declare, which makes an accurate schema a precondition
  rather than a courtesy — the strictness was added after four rounds of
  silently-ignored arguments, and it immediately surfaced one real gap where
  a tool read a key it had never advertised.

  Split per group so the registry stays editable without touching a monolith,
  and `read-only-tools` rides alongside because the same fact — this never
  modifies the store — decides both the MCP `readOnlyHint` and whether a
  client has to prompt." (:require [clojure.string :as str]))

(def orientation-tools
  "Read/orient tool descriptors: project, search, source, dossiers, the oracle. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "query_project"
    :description "THE orientation call: every namespace's outline (names, arities, !-status, test-ness) in one response. Call ONCE; detail=true adds doc lines; pass since=<your last delta id> on a re-check — unchanged structure returns a one-liner."
    :inputSchema {:type "object" :properties {:since {:type "string"}
                                              :detail {:type "boolean"}}}}
   {:name "query_search"
    :description "Regex search across all store source; ONE hit per matching form, and the hit is the form's card: {:ns :form :line :sig :doc [:matches n]} — the signature and doc line say what a hit IS without a read. Search before reading."
    :inputSchema {:type "object"
                  :properties {:pattern {:type "string"}
                               :limit {:type "integer"}}
                  :required ["pattern"]}}
   {:name "query_flow"
    :description "HOW forms connect, with the bodies on the way — the question a whole-namespace read stands in for. {from \"ns/a\" to \"ns/b\"}: the call path between two forms, each form on it whole and versioned (:v), the forms it calls off the path as cards, :edges between steps; an unreachable pair says :unreachable. {on \"ns/x\" reach 2}: callers and callees around one form, bodies one hop out, cards beyond. Across namespaces. What it sends enters the ledger — a later read of the same form is a reference."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"}
                               :to {:type "string"}
                               :on {:type "string"}
                               :reach {:type "integer"}}}}
   {:name "query_source"
    :description "{ns} alone: a small namespace whole (one read), a big one as CARDS — per form its name, sig, first doc sentence and :v (version stamp), plus one example test whole; either way a hint names the better questions. targets [\"ns/name\" …] reads the BODIES of named forms in ONE call — the normal read before an edit; each row carries :v, and a form you already hold at that version comes back as :source-already-sent. How forms connect is query_flow, not a namespace."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :full {:type "boolean"}
                                              :resend {:type "boolean"
                                                       :description "bypass the already-sent ledger for THIS call — for when compaction replaced your context with a summary and you no longer hold what the ledger says you do"}
                               :targets {:type "array"
                                         :description (str "each target is \"ns/name\" (or plain"
                                                           " \"ns\" for its outline), or the"
                                                           " equivalent {ns, name} object")
                                         :items {:oneOf [{:type "string"}
                                                         {:type "object"
                                                          :properties {:ns {:type "string"}
                                                                       :name {:type "string"}}
                                                          :required ["ns"]}]}}}}}
   {:name "explore" :image-free false :read-only true
    :description "THE question verb — several questions, ONE call: ops = [{op …args} …] with any read op (check, query_source, query_search, query_depends, query_history, report, orient, …) — answers [{:op :result} …], each entry through the same already-sent/ledger door as the single call. At most 6 entries; a write op is refused before anything runs. Models do not emit parallel tool calls, so the batch lives inside the call — bring every independent question you have."
    :inputSchema {:type "object"
                  :properties {:ops {:type "array" :items {:type "object"}}}
                  :required ["ops"]}}
   {:name "check" :image-free false :read-only true
    :description "Run ASSERTION code against the live image with clojure.test's reporting captured — NOTHING is written: {:value … :pass n :fail n :assertions [{:type :expected :actual} …]}. The find-something-out red belongs here, as an ANSWER — no landed test to clean up, no temptation to fix working code against a wrong assertion. Land it as a real test (change {tests […]}) only once it says what you mean. Plain expressions work too; (clojure.test/is …) forms are what get counted."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"}}
                  :required ["code"]}}
   {:name "query_brief"
    :description "THE form dossier, one call: source + effect flags + cross-ns callers + the tests covering it + the recorded WHY (last prompt/intent). Prefer this over separate source/references/lineage reads when you're about to change a form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "session_brief"
    :description "START HERE, once: namespaces with form names, recent commit-points, git alignment, and the working loop — orientation in one small call. Depth on demand: query_source {ns}/query_brief/report."
    :inputSchema {:type "object" :properties {}}}
   {:name "orient" :image-free true :read-only true
    :description "THE map for an ask, in ONE budgeted call: the forms that matter for it, ranked by a walk over the reference graph and the tests that cover them, fitted to `tokens` (default 1500). Each row is a card (sig, doc line, recorded why, test warranty) plus :via — the edge that made it relevant (seed / called by X / calls X / covered by T). Give it the ask verbatim (`ask`) and/or the forms you already know (`seeds` [\"ns/name\"]). The forms the ask NAMES (the seeds) carry their :source when the budget allows, so the write that follows needs no read in between; the neighbourhood is cards. :more counts what the budget cut."
    :inputSchema {:type "object"
                  :properties {:ask {:type "string"}
                               :seeds {:type "array" :items {:type "string"}}
                               :tokens {:type "integer"}}}}
   {:name "query_slice"
    :description "THE focused read: full source of ONE entry-point form + interface CARDS (sig, doc line, test warranty) for everything it reaches — same-ns private helpers and cross-ns callees, breadth-first to depth (default 2, capped). match=<text> WINDOWS the target to `window` lines (default 25) around the first matching line — use it on giant forms. verbose=true adds each card's recorded why (the last ask that touched it); query_brief carries it always. Trust the cards: edits re-run covering tests, a violated contract turns red with :implicated. Prefer over fetching several forms."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :depth {:type "integer"} :limit {:type "integer"}
                               :match {:type "string"} :window {:type "integer"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "query_depends"
    :description "THE generic dependency question: what depends on X — a namespace (who requires it + qualified refs), a var ns/name (blast radius — plus :red-after, the tests that went red in episodes where the form changed, most often first: what usually breaks when you touch it), or a :keyword (field flow). modules=true reads the MODULE system: alone = the manifest (declared edges + standing debt); with on=<module> = that module's SURFACE (public fns + exported deep vars with sig/doc, its deps, its consumers) — the cheap browse before calling into a module. Ask this first; query_slice {ns name} and query_brief {ns name} give per-form depth."
    :inputSchema {:type "object"
                  :properties {:on {:type "string"}
                               :direction {:type "string" :enum ["dependents" "dependencies"]}
                               :modules {:type "boolean"}
                               :detail {:type "boolean"}}}}
   {:name "review_scan"
    :description "REVIEW TRIAGE for a whole codebase (or one :ns): every form the store thinks is RISKY — untested (no covering test), off-platform (:cljs — the JVM oracle cannot load it, so NO test could ever cover it: the compiler is its only check, and this is a standing fact rather than a gap to close, so it ranks below untested), unused (public with ZERO in-store callers — dead code or unadvertised surface; whole scans only), effectful (!), high-blast (many callers), large, lint-flagged, or undocumented public surface — RISK-RANKED so you read the dangerous forms first. One pass; :top rows carry :form/:risk/:flags/:callers/:covered plus :evidence — WHICH KIND of evidence stands behind the row, so a quiet row is readable instead of ambiguous: :observed (a test actually ran it — the only one meaning verified), :declared (a ^{:covers} marker names a path nothing can watch, which is how coverage that happens ACROSS A PROCESS BOUNDARY — an endpoint hit over a socket, a namespace mounted by quoted symbol — is stated), :static with :hops (some form in a test namespace reaches it in the call graph; at 3-4 hops that is nearly free), :off-platform, or :none. The summary's :evidence is that split over the whole list — read it before trusting the quiet rows. Run a test_run first or nothing is :observed. Drill in with query_slice."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :limit {:type "integer"}}}}
   {:name "query_detail"
    :description "The FULL version of a trimmed response (responses over the size gate carry a query_detail id). The spool keeps the last 20."
    :inputSchema {:type "object"
                  :properties {:id {:type "string"}}
                  :required ["id"]}}
   {:name "query_eval" :image-free false
    :description "Read-only REPL eval against the live image (the oracle) — the escape hatch for ARBITRARY expressions. For the common case (invoke one fn with data args) prefer query_call: it carries the reference so renames/moves/the unused gate see it. Questions ABOUT the codebase-as-data: query_store."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"}}
                  :required ["code"]}}
   {:name "query_call" :image-free false
    :description "Observe-only INVOKE of one var in the live image: {sym \"app.core/f\", args [1 2]} — the structured face of query_eval's common case. The reference is CARRIED (visible to renames, moves, and the unused gate) instead of hidden in an eval string; args must be printable data."
    :inputSchema {:type "object"
                  :properties {:sym {:type "string"}
                               :args {:type "array"}}
                  :required ["sym"]}}
   {:name "query_store" :image-free false
    :description "The STORE-VALUE oracle: one read-only (fn [store] ...) evaluated over the current immutable store value — ad-hoc analysis ABOUT the codebase (form counts, metadata sweeps, custom aggregation) that no canned query covers. Fully-qualify everything (slopp.store/forms, slopp.store.render/render-ns, slopp.index.analyze/analyze ...); no effects/defs/interop/IO; results must print small. timeout_ms default 10000."
    :inputSchema {:type "object"
                  :properties {:code {:type "string"}
                               :timeout_ms {:type "integer"}}
                  :required ["code"]}}
   {:name "query_observe" :image-free false
    :description "Capture args/returns of ns/name while running driver `code` — what actually flows through it."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :code {:type "string"}
                               :limit {:type "integer"}}
                  :required ["ns" "name" "code"]}}
   {:name "query_macroexpand" :image-free false
    :description "Macroexpansion (expand-1 + full)."
    :inputSchema {:type "object" :properties {:code {:type "string"}}
                  :required ["code"]}}
   {:name "query_branches"
    :description "Branches with head deltas; marks the current one."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_vocabulary"
    :description "Browse the store's domain-keyword VOCABULARY (namespaced keys, most-used first) BEFORE coining new ones, so you REUSE an established key like :user/email instead of inventing a near-duplicate the key-hygiene advisory flags at done. Optional ns narrows to a keyword namespace (exact or dotted-child; e.g. `user` matches :user/* and :user.address/*)."
    :inputSchema {:type "object" :properties {:ns {:type "string"}}}}
   {:name "query_rules"
    :description "The ENFORCEMENT CATALOG for this store: every D9 rule (write gates + done-time advisories) with its grain, its EFFECTIVE per-store severity, how to discharge it, and what it means. See what's gated and at what grade. Dial any rule with config_file {path rules key <rule> value <severity>} — off / advisory / error / refuse."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_capabilities"
    :description "Every capability setting for this store: the declared registry (type, default, doc) joined with the stored `capabilities` config — effective values, what's set, and the wildcard families (http.static.*, http.auth.<provider>.*, http.auth.groups.*.members). Set with config_file {path capabilities key <k> value <v>}; writes validate against the registry."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_surface" :image-free true :read-only true
    :description (str "EVERYTHING this store declares it EXPOSES, sectioned by the"
                      " capability that owns it — commands under :cli, endpoints and"
                      " static mounts and performers under :http. ONE tool rather than"
                      " one per capability, because with separate tools an agent that"
                      " asks the wrong one gets an empty answer and cannot tell it from"
                      " a store that exposes nothing. A section appears only for an"
                      " ENABLED capability, so the shape of the reply says what kind of"
                      " application this is; with none enabled you get teaching, not {}."
                      " Rows are self-describing (:kind on every one, :doc where the"
                      " declaration has one) and name their handlers — query_slice on a"
                      " handler gives the contract exactly. The same derivations the"
                      " write gates enforce, so what this shows is what they guaranteed.")
    :inputSchema {:type "object" :properties {}}}
   {:name "query_rule_telemetry"
    :description "The D9 rules' FIRE-RATE + DISCHARGE signal for this store — the demand signal the severity dial is set by. Per rule: how often it fires (dones/instances), whether findings get :discharged (fixed) or :persisted (keep recurring = ignored/friction); plus escape-marker density (agents opting out via ^:unsafe/^:reads/^:unused-ok) and the current dials. Read-only history analysis over the delta log. Optional since (a delta/commit id from query_commits) windows it."
    :inputSchema {:type "object" :properties {:since {:type "string"}}}}
   {:name "query_cost" :image-free true :read-only true
    :description "WHERE THE WALL CLOCK WENT, folded over the per-turn records every turn already writes. Three-way and exhaustive: :slopp-ms inside a tool, :idle-ms for the session nobody was in, :outside-ms for agent reasoning plus every non-slopp tool — which the server cannot tell apart and does not pretend to. :slopp-share is taken against ACTIVE time, so a human going to bed is not counted as time slopp failed to use. Also :tools ranked by total cost, :refused with its per-tool breakdown (each refusal is a whole round trip that produced nothing), and :repeats — a tool run more than once inside ONE ask, ranked by what the extra runs cost, which is how an ordinary second read is told apart from a second whole-store check. :tools is a LOWER BOUND: only the five costliest tools per turn are recorded, so a cheap tool's absence is not evidence it was not called. Read-only over the delta log; optional since (a delta/commit id from query_commits) windows it."
    :inputSchema {:type "object" :properties {:since {:type "string"}
                                              :by {:type "string" :enum ["commit-point"]}}}}])

(def history-tools
  "Provenance tool descriptors: history, time-travel, change queries. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "report"
    :description "THE summary/handoff composite, one read: :by-ask — every ask verbatim with the forms it added, changed, deleted and renamed (newest first) — plus commit-points, net form changes, the last verification and alignment. It answers 'what changed here and why' by itself; per-namespace histories are for drilling into ONE form. contains matching form NAMES also carries :story — the most-storied forms' version rows (ask/op/at/state): the provenance answer ('why is X what it is') in this same call. since=<delta/commit-point id>, contains=<filter>. Prefer over stitching query_history/query_changes/query_commits."
    :inputSchema {:type "object"
                  :properties {:since {:type "string"}
                               :contains {:type "string"}
                               :limit {:type "integer"}}}}
   {:name "query_history"
    :description "EVERYTHING that happened, one tool: no args = change history (collapse=true for episode rows); {ns name} = one form's life; {ns name at} = TIME-TRAVEL to a past delta/commit-point; {ns name effort true} = what that form COST to get green (red→green cycles, distinct asks, recorded verification time + how much of its life that covers); {at} = was-green-at; {contains} = which asks/prompts touched X; {dead_ends true} = SCRAPPED explorations (reverts) with their why + the forms they dropped, {dead_ends \"some.ns\"} narrows to ones that touched it — check it before re-walking a path someone already abandoned. format=text for humans. For summaries/handoffs use report."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :at {:type "string"} :contains {:type "string"}
                               :limit {:type "integer"}
                               :collapse {:type "boolean"}
                               :effort {:type "boolean"}
                               :dead_ends {:type ["boolean" "string"]}
                               :format {:type "string" :enum ["edn" "text"]}}}}
   {:name "query_changes"
    :description "THE code-level change view — net per-form diffs (:was/:now) + red/green arc. Your open episode by default, or a span via :from/:to. :from takes NAMED ANCHORS as well as delta ids: \"start\" (the whole lifetime), \"last-commit\" (since the last commit-point), \"last-done\". This is what answers 'show me what changed, WITH the code' — reach for it instead of shelling out to git diff; format=text renders line diffs."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :format {:type "string" :enum ["edn" "text"]}}}}])

(def edit-tools
  "Write tool descriptors: the change verb, namespaces, renames, refactors.
  (Q4: the registry is per-group — editable without touching a monolith.)
  The former per-form write ops (edit_replace_form, edit_add_form,
  edit_group, edit_subform, edit_delete_form) and the old verb names
  (intent, query_batch) are DE-ADVERTISED: their vocabulary lives on as
  `change` steps and their names as dispatchable aliases
  (`single-write-tools`), not as descriptors."
  [{:name "ns_create"
    :description "Create a BRAND-NEW namespace (never overwrites). Either `requires` (clause strings) scaffolds an empty ns to grow with red-first TDD, or `source` lands whole namespace text in one verified call (ported/reference code) — mutually exclusive. A `requires` clause MAY name a namespace of yours that does not exist yet: it is created empty and reported in `:also-created`, so spec-first works across a namespace boundary instead of failing to load. `platform` (\"jvm\"/\"cljc\"/\"cljs\") declares the namespace's target platform up front, so a client ns is born :cljs — its js/* forms defer to the ClojureScript compiler (compile_client) instead of failing to load into the JVM oracle."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :requires {:type "array" :items {:type "string"}}
                               :source {:type "string"}
                               :platform {:type "string" :enum ["jvm" "cljc" "cljs"]}
                               :doc {:type "string"
                                     :description "the namespace's purpose, stored as its docstring (else the prompt is)"}
                               :prompt {:type "string"}}
                  :required ["ns"]}}
   {:name "ns_add_require"
    :description "Add one require clause (e.g. \"[clojure.string :as str]\") to the ns form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :require {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "require"]}}
   {:name "ns_remove_require"
    :description "Remove a library's require spec from the ns form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :lib {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "lib"]}}
   {:name "edit_comment"
    :description "Set (or clear, with empty text) the comment rendered above form `name`. Owned by the form; no anchor, no verification."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :text {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns" "name" "text"]}}
   {:name "change"
    :description "THE write door — a whole unit of work, or any slice of one, as ONE call. `tests` steps (OPTIONAL) land first and the result reports which went RED — watched failing, red-first honored; `impl` steps land; `accept`'s expectation shifts finish; ONE verification; one result, with :test-src on any residual red (fix forward from it — a red change lands NOTHING and loses nothing: the work stays on your thread). `done \"label\"` / `commit true` on the LAST change closes the unit in the same call (:closed); a unit may span change -> explore -> change, and the server lands what is left green on your thread when the session ends. {prompt, impl} alone is the ordinary write — a docstring, a comment, a one-form fix; tests lead only when there is an expectation to watch fail, and a test you are writing to FIND something out is explore {ops [{op check …}]} instead. Steps: [{action: add|replace|subform|delete|require|patch, ns, name, source, match, text, where, require}] — patch = {action: patch, ns, name, replace: [{match, source, text?, where?} …]}: several small changes INSIDE one form as deltas, never a whole-form retype. A step with no action is inferred (:replace when the named form exists, :add when not). A delete step refuses while callers remain, naming them. Auto-require and auto-module-dep repair as on every write. A change past ~10 steps is two changes."
    :inputSchema {:type "object"
                  :properties {:prompt {:type "string"}
                               :tests {:type "array" :items {:type "object"}}
                               :impl {:type "array" :items {:type "object"}}
                               :accept {:type "array" :items {:type "string"}
                                        :description "tests (ns/name) whose literal expectations this change is MEANT to move — their :proposed updates are applied and re-verified in this same call (:finisher)"}
                               :done {:type "string"
                                      :description "this change FINISHES the unit: close it in the same call under this label — done, landed, the suite, the whole-store verdict when cheap (:closed); a red change closes nothing"}
                               :commit {:type ["boolean" "string"]
                                        :description "and take a commit point (true, or its label) — the write that finishes an ask is one turn"}
                               :verbose {:type "boolean"}}
                  :required ["prompt"]}}
   {:name "edit_revert"
    :description "Revert a form to an earlier version (default previous, or a delta id)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :to {:type "string"} :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "rename_sweep"
    :description "A concept rename as ONE intent: every namespace, var, keyword, prose occurrence and tracked text file naming `from` (whole word/segment; a bare word's Zone/ZONE spellings too) becomes `to`, store-wide — ns renames + one atomic group, one verification. THE tool for docs-team renames; never form-by-form. PREVIEW FIRST with dry_run (a read — it rides in explore): :in-code, :in-strings (each with its form's source: a fixture is data, not prose, judge it), :in-files, :mentions (every mention, any case). The run reports :rewritten (the string-hit forms as they now read), :files, :case-variants, :remaining (what still names the old word), and for keyword renames :requalified / :left-behind (destructurings moved / declined) and :patterns-rewritten (regex literals)."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :dry_run {:type "boolean"
                                         :description "preview only: write nothing, report :in-code and :in-strings"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["from" "to"]}}
   {:name "edit_requalify"
    :description "Namespace a module-external fn's OPTION KEYS in ONE intent: its arglist destructuring AND every caller's map literal, together. THE way to discharge require-namespaced-keys — a store-wide rename_sweep is unsafe whenever the key means more than one thing (:dir names three different things), and hand-editing dozens of call sites is worse. Keys are DERIVED from the arglist, so half a contract cannot be namespaced and left reading nil. `to-ns` defaults to the target's namespace. Callers passing a NON-literal map are reported under :unknown-shape and left UNTOUCHED — no syntactic reader can see through a binding, and those are yours to check. Call sites outside the store (kernel .clj files) are invisible to it. dry-run previews without writing."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :to_ns {:type "string"}
                               :dry_run {:type "boolean"
                                         :description "preview only: write nothing, report the form count and :unknown-shape"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_rename"
    :description "Rename ONE form + every reference across namespaces (shadow-safe): {ns from to}. For concept-wide renames (ns + keys + prose) use rename_sweep; to rename a namespace's ALIAS rather than a var, ns_realias."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :from {:type "string"}
                               :to {:type "string"} :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "from" "to"]}}
   {:name "ns_realias"
    :description "Rename ONE namespace's require alias as a single intent: the `:as` in its ns form and every `alias/sym` in its bodies, together. THE tool for an alias ns_rename left stale — a rename rewrites namespaces and walks straight past the `:as`, so moved code goes on being called by its old module's name; when that name is later REUSED the alias points at a real, different module, which is worse than one naming nothing. There is no safe hand route: the two halves cannot be separate writes, because between them the ns form and the bodies disagree about the qualifier and the namespace does not load. Scoped to one namespace on purpose — an alias is a name ONE namespace chose, so two namespaces calling a lib different things is not drift. A BARE occurrence of the old alias is LEFT ALONE: only `alias/x` is the qualifier, and the same spelling is routinely a local or a parameter. READ THE RESULT: :sites is how many qualified references moved (0 means the alias was unused, not that nothing happened); :left-behind is the alias named inside STRING literals — fixture source, a docstring saying `alias/f` — reported and never rewritten, because rewriting one half of a fixture is how a half-renamed ns form ships green. Absence means checked-and-none."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :from {:type "string"}
                               :to {:type "string"} :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "from" "to"]}}
   {:name "change_signature"
    :description "Change a fn's signature atomically: `source` = the new defn (same name); `calls` = arg-list template rebuilding every call site ($1..$9 = the site's existing args; adding a trailing arg = \"$1 $2 nil\"). Higher-order refs return under :manual."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :source {:type "string"} :calls {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name" "source" "calls"]}}
   {:name "edit_extract"
    :description "Extract a subform of `from` into a new fn (params computed from free locals, call site rewritten, verified). Address the subform EITHER by `match` (its exact text — the same word change's :subform steps and query_slice use) OR by `at` — an ANCHOR, its first line or so, which need not parse on its own (\"(let [turn-brackets\"). Prefer `at` for anything large: quoting a big subform's whole body means transcribing the exact code you were trying not to touch. A non-unique anchor asks you to extend it; a missing one returns :source-now."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :from {:type "string"}
                               :match {:type "string"
                                       :description "the exact subform to extract (or use `at`)"}
                               :at {:type "string"
                                    :description "anchor: the subform's head; resolves to the smallest complete form containing it"}
                               :name {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns" "from" "name"]}}
   {:description "Walk back your OWN recent writes — the cheap, reach-for-it-immediately undo. deltas: n (default 1) undoes your last n writes; to: \"d123\" undoes everything of yours after that delta. to also takes a NAMED anchor: \"last-commit\" scraps everything since the last commit-point (the usual dead-end rollback — no delta id to hunt), \"last-done\" goes back to your last done point. Addressed by DELTA, not by name, so it also restores a form you DELETED. Forms another agent also wrote in the span are skipped and reported. deltas counts over the LOG and REFUSES rather than reaching past: a delta whose op it cannot invert (ns_rename, edit_move_forms, ns_delete, config_file, module_dep — anything changing more than form sources) comes back in :blocked with NOTHING reverted, rather than being stepped over to undo something older. One atomic verified group. Reach for this the moment a write turns out wrong; use episode_revert only to scrap a whole episode.", :inputSchema {:properties {:prompt {:type "string"}, :deltas {:type "integer"}, :to {:type "string"}}, :type "object"}, :name "undo"}
   {:name "episode_revert"
    :description "Roll back everything YOU changed since your last done (other sessions' forms skipped, reported). To walk back just one write, or a short chain, without losing the rest of the episode, use undo."
    :inputSchema {:type "object"
                  :properties {:prompt {:type "string"}}}}
   {:name "ns_rename"
    :description "Rename a WHOLE namespace everywhere (decl, requires, qualified refs). Verified. READ THE RESULT: a relocation lands as one changeset and runs NO write gates, so nothing refuses what it breaks. :left-behind lists what no rewrite reaches — strings, qualified KEYWORDS, the -test sibling, and under :alias the callers whose `:as` still spells the OLD name, because a rename rewrites the lib symbol beside an alias and never the alias itself. Each :alias row carries :suggest, the alias to pass ns_realias — absent where that caller already spells another lib that way, since realias would refuse it. An alias that reads correctly for BOTH names (a namespace changing modules under the same last segment) is not reported and needs nothing. :module-debt lists the module_dep edges its callers now need, calls that now reach a package-private ns, and cycles module_dep will refuse. Absence of either means checked-and-none. Run the ns_realias calls now rather than later: a stale alias is harmless only until the old name is REUSED, after which it points at a real and different module — which reads identically in the source and is the worse failure."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :prompt {:type "string"}}
                  :required ["from" "to"]}}
   {:name "ns_delete"
    :description "Retire a namespace: refuses while any form remains (delete the forms first, callers before callees — each deletion verified) or any other ns still requires it (ns_remove_require) — then removes the empty husk from store, image, and every projection. One :ns-delete delta; say WHY in prompt."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns"]}}
   {:name "edit_move_forms"
    :description "Move forms to another namespace, NEW or EXISTING — the general relocation refactor. Callers EVERYWHERE (prod + tests) are rewritten to alias-qualified calls and gain the require; moved defs are publicized (module visibility is the boundary); the target gets only the requires the moved code uses; dependency direction is analyzed (a two-way split refuses — a real cycle). export: true WIDENS per var — a var already ^:export keeps its level without the flag, and passing it does not silently widen the rest. A NEW target inherits the SOURCE's purity tier when the source declared one, so a split does not drop its forms into the shell; an undeclared source mints nothing. READ :export-not-landed — the move checks its own postcondition against the committed store and names the var. One atomic group, verified."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :forms {:type "array" :items {:type "string"}}
                               :to {:type "string"}
                               :export {:type ["boolean" "string"]
                                        :description "true = world surface; a namespace-prefix string = visible to that subtree only"}
                               :prompt {:type "string"}}
                  :required ["ns" "forms" "to"]}}
   {:name "module_extract"
    :description "Regroup at MODULE grain: pull whole namespaces (with their subtrees and -test siblings) under `to` — the op for a namespace that grew into its own component, or for giving a set of them one owning prefix. Going from two segments to three makes a namespace PACKAGE-PRIVATE, so every outside caller would break at once: this hoists exactly the vars that lose visibility (^:export) BEFORE renaming, then renames (callers, requires, prose, and the manifest all follow), then declares the edges the moved store actually references. ALWAYS dry-run first: it writes nothing and returns the plan — the renames, every var that must be exported AND WHICH CALLERS FORCE IT, the edges that appear, the edges the regroup unbacks. Refuses a regroup that would leave a production module cycle; a -test back-edge is not one."
    :inputSchema {:type "object"
                  :properties {:namespaces {:type "array" :items {:type "string"}
                                            :description "the namespaces to pull; each takes its subtree and -test sibling with it"}
                               :to {:type "string" :description "the owning prefix, e.g. \"my.core\""}
                               :dry_run {:type "boolean"
                                         :description "plan only: write nothing, report renames / exports+who-forces-them / edges / refusal"}
                               :prompt {:type "string"}}
                  :required ["namespaces" "to"]}}
   {:name "cleanup"
    :description "Bring a namespace up to current standards — the WHOLE namespace, regardless of what you touched. Pass `all: true` instead of `ns` to sweep the ENTIRE store: that is the migration surface for adopting slopp on an existing codebase, or for landing a slopp upgrade that adds a rule every existing form predates. APPLIES: normalize every form (conservative, behavior-preserving), reorder definitions above their callers, retire legacy or stale (declare …)s and phantom names. REPORTS everything slopp can check on a WRITE, replayed over EXISTING code — :lint, :unused (dead public surface), :undocumented, :gates (module / tier / schema / namespaced-keys write gates), :advisories (ambient-state, bare-throw, key-typos, schema-drift) and :purity (which tier the namespace could support). Those all normally fire only as code is written, so a form predating a rule was never subject to it. Findings are REPORTED, never auto-fixed — each needs a judgment call."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :all {:type "boolean"
                                     :description "sweep every namespace in the store (migration mode); omit ns"}
                               :prompt {:type "string"}}}}])

(def flow-tools
  "Session-flow tool descriptors: turns, tests, done-points, commit-points, build. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "turn_begin"
    :description "Open a turn manually (records the verbatim user ask as intent). Turns are normally opened FOR you by the plugin's hooks — only needed if a write is refused."
    :inputSchema {:type "object"
                  :properties {:intent {:type "string"}
                               :user {:type "string"}}
                  :required ["intent"]}}
   {:name "turn_end"
    :description "Close the turn (usually automatic)."
    :inputSchema {:type "object"
                  :properties {:note {:type "string"}}}}
   {:name "full_check"
    :description "The WHOLE-STORE check: lint, dead surface, both layering graphs, the rule sweep, and every test in every tier (in-image, ^:integration, ^:external). Nothing forces it, and a close already carries the whole-store verdict when the store is small — reach for this on a big store after a broad change, after deleting a caller, or before a commit you stand behind. A verdict that still STANDS returns in a millisecond (:standing true); force=true re-runs. affected=true narrows the ^:external tier (nearly all the cost) to what changes since the last commit-point reach; :external :cost reads the tier as its slowest shard and :narrowing-ceiling-ms as the most narrowing can return."
    :inputSchema {:type "object" :properties {:affected {:type "boolean"}
                                              :force {:type "boolean"}
                                              :verbose {:type "boolean"}}}}
   {:name "done"
    :description "Close a unit of work: normalize touched forms, re-verify (the whole in-image suite plus the ^:external tests your changes impact; lint and dead surface over the namespaces you touched), record a labeled boundary, land. Carries :suite (the counts), :whole-store when the whole-store check is cheap, and with commit=true (or a label) the commit point too. A deferred impacted ^:external set is REPORTED (:external-pending). done reports, never refuses; a red one stands until new work supersedes it. The usual close is `done`/`commit` on the LAST change rather than this call."
    :inputSchema {:type "object" :properties {:label {:type "string"}
                                              :commit {:type ["boolean" "string"]
                                                       :description "also take a commit point (true, or its label) in this same call"}}}}
   {:name "commit_point"
    :description "Record a COMMIT-POINT: runs a full done and gates on its verdict (force=true records red honestly); it runs no whole-store check of its own. The git-projection grain; target=<delta id> marks an earlier spot. The usual form is `commit true` on the closing change or on done."
    :inputSchema {:type "object"
                  :properties {:label {:type "string"}
                               :force {:type "boolean"}
                               :target {:type "string"}}
                  :required ["label"]}}
   {:name "test_run"
    :description "SPOT-CHECK specific tests: {ns \"x.y-test\"} or {only [\"x.y-test/some-t\"]}. Targets run in their OWN tier: in-image members in-image, named ^:external members in one serial external JVM — the red/green fast lane for an external test needs no {external true} detour. You do NOT need this before done or commit_point — done runs the affected tests in every tier (impacted ^:external included) and the commit-point runs the whole external suite itself. Whole in-image suite: {all true} (rarely needed). Explicit full external run: {external true} — fresh JVM, auto-shards (:parallel N overrides), {affected true} narrows to test nses reaching changes since the last commit-point. Red external runs return :failing + :all-failing {file [tests]} + :themes."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :only {:type "array" :items {:type "string"}}
                               :all {:type "boolean"}
                               :external {:type "boolean"}
                               :affected {:type "boolean"}
                               :fresh {:type "boolean"}
                               :parallel {:type "integer"}}}}
   {:name "draft_test"
    :description "A ready-to-edit deftest DRAFT for an :untested form. With :code (a driver expression) it observes real calls and turns each into an assertion; without, a signature skeleton with TODO holes. Nothing is written — adopt via edit_add_form, red-first."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :code {:type "string"}
                               :limit {:type "integer"}}
                  :required ["ns" "name"]}}
   {:name "help" :read-only true :image-free true
    :description "The cheat-sheet (no argument) or ONE reference topic whole: help {topic} serves the plugin's skills/slopp/reference/<topic>.md — the chapters the one-page skill points at (web, rest, cli, capabilities, results, writing, oracle, tools …). Read a topic when the task needs it, not up front."
    :inputSchema {:type "object" :properties {:topic {:type "string"}}}}
   {:name "restart"
    :description "Restart the live image; reload all forms. **It does NOT reload the JAR.** The image is rebuilt inside the SAME JVM, so slopp's own code — the tools, the framework vendored into your store — is whatever this process loaded at boot, and a jar rebuilt on disk since then is not in it. A consumer ran this after a framework fix, saw `restarted`, and found the function still had its old arglist. What reaches a new jar is restarting the MCP SERVER, which is the user's to do. Three states, and only the last one decides: what slopp ANNOUNCED, what the jar on disk CARRIES (`unzip -p <jar> META-INF/slopp/head.edn`), and what this process has LOADED — session_brief's :host :jar :head, with :behind counting the deltas between them. `app true` ALSO re-serves this project's own app server, which the plain call has never touched: a declared dev entry (config_file {path \"dev\" key \"run.<name>.main\"}) answers `:started` once its namespace loads and its thread spawns, so one that came up half dead reports exactly what a healthy one does — this is how you ask again without making an unrelated write to trigger a done."
    :inputSchema {:type "object"
                  :properties {:app {:type "boolean"}}}}
   {:name "build"
    :description "Materialize every namespace to .clj files under dir (absolute). Optional main (qualified entry fn) adds a GraalVM native-image recipe."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"} :main {:type "string"}
                               :name {:type "string"}}
                  :required ["dir"]}}])

(def env-tools
  "Environment tool descriptors: deps, files, config, branches. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "config"
    :description "Read/set store config (user.name / user.email — the commit-point author; \"<git>\" defers to git config). Omit value to read."
    :inputSchema {:type "object"
                  :properties {:key {:type "string"}
                               :value {:type "string"}}
                  :required ["key"]}}
   {:name "file_put"
    :description (str "Track a non-code file on the files manifest (rides every projected"
                      " tree). Text by default; encoding \"base64\" stores BINARY"
                      " content-addressed (content-type labels it) — the journal carries"
                      " only the sha. `source` reads the bytes from a PATH on disk instead"
                      " of taking them inline, which is what you want for a vendored"
                      " library, font or image: inline means reading the file into your"
                      " own context and writing it straight back out.")
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}
                               :content {:type "string"}
                               :source {:type "string"}
                               :encoding {:type "string"}
                               :content_type {:type "string"}
                               :prompt {:type "string"}}
                  :required ["path"]}}
{:name "js_dep"
    :description (str "Vendor and declare a JavaScript library — the third dependency world."
                      " ONE call: source names the bytes on disk, and they are written to"
                      " the content-addressed artifact cache with a :download recipe, so"
                      " the journal carries a sha and a way back rather than the library."
                      " Anchor provenance to the REGISTRY — npm \"roughjs@4.6.6\" +"
                      " npm-path \"bundled/rough.js\" + integrity — because npm versions are"
                      " immutable, where a CDN url only says how the bytes arrived this"
                      " time. format is \"iife\"/\"umd\" (concatenated into the bundle via"
                      " deps.cljs :foreign-libs, and global names what it sets on window)"
                      " or \"esm\" (loaded by the page). file is where it sits in the"
                      " project tree. remove retracts the declaration. Read them back with"
                      " query_store over :js-deps.")
    :inputSchema {:type "object"
                  :properties {:name {:type "string"}
                               :version {:type "string"}
                               :format {:type "string"}
                               :global {:type "string"}
                               :file {:type "string"}
                               :source {:type "string"}
                               :npm {:type "string"}
                               :npm_path {:type "string"}
                               :integrity {:type "string"}
                               :source_url {:type "string"}
                               :license {:type "string"}
                               :remove {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["name"]}}
   {:name "file_remove"
    :description "Drop a path from the files manifest."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}
                               :prompt {:type "string"}}
                  :required ["path"]}}
   {:name "file_list" :image-free true :read-only true
    :description "The files manifest: {path bytes}."
    :inputSchema {:type "object" :properties {}}}
   {:name "file_get" :image-free true :read-only true
    :description "A manifest file's content (optionally at a past delta/commit-point via `at`)."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"} :at {:type "string"}}
                  :required ["path"]}}
   {:name "file_history" :image-free true :read-only true
    :description "A manifest file's tracked versions with provenance."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}}
                  :required ["path"]}}
   {:name "config_file"
    :description "STRUCTURED config file: semantic key/values with per-key history, serialized into the projection (e.g. META-INF/MANIFEST.MF). Set path+key+value; unset=true removes; path alone reads. Prefer over file_put for key/value config."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"} :key {:type "string"}
                               :value {:type "string"} :unset {:type "boolean"}
                               :format {:type "string"}
                               :prompt {:type "string"}}
                  :required ["path"]}}
   {:name "module_dep"
    :description "Declare (or retract with remove=true) ONE module dependency edge — modules are the first two ns segments (\"logi.parcel\"). Each call is one journaled delta; say WHY in prompt. Adds are cycle-checked over PRODUCTION edges. `test_only: true` declares the edge for the module's -test namespaces ONLY — production code under `from` is still refused, and a test-only edge is not a production edge so it is never a cycle. Reach for it when a fixture must drive a surface that calls back into this module (a done-time advisory can only be tested by writing code and calling done): the alternative is moving the test away from its subject. The cycle refusal names this option itself when every namespace crossing is a test. Read the manifest: query_depends {modules true}."
    :inputSchema {:type "object"
                  :properties {:from {:type "string"} :to {:type "string"}
                               :remove {:type "boolean"}
                               :test_only {:type "boolean"
                                           :description "bind the module's -test namespaces only; production stays refused"}
                               :prompt {:type "string"}}
                  :required ["from" "to"]}}
   {:name "module_purity"
    :description "Declare a NAMESPACE's purity tier for the functional-core gate. :pure = referentially transparent (no mutation, no rand/slurp) — that is what lets the generative schema check run on it; :internal = may mutate IN-PROCESS state (a memo, a registry) but touches NOTHING outside the process; :external = IO (files, subprocesses, network, db). Undeclared = :external = ungated. Scope is a namespace PATH and the MOST SPECIFIC declaration wins, so a pure core one level below an effectful module (shop.totals.round inside shop.totals) is declarable. Declaring VERIFIES THE FORMS already there and refuses a tier they exceed — the result's :verified/:unverified says so, because it does NOT check layering (whether the namespace requires a LOOSER tier): that verdict changes as legitimate work continues, so full_check owns it and a wrong tier can stand until then. The axis is internal/external because that is what decides how a thing must be TESTED: external needs isolation (fresh JVM, temp dirs), internal needs only a cache/state reset, pure needs nothing. Caches must go through slopp.cache so `internal` stays checkable. Say WHY in prompt. (:reads/:effects are legacy spellings of :internal/:external.) Read tiers: query_depends {modules true}."
    :inputSchema {:type "object"
                  :properties {:module {:type "string"}
                               :tier {:type "string"}
                               :remove {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["module"]}}
   {:name "deps_add"
    :description "Add an external dependency (hot to the live classpath, no restart). lib like \"org.clojure/data.json\"; version string or full coord map."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"}
                               :version {:type "string"}
                               :coord {:type "object"}
                               :client {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["lib"]}}
   {:name "deps_remove"
    :description "Remove a dependency (restarts the image)."
    :inputSchema {:type "object"
                  :properties {:lib {:type "string"}}
                  :required ["lib"]}}
   {:name "deps_list" :image-free true :read-only true
    :description "The dependency manifest: {:deps {lib coord}}, plus :host-override for any declaration slopp's own process bundles at a different version and so cannot honor (inert; the host's copy wins). Note what is NOT here: slopp's framework, AND what the framework itself requires. slopp vendors the source into every store that uses it and supplies its deps alongside, at the version slopp is, so neither is ever declared and neither drifts. A store reasoning 'the vendored code requires replicant, so I must declare replicant' is reasoning about a classpath it does not own — measured by a consumer who removed the declaration and found the artifact still carried it."
    :inputSchema {:type "object" :properties {}}}
   {:name "store_health" :read-only true
    :description "What this store CARRIES, in bytes: the journal per op (heaviest first), the materialized state, the blob table, and the on-disk artifact cache. Cheap — SQLite LENGTH only, nothing parsed. full_check answers whether the store is CORRECT; this answers what it COSTS. Reach for it when a session feels slow to open, before growing what a delta carries, and periodically: a store can rot by GROWING, and nothing else measures that."
    :inputSchema {:type "object" :properties {}}}
   {:name "store_doctor" :read-only true
    :description "The LEGACY sweep: elements that predate a rule slopp now enforces and that no ordinary tool can reach — hand-written (declare …) the ordering pipeline cannot see, two elements in one namespace defining ONE name (a form-addressed edit cannot say which you mean, and the last wins at load), and metadata that looks like one of slopp's dials but is not (^:unusedok waives nothing while reading as though it does). Every finding carries the call that fixes it. A THIRD question: full_check asks whether the store is CORRECT, store_health what it COSTS in bytes, this what is in here that the current rules would never have let in. Reach for it right after adopting an existing codebase (git_clone / import), where every form predates every rule — a store written entirely through slopp is normally clean."
    :inputSchema {:type "object" :properties {}}}
   {:name "store_compact"
    :description "Reclaim what settled lines left behind and VACUUM the file: every elements row belonging to a landed or abandoned line is deleted, then SQLite gives the space back. Returns {:rows-dropped :bytes-before :bytes-after :reclaimed}. Deliberate, not automatic: land-thread! now drops a landed thread's view as it lands, but 663 landings before that fix left 3.4 GB (64% of one file) behind, and a fix does not un-write what it wrote. store_health's :elements :by-status shows what this would reclaim — run it when the 'landed' rows are not zero. Read its :source-bytes as a FLOOR, not an estimate: it is LENGTH() over the source text, and the file also carries row overhead, indexes and free pages for those rows (measured 1.37× on one store, 269 MB back against 197 MB of text). VACUUM holds the file lock for as long as the copy takes, so run it when no other writer is mid-land."
    :inputSchema {:type "object" :properties {}}}
   {:name "ui_serve"
    :description "Serve THIS project's own API listener — /api/* as JSON, plus this project's own SURFACE as EDN, one document per capability: /api/rest/paths (typed endpoints and their schemas), /api/http/paths (the content it serves — usually empty), /api/webapp/paths (the addresses its browser routes to, one row per :webapp/path page, with the endpoints each page calls — usually empty). It has NO pages in it: `/` answers 404, so a human handed this url sees JSON. The screens live in the HUB, a separate application that renders every page and fronts this project at /p/<slug>/ (D-hub part 4) — when the answer is for a human, hand over session_brief's :hub, not its :ui. Served on the LIVE session, so warranty and observed examples are the ones this session actually has; a process that opened the same store fresh would show every form as covered by nothing. Returns {:url :port}. `port` pins the address for THIS run only — there is no capability for it, because this port is an output: DERIVED from the store dir so several projects on one machine never collide, stable across restarts, and reported rather than set. 7359 is slopp.hub.port, a different setting and a real one; `stop: true` shuts it down. Serving again EVICTS the running server rather than hunting for a free port, and a port someone else holds comes back as a sentence, not a stack trace."
    :inputSchema {:type "object"
                  :properties {:port {:type "integer"}
                               :stop {:type "boolean"}}}}
{:name "screen"
    :description "LOOK AT a screen of this app, driven headlessly — no browser, no rendering engine, no test written. Hand it a `url` the way you would type one into a browser: it opens the zero-arg fn you marked ^:app/entry (a slopp.http ctx, or {:state :view}), goes there, runs any ordered `steps` script through the app's OWN handlers, and returns the screen as text with a WHITELISTED tag channel. The provenance rule reads it for you: plain text is the page's words, HTML-escaped, so it can never be mistaken for markup; an UNPREFIXED tag or attr was really on the page and survives only where it carries something you can act on (<a href>, <button>, <input>, <select>/<option>, <textarea>, <form>, <label>, headings, <table>/<tr>, <pre>, <img alt>, an <svg> censused by CLASS); anything slopp: -prefixed the reader DERIVED — <slopp:region name=\"main\"> wrapping each :data-region pane, <ul slopp:count=\"5\"> with a machine-visible <slopp:elided count=\"2\"/> where the tool's 3-row cap bit, and slopp:on=\"click :action arg\" naming what a control does (a closure handler shows as \"click (fn)\"; scalar action args travel because they are what tell twin buttons apart). Besides the page you get what a browser would also tell you: `url` — the ADDRESS BAR, which after a redirect is not what you asked for — `status` as a NUMBER to compare rather than a sentence to search the page for, and `redirects` when the app sent you somewhere. Each is absent when there is nothing to say. `steps`: [{visit \"/x\"} {click \"Add\"} {fill \"Filter\" value \"web\"}] — click matches visible text, an href, or an aria-label and BUBBLES to the nearest handled ancestor like a browser (a disabled control refuses); fill matches placeholder/name/id/aria-label, a <select> refuses a value it has no option for, a checkbox takes its checked boolean; every miss refuses listing what IS clickable/fillable rather than shrugging. `region` scopes to one pane and throws if absent; `detail` is \"structured\" (default) or \"prose\" (sentences only, UNESCAPED, for `does it say X` at a fraction of the tokens); `trace` returns the screen after EVERY step of one run. There is deliberately NO session between calls: a url plus a script is the whole interaction, so the screen you looked at is one you can pin with the same call in a test (slopp.cljnx/open! + drive! — which elides nothing by default; the cap is this tool's). Runs in the VERIFICATION image, so it shows the code the store holds — not the served app, which may be behind. NOT a screenshot: wrapping and colour contrast still need eyes."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"
                                     :description "the address to open at, e.g. \"/things/42\""}
                               :steps {:type "array" :items {:type "object"}}
                               :region {:type "string"}
                               :detail {:type "string"}
                               :trace {:type "boolean"}}}}
   {:name "compile_client"
    :description "Compile the store's CLIENT namespaces (:cljc + :cljs, declared via module_platform) to JavaScript with the configured backend (default ClojureScript, compiled ON THE JVM — no Node) and record the output as a served file blob. Compile-error-as-oracle: analyzer warnings and hard errors are anchored to the owning store forms. `output` sets the served path (default public/cljs/main.js). slopp injects its OWN compiler toolchain at build time — never deps_add the compiler."
    :inputSchema {:type "object"
                  :properties {:output {:type "string"}}}}
   {:description "Generate the typed CLIENT (D-web-contracts part 2): one edit-PROTECTED :cljs namespace of typed fetch wrappers, validating the request OUT and the response IN against a shared :cljc schema. Two sources. WITHOUT `from`: read the endpoints THIS store serves. WITH `from` (a URL serving a published contract, e.g. http://host/api/rest/paths): generate against an API this store CONSUMES — writes a :cljc contracts namespace of the published schemas alongside the client, reads no other store, and refuses a contract version it does not know. The target ns defaults to the client/generated-ns config, else <this store's own namespace family>.client.api; `ns` overrides. Any OTHER namespace already holding generated forms comes back in :other-clients — it stays marked :cljs, so compile_client keeps bundling it. An EXPLICIT step (like compile_client, not on every edit); marks the ns :cljs so compile_client picks it up, and with client/auto-compile on schedules a background recompile. Endpoints whose schema can't ship to the client (non-:cljc, or a missing var) are SKIPPED and reported in :problems. Regenerate, never hand-edit.", :inputSchema {:properties {:ns {:type "string"}, :from {:type "string"}}, :type "object"}, :name "generate_client"}
{:name "module_platform"
    :description "Declare a MODULE's target platform for the client wave — :jvm (Clojure on the JVM, the default), :cljc (portable: loads on the JVM AND compiles to JS — shared schemas/logic), or :cljs (ClojureScript only: compiled to JS, NEVER loaded into the JVM oracle — browser code). Namespace grain, most-specific declaration wins (like module_purity). A :cljs namespace renders as .cljs under a separate cljs-src/ tree, is excluded from JVM image-load, and is verified by the cljs compile step, not the JVM. Say WHY in prompt. Read platforms: query_depends {modules true}."
    :inputSchema {:type "object"
                  :properties {:module {:type "string"}
                               :platform {:type "string"}
                               :remove {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["module"]}}
{:name "module_role"
    :description "Declare what KIND of code a MODULE is, which decides whether it SHIPS — :product (the default: the system runs it, materialized under src/, carried into the jar) or :instrument (a HUMAN runs it by hand — a benchmark, a seeding script, a data-mining CLI). An :instrument is materialized under `instruments/` instead of `src/`, so any build that jars src/ leaves it out with no knowledge of roles, and it is dropped from the architecture view so a harness cannot sit on top of what it measures. Declaring :instrument is REFUSED while product code requires the module, naming the callers — otherwise the jar would carry a require to a namespace it does not contain, and you would learn that at a consumer's load time. A -test namespace requiring it is fine: a test does not ship either. Namespace grain, most-specific declaration wins (like module_purity). `remove: true` retires a declaration — absent is not the same claim as :product. Say WHY in prompt. Read roles: query_depends {modules true}."
    :inputSchema {:type "object"
                  :properties {:module {:type "string"}
                               :role {:type "string"}
                               :remove {:type "boolean"}
                               :prompt {:type "string"}}
                  :required ["module"]}}
   {:name "deps_pure"
    :description "Assert a dep target is PURE so callers aren't !-flagged: a var (\"ns/f\"), a namespace, or a whole lib. pure=false undoes."
    :inputSchema {:type "object"
                  :properties {:target {:type "string"}
                               :pure {:type "boolean"}}
                  :required ["target"]}}
   {:name "branch_create"
    :description "Create a branch from the current state and switch to it (O(1))."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_switch"
    :description "Checkout another branch (or main); the live image follows. Trace narrowing resets."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_merge"
    :description "Merge a branch into the CURRENT line. Same-form divergence returns :conflicts (current kept; payload IS current source). The branch survives."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_delete"
    :description "Delete a branch (never the one you are on)."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "thread_list"
    :description "The live THREADS on this branch — the private lines agents write to before a done lands them. Each row: :agent, :unlanded (deltas written since it forked), :idle-ms, :held (a LIVE process holds the write lease — a different question from idle, and usually the one you are asking), and :mine on your own. Across all agents on purpose: the question this answers is whether there is work here nobody is going to finish, and an idle thread is by definition somebody else's. Nothing reaps a thread on a timer; a drop is a decision somebody makes."
    :inputSchema {:type "object" :properties {}}}
   {:name "thread_drop"
    :description "START OVER: abandon a thread and take its work off your store and image. NO ARGUMENT means your own — reach for this when you have gone down a wrong path and want to be back where the branch is. Not undo/episode_revert: those are forward-only (they append revert deltas, so the work stays in your history) and episode-bounded, while this settles the LINE and covers everything since the last thing that LANDED — several red done points, which is when start-over gets asked. The deltas stay walkable either way. Pass {id} from thread_list to drop somebody else's."
    :inputSchema {:type "object" :properties {:id {:type "string"}}}}
   {:name "merge_from"
    :description "Merge a diverged COPY of this project (absolute dir). Same-form divergence = :conflicts, ours kept."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"}}
                  :required ["dir"]}}])

(def sync-tools
  "Git-sync tool descriptors: push/pull/clone/conflicts and remotes. (Q4: the registry is per-group \u2014 editable without touching a monolith.)"
  [{:name "query_commits" :image-free true :read-only true
    :description "Commit-points, newest first — TITLE lines only (+ :more-lines); {commit \"dN\"} drills into ONE full description (targets plug into query_changes from/to). With a git remote configured, :alignment PROVES whether the slopp branch head is the latest commit-point's projection — trust it; no worktree/sqlite cross-checks."
    :inputSchema {:type "object" :properties {:commit {:type "string"}}}}
   {:name "query_git" :image-free true :read-only true
    :description "This session's git view: the saved external remote and the clone base it grafts onto, or a refusal naming how to set one."
    :inputSchema {:type "object" :properties {}}}
   {:name "git_push"
    :description "Publish slopp history to the git remote: from a checkout, pushes your slopp/<branch> mirror branches (current store branch by default; branches: [...] for more); a fileless store publishes its projection. First url becomes the saved default; one-off urls never rewrite it. Fast-forward only."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"} :token {:type "string"}
                               :branches {:type "array" :items {:type "string"}}}}}
   {:name "git_clone"
    :description "Clone a remote into dir as a FILELESS store (every ns ingested + verified; no .clj files materialized)."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"}
                               :dir {:type "string"}
                               :token {:type "string"}}
                  :required ["url" "dir"]}}
   {:name "git_pull"
    :description "Fetch the remote's slopp/<branch> mirrors into local git (fast-forward only) AND absorb remote store history (3-way: remote wins where you're clean; both-touched = conflict, yours stays live, push blocked until git_resolve)."
    :inputSchema {:type "object"
                  :properties {:url {:type "string"} :token {:type "string"}
                               :branches {:type "array" :items {:type "string"}}}}}
   {:name "import_dir"
    :description "Absorb a DIRECTORY of files into this store as ordinary tracked form edits — 3-way against your last commit-point, same conflict handling and same done gate as git_pull, with NO git anywhere. For a zip, a scratch tree, or another tool's output. Paths the exported base never carried and that are not namespaces are left alone and noted."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"}}
                  :required ["dir"]}}
   {:name "git_conflicts"
    :description "Unresolved pull conflicts, with the raw remote content to merge from."
    :inputSchema {:type "object" :properties {}}}
   {:name "git_resolve"
    :description "Mark a pull conflict resolved (omit path = all). Unblocks git_push."
    :inputSchema {:type "object" :properties {:path {:type "string"}}}}])

(def cheat-sheet
  "slopp cheat-sheet — the one-page loop; help {topic} for a chapter
TOOLS:   14 families; an op is called through its family:
         read {op explore ops […]} · edit {op change prompt impl […]}.
         help {topic <op>} is an op's card. done and commit_point take no op.
TURNS:   automatic. A write carrying `prompt` opens its own turn; the prompt
         hook records your ask. turn_begin only when a refusal asks for it.
EXPLORE: your ask ARRIVES with its map (the [slopp] block above it) —
         current, don't re-read it. EVERY further question is ONE call:
         explore {ops [{op query_source targets […]} {op check code …}
         {op query_depends on ns/name} …]} — up to 6 questions at once.
         check runs assertion code in the image, NOTHING written — the
         find-something-out red as an ANSWER ({:pass :fail :assertions}).
         query_eval {code} is your REPL; query_observe captures real calls.
CHANGE:  every write is ONE call: change {prompt tests? impl accept?} —
         tests (OPTIONAL) land first and the result says which went RED
         (watched); impl lands; accepted shifts finish; one verification;
         one result. {prompt impl} alone is the ordinary write — a
         docstring, a comment, a fix. Touch existing forms with
         {action patch, ns, name, replace [{match source} …]} — deltas,
         never a whole-form retype; a step with no action is inferred
         (:replace if the form exists, :add if not). A red change lands
         NOTHING and loses nothing — fix forward from :test-src.
         edit_rename {ns from to}   <- never rename by editing call sites
         rename_sweep {from to dry_run true} first, then without
         edit_extract {ns from match name} · ns_create {ns requires?|source?}
         ns_add_require / module_dep  <- usually automatic; a refusal names the edge
         undo {deltas n} walks back your own writes
RULES:   form ORDER is derived — write forms in any order, never a (declare).
         red-first TDD = tests ride change {tests […]}, watched failing,
         then the impl in the same call — or explore {op check} FIRST when
         the test is a question you want answered before writing anything.
RESULTS: the result IS the check — do not re-read, restart or test_run after
         a green write. {:ok true :test {:ran :pass}} green · :failures = why
         (:implicated :attribution :expected :actual; :test-src = the failing
         test's source rides the red — the next call is the fix, not a read)
         · :warnings = fix per :suggest · :already-sent = you hold it ·
         :truncated = query_detail {id} only when the missing part changes
         what you do next
FINISH:  done {label} when the UNIT is finished — a unit may span
         change -> explore -> change, and the Stop hook runs done if you
         forget. full_check (whole store) and commit_point {label} (a
         commit-point) are the human's grain.
SHARE:   git_push {url?} · git_pull · config {key value?} (commit-point identity)
CLI:     every op, from a shell, routed to THIS running server (fast):
         slopp <op> '{…json…}' · whole-blob writes with RAW heredoc source:
         slopp add <ns> <<'EOF' …forms… EOF · slopp replace <ns/name> <<'EOF'
         slopp change --prompt '…' <<'EOF' with ;;;tests <ns> / ;;;impl <ns>
         sections — forms split per top level, add/replace inferred")

(def single-write-tools
  "The de-advertised write-and-batch ALIASES. None is described or
  advertised any more — `change` is the write door and `explore` the
  question door — but every name still DISPATCHES (the --call door, hooks,
  benchmarks, older scripts, and every docstring or refusal that spells
  one), and the write-shaped ones still gate as writes. The prose-names-a-
  real-tool check unions this set, so prose naming them stays valid."
  #{"edit_replace_form" "edit_add_form" "edit_group" "edit_subform"
    "edit_delete_form" "intent" "query_batch"})

(def write-tools
  ;; `change` is THE write verb (s11) and was missing here while its alias
  ;; `intent` was present — so the dispatcher skipped the turn gate, the
  ;; rotation and the read-ring flush for every change, and no eval since
  ;; s11 recorded a turn for its writes (s18 forensics). A write verb that
  ;; is not a write tool is silent provenance loss.
  (into single-write-tools
        ["change" "edit_group" "edit_delete_form" "edit_rename" "edit_extract"
         "ns_add_require" "ns_remove_require" "ns_create"
         "ns_delete" "done" "commit_point" "deps_add" "deps_remove"
         "deps_pure" "change_signature" "ns_realias"]))

(def wire-keys
  "Every key a write result may carry to the agent — ONE list, replacing the
  fourteen hand-maintained `select-keys` allowlists in `call-tool!`.

  **The union is safe, and that is the whole argument.** A key absent from a
  result is absent from the output whatever the allowlist says, so a per-tool
  list never protected anything — each was an independent guess at what that
  one operation returns. What they did instead was lose things: `:dry-run`'s
  payload, `:drift`, `:external-pending` and a `:fix` hint have each been
  built, tested and correct one layer down while the agent saw the old
  behaviour. `summarize`'s docstring has recorded that happening three times;
  the fourth is what produced this.

  Measured before consolidating: 14 lists, 39 distinct keys, and exactly TWO
  (`:error`, `:test`) appearing in all of them.

  Bulk payloads are not excluded here but by `summarize`, which strips
  `:source`/`:sources`/`:node` off deltas — a size concern, not a routing one,
  and it belongs where the shaping happens."
  #{;; refusals and the recovery they name
    :error :conflict :note :hint :suggestion :source-now :fix
    ;; what landed
    :delta :deltas :group :forms :affected :renamed :renamed-namespaces
    :mentions :changed-nses :reverted :skipped-shared :moved-to :moved :rewrote
    :callers :edges-declared :export-not-landed :export-note :shadowed :shadowed-note :callers-unrewritten
    :extracted :step :steps :auto-require :canonicalized :auto-module-dep :finisher :accept-unused :to-ns :keys :unknown-shape
    ;; what a realias moved, and what it declined to
    :sites :lib :left-behind :also-created :replaced :upgraded :merged-refer :closed :whole-store :commit :repaired
    ;; what it cost and whether to believe it
    :test :ms :untested :image-healed :red-first :red-first-arity :carried-errors
    :warnings :existing-warnings :advisories :drift :manual
    ;; a preview's whole point
    :dry-run :in-code :in-strings :in-files :files :files-on-disk :remaining :case-variants :rewritten :verify})

(defn classify
  "`entries` with `k` resolved — `default?` unless an entry already states its
  own.

  The classification belongs to the GROUP because the group is already the
  answer: `orientation-tools` is 20 reads, `edit-tools` is 23 writes. Measured
  across all six for both facts it carries today, only NINE tools disagree with
  their group about `:read-only` and TWELVE about `:image-free`, and those
  state it on themselves.

  Replaces a hand-kept set of name strings living three hundred lines from the
  descriptors it classified. Such a set was measured correct and had no
  mechanism keeping it so: adding a tool was two writes, and forgetting the
  second is the quietest failure here — the tool works, nothing goes red, and
  the client prompts for permission forever (`:read-only`) or waits for the
  image boot (`:image-free`).

  Takes the key rather than hardcoding one, because the second registry to
  collapse would otherwise arrive as a second copy of this function — which is
  the defect the first collapse was for."
  [entries k default?]
  (mapv #(update % k (fn [stated] (if (some? stated) stated default?))) entries))

(def ^:private classified
  "Every descriptor with its classifications resolved — the ONE list the wire
  payload, [[read-only-tools]] and [[image-free-tools]] all derive from.

  The group is the classification: orientation and history are reads that
  answer from the store value, the other four are writes that need the image.
  The tools that disagree carry the fact on themselves — NINE for
  `:read-only`, TWELVE for `:image-free`. Nothing downstream restates a tool
  name.

  **Two dials, not one.** Their per-group defaults happen to coincide, and
  their exceptions do not: `query_eval` is read-only (the observe gate blocks
  redefinition) and is NOT image-free (it evals IN the image). Merging them
  because today's defaults agree would put one switch on two facts."
  (into []
        cat
        [(-> orientation-tools (classify :read-only true)  (classify :image-free true))
         (-> history-tools     (classify :read-only true)  (classify :image-free true))
         (-> edit-tools        (classify :read-only false) (classify :image-free false))
         (-> flow-tools        (classify :read-only false) (classify :image-free false))
         (-> env-tools         (classify :read-only false) (classify :image-free false))
         (-> sync-tools        (classify :read-only false) (classify :image-free false))]))

(def read-only-tools
  "Tool names that never modify the STORE — advertised with the MCP
  readOnlyHint annotation so clients (Claude Code plan mode, permission
  systems) can auto-permit them instead of prompting.

  DERIVED from [[classified]], not maintained. It was a set of 32 name strings
  living three hundred lines from the descriptors it classified: adding a
  read-only tool was TWO writes, and forgetting the second is the quietest
  failure in this system — the tool works, no test goes red, and the client
  prompts for permission forever, which is indistinguishable from behaving
  correctly.

  `query_eval` and `query_observe` qualify because the observe gate blocks
  redefinition — the code they run cannot change the codebase (observation
  captures are a metadata cache). That is a judgement, which is why the fact is
  DECLARED on the tool rather than inferred from the dispatch."
  (into #{} (comp (filter :read-only) (map :name)) classified))

(def registry
  "Every tool descriptor the server advertises — derived from [[classified]];
  read-only tools carry the MCP readOnlyHint annotation so plan-mode clients
  auto-permit them.

  The classification markers are STRIPPED here. `slopp.mcp/handle!` answers
  `tools/list` with this value serialized as-is, so a descriptor must carry no
  key the protocol does not define — `:read-only` exists to produce the
  annotation and `:image-free` to gate the dispatch during the async image
  boot, and neither has any business on the wire."
  (mapv (fn [t]
          (cond-> (dissoc t :read-only :image-free)
            (:read-only t) (assoc :annotations {:readOnlyHint true})))
        classified))

(def image-free-tools
  "Tools that answer from the STORE VALUE + in-process analysis alone — they
  touch neither the owned image nor a write path, so the MCP dispatch serves
  them WITHOUT waiting for the async image boot (the server claims ready as
  soon as the store loads; orientation and reading are instant). Everything
  else — the oracle tools (query_eval/query_call/query_observe/
  query_macroexpand/query_store, which eval in the image) and every write —
  `api/await-image!`s the boot first.

  DERIVED from [[classified]], not maintained. It was a set of 25 name strings
  living three hundred lines from the descriptors it classified: adding a
  store-value read was TWO writes, and forgetting the second is silent — the
  tool works and merely waits for a boot it never needed.

  Being CONSERVATIVE is still safe and still the rule, but it is now stated per
  tool rather than by omission: a tool marked `:image-free false` waits for the
  boot; one wrongly marked true would touch a not-yet-live image. `store_doctor`
  and `store_health` are deliberately NOT image-free even though they only read
  — the conservative side of a judgement, which is worth being able to see on
  the entry."
  (into #{} (comp (filter :image-free) (map :name)) classified))

(defn accepted-arg-keys
  "The full set of argument keys tool `name` accepts: its inputSchema
   properties plus the universal cross-cutting keys (:agent stamped by the
   dispatch, :prompt intent, :verbose full payload). There are no aliases —
   one argument has one spelling, and the schema is it. nil for a name the
   server does not advertise (an unknown name) — that tool opts OUT of strict validation rather than
   refusing every key."
  [name]
  (when-let [d (some #(when (= name (:name %)) %) registry)]
    (into #{:agent :prompt :verbose}
          (keys (get-in d [:inputSchema :properties])))))

(defn unknown-arg-keys
  "The keys in `arguments` that tool `name` does not accept — the ones the
   dispatch would otherwise silently DROP. Returns a seq, or nil when the tool
   opts out (see accepted-arg-keys) or every key is accepted. The wire refuses
   a call carrying any, so a mistyped or unsupported argument fails loudly
   instead of evaporating into a no-op (or, for a safety flag, its opposite)."
  [name arguments]
  (when-let [acc (accepted-arg-keys name)]
    (seq (remove acc (keys arguments)))))

(defn missing-required-keys
  "The keys the registry descriptor for `name` marks `:required` that
  `arguments` does not carry — nil when none, or for a name the registry
  lacks. The client used to catch these locally against the op's own
  schema; under the family surface only `op` can be required there, so the
  server says by name what is missing (slopp-ui named the trade)."
  [name arguments]
  (when-let [d (some #(when (= name (:name %)) %) registry)]
    (seq (remove #(some? (get arguments (keyword %)))
                 (get-in d [:inputSchema :required])))))

(def families
  "The advertised surface: fourteen families, each an INDEX of the ops it
  holds. `op` is the operation's existing REGISTRY name — every refusal,
  docstring, skill line and mailbox message that spells `edit_subform` stays
  exactly right; only the packaging moved. eval10 measured why: 96 advertised
  tools (~64k chars of descriptions) were all DEFERRED by the client, and the
  agent paid 19–22 ToolSearch turns per lifetime cell to find them, plus the
  choosing cost of a 96-name list. `done` and `commit_point` keep their own
  names: they are the loop's two verbs. `slopp` is the remainder, named as
  such rather than pretending to be a cluster (slopp-ui, 2026-08-30)."
  [{:name "orient" :blurb "Where to start: the forms that matter for an ask, ranked with why; or the project brief." :ops ["orient" "session_brief"]}
   {:name "read" :blurb "Read code by form, never by file: MANY questions in one explore call, an in-image check, one form with what it reaches, a search, the outline, a spooled remainder." :ops ["explore" "check" "query_slice" "query_source" "query_brief" "query_detail" "query_search" "query_project"]}
   {:name "depends" :blurb "What reaches what: the call path between two forms or the neighbourhood around one (bodies on the way), callers and callees, the module graph, a macro expansion." :ops ["query_flow" "query_depends" "query_call" "query_macroexpand"]}
   {:name "history" :blurb "What changed, why and when: form history, intents, commit-points, git, branches." :ops ["query_history" "query_changes" "query_commits" "query_git" "query_branches" "report" "file_history"]}
   {:name "eval" :blurb "The live oracle: evaluate, observe a fn's real calls, query the store value." :ops ["query_eval" "query_observe" "query_store"]}
   {:name "edit" :blurb "Verified writes: a whole unit of work — or any slice of one — as ONE change call; bookkeeping ops beside it." :ops ["change" "edit_comment" "edit_revert" "undo" "episode_revert"]}
   {:name "refactor" :blurb "Transformations the tool derives from ONE intent: renames with their callers, extraction, signatures, moves." :ops ["rename_sweep" "edit_rename" "edit_extract" "edit_requalify" "change_signature" "edit_move_forms" "module_extract" "ns_rename" "ns_realias" "cleanup"]}
   {:name "declare" :blurb "Namespaces, requires, module edges and dials, dependencies." :ops ["ns_create" "ns_delete" "ns_add_require" "ns_remove_require" "module_dep" "module_purity" "module_role" "module_platform" "deps_add" "deps_remove" "deps_list" "deps_pure" "js_dep"]}
   {:name "verify" :blurb "A bigger question than one write answers: chosen tests, the whole store, a fresh image, a review, a drafted test, a rendered screen." :ops ["test_run" "full_check" "restart" "review_scan" "draft_test" "screen"]}
   {:name "build" :blurb "Artifacts: the jar's sources, the browser bundle, a generated client, the dev server." :ops ["build" "compile_client" "generate_client" "ui_serve"]}
   {:name "store" :blurb "The store itself: health, doctor, compaction, config, and what it declares (capabilities, rules, vocabulary, surface, telemetry, cost)." :ops ["store_health" "store_doctor" "store_compact" "config" "config_file" "query_capabilities" "query_rules" "query_vocabulary" "query_surface" "query_rule_telemetry" "query_cost"]}
   {:name "slopp" :blurb "The remainder, named as such: git, branches, threads, files, turns, help." :ops ["git_push" "git_clone" "git_pull" "git_conflicts" "git_resolve" "import_dir" "branch_create" "branch_switch" "branch_merge" "branch_delete" "thread_list" "thread_drop" "merge_from" "file_put" "file_get" "file_list" "file_remove" "turn_begin" "turn_end" "help"]}
   {:name "done" :ops ["done"]}
   {:name "commit_point" :ops ["commit_point"]}])

(defn- op-line
  "One index line for a registry descriptor: `op {required [optional]} — the
  first sentence of its description`, capped so 96 lines stay one screen."
  [{:keys [name description inputSchema]}]
  (let [req  (vec (:required inputSchema))
        opt  (vec (sort (remove (set req) (map clojure.core/name (keys (:properties inputSchema))))))
        s    (first (str/split (or description "") #"(?<=[.!?])\s" 2))
        s    (if (> (count s) 110) (str (subs s 0 107) "…") s)]
    (str name " {" (str/join " " req)
         (when (seq opt) (str (when (seq req) " ") "[" (str/join " " opt) "]"))
         "} — " s)))

(defn- merge-property
  "Two ops' schemas for the same key, as one property: identical stays as
  is; different TYPES become a JSON-schema type array so a client that
  validates locally accepts either (`edit_subform`'s `text` is a boolean,
  `edit_comment`'s a string). Descriptions keep the first op's."
  [a b]
  (if (= a b)
    a
    (let [types (fn [x] (let [t (:type x)] (if (vector? t) t (if t [t] []))))]
      (assoc (merge b a) :type (vec (distinct (concat (types a) (types b))))))))

(defn family-descriptor
  "The advertised descriptor for one family: a single-op family IS its op's
  registry descriptor (no `op` to pass); a multi-op family's description is
  the blurb plus one [[op-line]] per op, its schema `op` (enum, required)
  plus the union of its ops' properties — the union is for clients that
  validate locally — and it carries readOnlyHint only when every op does."
  [{:keys [name blurb ops]}]
  (let [members (mapv (fn [op] (or (some #(when (= op (:name %)) %) registry)
                                   (throw (ex-info (str "family " name " names an op the registry lacks: " op)
                                                   {:family name :op op}))))
                      ops)]
    (if (= 1 (count members))
      (first members)
      (cond-> {:name name
               :description (str blurb " Call with op = the operation's name."
                                 " Each line: op {required [optional]} — what it does;"
                                 " help {topic op} is the full card.\n"
                                 (str/join "\n" (map op-line members)))
               :inputSchema {:type "object"
                             :properties (reduce (fn [acc m]
                                                   (merge-with merge-property acc
                                                               (get-in m [:inputSchema :properties])))
                                                 {:op {:type "string" :enum ops}}
                                                 members)
                             :required ["op"]}}
        (every? #(get-in % [:annotations :readOnlyHint]) members)
        (assoc :annotations {:readOnlyHint true})))))

(def tools
  "Every tool descriptor the server ADVERTISES — the fourteen [[families]],
  derived from [[registry]] by [[family-descriptor]]. The registry stays the
  validation and dispatch surface: an op called by its own name still
  dispatches (the `--call` door, the hooks), it is just not advertised."
  (mapv family-descriptor families))

(def card-ops
  "The ops whose argument cards ride the bundle under the schema diet — the
  ten that covered ~95% of every call the s11-s13 eval cells made (census
  over 12 cells, both models, both terrains). Not a capability list: every
  other op still dispatches and `help {topic op}` still teaches it; these
  are the calls worth PREPAYING."
  ["change" "query_source" "explore" "done" "ns_create"
   "rename_sweep" "test_run" "report" "full_check" "query_search"])

(def op-cards
  "The argument-teaching block the bundle carries under the schema diet:
  one line per [[card-ops]] op — `name {required, [optional…]} — first
  sentence` — derived from [[registry]] so a card can never drift from what
  validates, plus a SHAPE line for the two ops whose arguments are
  structures (the change's steps, the namespace's two modes): eval28
  measured `help change` once a step in most opus cells, seven turns over
  three, because the card named the arguments and not what goes inside
  them. ~0.8k tokens standing in for the ~4k of family prose the diet
  sheds: the s13 law made the trade explicit — a schema is prepaid argument
  TEACHING, and teaching can ride the cheaper channel."
  (str/join
   "\n"
   (for [op card-ops
         :let [d (some #(when (= op (:name %)) %) registry)]
         :when d]
     (let [req  (set (get-in d [:inputSchema :required]))
           prop (map clojure.core/name (keys (get-in d [:inputSchema :properties])))
           args (str/join ", " (concat (sort (filter req prop))
                                       (map #(str "[" % "]")
                                            (sort (remove req prop)))))
           sent (first (str/split (str (:description d)) #"(?<=\.) " 2))
           shape (case op
                   "change" (str "\n    steps: [{ns name source [action add|replace|delete|patch|ns_create]} …] — action inferred"
                                 " (replace when the name exists, add when not); patch = {action patch, ns, name,"
                                 " replace [{match source [text true]} …]} for deltas inside one form; {ns requires}"
                                 " with no source creates the namespace; a call as lib/fn is stored as the project's alias."
                                 " tests land first (watched red), impl lands, done \"label\" + commit true on the last change close the unit.")
                   "ns_create" "\n    {ns source} lands a whole namespace; {ns requires [\"[lib :as a]\" …]} scaffolds one; both merge; on an existing namespace, requires are added."
                   "")]
       (str op " {" args "} — " (subs (str sent) 0 (min 240 (count (str sent)))) shape)))))

(defn remap-arguments
  "REPAIR before REFUSE. The unambiguous argument SHAPES the s17 census
  measured agents sending — and retrying after the refusal named the fix —
  rewritten to the shape they meant: `change {ns name source}` with no
  `:impl` is that one impl step; `query_slice {targets}` is a
  `query_source`; `query_changes {ns name}` is the form's history;
  `query_changes {since}` is `:from`; `query_commits {limit}` drops the
  key; `ns_add_require {requires}` is `:require`; `query_slice {ns}` alone is the
  namespace read; `query_commits {contains}` is `report` (eval22 step 2).
  eval24 opus: `query_depends {reach}` is `query_flow`'s question, `{sym}`
  is `:on`, a sibling op's key (`collapse`, `format`) is dropped,
  `query_brief {ns}` alone is the namespace read, and a `*_note` key is the
  caller's annotation to itself. eval25 opus: the two require ops take
  either key name (`lib` / `require`) — they were one argument under two
  spellings and cost a turn each way. Returns {:name :arguments :repaired} —
  :repaired nil when nothing moved, else a small map the result carries so
  the accepted shape is learned for free. A shape with TWO readings
  (top-level ns beside an :impl) is left for the refusal, which still names
  the accepted keys: refusals are for rules, spellings are repaired."
  [name arguments]
  (let [a0         (or arguments {})
        ;; a note key first, for ANY op: `op_note`, `why-note` — the caller
        ;; annotating its own call, never an argument
        ;; … and a harness's own flag (`run_in_background`) is never an argument
        notes      (vec (filter #(re-find #"[_-]note$|^run_in_background$" (clojure.core/name %)) (keys a0)))
        a          (apply dissoc a0 notes)
        noted      (fn [r] (cond-> r
                             (seq notes) (update :repaired #(update (or % {}) :dropped (fnil into []) notes))))
        write-keys [:ns :name :source :action :code :match :text :where :require]
        top        (select-keys a write-keys)]
    (noted
     (cond
       (and (#{"change" "intent"} name) (seq top) (nil? (:impl a)))
       {:name name
        :arguments (assoc (apply dissoc a write-keys) :impl [top])
        :repaired {:moved-into-impl (vec (keys top))}}

       (and (= "query_slice" name) (:targets a))
       (let [keep    (select-keys a [:targets :full :resend :prompt :agent :verbose])
             dropped (vec (remove (set (keys keep)) (keys a)))]
         {:name "query_source" :arguments keep
          :repaired (cond-> {:routed "query_source"}
                      (seq dropped) (assoc :dropped dropped))})

       (and (= "query_changes" name) (or (:ns a) (:name a)))
       {:name "query_history"
        :arguments (select-keys a [:ns :name :at :format :limit :agent :prompt :verbose])
        :repaired {:routed "query_history"}}

       (and (= "query_changes" name) (:since a))
       {:name name :arguments (assoc (dissoc a :since) :from (:since a))
        :repaired {:renamed {:since :from}}}

       ;; a sibling op's key: `collapse` is query_history's, `format` is
       ;; query_changes' — dropped, and the result says so
       (and (= "query_changes" name) (contains? a :collapse))
       {:name name :arguments (dissoc a :collapse) :repaired {:dropped [:collapse]}}

       (and (= "report" name) (contains? a :format))
       {:name name :arguments (dissoc a :format) :repaired {:dropped [:format]}}

       (and (#{"query_commits" "query_git"} name) (contains? a :limit) (nil? (:contains a)))
       {:name name :arguments (dissoc a :limit) :repaired {:dropped [:limit]}}

       ;; the namespace read: `query_slice {ns}` / `query_brief {ns}` with no
       ;; name names the whole namespace, which is query_source's answer
       (and (#{"query_slice" "query_brief"} name) (:ns a) (nil? (:name a)) (nil? (:targets a)))
       (let [keep    (select-keys a [:ns :full :resend :prompt :agent :verbose])
             dropped (vec (remove (set (keys keep)) (keys a)))]
         {:name "query_source" :arguments keep
          :repaired (cond-> {:routed "query_source"}
                      (seq dropped) (assoc :dropped dropped))})

       ;; `reach` is query_flow's question — callers and callees around one
       ;; form, bodies on the way — asked of query_depends
       (and (= "query_depends" name) (contains? a :reach))
       {:name "query_flow"
        :arguments (-> (select-keys a [:on :reach :sym :prompt :agent :verbose])
                       (cond-> (:sym a) (-> (assoc :on (:sym a)) (dissoc :sym))))
        :repaired {:routed "query_flow"}}

       (and (= "query_depends" name) (:sym a) (nil? (:on a)))
       {:name name :arguments (assoc (dissoc a :sym) :on (:sym a))
        :repaired {:renamed {:sym :on}}}

       ;; `contains` is report's key: a commit list filtered by what it
       ;; contains IS the store-wide history question report answers
       (and (= "query_commits" name) (:contains a))
       {:name "report"
        :arguments (select-keys a [:contains :since :limit :agent :prompt :verbose])
        :repaired {:routed "report"}}

       (and (= "ns_add_require" name) (:requires a) (nil? (:require a)))
       (let [r   (:requires a)
             one (if (and (sequential? r) (= 1 (count r))) (first r) r)]
         (if (string? one)
           {:name name :arguments (assoc (dissoc a :requires) :require one)
            :repaired {:renamed {:requires :require}}}
           {:name name :arguments a}))

       ;; one argument, two spellings: the add op says :require, the remove
       ;; op says :lib, and a model that just used one sends it to the other
       (and (= "ns_add_require" name) (:lib a) (nil? (:require a)))
       {:name name :arguments (assoc (dissoc a :lib) :require (:lib a))
        :repaired {:renamed {:lib :require}}}

       (and (= "ns_remove_require" name) (:require a) (nil? (:lib a)))
       {:name name :arguments (assoc (dissoc a :require) :lib (:require a))
        :repaired {:renamed {:require :lib}}}

       :else {:name name :arguments a}))))

(def verb-aliases
  "The two verbs the skill teaches — `explore`, `change` — as TOOL NAMES
  beside the families. Dispatch by op name always worked (`call-tool!`
  falls through to the op); what refused was the harness: opus called
  `mcp__…__explore` thirteen times across the s17 census cells and got
  \"No such tool available\" back each time, because the verb it was taught
  to reach for was filed under `read`. Thin schemas on purpose (the op
  cards on the bundle carry the argument teaching): under 1k chars against
  the dieted surface, measured, so the advertised list stays undeferrable.

  NOT `report`. It was advertised here for one grid (s17) and cost sonnet
  twelve and thirteen extra turns on the handoff step — `report {contains
  X}` per feature, then a `query_history` per form — where the bundle's
  injected handoff had answered in six. A reachable verb invites the
  drill-down the injection exists to pre-empt; `history {op report}` stays
  one hop away, which is the right distance."
  [{:name "explore"
    :description "= read {op explore}: several READ questions, one call — ops [{op …args} …]; help {topic explore} is the full card."
    :inputSchema {:type "object"
                  :properties {:ops {:type "array" :items {:type "object"}}}
                  :required ["ops"]}
    :annotations {:readOnlyHint true}}
   {:name "change"
    :description "= edit {op change}: tests land first (watched red), impl lands, ONE verification — {prompt tests? impl accept?}; argument cards ride the [slopp] block on your ask."
    :inputSchema {:type "object"
                  :properties {:prompt {:type "string"}
                               :tests {:type "array" :items {:type "object"}}
                               :impl {:type "array" :items {:type "object"}}
                               :accept {:type "array" :items {:type "string"}}}
                  :required ["prompt"]}}])

(def dieted-tools
  "[[tools]] with the op-index PROSE relocated: each multi-op family keeps
  its name, every schema and op enum (the structural half, which clients
  validate against), and a one-line description pointing at the bundle's
  op cards and `help {topic op}`. Measured before the diet: 24.4k advertised
  chars, 15.6k of them description prose — rent on every request of every
  session, teaching that the bundle can carry for a fraction. Plus the
  three [[verb-aliases]] — the names agents reach for unprompted."
  (into (mapv (fn [t]
                (if-let [ops (get-in t [:inputSchema :properties :op :enum])]
                  (assoc t :description
                         (str "ops: " (str/join " " ops)
                              " — argument cards ride the [slopp] block on your ask;"
                              " help {topic <op>} is one op's full card."))
                  t))
              tools)
        verb-aliases))
