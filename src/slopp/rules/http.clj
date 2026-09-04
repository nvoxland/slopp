(ns slopp.rules.http
  "What the store can SAY about its own web surface, derived from the forms.

  `slopp.http` is the framework an app runs on and knows nothing about stores.
  This is the other direction: reading a store's `:web/*` metadata as data, so
  the route table, the read/effect vocabularies and the rendered link inventory
  are answerable without starting a server. The web write gates and
  `query_surface` are both this, which is deliberate — a rule that refuses at the
  write and a report that lists the surface must agree, and they only can if
  they are one derivation.

  Pure functions of the store value, which is what makes them true on every
  branch and after every merge rather than at whatever moment a server last
  booted.

  **The recurring difficulty is that a rendered path is not a call.** A link is a
  string; nothing resolves it, so a typo and a legitimate handoff are the same
  token. Everything here that classifies one — `:exact`/`:prefix`/`:unresolved`,
  and the `:http/external-path` escape that survives it — is an attempt to
  keep those apart, and each distinction was added because collapsing it made a
  report state something false. Prefer adding a category over widening one."
  (:require [slopp.project.capabilities :as capabilities]
            [slopp.http.router :as router] [slopp.store :as store] [slopp.store.render :as store.render] [clojure.string :as str] [rewrite-clj.node :as n] [slopp.edit.http :as edit.http] [slopp.index.refs :as refs] [slopp.rules.webapp :as rules.webapp]))

(defn ^:export endpoints
  "Every declared endpoint in the store — a `:http/path` form's route row:
  `{:handler :ns :name :form-id :method :path :auth :http/effects :http/reads
  :schema? :effectful?}` (slopp's own vocabulary keys stay namespaced —
  the same rule the request envelope follows). Built on the SAME traversal
  the write gates check (`modules/web-endpoint-rows`), so what query_surface
  shows is what the gates enforced. A pure function of the store value."
  [store]
  (mapv (fn [{:keys [ns name form-id meta kind path]}]
          {:handler   (symbol (str ns) (str name))
           :ns        ns
           :name      name
           :form-id   form-id
           :method    (:http/method meta)
           ;; :path and :kind come from the TRAVERSAL rather than being
           ;; re-derived here. `:rest/path` and `:http/path` are two markers,
           ;; and choosing between them in two places is how two answers appear
           :path      path
           :kind      kind
           :auth      (:http/auth meta)
           :http/effects (:http/effects meta)
           :http/reads   (:http/reads meta)
           ;; the CONTRACT the endpoint-schema gate enforces (D-web-contracts) —
           ;; this used to read :malli/schema, a different key, so every
           ;; contract-carrying endpoint reported :schema? false
           :rest/request  (:rest/request meta)
           :rest/response (:rest/response meta)
           ;; the client-route prefixes this document also answers for, when it
           ;; declares any. Surfaced rather than expanded into synthetic
           ;; catch-all rows: query_surface should show what the author
           ;; DECLARED, and three `/store/**` rows would read as
           ;; surface nobody wrote.
           :webapp/client-routes   (:webapp/client-routes meta)
           :schema?   (contains? meta :rest/response)
           :effectful? (boolean (:http/effectful meta))})
        (edit.http/web-endpoint-rows store)))

(defn performers
  "The app-defined performer vocabulary for `marker-key` (`:http/effect` or
  `:http/read`): {kind → performer qsym}. Delegates to the SAME derivation
  the undeclared-effect gate checks (`modules/web-performers`)."
  [store marker-key]
  (edit.http/web-performers store marker-key))

(def ^:private url-attrs
  "Hiccup tag → the attributes that name a URL ON THAT ELEMENT, per HTML.
   `:href` is a URL on <a>, <link>, <area> and <base>; `:action` on <form>;
   `:src` on the elements that FETCH one — script, img, iframe, source,
   track, embed, audio, video. Everywhere else these are inert attributes the
   browser ignores, so a map carrying one is not a route reference — it is
   ordinary data that happens to share a key name. This table is why
   `link-refs` needs no heuristics: the question 'is this a link' is answered
   by the HTML spec, not by guessing.

   `:src` was missing until 2026-07-25, and the omission was not academic:
   slopp's OWN reviewer UI carried `[:script {:src \"/assets/cljs/main.js\"}]`
   in the shell of every page, served by nothing, 404ing on every request
   since the wave that added it — and `http-dangling-route-refs`, the gate
   built to fail `done` on exactly that, could not see it. A fetched URL is
   as dangling as a clicked one; the browser just fails more quietly."
  {:a #{:href} :link #{:href} :area #{:href} :base #{:href}
   :form #{:action}
   :script #{:src} :img #{:src} :iframe #{:src} :source #{:src}
   :track #{:src} :embed #{:src} :audio #{:src} :video #{:src}})

(defn- hiccup-tag
  "The ELEMENT of a hiccup tag keyword, with hiccup's `#id` / `.class` sugar
   stripped — `:a#main.big` and `:a.nav` are both `:a`. nil for anything that
   isn't a keyword, so a non-element vector answers no element at all."
  [x]
  (when (keyword? x)
    (keyword (first (str/split (name x) #"[#.]")))))

(defn- request-literals
  "The literal ring REQUESTS in one form's sexpr: maps carrying a string `:uri`,
   as `{:method :uri}`. `:request-method` gives the method (`:get` when a test
   omits it, matching ring). A map with a non-literal uri names no particular
   route and is skipped — there is nothing to join it to."
  [sexpr]
  (for [m (filter map? (tree-seq coll? seq sexpr))
        :let [uri (:uri m)]
        :when (and (string? uri) (str/starts-with? uri "/"))]
    {:uri uri
     :method (let [mv (get m :request-method :get)]
               (if (keyword? mv) mv (keyword (str/lower-case (str mv)))))}))

(defn ^:export endpoint-test-refs
  "`{qualified-endpoint-form #{qualified-test-form}}` — which tests exercise
   which declared endpoint, joined through the ROUTER over the literal ring
   requests test forms contain (`{:request-method :get :uri \"/todo/7\"}`).

   The tracer cannot see this edge: a test reaches a handler through
   `web/handle!`'s runtime route scan, so there is no static reference and no
   recorded evidence until the test has run once. Every endpoint write therefore
   reported `:no-covering-tests` while a red test aimed at exactly that route sat
   in the store. The route table IS static and `router/match` is the same matcher
   the server uses, so this join needs no heuristic.

   Erring toward INCLUSION is correct here, and is the opposite of the bar a RULE
   must clear (D-rule-grounding): this feeds test SELECTION, where an extra test
   costs seconds and a missed one costs a false green."
  [store]
  (let [routes (endpoints store)
        owner  (fn [{:keys [method uri]}]
                 (when-let [r (router/match routes method uri)]
                   (when (and (:ns r) (:name r))
                     (symbol (str (:ns r)) (str (:name r))))))]
    (reduce
     (fn [acc [test-sym reqs]]
       (reduce (fn [a req]
                 (if-let [e (owner req)] (update a e (fnil conj #{}) test-sym) a))
               acc reqs))
     {}
     (for [nsx  (sort (keys (:namespaces store)))
           :when (store.render/test-ns? nsx)
           e     (store/forms store nsx)
           :when (:name e)
           :let  [sx (try (n/sexpr (:node e)) (catch Exception _ nil))]
           :when sx]
       [(symbol (str nsx) (str (:name e))) (request-literals sx)]))))

(defn ^:export serving-namespaces
  "Every namespace that must be scanned to serve this store's web surface —
  the derived answer to `serve!`'s `:http/namespaces`, sorted.

  The union of two things the store already knows: the namespaces owning
  endpoint rows (`endpoints`), and the namespaces of the performer vars
  behind the effect/read vocabularies (`performers`). `-test` namespaces are
  excluded on both sides, the same rule `routes-report` applies — a test's
  endpoint-shaped form is a fixture, and serving it would mount a fake
  endpoint on the real app.

  Why derived rather than declared: `:http/namespaces` is the one REQUIRED
  opt on `serve!`, and `web/context`'s own docstring warns that \"a
  `:http/namespaces` list missing half the app assembles happily and
  answers\". A hand-kept list of what to serve IS that defect, held by every
  app that serves. The forgettable entry is a PERFORMER-only namespace: a
  route promising `:http/reads {:user [:user/by-id …]}` whose performer lives
  next door assembles into a context that throws `:http/missing-performers`,
  and the list is the only place that could have been wrong.

  Store-side on purpose. `slopp.http` requires nothing but `slopp.http.*` and
  must stay that way — it is what gets vendored into an app. So this is
  computed HERE and handed to the framework as data: directly by the dev
  server, and baked into the main `build!` emits."
  [store]
  (->> (concat (map :ns (endpoints store))
               (->> [:http/effect :http/read]
                    (mapcat #(vals (performers store %)))
                    (keep namespace)
                    (map symbol)))
       (remove nil?)
       (remove #(str/ends-with? (str %) "-test"))
       distinct
       sort
       vec))

(defn store-reader
  "The LIVE-store reader for `static/mount-routes`: resolve `path` through the
  store's manifest (text) or its content-addressed artifacts (bytes), falling
  back to `get-blob` for a sha the in-memory cache does not hold.

  `get-store` is a THUNK, not a store value, and that is the point of the
  seam: it is re-read per request, so under `--live` an edited asset serves
  without a rebuild. A store value captured once would freeze the tree at
  server start.

  The counterpart adapter is `static/file-or-resource-reader`, and the two
  answer the same port. They have diverged before — a mount prefix written
  `public/` asks for `public//app.css`, which a filesystem normalises away and
  a manifest lookup does not — so both are held to one suite,
  `slopp.http-test/reader-contract`. This existed as an anonymous fn inside
  `start-server!` and was therefore reachable only by booting a session and
  binding a port, which is why it had never been tested at all."
  [get-store get-blob]
  (fn [path]
    (let [{:keys [content content-type sha]} (store/file-content (get-store) path)]
      (when (or content sha)
        {:content (or content (get-blob sha))
         :content-type content-type}))))

(defn ^:export context-builder
  "The qsym of this store's `^{:http/context true}` fn — the zero-arg builder
  of `:http/perform-ctx` — or nil when the app declares none.

  The managed app server WRITES the `serve!` call, so it has to know how to
  build the context a handler receives as `:http/deps` and every performer
  receives as its first argument. That map is app-specific by definition (a
  registry, a pool, a connection), so the app must say — and a marker is how
  everything else in this framework is addressed.

  **A marker rather than a capability naming a qualified symbol.** A marker
  makes a GATE possible: both halves are then visible in the store — handlers
  destructuring `:http/deps`, and whether anything claims to build it — so
  \"this store takes `:http/deps` and declares no builder\" refuses at the WRITE
  rather than 500ing in a browser. That gate is
  `slopp.edit.web/http-undeclared-context`, and it is why this is a marker;
  a capability is a string in config, checkable for resolvability at boot,
  which is later and weaker, and it splits the declaration from the thing
  declared.

  The SCAN lives in `slopp.edit.web/web-context-builders` — shared with
  the gate, which asks whether ANY builder exists where this asks for THE one.
  What is here is the singleton POLICY, and only that.

  **It cannot be a performer**, and the idea is circular rather than merely
  wrong: performers already RECEIVE the perform-ctx as their first argument,
  so the context is strictly upstream of that vocabulary and cannot be a
  member of it. Written down because it is the obvious suggestion.

  **A SINGLETON**, unlike performers, which are keyed by kind because there
  are many. So two declarations is a refusal rather than a pick: choosing
  silently is how an app ends up running on deps it did not mean, and the
  failure would surface as a missing key three layers away."
  [store]
  (let [found (edit.http/web-context-builders store)]
    (when (seq found)
      (when (next found)
        (throw (ex-info (str "a store declares exactly ONE ^{:http/context true}"
                             " builder; this one has " (count found) ": "
                             (str/join ", " found))
                        {:http/context-builders (vec found)})))
      (first found))))

(defn ^:export static-mounts
  "This store's `http.static.*` mounts as `{url-prefix manifest-prefix}`.

  The key's tail is the URL prefix and the value a files-manifest path
  prefix, so `http.static./assets = public` serves `public/logo.png` at
  `/assets/logo.png`. Feeds `slopp.http.static/mount-routes`, which pairs it
  with a reader — [[store-reader]] for a live store, and
  `slopp.http.static/file-or-resource-reader` for the managed dev server,
  whose child image has no store and reads materialized bytes instead.

  **Trailing slashes are trimmed on both sides, and that is not cosmetic.**
  `mount-routes` adds its own separator, so `public/` asks the reader for
  `public//main.js` — which a filesystem quietly normalises and a manifest
  lookup does not, making the same config work or 404 depending on which
  reader is behind it. Pinned by `web-test/static-mounts-serve-raw-bytes`.

  Exists because `cljs/served-by-a-mount?` had parsed this family privately,
  which left the managed server no way to ask the same question without a
  second parser of one config family."
  [store]
  (into {}
        (for [[k v] (get-in store [:config "capabilities" :values])
              :when (re-matches #"http\.static\..+" (str k))]
          [(str/replace (subs (str k) (count "http.static.")) #"/$" "")
           (str/replace (str v) #"/$" "")])))

(defn http-public-mutation-check
  "Done-advisory (D-web): a CHANGED endpoint whose policy is :public and
   which declares `:http/effects` kinds — a publicly-writable surface should
   be a decision someone made, not an omission. Fires per form with the
   declared kinds; inert until the store opts into HTTP (http.enabled).
   v1 reads the DECLARATION; a public endpoint mutating without declaring
   is http-unsafe-get's (GET) or the effects-vocabulary's territory."
  [_session st* changed]
  (when (capabilities/enabled? st* "http")
    (vec (keep (fn [fid]
                 (when-let [e (store/form-by-id st* fid)]
                   (let [m (edit.http/web-name-meta e)]
                     (when (and (edit.http/route-path m)
                                (= :public (:http/auth m))
                                (seq (:http/effects m)))
                       {:form (symbol (str (store/ns-of-form-id st* fid))
                                      (str (:name e)))
                        :http/effects (vec (:http/effects m))}))))
               changed))))

(defn- schema-prose?
  "Does a malli entry's property map carry prose?

  BOTH spellings count. `:doc` is preferred and is what the teach string asks
  for; `:description` is malli's JSON-Schema spelling, and an imported schema
  that documented itself in that dialect must not fail a check for it.

  A `(str …)` form counts as much as a literal. This rule reads SOURCE, so a
  computed doc arrives as a LIST rather than a string — and `(str …)` is not an
  edge case here, it is the shape the teach asks for: unlike a docstring, a
  schema doc is a VALUE, so a multi-line literal ships its own source
  indentation to every consumer that renders it. Anything else non-string stays
  flagged, which is the safe direction for an advisory: a missed field costs a
  nudge, a wrong claim of prose costs the reader's trust in every other row."
  [props]
  (boolean (and (map? props)
                (some (fn [v] (or (string? v)
                                  (and (seq? v) (= 'str (first v)) (seq (rest v)))))
                      [(:doc props) (:description props)]))))

(defn schema-resolver
  "`(fn [from sym] -> [from' schema])` — the schema a symbol NAMES, resolved
  through THE reference graph.

  `from` is the `[ns name]` of the form the symbol was READ from, and the
  answer carries the resolved one, so a nested reference resolves from where
  it is written rather than from where the walk started.

  Resolution has to be real rather than by simple name: all ten of slopp's own
  endpoints name their schema through an ALIAS, and `timeline`, `module-index`,
  `form-view`, `change-view`, `ns-outline` and `module-detail` each exist in
  two or three namespaces of this store — so a name-matching resolver would
  skip most of the population it was written for and report clean.

  nil when the symbol resolves to nothing, to more than one thing, or to a
  form that is not a `def`. Those are the cases where a finding would be a
  guess, and this rule is advisory: a missed field costs a nudge, a wrong one
  costs trust in every other row.

  **ONE producer, two readers, and it was one reader short.** The contract
  advisories resolve through this; `rules.rest/contracts-report` did not, so
  `query_surface` answered `nil` for every contract declared by VAR while the
  rules judged those same contracts correctly. Measured by slopp-ui on a store
  where three of five endpoints declare through a shared var — which is the
  arrangement `rest-inline-schema-dup` actively tells an author to adopt. Not
  private for that reason: a resolver only one of two readers can reach is how
  the two came to disagree."
  [store]
  (let [edges (group-by (juxt :from-ns :from-var) (refs/refs store))]
    (fn [from sym]
      (let [nm   (symbol (name sym))
            hits (distinct (for [r (get edges from) :when (= nm (:to-name r))]
                             [(:to-ns r) (:to-name r)]))]
        (when (= 1 (count hits))
          (let [[tns tnm] (first hits)
                s (store/named-sexpr store tns tnm)]
            (when (and (seq? s) (= 'def (first s)))
              [[tns tnm] (last s)])))))))

(defn- undocumented-paths
  "Every `:map` entry reachable from `schema` whose properties carry no prose,
  as a vector PATH — `[:rows :loc]` for a field one level inside a sequential.

  `seen` holds the schemas already entered, so mutually-referencing schemas
  terminate on the relationship rather than on a depth guess.

  Nesting is followed through ANY vector rather than through an enumerated set
  of collection schemas: `:sequential`, `:maybe`, `:map-of`, `:or` and `:tuple`
  all carry their children positionally, so walking every vector covers the one
  nobody listed. The `(remove map? …)` drops a schema-level property map, which
  is the only non-entry a `:map` can hold."
  [resolve-sym from schema path seen]
  (cond
    (symbol? schema)
    (when-let [[from' s'] (resolve-sym from schema)]
      (when-not (contains? seen [from' s'])
        (undocumented-paths resolve-sym from' s' path (conj seen [from' s']))))

    (and (vector? schema) (= :map (first schema)))
    (mapcat (fn [entry]
              (when (vector? entry)
                (let [props (when (map? (second entry)) (second entry))
                      path' (conj path (first entry))]
                  (concat (when-not (schema-prose? props) [path'])
                          (undocumented-paths resolve-sym from (last entry) path' seen)))))
            (remove map? (rest schema)))

    (vector? schema)
    (mapcat #(undocumented-paths resolve-sym from % path seen) (rest schema))

    :else nil))

(defn undocumented-contract-fields
  "Every field of every declared `:rest/request` / `:rest/response` schema in
  `store` that says nothing about what it IS — `[{:endpoint :schema :fields}]`,
  one row per endpoint per schema key, `:fields` being the entry PATHS.

  A type is not a contract term. `:total :int` does not say that the number
  counts hits BEFORE the limit is applied, and on this store that sentence
  exists — in the schema def's docstring, which is not a value and does not
  travel. Malli property maps are open and survive the wire untouched, so the
  prose belongs on the entry, where a consumer generating a client can read it.

  A pure function of the store value, and whole-store by construction: the
  endpoints this fires on are stable and published, which is exactly the
  population an episode-scoped check can never see."
  [store]
  (let [resolve-sym (schema-resolver store)]
    (vec
     (for [{:keys [ns name meta]} (edit.http/web-endpoint-rows store)
           k     [:rest/request :rest/response]
           :let  [schema (get meta k)
                  from   [ns name]
                  fields (when schema
                           (vec (distinct (undocumented-paths resolve-sym from schema [] #{}))))]
           :when (seq fields)]
       {:endpoint (symbol (str ns) (str name)) :schema k :fields fields}))))

(defn- unconstrained
  "What `schema` DECLARES when it declares nothing — `:any`, `:map`, or nil.

  `:any` accepts anything and says so. A `:map` with no entries accepts any map
  at all, and looks like a type while doing it — which is the whole cost: a
  generated client validates every response against the published schema, so a
  field whose shape can change without failing validation has the mechanism
  that exists to catch drift pointed at it and switched off.

  `[:map {:closed true}]` counts too: the properties are not entries, and a
  closed map with nothing in it constrains no field."
  [schema]
  (cond
    (= :any schema) :any
    (= :map schema) :map
    (and (vector? schema) (= :map (first schema))
         (empty? (remove map? (rest schema))))
    :map
    :else nil))

(defn- unconstrained-paths
  "Every position reachable from `schema` that constrains nothing, as
  `{:path [...] :declares :map|:any}`.

  Same traversal and same resolver as [[undocumented-paths]] — deliberately,
  because the two rules must ask their different questions of the SAME
  population. A field one of them can see and the other cannot would be a gap
  neither reports.

  An empty `:path` means the schema ITSELF is unconstrained: `:rest/response :map`
  publishes an endpoint that promises a map and nothing else."
  [resolve-sym from schema path seen]
  (if-let [k (unconstrained schema)]
    [{:path path :declares k}]
    (cond
      (symbol? schema)
      (when-let [[from' s'] (resolve-sym from schema)]
        (when-not (contains? seen [from' s'])
          (unconstrained-paths resolve-sym from' s' path (conj seen [from' s']))))

      (and (vector? schema) (= :map (first schema)))
      (mapcat (fn [entry]
                (when (vector? entry)
                  (unconstrained-paths resolve-sym from (last entry)
                                       (conj path (first entry)) seen)))
              (remove map? (rest schema)))

      (vector? schema)
      (mapcat #(unconstrained-paths resolve-sym from % path seen) (rest schema))

      :else nil)))

(defn unconstrained-contract-fields
  "Every position in a declared `:rest/request` / `:rest/response` schema that
  constrains NOTHING — `[{:endpoint :schema :fields}]`, each field
  `{:path [...] :declares :map|:any}`.

  The prior question to [[undocumented-contract-fields]]: that one asks whether
  a declared field says what it MEANS, this asks whether it is declared at all.
  A field is not made real by prose — `[:sequential :map]` with a lovely `:doc`
  on it still admits any map, and the generated client still validates every
  response against it and finds nothing.

  Reported from a real drift: a `:diff` that moved from `[String]` to
  `[[String String]]` passed validation on every call for weeks, because the
  schema that would have caught it said `:map`.

  Pure, and whole-store for the same reason its sibling is: a published
  contract is stable, so no episode changes it and nothing episode-scoped can
  ever look at it again."
  [store]
  (let [resolve-sym (schema-resolver store)]
    (vec
     (for [{:keys [ns name meta]} (edit.http/web-endpoint-rows store)
           k     [:rest/request :rest/response]
           :let  [schema (get meta k)
                  fields (when schema
                           (vec (distinct (unconstrained-paths resolve-sym [ns name]
                                                               schema [] #{}))))]
           :when (seq fields)]
       {:endpoint (symbol (str ns) (str name)) :schema k :fields fields}))))

(defn- unique-let-inits
  "`{name init}` for every local in `sexpr` bound EXACTLY ONCE by a
  let-shaped form — the binding a route reference has to be read through when
  the href is a name rather than an expression.

  `(let [to (str \"/p/\" slug)] [:a {:href to} …])` is the ordinary way to
  write a built path, and it loses the literal prefix that the inline
  `{:href (str \"/p/\" slug)}` keeps. Recovering it is the difference between
  reporting *no static pass can resolve a local* — a fact about the analyzer —
  and answering whether the link is dead, which is what the reader asked.

  EXACTLY ONCE is the whole safety argument. A name bound twice in one form
  resolves to nothing here, because resolving it to the wrong initializer
  would manufacture a dangling finding out of working code, and a false
  finding costs every reader of every done while a missed one costs a 404
  somebody was already going to get. Shadowing is rare; being wrong about it
  is not worth the coverage."
  [sexpr]
  (let [binders #{'let 'let* 'when-let 'if-let 'when-some 'if-some 'loop}
        pairs   (for [x (tree-seq coll? seq sexpr)
                      :when (and (seq? x) (binders (first x)) (vector? (second x)))
                      [nm init] (partition 2 (second x))
                      :when (symbol? nm)]
                  [nm init])]
    (into {} (for [[nm ps] (group-by first pairs)
                   :when (= 1 (count ps))]
               [nm (second (first ps))]))))

(defn- link-refs
  "Route references in one form's SEXPR: the URL-bearing attribute of a hiccup
  element that HAS one. Root-relative string values are :exact; (str \"/lit\" …)
  with a root-relative literal first arg is :prefix; other dynamic values are
  :unresolved. Absolute URLs (scheme or //), anchors, and non-root-relative
  strings are not route references at all. :action takes its method from the
  same map's :method attr (default :get); :href is always :get.

  THE TAG DECIDES — see `url-attrs`. Reading instead \"any map in this form with
  an :href key\" was a coincidence test: it made `{:op :add :action :replace}` a
  route reference, 16 of them store-wide, none dischargeable by anyone. The
  attribute map must also sit in hiccup ATTRIBUTE position (second element,
  after the tag), which is what an attr map IS. Grounding in the HTML spec is
  not a tighter heuristic, it is the actual question, so there is no residue
  left to shave. A map assembled elsewhere and passed in by name is missed —
  the right side to err on: a missed ref costs a 404 nobody was told about, a
  false one costs every reader of every done."
  [sexpr]
  (for [inits [(unique-let-inits sexpr)]
        v     (filter vector? (tree-seq coll? seq sexpr))
        :when (map? (second v))
        :let  [m (second v)]
        attr  (url-attrs (hiccup-tag (first v)))
        :when (contains? m attr)
        :let [raw (get m attr)
              ;; a name resolves to what it was bound to, when that is
              ;; unambiguous. The classification below is unchanged and simply
              ;; sees through the binding — `:value` still reports what the
              ;; author WROTE, so an unresolvable ref still names the symbol
              ;; the reader has to go look at.
              val (if (symbol? raw) (get inits raw raw) raw)
              method (if (= :action attr)
                       (let [mv (get m :method "get")]
                         (keyword (str/lower-case (if (keyword? mv) (name mv) (str mv)))))
                       :get)
              ref (cond
                    (string? val)
                    (when (and (str/starts-with? val "/")
                               (not (str/starts-with? val "//")))
                      {:kind :exact :path val})

                    (and (seq? val) (= 'str (first val)) (string? (second val))
                         (str/starts-with? (second val) "/")
                         (not (str/starts-with? (second val) "//")))
                    {:kind :prefix :path (second val)}

                    (nil? val) nil

                    :else {:kind :unresolved :value (pr-str raw)})]
        :when ref]
    (assoc ref :attr attr :method method)))

(defn ui-route-refs
  "Every route REFERENCE the store's forms render: literal :href/:action
  attrs classified :exact / :prefix / :unresolved, each row carrying the
  qualified :form. A pure function of the forms (the keyword-inventory
  property) — correct on every branch, after every merge, at any revision.
  Test namespaces are fixtures.

  **One marker skips a form whole:** `^{:http/external-path \"why\"}` — the target
  is served by something OUTSIDE this store (nginx, another service). A genuine
  crossing, honest about being one.

  **`^:webapp/client-path` is RETIRED, and how it died is worth the paragraph.** It
  meant *the literal is this app's own client-router key, and nothing serves it
  as written* — true, and unfixable at the time for the reason recorded here:
  *teaching the check to SEE the prefixing is not possible in general, because
  the base arrives through an ordinary function call.*

  Both halves of that stopped being true. slopp does the prefixing now
  (`webapp/prefix-links`, over the finished tree), so a literal IS a client route
  key rather than an ambiguous string; and `:webapp/routes` is a declared table,
  so there is something to join it to. `dangling-route-refs` resolves against
  that table, and the marker has nothing left to say.

  What it cost while it lived is the reason to record this rather than delete it
  quietly: in one consuming store the escape sat on THIRTEEN views, each
  discharged with the same accurate sentence. Every instance passed review
  because every instance was true — an escape hatch every user discharges
  identically is one missing mechanism wearing N hats, and the count is the
  signal that nothing counts."
  [store]
  (vec
   (for [nsx (sort (keys (:namespaces store)))
         :when (not (store.render/test-ns? nsx))
         e (store/forms store nsx)
         :when (:name e)
         :let [sx (try (n/sexpr (:node e)) (catch Exception _ nil))
               mt (when (seq? sx) (meta (second sx)))]
         :when (and sx (not (:http/external-path mt)))
         ref (link-refs sx)]
     (assoc ref :form (symbol (str nsx) (str (:name e)))))))

(defn ^:export routes-report
  "The `query_surface` payload. `http.enabled` false → `{:enabled false
  :routes [] :note …}` — a store that never opted into HTTP has no web
  surface and no web rules (the adoption story). Enabled → every endpoint
  row (`endpoints`), each carrying `:rendered-by` (the forms whose
  `ui-route-refs` target it — exact refs through the router's matcher,
  prefix refs through the path pattern) when any do, plus the derived
  performer vocabularies (`:effect-kinds` / `:read-kinds`)."
  [store]
  (if-not (capabilities/effective store "http.enabled")
    {:enabled false :routes []
     :note (str "http.enabled is false — config_file {path \"capabilities\" "
                "key \"http.enabled\" value \"true\"} opts this store into HTTP")}
    (let [refs    (ui-route-refs store)
          renders (fn [row]
                    (->> refs
                         (filter (fn [{:keys [kind method path]}]
                                   (case kind
                                     :exact  (some? (router/match [row] method path))
                                     :prefix (str/starts-with? (str (:path row)) path)
                                     false)))
                         (map :form) distinct sort vec not-empty))]
      {:enabled true
       :routes (mapv #(if-let [r (renders %)] (assoc % :rendered-by r) %)
                     (endpoints store))
       :effect-kinds (set (keys (performers store :http/effect)))
       :read-kinds (set (keys (performers store :http/read)))})))

(defn dangling-route-refs
  "`ui-route-refs` joined against what the store actually serves: declared
  endpoints (through the router's matcher, so parameterized paths match),
  `http.static.*` mounts (an :exact path must map to a file that EXISTS on
  the manifest), and route/mount prefixes for :prefix refs. Returns
  `{:dangling [ref …] :unresolved [ref …]}` — dynamic refs are NAMED, never
  counted clean."
  [store]
  (let [refs   (ui-route-refs store)
        ;; the refs this check could answer for at all — a computed path is
        ;; already :unresolved before anything here looks at it
        literal (remove #(= :unresolved (:kind %)) refs)
        ;; **When the app rewrites its own links, the join below cannot be made.**
        ;; `slopp.webapp/prefix-links` runs at render, so an `:href` literal is in
        ;; APP space while the served table is in SERVER space, and the prefix
        ;; between them is route state — which no static read can supply.
        rewriters (rules.webapp/self-prefixed-links store)
        why     (when (seq rewriters)
                  (str "this store rewrites its own links —"
                       " slopp.webapp/prefix-links is called by "
                       (str/join ", " (map str rewriters))
                       " — so an :href literal is in APP space while the route"
                       " table is in SERVER space, and the prefix between them"
                       " is route state. Write links against the routes your"
                       " app DECLARES and this join resolves with no rewriting"
                       " at all"))
        routes (endpoints store)
        ;; a trailing slash on the value is trimmed, because the join below adds its
        ;; own: `public/` would build `public//app.css`, which no manifest holds,
        ;; and every asset link in the app would read as dangling
        mounts (into {}
                     (keep (fn [[k v]]
                             (when-let [[_ m] (re-matches #"http\.static\.(.+)" (str k))]
                               [m (str/replace (str v) #"/+$" "")])))
                     (get-in store [:config "capabilities" :values]))
        static-file? (fn [path]
                       (some (fn [[url-prefix file-prefix]]
                               (and (str/starts-with? path (str url-prefix "/"))
                                    (some? (store/file-content
                                            store
                                            (str file-prefix "/"
                                                 (subs path (inc (count url-prefix))))))))
                             mounts))
        ;; a document declaring :webapp/client-routes answers for client routes BELOW each
        ;; prefix, so a link to /store/form/f1 is served even though no
        ;; endpoint declares that path. Scoped, exactly as the fallback rows
        ;; are: a path outside every prefix still dangles, which is the half
        ;; of this that keeps the gate worth having.
        client-route-prefixes (into #{} (mapcat :webapp/client-routes) routes)
        client-route? (fn [path]
                        (some #(str/starts-with? path (str % "/")) client-route-prefixes))
        ;; the fourth source of served paths, after endpoints, static mounts and
        ;; the client-route PREFIXES above: the client ROUTE TABLE itself. A
        ;; literal :href in a view is a client route key — slopp prefixes it on
        ;; the way to the DOM — so it resolves against the patterns the app
        ;; declared rather than against anything the server serves.
        ;;
        ;; This is what retires `^:webapp/client-path`, whose whole justification
        ;; was that neither half existed: the prefixing was an app's own function
        ;; call, and the table was a closure.
        ;;
        ;; Matched with the SERVER's matcher on purpose. The two grammars are
        ;; pinned against each other by
        ;; `web.routes-test/the-CLIENT-and-SERVER-matchers-agree-about-the-pattern-grammar`,
        ;; so using one to read the other's patterns is a checked equivalence
        ;; rather than an assumption.
        client-rows   (mapv (fn [{:keys [path]}] {:method :get :path path})
                            (rules.webapp/page-routes store))
        client-routed? (fn [path] (boolean (router/match client-rows :get path)))
        served? (fn [{:keys [kind method path]}]
                  (case kind
                    :exact  (boolean (or (router/match routes method path)
                                         (static-file? path)
                                         (client-route? path)
                                         (client-routed? path)))
                    :prefix (boolean
                             (or (some #(str/starts-with? (str (:path %)) path) routes)
                                 (some (fn [[url-prefix _]]
                                         (str/starts-with? path (str url-prefix "/")))
                                       mounts)
                                 (client-route? path)
                                 ;; the same question the server arm above asks,
                                 ;; of the client table: does some declared
                                 ;; route START WITH this reference? The
                                 ;; DOMINANT link shape in a real browser app is
                                 ;; `(str "/store/form/" id)`, whose literal is a
                                 ;; prefix of the pattern `/store/form/:id` —
                                 ;; neither the pattern nor a whole path. It is
                                 ;; already CLASSIFIED as a prefix reference,
                                 ;; which is why the escape hatch went on those
                                 ;; views; resolving it any other way would
                                 ;; retire the marker for the six links that did
                                 ;; not need it and leave it on the eighteen
                                 ;; that did.
                                 (some #(str/starts-with? (str (:path %)) path)
                                       client-rows)))
                    false))]
    ;; **When the app rewrites its own links, this join cannot be made at all.**
    ;; `slopp.webapp/prefix-links` runs at render, so an `:href` literal is in
    ;; APP space while everything above is in SERVER space, and the prefix
    ;; between them is route state — which no static read can supply.
    ;;
    ;; The unresolved bucket is exactly right and already exists: a ref this
    ;; check cannot answer for, NAMED, `:severity :info`, never counted clean.
    ;; Reporting them as dangling would be false and would spend the
    ;; credibility of every other finding here — measured at 23 in one store,
    ;; all correct at runtime. Dropping them would turn the guarantee off with
    ;; nothing saying so, which is the failure this file keeps meeting.
    ;; The unresolved bucket is exactly right for a rewritten link and already
    ;; exists: a ref this check cannot answer for, NAMED, `:severity :info`,
    ;; never counted clean. Reporting them as dangling would be false — 23 of
    ;; them in one store, every one correct at runtime — and would spend the
    ;; credibility of every other finding here. Dropping them would turn the
    ;; guarantee off with nothing saying so, which is the failure this file
    ;; keeps meeting from every other direction.
    {:dangling   (if why [] (vec (remove served? literal)))
     :unresolved (into (filterv #(= :unresolved (:kind %)) refs)
                       (when why
                         (map #(assoc % :kind :unresolved :why why)
                              (remove served? literal))))}))

(defn http-dangling-route-refs-check
  "Done-advisory (D-web-html): rendered links/forms targeting a path no
   declared route or static mount serves — the UI nil-pun: it ships and
   404s. Fires STORE-WIDE, like dead surface, because deleting a route
   dangles an UNCHANGED form's link. Inert until http.enabled. The
   `^{:http/external-path \\\"why\\\"}` marker on the rendering form discharges.

   Dynamic (`:unresolved`) refs ride along as `:severity :info` findings:
   listed at done, never status-flipping. They used to be omitted entirely
   — the only way to keep them from flipping an `:error` rule red — which
   hid the one part of this check a human has to judge."
  [_session st* _changed]
  (when (capabilities/enabled? st* "http")
    (let [{:keys [dangling unresolved]} (dangling-route-refs st*)]
      (vec (concat dangling
                   (map #(assoc % :severity :info) unresolved))))))

(defn http-unreachable-declaration-check
  "Advisory: a route or performer marker sits on a PRIVATE form somewhere in
  this store — swept, rather than asked only of the form being written.

  **The write gate is the same question at the other grain, and it is the same
  FUNCTION**: this calls `edit.http/http-unreachable-declaration` for every
  form carrying one of the four markers, so the refusal a write produces and
  the finding a sweep produces cannot drift into two opinions.

  Why both grains are needed, in a consuming store's words: the gate *\"refuses
  the next form to declare a route privately and never asks the question of a
  form already in the store\"*. A violation arriving by any path that is not an
  edit-tool write — `import_dir`, a branch merge, an `episode_revert` — passes
  it untouched, and `done` is episode-scoped, so nothing asks again for the
  life of the store.

  They found the asymmetry in slopp's own catalog: `webapp-page-reach` is the
  PAGE version of this question, whose wording the gate reuses, and it was
  already swept. **Pages had both grains and routes had one.**

  Advisory rather than error at this grain, because the write gate is the
  enforcement: a form that arrived by a route with no gate is a state to be
  told about, not one to refuse a commit-point over."
  [_session store _changed]
  (for [nsx (keys (:namespaces store))
        e   (store/forms store nsx)
        :let [nm (:name e)
              m  (edit.http/web-name-meta e)]
        :when (and nm (or (edit.http/route-path m)
                          (:http/read m)
                          (:http/effect m)))
        :let [msg (edit.http/http-unreachable-declaration store nsx nm)]
        :when msg]
    {:form  (symbol (str nsx) (str nm))
     :teach msg}))

(def ^:export compiled-bundle-path
  "Where `compile_client` writes the browser bundle by default, as a
  files-manifest path.

  Here rather than in the compiler because two things need it and neither
  should ask the other: the compiler WRITES it, and [[bundle-url]] derives the
  url a shell injects FROM it. A second copy of this string is how a store
  compiles to one path and serves another."
  "public/cljs/main.js")

(defn ^:export bundle-url
  "The url this store's compiled browser bundle is served at, or nil when no
  static mount reaches it.

  **Derived, because both halves are already declared.** `compile_client`
  writes to [[compiled-bundle-path]] and `http.static.*` says which url prefix
  serves which manifest prefix — so the url a shell injects is a JOIN, not a
  fact anybody should be typing. It used to be typed: `:webapp/shell` held the
  url on every shell route, which made two shells two copies and left a store
  that moved its mount hunting for them.

  nil is a real answer and a caller must treat it as one: a store may compile a
  bundle and serve it from an ENDPOINT rather than a mount, which is what
  slopp's own reviewer UI does. `slopp.http/context` refuses a shell with no
  bundle at assembly, so an app in that position passes `:webapp/bundle`
  itself — the derivation is the default, not the only way."
  [store]
  (some (fn [[url-prefix path-prefix]]
          (when (str/starts-with? compiled-bundle-path (str path-prefix "/"))
            (str url-prefix (subs compiled-bundle-path (count path-prefix)))))
        (static-mounts store)))
