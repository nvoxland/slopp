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
  "The headless-review gate (D-web): a `^:app/entry` entry — the fn
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
  tool's `ns-publics` scan, so the store answers \"no ^:app/entry\" while
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
                                            (:app/entry (store/form-name-meta f)))]
                             f))]
       (str ns-sym " carries ^:app/entry on a nameless form (head: "
            (first (store/form-sexpr (:node bad)))
            ") — the marker must sit on a zero-arg public defn. A defmethod"
            " DISCARDS name metadata at macroexpansion, so the marker would"
            " land on no var and nothing could ever find this page. Mark the"
            " zero-arg defn that builds the app instead.")))
   (when-let [e (and form-name
                     (store/form-named candidate (symbol (str ns-sym))
                                       (symbol (str form-name))))]
     (when (:app/entry (store/form-name-meta e))
       (let [sexpr  (store/form-sexpr (:node e))
             head   (first sexpr)
             others (for [n     (keys (:namespaces candidate))
                          f     (store/forms candidate n)
                          :let  [nm (:name f)]
                          :when (and nm
                                     (:app/entry (store/form-name-meta f))
                                     (not (and (= n (symbol (str ns-sym)))
                                               (= nm (symbol (str form-name))))))]
                      (str n "/" nm))]
         (cond
           (= :cljs (store/platform-for candidate (symbol (str ns-sym))))
           (str ns-sym "/" form-name " is marked ^:app/entry in a :cljs namespace,"
                " so no JVM can open this app — and a headless test can then only"
                " drive a hand-built lookalike, which passes while the real screen"
                " is wrong. Move the entry (and the routing, derive and view code"
                " it reaches) to a :jvm or :cljc namespace, and pass the browser"
                "-shaped parts IN: :fetch, :render, a url pusher. The wiring is"
                " portable; only the effects are :cljs.")

           (not= 'defn head)
           (str ns-sym "/" form-name " carries ^:app/entry on a " head
                " — the marker must sit on a zero-arg public defn."
                (case head
                  defn-     (str " A private page is invisible to the tool's"
                                 " ns-publics scan, so the store would answer"
                                 " \"no ^:app/entry\" while carrying one — a"
                                 " confident wrong answer.")
                  def       (str " A def's arity cannot be read from the stored"
                                 " form, so slopp cannot promise the zero-arg"
                                 " call it opens pages with.")
                  "")
                " Mark the zero-arg defn that builds the app instead.")

           (:private (store/form-name-meta e))
           (str ns-sym "/" form-name " is marked ^:app/entry but ^:private —"
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
           (str ns-sym "/" form-name " is marked ^:app/entry but has no zero"
                " arity, and slopp opens it by calling it with none — there is"
                " nobody to pass arguments. Read what it needs from config or"
                " from the store instead, so the entry answers to slopp and to"
                " your own shell the same way.")

           (seq others)
           (str ns-sym "/" form-name " is a SECOND ^:app/entry in this store —"
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

(defn ^:export ^{:rule/applies-to :production} webapp-page-address
  "The page-address gate: a `^{:webapp/path …}` form whose address cannot
  work is refused at the write. Inert until `webapp.enabled`, which
  `edit.gates/gate-check` decides — not this gate. Returns a teaching string,
  or nil when clean.

  A page carries its own address now, so the address needs the gates an
  address has always had. Three ways one silently renders nothing, and none of
  them fails loudly:

  - **a pattern the router has no rule for** — `*name`, `*.css`, a wildcard in
    the middle. `slopp.http.router/match` is pure, so it contributes no rows
    and the page is simply never reached.
  - **a PRIVATE page** — the generated browser entry names the var, and it
    cannot name what it cannot see.
  - **a SECOND claim on one address** — the browser matches one of them and
    the other is unreachable with nothing to say why. The same question
    `http-route-collision` asks about a server route; the answer matters more
    here, because a client route has no 404 to notice.

  The same form RE-LANDING is not a collision, which is the replace case."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [m    (store/form-name-meta e)
          path (:webapp/path m)
          ;; `defn-` puts privacy in the HEAD, not in the name's metadata — a
          ;; stored form has never been evaluated, so the `:private true` the
          ;; macro would attach is not there to read. Both spellings, or the
          ;; arm passes on the one authors actually write.
          head (first (try (store/form-sexpr (:node e)) (catch Exception _ nil)))]
      (when (string? path)
        (let [segs (vec (remove str/blank? (str/split path #"/")))
              bad  (first (for [[i s] (map-indexed vector segs)
                                :when (and (str/includes? s "*")
                                           (or (not (#{"*" "**"} s))
                                               (not= i (dec (count segs)))))]
                            s))
              other (some (fn [[nsx nm p id]]
                            (when (and (= p path) (not= id (:id e)))
                              (symbol (str nsx) (str nm))))
                          (for [nsx (keys (:namespaces candidate))
                                x   (store/forms candidate nsx)
                                :when (:name x)
                                :let [pm (:webapp/path (store/form-name-meta x))]
                                :when (string? pm)]
                            [nsx (:name x) pm (:id x)]))]
          (cond
            bad
            (str ns-sym "/" form-name " declares the page address " path
                 " — \"" bad "\" is not a pattern the router has, so this page"
                 " is never reached and nothing says why. The grammar is"
                 " `:name` (one segment, captured), `*` (exactly one segment)"
                 " and `**` (zero or more), with both wildcards ANONYMOUS and"
                 " at the END only — a named splat like *path is now `**`, and"
                 " there is no partial-segment globbing")

            (or (:private m) (= 'defn- head))
            (str ns-sym "/" form-name " is marked :webapp/path but ^:private —"
                 " the generated browser entry names this var to route to it,"
                 " and it cannot name what it cannot see. Make it public, or"
                 " drop the marker if this is a helper rather than a page")

            other
            (str ns-sym "/" form-name " claims the page address " path
                 " but " other " already answers there — the browser matches"
                 " ONE of them and the other is unreachable with nothing to"
                 " say why, because a client route has no 404 to notice."
                 " Change the address, or extend the page that has it")))))))

(defn ^:export ^{:rule/applies-to :production} webapp-client-routes-retired
  "The retired-spelling gate: `^{:webapp/client-routes [\"/store\"]}` is refused
  at the write. Inert until `webapp.enabled`, which `edit.gates/gate-check`
  decides. Returns a teaching string, or nil when clean.

  **The marker no longer serves anything.** It used to make a client-routed
  document contribute one generated catch-all row per prefix, so a refreshed
  deep link reached the app. `D-page-marker` replaced that with a shell
  declaring its OWN `**` path: the fallback IS the declaration, and no row
  appears in the table that nobody wrote.

  What did not move was the marker's ability to be WRITTEN. So a store went on
  declaring it, the parse succeeded, and NOTHING was generated — measured on
  the only app with client-side routing, where sixteen deep links 404d on a
  refresh while an in-app click to the same address worked. That shape hides:
  the only way to reach it is the way that works.

  **It is worse than an inert marker, because it also feeds link validation.**
  `rules.http/dangling-route-refs` treats a declared prefix as covering the
  links below it, so the declaration did not merely fail to serve — it
  reported the unserved links as fine. A marker that waives nothing while
  reading as though it does is worse than its absence; one that waives a CHECK
  it can no longer honour is worse again.

  Refused rather than swept at `done`, because the failure it prevents has no
  other symptom: a client route that 404s only on a hard load is invisible from
  inside the app."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [m (store/form-name-meta e)]
      (when (contains? m :webapp/client-routes)
        (str ns-sym "/" form-name " declares :webapp/client-routes "
             (pr-str (:webapp/client-routes m))
             " — that marker is RETIRED and generates nothing, so every"
             " address under those prefixes 404s on a hard load while an"
             " in-app click to the same address works. Declare the shell's own"
             " wildcard instead: ^{:http/path \"/store/**\" :webapp/shell true}"
             " on the document, and the fallback IS the declaration — there is"
             " no prefix list left to keep in step with it.")))))
