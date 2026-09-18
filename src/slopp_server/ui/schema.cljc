(ns slopp-server.ui.schema
  "A malli schema as something a person reads — the API section's arithmetic.

  Exists because the API section has exactly one source of truth, a project's
  published `/api/contracts`, and that document's endpoints carry raw malli.
  `[:map [:modules [:sequential [:map …]]]]` is a precise answer to *what
  shape* and no answer at all to a reader deciding whether to call the thing.

  Separate from `slopp-server.ui.views` because none of it is hiccup: [[type-label]]
  is a phrase, [[rows]] is a flat depth-indexed list, [[nest]] turns that list
  into a tree, and [[path-params]] reads a route. The view's job starts where
  this one ends. Two shapes of the field list rather than one is deliberate —
  flat is what an assertion compares, nested is what the markup needs, and
  neither is derivable from the other cheaply enough to pick one.

  **The rule the whole namespace is built around:** every function degrades to
  the truth rather than to a blank. An unknown schema kind prints its own form,
  an alternation says it is one, a list of objects says what the objects hold.
  A cell reading `array` for two different arrays is the standing failure of
  generated API browsers, and being better than that is the reason this exists
  instead of an OpenAPI export and somebody else's viewer."
  (:require [clojure.string :as str] [clojure.edn :as edn] [slopp.http.endpoint :as endpoint]))

(defn type-label
  "A malli schema form as the short phrase a reader sees in the type column.

  Prose rather than malli's own spelling: `:sequential` is the library's word
  and `list of` is the reader's, and this screen is for someone deciding
  whether to call an endpoint rather than someone writing a schema.

  A composite says what it CONTAINS — `list of string`, not `list`. The
  containing kind alone answers a question nobody asked, and it is the failure
  mode of every generated API browser: a column that reads `array` for both
  `[:sequential :string]` and `[:sequential [:map …]]`.

  **An unknown form falls back to its own printed shape**, never to a blank or
  to an invented word. This function cannot be right about a schema kind slopp
  adds after it is written, and `[:fn odd?]` on screen is strictly better than
  an empty cell — it is at least the truth, and it is legible enough to act on.
  A default of `\"object\"` or `\"\"` would render a fact this screen does not
  have as one it does."
  [s]
  (cond
    (keyword? s) (case s
                   :string "string" :int "int" :double "double"
                   :boolean "boolean" :any "any" :map "object"
                   (pr-str s))
    (vector? s)  (let [[kind & args] s
                       ;; a properties map is optional and never part of the
                       ;; phrase — [:sequential {:min 1} :string] still reads
                       ;; `list of string`
                       args (if (map? (first args)) (rest args) args)]
                   (case kind
                     :map        "object"
                     :enum       (str "one of: " (str/join ", " args))
                     :maybe      (str (type-label (first args)) " or null")
                     :sequential (str "list of " (type-label (first args)))
                     :vector     (str "list of " (type-label (first args)))
                     :set        (str "set of " (type-label (first args)))
                     :tuple      (str "pair of " (str/join ", " (map type-label args)))
                     ;; DISTINCT labels, and a count when they collapse to one. Joining
                     ;; every branch produced `either object or object` for a
                     ;; success-or-error `:or` — true, useless, and reading
                     ;; exactly like a defect. Where the branches genuinely
                     ;; differ the concrete phrase is the useful one and stays;
                     ;; where they do not, the only new fact is HOW MANY, and
                     ;; the numbered branches underneath carry the rest.
                     ;;
                     ;; "shapes" rather than a pluralised label, because the
                     ;; label is a phrase: `one of 2 list of strings` is what
                     ;; adding an s to `list of string` gets you.
                     :or         (let [ls (distinct (map type-label args))]
                                   (if (= 1 (count ls))
                                     (str "one of " (count args) " shapes")
                                     (str "either " (str/join " or " ls))))
                     (pr-str s)))
    :else        (pr-str s)))

(defn- entries-of
  "The `[:map …]` form `s` describes, once its containers are peeled — or nil.

  `[:sequential [:map …]]` and `[:map …]` both have fields worth listing, and
  `[:sequential :string]` and a bare `:map` have none. Peeling is what lets one
  recursion handle a list of objects and an object identically, which is the
  only reason the row shapes come out the same."
  [s]
  (when (vector? s)
    (let [[kind & args] s
          args (if (map? (first args)) (rest args) args)]
      (case kind
        :map                            (seq args)
        (:sequential :vector :set)      (entries-of (first args))
        nil))))

(defn prose
  "`s` as one line of prose — whitespace runs collapsed, ends trimmed — or nil
  when there is nothing left.

  A schema doc is a string VALUE, not a docstring, so a multi-line literal
  carries its source indentation over the wire. slopp hit this within a minute
  of writing their first field doc and shipped four newlines and fourteen
  spaces before catching it.

  Being forgiving is not politeness. The readout renders text verbatim, so an
  embedded newline breaks the LINE as the unit — and the line is what every
  assertion on this screen and every reader scanning it is working in. A
  browser already collapses the same run; matching it costs nothing.

  Blank becomes nil, so a doc of only whitespace is no doc. Rendered, it would
  be an empty line under the field, which reads as a fact that failed to load
  rather than as one nobody wrote.

  **Public because the endpoint page needs it too.** An endpoint's `:doc` is a
  handler's DOCSTRING carried whole, so it arrives with paragraphs and indent
  by construction rather than by an author's slip. A second copy of this rule
  in the view would be the two-producers-of-one-shape pairing this project has
  a standing entry about."
  [s]
  (when s
    (let [t (str/trim (str/replace (str s) #"\s+" " "))]
      (when-not (= "" t) t))))

(defn- alts-of
  "The branches of an `[:or …]`, once its containers are peeled — or nil.

  The sibling of [[entries-of]] and peeled identically, so a `[:sequential
  [:or …]]` opens the way a list of objects does. One rule for what a container
  is, used by both, rather than two that agree until someone edits one."
  [s]
  (when (vector? s)
    (let [[kind & args] s
          args (if (map? (first args)) (rest args) args)]
      (case kind
        :or                        (seq args)
        (:sequential :vector :set) (alts-of (first args))
        nil))))

(defn rows
  "The FIELDS of a malli schema, flattened into depth-indexed rows:

      {:depth 0 :field \"modules\" :type \"list of object\" :optional? false}
      {:depth 1 :field \"module\"  :type \"string\"         :optional? false}

  The schema's own type is NOT a row — the view prints [[type-label]] for that
  on the response's type line. Repeating it as a parent row would indent every
  real field by one and say nothing the line above did not.

  **A list of objects descends into the element.** `list of object` with
  nothing under it is what a generated API browser prints for `array`, and it
  is the single most useless cell such a browser has: two endpoints returning
  completely different arrays render identically.

  **A field's own prose rides along as `:doc` when the schema declares it.**
  Malli entry properties are an open map that travels as a VALUE, so a doc
  written on a field arrives over the wire unchanged — measured against
  `slopp.http.contract/contract-document`, which needs no change for any of it.
  `:description` is honoured too, since that is malli's JSON-Schema spelling
  and an imported schema will use it; `:doc` wins where both appear, because it
  is slopp's word for the same thing everywhere else.

  The key is ABSENT when nobody wrote one, never nil. An absent key means no
  prose was written; a nil is a value, and the view would have to test for both.

  Declaration order is kept rather than sorted. The author's order carries
  meaning — the identifying field is usually first — and an alphabetised field
  list is a different document from the one the endpoint declared.

  **An `:or` opens into NUMBERED branches** — `option 1`, `option 2` — each
  with its own fields under it. The schema names its branches nothing, so a
  number is the only honest label, and it is what `oneOf` renders as everywhere
  else for the same reason.

  This was a recorded gap for two days, on the grounds that no live instance
  existed. One did: `slopp-server.ui.hub/register!` answers `[:or …]` and its own API
  page read `either object or object` with nothing under it — the useless cell
  this namespace exists to be better than, sitting in my own contract while I
  said so."
  [s]
  (letfn [;; what a schema CONTAINS, and the one place that decides. An
          ;; alternation opens into numbered branches; anything else opens into
          ;; its entries. Keeping both in one function is what stops them
          ;; disagreeing about depth — the bug a second recursion would have.
          (under [sub depth]
            (if-let [alts (alts-of sub)]
              (mapcat (fn [i alt]
                        (cons {:depth     depth
                               ;; the schema names its branches nothing, so a
                               ;; NUMBER is the only honest label — and it is
                               ;; what `oneOf` renders as everywhere else, for
                               ;; exactly the same reason.
                               :field     (str "option " (inc i))
                               :type      (type-label alt)
                               :optional? false
                               :alt?      true}
                              (under alt (inc depth))))
                      (range) alts)
              (walk (entries-of sub) depth)))
          (walk [entries depth]
            (mapcat (fn [[k props sub]]
                      ;; malli entries are [k schema] or [k props schema], and
                      ;; the two are told apart by whether the middle is a map —
                      ;; `[:gaps [:map …]]` has a VECTOR there, not properties
                      (let [[props sub] (if (and (map? props) (nil? sub))
                                          [props nil]
                                          (if (map? props) [props sub] [nil props]))
                            sub         (or sub (when-not (map? props) props))]
                        (cons (cond-> {:depth     depth
                                     :field     (name k)
                                     :type      (type-label sub)
                                     :optional? (boolean (:optional props))}
                              ;; ABSENT rather than nil when nobody wrote one:
                              ;; a missing key says "no prose", a nil is a
                              ;; value, and the view would have to test both
                              (prose (or (:doc props) (:description props)))
                              (assoc :doc (prose (or (:doc props) (:description props)))))
                              (under sub (inc depth)))))
                    entries))]
    (vec (under s 0))))

(defn nest
  "[[rows]] as a tree — each row gains `:children`, deeper rows moving inside
  the row above them.

  Two shapes of the same fact, and both are wanted. Flat is what an assertion
  compares; nested is what the view renders, because depth carried by a
  `padding-left` is nothing at all to a reader without the stylesheet — the
  rule this project has already paid for three times.

  Written as a recursive split rather than a fold with a stack. The fold is
  shorter and the case it gets wrong is a level POPPING OUT by more than one,
  which is common in these schemas and looks plausible when it is wrong: the
  subtree does not vanish, it reattaches one level too deep."
  [rows]
  (let [rows (vec rows)]
    (letfn [(children-of [i]
              ;; every row after i that is deeper than i, up to the next row at
              ;; i's own depth or shallower
              (let [d (:depth (rows i))]
                (->> (range (inc i) (count rows))
                     (take-while #(> (:depth (rows %)) d))
                     vec)))
            (build [idxs]
              (let [d (some->> idxs first rows :depth)]
                (vec (for [i idxs :when (= d (:depth (rows i)))]
                       (assoc (rows i) :children (build (children-of i)))))))]
      (build (vec (range (count rows)))))))

(defn path-params
  "The NAMED parameter names in a route `path`, in path order — `:x` segments.

  Read off the PATH rather than the request schema because the document is not
  consistent about them: measured 2026-08-08, `/api/form/:id` declares `:id` in
  its `:request` while `/api/module/:m`, `/api/change/:range`, `/api/ns/:ns`
  and `/api/source/:ns/:name` all declare `:request nil` and carry one anyway.

  That is not a workaround waiting on a fix. A path parameter is a fact about
  the PATH, and deriving it here means the screen is right about it whichever
  convention an endpoint's author followed.

  **A WILDCARD is not one of them, and this used to say the opposite.** slopp's
  grammar (d36976) has exactly two — `*` for one segment, `**` for zero or more
  — both end-only and both ANONYMOUS, because a route may carry at most one and
  so needs no name for it. The old text argued a splat *counts and keeps its
  name*, citing `/p/:slug/api/*path` on this project's own hub; that spelling
  now matches nothing, and the code behind the argument named `**` as a
  parameter called `*` and `*` as one called the EMPTY STRING.

  The argument it was making survives and belongs elsewhere: a surface view
  that silently dropped the wildcard would describe an endpoint nobody could
  call. What a caller supplies for the remainder is real, and the answer is not
  to pretend it has a name: [[url-parts]] substitutes it under the key `\"*\"`,
  measured rather than assumed.

  **The gap that leaves, stated because it is not fixed here.** [[request-fields]]
  builds the ad-hoc call form from THIS function, so a wildcard endpoint now
  offers no box for its remainder and `url-parts` would substitute the empty
  string. No endpoint reachable in this UI carries one — slopp's reviewer API
  has none, and this hub's own proxy is not browsable through it — so the form
  is not wrong about anything today. It would be the moment a project publishes
  a wildcard endpoint of its own."
  [path]
  ;; a WILDCARD segment is skipped, not renamed. It used to be included with
  ;; `(subs % 1)`, which under the new grammar yields a parameter called `*`
  ;; for `**` and one called the EMPTY STRING for `*` — a name on screen that
  ;; no caller can use, and in the second case no name at all.
  (into []
        (comp (filter #(str/starts-with? % ":"))
              (map #(subs % 1)))
        (str/split (or path "") #"/")))

(defn request-fields
  "The fields an ad-hoc call form should OFFER for `endpoint`, in fill order:
  every path parameter, then the request schema's own top-level fields.

  `:in` says which half of the request a field lands in — `:path`, `:query`
  for a GET, `:body` otherwise — so the form can group them without re-deriving
  the rule the [[request-for]] uses.

  **Top level only.** A flat form cannot fill `gaps.forms`, and rendering a box
  for it would invite a value with nowhere to go. An endpoint whose body is
  genuinely nested wants a body editor, which this is not.

  A path parameter the request schema ALSO declares is listed once, as
  `:path` — `/api/form/:id` declares `:id` in both, and two boxes for one
  value is a form that can contradict itself."
  [{:keys [method path request]}]
  (let [in-path (path-params path)
        want    (if (= :get (or method :get)) :query :body)]
    (into (mapv (fn [p] {:field p :type "string" :in :path :optional? false})
                in-path)
          (comp (remove #(some #{(:field %)} in-path))
                ;; `:doc` survives this dissoc deliberately: a box labelled with what
                ;; it is FOR is most of what a call form is worth, and it is the
                ;; one thing about a field a reader cannot infer from its type
                (map #(-> % (assoc :in want) (dissoc :depth :children))))
          (filter #(zero? (:depth %)) (rows request)))))

(defn- typed
  "The string `s` as the value `type` describes — or `s` unchanged.

  Only a BODY needs this. A query string is text on the wire whatever the
  schema says and the server coerces it; a JSON body is not, so `\"3\"` where
  `:int` is declared is a request that fails validation for a reason the reader
  cannot see from the form they filled in.

  `type` is [[type-label]]'s phrase, so `[:maybe :int]` arrives as
  `int or null` — the type INSIDE the maybe is the one the wire wants, and a
  prefix match reads it.

  **Portable by construction, which is why this needed nothing from
  `slopp.lang`.** A hand-rolled parse would have wanted `Long/parseLong` on one
  platform and `js/parseInt` on the other — a reader conditional, which the
  dialect refuses. `edn/read-string` exists on both, and a regex GATE in front
  of it means it can never be handed anything it could fail on, so there is no
  `catch` to make platform-shaped either.

  **A value that will not convert stays the string it was**, rather than
  becoming nil. The endpoint then refuses it and says why; a silent nil would
  be this form inventing an omission the reader did not make, which is the
  false-absence class this project has bugs recorded for."
  [type s]
  (let [t (str type)]
    (cond
      (str/starts-with? t "boolean")
      (case s "true" true "false" false s)

      (and (str/starts-with? t "int") (re-matches #"[-+]?\d+" s))
      (edn/read-string s)

      (and (str/starts-with? t "double") (re-matches #"[-+]?\d*\.?\d+([eE][-+]?\d+)?" s))
      (double (edn/read-string s))

      :else s)))

(defn request-for
  "The ad-hoc call `endpoint` describes, given `filled` — a flat map of field
  name to typed-in string — as a REQUEST:

      {:http/method :get :http/url \"/api/form/f1?depth=2\"}

  **It used to stop one step short of a url, and that is no longer the
  design.** Assembling one means percent-encoding, which is a platform
  difference — `encodeURIComponent` in a browser, `URLEncoder` on a JVM — and
  this dialect refuses reader conditionals, so the last step belonged to the
  performer. `slopp.http.endpoint/request` does that assembly in `:cljc`,
  segment-wise and fully encoded, and every OTHER request in this app now
  arrives at the performer finished. Leaving this one half-built made it the
  only request `slopp.webapp/addressed` could not measure and `:webapp/call`
  could not send — a control that renders, presses, and reaches nothing.

  So this builds a DESCRIPTOR from the document row and hands it over. The row
  is not a generated var, which is the whole point of an ad-hoc call: the
  endpoint being described is one the READER chose from a document, not one
  this app was compiled against.

  **`measure` is applied to that descriptor before the request is built** —
  the caller's `at-project`, which stamps the project's `:webapp/base` and
  takes the API prefix off the path. Without it the descriptor was measured
  from nothing: every other request in this app went through `at-project`
  and this one went to the origin's `/api/modules`, which the daemon does not
  serve, so the execute button reached a 404 in a browser and a canned nil
  headless, and no test asserted the url it sent. The 2-arity measures from
  nothing, for a caller with no project.

  **`:http/params` is built from the fields, and it has to be.**
  `endpoint/request` REFUSES a param the descriptor does not name — the guard
  that closed `?slug=demo` reaching a stranger — so a descriptor assembled here
  must declare exactly what this form collected, or the reader's own input is
  refused as a leak.

  A GET's request schema is its QUERY STRING; any other method's is its BODY,
  and `endpoint/request` places what the path does not consume by the VERB, so
  that split is no longer spelled here.

  **A blank value is omitted from the query, and a blank PATH parameter is
  not.** `?view=` asks for the empty view rather than for no view, and this app
  has a bug on record from an endpoint reading a parameter it was never meant
  to get. A missing path parameter is the opposite case: leaving it out changes
  which endpoint is addressed, so it travels blank and the url is visibly
  wrong — `/api/form/` is a 404 a reader understands, `/api/form` is somewhere
  else."
  ([endpoint filled] (request-for endpoint filled identity))
  ([{:keys [method path] :as endpoint} filled measure]
   (let [m      (or method :get)
         get?   (= :get m)
         blank? (fn [v] (or (nil? v) (= "" v)))
         fields (request-fields endpoint)
         ;; blank and present, never absent — see the docstring
         in-path (into {} (for [p (path-params path)]
                            [(keyword p) (str (get filled p ""))]))
         ;; a BODY is typed, a query is not. JSON carries numbers and booleans
         ;; as themselves; a query string carries everything as text and the
         ;; server coerces, which is why only one of these converts.
         sent    (into {} (for [{:keys [field in type]} fields
                                :when (= in (if get? :query :body))
                                :let  [v (get filled field)]
                                :when (not (blank? v))]
                            [(keyword field) (if get? v (typed type v))]))
         params  (merge in-path sent)]
     (endpoint/request (measure {:http/method m
                                 :http/path   path
                                 :http/params (set (keys params))})
                       params))))

(defn url-parts
  "A [[request-for]] request split so the performer has nothing left to decide:

      {:path-parts  [[:lit \"api\"] [:lit \"module\"] [:enc \"slopp.ops\"]]
       :query-parts [[[:enc \"q\"] [:enc \"a b\"]]]}

  `:lit` passes through, `:enc` gets percent-encoded. Joining is `/` for the
  path and `=`/`&` for the query, which the performer already knows.

  **Everything except the encode call is decided here, deliberately.** The
  performer lives in `client.app`, the one namespace the JVM oracle cannot
  reach, so anything it decides is something no test can see. The concrete bug
  this exists to prevent: a performer doing `str/replace` on `:m` would
  corrupt `/api/:module/:m`, and that would ship — the string is assembled in
  a browser and asserted nowhere.

  Segment-wise rather than by substitution for that exact reason. A parameter
  whose name is a prefix of another is a normal thing for an API to have."
  [{:keys [path path-params query]}]
  {:path-parts  (into []
                      (comp (remove str/blank?)
                            (mapcat
                             (fn [seg]
                               (cond
                                 ;; a WILDCARD's value is a REMAINDER: one part per
                                 ;; sub-segment, so its slashes survive the encode as
                                 ;; separators. Encoded whole, `form/f1` becomes
                                 ;; `form%2Ff1` and the matcher — which decodes each
                                 ;; sub-segment and then joins — never closes the
                                 ;; round trip.
                                 ;;
                                 ;; ZERO parts when there is nothing to put there:
                                 ;; `**` matches zero or more, and `[:enc ""]` would
                                 ;; join into a trailing slash on a url that should
                                 ;; simply stop.
                                 (str/starts-with? seg "*")
                                 (into [] (comp (remove str/blank?) (map #(vector :enc %)))
                                       (str/split (str (get path-params "*" "")) #"/"))

                                 ;; a NAMED segment's value is DATA. A slash in it is
                                 ;; a slash in a value — the endpoint declared one
                                 ;; segment and gets one — so it is escaped, which is
                                 ;; the opposite of the case above and the reason
                                 ;; these are two branches rather than one.
                                 (str/starts-with? seg ":")
                                 [[:enc (str (get path-params (subs seg 1) ""))]]

                                 :else [[:lit seg]]))))
                      (str/split (or path "") #"/"))
   :query-parts (vec (for [[k v] query] [[:enc (str k)] [:enc (str v)]]))})

(defn handler-source-path
  "The app path showing `handler`'s source — `/store/source/<ns>/<name>` — or
  nil when the document did not publish a qualified one.

  `:handler` arrived on the wire on 2026-08-09 and is what turns the API page's
  link from a SEARCH into a drill-down. Before it, the document gave the
  endpoint's `:name` and nothing about where the name lived, and on slopp's own
  store three of nine names had more than one exact match.

  **No escaping, unlike [[query-escape]].** A `+` in a path SEGMENT is literal —
  it only means a space in a query — and `/` cannot appear in a symbol's name,
  so both segments travel whole.

  **Nil rather than a half-built path** when the handler is unqualified or
  absent. `/store/source/modules` routes to nothing, so a link built from it
  would 404 from a control this screen offered; no link is better, and the view
  falls back to the search it used before `:handler` existed."
  [handler]
  (when-let [s (not-empty (str (or handler "")))]
    (let [[ns' nm] (str/split s #"/" 2)]
      (when (and (not-empty ns') (not-empty nm))
        (str "/store/source/" ns' "/" nm)))))

(defn first-sentence
  "The first sentence of `s`, for a place with room for one line — or nil.

  The document ships an endpoint's docstring WHOLE, which is what I asked for:
  a document that shipped only a first line could not be un-truncated by a
  consumer that wanted the rest, and the reverse is free. This is the cost of
  that bargain, paid here.

  **Never truncates to nothing.** A doc with no full stop yields the whole
  thing rather than the empty string — a summary column that blanks on an
  unpunctuated docstring is worse than one that shows too much, because blank
  reads as `no prose was written` and that would be false.

  Runs through [[prose]] first, so a multi-line docstring is one line before
  anything looks for a sentence in it."
  [s]
  (when-let [t (prose s)]
    (if-let [m (re-find #"^.*?[.!?](?=\s|$)" t)] m t)))

(defn summary-of
  "One line describing `endpoint`, for an index row — or nil.

  [[first-sentence]] of its `:doc`, with a leading `GET /api/x — ` removed when
  that method and path are THIS endpoint's own. The summary column is the
  narrowest thing on the index and it was opening with a copy of the two
  columns to its left.

  **Compared for EQUALITY against values already in hand**, which is what
  separates it from the handler heuristic this project refused. There, picking
  one of several exact search matches would have meant a rule about slopp's
  namespace naming, dressed as a fact about the document. Here the prefix
  either literally is this endpoint's method and path or nothing is removed —
  no convention is assumed and a doc describing something else keeps every
  word.

  **Never strips to nothing.** A doc that is only the prefix has said nothing
  the two columns did not, and the honest rendering of nothing is nothing —
  a blank cell reads as a missing value, which would be the same lie in the
  other direction."
  [{:keys [method path doc]}]
  (when-let [s (first-sentence doc)]
    (let [head (str (str/upper-case (name (or method :get))) " " path)]
      (not-empty
       (str/trim
        (if (str/starts-with? s head)
          ;; the separator is whatever the author used — an em dash here, but
          ;; the trim handles a colon, a hyphen or nothing at all without this
          ;; needing to know which
          (str/replace (subs s (count head)) #"^[\s—–-]+" "")
          s))))))

(defn auth-label
  "`auth` as the phrase a reader sees for who may call — or nil when the
  document does not say.

  slopp publishes the `:http/auth` DECLARATION verbatim rather than a boolean,
  and that is the reason it is worth rendering: `:authed? true` would have lost
  the half a reader actually needs, which is WHO.

  It is the cheapest key in that document to trust. The auth write gate refuses
  an endpoint that declares no `:http/auth`, so unlike `:request` it can never be
  nil-because-nobody-said — and `public` never has to be told from `unknown`.

  **Nil for absent, never `public`.** A document from an older jar carries no
  `:auth` at all, and rendering that as public would be this screen asserting an
  access rule nobody published — the worst direction for a wrong answer about
  who may call.

  Anything other than `:public` prints as its own data. The vocabulary is
  slopp's and it will grow; inventing a word for `[:group \"admin\"]` would be
  claiming to know an access rule this screen has never met. Same rule as
  [[type-label]]'s fallback, and for the same reason."
  [auth]
  (when (some? auth)
    (if (= :public auth) "public" (pr-str auth))))

(defn effectful?
  "Whether calling `endpoint` CHANGES something — read from its method.

  **Not from `:effectful?`, which no slopp server publishes.** This app asked
  for that key alongside `:auth`; `:auth` shipped 2026-08-11 and this one did
  not, and the screens went on reading it anyway. Measured 2026-08-24 on the
  hub's own contract document — the only one in reach with POST endpoints in
  it — every endpoint carries nine keys and `:effectful?` is not among them.

  What that cost is the reason this is a named function rather than an inline
  `(#{:post …} method)`: the two-press arming gate before a mutation fires read
  a nil on every real document, so it never engaged, and a POST went out on the
  first click. Every test passed, because `slopp-server.ui.page/contract-document`
  invents the key and the fixture was the only place it had ever existed.

  So the fact has to come from something PUBLISHED, and `:method` is in the
  same document, is never absent in practice, and is what the missing key would
  have been derived from anyway.

  **`:get` and `:head` are the safe pair**, and this read `(not= :get …)` until
  slopp published their own derivation to compare against. A HEAD is a GET
  without a body, so a two-press gate in front of one guards a request that
  cannot change anything. Their version is
  `(or (:http/effectful m) (not (contains? #{:get :head} method)))` — and the
  first arm is worth knowing about rather than copying: slopp's own ten
  endpoints declare `:http/effectful` ZERO times, so publishing the marker
  verbatim would have shipped `false` everywhere and moved this gate from
  inert-on-nil to inert-on-FALSE. Worse, because false looks like an answer.

  So the published `:effectful?` (contract version 2) is CONFIRMATION here, not
  a dependency. Reading it would re-acquire the coupling this function exists to
  remove, and would stop working on any producer older than the decision.

  **The default is `:get`**, matching [[request-fields]] and `views/try-panel`:
  an endpoint that somehow arrives with no method is read as read-only. That
  direction is deliberate but it is the WEAK one — a mutation misread as safe
  loses the confirmation, where a read misread as effectful only costs a click.
  It is defensible only because a document without `:method` is not a shape
  slopp can emit; if that ever stops being true, this default should flip and
  the endpoint should be refused instead."
  [{:keys [method]}]
  (not (contains? #{:get :head} (or method :get))))

(defn content-label
  "What a content page's VALUE is, as the phrase a reader sees — or nil when
  the document does not say.

  **The document describes the value rather than shipping it**, and this is the
  screen's half of that bargain. Publishing bodies would have put 14 kB of CSS
  on the wire to render one table row; what arrives instead is `:shape`, a size,
  and for hiccup a `:root-tag`. This turns those into one line.

  `:shape` is the framework's own discriminator — `vector?` decides whether the
  dispatcher serves `text/html` or `text/plain` — so it is the one key that is
  always present and the one this returns nil without.

  **`:root-tag :html` means a WHOLE document**, because `slopp.http.html/render`
  prepends the doctype to exactly that; anything else is a fragment, which is a
  different thing to serve and worth naming as its own tag rather than
  flattening both into `hiccup`.

  A missing size is DROPPED, never rendered as zero. `:nodes` and `:bytes` are
  `{:optional true}` in the published schema, and `0 bytes` is a claim the
  document did not make — the same stance [[auth-label]] takes on absent auth,
  for the same reason.

  Sizes are the units a reader thinks in: bytes below a kilobyte, one decimal
  above. `:nodes` is a count and stays a count — it is the tree AS AUTHORED,
  which the document says explicitly because what gets served has the bundle
  injected and would depend on where the app is mounted."
  [{:keys [shape root-tag nodes bytes]}]
  (when shape
    (->> [(clojure.core/name shape)
          (when (= :hiccup shape)
            (when root-tag
              (if (= :html root-tag)
                "whole document"
                (str "<" (clojure.core/name root-tag) "> fragment"))))
          (cond
            (and (= :hiccup shape) nodes) (str nodes " nodes")
            (and (= :text shape) bytes)
            (if (< bytes 1024)
              (str bytes " bytes")
              ;; 1000, not 1024, because the label says `kB`. Divided by 1024 this
              ;; read `13.9 kB` for 14,263 bytes — a number that is only right
              ;; under a unit the text does not name. `KiB` would be the other
              ;; consistent pair and is not what a reader of a page size expects.
              (str (/ (Math/round (double (/ bytes 100.0))) 10.0) " kB")))]
         (remove nil?)
         (str/join " · "))))
