(ns slopp.http.contract-test
  "Tests for contract publishing, with endpoint fixtures of its own.

  The fixtures live here rather than in `slopp.http-test` because that
  namespace's facade test asserts its own exact route COUNT — so an endpoint
  added there reds an unrelated passing test, which is how this namespace came
  to exist. A test namespace whose subject is `from-namespaces` traversal needs
  to own its route set."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.http.contract :as http.contract]))

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
  (let [doc     (http.contract/contract-document ['slopp.http.contract-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:endpoints doc))]

    (testing "the document names its own version, so a consumer can refuse one it doesn't know"
      (is (= 2 (:slopp/contract-version doc))))

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
      (is (not (contains? (set (map :path (:endpoints doc))) "/c/page"))))

    (testing "and a REAL api is published whatever any consumer wants from it"
      ;; `/c/no-client` carried `:rest/client false` for one afternoon, added
      ;; here to pin what the flag was "left doing" after content became its
      ;; own kind. It turned out to be doing nothing legitimate: whether to
      ;; generate a wrapper is the generating CONSUMER's question, asked
      ;; against this document, and excluding the endpoint from the document
      ;; took the question away from them entirely.
      (is (contains? (set (map :path (:endpoints doc))) "/c/no-client")
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
  (let [doc     (http.contract/contract-document ['slopp.http.contract-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:endpoints doc))
        things  (by-addr [:get "/c/things"])]

    (testing "the handler's QUALIFIED symbol — a name a consumer can resolve"
      (is (= 'slopp.http.contract-test/c-list (:handler things)))
      (is (= 'slopp.http.contract-test/c-create! (:handler (by-addr [:post "/c/things"])))))

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
  (let [doc     (http.contract/contract-document ['slopp.http.contract-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:endpoints doc))]
    (testing ":public travels as itself"
      (is (= :public (:auth (by-addr [:get "/c/things"])))))
    (testing "and so does a GROUP — the value, not merely the fact of a value"
      ;; the half a boolean `:authed?` would lose, and the half slopp-ui's UI
      ;; needs: it renders who may call, not whether anyone may
      (is (= [:group "admin"] (:auth (by-addr [:get "/c/admin"])))))
    (testing "every endpoint carries it, because the gate refuses one without"
      (is (every? #(contains? % :auth) (:endpoints doc))
          (pr-str (remove #(contains? % :auth) (:endpoints doc)))))))

(deftest version-2-publishes-EFFECTFULNESS-and-moves-because-keys-became-required
  ;; Two changes, one bump, because every bump costs a consumer a migration and
  ;; there is no reason to charge them twice.
  ;;
  ;; 1. `:handler`, `:doc`, `:media-type` and `:auth` all arrived while the
  ;;    version stayed 1, so a version-1 document was never ONE shape. They are
  ;;    required under 2, and the version is what says so.
  ;; 2. `:effectful?` — the fact a consumer wants BEFORE calling anything, and
  ;;    the one their arming gate was reading as nil.
  (let [doc     (http.contract/contract-document ['slopp.http.contract-test])
        by-addr (into {} (map (juxt (juxt :method :path) identity)) (:endpoints doc))]

    (testing "the version MOVED, because required keys did"
      (is (= 2 (:slopp/contract-version doc))))

    (testing "every endpoint carries the four keys that used to be conditional"
      (doseq [k [:handler :doc :media-type :auth]]
        (is (every? #(contains? % k) (:endpoints doc))
            (str k " is required under 2 — that is what the bump BUYS: "
                 (pr-str (remove #(contains? % k) (:endpoints doc)))))))

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
        (is (true? (:effectful? (by-addr [:post "/c/things"])))))

      (testing "every endpoint carries it, so nobody has to tell false from absent"
        (is (every? #(contains? % :effectful?) (:endpoints doc))
            (pr-str (remove #(contains? % :effectful?) (:endpoints doc))))))))
