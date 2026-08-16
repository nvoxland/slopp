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
