(ns slopp.rest.endpoint-test
  "Cover for turning an endpoint descriptor into a request.

  Two properties are worth the suite and neither is about the happy path. The
  ENCODING splits on what a `/` means — data inside a segment, structure inside
  a `**` remainder — and getting it backwards stops the round trip closing in a
  way that looks like a missing form rather than a wrong url. And the PARAM
  GUARD has to refuse what the contract does not name while never inventing a
  guard from a descriptor that enumerates nothing, because a guard built from a
  gap refuses what the boundary would accept."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.rest.endpoint :as endpoint]))

(deftest a-DESCRIPTOR-and-params-become-a-finished-request
  ;; What replaces the generated `-request` builder. The builder was an address
  ;; and a method wrapped in a function, with the contract split into a second
  ;; generated namespace — so the two facts about an endpoint that always
  ;; travel together were kept apart to dodge a tier.
  ;;
  ;; A descriptor is a `def`: it REFERENCES its schemas rather than calling
  ;; malli, so it stays `:pure` and a `:pure` view may name it. And it is one
  ;; var, so `query_depends` on it answers "which pages call this endpoint".
  (let [form   {:http/method :get :http/path "/api/form/:id"
                :http/params #{:id :depth}
                :rest/response [:map [:form-id :string]]}
        assets {:http/method :get :http/path "/assets/**"}
        one    {:http/method :get :http/path "/files/*"}
        create {:http/method :post :http/path "/api/orders"
                :http/params #{:sku :qty}
                :rest/request [:map [:sku :string] [:qty :int]]}]

    (testing "the address is RESOLVED here, so no performer has to decide it"
      (is (= {:http/method :get :http/url "/api/form/f1"
              :rest/response [:map [:form-id :string]]}
             (endpoint/request form {:id "f1"}))))

    (testing "a segment value is percent-encoded, so it cannot break out"
      ;; the round trip both matchers hold up their end of: `register!` came
      ;; back as a form nobody had defined when only one end encoded
      (is (= "/api/form/register%21" (:http/url (endpoint/request form {:id "register!"})))))

    (testing "a ** remainder encodes each sub-segment and joins — its slashes are STRUCTURE"
      (is (= "/assets/cljs/main.js" (:http/url (endpoint/request assets {:* "cljs/main.js"}))))
      (is (= "/assets/a%20b/x%21.js"
             (:http/url (endpoint/request assets {:* "a b/x!.js"}))))
      (is (= "/assets/" (:http/url (endpoint/request assets {})))
          "** matches zero segments, so an absent remainder is legal"))

    (testing "a * remainder is ONE segment, so its slash is DATA"
      (is (= "/files/a%2Fb" (:http/url (endpoint/request one {:* "a/b"})))))

    (testing "what the path did not consume travels as a QUERY on a non-body verb"
      (is (= "/api/form/f1?depth=2" (:http/url (endpoint/request form {:id "f1" :depth 2}))))
      (is (not (contains? (endpoint/request form {:id "f1" :depth 2}) :http/body))))

    (testing "and as a BODY on a body verb, with the path params still out of it"
      (let [r (endpoint/request create {:sku "abc" :qty 2})]
        (is (= "/api/orders" (:http/url r)))
        (is (= {:sku "abc" :qty 2} (:http/body r)))
        (is (= [:map [:sku :string] [:qty :int]] (:rest/request r)))))

    (testing "a param the contract does not name is REFUSED, not sent"
      ;; the measured failure: a consumer passed the map it HAD — its own route
      ;; params — and the extras rode out as a query string to a service that
      ;; never asked
      (let [e (try (endpoint/request form {:id "f1" :rogue 1}) nil
                   (catch Exception e e))]
        (is (some? e) "an undeclared param must not travel")
        (is (re-find #":rogue" (ex-message e)) (ex-message e))))

    (testing "a descriptor that enumerates NOTHING guards nothing"
      ;; :http/params absent means the contract could not be enumerated, and a
      ;; guard invented from a gap would refuse what the boundary accepts
      (is (= "/assets/x" (:http/url (endpoint/request assets {:* "x"})))))

    (testing "the response contract TRAVELS, which is what retires the checks namespace"
      ;; the whole reason a descriptor beats a builder: the thing that validates
      ;; the answer arrives with the thing that asks the question
      (is (= [:map [:form-id :string]] (:rest/response (endpoint/request form {:id "f1"})))))

    (testing "and a descriptor with no params at all needs no map"
      (is (= {:http/method :get :http/url "/api/timeline"}
             (endpoint/request {:http/method :get :http/path "/api/timeline"}))))))

(deftest a-value-cannot-BREAK-OUT-of-the-segment-it-was-put-in
  ;; Inherited from `slopp.webapp/request-url`, whose job this took over. The
  ;; scars are the reason the derivation is `:cljc` and asserted rather than
  ;; assembled in a browser: slopp-ui's own performer split a request into
  ;; pieces so it "has nothing left to decide", and their docstring named the
  ;; concrete bug — *a performer doing `str/replace` on `:m` would corrupt
  ;; `/api/:module/:m`, and that would ship, because the string is assembled in
  ;; a browser and asserted nowhere.*
  (let [url (fn [path params] (:http/url (endpoint/request {:http/path path} params)))]

    (testing "SEGMENT-WISE substitution, which is the bug they already paid for"
      ;; `(str/replace "/api/:module/:m" ":m" "y")` gives "/api/yodule/y" — a
      ;; parameter whose name is a PREFIX of another corrupts the path, and the
      ;; result still looks like a url
      (is (= "/api/x/y" (url "/api/:module/:m" {:module "x" :m "y"}))))

    (testing "a value cannot break out of its segment, which is the security half"
      ;; every character outside RFC 3986's unreserved set is escaped, so a
      ;; slash in a value is data rather than structure and a ? does not start
      ;; a query
      (is (= "/api/module/a%2Fb"     (url "/api/module/:m" {:m "a/b"})))
      (is (= "/api/module/a%3Fq%3D1" (url "/api/module/:m" {:m "a?q=1"})))
      (is (= "/api/module/a%26b"     (url "/api/module/:m" {:m "a&b"}))))

    (testing "an empty query leaves no trailing ?"
      ;; a url that differs from the one a reader typed is a cache key nobody
      ;; predicted
      (is (= "/api/search" (url "/api/search" {}))))

    (testing "and a nil param is DROPPED rather than sent as \"null\""
      ;; an absent optional and an explicit nil mean the same thing to a query
      ;; string, and only one of them is spellable
      (is (= "/api/search" (url "/api/search" {:q nil})))
      (is (= "/api/search?q=a%20b" (url "/api/search" {:q "a b"}))))))
