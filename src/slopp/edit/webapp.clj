(ns slopp.edit.webapp
  "Write gates for the app that runs IN THE PAGE — the browser owning routing
  and state — as opposed to the server that sends it.

  The distinction is the whole reason this namespace exists rather than more
  forms under `slopp.edit.http`. Serving HTML needs only `http`; a store whose
  browser owns navigation is a different kind of application, and grading it on
  page rules it never opted into is the R6 failure one capability over. An app
  that serves documents and runs no browser app carries none of these
  declarations and is asked nothing about them.

  Everything here is answerable from a store VALUE, with no image and no eval —
  these run against the CANDIDATE store, the value a write WOULD produce, so a
  violation is refused before it lands.

  Markers rather than config keys: a capability naming a var is an asserted
  relation that goes stale the day the var is renamed, and a marker cannot,
  because it IS the var.

  Neighbours: `slopp.edit.gates` is the chassis that registers and dispatches
  every gate (register a new one there, never at the N call sites) and derives
  this file's capability from its last segment; `slopp.rules.webapp` holds the
  done-grain half, which sees a whole episode where these see one form."
  (:require [clojure.string :as str]
            [slopp.store :as store]
            [slopp.edit.modules :as edit.modules] [rewrite-clj.node :as n]))

(defn ^:export ^{:rule/applies-to :production} webapp-page-unreachable
  "The headless-review gate (D-web): a `^:web/page` entry — the fn
  `slopp.cljnx` opens an app through — must be one slopp can actually
  call AND FIND. Returns a teaching string, or nil when clean. Five ways it
  cannot be:

  **In a `:cljs` namespace.** No JVM can call it there, so every headless test
  falls back to hand-building a map that RESEMBLES the app — and a resemblance
  drifts in the worst direction, passing while the real screen is wrong. That
  is the defect the fake browser exists to remove, so letting it back in
  through the entry point is the whole thing undone.

  **Not a public `defn`.** The review measured each wrong carrier passing a
  defn-shaped check: a `def`'s arity cannot be read from the stored form, so
  slopp cannot promise the zero-arg call it opens pages with; a `defmethod`
  DISCARDS name metadata at macroexpansion, so the marker never lands on any
  var and nothing can ever find the page — and it is also NAMELESS in the
  store (it names its TARGET, not itself), so the nameless arm below is the
  only lookup that can reach it at all; a private one is invisible to the
  tool's `ns-publics` scan, so the store answers \"no ^:web/page\" while
  carrying a gate-approved page — a confident wrong answer.

  **No zero arity.** slopp calls the entry with nothing, because there is
  nobody to pass anything — a config the entry needs is config the entry
  should read. A multi-arity entry WITH a zero arity is fine: the refusal's
  own rationale does not apply to it.

  **A SECOND one.** The tool finds the entry by scanning for the marker, so two
  of them means it answers from whichever the scan reached first, silently. A
  screen from the wrong app is worse than no screen at all, and the reader has
  no way to tell.

  The rule generalises past this marker and is worth stating plainly: **the
  wiring is portable, only the effects are `:cljs`.** Routing, derive and view
  code decide what a screen SAYS and must run anywhere; `js/fetch`, mounting
  into the DOM and pushing history are the parts that genuinely need a browser,
  and they arrive as arguments.

  A MARKER rather than a capability key, deliberately: a capability naming a
  var is an asserted relation that goes stale the day the var is renamed, and a
  marker cannot, because it IS the var.

  Whether this store opted into `webapp` at all is `edit.gates/gate-check`'s
  question, answered from the namespace this gate lives in — see
  `gate-capability`."
  [candidate ns-sym form-name]
  (or
   ;; the NAMELESS arm: a defmethod names its target, not itself, so the
   ;; store holds it unnamed and no form-named lookup can ever reach it —
   ;; the named arm below was silent for exactly the carrier whose marker
   ;; is doubly dead (discarded at macroexpansion AND unfindable by name)
   (when (nil? form-name)
     (when-let [bad (first (for [f     (store/forms candidate (symbol (str ns-sym)))
                                 :when (and (nil? (:name f))
                                            (:web/page (store/form-name-meta f)))]
                             f))]
       (str ns-sym " carries ^:web/page on a nameless form (head: "
            (first (store/form-sexpr (:node bad)))
            ") — the marker must sit on a zero-arg public defn. A defmethod"
            " DISCARDS name metadata at macroexpansion, so the marker would"
            " land on no var and nothing could ever find this page. Mark the"
            " zero-arg defn that builds the app instead.")))
   (when-let [e (and form-name
                     (store/form-named candidate (symbol (str ns-sym))
                                       (symbol (str form-name))))]
     (when (:web/page (store/form-name-meta e))
       (let [sexpr  (store/form-sexpr (:node e))
             head   (first sexpr)
             others (for [n     (keys (:namespaces candidate))
                          f     (store/forms candidate n)
                          :let  [nm (:name f)]
                          :when (and nm
                                     (:web/page (store/form-name-meta f))
                                     (not (and (= n (symbol (str ns-sym)))
                                               (= nm (symbol (str form-name))))))]
                      (str n "/" nm))]
         (cond
           (= :cljs (store/platform-for candidate (symbol (str ns-sym))))
           (str ns-sym "/" form-name " is marked ^:web/page in a :cljs namespace,"
                " so no JVM can open this app — and a headless test can then only"
                " drive a hand-built lookalike, which passes while the real screen"
                " is wrong. Move the entry (and the routing, derive and view code"
                " it reaches) to a :jvm or :cljc namespace, and pass the browser"
                "-shaped parts IN: :fetch, :render, a url pusher. The wiring is"
                " portable; only the effects are :cljs.")

           (not= 'defn head)
           (str ns-sym "/" form-name " carries ^:web/page on a " head
                " — the marker must sit on a zero-arg public defn."
                (case head
                  defn-     (str " A private page is invisible to the tool's"
                                 " ns-publics scan, so the store would answer"
                                 " \"no ^:web/page\" while carrying one — a"
                                 " confident wrong answer.")
                  def       (str " A def's arity cannot be read from the stored"
                                 " form, so slopp cannot promise the zero-arg"
                                 " call it opens pages with.")
                  "")
                " Mark the zero-arg defn that builds the app instead.")

           (:private (store/form-name-meta e))
           (str ns-sym "/" form-name " is marked ^:web/page but ^:private —"
                " the tool finds pages via ns-publics, so a private page is"
                " one the store denies having while the gate approved it."
                " Make the entry public.")

           ;; from the SEXPR, not from metadata: :arglists is attached by `defn` at
           ;; EVAL time and a stored form has never been evaluated, so reading
           ;; it here answers nil for every entry and the check passes
           ;; vacuously — which is exactly how it first shipped green over a
           ;; fixture built to violate it. `not-any? empty?` and not `some seq`:
           ;; a multi-arity entry WITH a zero arity is one slopp can call.
           (let [arglists (edit.modules/fn-arglists sexpr)]
             (and (seq arglists) (not-any? empty? arglists)))
           (str ns-sym "/" form-name " is marked ^:web/page but has no zero"
                " arity, and slopp opens it by calling it with none — there is"
                " nobody to pass arguments. Read what it needs from config or"
                " from the store instead, so the entry answers to slopp and to"
                " your own shell the same way.")

           (seq others)
           (str ns-sym "/" form-name " is a SECOND ^:web/page in this store —"
                " " (str/join ", " others) " already carries it. The tool finds"
                " the entry by scanning for the marker, so two of them means it"
                " answers from whichever it reaches first, silently, and a"
                " screen from the wrong app is worse than no screen. Keep one"
                " entry and let it branch.")))))))

(defn ^:export ^{:rule/applies-to :production} webapp-portable-handler
  "The port gate for EVENTS: a value-carrying control may not hand its handler a
  function. Returns a teaching string, or nil when clean.

  **This is the one place slopp's own tools lie, and the gate exists to close
  that.** Headless, `slopp.cljnx/fill!` hands a function handler a
  best-effort `{:value v :target {:value v}}`. In a browser, replicant hands the
  same function a real DOM event, whose value lives behind `(.. e -target
  -value)` — interop, which cannot run on a JVM at all. A handler written
  against either shape passes every test and does nothing in production. A green
  suite over broken code is worse than no suite, and `fill!`'s own docstring
  says so, as a paragraph an author reads or does not.

  **What changed is that the alternative stopped costing anything.** The DATA
  form — `{:on {:input [:query/typed]}}` — reaches the app as `(action value)`
  with the value already a scalar, because slopp owns the dispatcher now:
  `slopp.webapp.dom` reads it off the event once, for every app. It used to be
  advice that each app followed by hand or did not; it is structure, so the
  paragraph can become a refusal.

  **Scoped to controls that carry a VALUE** — `input`, `select`, `textarea` —
  and that scope is the whole content of the rule rather than caution. A click
  carries nothing to read off the event, so the two drivers have nothing to
  disagree about and a function there is perfectly portable. Refusing it would
  be a rule about style wearing this one's clothes.

  Both spellings, because real apps write both: replicant's `:on {:input …}` and
  reagent's `:on-change`. A gate that knew one of them would send half its
  readers away reassured.

  Whether this store opted into `webapp` at all is `edit.gates/gate-check`'s
  question, answered from the namespace this gate lives in."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [;; a hiccup tag may carry classes and an id — :input.field#q — so the
          ;; element is the leading segment rather than the whole keyword
          control? (fn [tag]
                     (and (keyword? tag)
                          (contains? #{"input" "select" "textarea"}
                                     (first (str/split (name tag) #"[.#]")))))
          ;; DATA is a vector: the action, verbatim, as a dispatcher switches on.
          ;; Everything else that can be called — an (fn …), a #(…) (which reads
          ;; as fn*), a bare symbol naming one — is the defect, and the symbol
          ;; case is the one worth catching because extracting a handler looks
          ;; like the responsible move
          fn-ish?  (fn [v] (and (some? v) (not (vector? v))))
          hit      (first
                    (for [v     (tree-seq coll? seq (try (n/sexpr (:node e))
                                                         (catch Exception _ nil)))
                          :when (and (vector? v) (control? (first v)) (map? (second v)))
                          :let  [attrs (second v)
                                 on    (:on attrs)]
                          [k h] (concat (select-keys attrs [:on-change])
                                        (when (map? on)
                                          (select-keys on [:input :change])))
                          :when (fn-ish? h)]
                      [(first v) k]))]
      (when hit
        (str ns-sym "/" form-name " gives " (first hit) " a FUNCTION handler on "
             (second hit) " — which is the one shape slopp's own tools disagree"
             " about. In a browser it receives a DOM event and the typed text is"
             " behind (.. e -target -value); headless, screen/fill! hands it a"
             " best-effort map. So it passes every test and does nothing in"
             " production. Use the data form — {:on {:input [:your/action]}} —"
             " and the value arrives as a SCALAR through your :webapp/act,"
             " because slopp's browser shim normalises the event once for every"
             " app. A function on a control that carries no value (a button) is"
             " portable and this gate ignores it.")))))
