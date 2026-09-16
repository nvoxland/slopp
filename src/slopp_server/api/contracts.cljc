(ns slopp-server.api.contracts
  "Wire contracts for the reviewer UI's JSON API — the shapes `/api/*`
  promises and the generated client validates against.

  `:cljc` on purpose, and the reason is the whole point of D-web-contracts:
  the server enforces these and the generated client enforces the SAME vars,
  so a drift is a write-time finding rather than a runtime surprise in a
  browser. A schema var that lived in a `:clj` namespace could not compile
  into the client, and `resolve-schema-ref` refuses one for exactly that.

  Plain malli DATA, so no require is needed to hold them — a schema is a
  vector, and only validating one needs malli.

  Everything crossing this boundary is JSON, which has no symbols: a
  namespace name is a `:string` here even though the store holds a symbol.
  Saying `:symbol` would describe the store rather than the wire, and the
  client would be validating against a shape that cannot arrive.")

(def namespace-row
  "One namespace on the wire: its name and how many named forms it holds."
  [:map
   [:ns {:doc "the namespace's full name"} :string]
   [:forms {:doc (str "how many NAMED forms it holds — the ns form and anonymous"
                      " top-level forms are not counted")} :int]])

(def namespace-list
  "`GET /api/namespaces` — every namespace, sorted.

  Composed from [[namespace-row]] rather than restating it: a schema var is
  an ordinary var, so this composition is a REAL reference edge, and changing
  the row shows up in its blast radius."
  [:sequential namespace-row])

(def form-row
  "One form in an outline: what it is called, what KIND of form it is, what it
  takes, whether it is private, its docstring's first line, any schema it
  declares, and the facts a consumer needs to RANK it against its neighbours.
  Enough to render the namespace INSTEAD of the source, which is the job this
  row exists for.

  `:maybe` on `:doc`, `:sig` and `:schema` because plenty of forms have none —
  a `def` has no signature at all — and a contract that could not say so would
  refuse legitimate data. `:private?` is a plain boolean rather than `:maybe`:
  an absent key and a public var render identically, and only one of them is a
  finding.

  `:sig` is a SEQUENTIAL, one string per arity, so a consumer can stack a
  multi-arity the way source stacks it. Joining them is something the reader
  can do and cannot undo, so the wire carries the separable form.

  The ranking half — `:mass`, `:calls`, `:callers-out`, `:effectful?`,
  `:exported?` — is FACTS and deliberately not a score. Weighting them into
  an importance number, and bucketing that number into perceptible steps, is
  drawing, and a consumer has to be able to tune it without a slopp release.
  Same split `module-index` makes by shipping layers rather than a laid-out
  picture.

  All five are required for the same reason `:private?` is. A `:calls` that
  is absent and a form that calls nothing draw identically; so do a missing
  `:callers-out` and a form nothing outside uses. Required is also what makes
  a field that stops being sent a red test here rather than a nil in someone
  else's pane — this contract is what the typed client is generated from, and
  `m/validate` passes an OPEN map, so a key the schema does not name is a key
  nothing checks."
  [:map
   [:name {:doc "the form's own name, unqualified"} :string]
   [:form-id {:doc (str "its stable address in the store — survives renames and"
                        " moves, which the name does not")} :string]
   [:kind {:doc "the defining head: defn, def, deftest, defmulti, ns …"} :string]
   [:sig {:doc (str "arglists, ONE STRING PER ARITY and unjoined, so a consumer can"
                    " stack a multi-arity the way source does. Joining is something"
                    " a reader can do and cannot undo. nil when the form has none —"
                    " a def has no signature at all")}
    [:maybe [:sequential :string]]]
   [:private? {:doc "true when the var is private"} :boolean]
   [:doc {:doc "the docstring's first line, nil when there is none"} [:maybe :string]]
   [:schema {:doc "the schema this form declares, nil when it declares none"} [:maybe :string]]
   [:mass {:doc (str "the form's size as a NODE COUNT over its sexpr — not lines and"
                     " not characters. Over the sexpr a docstring is one node, so"
                     " the body's structure dominates; by lines a 40-line docstring"
                     " over a 30-line body makes the documentation win")} :int]
   [:calls {:doc (str "the SAME-NAMESPACE forms this one calls, direct edges only,"
                      " sorted. From the reference graph, so a form reached through"
                      " a carrier position counts — resolved calls alone draw"
                      " dispatch targets as leaves, and those matter most. Empty"
                      " rather than absent: a leaf is an answer")}
    [:sequential :string]]
   [:callers-out {:doc (str "how many forms OUTSIDE this namespace call it, counting"
                            " PRODUCTION namespaces only — fan-in, the blast radius")}
    :int]
   [:callers-out-test {:doc (str "the same count over TEST namespaces, kept separate"
                                 " because one integer cannot be taken apart again."
                                 " Measured, not reasoned: as a single number it was"
                                 " ranking by test count — ten of twelve outside"
                                 " callers were deftests, and the entry point that IS"
                                 " the render sat fourth on its one real caller")}
    :int]
   [:effectful? {:doc (str "true when the form performs effects — what this form"
                           " DOES, as against the namespace's :tier, which is what"
                           " it is allowed to do")} :boolean]
   [:exported? {:doc "true when the form is part of its module's public surface"} :boolean]])

(def token
  "One syntax token: `[\"keyword\" \":http/path\"]`.

  A PAIR, not markup. The server walks the CST it already holds and sends
  classes and text; the client turns them into elements. That is the line the
  whole SPA rewrite is organised around — the server never decides what an
  element is, and the client never needs a lexer to find out.

  The invariant the model's specs pin: concatenating every `text` reproduces
  the source exactly, so a form renders from tokens alone."
  [:tuple :string :string])

(def timeline
  "`GET /api/timeline` — commit-points newest first, plus the working set."
  [:map
   [:commit-points {:doc "commit-points, NEWEST FIRST — render in the order given"}
    [:sequential [:map
                  [:commit {:doc (str "the commit-point's delta id, e.g. d24976 — this"
                                      " store's own address for it, not a git sha")} :string]
                  [:description {:doc "what the commit-point was recorded as achieving"} :string]
                  [:at {:doc "when it was recorded, formatted — \"2026-08-06 00:38\""} :string]
                  [:status {:doc (str "the verdict the commit-point was recorded under,"
                                      " mirroring its done-point — \"green\" on every"
                                      " commit-point in this store, and not declared as an"
                                      " enum because a red one is expressible and this"
                                      " store has never produced one to check against")} :string]
                  [:range {:optional true
                           :doc (str "from..to, the delta span this commit-point covers,"
                                     " which is what /api/change/:range takes. Absent"
                                     " on the first commit-point, which has no predecessor")} :string]
                  [:more-lines {:optional true
                                :doc (str "how many lines of :description were cut. Present"
                                          " only when it was capped, so its absence means"
                                          " you have the whole thing")} :int]
                  [:agent {:optional true
                           :doc "who recorded it; absent on commit-points written before agents were tracked"} :string]
                  [:sha {:optional true
                         :doc (str "the git sha this commit-point was projected to. Present"
                                   " only for commit-points whose DELTA carries one — this"
                                   " model is a pure fold and never opens the projection"
                                   " to go looking")} :string]]]]
   [:working {:doc (str "the work since the newest commit-point — what is written and"
                        " NOT yet committed. This is the field with no counterpart"
                        " in a git-shaped timeline: it is uncommitted work that is"
                        " nonetheless recorded, verified and addressable")}
    [:map
     [:since {:doc (str "the delta this set is measured AFTER — the newest commit-point's"
                        " id, or \"log-start\" when there is no commit-point yet. Every"
                        " other number here is relative to it, so a consumer showing"
                        " the counts without it is showing a figure with no baseline")} :string]
     [:forms {:doc "how many forms have been touched since :since"} :int]
     [:namespaces {:doc "the namespaces those forms are in"} [:sequential :string]]
     [:prompts {:doc (str "the recorded WHYs of those writes — the asks that produced"
                          " them, in order. Intent, not a commit message: nobody"
                          " wrote these to be read later. CAPPED; :forms is exact")}
      [:sequential :string]]
     [:more-prompts {:optional true
                     :doc (str "how many asks were left out of :prompts. Present only"
                               " when the list was capped, so its absence means you"
                               " have all of them")} :int]]]])

(def change-view
  "`GET /api/change/:range` — one commit-point reviewed, grouped module then
  namespace, with a count at every rung.

  The diff arrives as LINES (`[\"-(defn f [])\" \"+(defn f [x])\"]`), not as
  rendered markup — the client decides that a `-` line is a `.del` element.
  Same discipline as [[token]]: the server sends what changed, never how it
  should look."
  [:map
   [:from {:doc "the delta id this range starts AFTER — exclusive"} :string]
   [:to {:doc "the delta id this range ends at — inclusive, and usually a commit-point"} :string]
   [:count {:doc "how many forms changed across the whole range"} :int]
   [:modules {:doc (str "the changes grouped module then namespace, with a count at"
                        " every rung so a consumer can render a collapsed tree"
                        " without summing anything itself")}
    [:sequential [:map
                  [:module {:doc "the module name"} :string]
                  [:count {:doc "forms changed in this module"} :int]
                  [:namespaces {:doc "the namespaces within this module that changed"}
                   [:sequential [:map
                                 [:ns {:doc "the namespace's full name"} :string]
                                 [:count {:doc "forms changed in this namespace"} :int]
                                 [:forms {:doc "one entry per changed form"}
                                  [:sequential
                                   [:map
                                    [:form {:doc "the form's qualified name"} :string]
                                    [:form-id {:doc "its stable address in the store"} :string]
                                    [:status {:doc "what happened to it in this range"}
                                     [:enum "added" "modified" "deleted"]]
                                    [:why {:optional true
                                           :doc (str "the recorded ask behind the change."
                                                     " Absent when the write carried none")} :string]
                                    [:callers {:doc (str "how many distinct forms call it NOW —"
                                                         " a floor, since the graph is a"
                                                         " syntactic reader")} :int]
                                    [:diff {:doc (str "the change as [CLASS TEXT] pairs — class is"
                                                      " \"same\", \"add\" or \"del\", text is the line."
                                                      " Never rendered markup: the server says what"
                                                      " changed, and a \"del\" line becoming a .del"
                                                      " element is yours")}
                                     [:sequential [:tuple :string :string]]]]]]]]]]]]
   [:arc {:doc (str "the range's RED/GREEN arc, oldest first: one entry per"
                    " verification recorded in the range. Zero failures throughout"
                    " means the work never went red — which for a range that ADDED"
                    " assertions is itself a finding, since a test nobody watched"
                    " fail is a test nobody has evidence for")}
    [:sequential [:map
                  [:delta {:doc "the delta that verification ran at"} :string]
                  [:fail {:doc "failures and errors, summed — zero is green"} :int]
                  [:tests {:optional true
                           :doc (str "how many tests that run covered — the DENOMINATOR."
                                     " Without it a failure count cannot be read: 3 of 12"
                                     " and 3 of 2001 are the same number and different"
                                     " news. Absent on entries recorded before it was"
                                     " carried, which is not the same as a suite of zero")} :int]
                  [:pass {:optional true :doc "assertions that passed in that run"} :int]
                  [:ms {:optional true :doc "what the run cost in wall time"} :int]]]]])

(def form-source
  "`GET /api/source/:ns/:name` — one form's source text.

  Addressed by NAME because that is what a namespace outline knows, where
  [[form-view]] is addressed by the stable id. Both exist on purpose: the id
  is the permalink, the name is the path you arrive by."
  [:map
   [:ns {:doc "the namespace the form is in"} :string]
   [:name {:doc "the form's own name, unqualified"} :string]
   [:form-id {:doc (str "the form's stable store id — the address that survives a"
                        " rename, where this endpoint's own :ns/:name does not."
                        " It is HERE and deliberately not in the published contract"
                        " document: a form id exists only for a producer whose code"
                        " lives in a store, and this endpoint is already store-only,"
                        " so it costs the portable document nothing")} :string]
   [:source {:doc (str "the form's source text, RENDERED from the store rather than"
                       " read from a file — there is no file. Canonical formatting,"
                       " so it is the same text every caller gets")} :string]])

(def form-request
  "`GET /api/projects/:slug/form/:id` — what a caller SENDS.

  `:id` is interpolated into the path; `:view` and `:depth` travel as query
  parameters, and nothing here says so — the generated client reads the
  METHOD. `:rest/request` means what the caller sends, and a GET sends a query
  string for the same reason a POST sends a body.

  It exists because without it the generated wrapper takes a params map and
  only the path ever reads from it, so `?depth=` answered correctly on the wire
  and was unreachable through the client — which pushes a consumer toward
  hand-rolling a fetch, the exact thing `direct-http` refuses and the typed
  client exists to prevent.

  Both modifiers are `:optional`, and that is the compatibility promise: a
  wrapper called with only `:id` sends no query string at all, which is the
  request every link written before these existed already made."
  [:map
   [:slug {:doc "the project slug — the open project to read; interpolated into the PATH"} :string]
   [:id {:doc (str "the form's stable id — interpolated into the PATH, not sent as a"
                   " query parameter, and the only required part of the address")} :string]
   [:view {:optional true
           :doc (str "the rendering fidelity to build the response at; travels as a"
                     " query parameter. Omit for the default — a wrapper called with"
                     " only :id sends no query string at all, which is the request"
                     " every link written before these existed already made")} :string]
   [:depth {:optional true
            :doc (str "how far to follow the call graph for :callers and :callees;"
                      " query parameter. Omit for the default")} :int]])

(def gaps
  "Where a subject is about to be THIN — counts, never rows.

  A form with no recorded why and no covering test renders identically to one
  with both, so a diagram cannot point at its own weak spots: silence reads the
  same as coverage. These are the four numbers that let a consumer tint the
  EXISTING layout, which is what makes an overlay toggleable — rows would
  reflow it and a reflow is a different picture, not the same picture with a
  finding on it.

  One schema for all three carriers (a module row, a module's namespace row, a
  namespace outline) so the same four numbers cannot be described three ways.

  `:no-doc` is every named form without a docstring, which is NOT the
  `missing-doc-warning` advisory — that one asks whether to NAG (public module
  surface only) and this asks whether a reader can learn what a form is without
  opening it. `:no-why` is the absent WRITE PROMPT, the ask that produced the
  form. `:uncovered` is measured against the SESSION's trace map, so a process
  that has run nothing reports everything uncovered rather than a zero that
  would read as coverage."
  [:map
   [:forms {:doc "how many named forms the subject holds — the denominator the other three are counted against"} :int]
   [:no-doc {:doc (str "of those, how many carry no docstring. NOT the missing-doc"
                       " advisory, which asks whether to nag about public module"
                       " surface — this asks whether a reader can learn what a"
                       " form is without opening it")} :int]
   [:no-why {:doc (str "how many have no recorded WHY — the write prompt, the ask"
                       " that produced the form. Absent means nobody said why it"
                       " exists, which no amount of reading the code recovers")} :int]
   [:uncovered {:doc (str "how many no test has been OBSERVED exercising, measured"
                          " against this session's trace — so a process that has"
                          " run nothing reports everything uncovered rather than a"
                          " zero that would read as coverage")} :int]])

(def ns-outline
  "`GET /api/ns/:ns` — one namespace's forms in store order, and what tests it.

  `:tested-by` is always present and empty rather than absent when nothing
  covers the namespace: an absent key and an untested namespace would render
  identically, and the second is a finding worth showing.

  `:tier` is the NAMESPACE's effective purity tier, most-specific declaration
  winning — a claim about what this namespace is ALLOWED to do, which is a
  different grain from a row's `:effectful?` (what that form actually does).
  Always present for the same reason `:tested-by` is: undeclared resolves to
  `\"external\"`, so there is no \"nobody said\" for an absent key to mean, and a
  consumer badging on it would otherwise have to invent a fourth state."
  [:map
   [:ns {:doc "the namespace's full name"} :string]
   [:tier {:doc (str "this NAMESPACE's effective purity tier, most-specific"
                     " declaration winning — what it is ALLOWED to do, which is a"
                     " different grain from a form row's :effectful? (what one form"
                     " actually does). Undeclared resolves to \"external\", so there"
                     " is no fourth state for a consumer to invent")}
    [:enum "pure" "internal" "external"]]
   [:forms {:doc (str "its forms in STORE order — the order they load in, which is"
                      " the order they were written and not alphabetical. A"
                      " consumer that sorts them loses the only ordering the store"
                      " has an opinion about")}
    [:sequential form-row]]
   [:tested-by {:doc (str "the test namespaces covering this one. Always present and"
                          " EMPTY rather than absent when nothing does: an absent key"
                          " and an untested namespace would render identically, and"
                          " the second is a finding worth showing")}
    [:sequential :string]]
   [:gaps {:doc "where this namespace is thin"} gaps]])

(def module-row
  "One module in the Code nav: its production namespaces, how many test
   namespaces fold into it, its declared purity tier, whether it was found to
   be foundation, and what it depends on.

   `:namespaces` holds production names only and `:tests` is a COUNT, not a
   list — a `-test` namespace is not a peer of the code it covers, and
   listing it at the same rung says otherwise. The count stays because zero
   is a finding.

   `:deps` is what makes a consumer able to DRAW this — an edge needs two
   ends, and a row that names only itself leaves the producer as the only
   thing that could ever assemble a diagram. Foundation-free, matching the
   layering: an edge into the substrate is not drawn, and `:foundation`
   already says which modules those are."
  [:map
   [:module {:doc "the module name — a namespace's first two segments, e.g. slopp.index"} :string]
   [:namespaces {:doc (str "its PRODUCTION namespaces, sorted. Test namespaces are"
                           " not peers of the code they cover, so they are counted"
                           " in :tests rather than listed here")}
    [:sequential :string]]
   [:tests {:doc (str "how many -test namespaces fold into this module. A count"
                      " rather than an omission because zero is a finding")} :int]
   [:tier {:doc (str "the declared purity tier — what code in this module is"
                     " ALLOWED to do: pure, internal, or external")} :string]
   [:foundation {:doc (str "true when this module is substrate anything may depend"
                           " on. Edges INTO it are left out of :deps, so a drawn"
                           " graph is not a hairball of arrows into the basement")}
    :boolean]
   [:deps {:doc (str "the modules this one depends on, foundation excluded — the"
                     " other end of every edge, which is what lets a consumer draw"
                     " the graph instead of asking the producer for a picture")}
    [:sequential :string]]
   [:gaps {:doc "where this module is thin"} gaps]])

(def module-index
  "`GET /api/modules` — the Code landing: one row per module, the layering,
   and the cycles.

   No canvas. This carried a fully placed `module-picture` — boxes with
   coordinates, routed edges, an extent — until the reviewer UI became a
   separate project and demonstrated the cost: it ported a layout namespace
   and found nothing for it to do, because the layout had already happened
   here. An API that ships a drawing admits exactly one consumer.

   `:layers` is the compromise, and it is not one: a topological layering is
   ANALYSIS, it comes from the store's own module graph, and no consumer can
   recompute it. Placing boxes on those rungs is drawing, and every consumer
   should get to disagree about it.

   `:cycles` rides alongside rather than inside, because a cycle is a FINDING
   about the architecture, not a drawing instruction. On a tangled store it is
   the most useful thing on the screen, and a consumer that only wants the
   verdict should not have to read geometry to find it."
  [:map
   [:modules {:doc "one row per module, sorted by name"} [:sequential module-row]]
   [:layers {:doc (str "the TOPOLOGICAL layering, deepest first: each entry is the"
                       " modules at that depth, and a module's dependencies are all"
                       " in earlier entries. This is ANALYSIS — the store's own"
                       " module graph, which no consumer can recompute — and it is"
                       " deliberately not a drawing: placing boxes on these rungs is"
                       " yours, and every consumer should get to disagree about it")}
    [:sequential [:sequential :string]]]
   [:cycles {:doc (str "dependency cycles, each entry the modules caught in one."
                       " Empty is the healthy case and the usual one. Alongside"
                       " :layers rather than inside it because a cycle is a FINDING"
                       " about the architecture, not a drawing instruction — on a"
                       " tangled store it is the most useful thing on the screen,"
                       " and a consumer that only wants the verdict should not have"
                       " to read geometry to find it")}
    [:sequential [:sequential :string]]]])

(def module-detail
  "`GET /api/module/:m` — one module from the inside: its namespaces, how they
  depend on each other, and the edges that cross its boundary.

  The level below `module-index`, and it exists because that one stops exactly
  where a reader's question starts: `/api/modules` ships module→module `:deps`,
  so descending into a box on the diagram had no data behind it at all.

  Same split as the level above — ANALYSIS crosses, DRAWING does not.
  `:layers` is here for the identical reason it is there: a topological
  layering comes from the store's own graph and no consumer can recompute it,
  while placing boxes on those rungs is drawing.

  `:boundary` is the field this level cannot be read without. A descended
  diagram that shows only internal edges is context-free — you cannot tell a
  namespace that is the module's front door from one nothing outside touches,
  and those are different things to a reader. `:out` and `:in` name the
  OUTSIDE namespace and its module, so a consumer can draw them as labelled
  stubs on the frame without a second request.

  An internal edge appears in a namespace's `:deps` and NOT in `:boundary` —
  one arrow, one place. `-test` namespaces are excluded exactly as
  `module-index` excludes them: a test folds into the module it covers, so
  listing it puts two things at the same rung that are not peers."
  [:map
   [:module {:doc "the module name — a namespace's first two segments"} :string]
   [:tier {:doc "the module's declared purity tier — what its code is allowed to do"}
    [:enum "pure" "internal" "external"]]
   [:namespaces {:doc (str "its PRODUCTION namespaces. -test namespaces fold into"
                           " the module they cover, so listing them here would put"
                           " two things at one rung that are not peers")}
    [:sequential [:map
                  [:ns {:doc "the namespace's full name"} :string]
                  [:forms {:doc "how many named forms it holds"} :int]
                  [:tier {:doc (str "its own effective tier, most-specific"
                                    " declaration winning — which may be stricter"
                                    " than the module's")}
                   [:enum "pure" "internal" "external"]]
                  [:deps {:doc (str "the namespaces INSIDE this module it depends"
                                    " on. An edge that leaves the module is in"
                                    " :boundary instead — one arrow, one place")}
                   [:sequential :string]]
                  [:gaps {:doc "where this namespace is thin"} gaps]]]]
   [:boundary {:doc (str "the edges that CROSS the module's frame, which is what"
                         " makes a descended diagram readable: without it you cannot"
                         " tell the module's front door from a namespace nothing"
                         " outside touches, and those are different things")}
    [:map
     [:out {:doc "edges leaving this module"}
      [:sequential [:map
                    [:from {:doc "the namespace INSIDE this module that depends"} :string]
                    [:to {:doc "the outside namespace it depends on"} :string]
                    [:to-module {:doc (str "that namespace's module, so the stub can"
                                           " be labelled without a second request")} :string]]]]
     [:in {:doc "edges arriving from outside"}
      [:sequential [:map
                    [:from {:doc "the outside namespace that depends on us"} :string]
                    [:from-module {:doc "that namespace's module"} :string]
                    [:to {:doc "the namespace INSIDE this module it reaches"} :string]]]]]]
   [:layers {:doc (str "the topological layering of the namespaces WITHIN this"
                       " module, deepest first — the same analysis /api/modules"
                       " ships one level up, and drawing it is still yours")}
    [:sequential [:sequential :string]]]
   [:cycles {:doc (str "dependency cycles among this module's own namespaces, each"
                       " entry the namespaces caught in one. Empty is healthy")}
    [:sequential [:sequential :string]]]])

(def search-request
  "`GET /api/projects/:slug/search` — what a caller SENDS: the query text and a
  row budget, both as query parameters.

  `:q` is `:optional`, and that is a statement about the SCREEN rather than a
  courtesy. A search page is reachable by URL, so a reader can land on it
  having asked nothing; the answer to that is the empty state, not a 400 and
  an error panel. `search-results` comes back the same shape either way, with
  zeroes.

  `:limit` is optional because there is a declared default
  (`model/search-limits`) and a declared ceiling. A caller that sends nothing
  gets the default; one that asks for more than the ceiling is clamped to it,
  and `:total` is counted before the cut either way, so \"showing 20 of 340\"
  cannot be made false by a limit the caller did not choose."
  [:map
   [:slug {:doc "the project slug — the open project to read; interpolated into the PATH"} :string]
   [:q {:optional true
        :doc (str "the text to search for. Absent is a legal ask and answers"
                  " with the empty state rather than a 400, because a search"
                  " page is reachable by URL")} :string]
   [:limit {:optional true
            :doc (str "how many rows to return. Clamped to a declared ceiling,"
                      " and :total is counted BEFORE the cut either way, so"
                      " 'showing 20 of 340' cannot be made false by a limit you"
                      " did not choose")} :int]])

(def search-results
  "`GET /api/search` — everything matching a query, ranked across all three
  grains at once.

  The DOOR. Every other read here answers a question a reader already knows
  how to ask; this is the one that finds the address, and without it `/store`
  opens on a module diagram with no way in.

  **`:rank` is one scale across kinds, not per-kind normalised.** That is the
  fact a consumer cannot derive and must be told, because it decides a layout:
  one ranked list of typed rows is only honest if a module at 0.9 really does
  beat a form at 0.5. The ladder is name-exact 1.0, name-prefix 0.9,
  name-substring 0.8, doc 0.5, why 0.4, source 0.2.

  **`:hits` arrive SORTED**, rank descending, and are meant to be rendered in
  the order given. A consumer re-deriving the sort is a second opinion on the
  one thing it asked this side to own, and it goes stale the first time the
  ladder changes.

  **`:matched` is data, not decoration.** A hit whose name says nothing about
  the query reads as a bug unless the row can say the docstring is what
  matched. It is also what lets `\"source\"` be included at all — unlabelled, a
  source hit looks like a ranking failure rather than the escape hatch it is.

  **`:totals` is per kind and counted BEFORE `:limit`**, which is the whole
  reason it is here rather than left to the consumer: a limited hit list
  cannot know how many modules matched beyond the cut. `:total` is that summed.
  Named beside `:total` rather than folded into it so the two cannot read as a
  typo for each other.

  **No `:address`, and that is deliberate.** The rows carry the component
  parts — `:kind`, `:name`, `:module`, `:ns`, `:form-id` — and the consumer
  builds its own URL. Emitting `/store/module/<m>` here would put a
  CONSUMER'S routing scheme in the producer: slopp would be asserting a fact
  about somebody else's app, in their units, with nothing on either side able
  to check it, and a route change over there would silently falsify strings
  over here. It is the same error as naming a port for its consumer. One
  producer of the scheme, and it is the side that owns the routes."
  [:map
   [:query {:doc "the query as received, echoed so a result can label itself"} :string]
   [:total {:doc (str "how many things matched in all, counted BEFORE :limit —"
                      " so a limited hit list can still say 'showing 20 of 340'")} :int]
   [:totals {:doc (str "the same count split by kind, also before :limit: a cut"
                       " hit list cannot know how many modules matched beyond"
                       " the cut, which is why this is not left to the consumer")}
    [:map
     [:modules {:doc "modules matching, before :limit"} :int]
     [:namespaces {:doc "namespaces matching, before :limit"} :int]
     [:forms {:doc "forms matching, before :limit"} :int]]]
   [:hits {:doc (str "the matches, SORTED by :rank descending and meant to be"
                     " rendered in the order given — re-deriving the sort is a"
                     " second opinion on the one thing this side was asked to own")}
    [:sequential
     [:map
      [:kind {:doc "which grain matched"} [:enum "module" "namespace" "form"]]
      [:name {:doc "the thing's own name, unqualified for a form"} :string]
      [:module {:optional true :doc "the module it belongs to; absent for a module hit"} :string]
      [:ns {:optional true :doc "the namespace it belongs to; absent above form grain"} :string]
      [:form-id {:optional true :doc "the form's stable address, for a form hit"} :string]
      [:sig {:optional true :doc "the form's arglists, one string per arity"} [:sequential :string]]
      [:doc {:optional true :doc "the form's own docstring, when it has one"} :string]
      [:why {:optional true :doc "the recorded intent of the last write to it"} :string]
      [:matched {:doc (str "WHICH text matched, and it is data rather than"
                           " decoration: a hit whose name says nothing about the"
                           " query reads as a bug unless the row can say the"
                           " docstring is what matched")}
       [:enum "name" "doc" "why" "source"]]
      [:rank {:doc (str "ONE scale across kinds, not normalised per kind — a"
                        " module at 0.9 really does beat a form at 0.5, which is"
                        " what makes a single mixed list honest. Name-exact 1.0,"
                        " name-prefix 0.9, name-substring 0.8, doc 0.5, why 0.4,"
                        " source 0.2")} :double]]]]])

(def neighbour-card
  "One form on the OTHER end of an edge — a caller or a callee, as a card.

  Declared once for both directions because it is one shape, and declared at
  all because `[:sequential :map]` is not a type: a bare `:map` validates any
  map, so the generated client checked every response against it and could
  never find anything. Reported by slopp-ui after a shape change went silent
  for weeks one endpoint over.

  The card INLINES what a reader needs in order to decide whether to follow
  the edge — signature, docstring, recorded why, coverage — because the
  failure this page exists to avoid is the lonely bubble: arriving cold at a
  form and having to make one request per neighbour just to learn which of
  them matters.

  **The optional four are OPTIONAL rather than `:maybe`, measured over 34 real
  cards.** A form with no docstring OMITS `:doc`; it does not send nil. Getting
  that backwards writes a contract that refuses valid data, which is the
  failure mode where the contract becomes the thing you route around."
  [:map
   [:form {:doc "the neighbour's qualified name"} :string]
   [:form-id {:doc "its stable address, for a permalink"} :string]
   [:ns {:doc "the namespace it lives in"} :string]
   [:module {:doc "that namespace's module"} :string]
   [:calls {:doc (str "how many edges run between it and the subject — a COUNT here,"
                      " unlike a form row's :calls, which lists same-namespace callee"
                      " NAMES. Same key, two documents, two meanings")} :int]
   [:warranty {:doc "what is known to have exercised it"}
    [:map [:covered {:doc "how many tests were OBSERVED running it"} :int]]]
   [:sig {:optional true :doc "its arglist as one string; absent when it has none"} :string]
   [:doc {:optional true :doc "its docstring's first line; absent when it has none"} :string]
   [:why {:optional true :doc "the recorded ask behind its last write; absent when none"} :string]
   [:via {:optional true
          :doc (str "how the edge was found — present on a CALLEE, absent on a caller"
                    " card because callers are grouped by it one level up")} :string]])

(def form-view
  "`GET /api/form/:id` — one form's permalink model.

  OPEN (malli maps are, by default) and deliberately so: this names the keys
  the client renders and lets `slopp.api.model/form-view` carry the rest of
  its card. A closed schema over a model this rich would be a contract that
  refuses valid data every time the model grew a field — the failure mode
  where the contract becomes the thing you route around."
  [:map
   [:form-id {:doc "the form's stable address — the permalink this view answers for"} :string]
   [:form {:doc "the form's qualified name, ns/name"} :string]
   [:name {:doc "the form's own name, unqualified"} :string]
   [:ns {:doc "the namespace it lives in"} :string]
   [:view {:doc (str "the rendering FIDELITY this response was built at, echoing"
                     " ?view= — so a consumer can tell which one it got rather than"
                     " assuming its request was honoured")} :string]
   [:views {:doc "every fidelity this form can be requested at"} [:sequential :string]]
   [:tokens {:doc (str "the form's source as [CLASS TEXT] PAIRS — first element the"
                       " syntax class (\"keyword\", \"string\", \"comment\"…), second the"
                       " literal text. Not markup: the server sends classes and text"
                       " and the client decides what element they become, so no"
                       " consumer needs a lexer. Concatenating every TEXT reproduces"
                       " the source exactly, which is what lets a form render from"
                       " these alone")}
    [:sequential token]]
   [:callers {:doc (str "who reaches this form, GROUPED BY HOW — a static call and a"
                        " declared reference are both callers and are not the same"
                        " evidence")}
    [:sequential [:map
                  [:via {:doc (str "HOW the edge was found: \"static\" is a call written"
                                   " in the code, \"carrier\" is a reference passed as a"
                                   " value (#'var, a late-ref), \"declared\" is a marker"
                                   " naming it. Grouped rather than summed because"
                                   " they are not the same evidence")} :string]
                  [:count {:doc "how many callers reach it that way"} :int]
                  [:forms {:doc "the caller cards reached this way"}
                   [:sequential neighbour-card]]]]]
   [:callees {:doc (str "the forms this one reaches, as cards — the other direction"
                        " of the graph. Each carries its own :via inline, where a"
                        " caller's sits on the group")}
    [:sequential neighbour-card]]
   [:note {:doc (str "the standing caveat on :callers and :callees, in words: the"
                     " edges come from a SYNTACTIC reader over the store, so they are"
                     " a FLOOR and not a census — a call reached through a binding or"
                     " built at runtime is not among them. Render it wherever the"
                     " edges are shown; a reader who takes a caller list for complete"
                     " draws the wrong conclusion from a short one")} :string]])

(def change-request
  "`GET /api/projects/:slug/change/:range` — what a caller SENDS.

  Only the path segment (beyond the project). Declared for the reason
  [[form-request]] gives at length: `:rest/request` is what the caller sends,
  and a generated wrapper whose params map has no entry for `:range` cannot
  address the endpoint at all. slopp-ui reported this as one document carrying
  two conventions, and they were right — `form` declared its parameter and four
  others did not."
  [:map
   [:slug {:doc "the project slug — the open project to read; interpolated into the PATH"} :string]
   [:range {:doc (str "the commit-point range to review, `from..to` — two commit"
                      " point ids. Interpolated into the PATH. Both ends are"
                      " user input: an unparseable range is a 404, which is a"
                      " different answer from a range that parsed and changed"
                      " nothing")} :string]])

(def source-request
  "`GET /api/projects/:slug/source/:ns/:name` — what a caller SENDS.

  Both segments, and both are the address rather than a filter: there is no
  response without them."
  [:map
   [:slug {:doc "the project slug — the open project to read; interpolated into the PATH"} :string]
   [:ns {:doc (str "the namespace holding the form, e.g. `app.core` —"
                   " interpolated into the PATH")} :string]
   [:name {:doc (str "the form's name within that namespace. A name can be"
                     " ambiguous where a namespace holds more than one form"
                     " answering to it; the response carries the :form-id that"
                     " resolves it")} :string]])

(def ns-outline-request
  "`GET /api/projects/:slug/ns/:ns` — what a caller SENDS."
  [:map
   [:slug {:doc "the project slug — the open project to read; interpolated into the PATH"} :string]
   [:ns {:doc (str "the namespace to outline, e.g. `app.core` — interpolated"
                   " into the PATH. Unknown is a 404 rather than an empty"
                   " outline: `{:forms []}` would say the namespace exists and"
                   " holds nothing, which is a different statement")} :string]])

(def module-request
  "`GET /api/projects/:slug/module/:m` — what a caller SENDS."
  [:map
   [:slug {:doc "the project slug — the open project to read; interpolated into the PATH"} :string]
   [:m {:doc (str "the module name — the first TWO namespace segments, e.g."
                  " `slopp.store`. Interpolated into the PATH. Unknown is a"
                  " 404 rather than an empty frame, on the same reasoning"
                  " ns-outline uses")} :string]])

(def rest-paths-document
  "`GET /api/rest/paths` — every `:rest/path` form this project serves, with
  its contract. **Version 1.**

  Replaced `/api/contracts` and its `:slopp/contract-version 2`. A new
  document at a new address starting at 1: carrying the old counter forward
  would have claimed a lineage it does not have. The two served side by side
  for exactly one release, so the consumer could migrate against something
  that worked and report green before the old one went.

  The rule the old document taught, inherited whole: **adding a REQUIRED key
  moves the version, or the key ships `{:optional true}`.** The version field
  exists so a consumer can refuse a shape it does not know, and it cannot do
  that job while it is constant — four keys once arrived under a constant 1
  and a consumer holding a schema could not tell which shape it would get.

  Three fields are `:any` and cannot honestly be more. `:request` and
  `:response` carry malli SCHEMAS as values — a schema is a keyword, a vector,
  a symbol or a map, so no narrower shape is true of all of them. `:auth` is
  an app's own `:http/auth` declaration verbatim, whose grammar is open by
  design. See the endpoint's `:rest/unconstrained-ok` for why that is
  permanent rather than pending."
  [:map
   [:paths
    {:doc "every :rest/path endpoint this project serves; content is absent by KIND and there is no opt-out"}
    [:sequential
     [:map
      [:method {:doc "the HTTP method, as a keyword"} :keyword]
      [:path {:doc "the route pattern, with :segment captures as declared"} :string]
      [:name {:doc "the handler's own name, unqualified"} :symbol]
      [:handler {:doc "the fully-qualified handler symbol — :name alone does not resolve, and a third of real surfaces have duplicate simple names"} :symbol]
      [:doc {:doc "the handler's docstring, de-indented and whole — public API copy, and MARKDOWN"} [:maybe :string]]
      [:media-type {:doc "what this endpoint ANSWERS, defaulting to application/json — read it before choosing a decoder"} :string]
      [:effectful? {:doc "true when calling this may CHANGE something — DERIVED from the :http/effectful declaration or a non-safe method, so it is answered for every endpoint rather than only the ones whose author wrote a marker"} :boolean]
      [:auth {:doc "the endpoint's :http/auth policy verbatim: :public, :authenticated, or a composite like [:group \"admin\"]"} :any]
      [:request {:doc "the malli schema for everything the caller SENDS — path params, query and body as one map — or nil for an endpoint that takes none"} :any]
      [:response {:doc "the malli schema of the DOCUMENT a 200 carries, or nil when the endpoint declares none"} :any]]]]])

(def http-paths-document
  "`GET /api/http/paths` — every `:http/path` form this project serves, as
  CONTENT rather than as an api. **Version 1.**

  The gap this closes: a remote consumer could discover every API and zero
  pages. Content was excluded from the contract document because a typed
  wrapper over HTML would be nonsense — a statement about TYPING, which was
  never a reason for a page to be undiscoverable.

  **Every row DESCRIBES a page and none carries it.** An index shipping bodies
  would put a stylesheet on the wire to render one table row, and a reader who
  wants the body has the URL.

  `:media-type` is what the app ACTUALLY serves, not what the def declared —
  the framework derives one from the value's shape when a def declares none,
  and the page that declares nothing is the shell, which is the most important
  page in most stores."
  [:map
   [:paths
    {:doc "every :http/path form this project serves. EMPTY is the common case — most projects serve no content of their own, and a consumer must render that well"}
    [:sequential
     [:map
      [:method {:doc "the HTTP method, as a keyword"} :keyword]
      [:path {:doc "the route this content is served at"} :string]
      [:name {:doc "the def's own name, unqualified"} :symbol]
      [:handler {:doc "the fully-qualified symbol of the def whose VALUE is the page — :name alone does not resolve"} :symbol]
      [:doc {:doc "the def's docstring, de-indented and whole — public copy, and MARKDOWN"} [:maybe :string]]
      [:media-type {:doc "what this path ACTUALLY answers with, derived from the value when the def declares none: a vector serves text/html, anything else text/plain, and a declaration wins verbatim"} :string]
      [:auth {:doc "the route's :http/auth policy verbatim — never nil, because the auth gate refuses a route that declares none"} :any]
      [:shape {:doc ":hiccup when the value is a vector and :text otherwise — the same discriminator the framework serves by"} :keyword]
      [:root-tag {:optional true :doc "hiccup only: the document's outermost tag. :html means a WHOLE document (the renderer prepends the doctype); anything else is a fragment"} :keyword]
      [:nodes {:optional true :doc "hiccup only: how many nodes the tree has AS AUTHORED — a size signal, not a byte count. What is served has the bundle injected, which depends on where the app is mounted"} :int]
      [:bytes {:optional true :doc "text only: the length of the served string"} :int]
      [:shell {:optional true :doc "present when this page BOOTS an application — the url of the compiled bundle the framework injects into it"} :string]]]]])

(def webapp-paths-document
  "`GET /api/webapp/paths` — the pages a project's BROWSER routes to, one row
  per `:webapp/path` form.

  The client-side half of the content surface. `/api/http/paths` says what the
  server hands a browser; a client-routed app is ONE server route and a dozen
  addresses, and the dozen are what a reader navigating it sees.

  **It used to publish server-side PREFIXES**, which described a mechanism
  that no longer exists: a shell declares its own `**` path, so the fallback
  IS the declaration and there is no prefix list to keep in step.

  **No version key**, on this or any sibling. Nothing branched on one — it
  existed so a consumer could refuse rather than misread. API compatibility is
  a coordination between an API and its client, and both halves of this one
  migrate together; the rule that replaces it is that a document changes by
  RENAMING a key, never by redefining one in place.

  **No `:unreadable` either.** That key existed because the route table was a
  value inside the entry fn, so a table built in pieces could not be read
  whole. A page carries its address as metadata, which cannot be
  half-declared."
  [:map
   [:paths
    {:doc "one row per page the browser routes to, sorted by address. EMPTY unless the project has a browser app — most do not"}
    [:sequential
     [:map
      [:path {:doc "the address the BROWSER routes to, e.g. \"/store/form/:id\" — written as the browser sees it, the same coordinate system :http/path uses"} :string]
      [:page {:doc "the fully-qualified symbol of the function that renders this page"} :symbol]
      [:doc {:optional true :doc "that function's docstring, de-indented and whole — MARKDOWN. Absent when it has none"} :string]
      [:calls
       {:optional true
        :doc "the endpoints this page reaches, derived from the reference graph rather than declared beside it — so it cannot disagree with the code. ABSENT, not empty, when a page calls none"}
       [:sequential
        [:map
         [:endpoint {:doc "the fully-qualified symbol of the endpoint DESCRIPTOR the page names"} :symbol]
         [:method {:doc "the verb that descriptor declares, defaulting to :get"} :keyword]
         [:path {:doc "the address it names, in the same grammar :http/path uses"} :string]
         [:params
          {:optional true
           :doc "the LITERAL arguments this page passes to that endpoint, as read — {:depth 2}, not {:depth \"2\"}. POLICY rather than data: it is the only place a decision like fetching two levels deep is written down. PARTIAL by construction, which is why this is a map and not a rendered call — a route capture is not a literal and is correctly absent, so these arguments plus the address are the whole call. ABSENT, not empty, when the page passes no literal at all, which is the common case"}
          ;; the SCALARS `rules.webapp/page-calls` admits and nothing wider.
          ;; `:any` would aim the response-drift check at this field and switch
          ;; it off; the set is not a guess, it is the predicate the derivation
          ;; filters on
          [:map-of :keyword [:or :int :double :string :keyword :boolean]]]]]]]]]])

(def config-document
  "`GET /api/config` — how a project is CONFIGURED, one row per setting.

  **ONE document, not one per capability.** The path documents split by
  capability because a project's SURFACE is owned that way; config is not. It
  is a single flat namespaced keyspace — `http.port` beside `webapp.enabled`
  in one registry — and a page joining four documents would have to know the
  list of capabilities and would silently omit the fifth the day one ships.
  `:owner` on every row makes grouping the page's job, which costs nothing.

  **`prefix` narrows to a block** and matches the segments the keys already
  have: `http` gives `http.*`, `http.auth` narrows further. A prefix nothing
  matches answers EMPTY, never everything.

  **A credential family publishes its KEY and withholds its VALUE**, marked
  `:secret true`. `capabilities/secret-families` declares which — declared
  rather than matched on name fragments, because a denylist is updated by
  whoever adds the next secret and its failure mode is publishing one."
  [:map
   [:config
    {:doc "one row per setting the registry declares, plus any stored key a wildcard family governs. Narrowed when the request carries a prefix; EMPTY when the prefix matches nothing"}
    [:sequential
     [:map
      [:key {:doc "the setting's full dotted name, e.g. \"http.port\""} :string]
      [:owner {:doc "the capability that owns it, derived from the key's first segment — never stored beside it, so the label and the name cannot disagree"} :string]
      [:doc {:optional true :doc "what the setting DOES, from the registry. MARKDOWN"} [:maybe :string]]
      [:default {:optional true :doc "what it is when nobody sets it, as the registry declares it — WHATEVER TYPE that entry declares, so a scalar for most and a collection for some (http.auth.providers defaults to a set). Absent from a row with no declared default"} :any]
      [:effective {:optional true :doc "the value in force — the stored one if set, the default otherwise, PARSED to the type the registry declares, so it has that entry's type rather than a common one. WITHHELD on a secret row"} :any]
      [:set {:optional true :doc "true when this store sets it explicitly. Absent means defaulted, which is a different fact from set-to-the-default"} :boolean]
      [:value {:optional true :doc "the RAW stored string, exactly as config_file wrote it and before any parsing — present only when :set, and WITHHELD on a secret row"} :string]
      [:secret {:optional true :doc "this key belongs to a declared credential family: the key, owner and :set publish and the value never does"} :boolean]]]]
   [:owners
    {:doc "what each owner label MEANS, keyed by the label — the vocabulary the :owner column comes from, riding along because a reader of this document has no other route to it"}
    [:map-of :string :string]]
   [:bundle
    {:optional true
     :doc "the URL this project's compiled browser bundle is SERVED at, joined from the compile output and the static mount that reaches it. Not a setting — nothing sets it, which is why it rides beside the config rather than in it. ABSENT when no mount reaches the bundle, and that is a real answer rather than a gap: a store may serve its bundle from an endpoint instead, which is what slopp's own reviewer UI does. A PATH on the project's own host, never absolute — resolve it against the address this document came from, which is the one thing a remote consumer holding it cannot otherwise know"}
    :string]
   [:patterns
    {:optional true
     :doc "the wildcard FAMILIES themselves — they name settable spaces (http.static.*) rather than settings, so they are not rows. Absent when the prefix matches none"}
    [:sequential
     [:map
      [:key {:doc "the family's pattern, e.g. \"http.static.*\" — the * stands for any single segment a store may name"} :string]
      [:owner {:doc "the capability that owns the family, from the pattern's first segment"} :string]
      [:doc {:optional true :doc "what a setting in this family DOES. MARKDOWN"} [:maybe :string]]]]]
   [:orphaned
    {:optional true
     :doc "stored keys this build does not recognise — usually a capability RENAME nobody migrated. Absent when there are none, and that absence is unambiguous because it always computes"}
    [:sequential
     [:map
      [:key {:doc "the stored key, under a name no registry entry claims — nothing reads it"} :string]
      [:value {:optional true :doc "what it is set to, so the row is a migration instruction rather than a prompt to go and look. WITHHELD on a secret row"} :string]
      [:secret {:optional true :doc "the orphaned key belongs to a declared credential family, so its value is withheld here too"} :boolean]]]]])

(def config-request
  "`GET /api/projects/:slug/config` — the filter, declared so a generated client carries it.

  `:http/params` on the ENDPOINT reads to nobody: slopp defines no such marker
  there, and a key in a namespace slopp owns that slopp does not define
  refuses nothing and generates nothing while looking exactly like one that
  works. The request CONTRACT is where a parameter becomes visible — it rides
  the published document, so `generate_client` emits a descriptor that names
  `:prefix` and `endpoint/request` puts it in the query string."
  [:map
   [:slug {:doc "the project slug — the open project to read; interpolated into the PATH"} :string]
   [:prefix
    {:optional true
     :doc "narrow to one block of the keyspace, matching the segments the keys already have — \"http\" gives http.*, \"http.auth\" narrows further. Omit for every setting. A prefix nothing matches answers an EMPTY :config rather than everything"}
    :string]])

(def bundle
  "`GET /api/bundle?ask=` — the ask's map as one injectable text block.

  The consumer is the plugin's UserPromptSubmit hook, which has ~2 s and a
  context window to feed: it wants ONE string it can paste, not a structure
  it must render — the producer owns the layout for the same reason search
  owns its sort. A blank or absent ask answers 200 with the plain ranking;
  like search, this endpoint has no subject that can fail to exist."
  [:map
   [:bundle {:doc (str "the whole bundle as prompt-ready text: a header that"
                       " orients, the ask's ranked forms as one-line cards,"
                       " and the seeds' full sources — inject verbatim")} :string]])

(def cost-request
  "`GET /api/projects/:slug/cost` — what a caller SENDS.

  Declared for the reason `search-request` is: without it the generated client
  takes a params map nothing reads from, so `?by=` answers on the wire and is
  unreachable through the typed client.

  The enum is the whole validation. An unknown split would otherwise throw out
  of the fold as a 500; refusing it here makes it a 400, which is what a
  malformed request is."
  [:map
   [:slug {:doc "the project slug — the open project to read; interpolated into the PATH"} :string]
   [:by {:optional true
         :doc (str "which split to return. Omitted is \"commit-point\" — the"
                   " series with a time axis, and the only one that plots"
                   " directly against /api/timeline")}
    [:enum "commit-point" "model" "ask"]]])

(def cost-model-block
  "The model-side summary `GET /api/cost` reports for any grouping of requests
  — a `by=model` row, and the `:unattributed` / `:undated` buckets under
  `by=ask`.

  Declared once and referenced, because the three are the SAME fold over the
  same records. Two copies would drift, and the one that drifted would be the
  bucket nobody looks at until the numbers stop adding up."
  [:map
   [:requests {:doc "round trips — a batch arriving twice is counted once"} :int]
   [:input {:doc "input tokens billed"} :int]
   [:output {:doc "output tokens billed"} :int]
   [:cache-read {:doc "tokens served from the prompt cache"} :int]
   [:cache-creation {:doc "tokens written INTO the prompt cache"} :int]
   [:tokens {:doc "every token above, summed — the one number to plot"} :int]
   [:cost-usd {:doc "what those tokens cost, in dollars"} :double]
   [:context {:doc (str "how full the conversation got. A DISTRIBUTION, never a"
                        " sum: every request ships the whole conversation, so"
                        " summing counts the same tokens once per round trip")}
    [:map
     [:p50 {:doc "median context size across the window's requests"} :int]
     [:max {:doc "the largest — the number that forces a compaction"} :int]]]])

(def cost
  "`GET /api/cost` — where this store's wall clock and model spend went, as a
  SERIES rather than a snapshot.

  Three splits over one fold, and the rows differ by split, so every key each
  can send is declared here and marked optional. An open map would have been
  shorter and would have broken this API's standing promise that a response
  sends nothing its contract does not declare.

  **No telemetry is NO ROWS, never a zeroed one.** A zero would say *this cost
  nothing* where the truth is *nobody was measuring*, and a consumer cannot
  tell those apart after the fact. The model side is harness-supplied through
  the OTLP intake, so an empty `:rows` under `by=model` means telemetry is not
  reaching this store rather than that the work was free."
  [:map
   [:by {:doc "the split these rows are — \"commit-point\", \"model\" or \"ask\""} :string]
   [:rows {:doc "newest first for commit-point and ask; dearest first for model"}
    [:sequential
     [:map
      ;; by=commit-point
      [:commit {:optional true :doc "by=commit-point: the delta id this segment follows"} :string]
      [:description {:optional true :doc "by=commit-point: what that commit point was recorded as achieving"} :string]
      [:forms {:optional true
               :doc (str "by=commit-point: how many forms the store held AT that"
                         " point — LIVE forms, so a sweep that deleted a hundred"
                         " reads as the fall it was. Absent for a commit point older"
                         " than the fold")} :int]
      [:namespaces {:optional true
                    :doc (str "by=commit-point: how many namespaces the store held at"
                              " that point. A namespace is live while any of its forms"
                              " is, so the two counts never disagree about one store")} :int]
      [:at {:optional true :doc "by=commit-point and by=ask: when, epoch millis — the x axis"} :int]
      [:turns {:optional true :doc "by=commit-point: turns recorded in the segment"} :int]
      [:calls {:optional true :doc "by=commit-point: slopp tool calls in the segment"} :int]
      [:wall {:optional true :doc "by=commit-point: the three-way wall-clock split"}
       [:map
        [:active-ms {:optional true :doc "elapsed minus idle — time somebody was actually here"} :int]
        [:slopp-ms {:optional true :doc "time inside a slopp tool call"} :int]
        [:outside-ms {:optional true
                      :doc (str "agent reasoning plus every non-slopp tool, which the"
                                " server cannot tell apart and does not pretend to")} :int]
        [:idle-ms {:optional true :doc "gaps where nobody was in the session at all"} :int]
        [:elapsed-ms {:optional true :doc "wall clock end to end, idle included"} :int]
        [:slopp-share {:optional true
                       :doc (str "slopp-ms as a percentage of ACTIVE time, so a human"
                                 " going to bed is not counted as time slopp failed"
                                 " to use")} :string]]]
      [:refused {:optional true :doc "by=commit-point: round trips that produced nothing — a quality series"}
       [:map
        [:count {:optional true :doc "refused calls in the segment"} :int]
        [:pct {:optional true :doc "what share of all calls that was"} :int]]]
      [:rent {:optional true
              :doc (str "by=commit-point: CONTEXT RENT in characters, not tokens —"
                        " an answer's size times the calls that follow it, because"
                        " every one of them re-reads it. A within-turn LOWER BOUND:"
                        " the payload keeps riding until a compaction the server"
                        " never sees")}
       [:map
        [:carried-chars {:optional true :doc "every answer's size times the calls that followed it"} :int]
        [:by-tool {:optional true :doc "the dearest tools by carried characters"}
         [:sequential [:map
                       [:tool {:doc "the tool, as it is ranked — e.g. \"read/query_source\""} :string]
                       [:carried {:doc "characters this tool's answers carried"} :int]]]]]]
      ;; by=model — the only rows carrying tokens and dollars
      [:model {:optional true
               :doc (str "TWO SHAPES, one per split, and a consumer must branch."
                         " by=model: the model NAME each request records — null when a"
                         " request sent none, since those tokens were paid for either"
                         " way and a name nobody sent is not a name to invent."
                         " by=ask: the model-side SUMMARY of that ask's requests, the"
                         " same block :unattributed carries")}
       [:or [:maybe :string] cost-model-block]]
      [:requests {:optional true :doc "by=model: round trips, a batch counted once"} :int]
      [:input {:optional true :doc "by=model: input tokens billed"} :int]
      [:output {:optional true :doc "by=model: output tokens billed"} :int]
      [:cache-read {:optional true :doc "by=model: tokens served from the prompt cache"} :int]
      [:cache-creation {:optional true :doc "by=model: tokens written into the prompt cache"} :int]
      [:tokens {:optional true :doc "by=model: every token above, summed"} :int]
      [:cost-usd {:optional true :doc "by=model: what those tokens cost, in dollars"} :double]
      [:context {:optional true :doc "by=model: conversation size, as a distribution rather than a sum"}
       [:map
        [:p50 {:optional true :doc "median context size"} :int]
        [:max {:optional true :doc "the largest — what forces a compaction"} :int]]]
      ;; by=ask
      [:ask {:optional true :doc "by=ask: the recorded ask, verbatim"} :string]
      [:agent {:optional true :doc "by=ask: which agent's turn it was"} :string]
      [:intent {:optional true :doc "by=ask: the turn's recorded intent"} :string]
      [:ms {:optional true :doc "by=ask: the turn's wall time"} :int]
      [:prompts {:optional true :doc "by=ask: the prompt ids the turn's requests carried"}
       [:sequential :string]]]]]
   [:unattributed {:optional true
                   :doc (str "by=ask only: requests that fell in no turn bracket."
                             " Present rather than folded in, because attributing them"
                             " to a neighbouring ask would invent a fact")}
    [:map
     [:requests {:doc "how many requests could not be attributed"} :int]
     [:prompts {:doc "their prompt ids, so they can be chased"} [:sequential :string]]
     [:model {:doc "what they cost, folded the same way a by=model row is"} cost-model-block]]]
   [:undated {:optional true
              :doc (str "by=ask only: HOW MANY requests carried no timestamp at all,"
                        " which no rule can place in a bracket. A count rather than a"
                        " block — with no clock there is nothing to attribute, only"
                        " something to disclose. Absent when there are none")} :int]])
