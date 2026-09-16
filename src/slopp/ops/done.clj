(ns slopp.ops.done
  "The done point's PURE half: what closing a unit of work would FIND, and
  what it would rewrite.

  The effectful loop — run the suite, try a removal, re-verify, record the
  boundary — lives in `slopp.api`. Everything here is a function of the store,
  which is what lets the done point's judgements be tested with plain maps
  instead of a JVM apiece.

  Three things it answers:

  - **Normalization.** Which changed forms the conservative,
    behavior-preserving rewriter would actually touch (`normalize-rewrites`
    reports; nothing is committed), and the commit of those as one
    `:normalize` changeset (`apply-normalization!`, which throws rather than
    returning data — a normalization that will not compile is an invariant
    violation, and recording a boundary over code the image never accepted
    would be worse than failing).
  - **Unused requires.** Kondo's candidates, minus the ones marked
    `^:side-effect`. That marker is an EMPIRICAL escape, not a hint: it means
    removing the require was tried and BROKE verification, so the require is
    load-bearing in a way the reference graph cannot see. Such a require is
    kept, never re-tried, and never reported as unused.
  - **Lint, as ANCHORS rather than coordinates.** Each finding carries its
    owning form and a match-ready `:at` snippet, and `:row`/`:col` are
    dropped. A row number is meaningless to a form-addressed agent and stale
    the moment anything above it shifts; a form plus a snippet stays true and
    is already what the edit tools take.

  Lint here is EPISODE-scoped on purpose. Re-judging code this episode never
  touched is `full_check`'s job — done names it rather than doing it unasked."
  (:require [clojure.string :as str]
            [slopp.ops.engine :as engine]
            [slopp.index :as index]
            [slopp.index.normalize :as normalize]
            [slopp.store.render :as store.render]
            [slopp.store :as store] [rewrite-clj.node :as n] [slopp.read.history :as history]))

(defn normalize-rewrites "Which of the episode's `changed` form ids the normalizer would actually
  rewrite, as `[{:form-id :form :node :applied}]` — pure, nothing committed.
  `:applied` names the conservative behavior-preserving rewrites found; forms
  with none are omitted, so an empty result means there is nothing to do."
  [changed st]
  (vec (for [fid changed
                            :let [e (store/form-by-id st fid)
                                  {:keys [node applied]} (normalize/normalize-form (:node e))]
                            :when (seq applied)]
                        {:form-id fid
                         :form    (symbol (str (store/ns-of-form-id st fid))
                                          (str (or (:name e) (:id e))))
                         :node    node
                         :applied applied})))

(defn with-unused-gate "Fold the unused-public report into `lint` as ERROR-grade rows — dead public
  surface (`:unused-public`) and stale `^:unused-ok` markers on vars that ARE
  called now (`:stale-unused-ok`). Both directions gate, so the marker can
  never drift from the truth in either direction. Pure."
  [lint unused-rep]
  (into lint
                   (concat
                    (for [q (:unused unused-rep)]
                      {:level :error :type :unused-public
                       :ns (symbol (namespace q)) :form q
                       :message (str q " is public but NOTHING in the store"
                                     " calls it — delete it, or mark the name"
                                     " ^:unused-ok to declare it deliberate"
                                     " (external surface, runtime-resolved"
                                     " entry)")})
                    (for [q (:stale unused-rep)]
                      {:level :error :type :stale-unused-ok
                       :ns (symbol (namespace q)) :form q
                       :message (str q " carries ^:unused-ok but IS called now"
                                     " — remove the flag")}))))

(defn apply-normalization! "Commit `rewrites` as one `:normalize` changeset: hot-load the rewritten
  forms, then rebase-commit. Throws rather than returning data, deliberately —
  a normalization that will not compile, or a store that moved underneath the
  done-point, are both invariant violations rather than expected outcomes, and
  continuing past either would record a boundary over code the image never
  accepted."
  [rewrites st label agent session]
  (when (seq rewrites)
                   (let [changeset   (into {} (map (juxt :form-id :node)) rewrites)
                         main-ns     (store/ns-of-form-id st (:form-id (first rewrites)))
                         [st' _]     (store/apply-changeset st :normalize main-ns changeset
                                                            :prompt (or label "done normalization")
                                                            :agent agent)
                         touched     (distinct (map #(store/ns-of-form-id st' %) (keys changeset)))]
                     (when-let [err (:err (engine/hot-load-all! session st' (keys changeset)))]
                       (throw (ex-info (str "normalization failed to compile: " err) {})))
                     (when-not (engine/try-commit! session st st' (vec touched))
                       (throw (ex-info "store changed during done — retry" {}))))))

(defn- require-specs
  "Every require spec (symbol or vector) in an ns form's sexpr."
  [ns-form]
  (for [c ns-form
        :when (and (seq? c) (= :require (first c)))
        spec (rest c)]
    spec))

(defn side-effect-required?
  "True when `ns-sym`'s require of `lib` carries the `^:side-effect` marker —
   a require the done-point kept because removing it broke verification (a
   load-bearing registration the reference graph can't see). Such a require is
   deliberately present, so it is NOT reported as unused and NOT re-tried."
  [st ns-sym lib]
  (boolean
   (when-let [e (store/form-named st ns-sym ns-sym)]
     (some (fn [spec]
             (let [l (if (vector? spec) (first spec) spec)]
               (and (= l lib) (:side-effect (meta spec)))))
           (require-specs (n/sexpr (:node e)))))))

(defn unused-requires
  "The requires of `ns-sym` that kondo reports unused and that are not already
   marked `^:side-effect` — the done-point's prune candidates. Each entry is
   `{:lib sym :marked \"^:side-effect …\"}`; `:marked` is the re-add form used
   if the empirical removal turns out to break verification. Pure over the
   store — the effectful try-remove-verify loop lives in `slopp.api`."
  [st ns-sym]
  (when-let [e (store/form-named st ns-sym ns-sym)]
    (let [flagged (into #{}
                        (keep (fn [f]
                                (when (= :unused-namespace (:type f))
                                  (some-> (re-find #"namespace (\S+) is required"
                                                   (:message f))
                                          second symbol)))
                              (index/lint (store.render/render-ns st ns-sym)
                                          (store/kondo-lang st ns-sym))))]
      (vec (for [spec (require-specs (n/sexpr (:node e)))
                 :let [lib (if (vector? spec) (first spec) spec)]
                 :when (and (symbol? lib)
                            (contains? flagged lib)
                            (not (:side-effect (meta spec))))]
             {:lib    lib
              :marked (str "^:side-effect "
                           (pr-str (if (vector? spec) spec [spec])))})))))

(defn marked-unused?
  "True when kondo finding `f` is an `:unused-namespace` for a require that
   carries the `^:side-effect` keep-marker — a require the done-point kept
   because removing it breaks a cold load. It is deliberately present, so the
   finding is suppressed: a kept require must not read as unused."
  [st ns-sym f]
  (boolean
   (and (= :unused-namespace (:type f))
        (when-let [lib (some-> (re-find #"namespace (\S+) is required" (:message f))
                               second symbol)]
          (side-effect-required? st ns-sym lib)))))

(defn anchored-lint
  "Kondo findings for every namespace the EPISODE TOUCHED — `nses`, as
  [[touched-namespaces]] derives them — expressed as ANCHORS rather than
  coordinates: each row carries the owning `:form` and an `:at` snippet of
  the offending line, and `:row`/`:col` are dropped.

  Episode-scoped on purpose. A store-wide scan at every done point re-judges
  code this episode never touched, which is `full_check`'s job — done reminds
  the agent it exists rather than doing it unasked.

  Coordinates never cross the wire because they are meaningless to a
  form-addressed agent — and stale the moment anything above them shifts. A
  form plus a match-ready snippet stays true and is what the edit tools take."
  [session nses]
  (vec (for [ns-sym (distinct nses)
             :let [st*   (:store @session)
                   src   (store.render/render-ns st* ns-sym)
                   lines (vec (str/split-lines src))]
             :when (contains? (:namespaces st*) ns-sym)
             f (index/lint src (store/kondo-lang st* ns-sym))
             :when (not (marked-unused? st* ns-sym f))]
         ;; anchors, not coordinates: the owning form + a match-ready
         ;; snippet; row/col never cross the wire
         (cond-> (-> f
                     (dissoc :row :col)
                     (assoc :ns ns-sym
                            :form (when-let [e (store.render/owner-form
                                                st* ns-sym
                                                (:row f) (:col f))]
                                    (symbol (str ns-sym)
                                            (str (or (:name e) (:id e)))))))
           (get lines (dec (:row f 0)))
           (assoc :at (str/trim (nth lines (dec (:row f)))))))))

(defn ^:export landed-gap
  "Which `expected` forms — `#{[ns-sym \"name\"]}`, the live ones this episode's
  verdict covered — are NOT in `by-ns`, the branch's own elements. Sorted, and
  empty when the branch has them all.

  A verdict is earned against the THREAD image, which holds the whole episode,
  and the work then LANDS through a rebase that mints new form ids. If
  anything drops between those two moments the green is honest and wrong:
  measured with two forms, where one landed and the other did not, after which
  every request served 200 while the commit-point read green.

  `by-ns` must be read from the BRANCH rather than from the session that did
  the work. Checking a landing against the store that produced it is the same
  reader answering twice, which is exactly the mistake this exists to catch.

  Matched by NAME, not by source bytes, and that is deliberate on both sides.
  The measured failure is a form that did not arrive AT ALL, which a name
  catches; and a rebase legitimately re-mints ids and can reorder a namespace,
  so byte or id equality would report differences that are not losses. An
  element with no `:name` — an ns form, a bare comment — can match nothing,
  or any namespace would vouch for any form in it."
  [expected by-ns]
  (let [present (into #{}
                      (for [[ns-sym m] by-ns
                            e          (:elements m)
                            :when      (:name e)]
                        [ns-sym (str (:name e))]))]
    (vec (sort (remove present expected)))))

(defn ^:export declared-edge-gap
  "Which `declared` module edges — `[{:from :to :test-only}]`, the ones this
  episode added — are absent from the branch's manifests. Empty when the
  branch has them all.

  The twin of [[landed-gap]], for the same reason and against the same
  hazard: a verdict is earned against a THREAD and the work then LANDS, so
  \"green\" and \"on the branch\" are two facts. `landed-gap` joins them for
  FORMS. A `module_dep` is not a form — it is a `:module-edge` delta folded
  into the manifest — so it carried exactly that hazard and sat outside the
  check by construction.

  Measured: three edges were declared and landed, and hours later were gone
  from the trunk while still present in the declaring session's store. That
  asymmetry is what makes it expensive. The declaring agent's `full_check`
  reads its own session and stays GREEN; another agent's reads the trunk and
  goes red on edges belonging to somebody who cannot see the loss — and
  `commit_point` gates on the whole-store verdict, so the second agent is
  blocked by a fact the first one's tools deny.

  `production` and `test` are separate manifests and each edge is checked
  against its own. They are distinct fields on purpose — a fixture require
  must not open the production graph — so checking one against the other
  would report every test-only declaration as lost."
  [declared production test]
  (vec (remove (fn [{:keys [from to test-only]}]
                 (contains? (get (if test-only test production) from) to))
               declared)))

(defn touched-namespaces
  "The namespaces this episode TOUCHED, derived from the deltas rather than
  from the post-state: the namespace of every form in `changed` (ids the
  store still holds), plus the `:ns` of every `:delete` delta the episode's
  agents appended. A deleted form has no namespace in the store any more,
  so a set derived from `ns-of-form-id` alone was EMPTY for a delete-only
  episode — no lint, no dead-surface scan, a done that judged nothing — and
  the red the deletion had just fixed stood for the commit point."
  [session st agent changed]
  (let [agents (engine/episode-agents session agent)]
    (vec (distinct
          (concat (keep #(store/ns-of-form-id st %) changed)
                  (for [d (history/episode-span st agent)
                        :when (and (= :delete (:op d))
                                   (contains? agents (:agent d))
                                   (symbol? (:ns d)))]
                    (:ns d)))))))
