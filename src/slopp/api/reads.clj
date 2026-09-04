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
            [slopp.api.model :as model] [clojure.string :as str] [slopp.edit.modules :as edit.modules] [slopp.edit.tiers :as tiers] [slopp.edit.http :as edit.http] [slopp.rest.paths :as rest.paths] [slopp.http.paths :as http.paths] [slopp.rules.webapp :as rules.webapp] [slopp.project.capabilities :as capabilities] [slopp.rules.http :as rules.http] [slopp.ops :as ops] [slopp.read.orient :as orient] [slopp.read.modules :as read.modules] [slopp.read.history :as history]))

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
  "Read performer: the reviewer landing model — commit-points plus the
  working set."
  [{:keys [session]} _]
  (model/timeline (ops/with-history session :ops [:commit])))

(defn ^{:http/read :ui/change} change-read
  "Read performer: the review of one `from..to` range, or nil when the
  range is malformed or names deltas that do not exist — the page needs
  those to be the same answer, since both are a 404."
  [{:keys [session]} range-str]
  (let [[from to] (str/split (str range-str) #"\.\." 2)]
    (when (and (seq from) (seq to))
      (model/change-view (ops/with-history session) from to))))

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

(defn ^:export webapp-pages-document
  "The pages a project's BROWSER routes to, as data — one row per
  `:webapp/path` form.

  `{:paths [{:path \"/store/form/:id\" :page my.ui/form-page :doc \"…\"}]}`

  **`:calls` is the ENDPOINTS a page reaches, and it is graph-derived.** A row
  used to carry `:request` — the name of a builder var — and `:loads`, a url
  taken from whatever single path that builder named. A page makes as many
  calls as it likes now, by naming descriptors, so the honest answer is what
  the page's form REFERENCES. It cannot disagree with the code, and it is
  absent rather than empty when a page calls nothing.

  **The client-side half of the content surface.** `/api/http/paths` answers
  what the server hands a browser; it cannot answer what the browser then does
  with it, because a client-routed app is ONE server route and a dozen
  addresses. The dozen are what a reader navigating the app actually sees.

  **Derived from var METADATA, like every other surface this store publishes.**
  A page carries its own address, so this reads the same kind of declaration
  `/api/rest/paths` and `/api/http/paths` read. It used to derive from a
  `:webapp/routes` vector inside the entry fn — a VALUE, so a table built in
  pieces was unreadable and the document carried an `:unreadable` key to say
  so. That key is gone with its cause: metadata cannot be half-declared.

  **No version key.** Nothing branched on one; it existed so a consumer could
  refuse rather than misread. API compatibility is a coordination between an
  API and its client, and both halves of this one migrate together — so the
  rule that replaces it is that a document changes by RENAMING a key, never by
  redefining one in place.

  `[]` unless the project has a browser app, which most do not."
  [store]
  (let [calls (rules.webapp/page-calls store)]
    {:paths (mapv (fn [{:keys [path page doc]}]
                    (cond-> {:path path :page page}
                      doc              (assoc :doc (http.paths/undent doc))
                      (get calls page) (assoc :calls (get calls page))))
                  (rules.webapp/page-routes store))}))

(defn ^{:http/read :ui/webapp-paths} webapp-paths-read
  "The pages the project's BROWSER routes to — one row per `:webapp/path`
  form.

  **Purely the paths WITHIN the app.** It used to publish the server-side
  PREFIXES a document declared it owned, which described a mechanism that no
  longer exists: a shell declares its own `**` path, so the fallback is the
  declaration and there is no prefix list to keep.

  **The one read here with no image half.** Its siblings ask the store WHICH
  namespaces and the loaded image WHAT, because schemas and page values live
  only on loaded vars. A page's address is metadata, which the store holds
  directly — so this reads the store and nothing else."
  [ctx _]
  (webapp-pages-document (:store @(:session ctx))))

(defn ^:export config-document
  "How this project is CONFIGURED, as data — one row per setting, narrowed to
  `prefix` when one is given.

  `{:config [{:key \"http.port\" :owner \"http\" :value \"8080\" :set true
              :effective \"8080\" :default \"0\" :doc \"…\"}]}`

  **ONE document, not one per capability.** The three path documents split by
  capability because a project's SURFACE really is owned that way — a
  `:rest/path` belongs to `rest`. Config does not: it is already a single flat
  namespaced keyspace, and `http.port` sits beside `webapp.enabled` in one
  registry. Splitting it would slice something that is not sliced and leave a
  reader assembling one page from three fetches.

  **`prefix` is the filter a page reaches for when it outgrows one screen**,
  and it matches the blocks the keys already have — `http` narrows to
  `http.*`, `http.auth` narrows further. A prefix nothing matches answers
  EMPTY rather than everything: a filter that silently stops applying reads as
  a page with no settings under that block, which is the one answer a typo
  must not produce.

  **Credential VALUES are withheld and the keys are not.**
  `capabilities/secret-families` declares which families those are; a row from
  one publishes `:secret true` and drops `:value` and `:effective`, keeping
  `:key`, `:owner`, `:doc` and `:set`. *This is configured and I am not showing
  you* is a useful answer to a settings page; *nothing here* would be false.

  That redaction is HERE rather than in `capabilities/report`, and the layer is
  the point: `query_capabilities` answers an agent that already holds the store
  and can read the config directly, so redacting there would withhold a value
  from the one reader entitled to it while protecting nothing. This route
  answers a remote consumer over a public address, which is the boundary that
  needs it.

  `:patterns` rides along — the wildcard FAMILIES themselves, which name
  settable spaces rather than settings — and `:orphaned` when the store holds
  keys this build no longer knows, because a stored key under a retired name
  is exactly the diagnosis somebody is looking for and reads as unset
  otherwise."
  [store prefix]
  (let [{:keys [settings patterns owners orphaned]} (capabilities/report store)
        p       (some-> prefix str str/trim not-empty)
        keeps?  (fn [k] (or (nil? p)
                            (= (str k) p)
                            (str/starts-with? (str k) (str p "."))))
        secret? (fn [k] (boolean (some #(str/starts-with? (str k) %)
                                       capabilities/secret-families)))
        row     (fn [r] (if (secret? (:key r))
                          (-> r (dissoc :value :effective) (assoc :secret true))
                          r))
        narrow  (fn [rows] (vec (filter (comp keeps? :key) rows)))]
    (cond-> {:config (mapv row (narrow settings))
             :owners owners}
      ;; the compiled bundle's url, which is NOT a setting — nothing sets it.
      ;; `rules.http/bundle-url` joins the compile output to the static mount
      ;; that serves it, so it belongs BESIDE the config rather than in it.
      ;;
      ;; Its own field rather than inside an `:assembly` map, and the reason is
      ;; the container: a category with one key makes a scope claim it cannot
      ;; keep. A reader shown `{:bundle …}` under `:assembly` has been told
      ;; that assembly IS the bundle, so a second fact arriving later reads as
      ;; *the first was incomplete and nobody said so*. `:bundle` claims only
      ;; about the bundle and is complete the day it ships.
      ;;
      ;; ABSENT when no mount reaches it, which is a real answer rather than a
      ;; gap: a store may serve its bundle from an ENDPOINT instead, which is
      ;; what slopp's own reviewer UI does.
      (rules.http/bundle-url store)
      (assoc :bundle (rules.http/bundle-url store))

      (seq (narrow patterns))  (assoc :patterns (narrow patterns))
      (seq (narrow orphaned))  (assoc :orphaned (mapv row (narrow orphaned))))))

(defn ^{:http/read :ui/config} config-read
  "How the project under review is CONFIGURED — every setting with its owner,
  narrowed by the request's `prefix`.

  **The one read whose FILTER comes off the request.** Its siblings answer a
  whole surface and let the consumer slice it, which is right when the answer
  is dozens of rows. Config is the registry joined with the store and grows
  with every capability, so the page that renders it filters by block — and
  filtering server-side is what keeps a narrowed page one fetch rather than a
  whole document the browser discards most of.

  Reads the store and nothing else: a setting's owner, default and doc are
  declared in the capability registry, and its value is in the store's config.
  Neither needs a loaded var, which is why this has no image half."
  [ctx {:keys [query-params]}]
  (config-document (:store @(:session ctx)) (:prefix query-params)))

(defn- records-text
  "The RECORDS section of a bundle answering a records question (\"why is X
  computed this way — what do the project's own records say\"): for each of
  `forms` (qualified symbols), its versions — op, when, the ask that made
  it, and for an imported form the git sha with the note that no ask was
  recorded and the docstring is the only recorded reasoning. `hsess` is a
  session hydrated by `ops/with-history`. Nil when no form has a story.
  eval26 opus step 2, every cell: four turns of file_list, query_git,
  file_get and git log for exactly this answer."
  [hsess forms]
  (let [snip  (fn [s n] (let [t (str s)] (if (> (count t) n) (str (subs t 0 (- n 3)) "…") t)))
        lines (for [q forms
                    :let [vs (history/query-form-history hsess (symbol (namespace q)) (symbol (name q)))]
                    :when (seq vs)]
                (str "  " q ": "
                     (str/join "; "
                                          (for [v (take-last 4 vs)]
                                            (str (name (:op v)) " " (:at v)
                                                 (when-let [o (:origin v)]
                                                   (str " — imported from git "
                                                        (let [g (str (:git-sha o))] (subs g 0 (min 12 (count g))))
                                                        (when (:note o) (str " (" (:note o) ")"))))
                                                 (when (:prompt v) (str " «" (snip (:prompt v) 140) "»")))))))]
    (when (seq lines)
      (str "--- the records the ask asks about — each form's versions (op, when, the ask that made it); this IS the record, quote it ---\n"
           (str/join "\n" lines)))))

(defn ^{:http/read :orient/bundle} bundle-read!
  "Read performer: the ask bundle — `orient/bundle` over the live session,
  the ask from `?ask=` (blank is the plain ranking). `?session-id=` is the
  calling harness's id: when it MATCHES the session's claimed id, this
  session already has its map in context, so the answer is the small DELTA
  (tighter budget, one source) — injected text rides every later request as
  rent, and a second full map pays it twice. What was emitted is stashed on
  the session as `:pending-bundle-held {:sid :versions}`; absorbing the ask
  the bundle rode in with drains it into the form ledger, so a read of a
  bundle-carried form is a reference rather than a second copy.

  A HANDOFF-SHAPED ask (summarize / what changed / recap / since-last)
  arrives with the handoff already composed under a marked section —
  measured (eval12): that ask shape cost 10-13 turns of history spelunking
  while the composed answer sat one call away. It is `orient/handoff-text`
  over the whole `ops/report` — the asks first, whole rows, fitted to its
  budget — not a pr-str snip: the snip cut BEFORE :by-ask in every s17
  handoff cell and its \"(deeper: …)\" tail taught the drill-down that
  followed (s18). The injection PAYS for itself by shrinking the card budget
  (never appending past it — the s10 bundle-growth regression is the
  standing lesson), and a same-session delta bundle skips it: an
  already-mapped session can ask `report` by name."
  [{:keys [session]} {:keys [query-params]}]
  (let [ask      (str (:ask query-params))
        sid      (not-empty (str (or (:session-id query-params) "")))
        same?    (and sid (or (= sid (:intent-sid @session))
                              ;; …or this request already mapped it: delta
                              ;; mode used to need a TOOL CALL to set the
                              ;; sid, so a prompt-only turn paid for a
                              ;; second full map (s20)
                              (= sid (:bundled-sid @session))))
        handoff? (boolean (re-find #"(?i)(what(?:'s| has| have| was)? +(?:been +)?(?:changed|happened|done)|\bsummar|hand.?off|\bhanding\b|\bhand (?:this|it|the|over)\b|\brundown\b|\brecap\b|catch +(?:me +)?up|since +(?:the +)?last|\bwhat did (?:you|we)\b|\b(?:has|have) changed\b|\bchanged here\b|walk (?:me|us) through the changes|\bhistory of (?:the )?(?:changes|project)\b)"
                                   ask))
        {:keys [text sent]} (orient/bundle session ask
                                           :cli? (= "1" (str (:cli query-params)))
                                           :tokens (cond same? 450 handoff? 500 :else 1100)
                                           :sources (if (or same? handoff?) 1 2)
                                           ;; the delta is a refresher for a reader that
                                           ;; holds the map; versions ride the first map
                                           :versions? (not same?)
                                           ;; the whole namespaces are first-map material too
                                           :whole-ns? (not (or same? handoff?)))
        text     (if (and handoff? (not same?))
                   (str text "\n" (orient/handoff-text (ops/report session :limit 50) 3800))
                   text)
        ;; a RECORDS question — why is it this way, what was asked, what do
        ;; the records say — arrives with the named forms' version story
        ;; (eval26 opus: four turns of git and README per cell for it)
        records? (and (not same?)
                      (re-find #"(?i)\b(why (?:is|was|does|did|are|were)|history|records?|rationale|reasoning|recorded|asked for|what (?:was|were) asked)\b" ask))
        text     (if-let [rec (when records?
                                ;; ranked on the SENTENCES that ask the question, not the
                                ;; whole ask: a why-question beside a feature request
                                ;; ranked the feature's forms (eval27 opus)
                                (let [q-re   #"(?i)\b(why (?:is|was|does|did|are|were)|history|records?|rationale|reasoning|recorded|asked for|what (?:was|were) asked)\b"
                                      sents  (filter #(re-find q-re %) (re-seq #"[^.?!\n]+[.?!]?" ask))
                                      q-text (if (seq sents) (str/join " " sents) ask)
                                      top    (->> (:rows (orient/orient-map session :ask q-text :tokens 400))
                                                  (map :form) (take 3) vec)]
                                  (when (seq top)
                                    (records-text (ops/with-history session) top))))]
                   (str text "\n" rec)
                   text)
        ;; the project's aliases, once: the first map carries them, the delta
        ;; assumes the reader holds them
        text     (if-let [line (when-not same?
                                 (orient/aliases-line (read.modules/project-aliases (:store @session))))]
                   (str text "\n" line)
                   text)
        ;; the schema diet's other half: the op-index prose the advertised
        ;; surface shed rides HERE as compact cards — once per ask, not per
        ;; family per request. Only when the hook says the session is dieted
        ;; (?diet=1) and only on the FULL bundle: the delta bundle's reader
        ;; already holds its cards in context.
        text     (if (and (not same?) (string? (:op-cards @session)))
                   ;; every FULL bundle carries the cards — the argument
                   ;; teaching the dieted surface relocated here (s14,
                   ;; adopted by measurement); the delta bundle's reader
                   ;; already holds them in context
                   (str text
                        "\n--- op cards — the calls you will make ({required, [optional]}; help {topic <op>} for more) ---\n"
                        (:op-cards @session))
                   text)]
    (when (seq sent)
      (swap! session assoc :pending-bundle-held {:sid sid :versions sent}))
    ;; remembered so the NEXT bundle for this sid is the delta, whether or
    ;; not a tool call absorbs the intent in between (s20)
    (when sid
      (swap! session assoc :bundled-sid sid))
    text))
