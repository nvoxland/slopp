(ns slopp.webapp-test
  "The browser app's loop, and the DERIVATION of a headless driver from it.

  Nothing here starts a browser or compiles ClojureScript, which is the whole
  claim: an agent reads what a dynamic page SAYS by driving the app's real
  wiring on a JVM. A test that needed a bundle would prove the opposite."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.webapp :as webapp]
            [slopp.cljnx :as cljnx] [clojure.string :as str] [slopp.http.endpoint :as endpoint]))

(deftest a-DYNAMIC-page-can-be-READ-without-a-browser-or-a-compile
  ;; The standing constraint on this whole wave, asserted rather than described:
  ;; an agent must be able to see what a page SAYS without playwright and
  ;; without compiling to JS. Not a server-rendered page — a page whose BROWSER
  ;; owns routing and state, driven through its own wiring.
  ;;
  ;; **`driver` is the point.** slopp ships a fake browser whose contract is
  ;; `{:state :view :navigate :dispatch :boot}` — what a DRIVER needs — while an
  ;; app is declared as plug-ins, which is what an app IS. Different levels. The
  ;; only real webapp built on slopp bridged them with a ~30-line adapter, a
  ;; THIRD hand-written wiring beside the browser entry and the headless driver,
  ;; with nothing comparing the three. Four of the five keys are mechanical, so
  ;; slopp derives them and the adapter stops existing.
  ;;
  ;; The fifth is `:view`, and it is derived now too: a row names the screen fn,
  ;; so there is no `(case (:screen s) …)` and no keyword agreeing in three
  ;; places.
  (let [state  (atom {})
        asked  (atom [])
        ;; screens are ordinary pure functions of state, which is what makes
        ;; every one of them assertable without a browser. A screen is only
        ;; called when its data is READY, so it never writes the three-way case
        ;; on load-status — the framework renders those states and chrome
        ;; places them. What is left is the screen itself, and the REQUEST it
        ;; names: pure, in :cljc, so which call a url makes is readable here
        things (fn [page]
                 (let [ts (webapp/ask! page {:http/method :get
                                             :http/path "/api/things"} {})]
                   [:ul (for [t (:value ts)] [:li (:name t)])]))
        thing  (fn [{:keys [params] :as page}]
                 (webapp/ask! page {:http/method :get
                                    :http/path "/api/things/:id"
                                    :http/params #{:id}}
                              {:id (:id params)})
                 [:p (str "Thing " (:id params))])
        app    (webapp/wiring
                {:webapp/state     state
                 :webapp/routes    [["/things"     things]
                                    ["/things/:id" thing]]
                 :webapp/chrome    (fn [_s inner] [:main [:h1 "Catalogue"] inner])
                 :webapp/not-found (fn [_s] [:p "Nowhere"])
                 ;; the app's data source. In a browser the entry supplies
                 ;; `js/fetch`; here it answers from memory by URL, exactly as a
                 ;; server does — which is the seam that makes the loop drivable
                 :webapp/call      (fn [request ok _err]
                                     (let [url (:http/url request)]
                                       (swap! asked conj url)
                                       (ok (when (= "/api/things" url)
                                             [{:name "Anvil"} {:name "Rope"}]))))})
        s      (cljnx/open! (webapp/driver app))]

    (testing "the derived driver is the shape the fake browser accepts"
      ;; if this drifts, every assertion below fails in a way that looks like
      ;; an app bug rather than a derivation bug
      (is (= #{:navigate :boot :state :dispatch :view :location}
             (set (keys (webapp/driver app))))
          (pr-str (keys (webapp/driver app)))))

    (testing "visiting a client route renders what the app would show"
      (cljnx/visit! s "/things")
      (let [t (cljnx/text s)]
        (is (re-find #"Catalogue" t) t)
        (is (re-find #"Anvil" t) t)
        (is (re-find #"Rope" t) t)))

    (testing "and the URL the screen asked for is the one a server would see"
      ;; two wirings can reach equal state having asked different endpoints,
      ;; because canned answers do not care who asked — so the REQUESTS are
      ;; part of the claim, not colour. Asserting the finished url rather than
      ;; the screen's identity is what became possible when the request moved
      ;; out of the browser: this string is the one that goes on the wire
      (is (= ["/api/things"] @asked) (pr-str @asked)))

    (testing "a second navigation re-routes and re-renders through the same loop"
      (cljnx/visit! s "/things/42")
      (is (re-find #"Thing 42" (cljnx/text s)) (cljnx/text s))
      ;; a capture arrives as the TEXT that was in the url — the framework does
      ;; not guess that "42" wanted to be a number, because a slug and an id
      ;; live in the same slot and only the app knows which this is
      (is (= ["/api/things" "/api/things/42"] @asked) (pr-str @asked)))

    (testing "an unrouted path is the app's own nowhere, not an exception"
      (cljnx/visit! s "/nope")
      (is (re-find #"Nowhere" (cljnx/text s)) (cljnx/text s)))))

(deftest a-load-that-has-not-been-ASKED-is-not-a-load-that-answered-NIL
  ;; slopp-ui caught this on the first read, and half their diagnosis applied.
  ;; I claimed `arrive` bumps rather than clears; they read that as losing
  ;; display honesty.
  ;;
  ;; **What was at stake is the four-state load model.** A token COUNTER
  ;; answers "is this answer still wanted" and nothing else, so `:absent` —
  ;; nothing has been requested — was never representable. A view could only
  ;; ask `(if (:data s) …)`, which reads an unasked load and a load that
  ;; answered NIL as the same thing. That is the nil-pun the model exists to
  ;; remove, in the framework that exists to remove nil-puns.
  ;;
  ;; The states are per-REQUEST now rather than per-screen — a page asks for
  ;; several — but the distinction is the same one and is what a page cases on.
  (let [state   (atom {})
        pending (atom nil)
        thing   {:http/method :get :http/path "/api/thing"}
        page    {:webapp/state  state
                 :webapp/render (fn [_] nil)
                 :webapp/call   (fn [_request ok _err] (reset! pending ok))}
        key     (webapp/load-key (:webapp/base page) (endpoint/request thing {}))]

    (testing "before anything is asked, the load is ABSENT"
      (is (= :absent (webapp/load-status @state key)) (pr-str @state)))

    (testing "while the request is out it is LOADING — a moment the reader can see"
      ;; held callback, so the loading moment is observable: a call that
      ;; answers synchronously never has one
      (is (= :loading (:status (webapp/ask! page thing {}))) (pr-str @state)))

    (testing "an answer of NIL is READY, and that is the whole point"
      ;; :ready-with-nil and :absent are different statements about the world:
      ;; "this screen has no things" versus "nobody has asked yet"
      (@pending nil)
      (is (= :ready (webapp/load-status @state key)) (pr-str @state))
      (is (nil? (webapp/load-value @state key)) (pr-str @state)))

    (testing "asking again finds it READY rather than fetching again"
      ;; start-if-absent: a page re-runs on every resolution, so this is the
      ;; property that keeps a page with one load from spinning
      (reset! pending nil)
      (is (= :ready (:status (webapp/ask! page thing {}))))
      (is (nil? @pending) "a ready load was fetched again"))

    (testing "and a failure is FAILED, distinct from both"
      (let [st  (atom {})
            err (atom nil)
            p2  {:webapp/state  st
                 :webapp/render (fn [_] nil)
                 :webapp/call   (fn [_request _ok e] (reset! err e))}]
        (webapp/ask! p2 thing {})
        (@err "no")
        (is (= :failed (webapp/load-status @st key)) (pr-str @st))
        (is (= "no" (get-in @st [:loads key :error])) (pr-str @st))))))

(deftest a-SUPERSEDED-answer-does-not-land-on-the-load-that-replaced-it
  ;; I placed `:load-seq` outside `:loads` on the argument that a token drawn
  ;; from a map that gets emptied would restart at 1 — so a slow answer from
  ;; an earlier request would match a later one and land on it. Then I shipped
  ;; it without asserting that failure.
  ;;
  ;; slopp-ui pinned the same property on their side and, checking their
  ;; coverage rather than claiming it, found the test that LOOKS like it covers
  ;; supersession stays green through the break — because it FABRICATES a
  ;; superseded token instead of producing one, so it asserts the guard and
  ;; never the token supply.
  ;;
  ;; So this produces the collision the honest way. Two screens sharing one
  ;; `:main` key was the old shape; a load is keyed by its REQUEST now, so the
  ;; collision that remains is the one that matters — the same address asked
  ;; again after a [[stale!]], with the first answer still in flight.
  (let [state   (atom {})
        pending (atom [])
        thing   {:http/method :get :http/path "/api/thing"}
        ;; the key is the address this app FETCHES, taken the way `ask!`
        ;; takes it — base included, even where this fixture's base is nil
        key     (webapp/load-key nil (endpoint/request thing {}))
        page    {:webapp/state  state
                 :webapp/render (fn [_] nil)
                 :webapp/call   (fn [request ok _err]
                                  (swap! pending conj [request ok]))}]

    (webapp/ask! page thing {})
    (webapp/stale! page thing {})
    (webapp/ask! page thing {})

    (testing "POSITIVE CONTROL: the re-ask really produced a SECOND request"
      ;; without this the collision cannot arise and every assertion below
      ;; passes vacuously — which is the exact defect this test exists about
      (is (= 2 (count @pending)) (pr-str (count @pending)))
      (is (= ["/api/thing" "/api/thing"] (mapv (comp :http/url first) @pending)))
      (is (= :loading (webapp/load-status @state key)) (pr-str @state)))

    (testing "the FIRST answer arrives late and is dropped"
      (let [[_ ok-1] (first @pending)]
        (ok-1 :data-from-the-first))
      (is (nil? (webapp/load-value @state key))
          (str "a superseded answer landing is the stale-screen failure: "
               (pr-str @state)))
      (is (= :loading (webapp/load-status @state key))
          (str "and it must not mark the live request ready either: "
               (pr-str @state))))

    (testing "while the SECOND answer still lands"
      ;; the arm that makes the drop a discrimination rather than a blanket
      ;; refusal — a guard that dropped everything would satisfy the above
      (let [[_ ok-2] (second @pending)]
        (ok-2 :data-from-the-second))
      (is (= :data-from-the-second (webapp/load-value @state key)) (pr-str @state))
      (is (= :ready (webapp/load-status @state key)) (pr-str @state)))))

(deftest an-EFFECTFUL-action-goes-through-the-call-plugin-not-the-reducer
  ;; slopp-ui's second scar, and the reason `driver` refused rather than
  ;; half-working until now. Their shells each wrote `(= :try/execute (first
  ;; action))` by hand, and the comment beside it said the quiet part:
  ;;
  ;;   Both dispatchers have to make the same split, or the screen a test
  ;;   drives and the screen a browser shows differ on the one control that
  ;;   DOES something.
  ;;
  ;; Two wirings, agreement by hand, nothing checking it — and the browser copy
  ;; in the one namespace whose only verification is that it compiled. They
  ;; fixed it by moving the decision into DATA both shells look up, which is
  ;; the shape slopp adopts: `:webapp/actions` declares which actions are
  ;; effects, and there is one dispatcher rather than two agreeing ones.
  ;;
  ;; A pure action changes state. An effectful one makes a REQUEST, and the
  ;; difference matters because a request has states a `swap!` does not — it is
  ;; in flight, it can fail, and a reader has to be told which.
  (let [state (atom {})
        calls (atom [])
        app   (webapp/wiring
               {:webapp/state   state
                :webapp/routes  [["/" (fn [_s] [:p "home"])]]
                :webapp/act     (fn [s action value]
                                  (assoc s :last [action value]))
                :webapp/actions {:thing/rename {:effectful? false}
                                 :thing/delete {:effectful? true}}
                :webapp/request-for (fn [_s action] {:method :delete
                                                     :path   (str "/things/" (second action))})
                :webapp/call    (fn [request ok _err]
                                  (swap! calls conj request)
                                  (ok {:status 200}))})
        {:keys [dispatch]} (webapp/driver app)]

    (testing "a PURE action goes through the reducer and asks nothing"
      (dispatch [:thing/rename 7] "Anvil")
      (is (= [[:thing/rename 7] "Anvil"] (:last @state)) (pr-str @state))
      (is (empty? @calls) (pr-str @calls)))

    (testing "an EFFECTFUL action makes the request the app declared"
      (dispatch [:thing/delete 7] nil)
      (is (= [{:method :delete :path "/things/7"}] @calls) (pr-str @calls))
      (is (= [[:thing/rename 7] "Anvil"] (:last @state))
          (str "and does NOT also run the reducer — an effect is not a state "
               "transition, and running both is how two dispatchers drift: "
               (pr-str @state))))

    (testing "the outcome is recorded so a reader can be told which state it is in"
      ;; a request that is in flight, done or failed is three things a swap!
      ;; cannot express, and a button that looks like it did nothing is the
      ;; failure this whole capability exists to remove
      (is (= :done (get-in @state [:call :status])) (pr-str (:call @state)))
      (is (= {:status 200} (get-in @state [:call :response])) (pr-str (:call @state))))

    (testing "an action declared effectful with no :webapp/request-for REFUSES"
      ;; naming an effect and supplying no request is a control that cannot
      ;; work; doing nothing quietly is the one outcome ruled out
      (let [bare (webapp/wiring
                  {:webapp/state   (atom {})
                   :webapp/routes  []
                   :webapp/act     (fn [s _ _] s)
                   :webapp/actions {:thing/delete {:effectful? true}}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"request-for"
                              ((:dispatch (webapp/driver bare)) [:thing/delete 1] nil)))))))

(deftest effect-state-MERGES-because-a-request-keeps-its-inputs-beside-its-status
  ;; slopp-ui's catch, and it would have bitten them on migration. Their effect
  ;; entry holds `{:params … :armed? … :status … :request … :response …}` — the
  ;; params are what the reader TYPED into the call form and `:armed?` is their
  ;; consent, and both live in the same entry as the status.
  ;;
  ;; A framework `perform!` that writes a FRESH map blanks them the moment the
  ;; call starts: the form empties while it is running, and the panel whose job
  ;; is to show WHAT WAS SENT beside what came back has lost the sent half in
  ;; the window where it matters most.
  ;;
  ;; **This is the opposite of a LOAD, and the asymmetry is the point.**
  ;; `begin-load` REPLACES its entry, correctly — a fetch has no inputs the
  ;; reader supplied, so there is nothing to preserve and a stale entry would
  ;; only be a lie. An effect's inputs and its status are one entry, so it
  ;; merges.
  (let [state (atom {})
        app   (webapp/wiring
               {:webapp/state       state
                :webapp/routes      [["/" (fn [_s] [:p "home"])] ["/other" (fn [_s] [:p "other"])]]
                :webapp/chrome      (fn [_s inner] inner)
                :webapp/act         (fn [s _ _] s)
                :webapp/actions     {:go {:effectful? true}}
                :webapp/request-for (fn [s _action]
                                      ;; nil until the reader consents — the
                                      ;; DECLINE channel, not a missing value
                                      (when (:armed? (:call s))
                                        {:method :post :path "/go"}))
                :webapp/call        (fn [_req ok _err] (ok {:status 200}))})
        {:keys [dispatch]} (webapp/driver app)]

    (testing "a nil request DECLINES — nothing is called and nothing is written"
      ;; the arming gate lives in the pure derivation so neither the browser
      ;; shell nor the headless one can forget it; if perform! threw here, the
      ;; gate would have to move somewhere a JVM test cannot read
      (swap! state assoc :call {:params {:name "x"}})
      (dispatch [:go] nil)
      (is (= {:params {:name "x"}} (:call @state))
          (str "a decline must not write a status: " (pr-str (:call @state)))))

    (testing "and when it consents, the INPUTS survive the call starting"
      (swap! state update :call assoc :armed? true)
      (dispatch [:go] nil)
      (is (= {:name "x"} (get-in @state [:call :params]))
          (str "the reader's typed inputs must still be there beside the answer: "
               (pr-str (:call @state))))
      (is (true? (get-in @state [:call :armed?])) (pr-str (:call @state)))
      (is (= :done (get-in @state [:call :status])) (pr-str (:call @state)))
      (is (= {:status 200} (get-in @state [:call :response])) (pr-str (:call @state))))

    (testing "navigating away DOES clear it — leaving the panel is leaving it"
      ;; the same display-honesty argument as :data. A call panel showing the
      ;; previous screen's request under a new url is the page and the address
      ;; bar disagreeing
      (webapp/navigate! app "/elsewhere" false)
      (is (nil? (:call @state)) (pr-str @state)))))

(deftest what-dies-with-the-ADDRESS-is-declared-not-hardcoded
  ;; slopp-ui's objection, and it is my own critique of their `nav` handed back:
  ;; clearing `:call` unconditionally is a MEMBERSHIP decision made in the loop
  ;; rather than declared by the app. Same shape as a framework namespace
  ;; clearing an application's keys, with a framework-owned key instead.
  ;;
  ;; **The rule that decides membership is address-vs-session**, and their
  ;; reason for it is sharper than staleness: a call form's inputs are not
  ;; merely stale under the new route, they are TYPED to the old one. `:m` is a
  ;; parameter of `/api/module/:m` and means nothing to `/api/search`, so
  ;; carrying it shows a form claiming the new endpoint takes arguments it does
  ;; not have.
  ;;
  ;; But an effect panel is not ALWAYS address-scoped — a compose box, a global
  ;; command palette, a filter spanning screens. Clear as law and those apps
  ;; have no seam, so their inputs live somewhere the framework cannot see,
  ;; which is how state ends up in two places.
  ;;
  ;; So: the DEFAULT is address-scoped, because most call panels are and a
  ;; framework should be right without configuration — and it is a declared set
  ;; rather than a literal, so an app can say otherwise and can name its own
  ;; keys too.
  (let [mk (fn [extra]
             (let [state (atom {})]
               [state (webapp/wiring
                       (merge {:webapp/state  state
                               :webapp/routes [["/a" (fn [_s] [:p "a"])]
                                               ["/b" (fn [_s] [:p "b"])]]}
                              extra))]))]

    (testing "by DEFAULT the effect entry dies with the address"
      (let [[state app] (mk nil)]
        (swap! state assoc :call {:params {:m "slopp.http"}})
        (webapp/navigate! app "/search" false)
        (is (nil? (:call @state))
            (str "a form typed to the old endpoint must not claim the new one"
                 " takes those arguments: " (pr-str @state)))))

    (testing "an app whose effect state is SESSION-scoped says so, and keeps it"
      ;; a compose box survives navigation because it is not about the address
      (let [[state app] (mk {:webapp/address-keys #{}})]
        (swap! state assoc :call {:params {:body "half a message"}})
        (webapp/navigate! app "/search" false)
        (is (= {:params {:body "half a message"}} (:call @state)) (pr-str @state))))

    (testing "and the declaration reaches the app's OWN keys, not just :call"
      ;; which is what makes this the rule rather than a switch for one key
      (let [[state app] (mk {:webapp/address-keys #{:call :lens}})]
        (swap! state assoc :call {:params {:m "x"}} :lens :detail :kept true)
        (webapp/navigate! app "/search" false)
        (is (nil? (:call @state)) (pr-str @state))
        (is (nil? (:lens @state)) (pr-str @state))
        (is (true? (:kept @state))
            (str "and touches nothing it was not told about: " (pr-str @state)))))

    (testing "LOADS are not address-scoped at all any more, and that is not this rule"
      ;; They used to be cleared on every navigation except the ones
      ;; `:webapp/session-loads` declared — a membership rule an app had to
      ;; write, which existed because a screen's data was started BY the
      ;; navigation and so obviously belonged to it.
      ;;
      ;; A page ASKS for what it needs while rendering, so a load's lifetime is
      ;; answered by whether anything still asks: the next page re-asks and
      ;; finds it already there, and what nobody wants sits until `stale!`
      ;; drops it. The declaration AND the escape an app reached for when the
      ;; scope rule pushed a load out of the building both go.
      (let [state (atom {})
            app   (webapp/wiring {:webapp/state        state
                                  :webapp/routes       []
                                  :webapp/address-keys #{}})]
        (swap! state assoc :loads {[:get "/api/x"] {:status :ready :value [:old]}})
        (webapp/navigate! app "/search" false)
        (is (= :ready (webapp/load-status @state [:get "/api/x"]))
            (str "a load survives a navigation: what the next page still wants"
                 " it re-asks for and finds, and stale! is the explicit drop: "
                 (pr-str @state)))))))

(deftest the-mount-PREFIX-goes-on-and-comes-off-symmetrically
  ;; An app served behind a proxy is mounted under a prefix it cannot work out
  ;; from its own URL: `/p/slopp2/store` and `/store` are indistinguishable
  ;; without being told. So the mount point arrives as data, and every url
  ;; crossing the boundary is prefixed on the way out and stripped on the way
  ;; in.
  ;;
  ;; Written as a PAIR, and asserted as a round trip, for the reason
  ;; `slopp.lang`'s encoder gives about its decoder: two functions written apart
  ;; are two guesses. The failure they prevent is a link that works when served
  ;; at the root and 404s behind the proxy, which is the one place nobody tests.
  (testing "prefixed puts the mount point on"
    (is (= "/things" (webapp/prefixed "" "/things")))
    (is (= "/p/x/things" (webapp/prefixed "/p/x" "/things"))))

  (testing "strip-base takes it off"
    (is (= "/things" (webapp/strip-base "" "/things")))
    (is (= "/things" (webapp/strip-base "/p/x" "/p/x/things"))))

  (testing "and the round trip holds for every base"
    ;; the property, not three examples: whatever went out comes back
    (doseq [base ["" "/p/x" "/deep/mount/point"]
            path ["/" "/things" "/things/42" "/search?q=rate"]]
      (is (= path (webapp/strip-base base (webapp/prefixed base path)))
          (str "round trip failed for base " (pr-str base) " path " (pr-str path)))))

  (testing "the mount point ITSELF is the root path"
    ;; `/p/x` with nothing after it is the app's `/`, not the empty string —
    ;; an empty path routes to nothing and renders a blank page at a url that
    ;; looks right
    (is (= "/" (webapp/strip-base "/p/x" "/p/x")))
    (is (= "/" (webapp/strip-base "/p/x" "/p/x/"))))

  (testing "a path OUTSIDE the mount point is not ours to strip"
    ;; returning it unchanged would claim a foreign url is an app path; nil
    ;; says it belongs to somebody else
    (is (nil? (webapp/strip-base "/p/x" "/other/thing")))
    ;; and a prefix match must be at a SEGMENT boundary — /p/xylophone is not
    ;; under /p/x, and a naive starts-with? says it is
    (is (nil? (webapp/strip-base "/p/x" "/p/xylophone/thing")))))

(deftest the-current-path-includes-the-QUERY-STRING
  ;; A bug slopp-ui already paid for, carried here so it cannot be re-derived.
  ;; A url built from `location.pathname` alone routes `/store/search?q=rate`
  ;; to a search screen with an EMPTY query — a box that forgot what was typed
  ;; between pressing enter and the page loading.
  ;;
  ;; It is the shape worth naming rather than the instance: the browser splits
  ;; a url across two properties, the app's router reads one string, and a shim
  ;; that reads the obvious property loses the other half. **And it will look
  ;; correct** — the screen renders, the url is right in the address bar, and
  ;; only the contents of one field are wrong.
  ;;
  ;; This is `:cljc` so the JVM can assert it. The shim's whole job is reading
  ;; two properties and handing them here, which is the split the wave is for:
  ;; the decision is testable, the interop is not.
  (testing "pathname and search are rejoined"
    (is (= "/search?q=rate" (webapp/app-path "" "/search" "?q=rate"))))

  (testing "an empty search adds no question mark"
    ;; "/things?" and "/things" are different strings and only one routes
    (is (= "/things" (webapp/app-path "" "/things" "")))
    (is (= "/things" (webapp/app-path "" "/things" nil))))

  (testing "and the mount point comes off the PATH, not the query"
    ;; the prefix belongs to the pathname; stripping across the join would eat
    ;; a query that happened to contain the base as text
    (is (= "/search?q=rate" (webapp/app-path "/p/x" "/p/x/search" "?q=rate")))
    (is (= "/search?base=/p/x" (webapp/app-path "/p/x" "/p/x/search" "?base=/p/x"))))

  (testing "a url outside the mount point is not this app's path"
    (is (nil? (webapp/app-path "/p/x" "/other" "?q=1"))))

  (testing "the mount root with a query is the app's root with that query"
    (is (= "/?tab=all" (webapp/app-path "/p/x" "/p/x" "?tab=all")))))

(deftest which-clicks-are-OURS-is-a-decision-not-a-chain-of-ands
  ;; The rules deciding whether a click belongs to the app were, in slopp-ui's
  ;; shell, a chain of `and`s in a `:cljs` namespace nothing could check. They
  ;; are four independent judgements and each one is a real behaviour:
  ;;
  ;;   plain left-click   a middle-click opens a tab, cmd-click opens a tab,
  ;;                      and hijacking either is a browser that lies
  ;;   in-app only        an external link must leave; preventDefault on one
  ;;                      is a dead link
  ;;   routed only        an unrouted path FALLS THROUGH to the server, so a
  ;;                      wrong url stays a 404 instead of rendering nothing
  ;;   an href at all     a click on a button is not a navigation
  ;;
  ;; The third is the one worth stating twice. Swallowing an unrouted in-app
  ;; path turns every typo into a blank screen at a plausible url, which is the
  ;; SPA failure that makes a site feel broken rather than missing.
  (let [routes [["/things" :ok] ["/" :ok]]
        ;; namespaced, because the shim NAMES the properties it reads off the
        ;; event — what crosses into the decision is slopp's own data rather
        ;; than a browser object whose shape nobody declared
        click  (fn [m] (webapp/click-target
                        (merge {:webapp/button 0}
                               (into {} (for [[k v] m]
                                          [(keyword "webapp" (name k)) v])))
                        "/p/x" routes))]

    (testing "a plain left-click on a routed in-app link is ours"
      (is (= "/things" (click {:href "/p/x/things"}))))

    (testing "a MIDDLE click is not — it opens a tab, and always has"
      (is (nil? (click {:href "/p/x/things" :button 1}))))

    (testing "each MODIFIER is its own reading, and the decision is here"
      ;; The shim's alternative is `(or (.-metaKey e) (.-ctrlKey e) …)` — an
      ;; `or` in the one namespace whose only verification is that it compiled.
      ;; And it would be a judgement rather than an interop read: WHICH gestures
      ;; mean "open elsewhere" is a fact about browsers and platforms, and the
      ;; four differ (cmd on mac, ctrl elsewhere, shift a new window, alt a
      ;; download). So the shim reads four booleans and names them, and which
      ;; of them suppress a navigation is decided where a JVM test can read it.
      (doseq [k [:meta? :ctrl? :shift? :alt?]]
        (is (nil? (click {:href "/p/x/things" k true}))
            (str k " opens a tab, a window or a download on some platform —"
                 " hijacking it is a browser that lies about its own gestures"))))

    (testing "an EXTERNAL link leaves, and must"
      ;; preventDefault here is a link that looks live and does nothing
      (is (nil? (click {:href "https://example.com/things"})))
      (is (nil? (click {:href "/other/app"}))))

    (testing "an UNROUTED in-app path falls through to the server"
      ;; the one that makes a site feel broken rather than missing: swallow it
      ;; and a typo renders an empty screen at a url that looks valid
      (is (nil? (click {:href "/p/x/nope"}))
          "an unrouted path is the server's answer to give, which is a 404"))

    (testing "and a click with no href is not a navigation at all"
      (is (nil? (click {:href nil})))
      (is (nil? (click {}))))

    (testing "the mount ROOT is routable like any other path"
      ;; `/p/x` is the app's `/`, and a link to it is as ordinary as any
      (is (= "/" (click {:href "/p/x"}))))))

(deftest a-CLICK-and-a-BACK-BUTTON-are-decided-where-a-test-can-watch
  ;; The two listeners a browser app registers, and neither may decide anything
  ;; in the browser. [[click-target]] already says WHICH clicks are ours; what
  ;; is missing is what happens next — `preventDefault` and a push — and that is
  ;; the half that goes wrong invisibly:
  ;;
  ;;   preventDefault on a link that is NOT ours -> a dead link
  ;;   no preventDefault on one that IS          -> a full page load, and the
  ;;                                                app restarts on every click
  ;;   a push on the BACK button                 -> back stops working, because
  ;;                                                every pop adds an entry
  ;;
  ;; All three compile. All three are ordinary assertions here.
  (let [state     (atom {})
        pushed    (atom [])
        prevented (atom 0)
        things    (fn [_s] [:p "things"])
        search    (fn [page] [:p (str "search " (:q (:params page)))])
        ;; a row's target is normalised to a screen VALUE, so the fn an app
        ;; wrote is that screen's `:render`
        ;; a row names the PAGE FUNCTION itself, so :screen IS the fn an app
        ;; wrote — there is no wrapper to look inside
        showing   (fn [] (:screen @state))
        app       (webapp/wiring
                   {:webapp/state     state
                    :webapp/base      "/p/x"
                    :webapp/routes    [["/things" things]
                                       ["/search" search]]
                    :webapp/push-url! (fn [u] (swap! pushed conj u))})
        click!    (fn [m] (webapp/click! app (merge {:webapp/button 0} m)
                                         (fn [] (swap! prevented inc))))]

    (testing "a click that IS ours navigates, pushes, and swallows the default"
      (click! {:webapp/href "/p/x/things"})
      (is (= things (showing)))
      (is (= ["/p/x/things"] @pushed) "the pushed url carries the mount prefix")
      (is (= 1 @prevented) "without this the browser also loads the page"))

    (testing "a click that is NOT ours is left entirely alone"
      ;; the failure is a link that looks live and does nothing, and it is the
      ;; reason `click-target` answers nil rather than the path unchanged
      (click! {:webapp/href "https://example.com/things"})
      (is (= 1 @prevented) "preventDefault here is a dead external link")
      (is (= 1 (count @pushed)))
      (is (= things (showing))))

    (testing "the BACK button arrives as a url and must not push"
      ;; a push on a pop is the bug that makes back appear broken: each press
      ;; adds an entry, so the button walks the reader forward through their
      ;; own history and never leaves
      (webapp/navigate-url! app "/p/x/search" "" false)
      (is (= search (showing)))
      (is (= 1 (count @pushed)) (pr-str @pushed)))

    (testing "and the url's QUERY reaches the router PARSED, not as text"
      ;; the browser keeps the two halves in separate properties; a handler that
      ;; reads pathname alone routes /search?q=rate to an empty box and LOOKS
      ;; right. Now that routes are a table, the framework parses the query as
      ;; well as carrying it — so a screen receives `{:q "rate"}` rather than a
      ;; url fragment it would have to take apart itself
      (webapp/navigate-url! app "/p/x/search" "?q=rate" false)
      (is (= search (showing)))
      (is (= {:q "rate"} (:params @state)) (pr-str (:params @state))))

    (testing "a url outside the mount point is not this app's to show"
      (webapp/navigate-url! app "/somewhere/else" "" false)
      (is (= search (showing))
          "routing a foreign url through the app blanks the screen it was on"))))

(deftest LEAVING-the-app-is-a-third-kind-of-action-and-declared-like-the-others
  ;; Some controls are neither a state transition nor a request: they hand the
  ;; page back to the browser. slopp-ui's project switcher is the real one — a
  ;; different project is served under a different mount point, so the app it is
  ;; running IS a different app and cannot be reached by a client route.
  ;;
  ;; It is a third kind rather than a special case, and it is DECLARED for the
  ;; same reason `:effectful?` is: written by hand it becomes a condition in two
  ;; dispatchers that have to agree, and the one in the browser is the one
  ;; nothing can check.
  (let [state  (atom {})
        left   (atom [])
        acted  (atom [])
        called (atom [])
        app    (webapp/wiring
                {:webapp/state       state
                 ;; an app that routes nothing declares no rows — which is a
                 ;; table, and says so
                 :webapp/routes      []
                 :webapp/chrome      (fn [_s inner] inner)
                 :webapp/actions     {:project/switch {:leaves? true}
                                      :thing/run      {:effectful? true}}
                 ;; this switcher spells its destination INTO the action, which
                 ;; is still the right shape for a link or a button — the value
                 ;; is for the control that cannot, i.e. a `<select>`
                 :webapp/url-for     (fn [_s action _v] (when (second action)
                                                          (str "/p/" (second action) "/store")))
                 :webapp/request-for (fn [_s _action] {:path "/run"})
                 :webapp/call        (fn [req ok _err] (swap! called conj req) (ok :done))
                 :webapp/act         (fn [s action _v] (swap! acted conj action) s)
                 :webapp/leave!      (fn [url] (swap! left conj url))})]

    (testing "a :leaves? action goes to the leave plug-in, with the app's url"
      (webapp/dispatch! app [:project/switch "b"] nil)
      (is (= ["/p/b/store"] @left)))

    (testing "and does NOT also transition or make a request"
      ;; running two halves is how two dispatchers drift back apart — each does
      ;; the half its author was thinking about
      (is (= [] @acted))
      (is (= [] @called)))

    (testing "the other two kinds still go where they went"
      (webapp/dispatch! app [:thing/run] nil)
      (is (= [{:path "/run"}] @called))
      (webapp/dispatch! app [:thing/rename] "x")
      (is (= [[:thing/rename]] @acted))
      (is (= 1 (count @left)) "neither of those is a leave"))

    (testing "a nil url DECLINES, exactly as a nil request does"
      ;; the pure derivation's channel for refusing: an unarmed switcher, a
      ;; selection not yet made. Throwing here would push every app's arming
      ;; pattern into the browser
      (webapp/dispatch! app [:project/switch nil] nil)
      (is (= 1 (count @left)) (pr-str @left)))

    (testing "and a :leaves? action with no :webapp/url-for REFUSES"
      ;; the same refusal `:effectful?` gets without a request: a control that
      ;; appears to work and quietly does nothing is the outcome ruled out
      (let [bare (webapp/wiring {:webapp/state   (atom {})
                                 :webapp/routes  []
                                 :webapp/actions {:project/switch {:leaves? true}}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #":webapp/url-for"
                              (webapp/dispatch! bare [:project/switch "b"] nil)))))))

(deftest what-happens-at-PAGE-LOAD-is-two-steps-and-neither-is-the-shim's
  ;; A browser entry does exactly two things when the bundle runs: it lets the
  ;; app start whatever belongs to no particular screen, and it shows the url
  ;; the reader arrived at. Both are decisions — the ORDER especially, since
  ;; boot is where session-scoped loads begin and the first screen may read
  ;; them — so both are here rather than in the shim.
  (let [state  (atom {})
        booted (atom 0)
        things (fn [_s] [:p "things"])
        app    (webapp/wiring
                {:webapp/state  state
                 ;; nil, because that is what `(.getAttribute el "data-base")`
                 ;; answers for an app served at the root: the attribute is
                 ;; simply absent. The shim reads and does not interpret
                 :webapp/base   nil
                 :webapp/routes [["/things" things]]
                 :webapp/boot   (fn [s] (swap! booted inc) (assoc s :session "abc"))})]

    (testing "a mount point the DOM does not carry means the ROOT"
      (is (= "" (:webapp/base app))
          "nil would prefix every pushed url with the string \"null\""))

    (testing "boot runs once, and its writes survive the first routing"
      (webapp/start! app "/things" "")
      (is (= 1 @booted))
      (is (= "abc" (:session @state))
          "arriving must not clear what boot established — a session token
           cleared on the first navigation is a page that logs itself out")
      (is (= things (:screen @state))))

    (testing "and an app that declares no boot still starts"
      ;; the default has to be a function rather than nil, or every caller —
      ;; the driver, the entry, the next one — writes the same `or`
      (let [s2 (atom {})
            ok (fn [_s] [:p "ok"])
            a2 (webapp/wiring {:webapp/state  s2
                               :webapp/routes [["/anything" ok]]})]
        (is (fn? (:webapp/boot a2)))
        (webapp/start! a2 "/anything" "")
        (is (= ok (:screen @s2)))))))

(deftest a-page-with-no-MOUNT-POINT-is-refused-by-NAME
  ;; The server's template renders `<div id="app">` and the bundle mounts into
  ;; it. When the template does not, every browser says
  ;; `Cannot read properties of null (reading 'getAttribute')` — in a console
  ;; nobody has open, under a blank page, naming a property rather than the
  ;; thing that is missing.
  ;;
  ;; This is the constructor refusal `wiring` and `screen/open!` already make,
  ;; one input further out, and it is checkable HERE despite being about a DOM
  ;; node: `nil?` is not a platform question, so the element crosses into `:cljc`
  ;; as an opaque value and the refusal is an ordinary test.
  (testing "an absent element is named, and so is what renders it"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"id=\"app\""
                          (webapp/mount-point nil))))

  (testing "and a present one passes straight through"
    ;; a string stands in for the element, which is the assertion: this asks
    ;; whether there IS a node and never what it is, so anything non-nil does
    (is (= "an element" (webapp/mount-point "an element")))))

(deftest a-route-TABLE-is-data-and-the-matching-agrees-with-the-server
  ;; Routing is data on the server half — `^{:http/path "/store/ns/:ns"}` on the
  ;; handler, derived by `web.routes/from-namespaces` — and a CLOSURE on the
  ;; client half. Inside one app, every check, report and derivation that exists
  ;; for one is impossible for the other, which is why `crossings` carries
  ;; `:webapp/client-routing` and `:webapp/client-path` as unchecked exits.
  ;;
  ;; A row is `[pattern target]` and the target is OPAQUE here: what a screen IS
  ;; is the next slice's question, and a matcher that does not care is one that
  ;; cannot be wrong about it.
  (let [routes [["/"             :index]
                ["/things"       :things]
                ["/things/:id"   :thing]
                ["/things/:id/edit" :edit]
                ["/files/**"    :files]]
        at     (fn [p] (webapp/match-route routes p))]

    (testing "a static segment beats a capture, whatever the order"
      ;; precedence is fewest-captures-wins rather than first-listed, matching
      ;; the server exactly — so adding a route can never steal an existing one
      (is (= :things (:screen (at "/things"))))
      (is (= :thing  (:screen (at "/things/42"))))
      (is (= {:id "42"} (:params (at "/things/42")))))

    (testing "a trailing catch-all takes the remainder, and ranks below both"
      ;; anonymous — bound under `:*`, because there can be at most one and a
      ;; name threaded through three generators was read by one of them
      (is (= :files (:screen (at "/files/a/b/c.txt"))))
      (is (= {:* "a/b/c.txt"} (:params (at "/files/a/b/c.txt"))))
      (is (= {:* ""} (:params (at "/files")))
          "** matches ZERO segments, so a section answers for its own root"))

    (testing "an unrouted path is nil, which is a real answer"
      ;; the app's own nowhere. Defaulting to a screen tells the reader they are
      ;; somewhere they are not
      (is (nil? (at "/nope")))
      (is (nil? (at "/things/42/nonsense"))))

    (testing "a trailing slash is tolerated, because a browser produces both"
      (is (= :things (:screen (at "/things/"))))
      (is (= :index  (:screen (at "/")))))

    (testing "the QUERY is parsed into params, not left for the app"
      ;; `app-path` rejoins search onto pathname precisely so it reaches here.
      ;; An app parsing its own query string is the browser's split leaking
      ;; through the framework
      (is (= :things (:screen (at "/things?q=rate&page=2"))))
      (is (= {:q "rate" :page "2"} (:params (at "/things?q=rate&page=2"))))
      (is (= {:id "42" :tab "logs"} (:params (at "/things/42?tab=logs")))
          "path captures and query keys land in one map"))

    (testing "and the query goes through the SAME parser the server uses"
      ;; one grammar, both sides — a bare key is present with an empty value
      (is (= {:flag ""} (:params (at "/things?flag"))))
      (is (= {:q "a b"} (:params (at "/things?q=a%20b")))))))

(deftest an-app-DECLARES-its-routes-rather-than-computing-them
  ;; The change that makes the client half checkable. A function answers only
  ;; when called, with a path, at runtime — so nothing can list an app's screens,
  ;; join a link to one, or compare the client's table to the server's. Every
  ;; report and gate that exists for `^{:http/path}` was impossible here for
  ;; exactly that reason, and `crossings` records the two holes it leaves.
  (let [state  (atom {})
        pushed (atom [])
        things (fn [_s] [:p "things"])
        thing  (fn [s] [:p (str "thing " (:id (:params s)))])
        app   (webapp/wiring
               {:webapp/state     state
                :webapp/base      "/p/x"
                :webapp/routes    [["/things"     things]
                                   ["/things/:id" thing]]
                :webapp/push-url! (fn [u] (swap! pushed conj u))})]

    (testing "navigation routes through the declared table"
      (webapp/navigate! app "/things/42" false)
      (is (= thing (:screen @state))
          "a row names the PAGE FUNCTION itself — there is no wrapper to look inside")
      (is (= {:id "42"} (:params @state))))

    (testing "a click is OURS only when the table routes it"
      ;; `click-target`'s fourth judgement now reads the same table, so an
      ;; unrouted in-app path still falls through to the server and a typo stays
      ;; a 404 rather than a blank screen at a plausible url
      (let [click (fn [href] (webapp/click-target {:webapp/href href :webapp/button 0}
                                                  "/p/x" (:webapp/routes app)))]
        (is (= "/things/42" (click "/p/x/things/42")))
        (is (nil? (click "/p/x/nope")))))

    (testing "a FUNCTION is refused, and the message says what to write"
      ;; no back-compat shim: the refusal carries the migration, because a
      ;; silently-accepted function is a store that keeps every hole this change
      ;; exists to close
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)table|\[\[\"/"
           (webapp/wiring {:webapp/state  (atom {})
                           :webapp/routes (fn [p] (when (= "/x" p) {:screen :x}))}))))

    (testing "and the table is READABLE — which is the whole point"
      ;; an app's screens are now a value anything can list: a gate joining a
      ;; link to a route, a report showing a human the map, a check comparing
      ;; this against the prefixes the server answers for
      (is (= ["/things" "/things/:id"] (mapv first (:webapp/routes app)))))))

(deftest a-route-points-at-a-SCREEN-and-the-view-is-DERIVED
  ;; What `:screen` used to be: a bare keyword that had to agree in three
  ;; separate places — the routes fn returned it, the view cased on it, the
  ;; fetch received it — with nothing checking any of the three. Rename one and
  ;; the app renders a blank pane at a url that looks right.
  ;;
  ;; A row points at the screen FUNCTION now, so the three agreements collapse
  ;; into one var reference the reference graph can see. There is no `:screen`
  ;; keyword left to mistype.
  (let [state (atom {})
        things (fn [_s] [:ul [:li "Anvil"]])
        thing  (fn [page] [:p (str "Thing " (:id (:params page)))])
        app   (webapp/wiring
               {:webapp/state  state
                :webapp/routes [["/things"     things]
                                ["/things/:id" thing]]
                :webapp/chrome (fn [_s inner] [:main [:h1 "Catalogue"] inner])})
        s     (cljnx/open! (webapp/driver app))]

    (testing "the app declares no :webapp/view — slopp derives it"
      (is (fn? (:webapp/view app))
          "the driver still needs one; what changed is who writes it"))

    (testing "visiting a route renders that screen INSIDE the app's chrome"
      (cljnx/visit! s "/things")
      (let [t (cljnx/text s)]
        (is (re-find #"Catalogue" t) t)
        (is (re-find #"Anvil" t) t)))

    (testing "and the matched row's captures reach the screen it points at"
      (cljnx/visit! s "/things/42")
      (is (re-find #"Thing 42" (cljnx/text s)) (cljnx/text s)))

    (testing "an unrouted path renders NOT-FOUND, never a blank pane"
      ;; the failure this closes: with routes as data slopp KNOWS nothing
      ;; matched, so rendering nothing is a choice rather than an accident — and
      ;; a blank pane at a plausible url is indistinguishable from a screen
      ;; whose content is empty
      (cljnx/visit! s "/nope")
      (is (seq (str/trim (cljnx/text s)))
          "an unmatched path rendered nothing at all"))

    (testing "and the app can say what not-found LOOKS like"
      (let [s2 (cljnx/open!
                (webapp/driver
                 (webapp/wiring
                  {:webapp/state     (atom {})
                   :webapp/routes    [["/things" things]]
                   :webapp/not-found (fn [_s] [:p "Nowhere"])})))]
        (cljnx/visit! s2 "/nope")
        (is (re-find #"Nowhere" (cljnx/text s2)) (cljnx/text s2))))

    (testing "declaring :webapp/view is REFUSED, and the message says why"
      ;; no back-compat: a hand-written view is the thing that made `:screen` a
      ;; keyword agreeing in three places
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)derived|chrome"
           (webapp/wiring {:webapp/state  (atom {})
                           :webapp/routes []
                           :webapp/view   (fn [_] [:div])}))))
(testing "a route pointing at a KEYWORD is refused, not silently blank"
      ;; the trap making targets callable would otherwise introduce: a keyword
      ;; is `ifn?`, so `[["/things" :things]]` calls cleanly and renders nil —
      ;; a blank pane on a route that MATCHED, which not-found cannot cover
      ;; because nothing went wrong. Same for a map, a set or a vector
      (doseq [target [:things 'things {} #{} []]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"(?i)page"
             (webapp/wiring {:webapp/state  (atom {})
                             :webapp/routes [["/things" target]]}))
            (str (pr-str target) " is callable and renders nothing"))))))

(deftest slopp-PREFIXES-in-app-links-so-a-view-writes-CLIENT-paths
  ;; The join `crossings` reports as `:webapp/client-path`, and its stated
  ;; reason for being unchecked:
  ;;
  ;;   nothing joins the three parts up. The literal is a client route, the
  ;;   mount point arrives from the render, and a :webapp/client-routes fallback
  ;;   answers the result — so a typo'd literal, a prefix that stopped being
  ;;   applied, and a client route nobody registered all look the same.
  ;;
  ;; Two of the three parts are slopp's now. The mount point is declared, and
  ;; the route table is data — so the framework can do the prefixing itself and
  ;; a literal in a view becomes unambiguously a CLIENT ROUTE KEY. That is what
  ;; makes it checkable; every app writing its own `prefix-links` is what made
  ;; it not.
  (let [things (fn [_s] [:main
                         [:a {:href "/things"} "Things"]
                         [:a {:href "/things/42"} "Anvil"]
                         ;; not routed — the server's, or somebody else's
                         [:a {:href "/api/modules"} "raw"]
                         [:a {:href "https://example.com/things"} "elsewhere"]
                         [:form {:action "/api/save"} [:button "Save"]]
                         ;; a GET form that NAVIGATES: its action is a client
                         ;; route and it is spelled `action` only because a text
                         ;; box comes with it
                         [:form {:action "/search"} [:input {:name "q"}]]])
        routes [["/things" things] ["/things/:id" things] ["/search" things]]
        at     (fn [base] (webapp/prefix-links base routes (things {})))]

    (testing "a link the client ROUTES gets the mount point"
      (let [tree (at "/p/x")]
        (is (= "/p/x/things" (get-in tree [1 1 :href])))
        (is (= "/p/x/things/42" (get-in tree [2 1 :href])))))

    (testing "a link it does NOT route is left exactly alone"
      ;; the discrimination that makes this safe rather than a blanket rewrite:
      ;; `/api/modules` is the server's path and prefixing it would break the
      ;; one link the app cannot re-route
      (let [tree (at "/p/x")]
        (is (= "/api/modules" (get-in tree [3 1 :href])))
        (is (= "https://example.com/things" (get-in tree [4 1 :href])))))

    (testing "a form ACTION obeys the SAME rule, because it is the same question"
      ;; This was `:href` only, on the reasoning that an `:action` submits to a
      ;; server and rewriting one would point a POST at a screen. Right about
      ;; the POST and wrong about the scope, reported by the app it broke: a
      ;; search box written as a GET form is a NAVIGATION — `act` is pure and
      ;; navigating is an effect, so a form needs no dispatcher and no bundle —
      ;; and its action is a client route that happens to be spelled `action`.
      ;;
      ;; No new judgement is needed: `match-route` already separates them, and
      ;; it is the same call that keeps `/api/modules` from being prefixed as an
      ;; href. The failure runs the safe way too — an action that should be
      ;; prefixed and is not silently leaves the app, while one that should not
      ;; be matches no row.
      (let [tree (at "/p/x")]
        (is (= "/api/save" (get-in tree [5 1 :action]))
            "a POST to the server's own path must not be pointed at a screen")
        (is (= "/p/x/search" (get-in tree [6 1 :action]))
            (str "a GET form whose action IS a client route submitted to the"
                 " mount ROOT — a different application entirely"))))

    (testing "at the ROOT the transform changes nothing"
      ;; `prefixed` with "" is identity, so an app served at the root pays
      ;; nothing and reads identically
      (is (= (things {}) (at ""))))

    (testing "and what slopp PREFIXES is exactly what click-target CLAIMS"
      ;; the round trip that makes the pair a pair: a link the render prefixed
      ;; is a link the click handler will strip and route. If these two ever
      ;; disagree, every link in the app is either dead or leaves the page
      (doseq [base ["" "/p/x" "/deep/mount"]]
        (let [tree (webapp/prefix-links base routes (things {}))
              href (get-in tree [1 1 :href])]
          (is (= "/things"
                 (webapp/click-target {:webapp/href href :webapp/button 0}
                                      base routes))
              (str "render and click disagree under base " (pr-str base)
                   " — href was " (pr-str href))))))))

(deftest what-FETCH-needs-is-decided-here-and-merely-PERFORMED-in-the-browser
  ;; The shim may not branch, which is the property standing in for the tests a
  ;; `:cljs` namespace cannot have. So every judgement `js/fetch` needs is made
  ;; in `:cljc` and handed over as data: the method, the headers, WHICH encoder
  ;; the body wants, and which decoder the answer wants. What is left in the
  ;; browser is one `fetch` and two lookups.
  ;;
  ;; Naming the encoder rather than running it is the part that matters. A
  ;; `(if body …)` in the shim would be a decision nothing can check; a `:json`
  ;; keyword chosen here is one this test reads.
  ;;
  ;; The keys are `:http/*` because a request is an HTTP request:
  ;; `slopp.rest.client/call!` sends the identical map server-to-server with no
  ;; browser anywhere, so wearing `webapp`'s prefix was a claim about who reads
  ;; it that was never true.
  (testing "a GET names NO encoder, so the shim sends no body at all"
    ;; `fetch` throws on a GET carrying a body, so this is not tidiness — a
    ;; request that quietly acquires an empty body stops working entirely
    (let [init (endpoint/request-init {:http/url "/api/things"})]
      (is (= "GET" (:method init)) (pr-str init))
      (is (= :none (:encode init)) (pr-str init))
      (is (nil? (:body init)) (pr-str init))))

  (testing "a request WITH a body names the json encoder and says so in a header"
    (let [init (endpoint/request-init {:http/method :put
                                     :http/url    "/api/things/1"
                                     :http/body   {:name "Anvil"}})]
      (is (= "PUT" (:method init)) (pr-str init))
      (is (= :json (:encode init)) (pr-str init))
      (is (= {:name "Anvil"} (:body init)) (pr-str init))
      (is (= "application/json" (get (:headers init) "Content-Type")) (pr-str init))))

  (testing "an app's own headers are carried, and win over the default"
    ;; a token is STATE, not schema — it cannot be derived from an endpoint
    ;; declaration, so the shape has to carry headers or an authenticated app
    ;; falls straight back to writing its own fetch
    (let [init (endpoint/request-init {:http/url     "/api/me"
                                     :http/headers {"Authorization" "Bearer t"}})]
      (is (= "Bearer t" (get (:headers init) "Authorization")) (pr-str init)))
    (let [init (endpoint/request-init {:http/method  :post
                                     :http/url     "/api/upload"
                                     :http/body    "raw"
                                     :http/headers {"Content-Type" "text/plain"}})]
      (is (= "text/plain" (get (:headers init) "Content-Type"))
          (str "a declared content type must win, or an app can never send"
               " anything but json: " (pr-str init)))))

  (testing "a body of FALSE or nil are different requests"
    ;; the nil-pun this framework keeps removing, in the one place it would
    ;; silently drop a value: `false` is a body somebody meant to send
    (is (= :json (:encode (endpoint/request-init {:http/url "/x" :http/body false}))))
    (is (= :none (:encode (endpoint/request-init {:http/url "/x" :http/body nil})))))

  (testing "the ENCODER follows the declared content type"
    ;; json is the default and was briefly the only thing sendable, which is
    ;; the asymmetry the consuming app named: slopp's own API publishes
    ;; `application/edn`, so a framework that can only send json cannot POST to
    ;; the endpoints slopp itself serves
    (is (= :edn (:encode (endpoint/request-init
                          {:http/url     "/x"
                           :http/body    {:a 1}
                           :http/headers {"Content-Type" "application/edn"}}))))
    (is (= :json (:encode (endpoint/request-init
                           {:http/url     "/x"
                            :http/body    {:a 1}
                            :http/headers {"Content-Type" "application/json; charset=utf-8"}})))
        "the parameters come off before the lookup, the same as on the way back")
    (is (= :text (:encode (endpoint/request-init
                           {:http/url     "/x"
                            :http/body    "raw"
                            :http/headers {"Content-Type" "text/plain"}})))
        (str "a type slopp does not encode for sends the body AS GIVEN — which"
             " is honest, where guessing json would corrupt it"))
    (is (= :none (:encode (endpoint/request-init
                           {:http/url     "/x"
                            :http/headers {"Content-Type" "application/edn"}})))
        "a declared type on a request with NO body still sends no body"))

  (testing "a content-type header is reduced to the MEDIA TYPE a decoder is keyed by"
    ;; the browser answers `application/json; charset=utf-8`, and an exact
    ;; lookup on that misses — so the normalisation is here rather than being a
    ;; string-split in the namespace nothing can test
    (is (= "application/json" (endpoint/media-type "application/json; charset=utf-8")))
    (is (= "application/json" (endpoint/media-type "APPLICATION/JSON")))
    (is (= "text/csv" (endpoint/media-type "  text/csv  ")))
    (is (nil? (endpoint/media-type nil))
        "an answer with no content-type has no media type — not an empty one")
    (is (nil? (endpoint/media-type "")))))

(deftest a-RESPONSE-becomes-an-answer-or-a-failure-here-not-in-the-browser
  ;; The bug this closes is the one every hand-written `fetch` has on its first
  ;; day: `fetch` only rejects on a NETWORK error, so a 500 resolves happily and
  ;; the screen renders the error page's body as if it were data. The check that
  ;; prevents it is `(<= 200 status 299)` — a branch, in the namespace that may
  ;; not have one.
  ;;
  ;; So the status decision is here, and the shim looks the callback up by the
  ;; keyword this returns.
  (testing "a 2xx is an answer, and the value passes through untouched"
    (is (= [:ok {:name "Anvil"}] (webapp/response-outcome 200 {:name "Anvil"})))
    (is (= [:ok nil] (webapp/response-outcome 204 nil)))
    (is (= [:ok false] (webapp/response-outcome 200 false))
        "a body of false is an answer, not an absence"))

  (testing "anything else is a FAILURE, and the message names the status"
    ;; a reader looking at the failure pane needs to tell 404 from 500 — one is
    ;; a url that does not exist and the other is a server that broke
    (let [[kind message] (webapp/response-outcome 404 nil)]
      (is (= :failed kind))
      (is (re-find #"404" message) message))
    (is (= :failed (first (webapp/response-outcome 500 {:error "boom"})))))

  (testing "and a server that SAID what went wrong has that carried through"
    ;; slopp's own endpoints answer a map with an explanation, and dropping it
    ;; for a bare status code is throwing away the only useful half
    (let [[_ message] (webapp/response-outcome 422 {:error "name is required"})]
      (is (re-find #"name is required" message) message))
    (let [[_ message] (webapp/response-outcome 500 {:message "upstream timeout"})]
      (is (re-find #"upstream timeout" message) message))
    (let [[_ message] (webapp/response-outcome 503 "Service Unavailable")]
      (is (re-find #"Service Unavailable" message) message))))

(deftest a-REQUEST-carries-the-mount-point-like-every-other-address-does
  ;; The break 4d shipped, found by the app it broke: `:webapp/base` was applied
  ;; in exactly three places — `push-url!`, `strip-base` on an arriving url, and
  ;; `prefix-links` for `:href`/`:action` — and never to a REQUEST.
  ;;
  ;;   GET /api/modules              → 404   ← where the framework sent it
  ;;   GET /p/slopp-ui/api/modules   → 200   ← where it lives
  ;;
  ;; Not one pane. EVERY screen in a mounted app fetches a 404, so the
  ;; capability does not work for any app served anywhere but a root. Their
  ;; generated client had carried the base itself; the framework taking over
  ;; performing is what dropped it.
  ;;
  ;; The asymmetry IS the bug, in their words: what `prefix-links` does to an
  ;; `:href` and `strip-base` does to an arriving url, nothing did to a request.
  ;; So this makes the base-aware paths three-for-three, with no new vocabulary:
  ;; an app writes `/api/modules` and gets its own mount point for the same
  ;; reason its `:href` does.
  ;;
  ;; The request carries `:http/url` — RESOLVED, by
  ;; `slopp.http.endpoint/request` — so the mount is applied to a finished
  ;; address. `:webapp/base` keeps the browser prefix because a mount is the
  ;; one genuinely browser-shaped fact in the request.
  (let [called (atom [])
        state  (atom {})
        screen (fn [page]
                 (webapp/ask! page {:http/method :get :http/path "/api/things"} {})
                 [:p "x"])
        app    (webapp/wiring
                {:webapp/state       state
                 :webapp/base        "/p/demo"
                 :webapp/routes      [["/things" screen]]
                 :webapp/actions     {:thing/save {:effectful? true}}
                 :webapp/request-for (fn [_s _a] {:http/method :put
                                                  :http/url    "/api/things/1"})
                 :webapp/call        (fn [rq ok _err]
                                       (swap! called conj (:http/url rq))
                                       (ok nil))})]

    (testing "a SCREEN's request is fetched under the mount point"
      (webapp/navigate! app "/things" false)
      (is (= ["/p/demo/api/things"] @called)
          (str "an app served behind a proxy fetched the root — which is a"
               " different application, or nothing: " (pr-str @called))))

    (testing "and so is an effectful CONTROL's"
      ;; the second performer path, and it would otherwise be right once and
      ;; wrong once in the same app — the shape that reads as a flaky endpoint
      (reset! called [])
      (webapp/dispatch! app [:thing/save] nil)
      (is (= ["/p/demo/api/things/1"] @called) (pr-str @called)))

    (testing "an ABSOLUTE url is somebody else's server and is left alone"
      ;; the one judgement in it, and the same one `prefix-links` makes: a
      ;; third-party API is not under this app's mount point and prefixing it
      ;; would break the one request the app cannot re-address
      (reset! called [])
      (let [st (atom {})
            a2 (webapp/wiring
                {:webapp/state  st
                 :webapp/base   "/p/demo"
                 :webapp/routes [["/rates" (fn [page]
                                             (webapp/ask! page {:http/method :get
                                                                :http/path "https://api.example.com/v1/rates"} {})
                                             [:p "r"])]
                                 ["/proto" (fn [page]
                                             (webapp/ask! page {:http/method :get
                                                                :http/path "//cdn.example.com/x.json"} {})
                                             [:p "p"])]]
                 :webapp/call   (fn [rq ok _err]
                                  (swap! called conj (:http/url rq))
                                  (ok nil))})]
        (webapp/navigate! a2 "/rates" false)
        (webapp/navigate! a2 "/proto" false)
        (is (= ["https://api.example.com/v1/rates" "//cdn.example.com/x.json"] @called)
            (pr-str @called))))

    (testing "a request can say it is measured from the ORIGIN, not the mount"
      ;; the limit this shipped with, now with an instance. The app served at
      ;; /p/<slug> also calls the HUB, whose endpoint is at the origin root — a
      ;; genuinely different application at the same origin. It cannot write an
      ;; absolute url, because the origin is only known at runtime, and
      ;; prefixing sends the request to a project that does not serve it.
      ;;
      ;; Said as a VALUE — `:webapp/base ""` — rather than as the boolean
      ;; `:webapp/from-origin` this shipped with. That flag was an escape from
      ;; a field that could not hold two values; once the field holds any base,
      ;; the escape has no cause and is gone rather than deprecated.
      (reset! called [])
      (let [st (atom {})
            a4 (webapp/wiring
                {:webapp/state  st
                 :webapp/base   "/p/demo"
                 :webapp/routes [["/mine"   (fn [page]
                                              (webapp/ask! page {:http/method :get
                                                                 :http/path "/api/modules"} {})
                                              [:p "m"])]
                                 ["/theirs" (fn [page]
                                              ;; a request may name the base it is
                                              ;; measured from, which is why ask!
                                              ;; takes the built request's own keys
                                              ;; through untouched
                                              (webapp/ask! page {:http/method :get
                                                                 :http/path "/api/projects"
                                                                 :webapp/base ""} {})
                                              [:p "t"])]]
                 :webapp/call   (fn [rq ok _err]
                                  (swap! called conj (:http/url rq))
                                  (ok nil))})]
        (webapp/navigate! a4 "/mine" false)
        (webapp/navigate! a4 "/theirs" false)
        (is (= ["/p/demo/api/modules" "/api/projects"] @called)
            (str "a request measured from the origin took the mount point"
                 " anyway, so it reached a project that does not serve it: "
                 (pr-str @called)))))

    (testing "at the ROOT nothing changes, so an unmounted app reads identically"
      (reset! called [])
      (let [st (atom {})
            a3 (webapp/wiring
                {:webapp/state  st
                 :webapp/routes [["/things" screen]]
                 :webapp/call   (fn [rq ok _err]
                                  (swap! called conj (:http/url rq))
                                  (ok nil))})]
        (webapp/navigate! a3 "/things" false)
        (is (= ["/api/things"] @called) (pr-str @called))))))

(deftest ONE-declaration-reaches-both-entries-and-neither-is-hand-written
  ;; This test used to be `a-PAGE-cannot-be-both-the-inspection-entry-and-the-
  ;; browser-entry`, and it pinned a fork: `slopp.build/webapp-launcher-source`
  ;; had no caller because the entry marker was asked for two incompatible
  ;; things —
  ;;
  ;;   the fake browser        {:state :view :navigate :dispatch :boot}
  ;;   slopp.webapp.dom/mount!  the WIRING DECLARATION
  ;;
  ;; — so a generated `(mount! (entry))` refused at page load for any app whose
  ;; entry could be opened headlessly.
  ;;
  ;; **It was never a fork between two shapes.** It was one function holding
  ;; two capabilities' adapters: the fake browser took a served ctx and
  ;; performed the request itself, so http's adapter lived inside the namespace
  ;; that also has to drive browser apps — and vendoring is per FAMILY, so the
  ;; second adapter could never join the first. Naming both adapters
  ;; (`slopp.http/driver`, `slopp.webapp/driver`) and deriving through one
  ;; public `cljnx/driver-for` dissolves it.
  ;;
  ;; So the DECLARATION is the one value and both entries derive from it. What
  ;; is asserted here is the property that makes a generated browser entry safe
  ;; to write: the same map reaches both, and neither side is hand-wired.
  (let [declared {:webapp/state  (atom {})
                  :webapp/routes [["/things" (fn [_s] [:p "t"])]]}]

    (testing "the declaration MOUNTS — the browser entry wires what it is given"
      ;; `dom/mount!` calls `wiring` itself, over its own effect plug-ins, so
      ;; what it needs is the declaration and never a derived map
      (is (map? (webapp/wiring declared))))

    (testing "and the same declaration OPENS, through the public derivation"
      ;; the half that used to be impossible, and the whole reason the launcher
      ;; had no caller
      (let [s (cljnx/open! (cljnx/driver-for declared))]
        (cljnx/visit! s "/things")
        (is (re-find #"\bt\b" (cljnx/text s nil {:detail :prose})))))

    (testing "a DERIVED map is still refused by the browser entry's wiring"
      ;; the property that keeps the direction honest: derivation goes one way,
      ;; so an entry handing back a driver cannot be mounted — and returning one
      ;; is the mistake the marker's rename makes loud rather than silent
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)unknown wiring key"
           (webapp/wiring (webapp/driver (webapp/wiring declared))))))

    (testing "and the derivation is the SAME one the tool uses"
      ;; not a reconstruction of it — two derivations of one app drift, and
      ;; each passes against its own
      (is (= (set (keys (webapp/driver (webapp/wiring declared))))
             (set (keys (cljnx/driver-for declared))))))))

(deftest a-REQUEST-may-name-the-base-it-is-measured-from
  ;; `:http/url` because a request is an HTTP request; `:webapp/base` keeps the
  ;; browser prefix because a MOUNT is the one genuinely browser-shaped fact in
  ;; it, and this is the only thing that reads it.
  (testing "absent, the app's own mount point applies, exactly as before"
    (is (= "/p/demo/api/things"
           (:http/url (webapp/addressed "/p/demo" {:http/url "/api/things"})))))
  (testing "present, it WINS — the request is measured from the api it belongs to"
    (is (= "/p/other/api/things"
           (:http/url (webapp/addressed "/p/demo"
                                        {:http/url "/api/things"
                                         :webapp/base "/p/other"})))
        "a client-routed app switches which upstream it is reading WITHOUT a
         page load, so no value stamped once at load can be right — the app's
         base is a default, not the answer"))
  (testing "empty means the ORIGIN, which is what :webapp/from-origin said"
    (is (= "/api/projects"
           (:http/url (webapp/addressed "/p/demo"
                                        {:http/url "/api/projects"
                                         :webapp/base ""})))))
  (testing "the RETIRED flag is not read — no backwards compatibility"
    ;; `:webapp/from-origin` was a boolean escape from a field that could not
    ;; hold two values. `:webapp/base ""` says the same thing as a VALUE, so
    ;; the flag's cause is gone — and a mechanism kept alive past its cause is
    ;; the thing this codebase keeps removing, not something to carry for a
    ;; consumer's convenience.
    ;;
    ;; Same stance `auth-test/capabilities-values-parse-into-auth-config` and
    ;; `capabilities-test/every-capability-key-declares-its-owner` already
    ;; take about retired spellings: they resolve to nothing.
    (is (= "/p/demo/api/projects"
           (:http/url (webapp/addressed "/p/demo"
                                        {:http/url "/api/projects"
                                         :webapp/from-origin true})))
        "a retired marker waives nothing while reading as though it does —
         which is worse than its absence, so it reads as absent"))
  (testing "an absolute url is still left alone, whatever base is named"
    (is (= "https://other.example/api/x"
           (:http/url (webapp/addressed "/p/demo"
                                        {:http/url "https://other.example/api/x"
                                         :webapp/base "/p/other"}))))))

(deftest a-page-ASKS-for-what-it-needs-and-asking-twice-is-one-load
  ;; The shape that replaces the route-row spec map. A page names ONE
  ;; `:request` today and the framework performs it, which is why one screen
  ;; can load one thing. A page that ASKS can load several, conditionally, and
  ;; in whatever order its own logic wants — and it is still a pure function a
  ;; JVM test calls, because `:webapp/call` is the seam.
  ;;
  ;; **Start-if-absent is the whole contract.** A page is re-run each time a
  ;; load resolves, so an `ask` that started a fetch every time it was called
  ;; would fetch forever. The load's identity is the REQUEST it builds —
  ;; method plus url — so asking for the same thing twice in one render, or
  ;; again on the next, is one load.
  (let [calls (atom [])
        state (atom {})
        app   {:webapp/state  state
               :webapp/render (fn [_] nil)
               :webapp/call   (fn [rq ok _err]
                                (swap! calls conj (:http/url rq))
                                (ok {:name "Anvil"}))}
        page  (assoc app :state @state :params {:id "42"})
        thing {:http/method :get :http/path "/api/things/:id" :http/params #{:id}}]

    (testing "the first ask STARTS the load and answers with its state"
      (let [a (webapp/ask! page thing {:id "42"})]
        (is (= :ready (:status a)) (pr-str a))
        (is (= {:name "Anvil"} (:value a)) (pr-str a))
        (is (= ["/api/things/42"] @calls))))

    (testing "asking again for the SAME request does not fetch again"
      ;; the re-entrancy contract: a page re-runs on every resolution, so this
      ;; is not an optimisation — without it a page with one load spins
      (webapp/ask! page thing {:id "42"})
      (webapp/ask! page thing {:id "42"})
      (is (= 1 (count @calls)) (pr-str @calls)))

    (testing "a DIFFERENT request is a different load"
      (webapp/ask! page thing {:id "43"})
      (is (= ["/api/things/42" "/api/things/43"] @calls) (pr-str @calls)))

    (testing "stale! makes the next ask fetch again — explicitly, not by rule"
      ;; what a mutation needs. Automatic invalidation has several defensible
      ;; answers and no measurement behind any of them yet, so this is the
      ;; dull one that can BECOME automatic without a migration: an automatic
      ;; rule that guesses wrong costs a rewrite.
      (reset! calls [])
      (webapp/stale! page thing {:id "42"})
      (webapp/ask! page thing {:id "42"})
      (is (= ["/api/things/42"] @calls) (pr-str @calls)))

    (testing "and stale! on something never asked for is not an error"
      ;; an app clearing what a mutation MIGHT have staled should not have to
      ;; know whether this page happened to load it
      (is (nil? (webapp/stale! page thing {:id "999"}))))))

(deftest a-route-names-a-PAGE-FUNCTION-that-receives-ONE-map
  ;; The row spec map goes. A route named `{:render … :request … :check …
  ;; :derive …}` and the framework performed exactly one request for it — so
  ;; one screen could load one thing, and anything else went outside the load
  ;; machinery, which is where the only real webapp's nav pane acquired three
  ;; of the defects this namespace exists to prevent.
  ;;
  ;; A route names a FUNCTION now, and it receives ONE map — the same shape a
  ;; server handler takes one `req`. That is what lets a later key arrive
  ;; without changing every page's arity.
  (let [calls (atom [])
        state (atom {})
        thing {:http/method :get :http/path "/api/things/:id" :http/params #{:id}}
        tags  {:http/method :get :http/path "/api/things/:id/tags" :http/params #{:id}}
        page  (fn [{:keys [params] :as p}]
                ;; TWO loads, and the second only when the first arrived — the
                ;; whole point of asking rather than declaring
                (let [t (webapp/ask! p thing {:id (:id params)})]
                  (if (= :ready (:status t))
                    [:main (:name (:value t))
                     (:count (:value (webapp/ask! p tags {:id (:id params)})))]
                    [:main "Loading…"])))
        app   (webapp/wiring
               {:webapp/state  state
                :webapp/routes [["/things/:id" page]]
                :webapp/call   (fn [rq ok _err]
                                 (swap! calls conj (:http/url rq))
                                 (ok (if (re-find #"/tags$" (:http/url rq))
                                       {:count 3}
                                       {:name "Anvil"})))})]

    (testing "the page receives its route captures as :params"
      (webapp/navigate! app "/things/42" false)
      (is (= "/api/things/42" (first @calls)) (pr-str @calls)))

    (testing "and it can ask for a SECOND thing, conditionally"
      ;; a row's single `:request` could not express this at all
      (is (= ["/api/things/42" "/api/things/42/tags"] @calls) (pr-str @calls)))

    (testing "the page also receives the app STATE, so it renders against a snapshot"
      ;; passed rather than deref'd: a page is re-run each time a load
      ;; resolves, and two resolving close together would otherwise render
      ;; inconsistent halves
      (let [seen (atom nil)
            st   (atom {})
            a2   (webapp/wiring
                  {:webapp/state  st
                   :webapp/routes [["/x" (fn [p] (reset! seen p) [:main])]]
                   :webapp/call   (fn [_ ok _] (ok nil))})]
        (webapp/navigate! a2 "/x" false)
        (is (contains? @seen :state) (pr-str (keys @seen)))
        (is (contains? @seen :params) (pr-str (keys @seen)))
        (is (contains? @seen :webapp/state)
            (str "the page must be able to ask, which needs the app's own"
                 " keys: " (pr-str (keys @seen))))))

    (testing "a route pointing at something that is not a function is REFUSED"
      ;; a keyword, a set and a vector are all `ifn?`, so they call cleanly and
      ;; answer nil — a blank pane on a route that MATCHED, which
      ;; `:webapp/not-found` cannot cover because nothing went wrong
      (doseq [bad [:a-keyword #{:a :set} [:a :vector] {:render (fn [_] [:p])}]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"(?i)page"
             (webapp/wiring {:webapp/state  (atom {})
                             :webapp/routes [["/x" bad]]}))
            (str bad " was accepted as a page"))))))

(deftest a-loads-identity-is-the-address-it-actually-FETCHES
  ;; Measured by the only app that talks to more than one upstream, on the
  ;; migration that made it visible:
  ;;
  ;;   navigate /p/demo/store   → asks /api/p/demo/api/modules
  ;;   navigate /p/other/store  → asks NOTHING, renders demo's modules
  ;;
  ;; under the other project's name, with no way for a reader to tell. `ask!`
  ;; keyed on the request's own `:http/url`, and `:webapp/base` — which is
  ;; WHICH UPSTREAM that url means — was applied afterwards, inside `fetch!`.
  ;; So two genuinely different fetches were one load.
  ;;
  ;; **A cache hit is silent by construction.** No screen can show one and no
  ;; assertion about a rendered screen can catch it; they found it by reading a
  ;; recorded list of urls. That is what makes this the load model's own
  ;; question rather than an app's.
  ;;
  ;; `load-key`'s docstring already stated the rule it was breaking — *it is
  ;; the url that actually distinguishes one fetch from another*. The address
  ;; is resolved ONCE now, and identity and performance read the same answer.
  (let [asked   (atom [])
        wire    (fn [base]
                  (webapp/wiring
                   {:webapp/state  (atom {})
                    :webapp/base   base
                    :webapp/routes [["/x" (fn [_page] [:main "x"])]]
                    :webapp/call   (fn [request ok _err]
                                     (swap! asked conj (:http/url request))
                                     (ok {:status 200 :body {:names ["m"]}}))}))
        app     (wire "/p/demo")
        modules {:http/method :get :http/path "/api/modules"}]

    (testing "the same path under two BASES is two loads, and both are fetched"
      (webapp/ask! app modules {})
      (webapp/ask! (assoc app :webapp/base "/p/other") modules {})
      (is (= ["/p/demo/api/modules" "/p/other/api/modules"] @asked)
          (str "the second ask answered from the first project's load: "
               (pr-str @asked))))

    (testing "and asking the SAME one again is still one load"
      ;; the property start-if-absent exists for — a page is re-run every time
      ;; a load resolves, so a key that distinguished too finely fetches forever
      (webapp/ask! app modules {})
      (is (= 2 (count @asked)) (pr-str @asked)))

    (testing "a request naming its OWN base is keyed by that, not the app's"
      ;; the narrower declaration wins for identity exactly as it wins for the
      ;; address — they are the same fact read twice
      (webapp/ask! app (assoc modules :webapp/base "") {})
      (is (= ["/p/demo/api/modules" "/p/other/api/modules" "/api/modules"] @asked)
          (pr-str @asked)))

    (testing "and stale! drops the load that key names"
      (webapp/stale! app modules {})
      (webapp/ask! app modules {})
      (is (= 4 (count @asked)) (pr-str @asked))
      (is (= "/p/demo/api/modules" (last @asked)) (pr-str @asked)))))

(deftest an-ABSENT-route-table-names-the-CAUSE-not-the-shape
  ;; Nathan loaded the hub and got a blank page. Document served, bundle
  ;; loaded, nothing rendered:
  ;;
  ;;   mount! → wiring → ":webapp/routes is a declared TABLE, not a function"
  ;;
  ;; which reads as *you passed the wrong type* when the truth was *you passed
  ;; nothing, and nobody was going to*. An app does not write this table; the
  ;; GENERATED browser entry supplies it from the pages' markers — and the
  ;; build skips generating that entry when a store mounts the app itself.
  ;;
  ;; **A whole test suite could not see it.** `cljnx/driver-for` scans the
  ;; loaded vars and fills the table, so every headless drive stayed green
  ;; while the browser threw at startup. The store's own guard test — the one
  ;; whose docstring called itself the only thing between a bad declaration and
  ;; a blank page — derives through `driver-for`, so it could not have caught
  ;; this. One reader silently repairing what the other requires is what made
  ;; the green meaningless.
  (let [msg (fn [app] (try (webapp/wiring app) nil
                           (catch Exception e (ex-message e))))]

    (testing "ABSENT says what was going to supply it, and why nothing did"
      (let [m (msg {:webapp/state (atom {})})]
        (is (re-find #"never going to get one" (str m)) (pr-str m))
        (is (re-find #"generated browser entry" (str m))
            (str "it must name what supplies the table: " (pr-str m)))
        (is (re-find #"(?i)mounts the app itself" (str m))
            (str "and why this store did not get one: " (pr-str m)))
        (is (re-find #"driver-for" (str m))
            (str "and that a headless drive will not reproduce it, which is"
                 " the reason the suite was green: " (pr-str m)))))

    (testing "and a WRONG-TYPED table still says that instead"
      ;; the two are different mistakes and the messages must not merge — an
      ;; app that really did pass a routing function needs the old sentence
      (let [m (msg {:webapp/state (atom {}) :webapp/routes (fn [_path] nil)})]
        (is (re-find #"not a function" (str m)) (pr-str m))
        (is (not (re-find #"never going to get one" (str m))) (pr-str m))))))

(deftest a-load-that-RESOLVES-renders-through-the-render-attached-AFTER-wiring
  ;; Nathan loaded the hub. The white page was gone and it said `Loading…`
  ;; forever: `/api/projects` answered 200, the load went `:ready`, and the DOM
  ;; stayed on the loading branch. No console error, because nothing failed.
  ;;
  ;; `:webapp/view` is DERIVED, so a browser entry cannot supply a render until
  ;; after `wiring` — it needs the view to render. But `derived-view!` closed
  ;; over the app map AS IT WAS, so the map a PAGE receives carried the
  ;; DECLARED render. `ask!` takes its app from the page, `fetch!` from `ask!`,
  ;; `load!` calls `(render @state)` on it — the declared one. For any store
  ;; whose entry declares a placeholder render (which is correct for the
  ;; headless entry) a resolving load rendered into nothing.
  ;;
  ;; **Move A is what exposed it**: load-starting moved INTO the render, so the
  ;; only app in scope became the closure's. Before, `navigate!` and `start!`
  ;; started loads and were called with the MOUNTED app.
  ;;
  ;; **Nothing caught it, and the reason generalises.** Every headless drive is
  ;; green because `slopp.cljnx` calls the view explicitly on each step and
  ;; never relies on the render callback — so the browser is the only reader
  ;; that depends on `:webapp/render`, and it is the only reader nothing can
  ;; test. The consuming store's own first simulation PASSED and was wrong,
  ;; because it passed the render INTO the declaration, where the closure sees
  ;; it. **A fixture that supplies the render as part of the declaration cannot
  ;; see this**, which is why this one attaches it after.
  (let [renders (atom [])
        pending (atom [])
        state   (atom {})
        thing   {:http/method :get :http/path "/api/thing"}
        wired   (webapp/wiring
                 {:webapp/state  state
                  :webapp/routes [["/" (fn [page]
                                         (webapp/ask! page thing {})
                                         [:main "x"])]]
                  ;; the placeholder a headless entry correctly declares
                  :webapp/render (fn [_state] (swap! renders conj :DECLARED))
                  :webapp/call   (fn [_req ok _err] (swap! pending conj ok))})
        view    (:webapp/view wired)
        app     (webapp/with-render! wired (fn [st]
                                            (swap! renders conj :MOUNTED)
                                            (view st)))]

    (webapp/start! app "/" "")

    (testing "the page asked, so there is a load in flight to resolve"
      ;; without this the assertions below pass by never having a callback
      (is (= 1 (count @pending)) (pr-str @pending)))

    (testing "and when it RESOLVES the mounted render runs, not the declared one"
      (reset! renders [])
      ((first @pending) {:names ["a"]})
      (is (some #{:MOUNTED} @renders)
          (str "a resolving load rendered through the app captured at WIRING"
               " time, so the DOM never saw it: " (pr-str @renders)))
      (is (not (some #{:DECLARED} @renders))
          (str "the declared placeholder ran instead of the mounted render: "
               (pr-str @renders))))

    (testing "and the load really did land"
      ;; the half that makes the above a rendering claim rather than a
      ;; fetching one — state going :ready was never the broken part
      (is (= :ready (webapp/load-status @state
                                        (webapp/load-key nil (endpoint/request thing {}))))
          (pr-str @state)))))

(deftest the-FAILED-default-names-the-loads-that-actually-failed
  ;; It read `(:error (:main (:loads state)))`. There is no `:main` load under
  ;; `ask!` — a load is keyed by the ADDRESS it fetches — so the framework's
  ;; own fallback rendered an empty `<p>` for every failure, and the consuming
  ;; store wrote its own rather than use it.
  ;;
  ;; Residue of the one-load-per-screen model: `:main` was the name of the load
  ;; a screen declared, and when a page began asking for as many as it needed
  ;; the key stopped existing while the reader kept looking for it. A default
  ;; that renders nothing is worse than no default, because an app that has not
  ;; thought about failures gets silence instead of something honest.
  ;;
  ;; A page can now have SEVERAL loads and only some of them failed, so the
  ;; default names WHICH — the address is the thing a reader needs and the old
  ;; one did not print even when it worked.
  (let [failed (:webapp/failed
                (webapp/wiring {:webapp/state  (atom {})
                                :webapp/routes [["/" (fn [_p] [:main "x"])]]}))
        state  {:loads {[:get "/api/thing"]   {:status :failed
                                               :error "the endpoint's answer does not match"}
                        [:get "/api/other"]   {:status :ready :value 1}
                        [:post "/api/broken"] {:status :failed :error "500"}}}
        text   (pr-str (failed state))]

    (testing "every failed load's error appears"
      (is (re-find #"does not match" text) text)
      (is (re-find #"500" text) text))

    (testing "and each is named by the ADDRESS that failed"
      ;; a page with several loads renders this once; without the address a
      ;; reader cannot tell which pane is empty because of which failure
      (is (re-find #"/api/thing" text) text)
      (is (re-find #"/api/broken" text) text))

    (testing "a load that SUCCEEDED is not reported as a failure"
      (is (not (re-find #"/api/other" text)) text))

    (testing "and a state with nothing failed still renders something honest"
      ;; the default is reached because a PAGE chose to show it, so it must not
      ;; render an empty element when the page's own reason is not in :loads
      (is (seq (pr-str (failed {:loads {}})))))))

(deftest a-LEAVING-action-sees-the-VALUE-the-control-carried
  ;; `:webapp/act` is `[state action value]`, and its docstring says why the
  ;; third argument exists: the typed or selected text is the one thing the
  ;; view could not know, because everything else rides in the action itself.
  ;;
  ;; That is exactly as true of an action that LEAVES. A `<select>` has ONE
  ;; handler for N options by construction, so the chosen slug cannot be
  ;; spelled into the action vector the way a button's argument can, or into an
  ;; `:href` the way a link's can. It arrives as the value or not at all.
  ;;
  ;; Without it, a dropdown whose CHOICE IS THE DESTINATION cannot navigate on
  ;; change: the selection has to reach state first, and a `:leaves?` action
  ;; does not run the reducer — correctly, since an effect that also reduced is
  ;; how two dispatchers drift apart. So one interaction needs two controls, and
  ;; the second is a "go" button that exists only to read the state back. A
  ;; consumer shipped that button and marked it a workaround for this gap.
  ;;
  ;; Every dropdown-as-destination has this shape — project switcher, version
  ;; picker, branch selector, locale menu — so it belongs in the framework
  ;; rather than in each app.
  (let [went (atom nil)
        app  {:webapp/state   (atom {})
              :webapp/actions {:project/goto {:leaves? true}}
              :webapp/leave!  #(reset! went %)
              :webapp/url-for (fn [_state _action slug]
                                (when (seq slug) (str "/p/" slug)))}]

    (testing "the value the control carried reaches url-for"
      (webapp/dispatch! app [:project/goto] "slopp-ui")
      (is (= "/p/slopp-ui" @went)
          "url-for never saw the selected value, so a dropdown cannot be its own destination"))

    (testing "and nil still DECLINES, so the empty option is not a navigation"
      ;; the unarmed-switcher channel, unchanged — an empty selection is a real
      ;; state and must not throw or navigate
      (reset! went nil)
      (webapp/dispatch! app [:project/goto] "")
      (is (nil? @went) "an empty selection navigated somewhere"))))

(deftest a-page-may-answer-a-REDIRECT-and-the-framework-follows-it-in-place
  ;; The server's landing lists the open projects, and a list of ONE is not a
  ;; choice: the reader clicks the only row every time. A page can send them
  ;; on — but a page is pure and the framework owns navigation, so the page
  ;; ANSWERS the redirect as data and the framework performs it here, where a
  ;; test can watch. Two things a hand-rolled navigate-on-arrival gets wrong:
  ;; the url is REPLACED, never pushed, so the back button skips the page that
  ;; sent the reader on instead of bouncing them forward again; and the
  ;; destination renders in the SAME pass, so nothing paints the sender first.
  (let [state    (atom {:only "x"})
        replaced (atom [])
        pushed   (atom [])
        landing  (fn [{:keys [state]}]
                   (if-let [o (:only state)]
                     {:webapp/redirect (str "/p/" o)}
                     [:p "pick one"]))
        project  (fn [{:keys [params]}] [:h1 (str "project " (:slug params))])
        ping     (fn [_] {:webapp/redirect "/pong"})
        pong     (fn [_] {:webapp/redirect "/ping"})
        app      (webapp/wiring
                  {:webapp/state        state
                   :webapp/base         "/m"
                   :webapp/routes       [["/" landing] ["/p/:slug" project]
                                         ["/ping" ping] ["/pong" pong]]
                   :webapp/push-url!    (fn [u] (swap! pushed conj u))
                   :webapp/replace-url! (fn [u] (swap! replaced conj u))})
        view     (:webapp/view app)]

    (testing "arriving at a page that redirects shows the DESTINATION, in one render"
      (webapp/navigate! app "/" true)
      (is (= [:h1 "project x"] (view @state)))
      (is (= "/p/x" (:path @state)) "the app's own address moved with it"))

    (testing "the url is REPLACED under the mount point, and the arrival's push stands alone"
      (is (= ["/m/p/x"] @replaced)
          "replace, not push: back must go to where the reader came FROM, not to the page that sent them on")
      (is (= ["/m/"] @pushed) (pr-str @pushed)))

    (testing "a page with nothing to redirect for renders as it always did"
      (swap! state dissoc :only)
      (webapp/navigate! app "/" true)
      (is (= [:p "pick one"] (view @state)))
      (is (= 1 (count @replaced))))

    (testing "two pages sending the reader to each other is named as a LOOP, not left to hang"
      (let [m (try (webapp/navigate! app "/ping" true) nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (some? m) "a bounce forever is a hang, and a hang has no message")
        (is (str/includes? (str m) "/ping") m)
        (is (str/includes? (str m) "/pong") m)
        (is (str/includes? (str m) "loop") m)))

    (testing "driven headlessly, the address bar is the app's own — so it follows"
      (reset! state {:only "y"})
      (let [s (cljnx/open! (webapp/driver app) "/m/")]
        (is (str/includes? (cljnx/of (cljnx/tree s)) "project y") (cljnx/text s))
        (is (= "/m/p/y" (cljnx/url s))
            "a browser's address bar shows where the reader ENDED UP")))))
