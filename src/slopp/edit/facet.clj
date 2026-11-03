(ns slopp.edit.facet
  "The write gates that keep a facet declaration honest. A facet is a `def` whose name carries `^{:facet \"what this value is\"}` and whose value declares a value's fields, checks and views; `slopp.facet` enforces it at runtime, and these gates make sure what it enforces is what was declared: every ch…"
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.index.refs :as refs] [slopp.store.render :as store.render]))

(defn ^:export facet-sexpr
  "The form's sexpr when entry `e` defines a facet, `(def ^{:facet \"...\"} Name decl)`,
  else nil. The declaration is its last element."
  [e]
  (let [sx (try (n/sexpr (:node e)) (catch Exception _ nil))]
    (when (and (seq? sx) (= 'def (first sx)) (symbol? (second sx)) (:facet (meta (second sx))))
      sx)))

(defn- resolve-sym
  "`sym` as written in `ns-sym` as [ns name], through the namespace's
  `aliases`; an unqualified symbol is the namespace's own."
  [aliases ns-sym sym]
  (if-let [q (namespace sym)]
    [(get aliases (symbol q) (symbol q)) (symbol (name sym))]
    [ns-sym sym]))

(defn ^:export facets
  "Every facet `store` declares, as {[ns name] declaration-sexpr}. Found
  through the keyword index (forms that name `:facet/fields`), so a store with
  no facets pays one lookup."
  [store]
  (into {} (for [r (refs/keyword-refs store)
                 :when (#{:facet/fields} (:kw r))
                 :let [sx (some-> (store/form-by-id store (:from-form r)) facet-sexpr)]
                 :when sx]
             [[(:from-ns r) (symbol (name (second sx)))] (last sx)])))

(defn- declaration-problems
  "What is wrong with facet `nm`'s declaration `decl` in `ns-sym`, as strings.
  A check or claim is `{:doc \"...\" :fn #'var}` naming a form that exists;
  a view is a vector of declared field keys."
  [store aliases ns-sym nm decl]
  (let [fields (:facet/fields decl)]
    (if-not (and (map? decl) (map? fields))
      [(str "the value must be the declaration itself, a map literal with a :facet/fields map"
            " ({:facet/fields {:shop/id :int}}), so the store can read what the facet declares")]
      (concat
       (for [[group entries] (select-keys decl [:facet/checks :facet/claims])
             [k spec] entries
             :let [f (:fn spec)
                   p (cond
                       (not (map? spec)) (str k " in " group " must be {:doc \"...\" :fn #'the-check}")
                       (not (string? (:doc spec))) (str k " in " group " needs a :doc string: it is what a failure says")
                       (not (and (seq? f) (= 'var (first f)) (symbol? (second f))))
                       (str k "'s :fn must be a var, written #'the-check, so the facet always calls the check as it is now")
                       (not (apply store/form-named store (resolve-sym aliases ns-sym (second f))))
                       (str k "'s :fn #'" (second f) " names no form in the store"))]
             :when p]
         p)
       (for [[view ks] (:facet/views decl)
             k (if (vector? ks) ks [::not-a-vector])
             :when (not (contains? fields k))]
         (if (= k ::not-a-vector)
           (str "view " view " must be a vector of field keys")
           (str "view " view " names " k ", which is not a field of " nm)))))))

(defn- owner-clashes
  "Facets other than `ns-sym`/`nm` that already own one of `fields`' keys, as
  strings. A test namespace's fixture facets are compared only with facets in
  the same namespace: they do not ship, so they own nothing in the program."
  [store ns-sym nm fields]
  (let [test? store.render/test-ns?]
    (for [[[ons onm] odecl] (facets store)
          :when (not= [ons onm] [ns-sym nm])
          :when (or (= ons ns-sym) (not (or (test? ons) (test? ns-sym))))
          :let [shared (sort (filter (set (keys (:facet/fields odecl))) (keys fields)))]
          :when (seq shared)]
      (str (vec shared) " already belong to " ons "/" onm ": a key has ONE owner, so its"
           " rules live in one place. Nest that facet as a field, or name these keys differently."))))

(defn ^:export facet-declaration
  "Write gate: a facet's declaration names only what exists. Every check and
  claim is a var to a form in the store with a :doc, every view names declared
  fields, and no key already belongs to another facet. A facet is the contract
  its values are checked against, so one that names a missing check would fail
  at runtime, and one that shares a key with another would put that key's
  rules in two places.

  Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (let [ns-sym (symbol (str ns-sym))
        nm     (symbol (str form-name))]
    (when-let [sx (some-> (store/form-named candidate ns-sym nm) facet-sexpr)]
      (let [decl    (last sx)
            aliases (store/require-aliases candidate ns-sym)
            ps      (concat (declaration-problems candidate aliases ns-sym nm decl)
                            (when (map? (:facet/fields decl))
                              (owner-clashes candidate ns-sym nm (:facet/fields decl))))]
        (when (seq ps)
          (str ns-sym "/" nm " declares a facet, and the declaration does not hold:\n- "
               (str/join "\n- " ps)))))))

(defn- calls
  "Every list in sexpr `x` outside a quote: a quoted call is data."
  [x]
  (cond (and (seq? x) (= 'quote (first x))) []
        (seq? x) (cons x (mapcat calls x))
        (coll? x) (mapcat calls x)
        :else []))

(defn ^:export ^{:rule/applies-to :production} facet-by-var
  "Write gate: `slopp.facet/parse`, `project` and `parsed?` take a facet by
  VAR, `#'Order`, naming a def whose name carries `^{:facet ...}`. A computed
  or edited copy of a declaration, `(dissoc Order :facet/checks)`, would let a
  value through without the checks the facet declares. Test namespaces are
  exempt: a test hands parse a non-facet on purpose, to watch it refuse.

  Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (let [ns-sym (symbol (str ns-sym))]
    (when-not (= 'slopp.facet ns-sym)
      (when-let [e (store/form-named candidate ns-sym (symbol (str form-name)))]
        (let [sx      (try (n/sexpr (:node e)) (catch Exception _ nil))
              aliases (store/require-aliases candidate ns-sym)
              facet-call? (fn [h] (and (symbol? h) (namespace h)
                                       (= 'slopp.facet (get aliases (symbol (namespace h)) (symbol (namespace h))))
                                       (contains? #{"parse" "project" "parsed?"} (name h))))
              by-var? (fn [a] (and (seq? a) (= 'var (first a)) (symbol? (second a))
                                   (let [[n s] (resolve-sym aliases ns-sym (second a))]
                                     (some? (some-> (store/form-named candidate n s) facet-sexpr)))))
              bad     (first (for [c (calls sx) :when (facet-call? (first c)) :when (not (by-var? (second c)))] c))]
          (when bad
            (str ns-sym "/" form-name " calls (" (first bad) " " (pr-str (second bad)) " ...). parse,"
                 " project and parsed? take a facet by VAR: (facet/parse #'Order value), where"
                 " Order is a def whose name carries ^{:facet \"...\"}. A computed or edited copy"
                 " of a declaration would let a value through without the checks the facet declares.")))))))

(defn ^:export pure-checks
  "[[check-key qualified-var] ...] for each check in facet declaration `decl`
  (written in `ns-sym`) that declares no `:sources`, so must be pure."
  [store ns-sym decl]
  (let [aliases (store/require-aliases store ns-sym)]
    (for [[k spec] (:facet/checks decl)
          :let [f (when (map? spec) (:fn spec))]
          :when (and f (not (:sources spec)) (seq? f) (= 'var (first f)) (symbol? (second f)))
          :let [[n s] (resolve-sym aliases ns-sym (second f))]]
      [k (symbol (str n) (str s))])))
