(ns slopp.facet
  "Logic about a VALUE, declared in one place and enforced where data crosses the program's edge."
  (:require [malli.core :as m]
            [malli.error :as me] [clojure.string :as str]))

(def ^:private token
  "The capability that opens a holder. Only this namespace has it, so a holder's
  contents leave through `parse` and `reveal` and nowhere else."
  (Object.))

(defprotocol Opens
  "How this namespace reads a holder: with its private token, which no other code has."
  (-open [holder t] "The held value when `t` is this namespace's token; throws otherwise."))

(defn- closed
  "The refusal for opening a holder with the wrong token."
  []
  (ex-info "a facet holder opens only through slopp.facet/parse or slopp.facet/reveal"
           {:facet/holder :closed}))

(defn- unparsed
  "The refusal for reading a Raw value: says where it came from and what to call."
  [holder]
  (ex-info (str "this value came from outside the program (" holder ") and has not"
                " been parsed. Call (slopp.facet/parse #'YourFacet value) where it"
                " arrives, then read the parsed value.")
           {:facet/unparsed (str holder)}))

(deftype Raw [source ^:volatile-mutable held]
  Opens
  (-open [_ t] (if (identical? t token) held (throw (closed))))
  clojure.lang.ILookup
  (valAt [this _] (throw (unparsed this)))
  (valAt [this _ _] (throw (unparsed this)))
  clojure.lang.Seqable
  (seq [this] (throw (unparsed this)))
  clojure.lang.Associative
  (containsKey [this _] (throw (unparsed this)))
  (entryAt [this _] (throw (unparsed this)))
  (assoc [this _ _] (throw (unparsed this)))
  (count [this] (throw (unparsed this)))
  (cons [this _] (throw (unparsed this)))
  (empty [this] (throw (unparsed this)))
  (equiv [this o] (identical? this o))
  Object
  (toString [_] (str "#facet/raw " source)))

(deftype Secret [field ^:volatile-mutable held]
  Opens
  (-open [_ t] (if (identical? t token) held (throw (closed))))
  Object
  (toString [_] (str "#secret " (subs (str field) 1))))

(defmethod print-method Secret [s ^java.io.Writer w]
  (.write w (str s)))

(defn raw
  "Wrap data that arrived from outside the program. `source` names where it came
  from (`:db/orders`, `:http/body`) for the message a wrong read raises. Nothing
  but `parse` can read it."
  [data source]
  (->Raw source data))

(defn reveal
  "The value a secret field holds. Belongs only in a form that declares
  `^{:reveals \"why\"}`: the place the program hands a secret to the world."
  [secret]
  (if (instance? Secret secret)
    (-open secret token)
    (throw (ex-info "reveal takes the value of a secret field of a parsed value"
                    {:facet/not-a-secret (str (type secret))}))))

(defn- field-spec
  "A field is a malli schema, or `{:schema s :optional bool :secret bool}`."
  [v]
  (if (and (map? v) (contains? v :schema)) v {:schema v}))

(defn- schema-of
  "The closed malli map schema a declaration's fields describe. A field schema
  that is itself a facet declaration stands for that facet's fields."
  [decl]
  (letfn [(facet? [x] (and (map? x) (contains? x :facet/fields)))
          (nest [s] (cond (facet? s) (whole s) (vector? s) (mapv nest s) :else s))
          (whole [d] (into [:map {:closed true}]
                           (for [[k v] (:facet/fields d)
                                 :let [{:keys [schema optional]} (field-spec v)]]
                             (if optional [k {:optional true} (nest schema)] [k (nest schema)]))))]
    (whole decl)))

(defn- secret-keys
  "The fields a declaration marks `:secret`."
  [decl]
  (into #{} (keep (fn [[k v]] (when (:secret (field-spec v)) k))) (:facet/fields decl)))

(defn- declaration
  "The declaration a facet var holds now. A facet is taken by VAR so a changed
  copy of its declaration cannot stand in for it."
  [facet-var]
  (when-not (and (var? facet-var) (:facet (meta facet-var)))
    (throw (ex-info (str "a facet is passed by VAR, as (parse #'Order value), and "
                         (pr-str facet-var) " is not a var whose name carries ^{:facet \"...\"}")
                    {:facet/not-a-facet (pr-str facet-var)})))
  @facet-var)

(defn- version
  "The facet's version in this image: its declaration, plus the IDENTITY of
  each check and claim fn its vars hold now. Re-evaluating a check's body
  makes a new fn object, so a seal made under the old body stops fitting with
  no restart and no stamp in the source. A seal never leaves its image (the
  key is per image), so an in-image identity is all a version has to be."
  [decl]
  (hash [decl (mapv (fn [{f :fn}] (System/identityHashCode (if (var? f) @f f)))
                    (concat (vals (:facet/checks decl)) (vals (:facet/claims decl))))]))

(defn- canonical-text
  "One text for a value whatever order its maps and sets hold entries in, with
  each secret's real content, so the seal covers what the value IS."
  [x]
  (cond (instance? Secret x) (str "#secret " (canonical-text (-open x token)))
        (map? x)        (str "{" (str/join ", " (sort (for [[k v] x] (str (canonical-text k) " " (canonical-text v))))) "}")
        (set? x)        (str "#{" (str/join " " (sort (map canonical-text x))) "}")
        (sequential? x) (str "[" (str/join " " (map canonical-text x)) "]")
        :else           (pr-str x)))

(def ^:private seal-key
  "Random per image and never stored: a seal made in one image means nothing in
  another, and no code outside this namespace can make one."
  (let [k (byte-array 32)] (.nextBytes (java.security.SecureRandom.) k) k))

(defn- mac
  "The seal's MAC over a facet's name, its version and the value's content."
  ^bytes [facet-id v data]
  (let [m (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init m (javax.crypto.spec.SecretKeySpec. ^bytes seal-key "HmacSHA256"))
    (.doFinal m (.getBytes (str facet-id "\n" v "\n" (canonical-text data)) "UTF-8"))))

(defn- first-failure
  "Run the pure checks in written order and stop at the first that does not
  return `true`: `{:facet/failed {check {:doc :data}} :facet/blocked #{...}}`,
  or nil when all pass. Checks that declare `:sources` need the world and are
  not run here."
  [checks value]
  (let [pure (remove (comp :sources val) checks)]
    (reduce (fn [_ [k {:keys [doc] f :fn}]]
              (let [r (try (f value) (catch Exception e {:threw (.getName (class e))}))]
                (when-not (true? r)
                  (reduced {:facet/failed  {k {:doc doc :data r}}
                            :facet/blocked (into #{} (comp (map key) (drop-while #(not= k %)) (drop 1)) pure)}))))
            nil pure)))

(defn parse
  "Make `x` a value of the facet `facet-var` names, or say why not.

  `x` is a Raw from a port or a plain map. Its shape is checked against the
  facet's fields (closed: an undeclared key fails), then its pure checks run in
  written order and the first failure stops. Success returns the map with its
  secret fields held and a seal in its metadata; failure returns
  `{:facet/failed {check {:doc :data}} :facet/blocked #{checks not run}}`. A
  failure is a value, never a throw: the caller is a port that owes its sender
  an answer. Metadata on `x` is discarded; a seal counts only if made here."
  [facet-var x]
  (let [decl    (declaration facet-var)
        secrets (secret-keys decl)
        checks  (:facet/checks decl)
        opened  (if (instance? Raw x) (-open x token) x)
        data    (if (map? opened)
                  (reduce (fn [m k] (let [v (get m k)] (if (instance? Secret v) (assoc m k (-open v token)) m)))
                          (with-meta opened nil) secrets)
                  opened)
        schema  (schema-of decl)]
    (if-not (m/validate schema data)
      {:facet/failed  {:facet/shape {:doc  (str "the value has the fields " (symbol facet-var) " declares, and no others")
                                     :data (me/humanize (m/explain schema data))}}
       :facet/blocked (set (keys checks))}
      (let [held (reduce (fn [m k] (if (contains? m k) (update m k #(->Secret k %)) m)) data secrets)]
        (or (first-failure checks held)
            (let [id (symbol facet-var) v (version decl)]
              (with-meta held {:facet/seal {:facet id :version v :mac (mac id v held)}})))))))

(defn parsed?
  "True when `x` carries a seal this image made for this facet, at its current
  version, over exactly the content `x` has now."
  [facet-var x]
  (let [decl (declaration facet-var)
        id   (symbol facet-var)
        v    (version decl)
        s    (:facet/seal (meta x))]
    (boolean (and (map? x) s (= id (:facet s)) (= v (:version s))
                  (java.security.MessageDigest/isEqual ^bytes (:mac s) (mac id v x))))))

(defn project
  "What a reader of `view` may see of `x`, which is parsed again first: the core
  may have changed it. A failure keeps each check's `:doc` and drops its
  `:data`, because at an exit the reader is not the sender and the data may
  name values the view hides."
  [facet-var view x]
  (let [decl   (declaration facet-var)
        fields (get-in decl [:facet/views view])]
    (when-not fields
      (throw (ex-info (str (symbol facet-var) " declares no view " view "; its views are "
                           (vec (keys (:facet/views decl))))
                      {:facet/no-view view})))
    (let [r (if (parsed? facet-var x) x (parse facet-var x))]
      (if (:facet/failed r)
        (update r :facet/failed #(into {} (map (fn [[k f]] [k (select-keys f [:doc])])) %))
        (select-keys r fields)))))

(defn fields-schema
  "The closed malli schema the facet's fields describe: what `parse` validates
  against, what a port's request contract is, what a generator builds from."
  [facet-var]
  (schema-of (declaration facet-var)))
