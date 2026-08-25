(ns slopp.rules.rest
  "The `rest` capability's STORE-SIDE reading: what a project declares its typed
  API to be, derived from the same markers the write gates enforce.

  Reading and refusing are split across two namespaces on purpose — the gates
  refuse at the WRITE, where an author can still fix it, and this describes what
  stands. The same split `slopp.edit.http`/`slopp.rules.http` and
  `slopp.edit.cli`/`slopp.rules.cli` already make.

  **Named for the capability, and that is a rule rather than a habit** (R6). A
  report that reads one app type's vocabulary from a GENERIC namespace has no
  app type to disagree with, so the naming guard cannot grade it — which is how
  five web-only checks once sat in `slopp.rules` unnoticed. Moving a report out
  of here is the only way to change who owns it.

  Note this does NOT ship: it is under `slopp.rules`, slopp's own tooling, not
  under the `slopp.rest` family a consumer is vendored. Neighbours: `slopp.rest`
  is the shipped runtime, `slopp.rest.contract` decides what a contract means,
  and this reads what the store DECLARES without running anything."
  (:require [slopp.edit.http :as edit.http]
            [slopp.project.capabilities :as capabilities]
            [slopp.cli.spec :as spec] [clojure.string :as str] [slopp.rules.http :as rules.http]))

(defn rest-stale-client-check
  "Done-advisory (D-web-contracts part 2): the generated typed client
   (generate_client) is STALE — an endpoint or its :rest/request/:rest/response
   changed since the client was last generated. Fires only once a client has been
   generated (a `client`/`generated-sig` is on record), so it never nags a store
   that has not opted into a generated client. Regenerating re-records the
   signature and clears it."
  [_session store _changed]
  (let [recorded (get-in store [:config "client" :values "generated-sig"])]
    (when (and recorded (not= recorded (edit.http/client-signature store)))
      [{:rest-stale-client true
        :teach (str "the generated typed client is out of date — an endpoint or its"
                    " :rest/request/:rest/response changed since generate_client last"
                    " ran. Re-run generate_client to re-derive the wrappers.")}])))

(defn rest-inline-schema-dup-check
  "Done-advisory (D-web-contracts part 2): 2+ endpoints declare the SAME
   structured inline :rest/request/:rest/response schema — the DRY nudge toward the
   paved road. A shared shape should be a named .cljc schema VAR so server and
   client validate against ONE definition and a change lands in one place. Only
   structured (vector) inline schemas count — a bare keyword like :map is too
   trivial to extract. Fires once per duplicated shape."
  [_session store _changed]
  (let [inlines (for [{:keys [ns name meta]} (edit.http/web-endpoint-rows store)
                      k     [:rest/request :rest/response]
                      :let  [v (get meta k)]
                      :when (vector? v)]
                  {:endpoint (symbol (str ns) (str name)) :schema v})
        dups    (->> inlines
                     (group-by :schema)
                     (keep (fn [[schema es]]
                             (let [eps (distinct (map :endpoint es))]
                               (when (>= (count eps) 2) [schema (vec eps)])))))]
    (for [[schema eps] dups]
      {:duplicate-inline-schema (pr-str schema)
       :endpoints eps
       :teach (str (count eps) " endpoints declare the identical inline schema "
                   (pr-str schema) " — extract it to a named .cljc schema var so"
                   " the server and the generated client validate against ONE"
                   " definition and a change lands once.")})))

(defn rest-undocumented-contract-check
  "Advisory: a published endpoint's request/response schema has fields that
   say nothing about what they ARE. A type is a shape, not a term of the
   contract — nothing in `:total :int` tells a caller the number counts hits
   BEFORE the limit is applied, and a consumer generating a client from the
   document has no other place to learn it. Malli entry properties are open
   and already survive the wire, so this asks for prose rather than a feature.

   Advisory on purpose: an undocumented field is a legitimate state for the
   commit between declaring a schema and describing it, and red would make the
   rule something to route around. Whole-store on purpose: a published contract
   is stable, and a check whose population is the episode's CHANGED forms
   cannot see something that has stopped changing."
  [_session store _changed]
  (for [{:keys [endpoint schema fields]} (rules.http/undocumented-contract-fields store)
        :let [shown (take 6 fields)
              path-str (fn [p] (str/join " → " (map str p)))]]
    {:endpoint endpoint
     :schema schema
     :fields fields
     :teach (str endpoint "'s " schema " leaves " (count fields)
                 (if (= 1 (count fields)) " field" " fields")
                 " undocumented: " (str/join ", " (map path-str shown))
                 (when (> (count fields) (count shown))
                   (str " (+" (- (count fields) (count shown)) " more)"))
                 ". A type says what SHAPE a value has, never what it MEANS, and"
                 " the document is all a consumer generating a client can read."
                 " Put the prose on the entry, where malli carries it over the"
                 " wire unchanged: [" (pr-str (last (first shown)))
                 " {:doc \"what it is\"} <type>]. :description is accepted too —"
                 " it is malli's JSON-Schema spelling, so an imported schema is"
                 " not penalised for documenting itself in that dialect."
                 " Keep it to one line or build it with (str …): unlike a"
                 " docstring this is a VALUE, so a multi-line literal ships its"
                 " own source indentation to every consumer that renders it.")}))

(defn rest-unconstrained-contract-check
  "Advisory: a published endpoint declares a field that constrains nothing —
   a bare `:map`, which accepts any map, or `:any`, which accepts anything.

   The cost is not vagueness, it is a SILENT mechanism. A generated client
   validates every response against the published schema, so a field whose
   shape can change without failing validation has the one thing built to
   catch drift pointed at it and switched off. Measured in the wild: a `:diff`
   moved from `[String]` to `[[String String]]` and passed validation on every
   call for weeks.

   `:any` and a bare `:map` are both reported and are not the same offence.
   `:any` ADMITS it is saying nothing; a bare `:map` looks like a type while
   saying the same thing, and that is the one a reader trusts by mistake.

   **`^{:rest/unconstrained-ok \"why\"}` on the handler discharges it**, and
   that escape exists because slopp-ui found the rule had none. The teach used
   to end \"if the shape genuinely is not settled, say `:any`\" — advice that
   names the very state the rule reports, so an endpoint that legitimately
   cannot constrain (their `project-api` is a proxy: its response IS whatever
   a project sent, forwarded byte for byte) was flagged forever with no
   available action. Their reason for caring is the one to keep: a permanent
   finding nobody can act on trains a reader to skim the list, spending the
   credibility of every other finding in it.

   The marker POLICES ITSELF. Carried by an endpoint whose schema does
   constrain, it is reported as stale and asked to be dropped — so it cannot
   quietly become a mute button, the same discipline `^:foreign-keys` and
   `^:unused-ok` are held to.

   Advisory, because an unspecified field is a legitimate state while a shape
   is still settling. Whole-store, because a published contract is stable and
   nothing episode-scoped will look at it again."
  [_session store _changed]
  (let [rows    (rules.http/unconstrained-contract-fields store)
        loose   (set (map :endpoint rows))
        marked  (for [{:keys [ns name meta]} (edit.http/web-endpoint-rows store)
                      :when (:rest/unconstrained-ok meta)]
                  (symbol (str ns) (str name)))
        marked? (set marked)]
    (concat
     ;; the stale half FIRST: a marker that no longer describes anything is a
     ;; claim about the code that has quietly stopped being true, and it is
     ;; the finding a reader is least expecting
     (for [ep marked :when (not (loose ep))]
       {:endpoint ep
        :stale-marker true
        :teach (str ep " carries ^{:rest/unconstrained-ok …} but every field of"
                    " its contract constrains something — the waiver describes"
                    " nothing. Remove it. A marker is a CLAIM about the code"
                    " around it, and one that outlives what it waived reads"
                    " exactly like coverage while suppressing a rule nobody"
                    " has checked in the meantime.")})
     (for [{:keys [endpoint schema fields]} rows
           :when (not (marked? endpoint))
           :let [maps  (filter #(= :map (:declares %)) fields)
                 anys  (filter #(= :any (:declares %)) fields)
                 where (fn [f] (if (seq (:path f))
                                 (str/join " → " (map str (:path f)))
                                 "the whole schema"))]]
       {:endpoint endpoint
        :schema schema
        :fields fields
        :teach (str endpoint "'s " schema " declares "
                    (count fields) (if (= 1 (count fields))
                                     " field that constrains nothing"
                                     " fields that constrain nothing")
                    (when (seq maps)
                      (str " — a bare :map at " (str/join ", " (map where maps))
                           ", which accepts ANY map"))
                    (when (seq anys)
                      (str (if (seq maps) "; and :any at " " — :any at ")
                           (str/join ", " (map where anys))))
                    ". The generated client validates every response against this"
                    " schema, so a shape that changes underneath one of these"
                    " passes forever — the mechanism that exists to catch drift is"
                    " pointed at the field and switched off."
                    (when (seq maps)
                      (str " A bare :map is the misleading one: it looks like a"
                           " type and admits everything, where :any at least says"
                           " so."))
                    " Name the entries — [:map [:kind :string] [:text :string]]."
                    " If this endpoint CANNOT constrain — a proxy forwarding"
                    " another service's bytes — say so where a reader will see"
                    " it: ^{:rest/unconstrained-ok \"a proxy: the response is"
                    " whatever the project sent\"}. That discharges this, and"
                    " is reported as stale if the contract later constrains.")}))))

(defn- resolved-schema
  "`decl` with every schema symbol in it replaced by the form that symbol
  names — to a FIXED POINT, not one hop.

  A schema var may name another, and `api.contracts/namespace-list` documents
  why that is deliberate rather than incidental: *\"a schema var is an ordinary
  var, so this composition is a REAL reference edge, and changing the row shows
  up in its blast radius.\"* Resolving one level leaves an inner symbol in the
  form, malli refuses to build it, and the total accessor answers nil — which
  on slopp's own store was five endpoints of ten.

  `from` travels, because each hop resolves from where it is WRITTEN: an inner
  reference inside `api.contracts` is named from there, not from the endpoint
  whose metadata began the walk. That is the whole reason `schema-resolver`
  returns the resolved `[ns name]` alongside the schema.

  **A props map is not walked.** In `[:k {:doc \"…\"} schema]` the map holds
  documentation, and a `:doc` string mentioning a symbol is prose. Walking it
  would resolve words.

  Two guards, both for the same shape: a schema that references itself, which
  is legal and useful for a tree. `seen` stops a cycle and the depth bound
  stops a chain long enough to be pathological. Either way the unresolved
  symbol is LEFT in place, so the caller reports the name rather than nil."
  [resolve* from decl]
  (letfn [(walk [from decl depth seen]
            (cond
              (> depth 32) decl

              (symbol? decl)
              (if-let [[from' schema] (resolve* from decl)]
                (if (contains? seen from')
                  decl
                  (walk from' schema (inc depth) (conj seen from')))
                decl)

              ;; a props map's values are documentation, not schemas
              (map? decl) decl

              (vector? decl) (mapv #(walk from % (inc depth) seen) decl)

              :else decl))]
    (walk from decl 0 #{})))

(defn ^:export contracts-report
  "The `rest` section of `query_surface`: one row per endpoint that declares a
  typed contract.

  Empty until `rest.enabled` — the reading side of the same inertness the gates
  have, and for the same reason: a project that publishes no typed API must not
  be DESCRIBED as having one.

  **The shape is NAMES, not schemas**, the same choice `rules.cli/commands-report`
  makes and for a sharper reason here. A malli schema pretty-prints to several
  lines, and this surface is already the one whose payload grows with the APP
  rather than with the question — `query_rules` is what that looks like when it
  goes wrong, returning 17 of its 43 rules over the response gate with the
  truncation announced outside the payload. The names answer what an endpoint
  is FOR (what may I send, what comes back); `query_slice` on the handler
  answers what it accepts exactly.

  Read through `slopp.cli.spec/schema-names`, a TOTAL accessor shared with the
  cli surface, so a malformed declaration degrades to nil in both reports rather
  than throwing in one of them. A bad schema is caught at the WRITE, where the
  author can fix it.

  **Totality has to hold across SHAPES, not only across malformed input.** A
  field is a vector of key names when the contract describes a map — including a
  map inside a `[:sequential …]`, which is what a list endpoint is — and the
  schema's own TYPE keyword when there is nothing to enumerate, `:or` or
  `:string`. Reading a non-map's children as map entries is what once threw on a
  real store the day it enabled this capability, making nine endpoints
  unreadable because a tenth answered `[:or [:map …] [:map …]]`.

  **Every REST endpoint is a row, and there is no `:published` field.** It used
  to carry one — `:rest/client false` inverted — so that a reader asking what
  consumers can CALL could see an exclusion rather than infer it from an
  absence. Both halves of that are gone: content is excluded by KIND and never
  appears here, and an api cannot be excluded at all, because whether to
  generate a client is the generating consumer's question. A field that could
  only ever read `true` is not a field.

  Rows carry `:kind :contract`, so a renderer that knows nothing about this
  capability can draw one beside a command or an endpoint."
  [store]
  (if-not (capabilities/enabled? store "rest")
    []
    (let [resolve* (rules.http/schema-resolver store)
          ;; a declaration is either the schema itself or a SYMBOL naming one.
          ;; Resolving through the same producer the contract advisories use is
          ;; the point: two answers to one question is how this came to report
          ;; nil for a contract the rules had already judged.
          names    (fn [from decl]
                     (or (spec/schema-names (resolved-schema resolve* from decl))
                         ;; unresolvable: answer with the DECLARATION, never
                         ;; nil. It exists and says where it lives, and a blank
                         ;; field claims the endpoint declares nothing — the
                         ;; stronger form of the `[]` mistake this report
                         ;; already refuses to make.
                         decl))]
      ;; the row's own :path and :kind. Reading `:http/path` here survived the
      ;; api/content split as a SILENT wrong answer: every migrated endpoint
      ;; reported :path "" while the whole suite stayed green, because no test
      ;; asserted the path in this report. Caught by LOOKING at query_surface
      ;; rather than at the tests, which is the only thing that could have.
      (vec (for [{:keys [ns name meta path kind]} (sort-by :path
                                                           (edit.http/web-endpoint-rows store))
                 ;; every REST endpoint. It used to also need a contract declared, or
                 ;; the retired `:rest/client false` — but a `:rest/path` owes a
                 ;; `:rest/response` by gate, and content is excluded by kind
                 ;; one line up, so the extra arms could not vary
                 :when (= :rest kind)
                 :let [from [ns name]]]
             (cond-> {:kind      :contract
                      :method    (:http/method meta)
                      :path      path
                      :handler   (symbol (str ns) (str name))}
               (:rest/request meta)
               (assoc :request (names from (:rest/request meta)))
               (:rest/response meta)
               (assoc :response (names from (:rest/response meta)))))))))

(defn rest-envelope-schema-check
  "Advisory: an endpoint declares `:rest/response :string` under a media type
  slopp DECODES, so the schema describes the ENVELOPE and the boundary judges
  the document.

  **This exists because the suite of the store it breaks cannot see it.**
  `D-response-document` made the boundary decode a self-serialized body before
  judging it, which is right — but for a store that had declared `:string`
  (until that jar the only TRUE thing available, since the schema saw the
  bytes) the endpoint 500s from the moment it restarts. The store that found it
  had a test starting a real server, fetching the path and asserting 200. **It
  passed while the endpoint was down**, because the response check runs in the
  served app's context and not in a plain `serve!`. There was no test they
  could have written on that path.

  So the shape is found where a suite's blindness cannot reach: from the
  DECLARATIONS. A media type slopp decodes (`application/json`,
  `application/edn`) with a `:string` schema is a contradiction — the document
  is data, and `:string` is true only of the bytes it arrived in.

  **Advisory, not an error, and the reason is a real case rather than
  caution.** An endpoint answering `application/edn` whose document genuinely
  IS a string — `(pr-str \"hello\")` — is honest with `:string`, and this rule
  cannot tell it from the broken one. An error would block a correct
  declaration; an advisory names it, and the boundary is the enforcement.

  Media types slopp does NOT decode are left alone: `text/plain` really does
  arrive a string, so `:string` there is an honest description and reporting it
  would be a permanent finding nobody can act on."
  [_session store _changed]
  (for [{:keys [ns name meta]} (edit.http/web-endpoint-rows store)
        :let  [mt (some-> (:rest/media-type meta) str/lower-case
                          (str/split #";") first str/trim)]
        :when (and (= :string (:rest/response meta))
                   (contains? #{"application/json" "application/edn"} mt))]
    {:endpoint (symbol (str ns) (str name))
     :media-type mt
     :teach (str ns "/" name " declares :rest/response :string while answering "
                 mt " — so the schema describes the ENVELOPE and the boundary"
                 " judges the DOCUMENT. A self-serialized body is decoded"
                 " before the contract sees it (:rest/media-type owns the"
                 " encoding), so this endpoint answers 500 on every success"
                 " unless its document really is a string."
                 " Declare the shape a consumer reads —"
                 " [:map [:items [:sequential …]]] — and :string goes back to"
                 " meaning an endpoint that answers text."
                 " Nothing in a store's own suite can catch this: the response"
                 " check runs in the served app's context and not in a plain"
                 " serve!, so a test that starts a server and asserts 200"
                 " passes while the endpoint is down.")}))
