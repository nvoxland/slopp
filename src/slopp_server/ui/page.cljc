(ns slopp-server.ui.page
  "The entry a headless driver opens — this application, wired for LOOKING AT.

  `screen` finds the zero-arg fn marked `^:app/entry` and runs a step script
  through the app's own handlers, so a screen can be read without a browser, a
  server, or a project to talk to. Three write gates keep that possible: an
  entry in a `:cljs` namespace, one taking arguments, or a second `^:app/entry`
  all refuse rather than failing when someone reaches for the tool.

  **The marker is `app`'s rather than a capability's** because it is the one
  that genuinely spans: `slopp.cljnx/driver-for` reads it headless, `build!`
  generates a browser launcher from it, and the edit gates police it. It was
  `^:web/page` until D-cljnx, and the rename is what made the change to its
  MEANING detectable — an entry still returning a driver under the old spelling
  would have mounted the wrong thing in silence.

  **Alone in this namespace because it mints an atom**, which is `:internal`.
  Beside `wiring` and `act` it would have loosened the tier those two exist to
  hold — they are `:pure` precisely so a driver can call them freely, and that
  is the property the whole exercise rests on.

  The canned responses are BY SCREEN. One shape for all of them rendered the
  module page with an empty heading over a diagram belonging to somewhere else,
  and nothing threw — a fixture bug, visible in one look and invisible in any
  assertion I would have thought to write."
  (:require [slopp-server.ui.app :as app] [slopp.webapp :as webapp] [clojure.string :as str]))

(def contract-document
  "The canned `GET /api/contracts` answer, shared by BOTH API screens.

  Its own def rather than two entries in [[responses]], because the index and
  one endpoint's page are two views of ONE response — duplicated, the fixture
  could describe two different APIs and every assertion would still pass.

  FOUR endpoints, one per axis the section reads, because a document of four
  identical GETs would show that the screens render and nothing about what
  they say. In order: a plain GET with a deep response; a GET whose path
  parameter its request schema does NOT declare (the live document's real
  inconsistency, not an invented one); a GET with optional query parameters;
  and a POST whose response is an `:or`, which `schema/rows` deliberately does
  not flatten — the case where the type line is the only thing standing
  between the reader and a blank.

  **Contract version 2, and every endpoint carries all ten keys.** This used to
  omit `:handler`, `:media-type` and `:auth` from most entries, deliberately,
  to model a project on an older jar. Two things retired that: slopp made those
  keys REQUIRED under v2, and Nathan ruled that this project codes no
  migrations — every side restarts together, so there is one document shape in
  play and a fixture modelling an older one is a shape no server sends. That is
  the same defect as the `:effectful?` note below, pointing the other way, and
  `client.check-map/for-path` now checks this document against its published
  contract so the two cannot drift again."
  {:slopp/rest-paths-version 1
   :paths [{:method :get :path "/api/modules" :name 'modules
                :handler 'demo.api.endpoints/modules
                :doc "GET /api/modules — every module, its namespaces and its gaps. The layers are a TOPOLOGICAL order, not an alphabetical one."
                :media-type "application/json"
                :auth :public
                :effectful? false
                :request nil
                :response [:map
                           [:modules [:sequential
                                      [:map [:module :string]
                                       [:namespaces [:sequential :string]]
                                       [:tier [:enum "pure" "internal" "external"]]
                                       [:gaps [:map [:forms :int] [:no-doc :int]]]]]]
                           [:cycles [:sequential [:sequential :string]]]]}
               {:method :get :path "/api/module/:m" :name 'module
                :handler 'demo.api.endpoints/module
                ;; a REAL docstring, copied from slopp2's live document. Three
                ;; of four endpoints here carried none, which left the driven
                ;; index showing one description and three blank cells — a
                ;; screen that does not look like the screen a reader gets, on
                ;; the page whose whole editorial rule is one line each.
                :doc "GET /api/module/:m — one module from the inside: its namespaces, the edges among them, the layering, and what crosses its boundary."
                :media-type "application/json"
                ;; **the one endpoint whose auth NARROWS**, and the only thing
                ;; that drives `endpoints-main`'s `narrows?` branch — the index
                ;; marks auth only where it is not `public`, so a fixture that
                ;; is public throughout shows that the column renders and
                ;; nothing about when it speaks. The value is from
                ;; `schema/auth-label`'s own documented vocabulary rather than
                ;; invented here, and `schema-test` already pins the same one.
                :auth [:group "admin"]
                :effectful? false
                :request nil
                :response [:map [:module :string]
                           [:tier [:enum "pure" "internal" "external"]]]}
               ;; the one endpoint here whose fields carry PROSE, and the others
               ;; deliberately do not: the screen has to render a list that is
               ;; partly documented, which is what a real store looks like while
               ;; anyone is still writing the docs. A fixture where every field
               ;; had one would show that the paragraph renders and nothing
               ;; about the mixed case.
               ;;
               ;; `:limit` uses `:description` — malli's JSON-Schema spelling —
               ;; because an imported schema will, and the reader of the screen
               ;; should not be able to tell which vocabulary the author used.
               {:method :get :path "/api/search" :name 'search
                :handler 'demo.api.endpoints/search
                ;; `:doc` is NULL, not absent. Under v2 the key is required and
                ;; `[:maybe :string]`, so a handler with no docstring publishes
                ;; nil — which keeps the index's `—` placeholder reachable
                ;; without pretending the key can be missing.
                :doc nil
                :media-type "application/json"
                :auth :public
                :effectful? false
                :request [:map
                          [:q {:optional true :doc "what to search for; blank matches nothing"}
                           :string]
                          [:limit {:optional true :description "how many hits to return, at most"}
                           :int]]
                :response [:map [:query :string]
                           [:total {:doc "hits BEFORE the limit is applied"} :int]
                           [:hits [:sequential
                                   [:map [:kind [:enum "module" "namespace" "form"]]
                                    [:name :string]
                                    [:doc {:optional true} :string]]]]]}
               ;; the only MUTATION here, so it is what exercises the two-press
               ;; arming — a fixture where nothing is effectful would show that
               ;; the button renders and nothing about the gate.
               ;;
               ;; **It used to say so with `:effectful? true` while NO slopp
               ;; server published the key at all.** So this fixture was the
               ;; only place in the world where the arming gate saw a true
               ;; value: it passed every test and never engaged in production.
               ;; A fixture that invents a field is not merely unrepresentative,
               ;; it can PROP UP the bug it exists to exercise, and the more
               ;; carefully the assertions are written the more convincing the
               ;; result.
               ;;
               ;; v2 publishes `:effectful?` for real, so it is here — matching
               ;; the wire, and NOTHING READS IT. `schema/effectful?` derives
               ;; from `:method`, which works on every producer including ones
               ;; older than the decision. Treat this key as confirmation, the
               ;; way slopp asked it be treated.
               {:method :post :path "/api/register" :name 'register!
                :handler 'demo.api.endpoints/register!
                :doc nil
                :media-type "application/json"
                :auth :public
                :effectful? true
                :request [:map [:name :string] [:dir :string]
                          [:pid {:optional true} [:maybe :int]]]
                :response [:or [:map [:slug :string] [:beat-ms :int]]
                           [:map [:error :string]]]}]})

(def answers
  "Which canned answer a request PATH gets — the arrow from a declared
  `:webapp/path` to a key in [[responses]].

  Needed because a screen carries its own `:request` now, so the fixture
  answers a URL rather than a screen. That is the stronger shape on this
  project's own argument: two screens that reach equal state having asked
  DIFFERENT endpoints are indistinguishable to a fake keyed by screen.

  **`/api/contracts` serves BOTH API screens**, which is the case a
  screen-keyed fixture had to state twice and could state inconsistently. The
  index and the per-endpoint page read one document; that they do is the reason
  neither has its own load.

  A path absent here answers nil, which every screen already renders honestly —
  the same stance `responses` takes, one level out."
  {;; the HUB's answer rather than a project's, and a session LOAD rather than
   ;; a screen's. It used to be seeded into state by `:boot`; it is a declared
   ;; request now, so the fixture answers it the same way it answers a screen's.
   "/api/projects"            :projects
   "/api/timeline"            :timeline
   ;; the namespace INDEX. The Dashboard is the only screen that asks for TWO
   ;; documents, and because this fixture answers by PATH rather than by
   ;; screen, that costs one row here and no special case anywhere.
   "/api/namespaces"          :dashboard
   ;; the COST journal, the Dashboard's third ask. Keyed by path like the
   ;; rest, so the `?by=` split does not need its own fixture vocabulary.
   "/api/cost"                :cost
   "/api/modules"             :code
   "/api/search"              :search
   "/api/rest/paths"          :rest-paths
   ;; the CONTENT sibling, serving both Pages screens the way the API document
   ;; serves both API screens.
   "/api/http/paths"          :http-paths
   ;; the WEBAPP sibling — the third path document, serving both Webapp
   ;; screens the way each of the other two serves its own pair.
   "/api/webapp/paths"        :webapp-pages
   ;; the CONFIG document — one screen rather than a pair, because its landing
   ;; is its only page.
   "/api/config"              :config
   "/api/change/:range"       :change
   "/api/ns/:ns"              :ns
   "/api/module/:m"           :module
   "/api/form/:id"            :form
   ;; the STORY lens, both grains — one fixture, since the screen reads the
   ;; grain from the document rather than from the address
   "/api/story/:grain/:subject" :story
   ;; the BEHAVIOUR axis: the doors, one form's trace, the path between two
   "/api/entries"             :entries
   "/api/form/:id/sequence"   :sequence
   "/api/flow"                :flow
   ;; the DIAL — asked by the treemap always and by the map when one is chosen
   "/api/overlay/:dial"       :overlay
   ;; the DATA DICTIONARY, index and chosen key in one document
   "/api/data"                :data
   "/api/source/:ns/:name"    :source})

(def content-document
  "The canned `GET /api/http/paths` answer, shared by BOTH Pages screens.

  Its own def rather than two entries in [[responses]], for the reason
  [[contract-document]] is: the index and one page's screen are two views of
  ONE response, and duplicated they could describe two different apps while
  every assertion passed.

  **These are this store's REAL content forms**, measured off
  `/api/http/paths` on jar `d36734` rather than invented — the first populated
  content document either side of the split had seen. They differ on every axis
  a screen reads, which is why they are the whole fixture:

  - the SHELL declares no `:http/media-type` and the document reports the
    DERIVED one, because a vector serves `text/html`. It boots an application,
    so it carries `:shell`, and it is a whole document (`:root-tag :html`,
    which is what makes the renderer prepend a doctype).
  - the STYLESHEET declares its media type verbatim, is `:text` rather than
    hiccup, is sized in `:bytes` rather than `:nodes`, boots nothing, and
    carries no docstring — which keeps the index's `—` placeholder reachable.

  The empty case is NOT here and is not an oversight: it is a different
  document, driven directly in
  `views-test/the-pages-section-is-reachable-and-carries-its-own-rail`, because
  a fixture cannot be both populated and empty and the empty one is the case
  most projects actually send."
  {:slopp/http-paths-version 1
   :paths [{:path "/" :method :get :name 'shell
            :handler 'slopp-server.ui.shell/shell
            :auth :public
            :media-type "text/html; charset=utf-8"
            :shape :hiccup :root-tag :html :nodes 19
            :shell "/assets/cljs/main.js"
            :doc (str "GET / — the whole application, as one stored document.\n\n"
                      "**The only page this process serves.** Everything under `/p/<slug>` is a\n"
                      "CLIENT route beneath this shell rather than a document of its own.\n\n"
                      "The framework adds exactly two things and this must not write either —\n"
                      "the `<script>` from `:webapp/shell`, and the mount prefix stamped as\n"
                      "`data-base`.")}
           {:path "/css/style.css" :method :get :name 'stylesheet
            :handler 'slopp-server.ui.styles/stylesheet
            :auth :public
            :media-type "text/css"
            :shape :text :bytes 14263
            ;; `:doc` is NULL rather than absent — the key is required and
            ;; `[:maybe :string]`, so a def with no docstring publishes nil.
            :doc nil}]})

(def webapp-document
  "The canned `GET /api/webapp/paths` answer, shared by BOTH Webapp screens.

  Its own def, like [[contract-document]] and [[content-document]], so the
  index and the detail screen cannot describe two different applications.

  **Rows are REAL pages of this store**, because the detail screen is addressed
  by var and a made-up one would drive a not-found page while looking like a
  screen that rendered — the same reason the content fixture uses this store's
  own shell.

  Three rows, differing on the axes the screens read:

  - one with SEVERAL calls, so the index's list and the rail's count are driven
    by more than one element;
  - one with a call to a PARAMETERISED endpoint, because `:calls` links into
    the REST section and `/api/form/:id` is the address shape that has to
    survive being turned into a link;
  - one with **no `:calls` key at all** — absent rather than empty, which is
    slopp's rule and the branch that says *fetches nothing*. A fixture where
    every page fetched something would show that the column renders and nothing
    about what it says when the graph saw nothing.

  **`:doc` is OMITTED on that row rather than nil.** The published contract
  declares it `{:optional true} :string`, so a nil would fail the very check
  `every-canned-answer-satisfies-its-published-contract` exists to run — and
  absent is what the producer actually sends for a page with no docstring.

  **`:calls` is not in the published contract**, which is a real gap rather
  than a shape this fixture invented: the wire carries it — measured on this
  store's own `/api/webapp/paths` — and `webapp-paths-response` declares only
  `:path`, `:page` and `:doc`. malli maps are open so this validates, and a
  generated client cannot see the column the Webapp section is built on.
  Reported to slopp."
  {:paths
   [{:path "/p/:slug/store"
     :page 'slopp-server.ui.pages/code-page
     :doc "The Code index — the module diagram and the store's shape.\n\n**The diagram is laid out HERE rather than in the view.** It was a route row's `:derive`."
     :calls [{:endpoint 'slopp-server.ui.wire.api/modules :method :get :path "/api/modules"}]}
    {:path "/p/:slug/store/form/:id"
     :page 'slopp-server.ui.pages/form-page
     :doc "One form — what it is, what it calls, and its source behind a lens."
     :calls [{:endpoint 'slopp-server.ui.wire.api/form :method :get :path "/api/form/:id"}
             {:endpoint 'slopp-server.ui.wire.api/modules :method :get :path "/api/modules"}]}
    {:path "/p/:slug/about"
     :page 'slopp-server.ui.pages/about-page}]})

(defn answer-for
  "Which canned response `path` should get — [[answers]] matched as PATTERNS.

  **[[answers]] is keyed `/api/form/:id`, and a url arriving at the performer is
  concrete.** `slopp.http.endpoint/request` resolves the whole address now, so a
  map lookup misses every parameterised endpoint — and misses it SILENTLY,
  answering nil, which each screen renders as its own honest empty state:
  `nothing to show — this range came back empty` for a fixture that has the
  range.

  Keying the fixture by the endpoint's own pattern is still right: it makes an
  answer a fact about a URL rather than about which project a screen happened to
  be looking at. So the pattern stays and the MATCHING moves here.

  `match-route` rather than a matcher written here: the keys are paths with
  captures, which is exactly what that function reads, and a private lookalike
  would be free to disagree with the router about what `:id` matches."
  [path]
  (:screen (webapp/match-route (vec answers) path)))

(def config-document
  "The canned `GET /api/config` answer — the Config section's only load.

  Its own def, like [[contract-document]] and [[webapp-document]], though for a
  weaker reason: there is one Config screen, so nothing here can describe two
  applications. It is separate because it is long and because the rows are
  chosen rather than typical.

  **Shapes measured off this store's own `/api/config`**, not invented — the
  owner docs and the family patterns are slopp's own words, trimmed.

  One row per branch [[slopp-server.ui.views/config-main]] has, because a fixture of
  five ordinary settings would show that the table renders and nothing about
  the distinctions the document goes to trouble to make:

  - **set** — `http.port`, carrying `:set` and the raw `:value` beside a parsed
    `:effective`.
  - **defaulted** — `http.enabled`, with no `:set` key at all. Absent means
    defaulted, which is a different fact from set-to-the-default, and it is the
    pair most easily collapsed into one column.
  - **withheld** — a bearer token. `:set` is true and `:value`/`:effective` are
    gone: *this is configured and I am not showing you*. A fixture without one
    would leave the branch that says so undriven, and the failure it guards
    against — a secret rendering as though it were unset — reads as a fact
    about the project rather than as a redaction.
  - **unset** — `app.name`, `:effective` present and nil. The producer sends
    the key with a nil rather than omitting it, so the fixture does too.
  - **a value that is not a scalar** — `http.auth.providers` defaults to a SET.
    It is the reason the view uses `pr-str` rather than `str`: this is the one
    column where a reader is trying to tell types apart, and `str` renders a
    set and a string indistinguishably.

  **`:bundle` is DECLARED now, and this paragraph used to say it was not.**
  For a day it was on the wire and absent from `config-response`, so a
  generated client could not see the field — malli maps are open, so it
  validated and nothing went red. Reported, and slopp both declared it
  `{:optional true}` and built the check that finds the whole class:
  `a-document-ships-NOTHING-its-contract-does-not-DECLARE`, which validates each
  real answer against `mu/closed-schema` of its own contract.

  Kept visible because the CAUSE was diagnosed wrong twice, by both sides,
  before it was found. It was never the jar: a `--live` host reloads from the
  STORE, and `:rest/response` is evaluated at DEF TIME, so the schema is a
  value baked into the endpoint var's metadata. slopp changed the contracts
  namespace and not the endpoints namespace, `--live` correctly skipped the
  one whose source had not changed, and the host served the old contract
  indefinitely. Fixed in `boot/with-dependents` — changed namespaces plus
  everything that transitively requires them.

  The fixture keeps `:bundle` because the endpoint really sends it and because
  the bundle is the fact this page exists to make findable."
  {:config
   [{:key "app.name" :owner "app" :effective nil :default nil
     :doc "Application name. Unset = the store directory name at build time."}
    {:key "app.version" :owner "app" :effective "0.0.0" :default "0.0.0"
     :doc "Application version, carried into build artifacts."}
    {:key "http.enabled" :owner "http" :effective true :default false
     :set true :value "true"
     :doc "Whether this project serves HTTP at all."}
    {:key "http.port" :owner "http" :effective 7359 :default 7357
     :set true :value "7359"
     :doc "The port the server binds.\n\nMore prose that the table must not show."}
    {:key "http.auth.providers" :owner "http" :effective #{:static}
     :default #{} :set true :value "#{:static}"
     :doc "Which identity providers are consulted, in order."}
    {:key "http.auth.bearer.tokens.ci" :owner "http" :secret true :set true
     :doc "A bearer token for CI. Its value never publishes."}
    {:key "rest.enabled" :owner "rest" :effective true :default false
     :set true :value "true"
     :doc "Whether this project publishes typed contracts."}
    {:key "webapp.enabled" :owner "webapp" :effective true :default false
     :set true :value "true"
     :doc "Whether the browser owns routing and state."}]

   :owners
   {"app"    "any project, whatever kind of application it is"
    "http"   "an HTTP server: routing, static mounts, identity and authorization. Present in every store, inert until http.enabled"
    "rest"   "a typed API: request/response contracts, boundary validation, and generated clients derived from the same schemas"
    "webapp" "an application whose BROWSER owns routing and state: client-side routes, event dispatch, and the ClojureScript build"}

   :patterns
   [{:key "http.static.*" :owner "http"
     :doc "Static mount: the key's tail is the URL prefix, the value a files-manifest path prefix."}
    {:key "http.auth.bearer.*" :owner "http"
     :doc "Bearer-provider settings. Values in this family never publish."}]

   ;; a stored key under a name this build does not claim — the migration
   ;; instruction, carrying its value so the row says what to re-set. The
   ;; capabilities wave renamed `web.*` to `http.*`, so this is the real shape
   ;; of the real rename rather than an invented one.
   :orphaned
   [{:key "web.port" :value "7357"}]

   :bundle "/assets/cljs/main.js"})

(def responses
  "Canned wire responses, BY SCREEN — what each endpoint would answer.

  One response for every screen is what a first version does and it is wrong in
  a way worth recording: the module screen received the modules INDEX, so
  `module-main` destructured `:module` to nil and rendered `# ` — an empty
  heading — over `0 namespaces` and a diagram belonging to a different page.
  Nothing threw. It was visible in one look and would have survived any
  assertion I thought to write, which is the entire argument for looking.

  Shapes are the real ones, from `/api/contracts` — two modules differing on
  every axis a screen reads, a namespace with a documented form and an
  undocumented one, and a form with callers, callees and a recorded ask.

  `:projects` is the odd one out and belongs here anyway: it is the HUB's
  answer rather than a project's, and it arrives outside the navigate loop —
  which is why nothing headless had it until [[page]] gained a `:boot`."
  {;; the server's own answer, not a project's. One project is STOPPED on purpose:
   ;; the switcher's claim is that a project which stops answering stays listed,
   ;; disabled and labelled — disappearing from a control mid-session is how a
   ;; reader concludes they imagined it — and a fixture of three healthy
   ;; projects would assert that the happy path renders and nothing about that.
   :projects [{:slug "slopp2" :dir "/w/slopp2" :opened-at 0 :sessions 2 :cli true
               :app {:url "http://127.0.0.1:7358/api/" :branch "main"}}
              {:slug "demo"   :dir "/w/demo"   :opened-at 0 :sessions 1 :cli false :app nil}]

   ;; the namespace INDEX, which the Dashboard counts. It RANKED these too
   ;; until 2026-09-06, and the sizes still differ ON PURPOSE for the reason
   ;; that outlived the ranking: the store panel SUMS them, and a fixture of
   ;; five equal namespaces would let a wrong sum — a multiply, a count — look
   ;; right. 42+31+18+9+1 is a total no other arithmetic produces.
   :dashboard [{:ns "demo.core"      :forms 42}
               {:ns "demo.core-test" :forms 31}
               {:ns "demo.rate"      :forms 18}
               {:ns "demo.order"     :forms 9}
               {:ns "demo.tiny"      :forms 1}]

   ;; the COST journal as `by=ask` answers it. One row deliberately carries NO
   ;; `:model` block — that is an ask which opened a turn and never reached the
   ;; model, and it is the row that proves the panel drops it instead of
   ;; drawing a zero bar and averaging over it.
   :cost {:by "ask"
          :rows [{:ask "d3457" :intent "add a dashboard section"
                  :at 1788587138441 :requests 4
                  :model {:requests 4 :tokens 249610 :cost-usd 3.41
                          :input 12 :output 4210
                          :cache-read 158990 :cache-creation 86398
                          :context {:p50 121044 :max 140228}}}
                 {:ask "d3401" :intent "a monitor event that opened a turn"
                  :at 1788580000000 :requests 0}
                 {:ask "d3390" :intent "turn the otel exporter on"
                  :at 1788571000000 :requests 1
                  :model {:requests 1 :tokens 49310 :cost-usd 0.62
                          :input 4 :output 961
                          :cache-read 26443 :cache-creation 21902
                          :context {:p50 49173 :max 49173}}}]}

   ;; the CONFIG document, in its own def because it is long and its rows are
   ;; chosen rather than typical — one per branch the view has.
   :config config-document

   ;; THE REVIEW HALF. Absent until 2026-08-08, which meant `screen {visit "/"}`
   ;; threw and the entire section had never been looked at headlessly —
   ;; the first and worst of the fixture gaps. It hid a live bug: `change-main` read
   ;; `:diff` as strings with a leading `-`/`+` long after the endpoint moved to
   ;; PAIRS, so every line rendered colourless and each pair went into the tree
   ;; as an element named `same`.
   ;;
   ;; Shapes MEASURED off slopp2's live wire rather than invented, because a
   ;; fixture invented from a docstring reproduces the docstring's mistakes —
   ;; which is exactly what happened to the test that used to cover this.
   :timeline {:working {:since "d24946" :forms 3
                        :namespaces ["demo.core" "demo.web"]
                        :prompts ["sharpen hello" "rate needs a zone"]}
              ;; the newest carries a `:range` and the OLDEST does not: the
              ;; first commit-point in a store has nothing to diff against, and it
              ;; renders as plain text rather than a link to an empty change.
              ;; A fixture where every commit-point linked would show that the list
              ;; renders and nothing about the branch.
              :commit-points [{:commit "d3457" :status "green" :at "2026-08-08 02:09"
                            :description "The API section navigates like an API browser"
                            :range "d3300..d3457"}
                           ;; RECORDED RED, and the only one. `commit_point {force true}` records a
                           ;; red commit-point honestly, so this is a state a real store reaches —
                           ;; and the VALUE is from the contract's own vocabulary rather than
                           ;; invented: `:status`'s doc says "a red one is expressible and this
                           ;; store has never produced one to check against". A shape nobody
                           ;; emits would be a guess; a documented value nobody has happened to
                           ;; produce is a fixture's job.
                           ;;
                           ;; With every commit-point green the timeline showed that the list
                           ;; renders and nothing about the verdict it carries.
                           {:commit "d3300" :status "red" :at "2026-08-08 01:47"
                            :description "A third section: the API surface a project publishes"
                            :range "d3188..d3300"}
                           {:commit "d1" :status "green" :at "2026-07-28 00:47"
                            :description "the first commit-point, which has nothing behind it"}]}

   ;; `:diff` is [status text] pairs — `same`, `add`, `del` — the shape the
   ;; endpoint sends and the same convention `token-code` already uses. All
   ;; three statuses appear, because a fixture of only `same` lines would show
   ;; that the diff renders and nothing about the classifying that is the whole
   ;; reason it arrives structured.
   :change {:from "d3300" :to "d3457" :count 2
            :arc [{:delta "d3310" :fail 0 :tests 41 :pass 512 :ms 180} {:delta "d3326" :fail 2 :tests 41 :pass 510 :ms 240}
                  {:delta "d3457" :fail 0}]
            :modules [{:module "demo.core" :count 2
                       :namespaces [{:ns "demo.core" :count 2
                                     :forms [{:form "demo.core/rate" :form-id "f1"
                                              :status "modified" :callers 3
                                              :why "the order path was guessing at prices"
                                              :diff [["same" "(defn rate"]
                                                     ["del" "  [kg]"]
                                                     ["add" "  [kg zone]"]
                                                     ["same" "  (band-for kg))"]]}
                                             ;; no `:why` on this one: a form can
                                             ;; be changed without a recorded ask
                                             ;; and the pane must not leave a gap
                                             ;; where the sentence would be
                                             {:form "demo.core/band-for" :form-id "f2"
                                              :status "added" :callers 1
                                              :diff [["add" "(defn- band-for [kg] …)"]]}]}]}]}

   :code   {;; the CONFORMANCE of every edge below: four kinds, so every mark the
            ;; lens draws renders on a driven page. The billing → orders half of
            ;; the knot is used but never declared; web → orders is declared
            ;; and never used; billing → util is declared and used only by tests.
            :conformance {:edges [{:from "demo.billing" :to "demo.orders" :class "divergent"}
                                  {:from "demo.billing" :to "demo.util" :class "test-only"}
                                  {:from "demo.core" :to "demo.util" :class "convergent"}
                                  {:from "demo.orders" :to "demo.billing" :class "convergent"}
                                  {:from "demo.orders" :to "demo.util" :class "convergent"}
                                  {:from "demo.web" :to "demo.core" :class "convergent"}
                                  {:from "demo.web" :to "demo.orders" :class "absent"}
                                  {:from "demo.web" :to "demo.util" :class "convergent"}]}
            :modules [{:module "demo.web" :namespaces ["demo.web"] :tests 2
                       :tier "external" :foundation false
                       ;; TWO deps, so the table's ", " separator is driven. A
                       ;; separator supplied by CSS is nothing at all to a
                       ;; reader without it, and every other row here carries
                       ;; one dep, which shows the cell renders and not what it
                       ;; SAYS.
                       :deps ["demo.core" "demo.util"]
                       :gaps {:forms 12 :no-doc 9 :no-why 11 :uncovered 12}}
                      {:module "demo.core" :namespaces ["demo.core" "demo.core.calc"]
                       :tests 5 :tier "pure" :foundation false :deps ["demo.util"]
                       :gaps {:forms 20 :no-doc 0 :no-why 1 :uncovered 3}}
                      ;; THREE modules, because `:foundation` is an axis two
                      ;; things on this screen read — the census clause and the
                      ;; table's band marker — and a fixture where nobody is in
                      ;; the band drives neither. The dep above points INTO the
                      ;; band on purpose: `graph/diagram` drops an edge into the
                      ;; foundation and `module-table` names it, so the one
                      ;; place the two views of this screen legitimately
                      ;; disagree is a thing you can look at.
                      {:module "demo.util" :namespaces ["demo.util"] :tests 3
                       :tier "pure" :foundation true :deps []
                       :gaps {:forms 6 :no-doc 1 :no-why 2 :uncovered 0}}
                      ;; MUTUALLY ENTANGLED, and the shape is the real producer's —
                      ;; `slopp.store/module-layers` run on a manifest with a
                      ;; cycle in it, sent over by slopp rather than described.
                      ;; A cycle does not break `:layers` or make it absent; it
                      ;; CONDENSES into one entry with every member at the same
                      ;; depth, and `:cycles` rides alongside as the finding.
                      ;;
                      ;; **Not a corner case, and this comment said it was.** A
                      ;; module is the first TWO segments, so `pa.core.impl`
                      ;; calling `pb.app` while `pb.app` calls `pa.core` is a
                      ;; module cycle with NO namespace cycle in it — nothing
                      ;; loads in a circle and Clojure is perfectly happy. That
                      ;; is an ordinary mature codebase: a utility whose
                      ;; implementation reaches back into the app using it.
                      ;;
                      ;; It cannot be grown here — `module_dep` refuses an edge
                      ;; that closes a cycle — so it arrives by ADOPTION, and
                      ;; what adoption imports is normal code. This fixture is
                      ;; modelling the COMMON case for an imported store, which
                      ;; is the opposite of what I first wrote.
                      {:module "demo.orders" :namespaces ["demo.orders"] :tests 4
                       :tier "internal" :foundation false
                       :deps ["demo.billing" "demo.util"]
                       :gaps {:forms 14 :no-doc 5 :no-why 8 :uncovered 6}}
                      {:module "demo.billing" :namespaces ["demo.billing"] :tests 1
                       :tier "internal" :foundation false :deps ["demo.orders"]
                       :gaps {:forms 9 :no-doc 4 :no-why 7 :uncovered 9}}]
            ;; Layer 0 holds the condensed cycle AND `demo.core`, which has no part in
            ;; it: layer 0 means "depends on nothing below" and a knot qualifies,
            ;; so an innocent module shares the entry. That is why `:cycles`
            ;; rides alongside rather than marking the layer row — the layer
            ;; cannot discriminate, and a flag on it would be a second place for
            ;; the same fact.
            :layers [["demo.billing" "demo.core" "demo.orders"] ["demo.web"]] :cycles [["demo.billing" "demo.orders"]]}

   :module {:module "demo.core" :tier "pure"
            :tests [{:ns "demo.core-test" :count 3
                     :names ["rate-needs-a-zone" "rate-rounds-a-boundary-weight-up"]
                     :more 1}]
            :namespaces [{:ns "demo.core" :forms 11 :tier "pure"
                          :deps ["demo.core.calc"]
                          :gaps {:forms 11 :no-doc 0 :no-why 1 :uncovered 2}}
                         ;; A KNOT, and the third time this entry has been
                         ;; written. It is worth the space because the two
                         ;; wrong versions were wrong in opposite directions.
                         ;;
                         ;; First it was INVENTED — I had module-grain shapes
                         ;; and generalised them one rung down without noticing
                         ;; the grain changed. Then it was backed out with a
                         ;; FALSE justification: that a namespace cycle cannot
                         ;; be written, since Clojure refuses mutual requires.
                         ;; It does refuse them, and that is not the only way to
                         ;; use a var — `declare`, then a top-level `require`
                         ;; after it, and the far side calls the fully-qualified
                         ;; name. The classic hand cycle-break, measured loading
                         ;; in a plain JVM.
                         ;;
                         ;; Now it is COPIED. `slopp.api.model/module-detail`'s
                         ;; own return for a store built that way: `:layers`
                         ;; condenses the knot into ONE entry exactly as it does
                         ;; at module grain, and `:cycles` names the same pair.
                         ;; The third namespace above it sitting in its own
                         ;; layer is the producer's statement about this case,
                         ;; not my arithmetic on theirs.
                         ;;
                         ;; **The two `:cycles` fields are ORTHOGONAL, not one
                         ;; finding at two grains.** Both ends of this knot are
                         ;; inside one module, so no cross-module edge exists
                         ;; and `/api/modules` reports a clean store — measured.
                         ;; That is the REASON, deliberately left where the
                         ;; conclusion used to be. This said "the only surface on
                         ;; which this class is visible at all" — true when
                         ;; written, verified on the other side the same day, and
                         ;; a claim about which OTHER surfaces exist, which is
                         ;; the one kind of sentence nothing here could ever
                         ;; notice going stale. The measurement above outlives
                         ;; it: a reader who has the reason can check the
                         ;; conclusion themselves, on the day they need it.
                         ;;
                         ;; `:boundary` here stays POPULATED, which is the one
                         ;; way this differs from the store it was copied from.
                         ;; That store had `{:out [] :in []}` — a knot with no
                         ;; boundary at all — because every edge in a knot is
                         ;; internal by definition. Ours keeps its cross-module
                         ;; edges because [[responses]] must also drive the
                         ;; `depends on` branch that hid a reversed arrow. The
                         ;; combination this fixture therefore CANNOT reach —
                         ;; a cycle beside an empty boundary — is pinned in
                         ;; `views-test` on that store's verbatim output.
                         {:ns "demo.core.calc" :forms 9 :tier "pure"
                          :deps ["demo.core.tariff"]
                          :gaps {:forms 9 :no-doc 0 :no-why 0 :uncovered 1}}
                         {:ns "demo.core.tariff" :forms 5 :tier "pure"
                          :deps ["demo.core.calc"]
                          :gaps {:forms 5 :no-doc 2 :no-why 3 :uncovered 4}}]
            ;; BOTH halves populated. `:out` was empty, so the module page's
            ;; `depends on` heading rendered only its "nothing outside itself"
            ;; branch — a covered screen with an unreached path, which is the
            ;; shape the second fixture-gap finding became a rule about.
            ;;
            ;; The row shape is DECLARED — `[:map [:from :string] [:to :string]
            ;; [:to-module :string]]` in `/api/module/:m`'s contract — so this
            ;; is copied rather than invented, which is the difference between
            ;; a fixture and a guess about a producer.
            :boundary {:in  [{:from "demo.web" :from-module "demo.web"
                              :to "demo.core"}]
                       :out [{:from "demo.core" :to "demo.util"
                              :to-module "demo.util"}]}
            :layers [["demo.core.calc" "demo.core.tariff"] ["demo.core"]]
            :cycles [["demo.core.calc" "demo.core.tariff"]]}

   :ns     {:ns "demo.core" :tier "pure"
            :gaps {:forms 11 :no-doc 0 :no-why 1 :uncovered 2}
            :tested-by ["demo.core-test"]
            ;; NAMES, which is what the rail renders as sentences. Three reach
            ;; it and two are shown so the "and 1 more" branch is driven too.
            :tests [{:ns "demo.core-test" :count 3
                     :names ["rate-needs-a-zone" "rate-rounds-a-boundary-weight-up"]
                     :more 1}]
            ;; **Every key the contract REQUIRES is present, including the ones that
            ;; are null on the wire.** This vector omitted `:sig`, `:schema`,
            ;; `:private?`, `:exported?`, `:effectful?` and `:callers-out-test`
            ;; until `client.checks-test` compared it against the published
            ;; `ns-outline-response` — the first time anything had. Every
            ;; ns-screen test was running against rows thinner than the endpoint
            ;; sends, so a screen reading one of those keys exercised its
            ;; absent branch and never its present one.
            ;;
            ;; Values measured off the live wire, not invented: an `ns` row
            ;; really does carry `sig` and `schema` as null.
            :forms [{:name "demo.core" :kind "ns" :form-id "f0" :mass 4
                     :doc "Pricing, and the rules that decide it."
                     :sig nil :schema nil :private? false
                     :exported? false :effectful? false
                     :calls [] :callers-out 0 :callers-out-test 0}
                    ;; carries BOTH trail badges, because no form here carried ANY — the private
                    ;; one is filtered out by default, and nothing was exported or
                    ;; effectful. So the badge path rendered on no driven screen, and
                    ;; three missing separators sat in it until a misread key sent me
                    ;; looking. A row that renders and a row that READS are different
                    ;; questions; only the second one needs a fixture that reaches it.
                    {:name "rate" :kind "defn" :form-id "f1" :sig ["[kg zone]"]
                     :doc "Rate for a weight in a zone. Bands come from the tariff table, and a weight on a boundary rounds up."
                     :schema nil :private? false
                     :mass 40 :calls ["band-for"] :callers-out 3
                     :callers-out-test 2
                     :exported? true :effectful? true}
                    {:name "band-for" :kind "defn-" :form-id "f2" :sig ["[kg]"]
                     ;; nil rather than absent: the contract requires the key,
                     ;; and a private helper with no docstring is precisely the
                     ;; row this app renders its "no comment" marker for — so
                     ;; the fixture must be able to produce that state.
                     :doc nil :schema nil
                     :exported? false :effectful? false
                     :mass 12 :private? true :calls [] :callers-out 0
                     :callers-out-test 0}]}

   :form   {:form-id "f1" :name "rate" :form "demo.core/rate"
            :ns "demo.core" :module "demo.core"
            ;; the FIDELITY this form is shown at, and the ones on offer. Both
            ;; required by the published contract and both absent here until
            ;; `client.checks-test` compared the two — measured off the live
            ;; wire rather than guessed.
            :view "clojure" :views ["clojure"]
            ;; STRINGS, all three of them — the top-level one and both
            ;; neighbour cards. This map carried vectors until slopp read the
            ;; producer out on 2026-08-15: `read.orient/form-card` unwraps a
            ;; single arity and `api.model/json-card` `pr-str`s it, so one
            ;; arity sends `"[kg zone]"`, several send `"[[x] [x y]]"` — both
            ;; arities inside ONE string — and a form with no arglist omits the
            ;; key. `neighbour-card`'s `:sig` is declared `:string` too.
            ;;
            ;; **The invention was not arbitrary, which is why it survived.**
            ;; `:sig` really IS `[:sequential :string]` — on `form-row` and on
            ;; `search-results`, and the `:ns` and `:search` fixtures above and
            ;; below still carry it that way, correctly. One key, two shapes,
            ;; one grain apart. I copied a real shape from the wrong rung.
            ;;
            ;; Measured before changing anything: `form-main` renders all four
            ;; inputs correctly — string, multi-arity string, the old vector,
            ;; and absent — because it asks `coll?` first. So the fixture was
            ;; wrong and the SCREEN was not, which is the opposite of what a
            ;; wrong fixture usually means here.
            :sig "[kg zone]" :doc "Rate for a weight in a zone."
            :why "the order path was guessing at prices"
            :warranty {:covered 3}
            :keys [{:kw "order/total" :via "destructuring"}]
            :tests {:count 3
                    :shown [{:test "demo.core-test/rate-rounds-a-boundary-weight-up"
                             :via ["observed" "static"] :hops 1}]}
            :tokens [["text" "(defn rate "] ["delim" "["] ["text" "kg zone])"]]
            :callers [{:via "static" :count 1
                       :forms [{:form "quote" :ns "demo.order" :module "demo.order"
                                :form-id "f9" :calls 1 :sig "[order]"
                                :doc "Price one order." :warranty {:covered 4}}]}]
            ;; TWO callees, and the second one is the whole reason the `through`
            ;; route is testable. With one callee `spine`'s `prefer` set is
            ;; inert — it REORDERS the open options and there is nothing to
            ;; reorder — so `/store/form/f1/through/f2` rendered byte-identically
            ;; to `/store/form/f1` and an entire route arity was unverifiable.
            ;;
            ;; It reaches a second branch for free: every rung reported
            ;; `1 of 1`, so the alternatives count and the swap it implies had
            ;; never rendered on any driven screen either. One row, two
            ;; branches, and neither was reachable by adding an assertion.
            ;;
            ;; Copied rather than invented: `:callees` is
            ;; `[:sequential [:map …]]` and a form with two callees is the
            ;; ordinary case. The keys here are the ones the contract declares
            ;; for a neighbour card, `:sig` included — a STRING at this grain.
            :callees [{:form "band-for" :ns "demo.core" :module "demo.core"
                       :form-id "f2" :via "static" :calls 2 :sig "[kg]"
                       :warranty {:covered 1}}
                      {:form "round-up" :ns "demo.core" :module "demo.core"
                       :form-id "f3" :via "static" :calls 1 :sig "[x]"
                       :doc "Round a rate up to the nearest cent."
                       :warranty {:covered 2}}]
            :note "edges are a syntactic floor, not a census"}
   ;; TWO commit points and work in flight, so every branch of the story
   ;; screen is driven: a row with a range and one without (the first commit
   ;; point has nothing before it), asks past the cap, a red verdict, and the
   ;; in-flight section. Shape copied from `slopp.read.history/story-rows`.
   :story  {:grain "ns" :subject "demo.core" :page 0 :more 0
            :rows [{:commit "d3457" :description "The API section navigates like an API browser"
                    :status "green" :at "2026-08-08 02:09" :range "d3300..d3457"
                    :asks ["rate needs a zone" "band-for rounds up on a boundary"]
                    :more-asks 0 :forms 2}
                   {:commit "d3300" :description "A third section: the API surface a project publishes"
                    :status "red" :at "2026-08-08 01:47"
                    :asks ["the order path was guessing at prices"] :more-asks 3 :forms 1}]
            :working {:asks ["sharpen rate"] :more-asks 0 :forms 1 :since "d3457"}}
   ;; the DOORS: a door with a form and one without, so the panel's linked and
   ;; unlinked rows are both driven. The handler forms are the demo's own.
   :entries {:kinds [{:kind "main" :note "the process entry — what runs when the app starts"
                      :entries [{:kind "main" :label "app.main" :handler "demo.web/-main"
                                 :module "demo.web" :form-id "f20"}]}
                     {:kind "http" :note "HTTP routes — each request enters the code here"
                      :entries [{:kind "http" :label "GET /quote" :handler "demo.web/quote-handler"
                                 :module "demo.web" :form-id "f21"}
                                {:kind "http" :label "POST /orders" :handler "demo.web/order-handler"
                                 :module "demo.web"}]}]
             :unreadable []}
   ;; ONE of every step kind — a call, a cross-module call, a callee drawn
   ;; already, a depth cut, a cycle and the calls a fan-out cap held back — so
   ;; every branch of the sequence screen renders on a driven page.
   :sequence {:root {:form "demo.core/rate" :form-id "f1" :module "demo.core"}
              :lifelines ["demo.core" "demo.util"]
              :steps [{:i 0 :depth 1 :from "demo.core/rate" :from-module "demo.core"
                       :to "demo.core/band-for" :to-module "demo.core" :to-form-id "f2" :via "static"}
                      {:i 1 :depth 2 :from "demo.core/band-for" :from-module "demo.core"
                       :to "demo.util/round-up" :to-module "demo.util" :to-form-id "f3" :via "static"}
                      {:i 2 :depth 1 :from "demo.core/rate" :from-module "demo.core"
                       :to "demo.util/round-up" :to-module "demo.util" :to-form-id "f3" :via "static"
                       :seen-at 1}
                      {:i 3 :depth 1 :from "demo.core/rate" :from-module "demo.core"
                       :to "demo.core.calc/tariff" :to-module "demo.core" :via "carrier" :deeper? true}
                      {:i 4 :depth 1 :from "demo.core/rate" :from-module "demo.core"
                       :to "demo.core/rate" :to-module "demo.core" :via "static" :cycle? true}
                      {:i 5 :depth 1 :from "demo.core/rate" :from-module "demo.core" :more 2}]
              :truncated {:depth true :steps false}
              :note "the calls in the order they are WRITTEN, depth first — a static reading of the code, not a recording of a run"}
   ;; the EFFECTS dial, a share: one module untinted, the others on three
   ;; different rungs, so the ramp is visible on a driven page
   ;; the DICTIONARY with a key chosen, so the key panel's both kinds of use
   ;; and its test tally render on a driven page
   :data {:keys [{:kw "order/total" :modules 3 :namespaces 4 :forms 7 :destructured 2}
                 {:kw "order/id" :modules 2 :namespaces 2 :forms 3 :destructured 0}]
          :total 2 :shown 2
          :key {:kw "order/total"
                :modules [{:module "demo.core"
                           :forms [{:form "demo.core/rate" :form-id "f1" :via ["destructuring"]}]}
                          {:module "demo.web"
                           :forms [{:form "demo.web/quote-handler" :form-id "f21" :via ["literal"]}]}]
                :tests 1}}
   :overlay {:dial "effects" :kind "share" :label "effects"
             :note "the share of forms that perform effects — the imperative shell reads dark and a pure core reads pale"
             :namespaces [{:ns "demo.billing" :module "demo.billing" :value 2 :of 9}
                          {:ns "demo.core" :module "demo.core" :value 0 :of 11}
                          {:ns "demo.core.calc" :module "demo.core" :value 1 :of 9}
                          {:ns "demo.orders" :module "demo.orders" :value 5 :of 14}
                          {:ns "demo.util" :module "demo.util" :value 0 :of 6}
                          {:ns "demo.web" :module "demo.web" :value 10 :of 12}]
             :modules [{:module "demo.billing" :value 2 :of 9}
                       {:module "demo.core" :value 1 :of 20}
                       {:module "demo.orders" :value 5 :of 14}
                       {:module "demo.util" :value 0 :of 6}
                       {:module "demo.web" :value 10 :of 12}]}
   :flow {:root {:form "demo.web/quote-handler" :form-id "f21" :module "demo.web"}
          :lifelines ["demo.web" "demo.core" "demo.util"]
          :steps [{:i 0 :depth 1 :from "demo.web/quote-handler" :from-module "demo.web"
                   :to "demo.core/rate" :to-module "demo.core" :to-form-id "f1" :via "static"}
                  {:i 1 :depth 2 :from "demo.core/rate" :from-module "demo.core"
                   :to "demo.util/round-up" :to-module "demo.util" :to-form-id "f3" :via "static"}]
          :truncated {:depth false :steps false}
          :note "the shortest call path between the two, each hop as the code writes it — static, like every edge here"}
;; All three kinds and all four `:matched` values, because a fixture where
   ;; every hit matched its own name would show that the list renders and
   ;; nothing about what it SAYS. The source-only row is the one worth having
   ;; a fixture for at all: it is what turns a result list into one of this
   ;; UI's honesty surfaces rather than a ranking.
   ;; ONE document, both API screens. See `contract-document` for why it is a
   ;; def and what each of its four endpoints is there to exercise.
   ;; the THIRD screen found with no canned response, and the first found by a
   ;; LINK pointing at it rather than by an exception — the API page's new
   ;; drill-down goes here. Undriven it rendered `<h1></h1>` over an empty
   ;; `<pre>`, with `<a href="/store/ns/"></a>` beside it: a breadcrumb to
   ;; nowhere with no text in it.
   ;;
   ;; `:timeline`, `:change` and now `:source` were each found separately, by
   ;; accident, over three weeks. That is a population problem rather than
   ;; three oversights, and it was recorded as one.
   ;; `:form-id` landed on this endpoint 2026-08-09 — a STORE identity, so it
   ;; arrives here rather than in the published contract document, which has to
   ;; work for a producer running from a jar with no store. It is `f1` on
   ;; purpose: the `:form` fixture below is f1, so following the sideways link
   ;; lands on a page with real data rather than on a nil.
   :source {:ns "demo.core" :name "rate" :form-id "f1"
            :source "(defn rate\n  \"Rate for a weight in a zone.\"\n  [kg zone]\n  (* kg (band-for zone)))"}

   ;; ONE document, both API screens. See `contract-document` for why it is a
   ;; def and what each of its four endpoints is there to exercise.
   :rest-paths
   contract-document
   :rest-path  contract-document

   ;; ONE content document, both Pages screens — the same arrangement, for the
   ;; same reason. See `content-document`.
   :http-paths content-document
   :http-path  content-document

   ;; ONE webapp document, both Webapp screens — the third of the three, on the
   ;; same terms. See `webapp-document`.
   :webapp-pages webapp-document
   :webapp-page  webapp-document

   :search {:query "rate" :total 37
            :totals {:modules 1 :namespaces 1 :forms 35}
            ;; Ranks are slopp's PUBLISHED ladder — 1.0 exact name, 0.9 prefix,
            ;; 0.8 substring, 0.5 docstring, 0.4 recorded why, 0.2 source — and
            ;; not numbers invented here. A fixture rank off the ladder puts a
            ;; value on screen the endpoint can never produce, which is the
            ;; whole failure mode a fixture is supposed to catch.
            ;;
            ;; `:sig` appears on the defn rows only. slopp shipped it on a
            ;; `def` for about ten minutes — a registry's VALUE rendered as a
            ;; parameter list — and caught it on their live listener rather
            ;; than in a test, so this fixture pins the corrected rule.
            :hits [{:kind "form" :name "demo.core/rate"
                    :ns "demo.core" :module "demo.core" :form-id "f1"
                    :sig ["[kg zone]"]
                    :doc "Rate for a weight in a zone. Bands come from the tariff table, and a weight on a boundary rounds up."
                    :why "the order path was guessing at prices"
                    :matched "name" :rank 1.0}
                   {:kind "namespace" :name "demo.core.calc" :module "demo.core"
                    :doc "Rate arithmetic: bands, boundaries and rounding."
                    :matched "doc" :rank 0.5}
                   {:kind "module" :name "demo.core"
                    :why "pricing needed somewhere to live that the web layer could not reach into"
                    :matched "why" :rank 0.4}
                   {:kind "form" :name "demo.web/coerce-zone"
                    :ns "demo.web" :module "demo.web" :form-id "f7"
                    :sig ["[s]"]
                    :matched "source" :rank 0.2}]}})

(defn ^:app/entry page
  "This application DECLARED — the entry both `screen` and the browser open.

  Zero arguments and `:cljc` because a JVM has to call it, which is the whole
  gate: an entry in a `:cljs` namespace, or one taking arguments, refuses at the
  write rather than failing when someone reaches for the tool.

  **It returns the DECLARATION, not a driver, and that is the wave's point.**
  One value; each consumer derives what it needs — `slopp.cljnx/driver-for`
  for a headless drive, `dom/mount!` in a browser. A PRE-WIRED map is refused
  by both, because it already carries the derived `:webapp/view`, so handing
  back `(slopp.webapp/wiring …)` fails exactly as loudly as handing back a driver.

  This used to call `slopp.webapp/driver` over `slopp.webapp/wiring` itself, and the two
  entries were then the same declaration entered twice with nothing comparing
  them. `driver-for` is public precisely so the tool and this project's tests
  share ONE derivation — two would drift, and each would pass against its own
  reconstruction.

  **The canned data is the point of this fn rather than a limitation.** A screen
  worth looking at needs a shape, not a live project — and it uses `wiring`, the
  same map `client.app` hands the browser, so it cannot drift into a lookalike.
  Only the plug-ins that must differ are swapped, and `dom/mount!` merges the
  browser's own over these, so they cost a little fixture data in the bundle
  and nothing else.

  **The canned answers are keyed by request PATH** — see [[answers]] — because a
  screen carries its own `:request`, so what a fixture answers is a fact about a
  URL rather than about a screen.

  It also has to be, because there is one plug-in where there were two:
  `:webapp/call` performs screen loads, SESSION loads and the ad-hoc call. The
  `sent:` line the API panel renders comes from `slopp.webapp/perform!`, which
  records the request it was handed BEFORE performing it — so what the form
  would have sent is visible whatever this answers.

  **The atom starts EMPTY and there is no `:boot`**, which is the session-load
  wave's doing. Both were here: `:modules` seeded into the atom, and
  `:projects` assoc'd by a `:boot` written because *\"a driver runs routes and
  handlers but no entry point, so the project switcher was on no headless
  screen at all\"*. Both are declared `:webapp/session-loads` now, and the
  derived driver's `:boot` performs them through this very performer — so the
  fixture answers them by path like everything else, and the two hand-seeded
  copies are gone.

  Two modules that differ on every axis a screen reads — one thin, one
  documented — because a screen where every row is identical shows that it
  renders and not that it renders the right thing.

  Alone in this namespace because it mints an atom, which is `:internal`."
  []
  (app/wiring
   {:state  (atom {})
    ;; BY PATH. One response for every screen rendered the module page with
    ;; an empty heading over someone else's diagram; a path nobody canned
    ;; answers nil, which the app already renders honestly.
    ;;
    ;; The path arrives ADDRESSED — `/api/projects/<slug>/modules` — because
    ;; `webapp/fetch!` applies the request's base before handing it to this
    ;; performer. The project segment is the SERVER's mount of a project, not
    ;; the project's own endpoint, and [[answers]] is keyed by the latter.
    ;; Stripping it here rather than keying on the whole thing keeps the
    ;; fixture keyed by the endpoint's own path, which is what makes it a fact
    ;; about a URL rather than about which project a screen happened to be
    ;; looking at.
    :call   (fn [request ok _err]
              ;; **The server's OWN routing, not a normalisation.** This used to
              ;; be a strip-the-tenant-if-present regex — look up the rest —
              ;; which made an ADDRESSED request and an UNADDRESSED one
              ;; indistinguishable by construction. The `:modules` session load
              ;; asked `/api/modules` at the origin for weeks; the server
              ;; answers 404 there, the Code nav was empty in every browser,
              ;; and every drive test passed because this line answered it
              ;; anyway.
              ;;
              ;; A fixture that NORMALISES an input cannot test what produced
              ;; it. Keying answers by the endpoint's own path is still right —
              ;; it makes an answer a fact about a URL rather than about which
              ;; project a screen happened to be looking at — so the fix is to
              ;; keep the key and VALIDATE the prefix rather than discard it.
              ;;
              ;; Checked against what the SERVER serves, deliberately not
              ;; against the request's own `:webapp/base`: a wrong base would
              ;; then validate itself, which is the same defect one level in.
              (let [p    ;; `:http/url`, and the QUERY comes off. A request used to carry
                         ;; `:webapp/path` — the pattern — with its captures and query
                         ;; beside it; `slopp.http.endpoint/request` resolves the whole
                         ;; address into ONE finished url, so what arrives here is
                         ;; `/api/projects/demo/search?q=&limit=50`. Keyed on that whole
                         ;; string, every parameterised endpoint misses its answer and
                         ;; renders as an endpoint that returned nothing.
                         (-> (str (:http/url request))
                             (str/replace #"\?.*$" ""))
                    ;; the only endpoint this app asks the server for at the
                    ;; ORIGIN: its registry. Everything else is a project's and
                    ;; is reached through that project's mount.
                    server? (= "/api/projects" p)
                    own  (some->> (re-matches #"^/api/projects/[^/]+(/.*)$" p) second (str "/api"))]
                ;; **Matched as PATTERNS, through the app's own router.** [[answers]] is
                ;; keyed `/api/form/:id`, which is what a reader of the fixture needs
                ;; to see; the url arriving here is concrete, because
                ;; `slopp.http.endpoint/request` resolves the whole address now. A
                ;; map lookup therefore misses every parameterised endpoint — and
                ;; misses it SILENTLY, answering nil, which each screen renders as
                ;; its own honest empty state: `nothing to show — this range came
                ;; back empty` for a fixture that has the range.
                ;;
                ;; `match-route` rather than a second matcher written here: the
                ;; fixture's keys are paths with captures, which is exactly what
                ;; that function already reads, and a private lookalike would be
                ;; free to disagree with the router about what `:id` matches.
                (ok (cond server? (get responses (answer-for p))
                          own  (get responses (answer-for own))
                          ;; a project endpoint asked at the origin. The real
                          ;; server 404s it; answering nil here is what lets a
                          ;; screen test SEE that, which is the whole point.
                          :else nil))))
    :render (fn [_])}))
