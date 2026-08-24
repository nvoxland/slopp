(ns slopp.rest.contract-test
  "The boundary's decisions, exercised with no server and no socket.

  These tests are the demonstration of why the split exists: a wrong flag, a
  missing key, a keyword the wire turns into a string and a set it turns into
  an array are all `=` on a map here. The measurements pinned in
  `a-response-is-judged-on-what-the-CLIENT-receives` are the justification for
  the round-trip and belong to that test rather than to a design note, because
  they are the thing that would tempt the next reader to make it cheaper."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rest.contract :as rest.contract]))

(deftest a-request-is-DECODED-before-it-is-judged
  ;; The BODY carrier specifically. Decoding is what makes a typed contract
  ;; honourable at all: JSON has no keywords, no dates, no UUIDs and no sets, so
  ;; a contract naming one describes a value the wire cannot carry.
  ;;
  ;; What JSON CAN carry it is not asked to fix, and that boundary is the whole
  ;; security posture. A number is a number in JSON, so a client sending "7"
  ;; where the contract says :int has violated the contract, and coercing it
  ;; would make the published document lie about what is accepted. Leniency here
  ;; is how "it worked when I tried it" and "it accepts anything" end up being
  ;; the same system.
  ;;
  ;; The opposite is true of a param, which has no way to carry a number at all
  ;; — see `a-contract-covers-everything-the-caller-SENDS`. Same schema, two
  ;; carriers, two answers, and neither is a preference.
  (testing "what JSON cannot express is decoded"
    ;; KEYWORD keys, because that is what actually arrives — both adapters
    ;; parse with `(json/parse-string body true)`. A fixture with string keys
    ;; would test a shape no adapter produces.
    (let [r (rest.contract/decode-request [:map [:id :int] [:tag :keyword]]
                                          {:body {:id 7 :tag "urgent"}})]
      (is (nil? (:error r)) (pr-str r))
      (is (= {:id 7 :tag :urgent} (:body (:value r)))
          "the handler receives typed data having written no parsing")))

  (testing "and what JSON CAN express is judged, not repaired"
    (let [r (rest.contract/decode-request [:map [:id :int]] {:body {:id "7"}})]
      (is (nil? (:value r)) (pr-str r))
      (is (string? (:error r))
          "a string where the contract says :int is a client error — accepting
           it would publish a contract the server does not actually require")))

  (testing "a refusal names the FIELD, or the caller cannot act on it"
    (let [r (rest.contract/decode-request [:map [:id :int]] {:body {:id "banana"}})]
      (is (re-find #"id" (:error r)) (:error r))))

  (testing "a missing required key is refused"
    (is (:error (rest.contract/decode-request [:map [:id :int]] {:body {}}))))

  (testing "no schema declared means nothing to judge"
    ;; a :get with no params declares no :rest/request; that is an absence, not a
    ;; violation, and decoding against a schema nobody wrote would be inventing
    ;; one. Same distinction :cli/args [:catn] draws on the other side —
    ;; "takes nothing" and "never said" are different statements.
    (let [r (rest.contract/decode-request nil {:body {:anything 1}})]
      (is (nil? (:error r)))
      (is (= {:anything 1} (:body (:value r)))
          "an undeclared contract passes the value through UNTOUCHED"))))

(deftest a-response-is-judged-on-what-the-CLIENT-receives
  ;; The contract is a promise to a consumer who never sees Clojure data. So
  ;; the only question worth asking is whether the value that ARRIVES matches
  ;; it, and checking the in-image value answers a different question — wrongly,
  ;; in both directions. Measured, and this is why the check round-trips:
  ;;
  ;;   {:x :foo}     vs [:map [:x :string]]   pre-wire INVALID, arrives {:x "foo"} VALID
  ;;   {:tags #{"a"}} vs [:map [:tags [:set :string]]]
  ;;                                          pre-wire VALID, arrives ["a"] INVALID
  ;;
  ;; `m/encode` through the json-transformer fixes NEITHER — it leaves the
  ;; keyword a keyword and the set a set. That was the cheap idea and it does
  ;; not work, so the check pays for a real serialize/parse.

  (testing "a keyword the wire turns into a string HONOURS a :string contract"
    ;; the too-strict direction: an in-image check would 500 a response the
    ;; client receives exactly as promised
    (is (nil? (rest.contract/check-response [:map [:x :string]] {:x :foo}))))

  (testing "a set the wire turns into an array VIOLATES a :set contract"
    ;; the too-lenient direction, and the one the crossings registry has named
    ;; as blind since the day it was written: the value is a perfectly good set
    ;; here and an array over there
    (let [e (rest.contract/check-response [:map [:tags [:set :string]]] {:tags #{"a"}})]
      (is (string? e)
          "the client receives [\"a\"], which is not a set — a check that passes
           this is checking a shape no client receives")
      (is (re-find #"tags" e) e)))

  (testing "a response that matches is silent"
    (is (nil? (rest.contract/check-response [:map [:n :int]] {:n 1}))))

  (testing "a response that misses a promised key is named"
    (let [e (rest.contract/check-response [:map [:n :int]] {})]
      (is (string? e))
      (is (re-find #"n" e) e)))

  (testing "no schema declared means nothing to judge"
    (is (nil? (rest.contract/check-response nil {:whatever 1}))))

  (testing "a value that cannot be serialized at all is reported, not thrown"
    ;; the caller is a dispatcher holding a response it is about to send; an
    ;; exception here would turn a contract problem into a mystery 500 with no
    ;; mention of the contract
    (is (string? (rest.contract/check-response [:map [:f :any]] {:f (fn [])})))))

(deftest a-contract-covers-everything-the-caller-SENDS
  ;; `:rest/request` means what the caller sends — the codebase said so before
  ;; the boundary existed, in `api.contracts/form-request`: "a GET sends a query
  ;; string for the same reason a POST sends a body", and the generated client
  ;; reads the METHOD to decide where each key travels. One schema, three
  ;; possible carriers.
  ;;
  ;; The first cut of this boundary judged the BODY alone and scoped itself to
  ;; :post/:put/:patch, because judging a params schema against a nil body 400s
  ;; every correct GET. That was the right stopgap and the wrong contract: it
  ;; left path segments and query strings — untrusted input, in every link and
  ;; every crawler's history — checked by nobody.
  ;;
  ;; Each carrier is decoded by what its WIRE can express, which is why this
  ;; cannot be one transformer. Params are always text; a JSON body carries real
  ;; numbers and booleans.
  (testing "params are text, so they decode by string"
    (let [r (rest.contract/decode-request
             [:map [:id :int] [:view {:optional true} :keyword]]
             {:path-params {:id "7"} :query-params {:view "full"}})]
      (is (nil? (:error r)) (pr-str r))
      (is (= {:id 7} (:path-params (:value r)))
          "a path segment declared :int arrives typed, and the handler parses nothing")
      (is (= {:view :full} (:query-params (:value r))))))

  (testing "a body is JSON, so a string where the contract says :int is still refused"
    ;; the asymmetry is the point: JSON CAN carry a number, so accepting "7"
    ;; there would publish a contract the server does not require. A query
    ;; string cannot carry one, so accepting "7" there is the only way the
    ;; contract can be honoured at all.
    (is (:error (rest.contract/decode-request
                 [:map [:qty :int]] {:body {:qty "7"}})))
    (is (nil? (:error (rest.contract/decode-request
                       [:map [:qty :int]] {:body {:qty 7}})))))

  (testing "the schema is judged against the MERGE of what arrived"
    ;; a POST addressed by a path segment sends both, and one schema describes
    ;; the pair — which is what the generated client's argument map already is
    (let [r (rest.contract/decode-request
             [:map [:id :int] [:sku :string]]
             {:path-params {:id "7"} :body {:sku "abc"}})]
      (is (nil? (:error r)) (pr-str r))
      (is (= {:id 7} (:path-params (:value r))))
      (is (= {:sku "abc"} (:body (:value r))))))

  (testing "a key the contract requires and nobody sent is refused"
    (let [r (rest.contract/decode-request
             [:map [:id :int] [:sku :string]] {:path-params {:id "7"}})]
      (is (string? (:error r)))
      (is (re-find #"sku" (:error r)) (:error r))))

  (testing "an undeclared param is REFUSED — reversed 2026-08-24, reason expired"
    ;; This asserted the opposite, and its reason was: "the contract says what
    ;; is REQUIRED, not what is forbidden, and a link carrying a tracking
    ;; parameter must not 400."
    ;;
    ;; That reason was about PAGES, and it stopped applying when `:rest/path`
    ;; and `:http/path` split (D-rest-path). A page is general HTTP content
    ;; now: it declares no contract, so `decode-request` sees a nil schema and
    ;; passes every carrier through. `?utm=x` on a link is untouched by this
    ;; function. What remains here is a typed API, where an undeclared key is
    ;; not a tracking parameter — it is a caller sending something the contract
    ;; does not describe.
    ;;
    ;; The measured case: a generated builder forwarded its consumer's own
    ;; route param (`?slug=demo`) to a service that never asked for it. Correct
    ;; response, correct render, visible only in somebody else's access log.
    (let [r (rest.contract/decode-request
             [:map [:id :int]]
             {:path-params {:id "7"} :query-params {:utm "x"}})]
      (is (:error r) (pr-str r))
      (is (re-find #"utm" (:error r))
          (str "and it names the key, which an open map could never do because"
               " it never failed: " (pr-str r)))))

  (testing "no schema passes every carrier through untouched"
    (let [r (rest.contract/decode-request nil {:path-params {:a "1"} :body {:b 2}})]
      (is (nil? (:error r)))
      (is (= {:a "1"} (:path-params (:value r))))
      (is (= {:b 2} (:body (:value r)))))))

(deftest a-contract-is-CLOSED-so-an-undeclared-key-is-refused
  (let [schema [:map [:id :string] [:depth {:optional true} :int]]]
    (testing "a key the contract does not name is refused, and NAMED"
      (let [r (rest.contract/decode-request schema
                                            {:path-params {:id "f1"}
                                             :query-params {:depth "2" :slug "demo"}})]
        (is (:error r) (pr-str r))
        (is (re-find #"slug" (:error r))
            (str "the caller has to be told WHICH key, or the refusal is a"
                 " puzzle: " (pr-str r)))))
    (testing "and what it does name still passes, decoded"
      (let [r (rest.contract/decode-request schema
                                            {:path-params {:id "f1"}
                                             :query-params {:depth "2"}})]
        (is (nil? (:error r)) (pr-str r))
        (is (= 2 (:depth (:query-params (:value r)))) (pr-str r))))
    (testing "an OPTIONAL declared key may be absent without being undeclared"
      (is (nil? (:error (rest.contract/decode-request
                         schema {:path-params {:id "f1"} :query-params {}})))))
    (testing "a nil schema still passes everything through"
      (is (nil? (:error (rest.contract/decode-request
                         nil {:query-params {:anything "goes"}}))))))
  (testing "closing is TOP-LEVEL: a nested map the contract declares stays as declared"
    ;; a request carrying a free-form blob is a real shape, and closing every
    ;; map inside the schema would refuse the blob's own keys — a strictness
    ;; nobody asked for, applied where the author already said what they meant
    (let [schema [:map [:id :string] [:meta [:map [:kind :string]]]]]
      (is (nil? (:error (rest.contract/decode-request
                         schema {:body {:id "f1" :meta {:kind "x" :extra 1}}})))
          "the top level is closed; what the author declared inside it is not"))))
