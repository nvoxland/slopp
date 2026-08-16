(ns slopp.edit.webapp-test
  "The browser app's WRITE gates, driven through real sessions.

  Every fixture here enables `webapp` rather than `http`, and that is the
  subject rather than setup: these rules moved off `http` in wave 4 because
  serving HTML and running an app in the page are different kinds of project,
  and a store that does the first should not be graded on the second. Enabling
  `webapp` turns `http` on with it — `webapp` requires being served — so the
  fixtures say the narrower thing and get the broader one.

  Neighbours: `slopp.rules.webapp-test` covers the done-grain half." (:require [clojure.test :refer [deftest is testing]] [slopp.ops :as ops] [slopp.ops.external :as external] [slopp.store :as store]))

(deftest ^:external a-page-the-jvm-cannot-open-refuses-at-the-write
  ;; The architecture rule, enforced rather than suggested. `^:web/page` marks
  ;; the entry the fake browser opens an app through — and in a :cljs namespace
  ;; that entry cannot be CALLED from a JVM, so every headless test has to
  ;; hand-build a map that RESEMBLES the app.
  ;;
  ;; A resemblance drifts silently, and it drifts in the one direction that
  ;; costs the most: the lookalike keeps passing while the real screen is
  ;; wrong. That is the whole defect the fake browser exists to remove, so
  ;; letting it back in through the entry point is not a small compromise.
  ;;
  ;; A MARKER rather than a capability key, deliberately. A capability naming a
  ;; var is an asserted relation that drifts the day the var is renamed; a
  ;; marker cannot, because it IS the var. And a write gate needs a moment to
  ;; fire — with a marker there is one, and it names the right form.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "the browser owns routing here — this turns http on with it")
      (ops/ingest! sess 'ui.shell "(ns ui.shell)\n\n(defn seed \"S.\" [x] x)\n")
      (ops/module-platform! sess "ui.shell" "cljs"
                            :prompt "the browser shell is :cljs by nature")

      (testing "a page marked in a :cljs namespace refuses, and says what it costs"
        (let [r (ops/add-form! sess 'ui.shell
                               "(defn ^:web/page app \"A.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "the entry, in the wrong place")]
          (is (re-find #"(?i)cljs" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.shell 'app))
              "and the form did not land — a refusal that writes anyway teaches nothing")))

      (testing "the same page in a portable namespace lands"
        (ops/ingest! sess 'ui.app "(ns ui.app)\n\n(defn seed \"S.\" [x] x)\n")
        (let [r (ops/add-form! sess 'ui.app
                               "(defn ^:web/page app \"A.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "the entry, where a JVM can call it")]
          (is (nil? (:error r)) (pr-str r))))

      (testing "and an UNMARKED form in a :cljs namespace is none of the gate's business"
        (let [r (ops/add-form! sess 'ui.shell
                               "(defn mount \"M.\" [] :ok)"
                               :prompt "ordinary browser code")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external a-page-marker-that-cannot-be-opened-refuses
  ;; The gate already refuses a ^:web/page in a :cljs namespace. Two more ways
  ;; to mark one slopp cannot open, both of which would otherwise fail LATER
  ;; and somewhere else.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "the browser owns routing here — this turns http on with it")
      (ops/ingest! sess 'ui.core "(ns ui.core)\n\n(defn seed \"S.\" [x] x)\n")

      (testing "the entry must take NO arguments — slopp calls it, so there is nobody to pass one"
        (let [r (ops/add-form! sess 'ui.core
                               "(defn ^:web/page app \"A.\" [opts] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "an entry that wants configuring")]
          (is (re-find #"no zero arity" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.core 'app)))))

      (testing "a zero-arg entry lands"
        (let [r (ops/add-form! sess 'ui.core
                               "(defn ^:web/page app \"A.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "the entry")]
          (is (nil? (:error r)) (pr-str r))))

      (testing "a SECOND marker refuses, naming the first"
        ;; the tool scans for the marker and takes what it finds; two of them
        ;; means it answers from whichever the scan reached first, silently,
        ;; and a screen from the wrong app is worse than no screen
        (let [r (ops/add-form! sess 'ui.core
                               "(defn ^:web/page other \"O.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "a second entry")]
          (is (re-find #"ui\.core/app" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.core 'other)))))
      (finally (ops/close! sess)))))

(deftest ^:external the-page-marker-sits-on-a-zero-arg-public-defn
  ;; Review B-F2/F3: the gate graded the shape its author imagined. A
  ;; `(def ^:web/page app 42)` passed ("takes arguments" cannot fire on a def)
  ;; and CCE'd at drive time; a defmethod DISCARDS name metadata at
  ;; macroexpansion so its marker lands on nothing; a `defn-` page passed the
  ;; gate while being invisible to the tool's ns-publics scan — the store
  ;; answering "no ^:web/page" while carrying a gate-approved one is a
  ;; confident wrong answer. And the strict direction was wrong too: a
  ;; multi-arity entry WITH a zero arity was refused for arguments slopp
  ;; never passes.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "the browser owns routing here — this turns http on with it")
      (ops/ingest! sess 'ui.core "(ns ui.core)\n\n(defn seed \"S.\" [x] x)\n")

      (testing "a def carrier is refused — its arity cannot be read from the form"
        (let [r (ops/add-form! sess 'ui.core "(def ^:web/page app 42)"
                               :prompt "marker on a def")]
          (is (re-find #"zero-arg" (str (:error r))) (pr-str r))))

      (testing "a defmethod carrier is refused — the marker is discarded at macroexpansion"
        (let [r0 (ops/add-form! sess 'ui.core "(defmulti route \"R.\" :k)"
                                :prompt "a multi to hang the method on")
              r  (ops/add-form! sess 'ui.core "(defmethod ^:web/page route :home [x] x)"
                                :prompt "marker on a defmethod")]
          (is (nil? (:error r0)) (pr-str r0))
          (is (re-find #"defmethod" (str (:error r))) (pr-str r))))

      (testing "a private page is refused — invisible to the tool's scan"
        (let [r (ops/add-form! sess 'ui.core
                               "(defn- ^:web/page hidden \"H.\" [] {:state (atom {}) :view (fn [_] [:div])})"
                               :prompt "marker on a private defn")]
          (is (re-find #"(?i)private" (str (:error r))) (pr-str r))))

      (testing "a multi-arity entry WITH a zero arity lands — slopp can call it with none"
        (let [r (ops/add-form! sess 'ui.core
                               (str "(defn ^:web/page app \"A.\""
                                    " ([] {:state (atom {}) :view (fn [_] [:div])})"
                                    " ([x] x))")
                               :prompt "zero arity exists, so the refusal's rationale does not apply")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest ^:external a-FUNCTION-handler-on-an-input-refuses-at-the-write
  ;; `slopp.web.screen/fill!` already states this, as a paragraph an author
  ;; reads or does not:
  ;;
  ;;   A FUNCTION handler on an input cannot be portable … in a browser it
  ;;   receives a DOM event, and reading a value out of one is interop.
  ;;
  ;; It is the sharpest failure in the whole capability, because it is the one
  ;; where the tools LIE. Headless, `fill!` hands a function handler a
  ;; best-effort `{:value v :target {:value v}}`; in a browser the same handler
  ;; gets a real DOM event, whose value lives behind `(.. e -target -value)`.
  ;; A handler written against either passes every test and does nothing in
  ;; production — a green suite over broken code, which is worse than no suite.
  ;;
  ;; The DATA form has no such gap and now has no excuse: slopp owns the
  ;; dispatcher, so `slopp.webapp.dom` normalises the event to a scalar once,
  ;; for every app, and the app's interpreter never sees an event of any shape.
  ;; Advice became structure, so the paragraph becomes a refusal.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "webapp.enabled" :value "true"
                        :prompt "the browser owns routing here")
      (ops/ingest! sess 'ui.view "(ns ui.view)\n\n(defn seed \"S.\" [x] x)\n")

      (testing "a replicant :on map with a function on an input refuses"
        (let [r (ops/add-form! sess 'ui.view
                               (str "(defn field \"F.\" [s]"
                                    " [:input {:value (:q s)"
                                    "          :on {:input (fn [e] (prn e))}}])")
                               :prompt "the handler that works in exactly one of the two")]
          (is (re-find #"(?i)scalar|data" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.view 'field))
              "a refusal that writes anyway teaches nothing")))

      (testing "the reagent spelling refuses too — it is the same defect"
        ;; `fill!` tries both event names because real apps write both; a gate
        ;; that knew one of them would send half its readers away reassured
        (let [r (ops/add-form! sess 'ui.view
                               "(defn field \"F.\" [s] [:input {:on-change #(prn %)}])"
                               :prompt "reagent's spelling of the same mistake")]
          (is (some? (:error r)) (pr-str r))))

      (testing "a NAMED function is no better, and looks more responsible"
        (let [r (ops/add-form! sess 'ui.view
                               "(defn field \"F.\" [s] [:input {:on {:input handle-typing}}])"
                               :prompt "extracted, and still receives a DOM event")]
          (is (some? (:error r)) (pr-str r))))

      (testing "the DATA form lands — it is the portable one"
        (let [r (ops/add-form! sess 'ui.view
                               (str "(defn field \"F.\" [s]"
                                    " [:input {:value (:q s)"
                                    "          :on {:input [:query/typed]}}])")
                               :prompt "the action, and slopp supplies the value")]
          (is (nil? (:error r)) (pr-str r))))

      (testing "and a function on a BUTTON is none of this gate's business"
        ;; a click carries no value to read off the event, so there is nothing
        ;; for the two drivers to disagree about. Refusing it would be a rule
        ;; about style rather than about portability
        (let [r (ops/add-form! sess 'ui.view
                               "(defn go \"G.\" [] [:button {:on {:click (fn [_] :ok)}} \"Go\"])"
                               :prompt "a click handler, which is portable")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))
