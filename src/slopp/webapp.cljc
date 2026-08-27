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
  like. The moment it does, it is presentation and belongs to the app." (:require [clojure.string :as str] [slopp.lang :as lang] [clojure.walk :as walk]))

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
           :loads  ;; `(keys …)` because `session-loads` is a MAP of declared loads now:
                   ;; it says what each one IS as well as that it outlives a
                   ;; screen, which were always the same statement. Seq'd
                   ;; directly it hands `select-keys` MapEntries and keeps
                   ;; nothing
                   (select-keys (:loads s) (keys session-loads)))))

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
                   [:cat [:map [:webapp/state :any]] :keyword :any [:? :any]]
                   :any]}
  load!
  "Run one keyed load with the machinery: `:absent` → `:loading` → `:ready` or
  `:failed`, a minted token, and the supersession guard.

  `f` is `(fn [ok err])` — callbacks, for [[navigate!]]'s reason: a promise is
  not a thing the JVM oracle has.

  `opts` is `{:xform :check}`, both optional and both applied INSIDE the
  freshness guard, so work derived from an answer nobody is waiting on is never
  paid for:

  - `:xform` — `(fn [value] value')`, the shaping a caller wants.
  - `:check` — `(fn [value] -> nil | message)`. nil accepts the answer; a
    MESSAGE rejects it, and the load becomes `:failed` carrying that message
    with `:xform` never run.

  **A rejection is a VALUE, not a throw, and the reason is structural rather
  than stylistic.** A caller's obvious move is to validate inside `:xform` and
  throw — and that produces two different behaviours from one function. Headless
  the performer calls `ok` synchronously, so the throw propagates out of here
  and takes the driver with it; in a page `ok` is called from inside a `.then`,
  so the same throw lands in the shim's `.catch` and becomes a rendered failure
  screen. Same code, two outcomes, which is the one difference this capability
  exists to prevent. It cannot be closed by catching either: a `:cljc` form
  cannot catch on both platforms without the reader conditional D3 denies. So
  the failure channel is a return value, and nothing throws anywhere.

  Reported by the app that lost its response validation when the framework took
  over performing — its generated wrappers threw on a contract violation, and
  the four-state model had been turning that into the failed screen.

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
  ([app key f] (load! app key f {}))
  ([app key f opts]
   ;; a bare fn was the old fourth argument, and it is refused rather than
   ;; accepted alongside: two shapes for one slot is how a caller writes the
   ;; one that silently does less
   ;; asked as "is this a map" rather than "is this callable", because a MAP
   ;; is `ifn?` — the same trap `as-screen` refuses a keyword row target for,
   ;; met here by the guard written to catch it
   (when-not (map? opts)
     (throw (ex-info (str "load!'s fourth argument is an OPTS MAP now — {:xform"
                          " (fn [value] value') :check (fn [value] nil-or-message)}."
                          " A bare xform fn was the old shape; the map exists"
                          " because a screen needs to REJECT an answer, and a"
                          " rejection cannot be a throw: headless it escapes"
                          " load! and in a page it becomes a failure screen.")
                     {:webapp/retired-shape :load-xform-fn})))
   (let [{:webapp/keys [state render]} app
         {:keys [xform check]} opts]
     (swap! state begin-load key)
     (let [token  (get-in @state [:loads key :token])
           fresh? (fn [] (= token (get-in @state [:loads key :token])))
           write! (fn [m] (when (fresh?)
                            (swap! state update-in [:loads key] merge m)
                            (render @state)))]
       (f (fn [value]
            ;; the guard is asked BEFORE the check, so a superseded answer is
            ;; not validated either — the app this came from had generated
            ;; wrappers validating ahead of the guard, so an abandoned load
            ;; paid for its own validation
            (when (fresh?)
              (if-let [msg (and check (check value))]
                (write! {:status :failed :error msg})
                (write! {:status :ready :value ((or xform identity) value)}))))
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
  that check lives one platform down in `slopp.cljnx/open!`. So the element
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
  declares `^{:http/path \"/store/ns/:ns\"}` and the table is derived from var
  metadata, which is why an endpoint can be listed, collision-checked and joined
  against a link. The client half was one opaque `(fn [path] …)`, so none of
  that was possible for it — and both halves live in the same application.

  The `target` is OPAQUE here, deliberately. What a screen IS belongs to the
  layer above; a matcher that never asks cannot be wrong about the answer.

  **Same pattern grammar as the server**, and pinned by a test that runs both
  over one table: `:seg` captures one segment under that name, `*` matches
  exactly one segment and `**` zero or more, both anonymous, both END-ONLY, and
  both bound under `:*`. Precedence is POSITIONAL — rank each segment
  (literal 0 < `:name` 1 < `*` 2 < `**` 3) and compare left to right, shorter
  winning when one is a prefix of the other — so the longest static prefix wins
  and the answer never depends on the order the table happens to be in. A
  trailing slash is tolerated because a browser produces both.

  Two implementations rather than one, because `slopp.http.router`
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
        one?    #(= "*" %)
        many?   #(= "**" %)
        ;; a `*` this grammar does not have — never read as a literal segment,
        ;; for the reason the server states: a retired spelling that matches
        ;; the text `*path` answers the wrong requests instead of none
        ill?    (fn [ps]
                  (boolean (some (fn [[i s]]
                                   (and (str/includes? s "*")
                                        (or (not (or (one? s) (many? s)))
                                            (not= i (dec (count ps))))))
                                 (map-indexed vector ps))))
        rank    (fn [ps] (mapv #(cond (many? %) 3 (one? %) 2 (cap? %) 1 :else 0) ps))
        rank<   (fn [a b] (or (first (remove zero? (map compare a b)))
                              (compare (count a) (count b))))
        try-row (fn [[pattern target]]
                  (let [ps   (segs pattern)
                        done #(hash-map :screen target :params % :rank (rank ps))]
                    (when-not (ill? ps)
                      (loop [ps ps, u u, params {}]
                        (cond
                          (empty? ps)
                          (when (empty? u) (done params))

                          ;; zero or more, so this precedes the empty-path
                          ;; test — `/store/**` routes `/store` itself
                          (many? (first ps))
                          (done (assoc params :*
                                       ;; each segment encoded on its own, so
                                       ;; each decodes on its own and THEN
                                       ;; joins — the server does the same
                                       (str/join "/" (map lang/decode-component u))))

                          (empty? u) nil

                          (one? (first ps))
                          (when (= 1 (count u))
                            (done (assoc params :* (lang/decode-component (first u)))))

                          (cap? (first ps))
                          ;; DECODED, like the server's — a browser hands this
                          ;; matcher an encoded pathname, and the link it came
                          ;; from was built by `lang/encode-component`. Decoded
                          ;; after the split, never before: %2F is a slash in
                          ;; the VALUE and would otherwise re-segment the
                          ;; address. The two matchers must agree here as they
                          ;; do on the grammar, or one screen resolves a
                          ;; `register!` its server cannot.
                          (recur (rest ps) (rest u)
                                 (assoc params (keyword (subs (first ps) 1))
                                        (lang/decode-component (first u))))

                          (= (first ps) (first u))
                          (recur (rest ps) (rest u) params)

                          :else nil)))))]
    (when-let [hit (first (sort-by :rank rank< (keep try-row routes)))]
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
                   [:cat :string [:sequential [:tuple :string :any]] :any]
                   :any]}
  prefix-links
  "Rewrite every link in `hiccup` that this app ROUTES so it carries the mount
  point — leaving every other link exactly as written.

  **This is the join nothing could make before.** The boundary inventory reports
  it as an unchecked exit and says why: *the literal is a client route, the mount
  point arrives from the render, and a fallback answers the result — so a typo'd
  literal, a prefix that stopped being applied, and a client route nobody
  registered all look the same.* Two of those three parts are slopp's now — the
  mount point is declared and the route table is data — so the framework can do
  the prefixing, and a literal in a view becomes unambiguously a CLIENT ROUTE
  KEY. That is what makes it checkable. Every app writing its own `prefix-links`
  is what made it not.

  **Only links this app routes are touched, and the discrimination is the whole
  safety of it.** `/api/modules` is the server's path; prefixing it would break
  the one link the app cannot re-route. An external url is somebody else's
  entirely. Both are left alone because [[match-route]] says nothing matches
  them — the same table the click handler consults, so the two cannot disagree
  about which links are the app's.

  **`:href` and `:action`, on the same rule.** This was `:href` alone, on the
  reasoning that a form's `:action` submits to a server and rewriting one would
  point a POST at a screen. That is right about the POST and wrong about the
  scope, and the app it broke reported it: a search box written as a GET FORM is
  a NAVIGATION — a reducer is pure and navigating is an effect, so a form needs
  no dispatcher and no bundle at all — and its action is a client route that
  happens to be spelled `action` because a text box came with it.

  No second judgement is needed, which is why this is a widening rather than a
  new rule: the same `match-route` that keeps `/api/modules` from being prefixed
  as an href keeps `/api/save` from being prefixed as an action. The failure
  runs the safe way too — an action that should be prefixed and is not silently
  submits outside the app, while one that should not be matches no row.

  The round trip is the property worth stating: what this PREFIXES is exactly
  what [[click-target]] STRIPS and claims. If those two ever disagree, every link
  in the app is either dead or leaves the page — which is why they are tested
  against each other rather than separately."
  [base routes hiccup]
  (walk/postwalk
   (fn [node]
     (let [attrs (when (and (vector? node) (keyword? (first node)))
                   (second node))]
       (if (map? attrs)
         (reduce (fn [n k]
                   (let [v (get attrs k)]
                     (if (and (string? v) (match-route routes v))
                       (assoc-in n [1 k] (prefixed base v))
                       n)))
                 node
                 [:href :action])
         node)))
   hiccup))

(defn- derived-view
  "The `:webapp/view` slopp builds from an app's own parts: render the matched
  SCREEN, hand the result to the app's chrome, and prefix every in-app link.

  **This is what makes a route row point at a function rather than a keyword.**
  `:screen` used to be a bare keyword agreeing in three separate places — the
  routes fn returned it, the view cased on it, the fetch received it — with
  nothing checking any of the three. Rename one and the app renders a blank pane
  at a url that looks right. A row naming the screen's var collapses all three
  into one reference the graph can see, and there is no keyword left to mistype.

  **A screen is only called when its data is READY**, and the other three states
  are rendered by the framework: `:webapp/not-found` when no row matched,
  `:webapp/loading` while the `:main` load is out, `:webapp/failed` when it
  failed. That removes from every screen the same three-way `case` on
  `load-status` — the keyword-agreeing-in-three-places problem one level in.

  **Who renders a state, and who PLACES it, are different questions**, and the
  line between them is structural rather than aesthetic. The first answer here
  was \"slopp owns what is true, the app owns what is seen\", which slopp-ui
  showed does not survive its own precedent: what `not-found` looks like is
  presentation too, and slopp defaults that.

  The asymmetry that does hold: **`not-found` replaces the WHOLE page**, so the
  framework can render it outright — there is nothing else on screen to be wrong
  about. **`loading` and `failed` replace one PANE while the rest of the app
  stays up** — a reader who navigated with a module list should still see it
  while the main pane loads. So rendering those requires knowing WHERE they go,
  placement is layout, and layout is the one thing this capability does not
  take. Hence: the framework supplies the CONTENT as `inner`, and chrome decides
  where it sits.

  **`inner` is never nil.** A nil chrome has to test is the nil-pun again, in
  the one value every app handles; chrome asks `load-status` if it wants the
  distinction, which is the rule [[load-value]] already states.

  **Chrome takes the state as well as the inner hiccup**, so a nav bar can know
  which screen is current and what the session loaded without reaching for the
  atom it was handed.

  **[[prefix-links]] runs LAST, over the finished tree**, so a view writes client
  route keys and never the mount point. One producer: what this adds is exactly
  what [[click-target]] strips, which is why the two are tested against each
  other rather than separately."
  [{:webapp/keys [chrome not-found loading failed base routes]}]
  (fn [state]
    (let [screen (:screen state)
          inner  (if screen
                   (case (load-status state :main)
                     ;; :absent is the SCREEN, not a spinner: nothing was ever
                     ;; asked for, which is a screen that declared no :request
                     ;; or one whose :request declined. A spinner there would
                     ;; never end
                     (:absent :ready) ((:render screen) state)
                     :failed          (failed state)
                     (loading state))
                   (not-found state))]
      (prefix-links base routes (chrome state inner)))))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map
                          [:webapp/path :string]
                          [:webapp/path-params {:optional true} [:maybe [:map-of :keyword :any]]]
                          [:webapp/query {:optional true} [:maybe [:map-of :keyword :any]]]]]
                   :string]}
  request-url
  "The complete URL a request names — path parameters substituted, query
  appended, everything percent-encoded.

  **The performer receives a FINISHED string and decides nothing.** That is the
  whole design: whatever a browser shim computes is computed in the one
  namespace no JVM test can reach, so every judgement moved here is a judgement
  a reader can assert on. The app that prompted this had the same rule and
  stopped one step short — splitting a url into literal and encodable PARTS for
  its performer to join — because its encoder was `encodeURIComponent` and only
  existed in the browser. `slopp.lang/encode-component` is `:cljc`, so the join
  comes here too and the browser is left with `fetch`.

  **Substitution is SEGMENT-WISE, never `str/replace`.** A parameter whose name
  is a prefix of another corrupts the path: replacing `:m` in `/api/:module/:m`
  gives `/api/yodule/y`, which is a url, which is why it ships. Their docstring
  named this before either of us had a use for the fix, and the reason it is
  worth restating is that the corrupted result LOOKS correct.

  **A value cannot break out of its segment, and that is the security half.**
  `slopp.lang/percent-of` escapes every character outside RFC 3986's unreserved
  set, so a `/` in a value is data rather than structure and a `?` does not start
  a query. A request built in a browser is a string asserted nowhere; built here
  it is one an ordinary test reads.

  Non-ASCII passes through verbatim — `encode-component`'s documented limit,
  because asking a character for its code point is the platform question
  `slopp.lang` exists so nobody has to ask. A browser percent-encodes it as UTF-8
  before sending, so the wire is right; a caller needing exact bytes for a
  non-ASCII path segment needs a platform encoder and should say so where it is
  used.

  **An empty query appends nothing.** A trailing `?` makes a url that differs
  from the one a reader typed, which is a cache key nobody predicted."
  [{:webapp/keys [path path-params query]}]
  (let [enc   lang/encode-component
        seg   (fn [s]
                (cond
                  (str/starts-with? s ":")
                  (enc (get path-params (keyword (subs s 1))))

                  ;; a REMAINDER, not a segment. `encode-component` escapes
                  ;; `/` — right for a segment, where a slash is data trying
                  ;; to become structure, and wrong here, where it IS
                  ;; structure. Both matchers decode each sub-segment on its
                  ;; own and THEN join, so this encodes each on its own and
                  ;; then joins, or the round trip stops closing. `**` matches
                  ;; zero segments, so an absent value is legal and empty
                  (= s "**")
                  (str/join "/" (map enc (remove str/blank?
                                                 (str/split (str (:* path-params)) #"/"))))

                  ;; one segment, so it encodes whole like a named capture
                  (= s "*") (enc (:* path-params))

                  :else s))
        parts (map seg (str/split (str path) #"/"))
        q     (->> (sort-by key (or query {}))
                   (map (fn [[k v]] (str (enc (name k)) "=" (enc v))))
                   (str/join "&"))]
    (str (str/join "/" parts)
         (when (seq q) (str "?" q)))))

(defn as-screen
  "The screen VALUE a route row names — a map with `:render`, whatever the row
  was written as.

  Two ways to write a screen, and the short one is not the lesser one:

  - `(fn [state] hiccup)` — the whole declaration for a screen whose data the
    app already has.
  - `{:render (fn [state] hiccup)
      :request (fn [params] -> request | nil)
      :check   (fn [response] -> nil | message)
      :derive  (fn [response] -> value)}` — a screen that asks for something.

  `:request` is the seam this exists for. It is PURE and it is `:cljc`, so
  which call a screen makes is a fact an in-image test reads rather than a
  `js/fetch` in a namespace whose only verification is that it compiled. Its
  answer goes to [[request-url]] and then to `:webapp/call`, which the browser
  entry supplies — so an app that opts into this writes no ClojureScript to
  fetch its own data. A nil request DECLINES, the same channel [[perform!]]
  uses: a screen that has nothing to ask for right now waits for nothing.

  `:check` is how a screen REFUSES what it was sent — nil accepts, a message
  rejects and the load becomes `:failed` carrying it, with `:derive` never run.
  It exists because validating inside `:derive` and throwing produces two
  different behaviours from one function: headless the performer calls `ok`
  synchronously so the throw escapes [[load!]] and takes the driver with it,
  while in a page `ok` is called from inside a `.then` and the same throw
  becomes a rendered failure screen. A rejection is a VALUE for that reason, and
  it cannot be fixed by catching — a `:cljc` form cannot catch on both platforms
  without the reader conditional D3 denies.

  `:derive` shapes that screen's own answer. It replaced an app-wide
  `:webapp/derive` that received the screen and cased on it — a function asking
  \"which screen is this?\" to answer something the screen already knew.

  **Normalised HERE, once.** A view, a navigation and a surface report would
  otherwise each write the same `(if (map? target) …)`, and the one that forgot
  would pass every fixture written as a bare fn.

  Two refusals, and the second is the one that would not otherwise fail. A map
  with no `:render` is a route that matches and renders nil, because a map is
  `ifn?`. A map with a key this framework does not read — `:reqeust` — is a
  screen that renders with no data forever, at a url that looks right, with
  nothing anywhere saying why."
  [pattern target]
  (let [known #{:render :request :check :derive}]
    (cond
      (map? target)
      (let [unknown (remove known (keys target))]
        (when-not (ifn? (:render target))
          (throw (ex-info (str "the route " (pr-str pattern) " names a screen with"
                               " no :render — a screen map is {:render (fn [state]"
                               " hiccup)}, plus :request and :derive if it asks for"
                               " anything. Without :render this route matches and"
                               " draws nothing.")
                          {:webapp/not-a-screen pattern})))
        (when (seq unknown)
          (throw (ex-info (str "the screen at " (pr-str pattern) " declares "
                               (apply str (interpose ", " (map pr-str (sort-by str unknown))))
                               " — a screen reads :render, :request, :check and :derive."
                               " An unread key is not a crash: it is a screen that"
                               " renders with no data forever, at a url that"
                               " matched.")
                          {:webapp/unknown-key (vec unknown) :webapp/pattern pattern})))
        target)

      ;; a keyword, symbol, set and vector are all `ifn?`, so they call cleanly
      ;; and answer nil — a blank pane on a route that MATCHED, which
      ;; `:webapp/not-found` cannot cover because nothing went wrong. `fn?`
      ;; alone would be too narrow: a VAR is the readable way to write a row
      ;; and is not `fn?`
      (or (not (ifn? target))
          (keyword? target) (symbol? target) (set? target) (vector? target))
      (throw (ex-info (str "the route " (pr-str pattern) " points at "
                           (pr-str target) ", which is not a SCREEN — a row names"
                           " (fn [state] hiccup), or {:render … :request …} for a"
                           " screen that asks for its own data. A keyword is"
                           " callable and answers nil, so this route would match"
                           " and then render a blank page.")
                      {:webapp/not-a-screen pattern}))

      :else {:render target})))

(defn ^:export
  ^{:malli/schema [:=> {:throws [:webapp/unknown-key :webapp/missing-key]}
                   [:cat [:map
                          [:webapp/state :any]
                          [:webapp/routes :any]]]
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

  **The table is ADDRESSES, not screens.** A row's screen is not unique and a
  screen's row is not unique — one screen answers at several urls the moment an
  app has a lens bar, a print view, or a detail page that also takes an optional
  segment. Every row is normalised to a screen VALUE here by [[as-screen]], and
  anything counting one against the other is counting the wrong thing.

  DERIVED, and refused if declared: `:webapp/view`. A route row names the screen
  that renders it, so the view is those screens plus the app's chrome, composed
  here. What that removes is a keyword agreeing in THREE places — routes
  returned `:things`, the view cased on it, fetch received it — with nothing
  checking any of the three.

  RETIRED, and refused with the migration: `:webapp/fetch` and `:webapp/derive`.
  Both were handed the screen and had to case on it, which is that same
  three-place agreement moved one seam along. A screen names its own `:request`
  and its own `:derive` now; `:webapp/call` performs what the request names, and
  the browser entry supplies it — so an app fetching its own data writes no
  ClojureScript.

  Optional, defaulted here so no caller has to nil-check a plug-in:
  `:webapp/chrome` (`(fn [state inner] hiccup)`, the layout around a screen;
  identity by default).

  Three STATE SCREENS, all `(fn [state] hiccup)` and all defaulted, for the
  three moments a screen cannot render against: `:webapp/not-found` (no row
  matched), `:webapp/loading` (the `:main` load is out) and `:webapp/failed` (it
  failed). Defaulted rather than left blank because slopp knows which state it
  is in, and a blank pane is indistinguishable from a screen whose content is
  empty. Declaring one replaces the copy; deciding WHERE it sits is chrome's,
  and [[derived-view]] says why that line falls there.

  Also optional: `:webapp/base` (mount prefix, `\"\"`), `:webapp/call` (the ONE
  performer — `js/fetch` in a page, a canned answer headless), `:webapp/render`
  and `:webapp/push-url!` (no-ops headless, `js/…` in a page), plus
  `:webapp/act`, `:webapp/actions`, `:webapp/request-for`, `:webapp/url-for`,
  `:webapp/leave!` and `:webapp/boot`.

  `:webapp/leave!` is the third kind of action's effect — a full page load, for
  a destination that is not a client route (a different mount point, so a
  different app). Like `:webapp/render` and `:webapp/push-url!` it is `js/…` in
  a page and a no-op headless, and like them the app never writes it: the
  browser entry supplies it.

  **Namespaced keys, like `slopp.cli`'s context and every `:web/*` marker.** An
  unqualified `:state` in a map an app also puts its own keys in is the nil-pun
  waiting to happen, and this map crosses a framework boundary where the reader
  cannot see what produced it. A SCREEN map is the deliberate exception and its
  keys are bare: nothing of the app's goes in it, [[as-screen]] refuses anything
  slopp does not read, and that refusal is what makes the exception safe rather
  than hoped.

  **It REFUSES at the constructor, naming the key.** A page that cannot open is
  measured to die a screen later as something unrecognisable — a missing state
  as `Cannot invoke Future.get()`, a typo'd `:vew` as a BLANK PAGE, which reads
  as a bug in an app that was never wired. The mistake is made here, so here is
  where it is named.

  **What it does NOT check is that `:webapp/state` is an ATOM**, and that is the
  `slopp.lang` lesson rather than an omission: asking what a thing IS differs by
  platform, D3 denies the reader conditional that would branch, and this
  namespace compiles to both. So the kind check lives in `slopp.cljnx/open!`,
  which is `:clj` and can ask — one platform down, where the answer exists."
  [app]
  (let [known   #{:webapp/state :webapp/base :webapp/routes :webapp/chrome
                  :webapp/not-found :webapp/loading :webapp/failed
                  :webapp/render :webapp/push-url! :webapp/call
                  :webapp/request-for :webapp/act :webapp/actions
                  :webapp/address-keys :webapp/session-loads :webapp/boot
                  :webapp/url-for :webapp/leave!}
        ;; RETIRED, each carrying its own migration rather than being reported
        ;; as a typo. Both received the SCREEN and cased on it
        retired {:webapp/fetch  (str "a screen names its own :request now —"
                                     " [\"/things/:id\" {:render thing :request (fn"
                                     " [params] {:webapp/path \"/api/things/:id\""
                                     " :webapp/path-params {:id (:id params)}})}]."
                                     " That request is pure and :cljc, so WHICH call"
                                     " a screen makes is a fact a test reads;"
                                     " :webapp/call performs it, and the browser"
                                     " entry supplies that.")
                 :webapp/derive (str "a screen shapes its own answer now — {:render"
                                     " … :request … :derive (fn [response] value)}."
                                     " An app-wide derive could only ever be a case"
                                     " on which screen was asking, to answer"
                                     " something the screen already knew.")}
        ;; `:webapp/view` is neither known nor unknown — it is DERIVED, and has
        ;; its own refusal below naming what replaced it. Reported here it would
        ;; read as a typo, which is the least useful thing to tell someone who
        ;; wrote the key this framework asked for until yesterday. The retired
        ;; pair is held out for the same reason
        unknown (remove (into (conj known :webapp/view) (keys retired)) (keys app))
        listed  (fn [ks] (apply str (interpose ", " (map pr-str (sort-by str ks)))))]
    (when (seq unknown)
      (throw (ex-info (str "unknown wiring key" (when (next unknown) "s") " "
                           (listed unknown) " — an app declares " (listed known)
                           ". (A typo here used to render a BLANK page; refusing"
                           " is the favour.)")
                      {:webapp/unknown-key (vec unknown)})))
    (doseq [k [:webapp/state :webapp/routes]]
      (when-not (contains? app k)
        (throw (ex-info (str "an app needs " k
                             (case k
                               :webapp/state  " — its OWN atom, so what a handler changes is what the view re-reads"
                               :webapp/routes " — a table of [pattern screen] rows; without one no path means anything"))
                        {:webapp/missing-key k}))))
    ;; :webapp/view is DERIVED and no longer an app's to write. A hand-written
    ;; view is precisely what made `:screen` a keyword agreeing in three places
    ;; — routes returned it, the view cased on it, fetch received it — with
    ;; nothing checking any of the three.
    (when (contains? app :webapp/view)
      (throw (ex-info (str ":webapp/view is DERIVED now — a route row points at"
                           " the screen fn itself, so there is no (case (:screen"
                           " s) …) left to write. Declare :webapp/chrome"
                           " (fn [state inner] hiccup) for the layout around a"
                           " screen, and :webapp/not-found (fn [state] hiccup)"
                           " for a path no row matches.")
                      {:webapp/derived-key :webapp/view})))
    (doseq [[k why] retired]
      (when (contains? app k)
        (throw (ex-info (str k " is retired — " why)
                        {:webapp/retired-key k}))))
    ;; a SET said only WHICH loads survive a navigation, so starting one still
    ;; needed code the app wrote in its own entry point — which for a browser
    ;; app means ClojureScript, which is the thing this capability exists to
    ;; remove. The map says what the load IS as well, and those were always one
    ;; statement about one load
    (when (and (contains? app :webapp/session-loads)
               (not (map? (:webapp/session-loads app))))
      (throw (ex-info (str ":webapp/session-loads is a declared MAP now —"
                           " {:modules {:request (fn [state] {:webapp/path"
                           " \"/api/modules\"}) :derive :names}}. A set named"
                           " which loads survive a navigation and nothing else,"
                           " so STARTING one was still code in your entry point;"
                           " declared, slopp starts it at page load and"
                           " query_surface can draw it. A load with no :request"
                           " is scoped without being started, which is the set's"
                           " old meaning: {:user {}}.")
                      {:webapp/retired-shape :session-loads-set})))
    ;; A TABLE, never a function, and the refusal carries the migration because
    ;; there is no shim behind it. A function answers only when called, with a
    ;; path, at runtime — so nothing can list an app's screens, join a link to
    ;; one, or compare this table to the prefixes the server answers for. Every
    ;; report and gate that exists for `^{:http/path …}` was impossible on this
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
                :webapp/render       (fn [_state] nil)
                :webapp/push-url!    (fn [_url] nil)
                ;; the ONE performer, and the default REFUSES rather than
                ;; answering nothing. A screen that named a request and got
                ;; silence would render its empty self forever, which is the
                ;; failure this capability exists to make impossible
                :webapp/call         (fn [request _ok _err]
                                       (throw (ex-info (str "this app has no :webapp/call,"
                                                            " and something asked for "
                                                            (pr-str (:webapp/path request))
                                                            " — declare (fn [request ok err])."
                                                            " In a page the browser entry"
                                                            " supplies it; headless it is"
                                                            " where a test says what the"
                                                            " server answered.")
                                                       {:webapp/missing-key :webapp/call
                                                        :request request})))
                ;; the layout around a screen. Identity by default — an app with no
                ;; chrome is one whose screens are the whole page, which is ordinary
                :webapp/chrome       (fn [_state inner] inner)
                ;; slopp KNOWS when no row matched, so rendering nothing there would
                ;; be a choice rather than an accident — and a blank pane at a
                ;; plausible url is indistinguishable from a screen whose content is
                ;; empty, which is the failure this capability keeps naming.
                ;; Deliberately plain and obviously the framework's: an app that has
                ;; not thought about it gets something honest, not something pretty
                :webapp/not-found    (fn [state]
                                       [:div
                                        [:h1 "Not found"]
                                        [:p (str "No screen is routed to "
                                                 (pr-str (:path state)) ".")]])
                ;; the CONTENT of the two states a screen cannot render against.
                ;; Defaulted for `not-found`'s reason and placed by chrome for a
                ;; reason `not-found` does not have — see [[derived-view]]. Plain and
                ;; obviously the framework's: the one real app wrote almost exactly
                ;; these by hand, which is the evidence that defaulting them is not
                ;; picking somebody's spinner
                :webapp/loading      (fn [_state] [:p [:small "Loading…"]])
                :webapp/failed       (fn [state]
                                       [:div
                                        [:h1 "Could not load"]
                                        [:p (str (:error (:main (:loads state))))]])
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
                ;; nothing outlives the screen unless the app says so. The
                ;; conservative default, because a load that wrongly survives
                ;; shows the previous screen's answer under a new url — while
                ;; one that wrongly dies is only re-fetched.
                ;;
                ;; A MAP of declared loads: `{:modules {:request … :check …
                ;; :derive …}}`. It says what each load IS as well as that it
                ;; outlives a screen, which were always the same statement about
                ;; the same load — and a load declared with no `:request` is
                ;; scoped without being started, for the one an app begins
                ;; itself after a sign-in
                :webapp/session-loads {}}
               app)
        ;; a DECLARED nil is not the same as an absent key, and `merge` keeps
        ;; it. The mount point arrives from a DOM attribute the browser answers
        ;; nil for when it is simply absent — which is every app served at the
        ;; root — and nil here prefixes every pushed url with the string "null".
        ;; Normalised where the app is constructed, so the shim reads the
        ;; attribute and interprets nothing
        (update :webapp/base #(or % ""))
        ;; every row's target becomes a screen VALUE here, so a view, a
        ;; navigation and a surface report never ask which shape it was written
        ;; in — and the one that forgot to ask would have passed every fixture
        ;; written as a bare fn
        (update :webapp/routes
                (fn [rows] (mapv (fn [[pattern target]]
                                   [pattern (as-screen pattern target)])
                                 rows)))
        ;; the VIEW is derived last, from the parts above, and put where the
        ;; driver and the browser entry both already look for it. One producer:
        ;; a headless drive and a real page render the same function
        (as-> a (assoc a :webapp/view (derived-view a))))))

(defn ^:export
  ^{:malli/schema [:=> {:throws []} [:cat [:maybe :string]] [:maybe :string]]}
  media-type
  "The MEDIA TYPE inside a `Content-Type` header — `nil` for a header that is
  absent or empty.

  A browser answers `application/json; charset=utf-8`, so a decoder map keyed
  by `\"application/json\"` misses on the string the response actually carries.
  Taking the parameters off is a decision, small enough to look like none, and
  it belongs on this side of the seam for the reason every other one does: in
  the shim it would be a string-split nothing can run, and its failure mode is
  every response falling through to the default decoder — which for JSON means
  a screen rendering a string of JSON rather than the data in it.

  `nil` rather than `\"\"` when there is no header, so a caller's `get` reaches
  its default instead of matching an entry somebody keyed by the empty string."
  [content-type]
  (let [t (str/trim (str/lower-case (str content-type)))
        t (str/trim (first (str/split t #";")))]
    (when (seq t) t)))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map
                          [:webapp/method {:optional true} [:maybe :keyword]]
                          [:webapp/headers {:optional true} [:maybe [:map-of :string :string]]]
                          [:webapp/body {:optional true} :any]]]
                   [:map [:method :string] [:headers [:map-of :string :string]]
                    [:encode :keyword]]]}
  request-init
  "Everything `fetch` needs about a request except its URL — as DATA, with the
  encoder NAMED rather than run.

  The browser shim may not branch, because a namespace whose only verification
  is that it compiled must not be where a decision lives. So every judgement
  moves here: the method and its spelling, the headers, and WHICH encoder the
  body wants. What the shim does with `:encode` is one `get` into a map of
  encoders — the choice was already made, in a function this test suite drives.

  **The encoder follows the DECLARED content type**, and JSON is the default
  rather than the only option. That asymmetry was real and the consuming app
  named it: slopp's own API publishes `application/edn`, so a framework that
  could only send JSON could not POST to the endpoints slopp itself serves.

  | declared `Content-Type` | `:encode` |
  |---|---|
  | absent, or `application/json` | `:json` |
  | `application/edn` | `:edn` |
  | anything else | `:text` — the body AS GIVEN |

  `:text` is the honest answer rather than a gap: slopp does not know how to
  encode for `image/png`, and guessing JSON would corrupt what the caller
  handed over.

  `:encode` is `:none` when there is no body, whatever the declared type says.
  `fetch` throws on a GET carrying one, so a request that quietly acquired an
  empty body would stop working rather than send something harmless.

  **`false` is a body and `nil` is not**, which is the nil-pun this framework
  keeps removing, in the one place it would silently drop a value somebody
  meant to send.

  **Headers are in the shape from the start**, rather than after the first app
  needs them. A token is STATE and not schema — nothing about an endpoint
  declaration can produce it — so an app without this key would fall straight
  back to writing its own `fetch`, which is the whole thing being avoided. A
  declared header WINS over the default content type, which is also what makes
  the table above reachable at all."
  [{:webapp/keys [method headers body]}]
  (let [has-body? (some? body)
        declared  (media-type (get headers "Content-Type"))]
    {:method  (str/upper-case (name (or method :get)))
     ;; starts from {} so a request with no body and no declared headers
     ;; still answers a MAP — `clj->js` on nil is null, and a caller reading
     ;; (get (:headers init) …) would be asking a nil the same question
     :headers (merge {} (when has-body? {"Content-Type" "application/json"})
                     headers)
     :encode  (if has-body?
                (case declared
                  "application/edn" :edn
                  ("application/json" nil) :json
                  :text)
                :none)
     :body    body}))

(defn ^:export
  ^{:malli/schema [:=> {:throws []} [:cat :int :any] [:tuple :keyword :any]]}
  response-outcome
  "What a response IS — `[:ok value]` or `[:failed message]`.

  **`fetch` rejects only on a NETWORK error.** A 500 resolves happily, so a
  performer that hands every resolved response to the success callback renders
  the error page's body as though it were data — the screen fills with
  something, which is why it survives review. That check is `(<= 200 status
  299)`, a branch, and the browser shim may not have one; so it is here, and
  the shim looks its callback up by the keyword this returns.

  **A server that SAID what went wrong keeps saying it.** slopp's own endpoints
  answer a map with an explanation, and reducing that to a bare status code
  throws away the only half a reader can act on. `:error` and `:message` are
  the two keys worth knowing; a string body is carried as itself.

  The status stays in the message either way, because 404 and 500 are different
  problems — a url that does not exist against a server that broke — and the
  failure pane is where somebody decides which one they are looking at."
  [status value]
  (if (<= 200 status 299)
    [:ok value]
    [:failed (let [said (or (:error value) (:message value)
                            (when (string? value) value))]
               (str "HTTP " status (when said (str " — " said))))]))

(defn ^:export
  ^{:malli/schema [:=> {:throws []} [:cat [:maybe :string] [:maybe :map]] [:maybe :map]]}
  addressed
  "`request` with its path under the base it is measured from — the url a
  browser should actually fetch.

  **The third of three, and it was missing.** `:webapp/base` was applied by
  `:webapp/push-url!`, by [[strip-base]] on an arriving url, and by
  [[prefix-links]] to an `:href` or `:action`. Never to a request. So an app
  served behind a proxy at `/p/demo` asked for `/api/things`, which is a
  different application or nothing at all — and not on one pane: on EVERY
  screen, because the framework builds every url.

  Found by the only app served under a mount point, whose generated client had
  been carrying the base itself; the framework taking over performing is what
  dropped it. The asymmetry is theirs and it is the clearest statement of the
  bug: *what `prefix-links` does to an `:href` and `strip-base` does to an
  arriving url, nothing did to a request.*

  **A REQUEST may name its own base, and the app's is only the DEFAULT.** The
  app-level value is stamped once, at page load, from a per-server context —
  so it can only be right while an app talks to one upstream for the life of a
  document. A client-routed app does not: switching which project you are
  reading is a route change, not a page load, and the upstream it addresses
  changes with it. **Which api a request belongs to is ROUTE STATE**, and route
  state is not something a page-load-time attribute can carry. Same key at two
  scopes and deliberately the same word, because it is the same fact — the
  narrower one wins, the way an inline style beats a sheet.

  `:webapp/base \"\"` on a request therefore means *measured from the ORIGIN*,
  and that is exactly what the retired `:webapp/from-origin` boolean used to
  say. **That flag is GONE, not deprecated** — it was an escape from a field
  that could not hold two values, and an escape whose cause is removed is not a
  thing to keep working for a while. Nothing reads it, so a request still
  carrying it is addressed under the app's base like any other: a retired
  marker that waives nothing while reading as though it does is worse than its
  absence, which is the same stance `slopp.http.auth` and
  `slopp.project.capabilities` already take about retired spellings.

  **An ABSOLUTE url is left alone**, which is the same judgement
  `prefix-links` makes: a third-party API is not under anyone's mount point,
  and prefixing it would break the one request the app cannot re-address. Both
  spellings count — a scheme, and the protocol-relative `//` that a CDN link
  takes."
  [base request]
  (let [p    (:webapp/path request)
        base (if (contains? request :webapp/base)
                 (:webapp/base request)
                 base)]
    (if (and (string? p)
             (seq (str base))
             (not (str/includes? p "://"))
             (not (str/starts-with? p "//")))
      (assoc request :webapp/path (prefixed base p))
      request)))

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
    (let [{:webapp/keys [state base call render]} app
          ;; the SAME addressing a screen's load gets. Doing it on one path and
          ;; not the other would make an app right once and wrong once, which
          ;; reads as a flaky endpoint rather than as a missing prefix
          request (addressed base request)
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

(defn- fetch!
  "Run `request` as load `key`, applying `spec`'s `:check` and `:derive` — or
  nothing at all when the request is nil.

  **One producer, because there are two callers and they must not differ.**
  [[navigate!]] runs a screen's `:main` load and [[start!]] runs each declared
  session load, and the machinery between them — address the path, perform
  through `:webapp/call`, check and derive inside the freshness guard — is the
  same machinery or it is two that drift. The first version of this capability
  had a session load outside the loop entirely, and [[load!]] records the three
  defects that produced.

  A nil request DECLINES and is not an error: a screen with nothing to ask for
  and a session load that is not armed yet — no token, nothing selected — are
  the same statement, and both leave the load `:absent`, which is the true one."
  [{:webapp/keys [base call] :as app} key spec request]
  (when request
    (load! app key
           (fn [ok err] (call (addressed base request) ok err))
           {:xform (:derive spec) :check (:check spec)})))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map
                          [:webapp/state :any]
                          [:webapp/base {:optional true} :string]
                          [:webapp/routes :any]
                          [:webapp/call :any]
                          [:webapp/render :any]
                          [:webapp/push-url! :any]]
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

  **The SCREEN says what to fetch.** A route row's screen carries `:request`, a
  pure `(fn [params] -> request | nil)`, so which call a url makes is decided in
  `:cljc` where a test can read it, and `:webapp/call` is left with nothing but
  performing. What that replaced was an app-wide `:webapp/fetch` receiving the
  screen and casing on it — the three-place keyword agreement the route table
  removed, one seam along.

  **A nil request DECLINES**, the same channel [[perform!]] uses: a screen with
  nothing to ask for starts no load at all, so `:main` stays `:absent` and
  [[derived-view]] renders the screen rather than a spinner that never ends. The
  render happens HERE in that case and inside [[load!]] otherwise, so exactly
  one render shows the new address and it is never the empty pre-load flash.

  **The screen's data is the `:main` load**, run through [[load!]] like any
  other, so the machinery is one implementation rather than one for the loop and
  one for everybody else. That was not true of the first version, and what it
  cost is written up in `load!`.

  **`:webapp/call` takes CALLBACKS rather than returning a promise, and the
  reason travels with the decision because it reads as arbitrary style
  otherwise.** A promise is not a thing the JVM oracle has. A loop that could
  only run in a browser would put the whole headless exercise back where it
  started, which is the one outcome this capability exists to prevent. An app
  whose HTTP layer is promise-shaped adapts at this seam — that is what the seam
  is for.

  **The screen's `:derive` is applied inside the freshness guard, not inside the
  call.** The two read as equivalent and are not: the call runs before anything
  knows whether the answer is still wanted, so deriving there pays for every
  abandoned load."
  [{:webapp/keys [state base routes render push-url!
                  address-keys session-loads] :as app}
   path push?]
  (when push? (push-url! (prefixed base path)))
  (swap! state arrive path (match-route routes path) address-keys session-loads)
  (let [{:keys [screen params]} @state
        request (when-let [f (:request screen)] (f params))]
    (if request
      (fetch! app :main screen request)
      (render @state))))

(defn- navigate-for!
  "The driver's `:navigate`, partial'd over `app`.

  A named var rather than a closure inside [[driver]], and the reason is the
  `!`-naming rule rather than taste: a constructor that builds mutating
  closures INLINE computes as effectful itself, so the gate would demand
  `driver!` — a name asserting that calling it does something, when calling it
  only assembles a map. Naming the behaviours puts the `!` where the mutation
  actually is and leaves the assembler honest.

  **It takes the url a READER would type — mount point included — and strips it,
  exactly as a browser click does.** The fake browser follows an `:href` out of
  the rendered tree, and the render now prefixes in-app links, so the path
  arriving here carries the mount point and would match no route unprefixed. A
  headless drive that visited app-relative paths would be exercising a url no
  browser ever produces, which is the shape of divergence this whole capability
  exists to prevent.

  A url outside the mount point does NOTHING. [[strip-base]] answers nil there,
  and routing nil would clear the screen the reader was on — treating a foreign
  path as ours because it was handed to us.

  Returns the state AFTER the move, because that is what `screen` re-renders."
  [app _state url]
  (when-let [path (strip-base (:webapp/base app) url)]
    (navigate! app path false))
  @(:webapp/state app))

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

(defn- begin!
  "Boot the app and start every declared session load, returning the state that
  leaves. `path` is the app-relative address this session is starting at, or
  nil when there is none.

  Answers the question a page load and a headless drive must answer
  identically, so it is one function and not two.

  **Two callers and they must not differ.** [[start!]] is what the browser entry
  runs; `(:boot (driver app))` is what `slopp.cljnx/open!` runs. When only
  the first started session loads, a declaration that worked in a page was
  invisible to every headless drive — which is the ONE difference this
  capability exists to prevent, arriving in the change that closed the previous
  one. The app that took it found sixteen driven screens rendering an empty nav
  rail and reverted the declaration rather than paper over it.

  Third instance of the shape in a fortnight, after a throwing `:derive` and an
  unprefixed `:action`, so the rule is worth having explicitly: **anything a
  page load does before routing, a headless drive does too, out of one
  producer.**

  Boot FIRST, because a session `:request` reads the state boot established —
  an authenticated load carries a token boot put there, and reversing these two
  sends the request without one.

  **A session `:request` takes STATE and the route CAPTURES.** It used to take
  state alone, on the reasoning that a session load has no address so there are
  no captures to hand it. That reasoning conflated two things: a session load
  has no address OF ITS OWN, which is not the same as being independent of THE
  address. A real hub is where they come apart — which project a request
  belongs to is in the url, and the load that fills the nav pane needs it. It
  asked `/api/modules` at the origin, which that hub does not serve, and the
  pane was empty for the life of every session.

  **Matching is not rendering**, which is what makes this cost nothing: the
  address is turned into captures here, before routing, and step 3 still shows
  the screen. Both of [[start!]]'s ordering reasons survive untouched.

  Captures are `{}` rather than nil when there is no address, so a request never
  has to tell \"no captures\" from \"not asked\".

  Read-call-write, never inside `swap!`: boot is the app's own code, `swap!`
  demands a pure function and may retry, and an entry point that starts a fetch
  is neither."
  [{:webapp/keys [state boot session-loads routes] :as app} path]
  (reset! state (boot @state))
  (let [params (or (:params (match-route routes path)) {})]
    (doseq [[key spec] session-loads]
      (fetch! app key spec (when-let [f (:request spec)] (f @state params)))))
  @state)

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:webapp/state :any] [:webapp/view :any]]]
                   [:map [:state :any] [:view :any]
                    [:navigate :any] [:dispatch :any] [:boot :any]]]}
  driver
  "The headless DRIVER's map, derived from the app's own wiring.

  `slopp.cljnx/open!` takes `{:state :view :navigate :dispatch :boot}`.
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
  (let [{:webapp/keys [state view]} app]
    {:state    state
     :view     view
     ;; the app's own atom is the one that moves; `screen` re-reads what comes
     ;; back, which is why these return the state rather than a fresh map
     :navigate (partial navigate-for! app)
     :dispatch (partial dispatch! app)
     ;; everything a page load does BEFORE routing, which is boot AND every
     ;; declared session load — not the app's own transform alone. When this
     ;; was `boot`, a session load ran in a browser and in no headless drive,
     ;; and sixteen driven screens in the one real app rendered an empty nav
     ;; rail against a declaration that worked in production. The state
     ;; argument is ignored because [[begin!]] reads the same atom `open!`
     ;; read it from
     :boot     (fn [_state url] (begin! app (app-path (:webapp/base app) url nil)))}))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:webapp/state :any] [:webapp/boot :any]]
                    [:maybe :string] [:maybe :string]]
                   :any]}
  start!
  "Everything a browser entry does when the bundle runs: let the app start what
  belongs to no particular screen, then show the url the reader arrived at.

  Three steps now, and the ORDER is the decision.

  1. **`:webapp/boot`** — a pure `(fn [state] state)` for whatever the app wants
     in place before anything else. A token read from the document, a
     preference, a default filter.
  2. **Every declared `:webapp/session-loads` entry that names a `:request`** is
     started, through the same [[fetch!]] a screen's `:main` load goes through.
  3. **The url the reader arrived at.**

  Routing last, because the first screen may read a session load and rendering
  before they are in flight shows `:absent` as a flash of empty chrome on every
  page load. Boot FIRST, because a session request is a `(fn [state])` and the
  state it reads is the one boot established — an authenticated load carries a
  token boot put there, and reversing these two sends the request without one.

  **This is what `:webapp/boot` used to CLAIM and could not do.** Its docstring
  said it was where an app begins the loads that belong to the session rather
  than to a route; it is `(fn [state] state)`, with no `app`, so it can reach
  neither `:webapp/call` nor [[load!]] and can begin nothing. An app that wanted
  a nav pane fetched once had to write ClojureScript after `mount!` — which is
  this capability's own goal, stated as a number, not being zero. A docstring
  promising what the signature cannot deliver is worse than a missing feature:
  it sends a reader to write the wrong thing and then to wonder why the
  framework's own `session-loads` did not cover it.

  So a session load is DATA, like a route row and like a screen's request. That
  also makes it visible to everything that reads declarations — `query_surface`
  draws it, and `webapp-request-paths-are-served` joins its path against the
  endpoints this store serves, neither of which is possible for a fetch an app
  performs in its own entry point.

  **A session `:request` takes STATE where a screen's takes PARAMS**, and the
  asymmetry is the honest one: a session load has no address, so there are no
  captures to hand it, and what it does need is whatever boot established.

  **Read-call-write, never inside `swap!`**, matching `slopp.cljnx/open!`
  exactly. Boot is the app's own code; `swap!` demands a pure function and may
  retry, and an entry point is neither.

  This is the `:cljc` half of the browser entry, which is the point: the shim
  reads two properties off `location` and calls this. A page load is the one
  moment an app has no reader to notice it went wrong, so it is the last place
  a decision should live somewhere nothing can check."
  [app pathname search]
  
  ;; the address is KNOWN here and SHOWN in step 3 — nothing requires those to
  ;; be the same moment. Deriving it first is what lets a session load reach the
  ;; route captures without either ordering reason above being given up.
  (begin! app (app-path (:webapp/base app) pathname search))
  (navigate-url! app pathname search false))
