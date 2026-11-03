(ns slopp.edit.facet
  "The write gates that keep a facet declaration honest. A facet is a `def` whose name carries `^{:facet \"what this value is\"}` and whose value declares a value's fields, checks and views; `slopp.facet` enforces it at runtime, and these gates make sure what it enforces is what was declared: every ch…"
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.index.refs :as refs] [slopp.store.render :as store.render] [slopp.edit.tiers :as tiers]))

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
              by-var? (fn [a] (if (symbol? a)
                        ;; a symbol naming no top-level form is a local or a
                        ;; parameter: parse refuses anything but a facet var at
                        ;; runtime, so a port holding a route's var may pass it on
                        (nil? (apply store/form-named candidate (resolve-sym aliases ns-sym a)))
                        (and (seq? a) (= 'var (first a)) (symbol? (second a))
                             (let [[n s] (resolve-sym aliases ns-sym (second a))]
                               (some? (some-> (store/form-named candidate n s) facet-sexpr))))))
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

(defn ^:export owners
  "{key [ns name]} for every field key a PRODUCTION facet owns. A test
  namespace's fixture facets own nothing in the program."
  [store]
  (into {} (for [[[n nm] decl] (facets store)
                 :when (not (store.render/test-ns? n))
                 k (keys (:facet/fields decl))]
             [k [n nm]])))

(defn- parsed-in
  "The facets, as [ns name], that sexpr `sx` (written in `ns-sym`) hands to
  `slopp.facet/parse` or `project` by var."
  [aliases ns-sym sx]
  (set (for [c (calls sx)
             :let [h (first c) a (second c)]
             :when (and (symbol? h) (namespace h)
                        (= 'slopp.facet (get aliases (symbol (namespace h)) (symbol (namespace h))))
                        (contains? #{"parse" "project"} (name h))
                        (seq? a) (= 'var (first a)) (symbol? (second a)))]
         (resolve-sym aliases ns-sym (second a)))))

(defn ^:export ^{:rule/applies-to :production :rule/severity :advisory} facet-key-ownership
  "Write gate (advisory): in the SHELL — a namespace with no declared purity
  tier — a key a facet owns is read, destructured or built only where the
  value was parsed: in a form that calls `parse`/`project` with that facet, in
  a route handler whose `:rest/request` is that facet (its port parsed the
  body), or in the facet's own namespace. Inside a declared tier a parsed
  value is plain data and the rule says nothing. `^:unsafe` does not
  discharge it: the way past is a parser, not a marker.

  Advisory until it has been measured on real stores. Returns a teaching
  string, or nil when clean."
  [candidate ns-sym form-name]
  (let [ns-sym (symbol (str ns-sym))
        nm     (symbol (str form-name))]
    (when (and (not= 'slopp.facet ns-sym) (= :external (tiers/tier-for candidate ns-sym)))
      (when-let [e (store/form-named candidate ns-sym nm)]
        (let [own (owners candidate)]
          (when (seq own)
            (let [sx      (try (n/sexpr (:node e)) (catch Exception _ nil))
                  aliases (store/require-aliases candidate ns-sym)
                  ok      (let [rr (:rest/request (store/form-name-meta e))
                                rr (if (and (seq? rr) (= 'var (first rr))) (second rr) rr)]
                            (cond-> (parsed-in aliases ns-sym sx)
                              (symbol? rr) (conj (resolve-sym aliases ns-sym rr))))
                  bad     (distinct (for [[k _] (when sx (refs/form-keyword-uses sx))
                                          :let [o (get own k)]
                                          :when (and o (not= ns-sym (first o)) (not (ok o)))]
                                      [k o]))]
              (when-let [[k [on onm]] (first bad)]
                (str ns-sym "/" nm " uses " k ", which belongs to the facet " on "/" onm
                     ", in a namespace with no declared purity tier (the shell). In the shell an"
                     " owned key is used only where the value was parsed: call (facet/parse #'"
                     on "/" onm " value) in this form, declare :rest/request " onm " on the"
                     " route, or move this logic into a namespace with a declared tier, where"
                     " parsed values are plain data."
                     (when (next bad) (str " Also: " (vec (distinct (map first (rest bad)))) ".")))))))))))

(defn- facet-calls
  "The names (strings) of `slopp.facet` fns that form `form-name` in `ns-sym` calls, through whatever alias the namespace gives it; a quoted call is data."
  [candidate ns-sym e]
  (let [aliases (store/require-aliases candidate ns-sym)
        sx      (try (n/sexpr (:node e)) (catch Exception _ nil))]
    (set (for [c (when sx (calls sx))
               :let [h (first c)]
               :when (and (symbol? h) (namespace h)
                          (= 'slopp.facet (get aliases (symbol (namespace h)) (symbol (namespace h)))))]
           (name h)))))

(defn ^:export ^{:rule/applies-to :production} facet-reveal
  "Write gate: a form that calls `slopp.facet/reveal` must carry
  `^{:reveals \"what it hands the secret to, and why that needs the real value\"}`
  on its name. A secret's real value leaves the program only through a form
  that declares it, so a reader can list every place it can escape. A write
  adapter that must persist a secret is such a form. The `slopp.facet`
  namespace itself is skipped. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (let [ns-sym (symbol (str ns-sym))
        nm     (symbol (str form-name))]
    (when (not= 'slopp.facet ns-sym)
      (when-let [e (store/form-named candidate ns-sym nm)]
        (when (contains? (facet-calls candidate ns-sym e) "reveal")
          (let [why (:reveals (store/form-name-meta e))]
            (when-not (and (string? why) (not (str/blank? why)))
              (str ns-sym "/" nm " calls facet/reveal, which hands out a secret's real value."
                   " The real value leaves the program only through a form that declares it:"
                   " mark this form ^{:reveals \"what it hands the secret to, and why that"
                   " needs the real value\"}. A write adapter that must persist a secret is"
                   " such a form."))))))))

(def inbound-data-ports
  "The first words of an `:adapter` marker that name an INBOUND data port: data from outside arrives through them. This set is the spike's and grows with use."
  #{"db" "jdbc" "sql" "file" "queue"})

(defn ^:export ^{:rule/applies-to :production :rule/severity :advisory} facet-adapter-raw
  "Write gate (advisory): when the store has a production facet, a form whose
  `:adapter` marker names an inbound data port (`inbound-data-ports`: db,
  jdbc, sql, file, queue — the spike's set, which grows with use) should hand
  its data out as `(facet/raw rows :db/orders)`, so nothing reads it until a
  facet parses it. `process` and `http` adapters are not inbound data ports
  here. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (let [ns-sym (symbol (str ns-sym))
        nm     (symbol (str form-name))]
    (when (and (not= 'slopp.facet ns-sym) (seq (owners candidate)))
      (when-let [e (store/form-named candidate ns-sym nm)]
        (let [adapter (some-> (:adapter (store/form-name-meta e)) str str/triml)
              port    (some-> adapter (str/split #"\s+") first)]
          (when (and (contains? inbound-data-ports port)
                     (not (contains? (facet-calls candidate ns-sym e) "raw")))
            (str ns-sym "/" nm " is a " port " adapter, so data from outside arrives here."
                 " Hand it out as (facet/raw rows :db/orders), so nothing reads it until a"
                 " facet parses it.")))))))

(defn ^:export schema-form
  "The closed malli schema facet declaration `decl` (written in `ns-sym`)
  describes, built as DATA from its source — what a generated client validates
  against, where the facet's own namespace may not ship. A field schema naming
  another facet stands for that facet's fields. With `view`, only that view's
  fields, and a secret field typed `:string`: at the exit it leaves as its
  redaction text."
  ([store ns-sym decl] (schema-form store ns-sym decl nil))
  ([store ns-sym decl view]
   (let [aliases (store/require-aliases store ns-sym)
         nest    (fn nest [s]
                   (cond (symbol? s)
                         (let [[n nm] (resolve-sym aliases ns-sym s)
                               sx     (some-> (store/form-named store n nm) facet-sexpr)]
                           (if sx (schema-form store n (last sx)) s))
                         (vector? s) (mapv nest s)
                         :else s))
         shown   (when view (set (get-in decl [:facet/views view])))]
     (into [:map {:closed true}]
           (for [[k v] (sort-by key (:facet/fields decl))
                 :when (or (nil? shown) (shown k))
                 :let [{:keys [schema optional secret]} (if (and (map? v) (contains? v :schema)) v {:schema v})
                       s (if (and view secret) :string (nest schema))]]
             (if optional [k {:optional true} s] [k s]))))))

(defn ^:export weakenings
  "How facet declaration `new` is weaker than `old`, as sentences: a check
  removed, a check moved out of `parse` by declaring `:sources`, a field
  dropped (its key loses its owner), a secret field made plain, a field made
  optional, a view that shows more. A new field or check is a stronger
  contract and says nothing."
  [old new]
  (let [spec (fn [v] (if (and (map? v) (contains? v :schema)) v {:schema v}))
        of   (:facet/fields old) nf (:facet/fields new)
        oc   (:facet/checks old) nc (:facet/checks new)]
    (vec (concat
          (for [k (sort (keys oc)) :when (not (contains? nc k))]
            (str "check " k " was removed"))
          (for [k (sort (keys oc))
                :when (and (contains? nc k) (not (:sources (get oc k))) (:sources (get nc k)))]
            (str "check " k " now declares :sources, so parse no longer runs it"))
          (for [k (sort (keys of)) :when (not (contains? nf k))]
            (str "field " k " was dropped, so the key has no owner"))
          (for [k (sort (keys of))
                :when (contains? nf k)
                :let [o (spec (get of k)) n (spec (get nf k))]
                s [(when (and (:secret o) (not (:secret n))) (str "field " k " is no longer secret"))
                   (when (and (not (:optional o)) (:optional n)) (str "field " k " became optional"))]
                :when s]
            s)
          (for [[v ks] (sort-by key (:facet/views old))
                :let [added (remove (set ks) (get-in new [:facet/views v]))]
                :when (seq added)]
            (str "view " v " now also shows " (vec added)))))))
