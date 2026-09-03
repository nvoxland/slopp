(ns slopp.rules.webapp
  "Done-grain advisories for the app that runs IN THE PAGE, and the store reads
  they share.

  The sibling of `slopp.edit.webapp` across the grain boundary: a write gate
  judges ONE form against the store it would produce, and these judge an
  EPISODE — which is what lets them see a page whose closure changed without
  the page itself being written.

  **`page-cljs-reach` is one producer on purpose.** The done advisory, the
  `full_check` sweep, and `module_platform`'s stranded-page report all answer
  from it, because a rule that refuses at one surface and a report that lists
  at another must agree, and they only can if they are one derivation.

  Both advisories gate on `webapp.enabled`. `webapp-client-routes-consequences-check`
  gated on nothing until wave 4 — the same defect slopp-ui measured in `rest`'s
  contract advisories, where a check with no capability test runs on every
  store while the arms report claims its owner controls it.

  Neighbours: `slopp.rules` holds the registry these are declared in and the
  severity dial; `slopp.rules.http` is the server-side half, which judges
  routes, links and static mounts rather than pages."
  (:require [slopp.store :as store]
            [slopp.project.capabilities :as capabilities] [clojure.string :as str] [slopp.edit.http :as edit.http] [slopp.index.analyze :as analyze] [slopp.store.render :as store.render] [slopp.edit :as edit] [slopp.http.router :as router] [slopp.index.refs :as refs]))

(defn ^:export page-cljs-reach
  "The `:cljs` namespaces `ns-sym`'s require closure reaches, sorted — empty
  when a JVM can load the whole closure.

  ONE producer on purpose: the `http-page-reach` done-advisory, the full_check
  sweep, and `module_platform`'s stranded-page report all answer from here,
  because a rule that refuses at one surface and a report that lists at
  another must agree, and they only can if they are one derivation."
  [st ns-sym]
  (->> (store/ns-closure st ns-sym)
       (filter #(= :cljs (store/platform-for st %)))
       sort
       vec))

(defn webapp-page-reach-check
  "Done-advisory: a `^:app/entry` entry whose namespace CLOSURE reaches a
  `:cljs` namespace. Reports `{:form :cljs [namespaces]}`; inert until the
  store opts into `webapp`.

  **The write gate is the shallow half.** `webapp-page-unreachable` refuses an
  entry marked in a `:cljs` namespace, which catches the entry itself and
  nothing it calls. An entry sitting in `:cljc` and reaching a `:cljs` view
  passes the gate and fails the tool — and that is where a real app lands,
  because the entry is small and the views are where the code is.

  **Its FRAME, stated honestly (the review caught the prose overstating it):**
  at `done` this sees only pages in `changed`, so the case its class exists
  for — declaring some OTHER namespace `:cljs`, which strands an entry nobody
  wrote to — is silent here. Two surfaces cover that case instead:
  `module_platform` reports [[stranded-pages]] at the write that does the
  stranding, and the `full_check` sweep re-grades every page. The advisory
  earns its keep on the ordinary edit-the-page path; it is not the safety
  net, and prose claiming otherwise was teaching a false comfort.

  Namespace grain, because platform is declared per namespace, so a finer
  answer would be a proxy for one slopp does not actually have.

  It names the `:cljs` namespaces rather than the entry alone. The entry is
  usually fine; the finding is which dependency stranded it, and that is what
  a reader has to move or split.

  Reads the marker through `store/form-name-meta`, the generic address, rather
  than through an app type's own reader — a webapp check has no business
  requiring http's namespace to ask what metadata a form carries."
  [_session st* changed]
  (when (capabilities/enabled? st* "webapp")
    (vec (keep (fn [fid]
                 (when-let [e (store/form-by-id st* fid)]
                   (when (:app/entry (store/form-name-meta e))
                     (let [own  (store/ns-of-form-id st* fid)
                           cljs (page-cljs-reach st* own)]
                       (when (seq cljs)
                         {:form (symbol (str own) (str (:name e)))
                          :cljs cljs})))))
               changed))))

(defn ^:export page-routes
  "Every PAGE this store declares, as `[{:path :page :doc} …]` sorted by
  address — `[]` when it has no browser app.

  A page is a form carrying `^{:webapp/path \"/things/:id\"}`: the address the
  BROWSER routes to, in the same grammar and the same coordinate system as
  `:http/path`.

  **The marker is on the form that renders the page**, which is where
  `:rest/path`, `:http/path` and `:cli/command` already are, and everything
  follows from that one placement: write gates, `query_surface`, a published
  document, and a reference graph that answers *which endpoints does this page
  call*.

  **It replaces reading a `:webapp/routes` VECTOR out of the entry fn**, and
  the difference is not stylistic. A table inside a fn body is a value, so a
  big app builds it in pieces and names a var among them — and a reader that
  only understands literals reports a partial table as the whole one. That is
  what `:unreadable` existed to say. Metadata on a name is readable by
  construction, so a store cannot half-declare its pages and there is nothing
  left for that key to report.

  `[]` and never nil, so a caller joining against it does not have to tell
  \"no browser app\" from \"a browser app that routes nothing\"."
  [st]
  (vec (sort-by :path
                (for [nsx  (keys (:namespaces st))
                      ;; a fixture page is not surface — the rule
                      ;; `app-namespaces` states for every other publisher,
                      ;; and the one this traversal was written without. A
                      ;; consumer fetching /api/webapp/paths against slopp's
                      ;; own store was handed a page written that morning to
                      ;; prove the router finds one. The published document is
                      ;; the mild half: the BUILD reads this to generate a
                      ;; browser app's route table, so a fixture would ship an
                      ;; address into a real application
                      :when (not (store.render/test-ns? nsx))
                      e    (store/forms st nsx)
                      :when (:name e)
                      :let [p (:webapp/path (store/form-name-meta e))]
                      :when (string? p)]
                  {:path p
                   :page (symbol (str nsx) (str (:name e)))
                   :doc  (store/form-docstring (:node e))}))))

(defn ^:export request-paths
  "Every endpoint DESCRIPTOR path this store declares, as `[{:path :form} …]`
  sorted — `[]` when nothing names an endpoint.

  **The other half of a route reference.** A literal `:href` in a view is
  joined against the served table by `http-dangling-route-refs`; a descriptor's
  `:http/path` is the same kind of claim about the same table, and until this
  existed nothing read it. Both are a statement that this store serves
  something.

  **It reads a DESCRIPTOR, which is a map literal, not a request.** A request
  carries a finished `:http/url` — `slopp.http.endpoint/request` resolved it —
  so it holds no pattern to join and is not scanned. The pattern lives on the
  descriptor, which is a `def` and therefore always readable, so this no longer
  depends on where an app happens to assemble its map.

  `:http/path` is also a route MARKER, and the two do not collide: a marker
  rides the name symbol's metadata and this walks collection structure, which
  `tree-seq` never descends into. Measured rather than assumed.

  **Read from map literals anywhere in the store**, like [[client-routes]] and
  for the same reason: a descriptor is ordinary data and an app may build one
  beside the screen it belongs to.

  A non-literal path is SKIPPED rather than guessed at — a computed path is one
  this cannot read, and inventing an answer would make the join quietly partial,
  which is worse than a path reported as unserved because that at least gets
  looked at."
  [st]
  (vec (sort-by (juxt :path (comp str :form))
                (distinct
                 (for [nsx  (keys (:namespaces st))
                       e    (store/forms st nsx)
                       ;; `^{:http/external-path \"why\"}` skips a form WHOLE, the
                       ;; same marker `rules.http/ui-route-refs` honours for a
                       ;; link and for the same question. It is the escape an
                       ;; absolute url cannot cover: an API proxied under this
                       ;; app's own mount point is real, served, and not this
                       ;; store's — and cannot be written in full when the
                       ;; prefix is only known at runtime. Generation DECLARES
                       ;; it on every descriptor built from a foreign contract,
                       ;; so the escape is never hand-edited onto generated code.
                       :when (not (:http/external-path (store/form-name-meta e)))
                       :let [sx (try (store/form-sexpr (:node e)) (catch Exception _ nil))]
                       node (tree-seq coll? seq sx)
                       :when (map? node)
                       :let [p (get node :http/path)]
                       :when (string? p)]
                   {:path   p
                    ;; the verb travels with the address because half an
                    ;; address is not one: two endpoints share a path and
                    ;; differ only here, and a reader shown the path alone
                    ;; cannot tell which of them a page calls
                    :method (get node :http/method :get)
                    :form   (symbol (str nsx) (str (:name e)))})))))

(defn ^:export page-calls
  "Every ENDPOINT each page reaches, as `{page-symbol [{:endpoint :method
  :path :params} …]}` — a page that calls none is ABSENT rather than empty,
  and so is `:params` on a call that passes no literal.

  **What a reader of a browser app actually wants from a page row.** Not which
  function computes a url: which endpoint this screen talks to, and with what.
  The row used to answer `:request` — the name of a builder var — plus
  `:loads`, a url taken from whatever single path that builder named.

  Both were wrong in the same direction, and the direction is the shape of the
  framework rather than an oversight. A page makes as many calls as it likes,
  whenever it likes, by naming DESCRIPTORS. A declaration beside the route row
  could only ever describe one of them, and only while somebody kept it in
  agreement with the code.

  So the endpoints are the REFERENCE GRAPH: what the page's form references
  that is an endpoint. It cannot drift from what the page calls, because it is
  what the page calls.

  **`:params` are the LITERAL arguments, and they are policy.** Measured on the
  first store to render this table: of eighteen asks, fourteen pass no literal
  at all and four pass exactly one — `:depth 2` because a form page draws its
  neighbourhood and one hop does not fill it, `:limit 50` sent explicitly AT
  the endpoint's default because the screen says *showing 20 of 340* and a
  silently-applied ceiling falsifies that sentence. Neither appears in the
  endpoint's contract, in the route, or anywhere else, so a reader asking why a
  page fetches two levels has nowhere else to look.

  A RUNTIME argument is not published — `{:id (:id params)}` is a value this
  page does not have, and stating one would be a claim rather than a fact.
  Values travel AS READ: `{:depth 2}` with an integer, because a consumer
  comparing it against the endpoint's declared type cannot un-stringify a
  string.

  **Scalars only**, deliberately: a literal collection is arguably literal and
  is rare enough that admitting it would widen the claim for no measured case.

  **A `:params` map is PARTIAL by construction**, which is why the document
  publishes a map rather than a rendered call. `:id` is a route capture, so it
  is correctly absent — and `/api/form/:id?depth=2` is therefore not an address
  anyone can fetch while looking like one that is a substitution away.

  The whole derivation is bounded by what a descriptor is: a map literal with
  an `:http/path`. A page reaching an endpoint some other way is invisible
  here, which is why absent rather than `[]` is the honest empty."
  [st]
  (let [endpoints (into {} (for [{:keys [path method form]} (request-paths st)]
                             [form {:endpoint form :method method :path path}]))
        wanted    (set (map :page (page-routes st)))
        entries   (vec (for [nsx (keys (:namespaces st))
                             e   (store/forms st nsx)
                             :when (and (:name e)
                                        (contains? wanted (symbol (str nsx) (str (:name e)))))]
                         [(:id e) (symbol (str nsx) (str (:name e))) e]))
        pages     (into {} (map (juxt first second)) entries)
        literal?  (fn [v] (or (number? v) (string? v) (keyword? v) (boolean? v)))
        ;; `(webapp/ask! page api/form {:id (:id params) :depth 2})` — matched
        ;; on the NAME `ask!` whatever alias spells it, because the alias is
        ;; the app's choice and the call is the framework's. The descriptor is
        ;; matched by its own NAME against the endpoints the graph already says
        ;; this page references, so nothing here has to resolve an alias.
        asks      (fn [sx]
                    ;; DESTRUCTURED rather than indexed. `(nth node 2)` on a stored form is
                    ;; the position where a docstring and a def's value collide, and
                    ;; a reader meeting it here has to work out that this node is
                    ;; neither — it is a CALL, `(ask! page descriptor params)`.
                    (for [node (tree-seq coll? seq sx)
                          :when (seq? node)
                          :let  [[verb _page descriptor params] node]
                          :when (and (symbol? verb)
                                     (= "ask!" (name verb))
                                     (symbol? descriptor)
                                     (map? params))]
                      [(name descriptor)
                       (into {} (filter (comp literal? val)) params)]))
        ;; two asks for one endpoint that disagree about their literals leave
        ;; no single answer, so the page publishes none — the safe direction,
        ;; and the same one a computed path takes
        asked     (into {}
                        (for [[fid _ e] entries]
                          [fid (into {}
                                     (for [[nm ps] (group-by first
                                                             (asks (try (store/form-sexpr (:node e))
                                                                        (catch Exception _ nil))))
                                           :let [vs (distinct (map second ps))]
                                           :when (= 1 (count vs))]
                                       [nm (first vs)]))]))]
    (into {}
          (for [[fid rs] (group-by first
                                   (for [r    (refs/refs st)
                                         :let [t (symbol (str (:to-ns r)) (str (:to-name r)))]
                                         :when (and (contains? pages (:from-form r))
                                                    (contains? endpoints t))]
                                     [(:from-form r) (get endpoints t)]))
                :let [called (vec (sort-by :path
                                           (for [row (distinct (map second rs))
                                                 :let [p (get-in asked [fid (name (:endpoint row))])]]
                                             (cond-> row (seq p) (assoc :params p)))))]
                :when (seq called)]
            [(get pages fid) called]))))

(defn ^:export webapp-report
  "The `webapp` section of `query_surface`: what this browser application IS.

  The fifth thing a capability is — PORT, ADAPTER, FAKE, GATES, SURFACE REPORT —
  and the only one `webapp` had never had. Its readers are the three the catalog
  names: the agent, a consuming tool, and the HUMAN, who does not read the code
  and needs a rendered picture of the application.

  Three questions, which between them are what a browser app is:

  - `:screens` — every declared ADDRESS, the function that renders it, and the
    endpoints it CALLS. This is the map somebody draws, so a call is the
    endpoint's own address rather than the name of a function that computes
    one: a var answers WHICH function, and the reader of this report does not
    read the code.

    Addresses rather than screens, and the distinction is load-bearing here
    because this is what a count would be taken from: a row's screen is not
    unique and a screen's row is not unique, so an app with a lens bar or a
    print view has more rows than screens and neither number is wrong.
  - `:actions` — what a reader can DO, and which kind each is. The `:effectful?`
    ones reach a server and the `:leaves?` ones hand the page back to the
    browser, so a human asking what a control does needs the kind visible rather
    than inferred from the name.
  - `:cljs` — how many namespaces are outside the fast loop.

  **`:screens` reads the page MARKERS, and it used to read a table.** The
  addresses lived in a `:webapp/routes` vector inside the entry fn — a VALUE,
  so a table built in pieces was unreadable and this report carried an
  `:unreadable` line to say which. A page carries its own address as metadata
  now, which cannot be half-declared, so that whole class of skip is gone from
  the rows. `:unreadable` survives for `:webapp/actions`, which is still a map
  literal an app may name a var for.

  **`:session-loads` is GONE, with the key that declared it.** It answered what
  an app fetched that belonged to no screen — and a page ASKS for what it needs
  now, `ask!` being start-if-absent, so a load belonging to everyone is asked
  for by every page that shows it and found already there after the first.
  There is no separate declaration left to report.

  **`:cljs` is the goal stated as a NUMBER.** \"An app that opts into `webapp`
  writes no ClojureScript\" is an aspiration until a store can answer how much it
  writes; zero is the claim being true, and a store that has drifted to five says
  so without anybody having to go looking.

  Empty until `webapp.enabled` — the reading side of the inertness the gates
  have, and for the same reason: a project with no browser app must not be
  DESCRIBED as having one.

  Rows carry `:kind`, so a renderer that knows nothing about this capability can
  draw a screen beside a command or an endpoint.

  **No member may take the report down**, which is `rules.rest/contracts-report`'s
  own scar rather than caution in the abstract: a contract that answered
  `[:or …]` threw while reading its children as map entries, and made nine
  endpoints unreadable on the day a store enabled the capability. Here every
  extraction is total — a malformed action map contributes a row with the kinds
  it could read."
  [store]
  (if-not (capabilities/enabled? store "webapp")
    {:screens [] :actions [] :cljs 0}
    (let [rows     (for [nsx  (keys (:namespaces store))
                         e    (store/forms store nsx)
                         :let [sx (try (store/form-sexpr (:node e)) (catch Exception _ nil))]
                         node (tree-seq coll? seq sx)
                         :when (map? node)]
                     [nsx node])
          ;; **An app may name a VAR where a literal would go** —
          ;; `{:webapp/actions actions}` — and the extractor below seq's what it
          ;; finds, so a symbol threw `Don't know how to create ISeq from` and
          ;; took the whole of `query_surface` with it, including `:cli`,
          ;; `:http` and `:rest`, which have nothing to do with any of this.
          ;;
          ;; That is this function's own docstring failing in this function: no
          ;; member may take the report down. Reported by the store that could
          ;; no longer read the number `D-webapp` names as the target — so the
          ;; throw hid the metric the wave is scored by, in the tool that
          ;; reports it.
          declared (fn [node k pred]
                     (let [v (get node k)]
                       (when (pred v) v)))
          ;; what a skip SHOWS, because "not a literal" is the rule and the
          ;; value is the evidence — an author reading this needs to know
          ;; whether they meant it
          shown    (fn [v] (let [s (pr-str v)]
                             (if (> (count s) 60) (str (subs s 0 57) "…") s)))
          ;; **A skipped ENTRY is as named as a skipped SECTION**, and the
          ;; sentence that justified naming sections turns on this one step in:
          ;; a section quietly missing reads as a store that declares nothing
          ;; there, and an entry quietly missing reads as an app that declares
          ;; FEWER than it does. `:actions []` is the worse of the two, because
          ;; an empty vector is an affirmative claim of emptiness rather than an
          ;; absence — nothing in it distinguishes a store with seven actions
          ;; declared by var from one with none.
          ;;
          ;; The ADDRESSES used to need this too, and no longer can: they are
          ;; metadata on a name, which is readable by construction.
          unreadable
          (vec (concat
                (for [[nsx node] rows
                      :when (and (contains? node :webapp/actions)
                                 (not (map? (get node :webapp/actions))))]
                  ;; NOT "so none of it is in this answer", which this cannot know:
                  ;; this reader scans every map literal in the store, so the
                  ;; same rows may be declared literally somewhere else and
                  ;; reported from there
                  (str nsx " declares :webapp/actions as "
                       (shown (get node :webapp/actions))
                       " — only a literal is read here, so nothing was taken"
                       " from THIS declaration"))
                (for [[nsx node] rows
                      [a _decl] (declared node :webapp/actions map?)
                      :when (not (keyword? a))]
                  (str nsx " declares the action " (shown a)
                       " — an action is named by a keyword"))))
          calls    (page-calls store)
          screens  (vec (for [{:keys [path page doc]} (page-routes store)]
                          (cond-> {:kind :screen :path path :screen page}
                            doc             (assoc :doc doc)
                            (get calls page) (assoc :calls (get calls page)))))
          actions  (vec (sort-by :action
                                 (for [[_nsx node] rows
                                       [a decl] (declared node :webapp/actions map?)
                                       :when (keyword? a)]
                                   (cond-> {:kind :action :action a}
                                     (:effectful? decl) (assoc :effectful? true)
                                     (:leaves? decl)    (assoc :leaves? true)))))]
      {:screens    screens
       :actions    actions
       :unreadable unreadable
       :cljs       (count (filter #(= :cljs (store/platform-for store %))
                                  (keys (:namespaces store))))})))

(defn ^:export request-paths-unserved
  "The [[request-paths]] no endpoint in this store declares, sorted — `[]` when
  every screen asks for something that exists.

  **The join is EQUALITY, not a route match.** A request path is a PATTERN in
  the same grammar as `:http/path` — `/api/things/:id`, with its captures
  supplied separately as `:webapp/path-params` — so asking the router to match
  it as though it were a concrete url would answer nil for every parameterized
  endpoint in the store and report a working app as entirely broken. The two
  sides are the same kind of string and compare directly.

  **An ABSOLUTE url is left alone.** An app calling a third-party API declares a
  whole url, and reporting those would make this noise on every store that talks
  to anything. Same discipline `:http/external-path` states for links: this
  answers for what THIS store serves and says nothing about anyone else's
  server.

  **A request naming its own `:webapp/base` is left alone for the same
  reason**, and it is the form of that statement a MOUNTED app can actually
  write: it cannot spell an absolute url, because the origin is only known at
  runtime. A path measured from a base it names is addressed at whatever sits
  THERE, which is not this store or the declaration would be saying nothing.

  A base of `\"\"` is the empty case of that — the origin — which is what the
  retired `:webapp/from-origin` boolean used to say. Nothing reads that flag
  now: it was an escape from a field that could not hold two values, and once
  the field holds any base the escape has no cause. A request still carrying it
  is joined like any other and reported, which is the correct answer for a
  marker that waives nothing.

  Every escape here is a DECLARATION rather than a silence, which is what keeps
  the finding list clearable — and a list nobody can clear is a list everybody
  skims.

  Reads `edit.http/web-endpoint-rows` — the store's single route traversal —
  rather than `rules.http/endpoints`, which is the same rows one layer up.
  `rules.http` already depends on this namespace for the client route table, so
  the join has to be made from here or not at all."
  [st]
  ;; BOTH route kinds, through the one accessor. The question is whether this
  ;; store SERVES what a screen asks for, and serving does not care whether the
  ;; route is a typed api or an asset — a screen fetching a served stylesheet is
  ;; ordinary. Reading `:http/path` alone reported every `:rest/path` endpoint
  ;; as missing: a finding nobody can discharge, in a report whose whole value
  ;; is that its findings can be.
  (let [served (into #{} (keep #(edit.http/route-path (:meta %)))
                     (edit.http/web-endpoint-rows st))]
    (vec (remove (fn [{:keys [path]}]
                   (or (contains? served path)
                       (str/includes? path "://")
                       ;; protocol-relative is the same statement one hop along
                       (str/starts-with? path "//")))
                 (request-paths st)))))

(defn webapp-request-paths-are-served-check
  "Done-advisory: screens whose request names a path this store does not serve.
  Inert until the store opts into `webapp`.

  **The gap wave 4d created, and it was written into the boundary inventory the
  day it appeared rather than found later.** A literal `:href` is resolved
  against the served table by `http-dangling-route-refs`. The `:webapp/path`
  inside a screen's request is the same kind of claim about the same table, made
  in a different key, and nothing read it — so moving the fetch out of the
  browser and into a declaration bought verifiability everywhere except here.

  The failure is quiet in the way this capability keeps naming: the url routes,
  the screen renders, chrome and nav are fine, and one pane says it could not
  load. Every other pane works, so the reader's report is *the thing pages are
  slow* rather than *this endpoint does not exist*.

  **The finding names what the store DOES serve**, because a typo is nearly
  always one of them and a complaint an author cannot act on is one they learn
  to skim.

  Advisory rather than a refusal, and whole-store rather than episode-scoped,
  for the same two reasons its `webapp-client-routes-are-served` neighbour has:
  a store mid-migration is exactly the state this fires on, and the two
  declarations that drift apart are usually not edited together."
  [_session st* _changed]
  (when (capabilities/enabled? st* "webapp")
    ;; both kinds, matching the join above — the "this store serves …" list in
    ;; the teach string has to be the same set the finding was computed from,
    ;; or an author is handed a remedy that does not contain their path
    (let [served (sort (distinct (keep #(edit.http/route-path (:meta %))
                                       (edit.http/web-endpoint-rows st*))))]
      (vec (for [{:keys [path form]} (request-paths-unserved st*)]
             {:path path
              :form form
              :serves (vec served)
              :teach (str form " requests " (pr-str path) ", which no endpoint"
                          " in this store declares. The url will route and the"
                          " screen will render — one pane just always fails to"
                          " load, while everything around it works, so this gets"
                          " reported as slowness rather than as a missing"
                          " endpoint."
                          (when (seq served)
                            (str " This store serves "
                                 (apply str (interpose ", " (map pr-str served)))
                                 "."))
                          " Two ways it is not a typo, and both are declarations"
                          " rather than silences: a THIRD-PARTY api is a whole"
                          " url, scheme and all; and a path something OUTSIDE"
                          " this store serves — a proxied API under this app's"
                          " own mount point, which cannot be written in full"
                          " because the prefix is known only at runtime — is"
                          " ^{:http/external-path \"why\"} on the form, the same"
                          " marker a link takes.")})))))

(defn webapp-client-code-check
  "Done-advisory: the `:cljs` namespaces this store still hand-writes. Inert
  until the store opts into `webapp`, and silent at zero.

  **This is the capability's own goal, arriving instead of waiting to be
  asked.** \"An app that opts into `webapp` writes NO ClojureScript\" became
  readable when [[webapp-report]] gained `:cljs`, and readable is not reported:
  nobody opens a surface report on an ordinary day, so a store drifting from
  zero to five drifts silently and the number is consulted only by whoever
  already suspects.

  What a `:cljs` namespace costs, which is what the finding says rather than
  implies: it cannot load into the JVM oracle, so it is outside the fast loop
  and every edit costs a compile to learn anything — and its only verification
  is that it COMPILED, which is a proxy for correctness and a weak one. The only
  real webapp's two worst bugs both lived in exactly such a namespace.

  **Advisory and never a refusal.** Sketching in ClojureScript is legitimate; a
  browser-only library binding may have no portable form at all; and an app
  mid-migration is precisely the state this fires on. What is not legitimate is
  not knowing.

  Whole-store rather than episode-scoped, for its neighbours' reason: a
  namespace becomes `:cljs` by a `module_platform` declaration that touches no
  form, so the episode that adds one has nothing for an episode-scoped rule to
  hang a finding on."
  [_session st* _changed]
  (when (capabilities/enabled? st* "webapp")
    (vec (for [n (sort (filter #(= :cljs (store/platform-for st* %))
                               (keys (:namespaces st*))))]
           {:ns n
            :teach (str n " is :cljs, so it never loads into the image — it is"
                        " outside the fast loop, every edit costs a compile to"
                        " learn anything, and its only verification is that it"
                        " COMPILED. `webapp` exists so an app needs none of it:"
                        " routing, the render loop, the listeners, load states,"
                        " the performer and session loads are all declarations"
                        " now. If this namespace is a browser-only binding with"
                        " no portable form, that is a real answer — and worth"
                        " being a deliberate one rather than a leftover.")}))))

(defn ^:export own-mount-nses
  "The store namespaces that MOUNT this app themselves — every namespace
  containing a call to `slopp.webapp.dom/mount!` — sorted, `[]` when none.

  This is what decides whether `build!` generates a browser entry: generating
  one beside a store's own produces a bundle that mounts twice over the same
  element, and a double mount compiles exactly as clean as a single one, so
  nothing downstream can report it. The only symptom is a page that renders
  twice.

  **The signal is the CALL, and the two cheaper answers are both wrong.** By
  namespace NAME — the guard this replaced, which asked whether the store had
  a namespace called `native.client` — only ever caught the generator
  colliding with itself; a store's own entry is named whatever the store names
  it, so every other name passed clean. By REQUIRE, it would be wrong the
  other way: `slopp.webapp.dom` also publishes `click-data` and `typed-value`,
  which an event handler reads without mounting anything, and that store would
  silently lose the generation the capability exists to give it.

  So aliases are resolved by the ANALYZER rather than matched as text, and the
  string test is only a prefilter — a namespace that never names
  `slopp.webapp.dom` cannot resolve a call to it, so it is skipped before the
  analysis it would only fail."
  [st]
  (vec (sort (for [n     (keys (:namespaces st))
                   :let  [src (store.render/render-ns st n)]
                   :when (str/includes? src "slopp.webapp.dom")
                   :when (some (fn [u]
                                 (and (= 'slopp.webapp.dom (:to u))
                                      (= 'mount! (:name u))))
                               (:var-usages (analyze/analyze src)))]
               n))))

(defn ^:export page-rows
  "Every `^:app/entry` entry in `st`, as `[{:ns :name :page :closure} …]` sorted —
  `[]` when nothing declares one.

  `:page` is the qualified symbol a generated browser entry CALLS, and
  `:closure` is `ns` plus everything it transitively requires, which is what
  that entry must REQUIRE. Naming a page without requiring what it reaches
  produces a call to a var that does not exist — which reaches a reader as a
  blank page and reads like a rendering bug rather than a wiring one.

  One traversal, because four sites had inlined it — the page-unreachable gate,
  the page-reach advisory, its whole-store face, and now the build. A fifth copy
  is how the answers start to differ."
  [st]
  (vec (sort-by (comp str :page)
                (for [n     (keys (:namespaces st))
                      f     (store/forms st n)
                      :when (and (:name f) (:app/entry (store/form-name-meta f)))]
                  {:ns      n
                   :name    (:name f)
                   :page    (symbol (str n) (str (:name f)))
                   :closure (vec (sort (store/ns-closure st n)))}))))

(defn ^:export stranded-pages
  "Every `^:app/entry` in `st` whose namespace closure reaches `:cljs`, as
  `[{:page ns/name :cljs [namespaces]} …]` — empty when every page opens.

  This is the whole-store face of [[page-cljs-reach]], and it exists for the
  surface the done-advisory structurally cannot serve: declaring a namespace
  `:cljs` strands a page WITHOUT any write to the page, so the done that
  follows has no changed form to hang the finding on. `module_platform` is
  the write that does the stranding, so `module_platform` is where this
  report belongs — the reader who broke the reach is told at the moment they
  broke it, not at the next full_check."
  [st]
  (vec (for [{:keys [ns page]} (page-rows st)
             :let  [cljs (page-cljs-reach st ns)]
             :when (seq cljs)]
         {:page page :cljs cljs})))

(defn ^{:export "slopp.rules"} self-prefixed-links
  "The forms in which this store calls `slopp.webapp/prefix-links` itself,
  sorted — `[]` when it does not.

  **This is the fact that decides whether a literal `:href` can be joined
  against the served table at all.** `prefix-links` rewrites links at render,
  so a store that calls it writes hrefs in APP space while the route table is
  in SERVER space. The two are the same only when the prefix is empty, and the
  check has no way to know the prefix — it is route state, not configuration.

  Measured in the consuming store: 23 literal links, every one correct at
  runtime, every one reported as dangling. Both available answers were wrong.
  Reporting them as broken spends the credibility of every other finding in a
  list whose whole value is that its findings can be cleared; passing them
  silently turns the guarantee off with nothing saying so. Naming the cause is
  the third answer, and it is the one this makes possible.

  **The alias is RESOLVED rather than matched by name.** A symbol called
  `prefix-links` in somebody else's namespace is a coincidence; a call that
  resolves to slopp's own fn is a declaration. That distinction is the
  difference between a rule and a guess, and this rule exists to stop a report
  from being a guess.

  Answers the FORMS rather than a boolean, so a reader is told where to look —
  and so this can grow into \"which links\" without changing its shape."
  [st]
  (vec (sort-by str
                (distinct
                 (for [nsx  (keys (:namespaces st))
                       :let [aliases (edit/require-aliases st nsx)]
                       e    (store/forms st nsx)
                       :let [sx (try (store/form-sexpr (:node e))
                                     (catch Exception _ nil))]
                       node (tree-seq coll? seq sx)
                       :when (and (symbol? node)
                                  (= "prefix-links" (name node))
                                  (when-let [q (some-> (namespace node) symbol)]
                                    (= 'slopp.webapp (get aliases q q))))]
                   (symbol (str nsx) (str (:name e))))))))

(defn ^{:export "slopp.rules"} pages-unserved
  "The [[page-routes]] no SHELL in this store answers on a hard load, sorted —
  `[]` when every page is covered.

  **The join `:webapp/client-routing` records as its blind spot**, in the
  inventory's own words: *nothing compares the client's route table to the
  server's.* The failure is the one the only real webapp hit, with eight
  routes at once — every in-app CLICK keeps working, because that is client
  routing, and only a refresh or a shared link 404s. So the app is fine for
  whoever is already inside it and broken for whoever was sent a url, which is
  the population that never reports it because they assume the link was bad.

  **Asked with `router/match`, against the shell routes themselves.** That is
  the whole simplification this replaces: the old join compared a page path in
  APP space against a hand-written prefix in SERVER space, so it could not
  tell where app space began and tried EVERY suffix split of the prefix. A
  page declares its address the way a document declares its own, so both sides
  are one coordinate system and the question is just whether a shell's pattern
  covers this one.

  A shell's `**` matches zero segments, so a section's own root is covered by
  the same declaration that covers everything below it. That used to need a
  second explicit server route per section — a rule that lived in three
  docstrings and reached no author.

  Advisory rather than a refusal, for the reason a store mid-migration always
  gets: the state this fires on is a page and a shell that have not been
  reconciled, and refusing the writes would block the reconciliation."
  [st]
  (let [shells (vec (for [nsx (keys (:namespaces st))
                          e   (store/forms st nsx)
                          :when (:name e)
                          :let [m (store/form-name-meta e)]
                          :when (and (:webapp/shell m) (:http/path m))]
                      {:method :get
                       :path (str (:http/path m))
                       :handler (symbol (str nsx) (str (:name e)))}))]
    (vec (remove #(router/match shells :get (:path %)) (page-routes st)))))
