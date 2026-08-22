(ns slopp.cljnx
  "A rendered screen as structured text — hiccup in, readable lines out, so a
  UI can be reviewed and asserted on with no browser anywhere.

  **The other half already existed.** A `:cljc` wiring map with its effects
  passed in routes, fetches, derives and renders on a JVM today; driving an app
  headless was never the missing piece. What came out the far end was hiccup
  nobody could read, and that was the whole gap — which is why the review kept
  going back to screenshots for bugs that were plain wrong sentences.

  The obvious substitute is a flatten — every string in tree order, joined by
  spaces. Measured against a real screen it came out LONGER than the readout
  and carried less: class names and region names bleed into the prose, headings
  run into the paragraphs after them, and no address survives at all.

  **Two audiences, one output.** Ad hoc, [[of]] is what you read instead of
  opening a browser. In a test, [[lines]] is what you assert on, and it
  replaces the `(->> (tree-seq coll? seq v) (filter string?) (str/join \" \"))`
  helper that tends to get copied into every `deftest` that needs it — none of
  which separate a heading from the paragraph after it.

  **Depends on nothing but `clojure.string`** — no view, no app, no store. That
  is what let it move into the framework unchanged from the app it was built
  in, and it is what lets any project whose views are data use it the same way.
  The only structure it asks of an app is `:data-region` on its panes, which is
  markup an app already writes to address them.

  It sits beside `slopp.web.html` on purpose, and the pairing is where the name
  comes from: one renders hiccup for a BROWSER, this renders it for a READER.

  **Deliberately not `browser`**, which was the first instinct and is the one
  word this must not take. There is a real browser in this story — the review
  loop it replaces drove Chrome through Playwright — so \"did the browser check
  pass\" has to keep exactly one meaning. `browser` would also promise
  navigation and interaction that live nowhere near here: the navigating is the
  app's `:cljc` wiring, and the driving is the tool's.

  **`:clj`, not `:cljc`, deliberately.** A `:cljc` namespace is compiled into
  the client bundle, and a reading aid has no business in a user's shipped JS.

  **Not a screenshot, and it must not be sold as one.** Wrapping and contrast
  need pixels; see [[of]]."
  (:require [clojure.string :as str] [slopp.cljnx.hiccup :as hiccup] [slopp.cljnx.render :as cljnx.render]))

(defn lines
  "A rendered screen as a VECTOR of text lines — the checking face of [[of]],
  which joins these with newlines for reading.

  Two faces because there are two audiences and one of them was measurably
  badly served by a single string. A readout makes assertions easy to write and
  therefore easy to write TOO BROADLY: a real tint check matched its class
  pattern anywhere in the page, so it claimed the diagram, checked a list, and
  stayed green with the layout torn out. A whole-page `str/includes?` is one
  keystroke from asserting nothing in particular.

  Addressable lines make the NARROW assertion the easy one:

  ```clj
  (->> (lines view) (filter #(str/starts-with? (str/triml %) \"<svg\")) first)
  (lines view {:region \"main\"})
  (lines view {:within \"rate\"})   ; one row, addressed like a click
  ```

  Options — and an option outside this table REFUSES, because this was the
  last entry that guessed: a typo'd `:detial` used to silently answer
  STRUCTURED, and most prose assertions pass there too, so the test stayed
  green while checking something its author did not choose (reported from
  real use, the day the option vocabulary changed):

  | key | default | reach for it when |
  |---|---|---|
  | `:detail` | `:structured` | `:prose` — sentences only, for \"does it say X\" |
  | `:list-head` | `nil` — EVERY row | the `screen` tool passes `3`; a cap emits `<slopp:elided count=\"N\"/>`, machine-visible |
  | `:region` | none | scoping to one `:data-region` pane; THROWS if it is not there |
  | `:within` | none | scoping to ONE ELEMENT, a level below regions — addressed exactly as a click addresses (visible text, `:href`, `:aria-label`), resolved to the element that OWNS it (same bubbling), same refusals. One vocabulary for one document; asking about a row no longer means regexing its whole pane |

  `:within` composes with `:region` (the pane is cut first, so the element
  must be IN it) and can address anything on the screen — a disabled control
  refuses a PRESS, not a look; the act-gates are the click's alone.

  The `:list-head` default is the TEST path's: a test asserting a row that
  elision ate would fail false, and a false failure costs more than the tokens
  the cap saves. The tool is the look path, and it caps because a reader skims.

  `:region` cuts the TREE before anything renders, so everything composes and
  comes back at depth 0."
  ([hiccup] (lines hiccup nil))
  ([hiccup opts]
   (let [unknown (remove #{:detail :list-head :region :within} (keys opts))]
     (when (seq unknown)
       (throw (ex-info (str "unknown option" (when (next unknown) "s") " "
                            (str/join ", " (map str (sort-by str unknown)))
                            " — lines/text/of speak :detail (:structured or"
                            " :prose), :list-head (nil = every row), :region,"
                            " and :within. (:attrs was removed with format v2 —"
                            " the svg census is the overlay story.) Guessing a"
                            " default would answer a question you did not ask")
                       {:unknown (vec unknown)})))
     (when-let [d (:detail opts)]
       (when-not (#{:structured :prose} d)
         (throw (ex-info (str ":detail must be :structured or :prose — got "
                              (pr-str d))
                         {:detail d}))))
     (when-let [lh (:list-head opts)]
       (when-not (and (int? lh) (pos? lh))
         (throw (ex-info (str ":list-head must be a positive integer, or nil"
                              " for every row — got " (pr-str lh))
                         {:list-head lh}))))
     (let [o (merge {:list-head nil} opts)
           t (hiccup/expand hiccup)
           t (if-let [r (:region o)] (hiccup/region t r) t)
           t (if-let [w (:within o)] (hiccup/scope-node t w) t)]
       (vec (remove nil? (cljnx.render/emit t 0 o)))))))

(defn of
  "A rendered screen as structured text — what a reader would see, at a size
  worth reading. Hiccup in, text out.

  ```clj
  (screen/of (views/page state))
  (screen/of pane {:attrs #{:class} :list-head nil})
  ```

  ```
  § main
    # code
    3 modules, 4 namespaces — 1 of them foundation
    <ul ×32>
      demo.web [/store/module/demo.web] 9 undocumented, of 12
      +31 more
    <svg module-graph — 3 module-link, 1 module-node gap-w4, 17 module-node gap-w0>
  ```

  **This is the half that did not exist.** Driving an app headless already
  worked — a `:cljc` wiring map with `:fetch`/`:render` passed in routes,
  fetches, derives and renders on a JVM with no browser anywhere. What came out
  the far end was hiccup nobody could read, so the review went back to
  screenshots for bugs that were plain wrong sentences.

  Options and the two-audience argument are on [[lines]], which this joins.
  Reach for `lines` in a test; reach for this to LOOK at a screen.

  **It is not a screenshot and must not be sold as one.** Two of the seven bugs
  that got past a careful reviewer needed pixels — a list wrapping over three
  lines, and a tint invisible against white — and no amount of structure in a
  text rendering reaches either. Wrapping and contrast are still eyes.

  Lifted from slopp-ui's `readout`, where it was built against a week of real
  view bugs. Depends on nothing but `clojure.string` — no view, no app, no
  store — which is what let it move here unchanged, and what lets any project
  whose views are data use it the same way."
  ([hiccup] (of hiccup nil))
  ([hiccup opts] (str/join "\n" (lines hiccup opts))))

^{:unsafe "resolves slopp.webapp/slopp.web by NAME, and a static require is
  impossible here rather than merely inconvenient: this namespace ships to
  EVERY store through capabilities/shipping-common, while the two it derives
  from are vendored per FAMILY. A store using http is handed no slopp.webapp
  source at all, so requiring it would make the fake browser fail to load for
  the majority app type. The obligation is owned by construction — a store
  whose entry returns :webapp/routes IS a webapp store, so that family is
  present whenever the branch that resolves it runs. store/late-ref is the
  dialect's carrier for this and is unavailable for the same reason: slopp.store
  is not vendored either."}
(defn ^:export driver-for
  "Whatever an app's entry returned, as the ONE driving contract [[open!]] takes.

  Three shapes go in and one comes out:

  | the entry returned | derived by |
  |---|---|
  | a webapp DECLARATION (`:webapp/routes`) | `slopp.webapp/driver` over its wiring |
  | a served CTX (`:web/routes`) | `slopp.web/driver` |
  | the contract already | itself |

  **There is exactly one of these, and it is public, because two would drift.**
  The `screen` tool derives a driver from a store's marked entry; a project's
  own tests drive the same entry. If this were internal the tool would derive
  and the tests would spell their own — a second wiring of one app with nothing
  comparing them, which is the lookalike the fake browser exists to remove,
  reintroduced one level up. And it would PASS, because each half would be
  asserting against its own reconstruction.

  **This is the only place that knows app types, and [[open!]] still does not.**
  That split is what let the fake browser leave http's namespace family: the
  driving contract is neutral, and each capability derives it from what it owns.

  **The capabilities resolve LATE, inside the branch that matched**, which is
  load-bearing rather than stylistic — see the `^:unsafe` note above.

  Identity on the contract itself, so a caller never has to ask which of the
  three it is holding. A shape it cannot place refuses HERE, where the mistake
  was made, rather than a screen later."
  [entry]
  (cond
    (not (map? entry))
    (throw (ex-info (str "an app entry returns a MAP — a webapp declaration"
                         " (:webapp/routes), a served ctx (:web/routes), or a"
                         " page ({:state :view}) — got " (pr-str entry))
                    {:got entry}))

    (:webapp/routes entry)
    (let [wire ((requiring-resolve 'slopp.webapp/wiring) entry)]
      ((requiring-resolve 'slopp.webapp/driver) wire))

    (:web/routes entry)
    ((requiring-resolve 'slopp.web/driver) entry)

    ;; already the contract — :view or :document is what produces a screen, and
    ;; open! judges the rest
    (or (:document entry) (:view entry))
    entry

    :else
    (throw (ex-info (str "this entry is none of the three shapes an app can"
                         " return: no :webapp/routes (a browser app), no"
                         " :web/routes (a served app), and no :view or"
                         " :document (a page wired by hand). Keys: "
                         (pr-str (vec (sort (map str (keys entry))))))
                    {:keys (vec (keys entry))}))))

(defn ^:export open!
  "Open a headless browser over `app`. ONE contract, and it knows no app type:

  ```clj
  {:document (fn [path] hiccup)     ; how a path becomes a screen
   :state    (atom {:n 0})          ; the app's own state, if it has any
   :view     (fn [state] hiccup)    ; state -> hiccup, re-derived every read
   :navigate (fn [state path] state')
   :dispatch (fn [action value] …)
   :boot     (fn [state] state')}
  ```

  An app needs SOME way to produce a screen — a `:view` over state, a
  `:document` over a path, or both — and nothing else here is required.

  **Neither half of the contract is produced by hand.** A served app becomes
  one through `slopp.web/driver`, which performs a real request down the real
  pipeline; a browser app becomes one through `slopp.webapp/driver`, which runs
  the app's own client loop. Two producers, one contract, and this namespace
  depends on neither — which is what lets it be vendored to every store rather
  than to one capability's.

  It used to take a served CONTEXT directly and call the dispatcher itself.
  That put http's adapter inside the fake browser, and vendoring is per FAMILY:
  a store using http is handed no `slopp.webapp` source, so the second adapter
  could never join the first and a browser app's entry could not be reached
  from here at all. A ctx is refused now, naming its producer — there is no
  shim, because accepting one silently is exactly the arrangement that had to
  end.

  **`:boot` is the page's entry point, and open RUNS it, once.** A browser runs
  an app's entry point at page load, and the entry point is where every app
  starts the loads that belong to no particular screen — which is exactly the
  data a driven session used to show as structurally present and materially
  empty, with nothing distinguishing \"the app never asked\" from \"asked, not
  yet arrived\". Same shape and same discipline as `:navigate`: `(fn [state]
  state')`, applied read-call-write, never inside `swap!`.

  **Both together, for a mounted page that carries client logic** — which is
  ordinary and not an SPA. The document arrives through `:document`; the
  `:view` re-renders after an event, and finds the dispatched document in its
  own state under `:slopp.cljnx/document`. Without a `:view` the document
  is simply static after load, which is exactly right for a page with no client
  logic and is not something to warn about.

  **A page that cannot open REFUSES HERE, naming the key** — required, unknown,
  or the wrong kind of thing. The review measured the alternative: a missing
  `:state` died a screen later as `Cannot invoke Future.get()`, and `{:vew …}`
  rendered a BLANK PAGE, which reads as a bug in an app that was never wired.
  The mistake was made at the constructor, so the constructor is where it is
  named.

  **slopp runs the browser; the app supplies the page.** That split is the
  design, and it is where three earlier drafts went wrong. Each had the app
  hand back a driver — `{:visit … :click …}`, or a path→hiccup function — and
  so made every project write its own fake browser. An adapter like that is the
  least-exercised code in a project and is free to drift from the path a real
  browser takes, so a test drives a lookalike and passes while the real screen
  is wrong. That is the bug this exists to kill; a design that reintroduces it
  one level up is not a fix."
  [app]
  (when-not (map? app)
    (throw (ex-info (str "open takes the app as a map — {:document …} for a path"
                         " that renders, {:state … :view …} for client state —"
                         " got " (pr-str app))
                    {:got app})))
  ;; a served ctx is the one wrong shape worth naming rather than reporting as
  ;; unknown keys: its author did not typo anything, they handed over the map
  ;; this used to take, and the answer is one call away
  (when (:web/routes app)
    (throw (ex-info (str "this is a served CONTEXT, not a page — wrap it:"
                         " (open! (slopp.web/driver ctx)). The fake browser no"
                         " longer performs http's requests itself, so that the"
                         " same contract can be produced from a browser app's"
                         " wiring by slopp.webapp/driver.")
                    {:unknown [:web/routes]})))
  (let [allowed #{:state :view :navigate :dispatch :boot :document}
        unknown (remove allowed (keys app))]
    (when (seq unknown)
      (throw (ex-info (str "unknown page key"
                           (when (next unknown) "s") " "
                           (str/join ", " (map pr-str (sort-by str unknown)))
                           " — a page declares :document, :state, :view,"
                           " :navigate, :dispatch and :boot. (A typo here used"
                           " to render a BLANK page; refusing is the favour.)")
                      {:unknown (vec unknown)})))
    ;; SOME way to produce a screen. An app declaring neither renders nothing
    ;; at every url, which is the blank page this constructor exists to refuse
    (when-not (or (contains? app :view) (contains? app :document))
      (throw (ex-info (str "a page needs :view — (fn [state] hiccup) — or"
                           " :document — (fn [path] hiccup); with neither there"
                           " is no screen to read at any url")
                      {})))
    (when (and (contains? app :view) (not (ifn? (:view app))))
      (throw (ex-info (str "a page needs :view — (fn [state] hiccup); without"
                           " one there is no screen to read")
                      {})))
    (when (and (contains? app :document) (not (ifn? (:document app))))
      (throw (ex-info (str ":document must be callable — (fn [path] hiccup),"
                           " what a visit renders — got "
                           (pr-str (:document app)))
                      {:document (:document app)})))
    ;; a :view is a function OF STATE, so one without state has nothing to read
    (when (and (contains? app :view) (not (contains? app :state)))
      (throw (ex-info (str "a page needs :state — the app's OWN atom, so what"
                           " a handler changes is what the view re-reads")
                      {})))
    (when (and (contains? app :state)
               (not (instance? clojure.lang.IAtom (:state app))))
      (throw (ex-info (str "a page's :state must be an atom — something the"
                           " browser can read and reset! — got "
                           (pr-str (type (:state app))))
                      {:state (:state app)})))
    (when (and (contains? app :boot) (not (ifn? (:boot app))))
      (throw (ex-info (str ":boot must be callable — (fn [state] state'),"
                           " the entry point's state transform — got "
                           (pr-str (:boot app)))
                      {:boot (:boot app)})))
    (when (and (:boot app) (not (:state app)))
      (throw (ex-info ":boot needs :state — an entry point with no state to change has nothing to say headlessly" {}))))
  (when-let [b (:boot app)]
    ;; read, call, write — never inside swap!, for navigate's reason: the
    ;; entry point is the app's own code and swap! demands a pure fn
    (let [st (:state app)]
      (reset! st (b @st))))
  (atom {:app app :path nil :document nil}))

(defn tree
  "The session's current document.

  With a `:view`, that is the app's view over the app's state, RE-DERIVED on
  every call rather than cached — which is what makes a handler's effect
  visible without the browser knowing anything happened. A real browser earns
  this with a render loop; here the view is a pure function of state, so
  reading IS re-rendering.

  Without one, it is whatever the last [[visit!]] received from the app's own
  routes. A mounted page with no client logic is static after load, and that is
  the correct answer rather than a limitation."
  [session]
  (let [{:keys [app document]} @session]
    (if-let [view (:view app)]
      (view @(:state app))
      document)))

(defn ^:export visit!
  "Go to `path`. Returns the session.

  How a url resolves depends on what the app IS, and both answers are the
  app's own — slopp never learns what `/store` means:

  - **`:navigate`** — `(fn [state path] state')`, client-side routing. ONE
    function, deliberately not a router: which screen, which params, what to
    fetch, whether anything loads at all is the app's business. The path
    arrives VERBATIM, query string included.
  - **`:web/routes`** — a real request through `slopp.web.dispatch/handle!`:
    routing, auth policy, declared reads, the handler, effects. The url is
    split the way a browser sends it — `:uri` never carries the `?`, the
    query string arrives as `:query-string`, and a `#fragment` never reaches
    the wire at all. The review measured the alternative: `/search?q=web`
    404ing on a mounted route, so every pagination link read as a broken
    route.

  `:navigate` wins where both exist, because an app that routes on the client
  is telling you a url change is a client event.

  Three urls that are not navigations, each answered as a browser answers it:
  an EXTERNAL url (`https://…`) REFUSES — a headless session has nowhere to
  go, and handing it to a client router would be wrong for both sides; a bare
  fragment (`#top`) is a SCROLL, so it is a no-op here; a hash ROUTE (`#/…`)
  is client routing by convention and goes to `:navigate` like any path.

  **A non-hiccup body is rendered as its STATUS and its data**, never as a
  blank page. A 404 that read as an empty screen would send a reader looking
  for a rendering bug in a handler that was never reached.

  **An app with neither REFUSES rather than doing nothing.** An app without
  urls is legitimate, and visiting one is a mistake worth hearing about — a
  silent no-op reads as a page that navigated and rendered nothing, which is a
  bug report about the app rather than about the call."
  [session path]
  (let [{:keys [app]} @session]
    (cond
      (re-find #"^[a-z][a-z0-9+.-]*://" path)
      (throw (ex-info (str "visiting " (pr-str path) " leaves the app — a"
                           " headless session has nowhere else to go. The href"
                           " is on the screen, which is usually the fact a test"
                           " wants; following it is a real browser's business")
                      {:path path}))

      ;; a bare fragment is a scroll target; a browser changes no page state.
      ;; #/… is the hash-ROUTING convention and falls through to :navigate.
      (and (str/starts-with? path "#") (not (str/starts-with? path "#/")))
      nil

      (:navigate app)
      ;; NOT (swap! state nav path). swap! RETRIES its function whenever the
      ;; CAS loses, so it requires a pure one — and `nav` is the app's, which
      ;; slopp cannot know anything about. A real SPA loop swaps the same atom
      ;; from inside it: the inner swap changes the value mid-computation, the
      ;; outer CAS fails, it retries, forever. Measured at 64 MILLION retries in
      ;; three seconds, and it presents as a HANG rather than an error — a
      ;; consumer lost 127 seconds and a dead image to it, then `query_eval`
      ;; answering [] because the image was pinned.
      ;;
      ;; Read, call, write. There is one thread here, so nothing is lost by
      ;; giving up the atomicity — and an app that mutates the atom ITSELF and
      ;; returns the new value (the ordinary adapter shape) works either way.
      (let [st (:state app)]
        (reset! st ((:navigate app) @st path)))

      (:document app)
      ;; the path arrives VERBATIM. Splitting a url — stripping the fragment a
      ;; browser never sends, separating the query string — is what the
      ;; PRODUCER of this document does, because only it knows whether the url
      ;; is about to become an http request or a client route. This used to
      ;; call the dispatcher here, which is how http's adapter came to live
      ;; inside the fake browser.
      (let [doc ((:document app) path)]
        (swap! session assoc :document doc)
        ;; a mounted page that ALSO has client logic: its :view re-renders from
        ;; state, so the document has to be reachable from there
        (when-let [st (:state app)] (swap! st assoc ::document doc)))

      :else
      (throw (ex-info (str "this app declares neither :navigate nor :document,"
                           " so it has no urls — cannot visit " (pr-str path)
                           ". A served app gets both from slopp.web/driver and"
                           " a browser app from slopp.webapp/driver; a page"
                           " wired by hand adds :navigate (fn [state path]"
                           " state') or :document (fn [path] hiccup)")
                      {:path path})))
    (when-not (and (str/starts-with? path "#") (not (str/starts-with? path "#/")))
      (swap! session assoc :path path))
    session))

(defn- unary?
  "Whether `f` accepts exactly one argument — read off the function, never
  discovered by catching `ArityException`, which would report a genuine arity
  bug INSIDE a handler as a signature mismatch.

  Two cases, and the review measured what missing the first one costs:

  - A `RestFn` accepts one arg when its required positional count is ≤ 1.
    `(fn [e & more])` compiles to a RestFn declaring only `doInvoke`, so a
    declared-methods probe called it zero-arg and manufactured
    `Wrong number of args (0)` for a perfectly valid handler. `with-meta` on
    ANY fn wraps it in an `AFunction$1` — which IS a RestFn of requiredArity
    0 delegating through `applyTo` — so the wrapper is the same case.
  - Otherwise a compiled fn declares one `invoke` method per arity it
    supports, and `getDeclaredMethods` answers directly. (`getMethods` would
    not: `AFn` declares a throwing `invoke(Object)` for every fn, so the
    inherited view says yes to everything.)"
  [f]
  (if (instance? clojure.lang.RestFn f)
    (<= (.getRequiredArity ^clojure.lang.RestFn f) 1)
    (boolean (some #(and (= "invoke" (.getName ^java.lang.reflect.Method %))
                         (= 1 (count (.getParameterTypes ^java.lang.reflect.Method %))))
                   (.getDeclaredMethods (class f))))))

(defn- submit!
  "Submit the form enclosing `node` — navigate to its `action` with the named
  fields serialised. Returns the session, or nil when `node` is not a submit
  control inside a form.

  **A `<button>` with no `:type` IS a submit button.** That is HTML's default
  and the spelling most apps actually write, so requiring `:type \"submit\"`
  would make the framework's drivability a markup decision — the same reason
  a field is addressed by whichever of four attributes the author happened to
  use.

  Field values come from what [[fill!]] remembered, falling back to the
  element's own `:value` — which is what a browser submits for a field nobody
  touched, and is the whole content of a hidden field.

  Only a GET form is submitted. A POST carries its fields in a BODY, and a
  navigation cannot; rather than dropping them silently it refuses and says
  so, because a submit that reported success and sent nothing is the worst
  answer available here."
  [session node]
  (let [a (hiccup/attrs node)
        t (str/lower-case (str (:type a)))]
    (when (and (#{:button :input} (hiccup/tag node))
               (or (str/blank? t) (= "submit" t)))
      (when-let [f (hiccup/form-of (tree session) node)]
        (let [fa     (hiccup/attrs f)
              method (str/lower-case (str (or (:method fa) "get")))
              vals   (:form-values @session)
              enc    #(java.net.URLEncoder/encode (str %) "UTF-8")
              qs     (str/join
                      "&"
                      (for [el    (hiccup/nodes f)
                            :let  [ea (hiccup/attrs el)]
                            :when (:name ea)]
                        (str (enc (:name ea)) "="
                             (enc (or (get vals (:name ea)) (:value ea) "")))))
              path   (or (:action fa) "")]
          (when-not (= "get" method)
            (throw (ex-info (str "this form is method " (pr-str method)
                                 " — its fields travel in a BODY, and a headless"
                                 " submit navigates, so they would be dropped."
                                 " Drive the handler directly, or make the form"
                                 " a GET if the action is really a read")
                            {:method method :action path})))
          (visit! session (if (seq qs) (str path "?" qs) path)))))))

(defn click!
  "Click the element `target` names, running the app's own handler. Returns the
  session, so calls thread.

  `target` is an element's visible text (\"Add\"), its `:href` (\"/store\"), or
  its `:aria-label` — the address an icon-only control has instead of text.
  The handling element is resolved the way a browser resolves it (bubbling —
  see [[slopp.cljnx.hiccup/target-node]]), a disabled control refuses,
  and whatever the handler does to the app's state is simply true afterwards —
  [[tree]] re-derives, exactly as a re-render would.

  **Three ways a handler can be written, and all three are the app's own.**
  Checked against the libraries rather than inferred from one app:

  - `{:on-click (fn [e] …)}` — Reagent. Invoked here.
  - `{:on {:click (fn [e] …)}}` — Replicant, function form. Invoked here.
  - `{:on {:click [:action …]}}` — Replicant, DATA form. Handed to the page's
    `:dispatch`, which mirrors `replicant.dom/set-dispatch!`'s
    `(event-data handler-data)` arity. The handler data arrives VERBATIM,
    because that is what a dispatcher switches on.

  An `:href` with no handler NAVIGATES, through [[visit!]] and so through the
  page's own `:navigate`. That is the one opinion a browser holds that an app
  cannot override, and it is a browser's opinion rather than a framework's:
  following a link is what clicking one MEANS.

  **A function is called with one argument, an event map, unless it takes
  none.** Both spellings are everywhere in real code — `(fn [e] …)` and
  `#(swap! state …)` — and the arity is read off the function ([[unary?]])
  rather than discovered by catching `ArityException`, which would report a
  genuine arity bug INSIDE a handler as a signature mismatch and send the
  reader somewhere the defect is not.

  **Data with no `:dispatch` declared REFUSES.** A click that reported success
  and changed nothing is the worst answer available here."
  [session target]
  (let [node (hiccup/target-node (tree session) target)
        a    (hiccup/attrs node)
        ev   {:kind :click :target target :text (hiccup/text node) :href (:href a)}
        [how v] (hiccup/handler node :click)]
    (case how
      :fn   (if (unary? v) (v ev) (v))
      :data (if-let [d (:dispatch (:app @session))]
              (d v nil)
              (throw (ex-info (str "clicking " (pr-str target) " carries handler DATA "
                                   (pr-str v) " and this page declares no :dispatch,"
                                   " so nothing would run. Add"
                                   " :dispatch (fn [action value] …) to the page")
                              {:target target :handler v})))
      ;; no handler at all. An :href NAVIGATES; a submit control inside a form
      ;; SUBMITS it. Both are the browser's own opinion rather than the
      ;; framework's, and they have the same standing: following a link is what
      ;; clicking one MEANS, and so is submitting a form.
      (if (:href a)
        (visit! session (:href a))
        (submit! session node)))
    session))

(defn ^:export text
  "What is on the screen right now, as readable text — the whole assertion
  surface in one call.

  ```clj
  (screen b)                       ; the page
  (screen b \"main\")                ; one region, and THROWS if it is not there
  (screen b \"main\" {:detail :prose})
  ```

  `(screen b)` is `(slopp.cljnx/of (tree b))`, which is the line every
  test writes and the one worth not retyping. The REGION arity is the one that
  earns its place: a whole-page `str/includes?` is one keystroke from asserting
  nothing in particular, and that is not hypothetical — the bug that prompted
  this whole exercise was a tint check matching its pattern anywhere on the
  page, so it claimed the diagram, checked a list, and stayed green with the
  layout torn out.

  Naming the region makes the narrow assertion the SHORTER one to write, which
  is the only kind of discipline that survives contact with a deadline. And a
  region that is not on the screen refuses rather than scoping to nothing —
  otherwise every absence assertion downstream of it passes over a blank page.

  Options are [[lines]]'s (`:detail`, `:list-head`, `:region`) and an option
  outside that vocabulary REFUSES there — a typo'd `:detial` used to silently
  answer structured, a wrong answer reported as success."
  ([session] (of (tree session)))
  ([session region] (text session region nil))
  ([session region opts]
   (of (tree session) (assoc opts :region region))))

(defn fill!
  "Type `value` into the field `name` addresses, running the app's own handler.
  Returns the session, so calls thread.

  `name` is the field's `:placeholder`, `:name`, `:id` or `:aria-label` —
  whichever the app happens to have written. Both event names are tried,
  because Reagent apps write `:on-change` and a Replicant `:on` map usually
  names `:input`.

  **A control constrains its values the way a browser does.** A `<select>`
  only ever produces one of its options' values, so a `value` no option
  carries REFUSES, listing the choices — a test filling a value production
  cannot produce asserts nothing. A checkbox has no text either way: its
  `value` here is its checked state, the boolean passed through verbatim.

  **The DATA form is the portable one, and for an input it is the only one.**
  It reaches `:dispatch` as `(action value)` — the action verbatim and the
  typed text as a SCALAR. slopp invents no event, which is the whole point:
  Replicant's real event map carries `:replicant/dom-event` and no `:value`,
  so the typed text lives behind `(.. e -target -value)` — interop, which
  cannot run on a JVM at all. An earlier cut of this passed
  `{:value v :target {:value v}}`, and a handler written against it would have
  passed every headless test and done NOTHING in a browser. A tool that
  green-lights production breakage is worse than one that refuses.

  **A FUNCTION handler on an input cannot be portable**, and that is a fact
  about browsers rather than a gap here: in a browser it receives a DOM event,
  and reading a value out of one is interop. The map passed to it is a best
  effort for an app whose own `:cljs` shell normalises to the same shape
  DELIBERATELY — if yours does not, use the data form. The rule that makes this
  go away: your `:cljs` dispatcher turns the event into a scalar, and your
  `:cljc` interpreter never sees an event of any shape, so neither driver's
  event can be right while the other is wrong."
  [session name value]
  (let [node    (hiccup/field (tree session) name)
        [how v] (hiccup/input-handler node)]
    (when (= :select (hiccup/tag node))
      (let [choices (vec (keep #(:value (hiccup/attrs %))
                               (filter #(= :option (hiccup/tag %))
                                       (hiccup/kids node))))]
        (when-not (some #{value} choices)
          (throw (ex-info (str "the select " (pr-str name) " has no option "
                               (pr-str value) " — a browser only lets you"
                               " choose among: " (str/join ", " (map pr-str choices))
                               ". A test filling a value production cannot"
                               " produce asserts nothing")
                          {:field name :value value :options choices})))))
    (case how
      :fn   (v {:value value :target {:value value}})
      :data (if-let [d (:dispatch (:app @session))]
              (d v value)
              (throw (ex-info (str "the field " (pr-str name) " carries handler DATA "
                                   (pr-str v) " and this page declares no :dispatch,"
                                   " so typing would change nothing. Add"
                                   " :dispatch (fn [action value] …) to the page")
                              {:field name :handler v})))
      ;; no handler: a plain form field. Its value goes nowhere until the form
      ;; is SUBMITTED, so it is remembered here — which is exactly where a
      ;; browser keeps it, and why refusing this field said something false.
      (swap! session assoc-in
             [:form-values (or (:name (hiccup/attrs node)) name)] value))
    session))

(defn drive!
  "Run an ordered `steps` script against `session`. Returns the session.

  ```clj
  (drive! s [{:visit \"/store\"} {:fill \"Filter\" :value \"web\"} {:click \"Go\"}])
  ```

  Data rather than a call chain, so the same script can arrive from a tool
  (the `screen` tool reaches this by requiring-resolve inside the verification
  image, where the app's vars are), a test, or a file — and so the ONE
  interpreter is here, testable, rather than assembled as a string by whatever
  is driving. A generated call chain is a second producer of this behaviour
  and would drift from it.

  A step runs EXACTLY one action. Naming two refuses (running one silently is
  a guess about ordering the caller never made); naming none refuses with the
  step's OWN keys, which is where the typo is. A `:fill` step must carry
  `:value` — typing nothing is not a step, and clearing a field is
  `:value \"\"`. The review found the original here reporting `(keys steps)` —
  the whole VECTOR — so every typo'd step died as a ClassCastException; a
  refusal that names the mistake is the entire value of an interpreter this
  small.

  There is no session BETWEEN scripts on purpose. Filling a box and then
  clicking Search is one sequence or it is nothing, and a session held across
  calls makes the same call answer differently depending on what ran before
  it. The cost is re-running a script to take one more step; what it buys is
  that a screen you looked at is one you can pin, because the pin is the same
  script."
  [session steps]
  (when-not (and (sequential? steps) (every? map? steps))
    (throw (ex-info (str "steps must be a vector of step maps — got "
                         (pr-str steps) ". Each step names one of :visit,"
                         " :click or :fill")
                    {:steps steps})))
  (doseq [step steps]
    (let [named (filterv #(contains? step %) [:visit :click :fill])]
      (cond
        (< 1 (count named))
        (throw (ex-info (str "a step runs exactly one action — this one names "
                             (str/join " and " named)
                             ". Split it: filling and clicking are two steps"
                             " in a browser too")
                        {:step step}))

        (= [:visit] named) (visit! session (:visit step))
        (= [:click] named) (click! session (:click step))

        (= [:fill] named)
        (if (contains? step :value)
          (fill! session (:fill step) (:value step))
          (throw (ex-info (str "a :fill step needs :value — typing nothing is"
                               " not a step, and clearing a field is"
                               " :value \"\"")
                          {:step step})))

        :else
        (throw (ex-info (str "a step must name one of :visit, :click or :fill"
                             " — this one has " (pr-str (vec (keys step))))
                        {:step step})))))
  session)
