(ns slopp.webapp
  "The browser application as DATA — the loop a webapp runs, performed through
  plug-ins instead of through a browser.

  An app declares what it IS (state, routes, view, how a screen's data is
  fetched, how an action changes state) and slopp performs the sequencing that
  has to happen around those: push the url, transition, render, fetch, render
  again. Every step calls a function the app supplied, so `:render` and
  `:push-url!` are `js/…` in a real page and recorded values in a test.

  **Generalized from the only real browser app built on slopp, whose own
  routing and loop were further along than anything slopp had.** They built it because slopp shipped a fake browser
  whose contract did not fit, then wrote a ~30-line adapter to bridge the two —
  a THIRD hand-written wiring beside the browser entry and the headless driver,
  with nothing comparing the three. [[driver]] is what removes it.

  **Callbacks, not promises, and the reason travels with the decision.** A
  promise is not a thing the JVM oracle has, so a promise-based loop could only
  ever run in a browser — which puts the whole headless exercise back where it
  started. This reads as an arbitrary style choice right up until someone
  modernises it, which is why the reason is written here rather than assumed.

  **Renderer-agnostic, deliberately.** This never learns what a screen looks
  like. The moment it does, it is presentation and belongs to the app." (:require [clojure.string :as str] [slopp.lang :as lang]))

(defn- arrive
  "The state transition a navigation IS: `path` and its route in, everything
  belonging to the OLD address out.

  Pure, and separate from [[navigate!]] for the reason every decision here is
  separate from its performance — what a navigation MEANS is assertable without
  an atom, a plug-in or a clock.

  **The question for any key is: is it part of the ADDRESS, or part of the
  session?** Address-scoped state dies with the route. Session-scoped state — a
  nav pane, a signed-in user, a compose box — deliberately survives, and a loop
  that cleared it would be making a membership decision about an application's
  own state from inside the framework.

  So the membership is DECLARED, twice, because there are two kinds of it:
  `:webapp/address-keys` (default `#{:call}`) names plain state keys that die
  with the route, and `:webapp/session-loads` (default `#{}`) names LOADS whose
  entries outlive it.

  The reason for the `:call` default is sharper than staleness, and it is
  slopp-ui's: a call form's inputs are not merely stale under the new route,
  they are TYPED to the old one. `:m` is a parameter of `/api/module/:m` and
  means nothing to `/api/search`, so carrying it across shows a form claiming
  the new endpoint takes arguments it does not have.

  **An earlier version cleared every load regardless**, on the argument that the
  loop writes them so clearing them is housekeeping. That reasons from
  AUTHORSHIP, and slopp-ui showed what it costs: authorship decides who owns the
  MACHINERY, and scope is a separate question never derivable from who does the
  writing. A load pushed out of the framework to survive a navigation loses the
  four states, the token and the guard with it — see [[load!]] for the three
  defects that produced in a real app.

  **A load entry that stays is EMPTIED, not bumped, when it goes.** Emptying
  makes it `:absent` — nothing has been requested for this screen yet, which is
  the true statement — and [[begin-load]] then moves it to `:loading`. A token
  bumped in place never passes through `:absent`, so the four-state model
  quietly becomes three and a view can no longer tell an unasked load from one
  that answered nil."
  [state path route address-keys session-loads]
  (as-> state s
    (apply dissoc s address-keys)
    (assoc s
           :path   path
           :screen (:screen route)
           :params (:params route)
           :loads  (select-keys (:loads s) session-loads))))

(defn ^:export
  ^{:malli/schema [:=> {:throws [:webapp/unknown-key :webapp/missing-key]}
                   [:cat [:map
                          [:webapp/state :any]
                          [:webapp/routes :any]
                          [:webapp/view :any]]]
                   [:map [:webapp/state :any]]]}
  wiring
  "An application declared as DATA, checked and filled in — the one thing both
  the browser entry and the headless driver are derived FROM.

  Required, because nothing sensible happens without them:

  - `:webapp/state` — the app's own atom. ONE atom rendered by ONE pure
    function, which is what makes every screen reproducible from a map.
  - `:webapp/routes` — a TABLE of `[pattern screen]` rows,
    `[[\"/things\" things] [\"/things/:id\" thing]]`. Not a function, and that is
    the difference between routing an app can DESCRIBE and routing only it can
    perform: a function answers when called, with a path, at runtime, so nothing
    can list an app's screens, join a link to one, or compare this table to the
    paths the server answers for. [[match-route]] does the matching, on the same
    grammar as the server's router. A path no row matches is a real answer, and
    it is nil.
  - `:webapp/view` — `(fn [state] -> hiccup)`. Pure, so a JVM can call it.

  Optional, defaulted here so no caller has to nil-check a plug-in:
  `:webapp/base` (mount prefix, `\"\"`), `:webapp/fetch` (a screen's data;
  answers nil when a screen needs none), `:webapp/render` and
  `:webapp/push-url!` (no-ops headless, `js/…` in a page), plus
  `:webapp/derive`, `:webapp/call`, `:webapp/act`, `:webapp/actions`,
  `:webapp/request-for`, `:webapp/url-for`, `:webapp/leave!` and `:webapp/boot`.

  `:webapp/leave!` is the third kind of action's effect — a full page load, for
  a destination that is not a client route (a different mount point, so a
  different app). Like `:webapp/render` and `:webapp/push-url!` it is `js/…` in
  a page and a no-op headless, and like them the app never writes it: the
  browser entry supplies it.

  **Namespaced keys, like `slopp.cli`'s context and every `:web/*` marker.** An
  unqualified `:state` in a map an app also puts its own keys in is the nil-pun
  waiting to happen, and this map crosses a framework boundary where the reader
  cannot see what produced it.

  **It REFUSES at the constructor, naming the key.** A page that cannot open is
  measured to die a screen later as something unrecognisable — a missing state
  as `Cannot invoke Future.get()`, a typo'd `:vew` as a BLANK PAGE, which reads
  as a bug in an app that was never wired. The mistake is made here, so here is
  where it is named.

  **What it does NOT check is that `:webapp/state` is an ATOM**, and that is the
  `slopp.lang` lesson rather than an omission: asking what a thing IS differs by
  platform, D3 denies the reader conditional that would branch, and this
  namespace compiles to both. So the kind check lives in `slopp.web.screen/open!`,
  which is `:clj` and can ask — one platform down, where the answer exists."
  [app]
  (let [known   #{:webapp/state :webapp/base :webapp/routes :webapp/view
                  :webapp/fetch :webapp/render :webapp/push-url! :webapp/derive
                  :webapp/call :webapp/request-for :webapp/act :webapp/actions
                  :webapp/address-keys :webapp/session-loads :webapp/boot
                  :webapp/url-for :webapp/leave!}
        unknown (remove known (keys app))
        listed  (fn [ks] (apply str (interpose ", " (map pr-str (sort-by str ks)))))]
    (when (seq unknown)
      (throw (ex-info (str "unknown wiring key" (when (next unknown) "s") " "
                           (listed unknown) " — an app declares " (listed known)
                           ". (A typo here used to render a BLANK page; refusing"
                           " is the favour.)")
                      {:webapp/unknown-key (vec unknown)})))
    (doseq [k [:webapp/state :webapp/routes :webapp/view]]
      (when-not (contains? app k)
        (throw (ex-info (str "an app needs " k
                             (case k
                               :webapp/state  " — its OWN atom, so what a handler changes is what the view re-reads"
                               :webapp/routes " — a table of [pattern screen] rows; without one no path means anything"
                               :webapp/view   " — (fn [state] hiccup); without one there is no screen to read"))
                        {:webapp/missing-key k}))))
    ;; A TABLE, never a function, and the refusal carries the migration because
    ;; there is no shim behind it. A function answers only when called, with a
    ;; path, at runtime — so nothing can list an app's screens, join a link to
    ;; one, or compare this table to the prefixes the server answers for. Every
    ;; report and gate that exists for `^{:web/path …}` was impossible on this
    ;; side for exactly that reason.
    (when-not (sequential? (:webapp/routes app))
      (throw (ex-info (str ":webapp/routes is a declared TABLE, not a function —"
                           " [[\"/things\" things-screen] [\"/things/:id\" thing-screen]]."
                           " A function answers only when called, so nothing could"
                           " list this app's screens, join a link to one, or check"
                           " that the server serves the paths the browser routes."
                           " Got " (pr-str (type (:webapp/routes app))) ".")
                      {:webapp/routes-not-a-table true})))
    (-> (merge {:webapp/base         ""
                :webapp/fetch        (fn [_screen _params ok _err] (ok nil))
                :webapp/render       (fn [_state] nil)
                :webapp/push-url!    (fn [_url] nil)
                ;; a headless drive cannot LEAVE — there is no page to hand back
                ;; to — so the default is the no-op `push-url!` gets, and a test
                ;; that cares about the switcher supplies a recorder
                :webapp/leave!       (fn [_url] nil)
                ;; the entry point runs at page load and every app has one, even
                ;; if it is "nothing to start". A function rather than nil, or
                ;; the driver, the browser entry and whatever comes next each
                ;; write the same `or` — and one of them writes it in a place
                ;; nothing checks
                :webapp/boot         identity
                ;; most ad-hoc call panels are route-scoped, and a framework
                ;; should be right without configuration — but it is a SET so an
                ;; app whose effect panel spans screens can say #{}, and one
                ;; with its own address-scoped keys can name them
                :webapp/address-keys #{:call}
                ;; nothing outlives the screen unless the app says so. The
                ;; conservative default, because a load that wrongly survives
                ;; shows the previous screen's answer under a new url — while
                ;; one that wrongly dies is only re-fetched
                :webapp/session-loads #{}}
               app)
        ;; a DECLARED nil is not the same as an absent key, and `merge` keeps
        ;; it. The mount point arrives from a DOM attribute the browser answers
        ;; nil for when it is simply absent — which is every app served at the
        ;; root — and nil here prefixes every pushed url with the string "null".
        ;; Normalised where the app is constructed, so the shim reads the
        ;; attribute and interprets nothing
        (update :webapp/base #(or % "")))))

(defn- begin-load
  "Mark `key` as in flight, minting the token that decides whether its answer is
  still wanted.

  The token is a monotonic `:load-seq` kept OUTSIDE `:loads`, because
  [[arrive]] empties `:loads` on every navigation and a token drawn from a
  cleared map would restart — which would let a slow answer from the previous
  screen match the new screen's first request and land on it. That is the exact
  failure the token exists to prevent, reintroduced by where the number is
  stored."
  [state key]
  (let [n (inc (get state :load-seq 0))]
    (-> state
        (assoc :load-seq n)
        (assoc-in [:loads key] {:status :loading :token n}))))

(defn ^:export load-status
  "What is KNOWN about load `key` on this screen: `:absent`, `:loading`,
  `:ready` or `:failed`.

  **Four states, because three of them are routinely collapsed into nil and
  each collapse is a lie a reader believes.** `:absent` is nothing has been
  requested; `:loading` is asked and unanswered; `:ready` is answered, and
  answered with NIL or an empty list is still answered; `:failed` is asked and
  refused.

  A view written as `(if (:data s) …)` reads all four as two, and the two it
  produces are wrong in the direction that matters: an empty screen that says
  \"no results\" when nobody has asked yet, and a spinner that never stops
  because the answer was legitimately nothing.

  This is the reader a `:webapp/view` uses instead of testing `:data`, and it is
  the reason [[arrive]] empties rather than bumps."
  [state key]
  (get-in state [:loads key :status] :absent))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:webapp/state :any] [:webapp/call :any]] :any]
                   :any]}
  perform!
  "Run `request` through the app's `:webapp/call` plug-in and record the outcome.

  The sibling of [[navigate!]] and deliberately the same shape: nothing here
  touches a browser, because `:webapp/call` is `js/fetch` in a real page and a
  canned answer headless. That is what makes an ad-hoc call something a test can
  press and assert on.

  **Generic on purpose.** This knows a request goes out and an answer comes
  back; it does not know what an endpoint is. Deciding WHAT to call is
  `:webapp/request-for`, a pure function of state and action, so every judgement
  about which request an action means stays somewhere a JVM test can read it.

  **A nil request DECLINES, and that is a channel rather than a nil-check.** It
  is how the pure derivation refuses to perform — an unconsented press, an
  incomplete form — which puts the gate in a function a JVM test can read
  instead of in this one. If this threw on nil, every app's arming pattern
  would have to move somewhere worse.

  `:running` is written and rendered BEFORE the call, so a slow endpoint says so
  instead of looking like a button that did nothing.

  **The effect entry MERGES rather than being replaced**, which is the opposite
  of [[begin-load]] and the asymmetry is the point. An effect's INPUTS live
  beside its status — what the reader typed into the call form, and their
  consent — so a fresh map blanks the form the moment the call starts, and the
  panel whose job is to show what was SENT beside what came back loses the sent
  half exactly when it matters. A load has no inputs the reader supplied, so
  replacing its entry is correct there and wrong here. Caught by slopp-ui
  reading the first version.

  **No freshness token, unlike a navigation, and the difference is real rather
  than an omission.** A navigation can be superseded by another navigation; this
  is triggered by a press. Two presses in flight resolve in whatever order they
  resolve and the second answer wins — which is what a reader pressing twice
  means. If that ever stops being true it wants the same token machinery, not a
  guess."
  [app request]
  (when request
    (let [{:webapp/keys [state call render]} app
          record! (fn [m] (swap! state update :call merge m) (render @state))]
      (record! {:status :running :request request})
      (call request
            (fn [response] (record! {:status :done :request request :response response}))
            (fn [message]  (record! {:status :failed :request request :error message}))))))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:webapp/state :any]] :any :any]
                   :any]}
  dispatch!
  "Apply `action` to the app — the one place a control's press is turned into
  something happening.

  Public because it has TWO callers and they must not differ: the headless
  driver's `:dispatch`, and the browser shim's single registered dispatcher.
  That is the whole claim of this capability stated as a var — the screen a test
  drives and the screen a reader clicks reach the same function.

  **The split it makes is read from DATA.** Three kinds, and which one an action
  is comes out of `:webapp/actions` rather than out of this function:
  `:effectful?` is a REQUEST and goes through [[perform!]]; `:leaves?` hands the
  page back to the browser through `:webapp/leave!`, for the control whose
  destination is not a client route at all; anything else is a state transition
  and goes through `:webapp/act`. The distinction is not a
  judgement made here — it is looked up, because slopp-ui measured what happens
  when it is written by hand: their browser shell and their headless shell each
  carried `(= :try/execute (first action))`, in a `:cljs` namespace whose only
  verification is that it compiled, and the comment beside it already knew the
  risk — *both dispatchers have to make the same split, or the screen a test
  drives and the screen a browser shows differ on the one control that DOES
  something*. A lookup cannot disagree with the map it looks in.

  An effect does NOT also run the reducer. Running both is how two dispatchers
  drift back apart: each does the half its author was thinking about.

  Three refusals rather than three silences, because a control that appears to
  exist and does nothing is the failure a headless drive exists to catch — and
  the one their `.closest` listeners produced for real."
  [app action value]
  (let [{:webapp/keys [state act render actions request-for url-for leave!]} app]
    (cond
      (:leaves? (get actions (first action)))
      (if url-for
        ;; a nil url DECLINES, the same channel a nil request is — an unarmed
        ;; switcher, a selection not yet made. Throwing would push every app's
        ;; arming pattern into the browser
        (when-let [url (url-for @state action)]
          (leave! url))
        (throw (ex-info (str (pr-str (first action)) " is declared :leaves? but"
                             " this app has no :webapp/url-for — an action that"
                             " hands the page back to the browser with nowhere"
                             " to go is a control that cannot work. Declare"
                             " (fn [state action] -> url).")
                        {:webapp/missing-key :webapp/url-for
                         :action action})))

      (:effectful? (get actions (first action)))
      (if request-for
        (perform! app (request-for @state action))
        (throw (ex-info (str (pr-str (first action)) " is declared :effectful?"
                             " but this app has no :webapp/request-for — an"
                             " effect with no request to make is a control that"
                             " cannot work, and doing nothing quietly is the one"
                             " outcome ruled out. Declare (fn [state action] ->"
                             " request).")
                        {:webapp/missing-key :webapp/request-for
                         :action action})))

      act
      (do (swap! state act action value)
          (render @state))

      :else
      (throw (ex-info (str "this app dispatched " (pr-str action)
                           " and declares no :webapp/act — an action with nothing"
                           " to apply it is a control that silently does nothing,"
                           " which is the one failure a headless drive exists to"
                           " catch")
                      {:webapp/missing-key :webapp/act :action action})))))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:webapp/state :any]] :keyword :any [:? :any]]
                   :any]}
  load!
  "Run one keyed load with the machinery: `:absent` → `:loading` → `:ready` or
  `:failed`, a minted token, and the supersession guard.

  `f` is `(fn [ok err])` — callbacks, for [[navigate!]]'s reason: a promise is
  not a thing the JVM oracle has. `xform` is applied to the value INSIDE the
  freshness guard, so work derived from an answer nobody is waiting on is never
  paid for.

  **Public because scope is the app's question and the machinery is not.** The
  loop runs `:main` through here on every navigation; an app runs its own loads
  through here too — a nav pane, a signed-in user, anything fetched once and
  used on several screens. Declaring the key in `:webapp/session-loads` keeps
  its entry across [[arrive]]; everything else about it is identical.

  **The MACHINERY travels and the SCOPE does not**, which matters to anyone
  adopting this incrementally. This function reads two keys — `:webapp/state`
  and `:webapp/render` — so it works against a hand-built map from an app that
  has never called [[wiring]]. `:webapp/session-loads` does not travel with it:
  the guarantee is honoured by [[arrive]], so **an app that navigates with its
  own code gets no scope guarantee from declaring it**, and a load survives or
  dies by whatever that code does to `:loads`.

  Reported by the app it was written for, which read the sentence above three
  times without noticing it named a function they do not call. The sentence was
  exact; what it lacked was its own precondition, and a guarantee stated where
  it is IMPLEMENTED reads as unconditional at the point it is CONSUMED. Adopting
  `load!` alone is safe and useful; adopting `session-loads` alone is a fix that
  appears to work and then quietly does not, which is worse than the bug it
  replaces.

  **This exists because the alternative was measured, in the only real webapp
  built on slopp.** Their module nav is fetched once and read on every Code
  screen. Because loads were emptied on every navigation they kept it outside
  the load machinery, and outside it the load acquired: `(nil? value)` as its
  guard — absent, failed and answered-with-nothing collapsed into one value; a
  silent retry loop, because a swallowed failure leaves the value nil so every
  later navigation fetches again and a failing endpoint is hit forever with the
  reader told nothing; and no freshness token, in the one place that app
  fetched outside the loop. Three of the defects this namespace exists to
  prevent, caused by a SCOPE rule pushing a load out of the building.

  The rule that replaced it is slopp-ui's: **authorship decides who owns the
  MECHANISM; scope is a separate question and is never derivable from who does
  the writing.**"
  ([app key f] (load! app key f identity))
  ([app key f xform]
   (let [{:webapp/keys [state render]} app]
     (swap! state begin-load key)
     (let [token  (get-in @state [:loads key :token])
           fresh? (fn [] (= token (get-in @state [:loads key :token])))
           write! (fn [m] (when (fresh?)
                            (swap! state update-in [:loads key] merge m)
                            (render @state)))]
       (f (fn [value] (write! {:status :ready :value (xform value)}))
          (fn [message] (write! {:status :failed :error message})))))))

(defn ^:export load-value
  "The value load `key` answered with, or nil when it has not answered.

  Read this WITH [[load-status]], never instead of it. `nil` here is three
  different facts — nothing was asked, it is still being asked, it answered
  with nothing — and telling them apart is the whole reason the status exists.
  A view that branches on this alone has rebuilt the nil-pun the four states
  were added to remove."
  [state key]
  (get-in state [:loads key :value]))

(defn ^:export prefixed
  "`path` addressed under the app's mount point — the url a browser should see.

  The pair of [[strip-base]], and written as a pair deliberately: two functions
  written apart are two guesses, and the failure they prevent is a link that
  works when the app is served at the root and 404s behind a proxy, which is
  the one arrangement nobody tests locally.

  `\"\"` is the ordinary case and costs nothing — an app served at the root
  prefixes every url with the empty string."
  [base path]
  (str base path))

(defn ^:export strip-base
  "The app path inside `url-path`, or nil when the url is not under the mount
  point at all.

  The pair of [[prefixed]]; the round trip is what the tests assert.

  **nil rather than the path unchanged**, because those are different
  statements. Returning it would claim a foreign url is one of this app's
  paths, and the caller deciding whether a click is ours would then follow a
  link off its own site.

  **The prefix has to match at a SEGMENT boundary.** A plain `starts-with?`
  says `/p/xylophone` is under `/p/x`, which is how an app mounted at one slug
  starts routing another slug's urls into its own screens — a wrong answer that
  renders rather than erroring.

  The mount point itself is the app's `/`, not the empty string: an empty path
  routes to nothing and renders a blank page at a url that looks right."
  [base url-path]
  (let [b (str base)
        p (str url-path)]
    (cond
      (= "" b) p

      (= p b) "/"

      (str/starts-with? p (str b "/"))
      (let [rest* (subs p (count b))]
        (if (= "/" rest*) "/" rest*))

      :else nil)))

(defn ^:export app-path
  "The app path a browser is currently showing: `pathname` with the mount point
  off, and `search` rejoined — or nil when the url is not this app's.

  **The query string is half the address and the browser keeps it somewhere
  else.** A path built from `location.pathname` alone routes
  `/store/search?q=rate` to a search screen with an empty query — a box that
  forgot what was typed between pressing enter and the page loading. slopp-ui
  paid for that one; it is here so nobody re-derives it.

  What makes it expensive is that it LOOKS correct: the screen renders, the
  address bar is right, and only the contents of one field are wrong.

  The mount point comes off the PATHNAME, before the join, so a query that
  happens to contain the base as text is left alone.

  `:cljc`, which is the point — the browser splits a url across two properties
  and the shim's whole job is reading them. The decision about what to do with
  them is here, where a JVM test can see it."
  [base pathname search]
  (when-let [p (strip-base base pathname)]
    (let [q (str search)]
      (if (= "" q) p (str p q)))))

(defn ^:export
  ^{:malli/schema [:=> {:throws [:webapp/no-mount-point]} [:cat :any] :any]}
  mount-point
  "`el` if the page has a mount point, or a refusal naming what is missing.

  A browser entry's very first act is to find the element it renders into. When
  the server's template did not render one, every browser answers the same way:
  `Cannot read properties of null (reading 'getAttribute')`, in a console nobody
  has open, under a blank page, naming a property instead of the thing that is
  absent. The reader sees an app that does not start.

  This is the constructor refusal [[wiring]] makes, one input further out — and
  it belongs to the same rule: *the mistake is made here, so here is where it is
  named.*

  **`:cljc` despite being about a DOM node**, which is the interesting part.
  `nil?` is not a platform question — unlike \"is this an atom\", which is why
  that check lives one platform down in `slopp.web.screen/open!`. So the element
  crosses this boundary as an OPAQUE value, nothing here asks what it is, and
  the refusal a browser app depends on is an ordinary in-image test.

  Deliberately identity for anything non-nil. Checking that a value is really an
  Element would be a platform question, and would buy nothing: the next line
  reads an attribute off it, and a wrong node fails there with its own name."
  [el]
  (when (nil? el)
    (throw (ex-info (str "this page has no element with id=\"app\" — a browser"
                         " entry mounts into one, and the server's template is"
                         " what renders it. Without this you get a blank page"
                         " and a null property read in the console.")
                    {:webapp/no-mount-point true})))
  el)

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:sequential [:tuple :string :any]] [:maybe :string]]
                   [:maybe [:map [:screen :any] [:params [:map-of :keyword :string]]]]]}
  match-route
  "Match `path` against a declared route TABLE — `[[\"/things/:id\" target] …]` —
  answering `{:screen target :params {…}}`, or nil for a path this app does not
  route.

  **The client half of routing, as DATA.** The server half already is: a handler
  declares `^{:web/path \"/store/ns/:ns\"}` and the table is derived from var
  metadata, which is why an endpoint can be listed, collision-checked and joined
  against a link. The client half was one opaque `(fn [path] …)`, so none of
  that was possible for it — and both halves live in the same application.

  The `target` is OPAQUE here, deliberately. What a screen IS belongs to the
  layer above; a matcher that never asks cannot be wrong about the answer.

  **Same pattern grammar as the server**, and pinned by a test that runs both
  over one table: a `:seg` captures one segment, a trailing `*rest` captures the
  remainder, precedence is fewest-captures-wins so adding a route can never
  steal an existing one, and a trailing slash is tolerated because a browser
  produces both. Two implementations rather than one, because `slopp.web.router`
  ships in the `http` family and this ships in `webapp`: a store may vendor
  either without the other, so a require across them is a load failure in
  whichever store has one half. The agreement is asserted instead.

  **The QUERY is parsed here, not left to the app**, through the same
  `slopp.lang/query-params` the server uses — one grammar, both sides. Path
  captures and query keys land in ONE map, which is the shape a screen wants:
  `/things/42?tab=logs` is a thing and a tab, and which half of the url each
  arrived in is the browser's business rather than the app's.

  A path capture WINS a collision with a query key of the same name. The path is
  the address; a query string is something anyone can append to it."
  [routes path]
  (let [raw     (str path)
        cut     (str/index-of raw "?")
        p       (if cut (subs raw 0 cut) raw)
        query   (when cut (subs raw (inc cut)))
        segs    (fn [s] (vec (remove str/blank? (str/split (str s) #"/"))))
        u       (segs p)
        cap?    #(str/starts-with? % ":")
        splat?  #(str/starts-with? % "*")
        rank    (fn [ps] (+ (count (filter cap? ps))
                            (* 100 (count (filter splat? ps)))))
        try-row (fn [[pattern target]]
                  (let [ps (segs pattern)]
                    (when (if (some splat? ps)
                            (>= (count u) (count ps))
                            (= (count ps) (count u)))
                      (loop [ps ps, u u, params {}]
                        (cond
                          (empty? ps)
                          (when (empty? u)
                            {:screen target :params params :rank (rank (segs pattern))})

                          (splat? (first ps))
                          (when (and (= 1 (count ps)) (seq u))
                            {:screen target
                             :params (assoc params (keyword (subs (first ps) 1))
                                            (str/join "/" u))
                             :rank   (rank (segs pattern))})

                          (cap? (first ps))
                          (recur (rest ps) (rest u)
                                 (assoc params (keyword (subs (first ps) 1)) (first u)))

                          (= (first ps) (first u))
                          (recur (rest ps) (rest u) params)

                          :else nil)))))]
    (when-let [hit (first (sort-by :rank (keep try-row routes)))]
      {:screen (:screen hit)
       ;; query first, so a PATH capture wins the collision
       :params (merge (lang/query-params query) (:params hit))})))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map
                          [:webapp/href {:optional true} [:maybe :string]]
                          [:webapp/button {:optional true} [:maybe :int]]
                          [:webapp/meta? {:optional true} [:maybe :boolean]]
                          [:webapp/ctrl? {:optional true} [:maybe :boolean]]
                          [:webapp/shift? {:optional true} [:maybe :boolean]]
                          [:webapp/alt? {:optional true} [:maybe :boolean]]]
                    :string [:sequential [:tuple :string :any]]]
                   [:maybe :string]]}
  click-target
  "The app path this click should navigate to, or nil when the browser should
  handle it itself.

  Four independent judgements, and each is a real behaviour rather than a
  condition in a chain:

  - **a plain LEFT click.** A middle-click opens a tab and a cmd/ctrl/shift/alt
    click opens a tab, a window or a download. Hijacking either is a browser
    that lies about what its own gestures do.
  - **an href at all.** A click on a button is not a navigation.
  - **IN-APP only.** Calling `preventDefault` on an external link produces one
    that looks live and does nothing.
  - **ROUTED only.** An unrouted in-app path falls through to the server, so a
    wrong url stays a 404. Swallowing it turns every typo into a blank screen at
    a plausible url, which is the SPA failure that makes a site feel broken
    rather than missing.

  Written here, in `:cljc`, because in a browser shell this is a chain of `and`s
  in a namespace whose only verification is that it compiled — and it is four
  behaviours, each of which a reader will notice and none of which anything
  could check there.

  **The four modifiers arrive RAW rather than pre-combined**, and that is the
  same rule one level down. `(or metaKey ctrlKey shiftKey altKey)` looks like
  plumbing and is a judgement: which gestures mean \"open elsewhere\" is a fact
  about browsers and platforms — cmd on a mac, ctrl everywhere else, shift a new
  window, alt a download — and it differs per key. A shim that combined them
  would be deciding, in the one place nothing can check. It reads four
  properties and names them; the answer is here.

  The click arrives as slopp's own shape rather than a DOM event, and the keys
  are namespaced for the reason every boundary map here is: the shim reads
  properties off an event and names them, so what crosses into this function is
  data with an owner rather than a browser object with a shape nobody declared."
  [{:webapp/keys [href button meta? ctrl? shift? alt?]} base routes]
  (when (and href
             (or (nil? button) (zero? button))
             (not meta?) (not ctrl?) (not shift?) (not alt?))
    (when-let [path (strip-base base (str href))]
      (when (match-route routes path) path))))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map
                          [:webapp/state :any]
                          [:webapp/base {:optional true} :string]
                          [:webapp/routes :any]
                          [:webapp/fetch :any]
                          [:webapp/render :any]
                          [:webapp/push-url! :any]
                          [:webapp/derive {:optional true} :any]]
                    :string :boolean]
                   :any]}
  navigate!
  "Move `app` to `path`, performing every effect through the app's own plug-ins.

  Nothing here touches a browser, and that is the entire point: `:webapp/render`
  and `:webapp/push-url!` are `js/…` calls in a real page and recorded values in
  a test, so \"following this link pushes that url, asks for that endpoint, and
  shows loading until it answers\" is an ordinary in-image assertion — including
  the slow-response race, which in a real browser is a heisenbug you reproduce
  by throttling the network.

  **The screen's own data is the `:main` load**, run through [[load!]] like any
  other, so the machinery is one implementation rather than one for the loop and
  one for everybody else. That was not true of the first version, and what it
  cost is written up in `load!`.

  **`:webapp/fetch` takes CALLBACKS rather than returning a promise, and the
  reason travels with the decision because it reads as arbitrary style
  otherwise.** A promise is not a thing the JVM oracle has. A loop that could
  only run in a browser would put the whole headless exercise back where it
  started, which is the one outcome this capability exists to prevent. An app
  whose HTTP layer is promise-shaped adapts at this seam — that is what the seam
  is for.

  **`:webapp/derive` is applied inside the freshness guard, not inside the
  fetch.** The two read as equivalent and are not: the fetch runs before
  anything knows whether the answer is still wanted, so deriving there pays for
  every abandoned load."
  [{:webapp/keys [state base routes fetch render push-url! derive
                  address-keys session-loads] :as app}
   path push?]
  (when push? (push-url! (prefixed base path)))
  (swap! state arrive path (match-route routes path) address-keys session-loads)
  (render @state)
  (when (:screen @state)
    (let [{:keys [screen params]} @state]
      (load! app :main
             (fn [ok err] (fetch screen params ok err))
             (fn [value] (if derive (derive screen value) value))))))

(defn- navigate-for!
  "The driver's `:navigate`, partial'd over `app`.

  A named var rather than a closure inside [[driver]], and the reason is the
  `!`-naming rule rather than taste: a constructor that builds mutating
  closures INLINE computes as effectful itself, so the gate would demand
  `driver!` — a name asserting that calling it does something, when calling it
  only assembles a map. Naming the behaviours puts the `!` where the mutation
  actually is and leaves the assembler honest.

  Returns the state AFTER the move, because that is what `screen` re-renders."
  [app _state path]
  (navigate! app path false)
  @(:webapp/state app))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:webapp/state :any] [:webapp/view :any]]]
                   [:map [:state :any] [:view :any]
                    [:navigate :any] [:dispatch :any] [:boot :any]]]}
  driver
  "The headless DRIVER's map, derived from the app's own wiring.

  `slopp.web.screen/open!` takes `{:state :view :navigate :dispatch :boot}`.
  That is not the same map as [[wiring]], and it should not be: **the wiring is
  what an app IS, and this is what a DRIVER needs.** Different levels, so the
  answer is a derivation rather than making either learn the other's shape.

  **This function exists because the alternative was measured.** slopp-ui wrote
  the derivation by hand — about thirty lines — because slopp shipped the driver
  contract and no way to reach it from a declared app. That adapter was a THIRD
  hand-written wiring beside the browser entry and the headless driver, with
  nothing comparing the three, in a project whose whole point is that a headless
  drive and a real browser must not disagree. Four of the five keys are
  mechanical; only `:boot` was ever theirs.

  Unqualified keys OUT, namespaced keys IN, and the asymmetry is the seam rather
  than an inconsistency: `screen`'s contract is older, shipped, and used by
  server-rendered apps that have no wiring at all.

  `:boot` is defaulted by [[wiring]] rather than here. It used to be `(or boot
  identity)` at this one caller, which was correct and became wrong the moment
  there were three: the browser entry and [[start!]] would each have written the
  same `or`, and one of them lives where nothing can check it.

  **`:dispatch` is [[dispatch!]], and so is the browser's.** That is this
  capability's claim reduced to a var: the three-way split between a request, a
  leave and a state transition is read out of `:webapp/actions` by one function,
  which both drivers call. Two hand-written dispatchers have to make that split
  identically or the screen a test drives and the screen a reader clicks differ
  on the one control that DOES something — and slopp-ui had exactly that, the
  same `(= :try/execute (first action))` in two shells, one of them `:cljs`."
  [app]
  (let [{:webapp/keys [state view boot]} app]
    {:state    state
     :view     view
     ;; the app's own atom is the one that moves; `screen` re-reads what comes
     ;; back, which is why these return the state rather than a fresh map
     :navigate (partial navigate-for! app)
     :dispatch (partial dispatch! app)
     :boot     boot}))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:webapp/routes :any]] [:map] ifn?]
                   :any]}
  click!
  "Handle a click the browser just delivered: navigate if it is ours, and leave
  it entirely alone if it is not.

  [[click-target]] answers WHICH clicks are ours; this is what happens next, and
  it is the half that goes wrong invisibly. Three failures, all of which
  compile:

  - `preventDefault` on a link that is NOT ours — a link that looks live and
    does nothing, which is worse than a slow one because nothing reports it.
  - no `preventDefault` on one that IS — the browser also loads the page, so
    every in-app click restarts the application and the state it was holding.
  - navigating without pushing — the address bar disagrees with the screen, and
    a reload or a shared url lands somewhere else.

  **`prevent!` arrives as a THUNK rather than being called by the shim.** The
  shim's alternative is `(when (click-target …) (.preventDefault e) …)`, which
  puts the decision back in the one namespace whose only verification is that it
  compiled. Passing the effect in means the branch lives here, where a test
  counts the calls — and \"was the default swallowed for this click and not that
  one\" becomes an ordinary assertion instead of a thing you check by clicking.

  Returns the path navigated to, or nil — so a caller that wants to know whether
  the app took the click can ask."
  [{:webapp/keys [base routes] :as app} click prevent!]
  (when-let [path (click-target click base routes)]
    (prevent!)
    (navigate! app path true)
    path))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:webapp/routes :any]]
                    [:maybe :string] [:maybe :string] :boolean]
                   :any]}
  navigate-url!
  "Show whatever the browser's URL now names — the app's entry from an ADDRESS
  rather than from a path.

  Two callers, which is why it exists rather than being inlined at each: the
  first render at mount, and the back button. Both hand over a `pathname` and a
  `search` because that is how a browser keeps a url, and both must reach the
  same routing decision or the screen a reader lands on and the screen they come
  back to differ.

  **`push?` is false for both**, and the back button is the reason it is a
  parameter rather than a constant. Pushing on a POP is the bug that makes back
  appear broken: every press adds a history entry, so the button walks the
  reader forward through their own history and never leaves the app.

  **A url outside the mount point does NOTHING.** [[app-path]] answers nil there,
  and routing nil would clear the screen the reader was on — a blank page caused
  by an address that was never this app's to show.

  The query string is carried, which is [[app-path]]'s whole subject: the two
  halves of an address live in different properties and a handler that reads one
  routes `/search?q=rate` to an empty box while LOOKING correct."
  [{:webapp/keys [base] :as app} pathname search push?]
  (when-let [path (app-path base pathname search)]
    (navigate! app path push?)
    path))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:webapp/state :any] [:webapp/boot :any]]
                    [:maybe :string] [:maybe :string]]
                   :any]}
  start!
  "Everything a browser entry does when the bundle runs: let the app start what
  belongs to no particular screen, then show the url the reader arrived at.

  Two steps, and the ORDER is the decision. `:webapp/boot` is where an app
  begins the loads that belong to the session rather than to a route — the
  reader's identity, the project list, the thing every screen's chrome shows —
  and the first screen may read them. Routing first would render that screen
  against a state the app had not started yet, which is the four-state reader's
  `:absent` arriving as a flash of empty chrome on every page load.

  **Read-call-write, never inside `swap!`**, matching `slopp.web.screen/open!`
  exactly. Boot is the app's own code; `swap!` demands a pure function and may
  retry, and an entry point that starts a fetch is neither.

  This is the `:cljc` half of the browser entry, which is the point: the shim
  reads two properties off `location` and calls this. A page load is the one
  moment an app has no reader to notice it went wrong, so it is the last place
  a decision should live somewhere nothing can check."
  [{:webapp/keys [state boot] :as app} pathname search]
  (reset! state (boot @state))
  (navigate-url! app pathname search false))
