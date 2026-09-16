(ns slopp.webdev.cljs
  "The client build: ClojureScript compiled ON THE JVM, and the typed client
  generated from the endpoints' own contracts.

  This is where `:cljs` code gets its only verification. Such a namespace
  never loads into the oracle — it references `js/*` — so its write lands
  `:unverified` and COMPILE-ERROR-AS-ORACLE stands in: the real compiler runs
  here, and analyzer warnings and hard failures are anchored back to the
  owning store form, name-addressed, no file:line. Keeping `.cljs` thin and
  platform-neutral logic in `.cljc` is what keeps that trade honest.

  Declared JavaScript (`js_dep`) reaches the compiler as **`deps.cljs` in the
  materialized project**, not as a compiler option — `write-foreign-libs!`
  says why at length, and the short version is that the alternative meant
  changing a multimethod's arity, which a running image cannot absorb. Do not
  simplify it back into `compile-client*`'s signature.

  The compile backend is a multimethod on the store's configured compiler, so
  cherry/squint slot in as new methods without re-authoring a single form."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [slopp.store.render :as store.render] [slopp.build :as build] [slopp.ops.external :as external] [slopp.ops.testrun :as testrun] [slopp.image.repl :as repl] [slopp.store :as store] [slopp.ops.engine :as engine] [clojure.java.io :as io] [slopp.edit :as edit] [slopp.store.artifacts :as artifacts] [slopp.http.client :as http.client] [slopp.edit.http :as edit.http] [slopp.project.capabilities :as capabilities] [slopp.rules.http :as rules.http]))

(def result-marker
  "The line prefix the cljs compile runner prints its EDN summary behind, so the
  parent can extract a machine-readable result from arbitrary compiler chatter."
  "SLOPP-CLJS-RESULT ")

(defn parse-result
  "Extract the cljs compile runner's EDN summary from its captured `output` — the
  text after the `result-marker` line. Returns {:warnings [...] :error ...}, or
  nil when the marker is absent (the runner JVM died before printing, which the
  caller reports as a compile failure)."
  [output]
  (some->> (str/split-lines (str output))
           (some #(when (str/starts-with? % result-marker)
                    (subs % (count result-marker))))
           edn/read-string))

(defn anchor-warnings
  "Anchor cljs compile findings to store FORMS: each `{:ns :line :message ...}`
  becomes `{:form qsym :at snippet :type ... :message ...}` via
  render/owner-form — so a cljs warning reads like a clj compile error
  (name-addressed, no file:line; the anchor discipline). A finding whose ns/line
  don't resolve keeps its message but carries no form anchor."
  [store warnings]
  (vec
   (for [w warnings
         :let [nsx  (some-> (:ns w) symbol)
               row  (:line w)
               ok?  (boolean (and nsx row (contains? (:namespaces store) nsx)))
               e    (when ok? (store.render/owner-form store nsx row 1))
               at   (when ok? (nth (str/split-lines (store.render/render-ns store nsx))
                                   (dec row) nil))]]
     (cond-> {:type (:type w) :message (:message w)}
       e  (assoc :form (symbol (str nsx) (str (or (:name e) (:id e)))))
       at (assoc :at (str/trim at))))))

(defn runner-src
  "The self-contained Clojure program (a `-e` string) run in the :cljs-alias JVM
  to compile the materialized client namespaces. It binds a warning handler that
  collects analyzer warnings ({:type :line :ns :message}), catches hard errors,
  and prints an EDN summary behind `result-marker` that parse-result reads. The
  cljs compiler reads whatever .cljs/.cljc it finds under `src/` and `cljs-src/`
  (its .clj siblings are invisible to it). :simple yields one self-contained JS."
  [{:keys [output-to output-dir optimizations foreign-libs]
    :or {output-to "out/main.js" output-dir "out" optimizations :simple}}]
  (str
   "(require '[cljs.build.api :as b] '[cljs.analyzer :as ana])\n"
   "(def ws (atom []))\n"
   "(def dirs (filterv #(.exists (java.io.File. ^String %)) [\"src\" \"cljs-src\"]))\n"
   "(let [res (try\n"
   "            (binding [ana/*cljs-warning-handlers*\n"
   "                      [(fn [wt env extra]\n"
   "                         (when (get ana/*cljs-warnings* wt)\n"
   "                           (swap! ws conj {:type wt :line (:line env)\n"
   "                                           :ns (str (or (get-in env [:ns :name]) ana/*cljs-ns*))\n"
   "                                           :message (str \"WARNING: \" (try (ana/error-message wt extra) (catch Throwable _ (name wt))))})))]]\n"
   "              (b/build (apply b/inputs dirs) {:output-to \"" output-to "\" :output-dir \"" output-dir "\" :optimizations " optimizations (if (seq foreign-libs)
     (str " :foreign-libs " (pr-str (vec foreign-libs)))
     "")
   "})\n"
   "              nil)\n"
   ;; the WHOLE cause chain plus the ex-data LOCATION. The outermost
   ;; message is usually "failed compiling file:…" and the reason is in a
   ;; cause; the file and line live in ex-data. Keeping only .getMessage
   ;; threw both away, which is why a hard failure used to reach the agent
   ;; as a bare temp-dir path (anchor-error consumes :error-at).
   "            (catch Throwable e\n"
   "              (let [chain (->> (iterate #(.getCause ^Throwable %) e)\n"
   "                               (take-while some?) (take 8))\n"
   ;; the first ex-data carrying a LINE, not merely the first carrying
   ;; ex-data: the outer "failed compiling" exception has ex-data with no
   ;; location, so taking it anchors nothing while looking like it worked.
   "                    d     (first (filter :line (keep ex-data chain)))]\n"
   ;; the messages as a VECTOR, joined by the consumer. A separator string
   ;; literal here sits three levels of escaping deep — source → -e argument
   ;; → generated program — and one level too many turns " / " into a
   ;; character literal, which is exactly how this line first shipped broken.
   ;; Sending data instead of a formatted string removes the nesting.
   "                {:msgs (vec (distinct (keep #(.getMessage ^Throwable %) chain)))\n"
   ;; coerced, because this crosses a PROCESS boundary as EDN: ex-data's
   ;; :file is an object, and pr-str of it comes back as #object[…] which
   ;; the reader on this side refuses. Only readable scalars may cross.
   "                 :at  (when d {:file (str (:file d))\n"
   "                               :line (when (number? (:line d)) (:line d))\n"
   "                               :column (when (number? (:column d)) (:column d))})})))]\n"
   "  (println (str \"" result-marker "\" (pr-str {:warnings @ws :error res}))))\n"))

(defn anchor-error
  "Anchor a hard cljs compile FAILURE to a store form, the way
  [[anchor-warnings]] anchors an analyzer warning: `{:error msg}` plus
  `:form` and `:at` when the location resolves.

  `at` is the compiler's `{:file :line}` — `file` being a path inside the
  throwaway materialization dir, which is precisely why it must not reach the
  agent. slopp's standing invariant is that no `file:line` is ever handed
  out; warnings honoured it and errors did not, because the runner kept only
  `.getMessage` and dropped the `ex-data` carrying the location.

  The path→namespace step is real work, not a string replace: ClojureScript
  munges `-` to `_` in file names, so `app/my_view.cljs` is `app.my-view`.

  A location that does not resolve to a store namespace anchors NOTHING and
  says so by omission rather than guessing — an anchor pointing at the wrong
  form is worse than no anchor — a wrong one reads as a finding."
  [store msg {:keys [file line]}]
  (let [nsx (when file
              (some-> (re-matches #"(?:file:)?(?:.*?/)?((?:[^/]+/)*[^/]+)\.clj[sc]$"
                                  (str file))
                      second
                      (str/replace "/" ".")
                      (str/replace "_" "-")
                      symbol))
        ok? (boolean (and nsx line (contains? (:namespaces store) nsx)))
        e   (when ok? (store.render/owner-form store nsx line 1))
        at  (when ok? (nth (str/split-lines (store.render/render-ns store nsx))
                           (dec line) nil))]
    (cond-> {:error (-> (str msg)
                        ;; the compiler names the materialization dir, often
                        ;; more than once. :form and :at carry the location
                        ;; properly now, so the path is both redundant and a
                        ;; breach of the no-file:line invariant — and it
                        ;; points into a temp dir the agent never created.
                        (str/replace #"\s*at line \d+(?::\d+)?" "")
                        (str/replace #"(?:file:)?\S*\.clj[sc]\b" "")
                        (str/replace #"\s*/\s*/\s*" " / ")
                        (str/replace #"\s{2,}" " ")
                        (str/replace #"^[\s/]+|[\s/]+$" ""))}
      e  (assoc :form (symbol (str nsx) (str (or (:name e) (:id e)))))
      at (assoc :at (str/trim at)))))

(defmulti compile-client*
  "Compile the client namespaces materialized under `dir` with a specific
  backend, returning the runner's parsed `{:warnings [...] :error ...}` (or an
  `{:error ...}` for an unknown backend). Dispatches on the compiler keyword so a
  future cherry/squint backend adds a `defmethod` without touching callers
  (D-web-cljs, the pluggable-compiler decision)."
  (fn [compiler _dir] compiler))

(defmethod compile-client* :clojurescript
  [_ dir]
  (let [{:keys [out exit]} (testrun/run-cmd!
                            [repl/clojure-bin "-M:cljs" "-e" (runner-src {})]
                            dir 300000)]
    (or (parse-result out)
        {:warnings []
         :error (str "cljs compile produced no result (exit " exit ") — "
                     (->> (str/split-lines (str out)) (remove str/blank?)
                          (take-last 8) (str/join "\n")))})))

(defmethod compile-client* :default
  [compiler _dir]
  {:warnings []
   :error (str "no client-compiler backend for " compiler
               " — only :clojurescript is implemented today (cherry/squint are"
               " planned). Set the backend with config_file {path \"client\""
               " key \"compiler\" value \"clojurescript\"}.")})

(defn ^:private resolve-schema-ref
  "Resolve a :rest/request/:rest/response value into a client-usable schema
   reference. A schema VAR symbol (alias- or fully-qualified) →
   {:kind :var :sym <fq> :ns <schema-ns>} when it lives in a :cljc namespace so
   it compiles into BOTH the server oracle and the client bundle; a var that
   can't ship to the client is tagged instead: {:kind :not-cljc …} (wrong
   platform) or {:kind :missing …} (no such var). An inline malli form (vector
   or keyword) → {:kind :inline :schema raw}; nil → {:kind :none}. Alias
   resolution uses the ENDPOINT ns's requires (edit/require-aliases)."
  [store endpoint-ns raw]
  (cond
    (nil? raw)     {:kind :none}
    (symbol? raw)  (let [aliases (edit/require-aliases store endpoint-ns)
                         sns     (some-> (namespace raw) symbol)
                         full-ns (if sns (get aliases sns sns) endpoint-ns)
                         nm      (symbol (clojure.core/name raw))
                         fq      (symbol (str full-ns) (str nm))]
                     (cond
                       (nil? (store/form-named store full-ns nm))
                       {:kind :missing :sym fq :ns full-ns}
                       (not= :cljc (store/platform-for store full-ns))
                       {:kind :not-cljc :sym fq :ns full-ns
                        :platform (store/platform-for store full-ns)}
                       :else {:kind :var :sym fq :ns full-ns}))
    :else          {:kind :inline :schema raw}))

(defn ^:private schema-form
  "The cljs code for a resolved schema ref: the fully-qualified var symbol (a
   :var) or the inline malli form pr-str'd (an :inline); nil for :none."
  [resolved]
  (case (:kind resolved)
    :var    (str (:sym resolved))
    :inline (pr-str (:schema resolved))
    nil))

(defn ^:private path-expr
  "The cljs path expression for a route: the literal path string, or a (str …)
   that substitutes each parameter segment with its value from `params`.

   A parameter segment is `:name` (read as `:name`) or a WILDCARD — `*` or
   `**` — read as `:*`, which is the key both matchers bind it under. The
   wildcard used to be no segment at all here, so a path carrying one was
   printed verbatim and the generated wrapper fetched a url containing the
   characters `**`."
  [path]
  (let [segs   (str/split path #"/" -1)
        param? (fn [s] (or (str/starts-with? s ":") (#{"*" "**"} s)))
        as-key (fn [s] (if (#{"*" "**"} s) :* (keyword (subs s 1))))]
    (if (not-any? param? segs)
      (pr-str path)
      (let [parts     (mapcat (fn [s]
                                (if (param? s)
                                  ;; ENCODED, and which encoder depends on
                                  ;; whether a `/` in the value is data or
                                  ;; structure. Raw interpolation let a value
                                  ;; break out of its own segment, and it made
                                  ;; this generator disagree with `render-request`
                                  ;; — two clients of one endpoint, one
                                  ;; encoding and one not
                                  [(list (if (= s "**") 'seg* 'seg)
                                         (list (as-key s) 'params))]
                                  [s]))
                              segs)
            joined    (interpose "/" parts)
            collapsed (reduce (fn [acc x]
                                (if (and (string? x) (string? (peek acc)))
                                  (conj (pop acc) (str (peek acc) x))
                                  (conj acc x)))
                              [] joined)]
        (pr-str (cons 'str collapsed))))))

(defn ^:private render-wrapper
  "One typed fetch wrapper defn (a source string) for a client-wrapper-specs
   spec: marked ^{:generated <endpoint>} (provenance + edit-protection) and
   ^:export (client-API surface), validating the request against its schema
   before send and the response after receive (malli + json-transformer at the
   JSON boundary). Path :segments are interpolated from the params map.

   **How the request TRAVELS follows from the method, not from a second
   declaration.** A body verb (POST/PUT/PATCH) JSON-encodes the params map into
   the body; anything else sends it as a QUERY STRING. `:rest/request` already
   means \"what the caller sends\", and the contract already carries the method,
   so a `?depth=`-style parameter needs no new vocabulary — it needs the
   generator to stop assuming every declared request is a body.

   **How the response is READ follows from `:rest/media-type`.** This was
   `.json` unconditionally, and slopp's own `/api/rest/paths` answers
   `application/edn` — a malli schema is keywords and symbols, and JSON would
   render `:string` and `\"string\"` identically, so the far end could not tell
   them apart. A wrapper for it failed on the first character, and the endpoint
   carried `:rest/client false` to HIDE that rather than to state it. Saying
   what an endpoint answers is checkable, positive, and about the endpoint;
   opting out of clients was none of those.

   The PATH params are dissoc'd from the query: a segment interpolated into the
   url must not also arrive as a query key, and repeating it would make the url
   depend on map ordering."
  [{:keys [fn-name method path endpoint request response media-type request-keys]}]
  (let [verb      (str/upper-case (clojure.core/name method))
        req-code  (schema-form request)
        resp-code (schema-form response)
        ;; a WILDCARD is a path parameter too, anonymous and under `:*` — the
        ;; key both matchers bind it under. It has to be in this list for two
        ;; reasons beyond the url: it decides whether the wrapper takes a
        ;; params map at all, and `allowed` below is built from it, so a
        ;; wildcard missing here made the endpoint unreachable in both
        ;; directions — the url needed the value and the guard refused it
        segs      (keep #(cond (str/starts-with? % ":") (keyword (subs % 1))
                              (#{"*" "**"} %)          :*)
                        (str/split path #"/" -1))
        body?     (contains? #{:post :put :patch} method)
        params?   (boolean (or req-code (seq segs)))
        arglist   (if params? "[params]" "[]")
        query     (when (and req-code (not body?))
                    (str " (qs " (if (seq segs)
                                   (str "(dissoc params "
                                        (str/join " " (map pr-str segs)) ")")
                                   "params")
                         ")"))
        ;; only wrap in (str …) when there is something to append. Otherwise
        ;; every existing wrapper gains a pointless `(str "/api/timeline")` —
        ;; a diff across all generated code for no behaviour, in a namespace
        ;; whose contract is that it is regenerated wholesale and read by eye.
        url-expr  (let [pe (path-expr path)]
                    (cond
                      (nil? query) pe
                      ;; the path already IS a (str …) when it interpolates a
                      ;; segment — splice into it rather than nesting, because
                      ;; generated code is read by whoever consumes the API and
                      ;; (str (str …) …) is a seam showing
                      (str/starts-with? pe "(str ")
                      (str (subs pe 0 (dec (count pe))) query ")")
                      :else (str "(str " pe query ")")))
        opts      (if (and req-code body?)
                    (str "(clj->js {:method \"" verb "\" "
                         ":headers {\"Content-Type\" \"application/json\"} "
                         ":body (js/JSON.stringify (clj->js (m/encode " req-code
                         " params (mt/json-transformer))))})")
                    (str "(clj->js {:method \"" verb "\"})"))
        ;; BEFORE the malli check, and not instead of it: malli's map schema is
        ;; OPEN, so `m/validate` passed an undeclared key and always had. The
        ;; two ask different questions — this one whether the caller sent
        ;; something the contract never named, that one whether what it named
        ;; is well typed. Same guard the `:cljc` builder emits, because the
        ;; hole was never about which renderer you got.
        allowed   (when (seq request-keys)
                    (sort (into (set request-keys) segs)))
        undeclared (when (and (seq allowed) params?)
                     (str "  (when-let [extra (seq (remove #{"
                          (str/join " " (map pr-str allowed))
                          "} (keys params)))]\n"
                          "    (throw (ex-info (str \"" fn-name ": this endpoint's"
                          " contract does not name \" (pr-str (vec extra)))\n"
                          "                    {:undeclared (vec extra)})))\n"))
        validate  (when req-code
                    (str "  (when-not (m/validate " req-code " params)\n"
                         "    (throw (ex-info \"" fn-name " request failed validation\"\n"
                         "                    {:errors (m/explain " req-code " params)})))\n"))
        json?     (= "application/json" (or media-type "application/json"))
        read-body (if json? ".json" ".text")
        ;; non-JSON validates the body as it stands: there is no JSON boundary,
        ;; so nothing for the json-transformer to transform across, and
        ;; `js->clj` on a string would be a no-op that reads as intent.
        handle    (cond
                    (not resp-code)
                    "      (.then (fn [body] body)))"

                    json?
                    (str "      (.then (fn [body]\n"
                         "               (let [data (m/decode " resp-code
                         " (js->clj body :keywordize-keys true) (mt/json-transformer))]\n"
                         "                 (when-not (m/validate " resp-code " data)\n"
                         "                   (throw (ex-info \"" fn-name " response failed validation\"\n"
                         "                                   {:errors (m/explain " resp-code " data)})))\n"
                         "                 data))))")

                    :else
                    (str "      (.then (fn [body]\n"
                         "               (when-not (m/validate " resp-code " body)\n"
                         "                 (throw (ex-info \"" fn-name " response failed validation\"\n"
                         "                                 {:errors (m/explain " resp-code " body)})))\n"
                         "               body)))"))]
    (str "(defn ^{:generated \"" endpoint "\"} ^:export " fn-name "\n"
         "  \"" verb " " path " — generated client wrapper (D-web-contracts).\"\n"
         "  " arglist "\n"
         (or undeclared "") (or validate "")
         "  (-> (js/fetch (url " url-expr ") " opts ")\n"
         "      (.then (fn [resp] (" read-body " (ok! resp))))\n"
         handle ")")))

(defn ^:export render-client-ns
  "Render the generated typed-client namespace SOURCE (a string) from wrapper
   specs (client-wrapper-specs :wrappers): a ns form requiring malli + every
   schema's :cljc contracts namespace, then one typed fetch wrapper per endpoint.
   The whole namespace is generate_client's output — every form is ^:generated
   (edit-protected + inspection-exempt), and the schema references are real store
   references so 'edit a schema → every affected client call' falls out of the
   graph. Pure."
  [ns-sym wrappers]
  (let [schema-nses (->> wrappers
                         (mapcat (juxt #(get-in % [:request :ns])
                                       #(get-in % [:response :ns])))
                         (remove nil?) distinct sort)
        requires    (str "(:require [malli.core :as m]\n"
                         "            [malli.transform :as mt]"
                         (apply str (for [n schema-nses] (str "\n            " n)))
                         ")")]
    (str "(ns " ns-sym "\n"
         ;; the generated namespace has to satisfy the rules generated code is
         ;; subject to. `namespace-purpose` fires on a namespace with no
         ;; docstring, and a CONSUMER cannot discharge it here — a hand-edit is
         ;; overwritten by the next generate_client, so the advisory returns on
         ;; every contract change. A permanent finding nobody can clear trains
         ;; the reader to skim the whole list, which costs more than the
         ;; finding is worth. render-contracts-ns has always written one; this
         ;; closes the inconsistency between the generator's two outputs.
         "  \"Typed fetch wrappers for an API this app CONSUMES — generated by\n"
         "  generate_client, one wrapper per endpoint, each validating against\n"
         "  the SAME schema var the server validates with.\n\n"
         "  Regenerate, never hand-edit: every form here is ^:generated and the\n"
         "  next generate_client overwrites the namespace wholesale. Set the\n"
         "  mount prefix once with set-base! if the app is served under one.\"\n"
         "  " requires ")\n\n"
         ;; Every wrapper's path is root-absolute, which makes this namespace
         ;; unservable behind a proxy that mounts the app under a prefix —
         ;; /api/x resolves at the PROXY, and the page arrives and does
         ;; nothing (D-hub part 2). One base, set once by whatever mounts
         ;; the app; "" is exactly the behaviour every existing app has.
         "(defonce ^:export base (atom \"\"))\n\n"
         "(defn ^:export set-base! [b] (reset! base b))\n\n"
         "(defn- url [p] (str @base p))\n\n"
         ;; Path values are ENCODED, and which encoder depends on whether a
         ;; `/` in the value is data or structure. Raw interpolation let a
         ;; value break out of its own segment — and `/api/source/:ns/:name`
         ;; takes a VAR NAME, the exact parameter that once arrived as
         ;; `register%21` and resolved to nothing.
         ;;
         ;; It also made this generator disagree with `render-request`, whose
         ;; builders hand path params to `request-url` and get per-segment
         ;; encoding. Two clients of one endpoint, one encoding and one not.
         ;;
         ;; Helpers rather than inline, for `url` and `qs`'s reason: a fix
         ;; applied at eight interpolation sites misses the ninth.
         "(defn- seg\n"
         "  \"One path segment, percent-encoded: a / in a VALUE is data trying to\n"
         "   become structure, and the router decodes each segment on its own.\"\n"
         "  [v] (js/encodeURIComponent (str v)))\n\n"
         "(defn- seg*\n"
         "  \"A ** remainder — each sub-segment encoded on its own and THEN joined,\n"
         "   because here a / IS structure. Empty for an absent remainder, since\n"
         "   ** matches zero segments and a url has to be able to stop.\"\n"
         "  [v]\n"
         "  (let [s (str (or v \"\"))]\n"
         "    (if (= \"\" s) \"\" (.join (.map (.split s \"/\") seg) \"/\"))))\n\n"
         ;; `js/fetch` REJECTS only on a network error, so a 500 resolves and a
         ;; wrapper that goes straight to `.json` hands the error page's body to
         ;; `m/decode` — which fails validation and rejects with "… response
         ;; failed validation". The server said 500 and the screen blames the
         ;; contract. Reported by the app that consumes these, in the nine
         ;; wrappers it did not write, beside three it did that all check.
         ;;
         ;; The cost is worse than the wrong sentence: contract validation
         ;; exists to detect DRIFT, and a transport failure wearing its clothes
         ;; makes real drift and a 502 from a proxy produce the same words. And
         ;; a non-JSON error page rejects INSIDE `.json`, so the reader is shown
         ;; "Unexpected token <".
         ;;
         ;; One helper rather than a check per wrapper, for `url` and `qs`'s
         ;; reason: a fix applied eight times misses the ninth.
         "(defn- ok!\n"
         "  \"The response, or a throw naming the STATUS — called before the body\n"
         "   is read, because fetch resolves for a 500 and a decode failure would\n"
         "   otherwise report a transport problem as a contract problem.\"\n"
         "  [resp]\n"
         "  (when-not (.-ok resp)\n"
         "    (throw (ex-info (str \"HTTP \" (.-status resp) \" \" (.-statusText resp))\n"
         "                    {:status (.-status resp) :url (.-url resp)})))\n"
         "  resp)\n\n"
         ;; the query-string builder every non-body wrapper calls. reduce-kv
         ;; and not str/join on purpose: this namespace requires malli and the
         ;; schema namespaces and nothing else, and a generated ns that drags
         ;; in a dependency is one that can fail to compile for a reason its
         ;; author never wrote. A nil value is DROPPED rather than sent as
         ;; "null" — an absent optional and an explicit nil mean the same
         ;; thing to a query string, and only one of them is spellable.
         "(defn- qs [m]\n"
         "  (reduce-kv (fn [acc k v]\n"
         "               (if (nil? v)\n"
         "                 acc\n"
         "                 (str acc (if (= \"\" acc) \"?\" \"&\")\n"
         "                      (name k) \"=\" (js/encodeURIComponent (str v)))))\n"
         "             \"\" m))\n\n"
         (str/join "\n\n" (map render-wrapper wrappers)))))

(defn- served-by-a-mount?
  "Whether any `http.static.*` mount would serve `path`.

  A mount key's tail is the URL prefix and its value a files-manifest path
  prefix (`http.static./js = public/cljs` serves `public/cljs/main.js` at
  `/js/main.js`), so the question is just whether some mount's value is a
  prefix of the written path — read through `rules.http/static-mounts`, the
  one parser of that family, so a trailing slash means here what it means
  to the server.

  NOT the same question as \"is this file reachable over HTTP\", and the
  caller's message must not say it is. An ENDPOINT that reads the file serves
  it just as well — slopp's own reviewer UI does exactly that, and this
  predicate reported the bundle unserved while `/js/main.js` was returning it
  with a 200. Detecting that case generically means scanning source for the
  path as a string, which a docstring mentioning it would trip; saying what
  was actually checked is both cheaper and honest."
  [store path]
  (boolean
   (some (fn [[_ v]] (str/starts-with? (str path) (str v "/")))
         (rules.http/static-mounts store))))

(defn ^:export render-contracts-ns
  "Render the generated CONTRACTS namespace source (a string) from
   [[contract->plan]]'s `:defs` — one plain `def` per published schema.

   `:cljc` on purpose: the same var has to satisfy the JVM oracle when the
   server-side code validates against it AND compile into the client bundle,
   which is the property `resolve-schema-ref` refuses to generate without.

   Each def is `^{:generated \"<endpoint>\"}` — edit-protected, and saying which
   endpoint it came from, because a schema you cannot trace back to its source
   is worse than no schema. Regenerate, never hand-edit."
  [ns-sym defs]
  (str "(ns " ns-sym "\n"
       "  \"Schemas published by an API this app CONSUMES — generated by\n"
       "  generate_client, one def per endpoint request/response.\n\n"
       "  The names are derived from ENDPOINTS, not from whatever the producer\n"
       "  called them: a contract travels as evaluated values, so the author's\n"
       "  schema names never crossed the wire.\")\n\n"
       (str/join "\n\n"
                 (for [{:keys [name schema endpoint]} defs]
                   (str "(def ^{:generated \"" endpoint "\"} " name "\n"
                        "  " (pr-str schema) ")")))))

(defn ^:export fetch-contract
  "GET a published contract from `url` and read it as EDN.

   `clojure.edn/read-string`, never `read-string`: this is data off a network
   boundary, and the EDN reader evaluates nothing. A malli schema is made of
   vectors, keywords and predicate symbols, all of which survive it — which is
   why the contract is EDN rather than JSON, where `:string` and \"string\"
   would arrive indistinguishable.

   `requester` is the transport — [[slopp.http.client/request]] by default. The
   part worth testing is the paragraph above: that what comes back is READ and
   not evaluated, and that a non-200 fails rather than being parsed as if it
   were a contract. Neither needs a socket."
  ([url] (fetch-contract url http.client/request))
  ([url requester]
   (let [{:http/keys [status body]}
         (requester {:http/method  :get
                     :http/url     (str url)
                     :http/timeout-ms http.client/default-timeout-ms
                     :http/headers {"Accept" "application/edn"}})]
     (when-not (= 200 status)
       (throw (ex-info (str "contract fetch failed: HTTP " status " from " url)
                       {:url (str url) :status status})))
     (edn/read-string body))))

(defn foreign-libs-for
  "The `:foreign-libs` entries the ClojureScript compiler needs for a store's
  declared JavaScript.

  Only `:iife`/`:umd` are returned. Those are CONCATENATED into the bundle
  and mapped to the global they set, which is what makes `(:require
  [roughjs :as rough])` compile to `goog.global[\"rough\"]`. `:esm` is skipped
  deliberately: an ES module is loaded by the page, and concatenating one
  produces a bundle that fails at runtime with nothing to point at.

  `:file` is relative to the materialized build dir, which is where `build!`
  writes the files manifest — so the path recorded in the declaration is the
  path the compiler resolves, with no second convention to keep in step."
  [store]
  (vec (for [[nm {:keys [format global file]}] (sort (:js-deps store))
             :when (contains? #{:iife :umd} format)]
         {:file file
          :provides [nm]
          :global-exports {(symbol nm) (symbol global)}})))

(defn write-foreign-libs!
  "Write the store's declared JavaScript into the materialized project at
  `dir` as `src/deps.cljs`, and return what was written (nil if nothing).

  `deps.cljs` is the ClojureScript compiler's OWN mechanism:
  `cljs.closure/get-upstream-deps*` enumerates every `deps.cljs` at the root
  of every classpath entry and merges their `:foreign-libs`. `src` is on the
  generated project's classpath, so dropping the file there is all it takes
  — no compiler options, no per-backend plumbing.

  That is why it is done this way rather than by threading the value into
  `compile-client*`: that is a MULTIMETHOD, and a re-loaded `defmethod` does
  not replace its already-registered compiled fn in a running image, so an
  arity change there strands every live session until the process restarts.
  Measured twice, across a restart. This path needs no signature at all,
  works for every backend including the deferred ones, and is exactly what a
  published library would ship."
  [store dir]
  (let [fl (foreign-libs-for store)]
    (when (seq fl)
      (let [f (io/file dir "src" "deps.cljs")]
        (io/make-parents f)
        (spit f (pr-str {:foreign-libs fl}))
        fl))))

(defn ^:export compile-client!
  "Compile the store's CLIENT namespaces (:cljc + :cljs) to JavaScript with the
  configured backend (build/client-compiler, default :clojurescript) and record
  the output as a served blob — the client wave's compile-error-as-oracle
  (D-web-cljs). Materializes the store (build!) into a throwaway dir whose
  generated deps.edn carries the :cljs alias — build! injects slopp's OWN
  toolchain there (the compiler + malli), so the agent never hand-adds slopp's
  plumbing. Shells the compiler in a fresh JVM (no Node — real
  org.clojure/clojurescript on the JVM), then anchors warnings to store forms
  (name-addressed, no file:line) and stores the JS (file_put). Returns {:compiled
  <ns-count> :warnings [...anchored...] :output <path> :bytes n} on success,
  {:error ... :warnings ...} on a compile failure, or {:note ...} when there is
  no client code. `:output` sets the served path (default \"public/cljs/main.js\")."
  [session & {:keys [output] :or {output "public/cljs/main.js"}}]
  (let [st       (:store @session)
        compiler (build/client-compiler st)
        client   (filterv #(#{:cljc :cljs} (store/platform-for st %))
                          (keys (:namespaces st)))]
    (if (empty? client)
      {:note (str "no client namespaces to compile — declare one :cljc or :cljs"
                  " via module_platform")}
      (let [dir (str (java.nio.file.Files/createTempDirectory
                      "slopp-cljs"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
        (try
          (let [b (external/build! session dir)]
            (if (:error b)
              b
              (let [{:keys [warnings error]} (do
                                              ;; declared JavaScript reaches the
                                              ;; compiler as a classpath resource,
                                              ;; which every backend reads and no
                                              ;; signature has to carry
                                              (write-foreign-libs! st dir)
                                              (compile-client* compiler dir))
                    anchored (anchor-warnings st (or warnings []))]
                (if error
                  ;; the runner sends {:msg :at}; a legacy/fallback string still
                  ;; works, it simply anchors nothing
                  (merge (if (map? error)
                           (anchor-error st (str/join " / " (:msgs error)) (:at error))
                           (anchor-error st (str error) nil))
                         {:warnings anchored})
                  (let [js    (slurp (io/file dir "out" "main.js"))
                        ;; an ARTIFACT, not a file: bytes to the content-addressed
                        ;; cache, sha + recipe to the journal. Inline, this one
                        ;; path was 30.47MB of delta log across fifteen compiles.
                        entry (artifacts/put! (:dir @session)
                                              (.getBytes ^String js "UTF-8")
                                              {:kind :build :tool "compile_client"}
                                              :content-type "application/javascript")]
                    (let [prior (get-in @session [:store :artifacts output :sha])]
                      (engine/commit-appended!
                       session
                       (fn [s] (first (store/record-artifact s output entry)))
                       [])
                      ;; the bundle this one replaced is now unreferenced. Pruned
                      ;; HERE because this is the only point that knows which sha
                      ;; was superseded; a sweep of everything unreferenced would
                      ;; be driven by whichever store happened to be in hand.
                      (artifacts/prune-superseded! (:dir @session)
                                                   (:store @session)
                                                   prior))
                    ;; A bundle nothing serves is the failure this just had: slopp's own sat
                    ;; in the manifest for two waves while every page 404'd on it,
                    ;; because serving it needs an http.static.* mount and nothing
                    ;; said so. Serving it IS one line — the gap was
                    ;; discoverability, so the tool that wrote the file names the
                    ;; line, and goes quiet once a mount covers the path.
                    (cond-> {:compiled  (count client)
                             :namespaces (mapv str (sort client))
                             :warnings  anchored
                             :output    output
                             :sha       (:sha entry)
                             :bytes     (count js)}
                      (not (served-by-a-mount? (:store @session) output))
                      (assoc :serve-with
                             (let [dir (or (second (re-matches #"(.*)/[^/]+" output))
                                           output)]
                               (str "no http.static mount serves " output
                                    " — config_file {path \"capabilities\" key"
                                    " \"http.static./js\" value \"" dir "\"} mounts it"
                                    " at /js/. (An endpoint that reads the file"
                                    " serves it too; this checked mounts only.)")))))))))
          (finally
            (letfn [(rm! [f]
                      (when (.isDirectory f) (run! rm! (.listFiles f)))
                      (.delete f))]
              (rm! (io/file dir)))))))))

(defn client-compile-guard!
  "The per-session single-flight guard for background client recompiles
  (D-web-cljs (c) async): an atom `{:running? :dirty? :last}`. Lazily created on
  the session atom, atomically, so concurrent client writes share ONE guard."
  [session]
  (:client-compile
   (swap! session update :client-compile
          #(or % (atom {:running? false :dirty? false :last nil})))))

(defn recompile-loop!
  "Background thread body (D-web-cljs (c) async): compile the client bundle,
  then — if another client write set `:dirty?` while we compiled — clear it and
  compile AGAIN (coalescing), else set `:running?` false and stop. Each
  outcome is stored on `guard` under `:last`, surfaced on a later write; a
  compile error is captured, never thrown into the daemon."
  [session guard]
  (loop []
    (let [outcome (try
                    (let [r (compile-client! session)]
                      (if (:error r)
                        {:client-recompile-error (:error r)}
                        {:client-recompiled (:output r)}))
                    (catch Throwable t
                      {:client-recompile-error (ex-message t)}))
          [old _] (swap-vals! guard
                              (fn [s]
                                (-> (if (:dirty? s)
                                      (assoc s :dirty? false)
                                      (assoc s :running? false))
                                    (assoc :last outcome))))]
      (when (:dirty? old) (recur)))))

(defn schedule-client-recompile!
  "Single-flight (D-web-cljs (c) async): start a background compile thread only
  if none is running; otherwise mark the in-flight one `:dirty?` so it compiles
  once more when it finishes. `swap-vals!` gives the pre-swap state, so exactly
  the false→true transition starts the daemon."
  [session]
  (let [guard   (client-compile-guard! session)
        [old _] (swap-vals! guard
                            #(if (:running? %)
                               (assoc % :dirty? true)
                               (assoc % :running? true :dirty? false)))]
    (when-not (:running? old)
      (doto (Thread. ^Runnable #(recompile-loop! session guard)
                     "slopp-client-recompile")
        (.setDaemon true)
        (.start)))))

(defn maybe-recompile-client!
  "Dev loop (D-web-cljs): when `client`/`auto-compile` is ON and `ns-sym` is a
  CLIENT namespace (`:cljc`/`:cljs`), schedule an ASYNC background recompile of
  the client bundle so a `--live` server serves fresh JS — WITHOUT blocking the
  write. Single-flight + coalescing (a write during a compile triggers exactly
  ONE more compile after it — the bundle always reflects the latest edit).
  Returns `{:client-recompiling true}` immediately, plus `:client-recompile-prev`
  (the previous background compile's outcome — `{:client-recompiled path}` or
  `{:client-recompile-error msg}`) once one has finished; or nil when disabled or
  `ns-sym` is not a client namespace.

  The compile runs on a daemon thread and commits the served blob when done;
  a compile error is captured on the guard and surfaces on a later write, never
  thrown here.

  Reached through `session/after-write!`, which the `defmethod`s below register
  on the client platforms — so an ordinary `:jvm` write never arrives here at
  all, and the write engine does not name this namespace (R6). This used to run
  IN the engine and reach back for `compile-client!` through
  `store/late-ref`, because THIS namespace requires `slopp.ops.external` →
  `slopp.ops`, so a static require would have cycled. The escape hatch was
  holding up the misplacement, not the load order: registering points the edge
  the one way that never cycles, and the call below is now ordinary.

  The platform check stays even though the dispatch already made it, because
  `generate-client!` calls this directly — it is this function's own contract,
  not the hook's."
  [session ns-sym]
  (let [st (:store @session)]
    (when (and (= "true" (str (get-in st [:config "client" :values "auto-compile"])))
               (#{:cljc :cljs} (store/platform-for st ns-sym)))
      (schedule-client-recompile! session)
      (let [prev (:last @(client-compile-guard! session))]
        (cond-> {:client-recompiling true}
          prev (assoc :client-recompile-prev prev))))))

(defn- default-client-ns
  "Where a generated client goes when the caller does not name one: the
  `client` / `generated-ns` config, else `<family>.wire.api` for a store whose
  browser owns routing and `<family>.client.api` otherwise.

  **The split is a cycle, not a preference.** A browser entry's natural home is
  `<family>.client.*` — the app that found this has `<root>.client.app` — and it
  requires the app, which reads the route table in the views. A client generated
  under `<root>.client` therefore closes `views → client → app → views`, which
  no declaration can open: the module gate is right and the arrangement is
  wrong. The recommended TARGET and the recommended ENTRY wanted the same
  module, so every webapp store taking the shape this capability enables meets
  it.

  `wire` is the consuming app's word and it says the useful thing rather than
  merely dodging the clash: **a consumed API is not part of this app's browser
  layer.** The name is where it lives, not where it is called from.

  A store WITHOUT `webapp` has no framework entry and no cycle, so its default
  is unchanged — this is not a rename, it is a second answer for a second
  situation.

  The literal `app.client.api` this used to fall back to is only ever right
  for a store whose family is literally `app` — it was a placeholder that
  shipped as a default. Reported by a consumer whose 22-namespace `slopp-ui.*`
  store got a SECOND generated client under `app.client.*`, marked `:cljs`, so
  `compile_client` would have bundled a duplicate contracts namespace into the
  browser. Undoing it cost 19 calls: `ns_delete` refuses a non-empty
  namespace, there is no bulk delete, and `undo` was its own bug at the time.

  The family is the first segment the most namespaces share, ties broken
  alphabetically so it is deterministic. `-test` siblings are left in: they
  share their subject's first segment, so they can only reinforce the answer.
  A store with no namespaces at all has nothing to generate from, so the
  literal fallback survives only for that unreachable case."
  [store]
  (or (get-in store [:config "client" :values "generated-ns"])
      (when-let [fam (->> (keys (:namespaces store))
                          (map #(first (str/split (str %) #"\.")))
                          frequencies
                          (sort-by (juxt (comp - val) key))
                          ffirst)]
        (str fam (if (capabilities/enabled? store "webapp") ".wire.api" ".client.api")))
      'app.client.api))

(defn- other-generated-clients
  "Namespaces OTHER than `target` that already hold generated client forms.

  Named on the result rather than refused, because moving a client is a real
  thing to do and a refusal would block it. What must not happen is SILENCE: a
  stray generated namespace stays marked `:cljs`, so `compile_client` keeps
  bundling it, and nothing else in the system mentions it.

  This change is itself the main way one appears — a store generated under the
  old `app.client.api` placeholder keeps that namespace when the default moves
  to its own family."
  [store target]
  (vec (sort (for [n (keys (:namespaces store))
                   :when (and (not= n target)
                              (some (fn [f]
                                      (let [s (store/form-sexpr (:node f))]
                                        (and (seq? s)
                                             (:generated (meta (second s))))))
                                    (store/forms store n)))]
               n))))

(defn ^:private schema-literal-for
  "The literal schema a resolved `:var` reference points at, read out of the
  store, or nil.

  A contract declared inline hands its keys straight over; one declared as a
  var — which is the shape the dialect prefers, and the shape `:rest/request`
  usually takes — hides them behind a name. This is the difference between the
  two producers: `contract->plan` receives schemas as VALUES from a published
  document, while `client-wrapper-specs` receives a reference and has to look.

  Anything other than a plain `(def name … <literal>)` answers nil, which the
  caller treats as \"cannot enumerate\" and emits no guard."
  [store {:keys [kind sym ns]}]
  (when (= :var kind)
    (when-let [e (store/form-named store ns (symbol (clojure.core/name sym)))]
      (let [sx (try (store/form-sexpr (:node e)) (catch Exception _ nil))]
        (when (and (seq? sx) (= 'def (first sx)))
          (last sx))))))

(defn ^:private declared-map-keys
  "The top-level keys a LITERAL `[:map …]` schema declares, or nil when this
  cannot be answered from the data.

  nil is the safe answer and the common one: a schema that is a var reference,
  a non-map, a computed form, or a `[:map]` with no entries gives generation
  nothing to enumerate, and a guard built from a guess would refuse correct
  calls. No check beats a wrong one — the boundary still closes the same
  question server-side."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (let [entries (remove map? (rest schema))]
      (when (and (seq entries)
                 (every? #(and (vector? %) (keyword? (first %))) entries))
        (into #{} (map first) entries)))))

(defn ^:export client-wrapper-specs
  "The generated-client plan (D-web-contracts part 2): one wrapper SPEC per web
   endpoint (`edit.modules/web-endpoint-rows`), with its request/response schemas
   resolved to shippable :cljc vars. Returns {:wrappers [spec …] :problems [p …]}.
   A spec is {:fn-name :method :path :endpoint :request :response} — :fn-name gets
   a ! on a mutating verb (post/put/patch/delete), :request/:response are
   resolve-schema-ref results (only a body verb carries a request). An endpoint
   whose schema can't ship to the client (non-:cljc, or a missing var) is SKIPPED
   and reported as a problem {:endpoint :schema-ref :ns :issue :platform} so the
   generated namespace always compiles. Pure function of the store value."
  [store]
  (reduce
   (fn [acc {:keys [ns name meta kind path]}]
     ;; CONTENT is not a client's business at all — a typed fetch wrapper whose
     ;; (.json resp) runs against HTML is nonsense. That used to be
     ;; `:rest/client false`'s job, on a page that had no way to say it WAS a
     ;; page; `:kind` says it now.
     ;;
     ;; And there is no opt-out beyond that. `:rest/client false` also excluded
     ;; a REAL api, which put one consumer's decision on the producer — an
     ;; endpoint does not know who will call it, and the flag reached every
     ;; other consumer too. What it was standing in for turned out to be
     ;; `:rest/media-type` in the one case that was about the endpoint at all.
     (if (not= :rest kind)
       acc
       (let [endpoint (symbol (str ns) (str name))
             method   (:http/method meta)
             req      (resolve-schema-ref store ns (:rest/request meta))
             resp     (resolve-schema-ref store ns (:rest/response meta))
             bad      (vals (into {} (map (juxt :sym identity))
                                 (filter (comp #{:not-cljc :missing} :kind) [req resp])))]
         (if (seq bad)
           (update acc :problems into
                   (for [b bad] {:endpoint endpoint :schema-ref (:sym b)
                                 :ns (:ns b) :issue (:kind b) :platform (:platform b)}))
           (update acc :wrappers conj
                   {:fn-name  (symbol (str name
                                       ;; not a second bang: a mutating endpoint
                                       ;; named with one is already following the
                                       ;; dialect's convention, and `pay!!` is the
                                       ;; generator fighting the house style
                                       (when (and (#{:post :put :patch :delete} method)
                                                  (not (.endsWith (str name) "!")))
                                         "!")))
                    :method   method
                    :path     path
                    ;; what the endpoint ANSWERS, defaulting to JSON. A wrapper
                    ;; decodes what it is given, and `.json` on anything else
                    ;; fails on the first character — slopp's own
                    ;; /api/contracts answers application/edn
                    :media-type (or (:rest/media-type meta) "application/json")
                    :endpoint endpoint
                    ;; whatever the verb. WHETHER there is a request is the endpoint's
                    ;; declaration; HOW it travels — body or query string — is
                    ;; render-wrapper's decision from the method. Dropping it
                    ;; here on a non-body verb removed the caller's only way to
                    ;; say anything the PATH does not carry, which is how
                    ;; `?depth=` came to answer on the wire while the generated
                    ;; wrapper had nowhere to put it.
                    :request  req
                    ;; inline hands the keys over; a var hides them behind a
                    ;; name and has to be read. Either way nil means "cannot
                    ;; enumerate", and render-request then emits no guard —
                    ;; the boundary still closes the same question
                    :request-keys (or (declared-map-keys (:rest/request meta))
                                      (declared-map-keys
                                       (schema-literal-for store req)))
                    :response resp})))))
   {:wrappers [] :problems []}
   (edit.http/web-endpoint-rows store)))

(defn ^:export contract->plan
  "The generated-client plan for a PUBLISHED contract — the remote twin of
   [[client-wrapper-specs]], which reads the local store instead.

   `document` is what `slopp.rest.paths/paths-document` serves;
   `contracts-ns` is where the schemas will be defined in THIS store. Returns
   `{:defs [{:name :schema}] :wrappers [spec …] :problems [p …]}`, where the
   wrapper specs are the shape [[render-client-ns]] already renders — so
   generating against someone else's API and against your own produce the same
   kind of namespace.

   Schemas arrive as VALUES, because the publisher's var names did not survive
   evaluation (see `slopp.rest.paths`). So each is re-named from its
   ENDPOINT — `things` → `things-response`, `create!` → `create-request` — and
   the bang stays on the wrapper, where it describes the call, rather than
   leaking into a schema's name.

   An envelope outside [[supported-documents]] yields no wrappers and a problem.
   A consumer that generated anyway from a shape it does not know would fail
   later, somewhere else, with nothing pointing back to here."
  [document contracts-ns]
  (if-let [;; the ROWS key is the whole envelope now. A document carries no
                  ;; version: nothing branched on one, and a document changes
                  ;; by RENAMING a key rather than by redefining one in place
                  ;; — so a missing `:paths` IS the signal that this is a
                  ;; shape generation does not know.
                  rows-key (when (contains? document :paths) :paths)]
    (reduce
     (fn [acc {:keys [method path name request response media-type]}]
       (let [base    (symbol (str/replace (str name) #"!$" ""))
             mutate? (contains? #{:post :put :patch :delete} method)
             ;; a body verb carries a request; every other verb declares none,
             ;; the same split client-wrapper-specs makes locally
             ;; ANY verb that declares a request carries it — a body verb sends it
             ;; as a body, everything else as a query string, and render-wrapper
             ;; decides which from the method.
             ;;
             ;; This used to drop it on a non-body verb, matching a split
             ;; client-wrapper-specs made locally — and a comment here SAID SO,
             ;; which is how it survived being fixed there: the prose asserted a
             ;; parity in the same commit that broke it, positioned exactly
             ;; where a reader checks whether both paths were covered.
             ;; The parity is a TEST now, over both producers.
             req     (when request (symbol (str base "-request")))
             resp    (when response (symbol (str base "-response")))
             ref     (fn [sym] (if sym
                                 {:kind :var
                                  :sym (symbol (str contracts-ns) (str sym))
                                  :ns contracts-ns}
                                 {:kind :none}))]
         (-> acc
             (update :defs into (cond-> []
                                  req  (conj {:name req :schema request :endpoint name})
                                  resp (conj {:name resp :schema response :endpoint name})))
             (update :wrappers conj
                     {:fn-name  (symbol (str name
                                             (when (and mutate?
                                                        (not (str/ends-with? (str name) "!")))
                                               "!")))
                      :method   method
                      :path     path
                      ;; the document carries it, so both producers decode what
                      ;; the endpoint answers rather than assuming JSON — the
                      ;; parity a test pins over both
                      :media-type (or media-type "application/json")
                      :endpoint name
                      :request  (ref req)
                      ;; the schemas arrive as VALUES here, so the declared
                      ;; keys are simply in hand — no store lookup, no
                      ;; resolution. This is the path a store takes when it
                      ;; consumes SOMEBODY ELSE'S api, which is exactly where
                      ;; sending an undeclared key is somebody else's problem
                      ;; to notice
                      :request-keys (declared-map-keys request)
                      :response (ref resp)}))))
     {:defs [] :wrappers [] :problems []}
     ;; the rows live under whichever key this envelope puts them — `:paths`
     ;; on the current document, `:endpoints` on the one retiring beside it.
     ;; Identical rows either way, so everything below reads the same
     (rows-key document))
    {:defs [] :wrappers []
     :problems [{:issue :unrecognised-document
                 ;; what the document actually CARRIES, so a reader can see
                 ;; what arrived rather than only that it was wrong. A
                 ;; consumer pointed at a future release sees keys it does
                 ;; not know here, which is the case a bare nil could not
                 ;; tell from an empty response.
                 :keys (vec (sort (map str (keys document))))
                 :expected :paths}]}))

(defn ^:private render-request
  "One endpoint as a DESCRIPTOR — a source string, a plain `def`, `:cljc`.

   ```clojure
   (def ^{:generated \"hub/form\"} ^:export form
     \"GET /api/form/:id — generated endpoint descriptor.\"
     {:http/method   :get
      :http/path     \"/api/form/:id\"
      :http/params   #{:depth :id}
      :rest/response demo.client.contracts/form-response})
   ```

   `slopp.http.endpoint/request` turns one into a request; a route row names
   the result, and both performers — `fetch` in a page, `slopp.rest.client` on
   a server — receive a finished url.

   **One var where there were two, and the contract is why.** This emitted a
   `-request` FUNCTION that discarded the response schema entirely and used the
   request schema only as a boolean, with the real contract emitted separately
   as a `-check` in a sibling namespace. The two facts about an endpoint always
   travel together and were kept apart to dodge a tier: a check calls malli,
   which the functional-core gate reads as IO. A descriptor REFERENCES its
   schemas, so it is data, so the tier question does not arise and they can
   live in one place.

   **Named for the endpoint, with no `!` and no `-request` suffix.** A
   descriptor is not a function and performs nothing, so a mutating endpoint's
   bang would be a claim about a value.

   **`:http/params` is the allowlist, and it is absent when nothing can be
   enumerated.** A contract that cannot be read yields no guard rather than a
   guard built from a gap, which would refuse what the boundary accepts. Path
   segments are always in it: the url cannot be built without them, and whether
   a contract ought to name its own segments is the boundary's question.

   `external` is the url a FOREIGN contract came from, or nil for this store's
   own endpoints. When present the descriptor carries
   `^{:http/external-path <url>}`, because `webapp-request-paths-are-served`
   would otherwise report a path this store genuinely does not serve and its
   escape is a marker on a form nobody may hand-edit — the next generation
   drops it silently. Generation knows where the contract came from, so
   generation declares it."
  [{:keys [fn-name method path endpoint request response request-keys]} external]
  (let [base   (str/replace (str fn-name) #"!$" "")
        verb   (str/upper-case (clojure.core/name method))
        segs   (keep #(cond (str/starts-with? % ":") (keyword (subs % 1))
                            (#{"*" "**"} %)          :*)
                     (str/split path #"/" -1))
        params (when (or (seq request-keys) (seq segs))
                 (sort (into (set request-keys) segs)))
        req    (schema-form request)
        resp   (schema-form response)
        meta*  (str "^{:generated \"" endpoint "\""
                    (when external
                      (str " :http/external-path \"generated from the contract"
                           " published at " external "\""))
                    "}")
        entry  (fn [k v] (str "\n   " k " " v))]
    (str "(def " meta* " ^:export " base "\n"
         "  \"" verb " " path " — generated endpoint descriptor (D-web-contracts).\"\n"
         "  {" (str/join "" [(str ":http/method   " method)
                             (entry ":http/path    " (pr-str path))])
         (when params (entry ":http/params  " (str "#{" (str/join " " (map pr-str params)) "}")))
         (when req    (entry ":rest/request " req))
         (when resp   (entry ":rest/response" resp))
         "})")))

(defn ^:export
  render-request-ns
  "Render the generated ENDPOINT namespace source (a string) — a `:cljc`
   namespace of endpoint descriptors, one `def` per endpoint.

   **What generation owes a consumer changed when the framework started
   performing.** A store on `webapp` declares a route table whose rows name a
   `:request`; the fetch WRAPPERS `render-client-ns` emits are dead surface in
   such a store — and worse than dead, because they are `:cljs` and so are
   exactly what `webapp-client-code` reports.

   `:cljc`, which is the exercise: a descriptor is data, so it loads into the
   image and an ordinary test reads which url a screen will ask for, where a
   `js/fetch` wrapper could only be verified by compiling.

   **It requires the CONTRACTS namespace and nothing else.** A descriptor names
   its schemas rather than validating against them, and a schema is a plain
   `def` of malli DATA — so this namespace reaches no library, stays `:pure`,
   and a `:pure` view can name a descriptor. That is the whole reason the
   checks namespace could be deleted rather than kept beside it: the split
   existed to keep malli's tier off the builders, and referencing a schema does
   not call one.

   `external` is the url a FOREIGN contract came from, or nil for this store's
   own endpoints — see [[render-request]] for why the descriptors carry it."
  [ns-sym wrappers external]
  (let [schema-nses (->> wrappers
                         (mapcat (juxt #(get-in % [:request :ns])
                                       #(get-in % [:response :ns])))
                         (remove nil?) distinct sort)]
    (str "(ns " ns-sym "\n"
         "  \"Endpoint descriptors for an API this app CONSUMES — generated by\n"
         "  generate_client, one per endpoint, from the SAME contract the\n"
         "  server publishes.\n\n"
         "  Turn one into a request with slopp.http.endpoint/request and drop
"
         "  that into a route row's :request; slopp addresses it under the\n"
         "  app's mount point and performs it. Each descriptor carries its own\n"
         "  :rest/response, so the answer is checked against the contract the\n"
         "  producer published without a second var to keep in step.\n\n"
         "  Regenerate, never hand-edit: every form here is ^:generated and the\n"
         "  next generate_client overwrites the namespace wholesale.\""
         (when (seq schema-nses)
           (str "\n  (:require" (apply str (for [n schema-nses] (str "\n            " n))) ")"))
         ")\n\n"
         (str/join "\n\n" (map #(render-request % external) wrappers)))))

(defn ^:private client-shape
  "The client artifact `store` can USE, as `{:platform :touched :write}` —
   `:write` being a `(fn [store] store')` each generation path folds into its
   own commit.

   **One producer, because there are two generation paths and only one of them
   had the branch.** [[generate-client!]] reads the endpoints this store serves;
   [[generate-client-from!]] reads a contract published elsewhere. The first got
   the webapp branch and the second did not — which is backwards for the case
   that motivated it, since a browser app consuming somebody ELSE'S API reaches
   generation only through `from`, and that is the architecture `D-webapp`
   names. Reported by the app in exactly that position, which regenerated and
   got nine `:cljs` wrappers its own framework makes unreachable.

   With `webapp` on the framework performs every request out of a row's
   `:request`, so a typed fetch wrapper is surface nothing calls. Such a store
   gets ENDPOINT DESCRIPTORS in `target` — ONE namespace, `:cljc` and `:pure`.

   **It used to be two, and the second one is gone rather than moved.** The
   contract checks lived in a sibling `…checks` because they reach malli, which
   the functional-core gate reads as IO; written together, the builders
   inherited that tier and the app they were built for could not name them from
   its `:pure` views at all. A descriptor REFERENCES its schema instead of
   validating against it, so it is data, so there is nothing left for the
   sibling to hold — and the tier that forced the split never applies.

   `external` is the url a FOREIGN contract came from, or nil for this store's
   own endpoints.

   Not a flag: which artifact is useful FOLLOWS from who performs, and a store
   that has declared that should not have to declare it twice."
  [store target wrappers external]
  (if-not (capabilities/enabled? store "webapp")
    {:platform :cljs
     :touched  [target]
     :write    (fn [s]
                 (let [s1 (first (store/record-module-platform s (str target) :cljs))]
                   (store/ingest s1 target (render-client-ns target wrappers))))}
    {:platform :cljc
     :touched  [target]
     :write    (fn [s]
                 (let [s1 (first (store/record-module-platform s (str target) :cljc))
                       s2 (first (store/record-module-tier s1 (str target) :pure))]
                   (store/ingest s2 target (render-request-ns target wrappers external))))}))

(defn ^:private hand-written-collision
  "The first of `targets` that already holds code this tool did not write, or
   nil when every one of them is free or is its own previous output.

   **`store/ingest` is BELOW the per-form gates, deliberately** — that is what
   lets regeneration overwrite a generated namespace wholesale and be its only
   writer. The same property makes overwriting somebody's OWN code silent, and
   the names at risk are not the ones a caller passes: `<root>.api` derives
   `<root>.contracts` for the published schemas and `<root>.checks` for the
   contract checks, and either can already exist.

   Reported as a near-miss by the app that read what the generator DERIVES
   rather than what it had passed, and stopped. That is not a habit a tool
   should need: the derived name is the generator's to compute, so the collision
   is the generator's to refuse.

   **A namespace holding ANY `^:generated` form is this tool's own output** and
   is not a collision — a refusal that caught that would make the tool unusable
   on its second run. An EMPTY namespace is not one either: nothing is lost.

   `some` rather than `every`, and both shapes that make the difference are
   real. An `(ns …)` form carries no marker and never could. And the `:cljs`
   client emits unmarked HELPERS on purpose — `url`, `qs`, `ok!` — because the
   wrapper count is taken from the marker and helpers must not read as
   endpoints. `every` was false for output this tool had just written, twice
   over.

   The cost of `some` is a namespace somebody hand-wrote AROUND one generated
   form, which would be overwritten. That shape is already refused elsewhere:
   `http-generated-ns` gates hand edits inside a generated namespace, so mixing
   the two is not a state a store reaches by working normally."
  [store targets]
  (some (fn [ns-sym]
          (let [fs (store/forms store ns-sym)]
            (when (and (seq fs)
                       (not-any? #(:generated (store/form-name-meta %)) fs))
              ns-sym)))
        targets))

(defn ^:export generate-client!
  "Generate the typed client (D-web-contracts part 2): read every web endpoint's
   contract (client-wrapper-specs) and write a stored, edit-PROTECTED :cljs
   namespace of typed fetch wrappers — one per endpoint, validating the request
   out and the response in against the SAME shared :cljc schema the server
   enforces. The target namespace defaults to the `client`/`generated-ns` config,
   else `<this store's family>.client.api` (see `default-client-ns` — the old
   literal `app.client.api` was a placeholder that shipped as a default, and
   put a second client in a consumer's store); `:ns` overrides. Any OTHER
   namespace already holding generated forms comes back in `:other-clients`
   with a note, because a stray one stays marked `:cljs` and keeps being
   bundled. The write goes through store/ingest
   (BELOW the per-form gates — so regeneration overwrites wholesale and is the
   only writer) and marks the module :cljs, so `compile_client` picks it up; when
   `client`/`auto-compile` is on the write schedules a background recompile.
   Endpoints whose schema can't ship to the client (non-:cljc, or a missing var)
   are SKIPPED and surfaced in :problems so the namespace always compiles. An
   EXPLICIT step (mirrors compile_client); a done-advisory nudges regeneration
   when endpoints drift. slopp provisions malli itself (build! injects it — the
   generated code requires it), so the agent never hand-adds slopp's plumbing.
   Returns {:generated :wrappers :endpoints :platform :delta} (+ :problems,
   recompile keys) — or a :note when there is nothing to generate."
  [session & {:keys [ns]}]
  (let [st0    (:store @session)
        target (symbol (str (or ns (default-client-ns st0))))
        {:keys [wrappers problems]} (client-wrapper-specs st0)]
    (if (empty? wrappers)
      (cond-> {:generated target :wrappers [] :endpoints 0
               :note "no shippable endpoints — nothing generated"}
        (seq problems) (assoc :problems problems))
      ;; WHO PERFORMS decides the shape, and it is already declared. With
      ;; `webapp` on, the framework performs every request from a row's
      ;; `:request`, so a typed fetch wrapper is surface nothing calls — and it
      ;; is `:cljs`, so it is what `webapp-client-code` reports and what an
      ;; author is then asked to justify. Such a store gets REQUEST BUILDERS and
      ;; CONTRACT CHECKS instead, `:cljc`, which drop into a route row and load
      ;; into the image. Not a flag: which artifact is useful FOLLOWS from who
      ;; performs, and a store that has said so should not have to say it twice.
      (let [{:keys [platform checks touched write]} (client-shape st0 target wrappers nil)
            _ (when-let [clash (hand-written-collision st0 touched)]
                (throw (ex-info (str "generating here would overwrite " clash
                                     ", which holds code this tool did not"
                                     " write. With webapp on, generation derives"
                                     " MORE names than the one you pass — the"
                                     " contract checks go to a sibling — and"
                                     " store/ingest is below the per-form gates,"
                                     " so the overwrite would be silent. Pass"
                                     " :ns naming a family nothing else"
                                     " occupies.")
                                {:collision clash :would-write (mapv str touched)})))]
        (engine/commit-appended!
         session
         (fn [s]
           ;; record the contract fingerprint so the done-advisory can detect
           ;; endpoint drift and nudge a regenerate (the "explicit" safety net)
           (let [s' (write s)
                 ;; an in-store generation supersedes a url-sourced one: the
                 ;; advisory judges against this store's endpoints again
                 s' (if (get-in s' [:config "client" :values "generated-from"])
                      (first (store/record-config-unset s' "client" "generated-from"))
                      s')]
             (first (store/record-config-put s' "client" :manifest "generated-sig"
                                             (edit.http/client-signature st0)))))
         touched)
        (let [recompiled (maybe-recompile-client! session target)
              others     (other-generated-clients (:store @session) target)]
          (cond-> {:generated target
                   :wrappers  (mapv (comp str :fn-name) wrappers)
                   :endpoints (count wrappers)
                   :platform  platform
                   :delta     (:head (:store @session))}
            checks         (assoc :checks checks)
            ;; a `:cljs` namespace never loads into any JVM, so writing one
            ;; could not stale a process. A `:cljc` one does — and for a
            ;; webapp store this is now the ORDINARY path, so the serving
            ;; process falls behind on every regeneration rather than never.
            ;; Said here because this is where it happened; `done` and
            ;; `full_check` report it accurately afterwards, which is one unit
            ;; of work too late to be the first anyone hears of it
            (= :cljc platform)
            (assoc :loads-into-the-image true
                   :host-note (str "these namespaces are :cljc, so they LOAD —"
                                   " the process serving MCP now holds forms the"
                                   " store has moved past until it reloads them."
                                   " The `restart` tool builds a fresh"
                                   " VERIFICATION image and does not clear that;"
                                   " the serving process has to come up again."
                                   " A :cljs client never had this, because it"
                                   " never loaded into a JVM at all."))
            (seq problems) (assoc :problems problems)
            (seq others)
            (assoc :other-clients others
                   :note (str "this store also holds generated client form(s)"
                              " in " (str/join ", " others)
                              ". Those namespaces are still marked :cljs, so"
                              " compile_client keeps bundling them — retire"
                              " them, or pass :ns to regenerate over the one"
                              " you mean to keep."))
            recompiled     (merge recompiled)))))))

(defn ^:export generate-client-from!
  "Generate a typed client for an API this app CONSUMES, from the contract
   published at `url` — the cross-store twin of [[generate-client!]].

   Writes TWO namespaces: a `:cljc` contracts namespace of the published
   schemas (so the JVM oracle verifies them and the bundle can compile them),
   and the `:cljs` client of typed wrappers pointing at it. Both are
   `^:generated` — regenerate, never hand-edit.

   This is what lets a UI live in a different store from the API it renders:
   nothing here reads the producer's store, and the producer publishes values,
   not source it expects anyone to trust.

   Returns `{:generated :contracts :wrappers :endpoints :platform :delta}`, or
   `:problems` when the contract could not be used at all."
  [session url & {:keys [ns]}]
  (let [st0      (:store @session)
        target   (symbol (str (or ns (default-client-ns st0))))
        cns      (symbol (str/replace (str target) #"[^.]+$" "contracts"))
        document (fetch-contract url)
        {:keys [defs wrappers problems]} (contract->plan document cns)]
    (if (empty? wrappers)
      (cond-> {:generated target :contracts cns :wrappers [] :endpoints 0
               :note "no endpoints in the published contract — nothing generated"}
        (seq problems) (assoc :problems problems))
      ;; the SAME decision `generate-client!` makes, out of one producer. This
      ;; path had none, which was backwards for the case that motivated the
      ;; branch: a browser app consuming somebody ELSE'S API reaches generation
      ;; only through here, and that is `D-webapp`'s named architecture
      ;; the SAME writer `generate-client!` uses, and the url travels with it:
      ;; every endpoint here belongs to somebody else's server by construction,
      ;; so the builders declare that rather than being reported for it
      (let [{:keys [platform checks touched write]}
            (client-shape st0 target wrappers (str url))
            csrc  (render-contracts-ns cns defs)
            names (into [cns] touched)
            clash (hand-written-collision st0 names)]
        (if clash
          {:error (str "generating here would overwrite " clash ", which holds"
                       " code this tool did not write. Generation derives MORE"
                       " names than the one you pass — the published schemas go"
                       " to " cns " — and store/ingest is below the per-form"
                       " gates, so the overwrite would be silent. Pass :ns"
                       " naming a family nothing else occupies.")
           :collision   clash
           :would-write (mapv str names)}
          (do
            (engine/commit-appended!
             session
             (fn [s]
               ;; the CONTRACTS namespace is :cljc either way — the schemas have
               ;; to load in the image AND compile into the bundle, which is what
               ;; makes one definition check both sides of the wire
               (let [s1 (first (store/record-module-platform s (str cns) :cljc))
                     s2 (store/ingest s1 cns csrc)
                     s3 (write s2)
                     ;; RECORD where this client came from, and retire any
                     ;; in-store signature: the stale-client advisory judges
                     ;; a client against this store's endpoints, which says
                     ;; nothing about a contract fetched from elsewhere — a
                     ;; fossil signature from an earlier in-store run kept
                     ;; that advisory standing with nothing able to clear it
                     s4 (if (get-in s3 [:config "client" :values "generated-sig"])
                          (first (store/record-config-unset s3 "client" "generated-sig"))
                          s3)]
                 (first (store/record-config-put s4 "client" :manifest
                                                 "generated-from" (str url)))))
             names)
            (let [recompiled (maybe-recompile-client! session target)
                  ;; what this namespace HELD and no longer does, measured
                  ;; across the write rather than guessed from the plan — so
                  ;; it is exact whatever generation happens to emit beside
                  ;; the wrappers.
                  ;;
                  ;; Reported because a consumer did the thing it catches:
                  ;; hunting for a live contract they pointed this at their own
                  ;; hub instead of the API they consume, and it replaced
                  ;; thirteen descriptors with five and said success. A
                  ;; regeneration that drops every name it wrote and shares
                  ;; none with the new set is far likelier a wrong url than a
                  ;; contract that renamed everything at once.
                  ;;
                  ;; REPORTED, never refused: an API may legitimately rename
                  ;; everything, and a refusal would make the tool wrong about
                  ;; the case it is guessing at. The agent reads the answer.
                  named  (fn [s] (set (keep #(some-> (:name %) str)
                                            (store/forms s target))))
                  before (named st0)
                  after  (named (:store @session))
                  gone   (vec (sort (remove after before)))]
              (cond-> {:generated target
                       :contracts cns
                       :wrappers  (mapv (comp str :fn-name) wrappers)
                       :endpoints (count wrappers)
                       :platform  platform
                       :source    (str url)
                       :delta     (:head (:store @session))}
                (seq gone)     (assoc :dropped gone)
                ;; the test is about the WRAPPERS. A shared helper generation
                ;; emits under both contracts survives the write and is not
                ;; evidence that this is the same API
                (and (seq gone)
                     (not-any? before (map (comp str :fn-name) wrappers)))
                (assoc :dropped-note
                       (str "every name " target " held is gone and none of the"
                            " new ones match — that is what pointing at a"
                            " DIFFERENT API looks like, and it is the wrong url"
                            " far more often than it is a contract that renamed"
                            " everything at once. " (str url) " is what was"
                            " read. Regenerate from the right one, or undo."))
                checks         (assoc :checks checks)
                (seq problems) (assoc :problems problems)
                recompiled     (merge recompiled)))))))))

(defmethod engine/after-write! :cljs [session ns-sym]
  (maybe-recompile-client! session ns-sym))

(defmethod engine/after-write! :cljc [session ns-sym]
  (maybe-recompile-client! session ns-sym))
