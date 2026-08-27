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
  [{:kind       :http/routing
    :leaves     "a form's name metadata"
    :to         "the served route table, and from there HTTP"
    ;; both path markers: a REST api and general HTTP content leave the
    ;; process the same way, and an exit the registry cannot name is one
    ;; nothing reports on
    :markers    #{:rest/path :http/path :http/method :http/media-type}
    :checked-by "http-dangling-route-refs ties every literal :href/:src to a
                 route; query_surface reads the same metadata the gates enforce"
    :blind      "the SERVED table is built from interned vars in the running
                 process, not from the store, so a route can outlive its
                 definition until the host reloads.

                 And a store that calls `slopp.webapp/prefix-links` itself gets
                 NO LITERAL matching: its :href literals are in app space while
                 the table is in server space, and the prefix between them is
                 route state. That is now DECLARED rather than silent — every
                 literal lands in :unresolved carrying the reason — but
                 declared is not checked, and this is the shape where a report
                 says 'cannot answer' and a reader hears 'fine'.

                 Stated as LITERAL matching deliberately, because 'no link
                 check' was an overstatement this file carried for one day and
                 the consuming store corrected. A headless drive CLICKS real
                 links through the real router and asserts where they land, so
                 a store in this shape can have real coverage by EXECUTION over
                 the views its drives reach — and none over the views they do
                 not. Partial by execution, absent by literal, and the two are
                 not interchangeable"}

   {:kind       :wire/json
    :leaves     "a declared request/response contract"
    :to         "JSON, and a browser that never sees Clojure data"
    ;; `:rest/media-type` rides here because it describes the ENCODING this
    ;; crossing uses. The crossing's name says JSON because that is the
    ;; default and the common case; an endpoint answering EDN is still this
    ;; crossing, and a generated client that assumed JSON failed on the first
    ;; character of one.
    :markers    #{:rest/request :rest/response :rest/media-type}
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

   {:kind       :http/vocabulary
    :leaves     "a declared read/effect KIND, resolved by name"
    :to         "a performer found by scanning namespaces the caller lists
                 by hand"
    :markers    #{:http/reads :http/effects :http/read :http/effect}
    :checked-by "web/context refuses at assembly when a declared kind has no
                 performer among its namespaces"
    :blind      nil}

   {:kind       :webapp/client-routing
    :leaves     "a prefix declared as client-routed"
    :to         "a route table that lives in the browser"
    :markers    #{:webapp/client-routes}
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
{:kind       :webapp/page-address
    :leaves     "a PAGE function, carrying the address the browser routes to it"
    :to         "the browser's route table — GENERATED into the entry the build
                 writes, and scanned off the loaded vars for a headless drive"
    :markers    #{:webapp/path}
    :checked-by "webapp-page-address refuses at the write: a pattern no shell
                 could match, a private page the router could never call, and a
                 second page claiming one address. pages-unserved joins each
                 page to the shells this store serves, through the SAME
                 router/match the server uses — one coordinate system on both
                 sides, which is what the prefix comparison above never had"
    :blind      "the table reaches the browser by CODE GENERATION and the
                 headless one by scanning `all-ns`, so the two agree by both
                 reading this marker and not by being the same value. A page in
                 a namespace the build does not require is named and not
                 reachable — the launcher adds every page's namespace for that
                 reason, and a store that hand-writes its own mount is on its
                 own. And nothing joins a page's address to the LINKS that
                 point at it: an :href to an address no page claims is a
                 not-found the reader finds, not the store"}
{:kind       :webapp/shell
    :leaves     "a stored hiccup document declared as the app's SPA shell"
    :to         "a browser, which loads the compiled bundle it names and mounts
                 into the element it carries"
    :markers    #{:webapp/shell}
    :checked-by "slopp.http/context completes every shell at ASSEMBLY and
                 refuses one with no [:head …] to inject the script into, or no
                 mount point to render into — so an app that cannot work does
                 not come up, rather than answering a blank page to whoever
                 loads it first"
    :blind      "the two NAMES are hard-coded on both sides and nothing joins
                 them: this end writes id=\"app\" and data-base, and
                 slopp.webapp.dom/mount! reads exactly those. That namespace is
                 :cljs, so slopp has no runner that could assert the agreement —
                 a rename on either side compiles, serves, and renders a blank
                 page.

                 CURRENT rather than permanent, and saying which is the point:
                 **a ClojureScript test runner would end it**, and so would
                 moving the two names into one .cljc value both sides read. The
                 second needs no new tooling and is the cheaper of the two. If
                 either lands, delete this paragraph rather than annotating it.

                 The BUNDLE url used to be taken on trust — the marker held it,
                 so a typo was a script tag pointing at a 404, the same blank
                 page from a different cause. That is closed: the marker is a
                 BOOLEAN and the url is derived from the compile output joined
                 to the static mount that serves it, so a shell with no
                 reachable bundle refuses at assembly like the other two."}

   {:kind       :app/headless-entry
    :leaves     "the whole application as data — state, a route TABLE, the
                 screens its rows name, and the handlers sitting in its tree"
    :to         "slopp.cljnx's driver, which opens it on a JVM and
                 presses things"
    :markers    #{:app/entry}
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
                 :http/path rather than a concrete url. Its own limit: an
                 absolute url is skipped as somebody else's server, so a typo in
                 one is invisible, and a computed path is skipped rather than
                 guessed at, which makes the join partial in the safe direction"}

   {:kind       :http/foreign-route
    :leaves     "a link to a path this store does not serve"
    :to         "somebody else's server"
    :markers    #{:http/external-path}
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
                 stood in for the check — ^:webapp/client-path, thirteen copies of
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
                 declares, which is the same shape as the :http/routing blind
                 spot one process further out"}])

(def internal-markers
  "Markers slopp owns that are deliberately NOT crossings, and why each stays
  inside.

  Without this the classification is partial, and a partial classification is
  worse than none here: every internal marker reads as an exit nobody checks,
  and the one real hole drowns in five false ones. That precision failure is
  what got the `:positional-form-access` advisory withdrawn, so it is a named
  hazard rather than a hypothetical."
  {:http/auth      "a policy the dispatcher enforces in-process, on data that
                   never leaves"
   :http/effectful "declares the handler performs its own effects — a statement
                   about where effects run, not about anything crossing"
   :http/context   "names WHICH fn builds the perform-ctx — a declaration about
                   in-process assembly. The map it returns reaches handlers as
                   :http/deps and performers as their first argument, all inside
                   the image; nothing leaves through this key"
   :rest/unconstrained-ok "waives rest-unconstrained-contract for an endpoint that
                   genuinely cannot constrain its shape — a statement ABOUT a
                   declaration, not a declaration of its own, so nothing
                   crosses through it"
   :rule/applies-to "the rule registry describing itself to itself"
   :rule/severity   "the rule registry describing itself to itself"
   :rule/capability "the rule registry describing itself to itself: whether a
                   gate is armed by the store's CAPABILITY config or by a
                   marker on the form. Read only by edit.gates/gate-capability
                   deciding whether to run a gate, so nothing crosses"})

(def ^:export owned-marker-namespaces
  "The keyword namespaces slopp gives meaning to — the scope of every question
  of the form \"is this marker OURS?\".

  Includes the capability catalog's two OWNER rows: `app`, which every project
  has and which `:app/entry` belongs to precisely because that marker spans
  both app types, and `slopp`, which is reserved. `web` stays after the family
  was retired, and that is the point of keeping it: a consumer still spelling
  `:web/anything` is exactly who these checks are for.

  **ONE producer, because two copies drifted and the drift was silent.** The
  boundary inventory scoped itself to `#{web malli rule}` while the unknown
  marker rule used the full set. After the marker waves moved slopp's
  vocabulary to `:http/*`, `:rest/*` and `:webapp/*`, the inventory was
  policing a family that owns no live markers and ignoring every family that
  does — so the check whose whole job is to notice the registry rotting had
  rotted, in the direction where it reports nothing and looks fine.

  It survived the rename because it is a set of bare family-NAME strings, not
  markers: no sweep of `:web/x` can see the string `\"web\"`."
  #{"web" "webapp" "cli" "rest" "rule" "malli" "http" "app" "slopp"})

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
  - **`:unclassified`** — a marker in use, in one of the namespaces slopp owns
    ([[owned-marker-namespaces]]), that no kind claims. This is what stops the registry rotting: an
    inventory that cannot notice a new exit describes the system it was
    written against, not the one you have.

  Scoped to slopp's OWN marker vocabulary on purpose. A user's namespaced
  metadata is theirs and means nothing to slopp, so treating it as an
  unclassified exit would bury the real finding in a store slopp knows
  nothing about."
  [st]
  (let [ours?  (fn [k] (and (qualified-keyword? k)
                            (contains? owned-marker-namespaces (namespace k))))
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
  cover.** `:http/context` shipped in neither registry and this returned empty
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
                       [:rest/path :http/path :http/method :http/auth :http/reads :http/effects
                        :http/read :http/effect :http/effectful :rest/request
                        :rest/response :rest/media-type :http/media-type :http/context
                        :webapp/client-routes :webapp/shell :http/external-path
                        :malli/schema :rule/applies-to :rule/severity
                        :rule/capability])))))

(def ^:export deleted-markers
  "Markers slopp USED to read and has RETIRED outright, as
  `{\"old/name\" \"what to do instead\"}`.

  **The sibling [[retired-markers]] cannot hold these, and that is the bug this
  exists for.** That table maps old→new, so every row can say *the current
  spelling is X*. A marker with no successor produces no row — and no row reads
  as an all-clear. Reported by a consuming store at the boot after
  `:rest/client` went: at ONE boot it carried a RENAMED marker (reported,
  correctly) and nine forms declaring a DELETED one (silent), and a stylesheet
  still carried the dead flag with a docstring explaining why it was necessary.

  **The deleted case is the worse half.** A renamed marker usually breaks
  something visible; a deleted one quietly stops meaning anything, and every
  reader afterwards reasons from a declaration nothing reads.

  **And it can carry MORE than a rename can**, because the reason it went is
  known. \"Drop it\" plus why is a complete instruction, the way *re-run
  whatever writes them* is for a generated form — where a rename can only ever
  hand over a new spelling.

  The key is a STRING for [[retired-markers]]'s reason, and it is sharper here:
  a sweep rewrites every occurrence of the keyword it is renaming, and a ledger
  is nothing but occurrences. Written without the leading colon nothing can
  match it.

  **A partition is exactly the kind of change that deletes vocabulary rather
  than moving it**, so expect this table to grow when a concept splits."
  {"rest/client"
   (str "content is excluded from a client and from the published contract by"
        " KIND now — an `:http/path` form is not part of a typed API — so a"
        " page needs no flag. And an API can no longer opt out at all: whether"
        " to generate a client is the generating CONSUMER's question, asked"
        " against the document, and an endpoint does not know who will call it."
        " Drop the marker. If the endpoint answers something other than JSON,"
        " that fact is `:rest/media-type`")

   "webapp/from-origin"
   (str "a request now names the base it is measured from:"
        " `:webapp/base \"\"` says exactly what this flag said, as a VALUE."
        " Replace it. The boolean existed because `:webapp/base` was one"
        " scalar per APP and an app calling two APIs needed one of them to be"
        " an exception — so it was never about origins, it was a per-call"
        " escape from a field that could not hold two values. The field holds"
        " one per REQUEST now, so the escape has no cause and is gone rather"
        " than deprecated: nothing reads it, and a request still carrying it"
        " is addressed under the app's base like any other")})

(def ^:export retired-markers
  "The marker renames of the `:web/*` → owning-capability wave, as
  `{\"old/name\" :new/name}`.

  **Two consumers, and only one of them is visible to the reference graph.**
  `slopp.read.orient/unread-declarations` reads it to tell a store which
  CURRENT spelling a retired marker maps to. A repo-side script also reads it
  to check the SHIPPED docs, and has to live outside the store: both test tiers
  run in a materialized temp dir where those docs resolve to nothing, so an
  in-store version read ZERO files and passed every absence assertion
  vacuously — the exact failure it exists to prevent. That second reader is why
  this carried an `^:unused-ok` waiver until the first one existed.

  **The retired side is a STRING, and that is the whole reason this form works
  at all.** It was written with keyword keys and the sweep ATE it: a rename
  rewrites every occurrence of the keyword it is renaming, and a ledger is
  nothing but occurrences of that keyword. Fifteen of forty entries came back
  mapping each new name to itself — not a wrong table so much as no table at
  all. Written without the leading colon nothing can match it, because every
  sweep pattern begins with one.

  The general rule, which cost a form to learn: **a rename ledger must be
  written in a spelling the rename cannot match.** The same is true of prose
  describing a rename, which is why a comment saying `a -> b` comes out saying
  `b -> b`.

  **A LEDGER, rebuilt deliberately and meant to be retired again.** slopp had
  one — a declared old→new table plus checks reading it — retired on 2026-08-06
  once its restructure finished, because it had become a hand-kept list for a
  conversion nobody was doing. AGENTS.md says to rebuild something like it if a
  rename of that scale recurs and not to carry it between times.

  **The rule the table encodes: a marker takes the prefix of the capability
  whose code READS it** — not where the data comes from. That is what makes a
  stale marker detectable, and what `:cli/command` always did while the web
  family was the exception. Two entries are judgements rather than
  derivations: the contract pair is rest's (inert while `rest.enabled` is
  false, unrelated to whether HTTP works), and `unconstrained-ok` follows the
  advisory it discharges rather than the family it was spelled in.

  **A destination that is itself retired must LEAVE**, not point somewhere
  dead. `web/client` mapped to `:rest/client`, which no longer exists — so a
  consuming store carrying the old spelling would have been told to rename to a
  marker nothing reads, which is the exact failure this table exists to
  prevent, arrived at from the other end. Row removed. What such a store needs
  is not a rename: content declares `:http/path` and needs no flag, and an
  endpoint answering something other than JSON says `:rest/media-type`.

  **Three names are held out, and all three are the same shape.**

  - `web/spa` was retired long ago; every occurrence left is an incident record
    or a test whose SUBJECT is the retired spelling. Sweeping those invents a
    past.
  - `web/websocket` was never a real marker. It is the FIXTURE of
    `crossings-test/the-inventory-reports-holes-and-refuses-to-miss-a-new-one`,
    which needs a slopp-namespaced marker no crossing kind claims. Swept once,
    and the result is worth stating: renaming the fixture AND the registry
    together made the deliberately-unclaimed marker claimed, so the test that
    exists to report holes reported none.
  - `web/summary` names nothing anywhere. It appears in a comment arguing that
    an endpoint's prose belongs in its docstring RATHER than in a second marker
    beside it. The consuming store found this class first, in their own store,
    and this store turned out to carry one too.

  So: **this table is a complete account of what the PRODUCER reads, and never
  a complete account of what a CONSUMER wrote down.** Prose about a rejected
  design is invisible to a derived table by construction. The instruction that
  travels is not the table — it is *derive your own census from RENDERED
  source, diff it against this table, and open every marker the table does not
  explain.* **A census tells you which markers are WRITTEN; only reading each
  one tells you which are MEANT.**

  Rendered source, not sexprs, and that is measured rather than stylistic:
  `form-sexpr` drops reader metadata, so every marker on a defn is invisible to
  it. Measured here, 1582 occurrences via `rewrite-clj.node/string` against
  1353 via `form-sexpr` — and in a consumer whose markers live ONLY in metadata
  the gap swallowed six markers whole, including the subject of the wave."
  '{"web/page"              :app/entry
    "web/client-routes"     :webapp/client-routes
    "web/client-path"       :webapp/client-path
    "web/request"           :rest/request
    "web/response"          :rest/response
    "web/unconstrained-ok"  :rest/unconstrained-ok
    "web/path"              :http/path
    "web/method"            :http/method
    "web/auth"              :http/auth
    "web/auth-config"       :http/auth-config
    "web/namespaces"        :http/namespaces
    "web/routes"            :http/routes
    "web/reads"             :http/reads
    "web/read"              :http/read
    "web/read-performers"   :http/read-performers
    "web/effects"           :http/effects
    "web/effect"            :http/effect
    "web/effect-performers" :http/effect-performers
    "web/effectful"         :http/effectful
    "web/perform-ctx"       :http/perform-ctx
    "web/deps"              :http/deps
    "web/context"           :http/context
    "web/context-builders"  :http/context-builders
    "web/port"              :http/port
    "web/host"              :http/host
    "web/adapter"           :http/adapter
    "web/max-body-bytes"    :http/max-body-bytes
    "web/sub"               :http/sub
    "web/groups"            :http/groups
    "web/provider"          :http/provider
    "web/identity"          :http/identity
    "web/raw"               :http/raw
    "web/status"            :http/status
    "web/public"            :http/public
    "web/external-path"     :http/external-path
    "web/wrap-context"      :http/wrap-context
    "web/missing-performers" :http/missing-performers
    "web/keys"              :http/keys
    "web/vocabulary"        :http/vocabulary})
