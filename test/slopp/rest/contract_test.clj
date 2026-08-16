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
  ;; Decoding is what makes a typed contract honourable at all: JSON has no
  ;; keywords, no dates, no UUIDs and no sets, so a contract naming one
  ;; describes a value the wire cannot carry.
  ;;
  ;; What JSON CAN carry it is not asked to fix, and that boundary is the whole
  ;; security posture. A number is a number in JSON, so a client sending "7"
  ;; where the contract says :int has violated the contract, and coercing it
  ;; would make the published document lie about what is accepted. Leniency
  ;; here is how "it worked when I tried it" and "it accepts anything" end up
  ;; being the same system.
  (testing "what JSON cannot express is decoded"
    ;; KEYWORD keys, because that is what actually arrives — both adapters
    ;; parse with `(json/parse-string body true)`. A fixture with string keys
    ;; would test a shape no adapter produces.
    (let [r (rest.contract/decode-request [:map [:id :int] [:tag :keyword]]
                                     {:id 7 :tag "urgent"})]
      (is (nil? (:error r)) (pr-str r))
      (is (= {:id 7 :tag :urgent} (:value r))
          "the handler receives typed data having written no parsing")))

  (testing "and what JSON CAN express is judged, not repaired"
    (let [r (rest.contract/decode-request [:map [:id :int]] {:id "7"})]
      (is (nil? (:value r)) (pr-str r))
      (is (string? (:error r))
          "a string where the contract says :int is a client error — accepting
           it would publish a contract the server does not actually require")))

  (testing "a refusal names the FIELD, or the caller cannot act on it"
    (let [r (rest.contract/decode-request [:map [:id :int]] {:id "banana"})]
      (is (re-find #"id" (:error r)) (:error r))))

  (testing "a missing required key is refused"
    (is (:error (rest.contract/decode-request [:map [:id :int]] {}))))

  (testing "no schema declared means nothing to judge"
    ;; a :get has no body and declares no :web/request; that is an absence, not
    ;; a violation, and decoding against a schema nobody wrote would be
    ;; inventing one. Same distinction :cli/args [:catn] draws on the other
    ;; side — "takes nothing" and "never said" are different statements.
    (let [r (rest.contract/decode-request nil {:anything 1})]
      (is (nil? (:error r)))
      (is (= {:anything 1} (:value r))
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
