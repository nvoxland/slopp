(ns slopp.api.endpoints
  "The reviewer UI's JSON boundary — one function per endpoint.

  This is what D-webapp is organised around: an explicit, typed, independently
  testable surface. Each endpoint declares its route, its auth, the reads it
  needs and a `:rest/response` contract on the name, so it is a pure function
  of data — its test is `=` with no mock, no browser and no running server,
  and the same schema var validates the response here and in the generated
  client.

  Two things are deliberately elsewhere. The reads are PERFORMED in
  `slopp.api.reads`, addressed by vocabulary rather than by var — which is
  why `slopp.api.server/served-namespaces` names both namespaces and why
  serving only this one yields 500s. And the payloads are SHAPED in
  `slopp.api.model`; handlers here restate them key by key because that is
  where symbols become strings, JSON having no symbol type.

  This is now the WHOLE of what a slopp project serves. The reviewer UI moved
  to its own project and consumes these endpoints over HTTP like any other
  client, so an explicit typed independently testable surface stopped being
  an organising principle and became the only thing there is."
  (:require [slopp.api.contracts :as contracts]))

(defn ^{:http/method :get :rest/path "/api/namespaces" :http/auth :public
        :rest/response contracts/namespace-list
        :http/reads {:namespaces [:browse/namespaces []]}}
  namespaces
  "GET /api/namespaces — every namespace with its form count, sorted.

  Goes through the `:browse/namespaces` read rather than reading the store
  here. Reads are addressed by VOCABULARY rather than by var, so a performer
  is shared store-wide and any endpoint answering the same question answers it
  the same way — which mattered more when an HTML page in this store declared
  the same read, and is still what keeps the read reusable now that the pages
  belong to the hub (D-hub part 4).

  `:ns` is stringified HERE because the wire is JSON and JSON has no symbols.
  Doing it at the boundary rather than in the read leaves the read's own value
  a symbol, which is what an in-image caller wants."
  [req]
  {:status 200
   :body (mapv (fn [{:keys [ns forms]}] {:ns (str ns) :forms forms})
               (:namespaces (:http/reads req)))})

(defn ^{:http/method :get :rest/path "/api/ns/:ns" :http/auth :public
        :rest/request contracts/ns-outline-request
        :rest/response contracts/ns-outline
        :http/reads {:outline [:browse/ns-outline [:path-params :ns]]}}
  ns-outline
  "GET /api/ns/:ns — one namespace's forms in store order, and what tests it.

  An unknown namespace is a 404 rather than an empty outline: `{:forms []}`
  would say the namespace exists and holds nothing, which is a different
  statement and a false one. The read already returns nil for the unknown
  case, so the distinction costs a `when-let`.

  The body is restated key by key rather than passed straight through — this
  is where symbols become strings, and JSON has no symbol type. The cost is
  that a new key on the read must be named here too; the contract check is
  what makes that a red test rather than a silently missing field."
  [req]
  (if-let [{:keys [ns tier forms tested-by gaps]} (:outline (:http/reads req))]
    {:status 200
     :body {:ns (str ns)
            :tier tier
            :forms (mapv (fn [{:keys [name form-id kind sig private? doc schema mass calls
                                      callers-out callers-out-test
                                      effectful? exported?]}]
                           {:name (str name) :form-id form-id
                            :kind kind :sig sig
                            :private? private? :doc doc :schema schema
                            :mass mass :calls calls
                            :callers-out callers-out
                            :callers-out-test callers-out-test
                            :effectful? effectful? :exported? exported?})
                         forms)
            :tested-by (vec tested-by)
            :gaps gaps}}
    {:status 404 :body {:error "no such namespace"}}))

(defn ^{:http/method :get :rest/path "/api/timeline" :http/auth :public
        :rest/response contracts/timeline
        :http/reads {:timeline [:ui/timeline []]}}
  timeline
  "GET /api/timeline — commit-points newest first, plus the working set.

  A projection, not new logic: `slopp.api.model/timeline` already returns a
  JSON-shaped value, which is why the SPA rewrite is mostly moving rendering
  rather than inventing data."
  [req]
  {:status 200 :body (:timeline (:http/reads req))})

(defn ^{:http/method :get :rest/path "/api/change/:range" :http/auth :public
        :rest/request contracts/change-request
        :rest/response contracts/change-view
        :http/reads {:change [:ui/change [:path-params :range]]}}
  change
  "GET /api/change/:range — one commit-point reviewed, `from..to`.

  A range arrives from a URL, so both ends are user input. The read already
  separates \"nothing changed here\" from \"that is not a range\", and only the
  second is a 404."
  [req]
  (if-let [c (:change (:http/reads req))]
    {:status 200 :body c}
    {:status 404 :body {:error "no such change range"}}))

(defn ^{:http/method :get :rest/path "/api/form/:id" :http/auth :public
        :rest/request contracts/form-request
        :rest/response contracts/form-view
        :http/reads {:view [:ui/form []]}}
  form
  "GET /api/form/:id — one form's permalink model, at the requested rendering
  FIDELITY (`?view=`) and call-graph DEPTH (`?depth=`).

  Declared over the WHOLE request rather than one segment, because it is
  addressed by both halves of the URL. An unknown id and an unknown fidelity
  are the same answer — 404 — and neither is a reason to quietly render the
  other thing.

  An UNREADABLE depth is a 400, not a floor. This once promised the floor —
  \"1 is what every link written before the parameter existed meant\" — and that
  argument was made when nothing enforced the contract. `:depth` is declared
  `:int`, so `?depth=banana` is refused at the boundary before this handler can
  be kind about it, and the two could not both be true. What the compatibility
  promise actually protects is an ABSENT depth, which still means the default.

  `:rest/request` is declared even though this is a GET with no body. It is
  what a caller SENDS, and how that travels follows from the method — without
  it the generated client takes a params map that only the path reads from, so
  `?depth=` answers on the wire and is unreachable through the typed client,
  which pushes a consumer toward the hand-rolled fetch `direct-http` refuses."
  [req]
  (if-let [v (:view (:http/reads req))]
    {:status 200 :body v}
    {:status 404 :body {:error "no such form"}}))

(defn ^{:http/method :get :rest/path "/api/source/:ns/:name" :http/auth :public
        :rest/request contracts/source-request
        :rest/response contracts/form-source
        :http/reads {:source [:browse/form-source [:path-params]]}}
  source
  "GET /api/source/:ns/:name — one form's source text.

  The text arrives as a STRING and is escaped by the client when it renders.
  Serving arbitrary store source safely is the standing security dogfood
  here, and moving the render to the browser does not retire it — it moves
  it to the one place that must never build markup by concatenation."
  [req]
  (let [{:keys [ns name]} (:path-params req)]
    (if-let [{:keys [form-id source]} (:source (:http/reads req))]
      {:status 200 :body {:ns (str ns) :name (str name)
                          :form-id form-id :source source}}
      {:status 404 :body {:error "no such form"}})))

(defn ^{:http/method :get :rest/path "/api/modules" :http/auth :public
        :rest/response contracts/module-index
        :http/reads {:modules [:browse/modules []]}}
  modules
  "GET /api/modules — the architecture: one row per module, the layering, and
  the cycles.

  A projection, not new logic: `slopp.api.model/module-index` already returns
  JSON-shaped data, so there is nothing to reshape here. That is the payoff
  of shaping once in the model — symbols become strings exactly one place,
  and this endpoint cannot disagree with the model about what a module is.

  No canvas. It used to send one, and the reviewer UI becoming a separate
  project showed the cost: a consumer that receives coordinates cannot draw
  anything else. What crosses now is what only the store can work out —
  the layering, and each module's dependencies — and placement belongs to
  whoever is rendering."
  [req]
  {:status 200 :body (:modules (:http/reads req))})

(defn ^{:http/method :get :rest/path "/api/rest/paths" :http/auth :public
        :rest/media-type "application/edn"
        :rest/response contracts/rest-paths-document
        :rest/unconstrained-ok
        "three fields of this document are :any and cannot honestly be
         narrower. :request and :response carry malli SCHEMAS as values — a
         schema is a keyword, a vector, a symbol or a map, so no tighter shape
         is true of all of them. :auth is an app's own :http/auth declaration
         verbatim, whose grammar is open by design.

         PERMANENT, not pending. The tighter check a reader would reach for is
         a predicate — is this value a schema malli can build? — and a
         predicate cannot be PUBLISHED: this document is data a consumer reads
         and generates from, so a [:fn …] in it would arrive as something they
         cannot evaluate or trust. The constraint is the publishing, and
         publishing is the point."
        :http/reads {:doc [:ui/rest-paths []]}}
  rest-paths
  "GET /api/rest/paths — the typed API this project serves, as EDN.

  What makes a reviewer UI in a DIFFERENT store possible: it generates its
  typed client from this document instead of sharing the producer's contracts
  namespace.

  **Replaced `/api/contracts`, which is retired.** Same rows, same keys; two
  names differed — `:slopp/contract-version 2` became
  `:slopp/rest-paths-version 1` and `:endpoints` became `:paths`. A new
  document at a new address starts its own count rather than inheriting one.

  The two overlapped for exactly one release so the only consumer could
  repoint AND regenerate on a jar where both resolved, then report green
  before the old one went. That is what an overlap is for, and it earned its
  keep: it caught `generate_client` — a consumer of the document that names no
  URL and so survived the sweep that found every other reference.

  The address is the convention: `/api/<capability>/<what it lists>`, with the
  sub-api named for the MARKER, so a consumer that learns one learns
  `/api/http/paths` and `/api/webapp/paths` for free.

  EDN, not JSON, and `:http/raw` so the adapter leaves it alone. A malli schema
  is data made of keywords, symbols and vectors; JSON would render `:string`
  and `\"string\"` identically and the far end could not tell them apart."
  [req]
  {:status 200
   :http/raw true
   :headers {"Content-Type" "application/edn"}
   :body (pr-str (:doc (:http/reads req)))})

(defn ^{:http/method :get :rest/path "/api/http/paths" :http/auth :public
        :rest/media-type "application/edn"
        :rest/response contracts/http-paths-document
        :rest/unconstrained-ok
        ":auth is an app's own :http/auth declaration verbatim, whose grammar
         is open by design — :public, :authenticated, or a composite like
         [:group \"admin\"] — so no narrower shape is true of all of them.
         PERMANENT for the same reason the typed document's is: a predicate
         cannot be published to a consumer who has to evaluate it."
        :http/reads {:doc [:ui/http-paths []]}}
  http-paths
  "GET /api/http/paths — the CONTENT this project serves, as EDN.

  The gap this closes, reported by a consumer building a reviewer UI: **a
  remote consumer of a slopp project could discover every API and zero
  pages.** Content is absent from the typed document by KIND, because a
  generated wrapper whose `(.json resp)` runs against HTML would be nonsense —
  which is a statement about TYPING and was never a reason for a page to be
  undiscoverable.

  Every row DESCRIBES a page and none carries it: shape, size, what it
  actually answers with, and whether it boots an application. An index
  carrying bodies would put a stylesheet on the wire to render one table row,
  and a reader who wants the body has the URL.

  **`[]` is the ordinary answer.** Most projects serve no content of their own
  — slopp's own API is one — so a consumer renders the empty document far more
  often than a populated one.

  EDN for the same reason its siblings are: `:auth` values are keywords and
  vectors, and JSON would flatten a keyword into a string the far end cannot
  tell from one."
  [req]
  {:status 200
   :http/raw true
   :headers {"Content-Type" "application/edn"}
   :body (pr-str (:doc (:http/reads req)))})

(defn ^{:http/method :get :rest/path "/api/webapp/paths" :http/auth :public
        :rest/media-type "application/edn"
        :rest/response contracts/webapp-paths-document
        :http/reads {:doc [:ui/webapp-paths []]}}
  webapp-paths
  "GET /api/webapp/paths — the pages this project's BROWSER routes to, as EDN.

  One row per `:webapp/path` form, naming the function that renders it and
  what that page is.

  **The client-side half of the content surface.** `/api/http/paths` says what
  the SERVER hands a browser; this says what the browser then does with it,
  which `/api/http/paths` cannot, because a client-routed app is one server
  route and a dozen addresses.

  It used to publish the server-side PREFIXES a document declared it owned.
  That mechanism is gone: a shell declares its own `**` path, so the fallback
  IS the declaration and there is no prefix list to publish.

  `[]` unless the project has a browser app, which most do not."
  [req]
  {:status 200
   :http/raw true
   :headers {"Content-Type" "application/edn"}
   :body (pr-str (:doc (:http/reads req)))})

(defn ^{:http/method :get :rest/path "/api/module/:m" :http/auth :public
        :rest/request contracts/module-request
        :rest/response contracts/module-detail
        :http/reads {:detail [:browse/module [:path-params :m]]}}
  module
  "GET /api/module/:m — one module from the inside: its namespaces, the edges
  among them, the layering, and what crosses its boundary.

  The level below `/api/modules`, which ships module→module `:deps` and so
  stops exactly where the next question starts — descending into a box on the
  diagram had nothing behind it.

  An unknown module is a 404, not an empty frame, on the same reasoning
  `ns-outline` uses: `{:namespaces []}` would say the module exists and holds
  nothing, which is a different statement and a false one."
  [req]
  (if-let [d (:detail (:http/reads req))]
    {:status 200 :body d}
    {:status 404 :body {:error "no such module"}}))

(defn ^{:http/method :get :rest/path "/api/search" :http/auth :public
        :rest/request contracts/search-request
        :rest/response contracts/search-results
        :http/reads {:results [:browse/search []]}}
  search
  "GET /api/search?q=&limit= — the door: everything whose name, docstring,
  recorded why or source matches, ranked across all three grains at once.

  Every other read here answers a question a reader already knows how to ask.
  This is the one that finds the ADDRESS, which is why it is the entry that
  decides whether the rest of the reader API has a way in at all — `/store`
  opening on a module diagram with no way to ask a question was the finding
  that drove the wave.

  **Always 200, including for a blank query and for no matches.** The search
  screen is reachable by URL, so a reader can arrive having asked nothing; the
  honest answer to that is the empty state, and a 400 would put an error panel
  in front of someone who did nothing wrong. No-match is 200 for the ordinary
  reason. There is no 404 here at all — unlike `/api/module/:m`, this endpoint
  has no subject that can fail to exist, only a question that can go
  unanswered, and those are different things.

  `:rest/request` is declared for the same reason `form`'s is: without it the
  generated client takes a params map nothing reads from, so `?q=` answers on
  the wire and is unreachable through the typed client — which pushes a
  consumer toward the hand-rolled fetch `direct-http` refuses."
  [req]
  {:status 200 :body (:results (:http/reads req))})

(defn ^{:rest/unconstrained-ok
        "`:config → :default` and `:config → :effective` ONLY. A setting's value has the type ITS OWN registry entry declares — most are scalars and http.auth.providers is a SET — so there is no common type to name, and the registry is open: the next capability may declare a map. Constraining them to a scalar union was tried and REFUSED a real document, 500ing this endpoint against its own contract. Every other field here IS named: :value is the raw stored string, :owners is string to string, :orphaned/:value is a string."}
        ^{:http/method :get :rest/path "/api/config" :http/auth :public
        :rest/media-type "application/edn"
        :rest/request contracts/config-request
        :rest/response contracts/config-document
        :http/reads {:doc [:ui/config []]}}
  config
  "GET /api/config — how this project is CONFIGURED, as EDN.

  One row per setting, each carrying the capability that OWNS it, so a single
  page can group by owner without knowing the list of capabilities. That is
  the reason it is one document rather than one per capability: a page joining
  four would omit the fifth the day one ships, and `:owner` makes grouping the
  page's job at no cost.

  `?prefix=http` narrows to a block, matching the segments the keys already
  have; `?prefix=http.auth` narrows further. A prefix nothing matches answers
  an EMPTY `:config` rather than everything — a filter that silently stops
  applying is the one failure that turns a typo into a page nobody questions.

  **A credential family publishes its key and withholds its value**, marked
  `:secret`. This route is `:public` like its siblings and the registry carries
  token and client-secret families, so *this is configured and I am not showing
  you* is the answer a settings page gets. Which families those are is
  DECLARED, in `capabilities/secret-families`."
  [req]
  {:status 200
   :http/raw true
   :headers {"Content-Type" "application/edn"}
   :body (pr-str (:doc (:http/reads req)))})

(defn ^{:http/method :get :rest/path "/api/bundle" :http/auth :public
        :rest/response contracts/bundle
        :http/reads {:bundle [:orient/bundle []]}}
  bundle
  "GET /api/bundle?ask= — the ask's map as ONE injectable text block: the
  prompt hook fetches this at prompt time and the model starts with its
  orientation already in context (moonshot A). Always 200; a blank ask is
  the plain ranking, exactly as /api/search answers a blank query."
  [req]
  {:status 200 :body {:bundle (:bundle (:http/reads req))}})
