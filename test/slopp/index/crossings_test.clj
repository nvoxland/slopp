(ns slopp.index.crossings-test
  "Cover for the boundary inventory.

  The subject computes almost nothing, so these are not tests of a
  calculation. Its failure mode is SILENCE — a hole that goes unmentioned, a
  new exit the registry does not notice, a note that appears so often nobody
  reads it. So each test here asserts that something is said, or deliberately
  not said, and the fixtures are the exits slopp's own store actually has.

  Pure over a store value, so in-image and sub-millisecond."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.store :as store]
            [slopp.index.crossings :as crossings] [slopp.ops.external :as external] [slopp.rules.markers :as markers]))

(deftest the-inventory-reports-holes-and-refuses-to-miss-a-new-one
  ;; slopp models edges INSIDE the store — the reference graph — and
  ;; has no representation for one that LEAVES it. So every exit is unverified
  ;; by construction, and each grows a hand-written check or none. Fifteen of
  ;; the sixteen frictions in the SPA wave landed at a crossing.
  ;;
  ;; This does not verify the far side; nothing here could. It makes the exits
  ;; ENUMERABLE, and makes an exit with no checker say so. The two properties
  ;; that keep it from becoming a document that rots are below.
  (let [st (-> (store/empty-store)
               (store/ingest 'app.api "(ns app.api)
(defn ^{:http/method :get :http/path \"/api/x\" :rest/response [:map]} x [_] {})
(defn ^{:http/method :get :http/path \"/\" :webapp/client-routes [\"/app\"]} doc [_] {})
(defn ^{:http/external-path \"nginx serves the docs site\"} docs-link [] [:a {:href \"/docs/\"} \"docs\"])"))]
    (testing "every exit is listed, with the checker that covers it"
      (let [r (crossings/store-crossings st)
            by (into {} (map (juxt :kind identity)) (:crossings r))]
        (is (contains? by :http/routing))
        (is (contains? by :wire/json))
        ;; NOT `(string? (:checked-by …))`, which is what this asserted until
        ;; 2026-08-15 — and it passed for a year against a string claiming the
        ;; dispatcher validates every request and response against the schema
        ;; the client ships. It does not, and never did: `handle!` never reads
        ;; :rest/request or :rest/response, and no namespace in the shipped
        ;; slopp.http family requires malli at all.
        ;;
        ;; So the test asserted the field was FILLED IN rather than that it was
        ;; TRUE, which is the only thing a test over prose can check — and it
        ;; is why the wrong answer survived. Assert the shape here and let the
        ;; unchecked block below carry the claim that can be wrong.
        (is (contains? (by :wire/json) :checked-by)
            "the field is present whatever its value; nil is an answer here")))
    (testing "an exit with NO checker is reported, not omitted"
      ;; the whole point — an absent checker and an absent crossing look
      ;; identical unless one of them is written down
      (let [r  (crossings/store-crossings st)
            un (set (map :kind (:unchecked r)))]
        (is (contains? un :http/foreign-route)
            "a link marked as served by somebody ELSE is the crossing that is
             honest about being one: the declaration IS the whole check, and
             nothing confirms the foreign server serves that path or still
             does. It is permanent by construction, which makes it the right
             example here — :webapp/client-routing used to be this test's
             example and stopped being a hole when the client table became
             data, so an example that CAN be closed makes this test a hostage
             to progress")
        (is (not (contains? un :wire/json))
            "the wire contract is CHECKED now — the boundary decodes a request
             body against its declared schema and refuses what does not fit,
             and judges a response on what the client actually receives. This
             assertion ran the other way for one commit, deliberately: the row
             claimed a check that did not exist, was corrected to say so, and
             this is where it flipped back on a check that does. A crossing
             moving between the two lists is the only honest way this inventory
             ever changes.")))
    (testing "a marker NO kind claims is a finding — this is what stops it rotting"
      ;; the failure mode of any inventory: someone adds an exit and the list
      ;; silently does not describe the system any more
      (let [st2 (store/ingest st 'app.socket
                              "(ns app.socket)
(defn ^{:web/websocket \"/feed\"} feed [_] {})")
            r   (crossings/store-crossings st2)]
        (is (= [:web/websocket] (map :marker (:unclassified r)))
            "a slopp-namespaced marker that no crossing kind claims")
        (is (= '[app.socket/feed] (map :at (:unclassified r))))))
    (testing "a store with no exits says so with an empty inventory, not a nil"
      (let [bare (store/ingest (store/empty-store) 'plain "(ns plain)\n(defn f [] 1)")
            r    (crossings/store-crossings bare)]
        (is (= [] (:crossings r)))
        (is (= [] (:unclassified r)))))))

(deftest a-marker-that-is-deliberately-not-an-exit-must-say-so
  ;; Run against slopp's own store the moment it existed, the inventory
  ;; reported five markers as unclassified: :http/auth, :rest/client,
  ;; :http/effectful, :rule/applies-to, :rule/severity. None is an exit —
  ;; auth is enforced in-process, :rest/client MODIFIES the generated-client
  ;; crossing rather than being one, and :rule/* is the rule registry talking
  ;; to itself.
  ;;
  ;; That is the self-policing working, and it also shows what it needs to
  ;; stay usable: classification has to be TOTAL. With no way to say "not an
  ;; exit", every internal marker reads as a hole and the real finding drowns
  ;; in them — the precision failure that got :positional-form-access
  ;; withdrawn.
  (let [st (store/ingest (store/empty-store) 'app.api
                         "(ns app.api)
(defn ^{:http/method :get :http/path \"/x\" :http/auth :public :http/effectful true} x [_] {})")]
    (testing "a declared-internal marker is not a finding"
      (let [r (crossings/store-crossings st)]
        (is (= [] (:unclassified r))
            "auth is enforced in-process and never leaves — saying nothing
             about it would leave it looking like an unchecked exit")))
    (testing "and the classification is total — every marker slopp owns has a home"
      (is (empty? (crossings/unclassified-markers))
          "a marker in neither list is an exit nobody decided about"))
    (testing "including a marker slopp's OWN store never uses"
      ;; `:http/context` shipped classified nowhere. slopp declares no builder,
      ;; so nothing here exercised it, and the vocabulary list above is
      ;; hand-kept — the guard-on-the-guard cannot see a marker nobody added
      ;; to it. It was found by the first APP to declare one, whose full_check
      ;; then carried a permanent unclassified entry it could do nothing
      ;; about. A standing unexplained line is how a report stops being read,
      ;; so the cost lands on every store that adopts the feature.
      (let [st (store/ingest (store/empty-store) 'app.sys
                             "(ns app.sys)\n(defn ^{:http/context true} deps \"D.\" [] {})")]
        (is (= [] (:unclassified (crossings/store-crossings st)))
            "a builder DECLARATION is not an exit — it names which fn builds
             the context, and the map it returns never leaves the image")))))

(deftest the-inventory-is-a-note-not-a-verdict
  ;; It has to reach a surface someone reads, and `full_check` is the one that
  ;; already answers whole-store questions.
  ;;
  ;; But it must NOT flip the status. Every unchecked exit here is a hole
  ;; someone already knows about and wrote down; failing on a standing,
  ;; documented hole would make full_check red forever, and a check that is
  ;; always red is a check people stop running. The finding earns its place by
  ;; being visible at the moment a whole-store green is about to be believed —
  ;; the same slot :host-stale occupies, and for the same reason.
  ;;
  ;; The example is `:http/foreign-route` and that is deliberate: it is the
  ;; crossing that is honest about being one, where the DECLARATION is the whole
  ;; check and nothing confirms the far side. It cannot be closed, so this test
  ;; cannot become a hostage to progress — which the previous example did,
  ;; when :webapp/client-routing stopped being a hole.
  (let [st (store/ingest (store/empty-store) 'app.api
                         "(ns app.api)
(defn ^{:http/external-path \"nginx serves the docs site\"} docs-link [] [:a {:href \"/docs/\"} \"docs\"])")]
    (testing "a store with an unchecked exit produces a finding to attach"
      (let [f (crossings/finding st)]
        (is (some? f))
        (is (= [:http/foreign-route] (map :kind (:unchecked f))))
        (is (string? (:note f)))))
    (testing "a store that crosses nothing produces NO finding — silence is correct here"
      ;; the opposite of the usual rule: this is a note about holes, and a
      ;; store with no holes has nothing to say. An empty section would be
      ;; noise on every full_check of every store forever.
      (is (nil? (crossings/finding
                 (store/ingest (store/empty-store) 'plain "(ns plain)\n(defn f [] 1)")))))))

(deftest ^:external
  ^{:correspondence "the :web/* markers slopp DEFINES vs the vocabulary crossings/unclassified-markers classifies — a key claimed by neither registry falls down the gap and both guards report clean about it"}
  the-two-marker-registries-COVER-the-vocabulary
  ;; Two registries describe slopp's markers: this one asks whether a key
  ;; carries data ACROSS the store's edge, `slopp.rules.markers` asks
  ;; whether a dial waives a rule and should say why. Splitting them is
  ;; correct — merging would report every escape dial as an unclassified
  ;; crossing — but a split needs something checking the seam.
  ;;
  ;; **The invariant is COVERAGE, not disjointness**, and the first version of
  ;; this test got that wrong. It asserted the two sets do not overlap and
  ;; immediately failed on `:generated`, which is genuinely BOTH: a
  ;; name-metadata dial slopp owns, and the signal `:generated/client` uses to
  ;; find the forms that crossed into generated code. A marker can have two
  ;; properties; requiring the registries not to overlap asserted something the
  ;; system does not have and should not.
  ;;
  ;; What actually rots is a key claimed by NEITHER — it falls down the gap and
  ;; both guards report clean about it, which is indistinguishable from clean.
  (let [st        (external/built-store)
        in-use    (markers/in-use st)
        crossing? (into (set (keys crossings/internal-markers))
                        (mapcat :markers) crossings/kinds)
        dial?     (into #{} (map :marker) markers/marker-registry)
        clojures  #{:private :dynamic :macro :tag :doc :arglists :added
                    :deprecated :const :inline :test :author :since :no-doc}
        orphans   (into #{} (remove #(or (crossing? %) (dial? %) (clojures %))) in-use)]
    (testing "there is a population"
      (is (< 20 (count in-use)) (pr-str in-use)))
    (testing "no marker slopp uses falls between the two registries"
      (is (= #{} orphans)
          (str "these keys are claimed by neither registry, so both report"
               " clean about them: " (pr-str orphans))))
    (testing "and the overlap is the ONE marker that really is both"
      ;; pinned rather than tolerated: a second overlap is a question worth
      ;; being asked, even though overlap per se is legal
      (is (= #{:generated} (set (filter dial? crossing?)))
          "a marker in both registries has two properties — fine, but say
           which one and why rather than letting the set grow quietly"))))
