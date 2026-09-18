(ns slopp-server.ui.app-test
  "Whole-screen review as an ordinary in-image test.

  Drives the REAL wiring — `app/wiring` is the same map `client.app` hands the
  browser — through the app's own `spa/navigate!`, with three plug-ins swapped:
  canned data for `js/fetch`, an atom for the DOM, nothing for rough.js. Then
  asserts on `readout/of` what a reader would see.

  **What it replaces:** a Playwright harness pointing at an absolute path into
  a global `node_modules`, rebuilt every session, used to review bugs that were
  plain wrong sentences. Five of the seven that got past my assertions in one
  week were sentences; two needed pixels and still do.

  A test here should assert on the READOUT rather than on hiccup, because that
  is what makes it a review: the readout is the whole screen at once, and the
  bugs that got through were all things nobody had thought to write an
  assertion about."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [slopp-server.ui.app :as app]
            [slopp-server.ui.views :as views] [slopp.cljnx :as cljnx] [slopp-server.ui.page :as page] [slopp.webapp :as webapp] [slopp-server.ui.schema :as schema] [slopp.http.endpoint :as endpoint] [slopp-server.ui.wire.api :as api]))

(deftest every-action-a-control-can-dispatch-is-one-the-interpreter-knows
  ;; `actions` is what the VIEWS emit and `act` is what the shell CALLS — two
  ;; writes joined by a keyword, which is the pairing that has already produced
  ;; three bugs in this store. So the vocabulary is iterated rather than
  ;; spot-checked, and each entry must actually move the state.
  (testing "the population is real, or every check below passes vacuously"
    (is (seq app/actions))
    (is (every? vector? (map :example (vals app/actions)))
        "each entry carries a runnable example of its own shape"))

  (testing "every action the REDUCER owns changes state when interpreted"
    ;; THREE kinds, not two, and this loop is the one that has to know it.
    ;; `slopp.webapp/dispatch!` splits on `:effectful?` (a request) and
    ;; `:leaves?` (hands the page back through `:webapp/leave!`); everything
    ;; else reduces. Both of the first two correctly have no `act` branch, so
    ;; a split that only knew about `:effectful?` reported `:project/goto` as
    ;; a reducer action that moved nothing — a red that was right about the
    ;; test and wrong about the code.
    (doseq [[kind {:keys [example value]}]
            (remove #(or (:effectful? (val %)) (:leaves? (val %))) app/actions)]
      (let [before {:show {} :filter ""}
            after  (app/act before example value)]
        (is (not= before after) (str kind " dispatched and nothing moved")))))

  ;; The split this loop used to have no way to express, and the reason it
  ;; broke when the vocabulary grew: `:try/execute` is intercepted by the
  ;; shell AHEAD of `act` and performs a real request, so `act` correctly has
  ;; no branch for it. Asserted rather than assumed, because an effectful
  ;; action that quietly reduced would fire on the pure path too.
  (testing "and every action the SHELL owns falls through the reducer untouched"
    (let [shell-owned (filter #(or (:effectful? (val %)) (:leaves? (val %)))
                              app/actions)]
      (is (seq shell-owned)
          "no action is declared effectful or leaves? — the loop below checks
           nothing and the split above silently covers everything")
      ;; both kinds are represented, so neither branch of the split is
      ;; asserted only by the other's members
      (is (seq (filter (comp :effectful? val) app/actions)))
      (is (seq (filter (comp :leaves? val) app/actions)))
      (doseq [[kind {:keys [example value]}] shell-owned]
        (let [before {:show {} :filter "" :call {:armed? true}}]
          (is (= before (app/act before example value))
              (str kind " is owned by the shell and the pure reducer moved
                   state for it anyway"))))))

  (testing "and an action the vocabulary does not declare is a no-op rather than
            a throw — a stale handler in a cached bundle must not white-screen"
    (let [s {:show {} :filter ""}]
      (is (= s (app/act s [:invented/action "x"] nil)))))

  (testing "the two doc actions agree with the reader the renderer uses"
    (is (true? (views/doc-open? (:show (app/act {:show {}} [:docs/all true] nil)) "any")))
    (is (true? (views/doc-open? (:show (app/act {:show {}} [:docs/one "a"] nil)) "a"))))

  (testing "the filter takes its text from the EVENT, not from the action —
            it is the one control whose payload the view cannot know"
    (is (= "web" (:filter (app/act {:filter ""} [:filter/set] "web"))))))

(deftest every-screen-can-be-DRIVEN-through-the-entry-the-tool-opens
  ;; The guard that would have caught `:timeline`, `:change` and `:source` at
  ;; once, instead of one at a time by accident over three weeks.
  ;;
  ;; **It drives rather than renders**, which is the whole difference. The
  ;; views test next door supplies its own data for every screen, so it is
  ;; green whether or not `page/responses` has an entry — a test that brings
  ;; its own fixture cannot notice that the SHARED entry has none. This one
  ;; goes through `page/page`, the same entry the `screen` tool opens, so a
  ;; missing canned answer is a failure here and a blind spot nowhere.
  ;;
  ;; The population is `views/screens` rather than a list written out here,
  ;; because a list written out here is the third copy of the thing that went
  ;; stale twice already.
  ;;
  ;; **The paths are DERIVED from the patterns** — `screens` holds
  ;; `/store/ns/:ns`, which nobody visits, and `example-path` fills it from
  ;; that row's own `:sample`. One source for matching and for driving, so a
  ;; row cannot describe one route and exercise another.
  (let [s     (cljnx/open! (cljnx/driver-for (page/page)))
        rows  (for [{:keys [path sample] :as row} views/screens]
                (assoc row :example (views/example-path path sample)))]

    (testing "the POPULATION is real — every derived example routes to the
              screen its row claims. This is what stops the table decaying
              into patterns nobody can reach, and it also checks the samples:
              a `:sample` that does not fill its pattern fails here"
      (is (seq rows))
      ;; Compared by PAGE IDENTITY rather than through a subject. The table is
      ;; derived from the `^{:webapp/path …}` markers now, so the page a row
      ;; claims is exactly the one carrying that row's pattern — and asserting
      ;; the router lands on that same function is stronger than asking what
      ;; subject it renders, because three pages here SHARE a subject while no
      ;; two may share an address.
      (let [table (cljnx/marked-pages)
            at    (fn [pattern] (second (first (filter #(= pattern (first %)) table))))]
        (doseq [{:keys [screen example path]} rows]
          (let [m (webapp/match-route table example)]
            (is (some? m) (str example " matches no row"))
            (is (some? (at path))
                (str path " is in `screens` but no page declares that address"))
            (is (= (at path) (:screen m))
                (str example " does not route to " screen))))))

    ;; **The fixture is checked DIRECTLY, and the first version of this was not.**
    ;; It inferred presence from the render — non-empty output, no error panel,
    ;; no `loading…` — and I proved it useless by removing `:source` and
    ;; watching it stay green. A screen with no canned answer renders empty
    ;; ELEMENTS: `<h1></h1>` over an empty `<pre>`, which is markup, is not an
    ;; error, and is exactly the state that hid for three weeks.
    ;;
    ;; So the guard written to catch that failure would not have caught it. The
    ;; drill-down test did, because it asserts real CONTENT — which is the
    ;; lesson: a presence check has to look at the population, not at a
    ;; downstream rendering of it.
    (testing "every screen has a canned answer, checked against the table
              itself rather than guessed from what it drew"
      (doseq [{:keys [screen]} rows]
        (is (some? (get page/responses screen))
            (str screen " has no entry in page/responses — reachable and "
                 "undrivable, which is how three screens hid"))))

    (testing "and every one of them DRIVES — handler reached, something drawn,
              no dispatch gap"
      (doseq [{:keys [screen example]} rows]
        (cljnx/visit! s example)
        (let [out (cljnx/text s "main")]
          (is (seq out) (str screen " rendered an empty main pane"))
          (is (not (re-find #"no screen renderer" out))
              (str screen " routes but nothing draws it")))))

    (testing "a screen with no fixture is what this catches, and the finder
              must be able to MISS — otherwise the loop above proves only that
              `visit!` can be called. An unrouted path renders not-found rather
              than a pane, which is the same shape a missing screen would take"
      (cljnx/visit! s "/nothing/here")
      (is (let [out (cljnx/text s "main" {:detail :prose})]
                  (and (re-find #"(?i)not found" out)
                       ;; it NAMES the path. Both this app's hand-written
                       ;; version and the framework default do, and that is the
                       ;; half worth asserting: "not found" alone is true of a
                       ;; blank pane with a heading, while the url is what tells
                       ;; a reader which link was wrong.
                       (re-find #"/nothing/here" out)))))))

(defn- addr-of
  "The address captures for the endpoint `doc` publishes under `nm`.

  These tests name endpoints the way a reader does — `\"register!\"` — while the
  address is METHOD and PATH. Deriving it from the document row keeps the two
  from being written twice: a hardcoded address that stops matching does not
  fail loudly, it renders the not-found page, and every negative assertion on
  that page passes."
  [doc nm]
  (views/endpoint-params
   (first (filter #(= (str (:name %)) (str nm)) doc))))

(deftest every-action-a-CONTROL-dispatches-is-one-the-vocabulary-declares
  ;; The missing direction of the pairing next door. That loop walks
  ;; `actions` and dispatches each entry, which can only catch a DECLARED
  ;; action nobody interprets. The reverse — an action a control really
  ;; emits and nothing declares — is what shipped: the call form dispatches
  ;; `:try/set`, `:try/arm` and `:try/execute`, and `actions` named none of
  ;; them while its docstring said it was "every action a control in this
  ;; app can dispatch".
  ;;
  ;; **The panel is the only view whose controls are CONDITIONAL** — arming
  ;; and firing are different buttons on different branches — so a fixture
  ;; that renders one state declares two thirds of nothing. All three are
  ;; rendered here, through `endpoint-main`, the view a screen actually
  ;; draws.
  ;;
  ;; A tree-seq over `coll?` is wrong for reading TEXT and the test above
  ;; says why; this walks `:on` ATTRIBUTE maps, which are data the view
  ;; states rather than prose it renders, and there is no readout for them.
  (let [doc  [{:method :post :path "/api/register" :name 'register!
               :effectful? true
               :request [:map [:name :string]]
               :response [:map [:slug :string]]}
              {:method :get :path "/api/modules" :name 'modules
               :request nil :response [:map [:a :string]]}]
        emitted (fn [v] (->> (tree-seq coll? seq v)
                             (filter map?)
                             (keep :on)
                             (mapcat vals)
                             (map first)
                             set))
        kinds   (into #{}
                      (mapcat emitted)
                      [(views/endpoint-main doc (addr-of doc "modules") nil)
                        (views/endpoint-main doc (addr-of doc "register!") nil)
                        (views/endpoint-main doc (addr-of doc "register!") {:armed? true})])]

    (testing "the population is real — three branches, three distinct actions.
              An empty set would make the check below pass by finding nothing,
              and a set of one would mean only one branch rendered"
      (is (= #{:try/set :try/arm :try/execute} kinds)
          (str "the call form's branches emitted " (pr-str kinds))))

    (testing "and every one of them is DECLARED — a control that dispatches a
              word the vocabulary does not carry is the half of this pairing
              that had no check"
      (doseq [k kinds]
        (is (contains? app/actions k)
            (str k " is dispatched by a control and app/actions does not "
                 "declare it"))))))

(deftest the-declaration-this-app-hands-both-entries-is-a-valid-one
  ;; `page/page` returns the DECLARED map, and the two entries do opposite
  ;; things with it:
  ;;
  ;;   headless   `cljnx/driver-for` derives the driving contract
  ;;   browser    `dom/mount!` wires what it is given → REFUSES it wired
  ;;
  ;; So the declaration is the one shape both accept, and getting it wrong
  ;; fails in opposite directions. Only one of them is reachable from a JVM:
  ;; `slopp-server.ui.client.app` is `:cljs` and never loads into the image, so
  ;; **this test is the only thing standing between a bad declaration and a
  ;; blank browser page.**
  ;;
  ;; It derives through `driver-for` rather than spelling
  ;; `(webapp/driver (webapp/wiring …))`, which is what it used to do. That
  ;; spelling was a SECOND derivation of one app, and a test asserting against
  ;; its own reconstruction passes whatever the tool actually does. `driver-for`
  ;; is the function the `screen` tool calls, so the two cannot diverge.
  (let [declared (page/page)]

    (testing "it is a DECLARATION — the derived key must be absent, because
              `dom/mount!` refuses a map that already carries it, and that is
              the call the browser entry makes"
      (is (not (contains? declared :webapp/view))
          ":webapp/view is present, so dom/mount! would throw and the browser
           would render nothing at all"))

    (testing "and `driver-for` derives the driving contract from it — the
              headless path, exercised through the tool's own derivation"
      (let [d (cljnx/driver-for declared)]
        (is (fn? (:view d)))
        (is (fn? (:navigate d)))
        (is (fn? (:dispatch d)))
        (is (fn? (:boot d)))))

    (testing "the declaration names only keys the framework knows. An unknown
              one is refused BY NAME rather than ignored, so this is what turns
              a typo into a sentence instead of a missing behaviour"
      (is (thrown? clojure.lang.ExceptionInfo
                   (webapp/wiring (assoc declared :webapp/no-such-key (fn [_]))))))))

(deftest every-declared-request-path-has-a-CANNED-answer
  ;; slopp's second tell for a fixture that agrees with the code it tests:
  ;;
  ;;   > removing an arm of it makes nothing red
  ;;
  ;; **Measured, and not quite true here — which is worth more than the tell.**
  ;; Removing `/api/timeline` from `page/answers` DOES redden two tests, because
  ;; they assert real content. What they say is:
  ;;
  ;;     nothing to show — this project's timeline came back empty
  ;;
  ;; That is a sentence about the DATA. The fault is a missing fixture arm, and
  ;; a reader following that message goes looking at the timeline endpoint, the
  ;; response shape, and the screen's empty branch before arriving at the map
  ;; that answers urls. A red whose message points somewhere else is worse than
  ;; the bug, because the debugging budget is spent in the wrong place first.
  ;;
  ;; So this catches it with the right message, one line, naming the arm — and
  ;; it catches the other direction too: an arm canned for a path no page asks.
  ;;
  ;; **The population is RECORDED rather than declared, and that is Move A.** It
  ;; was every route row's `:request` plus every `:webapp/session-loads` entry —
  ;; two declarations to enumerate, and this test caught its own gap the moment
  ;; the project list became the second one. A page ASKS while it renders, so
  ;; there is nothing left to enumerate: render every page and record what it
  ;; asked for. Nothing can grow a second half this time, because the question
  ;; is no longer *what is declared* but *what did it actually fetch*.
  (let [table    (slopp.cljnx/marked-pages)
        concrete (fn [pattern]
                   (->> (str/split pattern #"/")
                        (map (fn [s] (cond (= s "**") "a/b"
                                           (= s "*") "x"
                                           (str/starts-with? s ":") (subs s 1)
                                           :else s)))
                        (str/join "/")))
        ;; the url as the FIXTURE keys it: `page/answers` answers a project's
        ;; endpoint by its own path, so the tenant prefix and any query string
        ;; — both this app's business rather than the endpoint's — come off.
        endpoint-path (fn [u]
                        (-> (str u)
                            (str/replace #"^/api/projects/[^/]+" "/api")
                            (str/replace #"\?.*$" "")))
        asked    (fn [[pattern page-fn]]
                   (let [path (concrete pattern)
                         m    (webapp/match-route table path)
                         seen (atom [])
                         p    {:webapp/state (atom {:path path :params (:params m) :loads {}})
                               :webapp/base  ""
                               :webapp/call  (fn [request _ok _err] (swap! seen conj request) nil)
                               :state {:path path :params (:params m)}
                               :params (:params m)}]
                     (page-fn p)
                     (map (comp endpoint-path :http/url) @seen)))
        declared (sort (distinct (mapcat asked table)))
        ;; **Matched segment-wise, because the two sides speak different halves
        ;; of one address.** `page/answers` is keyed by the endpoint's PATTERN —
        ;; `/api/form/:id` — which is what a reader of the fixture needs to see.
        ;; A recorded url is CONCRETE, because `endpoint/request` resolves the
        ;; whole address now; the old builder left `:webapp/path` holding the
        ;; pattern with the captures beside it, so string equality worked and
        ;; does not any more. Comparing resolved urls directly reports all five
        ;; parameterised endpoints as uncanned, which is what it did.
        matches? (fn [pattern url]
                   (let [ps (str/split pattern #"/")
                         us (str/split (str url) #"/")]
                     (and (= (count ps) (count us))
                          (every? true? (map (fn [p u]
                                               (or (str/starts-with? p ":") (= p u)))
                                             ps us)))))]

    (testing "the population is real — an empty route table would make the
              comparison below pass by having nothing to check"
      (is (seq table))
      (is (seq declared))
      (is (seq page/answers)))

    (testing "every path a page asks for has an answer. Without this, a
              deleted arm renders as an endpoint that returned nothing"
      (doseq [p declared]
        (is (some #(matches? % p) (keys page/answers))
            (str p " is requested by a page and canned by nothing — that page"
                 " renders as though its endpoint answered empty"))))

    (testing "and nothing is canned that no page asks for. A stale arm is the
              cheaper direction and still worth naming: it is a fixture
              describing an endpoint this app stopped using"
      (doseq [k (keys page/answers)]
        (is (some #(matches? k %) declared)
            (str k " is canned and requested by no page"))))))

(def ^:private demo
  "The project these drive tests are inside.

  Every address this app answers carries one — `/p/demo/store` — and none of
  these tests is about tenancy: they are about sections, lenses, and what a
  click reaches. So the project is named once here and [[at]] / [[where]] put
  it on and take it off, which keeps each test saying the screen it means."
  "/p/demo")

(defn- at
  "`path`, addressed inside [[demo]] — the url a reader would actually type."
  [path]
  (str demo path))

(deftest the-whole-app-can-be-driven-headless-and-a-click-changes-the-screen
  ;; The end of a three-day arc: a Playwright harness against an absolute path
  ;; into a global node_modules, then a hand-rolled readout, then the
  ;; framework's. This drives `page/page` — the SAME entry the `screen` tool
  ;; opens — so what a test pins and what I looked at cannot be two apps.
  ;;
  ;; It is also a regression test for a LIVELOCK. `visit!` used to call this
  ;; app's `:navigate` inside `(swap! state nav path)`; `swap!` retries its fn
  ;; on CAS failure, and a real navigate swaps the same atom from inside it —
  ;; 64,754,017 retries in three seconds, no exception, because a hang has no
  ;; message. Nothing here would ever be written on purpose to catch that,
  ;; which is exactly why it is worth keeping.
  ;;
  ;; The structured assertions are written against the readout's SECOND format
  ;; (slopp d24349) and the rewrite is worth a note, because it changed what
  ;; they are worth. The first format marked a heading `# ` and a link
  ;; `text [href]` — an alphabet this app's own content is made of, since it
  ;; renders Clojure. `#"^\s*# code\s*$"` was true because no line happened to
  ;; collide, not because none could. Page text is HTML-escaped now and the
  ;; markers are tags, so `<h1>code</h1>` cannot be produced by anything the
  ;; page says. Same length, same clunk, different guarantee.
  ;;
  ;; `open!` gained its bang in `d24423`, when opening became able to run a
  ;; page's declared `:boot` — an effect on the app's atom. This one line then
  ;; WEDGED the entire store for an hour, which is worth knowing because it
  ;; looks unremarkable: the framework is vendored at the jar's version and
  ;; declared nowhere, so no jar ever had both spellings, and the namespace
  ;; could not be repaired from inside. Every write in the store refused —
  ;; including writes to unrelated namespaces — because the ORACLE boot parked
  ;; the first failure in the session's ready promise and the tool gate rethrew
  ;; it before any edit logic ran. Fixed in `d24453`: both oracle boots
  ;; note-and-continue, and a write into a boot-failed namespace verifies
  ;; against the POST-edit namespace, which is how this very line landed.
  (let [;; **Opened AT a project address**, where it used to open bare. `open!`'s
        ;; second arity is what a hard page load does, and since jar d36193 a
        ;; session `:request` receives the route captures — so the `:modules`
        ;; load filling the Code nav is measured against the project in the boot
        ;; url. Opened bare, there is no project, that load DECLINES, and the
        ;; nav rail assertions below render empty.
        ;;
        ;; Not this test papering over the decline: a bare open is a page load
        ;; at `/`, which is the LANDING, and the landing has no nav rail. What a
        ;; reader who types a project url gets is this.
        ;;
        ;; **The gap it exposes is real.** Reaching a project FROM the landing
        ;; is a client navigation — `project-picker` renders an ordinary in-app
        ;; link — and session loads start once and never re-run, so that path
        ;; still gets an empty rail. `:modules` is project-scoped and a session
        ;; load is session-scoped; only the framework can close that, by
        ;; letting a session load declare the base it is scoped to.
        s (cljnx/open! (cljnx/driver-for (page/page)) (at "/store"))]

    (testing "a visit routes, fetches, derives and renders"
      (cljnx/visit! s (at "/store/gaps"))
      (let [out (cljnx/text s "main")]
        (is (re-find #"(?m)^<h1>code</h1>$" out) "the heading stands alone")
        (is (re-find #"<a href=\"/p/demo/store/module/demo\.web\">demo\.web</a>" out)
            "a gap row carries its address")
        (testing "and the diagram's tint is legible as text — scoped to the svg
                  LINE, because matching the whole page passed once with the
                  layout torn out entirely"
          (let [svg (first (filter #(str/starts-with? (str/trim %) "<svg")
                                   (str/split-lines out)))]
            (is (some? svg))
            (is (re-find #"gap-w4" svg) "the thin module is tinted")
            (is (re-find #"gap-w0" svg) "the documented one explicitly is not")))))

    (testing "following a link descends into a module, through the app's own router"
      (cljnx/visit! s (at "/store/gaps"))
      (cljnx/click! s "demo.core")
      (let [out (cljnx/text s "main")]
        (is (re-find #"(?m)^<h1>demo\.core</h1>$" out))
        (is (re-find #"3 namespaces, 25 forms" out))))

    (testing "a definition is separated from its signature in the MARKUP"
      ;; `[:a … "rate"]` sat directly beside `[:span.src-args "[kg zone]"]`
      ;; and a margin did the rest, so the text of a pane laid out like SOURCE
      ;; read `rate[kg zone]` — one word, and one source never says. The same
      ;; gap reaches a screen reader; the readout is only the instrument that
      ;; showed it.
      ;;
      ;; PROSE, and that is the point rather than an accident: in STRUCTURED
      ;; mode a `</a>` stands between the two words, so the format that is the
      ;; default is the one format in which this defect class is invisible. The
      ;; rule and the mode that can see it belong in the same sentence.
      (cljnx/visit! s (at "/store/ns/demo.core"))
      ;; `:within` scopes to the ROW rather than the pane — the option this
      ;; project asked for and slopp built, and the reason to prefer it is the
      ;; bug already on this store's record: a tint check matched `gap-w` in the
      ;; summary LIST while claiming the DIAGRAM, and stayed green with the
      ;; layout torn out. A whole-pane match is one step from asserting nothing.
      (let [row (cljnx/text s nil {:within "rate" :detail :prose})]
        ;; the row gained its BADGES when the fixture finally reached that path.
        ;; Pinning it whole is still the point, and it now pins three
        ;; separators rather than one — name/signature, signature/badge, and
        ;; badge/badge, all of which were missing and none of which any driven
        ;; screen could show while no fixture form carried a mark.
        (is (= "rate [kg zone] 🌐 ⚡" row)
            "the row is the unit this fact is about, so it can be pinned whole")))

    (testing "and the nav rail separates a module's name from its badge and count"
      ;; Same class, found the same way, three screens later: `module-head`
      ;; stacks four inline siblings with only CSS between them, so the rail
      ;; read `web⚡1 ns` and `core2 ns`. The second is the expensive kind — not
      ;; a missing space but a WRONG WORD, a module that looks like it is
      ;; called `core2`.
      (cljnx/visit! s (at "/store/ns/demo.core"))
      ;; **STRUCTURED, and it is a WORKAROUND — reported as `api-surface.md` #16.**
      ;; On jar `d25758` a prose render emits MARKUP for an `<a>` whose child is
      ;; a block, which is every row of this rail:
      ;;
      ;;   (lines [:a {:href "/x"} [:div "web"]] {:detail :prose})
      ;;   ;=> "<a href=\"/x\">\nweb\n</a>"        ← markup, in prose
      ;;   (lines [:div [:div "web"]] {:detail :prose})  ;=> "web"
      ;;
      ;; The same descent that caused it also puts this row's text on its own
      ;; line in STRUCTURED, so the adjacency — the fact this assertion is
      ;; about — is still visible and still pinned. Asserting the markup the
      ;; prose call now returns would bake the defect in as expected behaviour,
      ;; which this project already has an entry about a test doing.
      ;;
      ;; Revert to `{:detail :prose}` and the one-line form when a jar renders
      ;; `(lines [:a … [:div …]] {:detail :prose})` as text.
      ;; through `prose-of`, which strips the markup `d25758` leaks into prose —
      ;; see its docstring. The expectation is the one this assertion always
      ;; had, deliberately: the workaround filters the defect rather than
      ;; recording it.
      (is (= "web ⚡ 1 ns"
             (cljnx/text s nil {:within "/p/demo/store/ns/demo.web" :detail :prose})))
      ;; whole-pane ON PURPOSE, and the one shape where that is stronger rather
      ;; than weaker: a negative says the wrong word appears NOWHERE. `demo.core`
      ;; carries two anchors to one href, so :within refuses it — correctly,
      ;; since both are things a reader can click.
      (let [pane (cljnx/text s "nav/local" {:detail :prose})]
        (is (not (re-find #"core2" pane)) "a module that looks like it is named core2")
        (is (re-find #"core 2 ns" pane)))

      (testing "including the expanded namespace ROWS, which only an external
                module renders — demo.core is :pure and carries no mark, so
                every assertion above is blind to this instance"
        (cljnx/visit! s (at "/store/ns/demo.web"))
        (is (= "filter namespaces…\ndemo\nweb ⚡ 1 ns\nweb ⚡\ncore 2 ns\nutil 1 ns foundation\norders 🔁 1 ns\nbilling 🔁 1 ns"
               (cljnx/text s "nav/local" {:detail :prose}))
            "the whole rail, because at eight short lines the pane IS the unit.
             It carries the band module, which the rail was already marking with
             nothing reading it — and now BOTH tier marks: 🔁 is `internal`, and
             it had never appeared on a driven screen at all, because until the
             cycle fixture arrived no module in any canned response was internal.
             `tier-badges` has three entries and two of them were reachable.")))

    (testing "the project switcher is on a driven screen at all, which needed :boot"
      ;; It was on NO headless screen: `client.app/main` loads the project list
      ;; at page load, and the driver runs routes and handlers but never an
      ;; entry point. So the one control carrying a `— not running` state had a
      ;; unit call for a test and no screen anybody could look at, which is the
      ;; exact gap `page` exists to close. `:boot` (slopp d24423) is the
      ;; page-declared entry-point transform `open!` runs once.
      ;;
      ;; A stopped project is in the fixture ON PURPOSE. Absent-versus-present
      ;; is what `project-switcher`'s docstring argues about — a stopped project
      ;; stays listed and labelled, because disappearing from a control
      ;; mid-session is how a reader concludes they imagined it — and a fixture
      ;; where everything is healthy asserts that the happy path renders and
      ;; nothing about the claim.
      ;;
      ;; STRUCTURED, and deliberately the opposite choice from the row
      ;; assertions above — because "use prose for text facts" is the wrong
      ;; lesson to carry away.
      ;;
      ;; **The control CHANGED**, and the comment is updated rather than left
      ;; to rot. It was a `<select>` whose options carried `/p/<slug>/` as
      ;; their `:value`, and nothing anywhere read it — ported from slopp's
      ;; store on 2026-07-28 and decorative until 2026-08-28, a month of
      ;; looking like a control and doing nothing. A `<select>` cannot navigate
      ;; without script and this app has no action that navigates, so the
      ;; entries are LINKS now.
      ;;
      ;; The old comment recorded a prose-vs-structured lesson about `<select>`
      ;; specifically; the general half outlived its subject and is asserted
      ;; twice below rather than argued once:
      ;;
      ;;     structured → the hrefs, which are what make it a CONTROL
      ;;     prose      → the separator, which is what makes it READABLE
      ;;
      ;; A control's CONTENTS are a structured fact, adjacency is a prose fact,
      ;; and neither mode is the default answer. The prose half earned its
      ;; place immediately: the first version rendered `slopp2slopp-ui`.
      (cljnx/visit! s (at "/store"))
      (let [sw (cljnx/text s "nav/switcher")]
        (is (re-find #"<option value=\"slopp2\">slopp2</option>" sw))
        (is (not (re-find #"disabled" sw))
            "every listed project is open — the daemon lists a project exactly while
             something is attached, so nothing is disabled or labelled gone")
        ;; **the handlers are the assertion that would have caught the bug.**
        ;; For a month this rendered the options above and carried no handler
        ;; at all; every assertion anyone wrote was about the OPTIONS, and all
        ;; of them passed. `slopp:on` is the reader naming what a control
        ;; DOES, which is the fact nobody was asserting.
        (is (re-find #"slopp:on=\"change :project/goto\"" sw))
        (is (not (re-find #"button" sw))
            "and NO second control — the `go` button was a workaround for
             `url-for` not seeing the dispatched value, and it was deleted the
             day slopp widened the signature"))
    (is (= "demo" (cljnx/text s "nav/switcher" {:detail :prose}))
        "prose shows the CHOSEN option and nothing else — which is what a
         browser shows and a screen reader announces, and is exactly why the
         option list above is asserted structurally instead"))

    (testing "and a DATA handler changes what a reader sees — the half that was
              unreachable while every listener was delegated to the document"
      (cljnx/visit! s (at "/store/ns/demo.core"))
      (let [collapsed (cljnx/text s "main" {:detail :prose})]
        (cljnx/visit! s (at "/store/ns/demo.core"))
        (cljnx/click! s "expand all")
        (let [expanded (cljnx/text s "main" {:detail :prose})]
          (is (re-find #"Rate for a weight in a zone\." collapsed))
          (is (not (re-find #"Bands come from the tariff table" collapsed))
              "collapsed shows the first sentence only")
          (is (re-find #"Bands come from the tariff table" expanded)
              "the click dispatched :docs/all and the whole comment is there"))))))

(deftest an-endpoint-can-be-called-ad-hoc-and-the-request-is-visible
  ;; The button a headless driver can press, which is the whole reason the call
  ;; goes through a plug-in rather than through `js/fetch` in a handler. The
  ;; canned performer ECHOES the request, so what this asserts is the thing a
  ;; test can honestly know: which call the filled-in form would have made.
  ;; Making the request is the one part no driver can do.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]

    (testing "a path parameter gets a box, and the box says which half of the
              request it lands in"
      (cljnx/visit! s (at "/rest/paths/get/api/module/:m"))
      (is (re-find #"m · path · string" (cljnx/text s "main" {:detail :prose}))))

    (testing "filling it and pressing execute sends the endpoint's own path
              with the typed value in place"
      ;; `param-m`, not `m`: a bare `name="m"` would answer to the same word as
      ;; anything else on the page that happens to use it, and the header's
      ;; search box already proved that is not hypothetical.
      (cljnx/fill! s "param-m" "slopp.ops")
      (cljnx/click! s "execute")
      (let [out (cljnx/text s "main" {:detail :prose})]
        (is (re-find #"the answer" out))
        (is ;; MEASURED from the project: the request carries the project's
            ;; `:webapp/base`, the performer records it once ADDRESSED, and so
            ;; the panel shows the url the daemon actually answers — the mount
            ;; joined to the endpoint's path. Built from the raw row this read
            ;; `/api/module/slopp.ops`, which the daemon does not serve. The
            ;; map is no longer all-`:http`, so it prints plain; read by the
            ;; tail of the key.
            (re-find #"url \"/api/projects/demo/module/slopp\.ops\"" out))
        (is (re-find #"webapp/base \"/api/projects/demo\"" out)
            "the base is on screen too — it is half of where the call goes")
        (is (re-find #"method :get" out))))

    (testing "what was SENT is on screen beside what came back. A response with
              no request beside it cannot be attributed — three attempts and
              one body is the same defect as a count with no population"
      (is (re-find #"sent: " (cljnx/text s "main" {:detail :prose}))))

    (testing "an optional query parameter left BLANK is not sent. `?q=` asks
              for the empty query rather than for no query, and this app has a
              bug on record from an endpoint reading a parameter it was never
              meant to get"
      (cljnx/visit! s (at "/rest/paths/get/api/search"))
      (cljnx/click! s "execute")
      (is ;; nothing typed, so no query string is appended at all — where this
        ;; used to read an empty `:query {}` map beside the path.
        ;; nothing typed, so NO query string is appended at all — where this
        ;; used to read an empty `:query {}` map beside the path. The closing
        ;; quote is what makes it exact: without it this also matches the
        ;; `?q=rate` case below and the two assertions stop being different.
        (re-find #"url \"/api/projects/demo/search\"" (cljnx/text s "main" {:detail :prose}))))

    (testing "and filled, it travels"
      (cljnx/fill! s "param-q" "rate")
      (cljnx/click! s "execute")
      (is (re-find #"url \"/api/projects/demo/search\?q=rate\"" (cljnx/text s "main" {:detail :prose}))))

    (testing "leaving the endpoint clears the form — its fields are THAT
              endpoint's parameters, and carried across they would offer the
              next endpoint a value it has no field for"
      (cljnx/visit! s "/rest/paths/get/api/module/:m")
      (let [out (cljnx/text s "main" {:detail :prose})]
        (is (not (re-find #"the answer" out)))
        (is (not (re-find #"rate" out)))))

    (testing "a method with a BODY is offered now, one box per declared field —
              it used to be declined in words, which was right while nothing
              could send one"
      (cljnx/visit! s (at "/rest/paths/post/api/register"))
      (let [out (cljnx/text s "main" {:detail :prose})]
        (is (not (re-find #"not wired yet" out)))
        (is (re-find #"(?m)^name · body · string$" out))
        (is (re-find #"(?m)^pid · body · int or null · optional$" out))))

    (testing "and being effectful, it ARMS rather than fires — the one place
              the declaration changes an outcome instead of describing one"
      (cljnx/click! s "execute…")
      (let [out (cljnx/text s "main" {:detail :prose})]
        (is (re-find #"yes — call it" out))
        (is (not (re-find #"the answer" out))
            "the first press performed nothing")))

    (testing "the second press sends it, with the body TYPED — `pid` is
              declared `int`, and JSON carries a number rather than the string
              a text box produced"
      (cljnx/fill! s "param-name" "slopp2")
      (cljnx/fill! s "param-pid" "4131")
      (cljnx/click! s "yes — call it")
      (let [out (cljnx/text s "main" {:detail :prose})]
        (is (re-find #"body \{:name \"slopp2\", :pid 4131\}" out)
            "4131 unquoted — a number, not the text that was typed")))))

(deftest a-documented-field-says-what-it-is-for-on-both-screens
  ;; The complaint this answers: the parameters had no prose. Malli entry
  ;; properties travel as VALUES, so a doc written on a field arrives over the
  ;; wire unchanged — measured against `contract-document`, which needs no
  ;; change for any of it. Nothing declared one, and `schema/rows` dropped them.
  ;;
  ;; PROSE, and deliberately: the defect this pins was a run-together —
  ;; `q · query · string · optionalwhat to search for` — from a paragraph
  ;; inside a `<label>`, which the readout flattens inline with no separator.
  ;; Structured mode puts a closing tag between the two, so the one format in
  ;; which that is invisible is the default one.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]
    ;; the request and response are TABS now, addressed by `?part=`. Driving to
    ;; each is also the only end-to-end proof that the parameter reaches the
    ;; screen: the unit test hands `:part` straight to `endpoint-main`, so it
    ;; passed while `endpoint-screen` was still dropping it from the address.
    (cljnx/visit! s (at "/rest/paths/get/api/search?part=request"))
    (let [out (cljnx/text s "main" {:detail :prose})]

      (testing "the schema list carries each field's prose on its own line,
                under the row it belongs to"
        (is (re-find #"(?m)^q · string · optional$" out))
        (is (re-find #"(?m)^what to search for; blank matches nothing$" out)))

      (testing "`:description` reads identically to `:doc` — an imported schema
                uses malli's JSON-Schema spelling and a reader should not be
                able to tell which vocabulary its author wrote in"
        (is (re-find #"(?m)^how many hits to return, at most$" out)))

      (testing "a RESPONSE field carries it too. `total` counts hits before the
                limit is applied, which is the kind of thing no type can say
                and every caller has to know"
        ;; the other TAB, reached by pressing it rather than by typing its url —
        ;; so this asserts the bar works as well as what is behind it.
        (cljnx/click! s "response")
        (is (re-find #"hits BEFORE the limit is applied"
                     (cljnx/text s "main" {:detail :prose})))
        (cljnx/click! s "request"))

      (testing "and the box that asks for a value says what the value is FOR,
                on its own line rather than run into the label"
        (is (re-find #"(?m)^q · query · string · optional$" out))
        (is (not (re-find #"optionalwhat to search for" out))))

      (testing "a field with no prose renders none — the fixture is half
                documented on purpose, which is what a real store looks like
                while anyone is still writing them"
        (cljnx/visit! s (at "/rest/paths/get/api/module/:m"))
        (is (re-find #"(?m)^module · string$"
                     (cljnx/text s "main" {:detail :prose})))))))

(deftest routing-a-path-through-another-rung-actually-changes-the-path
  ;; `/store/form/:id/through/:through` is a whole route arity this app emits,
  ;; and it rendered BYTE-IDENTICALLY to `/store/form/:id`. Not because it was
  ;; broken — `app-view` reads the param and `spine` takes the `prefer` set —
  ;; but because the fixture's form had ONE callee, and a preference among one
  ;; option is inert. So the route was unverifiable from any screen.
  ;;
  ;; Found by driving both paths and diffing them, which no assertion in this
  ;; file did: every test here visits one path and reads it.
  ;;
  ;; A second callee is the whole fixture change, and it is copied rather than
  ;; invented — `:callees` is `[:sequential [:map …]]` and a form with two
  ;; callees is the ordinary case, not a corner. It reaches a second branch for
  ;; free: with one option every rung reported `1 of 1`, so the alternatives
  ;; count had never rendered either.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]
    (cljnx/visit! s (at "/store/form/f1"))
    (let [default (cljnx/text s "main" {:detail :prose})]
      (cljnx/visit! s (at "/store/form/f1/through/f3"))
      (let [routed (cljnx/text s "main" {:detail :prose})]
        (is (not= default routed)
            "the address carries the choice, so the two paths cannot be the same page")
        (is (re-find #"round-up" routed)
            "and the rung the address names is the one on the path")))))

(deftest a-cycle-is-a-finding-and-it-sits-above-the-picture-not-inside-it
  ;; The branch `code-index-main` calls "the most useful sentence on a tangled
  ;; store", never rendered: every `:cycles` in this store was `[]` until now.
  ;;
  ;; The fixture's shape is the real producer's — `slopp.store/module-layers`,
  ;; run on three manifests and sent over rather than described. A cycle does
  ;; NOT break `:layers` or make it absent; it CONDENSES into one entry, every
  ;; member at the same depth, and the graph above and below lays out normally.
  ;;
  ;; `module_dep` cycle-checks every add, so this store cannot grow one and the
  ;; branch was unreachable from any response here. It arrives by ADOPTION —
  ;; and NOT as a corner case: a module is the first two segments, so a
  ;; module cycle needs no namespace cycle in it, and a utility whose
  ;; implementation reaches back into the app using it is an ordinary mature
  ;; codebase. This fixture models the common shape for an imported store.
  ;;
  ;; Whole-pane `=`, because at six lines the pane IS the unit and the POSITION
  ;; is half the claim — the finding above the diagram rather than left to be
  ;; spotted in it. `:within` cannot scope this one: it addresses what `click`
  ;; addresses, and a finding is read rather than pressed.
  ;;
  ;; That the ring closes back on `demo.billing` is what makes it read as a
  ;; cycle rather than a list of two modules. Which modules are NOT in it is
  ;; asserted next door, on the table, because that is the pane where the
  ;; innocent module is visibly stacked between the two entangled ones.
  ;;
  ;; ONE GRAIN ONLY, and NOT because the other is this one a rung down — that
  ;; framing was wrong and is the reason to say so here. `/api/module/:m`'s
  ;; `:cycles` are cycles among a module's own NAMESPACES, and both ends of such
  ;; a cycle sit inside ONE module, so no cross-module edge exists and the store
  ;; index reports a perfectly clean store. The two findings are ORTHOGONAL:
  ;; this assertion passing says nothing whatever about that one, and a reader
  ;; who only ever looked here would see nothing wrong.
  ;;
  ;; Both are asserted below, and the module one took three attempts to get
  ;; honest: invented, retracted on a false claim, then transcribed from
  ;; `module-detail`'s own return. `page/responses` carries that history.
  ;;
  ;; What neither driven assertion can reach is a cycle beside an EMPTY
  ;; boundary, since this fixture keeps both halves populated for a different
  ;; branch — `views-test` pins that combination on the producer's output.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]
    (cljnx/visit! s (at "/store"))
    (is (= (str "diagram table gaps\n"
                "code\n"
                "5 modules, 6 namespaces — 1 of them foundation\n"
                "1 dependency cycle\n"
                "demo.billing → demo.orders → demo.billing\n"
                "svg module-graph")
           (cljnx/text s "main" {:detail :prose})))

    ;; The diagram draws BOTH directions of the knot — three module-edges here
    ;; are `web → core` plus the two opposed ones, the other three being dropped
    ;; into the foundation. Two opposed lines between one pair of boxes are not
    ;; distinguishable from one line, which is an argument FOR the sentence
    ;; above rather than a defect in the picture: geometry is where a cycle
    ;; hides. Censused by class, because that is the only thing text can see.
    (is (re-find #"3 module-edge" (cljnx/text s "main")))

    ;; The ORTHOGONAL finding, driven. A different sentence in a different
    ;; renderer — `module-main` adds "inside this module" — and a different
    ;; fact: nothing above could tell you this module is knotted, because both
    ;; ends of the knot are inside it and the index sees no edge to report.
    ;;
    ;; Two lines rather than the pane, because the adjacency IS the claim: a
    ;; finding whose ring drifted away from its heading would still satisfy two
    ;; separate `re-find`s.
    (cljnx/visit! s (at "/store/module/demo.core"))
    (is (re-find #"1 dependency cycle inside this module\ndemo\.core\.calc → demo\.core\.tariff → demo\.core\.calc"
                 (cljnx/text s "main" {:detail :prose})))))

(deftest a-commit-point-recorded-red-does-not-look-like-a-green-one
  ;; `:status` is a declared field on every commit-point — "the verdict the
  ;; commit-point was recorded under, mirroring its done-point" — and the timeline
  ;; rendered description and timestamp and nothing else. So a commit-point
  ;; recorded RED was indistinguishable from a green one on the only screen
  ;; that lists them.
  ;;
  ;; `commit_point {force true}` records red honestly, which is what makes this
  ;; reachable rather than theoretical. slopp's own doc says a red one is
  ;; expressible and their store has never produced one — so the VALUE is
  ;; copied from the contract's own vocabulary, not a shape I invented. That
  ;; distinction is the one this project got wrong once and is being careful
  ;; about since.
  ;;
  ;; MARKED ONLY WHERE IT NARROWS, the same rule as `:auth :public` on the API
  ;; index and the effect mark on a read-only row: green is the overwhelming
  ;; case, so a badge on every row is a badge nobody reads. Absence means green
  ;; and the mark means look.
  ;;
  ;; The heading is TWO WORDS. `:commit-points` is the wire key; "commit
  ;; points" is the noun slopp's own document uses, and a hyphenated key
  ;; rendered as a heading is an implementation detail worn on the outside.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]
    (cljnx/visit! s (at "/"))
    (is (= (str "review\n"
                "in flight\n"
                "3 forms in 2 namespaces since d24946\n"
                "sharpen hello\n"
                "rate needs a zone\n"
                "commit points\n"
                "The API section navigates like an API browser 2026-08-08 02:09\n"
                "A third section: the API surface a project publishes 2026-08-08 01:47 · recorded red\n"
                "the first commit-point, which has nothing behind it 2026-07-28 00:47")
           (cljnx/text s "main" {:detail :prose})))))

(deftest the-review-arc-says-whether-the-work-went-red
  ;; `:arc` is a DECLARED top-level field on `/api/change` and it was rendered
  ;; by nothing — `change-main` did not even destructure it. The fixture has
  ;; carried a red delta (`d3326`, two failures) since the day it was written,
  ;; and no screen has ever shown it. Found by driving the screen and reading
  ;; it, not by an assertion: every assertion here was about the diff.
  ;;
  ;; What makes it worth screen space rather than a number in a tooltip is the
  ;; field's own documentation, which is an argument rather than a description:
  ;;
  ;;   "Zero failures throughout means the work never went red — which for a
  ;;    range that ADDED assertions is itself a finding, since a test nobody
  ;;    watched fail is a test nobody has evidence for"
  ;;
  ;; **And the DENOMINATOR, added 2026-09-05.** `1 red` says nothing about
  ;; scale: two failures out of twelve tests and two out of two thousand are
  ;; the same number and different news. slopp shipped `:tests`, `:pass` and
  ;; `:ms` on each arc entry after this project made that argument, so the
  ;; count now arrives with what it is a count OF.
  ;;
  ;; PROSE and a whole-line match: the delta id has to sit next to the count,
  ;; because "1 red" somewhere on the page and "d3326" somewhere else is two
  ;; facts a reader has to join up themselves.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]
    (cljnx/visit! s (at "/change/d3300..d3457"))
    (is (re-find #"3 verifications in 420ms, 1 red — d3326 \(2 of 41\)"
                 (cljnx/text s "main" {:detail :prose})))))

(deftest an-endpoint-with-no-docstring-marks-the-cell-rather-than-leaving-it-blank
  ;; `:doc` on an endpoint is its handler's docstring, so nil is reachable — no
  ;; rule requires one, and `web-undocumented-contract` is about FIELD docs.
  ;; `summary-of` returns nil for it and the cell rendered nothing at all.
  ;;
  ;; An empty cell is ambiguous in the one direction that costs: it reads as
  ;; "this column is broken" exactly as much as "this endpoint has no
  ;; description", and the reader cannot tell which. `module-table` already
  ;; settled the convention next door — a module with no dependencies prints
  ;; `—` rather than nothing — so this is consistency with a reason rather than
  ;; consistency for its own sake.
  ;;
  ;; The mark goes ONLY where the cell would otherwise be empty. A row that has
  ;; no description but does carry the effect mark is not an empty cell, and
  ;; `— · changes something` would be a placeholder standing next to content it
  ;; is denying the existence of.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]
    (cljnx/visit! s (at "/rest/paths"))
    (let [out (cljnx/text s "main" {:detail :prose})]
      ;; `/api/search` rather than `/api/module/:m`, which gained its real
      ;; docstring when the fixture stopped leaving three of four blank. Search
      ;; is now the ONE endpoint here with no doc, no effect mark and no
      ;; narrowing auth — which is exactly the population this placeholder is
      ;; for, and documenting it too would make the branch unreachable.
      (is (re-find #"GET /api/search — search" out)
          "no docstring, nothing else in the cell: marked")
      ;; **The claim is the ABSENCE of the placeholder**, which is what the
      ;; comment above argues. This used to assert
      ;; `POST /api/register · changes something` — and that leading `· ` was
      ;; a separate defect the expected string had frozen in place: with no
      ;; summary on its left it opened the cell with a separator and nothing
      ;; before it. Asserting the whole line pinned the bug alongside the
      ;; claim, so the claim is now made directly and the spacing is
      ;; `views-test/an-index-cell-never-opens-with-a-dangling-separator`'s
      ;; job.
      (is (re-find #"POST /api/register changes something" out)
          "no docstring but the effect mark is there, so no placeholder")
      (is (not (re-find #"/api/register — " out))
          "and specifically no dash: a placeholder beside the effect mark would
           deny content that is right there"))))

(deftest a-rail-row-separates-its-name-from-its-module-and-its-via
  ;; Found by opening the rail after wiring `cg/ego` into it, not by an
  ;; assertion: the row read `quotedemo.orderstatic`. That exact string is
  ;; already in AGENTS.md as a defect this project has shipped once — "the
  ;; module tag and the via badge ran together as demo.orderstatic, a margin
  ;; separating them in a browser and nowhere else".
  ;;
  ;; PROSE, per the rule: adjacency is a prose fact. In structured mode a
  ;; closing tag stands between the words, so the default mode is the one mode
  ;; this defect class is invisible in.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]
    (cljnx/visit! s (at "/store/form/f1"))
    (let [rail (cljnx/text s "nav/detail" {:detail :prose})]
      (testing "the name, the module and the via are three things"
        (is (re-find #"quote\s+demo\.order\s+static" rail)
            "a caller row that reads `quotedemo.orderstatic` is not a missing
             space, it is a wrong word"))
      (testing "and the callee row the same way"
        (is (re-find #"band-for\s+demo\.core" rail))))))

(defn- where
  "Where the driver is, with [[demo]] taken off — the path an assertion means."
  [s]
  (let [p (str (:path @s))]
    (if (str/starts-with? p demo) (subs p (count demo)) p)))

(deftest the-api-section-is-reachable-and-says-how-to-call-an-endpoint
  ;; The section exists because a project's HTTP surface was legible only by
  ;; opening its store, which is the one thing this project may not do. Driven
  ;; rather than unit-asserted for the usual reason: the views test beside this
  ;; brings its own document, and a test that brings its own data cannot notice
  ;; that the shared entry has none.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]

    (testing "the global bar reaches the section directly — ONE level, because
              every section turned out to hold exactly one page"
      (cljnx/visit! s (at "/store"))
      ;; **REST**, not API. The section is named for slopp's capability, which
      ;; is what the endpoint publishing it is called and what a reader has to
      ;; type to talk to anyone about it.
      (cljnx/click! s "REST")
      (is (= "/rest/paths" (where s)))
      (is (re-find #"(?m)^<h1>REST paths</h1>$" (cljnx/text s "main"))
          "the heading names the CAPABILITY and the page — three sections each
           list something, so a bare `Paths` would repeat the tab above it and
           `API` was this app's coinage for one of the three")
      ;; the second-level bar is GONE. It existed to make a section a
      ;; capability rather than a screen, and that argument was right about
      ;; the SECTIONS and wrong about the bar: with one page in each, it drew
      ;; `Paths` under `REST` and `Settings` under `Config` — a full row of
      ;; chrome restating the tab a reader had just clicked. The heading
      ;; already names the page.
      (is (thrown? Exception (cljnx/text s "nav/pages"))
          "no second-level page bar — the region is absent, not empty"))

    (testing "the index names every endpoint and expands NONE of them. That is
              the whole editorial rule of the page: nine endpoints in full was
              already too long and a real surface has fifty"
      (let [out (cljnx/text s "main" {:detail :prose})]
        (is (re-find #"GET /api/modules" out))
        (is (re-find #"POST /api/register" out))
        (is (not (re-find #"list of object" out))
            "no field lists — the response TYPE is a phrase, its fields are not")))

    (testing "the rail crosses from one endpoint to another WITHOUT going back
              through the index. That is what the rail is for, and it is the
              part an expand-in-place row cannot do at all"
      (cljnx/visit! s (at "/rest/paths/get/api/search"))
      ;; addressed by :href, and it is unambiguous only HERE — on the index
      ;; both the table row and the rail answer to it, and the driver refuses
      ;; to guess between two real controls. Which is the right refusal: they
      ;; are both things a reader can click.
      (cljnx/click! s (at "/rest/paths/get/api/modules"))
      (is (= "/rest/paths/get/api/modules" (where s)))
      (is (re-find #"GET /api/modules" (cljnx/text s "main"))))

    (testing "one endpoint's page carries the detail the index drops, with the
              response's NESTING as real markup — a reader has to be able to
              tell a top-level key from one inside another object"
      ;; STRUCTURED on purpose, the opposite call from the row assertions in
      ;; this file: prose flattens the lists, so `gaps` and the fields inside
      ;; it come out at one left margin and the fact this pane exists to carry
      ;; is the one prose cannot show.
      (is (re-find #"<ul[^>]*>\s*<li>\s*modules · list of object\s*<ul"
                   (cljnx/text s "main"))))

    (testing "a path parameter is named even where the request schema is nil —
              the document is inconsistent about them and the screen is not"
      (cljnx/visit! s (at "/rest/paths/get/api/module/:m"))
      (is (re-find #"(?m)^path parameters: m$"
                   (cljnx/text s "main" {:detail :prose}))))

    (testing "a GET says `no parameters`, never `no request body` — a GET has
              no body to be missing, and this screen's only job is saying how
              to call the thing"
      ;; on the REQUEST tab, which is where a request's own wording lives. The
      ;; page defaults to `response` because a GET's request tab is empty on
      ;; most of this surface — which is the very fact this block asserts.
      (cljnx/click! s "request")
      (let [out (cljnx/text s "main" {:detail :prose})]
        (is (re-find #"no parameters" out))
        (is (not (re-find #"no request body" out)))))

    (testing "an unknown name says so rather than rendering an empty page"
      (cljnx/visit! s (at "/rest/paths/get/api/nope"))
      (is (re-find #"no endpoint" (cljnx/text s "main" {:detail :prose}))))

    (testing "an alternation states how MANY shapes it has and then opens them,
              with NO empty list under it — it says what it knows and does not
              render an absence as an answer"
      ;; This block used to assert `either object or object` and its comment
      ;; called register!'s response "a shape the flattener gives up on". Both
      ;; halves went stale and the assertion kept passing, which is why it
      ;; survived: `alts-of` opens an `:or` into numbered branches now, so the
      ;; flattener does NOT give up — the branches below carry the fields.
      ;;
      ;; And the type line was a sentence that said nothing twice. `type-label`
      ;; joined every branch's label, so two map branches read `either object
      ;; or object` — true, useless, and indistinguishable from a bug. Where
      ;; the labels DIFFER it is still the concrete phrase (`either string or
      ;; int`); where they are all the same the count is the only new fact.
      (cljnx/visit! s (at "/rest/paths/post/api/register"))
      (let [out (cljnx/text s "main")]
        (is (re-find #"one of 2 shapes" out))
        (is (not (re-find #"either object or object" out)))
        (is (re-find #"option 1 · object" out)
            "and the branches are opened, which is what makes the count useful")
        (is (not (re-find #"slopp:count=\"0\"" out)))))))

(deftest an-endpoint-links-its-handler-s-body-and-that-page-widens
  ;; Driven rather than asserted on the href: an href assertion proves a link
  ;; exists, not that pressing it arrives somewhere — and what it arrives at is
  ;; the point here.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]

    (testing "the page names the handler and links it"
      (cljnx/visit! s (at "/rest/paths/get/api/modules"))
      (is (re-find #"demo\.api\.endpoints/modules"
                   (cljnx/text s "main" {:detail :prose}))))

    (testing "and the link lands on that FORM'S BODY — its own name as the
              heading and one `(defn …)` under it, not the namespace"
      (cljnx/click! s "read its source")
      (is (= "/store/source/demo.api.endpoints/modules" (where s)))
      (let [out (cljnx/text s "main")]
        (is (re-find #"(?m)^<h1>rate</h1>$" out)
            "headed by the form's own name")
        (is (re-find #"defn rate" out))))

    (testing "from there wider is one click SIDEWAYS — the form's own page, with
              the callers and callees the body alone cannot show. This is the
              hop `:form-id` on `/api/source` bought, and it lands on real data
              rather than on a nil"
      (cljnx/click! s "its callers and callees")
      (is (= "/store/form/f1" (where s)))
      (let [out (cljnx/text s "main" {:detail :prose})]
        (is (re-find #"the path through here" out))
        (is (re-find #"3 covering tests" out))))

    (testing "and wider UP is the breadcrumb, which needs no store id at all —
              the namespace is where a reader goes when one form was not
              enough. Both widenings exist; neither is the default"
      (cljnx/visit! s (at "/store/source/demo.api.endpoints/modules"))
      (cljnx/click! s "demo.core")
      (is (= "/store/ns/demo.core" (where s))))

    (testing "a document that does not publish its handler offers NO link —
              not a search, not the namespace, not a guess. It says so, which
              is a fact about the project and is actionable in a way a search
              box is not"
      ;; **Called DIRECTLY, where it used to be driven through
      ;; `/endpoints/get/api/search`.** Under contract version 2 `:handler` is
      ;; a REQUIRED `:symbol`, so no document a slopp server publishes can omit
      ;; it — and `page/contract-document` no longer does either, because a
      ;; fixture modelling an older jar is a shape nobody sends and this
      ;; project codes no migrations.
      ;;
      ;; The branch is kept rather than deleted: `client.check-map` REPORTS
      ;; drift, it does not stop a document reaching the view, so a server that
      ;; misbehaves still arrives here and saying so is better than rendering a
      ;; broken link. But it is no longer reachable from a truthful document,
      ;; and asserting it through one would mean keeping a lie in the fixture
      ;; every other screen test also reads.
      (let [no-handler {:method :get :path "/api/x" :name 'x
                        :media-type "application/json" :auth :public
                        :request nil :response :string}
            out (str/join " " (filter string?
                                      (tree-seq coll? seq
                                                (views/endpoint-main
                                                  [no-handler]
                                                  (views/endpoint-params no-handler)
                                                  nil))))]
        (is (re-find #"GET /api/x" out)
            "it renders — otherwise the assertions below are the not-found page")
        (is (re-find #"does not publish which form handles it" out))
        (is (not (re-find #"search this store" out)))))))

(deftest the-store-table-reads-as-rows-and-each-row-is-a-way-in
  ;; What the store index is, driven: `code`, one census sentence and an svg
  ;; summarised by class. No module named, no number shown. That is the whole
  ;; page to this instrument, and every number it omits arrived on the wire.
  ;;
  ;; Driven rather than unit-asserted because the unit test next door supplies
  ;; its own data, and a test that brings its own data cannot notice that the
  ;; shared entry has none. This one goes through `page/page`.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]

    (testing "the switcher reaches the table and back — a lens with no link is
              a url you have to already know"
      (cljnx/visit! s (at "/store"))
      (is (not (re-find #"demo\.(core|util|web)" (cljnx/text s "main")))
          "the PICTURE names no module: the contrast with the table, measured on
           the page rather than claimed in a docstring. It used to exclude
           `demo.` outright and could not survive a finding — the two names that
           now appear are the cycle's, printed by the sentence ABOVE the diagram
           rather than by the diagram, where a module is a shape and not a word.")
      (cljnx/click! s "table")
      (is (= "/store/table" (where s)))
      (cljnx/click! s "diagram")
      (is (= "/store" (where s))))

    (testing "the rows say the magnitudes, in the order the picture stacks"
      ;; PROSE and whole-pane, and both halves are deliberate. Prose, because
      ;; the two facts most likely to be wrong here are adjacencies — the band
      ;; marker sits beside the module name and the deps are separated by a
      ;; string this markup supplies — and structured mode puts a closing tag
      ;; between them, which is where this project has already lost three bugs.
      ;; Whole-pane, because at three rows the table IS the unit and `:within`
      ;; refuses `demo.core` anyway: it names a row AND appears in two others'
      ;; deps, which is a real ambiguity rather than a limitation.
      (cljnx/visit! s (at "/store/table"))
      ;; The three middle rows are the second half of the cycle finding's claim,
      ;; and the reason they are asserted HERE rather than beside it: layer 0
      ;; holds `demo.billing`, `demo.core` and `demo.orders`, stacked
      ;; adjacently, and NOTHING in a row says which two are knotted. The
      ;; innocent module is sandwiched between the guilty pair. Layer 0 means
      ;; "depends on nothing below" and a condensed cycle qualifies, so a
      ;; reader inferring entanglement from the stacking would convict
      ;; `demo.core` — `:cycles` is the only thing that discriminates, which is
      ;; why it rides alongside instead of marking the layer row.
      (is (= (str "diagram table gaps\n"
                  "code\n"
                  "5 modules, 6 namespaces — 1 of them foundation\n"
                  "1 dependency cycle\n"
                  "demo.billing → demo.orders → demo.billing\n"
                  "module namespaces forms tests tier depends on\n"
                  "demo.util foundation 1 6 3 pure —\n"
                  "demo.billing 1 9 1 internal demo.orders\n"
                  "demo.core 2 20 5 pure demo.util\n"
                  "demo.orders 1 14 4 internal demo.billing, demo.util\n"
                  "demo.web 1 12 2 external demo.core, demo.util")
             (cljnx/text s "main" {:detail :prose}))))

    (testing "the picture is REPLACED, not captioned. A lens is an alternative
              rendering of one subject; above a table it would be an addition
              to the default view instead of a way out of it"
      (cljnx/visit! s (at "/store/table"))
      (is (not (re-find #"<svg" (cljnx/text s "main"))))
      (cljnx/visit! s (at "/store"))
      (is (re-find #"<svg" (cljnx/text s "main"))))

    (testing "and a module name in a row descends, so the table is a way in"
      (cljnx/visit! s (at "/store/table"))
      (cljnx/click! s (at "/store/module/demo.web"))
      (is (= "/store/module/demo.web" (where s))))))

(deftest the-search-door-can-be-typed-into-and-pressed
  ;; Could not be written until slopp d24792. The driver's model of "control"
  ;; was Replicant's — something carrying an `:on` — rather than HTML's, so a
  ;; plain GET form was unfillable AND unclickable, and the most important
  ;; control in this app was the one thing no headless test could operate
  ;; (friction #30). It was pinned by asserting the MARKUP that would have
  ;; produced the press. That test is kept, because this one makes a different
  ;; claim; but only this one presses the button.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]

    (testing "typing into the box and pressing search lands on the results"
      (cljnx/visit! s (at "/store"))
      (cljnx/fill! s "search this store" "kg zone")
      (cljnx/click! s "search")
      (is (= "/store/search?q=kg+zone" (where s))
          "the form serialised its named field and encoded the space")
      (is (re-find #"search: kg zone" (cljnx/text s "main" {:detail :prose}))
          "and the whole chain closed — form → url → router → decode → heading"))

    (testing "a value needing REAL encoding survives, which is the case every
              cheaper assertion passes on"
      ;; An unencoded `&` does not corrupt the query, it SPLITS it: `q=a&b`
      ;; arrives as two parameters and the app searches for `a`. The screen
      ;; still renders, the heading still says something, and nothing is red —
      ;; the reader simply gets results for a query they did not type.
      (cljnx/visit! s (at "/store"))
      (cljnx/fill! s "search this store" "a&b")
      (cljnx/click! s "search")
      (is (= "/store/search?q=a%26b" (where s)))
      (is (re-find #"search: a&b" (cljnx/text s "main" {:detail :prose}))
          "the ampersand is one query, not the boundary between two"))))

(deftest the-review-section-can-be-driven-at-last
  ;; `screen {steps [{visit "/"}]}` threw until 2026-08-08 — `timeline-main`
  ;; NPE'd on a nil working set, and `:timeline` and `:change` had no canned
  ;; responses. So the entire Review half of this app had never been looked at
  ;; headlessly — the first fixture gap closed — and it hid two live
  ;; defects in `change-main` that this test now pins.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]

    (testing "the Review screen renders at all — it is a project's root, and it
              threw for as long as this fixture was missing"
      ;; `/p/demo/`, not `/`. The app's `/` is the daemon's LANDING — the
      ;; picker, which lists projects; the Review section is the root of one.
      (cljnx/visit! s (at "/"))
      (is (re-find #"(?m)^<h1>review</h1>$" (cljnx/text s "main"))))

    (testing "a commit-point with a range links its change screen and the OLDEST
              does not — the first commit-point in a store has nothing behind it,
              and a link to an empty diff would be a link to nowhere"
      (let [out (cljnx/text s "main")]
        (is (re-find #"<a href=\"/p/demo/change/d3300\.\.d3457\">" out))
        (is (not (re-find #"<a[^>]*>the first commit-point" out)))
        (is (re-find #"the first commit-point" out)
            "still listed, just not a link")))

    (testing "and following one arrives at the diff"
      (cljnx/click! s "The API section navigates like an API browser")
      (is (= "/change/d3300..d3457" (where s)))
      (is (re-find #"(?m)^<h1>d3300\.\.d3457</h1>$" (cljnx/text s "main"))))

    (testing "a diff's added and removed lines are DIFFERENT STRINGS, not the
              same string in two colours. `.del` and `.add` are a background,
              so without the stylesheet this screen showed a diff with no diff
              in it — and this is the reader without the stylesheet"
      (let [out (cljnx/text s "main" {:detail :prose})]
        (is (re-find #"(?m)^- +\[kg\]$" out))
        (is (re-find #"(?m)^\+ +\[kg zone\]$" out))))

    (testing "a form with no recorded ask leaves no gap where the sentence
              would be — `band-for` was added without one"
      (is (re-find #"band-for" (cljnx/text s "main" {:detail :prose}))))

    (testing "the section is reachable from the global bar, which is the last
              thing that was never checked because nothing could get here"
      (cljnx/visit! s (at "/store"))
      (cljnx/click! s "Review")
      (is (= "/" (where s))))))

(deftest the-code-section-can-be-navigated-without-typing-a-url
  ;; The question that produced this: "how am I supposed to navigate around? It
  ;; doesn't seem hooked up anywhere." It was right. `/store/gaps` had no link
  ;; in the app at all, and `/store/module/:m` had one — inside `/store/gaps`,
  ;; which you could not reach. Both features were URL-only.
  ;;
  ;; Driven rather than asserted on hrefs: an href assertion proves a link
  ;; EXISTS, not that pressing it arrives somewhere. That gap is the whole
  ;; complaint.
  (let [s (cljnx/open! (cljnx/driver-for (page/page)))]

    (testing "the lens switcher reaches the gap overlay and back again"
      (cljnx/visit! s (at "/store"))
      (cljnx/click! s "gaps")
      (is (= "/store/gaps" (where s)))
      (is (re-find #"where the record is thin"
                   (cljnx/text s "main" {:detail :prose})))
      (cljnx/click! s "diagram")
      (is (= "/store" (where s))
          "and the default is a view you can return to, not a one-way door"))

    (testing "a module box DESCENDS into the module it stands for"
      ;; addressed by :href — two controls answer to "core", the box and the
      ;; nav row, and the driver refuses to guess between them rather than
      ;; picking one. That refusal is correct and worth leaning on.
      ;;
      ;; The href carries the project, because that is what the markup holds:
      ;; `chrome` prefixes every link this app routes, so a click target is a
      ;; whole address.
      (cljnx/visit! s (at "/store"))
      (cljnx/click! s (at "/store/module/demo.core"))
      (is (= "/store/module/demo.core" (where s)))
      (let [main (cljnx/text s "main" {:detail :prose})]
        (is (re-find #"3 namespaces, 25 forms" main))
        (is (re-find #"used by" main)
            "the boundary is what makes the descend more than a smaller diagram")))

    (testing "and from a module you reach a namespace, then a form — the whole
              chain with no url typed after the first"
      ;; by LABEL, not href: three controls legitimately point at
      ;; /store/ns/demo.core here — the diagram box, the namespaces list and
      ;; the left nav — and two point at the form, the outline name and the
      ;; elided skeleton under it. The driver refuses to guess between real
      ;; duplicates, which is right, so this names the one it means.
      (cljnx/click! s "calc")
      (is (= "/store/ns/demo.core.calc" (where s)))
      (cljnx/click! s "rate")
      (is (= "/store/form/f1" (where s)))
      (is (re-find #"the path through here"
                   (cljnx/text s "main" {:detail :prose}))
          "arriving at a form, the spine is there to orient you"))))

(deftest every-screen-asks-for-a-url-measured-against-its-own-PROJECT
  ;; **The test that would have caught it, written after it did not.**
  ;;
  ;; The framework builds every url, and once it did, every screen fetched
  ;; `/api/modules` at the ORIGIN root — which the daemon answers 404 for. The
  ;; whole app, not one pane. **The suite was green throughout**, because
  ;; `page/responses` cans the performer BY PATH: a fixture keyed by the url it
  ;; expects agrees with whatever the code sends it.
  ;;
  ;; What a project-facing request is measured from is the PROJECT in its
  ;; address, which is route state rather than configuration.
  (letfn [(fresh []
            ;; **`:render` has to build the VIEW, and that is new.** `navigate!`
            ;; hands `render` the STATE; a browser's render is what calls the
            ;; view. While a route row declared its `:request`, navigating
            ;; fetched whether or not anything rendered, so a no-op render still
            ;; exercised the urls. A page asks WHILE IT RENDERS — so a test that
            ;; does not render asks for nothing, and every loop below would pass
            ;; against an empty list.
            (let [asked (atom [])
                  wired (atom nil)
                  app   (webapp/wiring
                         (assoc (app/wiring
                                 {:state  (atom {})
                                  :call   (fn [request ok _err]
                                            ;; already RESOLVED: `request-url` is
                                            ;; gone because `endpoint/request`
                                            ;; builds one finished `:http/url`.
                                            (swap! asked conj (:http/url request))
                                            (ok nil))
                                  :render (fn [st] (when-let [w @wired] ((:webapp/view w) st)))})
                                ;; the entry declares no table — `driver-for` and
                                ;; the build each fill it from the page markers.
                                :webapp/routes (cljnx/marked-pages)))]
              (reset! wired app)
              {:app app :asked asked}))
          (go [{:keys [app asked]} path]
            (reset! asked [])
            (webapp/navigate! app path false)
            @asked)]

    (testing "a screen's request is measured against the project in its address
              — and the daemon's mount for that project is what the url names"
      ;; `/api/projects/demo` is the DAEMON's mount for the project; `/modules`
      ;; is the PROJECT's own path with the contract's `/api` prefix replaced
      ;; by the mount — emitted by the generated descriptor with no slug in
      ;; it. Two api spaces that share a word, owned by different stores.
      ;;
      ;; `/api/projects` rides along because the nav's project switcher asks for
      ;; it on the first screen that draws a shell. It was a declared session
      ;; load started at boot; it is an ordinary `ask!` now, made once.
      (is (= ["/api/projects/demo/modules" "/api/projects"]
             (go (fresh) "/p/demo/store"))))

    (testing "including path parameters and a query, which is where a prefix
              bug would otherwise hide behind a url that looks plausible"
      (let [app (fresh)]
        (go app "/p/demo/store")
        (is (= ["/api/projects/demo/form/f1?depth=2"]
               (go app "/p/demo/store/form/f1")))))

    (testing "a DIFFERENT project is a DIFFERENT LOAD, which is the half that
              was broken: the tenant is in the url, so the two cannot share a
              cache entry"
      ;; measured before the fix: this asked for NOTHING and rendered demo's
      ;; modules under the other project's name. `load-key` is `[method url]`
      ;; and `:webapp/base` is applied after the key is taken, so a base-carried
      ;; tenant is invisible to the cache. See `views/at-project`.
      (let [app (fresh)]
        (go app "/p/demo/store")
        (is (= ["/api/projects/other/modules"] (go app "/p/other/store")))))

    (testing "and a lens address asks for its subject's url — three addresses,
              ONE load, which is the fact that made them one subject"
      (let [app (fresh)]
        (is (= ["/api/projects/demo/modules" "/api/projects"]
               (go app "/p/demo/store/gaps"))
            "the gaps lens asks for the Code section's own endpoint")
        (is (= [] (go app "/p/demo/store/table"))
            "and the table lens asks for nothing further — same request, same
             load, which `ask!` answers from what the first one started")))

    (testing "the LANDING asks the daemon's own registry at the origin, not a project's"
      (is (= ["/api/projects"] (go (fresh) "/"))))

    (testing "a page with nothing to ask for asks for NOTHING rather than for
              the empty url — an address no page matches renders not-found,
              which asks nobody anything"
      (is (= [] (go (fresh) "/nothing/here"))))))

(defn- with-endpoints
  "`state` carrying `doc` under the key the endpoint page's own ask produces.

  **`:main` is a key nothing looks under any more**, and that is why this
  exists rather than a literal. A load is identified by its REQUEST — same
  descriptor, same base — so the only way for a fixture to put the document
  where `try-request` will look is to build the same request the page builds.

  Written by hand, `{:loads {:main …}}` does not fail loudly: `try-request`
  finds nothing, answers nil, and nil is the same channel an UNARMED call uses.
  So an assertion that a filled form produces a request goes QUIET rather than
  red — which is exactly what three tests in this file did across this
  migration, reporting green while asserting nothing at all."
  [state doc]
  (assoc state :loads
         {(webapp/load-key
           ;; `\"\"` is this app's own base — the shell is served at the root, so
           ;; `app/wiring` answers `(or base \"\")` and that is what `ask!` keys
           ;; with. The REQUEST carries its own `/api/projects/<slug>` from
           ;; `at-project` and `addressed` gives that the last word, so the key
           ;; resolves to the project's mount either way.
           ""
           (endpoint/request (views/at-project (:params state) api/rest-paths) {}))
          {:status :ready :value {:paths doc}}}))

(defn- route-params
  "The captures the ROUTER produces for `ep`'s own page.

  `views/endpoint-params` builds an address from a DOCUMENT ROW — `{:method
  :path}` — and that is what a LINK is made of. What arrives in `:params` is
  different: the route ends in `**`, slopp's wildcards are anonymous, so the
  remainder lands under `:*`.

  The two are one translation apart and `views/routed-address` owns it. This is
  its inverse, and it exists so a hand-built fixture cannot quietly hold a shape
  the router never produces — which is the failure this file already carries a
  comment about, from the edit where `try-request` and a fixture agreed with
  each other and neither with the application."
  [ep]
  (let [{:keys [method path]} (views/endpoint-params ep)]
    {:method method :* path}))

(deftest filling-in-a-call-is-state-and-the-request-is-derived-from-it
  ;; The try-it-out form is the one place this app takes input that is not a
  ;; url. It goes through `act` like every other control, so the whole of it is
  ;; testable in-image and only the HTTP call itself is a plug-in.
  (let [doc [{:method :get :path "/api/module/:m" :name 'module
              :request nil :response [:map [:module :string]]}
             {:method :get :path "/api/search" :name 'search
              :request [:map [:q {:optional true} :string]]
              :response [:map [:total :int]]}]
        at  (fn [nm]
              ;; the state the ROUTER produces — see the note in
              ;; `an-effectful-call-is-armed-before-it-fires`
              (let [ep   (first (filter #(= (str (:name %)) (str nm)) doc))
                    ;; a name the document does not carry still gets a WELL-FORMED
                    ;; address — the case under test is an address that names no
                    ;; endpoint, not one the router could never produce.
                    ;;
                    ;; ROUTER-shaped, so `:*` rather than `:path`: the route ends
                    ;; in `**` and slopp's wildcards are anonymous.
                    addr (if ep
                           (route-params ep)
                           {:method "get" :* (str "api/" nm)})]
                                ;; **`:screen` is gone from the fixture because nothing reads it**
                ;; — `try-request` discriminates on what the ADDRESS captured, a
                ;; page function carrying no metadata to look a subject up from —
                ;; and the document is seeded under the page's own load key,
                ;; because `:main` is a key nothing looks under. See
                ;; [[with-endpoints]] for what a literal there costs.
                (with-endpoints
                  {:path   (str "/p/demo/endpoints/" (:method addr) "/" (:* addr))
                   ;; the slug rides in params on every address this app
                   ;; answers, and the ad-hoc call is measured from it
                   :params (assoc addr :slug "demo")}
                  doc)))]

    (testing "typing into a field is an ordinary action carrying the field name,
              with the text arriving as `value` — the same seam the ns filter
              already uses, so nothing here needs a browser"
      (is (= {"m" "slopp.ops"}
             (-> (at "module") (app/act [:try/set "m"] "slopp.ops") :call :params))))

    (testing "the request is DERIVED from state rather than assembled by the
              control, so what is sent is a pure function of what was typed"
      (let [st (-> (at "module") (app/act [:try/set "m"] "slopp.ops"))]
        ;; a RESOLVED request: `schema/request-for` goes through
        ;; `slopp.http.endpoint/request` now, so the pattern and its captures
        ;; are already spent on one finished url — which is what the performer
        ;; needs and what this used to stop one step short of.
        (is (= {:http/method :get :http/url "/module/slopp.ops"
                :webapp/base "/api/projects/demo"}
               (app/try-request st ""))
            (str "measured from the PROJECT, like every other request this app"
                 " makes: built from the raw row it went to the origin's"
                 " /api/module/…, which the daemon does not serve — "
                 (pr-str (app/try-request st ""))))))

    (testing "on a screen that is not one endpoint's page there is nothing to
              call, and asking produces nil rather than a guess"
      ;; the endpoint INDEX, a real screen var — not a bare keyword. With a
      ;; keyword here this passed because `screen-subject` answers nil for
      ;; anything that is not a row target, so it was asserting "garbage has no
      ;; call" rather than "the index screen has no call". The distinction is
      ;; the whole content of the claim.
      ;; the endpoint INDEX, addressed the way the router addresses it: no
      ;; `:method` capture, because `/p/:slug/endpoints` has none. That IS the
      ;; discriminator now — the index and the detail page differ by what their
      ;; patterns capture, which is a fact about the address rather than about
      ;; which var happens to be in `:screen`.
      (is (nil? (app/try-request
                 (with-endpoints {:path "/rest/paths" :params {}} doc) "")))
      (is (nil? (app/try-request (at "nope") ""))))

    (testing "the filled form dies with the ROUTE that carried it. `/api/search`
              has no `m` and would otherwise inherit one — the same reason
              `:lens` and `:loads` are cleared, and the answer to the question
              `navigate`'s own docstring poses about any new key: is it part of
              the ADDRESS, or part of the session?"
      ;; through the REAL navigation, not this app's retired copy. `:try` dies
      ;; because `wiring` declares it in `:webapp/address-keys` and the
      ;; framework's `arrive` drops those on every move. Asserting against
      ;; `nav/navigate` — which clears `:try` with its own hand-written
      ;; `assoc` — would keep passing after the shipped mechanism broke, which
      ;; is the one failure a test of a retired path always has.
      (let [atom* (atom (app/act (at "module") [:try/set "m"] "slopp.ops"))
            app   (app/wiring {:state  atom*
                                        :fetch  (fn [_ _ ok _] (ok doc))
                                        :render (fn [_])})]
        (is (some? (:call @atom*)) "nothing was typed, so the move below proves nothing")
        (webapp/navigate! app "/endpoints/get/api/search" false)
        (is (nil? (:call @atom*)))))))

(deftest the-arming-gate-reads-the-METHOD-because-nobody-publishes-effectful
  ;; **The gate above was inert on every real document, and this store's whole
  ;; test suite agreed it worked.**
  ;;
  ;; `:effectful?` is a key slopp publishes NOWHERE. Measured 2026-08-24
  ;; against the hub's own contract document, which unlike a project's has POST
  ;; endpoints in it:
  ;;
  ;;     /api/register  :post  auth :public  9 keys  :effectful? ABSENT
  ;;
  ;; So `(:effectful? endpoint)` was nil on live data, the two-press arming
  ;; never engaged, and a mutation fired on the FIRST click. Every assertion
  ;; about it passed because `page/contract-document` invents the key — the
  ;; fixture was the only place it had ever existed.
  ;;
  ;; `slopp-server.ui.schema-test` had already recorded the near-miss: *":auth landed
  ;; 2026-08-11, the ask beside `:effectful?` that did not ship."* The ask not
  ;; shipping was written down. That a shipped safety gate depended on it was
  ;; not, for thirteen days.
  ;;
  ;; The method is published, is in the same document, and is the fact the key
  ;; would have been derived from anyway. slopp agrees and would rather not add
  ;; a fifth key under an unchanged `:slopp/contract-version 1` — which is the
  ;; defect they fixed this morning.
  (let [wire {:method :post :path "/api/register" :name 'register!
              ;; NO :effectful? — this is the shape that actually arrives, and
              ;; the sibling test above uses the fixture's invented one.
              :request  [:map [:name :string]]
              :response [:map [:slug :string]]}
        doc  [wire
              {:method :get :path "/api/modules" :name 'modules
               :request nil :response [:map [:a :string]]}]
                ;; `:screen` dropped — `try-request` reads the ADDRESS — and the
        ;; document seeded under the page's own load key, since `:main` is a
        ;; key nothing looks under. A literal there would leave `try-request`
        ;; finding nothing and answering nil, which is the same channel an
        ;; UNARMED call uses: the gate assertions below would pass whether or
        ;; not the gate worked.
        st   (fn [ep] (with-endpoints
                        {:path   (views/endpoint-address ep)
                         :params (route-params ep)}
                        doc))]

    (testing "a POST with no :effectful? key is STILL effectful — the document
              says so with :method, which is published"
      (is (schema/effectful? wire))
      (is (not (schema/effectful? (second doc))))
      (is (schema/effectful? {:method :delete :path "/x"}))
      ;; HEAD is SAFE, and this store had it wrong until slopp published their
      ;; own derivation to compare against: `(not (contains? #{:get :head} …))`.
      ;; A HEAD is a GET without a body — it is the one other method HTTP
      ;; defines as safe, and marking it "changes something" would put a
      ;; two-press gate in front of a request that cannot change anything.
      (is (not (schema/effectful? {:method :head :path "/x"})))
      (is (not (schema/effectful? {:path "/x"}))
          "no method at all defaults to GET, the way the rest of this app reads it"))

    (testing "so the gate engages on wire data, which is the whole point"
      (is (nil? (app/try-request (st wire) ""))
          (str "a mutation was assembled on the first press: the arming gate"
               " read a key no server sends"))
      (is (some? (app/try-request (assoc-in (st wire) [:call :armed?] true) ""))))))

(deftest an-effectful-call-is-armed-before-it-fires
  ;; Executing a POST used to be declined in words. It is wired now, and the
  ;; endpoint that made it worth wiring is this app's own `register!` — a
  ;; documented, declared-effectful POST that really does mutate a registry.
  ;;
  ;; **Two clicks, and only for an effectful endpoint.** `:effectful?` is a
  ;; fact no other API browser has, and spending it on a warning that cannot
  ;; stop anything would be spending it on nothing. A read-only endpoint still
  ;; fires on one click, because a confirmation on every call is a
  ;; confirmation nobody reads.
  (let [doc  [{:method :post :path "/api/register" :name 'register!
               :effectful? true
               :request [:map [:name :string] [:pid {:optional true} :int]]
               :response [:map [:slug :string]]}
              {:method :get :path "/api/modules" :name 'modules
               :request nil :response [:map [:a :string]]}]
        st   (fn [nm]
                ;; the state the ROUTER produces, not one shaped by hand. This
                ;; built `:params {:name nm}` for one edit after the address
                ;; became method+path — a shape the app can no longer reach —
                ;; and `try-request` went on selecting by `:name`, so both
                ;; agreed with each other and neither with the application.
                (let [ep (first (filter #(= (str (:name %)) (str nm)) doc))]
                                    ;; `:screen` dropped — `try-request` reads the ADDRESS, not a
                  ;; subject looked up from a var — and the document seeded under
                  ;; the page's own load key, since `:main` is a key nothing
                  ;; looks under. See [[with-endpoints]].
                  (with-endpoints
                    {:path   (views/endpoint-address ep)
                     :params (route-params ep)}
                    doc)))
        ;; through the readout, not a tree-seq. A tree-seq over `coll?` yields
        ;; attribute-map VALUES as well, so class names interleave with the
        ;; text: `name · body · string` came out `field name  ·  ftype string`.
        ;; This is the same defect as reading a screen by grepping its markup,
        ;; and the readout is the thing that exists to avoid it.
        text (fn [v] (str/join "\n" (cljnx/lines v {:detail :prose})))]

    (testing "a POST gets a box per body field, where it used to get a refusal"
      (let [v (views/endpoint-main doc (addr-of doc "register!") nil)]
        (is (not (re-find #"not wired yet" (text v))))
        (is (re-find #"name · body · string" (text v)))
        (is (re-find #"pid · body · int" (text v)))))

    (testing "the first press ARMS rather than fires, and the button then says
              what the second press will do"
      ;; `[:try/arm]` is what the unarmed button dispatches — asserting
      ;; `[:try/execute]` here would have been pinning an action this view
      ;; never emits, which is the registry-and-consumer pairing one rung in.
      (let [armed (app/act (st "register!") [:try/arm] nil)]
        (is (:armed? (:call armed)))
        (is (nil? (:status (:call armed))) "nothing has been performed yet")
        (is (re-find #"yes — call it"
                     (text (views/endpoint-main doc (addr-of doc "register!") (:call armed)))))))

    (testing "and the request is only BUILT once armed — a screen that
              assembles a mutation on the first click has already decided"
      (is (some? (app/try-request (assoc-in (st "register!") [:call :armed?] true) "")))
      (is (nil? (app/try-request (st "register!") ""))
          "unarmed, there is nothing to perform"))

    (testing "a READ-ONLY endpoint is unchanged — one click, no arming. A
              confirmation on every call is one nobody reads"
      (is (some? (app/try-request (st "modules") "")))
      (is (not (re-find #"yes — call it"
                        (text (views/endpoint-main doc "modules" nil))))))

    (testing "and leaving the endpoint DISARMS it, because `:try` dies with the
              route — an armed mutation surviving a navigation is the one way
              this could fire without a reader meaning it"
      ;; through the REAL navigation. This is the assertion in this file with
      ;; the most at stake, so it must exercise the mechanism that actually
      ;; ships: `:try` is in `:webapp/address-keys` and the framework's
      ;; `arrive` drops it. Pointed at `nav/navigate` it would assert against
      ;; a hand-written `assoc` that is no longer on the path — passing while
      ;; the shipped app carried an armed POST across a navigation.
      (let [atom* (atom (app/act (st "register!") [:try/arm] nil))
                        app   (assoc (app/wiring
                          {:state  atom*
                           ;; `:call`, not `:fetch` — the entry takes one
                           ;; performer, and `:fetch` was silently ignored.
                           :call   (fn [_ ok _] (ok doc))
                           :render (fn [_])})
                         ;; the entry declares no table; `navigate!` reads one,
                         ;; so a test calling it directly derives the same table
                         ;; `driver-for` and the build derive.
                         :webapp/routes (cljnx/marked-pages))]
        (is (:armed? (:call @atom*))
            "not armed to begin with, so the move below would prove nothing")
        ;; a WHOLE address: every route this app answers carries the project it
        ;; is inside, so an app-relative path matches no row and the navigation
        ;; would be a no-op — leaving `:call` in place and passing for the
        ;; wrong reason.
        (webapp/navigate! app "/p/demo/endpoints/get/api/modules" false)
        (is (nil? (:call @atom*)))))))

(deftest the-BROWSER-entry-needs-a-route-table-and-nothing-fills-it-for-it
  ;; **This is the test that was missing when the browser went white.**
  ;;
  ;; `the-declaration-this-app-hands-both-entries-is-a-valid-one` claims in its
  ;; own docstring to be *the only thing standing between a bad declaration and
  ;; a blank browser page*, and it did not catch this — because it derives
  ;; through `cljnx/driver-for`, which FILLS `:webapp/routes` from
  ;; `marked-pages` when the entry omits it. `slopp.webapp.dom/mount!` fills
  ;; nothing: it merges the browser's effect plug-ins over what it is handed and
  ;; calls `wiring` directly.
  ;;
  ;; So Move A removed the declared table, the headless path went on working
  ;; because `driver-for` re-derived it, and the browser threw at startup with
  ;; the document served, the bundle loaded and nothing rendered.
  ;;
  ;; The two paths differ in exactly one key, and that is the whole content of
  ;; this test.
  (let [declared (page/page)
        ;; what `dom/mount!` merges over the declaration — the four effects a
        ;; page has. Values are irrelevant; `wiring` only checks the shape.
        browser  (fn [m] (merge m {:webapp/base      ""
                                   :webapp/push-url! (fn [_])
                                   :webapp/leave!    (fn [_])
                                   :webapp/call      (fn [_ _ _])}))]

    (testing "the declaration carries NO route table — the markers are the one
              declaration, and a table beside them made the marker decorative"
      (is (not (contains? declared :webapp/routes))))

    (testing "so wiring it the way the BROWSER does refuses, and that refusal
              is the white page: document served, bundle loaded, nothing drawn"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":webapp/routes"
           (webapp/wiring (browser declared)))))

    (testing "and wiring it WITH the table the build generates succeeds — which
              is what the generated browser entry does, and why this store must
              not hand-write one"
      ;; `marked-pages` is the image-side reader of the same `^{:webapp/path …}`
      ;; markers `slopp.rules.webapp/page-routes` reads at build time. Two
      ;; readers of one marker, so standing in for the generated table here is
      ;; not a second source of truth.
      (let [wired (webapp/wiring (browser (assoc declared :webapp/routes (cljnx/marked-pages))))]
        (is (fn? (:webapp/view wired)))
        (is (seq (:webapp/routes wired)))))

    (testing "the headless path fills it and therefore CANNOT see this — stated
              so the next reader does not take a green drive as cover for the
              browser"
      (is (not (contains? declared :webapp/routes)))
      ;; it answers the DRIVER contract rather than the wired app, so the
      ;; filled table is not on what comes back — what proves it filled one is
      ;; that a `:view` exists at all. `wiring` refuses without a table, so a
      ;; derivation that reached a view is a derivation that supplied one.
      (is (fn? (:view (cljnx/driver-for declared)))
          "driver-for derives a view, which it can only do by filling the
           table the declaration omits — the same omission that throws above"))))

(deftest choosing-a-project-actually-leaves-for-it
  ;; **The assertion that was missing for a month.** The switcher rendered a
  ;; `<select>` whose options carried the address as `:value` and nothing
  ;; anywhere read it. Every test written about it asked what the OPTIONS said;
  ;; all of them passed, and choosing an entry did nothing at all.
  ;;
  ;; So this drives the CONTROL rather than reading it, and it wires its own
  ;; `:leave!` — `app/wiring` defaults that plug-in to `(fn [_])`, which means a
  ;; headless drive of a working switcher and one of a dead switcher produce
  ;; the same nothing. A recorder is the only way that difference is visible
  ;; without a browser, and it is the far side of the joint: the view and
  ;; `url-for` were each covered while the wire between them was not.
  (let [left (atom [])
        s    (cljnx/open!
              (cljnx/driver-for
               (app/wiring {:state  (atom {})
                            :call   (fn [_ ok _] (ok (:projects page/responses)))
                            :render (fn [_])
                            :leave! (fn [url] (swap! left conj url))})))]
    (cljnx/visit! s "/p/demo/store")

    (testing "choosing a project leaves for it — ONE interaction, because
              `url-for` is handed the selected value the way `act` is"
      (cljnx/fill! s "switch project" "slopp2")
      (is (= ["/p/slopp2"] @left)))

    (testing "and the address is built from the SLUG by `url-for` rather than
              carried on the option, so the option and the destination cannot
              spell the same project differently"
      (cljnx/fill! s "switch project" "demo")
      (is (= ["/p/slopp2" "/p/demo"] @left)))

    (testing "a project the daemon does not hold cannot be chosen — the driver
              refuses a value a browser would not let a reader produce, which
              is also why no guard for it lives in the view"
      (is (thrown? Exception (cljnx/fill! s "switch project" "nonesuch")))
      (is (= ["/p/slopp2" "/p/demo"] @left)
          "a refused fill left for nowhere"))))

(deftest a-hard-load-of-the-root-lands-in-the-only-open-project
  ;; The same fact one level up, through the entry a browser and the `screen`
  ;; tool open: typed `/`, the address bar shows the project. `page/page`
  ;; cans TWO projects on purpose (the switcher's fixture), so this wires the
  ;; same declaration with a registry of one.
  (let [only  [{:slug "only" :dir "/w/only" :opened-at 0 :sessions 1 :cli false :app nil}]
        entry (app/wiring
               {:state  (atom {})
                :call   (fn [request ok _err]
                          (ok (when (= "/api/projects" (str (:http/url request))) only)))
                :render (fn [_])})
        s     (cljnx/open! (cljnx/driver-for entry) "/")]
    ;; the SWITCHER rather than the main pane: the destination page asks for
    ;; its own timeline, which this fixture does not can, so its pane says
    ;; so — and that is the project's screen honestly loading, not the landing
    (is (re-find #"only" (cljnx/text s "nav/switcher")) (cljnx/text s))
    (is (= "/p/only" (cljnx/url s))
        "the address bar shows the project, not the landing that sent the reader there")))
