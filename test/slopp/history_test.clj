(ns slopp.history-test
  "Roadmap #5 — the semantic × history depth surface: form-at-delta
  (time-travel), was-green-at, delta-log search, and form-history diffs.
  Queries over the journal slopp already records — the combination the
  roadmap calls 'the moat'."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops]
            [slopp.mcp]
            [slopp.ops.external :as external] [slopp.read.history :as history]))

(deftest ^:external form-history-is-reconstructible
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'h.core "(ns h.core)\n(defn f [x] x)\n(defn g [x] (f x))\n")
      (ops/edit-replace! sess 'h.core 'f "(defn f [x] (inc x))" :prompt "bump by one")
      (ops/edit-replace! sess 'h.core 'f "(defn f [x] (+ 2 x))" :prompt "bump by two")
      (testing "every content version of the form, oldest first, with intent"
        (let [h (history/query-form-history (ops/with-history sess) 'h.core 'f)]
          (is (= 3 (count h)))
          (is (= [:ingest :replace :replace] (mapv :op h)))
          (is (re-find #"\[x\] x" (:source (first h))))
          (is (= "bump by one" (:prompt (second h))))
          (is (re-find #"\+ 2 x" (:source (last h))))))
      (testing "the log reads as a filterable story"
        (let [hist (history/query-history (ops/with-history sess) :contains "bump by one")]
          (is (= 1 (count hist)))
          (is (= :replace (:op (first hist)))))
        (is (<= (count (history/query-history (ops/with-history sess) :limit 3)) 3)))
      (testing "done labels appear in the story"
        (external/done! sess :label "phase one done")
        (is (= :done
               (:op (first (history/query-history (ops/with-history sess) :contains "phase one"))))))
      (testing "lineage responses stay lean (no bulk sources)"
        (is (not-any? :sources (history/query-lineage (ops/with-history sess) 'h.core 'f))))
      (finally (ops/close! sess)))))

(def seed
  (str "(ns hi.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn f [x] (+ x 1))\n"
       "(defn ^:unused-ok g [x] (* x 2))\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

;; ---------------------------------------------------------------------------
;; HM1: form-at-delta (time-travel)
(deftest ^:external form-at-delta-travels-through-a-forms-versions
  (let [sess (external/open!)
        at   (fn [nm at] (history/query-form-at (ops/with-history sess) 'hi.core nm :at at))]
    (try
      (ops/ingest! sess 'hi.core seed)
      (let [v1 (external/commit-point! sess "v1: f adds one" :agent "a")]
        (ops/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 100))"
                           :prompt "bump to 100" :agent "a")
        (ops/edit-replace! sess 'hi.core 'f-t "(deftest f-t (is (= 101 (f 1))))"
                           :prompt "match" :agent "a")
        (let [v2 (external/commit-point! sess "v2: f adds 100" :agent "a")]
          (testing "a form renders as it stood at a past delta"
            (is (= "(defn f [x] (+ x 1))" (:source (at 'f (:target v1)))))
            (is (= "(defn f [x] (+ x 100))" (:source (at 'f (:target v2))))))
          (testing "a COMMIT id resolves to its target (time-travel to a milestone)"
            (is (= "(defn f [x] (+ x 1))" (:source (at 'f (:commit v1))))))
          (testing "the version carries the was-green-at status of that point"
            (is (= :green (:status (at 'f (:target v2))))))))
      (finally (ops/close! sess)))))

(deftest ^:external form-at-delta-edge-cases
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hi.core seed)
      (let [early (:head (:store @sess))]
        (ops/add-form! sess 'hi.core "(defn late [x] x)"
                       :prompt "added later" :agent "a")
        (testing "a form absent at that point is an honest error, not a guess"
          (is (:error (history/query-form-at (ops/with-history sess) 'hi.core 'late :at early))))
        (testing "an unknown delta is refused"
          (is (:error (history/query-form-at (ops/with-history sess) 'hi.core 'f :at "d99999"))))
        (testing ":at is required"
          (is (:error (history/query-form-at (ops/with-history sess) 'hi.core 'f)))))
      (finally (ops/close! sess)))))

(deftest ^:external form-at-delta-follows-renames
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hi.core seed)
      (let [before (:head (:store @sess))]
        (ops/rename! sess 'hi.core 'g 'doubler :prompt "clearer name" :agent "a")
        (testing "the OLD name resolves at a delta before the rename"
          (let [r (history/query-form-at (ops/with-history sess) 'hi.core 'g :at before)]
            (is (nil? (:error r)) (pr-str r))
            (is (= "(defn ^:unused-ok g [x] (* x 2))" (:source r)))))
        (testing "the NEW name resolves at the current head"
          (let [head (:head (:store @sess))
                r    (history/query-form-at (ops/with-history sess) 'hi.core 'doubler :at head)]
            (is (nil? (:error r)) (pr-str r))
            (is (str/includes? (:source r) "doubler")))))
      (finally (ops/close! sess)))))

;; ---------------------------------------------------------------------------
;; HM2: was-green-at
(deftest ^:external was-green-at-reads-the-verification-arc
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hi.core seed)
      (let [green-head (:head (:store @sess))]
        (testing "a delta after a passing verify is green, naming its verify delta"
          (let [r (history/query-status-at (ops/with-history sess) :at green-head)]
            (is (= :green (:status r)))
            (is (some? (:verify r)))))
        (testing "a commit id resolves to its target's status"
          (let [c (external/commit-point! sess "v1" :agent "a")]
            (is (= :green (:status (history/query-status-at (ops/with-history sess) :at (:commit c)))))))
        (testing "a deliberately red state reads red"
          (ops/edit-replace! sess 'hi.core 'f-t
                             "(deftest f-t (is (= 999 (f 1))))"
                             :prompt "break it" :agent "a")
          (let [red-head (:head (:store @sess))]
            (is (= :red (:status (history/query-status-at (ops/with-history sess) :at red-head))))))
        (testing "an unknown delta is refused"
          (is (:error (history/query-status-at (ops/with-history sess) :at "d99999")))))
      (finally (ops/close! sess)))))

(deftest ^:external form-history-versions-carry-was-green-at
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hi.core seed)
      (ops/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 2))"
                         :prompt "green change" :agent "a")
      (ops/edit-replace! sess 'hi.core 'f-t "(deftest f-t (is (= 999 (f 1))))"
                         :prompt "make it red" :agent "a")
      (testing "each version of a form is tagged with the state it landed in"
        (let [h (history/query-form-history (ops/with-history sess) 'hi.core 'f)]
          (is (every? #(contains? % :status) h))
          (is (= :green (:status (first h))))))
      (finally (ops/close! sess)))))

(deftest ^:external form-at-delta-rides-the-mcp-surface
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hi.core seed)
      (let [v1 (external/commit-point! sess "v1" :agent "a")
            _  (ops/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 100))"
                                  :prompt "bump" :agent "a")
            call (fn [args]
                   (get-in (slopp.mcp/handle!
                            sess {:id 1 :method "tools/call"
                                  :params {:name "query_history" :arguments args}})
                           [:result :content 0 :text]))]
        (is (str/includes? (call {:ns "hi.core" :name "f" :at (:commit v1)})
                           "(+ x 1)")))
      (finally (ops/close! sess)))))

;; ---------------------------------------------------------------------------
;; HM3: delta-log search ("which prompts touched X")
(deftest ^:external search-history-finds-prompts-intents-and-descriptions
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hi.core seed)
      (ops/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 3))"
                         :prompt "add auth bounds check" :agent "a")
      (ops/edit-replace! sess 'hi.core 'g "(defn g [x] (* x 3))"
                         :prompt "unrelated tweak" :agent "a")
      (testing "a prompt match returns the delta AND the forms it touched"
        (let [r (history/query-search-history (ops/with-history sess) "auth")]
          (is (= 1 (count r)))
          (is (= "add auth bounds check" (:prompt (first r))))
          (is (some #{'hi.core/f} (:forms (first r))))
          (is (some? (:at (first r))))))
      (testing "matching is case-insensitive"
        (is (= 1 (count (history/query-search-history (ops/with-history sess) "AUTH")))))
      (testing "a turn INTENT match catches deltas whose own prompt is silent"
        (ops/turn-begin! sess :agent "b" :intent "wire up the login flow")
        (ops/edit-replace! sess 'hi.core 'g "(defn g [x] (* x 5))"
                           :prompt "tweak again" :agent "b")
        (ops/turn-end! sess :agent "b")
        (let [r (history/query-search-history (ops/with-history sess) "login")]
          (is (seq r))
          (is (every? #(= "wire up the login flow" (:turn-intent %)) r))))
      (testing "a commit-point DESCRIPTION is searchable"
        ;; :force — the earlier edits left f-t red; we only care that the
        ;; :commit marker (with its description) lands and is searchable
        (external/commit-point! sess "auth milestone shipped" :agent "a" :force true)
        (is (some #(= "auth milestone shipped" (:description %))
                  (history/query-search-history (ops/with-history sess) "milestone"))))
      (testing "a blank pattern is refused; limit is respected"
        (is (:error (history/query-search-history (ops/with-history sess) "  ")))
        (is (<= (count (history/query-search-history (ops/with-history sess) "x" :limit 1)) 1)))
      (finally (ops/close! sess)))))

(deftest ^:external search-history-rides-the-mcp-surface
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hi.core seed)
      (ops/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 9))"
                         :prompt "harden auth path" :agent "a")
      (let [r (get-in (slopp.mcp/handle!
                       sess {:id 1 :method "tools/call"
                             :params {:name "query_history"
                                      :arguments {:contains "auth"}}})
                      [:result :content 0 :text])]
        (is (str/includes? r "harden auth path")))
      (finally (ops/close! sess)))))

;; ---------------------------------------------------------------------------
;; HM4: form-history diffs — one form's life as a diff story
(deftest ^:external form-history-renders-as-a-diff-timeline
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hi.core seed)
      (ops/edit-replace! sess 'hi.core 'f "(defn f [x] (+ x 2))"
                         :prompt "bump to two" :agent "a")
      (ops/edit-replace! sess 'hi.core 'f "(defn f [x] (- x 9))"
                         :prompt "now subtract" :agent "a")
      (testing "EDN rows now also carry a human :at"
        (is (every? :at (history/query-form-history (ops/with-history sess) 'hi.core 'f))))
      (testing "text format is a per-version LINE-diff story with intents"
        (let [txt (history/query-form-history (ops/with-history sess) 'hi.core 'f :format "text")]
          (is (str/includes? txt "form hi.core/f"))
          (is (str/includes? txt "bump to two"))
          (is (str/includes? txt "now subtract"))
          ;; the churn between versions shows as - / + lines
          (is (str/includes? txt "- (defn f [x] (+ x 2))"))
          (is (str/includes? txt "+ (defn f [x] (- x 9))"))))
      (testing "the same story rides the MCP surface via :format"
        (let [r (get-in (slopp.mcp/handle!
                         sess {:id 1 :method "tools/call"
                               :params {:name "query_history"
                                        :arguments {:ns "hi.core" :name "f"
                                                    :format "text"}}})
                        [:result :content 0 :text])]
          (is (str/includes? r "bump to two"))))
      (finally (ops/close! sess)))))

(deftest ^:external dead-ends-render-as-text-not-blank-delta-lines
  ;; query_history {dead_ends true, format text} ran the delta renderer over
  ;; dead-end rows, which carry :why/:forms/:undid and none of the delta keys,
  ;; so it printed blank lines. The why must survive.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'de.core "(ns de.core)\n\n(defn ^:unused-ok a [x] x)\n")
      (ops/add-form! sess 'de.core "(defn ^:unused-ok b [x] (inc x))"
                     :prompt "add b" :agent "u")
      (ops/undo! sess :prompt "the warm-pool idea did not pan out" :agent "u")
      (testing "the dead-end's why survives the text rendering"
        (let [txt (history/query-history (ops/with-history sess) :dead-ends true :format "text")]
          (is (string? txt) (pr-str txt))
          (is (re-find #"warm-pool idea did not pan out" txt) txt)
          (is (re-find #"(?i)dead" txt) txt)))
      (finally (ops/close! sess)))))

(deftest ^:external a-provenance-ask-is-one-report-call
  ;; s8 census: the provenance-shaped ask fanned out into report +
  ;; query_commits + query_history ×4 in BOTH cells — the story of a form
  ;; (why is it what it is) answered piecewise. When `contains` names a
  ;; topic, the report carries the most-storied matching forms' STORY
  ;; inline: version rows with the recorded ask, op, time and verification
  ;; state — the exact rows the fan-out was re-deriving. Ranked and capped
  ;; at 3, never withheld: a topic word matching many forms is the NORMAL
  ;; shape of the question (opus s9c: \"fuel\" matched fuel-t, half-fuel and
  ;; the asked-about form, and a ≤3 gate answered with nothing).
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'pv.core
                   (str "(ns pv.core)\n"
                        "(defn ^:unused-ok rate-cents [] 100)\n"
                        "(defn ^:unused-ok rate-cents-floor [] 1)\n"
                        "(defn ^:unused-ok rate-cents-ceil [] 9)\n"
                        "(defn ^:unused-ok rate-cents-doc [] :doc)\n"
                        "(defn ^:unused-ok rate-cents-x [] :x)\n"))
      (ops/edit-replace! sess 'pv.core 'rate-cents
                         "(defn ^:unused-ok rate-cents [] 250)"
                         :prompt "fuel spike: pass through the June contract uplift")
      (ops/edit-replace! sess 'pv.core 'rate-cents
                         "(defn ^:unused-ok rate-cents [] 210)"
                         :prompt "partial rollback after the carrier rebate landed")
      (let [r (ops/report sess :contains "rate-cents")]
        (is (seq (:story r)) (pr-str (keys r)))
        (is (<= (count (:story r)) 3)
            "many matches rank and cap — they never widen past three")
        (is (= 'pv.core/rate-cents (:form (first (:story r))))
            "the most-storied form ranks first — it is the one being asked about")
        (let [rows (:versions (first (:story r)))]
          (is (<= 2 (count rows)) (pr-str (:story r)))
          (is (some #(re-find #"June contract uplift" (str (:ask %))) rows))
          (is (some #(re-find #"carrier rebate" (str (:ask %))) rows))
          (is (not-any? :source rows)
              "the story is asks and states — code lives one call away")))
      (testing "the story does not depend on the line's recent deltas — imported
                or pre-`since` history is exactly when provenance is asked for"
        (let [head (:id (last (ops/journal sess)))
              r2   (ops/report sess :since head :contains "rate-cents")]
          (is (empty? (:changes r2)) (pr-str (:changes r2)))
          (is (seq (:story r2)) (pr-str (keys r2)))
          (is (some #(re-find #"June contract uplift" (str (:ask %)))
                    (:versions (first (:story r2)))))))
      (testing "a broad report carries no story — it is the narrow question's answer"
        (is (nil? (:story (ops/report sess)))))
      (finally (ops/close! sess)))))
