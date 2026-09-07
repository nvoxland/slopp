(ns slopp.project.capabilities
  "The capability REGISTRY and the readers for it — what a store may declare
  about itself, and what those declarations currently say.

  Capabilities are the store's own configuration surface (`http.enabled`,
  `http.port`, the `http.auth.*` and `http.static.*` families): typed,
  defaulted, documented in one table, and written only through a gate that
  validates against it. `config_file` refuses an unregistered key, which is
  what keeps this a vocabulary rather than a bag.

  **A key's FIRST SEGMENT names its owner** — `owners` is that vocabulary,
  and it is how this registry stops being one app type's settings under
  generic names (R1/R6).

  **This namespace is the only place that knows where values live.**
  `effective` parses per the registry type and falls back to the default so a
  registered key never nil-puns; `stored?` answers the question `effective`
  deliberately erases, whether a value was actually SET. Both are exported
  for the same reason: a consumer reaching into
  `[:config \"capabilities\" :values]` itself would be a second place that
  knows the shape, and would skip the parsing and the defaults on the way."
  (:require [clojure.string :as str]))

(def ^:export capability-catalog
  "Every CAPABILITY a store may declare, and what each one requires beneath it.

  A capability is a feature an application opts into — and, because it is an
  opt-in, the thing that decides whether a whole family of settings, write
  gates and framework code applies at all. `owners` is DERIVED from this table,
  so the vocabulary of key prefixes cannot drift from the list of features.

  **What a capability IS, stated here because this is where one gets added.**
  Five things, and a row that cannot supply all five probably wants to be a
  setting instead:

  - a PORT — the abstraction an application writes its own code against;
  - an ADAPTER — the real IO behind that port, which is slopp's code, tested
    against real sockets and streams rather than the consumer's;
  - a FAKE — shipped beside the port, so a consumer's tests need no socket, no
    browser and no subprocess, and stay in the fast in-image tier;
  - GATES — which refuse reaching AROUND the port, since a fake nobody has to
    use buys nothing;
  - a SURFACE REPORT — one derivation with three readers: the agent, a
    consuming tool, and the human, who does not read the code and needs a
    rendered picture of what the application is.

  `html` is deliberately absent, and it is the useful negative example: hiccup
  and garden are pure functions that throw on unsafe input, so there is no IO
  to own and nothing to fake. They ship always-available, the way `slopp.lang`
  does. A capability exists where there is IO to take away from the consumer.

  **`:requires` is NOT a chain, and the first draft was one.** `rest` and
  `webapp` both require `http` and neither requires the other; `cli` is nobody's
  parent. An edge exists only where one capability genuinely cannot function
  without another — `webapp` has to be SERVED, `rest` has to be served — and
  not where two are merely usually used together.

  The reason to be strict is mechanical rather than tidy: `:requires` drives
  what an enable turns on AND what a DISABLE is refused for. Under the first
  draft's chain, `rest.enabled false` would have been refused on any store with
  `webapp` on, including one whose browser app talks to an API slopp does not
  serve — a relationship that is not real becoming a refusal that is, met by
  someone who has done nothing wrong. Likewise `cli`: a `-main` is a PACKAGING
  fact, and an embedded or library-hosted server would have carried argv
  parsing it never uses.

  A capability off to the side (a database) is an ordinary row with its own
  `:requires`, which is why `prerequisites` walks the graph rather than
  assuming an order.

  `slopp` and `app` are owners rather than capabilities: there is no switch to
  throw. `:reserved` means an application can never own a key under it;
  `:always-on` means every project has it whatever kind of application it is.
  They carry no `:requires` at all rather than an empty one, so a caller asking
  what a non-capability requires gets nil rather than a plausible answer."
  [{:capability "slopp" :reserved true
    :doc "slopp itself — RESERVED, a project's app can never own a key here"}
   {:capability "app" :always-on true
    :doc "any project, whatever kind of application it is"}
   {:capability "cli" :requires []
    :ns-prefix "slopp.cli" :entry-markers [:cli/command]
    :doc "a command-line shell: argument parsing, an injected stdin/stdout/stderr, and exit codes. Without it an app's main runs with no argument or stream support at all"}
   {:capability "http" :requires []
    :ns-prefix "slopp.http" :entry-markers [:app/entry :http/path]
    :doc "an HTTP server: routing, static mounts, identity and authorization. Present in every store, inert until http.enabled"}
   {:capability "rest" :requires ["http"]
    :ns-prefix "slopp.rest" :entry-markers [:rest/path :rest/request :rest/response]
    :doc "a typed API: request/response contracts, boundary validation, and generated clients derived from the same schemas"}
   {:capability "webapp" :requires ["http"]
    :ns-prefix "slopp.webapp" :entry-markers [:webapp/path :webapp/shell :webapp/client-routes]
    :doc "an application whose BROWSER owns routing and state: client-side routes, event dispatch, and the ClojureScript build. Needs serving, so it requires http — but NOT rest: a browser app may talk to a third-party API, a socket, or to no server data at all. Not the same as serving HTML, which needs only http"}])

(defn ^:export capability
  "The catalog row for `c`, or nil when nothing declares it.

  Exported for the same reason `effective` is: a caller filtering
  `capability-catalog` itself would be a second place that knows the row shape,
  and nil-vs-row is the answer every consumer actually wants — an unknown
  capability must be distinguishable from one that requires nothing."
  [c]
  (let [c (str c)]
    (some #(when (= (:capability %) c) %) capability-catalog)))

(defn ^:export prerequisites
  "Every capability `c` needs beneath it, transitively — a SET, not an order.

  What one `<c>.enabled true` write turns on with it. Walked rather than read
  off the declared chain, because the chain is a fact about today's four rows:
  a capability added off to the side (a database, needing only `app`) has to
  work here without this function learning about it.

  Cycle-safe by construction — `seen` accumulates and the frontier only ever
  gains rows it has not already absorbed, so a row that required something
  requiring it back terminates rather than recurring forever. Nothing should
  ever declare that graph, and
  `the-catalog-declares-every-capability-and-its-prerequisites` says so; this
  still must not hang while proving it."
  [c]
  (loop [frontier [(str c)] seen #{}]
    (if-let [x (first frontier)]
      (let [needs (remove seen (:requires (capability x) []))]
        (recur (into (vec (rest frontier)) needs) (into seen needs)))
      seen)))

(defn ^:export dependents
  "Every capability that needs `c`, transitively — the mirror of
  [[prerequisites]].

  What a `<c>.enabled false` write has to refuse for: turning off the thing
  something else is standing on leaves a store whose gates and framework
  disagree with its own config.

  Derived by asking each row rather than by storing an inverted graph, which
  would be the same drift `owners` used to have — one relation, read two ways,
  never written down twice."
  [c]
  (let [c (str c)]
    (into #{} (comp (map :capability)
                    (filter #(contains? (prerequisites %) c)))
          capability-catalog)))

(def ^:export owners
  "Who a capability key belongs to, keyed by its FIRST SEGMENT — DERIVED from
  `capability-catalog`, never written down beside it.

  **R1, generalized, and it is the whole of R6's answer for this registry.**
  A capability key's first segment names its owner, so the name carries the
  fact and no second field can drift from it. `slopp` and `app` are owners
  without being opt-ins; the rest are the features a store declares.

  **Why a vocabulary and not a convention.** The registry was 74% one app type
  under names that did not say so — `auth.*` and `groups.*` read as generic
  project settings while every reader of them was `slopp.http.auth` or a `web-`
  write gate. R6 says support for an app TYPE lives under that type's name and
  the pattern must be replicable for type #2 without renaming type #1. That
  only holds if a key OUTSIDE the declared owners is refused, which is what
  `every-capability-key-declares-its-owner` pins: capability #2 adds a catalog
  row and its keys under that segment, and nothing it declares can land in the
  generic pool by accident.

  **This used to be typed out, and that was one source of truth too many.**
  Three strings sat here beside a nineteen-row registry, joined to the features
  they named by nothing but somebody keeping them in step — the same
  registry-and-consumer shape whose failures fill this codebase's decision log.
  Deriving it means adding a capability cannot leave its owner segment
  undeclared, because there is no second list to forget.

  The docs are the reader's, not decoration — `report` groups by owner, so a
  store that never enables HTTP sees one named feature it has not turned on
  rather than fourteen unrelated settings it could set."
  (into {} (map (juxt :capability :doc)) capability-catalog))

(def ^:export registry
  "The capability registry: one entry per `capabilities` config key —
  `{:key :type :default :doc}`. THE single source the validator
  (`check-value`), the effective-value read (`effective`), and
  `query_capabilities` all derive from, the same declare-once shape as
  `slopp.rules.catalog/rule-catalog`.

  **A key's FIRST SEGMENT names the CAPABILITY that owns it**, and the
  vocabulary is `owners`, itself derived from `capability-catalog` — so read
  the catalog before adding a key, and add the capability there first if the
  segment is new.

  `:type` is a small STRUCTURAL vocabulary (`:string` `:boolean` `:int`
  `:enum` `:set-of` `:qualified-symbol` `:csv`) interpreted by plain
  Clojure, not malli — this ns loads in the server/boot JVM, which runs on
  kernel deps only (the two-process split; the `schema-refusal` precedent).
  A `*` in a key is a pattern: trailing `*` matches one-or-more remaining
  segments (`http.auth.static.*`), a mid `*` exactly one
  (`http.auth.groups.*.members`). Defaults are chosen so `http.enabled = true`
  alone yields a working, localhost-bound, deny-by-default server."
  [{:key "app.name" :type [:string] :default nil
    :doc "Application name. Unset = the store directory name at build time."}
   
   {:key "app.main" :type [:qualified-symbol] :default nil
    :doc "The entry fn (app.core/-main). Unset = build's :main arg required."}

   {:key "cli.enabled" :type [:boolean] :default false
    :doc "Whether this project has a command-line shell. Without it an app's main runs with no argument parsing, no injected streams and no exit codes — which is what a bare -m gives you."}

   {:key "http.enabled" :type [:boolean] :default false
    :doc "Whether this project serves HTTP. The master opt-in: http rules and query_surface exist only when true. Requires nothing — how a server is launched is packaging, not a dependency."}
   {:key "http.adapter" :type [:enum "http-kit" "jdk"] :default :http-kit
    :doc "Server adapter. http-kit is the production default; jdk (com.sun.net.httpserver) is the zero-dep fallback."}
   {:key "http.host" :type [:string] :default "127.0.0.1"
    :doc "Bind address. Localhost by default; widen deliberately."}
   {:key "http.port" :type [:int {:min 1 :max 65535}] :default nil
    :doc "Port the app's HTTP server binds. Unset = 8080 in production (slopp.http/serve! defaults it, so declaring 8080 here would only resolve \"unset\" a layer too early) and DERIVED from the store dir for the dev server, which is what keeps two projects on one machine from colliding. Set it to pin one address for both."}
   {:key "http.max-body-bytes" :type [:int {:min 1}] :default 1048576
    :doc "Largest accepted request body, bytes."}

   {:key "rest.enabled" :type [:boolean] :default false
    :doc "Whether this project publishes a typed API: request/response contracts, boundary validation, and generated clients derived from the same schemas. Requires http."}

   {:key "webapp.enabled" :type [:boolean] :default false
    :doc "Whether this project's BROWSER owns routing and state — client-side routes, event dispatch, the ClojureScript build. Serving HTML needs only http; this is the app that runs in the page. Requires http (it has to be served) and NOT rest: a browser app may talk to a third-party API, a socket, or to no server data at all."}

   
   {:key "http.static.*" :type [:string] :default nil
    :doc "Static mount: the key's tail is the URL prefix, the value a files-manifest path prefix (http.static./assets = public serves public/cljs/main.js at /assets/cljs/main.js). A trailing slash on either is trimmed."}
   {:key "http.auth.providers" :type [:set-of [:enum "static" "bearer" "proxy-header" "oidc"]] :default #{}
    :doc "Enabled identity providers, comma-separated."}
   {:key "http.auth.default-policy" :type [:enum "deny" "authenticated" "public"] :default :deny
    :doc "Policy for an endpoint with no :http/auth of its own (reachable only when http-auth-refusal is dialed down)."}
   {:key "http.auth.static.*" :type [:string] :default nil
    :doc "Static-provider settings (http.auth.static.users.<name> = {:password-hash … :groups […]})."}
   {:key "http.auth.bearer.*" :type [:string] :default nil
    :doc "Bearer-provider settings (http.auth.bearer.tokens.<name> = {:secret \"env:NAME\" :groups […]})."}
   {:key "http.auth.proxy.*" :type [:string] :default nil
    :doc "Trusted-proxy-provider settings (http.auth.proxy.trusted, http.auth.proxy.user-header)."}
   {:key "http.auth.oidc.*" :type [:string] :default nil
    :doc "OIDC-provider settings (http.auth.oidc.issuer, http.auth.oidc.client-id, …). Secrets as env:NAME."}
   {:key "http.auth.groups.*.members" :type [:csv] :default nil
    :doc "Members of a named group, comma-separated (http.auth.groups.admin.members = alice,bob). A group exists to be named by :http/auth [:group …], which is why it sits under auth rather than beside it."}])

(defn- match-pattern?
  "Does dotted key `k` match registry `pattern`? A trailing `*` matches one
  or more remaining segments; a mid-pattern `*` exactly one."
  [pattern k]
  (loop [ps (str/split (str pattern) #"\.")
         ks (str/split (str k) #"\.")]
    (cond
      (empty? ps) (empty? ks)
      (and (= "*" (first ps)) (empty? (rest ps))) (boolean (seq ks))
      (empty? ks) false
      (or (= "*" (first ps)) (= (first ps) (first ks))) (recur (rest ps) (rest ks))
      :else false)))

(defn ^{:export "slopp.project"} find-entry
  "The registry entry governing concrete key `k` — exact match first, then
  wildcard patterns. nil = no such capability (the unknown-key refusal
  signal; a typo'd key must never silently do nothing).

  Takes the REGISTRY in the 2-arity, because `dev` is governed by its own
  (`slopp.project.dev/registry`) and the lookup — exact, then pattern — is
  the same question whichever set of rows is asked. The 1-arity is the
  capability registry, which is what every existing caller means."
  ([k] (find-entry registry k))
  ([rows k]
   (let [k (str k)]
     (or (some #(when (= (:key %) k) %) rows)
         (some #(when (and (str/includes? (:key %) "*")
                           (match-pattern? (:key %) k))
                  %)
               rows)))))

(defn check-value
  "Validate config string `v` against `entry`'s declared `:type`. nil when
  the value suits; else a TEACHING string — the key, what it takes, what
  arrived — surfaced verbatim by config_file's refusal. Write-time
  validation is the point: a bad value is refused at the write, not
  discovered when the server fails to boot."
  [entry v]
  (when entry
    (let [v (str v)
          [t opt] (:type entry)
          bad (fn [wants]
                (str (:key entry) " takes " wants ", got " (pr-str v)))]
      (case t
        :string nil
        :boolean (when-not (#{"true" "false"} v) (bad "true or false"))
        :int (let [n (try (Long/parseLong v) (catch NumberFormatException _ nil))]
               (cond
                 (nil? n) (bad "an integer")
                 (and (:min opt) (< n (:min opt))) (bad (str "an integer ≥ " (:min opt)))
                 (and (:max opt) (> n (:max opt))) (bad (str "an integer ≤ " (:max opt)))))
        :enum (let [members (rest (:type entry))]
                (when-not (some #(= % v) members)
                  (bad (str "one of " (str/join ", " members)))))
        :set-of (let [members (rest (second (:type entry)))
                      vals* (map str/trim (str/split v #","))]
                  (when-let [alien (some #(when-not (some (fn [m] (= m %)) members) %)
                                         vals*)]
                    (bad (str "a comma-separated subset of " (str/join ", " members)
                              " — " (pr-str alien) " is not one"))))
        :qualified-symbol (when-not (re-matches #"[^\s/]+/[^\s/]+" v)
                            (bad "a qualified symbol (app.core/-main)"))
        :csv (when (str/blank? v) (bad "a comma-separated list"))
        ;; ORDERED, where :csv is a set. Membership is the right shape for a
        ;; group's members; it is the wrong one for anything positional —
        ;; `--port,8080` and `8080,--port` are not the same arguments, and a
        ;; set cannot tell them apart.
        :csv-list (when (str/blank? v) (bad "a comma-separated list"))))))

(defn ^{:export "slopp.project"} parse-value
  "A stored config STRING as its registry `entry`'s declared type.

  Extracted from [[effective]] so a second registry can read its own values
  without copying the `case` — `slopp.project.dev` governs the `dev` path
  with the same type vocabulary, and a second copy of this would drift the
  day a type is added. What is shared is the VOCABULARY, not the keys: each
  registry still declares its own.

  Assumes `v` already passed [[check-value]]. A value that has not is the
  caller's problem, and [[effective]] handles it by falling back to the
  entry's default rather than throwing at serve time."
  [entry v]
  (case (first (:type entry))
    :string v
    :boolean (= "true" v)
    :int (Long/parseLong v)
    :enum (keyword v)
    :set-of (into #{} (map (comp keyword str/trim)) (str/split v #","))
    :qualified-symbol (symbol v)
    :csv (into #{} (map str/trim) (str/split v #","))
    ;; a VECTOR, order kept — see check-value for why the two comma types
    ;; are not one type
    :csv-list (into [] (map str/trim) (str/split v #","))))

(defn ^:export effective
  "The effective value of capability `k` for this store: the stored
  `capabilities` config value parsed per its registry type, else the
  entry's `:default` — so a registered key with a default never nil-puns.
  Unknown key → nil. A stored value failing its check (reachable only via
  a foreign merge; the write gate refuses it) falls back to the default
  rather than throwing at serve time.

  Exported: it is THE reader for a capability value, and a consumer
  outside this module reaching into `[:config \"capabilities\" :values]`
  would skip both the type parsing and the default."
  [store k]
  (let [k (str k)
        entry (find-entry k)
        v (get-in store [:config "capabilities" :values k])]
    (cond
      (nil? entry) nil
      (and v (nil? (check-value entry v))) (parse-value entry v)
      :else (:default entry))))

(defn ^:export stored?
  "Whether capability `k` was explicitly SET in this store, as opposed to
  carrying its registry default.

  `effective` deliberately erases that distinction so a registered key never
  nil-puns. Some callers need it back: the dev server binds an explicitly
  pinned `http.port` but DERIVES one when nobody pinned it, because a fixed
  default collides between two projects on one machine (the reasoning
  `slopp.webdev.live/derived-port` records). \"8080\" typed by hand and 8080
  arriving from the registry have to be told apart to do that.

  Exported for the same reason `effective` is: the config path is this
  namespace's business, and a consumer reaching into
  `[:config \"capabilities\" :values]` to answer this would be the second
  place that knows where values live."
  [store k]
  (some? (get-in store [:config "capabilities" :values (str k)])))

(defn ^:export enabled?
  "Whether `store` has opted into capability `c`.

  THE predicate. It existed six times as a hand-rolled
  `(= \"true\" (get-in candidate [:config \"capabilities\" :values \"web.enabled\"]))`
  — in the write gates, in the done-grain checks, in the dev server and in the
  client build — which is a second reader of a path this namespace is supposed
  to be the only owner of, copied five more times. A rename then reaches five
  of the six, and the sixth keeps working against a key nothing writes.

  Reads through [[effective]] rather than the raw config, so it inherits the
  type parse and the registry default: an unregistered or misspelled capability
  is `false` rather than a truthy string, and there is no spelling of `true`
  that works here and not in `query_capabilities`."
  [store c]
  (true? (effective store (str c ".enabled"))))

(defn ^:export implied-puts
  "The `<c>.enabled` keys a write of `k`=`v` must ALSO set, in dependency
  order — empty when there is nothing to imply.

  Enabling a capability turns on what it requires, transitively, because the
  alternative is the puzzle every project would otherwise meet once: opting
  into `webapp` appears to work and then nothing serves, since `http` was
  never set. Only prerequisites already OFF are returned, so the reported
  `:implied` names the changes a reader could not have predicted rather than
  restating the graph back at them.

  Only ever ADDS. A write of `false` implies nothing — turning `webapp` off is
  not a reason to tear down the server, which may be serving other things —
  and that asymmetry is deliberate: the enable direction has one safe answer
  and the disable direction is a question only the author can settle, which is
  what [[disable-refusal]] asks."
  [store k v]
  (let [k (str k)]
    (if-not (and (str/ends-with? k ".enabled") (= "true" (str v)))
      []
      (let [c (subs k 0 (- (count k) (count ".enabled")))]
        (into []
              (comp (remove #(enabled? store %))
                    (map #(str % ".enabled")))
              (sort (prerequisites c)))))))

(defn ^:export disable-refusal
  "A teaching refusal when turning `k` off would leave a dependent capability
  standing on nothing — nil when the write may land.

  The mirror of [[implied-puts]], and the half that is easy to leave out. An
  enable that implies its prerequisites but a disable that does not check its
  dependents lets the config reach a state the catalog says is impossible:
  `webapp` on with `http` off. Nothing would refuse it, and the consequence
  would surface as a browser app that never loads — a diagnosis several layers
  from the config that caused it.

  It REFUSES rather than cascading, and the asymmetry with the enable
  direction is the point: turning something on has one safe answer, since a
  prerequisite is exactly what the capability cannot work without. Turning
  something off does not — silently disabling `webapp` because you disabled
  `http` would be slopp deciding to remove a feature the author never
  mentioned. So the enable direction acts, and this one asks."
  [store k v]
  (let [k (str k)]
    (when (and (str/ends-with? k ".enabled") (= "false" (str v)))
      (let [c    (subs k 0 (- (count k) (count ".enabled")))
            held (sort (filter #(enabled? store %) (dependents c)))]
        (when (seq held)
          (str k " cannot be turned off while "
               (str/join ", " held)
               (if (= 1 (count held)) " is enabled" " are enabled")
               " — " (str/join " and " held)
               (if (= 1 (count held)) " requires " " require ")
               c ", so this would leave "
               (if (= 1 (count held)) "it" "them")
               " standing on nothing. Turn "
               (str/join ", " (map #(str % ".enabled") held))
               " off first, or leave " k " as it is."))))))

(defn ^:export rule-owner
  "The capability that owns rule `k` — read off the rule's own NAME — or nil for
  a rule every project has.

  `:rest-stale-client` is rest's; `http-auth-refusal` is http's; `key-typos` is
  nobody's. Derived from the name rather than declared beside it, which is the
  same move `owners` makes for a config key's first segment and
  `gate-capability` makes for a gate's namespace: there is no second field to
  keep in step, and renaming a rule is the only way to change who owns it.

  Only a capability with `:requires` qualifies — `slopp` and `app` are owners
  rather than opt-ins, so no rule can belong to them.

  **One derivation, three readers**, and it was two of them disagreeing that
  made this a function. `query_capabilities` reported a rule under a
  capability's `:arms` — the list saying what opting in would turn on — while
  the sweep ran that rule regardless of whether the capability was enabled. A
  capability claiming rules it does not control is the model failing at the one
  thing it exists to do."
  [k]
  (let [n (name k)]
    (some (fn [{:keys [capability requires]}]
            (when (and requires (str/starts-with? n (str capability "-")))
              capability))
          capability-catalog)))

(def ^:export shipping-common
  "Namespaces that ship with EVERY capability — the `\"_\"` family of the vendored
  manifests, as `{ns path}`.

  Not a capability, and that is why it needs its own name: these belong to the
  DIALECT and its disciplines rather than to any one kind of application, so
  keying them under `http` would leave a command-line app requiring code it was
  never handed, and keying them under both would be a list to keep in sync.

  Membership is narrow on purpose — a namespace belongs here when slopp's own
  rules tell an author to USE it, because a rule whose discharge names a
  namespace the author does not have is a rule that cannot be discharged. Every
  member arrived that way:

  - `slopp.lang` — D3.1: the dialect denies reader conditionals and owes the
    author the portable call instead, so a shipped family may require it.
  - `slopp.cache` — the `tier-refusal` gate's escape names it (\"an :internal
    module may mutate in-process, e.g. a memo through slopp.cache\"), and the
    shipped skill states the rule that every cache goes through it. It was
    documented as a day-one rule and vendored NOWHERE, which is exactly the
    failure `no-rule-names-a-namespace-that-does-not-ship` now watches for.
  - `slopp.cljnx` and its two halves — the fake browser. The skill tells every
    author to open a screen with it, and `edit.webapp/webapp-page-unreachable`
    refuses a page it could not open, naming it. **It shipped by COINCIDENCE OF
    NAMING until D-cljnx**: as `slopp.http.screen` it sat under http's
    `:ns-prefix`, so the vendor glob covered it — and covered it only for
    stores that use http, while a `cli` store driving no screens is not the
    population that mattered. Being named here is the first time its shipping
    is a decision rather than a side effect.

  **What every member has in common is what makes shipping them a COPY rather
  than a dependency graph: none reaches back into slopp.** `slopp.lang` and
  `slopp.cache` have zero requires at all; the cljnx three require each other
  and `clojure.string` and nothing else. That is a property to check rather
  than assume when adding a member — a namespace here that required a
  capability's code would land in a store that does not have it, which is the
  failure `framework-injection` describes as the framework arriving intact and
  failing inside itself.

  THE derivation two readers consume: `build.clj` files these under `\"_\"`, and
  the leak guard permits a family namespace to reach them."
  '{slopp.lang         "slopp/lang.cljc"
    slopp.cache        "slopp/cache.clj"
    slopp.cljnx        "slopp/cljnx.clj"
    slopp.cljnx.hiccup "slopp/cljnx/hiccup.clj"
    slopp.cljnx.render "slopp/cljnx/render.clj"})

(defn ^:export shipping-families
  "`{capability ns-prefix}` for every capability that SHIPS a namespace family
  into consuming projects.

  THE derivation three readers consume: the vendor glob in `build.clj`, the
  injection predicate in `slopp.ops.engine`, and the leak guard that says a
  framework namespace may not reach back into slopp. Each of those hardcoded
  `slopp.http` before, and each was correct for one app type while being blind
  to a second — the guard in particular went GREEN when a namespace left the
  family, because its population is derived by prefix and a departing member
  simply stops being in it.

  A capability with no `:ns-prefix` ships nothing and is absent here, rather
  than present with an empty family. `rest` and `webapp` are in that state
  today: they are declared, they arm nothing, and they vendor nothing. Absent
  and empty-family are different claims, and only the first is true."
  []
  (into {} (for [{:keys [capability ns-prefix]} capability-catalog
                 :when ns-prefix]
             [capability ns-prefix])))

(defn ^:export config-refusal
  "The `capabilities` config write gate: a teaching error for an unknown
  key, a value that fails its registry type, or a CREDENTIAL-shaped literal
  — nil when the write may land. An unknown key MUST refuse (a typo'd
  capability that silently does nothing is the nil-pun failure this
  registry exists to kill). A secret literal must refuse too: this config
  is tracked and git-projected, so `http.auth.*` credential positions (a
  `…token…`/`…secret` key, or a `:secret` entry in the value) take
  `env:NAME` indirections only; `password-hash` is exempt — a hash IS the
  safe form."
  [k v]
  (let [k (str k) v (str v)
        credential-key? (and (str/starts-with? k "http.auth.")
                             (re-find #"(token|secret)s?(\.|$)" k))
        secret-entry (second (re-find #":secret\s+\"([^\"]*)\"" v))
        literal? (fn [s] (and (seq (str s))
                              (not (str/starts-with? (str s) "env:"))))]
    (if-let [entry (find-entry k)]
      (or (check-value entry v)
          (cond
            (and credential-key? (nil? secret-entry) (not (str/includes? v ":"))
                 (literal? v))
            (str k " holds a literal credential — this config is tracked and"
                 " git-projected, so secrets go through the environment:"
                 " value \"env:SOME_NAME\", and the deployment sets SOME_NAME")

            (and (str/starts-with? k "http.auth.") secret-entry (literal? secret-entry))
            (str k " embeds a literal :secret — this config is tracked and"
                 " git-projected, so secrets go through the environment:"
                 " :secret \"env:SOME_NAME\", and the deployment sets SOME_NAME")))
      (str k " is not a capability — query_capabilities lists every setting"
           " with its type, default, and effective value; known keys/patterns: "
           (str/join ", " (map :key registry))))))

(defn ^:export report
  "The `query_capabilities` payload: `{:settings [...] :patterns [...]
  :owners {...}}`, plus `:orphaned` when the store has stored keys this build
  does not recognise. `:settings` = one row per CONCRETE registry key
  `{:key :owner :effective :default :doc}` (+ `:set true :value <raw>` when
  the store sets it), plus a row for every stored key a wildcard pattern
  governs. `:patterns` = the wildcard entries themselves (key + owner + doc)
  — they name families, they are not settable rows. A pure function of the
  store value, so it is correct on any branch and at any revision.

  **`:owner` is DERIVED from the key's first segment**, never stored beside
  it, so the label and the name cannot disagree; `:owners` is the vocabulary
  those labels come from. It exists because every project is shown every
  key, and fourteen of nineteen belong to one app type — a store that will
  never serve HTTP still reads `http.auth.oidc.*` as something it could set.
  Filtering them out would be the wrong fix: `http.enabled` is itself a web
  key, so hiding web keys until web is on hides the switch that turns it on.
  Attribution is what makes fourteen settings read as one feature.

  **`:orphaned` is the rename path, and it used to be invisible.** This is a
  JOIN of the registry against the stored config, and a stored key with no
  registry row simply fell off it. So a store carrying three settings under
  retired names reported ZERO `:set true` and said nothing at all — the tool
  whose job is *what is configured here* describing an unconfigured store,
  while the reason its app server would not start sat in the config it
  declined to mention. UNSET and SET-UNDER-A-NAME-I-NO-LONGER-KNOW shared one
  representation at the exact moment the difference IS the diagnosis.

  The rows carry the VALUE, not just the key, because that makes the answer a
  migration instruction rather than a prompt to go and look. Absent when there
  are none, the way the module manifest's `:debt` is — this always computes,
  so absence unambiguously means none.

  Found by the first store to cross a capability rename. With
  `no-backwards-compatibility` standing policy that path is common rather than
  rare, so the report has to survive it."
  [store]
  (let [values (get-in store [:config "capabilities" :values] {})
        concrete? #(not (str/includes? (:key %) "*"))
        owner-of (fn [k] (first (str/split (str k) #"\.")))
        setting (fn [k entry]
                  (let [v (get values k)]
                    (cond-> {:key k
                             :owner (owner-of k)
                             :effective (effective store k)
                             :default (:default entry)
                             :doc (:doc entry)}
                      (some? v) (assoc :set true :value v))))
        rows (mapv #(setting (:key %) %) (filter concrete? registry))
        exact? (fn [k] (some #(when (= (:key %) k) %) registry))
        ;; every stored key the concrete rows above did not already cover:
        ;; some are governed by a wildcard pattern, and the rest are governed
        ;; by nothing, which is the case this used to drop on the floor.
        loose (remove exact? (sort (keys values)))
        {governed true orphans false} (group-by #(some? (find-entry %)) loose)
        wild (mapv #(setting % (find-entry %)) governed)
        orphaned (mapv (fn [k] {:key k :value (get values k)}) orphans)]
    (cond-> {:settings (into rows wild)
             :patterns (mapv #(assoc (select-keys % [:key :doc]) :owner (owner-of (:key %)))
                             (remove concrete? registry))
             ;; the vocabulary rides along rather than being looked up: an
             ;; owner label on a row is only useful beside what the label
             ;; MEANS, and a reader of this payload has no other way to it.
             :owners owners}
      (seq orphaned)
      (assoc :orphaned orphaned
             :orphaned-note
             (str "stored under names this slopp does not know — nothing reads"
                  " them. They are usually a capability RENAME you have not"
                  " migrated: set the current key (query_capabilities lists"
                  " them all) and then config_file {path \"capabilities\" key"
                  " <old> unset true}")))))

(def ^:export secret-families
  "Key prefixes whose VALUES are credentials and must never be published.

  Declared as data rather than matched by name, and the difference is the whole
  point. A denylist of fragments — `token`, `secret`, `password` — has to be
  updated by whoever adds the next setting, and its failure mode is publishing
  a credential rather than withholding a boring key. A family declared here is
  withheld until somebody removes it.

  **This governs the PUBLISHED document, not the store-side tool.**
  `query_capabilities` runs for an agent that already holds the store and can
  read the config directly, so redacting there would hide a value from the one
  reader entitled to it while protecting nothing. `/api/config` answers a
  REMOTE consumer over a public route, which is a different trust boundary and
  the only one that needs this.

  What is withheld is the VALUE. The key, its owner, its doc and whether it is
  SET all still publish — *this is configured and I am not showing you* is a
  useful answer, and *nothing here* would be a false one."
  ["http.auth.static." "http.auth.bearer." "http.auth.oidc."])
