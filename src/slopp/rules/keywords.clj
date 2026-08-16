(ns slopp.rules.keywords
  "The store's domain-keyword VOCABULARY, and the typo advisory built on it.

  Open maps are the guardrail slopp cannot otherwise offer: a misspelled
  namespaced key does not fail, it nil-puns, and an agent reading one slice
  cannot see that `:user/emial` was meant to be `:user/email`. Everything here
  exists to make that one failure visible.

  **The inventory is DERIVED, never stored.** `keyword-inventory` is a pure
  function of the forms, so it is correct by construction on every branch,
  after every merge, and at any past revision — the merge machinery reconciles
  FORMS, and f(forms) follows for free. That is why this namespace has no
  index to merge, no per-delta snapshot, and no invalidation logic; recompute
  when needed, and memoize behind a store-version key only if a profile ever
  calls for it.

  Two surfaces come off the inventory, and they are the write side and the
  read side of the same idea: `near-duplicate-keys` is the write-time advisory
  (a CHANGED form's NEW key sitting one Damerau edit from an ESTABLISHED one),
  and `vocabulary` is the browse that prevents the typo in the first place —
  established keys most-used first, so an agent REUSES `:user/email` instead
  of inventing a near-duplicate."
  (:require [rewrite-clj.node :as n]
            [slopp.store :as store] [clojure.string :as str]))

(defn form-keywords
  "The set of NAMESPACED domain keywords a form `node` uses. Unqualified keys
   (`:x`, `:limit`) are excluded as too noisy, and destructuring directives
   (`:keys`/`:as`/`:or`, incl. namespaced `:user/keys`) are excluded as not data.
   Reads the node's sexpr and walks it (metadata — where malli schemas live — is
   not traversed, so schema-internal keywords never pollute the vocabulary).
   Guarded: an unreadable node yields the empty set."
  [node]
  (let [specials #{"keys" "syms" "strs" "as" "or"}]
    (try
      (->> (n/sexpr node)
           (tree-seq coll? seq)
           (filter keyword?)
           (filter namespace)
           (remove #(specials (name %)))
           set)
      (catch Exception _ #{}))))

(defn keyword-inventory
  "The store's domain-keyword vocabulary as a DERIVED index: {namespaced-kw ->
   #{form-ids using it}}. A pure function of the forms — so under the CRDT /
   multi-branch / history model it is correct by construction on every branch,
   after every merge, and at any past revision (the merge machinery reconciles
   FORMS; f(forms) follows for free — no index-specific merge or per-delta
   snapshot). Recompute when needed; memoize behind a store-version key only if a
   profile ever calls for it."
  [store]
  (reduce
   (fn [acc [_ns {:keys [elements]}]]
     (reduce (fn [acc e]
               (if-let [fid (:id e)]
                 (reduce (fn [acc kw] (update acc kw (fnil conj #{}) fid))
                         acc (form-keywords (:node e)))
                 acc))
             acc elements))
   {} (:namespaces store)))

(defn edit-1?
  "True iff `a` and `b` are exactly ONE Damerau-Levenshtein edit apart: a single
   substitution, a single insertion/deletion, or a single ADJACENT TRANSPOSITION
   (`email`/`emial`) — transpositions are among the commonest keyword typos, so
   plain Levenshtein-1 (which scores them 2) would miss them."
  [a b]
  (let [a (str a) b (str b) la (count a) lb (count b)]
    (cond
      (= a b) false
      (= la lb)
      (let [diffs (keep-indexed (fn [i [x y]] (when (not= x y) i))
                                (map vector a b))]
        (or (= 1 (count diffs))
            (and (= 2 (count diffs))
                 (= (second diffs) (inc (first diffs)))
                 (= (nth a (first diffs)) (nth b (second diffs)))
                 (= (nth a (second diffs)) (nth b (first diffs))))))
      (= 1 (Math/abs (- la lb)))
      (let [[s l] (if (< la lb) [a b] [b a])]
        (loop [i 0 j 0 skips 0]
          (cond (> skips 1)        false
                (= i (count s))    (<= (- (count l) j) 1)
                (= (nth s i) (nth l j)) (recur (inc i) (inc j) skips)
                :else              (recur i (inc j) (inc skips)))))
      :else false)))

(defn near-duplicate-keys
  "Over the DERIVED keyword inventory, the likely-typo findings for this episode:
   a namespaced key introduced by a CHANGED form that (a) is used by no more
   than ONE form in the whole store, (b) has a name of length >= 4 (short names
   are noise), and (c) is exactly one Damerau edit from an ESTABLISHED
   same-namespace key (used by >= 2 unchanged forms). Returns
   [{:used :suggest :seen} …] — an ADVISORY (the open-map guardrail: a typo'd
   key silently nil-puns, the one failure a slice-limited agent can't see).
   Being derived, it needs no history or CRDT handling of its own.

   **A key used in more than one place is VOCABULARY, and that test counts every
   form rather than only the unchanged ones.** The candidate population used to
   be \"keys no unchanged form uses\", which conflates \"new in this episode\" with
   \"typed wrong\": an episode that reworks a subsystem touches every user of its
   keys, and each one then looks brand new. Measured on slopp's own store — a
   cli rework reported `:cli/commands`, the context key, as a typo of
   `:cli/command`, the declaration marker, with eleven users between them and
   both real since the capability shipped.

   It is the same derived-population failure the sweep exclusion names from the
   other side (\"a sweep in which every form is changed establishes nothing\"),
   and it lands as a false POSITIVE rather than a vacuous green. The narrowing
   is real and small: a slip repeated in two NEW forms now escapes. That is the
   right side to be wrong on, because a typo is by nature a one-off — you do not
   type the same misspelling twice — and an advisory that cries wolf on an
   author's own vocabulary is the one that gets dialled off."
  [store changed-fids]
  (let [changed     (set changed-fids)
        inv         (keyword-inventory store)
        established (into {} (keep (fn [[kw fids]]
                                     (let [n (count (remove changed fids))]
                                       (when (pos? n) [kw n]))))
                          inv)
        changed-kws (into #{} (mapcat (fn [fid]
                                        (when-let [e (store/form-by-id store fid)]
                                          (form-keywords (:node e))))
                                      changed))]
    (vec (for [k     changed-kws
               :when (and (< (count (get inv k)) 2)
                          (>= (count (name k)) 4))
               :let  [nbr (some (fn [[k2 c]]
                                  (when (and (= (namespace k) (namespace k2))
                                             (>= c 2)
                                             (edit-1? (name k) (name k2)))
                                    k2))
                                established)]
               :when nbr]
           {:used k :suggest nbr :seen (get established nbr)}))))

(defn ^:export vocabulary
  "The store's domain-keyword vocabulary, most-used first: `[{:kw :uses} …]`. The
   discoverability surface behind key hygiene — an agent browses the established
   vocabulary and REUSES `:user/email` instead of inventing a near-duplicate.
   Optional `ns-prefix` (string) keeps only keywords whose namespace equals it or
   is a dotted child (`user` → `:user/*` and `:user.address/*`). Derived from
   `keyword-inventory`."
  [store & {:keys [ns-prefix]}]
  (->> (keyword-inventory store)
       (keep (fn [[kw fids]]
               (when (or (nil? ns-prefix)
                         (let [ns (namespace kw)]
                           (or (= ns ns-prefix)
                               (str/starts-with? ns (str ns-prefix ".")))))
                 {:kw kw :uses (count fids)})))
       (sort-by (juxt (comp - :uses) (comp str :kw)))
       vec))
