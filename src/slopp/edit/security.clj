(ns slopp.edit.security
  "Write gates for the security half of the dialect (D3.2): the calls that make the unsafe thing the short thing in shipped code. Each refusal names the replacement, because feedback that names the specific weakness is the shape measured to work and a generic 'write secure code' measured nothing."
  (:require [slopp.store :as store]
            [rewrite-clj.node :as n]
            [clojure.string :as str] [slopp.store.render :as store.render]))

(defn- symbols-outside-quotes
  "Every symbol in sexpr `x` except those under a quote: a quoted name is data
  (`slopp.index.derive/external-leaves` names `clojure.java.shell/sh` in a
  quoted set), and to run a call you have to write it unquoted."
  [x]
  (cond
    (and (seq? x) (= 'quote (first x))) []
    (symbol? x) [x]
    (coll? x) (mapcat symbols-outside-quotes x)
    :else []))

(defn- hazard
  "`:process` when `sym` starts a process, `:deserialize` when it builds a
  Java object reader, else nil. `aliases` is the namespace's {alias lib} map,
  so `sh/sh` is seen as `clojure.java.shell/sh`; a bare `sh` counts when the
  namespace requires that lib at all, because `:refer` is not resolved there.
  `Runtime` itself is not a hazard — `.availableProcessors` and shutdown hooks
  are ordinary — only the `.exec` it carries is."
  [aliases sym]
  (let [nsp  (namespace sym)
        nm   (name sym)
        full (when nsp (get aliases (symbol nsp) (symbol nsp)))]
    (cond
      (= 'clojure.java.shell full) :process
      (and (nil? nsp) (= "sh" nm) (contains? aliases 'clojure.java.shell)) :process
      (and (nil? nsp) (= ".exec" nm)) :process
      (and (nil? nsp) (#{"ProcessBuilder." "java.lang.ProcessBuilder."} nm)) :process
      (#{"ProcessBuilder" "java.lang.ProcessBuilder"} nsp) :process
      (and (nil? nsp) (#{"ObjectInputStream." "java.io.ObjectInputStream."} nm)) :deserialize
      :else nil)))

(defn- hazards-of
  "[entry {hazard first-symbol}] for the form `ns-sym`/`form-name` in `store`,
  or nil when there is no such form. The ns form itself is skipped: requiring
  a lib calls nothing."
  [store ns-sym form-name]
  (when-let [e (store/form-named store (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [sx (try (n/sexpr (:node e)) (catch Exception _ nil))]
      (when (and (seq? sx) (not= 'ns (first sx)))
        (let [aliases (store/require-aliases store (symbol (str ns-sym)))
              found   (reduce (fn [m s]
                                (if-let [h (hazard aliases s)]
                                  (update m h #(or % s))
                                  m))
                              {}
                              (symbols-outside-quotes sx))]
          [e found])))))

(defn ^:export ^{:rule/applies-to :production} process-launch
  "Write gate: shipped code starts a process only in a form marked
  `^{:adapter \"process — which program, and how a test fakes it\"}`.

  Starting a process is a PORT, like the network: the command line should be
  built in one reviewed place that takes its arguments as data, and the rest
  of the code should reach it through a parameter a test can replace. The
  marker is the existing adapter marker; its first word names the port, which
  is how the HTTP rule already tells its own adapters from others. Test
  namespaces are exempt — they do not ship.

  Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when-let [[e found] (hazards-of candidate ns-sym form-name)]
    (when-let [hit (:process found)]
      (let [adapter (some-> (:adapter (store/form-name-meta e)) str str/triml)]
        (when-not (and adapter (str/starts-with? adapter "process"))
          (str ns-sym "/" form-name " starts a process (" hit "). In shipped"
               " code that call is a PORT: put it in ONE small form marked"
               " ^{:adapter \"process — which program, and how a test fakes it\"}"
               " that takes the program and its arguments as a VECTOR, and have"
               " the rest of the code call that form, or take it as a parameter so"
               " a test passes a fake. Build arguments as data, never as one shell"
               " string: (sh \"sh\" \"-c\" (str …)) is command injection."))))))

(defn ^:export ^{:rule/applies-to :production} java-deserialization
  "Write gate: shipped code does not read Java-serialized objects.

  `ObjectInputStream` runs code chosen by whoever wrote the bytes (CWE-502),
  and no marker can make that safe, so the adapter escape does not apply here;
  the per-store rules dial is the escape, where the choice is visible. Test
  namespaces are exempt. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when-let [[_ found] (hazards-of candidate ns-sym form-name)]
    (when-let [hit (:deserialize found)]
      (str ns-sym "/" form-name " reads Java-serialized objects (" hit ")."
           " Deserialization runs code chosen by whoever wrote the bytes, and no"
           " marker makes that safe. Read data as data: clojure.edn/read-string,"
           " or a JSON reader. If this store truly must accept it, dial"
           " :java-deserialization off in the rules config, where the choice is"
           " visible to every reader."))))

(defn ^:export security-scan
  "The import path's face of [[process-launch]] and [[java-deserialization]]:
  every form of `ns-sym` in `store`, one error string naming each offending
  form, or nil. A test namespace is not scanned, as the write gates skip it.
  The import path cannot run the per-form write gates, which fire on one
  candidate form, so it runs this — as it runs the dialect scan."
  [store ns-sym]
  (when-not (store.render/test-ns? ns-sym)
    (let [errs (keep (fn [e]
                       (when-let [nm (:name e)]
                         (or (process-launch store ns-sym nm)
                             (java-deserialization store ns-sym nm))))
                     (store/forms store ns-sym))]
      (when (seq errs)
        (str/join "\n" errs)))))
