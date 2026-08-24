(ns slopp.webapp-test
  "The browser app's loop, and the DERIVATION of a headless driver from it.

  Nothing here starts a browser or compiles ClojureScript, which is the whole
  claim: an agent reads what a dynamic page SAYS by driving the app's real
  wiring on a JVM. A test that needed a bundle would prove the opposite."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.webapp :as webapp]
            [slopp.cljnx :as cljnx] [clojure.string :as str]))

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
        things {:render  (fn [s] [:ul (for [t (webapp/load-value s :main)]
                                        [:li (:name t)])])
                :request (fn [_params] {:webapp/path "/api/things"})}
        thing  {:render  (fn [s] [:p (str "Thing " (:id (:params s)))])
                :request (fn [params] {:webapp/path        "/api/things/:id"
                                       :webapp/path-params {:id (:id params)}})}
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
                                     (let [url (webapp/request-url request)]
                                       (swap! asked conj url)
                                       (ok (when (= "/api/things" url)
                                             [{:name "Anvil"} {:name "Rope"}]))))})
        s      (cljnx/open! (webapp/driver app))]

    (testing "the derived driver is the shape the fake browser accepts"
      ;; if this drifts, every assertion below fails in a way that looks like
      ;; an app bug rather than a derivation bug
      (is (= #{:state :view :navigate :dispatch :boot}
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
        thing   {:render  (fn [_s] [:p "thing"])
                 :request (fn [_params] {:webapp/path "/api/thing"})}
        app     (webapp/wiring
                 {:webapp/state  state
                  :webapp/routes [["/thing" thing]]
                  ;; hold the callback so the LOADING moment is observable —
                  ;; a call that answers synchronously never has one
                  :webapp/call   (fn [_request ok _err] (reset! pending ok))})]

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

    (testing "a screen that names NO request never leaves :absent"
      ;; the fourth state earns its keep here: a screen with nothing to fetch
      ;; is not loading, has not failed, and has not answered. Anything else
      ;; would be a spinner with no end or a lie about an answer
      (let [st  (atom {})
            a3  (webapp/wiring {:webapp/state  st
                                :webapp/routes [["/static" (fn [_s] [:p "static"])]]})]
        (webapp/navigate! a3 "/static" false)
        (is (= :absent (webapp/load-status @st :main)) (pr-str @st))))

    (testing "and a failure is FAILED, distinct from both"
      (webapp/navigate! app "/thing" false)
      (let [err (atom nil)
            app2 (webapp/wiring
                  {:webapp/state  state
                   :webapp/routes [["/thing" thing] ["/other" thing]]
                   :webapp/call   (fn [_request _ok e] (reset! err e))})]
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
        screen-a {:render  (fn [_s] [:p "a"])
                  :request (fn [_p] {:webapp/path "/api/a"})}
        screen-b {:render  (fn [_s] [:p "b"])
                  :request (fn [_p] {:webapp/path "/api/b"})}
        app      (webapp/wiring
                  {:webapp/state  state
                   :webapp/routes [["/a" screen-a] ["/b" screen-b]]
                   :webapp/call   (fn [request ok _err]
                                    (swap! pending conj [request ok]))})]

    (webapp/navigate! app "/a" false)
    (webapp/navigate! app "/b" false)

    (testing "POSITIVE CONTROL: the second navigation really emptied and re-minted"
      ;; without this the collision cannot arise and every assertion below
      ;; passes vacuously — which is the exact defect this test exists about
      (is (= 2 (count @pending)) (pr-str (count @pending)))
      (is (= ["/api/a" "/api/b"] (mapv (comp :webapp/path first) @pending))
          "each screen asked for its OWN endpoint")
      (is (= (:render screen-b) (:render (:screen @state))) (pr-str (:path @state)))
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
                 ;; declared with no :request: scoped, and STARTED by the app.
                 ;; That is exactly what the old SET meant and all this test is
                 ;; about — starting one at page load is its own test
                 :webapp/session-loads {:modules {}}})
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
                   :webapp/session-loads {:modules {}}})]
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
        ;; a row's target is normalised to a screen VALUE, so the fn an app
        ;; wrote is that screen's `:render`
        showing   (fn [] (:render (:screen @state)))
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
      (is (= things (:render (:screen @state)))))

    (testing "and an app that declares no boot still starts"
      ;; the default has to be a function rather than nil, or every caller —
      ;; the driver, the entry, the next one — writes the same `or`
      (let [s2 (atom {})
            ok (fn [_s] [:p "ok"])
            a2 (webapp/wiring {:webapp/state  s2
                               :webapp/routes [["/anything" ok]]})]
        (is (fn? (:webapp/boot a2)))
        (webapp/start! a2 "/anything" "")
        (is (= ok (:render (:screen @s2))))))))

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
      (is (= thing (:render (:screen @state)))
          "a row written as a bare fn IS that screen's :render")
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
             clojure.lang.ExceptionInfo #"(?i)screen"
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

(deftest the-framework-defaults-the-CONTENT-of-a-load-state-and-chrome-keeps-the-PLACEMENT
  ;; Every screen was going to repeat the same three-way case:
  ;;
  ;;   (case (load-status s :main) :ready … :failed … [:p "loading…"])
  ;;
  ;; which is the keyword-agreeing-in-three-places problem again, one level in.
  ;;
  ;; **The asymmetry that decides who renders it is STRUCTURAL, not aesthetic.**
  ;; My first answer was "slopp owns what is true, the app owns what is seen" —
  ;; and slopp-ui pointed out that it does not survive `not-found`, which is
  ;; equally presentation and which slopp already defaults. The line did not
  ;; separate the cases it was invoked for.
  ;;
  ;; Theirs does: `not-found` replaces the WHOLE page, so the framework can
  ;; render it — there is nothing else on screen to be wrong about. `loading`
  ;; and `failed` replace one PANE while the rest of the app stays up: their app
  ;; renders the nav rail while main says loading, because a reader who
  ;; navigated with the module list should still see it. So a framework
  ;; rendering those has to know WHERE they go, and placement is layout, which
  ;; is the one thing this capability does not take.
  ;;
  ;; Hence: the framework supplies the CONTENT as `inner`, chrome decides WHERE.
  (let [state   (atom {})
        pending (atom nil)
        ;; a screen with a REQUEST, because a load state is only reachable for
        ;; a screen that asked for something — one that names no request is
        ;; never loading and never failed
        things  {:render  (fn [_s] [:p "THE SCREEN"])
                 :request (fn [_p] {:webapp/path "/api/things"})}
        app     (webapp/wiring
                 {:webapp/state  state
                  :webapp/routes [["/things" things]]
                  :webapp/chrome (fn [_s inner] [:main [:nav "RAIL"] inner])
                  :webapp/call   (fn [_request ok _err] (reset! pending ok))})
        text    (fn [] (pr-str ((:webapp/view app) @state)))]

    (webapp/navigate! app "/things" false)

    (testing "while loading, the framework renders the state and chrome places it"
      (is (re-find #"(?i)loading" (text)) (text))
      (is (re-find #"RAIL" (text))
          "the rest of the app stays up — that is the whole reason chrome places it")
      (is (not (re-find #"THE SCREEN" (text)))
          "a screen must not render against data that has not arrived"))

    (testing "inner is NEVER nil, which is the nil-pun this would otherwise be"
      ;; slopp-ui's constraint, and it is the rule `load-value` already states:
      ;; a nil chrome has to test is the same pun in the one value every app
      ;; handles. Chrome asks `load-status` if it wants the distinction
      (let [seen (atom ::none)
            a2   (webapp/wiring
                  {:webapp/state  (atom {})
                   :webapp/routes [["/things" things]]
                   :webapp/chrome (fn [_s inner] (reset! seen inner) [:main inner])
                   :webapp/call   (fn [_rq _ok _e] nil)})]
        ((:webapp/view a2) {})
        (is (some? @seen) "chrome received nil and would have to test it")))

    (testing "when it arrives the screen renders"
      (@pending [:anvil])
      (is (re-find #"THE SCREEN" (text)) (text)))

    (testing "a FAILED load renders the failure, not the screen"
      (webapp/navigate! app "/things" false)
      (is (re-find #"(?i)loading" (text)) (text)))

    (testing "and an app that wants different COPY declares it"
      ;; the middle tier: same placement, its own words. Same shape as
      ;; :webapp/not-found, which is what makes this one story rather than two
      (let [s2 (atom {})
            a2 (webapp/wiring
                {:webapp/state   s2
                 :webapp/routes  [["/things" things]]
                 :webapp/loading (fn [_s] [:p "Fetching your things"])
                 :webapp/call    (fn [_rq _ok _e] nil)})]
        (webapp/navigate! a2 "/things" false)
        (is (re-find #"Fetching your things" (pr-str ((:webapp/view a2) @s2)))
            (pr-str ((:webapp/view a2) @s2)))))))

(deftest a-request-becomes-a-FINISHED-url-where-a-test-can-read-it
  ;; The performer seam's pure half. slopp-ui's `url-parts` splits a request into
  ;; `[:lit …]`/`[:enc …]` pieces so their browser performer "has nothing left to
  ;; decide" — and slopp can go one step further, because
  ;; `slopp.lang/encode-component` is already `:cljc`: the performer receives a
  ;; COMPLETE url and does nothing but fetch it.
  ;;
  ;; Their docstring says why it cannot live in the browser, and it is the
  ;; sentence this test exists for:
  ;;
  ;;   The performer lives in the one namespace the JVM oracle cannot reach, so
  ;;   anything it decides is something no test can see. The concrete bug: a
  ;;   performer doing `str/replace` on `:m` would corrupt `/api/:module/:m`, and
  ;;   that would ship — the string is assembled in a browser and asserted
  ;;   nowhere.
  (let [url webapp/request-url]

    (testing "a path with no params is itself"
      (is (= "/api/modules" (url {:webapp/path "/api/modules"}))))

    (testing "SEGMENT-WISE substitution, which is the bug they already paid for"
      ;; `(str/replace "/api/:module/:m" ":m" "y")` gives "/api/yodule/y" —
      ;; a parameter whose name is a PREFIX of another corrupts the path, and
      ;; the result looks like a url
      (is (= "/api/x/y" (url {:webapp/path "/api/:module/:m"
                              :webapp/path-params {:module "x" :m "y"}}))))

    (testing "a value cannot BREAK OUT of its segment, which is the security half"
      ;; percent-of escapes every non-unreserved ASCII character, so a slash in
      ;; a value is data rather than structure. This is why the derivation is
      ;; here and not in a namespace whose only verification is that it compiled
      (is (= "/api/module/a%2Fb" (url {:webapp/path "/api/module/:m"
                                       :webapp/path-params {:m "a/b"}})))
      (is (= "/api/module/a%3Fq%3D1" (url {:webapp/path "/api/module/:m"
                                           :webapp/path-params {:m "a?q=1"}})))
      (is (= "/api/module/a%26b" (url {:webapp/path "/api/module/:m"
                                       :webapp/path-params {:m "a&b"}}))))

    (testing "the query is appended and encoded, and absent when there is none"
      (is (= "/api/search?q=a%20b" (url {:webapp/path "/api/search"
                                         :webapp/query {:q "a b"}})))
      (is (= "/api/search" (url {:webapp/path "/api/search" :webapp/query {}}))
          "an empty query must not leave a trailing ? — a url that differs from
           the one a reader typed is a cache key nobody predicted"))

    (testing "and a param the path does not name is a QUERY key, not silence"
      ;; dropping it would send a request missing an argument the caller
      ;; supplied, which reaches them as a wrong answer rather than an error
      (is (= "/api/module/x?depth=2"
             (url {:webapp/path "/api/module/:m"
                   :webapp/path-params {:m "x"}
                   :webapp/query {:depth "2"}}))))))

(deftest a-screen-is-a-VALUE-and-it-names-its-own-REQUEST
  ;; The last thing forcing a real browser app into ClojureScript: `js/fetch`.
  ;; `:webapp/fetch` was app-supplied and took the SCREEN, so every app wrote a
  ;; performer, and every performer had to case on which screen was asking —
  ;; the same three-place agreement the route table removed, moved one seam
  ;; along.
  ;;
  ;; A screen names its own request instead. `:request` is `(fn [params] ->
  ;; request | nil)`, pure, in `:cljc`, so WHICH call a screen makes is a fact
  ;; a JVM test reads. What is left for a browser is `fetch`.
  (let [state (atom {})
        calls (atom [])
        thing {:render  (fn [s] [:p (str "Thing " (:name (webapp/load-value s :main)))])
               :request (fn [params] {:webapp/method      :get
                                      :webapp/path        "/api/things/:id"
                                      :webapp/path-params {:id (:id params)}})}
        plain (fn [_s] [:p "Plain, and it asks for nothing"])
        app   (webapp/wiring
               {:webapp/state  state
                :webapp/routes [["/things"     plain]
                                ["/things/:id" thing]]
                :webapp/call   (fn [request ok _err]
                                 (swap! calls conj request)
                                 (ok {:name "Anvil"}))})
        s     (cljnx/open! (webapp/driver app))]

    (testing "the screen's own :request decides the call, and it is a finished URL"
      (cljnx/visit! s "/things/42")
      (is (= 1 (count @calls)) "the screen asked for its data exactly once")
      (is (= "/api/things/42" (webapp/request-url (first @calls)))
          "the params the route captured are the ones the request substitutes")
      (is (re-find #"Thing Anvil" (cljnx/text s)) (cljnx/text s)))

    (testing "a screen that declares NO request never waits for one"
      ;; not the same as a request that answers nil: there is nothing in
      ;; flight, so a load state would be a lie and a spinner would never end
      (reset! calls [])
      (cljnx/visit! s "/things")
      (is (= [] @calls) "a screen with no request made one anyway")
      (is (re-find #"asks for nothing" (cljnx/text s)) (cljnx/text s)))

    (testing "a bare fn is still a screen — the shorthand for exactly that case"
      (is (fn? plain)))

    (testing "the screen's :derive shapes its own answer"
      ;; what `:webapp/derive` was for, and it could only ever be written as a
      ;; case on screen identity — an app-wide function asked \"which screen is
      ;; this?\" to answer a question the screen already knows
      (let [st  (atom {})
            app (webapp/wiring
                 {:webapp/state  st
                  :webapp/routes [["/thing" {:render (fn [s] [:p (webapp/load-value s :main)])
                                             :request (fn [_] {:webapp/path "/api/thing"})
                                             :derive  (fn [v] (str "derived:" (:name v)))}]]
                  :webapp/call   (fn [_rq ok _err] (ok {:name "Anvil"}))})
            s2  (cljnx/open! (webapp/driver app))]
        (cljnx/visit! s2 "/thing")
        (is (re-find #"derived:Anvil" (cljnx/text s2)) (cljnx/text s2))))

    (testing "a screen map with no :render is REFUSED"
      ;; the shape that made maps refusable in the first place: a map is
      ;; `ifn?`, so a row pointing at one used to match and render nil
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i):render"
           (webapp/wiring {:webapp/state  (atom {})
                           :webapp/routes [["/things" {:request (fn [_] nil)}]]}))))

    (testing "a TYPO inside a screen map is refused, not silently ignored"
      ;; `:reqeust` would never be read, so the screen would render with no
      ;; data forever and nothing would say why — the failure this whole
      ;; capability keeps closing
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)reqeust"
           (webapp/wiring {:webapp/state  (atom {})
                           :webapp/routes [["/things" {:render  (fn [_] [:p])
                                                       :reqeust (fn [_] nil)}]]}))))

    (testing ":webapp/fetch and :webapp/derive are RETIRED, and the refusal migrates"
      ;; no back-compat: both existed to be handed a screen and case on it,
      ;; which is what the screen value removes
      (doseq [k [:webapp/fetch :webapp/derive]]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"(?i)screen"
             (webapp/wiring {:webapp/state  (atom {})
                             :webapp/routes []
                             k              (fn [& _] nil)}))
            (str k " is still accepted"))))))

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
  (testing "a GET names NO encoder, so the shim sends no body at all"
    ;; `fetch` throws on a GET carrying a body, so this is not tidiness — a
    ;; request that quietly acquires an empty body stops working entirely
    (let [init (webapp/request-init {:webapp/path "/api/things"})]
      (is (= "GET" (:method init)) (pr-str init))
      (is (= :none (:encode init)) (pr-str init))
      (is (nil? (:body init)) (pr-str init))))

  (testing "a request WITH a body names the json encoder and says so in a header"
    (let [init (webapp/request-init {:webapp/method :put
                                     :webapp/path   "/api/things/1"
                                     :webapp/body   {:name "Anvil"}})]
      (is (= "PUT" (:method init)) (pr-str init))
      (is (= :json (:encode init)) (pr-str init))
      (is (= {:name "Anvil"} (:body init)) (pr-str init))
      (is (= "application/json" (get (:headers init) "Content-Type")) (pr-str init))))

  (testing "an app's own headers are carried, and win over the default"
    ;; a token is STATE, not schema — it cannot be derived from an endpoint
    ;; declaration, so the shape has to carry headers or an authenticated app
    ;; falls straight back to writing its own fetch
    (let [init (webapp/request-init {:webapp/path    "/api/me"
                                     :webapp/headers {"Authorization" "Bearer t"}})]
      (is (= "Bearer t" (get (:headers init) "Authorization")) (pr-str init)))
    (let [init (webapp/request-init {:webapp/method  :post
                                     :webapp/path    "/api/upload"
                                     :webapp/body    "raw"
                                     :webapp/headers {"Content-Type" "text/plain"}})]
      (is (= "text/plain" (get (:headers init) "Content-Type"))
          (str "a declared content type must win, or an app can never send"
               " anything but json: " (pr-str init)))))

  (testing "a body of FALSE or nil are different requests"
    ;; the nil-pun this framework keeps removing, in the one place it would
    ;; silently drop a value: `false` is a body somebody meant to send
    (is (= :json (:encode (webapp/request-init {:webapp/path "/x" :webapp/body false}))))
    (is (= :none (:encode (webapp/request-init {:webapp/path "/x" :webapp/body nil})))))

  (testing "the ENCODER follows the declared content type"
    ;; json is the default and was briefly the only thing sendable, which is
    ;; the asymmetry the consuming app named: slopp's own API publishes
    ;; `application/edn`, so a framework that can only send json cannot POST to
    ;; the endpoints slopp itself serves
    (is (= :edn (:encode (webapp/request-init
                          {:webapp/path    "/x"
                           :webapp/body    {:a 1}
                           :webapp/headers {"Content-Type" "application/edn"}}))))
    (is (= :json (:encode (webapp/request-init
                           {:webapp/path    "/x"
                            :webapp/body    {:a 1}
                            :webapp/headers {"Content-Type" "application/json; charset=utf-8"}})))
        "the parameters come off before the lookup, the same as on the way back")
    (is (= :text (:encode (webapp/request-init
                           {:webapp/path    "/x"
                            :webapp/body    "raw"
                            :webapp/headers {"Content-Type" "text/plain"}})))
        (str "a type slopp does not encode for sends the body AS GIVEN — which"
             " is honest, where guessing json would corrupt it"))
    (is (= :none (:encode (webapp/request-init
                           {:webapp/path    "/x"
                            :webapp/headers {"Content-Type" "application/edn"}})))
        "a declared type on a request with NO body still sends no body"))

  (testing "a content-type header is reduced to the MEDIA TYPE a decoder is keyed by"
    ;; the browser answers `application/json; charset=utf-8`, and an exact
    ;; lookup on that misses — so the normalisation is here rather than being a
    ;; string-split in the namespace nothing can test
    (is (= "application/json" (webapp/media-type "application/json; charset=utf-8")))
    (is (= "application/json" (webapp/media-type "APPLICATION/JSON")))
    (is (= "text/csv" (webapp/media-type "  text/csv  ")))
    (is (nil? (webapp/media-type nil))
        "an answer with no content-type has no media type — not an empty one")
    (is (nil? (webapp/media-type "")))))

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

(deftest a-screen-can-REJECT-what-it-was-SENT-and-both-drivers-agree
  ;; Reported by the app that lost its response validation to 4d, and measured
  ;; rather than argued: their generated `fetch` wrappers THREW on a contract
  ;; violation, the four-state model turned that into the failed screen, and
  ;; when the framework took over performing, the wrappers stopped being called.
  ;;
  ;; The obvious repair — validate inside `:derive` — is not available, and the
  ;; reason is worth stating because it is structural rather than an oversight:
  ;;
  ;;   headless  `ok` is called SYNCHRONOUSLY by the fake, so a throw inside
  ;;             `write!` propagates out of `load!` and takes the driver with it
  ;;   browser   `ok` is called from inside a `.then`, so the same throw lands
  ;;             in `call!`'s `.catch` and becomes a rendered failure screen
  ;;
  ;; Same `:derive`, two behaviours — which is the ONE difference this
  ;; capability exists to prevent, arriving through the seam it added. And it
  ;; cannot be closed by catching, because a `:cljc` form cannot catch on both
  ;; platforms without the reader conditional D3 denies.
  ;;
  ;; So a rejection is a VALUE. `:check` answers nil for an acceptable response
  ;; and a MESSAGE for one it refuses, and nothing throws anywhere.
  (let [state (atom {})
        screen {:render  (fn [s] [:p (str "got " (webapp/load-value s :main))])
                :request (fn [_p] {:webapp/path "/api/thing"})
                :check   (fn [v] (when-not (:ok v) "contract violation: :ok is missing"))
                :derive  :name}
        answer (atom {:ok true :name "Anvil"})
        app    (webapp/wiring
                {:webapp/state  state
                 :webapp/routes [["/thing" screen]]
                 :webapp/call   (fn [_rq ok _err] (ok @answer))})
        s      (cljnx/open! (webapp/driver app))]

    (testing "an answer the screen accepts is derived and rendered"
      (cljnx/visit! s "/thing")
      (is (= :ready (webapp/load-status @state :main)) (pr-str @state))
      (is (re-find #"got Anvil" (cljnx/text s)) (cljnx/text s)))

    (testing "an answer it REJECTS becomes :failed, carrying the check's message"
      (reset! answer {:name "Anvil"})
      (cljnx/visit! s "/thing")
      (is (= :failed (webapp/load-status @state :main)) (pr-str @state))
      (is (= "contract violation: :ok is missing"
             (get-in @state [:loads :main :error]))
          (pr-str @state))
      (is (re-find #"contract violation" (cljnx/text s)) (cljnx/text s)))

    (testing "and :derive never runs on an answer that was rejected"
      ;; deriving from a value the screen just refused is work on data nobody
      ;; trusts, and its own failure would arrive as the second error for one
      ;; fault — the louder and less true of the two
      (let [derived (atom 0)
            st      (atom {})
            a2      (webapp/wiring
                     {:webapp/state  st
                      :webapp/routes [["/thing" {:render  (fn [_s] [:p "x"])
                                                 :request (fn [_p] {:webapp/path "/api/thing"})
                                                 :check   (fn [_v] "no")
                                                 :derive  (fn [v] (swap! derived inc) v)}]]
                      :webapp/call   (fn [_rq ok _err] (ok {:whatever true}))})]
        (webapp/navigate! a2 "/thing" false)
        (is (= 0 @derived) "the derive ran on a value the check had refused")))

    (testing "a SUPERSEDED answer is never checked either"
      ;; the check is inside the freshness guard for `:derive`'s own reason: an
      ;; answer nobody is waiting on must not be paid for. The app this came
      ;; from had exactly the opposite — generated wrappers that validated
      ;; BEFORE the guard, so an abandoned load paid for its own validation
      (let [checked (atom 0)
            st      (atom {})
            pending (atom [])
            a3      (webapp/wiring
                     {:webapp/state  st
                      :webapp/routes [["/a" {:render  (fn [_s] [:p "a"])
                                             :request (fn [_p] {:webapp/path "/api/a"})
                                             :check   (fn [_v] (swap! checked inc) nil)}]
                                      ["/b" (fn [_s] [:p "b"])]]
                      :webapp/call   (fn [_rq ok _err] (swap! pending conj ok))})]
        (webapp/navigate! a3 "/a" false)
        (webapp/navigate! a3 "/b" false)
        ((first @pending) {:anything true})
        (is (= 0 @checked)
            "a superseded answer was validated — the abandoned load paid for it")))))

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
  (let [called (atom [])
        state  (atom {})
        screen {:render  (fn [_s] [:p "x"])
                :request (fn [_p] {:webapp/path "/api/things"})}
        app    (webapp/wiring
                {:webapp/state       state
                 :webapp/base        "/p/demo"
                 :webapp/routes      [["/things" screen]]
                 :webapp/actions     {:thing/save {:effectful? true}}
                 :webapp/request-for (fn [_s _a] {:webapp/method :put
                                                  :webapp/path   "/api/things/1"})
                 :webapp/call        (fn [rq ok _err]
                                       (swap! called conj (webapp/request-url rq))
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
                 :webapp/routes [["/rates" {:render  (fn [_s] [:p "r"])
                                            :request (fn [_p] {:webapp/path "https://api.example.com/v1/rates"})}]
                                 ["/proto" {:render  (fn [_s] [:p "p"])
                                            :request (fn [_p] {:webapp/path "//cdn.example.com/x.json"})}]]
                 :webapp/call   (fn [rq ok _err]
                                  (swap! called conj (webapp/request-url rq))
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
      ;; Declared on the REQUEST rather than on the app, which is where the fact
      ;; lives: this app's other requests ARE mounted and only this one is not
      (reset! called [])
      (let [st (atom {})
            a4 (webapp/wiring
                {:webapp/state  st
                 :webapp/base   "/p/demo"
                 :webapp/routes [["/mine"   {:render  (fn [_s] [:p "m"])
                                             :request (fn [_p] {:webapp/path "/api/modules"})}]
                                 ["/theirs" {:render  (fn [_s] [:p "t"])
                                             :request (fn [_p] {:webapp/path        "/api/projects"
                                                                :webapp/from-origin true})}]]
                 :webapp/call   (fn [rq ok _err]
                                  (swap! called conj (webapp/request-url rq))
                                  (ok nil))})]
        (webapp/navigate! a4 "/mine" false)
        (webapp/navigate! a4 "/theirs" false)
        (is (= ["/p/demo/api/modules" "/api/projects"] @called)
            (str "a from-origin request took the mount point anyway, so it"
                 " reached a project that does not serve it: " (pr-str @called)))))

    (testing "at the ROOT nothing changes, so an unmounted app reads identically"
      (reset! called [])
      (let [st (atom {})
            a3 (webapp/wiring
                {:webapp/state  st
                 :webapp/routes [["/things" screen]]
                 :webapp/call   (fn [rq ok _err]
                                  (swap! called conj (webapp/request-url rq))
                                  (ok nil))})]
        (webapp/navigate! a3 "/things" false)
        (is (= ["/api/things"] @called) (pr-str @called))))))

(deftest a-SESSION-load-is-DECLARED-and-STARTS-at-page-load
  ;; The gap between what `:webapp/boot` says and what its shape can do.
  ;;
  ;;   "`:webapp/boot` is where an app begins the loads that belong to the
  ;;    session rather than to a route"
  ;;
  ;; It is `(fn [state] state)`. No `app`, so no `:webapp/call` and no
  ;; `:webapp/render`, so it cannot reach [[load!]] and cannot begin anything.
  ;; An app that wants a nav pane fetched once has to write ClojureScript after
  ;; `mount!` — which is the remaining `:cljs` in the only real consumer, and
  ;; the goal of this whole capability stated as a number that is not zero.
  ;;
  ;; A docstring promising what the signature cannot deliver is worse than a
  ;; missing feature: it sends the reader to write the wrong thing and then to
  ;; wonder why the framework's own `session-loads` did not cover it.
  ;;
  ;; So a session load is DATA, like a route row and like a screen's request —
  ;; and `:webapp/session-loads` is one declaration rather than two, because
  ;; declaring what a load IS and declaring that it outlives a screen were
  ;; always the same statement about the same load.
  (let [state  (atom {})
        called (atom [])
        app    (webapp/wiring
                {:webapp/state         state
                 :webapp/base          "/p/demo"
                 :webapp/routes        [["/code" (fn [s] [:p (str "code "
                                                                  (webapp/load-value s :modules))])]]
                 :webapp/boot          (fn [s] (assoc s :token "abc"))
                 :webapp/session-loads {:modules {:request (fn [s] {:webapp/path    "/api/modules"
                                                                   :webapp/headers {"Authorization" (:token s)}})
                                                  :derive  :names}
                                        ;; declared session-scoped, started by
                                        ;; the app itself — no :request
                                        :user    {}}
                 :webapp/call          (fn [rq ok _err]
                                         (swap! called conj rq)
                                         (ok {:names ["a" "b"]}))})]

    (testing "page load starts every session load that names a request"
      (webapp/start! app "/p/demo/code" "")
      (is (= ["/p/demo/api/modules"] (mapv webapp/request-url @called))
          (str "a declared session load did not start, or started at the wrong"
               " address: " (pr-str @called)))
      (is (= :ready (webapp/load-status @state :modules)) (pr-str @state))
      (is (= ["a" "b"] (webapp/load-value @state :modules))
          "the :derive did not run on a session load"))

    (testing "and BOOT ran first, so its state is what the request reads"
      ;; the order start! already documents, now with something that depends on
      ;; it: a token established by boot is what an authenticated session load
      ;; must carry, and routing first would send the request without one
      (is (= "abc" (get-in (first @called) [:webapp/headers "Authorization"]))
          (pr-str (first @called))))

    (testing "a load declared with NO request is scoped but not started"
      ;; the two questions are still two: this one outlives a screen AND is the
      ;; app's to begin — after a sign-in, say, which is not page load
      (is (= :absent (webapp/load-status @state :user)) (pr-str @state))
      (is (= 1 (count @called)) (pr-str @called)))

    (testing "and both still SURVIVE navigation, which is what scoped means"
      (webapp/navigate! app "/code" false)
      (is (= :ready (webapp/load-status @state :modules)) (pr-str @state))
      (is (= ["a" "b"] (webapp/load-value @state :modules)) (pr-str @state)))

    (testing "a nil request DECLINES, the same channel everywhere else"
      ;; the shape an app needs before sign-in: the load is declared, and it
      ;; starts when there is something to ask with
      (let [st (atom {})
            hit (atom 0)
            a2 (webapp/wiring
                {:webapp/state         st
                 :webapp/routes        [["/x" (fn [_s] [:p "x"])]]
                 :webapp/session-loads {:me {:request (fn [s] (when (:token s)
                                                                {:webapp/path "/api/me"}))}}
                 :webapp/call          (fn [_rq ok _err] (swap! hit inc) (ok nil))})]
        (webapp/start! a2 "/x" "")
        (is (= 0 @hit) "an unarmed session load fetched anyway")
        (is (= :absent (webapp/load-status @st :me)) (pr-str @st))))

    (testing "a SET is refused, and the message carries the migration"
      ;; no back-compat: the set said only which loads survive, and the map says
      ;; that AND what they are — one declaration where there were two
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)session-loads"
           (webapp/wiring {:webapp/state         (atom {})
                           :webapp/routes        []
                           :webapp/session-loads #{:modules}}))))))

(deftest a-SESSION-load-runs-in-the-HEADLESS-drive-as-well-as-the-page
  ;; The divergence this capability exists to prevent, shipped by the change
  ;; that closed the last one. `start!` — the BROWSER entry — performs every
  ;; declared session load. `driver` hands `screen/open!` the app's raw
  ;; `:webapp/boot`, and `open!` refuses any key it does not know, so nothing
  ;; headless ever started one.
  ;;
  ;; A declaration that works in a page and not in a test is worse than no
  ;; declaration: the app that took it found sixteen driven screens rendering an
  ;; empty nav, reverted, and kept the `:cljs` fetch — with the three defects
  ;; `load!`'s docstring cites that app for still attached to it.
  ;;
  ;; Third finding in this shape in a fortnight — a throwing `:derive`, an
  ;; unprefixed `:action`, and this — so the rule is worth stating where it can
  ;; be checked rather than remembered: **anything a page load does before
  ;; routing, a headless drive does too, out of one producer.**
  (let [state  (atom {})
        called (atom [])
        app    (webapp/wiring
                {:webapp/state         state
                 :webapp/base          "/p/demo"
                 :webapp/routes        [["/code" (fn [s] [:main "rail: "
                                                          (str (webapp/load-value s :modules))])]]
                 :webapp/boot          (fn [s] (assoc s :token "abc"))
                 :webapp/session-loads {:modules {:request (fn [_s] {:webapp/path "/api/modules"})
                                                  :derive  :names}}
                 :webapp/call          (fn [rq ok _err]
                                         (swap! called conj (webapp/request-url rq))
                                         (ok {:names "web ops"}))})
        s      (cljnx/open! (webapp/driver app))]

    (testing "opening the driver starts the session load, as a page load does"
      (is (= ["/p/demo/api/modules"] @called)
          (str "a declared session load ran in the browser and in no headless"
               " drive, which is the one difference this capability exists to"
               " prevent: " (pr-str @called)))
      (is (= :ready (webapp/load-status @state :modules)) (pr-str @state)))

    (testing "and boot still ran, so a driven app is not half-started"
      (is (= "abc" (:token @state)) (pr-str @state)))

    (testing "so a screen READING it draws the same thing a browser draws"
      ;; the assertion the consuming app could not make: sixteen of their
      ;; screens render a nav rail out of a session load, and every one of them
      ;; was empty under the driver
      (cljnx/visit! s "/p/demo/code")
      (is (re-find #"web ops" (cljnx/text s)) (cljnx/text s)))

    (testing "and it is started ONCE, not again on every navigation"
      ;; the load survives `arrive` by being declared, so re-fetching it per
      ;; navigation would be the silent retry loop this model removed
      (cljnx/visit! s "/p/demo/code")
      (is (= 1 (count @called)) (pr-str @called)))))

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
  (testing "absent, the app's own mount point applies, exactly as before"
    (is (= "/p/demo/api/things"
           (:webapp/path (webapp/addressed "/p/demo" {:webapp/path "/api/things"})))))
  (testing "present, it WINS — the request is measured from the api it belongs to"
    (is (= "/p/other/api/things"
           (:webapp/path (webapp/addressed "/p/demo"
                                           {:webapp/path "/api/things"
                                            :webapp/base "/p/other"})))
        "a client-routed app switches which upstream it is reading WITHOUT a
         page load, so no value stamped once at load can be right — the app's
         base is a default, not the answer"))
  (testing "empty means the ORIGIN, which is what :webapp/from-origin said"
    (is (= "/api/projects"
           (:webapp/path (webapp/addressed "/p/demo"
                                           {:webapp/path "/api/projects"
                                            :webapp/base ""})))))
  (testing "and :webapp/from-origin still says it, in terms of the same lever"
    (is (= "/api/projects"
           (:webapp/path (webapp/addressed "/p/demo"
                                           {:webapp/path "/api/projects"
                                            :webapp/from-origin true})))))
  (testing "an absolute url is still left alone, whatever base is named"
    (is (= "https://other.example/api/x"
           (:webapp/path (webapp/addressed "/p/demo"
                                           {:webapp/path "https://other.example/api/x"
                                            :webapp/base "/p/other"}))))))
