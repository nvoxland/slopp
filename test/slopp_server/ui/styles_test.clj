(ns slopp-server.ui.styles-test
  "In-image assertions on the CSS this project actually serves.

  On the rendered TEXT rather than on the garden data, deliberately: the
  failure this stylesheet has already shipped once is a selector that reads
  correctly as Clojure and renders wrong — `:.app>nav` vs `[:.app [:nav]]` vs
  `[:.app :nav]` are three different rules and one of them was a function
  object. A test over the input data cannot see any of that; it agrees with
  whatever was written.

  Declaration ORDER is not asserted anywhere here. The renderer reorders a
  property map, so an assertion on a whole block pins something nobody chose
  and breaks on an unrelated edit."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp-server.ui.styles :as styles]))

(defn- decls
  "The declarations of the rule `sel` names, as a set — or nil if there is no
  such rule. A set because order is the renderer's business, not this
  project's."
  [css sel]
  (some-> (re-find (re-pattern (str (str/replace sel "." "\\.") "\\{([^}]*)\\}")) css)
          second
          (str/split #";")
          set))

(deftest the-project-switcher-is-styled-INTO-the-bar-not-dropped-beside-it
  ;; It was a bare <select> sitting as a flex sibling of the section links,
  ;; which means OS chrome: its own font, its own box, its own background. It
  ;; is IN the bar structurally and foreign to it visually, and the two roles
  ;; were also undifferentiated — a control that changes which APPLICATION you
  ;; are in, rendered as one more item in a run of links that move you around
  ;; INSIDE one.
  (let [css styles/stylesheet
        sw  (decls css "header .project-switcher")]
    (testing "there is a rule at all, and it is scoped to the bar"
      (is (some? sw)))
    (testing "the bar's own type — this is what makes it read as part of it"
      (is (contains? sw "font:inherit"))
      (is (contains? sw "color:inherit"))
      (is (contains? sw "background:transparent")))
    (testing "at the trailing edge, because it changes the application rather
              than the section, and those are different questions"
      (is (contains? sw "margin-left:auto")))))

(deftest the-switchers-hover-border-is-declared-for-both-colour-schemes
  ;; #ccc on the light bar and #444 on the dark one. Declaring only the light
  ;; value is not a missing rule, it is a WRONG one — a near-white hairline on
  ;; #191919 — which is the failure mode `spine-styles` already recorded once,
  ;; where a dark-mode counterpart outlived the class it was styling.
  (let [css    styles/stylesheet
        hovers (mapv second (re-seq #"header \.project-switcher:hover\{([^}]*)\}" css))]
    (testing "one rule per scheme, and they differ"
      (is (= #{"border-color:#ccc" "border-color:#444"} (set hovers)))
      (is (= 2 (count hovers)) "two rules, not one repeated"))
    (testing "the dark one is INSIDE the dark block, not merely present"
      ;; A rule with the right colour at top level would win in both schemes,
      ;; and the assertion above cannot tell the difference. The sheet's main
      ;; dark block is its last form, so being after the last opener is being
      ;; inside it.
      (is (> (str/index-of css "header .project-switcher:hover{border-color:#444}")
             (str/last-index-of css "@media(prefers-color-scheme:dark){"))))))

(deftest the-search-door-is-styled-into-the-bar-and-does-not-claim-its-edge
  ;; The same complaint the switcher answered: a control flexed into this bar
  ;; without inheriting its type is rendered by the OS — its own font, its own
  ;; box, its own background — and is structurally in the bar while visually
  ;; foreign to it.
  (let [css   styles/stylesheet
        field (decls css "header .store-search input")
        form  (decls css "header .store-search")]
    (testing "the field is the bar's own type"
      (is (some? field) "there is a rule at all")
      (is (contains? field "font:inherit"))
      (is (contains? field "color:inherit"))
      (is (contains? field "background:transparent")))

    (testing "the form does NOT claim the trailing edge — the switcher owns it,
              because it changes which APPLICATION you are in while the search
              box acts on the store you are already in. Two competing
              margin-left:autos split the free space BETWEEN them, which puts a
              hole in the middle of the bar rather than a control at its end"
      (is (some? form))
      (is (not (contains? form "margin-left:auto")))
      (is (= 1 (count (re-seq #"margin-left:auto" css)))
          "exactly one thing in this sheet claims a trailing edge"))

    (testing "and the field's border is declared for BOTH schemes — a #ccc
              hairline on the dark bar's #191919 is a wrong rule, not a
              missing one"
      (let [borders (mapv second (re-seq #"header \.store-search input\{[^}]*border(?:-color)?:1?p?x? ?s?o?l?i?d? ?(#[0-9a-f]{3,6})" css))]
        (is (seq borders)))
      (is (> (str/index-of css "header .store-search input{border-color:#444}")
             (str/last-index-of css "@media(prefers-color-scheme:dark){"))
          "the dark rule must be INSIDE the dark block — a correct colour at
           top level wins in both schemes and no simpler assertion can tell"))))

(deftest a-single-instance-pane-does-not-rule-itself-off-from-a-sibling-it-does-not-have
  ;; `.endpoint`'s `border-top` had a stated purpose in its own docstring —
  ;; "rule off one endpoint from the next" — and it was true when the API
  ;; screen LISTED endpoints. It does not any more: `endpoints-main` is the
  ;; index, `endpoint-main` is the detail, and `.endpoint` appears in exactly
  ;; one form in the whole store, rendered once. A separator with nothing
  ;; above it is a line the reader has to explain to themselves.
  ;;
  ;; The left bar is the same shape one rule out. `[:article]` carries a 3px
  ;; left border as a LIST-ITEM marker, which earns its place in `change-main`
  ;; (one article per changed form) and `form-rail` (one per caller). The
  ;; endpoint detail is a single article, so it inherits a bar marking it out
  ;; from nothing.
  ;;
  ;; This is the construct-outlives-its-reason class: both rules were correct
  ;; when written, the markup changed underneath them, and they kept their
  ;; SHAPE while losing their meaning — which is what a reader still sees.
  (let [css styles/stylesheet
        ep  (decls css ".endpoint")]
    (is (some? ep) "the rule still exists — this is about what it declares")

    (testing "no top rule: there is no next endpoint to be ruled off from"
      (is (not-any? #(str/starts-with? % "border-top") ep)))

    (testing "and it opts out of the article list-item bar"
      ;; `border-left:0`, not `:none` — garden normalises the keyword on the
      ;; way out, and this test reads the RENDERED sheet. Asserting the source
      ;; spelling would pin what was typed rather than what the browser gets.
      (is (contains? ep "border-left:0")))

    (testing "the dark counterpart goes WITH it, or it outlives the thing it
              styled — the failure `spine-styles` already recorded once"
      (is (not (str/includes? css ".endpoint{border-top-color"))))))

(deftest the-landing-is-a-centred-column-with-its-own-rules
  ;; Seen at localhost:7358 with nothing attached: "slopp" and one line of
  ;; text flush against the viewport's left edge, in the browser's defaults.
  ;; The landing is a page in its own right — `project-picker` renders no
  ;; `app-shell` and no `main` — so it had no rule at all. It gets a narrow
  ;; centred column, a project row that reads as a row, and an empty state
  ;; that reads as a panel rather than a stray sentence.
  (let [css styles/stylesheet
        landing (decls css ".landing")
        row     (decls css ".landing li.project")
        empty   (decls css ".landing-empty")]
    (testing "the column"
      (is (some? landing))
      (is (contains? landing "margin:0 auto") "centred")
      (is (some #(re-find #"^max-width:" %) landing) "with a measure")
      (is (some #(re-find #"^padding:" %) landing) "and a gutter"))
    (testing "a project row is laid out, not run together inline"
      (is (some? row))
      (is (contains? row "display:grid")))
    (testing "the empty state is a panel"
      (is (some? empty))
      (is (some #(re-find #"^border:" %) empty)))
    (testing "and it follows the dark theme — a light border in a dark page is the one thing a reader notices"
      (let [dark (subs css (str/index-of css "@media(prefers-color-scheme:dark)"))]
        (is (re-find #"\.landing li\.project\{border-color:#[0-9a-f]{3,6}\}" dark))
        (is (re-find #"\.landing-empty\{[^}]*border-color:#[0-9a-f]{3,6}" dark))))
    (testing "nothing is named hub any more"
      (is (nil? (decls css ".hub"))))))
