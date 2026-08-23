(ns slopp.edit.modules-test
  "Gates exercised as PURE FUNCTIONS of a store value.

  Every check in `slopp.edit.modules` takes a candidate store and returns a
  teaching string or nil, so these tests build a small store with
  `store/ingest`, flip the capabilities that arm a rule, and call the gate
  directly — no write path, no image, no server. That is what keeps them fast
  and what makes a refusal's WORDING testable: several assertions here check
  the teaching, not just that something fired, because the string is the
  entire user experience of a gate.

  The write path itself — that a refusal actually blocks a write, and that
  the per-store dial downgrades it — is `slopp.modules-test`'s job."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.edit.http :as edit.http] [slopp.project.capabilities :as capabilities] [clojure.string :as str] [slopp.edit.gates :as gates]))

(deftest web-gates-guard-the-declared-surface
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:http/method :get :http/path \"/api/users/:id\"\n"
                 "        :http/auth [:group \"admin\"]} get-user \"U.\" [req] req)\n\n"
                 "(defn ^{:http/effect :user/insert} insert-user! \"I.\" [ctx row] row)\n")
        s0  (store/ingest (store/empty-store) 'shop.api src)
        on  (first (store/record-config-put s0 "capabilities" :manifest "http.enabled" "true"))
        land (fn [st form-src]
               (store/ingest st 'shop.more (str "(ns shop.more)\n\n" form-src "\n")))]
    (testing "OFF: dispatch runs no http gate while http.enabled is absent.
              Asserted through gate-check rather than through the gate, because
              inertness moved OUT of the gates: each one used to open with
              `(when (web-enabled? candidate) …)`, which is a rule nine authors
              had to remember and nothing reminded them of. The gate is now a
              pure question about the FORM and answers it wherever it is asked;
              only dispatch knows whether this store asked.
              a-gate-under-a-capability-is-inert-while-it-is-off covers the
              whole derived population — this keeps one concrete instance
              beside the ON cases it pairs with."
      (let [s (land s0 "(defn ^{:http/method :get :http/path \"/x\"} bare \"B.\" [req] req)")]
        (is (empty? (:refusals (gates/gate-check s 'shop.more 'bare))))
        (is (some? (edit.http/http-auth-refusal s 'shop.more 'bare))
            "the gate itself still answers — that is the seam, not a leak")))
    (testing "http-auth-refusal: an endpoint with no :http/auth refuses with teaching"
      (let [s (land on "(defn ^{:http/method :get :http/path \"/x\"} bare \"B.\" [req] req)")]
        (is (re-find #":http/auth" (str (edit.http/http-auth-refusal s 'shop.more 'bare))))
        (testing "a declared :public discharges it — deny is the default, not the ceiling"
          (let [s2 (land on "(defn ^{:http/method :get :http/path \"/x\" :http/auth :public} open \"O.\" [req] req)")]
            (is (nil? (edit.http/http-auth-refusal s2 'shop.more 'open)))))
        (testing "a non-endpoint never trips it"
          (is (nil? (edit.http/http-auth-refusal s 'shop.api 'insert-user!))))))
    (testing "http-route-collision: a second claim on method+path refuses; the same form re-landing does not"
      (let [s (land on "(defn ^{:http/method :get :http/path \"/api/users/:id\" :http/auth :public} dupe \"D.\" [req] req)")]
        (is (re-find #"/api/users/:id" (str (edit.http/http-route-collision s 'shop.more 'dupe))))
        (is (nil? (edit.http/http-route-collision on 'shop.api 'get-user))
            "a form is never its own collision (the re-land/replace case)")
        (testing "same path, different method, no collision"
          (let [s2 (land on "(defn ^{:http/method :post :http/path \"/api/users/:id\" :http/auth :public} other \"O.\" [req] req)")]
            (is (nil? (edit.http/http-route-collision s2 'shop.more 'other)))))))
    (testing "http-undeclared-effect: a declared kind needs a marked performer"
      (let [s (land on (str "(defn ^{:http/method :post :http/path \"/y\" :http/auth :public\n"
                            "        :http/effects [:user/insert :email/welcome]} mk \"M.\" [req] req)"))]
        (is (re-find #":email/welcome" (str (edit.http/http-undeclared-effect s 'shop.more 'mk))))
        (testing "every kind covered → clean"
          (let [s2 (land on (str "(defn ^{:http/method :post :http/path \"/y\" :http/auth :public\n"
                                 "        :http/effects [:user/insert]} mk2 \"M.\" [req] req)"))]
            (is (nil? (edit.http/http-undeclared-effect s2 'shop.more 'mk2)))))))
    (testing "http-unsafe-get: a GET declaring effect kinds refuses; a POST doing the same is fine"
      (let [s (land on (str "(defn ^{:http/method :get :http/path \"/z\" :http/auth :public\n"
                            "        :http/effects [:user/insert]} gz \"G.\" [req] req)"))]
        (is (re-find #"GET" (str (edit.http/http-unsafe-get s 'shop.more 'gz))))
        (let [s2 (land on (str "(defn ^{:http/method :post :http/path \"/z\" :http/auth :public\n"
                               "        :http/effects [:user/insert]} pz \"P.\" [req] req)"))]
          (is (nil? (edit.http/http-unsafe-get s2 'shop.more 'pz))))))
    (testing "http-unsafe-get: a GET whose handler reaches a mutation refuses"
      (let [s (land on (str "(def store-atom (atom {}))\n\n"
                            "(defn ^{:http/method :get :http/path \"/w\" :http/auth :public} gw \"G.\" [req]\n"
                            "  (swap! store-atom assoc :hit req))"))]
        (is (re-find #"mutation" (str (edit.http/http-unsafe-get s 'shop.more 'gw))))))))

(deftest http-unknown-group-guards-the-policy-vocabulary
  (let [s0 (store/ingest (store/empty-store) 'shop.api "(ns shop.api)\n\n(defn seed \"S.\" [x] x)\n")
        on (-> s0
               (store/record-config-put "capabilities" :manifest "http.enabled" "true") first
               (store/record-config-put "capabilities" :manifest "http.auth.groups.admin.members" "alice") first)
        land (fn [st form-src]
               (store/ingest st 'shop.more (str "(ns shop.more)\n\n" form-src "\n")))]
    (testing "a policy naming a configured group lands"
      (let [s (land on "(defn ^{:http/method :get :http/path \"/a\" :http/auth [:group \"admin\"]} a \"A.\" [req] req)")]
        (is (nil? (edit.http/http-unknown-group s 'shop.more 'a)))))
    (testing "a typo'd group refuses with the configured vocabulary in the teaching"
      (let [s (land on "(defn ^{:http/method :get :http/path \"/b\" :http/auth [:group \"admn\"]} b \"B.\" [req] req)")]
        (is (re-find #"admn" (str (edit.http/http-unknown-group s 'shop.more 'b))))
        (is (re-find #"admin" (str (edit.http/http-unknown-group s 'shop.more 'b))))))
    (testing "composite policies are walked"
      (let [s (land on "(defn ^{:http/method :get :http/path \"/c\" :http/auth [:any :authenticated [:group \"ghost\"]]} c \"C.\" [req] req)")]
        (is (re-find #"ghost" (str (edit.http/http-unknown-group s 'shop.more 'c))))))
    (testing "inert until http.enabled — decided by DISPATCH, not by the gate.
              The gate answers about the form wherever it is asked; whether
              this store asked is edit.gates/gate-check's question, read off
              the namespace the gate lives in. Nine gates used to carry that
              guard themselves, which is nine places to forget it."
      (let [s (land s0 "(defn ^{:http/method :get :http/path \"/d\" :http/auth [:group \"ghost\"]} d \"D.\" [req] req)")]
        (is (empty? (:refusals (gates/gate-check s 'shop.more 'd))))))))

(deftest http-generated-ns-gate-refuses-hand-edits
  ;; the second duty of ^:generated (D-web-contracts part 2): a write gate
  ;; refuses HAND edits to a generated form. Regeneration rewrites the ns
  ;; wholesale through store/ingest (below the gate layer), so the generator
  ;; itself is unaffected — only edit-tool writes reach this gate.
  (let [st (-> (store/empty-store)
               (store/ingest 'gc.client
                             (str "(ns gc.client)\n\n"
                                  "(defn ^{:generated \"app.orders/create-order\"} create-order! [x] x)\n\n"
                                  "(defn hand [x] x)\n")))]
    (testing "editing a ^:generated form refuses, teaching to regenerate instead"
      (let [t (edit.http/http-generated-ns st 'gc.client 'create-order!)]
        (is (string? t) (pr-str t))
        (is (re-find #"generate_client" t))))
    (testing "a normal form is untouched by the gate"
      (is (nil? (edit.http/http-generated-ns st 'gc.client 'hand))))))

(deftest client-signature-tracks-endpoint-contracts
  ;; the fingerprint behind the generated-client staleness advisory
  ;; (D-web-contracts part 2): it changes iff an endpoint's declared contract
  ;; changes, so the advisory can nudge "run generate_client" without re-rendering.
  (let [mk (fn [resp] (store/ingest (store/empty-store) 'sig.api
                                    (str "(ns sig.api)\n\n"
                                         "(defn ^{:http/method :post :http/path \"/o\""
                                         " :rest/request sig.c/a :rest/response " resp "} make [r] r)\n")))]
    (testing "stable for identical contracts"
      (is (= (edit.http/client-signature (mk "sig.c/a"))
             (edit.http/client-signature (mk "sig.c/a")))))
    (testing "changes when a response contract changes"
      (is (not= (edit.http/client-signature (mk "sig.c/a"))
                (edit.http/client-signature (mk "sig.c/b")))))
    (testing "an endpointless store still fingerprints (a stable string)"
      (is (string? (edit.http/client-signature (store/empty-store)))))))

(deftest the-deps-a-handler-reads-must-have-a-declared-source
  (let [s0   (store/ingest (store/empty-store) 'shop.api "(ns shop.api)\n")
        on   (first (store/record-config-put s0 "capabilities" :manifest "http.enabled" "true"))
        with-builder (store/ingest on 'shop.sys
                                   (str "(ns shop.sys)\n\n"
                                        "(defn ^{:http/context true} app-context \"C.\" [] {:registry :r})\n"))
        land (fn [st form-src]
               (store/ingest st 'shop.more (str "(ns shop.more)\n\n" form-src "\n")))
        endpoint (fn [body]
                   (str "(defn ^{:http/method :get :http/path \"/x\" :http/auth :public} h \"H.\" [req] "
                        body ")"))]
    (testing "OFF: dispatch runs no http gate while http.enabled is absent —
              the gate itself still answers about the form, which is the seam"
      (let [s (land s0 (endpoint "(:http/deps req)"))]
        (is (empty? (:refusals (gates/gate-check s 'shop.more 'h))))))
    (testing "an endpoint reading :http/deps with no builder refuses, naming the marker"
      (let [teach (str (edit.http/http-undeclared-context (land on (endpoint "(:http/deps req)"))
                                                       'shop.more 'h))]
        (testing "the fix is a LITERAL FORM, not a description of one — cold-read
                  evidence says that is what made it actionable without the skill:
                  the marker spelling, the arity, defn-not-def and the return
                  shape all come off it at once"
          (is (re-find #"\(defn \^\{:http/context true\}" teach) teach)
          (is (re-find #"ONE" teach) "and that a second is not allowed"))
        (testing "it does NOT argue that the context cannot be a performer"
          ;; the right sentence in the wrong room. It answers a DESIGN question
          ;; to a reader in fix-it mode who has already been handed the form,
          ;; and it is the one clause that needs knowing what a performer IS.
          ;; It lives in api.web/context-builder's docstring and the SKILL,
          ;; where someone DECIDING meets it.
          (is (not (re-find #"performer" teach)) teach))
        (testing "and the lifecycle line is true for the store being refused"
          ;; this gate fires on any http.enabled store, including one with
          ;; dev.server false where no managed server boots at all. A clause
          ;; phrased around done points asserts, to that reader, a behaviour
          ;; that does not happen to them — a general truth in this store's
          ;; voice, which is the shape that keeps costing us.
          (is (not (re-find #"done point|managed server" teach)) teach)
          (is (re-find #"new each time" teach) teach))))
    (testing ":http/keys destructuring is the same read"
      (let [s (land on (str "(defn ^{:http/method :get :http/path \"/x\" :http/auth :public} h \"H.\"\n"
                            "  [{:http/keys [deps]}] deps)"))]
        (is (re-find #":http/context" (str (edit.http/http-undeclared-context s 'shop.more 'h))))))
    (testing "a declared builder discharges it"
      (is (nil? (edit.http/http-undeclared-context (land with-builder (endpoint "(:http/deps req)"))
                                                'shop.more 'h))))
    (testing "an endpoint that never reads deps is not asked to declare a source"
      (is (nil? (edit.http/http-undeclared-context (land on (endpoint "req")) 'shop.more 'h))))
    (testing "a NON-endpoint naming :http/deps is the framework's own dispatcher, not an app handler"
      (let [s (land on "(defn dispatch! \"D.\" [ctx req] (assoc req :http/deps (:http/perform-ctx ctx)))")]
        (is (nil? (edit.http/http-undeclared-context s 'shop.more 'dispatch!)))))))

(deftest ^{:correspondence "every gate in edit.gates/per-form-write-gates implemented under slopp.edit.<capability> vs that capability being off — the guard each gate used to write for itself"}
  a-gate-under-a-capability-is-inert-while-it-is-off
  ;; Nine gates each opened with `(when (web-enabled? candidate) …)`. That is a
  ;; rule every gate author has to know and none of them is reminded of, and
  ;; forgetting it does not fail loudly — it fires an HTTP rule on a store that
  ;; never asked for HTTP, which is the adoption story breaking for projects
  ;; that will never read this file.
  ;;
  ;; So inertness moved OUT of the gates and into dispatch: a gate implemented
  ;; in `slopp.edit.<capability>` does not run unless that capability is
  ;; enabled. There is nothing left to remember, which is the only version of
  ;; this that survives capability #5.
  ;;
  ;; **So this drives `gate-check`, not the gates.** Calling
  ;; `http-auth-refusal` directly on an opted-out store now returns its
  ;; teaching, correctly — the gate answers about the FORM and dispatch answers
  ;; about the store. A test that called the var would be asserting the old
  ;; design.
  ;;
  ;; The population is DERIVED from the registry rather than listed, for the
  ;; same reason `the-web-framework-never-reaches-back-into-slopp` derives its
  ;; own: a hand-kept list goes stale in exactly the direction that matters,
  ;; reporting green over the gates somebody remembered.
  (let [capability-gates
        (for [v gates/per-form-write-gates
              :let [ns-sym (str (ns-name (:ns (meta v))))
                    c (capabilities/capability
                       (last (str/split ns-sym #"\.")))]
              :when (and c (:requires c))]
          {:capability (:capability c) :gate (:name (meta v))})
        ;; a :http/path endpoint declaring NO auth policy: the exact shape
        ;; http-auth-refusal exists to refuse, so an opted-out store LANDING it
        ;; is the property under test rather than an absence of anything to
        ;; trip over.
        src   (str "(ns shop.api)\n\n"
                   "(defn ^{:http/method :get :http/path \"/x\"} x \"X.\" [req] req)\n")
        naked (store/ingest (store/empty-store) 'shop.api src)
        on    (first (store/record-config-put naked "capabilities" :manifest
                                              "http.enabled" "true"))]
    (testing "the derivation found gates — over an empty set this proves nothing"
      (is (seq capability-gates)
          "no write gate resolved to a capability namespace; the derivation is not reading the registry")
      (is (some #(= 'http-auth-refusal (:gate %)) capability-gates)
          (str "pinned on a NAMED member rather than a count, because a count is"
               " a second hand-kept number that goes stale the other way."
               " Found: " (vec (map :gate capability-gates)))))
    (testing "OFF: dispatch runs no capability gate, so nothing refuses or advises"
      (let [{:keys [refusals advisories]} (gates/gate-check naked 'shop.api 'x)]
        (is (empty? refusals) (pr-str refusals))
        (is (empty? advisories) (pr-str advisories))))
    (testing "ON: the same store and the same form now refuses"
      ;; the other half, and it is what stops this passing by breaking dispatch
      ;; altogether — a gate that never runs anywhere satisfies the case above.
      (let [{:keys [refuse]} (gates/gate-check on 'shop.api 'x)]
        (is (some? refuse) "an endpoint with no :http/auth must refuse once http is enabled")
        (is (re-find #":http/auth" (str refuse)) (pr-str refuse))))
    (testing "a capability gate still answers about the FORM when asked directly"
      ;; the seam this change creates, stated so it is not mistaken for a bug:
      ;; the gate is a pure question about the candidate form, and only
      ;; dispatch knows whether this store asked the question. Anything that
      ;; calls a gate var itself owns that decision.
      (is (some? (edit.http/http-auth-refusal naked 'shop.api 'x))))
    (testing "a MARKER-armed gate opts out and keeps firing on an opted-out store"
      ;; The regression this exists to prevent, and it SHIPPED for an hour:
      ;; deriving a gate's capability from the namespace it lives in silenced
      ;; `http-generated-ns`, which is armed by `^:generated` on the form rather
      ;; than by the store's configuration. A store holding generated code holds
      ;; it whether or not it serves HTTP, so a generated form became quietly
      ;; hand-editable — caught only by an end-to-end external test.
      ;;
      ;; Both halves, because either alone passes for the wrong reason: the
      ;; opt-out has to be READ by the derivation, and the gate has to actually
      ;; refuse through dispatch on a store with no capability at all.
      (is (nil? (gates/gate-capability #'edit.http/http-generated-ns))
          "the ^{:rule/capability :any} opt-out is not being read")
      (let [gen (store/ingest (store/empty-store) 'shop.client
                              (str "(ns shop.client)\n\n"
                                   "(defn ^{:generated \"shop.api/mk\"} mk! \"M.\" [p] p)\n"))]
        (is (some? (:refuse (gates/gate-check gen 'shop.client 'mk!)))
            "a generated form must refuse a hand edit on ANY store, http or not")))))

(deftest a-half-migrated-endpoint-is-told-it-is-MID-MIGRATION-not-missing-a-declaration
  ;; Reported by a consuming store, mid marker-family migration. Sweeping the
  ;; marker that CONSTITUTES an endpoint first leaves every route declaring
  ;; `:http/path` beside siblings that have not moved yet, and the auth gate
  ;; fires correctly — it IS an endpoint with no `:http/auth`.
  ;;
  ;; The refusal was right and its TEACHING was wrong for this case: "declares
  ;; a route but no :http/auth" is exactly what a genuinely forgotten
  ;; declaration looks like, and the obvious remedy — adding `:http/auth` by
  ;; hand — produces a form carrying a retired marker beside a live one, half
  ;; migrated, which nothing flags afterwards. They avoided it only by knowing
  ;; they were mid-migration.
  ;;
  ;; The tool can tell the two apart from what is ALREADY on the form: a
  ;; retired marker that maps to the missing one is an ordering artifact, not
  ;; an omission. Read from the retired-marker ledger rather than a hardcoded
  ;; pair, so the next family migration gets this for free.
  (let [mk (fn [src] (store/ingest (store/empty-store) 'shop.mid src))]

    (testing "a genuinely bare endpoint still gets the default-deny teaching"
      ;; population control: if the new branch swallowed the ordinary case the
      ;; gate would have stopped doing its job
      (let [s (mk (str "(ns shop.mid)\n\n"
                       "(defn ^{:http/method :get :http/path \"/x\"}\n"
                       "  bare \"B.\" [_] {:status 200})\n"))
            r (str (edit.http/http-auth-refusal s 'shop.mid 'bare))]
        (is (re-find #":http/auth" r) r)
        (is (not (re-find #"(?i)migration" r))
            (str "an ordinary omission was told it was mid-migration: " r))))

    (testing "but one carrying the RETIRED sibling is told what is actually happening"
      (let [s (mk (str "(ns shop.mid)\n\n"
                       "(defn ^{:http/method :get :http/path \"/x\" :web/auth :public}\n"
                       "  half \"H.\" [_] {:status 200})\n"))
            r (str (edit.http/http-auth-refusal s 'shop.mid 'half))]
        (is (re-find #"(?i)migrat" r)
            (str "a half-migrated endpoint reads as a forgotten declaration: " r))
        (is (re-find #":web/auth" r)
            (str "the refusal does not name the retired marker that is already here: " r))))))
