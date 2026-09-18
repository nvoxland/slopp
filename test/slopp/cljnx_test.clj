(ns slopp.cljnx-test
  "Cover for the readout — and the tests are the decisions, not the plumbing.

  Every case here is a real view bug that got past a careful reviewer's own
  assertions, which is why each one asserts the DIFFERENCE from the obvious
  substitute rather than the output shape. A flatten would satisfy \"contains
  the words\" for most of these; what it loses is the boundary between them.

  The one addition to the lifted implementation — a region that is not on the
  screen refusing rather than scoping to nothing — has its own test, because
  its failure mode is a green suite over a blank page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.cljnx :as cljnx] [slopp.cljnx.hiccup :as hiccup] [slopp.webapp :as webapp] [slopp.http :as slopp.http] [slopp.http.html :as html]))

(deftest a-block-never-glues-to-the-text-around-it
  ;; THE founding bug, and the reason a naive flatten is not merely uglier but
  ;; blind. `(->> (tree-seq coll? seq v) (filter string?) (str/join " "))` —
  ;; the helper slopp-ui had copied into six deftests — renders
  ;; [:h1 "code"] [:p "3 modules"] as "code 3 modules". A WRONG SENTENCE IN THE
  ;; MIDDLE OF A RUN-ON LINE IS INVISIBLE, and `5 of thems foundation` sat on a
  ;; served page for as long as that sentence existed.
  (testing "a heading owns its line, and carries its level as a real tag"
    (is (= "<h1>code</h1>\n3 modules, 4 namespaces"
           (cljnx/of [:div [:h1 "code"] [:p "3 modules, 4 namespaces"]]))))
  (testing "inline tags DO join the line — the split is by tag, not by nesting"
    (is (= "the store has 12 forms"
           (cljnx/of [:p "the store has " [:strong "12"] " forms"]))))
  (testing "an unknown tag starts a line rather than joining one"
    ;; the safe direction: over-separated is readable, silently glued is not
    (is (= "one\ntwo" (cljnx/of [:div [:whatever "one"] [:aside "two"]])))))

(deftest what-a-reader-cannot-afford-to-lose
  (testing "a seq child is a FRAGMENT, and dropping it reads as an empty section"
    ;; three spellings, all ordinary hiccup, all meaning the same thing
    (is (= "<ul slopp:count=\"2\">\n  <li>a</li>\n  <li>b</li>\n</ul>"
           (cljnx/of (into [:ul] (for [x ["a" "b"]] [:li x])))))
    (is (= "a\nb" (cljnx/of [:div (for [x ["a" "b"]] [:p x])])))
    (is (= "a\nb" (cljnx/of [:div (list (list [:p "a"] [:p "b"]))]))))

  (testing "an :href always travels — where a thing points is half of every check"
    (is (= "<a href=\"/store\">Code</a>" (cljnx/of [:p [:a {:href "/store"} "Code"]]))))

  (testing "a :class NEVER travels — style is a claim a text readout cannot honour"
    ;; the overlay story lives in the svg census below, where class is
    ;; capability vocabulary rather than styling; everywhere else dropping it
    ;; is what makes sugar verifiable (:h1.big ≡ [:h1 {:class \"big\"}])
    (is (= "x" (cljnx/of [:p [:span {:class "tint-3"} "x"]]))))

  (testing "a list is COUNTED, and the tool's cap is a machine-visible tag"
    (let [big (into [:ul] (for [i (range 32)] [:li (str "row " i)]))]
      (is (= "<ul slopp:count=\"32\">" (first (cljnx/lines big {:list-head 3})))
          "32 rows is a wall a reader skims; the count is one line they cannot")
      (is (= ["  <slopp:elided count=\"29\"/>" "</ul>"]
             (vec (take-last 2 (cljnx/lines big {:list-head 3}))))
          "the truncation is a TAG, so an assertion can never be eaten silently")
      (is (= 34 (count (cljnx/lines big)))
          "and the TEST path elides nothing by default — a test's tokens are cheap, its false failure is not")))

  (testing "an svg is censused by CLASS and never descended"
    ;; a path is an edge in one place and a sketched box in another, so the tag
    ;; says nothing; `2 gap-w0, 1 gap-w4` IS the tint check, with no pixels
    (let [g [:svg {:class "module-graph"}
             [:path {:class "module-link"} "M 0 0 C 12 40, 88 60, 88 100"]
             [:g {:class "gap-w0"}] [:g {:class "gap-w0"}] [:g {:class "gap-w4"}]]]
      (is (= "<svg class=\"module-graph\">2 gap-w0, 1 gap-w4, 1 module-link</svg>"
             (cljnx/of g)))
      (is (not (re-find #"88 100" (cljnx/of g)))
          "coordinates are a screen of context that answers nothing"))))

(deftest a-region-that-is-not-there-REFUSES
  ;; The addition to slopp-ui's design, and it comes from their own near-miss:
  ;; a tint assertion matched its class pattern anywhere on the page, so it
  ;; claimed the diagram, checked a list, and stayed GREEN with the layout torn
  ;; out. A readout makes assertions easy to write and therefore easy to write
  ;; too broadly. Scoping is the fix, and a scope that silently returns nothing
  ;; is worse than no scope at all — every absence assertion downstream of it
  ;; passes, over a screen that may have rendered nothing.
  (let [screen [:div
                [:nav {:data-region "nav"} [:a {:href "/"} "Review"]]
                [:main {:data-region "main"}
                 [:h1 "code"]
                 [:svg {:class "module-graph"} [:g {:class "gap-w4"}]]]]]
    (testing "a region scopes to its own lines and nothing else"
      (is (= ["<h1>code</h1>" "<svg class=\"module-graph\">1 gap-w4</svg>"]
             (mapv str/triml (cljnx/lines screen {:region "main"}))))
      (is (= ["<a href=\"/\">Review</a>"]
             (mapv str/triml (cljnx/lines screen {:region "nav"})))))

    (testing "a region that is not on the screen THROWS, and names the ones that are"
      (let [e (try (cljnx/lines screen {:region "sidebar"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a missing region is a finding, never an empty scope")
        (is (str/includes? (ex-message e) "regions present: nav, main")
            "the list IS the answer to the question behind the mistake")))

    (testing "TWO regions under one name refuse like an ambiguous click"
      (let [dup [:div
                 [:section {:data-region "card"} [:p "first"]]
                 [:section {:data-region "card"} [:p "second"]]]
            e   (try (cljnx/lines dup {:region "card"}) nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "silently taking the first lets an assertion pass against the wrong pane")
        (is (str/includes? (ex-message e) "2 regions"))))

    (testing "and a screen with no regions at all says THAT, not the same message"
      (let [e (try (cljnx/lines [:div [:p "x"]] {:region "main"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (str/includes? (ex-message e) ":data-region")
            "an app that has never addressed a pane needs the mechanism, not a list")))))

(deftest clicking-runs-the-apps-OWN-handler-on-the-jvm
  ;; THE founding case, and the bar the earlier designs failed. A contract where
  ;; the app hands back {:visit :click} makes the APP write the fake browser —
  ;; every project builds its own adapter, that adapter is the least-exercised
  ;; code in the project, and it is free to drift from the real browser path.
  ;; "the agent writes the driver" and "the agent writes the test" are the same
  ;; failure, and it is the failure this whole exercise exists to remove.
  ;;
  ;; So the browser dispatches, and the handler under it is the app's own —
  ;; an ordinary Clojure fn sitting in the tree, exactly as it sits there for
  ;; reagent. Nothing is injected, nothing is simulated, and the state it
  ;; changes is the state the real client would change.
  (let [state (atom {:n 0})
        page  {:state state
               :view  (fn [s]
                        [:div
                         [:p (str "n=" (:n s))]
                         [:button {:on-click #(swap! state update :n inc)} "Add"]])}
        b     (cljnx/open! page)]
    (testing "the document renders from state before anything happens"
      ;; the <button> tag is the readout saying the button is a button — a
      ;; reader deciding what to do next could not otherwise tell it from a
      ;; paragraph
      (is (= "n=0\n<button slopp:on=\"click (fn)\">Add</button>"
             (cljnx/of (cljnx/tree b)))))
    (testing "a click fires the app's handler and the document changes"
      (cljnx/click! b "Add")
      (is (= "n=1\n<button slopp:on=\"click (fn)\">Add</button>"
             (cljnx/of (cljnx/tree b))))
      (cljnx/click! b "Add")
      (is (= "n=2\n<button slopp:on=\"click (fn)\">Add</button>"
             (cljnx/of (cljnx/tree b)))
          "and the session KEEPS state, the way a browser does between clicks"))))

(deftest following-a-link-is-what-clicking-one-means
  ;; The one opinion a browser holds that an app cannot override. Everything
  ;; else about a url — which screen, which params, what loads — goes through
  ;; the page's own :navigate, which is ONE function and deliberately not a
  ;; router. slopp never learns what "/store" means.
  (let [state (atom {:at "/"})
        page  {:state    state
               :navigate (fn [s path] (assoc s :at path))
               :view     (fn [s]
                           [:div
                            [:h1 (:at s)]
                            [:a {:href "/store"} "Code"]])}
        b     (cljnx/open! page)]
    (testing "an href with no handler navigates through the app's own :navigate"
      (cljnx/click! b "Code")
      (is (= "<h1>/store</h1>\n<a href=\"/store\">Code</a>"
             (cljnx/of (cljnx/tree b)))))
    (testing "and a link can be clicked by its address as well as its label"
      (cljnx/visit! b "/")
      (cljnx/click! b "/store")
      (is (= "/store" (:at @state))))))

(deftest a-click-that-cannot-be-honest-REFUSES
  ;; Four ways a click can be dishonest, which a forgiving browser would
  ;; collapse into one silent no-op. The second is the afternoon-waster,
  ;; because the word IS on the screen and the reader can see it there —
  ;; and since clicks BUBBLE now, the refusal is a statement about the
  ;; element and everything above it, not one node's attrs.
  (let [page {:state (atom {})
              :view  (fn [_]
                       [:div
                        [:p "Save"]
                        [:button {:on-click (fn [_])} "Delete"]
                        [:button {:on-click (fn [_])} "Delete"]
                        [:a {:href "/only"} "Go"]])}
        b    (cljnx/open! page)
        msg  (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (testing "nothing says it — and the answer is what CAN be clicked"
      (let [m (msg #(cljnx/click! b "Nope"))]
        (is (str/includes? m "nothing on this screen says"))
        (is (str/includes? m "Delete")
            "the list is the answer to the question behind the mistake")))

    (testing "it is on the screen but nothing over it handles a click"
      (is (str/includes? (msg #(cljnx/click! b "Save"))
                         "neither it nor anything above it handles a click")
          "a different bug from 'not found', and it must not read as one"))

    (testing "two distinct controls say it — picking one is a guess"
      (is (str/includes? (msg #(cljnx/click! b "Delete"))
                         "picking one of them is a guess")))

    (testing "and a page with no :navigate refuses a visit rather than rendering nothing"
      (is (str/includes? (msg #(cljnx/visit! b "/x"))
                         "declares neither :navigate nor :document")))))

(deftest a-server-rendered-app-needs-no-page-declaration-at-all
  ;; The case slopp.http is actually built for, and it must not be the awkward
  ;; one. A page mounted at a url, gone to directly — no SPA, no client router,
  ;; no state to speak of. The route table already exists, `dispatch/handle!`
  ;; is already callable in-process, and the browser should USE them rather
  ;; than ask an app to restate what the framework knows.
  ;;
  ;; So the app's own ctx — the same map `slopp.http/serve!` runs on — becomes a
  ;; driver through `slopp.http/driver`, and a visit is a REAL request through
  ;; the REAL pipeline. `:auth :public` is here because that pipeline is
  ;; default-deny and this fixture would otherwise 401: the browser inherits
  ;; every guarantee the served app has, which is the argument for driving
  ;; dispatch instead of the handler.
  ;;
  ;; The wrapping call is the whole of what an app writes, and it is deliberate
  ;; rather than ceremony: the fake browser knows no app type, so http supplies
  ;; its own adapter exactly as `webapp` supplies its.
  (let [page (fn [body] {:status 200 :body body})
        ctx  {:http/routes
              [{:method :get :path "/" :auth :public :handler
                (fn [_] (page [:div [:h1 "Home"] [:a {:href "/about"} "About"]]))}
               {:method :get :path "/about" :auth :public :handler
                (fn [_] (page [:div [:h1 "About"]]))}
               {:method :get :path "/secret" :auth :authenticated :handler
                (fn [_] (page [:div [:h1 "Secret"]]))}]}
        b    (cljnx/open! (slopp.http/driver ctx))]
    (testing "visiting a mounted path renders that page"
      (cljnx/visit! b "/")
      (is (= "<h1>Home</h1>\n<a href=\"/about\">About</a>"
             (cljnx/of (cljnx/tree b)))))

    (testing "and a link goes there, through the router — no :navigate anywhere"
      (cljnx/click! b "About")
      (is (= "<h1>About</h1>" (cljnx/of (cljnx/tree b)))))

    (testing "a path the app does not mount reads as the 404 it is"
      (cljnx/visit! b "/nope")
      (is (str/includes? (cljnx/of (cljnx/tree b)) "404")
          "the status is the finding — a blank screen would read as a broken page"))

    (testing "and the app's own auth policy applies, because this IS the pipeline"
      (cljnx/visit! b "/secret")
      (is (str/includes? (cljnx/of (cljnx/tree b)) "401")
          "an anonymous visit to a protected page is 401 here exactly as it is served"))))

(deftest the-scoped-assertion-is-the-shorter-one-to-write
  ;; A helper is only a helper if the RIGHT thing is the easy thing. The whole
  ;; failure this exercise came from is an assertion written too broadly —
  ;; matching a class pattern anywhere on the page, claiming the diagram,
  ;; checking a list, and staying green with the layout torn out. So the
  ;; region-scoped call is one argument, not one argument plus an options map.
  (let [page {:state (atom {})
              :view  (fn [_]
                       [:div
                        [:nav {:data-region "nav"} [:a {:href "/"} "Review"]]
                        [:main {:data-region "main"}
                         [:h1 "code"]
                         [:svg {:class "module-graph"} [:g {:class "gap-w4"}]]]])}
        b    (cljnx/open! page)]
    (testing "the page, when the page is what you mean"
      (is (str/includes? (cljnx/text b) "<a href=\"/\">Review</a>")))

    (testing "one region, in one argument — and it comes back dedented"
      (is (= "<h1>code</h1>\n<svg class=\"module-graph\">1 gap-w4</svg>"
             (cljnx/text b "main")))
      (is (= "<a href=\"/\">Review</a>" (cljnx/text b "nav"))))

    (testing "options still reach through — the overlay case is the census's job"
      (is (str/includes? (cljnx/text b "main" {:detail :prose}) "code")))

    (testing "and a region that is not there refuses rather than scoping to nothing"
      (is (thrown? clojure.lang.ExceptionInfo (cljnx/text b "sidebar"))))))

(deftest prose-drops-the-structure-and-keeps-the-sentences
  ;; Most assertions are "does it say X", and they pay for addresses, counts,
  ;; region markers and an svg census they never read. On a real page that is
  ;; most of the bytes.
  ;;
  ;; What it must NOT become is the naive flatten this feature exists to
  ;; replace: every string joined by spaces, where a wrong sentence hides in a
  ;; run-on line. So :prose drops the TAGS and keeps the LINE STRUCTURE — the
  ;; boundary between two sentences is the whole point. Prose is also the one
  ;; mode that never escapes: it makes no structural claims, so it has nothing
  ;; to be confused with.
  (let [page [:div
              [:main {:data-region "main"}
               [:h1 "code"]
               [:p "3 modules, 4 namespaces"]
               [:a {:href "/store"} "Code"]
               [:svg {:class "module-graph"} [:g {:class "gap-w4"}]]
               (into [:ul] (for [i (range 6)] [:li (str "row " i)]))]]]
    (testing "no region wrapper, no heading tag, no address, no census"
      ;; :list-head 3 is the TOOL's cap, passed explicitly — the test path
      ;; defaults to every row. The svg is named in WORDS: prose is unescaped,
      ;; so an angle-bracketed marker here would be the v1 flaw surviving in
      ;; the one mode where nothing could distinguish it from content.
      (is (= (str "code\n"
                  "3 modules, 4 namespaces\n"
                  "Code\n"
                  "svg module-graph\n"
                  "row 0\nrow 1\nrow 2\n"
                  "+3 more")
             (cljnx/of page {:detail :prose :list-head 3}))))

    (testing "a block still owns its line — this is not the flatten"
      (is (not (str/includes? (cljnx/of page {:detail :prose})
                              "code 3 modules"))
          "a wrong sentence in a run-on line is invisible, which is the whole point"))

    (testing "the truncation still SAYS so when a cap is asked for"
      (is (str/includes? (cljnx/of page {:detail :prose :list-head 3}) "+3 more")
          "a cap that went quiet would be a report lying about its own scope"))

    (testing "an svg still marks its place, in words, so a picture does not read as nothing"
      (is (str/includes? (cljnx/of page {:detail :prose}) "svg module-graph"))
      (is (not (str/includes? (cljnx/of page {:detail :prose}) "<svg"))
          "prose is unescaped, so brackets here would be v1's flaw surviving"))

    (testing "and it composes with region scoping"
      (is (str/starts-with? (cljnx/of page {:detail :prose :region "main"}) "code")))))

(deftest what-can-be-clicked-says-so
  ;; An agent looking at a screen is usually deciding what to do NEXT, and
  ;; "what can I click" was unanswerable: a [:button {:on-click f} "Add"]
  ;; rendered identically to [:p "Add"]. In v2 capability keeps the TAG —
  ;; a button is a <button>, a link is an <a href>, and a handler on any
  ;; element at all keeps that element's tag so slopp:on has a place to ride.
  (let [page [:div
              [:p "Save"]
              [:button {:on-click (fn [_])} "Add"]
              [:a {:href "/store"} "Code"]
              [:div {:on-click (fn [_])} [:span "Card"]]]]
    (testing "capability keeps the tag; a closure handler is honestly opaque"
      (is (= (str "Save\n"
                  "<button slopp:on=\"click (fn)\">Add</button>\n"
                  "<a href=\"/store\">Code</a>\n"
                  "<div slopp:on=\"click (fn)\">Card</div>")
             (cljnx/of page))))

    (testing "an unprefixed attr was on the page; the derived one is slopp:*"
      (is (not (str/includes? (cljnx/of page) "slopp:on=\"click (fn)\" href"))
          "href is the page's own fact and never doubles as the derived annotation"))

    (testing "prose stays prose"
      (is (= "Save\nAdd\nCode\nCard" (cljnx/of page {:detail :prose}))
          ":prose answers 'does it say X' and pays for nothing else"))))

(deftest typing-into-a-field-runs-the-apps-own-handler
  ;; Clicking alone is not a browser. A form is the other half of nearly every
  ;; screen, and an app whose filter box cannot be exercised headlessly is back
  ;; to a hand-built lookalike for exactly the interaction most likely to be
  ;; wrong.
  ;;
  ;; A field has no visible TEXT to name it by, so it is addressed the way a
  ;; person would: its placeholder, its name, its id, or its aria-label.
  (let [state (atom {:q ""})
        page  {:state state
               :view  (fn [s]
                        [:div
                         [:input {:placeholder "Filter"
                                  :on-change #(swap! state assoc :q (:value %))}]
                         [:p (str "showing " (:q s))]])}
        b     (cljnx/open! page)]
    (testing "the handler receives the value as DATA and the document changes"
      (cljnx/fill! b "Filter" "store")
      (is (str/includes? (cljnx/of (cljnx/tree b)) "showing store")))

    (testing "a field that is not there refuses, and says what can be filled"
      (let [m (try (cljnx/fill! b "Nope" "x") nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? m "Filter")
            "the list is the answer to the question behind the mistake")))

    (testing "and a field with no :on-change is a different bug from a missing one"
      (let [b2 (cljnx/open! {:state (atom {})
                             :view (fn [_] [:input {:placeholder "Inert"}])})
            m  (try (cljnx/fill! b2 "Inert" "x") nil
                    (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? m "no :on-change"))))))

(deftest a-field-is-visible-and-says-it-can-be-filled
  ;; Worse than unmarked: an [:input] has no CHILDREN, so the first readout
  ;; showed nothing at all where a search box was. An agent reading that screen
  ;; would conclude the app has no filter.
  ;;
  ;; In v2 a field is an `<input>` with every attr fill! can address by —
  ;; the old format showed the FIRST addressing attr, and the review's lead
  ;; finding was its mirror (a field the screen denied while fill! drove it).
  ;; Fillability is the slopp:on annotation; a control state a browser shows
  ;; (value, checked) is an unprefixed attr because it was really on the page.
  (let [page [:form
              [:input {:placeholder "Filter" :value "store" :on-change (fn [_])}]
              [:input {:name "email" :on-change (fn [_])}]
              [:input {:type "checkbox" :aria-label "Agree" :on-change (fn [_])}]
              [:input {:placeholder "Inert"}]
              [:textarea {:name "notes" :on-change (fn [_])}]]]
    (testing "a field is VISIBLE, with its addresses, state, and handler"
      (is (= (str "<form>\n"
                  "  <input placeholder=\"Filter\" value=\"store\" slopp:on=\"change (fn)\"/>\n"
                  "  <input name=\"email\" slopp:on=\"change (fn)\"/>\n"
                  "  <input type=\"checkbox\" aria-label=\"Agree\" slopp:on=\"change (fn)\"/>\n"
                  "  <input placeholder=\"Inert\"/>\n"
                  "  <textarea name=\"notes\" slopp:on=\"change (fn)\"/>\n"
                  "</form>")
             (cljnx/of page))))

    (testing "an inert field shows WITHOUT slopp:on — that is the finding, not a gap"
      (is (str/includes? (cljnx/of page) "<input placeholder=\"Inert\"/>")))

    (testing "and prose keeps the field's name but drops the tags"
      (is (= "Filter\nemail\nAgree\nInert\nnotes" (cljnx/of page {:detail :prose}))))))

(deftest a-handler-runs-whichever-idiom-the-tree-uses
  ;; CHECKED against both libraries rather than inferred from one app, because
  ;; the first version of this supported one idiom and could not drive a real
  ;; app at all.
  ;;
  ;; Reagent: `[:button {:on-click (fn [e] …)}]` — a FUNCTION in the tree.
  ;; Replicant: `[:button {:on {:click …}}]` — a function OR DATA, and data
  ;; goes to one global dispatcher registered with `replicant.dom/set-dispatch!`.
  ;;
  ;; BOTH put the handler ON THE ELEMENT (bubbling — a click on text INSIDE
  ;; one reaching it — is DOM semantics and supported; what stays unsupported
  ;; is hand-rolled `document.addEventListener` delegation, which lives in
  ;; :cljs and never runs here).
  (let [seen  (atom [])
        state (atom {:n 0})
        page  {:state    state
               :dispatch (fn [action value] (swap! seen conj [action value]))
               :view     (fn [_]
                           [:div
                            [:button {:on-click #(swap! state update :n inc)} "Reagent"]
                            [:button {:on {:click #(swap! state update :n inc)}} "Fn"]
                            [:button {:on {:click [:like-video 7]}} "Data"]])}
        b     (cljnx/open! page)]
    (testing "a Reagent-style function on the element"
      (cljnx/click! b "Reagent")
      (is (= 1 (:n @state))))

    (testing "a function under Replicant's :on map"
      (cljnx/click! b "Fn")
      (is (= 2 (:n @state))))

    (testing "DATA under :on reaches dispatch VERBATIM, with no event invented"
      ;; slopp passes (action value) and nothing else. Handing over an event
      ;; MAP was the first design and it was wrong in the worst direction:
      ;; Replicant's real event map carries :replicant/dom-event and no :value,
      ;; so a handler reading (:value e) would have passed here and done
      ;; nothing in a browser. A test that green-lights production breakage is
      ;; worse than no test.
      (cljnx/click! b "Data")
      (is (= [[:like-video 7] nil] (last @seen))
          "the action verbatim — it is what a dispatcher switches on — and no value for a click"))

    (testing "all three read as clickable, and the DATA form says what it will do"
      (is (= (str "<button slopp:on=\"click (fn)\">Reagent</button>\n"
                  "<button slopp:on=\"click (fn)\">Fn</button>\n"
                  "<button slopp:on=\"click :like-video 7\">Data</button>")
             (cljnx/of (cljnx/tree b)))
          "a serializable action is the only handler shape a readout can report — scalar args included, since they are what tells two controls apart"))

    (testing "data with no :dispatch declared REFUSES, naming the gap"
      (let [b2 (cljnx/open! {:state (atom {})
                             :view  (fn [_] [:button {:on {:click [:boom]}} "X"])})
            m  (try (cljnx/click! b2 "X") nil
                    (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? m ":dispatch")
            "silently doing nothing would be a click that reported success and changed nothing")))))

(deftest typing-runs-whichever-idiom-the-field-uses
  ;; The sibling of the click case, and it needs its own test because the two
  ;; idioms disagree about the EVENT NAME as well as the shape: Reagent apps
  ;; write :on-change, a Replicant :on map usually names :input. Trying one
  ;; would leave a field inert for half the ecosystem — and inert reads as a
  ;; bug in the app rather than in the reader.
  (let [seen  (atom nil)
        state (atom {:q "" :r ""})
        page  {:state    state
               :dispatch (fn [action value] (reset! seen [action value]))
               :view     (fn [s]
                           [:div
                            [:input {:placeholder "Reagent" :value (:q s)
                                     :on-change #(swap! state assoc :q (:value %))}]
                            [:input {:placeholder "Replicant" :value (:r s)
                                     :on {:input #(swap! state assoc :r (:value %))}}]
                            [:input {:placeholder "Data" :on {:input [:search]}}]])}
        b     (cljnx/open! page)]
    (testing "Reagent's :on-change"
      (cljnx/fill! b "Reagent" "abc")
      (is (= "abc" (:q @state))))

    (testing "Replicant's :on {:input …} as a function"
      (cljnx/fill! b "Replicant" "xyz")
      (is (= "xyz" (:r @state))))

    (testing "and as DATA — the action verbatim, the typed text as a SCALAR"
      ;; No event map. Replicant's real one has no :value: the typed text sits
      ;; behind (.. e -target -value), which is interop and cannot run on a JVM.
      ;; A handler written against an invented {:value v} would pass here and do
      ;; nothing in a browser, and that direction is the one worth refusing.
      (cljnx/fill! b "Data" "q")
      (is (= [[:search] "q"] @seen)))

    (testing "all three show as fillable, under the event name each one wrote"
      (is (= (str "<input placeholder=\"Reagent\" value=\"abc\" slopp:on=\"change (fn)\"/>\n"
                  "<input placeholder=\"Replicant\" value=\"xyz\" slopp:on=\"input (fn)\"/>\n"
                  "<input placeholder=\"Data\" slopp:on=\"input :search\"/>")
             (cljnx/of (cljnx/tree b)))))))

(deftest a-navigate-that-touches-its-own-atom-does-not-LIVELOCK
  ;; The bug slopp-ui hit, and it took a measurement from them to find because
  ;; every fixture here had a PURE :navigate. Theirs calls their SPA loop, which
  ;; swaps the same state atom — the ordinary shape for a real app.
  ;;
  ;; `visit!` called the app's :navigate INSIDE (swap! state nav path). swap!
  ;; retries its function whenever the CAS loses, so it requires a pure one: an
  ;; inner swap! on the same atom changes the value mid-computation, the outer
  ;; CAS fails, it retries, the inner swaps again — forever, at full CPU.
  ;;
  ;; It presents as a HANG, not an error. Theirs ran 127 seconds and then
  ;; reported "the run did not happen"; query_eval answered [] because the image
  ;; was pinned. Their own loop, measured outside the driver, is 1.4 ms.
  ;;
  ;; **The fixture has to CHANGE something on every pass**, and the first cut of
  ;; this test did not — it assoc'd a constant, and `assoc` returns the
  ;; identical map when the value is already there by identity, so the retry
  ;; converged and the test went green over a live livelock. A real navigate
  ;; mints something fresh each time (their nav/begin-load makes a load TOKEN),
  ;; which is precisely why theirs never converged and mine did.
  ;;
  ;; The general rule, and why this is a design bug rather than a typo:
  ;; **never call an app-supplied function inside swap!.** slopp cannot know
  ;; what an app's fn does, and swap!'s contract says it must do nothing.
  (let [st   (atom {:n 0})
        page {:state    st
              :view     (fn [s] [:p (str "at=" (:at s) " n=" (:n s))])
              ;; a real SPA loop: mutate the atom, mint something fresh, hand
              ;; back the new value
              :navigate (fn [_ path]
                          (swap! st #(-> % (update :n inc) (assoc :at path)))
                          @st)}
        s    (cljnx/open! page)
        f    (future (cljnx/visit! s "/store") :done)]
    (is (= :done (deref f 3000 :TIMED-OUT))
        "a :navigate that touches its own atom livelocked inside swap! — it presents as a hang, and a hang has no message")
    (is (= 1 (:n @st))
        "and it ran ONCE: a retry loop would have incremented this a great many times")
    (is (= "at=/store n=1" (cljnx/of (cljnx/tree s)))
        "and the navigation actually happened")))

(deftest an-action-shows-the-arguments-that-DISTINGUISH-it
  ;; Reported from a real screen: two buttons carrying [:docs/all true] and
  ;; [:docs/all false] both rendered the action kind alone, so the readout said
  ;; the rail had two identical controls. The kind alone is right where the
  ;; argument is an id and noise; it is wrong where the argument IS the whole
  ;; difference, and nothing in a tree tells you which.
  ;;
  ;; So: SCALARS travel, everything else is elided. An enum, a flag or a name
  ;; is what one button has and its neighbour does not; an entity passed whole
  ;; is the case that motivated showing only the kind, and it stays hidden.
  (let [page {:state    (atom {})
              :dispatch (fn [_ _])
              :view     (fn [_]
                          [:div
                           [:button {:on {:click [:docs/all true]}}  "expand all"]
                           [:button {:on {:click [:docs/all false]}} "collapse all"]
                           [:button {:on {:click [:like-video {:id 7 :title "x"}]}} "Like"]
                           [:button {:on {:click [:save]}} "Save"]])}]
    (testing "scalar arguments travel, because they are what tells two controls apart"
      (is (= (str "<button slopp:on=\"click :docs/all true\">expand all</button>\n"
                  "<button slopp:on=\"click :docs/all false\">collapse all</button>\n"
                  "<button slopp:on=\"click :like-video …\">Like</button>\n"
                  "<button slopp:on=\"click :save\">Save</button>")
             (cljnx/of (cljnx/tree (cljnx/open! page))))))))

(deftest the-structured-format-is-text-with-a-whitelisted-tag-channel
  ;; v2 contract, settled 2026-08-05: plain text stays plain; a tag keeps its
  ;; angle brackets only when it carries a fact an agent acts on or asserts
  ;; that its text alone does not say (interactive, enumerable, structural).
  ;; The provenance rule does the rest: an UNPREFIXED tag or attr was really
  ;; on the page; anything slopp derived is slopp:*-prefixed. This replaces
  ;; the invented marker syntax (`#`, `[click]`, `§`, `×N`) whose fatal flaw
  ;; was sharing an alphabet with page text.
  (let [view [:div {:data-region "main"}
              [:h1.page-title "orders"]
              [:p "3 open, " [:strong "1 overdue"]]
              [:p "docs: " [:a {:href "/docs"} "read me"]]
              [:input.search {:placeholder "filter orders" :on {:input [:orders/filter]}}]
              [:ul (for [i (range 5)] [:li (str "row " i)])]
              [:button {:disabled true :on {:click [:orders/expand true]}} "expand all"]
              [:svg {:class "chart"} [:path.bar] [:path.bar]]]
        s (cljnx/of view)]
    (testing "headings are real tags, not markdown"
      (is (str/includes? s "<h1>orders</h1>")))
    (testing "inline emphasis is text only — strong/em/span carry no brackets"
      (is (str/includes? s "3 open, 1 overdue")))
    (testing "a link keeps its tag and href, inline in its sentence"
      (is (str/includes? s "docs: <a href=\"/docs\">read me</a>")))
    (testing "a field shows its addressing attrs and its handler as slopp:on"
      (is (str/includes? s "<input placeholder=\"filter orders\" slopp:on=\"input :orders/filter\"/>")))
    (testing "a list carries its real count, and the test path elides nothing"
      (is (str/includes? s "<ul slopp:count=\"5\">"))
      (is (str/includes? s "<li>row 4</li>"))
      (is (str/includes? s "</ul>")))
    (testing "a disabled control says so, and a data action's scalar args travel"
      (is (str/includes? s "<button disabled slopp:on=\"click :orders/expand true\">expand all</button>")))
    (testing "an svg is censused by class, never descended"
      (is (str/includes? s "<svg class=\"chart\">2 bar</svg>")))
    (testing "a region is a slopp: wrapper — derived by the reader, so prefixed"
      (is (str/includes? s "<slopp:region name=\"main\">"))
      (is (str/includes? s "</slopp:region>")))
    (testing "class and id never reach the output, so sugar and plain spellings render identically"
      (is (= (cljnx/of [:div [:h1.big "t"]])
             (cljnx/of [:div [:h1 {:class "big"} "t"]]))))))

(deftest page-text-that-looks-like-syntax-cannot-be-confused-with-it
  ;; The old format's markers were plain text, so a page containing "# xyz" or
  ;; "[click]" was unfalsifiable — and the first real consumer renders CLOJURE
  ;; SOURCE, a domain made of #, [...] and angle brackets. v2 escapes & < > in
  ;; every text node and attr value; the only raw angle brackets in the output
  ;; are the reader's own tag channel.
  (let [s (cljnx/of [:div
                      [:p "# xyz"]
                      [:p "grep for [click] in the source"]
                      [:p "a < b & c > d"]
                      [:pre "(defn f [] \"<ul>\")"]])]
    (testing "old-syntax lookalikes are inert text now — no marker grammar exists to collide with"
      (is (str/includes? s "# xyz"))
      (is (str/includes? s "grep for [click] in the source")))
    (testing "angle brackets and ampersands in page text are escaped"
      (is (str/includes? s "a &lt; b &amp; c &gt; d")))
    (testing "pre content is verbatim lines, escaped, never squeezed"
      (is (str/includes? s "<pre>"))
      (is (str/includes? s "(defn f [] \"&lt;ul&gt;\")"))
      (is (str/includes? s "</pre>"))
      (is (not (str/includes? s "(defn f [] \"<ul>\")"))))
    (testing "a multi-line pre yields one real line per source line"
      (let [ls (mapv str/triml (cljnx/lines [:div [:pre "line1\nline2"]]))]
        (is (= ["<pre>" "line1" "line2" "</pre>"] ls))))))

(deftest clicking-behaves-like-a-browser-not-a-matcher
  ;; Three review findings, one root: target-node modeled clicks as label
  ;; matching, where a browser models them as events on a tree. A click on
  ;; text INSIDE a handled element fires that handler (bubbling — not
  ;; delegation, which stays unsupported); a disabled control fires nothing
  ;; and says why; an aria-label is an address for a control whose only
  ;; content is an icon — the same attr fill! already honours.
  (let [hits (atom [])
        page (fn [] {:state (atom {})
                     :view (fn [_]
                             [:div
                              [:div {:on {:click [:open-card]}}
                               [:span "icon"] [:span "Open"]]
                              [:button {:disabled true :on {:click [:save]}} "Save"]
                              [:button {:aria-label "close" :on {:click [:close]}} [:svg {:class "x"}]]])
                     :dispatch (fn [a _] (swap! hits conj a))})]
    (testing "a click on text inside a handled ancestor bubbles to it"
      (reset! hits [])
      (cljnx/click! (cljnx/open! (page)) "Open")
      (is (= [[:open-card]] @hits)))
    (testing "a disabled control refuses and runs nothing — production would not fire it"
      (reset! hits [])
      (let [e (try (cljnx/click! (cljnx/open! (page)) "Save") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "disabled"))
        (is (= [] @hits))))
    (testing "aria-label addresses an icon-only control"
      (reset! hits [])
      (cljnx/click! (cljnx/open! (page)) "close")
      (is (= [[:close]] @hits)))))

(deftest the-tree-is-read-the-way-the-libraries-run-it
  ;; Review findings F1/F4/F12/F13 share a root: the reader held a private
  ;; model of hiccup narrower than the one the libraries execute. A component
  ;; vector is CALLED (form-1) and called again when it returns a render fn
  ;; (form-2) — Reagent's own semantics, not a special case. A fragment
  ;; splices. Sugar id/class are attrs, so a sugared field is addressable.
  ;; And the label a screen shows is byte-identical to the label a click
  ;; accepts, because both come from the ONE text function.
  (testing "a fragment splices its children into the sentence"
    (is (= "a x b" (cljnx/of [:p "a " [:<> [:b "x"]] " b"]))))
  (testing "a component vector is called, as the libraries call it"
    (is (= "hello" (cljnx/of [:div [(fn [t] [:p t]) "hello"]]))))
  (testing "a form-2 component's returned render fn runs with the same args"
    (is (= "hi" (cljnx/of [:div [(fn [_] (fn [t] [:p t])) "hi"]]))))
  (testing "sugar id is an attr, so a sugared field is addressable and shown"
    (let [got (atom nil)
          ss  (cljnx/open! {:state (atom {})
                            :view (fn [_] [:div [:input#q {:on {:input [:q/set]}}]])
                            :dispatch (fn [_ v] (reset! got v))})]
      (is (str/includes? (cljnx/text ss nil) "<input id=\"q\""))
      (cljnx/fill! ss "q" "web")
      (is (= "web" @got))))
  (testing "the shown label IS the clickable label"
    (let [n  (atom 0)
          ss (cljnx/open! {:state n
                           :view (fn [_] [:div [:button {:on-click (fn [_] (swap! n inc))} "foo" [:span "bar"]]])})]
      (is (str/includes? (cljnx/text ss nil) ">foobar</button>"))
      (cljnx/click! ss "foobar")
      (is (= 1 @n)))))

(deftest a-script-step-that-cannot-run-says-which-and-why
  ;; The review's most-found bug: drive!'s :else reported (keys steps) — the
  ;; whole VECTOR — so every typo'd step died as "PersistentArrayMap cannot be
  ;; cast to Map$Entry", a refusal that answers nothing, in the interpreter
  ;; every screen tool script runs through. It was also the one form with no
  ;; direct test, which is how a broken refusal ships: fixtures agree with
  ;; their authors, and an untested path agrees with everybody.
  (let [page (fn [seen] {:state    (atom {})
                         :view     (fn [_] [:div
                                            [:input {:placeholder "q" :on {:input [:q/set]}}]
                                            [:button {:on {:click [:go]}} "Go"]])
                         :dispatch (fn [a v] (swap! seen conj [a v]))})
        msg  (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (testing "a typo'd step names ITS keys and the step vocabulary"
      (let [m (msg #(cljnx/drive! (cljnx/open! (page (atom []))) [{:vist "/x"}]))]
        (is (some? m) "a raw ClassCastException is not a refusal")
        (is (str/includes? m ":visit, :click or :fill"))
        (is (str/includes? m ":vist") "the offending step's own keys, not the script's")))
    (testing "a step naming two actions refuses — running one silently is a guess"
      (let [m (msg #(cljnx/drive! (cljnx/open! (page (atom []))) [{:visit "/a" :click "Go"}]))]
        (is (str/includes? m "one action"))))
    (testing "a :fill step with no :value refuses — typing nothing is not a step"
      (let [m (msg #(cljnx/drive! (cljnx/open! (page (atom []))) [{:fill "q"}]))]
        (is (str/includes? m ":value"))))
    (testing "steps that are not maps refuse readably"
      (let [m (msg #(cljnx/drive! (cljnx/open! (page (atom []))) "hi"))]
        (is (str/includes? m "steps"))))
    (testing "a good script runs in order and returns the session"
      (let [seen (atom [])
            s    (cljnx/drive! (cljnx/open! (page seen))
                                [{:fill "q" :value "web"} {:click "Go"}])]
        (is (= [[[:q/set] "web"] [[:go] nil]] @seen))
        (is (some? s))))))

(deftest a-page-that-cannot-open-says-so-at-open
  ;; Review F6: open's docstring says :state and :view are REQUIRED and the
  ;; code checked nothing — a page missing :state died later as "Cannot invoke
  ;; Future.get() because fut is null", and {:vew …} rendered a BLANK PAGE,
  ;; which sends a reader hunting a rendering bug in an app that was never
  ;; wired. Validation belongs at the constructor, where the mistake was made.
  (let [msg (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
    (testing "a page missing a required key is refused, naming it"
      (is (str/includes? (msg #(cljnx/open! {:view (fn [_] [:div])})) ":state"))
      (is (str/includes? (msg #(cljnx/open! {:state (atom {})})) ":view")))
    (testing "an unknown page key is refused — a typo'd :view is a blank page otherwise"
      (let [m (msg #(cljnx/open! {:state (atom {}) :vew (fn [_] [:div])}))]
        (is (some? m))
        (is (str/includes? m ":vew"))))
    (testing ":state must be something deref-and-reset can drive"
      (is (str/includes? (msg #(cljnx/open! {:state {} :view (fn [_] [:div])})) "atom")))
    (testing "a served ctx is REFUSED, and the refusal names the one call that
              turns it into a page"
      ;; D-cljnx: it used to pass through untouched, and `visit!` performed the
      ;; request itself. That is http's adapter living inside the fake browser,
      ;; which is what stopped a browser app's entry ever reaching it — so the
      ;; ctx shape leaves rather than being tolerated beside the new one
      (let [m (msg #(cljnx/open! {:http/routes []}))]
        (is (some? m) "a ctx is no longer a page and must not be accepted as one")
        (is (str/includes? m "slopp.http/driver")
            "the refusal has to carry the migration; the author typo'd nothing"))
      (is (str/includes? (msg #(cljnx/open! {:http/routes [] :state (atom {})
                                                  :view (fn [_] [:div])}))
                         "slopp.http/driver")
          "an app that is BOTH still enters through its capability's driver,
           which carries the page half through"))))

(deftest a-url-is-split-the-way-a-browser-sends-it
  ;; Review F3: visit! passed the raw path as :uri, and Ring's :uri never
  ;; contains "?" — so /search?q=web 404'd on a mounted route that works, and
  ;; every pagination or filter link in a real app read as a broken route.
  (let [ctx {:http/routes
             [{:method :get :path "/search" :auth :public :handler
               (fn [req] {:status 200
                          :body [:div [:h1 "Search"]
                                 [:p (or (:query-string req) "none")]]})}]}
        b   (cljnx/open! (slopp.http/driver ctx))]
    (testing "query params reach the handler as :query-string, not a 404"
      (cljnx/visit! b "/search?q=web")
      (let [s (cljnx/of (cljnx/tree b))]
        (is (str/includes? s "Search"))
        (is (str/includes? s "q=web"))))
    (testing "a fragment is never sent — a browser strips it before the wire"
      (cljnx/visit! b "/search#top")
      (is (str/includes? (cljnx/of (cljnx/tree b)) "none"))))

  (let [page {:state (atom {:at "/"})
              :navigate (fn [s p] (assoc s :at p))
              :view  (fn [s] [:div [:h1 (:at s)]
                              [:a {:href "https://example.com"} "Docs"]
                              [:a {:href "#top"} "Top"]])}
        b    (cljnx/open! page)]
    (testing "an external url refuses — a headless session has nowhere to go"
      (let [m (try (cljnx/click! b "Docs") nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (some? m) "handing https://… to a client router is wrong for both sides")
        (is (str/includes? m "leaves the app"))))
    (testing "a fragment-only href is a scroll, which is a no-op here"
      (cljnx/click! b "Top")
      (is (= "/" (:at @(:state (:app @b)))) "no navigation happened, and nothing threw"))))

(deftest a-handler-arity-is-read-off-the-function-correctly
  ;; Review F2: the probe looked for a DECLARED 1-param invoke. A rest-arg fn
  ;; compiles to RestFn (only doInvoke); with-meta wraps in an AFunction$1 —
  ;; which IS a RestFn of requiredArity 0. Both are valid one-arg handlers in
  ;; any browser, and both got "Wrong number of args (0)" — the manufactured
  ;; signature mismatch the reflection was chosen to avoid.
  (testing "a variadic handler receives the event"
    (let [got (atom ::never)
          b   (cljnx/open! {:state (atom {})
                            :view  (fn [_] [:button {:on-click (fn [e & _more] (reset! got e))} "Go"])})]
      (cljnx/click! b "Go")
      (is (map? @got))))
  (testing "a with-meta wrapped handler receives the event"
    (let [got (atom ::never)
          b   (cljnx/open! {:state (atom {})
                            :view  (fn [_] [:button {:on-click (with-meta (fn [e] (reset! got e)) {:why "meta"})} "Go"])})]
      (cljnx/click! b "Go")
      (is (map? @got))))
  (testing "the zero-arg shorthand still runs"
    (let [n (atom 0)
          b (cljnx/open! {:state n
                          :view  (fn [_] [:button {:on-click #(swap! n inc)} "Go"])})]
      (cljnx/click! b "Go")
      (is (= 1 @n)))))

(deftest filling-a-select-is-choosing-an-option
  ;; Review F11's driving half. A browser never lets you type into a <select>
  ;; — you choose among its options — so a fill! value no option carries is a
  ;; test asserting a flow production cannot produce. Refusing with the choice
  ;; list is the select's version of the click refusal listing what is
  ;; clickable. A checkbox has no text either way: its value is its checked
  ;; state, passed through as the boolean it is.
  (let [seen (atom nil)
        page {:state    (atom {})
              :dispatch (fn [a v] (reset! seen [a v]))
              :view     (fn [_]
                          [:div
                           [:select {:name "sort" :on {:change [:sort/set]}}
                            [:option {:value "name"} "By name"]
                            [:option {:value "age"} "By age"]]
                           [:input {:type "checkbox" :name "agree" :on {:change [:agree/set]}}]])}
        b    (cljnx/open! page)]
    (testing "choosing an option a select carries dispatches its value"
      (cljnx/fill! b "sort" "age")
      (is (= [[:sort/set] "age"] @seen)))
    (testing "a value no option carries refuses, listing the choices"
      (let [m (try (cljnx/fill! b "sort" "created") nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (some? m) "a browser cannot produce this value, so a test must not")
        (is (str/includes? m "name"))
        (is (str/includes? m "age"))))
    (testing "a checkbox takes its checked state as a boolean"
      (cljnx/fill! b "agree" true)
      (is (= [[:agree/set] true] @seen)))))

(deftest the-pages-own-not-a-control-statements-are-honoured
  ;; slopp-ui's ask A, from a real page: an outline row links the same form
  ;; twice — the name, and a deliberately label-less skeleton anchor carrying
  ;; aria-hidden "true" so screen readers get ONE tab stop. A browser has no
  ;; ambiguity there: exactly one user-reachable control has that href. The
  ;; whitelist admitted <a> for being actionable and then discarded the
  ;; attribute saying it is not — and the refusal's suggested fix (label it)
  ;; was the accessibility bug their comment exists to prevent.
  (let [hits (atom [])
        page {:state    (atom {:at "/"})
              :navigate (fn [s p] (assoc s :at p))
              :dispatch (fn [a _] (swap! hits conj a))
              :view     (fn [_]
                          [:div
                           [:a {:href "/store/form/f1"} "rate"]
                           [:a {:href "/store/form/f1"
                                :aria-hidden "true" :tabindex "-1"}
                            [:pre "(defn rate [w z] …)"]]
                           [:button {:inert true :on {:click [:never]}} "Frozen"]])}
        b    (cljnx/open! page)]
    (testing "an aria-hidden duplicate does not make a click ambiguous"
      (cljnx/visit! b "/")
      (cljnx/click! b "/store/form/f1")
      (is (= "/store/form/f1" (:path @b))
          "one user-reachable control answers, exactly as in a browser"))
    (testing "the readout shows the statement, so a reader can tell the two apart"
      (is (str/includes? (cljnx/text b nil) "aria-hidden=\"true\"")))
    (testing "an inert control refuses like a disabled one — a browser delivers no events to it"
      (reset! hits [])
      (let [e (try (cljnx/click! b "Frozen") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "inert"))
        (is (= [] @hits))))))

(deftest the-options-map-is-the-last-entry-that-guessed
  ;; slopp-ui's ask B: open validates, drive! refuses, the tool refuses — and
  ;; text accepted anything. {:detial :prose} silently asserted against
  ;; STRUCTURED output, and most prose assertions pass there too, so the typo
  ;; never surfaces and the test checks something its author did not choose.
  (let [v [:div [:main {:data-region "main"} [:p "hello"]]]]
    (testing "an unknown option refuses, naming it and the vocabulary"
      (let [e (try (cljnx/lines v {:detial :prose}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a guessed default is a wrong answer reported as success")
        (is (str/includes? (ex-message e) ":detial"))
        (is (str/includes? (ex-message e) ":detail"))))
    (testing "the removed :attrs option refuses too — it silently ignored"
      (is (thrown? clojure.lang.ExceptionInfo (cljnx/lines v {:attrs #{:class}}))))
    (testing "a :detail value outside its two words refuses"
      (is (thrown? clojure.lang.ExceptionInfo (cljnx/lines v {:detail :porse}))))
    (testing "the valid vocabulary still passes"
      (is (= "hello" (cljnx/of v {:detail :prose :region "main" :list-head 3}))))))

(deftest within-scopes-text-to-the-element-a-click-would-own
  ;; slopp-ui's ask: region is pane-grain, so a question about one ROW meant
  ;; regexing the whole pane — the exact too-broad assertion the region arity
  ;; exists to prevent, one level down. The fix is a unification, not an
  ;; addition: `:within` addresses by the click matcher's vocabulary (visible
  ;; text, href, aria-label) and resolves by its bubbling notion of the
  ;; OWNING element, then renders that subtree instead of clicking it. One
  ;; document, one addressing scheme.
  (let [page {:state (atom {})
              :dispatch (fn [_ _])
              :view  (fn [_]
                       [:main {:data-region "main"}
                        [:ul
                         [:li {:on {:click [:open :rate]}}
                          [:a {:href "/store/form/f1"} "rate"] " " [:span "[kg zone]"]]
                         [:li {:on {:click [:open :band]}}
                          [:a {:href "/store/form/f2"} "band-for"] " " [:span "[kg]"]]]])}
        b    (cljnx/open! page)]
    (testing "the subtree of the OWNING element — the row, not just the anchor"
      (is (= "rate [kg zone]" (cljnx/text b nil {:within "rate" :detail :prose}))))
    (testing "addressable by href too, exactly like a click"
      (is (str/includes? (cljnx/text b nil {:within "/store/form/f2"}) "band-for")))
    (testing "it composes with a region, and misses refuse listing what IS addressable"
      (is (= "rate [kg zone]" (cljnx/text b "main" {:within "rate" :detail :prose})))
      (let [e (try (cljnx/text b nil {:within "nope"}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "rate")
            "the list is the answer to the question behind the mistake"))))

  (testing "a disabled control can still be LOOKED at — the act-gates are the click's, not the address's"
    (let [b (cljnx/open! {:state (atom {})
                          :view (fn [_] [:div [:button {:disabled true :on-click (fn [_])} "Save"]])})]
      (is (str/includes? (cljnx/text b nil {:within "Save"}) "Save"))))

  (testing "aria-hidden content STAYS in text — it hides nothing visually, and a readout shows the screen"
    ;; decided on purpose (slopp-ui's flag): the click set and the text set
    ;; answer different questions and are allowed to differ. A sighted reader
    ;; sees that span; a readout claiming to show the screen must too.
    (is (= "decorative" (cljnx/of [:p [:span {:aria-hidden "true"} "decorative"]] {:detail :prose})))
    (is (str/includes? (cljnx/of [:div [:p "real"] [:span {:aria-hidden "true"} "decorative"]])
                       "decorative"))))

(deftest a-page-declares-what-runs-at-boot-and-open-runs-it
  ;; slopp-ui's <ul ×0>, root-caused by them: in a browser the entry point
  ;; runs at page load and STARTS the loads not tied to any screen; the
  ;; driver ran routes, views and handlers but never the entry point, so
  ;; boot-scoped data was structurally present and materially empty — with
  ;; nothing distinguishing "the app never asked" from "asked, not arrived".
  ;; The page contract gains :boot — (fn [state] state'), navigate's shape —
  ;; and open runs it once. (User decision, over route-declared loads, which
  ;; would have quietly made route-driven-everything the required
  ;; architecture — a forcing this project already declined once.)
  (testing "boot runs once at open, read-call-write like navigate"
    (let [page {:state (atom {:projects {:status :absent}})
                :boot  (fn [s _url] (assoc s :projects {:status :loading}))
                :view  (fn [s] [:div [:p (name (get-in s [:projects :status]))]])}
          b    (cljnx/open! page)]
      (is (= "loading" (cljnx/text b nil {:detail :prose}))
          "the screen shows the app ASKED — its loading state, not an absence")))
  (testing "and boot is handed the ADDRESS the session opened at"
    ;; "belongs to no particular SCREEN" is not "independent of the ADDRESS",
    ;; and a real hub is where they come apart: its nav pane is a session load
    ;; whose upstream is chosen by the slug in the url. Passing the url here
    ;; rather than at the visit is what keeps a drive and a browser identical,
    ;; since the browser's start! has the address before it routes too.
    (let [seen (atom :unset)
          page {:state    (atom {})
                :boot     (fn [s url] (reset! seen url) s)
                ;; the url arity VISITS, so the page needs urls — open!'s own
                ;; refusal, which is why this carries a :navigate at all
                :navigate (fn [s _path] s)
                :view     (fn [_s] [:p "x"])}]
      (cljnx/open! page "/p/demo/store")
      (is (= "/p/demo/store" @seen))
      (testing "and nil when it opened at no address, which is honest"
        (reset! seen :unset)
        (cljnx/open! page)
        (is (nil? @seen)))))
  (testing "a page without :boot is unchanged"
    (let [b (cljnx/open! {:state (atom {}) :view (fn [_] [:p "hi"])})]
      (is (= "hi" (cljnx/text b nil {:detail :prose})))))
  (testing "a :boot that is not callable refuses at open"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":boot"
                          (cljnx/open! {:state (atom {}) :view (fn [_] [:p "x"]) :boot 42})))))

(deftest a-select-speaks-in-both-modes
  ;; slopp-ui's pair, from their real project switcher. A: prose rendered the
  ;; literal word "select" — the tag NAME as page text, in the one mode with
  ;; no escaping; the v1 flaw class surviving exactly where <svg …> did. A
  ;; browser shows the SELECTED option's label and a screen reader announces
  ;; it, so that is what prose says (falling back to the first option). B:
  ;; disabled did not render on <option>, so a test could not tell
  ;; listed-and-disabled from listed-and-clickable — the select branch held a
  ;; PRIVATE attr list instead of reading kept-attrs, the same second-producer
  ;; shape the inline path had last round.
  (let [v [:div [:select {:on {:change [:proj/go]}}
                 [:option {:value "/a"} "slopp2"]
                 [:option {:value "/b" :disabled true} "older — not running"]]]]
    (testing "prose shows what a browser shows: the selected option's label, first by default"
      (is (= "slopp2" (cljnx/of v {:detail :prose}))))
    (testing "an explicitly selected option wins"
      (is (= "older — not running"
             (cljnx/of [:div [:select {}
                               [:option {:value "/a"} "slopp2"]
                               [:option {:value "/b" :selected true} "older — not running"]]]
                        {:detail :prose}))))
    (testing "disabled renders on an option, so listed-and-disabled is assertable"
      (is (str/includes? (cljnx/of v)
                         "<option value=\"/b\" disabled>older — not running</option>")))))

(deftest every-page-tag-renders-through-one-attr-route
  ;; slopp-ui counted the class: two private attr lists in two rounds, both
  ;; found as downstream symptoms on real pages, neither by looking for the
  ;; shape. The grep they asked for found two more sites in agreement today
  ;; and free to drift (img, the svg class pair) plus the capability trio
  ;; existing as two identical copies. The structural answer: page tags render
  ;; through ONE builder — per-tag whitelist, then the cross-cutting
  ;; capability statements (aria-label / aria-hidden / inert), then slopp:on —
  ;; and a branch cannot hand-build page attrs at all. Pinned here on the
  ;; tags whose private lists dropped the trio.
  (testing "an img carries the page's not-a-control statement like every control does"
    (is (str/includes? (cljnx/of [:div [:img {:alt "chart" :aria-hidden "true"}]])
                       "<img alt=\"chart\" aria-hidden=\"true\"/>")))
  (testing "an option carries it too — no tag is outside the rule"
    (is (str/includes?
         (cljnx/of [:div [:select {}
                           [:option {:value "/a" :aria-hidden "true"} "ghost"]]])
         "<option value=\"/a\" aria-hidden=\"true\">ghost</option>")))
  (testing "the trio renders in one order everywhere — block and inline paths agree"
    (let [block  (cljnx/of [:div [:div {:aria-label "card" :inert true
                                         :on {:click [:open]}} [:p "body"]]])
          inline (cljnx/of [:p "see " [:span {:aria-label "card" :inert true
                                               :on {:click [:open]}} "this"]])]
      (is (str/includes? block  "aria-label=\"card\" inert slopp:on=\"click :open\""))
      (is (str/includes? inline "aria-label=\"card\" inert slopp:on=\"click :open\""))))
  ;; The STRUCTURAL tags were the half this guard never reached. Each built
  ;; its own opening tag as a literal, so each dropped the trio — and the
  ;; guard stayed green because it asserted only the tags that already
  ;; complied. A clickable <h2> is the one that shows the cost: a real
  ;; control, rendered as inert words, in the mode whose whole contract is
  ;; that an unprefixed tag means something you can act on.
  (testing "a heading is a page tag — a clickable one says so"
    (is (str/includes? (cljnx/of [:div [:h2 {:aria-label "sect" :on {:click [:fold]}} "Title"]])
                       "<h2 aria-label=\"sect\" slopp:on=\"click :fold\">Title</h2>")))
  (testing "a table is a page tag"
    (is (str/includes? (cljnx/of [:table {:aria-label "index"} [:tr [:td "a"]]])
                       "<table aria-label=\"index\">")))
  (testing "a pre is a page tag"
    (is (str/includes? (cljnx/of [:div [:pre {:aria-label "src"} "code"]])
                       "<pre aria-label=\"src\">")))
  (testing "and a cell, which is where the class was reported from"
    (is (str/includes? (cljnx/of [:table [:tr [:td {:aria-label "n"} "11"]]])
                       "<td aria-label=\"n\">11</td>"))))

(deftest a-plain-html-FORM-is-a-control-because-a-browser-says-so
  ;; slopp-ui, 2026-08-06. The door their search wave shipped is
  ;; `<form method="get" action="…">` with a named input and a submit button —
  ;; a form rather than an `:on` handler because navigating is an effect no
  ;; state transition expresses, and because a form needs no bundle at all.
  ;;
  ;; The driver refused both halves, each with a well-written message that is
  ;; wrong about this element:
  ;;
  ;;   fill  → "…has no :on-change and no :on {:input …}, so typing into it
  ;;            can change nothing"
  ;;   click → "2 elements say \"search\" and none of them is a control"
  ;;
  ;; The model of "control" was Replicant's — something carrying an `:on`
  ;; handler — rather than HTML's. A GET form's value does not travel on
  ;; INPUT; it travels on SUBMIT, and the submit button IS the control.
  ;;
  ;; It generalises past one app, which is why it is the framework's problem:
  ;; any slopp app with a login, filter or create form has this hole, and each
  ;; author would reach for an `:on` handler they do not need in order to
  ;; become drivable — the driver teaching apps to be LESS script-free than
  ;; they were.
  (let [page {:state    (atom {})
              :navigate (fn [st path] (assoc st :went path))
              :view     (fn [_]
                          [:form {:method "get" :action "/store/search"}
                           [:input {:type "search" :name "q"
                                    :placeholder "search this store"}]
                           [:button {:type "submit"} "search"]])}
        b    (cljnx/open! page)]
    (testing "a NAMED field inside a form is fillable, handler or not"
      ;; its value has nowhere to go until submit, which is not the same
      ;; statement as "typing into it can change nothing"
      (cljnx/fill! b "search this store" "kg zone"))

    (testing "the submit button is a control, and submitting NAVIGATES"
      (cljnx/click! b "search")
      (is (= "/store/search?q=kg+zone" (:path @b))
          (str "action + the named fields serialised by method — a browser's"
               " own behaviour, needing no cooperation from the app: "
               (pr-str (:path @b)))))

    (testing "the field's own :value is the default when nothing was typed"
      (let [c (cljnx/open!
               {:state (atom {}) :navigate (fn [st p] (assoc st :went p))
                :view (fn [_] [:form {:action "/f"}
                               [:input {:name "kind" :value "form"}]
                               [:button {:type "submit"} "go"]])})]
        (cljnx/click! c "go")
        (is (= "/f?kind=form" (:path @c)))))

    (testing "a value with characters a URL cannot carry is encoded"
      (let [d (cljnx/open!
               {:state (atom {}) :navigate (fn [st p] (assoc st :went p))
                :view (fn [_] [:form {:action "/f"}
                               [:input {:name "q" :placeholder "q"}]
                               [:button {:type "submit"} "go"]])})]
        (cljnx/fill! d "q" "a&b=c")
        (cljnx/click! d "go")
        (is (= "/f?q=a%26b%3Dc" (:path @d))
            (str "or the app receives a query string it did not send: "
                 (pr-str (:path @d))))))))

(deftest whitespace-between-two-elements-is-a-text-node-and-survives
  ;; Reported by slopp-ui as "the screen cannot see whitespace inside a
  ;; heading". It is not about headings: `text` is the reader, and it trimmed
  ;; at EVERY level of its own recursion, so a `" "` child became `""` before
  ;; it could be joined to anything. Headings are simply where the renderer
  ;; calls `text` — the other caller is every LABEL that locate/click! match
  ;; on, which is the half nobody had looked at.
  (testing "the separator survives, wherever it sits"
    (is (= "quote demo.order static"
           (hiccup/text [:h4 "quote" " " [:small "demo.order"]
                         [:span " " [:span "static"]]])))
    (is (= "Add 5" (hiccup/text [:button "Add" " " [:span "5"]]))
        "a label a human reads as two words must be pressable as two words"))
  (testing "and the two properties that made this function what it is"
    (is (= "ReviewCode" (hiccup/text [:div [:a "Review"] [:a "Code"]]))
        "CONCATENATED, not space-joined: no whitespace in the markup means none in a browser, and the fix belongs in the markup")
    (is (= "foo bar" (hiccup/text [:span "foo bar"]))
        "the measured case behind that rule — the screen once showed foobar for a button it then refused to press")))

(deftest a-cell-descends-the-way-a-list-item-does
  ;; slopp-ui, building a table lens over their Code index: every module name
  ;; in the table is an <a href>, the links were LIVE (a click step navigated),
  ;; and the readout showed bare words. So a reader who takes structured mode
  ;; at face value files "the table is a readout, not a way in" — a false
  ;; finding about working code, produced by the instrument that exists to
  ;; prevent false findings. Same class as the canned-response `page` bug:
  ;; nothing throws, and it is visible only if you go and check the thing the
  ;; output has just told you not to bother checking.
  ;;
  ;; The cell branch was ALSO the last hand-built tag site — it wrote "<td>"
  ;; by hand, so the capability trio never rode a cell either. That is why the
  ;; fix is page-tag rather than a special case for <a>.
  (testing "an anchor in a cell survives, exactly as the same anchor in an <li> does"
    (let [link [:a {:href "/store/ns/demo.core"} "demo.core"]
          cell (cljnx/of [:table [:tr [:td link]]])
          item (cljnx/of [:ul [:li link]])]
      (is (str/includes? item "<a href=\"/store/ns/demo.core\">demo.core</a>"))
      (is (str/includes? cell "<a href=\"/store/ns/demo.core\">demo.core</a>"))))
  (testing "a cell is a page tag, so the capability statements ride it too"
    (is (str/includes?
         (cljnx/of [:table [:tr [:td {:aria-label "forms" :on {:click [:sort]}} "11"]]])
         "<td aria-label=\"forms\" slopp:on=\"click :sort\">11</td>")))
  (testing "a plain row is still ONE line — the wide-table readout is the point"
    (is (str/includes?
         (cljnx/of [:table [:tr [:td "demo.core"] [:td "11"]]])
         "<tr><td>demo.core</td><td>11</td></tr>")))
  (testing "prose still reads a row as its words"
    (is (str/includes?
         (cljnx/of [:table [:tr [:td [:a {:href "/x"} "demo.core"]] [:td "11"]]]
                    {:detail :prose})
         "demo.core 11"))))

(deftest no-container-flattens-away-a-child-it-cannot-replace
  ;; slopp-ui ran the matrix after the cell fix rather than re-reading the one
  ;; container they had reported, and found the SAME flatten-to-text defect in
  ;; headings — which the cell fix had not carried, because that fix was made
  ;; per-container instead of per-defect. Their instance: `views/change-main`
  ;; renders `[:h4 [:a {:href …} form-name]]`, the ONE route from a change entry
  ;; to the form it changed, and the Review screen read as a list of dead names
  ;; for nine days.
  ;;
  ;; So the assertion here is over the CONTAINER SET, not over the two
  ;; containers that were reported. Enumerating the containers is precisely how
  ;; the first pass missed the second instance.
  (let [link [:a {:href "/x"} "quote"]]
    (doseq [container [[:div link]
                       [:p link]
                       [:label link]
                       [:ul [:li link]]
                       [:h1 link] [:h2 link] [:h3 link]
                       [:h4 link] [:h5 link] [:h6 link]
                       [:table [:tr [:td link]]]
                       [:table [:tr [:th link]]]]]
      (is (str/includes? (cljnx/of [:div container])
                         "<a href=\"/x\">quote</a>")
          (str "dropped inside " (pr-str (first container))))))
  (testing "a control inside a heading keeps what it DOES, not just its label"
    ;; strictly worse than the inert-heading case: not a control rendered
    ;; inert, a control that is absent from the readout altogether
    (is (str/includes? (cljnx/of [:div [:h2 [:button {:on {:click [:go]}} "toggle"]]])
                       "<button slopp:on=\"click :go\">toggle</button>")))
  (testing "and the text joining that slopp-ui #31 fixed still holds"
    ;; a heading whose children carry nothing actionable still reads as one
    ;; sentence — descending must not become a reason to split the line
    (is (str/includes? (cljnx/of [:div [:h2 "quote" " " [:small "demo.order"]]])
                       "<h2>quote demo.order</h2>"))))

(deftest a-BLOCK-child-breaks-the-line-whatever-container-it-is-in
  ;; Reported by slopp-ui from a real screen, and the generalisation is theirs:
  ;; the rule is not about labels. `li` and `div` descend into a block child
  ;; while `label` and `span` glue every child onto one line — so the SAME
  ;; markup reads two ways depending on a container the output never names, and
  ;; a browser disagrees with both of the gluing cases.
  ;;
  ;; One root cause under three symptoms: inline-ness was decided by the TAG,
  ;; and it is a property of the SUBTREE. `[:span "a" [:p "b"]]` is
  ;; inline-tagged and is not inline.
  (let [l (fn [v] (mapv str/triml (cljnx/lines v)))]
    (testing "a label whose children are all inline is still ONE line — the
              common case, and the control this whole change has to preserve"
      (is (= ["<label>q · string</label>"]
             (l [:label [:span "q"] " · " [:span "string"]]))))

    (testing "a label carrying a BLOCK child opens out, the way an li does"
      (is (= ["<label>" "q · string" "what it is" "</label>"]
             (l [:label [:span "q"] " · " [:span "string"] [:p "what it is"]]))))

    (testing "and a span does too — which is what shows the rule is about
              containers in general rather than about labels. It KEEPS its tag
              on the way out rather than going transparent: the first version of
              this let the element fall through to the transparent container,
              which dropped an `[:a {:aria-hidden \"true\"} [:pre …]]` entirely and
              with it the statement that the anchor is not a control. Ugly and
              readable beats tidy and silent, the same asymmetry `inline-tags`
              is chosen on — and a `p` inside a `span` is invalid HTML anyway,
              so the noisy answer is the honest one exactly where it shows up."
      (is (= ["<span>" "a" "b" "</span>"] (l [:span [:span "a"] [:p "b"]]))))

    (testing "an inline-only span at block position stays one line"
      (is (= ["ab"] (l [:span [:span "a"] [:em "b"]]))))

    (testing "the predicate is RECURSIVE, so a block grandchild counts: a
              heading whose inline child hides a block must open out, or the
              guard the heading already has is decided by the wrong question"
      (is (= ["<h2>" "<span>" "a" "b" "</span>" "</h2>"]
             (l [:h2 [:span "a" [:p "b"]]]))))

    (testing "an li was already right and stays right — the fix must not reach
              the branches that had the guard"
      (is (= ["<ul slopp:count=\"1\">" "<li>" "q" "what it is" "</li>" "</ul>"]
             (l [:ul [:li [:span "q"] [:p "what it is"]]]))))))

(deftest prose-never-emits-markup-however-a-container-opened-out
  ;; REGRESSION, reported by slopp-ui against jar d25758 — mine, and from the
  ;; descent fix they asked for. Making an inline tag with block children open
  ;; out was right; writing that branch without the `prose?` guard every sibling
  ;; branch has was not, so `<a href>` wrapping a `<div>` printed its own tags
  ;; into a mode whose entire contract is sentences, unescaped.
  ;;
  ;; Worse than its size for one reason they named: prose is what this project
  ;; is told to reach for when the fact is an ADJACENCY — `web ⚡ 1 ns` — because
  ;; structured puts a closing tag between the two words. Markup in prose puts
  ;; back exactly the characters prose exists to remove, and AGENTS.md points a
  ;; reader at that mode with `:within`, so the recommended assertion style was
  ;; the one that broke.
  ;;
  ;; Their nav rail is the ordinary whole-row-is-a-link pattern — an `<a>`
  ;; around a block — so every row printed markup. Two of their assertions were
  ;; the only thing that noticed.
  (let [p (fn [v] (mapv str/triml (cljnx/lines v {:detail :prose})))]
    (testing "an inline tag with INLINE children is words, as it always was"
      (is (= ["web 1 ns"]
             (p [:a {:href "/x"} [:span "web"] " " [:span "1 ns"]]))))

    (testing "an inline tag with a BLOCK child is ALSO words — opening out is a
              structured-mode decision and prose makes no structural claims"
      (is (= ["web"] (p [:a {:href "/x"} [:div "web"]]))))

    (testing "the same shape that is not an inline tag, as the control"
      (is (= ["web"] (p [:div [:div "web"]]))))

    (testing "a label was already right, and the fix must not disturb it"
      (is (= ["q" "what it is"] (p [:label [:span "q"] [:p "what it is"]]))))

    (testing "and NOTHING this renderer can produce in prose carries a tag —
              the property the branch-by-branch assertions above are instances
              of, asserted once over a tree that exercises every opened-out path"
      (let [out (p [:div
                    [:a {:href "/x"} [:div "row"]]
                    [:span [:span "a"] [:p "b"]]
                    [:label [:span "q"] [:p "d"]]
                    [:h2 [:span "h" [:p "i"]]]
                    [:ul [:li [:a {:href "/y"} [:p "cell"]]]]])]
        (is (empty? (filter #(re-find #"[<>]" %) out))
            (str "prose is sentences, unescaped and unmarked: " (pr-str out)))))))

(deftest a-MOUNTED-webapp-can-be-clicked-through-headlessly
  ;; The round trip end to end, through the fake browser rather than through the
  ;; two functions separately. An app served behind a proxy at `/p/x` renders
  ;; links carrying that prefix, and a reader clicking one arrives at the screen
  ;; it names.
  ;;
  ;; This is the assertion that would have caught every version of the bug the
  ;; mount point has produced: a link that works at the root and 404s behind the
  ;; proxy, a `prefix-links` that stopped being applied, a click handler that
  ;; strips a prefix the render never added. All three are one test now, because
  ;; ONE producer adds the prefix and ONE consumer takes it off.
  ;;
  ;; It lives here rather than beside `slopp.webapp` because `click!` is
  ;; package-private to `slopp.http.*`, and the module edge this needs is already
  ;; declared test-only — production code under `slopp.http` still may not reach
  ;; `webapp`.
  (let [state  (atom {})
        things (fn [_s] [:main
                         [:h1 "Things"]
                         [:a {:href "/things/42"} "Anvil"]])
        thing  (fn [page] [:p (str "Thing " (:id (:params page)))])
        app    (webapp/wiring
                {:webapp/state  state
                 :webapp/base   "/p/x"
                 :webapp/routes [["/things"     things]
                                 ["/things/:id" thing]]})
        s      (cljnx/open! (webapp/driver app))]

    (testing "a reader visits the url they would actually type"
      ;; the FULL url, mount point included — which is what is in the address
      ;; bar. A headless drive that visited app-relative paths would exercise a
      ;; url no browser ever produces
      (cljnx/visit! s "/p/x/things")
      (is (re-find #"Things" (cljnx/text s)) (cljnx/text s)))

    (testing "the rendered link carries the mount point"
      (is (re-find #"/p/x/things/42" (pr-str ((:webapp/view app) @state)))
          (pr-str ((:webapp/view app) @state))))

    (testing "and CLICKING it lands on the screen it names"
      ;; the whole point: the href the render produced is one the navigation
      ;; understands. If prefix and strip disagree by a character, this is the
      ;; assertion that says so
      (cljnx/click! s "Anvil")
      (is (re-find #"Thing 42" (cljnx/text s)) (cljnx/text s))
      (is (= thing (:screen @state))))

    (testing "a url outside the mount point routes nowhere"
      ;; `/things` is not a url under `/p/x`, and treating it as one would mean
      ;; guessing that a foreign path was meant to be ours
      (cljnx/visit! s "/things")
      (is (= thing (:screen @state))
          "the app must not have moved for a url that was never its own"))))

(deftest a-REALISTIC-browser-app-is-DRIVEN-with-nothing-reaching-for-the-platform
  ;; The wave's claim, stated so it can be false: **an app that opts into
  ;; `webapp` writes NO ClojureScript.** Not "as little as possible" — none, as
  ;; the default it has to deliberately leave.
  ;;
  ;; That matters because a `:cljs` namespace is not merely untested, it is
  ;; outside the LOOP: every edit costs a compile to learn anything, which is
  ;; the slow path this project exists to avoid, and it is where the only real
  ;; webapp's two worst bugs both lived.
  ;;
  ;; So the fixture is deliberately NOT minimal. It has what a real screen has —
  ;; a table with captures, chrome with a nav rail, an async load, all three
  ;; kinds of action, a typed input, a link that must be prefixed — and if any
  ;; one of those still forced a browser, this is where it would show.
  ;;
  ;; **What this does NOT cover**, because a checker named without its limits is
  ;; the conflation this repo keeps naming:
  ;;
  ;;   - it does not prove a bundle COMPILES in a consumer's tree
  ;;     (`compile_client` over slopp's own store covers the framework half,
  ;;     `a-built-web-app-RUNS-outside-slopp-entirely` covers vendoring)
  ;;   - it cannot COUNT an app's `:cljs` namespaces, because this fixture has
  ;;     no namespaces of its own — that question is answered at store grain by
  ;;     `query_surface`'s `:webapp/cljs`
  ;;
  ;; What IS asserted: a full application driven end to end, every function of
  ;; which a JVM just called.
  (let [state   (atom {})
        pending (atom nil)
        called  (atom [])
        left    (atom [])
        nav     (fn [s] [:nav [:a {:href "/things"} "Things"]
                         [:span (str "at " (:path s))]])
        ;; the page cases on its OWN load's status — the framework used to render
        ;; loading and failed for the one request a screen declared, which was
        ;; right while a screen had exactly one. A page asks for as many as it
        ;; needs, so only the page knows which of them it is waiting on
        things  (fn [page]
                  (let [ts (webapp/ask! page {:http/method :get
                                              :http/path "/api/things"} {})]
                    (if (= :ready (:status ts))
                      [:ul (for [t (:value ts)]
                             [:li [:a {:href (str "/things/" (:id t))} (:name t)]])]
                      [:p "Loading…"])))
        thing   (fn [{:keys [state params] :as page}]
                  (webapp/ask! page {:http/method :get
                                     :http/path "/api/things/:id"
                                     :http/params #{:id}}
                               {:id (:id params)})
                  [:article
                   [:h1 (str "Thing " (:id params))]
                   [:input {:placeholder "note"
                            :value (:draft state)
                            :on {:input [:thing/typed]}}]
                   [:button {:on {:click [:thing/save]}} "Save"]
                   [:button {:on {:click [:project/switch "other"]}} "Switch"]])
        app     (webapp/wiring
                 {:webapp/state       state
                  :webapp/base        "/p/demo"
                  :webapp/routes      [["/things"     things]
                                       ["/things/:id" thing]]
                  :webapp/chrome      (fn [s inner] [:main (nav s) inner])
                  :webapp/actions     {:thing/typed    {}
                                       :thing/save     {:effectful? true}
                                       :project/switch {:leaves? true}}
                  :webapp/act         (fn [s _action v] (assoc s :draft v))
                  :webapp/request-for (fn [s _a] {:http/method :put
                                                  :http/url    (str "/api/things/" (:id (:params s)))
                                                  :http/body   (:draft s)})
                  :webapp/url-for     (fn [_s a _v] (str "/p/" (second a)))
                  ;; ONE performer, and it sees BOTH kinds of traffic: the load a
                  ;; screen names and the request a control derives. That is the
                  ;; seam `js/fetch` sits behind in a page, which is why there is
                  ;; exactly one of it
                  :webapp/call        (fn [request ok _err]
                                        (swap! called conj request)
                                        (if (= :put (:http/method request))
                                          (ok :saved)
                                          (reset! pending ok)))
                  :webapp/leave!      (fn [u] (swap! left conj u))})
        s       (cljnx/open! (webapp/driver app))]

    (testing "the loading state renders while the load is out, chrome and all"
      (cljnx/visit! s "/p/demo/things")
      (is (re-find #"(?i)loading" (cljnx/text s)) (cljnx/text s))
      (is (re-find #"Things" (cljnx/text s))
          "the nav rail stays up, which is why chrome places the load state"))

    (testing "and the screen renders when the answer lands"
      (@pending [{:id "42" :name "Anvil"}])
      (is (re-find #"Anvil" (cljnx/text s)) (cljnx/text s)))

    (testing "a link carries the mount point, and clicking it routes"
      (cljnx/click! s "Anvil")
      ;; the new page asks for its OWN thing as it renders, and shows what it
      ;; has meanwhile. Answering it here is what a server does
      (@pending nil)
      (is (re-find #"Thing 42" (cljnx/text s)) (cljnx/text s)))

    (testing "each screen asked for its OWN url, decided in :cljc"
      ;; the half that used to live in a browser: WHICH endpoint a url calls.
      ;; A request built by `js/fetch` is a string asserted nowhere; built by
      ;; the screen it is this line
      (is (= ["/p/demo/api/things" "/p/demo/api/things/42"]
             (mapv :http/url @called))
          (str "this app is MOUNTED at /p/demo, so its own API is under that"
               " prefix too — a request addressed at the root is a different"
               " application, or nothing: " (pr-str @called))))

    (testing "typing reaches the reducer as a SCALAR, with no event in sight"
      ;; the shape that used to force an app into :cljs — `(.. e -target -value)`
      ;; in a hand-written dispatcher — is the framework's now, so the app's
      ;; interpreter never sees an event of any kind
      (cljnx/fill! s "note" "hello")
      (is (= "hello" (:draft @state)) (pr-str @state)))

    (testing "an effectful control makes the request the app DERIVED"
      (cljnx/click! s "Save")
      (is (= {:http/method :put
              :http/url    "/p/demo/api/things/42"
              :http/body   "hello"}
             (last @called))
          (pr-str @called))
      (is (= "/p/demo/api/things/42" (:http/url (last @called)))
          (str "a control's request is the same shape a screen's is AND takes"
               " the same mount point — addressing one and not the other makes"
               " an app right once and wrong once, which reads as a flaky"
               " endpoint rather than as a missing prefix")))

    (testing "and a leaving control hands the page back to the browser"
      (cljnx/click! s "Switch")
      (is (= ["/p/other"] @left) (pr-str @left)))))

(deftest the-fake-browser-drives-ONE-contract-and-knows-no-app-type
  ;; D-cljnx, wave 1. The driving contract is now neutral: `:document` is how a
  ;; path becomes a screen, wherever the screen came from. `slopp.http/driver`
  ;; produces it from a served ctx, `slopp.webapp/driver` from a browser app's
  ;; wiring, and this namespace knows about neither — which is what lets it
  ;; leave http's family and become `cljnx`.
  (let [visited (atom [])]

    (testing "a :document-only app needs no state and no view"
      ;; the server-rendered case, which used to require this namespace to
      ;; carry `:http/routes` and call the dispatcher itself
      (let [s (cljnx/open! {:document (fn [path]
                                             (swap! visited conj path)
                                             [:main [:h1 (str "at " path)]])})]
        (cljnx/visit! s "/store")
        (is (= ["/store"] @visited)
            "the document producer was never asked for the path")
        (is (re-find #"at /store" (cljnx/text s nil {:detail :prose})))))

    (testing "the PATH arrives verbatim — splitting it is the producer's job"
      ;; a client router wants the query string; an http adapter strips it
      ;; before it reaches the wire. Neither decision belongs here
      (let [s (cljnx/open! {:document (fn [p] [:main [:p p]])})]
        (cljnx/visit! s "/search?q=kg+zone")
        (is (re-find #"/search\?q=kg\+zone"
                     (cljnx/text s nil {:detail :prose})))))

    (testing "a served CONTEXT is refused, and the refusal names its producer"
      ;; no compatibility shim: a ctx is no longer a shape this namespace
      ;; knows, and a silent acceptance would put http's adapter back
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"slopp\.http/driver"
           (cljnx/open! {:http/routes [{:method :get :path "/x"}]}))))

    (testing "and an app that declares NEITHER a view nor a document refuses"
      ;; an app with no way to produce a screen is a mistake worth hearing
      ;; about at the constructor, where the mistake was made
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i):view|:document"
           (cljnx/open! {:state (atom {})}))))))

(deftest the-entry-to-driver-derivation-is-PUBLIC-and-there-is-only-one
  ;; slopp-ui's ask, and their argument is what decides it. The `screen` tool
  ;; derives a driver from whatever the entry returns. If that derivation is
  ;; internal, a consumer's tests must spell their own — and then the tool and
  ;; the tests wire the app two different ways, which is the lookalike their
  ;; `the-declaration-this-app-hands-both-entries-is-a-valid-one` exists to
  ;; prevent. Worse, it would PASS, because it would be asserting against their
  ;; own reconstruction.
  ;;
  ;; So the derivation is a named public function and both callers point at it.
  ;;
  ;; **It is the one place that knows app types, and it resolves them LATE.**
  ;; `open!` stays ignorant — that is what let the fake browser leave http's
  ;; family. This function names the two capabilities, but only as symbols to
  ;; resolve inside the branch that matched, because vendoring is per family: a
  ;; store using http is handed no `slopp.webapp` source at all, so resolving
  ;; both up front would throw on exactly the app type that has always worked.
  (let [state (atom {})]

    (testing "a webapp DECLARATION becomes the driving contract"
      (let [d (cljnx/driver-for
               {:webapp/state  state
                :webapp/routes [["/things" (fn [_s] [:main [:h1 "things"]])]]})]
        (is (= #{:navigate :boot :state :dispatch :view :location} (set (keys d)))
            (pr-str (keys d)))
        (is (some? (cljnx/open! d)) "and what comes back opens")))

    (testing "a served CTX becomes the same contract"
      (let [d (cljnx/driver-for
               {:http/routes [{:method :get :path "/" :auth :public
                              :handler (fn [_] {:status 200 :body [:main [:h1 "home"]]})}]})]
        (is (fn? (:document d)) (pr-str (keys d)))
        (let [s (cljnx/open! d)]
          (cljnx/visit! s "/")
          (is (re-find #"home" (cljnx/text s nil {:detail :prose}))))))

    (testing "something ALREADY the contract passes through untouched"
      ;; identity on the neutral shape, so a caller never has to ask which of
      ;; the three it is holding — which is the whole point of one derivation
      (let [page {:state state :view (fn [_] [:main [:p "raw"]])}]
        (is (= page (cljnx/driver-for page)))))

    (testing "and a shape it cannot place REFUSES, naming what it takes"
      ;; the constructor's own discipline: a page that cannot open says so
      ;; where the mistake was made, rather than a screen later
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)webapp/state"
           (cljnx/driver-for {:nonsense true}))))))

(deftest an-app-OPENS-at-a-url-and-its-STATUS-is-a-field
  ;; Two halves of one idea: the external interface an agent drives should
  ;; look like a browser. A browser is handed an ADDRESS, and it is never
  ;; wrong about what came back.
  ;;
  ;; Before this, `open!` left the session at no url at all and the only way
  ;; in was a `{:visit …}` step, while the status arrived RENDERED — a 404
  ;; was the string "HTTP 404" somewhere on the screen. Asserting on that
  ;; means a whole-page `str/includes?`, which is one keystroke from
  ;; asserting nothing in particular; it is the same failure `lines` was
  ;; split into two faces to prevent.
  (let [ctx {:http/routes
             [{:method :get :path "/" :auth :public :handler
               (fn [_] {:status 200 :body [:div [:h1 "Home"]]})}
              {:method :get :path "/thing" :auth :public :handler
               (fn [_] {:status 200 :body [:div [:h1 "Thing"]]})}
              {:method :get :path "/gone" :auth :public :handler
               (fn [_] {:status 410 :body {:error "gone"}})}]}]

    (testing "opening AT an address renders that screen, with no visit step"
      (let [b (cljnx/open! (slopp.http/driver ctx) "/thing")]
        (is (= "<h1>Thing</h1>" (cljnx/of (cljnx/tree b))))
        (is (= "/thing" (cljnx/url b)) "the address bar says where we are")
        (is (= 200 (cljnx/status b)))))

    (testing "a status is a NUMBER to compare, not a sentence to search for"
      (let [b (cljnx/open! (slopp.http/driver ctx) "/nope")]
        (is (= 404 (cljnx/status b))
            "the whole point: `(= 404 (status b))` cannot accidentally pass")
        (is (str/includes? (cljnx/of (cljnx/tree b)) "404")
            (str "and the screen still SAYS it — a reader looking at a 404"
                 " must not see a blank page and go hunting for a rendering"
                 " bug in a handler that was never reached"))))

    (testing "every status the pipeline produces arrives the same way"
      (is (= 410 (cljnx/status (cljnx/open! (slopp.http/driver ctx) "/gone"))))
      (is (= 401 (cljnx/status (cljnx/open! (slopp.http/driver
                                             {:http/routes
                                              [{:method :get :path "/s"
                                                :auth :authenticated
                                                :handler (fn [_] {:status 200 :body [:p "s"]})}]})
                                            "/s")))
          "an anonymous visit to a protected page is 401 here exactly as served"))))

(deftest a-redirect-lands-where-a-browser-would
  ;; Post-redirect-get is the commonest real web flow and it did not work at
  ;; all: the driver kept `(:body resp)` and dropped the status and headers,
  ;; so a 302 rendered the WORDS "HTTP 302" and the session's path still
  ;; reported the url you asked for. The address bar is the one thing a
  ;; browser is never wrong about.
  (let [ctx {:http/routes
             [{:method :get :path "/admin" :auth :public :handler
               (fn [_] {:status 302 :headers {"Location" "/login?next=/admin"}})}
              {:method :get :path "/login" :auth :public :handler
               (fn [req] {:status 200
                          :body [:div [:h1 "Sign in"]
                                 [:p (or (:query-string req) "none")]]})}
              ;; two urls pointing at each other — a misconfigured auth
              ;; redirect, and the failure this has to name precisely
              {:method :get :path "/a" :auth :public :handler
               (fn [_] {:status 302 :headers {"Location" "/b"}})}
              {:method :get :path "/b" :auth :public :handler
               (fn [_] {:status 302 :headers {"Location" "/a"}})}
              {:method :get :path "/away" :auth :public :handler
               (fn [_] {:status 302 :headers {"Location" "https://example.com/"}})}]}]

    (testing "the hop is followed and the FINAL address is what we report"
      (let [b (cljnx/open! (slopp.http/driver ctx) "/admin")]
        (is (str/includes? (cljnx/of (cljnx/tree b)) "Sign in"))
        (is (= "/login?next=/admin" (cljnx/url b))
            "not /admin — a browser's address bar shows where you ENDED UP")
        (is (= 200 (cljnx/status b))
            "the status is the FINAL response's; the 302 is history")
        (is (= [{:from "/admin" :status 302 :to "/login?next=/admin"}]
               (cljnx/redirects b))
            (str "and the hop is recorded, so a test can assert THAT it"
                 " redirected rather than only that it ended up somewhere"))
        (is (str/includes? (cljnx/of (cljnx/tree b)) "next=/admin")
            "the target is a real request, query string and all")))

    (testing "a plain page records no redirects at all"
      (is (= [] (cljnx/redirects (cljnx/open! (slopp.http/driver ctx) "/login")))))

    (testing "a LOOP names the cycle, and does not blame a hop count"
      (let [m (try (cljnx/open! (slopp.http/driver ctx) "/a") nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (some? m) "bouncing forever is not an answer")
        (is (str/includes? m "/a") m)
        (is (str/includes? m "/b") m)
        (is (not (str/includes? m "10"))
            (str "a hop CAP would report the commonest redirect bug as"
                 " \"too many hops\", which sends the reader looking for a"
                 " long chain that does not exist. Name the cycle instead"))))

    (testing "a redirect OFF-SITE refuses, and says the app did it"
      (let [m (try (cljnx/open! (slopp.http/driver ctx) "/away") nil
                   (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (some? m))
        (is (str/includes? m "example.com") m)
        (is (str/includes? m "redirected") m)
        (is (not (str/includes? m "leaves the app"))
            (str "distinct from a CALLER visiting an external url: that is a"
                 " mistake in the test, this is a fact about the app"))))))

(deftest a-document-answers-hiccup-or-a-RESPONSE-and-nothing-else
  ;; The contract widened so the status could survive the trip, and the
  ;; discrimination is safe for a reason worth stating: top-level hiccup is a
  ;; VECTOR and never a map, so the two shapes cannot be confused.
  ;;
  ;; A hand-written page and `slopp.webapp`'s driver keep answering hiccup and
  ;; keep knowing nothing about http. That is the property that lets the fake
  ;; browser belong to no capability, and widening a contract is exactly where
  ;; it would be lost by accident.
  (testing "a hiccup document renders, and has no status because nothing asked one"
    (let [b (cljnx/open! {:document (fn [p] [:div [:h1 (str "at " p)]])} "/x")]
      (is (= "<h1>at /x</h1>" (cljnx/of (cljnx/tree b))))
      (is (nil? (cljnx/status b))
          (str "not 200 — inventing a status for a page with no http behind"
               " it would be a confident answer to a question nobody asked"))
      (is (= "/x" (cljnx/url b)))))

  (testing "a response document renders its body and carries its status"
    (let [b (cljnx/open! {:document (fn [_] {:status 201 :body [:p "made"]})} "/x")]
      (is (= "made" (cljnx/of (cljnx/tree b))))
      (is (= 201 (cljnx/status b)))))

  (testing "a non-hiccup body renders as its STATUS and its data"
    ;; a 404 that read as an empty screen sends a reader looking for a
    ;; rendering bug in a handler that was never reached
    (let [b (cljnx/open! {:document (fn [_] {:status 404 :body {:error "no route"}})} "/x")
          s (cljnx/of (cljnx/tree b))]
      (is (str/includes? s "404") s)
      (is (str/includes? s "no route") s)))

  (testing "a map that is neither REFUSES, naming both legal shapes"
    (let [m (try (cljnx/open! {:document (fn [_] {:body [:p "hi"]})} "/x") nil
                 (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (is (some? m) "rendering this blank is the failure open!'s checks exist to end")
      (is (str/includes? m ":status") m)
      (is (str/includes? m "hiccup") m))))

(deftest a-STRING-body-is-already-text-and-is-not-quoted-again
  ;; Measured by slopp-ui on a real 302 before taking the fix, which is the
  ;; only window in which the old behaviour could be recorded rather than
  ;; remembered. The hub's root answered:
  ;;
  ;;   HTTP 302
  ;;   <pre>"&lt;!DOCTYPE html&gt;…&lt;a href=\"/p/demo/\"&gt;continue&lt;/a&gt;…"</pre>
  ;;
  ;; DOUBLE-escaped: `pr-str` wrapped the body in quotes and escaped its
  ;; quotes, and then the reader HTML-escaped the result. So the one actionable
  ;; thing in a redirect body — the `continue` link naming the target — arrived
  ;; as unreadable text rather than as a link a click! could take. The
  ;; correction to the account matters: this was not "a redirect you must
  ;; follow by hand", it was one with no manual workaround at all.
  ;;
  ;; `pr-str` is right for DATA — {:error "no route"} has to keep its shape —
  ;; and wrong for a string, which is already what it looks like.
  (testing "a string body shows the text, escaped ONCE by the reader"
    (let [b (cljnx/open! {:document (fn [_] {:status 502
                                             :body "<a href=\"/p/demo/\">continue</a>"})}
                         "/x")
          s (cljnx/of (cljnx/tree b))]
      (is (str/includes? s "502") s)
      (is (str/includes? s "continue") s)
      (is (not (str/includes? s "\\\""))
          (str "no backslash-escaped quotes: that is pr-str's, on top of the"
               " reader's own escaping, and it is what made the link"
               " unreadable: " s))))

  (testing "and DATA keeps its shape, because that is what reading it means"
    (let [b (cljnx/open! {:document (fn [_] {:status 404 :body {:error "no route"}})}
                         "/x")
          s (cljnx/of (cljnx/tree b))]
      (is (str/includes? s ":error") s)
      ;; a map's string VALUE stays quoted — that is the map's own printing
      (is (str/includes? s "\"no route\"") s))))

(deftest a-page-served-through-slopps-own-HTML-HELPER-is-drivable
  ;; Found by slopp-ui on a real content page, and it is general rather than
  ;; theirs: `html-response` calls `(render hiccup)`, so its `:body` is a
  ;; STRING — which took the non-hiccup fallback. Every `slopp.http` app
  ;; serving through slopp's OWN documented helper was undrivable:
  ;;
  ;;   text   "HTTP 200\n<pre>&lt;!DOCTYPE html&gt;…&lt;h1&gt;slopp&lt;/h1&gt;…</pre>"
  ;;
  ;; so `text` returned MARKUP rather than the page's words, `region` could not
  ;; scope, `click!` had no anchors, and a 200 was labelled `HTTP 200` — an
  ;; error banner on a page that is fine.
  ;;
  ;; The fix is NOT an HTML parser. `html-response` holds the hiccup at the
  ;; moment it serializes, so it carries it: `:http/hiccup` beside the body.
  ;; Same move as the whole response travelling, one layer in — keep what you
  ;; already have rather than reconstructing it downstream.
  ;;
  ;; And the translation lives in `slopp.http/driver`, NOT here. The fake
  ;; browser must not learn an http-prefixed key: it belongs to no capability,
  ;; which is what lets it be vendored to every store. An adapter turning its
  ;; own vocabulary into the neutral contract is exactly the seam's job — the
  ;; same argument that puts url-splitting there.
  (let [ctx {:http/routes
             [{:method :get :path "/" :auth :public :handler
               (fn [_] (html/html-response
                        [:main {:data-region "main"}
                         [:h1 "slopp"]
                         [:p "No project has checked in yet"]
                         [:a {:href "/p/demo/"} "demo"]]))}
              {:method :get :path "/p/demo/" :auth :public :handler
               (fn [_] (html/html-response [:main [:h1 "demo"]]))}]}
        b   (cljnx/open! (slopp.http/driver ctx) "/")]

    (testing "the screen is the page's WORDS, not its markup"
      (let [s (cljnx/text b)]
        (is (str/includes? s "No project has checked in yet") s)
        (is (not (str/includes? s "DOCTYPE")) s)
        (is (not (str/includes? s "HTTP 200"))
            (str "a 200 labelled HTTP 200 reads as an error banner on a page"
                 " that is fine: " s))))

    (testing "and it is structure, so a region scopes and a link is a link"
      (is (str/includes? (cljnx/text b "main" {:detail :prose}) "slopp")
          (str "the region cut works because the tree was never a string: "
               (cljnx/text b)))
      (cljnx/click! b "demo")
      (is (str/includes? (cljnx/text b) "demo"))
      (is (= "/p/demo/" (cljnx/url b))
          "clicking an anchor navigates, which needs a parsed anchor"))

    (testing "the status still travels, and the two views cannot disagree"
      (is (= 200 (cljnx/status b)))
      ;; `:document` is the fake browser's contract, not the socket's, so the
      ;; body it hands back is the SCREEN's — the structure. The socket's view
      ;; is `html-response`'s own map, whose :body is the rendered string.
      ;;
      ;; They cannot drift, and that is the property worth pinning rather than
      ;; either value: both derive from ONE hiccup at one call site. An HTML
      ;; parser downstream would have produced a second derivation that agrees
      ;; until it does not.
      (let [served (html/html-response [:p "one"])
            driven ((:document (slopp.http/driver
                                {:http/routes [{:method :get :path "/" :auth :public
                                                :handler (fn [_] served)}]}))
                    "/")]
        (is (string? (:body served)) "what the socket writes")
        (is (= [:p "one"] (:body driven)) "what the reader reads")
        (is (= (:http/hiccup served) (:body driven))
            "one source, so no second derivation exists to drift")))))

^{:unsafe "interns a var with no stored form, which is the SUBJECT rather than a
  shortcut: `marked-pages` scans the loaded IMAGE, so proving it finds a marked
  page needs a loaded var. Writing one at this namespace's top level is what
  this test replaced — it made a fixture page part of slopp's own published
  surface, and made it visible to every other driver-for call in this image.
  The namespace is created and removed inside the test, so nothing outlives it."}
(deftest a-page-DECLARES-its-address-and-the-entry-declares-no-table
  ;; The last duplicate address in this framework. A page carried
  ;; `^{:webapp/path "/things"}` — which is what a build, a document and a gate
  ;; read — and the entry ALSO listed `[["/things" things]]`, which is what the
  ;; running app read. Two coordinate systems for one fact, and the marker was
  ;; the decorative one: nothing broke when they disagreed.
  ;;
  ;; So the entry stops declaring a table. **The marker is the declaration, and
  ;; both entries derive from it** — the browser's from the store at build time
  ;; (`slopp.build/webapp-launcher-source`), the headless one from the loaded
  ;; vars here, which is the image being the oracle for the code that is
  ;; actually loaded.
  ;;
  ;; It happens in `driver-for` rather than in the `screen` tool, and for the
  ;; reason that put `driver-for` here at all: a consumer's own tests drive the
  ;; same entry. Deriving in the tool would route the tool's app and leave the
  ;; consumer's test rendering not-found, which is one app wired two ways with
  ;; nothing comparing them.
  ;;
  ;; An explicit `:webapp/routes` still WINS, so a test can pin one table
  ;; without the image's opinion of it.
  (let [nsx (create-ns 'slopp.fixture-ui)]
    (try
      (intern nsx (with-meta 'things-page {:webapp/path "/fixture/things"})
              (fn [_page] [:main [:h1 "fixture things"]]))
      (let [d (cljnx/driver-for {:webapp/state (atom {})})
            s (cljnx/open! d "/fixture/things")]
        (is (re-find #"fixture things" (cljnx/text s nil {:detail :prose}))
            "the page marked with this address rendered, and no table named it"))
      (finally (remove-ns (ns-name nsx))))))

(deftest a-DRIVEN-page-checks-its-canned-answer-against-the-published-contract
  ;; `endpoint/request` copies `:rest/response` onto the request and `ask!`
  ;; dropped it, so response validation had no hook on the page path at all.
  ;;
  ;; **It is checked HERE rather than in the page, and that is a bundle
  ;; decision.** Validating means calling malli, and `slopp.webapp` is `:cljc`
  ;; — measured, that put 555 KB into every browser bundle, charged to apps
  ;; declaring no contract as much as to those that do, for a promise the
  ;; server already keeps before the bytes leave. On this side malli is on the
  ;; classpath already and costs nothing.
  ;;
  ;; And this side catches what a browser check never sees: a canned FIXTURE
  ;; that has drifted from the contract it claims to exercise. Every bug the
  ;; only real browser app found this week came from a fixture quietly
  ;; bypassing the machinery it was supposed to be driving.
  (let [thing {:http/method   :get
               :http/path     "/api/thing"
               :rest/response [:map [:name :string]]}
        app   (fn [answer]
                {:webapp/state  (atom {})
                 :webapp/routes [["/" (fn [page]
                                        (let [r (slopp.webapp/ask! page thing {})]
                                          [:main (str (:status r) " "
                                                      (:error r))]))]]
                 :webapp/call   (fn [_req ok _err] (ok answer))})]

    (testing "an answer that HONOURS the contract renders ready"
      (let [s (cljnx/open! (cljnx/driver-for (app {:name "a"})) "/")]
        (is (re-find #"ready" (cljnx/text s nil {:detail :prose})))))

    (testing "and one that BREAKS it fails the load, naming the contract"
      ;; the fixture a page would otherwise render as though it were what the
      ;; endpoint promised
      (let [s (cljnx/open! (cljnx/driver-for (app {:name 42})) "/")
            t (cljnx/text s nil {:detail :prose})]
        (is (re-find #"failed" t) t)
        (is (re-find #"(?i)contract" t)
            (str "the failure must say a promise was broken rather than read"
                 " as a transport error: " t))))

    (testing "an app that declares its OWN checker keeps it"
      ;; the driver fills a gap and never overrides — an app with a reason to
      ;; check differently, or not at all, says so
      (let [seen (atom [])
            own  (assoc (app {:name 42})
                        :webapp/check-response
                        (fn [_schema _value] (swap! seen conj :MINE) nil))
            s    (cljnx/open! (cljnx/driver-for own) "/")]
        (is (= [:MINE] @seen) (pr-str @seen))
        (is (re-find #"ready" (cljnx/text s nil {:detail :prose}))
            "the app's own checker accepted it and the driver's did not run")))))

(deftest an-app-with-its-OWN-address-bar-is-asked-where-it-is
  ;; `url` reported the path the caller VISITED. A client-routed app can move
  ;; after that — a page that redirects while rendering — and a browser's
  ;; address bar is never wrong about where you ended up. So an app may
  ;; declare `:location`, its own address bar, and `url` asks it.
  (let [where (atom "/start")
        app   {:state    (atom {})
               :view     (fn [_] [:p "x"])
               :navigate (fn [s p] (reset! where (str p "/landed")) s)
               :location (fn [] @where)}
        s     (cljnx/open! app "/a")]
    (is (= "/a/landed" (cljnx/url s)) "the app's own answer, not the visited path")
    (is (thrown? clojure.lang.ExceptionInfo (cljnx/open! (assoc app :location "/x")))
        "a :location that cannot be called is refused at the constructor")))
