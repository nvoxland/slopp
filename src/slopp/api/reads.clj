(ns slopp.api.reads
  "Read-only store browser: server-rendered hiccup pages over the query
  surfaces — the D-web-html dogfood. Plain links, full-page renders, zero
  writes; rendering arbitrary store source through the escaper is a
  standing security exercise.

  Lives in slopp.api.**, slopp's OWN webapp, and never in slopp.http.** —
  slopp.http is the framework every user's app is built on and ships in the
  slim jar, so an app page placed there would ride into every user's
  application. The dependency runs slopp.api → slopp.http, never back.

  Pages hold hiccup and nothing else; the data they render is assembled by
  slopp.api.model, which is where a static JSON sink would attach."
  (:require [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.api.model :as model] [clojure.string :as str] [slopp.edit.modules :as edit.modules] [slopp.edit.tiers :as tiers] [slopp.edit.http :as edit.http] [slopp.rest.paths :as rest.paths] [slopp.http.paths :as http.paths] [slopp.webapp.paths :as webapp.paths]))

(defn ^{:http/read :browse/namespaces} namespaces-read
  "Read performer: `{:ns sym :forms n}` rows for every namespace, sorted."
  [{:keys [session]} _]
  (let [st (:store @session)]
    (mapv (fn [nsx] {:ns nsx :forms (count (filter :name (store/forms st nsx)))})
          (sort (keys (:namespaces st))))))

(defn ^{:http/read :browse/form-source} form-source-read
  "Read performer: one form's source text AND its store id, or nil when the
  form is unknown.

  The id rides along because this performer already resolved the element to
  get the text — it was in hand and discarded. It is what lets a consumer
  holding only the NAME (which is what the published contract carries, as
  `:handler`) reach the id-addressed form page, without a store identity ever
  entering the portable document."
  [{:keys [session]} {:keys [ns name]}]
  (let [st (:store @session)]
    (when-let [e (store/form-named st (symbol (str ns)) (symbol (str name)))]
      {:form-id (:id e) :source (n/string (:node e))})))

(defn ^{:http/read :ui/timeline} timeline-read
  "Read performer: the reviewer landing model — milestones plus the
  working set."
  [{:keys [session]} _]
  (model/timeline session))

(defn ^{:http/read :ui/change} change-read
  "Read performer: the review of one `from..to` range, or nil when the
  range is malformed or names deltas that do not exist — the page needs
  those to be the same answer, since both are a 404."
  [{:keys [session]} range-str]
  (let [[from to] (str/split (str range-str) #"\.\." 2)]
    (when (and (seq from) (seq to))
      (model/change-view session from to))))

(defn ^{:http/read :ui/form} form-view-read
  "Read performer: one form's page model by ID, at the requested rendering
  FIDELITY and call-graph DEPTH. Addressed by BOTH halves of the URL — the id
  from the path, `?view=` and `?depth=` from the query — so it is declared
  over the whole request rather than one segment.

  nil when no form has that id, or when the fidelity does not exist. Both are
  a 404, and neither is a reason to render the other thing.

  An unreadable or absent `?depth=` is 1, NOT a 404, and the asymmetry with
  `?view=` is deliberate: an unknown fidelity has no right answer, so serving
  a different notation would be lying; depth has an obviously correct floor,
  and 1 is exactly what every link written before the parameter existed
  meant. The model clamps the ceiling."
  [{:keys [session]} {:keys [path-params query-params]}]
  (model/form-view session (str (:id path-params)) (:view query-params)
                   (or (parse-long (str (:depth query-params))) 1)))

(defn ^{:http/read :browse/modules} modules-read
  "Read performer: the architecture as module rows plus a drawable canvas.

  Named `:browse/modules` to sit beside `:browse/namespaces` — reads are
  addressed by VOCABULARY rather than by var, so any second representation
  of the architecture shares this one answer instead of re-deriving it."
  [{:keys [session]} _]
  (model/module-index session))

(defn ^:export app-namespaces
  "The namespaces of `store` that DECLARE endpoints — this application's own
  API surface, whoever happens to be serving it.

  **Serving and declaring are different questions**, and the reviewer listener
  is where they come apart. It runs slopp's own API namespaces, so a contract
  built from what it serves describes the MCP SERVER rather than the project
  being reviewed. That is true on every store: the endpoints screen was 100%
  infrastructure and 0% application, and it read as correct because on slopp's
  own store the two answers coincide — `slopp.api.endpoints` is a form in
  slopp's store and framework everywhere else. A feature that works on exactly
  the store it was developed on.

  It also removes a symptom rather than papering it: every handler named by a
  document built from this list IS a form in the store the document describes,
  so the reviewer UI's `read its source` link resolves by construction. It used
  to 404 on every endpoint of every project, and the honest reading of that 404
  was that those endpoints were never the application's.

  Built on `web-endpoint-rows`, the single route traversal the write gates also
  use, so what is documented is what was enforced — and TEST namespaces are
  excluded there, which is right here too: a fixture endpoint is not surface."
  [store]
  (vec (distinct (map :ns (edit.http/web-endpoint-rows store)))))

(defn- form-doc
  "A form's docstring, or nil — through `store/form-docstring`, which is the
  only thing that knows when index 2 is a docstring and when it is a `def`'s
  VALUE.

  It read index 2 directly and took any string it found, so
  `(def greeting \"hello\")` rendered \"hello\" as the form's documentation.
  Wrong-index reads do not throw; they return something plausible, which is
  why this class keeps surviving review."
  [e]
  (store/form-docstring (:node e)))

(defn- form-shape
  "What a SOURCE-shaped listing needs beyond name and doc: the kind of form,
  the arg vector of each arity, whether it is private, and any declared schema.

  `:kind` is the head symbol as WRITTEN (`defn` / `defn-` / `def` / `deftest`
  / `ns`), because a pane laid out like source states things as fact, and a
  value drawn as though it were callable is a false one.

  `:sig` comes from `modules/fn-arglists`, which is TOTAL — nil for a form that
  defines no fn — so there is no head check here. There used to be one, and the
  docstring beside it claimed the CALLEE knew a `def` has no arities. It did
  not; this guard did. A later reader believed the claim, called `fn-arglists`
  unguarded, and shipped `:sig`'s fourth producer with the same wrong-index
  read the other three had. The knowledge now lives in the one function that
  has to have it, which is why the guard is gone rather than merely correct.

  That read is the same one `form-doc` above had to be rescued from: index 2 is
  a docstring in a `defn` and a VALUE in a `def`, and neither mistake throws.
  They return something plausible, which is why the class kept surviving
  review.

  nil rather than `[]` for a missing signature, since `[]` is a real
  zero-arity. `:private?` is always a boolean: absent and public would render
  identically, and only one of those is a finding."
  [e]
  (let [s    (store/form-sexpr (:node e))
        head (when (seq? s) (first s))
        kind (str head)
        nm   (when (seq? s) (second s))
        args (edit.modules/fn-arglists s)]
    {:kind     kind
     :sig      (when (seq args) (mapv pr-str args))
     :private? (boolean (or (= "defn-" kind) (:private (meta nm))))
     :schema   (some-> (:malli/schema (meta nm)) pr-str)}))

(defn ^{:http/read :browse/ns-outline} ns-outline-read
  "Read performer: one namespace's form rows in store order — name, doc,
  shape, and the facts a consumer needs to rank them — plus the test
  namespaces covering it, or nil for an unknown namespace.

  `outline-metrics` is called ONCE for the namespace and looked up per row.
  The reverse reference index it reads is a whole-store grouping; asking it
  per form would be quadratic in a namespace's size, which is exactly the
  shape `refs-by-target` was introduced to retire."
  [{:keys [session]} nsx]
  (let [st  (:store @session)
        sym (symbol (str nsx))]
    (when (contains? (:namespaces st) sym)
      (let [metrics (model/outline-metrics st sym)]
        {:ns sym
         :forms (into []
                      (keep (fn [e]
                              (when (:name e)
                                (merge (form-shape e)
                                       (get metrics (str (:name e)))
                                       {:name    (:name e)
                                        :doc     (form-doc e)
                                        ;; the ADDRESS, so a row can link to
                                        ;; the form page rather than to source
                                        :form-id (:id e)}))))
                      (store/forms st sym))
         :tested-by (model/tests-covering st sym)
         ;; NAMESPACE grain, deliberately not a row field. A row's
         ;; `:effectful?` says what THAT form does; the tier says what this
         ;; namespace is ALLOWED to do, and the two disagree constantly — a
         ;; namespace with permission to do IO is mostly pure functions.
         ;; Repeated per row it would state one fact N times and read as a
         ;; form fact, which is the mistake it exists to prevent.
         ;; Always present: undeclared resolves to :external, so there is no
         ;; "nobody said" for an absent key to mean.
         :tier (name (tiers/tier-for st sym))
;; NAMESPACE grain like :tier, and for the same reason: the module
         ;; rollup answers "which box is thin", this answers "and where in it".
         ;; A consumer holding only the rollup would have to sum the rows it is
         ;; already rendering, which is the arithmetic this exists to save.
         :gaps (get (model/gaps-by-ns st (:test-map @session)) sym)}))))

(defn ^{:http/read :browse/module} module-detail-read
  "Read performer: one module from the inside — its production namespaces,
  the ns→ns edges among them, the layering, and the boundary crossings; nil
  for a module with no production namespaces.

  `:browse/module` beside `:browse/modules`, singular against plural, because
  they are the same subject at two grains and a reader following one to the
  other should not have to learn a second vocabulary."
  [{:keys [session]} m]
  (model/module-detail session m))

(defn ^{:http/read :browse/search} search-read
  "Read performer: everything matching `?q=`, ranked across modules,
  namespaces and forms, cut to `?limit=`.

  Declared over the WHOLE request rather than one segment, like `:ui/form`:
  both parameters travel in the query string, and neither is optional to the
  performer even though both are optional to the caller — a missing `q` is the
  empty state and a missing `limit` is the declared default, and the model
  answers each rather than the route.

  `:browse/search` sits beside `:browse/module` and `:browse/modules`: it is
  the same subject matter reached by asking rather than by descending, and a
  reader following one to the other should not have to learn a second
  vocabulary."
  [{:keys [session]} {:keys [query-params]}]
  ;; an unreadable limit falls back to the declared default rather than
  ;; refusing: a row budget has an obvious right answer, the same stance
  ;; `?depth=banana` takes one endpoint over
  (let [limit (when (re-matches #"\d+" (str (:limit query-params)))
                (parse-long (str (:limit query-params))))]
    (model/search (:store @session) (:q query-params) limit)))

(defn ^{:http/read :ui/rest-paths} rest-paths-read
  "The typed API surface of the project under review, for a consumer that
  generates a client against it.

  **The namespaces come from the STORE, not from what this listener serves.**
  The reviewer listener runs slopp's own API, so serving is the wrong source:
  it would publish the MCP server's surface as though the project had written
  it, on every project, and would look right only on slopp's own store.

  The CONTENT still comes from var metadata, and that split is forced rather
  than chosen. A schema is evaluated at def time, so the store holds the
  SYMBOL and only the loaded var holds the value; a document derived outright
  from the store would publish schema NAMES a consumer cannot validate
  against. The store says WHICH, the image says WHAT."
  [ctx _]
  (rest.paths/paths-document (app-namespaces (:store @(:session ctx)))))

(defn ^{:http/read :ui/http-paths} http-paths-read
  "The CONTENT surface of the project under review — every `:http/path` form,
  described rather than carried.

  Same store-says-WHICH, image-says-WHAT split as [[rest-paths-read]], and
  here the image half is load-bearing for a second reason: a page's shape,
  size and effective media type are properties of the def's VALUE, which only
  a loaded var has. A document built from the store alone could name the pages
  and say nothing true about any of them.

  EMPTY is the ordinary answer. Most projects serve no content of their own —
  slopp's is one — so a consumer renders `[]` far more often than a row."
  [ctx _]
  (http.paths/paths-document (app-namespaces (:store @(:session ctx)))))

(defn ^{:http/read :ui/webapp-paths} webapp-paths-read
  "The paths the project's BROWSER owns — one row per declared
  `:webapp/client-routes` prefix.

  Same split as its two siblings. A form here also appears in
  [[http-paths-read]]'s document: it is a served page AND the owner of paths
  the server has no route for. The two answer different questions and neither
  is a subset of the other, which is why there is no union document."
  [ctx _]
  (webapp.paths/paths-document (app-namespaces (:store @(:session ctx)))))
