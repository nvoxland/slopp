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
