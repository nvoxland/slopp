(ns slopp.index.crossings
  "The edges that LEAVE the store — the representation verification lacks at
  the boundary.

  `slopp.index.refs`, its sibling here, makes every edge INSIDE the store
  answerable: who calls
  what, what a rename touches, which tests cover a form. There has never been
  an equivalent for an edge that leaves — form data handed to garden, form
  metadata read as a route table, a contract turned into JSON for a browser,
  `.cljc` source handed to the ClojureScript compiler, a spec turned into
  generated code. So every exit was unverified by construction, and each grew
  an ad-hoc hand-written check or none, with no way to tell which. Fifteen of
  the sixteen frictions in one wave landed at a crossing.

  **This verifies nothing, on purpose.** Nothing here could: the far side is
  another system, and the checker that would know lives there and reports in
  that system's vocabulary. What it does is make the exits ENUMERABLE and make
  an exit with no checker SAY SO — because an exit nothing checks and an exit
  that does not exist are indistinguishable until one of them is written down.

  Two lists, and both halves are load-bearing: `kinds` (what crosses, to
  where, checked by what, blind to what) and `internal-markers` (what slopp
  owns that deliberately stays inside). Together they make classification
  total, which is what lets an unclassified marker be a finding rather than
  noise — an inventory that cannot notice a new exit describes the system it
  was written against, not the one you have."
  (:require [slopp.store :as store]))

(def kinds
  "Every way store data LEAVES slopp's verification, as data.

  Each row says what crosses, what is waiting on the far side, which markers
  signal it, and — the load-bearing field — `:checked-by`, the surface that
  proves the far side agrees, or `nil` where nothing does. `:blind` states what
  the checker does NOT cover, because a checker named without its limits is
  the absence-of-check-reads-as-absence-of-finding conflation wearing a
  different hat.

  Registering a kind does not verify anything. It makes the exit ENUMERABLE,
  which is the thing that was missing: an exit with no checker and an exit
  that does not exist look identical until one of them is written down."
  [{:kind       :http/route
    :leaves     "a form's name metadata"
    :to         "the served route table, and from there HTTP"
    :markers    #{:web/path :web/method}
    :checked-by "http-dangling-route-refs ties every literal :href/:src to a
                 route; query_surface reads the same metadata the gates enforce"
    :blind      "the SERVED table is built from interned vars in the running
                 process, not from the store, so a route can outlive its
                 definition until the host reloads"}

   {:kind       :wire/json
    :leaves     "a declared request/response contract"
    :to         "JSON, and a browser that never sees Clojure data"
    :markers    #{:web/request :web/response}
    :checked-by "the boundary honours it at runtime once `rest.enabled`.
                 `slopp.rest.contract/decode-request` judges EVERYTHING the
                 caller sent — path segments, query string and body against one
                 schema, each decoded by what its own wire can express — and
                 refuses with a 400 before the declared reads and before the
                 handler. `check-response` judges the answer on what the client
                 actually RECEIVES, via a real serialize/parse, because a
                 keyword that arrives as a string satisfies a :string contract
                 the in-image value fails and a set that arrives as an array
                 fails one it passes. `slopp.rest/call` drives the same path in
                 process, so a test sees the consumer's view without a socket"
    :blind      "it is CONDITIONAL on the capability. A store with `rest.enabled`
                 false declares contracts that nothing honours — which is the
                 state this whole framework was in until 2026-08-15, when this
                 row claimed the dispatcher validated and it never had.

                 A consequence worth knowing rather than a gap: because the
                 decoding happens only when the boundary runs, a handler
                 receives TYPED params with rest on and text with it off. An
                 app that writes handlers against the typed shape has committed
                 to the capability, and turning it off is then a behaviour
                 change rather than only a loss of checking."}

   {:kind       :generated/client
    :leaves     "an endpoint's contract"
    :to         "generated ClojureScript nobody hand-edits"
    :markers    #{:generated}
    :checked-by "the rest-stale-client advisory when a contract drifts from
                 the last generation; the http-generated-ns gate refuses hand
                 edits"
    :blind      "generation is EXPLICIT, so between a contract change and the
                 next generate_client the two disagree by design"}

   {:kind       :schema/malli
    :leaves     "a value and the schema that describes it"
    :to         "malli, on both the JVM and in the browser bundle"
    :markers    #{:malli/schema}
    :checked-by "validation at the boundary, from the one .cljc var both
                 sides load"
    :blind      "a schema that is not .cljc cannot ship, and the endpoint is
                 SKIPPED rather than failing loudly"}

   {:kind       :web/vocabulary
    :leaves     "a declared read/effect KIND, resolved by name"
    :to         "a performer found by scanning namespaces the caller lists
                 by hand"
    :markers    #{:web/reads :web/effects :web/read :web/effect}
    :checked-by "web/context refuses at assembly when a declared kind has no
                 performer among its namespaces"
    :blind      nil}

   {:kind       :webapp/client-routing
    :leaves     "a prefix declared as client-routed"
    :to         "a route table that lives in the browser"
    :markers    #{:web/client-routes}
    :checked-by "webapp-client-routes-are-served compares the client's declared
                 route TABLE against the prefixes this document answers for, and
                 reports every route that 404s on a hard load — the failure that
                 is invisible from inside the app, because clicking to it works
                 and only a refresh or a shared link breaks.
                 webapp-client-routes-consequences states, once, what declaring
                 a prefix changed about every status code beneath it"
    :blind      "the comparison is on the prefix's TAIL, because a prefix is in
                 SERVER space and a client route is in APP space and the mount
                 point is a deployment fact no store knows. So a prefix whose
                 tail coincidentally matches an unrelated client route reads as
                 covered. And the client table must be LITERAL: a computed
                 pattern is skipped rather than guessed at, which makes the join
                 partial in the safe direction"}

   {:kind       :app/headless-entry
    :leaves     "the whole application as data — state, a route TABLE, the
                 screens its rows name, and the handlers sitting in its tree"
    :to         "slopp.cljnx's driver, which opens it on a JVM and
                 presses things"
    :markers    #{:web/page}
    :checked-by "webapp-page-unreachable refuses an entry that is not a public
                 zero-arity defn (def, defmethod, private, no [] arity), one in
                 a :cljs namespace, and a SECOND entry; webapp-portable-handler
                 refuses a FUNCTION handler on an input, which is the one shape
                 that cannot be portable at all — in a browser it receives a DOM
                 event, so one written against the map the driver passes runs
                 headless and does nothing served; the webapp-page-reach
                 advisory re-grades a CHANGED page's closure at done;
                 module_platform reports the pages a :cljs declaration
                 strands; screen/open! refuses a page whose :state is not an
                 atom, and webapp/wiring refuses an unknown or retired key, a
                 routes FUNCTION, a declared :webapp/view, and a row target that
                 is callable but renders nothing"
    :blind      "all of that grades whether the app OPENS and none of it grades
                 whether the app WORKS: that a screen returns hiccup, that a
                 handler does anything.

                 A screen's REQUEST is the other half of a route reference and is
                 joined by webapp-request-paths-are-served — an EQUALITY join,
                 because a request path is a PATTERN in the same grammar as
                 :web/path rather than a concrete url. Its own limit: an
                 absolute url is skipped as somebody else's server, so a typo in
                 one is invisible, and a computed path is skipped rather than
                 guessed at, which makes the join partial in the safe direction"}

   {:kind       :http/foreign-route
    :leaves     "a link to a path this store does not serve"
    :to         "somebody else's server"
    :markers    #{:web/external-path}
    :checked-by nil
    :blind      "the DECLARATION is the whole check: it stops
                 http-dangling-route-refs asking, and nothing confirms the
                 foreign server serves that path or still does. This is the
                 crossing that is honest about being one"}

   {:kind       :webapp/client-path
    :leaves     "a link written as a CLIENT-ROUTER key, not a server path"
    :to         "this app's own router, after the render prefixes it"
    ;; no marker: the literal itself is the signal, joined against the table
    :markers    #{}
    :checked-by "http-dangling-route-refs resolves it against the DECLARED
                 client route table (rules.webapp/client-routes), using the
                 server's own matcher — legitimate because the two grammars are
                 pinned against each other by web.routes-test. A literal that
                 matches no client route and no server route still dangles"
    :blind      "the join reads route patterns that are LITERAL STRINGS in a
                 :webapp/routes table. A computed pattern is skipped rather than
                 guessed at, so an app that builds its table dynamically gets a
                 partial join — and its links read as dangling, which is the
                 safe direction but is still a false report.

                 This row is the one that stopped being a hole, and how is worth
                 keeping: it was unchecked because the mount point arrived
                 through an app's own function call and the route table was a
                 closure. Both became the framework's, and the escape hatch that
                 stood in for the check — ^:web/client-path, thirteen copies of
                 one accurate sentence in the one real app — retired with it"}

   {:kind       :cli/command
    :leaves     "a form's name metadata: the command's name, its one-line doc,
                 and the malli schemas for its positionals and options"
    :to         "argv — untrusted process input — and the launcher slopp
                 GENERATES at build time, which is the only code that reads it"
    :markers    #{:cli/command :cli/doc :cli/args :cli/opts}
    :checked-by "cli-args-schema refuses a command that declares no :cli/args,
                 so what argv is allowed to say is always written down;
                 cli-command-collision refuses two forms claiming one name;
                 cli-direct-stdio refuses a body that prints, reads a line or
                 exits rather than returning; query_surface's :cli section
                 reads the same metadata the gates enforce"
    :blind      "the generated launcher REQUIRES the namespaces it scans, and
                 `commands-in` finds commands with `find-ns` — so a namespace
                 that fails to load contributes no commands rather than
                 throwing, and the binary answers \"unknown command\" instead of
                 \"that namespace is broken\". Nothing compares the commands a
                 BUILT binary actually carries against the ones the store
                 declares, which is the same shape as the :http/route blind
                 spot one process further out"}])

(def internal-markers
  "Markers slopp owns that are deliberately NOT crossings, and why each stays
  inside.

  Without this the classification is partial, and a partial classification is
  worse than none here: every internal marker reads as an exit nobody checks,
  and the one real hole drowns in five false ones. That precision failure is
  what got the `:positional-form-access` advisory withdrawn, so it is a named
  hazard rather than a hypothetical."
  {:web/auth      "a policy the dispatcher enforces in-process, on data that
                   never leaves"
   :web/effectful "declares the handler performs its own effects — a statement
                   about where effects run, not about anything crossing"
   :web/client    "a MODIFIER on the generated-client crossing (opt this
                   endpoint out), not an exit of its own"
   :web/context   "names WHICH fn builds the perform-ctx — a declaration about
                   in-process assembly. The map it returns reaches handlers as
                   :web/deps and performers as their first argument, all inside
                   the image; nothing leaves through this key"
   :web/unconstrained-ok "waives rest-unconstrained-contract for an endpoint that
                   genuinely cannot constrain its shape — a statement ABOUT a
                   declaration, not a declaration of its own, so nothing
                   crosses through it"
   :rule/applies-to "the rule registry describing itself to itself"
   :rule/severity   "the rule registry describing itself to itself"
   :rule/capability "the rule registry describing itself to itself: whether a
                   gate is armed by the store's CAPABILITY config or by a
                   marker on the form. Read only by edit.gates/gate-capability
                   deciding whether to run a gate, so nothing crosses"})

(defn ^:export store-crossings
  "The store's boundary exits: which crossing kinds it actually has, which of
  those nothing checks, and any marker no kind claims.

  Returns `{:crossings [...] :unchecked [...] :unclassified [...]}` — always
  all three keys, empty vectors when there is nothing to say, because an
  absent key would read as unexamined.

  - **`:crossings`** — the registered kinds this store reaches, each with the
    forms that reach it. A kind nothing here uses is simply absent; the
    registry is the vocabulary, not the finding.
  - **`:unchecked`** — of those, the ones with no `:checked-by`. This is the
    output that matters: `slopp.index.refs` makes every edge INSIDE the store
    answerable, and there was no equivalent question for an edge leaving it,
    so each exit grew an ad-hoc check or none and nobody could tell which.
  - **`:unclassified`** — a `web/`, `malli/` or `rule/`-namespaced marker in
    use that no kind claims. This is what stops the registry rotting: an
    inventory that cannot notice a new exit describes the system it was
    written against, not the one you have.

  Scoped to slopp's OWN marker vocabulary on purpose. A user's namespaced
  metadata is theirs and means nothing to slopp, so treating it as an
  unclassified exit would bury the real finding in a store slopp knows
  nothing about."
  [st]
  (let [ours?  (fn [k] (and (qualified-keyword? k)
                            (contains? #{"web" "malli" "rule"} (namespace k))))
        owned  (into (set (keys internal-markers)) (mapcat :markers) kinds)
        marked (for [nsx  (keys (:namespaces st))
                     e    (store/forms st nsx)
                     :let [s (store/form-sexpr (:node e))]
                     :when (and s (symbol? (second s)))
                     k    (keys (meta (second s)))]
                 {:marker k :at (symbol (str nsx) (str (second s)))})
        hits   (group-by :marker marked)
        rows   (vec (for [{:keys [markers] :as k} kinds
                          :let [at (vec (sort (distinct (mapcat #(map :at (hits %))
                                                                markers))))]
                          :when (seq at)]
                      (assoc (dissoc k :markers) :at at)))]
    {:crossings    rows
     :unchecked    (vec (remove :checked-by rows))
     :unclassified (vec (sort-by (juxt :marker :at)
                                 (distinct (filter #(and (ours? (:marker %))
                                                         (not (owned (:marker %))))
                                                   marked))))}))

(defn ^:export finding
  "The `full_check` section for this store's boundary exits, or NIL when it
  has none worth saying.

  ADVISORY, and deliberately so. Every entry here is a hole someone already
  identified and wrote down — flipping the verdict on a standing documented
  gap would make `full_check` red forever, and a check that is always red is
  a check people stop running. What it buys instead is placement: the holes
  are named at the exact moment a whole-store green is about to be believed,
  which is the slot `:host-stale` occupies and works for the same reason.

  Nil rather than an empty section when there is nothing to report. The usual
  rule here runs the other way — an absent key reads as unmeasured — but this
  section is ABOUT holes, so 'no holes' and 'nothing to say' are the same
  statement, and printing it on every check of every store would be noise
  forever."
  [st]
  (let [{:keys [unchecked unclassified]} (store-crossings st)]
    (when (or (seq unchecked) (seq unclassified))
      (cond-> {:note (str "verification stops at the store's edge: "
                          (count unchecked) " exit kind(s) here have no checker"
                          (when (seq unclassified)
                            (str ", and " (count unclassified)
                                 " marker(s) belong to no exit kind at all"))
                          ". Nothing is wrong with the code — this names where"
                          " a mistake would not be caught, because an exit with"
                          " no check and an exit that does not exist look"
                          " identical otherwise")}
        (seq unchecked)    (assoc :unchecked (mapv #(select-keys % [:kind :to :blind :at])
                                                   unchecked))
        (seq unclassified) (assoc :unclassified unclassified)))))

(defn ^:export known-markers
  "Every namespaced marker slopp gives meaning to — the union of what [[kinds]]
  reports as crossing and what [[internal-markers]] declares stays inside.

  DERIVED rather than listed, and it is the one derivation two different
  questions ask:

  - [[unclassified-markers]] asks it about SLOPP'S OWN vocabulary — is every
    marker slopp produces accounted for by one registry or the other?
  - `rules/unknown-marker-check` asks it about a STORE'S FORMS — is this
    `:web/…` key on this endpoint one slopp actually reads?

  Those look similar and are opposites in direction, which is exactly why they
  must not each keep a list. A marker added to slopp and forgotten in one place
  would be reported as unclassified by the first, or as unknown-on-a-consumer's-
  form by the second — the same hand-kept-list failure the second rule exists to
  catch, one level up."
  []
  (into (set (keys internal-markers)) (mapcat :markers) kinds))

(defn ^:export unclassified-markers
  "Markers slopp's own surfaces produce that neither `kinds` nor
  `internal-markers` claims — empty when the classification is total.

  The guard on the guard. `store-crossings` can only report a marker as
  unclassified if it appears in a STORE; this asks the same question of the
  VOCABULARY, so a marker slopp defines and no store has used yet still has to
  be decided about.

  **The vocabulary below is HAND-KEPT, and that is the hole this guard cannot
  cover.** `:web/context` shipped in neither registry and this returned empty
  the whole time, because a marker nobody added to the list is invisible to a
  list. It was caught by the first APP to declare a builder, whose `full_check`
  then carried a permanent unclassified entry — the cost landing on adopters
  rather than on the author. So the real backstop is `store-crossings` in a
  store that USES the marker, one step later than intended and paid for by
  someone else. When you add a `:web/*` marker, add it here in the same write;
  nothing will remind you.

  **Scope: NAMESPACED keys only, and the split from `slopp.rules.markers`
  is deliberate rather than an oversight.** The two registries ask different
  questions about disjoint key spaces:

  - here — `:web/*`, `:malli/*`, `:rule/*`: does data pass through this key to
    something OUTSIDE the store, and does anything check it there?
  - there — `:unused-ok`, `:entry-point`, `:unsafe`: does this dial waive a
    rule, and should it say why?

  Merging them would report every escape dial as an unclassified crossing,
  which is precision failure by construction. `markers/undeclared` excludes
  namespaced keywords for the mirrored reason, so between them the store's
  marker vocabulary is partitioned rather than double-counted — pinned by
  `crossings-test/the-two-marker-registries-partition-the-vocabulary`."
  []
  (let [owned (known-markers)]
    (vec (sort (remove owned
                       [:web/path :web/method :web/auth :web/reads :web/effects
                        :web/read :web/effect :web/effectful :web/request
                        :web/response :web/client :web/context
                        :web/client-routes :web/external-path
                        :malli/schema :rule/applies-to :rule/severity
                        :rule/capability])))))
