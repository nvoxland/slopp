(ns slopp.otel-test   "Covers `slopp.otel` against payloads TRANSCRIBED FROM A REAL CAPTURE, which
  is the only reason they are evidence.

  This namespace reads a schema slopp does not own, so a fixture invented from
  documentation would test that the parser agrees with our memory of the
  format. Every shape here — the envelope, the attribute names, the value
  encodings — came off the wire from a live run, including the one detail no
  spec would have told us: this exporter sends `intValue` as a JSON number
  where the spec permits a string.

  When a CLI version bump breaks something, the fix is to re-capture and update
  these fixtures, not to loosen the assertions."
  (:require [slopp.otel :as otel]
            [clojure.test :refer [deftest testing is]]))

(deftest an-attribute-value-unwraps-from-every-shape-the-CLI-actually-sends
  ;; OTLP wraps every attribute value in a single-key map naming its type.
  ;; The spec PERMITS intValue as a string (JSON has one number type and
  ;; int64 does not survive it); Claude Code 2.1.250 sends a NUMBER. A parser
  ;; that handles only the spec's shape reads every token count as nil, and a
  ;; nil token count is indistinguishable from a request that used none.
  (testing "the three shapes observed in a real capture"
    (is (= "claude-opus-5" (otel/attr-value {:stringValue "claude-opus-5"})))
    (is (= 9987 (otel/attr-value {:intValue 9987})))
    (is (= 0.0806935 (otel/attr-value {:doubleValue 0.0806935}))))
  (testing "intValue as a STRING, which the spec allows and a different exporter may send"
    (is (= 9987 (otel/attr-value {:intValue "9987"}))))
  (testing "an unknown or empty shape is nil rather than a guess"
    (is (nil? (otel/attr-value {})))
    (is (nil? (otel/attr-value {:bytesValue "AA=="})))
    (is (nil? (otel/attr-value nil))))
  (testing "boolValue, because false must not read as absent"
    (is (false? (otel/attr-value {:boolValue false})))))

(deftest api-requests-normalizes-a-REAL-payload-and-drops-the-identity-attributes
  ;; The fixture is transcribed from a capture against Claude Code 2.1.250 —
  ;; envelope shape, attribute names and value encodings all as observed. See
  ;; the schema note that ships with this store's docs for how to re-capture;
  ;; the names are a product's, not a standard's, and nothing warns you when
  ;; they move.
  (let [attr (fn [k v] {:key k :value v})
        record (fn [body attrs]
                 {:timeUnixNano "1787881784700000000"
                  :body {:stringValue body}
                  :attributes attrs})
        ident [(attr "user.id" {:stringValue "ec1ac08b"})
               (attr "session.id" {:stringValue "abb10d97-56e9-4c7f-8d08-99130b092c64"})
               (attr "organization.id" {:stringValue "ee0c7701"})
               (attr "user.email" {:stringValue "someone@example.com"})
               (attr "user.account_uuid" {:stringValue "ba56c8d8"})
               (attr "user.account_id" {:stringValue "user_01Q1"})
               (attr "terminal.type" {:stringValue "Apple_Terminal"})]
        payload {:resourceLogs
                 [{:resource {:attributes [(attr "service.name" {:stringValue "claude-code"})
                                           (attr "service.version" {:stringValue "2.1.250"})]}
                   :scopeLogs
                   [{:scope {:name "com.anthropic.claude_code.events" :version "2.1.250"}
                     :logRecords
                     [(record "claude_code.user_prompt"
                              (conj ident (attr "event.name" {:stringValue "user_prompt"})))
                      (record "claude_code.api_request"
                              (into ident
                                    [(attr "event.name" {:stringValue "api_request"})
                                     (attr "prompt.id" {:stringValue "76e226ad"})
                                     (attr "event.sequence" {:intValue 26})
                                     (attr "model" {:stringValue "claude-opus-5"})
                                     (attr "input_tokens" {:intValue 2})
                                     (attr "output_tokens" {:intValue 4})
                                     (attr "cache_read_tokens" {:intValue 9987})
                                     (attr "cache_creation_tokens" {:intValue 7559})
                                     (attr "cost_usd" {:doubleValue 0.0806935})
                                     (attr "duration_ms" {:intValue 1475})
                                     (attr "effort" {:stringValue "high"})
                                     (attr "query_source" {:stringValue "sdk"})]))]}]}]}
        [r :as rs] (otel/api-requests payload)]

    (testing "only api_request records — the other six event kinds are not this"
      (is (= 1 (count rs)) (pr-str rs)))

    (testing "the fields that cost money"
      (is (= "abb10d97-56e9-4c7f-8d08-99130b092c64" (:session r)))
      (is (= "76e226ad" (:prompt r)))
      (is (= "claude-opus-5" (:model r)))
      (is (= 2 (:input r)))
      (is (= 4 (:output r)))
      (is (= 9987 (:cache-read r)))
      (is (= 7559 (:cache-creation r)))
      (is (= 0.0806935 (:cost-usd r)))
      (is (= 1475 (:duration-ms r))))

    (testing "CONTEXT SIZE is derived, because the CLI does not send it"
      ;; every request ships the whole conversation, split into what was
      ;; cached and what was not. This is the signal slopp cannot observe for
      ;; itself and the reason this namespace exists.
      (is (= (+ 2 9987 7559) (:context r))
          (str "context must be input + cache-read + cache-creation: " (pr-str r))))

    (testing "the IDENTITY attributes are dropped at the boundary, not filtered later"
      ;; user.email rides EVERY record. A store that persists raw telemetry
      ;; persists the operator's email address, and the honest place to stop
      ;; that is where the record is built.
      (let [vals (set (map str (vals r)))]
        (is (not (contains? vals "someone@example.com")) (pr-str r))
        (is (every? #(not (contains? r %))
                    [:user.email :user-email :user.id :organization.id :user-id])
            (pr-str (keys r)))))))
