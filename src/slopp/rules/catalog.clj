(ns slopp.rules.catalog
  "The PROSE half of every rule: what it means, and how to discharge it.

  Deliberately nothing else. Severity lives where the rule is implemented and
  is joined back in, because when it was a column here it drifted — the
  catalog claimed `:advisory` for a rule that refused. Execution lives in the
  two registries. What remains is the part no registry can hold: the sentence
  an agent reads when a finding fires.

  Being a separate, inert data def is what makes it POLICEABLE: a coverage
  test asserts that every registered rule has an entry here and vice versa, so
  a new rule cannot ship without saying what it wants and how to satisfy it." (:require [clojure.string :as str]))

(def ^:export rule-catalog
  "The unified DECLARATIVE catalog of every D9 rule across both grains — each a
   `{:rule :grain :escape :teach}` map. It holds only what the registries cannot:
   the grain and the PROSE (what the rule means, how to discharge it).

   Severity is NOT here. It is declared where the rule is implemented — a write
   gate's `:rule/severity` metadata, a done-advisory's registry `:severity` — and
   `rule-rows` joins it back in. It used to be a column here, and nothing read it:
   `gate-check` hardcoded `:refuse` while `query_rules` reported whatever this
   said, so the catalog could claim `:advisory` for a rule that refused. Read
   `rule-rows`, not this def.

   Execution runs through the two registries (`edit.modules/per-form-write-gates`,
   `rules/done-advisories`); the `catalog-covers-every-registered-rule` test guards
   that this never drifts behind them."
  [{:rule :module-refusal :grain :form
    :escape "declare the edge (module_dep) or respect visibility (^:export / restructure)"
    :teach "a cross-module call needs a declared edge and must respect recursive visibility"}
   {:rule :cli-args-schema :grain :form
    :escape "add :cli/args to the name metadata — a malli schema, [:catn [:name :string]] for one positional or [:catn] if it genuinely takes none"
    :teach "a :cli/command must declare what it ACCEPTS. argv is untrusted input, so a command with no contract has moved the boundary into its own body, where nothing checks it and the first wrong value arrives as a string (inert until cli.enabled)"}
   {:rule :cli-command-collision :grain :form
    :escape "rename one, or extend the existing command (query_surface lists every claim, under :cli)"
    :teach "one command name has one owner. commands-in keys by name, so a duplicate does not fail at startup — whichever namespace loads first wins and the other command is simply unreachable, which looks exactly like one nobody has tried (inert until cli.enabled)"}
   {:rule :cli-direct-stdio :grain :form
    :escape "write to the INJECTED stream, (.write (:cli/out ctx) …), read from (:cli/in ctx), and RETURN an exit status instead of calling System/exit"
    :teach "a command body reaches for an AMBIENT stream or exits. A command writes its answer to the stream its context carries: *out* is a different stream that no test captures, no driver redirects and no fake stands in for, so the output escapes — and System/exit ends the process from inside business logic, so the launcher's own exit, a driver and a test never run. NOT a purity claim — printing is classified separately and a non-command may print freely (inert until cli.enabled)"}
   {:rule :tier-refusal :grain :form
    :escape "module_purity {module tier :internal/:external}, or move the effect into an :external namespace (:internal may mutate in-process, e.g. a memo through slopp.cache)"
    :teach "a form's effect or non-determinism exceeds its module's declared purity tier"}
   {:rule :schema-refusal :grain :form
    :escape "add a :=> :malli/schema, or config_file {path gates key require-boundary-schemas unset true}"
    :teach "a module-external map-arg fn must carry a :=> :malli/schema (when the store opts in)"}
   {:rule :namespaced-keys-refusal :grain :form
    :escape "use {:some.ns/keys [...]}, ^:foreign-keys for a third-party map, or config_file {path gates key require-namespaced-keys unset true}"
    :teach (str "a module-external fn's ARGLIST destructuring must use namespaced keys"
                " (when the store opts in). SCOPE: arglist destructuring on a"
                " module-external defn ONLY — not map keys generally, not return maps,"
                " not private fns, not (:k m) body reads. Its finding list IS the"
                " worklist. A deliberate HOUSE rule, stricter than Clojure practice,"
                " which defaults to unqualified keys: the argument for bare keys assumes"
                " context disambiguates, and an agent reads one form")}
   {:rule :http-auth-refusal :grain :form
    :escape "declare :http/auth on the endpoint (:public typed out, :authenticated, or [:group \"<name>\"]) — or dial the rule down and let http.auth.default-policy govern"
    :teach "an endpoint (:http/path) must declare its auth policy — default-deny: an unsecured route is a visible decision, never an omission (inert until http.enabled)"}
   {:rule :http-route-collision :grain :form
    :escape "change the path or method, or extend the existing handler (query_surface lists every claim)"
    :teach "one method+path has one owning endpoint — a duplicate route refuses at the write instead of surprising at startup (inert until http.enabled)"}
   {:rule :webapp-page-unreachable :grain :form
    :escape "move the entry — and the routing, derive and view code it reaches — to a :jvm or :cljc namespace, passing the browser-shaped parts in (:fetch, :render, a url pusher); or drop the ^:app/entry marker if this app is not meant to be reviewed headlessly"
    :teach "a ^:app/entry entry may not sit in a :cljs namespace — no JVM can open the app there, so every headless test drives a hand-built lookalike instead, and a lookalike passes while the real screen is wrong. The wiring is portable; only the effects are :cljs (inert until http.enabled)"}
{:rule :webapp-portable-handler :grain :form
    :escape "use the data form — {:on {:input [:your/action]}} — and read the value from your :webapp/act, which receives it as a scalar; or dial it down (config_file {path \"rules\" key \"webapp-portable-handler\" value \"advisory\"}) for an app whose own :cljs dispatcher normalises the event deliberately"
    :teach "a value-carrying control (input, select, textarea) may not take a FUNCTION handler — in a browser it receives a DOM event and headless it receives slopp's best-effort map, so a handler written against either passes every test and does nothing in production. A function on a button carries no value and is fine (inert until webapp.enabled)"}
{:rule :unknown-marker :grain :done
    :escape "fix the spelling, or move the key into your OWN namespace if it is yours — slopp reads nothing under :web/*, :webapp/*, :cli/*, :rest/*, :rule/* or :malli/* that it does not define"
    :teach "a marker in a namespace slopp OWNS that slopp does not define is a declaration nothing reads — it refuses nothing, generates nothing and changes nothing, while looking exactly like one that works. Usually a typo or a name that used to work: :web/spa became :webapp/client-routes, and a store keeping the old spelling serves fine, clicks fine, and 404s on every refresh and every shared deep link"}
{:rule :webapp-client-routes-are-served :grain :done
    :escape "declare a :webapp/client-routes prefix on the document endpoint that covers the route, or give the route a server route of its own. The prefix ROOT is not covered by the fallback — [\"/store\"] generates /store/*client-path, which needs at least one segment below it"
    :teach "a client route the server does not serve on a hard load: clicking to it works, refreshing it or opening a shared link 404s. So the app is fine for whoever is already inside it and broken for whoever was sent a url — the population that never reports it, because they assume the link was bad (inert until webapp.enabled)"}
   {:rule :webapp-request-paths-are-served :grain :done
    :escape "fix the path to one this store declares (the finding lists them), declare the endpoint, write the WHOLE url if it is a third-party server (an absolute url is never reported), or mark the form ^{:http/external-path \"why\"} when something OUTSIDE this store serves it — a proxied API under the app's own mount point cannot be written in full, because the prefix is known only at runtime"
    :teach "a screen's :webapp/path names an endpoint this store does not serve. It is the other half of a route reference: a literal :href is joined against the served table, and this is the same claim in a different key. The failure is quiet — the url routes, the screen renders, chrome and nav are fine, and one pane always fails to load while everything around it works, so it is reported as slowness rather than as a missing endpoint (inert until webapp.enabled)"}
   {:rule :webapp-client-code :grain :done
    :escape "move what is portable into :cljc — routing, the render loop, the listeners, load states, the performer and session loads are all declarations now, so most of a browser app has no :cljs left to be. A browser-only binding with no portable form is a real answer; there is no marker, because the finding IS the inventory and a permanently silenced entry would defeat it"
    :teach "a :cljs namespace this store still hand-writes. It never loads into the image, so it is outside the fast loop — every edit costs a compile to learn anything — and its only verification is that it COMPILED, which is a weak proxy for correctness: the only real webapp's two worst bugs both lived in one. This is the capability's goal stated as a number, arriving rather than waiting to be asked, and silent at zero (inert until webapp.enabled)"}
   {:rule :http-undeclared-effect :grain :form
    :escape "define a performer per kind ((defn ^{:http/effect <kind>} name! [ctx …] …)) or reuse an existing kind (query_surface lists the vocabulary)"
    :teach "an endpoint's :http/effects may only name kinds a marked performer provides — a typo'd kind fails at the write, not at the first request (inert until http.enabled)"}
{:rule :http-undeclared-context :grain :form
    :escape "declare ONE zero-arg builder ((defn ^{:http/context true} app-context [] {…})) — an app that runs its own serve! should mark the builder it already has and call it, since two definitions of one store's context agree until one gains a key. Dial it down (config_file {path \"rules\" key \"http-undeclared-context\" value \"advisory\"}) for a context that genuinely cannot be built without arguments"
    :teach "an endpoint reading :http/deps needs a store that declares where those deps come from — otherwise the map arrives nil, which 500s or, worse, answers 200 with an empty body, and generate_client consumes the empty one as a success (inert until http.enabled)"}
   {:rule :http-unsafe-get :grain :form
    :escape "make it :post/:put/:delete, drop the declared effects, or return the change as data from a non-safe endpoint"
    :teach "a :get/:head endpoint must be SAFE — it may neither declare :http/effects kinds nor reach a mutation (inert until http.enabled)"}
   {:rule :http-unknown-group :grain :form
    :escape "config_file {path \"capabilities\" key \"http.auth.groups.<name>.members\" value \"…\"} defines the group, or fix the name in :http/auth"
    :teach "an endpoint's [:group …] policy may only name groups the capabilities config defines — a typo'd group silently denies forever, the authz nil-pun (inert until http.enabled)"}
   {:rule :http-react-attrs :grain :form
    :escape "spell it as HTML (:class, :for), replace handlers with a link/form targeting an endpoint, or dial it down (config_file {path \"rules\" key \"http-react-attrs\" value \"advisory\"}) for a map that is genuinely not an element"
    :teach "a literal hiccup element carries a React attribute name (:className, :htmlFor, :onClick…) — browsers silently ignore unknown attributes, so it ships and does nothing (inert until http.enabled)"}
{:rule :http-content-shape :grain :form
    :escape "decide which it IS. Computing an answer from the request makes it an api: declare :rest/path (under the store's API prefix) with a :rest/response. Answering the same thing every time makes it content: a def whose VALUE is served — hiccup renders as text/html, anything else as it stands, and :http/media-type says what it is"
    :teach "a defn under :http/path. Content is a stored value the dispatcher DEREFERENCES, so a defn there is not a handler that never runs — it is served, as the string of its own function object, 200 with the right Content-Type and nothing downstream able to tell it from a page. One direction only: :rest/path on a def throws on the first request, which is loud and implicates the right form, and a gate exists to convert SILENCE (inert until http.enabled)"}
{:rule :http-unreachable-declaration :grain :form
    :escape "make it public — or, if the IMPLEMENTATION should stay private, move the marker to a public wrapper that calls it. That is the shape this gate leaves open and the ONLY one: there is no dial and no marker, because the serving population is public vars and no waiver changes that"
    :teach "a route or performer marker on a PRIVATE form. from-namespaces and performers-from-namespaces both build from ns-publics, so it declares a surface and contributes nothing — passing every other gate, appearing in query_surface, and not being there. A private ROUTE 404s; a private PERFORMER answers 500 on a request the store believes it serves, with a stack trace naming the framework rather than the defn-, which slopp.http/context calls the worst pairing available (inert until http.enabled)"}
   {:rule :rest-path-partition :grain :form
    :escape "declare the kind it IS — :rest/path for a typed api under the store's API prefix, :http/path for content outside it — or move the prefix (config_file {path \"capabilities\" key \"rest.prefix\" value \"/v1\"}) if this store's API genuinely lives elsewhere"
    :teach "a route is a REST api (:rest/path) or general HTTP content (:http/path), never both and never on the wrong side of the API prefix. The partition is what lets /api/* mean something to a proxy, a CSP or a reader WITHOUT consulting metadata. Before it, one marker served both and every endpoint was asked the API's questions — which is why a stylesheet declared :rest/response :string and then :rest/client false to undo it (inert until rest.enabled)"}
   {:rule :rest-endpoint-schema :grain :form
    :escape "add :rest/response (and :rest/request on a body method) to the endpoint's name metadata — a .cljc malli schema var (shareable/reusable) or an inline [:map …] for a one-off — or, if it is content rather than an api, declare :http/path instead and it is asked for none of this. Or dial it down (config_file {path \"rules\" key \"rest-endpoint-schema\" value \"advisory\"})"
    :teach "a :rest/path endpoint must declare :rest/response (and :rest/request on a :post/:put/:patch body method) — its contract, which the boundary VALIDATES at runtime and the generated client validates against, from the same schema. It asks :rest/path ONLY: asking every route made a stylesheet answer with a JSON schema. Inert until rest.enabled: serving a document is http's business, and publishing a typed API is rest's"}
   {:rule :http-public-mutation :grain :done
    :escape "tighten :http/auth, or accept it — a deliberately public write surface (signup, webhook) is legitimate and this asks per changed form"
    :teach "a changed :public endpoint declares :http/effects kinds — a publicly writable surface should be a decision, not an omission (inert until http.enabled)"}
   {:rule :http-dangling-route-refs :grain :done
    :escape "fix the path, add the endpoint or static asset, declare the client ROUTE it names (:webapp/routes — a link to a declared client route resolves with no marker at all), or mark the RENDERING form ^{:http/external-path \"why\"} when something OUTSIDE this store serves it. ^:webapp/client-path is RETIRED: slopp prefixes in-app links and the route table is data, so the literal is joined against it rather than escaped — and external-path on an app's own path files a false statement in the one report that says what is unchecked"
    :teach "a rendered link/form targets a path no declared route or static mount serves — the UI nil-pun: it ships and 404s. Dynamic paths ride along as :info findings: reported, never status-flipping (inert until http.enabled)"}
   {:rule :schema-drift :grain :done
    :escape "fix the schema or the impl so they agree"
    :teach "a written :=> schema disagrees with its live impl (generative mg/check)"}
{:rule :stored-name :grain :done
    :escape "rewrite the form so the store recomputes its name — edit_replace_form, or edit_subform addressing it by the form ID the finding names (an id works where the name does not, which is the whole problem)"
    :teach (str "a form's stored :name disagrees with the name its own source"
                " defines. The store keeps both and derives one from the other"
                " at every write, so a disagreement means some write did not —"
                " and the consequence is silent: name-addressed surfaces"
                " (rename_sweep, edit_subform {form}, :left-behind's :form)"
                " skip the form and report their own counts as complete, while"
                " id-addressed passes keep working, so half the rename"
                " machinery succeeds and the other half says nothing")}
   {:rule :stale-pattern :grain :done
    :escape (str "rewrite the pattern to a name that exists — the finding's"
                 " :suggest is the only namespace sharing its last segment."
                 " There is deliberately no dial: a regex naming a name this"
                 " store's own family does not have has never yet been"
                 " intentional here, and an escape invented before its first"
                 " real case is one nobody can evaluate")
    :teach (str "a regex literal spells a name in this store's own namespace"
                " family that is neither a namespace nor a prefix of one, so"
                " the pattern cannot match what it was written to find. A"
                " search pattern is DATA: ns_rename rewrites requires,"
                " qualified refs, quoted symbols and prose but not patterns,"
                " and rename_sweep's text pass misses the escaped dots. The"
                " worst case is an absence assertion — (is (not (re-find …)))"
                " over a name that moved is permanently, silently true")}
   {:rule :key-typos :grain :done
    :escape "reuse the established key (query_vocabulary), or accept the new one"
    :teach "a new namespaced key is one Damerau edit from an established same-namespace key"}
   {:rule :breaking-changes :grain :done
    :escape "^:breaking-ok on the name (a DELIBERATE break — you own telling downstream; it polices itself, a marker that narrowed nothing is reported stale), restore the arity/key/visibility, or rename for a clean break"
    :teach "a module-external fn's contract narrowed (arity/schema-key/visibility) vs the last-done baseline"}
   {:rule :ambient-state :grain :done
    :escape "pass state in as an arg, or accept it (a legit top-level cache — and a defonce that a ^{:http/context true} builder merely REFERENCES is one, since a builder allocating its own atom hands the app a fresh one per call)"
    :teach "a global (def _ (atom/ref/agent/volatile! …)) — ambient mutable state a slice can't track"}
   {:rule :assertions-never-red :grain :done
    :escape (str "break the subject with a WRITE and watch the test bounce."
                 " Red is read from :verify deltas, so a bare test_run does not"
                 " clear this however red it was — and neither does a write"
                 " whose verification DEFERS the test, which is every"
                 " ^:external one. For those two paths there is nothing to run:"
                 " accept it. Advisory precisely because only you know whether"
                 " you already watched it fail")
    :teach (str "a changed deftest GAINED assertion forms and never went red this"
                " episode, so the new ones have only ever been seen green. The"
                " load-bearing half of red-first is not test-before-code, it is"
                " that every assertion was WATCHED FAIL at least once — adding"
                " one to an already-passing test skips that and nothing else"
                " notices. key-not-returned catches the one silently-vacuous"
                " shape; this asks the general question")}
   {:rule :marker-why :grain :done
    :escape "write the reason into the marker — ^{:unused-ok \"why\"} discharges exactly as ^:unused-ok does — or accept it; advisory, never blocking"
    :teach (str "an escape marker on a changed form is a BARE keyword, so it says"
                " a rule was waived and nothing about why. ^:unused-ok — ok for"
                " what reason? ^:entry-point — invoked by WHAT? The map form"
                " answers in place and the dial becomes provenance instead of a"
                " mute flag, the way :prompt rides every delta and"
                " ^{:covers \"ns/name — why\"} already does. NOT ^:export (its"
                " string already means the subtree it widens to)")}
   {:rule :ambiguous-index :grain :done
    :escape "read through store/form-docstring, store/def-init or store/form-symbol — or accept it; advisory, never blocking"
    :teach (str "a changed form indexes position 2 of a STORE FORM, where a"
                " docstring and a def's VALUE share the slot. This is the"
                " codebase's worst bug class and it is SILENT: a wrong index"
                " yields nil, nil is falsy, and the rule reading it stops firing"
                " while looking healthy. NOT 'positional access' in general —"
                " that predicate measured 4-5 false positives out of 5; a"
                " defmethod's dispatch value at index 2 cannot shift and is not"
                " flagged")}
   {:rule :webapp-client-routes-consequences :grain :done
    :escape "nothing to discharge — it states a consequence once, for the episode that declared the prefix"
    :teach (str "an endpoint gained :webapp/client-routes this episode: every path under the"
                " declared prefix now answers 200 instead of 404, and NOT-FOUND"
                " moves into the client. Correct, and what :webapp/client-routes is for — but"
                " a real semantic change that no surface mentioned, and one that"
                " two existing tests caught only by asserting the old status."
                " The prefix ROOT is not covered by the fallback and still needs"
                " its own route")}
   {:rule :namespace-purpose :grain :done
    :escape "add a docstring to the ns form saying why the namespace exists — or accept it; this is advisory and never blocks"
    :teach (str "a namespace the episode touched states no PURPOSE. Its inventory is"
                " DERIVED — query_project, the module surface and the outline all"
                " list its forms — so the docstring is for what no tool can derive:"
                " why it exists, what to expect inside, and how it relates to its"
                " neighbours. NOT a list of what it contains. review_scan :purpose"
                " answers the same question for the whole store; generated and"
                " empty namespaces are exempt (there is no author to ask)")}
   {:rule :bare-throw :grain :done
    :escape "return data / (ex-info …), or ^{:bare-throw-ok \"why\"} on the name when the exact exception type is required by something outside your control — a Java API contract, an InterruptedException, a test proving a non-ex-info gets masked (it polices itself: a marker on a form with no bare throw is reported stale)"
    :teach "ANY fn throws a freshly-constructed non-ex-info exception. The cost is not tidiness: a bare exception can only be caught by TYPE, so a caller handling one failure catches a whole class and swallows every unrelated bug with it. Measured: a registration call that caught \"the server is not there\" reported the project ABSENT whenever ANY bug fired inside it, because both arrived as the same type. Give the throw ex-data and the catch can be narrow"}
{:rule :key-not-returned :grain :done
    :escape "fix the assertion to read a key the callee returns, or drop it — a read of a key the callee never returns is always nil"
    :teach "(:k local) where local is bound to a call whose statically-known return shape has no :k — a vacuous assertion that stays green no matter what the code does (assertions-that-cannot-fail)"}
   {:rule :stale-reference :grain :done
    :escape "fix the prose (or the reference) so the name resolves — the text is teaching, and teaching that lies costs a failed call to discover"
    :teach "a docstring/teach-string names a.b/c where namespace a.b is in this store but has no form c — a rename or move left the prose behind (gates never see a var inside a string)"}
   {:rule :direct-http :grain :done
    :escape "call slopp.http.client/request, taking it as a PARAMETER so callers can pass client/fake-requester — or ^{:adapter \"http — why\"} on the name if this form IS the adapter (it polices itself; the value's first word names the port, so a \"postgres\" adapter is ignored rather than called stale)"
    :teach "a form reaches the network itself — a java.net.http.HttpClient, or a slurp of an http(s):// literal. Raw reaching belongs in a declared ADAPTER; everything else goes through the port and inherits its fake and its contract suite. TESTS ARE NOT EXEMPT: calling the port from a test still makes a REAL call, so an exemption would buy nothing and would carve out the one place this boilerplate breeds. Scoped to HTTP because a gate may only demand a port that EXISTS — slopp ships one for HTTP and none for files or subprocesses"}
   {:rule :http-generated-ns :grain :form
    :escape "regenerate via generate_client after changing the ENDPOINT (its :rest/request/:rest/response), strip the ^:generated marker to take manual ownership, or dial it down (config_file {path \"rules\" key \"http-generated-ns\" value \"advisory\"})"
    :teach "a ^:generated form is generate_client's output and must not be hand-edited — regeneration rewrites the whole client namespace, so a hand edit is lost on the next generate (D-web-contracts part 2)"}
   {:rule :webapp-page-reach :grain :done
    :escape "move the view/derive code the entry reaches into a :jvm or :cljc namespace and pass the browser-shaped parts IN (:fetch, :render, a url pusher); or drop the ^:app/entry marker if this app is not meant to be reviewed headlessly"
    :teach "a ^:app/entry entry REACHES a :cljs namespace, so no JVM can open the app — the screen tool and every headless test fall back to a hand-built lookalike, which passes while the real screen is wrong. http-page-unreachable refuses the ENTRY's own shape at the write; this is the reach. At done it sees only pages you CHANGED — the dependency-flip case (some other namespace declared :cljs, no write to the entry) is reported by module_platform itself at the declaration (:stranded-pages) and re-graded by the full_check sweep (inert until http.enabled)"}
   {:rule :rest-stale-client :grain :done
    :escape "run generate_client to re-derive the client from the current endpoints, or accept the drift"
    :teach "the generated typed client is stale — an endpoint's contract changed since generate_client last ran (D-web-contracts part 2)"}
   {:rule :rest-inline-schema-dup :grain :done
    :escape "extract the shared inline schema to a named .cljc var both endpoints reference, or accept the duplication"
    :teach "2+ endpoints declare the same inline request/response schema — a shared shape belongs in one named .cljc schema so the server and the generated client agree (D-web-contracts part 2)"}
   {:rule :rest-unconstrained-contract :grain :done
    :escape "name the entries — [:map [:kind :string] [:text :string]]. If the endpoint genuinely CANNOT constrain — a proxy forwarding another service's bytes — say so with ^{:rest/unconstrained-ok \"why\"}, which discharges it and is itself reported as stale once the contract does constrain. Saying :any is HONEST and does not discharge: it is the reported state, not the way out"
    :teach (str "a published endpoint declares a field that constrains nothing"
                " — a bare :map, which accepts any map, or :any, which accepts"
                " anything. The cost is not vagueness but a SILENT mechanism:"
                " the generated client validates every response against the"
                " published schema, so a field whose shape can change without"
                " failing validation has the one thing built to catch drift"
                " pointed at it and switched off. Measured — a :diff moved from"
                " [String] to [[String String]] and passed on every call for"
                " weeks. Both are reported and they are not the same offence:"
                " :any ADMITS it says nothing, a bare :map looks like a type"
                " while saying the same thing")}
   {:rule :rest-undocumented-contract :grain :done
    :escape "add :doc (or :description) to the entry's property map — [:total {:doc \"hits before the limit is applied\"} :int] — or accept it; this is advisory and never blocks"
    :teach (str "a published endpoint's :rest/request/:rest/response schema has"
                " fields that say nothing about what they ARE. A type is a"
                " SHAPE, not a term of the contract: :total :int does not say"
                " the number counts hits before the limit is applied, and the"
                " document is all a consumer generating a client can read —"
                " the schema def's docstring is not a value and does not"
                " travel. Malli entry properties are open and cross the wire"
                " untouched, so this needs prose rather than a feature."
                " WHOLE-STORE by construction: a published contract is stable,"
                " and a check whose population is the episode's changed forms"
                " cannot see what has stopped changing")}
   {:rule :shell-widening :grain :done
    :escape "move the effect into an existing SHELL namespace and keep the pure part in core, or accept the widening (it asks once)"
    :teach "this episode declared a namespace :external/:internal — the functional CORE got smaller, and only you know whether it had to"}
   {:rule :tier-governance :grain :done
    :escape "declare the moved namespace's OWN tier (module_purity, namespace path — most specific wins), or move the effects out of it"
    :teach "a namespace this episode RENAMED or relocated now sits under a stricter tier by prefix, and its forms exceed it. Tiers are inherited, so the move — not any write — put this code under a rule it cannot satisfy, and the write-time gate only ever sees forms a write touches: nothing would re-check it until someone happened to edit one"}
      {:rule :module-governance :grain :done
    :escape "declare the edge (module_dep), hoist the target into its module's surface (^:export on the defn name, or ^{:export \"prefix\"} for a subtree), or restructure the call"
    :teach "a relocation this episode made left a call that breaks a module rule — usually a rename taking a namespace from two segments to three, which makes it package-private while its callers stay outside. The namespace that MOVED is not the one reported: the caller is, and the caller never moved. Module rules are inherited from the NAME and enforced at write time, and ns_rename rewrites its own callers, so nothing re-checks them — the operation most likely to drift the architecture is the one the architecture's check cannot see"}
   {:rule :tracked-file-drift :grain :done
    :escape "file_put the working-tree copy if a human edited it, or project/pull if the store's copy is the newer — reconcile deliberately, in one direction"
    :teach "a tracked manifest file differs from the real file the human branch carries at the same path — the one fact this system keeps two copies of, and nothing compared them until build.clj drifted far enough to reintroduce a fixed jar-corruption bug downstream"}])

(defn ^:export rule-rows
  "The rule catalog with each rule's DECLARED default `:severity` merged in from
   `declared` — a `{rule-key severity}` map, built by `rules/declared-severities`
   from the two registries that own the fact. Read this, not the raw
   `rule-catalog`: the catalog holds only what the registries cannot (the prose
   `:teach`/`:escape` and the `:grain`), so the severity a reader sees is the one
   enforcement uses. It used to be a column here too, and the two disagreed
   silently — `query_rules` reported a catalog claim while `gate-check` refused
   by default regardless.

   `declared` arrives as DATA rather than being fetched here: this namespace is
   `:pure` and the registries live behind an `:external` one, so reaching for
   them would be a core→shell dependency — a tier claim this namespace does not
   earn."
  [declared]
  (mapv (fn [r] (assoc r :severity (get declared (:rule r)))) rule-catalog))

(def ^:export rule-severities
  "Every severity a `rules` dial may carry.

  `:off` skips the rule; `:advisory` warns and proceeds; `:refuse` blocks a
  write; `:error` fails a done point. Not every value means something at every
  GRAIN — a write gate has no use for `:error` and reports it as `:refuse`,
  which `rules/query-rules` documents — but all four are legal to write, and
  refusing a legal-but-remapped value would be a second rule to learn for no
  gain."
  #{"off" "advisory" "error" "refuse"})

(defn ^:export config-refusal
  "The `rules` config write gate: a teaching error for a key naming no rule or a
  value outside [[rule-severities]] — nil when the write may land.

  **There was never a missing registry, only a write path not consulting one.**
  This catalog names every rule; the severities are four words. Until this
  existed the `rules` path recorded whatever it was given and said so honestly
  (`:unverified [:schema]`), which meant a renamed dial and a MISTYPED dial were
  the same event: both accepted, both governing nothing, neither reported.
  `http-unsafe-get` and `http-unsafe-gets` were indistinguishable. Reported by
  slopp-ui after five rules were renamed out from under any dial naming them.

  A dial that silently does nothing is the nil-pun this registry exists to kill,
  and it is worse here than elsewhere because a dial is set ONCE and never
  re-read: the moment it stops matching, the rule returns to its default and
  nothing in any report mentions it.

  **Near misses are found by shared SEGMENTS rather than by edit distance**, and
  that is aimed rather than lazy. The two mistakes that actually happen are a
  typo, which differs by a character (`http-unsafe-gets` CONTAINS the real key),
  and a rename, which keeps the tail (`http-stale-client` → `rest-stale-client`
  shares `stale-client`). Segment overlap catches both and cannot suggest
  something unrelated — the failure mode that matters, since a wrong suggestion
  sends the reader to change the wrong thing and offering none beats offering a
  stranger."
  [k v]
  (let [k     (str k)
        names (map (comp name :rule) rule-catalog)]
    (cond
      (not (some #{k} names))
      (let [segs  (set (str/split k #"-"))
            close (->> names
                       (map (fn [n] [n (count (filter segs (str/split n #"-")))]))
                       (filter #(pos? (second %)))
                       (sort-by (comp - second))
                       (map first)
                       (take 3))]
        (str k " is not a rule, so this dial would govern nothing — and a dial"
             " that does nothing is the worst kind, because it is set once and"
             " never read again."
             (when (seq close)
               (str " Did you mean " (str/join " / " close) "?"))
             " query_rules lists every rule with its grain and effective"
             " severity."))

      (not (rule-severities (str v)))
      (str (str v) " is not a severity — a rules dial takes "
           (str/join " / " (sort rule-severities))
           ". query_rules shows what each rule is set to now."))))
