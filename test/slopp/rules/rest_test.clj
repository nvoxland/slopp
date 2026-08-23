(ns slopp.rules.rest-test
  "The typed surface as a project declares it, read off a store value.

  Two properties are the reason this report exists rather than pointing a
  reader at the schemas directly: it is inert until the capability is on, so a
  project is never DESCRIBED as publishing an API it does not; and it reports
  NAMES rather than schemas, because this is the one payload that grows with
  the application instead of with the question."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rules.rest :as rules.rest]
            [slopp.store :as store]))

(deftest the-rest-section-reports-the-TYPED-surface
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:http/method :post :rest/path \"/api/orders\" :http/auth :public\n"
                 "        :rest/request [:map [:sku :string] [:qty :int]]\n"
                 "        :rest/response [:map [:id :int]]}\n"
                 "  create! \"Place an order.\" [req] req)\n\n"
                 "(defn ^{:http/method :get :rest/path \"/api/health\" :http/auth :public\n"
                 "        :rest/response [:map [:ok :boolean]]}\n"
                 "  health \"Health.\" [req] req)\n\n"
                 "(defn ^{:http/method :get :http/path \"/\" :http/auth :public}\n"
                 "  page \"A document, not an API — no flag needed to say so.\" [req] req)\n\n"
                 "(defn ^{:http/method :get :rest/path \"/api/internal\" :http/auth :public\n"
                 "        :rest/response [:map [:n :int]] :rest/client false}\n"
                 "  internal \"A real api this store generates no wrapper for.\" [req] req)\n")
        off (store/ingest (store/empty-store) 'shop.api src)
        on  (assoc-in off [:config "capabilities" :values "rest.enabled"] "true")]

    (testing "empty until rest.enabled"
      ;; the reading side of the same inertness the gates have: a project that
      ;; publishes no typed API must not be DESCRIBED as having one
      (is (empty? (rules.rest/contracts-report off))))

    (let [rows (rules.rest/contracts-report on)
          by   (into {} (map (juxt :path identity)) rows)]
      (testing "every declared contract is a row, and says what kind it is"
        (is (= 3 (count rows)) (pr-str rows))
        (is (every? #(= :contract (:kind %)) rows)
            "so a renderer that knows nothing about this capability can still
             draw it beside a command or an endpoint"))

      (testing "the shape is NAMES, not schemas"
        ;; a malli schema pretty-prints to several lines, and this surface is
        ;; already the one whose payload grows with the APP rather than with
        ;; the question — query_rules is what that looks like when it goes
        ;; wrong (17 of 43 rules over the wire). The names answer what the
        ;; endpoint is FOR; query_slice on the handler answers it exactly.
        (is (= [:sku :qty] (:request (by "/api/orders"))))
        (is (= [:id] (:response (by "/api/orders"))))
        (is (= 'shop.api/create! (:handler (by "/api/orders")))))

      (testing "a GET declares no request, and that absence is reported as one"
        (is (nil? (:request (by "/api/health"))))
        (is (= [:ok] (:response (by "/api/health")))))

      (testing "an endpoint OUT of the published API says so"
        ;; :rest/client false is how a document opts out of the typed surface —
        ;; a generated fetch wrapper over an HTML page is nonsense — and a
        ;; reader asking what consumers can call needs that visible rather
        ;; than inferred from a missing schema
        (is (false? (:published (by "/api/internal"))))
        (is (true? (:published (by "/api/orders"))))))))

(deftest a-contract-that-is-not-a-MAP-does-not-break-the-whole-report
  ;; Reported from a real store the day `rest` was enabled there: `query_surface`
  ;; threw `Don't know how to create ISeq from: malli.core$_map_schema$reify`,
  ;; with `rest.enabled` false returning the full report and true throwing —
  ;; and no compiled schema anywhere in that store's declarations.
  ;;
  ;; The cause: `(mapv first (m/children …))` reads a `:map`'s `[k props v]`
  ;; entries, and every other schema's children are compiled SCHEMAS. So the
  ;; one endpoint returning `[:or [:map …] [:map …]]` took the entire report
  ;; down — nine endpoints unreadable because of a tenth.
  ;;
  ;; **A report over a population must not be hostage to one member.** This
  ;; surface is the human-facing view of an API, so the shape most likely to
  ;; appear is the shape someone reached for when a plain map would not do.
  (let [src (str "(ns odd.api)\n\n"
                 "(defn ^{:http/method :get :rest/path \"/api/projects\" :http/auth :public\n"
                 "        :rest/response [:sequential [:map [:id :int] [:slug :string]]]}\n"
                 "  projects \"A list.\" [req] req)\n\n"
                 "(defn ^{:http/method :post :rest/path \"/api/register\" :http/auth :public\n"
                 "        :rest/request [:map [:slug :string]]\n"
                 "        :rest/response [:or [:map [:ok :boolean]] [:map [:error :string]]]}\n"
                 "  register! \"Two answers.\" [req] req)\n\n"
                 "(defn ^{:http/method :get :rest/path \"/api/token\" :http/auth :public\n"
                 "        :rest/response :string}\n"
                 "  token \"Just a string.\" [req] req)\n")
        st  (-> (store/ingest (store/empty-store) 'odd.api src)
                (assoc-in [:config "capabilities" :values "rest.enabled"] "true"))
        by  (into {} (map (juxt :path identity)) (rules.rest/contracts-report st))]

    (testing "every endpoint is still a row"
      (is (= 3 (count by)) (pr-str by)))

    (testing "a map under a collection reports the INNER keys, which is the useful half"
      ;; a list endpoint is the commonest non-map contract there is, and
      ;; answering `:sequential` alone would throw away everything a reader
      ;; came for
      (is (= [:id :slug] (:response (by "/api/projects"))) (pr-str (by "/api/projects"))))

    (testing "a schema with nothing to enumerate answers with its own TYPE"
      ;; not [] — an empty vector reads as "a map with no keys", which is a
      ;; claim about the contract rather than about this report's reach
      (is (= :or (:response (by "/api/register"))) (pr-str (by "/api/register")))
      (is (= :string (:response (by "/api/token"))) (pr-str (by "/api/token"))))

    (testing "and the ordinary map case is untouched"
      (is (= [:slug] (:request (by "/api/register")))))))

(deftest a-contract-declared-by-VAR-reports-its-keys
  ;; Reported by slopp-ui the day the report shipped: every INLINE contract
  ;; reported exactly what it promised, and every VAR-referenced one reported
  ;; `nil`. Five endpoints, and the split was clean along that one line.
  ;;
  ;; **This is worse than the throw it replaced**, by the principle stated in
  ;; the same message that shipped it: answering `[]` would be "a claim about
  ;; your contract rather than about the report's reach", and `nil` is the
  ;; stronger version of that mistake — an eight-key response is
  ;; indistinguishable from an endpoint that declares nothing.
  ;;
  ;; It also punishes the practice slopp itself pushes. `rest-inline-schema-dup`
  ;; fires on the same inline schema in two endpoints and tells the author to
  ;; share a var — and sharing the var is what made the surface go blank.
  ;;
  ;; **The rules were never blind**; `schema-resolver` resolves through the
  ;; reference graph, by edge rather than by name, because slopp's own ten
  ;; endpoints all name their schema through an alias. So the two answers to
  ;; one question came from two producers, and only the report had no resolver.
  (let [contracts (str "(ns shop.contracts)\n\n"
                       "(def order [:map [:sku :string] [:qty :int]])\n\n"
                       "(def receipt [:sequential [:map [:id :int] [:total :int]]])\n")
        api       (str "(ns shop.api\n"
                       "  (:require [shop.contracts :as contracts]))\n\n"
                       "(defn ^{:http/method :post :rest/path \"/api/orders\" :http/auth :public\n"
                       "        :rest/request contracts/order\n"
                       "        :rest/response contracts/receipt}\n"
                       "  create! \"Place an order.\" [req] req)\n\n"
                       "(defn ^{:http/method :get :rest/path \"/api/health\" :http/auth :public\n"
                       "        :rest/response [:map [:ok :boolean]]}\n"
                       "  health \"Health.\" [req] req)\n")
        st        (-> (store/empty-store)
                      (store/ingest 'shop.contracts contracts)
                      (store/ingest 'shop.api api)
                      (assoc-in [:config "capabilities" :values "rest.enabled"] "true"))
        by        (into {} (map (juxt :path identity)) (rules.rest/contracts-report st))]

    (testing "a var-referenced map reports the var's keys"
      (is (= [:sku :qty] (:request (by "/api/orders"))) (pr-str (by "/api/orders"))))

    (testing "and a var-referenced [:sequential [:map …]] reports the INNER keys"
      ;; the case singled out as "the commonest non-map contract there is",
      ;; and the one that reported nil on a real store
      (is (= [:id :total] (:response (by "/api/orders"))) (pr-str (by "/api/orders"))))

    (testing "the inline case is untouched — the control"
      ;; without this the fix could resolve vars by breaking everything else
      (is (= [:ok] (:response (by "/api/health"))) (pr-str (by "/api/health"))))))

(deftest a-contract-COMPOSED-from-other-vars-resolves-all-the-way-down
  ;; The other half of slopp-ui's finding, and the half slopp's own store
  ;; proves. Resolving ONE level fixed five of ten endpoints here and left five
  ;; reporting nil, because a schema var may name another:
  ;;
  ;;   (def namespace-list [:sequential namespace-row])
  ;;
  ;; `namespace-row` is still a symbol after one hop, so malli throws building
  ;; the schema and the total accessor answers nil. Composing schemas this way
  ;; is not an edge case — `api.contracts/namespace-list` documents it as
  ;; deliberate: *"a schema var is an ordinary var, so this composition is a
  ;; REAL reference edge, and changing the row shows up in its blast radius."*
  ;;
  ;; So the reader has to resolve to a FIXED POINT, not to one hop, and each
  ;; hop has to resolve from where it is WRITTEN — `namespace-row` is named
  ;; from inside `api.contracts`, not from the endpoint that started the walk.
  (let [contracts (str "(ns shop.contracts)\n\n"
                       "(def line [:map [:sku :string] [:qty :int]])\n\n"
                       "(def order [:map [:id :int] [:lines [:sequential line]]])\n\n"
                       "(def receipts [:sequential order])\n")
        api       (str "(ns shop.api\n"
                       "  (:require [shop.contracts :as contracts]))\n\n"
                       "(defn ^{:http/method :get :rest/path \"/api/receipts\" :http/auth :public\n"
                       "        :rest/response contracts/receipts}\n"
                       "  all \"Every receipt.\" [req] req)\n")
        st        (-> (store/empty-store)
                      (store/ingest 'shop.contracts contracts)
                      (store/ingest 'shop.api api)
                      (assoc-in [:config "capabilities" :values "rest.enabled"] "true"))
        row       (first (rules.rest/contracts-report st))]

    (testing "two hops through vars, then a collection wrapper, still reports keys"
      ;; receipts -> order (var) -> [:map …]; the inner `line` reference must
      ;; also resolve or malli cannot build the map at all
      (is (= [:id :lines] (:response row)) (pr-str row)))))
