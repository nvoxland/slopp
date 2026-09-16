(ns slopp-server.ui.schema-test
  "What the API section is allowed to SAY about a schema.

  Every assertion here is about a reading rather than a shape: that a list says
  what it holds, that an enum lists its values, that depth survives as
  structure, and that a form this renderer does not know still prints something
  true. The cases written out at length are the ones a worse renderer would
  also pass — `list of object` with nothing under it, `either object or object`
  with no fields, a fold that reattaches a subtree one level too deep.

  The `:or` gap is PINNED rather than fixed, so the day someone flattens an
  alternation this file is what has to change and the deferral cannot rot into
  a silent limitation."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp-server.ui.schema :as schema]))

(deftest a-schema-form-reads-as-a-phrase-a-reader-can-use
  ;; The label is what stands where OpenAPI would print `"type": "string"`.
  ;; It is prose rather than the schema's own spelling on purpose: `:sequential`
  ;; is malli's word and "list of" is the reader's, and this screen exists for
  ;; someone deciding whether to call the endpoint.
  (testing "the scalars, named the way the wire names them"
    (is (= "string"  (schema/type-label :string)))
    (is (= "int"     (schema/type-label :int)))
    (is (= "double"  (schema/type-label :double)))
    (is (= "boolean" (schema/type-label :boolean)))
    (is (= "any"     (schema/type-label :any))))

  (testing "a collection says what it holds — `list` alone is the answer to a
            question nobody asked"
    (is (= "list of string" (schema/type-label [:sequential :string])))
    (is (= "list of object" (schema/type-label [:sequential [:map [:a :string]]])))
    (is (= "list of list of string"
           (schema/type-label [:sequential [:sequential :string]]))))

  (testing "an enum lists its values. They are the whole content of the type,
            and an API browser that says `enum` and stops has told the reader
            nothing they could not have guessed"
    (is (= "one of: pure, internal, external"
           (schema/type-label [:enum "pure" "internal" "external"]))))

  (testing "a maybe is spelled as the nullability it is"
    (is (= "string or null" (schema/type-label [:maybe :string]))))

  (testing "a tuple keeps its arity — [:tuple :string :string] is a PAIR, and
            `list of string` would be a different contract"
    (is (= "pair of string, string" (schema/type-label [:tuple :string :string]))))

  (testing "an alternation names its branches where they DIFFER — a reader has
            to handle both and the concrete phrase is what tells them apart"
    (is (= "either string or int" (schema/type-label [:or :string :int]))))

  (testing "and counts them where they do not. This asserted `either object or
            object` — true, and a sentence that says nothing twice. Both
            branches of the register endpoint's response are maps, so joining
            per-branch labels produced a phrase indistinguishable from a bug;
            the only new fact is how many, and the numbered branches rendered
            under it carry the fields"
    (is (= "one of 2 shapes"
           (schema/type-label [:or [:map [:slug :string]] [:map [:error :string]]])))
    ;; "shapes", not a pluralised label: the label is a phrase, and adding an s
    ;; to `list of string` gets you `one of 2 list of strings`
    (is (= "one of 2 shapes"
           (schema/type-label [:or [:sequential :string] [:sequential :string]]))))

  (testing "a map is an `object`, and it is the one label that promises MORE
            below it — the rows carry its fields, this only names the kind"
    (is (= "object" (schema/type-label [:map [:a :string]])))
    (is (= "object" (schema/type-label :map))))

  (testing "and an unrecognised form falls back to its own printed shape rather
            than to a blank or an invented word. A schema this does not know is
            a fact about the endpoint, and showing it is strictly better than
            showing nothing — this screen has no way to be right about a form
            slopp added since it was written"
    (is (= "[:fn odd?]" (schema/type-label [:fn 'odd?])))
    (is (= ":uuid" (schema/type-label :uuid)))))

(deftest a-schema-flattens-into-fields-a-reader-can-scan
  (testing "a map's entries, in declaration order, at depth 0"
    (is (= [{:depth 0 :field "ns" :type "string" :optional? false}
            {:depth 0 :field "forms" :type "int" :optional? false}]
           (schema/rows [:map [:ns :string] [:forms :int]]))))

  (testing "a nested map descends, and the parent keeps a row of its own —
            dropping it would leave the child fields hanging under nothing"
    (is (= [{:depth 0 :field "gaps" :type "object" :optional? false}
            {:depth 1 :field "forms" :type "int" :optional? false}]
           (schema/rows [:map [:gaps [:map [:forms :int]]]]))))

  (testing "a list OF maps descends into the element. `list of object` with no
            fields under it is the exact failure this is written against — it
            is what a generated browser prints for `array` and it tells the
            reader nothing about what is in the array"
    (is (= [{:depth 0 :field "modules" :type "list of object" :optional? false}
            {:depth 1 :field "module" :type "string" :optional? false}]
           (schema/rows [:map [:modules [:sequential [:map [:module :string]]]]]))))

  (testing "optionality travels, because it is the field a caller gets wrong"
    (is (= [{:depth 0 :field "q" :type "string" :optional? true}
            {:depth 0 :field "limit" :type "int" :optional? true}]
           (schema/rows [:map [:q {:optional true} :string]
                         [:limit {:optional true} :int]]))))

  (testing "a root that is itself a list of objects puts those fields at depth
            0 — the `list of` fact belongs to the response's own type line, and
            repeating it as a parent row would indent every real field by one
            for no information"
    (is (= [{:depth 0 :field "ns" :type "string" :optional? false}
            {:depth 0 :field "forms" :type "int" :optional? false}]
           (schema/rows [:sequential [:map [:ns :string] [:forms :int]]]))))

  (testing "a scalar root has no fields. Not an invented single row — the view
            prints its type-label and there is nothing else true to say"
    (is (= [] (schema/rows :string)))
    (is (= [] (schema/rows nil)))
    (is (= [] (schema/rows [:sequential :string]))))

  (testing "a bare :map has no declared entries and so has no rows, which is
            different from having none — `object` is all this endpoint said"
    (is (= [] (schema/rows :map)))
    (is (= [{:depth 0 :field "forms" :type "list of object" :optional? false}]
           (schema/rows [:map [:forms [:sequential :map]]]))))

  (testing "an ALTERNATION opens into its branches, numbered. The schema gives
            them no names, so `option 1` is the only honest label — and it is
            what `oneOf` renders as everywhere else for the same reason.
            `either object or object` with nothing under it was the useless
            cell this whole namespace exists to be better than, and it was
            sitting in my own hub's contract while I said so"
    (is (= [{:depth 0 :field "option 1" :type "object" :optional? false :alt? true}
            {:depth 1 :field "slug" :type "string" :optional? false}
            {:depth 0 :field "option 2" :type "object" :optional? false :alt? true}
            {:depth 1 :field "error" :type "string" :optional? false}]
           (schema/rows [:or [:map [:slug :string]] [:map [:error :string]]]))))

  (testing "a branch that is a SCALAR gets its option row and no children —
            `[:or :string [:map …]]` is a real shape and the string half has
            nothing to open"
    (is (= [{:depth 0 :field "option 1" :type "string" :optional? false :alt? true}
            {:depth 0 :field "option 2" :type "object" :optional? false :alt? true}
            {:depth 1 :field "a" :type "int" :optional? false}]
           (schema/rows [:or :string [:map [:a :int]]]))))

  (testing "and an alternation NESTED under a field opens there too, at that
            field's depth — the register endpoint's shape is top-level, but
            nothing says the next one will be"
    (is (= ["result" "option 1" "slug" "option 2" "error"]
           (map :field (schema/rows [:map [:result [:or [:map [:slug :string]]
                                                   [:map [:error :string]]]]]))))))

(deftest depth-becomes-structure-because-css-indentation-is-not-readable
  ;; `rows` is flat because that is what an assertion can compare. The SCREEN
  ;; needs nesting: a field list whose depth is a `padding-left` reads as one
  ;; undifferentiated column to anything without a stylesheet, which is this
  ;; project's oldest standing rule and the source of three of its bugs.
  (let [r (fn [d f] {:depth d :field f :type "string" :optional? false})]

    (testing "siblings stay siblings"
      (is (= [(assoc (r 0 "a") :children [])
              (assoc (r 0 "b") :children [])]
             (schema/nest [(r 0 "a") (r 0 "b")]))))

    (testing "a deeper row becomes a child of the row above it"
      (is (= [(assoc (r 0 "a") :children [(assoc (r 1 "b") :children [])])]
             (schema/nest [(r 0 "a") (r 1 "b")]))))

    (testing "and the level POPS back out — the case a naive fold gets wrong,
              since `c` belongs beside `a` and not inside `b`"
      (is (= [(assoc (r 0 "a") :children [(assoc (r 1 "b") :children [])])
              (assoc (r 0 "c") :children [])]
             (schema/nest [(r 0 "a") (r 1 "b") (r 0 "c")]))))

    (testing "popping more than one level at once"
      (is (= [(assoc (r 0 "a") :children
                     [(assoc (r 1 "b") :children [(assoc (r 2 "c") :children [])])])
              (assoc (r 0 "d") :children [])]
             (schema/nest [(r 0 "a") (r 1 "b") (r 2 "c") (r 0 "d")]))))

    (testing "nothing in, nothing out"
      (is (= [] (schema/nest []))))

    (testing "on a real schema, every leaf survives the nesting — the count is
              the check, because a fold that loses a subtree still looks
              plausible in a spot check"
      (let [flat (schema/rows [:map [:modules [:sequential
                                               [:map [:module :string]
                                                [:gaps [:map [:forms :int] [:no-doc :int]]]]]]
                               [:cycles [:sequential [:sequential :string]]]])
            tree (schema/nest flat)
            leaves (fn leaves [ns] (mapcat #(cons (:field %) (leaves (:children %))) ns))]
        (is (= (count flat) (count (leaves tree))))
        (is (= ["modules" "module" "gaps" "forms" "no-doc" "cycles"]
               (leaves tree)))))))

(deftest a-path-declares-parameters-the-request-schema-does-not
  ;; Measured on the live contract document, 2026-08-08: `/api/form/:id`
  ;; declares `:id` in its `:request`, and `/api/module/:m`,
  ;; `/api/change/:range`, `/api/ns/:ns` and `/api/source/:ns/:name` all declare
  ;; `:request nil` while carrying path parameters. Same document, two
  ;; conventions.
  ;;
  ;; So the path itself is the only source that is right for all of them, and
  ;; the screen reads it. Not a workaround to be removed when the document is
  ;; fixed: a path parameter is a fact ABOUT THE PATH, and deriving it here
  ;; means the screen cannot be wrong about it whichever way the schema goes.
  (testing "a segment beginning with a colon is a parameter, in path order"
    (is (= ["ns" "name"] (schema/path-params "/api/source/:ns/:name")))
    (is (= ["m"] (schema/path-params "/api/module/:m")))
    (is (= [] (schema/path-params "/api/modules"))))

  (testing "a WILDCARD is not a named parameter — slopp's grammar (d36976) has
            exactly two, `*` for one segment and `**` for zero or more, both
            end-only and both ANONYMOUS, because a route may carry at most one
            and so needs no name for it"
    ;; This used to assert `["slug" "path"]` for `/p/:slug/api/*path`, on the
    ;; argument that a surface view dropping the wildcard would describe an
    ;; endpoint nobody could call. The argument still holds and the SPELLING
    ;; does not: a named splat matches nothing now, and reporting `*` as a
    ;; parameter called `*` would put a name on screen that no caller can use.
    ;;
    ;; What a caller supplies for the remainder is a real question and it is
    ;; NOT answered by pretending it has a name — see `url-parts`, which
    ;; interpolates it from `:*`.
    (is (= ["slug"] (schema/path-params "/p/:slug/api/**")))
    (is (= ["slug"] (schema/path-params "/p/:slug/api/*")))
    (is (= [] (schema/path-params "/**"))))

  (testing "the root, and paths with no parameters at all"
    (is (= [] (schema/path-params "/")))
    (is (= [] (schema/path-params "")))
    (is (= [] (schema/path-params nil)))))

(deftest an-ad-hoc-call-is-a-REQUEST-not-a-url-string
  ;; Executing an endpoint means percent-encoding, and this dialect refuses
  ;; reader conditionals — so for a long time the portable layer stopped one
  ;; step short of a string, producing the request as DATA for the performer to
  ;; assemble.
  ;;
  ;; **It does not stop short any more, and the reason is the whole of Move A.**
  ;; `slopp.http.endpoint/request` does that assembly in `:cljc`, segment-wise
  ;; and fully encoded, and every other request in this app arrives at the
  ;; performer finished. Leaving this one half-built made it the only request
  ;; `slopp.webapp/addressed` could not measure and `:webapp/call` could not
  ;; send. The argument for portability is unchanged and now costs nothing: the
  ;; url is something an in-image test COMPUTES, which was impossible while the
  ;; assembly lived in `:cljs`.
  (let [ep {:method :get :path "/api/form/:id" :name 'form
            :request [:map [:id :string]
                      [:view {:optional true} :string]
                      [:depth {:optional true} :int]]}]

    (testing "a field named in the PATH is a path parameter; the rest of the
              request schema is the query string. Both come from one filled-in
              map, because a reader typing into a form does not know or care
              which half of the url a field lands in"
      (is (= {:http/method :get :http/url "/api/form/f1?depth=2"}
             (schema/request-for ep {"id" "f1" "depth" "2"}))))

    (testing "a blank is OMITTED, not sent as empty. `?view=` is a request for
              the empty view and this app already has a bug on record from an
              endpoint reading a parameter it was never meant to get"
      (is (= {:http/method :get :http/url "/api/form/f1"}
             (schema/request-for ep {"id" "f1" "view" "" "depth" nil}))))

    (testing "a path parameter with no value still travels, as blank — the url
              is then visibly wrong rather than silently a different one.
              `/api/form/` is a 404 a reader can understand; `/api/form` is a
              different endpoint"
      (is (= "/api/form/" (:http/url (schema/request-for ep {})))))

    (testing "an endpoint with no request schema still gets its path parameters
              — the document does not declare them consistently and the path
              does"
      (is (= {:http/method :get :http/url "/api/module/slopp.ops"}
             (schema/request-for {:method :get :path "/api/module/:m"}
                                 {"m" "slopp.ops"}))))

    (testing "a method that is not GET carries a BODY rather than a query — its
              request schema describes what goes in the body, and putting those
              fields in the url would be a different call entirely"
      (is (= {:http/method :post :http/url "/api/register" :http/body {:dir "/tmp/x"}}
             (schema/request-for {:method :post :path "/api/register"
                                  :request [:map [:dir :string]]}
                                 {"dir" "/tmp/x"}))))

    (testing "the encoding is the half that was thirty-five lines of UTF-8 bit
              math when this app considered doing it — a `/` in a value must not
              become another path segment"
      (is (= "/api/module/a.b%2Fc"
             (:http/url (schema/request-for
                         {:method :get :path "/api/module/:m"}
                         {"m" "a.b/c"})))))

    (testing "the fields a form should OFFER: path parameters first, then the
              request schema's own top-level fields, each with its type and
              whether it is required"
      (is (= [{:field "id"    :type "string" :in :path  :optional? false}
              {:field "view"  :type "string" :in :query :optional? true}
              {:field "depth" :type "int"    :in :query :optional? true}]
             (schema/request-fields ep))))

    (testing "and a nested field is NOT offered. Only the top level is fillable
              from a flat form, and rendering a box for `gaps.forms` would
              invite a value that has nowhere to go"
      (is (= ["dir"] (map :field (schema/request-fields
                                  {:method :post :path "/x"
                                   :request [:map [:dir [:map [:deep :string]]]]})))))))

(deftest a-url-is-split-so-only-the-ENCODING-is-left-to-the-platform
  ;; The performer must not be doing arithmetic. `js/encodeURIComponent` is the
  ;; one thing it legitimately owns — it is the platform difference this
  ;; dialect cannot branch on — and everything around it is decidable here:
  ;; which segments are parameters, which value goes in each, what joins them.
  ;;
  ;; The rule this is written against is `client.app`'s: when it grows, the
  ;; first question is which part of it stopped being tested. A performer that
  ;; split the path itself would carry the one bug a JVM test cannot see.
  (testing "each path segment is either a literal to pass through or a value to
            encode, in order"
    (is (= [[:lit "api"] [:lit "module"] [:enc "slopp.ops"]]
           (:path-parts (schema/url-parts {:path "/api/module/:m"
                                           :path-params {"m" "slopp.ops"}})))))

  (testing "a parameter whose name is a PREFIX of another is not confused with
            it. `:m` inside `/api/:module/:m` is the collision a `str/replace`
            performer would ship, and no JVM test could ever see it"
    (is (= [[:lit "api"] [:enc "a"] [:enc "b"]]
           (:path-parts (schema/url-parts {:path "/api/:module/:m"
                                           :path-params {"module" "a" "m" "b"}})))))

  (testing "a splat is a parameter like any other"
    (is (= [[:lit "p"] [:enc "x"] [:lit "api"] [:enc "modules"]]
           ;; `**` and the `"*"` key — a named splat is retired, and a wildcard's
             ;; value is looked up under the one name the grammar gives it. The
             ;; REMAINDER case is `a-REMAINDER-is-structure-and-a-segment-value-is-data`.
             (:path-parts (schema/url-parts {:path "/p/:slug/api/**"
                                             :path-params {"slug" "x" "*" "modules"}})))))

  (testing "query pairs come out as key and value, both to be encoded"
    (is (= [[[:enc "q"] [:enc "a b"]] [[:enc "limit"] [:enc "5"]]]
           (:query-parts (schema/url-parts {:path "/api/search"
                                            :query {"q" "a b" "limit" "5"}})))))

  (testing "no query, no pairs — so the performer can tell `?` from no `?`
            without inspecting the map itself"
    (is (= [] (:query-parts (schema/url-parts {:path "/api/modules"}))))))

(deftest a-field-carries-its-own-prose-when-the-schema-declares-it
  ;; Malli entry properties are an open map and travel as VALUES, so a doc
  ;; written on a field arrives over the wire unchanged. Measured 2026-08-08
  ;; against `slopp.http.contract/contract-document`: no framework change is
  ;; needed for any of this, and the reason no doc reaches the screen today is
  ;; that nothing declares one and this function used to drop them.
  ;;
  ;; Which is the expensive half. A schema's prose currently lives in the
  ;; DEFINING FORM's docstring — `project-beat` explained `:dir`, the optional
  ;; keys and the `:maybe` reasoning for weeks — and a docstring cannot travel.
  ;; The reader of the published contract is the audience a schema exists for
  ;; and the one audience that prose never reached.
  (testing "a `:doc` property rides on the row"
    (is (= [{:depth 0 :field "dir" :type "string" :optional? false
             :doc "the project root"}]
           (schema/rows [:map [:dir {:doc "the project root"} :string]]))))

  (testing "and it composes with optionality rather than replacing it — the two
            are different facts and a field has both"
    (is (= [{:depth 0 :field "q" :type "string" :optional? true
             :doc "what to search for"}]
           (schema/rows [:map [:q {:optional true :doc "what to search for"} :string]]))))

  (testing "a field with no doc carries no `:doc` KEY, rather than a nil. An
            absent key is `nobody wrote one`; a nil is a value, and the view
            would have to test for both"
    (is (= [{:depth 0 :field "a" :type "string" :optional? false}]
           (schema/rows [:map [:a :string]]))))

  (testing "`:description` is honoured too, because it is malli's own
            JSON-Schema spelling and an imported schema will use it. `:doc`
            wins where both appear — it is slopp's word for the same thing
            everywhere else, and a schema carrying both has already told us
            which vocabulary its author was writing in"
    (is (= "from json" (:doc (first (schema/rows [:map [:a {:description "from json"} :string]])))))
    (is (= "ours" (:doc (first (schema/rows [:map [:a {:doc "ours" :description "theirs"} :string]]))))))

  (testing "nested fields carry theirs too — the prose is most useful exactly
            where the field list is deepest and a reader is least sure what
            they are looking at"
    (is (= ["outer" nil "inner"]
           (map :doc (schema/rows [:map [:a {:doc "outer"} [:map [:b :int]]]
                                   [:c {:doc "inner"} :string]])))))

  (testing "and a fillable field offers its doc, so the call form can label a
            box with what the box is for"
    (is (= [{:field "q" :type "string" :in :query :optional? true
             :doc "what to search for"}]
           (schema/request-fields
            {:method :get :path "/api/search"
             :request [:map [:q {:optional true :doc "what to search for"} :string]]})))))

(deftest a-doc-is-a-VALUE-so-its-source-indentation-travels
  ;; The hazard slopp hit within a minute of writing their first field doc, and
  ;; reported before it could reach me: a schema doc is a string VALUE, not a
  ;; docstring, so a multi-line literal carries its own source indentation over
  ;; the wire — four newlines and fourteen spaces, in their instance.
  ;;
  ;; They fixed their side and said this is an author bug on the producing
  ;; side, which it is. Being forgiving anyway is still right, and for a reason
  ;; that is not politeness: the readout renders text verbatim, so a doc with
  ;; embedded newlines breaks the LINE as the unit — every line-anchored
  ;; assertion on this screen, and every reader scanning it, is reading lines.
  ;; A browser already collapses this run; matching that is not a favour.
  (testing "runs of whitespace collapse to one space, and the ends are trimmed"
    (is (= "one two three"
           (:doc (first (schema/rows [:map [:a {:doc "  one   two\n\n      three  "} :string]]))))))

  (testing "a doc that is only whitespace is no doc at all — it would render as
            an empty line under the field, which reads as a fact that failed to
            load rather than as one nobody wrote"
    (is (nil? (:doc (first (schema/rows [:map [:a {:doc "   \n  "} :string]])))))
    (is (not (contains? (first (schema/rows [:map [:a {:doc ""} :string]])) :doc))))

  (testing "an ordinary one-line doc is untouched"
    (is (= "the project root"
           (:doc (first (schema/rows [:map [:a {:doc "the project root"} :string]])))))))

(deftest a-published-handler-addresses-its-own-source
  ;; `:handler` arrived on the wire 2026-08-09, which is what turns the API
  ;; page's link from a SEARCH into a drill-down. `/store/source/:ns/:name` is
  ;; a screen this app already had; only the address was missing.
  ;;
  ;; No escaping. A `+` in a PATH segment is literal — it only means a space in
  ;; a query — and `/` cannot appear in a symbol's name, so the two segments go
  ;; through whole. That is the difference from `query-escape` next door and
  ;; the reason this needs no encoder.
  (testing "the qualified symbol splits into the two segments the source screen
            takes"
    (is (= "/store/source/slopp-server.api.endpoints/modules"
           (schema/handler-source-path 'slopp-server.api.endpoints/modules))))

  (testing "a name that would need escaping in a QUERY does not need it here"
    (is (= "/store/source/demo.core/merge+"
           (schema/handler-source-path (symbol "demo.core" "merge+")))))

  (testing "it takes a string too — the document is EDN and reads as a symbol,
            but a consumer that got there through JSON has a string, and one
            call site should not have to know which"
    (is (= "/store/source/a.b/c" (schema/handler-source-path "a.b/c"))))

  (testing "and an UNQUALIFIED or absent handler is nil, never a half-built
            path. `/store/source/modules` routes to nothing and would 404 on a
            link this screen offered — worse than offering none"
    (is (nil? (schema/handler-source-path 'modules)))
    (is (nil? (schema/handler-source-path nil)))
    (is (nil? (schema/handler-source-path "")))))

(deftest a-summary-does-not-spend-its-width-repeating-the-columns-beside-it
  ;; Read off the index the minute it had prose in it:
  ;;
  ;;   method  path           what it does
  ;;   GET     /api/modules   GET /api/modules — every module, its namespaces…
  ;;
  ;; The summary column is the narrowest thing on the page and it was opening
  ;; with a copy of the two columns to its left.
  ;;
  ;; **This is not the heuristic refused for the handler**, and the difference
  ;; is the whole reason it is allowed. There, picking one of several exact
  ;; matches would have meant a rule about slopp's namespace naming. Here the
  ;; prefix is compared for EQUALITY against this endpoint's own method and
  ;; path, both already in hand — so it either matches by construction or
  ;; nothing is removed.
  (testing "a doc opening with this endpoint's own method and path loses it"
    (is (= "every module, its gaps."
           (schema/summary-of {:method :get :path "/api/modules"
                               :doc "GET /api/modules — every module, its gaps."}))))

  (testing "a doc opening with a DIFFERENT method or path keeps every word.
            Whatever it is describing, this screen is not entitled to decide
            it was redundant"
    (is (= "GET /api/other — a thing."
           (schema/summary-of {:method :get :path "/api/modules"
                               :doc "GET /api/other — a thing."})))
    (is (= "POST /api/modules — a thing."
           (schema/summary-of {:method :get :path "/api/modules"
                               :doc "POST /api/modules — a thing."}))))

  (testing "a doc with no such prefix at all is untouched"
    (is (= "every module, its gaps."
           (schema/summary-of {:method :get :path "/api/modules"
                               :doc "every module, its gaps."}))))

  (testing "and stripping never empties it. A doc that is ONLY the prefix has
            said nothing beyond the two columns, and the honest rendering of
            nothing is nothing rather than a blank cell that looks like a
            missing value"
    (is (nil? (schema/summary-of {:method :get :path "/api/modules"
                                  :doc "GET /api/modules"}))))

  (testing "no doc, no summary"
    (is (nil? (schema/summary-of {:method :get :path "/api/modules"}))))

  (testing "it is still only the FIRST sentence — the column has room for one"
    (is (= "one." (schema/summary-of {:method :get :path "/x" :doc "one. two."})))))

(deftest a-body-carries-TYPED-values-because-json-is-not-a-query-string
  ;; The difference that makes non-GET execution more than wiring. A query
  ;; string is text on the wire whatever the schema says, and the server
  ;; coerces; a JSON body is not. Sending `"3"` where `:int` is declared is a
  ;; request that fails validation for a reason the reader cannot see from the
  ;; form they filled in.
  ;;
  ;; The declared type is the only thing that can say which is which, and it is
  ;; already in hand — `request-fields` carries it.
  ;;
  ;; Field names are KEYWORDS on the way out, which is a change of key and not
  ;; of typing: what a value BECOMES is decided here exactly as before, and the
  ;; body still reaches `js/fetch` through `clj->js`, which spells a keyword
  ;; back out as the string the endpoint declared.
  ;;
  ;; **The request is `:http/*` now** — `slopp.http.endpoint/request` resolves
  ;; the address and places what the path does not consume BY THE VERB, so the
  ;; query/body split this test is about is the framework's rule rather than
  ;; this app's. What is still ours is the TYPING, which is what is asserted.
  (let [ep {:method :post :path "/api/register"
            :request [:map
                      [:name :string]
                      [:pid {:optional true} [:maybe :int]]
                      [:ratio :double]
                      [:live :boolean]]}]

    (testing "an int field becomes a number, and a string field stays text"
      (is (= {:http/method :post :http/url "/api/register"
              :http/body {:name "slopp2" :pid 4131}}
             (schema/request-for ep {"name" "slopp2" "pid" "4131"}))))

    (testing "a double, and a boolean by the two words a form can produce"
      (is (= {:ratio 0.5 :live true}
             (:http/body (schema/request-for ep {"ratio" "0.5" "live" "true"}))))
      (is (= {:live false} (:http/body (schema/request-for ep {"live" "false"})))))

    (testing "`:maybe` does not change the coercion — the type inside it is the
              one the wire wants, and `[:maybe :int]` still means a number"
      (is (= {:pid 7} (:http/body (schema/request-for ep {"pid" "7"})))))

    (testing "a value that is NOT a number stays the string it was, rather than
              becoming nil. The endpoint refuses it and says why; a silent nil
              would be this form inventing an omission the reader did not make"
      (is (= {:pid "not-a-number"}
             (:http/body (schema/request-for ep {"pid" "not-a-number"})))))

    (testing "a blank is still omitted, as it is for a query — and that is the
              one rule the two halves share"
      ;; ABSENT rather than empty, which is the framework's call and a better
      ;; one: `endpoint/request` attaches `:http/body` only when there is
      ;; something to send, so a POST with every field blank carries no body at
      ;; all instead of an empty JSON object the endpoint has to interpret.
      (is (nil? (:http/body (schema/request-for ep {"name" "" "pid" nil})))))

    (testing "a GET is untouched: its request IS a query string, so everything
              stays text and the server coerces as it always has"
      (is (= "/api/search?limit=50"
             (:http/url (schema/request-for {:method :get :path "/api/search"
                                             :request [:map [:limit :int]]}
                                            {"limit" "50"})))))))

(deftest who-may-call-is-a-value-not-a-boolean
  ;; `:auth` landed 2026-08-11, the ask beside `:effectful?` that did not ship
  ;; with it. slopp publishes the `:http/auth` DECLARATION verbatim rather than
  ;; a boolean, and their reason is the one that makes it worth rendering:
  ;; `:authed? true` would have lost the half a reader needs, which is WHO.
  ;;
  ;; It is also the cheapest key in that document to trust — the auth write
  ;; gate refuses an endpoint declaring no `:http/auth`, so it can never be
  ;; nil-because-nobody-said, and "public" never has to be told from "unknown".
  ;; Absent means an older jar, and that is a different sentence.
  (testing "the one value every endpoint on slopp's own surface carries"
    (is (= "public" (schema/auth-label :public))))

  (testing "a value this screen has never seen prints as its own data rather
            than as a guess. The vocabulary is slopp's and it will grow; a
            label that invented a word for `[:group \"admin\"]` would be this
            screen claiming to know an access rule it has never met"
    (is (= "[:group \"admin\"]" (schema/auth-label [:group "admin"])))
    (is (= ":internal" (schema/auth-label :internal))))

  (testing "and ABSENT is nil, not `public`. A document from an older jar
            carries no `:auth` at all, and rendering that as public would be
            this screen asserting an access rule nobody published — the
            worst direction for a wrong answer about who may call"
    (is (nil? (schema/auth-label nil)))))

(deftest what-a-content-page-IS-is-a-phrase-not-a-body
  ;; The document describes the value rather than shipping it, deliberately —
  ;; a stylesheet is 14 kB and an index carrying bodies would put all of it on
  ;; the wire to render one table row. So the screen's job is to turn what the
  ;; document DOES say into a sentence.
  ;;
  ;; `:shape` is the same discriminator the framework serves by (`vector?`),
  ;; `:root-tag :html` means a WHOLE document because the renderer prepends the
  ;; doctype to one, and the size key differs by shape: `:nodes` for hiccup,
  ;; `:bytes` for text. Every one of them is `{:optional true}` except `:shape`.
  (testing "a whole document says so — that is the fact a reader most wants,
            and the one key that answers it"
    (is (= "hiccup · whole document · 19 nodes"
           (schema/content-label {:shape :hiccup :root-tag :html :nodes 19}))))

  (testing "and a FRAGMENT is named by its own tag rather than called a
            document, because the difference decides whether it can be served
            to a browser on its own"
    (is (= "hiccup · <div> fragment · 4 nodes"
           (schema/content-label {:shape :hiccup :root-tag :div :nodes 4}))))

  (testing "text reports bytes, in units a reader thinks in"
    (is (= "text · 14.3 kB" (schema/content-label {:shape :text :bytes 14263})))
    (is (= "text · 412 bytes" (schema/content-label {:shape :text :bytes 412}))))

  (testing "a missing size is DROPPED rather than rendered as zero — the keys
            are optional in the published schema, and `0 bytes` is a claim the
            document did not make"
    (is (= "text" (schema/content-label {:shape :text})))
    (is (= "hiccup · whole document"
           (schema/content-label {:shape :hiccup :root-tag :html}))))

  (testing "and a row with no shape at all yields nil rather than a phrase
            about nothing — the not-found and the undescribed must not look
            alike"
    (is (nil? (schema/content-label {})))
    (is (nil? (schema/content-label {:bytes 10})))))

(deftest a-REMAINDER-is-structure-and-a-segment-value-is-data
  ;; **The same bug slopp fixed twice in their own store, in the reader neither
  ;; of us checked.** They found `render-request` and `webapp/request-url` after
  ;; asking how many parsers of the path grammar exist, and counted three wrong
  ;; out of six across both stores. `url-parts` is a fourth: it interpolated a
  ;; wildcard correctly and encoded it wrongly, so it looked right from the one
  ;; angle either of us had checked.
  ;;
  ;; The distinction, in their words: a `/` in a SEGMENT value is data trying to
  ;; be structure, and a `/` in a REMAINDER *is* structure. `encode-component`
  ;; escapes `/`, so one whole-value encode turns `form/f1` into `form%2Ff1` and
  ;; the matcher — which decodes each sub-segment and then joins — never closes
  ;; the round trip.
  (testing "a named segment's value is DATA: a slash in it is escaped, because
            the endpoint declared one segment and got one"
    (is (= [[:lit "api"] [:lit "ns"] [:enc "a/b"]]
           (:path-parts (schema/url-parts {:path "/api/ns/:ns"
                                           :path-params {"ns" "a/b"}})))))

  (testing "a REMAINDER is structure: it becomes one part per sub-segment, so
            the slashes survive the encode as separators rather than as text"
    (is (= [[:lit "api"] [:lit "p"] [:enc "demo"] [:lit "api"]
            [:enc "form"] [:enc "f1"]]
           (:path-parts (schema/url-parts {:path "/api/p/:slug/api/**"
                                           :path-params {"slug" "demo" "*" "form/f1"}})))))

  (testing "and a remainder with no slash is still one part, so the simple case
            is unchanged"
    (is (= [[:lit "api"] [:lit "p"] [:enc "demo"] [:lit "api"] [:enc "modules"]]
           (:path-parts (schema/url-parts {:path "/api/p/:slug/api/**"
                                           :path-params {"slug" "demo" "*" "modules"}})))))

  (testing "an absent remainder contributes nothing rather than an empty
            segment — `**` matches ZERO or more, so the url has to be able to
            stop there"
    (is (= [[:lit "api"] [:lit "p"] [:enc "demo"] [:lit "api"]]
           (:path-parts (schema/url-parts {:path "/api/p/:slug/api/**"
                                           :path-params {"slug" "demo"}}))))))
