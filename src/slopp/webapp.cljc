(ns slopp.webapp
  "The browser application as DATA — the loop a webapp runs, performed through
  plug-ins instead of through a browser.

  An app declares what it IS (state, routes, view, how a screen's data is
  fetched, how an action changes state) and slopp performs the sequencing that
  has to happen around those: push the url, transition, render, fetch, render
  again. Every step calls a function the app supplied, so `:render` and
  `:push-url!` are `js/…` in a real page and recorded values in a test.

  **Generalized from slopp-ui's own `spa`/`nav`, which were further along than
  anything slopp had.** They built it because slopp shipped a fake browser
  whose contract did not fit, then wrote a ~30-line adapter to bridge the two —
  a THIRD hand-written wiring beside the browser entry and the headless driver,
  with nothing comparing the three. [[driver]] is what removes it.

  **Callbacks, not promises, and the reason travels with the decision.** A
  promise is not a thing the JVM oracle has, so a promise-based loop could only
  ever run in a browser — which puts the whole headless exercise back where it
  started. This reads as an arbitrary style choice right up until someone
  modernises it, which is why the reason is written here rather than assumed.

  **Renderer-agnostic, deliberately.** This never learns what a screen looks
  like. The moment it does, it is presentation and belongs to the app.")

(defn- arrive
  "The state transition a navigation IS: `path` and its route in, everything the
  previous screen loaded out.

  Pure, and separate from [[navigate!]] for the reason every decision in this
  codebase is separate from its performance — what a navigation MEANS is
  assertable without an atom, a plug-in or a clock.

  **`:loads` is bumped rather than cleared**, and that is the freshness token.
  A navigation that supersedes a slow one must make the slow one's answer
  unwanted, and an answer arriving for a token nobody is waiting on is dropped
  rather than written over the new screen. Clearing would let the old answer
  match the new screen's empty token and land."
  [state path route]
  (-> state
      (assoc :path   path
             :screen (:screen route)
             :params (:params route)
             :data   nil
             :error  nil)
      (update-in [:loads :main] (fnil inc 0))))

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

  **`:webapp/fetch` takes CALLBACKS rather than returning a promise, and the
  reason travels with the decision because it reads as arbitrary style
  otherwise.** A promise is not a thing the JVM oracle has. A loop that could
  only run in a browser would put the whole headless exercise back where it
  started, which is the one outcome this capability exists to prevent. An app
  whose HTTP layer is promise-shaped adapts at this seam — that is what the seam
  is for.

  **`:webapp/derive` is inside the freshness guard, not inside the fetch.** The
  two read as equivalent and are not: the fetch runs before anything knows
  whether the answer is still wanted, so deriving there pays for every abandoned
  load. An expensive value computed FROM a response — a layout, a parse — is
  guarded by the same check as the write it accompanies."
  [{:webapp/keys [state base routes fetch render push-url! derive]} path push?]
  (when push? (push-url! (str base path)))
  (swap! state arrive path (routes path))
  (render @state)
  (when (:screen @state)
    (let [{:keys [screen params]} @state
          token  (get-in @state [:loads :main])
          fresh? (fn [] (= token (get-in @state [:loads :main])))]
      (fetch screen params
             (fn [value]
               (when (fresh?)
                 (swap! state assoc :data (if derive (derive screen value) value))
                 (render @state)))
             (fn [message]
               (when (fresh?)
                 (swap! state assoc :error message)
                 (render @state)))))))

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
  - `:webapp/routes` — `(fn [path] -> {:screen :params} | nil)`. nil is a real
    answer: a path this app does not route.
  - `:webapp/view` — `(fn [state] -> hiccup)`. Pure, so a JVM can call it.

  Optional, defaulted here so no caller has to nil-check a plug-in:
  `:webapp/base` (mount prefix, `\"\"`), `:webapp/fetch` (a screen's data;
  answers nil when a screen needs none), `:webapp/render` and
  `:webapp/push-url!` (no-ops headless, `js/…` in a page), plus
  `:webapp/derive`, `:webapp/call`, `:webapp/act`, `:webapp/actions` and
  `:webapp/boot`.

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
                  :webapp/call :webapp/act :webapp/actions :webapp/boot}
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
                               :webapp/routes " — (fn [path] {:screen :params}); without one no path means anything"
                               :webapp/view   " — (fn [state] hiccup); without one there is no screen to read"))
                        {:webapp/missing-key k}))))
    (merge {:webapp/base      ""
            :webapp/fetch     (fn [_screen _params ok _err] (ok nil))
            :webapp/render    (fn [_state] nil)
            :webapp/push-url! (fn [_url] nil)}
           app)))

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

(defn- dispatch-for!
  "The driver's `:dispatch`, partial'd over `app` — see [[navigate-for!]] for
  why it is a named var rather than a closure.

  An app with no `:webapp/act` REFUSES rather than doing nothing. A control that
  silently does nothing is the exact failure a headless drive exists to catch,
  and swallowing it here would make the fake agree with a browser about a button
  that works in neither."
  [app action value]
  (let [{:webapp/keys [state act render]} app]
    (if act
      (do (swap! state act action value)
          (render @state))
      (throw (ex-info (str "this app dispatched " (pr-str action)
                           " and declares no :webapp/act — an action with nothing"
                           " to apply it is a control that silently does nothing,"
                           " which is the one failure a headless drive exists to"
                           " catch")
                      {:webapp/missing-key :webapp/act :action action})))))

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

  `:boot` defaults to `identity` — an app that starts nothing at page load is
  ordinary, and a nil there would be refused by `open!` as a non-callable rather
  than understood as \"nothing to do\".

  **The effectful-dispatch split is not here yet.** slopp-ui's shell routes an
  action declared `:effectful?` through a request-performing sibling rather than
  through the state reducer, and both their shells have to make that split
  identically or the screen a test drives and the screen a browser shows differ
  on the one control that DOES something. It is the next slice, with its own
  red-first test — writing the branch now would ship an untested path in the
  function whose entire purpose is that two paths cannot disagree."
  [app]
  (let [{:webapp/keys [state view boot]} app]
    {:state    state
     :view     view
     ;; the app's own atom is the one that moves; `screen` re-reads what comes
     ;; back, which is why these return the state rather than a fresh map
     :navigate (partial navigate-for! app)
     :dispatch (partial dispatch-for! app)
     :boot     (or boot identity)}))
