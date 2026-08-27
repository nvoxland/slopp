(ns slopp.webapp.dom
  "The browser SHIM — the interop half of a `webapp`, and the only `:cljs`
  namespace slopp ships.

  Its neighbour `slopp.webapp` holds every DECISION a browser app makes: which
  clicks are the app's, what a url means, which kind of thing an action is, what
  happens at page load. That namespace is `:cljc`, so a JVM test drives all of
  it headlessly, in seconds, with no compile and no browser. This one holds what
  cannot be moved there — `getElementById`, `addEventListener`, `.-metaKey`,
  `pushState`, `location.assign`, and a render loop.

  **The split is not tidiness, it is where verification is possible.** slopp has
  no ClojureScript test runner — no node, no doo, no karma — so this file's
  only verification is that it compiled. That is exactly the condition the
  `webapp` capability exists to end rather than relocate, and the answer is not
  to build a cljs test rig: it is to leave nothing here worth verifying.

  So this namespace may not BRANCH, and a check enforces it
  (`ops.selfcheck-test/the-browser-SHIM-cannot-branch-and-so-cannot-decide`).
  Code that cannot branch cannot decide. Read the nil-handling here in that
  light: `\"a[href], html\"` is a selector that always matches so that `closest`
  never answers null, and `goog.object/getValueByKeys` is a read that returns
  nil rather than throwing on one. Neither is a decision about the app — both
  are how a nil crosses into `:cljc`, where the decision already lives.

  The guard is a PROXY and worth naming as one: it proves nothing here decides,
  not that the interop is right. A misspelled property compiles and fails in a
  browser.

  An app that opts into `webapp` writes no ClojureScript at all, because
  everything that used to force it into `:cljs` is in here — see [[mount!]] for
  the table."
  (:require [goog.object :as gobj]
            [replicant.dom :as replicant]
            [slopp.webapp :as webapp] [cljs.reader :as reader] [slopp.http.endpoint :as endpoint]))

(defn- click-data
  "A DOM click event read into the map `slopp.webapp/click-target` decides on.

  Six properties, named. Nothing here judges: whether a modifier means \"open
  elsewhere\", whether an href is in-app, whether a path is routed — all of that
  is `:cljc`, and this hands over the readings those decisions are made from.

  **`\"a[href], html\"` is the floor, and it is why this has no `nil?` check.**
  `closest` walks up from the clicked node looking for a link; when the click
  was on a button, or on nothing in particular, it would answer null and
  `.getAttribute` would throw. Naming `html` as a second match means it always
  answers an element — one with no `href` attribute, so `getAttribute` gives nil
  and `click-target`'s \"an href at all\" judgement handles it, where it already
  lives. A `when-let` here would have moved that judgement into the one
  namespace nothing can check."
  [e]
  {:webapp/href   (.getAttribute (.closest (.-target e) "a[href], html") "href")
   :webapp/button (.-button e)
   :webapp/meta?  (.-metaKey e)
   :webapp/ctrl?  (.-ctrlKey e)
   :webapp/shift? (.-shiftKey e)
   :webapp/alt?   (.-altKey e)})

(defn- typed-value
  "The scalar a control produced, out of replicant's event map.

  **This one line is why an app's handlers can be `:cljc` at all.** A DOM event
  is interop and cannot exist on a JVM, so a handler written to take one is a
  handler no headless drive can call. Normalising HERE means the app's
  interpreter receives what the reader typed and never sees an event of any
  shape — so neither driver's event can be right while the other's is wrong.

  `slopp.cljnx/fill!` states the same rule from the other side, as advice
  to an app author: *your `:cljs` dispatcher turns the event into a scalar, and
  your `:cljc` interpreter never sees an event.* Every app followed it by hand
  or did not. Here it is structural — the framework owns the dispatcher, so
  there is no by-hand left.

  **`getValueByKeys` rather than `..`, because a nil event is ORDINARY.**
  Replicant calls the global dispatcher for life-cycle hooks too, and those
  carry no DOM event; a plain `(.. e -target -value)` would throw on a mounted
  node. It is a nil-safe read rather than a decision — `nil` in, `nil` out —
  which is the only kind of nil-handling that belongs in a shim."
  [event-data]
  (gobj/getValueByKeys (:replicant/dom-event event-data) "target" "value"))

(def encoders
  "How a request body becomes what `fetch` sends, keyed by the encoder
  `slopp.http.endpoint/request-init` NAMED.

  A map rather than a `cond`, and the difference is the whole discipline: the
  choice between these was made in `:cljc`, by a function an in-image test
  drives, and what is left here is a `get`. The table mirrors [[decoders]] on
  purpose — what a framework can SEND and what it can READ drifting apart is
  how an app ends up able to browse an API it cannot post to.

  `:none` answers `js/undefined`, which is a `fetch` init with no body at all —
  a GET carrying even an empty one throws. `:text` sends the body AS GIVEN,
  which is the honest answer for a media type slopp does not encode for:
  guessing JSON would corrupt what the caller handed over."
  {:json (fn [body] (js/JSON.stringify (clj->js body)))
   :edn  pr-str
   :text str
   :none (fn [_body] js/undefined)})

(def decoders
  "How a response body becomes a Clojure value, keyed by MEDIA TYPE —
  `slopp.http.endpoint/media-type` produces the key, so `application/json;
  charset=utf-8` finds the JSON entry rather than falling through.

  **`application/edn` is here because slopp's own API publishes it.** `:http/raw`
  serves EDN, `generate_client` reads the contract document as EDN, and until
  this entry existed `slopp.webapp` was the one consumer that could not — so an
  app browsing slopp's own API hit it on its first screen. That was not a
  degradation to raw text either: the screen called `(:endpoints doc)` on a
  string, got nil, and rendered an empty index, which reads as *this project has
  no API* — a sentence about the project rather than about a decoder.

  **The honest cost, measured rather than estimated:** `cljs.reader` adds about
  150 KB to a `:simple` bundle — slopp's own went from 1,346,912 to 1,497,561
  bytes on the compile that added this line — and every app pays it, including
  one that never sees an EDN response. That is the price of the table not being
  configurable, and it is the right side of the trade only because the
  alternative is an app that silently renders an empty screen.

  It ships by default rather than being declarable, and the earlier decision not
  to ship it was made on a misreading of the dialect gate. The denylist covers
  the BARE `read-string`, which evaluates; `clojure.edn/read-string` and its
  ClojureScript counterpart are a different var and the gate says so in its own
  message. Making the table configurable was the alternative and is refused for
  the reason `:webapp/fetch` was retired: a seam every app has to configure is a
  second vocabulary, and this one has a right answer.

  `::text` is the fallback, reached by `get`'s default argument rather than by a
  test on the type. An answer with no `Content-Type` — a 204, most often — is
  decoded as text and arrives as the empty string; that is a real limit rather
  than a hidden one, and an app that must tell `\"\"` from nothing apart has a
  screen-level question this seam cannot answer for it.

  Each entry returns a PROMISE, because the browser's own body readers do."
  {"application/json" (fn [response]
                        (.then (.json response)
                               (fn [data] (js->clj data :keywordize-keys true))))
   "application/edn"  (fn [response]
                        (.then (.text response)
                               (fn [text] (reader/read-string text))))
   ::text             (fn [response] (.text response))})

(defn call!
  "The `:webapp/call` a real page runs: `js/fetch`, and nothing else.

  Every judgement it needs was made in `slopp.webapp`, which is `:cljc` and
  driven by ordinary tests — the URL by `slopp.http.endpoint/request`, which
  resolves it before the request is ever performed, the method, headers and
  encoder by `request-init`, the decoder key by `media-type`, and whether the
  answer is data or a failure by `response-outcome`. What is left here is one
  `fetch` and three `get`s, which is why this namespace can be verified by
  compiling alone without that being a claim it is correct.

  **The failure this shape prevents is the one every hand-written `fetch` has
  on its first day**: `fetch` rejects only on a NETWORK error, so a 500
  RESOLVES, and a performer that passes every resolved response to the success
  callback renders the error page's body as though it were data. The screen
  fills with something, so it survives review. `response-outcome` is the check,
  and it is on the side of the seam where a test can watch it fail.

  A rejected promise — DNS, offline, CORS — is the other channel and reaches
  `err` with the browser's own message."
  [request ok err]
  (let [init    (endpoint/request-init request)
        respond (fn [status]
                  (fn [value]
                    (let [[kind v] (webapp/response-outcome status value)]
                      ((get {:ok ok :failed err} kind) v))))]
    (-> (js/fetch (:http/url request)
                  #js {:method  (:method init)
                       :headers (clj->js (:headers init))
                       :body    ((get encoders (:encode init)) (:body init))})
        (.then (fn [response]
                 (-> ((get decoders
                           (endpoint/media-type (.get (.-headers response) "content-type"))
                           (::text decoders))
                      response)
                     (.then (respond (.-status response))))))
        (.catch (fn [e] (err (.-message e)))))))

(defn ^:export
  ^{:unused-ok "the generated browser entry calls it, and that entry is a
  STRING built by slopp.build/webapp-launcher-source — so no reference graph can
  see the call. `ops.external/build!` emits it for any webapp store with an
  ^:app/entry, and a-built-BROWSER-app-gets-its-entry-generated asserts the
  emitted source names this fn.

  Two earlier versions of this justification were wrong in opposite directions,
  which is why it now names a TEST rather than asserting a caller. The first
  claimed a caller that did not exist — and a justification asserting one is
  exactly what stops anyone looking. The second said the var was genuinely
  unused, true at the time, because the generator had no caller: the entry
  marker was asked for a DRIVER by the fake browser and the DECLARATION by this
  function. That fork is gone — the marker names the declaration and
  cljnx/driver-for derives the rest — so the generator has a caller again."}
  mount!
  "Run `declared` as a browser application: supply the effects a page has,
  register the two listeners a page needs, and start.

  The generated entry calls this and nothing else, which is the point — an app
  that opts into `webapp` writes no ClojureScript, because everything that used
  to force it into `:cljs` has an owner here:

  | what an app used to write | who has it now |
  |---|---|
  | `replicant.dom/render` in a loop | `:webapp/render`, below |
  | `js/fetch` for a screen's data  | [[call!]], via `:webapp/call` |
  | `history.pushState`             | `:webapp/push-url!` |
  | `location.assign`               | `:webapp/leave!` |
  | a click listener with `.closest` and `preventDefault` | [[click-data]] + `slopp.webapp/click!` |
  | a popstate listener             | `slopp.webapp/navigate-url!` |
  | `(.. e -target -value)` in a dispatcher | [[typed-value]] |
  | reading the mount prefix off the DOM | `data-base`, below |

  **The effect plug-ins OVERRIDE whatever the app declared**, rather than
  filling in gaps. They are not the app's to supply: an app that rendered
  itself would be a second render loop, and the headless drive would exercise
  neither. `merge` in this direction is the enforcement.

  **Wiring happens BEFORE the render loop is built, and the order is load-bearing
  now.** `:webapp/view` is DERIVED — a route row names its screen fn, and
  `wiring` composes those with the app's chrome — so the view this renders is
  one that does not exist until the app is wired. Reading it from `declared`
  would render whatever the app happened to put there, which since the
  derivation landed is nothing at all.

  **`data-base` is how the mount prefix reaches the browser.** An app served at
  `/p/slopp2/store` cannot tell that from `/store` by looking at its own url, so
  the server renders the answer onto the mount point and the entry reads it.
  Absent means the root, and `wiring` is what says so — this reads the attribute
  and interprets nothing.

  **Nothing here branches, and that is checked** (`ops.selfcheck-test`). slopp
  has no ClojureScript test runner, so this namespace's only verification is
  that it compiled — the exact condition this capability exists to end rather
  than relocate. A shim that cannot branch cannot decide, so everything a reader
  would want to assert about a browser app is in `slopp.webapp`, `:cljc`, driven
  headlessly. It is a PROXY and worth naming as one: it proves this code makes
  no decisions, not that its interop is correct. A property name typo compiles
  and fails in a browser, and no guard here will catch that.

  Returns the wired app, so a REPL session or a browser-plugin can reach it."
  [declared]
  (let [el    (webapp/mount-point (.getElementById js/document "app"))
        wired (webapp/wiring
               (merge declared
                      {:webapp/base      (.getAttribute el "data-base")
                       :webapp/push-url! (fn [url] (.pushState js/history nil "" url))
                       :webapp/leave!    (fn [url] (.assign js/location url))
                       :webapp/call      call!}))
        view  (:webapp/view wired)
        ;; INSTALLED, not assoc'd. `derived-view` has already closed over
              ;; `wired`, so an assoc here produces a map this entry holds and
              ;; the PAGES do not — and a load resolving inside a page would
              ;; render through whatever the captured map carried. That is a
              ;; blank pane and a `Loading…` that never clears, with a 200 in
              ;; the network pane and nothing thrown.
              app   (webapp/with-render!
                     wired (fn [state] (replicant/render el (view state))))]
    (replicant/set-dispatch!
     (fn [event-data handler-data]
       (webapp/dispatch! app handler-data (typed-value event-data))))
    (.addEventListener js/document "click"
                       (fn [e] (webapp/click! app (click-data e)
                                              (fn [] (.preventDefault e)))))
    (.addEventListener js/window "popstate"
                       (fn [_e] (webapp/navigate-url! app
                                                      (.-pathname js/location)
                                                      (.-search js/location)
                                                      false)))
    (webapp/start! app (.-pathname js/location) (.-search js/location))
    app))
