(ns slopp.rest.paths-test
  "Tests for contract publishing, with endpoint fixtures of its own.

  The fixtures live here rather than in `slopp.http-test` because that
  namespace's facade test asserts its own exact route COUNT — so an endpoint
  added there reds an unrelated passing test, which is how this namespace came
  to exist. A test namespace whose subject is `from-namespaces` traversal needs
  to own its route set."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rest.paths :as rest.paths]))

(defn ^{:http/method :get :rest/path "/c/things" :http/auth :public
        :rest/response [:map [:things [:sequential :string]]]}
  c-list
  "Fixture: a typed GET — the ordinary case a published contract describes.

  Deliberately MULTI-LINE with a paragraph break, because a handler docstring
  is multi-line by construction and the continuation lines carry the source's
  own indentation. A document that ships that indentation ships a fact about
  our formatting to every consumer."
  [_req]
  {:status 200 :body {:things []}})

(defn ^{:http/method :post :rest/path "/c/things" :http/auth :public
        :http/effectful true
        :rest/request [:map [:name :string]]
        :rest/response [:map [:id :int]]}
  c-create!
  "Fixture: a body verb — the only shape that carries a :rest/request."
  [req]
  {:status 201 :body {:id (count (str (:name (:body req))))}})

(defn ^{:http/method :post :rest/path "/c/quiet" :http/auth :public
        :rest/request [:map [:q :string]]
        :rest/response [:map [:hits :int]]}
  c-quiet
  "Fixture: a body verb that declares NO `:http/effectful`.

  It exists to separate the two arms of the published `:effectful?`. This one
  is answered by the METHOD alone, which is what makes the field useful in a
  store where nobody writes the marker — slopp's own ten endpoints declare it
  zero times, so publishing the marker verbatim would have shipped `false`
  everywhere and read like an answer."
  [req]
  {:status 200 :body {:hits (count (str (:q (:body req))))}})

(defn ^{:http/method :get :http/path "/c/page" :http/auth :public}
  c-page
  "Fixture: an HTML page — CONTENT, not an api.

  It used to carry `:rest/response :string` and `:rest/client false`, and both
  existed only because there was one path marker: the contract gate asked every
  route for a schema, a page answered `:string` — a lie about an HTML body —
  and then opted out of the wrapper that followed. `:http/path` says content,
  so it is asked for neither."
  [_req]
  {:status 200 :body "<h1>c</h1>"})

(defn ^{:http/method :get :rest/path "/c/bare" :http/auth :public
        :rest/response [:map [:ok :boolean]]}
  c-bare
  [_req]
  {:status 200 :body {:ok true}})

(defn ^{:http/method :get :rest/path "/c/admin" :http/auth [:group "admin"]
        :rest/response [:map [:secret :string]]}
  c-admin
  "Fixture: an endpoint only a group may call — the case `:public` cannot show."
  [_req]
  {:status 200 :body {:secret "s"}})

(defn ^{:http/method :get :rest/path "/c/no-client" :http/auth :public
        :rest/response [:map [:ok :boolean]]}
  c-no-client
  "Fixture: a REAL typed api that one consumer generates no wrapper for.

  It carried `:rest/client false` for one afternoon — added to pin what the
  flag was \"left doing\" once content became its own kind. It turned out to be
  doing nothing legitimate. The three real instances resolved as: a base
  problem in one client namespace serving two APIs; \"our browser does not call
  it\", which is not the producer's business; and an endpoint answering EDN,
  which is `:rest/media-type`.

  Kept, without the flag, because the property worth pinning is the opposite
  one: **this is published whatever any consumer wants from it.** An endpoint
  does not know who will call it, and a document that omits one takes the
  decision away from the consumer whose decision it is."
  [_req]
  {:status 200 :body {:ok true}})

(deftest a-contract-publishes-the-typed-surface-and-nothing-else
  (let [doc     (rest.paths/paths-document ['slopp.rest.paths-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:paths doc))]

    (testing "the document names its own version, so a consumer can refuse one it doesn't know"
      ;; a NEW key starting at 1, not :slopp/contract-version 2 carried over.
      ;; This is a different document at a different address; continuing the
      ;; old counter would claim a lineage it does not have, and the old
      ;; document is still served this jar for a consumer to migrate off.
      (is (= #{:paths} (set (keys doc))) (pr-str (keys doc)))
      (is (not (contains? doc :slopp/contract-version)) (pr-str (keys doc))))

    (testing "an endpoint is addressed by method AND path — one path serves two verbs"
      (is (= #{[:get "/c/admin"] [:get "/c/no-client"] [:get "/c/things"]
        [:post "/c/things"] [:post "/c/quiet"] [:get "/c/bare"]}
             (set (keys by-addr)))))

    (testing "schemas travel as VALUES, equal to what the var declared"
      ;; var metadata is evaluated, so the schema is already data by the time
      ;; it is published — no store, no source text, no importer.
      (is (= [:map [:things [:sequential :string]]]
             (:response (by-addr [:get "/c/things"]))))
      (is (= [:map [:name :string]]
             (:request (by-addr [:post "/c/things"])))))

    (testing "a verb with no body says so with nil rather than by omitting the key"
      ;; a consumer must be able to tell 'no request body' from 'unknown'.
      (is (contains? (by-addr [:get "/c/things"]) :request))
      (is (nil? (:request (by-addr [:get "/c/things"])))))

    (testing "the endpoint carries its NAME — the consumer names its wrapper from it"
      (is (= 'c-list (:name (by-addr [:get "/c/things"])))))

    (testing "CONTENT is absent because it is content, not because of a flag"
      ;; `/c/page` used to be excluded by `:rest/client false`. It is
      ;; `:http/path` now, so it is not a contract's business at all — and this
      ;; assertion would pass either way, which is why the flag gets its own
      ;; fixture below rather than sharing this one
      (is (not (contains? (set (map :path (:paths doc))) "/c/page"))))

    (testing "and a REAL api is published whatever any consumer wants from it"
      ;; `/c/no-client` carried `:rest/client false` for one afternoon, added
      ;; here to pin what the flag was "left doing" after content became its
      ;; own kind. It turned out to be doing nothing legitimate: whether to
      ;; generate a wrapper is the generating CONSUMER's question, asked
      ;; against this document, and excluding the endpoint from the document
      ;; took the question away from them entirely.
      (is (contains? (set (map :path (:paths doc))) "/c/no-client")
          "an endpoint does not know who will call it"))))

(deftest an-endpoint-says-what-it-IS-and-WHERE-it-lives
  ;; Two keys, both asked for by slopp-ui after measuring what the document
  ;; could not answer, and both derived from var METADATA — which is the whole
  ;; constraint this namespace exists under. Neither needs a store, so a jar and
  ;; a native binary publish them identically.
  ;;
  ;; :handler, because :name alone does not resolve. Measured against slopp's
  ;; own nine endpoints: `ns-outline`, `search` and `timeline` each match more
  ;; than one form by simple name — a third of the surface — so a consumer
  ;; linking to "the form called :name" points at the wrong one and looks right
  ;; doing it. (The same measurement, from the other direction, is why
  ;; rules.web's schema resolver goes through the reference graph.)
  ;;
  ;; :doc, because the prose already exists. Every handler here opens with
  ;; `GET <path> — what it is`; it was written for the API's reader and it
  ;; stopped at the process boundary, because a docstring is not a value.
  (let [doc     (rest.paths/paths-document ['slopp.rest.paths-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:paths doc))
        things  (by-addr [:get "/c/things"])]

    (testing "the handler's QUALIFIED symbol — a name a consumer can resolve"
      (is (= 'slopp.rest.paths-test/c-list (:handler things)))
      (is (= 'slopp.rest.paths-test/c-create! (:handler (by-addr [:post "/c/things"])))))

    (testing ":name stays exactly as it was — the client generator names its
              wrapper from it, and this is additive"
      (is (= 'c-list (:name things))))

    (testing "the docstring travels WHOLE. slopp-ui takes the first sentence for
              its index and renders the rest on the endpoint's page: a document
              that ships only a first line cannot be un-truncated by a consumer
              that wants the rest, and the reverse is free"
      (is (re-find #"^Fixture: a typed GET" (:doc things)))
      (is (re-find #"ships a fact about\nour formatting" (:doc things))
          "the tail is present, and the paragraph break with it"))

    (testing "and it arrives DE-INDENTED. A docstring's continuation lines carry
              the source's own indentation, which is an artifact of where the
              form sits in a file — publishing it ships our formatting to every
              consumer, the same trap a schema :doc falls into"
      (is (not (re-find #"\n  \S" (:doc things)))
          (str "no continuation line should start with the source indent: "
               (pr-str (:doc things)))))

    (testing "an endpoint with no docstring says so with nil rather than by
              omitting the key — the same discipline :request already follows"
      (let [bare (by-addr [:get "/c/bare"])]
        (is (contains? bare :doc))
        (is (nil? (:doc bare)))))))

(deftest an-endpoint-says-who-may-CALL-it
  ;; slopp-ui's ask, and their argument for it: `:auth` and `:effectful?` are
  ;; the two facts a reader wants BEFORE calling anything. One of them arrived
  ;; and this one did not.
  ;;
  ;; It is cheaper than either, because `:http/auth` is already REQUIRED — the
  ;; `http-auth-refusal` gate refuses an endpoint that declares none. So unlike
  ;; `:request`, this key can never be nil-because-unknown, and a consumer
  ;; never has to tell "public" from "nobody said".
  (let [doc     (rest.paths/paths-document ['slopp.rest.paths-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:paths doc))]
    (testing ":public travels as itself"
      (is (= :public (:auth (by-addr [:get "/c/things"])))))
    (testing "and so does a GROUP — the value, not merely the fact of a value"
      ;; the half a boolean `:authed?` would lose, and the half slopp-ui's UI
      ;; needs: it renders who may call, not whether anyone may
      (is (= [:group "admin"] (:auth (by-addr [:get "/c/admin"])))))
    (testing "every endpoint carries it, because the gate refuses one without"
      (is (every? #(contains? % :auth) (:paths doc))
          (pr-str (remove #(contains? % :auth) (:paths doc)))))))

(deftest every-endpoint-carries-the-keys-a-CONSUMER-cannot-do-without
  ;; This used to be a statement about a VERSION: the field existed so a
  ;; consumer could refuse a shape it did not know, and the rule was that
  ;; adding a required key moves it. That was learned expensively on this
  ;; document's predecessor — `:handler`, `:doc`, `:media-type` and `:auth`
  ;; all arrived while `:slopp/contract-version` stayed 1, so a version-1
  ;; document was never ONE shape and a consumer holding a schema could not
  ;; tell which it would get.
  ;;
  ;; **There is no version key now**, because nothing ever branched on one and
  ;; API compatibility is a coordination between an API and its client rather
  ;; than a mechanism a framework invents. What replaces it is narrower and
  ;; enforceable here: a document changes by RENAMING a key, never by
  ;; redefining one in place — so the keys below are asserted directly, and a
  ;; consumer that finds them missing has met a document it does not know.
  (let [doc     (rest.paths/paths-document ['slopp.rest.paths-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:paths doc))]

    (testing "the envelope is the rows and nothing else"
      (is (= #{:paths} (set (keys doc))) (pr-str (keys doc))))

    (testing "every endpoint carries the keys a consumer cannot generate without"
      (doseq [k [:handler :doc :media-type :auth :effectful?]]
        (is (every? #(contains? % k) (:paths doc))
            (str k " is missing from an endpoint, so a consumer holding this"
                 " document cannot call it: "
                 (pr-str (remove #(contains? % k) (:paths doc)))))))

    (testing ":effectful? is DERIVED, not the marker verbatim"
      ;; slopp's own ten endpoints declare `:http/effectful` zero times, so
      ;; publishing the marker as it stands would ship `false` everywhere — and
      ;; a consumer's arming gate would go from inert-on-nil to inert-on-FALSE,
      ;; which is worse because false looks like an answer.
      (testing "a safe verb with no marker is false"
        (is (false? (:effectful? (by-addr [:get "/c/things"])))))

      (testing "a mutating verb is true on the METHOD alone"
        ;; c-quiet declares no marker. This arm is what makes the field useful
        ;; in a store where nobody writes one
        (is (true? (:effectful? (by-addr [:post "/c/quiet"])))))

      (testing "and the MARKER still decides where the method cannot"
        ;; c-create! declares it AND is a body verb, so it proves the marker is
        ;; read rather than that POST is true — which the row above already
        ;; established. Both arms are separable, deliberately.
        (is (true? (:effectful? (by-addr [:post "/c/things"]))))))))
