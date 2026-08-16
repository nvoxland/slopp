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
