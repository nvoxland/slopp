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
            [slopp.webapp :as webapp]))

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

  `slopp.web.screen/fill!` states the same rule from the other side, as advice
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

(defn ^:export
  ^{:unused-ok "the generated browser entry calls it, and that entry is a STRING
  built by slopp.build/webapp-launcher-source — so the only caller in existence
  is one no reference graph can see. Same shape as the cli launcher's -main."}
  mount!
  "Run `declared` as a browser application: supply the effects a page has,
  register the two listeners a page needs, and start.

  The generated entry calls this and nothing else, which is the point — an app
  that opts into `webapp` writes no ClojureScript, because everything that used
  to force it into `:cljs` has an owner here:

  | what an app used to write | who has it now |
  |---|---|
  | `replicant.dom/render` in a loop | `:webapp/render`, below |
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
                       :webapp/leave!    (fn [url] (.assign js/location url))}))
        view  (:webapp/view wired)
        app   (assoc wired :webapp/render
                     (fn [state] (replicant/render el (view state))))]
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
