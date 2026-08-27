(ns slopp.api.reads-test
  "The store browser through the PORTLESS pipeline: route → policy →
  declared reads → handler, against an in-memory fixture store. The
  escaping assertion is a SECURITY test — the browser renders arbitrary
  store source."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.reads :as api.reads] [clojure.string :as str]))

(deftest a-defs-value-is-not-its-docstring
  ;; Pattern 1, alive in the shipped UI. `form-doc` read `(nth sx 2 nil)` and
  ;; accepted any string it found — but index 2 of a `def` is the VALUE when
  ;; there is no docstring, so `(def greeting "hello")` rendered "hello" as the
  ;; form's documentation on the reviewer page.
  ;;
  ;; The failure is silent by construction: a wrong index does not throw, it
  ;; yields something plausible. `store/form-docstring` exists to ask whether a
  ;; docstring can LEGALLY be there rather than whether index 2 happens to hold
  ;; a string, and this is the second site caught not using it.
  (let [st  (store/ingest (store/empty-store) 'fd.core
                          (str "(ns fd.core)\n"
                               "(def greeting \"hello\")\n"
                               "(def ^:ambient-ok counter \"How many.\" (atom 0))\n"
                               "(defn f \"F does a thing.\" [x] x)\n"
                               "(defn g [x] x)\n"))
        doc (fn [nm] (#'api.reads/form-doc (store/form-named st 'fd.core nm)))]
    (testing "a def with a string VALUE and no docstring has no docstring"
      (is (nil? (doc 'greeting))))
    (testing "a def that really is documented still reports it"
      (is (= "How many." (doc 'counter))))
    (testing "a documented defn reports its docstring"
      (is (= "F does a thing." (doc 'f))))
    (testing "an undocumented defn reports none"
      (is (nil? (doc 'g))))))

(deftest a-browser-apps-own-ADDRESSES-are-published-as-a-document
  ;; `/api/http/paths` answers what the SERVER hands a browser. It cannot
  ;; answer what the browser then does with it: a client-routed app is ONE
  ;; server route and a dozen addresses, and the dozen are the thing a reader
  ;; navigating the app actually sees.
  ;;
  ;; Derived from var METADATA, like every other surface this store publishes.
  ;; It used to read a `:webapp/routes` vector out of the entry fn — a VALUE,
  ;; so a table built in pieces was unreadable and the document carried an
  ;; `:unreadable` key to say so. A page carries its own address now, which
  ;; cannot be half-declared, so that key went with its cause.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:webapp/path \"/things\"} things\n"
                 "  \"Every thing, listed.\"\n"
                 "  [_a _p] [:main \"things\"])\n\n"
                 "(defn ^{:webapp/path \"/things/:id\"} thing\n"
                 "  \"One thing,\n   in detail.\"\n"
                 "  [_a _p] [:main \"thing\"])\n\n"
                 "(defn helper \"Not a page.\" [x] x)\n")
        doc (api.reads/webapp-pages-document (store/ingest (store/empty-store) 'shop.ui src))
        by  (into {} (map (juxt :path identity)) (:paths doc))]

    (testing "one row per declared client ADDRESS, sorted"
      (is (= ["/things" "/things/:id"] (mapv :path (:paths doc))) (pr-str doc)))

    (testing "a row names the function that renders it"
      (is (= 'shop.ui/things (:page (by "/things")))))

    (testing "and what that page IS"
      ;; the column that makes a list of addresses readable: a reader scanning
      ;; them is asking what each one is FOR, and the path alone never says
      (is (= "Every thing, listed." (:doc (by "/things")))))

    (testing "the doc is de-indented, because it is published as markdown"
      (is (= "One thing,\nin detail." (:doc (by "/things/:id")))
          (pr-str (by "/things/:id"))))

    (testing "an unmarked form is not a page"
      (is (not-any? #(= 'shop.ui/helper (:page %)) (:paths doc))))

    (testing "a page that calls an endpoint says WHICH, and calls nothing says nothing"
      ;; the column a reader of a browser app actually wants: not the name of
      ;; the function that computes a url, but the endpoint this screen talks
      ;; to. Graph-derived, so it cannot disagree with the code — and absent
      ;; rather than empty, because an empty vector is a claim
      
      (let [d2 (api.reads/webapp-pages-document
                (-> (store/empty-store)
                    (store/ingest 'shop.api
                                  (str "(ns shop.api)\n\n"
                                       "(def thing \"One.\"\n"
                                       "  {:http/method :get :http/path \"/api/thing/:id\"})\n"))
                    (store/ingest 'shop.pages
                                  (str "(ns shop.pages (:require [shop.api :as api]))\n\n"
                                       "(defn ^{:webapp/path \"/quiet\"} quiet \"Calls nothing.\"\n"
                                       "  [_page] [:main \"quiet\"])\n\n"
                                       "(defn ^{:webapp/path \"/things/:id\"} thing \"A thing.\"\n"
                                       "  [_page] [:main (:http/path api/thing)])\n"))))
            by2 (into {} (map (juxt :path identity)) (:paths d2))]
        ;; both halves in ONE store, so a derivation answering the same thing
        ;; for every page fails here — rather than passing the absent half in a
        ;; fixture that has no endpoints to attribute in the first place
        (is (= [{:endpoint 'shop.api/thing :method :get :path "/api/thing/:id"}]
               (:calls (by2 "/things/:id")))
            (pr-str d2))
        (is (nil? (:calls (by2 "/quiet"))) (pr-str (by2 "/quiet")))))

    (testing "the EMPTY document is the common case a consumer renders most"
      ;; most projects have no browser app at all
      (is (= {:paths []} (api.reads/webapp-pages-document (store/empty-store)))))

    (testing "and there is NO version key on it"
      ;; nothing branched on one. It existed so a consumer could refuse rather
      ;; than misread, and the rule that replaces it is that a document changes
      ;; by RENAMING a key — never by redefining one in place, which is the
      ;; only change a version key could have caught.
      (is (= #{:paths} (set (keys doc))) (pr-str doc)))))

(deftest a-projects-CONFIG-is-ONE-document-filtered-by-PREFIX
  ;; The settings page asked for a per-capability document, and Nathan changed
  ;; it to one: config is already a single flat namespaced keyspace
  ;; (`http.port`, `webapp.enabled`, `http.static./js`), so three documents
  ;; would slice something that is not sliced. `prefix` is how a page that has
  ;; grown too large narrows itself — the same blocks the keys already have.
  ;;
  ;; **Values of CREDENTIAL families are withheld**, and the key still
  ;; publishes. This route is `:http/auth :public` like its siblings, and the
  ;; registry carries `http.auth.static.*`, `http.auth.bearer.*` and
  ;; `http.auth.oidc.*` — token and client-secret families. *This is
  ;; configured and I am not showing you* is a useful answer; *nothing here*
  ;; would be a false one.
  (let [st  (-> (store/empty-store)
                (assoc-in [:config "capabilities" :values "http.port"] "8080")
                (assoc-in [:config "capabilities" :values "webapp.enabled"] "true")
                (assoc-in [:config "capabilities" :values "http.auth.static.tok-abc"] "admins"))
        all (api.reads/config-document st nil)
        by  (fn [doc k] (some #(when (= k (:key %)) %) (:config doc)))]

    (testing "one document, every setting, whatever capability owns it"
      (is (some #(= "http.port" (:key %)) (:config all)) (pr-str (mapv :key (:config all))))
      (is (some #(= "webapp.enabled" (:key %)) (:config all))))

    (testing "a row says what it IS, not just what it holds"
      ;; a reader of a settings page has no other route to the vocabulary
      (let [row (by all "http.port")]
        (is (= "8080" (:value row)) (pr-str row))
        (is (true? (:set row)) (pr-str row))
        (is (= "http" (:owner row)) (pr-str row))
        (is (string? (:doc row)) (pr-str row))))

    (testing "a setting nobody set is present, defaulted, and says so"
      ;; absent rows would make an unconfigured store read as an empty page
      (let [row (by all "http.host")]
        (is (some? row) (pr-str (mapv :key (:config all))))
        (is (not (:set row)) (pr-str row))))

    (testing "a CREDENTIAL family publishes the key and withholds the value"
      (let [row (by all "http.auth.static.tok-abc")]
        (is (some? row) "the key must still appear — set-and-hidden is the answer")
        (is (true? (:secret row)) (pr-str row))
        (is (true? (:set row)) (pr-str row))
        (is (nil? (:value row)) (str "a credential reached the wire: " (pr-str row)))
        (is (nil? (:effective row)) (str "a credential reached the wire: " (pr-str row)))))

    (testing "PREFIX narrows to a block, matching the key's own segments"
      (let [http (api.reads/config-document st "http")]
        (is (every? #(str/starts-with? (:key %) "http.") (:config http))
            (pr-str (mapv :key (:config http))))
        (is (some #(= "http.port" (:key %)) (:config http)))
        (is (not-any? #(= "webapp.enabled" (:key %)) (:config http)))))

    (testing "and a DEEPER prefix narrows further"
      (let [auth (api.reads/config-document st "http.auth")]
        (is (some #(= "http.auth.static.tok-abc" (:key %)) (:config auth)))
        (is (not-any? #(= "http.port" (:key %)) (:config auth)))))

    (testing "the compiled bundle's URL is its OWN field, not a category"
      ;; Asked for as a top-level `:bundle` rather than inside an `:assembly`
      ;; map, and the reason is the container: a category with one key makes a
      ;; scope claim it cannot keep. A reader shown `{:bundle …}` under
      ;; `:assembly` has been told that assembly IS the bundle, so a second
      ;; fact arriving later reads as *the first was incomplete and nobody
      ;; said so*. `:bundle` claims only about the bundle and is complete the
      ;; day it ships.
      ;;
      ;; It is NOT a setting — nothing sets it. `rules.http/bundle-url` joins
      ;; the compile output to the static mount that serves it, so it belongs
      ;; beside the config rather than in it.
      (let [served (-> st
                       (assoc-in [:config "capabilities" :values "http.static./js"]
                                 "public/cljs"))]
        (is (= "/js/main.js" (:bundle (api.reads/config-document served nil)))
            (pr-str (api.reads/config-document served nil)))))

    (testing "and it is ABSENT when no mount reaches the bundle"
      ;; nil is a real answer: a store may serve its bundle from an ENDPOINT
      ;; rather than a mount, which is what slopp's own reviewer UI does.
      ;; Absent rather than nil, so a page renders nothing instead of "null"
      (is (not (contains? (api.reads/config-document st nil) :bundle))
          (pr-str (api.reads/config-document st nil))))

    (testing "a prefix nothing matches is EMPTY rather than everything"
      ;; the failure that turns a filter into a footgun: a typo'd block
      ;; showing the whole page reads as though the filter did not apply
      (is (= [] (:config (api.reads/config-document st "nosuch")))))))

(deftest the-PREFIX-a-page-SENDS-reaches-the-filter
  ;; The document's own filtering is covered above, exhaustively, by calling
  ;; `config-document` with a prefix. That proved the derivation and NOT the
  ;; wiring, and the wiring is where it broke: `config-read` reached into
  ;; `[:params :prefix]` while every sibling read on this surface takes
  ;; `:query-params`. So `?prefix=http.auth` answered all twenty settings —
  ;; the exact failure the document's docstring warns about one paragraph
  ;; earlier, shipped by the test that warns about it.
  ;;
  ;; A filter tested only through its own function is a filter tested with the
  ;; request removed, and the request is the half a consumer supplies. This
  ;; test enters where the consumer does.
  (let [st  (-> (store/empty-store)
                (assoc-in [:config "capabilities" :values "http.port"] "8080")
                (assoc-in [:config "capabilities" :values "webapp.enabled"] "true"))
        ctx {:session (atom {:store st})}
        ask (fn [req] (mapv :key (:config (api.reads/config-read ctx req))))]

    (testing "a prefix on the QUERY STRING narrows, as the endpoint documents"
      (is (every? #(str/starts-with? % "http.")
                  (ask {:query-params {:prefix "http"}}))
          (pr-str (ask {:query-params {:prefix "http"}}))))

    (testing "and narrowing is VISIBLE — fewer rows than the whole document"
      ;; `every?` alone is true of a filter that answers nothing, and true of
      ;; one that answers everything if every key happens to match. The claim
      ;; is that the request CHANGED the answer.
      (is (< (count (ask {:query-params {:prefix "http"}}))
             (count (ask {})))
          "the prefix made no difference — the filter is inert"))

    (testing "no prefix is the whole document, not an empty one"
      (is (some #{"webapp.enabled"} (ask {}))))

    (testing "a prefix nothing matches is empty THROUGH the request too"
      (is (= [] (ask {:query-params {:prefix "nosuch"}}))))))
