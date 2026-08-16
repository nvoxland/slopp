(ns slopp.webapp-test
  "The browser app's loop, and the DERIVATION of a headless driver from it.

  Nothing here starts a browser or compiles ClojureScript, which is the whole
  claim: an agent reads what a dynamic page SAYS by driving the app's real
  wiring on a JVM. A test that needed a bundle would prove the opposite."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.webapp :as webapp]
            [slopp.web.screen :as web.screen]))

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
  (let [state (atom {})
        asked (atom [])
        app   (webapp/wiring
               {:webapp/state  state
                :webapp/routes (fn [path] (case path
                                            "/things"    {:screen :things :params {}}
                                            "/things/42" {:screen :thing :params {:id 42}}
                                            nil))
                :webapp/view   (fn [s] [:main
                                        [:h1 "Catalogue"]
                                        (case (:screen s)
                                          :things (if (:data s)
                                                    [:ul (for [t (:data s)] [:li (:name t)])]
                                                    [:p "Loading…"])
                                          :thing  [:p (str "Thing " (:id (:params s)))]
                                          [:p "Nowhere"])])
                ;; the app's own data source. In a browser this is the generated
                ;; typed client; here it answers from memory, which is exactly
                ;; the seam that makes the loop drivable at all
                :webapp/fetch  (fn [screen params ok _err]
                                 (swap! asked conj [screen params])
                                 (ok (when (= :things screen)
                                       [{:name "Anvil"} {:name "Rope"}])))})
        s     (web.screen/open! (webapp/driver app))]

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
      (is (= [[:things {}] [:thing {:id 42}]] @asked) (pr-str @asked)))

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
        app     (webapp/wiring
                 {:webapp/state  state
                  :webapp/routes (fn [p] (when (= "/thing" p) {:screen :thing :params {}}))
                  :webapp/view   (fn [_] [:p "x"])
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
      (is (nil? (:data @state)) (pr-str @state)))

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
                   :webapp/routes (fn [_] {:screen :thing :params {}})
                   :webapp/view   (fn [_] [:p "x"])
                   :webapp/fetch  (fn [_s _p _ok e] (reset! err e))})]
        (webapp/navigate! app2 "/thing" false)
        (@err "no")
        (is (= :failed (webapp/load-status @state :main)) (pr-str @state))
        (is (= "no" (:error @state)) (pr-str @state))))))

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
  ;; navigations mint token 1, and `:data-from-a` lands on screen `:b` with
  ;; the load marked `:ready`. The other two tests in this namespace stay
  ;; green through that break, so this is the only cover for the placement.
  (let [state   (atom {})
        pending (atom [])
        app     (webapp/wiring
                 {:webapp/state  state
                  :webapp/routes (fn [p] {:screen (keyword (subs p 1)) :params {}})
                  :webapp/view   (fn [_] [:p "x"])
                  :webapp/fetch  (fn [screen _params ok _err]
                                   (swap! pending conj [screen ok]))})]

    (webapp/navigate! app "/a" false)
    (webapp/navigate! app "/b" false)

    (testing "POSITIVE CONTROL: the second navigation really emptied and re-minted"
      ;; without this the collision cannot arise and every assertion below
      ;; passes vacuously — which is the exact defect this test exists about
      (is (= 2 (count @pending)) (pr-str (mapv first @pending)))
      (is (= :b (:screen @state)) (pr-str @state))
      (is (= :loading (webapp/load-status @state :main)) (pr-str @state)))

    (testing "the FIRST screen's answer arrives late and is dropped"
      (let [[_ ok-a] (first @pending)]
        (ok-a :data-from-a))
      (is (nil? (:data @state))
          (str "a superseded answer landing is the stale-screen failure: " (pr-str @state)))
      (is (= :loading (webapp/load-status @state :main))
          (str "and it must not mark the NEW screen ready either: " (pr-str @state))))

    (testing "while the SECOND screen's answer still lands"
      ;; the arm that makes the drop a discrimination rather than a blanket
      ;; refusal — a guard that dropped everything would satisfy the above
      (let [[_ ok-b] (second @pending)]
        (ok-b :data-from-b))
      (is (= :data-from-b (:data @state)) (pr-str @state))
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
                :webapp/routes  (fn [_] {:screen :home :params {}})
                :webapp/view    (fn [_] [:p "x"])
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
                   :webapp/routes  (fn [_] nil)
                   :webapp/view    (fn [_] [:p "x"])
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
                :webapp/routes      (fn [_] {:screen :home :params {}})
                :webapp/view        (fn [_] [:p "x"])
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
                               :webapp/routes (fn [p] {:screen (keyword (subs p 1)) :params {}})
                               :webapp/view   (fn [_] [:p "x"])}
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
                                  :webapp/routes       (constantly nil)
                                  :webapp/view         (fn [_] [:p "x"])
                                  :webapp/address-keys #{}})]
        (swap! state assoc :data [:old] :error "old" :loads {:main {:status :ready}})
        (webapp/navigate! app "/search" false)
        (is (nil? (:data @state)) (pr-str @state))
        (is (nil? (:error @state)) (pr-str @state))
        (is (= :absent (webapp/load-status @state :main))
            (str "nothing has been requested for this screen, which is the true"
                 " statement and the one a bumped token cannot make: "
                 (pr-str @state)))))))
