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
  ;; navigating the app actually sees. Those live inside the `^:app/entry`
  ;; fn's `:webapp/routes` table, which is why this document is derived from
  ;; the STORE while its three siblings are derived from the loaded image —
  ;; nothing on a loaded var says what the table holds without calling it.
  ;;
  ;; Derived through `rules.webapp/webapp-report` rather than re-traversed
  ;; here. That is the same reading `query_surface` shows, so the tool and the
  ;; document cannot start answering differently — a second copy of a
  ;; traversal is how they would.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn things \"Every thing, listed.\" [_s] [:p \"things\"])\n"
                 "(defn thing-request \"R.\" [_p] {:webapp/path \"/api/thing/:id\"})\n"
                 "(defn thing\n  \"One thing,\n   in detail.\"\n  [_s] [:p \"thing\"])\n\n"
                 "(defn ^:app/entry app \"A.\" []\n"
                 "  {:webapp/routes [[\"/things\" things]\n"
                 "                   [\"/things/:id\" {:render thing :request thing-request}]]})\n")
        on  (assoc-in (store/ingest (store/empty-store) 'shop.ui src)
                      [:config "capabilities" :values "webapp.enabled"] "true")
        doc (api.reads/webapp-routes-document on)
        by  (into {} (map (juxt :path identity)) (:routes doc))]

    (testing "versioned, one row per declared client ADDRESS"
      (is (= 1 (:slopp/webapp-routes-version doc)))
      (is (= ["/things" "/things/:id"] (mapv :path (:routes doc)))
          (pr-str doc)))

    (testing "a row names what renders it, and what that screen IS"
      ;; the column that made the pages section readable: a reader scanning
      ;; addresses is asking what each one is FOR, and only the render fn's
      ;; docstring answers that
      (is (= 'shop.ui/things (:screen (by "/things"))))
      (is (= "Every thing, listed." (:doc (by "/things")))))

    (testing "the doc is de-indented, because it is published as markdown"
      ;; a docstring carries its SOURCE indentation, and a markdown renderer
      ;; reads four leading spaces as a code block
      (is (= "One thing,\nin detail." (:doc (by "/things/:id")))
          (pr-str (by "/things/:id"))))

    (testing "and what the screen LOADS, as the url rather than the var"
      (is (= 'shop.ui/thing-request (:request (by "/things/:id"))))
      (is (= "/api/thing/:id" (:loads (by "/things/:id")))))

    (testing "no :kind on a row — the whole document is one kind"
      ;; `query_surface` rows carry it so a renderer can draw a screen beside
      ;; a command; a document that lists nothing else has nothing to tell apart
      (is (not-any? :kind (:routes doc)) (pr-str (:routes doc))))

    (testing "the EMPTY document is the common case a consumer renders most"
      ;; most projects have no browser app at all, and one that has the
      ;; capability off must not be DESCRIBED as having one
      (doseq [st [(store/empty-store)
                  (store/ingest (store/empty-store) 'shop.ui src)]]
        (let [d (api.reads/webapp-routes-document st)]
          (is (= 1 (:slopp/webapp-routes-version d)))
          (is (= [] (:routes d)) (pr-str d))
          (is (= [] (:unreadable d)) (pr-str d)))))))

(deftest a-declaration-slopp-cannot-READ-is-named-rather-than-silently-dropped
  ;; The reason this document carries a second key at all. A big app builds its
  ;; route table in pieces and names a var where the literal would go, and only
  ;; a literal can be read from the store. Dropping those rows silently would
  ;; publish an app with fewer screens than it has — and an empty `:routes` is
  ;; an affirmative claim of emptiness, not an absence, so nothing in the
  ;; document would distinguish it from a store that declares no screens.
  ;;
  ;; `:unreadable` is EVERY `:webapp/*` declaration the reader could not take
  ;; literally, not only the route ones: routes, actions and session loads come
  ;; off one traversal, and splitting the message list by sniffing its strings
  ;; would be a second thing to keep in step. Over-reporting in a diagnostics
  ;; key is the safe direction; under-reporting is the failure being fixed.
  (let [src (str "(ns shop.two)\n\n"
                 "(def table [[\"/things\" identity]])\n\n"
                 "(defn ^:app/entry app \"A.\" [] {:webapp/routes table})\n")
        doc (api.reads/webapp-routes-document
             (assoc-in (store/ingest (store/empty-store) 'shop.two src)
                       [:config "capabilities" :values "webapp.enabled"] "true"))]
    (is (= [] (:routes doc)) (pr-str doc))
    (is (seq (:unreadable doc)) (pr-str doc))
    (is (some #(re-find #"shop\.two" %) (:unreadable doc)) (pr-str doc))
    (is (every? string? (:unreadable doc))
        (str "a consumer renders these as a caveat line, so they must be"
             " sentences rather than a shape it has to learn: " (pr-str doc)))))
