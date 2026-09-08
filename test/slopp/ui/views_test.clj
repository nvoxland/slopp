(ns slopp.ui.views-test
  "Tests for every screen, asserting on hiccup DATA.

  These replaced assertions that regexed server-rendered HTML strings. The
  properties are the same; reaching them is now cheaper and sharper, with no
  server and no browser in the loop — which is the payoff the `:cljc` view
  split was chosen for, and the reason to keep resisting anything that pulls
  rendering into `:cljs`.

  Two screens can only be tested here at all: LOADING and NOT-FOUND exist
  only once the server stops rendering pages, so nothing else observes
  them."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ui.views :as views] [clojure.string :as str] [slopp.ui.styles :as styles] [slopp.ui.callgraph :as callgraph] [slopp.ui.schema :as schema] [slopp.cljnx :as cljnx] [slopp.webapp :as webapp] [slopp.ui.app :as app] [slopp.ui.wire.api :as api] [slopp.http.endpoint :as endpoint] [slopp.ui.pages :as pages]))

(deftest app-shell-lays-out-three-panes-from-data
  ;; The whole point of the :cljc discipline: this is an ORDINARY in-image
  ;; test asserting on hiccup DATA. No DOM, no browser, no cljs runner —
  ;; 0.5 ms rather than the 368 ms anything in the external tier costs. If a
  ;; view ever needs the external tier, the split is wrong.
  (let [v (views/app-shell
           {:nav/sections [{:label "Review" :href "/"}
                           {:label "Code" :href "/store" :active? true}]
                        :nav/local    [:div [:input {:id "ns-filter"}] [:p "left pane"]]
            :nav/detail   [[:h3 "Callers"] [:p "3 forms"]]}
           [:h1 "main content"])]
    (testing "all three panes are present, and the main content with them"
      (is (some? (views/find-region v :nav/sections)))
      (is (some? (views/find-region v :nav/local)))
      (is (some? (views/find-region v :nav/detail))))
    (testing "sections are links, and the active one is marked"
      (let [top (views/find-region v :nav/sections)
            hrefs (for [x (tree-seq coll? seq top)
                        :when (and (vector? x) (= :a (first x)))]
                    (:href (second x)))]
        (is (= ["/" "/store"] (vec hrefs)))
        ;; `some`, not `(str (for …))` — stringifying a lazy seq yields
        ;; "clojure.lang.LazySeq@ab2f7f25", which matches no regex and would
        ;; have made this assertion permanently meaningless either way
        (is (= "active"
               (some (fn [x] (when (and (vector? x) (map? (second x))
                                        (= "/store" (:href (second x))))
                               (:class (second x))))
                     (tree-seq coll? seq top)))
            "the current section has to be visibly current")))
        (testing "side panes ride through as given — neither is a link list"
      ;; The local pane carries a filter INPUT, which is why it cannot be link
      ;; data: forcing it through nav-links is exactly what dropped the box.
      (is (some #(= [:h3 "Callers"] %) (tree-seq coll? seq v)))
      (is (some #(= [:input {:id "ns-filter"}] %) (tree-seq coll? seq v))))
    (testing "an omitted pane is absent, not an empty box"
      ;; a form page has no section-local nav; rendering an empty rail would
      ;; take layout space and say nothing
      (let [bare (views/app-shell {:nav/sections [{:label "Review" :href "/"}]}
                                  [:h1 "x"])]
        (is (nil? (views/find-region bare :nav/local)))
        (is (nil? (views/find-region bare :nav/detail)))))))

(deftest sections-are-one-table-and-mark-where-you-are
  ;; Global nav has to be identical on every page, and the CURRENT section has
  ;; to be marked. Both fail the same way if each page builds its own list:
  ;; they drift, and the reader loses track of where they are.
  (testing "every section is a literal href the dangling-route gate can see"
    (doseq [{:keys [href label]} views/sections]
      (is (string? label))
      (is ;; two segments now, because a section is a CAPABILITY and its landing is
        ;; one PAGE inside it — `/rest/paths`, not `/rest`. Still lower-case and
        ;; still literal, which is what `web-dangling-route-refs` joins against.
        (re-matches #"/[a-z/]*" href) (str label " -> " href))))
  (testing "a path picks its section by prefix, longest first"
    (is (= "Review" (:label (views/current-section "/"))))
    (is (= "Review" (:label (views/current-section "/change/d1..d2"))))
    (is (= "Code" (:label (views/current-section "/store"))))
    (is (= "Code" (:label (views/current-section "/store/ns/slopp.api"))))
    (is (= "Code" (:label (views/current-section "/store/form/f1"))))
    (is (= "Dashboard" (:label (views/current-section "/dashboard")))))
  (testing "an unknown path selects nothing rather than guessing"
    ;; marking a section active on a page that is not in it is worse than
    ;; marking none — it tells the reader something false
    (is (nil? (views/current-section "/nowhere"))))
  (testing "marked sections carry exactly one active"
    (let [marked (views/marked-sections "/store/ns/slopp.api")]
      (is (= 1 (count (filter :active? marked))))
      (is (= "Code" (:label (first (filter :active? marked)))))
      (is (= (count views/sections) (count marked))))))

(defn- with-main-load
  "`state` carrying the answer this screen's own ask should receive.

  **It no longer builds a load, because a fixture cannot know the key.** Loads
  were keyed by `:main` — one declared request per route row — so a fixture
  could mint one through `slopp.webapp/load!` and put it where the screen would
  look. Under `ask!` the key is the REQUEST, which is built inside the page from
  a descriptor the fixture does not name; writing that key here would be
  spelling out the page's own endpoint at thirty call sites, and it would go
  quietly stale the moment a page asked for something else.

  So this records the ANSWER and [[full-view]] delivers it, through the app's
  own `:webapp/call`. The load machinery then runs for real — `load!` mints the
  token, applies the freshness guard and puts the result where the page looks —
  which is the property the old version was written to keep. What moved is only
  which end the fixture attaches to: it used to write the result, it now
  answers the request.

  The three statuses survive: `:ready` answers, `:failed` errors, `:loading`
  says nothing at all and leaves the load in flight."
  [state status v]
  (assoc state ::main {:status status :value v}))

(deftest a-definition-is-coloured-by-category-and-badged-for-its-modifiers
  ;; :sig arrives as a SEQUENTIAL of per-arity strings. Rendering it with `str`
  ;; printed the Clojure literal — `render! ["[]"]` on screen — which is the
  ;; failure this whole pane exists to avoid: a source-shaped view showing
  ;; something that is not what source says.
  ;;
  ;; Three channels, three jobs: hue is CATEGORY (function or value),
  ;; lightness is importance, badges are modifiers. Privacy was briefly a
  ;; paler green, which reads as "a lesser function" and would have collided
  ;; with lightness the moment importance arrived.
  (let [v (views/ns-outline-main
           {:ns    "demo.core"
            :forms [{:name "demo.core" :kind "ns"    :sig nil :private? false
                     :doc "Header." :schema nil}
                    {:name "one"    :kind "defn"  :sig ["[x]"] :private? false
                     :doc nil :schema nil}
                    {:name "many"   :kind "defn"  :sig ["[p]" "[p opts]"]
                     :private? false :doc nil :schema nil}
                    {:name "helper" :kind "defn-" :sig ["[]"] :private? true
                     :doc nil :schema nil}
                    {:name "cache"  :kind "defonce" :sig nil :private? false
                     :doc nil :schema nil}
                    {:name "konst"  :kind "def"   :sig nil :private? false
                     :doc nil :schema "[:map [:a :int]]"}
                    {:name "odd"    :kind "defprotocol" :sig nil :private? false
                     :doc nil :schema nil}]}
           {:private? true :state? true})
        nodes  (tree-seq coll? seq v)
        classed (fn [c] (filter #(and (vector? %) (map? (second %))
                                      (= c (:class (second %))))
                                nodes))
        texts  (fn [c] (set (map last (classed c))))
        entry  (fn [n] (first (filter #(some #{n} (filter string?
                                                          (tree-seq coll? seq %)))
                                      (classed "src-def"))))
        ;; the HUE alone: the importance step rides on the same element as its
        ;; own class, and this test is about category, not weight
        strip  (fn [c] (str/join " " (remove #(re-find #"^src-w\d" %)
                                             (str/split (str c) #" "))))
        klass  (fn [n] (->> (tree-seq coll? seq (entry n))
                            (filter #(and (vector? %) (map? (second %))
                                          (re-find #"^src-name\b"
                                                   (str (:class (second %))))))
                            first second :class strip))
        marks  (fn [n] (->> (tree-seq coll? seq (entry n))
                            (filter #(and (vector? %) (map? (second %))
                                          (= "src-badge" (:class (second %)))))
                            (mapv last)))]
    (testing "the kind is not a WORD any more — it was `defn` fourteen times"
      (is (empty? (classed "src-kind")))
      (is (not-any? #{"defn" "defn-" "defonce"} (filter string? nodes))))
    (testing "hue is CATEGORY: a function is a function whether private or not,
              and a value is a value whether it is mutable or not"
      (is (= "src-name src-fn"  (klass "one")))
      (is (= "src-name src-fn"  (klass "helper")))
      (is (= "src-name src-val" (klass "konst")))
      (is (= "src-name src-val" (klass "cache"))))
    (testing "a category this table has no opinion about gets NO class rather
              than borrowing a hue that is already spoken for"
      (is (= "src-name" (klass "odd"))))
    (testing "every VALUE is badged, so the category survives without colour —
              a function needs none because its argument list already says
              `callable`, and a `def` has nothing in that position"
      (is (= [] (marks "one")) "a plain function: its [x] is its mark")
      (is (= 1 (count (marks "konst"))) "a constant")
      (is (= 1 (count (marks "cache"))) "defonce holds state")
      (is (= 1 (count (marks "helper"))) "private")
      (is (= [] (marks "odd")) "and a kind nothing has an opinion about"))
    (testing "marks compose: a private constant carries both, category first"
      (let [v2 (views/ns-outline-main
                {:ns "d" :forms [{:name "k" :kind "def" :private? true}]}
                {:private? true :state? true})]
        (is (= ["📦" "🔒"]
               (->> (tree-seq coll? seq v2)
                    (filter #(and (vector? %) (map? (second %))
                                  (= "src-badge" (:class (second %)))))
                    (mapv last))))))
    (testing "WHAT a thing is leads the name; HOW it reaches outside trails it.
              A reader scanning the column is asking `what are these` first, and
              that answer belongs where the eye already is"
      (let [row  (fn [f] (->> (tree-seq coll? seq
                                        (views/ns-outline-main
                                         {:ns "d" :forms [f]}
                                         {:private? true :state? true}))
                              (keep #(when (and (vector? %) (map? (second %)))
                                       (strip (:class (second %)))))
                              (filter #{"src-lead" "src-name src-fn"
                                        "src-name src-val" "src-badge"})
                              vec))]
        (is (= ["src-lead" "src-badge" "src-name src-fn"]
               (row {:name "h" :kind "defn-" :sig ["[]"] :private? true}))
            "private is a TYPE mark and leads")
        (is (= ["src-lead" "src-badge" "src-name src-val"]
               (row {:name "s" :kind "defonce"}))
            "so is the pin")
        (is (= ["src-lead" "src-name src-fn" "src-badge"]
               (row {:name "m" :kind "defn" :sig ["[]"] :exported? true}))
            "exported trails")
        (is (= ["src-lead" "src-name src-fn" "src-badge"]
               (row {:name "f" :kind "defn" :sig ["[]"] :effectful? true}))
            "so does the effect mark")))
    (testing "the leading slot is always PRESENT, even when empty — it is a
              gutter, and without it every name would start at a different x
              and the straight left edge would be gone"
      (let [leads (fn [f] (->> (tree-seq coll? seq
                                         (views/ns-outline-main {:ns "d" :forms [f]} nil))
                               (filter #(and (vector? %) (map? (second %))
                                             (= "src-lead" (:class (second %)))))))]
        (is (= 1 (count (leads {:name "plain" :kind "defn" :sig ["[]"]}))))))
    (testing "EXPORTED and EFFECTFUL are two facts and get two marks — `^:export`
              is about NAMING (JS calls it by that name), `:effectful?` is about
              EFFECT (it performs or reaches one). A form can be either, both or
              neither, so one combined mark would answer neither.

              It is `:effectful?` and NOT the purity tier: a tier is declared
              per NAMESPACE, so a tier badge would mark every form in
              `slopp.ui.hub` or none — the exact opposite of letting the
              effectful ones stand out, which is what the badge is for"
      (let [badges (fn [forms]
                     (->> (tree-seq coll? seq
                                    (views/ns-outline-main {:ns "d" :forms forms} nil))
                          (filter #(and (vector? %) (map? (second %))
                                        (= "src-badge" (:class (second %)))))
                          (mapv second)))
            titles (fn [forms] (str/join " " (map :title (badges forms))))]
        (is (empty? (badges [{:name "plain" :kind "defn" :sig ["[]"]
                              :private? false :exported? false :effectful? false}])))
        (is (re-find #"(?i)export"
                     (titles [{:name "main" :kind "defn" :sig ["[]"]
                               :exported? true :effectful? false}])))
        (is (re-find #"(?i)effect"
                     (titles [{:name "fetch!" :kind "defn" :sig ["[]"]
                               :exported? false :effectful? true}])))
        (testing "a tier does NOT badge — it is a namespace-wide declaration
                  and would be true of every row or of none"
          (is (empty? (badges [{:name "quiet" :kind "defn" :sig ["[]"]
                                :tier "external" :effectful? false}]))))
        (testing "and a form that is both carries both, exported first"
          (let [b (badges [{:name "boot" :kind "defn" :sig ["[]"]
                            :exported? true :effectful? true}])]
            (is (= 2 (count b)))
            (is (re-find #"(?i)export" (str (:title (first b)))))
            (is (re-find #"(?i)effect" (str (:title (second b)))))))))
    (testing "each badge says in words what its mark means, because an emoji
              is a mnemonic and not a definition"
      (let [b (first (filter #(and (vector? %) (map? (second %))
                                   (= "src-badge" (:class (second %))))
                             (tree-seq coll? seq (entry "helper"))))]
        (is (re-find #"(?i)private" (str (:title (second b)))))))
    (testing "one arity sits beside the name, as its own text and not a literal"
      (is (contains? (texts "src-args") "[x]"))
      (is (not-any? #(re-find #"^\[\"" (str %)) (texts "src-args"))))
    (testing "several arities stack, one line each, and the name is not repeated"
      (is (= ["[p]" "[p opts]"] (mapv last (classed "src-arity")))))
    (testing "a def has NO argument list — not an empty one"
      (is (not-any? #(= "konst" %) (map last (classed "src-args")))))
    (testing "a declared schema is shown, since the pane is the only place it
              appears anywhere in this UI"
      (is (contains? (texts "src-schema") "[:map [:a :int]]"))
      (is (= 1 (count (classed "src-schema")))))))

(deftest shown-defs-keeps-the-surface-and-reveals-only-what-was-asked-for
  (let [rows [{:name "pub"    :kind "defn"    :private? false}
              {:name "hidden" :kind "defn-"   :private? true}
              {:name "cache"  :kind "defonce" :private? false}
              {:name "secret" :kind "defonce" :private? true}]
        names (fn [show] (mapv :name (views/shown-defs rows show)))]
    (testing "nothing asked for: the public surface, and nothing else"
      (is (= ["pub"] (names nil)))
      (is (= ["pub"] (names {}))))
    (testing "each toggle reveals exactly its own class, not the other"
      (is (= ["pub" "hidden"] (names {:private? true})))
      (is (= ["pub" "cache"]  (names {:state? true}))))
    (testing "a row BOTH options hide needs both — one is not enough, or the
              toggle would be showing something its label does not name"
      (is (= ["pub" "hidden" "cache" "secret"]
             (names {:private? true :state? true}))))
    (testing "order is the store's, never the filter's"
      (is (= ["pub" "hidden" "cache" "secret"]
             (names {:private? true :state? true}))))
    (testing "an option nobody has heard of does not hide anything"
      (is (= ["pub"] (names {:invented? true}))))))

(deftest the-namespace-rail-offers-a-toggle-per-display-option
  (let [data {:ns "demo.core"
              :forms [{:name "demo.core" :kind "ns"      :private? false}
                      {:name "pub"       :kind "defn"    :private? false}
                      {:name "hidden"    :kind "defn-"   :private? true}
                      {:name "cache"     :kind "defonce" :private? false}]}
        nodes (fn [v] (tree-seq coll? seq v))
        boxes (fn [v] (->> (nodes v)
                           (filter #(and (vector? %) (= :input (first %))))
                           (mapv second)))]
    (testing "a section of toggles, one per declared option, IN that order —
              written out by hand they drift from what the filter obeys"
      (let [b (boxes (views/ns-rail data nil))]
        (is (= (mapv :data views/display-options) (mapv :data-show b))
            "the DOM attribute is the option's own :data, so the round trip
             back from a click goes through one table")
        (is (every? #(= "checkbox" (:type %)) b))))
    (testing "unchecked by default: arriving at a namespace shows its surface"
      (is (not-any? :checked (boxes (views/ns-rail data nil)))))
    (testing "and checked for exactly the options that are on"
      (let [b (boxes (views/ns-rail data {:state? true}))]
        (is (= [nil true] (mapv :checked b)))))
    (testing "each toggle says how many rows it controls, so turning it on is
              an informed choice rather than a poke"
      (let [t (->> (nodes (views/ns-rail data nil)) (filter string?) (apply str))]
        (is (re-find #"private definitions" t))
        (is (re-find #"state variables" t))
        (is (re-find #"·\s*1" t) "one of each in this fixture")))
    (testing "the rail is a VECTOR of sections, which is what app-shell splices"
      (is (vector? (views/ns-rail data nil)))
      (is (every? #(and (vector? %) (= :section (first %)))
                  (views/ns-rail data nil))))))

(deftest a-toggle-s-dom-name-resolves-back-to-the-option-it-came-from
  ;; The one hop that leaves `:cljc`: a checkbox carries a STRING and the
  ;; browser hands it back. Resolving it here rather than in `client.app` is
  ;; what keeps the round trip testable — a name that did not survive the trip
  ;; would render, click, and silently change nothing, which is indistinguishable
  ;; from a toggle nobody pressed.
  (testing "every declared option round-trips through its own :data"
    (doseq [{:keys [key data]} views/display-options]
      (is (= key (:key (views/option-for data))))))
  (testing "an unknown name is nil, never a guess — the caller writes no state
            rather than writing state under a key nothing reads"
    (is (nil? (views/option-for "invented")))
    (is (nil? (views/option-for nil)))
    (is (nil? (views/option-for "")))))

(deftest importance-stains-the-name-and-does-not-move-when-a-filter-does
  (let [rows  [{:name "big"    :kind "defn"  :mass 100 :calls ["small"] :callers-out 3}
               {:name "small"  :kind "defn"  :mass 5   :calls []        :callers-out 0}
               {:name "mid"    :kind "defn"  :mass 40  :calls ["small"] :callers-out 1}
               {:name "hidden" :kind "defn-" :mass 60  :calls []        :private? true}]
        data  {:ns "d" :forms rows}
        klass (fn [v n] (->> (tree-seq coll? seq v)
                             (filter #(and (vector? %) (map? (second %))
                                           (re-find #"^src-name\b"
                                                    (str (:class (second %))))
                                           (= n (last %))))
                             first second :class))
        step  (fn [v n] (some-> (klass v n) (->> (re-find #"src-w(\d)")) second
                                parse-long))]
    (testing "the weight is its own class, so hue and lightness stay separate
              channels rather than one combined token per combination"
      (is (re-find #"src-name src-fn src-w\d" (klass (views/ns-outline-main data nil) "big"))))
    (testing "and it ranks: the form that covers the most is darker"
      (is (< (step (views/ns-outline-main data nil) "small")
             (step (views/ns-outline-main data nil) "big"))))
    (testing "computed over the WHOLE namespace, not the visible subset —
              revealing the private helper must not restain the rows that were
              already on screen, or every toggle would repaint the listing and
              the colour would be about the filter instead of the code"
      (doseq [n ["big" "small" "mid"]]
        (is (= (step (views/ns-outline-main data nil) n)
               (step (views/ns-outline-main data {:private? true}) n))
            (str n " changed step when a filter opened"))))))

(deftest the-tier-is-badged-on-the-NAMESPACE-because-that-is-its-grain
  ;; `module_purity` scopes by namespace PATH, so every form in slopp.ui.hub
  ;; shares its tier. Badging forms with it would mark all 23 or none — which
  ;; is what the first version of the effect badge did, and the opposite of
  ;; letting anything stand out. The per-FORM fact is `:effectful?`, and on
  ;; that same namespace it marks 5 of 23.
  (let [page  (fn [tier] (views/ns-outline-main
                          {:ns "demo.core" :tier tier
                           :forms [{:name "f" :kind "defn" :sig ["[]"]}]}
                          nil))
        head  (fn [v] (first (filter #(and (vector? %) (= :h1 (first %)))
                                     (tree-seq coll? seq v))))
        marks (fn [v] (->> (tree-seq coll? seq (head v))
                           (filter #(and (vector? %) (map? (second %))
                                         (= "src-badge" (:class (second %)))))
                           (mapv second)))]
    (testing "the mark sits on the heading — the namespace's own line"
      (is (= 1 (count (marks (page "external")))))
      (is (re-find #"(?i)external" (str (:title (first (marks (page "external"))))))))
    (testing "internal is its own mark: in-process mutation is a different
              claim from IO, and the tier system distinguishes them"
      (is (= 1 (count (marks (page "internal")))))
      (is (re-find #"(?i)internal|in-process"
                   (str (:title (first (marks (page "internal"))))))))
    (testing "PURE gets no mark. It is eight of this store's eleven modules,
              and a mark on the majority is not a mark — absence reads as pure
              because the effective tier is always one of the three and an
              undeclared namespace resolves to external, which IS marked"
      (is (empty? (marks (page "pure")))))
    (testing "and no tier on the wire yet means no claim either way"
      (is (empty? (marks (page nil)))))
    (testing "the heading still says the namespace, badge or not"
      (is (some #{"demo.core"} (filter string? (tree-seq coll? seq (head (page "external")))))))))

(deftest the-nav-marks-which-parts-of-the-store-can-do-io
  ;; The same mark as the namespace heading, in the place you are standing when
  ;; you CHOOSE what to open. Knowing that before you click is worth more than
  ;; knowing it after.
  (let [modules [{:module "demo.a" :namespaces ["demo.a.core" "demo.a.util"]
                  :tier "external" :tests 1}
                 {:module "demo.b" :namespaces ["demo.b.pure"]
                  :tier "pure" :tests 1}
                 {:module "demo.c" :namespaces ["demo.c.cache"]
                  :tier "internal" :tests 0}]
        marks (fn [h] (->> (tree-seq coll? seq h)
                           (filter #(and (vector? %) (map? (second %))
                                         (= "src-badge" (:class (second %)))))
                           (mapv last)))
        row   (fn [h cls] (first (filter #(and (vector? %) (map? (second %))
                                               (= cls (:class (second %))))
                                         (tree-seq coll? seq h))))]
    (testing "an external module is marked where you decide whether to open it"
      (let [h (views/module-nav modules nil nil)]
        (is (= ["⚡" "🔁"] (marks h))
            "external and internal marked, pure not — same rule as the heading")))
    (testing "and the mark rides onto its NAMESPACE rows, because the tier
              scopes by PATH: a namespace under an external module is external"
      (let [h (views/module-nav modules "demo.a.core" nil)]
        (is (= 3 (count (filter #{"⚡"} (marks h))))
            "the module head plus BOTH its namespaces — opening a module
             shows all of them, and each carries the tier it inherits")))
    (testing "a pure module's namespaces stay unmarked when opened"
      (let [h (views/module-nav modules "demo.b.pure" nil)]
        (is (empty? (filter #{"⚡" "🔁"} (marks (row h "ns-row")))))))
    (testing "the labels and links are untouched by any of this"
      (let [h (views/module-nav modules nil nil)
            texts (set (filter string? (tree-seq coll? seq h)))]
        (is (contains? texts "a"))
        (is (contains? texts "b"))))))

(deftest a-docstring-collapses-to-its-first-sentence
  (testing "the first sentence, however it ends"
    (is (= "One line." (views/first-sentence "One line.\n\n  And more after it.")))
    (is (= "A question?" (views/first-sentence "A question? Then more.")))
    (is (= "Wow!" (views/first-sentence "Wow! More.")))
    (is (= "No terminator at all"
           (views/first-sentence "No terminator at all"))))
  (testing "a sentence WRAPPED across lines comes back as one line — the
            author's hard wrap is right for a paragraph and wrong for a summary"
    (is (= "Place the layers and the band on a canvas."
           (views/first-sentence "Place the layers and the\n  band on a canvas.\n\n  Then more."))))
  (testing "an abbreviation does not end a sentence — `e.g.` and `i.e.` would
            otherwise truncate a summary mid-clause"
    (is (= "Takes a manifest, e.g. from the wire, and draws it."
           (views/first-sentence "Takes a manifest, e.g. from the wire, and draws it. More."))))
  (testing "blank and nil are themselves rather than an exception"
    (is (= "" (views/first-sentence nil)))
    (is (= "" (views/first-sentence "")))))

(deftest docs-start-collapsed-and-one-switch-opens-them-all
  (let [data {:ns "d"
              :forms [{:name "d" :doc "Header line. Second part here."}
                      {:name "f" :kind "defn" :sig ["[]"]
                       :doc "Does a thing. And here is the long half of it."}]}
        text (fn [v] (str/join " " (filter string? (tree-seq coll? seq v))))
        classes (fn [v] (->> (tree-seq coll? seq v)
                             (keep #(when (and (vector? %) (map? (second %)))
                                      (:class (second %))))
                             set))]
    (testing "collapsed by DEFAULT — arriving at a namespace you want to see
              what is in it, and eleven full docstrings is a wall"
      (let [t (text (views/ns-outline-main data nil))]
        (is (re-find #"Does a thing\." t))
        (is (not (re-find #"long half" t)))))
    (testing "one switch opens every one of them"
      (is (re-find #"long half" (text (views/ns-outline-main data {:docs? true})))))
    (testing "the NAMESPACE's own doc follows the same switch — it is the
              longest prose on the screen and the first thing in the way"
      (is (not (re-find #"Second part" (text (views/ns-outline-main data nil)))))
      (is (re-find #"Second part" (text (views/ns-outline-main data {:docs? true})))))
    (testing "a collapsed block is MARKED collapsed, so a reader knows there is
              more rather than believing the summary is the whole docstring"
      (is (some #(re-find #"src-doc-short" %) (classes (views/ns-outline-main data nil))))
      (is (not-any? #(re-find #"src-doc-short" %)
                    (classes (views/ns-outline-main data {:docs? true})))))
    (testing "a docstring that IS one sentence is not marked — there is nothing
              hidden, and a mark promising more would be a lie"
      (let [one {:ns "d" :forms [{:name "f" :kind "defn" :doc "All of it."}]}]
        (is (not-any? #(re-find #"src-doc-short" %)
                      (classes (views/ns-outline-main one nil))))))))

(deftest the-rail-can-expand-and-collapse-every-comment
  (let [data {:ns "d" :forms [{:name "f" :kind "defn" :doc "One. Two."}]}
        btns (fn [show] (->> (tree-seq coll? seq (views/ns-rail data show))
                             (filter #(and (vector? %) (map? (second %))
                                           (= "doc-toggle" (:class (second %)))))
                             (mapv second)))
        text (fn [show] (str/join " " (filter string?
                                              (tree-seq coll? seq (views/ns-rail data show)))))]
    (testing "both controls are always present, so the pair reads as one
              two-position control rather than a button that vanishes"
      (is (= 2 (count (btns nil))))
      (is (= 2 (count (btns {:docs? true})))))
    (testing "each carries the state it SETS, not the state it is in — the
              browser sends back a string and views/option-for is the only
              thing that reads it, so a button that named its own state would
              have to be interpreted backwards somewhere"
      (is (= #{"expand" "collapse"} (set (map :data-docs (btns nil))))))
    (testing "the one already in effect is marked, so the pair shows which way
              the listing currently is"
      (is (= 1 (count (filter :disabled (btns nil)))))
      (is (= 1 (count (filter :disabled (btns {:docs? true}))))))
    (testing "and the section says what it governs"
      (is (re-find #"(?i)comment|doc" (text nil))))))

(deftest one-comment-can-differ-from-what-the-buttons-set
  (testing "with no override, a comment follows the all-switch"
    (is (not (views/doc-open? nil "f")))
    (is (not (views/doc-open? {} "f")))
    (is (views/doc-open? {:docs? true} "f")))
  (testing "an override wins over the switch, in BOTH directions — collapsing
            one while everything is open is as necessary as the reverse"
    (is (views/doc-open? {:docs? false :doc {"f" true}} "f"))
    (is (not (views/doc-open? {:docs? true :doc {"f" false}} "f"))))
  (testing "an override is per NAME and reaches nothing else"
    (let [show {:docs? false :doc {"f" true}}]
      (is (views/doc-open? show "f"))
      (is (not (views/doc-open? show "g")))))
  (testing "always a boolean, never the raw value — the caller renders on it"
    (is (false? (views/doc-open? nil "f")))
    (is (true? (views/doc-open? {:doc {"f" true}} "f")))))

(deftest each-comment-carries-the-name-that-toggles-it
  (let [data {:ns "d" :doc nil
              :forms [{:name "d" :doc "Header. Hidden header half."}
                      {:name "long"  :kind "defn" :doc "Short bit. Hidden long half."}
                      {:name "brief" :kind "defn" :doc "All of it."}]}
        text (fn [show] (str/join " " (filter string?
                                              (tree-seq coll? seq
                                                        (views/ns-outline-main data show)))))
        docs (fn [show] (->> (tree-seq coll? seq (views/ns-outline-main data show))
                             (filter #(and (vector? %) (map? (second %))
                                           (:data-doc (second %))))
                             (mapv #(:data-doc (second %)))))]
    (testing "a comment with something hidden carries the name that toggles it"
      (is (some #{"long"} (docs nil))))
    (testing "the NAMESPACE's own comment is toggleable too, under its own name"
      (is (some #{"d"} (docs nil))))
    (testing "a one-sentence comment carries NOTHING — there is nothing to
              reveal, and a control that does nothing is worse than no control"
      (is (not-any? #{"brief"} (docs nil))))
    (testing "still toggleable once open, or you could expand and not undo it"
      (is (some #{"long"} (docs {:doc {"long" true}}))))
    (testing "one override opens ONE comment and leaves the others collapsed"
      (let [t (text {:doc {"long" true}})]
        (is (re-find #"Hidden long half" t))
        (is (not (re-find #"Hidden header half" t)))))
    (testing "and one override can CLOSE a comment while the rest are open"
      (let [t (text {:docs? true :doc {"long" false}})]
        (is (not (re-find #"Hidden long half" t)))
        (is (re-find #"Hidden header half" t))))))

(deftest elided-lines-are-deterministic-and-do-not-repeat-as-a-stamp
  (testing "one line per unit of importance, and no more"
    (is (= 1 (count (views/elided-lines "f" 1))))
    (is (= 5 (count (views/elided-lines "f" 5))))
    (is (= [] (views/elided-lines "f" 0))))
  (testing "each line has a width and an indent, so it reads as CODE rather
            than as a bar chart lying on its side"
    (is (every? #(and (:width %) (:indent %)) (views/elided-lines "f" 5)))
    (is (every? #(<= 20 (:width %) 100) (views/elided-lines "f" 5))))
  (testing "DETERMINISTIC — the same form draws the same way every render, or
            the listing would shimmer on every keystroke in the filter box"
    (is (= (views/elided-lines "diagram" 4) (views/elided-lines "diagram" 4))))
  (testing "and it must be deterministic the SAME WAY on both platforms: this
            is :cljc, the tests run on the JVM and the pane runs in a browser,
            so anything built on `hash` would assert one picture here and draw
            another there. Portable arithmetic only"
    (is (= [88 68 81] (map :width (views/elided-lines "abc" 3)))
        "a fixed pattern: line 0 fixed wide, the rest rotated by name length —
         no platform hashing anywhere in it"))
  (testing "neighbouring forms differ, so a column does not read as a stamp"
    (is (not= (map :width (views/elided-lines "ab" 3))
              (map :width (views/elided-lines "abc" 3)))))
  (testing "the longest line comes first, the way a defn's opening line is
            usually its widest"
    (is (apply >= (take 2 (map :width (views/elided-lines "abc" 2)))))))

(deftest every-registry-entry-can-actually-reach-the-screen
  ;; slopp's finding, applied here: a registry and the thing that consumes it
  ;; are two writes related only by a STRING LITERAL, and a green suite after
  ;; the first one says nothing is wrong. Their instance was a tool advertised
  ;; with no dispatch branch; mine are a badge with a position nobody renders
  ;; and a class name nobody styles. Both ship, both look fine, and both are
  ;; invisible until someone looks at the screen.
  (let [css (pr-str styles/ns-source-styles)]
    (testing "the POPULATIONS are non-empty — every check below is
              absence-shaped, and over an empty registry they all pass by
              having nothing to disagree with"
      (is (seq views/form-badges))
      (is (seq views/kind-classes))
      (is (seq views/tier-badges))
      (is (seq views/display-options)))

    (testing "every badge declares a position the renderer actually asks for.
              `ns-outline-main` reads :lead and :trail and nothing else, so a
              third value renders NOWHERE and no other test would fail"
      (is (every? #{:lead :trail} (map :at views/form-badges))))
    (testing "and both of those positions really do render, so the check above
              is not agreeing with a renderer that ignores them both"
      ;; `coll?`, not `vector?`. The LEAD badges are wrapped by `into` and come
      ;; out a vector; the TRAIL badges are a bare seq spliced into one, and a
      ;; vector-only walk cannot descend into a seq. This control caught that
      ;; on its first run — which is the entire argument for writing one.
      (let [row (fn [f] (->> (tree-seq coll? seq
                                       (views/ns-outline-main
                                        {:ns "d" :forms [f]} {:private? true}))
                             (filter #(and (vector? %) (map? (second %))
                                           (= "src-badge" (:class (second %)))))
                             count))]
        (is (pos? (row {:name "a" :kind "defn" :private? true}))  "a lead badge")
        (is (pos? (row {:name "b" :kind "defn" :exported? true})) "a trail badge")))

    (testing "every class name the view hands out has a rule in the stylesheet
              — the pair is a literal in one namespace and a selector in another"
      (doseq [c (vals views/kind-classes)]
        (is (str/includes? css (str ":." c)) (str "no rule for ." c)))
      (doseq [i (range 5)]
        (is (str/includes? css (str "src-w" i)) (str "no rule for step " i))))
    (testing "and the finder can MISS — otherwise the loop above proves only
              that `includes?` can return true. This is the positive control,
              and it is the assertion slopp's own wording test lacked"
      (is (not (str/includes? css ":.src-invented-class"))))

    (testing "every tier that carries a mark has both halves of it"
      (is (every? #(and (:mark %) (:title %)) (vals views/tier-badges))))

    (testing "every reference kind the call graph ranks by has a rule that
              RENDERS it. `confidence` is a vocabulary in one namespace and
              `.via-<kind>` is a selector in another, joined by nothing but the
              spelling — the same pairing as the classes above, one rung out"
      ;; The SELECTORS as data, not `pr-str` + `includes?`. Substring matching
      ;; reads a renamed `.via-observedx` as still containing `.via-observed`,
      ;; so the check passed with the rule broken — caught by breaking it on
      ;; purpose, which is the only reason it was caught at all. A prefix is
      ;; not a match, and garden rules are a vector of keywords sitting right
      ;; there asking to be compared exactly.
      (let [selectors (set (filter keyword? (tree-seq coll? seq styles/spine-styles)))]
        (is (seq callgraph/confidence))
        (is (seq selectors))
        (doseq [k callgraph/confidence]
          (is (contains? selectors (keyword (str ".via-" k))) (str "no rule for .via-" k)))
        (is (not (contains? selectors :.via-invented))
            "the finder must be able to miss, or the loop proves only that
             `contains?` can return true"))

      (testing "and every step the gap ramp can produce has a rule — `gap-step`
                returns 0..4 and the stylesheet is where that range is spent"
        (let [gap-sels (set (filter keyword? (tree-seq coll? seq styles/gap-styles)))
              steps    (set (map #(views/gap-step {:forms 10 :no-doc % :no-why % :uncovered 0})
                                 (range 11)))]
          (is (= #{0 1 2 3 4} steps) "gap-step no longer spans the ramp it is styled for")
          (doseq [s (disj steps 0)]
            (is (contains? gap-sels (keyword (str "li.gap-w" s)))
                (str "no rule for step " s)))
          (is (not (contains? gap-sels :li.gap-w9))))))))

(defn- nodes
  "Every node in a hiccup tree — elements AND the seqs they are spliced from,
  never an attribute map's contents.

  Written because the two obvious predicates are each wrong in a different
  direction, and both fail toward CLEAN:

  - `tree-seq vector?` cannot descend into a bare seq. Hiccup produced by
    `(for …)` and spliced into a vector — `(badges row :trail)`, the rail's
    buttons — is invisible to it, and the failure looks like \"that element is
    not rendered\" rather than \"I cannot see there\".
  - `tree-seq coll?` descends into the attribute MAP, so `{:data-doc \"x\"}`
    and `{:href \"/a\"}` yield their values as strings. A test counting rendered
    TEXT then counts attribute values, which is how one assertion here started
    reporting a namespace name twice when the only change was adding an
    attribute.

  This walks vectors and seqs and stops at maps, which is what a hiccup tree
  actually is. Both hazards were live in this file before it existed."
  [v]
  (tree-seq #(and (coll? %) (not (map? %))) seq v))

(deftest the-project-switcher-lists-what-the-daemon-holds-and-marks-where-you-are
  (testing "no open project means NO switcher — the dropdown degrades to
            nothing rather than to an empty control, and one project alone on
            a daemon is the ordinary case, not a failure"
    (is (nil? (views/project-switcher [] "slopp2")))
    (is (nil? (views/project-switcher nil "slopp2"))))
  (let [ps [{:slug "slopp2" :dir "/w/slopp2" :opened-at 0 :sessions 1 :cli false :app nil}
            {:slug "other"  :dir "/w/other"  :opened-at 0 :sessions 2 :cli true  :app {:url "http://127.0.0.1:7358/api/" :branch "main"}}]
        sw (views/project-switcher ps "slopp2")
        s  (pr-str sw)]

    (testing "a DROPDOWN, one option per open project, addressed by slug"
      (is (some #(and (vector? %) (= :select (first %))) (nodes sw))
          "a <select>, not a run of links — `find-region` addresses
           `:data-region` rather than tags, so the tag is what to look for")
      (is (str/includes? s "\"slopp2\""))
      (is (str/includes? s "\"other\"")))

    (testing "it navigates ON CHANGE, and there is no second control"
      (is (str/includes? s ":change [:project/goto]")
          "choosing a project leaves for it, in one interaction")
      (is (not (str/includes? s ":button")))
      (is (not (str/includes? s ":project/set"))))

    (testing "the project you are looking at is the selected one"
      (is (str/includes? s ":selected true")))

    (testing "every listed project is OPEN — the daemon lists a project exactly
              while something is attached — so nothing is disabled or labelled
              as gone; that state no longer exists to render"
      (is (not (str/includes? s ":disabled")))
      (is (not (str/includes? s "not running"))))))

(deftest the-screens-render-what-the-server-pages-used-to
  ;; These properties were asserted by regexing server-rendered HTML. They
  ;; matter just as much now, and they are cheaper and sharper here: hiccup
  ;; DATA, in-image, no server and no browser. This is the payoff the :cljc
  ;; split was chosen for.
  (letfn [(text  [v] (str/join " " (filter string? (nodes v))))
          (hrefs [v] (vec (for [x (tree-seq coll? seq v)
                                :when (and (vector? x) (= :a (first x)))]
                            (:href (second x)))))]
    (testing "timeline: newest first, each commit-point linking its own range"
      (let [v (views/timeline-main
               {:commit-points [{:commit "c2" :description "the second" :range "c1..c2"}
                             {:commit "c1" :description "the first"}]
                :working {:since "c2" :forms 1 :namespaces ["demo.core"]
                          :prompts ["sharpen hello"]}})
            t (text v)]
        (is (< (.indexOf t "the second") (.indexOf t "the first"))
            "newest first — the reviewer's scan order")
        (is (= ["/change/c1..c2"] (hrefs v))
            "the oldest commit-point has no range, so it links nowhere rather than
             to an empty one")
        (is (re-find #"sharpen hello" t) "the working set shows the recorded asks")))
    (testing "timeline: a clean working set says so instead of showing zero"
      (is (re-find #"clean"
                   (text (views/timeline-main {:commit-points []
                                               :working {:since "c1" :forms 0
                                                         :namespaces [] :prompts []}})))))
    (testing "change: a diff line carries its own status"
      ;; **This assertion used to pin the WRONG SHAPE**, and it is worth saying
      ;; where rather than quietly correcting it. It fed
      ;; `:diff ["-(defn …)" "+(defn …)"]` — strings with a leading marker —
      ;; and asserted the view classified them. The endpoint sends
      ;; `[["del" "(defn …)"] ["add" "(defn …)"]]` and has for some time.
      ;;
      ;; So the test did not merely miss the drift, it HELD IT IN PLACE: it was
      ;; green on data no server produces, which is the failure a fixture that
      ;; brings its own shape can always have. `token-code` four testings below
      ;; has used `[[class text] …]` all along — the two producers converged
      ;; and only this consumer did not follow.
      (let [v (views/change-main
               {:from "c1" :to "c2" :count 1
                :modules [{:module "demo" :count 1
                           :namespaces [{:ns "demo.core" :count 1
                                         :forms [{:form "demo.core/hello"
                                                  :form-id "f1"
                                                  :why "make hello increment"
                                                  :callers 1
                                                  :diff [["del" "(defn hello [x] x)"]
                                                         ["add" "(defn hello [x] (inc x))"]]}]}]}]})
            classes (vec (for [x (tree-seq coll? seq v)
                               :when (and (vector? x) (= :span (first x)))]
                           (:class (second x))))]
        (is (= ["del" "add"] classes)
            "the endpoint classifies; this view only renders what it was told")
        (is (= ["/store/form/f1"] (hrefs v)) "each form links its permalink")
        (is (re-find #"make hello increment" (text v)) "the recorded ask leads")
        (is (re-find #"1 caller" (text v)))))
    (testing "form: tokens become spans, and only classified ones do"
      (let [v (views/token-code [["delim" "("] ["special" "defn"] ["ws" " "]
                                 ["text" "hello"] ["delim" ")"]])
            spans (vec (for [x (tree-seq coll? seq v)
                             :when (and (vector? x) (= :span (first x)))]
                         (:class (second x))))]
        (is (= ["delim" "special" "delim"] spans)
            "whitespace and plain text stay BARE — a span per run would triple
             the markup for no colour")
        ;; the code element's own children, NOT tree-seq: tree-seq yields
        ;; attribute VALUES as well, so "delim" and "special" were being
        ;; counted as rendered text
        (is (= "(defn hello)"
               (apply str (map #(if (string? %) % (last %))
                               (second (second v)))))
            "and the text still concatenates back to the source")))
    (testing "form rail: callees inline their signature and doc"
      ;; the whole reason the rail exists — a link is not visibility
      (let [v (views/form-rail
               {:why "because" :warranty {:covered 3} :note "a floor, not a census"
                :callers [{:via "symbol" :count 1
                           :forms [{:form "demo.b/caller" :form-id "f9" :module "demo.b"}]}]
                :callees [{:form "demo.c/callee" :form-id "f7" :module "demo.c"
                           :sig "[x]" :doc "Adds one."}]})
            t (text v)]
        (is (re-find #"Adds one\." t) "the callee's doc appears on the caller's screen")
        (is (re-find #"\[x\]" t) "and its signature")
        (is (re-find #"3 covering tests" t))
        (is (re-find #"floor, not a census" t) "the honesty note rides along")
        (is (= ["/store/form/f9" "/store/form/f7"] (hrefs v))
            "every edge is an id — a name would break the moment it changes")))
    (testing "module nav: the filter selects by NOT rendering"
      (let [mods [{:module "demo" :namespaces ["demo.core"]
                   :tests 1 :tier "pure" :foundation false}
                  {:module "other" :namespaces ["other.thing"]
                   :tests 0 :tier "pure" :foundation false}]
            rows (fn [needle]
                   ;; .ns-row anchors only. Module HEADS are anchors now too,
                   ;; so "every :a in the tree" stopped meaning "the namespaces
                   ;; the filter chose to render" — which is the whole subject
                   ;; of this assertion.
                   ;; the ANCHOR's text: an ns-row carries a tier mark after its link now,
                   ;; so "the last child" stopped meaning "the name"
                   (vec (for [x (tree-seq coll? seq (views/module-nav mods nil needle))
                              :when (and (vector? x) (= "ns-row" (:class (second x))))]
                          (last (first (filter #(and (vector? %) (= :a (first %)))
                                               (tree-seq coll? seq x)))))))]
        (is (= [] (rows nil))
            "no needle: modules are collapsed, so no namespace links yet")
        (is (= ["demo.core"] (rows "demo"))
            "a needle expands its module and drops the rest from the DOM")
        (is (= [] (rows "zzz")) "and a needle matching nothing renders no rows")
        (is (= "demo" (:value (second (first (filter #(and (vector? %)
                                                           (= :input (first %)))
                                                     (tree-seq coll? seq
                                                               (views/module-nav mods nil "demo")))))))
            "the box carries its value, or a re-render would erase what was typed")))))

(deftest the-rail-names-the-tests-that-cover-the-namespace
  ;; This lived at the BOTTOM of the listing, rendered as one more definition
  ;; so it would read as part of the source. It read as part of the source —
  ;; which is the problem, because it is not one. `tested-by` is something you
  ;; consult BESIDE the code, which is what the rail is for, and putting it
  ;; there also stops it being the thing a reader scrolls past to reach the
  ;; end of a namespace.
  (letfn [;; rendered TEXT, not attribute values — every name asserted here is also
          ;; an href, so a `coll?` walk would pass on the link alone
          (text  [v] (str/join " " (filter string? (nodes v))))
          (hrefs [v] (vec (for [x (tree-seq coll? seq v)
                                :when (and (vector? x) (= :a (first x)))]
                            (:href (second x)))))]
    (testing "covering tests are named and linked — this is where the nav's demoted rows come back"
      (let [v (views/ns-rail
               {:ns "demo.a.core"
                :forms [{:name "hello" :kind "defn" :private? false}]
                :tested-by ["demo.a.core-test" "demo.far-test"]}
               nil)]
        (is (re-find #"demo\.a\.core-test" (text v)))
        (is (re-find #"demo\.far-test" (text v)))
        (is (some #{"/store/ns/demo.a.core-test"} (hrefs v))
            "and each is a link you can follow, not just a name")))
    (testing "a namespace nothing covers SAYS so rather than showing an empty gap"
      (let [v (views/ns-rail
               {:ns "demo.b.util"
                :forms [{:name "helper" :kind "defn" :private? false}]
                :tested-by []}
               nil)]
        (is (re-find #"(?i)no tests" (text v))
            "silence would look identical to a page that just doesn't show coverage")))
    (testing "and it is OUT of the listing, which is only definitions now"
      (let [main (views/ns-outline-main
                  {:ns "demo.a.core"
                   :forms [{:name "hello" :kind "defn" :private? false}]
                   :tested-by ["demo.a.core-test"]}
                  nil)]
        (is (not (re-find #"tested-by|core-test" (text main))))))))

(deftest module-graph-renders-the-picture-as-addressable-svg
  (let [picture {:width 400 :height 300
                 :nodes [{:module "demo.a" :layer 0 :x 10 :y 200 :w 100 :h 50}
                         {:module "demo.b" :layer 1 :x 10 :y 60 :w 100 :h 50}]
                 :band  [{:module "demo.lib" :x 10 :y 260 :w 100 :h 30}]
                 :edges [{:from "demo.b" :to "demo.a" :points [[60 110] [60 200]]
                          :x1 60 :y1 110 :x2 60 :y2 200}]}
        svg   (views/module-graph picture)
        boxes (->> (nodes svg)
                   (filter #(and (vector? %) (map? (second %))
                                 (get-in % [1 :data-module]))))]
    (testing "the canvas is sized by the picture, not by the view"
      (is (= :svg (first svg)))
      (is (= "0 0 400 300" (get-in svg [1 :viewBox]))))
    (testing "every box is an addressable element the client can bind to"
      (is (= #{"demo.a" "demo.b" "demo.lib"}
             (set (map #(get-in % [1 :data-module]) boxes)))))
    (testing "foundation members are marked, so CSS can treat the band differently"
      (let [cls (fn [m] (->> boxes
                             (filter #(= m (get-in % [1 :data-module])))
                             first (#(get-in % [1 :class]))))]
        (is (re-find #"foundation" (str (cls "demo.lib"))))
        (is (not (re-find #"foundation" (str (cls "demo.a")))))))
    (testing "every module is legible — what is left of its name appears as text,
              with the root they all share named once instead of three times"
      (let [texts (->> (nodes svg)
                       (filter #(and (vector? %) (= :text (first %))))
                       (mapcat rest) (filter string?) set)]
        (is (every? texts ["a" "b" "lib"]))
        (is (contains? texts "demo"))
        (is (not-any? texts ["demo.a" "demo.b" "demo.lib"]))))
    (testing "edges are drawn BEFORE nodes, so arrowheads do not sit on labels"
      (let [kids   (rest (drop-while (complement map?) svg))
            groups (filter vector? (nodes svg))
            idx-of (fn [pred] (count (take-while (complement pred) groups)))]
        (is (seq kids))
        (is (< (idx-of #(= :path (first %)))
               (idx-of #(get-in % [1 :data-module]))))))))

(deftest the-outline-view-renders-from-the-wire-shape
  ;; This is the view the SPA swap and the server render SHARE, so it takes
  ;; the shape that crosses the wire — strings, `:doc` possibly nil — rather
  ;; than the store's shape. If it took symbols, the server would render fine
  ;; and the client would render `nil`s, and only a browser would tell you.
  (let [v (views/ns-outline-main {:ns    "demo.core"
            :forms [{:name "demo.core" :doc "What the namespace is for.\n\n  And why."}
                    {:name "hello" :doc "Says hi." :kind "defn" :sig ["[who]"]}
                    {:name "quiet" :doc nil}]} nil)
        ;; the shared walk: seqs included, attribute maps excluded. The text
        ;; assertion below used to need its own vector-only traversal, because
        ;; a `coll?` walk reported `data-doc="demo.core"` as rendered text.
        tree    (nodes v)
        classed (fn [c] (filter #(and (vector? %) (map? (second %))
                                      (= c (:class (second %))))
                                tree))
        entry  (fn [n] (first (filter #(some #{n} (filter string? (nodes %)))
                                      (classed "src-def"))))
        shape  (fn [e] (mapv #(:class (second %)) (filter vector? (drop 2 e))))]
    (testing "no list elements anywhere: this is a source listing, and a
              bulleted index of prose is what it stopped being"
      (is (not-any? #(and (vector? %) (#{:ul :ol :li} (first %))) tree)))
    (testing "the namespace's own doc is the header, and its NAME is not
              repeated under the heading that already says it"
      (is (some #(= [:h1 "demo.core"] %) tree))
      (is (= 1 (count (filter #(= "demo.core" %) (filter string? tree))))
          "once, in the heading — the row that repeated it is gone")
      ;; the class gained a src-doc-short suffix when the block is collapsed,
      ;; and this fixture's header doc IS collapsed — match by prefix
      (is (re-find #"What the namespace is for"
                   (str (last (first (filter #(and (vector? %) (map? (second %))
                                                   (re-find #"^src-doc src-ns-doc"
                                                            (str (:class (second %)))))
                                             tree)))))))
    (testing "the doc is a BLOCK, and it comes BEFORE the name it describes"
      (is (= ["src-doc" "src-sig" "src-elide-link"] (shape (entry "hello")))
          "comment, then signature, then the elided body — the order source
           itself has. The body is wrapped in a link to the same place the
           name goes, which is why the class here is the link's"))
    (testing "and it is a <pre>, so the docstring's own line breaks survive —
              rendering it as a paragraph is what made it read as prose"
      (is (= 2 (count (filter #(and (vector? %) (= :pre (first %)))
                              (nodes (first (classed "src-defs"))))))
          "one per definition — hello's, and quiet's `(none)`. This used to
           assert the opposite: an undocumented form rendered no block at all,
           which is indistinguishable from a block that failed to render")
      (is (= 1 (count (filter #(and (vector? %) (map? (second %))
                                    (re-find #"^src-doc src-ns-doc"
                                             (str (:class (second %)))))
                              tree)))
          "the namespace's own, once, above the definitions"))
    (testing "the arguments are part of the signature, beside the name"
      (is (= ["src-doc src-doc-none" "src-sig" "src-elide-link"] (shape (entry "quiet")))
          "an undocumented form still leads with a comment block, saying so")
      (is (some #(= "[who]" (last %)) (classed "src-args"))))
    (testing "every form still links its source; the namespace row does not,
              because it is no longer a row"
      ;; two anchors per definition now — the name and the elided body, both
      ;; to the same place. DISTINCT is the claim: a near-miss between them
      ;; would send the two halves of one row to different screens.
      (is (= ["/store/source/demo.core/hello" "/store/source/demo.core/quiet"]
             (vec (distinct (for [x tree :when (and (vector? x) (= :a (first x)))]
                              (:href (second x))))))))))

(deftest module-nav-lists-modules-and-expands-only-where-it-should
  (let [modules [{:module "demo.a" :namespaces ["demo.a.core" "demo.a.util"]
                  :tests 2 :tier "pure" :foundation false}
                 {:module "demo.b" :namespaces ["demo.b.web"]
                  :tests 0 :tier "external" :foundation true}]
        ids     (fn [h] (->> (nodes h)
                             (filter #(and (vector? %) (map? (second %))))
                             (keep #(get-in % [1 :id])) set))
        rows    (fn [h cls] (->> (nodes h)
                                 (filter #(and (vector? %) (map? (second %))
                                               (= cls (get-in % [1 :class]))))))
        texts   (fn [h] (->> (nodes h) (filter string?) set))
        says?   (fn [h re] (boolean (some #(re-find re %) (texts h))))]
    (testing "the hooks the client binds to survive the restructure"
      (let [h (views/module-nav modules nil nil)]
        (is (contains? (ids h) "ns-filter"))
        (is (contains? (ids h) "ns-list"))))
    (testing "collapsed by default: modules are rows, namespaces are not"
      (let [h (views/module-nav modules nil nil)]
        (is (= 2 (count (rows h "module-row"))))
        (is (zero? (count (rows h "ns-row"))))))
    (testing "test namespaces are neither listed nor tallied"
      ;; the tally was a number you cannot navigate by, printed on every row
      ;; of the narrowest pane on the screen. Coverage is a finding about a
      ;; module, and it belongs where findings go, not in a nav.
      (let [h (views/module-nav modules nil nil)]
        (is (not-any? #(re-find #"-test" %) (texts h)))
        (is (not (says? h #"\btests?\b")))))
    (testing "the module holding the current namespace is expanded, and only it"
      (let [h (views/module-nav modules "demo.a.util" nil)]
        (is (= 2 (count (rows h "ns-row"))))
        (is (contains? (texts h) "a.core"))
        (is (not (contains? (texts h) "b.web")))))
    (testing "a filter match expands its module — otherwise search finds nothing behind a collapsed row"
      (let [h (views/module-nav modules nil "web")]
        (is (contains? (texts h) "b.web"))
        (is (not (contains? (texts h) "a.core")))))
    (testing "the filter still matches on the FULL name, not the shortened label"
      ;; the root is hidden, not gone: typing it must still find its rows
      (let [h (views/module-nav modules nil "demo.a")]
        (is (= 2 (count (rows h "ns-row"))))))))

(deftest the-nav-names-its-root-once-and-every-row-is-a-way-in
  (let [modules [{:module "demo.a" :namespaces ["demo.a" "demo.a.util"]
                  :tests 2 :foundation false}
                 {:module "demo.b" :namespaces ["demo.b.web"]
                  :tests 0 :foundation true}]
        h       (views/module-nav modules nil nil)
        attrs   (fn [h k] (->> (nodes h)
                               (filter #(and (vector? %) (map? (second %))))
                               (keep #(get-in % [1 k])) vec))
        texts   (fn [h] (->> (nodes h) (filter string?) set))]
    (testing "the shared root is a heading, not a prefix repeated on every row"
      (is (contains? (texts h) "demo"))
      (is (contains? (texts h) "a"))
      (is (contains? (texts h) "b"))
      (is (not (contains? (texts h) "demo.a"))
          "the root is printed once or it is not printed"))
    (testing "every module row links to the namespace its box links to"
      ;; Nothing binds a click handler to .module-head — expansion happens
      ;; only for the module already holding `current`. So a nav with no
      ;; anchors is a nav you cannot enter from a standing start, which is
      ;; exactly how the Code screen behaved: rows lit on hover, did nothing.
      (is (= ["/store/ns/demo.a" "/store/ns/demo.b.web"] (attrs h :href))))
    (testing "a module with no namespaces is not a link to nowhere"
      (is (empty? (attrs (views/module-nav [{:module "x.y" :namespaces []}
                                            {:module "x.z" :namespaces []}]
                                           nil nil)
                         :href))))
    (testing "no shared root means no heading, and full names stay"
      (let [h (views/module-nav [{:module "foo.a" :namespaces ["foo.a"]}
                                 {:module "bar.b" :namespaces ["bar.b"]}]
                                nil nil)]
        (is (contains? (texts h) "foo.a"))
        (is (contains? (texts h) "bar.b"))))))

(deftest the-boxes-drop-the-root-they-all-share-and-name-it-once
  (let [picture {:width 400 :height 200 :edges []
                 :nodes [{:module "demo.hub"   :x 0   :y 0 :w 100 :h 40 :layer 1}
                         {:module "demo.views" :x 120 :y 0 :w 100 :h 40 :layer 1}]
                 :band  [{:module "demo.base"  :x 0 :y 100 :w 100 :h 40}]}
        ;; `targets` is {module HREF} now: the caller owns the route scheme,
        ;; because this component draws boxes at two different grains and
        ;; cannot know which rung its boxes stand for.
        h     (views/module-graph picture {"demo.hub" "/store/ns/demo.hub"})
        texts (fn [v] (->> (nodes v) (filter string?) set))
        attrs (fn [v k] (->> (nodes v)
                             (filter #(and (vector? %) (map? (second %))))
                             (keep #(get-in % [1 k])) set))]
    (testing "a box says what is left of its name once the shared root is gone"
      (is (contains? (texts h) "hub"))
      (is (contains? (texts h) "views"))
      (is (not (contains? (texts h) "demo.hub"))))
    (testing "the band shortens too — it is the same picture, not a footnote"
      (is (contains? (texts h) "base")))
    (testing "and the root is named ONCE, on the picture: a diagram gets
              screenshotted away from the nav that would otherwise say it"
      (is (contains? (texts h) "demo")))
    (testing "the FULL name survives wherever it is an ADDRESS rather than a
              label — data-module is what the client selects on and the href is
              a real route, and neither may be shortened"
      (is (contains? (attrs h :data-module) "demo.hub"))
      (is (contains? (attrs h :href) "/store/ns/demo.hub")))
    (testing "nothing shared: nothing stripped, and no plate claiming a root"
      (let [h2 (views/module-graph {:width 400 :height 200 :edges [] :band []
                                    :nodes [{:module "foo.a" :x 0 :y 0 :w 100 :h 40}
                                            {:module "bar.b" :x 120 :y 0 :w 100 :h 40}]})]
        (is (contains? (texts h2) "foo.a"))
        (is (contains? (texts h2) "bar.b"))))))

(deftest the-elided-body-goes-where-the-name-goes
  ;; The skeleton is the biggest target in the row and it stands for the code
  ;; itself, so clicking it should open the code. A reader who has just judged
  ;; a form by its five lines is already pointing at them.
  (let [v (views/ns-outline-main
           {:ns "demo.core"
            :forms [{:name "hello" :kind "defn" :sig ["[]"] :doc "Hi."}]}
           nil)
        anchors (fn [] (filter #(and (vector? %) (= :a (first %)))
                               (nodes v)))
        elide   (fn [] (first (filter #(re-find #"src-elide-link"
                                                (str (:class (second %))))
                                      (anchors))))]
    (testing "it is a link, to exactly where the name goes"
      (is (some? (elide)))
      (is (= "/store/source/demo.core/hello" (:href (second (elide))))))
    (testing "the SAME destination as the name, not a near-miss"
      (is (= 1 (count (distinct (map #(:href (second %)) (anchors)))))))
    (testing "but hidden from assistive tech and OUT of the tab order — it is
              the same destination a second time, and a duplicate stop that
              announces nothing is a trap rather than a convenience. The name
              is the accessible route and it is unchanged"
      (is (= "true" (:aria-hidden (second (elide)))))
      (is (= "-1" (:tabindex (second (elide))))))
    (testing "the lines themselves are still inside it"
      (is (seq (filter #(and (vector? %) (map? (second %))
                             (re-find #"src-elide-line" (str (:class (second %)))))
                       (nodes (elide))))))))

(deftest module-graph-draws-sketch-paths-when-given-them-and-rects-when-not
  (let [picture {:width 400 :height 300
                 :nodes [{:module "demo.a" :layer 0 :x 10 :y 200 :w 100 :h 50}]
                 :band  [] :edges []}
        tags    (fn [v t] (->> (nodes v)
                               (filter #(and (vector? %) (= t (first %))))))]
    (testing "with no sketch data it renders plain rects — this is the JVM path"
      (let [v (views/module-graph picture)]
        (is (= 1 (count (tags v :rect))))
        (is (zero? (count (filter #(= "sketch" (get-in % [1 :class]))
                                  (tags v :path)))))))
    (testing "given sketch paths for a module, it draws those instead of the rect"
      (let [v (views/module-graph
               (assoc picture :sketch {"demo.a" [{:d "M 0 0 L 10 10"}
                                                 {:d "M 1 1 L 11 11"}]}))]
        (is (zero? (count (tags v :rect))) "the rect is replaced, not layered under")
        (is (= ["M 0 0 L 10 10" "M 1 1 L 11 11"]
               (->> (tags v :path)
                    (filter #(= "sketch" (get-in % [1 :class])))
                    (mapv #(get-in % [1 :d])))))))
    (testing "a module with no sketch entry still gets its rect — partial data degrades"
      (let [v (views/module-graph (assoc picture :sketch {"other" [{:d "M 0 0"}]}))]
        (is (= 1 (count (tags v :rect))))))))

(deftest a-routed-edge-is-drawn-through-its-waypoints-not-just-its-ends
  ;; The geometry can route an edge between the boxes and the picture still be
  ;; a swoop, because the path is what the reader sees. One cubic per segment,
  ;; with vertical tangents where they meet, so the joins are smooth and the
  ;; route stays legible as a descent.
  (let [picture {:width 100 :height 100 :nodes [] :band []
                 :edges [{:from "a" :to "b" :points [[10 0] [50 20] [90 40]]}
                         {:from "c" :to "d" :points [[10 0] [90 40]]}]}
        ds      (->> (nodes (views/module-graph picture))
                     (filter #(and (vector? %) (map? (second %))
                                   (= "module-edge" (:class (second %)))))
                     (mapv #(get-in % [1 :d])))]
    (testing "one cubic per segment — a two-hop route is two curves, not one"
      (is (= [2 1] (mapv #(count (re-seq #"C" %)) ds))))
    (testing "every path starts at the source anchor"
      (is (every? #(str/starts-with? % "M 10 0") ds)))
    (testing "and the waypoint is actually on the route"
      (is (str/includes? (first ds) "50 20")))))

(deftest an-undocumented-form-SAYS-so-rather-than-showing-a-gap
  ;; The listing's own rule, applied to itself: an absence stated out loud
  ;; beats an absence you have to notice. A form with no comment used to
  ;; render no block at all, which is indistinguishable from a form whose
  ;; comment failed to render — and on a screen whose whole claim is "this is
  ;; what is in the namespace", silence is the one thing it must not do.
  (let [data {:ns "d" :doc nil
              :forms [{:name "d" :doc nil}
                      {:name "bare"  :kind "defn" :sig ["[]"] :doc nil}
                      {:name "blank" :kind "defn" :sig ["[]"] :doc "   "}
                      {:name "told"  :kind "defn" :sig ["[]"] :doc "Documented."}]}
        blocks (fn [show] (->> (nodes (views/ns-outline-main data show))
                               (filter #(and (vector? %) (= :pre (first %))))))
        by-cls (fn [show c] (filter #(re-find (re-pattern c) (str (:class (second %))))
                                    (blocks show)))]
    (testing "every form gets a comment block, documented or not"
      (is (= 4 (count (blocks nil))) "three definitions and the namespace"))
    (testing "an undocumented one says (none)"
      (is (= 3 (count (by-cls nil "src-doc-none"))))
      (is (every? #(= "(none)" (last %)) (by-cls nil "src-doc-none"))))
    (testing "whitespace is not a docstring"
      (is (some #(= "(none)" (last %)) (by-cls nil "src-doc-none"))))
    (testing "it is NOT clickable and NOT marked as shortened — there is
              nothing behind it, and either mark would promise something"
      (is (not-any? #(:data-doc (second %)) (by-cls nil "src-doc-none")))
      (is (not-any? #(re-find #"src-doc-short" (str (:class (second %))))
                    (by-cls nil "src-doc-none"))))
    (testing "and expand-all leaves it alone — there is nothing to expand"
      (is (= 3 (count (by-cls {:docs? true} "src-doc-none"))))
      (is (every? #(= "(none)" (last %)) (by-cls {:docs? true} "src-doc-none"))))
    (testing "the documented one is untouched by any of this"
      (is (= 1 (count (remove #(re-find #"src-doc-none" (str (:class (second %))))
                              (blocks nil))))))))

(deftest a-knot-inside-a-module-can-sit-beside-a-boundary-that-is-empty-both-ways
  ;; `slopp.api.model/module-detail`'s VERBATIM return for a store built to hold
  ;; a namespace cycle — sent over rather than described, and not adapted. The
  ;; last two attempts at this shape were an invention and then a retraction on
  ;; false grounds, so this one is a transcription on purpose.
  ;;
  ;; **It reaches a combination `page/responses` cannot.** That fixture keeps
  ;; both boundary halves populated, because an empty `:out` once hid a reversed
  ;; arrow — so the driven module screen can show a cycle OR an empty boundary
  ;; and never both. Here they are both, which is not a contrivance: every edge
  ;; in a knot is internal by definition, so a module whose only structure is a
  ;; cycle tends toward no boundary at all, and this store is that extreme.
  ;;
  ;; The claim: the two boundary sentences stay TRUE directly beneath a finding
  ;; that the module is tangled, and what keeps them true is the SCOPING —
  ;; "outside this module", "outside itself". A panel that said "no external
  ;; dependencies" in the same spot would be equally accurate and would read as
  ;; reassurance under a finding, which is the one word between this screen and
  ;; a page that congratulates a module for being maximally self-contained at
  ;; the same time as maximally entangled. Both facts are true at once.
  ;;
  ;; Whole-output `=` because at twelve lines it IS the unit, and because the
  ;; ADJACENCY of the finding to those sentences is the thing being asserted —
  ;; separate `re-find`s would pass with the two panels anywhere on the page.
  (let [z3 {:module     "z3.core"
            :tier       "external"
            :namespaces [{:ns "z3.core.a" :forms 4 :tier "external"
                          :deps ["z3.core.b"]
                          :gaps {:forms 2 :no-doc 1 :no-why 2 :uncovered 2}}
                         {:ns "z3.core.b" :forms 3 :tier "external"
                          :deps ["z3.core.a"]
                          :gaps {:forms 3 :no-doc 1 :no-why 3 :uncovered 3}}]
            :boundary   {:out [] :in []}
            :layers     [["z3.core.a" "z3.core.b"]]
            :cycles     [["z3.core.a" "z3.core.b"]]}]

    (testing "the finding is stated, and the two empty halves say what is empty
              about them rather than that nothing is wrong"
      (is (= ["code / z3.core"
              "z3.core⚡"
              ;; no `— used by …, depends on …` clause: the census omits the
              ;; boundary sentence when there is no boundary, instead of
              ;; rendering `used by 0 namespaces`
              "2 namespaces, 7 forms"
              "1 dependency cycle inside this module"
              "z3.core.a → z3.core.b → z3.core.a"
              "namespaces"
              ;; `:forms 4` counts the ns form, the `declare`, the top-level
              ;; `require` and the `defn` — the cycle-break machinery is real
              ;; forms, so a reader counting three has not lost one
              "a · 4 forms"
              "b · 3 forms"
              "used by"
              "nothing outside this module names any of its namespaces"
              "depends on"
              "this module depends on nothing outside itself"]
             (cljnx/lines (views/module-main z3) {:detail :prose}))))

    (testing "`:layers` condenses the knot into ONE entry, and marks nobody —
              the same caveat as the store index, one rung down: a layer entry
              cannot say which of its members are entangled, so `:cycles` is
              the only thing that discriminates here too"
      (is (= 1 (count (:layers z3))))
      (is (= (set (first (:layers z3))) (set (first (:cycles z3))))))))

(deftest the-outline-leads-to-what-a-form-IS-now-that-it-can
  ;; The last place source was still the destination. The outline linked every
  ;; name to `/store/source/<ns>/<name>` **because that was the only address it
  ;; could construct** — a reader-API gap, now shipped. So the pane
  ;; whose whole job is to be an alternative to source had source as the only
  ;; thing it could offer, which is the inversion this wave exists to undo.
  (let [data {:ns "demo.core" :tier "pure"
              :forms [{:name "rate" :kind "defn" :form-id "f-rate"
                       :sig ["[kg]"] :doc "Rate for a weight." :mass 40
                       :calls [] :callers-out 3}
                      {:name "legacy" :kind "defn" :sig ["[]"] :mass 5
                       :calls [] :callers-out 0}]}
        page (views/ns-outline-main data)
        hrefs (->> (tree-seq coll? seq page)
                   (filter #(and (map? %) (string? (:href %))))
                   (map :href) set)]
    (testing "the fixture has one row WITH a form-id and one without, or neither
              branch below is exercised"
      (is (some :form-id (:forms data)))
      (is (some (complement :form-id) (:forms data))))

    (testing "a row that can reach the form page does"
      (is (contains? hrefs "/store/form/f-rate"))
      (is (not (contains? hrefs "/store/source/demo.core/rate"))
          "source is the escape hatch, not the listing's destination"))

    (testing "a row with no form-id still reaches SOURCE rather than nothing —
              a name that is not a link is worse than one that goes somewhere
              plainer"
      (is (contains? hrefs "/store/source/demo.core/legacy")))

    (testing "the elided body links where its own name links, as it always has"
      (let [all (->> (tree-seq coll? seq page)
                     (filter #(and (map? %) (string? (:href %))))
                     (map :href))]
        ;; PER ROW: the name and the skeleton beneath it are two links to one
        ;; destination. Counting the whole page would have been 4 here and is
        ;; 2n in general, which says nothing about the pairing it claims.
        (is (= 2 (count (filter #{"/store/form/f-rate"} all))))
        (is (= 2 (count (filter #{"/store/source/demo.core/legacy"} all))))))))

(deftest reading-show-state-was-cljc-and-writing-it-was-not
  ;; The asymmetry slopp asked me to check for, and it is here. Every READ of
  ;; the `:show` map is `:cljc` and tested — `doc-open?`, `option-for`,
  ;; `shown-defs`. Every WRITE was inline in `client.app`'s document listener:
  ;;
  ;;   (swap! state update :show
  ;;          #(-> % (assoc :docs? (= "expand" (.getAttribute btn "data-docs")))
  ;;               (dissoc :doc)))
  ;;
  ;; So the rule that decides what a reader SEES is checkable and the rule that
  ;; decides what a click DOES is not — and the two have to agree or a toggle
  ;; lies. That is slopp's shipped line about logic drifting into `.cljs`,
  ;; arriving from the direction of "why can a headless driver not click".
  (testing "the all-switch sets the default AND drops every per-comment override
            — an `expand all` that left one comment shut would not be expand all"
    (let [show {:docs? false :doc {"a" true "b" false}}]
      (is (= {:docs? true} (views/toggle-all-docs show true)))
      (is (= {:docs? false} (views/toggle-all-docs show false)))))

  (testing "one comment's toggle is an override ON TOP of whatever the switch says,
            so it has to read through the same fn the renderer reads through"
    (let [show {:docs? false}]
      ;; closed by default → the toggle opens it
      (is (true? (views/doc-open? (:show {:show (views/toggle-doc show "a")}) "a"))
          "toggling a closed comment opens it")
      ;; and open by default → the toggle shuts it
      (let [open {:docs? true}]
        (is (false? (views/doc-open? (views/toggle-doc open "a") "a"))
            "toggling an open comment shuts it"))))

  (testing "a display toggle writes the key its OPTION names, not the DOM's spelling"
    (let [opt (first views/display-options)]
      (is (some? opt) "no display options to test with")
      (is (true? (get (views/set-display-option {} (:key opt) true) (:key opt))))
      (is (false? (get (views/set-display-option {} (:key opt) false) (:key opt))))))

  (testing "and every transition returns the SHOW map, not the whole app state —
            the browser owns the atom, these own the rule"
    (is (map? (views/toggle-all-docs {} true)))
    (is (map? (views/toggle-doc {} "x")))
    (is (map? (views/set-display-option {} :private? true)))))

(deftest a-search-hit-says-what-it-is-and-why-it-is-in-the-list
  ;; The fixture is the shape the endpoint SENDS, not the shape it was asked
  ;; for: measured on slopp's listener at 127.0.0.1:53610, where
  ;; `slopp.index.refs/refs` came back with a qualified `:name`, an `:ns`, a
  ;; `:form-id` and NO `:address`. Building against a proposal is what let this
  ;; screen exist before the endpoint did; re-deriving it from the wire is the
  ;; other half of that bargain.
  (let [text (fn [v] (str/join " " (filter string? (nodes v))))
        hits [{:kind "form" :name "demo.core/rate"
               :ns "demo.core" :module "demo.core" :form-id "f1"
               :sig ["[kg zone]"]
               :doc "Rate for a weight in a zone. Bands come from the tariff table."
               :matched "name" :rank 1.0}
              {:kind "namespace" :name "demo.core.calc" :module "demo.core"
               :why "the order path was guessing at prices"
               :matched "why" :rank 0.4}
              {:kind "form" :name "demo.web/coerce"
               :ns "demo.web" :module "demo.web" :form-id "f7"
               :matched "source" :rank 0.2}]
        resp {:query "rate" :total 340
              :totals {:modules 2 :namespaces 11 :forms 327}
              :hits hits}
        v    (views/search-main resp)]

    (testing "a row links to the address this app BUILDS from the parts it was
              sent — and `a-hits-address-is-BUILT-here-and-round-trips-through-the-router`
              is what stops that builder drifting from the router"
      (is (= ["/store/form/f1" "/store/ns/demo.core.calc" "/store/form/f7"]
             (->> (nodes v) (filter map?) (keep :href) (remove #{"/store"})))))

    (testing "the order on screen is the order the store ranked — relevance
              needs the whole corpus, which this UI is not allowed to open, so
              re-sorting here would be a second opinion nobody asked for"
      (is (= ["rate" "demo.core.calc" "coerce"]
             (->> (nodes v) (filter vector?)
                  (filter #(= :a (first %)))
                  (keep #(first (filter string? (nodes %))))
                  (remove #{"code"})))
          "and a form shows its BARE name — the namespace is already the
           context beside it, and printing it twice is a wider row saying less"))

    (testing "the summary counts what was returned AND what was not — a limited
              hit list cannot derive either number from itself"
      (is (re-find #"3 of 340" (text v)))
      (is (re-find #"2 modules" (text v)))
      (is (re-find #"11 namespaces" (text v)))
      (is (re-find #"327 forms" (text v))))

    (testing "every row says why it is in the list. A hit whose name does not
              contain the query reads as a bug unless the row can say where the
              words actually were"
      (is (re-find #"(?i)matched in the name" (text v)))
      (is (re-find #"(?i)matched in the recorded why" (text v))))

    (testing "and the source-only hit is a FINDING, called out above the list
              rather than left to be spotted in it: on this UI's own thesis,
              words that appear only in the code are a gap in the store's
              names, docstrings and whys"
      (is (some #(and (vector? %) (= :div (first %))
                      (str/includes? (str (:class (second %))) "finding"))
                (nodes v))
          "a source-only match must be surfaced as a finding callout")
      (is (re-find #"(?i)only in the source" (text v))))))

(deftest the-rail-ranks-and-caps-its-neighbours-and-says-what-it-held-back
  ;; `callgraph/ego` was built, tested and wired to nothing for two days while
  ;; the rail rendered the wire list raw — the first code-navigation gap.
  ;; Its own docstring is the argument against what shipped: past about six
  ;; neighbours an unranked list ranks nothing.
  (let [caller (fn [i] {:form (str "demo.order/c" i) :form-id (str "fc" i)
                        :ns "demo.order" :module "demo.order"
                        :sig ["[x]"] :doc "A caller." :calls 1})
        data   {:form-id "f1" :name "demo.core/rate" :ns "demo.core"
                :module "demo.core" :warranty {:covered 2}
                :callers [{:via "static" :count 9
                           :forms (mapv caller (range 9))}]
                :callees [{:form "demo.core/band" :form-id "f2" :ns "demo.core"
                           :module "demo.core" :via "static" :calls 1
                           :sig ["[kg]"] :doc "Band for a weight."}]
                :note "edges are a syntactic floor, not a census"}
        v      (views/form-rail data)
        text   (str/join " " (filter string? (nodes v)))
        hrefs  (->> (nodes v) (filter map?) (keep :href))]

    (testing "nine callers do not become nine rows — the cap is six"
      (is (= 6 (count (filter #(str/starts-with? % "/store/form/fc") hrefs)))
          "ranked and capped, not truncated by whatever order the wire used"))

    (testing "and the three it held back are STATED. A list that truncates in
              silence lets a reader conclude a function has no other callers,
              which is the specific way a comprehension tool produces a
              confident false belief"
      (is (re-find #"3 more" text)))

    (testing "the callee keeps its signature and doc INLINED — a link is not
              visibility, and reading the form WITH what it reaches is the
              whole reason the rail exists"
      (is (re-find #"\[kg\]" text))
      (is (re-find #"Band for a weight" text)))

    (testing "a caller now carries its card too. The wire's asymmetry was real
              and is FIXED (reader-api-gaps #4, slopp d23203) — measured on the
              live listener, both sides come back with sig, doc, why, warranty"
      (is (re-find #"\[x\]" text)
          "callers and callees get equal weight, which is what neighbours/ego
           exist to make possible"))

    (testing "a form nothing calls still says so rather than rendering silence"
      (let [bare (views/form-rail (assoc data :callers [] :callees []))
            t    (str/join " " (filter string? (nodes bare)))]
        (is (re-find #"nothing in this store calls it" t))
        (is (re-find #"it calls nothing else in this store" t))))))

(deftest the-table-s-classes-are-derived-from-what-it-renders-not-listed-by-hand
  ;; Same pairing as `every-registry-entry-can-actually-reach-the-screen`, one
  ;; view further on: `module-table` writes a class name and `table-styles`
  ;; writes a selector, and nothing but the spelling joins them.
  ;;
  ;; The class set is READ OFF THE RENDERED TABLE rather than written out here.
  ;; A hand-listed pair is two literals agreeing with each other while both
  ;; disagree with the view — which is the failure one level up from the one
  ;; being checked.
  (let [sels    (set (filter keyword? (tree-seq coll? seq styles/table-styles)))
        classes (->> (views/module-table
                      {:modules [{:module "demo.base" :namespaces [] :tests 0
                                  :tier "pure" :foundation true :deps []
                                  :gaps {:forms 1}}
                                 {:module "demo.core" :namespaces ["demo.core"]
                                  :tests 1 :tier "pure" :foundation false
                                  :deps ["demo.base"] :gaps {:forms 2}}]
                       :layers  [["demo.core"]]})
                     (tree-seq coll? seq)
                     (filter map?)
                     (keep :class)
                     set)]
    (testing "the table renders exactly these classes — a new one arriving with
              no rule reddens here rather than shipping unstyled"
      (is (= #{"data-table" "foundation" "num"} classes)))
    (testing "and each has a rule. Exact keywords, not `includes?`: a prefix is
              not a match, and this suite has already passed once with a rule
              broken because a substring still read as present"
      (is (contains? sels :.data-table))
      (is (contains? sels :tr.foundation)))
    (testing "the finder can miss — otherwise the two assertions above prove
              only that `contains?` can return true"
      (is (not (contains? sels :.module-invented))))))

(deftest the-api-screen-s-classes-are-derived-from-what-it-renders
  ;; Same pairing as the table's guard next door: a class name is a literal in
  ;; `views` and a selector in `styles`, joined by nothing but the spelling.
  ;; Read off the RENDERED screens, because a hand-listed pair is two literals
  ;; agreeing with each other while both disagree with the view.
  ;;
  ;; All THREE views of the section, because the split put classes in places
  ;; the single screen never had them — a guard that kept reading only the one
  ;; it was written for is the enumeration failure this file already has an
  ;; entry about.
  ;;
  ;; And the attribute is SPLIT on whitespace. `class` legitimately holds
  ;; several names, and the first version of this compared the whole string:
  ;; it read `method method-get` as one class and would have reported it as
  ;; missing a rule for as long as it existed.
  (let [;; one field carries PROSE, because `.fdoc` only exists once a schema
        ;; declares some. A derivation run over a document with no docs reports
        ;; on a screen half of which never rendered — the enumeration failure
        ;; this test is named for, arriving through the fixture rather than
        ;; through the list.
        doc     [{:method :get :path "/api/x/:id" :name 'x
                  ;; an endpoint-level `:doc` as well as a field one:
                  ;; `.endpoint-doc` exists only when a document carries prose
                  ;; about the endpoint ITSELF, which is a different key from
                  ;; the field's and would have gone unstyled behind the same
                  ;; blind spot
                  :doc "What it does. And a second sentence."
                  ;; declares an EFFECT, because `.effectful` exists only on an
                  ;; endpoint that says it changes something. Third class in
                  ;; this fixture that a derivation could only see once the
                  ;; state that produces it was actually rendered.
                  :effectful? true
                  :request [:map [:q {:optional true :doc "what to look for"} :string]]
                  :response [:map [:a [:map [:b :int]]]]}
                 {:method :post :path "/api/y" :name 'y
                  :request nil :response [:map [:c :string]]}]
        sels    (set (filter keyword? (tree-seq coll? seq styles/endpoint-styles)))
        classes (->> [(views/endpoints-main doc)
                      ;; rendered WITH a result, because the answer's classes only exist once
                      ;; there is an answer — a derivation that never triggers the
                      ;; state is a derivation that reports on half the screen
                      (views/endpoint-main doc (views/endpoint-params (first doc))
                                           {:params {"id" "f1"} :status :failed
                                            :request {:method :get} :error "nope"})
                      (views/endpoint-nav doc
                                          (views/endpoint-address (first doc)))]
                     (mapcat #(tree-seq coll? seq %))
                     (filter map?)
                     (keep :class)
                     (mapcat #(str/split % #"\s+"))
                     (remove str/blank?)
                     set)]
    (testing "the section renders exactly these classes — a new one arriving
              with no rule reddens here rather than shipping unstyled"
      (is (= #{"endpoint" "schema-fields" "field" "ftype" "fdoc" "endpoint-doc"
               "method" "method-get" "method-post" "effectful"
               "endpoint-row" "endpoint-path" "active" "data-table"
               "try-panel" "try-fields" "try-field" "try-result" "try-error"
               ;; the request/response TAB BAR. These were `lens-bar`'s classes
               ;; for one commit, on the argument that it is the same
               ;; interaction — which it is, and which turned out to be the
               ;; wrong axis: `lens-bar` is styled for its own region at the top
               ;; of a page, so inline in prose it rendered as two words with no
               ;; affordance at all. Semantics and appearance are separate
               ;; questions and sharing a class answers both at once.
               ;;
               ;; `schema-part` is the panel under them, which lost the `<h3>`
               ;; that used to name it — the tab says `request` and an `<h3>`
               ;; saying `request` underneath it said the word twice.
               "endpoint-tabs" "tab-current" "schema-part"}
             classes)))
    (testing "and each has a rule in the SERVED stylesheet — not in one style
              def, because `.active` is declared in the stylesheet form itself
              and a per-def check would have to know which def owns what"
      ;; the lookahead is load-bearing. A plain substring finds `.method`
      ;; inside `.method-get`, so a class with no rule of its own would pass
      ;; on a longer sibling's — the same prefix-is-not-a-match failure this
      ;; file already caught once, arriving through the text side instead of
      ;; the keyword side.
      (let [css styles/stylesheet]
        (doseq [c classes]
          (is (re-find (re-pattern (str "\\." c "(?![-\\w])")) css)
              (str "no rule for ." c)))
        (is (not (re-find #"\.endpoint-invented(?![-\w])" css))
            "the finder must be able to miss")))
    (testing "the finder can miss"
      (is (not (contains? sels :.endpoint-invented))))))

(deftest a-schema-with-no-fields-renders-no-list-at-all
  ;; Found by LOOKING: driving `/endpoints` showed `<ul slopp:count="0"/>` under
  ;; the register endpoint's response, where `[:or …]` yields no rows. No
  ;; assertion I had written was going to catch an empty element — they all ask
  ;; what IS there.
  ;;
  ;; Same rule `app-shell` already makes for an omitted pane: an empty
  ;; container takes layout and says nothing. Here it says slightly worse than
  ;; nothing, because a bordered empty box under `response` reads as an answer
  ;; that came back empty rather than a shape this screen cannot flatten.
  (testing "no fields, no list"
    (is (nil? (views/schema-fields []))))
  (testing "fields, a list"
    (is (vector? (views/schema-fields
                  (schema/nest (schema/rows [:map [:a :string]]))))))
  (testing "and the screen carries none for a schema that genuinely has no
            fields, whose type line is the whole of what is known"
    ;; **`[:or …]` used to be the illustration here and no longer is** — it
    ;; opens into numbered branches now, which was the point of fixing it.
    ;; `[:sequential :string]` is the honest replacement: a list of scalars has
    ;; nothing to open, and `list of string` really is everything known.
    ;;
    ;; Worth the note rather than a silent swap: an example going stale because
    ;; the behaviour it illustrated improved is the good version of a test
    ;; changing, and telling it apart from the bad version is the whole reason
    ;; this file writes down why.
    (let [ep {:method :post :path "/api/register" :name 'register!
                :request nil
                :response [:sequential :string]}
          v  (views/endpoint-main [ep] (views/endpoint-params ep) nil)]
      (is (not-any? #(and (vector? %) (= :ul (first %))) (nodes v)))
      (is (some #(= "list of string" %) (filter string? (nodes v)))))))

(deftest a-diff-line-arrives-as-a-PAIR-and-carries-its-own-status
  ;; Measured on slopp2's live wire 2026-08-08. `:diff` is
  ;;
  ;;   [["same" "(ns slopp.cljnx-test"] ["add" "  :new"] ["del" "  :old"]]
  ;;
  ;; and this view read it as strings with a leading `-`/`+`. The failure is
  ;; SILENT in both directions, which is why it lived:
  ;;
  ;;   {:class (cond (str/starts-with? line "-") "del" …)}
  ;;
  ;; `clojure.string/starts-with?` calls `.toString` on its argument, so a
  ;; VECTOR does not throw — it stringifies to `["same" "(ns …"]`, starts with
  ;; `[`, and every line got `:class nil`. So the diff has been colourless.
  ;; And the pair itself sat in the tree where a string was expected, which
  ;; hiccup reads as an ELEMENT: `<same>(ns demo)</same>` in a browser.
  ;;
  ;; Neither is visible in the readout — text renders either way — and the
  ;; screen has no fixture, so nothing has ever driven it. That pairing — an
  ;; undriven screen hiding a live defect — is a recurring finding, and this
  ;; is the second instance it hid.
  (let [v (views/change-main
           {:from "a" :to "b" :count 1
            :modules [{:module "m" :count 1
                       :namespaces [{:ns "n" :count 1
                                     :forms [{:form "n/x" :form-id "f1" :callers 0
                                              :diff [["same" "(ns demo)"]
                                                     ["add" "  :new"]
                                                     ["del" "  :old"]]}]}]}]})
        spans (filter #(and (vector? %) (= :span (first %))) (nodes v))]

    (testing "a status becomes the line's CLASS — the whole point of shipping
              the diff classified rather than as raw text"
      (is (= [nil "add" "del"] (map #(:class (second %)) spans))))

    (testing "and the TEXT is the line, with nothing invented around it. The
              pair's first element is a status, not an element name"
      ;; the marker prefix is the neighbouring test's fact, not this one's — here
      ;; the claim is only that the pair's TEXT half is what gets rendered, and
      ;; that nothing is invented around it
      (is (= ["(ns demo)" "  :new" "  :old"]
             (map #(str/replace (nth % 2) #"^[ +-] " "") spans)))
      (is (not-any? #(and (vector? %) (string? (first %))) (nodes v))
          "no node has a string in tag position — that is a pair read as markup"))

    (testing "an unknown status renders the line without a class rather than
              dropping it. A diff that omits a line it cannot colour is worse
              than one that shows it plainly"
      (let [v2 (views/change-main
                {:from "a" :to "b" :count 1
                 :modules [{:module "m" :count 1
                            :namespaces [{:ns "n" :count 1
                                          :forms [{:form "n/x" :form-id "f1" :callers 0
                                                   :diff [["moved" "  :x"]]}]}]}]})]
        (is (= 1 (count (filter #(and (vector? %) (= :span (first %))) (nodes v2)))))
        (is (re-find #":x" (str/join " " (filter string? (nodes v2)))))))))

(deftest the-review-panes-degrade-honestly-on-an-empty-answer
  ;; An endpoint that answers with nothing is a real state. Every other main
  ;; pane already treats it as one — `module-main` falls back to the index with
  ;; a pending sentence, `search-main` renders four states and says which it is
  ;; in. These two were never given that treatment because nothing ever handed
  ;; them a nil: they are the two screens with no canned response, so no driver
  ;; and no test ever reached them without bringing data.
  ;;
  ;; `timeline-main` NPE'd — `(zero? (:forms working))` on a nil `working` — a
  ;; WHITE PAGE in a browser. `change-main` did not throw, which is worse in
  ;; the way this project keeps finding: it rendered `..` as a heading and
  ;; ` forms` with no number, the same false-sentence class as `0 forms` and
  ;; `5 of thems foundation`, arriving from the nil direction instead of the
  ;; wrong-key one.
  (let [text (fn [v] (str/join " " (filter string? (nodes v))))]

    (testing "timeline says there is nothing rather than throwing"
      (let [t (text (views/timeline-main nil))]
        (is (re-find #"review" t) "it is still the Review screen")
        (is (re-find #"nothing to show" t))))

    (testing "and an answer with commit-points but no working set is not the same
              thing as no answer — the endpoint can legitimately send one"
      (let [t (text (views/timeline-main {:commit-points [{:commit "c1" :description "first"}]}))]
        (is (re-find #"first" t))))

    (testing "change says which range it could not load, because the RANGE came
              from the url and is the one fact still known when the answer is
              empty"
      (let [t (text (views/change-main nil))]
        (is (re-find #"nothing to show" t))
        (is (not (re-find #"\.\." t)) "no heading made of two dots")
        (is (not (re-find #"(?<!\d )forms" t)) "no count sentence with no count")))))

(deftest a-diff-line-carries-its-marker-in-the-TEXT-not-only-in-a-colour
  ;; Found by looking at the change screen in the first minute it was drivable.
  ;; The classes were right and the screen read:
  ;;
  ;;   (defn rate
  ;;     [kg]
  ;;     [kg zone]
  ;;     (band-for kg))
  ;;
  ;; The removed line and the added line are INDISTINGUISHABLE. `.del` and
  ;; `.add` are a background colour, and a reader without the stylesheet — this
  ;; tool, and anything else reading text — sees a diff with no diff in it.
  ;;
  ;; Same rule as `rate[kg zone]` and `web⚡1 ns`: a distinction carried only by
  ;; CSS is not carried. Third instance, first one where the missing character
  ;; is a whole convention rather than a space.
  ;;
  ;; The marker was in the text once — the endpoint used to send `-(defn …)` —
  ;; and moving the classification onto a `:status` key dropped it. Nobody
  ;; added it back on this side, because nobody could see this screen.
  (let [v (views/change-main
           {:from "a" :to "b" :count 1
            :modules [{:module "m" :count 1
                       :namespaces [{:ns "n" :count 1
                                     :forms [{:form "n/x" :form-id "f1" :callers 0
                                              :diff [["same" "(defn rate"]
                                                     ["del" "  [kg]"]
                                                     ["add" "  [kg zone]"]]}]}]}]})
        lines (->> (nodes v)
                   (filter #(and (vector? %) (= :span (first %))))
                   (map #(nth % 2)))]

    (testing "every line is prefixed the way every diff tool prefixes them, so
              the three kinds are three different strings"
      (is (= ["  (defn rate" "-   [kg]" "+   [kg zone]"] lines)))

    (testing "and a context line is padded to the same width, so the code still
              lines up under the markers rather than shifting by one"
      (is (every? #(= 1 (count (re-find #"^[ +-]" %))) lines)))

    (testing "the class is still there — the marker is a SECOND channel, not a
              replacement. A reader with the stylesheet keeps the colour"
      (is (= [nil "del" "add"]
             (->> (nodes v)
                  (filter #(and (vector? %) (= :span (first %))))
                  (map #(:class (second %)))))))))

(deftest an-endpoint-links-its-handler-s-BODY-or-says-it-cannot
  ;; The link goes to the FUNCTION, not to its namespace and not to a list of
  ;; candidates. `/store/source/:ns/:name` renders that one form's body under
  ;; its own name, and breadcrumbs out to the namespace for a reader who wants
  ;; wider — measured against the live endpoint, which returns the 19-line
  ;; `(defn …)` and nothing around it.
  ;;
  ;; **There is no search fallback any more.** There was one, from before
  ;; `:handler` was published, and it was wrong in a way worth recording: a
  ;; list of places the function MIGHT be is not a link to the function. It
  ;; put a control on the page that looked like the answer and produced a
  ;; ranking, and the reader had to do the resolving the screen could not.
  ;;
  ;; A document with no `:handler` now gets a SENTENCE. That is a fact about
  ;; the project — its contract does not publish where its handlers live — and
  ;; it is actionable in a way a search box is not.
  (let [page (fn [ep] (views/endpoint-main [ep] (views/endpoint-params ep) nil))
        text (fn [v] (str/join " " (filter string? (nodes v))))
        href (fn [v h] (some #(and (vector? %) (= :a (first %))
                                   (= h (:href (second %))))
                             (nodes v)))]

    (testing "with a published handler the page links that form's BODY"
      (let [v (page {:method :get :path "/api/modules" :name 'modules
                     :handler 'slopp.api.endpoints/modules
                     :request nil :response :string})]
        (is (href v "/store/source/slopp.api.endpoints/modules"))
        (is (re-find #"read its source" (text v)))
        (is (re-find #"slopp\.api\.endpoints/modules" (text v))
            "and NAMES it, so a reader knows where the link goes")))

    (testing "without one there is NO link — not a search, not a namespace, not
              a guess. The screen says the document did not publish it"
      (let [v (page {:method :get :path "/api/modules" :name 'modules
                     :request nil :response :string})]
        (is (not-any? #(and (vector? %) (= :a (first %))
                            (str/starts-with? (str (:href (second %))) "/store"))
                      (nodes v))
            "no link into the store at all")
        (is (re-find #"does not publish" (text v)))
        (is (not (re-find #"search" (text v))))))

    (testing "an unqualified handler is the same case as none — it cannot
              address a form, and half an address is not a link"
      (let [v (page {:method :get :path "/api/modules" :name 'modules
                     :handler 'modules :request nil :response :string})]
        (is (re-find #"does not publish" (text v)))))))

(deftest an-endpoint-s-own-prose-lands-where-each-screen-has-room-for-it
  ;; `:doc` per endpoint arrived on the wire 2026-08-09, carrying the handler's
  ;; existing docstring rather than a second `:web/summary` beside it — slopp's
  ;; counter-proposal to my ask, and better than the ask: a second prose field
  ;; is a fact with two homes that can disagree.
  ;;
  ;; I asked for it WHOLE, on the grounds that a document shipping only a first
  ;; line cannot be un-truncated by a consumer that wants the rest. This is my
  ;; half of that bargain: the index takes the first sentence, the page renders
  ;; all of it.
  ;;
  ;; Both screens take ROWS rather than the document — the route row's
  ;; `:derive :paths` strips the envelope, so nothing here knows the version
  ;; key or the rows key. Both moved when `/api/contracts` became
  ;; `/api/rest/paths` and no row changed.
  (let [ep   {:method :get :path "/api/modules" :name 'modules
              :doc "Every module, its namespaces and its gaps. The layers are a TOPOLOGICAL order, not an alphabetical one."
              :request nil :response [:map [:a :string]]}
        text (fn [v] (str/join " " (filter string? (nodes v))))]

    (testing "the INDEX takes the first sentence — one line per endpoint is the
              whole editorial rule of that page, and a docstring is paragraphs"
      (let [t (text (views/endpoints-main [ep]))]
        (is (re-find #"Every module, its namespaces and its gaps\." t))
        (is (not (re-find #"TOPOLOGICAL" t))
            "the rest belongs on the endpoint's own page")))

    (testing "the PAGE renders all of it, which is what asking for it whole was
              for — the second sentence is the one carrying the fact a reader
              cannot guess"
      (let [t (text (views/endpoint-main [ep] (views/endpoint-params ep) nil))]
        (is (re-find #"TOPOLOGICAL" t))))

    (testing "an endpoint with no prose renders none, on both screens. Every
              project on an older jar publishes a document without any"
      (let [bare {:method :get :path "/api/x" :name 'x :request nil :response :string}
            v    (views/endpoint-main [bare] (views/endpoint-params bare) nil)]
        (is (some? (views/endpoints-main [bare])))
        ;; `some?` alone was true of the NOT-FOUND page too, so this said
        ;; nothing about prose once the address changed shape — it passed for
        ;; the wrong reason for exactly one edit.
        (is (re-find #"GET /api/x" (text v))
            "the endpoint is rendered at all")
        (is (not-any? #(and (vector? %) (map? (second %))
                            (= "endpoint-doc" (:class (second %))))
                      (nodes v))
            "and carries no prose paragraph")))

    (testing "a one-sentence doc is not truncated into nothing, and a doc with
              no full stop still yields a first line rather than the empty
              string — a summary column that blanks on an unpunctuated
              docstring is worse than one that shows too much"
      (is (re-find #"just this"
                   (text (views/endpoints-main [(assoc ep :doc "just this.")]))))
      (is (re-find #"no full stop"
                   (text (views/endpoints-main [(assoc ep :doc "no full stop here")])))))))

(deftest the-body-page-widens-both-ways-when-the-store-says-who-this-form-is
  ;; The drill-down lands on one form's body. From there UP to the namespace
  ;; was always there — the breadcrumb — and SIDEWAYS was the missing hop:
  ;; `/store/form/:id` is this app's richer page, with callers, callees, the
  ;; warranty and the spine.
  ;;
  ;; `:form-id` arrived on `/api/source/:ns/:name` on 2026-08-09, and WHERE it
  ;; arrived is the point. It is a store identity, so it does not belong in the
  ;; published contract document — that document has to work for a producer
  ;; running from a jar with no store at all. `/api/source` is already
  ;; store-only: it answers with stored source and pretends to be nothing else.
  (let [text (fn [v] (str/join " " (filter string? (nodes v))))
        href (fn [v h] (some #(and (vector? %) (= :a (first %))
                                   (= h (:href (second %))))
                             (nodes v)))]

    (testing "with a form id the page offers the form's own page"
      (let [v (views/source-main {:ns "demo.core" :name "rate" :form-id "f1"
                                  :source "(defn rate [kg] kg)"})]
        (is (href v "/store/form/f1"))
        (is (re-find #"callers" (text v))
            "and says what is over there, rather than a bare `more`")))

    (testing "and UP is still there and still first — the namespace is the
              wider view a reader reaches for most, and it needs no store id"
      (let [v (views/source-main {:ns "demo.core" :name "rate" :form-id "f1"
                                  :source "()"})]
        (is (href v "/store/ns/demo.core"))))

    (testing "without one the page is unchanged — no link, no gap where a link
              would be. Every project on an older jar answers without it, and
              this is the third control on this app that has to render both
              a document that says where something is and one that does not"
      (let [v (views/source-main {:ns "demo.core" :name "rate" :source "()"})]
        (is (not (href v "/store/form/")))
        (is (not-any? #(and (vector? %) (= :a (first %))
                            (str/starts-with? (str (:href (second %)))
                                              "/store/form"))
                      (nodes v)))
        (is (not (re-find #"callers" (text v))))))))

(deftest an-effectful-endpoint-says-so-before-you-can-fire-it
  ;; **This test used to assert a world that does not exist**, and it passed
  ;; for thirteen days by supplying that world itself. Both of its premises
  ;; were wrong:
  ;;
  ;; 1. *"`:effectful?` arrived on the wire with `:handler` and `:doc`"* — it
  ;;    did not. `:auth` shipped 2026-08-11 and `:effectful?` was the ask
  ;;    beside it that did not, which `schema-test` had already written down.
  ;;    Measured on the hub's own document, the only one in reach with POST
  ;;    endpoints: nine keys, and `:effectful?` is not one of them.
  ;;
  ;; 2. *"A method is a poor proxy: slopp's surface is all GET, and a GET can
  ;;    be effectful."* True of HTTP, false of the only endpoints this screen
  ;;    ever renders. slopp's safe-method gate REFUSES a GET that reaches a
  ;;    mutation — `hub/forward`'s docstring turns on exactly that, declaring
  ;;    `^:reads` rather than a bang so the gate lets it be a GET. An effectful
  ;;    GET is not a shape a slopp store can serve.
  ;;
  ;; So the fixture fed `:effectful? true` to a `:get` endpoint — a combination
  ;; the framework forbids — and every assertion here was about that impossible
  ;; value. Meanwhile the shipped screens read the same key on real documents,
  ;; got nil, and the two-press arming gate before a mutation never engaged.
  ;;
  ;; It is now derived from `:method`, which IS published. See
  ;; `slopp.ui.schema/effectful?` for why that direction is the safe one and
  ;; where its default is weak.
  (let [text (fn [v] (str/join " " (filter string? (nodes v))))
        ep   (fn [method] {:method method :path "/api/x" :name 'x
                           :request nil :response :string})]

    (testing "a mutation is marked on its own page"
      (is (re-find #"changes something"
                   (text (views/endpoint-main [(ep :post)]
                                              (views/endpoint-params (ep :post)) nil)))))

    (testing "and the call form warns where the button is, which is the only
              place the warning can still change a decision"
      (is (re-find #"changes something" (text (views/try-panel (ep :post) nil)))))

    (testing "every mutating method counts, not just POST — a DELETE is the one
              where firing on the first press is least recoverable"
      (doseq [m [:post :put :patch :delete]]
        (is (re-find #"changes something" (text (views/try-panel (ep m) nil)))
            (str m " was treated as read-only"))))

    (testing "a GET says NOTHING — a badge on every row is a badge nobody
              reads, and `read-only` on eight of nine endpoints would make the
              ninth's absence the signal"
      (let [v (views/endpoint-main [(ep :get)]
                                   (views/endpoint-params (ep :get)) nil)]
        ;; the endpoint must RENDER for the two negatives below to mean
        ;; anything — on a not-found page both are trivially true
        (is (re-find #"GET /api/x" (text v)))
        (is (not (re-find #"changes something" (text v))))
        (is (not (re-find #"read-only" (text v))))))

    (testing "and a document carrying the old `:effectful?` key is not
              consulted — the METHOD decides, so a stale or invented value
              cannot contradict what the request will actually do"
      (let [lying (assoc (ep :get) :effectful? true)
            v     (views/endpoint-main [lying]
                                       (views/endpoint-params lying) nil)]
        (is (re-find #"GET /api/x" (text v)))
        (is (not (re-find #"changes something" (text v))))))

    (testing "the INDEX marks it too — that is where a reader scans before
              choosing which endpoint to open"
      (is (re-find #"changes something"
                   (text (views/endpoints-main [(ep :post)])))))))

(deftest a-badge-is-separated-from-what-it-marks
  ;; The fourth instance of this project's oldest class, and the largest: a
  ;; whole control, three separators missing at once.
  ;;
  ;;   rate [kg]🌐⚡      trail badges run into the signature AND each other
  ;;   📦rate            a lead badge runs into the name
  ;;   📦🔒rate           and into the next badge
  ;;
  ;; Invisible because NO form in `page/responses` carries a badge — not
  ;; `:private?` (the one that does is filtered out by default), not
  ;; `:exported?`, not `:effectful?`. So the control renders on every driven
  ;; screen with its badge path never taken.
  ;;
  ;; `every-registry-entry-can-actually-reach-the-screen` proves a trail badge
  ;; RENDERS, using data it brings itself and COUNTING the results. Counting is
  ;; what let this through: a badge that renders and a row that reads correctly
  ;; are different questions, and only the second one needs the row.
  (let [row  (fn [m] (merge {:name "rate" :kind "defn" :form-id "f1" :sig ["[kg]"]
                             :mass 10 :calls [] :callers-out 0} m))
        line (fn [m] (last (str/split-lines
                            (str/join "\n"
                              (cljnx/lines (views/ns-outline-main
                                             {:ns "d" :forms [(row m)]}
                                             {:private? true})
                                            {:detail :prose})))))]

    (testing "a trail badge is separated from the signature it follows"
      (is (= "rate [kg] ⚡" (line {:effectful? true}))))

    (testing "and two trail badges from each other — a reader has to be able to
              see that there are two marks rather than one wide glyph"
      (is (= "rate [kg] 🌐 ⚡" (line {:exported? true :effectful? true}))))

    (testing "a lead badge is separated from the NAME it precedes"
      (is (= "📦 rate" (line {:kind "def" :sig nil}))))

    (testing "and two lead badges from each other"
      (is (= "📦 🔒 rate" (line {:kind "def" :sig nil :private? true}))))

    (testing "a row with no badges gains no stray space — the straight left
              edge this listing is built on is the reason the gutter exists,
              and a separator that renders unconditionally would undo it"
      (is (= "rate [kg]" (line {}))))))

(deftest a-path-pattern-matches-segments-and-captures-what-it-names
  ;; The piece that turns `views/screens` from a DESCRIPTION of the router into
  ;; the router's source. While `subject-for` was a `case`/`cond`, a screen
  ;; could exist in code and not in the table and nothing could see it; once
  ;; the table is what matches, a screen the router reaches is a screen the
  ;; table lists, by construction.
  (testing "a literal pattern matches its own segments and captures nothing"
    (is (= {} (views/match-path "/store" ["store"])))
    (is (= {} (views/match-path "/" [])))
    (is (nil? (views/match-path "/store" ["store" "ns"])))
    (is (nil? (views/match-path "/store" ["other"]))))

  (testing "a `:name` segment captures, keyed by the name it declares"
    (is (= {:ns "demo.core"} (views/match-path "/store/ns/:ns" ["store" "ns" "demo.core"])))
    (is (= {:ns "demo.core" :name "rate"}
           (views/match-path "/store/source/:ns/:name" ["store" "source" "demo.core" "rate"]))))

  (testing "length is part of the match — a pattern is not a prefix. Without
            this `/store/form/:id` would swallow `/store/form/:id/through/:x`,
            and the through-link is a real route this app emits"
    (is (nil? (views/match-path "/store/form/:id" ["store" "form" "f1" "through" "x"])))
    (is (= {:id "f1" :through "x"}
           (views/match-path "/store/form/:id/through/:through"
                             ["store" "form" "f1" "through" "x"]))))

  (testing "a captured segment may look like anything — a dotted namespace, a
            range with dots, a name with punctuation. It is a SEGMENT, and the
            router's job is not to have opinions about its shape"
    (is (= {:range "d1..d2"} (views/match-path "/change/:range" ["change" "d1..d2"])))
    (is (= {:name "register!"} (views/match-path "/endpoints/:name" ["endpoints" "register!"]))))

  (testing "and a pattern renders a concrete example from sample values, so the
            table carries ONE source for both matching and driving — an example
            written out beside its pattern is two literals that can disagree"
    (is (= "/store/ns/demo.core"
           (views/example-path "/store/ns/:ns" {:ns "demo.core"})))
    (is (= "/store/source/demo.core/rate"
           (views/example-path "/store/source/:ns/:name" {:ns "demo.core" :name "rate"})))
    (is (= "/store" (views/example-path "/store" nil)))
    (is (= "/" (views/example-path "/" nil))))
(testing "a trailing `*name` captures the REST as one value, because an
            endpoint is addressed by its own path and a path has slashes in it.
            Same grammar as `slopp.webapp/match-route`, which these two claim to
            mirror — a claim that was true only while no row used a splat"
    (is (= {:method "get" :path "api/modules"}
           (views/match-path "/endpoints/:method/*path" ["endpoints" "get" "api" "modules"])))
    (is (= {:method "get" :path "api/form/:id"}
           (views/match-path "/endpoints/:method/*path"
                             ["endpoints" "get" "api" "form" ":id"]))
        "the API path's own parameter is a LITERAL segment here — it is the
         pattern being addressed, not a value bound to it")
    (is (= {:method "post" :path "api/register"}
           (views/match-path "/endpoints/:method/*path" ["endpoints" "post" "api" "register"])))
    (is (nil? (views/match-path "/endpoints/:method/*path" ["endpoints" "get"]))
        "a splat needs at least one segment — /endpoints/get addresses nothing"))

  (testing "and the splat renders back, so one row still drives both jobs"
    (is (= "/endpoints/get/api/modules"
           (views/example-path "/endpoints/:method/*path"
                               {:method "get" :path "api/modules"})))))

(deftest the-two-halves-of-a-boundary-point-opposite-ways
  ;; `used by` and `depends on` are the SAME list rendered twice, and the arrow
  ;; between the module and the namespace was hardcoded `→`. That is right for
  ;; one of them:
  ;;
  ;;   used by      demo.web  1 edge → demo.core     demo.web depends on us ✓
  ;;   depends on   demo.util 1 edge → demo.core     reads as demo.util → us ✗
  ;;
  ;; An out-edge runs the other way — `demo.core` is the `:from`, `demo.util`
  ;; the `:to`. Both rows claimed the same direction, so the only thing telling
  ;; them apart was the heading above them.
  ;;
  ;; **Live-visible, not a fixture artifact.** `:out` is non-empty on any real
  ;; store; it was empty in `page/responses` and nowhere else. Three weeks of a
  ;; covered screen with an unreached branch — the fifth instance of an
  ;; undriven path hiding a live defect, and the first where the branch was
  ;; one row in a list rather than a whole pane.
  (let [v (views/module-main
           {:module "demo.core" :tier "pure"
            :namespaces [{:ns "demo.core" :forms 3 :tier "pure" :deps []}]
            :layers [["demo.core"]] :cycles []
            :boundary {:in  [{:from "demo.web"  :from-module "demo.web"  :to "demo.core"}]
                       :out [{:from "demo.core" :to "demo.util" :to-module "demo.util"}]}})
        text (fn [x] (str/join "\n" (cljnx/lines x {:detail :prose})))
        line (fn [after]
               (let [ls (str/split-lines (text v))]
                 (second (drop-while #(not= after %) ls))))]

    (testing "an INBOUND edge points at us — they name our namespace"
      (is (= "demo.web 1 edge → demo.core" (line "used by"))))

    (testing "and an OUTBOUND edge points away — we name theirs. The row is
              still grouped by the far module, so the arrow is what carries
              the direction rather than the word order"
      (is (= "demo.util 1 edge ← demo.core" (line "depends on"))))

    (testing "the two are therefore DIFFERENT strings, which is the whole
              point: a reader who lands mid-page and sees one row must be able
              to tell which half they are in without scrolling to the heading"
      (is (not= (line "used by") (line "depends on"))))))

(deftest who-may-call-is-stated-on-the-page-and-marked-only-when-it-narrows
  ;; `:auth` is the fact a reader wants beside `:effectful?` — one says whether
  ;; calling changes anything, the other says whether they may. Both landed as
  ;; asks; this renders the one that arrived.
  ;;
  ;; **Two placements, one argument.** The page STATES it, because who may call
  ;; is first-order and the page has room. The index marks only what NARROWS
  ;; it: all nine endpoints on slopp's own surface are `:public`, and a column
  ;; reading `public` nine times is a badge nobody reads — the same reasoning
  ;; that keeps the effect mark off read-only rows.
  (let [text (fn [v] (str/join " " (filter string? (nodes v))))
        ep   (fn [auth] (cond-> {:method :get :path "/api/x" :name 'x
                                 :request nil :response :string}
                          (some? auth) (assoc :auth auth)))]

    (testing "the page states it, whatever it is"
      (is (re-find #"who may call: public"
                   (text (views/endpoint-main [(ep :public)]
                                              (views/endpoint-params (ep :public)) nil))))
      (is (re-find #"who may call: \[:group \"admin\"\]"
                   (text (views/endpoint-main [(ep [:group "admin"])]
                                              (views/endpoint-params (ep [:group "admin"])) nil)))))

    (testing "the INDEX marks only an endpoint that is not public — that is the
              row a reader is scanning for"
      (is (re-find #"\[:group \"admin\"\]"
                   (text (views/endpoints-main [(ep [:group "admin"])]))))
      (is (not (re-find #"public"
                        (text (views/endpoints-main [(ep :public)]))))))

    (testing "and a document that does not publish `:auth` says NOTHING on
              either screen. Rendering absent as public would assert an access
              rule nobody declared, which is the worst direction to be wrong in
              — and it is the shape every project on an older jar sends"
      (let [v (text (views/endpoint-main [(ep nil)]
                                         (views/endpoint-params (ep nil)) nil))]
        (is (re-find #"GET /api/x" v)
            "it renders — a not-found page also says nothing about who may call")
        (is (not (re-find #"who may call" v))))
      (is (not (re-find #"public"
                        (text (views/endpoints-main [(ep nil)]))))))))

(deftest an-arglist-arrives-as-a-STRING-at-this-grain-and-a-list-one-rung-over
  ;; `:sig` is ONE key with TWO shapes in this API, and the fixture copied the
  ;; wrong one for three weeks:
  ;;
  ;;   form-view top level, neighbour-card   →  :string      "[kg zone]"
  ;;   form-row, search-results              →  [:sequential :string]
  ;;
  ;; The producer, per slopp 2026-08-15: `read.orient/form-card` unwraps a
  ;; single arity, then `api.model/json-card` `pr-str`s it. So SEVERAL arities
  ;; arrive as one string with both inside it — `"[[x] [x y]]"` — which is the
  ;; shape no fixture here reaches and no real store has yet sent me.
  ;;
  ;; Pinned as a unit test rather than a second fixture because a form page
  ;; shows one form: the driven screen can carry the single-arity string or the
  ;; multi-arity one, never both. Same limit as a cycle beside an empty
  ;; boundary, and the same answer — a combination one fixture excludes belongs
  ;; here, on copied data.
  (letfn [(arglist [sig]
            (nth (cljnx/lines (views/form-main {:form "demo.core/rate" :form-id "f1"
                                                 :ns "demo.core" :module "demo.core"
                                                 :sig sig :doc "d" :tokens []})
                               {:detail :prose})
                 2))]

    (testing "one arity is the string the wire sends, rendered as it arrived"
      (is (= "[kg zone]" (arglist "[kg zone]"))))

    (testing "SEVERAL arities are still ONE string, and it is not this screen's
              job to take it apart — splitting would be guessing at a format
              the producer flattened on purpose"
      (is (= "[[x] [x y]]" (arglist "[[x] [x y]]"))))

    (testing "and a form with no arglist SAYS so rather than rendering a blank
              where the signature goes — an absent fact and an absent rendering
              look identical, and a reader assumes the flattering one"
      (is (= "no signature recorded" (arglist nil))))

    (testing "the sequential shape still renders, because the same view is not
              the only caller of this key — `form-row` and `search-results`
              really do send a list, and dropping the branch would break the
              rung this fixture spent three weeks imitating"
      ;; the view joins with two spaces and the prose reader collapses runs of
      ;; whitespace, so this is one space. Asserting the source's spelling
      ;; rather than the reader's output would pin a fact no reader can see.
      (is (= "[kg] [kg zone]" (arglist ["[kg]" "[kg zone]"]))))))

(deftest sibling-options-on-a-rung-are-separated-in-the-MARKUP
  ;; `band-forround-up` — two form names welded into one word, and a wrong one.
  ;; The alternatives on a rung were emitted as adjacent `<a>` elements with
  ;; nothing between them, so a browser's flex gap was the only separator.
  ;;
  ;; **This is the `demo.orderstatic` bug, in the form that documents it.**
  ;; `spine-view` carries a comment three lines above the defect saying the
  ;; space belongs in the markup and not only in the stylesheet — written when
  ;; the module tag had the same fault. The rule was known, written down, and
  ;; applied to the neighbouring span but not this one.
  ;;
  ;; Unreachable from any fixture until 2026-08-15: every form in
  ;; `page/responses` had ONE callee, so no rung ever had two alternatives to
  ;; run together. Adding a second callee to reach the `through` route surfaced
  ;; this on the same row.
  ;;
  ;; PROSE, because adjacency is a prose fact — structured output puts a
  ;; closing tag between the two names and the defect is invisible there.
  (let [rung (fn [n] {:focus 0
                      :rungs [{:form "demo.core/rate" :form-id "f1"
                               :module "demo.core" :choice 0 :alternatives n
                               :options (into [{:form-id "f1" :form "demo.core/rate"}]
                                              (take (dec n)
                                                    [{:form-id "f2" :form "demo.core/band-for"}
                                                     {:form-id "f3" :form "demo.core/round-up"}]))}]})
        text (fn [n] (last (cljnx/lines (views/spine-view "f1" (rung n))
                                         {:detail :prose})))]

    (testing "two siblings are two words, not one"
      (is (= "rate demo.core 1 of 3 band-for, round-up" (text 3))))

    (testing "and one sibling still reads as itself — the separator must not
              become a trailing comma on the common case"
      (is (= "rate demo.core 1 of 2 band-for" (text 2))))))

(defn full-view
  "The WHOLE page as hiccup, for a state at its own `:path` — built the way the
  shipped app builds it.

  **The view is DERIVED, and the table is too.** `slopp.webapp/wiring` composes
  the view from the route table, the pages it names and the state screens; the
  table itself comes from `slopp.cljnx/marked-pages`, which is the same reader
  the headless driver uses. So this is the shipped composition, not a harness
  resembling it — and it now also proves the `^{:webapp/path …}` markers route,
  because there is no declared table left to fall back on.

  **The SCREEN is routed, not declared.** The caller supplies `:path`; this runs
  it through `match-route` and overwrites `:screen` and `:params`. A caller may
  still pass them and they are ignored — a test that named its own screen could
  assert one the router cannot reach from the path in the same map, which is two
  facts pretending to be one.

  **The fixture answers REQUESTS now.** Pages ask while they render, so there is
  no load to pre-seed — see [[with-main-load]]. `:webapp/call` answers
  synchronously, which is what lets a single render see the result: `ask!`
  starts the load, the answer lands before it re-reads, and the page renders
  against it.

  Three urls are answered, and the split matters:

  - `/api/modules` is BOTH the Code section's nav and the Code screens' own
    load, and under `ask!` those are one request and therefore one load — so a
    modules-shaped `::main` wins, and everything else gets the nav list.
  - `/api/projects` is the hub's, for the switcher.
  - anything else is this screen's own, answered from `::main`.

  Params MERGE over the routed ones so a fixture can still supply something the
  pattern does not capture; the routed values win for the keys the pattern owns."
  ([state] (full-view state {}))
  ([state {:keys [base]}]
   (let [;; **A fixture names a path inside a PROJECT.** The app's addresses
         ;; carry a tenant segment now, and almost every test here is about a
         ;; SCREEN rather than about tenancy, so the project is supplied once
         ;; here instead of being spelled into thirty-five fixtures. A fixture
         ;; that IS about tenancy writes the whole address and this leaves it
         ;; alone.
         given (or (:path state) "/")
         addr  (if (str/starts-with? given "/p/") given (str "/p/demo" given))
         table (slopp.cljnx/marked-pages)
         m     (webapp/match-route table addr)
         main  (::main state)
         st    (-> state
                   (assoc :path addr)
                   (assoc :screen (:screen m))
                   ;; ROUTED last, so the router wins the keys its pattern owns.
                   (assoc :params (merge (:params state) (:params m))))
         store (atom st)
         call  (fn [request ok err]
                 (let [u (str (:http/url request))]
                   (cond
                     (str/ends-with? u "/api/modules")
                     (ok (let [v (:value main)]
                           (if (and (map? v) (contains? v :modules))
                             v
                             {:modules (or (:modules state) [])})))

                     (str/ends-with? u "/api/projects")
                     (ok (or (:projects state) []))

                                          :else
                     (case (:status main)
                       ;; **A `…/paths` document, when the fixture recorded
                       ;; ROWS.** These two endpoints publish
                       ;; `{:paths […]}` and the PAGE strips that envelope now;
                       ;; it used to be a route row's `:derive :paths`, so a
                       ;; fixture recorded what the screen received, which was
                       ;; the rows. Wrapping here keeps those fixtures saying
                       ;; what they are about — this row renders like THIS —
                       ;; instead of restating an envelope none of them is
                       ;; testing. The stripping itself is covered, on real
                       ;; documents, by
                       ;; `a-screen-the-router-can-reach-always-renders-something`.
                       :ready  (ok (let [v (:value main)]
                                     (if (and (str/ends-with? u "/paths") (sequential? v))
                                       {:paths v}
                                       v)))
                       :failed (err (:value main))
                       nil))))
         app   (webapp/wiring
                (assoc (app/wiring {:state  store
                                             :base   (or base "")
                                             :call   call
                                             :render (fn [_])})
                       :webapp/routes table))]
     ((:webapp/view app) @store))))

(deftest the-store-table-carries-the-magnitudes-the-diagram-cannot
  ;; Driven headlessly, the whole of `/store` is `code`, one census sentence
  ;; and an svg summarised by CLASS: `2 module-node layered, 1 graph-root`. Not
  ;; one module is named and not one number appears. That is not a degraded
  ;; reading of the page — it is the page, to the instrument this project reads
  ;; itself with.
  ;;
  ;; And the numbers were never missing. `/api/modules` sends namespaces,
  ;; tests, tier, deps and gaps on every row, and the default view spends all
  ;; of them on box positions. The table is the lens that says them.
  ;;
  ;; Deliberately NOT the gap counts: those are the `gaps` lens's subject, and
  ;; a lens that shows everything is the default view with more rows.
  (let [data  {:modules [{:module "demo.web" :namespaces ["demo.web"] :tests 2
                          :tier "external" :foundation false :deps ["demo.core"]
                          :gaps {:forms 12 :no-doc 9 :no-why 11 :uncovered 12}}
                         {:module "demo.core" :namespaces ["demo.core" "demo.core.calc"]
                          :tests 5 :tier "pure" :foundation false :deps []
                          :gaps {:forms 20 :no-doc 0 :no-why 1 :uncovered 3}}
                         {:module "demo.base" :namespaces ["demo.base"] :tests 1
                          :tier "pure" :foundation true :deps []
                          :gaps {:forms 3 :no-doc 0 :no-why 0 :uncovered 0}}]
               :layers  [["demo.core"] ["demo.web"]]
               :cycles  []}
        main  (fn [lens]
                (views/find-region
                  (full-view (with-main-load {:path (if lens (str "/store/" lens) "/store")
                                                   :screen :code :params {} :lens lens}
                                    :ready data))
                  :main))
        rows  (fn [v] (filter #(and (vector? %) (= :tr (first %))) (nodes v)))
        text  (fn [v] (str/join " " (filter string? (nodes v))))]

    (testing "the default view names no module at all — the contrast this lens
              exists for, asserted rather than asserted about"
      (let [d (text (main nil))]
        (is (not (re-find #"demo\.core" d)))
        (is (not (re-find #"demo\.base" d)))))

    (testing "one row per module plus the header, ordered the way the picture
              stacks: foundation, then layer 0, then layer 1"
      (let [rs (rows (main "table"))]
        (is (= 4 (count rs)))
        (is (= ["demo.base" "demo.core" "demo.web"]
               (->> (rest rs)
                    (map #(->> (nodes %) (filter string?) first))))
            "and the module's own name is the first thing in its row")))

    (testing "a row carries the magnitudes: namespaces, forms, tests, tier, and
              what it depends on BY NAME — the edge the diagram draws is the
              one fact a table would otherwise throw away"
      (let [core (->> (rows (main "table"))
                      (filter #(re-find #"demo\.core" (text %)))
                      first)]
        (is (re-find #"2" (text core)) "two namespaces")
        (is (re-find #"20" (text core)) "twenty forms")
        (is (re-find #"5" (text core)) "five tests")
        (is (re-find #"pure" (text core)))))

    (testing "each module name is a link to its own page, so the table is a way
              in and not only a readout"
      (let [v (main "table")]
        (is (some #(and (vector? %) (= :a (first %))
                        (= "/p/demo/store/module/demo.base" (:href (second %))))
                  (nodes v)))))

    (testing "the foundation is MARKED, not left to be inferred from position.
              A reader who sorts or skims has lost the only cue the picture
              gave, which was that the band sits beneath everything"
      (let [base (->> (rows (main "table"))
                      (filter #(re-find #"demo\.base" (text %)))
                      first)]
        (is (re-find #"foundation" (text base)))))

    (testing "the diagram is REPLACED, not decorated. A lens shows one subject
              a different way; leaving the picture above the table would make
              it an addition to the default view instead of an alternative"
      (is (not (some #(and (vector? %) (= :svg (first %))) (nodes (main "table")))))
      (is (some #(and (vector? %) (= :svg (first %))) (nodes (main nil)))))))

(deftest the-results-pane-names-what-was-SEARCHED-not-only-what-was-found
  ;; Found by opening the screen, not by an assertion: the pane read
  ;; "showing 4 of 37 hits — 1 module, 1 namespace, 35 forms" and never said
  ;; 37 hits FOR WHAT. The search box holds the query, but the box is in
  ;; another region and a shared link lands a reader in this one.
  ;;
  ;; The query here appears in NO hit, deliberately. The obvious version of
  ;; this test searches for "rate" against a fixture containing a form called
  ;; `rate`, and then passes on a page that names the query nowhere.
  (letfn [(text [v] (str/join " " (filter string? (nodes (views/find-region v :main)))))]
    (let [v (full-view
             (with-main-load {:path "/store/search?q=tariff" :screen :search
                              :params {:q "tariff"}}
               :ready {:query "tariff" :total 37
                       :totals {:modules 1 :namespaces 1 :forms 35}
                       :hits [{:kind "form" :name "rate" :address "/store/form/f1"
                               :ns "demo.core" :matched "doc" :rank 0.9}]}))
          t (text v)]
      (is (re-find #"tariff" t)
          "a count with no subject is the same sentence as `1 of them` on every module")
      (is (re-find #"37" t) "and it still says how many were not shown"))))

(deftest the-api-screen-says-enough-to-call-the-endpoint
  ;; The section exists because a project's HTTP surface was legible only by
  ;; reading its store, which is the one thing this project may not do. The bar
  ;; is not "renders the document" — it is that a reader can call the endpoint
  ;; afterwards without opening anything else.
  ;;
  ;; Each endpoint is rendered on its OWN page since the index/detail split,
  ;; and these assertions moved with it. That is incidentally a stronger test:
  ;; on one shared page a `re-find` could be satisfied by a different
  ;; endpoint's text and nothing would have said so.
  (let [;; the ROWS, not the document. The route row's `:derive :paths` strips the
        ;; envelope, so the `:main` load value a screen receives is this.
        doc  [{:method :get :path "/api/modules" :name 'modules
               :request nil
               :response [:map [:modules [:sequential [:map [:module :string]
                                                       [:gaps [:map [:forms :int]]]]]]
                          [:cycles [:sequential [:sequential :string]]]]}
              {:method :get :path "/api/module/:m" :name 'module
               :request nil
               :response [:map [:module :string]
                          [:tier [:enum "pure" "internal" "external"]]]}
              {:method :post :path "/api/register" :name 'register!
               :request [:map [:name :string] [:pid {:optional true} [:maybe :int]]]
               :response [:or [:map [:slug :string]] [:map [:error :string]]]}]
        page (fn [nm]
               ;; callers still say "modules" — a name is what a reader of this
               ;; test recognises. The ADDRESS is derived from the document row
               ;; it names, so the fixture and the address cannot disagree.
               (let [ep (first (filter #(= (str (:name %)) (str nm)) doc))]
                 (views/find-region
                  (full-view (with-main-load {:path   (views/endpoint-address ep)
                                              :screen :endpoint
                                              :params (views/endpoint-params ep)}
                               :ready doc))
                  :main)))
        text (fn [v] (str/join " " (filter string? (nodes v))))]

    (testing "method and path together — the two facts you need before any
              other, and useless apart"
      (is (re-find #"GET /api/modules" (text (page "modules"))))
      (is (re-find #"POST /api/register" (text (page "register!")))))

    (testing "a path parameter is named even though this endpoint's request
              schema is nil — the document is inconsistent about them and the
              screen is not"
      (is (re-find #"path parameters: m" (text (page "module")))))

    (testing "response fields keep their NESTING as markup, not as indentation.
              A field list flattened to one column cannot say whether `forms`
              is a top-level key or lives inside `gaps`, and that is the
              difference between a reader calling the endpoint and guessing"
      (let [v      (page "modules")
            lists  (filter #(and (vector? %) (= :ul (first %))) (nodes v))
            nested (filter (fn [ul] (some #(and (vector? %) (= :ul (first %)))
                                          (nodes ul)))
                           lists)]
        (is (seq lists) "the fields render as a list at all")
        (is (seq nested) "and at least one list contains another")))

    (testing "an endpoint with no request says so in words. A blank where the
              parameters go is the reader's problem to interpret and this
              screen's job to answer"
      ;; and it says the right ABSENT thing. A GET has no body to be missing —
      ;; its request schema is the query string — so `no request body` on one
      ;; is a false sentence about how to call it. Both spellings are pinned
      ;; here because the wrong one shipped and read perfectly well.
      (is (re-find #"no parameters" (text (page "modules"))))
      (is (not (re-find #"no request body" (text (page "modules"))))))

    (testing "optionality is on the page, because it is the thing a caller gets
              wrong and the schema is the only place it is written down"
      (is (re-find #"optional" (text (page "register!")))))

    (testing "an alternation says how many shapes it has AND opens them, so the
              endpoint never renders as having no response"
      ;; The comment here used to say `rows` yields nothing for an `:or`, so
      ;; the type line was "the whole answer". That stopped being true when
      ;; `alts-of` landed and nothing said so — the assertion went on passing
      ;; because it only ever checked the type line, which is the half that did
      ;; not change. Third stale claim about this one behaviour, all three
      ;; passing, and the phrase they pinned said nothing twice.
      (let [out (text (page "register!"))]
        (is (re-find #"one of 2 shapes" out))
        (is (re-find #"option 1" out) "the branches, which the count refers to")
        (is (not (re-find #"either object or object" out)))))))

(deftest a-screen-that-offers-lenses-shows-a-way-to-switch
  ;; `/store/gaps` — the gap overlay, its tint ramp, its summary and its styles
  ;; — had NO link anywhere in this app. `with-lens` was called in exactly one
  ;; production place, the "read the source" link on a form page, so the only
  ;; way to reach a lens was to type its URL.
  ;;
  ;; `lenses`' own docstring said the table is read by three things, the third
  ;; being "the switcher chrome renders it". There was no switcher. That claim
  ;; is the kind this repo keeps paying for: prose asserting a consumer that
  ;; does not exist.
  ;;
  ;; The expected hrefs are DERIVED from `lenses` rather than written out.
  ;; They were literals — `["/store/gaps"]` — and landing the store table
  ;; reddened this test, which is about the switcher and not about any
  ;; particular lens. A literal here makes the registry and its consumer agree
  ;; by hand, which is the exact failure the test above it exists to catch.
  (letfn [(bar [path screen lens]
            (views/find-region
             (full-view (with-main-load {:path path :screen screen :params {}
                                              :lens lens}
                               :ready {:modules [] :layers [] :cycles []}))
             :nav/lens))
          (hrefs [v] (keep #(when (map? %) (:href %)) (nodes v)))
          (text  [v] (str/join " " (filter string? (nodes v))))]

    (testing "the DEFAULT and every lens are both offered — a switcher that
              lists only the alternatives is a one-way door out of the default"
      (let [b (bar "/store" :code nil)]
        (is (some? b) "a screen with lenses renders a switcher")
        ;; both views NAMED, and only the one you are not in is a link. My
        ;; first version of this asserted the default was a link too, which
        ;; contradicted the current-view assertion below — on the bare path
        ;; they are the same element.
        (is (re-find #"diagram" (text b)))
        (is (every? #(re-find (re-pattern %) (text b)) (:code views/lenses))
            "every lens the store declares is NAMED in the bar")
              (is (= (mapv #(str "/p/demo/store/" %) (:code views/lenses)) (vec (hrefs b)))
            "in bar order, and the bare path is not a link to itself")))

    (testing "the default has a NAME rather than being an unlabelled way back.
              It is a view like any other and a reader has to be able to say
              which one they are looking at"
      (is (re-find #"diagram" (text (bar "/store" :code nil)))))

    (testing "the current view is marked and is not a link to itself"
      (let [b (bar "/store/gaps" :code "gaps")]
              (is (= (into ["/p/demo/store"]
                   (comp (remove #{"gaps"}) (map #(str "/p/demo/store/" %)))
                     (:code views/lenses))
               (vec (hrefs b)))
            "the lens you are in is not offered as somewhere to go")
        (is (re-find #"gaps" (text b)))))

    (testing "a screen with no lenses renders NO switcher — an empty bar takes
              layout and says nothing, the same rule app-shell already makes
              for an omitted pane"
      (is (nil? (bar "/store/ns/demo.core" :ns nil))))

    (testing "every screen in the lens table names its default view, and no
              other — the two halves are one registry and drift silently"
      (is (= (set (keys views/lenses)) (set (keys views/default-view)))))))

(deftest the-app-renders-every-state-including-the-two-an-spa-invents
  ;; Server-rendered pages have no LOADING state and no client-side
  ;; NOT-FOUND — the server answers or it 404s. An SPA invents both, and
  ;; both render as a blank pane if nobody handles them. A blank pane is
  ;; indistinguishable from a screen whose content is empty, which is the
  ;; specific way an SPA lies to its reader.
  ;;
  ;; **All three are the FRAMEWORK's now**, and this app declares none of them.
  ;; `slopp.webapp/wiring` defaults `:webapp/not-found`, `:webapp/loading` and
  ;; `:webapp/failed`, and slopp's comment beside them says this app wrote
  ;; almost exactly those by hand — which is the evidence they were worth
  ;; defaulting rather than a spinner somebody picked. What this app still owns
  ;; is WHERE they sit, which is chrome's, and that is what the region
  ;; assertions below check.
  ;;
  ;; So the wording is theirs and the assertions are case-insensitive on
  ;; purpose: pinning `"loading…"` exactly would make this a test of slopp's
  ;; copy-editing rather than of this app's behaviour.
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (testing "in flight: says so, rather than rendering an empty main"
      (let [v (full-view (with-main-load {:path "/"} :loading nil))]
        (is (re-find #"(?i)loading" (text v)))
        (is (some? (views/find-region v :nav/sections))
            "the global bar stays up while the screen loads — it is not part of the fetch")))
    (testing "not found: names the path instead of guessing a screen"
      (let [v (full-view {:path "/nonsense"})]
        (is (re-find #"(?i)not found" (text v)))
        (is (re-find #"/nonsense" (text v))
            "the url is what tells a reader which link was wrong")))
    (testing "an error renders as an error, not as a permanent spinner"
      ;; a failed fetch that leaves "loading…" on screen is worse than a
      ;; blank one — it promises something is still coming
      (let [v (full-view (with-main-load {:path "/"} :failed "boom"))]
        (is (re-find #"(?i)could not load|error" (text v)))
        (is (re-find #"boom" (text v))
            "the MESSAGE reaches the screen — a failure that says only that it
             failed sends the reader to the console for the half that matters")
        (is (not (re-find #"(?i)loading" (text v))))))
    (testing "the Code screens carry the left pane; the others do not"
      ;; REAL addresses, because `full-view` routes from the path. These were
      ;; `"/x"` with the screen named alongside — and `/x` matches no row, so
      ;; both sides would render not-found and this would compare two identical
      ;; pages while claiming to compare two sections.
      (let [mods [{:module "demo" :namespaces ["demo.core"] :tests 1
                   :tier "internal" :foundation false}]
            of  (fn [path params data]
                  (full-view
                   (with-main-load {:path path :params params :modules mods}
                     :ready data)))]
        (is (some? (views/find-region (of "/store/ns/demo.core" {:ns "demo.core"}
                                          {:ns "demo.core" :forms []})
                                      :nav/local)))
        (is (nil? (views/find-region (of "/" {} {:commit-points [] :working {:forms 0}})
                                     :nav/local))
            "Review is not a section with local navigation")))
    (testing "only the form screen opens the detail rail, and only once answered"
      (let [with (full-view
                  (with-main-load {:path "/store/form/f1" :params {:id "f1"}}
                    :ready {:form "a/b" :ns "a" :module "a"
                            :tokens [["text" "x"]] :warranty {:covered 0}
                            :callers [] :callees [] :note "n"}))
            without (full-view
                     (with-main-load {:path "/store/form/f1" :params {:id "f1"}}
                       :loading nil))]
        (is (some? (views/find-region with :nav/detail)))
        (is (nil? (views/find-region without :nav/detail))
            "an empty rail while loading would take layout space and say nothing")))))

(deftest the-form-page-leads-with-what-the-form-IS-and-keeps-source-behind-a-lens
  ;; The point of the whole UI, at form grain. This page used to render the
  ;; breadcrumb, the signature, the doc and then THE SOURCE — so the fastest
  ;; way to find out what a form does was to read it, and every other thing
  ;; the store knows sat in a rail beside the answer nobody needed.
  ;;
  ;; Inverted: the default page is what the form is FOR and how it sits in the
  ;; system. Source is still one click away and always will be — a curated view
  ;; that becomes the only way to see a thing produces confident false beliefs
  ;; — but reaching for it is the signal that this page was too thin.
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))
          ;; the lens is the ADDRESS: `/store/form/f-rate/source` routes to
          ;; `form-source-screen`. Left as `:lens` in state, the lensed call
          ;; would render the DEFAULT page — and "the source lens shows the
          ;; source" would be asserting against the very page it exists to
          ;; differ from.
          (page [lens data]
            (full-view (with-main-load
                         {:path   (views/with-lens "/store/form/f-rate" lens)
                          :params {:id "f-rate"}}
                         :ready data)))]
    (let [full {:form-id "f-rate" :name "rate" :form "post.tariff/rate"
                :ns "post.tariff" :module "post.tariff"
                :sig ["[kg zone]"] :doc "Rate for a weight in a zone."
                :why "so the order path stops guessing" :warranty {:covered 3}
                :tokens [["text" "(defn rate "] ["delim" "["] ["text" "kg zone])"]]
                :callers [{:via "static" :count 1
                           :forms [{:form "quote" :ns "post.order" :module "post.order"
                                    :form-id "f-quote" :calls 1}]}
                          {:via "observed" :count 1
                           :forms [{:form "audit" :ns "post.job" :module "post.job"
                                    :form-id "f-audit" :calls 1}]}]
                :callees [{:form "band-for" :ns "post.tariff" :module "post.tariff"
                           :form-id "f-band" :via "static" :calls 2
                           :sig ["[kg]"] :doc "Which weight band."
                           :warranty {:covered 1}}]
                :note "edges are a syntactic floor"}]

      (testing "the default page says what the form is for, and does NOT show source"
        (let [t (text (page nil full))]
          (is (re-find #"Rate for a weight in a zone" t))
          (is (re-find #"so the order path stops guessing" t) "the recorded ask")
          (is (re-find #"kg zone" t) "the signature")
          (is (not (re-find #"defn rate" t))
              "source on the default page is the inversion this wave undoes")))

      (testing "callers and callees are BOTH on the page beside the form, because
                the reason to open this screen is to read it WITH them.
                They sit in the RAIL rather than stacked above and below: that
                was tried, and `form-rail`'s own docstring records why it lost
                — stacking meant scrolling away from the form to see either,
                which is the opposite of what the rail is for"
        (let [v (page nil full)]
          (is (some? (views/find-region v :nav/detail)) "no rail")
          (let [t (str/join " " (filter string? (nodes (views/find-region v :nav/detail))))]
            (is (re-find #"quote" t)    "no caller rendered")
            (is (re-find #"band-for" t) "no callee rendered"))))

      (testing "and the MAIN pane carries the path this form sits on, which is
                the multi-hop question a one-hop rail cannot answer"
        (let [t (str/join " " (filter string? (nodes (views/find-region (page nil full) :main))))]
          (is (re-find #"quote" t) "the spine names the caller above this form")
          (is (re-find #"band-for" t) "and the callee below it")))

      (testing "a caller arrives ranked, and an observed edge sinks below a static one"
        (let [ns' (nodes (page nil full))
              at  (fn [re] (first (keep-indexed #(when (and (string? %2)
                                                            (re-find re %2)) %1) ns')))]
          (is (< (at #"quote") (at #"audit")))))

      (testing "the source LENS shows the source, and is reachable from the page"
        (is (re-find #"defn rate" (text (page "source" full))))
        (is (re-find #"/store/form/f-rate/source"
                     (pr-str (page nil full)))
            "no way to reach the escape hatch is worse than not having one"))

      (testing "a form with nothing recorded SAYS so — silence reads the same as
                coverage, and this page is the one that must not fake it"
        (let [t (text (page nil (dissoc full :doc :why)))]
          (is (re-find #"(?i)no .*(comment|doc)" t))
          (is (re-find #"(?i)no .*(ask|why|recorded)" t))))

      (testing "and those sentences do NOT appear when the facts are there —
                otherwise the check above passes on a page that always says it"
        (let [t (text (page nil full))]
          (is (not (re-find #"(?i)no .*(ask|why|recorded)" t))))))))

(deftest a-gap-tint-colours-only-what-is-stable-and-says-what-is-not
  ;; The overlay that makes "you had to open the source, so we failed" a number
  ;; on a box. Two of the three axes are properties of the RECORD — a form
  ;; either carries a docstring and a recorded ask or it does not, and that is
  ;; the same answer in any process. The third is not: slopp measures
  ;; `:uncovered` against the SESSION's trace map, so a listener that has run
  ;; nothing reports everything uncovered. Tinting by it would paint a
  ;; freshly-booted store uniformly alarming for a reason that is about the
  ;; process rather than the code.
  (testing "a module with everything recorded gets no tint at all"
    (is (zero? (views/gap-step {:forms 10 :no-doc 0 :no-why 0 :uncovered 10}))
        "fully uncovered but fully documented must still be step 0 —
         coverage is the axis that cannot be trusted to be about the code"))
  (testing "and one with nothing recorded gets the darkest"
    (is (= 4 (views/gap-step {:forms 10 :no-doc 10 :no-why 10 :uncovered 0}))))
  (testing "the ramp moves with the stable axes and NOT with coverage"
    (let [half {:forms 10 :no-doc 5 :no-why 5 :uncovered 0}]
      (is (= (views/gap-step half)
             (views/gap-step (assoc half :uncovered 10))))
      (is (pos? (views/gap-step half)))
      (is (> 4 (views/gap-step half)))))
  (testing "a module with no forms is not a gap — it is nothing to judge"
    (is (zero? (views/gap-step {:forms 0 :no-doc 0 :no-why 0 :uncovered 0})))
    (is (zero? (views/gap-step nil))))

  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (let [index {:modules [{:module "demo.thin" :namespaces ["demo.thin"] :tests 0
                            :tier "pure" :foundation false :deps []
                            :gaps {:forms 10 :no-doc 8 :no-why 9 :uncovered 10}}
                           {:module "demo.solid" :namespaces ["demo.solid"] :tests 3
                            :tier "pure" :foundation false :deps []
                            :gaps {:forms 10 :no-doc 0 :no-why 0 :uncovered 0}}]
                 :layers [["demo.thin" "demo.solid"]] :cycles []}
          ;; the lens is the ADDRESS. `:lens` in state is a key nothing reads
          ;; any more — left as one, both branches below would render the
          ;; default diagram and the tint assertions would compare a page with
          ;; itself.
          page  (fn [lens] (full-view (with-main-load
                                        {:path (views/with-lens "/store" lens)}
                                        :ready index)))]
      (testing "the fixture has one thin module and one solid one"
        (is (not= (views/gap-step (:gaps (first (:modules index))))
                  (views/gap-step (:gaps (second (:modules index)))))))

      (testing "the default diagram carries NO gap classes — an overlay that is
                always on is not an overlay"
        (is (not (re-find #"gap-w" (pr-str (page nil))))))

      (testing "the gaps lens tints each box by its own step"
        (let [s (pr-str (page "gaps"))]
          (is (re-find #"gap-w[1-4]" s) "the thin module must be tinted")
          (is (re-find #"gap-w0" s)     "and the solid one explicitly not")))

      (testing "and it states the numbers, because a colour is not a measurement"
        (let [t (text (page "gaps"))]
          (is (re-find #"(?i)no comment|undocumented" t))
          (is (re-find #"(?i)no recorded ask|no ask" t))))

      (testing "coverage is REPORTED but the page says why it is not the tint"
        (let [t (text (page "gaps"))]
          (is (re-find #"(?i)uncovered|covering" t))
          (is (re-find #"(?i)this session|trace|session" t)
              "the caveat has to travel with the number, not live in a docstring"))))))

(deftest the-namespace-screen-carries-a-rail-and-its-options-reach-the-listing
  (let [data  {:ns "demo.core"
               :forms [{:name "demo.core" :kind "ns"    :private? false}
                       {:name "pub"    :kind "defn"  :private? false}
                       {:name "hidden" :kind "defn-" :private? true}]}
                ;; `with-main-load` rather than a hand-written `:loads {:main …}`: a load
        ;; is keyed by its REQUEST now, so `:main` is a key nothing looks under
        ;; and the screen renders as though nothing answered.
        page  (fn [show]
                (full-view (cond-> (with-main-load {:path "/store/ns/demo.core"} :ready data)
                             show (assoc :show show))))
        rail? (fn [v] (some #(and (vector? %) (= :aside (first %))) (tree-seq coll? seq v)))
        shown ;; scoped to .src-defs: the tested-by row wears .src-name too, and it is
        ;; not a definition the display toggles govern
        (fn [v] (->> (tree-seq coll? seq v)
                     (filter #(and (vector? %) (map? (second %))
                                   (= "src-defs" (:class (second %)))))
                     (mapcat #(tree-seq coll? seq %))
                     ;; prefix, not equality: the name also carries its kind's colour class
                     (filter #(and (vector? %) (map? (second %))
                                   (re-find #"^src-name\b" (str (:class (second %))))))
                     (map last) set))]
    (testing "the namespace screen has a right rail; the code index does not,
              because there is nothing yet to set there"
      (is (some? (rail? (page nil))))
      (is (nil? (rail? (full-view {:path "/store" :base ""
                                        :loads {:main {:status :ready
                                                         :value {:modules [] :picture {} :cycles []}}}})))))
    (testing "with nothing set, the listing is the surface"
      (is (= #{"pub"} (shown (page nil)))))
    (testing "and an option held in STATE reaches the listing it governs —
              the rail and the pane are one render, so a toggle that did not
              travel would look exactly like one that did nothing"
      (is (= #{"pub" "hidden"} (shown (page {:private? true})))))))

(deftest the-module-screen-shows-what-the-wire-carries-and-names-what-it-does-not
  ;; `GET /api/module/:m` was asked for and does not exist. The honest
  ;; response to that is not a blank screen and not a diagram of nothing: the
  ;; modules index ALREADY carries this module's namespaces and its
  ;; module-level deps, so who it uses and who uses it are derivable now. What
  ;; is missing is the structure INSIDE — and a page that quietly omits it
  ;; reads as a module with no internal structure, which is a lie about every
  ;; module that has any.
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (let [index {:modules [{:module "demo.web" :namespaces ["demo.web" "demo.web.routes"]
                            :tests 2 :tier "external" :foundation false
                            :deps ["demo.core"]}
                           {:module "demo.core" :namespaces ["demo.core" "demo.core.calc"]
                            :tests 5 :tier "pure" :foundation false :deps []}
                           {:module "demo.job" :namespaces ["demo.job"]
                            :tests 1 :tier "external" :foundation false
                            :deps ["demo.core"]}]
                 :layers [["demo.core"] ["demo.web" "demo.job"]] :cycles []}
          shape (views/module-from-index index "demo.core")]

      (testing "the fixture has both a dependency and TWO consumers, or the
                boundary assertions below cannot tell direction from accident"
        (is (= 2 (count (filter #(some #{"demo.core"} (:deps %)) (:modules index)))))
        (is (seq (:deps (first (:modules index))))))

      (testing "the namespaces it holds come straight off the index"
        (is (= ["demo.core" "demo.core.calc"] (map :ns (:namespaces shape))))
        (is (= "pure" (:tier shape))))

      (testing "who uses it and what it uses are derivable at MODULE grain today"
        (is (= #{"demo.web" "demo.job"} (set (map :from-module (:in (:boundary shape))))))
        (is (empty? (:out (:boundary shape))) "demo.core depends on nothing"))

      (testing "and a module that DOES depend on something says so"
        (let [w (views/module-from-index index "demo.web")]
          (is (= #{"demo.core"} (set (map :to-module (:out (:boundary w))))))))

      (testing "the screen names the structure it cannot draw, rather than
                drawing an empty diagram and letting it read as no structure"
        (let [v (full-view (with-main-load {:path "/store/module/demo.core"
                                                 :params {:module "demo.core"}
                                                 :modules (:modules index)}
                                  :ready shape))]
          (is (re-find #"(?i)not on the wire|needs an endpoint|cannot draw" (text v)))
          ;; the LABEL drops the module root — `calc`, not `demo.core.calc`, the
          ;; same trade the boxes and the nav make. The href keeps the whole
          ;; name because that is an address, and an address shortened for
          ;; legibility is a broken address.
          (is (re-find #"/store/ns/demo\.core\.calc" (pr-str v))
              "its namespaces are still listed, and reachable")))

      (testing "and that sentence is ABSENT once real namespace edges arrive —
                otherwise the page says it forever and a reader stops reading it"
        (let [real ;; what `GET /api/module/:m` will send: real namespace edges and NO
              ;; :pending, because the endpoint answering is what retires it
              (-> shape
                  (dissoc :pending)
                  (assoc :namespaces [{:ns "demo.core" :forms 4 :tier "pure"
                                       :deps ["demo.core.calc"]}
                                      {:ns "demo.core.calc" :forms 9 :tier "pure"
                                       :deps []}]
                         :layers [["demo.core.calc"] ["demo.core"]]))
              v    (full-view (with-main-load {:path "/store/module/demo.core"
                                                 :params {:module "demo.core"}}
                                  :ready real))]
          (is (not (re-find #"(?i)not on the wire|needs an endpoint" (text v)))))))))

(deftest a-module-box-is-a-link-into-the-code-it-stands-for
  ;; The boxes carried `data-module` and nothing else: they highlighted on
  ;; hover — a stylesheet rule — and went nowhere. A hook someone left for
  ;; exactly this and never used, which reads as broken rather than as absent.
  ;;
  ;; Then they linked to a representative NAMESPACE, which was right until
  ;; `/store/module/:m` shipped and became the thing a box means. Nothing
  ;; changed the box, so the descend had almost no way in (the second
  ;; code-navigation wave).
  ;;
  ;; **Scoped to the SVG.** This test used to read every href on the page while
  ;; claiming to be about a box — and `module-nav` renders `/store/ns/…` onto
  ;; the same page, so it could not tell a diagram link from a nav link and
  ;; would have passed unchanged through exactly the change below.
  (letfn [(svg-hrefs [v]
            (let [svg (first (filter #(and (vector? %) (= :svg (first %))) (nodes v)))]
              (set (keep #(when (map? %) (:href %)) (nodes svg)))))]
    (let [state (with-main-load
                  {:path "/store" :screen :code :base ""}
                  :ready
                  {:modules [{:module "demo.hub" :namespaces ["demo.hub.util" "demo.hub"]
                              :tests 1 :foundation false}
                             {:module "demo.client" :namespaces ["demo.client.api"]
                              :tests 0 :foundation false}
                             {:module "demo.empty" :namespaces [] :tests 0 :foundation false}]
                   :cycles []
                   ;; `:layers`, and the diagram is LAID OUT from it — where this
                   ;; used to hand-write a `:picture`. The layout was a route row's
                   ;; `:derive`, and the old fixture wrote the load directly, so the
                   ;; derive never ran and the picture here was the one rendered. A
                   ;; page applies its own derive now, so a hand-written picture is
                   ;; overwritten by the real one — and the real one needs `:layers`,
                   ;; which this fixture never had. `picture-of` answers 0 nodes
                   ;; without it, which is a diagram with no boxes to be links.
                   :layers [["demo.hub"] ["demo.client" "demo.empty"]]})
          hrefs (svg-hrefs (full-view state))]

      (testing "a box DESCENDS into the module it stands for — that is what the
                box has meant since /store/module/:m shipped"
        (is (= #{"/p/demo/store/module/demo.hub"
                 "/p/demo/store/module/demo.client"
                 "/p/demo/store/module/demo.empty"}
               hrefs)))

      (testing "and it no longer jumps PAST the module page to a namespace —
                the rung between the diagram and a namespace is the whole
                reason the module screen exists"
        (is (not-any? #(re-find #"/store/ns/" %) hrefs)))

      (testing "a module with no namespaces is a link now, and that REVERSES an
                earlier rule here. It was not a link because a representative
                namespace did not exist; its module page does, and renders what
                the index knows — so the box goes somewhere real"
        (is (contains? hrefs "/p/demo/store/module/demo.empty")))

      (testing "and the href carries whichever PROJECT is being read, the same
                way every other link does"
        ;; this used to ask for a mount point — `{:base \"/p/toy\"}` — because the
        ;; app was served under one and the framework prefixed. There is no
        ;; mount point now: the prefix IS the project, it comes from the slug in
        ;; the address, and asking for a mount on top of it produced
        ;; `/p/toy/p/demo/…`. The fact being pinned is unchanged — a link built
        ;; from DATA moves with the prefix like a written one does.
        (is (contains? (svg-hrefs (full-view (assoc state :path "/p/toy/store")))
                       "/p/toy/store/module/demo.hub"))))))

(deftest the-search-screen-belongs-to-the-code-section
  ;; `app-view` decides the left pane from a set of screen keywords, so a new
  ;; Code screen that is not in it renders with the section's navigation
  ;; missing — which reads as a different application rather than as a screen.
  ;; It is also the "show context" half of search, show context, expand on
  ;; demand: the results are the door, the nav is what you arrive next to.
  (let [v (full-view (with-main-load {:path "/store/search?q=rate"
                                           :screen :search :params {:q "rate"}}
                            :ready {:query "rate" :total 0
                                    :totals {:modules 0 :namespaces 0 :forms 0}
                                    :hits []}))
        local (views/find-region v :nav/local)]
    (is (some? local) "a Code screen carries the Code section's left pane")
    (is (some #(and (map? %) (= "ns-filter" (:id %))) (nodes local))
        "and that pane is the real one, not an empty box")
    (is (some #(and (map? %) (= "Code" (:label %)) (:active? %))
              (nodes (views/marked-sections "/store/search")))
        "the section bar says which section you are in")))

(deftest the-search-screen-answers-in-four-states-including-nothing-answered
  ;; Driven through `app-view` rather than `search-main` because a screen the
  ;; router reaches and the renderer does not is a white page, and the whole
  ;; point of the door is that it OPENS.
  (letfn [(text [v] (str/join " " (filter string? (nodes (views/find-region v :main)))))
          (view [q status v]
            (full-view (with-main-load {:path (str "/store/search?q=" q)
                                             :screen :search :params {:q q}}
                              status v)))]

    (testing "no query yet — the screen says what it searches rather than
              reporting nothing found, which is a different fact"
      (let [t (text (view "" :ready nil))]
        (is (re-find #"(?i)names, docstrings and recorded whys" t))
        (is (not (re-find #"(?i)nothing" t)))))

    (testing "a query, and NOTHING ANSWERED — the state this wave ships in.
              An absent endpoint must not render as an empty result: one is a
              gap in the API and the other is a fact about the store"
      (let [t (text (view "rate" :ready nil))]
        (is (re-find #"/api/search" t) "it names the endpoint that is missing")
        (is (not (re-find #"(?i)nothing in this store" t)))))

    (testing "a query that genuinely matches nothing NAMES the query — a bare
              'no results' leaves a reader unsure the box even took their text"
      (let [t (text (view "zzz" :ready {:query "zzz" :total 0
                                        :totals {:modules 0 :namespaces 0 :forms 0}
                                        :hits []}))]
        (is (re-find #"zzz" t))
        (is (re-find #"(?i)nothing in this store" t))))

    (testing "and a query with hits renders them"
      (let [t (text (view "rate" :ready
                          {:query "rate" :total 1
                           :totals {:modules 0 :namespaces 0 :forms 1}
                           :hits [{:kind "form" :name "rate" :address "/store/form/f1"
                                   :ns "demo.core" :matched "name" :rank 1.0}]}))]
        (is (re-find #"rate" t))
        (is (re-find #"1 hit" t))
        (is (not (re-find #"(?i)no screen renderer" t))
            "the router reaches :search, so the renderer must too")))))

(deftest the-foundation-count-is-a-sentence-not-a-pluralised-phrase
  ;; "19 modules, 88 namespaces — 5 of thems foundation", on the served page.
  ;; `plural` appends an s to whatever it is handed, which is right for a NOUN
  ;; and wrong for the phrase "of them" — and the call site read fine because
  ;; the bug lives in the interaction, not in either half.
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (let [index {:modules [{:module "a" :namespaces ["a"] :tests 0 :tier "pure"
                            :foundation true :deps []}
                           {:module "b" :namespaces ["b"] :tests 0 :tier "pure"
                            :foundation true :deps []}
                           {:module "c" :namespaces ["c"] :tests 0 :tier "pure"
                            :foundation false :deps []}]
                 :layers [["c"]] :cycles []}
          page  (fn [ms] (text (full-view
                                (with-main-load {:path "/store" :screen :code}
                                  :ready (assoc index :modules ms)))))]
      (testing "the fixture has more than one foundation module, which is the
                case that pluralises"
        (is (= 2 (count (filter :foundation (:modules index))))))
      (testing "no word gains an s that is not a noun"
        (is (not (re-find #"thems" (page (:modules index))))))
      (testing "and it still reads as English at both counts"
        (is (re-find #"2 of them" (page (:modules index))))
        (is (re-find #"1 of them"
                     (page (assoc-in (vec (:modules index)) [1 :foundation] false))))))))

(deftest a-truncated-graph-says-so-on-the-page
  ;; slopp caps the neighbourhood at 250 nodes and reports
  ;; `:truncated {:node-cap :depth-reached}` — the last COMPLETE level, never a
  ;; half-expanded frontier. Rendering that silently would be the worst version
  ;; of the silence problem: the spine SHOWS a path, and a reader takes its top
  ;; rung for a root. "This is where the walk stopped" and "this is where the
  ;; calls stop" are different facts and only one of them is true here.
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))
          (page [data] (full-view (with-main-load {:path "/store/form/f-rate"
                                                        :screen :form
                                                        :params {:id "f-rate"}}
                                         :ready data)))]
    (let [base {:form-id "f-rate" :name "rate" :form "post.tariff/rate"
                :ns "post.tariff" :module "post.tariff"
                :sig ["[kg]"] :doc "Rate it." :why "the subject"
                :warranty {:covered 1} :tokens [] :callers [] :callees []
                :graph {:depth 2
                        :nodes [{:form-id "f-rate" :form "rate" :ns "post.tariff"
                                 :module "post.tariff"}
                                {:form-id "f-quote" :form "quote" :ns "post.order"
                                 :module "post.order"}]
                        :edges [{:from "f-quote" :to "f-rate" :via "static"}]}
                :note "n"}]
      (testing "the fixture draws a spine at all, or the check below is vacuous"
        (is (re-find #"quote" (text (page base)))))

      ;; `cap` alone matched something else already on the page, which is the
      ;; sort of false negative a loose alternation buys. One distinctive word.
      (testing "an untruncated graph makes no claim about being cut off"
        (is (not (re-find #"(?i)truncated" (text (page base))))))

      (testing "a truncated one says where the walk stopped and why"
        (let [t (text (page (assoc-in base [:graph :truncated]
                                      {:node-cap 250 :depth-reached 1})))]
          (is (re-find #"(?i)truncated" t))
          (is (re-find #"250" t) "the cap is the number a reader can act on")
          (is (re-find #"1" t) "and how far it actually got"))))))

(deftest the-boundary-groups-by-the-module-at-its-far-end
  ;; On `slopp.edit` the real endpoint sends 32 inbound and 31 outbound rows,
  ;; and rendering one line per EDGE put `slopp.ops →` on screen nine times.
  ;; That is the fan-out failure in miniature: an unranked, ungrouped list long
  ;; enough that the thing a reader wants — WHICH modules depend on this, and
  ;; through what — is somewhere inside it rather than on it.
  (letfn [(page [shape] (full-view (with-main-load {:path "/store/module/demo.core"
                                                         :screen :module
                                                         :params {:module "demo.core"}}
                                          :ready shape)))
          (items [v] (->> (tree-seq coll? seq v)
                          (filter #(and (vector? %) (= :li (first %))))))]
    (let [shape {:module "demo.core" :tier "pure"
                 :namespaces [{:ns "demo.core" :forms 4 :deps []}]
                 :boundary {:in [{:from "demo.web.a" :from-module "demo.web" :to "demo.core"}
                                 {:from "demo.web.b" :from-module "demo.web" :to "demo.core.calc"}
                                 {:from "demo.web.a" :from-module "demo.web" :to "demo.core"}
                                 {:from "demo.job.z" :from-module "demo.job" :to "demo.core"}]
                            :out []}
                 :layers [] :cycles []}]
      (testing "the fixture repeats one far module across rows, or grouping is a no-op"
        (is (= 4 (count (:in (:boundary shape)))))
        (is (= 2 (count (distinct (map :from-module (:in (:boundary shape))))))))

      (let [v     (page shape)
            text  (fn [x] (str/join " " (filter string? (nodes x))))
            rows  (filter #(re-find #"demo\.(web|job)" (text %)) (items v))]
        (testing "one row per far MODULE, not per edge"
          (is (= 2 (count rows))
              "four edges from two modules must render as two rows"))
        (testing "each row carries how many edges it stands for"
          (let [web (first (filter #(re-find #"demo\.web" (text %)) rows))]
            (is (re-find #"3" (text web)) "demo.web accounts for three of the four")))
        (testing "and which namespaces inside this module it actually reaches,
                  deduplicated — demo.web.a arrives twice at the same place"
          (let [web (text (first (filter #(re-find #"demo\.web" (text %)) rows)))]
            (is (re-find #"core" web))
            (is (re-find #"calc" web))))))))

(deftest the-boundary-count-and-its-unit-name-the-same-thing
  ;; The mislabel that survived the last fix, because the index fallback made
  ;; it unfalsifiable: at module grain a row's `:from` and `:from-module` are
  ;; the SAME STRING, so counting one and naming the other agreed by accident.
  ;; The real endpoint separates them — `:from` is an outside NAMESPACE — and
  ;; the sentence started counting modules while saying namespaces.
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))
          (page [shape] (full-view (with-main-load {:path "/store/module/demo.core"
                                                         :screen :module
                                                         :params {:module "demo.core"}}
                                          :ready shape)))]
    (let [real {:module "demo.core" :tier "pure"
                :namespaces [{:ns "demo.core" :forms 4 :deps []}]
                ;; THREE rows, TWO distinct namespaces, ONE module — every
                ;; plausible wrong reading gives a different number
                :boundary {:in [{:from "demo.web.a" :from-module "demo.web" :to "demo.core"}
                                {:from "demo.web.b" :from-module "demo.web" :to "demo.core"}
                                {:from "demo.web.a" :from-module "demo.web" :to "demo.core"}]
                           :out [{:from "demo.core" :to "demo.util.x" :to-module "demo.util"}]}
                :layers [] :cycles []}
          t    (text (page real))]
      (testing "the fixture separates the two grains, or nothing below can fail"
        (is (= 2 (count (distinct (map :from (:in (:boundary real)))))))
        (is (= 1 (count (distinct (map :from-module (:in (:boundary real))))))))

      (testing "with namespace-grain rows the count is NAMESPACES and says so"
        (is (re-find #"used by 2 namespaces" t)
            "two distinct outside namespaces reach demo.core; one module holds them"))

      (testing "and it is not silently counting modules under a namespace label"
        (is (not (re-find #"used by 1 namespace" t))))

      (testing "module-grain rows still count MODULES and say module — the
                fallback path must not inherit the namespace wording"
        (let [index {:modules [{:module "demo.web" :namespaces ["demo.web"] :tests 0
                                :tier "pure" :foundation false :deps ["demo.core"]}
                               {:module "demo.core" :namespaces ["demo.core"] :tests 0
                                :tier "pure" :foundation false :deps []}]}
              ft    (text (page (views/module-from-index index "demo.core")))]
          (is (re-find #"used by 1 module" ft)))))))

(deftest the-module-summary-counts-only-what-it-actually-knows
  ;; Every one of these was found by opening the page, not by a test — the
  ;; assertions I wrote checked the NAMES on the boundary rows and never the
  ;; numbers beside them, so three false sentences rendered green.
  ;;
  ;; One root cause: at module grain a boundary row's near end is always the
  ;; module itself. Counting distinct near ends therefore always yields 1, and
  ;; labelling each row by it prints the page heading once per row.
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))
          (page [shape] (full-view (with-main-load {:path "/store/module/demo.core"
                                                         :screen :module
                                                         :params {:module "demo.core"}}
                                          :ready shape)))]
    (let [index {:modules [{:module "demo.web" :namespaces ["demo.web"] :tests 2
                            :tier "external" :foundation false :deps ["demo.core"]}
                           {:module "demo.job" :namespaces ["demo.job"] :tests 1
                            :tier "external" :foundation false :deps ["demo.core"]}
                           {:module "demo.core" :namespaces ["demo.core" "demo.core.calc"]
                            :tests 5 :tier "pure" :foundation false :deps ["demo.util"]}
                           {:module "demo.util" :namespaces ["demo.util"] :tests 0
                            :tier "pure" :foundation true :deps []}]
                 :layers [] :cycles []}
          t     (text (page (views/module-from-index index "demo.core")))]

      (testing "the fixture really has two consumers and one dependency"
        (is (= 2 (count (filter #(some #{"demo.core"} (:deps %)) (:modules index))))))

      (testing "an unknown form count is NOT rendered as zero — the index does
                not carry per-namespace form counts, and `0 forms` is a claim"
        (is (not (re-find #"0 forms" t))))

      (testing "the module-grain boundary is counted in MODULES, because the
                namespace end of every row is this module and counting it
                always yields one"
        (is (not (re-find #"1 of them reached from outside" t))
            "a count that is 1 for every module on every store says nothing")
        (is (re-find #"2 modules" t) "two modules reach demo.core")
        (is (re-find #"1 module" t)  "demo.core reaches one"))

      (testing "a boundary row leads with the module at its FAR end — the near
                end is the page you are already on"
        (let [in-rows (->> (nodes (page (views/module-from-index index "demo.core")))
                           (filter string?))]
          (is (some #(= "demo.web" %) in-rows))
          (is (some #(= "demo.job" %) in-rows))))

      (testing "and once namespace-grain edges arrive the counts are namespaces
                again — this must not harden into module-only"
        (let [real {:module "demo.core" :tier "pure"
                    :namespaces [{:ns "demo.core" :forms 4 :deps ["demo.core.calc"]}
                                 {:ns "demo.core.calc" :forms 9 :deps []}]
                    :boundary {:in [{:from "demo.web" :from-module "demo.web"
                                     :to "demo.core"}]
                               :out [{:from "demo.core.calc" :to "demo.util.x"
                                      :to-module "demo.util"}]}
                    :layers [] :cycles []}
              rt   (text (page real))]
          (is (re-find #"13 forms" rt) "forms are known now and must be summed"))))))

(defn render-var
  "The var that RENDERS a row target — the target itself when it is a bare fn,
  its `:render` when it is a screen value.

  A row is `[pattern screen]` and a screen that fetches is
  `{:render … :request … :derive …}`, so the thing a test wants to call, or to
  assert is openable, is one level in for some rows and not others.
  `slopp.webapp/derived-view` makes the same distinction; this is the reading
  half of it.

  Test-side on purpose: production never asks, because the framework calls the
  screen and this app's own code reaches the subject through
  `views/subject-of`."
  [target]
  (if (map? target) (:render target) target))

(defn page-subject
  "The SUBJECT `page-fn` passes to `chrome` — the arrow `screen-subject` used
  to be.

  That map is gone: a page states its own subject in the one call that needs
  it, so recovering it here means calling the page and catching what went past.
  `chrome` is redefined for the length of one call and answers a constant, so
  nothing renders; the page's own asks go nowhere because the stand-in page has
  a `:webapp/call` that never calls back, and a page left waiting still passes
  its subject on the way.

  A literal map here would be the second copy of a fact the code already
  states, which is exactly what the retired one was."
  [page-fn]
  (let [seen (atom nil)
        page {:webapp/state (atom {})
              :webapp/base  ""
              :webapp/call  (fn [_ _ _] nil)
              :state {} :params {}}]
    (with-redefs [pages/chrome (fn [_ subject _ _] (reset! seen subject) nil)]
      (page-fn page))
    @seen))

(deftest the-code-section-has-a-door-and-it-is-a-plain-form
  ;; The finding this wave exists for: `/store` opened on the module diagram
  ;; and the UI had no way in at all. Overview-first is the documented wrong
  ;; entry — search, show context, expand on demand — and a landing page is
  ;; not a door.
  (letfn [(screen [path]
            ;; the screen is ROUTED from the path. This used to pin `:screen
            ;; :code` while varying the path across three different sections,
            ;; so two of the three rendered a screen their own url does not
            ;; reach.
            ;;
            ;; It also used to take a `:base` — a mount point the framework
            ;; prefixed with. There is none: the prefix is the PROJECT, it is
            ;; the first segment of the address, and `full-view` supplies
            ;; `/p/demo` for a path that does not name one.
            (full-view (with-main-load {:path path}
                         :ready {:modules [] :layers [] :cycles []})))
          (head [path] (views/find-region (screen path) :nav/sections))
          (attrs [v] (filter map? (nodes v)))]

    (testing "the box is in the header, so it is on EVERY screen rather than
              only on the one you have to already be on"
      (doseq [path ["/store" "/store/ns/demo.core" "/store/form/f1"]]
        (is (some :action (attrs (head path))) (str "no search form on " path))))

    (testing "it is a GET form: `act` is pure state and navigating is an
              effect, and a form needs no bundle — the same argument the
              picker already makes for being script-free"
      (is (some #(= "get" (:method %)) (attrs (head "/store"))))
      (is (some #(= "q" (:name %)) (attrs (head "/store")))
          "the field is named q, which is the parameter the router reads"))

    (testing "the action carries the PROJECT like every other in-app url — an
              unprefixed one posts at the hub root, which is a different
              application"
      (is (some #(= "/p/slopp2/store/search" (:action %))
                (attrs (head "/p/slopp2/store")))))

    (testing "and the two halves agree: the url this form produces is one the
              router parses back to the same query. Composed from what the form
              RENDERED rather than from a literal, because a literal agrees
              with whatever it was written beside"
      (let [as     (attrs (head "/store"))
            action (some :action as)
            field  (some #(when (= "q" (:name %)) (:name %)) as)
                        m      (webapp/match-route (slopp.cljnx/marked-pages)
                                       (str action "?" field "=kg+zone"))]
        (is (= :search (page-subject (:screen m)))
            "the door emits ?q= at an address the router does not reach")
        ;; `:slug` dropped: every address captures the project it is inside,
        ;; and what these two halves have to agree about is the QUERY.
        (is (= {:q "kg zone"} (dissoc (:params m) :slug))
            "the door emits ?q= and the router reads ?q= — nothing else in the
             app makes those two agree")))

    (testing "on the search screen the box still holds what was searched — a
              door that forgets makes refining a query a retype"
      (let [v (full-view (with-main-load {:path "/store/search?q=rate"} :ready nil))]
        (is (some #(= "rate" (:value %))
                  (attrs (views/find-region v :nav/sections))))))))

(defn routed
  "What `path` reaches INSIDE a project, as `[subject params]` — or nil.

  The app's real routing in one place: `match-route` against the DERIVED table,
  then [[page-subject]] to name the page it found. `[:module {:module
  \"demo.core\"}]` is what a reader of a test wants to see; the page function
  itself is not.

  **`path` is project-relative and the slug is dropped from the answer.** Every
  address this app answers carries a tenant segment, so asking about
  `/store/gaps` means asking about `/p/demo/store/gaps` — but tenancy is not
  what these tests are about, and repeating `/p/demo` at every call site would
  bury the address under the thing being held constant.

  **`/` means the PROJECT's root here, not the landing**, and that is a real
  edge worth knowing rather than a wart: project-relative `/` is `/p/demo`,
  which is the Review screen. The landing is the absolute `/`, belonging to no
  project, and a caller that means THAT one addresses it directly — as
  `a-screen-the-router-can-reach-always-renders-something` does, because its
  paths are already whole addresses. Special-casing it here was tried and
  reverted: it made `at \"/\"` answer `:projects`, and `at` is project-relative
  by construction.

  A caller that IS about tenancy passes the whole address and this leaves it
  alone."
  [path]
  (let [p    (str path)
        addr (if (str/starts-with? p "/p/") p (str "/p/demo" p))]
    (when-let [m (webapp/match-route (slopp.cljnx/marked-pages) addr)]
      [(page-subject (:screen m)) (dissoc (:params m) :slug)])))

(deftest a-screen-the-router-can-reach-always-renders-something
  ;; The same shape as `every-registry-entry-can-actually-reach-the-screen`,
  ;; one level up.
  ;;
  ;; **The failure this was written for is now unrepresentable, and that is the
  ;; result rather than a reason to delete the test.** It read: `route-for`
  ;; produces a screen KEYWORD, `app-view` dispatches on it, the two are
  ;; related only by that keyword, and a `case` with no default turns the
  ;; disagreement into a white page. A route row names the screen FUNCTION now,
  ;; so there is no keyword to disagree about and no `case` to fall off — the
  ;; router and the renderer are one reference the graph can see.
  ;;
  ;; What is left to check is the half that survives: every address the router
  ;; reaches renders a main pane, and an address it does not reach degrades to
  ;; a panel that names it.
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (let [data {;; the LANDING, and the only screen here that is not inside a
                ;; project. It answers the HUB's own `/api/projects`, so its
                ;; data is a list of project rows rather than anything a store
                ;; publishes.
                :projects [{:slug "demo" :dir "/w/demo" :opened-at 0 :sessions 1 :cli false :app nil}]
                :timeline {:commit-points [] :working {:forms 0}}
                :change   {:from "a" :to "b" :count 0 :modules []}
                :code     {:modules [] :layers [] :cycles []}
                :ns       {:ns "demo.core" :forms []}
                :source   {:ns "demo.core" :name "hello" :source "()"}
                :form     {:form "demo.core/hello" :ns "demo.core" :module "demo"
                           :tokens [] :warranty {:covered 0}
                           :callers [] :callees [] :note "n"}
                :module   {:module "demo.thing" :tier "pure"
                           :namespaces [{:ns "demo.thing.core" :forms 3 :tier "pure"
                                         :deps []}]
                           :boundary {:out [] :in []}
                           :layers [["demo.thing.core"]] :cycles []}
                ;; THREE screens this map never gained, exposed the moment the
                ;; population stopped being a copy of itself. `:search`,
                ;; `:endpoints` and `:endpoint` have been reachable and
                ;; unchecked HERE since the day each was built.
                :search    {:query "" :total 0 :totals {} :hits []}
                ;; the Pages section. ROWS, like the API screens — both its
                ;; routes carry `:derive :paths`.
                ;; the whole DOCUMENT for these four, envelope and all. It used to be
                ;; the rows: the route carried `:derive :paths` and the framework
                ;; stripped the envelope before the screen saw it. `ask!` takes no
                ;; `:derive`, so the PAGE strips it — which means a fixture
                ;; answering with rows answers something the endpoint never
                ;; publishes, and `(:paths …)` of it is nil.
                :http-paths {:paths [{:path "/css/style.css" :method :get :name 'stylesheet
                                      :handler 'slopp.ui.styles/stylesheet :auth :public
                                      :media-type "text/css" :shape :text :bytes 14263}]}
                :http-path {:paths [{:path "/css/style.css" :method :get :name 'stylesheet
                                      :handler 'slopp.ui.styles/stylesheet :auth :public
                                      :media-type "text/css" :shape :text :bytes 14263}]}
                ;; the ROWS on both API screens, because their rows carry
                ;; `:derive :paths` — the `:main` value a screen receives is
                ;; the document with its envelope already stripped.
                :rest-paths {:paths [{:method :get :path "/api/modules"
                                      :name 'modules :request nil
                                      :response [:map [:a :string]]}]}
                :rest-path {:paths [{:method :get :path "/api/modules"
                                      :name 'modules :request nil
                                      :response [:map [:a :string]]}]}
                ;; the WEBAPP pair, the third document. `:calls` is present on
                ;; one and ABSENT on the other, because absent is slopp's rule
                ;; for a page the graph saw no endpoint in — and the branch that
                ;; says so in words is the one most likely to render as a blank.
                :webapp-pages {:paths [{:path "/p/:slug/store"
                                        :page 'slopp.ui.pages/code-page
                                        :doc "The Code index."
                                        :calls [{:endpoint 'slopp.ui.wire.api/modules
                                                 :method :get :path "/api/modules"}]}
                                       {:path "/p/:slug/about"
                                        :page 'slopp.ui.pages/about-page}]}
                :webapp-page  {:paths [{:path "/p/:slug/store"
                                        :page 'slopp.ui.pages/code-page
                                        :doc "The Code index."
                                        :calls [{:endpoint 'slopp.ui.wire.api/modules
                                                 :method :get :path "/api/modules"}]}]}
                ;; CONFIG, and the whole document with no envelope to strip:
                ;; `:config` is the rows, but `:owners` is the vocabulary the
                ;; owner column reads and the page hands the view all of it.
                ;; Two owners, because the page GROUPS by owner and a
                ;; single-owner fixture drives the grouping trivially.
                :config {:config [{:key "http.port" :owner "http" :effective 7359
                                   :set true :value "7359" :doc "The port."}
                                  {:key "app.name" :owner "app" :effective nil
                                   :doc "Application name."}]
                         :owners {"http" "an HTTP server." "app" "any project."}}
                ;; the DASHBOARD, and the only screen whose primary load is not
                ;; the whole story: `chrome` reads the namespace index, and the
                ;; timeline it also asks for is deliberately NOT seeded here.
                ;; So this row drives the degradation the page's docstring
                ;; promises — the counts stand and the cadence panel reports
                ;; nothing rather than the screen throwing over its lesser half.
                :dashboard [{:ns "demo.core" :forms 3} {:ns "demo.rate" :forms 1}]}
          ;; DERIVED from the patterns: `screens` holds `/store/ns/:ns`, which is
          ;; not a path, and `example-path` fills it from that row's own sample.
          ;; `:form` has two rows — two arities of one screen — and either proves
          ;; the dispatch, so one entry per SCREEN is what this map wants and
          ;; `into` keeping the last is correct rather than lucky.
          paths (into {} (map (juxt :screen #(views/example-path (:path %) (:sample %))))
                      views/screens)]

      (testing "the POPULATION is real — every screen below is one the router
                actually reaches from the path this test uses for it"
        (is (seq paths))
        (is (= (set (keys paths)) (set (keys data)))
            "a screen has data but no path, or the reverse")
        (doseq [[screen path] paths]
                              ;; addressed ABSOLUTELY rather than through `routed`, because these
          ;; paths are already whole addresses — `example-path` filled each
          ;; pattern from its own sample, tenant segment included. `routed` is
          ;; project-relative and would turn the landing's `/` into `/p/demo`,
          ;; which is the Review screen: the one address outside every project
          ;; rewritten into the one most inside one.
          (let [m (webapp/match-route (slopp.cljnx/marked-pages) path)]
            (is (some? m) (str path " matches no row"))
            (is (= screen (page-subject (:screen m)))
                (str path " does not reach " screen)))))

      (testing "every one of them renders a main pane rather than throwing"
        (doseq [[screen path] paths]
          (let [v (full-view (with-main-load {:path path} :ready (data screen)))]
            (is (some? (views/find-region v :main)) (str screen " rendered no main")))))

      (testing "the module screen names the module it opened"
        (let [v (full-view (with-main-load {:path "/store/module/demo.thing"}
                             :ready (data :module)))]
          (is (re-find #"demo\.thing" (text v)))))

      (testing "an address no row matches degrades to a panel NAMING it, rather
                than throwing. This replaces a check for a screen the renderer
                had no branch for — that state cannot be built any more, because
                the row carries the function"
        (let [v (full-view (with-main-load {:path "/x/invented"} :ready {}))]
          (is (re-find #"invented" (text v))
              "the unroutable path is not named, so a reader cannot tell which
               link was wrong")
          (is (some? (views/find-region v :main))
              "not-found must be a PANEL in the app, not a blank page")))

      (testing "and that panel does NOT appear for an address that does render —
                otherwise the check above passes on a view that always says it"
        (let [v (full-view (with-main-load {:path "/"} :ready (data :timeline)))]
          (is (not (re-find #"(?i)not found" (text v)))))))))

(deftest a-lens-is-a-url-segment-and-an-unknown-one-is-not-a-default
  ;; The whole point of putting the lens in the path rather than in client
  ;; state: the address bar is a persistent, always-visible rendering of view
  ;; state, and Back is the only undo affordance every user already knows.
  ;; Desktop tools structurally cannot have that — undo is reserved for
  ;; actions that change data, not ones that change the view.
  ;;
  ;; **That argument is now the implementation rather than a policy.** Under
  ;; `route-for` the lens was parsed off the path INTO `:lens` in state, so the
  ;; url and the state were two representations that had to be kept in step.
  ;; A lens is a declared ROW now — `/store/gaps` names `code-gaps-screen` —
  ;; so there is no second copy to disagree, and `:lens` has left state
  ;; entirely. What a reader shares is what the app renders, by construction.
  (let [at     routed
        sample ;; only the screens that OFFER a lens. `:module` and `:ns` had entries
        ;; here while rendering nothing for them — a code-navigation gap. The
        ;; key-set assertion below is what caught their removal, which is the
        ;; pairing check doing its job.
        {:code "/store"
         :form "/store/form/f123"}]
    (testing "the POPULATIONS are non-empty — every check below iterates the
              table, and over an empty one they all pass by having nothing to
              disagree with"
      (is (seq views/lenses))
      (is (every? seq (vals views/lenses)))
      (is (= (set (keys views/lenses)) (set (keys sample)))
          "a screen gained or lost lenses and this test did not notice"))

    (testing "the default lens is the bare subject path — so a screen that
              never switches renders exactly as it always did"
      (is (= [:code {}] (at "/store")))
      (is (= "/store" (views/with-lens "/store" nil))))

    (testing "every lens a screen offers round-trips through the URL to the
              same SUBJECT"
      (doseq [[screen ls] views/lenses
              lens        ls]
        (let [subject (sample screen)
              addr    (views/with-lens subject lens)]
          (is (some? (at addr)) addr)
          (is (= screen (first (at addr))) addr))))

    (testing "a lens preserves the SUBJECT — a switch that drops you somewhere
              else is a navigation, not a lens"
      (doseq [[screen ls] views/lenses
              lens        ls]
        (let [subject (sample screen)]
          (is (= (second (at subject))
                 (second (at (views/with-lens subject lens))))
              (str subject " / " lens)))))

    (testing "an unrecognised lens resolves to NOTHING, never to the default —
              silently showing the default view for a URL that named a lens is
              how a reader ends up certain they looked at something they did not.
              Under the row table this needs no lens-validity check at all: the
              address is simply not in the table"
      (is (nil? (at "/store/form/f123/nonsense")))
      (is (nil? (at "/store/nonsense")))
      (is (nil? (at "/store/ns/demo.core/nonsense"))))

    (testing "and the finder can MISS — otherwise the round-trip above proves
              only that the router returns something for everything"
      (is (not-any? #(= "nonsense" %) (mapcat val views/lenses))))))

(deftest each-screen-has-a-route-and-every-section-link-parses
  ;; With the server rendering gone, THIS is the routing table. A path it
  ;; cannot parse is a blank screen, so the mapping is pinned here rather
  ;; than discovered in a browser.
  ;;
  ;; **The universal this test could not state is now stated elsewhere, and
  ;; that paragraph is worth keeping because it came true.** It used to say the
  ;; client/server comparison was *"not closeable from here as written: the
  ;; server declares its SPA prefixes as DATA while `route-for` is a parser
  ;; rather than a table, so there is no client-side population to compare
  ;; against. Making the universal true means making the routes enumerable
  ;; first."*
  ;;
  ;; The routes ARE enumerable now — `views/client-routes` is the table the
  ;; framework routes on — and `hub-test/every-client-route-survives-a-hard-load`
  ;; makes exactly that comparison, over every address rather than a list
  ;; somebody maintains. The prediction named the blocker correctly and the
  ;; migration removed it.
  ;;
  ;; What is left here is the mapping itself: which address reaches which
  ;; subject with which params.
  (let [at routed]
    (testing "each screen's URL resolves to its route and params"
      (is (= [:timeline {}] (at "/")))
      (is (= [:change {:range "d1..d2"}] (at "/change/d1..d2")))
      (is (= [:code {}] (at "/store")))
      (is (= [:ns {:ns "slopp.api"}] (at "/store/ns/slopp.api")))
      ;; a made-up ns/name on purpose: a fixture naming a REAL var reads as a
      ;; reference to it, so the stale-reference advisory fires when that var
      ;; moves even though nothing here depends on it
      (is (= [:source {:ns "demo.core" :name "hello"}]
             (at "/store/source/demo.core/hello")))
      (is (= [:form {:id "f123"}] (at "/store/form/f123")))
      ;; the module screen is the rung between the diagram and a namespace:
      ;; a box on /store opens the graph of what is INSIDE that module
      (is (= [:module {:module "demo.thing"}] (at "/store/module/demo.thing")))
      ;; the DOOR. First row in this table whose subject is text a reader typed
      ;; rather than a name the store owns, which is why it is the first to
      ;; need a query string at all
      (is (= [:search {:q "rate"}] (at "/store/search?q=rate"))))

    (testing "the bare search address carries NO :q, where the old router
              supplied \"\" from `screens`' `:defaults` — a framework row has
              nowhere to put a default"
      (is (= [:search {}] (at "/store/search")))
      (testing "and that is a difference with no consequence, measured rather
                than assumed: `search-main` renders the same page for a nil
                query as for an empty one, which is what its `:defaults` entry
                existed to guarantee in the first place"
        (is (= (views/search-main nil nil) (views/search-main nil "")))))

    (testing "the lens addresses reach the same subject as their bare one"
      (is (= [:code {}] (at "/store/table")))
      (is (= [:code {}] (at "/store/gaps")))
      (is (= [:form {:id "f1"}] (at "/store/form/f1/source"))))

    (testing "a trailing slash is the same screen, not a different one"
      ;; /store/ and /store are the same place to a reader, and a router that
      ;; disagrees produces a blank screen for a URL that looks right
      (is (= [:code {}] (at "/store/"))))

    (testing "an unknown path resolves to NOTHING, never to a guess"
      ;; the SPA equivalent of a 404 — rendering the timeline for an unknown
      ;; URL would tell the reader they are somewhere they are not
      (is (nil? (at "/nonsense")))
      (is (nil? (at "/store/nonsense")))
      (is (nil? (at "/store/form")))
      (is (nil? (at "/store/source/only-one-segment"))))

    (testing "names with dots and bangs survive — they are ordinary in Clojure"
      (is (= [:source {:ns "a.b.c" :name "swap!"}]
             (at "/store/source/a.b.c/swap!"))))

    (testing "every route the app links to is one this router can parse"
      ;; the two halves have to agree or a link goes nowhere; `sections` is the
      ;; global nav, so it is the one list that must round-trip
      (doseq [{:keys [href]} views/sections]
        (is (some? (at href)) href)))))

(deftest a-query-string-is-data-rather-than-part-of-a-name
  ;; Separate from the routing TABLE above because it is about a capability the
  ;; router did not have, not another row: nothing in this app had ever emitted
  ;; a query string, so the router split on "/" and nothing else, and the
  ;; defect sat there being true.
  ;;
  ;; **The router is `slopp.webapp/match-route` now, so these assert a property
  ;; of the framework rather than of this app.** Kept anyway, and deliberately:
  ;; the search door is the one screen whose subject is text a reader typed,
  ;; and if query handling ever regresses upstream this app is where it shows.
  ;; A consumer test of a dependency's promise is worth having exactly when the
  ;; consumer would be the one telling the story.
  (testing "the latent bug, measured on the live image before this was written:
            a query string became part of the NAME it followed"
    (is (= [:ns {:ns "demo.core" :x "1"}] (routed "/store/ns/demo.core?x=1"))
        "the namespace is demo.core — it was demo.core?x=1"))
  (testing "every parameter arrives, because a screen that does not read one
            simply ignores it — a router deciding which are allowed is a
            second table to keep in step with the screens"
    (is (= {:q "rate" :limit "20"}
           (second (routed "/store/search?q=rate&limit=20")))))
  (testing "a lens address still parses UNDER a query string. This used to be
            about peeling the last PATH segment and not the query; a lens is a
            declared row now, so what it checks is that the query does not
            change which row matches"
    (is (= [:form {:id "f1" :q "x"}] (routed "/store/form/f1/source?q=x"))))
  (testing "an unknown path stays nil however it is decorated — a query string
            is not a way to smuggle a route past the 404"
    (is (nil? (routed "/nonsense?q=1")))
    (is (nil? (routed "/store/nonsense?q=1")))))

(deftest the-api-section-is-an-index-and-a-page-per-endpoint
  ;; Every endpoint's full schemas on ONE page does not scale — nine was
  ;; already long and a real app has fifty. Same shape every API browser
  ;; converges on, and this app already has the pieces: a path IS the address,
  ;; so one endpoint is a route rather than an expanded row, and Back undoes
  ;; opening it.
  ;;
  ;; **Addressed by METHOD and PATH, not by `:name`.** The name is the
  ;; generated client's fn — the join key to CODE — and this screen documents
  ;; the application's HTTP surface, where the join key is the request itself.
  ;; `endpoint-nav` already rendered method and path as the row identity and
  ;; said why in its own docstring — *neither identifies an endpoint alone* —
  ;; while addressing the row by a third thing.
  ;;
  ;; OpenAPI tools go the other way and key on `operationId`, because a spec
  ;; may not carry a usable one and method+path is the only identity always
  ;; present. slopp's contract carries BOTH and neither is junk, so that
  ;; pressure does not apply here.
  ;;
  ;; **The section is REST and its addresses live under it** — `/rest/paths`,
  ;; beside `/http/paths` and `/webapp/pages`. It was `/endpoints`, one word
  ;; for one of the three documents slopp publishes; see `views/sections`.
  (let [;; the ROWS: the page strips the document envelope, so this is what its
        ;; own ask holds.
        doc  [{:method :get :path "/api/modules" :name 'modules
               :request nil :response [:map [:modules [:sequential :map]]]}
              {:method :get :path "/api/module/:m" :name 'module
               :request nil :response [:map [:module :string]]}
              {:method :post :path "/api/register" :name 'register!
               :request [:map [:dir :string]] :response [:map [:slug :string]]}]
        ;; through the app's OWN builder rather than a literal spelled here. It
        ;; was `(str "/endpoints/" method "/" path)` — a second copy of the
        ;; address pattern, which went stale the moment the section moved.
        ;; `endpoint-address` is what the index and the rail both link, so a
        ;; test built on it cannot describe an address the app does not make.
        at   views/endpoint-address
        view (fn [screen params]
               (full-view (with-main-load {:path (if (= :rest-path screen)
                                                   (at params)
                                                   "/rest/paths")
                                           :screen screen :params params}
                            :ready doc)))
        main (fn [screen params] (views/find-region (view screen params) :main))
        text (fn [v] (str/join " " (filter string? (nodes v))))]

    (testing "an endpoint's own address is a ROUTE, so opening one is
              navigation and Back closes it — the thing an expand-in-place row
              cannot do"
      ;; `:*`, because these assert what the ROUTER hands over and slopp's
      ;; wildcards are anonymous — the route ends in `**`. The screen translates
      ;; it into this app's `:path` vocabulary through `views/routed-address`;
      ;; asserting the translated shape here would be pinning the translation
      ;; rather than the routing, and would stay green if the route pattern
      ;; itself stopped matching.
      (is (= [:rest-path {:method "get" :* "api/modules"}]
             (routed "/rest/paths/get/api/modules")))
      (is (= [:rest-path {:method "post" :* "api/register"}]
             (routed "/rest/paths/post/api/register")))
      (is (= [:rest-paths {}] (routed "/rest/paths"))))

    (testing "a path PARAMETER rides in the address as a literal segment — the
              thing being addressed is the pattern, not one call of it"
      (is (= [:rest-path {:method "get" :* "api/module/:m"}]
             (routed "/rest/paths/get/api/module/:m"))))

    (testing "the INDEX names every endpoint and expands none of them. The
              response type is a phrase, not a field list — one line per
              endpoint is the whole point"
      (let [t (text (main :rest-paths {}))]
        (is (re-find #"GET /api/modules" t))
        (is (re-find #"POST /api/register" t))
        (is (not (re-find #"no request body" t))
            "no per-endpoint request detail on the index")
        (is (not-any? #(and (vector? %) (= "schema-fields" (:class (second %))))
                      (nodes (main :rest-paths {})))
            "and no field lists at all")))

    (testing "every index row links to that endpoint's own page, by the request
              it names rather than by the fn that serves it"
      (is (some #(and (vector? %) (= :a (first %))
                      (= "/p/demo/rest/paths/get/api/modules" (:href (second %))))
                (nodes (main :rest-paths {}))))
      (is (some #(and (vector? %) (= :a (first %))
                      (= "/p/demo/rest/paths/post/api/register" (:href (second %))))
                (nodes (main :rest-paths {})))
          "the method is part of the address, so two verbs on one path stay
           distinguishable"))

    (testing "the PAGE shows one endpoint in full, and only that one"
      (let [t (text (main :rest-path {:method "get" :path "api/module/:m"}))]
        (is (re-find #"GET /api/module/:m" t))
        (is (re-find #"path parameters: m" t))
        (is (not (re-find #"/api/register" t))
            "the other endpoints are not on this page")))

    (testing "METHOD is part of the identity, not decoration — the same path
              under a verb the document does not declare is not that endpoint"
      (let [t (text (main :rest-path {:method "delete" :path "api/register"}))]
        (is (re-find #"no endpoint" t))))

    (testing "an unknown address says so rather than rendering an empty page,
              and names what was asked for"
      (let [t (text (main :rest-path {:method "get" :path "api/nope"}))]
        (is (re-find #"/api/nope" t))
        (is (re-find #"no endpoint" t))))

    (testing "the RAIL lists every endpoint on both screens, with the open one
              marked — a fifty-endpoint app needs a way across that is not the
              index page"
      (doseq [[screen params] [[:rest-paths {}]
                               [:rest-path {:method "get" :path "api/module/:m"}]]]
        (let [rail (views/find-region (view screen params) :nav/local)]
          (is (some? rail) (str "rail on " screen))
          (is (re-find #"/api/modules" (text rail)))
          (is (re-find #"/api/register" (text rail)))))
      (let [rail (views/find-region (view :rest-path {:method "get" :path "api/module/:m"})
                                    :nav/local)]
        (is (some #(and (vector? %) (map? (second %))
                        (= "active" (:class (second %)))
                        (= "/p/demo/rest/paths/get/api/module/:m" (:href (second %))))
                  (nodes rail))
            "the endpoint you are reading is marked in the rail")))))

(deftest the-router-cannot-reach-a-screen-the-table-does-not-list
  ;; The gap `views/screens` had to record as OPEN when it was a description:
  ;; nothing stopped a screen existing in a dispatch `case` and not in the
  ;; table, so the registry could check that everything it listed worked and
  ;; not that it listed everything.
  ;;
  ;; **It is closed twice over now.** A route names the page FUNCTION, so
  ;; routing to a screen IS holding a reference to it — there is no keyword in
  ;; between to mistype and no `case` to fall off. This pins the population so
  ;; a future edit that reintroduces a hand-written branch fails here rather
  ;; than quietly reopening it.
  (testing "every screen the router can produce is one the table declares"
    (let [declared (set (map :screen views/screens))]
      ;; the paths a reader could plausibly type, including several that are
      ;; near-misses for real routes — none may yield a screen off the table
      (doseq [p ["/" "/store" "/store/search" "/rest/paths" "/change/d1..d2"
                 "/store/ns/demo.core" "/store/module/demo" "/store/form/f1"
                 "/store/form/f1/through/f2" "/store/source/a/b" "/rest/paths/x"
                 "/store/table" "/store/gaps" "/store/form/f1/source"
                 "/store/nonsense" "/store/ns" "/store/ns/a/b" "/nope"
                 "/rest/paths/x/y" "/change" "/store/form"
                 "/http/paths" "/http/paths/a/b" "/http/paths/a"
                 "/webapp/pages" "/webapp/pages/a/b" "/webapp/pages/a"
                 ;; the SECTION roots, which are not addresses: a section's
                 ;; landing is a page inside it, so `/rest` itself routes to
                 ;; nothing and must not be quietly answered by a neighbour.
                 "/rest" "/http" "/webapp"]]
        (when-let [subject (first (routed p))]
          (is (contains? declared subject)
              (str p " routed to " subject ", which views/screens does not list"))))))

  (testing "and the reachable set is not empty — a router that matched nothing
            would satisfy the loop above by never entering it"
    (is (seq (keep #(first (routed %)) ["/" "/store" "/rest/paths"]))))

  (testing "and the whole population is pinned. A screen added to the app and
            not to the table is now impossible to route to at all; a screen
            removed shows up as this set changing rather than as a url that
            quietly stops working"
    (is (= #{:projects :ns :source :search :module :code :form :timeline :change
             ;; the three CAPABILITIES, two screens each — an index and a
             ;; detail. They were `:endpoints`/`:endpoint` and `:pages`/`:page`,
             ;; one pair per document with no word saying which document; the
             ;; names carry the capability now, the same way the addresses and
             ;; the sections do.
             :rest-paths :rest-path
             :http-paths :http-path
             ;; the webapp half, added 2026-08-27 — a project's own browser
             ;; app, which `/api/webapp/paths` publishes with `:calls`.
             :webapp-pages :webapp-page
             ;; Config, added 2026-08-27, and the only section with ONE screen:
             ;; its landing is its only page, because `/api/config` is one
             ;; document covering every owner rather than a list to drill into.
             :config
             ;; the Dashboard, added 2026-09-05 — the only screen that JOINS
             ;; two documents rather than unpacking one, and so the only one
             ;; whose canned answer is named for the SCREEN because no single
             ;; document is what it shows.
             :dashboard}
           (set (map :screen views/screens))))))

(deftest a-hits-address-is-BUILT-here-and-round-trips-through-the-router
  ;; slopp declined to send `:address`, and the reasoning is one this project
  ;; agrees with: emitting `/store/…` would put THIS app's routing scheme in
  ;; the producer, in units it does not own, with nothing on either side able
  ;; to check it — a route change here would silently falsify strings there.
  ;;
  ;; Which leaves the obvious objection: the scheme now has a builder AND a
  ;; parser. This test is the answer. Every address is fed back through
  ;; `route-for`, so the two are pinned against each other rather than written
  ;; beside each other and trusted. Same technique as the door's round-trip.
  (testing "each kind addresses the screen that shows it, and the router agrees"
    (is (= [:module {:module "demo.core"}]
           (routed (views/address-for {:kind "module" :name "demo.core"}))))
    (is (= [:ns {:ns "demo.core.calc"}]
           (routed (views/address-for {:kind "namespace" :name "demo.core.calc"
                                       :module "demo.core"}))))
    (is (= [:form {:id "f1"}]
           (routed (views/address-for {:kind "form" :name "demo.core/rate"
                                       :ns "demo.core" :form-id "f1"})))))

  (testing "a form with no :form-id still goes SOMEWHERE plainer rather than
            becoming dead text — the same fallback form-href already makes,
            and the case where a reader most needs the escape hatch"
    (is (= [:source {:ns "demo.core" :name "rate"}]
           (routed (views/address-for {:kind "form" :name "demo.core/rate"
                                       :ns "demo.core"})))))

  (testing "a form's :name arrives QUALIFIED — measured on the live listener,
            where slopp.index.refs/refs came back beside :ns slopp.index.refs —
            so the row shows the bare name and lets the context say the rest,
            rather than printing the namespace twice on one line"
    (is (= "rate" (views/hit-name {:kind "form" :name "demo.core/rate"})))
    (is (= "demo.core.calc" (views/hit-name {:kind "namespace" :name "demo.core.calc"}))
        "a namespace's name is not qualified and must not be truncated")
    (is (= "/" (views/hit-name {:kind "form" :name "clojure.core//"}))
        "the division fn is the one name whose bare half IS a slash")))

(defn lens-addresses
  "Every ADDRESS that `views/lenses` implies, derived from the two tables that
  own the halves — `views/screens` for the bare path, `views/lenses` for the
  segment. Each entry carries `:bare` too, so a caller comparing the lens row
  against the bare row does not derive the path a third time.

  One function because three tests need it and a fold copied three times is
  three chances to derive a different set than the one under test. It is
  deliberately NOT what `views/client-routes` declares: these tests exist to
  compare the derivation against the declaration, so sharing the declaration
  would make them pass by tautology.

  The BARE row per screen: `:form` appears twice in `screens` and the lens
  hangs off `/store/form/:id` only — `form-main` builds it as
  `(with-lens (str \"/store/form/\" id) …)`, never off the `through` address."
  []
  (let [bare (fn [screen] (:path (first (filter #(= screen (:screen %)) views/screens))))]
    (vec (for [[screen ls] views/lenses, l ls]
           {:screen screen :lens l
            :bare (bare screen) :address (views/with-lens (bare screen) l)}))))

(deftest every-declared-lens-actually-renders-something-different
  ;; `lenses` is read by three things: it decides whether a trailing segment is
  ;; a lens or nonsense, `with-lens` builds the hrefs, and the switcher renders
  ;; the chrome. So a lens in this table is a URL declared VALID — and five of
  ;; the seven once had no rendering branch at all. `/store/table` answered 200
  ;; and drew the plain diagram, which is the failure an unknown lens is
  ;; refused for: rendering the default view for a url that asked for a lens
  ;; leaves a reader certain they looked at something they did not.
  ;;
  ;; There is a FOURTH reader: each lens address is a PAGE whose body spells the
  ;; lens as a literal. The last block is what keeps that spelling honest.
  (letfn [(main [path params data]
            (full-view (with-main-load {:path path :params params} :ready data)))
          (text [v] (str/join " " (filter string? (nodes (views/find-region v :main)))))]
    (let [data {:code   {:modules [{:module "demo.core" :namespaces ["demo.core"]
                                    :tests 1 :tier "pure" :foundation false :deps []
                                    :gaps {:forms 4 :no-doc 1 :no-why 1 :uncovered 1}}]
                         :layers [["demo.core"]] :cycles []}
                :module {:module "demo.core" :tier "pure"
                         :namespaces [{:ns "demo.core" :forms 4 :tier "pure" :deps []}]
                         :boundary {:in [] :out []} :layers [["demo.core"]] :cycles []}
                :ns     {:ns "demo.core" :tier "pure" :forms []}
                :form   {:form "demo.core/rate" :form-id "f1" :ns "demo.core"
                         :module "demo.core" :tokens [] :warranty {:covered 1}
                         :callers [] :callees [] :note "n"}}
          ;; whole addresses, because these go to `match-route` directly rather
          ;; than through `routed` — and every address carries its project.
          paths {:code "/p/demo/store" :module "/p/demo/store/module/demo.core"
                 :ns "/p/demo/store/ns/demo.core" :form "/p/demo/store/form/f1"}
          params {:code {} :module {:module "demo.core"}
                  :ns {:ns "demo.core"} :form {:id "f1"}}]

      (testing "every lens the table declares is one the router resolves —
                otherwise the table is chrome for a dead url"
        (doseq [[screen ls] views/lenses, l ls]
          (let [addr (str (paths screen) "/" l)
                m    (webapp/match-route (slopp.cljnx/marked-pages) addr)]
            (is (some? m) (str addr " matches no page"))
            (is (= screen (page-subject (:screen m)))
                (str addr " reaches a page whose subject is not " screen))
            ;; `:slug` dropped alongside `:lens`: every address captures the
            ;; project it is inside, and what this asserts is that a lens
            ;; address captures the same SUBJECT params as its bare path.
            (is (= (params screen) (dissoc (:params m) :lens :slug))
                (str addr " routes with unexpected params")))))

      (testing "and every one of them renders something the DEFAULT does not.
                A lens that draws the default view is a url that lies, which is
                strictly worse than a 404 — the reader believes they saw it"
        (doseq [[screen ls] views/lenses, l ls]
          (let [bare   (text (main (paths screen) (params screen) (data screen)))
                lensed (text (main (str (paths screen) "/" l) (params screen) (data screen)))]
            (is (not= bare lensed)
                (str screen " lens " (pr-str l) " renders exactly the default view")))))

      (testing "and the lens PAGE renders what the lens renders. Each of
                `code-table-page`, `code-gaps-page` and `form-source-page`
                spells its lens as a literal, which is a THIRD copy of the
                string beside this table and its own `:webapp/path`. Rendering
                the bare address WITH that lens in state and comparing against
                the lens address is red the moment any of the three drifts"
        ;; through the rendered view rather than by calling the two targets. A
        ;; page takes the app's own map now — `:state`, `:params` and the keys
        ;; that let it ask — so handing one a bare state renders nothing and
        ;; the comparison would be nil against nil.
        (doseq [{:keys [screen lens bare address]} (lens-addresses)]
          (testing (str address)
            (let [with-lens (text (full-view (-> (with-main-load {:path bare} :ready (data screen))
                                                 (assoc :lens lens))))
                  at-lens   (text (main address (params screen) (data screen)))]
              (is (= with-lens at-lens)
                  (str address " renders differently from " bare
                       " carrying :lens " (pr-str lens)
                       " — the literal in the lens page and `lenses` have drifted")))))))))

(deftest the-declared-table-and-the-router-table-are-one-fact
  ;; The route table is what slopp reads to join a rendered link to a screen;
  ;; `views/screens` plus `views/lenses` is the vocabulary the rest of this app
  ;; speaks. Two representations of one fact, and this assertion is what makes
  ;; drift a red instead of a silent divergence.
  ;;
  ;; **One side of the duplicate is gone.** The table used to be written out as
  ;; `views/client-routes` beside the markers; it is DERIVED from them now, so
  ;; what this compares is the addresses the pages themselves declare against
  ;; the ones `screens` and `lenses` describe. The remaining duplicate exists
  ;; because `screens` carries `:sample` and `:defaults`, which have no home on
  ;; a marker.
  ;;
  ;; **The declared side is ADDRESSES, so lenses count.** A lens is not a
  ;; screen, which is why `screens` does not list one; it IS an address, which
  ;; is why a page must carry it.
  (let [declared (map first (slopp.cljnx/marked-pages))
        routed   (concat (map :path views/screens)
                         (map :address (lens-addresses)))]

    (testing "both tables are real — an empty one on either side would make the
              comparison below pass by having nothing to disagree about"
      (is (seq declared))
      (is (seq routed))
      (testing "and the lens half is genuinely in the population, or this
                reverts to the screens-only check it replaced without saying so"
        (is (seq (lens-addresses)))))

    (testing "no pattern is declared twice — two pages claiming one address"
      (is (= (count declared) (count (distinct declared)))
          (str "duplicate patterns: "
               (pr-str (map key (filter #(< 1 (val %)) (frequencies declared)))))))

    (testing "nor does a lens address collide with a screen's own pattern —
              they are folded into one set below, so a collision would hide"
      (is (= (count routed) (count (distinct routed)))
          (str "an address is both a screen and a lens: "
               (pr-str (map key (filter #(< 1 (val %)) (frequencies routed)))))))

    (testing "and they name the same routes"
      (is (= (set declared) (set routed))
          (str "declared but not routed: "
               (pr-str (sort (remove (set routed) declared)))
               " / routed but not declared: "
               (pr-str (sort (remove (set declared) routed))))))))

(deftest every-lens-address-is-a-declared-row
  ;; A lens is an ADDRESS, and this is the assertion that makes it one.
  ;;
  ;; `match-route` does no peeling: a lens url either matches a row or reaches
  ;; nothing, so a lens without an address of its own is a 404 for three
  ;; addresses this app really serves. That is the STRONGER shape — `/store/bogus`
  ;; matches no row and is not-found, where a peel-off parser had to consult
  ;; `lenses` to reject it. The validity check stops being a second table read
  ;; at runtime and becomes the address table itself.
  ;;
  ;; `lenses` still owns the hrefs and the switcher chrome, so the two must
  ;; agree — that agreement is exactly what this test is.
  ;;
  ;; **The table is DERIVED now**, from the `^{:webapp/path …}` markers, so this
  ;; also asserts that each lens page carries its own marker: an address that
  ;; exists only in `lenses` reaches nothing at all.
  (let [addrs    (lens-addresses)
        declared (slopp.cljnx/marked-pages)]

    (testing "the population is real — deriving addresses from two tables that
              are both empty would assert nothing at all"
      (is (seq addrs))
      (is (seq declared))
      (is (= 3 (count addrs))
          (str "expected the three lens addresses, got " (pr-str (mapv :address addrs)))))

    (doseq [{:keys [screen lens address]} addrs]
      (testing (str "the lens address " (pr-str address) " is a declared row")
        (let [m (webapp/match-route declared address)]
          (is (some? m)
              (str screen " lens " (pr-str lens) " at " address
                   " matches no page — the url 404s under the framework router"))
          ;; a PAGE, which is a function of one map. `marked-pages` stores the
          ;; deref'd value rather than the var, so there is no var to check —
          ;; what a row owes is that it is callable and takes the page map.
          (is (fn? (:screen m))
              (str address " routes to something that is not a page function")))))))

(deftest every-row-target-has-a-subject
  ;; A subject is what `lens-bar` looks up, what places a screen in a section
  ;; and what decides which rail is drawn. A page that states none reads as
  ;; nil, and nil is not an error anywhere it lands: no bar, no section, no
  ;; rail. Every one of those is a screen that looks slightly wrong rather than
  ;; one that fails — this project's most expensive bug class.
  ;;
  ;; **`screen-subject` is gone and this is what replaced it.** It was a map
  ;; from a row's target var to a keyword, and its failure was an entry someone
  ;; forgot. A page passes its own subject to `chrome`, so the fact is stated
  ;; where it is used — and what is left to check is that every routable page
  ;; states one, and that what it states is vocabulary the other tables speak.
  ;;
  ;; The population is the ROUTE TABLE rather than a list here, because what
  ;; must be total is the arrow from what the router can actually reach.
  (let [pages    (map second (slopp.cljnx/marked-pages))
        subjects (set (map :screen views/screens))]

    (testing "the population is real — an empty route table would make the loop
              below pass by iterating nothing"
      (is (seq pages))
      (is (seq subjects)))

    (testing "every page the router can reach states a subject"
      (doseq [[pattern page-fn] (slopp.cljnx/marked-pages)]
        (testing pattern
          (is (some? (page-subject page-fn))
              (str pattern " renders without passing a subject to chrome —"
                   " `lens-bar` would draw no bar for it and the nav would not"
                   " place it in a section, both silently")))))

    (testing "and every subject it states is one the other tables already
              speak, or a page has invented a vocabulary nothing else reads"
      (doseq [[pattern page-fn] (slopp.cljnx/marked-pages)]
        (let [s (page-subject page-fn)]
          (is (contains? subjects s)
              (str pattern " claims subject " (pr-str s)
                   ", which is not a `:screen` in `views/screens` — the"
                   " subjects are " (pr-str (sort subjects)))))))

    (testing "and every subject that declares LENSES is carried by some page,
              since a lens bar is drawn from the subject"
      (let [stated (set (map page-subject pages))]
        (doseq [[s _] views/lenses]
          (is (contains? stated s)
              (str "subject " (pr-str s) " declares lenses but no page carries"
                   " it, so the bar would never render")))))))

(deftest the-current-lens-is-read-from-the-ADDRESS
  ;; Under `route-for` the lens rode in state: the router parsed it off the
  ;; path and `nav/navigate` cleared it with the route. Under the framework
  ;; neither happens — `arrive` writes `:path`, `:screen` and `:params` and
  ;; nothing else, and `:lens` is an address key that dies on every move. So
  ;; the fact "which lens am I looking at" has exactly one source left, which
  ;; is the address itself.
  ;;
  ;; That is a simplification rather than a loss. The lens was in state, in the
  ;; url, and implied by the screen var, and those three could disagree. Now
  ;; the url is the only one that can answer.
  ;;
  ;; A trailing segment that is not a DECLARED lens for that subject is not a
  ;; lens — same refusal `route-for` made, for the same reason: marking a bar
  ;; entry active because the url ends in a word leaves a reader certain they
  ;; are looking at something they are not.
  (testing "a declared lens is read off the end of the path"
    (is (= "table" (views/current-lens "/store/table" :code)))
    (is (= "gaps" (views/current-lens "/store/gaps" :code)))
    (is (= "source" (views/current-lens "/store/form/f1/source" :form))))

  (testing "the bare address has no lens — the default is the path itself, and
            `lenses` deliberately does not list it"
    (is (nil? (views/current-lens "/store" :code)))
    (is (nil? (views/current-lens "/store/form/f1" :form))))

  (testing "a trailing segment that is not a declared lens for THIS subject is
            not a lens, however much it looks like one"
    (is (nil? (views/current-lens "/store/bogus" :code)))
    (testing "including one that is a real lens on a DIFFERENT subject — the
              tables are per-subject and a cross-subject match would mark a bar
              entry that this screen does not offer"
      (is (nil? (views/current-lens "/store/source" :code)))
      (is (nil? (views/current-lens "/store/form/f1/gaps" :form)))))

  (testing "a subject with no lenses at all never has one"
    (is (nil? (views/current-lens "/endpoints/get/api/modules" :endpoint))))

  (testing "and a query string is not part of the name — it comes off first,
            or `/store/table?x=1` is a namespace literally named `table?x=1`"
    (is (= "table" (views/current-lens "/store/table?x=1" :code)))))

(deftest a-picker-row-separates-the-project-from-its-path-and-its-state
  ;; Found by LOOKING at the landing, which only became possible when it moved
  ;; from a server-rendered page into a client screen — the `screen` tool reads
  ;; the app, and the picker was not part of the app.
  ;;
  ;;     slopp2/w/demo1 session     ← what it would say
  ;;     slopp2 /w/demo 1 session   ← what it means
  ;;
  ;; Three inline siblings with only a CSS gap between them is a defect class
  ;; this app has met four times. The reader without the stylesheet is the
  ;; tool, and a margin is invisible to it.
  ;;
  ;; The ROW is the unit, so it is pinned whole. Joined with the empty string
  ;; on purpose: a separator has to be IN the markup, and joining with a space
  ;; would supply the very thing under test.
  (let [row (fn [p]
              (let [li (first (filter #(and (vector? %) (= :li (first %)))
                                      (nodes (views/hub-picker [p]))))]
                (str/join "" (filter string? (nodes li)))))]
    (testing "a project's slug, path and state stay three separate words"
      (is (= "slopp2 /w/demo 1 session"
             (row {:slug "slopp2" :dir "/w/demo" :opened-at 0 :sessions 1 :cli false :app nil}))))
    (testing "and one serving an app says where, in the same sentence"
      (is (= "slopp2 /w/demo 2 sessions · app http://127.0.0.1:7358/api/"
             (row {:slug "slopp2" :dir "/w/demo" :opened-at 0 :sessions 2 :cli true
                   :app {:url "http://127.0.0.1:7358/api/" :branch "main"}}))))))

(deftest an-endpoints-documentation-is-MARKDOWN-and-keeps-its-structure
  ;; The instance: Nathan opened `/p/slopp.ui/endpoints/get/api/projects` and
  ;; read a three-paragraph docstring rendered as one wall of text.
  ;; `schema/prose` collapses whitespace runs — correct for a one-line SUMMARY
  ;; on the index, wrong for the endpoint's own page, and it was used for both.
  ;;
  ;; slopp has since DECLARED docstrings markdown (`D-doc-markdown`) and ruled
  ;; that the rendering is this store's, all of it, including `[[wikilink]]`
  ;; resolution — *decoding has one correct answer and rendering does not*.
  (let [doc  [{:method :get :path "/api/modules" :name 'modules
               :handler "demo.api.endpoints/modules"
               :doc (str "One line about it.\n\n"
                         "A second paragraph that says **more**, mentions\n"
                         "`a-code-span`, and points at [[demo.api.model/shape]].\n\n"
                         "- a bullet\n- another")
               :request nil :response [:map [:a :string]]}]
        main (views/endpoint-main doc {:method "get" :path "api/modules"} nil)
        all  (nodes main)
        tag? (fn [t] (filter #(and (vector? %) (= t (first %))) all))]

    (testing "the paragraphs the author wrote are paragraphs"
      ;; scoped to the DOC. Counting `:p` over the whole page passed while the
      ;; docstring was still one collapsed block, because the page carries other
      ;; paragraphs — a green that could not go red, which is the shape this
      ;; file keeps having to unlearn.
      (let [doc-div (first (filter #(and (vector? %) (map? (second %))
                                         (= "endpoint-doc" (:class (second %))))
                                   all))]
        (is (some? doc-div) "no endpoint-doc block at all")
        (is (<= 2 (count (filter #(and (vector? %) (= :p (first %)))
                                 (nodes doc-div))))
            "the docstring rendered as one block — the defect Nathan read")))

    (testing "and the marks this store's docstrings actually use render"
      (is (seq (tag? :strong)) "**more** is not strong")
      (is (some #(= [:code "a-code-span"] %) all) "the code span is not code")
      (is (seq (tag? :ul)) "the bullets are not a list"))

    (testing "a QUALIFIED wikilink becomes a link into the code section"
      ;; project-relative, like every other href here — `chrome` puts the
      ;; project on. See `every-in-app-link-is-addressed-under-the-PROJECT`.
      (is (some #(= [:a {:href "/store/source/demo.api.model/shape"}
                     "demo.api.model/shape"] %)
                all)
          "the wikilink did not resolve to the form it names"))

    (testing "and a BARE one resolves in the handler's own namespace, which is
              what an author means by [[act]] beside `act`"
      (let [one (views/endpoint-main
                 [{:method :get :path "/api/x" :name 'x
                   :handler "demo.api.endpoints/x"
                   :doc "see [[sibling]]"}]
                 {:method "get" :path "api/x"} nil)]
        (is (some #(= [:a {:href "/store/source/demo.api.endpoints/sibling"}
                       "sibling"] %)
                  (nodes one)))))))

(deftest the-request-and-response-are-TABBED-and-the-tab-is-in-the-address
  ;; Both sections rendered in full, one above the other, and a real endpoint's
  ;; field lists are long enough that the page stopped being readable.
  ;;
  ;; **The tab is in the ADDRESS, not in client state**, which is this app's
  ;; standing argument about view state: the address bar is a persistent,
  ;; always-visible rendering of it, and Back is the only undo affordance every
  ;; reader already knows. A tab held in state loses both, and cannot be shared.
  ;;
  ;; **A query parameter rather than a lens SEGMENT**, and that is forced rather
  ;; than chosen. The route is `/p/:slug/endpoints/:method/*path` — a trailing
  ;; splat — so `/endpoints/get/api/modules/request` is indistinguishable from
  ;; an endpoint whose path really is `/api/modules/request`. `?part=` cannot
  ;; collide with it, and `?q=` on search is the same shape already.
  (let [doc  [{:method :get :path "/api/modules" :name 'modules
               :request  [:map [:only-in-request :string]]
               :response [:map [:only-in-response :string]]}]
        page (fn [part]
               (views/endpoint-main doc (cond-> {:method "get" :path "api/modules"}
                                          part (assoc :part part))
                                    nil))
        ;; the SECTION, not the page. A request field also appears under `call
        ;; it` — that panel lists the parameters you would send — so a
        ;; whole-page negative asserts something false and this test failed on
        ;; its own first version for exactly that.
        sect (fn [part]
               (first (filter #(and (vector? %) (map? (second %))
                                    (= "schema-part" (:class (second %))))
                              (nodes (page part)))))
        txt  (fn [v] (apply str (filter string? (nodes v))))]

    (testing "the DEFAULT is RESPONSE, because the request tab is EMPTY on most
              of this surface"
      ;; The first version defaulted to `request`, on call order. slopp's
      ;; surface is GET, a GET carries no body, so that tab reads `no
      ;; parameters` on most endpoints — a default that shows nothing for the
      ;; common case. Eleven existing tests went red on it and every one
      ;; asserted response content, which is what the page is FOR.
      (let [t (txt (sect nil))]
        (is (re-find #"only-in-response" t))
        (is (not (re-find #"only-in-request" t))
            "both sections still render — the page is as long as it was")))

    (testing "and ?part=request shows the other one, only"
      (let [t (txt (sect "request"))]
        (is (re-find #"only-in-request" t))
        (is (not (re-find #"only-in-response" t)))))

    (testing "the bar marks where you are and links where you are not — the
              same shape `lens-bar` already uses, because it is the same
              interaction: two views of one subject"
      (let [dflt (nodes (page nil))]
        (is (some #(and (vector? %) (= :strong (first %))
                        (= "tab-current" (:class (second %)))
                        (= "response" (last %)))
                  dflt)
            "the current tab is not marked")
        (is (some #(= [:a {:href "/rest/paths/get/api/modules?part=request"}
                       "request"] %)
                  dflt)
            "no link to the other tab — a view reachable only by typing its URL")))

    (testing "an unknown part falls back to the default rather than rendering
              an empty page"
      ;; DIFFERENT from `lenses`, deliberately. An unknown LENS resolves to
      ;; nothing, because `lenses` declares which exist and a declared-but-dead
      ;; view is how a reader ends up certain they saw something they did not.
      ;; No table declares parts, so `?part=nonsense` is a typed url and not a
      ;; broken promise — and a query string cannot 404.
      (is (re-find #"only-in-response" (txt (sect "nonsense")))))))

(deftest an-index-cell-never-opens-with-a-dangling-separator
  ;; **Found by LOOKING at the index, not by an assertion.** The cell composes
  ;; up to three pieces — the one-line summary, the effect mark, the auth mark
  ;; — and each mark carried its own leading `" · "`. That is correct only
  ;; while the summary is present. An endpoint whose handler has no docstring
  ;; renders:
  ;;
  ;;     POST /api/register · changes something
  ;;
  ;; a separator with nothing on its left. It was there the whole time and no
  ;; fixture had ever combined a missing `:doc` with a mark, so nothing said
  ;; so; the screen said it in one look.
  ;;
  ;; Same family as the three adjacency defects this project has already fixed,
  ;; inverted: those were two words with NO separator between them, this is a
  ;; separator with no words before it. Both are the markup owing the reader
  ;; something the stylesheet cannot supply.
  (let [text (fn [v] (str/join "" (filter string? (nodes v))))
        row  (fn [ep] (text (views/endpoints-main [ep])))
        base {:path "/api/x" :name 'x :request nil :response :string}]

    (testing "a mark with no summary before it opens the cell cleanly"
      (let [out (row (assoc base :method :post))]
        (is (re-find #"changes something" out) "the mark renders at all")
        (is (not (re-find #"·\s*changes something" out))
            (str "the cell opens with a separator and nothing on its left: "
                 (pr-str out)))))

    (testing "and an auth mark with no summary is the same case"
      (let [out (row (assoc base :method :get :auth [:group "admin"]))]
        (is (re-find #"admin" out))
        (is (not (re-find #"·\s*\[:group" out)) (pr-str out))))

    (testing "but a summary followed by a mark KEEPS its separator — the fix
              must not simply delete them"
      (let [out (row (assoc base :method :post
                            :doc "POST /api/x — does a thing. And more."))]
        (is (re-find #"does a thing\." out))
        (is (re-find #"·\s*changes something" out)
            "with prose on its left the separator is what keeps the two apart")))

    (testing "and two marks together stay separated from each other"
      (let [out (row (assoc base :method :post :auth [:group "admin"]))]
        (is (re-find #"changes something\s*·\s*\[:group" out) (pr-str out))))))

(deftest a-content-page-is-addressed-by-its-VAR-not-by-its-path
  ;; **The obvious address does not work, and the reason is in this store's own
  ;; content surface.** An endpoint is addressed by method and path because the
  ;; two together identify it. The equivalent for content would be
  ;; `/pages/*path` — and `slopp.ui.shell/shell` is served at `/`, so its address
  ;; would be `/pages/` with an EMPTY wildcard. The most important page in this
  ;; store would be the one that could not be opened.
  ;;
  ;; The var is total instead: `http-unreachable-declaration` refuses a private
  ;; content form, so every row has a public var, and `:handler` publishes it
  ;; fully qualified precisely because `:name` alone does not resolve. It also
  ;; matches `schema/handler-source-path`'s existing `/store/source/:ns/:name`,
  ;; so a reader meets one spelling for "which form is this" twice.
  (let [shell {:path "/" :method :get :name 'shell
               :handler 'slopp.ui.shell/shell :auth :public
               :media-type "text/html; charset=utf-8"
               :shape :hiccup :root-tag :html :nodes 19
               :shell "/assets/cljs/main.js"}
        css   {:path "/css/style.css" :method :get :name 'stylesheet
               :handler 'slopp.ui.styles/stylesheet :auth :public
               :media-type "text/css" :shape :text :bytes 14263}]

    (testing "the captures are the var's namespace and name"
      (is (= {:ns "slopp.ui.shell" :name "shell"} (views/page-params shell)))
      (is (= {:ns "slopp.ui.styles" :name "stylesheet"} (views/page-params css))))

    (testing "and a page served at the ROOT is addressable, which is the whole
              reason the address is not the path"
      ;; under `/http/paths`, the HTTP section's own listing — it was `/pages`,
      ;; one word for one of the three documents. See `views/sections`.
      (is (= "/http/paths/slopp.ui.shell/shell" (views/page-address shell)))
      (is (= "/http/paths/slopp.ui.styles/stylesheet" (views/page-address css))))

    (testing "the address is DERIVED from the same captures the screen receives,
              so a link and the page it opens cannot drift"
      (doseq [p [shell css]]
        (is (= (views/page-address p)
               (str "/http/paths/" (:ns (views/page-params p))
                    "/" (:name (views/page-params p)))))))

    (testing "and the predicate finds exactly the row an address names"
      (is ((views/at-page? (views/page-params shell)) shell))
      (is (not ((views/at-page? (views/page-params shell)) css)))
      (is (not ((views/at-page? {:ns "slopp.ui.hub" :name "nope"}) shell))
          "same namespace, different var")
      (is (not ((views/at-page? {:ns "other" :name "shell"}) shell))
          "same var name, different namespace — which is why :name alone will not do"))

    (testing "a row whose handler is not qualified has no address rather than a
              wrong one — the document says a qualified symbol, and guessing a
              namespace would send a reader to a form that may not exist"
      (is (nil? (views/page-params {:path "/x" :name 'x :handler 'x})))
      (is (nil? (views/page-address {:path "/x" :name 'x :handler 'x})))
      (is (nil? (views/page-params {:path "/x" :name 'x}))))))

(deftest the-pages-section-is-an-index-and-a-page-per-CONTENT-form
  ;; The other half of the api/content partition. `:rest/path` is an API and
  ;; gets the API section; `:http/path` is CONTENT — a stored VALUE the
  ;; dispatcher serves by dereferencing — and had no screen at all, because
  ;; nothing published it. `/api/http/paths` does now.
  ;;
  ;; The rows here are this store's REAL content surface, measured off
  ;; `/api/http/paths` rather than invented: a shell that declares no media
  ;; type and gets a derived one, and a stylesheet that declares one verbatim.
  (let [shell {:path "/" :method :get :name 'shell
               :handler 'slopp.ui.shell/shell :auth :public
               :doc "GET / — the whole application, as one stored document. The framework adds the script and the base."
               :media-type "text/html; charset=utf-8"
               :shape :hiccup :root-tag :html :nodes 19
               :shell "/assets/cljs/main.js"}
        css   {:path "/css/style.css" :method :get :name 'stylesheet
               :handler 'slopp.ui.styles/stylesheet :auth :public
               :doc nil
               :media-type "text/css" :shape :text :bytes 14263}
        text  (fn [v] (str/join " " (filter string? (nodes v))))]

    (testing "the INDEX names every page by the PATH it is served at — that is
              what a reader recognises, the same call the API index makes"
      (let [t (text (views/pages-main [shell css]))]
        (is (re-find #"/css/style\.css" t))
        (is (re-find #"text/css" t))
        (is (re-find #"stylesheet" t) "and which form serves it")))

    (testing "and says what each value IS without shipping it"
      (let [t (text (views/pages-main [shell css]))]
        (is (re-find #"whole document" t))
        (is (re-find #"14\.3 kB" t))))

    (testing "every row links to that page's own screen, addressed by the var"
      (is (some #(and (vector? %) (= :a (first %))
                      (= "/http/paths/slopp.ui.shell/shell" (:href (second %))))
                (nodes (views/pages-main [shell css])))))

    (testing "an EMPTY document is the common case — most projects serve no
              content of their own — so it says so in words rather than
              rendering an empty table"
      (let [t (text (views/pages-main []))]
        (is (re-find #"no static pages" t))
        (is (not-any? #(and (vector? %) (= :table (first %)))
                      (nodes (views/pages-main []))))))

    (testing "a nil document degrades the same way the API index does, naming
              the address a reader could curl"
      (is (re-find #"/api/http/paths" (text (views/pages-main nil)))))

    (testing "the PAGE shows one content form in full, and only that one"
      (let [t (text (views/page-main [shell css] (views/page-params css)))]
        (is (re-find #"/css/style\.css" t))
        (is (re-find #"text/css" t))
        (is (re-find #"14\.3 kB" t))
        (is (not (re-find #"/assets/cljs/main\.js" t))
            "the other page's bundle is not on this one")))

    (testing "it names the form that serves it and links to the source, which
              is the whole drill-down — the same join the API page makes"
      (let [v (views/page-main [shell css] (views/page-params css))]
        (is (re-find #"slopp\.ui\.styles/stylesheet" (text v)))
        (is (some #(and (vector? %) (= :a (first %))
                        (= "/store/source/slopp.ui.styles/stylesheet"
                           (:href (second %))))
                  (nodes v)))))

    (testing "a page that BOOTS an application says so and names the bundle —
              it is the most interesting fact about the shell and the document
              publishes it only for pages that have one"
      (let [t (text (views/page-main [shell css] (views/page-params shell)))]
        (is (re-find #"/assets/cljs/main\.js" t)))
      (is (not (re-find #"application"
                        (text (views/page-main [shell css] (views/page-params css)))))
          "and a page that boots nothing says nothing about a bundle"))

    (testing "an unknown address says so rather than rendering an empty page,
              and names what was asked for"
      (let [t (text (views/page-main [shell css] {:ns "nope" :name "gone"}))]
        (is (re-find #"no page" t))
        (is (re-find #"nope/gone" t))))))

(deftest the-pages-section-is-reachable-and-carries-its-own-rail
  ;; The section as the app sees it: a tab, a page bar, two routes, a subject
  ;; each, and a left rail on both screens — the same shape the REST section
  ;; has, because a reader crossing between them should not have to learn a
  ;; second one.
  ;;
  ;; **It is the HTTP capability's `Paths` page**, where it used to be a
  ;; top-level `Pages` tab. `:http/path` is what these forms declare, so
  ;; `Paths` is the word the document uses; `Pages` moved to the Webapp
  ;; section, where `:page` is what the rows are called. See `views/sections`.
  (let [pages [{:path "/" :method :get :name 'shell
                :handler 'slopp.ui.shell/shell :auth :public
                :doc "GET / — the whole application." :media-type "text/html; charset=utf-8"
                :shape :hiccup :root-tag :html :nodes 19 :shell "/assets/cljs/main.js"}
               {:path "/css/style.css" :method :get :name 'stylesheet
                :handler 'slopp.ui.styles/stylesheet :auth :public :doc nil
                :media-type "text/css" :shape :text :bytes 14263}]
        view (fn [screen params]
               (full-view (with-main-load {:path   (if (= :http-path screen)
                                                     ;; through the app's own builder, not a
                                                     ;; literal — the address moved once already
                                                     (views/page-address
                                                      {:handler (symbol (str (:ns params))
                                                                        (str (:name params)))})
                                                     "/http/paths")
                                           :screen screen :params params}
                            :ready pages)))
        text (fn [v] (str/join " " (filter string? (nodes v))))]

    (testing "both addresses route, and to their own subjects"
      (is (= [:http-paths {}] (routed "/http/paths")))
      (is (= [:http-path {:ns "slopp.ui.shell" :name "shell"}]
             (routed "/http/paths/slopp.ui.shell/shell"))))

    (testing "the section tab is in the bar and marks itself when you are in it"
      (let [bar (views/find-region (view :http-paths {}) :nav/sections)]
        (is (re-find #"HTTP" (text bar)))
        (is (some #(and (vector? %) (map? (second %))
                        (= "active" (:class (second %)))
                        (= "/p/demo/http/paths" (:href (second %))))
                  (nodes bar))
            "the tab a reader is standing in is the one marked")))

    (testing "and there is NO second-level page bar. Every section ended up with
              exactly one page, so a bar naming it said only what the tab above
              it already said — `REST` over `Paths`, `Config` over `Settings` —
              and cost a full row of chrome to do it"
      (is (nil? (views/find-region (view :http-paths {}) :nav/pages))))

    (testing "and the REST tab is NOT marked while you are in HTTP — two
              sections whose prefixes are both under the project root, so a
              longest-prefix bug would light both"
      (let [bar (views/find-region (view :http-paths {}) :nav/sections)]
        (is (not-any? #(and (vector? %) (map? (second %))
                            (= "active" (:class (second %)))
                            (= "/p/demo/rest/paths" (:href (second %))))
                      (nodes bar)))))

    (testing "the RAIL lists every page on BOTH screens, with the open one
              marked — the same affordance the REST rail gives"
      (doseq [[screen params] [[:http-paths {}]
                               [:http-path {:ns "slopp.ui.styles" :name "stylesheet"}]]]
        (let [rail (views/find-region (view screen params) :nav/local)]
          (is (some? rail) (str "no rail on " screen))
          (is (re-find #"/css/style\.css" (text rail)))
          (is (re-find #"/" (text rail)))))
      (let [rail (views/find-region (view :http-path {:ns "slopp.ui.styles" :name "stylesheet"})
                                    :nav/local)]
        (is (some #(and (vector? %) (map? (second %))
                        (= "active" (:class (second %)))
                        (= "/p/demo/http/paths/slopp.ui.styles/stylesheet" (:href (second %))))
                  (nodes rail))
            "the page you are reading is marked in the rail")))

    (testing "and an EMPTY project renders no rail rather than an empty one —
              the pane is omitted, not drawn blank"
      (let [v (full-view (with-main-load {:path "/http/paths" :screen :http-paths :params {}}
                           :ready []))]
        (is (nil? (views/find-region v :nav/local)))
        (is (re-find #"no static pages" (text (views/find-region v :main))))))))

(deftest the-pages-screens-classes-and-ids-all-have-rules
  ;; The sibling of `the-api-screen-s-classes-are-derived-from-what-it-renders`,
  ;; and it exists for the reason that one names: a class is a literal in
  ;; `views` and a selector in `styles`, joined by nothing but the spelling.
  ;;
  ;; The Pages section adds NO new class — it reuses the API section's, which
  ;; is the answer this test is here to keep honest. Reuse is a fact about
  ;; today, and the next row added to these screens is exactly as likely to
  ;; invent a class as any other. Without this, it would ship unstyled and
  ;; nothing would say so.
  ;;
  ;; Rendered from a fixture that reaches every branch: an auth that NARROWS,
  ;; a page that BOOTS an app, one with prose and one with `:doc nil`.
  (let [pages [{:path "/" :method :get :name 'shell :handler 'slopp.ui.shell/shell
                :auth [:group "admin"] :doc "One. Two."
                :media-type "text/html; charset=utf-8"
                :shape :hiccup :root-tag :html :nodes 19 :shell "/b.js"}
               {:path "/css/style.css" :method :get :name 'stylesheet
                :handler 'slopp.ui.styles/stylesheet :auth :public :doc nil
                :media-type "text/css" :shape :text :bytes 14263}]
        of   (fn [v] (->> (nodes v)
                          (filter #(and (vector? %) (map? (second %))))
                          (map second)))
        classes (->> [(views/pages-main pages)
                      (views/page-main pages (views/page-params (first pages)))
                      (views/page-nav pages "/pages/slopp.ui.shell/shell")]
                     (mapcat of)
                     (keep :class)
                     (mapcat #(str/split % #"\s+"))
                     (remove str/blank?)
                     set)
        ids     (->> [(views/page-nav pages "/pages/slopp.ui.shell/shell")]
                     (mapcat of) (keep :id) set)
        css     styles/stylesheet]

    (testing "the population is real — an empty set would make the loop below
              pass by checking nothing"
      (is (seq classes))
      (is (seq ids)))

    (testing "every class these screens render has a rule in the SERVED
              stylesheet"
      ;; the lookahead is load-bearing: a plain substring finds `.endpoint`
      ;; inside `.endpoint-row`, so a class with no rule of its own would pass
      ;; on a longer sibling's.
      (doseq [c classes]
        (is (re-find (re-pattern (str "\\." c "(?![-\\w])")) css)
            (str "no rule for ." c))))

    (testing "and so does every id — the rail is addressed by one, and
              `#page-list` is its own rather than borrowed from the API rail
              because an id is a NAME a reader of the document meets"
      (doseq [i ids]
        (is (re-find (re-pattern (str "#" i "(?![-\\w])")) css)
            (str "no rule for #" i))))

    (testing "the finder can miss"
      (is (not (re-find #"\.pages-invented(?![-\w])" css)))
      (is (not (re-find #"#page-invented(?![-\w])" css))))))

(deftest at-project-measures-a-DESCRIPTOR-against-the-project-in-the-address
  ;; Move A: a page ASKS with a descriptor rather than declaring a `:request`,
  ;; so measuring one against a project is one function and the whole of *which
  ;; api is this* lives in it.
  (testing "the base lands on the descriptor — the daemon's MOUNT for the project
            — and the descriptor's path loses the producer's /api prefix,
            because the mount replaces it rather than nesting under it"
    (is (= "/api/projects/demo" (:webapp/base (views/at-project {:slug "demo"} api/modules))))
    (is (= "/modules" (:http/path (views/at-project {:slug "demo"} api/modules)))))

  (testing "and it survives onto the built request, which is what carries it
            to both the performer and the load key"
    (is (= "/api/projects/demo"
           (:webapp/base (endpoint/request (views/at-project {:slug "demo"} api/modules) {})))))

  (testing "TWO projects are TWO LOADS — the assertion that matters, and the
            one that was false for a day"
    ;; `load-key` was `[method url]` on the UNBASED url, so the base — which is
    ;; WHICH UPSTREAM a path belongs to — was not part of a load's identity.
    ;; Every project's `/api/modules` was one entry: navigating from
    ;; `/p/demo/store` to `/p/other/store` asked for nothing and rendered demo's
    ;; modules under the other project's name.
    ;;
    ;; A cache hit is silent by construction, so no screen could show it and no
    ;; assertion about a rendered screen could catch it. This store carried a
    ;; workaround for a day — prefixing the PATH so the url itself discriminated
    ;; — and slopp fixed the model instead: `load-key` takes the base and
    ;; resolves through `addressed` before keying. This is the same claim,
    ;; asserted one layer down, where it is now true by construction.
    (let [k (fn [slug] (webapp/load-key
                        ;; the APP's base, which is what `ask!` passes — the
                        ;; request's own wins inside `addressed`, and that is
                        ;; precisely the resolution being asserted.
                        ""
                        (endpoint/request (views/at-project {:slug slug} api/modules) {})))]
      (is (not= (k "demo") (k "other"))
          "two projects share one load — switching projects shows the previous one's data")
      (is (= [:get "/api/projects/demo/modules"] (k "demo"))
          "the key is the address a browser will actually FETCH, resolved once")))

  (testing "the slug is THIS app's routing and never reaches the project"
    ;; the leak this replaces was `?slug=demo` arriving at an endpoint that
    ;; never named it. The guard now lives in `endpoint/request` rather than in
    ;; a generated builder, so it is the framework's refusal being asserted.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"does not name"
                          (endpoint/request api/form {:id "f1" :slug "demo"})))))

(deftest the-webapp-section-is-an-index-of-every-SCREEN-a-project-declares
  ;; The third of the three path documents, and the one with a join in it:
  ;; `/api/webapp/paths` publishes `:calls`, DERIVED from the reference graph —
  ;; what each page's form references that is an endpoint descriptor. So this
  ;; index can say not just *what screens exist* but *what each one fetches*,
  ;; and the answer is checkable against the REST section next door rather than
  ;; being a claim this app makes.
  (let [rows [{:path "/p/:slug/store"
               :page 'slopp.ui.pages/code-page
               :doc "The Code index — the module diagram and the store's shape.\n\nMore prose."
               :calls [{:endpoint 'slopp.ui.wire.api/modules :method :get :path "/api/modules"}]}
              {:path "/p/:slug/store/form/:id"
               :page 'slopp.ui.pages/form-page
               :doc "One form."
               :calls [{:endpoint 'slopp.ui.wire.api/form :method :get :path "/api/form/:id"}]}
              ;; **`:calls` is ABSENT, not empty**, when a page fetches nothing —
              ;; slopp's own rule, because an empty vector would be a claim and
              ;; the derivation is bounded. A row like this is the one most
              ;; likely to render as a blank cell that reads like a bug.
              {:path "/p/:slug/about" :page 'slopp.ui.pages/about-page :doc nil}]
        v    (views/webapp-pages-main rows)
        text (fn [t] (str/join " " (filter string? (nodes t))))
        hrefs (fn [t] (set (keep #(when (map? %) (:href %)) (nodes t))))]

    (testing "every declared screen is listed, addressed by its VAR"
      (is (re-find #"3 pages" (text v)))
      (is (contains? (hrefs v) "/webapp/pages/slopp.ui.pages/code-page"))
      (is (contains? (hrefs v) "/webapp/pages/slopp.ui.pages/form-page")))

    (testing "the ROUTE PATTERN is what a reader recognises, so it is the label"
      (is (re-find #"/p/:slug/store/form/:id" (text v))))

    (testing "what a page FETCHES is in the row, and it links into the REST
              section — the join `:calls` exists for, and the reason these two
              are sections of one application rather than two lists"
      (is (contains? (hrefs v) "/rest/paths/get/api/modules"))
      (is (contains? (hrefs v) "/rest/paths/get/api/form/:id")))

    (testing "a page that fetches NOTHING says so, rather than leaving a cell
              that reads as data the screen failed to load"
      (is (re-find #"(?i)fetches nothing|no calls|nothing" (text v))))

    (testing "an empty document is the common case and gets words — most
              projects declare no webapp pages at all"
      (is (re-find #"(?i)declares no" (text (views/webapp-pages-main []))))
      (is (nil? (views/find-region (views/webapp-pages-main []) :table))))

    (testing "and NO document is the older, different case — it names the
              address so a reader can curl what the page could not get"
      (is (re-find #"/api/webapp/paths" (text (views/webapp-pages-main nil)))))))

(deftest a-webapp-page-screen-says-what-it-answers-and-what-it-fetches
  (let [rows [{:path "/p/:slug/store"
               :page 'slopp.ui.pages/code-page
               :doc "The Code index.\n\nThe diagram is laid out **here** rather than in the view."
               :calls [{:endpoint 'slopp.ui.wire.api/modules :method :get :path "/api/modules"}]}
              {:path "/p/:slug/about" :page 'slopp.ui.pages/about-page :doc nil}]
        at   (fn [nm] (views/webapp-page-main rows {:ns "slopp.ui.pages" :name nm}))
        text (fn [t] (str/join " " (filter string? (nodes t))))
        hrefs (fn [t] (set (keep #(when (map? %) (:href %)) (nodes t))))]

    (testing "it leads with the ADDRESS the page answers, which is what a
              reader came here holding"
      (is (re-find #"/p/:slug/store" (text (at "code-page")))))

    (testing "and names the var that answers it, with a way into the code —
              the join every other detail screen in this app makes"
      (is (re-find #"code-page" (text (at "code-page"))))
      (is (contains? (hrefs (at "code-page"))
                     "/store/source/slopp.ui.pages/code-page")))

    (testing "the prose is MARKDOWN and keeps its structure, like an endpoint's"
      (is (some #(and (vector? %) (= :strong (first %)))
                (nodes (at "code-page")))))

    (testing "what it fetches links into the REST section, so the two sections
              join rather than each holding half a fact"
      (is (contains? (hrefs (at "code-page")) "/rest/paths/get/api/modules")))

    (testing "a page that fetches nothing SAYS so — and the reason is worth
              stating on the page, because absent means `nothing the graph
              could see` rather than `nothing`"
      (is (re-find #"(?i)fetches nothing" (text (at "about-page")))))

    (testing "an address naming no page degrades to a panel that names it,
              rather than rendering a blank screen for a link that was wrong"
      (let [v (views/webapp-page-main rows {:ns "slopp.ui.pages" :name "nope"})]
        (is (re-find #"(?i)no page" (text v)))
        (is (re-find #"nope" (text v)))))))

(deftest the-webapp-screens-classes-and-ids-all-have-rules
  ;; The third of three, and it exists for the reason the other two name: a
  ;; class is a literal in `views` and a selector in `styles`, joined by
  ;; nothing but the spelling.
  ;;
  ;; **This one had something to catch**, where the Pages sibling records that
  ;; reuse was total. The Webapp screens invent `.page-calls` for the list of
  ;; endpoints a page fetches and `#webapp-page-list` for the rail, and both
  ;; shipped unstyled until this ran.
  ;;
  ;; Rendered from a fixture that reaches every branch: a page with SEVERAL
  ;; calls, and one with none — `:calls` absent rather than empty, which is
  ;; slopp's rule and a different rendering.
  (let [rows [{:path "/p/:slug/store" :page 'slopp.ui.pages/code-page
               :doc "One. Two."
               :calls [{:endpoint 'slopp.ui.wire.api/modules :method :get :path "/api/modules"}
                       {:endpoint 'slopp.ui.wire.api/form :method :get :path "/api/form/:id"}]}
              {:path "/p/:slug/about" :page 'slopp.ui.pages/about-page}]
        of   (fn [v] (->> (nodes v)
                          (filter #(and (vector? %) (map? (second %))))
                          (map second)))
        classes (->> [(views/webapp-pages-main rows)
                      (views/webapp-page-main rows (views/webapp-page-params (first rows)))
                      (views/webapp-page-nav rows "/webapp/pages/slopp.ui.pages/code-page")]
                     (mapcat of)
                     (keep :class)
                     (mapcat #(str/split % #"\s+"))
                     (remove str/blank?)
                     set)
        ids     (->> [(views/webapp-page-nav rows "/webapp/pages/slopp.ui.pages/code-page")]
                     (mapcat of) (keep :id) set)
        css     styles/stylesheet]

    (testing "the population is real — an empty set would make the loop below
              pass by checking nothing"
      (is (seq classes))
      (is (seq ids)))

    (testing "every class these screens render has a rule in the SERVED
              stylesheet"
      ;; the lookahead is load-bearing: a plain substring finds `.endpoint`
      ;; inside `.endpoint-row`, so a class with no rule of its own would pass
      ;; on a longer sibling's.
      (doseq [c classes]
        (is (re-find (re-pattern (str "\\." c "(?![-\\w])")) css)
            (str "no rule for ." c))))

    (testing "and so does every id — the rail is addressed by one, and
              `#webapp-page-list` is its own rather than borrowed, because an
              id is a NAME a reader of the document meets"
      (doseq [i ids]
        (is (re-find (re-pattern (str "#" i "(?![-\\w])")) css)
            (str "no rule for #" i))))

    (testing "the finder can miss"
      (is (not (re-find #"\.webapp-invented(?![-\w])" css)))
      (is (not (re-find #"#webapp-invented(?![-\w])" css))))))

(deftest a-delta-id-is-an-ADDRESS-and-no-screen-infers-order-from-it
  ;; **slopp allocates ids in BLOCKS as of jar d42353**, so a session holding
  ;; `[10000,11000)` can write LATER than one holding `[11000,12000)` and carry
  ;; the LOWER id. Within one line journal order is unchanged; ACROSS concurrent
  ;; sessions there is no longer a total order by magnitude.
  ;;
  ;; They audited their own fourteen `since`/`from` readers — all walk by
  ;; IDENTITY — and said this store is the population they could not check:
  ;; anything that sorts deltas by id, compares two with `<`, or infers *later*
  ;; from *larger* is reading an order that no longer exists.
  ;;
  ;; It does not, and this is what keeps it that way. Both id-bearing screens
  ;; render what the endpoint sent, in the order it sent it — the wire is
  ;; ordered by journal position and that is the ordering that means something.
  (let [;; **The block allocator is GONE and the claim got STRONGER.** The
        ;; paragraph above describes id blocks, which existed for about six
        ;; hours: a session reserved a disjoint range and minted inside it, so
        ;; magnitude was a total order within a line and not across lines.
        ;; slopp then deleted the counter entirely — an id is a prefix plus 48
        ;; random bits — after a grep showed nothing anywhere compares one by
        ;; magnitude.
        ;;
        ;; So there is no order left to infer, not merely a partial one, and
        ;; the cost is deliberate rather than incidental: ids stopped being
        ;; comparable by eye ON PURPOSE. Ask the journal, not the number.
        ;;
        ;; This test is UNCHANGED by that, which is the useful thing about it:
        ;; it pinned the property — no screen infers order from an id — rather
        ;; than the mechanism, so it was the right pin for blocks and is the
        ;; right pin for random.
        flat (fn [v] (apply str (filter string? (tree-seq coll? seq v))))
        ;; DELIBERATELY non-monotonic: the newest commit-point carries the LOWER
        ;; id, which is exactly what two concurrent sessions now produce and
        ;; what a magnitude sort would silently reverse.
        tl   (views/timeline-main
              {:working {:forms 0}
               :commit-points [{:commit "d10800" :status "green" :at "2026-08-27 09:00"
                             :description "newest, and its id is LOWER"
                             :range "d9000..d10800"}
                            {:commit "d11900" :status "green" :at "2026-08-27 08:00"
                             :description "older, and its id is HIGHER"
                             :range "d9000..d11900"}]})
        ch   (views/change-main
              {:from "d11900" :to "d10800" :count 1
               :arc [{:delta "d11900" :fail 0} {:delta "d10800" :fail 2}]
               :modules []})
        before? (fn [s a b] (< (.indexOf ^String s ^String a) (.indexOf ^String s ^String b)))]

    (testing "the population is real — a screen that dropped these ids entirely
              would satisfy the ordering checks below by having nothing to order"
      (doseq [[what s ids] [["timeline" (flat tl) ["d10800" "d11900"]]
                            ["arc" (flat ch) ["d11900" "d10800"]]]]
        (doseq [i ids]
          (is (<= 0 (.indexOf ^String s ^String i))
              (str what " does not render " i " at all")))))

    (testing "the TIMELINE renders commit-points in wire order — the newest first
              because the endpoint sent it first, NOT because its id is larger"
      (is (before? (flat tl) "d10800" "d11900")))

    (testing "and the change ARC likewise keeps the order it was given"
      (is (before? (flat ch) "d11900" "d10800")))

    (testing "and the fixture can TELL — a magnitude sort would answer
              differently, so the two assertions above are not passing by
              coincidence of the order they were written in"
      ;; the point of choosing these two ids: sorted, `d10800` comes first, and
      ;; the timeline wants it first while the arc wants it LAST. So one of the
      ;; two checks above must fail under any implementation that sorts, in
      ;; either direction — which is what makes them a test of the app rather
      ;; than a restatement of the fixture.
      (is (= ["d10800" "d11900"] (sort ["d11900" "d10800"]))
          "these ids no longer sort into the order the wire uses, which is the
           whole reason a screen may not sort them"))))

(deftest the-config-section-is-ONE-page-covering-every-owner
  ;; Nathan's call, and the reason this is a top-level section rather than a
  ;; `Settings` page inside HTTP: a reader asking *what is this project
  ;; configured to do* wants `rest.enabled` and `webapp.enabled` in the same
  ;; answer as `http.port`, and `http.*` is one owner out of six.
  ;;
  ;; The three facts a settings table normally loses, all of them distinctions
  ;; the document is careful to make and a careless renderer collapses:
  ;;   - a SECRET is withheld, which is not the same as having no value
  ;;   - `:set` absent means DEFAULTED, which is not set-to-the-default
  ;;   - `:effective` absent means unset, which is not a blank cell
  (let [secret-row {:key "http.auth.bearer.tokens.ci" :owner "http"
                    :secret true :set true :doc "A CI token."}
        doc {:config [{:key "http.port" :owner "http" :effective 7357
                       :default 7357 :set true :value "7357"
                       :doc "The port the server binds. More prose here."}
                      {:key "http.enabled" :owner "http" :effective false
                       :default false :doc "Whether this project serves HTTP."}
                      secret-row
                      {:key "app.name" :owner "app" :effective nil :default nil
                       :doc "Application name. Unset = the store directory name."}]
             :owners {"http" "an HTTP server: routing, static mounts, identity."
                      "app"  "any project, whatever kind of application it is."}
             :patterns [{:key "http.static.*" :owner "http"
                         :doc "Static mount: the tail is the URL prefix."}]
             :bundle "/assets/cljs/main.js"
             :orphaned [{:key "web.port" :value "7357"}]}
        v     (views/config-main doc)
        text  (fn [t] (str/join " " (filter string? (nodes t))))
        hrefs (fn [t] (set (keep #(when (map? %) (:href %)) (nodes t))))]

    (testing "every setting is listed, grouped under the capability that owns it"
      (is (re-find #"4 settings" (text v)))
      (doseq [k ["http.port" "http.enabled" "app.name"]]
        (is (re-find (re-pattern (str/replace k "." "\\.")) (text v))
            (str k " should be on the page"))))

    (testing "the owner label carries what it MEANS — the :owners vocabulary
              rides along precisely because a reader of this document has no
              other route to it"
      (is (re-find #"(?i)an HTTP server" (text v)))
      (is (re-find #"(?i)any project" (text v))))

    (testing "a SET value and a DEFAULTED one are different facts, and the
              document distinguishes them deliberately — set-to-the-default is
              not the same as never set"
      (is (re-find #"7357" (text v)))
      (is (re-find #"(?i)\bset\b" (text v)))
      (is (re-find #"(?i)default" (text v))))

    (testing "a SECRET is WITHHELD, which is `this is configured and I am not
              showing you` — never `nothing here`. Scoped to a one-row document
              so the assertion cannot be satisfied by another row's words"
      (let [only (views/config-main {:config [secret-row]
                                     :owners {"http" "an HTTP server."}})]
        (is (re-find #"(?i)withheld" (text only)))
        (is (not (re-find #"(?i)unset|no value|—" (text only))))))

    (testing "an UNSET key says so rather than leaving a blank cell that reads
              as data the screen failed to load"
      (let [only (views/config-main {:config [{:key "app.name" :owner "app"
                                               :effective nil :default nil
                                               :doc "Application name."}]
                                     :owners {"app" "any project."}})]
        (is (re-find #"(?i)unset" (text only)))))

    (testing "the BUNDLE is the fact this document exists to make findable —
              the missing `:webapp/bundle` cost a day before a page showed it"
      (is (re-find #"/assets/cljs/main\.js" (text v))))

    (testing "an ORPHANED key is a MIGRATION INSTRUCTION — it carries its value
              so the row tells you what to re-set, rather than sending you
              looking for it"
      (is (re-find #"web\.port" (text v)))
      (is (re-find #"(?i)migrat|no longer|unrecognis|unrecogniz" (text v))))

    (testing "the wildcard FAMILIES are named spaces rather than settings, so
              they are listed apart from the rows and not mixed in as keys"
      (is (re-find #"http\.static\.\*" (text v))))

    (testing "every owner heading links nowhere and the page asks for no
              prefix — one document, one page, no navigation inside it"
      (is (empty? (hrefs v))))

    (testing "an EMPTY config is the prefix-matched-nothing case and gets words"
      (is (re-find #"(?i)no settings|nothing" (text (views/config-main {:config [] :owners {}}))))
      (is (nil? (views/find-region (views/config-main {:config [] :owners {}}) :table))))

    (testing "and NO document names the address, so a reader can curl what the
              page could not get"
      (is (re-find #"/api/config" (text (views/config-main nil)))))))

(deftest the-config-screen-s-classes-all-have-rules
  ;; The fourth of these, and the reason is the one the other three name: a
  ;; class is a literal in `views` and a selector in `styles`, joined by
  ;; nothing but the spelling. The Webapp sibling had two shipped unstyled
  ;; when it first ran, which is why this is written before the CSS.
  ;;
  ;; Rendered from a document reaching every SECTION the page can draw —
  ;; owner groups, unrecognised keys and families — because each is its own
  ;; class and a fixture without orphans or patterns would check one of three.
  (let [doc {:config [{:key "http.port" :owner "http" :effective 7359
                       :set true :value "7359" :doc "The port."}
                      {:key "app.name" :owner "app" :effective nil
                       :doc "Application name."}]
             :owners {"http" "an HTTP server." "app" "any project."}
             :patterns [{:key "http.static.*" :owner "http" :doc "Static mount."}]
             :orphaned [{:key "web.port" :value "7357"}]
             :bundle "/assets/cljs/main.js"}
        of      (fn [v] (->> (nodes v)
                             (filter #(and (vector? %) (map? (second %))))
                             (map second)))
        classes (->> [(views/config-main doc)]
                     (mapcat of)
                     (keep :class)
                     (mapcat #(str/split % #"\s+"))
                     (remove str/blank?)
                     set)
        css     styles/stylesheet]

    (testing "the population is real — an empty set would make the loop below
              pass by checking nothing"
      (is (seq classes))
      ;; and it is the population this page actually invents, not just the
      ;; table class it borrows from every other index
      (is (contains? classes "config-owner"))
      (is (contains? classes "config-orphaned"))
      (is (contains? classes "config-patterns")))

    (testing "every class this screen renders has a rule in the SERVED
              stylesheet"
      ;; the lookahead is load-bearing: a plain substring finds `.config-owner`
      ;; inside `.config-owner-name`, so a class with no rule of its own would
      ;; pass on a longer sibling's.
      (doseq [c classes]
        (is (re-find (re-pattern (str "\\." c "(?![-\\w])")) css)
            (str "no rule for ." c))))

    (testing "the finder can miss"
      (is (not (re-find #"\.config-invented(?![-\w])" css))))))

(deftest a-form-with-no-source-says-so-rather-than-rendering-an-empty-block
  ;; **The rule this app applies everywhere else and never applied here.**
  ;; `config-main` says `unset`, `webapp-pages-main` names the address it could
  ;; not get, `pages-main` degrades in words — all on the same argument: a
  ;; blank cell reads as data the screen failed to load, which is
  ;; indistinguishable from a form that is genuinely empty.
  ;;
  ;; `source-main` rendered `[:pre [:code source]]` with no branch, so a
  ;; response without `:source` drew an empty code block: no error, no
  ;; sentence, and a reader who has to guess whether this form has no body or
  ;; the fetch lost it.
  ;;
  ;; Found by reading rather than by breaking, while checking whether slopp's
  ;; new `:source-already-sent` dedup could reach this app's HTTP surface. It
  ;; cannot today — that is the MCP wire and this is `/api/source/:ns/:name` —
  ;; but the question is what exposed the pane that had no answer either way.
  (let [text (fn [v] (str/join " " (filter string? (nodes v))))]

    (testing "a response with no source says so, and names the address, so a
              reader can curl what the screen could not get"
      (let [v (views/source-main {:ns "demo.core" :name "rate"})]
        (is (re-find #"(?i)no source" (text v)))
        (is (re-find #"/api/source/demo\.core/rate" (text v)))))

    (testing "and renders NO empty code block — the blank is the whole bug"
      (let [v (views/source-main {:ns "demo.core" :name "rate"})]
        (is (not-any? #(and (vector? %) (= :pre (first %))) (nodes v)))))

    (testing "an empty STRING is the same fact as an absent key — a form
              always has a body, so neither is a form with nothing in it"
      (is (re-find #"(?i)no source"
                   (text (views/source-main {:ns "demo.core" :name "rate" :source ""})))))

    (testing "and a form WITH source is unchanged — still a code block, still
              escaped by being a text node"
      (let [v (views/source-main {:ns "demo.core" :name "rate"
                                  :source "(defn rate [kg] kg)"})]
        (is (some #(and (vector? %) (= :pre (first %))) (nodes v)))
        (is (re-find #"defn rate" (text v)))
        (is (not (re-find #"(?i)no source" (text v))))))

    (testing "the breadcrumb is there either way — it needs only the name the
              page was addressed by, which is exactly what a failed load still
              has"
      (let [v (views/source-main {:ns "demo.core" :name "rate"})]
        (is (some #(and (vector? %) (= :a (first %))
                        (= "/store/ns/demo.core" (:href (second %))))
                  (nodes v)))))))

(deftest a-dashboard-panel-with-no-data-says-WHICH-nothing-it-is
  ;; Four different absences reach this screen and an empty chart renders them
  ;; identically. Naming the kind is the difference between a reader who waits
  ;; and a reader who asks.
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (let [t (text (views/dashboard-main {:namespaces [] :timeline {:commit-points []}}))]
      (testing "token usage is NOT EXPORTED until the harness sends it —
                which is a different sentence from zero"
        (is (re-find #"(?i)not exported" t))
        (is (not (re-find #"0 tokens" t))))
      (testing "test timing is published per RANGE and simply not summed here"
        (is (re-find #"(?i)not aggregated" t))
        (is (not (re-find #"(?i)not published" t))))
      (testing "growth and effort say NOT COUNTED — the fold has nothing for
                this store yet, which is neither of the other two"
        (is (re-find #"(?i)not counted" t)))
      (testing "an empty store still renders its zeros rather than throwing"
        (is (re-find #"0 namespaces" t))))))

(deftest the-token-panel-reports-what-was-spent-once-telemetry-arrives
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (let [t (text (views/dashboard-main
                   {:namespaces []
                    :timeline   {:commit-points []}
                    :cost {:by "ask"
                           :rows [{:ask "d1" :intent "turn the exporter on"
                                   :model {:tokens 2000 :cost-usd 8.0 :requests 4}}
                                  {:ask "d2" :intent "an ask that spent nothing"}
                                  {:ask "d3" :intent "build the dashboard"
                                   :model {:tokens 1000 :cost-usd 2.0 :requests 1}}]}}))]
      (testing "the totals are stated, with the mean beside its own denominator"
        (is (re-find #"3000 tokens" t))
        (is (re-find #"2 asks" t))
        (is (re-find #"1500" t) "the mean is over asks that SPENT, not over all three")
        (is (re-find #"\$10\.00" t)))
      (testing "each bar is labelled by the ask's own recorded intent"
        (is (re-find #"turn the exporter on" t))
        (is (re-find #"build the dashboard" t)))
      (testing "an ask that spent nothing is absent rather than a zero bar"
        (is (not (re-find #"an ask that spent nothing" t))))
      (testing "and the not-exported sentence is GONE once there is data —
                otherwise the panel would report both at once"
        (is (not (re-find #"(?i)not exported" t)))))))

(deftest the-token-panel-discloses-what-fell-outside-every-ask
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (let [t (text (views/dashboard-main
                   {:namespaces [] :timeline {:commit-points []}
                    :cost {:by "ask"
                           :rows [{:ask "d1" :intent "the work"
                                   :model {:tokens 1000 :cost-usd 2.0 :requests 3}}]
                           :unattributed {:requests 2
                                          :model {:tokens 400 :cost-usd 0.8 :requests 2}}
                           :undated 7}}))]
      (testing "the attributed total still means asks"
        (is (re-find #"1000 tokens" t)))
      (testing "what fell in no ask is stated, with its own cost and a grand total —
                reading only :rows would have understated the spend silently"
        (is (re-find #"400 tokens" t))
        (is (re-find #"no ask" t))
        (is (re-find #"1400 tokens" t)))
      (testing "requests with no timestamp are disclosed as a count"
        (is (re-find #"7 requests" t))
        (is (re-find #"(?i)no timestamp" t))))))

(deftest the-growth-and-effort-panels-read-the-commit-point-split
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (let [t (text (views/dashboard-main
                   {:namespaces [] :timeline {:commit-points []}
                    :effort {:by "commit-point"
                             :rows [{:commit "c2" :at 200 :forms 120 :namespaces 12
                                     :turns 2 :calls 20 :refused {:count 1 :pct 5}
                                     :wall {:active-ms 800 :slopp-ms 200 :outside-ms 600 :idle-ms 50}
                                     :rent {:carried-chars 1000}}
                                    {:commit "c1" :at 100 :forms 90 :namespaces 10
                                     :turns 1 :calls 10 :refused {:count 0 :pct 0}
                                     :wall {:active-ms 200 :slopp-ms 100 :outside-ms 100 :idle-ms 10}
                                     :rent {:carried-chars 500}}]}}))]
      (testing "growth reads oldest to newest in words, not only in the line"
        (is (re-find #"90" t))
        (is (re-find #"120 forms" t))
        (is (re-find #"12 namespaces" t)))
      (testing "effort states the counts and the derived share"
        (is (re-find #"3 turns" t))
        (is (re-find #"30 calls" t))
        (is (re-find #"1 refused" t))
        (is (re-find #"30%" t)))
      (testing "context rent is named as CHARACTERS, because calling it tokens
                is the mistake slopp corrected me on"
        (is (re-find #"1500 characters" t))
        (is (not (re-find #"1500 tokens" t)))))))

(deftest the-dashboard-counts-the-store-and-summarises-its-cadence
  (letfn [(text [v] (str/join " " (filter string? (nodes v))))]
    (let [v (views/dashboard-main
             {:namespaces [{:ns "app.big" :forms 40} {:ns "app.mid" :forms 20} {:ns "app.small" :forms 4}]
              :timeline   {:commit-points [{:at "2026-08-30 11:45"} {:at "2026-08-30 02:09"}
                                           {:at "2026-08-28 11:31"}]}})
          t (text v)]
      (testing "the totals are stated as counts"
        (is (re-find #"3 namespaces" t))
        (is (re-find #"64 forms" t)))
      (testing "and the namespaces themselves are NOT listed — the ranking panel
                was removed 2026-09-06, so a screen naming them again is a
                regression rather than a nicety somebody re-added"
        (is (not (re-find #"app\.big" t))))
      (testing "cadence is summarised in words as well as drawn"
        (is (re-find #"3 commit points" t))
        (is (re-find #"2 days" t))))))
