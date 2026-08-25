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
  (:require [rewrite-clj.parser :as p]
            [slopp.store :as store]
            [slopp.project.capabilities :as capabilities] [clojure.string :as str] [slopp.edit.http :as edit.http] [slopp.index.analyze :as analyze] [slopp.store.render :as store.render] [slopp.edit :as edit]))

(defn webapp-client-routes-consequences-check
  "Done-advisory: an endpoint gained `:webapp/client-routes` this episode — state what that
   changed, once. Inert until the store opts into `webapp`.

   Declaring a client-routed prefix is the single biggest behavioural change
   available in one piece of metadata, and nothing said so. Before: a bad deep
   link under the prefix was a 404, resolved and refused by the server. After:
   the server serves the document (it cannot know the path is bad), the client
   fetches, gets its own 404, and renders a not-found screen. **The HTTP status
   for every path under that prefix changed from 404 to 200.**

   That is correct — it is what `:webapp/client-routes` is FOR — but it is a real semantic
   change that only surfaced here because two existing tests happened to assert
   the old status.

   Fires only for the episode that ADDED the declaration, like
   `shell-widening`: it asks once, while the reason is still in context, and
   cannot decay into a standing warning to scroll past. It teaches rather than
   checks, and the boundary inventory still reports `:webapp/client-routing` as an
   UNCHECKED exit — nothing compares the client's route table to the server's,
   and a teach is not a check.

   **It gated on NOTHING until wave 4**, which is the defect slopp-ui found in
   `rest`'s four contract advisories one capability over: a check with no
   capability test runs on every store while the arms report claims its owner
   controls it. Declaring a client-routed prefix is meaningless without the
   capability that makes the browser own routing, so the gate is `webapp`."
  [_session st* changed]
  (when (capabilities/enabled? st* "webapp")
    (let [ds       (store/deltas st*)
          baseline (->> ds (filter #(= :done (:op %))) last :id)
          old-srcs (when baseline (store/sources-at st* baseline))
          declares-client-routes?     (fn [form] (when (and (seq? form) (symbol? (second form)))
                                (:webapp/client-routes (meta (second form)))))]
      (vec (for [fid changed
                 :let [e (store/form-by-id st* fid)]
                 :when (and e (:name e))
                 :let [new (store/form-sexpr (:node e))
                       old (some-> (get old-srcs fid) p/parse-string store/form-sexpr)
                       ps  (declares-client-routes? new)]
                 ;; only when the declaration is NEW: either the form is new, or
                 ;; its previous version did not carry one
                 :when (and ps (not (declares-client-routes? old)))]
             {:form  (symbol (str (store/ns-of-form-id st* fid)) (str (:name e)))
              :teach (str "every path under " (pr-str ps) " now answers 200, not 404 —"
                          " the server serves this document for any path below the"
                          " prefix and NOT-FOUND moves into the client. Make sure the"
                          " client renders a not-found screen for a path its own"
                          " router does not know, or a bad deep link shows a blank"
                          " pane at a URL that looks valid. The prefix ROOT is not"
                          " covered by the fallback and still needs its own route")})))))

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

(defn ^{:export "slopp.rules"} client-routes
  "Every client route PATTERN this store declares, sorted — `[]` when it has no
  browser app.

  **The value that retires an escape hatch.** `rules.http/ui-route-refs` skips
  any form marked `^:webapp/client-path`, and its docstring says exactly why:
  *teaching the check to SEE the prefixing is not possible in general, because
  the base arrives through an ordinary function call.* That stopped being true
  when the framework took over the prefixing — a literal `:href` in a view is now
  a CLIENT ROUTE KEY, and this is the table it is a key into.

  In one consuming store that escape was on thirteen views, every one discharged
  with the same sentence. Thirteen copies of one accurate justification is one
  missing mechanism wearing thirteen hats; this is the mechanism.

  **Read from `:webapp/routes` literals anywhere in the store**, rather than from
  the `^:app/entry` entry alone. A big app builds its table in pieces and
  concatenates them, and a reader that insisted on one literal in one place would
  report a partial table as the whole one — the shape that makes a join silently
  incomplete.

  **A row whose pattern is not a literal string is SKIPPED rather than guessed
  at.** A computed pattern is one this cannot read; inventing an answer would
  make the join quietly partial, which is worse than a link reported as dangling,
  because a dangling report at least gets looked at.

  `[]` and never nil, so a caller joining against it does not have to tell \"no
  webapp\" apart from \"a webapp that routes nothing\"."
  [st]
  (vec (sort (distinct
              (for [nsx  (keys (:namespaces st))
                    e    (store/forms st nsx)
                    :let [sx (try (store/form-sexpr (:node e)) (catch Exception _ nil))]
                    node (tree-seq coll? seq sx)
                    :when (map? node)
                    row  (get node :webapp/routes)
                    :when (and (vector? row) (string? (first row)))]
                (first row))))))

(defn ^{:export "slopp.rules"} client-routes-unserved
  "The client routes this store declares that its own document does NOT serve on
  a hard load, sorted — `[]` when every one is covered.

  **The join `:webapp/client-routing` records as its blind spot**, in the
  inventory's own words: *nothing compares the client's route table to the
  server's.* The failure is not hypothetical — the one real app hit it with
  eight routes at once. Every in-app click kept working, because that is client
  routing; only a refresh or a shared link 404'd, so the app looked fine to
  whoever was already in it and broken to whoever was sent a url.

  **Two ways a client route is served, and the check has to know both.**

  1. **The generated catch-all.** A declared prefix becomes
     `<prefix>/*client-path`, so anything STRICTLY BELOW the prefix is answered.
     The comparison is on the prefix's TAIL, because a prefix is in SERVER space
     (`/p/:slug/store`) and a client route is in APP space (`/store/form/:id`);
     what is decidable is whether some suffix of the prefix is a leading segment
     of the route.
  2. **An EXPLICIT server route.** The catch-all needs at least one segment
     below the prefix, so the prefix ROOT is not covered by it and needs a route
     of its own — which the advisory's escape text has always said and this
     check did not look for. That cost three false positives on the first real
     store, against the app's own socket test proving all three answer. **A
     remedy the check cannot see is a remedy that produces findings for taking
     it**, which is worse than not offering it.

  The client ROOT is the document's own url, so a document at `/p/:slug` serves
  the client route `/` by existing.

  **Both reads of `:http/path` here are CONTENT reads, not oversights.** A
  client route is served by a document on a hard load — the mount, and any
  explicit route covering a prefix root — and a `:rest/path` api can serve
  neither. So this asks the content marker rather than `route-path`, unlike the
  request-path join next door, which asks both because a screen may legitimately
  fetch an asset.

  Advisory rather than a refusal, for the reason a store mid-migration always
  gets: the state this fires on is a table and a declaration that have not been
  reconciled, and refusing the writes would block the reconciliation."
  [st]
  (let [segs     (fn [s] (vec (remove str/blank? (str/split (str s) #"/"))))
        marked   (for [nsx (keys (:namespaces st))
                       e   (store/forms st nsx)
                       :when (:name e)
                       :let [m (store/form-name-meta e)]]
                   m)
        prefixes (mapcat :webapp/client-routes marked)
        ;; every path the server declares outright
        served   (set (map str (keep :http/path marked)))
        ;; the mount is the document's own path, and the document is the form
        ;; carrying the prefixes — the one unambiguous way to name it
        mounts   (for [m marked
                       :when (and (seq (:webapp/client-routes m)) (:http/path m))]
                   (str (:http/path m)))
        explicit? (fn [route]
                    (boolean (some #(or (contains? served (str % route))
                                        (and (= "/" route) (contains? served %)))
                                   mounts)))
        ;; the mount point is unknown from a prefix alone, so every split of one
        ;; is a candidate for where app space begins
        tails    (for [pfx  prefixes
                       :let [ps (segs pfx)]
                       n    (range (count ps))]
                   (vec (drop n ps)))
        below?   (fn [route]
                   (let [rs (segs route)]
                     (boolean
                      (some (fn [tail]
                              ;; STRICTLY below: the catch-all needs at least one
                              ;; segment under the prefix, so an exact match is
                              ;; the root case and wants arm 2
                              (and (< (count tail) (count rs))
                                   (= tail (vec (take (count tail) rs)))))
                            tails))))]
    (vec (sort (remove #(or (below? %) (explicit? %)) (client-routes st))))))

(defn ^{:export "slopp.rules"} derived-client-route-prefixes
  "The `:webapp/client-routes` prefixes this store's client table IMPLIES, sorted —
  `[]` when no form declares any.

  **The mount point turned out not to be a deployment fact.** A prefix is in
  SERVER space (`/p/:slug/store`), a client route is in APP space
  (`/store/form/:id`), and where the app is mounted looked like a property of how
  it is served. It is not — **the document's own `:http/path` IS the mount
  point**, declared beside the prefixes an author keeps by hand:

      the document's :http/path  +  each top-level segment of the client table

  **`:http/path` DELIBERATELY, not `route-path`.** The mount is a DOCUMENT —
  the SPA shell a hard load lands on — so it is content by kind, and a
  `:rest/path` api must never be picked as one. Before the api/content split
  that read as the only available marker; it is a statement now, and the
  partition makes it enforceable rather than hopeful.

  **The document is the form carrying `:webapp/client-routes`**, and identifying it
  any other way is wrong in a store that separates its forms. The first cut took
  the alphabetically-first endpoint path, which was the same form in slopp's own
  fixtures and `/` in the first real store — so every derived prefix came back
  with a doubled slash. It is NOT the `^:app/entry` entry either: that marker
  means *an entry `screen` can open*, and a store may mark a headless entry that
  no route serves, which the first real store does.

  **One prefix per top-level SEGMENT, not one per route.** `/store` and
  `/store/form/:id` are one prefix, because the generated catch-all under
  `/store` answers both; listing them separately would be three declarations
  where one serves.

  **The app root contributes nothing.** `/` would generate `//*client-path`,
  which is not a path — and the document already answers its own url.

  **Reported, never enforced.** An app may legitimately serve only some of its
  client routes as deep links: a section reachable only from inside the app is a
  real design. Rewriting the declaration would take that choice away. What an
  author should not do is arrive at a gap by FORGETTING, so slopp computes the
  answer and leaves declining it to them."
  [st]
  (let [segs (fn [s] (vec (remove str/blank? (str/split (str s) #"/"))))
        doc  (first (sort (for [nsx (keys (:namespaces st))
                                e   (store/forms st nsx)
                                :when (:name e)
                                :let [m (store/form-name-meta e)]
                                :when (and (seq (:webapp/client-routes m))
                                           (:http/path m))]
                            (str (:http/path m)))))
        tops (distinct (keep (comp first segs) (client-routes st)))]
    (if doc
      (vec (sort (map #(str doc "/" %) tops)))
      [])))

(defn webapp-client-routes-are-served-check
  "Done-advisory: client routes this store declares that its own document does
  not serve on a hard load. Inert until the store opts into `webapp`.

  **The blind spot `:webapp/client-routing` has carried since it was
  registered**, in the inventory's own words: *nothing compares the client's
  route table to the server's.* Both halves are readable now — the client table
  is data and the prefixes are metadata — so the comparison exists.

  The failure it reports is the one the only real webapp hit, with eight routes
  at once: every in-app CLICK keeps working, because that is client routing, and
  only a refresh or a shared link 404s. So the app is fine for whoever is already
  inside it and broken for whoever was sent a url — which is the population that
  never reports bugs, because they assume the link was bad.

  **Whole-store, not episode-scoped**, unlike its `webapp-page-reach` neighbour:
  the two declarations that drift apart are usually not edited together, and the
  episode that breaks the join is the one that touches only ONE of them.

  Advisory rather than a refusal, for the reason a store mid-migration always
  gets: the state this fires on is a table and a declaration that have not been
  reconciled yet, and refusing the writes would block the reconciliation."
  [_session st* _changed]
  (when (capabilities/enabled? st* "webapp")
    (let [want (derived-client-route-prefixes st*)]
      (vec (for [route (client-routes-unserved st*)]
             {:route route
              ;; the finding carries the ANSWER, not just the complaint: the
              ;; prefixes are computable from the document's own :http/path plus
              ;; the client table, so there is no reason to make an author work
              ;; out what to paste
              :declare want
              :teach (str "the client route " (pr-str route) " is not served on a"
                          " hard load — clicking to it works, refreshing it or"
                          " opening a shared link 404s, so the app is fine for"
                          " whoever is already inside it and broken for whoever"
                          " was sent a url."
                          (when (seq want)
                            (str " Your client table and this document's own"
                                 " path imply :webapp/client-routes "
                                 (pr-str want) "."))
                          " Note the prefix ROOT is not covered by the fallback:"
                          " [\"/store\"] generates /store/*client-path, which"
                          " needs at least one segment below it, so a route AT"
                          " the prefix needs its own server route.")})))))

(defn ^:export request-paths
  "Every `:webapp/path` literal this store declares, as
  `[{:path :form} …]` sorted — `[]` when nothing requests anything.

  **The other half of a route reference.** A literal `:href` in a view is
  joined against the served table by `http-dangling-route-refs`; a screen's
  request names a path in exactly the same way, and until this existed nothing
  read it. Both are a claim that this store serves something.

  **Read from map literals anywhere in the store**, like [[client-routes]] and
  for the same reason: a request is an ordinary function, so its map can be
  built in a helper, a `cond`, or beside the screen it belongs to, and a reader
  that insisted on one shape in one place would report a partial answer as a
  whole one.

  A non-literal path is SKIPPED rather than guessed at — a computed path is one
  this cannot read, and inventing an answer would make the join quietly partial,
  which is worse than a path reported as unserved because that at least gets
  looked at."
  [st]
  (vec (sort-by (juxt :path (comp str :form))
                (distinct
                 (for [nsx  (keys (:namespaces st))
                       e    (store/forms st nsx)
                       ;; `^{:http/external-path "why"}` skips a form WHOLE, the
                       ;; same marker `rules.http/ui-route-refs` honours for a
                       ;; link and for the same question. It is the escape the
                       ;; absolute-url one cannot cover: an API proxied under
                       ;; this app's own mount point is real, served, and not
                       ;; this store's — and cannot be written in full when the
                       ;; prefix is only known at runtime. Without it that app
                       ;; carries a finding per screen that nothing can clear,
                       ;; which is how a reader learns to skim the whole list
                       :when (not (:http/external-path (store/form-name-meta e)))
                       :let [sx (try (store/form-sexpr (:node e)) (catch Exception _ nil))]
                       node (tree-seq coll? seq sx)
                       :when (map? node)
                       :let [p (get node :webapp/path)]
                       :when (string? p)]
                   (cond-> {:path p
                            :form (symbol (str nsx) (str (:name e)))}
                     ;; CARRIED rather than filtered here: a request measured
                     ;; from a base it names is still what a screen LOADS, so
                     ;; the surface report wants it. Only the served join skips
                     ;; it, because that base is not this store's to answer for.
                     ;;
                     ;; `:webapp/from-origin` used to be carried beside this as
                     ;; the empty case. It is GONE rather than honoured: the
                     ;; boolean was an escape from a field that could not hold
                     ;; two values, and a retired marker read by nothing is
                     ;; worse than its absence — it waives nothing while reading
                     ;; as though it does.
                     (contains? node :webapp/base) (assoc :own-base true)))))))

(defn ^:export webapp-report
  "The `webapp` section of `query_surface`: what this browser application IS.

  The fifth thing a capability is — PORT, ADAPTER, FAKE, GATES, SURFACE REPORT —
  and the only one `webapp` had never had. Its readers are the three the catalog
  names: the agent, a consuming tool, and the HUMAN, who does not read the code
  and needs a rendered picture of the application.

  Four questions, which between them are what a browser app is:

  - `:screens` — every declared ADDRESS, the function that renders it, and what
    it LOADS. This is the map somebody draws, so the load is the url rather than
    the name of the function that computes one: a var answers WHICH function,
    and the reader of this report does not read the code.

    Addresses rather than screens, and the distinction is load-bearing here
    because this is what a count would be taken from: a row's screen is not
    unique and a screen's row is not unique, so an app with a lens bar or a
    print view has more rows than screens and neither number is wrong.
  - `:session-loads` — what this app fetches that belongs to NO screen: a nav
    rail, a signed-in user, anything started at page load and readable
    everywhere. Absent from `:screens` by definition, so a report drawing only
    screens shows an app fetching less than it does.
  - `:actions` — what a reader can DO, and which kind each is. The `:effectful?`
    ones reach a server and the `:leaves?` ones hand the page back to the
    browser, so a human asking what a control does needs the kind visible rather
    than inferred from the name.
  - `:cljs` — how many namespaces are outside the fast loop.

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
  it could read, and a route row that is not a `[pattern screen]` pair is
  skipped rather than throwing over the rest."
  [store]
  (if-not (capabilities/enabled? store "webapp")
    {:screens [] :actions [] :session-loads [] :cljs 0}
    (let [rows     (for [nsx  (keys (:namespaces store))
                         e    (store/forms store nsx)
                         :let [sx (try (store/form-sexpr (:node e)) (catch Exception _ nil))]
                         node (tree-seq coll? seq sx)
                         :when (map? node)]
                     [nsx node])
          ;; an unqualified screen name is resolvable only against the namespace
          ;; that DECLARED the table, and a row nobody can look up answers half
          ;; the question. A symbol written with an alias is left as written:
          ;; resolving one means reading the ns form, and `views/thing` is
          ;; already findable by a reader
          qualify  (fn [nsx s]
                     (if (and (symbol? s) (nil? (namespace s)))
                       (symbol (str nsx) (str s))
                       s))
          ;; **An app may name a VAR where a literal would go** —
          ;; `{:webapp/routes routes}` — and every extractor below seq's what it
          ;; finds, so a symbol threw `Don't know how to create ISeq from` and
          ;; took the whole of `query_surface` with it, including `:cli`,
          ;; `:http` and `:rest`, which have nothing to do with any of this.
          ;;
          ;; That is this function's own docstring failing in this function: no
          ;; member may take the report down. Reported by the store that could
          ;; no longer read the number `D-webapp` names as the target — so the
          ;; throw hid the metric the wave is scored by, in the tool that
          ;; reports it.
          ;;
          ;; Skipped rather than guessed at, like a computed route pattern: an
          ;; unreadable declaration costs its own rows and the readable ones in
          ;; the same store still land
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
          ;; Reported by the store that would have asked this tool "does the
          ;; surface agree with what I declared?", which is the question it is
          ;; for. It said yes about routes and quietly no about the rest.
          unreadable
          (vec (concat
                ;; a whole declaration written as something other than a literal
                (for [[nsx node] rows
                      ;; `vector?` and not `sequential?`, which is the whole of a false
                      ;; positive worth remembering: a CALL FORM is a list, so
                      ;; `(mapv f (:webapp/routes views/client-routes))` passed
                      ;; `sequential?` and the reader walked the call as if it
                      ;; were the table — reporting its three elements (`mapv`,
                      ;; the fn, the argument) as three unreadable ROWS, on a
                      ;; store whose fourteen routes were all present. A
                      ;; threaded argument would have made it four. A literal
                      ;; table is a vector; anything else is a computation
                      [k pred] [[:webapp/routes vector?]
                                [:webapp/actions map?]
                                [:webapp/session-loads map?]]
                      :when (and (contains? node k) (not (pred (get node k))))]
                  ;; NOT "so none of it is in this answer", which this cannot know:
                  ;; these readers scan every map literal in the store, so the
                  ;; same rows may be declared literally somewhere else and
                  ;; reported from there. The app that found the row bug has
                  ;; exactly that shape — a computed table here, the literal one
                  ;; it maps over in the views — so the over-claim would have
                  ;; been a second false statement beside the first
                  (str nsx " declares " k " as " (shown (get node k))
                       " — only a literal is read here, so nothing was taken"
                       " from THIS declaration"))
                ;; one entry inside a declaration that IS readable
                (for [[nsx node] rows
                      [k spec] (declared node :webapp/session-loads map?)
                      :when (not (and (keyword? k) (map? spec)))]
                  (str nsx " declares the session load " (pr-str k) " as "
                       (shown spec) " — only a literal map can be read"))
                (for [[nsx node] rows
                      row (declared node :webapp/routes vector?)
                      :when (not (and (vector? row) (= 2 (count row))
                                      (string? (first row))))]
                  (str nsx " declares a route row as " (shown row)
                       " — a readable row is [\"/pattern\" screen]"))
                (for [[nsx node] rows
                      [a _decl] (declared node :webapp/actions map?)
                      :when (not (keyword? a))]
                  (str nsx " declares the action " (shown a)
                       " — an action is named by a keyword"))))
          ;; request VAR → the path it names, so a row can say what it loads as
          ;; a url rather than as the name of the function that computes one.
          ;; `first` because a request that names two paths is answering a
          ;; question this report does not ask; the finding grain for that is
          ;; `request-paths-unserved`, which lists every one
          loads    (into {} (for [[form rows] (group-by :form (request-paths store))]
                              [form (:path (first rows))]))
          screens  (vec (sort-by :path
                                 (for [[nsx node] rows
                                       row  (declared node :webapp/routes vector?)
                                       :when (and (vector? row) (= 2 (count row))
                                                  (string? (first row)))
                                       ;; a row's target is a bare render fn or a
                                       ;; screen VALUE naming its own request.
                                       ;; Reporting the map verbatim would answer
                                       ;; neither question a reader has — what
                                       ;; draws this, and what does it load
                                       :let [target  (second row)
                                             screen  (if (map? target) (:render target) target)
                                             request (when (map? target) (:request target))]]
                                   (let [rq (when request (qualify nsx request))]
                                     (cond-> {:kind :screen :path (first row)}
                                       ;; absent when the map has no :render, which
                                       ;; `wiring` refuses — but dropping the row
                                       ;; would hide an ADDRESS this app declares,
                                       ;; and the address is the half a server route
                                       ;; has to answer for
                                       screen (assoc :screen (qualify nsx screen))
                                       rq     (assoc :request rq)
                                       ;; and what it LOADS, as the url rather than
                                       ;; as the var that computes it. A var name
                                       ;; answers WHICH function; the reader of this
                                       ;; report does not read the code, and their
                                       ;; question is which endpoint. Absent when a
                                       ;; request builds its path rather than naming
                                       ;; one — the same limit [[request-paths]]
                                       ;; states, in the same safe direction
                                       (get loads rq) (assoc :loads (get loads rq)))))))
          actions  (vec (sort-by :action
                                 (for [[_nsx node] rows
                                       [a decl] (declared node :webapp/actions map?)
                                       :when (keyword? a)]
                                   (cond-> {:kind :action :action a}
                                     (:effectful? decl) (assoc :effectful? true)
                                     (:leaves? decl)    (assoc :leaves? true)))))
          ;; the fetches that belong to NO screen — a nav rail, a signed-in
          ;; user — started at page load and readable from every screen. A
          ;; report drawing only screens would show an app fetching less than
          ;; it does, and these are the requests a reader never navigates to
          sessions (vec (sort-by :load
                                 (for [[nsx node] rows
                                       [k spec] (declared node :webapp/session-loads map?)
                                       :when (and (keyword? k) (map? spec))
                                       :let [rq (when-let [r (:request spec)]
                                                  (qualify nsx r))]]
                                   (cond-> {:kind :session-load :load k}
                                     rq             (assoc :request rq)
                                     (get loads rq) (assoc :loads (get loads rq))))))]
      {:screens       screens
       :actions       actions
       :session-loads sessions
       :unreadable    unreadable
       :cljs          (count (filter #(= :cljs (store/platform-for store %))
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
    (vec (remove (fn [{:keys [path own-base]}]
                   (or (contains? served path)
                       (str/includes? path "://")
                       ;; the same statement an absolute url makes, in the form
                       ;; a MOUNTED app can actually write. A client-routed app
                       ;; switches which upstream it reads without a page load,
                       ;; so the base is route state on the request; one that
                       ;; NAMES a base is measured from there, which is not this
                       ;; store or the declaration says nothing
                       own-base))
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
