(ns slopp.webapp-test
  "The browser app's loop, and the DERIVATION of a headless driver from it.

  Nothing here starts a browser or compiles ClojureScript, which is the whole
  claim: an agent reads what a dynamic page SAYS by driving the app's real
  wiring on a JVM. A test that needed a bundle would prove the opposite."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.webapp :as webapp]
            [slopp.web.screen :as web.screen] [clojure.string :as str]))

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
        ;; every one of them assertable without a browser
        things (fn [s] (case (webapp/load-status s :main)
                         ;; the four-state reader, not (if (:data s) …) — which
                         ;; is the nil-pun this framework removes
                         :ready  [:ul (for [t (webapp/load-value s :main)]
                                        [:li (:name t)])]
                         :failed [:p "Could not load"]
                         [:p "Loading…"]))
        thing  (fn [s] [:p (str "Thing " (:id (:params s)))])
        app    (webapp/wiring
                {:webapp/state     state
                 :webapp/routes    [["/things"     things]
                                    ["/things/:id" thing]]
                 :webapp/chrome    (fn [_s inner] [:main [:h1 "Catalogue"] inner])
                 :webapp/not-found (fn [_s] [:p "Nowhere"])
                 ;; the app's own data source. In a browser this is the generated
                 ;; typed client; here it answers from memory, which is exactly
                 ;; the seam that makes the loop drivable at all
                 :webapp/fetch     (fn [screen params ok _err]
                                     (swap! asked conj [(if (= screen things) :things :thing)
                                                        params])
                                     (ok (when (= screen things)
                                           [{:name "Anvil"} {:name "Rope"}])))})
        s      (web.screen/open! (webapp/driver app))]

    (testing "the derived driver is the shape the fake browser accepts"
      ;; if this drifts, every assertion below fails in a way that looks like
      ;; an app bug rather than a derivation bug
      (is (= #{:state :view :navigate :dispatch :boot}
             (set (keys (webapp/driver app))))
          (pr-str (keys (webapp/driver app)))))

    (testing "visiting a client route renders what the app would show"
      (web.screen/visit! s "/things")
      (let [t (web.screen/text s)]
        (is (re-find #"Catalogue" t) t)
        (is (re-find #"Anvil" t) t)
        (is (re-find #"Rope" t) t)))

    (testing "and the app's own fetch was asked, with the route's params"
      ;; two wirings can reach equal state having asked different endpoints,
      ;; because canned answers do not care who asked — so the REQUESTS are
      ;; part of the claim, not colour
      (is (= [[:things {}]] @asked) (pr-str @asked)))

    (testing "a second navigation re-routes and re-renders through the same loop"
      (web.screen/visit! s "/things/42")
      (is (re-find #"Thing 42" (web.screen/text s)) (web.screen/text s))
      ;; a capture arrives as the TEXT that was in the url — the framework does
      ;; not guess that "42" wanted to be a number, because a slug and an id
      ;; live in the same slot and only the app knows which this is
      (is (= [[:things {}] [:thing {:id "42"}]] @asked) (pr-str @asked)))

    (testing "an unrouted path is the app's own nowhere, not an exception"
      (web.screen/visit! s "/nope")
      (is (re-find #"Nowhere" (web.screen/text s)) (web.screen/text s)))))

(deftest a-load-that-has-not-been-ASKED-is-not-a-load-that-answered-NIL
  ;; slopp-ui caught this on the first read, and half their diagnosis applied.
  ;; I claimed `arrive` bumps rather than clears; they read that as losing
  ;; display honesty. It does not — `arrive` clears `:data`, so the previous
  ;; screen's answers never show under the new url.
  ;;
  ;; **What it did lose is the four-state load model**, and that is the real
  ;; defect. A token COUNTER answers "is this answer still wanted" and nothing
  ;; else, so `:absent` — nothing has been requested for this screen yet — was
  ;; never representable. A view could only ask `(if (:data s) …)`, which reads
  ;; an unasked load and a load that answered NIL as the same thing. That is
  ;; the nil-pun the model exists to remove, in the framework that exists to
  ;; remove nil-puns.
  ;;
  ;; Three concerns, three mechanisms, and I had merged two:
  ;;   display honesty  — clearing the previous screen's data
  ;;   what is KNOWN    — :absent -> :loading -> :ready | :failed
  ;;   supersession     — a token minted per request, checked on arrival
  (let [state   (atom {})
        pending (atom nil)
        thing   (fn [_s] [:p "thing"])
        app     (webapp/wiring
                 {:webapp/state  state
                  :webapp/routes [["/thing" thing]]
                  ;; hold the callback so the LOADING moment is observable —
                  ;; a fetch that answers synchronously never has one
                  :webapp/fetch  (fn [_screen _params ok _err] (reset! pending ok))})]

    (testing "before anything is asked, the load is ABSENT"
      (is (= :absent (webapp/load-status @state :main)) (pr-str @state)))

    (testing "while the request is out it is LOADING — a moment the reader can see"
      (webapp/navigate! app "/thing" false)
      (is (= :loading (webapp/load-status @state :main)) (pr-str @state)))

    (testing "an answer of NIL is READY, and that is the whole point"
      ;; :ready-with-nil and :absent are different statements about the world:
      ;; "this screen has no things" versus "nobody has asked yet"
      (@pending nil)
      (is (= :ready (webapp/load-status @state :main)) (pr-str @state))
      (is (nil? (webapp/load-value @state :main)) (pr-str @state)))

    (testing "navigating away makes it ABSENT again, not stale-READY"
      ;; the true statement about the new screen: nothing has been requested
      ;; for it. Leaving it :ready would say the new screen had answered
      (webapp/navigate! app "/nowhere" false)
      (is (= :absent (webapp/load-status @state :main)) (pr-str @state)))

    (testing "and a failure is FAILED, distinct from both"
      (webapp/navigate! app "/thing" false)
      (let [err (atom nil)
            app2 (webapp/wiring
                  {:webapp/state  state
                   :webapp/routes [["/thing" thing] ["/other" thing]]
                   :webapp/fetch  (fn [_s _p _ok e] (reset! err e))})]
        (webapp/navigate! app2 "/thing" false)
        (@err "no")
        (is (= :failed (webapp/load-status @state :main)) (pr-str @state))
        (is (= "no" (get-in @state [:loads :main :error])) (pr-str @state))))))

(deftest a-SUPERSEDED-answer-does-not-land-on-the-screen-that-replaced-it
  ;; I placed `:load-seq` outside `:loads` on the argument that a token drawn
  ;; from the map `arrive` empties would restart at 1 every navigation — so a
  ;; slow answer from the previous screen would match the new screen's first
  ;; request and land on it. Then I shipped it without asserting that failure.
  ;;
  ;; slopp-ui pinned the same property on their side and, checking their
  ;; coverage rather than claiming it, found the test that LOOKS like it covers
  ;; supersession stays green through the break — because it FABRICATES a
  ;; superseded token instead of producing one, so it asserts the guard and
  ;; never the token supply.
  ;;
  ;; So this produces the collision the honest way: navigate, hold the first
  ;; screen's callback, navigate again, then answer the FIRST one.
  ;;
  ;; **MEASURED rather than reasoned**, by breaking the subject: moving the
  ;; counter inside `:loads` — the map `arrive` empties — makes both
  ;; navigations mint token 1, and `:data-from-a` lands on screen `b` with
  ;; the load marked `:ready`. The other two tests in this namespace stay
  ;; green through that break, so this is the only cover for the placement.
  (let [state    (atom {})
        pending  (atom [])
        screen-a (fn [_s] [:p "a"])
        screen-b (fn [_s] [:p "b"])
        app      (webapp/wiring
                  {:webapp/state  state
                   :webapp/routes [["/a" screen-a] ["/b" screen-b]]
                   :webapp/fetch  (fn [screen _params ok _err]
                                    (swap! pending conj [screen ok]))})]

    (webapp/navigate! app "/a" false)
    (webapp/navigate! app "/b" false)

    (testing "POSITIVE CONTROL: the second navigation really emptied and re-minted"
      ;; without this the collision cannot arise and every assertion below
      ;; passes vacuously — which is the exact defect this test exists about
      (is (= 2 (count @pending)) (pr-str (count @pending)))
      (is (= screen-b (:screen @state)) (pr-str (:path @state)))
      (is (= :loading (webapp/load-status @state :main)) (pr-str @state)))

    (testing "the FIRST screen's answer arrives late and is dropped"
      (let [[_ ok-a] (first @pending)]
        (ok-a :data-from-a))
      (is (nil? (webapp/load-value @state :main))
          (str "a superseded answer landing is the stale-screen failure: " (pr-str @state)))
      (is (= :loading (webapp/load-status @state :main))
          (str "and it must not mark the NEW screen ready either: " (pr-str @state))))

    (testing "while the SECOND screen's answer still lands"
      ;; the arm that makes the drop a discrimination rather than a blanket
      ;; refusal — a guard that dropped everything would satisfy the above
      (let [[_ ok-b] (second @pending)]
        (ok-b :data-from-b))
      (is (= :data-from-b (webapp/load-value @state :main)) (pr-str @state))
      (is (= :ready (webapp/load-status @state :main)) (pr-str @state)))))

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
        (swap! state assoc :call {:params {:m "slopp.web"}})
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

    (testing "the loop's OWN machinery clears regardless of the declaration"
      ;; :data, :error and :loads are written by the loop, so clearing them is
      ;; not a membership decision about the app's state — an app cannot opt a
      ;; screen's fetched answer into surviving the screen.
      ;;
      ;; Routed to NOWHERE on purpose: with no screen there is no fetch, so
      ;; :absent is observable. On a routed screen the default fetch answers
      ;; synchronously and the load is :ready before anything can look — which
      ;; is correct, and is why asserting :absent there would have been
      ;; asserting the wrong thing rather than finding a bug.
      (let [state (atom {})
            app   (webapp/wiring {:webapp/state        state
                                  :webapp/routes       []
                                  :webapp/address-keys #{}})]
        (swap! state assoc :loads {:main {:status :ready :value [:old]}})
        (webapp/navigate! app "/search" false)
        (is (= :absent (webapp/load-status @state :main))
            (str "nothing has been requested for this screen, which is the true"
                 " statement and the one a bumped token cannot make: "
                 (pr-str @state)))
        (is (nil? (webapp/load-value @state :main))
            (str "and the previous screen's answer goes with the status — an app"
                 " opts a load into surviving by naming it in :webapp/session-loads,"
                 " not by the loop deciding: " (pr-str @state)))))))

(deftest a-SESSION-scoped-load-keeps-the-machinery-it-would-otherwise-lose
  ;; slopp-ui disagreed with the boundary I asked them to disagree with, and the
  ;; evidence is a defect in their app that exists BECAUSE they obeyed my rule.
  ;;
  ;; I had written: the loop writes `:loads`, so the loop clears it, and an app
  ;; cannot opt a fetched answer into surviving the screen. That reasons from
  ;; AUTHORSHIP, and it conflates two things the loop owns. It owns a load's
  ;; MACHINERY — four states, the token, the supersession guard — which is not
  ;; negotiable. It was also deciding the load's SCOPE, and scope is the
  ;; address-vs-session question only the app can answer.
  ;;
  ;; Their module nav is fetched once and used on every Code screen. Because
  ;; `:loads` was emptied on every navigation they kept it outside `:loads`, and
  ;; outside `:loads` it got NONE of the machinery. What that produced, in the
  ;; one load the framework was not allowed to cover:
  ;;
  ;;   - `(nil? (:modules @state))` as the guard — the nil-pun, so absent,
  ;;     failed and answered-with-nothing are one value
  ;;   - a silent retry loop: the catch swallows, the value stays nil, and every
  ;;     later navigation fetches again. A failing endpoint is hit once per
  ;;     navigation forever and the reader is told nothing
  ;;   - no freshness token, in the one place that app fetches outside the loop
  ;;
  ;; Three of the defects this namespace exists to prevent, caused by the scope
  ;; rule sending the load out of the building.
  (let [state  (atom {})
        calls  (atom 0)
        answer (atom nil)
        app    (webapp/wiring
                {:webapp/state         state
                 :webapp/routes        [["/code"   (fn [_s] [:p "code"])]
                                        ["/change" (fn [_s] [:p "change"])]
                                        ["/other"  (fn [_s] [:p "other"])]]
                 :webapp/session-loads #{:modules}})
        fetch! (fn [ok err]
                 (swap! calls inc)
                 (reset! answer [ok err]))]

    (testing "a session load gets the four states like any other"
      (is (= :absent (webapp/load-status @state :modules)) (pr-str @state))
      (webapp/load! app :modules fetch!)
      (is (= :loading (webapp/load-status @state :modules)) (pr-str @state))
      ((first @answer) [:a :b])
      (is (= :ready (webapp/load-status @state :modules)) (pr-str @state))
      (is (= [:a :b] (webapp/load-value @state :modules)) (pr-str @state)))

    (testing "and it SURVIVES navigation, which is the whole disagreement"
      (webapp/navigate! app "/code" false)
      (is (= :ready (webapp/load-status @state :modules)) (pr-str @state))
      (is (= [:a :b] (webapp/load-value @state :modules)) (pr-str @state)))

    (testing "while an ordinary load does not"
      (webapp/load! app :other fetch!)
      ((first @answer) :something)
      (is (= :ready (webapp/load-status @state :other)))
      (webapp/navigate! app "/ns" false)
      (is (= :absent (webapp/load-status @state :other)) (pr-str @state)))

    (testing "a FAILED session load stays failed — which is what stops the retry loop"
      ;; the defect in their app: a swallowed failure leaves the value nil, the
      ;; guard reads nil as never-asked, and every navigation fetches again. A
      ;; recorded :failed is a state an app can guard on and a reader can be told
      (let [s2  (atom {})
            n   (atom 0)
            cbs (atom nil)
            app2 (webapp/wiring
                  {:webapp/state         s2
                   :webapp/routes        [["/code"   (fn [_s] [:p "code"])]
                                          ["/change" (fn [_s] [:p "code"])]]
                   :webapp/session-loads #{:modules}})]
        (webapp/load! app2 :modules (fn [ok err] (swap! n inc) (reset! cbs [ok err])))
        ((second @cbs) "boom")
        (is (= :failed (webapp/load-status @s2 :modules)) (pr-str @s2))
        (webapp/navigate! app2 "/code" false)
        (is (= :failed (webapp/load-status @s2 :modules))
            (str "a failure that survives is one an app can decline to retry: "
                 (pr-str @s2)))
        (is (= 1 @n) "and nothing re-fetched it behind the app's back")))))

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
        search    (fn [s] [:p (str "search " (:q (:params s)))])
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
      (is (= things (:screen @state)))
      (is (= ["/p/x/things"] @pushed) "the pushed url carries the mount prefix")
      (is (= 1 @prevented) "without this the browser also loads the page"))

    (testing "a click that is NOT ours is left entirely alone"
      ;; the failure is a link that looks live and does nothing, and it is the
      ;; reason `click-target` answers nil rather than the path unchanged
      (click! {:webapp/href "https://example.com/things"})
      (is (= 1 @prevented) "preventDefault here is a dead external link")
      (is (= 1 (count @pushed)))
      (is (= things (:screen @state))))

    (testing "the BACK button arrives as a url and must not push"
      ;; a push on a pop is the bug that makes back appear broken: each press
      ;; adds an entry, so the button walks the reader forward through their
      ;; own history and never leaves
      (webapp/navigate-url! app "/p/x/search" "" false)
      (is (= search (:screen @state)))
      (is (= 1 (count @pushed)) (pr-str @pushed)))

    (testing "and the url's QUERY reaches the router PARSED, not as text"
      ;; the browser keeps the two halves in separate properties; a handler that
      ;; reads pathname alone routes /search?q=rate to an empty box and LOOKS
      ;; right. Now that routes are a table, the framework parses the query as
      ;; well as carrying it — so a screen receives `{:q "rate"}` rather than a
      ;; url fragment it would have to take apart itself
      (webapp/navigate-url! app "/p/x/search" "?q=rate" false)
      (is (= search (:screen @state)))
      (is (= {:q "rate"} (:params @state)) (pr-str (:params @state))))

    (testing "a url outside the mount point is not this app's to show"
      (webapp/navigate-url! app "/somewhere/else" "" false)
      (is (= search (:screen @state))
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
                 :webapp/url-for     (fn [_s action] (when (second action)
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
  ;; Routing is data on the server half — `^{:web/path "/store/ns/:ns"}` on the
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
                ["/files/*path"  :files]]
        at     (fn [p] (webapp/match-route routes p))]

    (testing "a static segment beats a capture, whatever the order"
      ;; precedence is fewest-captures-wins rather than first-listed, matching
      ;; the server exactly — so adding a route can never steal an existing one
      (is (= :things (:screen (at "/things"))))
      (is (= :thing  (:screen (at "/things/42"))))
      (is (= {:id "42"} (:params (at "/things/42")))))

    (testing "a trailing catch-all takes the remainder, and ranks below both"
      (is (= :files (:screen (at "/files/a/b/c.txt"))))
      (is (= {:path "a/b/c.txt"} (:params (at "/files/a/b/c.txt")))))

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
  ;; report and gate that exists for `^{:web/path}` was impossible here for
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
      (is (= thing (:screen @state)) "the row's screen fn IS the screen")
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
        thing  (fn [s] [:p (str "Thing " (:id (:params s)))])
        app   (webapp/wiring
               {:webapp/state  state
                :webapp/routes [["/things"     things]
                                ["/things/:id" thing]]
                :webapp/chrome (fn [_s inner] [:main [:h1 "Catalogue"] inner])})
        s     (web.screen/open! (webapp/driver app))]

    (testing "the app declares no :webapp/view — slopp derives it"
      (is (fn? (:webapp/view app))
          "the driver still needs one; what changed is who writes it"))

    (testing "visiting a route renders that screen INSIDE the app's chrome"
      (web.screen/visit! s "/things")
      (let [t (web.screen/text s)]
        (is (re-find #"Catalogue" t) t)
        (is (re-find #"Anvil" t) t)))

    (testing "and the matched row's captures reach the screen it points at"
      (web.screen/visit! s "/things/42")
      (is (re-find #"Thing 42" (web.screen/text s)) (web.screen/text s)))

    (testing "an unrouted path renders NOT-FOUND, never a blank pane"
      ;; the failure this closes: with routes as data slopp KNOWS nothing
      ;; matched, so rendering nothing is a choice rather than an accident — and
      ;; a blank pane at a plausible url is indistinguishable from a screen
      ;; whose content is empty
      (web.screen/visit! s "/nope")
      (is (seq (str/trim (web.screen/text s)))
          "an unmatched path rendered nothing at all"))

    (testing "and the app can say what not-found LOOKS like"
      (let [s2 (web.screen/open!
                (webapp/driver
                 (webapp/wiring
                  {:webapp/state     (atom {})
                   :webapp/routes    [["/things" things]]
                   :webapp/not-found (fn [_s] [:p "Nowhere"])})))]
        (web.screen/visit! s2 "/nope")
        (is (re-find #"Nowhere" (web.screen/text s2)) (web.screen/text s2))))

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
             clojure.lang.ExceptionInfo #"(?i)screen"
             (webapp/wiring {:webapp/state  (atom {})
                             :webapp/routes [["/things" target]]}))
            (str (pr-str target) " is callable and renders nothing"))))))

(deftest slopp-PREFIXES-in-app-links-so-a-view-writes-CLIENT-paths
  ;; The join `crossings` reports as `:webapp/client-path`, and its stated
  ;; reason for being unchecked:
  ;;
  ;;   nothing joins the three parts up. The literal is a client route, the
  ;;   mount point arrives from the render, and a :web/client-routes fallback
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
                         [:form {:action "/api/save"} [:button "Save"]]])
        routes [["/things" things] ["/things/:id" things]]
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

    (testing "a form ACTION is never touched — it submits to a server"
      ;; scoped to :href deliberately. An :action is a server submission, not a
      ;; client route, and the client router has nothing to say about it
      (is (= "/api/save" (get-in (at "/p/x") [5 1 :action]))))

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
