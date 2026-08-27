(ns slopp.api.reads-test
  "The store browser through the PORTLESS pipeline: route → policy →
  declared reads → handler, against an in-memory fixture store. The
  escaping assertion is a SECURITY test — the browser renders arbitrary
  store source."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api.reads :as api.reads]))

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

    (testing "the EMPTY document is the common case a consumer renders most"
      ;; most projects have no browser app at all
      (is (= {:paths []} (api.reads/webapp-pages-document (store/empty-store)))))

    (testing "and there is NO version key on it"
      ;; nothing branched on one. It existed so a consumer could refuse rather
      ;; than misread, and the rule that replaces it is that a document changes
      ;; by RENAMING a key — never by redefining one in place, which is the
      ;; only change a version key could have caught.
      (is (= #{:paths} (set (keys doc))) (pr-str doc)))))
