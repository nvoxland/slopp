(ns slopp.ui.app
  "This application as DATA — the seam that lets one app be driven by a browser
  or by a test.

  `slopp.ui.spa/navigate!` already took the whole app as a map of plug-ins and
  touched no browser; its `:fetch` uses callbacks rather than promises
  specifically so it stays `:cljc`. So the loop was always drivable from a JVM.
  What was not, was the MAP: it was assembled in `slopp.ui.client.app`, which
  is `:cljs`, so nothing on this side could construct the real thing.

  A headless driver therefore had to hand-build a lookalike — and a lookalike
  drifts silently, because it passes while the real screen is wrong. That is
  the same defect as a fixture the author chose, one level up: the app under
  test agrees with your model of the app because you wrote it.

  So routing and derive live here, and `client.app` keeps only what could not
  be anywhere else — the promise adaptation, `pushState`, the DOM, rough.js.
  `AGENTS.md` already asked for that on the grounds that `:cljs` is the one
  layer the JVM oracle cannot verify; this is the same rule arriving from the
  other direction, and it is what makes
  `app-test/the-whole-app-can-be-driven-headless-and-read-as-text` a test of
  the app rather than of a resemblance."
  (:require [slopp.ui.views :as views] [slopp.ui.schema :as schema] [slopp.webapp :as webapp] [slopp.http.endpoint :as endpoint] [slopp.ui.wire.api :as api]))

(def actions
  "Every action a control in this app can dispatch, with a runnable example.

  **Data handlers, not closures** — `[:docs/all true]` in the tree rather than
  `(fn [e] …)`. Replicant takes either and routes data through one dispatcher
  (`replicant.dom/set-dispatch!`), and the data form buys three things a
  closure cannot:

  - a headless driver can invoke it without a JavaScript runtime
  - a READOUT can report what a click DOES — `Expand [click :docs/all]` rather
    than a bare `[click]`, which only says that something is possible
  - the vocabulary is enumerable, so `actions` and [[act]] can be checked
    against each other instead of agreeing by accident

  The `:example` on each row exists for that last one: the pairing test
  iterates this map and dispatches every entry, so an action nobody interprets
  is a failure rather than a keyword that quietly does nothing.

  **`:effectful?` says which INTERPRETER owns the word, and the map used to
  have no way to say it.** Most actions are reduced by [[act]] into new state
  and nothing else happens. `:try/execute` is not: the shell intercepts it
  ahead of `act` and performs a real request, so `act` has no branch for it
  and never will. Both dispatchers read this flag rather than naming the
  keyword, which is what makes a second effectful action a row here instead
  of an edit in two shells.

  **Three rows arrived late and that is the point.** `:try/set`, `:try/arm`
  and `:try/execute` were dispatched by the call form and declared nowhere,
  for as long as the paragraph above claimed the vocabulary was enumerable.
  A loop over this map cannot notice an action missing FROM it — see
  `app-test/every-action-a-CONTROL-dispatches-is-one-the-vocabulary-declares`,
  which renders the controls and reads what they emit."
  {:docs/all    {:what "expand or collapse every comment"
                 :example [:docs/all true]}
   :docs/one    {:what "toggle one comment's override"
                 :example [:docs/one "a"]}
   :display/set {:what "set one display option to a given value"
                 :example [:display/set :private? true]}
   :filter/set  {:what "set the namespace filter to the typed text"
                 :example [:filter/set] :value "web"}
   :try/set     {:what "type into one field of the ad-hoc call form"
                 :example [:try/set "name"] :value "slopp.ops"}
   :try/arm     {:what "consent to an effectful call — the first of its two presses"
                 :example [:try/arm]}
   :try/execute {:what "actually send the ad-hoc call"
                 :example [:try/execute]
                 :effectful? true}
   ;; **`:leaves?`, the third kind.** Not `:effectful?` — that makes a REQUEST
   ;; and stays on the page. This hands the page back to the browser through
   ;; `:webapp/leave!`, which is what switching project is: every pane is
   ;; showing a different project's data afterwards.
   ;;
   ;; ONE row. It was two for a day — a `:project/set` that recorded the choice
   ;; and this one that left for it — because `url-for` could not see the
   ;; dispatched value and a select's payload cannot ride in the action. That
   ;; argument went to slopp and `url-for` now takes the value, so the
   ;; round-trip through state is gone.
   :project/goto {:what "switch to the project the switcher is pointing at"
                  :example [:project/goto]
                  :leaves? true}
   })

(defn act
  "State after dispatching `action`, where `value` is the typed/selected text
  the view could not know — everything else rides in the action itself.

  **`value` is a normalised scalar, not an event.** Replicant's real event map
  is `{:replicant/trigger … :replicant/dom-event e :replicant/node <DOM node>}`
  and carries no value at all — the text is at `(.-value node)`, which is
  interop and cannot run on a JVM. A headless driver synthesises a different
  shape again. So neither event shape crosses this line: whoever owns the
  browser normalises first, and this stays `:cljc`.

  That matters more than it looks. A handler written to read one driver's event
  shape directly passes under that driver and fails in a browser, which is the
  worst direction — so the seam is here, where both callers can meet it.

  **An unrecognised action is a NO-OP, not a throw.** A cached bundle can
  outlive the vocabulary that generated its markup, and a stale handler should
  do nothing rather than white-screen the app; the pairing test is what stops
  an action being unrecognised in the first place."
  [state [kind & args] value]
  (case kind
    :docs/all    (update state :show views/toggle-all-docs (first args))
    :docs/one    (update state :show views/toggle-doc (first args))
    :display/set (update state :show views/set-display-option (first args) (second args))
    :filter/set  (assoc state :filter value)
    ;; the API section's ad-hoc call form. The FIELD NAME rides in the action
    ;; and the typed text arrives as `value` — same shape as :filter/set, and
    ;; for the same reason: the view knows which box it drew, and only the
    ;; browser knows what is in it.
    ;; `:call`, not `:try`, because `slopp.webapp/perform!` merges `:status`,
    ;; `:request` and `:response` into exactly that key — and it MERGES so an
    ;; effect's inputs sit beside its outcome. Written under `:try` these would
    ;; be a second map the panel had to join by hand, and the panel whose whole
    ;; job is showing what was sent beside what came back would lose the sent
    ;; half the moment the call started.
    :try/set     (assoc-in state [:call :params (first args)] value)
    ;; ARM. An effectful endpoint takes two presses, and the first one is a
    ;; pure state change — the dispatchers only reach `spa/perform!` once
    ;; `try-request` returns something, and it returns nil while unarmed.
    ;; `:effectful?` is the only thing that can say which endpoints deserve
    ;; the second press, and it is a fact no other API browser has.
    :try/arm     (assoc-in state [:call :armed?] true)

    state))

(defn try-request
  "The ad-hoc call `state` currently describes, as data — or nil.

  Derived from state rather than built by the control that triggers it, so what
  gets SENT is a pure function of what was typed and can be asserted without
  performing anything. The performer's whole job is turning this into an HTTP
  call; every decision about the call is made here.

  Nil unless the open address is one endpoint's page AND that endpoint is in
  the loaded document. A reader can reach `/endpoints/get/api/nope`, and a
  request guessed from a name the document does not have would be a call to
  nothing.

  **An EFFECTFUL endpoint is also nil until armed.** The gate lives here rather
  than in the two dispatchers so that neither of them can forget it: there is
  simply no request to perform, and both already do nothing with a nil. A
  screen that assembles a mutation on the first press has already made the
  decision the second press exists to ask about.

  Read-only endpoints are unaffected — a confirmation on every call is one
  nobody reads, and `:effectful?` is what tells the two apart.

  **The ADDRESS says which screen this is, rather than a map from the screen.**
  It used to ask `screen-subject` what `:screen` was, because a route row named
  a var and `arrive` put that var in state. Under Move A `:screen` is the page
  FUNCTION — `marked-pages` stores `@v`, not the var — so there is no var
  metadata to read and such a map would have to be keyed by function values.
  The endpoint page is the only address in this app that captures a `:method`,
  so the capture IS the discriminator, and it is one this function can see.

  **The document is found under the page's OWN load key.** There is no `:main`
  load any more: `ask!` keys a load by the request it built, so reading the
  document means building the same request the page built — same descriptor,
  same base, through [[slopp.ui.views/at-project]]. Written any other way the
  two would select from different documents, which is the failure
  `routed-address` already exists to prevent one seam along."
  [{:keys [params] :as state} base]
  (let [addr (views/routed-address params)]
    (when (and (:method addr) (:path addr))
      (let [;; **The BASE, because `load-key` resolves the address before keying.**
            ;; It was `[method url]` on the unbased url, which made two
            ;; projects' `/api/modules` one load; slopp fixed that at d42353
            ;; and the 1-arity is gone rather than kept, because there is no
            ;; arity here that is right without a base. What has to match is
            ;; the key `ask!` minted, and `ask!` passes the APP's base — so
            ;; this takes the same one from the wiring rather than assuming
            ;; the root.
            k   (webapp/load-key
                 base
                 (endpoint/request (views/at-project params api/rest-paths) {}))
            ;; the ROWS, not the document. `ask!` applies no `:derive`, so the load
            ;; holds `{:slopp/rest-paths-version 1 :paths […]}` and the page
            ;; strips the envelope for itself. Filtering the document instead
            ;; walks its MAP ENTRIES, matches none of them, and answers nil —
            ;; which is the same channel an unarmed call uses, so the button
            ;; renders, presses, and does nothing.
            doc (:paths (webapp/load-value state k))]
        ;; the SAME selector the page uses. Selecting by `:name` here was
        ;; correct only while the address carried one — once it became method
        ;; and path, this matched nothing, and nil is the channel an UNARMED
        ;; call already uses, so the button would have rendered, pressed, and
        ;; done nothing.
        (when-let [ep (first (filter (views/at-address?
                                      (select-keys addr [:method :path]))
                                     doc))]
          (when (or (not (schema/effectful? ep)) (get-in state [:call :armed?]))
            (schema/request-for ep (get-in state [:call :params] {}))))))))

(defn ^{:http/external-path
        "the PROJECT serves `/api/modules`, not the daemon. This app is the
         reviewer UI: it talks to a project's API over HTTP and never opens a
         store, and the daemon mounts each project's API at
         `/api/projects/<slug>/**`, which is the base every page's request
         is measured from. `/api/projects` is the exception and says so
         itself, carrying an EMPTY `:webapp/base` — it is the DAEMON's own
         registry, at the origin root rather than under a mount, and the
         origin is the empty base rather than a flag of its own."}
  wiring
  "This application as DATA — the one map both the browser entry and the
  headless driver are derived FROM.

  The caller supplies only what genuinely differs between a browser and a
  headless driver:

  | plug-in | in a browser | headless |
  |---|---|---|
  | `:call` | `js/fetch`, supplied by `mount!` | canned answers, keyed by url |
  | `:render` | mount hiccup into the DOM | `reset!` an atom |
  | `:push-url!` | `history.pushState` | omitted; defaults to a no-op |
  | `:leave!` | `location.assign` — a full page load | omitted; a headless drive cannot leave |
  | `:boot` | fetch the project list | canned |

  **It returns the DECLARED map, and three keys it used to carry are gone.**

  `:webapp/routes` is DERIVED from the pages themselves. Every page in
  [[slopp.ui.pages]] carries `^{:webapp/path \"…\"}`; a build reads those markers
  to generate the browser's table and `slopp.cljnx/driver-for` scans the loaded
  vars for the headless one. The table used to be written here as well, which
  made the marker the decorative copy — nothing broke when the two disagreed.
  A caller that needs the map to be complete asks `driver-for` for it rather
  than calling `slopp.webapp/wiring` directly, because filling that key in is
  exactly what `driver-for` is for.

  `:webapp/session-loads` is deleted from the framework. The two loads that
  belong to no screen — the module index and the hub's project list — are asked
  for by [[slopp.ui.pages/chrome]], which is the only thing that ever read
  them. `ask!` is start-if-absent, so the first page to render starts them and
  every later one finds them: the same single fetch, without an entry-level
  declaration.

  `:webapp/chrome` is gone because a PAGE calls chrome now. The framework's
  hook is handed `(state inner)`, and under `ask!` a load is keyed by its
  request rather than by `:main` — so state alone can no longer say what this
  screen asked for. The page has the answer already and passes it. See
  [[slopp.ui.pages/chrome]].

  **`:fetch`, `:derive` and the `checks` map went the same way.** A screen's
  request was declared on a route row and validated by a `:check` the entry
  attached by path; `ask!` takes neither. What a page loads is now written
  inside the page, in `:cljc`, where a JVM test reads the url without a
  browser — and response validation has no hook at all on this path, which is
  a live gap rather than a decision. `slopp.http.endpoint/request` copies
  `:rest/response` onto the request it builds and `fetch!` ignores it; the
  contract is carried to the door and not used.

  **`:sketch` is the one thing this migration lost.** rough.js is `:cljs` and
  was supplied here, closed over by the app-wide derive. See
  [[slopp.ui.views/with-store-picture]]; slopp has it recorded unbuilt, on the
  argument that one cosmetic instance should not shape a seam.

  **`:webapp/address-keys` is `#{:lens :call}`** — the state that dies with the
  route, DECLARED rather than hand-cleared. `:call` is there for the reason the
  framework's own default names: a call form's fields are that endpoint's
  parameters, so carried across they would offer `/api/search` a value for
  `:m`. `:lens` because a lens belongs to the address that carried it."
  [{:keys [state base call render push-url! leave! boot]}]
  {:webapp/state  state
   :webapp/base   (or base "")
   ;; the PERFORMER, for page loads and for the ad-hoc call alike.
   ;; `dom/mount!` overrides it in a page, which is what stops a canned
   ;; performer reaching a browser by being left in the wiring.
   :webapp/call   (or call (fn [_ _ err] (err "no caller wired")))
   :webapp/render (or render (fn [_]))
   :webapp/push-url! (or push-url! (fn [_]))
   :webapp/leave!    (or leave! (fn [_]))
   :webapp/boot      (or boot identity)
   :webapp/act       act
   :webapp/actions   actions
   ;; `:effectful?` actions come here for their request. The action is ignored
   ;; because every judgement about WHAT to send is already in [[try-request]],
   ;; derived from state — which is what makes the request assertable without
   ;; performing anything.
   ;; the app's own base goes with it: `load-key` resolves an address before
   ;; keying, and the key `try-request` looks under must be the one `ask!`
   ;; minted from the same base.
   :webapp/request-for (fn [st _action] (try-request st (or base "")))
   ;; `:leaves?` actions come here for their DESTINATION, the way effectful
   ;; ones come to `:webapp/request-for` for their request. One action uses it
   ;; — the project switcher — and the address is built HERE rather than
   ;; carried on each `<option>`, so the option and the destination cannot
   ;; spell the same project differently.
   ;;
   ;; **Three arguments, and for a day it was two.** The value is the selected
   ;; slug, and a `<select>` is the one control whose payload cannot ride in
   ;; the action — one handler for N options, where a link spells its
   ;; destination in the `:href`. Without it the switcher needed a second
   ;; control to carry the choice through state; slopp widened this to match
   ;; `act`, and the button was deleted.
   ;;
   ;; **Nil DECLINES rather than throwing**, which is the framework's own
   ;; channel for a control with nothing to do yet: an empty option navigates
   ;; nowhere rather than to `/p/`. Same shape as `try-request` returning nil
   ;; while the call form is unarmed.
   :webapp/url-for (fn [_st _action slug]
                     (when (seq slug) (str "/p/" slug)))
   :webapp/address-keys #{:lens :call}
   ;; **not-found is the framework's, and it now replaces the WHOLE page.**
   ;; It used to arrive at `chrome` as `inner` like any other content, so the
   ;; shell stayed up around it; a page calls chrome itself now, and an address
   ;; no page matched has no page to call it. slopp's argument for that is
   ;; sound — a page nobody routed to has nothing around it to be right about.
   ;;
   ;; What it costs is the pane, and that is worth keeping: a blank document is
   ;; indistinguishable from a crash, and the ONE thing a reader needs is the
   ;; address that failed, because they arrived here from a link that was
   ;; wrong. So this carries `:data-region "main"`, which is what makes it a
   ;; PANEL rather than a page that happens to have words on it.
   :webapp/not-found (fn [state]
                       [:main {:data-region "main" :class "not-found"}
                        [:h1 "Not found"]
                        [:p "No screen answers " [:code (str (:path state))] "."]])})
