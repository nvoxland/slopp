(ns slopp-server.ui.views
  "Hiccup, and nothing else — every screen this app renders, as a pure
  function of data.

  `:cljc` so the same view compiles into the browser bundle and is verified by
  the JVM oracle. That is why nothing here reaches for a namespace:
  `slopp-server.ui.registry` is JVM-only (it formats a hash), so a view that required
  it could not compile into the client. Views take DATA.

  Right now this holds only what the HUB renders. The project-page views
  arrive with the SPA." (:require [clojure.string :as str] [slopp-server.ui.nsfilter :as nsfilter] [slopp-server.ui.basepath :as basepath] [slopp-server.ui.importance :as importance] [slopp-server.ui.callgraph :as callgraph] [slopp-server.ui.graph :as graph] [slopp-server.ui.schema :as schema] [slopp-server.ui.markdown :as markdown] [slopp-server.ui.metrics :as metrics] [slopp-server.ui.wire.api :as api]))

(defn find-region
  "The hiccup subtree for pane `role` (`:nav/sections`, `:nav/local`,
  `:nav/detail`, `:main`), or nil when the shell omitted it.

  Panes carry `:data-region` and are found by it, so a caller — a test above
  all — addresses a pane by what it IS rather than by where it sits. Reaching
  into `[2 1 0]` breaks on every layout change and reports nothing about what
  actually broke."
  [hiccup role]
  (first (for [x (tree-seq coll? seq hiccup)
               :when (and (vector? x)
                          (map? (second x))
                          ;; (symbol …), not (name …) — name DROPS the namespace, so :nav/sections
                          ;; asked for "sections" while the markup wrote "nav/sections"
                          (= (str (symbol role)) (:data-region (second x))))]
           x)))

(def sections
  "The application's global sections, in bar order — ONE table, so every page
  shows the same navigation and agrees about which section it is in.

  **A section is a CAPABILITY, not a screen.** It used to be one of each:
  `API` opened the endpoint list and there was nothing else in it, so the bar
  was a list of pages wearing the word section. The three documents slopp
  publishes are per-capability — `/api/rest/paths`, `/api/http/paths`,
  `/api/webapp/paths` — and naming the bar for the capability rather than for
  the one screen inside it is what lets a section gain a second page without
  the navigation being renamed.

  **A second level existed for that and is gone** (2026-08-28). `section-pages`
  named the pages inside the current section; every section held exactly one,
  so it drew `Paths` under `REST` and `Settings` under `Config` — a row of
  chrome restating the tab above it. If a section ever gains a real second
  page, that bar comes back; one page does not need it.

  **Named for the capability, not for what the page shows.** `REST`, `HTTP` and
  `Webapp` are slopp's own words for these three, they are what the endpoints
  are called, and they are what a reader has to type to talk to anyone about
  them. `API` was this app's coinage for one of the three and stopped being
  distinguishing the moment the other two arrived.

  **`Dashboard` is the one section that is not a slopp capability**, and it is
  named for what a reader wants rather than for a document: it is the only
  screen here that JOINS documents — the namespace index and the timeline — to
  say something neither of them says alone. Its panels are per-metric rather
  than per-endpoint for the same reason.

  `:prefix` is what `current-section` matches a path against. Each `:href` is a
  literal that `web-dangling-route-refs` joins against the served routes, so a
  section pointing at a path nothing serves fails `done` rather than 404ing in
  someone's browser.

  **None of these is `/api`.** A client path is prefixed to `/p/<slug>/…`
  before the browser sees it, and `/p/:slug/api/**` is the hub's proxy route —
  so `/api/…` would be swallowed by the proxy and answered 200 with a project's
  JSON. A section whose failure mode is rendering someone else's response body
  is worse than one that 404s. `/rest`, `/http` and `/webapp` are outside it."
  [{:label "Review"    :href "/"             :prefix ["/" "/change"]}
   {:label "Dashboard" :href "/dashboard"    :prefix ["/dashboard"]}
   {:label "Code"      :href "/store"        :prefix ["/store"]}
   {:label "REST"      :href "/rest/paths"   :prefix ["/rest"]}
   {:label "HTTP"      :href "/http/paths"   :prefix ["/http"]}
   {:label "Webapp"    :href "/webapp/pages" :prefix ["/webapp"]}
   ;; **Config is a section, not a page inside HTTP.** The first design put a
   ;; `Settings` page in the HTTP section and the ask went to slopp in those
   ;; words; Nathan changed it. `http.*` is one owner out of six, and a reader
   ;; asking what a project is configured to do wants `rest.enabled` and
   ;; `webapp.enabled` in the same answer as `http.port`.
   {:label "Config"    :href "/config"       :prefix ["/config"]}])

(defn nav-links
  "A `<ul>` of links from `items` (`{:label :href :active?}`), or nil when
  there are none — so a caller can splice the result straight in and get
  nothing when there is nothing to show.

  The active item carries `class=\"active\"`. Every href is a LITERAL in the
  returned data, which is what keeps `web-dangling-route-refs` able to see
  navigation at all: it joins literal `:href`s against the served routes, and
  it caught a real shipped 404 doing exactly that."
  [items]
  (when (seq items)
    (into [:ul]
          (for [{:keys [label href active?]} items]
            [:li [:a (cond-> {:href href} active? (assoc :class "active"))
                  label]]))))

(defn plural
  "`n` with `word`, pluralised by adding an s. Enough for the counts these
  screens state — \"1 forms\" is the kind of thing that reads as a bug in the
  data rather than a slip in the copy."
  [n word]
  (str n " " word (when (not= 1 n) "s")))

(defn token-code
  "A `[[class text] …]` token stream as hiccup.

  Whitespace and unclassified text are emitted BARE — a span per character
  run of ordinary code would triple the markup for no colour — so only what
  is actually distinguished carries an element.

  This is where the SPA line falls: the server walks the CST it already has
  and sends PAIRS, and the decision that a `\"keyword\"` token is a
  `[:span {:class \"keyword\"}]` is made here. No lexer ships to the browser
  and no markup ships from the server."
  [tokens]
  [:pre [:code
         (for [[cls text] tokens]
           (if (#{"ws" "text"} cls) text [:span {:class cls} text]))]])

(defn project-switcher
  "The top-nav control for moving between the projects the daemon holds, or
  nil when there is nothing to move between.

  Nil is the ORDINARY case, not a failure: one project alone on a daemon has
  nothing to switch to, and an empty dropdown would take up the same space
  to say the same nothing.

  **`:leaves?`, the third action kind.** `slopp.webapp/dispatch!` splits on the
  vocabulary rather than on the keyword — `:effectful?` makes a request,
  `:leaves?` hands the page back through `:webapp/leave!`, anything else
  reduces — so both dispatchers read one map and cannot disagree about which
  controls do something. `url-for` receives the VALUE, because a `<select>`
  has ONE handler for N options by construction.

  Options are valued by SLUG rather than by address: `url-for` builds the
  address, so an option carrying one too would be the same fact written twice
  and free to disagree. Every listed project is open, so nothing here is
  disabled or labelled gone — that state existed for a heartbeat registry
  and the daemon's has no such row."
  [projects current]
  (when (seq projects)
    (into [:select {:class "project-switcher" :data-region "nav/switcher"
                    :aria-label "switch project"
                    :on {:change [:project/goto]}}]
          (for [{:keys [slug]} projects]
            [:option (cond-> {:value slug}
                       (= slug current) (assoc :selected true))
             slug]))))

(defn app-shell
  "The application shell: global sections across the top, section-local
  navigation on the left, in-page detail on the right, content in the middle.
  Pure — data in, hiccup DATA out.

  `:nav/sections` is link DATA (`marked-sections`), because navigation is the
  one thing that must be identical on every page. `:nav/local` and
  `:nav/detail` are HICCUP, because a pane is a pane: the Code section's left
  carries a filter box as well as links, and a form's right rail carries
  callers and covering tests as content. Forcing them through a link list is
  what dropped the filter box and silently broke it.

  **ONE level of section navigation, and there used to be two.** A second bar
  named the pages inside the current section, on the argument that a section is
  a CAPABILITY rather than a screen — which was right about the SECTIONS and
  wrong about the BAR. Every section ended up holding exactly one page, so the
  strip rendered `Paths` under `REST` and `Settings` under `Config`: a full grid
  row restating the tab a reader had just clicked, and the `<h1>` already names
  the page. Deleted 2026-08-28 with `section-pages`, `marked-section-pages` and
  `section-nav-styles`.

  **An omitted pane is ABSENT, not empty.** A form page has no section-local
  navigation; rendering an empty box would take layout space and say nothing.
  The grid collapses to whichever panes exist.

  Lives in THIS application, deliberately NOT in `slopp.http`: the panes are
  this application's design, and a framework carrying an opinion about
  navigation has stopped being a framework. The split made that argument
  literal — `slopp.http` ships as a jar to anyone, and this shell ships to
  nobody."
  [{:nav/keys [sections local detail switcher search lens]} & content]
  (cond-> [:div {:class "app"}
           ;; `search` is HICCUP, like :nav/local and :nav/detail and unlike
           ;; :nav/sections — a pane is a pane, and forcing a form through a
           ;; link list is precisely what dropped the filter box once already.
           ;; Between the links and the switcher because it acts on THIS
           ;; store: it belongs with the controls that move you around inside
           ;; one application, while the switcher keeps the trailing edge
           ;; because it changes which application you are in.
           [:header {:data-region "nav/sections"} (nav-links sections) search switcher]]
    (some? local)  (conj [:nav {:data-region "nav/local"} local])
    ;; the lens switcher belongs INSIDE main, because a lens is scoped to
    ;; the subject that pane shows — in the global bar it would read as
    ;; applying to the whole application. Omitted entirely when there is
    ;; none, which is this shell's standing rule for an absent pane.
    true           (conj (into [:main {:data-region "main"}]
                               (cond->> content
                                 (some? lens)
                                 (cons [:div {:data-region "nav/lens"} lens]))))
    (seq detail)   (conj (into [:aside {:data-region "nav/detail"}] detail))))

(def display-options
  "What the namespace rail can show or hide, as DATA — one entry per toggle.

  Three things need to agree about an option: the rail draws a checkbox for
  it, the listing filters by it, and the browser turns a click back into the
  key that names it. Written out three times they drift, and the way they
  drift is silent — a toggle that renders and filters nothing looks exactly
  like a toggle nobody has clicked. So the option is the data and all three
  read it.

  `:hides?` is a predicate on a WIRE row: true means \"this row is hidden
  while the toggle is off\". `:data` is what the checkbox carries in the DOM
  and what [[option-for]] maps back to `:key`, so the round trip through the
  browser goes through one table rather than through two matching literals.

  Both default to OFF, which is the whole point: what you want from a
  namespace on arrival is its SURFACE. `slopp-server.ui.client.app` opened with two
  undocumented `defonce`s before the first function, and neither is why you
  came.

  **State is detected as `defonce`, and that is a proxy.** It is the idiom for
  a value that must survive a reload, which in practice is every atom in this
  store — but a `def` holding an atom would be missed, and nothing on the wire
  would let this know. Recorded rather than papered over: if it starts
  mattering, the fix is a field from the API, not a cleverer guess here."
  [{:key :private? :data "private" :label "private definitions"
    :hides? :private?}
   {:key :state? :data "state" :label "state variables"
    :hides? #(= "defonce" (str (:kind %)))}])

(defn shown-defs
  "`rows` filtered by `show`, a map of `{option-key true}`.

  A row is hidden while ANY [[display-options]] entry that hides it is off, so
  a private `defonce` needs both toggles rather than either — a toggle that
  revealed rows its own label does not name would be lying about what it
  controls.

  `show` is read with `get`, so nil, `{}` and an unknown key all mean the same
  thing: nothing extra asked for. That is what makes the default correct
  without anywhere having to say so — a fresh page has no `:show` in state at
  all.

  Order is the STORE's throughout. A filter that also sorts would make turning
  an option on move the rows a reader was already looking at."
  [rows show]
  (vec (remove (fn [row]
                 (some (fn [{:keys [key hides?]}]
                         (and (not (get show key)) (hides? row)))
                       display-options))
               rows)))

(def tier-badges
  "Purity tier → the mark on a NAMESPACE's heading, for the tiers worth
  marking.

  `module_purity` scopes by namespace PATH, most-specific-wins, so a tier is a
  property of the namespace and every form in it shares one. That grain is the
  whole point: the first version of the effect badge keyed off the tier and
  would have marked all 23 forms in `slopp-server.ui.hub` or none of them, which is
  the opposite of letting anything stand out. The per-FORM fact is
  `:effectful?` ([[form-badges]]), and on that same namespace it marks 5.

  Both facts are worth having and they answer different questions. The tier is
  a PERMISSION — what this namespace is allowed to do, declared and gated.
  `:effectful?` is an OBSERVATION — what a particular form actually does. A
  namespace can be `:external` and hold twenty pure functions, which is exactly
  what `hub` is.

  **`:pure` is deliberately unmarked.** It is eight of this store's eleven
  modules, and a mark carried by the majority has stopped being a mark.
  Absence reads as pure without ambiguity: the effective tier is always one of
  the three, and an UNDECLARED namespace resolves to `:external` — which is
  marked. So there is no state where a missing badge means \"nobody said\"."
  {"external" {:mark "⚡" :title "external — this namespace may do IO: files, network, subprocesses, the clock"}
   "internal" {:mark "🔁" :title "internal — may mutate state inside this process, but touches nothing outside it"}})

(defn first-sentence
  "The first sentence of `s`, on ONE line, or `\"\"` for nothing.

  The collapsed state of a docstring. The pane's whole design rests on
  preserving the author's hard wrapping — that is what makes a docstring read
  as source rather than as an essay — and a one-line SUMMARY wants the
  opposite, so this unwraps deliberately rather than by accident.

  A sentence ends at `.`, `?` or `!` followed by whitespace or the end of the
  string. **Except after an abbreviation**: `e.g.`, `i.e.`, `etc.`, `vs.`,
  `cf.` and a bare initial are all followed by a space and none of them ends
  anything. Without that, the summary of a form documented with an example
  truncates mid-clause and reads as a complete thought — which is the worst
  outcome for a line whose entire job is to stand in for a paragraph.

  Scanned by POSITION rather than by searching for the terminator: it is a
  character that also occurs inside the abbreviations being skipped, so
  searching by value finds the wrong one — `index-of` on `\"e.g. x.\"` returns
  the dot in `e.g`, not the one that ended anything.

  No terminator at all means the whole string, which is right for the short
  docstrings that are already one phrase."
  [s]
  (let [t (str/trim (str/replace (str s) #"\s+" " "))
        n (count t)]
    (if (str/blank? t)
      ""
      (loop [i 0]
        (cond
          (>= i n) t

          (and (#{\. \? \!} (nth t i))
               (or (= i (dec n)) (= \space (nth t (inc i)))))
          (let [head (subs t 0 (inc i))]
            (if (re-find #"(?i)(^|\s)(e\.g|i\.e|etc|vs|cf|[a-z])\.$" head)
              (recur (inc i))
              head))

          :else (recur (inc i)))))))

(defn doc-open?
  "Whether the comment on `nm` is expanded, given the rail's `show` state.

  A DEFAULT plus OVERRIDES: `:docs?` is what the rail's two buttons set, and
  `:doc` is `{name bool}` for the ones a reader has since clicked. The
  override wins in both directions — collapsing one comment while everything
  is open is as necessary as the reverse, and a model that only remembered
  \"opened\" could not express it.

  **The buttons CLEAR the overrides rather than merely setting the default**,
  which is the whole reason this is two fields. \"Expand all\" that left a
  previously-collapsed comment shut would not be expand all; the word is the
  contract.

  Always a boolean, because the caller renders on it and `nil` and `false`
  reaching a class-name branch differently is a bug waiting for a namespace
  whose form is called \"doc\"."
  [show nm]
  (boolean (get (:doc show) nm (:docs? show))))

(def ^:private elide-shape
  "The repeating width/indent pattern the elided lines are cut from.

  Seven entries, opening wide and varying after — a definition's first line is
  usually its longest, and lines that are all one width read as a chart rather
  than as code."
  [{:width 88 :indent 0}
   {:width 62 :indent 1}
   {:width 74 :indent 1}
   {:width 45 :indent 2}
   {:width 68 :indent 1}
   {:width 81 :indent 0}
   {:width 55 :indent 2}])

(defn elided-lines
  "`n` fake source lines for the form named `nm` — `[{:width :indent} …]`.

  Importance as something COUNTABLE. A lightness ramp asks a reader to judge a
  shade against shades elsewhere on the page; four lines against two is a
  comparison anyone makes at a glance, and it survives greyscale, a bad
  monitor and colour blindness.

  The pattern is fixed and ROTATED BY NAME LENGTH rather than hashed, and that
  is a portability decision rather than a shortcut. This namespace is `:cljc`:
  the tests run on the JVM and the pane runs in a browser, and `hash` does not
  agree across those two. Anything built on it would assert one picture in the
  suite and draw a different one on screen — the exact class this project
  exists to notice, and invisible in both directions because both pictures
  look plausible.

  Rotation is variation enough. Neighbouring definitions rarely share a name
  length, so a column does not read as a repeating stamp; two that do are
  drawing decoration, not information. The INFORMATION is the count."
  [nm n]
  (let [k   (count elide-shape)
        off (mod (count (str nm)) k)]
    (vec (for [i (range n)]
           ;; the FIRST line is always the widest, whatever the rotation —
           ;; a definition opens with its signature and that is its longest
           ;; line. Rotating that too made the skeleton start narrow, which
           ;; reads as a fragment rather than as the top of something.
           (nth elide-shape (if (zero? i) 0 (mod (+ off i) k)))))))

(defn- tier-mark
  "The badge element for purity `tier`, or nil for a tier that carries none.

  Three places show this — the namespace heading, the nav's module rows, and
  the namespace rows under them — and they must agree, because a reader who
  sees ⚡ in the nav and no ⚡ on the page it opens will believe the page.
  Three copies of the same `when-let` is exactly how that stops being true."
  [tier]
  (when-let [t (tier-badges (str tier))]
    [:span {:class "src-badge" :title (:title t)} (:mark t)]))

(def form-badges
  "Marks a definition can carry, as DATA — one entry per badge, in the order
  they are drawn.

  The third channel. Hue says what CATEGORY a form is and lightness says how
  important it is; a badge says everything a reader would otherwise have to
  infer from colour alone.

  **Values are badged; functions are not, and that is not an oversight.** A
  function already carries a mark of its own — its argument list. `[x]` beside
  a name says \"callable\" without any help. A `def` has nothing there, so hue
  was the only thing distinguishing it, and a category that exists only as a
  colour is invisible to a reader who cannot separate those two colours. The
  badge is what makes the pane legible in greyscale.

  It also splits a distinction hue deliberately does not: `def` and `defonce`
  share blue because both are values, and the marks say which — a constant, or
  something that survives a reload because it holds state.

  **`exported?` and `effectful?` are two different facts and get two marks.**
  `^:export` is about NAMING — the compiler must not munge it, because
  JavaScript calls it by that name; it is a door into the code from outside.
  `effectful?` is about EFFECT — the form performs one, or reaches one through
  a callee. A form can be either, both or neither, and collapsing them into one
  \"outward\" badge would answer neither question.

  **It is `effectful?` and deliberately NOT the purity tier**, which is what
  this first asked for. A tier is declared per NAMESPACE (`module_purity`,
  most-specific path wins), so a tier badge would mark every form in
  `slopp-server.ui.hub` or none of them — the exact opposite of letting the effectful
  ones stand out, which is the only reason to have the badge. A mark that shows
  up on every row has stopped being a mark. Caught by slopp on review of the
  ask, before the field was built.

  `:mark` is a mnemonic, `:title` is the definition. The emoji alone is a
  guess — 🔒 could be private, could be locked, could be security — so every
  badge carries the words as a tooltip and the words are the contract. The
  tests assert titles, not glyphs, for exactly that reason: the glyph is the
  part that can be swapped without anything breaking.

  **`:at` places the mark, and the split is what the reader is asking.** What
  a thing IS — a constant, a pinned value, a private helper — LEADS the name,
  because that is the first question when scanning a column and the answer
  belongs where the eye already is. How it reaches OUTSIDE — exported,
  external — TRAILS it, because that is a second question you ask about a
  particular row once you have found it.

  The leading marks sit in a fixed GUTTER that is rendered whether or not
  anything fills it. Without that, a row with two marks would push its name
  two glyphs right of a row with none, and the straight left edge this listing
  is built on would be gone on the first private helper.

  Ordered so a row with several always reads the same way round — badges that
  reorder between renders read as different badges."
  [{:key :constant :mark "📦" :at :lead :when? #(= "def" (str (:kind %)))
    :title "a constant — one value, evaluated once at load"}
   {:key :state :mark "📌" :at :lead :when? #(= "defonce" (str (:kind %)))
    :title "defonce — survives a reload, which is how mutable state is held"}
   {:key :private :mark "🔒" :at :lead :when? :private?
    :title "private — not part of this namespace's surface"}
   {:key :exported :mark "🌐" :at :trail :when? :exported?
    :title "exported — ^:export, so JavaScript can call it by this name"}
   {:key :effectful :mark "⚡" :at :trail :when? :effectful?
    :title "effectful — it performs or reaches an effect, so it is not pure"}])

(defn badges
  "The [[form-badges]] marks that apply to wire row `row` at position `at`
  (`:lead` or `:trail`), as elements.

  One reader for the table, called twice, rather than the same `filter` written
  out at each end of the signature line. The two differ only in `:at`, and two
  copies of one predicate is how a badge ends up rendering in both places or
  neither."
  [row at]
  (let [marks (for [{:keys [mark title when?] :as b} form-badges
                    :when (and (= at (:at b)) (when? row))]
                [:span {:class "src-badge" :title title} mark])]
    ;; **The SEPARATORS come with the badges**, and live here for the reason
    ;; this function exists at all: two call sites spacing their own group is
    ;; how one end gets it and the other does not. Every mark used to run into
    ;; its neighbour — `rate [kg]🌐⚡`, `📦rate`, `📦🔒rate` — which is this
    ;; project's oldest rule, fourth instance, and the largest: a whole control.
    ;;
    ;; Invisible because no form in the shared fixture carries a badge, so the
    ;; path rendered on no driven screen. The test that proved a badge renders
    ;; COUNTED them, and a badge that renders and a row that reads correctly
    ;; are different questions.
    ;;
    ;; Nothing at all when there are none: the leading gutter is what keeps
    ;; this listing's left edge straight, and an unconditional space would undo
    ;; the thing the gutter is for.
    (when (seq marks)
      (let [spaced (interpose " " marks)]
        (if (= :lead at)
          (concat spaced [" "])
          (cons " " spaced))))))

(def kind-classes
  "Definition kind → the class that colours its NAME. Hue carries CATEGORY and
  nothing else: green for a function, blue for a value.

  The kind used to be a word beside every name, and fourteen rows each
  prefixed `defn` is fourteen copies of the least surprising fact on the
  screen. Almost everything in a namespace is a function, so the token carried
  information only on the rows where it was NOT `defn` — which is exactly what
  colour is good at and text is bad at.

  **Three channels, three jobs**, and keeping them separate is what stops this
  becoming a set of exceptions:

  | channel   | carries                                    |
  |-----------|--------------------------------------------|
  | hue       | category — function or value               |
  | lightness | importance within the namespace            |
  | badge     | modifiers — private, mutable, effectful     |

  So `defn` and `defn-` share a hue, and `def` and `defonce` share a hue.
  Privacy was briefly a paler green, which reads as *a lesser function* — the
  wrong claim, and one that would have collided head-on with lightness meaning
  importance. It is a badge now ([[form-badges]]).

  `def` and `defonce` share blue for the same reason: constant-versus-mutable
  is a modifier on \"value\", and there are three `defonce` in this whole store
  against 187 forms. A rare thing should read as a MARK, not as a fourth
  colour category a reader has to learn.

  **A kind that is not in here gets NO class** and reads in the ordinary link
  colour — a kind this table has no opinion about must not borrow a hue that
  is already spoken for."
  {"defn"    "src-fn"
   "defn-"   "src-fn"
   "def"     "src-val"
   "defonce" "src-val"})

(defn option-for
  "The [[display-options]] entry whose `:data` is `s`, or nil.

  The one hop that leaves `:cljc`. A checkbox carries a string into the DOM
  and the browser hands that string back on a click; resolving it HERE rather
  than in `slopp-server.ui.client.app` is what makes the round trip an ordinary
  in-image assertion instead of something only a browser could disprove.

  **nil for anything unrecognised**, so the caller writes no state at all
  rather than writing under a key nothing reads. The two failures are worth
  telling apart: state under an unknown key is invisible and permanent, where
  no state at all is a toggle that visibly does nothing on the first click."
  [s]
  (first (filter #(= (str s) (:data %)) display-options)))

(defn ns-rail
  "The Code section's right RAIL for one namespace: what you SET while reading
  it, where [[form-rail]] is what you consult.

  Returns a VECTOR of sections, which is what `app-shell` splices into the
  rail — so this grows by appending a section, and the one that exists today
  is `display`.

  The toggles are built from [[display-options]] rather than written out. That
  is not tidiness: an option the rail draws but the filter ignores, or the
  reverse, looks exactly like an option nobody has clicked, so the failure is
  invisible in the one place a reader would look for it. Adding an option is
  an entry in that vector and nothing here changes.

  **Each toggle says how many rows it controls.** \"private definitions · 4\"
  turns the choice into an informed one, and a zero says the toggle would do
  nothing — which is worth knowing before clicking it rather than after. The
  count is of the WHOLE namespace, not of what is currently visible, because
  it answers \"what am I not seeing\".

  The namespace's own row is excluded from the counts for the same reason
  [[ns-outline-main]] excludes it from the listing: it is the header, not a
  definition, and no toggle governs it."
  [{:keys [ns forms tested-by]} show]
  (let [defs (remove #(= (str ns) (str (:name %))) forms)]
    [(into [:section
            [:h3 "display"]]
           (for [{:keys [key data label hides?]} display-options]
             [:label {:class "rail-toggle"}
              ;; the action carries the value it SETS, computed from what the view
              ;; already knows — so the handler never has to read `.-checked`
              ;; off a DOM node, which is the one thing that cannot run on a JVM
              [:input (cond-> {:type "checkbox" :class "display-toggle"
                               :data-show data
                               :on {:change [:display/set key (not (boolean (get show key)))]}}
                        (get show key) (assoc :checked true))]
              [:span label]
              [:small (str " · " (count (filter hides? defs)))]]))
     ;; comments get BUTTONS, not a checkbox, and that is a deliberate
     ;; difference from the display toggles above. A toggle is a preference
     ;; you set; expand-all is an ACTION you take on what is in front of you.
     ;; Both are always drawn so the pair reads as one two-position control
     ;; rather than a button that disappears when used, and each carries the
     ;; state it SETS rather than the state it is in — the browser hands back
     ;; a string, and a control naming its own state would have to be read
     ;; backwards somewhere.
     [:section
      [:h3 "comments"]
      [:div {:class "rail-buttons"}
       (for [[k label] [["collapse" "collapse all"] ["expand" "expand all"]]]
         [:button (cond-> {:class "doc-toggle" :data-docs k
                           :on {:click [:docs/all (= k "expand")]}}
                    (= (boolean (:docs? show)) (= k "expand"))
                    (assoc :disabled true))
          label])]
      [:p [:small "collapsed shows each comment's first sentence"]]]
     [:section
      [:h3 "tested by"]
      (if (seq tested-by)
        (into [:ul]
              (for [t tested-by]
                [:li [:a {:href (str "/store/ns/" t)} (str t)]]))
        [:p [:small "no tests require this namespace directly"]])]]))

(defn form-href
  "Where an outline row for `ns` points: the FORM page when the row carries a
  `:form-id`, its source otherwise.

  The outline used to point every name at `/store/source/<ns>/<name>` because
  that was the only address it could build — `:form-id` was not on the wire
  (a reader-API gap, since closed). So the one pane whose whole job is to be an
  alternative to reading the source offered source as its only destination.

  The fallback is not defensiveness. A row with no id still goes SOMEWHERE
  plainer rather than becoming dead text, and an id-less row is exactly the
  case where a reader most needs the escape hatch.

  Computed once because the name and the elided skeleton beneath it are two
  links to the same place, and a listing where they disagree sends a reader
  somewhere different depending on which half of the row they hit."
  [ns row]
  (if-let [fid (:form-id row)]
    (str "/store/form/" fid)
    (str "/store/source/" ns "/" (:name row))))

(defn ns-outline-main
  "The Code section's MAIN pane for one namespace, laid out like SOURCE.

  Takes the WIRE shape — exactly what `GET /api/ns/:ns` returns — rather
  than the store's shape. That is the whole point of it being one function:
  the server renders it into the page, and the client renders the SAME
  function into the same pane after a fetch, so a click and a refresh cannot
  show different things. If it took the store's shape (symbols, store-side
  keys) the server would render correctly and the client would render nils,
  and only a browser would ever tell you.

  `show` is the second argument rather than a key on that map, because it is
  VIEW state and the map is the wire. Merging them would make the one shape
  this function is careful about into a place where local state also lives.

  **It was a bulleted list of paragraphs**, which is the wrong genre for the
  thing it describes. A docstring in this store is hard-wrapped prose written
  to be read in a file; flowed into an `<li>` it lost its line breaks, its
  paragraphs ran together, and eleven of them in a row read as an essay with
  bullets rather than as a namespace. Four changes, all of them about genre:

  - The doc is a `<pre>`, so the author's own wrapping survives. This is the
    one that matters; the rest follow from it.
  - The doc comes BEFORE the name, where a docstring sits in source.
  - Forms are separated by space rather than by a bullet.
  - The namespace's OWN doc is the header. It arrives as a form whose name is
    the namespace, and rendering it as a row meant printing `demo.core` twice
    in two inches — once as the heading and once as the first bullet under it.

  A definition shows `:kind` as a hue, its arities, its schema and its marks.
  `:private?` is not drawn as text: it is a badge, and it is what
  [[shown-defs]] filters on.

  **Comments COLLAPSE to their first sentence** and each one toggles on its
  own ([[doc-open?]]) — the rail's two buttons set the default, a click on a
  comment overrides it for that form. Collapsed is the default because
  arriving at a namespace you want to know what is IN it, and eleven full
  docstrings is a wall you read past rather than a listing you scan. Only a
  comment with something hidden carries the toggle: a control that does
  nothing is worse than no control, because it invites a click and answers
  with a redraw that looks identical.

  The listing is FILTERED by `show` ([[display-options]]) — private helpers
  and state vars are hidden until asked for. The counts live in [[ns-rail]],
  so what is hidden is stated rather than merely absent."
  ([data] (ns-outline-main data nil))
  ([{:keys [ns forms tier]} show]
   (let [own  (first (filter #(= (str ns) (str (:name %))) forms))
         all  (remove #(= (str ns) (str (:name %))) forms)
         defs (shown-defs all show)
         ;; ranked over ALL definitions, never over the visible subset.
         ;; Ranking the filtered rows would restain the listing every time a
         ;; toggle opened, so the colour would be about the filter rather than
         ;; about the code — and a reader who had just revealed the private
         ;; helpers would watch everything they were already reading change.
         ;; FIVE steps, because the skeleton below each definition draws one line
         ;; per step and one-to-five is the range a reader can count without
         ;; counting. The hue keeps a lightness ramp over the same number, so
         ;; the two channels agree rather than competing — but the lines are
         ;; the legible one, and a shade is only ever a comparison.
         step (importance/steps all 5)
         ;; EVERY form gets a comment block, documented or not. A form with no
         ;; docstring used to render nothing, which is indistinguishable from
         ;; one whose comment failed to render — and on a screen whose whole
         ;; claim is "this is what is in the namespace", a silent gap is the
         ;; one thing it must not do. `(none)` is a finding stated out loud,
         ;; the same rule the rail already follows for coverage.
         doc  (fn [nm d & [extra]]
                (let [full  (str d)
                      none? (str/blank? full)
                      short (first-sentence full)
                      more? (not= short (str/trim (str/replace full #"\s+" " ")))
                      open? (doc-open? show nm)]
                  [:pre (cond-> {:class (str "src-doc"
                                             (when extra (str " " extra))
                                             (when none? " src-doc-none")
                                             ;; clickable-either-way, so the
                                             ;; stylesheet can offer a pointer
                                             ;; without an attribute selector
                                             (when more? " src-doc-more")
                                             (when (and more? (not open?))
                                               " src-doc-short"))}
                          ;; nothing behind it, so no toggle and no ellipsis —
                          ;; either would promise something that is not there
                          more? (assoc :data-doc (str nm)
                                       :on {:click [:docs/one (str nm)]}))
                   (cond none? "(none)"
                         open? full
                         :else short)]))]
     [:div {:class "ns-source"}
      ;; the TIER rides on the heading, because that is its grain: it governs
      ;; the whole namespace, not any one form in it. See [[tier-badges]] for
      ;; why :pure carries no mark.
      (cond-> [:h1 (str ns)]
        (tier-mark tier) (conj (tier-mark tier)))
      (doc ns (:doc own) "src-ns-doc")
      (into [:div {:class "src-defs"}]
            (for [{:keys [name kind sig schema] d :doc :as row} defs]
              [:div {:class "src-def"}
               (doc name d)
               [:div {:class "src-sig"}
                ;; the leading GUTTER: what this thing IS. Always rendered,
                ;; even empty — it hangs left of the text column, so every
                ;; name starts at the same x whether it carries two marks or
                ;; none. Ragged names would undo the straight edge the whole
                ;; listing rests on.
                (into [:span {:class "src-lead"}] (badges row :lead))
                ;; hue is CATEGORY — see [[kind-classes]] for the three
                ;; channels, and for why an unlisted kind gets no hue at all
                [:a {:href (form-href ns row)
                     ;; hue and lightness are separate CLASSES because they are
                     ;; separate channels — a combined token per pair would be
                     ;; eight names meaning two things
                     :class (str "src-name"
                                 (when-let [k (kind-classes (str kind))]
                                   (str " " k))
                                 (when-let [s (step name)]
                                   (str " src-w" s)))}
                 (str name)]
                ;; ONE arity inline, several stacked — never `(str sig)`, which
                ;; printed the Clojure vector: `render! ["[]"]` on screen, a
                ;; source-shaped pane showing something source never says.
                ;; A `def` has no arities at all and gets no brackets, which is
                ;; the distinction :kind was asked for in the first place.
                ;; the separator is MARKUP, never a margin. Two inline elements with
                ;; only CSS between them have NOTHING between them in the text,
                ;; and this pane's whole claim is to be laid out like source —
                ;; where `(defn rate [kg zone])` has a space in it. Without this
                ;; the row read `rate[kg zone]`, to a headless reader and to a
                ;; screen reader alike.
                (when (seq sig)
                  (list " "
                        (if (= 1 (count sig))
                          [:span {:class "src-args"} (first sig)]
                          (into [:span {:class "src-args"}]
                                (interpose " "
                                           (for [s sig]
                                             [:span {:class "src-arity"} s]))))))
                ;; and how it reaches OUTSIDE, trailing — a second question,
                ;; asked about a row you have already found
                (badges row :trail)]
               (when (seq (str schema))
                 [:div {:class "src-schema"} (str schema)])
               ;; the elided body: one line per step of importance, so the
               ;; thing a reader is judging is a COUNT rather than a shade.
               ;; Fuzzy on purpose — this is not the code, it is how much
               ;; code, and a skeleton that looked crisp would invite someone
               ;; to try to read it.
               ;; and it LINKS where the name links. The skeleton is the biggest
               ;; target in the row and it stands for the code, so a reader who
               ;; has just judged a form by its five lines is already pointing
               ;; at the thing they want to open.
               ;;
               ;; aria-hidden and tabindex -1 together: this is the same
               ;; destination a SECOND time, and a duplicate tab stop that
               ;; announces nothing is a trap rather than a convenience. The
               ;; name above is the accessible route and it is untouched.
               [:a {:href (form-href ns row)
                    :class "src-elide-link" :aria-hidden "true" :tabindex "-1"}
                (into [:div {:class "src-elide"}]
                      (for [{:keys [width indent]} (elided-lines name (inc (step name 0)))]
                        [:span {:class (str "src-elide-line src-elide-i" indent)
                                :style {:width (str width "%")}}]))]]))
      ;; coverage used to be rendered HERE, as one more definition so it would
      ;; read as part of the source. It read as part of the source, which is
      ;; the problem — it is not one. It lives in `ns-rail` now, where the
      ;; things you consult beside the code live, and the listing is
      ;; definitions and nothing else.
      ])))

(defn timeline-main
  "The Review section's main pane: what is in flight, then what has been
  finished. Takes `GET /api/timeline`'s shape.

  Every commit point row links its own change screen through the range the
  MODEL computed, so this stays a template — the oldest commit point has no
  range and renders as plain text rather than a link to nowhere.

  The KEY is `:commit-points` and the NOUN is \"commit points\". slopp renamed
  this concept from `milestone` on 2026-09-02 and the wire followed; a
  hyphenated key rendered as a heading would wear the transport on the
  outside."
  [{:keys [commit-points working]}]
  [:div
   [:h1 "review"]
   ;; an endpoint CAN answer with nothing, and this pane used to THROW on it:
   ;; `(zero? (:forms working))` with no working set is an NPE, which is a
   ;; white page in a browser. Nothing ever handed it a nil, because it is one
   ;; of the two screens with no canned response — so no driver and no test
   ;; ever reached it without bringing data of their own.
   (when-not (or commit-points working)
     [:p "nothing to show — this project's timeline came back empty."])
   ;; each section only when its half arrived. The two are independent on the
   ;; wire and a store with no commit points yet is an ordinary state, not an
   ;; error to report.
   (when working
     [:section
      [:h2 "in flight"]
      (if (zero? (:forms working))
        [:p "nothing since " [:code (str (:since working))]
         " — the working set is clean"]
        [:div
         [:p (str (plural (:forms working) "form") " in "
                  (plural (count (:namespaces working)) "namespace")
                  " since " (:since working))]
         (into [:ul] (for [p (:prompts working)] [:li p]))
         (when-let [n (:more-prompts working)]
           [:p [:small (str "… and " (plural n "more ask"))]])])])
   (when (seq commit-points)
     [:section
      [:h2 "commit points"]
      (into [:ol]
            (for [m commit-points]
              [:li
               (if (:range m)
                 [:a {:href (str "/change/" (:range m))} (:description m)]
                 [:span (:description m)])
               " "
               [:small (str (:at m))
                ;; MARK ONLY WHAT NARROWS. `:status` is on every commit point and
                ;; is \"green\" on essentially all of them, so a badge on every
                ;; row is one nobody reads — the same rule that keeps `public`
                ;; off the API index and the effect mark off a read-only row.
                ;; Absence means green; the mark means look.
                ;;
                ;; A WORD, not a colour. `.red` as a background is nothing at
                ;; all to a reader without the stylesheet, which is exactly how
                ;; this project shipped a diff whose added and removed lines
                ;; were the same string in two tints.
                (when (= "red" (:status m))
                  " · recorded red")
                (when-let [n (:more-lines m)]
                  (str " · " (plural n "more line")))]]))])])

(defn spine-view
  "The path a form sits on: root caller at the top, leaf callee at the bottom.

  Answers the question a one-hop rail cannot — *how does control get from
  there to here* — and it is the shape that survives fan-out. A form with two
  hundred callers renders here as `1 of 200` and stays one line, because the
  path spends its space on DEPTH and shows one option per position.

  Two moves from every rung, and they are different questions:

  - the rung's own name **re-centres** the whole page on it, so walking the
    chain is ordinary navigation with the back button as the trail;
  - a sibling option **turns the lock** — same focus, different route through
    it — via `/store/form/<id>/through/<other>`.

  Both are hrefs, so middle-click and open-in-new-tab keep working. A code
  browser is read in several tabs at once and a path is exactly the thing you
  want two of.

  **The weakest link is stated for the path as a whole.** Each rung shows the
  `:via` of the call it makes to the one below, but a reader scanning a chain
  reads the strong ones and remembers a strong chain; naming the least certain
  edge once, at the top, is what stops the path being trusted more than its
  evidence. slopp's own rule is never to present the reference graph as
  complete."
  [focus-id {:keys [rungs focus cycle? weakest truncated]}]
  (when (seq rungs)
    [:section {:class "spine"}
     [:h3 "the path through here"]
     (when weakest
       [:p [:small {:class (str "spine-weakest via-" weakest)}
            (if (= "static" weakest)
              "every call on this path is statically resolved"
              (str "weakest link on this path: " weakest))]])
     (when cycle?
       [:p [:small {:class "spine-cycle"} "the path comes back on itself — stopped there"]])
     ;; The one sentence that keeps this view honest. A capped neighbourhood
     ;; renders as a shorter path, and a shorter path is indistinguishable from
     ;; a path that genuinely ends — so a reader takes the top rung for a root
     ;; and the bottom for a leaf. Neither is claimed here unless it is true.
     (when truncated
       [:p [:small {:class "spine-cycle"}
            (str "the neighbourhood was TRUNCATED at "
                 (:node-cap truncated) " forms — this walk reached "
                 (plural (:depth-reached truncated) "hop")
                 ", so the ends of this path are where the DATA stopped, "
                 "not where the calls do")]])
     ;; short names on every rung. The focus arrives from `:name` and the
     ;; rest from `:form`, which is qualified — so a path rendered raw mixes
     ;; `deps-edn` with `slopp.index.deps-test/build-deps-edn-test-alias` and
     ;; the eye cannot follow the column. The module is already beside it.
     (let [short (fn [s] (let [s (str s)
                               i (.lastIndexOf s "/")]
                           (if (neg? i) s (subs s (inc i)))))
           ;; at most three alternatives inline. Nine of them wrapped over
           ;; three lines and buried the rung they belonged to; past about six
           ;; an unranked list carries no signal anyway. The rest are reachable
           ;; by re-centring on the rung, which is what the overflow links to.
           cap   3]
       (into [:ol {:class "spine-rungs"}]
             (for [[i r] (map-indexed vector rungs)
                   :let  [alts (remove #(= (:form-id %) (:form-id r)) (:options r))]]
               [:li {:class (str "spine-rung" (when (= i focus) " spine-here"))
                     :data-form (:form-id r)}
                (if (= i focus)
                  [:strong (short (:form r))]
                  [:a {:href (str "/store/form/" (:form-id r))} (short (:form r))])
                " " [:small {:class "module-tag"} (:module r)]
                ;; the space is in the MARKUP, not only in the stylesheet. A margin
                ;; separates these in a browser and nowhere else, so the text of
                ;; this screen read `demo.orderstatic` — one word, and a wrong
                ;; one. A reader without CSS is not only a headless driver.
                (when (:via r) [:span " " [:span {:class (str "spine-via via-" (:via r))}
                                           (:via r)]])
                (when (seq alts)
                  [:span {:class "spine-choice"}
                   [:small (str " " (inc (:choice r)) " of " (:alternatives r) " ")]
                   ;; the separator is in the MARKUP, for the same reason the module tag
                   ;; above carries its space: adjacent anchors with only a flex
                   ;; gap between them read as ONE word to anything without the
                   ;; stylesheet, and this rendered `band-forround-up`. The rule
                   ;; was already written three lines up and applied to the
                   ;; neighbouring span and not to this one — which is why no
                   ;; fixture caught it: every form here had a single callee, so
                   ;; no rung ever had two siblings to weld.
                   (into [:span]
                         (interpose ", "
                                    (for [o (take cap alts)]
                                      [:a {:class "spine-alt"
                                           :href (str "/store/form/" focus-id
                                                      "/through/" (:form-id o))}
                                       (short (:form o))])))
                   (when (< cap (count alts))
                     [:a {:class "spine-alt spine-more"
                          :href (str "/store/form/" (:form-id r))}
                      (str "+" (- (count alts) cap) " more")])])])))]))

(defn form-rail
  "The Code section's right RAIL for one form: what you consult while reading
  it — the recorded ask, the warranty, who calls it, and what it calls, each
  with its signature and doc INLINED.

  Returns a VECTOR of sections, which is what `app-shell` splices into the
  rail.

  The inlining is the point and it is why the rail exists: a link is not
  visibility, and the reason to open this screen is almost always to read the
  form WITH the things it reaches.

  **This is the EGO half of `ego re-center + path spine`** — the plan's
  call-navigation decision, of which only the spine had shipped. It goes
  through [[slopp-server.ui.callgraph/ego]]: ranked, capped, and honest about what it
  held back. Before that it rendered the wire list raw, so a form with forty
  callers got forty unranked rows — which is the wall `ego` was written to
  prevent, quoting its own docstring: past about six neighbours an unranked
  list ranks nothing, because it is doing the reader's sorting for them badly.

  **`:more` is a sentence, never a silent cut.** A list that truncates without
  saying so lets a reader conclude a function has no other callers, which is
  the specific way a comprehension tool produces a confident false belief —
  worse than showing nothing, because the reader does not know to look.

  **Both sides render identically, and that is now TRUE rather than aspirational.**
  The wire used to send callers as bare names and callees with their card
  (the reader-API gap `from-form-view` records); slopp fixed it and both
  sides carry `:sig`, `:doc`, `:why` and `:warranty`. Measured on the live
  listener, not read off a docstring — `from-form-view`'s still said
  otherwise.

  Every row is an ordinary href, so clicking a neighbour RE-CENTRES the page
  there and the back stack is the trail. That is the whole navigation model;
  it needs no state in `client.app`.

  `:via` arrives as a STRING — it is a keyword in the store and JSON has no
  keywords, so this renders it directly rather than calling `name` on it."
  [{:keys [why warranty note] :as data}]
  (let [{:keys [callers callees]} (callgraph/ego (callgraph/from-form-view data) (:form-id data))
        row  (fn [r]
               [:article
                ;; the separators are MARKUP. Inline siblings with only a CSS
                ;; margin between them have nothing between them in the text,
                ;; and a name running into its module is a wrong word.
                ;; the heading is the NAME. Module and via are metadata and get their
                ;; own line — a heading whose text is name+module+via is a bad
                ;; heading for anyone navigating the rail by heading, and the
                ;; separators inside it were being lost, so the row read
                ;; `quotedemo.orderstatic`: not a missing space but a wrong word.
                [:h4 (if (:form-id r)
                       [:a {:href (str "/store/form/" (:form-id r))} (:form r)]
                       [:span (:form r)])]
                [:p {:class "ego-meta"}
                 [:small {:class "module-tag"} (:module r)]
                 (when (:via r)
                   [:span " " [:span {:class (str "via-" (:via r))} (:via r)]])]
                (when (seq (:sig r))
                  [:p [:code (if (coll? (:sig r)) (str/join "  " (:sig r)) (str (:sig r)))]])
                (when (:doc r) [:p (first-sentence (:doc r))])])
        side (fn [heading {:keys [shown more]} empty-note]
               (into [:section [:h3 heading]]
                     (if (empty? shown)
                       [[:p [:small empty-note]]]
                       (cond-> (mapv row shown)
                         (pos? more)
                         (conj [:p {:class "ego-more"}
                                [:small (str "and " more " more — open the form to see "
                                             "them ranked among their own neighbours")]])))))]
    [[:section
      [:h3 "warranty"]
      [:p [:small (plural (:covered warranty) "covering test")]]
      (when why [:p [:em why]])]
     (side "callers" callers "nothing in this store calls it")
     (side "callees" callees "it calls nothing else in this store")
     [:footer [:small note]]]))

(defn source-main
  "The Code section's main pane for one form addressed by NAME. Takes
  `GET /api/source/:ns/:name`'s shape.

  The source arrives as a string and is a text node here, which is what
  escapes it. Serving arbitrary store source safely is the standing security
  dogfood, and moving the render into the browser does not retire it — it
  concentrates it in the one place that must never build markup by
  concatenation.

  **Widens two ways, and the asymmetry is deliberate.** UP to the namespace is
  the breadcrumb and is always there: it needs nothing but the name this page
  was addressed by. SIDEWAYS to `/store/form/:id` — callers, callees, the
  warranty, the spine — needs `:form-id`, which is a STORE identity and so
  arrives on this endpoint rather than in the published contract document. That
  document has to work for a producer running from a jar with no store at all;
  this endpoint answers with stored source and pretends to be nothing else.

  No id, no link and no gap where one would be. Every project on an older jar
  answers without it.

  **A missing `:source` is a SENTENCE, not a blank.** This pane drew an empty
  `<pre>` for a response that carried no source until 2026-08-30 — the one
  screen in this app the standing rule was never applied to, while
  `config-main`, `webapp-pages-main` and `pages-main` all degrade in words.
  A blank code block is indistinguishable from a form with an empty body, and
  the reader cannot tell which they are looking at."
  [{:keys [ns name source form-id]}]
  [:div
   [:nav [:a {:href "/store"} "code"] " / "
    [:a {:href (str "/store/ns/" ns)} ns]]
   [:h1 name]
   ;; **A missing source is SAID, not left blank.** This rendered
   ;; `[:pre [:code source]]` unconditionally, so a response without one drew
   ;; an empty code block — no error, no sentence, and a reader with no way to
   ;; tell a form with no body from a fetch that lost it. Every other pane in
   ;; this app already refused that: `config-main` says `unset`,
   ;; `webapp-pages-main` names the document it could not get. This was the
   ;; one that never had the rule applied.
   ;;
   ;; The address is named because it is what a reader can act on — the same
   ;; reason the nil branches elsewhere name theirs. An empty string and an
   ;; absent key are one fact here: a form always has a body, so neither is a
   ;; form with nothing in it.
   (if (seq source)
     [:pre [:code source]]
     [:p "no source came back — " [:code (str "/api/source/" ns "/" name)]
      " answered without one."])
   (when form-id
     ;; NAMES what is over there rather than saying `more`. A reader decides
     ;; whether to follow a link by what it promises, and `callers and callees`
     ;; is the promise; `more` is the same click with the information removed.
     [:p [:small [:a {:href (str "/store/form/" form-id)}
                  "its callers and callees"]]])])

(defn- module-target
  "The namespace a module's box links to, or nil when the module has none.

  Prefers the namespace NAMED for the module — `demo.hub` over
  `demo.hub.util` — because that is the one a reader means when they point at
  the box. Falls back to the first otherwise, which is arbitrary but lands you
  inside the right module, and the nav opens that module on arrival because it
  holds the current namespace.

  **nil is a real answer.** A module with no namespaces gets a box that is not
  a link, rather than one that looks clickable and goes nowhere — which is the
  state the whole diagram was in."
  [{:keys [module namespaces]}]
  (or (first (filter #(= (str module) (str %)) namespaces))
      (first namespaces)))

(defn module-graph
  "The architecture picture as SVG hiccup.

  Takes a `:picture` — boxes and endpoints already placed — and turns it into
  elements. Nothing here computes geometry and nothing here names a colour:
  positions come from `slopp-server.ui.graph/picture-of`, and appearance is class
  names the stylesheet owns.

  Being hiccup rather than an opaque blob is the point. Every box is a real
  element carrying `data-module`, so hover, selection and keyboard focus are
  ordinary DOM concerns, and the whole diagram is an in-image test that
  asserts on data instead of a screenshot.

  **`targets` makes the boxes LINKS**, `{module ns}` from [[module-target]].
  For a long time they carried `data-module` and nothing else: they lit up on
  hover — a stylesheet rule — and did nothing when clicked, which reads as
  broken rather than as absent. An `<a>` rather than a click handler because
  everything else already works on hrefs: `prefix-links` re-addresses it under
  the mount point, the client's delegated handler intercepts it, and
  middle-click and open-in-new-tab keep working, which matters for a diagram
  someone reads by opening three boxes at once.

  A module with no target stays a bare `<g>`. Rendering an anchor to nowhere
  would restore exactly the thing this fixed.

  **`tints` is an OVERLAY — `{module step}`, classes only.** The picture it
  annotates is the IDENTICAL picture: same nodes, same coordinates, same
  edges. That is the whole discipline of an overlay, and it is why the gap
  view is a lens over this rather than a second diagram — two readings of a
  diagram are only comparable if the diagram did not move between them, and a
  layout that reflows when you toggle an annotation makes the annotation
  unreadable.

  **Box LABELS drop the root every module shares**, which is named once on a
  plate in the corner instead — the same trade the nav makes, and for the same
  reason: `slopp-server.ui.` on eleven boxes is eleven copies of the fact a reader
  already has, and it is what forces the boxes wide enough to push the rows
  apart. The root is derived HERE, from the modules actually being drawn
  (`nodes` and `band` together), so there is one computation and nothing for a
  caller to disagree with. The plate is in the SVG rather than in the page
  heading because a diagram gets screenshotted away from the page.

  `data-module` and the `href` keep the WHOLE name. Those are addresses — one
  is what the client selects on, the other is a route — and an address that
  has been shortened for legibility is a broken address.

  Edges render BEFORE nodes so arrowheads sit behind labels rather than on
  them. Each edge is a POLYLINE — `:points` from `graph/diagram`, two or more
  — drawn as one cubic per segment with vertical tangents at the joins. A
  waypoint is not decoration: it is where the geometry decided the edge should
  cross that row, and drawing only the two ends would put the line back
  through the boxes it was routed around."
  ([picture] (module-graph picture nil nil))
  ([picture targets] (module-graph picture targets nil))
  ([{:keys [nodes band edges width height sketch]} targets tints]
   (let [root  (nsfilter/common-root (map :module (concat nodes band)))
         box   (fn [{:keys [module x y w h]} kind]
                 (let [g [:g {:class (str "module-node " kind
                                          (when-let [t (get tints module)]
                                            (str " gap-w" t)))
                              :data-module module}
                          ;; hand-drawn strokes REPLACE the rect rather than
                          ;; layering over it — two outlines at slightly
                          ;; different offsets reads as a printing error, not
                          ;; as a sketch
                          (if-let [ps (seq (get sketch module))]
                            (into [:g] (for [p ps]
                                         [:path {:class "sketch" :d (:d p)}]))
                            [:rect {:x x :y y :width w :height h :rx 8}])
                          [:text {:x (+ x (quot w 2)) :y (+ y (quot h 2) 5)
                                  :text-anchor "middle"}
                           ;; the LABEL loses the shared root; `data-module` and
                           ;; the href above keep the whole name, because those
                           ;; are addresses and an address may not be shortened
                           (nsfilter/without-root root module)]]]
                   (if-let [href (get targets module)]
                     [:a {:href href :class "module-link"} g]
                     g)))
         curve (fn [{:keys [points]}]
                 ;; One cubic per segment, tangents vertical at every join, so
                 ;; the route reads as a descent through the picture.
                 (str "M " (ffirst points) " " (second (first points))
                      (apply str
                             (for [[[ax ay] [bx by]] (partition 2 1 points)
                                   :let [dy (max 12 (quot (- by ay) 2))]]
                               (str " C " ax " " (+ ay dy) ", "
                                    bx " " (- by dy) ", " bx " " by)))))]
     (into [:svg {:class "module-graph" :viewBox (str "0 0 " width " " height)
                  :role "img" :aria-label "module dependency graph"}
            [:defs
             [:marker {:id "arrow" :viewBox "0 0 10 10" :refX 9 :refY 5
                       :markerWidth 6 :markerHeight 6 :orient "auto-start-reverse"}
              [:path {:d "M 0 0 L 10 5 L 0 10 z"}]]]
            ;; the hoisted root, once, in the picture's own bottom corner —
            ;; a diagram gets screenshotted away from the nav that would
            ;; otherwise be the only thing naming the project. nil when there
            ;; is no shared root, rather than a plate asserting one.
            (when root [:text {:class "graph-root" :x 8 :y (- height 8)} root])]
           (concat
            (for [e edges]
              [:path {:class "module-edge" :d (curve e)
                      :marker-end "url(#arrow)"
                      :data-from (:from e) :data-to (:to e)}])
            (for [n nodes] (box n "layered"))
            (for [b band] (box b "foundation")))))))

(defn gap-step
  "How thin `gaps` is, as a step `0`..`4` — light to dark, `0` meaning nothing
  missing.

  **Only the two STABLE axes.** A form either carries a docstring and a
  recorded ask or it does not, and that is the same answer in any process.
  `:uncovered` is not like that: slopp measures it against the SESSION's trace
  map, so a listener that has run nothing reports everything uncovered. Tinting
  by it would paint a freshly-booted store uniformly alarming for a reason
  about the process rather than about the code — and the reader cannot tell
  those apart from a colour. The number is still shown; it is just not what
  decides the shade.

  **Absolute, not ranked.** [[slopp-server.ui.importance/steps]] ranks within a
  namespace because \"important HERE\" is the question there. This asks *how
  much of what a reader needs is missing*, which has a true answer independent
  of neighbours. Ranking would guarantee that some module always looks worst,
  including in a store where everything is documented — manufacturing an alarm
  out of a distribution.

  A module with no forms is `0`. Nothing recorded about nothing is not a gap,
  and dividing by it would be the more interesting mistake."
  [{:keys [forms no-doc no-why]}]
  (if (or (nil? forms) (zero? forms))
    0
    (let [missing (+ (or no-doc 0) (or no-why 0))
          share   (/ (double missing) (* 2 forms))]
      (min 4 (long (* 5 share))))))

(defn module-table
  "The modules of a `GET /api/modules` response as a table, one row per module.

  The alternative to the picture rather than a caption for it. A diagram
  carries SHAPE and cannot carry SIZE: `/store` drawn is `2 module-node
  layered` and no module is named, no count appears, and every one of those
  numbers arrived on the wire and was spent on box positions.

  Ordered by [[slopp-server.ui.graph/reading-order]] — the band, then each layer from
  the base upward — so the table and the picture cannot disagree about what
  comes first. Sorting by size instead would read better and would be a second
  arrangement of one graph with nothing holding the two together.

  `:deps` go in BY NAME. It is the one fact a table would otherwise throw away
  that the diagram has, and each is a link, so the table is a way in rather
  than only a readout. Interposed with a real \", \" string: a comma supplied
  by CSS is nothing at all to a reader without it.

  Deliberately NOT the gap counts. Those are the `gaps` lens's subject, and a
  lens that shows everything is the default view with more rows in it.

  An absent `:gaps` renders as an em dash rather than `0`. This project has
  three recorded bugs of the shape `0 forms` — a missing number rendered as a
  measured zero — and a table is where that reads most like a fact."
  [{:keys [modules layers]}]
  [:table {:class "data-table"}
   [:thead
    [:tr [:th "module"]
     [:th {:class "num"} "namespaces"] [:th {:class "num"} "forms"]
     [:th {:class "num"} "tests"]
     [:th "tier"] [:th "depends on"]]]
   (into [:tbody]
         (for [{:keys [module namespaces tests tier foundation deps gaps]}
               (graph/reading-order {:modules modules :layers layers})]
           [:tr {:class (when foundation "foundation")}
            [:td [:a {:href (str "/store/module/" module)} module]
             ;; MARKED, not left to position. The band's meaning in the picture
             ;; is that it sits beneath everything; a row that has been read out
             ;; of the stack has lost that cue and nothing else says it.
             (when foundation [:small " foundation"])]
            [:td {:class "num"} (str (count namespaces))]
            [:td {:class "num"} (if-let [f (:forms gaps)] (str f) "—")]
            [:td {:class "num"} (str tests)]
            [:td tier]
            [:td (if (seq deps)
                   (into [:span]
                         (interpose ", "
                                    (for [d deps]
                                      [:a {:href (str "/store/module/" d)} d])))
                   "—")]]))])

(defn code-index-main
  "The Code section's main pane: the architecture as a picture.

  This pane used to be near-empty, on the reasoning that the namespace list
  was already the left pane and repeating it would show one list twice. That
  reasoning was right and the conclusion was wrong: what belongs here is not
  a list but the SHAPE of the system, which a nav cannot show.

  Cycles are called out ABOVE the diagram rather than left to be spotted in
  it. On a tangled store 'these modules are mutually entangled' is the most
  useful sentence on the screen, and geometry is a poor place to hide a
  finding.

  The link targets are computed HERE and handed down, because `modules` — the
  wire shape — is what knows which namespaces a module holds, and the
  `:picture` deliberately does not: it carries geometry and nothing else, so
  that a different layout could be swapped in without teaching it about code.

  Takes `GET /api/modules`'s shape PLUS a `:picture`, which does not come
  over the wire: `client.app` derives it with `slopp-server.ui.graph/picture-of` on
  arrival and adds it to the data. The API used to send placed boxes, and
  that is the arrangement this project's existence argued against — a
  producer that ships coordinates decides the diagram for every consumer it
  will ever have.

  **The `table` lens REPLACES the picture; the `gaps` lens tints it.** That is
  the difference between an alternative view and an overlay, and it is why the
  two are separate lenses rather than one busier screen: the picture is what
  the table exists to be readable INSTEAD of, so leaving it above would make
  the switch an addition rather than a choice."
  [{:keys [modules picture cycles layers]} lens]
  (let [nses    (reduce + 0 (map (comp count :namespaces) modules))
        band    (filter :foundation modules)
        ;; DESCEND. A box stands for a module, and since `/store/module/:m`
          ;; exists that is where it goes — the rung between the diagram and a
          ;; namespace. It used to jump PAST that page to a representative
          ;; namespace, which was right before the page existed and left it
          ;; almost unreachable after.
          ;;
          ;; Every module is a link, including one with no namespaces. The old
          ;; rule declined those because no representative namespace existed; a
          ;; module PAGE always does, so the box now goes somewhere real.
        targets (into {} (map (juxt :module #(str "/store/module/" (:module %)))) modules)]
    [:div
     [:h1 "code"]
     [:p (str (plural (count modules) "module") ", "
              (plural nses "namespace")
              (when (seq band)
                ;; NOT `plural` — it pluralises a NOUN by adding an s, and "of them"
                ;; is a phrase, so it rendered "5 of thems foundation" on the
                ;; served page for as long as the sentence existed
                (str " — " (count band) " of them foundation")))]
     ;; Under the gaps lens the numbers go BESIDE the picture, because a colour
     ;; is not a measurement — a reader can see that one box is darker and
     ;; cannot see by how much or in what.
     (when (= "gaps" lens)
       (let [g   (keep :gaps modules)
             sum (fn [k] (reduce + 0 (map #(or (k %) 0) g)))]
         [:div {:class "gap-summary"}
          [:h2 "where the record is thin"]
          [:p (str (sum :no-doc) " of " (sum :forms)
                   " forms carry no comment, and " (sum :no-why)
                   " have no recorded ask — nothing says why they were written.")]
          [:p [:small (str (sum :uncovered) " are uncovered. That one is measured "
                           "against THIS SESSION's trace map, so a process that "
                           "has run little reports much — which is why the tint "
                           "above ignores it and reads only the two counts that "
                           "mean the same thing in any process.")]]
          (into [:ul {:class "gap-rows"}]
                (for [m (->> modules
                             (filter :gaps)
                             (sort-by #(- (gap-step (:gaps %))))
                             (take 8))
                      :let [{:keys [forms no-doc no-why uncovered]} (:gaps m)]]
                  [:li {:class (str "gap-w" (gap-step (:gaps m)))}
                   [:a {:href (str "/store/module/" (:module m))} (:module m)]
                   " " [:small (str no-doc " undocumented, " no-why " with no ask, "
                                    uncovered " uncovered, of " forms)]]))]))
     (when (seq cycles)
       [:div {:class "finding cycles"}
        [:h2 (str (plural (count cycles) "dependency cycle"))]
        (into [:ul]
              (for [c cycles]
                [:li (clojure.string/join " → " (concat c [(first c)]))]))])
     (if (= "table" lens)
       (module-table {:modules modules :layers layers})
       (module-graph picture targets
                     (when (= "gaps" lens)
                       (into {} (map (juxt :module #(gap-step (:gaps %)))) modules))))]))

(defn current-section
  "The section `path` belongs to, or nil.

  Longest prefix wins, so `/store` beats the root. `\"/\"` matches only the
  root exactly — as a prefix it would match everything and make the first
  section permanently current.

  **Nil rather than a guess.** Marking a section active on a page that is not
  in it tells the reader something false, which is worse than telling them
  nothing."
  [path]
  (->> sections
       (keep (fn [s]
               (when-let [hit (->> (:prefix s)
                                   (filter (fn [p]
                                             (if (= "/" p)
                                               (= "/" path)
                                               (or (= p path)
                                                   (str/starts-with? path (str p "/"))))))
                                   (sort-by count)
                                   last)]
                 [(count hit) s])))
       (sort-by first)
       last
       second))

(defn marked-sections
  "`sections` with `:active?` on the one `path` is in — the shape `nav-links`
  wants, and the only thing a page should ever pass as `:nav/sections`. A page
  that assembles its own is how two pages come to disagree about where you
  are."
  [path]
  (let [cur (current-section path)]
    (mapv #(cond-> % (= (:label cur) (:label %)) (assoc :active? true))
          sections)))

(def lenses
  "The alternative views each screen offers over the SAME subject, in bar order.

  A lens is not a screen. It shows one subject a different way, and the click
  that means \"open this namespace\" means exactly that in every lens — which
  is what makes it a view rather than a MODE. The moment a lens changes what an
  action does, it needs mode treatment instead.

  **The default lens is the bare path and is not in this table.** `/store` is
  the module diagram; `/store/gaps` is the same diagram tinted. So a screen
  that never switches renders from exactly the URL it always had.

  ONE table, read by three things that would otherwise agree only by accident:
  [[route-for]] uses it to decide whether a trailing segment is a lens or
  nonsense, [[with-lens]] builds the hrefs from it, and the switcher chrome
  renders it. That is the pairing `every-registry-entry-can-actually-reach-the-screen`
  exists to catch — a registry and its consumer related only by a string
  literal.

  **This table means IMPLEMENTED, and it used to mean \"what can exist\".**
  That reading is what let five of seven entries render nothing: `namespaces`
  and `table` on the store, `table` and `gaps` on a module, `graph` on a
  namespace. `route-for` reads this to decide a url is VALID, so
  `/store/table` answered 200 and drew the plain diagram — leaving a reader
  certain they had looked at something they had not, which is the one thing
  `route-for` refuses for an unknown lens. It admitted these five because the
  table said they existed. A table cannot be both a wish list and the router's
  validity check; the wish list belongs in a backlog, where a deferred lens
  is visible as a deferral instead of as a dead url.

  `table` on the store is BACK, by the only route that admits one: it renders
  now. The remaining four are still deferrals, and `:module` and `:ns` need a
  plumbing change before they can be anything else — `app-view` hands `lens`
  to the code and form panes only.

  Bar order puts `table` beside the default because the two are alternative
  renderings of the whole subject; `gaps` is an overlay on one of them and
  goes last.

  Pinned by `every-declared-lens-actually-renders-something-different`, which
  asserts each entry both routes AND renders something the default does not."
  {:code ["table" "gaps"]
   :form ["source"]})

(def default-view
  "What the BARE path of each screen shows, NAMED — one entry per screen in
  [[lenses]].

  The default lens is not in `lenses` (it is the absence of a segment), so
  nothing could label it and a switcher would have listed only the
  alternatives. That is a one-way door: you reach `/store/gaps` and the way
  back is the browser's Back button or nothing. The default is a view like any
  other and a reader has to be able to say which one they are looking at.

  Keyed by the same screens as `lenses`, and
  `a-screen-that-offers-lenses-shows-a-way-to-switch` asserts the key sets are
  equal. Two halves of one registry are exactly the pairing this codebase keeps
  paying for when nothing checks them."
  {:code "diagram"
   :form "what it is"})

(defn with-lens
  "The address of subject `path` seen through `lens` — the bare path when
  `lens` is nil.

  Trivial on purpose, and it exists so that nothing else spells it. The chrome
  that renders a lens switcher and the router that parses the result are two
  writes related only by the shape of a string; when that shape is written
  once, a round-trip test over [[lenses]] proves they agree, and when it is
  written twice the test proves only that both copies were typed the same day."
  [path lens]
  (if lens (str path "/" lens) path))

(defn form-main
  "The Code section's main pane for one form. Takes `GET /api/form/:id`'s shape.

  **Source is no longer the destination.** This pane used to render the
  breadcrumb, the signature, the doc and then the whole form — so the fastest
  way to learn what something did was to read it, and everything else the store
  knows sat in a rail beside an answer nobody needed. Inverted: the page leads
  with what the form is FOR, what it is worth trusting, and where it sits.

  Source stays one click away under the `source` lens and always will. A
  curated view that becomes the only way to see a thing produces confident
  false beliefs, which is worse than no view — so the escape hatch is named,
  linked and permanent. Reaching for it is the signal that THIS page was too
  thin, and the thin spots are a backlog's business, not this page's.

  **A gap is stated, never rendered as silence.** No docstring, no recorded
  ask, no covering test each get a sentence. An absent fact and an absent
  RENDERING look identical on a screen, and a reader who cannot tell them apart
  will assume the more flattering one. That is `an-undocumented-form-SAYS-so-rather-than-showing-a-gap`
  applied to every field on the page rather than to one.

  `prefer` is the rung the reader asked the path to route through, from
  `/store/form/<id>/through/<other>` — a set because [[slopp-server.ui.callgraph/spine]]
  takes one, empty when the address names none."
  ([data] (form-main data nil nil))
  ([data lens prefer]
   (let [{:keys [form form-id ns module tokens sig doc why warranty]} data
         nbhd (callgraph/from-form-view data)
         gap  (fn [text] [:p {:class "src-gap"} [:small text]])]
     [:div
      [:nav [:a {:href "/store"} "code"] " / "
       [:a {:href (str "/store/module/" module)} module] " / "
       [:a {:href (str "/store/ns/" ns)} ns]]
      [:h1 form]
      (if (seq (str sig))
        [:p [:code (if (coll? sig) (str/join "  " sig) (str sig))]]
        (gap "no signature recorded"))
      (if lens
        ;; the escape hatch, taken. Reaching this is not a failure of the
        ;; reader — it is a finding about the page they came from.
        [:div {:class "src-escape"}
         [:p [:small "the compiled form. Everything above is what the store
                      knows about it; if you needed this to answer your
                      question, that is a gap in the page, not in you."]]
         (token-code tokens)
         [:p [:a {:href (str "/store/form/" form-id)} "back to what it is"]]]
        [:div
         (if doc [:p doc] (gap "no comment recorded on this form"))
         (if why
           [:p {:class "src-why"} [:em why]]
           (gap "no recorded ask — nothing says why this form was written"))
         (if (pos? (or (:covered warranty) 0))
           [:p [:small (plural (:covered warranty) "covering test")]]
           (gap "no covering test — nothing proves this behaves"))
         (spine-view form-id (assoc (callgraph/spine nbhd form-id (or prefer #{}))
                                    :truncated (:truncated nbhd)))
         [:p {:class "src-escape-link"}
          [:a {:href (with-lens (str "/store/form/" form-id) "source")}
           "read the source"]
          " " [:small "— the escape hatch"]]])])))

(defn match-path
  "`segs` against route pattern `pattern` — the captured params, or nil.

  A `:name` segment captures under that name; a trailing `*name` captures the
  REST as one slash-joined value; every other segment must match literally.

  **Lengths must agree except across a splat**, and that is a real distinction
  rather than a special case: without it `/store/form/:id` would swallow
  `/store/form/:id/through/:through`, which is a route this app really emits.
  A splat says the tail is deliberately variable, so it needs AT LEAST one
  segment to capture — `/endpoints/get` addresses no endpoint and matching it
  would hand a screen an empty path.

  **Same grammar as `slopp.webapp/match-route`**, which this mirrors. It said
  so before it was true: the framework has had the splat all along and this
  had only `:name`, which nothing caught because no row in `screens` used one
  until an endpoint's address became its own path.

  A captured segment may look like anything — a dotted namespace, a range with
  dots in it, a name ending in `!`, or the API path parameter `:id`, which is a
  LITERAL here because the pattern is what is being addressed. It is one
  segment, and the router has no business having opinions about its shape; the
  screen that receives it does."
  [pattern segs]
  (let [ps     (into [] (remove str/blank?) (str/split (or pattern "") #"/"))
        segs   (vec segs)
        splat? (fn [p] (str/starts-with? p "*"))
        tail   (when (seq ps) (peek ps))
        splat  (when (and tail (splat? tail)) tail)
        head   (if splat (pop ps) ps)
        step   (fn [acc [p s]]
                 (cond
                   (str/starts-with? p ":") (assoc acc (keyword (subs p 1)) s)
                   (= p s)                  acc
                   :else                    (reduced nil)))]
    (if splat
      (when (> (count segs) (count head))
        (some-> (reduce step {} (map vector head (subvec segs 0 (count head))))
                (assoc (keyword (subs splat 1))
                       (str/join "/" (subvec segs (count head))))))
      (when (= (count head) (count segs))
        (reduce step {} (map vector head segs))))))

(defn example-path
  "A concrete path from route pattern `pattern`, filling its `:name` segments
  from `sample`.

  So a table row carries ONE source for both jobs — matching and driving. An
  example written out beside its pattern is two literals that can disagree,
  which is the pairing this project keeps paying for, and the guard that drives
  every screen would be the thing quietly driving the wrong one."
  [pattern sample]
  (let [ps (into [] (remove str/blank?) (str/split (or pattern "") #"/"))]
    (if (seq ps)
      (str "/" (str/join "/" (for [p ps]
                               (if (or (str/starts-with? p ":")
                                       (str/starts-with? p "*"))
                                 (str (get sample (keyword (subs p 1))))
                                 p))))
      "/")))

(def screens
  "Every screen this app has, as a ROUTE PATTERN — and the router's source
  rather than a description of it.

  It began as a list of screens with an example path each, which closed three
  drifting copies (the router test's own map, `app-view`'s dispatch,
  `page/responses`) but could not close the fourth: nothing stopped a screen
  existing in a dispatch `case` and not here. The router MATCHES against a
  table now, so a screen the router can reach is one this table lists, by
  construction — the gap the first version had to record as open.

  **Every pattern carries `/p/:slug`, because the slug is a route parameter.**
  These were mount-relative while the app was served at `/p/<slug>` and the
  framework stripped the mount before routing. With one shell at the root the
  browser's url IS the whole thing, so this table spells it — and `:slug` is a
  capture like `:ns` or `:id`, with a `:sample` like any other.

  **Three families sit under their CAPABILITY** — `/rest/paths`, `/http/paths`,
  `/webapp/pages` — because a section is a capability rather than a screen and
  the address says which one. They used to be `/endpoints` and `/pages`, two
  unrelated words for two of the three documents slopp publishes. See
  [[sections]].

  Each row:

  - `:path` — the pattern. A `:name` segment captures under that name.
  - `:sample` — values for the drivable example, DERIVED by [[example-path]]
    rather than written out, so one row cannot describe one route and drive
    another.
  - `:defaults` — params a screen wants present even when the url carries
    none. Only `:search` has one, and its reason is in `search-main`: the
    screen reads ONE shape whether or not a query was typed.

  **Order is first-match-wins and the literal rows come first.** No two
  patterns here can both match one path — the lengths and literals separate
  them — but a table where that has to be reasoned out is one edit away from
  a route nobody can reach, so the arrangement makes it obvious instead.

  `:form` appears TWICE, and that is the shape the pattern language is for:
  the same screen at two arities, `/p/:slug/store/form/:id` and the same form
  seen THROUGH another. Before this it was two branches of a `cond` that could
  drift apart."
  [;; the LANDING, and the only row that is not inside a project — no `:slug`
   ;; to capture and no `:sample` to fill, because `/` is already concrete.
   {:screen :projects     :path "/"}
   {:screen :timeline     :path "/p/:slug"                :sample {:slug "demo"}}
   {:screen :dashboard    :path "/p/:slug/dashboard"    :sample {:slug "demo"}}
   {:screen :code         :path "/p/:slug/store"          :sample {:slug "demo"}}
   {:screen :search       :path "/p/:slug/store/search"   :sample {:slug "demo"}
    :defaults {:q ""}}
   {:screen :rest-paths   :path "/p/:slug/rest/paths"     :sample {:slug "demo"}}
   {:screen :http-paths   :path "/p/:slug/http/paths"     :sample {:slug "demo"}}
   {:screen :webapp-pages :path "/p/:slug/webapp/pages"   :sample {:slug "demo"}}
   ;; Config's landing IS its only page, so there is one row here and no
   ;; detail screen under it — the document is one answer covering every
   ;; owner rather than a list you drill into.
   {:screen :config       :path "/p/:slug/config"         :sample {:slug "demo"}}
   ;; the sample is a REAL content form of this store — the shell — because
   ;; the fixture answers by path and a made-up var would drive a not-found
   ;; page while looking like a screen that rendered.
   {:screen :http-path    :path "/p/:slug/http/paths/:ns/:name"
    :sample {:slug "demo" :ns "slopp-server.ui.hub" :name "shell"}}
   ;; and likewise a real PAGE of this store, for the same reason.
   {:screen :webapp-page  :path "/p/:slug/webapp/pages/:ns/:name"
    :sample {:slug "demo" :ns "slopp-server.ui.pages" :name "code-page"}}
   {:screen :change       :path "/p/:slug/change/:range"
    :sample {:slug "demo" :range "d1..d2"}}
   ;; `**` and `:*` — slopp's wildcards are anonymous, and this table is
   ;; joined against the ROUTER's, so it says what the router says.
   {:screen :rest-path    :path "/p/:slug/rest/paths/:method/**"
    :sample {:slug "demo" :method "get" :* "api/modules"}}
   {:screen :ns           :path "/p/:slug/store/ns/:ns"
    :sample {:slug "demo" :ns "demo.core"}}
   {:screen :module       :path "/p/:slug/store/module/:module"
    :sample {:slug "demo" :module "demo.core"}}
   {:screen :form         :path "/p/:slug/store/form/:id"
    :sample {:slug "demo" :id "f1"}}
   {:screen :source       :path "/p/:slug/store/source/:ns/:name"
    :sample {:slug "demo" :ns "demo.core" :name "rate"}}
   ;; the same form seen THROUGH another — Blaze's combination lock as an
   ;; address rather than as client state, so a swap is shareable and Back
   ;; undoes it
   {:screen :form         :path "/p/:slug/store/form/:id/through/:through"
    :sample {:slug "demo" :id "f1" :through "f2"}}])

(defn change-main
  "The Review section's main pane for one commit-point. Takes
  `GET /api/change/:range`'s shape.

  A diff line is a PAIR — `[\"add\" \"  :new\"]` — exactly like [[token-code]]'s
  tokens: the endpoint classifies, this renders. `same` carries no class,
  rather than a `.same` rule that would have to be written to say nothing.

  **This paragraph used to describe a different contract, confidently**, and
  the correction is worth leaving visible because of how long it survived. It
  said the diff arrives as LINES with a leading `-`/`+`, and went on to argue
  a subtle `:cljc` point about classifying with `starts-with?` rather than by
  comparing the first character. Every word was true when written. The
  endpoint moved to pairs; nothing said so.

  **The failure was silent in both directions.**
  `clojure.string/starts-with?` calls `.toString`, so a VECTOR did not throw —
  it stringified to `[\"same\" \"(ns …\"]`, started with `[`, and every line got
  `:class nil`. The diff was colourless. And the pair itself sat where a
  string was expected, which hiccup reads as an ELEMENT: a browser was being
  handed `<same>(ns demo)</same>`.

  Neither shows up in the readout, because the text renders either way — and
  this screen has no fixture, so nothing has ever driven it. That is the
  second instance of a live defect behind an undriven screen."
  [{:keys [from to modules arc] total :count}]
  [:div
   ;; on an empty answer this rendered `..` as a heading and ` forms` with no
   ;; number — the same false-sentence class as `0 forms` and
   ;; `5 of thems foundation`, from the nil direction rather than the
   ;; wrong-key one. It did not throw, which is why it lasted.
   (if (and from to)
     [:h1 (str from ".." to)]
     [:h1 "change"])
   (if total
     [:p (plural total "form")]
     [:p "nothing to show — this range came back empty."])
   ;; THE ARC, which nothing rendered until 2026-08-15. It is one entry per
   ;; verification in the range, and its own contract argues for it better than
   ;; a docstring here could: zero failures throughout "for a range that ADDED
   ;; assertions is itself a finding, since a test nobody watched fail is a
   ;; test nobody has evidence for".
   ;;
   ;; So BOTH branches say something. A red arc names the deltas — a count with
   ;; no address makes a reader hunt — and a clean one says `none red` rather
   ;; than staying silent, because silence here is indistinguishable from a
   ;; range that carried no arc at all.
   ;;
   ;; Deliberately NOT a sparkline. The one thing a reviewer needs is whether
   ;; it went red and where, and that is a sentence; geometry is where a
   ;; finding hides, which this project has already paid to learn on the
   ;; dependency-cycle diagram.
   (when (seq arc)
     (let [{:keys [verifications red ms]} (metrics/arc-summary arc)]
       ;; ONE string node, not five. Adjacency is a prose fact: emitted as
       ;; separate nodes this read `3 verifications  in 420ms` to every reader
       ;; without markup, which is the tool that reviews this screen.
       [:p {:class "arc"}
        (str (plural verifications "verification")
             (when ms (str " in " ms "ms"))
             (if (seq red)
               (str ", " (count red) " red — "
                    (clojure.string/join
                     ", "
                     (map (fn [e]
                            ;; the size it is a count OF, when the entry knows
                            ;; it. An entry recorded before the denominator
                            ;; shipped says just its delta rather than `of 0`.
                            (if (:tests e)
                              (str (:delta e) " (" (:fail e) " of " (:tests e) ")")
                              (str (:delta e))))
                          red)))
               ", none red"))]))
   (into [:div]
         (for [m modules]
           (into [:section {:id (:module m)}
                  [:h2 (:module m) " " [:small (plural (:count m) "form")]]]
                 (for [n (:namespaces m)]
                   (into [:div [:h3 (:ns n)]]
                         (for [f (:forms n)]
                           [:article
                            [:h4 [:a {:href (str "/store/form/" (:form-id f))}
                                  (:form f)]]
                            (when (:why f) [:p [:em (:why f)]])
                            [:pre
                             (into [:code]
                                   ;; a line is [status text] — `same`, `add`,
                                   ;; `del` — so the CLASSIFYING is the
                                   ;; endpoint's and this only renders it.
                                   ;; `same` gets no class rather than a
                                   ;; `.same` rule that would have to be
                                   ;; written to say nothing.
                                   (for [[status text] (:diff f)]
                                     ;; the marker is in the TEXT, and the class
                                     ;; is a second channel rather than the only
                                     ;; one. `.del`/`.add` are a background
                                     ;; colour, so without the stylesheet the
                                     ;; removed line and the added line were the
                                     ;; same string — a diff with no diff in it.
                                     ;; Context is padded to the same width so
                                     ;; the code stays aligned under the markers.
                                     [:span {:class (#{"add" "del"} status)}
                                      (str (case status "add" "+ " "del" "- " "  ")
                                           text)
                                      "\n"]))]
                            [:p [:small (plural (:callers f) "caller")]]])))))) ])

(defn module-nav
  "The Code section's left PANE: modules, expanding to their namespaces.

  This replaces a flat alphabetical list of every namespace — on slopp's own
  store, 186 rows of which 103 were tests. Modules are the coarser rung the
  code actually has, and the list is now 14 rows that open.

  Two rules. A module EXPANDS when it holds `current` or when `needle`
  matches one of its namespaces: a filter that finds nothing because the
  match sits behind a collapsed row is worse than no filter. And test
  namespaces are never listed.

  **Every module row is an ANCHOR**, to the same namespace its box in the
  diagram links to. Nothing binds a click handler to `.module-head` and
  nothing ever did, so before this the only way to open a row was to already
  be inside it: the pane rendered fourteen rows, lit them on hover, and did
  nothing when clicked. Expansion still needs no handler, because arriving at
  a module's namespace is what opens it — the link and the rule are the same
  mechanism seen from two ends. A module with no namespaces gets no anchor
  rather than one that goes nowhere.

  **The shared root is printed ONCE, as a heading.** `slopp-server.ui.` on all
  fourteen rows was fourteen copies of the thing the reader knows, and it was
  the widest text in the narrowest pane. [[nsfilter/common-root]] declines to
  find one when there is nothing to hoist, and `without-root` passes names
  through whole in that case, so a store with unrelated module names is
  unchanged. The FILTER still matches full names — the root is hidden, not
  gone, and typing it must still find its rows.

  The test tally is gone. It was a number nobody navigates by, and \"no
  tests\" is a finding about a module, which belongs where findings go rather
  than on every row of a nav.

  `#ns-filter`, `#ns-list` and `.ns-row` are kept exactly as they were: they
  are what the tests and the client address, and dropping them once already
  broke the filter silently."
  ([modules current] (module-nav modules current nil))
  ([modules current needle]
   (let [hit?  (fn [m] (some #(nsfilter/matches? needle %) (:namespaces m)))
         open? (fn [m] (or (some #(= (str current) %) (:namespaces m))
                           (and (seq (str needle)) (hit? m))))
         root  (nsfilter/common-root (map :module modules))
         label (fn [n] (nsfilter/without-root root n))]
     [:div
      [:input {:id "ns-filter" :type "search" :autocomplete "off"
               :value (or needle "")
               ;; the ONE control whose payload the view cannot know, so its
               ;; action carries no argument and the text arrives normalised
               :on {:input [:filter/set]}
               :placeholder "filter namespaces…" :aria-label "filter namespaces"}]
      (when root [:p {:class "ns-root"} root])
      (into [:ul {:id "ns-list"}]
            (for [m modules
                  :when (or (not (seq (str needle))) (hit? m))]
              (into [:li {:class "module-row" :data-module (:module m)
                          :data-open (str (boolean (open? m)))}
                     (let [head (into [:div {:class "module-head"}]
                                ;; the separators are MARKUP, never a margin.
                                ;; Four inline siblings with only CSS between
                                ;; them have NOTHING between them in the text:
                                ;; this rail read `web⚡1 ns` and `core2 ns`,
                                ;; and the second is the expensive kind — not a
                                ;; missing space but a WRONG WORD. A reader
                                ;; without CSS is a headless driver and a screen
                                ;; reader alike.
                                ;;
                                ;; interpose over the NON-NIL parts, because two
                                ;; of the four are conditional and a separator
                                ;; before an absent element is the same defect
                                ;; reversed — a rail that reads `core  2 ns`.
                                (interpose " "
                                  (remove nil?
                                    [[:span {:class "module-name"} (label (:module m))]
                                     ;; the tier, where you DECIDE what to open.
                                     ;; Knowing a module does IO before clicking
                                     ;; into it is worth more than knowing after.
                                     (tier-mark (:tier m))
                                     [:span {:class "module-meta"}
                                      (str (count (:namespaces m)) " ns")]
                                     (when (:foundation m)
                                       [:span {:class "module-tag"} "foundation"])])))]
                       (if-let [target (module-target m)]
                         [:a {:class "module-link" :href (str "/store/ns/" target)}
                          head]
                         head))]
                    (when (open? m)
                      (for [n (:namespaces m)
                            :when (nsfilter/matches? needle n)]
                        (cond-> [:div {:class "ns-row"}
                                 [:a (cond-> {:href (str "/store/ns/" n)}
                                       (= n (str current)) (assoc :class "active"))
                                  (label n)]]
                           ;; the module's tier, carried onto its namespaces: it
                           ;; scopes by PATH, so a namespace under an external
                           ;; module IS external. The limit worth knowing is that
                           ;; `GET /api/modules` reports one tier per MODULE, and
                           ;; `module_purity` allows a more specific declaration
                           ;; underneath that this endpoint does not expose — so
                           ;; a sub-namespace with its own tier would show its
                           ;; module's here. Nine of this store's twelve modules
                           ;; hold exactly one namespace, so the question only
                           ;; arises under `client`.
                           ;;
                           ;; the separator is markup for the reason the head
                           ;; above carries one — and this instance renders in
                           ;; NO current fixture, because demo.core is :pure and
                           ;; has no mark. Fixed anyway: it is the same one
                           ;; character, and the tier declaration that would
                           ;; reveal it is the kind of change nobody re-reads a
                           ;; nav rail after.
                           (tier-mark (:tier m))
                           (conj " " (tier-mark (:tier m)))))))))])))

(defn project-picker
  "The landing page: one row per project the daemon holds, each a link into
  it.

  A listed project is OPEN — the daemon lists a project exactly while
  something is attached to it — so there is no stale row to grey and no
  last-seen to age; the row says what is attached and, when the daemon runs
  the project's app, where that answers."
  [projects]
  ;; `:data-region "main"` because this screen gets no `app-shell` — it is a
  ;; page in its own right — and the shell is what marks every other screen's
  ;; panes. Without it the landing declared NO regions, so nothing could
  ;; address its content: not a test, and not the `screen` tool.
  [:div {:class "landing" :data-region "main"}
   [:h1 "slopp"]
   [:p {:class "landing-tagline"}
    (if (seq projects) "Projects open on this daemon" "Nothing is open on this daemon")]
   (if (seq projects)
     (into [:ul {:class "projects"}]
           (for [{:keys [slug dir sessions app]} projects]
             [:li {:class "project"}
              [:a {:class "project-name" :href (str "/p/" slug "/")} slug]
              ;; the separators are TEXT, not a CSS gap: three inline siblings
              ;; with only a margin between them read `slopp2/w/demo1 session`
              ;; to anything that does not apply the stylesheet — the `screen`
              ;; tool included. The row is a grid, which ignores whitespace-only
              ;; text between its items, so the words stay separate both ways.
              " "
              [:span {:class "dir"} dir]
              " "
              [:span {:class "status"}
               (str sessions " session" (when (not= 1 sessions) "s")
                    (when-let [u (:url app)] (str " · app " u)))]]))
     ;; plain words, and the command as markup: the first cut said "or run
     ;; `slopp <op>` in one", with the backticks rendered literally and the
     ;; CLI's own placeholder standing in for a sentence
     [:div {:class "landing-empty"}
      [:p "No project is open. A project is listed here while something is"
       " attached to it:"]
      [:ul
       [:li "start an agent session in a slopp checkout, or"]
       [:li "run a " [:code "slopp"] " command from one."]]])])

(defn module-from-index
  "The module screen's shape, assembled from `GET /api/modules` — everything
  about one module that TODAY'S wire can honestly answer.

  **`GET /api/module/:m` EXISTS now** — shipped at slopp `d23203` and wired
  here, and the reader-API gap it filled is closed. This docstring said it
  did not for as long as it has, which is a description outliving its code in
  the place least able to notice.

  So this is not a stopgap waiting for an endpoint. **It is the fallback for
  when that endpoint cannot be REACHED** — the module index is already cached
  for the nav, so a module screen can still say most of what it knows when the
  per-module call fails. `app-view` reaches it only when the `:main` load is
  `:ready` and empty; the ordinary path goes straight to the wire shape.

  Worth stating the difference because the two read the same from the code and
  point opposite ways: a stopgap should be DELETED when its endpoint lands, and
  a resilience path should not.

  The tempting responses to having no data are both wrong: a
  blank screen throws away what the index already knows, and fetching every
  namespace to reconstruct the edges makes the page a slow, lossy view of
  something the store could answer in one call.

  What IS honest from the index: the namespaces this module holds, its tier,
  and its boundary **at module grain** — which modules it depends on, and which
  depend on it. That is most of \"how does this fit\" and it costs nothing.

  What is not: the namespace→namespace edges INSIDE. Those come back `[]` with
  `:pending` set, and [[module-main]] renders that as a sentence. Empty deps
  with no sentence would draw a diagram of unconnected boxes, and a module with
  no internal structure and a module whose structure is not on the wire look
  identical on a screen — which is the specific way this UI would lie."
  [{:keys [modules]} module]
  (let [row (first (filter #(= module (:module %)) modules))]
    (when row
      {:module     module
       :tier       (:tier row)
       :namespaces (mapv (fn [n] {:ns n :deps []}) (:namespaces row))
       :boundary   {:out (mapv (fn [d] {:from module :to d :to-module d}) (:deps row))
                    :in  (into [] (comp (filter #(some #{module} (:deps %)))
                                        (map (fn [m] {:from (:module m)
                                                      :from-module (:module m)
                                                      :to module})))
                              modules)}
       :layers     []
       :cycles     []
       :pending    "the structure INSIDE this module is not on the wire yet —
                    GET /api/module/:m would carry the namespace-level edges"})))

(defn module-main
  "One module from the inside: its namespaces, how they depend on each other,
  and what crosses its boundary.

  The rung the hierarchy was missing. `/store` drew the architecture at module
  grain and a box led straight to a representative NAMESPACE, so the obvious
  question a diagram provokes — *what is this thing made of* — had nowhere to
  go and no data behind it either.

  Rendered by [[module-graph]] unchanged: at this grain a box IS a namespace,
  so the `targets` map that makes boxes links is the identity over the
  namespaces drawn, and every box already points at `/store/ns/…`.

  **The boundary is why this is not just a smaller diagram.** A descended view
  that draws only the edges INSIDE the module cannot distinguish the namespace
  everything outside comes through from one nothing outside has heard of — and
  that distinction is most of what a reader wants at this level.

  **Every count here is over something the response actually carries.** Three
  false sentences shipped green before anyone opened the page: `0 forms` for an
  index that carries no form counts, and `1 of them reached from outside` on
  every module of every store — because until `GET /api/module/:m` exists the
  boundary rows are MODULE-grain, so their near end is always this module and
  counting distinct near ends always yields one. A number that cannot vary is
  not a measurement. So the summary counts modules when the rows are
  module-grain and namespaces when they are not, and an unknown total is
  omitted rather than printed as zero.

  Boundary rows lead with the module at their FAR end. The near end is the page
  you are already on, and printing it once per row is the heading repeated."
  [{:keys [module tier namespaces boundary picture cycles pending]}]
  (let [forms   (keep :forms namespaces)
        ;; at THIS grain a box is a namespace, so the /store/ns/ prefix belongs
        ;; here — in the caller that knows what its boxes stand for, rather
        ;; than in the component that draws them. That split is what lets the
        ;; store view descend into a module from the same component.
        targets (into {} (map (juxt :ns #(str "/store/ns/" (:ns %)))) namespaces)
        ;; Count the FAR end by its OWN key, at whatever grain the rows have.
        ;; While `:pending` the boundary is module-grain and `:from` equals
        ;; `:from-module`; the real endpoint sends `:from` as an outside
        ;; NAMESPACE. Reading `:from-module` while labelling it "namespace"
        ;; was unfalsifiable against the fallback — the two keys held one
        ;; string — and began lying the moment the endpoint answered.
        far     (fn [rows k] (into #{} (map k) rows))
        ins     (far (:in boundary) :from)
        outs    (far (:out boundary) :to)
        unit    (if pending "module" "namespace")
        edge-list
        ;; ONE ROW PER FAR MODULE, not per edge. `slopp.edit` has 32 inbound
        ;; edges from 15 namespaces, and rendering them raw put `slopp.ops →`
        ;; on screen nine times — an ungrouped list long enough that the thing
        ;; a reader wants (WHICH modules depend on this, through what) is
        ;; somewhere inside it rather than on it. Grouping by the far module is
        ;; the documented fan-out remedy and it is the grain the question is
        ;; asked at anyway.
        ;;
        ;; The far NAMESPACE is deliberately not shown. It was a parameter
        ;; here until the grouping stopped reading it, and a parameter callers
        ;; supply while the body ignores it is the same species of lie as a
        ;; docstring describing code that moved.
        (fn [heading rows far-module near-k empty-note]
          [:section
           [:h2 heading]
           (if (seq rows)
             (into [:ul]
                   (for [[m es] (sort-by key (group-by far-module rows))
                         :let [nears (distinct (sort (map near-k es)))]]
                     [:li [:strong m]
                      " " [:small (plural (count es) "edge")]
                      (when-not pending
                        ;; the arrow is DERIVED from which key names the NEAR side, so it
                        ;; cannot disagree with the direction this row is about.
                        ;; `:to` means our namespace is the target — they name
                        ;; us, so the arrow points in. `:from` means ours is the
                        ;; source — we name them, so it points out.
                        ;;
                        ;; It was hardcoded `→`: right for inbound, backwards
                        ;; for outbound, so `depends on` claimed the far module
                        ;; named us when we name it. Invisible because `:out`
                        ;; was empty in the only fixture that drives this
                        ;; screen, and it is non-empty on any real store.
                        (into [:span (if (= near-k :to) " → " " ← ")]
                              (interpose
                               ", "
                               (for [n nears]
                                 [:a {:href (str "/store/ns/" n)}
                                  (nsfilter/without-root module n)]))))]))
             [:p [:small empty-note]])])]
    [:div
     [:nav [:a {:href "/store"} "code"] " / " module]
     [:h1 module (tier-mark tier)]
     [:p (str (plural (count namespaces) "namespace")
              (when (seq forms) (str ", " (plural (reduce + 0 forms) "form")))
              (when (seq ins) (str " — used by " (plural (count ins) unit)))
              (when (seq outs) (str ", depends on " (plural (count outs) unit))))]
     (when (seq cycles)
       [:div {:class "finding cycles"}
        [:h2 (str (plural (count cycles) "dependency cycle") " inside this module")]
        (into [:ul]
              (for [c cycles]
                [:li (str/join " → " (concat c [(first c)]))]))])
     (cond
       picture (module-graph picture targets)
       pending [:p {:class "src-gap"} [:small pending]]
       :else   nil)
     (when (seq namespaces)
       [:section
        [:h2 "namespaces"]
        (into [:ul]
              (for [n (sort-by :ns namespaces)]
                [:li [:a {:href (str "/store/ns/" (:ns n))}
                      (nsfilter/without-root module (:ns n))]
                 (when (:forms n) [:small (str " · " (plural (:forms n) "form"))])]))])
     (edge-list "used by" (:in boundary) :from-module :to
                "nothing outside this module names any of its namespaces")
     (edge-list "depends on" (:out boundary) :to-module :from
                "this module depends on nothing outside itself")]))

(defn toggle-all-docs
  "`show` with every comment set to `open?` — the rail's all-switch.

  **Drops the per-comment overrides as well as setting the default.** An
  \"expand all\" that left a previously-collapsed comment shut would not be
  expand all, and the reader would be looking at a switch that says one thing
  and a page that does another.

  Lived inline in `client.app`'s document listener until 2026-08-05, which
  meant the rule deciding what a reader SEES ([[doc-open?]]) was `:cljc` and
  tested while the rule deciding what a CLICK DOES was neither. Those two have
  to agree or a toggle lies, and only one of them could be checked."
  [show open?]
  (-> show (assoc :docs? open?) (dissoc :doc)))

(defn toggle-doc
  "`show` with the comment on `nm` flipped — an override on top of whatever the
  all-switch says.

  **Flips what [[doc-open?]] currently reports**, rather than flipping a stored
  boolean. The two are different whenever no override exists yet: the stored
  value is absent and the effective value comes from `:docs?`, so toggling a
  raw `nil` would open a comment that was already open. Going through the
  reader is what makes the write agree with the render by construction."
  [show nm]
  (assoc-in show [:doc nm] (not (doc-open? show nm))))

(defn set-display-option
  "`show` with display option `k` set to `on?`.

  Thin on purpose. It exists so the browser hands over a KEY that
  [[option-for]] resolved from the DOM, rather than reaching into the show map
  itself — the round trip through the DOM then goes through one table instead
  of two matching literals, and a toggle whose name did not survive the trip
  changes nothing rather than changing the wrong thing."
  [show k on?]
  (assoc show k on?))

(defn hit-name
  "The text a search row SHOWS for a hit.

  A form arrives with its name QUALIFIED — measured on slopp's live listener,
  where `slopp.index.refs/refs` came back beside `:ns slopp.index.refs` — so
  printing it whole puts the namespace twice on one line, once in the link and
  once in the context beside it. The row shows the bare half and lets the
  context say the rest.

  Split on the FIRST slash rather than the last: `clojure.core//` is a real
  name whose bare half IS a slash, and splitting on the last one answers the
  empty string for it.

  Only forms are qualified. A namespace or module name is whole, and
  truncating it at a dot would be the same defect in the other direction."
  [{:keys [kind name]}]
  (if (= "form" kind)
    (if-let [i (str/index-of (str name) "/")]
      (subs (str name) (inc i))
      (str name))
    (str name)))

(defn address-for
  "Where a search hit lives in THIS app — the url its `:kind` addresses.

  **Built here rather than sent.** slopp declined to emit an `:address` on a
  search row, and the reasoning is one this project agrees with: a producer
  emitting `/store/…` asserts a fact about THIS app's routing in units it does
  not own, with nothing on either side able to check it — a route change here
  would silently falsify strings there. Declaring the template in the contract
  is the same claim wearing a schema.

  That leaves the obvious objection, which is the one [[form-href]] is this
  project's standing example of: the scheme now has a builder and a parser.
  The answer is a test rather than care — every address this returns is fed
  back through [[route-for]], so the two are pinned against each other. If
  [[subject-for]] gains or moves a route, that test fails here rather than a
  link failing in a browser.

  A form with no `:form-id` falls back to its source, exactly as `form-href`
  does, and for the same reason: a row that goes somewhere plainer beats one
  that is dead text, and an id-less row is where a reader most needs the
  escape hatch."
  [{:keys [kind name ns form-id] :as hit}]
  (case kind
    "module"    (str "/store/module/" name)
    "namespace" (str "/store/ns/" name)
    "form"      (if form-id
                  (str "/store/form/" form-id)
                  (str "/store/source/" ns "/" (hit-name hit)))
    nil))

(defn search-main
  "The Code section's main pane for a search: what in this store carries these
  words, and where the words actually were.

  **The door.** `/store` opens on the module diagram, and a diagram is a
  landing page rather than a way in: arriving cold you get the architecture and
  no way to ask about the thing you came for. Overview-first is the documented
  wrong entry — *search, show context, expand on demand* is the order — and
  until this screen the UI had no door at all.

  **The order on screen is the order the store ranked**, and this pane does not
  re-sort. That is the opposite of the line taken on the call graph, where
  ranking six visible neighbours belongs here; the difference is that relevance
  needs the CORPUS, and this project may not open a store. Sorting here would
  be a second opinion nobody asked for, and it would silently diverge the first
  time the store's ranking changed.

  **A row links to an address this app BUILDS**, through [[address-for]].
  I asked for `:address` on the wire and slopp declined, correctly: a producer
  emitting `/store/…` asserts a fact about THIS app's routing in units it does
  not own, checkable by nobody, and a route change here would silently falsify
  strings there. [[form-href]] is this project's standing example of what two
  producers cost, so the builder is pinned to the parser by a round-trip test
  rather than by care. The context beside a name — the namespace a form is in,
  the module a namespace is in — stays TEXT rather than a link, because it is
  the one thing a row genuinely has no address for.

  **`:matched` is why the row is trustworthy.** A hit whose name does not
  contain the query reads as a bug unless it can say the words were in the
  recorded why. And `\"source\"` is the honest escape hatch rather than a
  result: it says the only place these words appear is the CODE, which on this
  UI's own thesis is a finding about the store — so it is called out ABOVE the
  list, where a finding goes, and not left to be noticed row by row.

  `data` nil means nothing answered. That is distinct from an empty result and
  is rendered as such: the endpoint is agreed and being built, and a screen
  that says \"nothing matches\" when nothing was asked is worse than one that
  says what is missing."
  ([data] (search-main data (:query data)))
  ([{:keys [total totals hits]} query]
   (let [q      (str/trim (str query))
         shown  (count hits)
         only   (filter #(= "source" (:matched %)) hits)
         kinds  (when totals
                  (keep (fn [[k w]] (when (pos? (or (k totals) 0)) (plural (k totals) w)))
                        [[:modules "module"] [:namespaces "namespace"] [:forms "form"]]))
         phrase {"name"   "matched in the name"
                 "doc"    "matched in the docstring"
                 "why"    "matched in the recorded why"
                 "source" "matched only in the source"}]
     [:div
      [:nav [:a {:href "/store"} "code"] " / " "search"]
      ;; the heading names the QUERY, because the box holding it is in
      ;; another region: a shared link lands a reader here, and a count
      ;; with no subject is the same sentence as `1 of them` on every
      ;; module of every store
      [:h1 (if (str/blank? q) "search" (str "search: " q))]
      (cond
        (str/blank? q)
        [:p "Names, docstrings and recorded whys are what this searches. "
         "The source is searched too, and a hit found only there is reported "
         "as a finding rather than as an answer."]

        (nil? hits)
        [:p {:class "src-gap"}
         [:small (str "this project does not answer GET /api/search, so "
                      "nothing here could be looked up. The hub fronts "
                      "projects from different slopp releases on purpose, and "
                      "an older one will not have it")]]

        (empty? hits)
        [:p "nothing in this store carries " [:strong q]
         " in a name, a docstring or a recorded why."]

        :else
        [:div
         [:p (str (if (= shown total)
                    (plural total "hit")
                    (str "showing " shown " of " (plural total "hit")))
                  (when (seq kinds) (str " — " (str/join ", " kinds))))]
         (when (seq only)
           [:div {:class "finding matched-source"}
            [:h2 (str (plural (count only) "hit") " matched only in the source")]
            [:p (str "These words appear in the code and in none of the names, "
                     "docstrings or recorded whys around it. That is a finding "
                     "about the store rather than a good result: the meaning is "
                     "only in the source, which is the thing this UI exists to "
                     "be an alternative to.")]])
         (into [:ul {:class "hits"}]
               (for [h hits
                     :let [summary (first-sentence (or (:doc h) (:why h)))]]
                 [:li {:class (str "hit hit-" (:kind h))}
                  ;; the separators are MARKUP, never a margin. Inline siblings
                  ;; with only CSS between them have NOTHING between them in
                  ;; the text, and a name running into its own signature is not
                  ;; a missing space but a WRONG WORD.
                  (into [:div {:class "hit-head"}]
                        (interpose " "
                                   (remove nil?
                                           [[:a {:href (address-for h)} (hit-name h)]
                                            (when (seq (:sig h))
                                              [:code {:class "hit-sig"}
                                               (str/join " " (:sig h))])
                                            [:span {:class "hit-kind"} (:kind h)]
                                            (when-let [where (or (:ns h) (:module h))]
                                              [:span {:class "hit-where"} (str "in " where)])])))
                  (when-not (str/blank? summary)
                    [:p {:class "hit-doc"} [:small summary]])
                  [:p {:class (str "hit-matched"
                                   (when (= "source" (:matched h)) " matched-source"))}
                   [:small (get phrase (:matched h)
                                (str "matched in " (:matched h)))]]]))])])))

(defn search-box
  "The door: ask this store a question, from anywhere in the app.

  In the HEADER rather than on the Code index, because a door you have to
  navigate to first is not one. `/store` opening on the module diagram with no
  way to ask about the thing you came for is the finding this exists to close.

  **A plain GET form rather than an `:on` handler.** [[slopp-server.ui.app/act]] is
  pure state and navigating is an effect no state transition can express, so a
  handler would need a plug-in threaded to every control that wants to move —
  and a form needs no bundle at all, which is the argument the picker already
  makes for being script-free. The url it produces is `/store/search?q=…`,
  which is exactly what the route table declares; those two halves are pinned
  against each other by a test that composes the url from what this renders.

  **The action is a plain client route and the framework addresses it**, which
  is worth a sentence because it was not true for an hour. `prefix-links`
  rewrote `:href` only — deliberately, since a form action usually submits to a
  server — so under a mount point this door submitted to `/store/search` at the
  HUB root, a different application. This form briefly carried its own `base`
  to compensate.

  slopp took the argument rather than the workaround: an `:action` is prefixed
  IFF `match-route` reaches it, which is the same discrimination that already
  keeps `/api/modules` from being prefixed as an href. So a POST to an API path
  is left alone by the guard that protects the links, and this form is back to
  writing the address every other screen writes.

  It carries the current query, so refining a search is an edit rather than a
  retype — the single most common thing a reader does after the first result
  list is not what they wanted."
  [query]
  [:form {:class "store-search" :method "get" :action "/store/search"
          :role "search"}
   [:input {:type "search" :name "q" :value (or query "")
            :autocomplete "off"
            :placeholder "search this store…" :aria-label "search this store"}]
   [:button {:type "submit"} "search"]])

(defn schema-fields
  "A schema's fields as a NESTED list — [[slopp-server.ui.schema/nest]]'s tree as hiccup.

  Nesting rather than one flat list with an indent class, and that is the whole
  reason `nest` exists: depth carried by `padding-left` is invisible to a
  reader without the stylesheet, and this app's oldest standing rule is that
  spacing belongs in the markup. Here the cost of getting it wrong is not
  cosmetic — a flat column cannot say whether `forms` is a top-level key or
  lives inside `gaps`, which is the difference between calling the endpoint and
  guessing at it.

  **Nil for no fields, never an empty list.** `[:or …]` yields no rows, and a
  bordered empty box under `response` reads as an answer that came back empty
  rather than as a shape this screen cannot flatten. Same rule `app-shell`
  makes for an omitted pane.

  The separators are real strings. `\" · \"` between the name and the type, and
  a space before `optional`, so the row reads `module · string` rather than
  `modulestring` in the one mode that shows adjacency."
  [nodes]
  (when (seq nodes)
    (into [:ul {:class "schema-fields"}]
          (for [{:keys [field type optional? doc children]} nodes]
            (cond-> [:li
                     [:span {:class "field"} field]
                     " · "
                     [:span {:class "ftype"} type]]
              ;; the same " · " the type gets. With a bare space the row read
              ;; `pid · int or null optional`, where the marker runs into a type
              ;; that already contains words — legible, and one reading away from
              ;; looking like part of the type
              optional?      (conj " · " [:small "optional"])
              ;; its OWN line, not appended to the row. A sentence after
              ;; `q · string · optional` makes the row unscannable, and the name
              ;; is what a reader scans for — the prose is what they stop on.
              doc            (conj [:p {:class "fdoc"} doc])
              (seq children) (conj (schema-fields children)))))))

(defn try-panel
  "The ad-hoc call form for one endpoint: a box per fillable field, a button,
  and whatever came back.

  The one control in this app that takes input which is not a url. Every field
  dispatches `[:try/set <field>]` and the text arrives as the action's value —
  the same seam `#ns-filter` uses, so the whole form is pressable by the
  headless driver and none of it needs a browser.

  **What was SENT is rendered beside what came back.** A response with no
  request beside it cannot be attributed — the reader has a body on screen and
  no way to tell which of three attempts produced it, which is the same defect
  as a count with no population. It also makes the request visible as DATA
  rather than as a url, which is honest about where the url is actually
  assembled: `slopp-server.ui.schema/request-for` stops short of a string because
  encoding is a platform difference this dialect will not let a form branch on.

  A method other than GET is declined in words rather than offered and broken.
  Nothing in slopp's store API is one, so the case is unexercised, and a form
  that silently sent an empty body would be worse than one that says it cannot."
  [endpoint
   {:keys [params status request response error] armed? :armed?}]
  (let [fields     (schema/request-fields endpoint)
        ;; ONE derivation, and `get?` is its complement rather than a second
        ;; copy of the same `(= :get …)`. They were independent before and
        ;; agreed only because nothing had made them disagree.
        effectful? (schema/effectful? endpoint)
        get?       (not effectful?)]
    [:div {:class "try-panel"}
     [:h3 "call it"]
     ;; the warning goes WHERE THE BUTTON IS. It is on the page above too, but
     ;; this is the only place it can still change a decision — a reader who
     ;; has scrolled past the heading to fill in a form is about to fire the
     ;; thing.
     (when effectful?
       [:p [:strong {:class "effectful"} "this changes something"]])
     [:div
        (if (seq fields)
          (into [:div {:class "try-fields"}]
                (for [{:keys [field type in optional? doc]} fields]
                  [:div {:class "try-field"}
                   ;; the label names the FIELD and points at its box by id. It
                   ;; used to WRAP the input with the prose inside it, and the
                   ;; readout flattened the paragraph inline with no separator:
                   ;; `q · query · string · optionalwhat to search for`. Same
                   ;; adjacency defect this project has three of, in a container
                   ;; nothing had tried. A description beside the label rather
                   ;; than inside it is also the more correct markup.
                   [:label {:for (str "param-" field)}
                    [:span {:class "field"} field]
                    " · "
                    [:span {:class "ftype"} (str (clojure.core/name in) " · " type)]
                    (when optional? " · ")
                    (when optional? [:small "optional"])]
                   ;; the field's OWN prose, on the box that asks for it. A form
                   ;; asking for `m` with nothing saying what `m` is has told the
                   ;; reader its type and not the thing they need in order to
                   ;; type anything — which is the whole complaint this answers.
                   (when doc [:p {:class "fdoc"} doc])
                   ;; `param-<field>`, not `<field>`. A bare `name="q"` collided with the
                   ;; header's search box — two controls on the page answering to
                   ;; the same word, which the driver refuses to guess between and
                   ;; a real reader's autofill would guess wrong.
                   [:input {:type "text"
                            :id (str "param-" field) :name (str "param-" field)
                            :value (get params field "")
                            :aria-label (str field " parameter")
                            :on {:input [:try/set field]}}]]))
          [:p (if get?
                "no parameters — this one takes nothing."
                "no body — this one takes nothing.")])
        ;; TWO presses for an effectful endpoint, one for everything else. The
        ;; first only ARMS: `app/try-request` returns nil until it has, so the
        ;; gate is in the pure derivation rather than in either dispatcher, and
        ;; neither of them can forget it.
        ;;
        ;; This is the one place `:effectful?` changes an OUTCOME rather than
        ;; only describing one — and a confirmation on every call is one nobody
        ;; reads, which is why a read-only endpoint keeps its single click.
        (if (and effectful? (not armed?))
          [:button {:type "button" :on {:click [:try/arm]}} "execute…"]
          [:button {:type "button" :on {:click [:try/execute]}}
           (if effectful? "yes — call it" "execute")])]
     (when status
       [:div {:class "try-result"}
        [:h4 (case status
               :running "calling…"
               :failed  "the call failed"
               "the answer")]
        [:p [:small (str "sent: " (pr-str request))]]
        (cond
          (= :failed status) [:p {:class "try-error"} (str error)]
          (= :done status)   [:pre [:code (pr-str response)]]
          :else nil)])]))

(defn endpoint-params
  "The ADDRESS captures for `endpoint` — `{:method \"get\" :path \"api/modules\"}`.

  The url spelling of a document row: method lower-cased and unqualified, path
  without its leading slash, because both are segments and a segment does not
  carry one. [[at-address?]] is the other direction, and the two are a pair —
  what this builds, that matches.

  Separate from [[endpoint-address]] because the router hands a SCREEN this
  shape and hands a LINK the string; deriving one from the other keeps them
  from being written twice."
  [{:keys [method path]}]
  {:method (str/lower-case (clojure.core/name (or method :get)))
   ;; a rooted path loses its slash; anything else is passed through. Blindly
   ;; dropping the first character threw on an absent `:path` — a public fn
   ;; handed a row that is not there should answer something useless, not
   ;; explode inside a caller that was only asking a question.
   :path   (let [p (str path)]
             (if (str/starts-with? p "/") (subs p 1) p))})

(defn endpoint-address
  "The in-app path for `endpoint`'s own page — `/rest/paths/get/api/modules`.

  **The index and the rail both link this**, and they built it separately for
  one commit — exactly the shape that drifts: two spellings of one fact,
  agreeing until someone changes the pattern in one of them. Derived from
  [[endpoint-params]] so the link and the address a screen receives cannot
  disagree either.

  `/rest/paths/…`, where this was `/endpoints/…`: a section is a CAPABILITY now
  and the address says which one, so the three path listings read as siblings —
  `/rest/paths`, `/http/paths`, `/webapp/pages` — rather than as three
  unrelated words. See [[sections]]."
  [endpoint]
  (let [{:keys [method path]} (endpoint-params endpoint)]
    (str "/rest/paths/" method "/" path)))

(defn endpoint-nav
  "The API section's left PANE: every endpoint the document declares.

  Present on the index AND on a single endpoint's page, which is the whole
  point of having it — with only an index, crossing from one endpoint to
  another is two navigations through a page you have already read. That is
  tolerable at nine endpoints and is the reason every API browser grew a
  persistent list.

  Method and path together on one row, because neither identifies an endpoint
  alone, and the method carries its own class so a POST can be told from a GET
  without reading. The path is what a reader recognises; `:name` is the
  generated client's fn and lives on the endpoint's own page.

  The separator is MARKUP, not a margin — this app has three recorded bugs
  from inline siblings with only CSS between them, one of which produced a
  wrong word rather than a missing space."
  [endpoints current]
  [:div
   (into [:ul {:id "endpoint-list"}]
         (for [{:keys [method path] :as ep} endpoints
               :let [addr (endpoint-address ep)]]
           [:li {:class "endpoint-row"}
            [:a (cond-> {:href addr}
                  (= addr (str current)) (assoc :class "active"))
             [:span {:class (str "method method-" (clojure.core/name (or method :get)))}
              (str/upper-case (clojure.core/name (or method :get)))]
             " "
             [:span {:class "endpoint-path"} path]]]))])

(defn endpoints-main
  "The API section's INDEX: one line per endpoint, and no schemas at all.

  It used to render every endpoint in full on this one page. Nine of them was
  already long and a real surface has fifty, which is the complaint that split
  it — the detail is [[endpoint-main]] now, at `/endpoints/:name`.

  **The response TYPE stays and its fields do not.** `list of object` is one
  phrase, tells a reader which endpoint they want, and costs a line; the field
  list is what makes an index unreadable. That is the whole editorial rule of
  this page.

  Takes `GET /api/contracts`'s shape. A nil document degrades in words rather
  than into an empty table, the same way [[endpoint-main]] does."
  [endpoints]
  (if-not endpoints
    [:div
     ;; **`REST paths`, naming the capability.** It was `API`, this app's own
     ;; coinage for one of the three documents slopp publishes, and it stopped
     ;; distinguishing anything the moment the other two arrived — every one of
     ;; the three is an API in the ordinary sense. Siblings: `HTTP paths` and
     ;; `Webapp pages`.
     [:h1 "REST paths"]
     [:p "no API document — this project publishes no "
      [:code "/api/rest/paths"] "."]]
    [:div
     [:h1 "REST paths"]
     [:p (str (plural (count endpoints) "endpoint")
              ", as the project publishes them")]
     [:table {:class "data-table"}
      [:thead
       [:tr [:th "method"] [:th "path"] [:th "what it does"]
        [:th "client"] [:th "answers"]]]
      (into [:tbody]
            (for [{:keys [method path name request response doc auth] :as ep} endpoints
                  :let [effectful? (schema/effectful? ep)]]
              [:tr
               [:td [:span {:class (str "method method-"
                                        (clojure.core/name (or method :get)))}
                     (str/upper-case (clojure.core/name (or method :get)))]]
               ;; the PATH is the link, not the name: it is what a reader
               ;; recognises, and it is the widest thing in the row
               [:td [:a {:href (endpoint-address ep)} path]]
               ;; the FIRST SENTENCE. The document ships the docstring whole —
               ;; which is what I asked for, since a first line cannot be
               ;; un-truncated by a consumer that wants the rest — so the
               ;; cutting is this screen's job, and one line per endpoint is
               ;; this page's whole editorial rule.
               (let [d        (schema/summary-of {:method method :path path :doc doc})
                     a        (schema/auth-label auth)
                     narrows? (and a (not= "public" a))
                     parts    (cond-> []
                                d          (conj [:small d])
                                effectful? (conj [:small [:strong {:class "effectful"} "changes something"]])
                                narrows?   (conj [:small [:strong {:class "effectful"} a]]))]
                 ;; **The separator JOINS; it does not prefix.** Each piece used to carry
                 ;; its own leading " · ", which is right only while the summary is
                 ;; present — an endpoint whose handler has no docstring opened the
                 ;; cell with `· changes something`, a separator with nothing on its
                 ;; left. Built as a list and interposed, that state cannot be
                 ;; spelled. Found by reading the driven index rather than by an
                 ;; assertion; see `views-test/an-index-cell-never-opens-with-a-dangling-separator`.
                 ;;
                 ;; In order: the one-line summary; then the effect DECLARATION,
                 ;; because this is where a reader scans before choosing which
                 ;; endpoint to open; then who may call, but ONLY where it NARROWS
                 ;; — slopp's own surface is `:public` throughout, so a column
                 ;; reading `public` every row is a badge nobody reads, and the
                 ;; endpoint's own page states it either way.
                 (into [:td]
                       ;; a MARK when the cell would otherwise be empty — an
                       ;; endpoint's `:doc` is its handler's docstring, so nil is
                       ;; an ordinary state and nothing requires one. A blank cell
                       ;; reads as "this column is broken" exactly as readily as
                       ;; "this endpoint has no description", and `module-table`
                       ;; already settled the convention with `—` for a module
                       ;; that depends on nothing. Only when the cell is EMPTY: a
                       ;; placeholder beside a mark would deny content that is
                       ;; there.
                       (if (seq parts)
                         (interpose [:small " · "] parts)
                         [[:small "—"]])))
               [:td [:small (str name)]]
               [:td [:small (schema/type-label response)
                     ;; a GET's request schema is the QUERY STRING, not a body.
                     ;; This said "takes a body" on `/api/search` — a false
                     ;; sentence about how to call the endpoint, on the one
                     ;; screen whose whole job is saying how to call it.
                     (when request
                       (if (= :get (or method :get))
                         " · takes parameters"
                         " · takes a body"))]]]))]]))

(defn at-address?
  "A predicate for the endpoint an ADDRESS names — `{:method \"get\" :path \"api/modules\"}`.

  Method AND path, because neither identifies an endpoint alone: one path can
  carry two verbs, and one verb spans the whole surface. That pair is what
  `endpoint-nav` has always rendered as a row's identity, while the row was
  addressed by a third thing.

  **The two sides spell it differently and that is not incidental.** An address
  comes off the url — method lower-case, path without its leading slash,
  because both are segments. A document row comes off the wire — method a
  keyword, path rooted. Normalising here keeps one fact in one place; done at
  the call site it would be two spellings agreeing by inspection, and there are
  two call sites.

  A row with no `:method` is a GET, the same default the rest of this screen
  uses — absent means an older jar rather than an unknown verb."
  [{:keys [method path]}]
  (fn [ep]
    (and (= (str/lower-case (str method))
            (str/lower-case (clojure.core/name (or (:method ep) :get))))
         (= (str "/" path) (:path ep)))))

(defn doc-link
  "A resolver for `[[wikilink]]` targets in a docstring belonging to `within` —
  a qualified name like `demo.api.endpoints/modules`, or nil.

  **The meaning of a target is entirely this app's**, and slopp says so: nothing
  in slopp reads `[[…]]` — no rule, no index, no derivation — so it is prose
  with no machine reader. This app decides it names a FORM, and points at that
  form's page.

  A BARE target resolves in `within`'s namespace, which is what an author means
  by `[[act]]` in a docstring that lives beside `act`. With no `within` there is
  nothing to resolve against and the link degrades to its own text, which is the
  property that kept the syntax over `[name](#target)`.

  Built through [[slopp-server.ui.schema/handler-source-path]] rather than by
  concatenating — it is the one builder for this address, and the alternative is
  a second spelling that agrees today. `read its source` on this very page uses
  it too."
  [within]
  (fn [target]
    (let [t (str target)
          q (if (str/includes? t "/")
              t
              (when-let [n (some-> within str (str/split #"/") first not-empty)]
                (str n "/" t)))]
      (when (seq (str q)) (schema/handler-source-path q)))))

(defn endpoint-main
  "ONE endpoint, in full — the page [[endpoints-main]] links to.

  Everything the index drops on purpose lives here: path parameters, the
  request and response field lists, and the type line above each.

  **An unknown name is stated, not rendered as an empty page.** A `:name` that
  is not in the document is the same class of wrong as a lens that draws the
  default view — the reader asked for something specific and would otherwise
  be left believing they had seen it. Same stance as `route-for` on an unknown
  lens, one rung further in: the router cannot check this one, because the
  population is a document that arrives over the wire.

  The type line is printed even when the field list is empty. `[:or …]` is the
  live instance — `schema/rows` does not flatten one, so without the line the
  endpoint would render as having no response at all."
  [endpoints address try-state]
  (if-let [{:keys [method path request response handler doc auth]
            ep-name :name :as ep}
           (first (filter (at-address? address) endpoints))]
    (let [params (schema/path-params path)
          ;; WHICH of the two is showing, from the ADDRESS. Both used to render
          ;; in full, one above the other, and a real endpoint's field lists are
          ;; long enough that the page stopped being readable.
          ;;
          ;; **Defaults to RESPONSE, and the first guess was `request`.** Call
          ;; order says request; the data says otherwise. slopp's surface is
          ;; GET, a GET carries no body, so the request tab reads `no
          ;; parameters` on most endpoints — a default that is EMPTY for the
          ;; common case. The response is the part that is never empty.
          ;;
          ;; Eleven existing tests went red on the other default, and every one
          ;; of them asserts response content: the nested field list, `one of 2
          ;; shapes`, a field's `:doc`. Those were written about what this page
          ;; is FOR, which is better evidence than an argument from convention.
          ;;
          ;; The tab ORDER stays request-then-response, because that is the
          ;; order the two things happen in. The default is which one is worth
          ;; showing, and they are different questions.
          ;;
          ;; An unrecognised value FALLS BACK, unlike a lens: nothing declares
          ;; which parts exist, so `?part=nonsense` is a typed url rather than a
          ;; promise this app made, and a query string cannot 404 anyway.
          showing (if (= "request" (:part address)) "request" "response")
          ;; through [[endpoint-address]], which is the one builder for this
          ;; page's own url — its docstring already names the drift a second
          ;; spelling causes.
          ;; **Its own class, not `lens-bar`'s.** Reusing that was right about
          ;; the semantics — two views of one subject — and wrong about the
          ;; appearance, which is the half a reader sees. `lens-bar` is styled
          ;; for its OWN region at the top of a page, where position carries the
          ;; meaning; inline in the middle of prose it renders as two words, one
          ;; of them green, with no affordance saying either is a tab.
          tab     (fn [p]
                    (if (= p showing)
                      [:strong {:class "tab-current"} p]
                      [:a {:href (str (endpoint-address ep) "?part=" p)} p]))
          ;; NO heading. The tab is the label — an `<h3>request</h3>` under a
          ;; tab already reading `request` says the same word twice and leaves a
          ;; reader working out which one is the control.
          part   (fn [heading s]
                   [:div {:class "schema-part"}
                    (if s
                      [:div
                       [:p (schema/type-label s)]
                       (schema-fields (schema/nest (schema/rows s)))]
                      ;; "no request body" on a GET names a thing a GET never has. The
                      ;; absent fact is parameters; the body wording belongs to
                      ;; the methods that can carry one.
                      [:p (if (and (= "request" heading) (= :get (or method :get)))
                            "no parameters"
                            (str "no " heading " body"))])])]
      [:article {:class "endpoint"}
       [:h2 (str (str/upper-case (clojure.core/name (or method :get))) " " path)]
       ;; **Inferred from the METHOD, and this comment used to argue the
       ;; opposite.** It said: the declaration, not an inference from the
       ;; method, because slopp's whole surface is GET and a GET can be
       ;; effectful. Both halves were wrong. slopp's safe-method gate REFUSES
       ;; a GET that reaches a mutation, so an effectful GET is not a shape a
       ;; slopp store can serve; and the `:effectful?` it deferred to is
       ;; published by no slopp server at all, so this read was nil on every
       ;; real document while the fixture invented the key.
       ;;
       ;; It also worried that absent is not `false` and that inferring would
       ;; be inventing a safety claim. The inference is the SAFER direction
       ;; here — see `schema/effectful?` — and the claim it declined to make is
       ;; the one the wire can actually support. Nothing is rendered for a
       ;; read-only endpoint either: a badge on every row is a badge nobody
       ;; reads.
       (when (schema/effectful? ep)
         [:p [:strong {:class "effectful"} "changes something"]])
       ;; who may call, beside whether calling changes anything — the pair a
       ;; reader wants before doing either. STATED here rather than marked,
       ;; because the page has room and this is first-order; the index marks
       ;; only what narrows it.
       ;;
       ;; Nothing at all when the document does not say. It is never
       ;; nil-because-nobody-said on a current jar — the auth write gate
       ;; refuses an endpoint with no `:http/auth` — so absent means an OLDER
       ;; jar, and rendering that as public would assert an access rule nobody
       ;; published.
       (when-let [a (schema/auth-label auth)]
         [:p [:small (str "who may call: " a)]])
       ;; DRILL-DOWN when the document says where the handler lives, and the
       ;; search when it does not — two behaviours, both real, each labelled
       ;; for what it does.
       ;;
       ;; `:handler` arrived on the wire 2026-08-09. Until then the honest
       ;; thing was a search: the document gave the endpoint's `:name` and
       ;; nothing about where it lived, and on slopp's own store three of nine
       ;; names had more than one exact match — `ns-outline` resolved to both
       ;; `slopp.api.contracts/ns-outline` and `slopp.api.endpoints/ns-outline`
       ;; at rank 1.0, so a top-hit link would have pointed at the wrong form
       ;; for a third of the surface and looked exactly like a right one.
       ;;
       ;; The fallback is not padding: a document without `:handler` is what
       ;; every project on an older jar still publishes. And `read its source`
       ;; on a control that runs a query would be the label that makes a wrong
       ;; result look like a right one.
       ;; the handler's own FORM BODY — `/store/source/:ns/:name` renders that one
       ;; `(defn …)` under its own name and breadcrumbs out to the namespace,
       ;; so wider is one click and is not the default.
       ;;
       ;; **No fallback.** There was a search here, from before `:handler` was
       ;; published, and it was wrong in a way worth naming: a list of places
       ;; the function MIGHT be is not a link to the function. It looked like
       ;; the answer and produced a ranking, leaving the reader to do the
       ;; resolving the screen could not do — and the two controls were told
       ;; apart only by their labels.
       ;;
       ;; A document without `:handler` gets a sentence instead. That is a fact
       ;; about the PROJECT — its contract does not publish where its handlers
       ;; live — and unlike a search box it is actionable.
       (if-let [src (schema/handler-source-path handler)]
         [:p [:small (str "client: " ep-name) " · "
              [:a {:href src} "read its source"]
              " · "
              ;; NAMED, so a reader knows where the link goes before pressing
              ;; it — the handler sits in a namespace the endpoint's own path
              ;; says nothing about
              [:code (str handler)]]]
         [:p [:small (str "client: " ep-name)
              " · this project's contract does not publish which form handles it"]])
       ;; the endpoint's own prose, WHOLE and above the parameters: what it does
       ;; before what it takes. The index shows only its first sentence, which
       ;; is why the document ships all of it rather than a summary — the
       ;; truncation is a consumer's decision, made per screen.
       ;; MARKDOWN, not one collapsed line. `schema/prose` squeezes whitespace runs,
       ;; which is right for the index's one-line summary and wrong here — and it
       ;; was doing both jobs, so this page rendered a three-paragraph docstring
       ;; as a wall of text. Wikilinks resolve against the HANDLER's name, which
       ;; is the namespace a bare `[[target]]` means.
       (when-let [blocks (markdown/as-hiccup doc (doc-link handler))]
         (into [:div {:class "endpoint-doc"}] blocks))
       (when (seq params)
         [:p (str "path parameters: " (str/join ", " params))])
       ;; TABBED, and styled as tabs rather than as `lens-bar`. See the `tab`
       ;; binding: sharing that class was right about the interaction and wrong
       ;; about the affordance, which is the half a reader actually sees.
       ;;
       ;; The separators are MARKUP, never a margin, for the reason this project
       ;; has now paid for four times: a reader without the stylesheet — which
       ;; is this project's own `screen` tool — sees the children run together.
       ;; They stay even though the rule below uses flex `gap`, because the
       ;; stylesheet is the thing that may be missing.
       (into [:nav {:class "endpoint-tabs" :aria-label "request or response"}]
             (interpose " " [(tab "request") (tab "response")]))
       (if (= "response" showing)
         (part "response" response)
         (part "request" request))
       ;; UNDER the schemas: you read what an endpoint takes, then call it.
       (try-panel (first (filter (at-address? address) endpoints))
                  try-state)])
    [:div
     [:h1 "no endpoint"]
     [:p "this project's contract document declares no "
      [:strong (str/upper-case (str (:method address)))]
      " at "
      [:code (str "/" (:path address))] "."]]))

(defn current-lens
  "Which lens `path` is being seen through, for a screen whose subject is
  `subject` — or nil for the bare address.

  **The ADDRESS is the only source, and that is the point.** The lens used to
  be three facts that could disagree: a `:lens` key in state, a segment in the
  url, and (once the route table named screen vars) the identity of the screen
  itself. `slopp.webapp/arrive` writes `:path`, `:screen` and `:params` and
  nothing else, so the state copy is gone — leaving the url, which is what a
  reader shared and what the back button restores.

  **A trailing segment is a lens only if [[lenses]] declares it for THIS
  subject.** Same refusal [[route-for]] made and for the same reason: marking a
  bar entry active because the url happens to end in a word tells a reader they
  are looking at something they are not. Cross-subject matches are refused too
  — `source` is a lens on `:form` and an ordinary route segment under `:code`,
  so a check that asked only \"is this any lens\" would light the Code bar on
  `/store/source`.

  The query string comes off FIRST, through [[slopp-server.ui.basepath/split-query]].
  Without that `/store/table?x=1` ends in a segment named `table?x=1`, which is
  no lens at all — the same bug `route-for` shipped before search existed to
  put text in a url."
  [path subject]
  (let [[bare _] (basepath/split-query path)
        segs     (vec (remove str/blank? (str/split bare #"/")))
        tail     (peek segs)]
    (when (and tail (some #{tail} (lenses subject)))
      tail)))

(defn with-store-picture
  "The Code index's answer with its diagram laid out — or unchanged, when there
  is nothing to draw.

  **Laid out once per render now, and it used to be once per ANSWER.** It was a
  route row's `:derive`, which `slopp.webapp/load!` applied inside the freshness
  guard, so a response nobody was waiting on was never laid out at all. `ask!`
  takes no `:derive`, so the page applies it — see [[slopp-server.ui.pages/code-page]].
  That is a real cost and it is recorded here rather than absorbed.

  **A picture with NO NODES is not attached** — `picture-of` reads `:layers`,
  and answers a layout with zero nodes for a response that carries none, which
  drew an empty `<svg>` where the page should fall back. Same defect as
  [[with-module-picture]], where it was load-bearing enough to hide a wrong
  sentence for the life of the screen.

  **The hand-drawn look is GONE here and it was a real loss, recorded rather
  than absorbed.** This used to be `app/wiring`'s app-wide `:webapp/derive`,
  which closed over a `:sketch` plug-in — rough.js, `:cljs`, supplied by the
  browser entry and absent headless. A `:derive` was `(fn [response])`: no
  state, no params, no wiring, so there was nowhere for a runtime plug-in to
  reach it. Reported to slopp, who recorded it unbuilt on the grounds that one
  cosmetic instance should not shape a seam — which is the argument this
  project made to them about its own lens rows.

  What that costs is exactly the difference between a headless render and a
  browser one, and `module-graph` already falls back to plain rects. So the
  browser now draws the diagram the tests have always seen, which is the
  cheapest direction for a divergence to collapse in."
  [data]
  (let [pic (graph/picture-of data)]
    (if (seq (:nodes pic))
      (assoc data :picture pic)
      data)))

(defn with-module-picture
  "One module's answer with its internal diagram laid out — or unchanged, when
  there is nothing to draw. See [[with-store-picture]], including what the
  split cost.

  **A picture with NO NODES is not attached, and that is a bug this migration
  surfaced rather than a refinement.** `picture-within` answers a layout object
  either way; on a `:pending` shape — one built from the modules INDEX, which
  has module-level deps and no namespace-level edges — that layout has zero
  nodes. Attaching it made `module-main` draw an empty diagram INSTEAD of
  saying the structure is not on the wire yet, which is exactly the failure the
  `:pending` sentence exists to prevent: *a page that quietly omits it reads as
  a module with no internal structure, which is a lie about every module that
  has any.*

  It shipped that way and no test saw it, because the derive was a route row's
  and the fixture wrote the load directly — so every assertion about this
  screen ran against data the derive had never touched. Move A moved the derive
  into the page, the fixture started answering the request instead of writing
  the result, and the empty diagram appeared. **The test was right and had been
  passing against a rendering the application never produced.**"
  [data]
  (let [pic (graph/picture-within data)]
    (if (seq (:nodes pic))
      (assoc data :picture pic)
      data)))

(def daemon-projects
  "The daemon's registry, as a descriptor measured from the ORIGIN — the one
  request this app makes that is not a project's.

  It is the generated descriptor for `GET /api/projects`: the daemon's own
  endpoints are declared beside every project's and published in the same
  contract, so the registry's rows are typed by the contract the daemon
  publishes, not by a copy kept here. **`:webapp/base \"\"` means measured
  from the origin**, which is why this cannot go through [[at-project]]:
  every other request this app makes is mounted under a project."
  (assoc api/projects-endpoint :webapp/base ""))

(defn at-project
  "The endpoint `descriptor`, measured against the project the route names.

  **The slug is ROUTE STATE, which is why this is per-request.** This app talks
  to two APIs: the daemon's own, at the origin, and whichever project the
  reader is looking at, which the daemon MOUNTS at `/api/projects/<slug>/…`.
  A single app-level `:webapp/base` cannot serve both — and under a root
  shell it cannot serve the second alone, because the slug changes when the
  project switcher is used and nothing re-stamps a page-load attribute
  mid-page.

  `slopp.webapp/addressed` gives a REQUEST's own base the last word over the
  app's for exactly this, and `slopp.http.endpoint/request` copies the key off
  the descriptor onto the request it builds. So the whole of *which api is this
  measured from* is this one function.

  **The mount REPLACES the producer's prefix; it does not nest under it.** A
  project's contract declares `/api/modules` — `/api` being that store's own
  API partition — and the daemon serves it at `/api/projects/<slug>/modules`:
  one root, no second `/api` inside. So the descriptor's path loses the
  prefix the base stands in for, and the two halves join to the address the
  daemon actually answers. The prefix is the one every published contract
  spells (`rest.prefix`, `/api` unless a store moves it), which is why it is
  a constant here rather than read off the descriptor: a descriptor carries
  the path, not the partition it sits in.

  **It decides the LOAD KEY as much as the url.** `load-key` takes the base
  and resolves the address through `addressed` before keying, so two
  projects' `/modules` are two loads — measured the other way once, when
  navigating to a second project rendered the first one's modules under its
  name, because a cache hit is silent by construction.

  **What it does not do is leak the slug.** A page names the params it sends,
  one endpoint at a time, and `slopp.http.endpoint/request` REFUSES a key the
  descriptor does not declare; the tenant travels only in the base."
  [{:keys [slug]} descriptor]
  (assoc descriptor
         :webapp/base (str "/api/projects/" slug)
         :http/path   (str/replace-first (str (:http/path descriptor)) #"^/api(?=/|$)" "")))

(defn page-params
  "The ADDRESS captures for content page `page` — `{:ns \"slopp-server.ui.hub\" :name \"shell\"}`
  — or nil when its `:handler` is not a qualified symbol.

  **A content page is addressed by its VAR, where an endpoint is addressed by
  method and path.** The asymmetry is forced rather than chosen. `/pages/*path`
  is the obvious shape and it cannot address this store's own shell, which is
  served at `/`: the wildcard would be empty and the most important page here
  would be the one nobody could open.

  The var is total. `http-unreachable-declaration` refuses a private content
  form, so every published row has a public var, and `:handler` carries it
  fully qualified for the reason the document states — `:name` alone does not
  resolve, and a third of real surfaces have duplicate simple names.

  Nil rather than a guess when the handler is unqualified: inventing a
  namespace would address a form that may not exist, and the not-found page a
  reader would then meet says nothing about which half was wrong."
  [{:keys [handler]}]
  (when-let [ns' (some-> handler str symbol namespace)]
    {:ns ns' :name (name (symbol (str handler)))}))

(defn page-address
  "The in-app path for content page `page`'s own screen —
  `/pages/slopp-server.ui.shell/shell` — or nil when it has no address.

  Derived from [[page-params]] rather than spelled again, so a link and the
  screen it opens cannot disagree about which page is meant. Same reason
  [[endpoint-address]] goes through [[endpoint-params]].

  It deliberately echoes `slopp-server.ui.schema/handler-source-path`'s
  `/store/source/:ns/:name` — a reader meets one spelling for *which form is
  this* in both sections."
  [page]
  (when-let [{:keys [ns name]} (page-params page)]
    (str "/http/paths/" ns "/" name)))

(defn at-page?
  "A predicate for the content page an ADDRESS names — `{:ns \"…\" :name \"…\"}`.

  Both halves, always. A page is not identified by its var name alone — two
  namespaces may each hold a `shell` — which is the same fact that made the
  document publish a qualified `:handler` in the first place."
  [{:keys [ns name]}]
  (fn [page]
    (let [p (page-params page)]
      (and p (= (str ns) (:ns p)) (= (str name) (:name p))))))

(defn pages-main
  "The Pages INDEX: every `:http/path` content form a project serves.

  Takes the ROWS of `GET /api/http/paths`, the sibling of the document the API
  section reads — the route strips the envelope with `:derive :paths`, so this
  screen knows nothing about the version key.

  **The other half of the api/content partition.** `:rest/path` declares an API
  and gets the API section; `:http/path` declares CONTENT — a stored value the
  dispatcher serves by DEREFERENCING rather than calling — and had no screen at
  all, because until slopp published this document nothing listed it. A remote
  consumer could discover every API and zero pages.

  **An EMPTY document is the common case and gets words, not an empty table.**
  Most projects serve no content of their own; slopp's own app is one. A reader
  who lands here on such a project has asked a reasonable question and deserves
  an answer, and `0 pages` over a header row is the shape that reads like a
  broken screen.

  Nil is the older, different case — no document at all — and names the address
  so a reader can curl what the page could not get."
  [pages]
  [:div
   ;; **`HTTP paths`, naming the capability.** Three sections each list
   ;; something, so a bare `Paths` repeats the tab above it and says nothing a
   ;; reader crossing between them can use. `Pages` — what this said — is now
   ;; the WEBAPP section's word, and using it here for `:http/path` content
   ;; would collide with the other listing outright.
   [:h1 "HTTP paths"]
   (cond
     (nil? pages)
     [:p "no content document — this project publishes no "
      [:code "/api/http/paths"] "."]

     (empty? pages)
     [:p "this project serves no static pages — every route it declares is an "
      [:a {:href "/rest/paths"} "API"] "."]

     :else
     [:div
      [:p (str (plural (count pages) "page") ", as the project publishes them")]
      [:table {:class "data-table"}
       [:thead
        [:tr [:th "path"] [:th "what it is"] [:th "type"] [:th "serves"]]]
       (into [:tbody]
             (for [{:keys [path doc media-type] :as page} pages]
               [:tr
                ;; the PATH is the link and the row's identity, the way the API
                ;; index links the path rather than the name — it is what a
                ;; reader recognises and the widest thing in the row.
                [:td (if-let [addr (page-address page)]
                       [:a {:href addr} path]
                       path)]
                (let [d (schema/first-sentence doc)
                      a (schema/auth-label (:auth page))
                      parts (cond-> []
                              d (conj [:small d])
                              (:shell page) (conj [:small [:strong {:class "effectful"} "boots the app"]])
                              (and a (not= "public" a)) (conj [:small [:strong {:class "effectful"} a]]))]
                  ;; interposed rather than prefixed, and `—` only when the cell
                  ;; is otherwise EMPTY — both settled next door, and a
                  ;; separator with nothing on its left is the defect the API
                  ;; index shipped for a while.
                  (into [:td]
                        (if (seq parts)
                          (interpose [:small " · "] parts)
                          [[:small "—"]])))
                ;; the media type and what the value IS are one fact for a reader —
                ;; `text/css · text · 14.3 kB` — so they share a cell rather
                ;; than splitting a narrow column in two.
                [:td [:small media-type
                      (when-let [c (schema/content-label page)] (str " · " c))]]
                ;; the FORM that serves it — the join to code, and the reason the
                ;; row has an address at all.
                [:td [:small (str (:name page))]]]))]])])

(defn page-main
  "ONE content page, in full — the screen [[pages-main]] links to.

  `address` is `{:ns \"slopp-server.ui.hub\" :name \"shell\"}`, the var rather than the
  path, because a page served at `/` has no path to address it by. See
  [[page-params]].

  **An unknown address is stated, not rendered as an empty page** — the same
  stance [[endpoint-main]] takes, and for the same reason: the reader asked for
  something specific and would otherwise be left believing they had seen it.

  What is here that the index drops: the whole docstring as markdown, who may
  call, the value's description, and the bundle for a page that boots an
  application. The VALUE itself is deliberately absent — the document describes
  it rather than shipping it, and the URL already serves it to anyone who wants
  the bytes."
  [pages address]
  (if-let [{:keys [path method doc media-type auth handler] :as page}
           (first (filter (at-page? address) pages))]
    [:article {:class "endpoint"}
     [:h2 (str (str/upper-case (clojure.core/name (or method :get))) " " path)]
     (when-let [a (schema/auth-label auth)]
       [:p [:small (str "who may call: " a)]])
     [:p [:small "serves: "]
      (if-let [src (schema/handler-source-path handler)]
        [:span [:a {:href src} "read its source"] " · " [:code (str handler)]]
        [:code (str handler)])]
     (when-let [blocks (markdown/as-hiccup doc (doc-link handler))]
       (into [:div {:class "endpoint-doc"}] blocks))
     [:div {:class "schema-part"}
      [:p [:small media-type " · " (schema/content-label page)]]
      ;; the bundle, for a page that BOOTS an application. Present only when
      ;; the document carries `:shell`, which it does only for a page that
      ;; declares one — so this says nothing about an ordinary document rather
      ;; than saying "no bundle".
      (when-let [bundle (:shell page)]
        [:p [:small "boots the application from "] [:code bundle]])]]
    [:div
     [:h1 "no page"]
     [:p "this project's content document declares no "
      [:code (str (:ns address) "/" (:name address))] "."]]))

(defn page-nav
  "The Pages section's left PANE: every content form the project declares.

  Rows carry the PATH, because that is what a reader recognises and what the
  index links — the var is the address, not the label. Same split the API rail
  makes between `endpoint-path` and the row's href.

  The `\" \"` between path and type is real markup rather than a CSS gap: this
  pane is read by the `screen` tool with no stylesheet, and two inline siblings
  separated by a margin are one word to it."
  [pages current]
  [:div
   (into [:ul {:id "page-list"}]
         (for [{:keys [path media-type] :as page} pages
               :let [addr (page-address page)]]
           [:li {:class "endpoint-row"}
            [:a (cond-> {:href addr}
                  (= addr current) (assoc :class "active"))
             [:span {:class "endpoint-path"} path]
             " "
             [:small media-type]]]))])

(defn routed-address
  "The endpoint ADDRESS a route's captures name — `{:method \"get\" :path \"api/modules\" :part …}`.

  **The translation seam for slopp's anonymous wildcards.** A route ending in
  `**` binds its remainder under `:*`, because the grammar allows at most one
  wildcard and so needs no name for it. Everything on this side of the seam
  speaks `:path`: [[endpoint-params]] builds an address from a DOCUMENT ROW,
  which has no router in it to name a capture, and [[at-address?]] compares the
  two.

  It is its own form because TWO things read a route's captures — the screen
  and `slopp-server.ui.app/try-request`, which must select the same endpoint the page
  is showing or the call form builds a request for something else. Written
  twice, they would agree until one of them was edited, which is the shape this
  store has already paid for in `at-address?`'s own history."
  [params]
  (let [{:keys [method part] splat :*} params]
    {:method method :path splat :part part}))

(defn webapp-page-params
  "The ADDRESS captures for webapp page `row` — `{:ns \"slopp-server.ui.pages\" :name \"code-page\"}`
  — or nil when its `:page` is not a qualified symbol.

  **Through [[page-params]], because it is the same fact.** A content form
  carries its var as `:handler` and a webapp page carries it as `:page`; both
  are addressed by namespace and name, both are total for the same reason (the
  document publishes a QUALIFIED symbol, and a bare name neither resolves nor
  distinguishes two namespaces that each declare a `shell`), and both answer
  nil rather than guessing at a namespace. Written twice they would drift on
  the nil case first, which is the half nobody tests by hand.

  **Addressed by VAR rather than by the route it answers**, which is the
  sharper version of the argument the content section already makes.
  `/webapp/pages/**` would have to carry `/p/:slug/store/form/:id` as data —
  a route pattern inside a route — so the colons and the `**` in it would be
  matched by this app's own router on the way in. The var has no such
  characters and is unique by construction: a write gate refuses two pages
  claiming one address, so no two rows can share a var either."
  [row]
  (page-params {:handler (:page row)}))

(defn webapp-page-address
  "The in-app path for webapp page `row`'s own screen —
  `/webapp/pages/slopp-server.ui.pages/code-page` — or nil when it has no address.

  Derived from [[webapp-page-params]] rather than spelled again, so a link and
  the screen it opens cannot disagree about which page is meant. Same reason
  [[page-address]] goes through [[page-params]] and [[endpoint-address]]
  through [[endpoint-params]]."
  [row]
  (when-let [{:keys [ns name]} (webapp-page-params row)]
    (str "/webapp/pages/" ns "/" name)))

(defn at-webapp-page?
  "A predicate for the webapp page an ADDRESS names — `{:ns \"…\" :name \"…\"}`.

  Both halves, always. A page is not identified by its var name alone — this
  store alone has `content-page` and `form-page` and could as easily have two
  namespaces each declaring `index-page` — which is the same fact that makes
  the document publish a qualified symbol. See [[at-page?]], which this
  mirrors."
  [{:keys [ns name]}]
  (fn [row]
    (let [p (webapp-page-params row)]
      (and p (= (str ns) (:ns p)) (= (str name) (:name p))))))

(defn webapp-pages-main
  "The Webapp PAGES index: every screen a project's browser app declares.

  Takes the ROWS of `GET /api/webapp/paths` — the third of the three path
  documents, beside the REST endpoints and the HTTP content forms. A row is
  `{:path :page :doc :calls}`, where `:path` is the route pattern the page
  answers and `:page` is the var that answers it.

  **`:calls` is the column this section exists for.** It is DERIVED from the
  reference graph — what the page's own form references that is an endpoint
  descriptor — so it cannot disagree with the code, and it is the join between
  two sections of this application: every call links to that endpoint's page in
  the REST section. Nothing else anywhere says *which screens would break if
  this endpoint changed*, and it is readable in both directions because the
  REST index is the same data seen from the other end.

  **Absent, not empty, when a page fetches nothing** — slopp's rule, because an
  empty vector would be a claim and the derivation is bounded: a descriptor is
  a map literal with an `:http/path`, and a page reaching an endpoint some other
  way is invisible here. So a row with no calls says so in words; a blank cell
  reads as data the screen failed to load.

  **An EMPTY document is the common case and gets words, not an empty table.**
  Most projects serve an API and no browser app at all. Nil is the older,
  different case — no document — and names the address so a reader can curl
  what the page could not get. Same three-branch shape as [[pages-main]], for
  the same reasons."
  [rows]
  [:div
   ;; **`Webapp pages`, naming the capability** — see `pages-main`. `Pages` is
   ;; this section's word, and the heading says which section it is the word of.
   [:h1 "Webapp pages"]
   (cond
     (nil? rows)
     [:p "no webapp document — this project publishes no "
      [:code "/api/webapp/paths"] "."]

     (empty? rows)
     [:p "this project declares no webapp pages — it serves an "
      [:a {:href "/rest/paths"} "API"] " and no browser app of its own."]

     :else
     [:div
      [:p (str (plural (count rows) "page") ", as the project declares them")]
      [:table {:class "data-table"}
       [:thead
        [:tr [:th "address"] [:th "what it is"] [:th "fetches"]]]
       (into [:tbody]
             (for [{:keys [path doc calls] :as row} rows]
               [:tr
                ;; the route PATTERN is the label and the var is the address —
                ;; the same split the content index makes, and for the same
                ;; reason: a reader recognises the url, not the function.
                [:td (if-let [addr (webapp-page-address row)]
                       [:a {:href addr} path]
                       path)]
                [:td [:small (or (schema/first-sentence doc) "—")]]
                ;; each call links into the REST section, through the same
                ;; builder that section's own index and rail use — so a link
                ;; here and the page it opens cannot disagree.
                ;; **INTERPOSED, not a `<ul>`.** A bullet list here rendered
                ;; `GET /api/form/:idGET /api/modules` in text: two elements
                ;; separated by nothing but layout, which is one word to a
                ;; reader without CSS — and the reader without CSS is the
                ;; `screen` tool. Same separator the sibling index uses.
                (into [:td]
                      (if (seq calls)
                        (interpose
                          [:small " · "]
                          (for [{:keys [method path]} calls]
                            [:a {:href (endpoint-address {:method method :path path})}
                             [:small (str (str/upper-case (name (or method :get)))
                                          " " path)]]))
                        [[:small "fetches nothing"]]))]))]])])

(defn webapp-page-main
  "ONE webapp page, in full — the screen [[webapp-pages-main]] links to.

  `address` is `{:ns \"slopp-server.ui.pages\" :name \"code-page\"}`, the var rather than
  the route it answers. See [[webapp-page-params]]: a page's own address is a
  route PATTERN, colons and `**` included, and carrying one as data inside this
  app's address would hand its router a pattern to match.

  **An unknown address is stated, not rendered as an empty page** — the same
  stance [[page-main]] and [[endpoint-main]] take, and for the same reason: the
  reader asked for something specific and would otherwise be left believing
  they had seen it.

  What is here that the index drops: the whole docstring as markdown, and every
  call rather than a summary. **`:calls` absent is said in words and with its
  reason**, because absent means *nothing the reference graph could see* rather
  than *nothing* — a page that reaches an endpoint some other way is invisible
  to the derivation, and a reader who does not know that will read silence as a
  fact about the page."
  [rows address]
  (if-let [{:keys [path page doc calls]} (first (filter (at-webapp-page? address) rows))]
    [:article {:class "endpoint"}
     [:h2 path]
     [:p [:small "answered by: "]
      (if-let [src (schema/handler-source-path (str page))]
        [:span [:a {:href src} "read its source"] " · " [:code (str page)]]
        [:code (str page)])]
     (when-let [blocks (markdown/as-hiccup doc (doc-link page))]
       (into [:div {:class "endpoint-doc"}] blocks))
     [:div {:class "schema-part"}
      [:h3 "fetches"]
      (if (seq calls)
        (into [:ul {:class "page-calls"}]
              (for [{:keys [method path]} calls]
                [:li [:a {:href (endpoint-address {:method method :path path})}
                      (str (str/upper-case (clojure.core/name (or method :get))) " " path)]]))
        [:p [:small "fetches nothing — no endpoint descriptor is named in this
             page's own form. A page that reaches one some other way is
             invisible to the derivation, so this is what the graph could see
             rather than a promise about the code."]])]]
    [:div
     [:h1 "no page"]
     [:p "this project's webapp document declares no "
      [:code (str (:ns address) "/" (:name address))] "."]]))

(defn webapp-page-nav
  "The Webapp section's left PANE: every screen the project declares.

  Rows carry the route PATTERN, because that is what a reader recognises and
  what the index links — the var is the address, not the label. Same split the
  API and content rails make.

  The `\" \"` between the pattern and its call count is real markup rather than
  a CSS gap: this pane is read by the `screen` tool with no stylesheet, and two
  inline siblings separated by a margin are one word to it."
  [rows current]
  [:div
   (into [:ul {:id "webapp-page-list"}]
         (for [{:keys [path calls] :as row} rows
               :let [addr (webapp-page-address row)]]
           [:li {:class "endpoint-row"}
            [:a (cond-> {:href addr}
                  (= addr current) (assoc :class "active"))
             [:span {:class "endpoint-path"} path]
             " "
             ;; the CALL COUNT rather than the endpoints themselves: the rail is
             ;; for finding a page, and a list of urls per row would make every
             ;; row three lines. Zero is written as a word for the same reason
             ;; the index does it — `0` beside a path reads as a failure to load.
             [:small (if (seq calls)
                       (plural (count calls) "call")
                       "no calls")]]]))])

(defn config-main
  "The Config page: every setting this project has, whoever owns it.

  Takes the WHOLE `GET /api/config` document rather than its rows, because
  four of the five things worth showing are not rows — `:owners` is the
  vocabulary the owner column comes from, `:patterns` are settable SPACES
  rather than settings, `:bundle` is derived, and `:orphaned` is a migration
  instruction. A `*-main` taking rows would have to be handed the rest
  separately and could then be given a mismatched pair.

  **ONE page for every owner, and that is Nathan's call rather than the first
  design.** The plan was a `Settings` page inside the HTTP section, and the ask
  went to slopp in those words. A reader asking *what is this project
  configured to do* wants `rest.enabled` and `webapp.enabled` in the same
  answer as `http.port`, and `http.*` is one owner out of six — so grouping by
  `:owner` inside one document beats four documents, and the page never has to
  learn the list of capabilities to avoid silently omitting the fifth.

  **Three distinctions the document makes deliberately, each of which a
  settings table normally collapses into a blank cell:**

  - a SECRET is WITHHELD — *this is configured and I am not showing you*, never
    *nothing here*. slopp withholds by credential FAMILY (`http.auth.bearer.`
    and its siblings), so `oidc.issuer` is withheld too although it is not a
    secret by any reasonable reading. That is the safe direction and it is
    deliberate: a key-level allowlist is the thing that goes stale dangerously.
  - `:set` absent means DEFAULTED, which is not the same fact as
    set-to-the-default. The `source` column says which.
  - `:effective` absent, or nil, means UNSET, and says so in words. A blank
    cell reads as data the screen failed to load.

  `pr-str` on the value rather than `str`, because `:effective` carries
  WHATEVER TYPE the registry entry declares — a number for `http.port`, a
  boolean for `http.enabled`, a set for `http.auth.providers`. `str` on a set
  and `str` on a string are indistinguishable once rendered, and this is the
  one column where a reader is trying to tell types apart.

  **No links.** Every other index in this app links somewhere; there is one
  config document and one page showing it, so a link here would only ever
  point back at this page."
  [{:keys [config owners patterns bundle orphaned] :as doc}]
  [:div
   [:h1 "Config"]
   (cond
     (nil? doc)
     [:p "no config document — this project publishes no "
      [:code "/api/config"] "."]

     (empty? config)
     [:p "no settings to show — this project's configuration document is empty."]

     :else
     [:div
      [:p (str (plural (count config) "setting")
               ", grouped by the capability that owns them")]

      ;; the bundle, and it is the reason a config PAGE was asked for at all:
      ;; a missing `:webapp/bundle` cost a day of white-page hunting, and the
      ;; ask would have been found sooner if a screen had shown it. Absent
      ;; when no mount reaches it, which is a real answer rather than a gap.
      (when bundle
        [:p "browser bundle served at " [:code bundle] "."])

      ;; **Unrecognised keys go FIRST**, above the settings they are not part
      ;; of. They are the only rows on this page that ask the reader to do
      ;; something, and each carries its value so the row is the migration
      ;; instruction rather than a prompt to go and look it up.
      (when (seq orphaned)
        [:section {:class "config-orphaned"}
         [:h2 "Unrecognised keys"]
         [:p "stored under a name this build does not know — usually a "
          "capability rename nobody migrated. Nothing reads these. Each row "
          "carries its value, so it is a migration instruction."]
         [:table {:class "data-table"}
          [:thead [:tr [:th "key"] [:th "value"]]]
          (into [:tbody]
                (for [{:keys [key value secret]} orphaned]
                  [:tr
                   [:td [:code key]]
                   [:td (if secret
                          [:small "withheld"]
                          [:code (pr-str value)])]]))]])

      (into [:div]
            (for [[owner rows] (sort-by first (group-by :owner config))]
              [:section {:class "config-owner"}
               [:h2 owner]
               ;; what the owner label MEANS, from the document's own
               ;; vocabulary — it rides along precisely because a reader of
               ;; this document has no other route to it.
               (when-let [meaning (get owners owner)]
                 [:p [:small (schema/first-sentence meaning)]])
               [:table {:class "data-table"}
                [:thead
                 [:tr [:th "setting"] [:th "what it does"]
                  [:th "value"] [:th "source"]]]
                (into [:tbody]
                      (for [{:keys [key doc effective secret] :as row} rows]
                        [:tr
                         [:td [:code key]]
                         [:td [:small (or (schema/first-sentence doc) "—")]]
                         [:td (cond
                                secret                        [:small "withheld"]
                                (not (contains? row :effective)) [:small "unset"]
                                (nil? effective)              [:small "unset"]
                                :else                         [:code (pr-str effective)])]
                         [:td [:small (if (:set row) "set" "default")]]]))]]))

      ;; the FAMILIES, last and apart: they name settable spaces rather than
      ;; settings, so they have no value of their own and mixing them into the
      ;; tables above would put a row with a permanently empty value column
      ;; beside rows where an empty value means something.
      (when (seq patterns)
        [:section {:class "config-patterns"}
         [:h2 "Families"]
         [:p "wildcard spaces a store may name keys in — these are settable "
          "SPACES rather than settings, so they have no value of their own."]
         [:table {:class "data-table"}
          [:thead [:tr [:th "family"] [:th "owner"] [:th "what it does"]]]
          (into [:tbody]
                (for [{:keys [key owner doc]} patterns]
                  [:tr
                   [:td [:code key]]
                   [:td [:small owner]]
                   [:td [:small (or (schema/first-sentence doc) "—")]]]))]])])])

(defn dashboard-main
  "The Dashboard section's main pane: what this project IS, how it grew, what
  it has been doing, and what that cost.

  Takes the documents [[slopp-server.ui.pages/dashboard-page]] asks for —
  `/api/namespaces`, `/api/timeline`, `/api/cost?by=ask` and
  `/api/cost?by=commit-point` — and does no arithmetic of its own. The counting
  is [[slopp-server.ui.metrics]], so an assertion about a number does not have to go
  through hiccup to reach it.

  **Every bar states its own number.** A chart whose magnitudes live only in
  the geometry is invisible to the headless reader, which is this project's
  actual reader; the bar is the ornament and the label is the fact.

  **An empty panel says WHICH kind of nothing it is**, and there are now four,
  none interchangeable: token usage is *not exported* until the harness sends
  telemetry; growth and effort are *not counted* because the cost fold has
  nothing for this store yet; test timing is *not aggregated* here because
  slopp publishes it per range and this screen does not walk every range. The
  fourth is an empty store, which renders its zeros.

  **The biggest-namespaces ranking was here and is gone** (2026-09-06). It
  listed the twelve largest with a bar each; the store panel above already
  says how many namespaces and how many forms, and the Code section is where
  a reader goes to ask which ones. `metrics/top-namespaces` went with it
  rather than staying as surface nothing calls.

  **The token panel reports three numbers, not one.** `:unattributed` and
  `:undated` are kept out of the ask rows by slopp on purpose, so a panel that
  folded only `:rows` would understate the spend and say nothing — which it
  did, until 2026-09-05."
  [{:keys [namespaces timeline cost effort]}]
  (let [rows    (or namespaces [])
        shape   (metrics/store-shape rows)
        cadence (metrics/by-day (:commit-points timeline))
        peak    (reduce max 1 (map :count cadence))
        spend   (metrics/spend-summary cost)
        bars    (metrics/recent-token-use (:rows cost) 8)
        series  (metrics/growth (:rows effort))
        eff     (metrics/effort-totals (:rows effort))
        top-f   (reduce max 1 (map :forms series))]
    [:div
     [:h1 "dashboard"]
     [:section
      [:h2 "store"]
      [:p (plural (:namespaces shape) "namespace") " holding "
       (plural (:forms shape) "form")]]
     [:section
      [:h2 "store over time"]
      (if (seq series)
        [:div
         [:p (plural (count series) "commit point") " counted — from "
          (str (:forms (first series))) " to " (plural (:forms (last series)) "form")
          ", " (str (:namespaces (first series))) " to "
          (plural (:namespaces (last series)) "namespace")]
         [:svg {:class "cadence"
                :viewBox (str "0 0 " (max 10 (* 10 (dec (count series)))) " 40")}
          [:polyline {:class "growth-line" :fill "none"
                      :points (str/join " "
                                        (map-indexed
                                         (fn [i {:keys [forms]}]
                                           (str (* 10 i) ","
                                                (- 40 (* 36 (/ (double forms) top-f)))))
                                         series))}]]]
        [:p "not counted — the cost fold has no store-size series for this "
         "project yet. It rides /api/cost?by=commit-point and is absent for a "
         "commit point older than the fold."])]
     [:section
      [:h2 "commit points"]
      [:p (plural (reduce + 0 (map :count cadence)) "commit point")
       " across " (plural (count cadence) "day")]
      (when (seq cadence)
        (into [:svg {:class "cadence" :viewBox (str "0 0 " (* 12 (count cadence)) " 40")}]
              (map-indexed
               (fn [i {:keys [day count]}]
                 [:rect {:class "cadence-bar" :x (+ 1 (* 12 i))
                         :y (- 40 (* 36 (/ (double count) peak)))
                         :width 10 :height (* 36 (/ (double count) peak))
                         :data-day day :data-count (str count)}])
               cadence)))]
     [:section
      [:h2 "effort"]
      (if (pos? (:calls eff))
        [:div
         [:p (plural (:turns eff) "turn") ", " (plural (:calls eff) "call")
          ", " (str (:refused eff) " refused")]
         [:p (str "slopp held " (:slopp-share eff) "% of active time; ")
          (plural (:carried-chars eff) "character") " of context rent"]]
        [:p "not counted — no tool calls are recorded against this project's "
         "commit points."])]
     [:section
      [:h2 "token usage"]
      (if (seq bars)
        [:div
         [:p (plural (:tokens spend) "token") " across "
          (plural (:asks spend) "ask") " — "
          (plural (:requests spend) "request") ", "
          (metrics/usd (:cost-usd spend)) ", mean "
          (str (:mean-tokens spend)) " tokens per ask"]
         (when (pos? (:tokens (:unattributed spend)))
           [:p [:small "plus " (plural (:tokens (:unattributed spend)) "token")
                " in " (plural (:requests (:unattributed spend)) "request")
                " that fell in no ask, "
                (metrics/usd (:cost-usd (:unattributed spend)))
                " — total " (plural (:total-tokens spend) "token")]])
         (when (pos? (:undated spend))
           [:p [:small (plural (:undated spend) "request")
                " carried no timestamp and could be placed in no ask"]])
         (into [:ol {:class "bars"}]
               (for [{:keys [intent tokens cost-usd share]} bars]
                 [:li
                  [:span {:class "bar-label"} (str intent)]
                  " "
                  [:span {:class "bar-count"} (str tokens)]
                  " "
                  [:small (metrics/usd cost-usd)]
                  [:span {:class "bar" :style {:width (str (int (* 100 share)) "%")}}]]))]
        [:p "not exported — slopp measures its own wall clock and cannot see "
         "tokens. The harness exports them over OTLP, and nothing is exporting "
         "into this store yet, so there is no series to draw rather than a "
         "series of zeroes."])]
     [:section
      [:h2 "test execution time"]
      [:p "not aggregated here — slopp publishes the suite's time and counts on "
       "each verification in a range's arc, and this screen does not walk every "
       "range to sum them. The Review section shows them per change."]]]))

(defn lens-subject
  "The address a lens applies to, for the screen at `path` seen through `lens`
  (nil for the bare view): the path less its query string and its lens
  segment — except where a screen's address carries MORE than its subject.
  The through view names a second form after the subject, and a lens built on
  the whole address led to `/store/form/x/through/y/source`, which no route
  answers; a sweep of every href on every route found exactly one unroutable
  link, that one."
  [path subject lens]
  (let [bare (first (basepath/split-query (str path)))
        bare (if lens
               (subs bare 0 (max 0 (- (count bare) (inc (count lens)))))
               bare)]
    (if (= :form subject)
      (str/replace bare #"/through/[^/]+$" "")
      bare)))

(defn lens-bar
  "The switcher for the screen at `path`: its default view and every lens it
  offers, with the current one marked.

  **The chrome [[lenses]] said existed and did not.** That docstring named
  three consumers of the table and the third — the switcher chrome renders it
  — was aspirational, so `/store/gaps` had no link anywhere in the app and
  `with-lens` had exactly one production caller. A feature reachable only by
  typing its URL is a feature nobody finds.

  **nil for a screen with no lenses**, so `app-shell` can leave the element out
  entirely rather than render an empty bar. Same rule it already makes for an
  omitted pane: absent, not empty.

  The subject path is [[lens-subject]]'s answer — the lens segment taken back
  OFF, and a through view's second form with it — rather than rebuilt from
  `screen` and `params`. Rebuilding would make this a reverse router — a
  second producer of the scheme [[route-for]] and [[subject-for]] already own,
  and the defect this project has now paid for three times."
  [path screen lens]
  (when-let [ls (seq (lenses screen))]
    (let [subject (lens-subject path screen lens)
          item    (fn [l label]
                    (if (= l lens)
                      [:strong {:class "lens-current"} label]
                      [:a {:href (with-lens subject l)} label]))]
      (into [:nav {:class "lens-bar" :aria-label "views of this subject"}]
            ;; the separators are MARKUP, never a margin
            (interpose " "
                       (cons (item nil (default-view screen))
                             (for [l ls] (item l l))))))))
