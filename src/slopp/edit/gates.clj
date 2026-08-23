(ns slopp.edit.gates
  "The write-gate CHASSIS (D9): the registry every write site consults, the
  dispatch, the severity dial, and the refusal entry point.

  A gate is a `(candidate ns-sym form-name)` → teaching-string-or-nil check run
  against the CANDIDATE store — the value the write WOULD produce — so a
  violation is refused before it lands rather than found afterwards. That is
  the constraint the gate families are shaped by: everything they ask must be
  answerable from a store VALUE, with no image and no eval.

  Register a new gate in `per-form-write-gates`, never at the N call sites;
  `gate-refusal` is the entry point, and `rule-severity` is where a store's
  own `rules` config can dial one down. The gates themselves live by FAMILY —
  `slopp.edit.modules` (edges, visibility, and what a module surface must
  declare), `slopp.edit.tiers` (purity and layering), and one namespace per APP
  TYPE: `slopp.edit.http`, `slopp.edit.cli`, `slopp.edit.rest`. This namespace
  knows them all and none of them knows it, which is the direction that lets a
  family be read without the mechanism.

  Split out because holding the mechanism inside one of the families is what
  made `slopp.edit.modules` a misnomer: 45 forms under a name that described
  a third of them."
  (:require [clojure.string :as str]
            [slopp.edit.modules :as edit.modules]
            [slopp.edit.tiers :as tiers]
            [slopp.edit.http :as edit.http]
            [slopp.store :as store]
            [slopp.store.render :as store.render] [slopp.project.capabilities :as capabilities] [slopp.edit.cli :as edit.cli] [slopp.edit.rest :as edit.rest] [slopp.edit.webapp :as edit.webapp]))

(defn ^:export rule-severity
  "The effective severity of rule `rule-key` for this store: a per-store OVERRIDE
   from the `rules` config file — `config_file {path \"rules\" key <rule> value
   <severity>}` — else `default`. `rule-key` is coerced via `name`, so a write
   gate's var name (`'schema-refusal`), a done-advisory `:key`, or a plain string
   all work. Severities: `:refuse`/`:error` (blocking), `:advisory` (surfaced,
   non-blocking), `:off` (skipped). The stored value is a string; a leading colon
   is tolerated (`\":off\"` == `\"off\"`) and an UNKNOWN value falls back to
   `default` — a mistyped severity must not silently mint a junk keyword that
   leaves the rule enabled-but-unrecognized. This is the dial that makes the
   hard-refuse program project-tunable; it rides the store `:config`, so it
   projects into git."
  [store rule-key default]
  (if-let [v (get-in store [:config "rules" :values (name rule-key)])]
    (let [k (keyword (str/replace (str v) #"^:+" ""))]
      (if (#{:off :advisory :error :refuse} k) k default))
    default))

(def ^:export per-form-write-gates
  "The ordered per-form WRITE gates (the rule-registry seed, D9): each is a
  (candidate ns-sym form-name) → teaching-string-or-nil check. Held as VARS
  (`#'`) so a hot-reload of a gate is picked up — a value vector would freeze
  the stale fns, the composed-def trap — and so the reference graph sees them.
  Register a new per-form write gate HERE, not at the N write sites. Each gate's
  per-store `rule-severity` (`:off` skips it) is consulted by `gate-refusal`.
  A gate implemented under a CAPABILITY's name (`slopp.edit.http`, …) is
  additionally inert until the store declares that capability — `gate-capability`
  derives which, and `gate-check` skips it. That guard used to be a
  `(when (web-enabled? candidate) …)` wrapper inside each of the nine http
  gates, which is a rule every gate author had to know and nothing reminded
  them of; forgetting it fired an HTTP rule on a project that never asked for
  HTTP."
  [#'edit.modules/module-refusal #'tiers/tier-refusal #'edit.modules/schema-refusal #'edit.modules/namespaced-keys-refusal #'edit.http/http-generated-ns
   #'edit.http/http-auth-refusal #'edit.rest/rest-path-partition #'edit.rest/rest-endpoint-schema #'edit.http/http-route-collision #'edit.webapp/webapp-page-unreachable #'edit.webapp/webapp-portable-handler #'edit.http/http-undeclared-effect #'edit.http/http-undeclared-context
   #'edit.http/http-unsafe-get #'edit.http/http-unknown-group #'edit.http/http-react-attrs
   #'edit.cli/cli-args-schema #'edit.cli/cli-command-collision #'edit.cli/cli-direct-stdio])

(defn ^:export write-gate-namespaces
  "`{rule-key defining-ns-sym}` for the registered per-form write gates — where
   each gate is IMPLEMENTED, which is what says who OWNS it (R6: support for an
   app TYPE lives in a namespace named for that type, so a gate defined in
   `slopp.edit.http` is the http app type's). The done-grain sibling reads the
   same fact off `rules/done-advisories`' `:check` vars.

   The rule KEY is the gate var's own name, which is why `write-gate-names` is
   this map's keys rather than a second walk of the registry."
  []
  (into {} (for [v per-form-write-gates
                 :let [m (meta v)]]
             [(keyword (:name m)) (ns-name (:ns m))])))

(defn ^:export write-gate-names
  "The keyword rule-names of the registered per-form write gates — the
   enumeration the unified rule catalog + its drift-guard use without reaching
   the package-private `per-form-write-gates`. The keys of
   `write-gate-namespaces`, so a gate can never appear in one and not the
   other."
  []
  (vec (keys (write-gate-namespaces))))

(defn ^:export rule-applies-to-platform?
  "Whether a rule scoped to `rule-scope` (:everywhere / :clojure / :clojurescript)
  fires for a form on `platform` (:jvm / :cljc / :cljs) — the platform axis of a
  rule's applicability (D-web-cljs, the sibling of the :production test-ns axis).
  :everywhere always fires. A :cljc form is checked by BOTH :clojure and
  :clojurescript rules because it compiles to both. :jvm satisfies :clojure;
  :cljs satisfies :clojurescript; an unknown scope defaults to firing."
  [rule-scope platform]
  (case rule-scope
    :clojure       (contains? #{:jvm :cljc} platform)
    :clojurescript (contains? #{:cljs :cljc} platform)
    true))

(defn ^:export write-gate-severities
  "`{rule-key declared-severity}` for every registered per-form write gate — the
   `:rule/severity` each gate declares in its own metadata, `:refuse` when it
   declares none. This is the DEFAULT `gate-check` passes to `rule-severity`, so
   a catalog or report built on it cannot drift from what is enforced (it did:
   the catalog carried a `:severity` column nothing read). The per-store dial
   still overrides it."
  []
  (into {} (map (fn [g] [(keyword (:name (meta g)))
                         (:rule/severity (meta g) :refuse)]))
        per-form-write-gates))

(defn ^:export gate-capability
  "The capability that owns write gate `gate` — the opt-in a store must declare
  before this gate runs at all — or nil for a gate that applies to every
  project.

  DERIVED from the implementing namespace's last segment: a gate defined in
  `slopp.edit.http` belongs to `http`, and there is no second field to keep in
  step with it. Same move as `capabilities/owners` reading a key's first
  segment, and as `a-rule-owned-by-an-app-type-is-named-for-it` reading a
  rule's; moving a gate is the only way to change who owns it.

  Only a capability with `:requires` qualifies — `slopp` and `app` are owners
  rather than opt-ins, so a namespace ending `.app` could not gate anything
  even if one existed.

  **The key this feeds is ASSEMBLED, not spelled.** `gate-check` asks
  `capabilities/enabled?` for the name returned here, which reads
  `<capability>.enabled` — so `cli.enabled`, `http.enabled`, `rest.enabled` and
  `webapp.enabled` are all consulted through this one call site and none of them
  appears as a literal anywhere. Worth saying because a scan for literal key
  spellings cannot see it, and because three of those four have no gates yet:
  `http` is the only capability whose rules exist today, so the others resolve
  here, consult a switch, and find nothing to arm. That is the
  declared-ahead-of-built state wave 1 leaves on purpose, not a dead registry
  row.

  **`^{:rule/capability :any}` opts a gate OUT**, and the case is narrow enough
  to name: a gate armed by a MARKER on the form rather than by the store's
  configuration. `http-generated-ns` refuses hand-edits to `^:generated`
  output, and a store holding generated code has generated code whether or not
  it currently serves HTTP — so deriving its gating from the namespace it
  happens to live in silenced it, and a generated form became editable. Found
  by `webdev.cljs-test/generate-client-writes-a-protected-cljs-namespace` after
  the derivation landed.

  The marker is deliberately explicit rather than inferred. There is no
  property of a gate that distinguishes marker-armed from config-armed —
  both are `(candidate ns-sym form-name)` fns in the same registry — so the
  author says which, once, where the gate is defined."
  [gate]
  (when-not (= :any (:rule/capability (meta gate)))
    (let [seg (last (str/split (str (ns-name (:ns (meta gate)))) #"\."))
          c   (capabilities/capability seg)]
      (when (:requires c) (:capability c)))))

(defn ^:export gate-check
  "Run every per-form write gate over the CANDIDATE store ONCE, bucketed by each
   gate's effective per-store `rule-severity`: returns `{:refuse <first
   refuse-grade teaching, or nil> :refusals [<every refuse-grade teaching>]
   :advisories [<advisory-grade teachings>]}`. A gate dialed `:off` is skipped;
   `:refuse`/`:error` (and the default) BLOCK; `:advisory` is non-blocking and
   its teaching rides the write result (the dial's warn-but-proceed mode).
   `gate-refusal` is the blocking view.

   Every gate is run even once one has refused, and `:refusals` keeps them ALL —
   two stacked requirements are both knowable from the first candidate, so
   teaching one per round-trip costs a resend to learn what was already in hand.

   A gate DECLARES its own default severity as `:rule/severity` metadata
   (`:refuse` when absent); the per-store dial overrides it. The default lives on
   the gate rather than at this call site so the rule catalog can report it
   instead of restating it — a hardcoded default here and a `:severity` column
   there is one fact stored twice, and they did disagree.

   A gate also declares WHERE it applies. `:rule/applies-to :production` skips
   TEST namespaces (declared once, here, so a gate and any REPORT of the same
   rule cannot disagree — they did: purity-standing excluded tests while
   tier-refusal gated them). `:rule/platform` (:everywhere default / :clojure /
   :clojurescript) skips forms whose platform the rule doesn't cover — a :cljs
   gate never fires on a :jvm form, and a :cljc form is checked by both worlds
   (D-web-cljs)."
  [candidate ns-sym form-name]
  (let [platform (store/platform-for candidate ns-sym)]
    (reduce (fn [acc gate]
              (let [sev   (rule-severity candidate (:name (meta gate))
                                         (:rule/severity (meta gate) :refuse))
                    skip? (or (and (= :production (:rule/applies-to (meta gate) :all))
                                   (store.render/test-ns? ns-sym))
                              (not (rule-applies-to-platform?
                                    (:rule/platform (meta gate) :everywhere)
                                    platform))
                              (when-let [c (gate-capability gate)]
                                (not (capabilities/enabled? candidate c))))]
                (if (or (= :off sev) skip?)
                  acc
                  (if-let [t (gate candidate ns-sym form-name)]
                    (if (= :advisory sev)
                      (update acc :advisories conj t)
                      (-> acc
                          (update :refusals conj t)
                          (cond-> (nil? (:refuse acc)) (assoc :refuse t))))
                    acc))))
            {:refuse nil :refusals [] :advisories []}
            per-form-write-gates)))

(defn ^:export gate-refusal
  "The BLOCKING view of `gate-check`: the refuse-grade per-form write-gate
   teachings over the CANDIDATE store as ONE message, or nil when none refuse. A
   gate dialed `:off` is skipped and an `:advisory` gate is non-blocking (its
   teaching rides `gate-check`'s `:advisories` onto the write result).

   When several gates refuse, the extras follow the first under `ALSO PENDING:`
   — all of them were knowable from the same candidate, so the caller can fix
   everything in one resend. A lone refusal reads exactly as it always did.
   Register a new per-form write gate in `per-form-write-gates`, not at the N
   write sites."
  [candidate ns-sym form-name]
  (let [[t & more] (:refusals (gate-check candidate ns-sym form-name))]
    (when t
      (if (seq more)
        (str t "\nALSO PENDING: " (str/join "\nALSO PENDING: " more))
        t))))
