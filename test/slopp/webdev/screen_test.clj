(ns slopp.webdev.screen-test
  "End-to-end cover for the `screen` tool: a real store, a real image, the
  app's own handlers, and a readable answer.

  Everything below `slopp.cljnx` is unit-tested against hiccup literals,
  which is right and proves nothing about the part that actually breaks — that
  the entry is findable, that the framework is on the image's classpath, and
  that a script survives crossing into another JVM as data. Those are the
  seams, so this test ingests a small app and drives it.

  It is `^:external` for the plainest of reasons: it starts an image."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops]
            [slopp.ops.external :as external]
            [slopp.webdev.screen :as webdev.screen] [clojure.java.io :as io] [slopp.kernel.boot :as boot] [rewrite-clj.parser :as p] [slopp.store :as store] [rewrite-clj.node :as n] [slopp.project.capabilities :as capabilities]))

(deftest ^:external an-agent-can-look-at-a-screen-without-writing-code
  ;; The end of the loop this feature exists to close: a real store, a real
  ;; image, the app's own handlers, and a readable answer — no test written by
  ;; the reader and no browser opened.
  ;;
  ;; TWO environment facts make this look stranger than it is, and both are
  ;; properties of the external TIER rather than of the tool:
  ;;
  ;; (1) `framework-files` reads META-INF/slopp/framework-files.edn, which
  ;; build.clj generates INTO THE JAR. A test JVM running from a materialized
  ;; tree has no such resource, so the real fn answers nil and nothing would
  ;; ever vendor. `a-framework-using-store-survives-a-RESTART` redefs it for
  ;; the same reason; this one supplies the REAL slopp/web sources, DERIVED
  ;; from the classpath rather than hand-listed — a hand-kept file list goes
  ;; stale the first time a namespace is added, which this store learned twice
  ;; this week.
  ;;
  ;; (2) Vendoring must precede process start — a JVM caches a relative
  ;; classpath dir that did not exist at launch — so the app is ingested and
  ;; THEN the image is restarted. A real session gets that ordering for free,
  ;; because the store already holds the app when it opens.
  (let [web-clj (io/file (.toURI (io/resource "slopp/web.clj")))
        web-dir (io/file (.getParentFile web-clj) "web")
        prefix  (inc (count (.getPath web-dir)))
        subtree (into {"slopp/web.clj" (slurp web-clj)}
                      (for [f (file-seq web-dir)
                            :when (and (.isFile f) (.endsWith (.getName f) ".clj"))]
                        [(str "slopp/web/" (subs (.getPath f) prefix)) (slurp f)]))
        ;; ...plus the "_" half, filed the way build.clj files it: the
        ;; namespaces belonging to every capability rather than to one.
        ;;
        ;; **Read off `capabilities/shipping-common`, the SAME list production
        ;; reads**, and that is the whole point of this binding. It used to
        ;; scan `subtree`'s requires with a regex, and the comment here treated
        ;; that as a virtue — a second derivation that keeps the first honest.
        ;; It is not one. A second derivation agrees until it does not, and
        ;; this one stopped agreeing the moment a member arrived that nothing
        ;; under slopp/web/** requires.
        ;;
        ;; `slopp.cljnx` is exactly that member: the fake browser ships to
        ;; every store and is required by no framework namespace, because slopp
        ;; opens an app with it on the app's BEHALF. The scan therefore
        ;; vendored none of it and the tool died on "Could not locate
        ;; slopp/cljnx.clj" — in the one test whose whole subject is the tool
        ;; working in a store that has only what slopp handed it.
        ;;
        ;; A collapse beats a check: one list, and this reads it.
        syntax  (into {}
                      (for [[_ path] capabilities/shipping-common
                            :let [u (io/resource path)]
                            :when u]
                        [path (slurp u)]))
        fw      (merge subtree syntax)]
    (is (contains? fw "slopp/web/dispatch.clj")
        "the derivation found the framework — an empty file map vendors nothing and every assertion below would fail for the wrong reason")
    (is (contains? fw "slopp/lang.cljc")
        "and its out-of-subtree deps: slopp.web.router calls slopp.lang, which ships WITH the framework and does not live under it")
    (is (contains? fw "slopp/cljnx.clj")
        "the fake browser is what this tool DRIVES with, and nothing under slopp/web/** requires it — so a derivation that follows requires cannot find it")
    ;; and its DEPS, for the same reason and with the same cause: vendoring
    ;; copies SOURCE and discards the pom, so the framework's own requires have
    ;; to arrive separately. Production derives this list at build time into
    ;; framework-deps.edn; here it is spelled out, and a drift announces itself
    ;; as "Could not locate <lib>" rather than hiding.
    (with-redefs [boot/framework-files (constantly {"http" subtree "_" syntax})
                  boot/framework-deps  (constantly '{"http" {cheshire/cheshire {:mvn/version "5.13.0"}
                                                             hiccup/hiccup     {:mvn/version "2.0.0"}
                                                             garden/garden     {:mvn/version "1.3.10"}
                                                             http-kit/http-kit {:mvn/version "2.8.0"}}})]
      (let [sess (external/open!)]
        (try
          (ops/ingest! sess 'demo.app
                       (str "(ns demo.app)\n\n"
                            "(def state (atom {:q \"\" :n 0}))\n\n"
                            "(defn view \"V.\" [s]\n"
                            "  [:main {:data-region \"main\"}\n"
                            "   [:h1 \"Demo\"]\n"
                            "   [:input {:placeholder \"Filter\" :value (:q s)\n"
                            "            :on-change #(swap! state assoc :q (:value %))}]\n"
                            "   [:button {:on-click #(swap! state update :n inc)} \"Add\"]\n"
                            "   [:p (str \"q=\" (:q s) \" n=\" (:n s))]])\n\n"
                            "(defn ^:web/page page \"P.\" [] {:state state :view view})\n"))
          ;; the MARKER is what makes this store a framework user: its own code
          ;; requires nothing from slopp.web, and slopp opens it with
          ;; slopp.cljnx on its behalf
          (ops/restart! sess)

          (testing "a bare look renders the v2 screen, and names the entry it used"
            (let [r (webdev.screen/screen! sess)]
              (is (nil? (:error r)) (pr-str r))
              (is (str/includes? (str (:screen r)) "<h1>Demo</h1>"))
              (is (str/includes? (str (:screen r)) "<input placeholder=\"Filter\"")
                  "a field is VISIBLE with its addressing attrs")
              (is (str/includes? (str (:screen r)) "slopp:on=\"change (fn)\"")
                  "and says typing changes something")
              (is (str/includes? (str (:screen r)) "<button slopp:on=\"click (fn)\">Add</button>")
                  "and a button says it can be clicked")
              (is (str/includes? (str (:entry r)) "demo.app/page")
                  "a screen is only as trustworthy as the app it came from")))

          (testing "a script drives the app's OWN handlers, in order"
            (let [r (webdev.screen/screen! sess
                                 :steps [{:fill "Filter" :value "web"}
                                         {:click "Add"}
                                         {:click "Add"}]
                                 :region "main" :detail "prose")]
              (is (nil? (:error r)) (pr-str r))
              (is (str/includes? (str (:screen r)) "q=web n=2")
                  "typed, clicked twice, and the state is the app's own")
              (is (not (str/includes? (str (:screen r)) "slopp:on"))
                  "prose was asked for and prose is what came back")))

          (testing "trace shows the screen after every step, from the same interpreter"
            ;; demo.app's atom is a DEF, so it persists across tool calls — the
            ;; script above left n=2 and the trace continues from there. That
            ;; persistence is the app's design, not the tool's; a page that
            ;; builds fresh state in its ^:web/page fn starts clean each open.
            (let [r (webdev.screen/screen! sess
                                 :steps [{:fill "Filter" :value "web"} {:click "Add"}]
                                 :region "main" :detail "prose" :trace true)]
              (is (nil? (:error r)) (pr-str r))
              (is (= 2 (count (:screens r))))
              (is (str/includes? (str (:screen (first (:screens r)))) "q=web n=2"))
              (is (str/includes? (str (:screen (last (:screens r)))) "q=web n=3"))))

          (testing "a store with no marked page says what to do about it"
            (let [s2 (external/open!)]
              (try
                (ops/ingest! s2 'plain.core "(ns plain.core)\n\n(defn f \"F.\" [x] x)\n")
                (is (str/includes? (str (:error (webdev.screen/screen! s2))) "no ^:web/page")
                    "an ERROR, not an empty screen — a blank answer reads as a broken app")
                (finally (ops/close! s2)))))
          (finally (ops/close! sess)))))))

(deftest the-tool-refuses-what-it-cannot-mean
  ;; Review B-F4: `(keyword detail)` accepted anything — a typo'd "porse"
  ;; silently rendered structured (a wrong default reported as success), and a
  ;; detail with a space corrupted the GENERATED CODE, whose read failure came
  ;; back labelled "the image's answer was not readable as data" — blaming the
  ;; answer for the input. None of these should reach the image at all.
  (let [dummy (atom {})]
    (testing "a detail the tool does not speak is refused, naming the choices"
      (let [r (webdev.screen/screen! dummy :detail "porse")]
        (is (some? (:error r)))
        (is (str/includes? (:error r) "structured"))
        (is (str/includes? (:error r) "porse"))))
    (testing "steps that are not an array of step maps are refused before the image"
      (let [r (webdev.screen/screen! dummy :steps "visit /store")]
        (is (some? (:error r)))
        (is (str/includes? (:error r) "steps"))))
    (testing "the generated driver PARSES as one balanced form, in every shape"
      ;; the parser, not str/includes? — the grep version of this test stayed
      ;; green over generated code that hit EOF at read (a dropped close paren
      ;; in a string-concatenated body), which the whole external tier then
      ;; reported one seam later. The one property generated source must have
      ;; is that it reads; asserting anything else first is theatre.
      (doseq [opts [{:region nil :detail :structured :list-head 3 :trace false}
                    {:region "main" :detail :prose :list-head 3 :trace true}
                    {:region nil :detail :structured :list-head nil :trace true}]]
        (let [code (webdev.screen/drive-code [{:visit "/x"} {:click "Go"}] opts)
              parsed (try (p/parse-string code) (catch Throwable e e))]
          (is (not (instance? Throwable parsed))
              (str (pr-str opts) " — " (when (instance? Throwable parsed)
                                         (ex-message parsed)))))))
    (testing "the cap and the conditional cause separator ride the generated code"
      (let [code (webdev.screen/drive-code [{:visit "/x"}]
                                 {:region nil :detail :structured
                                  :list-head 3 :trace false})]
        (is (str/includes? code ":list-head 3"))
        (is (str/includes? code "cond->")
            "a message-less cause must not render a trailing colon — parity with read.query/cause-chain")))
    (testing "trace drives ONE session a step at a time through the one interpreter"
      (let [code (webdev.screen/drive-code [{:visit "/x"} {:click "Go"}]
                                 {:region nil :detail :structured
                                  :list-head 3 :trace true})]
        (is (str/includes? code ":screens"))
        (is (str/includes? code "(drive s [step])")
            "single-step drives on one session: no second interpreter, and no re-run of non-idempotent effects")))))

(deftest ^:external
  ^{:correspondence "every form in slopp.cljnx.render that OPENS a page tag vs page-tag, the single attr route its own docstring calls 'deliberately the only route' — a branch building \"<\" (name t) \">\" by hand drops the capability trio and slopp:on silently, which is how a clickable <h2> rendered as inert text"}
  no-form-in-the-renderer-opens-a-page-tag-by-hand
  ;; `page-tag`'s docstring asserts it is "deliberately the only route, so a
  ;; branch cannot hand-build page attrs and drift from the whitelist", and
  ;; nothing made that true. Prose asserting that two things agree is a test
  ;; nobody runs, and worse than no prose at all: a bare duplicate invites
  ;; suspicion while a documented parity disarms it. Its enumerating sibling
  ;; `screen-test/every-page-tag-renders-through-one-attr-route`
  ;; asserted FOUR tags, all four of which already complied, and stayed green
  ;; for the entire life of a four-tag defect: heading, table, pre and cell
  ;; each built their own opening tag, so a clickable <h2> rendered
  ;; `<h2>Title</h2>` — a real control shown as inert text, in the mode whose
  ;; whole contract is that an unprefixed tag means something you can act on.
  ;;
  ;; The two are complementary, not redundant: the enumeration pins attr ORDER
  ;; and content, which this cannot see; this makes the CLASS total, which the
  ;; enumeration cannot.
  ;;
  ;; It lives HERE rather than beside its subject because it is store ANALYSIS
  ;; of web rendering — tooling genre, the same reasoning that moved web-only
  ;; rules out of generic `slopp.rules` into `slopp.rules.web`. `slopp.web` is
  ;; layer 0 and `the-web-framework-never-reaches-back-into-slopp` holds the
  ;; whole subtree there; putting this beside its subject cost two test-only
  ;; edges out of that module, which is a worse trade than the distance.
  ;;
  ;; The population is the NAMESPACE, with no exemption list — measured:
  ;; `open-tag` and `page-tag` build `(str "<" tag …)`, whose literal is `"<"`
  ;; with no letter after it, so the sanctioned builders need no carve-out. An
  ;; exemption list here would be the same hand-kept-list defect one level up.
  (let [st    (external/built-store)
        opens (fn [s] (->> (tree-seq coll? seq s)
                           (filter string?)
                           (filter #(re-find #"<[a-z]" %))
                           ;; slopp:* tags are DERIVED by the reader and have no
                           ;; page attrs to be wrong about — page-tag says so
                           (remove #(clojure.string/starts-with? % "<slopp:"))
                           distinct vec))
        body  (fn [f] (let [[_ _ & more] (n/sexpr (:node f))]
                        (if (string? (first more)) (rest more) more)))]
    (testing "the detector BITES — an empty result must not be its only mode"
      (is (= ["<td>"] (opens '(str "<td>" x "</td>"))))
      (is (= ["<h2 class="] (opens '(str "<h2 class=" c ">")))))
    (testing "and it ignores exactly what it must"
      (is (= [] (opens '(str "</td>" "</" (name t) ">")))
          "a CLOSING tag is not an opening one")
      (is (= [] (opens '(str "<slopp:elided count=\"2\"/>")))
          "the derived channel carries no page attrs")
      (is (= [] (opens '(str "<" (name t) ">")))
          "the sanctioned builders compose the tag, which is the point"))
    (testing "there is a population — the scan reached real source"
      (let [strs (mapcat #(filter string? (tree-seq coll? seq (body %)))
                         (store/forms st 'slopp.cljnx.render))]
        (is (< 50 (count strs))
            (str "an empty scan and a clean renderer are the same output —"
                 " measured 83 here, so this catches built-store reaching"
                 " nothing rather than tracking the renderer's size"))))
    (testing "no form in the renderer opens a page tag by hand"
      (doseq [f (store/forms st 'slopp.cljnx.render)
              :when (:name f)]
        (is (= [] (opens (body f)))
            (str "slopp.cljnx.render/" (:name f)
                 " builds a page tag by hand, so every capability attr"
                 " (aria-label / aria-hidden / inert) and slopp:on it should"
                 " carry is dropped — render it through page-tag"))))))
