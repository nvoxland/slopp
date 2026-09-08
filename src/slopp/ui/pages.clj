(ns slopp.ui.pages
  "Every SCREEN this app answers, as `slopp.webapp` pages — the half that ASKS.

  A page is `(fn [page] hiccup)` carrying `:state`, `:params` and the app's own
  keys, marked with `^{:webapp/path \"…\"}`. The build derives the browser's
  route table from those markers and `slopp.cljnx/driver-for` derives the
  headless one, so an address is declared exactly once, on the function that
  answers it.

  **Why this is not in [[slopp.ui.views]], which is where every screen used to
  live.** `slopp.webapp/ask!` starts a fetch, and `views` is declared `:pure`;
  the functional-core gate refuses the call outright. That refusal is correct,
  and it is the whole reason this namespace exists — Move A moved the decision
  *what does this screen load* out of a declared route table and INTO the
  render, which is to say it moved IO into the view layer.

  So the split is by TIER rather than by taste: a `*-main` in `views` answers
  *what does this data look like* and stays pure, testable with a literal map —
  which is what the forty-odd assertions that call one depend on. A page here
  answers *where does that data come from*, and is allowed to ask.

  **The word `pages` is overloaded in this repo and this is the FRAMEWORK's
  sense of it.** slopp's `/api/webapp/paths` calls these pages and keys its rows
  `:page`. The UI's *Pages* section is a different noun entirely — the static
  `:http/path` content a project serves, listed by `/api/http/paths`. Nothing
  in here is about that one."
  (:require [slopp.ui.views :as views]
            [slopp.ui.wire.api :as api]
            [slopp.webapp :as webapp] [clojure.string :as str]))

(defn project-routes
  "This app's route patterns with the tenant segment removed — the addresses a
  link INSIDE the app writes.

  **Derived rather than kept beside, because a second copy is the bug.** Every
  href in [[slopp.ui.views]] is written project-relative — `/store/ns/demo.core`
  — and [[chrome]] prefixes the finished tree with the project the reader is
  actually in. `slopp.webapp/prefix-links` needs the route list to know which
  links are the app's own: it rewrites the ones it ROUTES and leaves every other
  href exactly as written, so an absolute url to somewhere else survives.

  That join is why this exists at all. The alternative — rewriting anything
  starting with `/` — would silently prefix a link to another origin the first
  time one appeared, and nothing would report it.

  **Read off the PAGE rather than off a declared table.** It used to derive
  from `views/client-routes`, which no longer exists: the table is generated
  from the `^{:webapp/path …}` markers now, and a page is handed the app's own
  keys — so `:webapp/routes` is already here, whichever of the two readers
  produced it. That also keeps this working in ClojureScript, where the
  image-scanning reader is not available at all."
  [page]
  (mapv (fn [[pattern target]]
          [(str/replace pattern #"^/p/:slug" "") target])
        (:webapp/routes page)))

(defn chrome
  "The layout around a page's own content.

  **A PAGE calls this; the framework does not.** It used to be
  `:webapp/chrome`, called with `(state inner)` — which worked while every
  screen had exactly one declared `:main` load, because chrome could read that
  load and build the rails from it. Under [[slopp.webapp/ask!]] a load is keyed
  by its REQUEST, there is no `:main`, and `state` alone cannot say which
  endpoint this screen asked for. Two ways out: chrome re-derives the request
  from the subject, or the page hands over the answer it already has. The
  second is one decision in one place; the first is the endpoint written twice
  and free to disagree.

  So a page passes three things it already holds — itself, its `subject`, and
  the `answer` from its own ask — and gets the shell back.

  **`subject` is a PARAMETER now, and that deletes a map.** `screen-subject`
  existed to get from a row's target var back to the keyword this app speaks,
  because a row named a var and `arrive` put it in `:screen`. A page knows what
  it is; passing it removes the arrow, and with it the arrow's own failure — a
  screen added without an entry, answering nil, quietly drawing no lens bar and
  no nav.

  **The session loads are ASKED here**, which is where they were always used.
  They were `:webapp/session-loads` on the entry, declared once and started by
  the framework's boot; that key is deleted. `ask!` is start-if-absent, so the
  first page to render asks and every later one finds it — the same single
  fetch, without an entry-level declaration that only this function read.

  **`inner` is finished hiccup and is never nil**, including for the states a
  page cannot render against: loading and failed arrive here as content and
  chrome decides where they sit. The framework knows WHICH state is true, the
  app knows WHERE it goes, and it is why the nav pane stays up while the main
  pane loads. `not-found` no longer comes through here at all — the framework
  replaces the whole page with it, on its own argument that a page nobody
  routed to has nothing around it to be right about.

  The rails read the answer's STATUS rather than testing its value for nil —
  the distinction that kept this pane from rendering `loading…` forever for an
  endpoint that legitimately answered with nothing."
  [{:keys [state params] :as page} subject {:keys [status value]} inner]
  (let [{:keys [path show] filter-text :filter} state
        ;; The tenant segment comes OFF here and goes back on at the end. Every
        ;; view is written project-relative — `/store/ns/demo.core` — and every
        ;; prefix in `sections` is matched against a project-relative path, so
        ;; stripping once at the top is what keeps twenty-three href sites and
        ;; one section table from each learning about slugs.
        slug   (:slug params)
        base   (when (seq (str slug)) (str "/p/" slug))
        here   (or (when base (webapp/strip-base base (or path "/")))
                   path "/")
        code?  (#{:code :module :ns :source :search} subject)
        api?   (#{:rest-paths :rest-path} subject)
        pages? (#{:http-paths :http-path} subject)
        ;; the Webapp section's rail, on the same terms as the other two —
        ;; fed from the page's own answer, because the document IS this
        ;; section's data on both its screens.
        webapp? (#{:webapp-pages :webapp-page} subject)
        local  (cond
                 ;; the Code section's nav: one index, read on every Code
                 ;; screen and cached ACROSS navigations. `ask!` gives it the
                 ;; four states and a freshness token that the plain key it
                 ;; first lived in — written by a `:cljs` fetch, guarded by
                 ;; `(nil? …)` — did not have.
                 code? (views/module-nav
                        (or (:modules (:value (webapp/ask! page (views/at-project params api/modules) {}))) [])
                        (:ns params) filter-text)
                 ;; from the page's own answer, not from a cache: the document
                 ;; IS this section's data on BOTH its screens, so unlike the
                 ;; module index there is nothing here that survives a
                 ;; navigation and needs keeping. It is nil while loading, and
                 ;; app-shell omits an absent pane rather than rendering an
                 ;; empty one.
                 (and api? (= :ready status) (seq value))
                 (views/endpoint-nav value here)

                 ;; the CONTENT rail, on the same terms as the API one — fed
                 ;; from the page's answer because the document IS this
                 ;; section's data on both its screens. `seq` rather than a nil
                 ;; check: a project that serves no pages gets NO rail, not an
                 ;; empty one, and `app-shell` omits an absent pane.
                 (and pages? (= :ready status) (seq value))
                 (views/page-nav value here)

                 (and webapp? (= :ready status) (seq value))
                 (views/webapp-page-nav value here))
        detail (when (= :ready status)
                 (case subject
                   ;; what you SET while reading a namespace, where the form
                   ;; rail is what you consult. It grows; the listing's display
                   ;; toggles are what is in it today.
                   :form (views/form-rail value)
                   :ns   (views/ns-rail value show)
                   nil))]
    (if (= :projects subject)
      ;; the LANDING is in no SECTION and belongs to no project. `hub-picker`
      ;; is a page in its own right — its own heading, nothing to be inside,
      ;; nothing to switch away from — so it gets no shell. Wrapping it would
      ;; draw a section bar whose every link points into a project the reader
      ;; has not chosen yet.
      inner
      (cond->> (views/app-shell
                (cond-> {:nav/sections (views/marked-sections here)
                         ;; the second level: the PAGES of whichever section
                         ;; this is. Nil for a section that declares none —
                         ;; `app-shell` omits an absent pane, and an empty page
                         ;; bar is a strip of chrome saying nothing.
                         
                         ;; unconditional: a door that appears only on the
                         ;; screens which already know about search is a door
                         ;; inside the room
                         :nav/search   (views/search-box (:q params))
                         ;; the HUB's answer rather than a project's, which is
                         ;; why [[slopp.ui.views/hub-projects]] names an empty
                         ;; base instead of going through `at-project`.
                         ;; **the current project comes from the ADDRESS, not from state.** It
                         ;; was `(:project state)`, written by the browser entry's
                         ;; `:boot` from the `data-base` attribute — so it existed
                         ;; only in a browser, and every headless drive rendered a
                         ;; switcher that marked nothing. The slug is a route
                         ;; capture on every address this app answers, which is the
                         ;; same fact without the DOM read, and removing that read
                         ;; is what lets slopp generate this app's browser entry.
                         :nav/switcher (views/project-switcher
                                        (:value (webapp/ask! page views/hub-projects {}))
                                        slug)
                         ;; nil for a subject with no lenses, which app-shell
                         ;; leaves out entirely rather than rendering empty. The
                         ;; ACTIVE one is read from the address, because `:lens`
                         ;; no longer rides in state under the framework loop.
                         :nav/lens     (views/lens-bar here subject (views/current-lens here subject))}
                  local  (assoc :nav/local local)
                  detail (assoc :nav/detail detail))
                inner)
        ;; and the tenant segment goes back on. Every href is written
        ;; project-relative, so this is the ONE place that knows which project
        ;; they belong to — which is what lets the slug change on a client
        ;; navigation. `prefix-links` rewrites only the links this app ROUTES,
        ;; so an absolute url to another origin survives untouched.
        ;;
        ;; Guarded, because a caller that names no project has nothing to
        ;; prefix WITH — and silently rewriting its links against "" would be
        ;; worse than leaving them.
        base (webapp/prefix-links base (project-routes page))))))

(defn answered
  "`render` over the answer's value — or what the load is doing instead.

  **The framework stopped rendering these and a page has to.** It owned
  `loading` and `failed` for the ONE `:main` load a screen declared, which was
  right while a screen had exactly one; a page asks for as many as it likes, so
  only the page knows which it is waiting on. What that leaves behind is a trap
  in both directions, because every `*-main` in [[slopp.ui.views]] renders nil
  as its own EMPTY STATE — so a page that just passes `(:value answer)` through
  renders both a pending fetch and a failed one as *this project has nothing*:
  the wrong sentence, indistinguishable from the right one, on every screen at
  once.

  Three states, and only `:ready` reaches the view:

  - `:ready` — render, including when the value is legitimately empty. That is
    the case each `*-main` wrote its own sentence for, and it is the one this
    must not steal.
  - `:failed` — say so, with what came back. There is no shape of the data that
    means this.
  - anything else — in flight. `ask!` answers `:absent` for the render that
    starts a load and `:loading` until it lands, and both are the same fact to
    a reader.

  Worth knowing: `slopp.webapp`'s own `:webapp/failed` default still reads
  `(:error (:main (:loads state)))`, and there is no `:main` load under `ask!` —
  so the framework's fallback renders an empty `<p>` for every failure. That is
  reported, not worked around; nothing here uses it."
  [{:keys [status error]} render]
  (case status
    :ready  (render)
    :failed [:main {:class "load-failed"}
             [:h1 "Could not load"]
             [:p (str error)]]
    [:main {:class "load-pending"} [:p [:small "Loading…"]]]))

(defn ^{:webapp/path "/" :unused-ok "the route table RESOLVES this at runtime — cljnx/marked-pages scans loaded vars for :webapp/path, and the build reads the same marker — so no form names it and none should"} hub-picker-page
  "The landing screen at `/` — the list of projects the hub fronts.

  **`/` is a client route.** It was a server-rendered page: `hub/picker` read
  the registry and answered HTML, because the app was mounted per project and
  the landing sat outside every mount. With one shell at the root the landing
  is an ordinary page, fed by the hub's own `/api/projects`.

  The one page that does NOT go through [[slopp.ui.views/at-project]]: there is
  no project in the address to measure against, and the endpoint is the hub's."
  [page]
  (let [answer (webapp/ask! page views/hub-projects {})]
    (chrome page :projects answer
            (answered answer #(views/hub-picker (:value answer))))))

(defn ^{:webapp/path "/p/:slug" :unused-ok "runtime-resolved entry — see hub-picker-page"} timeline-page
  "The Review screen — a project's recent work, at its root address.

  The shape every page here follows: ask for one endpoint measured against the
  project in the address, hand the answer to [[chrome]] so the rails can read
  its status, and render the `*-main` that knows what the data looks like. The
  `*-main` stays in [[slopp.ui.views]] and stays `:pure`; the ask is why this
  half is not."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/timeline) {})]
    (chrome page :timeline answer
            (answered answer #(views/timeline-main (:value answer))))))

(defn ^{:webapp/path "/p/:slug/store" :unused-ok "runtime-resolved entry — see hub-picker-page"} code-page
  "The Code index — the module diagram and the store's shape.

  **The diagram is laid out HERE rather than in the view.** It was a route
  row's `:derive`, applied by the framework inside the freshness guard so an
  answer nobody was waiting on was never laid out. `ask!` takes no `:derive`,
  so the page applies it — which does mean laying out per render rather than
  per answer. See [[slopp.ui.views/with-store-picture]], including what the
  split already cost.

  `(:lens state)` is a TEST seam: the lens pages are routes of their own and
  nothing in the app writes `:lens`, but
  `every-declared-lens-actually-renders-something-different` renders this page
  under each lens to prove the dedicated page and the table agree."
  [{:keys [params state] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/modules) {})]
    (chrome page :code answer
            (answered answer #(views/code-index-main
                               (some-> (:value answer) views/with-store-picture)
                               (:lens state))))))

(defn ^{:webapp/path "/p/:slug/store/table" :unused-ok "runtime-resolved entry — see hub-picker-page"} code-table-page
  "The Code index at `/store/table` — the same data as rows.

  A lens BINDING, and a different kind of form from [[code-page]]: that one
  takes the lens from state, this one supplies the lens its ADDRESS names. The
  literal `\"table\"` is also in [[slopp.ui.views/lenses]] and in this var's
  `:webapp/path`; `every-declared-lens-actually-renders-something-different`
  is what keeps the spellings from drifting apart."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/modules) {})]
    (chrome page :code answer
            (answered answer #(views/code-index-main
                               (some-> (:value answer) views/with-store-picture)
                               "table")))))

(defn ^{:webapp/path "/p/:slug/store/gaps" :unused-ok "runtime-resolved entry — see hub-picker-page"} code-gaps-page
  "The Code index at `/store/gaps` — the same diagram, tinted by what is
  missing. A lens BINDING; see [[code-table-page]]."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/modules) {})]
    (chrome page :code answer
            (answered answer #(views/code-index-main
                               (some-> (:value answer) views/with-store-picture)
                               "gaps")))))

(defn ^{:webapp/path "/p/:slug/store/search" :unused-ok "runtime-resolved entry — see hub-picker-page"} search-page
  "The search results.

  The query comes from the ADDRESS rather than from the response: the url is
  what the reader asked, the response is what came back, and the state this
  ships in is the one where nothing came back at all.

  `:q` defaults to `\"\"` rather than being omitted — the request schema makes it
  optional and the endpoint answers 200 with `:total 0` either way, so sending
  the blank is the same fact stated out loud. `:limit` is sent EXPLICITLY, at
  slopp's declared default of 50, rather than left to the server: the screen's
  *showing 20 of 340* sentence is exactly what a silently-applied ceiling
  falsifies, so the number on screen should be one this side chose."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/search)
                            {:q (or (:q params) "") :limit 50})]
    (chrome page :search answer
            (answered answer #(views/search-main (:value answer) (:q params))))))

(defn ^{:webapp/path "/p/:slug/rest/paths" :unused-ok "runtime-resolved entry — see hub-picker-page"} endpoints-page
  "The API index — every `:rest/path` endpoint the project publishes.

  **The ENVELOPE is stripped here.** It was a row's `:derive :paths`, so no
  screen knew the document's version key or its rows key — and that envelope
  has already moved once: `/api/contracts` published
  `{:slopp/contract-version 2 :endpoints […]}` and `/api/rest/paths` publishes
  `{:slopp/rest-paths-version 1 :paths […]}` with byte-identical rows. A screen
  that reads the wrapper gets edited for a change that never reached it. `ask!`
  takes no `:derive`, so the page does it — one `(:paths …)`, still nowhere
  near `endpoints-main`.

  [[chrome]] gets the derived rows rather than the document, which is what lets
  the API rail read `seq` on the answer's value directly."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/rest-paths) {})
        rows   (update answer :value :paths)]
    (chrome page :rest-paths rows
            (answered rows #(views/endpoints-main (:value rows))))))

(defn ^{:webapp/path "/p/:slug/rest/paths/:method/**" :unused-ok "runtime-resolved entry — see hub-picker-page"} endpoint-page
  "One endpoint, and the form for calling it ad hoc.

  Three inputs from three places: the document from the ask, the endpoint's
  ADDRESS from the route, and `:call` — the call form's state, which the
  framework clears with the address because its fields are that endpoint's
  parameters.

  **`:*` is TRANSLATED at one seam.** slopp's wildcards are anonymous, so this
  route's remainder arrives under `:*` — but `endpoint-params`,
  `endpoint-address` and `at-address?` all speak `:path` to each other, and an
  address built from a document row has no router in it to name a capture. So
  the framework's spelling stops at [[slopp.ui.views/routed-address]], which is
  also what [[slopp.ui.app/try-request]] uses, so the page and the request
  cannot select different endpoints."
  [{:keys [params state] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/rest-paths) {})
        rows   (update answer :value :paths)]
    (chrome page :rest-path rows
            (answered rows #(views/endpoint-main (:value rows)
                                                 (views/routed-address params)
                                                 (:call state))))))

(defn ^{:webapp/path "/p/:slug/http/paths" :unused-ok "runtime-resolved entry — see hub-picker-page"} content-index-page
  "The **Pages** section index — the static content a project serves.

  The CONTENT half of the api/content partition: a `:http/path` form is a
  stored VALUE the dispatcher dereferences, where a `:rest/path` is an endpoint
  it calls. `/api/http/paths` publishes them.

  **Named `content-*` rather than `pages-*` on purpose.** In this namespace
  `page` is the framework's word for a screen function, so `pages-page` would
  read as two different nouns in one name. The SUBJECT keyword stays `:pages`,
  because that is what the section is called on screen and what
  [[chrome]] and the lens tables already speak."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/http-paths) {})
        rows   (update answer :value :paths)]
    (chrome page :http-paths rows
            (answered rows #(views/pages-main (:value rows))))))

(defn ^{:webapp/path "/p/:slug/http/paths/:ns/:name" :unused-ok "runtime-resolved entry — see hub-picker-page"} content-page
  "One static page's detail — what it serves and what its value is.

  **Addressed by VAR, not by path.** `hub/shell` is served at `/`, so under a
  `/pages/**` address its own address would be `/pages/` with an empty
  remainder — unaddressable. A content form always has a public var, so `:ns`
  plus `:name` is total and unambiguous.

  `select-keys` rather than the whole params map: the address is `:ns` and
  `:name` and nothing else, and handing a view captures it does not read is how
  a screen ends up depending on one it was never given."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/http-paths) {})
        rows   (update answer :value :paths)]
    (chrome page :http-path rows
            (answered rows #(views/page-main (:value rows)
                                             (select-keys params [:ns :name]))))))

(defn ^{:webapp/path "/p/:slug/change/:range" :unused-ok "runtime-resolved entry — see hub-picker-page"} change-page
  "One range of change — what moved, and whether it went red on the way."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/change)
                            (select-keys params [:range]))]
    (chrome page :change answer
            (answered answer #(views/change-main (:value answer))))))

(defn ^{:webapp/path "/p/:slug/store/ns/:ns" :unused-ok "runtime-resolved entry — see hub-picker-page"} ns-page
  "One namespace's outline — its forms, in source order.

  `:show` is the doc-expansion state, which is SESSION-scoped and survives
  navigation deliberately: it is what you SET while reading, not part of the
  address. It is absent from `:webapp/address-keys` for that reason."
  [{:keys [params state] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/ns-outline)
                            (select-keys params [:ns]))]
    (chrome page :ns answer
            (answered answer #(views/ns-outline-main (:value answer) (:show state))))))

(defn ^{:webapp/path "/p/:slug/store/module/:module" :unused-ok "runtime-resolved entry — see hub-picker-page"} module-page
  "One module — its internal diagram, its boundary, what it depends on.

  **The one page that DECIDES rather than unpacks, and Move A is what makes it
  honest.** When `/api/module/:m` has not answered, the screen is assembled
  from the module INDEX instead — the ordinary path is the module endpoint, and
  this is resilience for when it could not be reached. Under the old model that
  fallback read `(:modules state)`, a session load somebody else had declared
  and started; the page depended on a fetch it did not ask for and could not
  see. Here it asks for both, and `ask!` being start-if-absent means the index
  is the same single load [[chrome]] is already using for the rail.

  This is the case that answers slopp's question about asking from inside a
  render: it is the only page in this app that wanted two loads, and under a
  declared one-request-per-row it had to reach around the load machinery to get
  the second."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/module)
                            {:m (:module params)})
        index  (webapp/ask! page (views/at-project params api/modules) {})]
    (chrome page :module answer
            (answered answer
                      #(views/module-main
                        (or (some-> (:value answer) views/with-module-picture)
                            (views/module-from-index
                             {:modules (or (:modules (:value index)) [])}
                             (:module params))))))))

(defn ^{:webapp/path "/p/:slug/store/form/:id" :unused-ok "runtime-resolved entry — see hub-picker-page"} form-page
  "One form — what it is, what it calls, and its source behind a lens.

  `:depth 2` is this app's choice rather than the endpoint's default: the page
  draws the form's immediate neighbourhood, and one hop would not fill it.
  `(:lens state)` is the test seam [[code-page]] describes."
  [{:keys [params state] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/form)
                            {:id (:id params) :depth 2})]
    (chrome page :form answer
            (answered answer #(views/form-main (:value answer) (:lens state) nil)))))

(defn ^{:webapp/path "/p/:slug/store/form/:id/through/:through" :unused-ok "runtime-resolved entry — see hub-picker-page"} form-through-page
  "One form with a second form beside it — the same page as [[form-page]], seen
  through another rung of the call path.

  **A separate var because `^{:webapp/path …}` names ONE address.** These two
  were a single screen at two rows: `:through` is a rendering concern rather
  than a different call, so both rows pointed at one function and shared its
  request. `slopp.cljnx/marked-pages` reads a marker only when it is a string,
  so a page cannot claim two addresses and the second address needs a second
  var. The request and the render stay identical — what differs is the one
  capture, which is the whole content of the distinction."
  [{:keys [params state] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/form)
                            {:id (:id params) :depth 2})]
    (chrome page :form answer
            (answered answer #(views/form-main (:value answer) (:lens state)
                                               (when-let [t (:through params)] #{t}))))))

(defn ^{:webapp/path "/p/:slug/store/form/:id/source" :unused-ok "runtime-resolved entry — see hub-picker-page"} form-source-page
  "One form's page at `/store/form/:id/source` — the source lens.

  A lens BINDING; see [[code-table-page]]. No `:through` set, and that is the
  address rather than an omission: the lens hangs off the bare form path only —
  `form-main` builds it as `(with-lens (str \"/store/form/\" id) \"source\")` — so
  there is no declared address carrying both a lens and a second form."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/form)
                            {:id (:id params) :depth 2})]
    (chrome page :form answer
            (answered answer #(views/form-main (:value answer) "source" nil)))))

(defn ^{:webapp/path "/p/:slug/store/source/:ns/:name" :unused-ok "runtime-resolved entry — see hub-picker-page"} source-page
  "One form's source, addressed by namespace and name.

  The address a HANDLER link points at — an endpoint's page links to the form
  that serves it, and what it knows is the qualified name rather than a store's
  internal form id."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/source)
                            (select-keys params [:ns :name]))]
    (chrome page :source answer
            (answered answer #(views/source-main (:value answer))))))

(defn ^{:webapp/path "/p/:slug/webapp/pages"
        :unused-ok "runtime-resolved entry — see hub-picker-page"}
  webapp-index-page
  "The **Webapp** section's index — every screen a project's browser app
  declares.

  The third of the three path documents, and the only one that describes the
  project's own BROWSER app rather than what it serves to a caller. A row is a
  page: the route pattern it answers, the var that answers it, its docstring,
  and `:calls` — the endpoints its form references, derived from the reference
  graph.

  **Named `Pages` because that is slopp's word**, not this app's. A screen is a
  `:page` on the row, declared with `^{:webapp/path …}` and read by
  `marked-pages`. The HTTP section's listing is `Paths` for the matching
  reason: those forms declare `:http/path`. Two sections, two nouns, each
  taking the one its own document uses."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/webapp-paths) {})
        rows   (update answer :value :paths)]
    (chrome page :webapp-pages rows
            (answered rows #(views/webapp-pages-main (:value rows))))))

(defn ^{:webapp/path "/p/:slug/webapp/pages/:ns/:name"
        :unused-ok "runtime-resolved entry — see hub-picker-page"}
  webapp-page
  "One webapp page in full — what it answers, what answers it, and what it
  fetches.

  **Addressed by VAR, and here the reason is sharper than it is for content.**
  A webapp page's own address is a route PATTERN — `/p/:slug/store/form/:id`,
  colons and `**` included — so carrying it as data inside this app's address
  would hand this app's router a pattern to match. The var has no such
  characters and is unique by construction: a write gate refuses two pages
  claiming one address.

  `select-keys` rather than the whole params map: the address is `:ns` and
  `:name` and nothing else, and handing a view captures it does not read is how
  a screen ends up depending on one it was never given."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/webapp-paths) {})
        rows   (update answer :value :paths)]
    (chrome page :webapp-page rows
            (answered rows #(views/webapp-page-main (:value rows)
                                                    (select-keys params [:ns :name]))))))

(defn ^{:webapp/path "/p/:slug/config"
        :unused-ok "runtime-resolved entry — see hub-picker-page"}
  config-page
  "The **Config** section — every setting this project has, whoever owns it.

  The one section whose landing IS its only page.
  `every-section-page-is-inside-its-section` already allows that: it asks that
  the landing be one of the pages, not that there be more than one.

  **The whole document goes to the view, not its rows.** Every other page here
  does `(update answer :value :paths)` and hands over a vector, because those
  documents are a list with a wrapper. This one is not: `:owners` is the
  vocabulary the owner column comes from, `:patterns` are settable spaces
  rather than settings, `:bundle` is derived from the compile output joined to
  the static mount, and `:orphaned` is a migration instruction. Passing rows
  would mean passing the other four separately, and then they could be
  mismatched.

  **No `:prefix` param.** The descriptor takes one — `?prefix=http` narrows to
  a block — and this page deliberately asks for everything, because the reason
  it is one page rather than four is that a reader wants every owner in one
  answer."
  [{:keys [params] :as page}]
  (let [answer (webapp/ask! page (views/at-project params api/config) {})]
    (chrome page :config answer
            (answered answer #(views/config-main (:value answer))))))

(defn ^{:webapp/path "/p/:slug/dashboard"}
  dashboard-page
  "The **Dashboard** section — this project's shape, its growth, its cadence,
  its effort and its cost.

  **The page here that asks FOUR times**, and two of those are the same
  endpoint under different splits. Every other screen unpacks a single
  document; this one joins the namespace index, the timeline and both halves of
  the cost journal, because the question it answers is in none of them alone.
  [[module-page]] is the precedent for more than one ask.

  `chrome` takes ONE answer and there are four, so it gets the namespaces load:
  it is the one that decides whether the page has anything to say at all. Any
  other document that has not arrived leaves its own panel empty and the rest
  standing — the honest degradation, where the reverse would blank the whole
  screen over a lesser half.

  **`by=ask` and `by=commit-point` are different questions of one endpoint.**
  The same `:model` key is a NAME under `by=model` and the whole spend BLOCK
  under `by=ask`; the token panel wants the block per ask, because an ask is
  the unit a reader recognises. The store-size series and the wall-clock split
  only exist under `by=commit-point`.

  **The canned fixture answers by PATH**, so under [[slopp.ui.page/page]] both
  cost asks resolve to one document and whichever panel it does not match
  renders its empty state. That is a property of the fixture rather than of the
  app, it is asserted where it shows, and it is the reason the populated cases
  are driven straight through [[slopp.ui.views/dashboard-main]] instead."
  [{:keys [params] :as page}]
  (let [shape    (webapp/ask! page (views/at-project params api/namespaces) {})
        timeline (webapp/ask! page (views/at-project params api/timeline) {})
        cost     (webapp/ask! page (views/at-project params api/cost) {:by "ask"})
        effort   (webapp/ask! page (views/at-project params api/cost) {:by "commit-point"})]
    (chrome page :dashboard shape
            (answered shape #(views/dashboard-main {:namespaces (:value shape)
                                                    :timeline   (:value timeline)
                                                    :cost       (:value cost)
                                                    :effort     (:value effort)})))))
