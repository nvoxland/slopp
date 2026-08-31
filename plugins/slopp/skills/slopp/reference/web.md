<!-- reference topic `web` — served whole by `help {topic "web"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## Web applications (D-web)

Opt in once: `config_file {path "capabilities" key "http.enabled" value
"true"}`. A store that never opts in has no web surface and no web rules.

**An endpoint is one `defn` carrying its whole contract in name metadata** —
no route table, no macro:

```clj
(defn ^{:http/method :get
        :rest/path   "/api/users/:id"
        :http/auth   [:group "admin"]
        :http/reads  {:user [:user/by-id [:path-params :id]]}
        :malli/schema [:=> [:cat Req] Resp]}
  get-user "One user." [{:keys [path-params] :http/keys [reads]}] …)
```

Request/response maps are RING-shaped (`:request-method` `:uri` `:body` /
`:status` `:headers` `:body` as data); everything slopp adds is namespaced by
the CAPABILITY that reads it — `:http/*` for the server's own vocabulary,
`:rest/*` for a typed contract, `:webapp/*` for the browser's routing. `query_surface` lists the whole surface in its `:http` section: every method,
path, policy, handler, the declared `:rest/request`/`:rest/response` contract,
and the derived effect/read vocabularies.

**Write gates** (all inert until `http.enabled`; dial via `rules` config):
- every endpoint DECLARES `:http/auth` — `:public` is typed out, never implied
- every endpoint TYPES its contract — `:rest/response` (all) and `:rest/request`
  (body methods `:post`/`:put`/`:patch`) — a `.cljc` malli schema VAR
  (`some.contracts/order`: shared, refs-visible, and the input to the generated
  client — the paved road) or an inline `[:map …]` for a one-off (D-web-contracts)
- one method+path has one owner (collision refuses at the write)
- a `:get`/`:head` endpoint is SAFE: no `:http/effects`, no reachable mutation
- `:http/effects` may only name kinds a `^{:http/effect <kind>}` performer
  provides

**Keep handlers pure.** Reads: declare `:http/reads {alias [<kind> <req-path>]}`
naming a `^{:http/read <kind>}` performer — the framework fetches BEFORE the
handler, so a unit test just passes the value. Writes: RETURN
`{:http/effects [[<kind> & args]…]}` as data and let the dispatcher run the
marked performer — the test asserts `=` on data, no mocks. An endpoint that
must perform effects directly declares `:http/effectful true` (ON the name,
with the rest of the contract) and lives in an `:external` namespace (the
escape, not the default); its dependencies arrive as `:http/deps` on the
request, never as ambient state.

**For many web projects you do not run it — slopp does.** slopp boots a
DEDICATED image for your app, loads the web surface into it, and re-serves at
every `done` point; `session_brief` carries the url as `:app`. Where that
applies you write no `serve!` call, no namespace list and no port — all three
are derived from the store, so they cannot disagree with the gates.

**There is no switch, and you do not need one.** Whether slopp manages your
server is DERIVED: it does, unless the calling process already serves every
namespace your store would — true of exactly one store, slopp's own, whose web
surface *is* the API the live session already serves. A project cannot answer
this wrong because it is never asked. (It used to be a `dev.server` capability.
The only adopter who ever set it set it to work around 404ing assets, and the
switch then made a bug look like a preference for a week.)

`http.static.*` mounts and handlers taking `:http/deps` both work — the generated
call carries the mounts and calls your context builder.

**One real gap: `:http/auth-config` is not carried,** so an app using it gets a
managed server on which identity does not resolve. There is nothing to
configure around it; if that is you, say so rather than working around it.

**Handlers taking `:http/deps` DO work — declare the builder, and slopp
insists.** Mark one zero-arg fn `^{:http/context true}`; slopp calls it and
passes the result as `:http/perform-ctx`, handlers receive it as `:http/deps`,
performers as their first argument. Exactly one per store (a singleton, unlike
performers, which are keyed by kind). It cannot be a performer — performers
already RECEIVE the context, so it is upstream of that vocabulary. Writing an
endpoint that reads `:http/deps` into a store that declares no builder is
REFUSED (`http-undeclared-context`): nil deps either 500 or, worse, answer 200
with an empty body, and `generate_client` consumes the empty one as a success.
An app that runs its OWN `serve!` should mark the builder it already has and
call it — two definitions of one store's context agree right up until one
gains a key.

**But the context does NOT survive a refresh.** Each `done` boots a fresh app
image, so the builder runs again and anything it allocated — an atom, a pool,
a cache — is new. An app accumulating state there will find it silently empty
after any done point. Keep live state outside the context, or opt out. Note
what that means for the obvious workaround: a builder returning `{:registry
(atom {})}` allocates per CALL, so only a top-level `(defonce registry (atom
{}))` the builder REFERENCES could survive anything. That is deliberately the
shape `ambient-state` flags — advisory, with "a legit top-level cache" as its
named escape, and this is one of them.

**The builder is ZERO-ARG, so it cannot double as a test seam.** If your only
end-to-end seam was context construction — a `serve!` arity taking a fake
collaborator, say — the builder becomes the single source of the context and
simultaneously stops being parameterisable. Two ways out, both fine: inject at
the HANDLER (`(http/handle! … {:http/deps {:registry … :requester fake}})`,
which is below the builder and unaffected), or have your own `serve!` call the
builder and merge an override over it, which keeps one definition and keeps
the seam. Obvious once said, and not before.

Where it does apply, three things worth knowing:

- **`done` is the grain, not the write.** Mid-episode your store is
  intentionally incomplete, so a browser reloading on every write would show
  you a broken app repeatedly. A RED `done` still refreshes — looking at the
  app is part of finding out you were not finished.
- **A store that will not load leaves the previous version serving**, and
  says why. "Always up" and "up to date" only conflict when a boot fails.
- **The app image carries YOUR deps, not slopp's.** Code that works because
  slopp happens to have a library on its classpath fails the moment you look
  at the page instead of when someone deploys — but that signal depends on
  the page working, so it is worth nothing until the exceptions above are.

`http.port` pins the address; unset, it is derived from the store dir so two
projects on one machine never collide.

**The runtime underneath: `slopp.http`.** `(http/serve! {:http/namespaces
['my.api] :http/port 8080})` scans the namespaces' var metadata (the same
contract the gates enforced) and serves on http-kit (`:http/adapter :jdk` =
zero-dep fallback) — that is what a deployed build calls, and what the dev
server calls for you. Tests never need it: `(http/handle! (http/context
{:http/namespaces ['my.api]}) request-map)` runs the ENTIRE pipeline — route,
policy, declared reads, handler, effect interpretation — portlessly. In-handler
guards: `(http/enforce (= owner sub))` throws a 403-mapped ex-info (no bang —
your handler stays analyzer-pure); `(http/authorized? policy identity)`
answers booleans. Test namespaces' endpoint-shaped forms are FIXTURES —
they neither report in query_surface nor claim paths.

**A route that DECLARES a contract may only be served by a context that
HONOURS one.** The moment any endpoint carries `:rest/request` or
`:rest/response`, both calls above take `:http/wrap-context rest/validating`
— the one call that turns a declared contract into an enforced one — and
`context` REFUSES to assemble without it, naming the routes. Assemble it in
ONE function that your tests and your `serve!` both call, rather than at each
site: an app that can be stood up two ways will eventually be stood up both,
and only one of them will validate. Not hypothetical — a real app's test
started a server, asserted 200, and passed for as long as the served endpoint
had been answering 500, because the production path wrapped and the test path
did not.

**Both halves of the URL are addressed the same way.** The dispatcher puts
`:path-params` AND `:query-params` on the request (the query string is
parsed once, there — don't split `:query-string` yourself), so a declared
read reaches a query parameter exactly as it reaches a path one:
`:http/reads {:page [:my/page [:query-params :view]]}`. Needs both? Declare
the read over the whole request with `[]` and destructure. A parameter the
page cannot honour should be a 404, not a silent fall back to the default —
otherwise a link means "whatever the default became" the day you add a
second value.

**Security posture the runtime enforces** (not just the write gates): auth is
default-deny and an empty `[:all]`/`[:any]` policy DENIES; the dispatcher
bounds a response's effects to the route's declared `:http/effects` (a handler
cannot emit an undeclared kind, even one a performer provides); error bodies
are redacted — an `ex-info` with `:http/status` surfaces its message plus only
a `:http/public` allowlist, anything else is a generic 500 (detail logged, not
returned); request bodies are capped (default 1 MiB — thread
`:http/max-body-bytes` from the `http.max-body-bytes` capability into
`serve!`); the static asset reader contains paths under its root. Auth: static
passwords are salted PBKDF2 (`slopp.http.auth/hash-password` — mint one with
`query_eval`, it is not on the `slopp.http` facade), bearer and
password compares are constant-time, and **OIDC requires a configured
`http.auth.oidc.audience`** — an unset audience denies every token (a resource
server must not accept cross-audience tokens). Row-level authz is still yours:
slopp does not taint-track a handler returning another tenant's rows.

**HTML is hiccup, and `:http/path` serves a stored VALUE (D-web-html).** A
content endpoint is a **`def`**, not a `defn` — its value IS what is served:

```clj
(def ^{:http/method :get :http/path "/about" :http/auth :public}
  about
  [:main [:h1 "About"]])                       ; hiccup → rendered, text/html

(def ^{:http/method :get :http/path "/robots.txt" :http/auth :public
       :http/media-type "text/plain"}
  robots
  "User-agent: *\nDisallow:\n")                ; anything else → served as it stands
```

**An endpoint and a performer must be PUBLIC.** The served route table and the
performer vocabulary are both built from `ns-publics`, so a `defn-` or
`^:private` carrying `:rest/path`, `:http/path`, `:http/read` or `:http/effect`
declares a surface and contributes nothing — `http-unreachable-declaration`
refuses it. A private route 404s on a path `query_surface` lists; a private
performer answers **500**, on a request the store believes it serves, with a
stack trace naming the framework rather than your form. Want the implementation
private? Put the marker on a public wrapper that calls it — that is the shape
left open, and the only one: there is no dial, because no waiver changes which
vars get served.

**Dynamic means API.** `:rest/path` is the marker for anything that computes an
answer from a request; `:http/path` names content that does not vary. A `defn`
under `:http/path` is not a handler slopp calls — it is a value slopp serves,
so it would render as the string of its own function object. **`http-content-shape`
refuses that at the write**, naming both ways out: `:rest/path` if it computes,
a `def` if it does not. One direction only — `:rest/path` on a `def` throws on
the first request, which is loud and implicates the right form, and a gate
exists to convert silence.

`:http/media-type` is used VERBATIM when declared; hiccup defaults to
`text/html`, everything else to `text/plain`. There is no extension-based
guessing: a def named `robots.txt` is a var, not a file.

**A VECTOR means hiccup, so one declaring a non-HTML media type REFUSES.** The
case that needs it is a stylesheet: garden rules are vectors too, so `(def
^{:http/media-type "text/css"} style [:main {:margin 0}])` is valid hiccup,
renders to `<main margin="0">`, and serves 200 with the right header and a
nonsense body. Render at def time — `slopp.http.css/render` answers a string,
which is what a stylesheet's value should be.

`slopp.http.html/render` serializes hiccup (hiccup 2.x underneath,
escape-by-default) and **prepends `<!DOCTYPE html>` to a top-level `[:html …]`**,
so an app writes its whole document — title, meta, stylesheets, all of it —
with no shell helper and no parameters for any of it. Content responses carry
the hiccup they rendered from as `:http/hiccup`, which is what lets `cljnx`
drive a page as STRUCTURE rather than as escaped markup. (`html-response` is
the same wrapper for a value you build in code.)

**A single-page app declares `:webapp/shell true` on its document, and the
framework completes it**: the `<script>` goes at the end of the `[:head …]`
you wrote, and the mount prefix is stamped on your mount point as `data-base`.
**The bundle URL is DERIVED**, from the compile output joined to the static
mount that serves it — it used to be the marker's value, so every shell
repeated a fact only the build knows and a rename of the mount left a
`<script>` pointing at a 404. Those are the only two things it adds — they are the two facts a
stored value CANNOT hold, since an app served at `/p/x/store` cannot tell that
from its own url. Your side of the contract is a `[:head …]` and a mount point
(`[:div {:id "app"}]`, or the `#app` shorthand); a document missing either
REFUSES when the app is assembled, because the symptom is a blank page and a
blank page implicates everything.

**Pass `:webapp/routes` when you assemble the context, or your wildcard shell
lies.** A `**` shell covers every address beneath it — that is what makes a
refreshed deep link work — so without a client route table the whole subtree
answers **200**, and a typo, a stale asset url and a real page become
indistinguishable at the server. An app that can never 404 has no way to say
*no such thing*.

Hand the context the table your pages already declare, and the STATUS is
derived while the BODY is not:

```clojure
(slopp.http/context {:http/namespaces [...]
                     :webapp/bundle "/js/main.js"
                     :webapp/routes [["/things" 'app.ui/things]
                                     ["/things/:id" 'app.ui/thing]]})
;; /things/42  → 200 + shell
;; /things/oops/typo → 404 + shell   (same bytes)
```

Same document either way: the SPA boots, finds an address its own table does
not match, and renders whatever not-found it wants. **Rendering stays your
job; telling the truth becomes the server's** — a crawler, a link checker, a
`curl -f` and a monitor all read status and never see your page.

The alternative people reach for is a hand-maintained list of route prefixes,
one marker per section, so the statuses stay honest. That list goes stale, and
it is a second copy of something your `^{:webapp/path …}` markers already say.

Omitting `:webapp/routes` is still legal and still answers 200 everywhere —
the compatibility answer, for a shell written before this existed. It is not
the one to choose on purpose.

The rules that matter:

- **Attrs are position 2, always a map or absent.** Compute conditional
  attrs — `(cond-> {:class "todo"} done? (update :class str " done"))`;
  `(when p {…})` in position 2 is a vanishing CHILD when p is false.
- **Everything escapes; never pre-escape.** `[:html/raw s]` is the ONE
  bypass (string payload only). The renderer refuses crafted tag/attr
  NAMES and `javascript:`/`data:` URLs in `href`/`src`/`action` — those
  survive escaping, so they throw instead.
- **A vector is an element; a seq splices.** Repeat with `for`/`map`;
  never group siblings in a vector.
- **No React names** — `:class` not `:className`, `:for` not `:htmlFor`,
  no `:onClick`-style handlers (the `http-react-attrs` gate refuses them:
  browsers silently ignore unknown attributes, so the mistake ships and
  does nothing).
- **Component-per-defn.** A thin page shell composing small component fns —
  that is the merge grain, the test grain, and each component stays
  `=`-testable data.
- **Check `query_surface` before writing a link or form path.** Literal
  `:href`/`:action` values are INDEXED: route rows carry `:rendered-by`
  (who links here), and `done` fails on a path nothing serves
  (`http-dangling-route-refs`). `(str "/prefix/" x)` checks by prefix; a
  fully dynamic path is reported `:unresolved`, never counted clean.
  Served by something outside this store? `^{:http/external-path "why"}`
  on the rendering form discharges.
  **A store that calls `slopp.webapp/prefix-links` itself makes this join
  impossible**, and is TOLD so rather than being handed false findings: an
  `:href` literal is then in APP space while the route table is in SERVER
  space, and the prefix between them is route state. Every literal lands in
  `:unresolved` carrying that reason. The way back to a working check is to
  write links against the routes your app DECLARES — under a root shell the
  base is empty, so there is nothing left to rewrite.
- **See a page without a server:** `(http/handle! (http/context
  {:http/namespaces ['my.ui]}) {:request-method :get :uri "/x"})` via
  `query_eval` — the full pipeline, rendered HTML in the response map.
  Test on data first (call the handler with a synthetic `{:http/reads …}`
  request); pin one rendered string per component. Under `--live`, an
  edited page hot-serves — browser F5, no build step.

**CSS is garden — the same story for stylesheets (`slopp.http.css`).** A
stylesheet is a `defn` GET endpoint returning `css-response`; rules are
garden data (`[:main {:margin "0 auto"}]`, nested `[:main [:a {…}]]`,
`garden.stylesheet/at-media` for `@media`). `render` serializes minified
and validates every selector/value string against block-breakout (`{ } <`
throw — garden renders strings verbatim, so an interpolated value is an
injection door; `;` is allowed because data URIs use it). Serve it, then
`[:link {:rel "stylesheet" :href "/styles/app.css"}]` in your document's
`[:head …]` — that `:href` is a literal, so `http-dangling-route-refs`
ties the link to the stylesheet endpoint like any other route. Raw or
vendored CSS goes through a static `.css` asset (`file_put` + an
`http.static.*` mount), not the renderer.

**Client code is ClojureScript — same store, compiled to JS (D-web-cljs).**
Browser logic is authored like everything else: forms in the store, edited by
the same tools, gated by the same dialect. A namespace declares its target
**platform** — `module_platform {module platform}`, or born at creation with
`ns_create {ns … platform}`:

- **`:jvm`** (default) — ordinary Clojure, loads into the oracle, never
  compiled to JS. Everything you already write.
- **`:cljc`** — `.cljc`, loads on the JVM (`:clj` branch) AND compiles to JS.
  **This is where testable logic goes.** A `:cljc` form is verified by the JVM
  oracle for FREE, exactly like Clojure — write a `-test`, red→green as usual.
- **`:cljs`** — `.cljs`, client-ONLY (`js/*`, the DOM). Never loads into the
  oracle; a write lands `:unverified` with reason `:cljs-deferred-to-compile`.
  It is verified by the COMPILER, not the suite.

**The discipline that makes this work: keep platform-neutral logic in `.cljc`
so the free JVM oracle covers it; reserve `.cljs` for the thin, genuinely
browser-bound edge (DOM, events).** A predicate, a schema, a state transition
belong in `.cljc` and get red/green; only `js/document`-touching glue is
`.cljs`, and it stays small because a cljs-only form is verified by "it
compiled" alone until browser tests exist (out of scope — that is
Cypress/Playwright territory someday).

- **Compile with `compile_client`** — it compiles every `:cljc`+`:cljs`
  namespace with the configured backend (real ClojureScript, **on the JVM, no
  Node**) to one `:simple` bundle, recorded as a served blob (default
  `public/cljs/main.js`, served at a URL you choose via a static mount).
  Compile-error-as-oracle: analyzer warnings and hard errors are anchored to
  the owning store form. **Do not write the `<script>` yourself** — declare
  `:webapp/shell true` on the shell document and the framework injects it into
  the `[:head …]` you wrote, with the mount prefix beside it. A top-level
  `(defonce _ (main))` self-starts the bundle, so the page needs no inline JS.
  **You do not write the bundle URL either.** It is derived from the compile
  output and the static mount that serves it, so there is nothing to keep in
  agreement: rename the mount and the `<script>` follows. A shell assembled
  with no mount serving the bundle REFUSES, which is the failure a typed url
  used to turn into a script tag pointing at a 404.
  **Address it by what it IS, not by what built it** — mount the bundle at
  `/js/main.js`, not `/assets/cljs/main.js`. A URL ends up in bookmarks and
  caches; `cljs` names a toolchain you might change, and nothing about serving
  JavaScript changes if you do.
  **A `:src` you write in hiccup is a route reference** —
  `http-dangling-route-refs` checks it like an `:href`, so a script or image
  you link but never mount fails `done` instead of 404ing silently.
- **slopp provisions its OWN toolchain — you never `deps_add` the compiler or
  malli.** There are TWO dep configs: **yours** (the `deps_add` manifest —
  application libraries, delta-tracked, in `deps_list`) and **slopp's** (the
  compiler + malli), which slopp injects **at build time**, versioned centrally
  with slopp. They never enter your manifest, never appear in `deps_list`, and
  never land as deltas in your history — so a slopp upgrade moves every store
  forward with no migration. The compiler goes to the build-only `:cljs` alias
  (never hot-loaded, never in the runtime jar or native binary); malli to the
  build's `:deps` (the `:cljs` alias inherits it, so one entry serves the JVM
  oracle, the external tier, and the compile). Injection happens only when the
  store HAS client code. The compiler backend is a per-project config
  (`config_file {path "client" key "compiler"}`, default `:clojurescript`) —
  cherry/squint are future backends, same source.
- **An external JS library goes through `js_dep`, and declaring IS vendoring.**
  `js_dep {name, version, format, global, file, source}` records the library
  AND stores its bytes in one act — `:source` is a path to bytes you already
  fetched, because there is no npm client in the loop. slopp writes a
  `deps.cljs` at compile time, so `(:require [roughjs :as rough])` resolves
  through `:global-exports` to the browser global. `format` is `:iife`/`:umd`
  (concatenated into the bundle) or `:esm` (import map). The bytes are an
  ARTIFACT — sha and a `{:kind :download :npm …}` recipe in the journal, bytes
  on disk — so a vendored library never bloats your delta log.
- **Test the library boundary by testing the DATA, not a fake of it.** A
  hand-written fake needs a contract suite run against the real thing to stay
  honest, and slopp's oracle cannot run JS — so the fake could never be
  checked. Keep the analysis and the emitted structure in `.cljc` where the
  oracle verifies them at full speed, and let the `.cljs` adapter be thin
  enough that reading it is the review. `compile_client` is the only oracle
  the JS side gets; that is a reason to put almost nothing there.
- **Read platforms at a glance** with `query_depends {modules true}` — a
  `:platforms` map names the `:cljs`/`:cljc` namespaces (undeclared = `:jvm`).
- **Share real logic AND libraries in `.cljc`** — a malli schema in `.cljc`
  (`m/validate`) is JVM-verified by the oracle here AND compiled into the
  browser bundle, so the same contract checks both sides. Keep the schema and
  any pure transform in `.cljc`; the `.cljs` stays thin.
- **In `.cljs`, definitions must PRECEDE their callers.** There are no
  top-level forward declarations, so a helper written below its caller
  compiles to an undeclared-var warning. The pipeline arranges definitions
  before their callers at every write, in `.cljs` exactly as in `.clj`, so
  nothing is yours to do here — write the forms in any order.

### Non-trivial apps: a REST API and an SPA that consumes it

For anything past a few pages, the shape that keeps SCALING is **a JSON API
with declared contracts, consumed by client-side code** — not HTML assembled
on the server for the browser to slot in. The reason is not fashion: the API
is an explicit, testable boundary. One call (`query_surface`) answers what the
app can do, each endpoint is `=` on data with no mocks, and the frontend
consumes a GENERATED contract instead of sharing the server's internals.
Server-rendered pages and static content stay fully supported — they just
stop being the assumption once the app grows.

- **Address the JSON surface at `/api/*`** and keep it separate from the
  pages. Same reason URLs name what they ARE: a reader (and an agent) can see
  the whole data surface without reading handlers.
- **Views are `.cljc`, so the SAME function renders on both sides.** The
  server renders it into the page; the client re-renders it after a fetch. One
  renderer means a click and a refresh cannot show different things — the
  drift that otherwise only a browser reveals.
- **Take the WIRE shape in a shared view**, not the server's shape. JSON has
  no symbols and no keywords-as-values; a view taking the server's shape
  renders correctly on the server and renders `nil`s in the browser.
- **THE test of whether you got the split right: your UI tests are in-image**
  (~0.5 ms each), asserting on returned hiccup DATA — `(get-in v [2 1])`. If
  UI tests need the external tier or a browser, too much logic drifted into
  `.cljs`. That is the check on the whole discipline, and it is why a view
  that returns data beats one that returns a framework's component object.
- **The tell that you got it HALF right: every READ of your view state is
  `.cljc` and tested, every WRITE is `.cljs` and is not.** This is the most
  comfortable way to be wrong, because nothing looks missing — the rule
  deciding what a reader SEES is checkable, and only the rule deciding what a
  CLICK DOES is not. Those two have to agree or a toggle lies about its own
  state. Measured in a real app: four handlers, one of them extracted the
  read (`doc-open?`, `:cljc`, tested) and left the write inline
  (`(swap! state assoc-in [:show :doc k] (not (doc-open? (:show @state) k)))`).
  That one carried a deref-compute-swap race — nominal for a human clicking,
  and the first thing a programmatic driver hits.
- **Progressive enhancement beats a hard SPA when a server route exists.**
  Intercept plain left-clicks only — leave middle-clicks and cmd/ctrl/shift
  clicks to the browser, or the enhancement takes away open-in-a-new-tab. And
  on a failed fetch, fall back to a full page load: a stale pane under a new
  URL is the SPA failure mode that lies to the reader.
- **One list of served namespaces, not a literal per server.** Routes and
  `:http/read` performers can live in different namespaces (reads resolve by
  VOCABULARY, store-wide, so an API endpoint can reuse a page's read). A
  server given only half answers **500, not 404** — much harder to diagnose.
  If two servers mount the same app, they share one `def`.
- **Health, metrics and RPC transports are still APIs — declare them, and let
  the generator emit wrappers you may not call.** They used to be marked
  `^{:rest/client false}` to keep them out of the client; that flag is retired,
  because "this consumer does not call it" is not a fact about the endpoint,
  and excluding it also took it out of the app's published documentation. An
  unused generated wrapper costs a few lines in a namespace that is regenerated
  wholesale; a missing one costs the next consumer the endpoint entirely.

#### If you go all the way: no server-rendered pages at all

- **Serve ONE document** — head, an empty `<div id="app">`, nothing else —
  and declare `:webapp/client-routes` with the prefixes your client router owns.
  Note the prefix ROOT is not covered (`["/store"]` generates `/store/*`), so
  `/store` itself needs its own route.
- **Consequence to state out loud: every path under a declared prefix now
  answers 200.** Not-found moves into the client. A bad deep link that used
  to 404 now serves the document and the client renders "not found" after its
  fetch 404s. That is correct, and it is a real change in what your status
  codes mean. `done` says it once, for the episode that adds the declaration
  (`webapp-client-routes-consequences`), and `webapp-client-routes-are-served`
  reports any client route the server does NOT answer on a hard load — the
  failure that is invisible from inside the app, because clicking to it works
  and only a refresh or a shared link breaks.

**Everything below the declaration is `webapp`'s, not yours.** Turn it on
(`config_file {path "capabilities" key "webapp.enabled" value "true"}`) and
declare the app; slopp owns the loop.

```clojure
;; A PAGE declares its own address. Nothing lists the addresses anywhere
;; else — a build reads these markers to write the browser's route table,
;; `/api/webapp/paths` publishes them, and a write gate refuses two pages
;; claiming one address.
(defn ^{:webapp/path "/things"} things-page
  "Every thing, listed."
  [page]
  [:main (for [t (:value (webapp/ask! page api/things {}))] [:li (:name t)])])

(defn ^{:webapp/path "/things/:id"} thing-page
  "One thing."
  [{:keys [params] :as page}]
  (let [{:keys [status value]} (webapp/ask! page api/thing {:id (:id params)})]
    (if (= :ready status) [:main (:name value)] [:main "…"])))

;; the entry returns the DECLARATION — slopp wires it. Both entries derive
;; from this one value: `cljnx/driver-for` for a headless drive, `dom/mount!`
;; for the browser. Returning `(webapp/wiring …)` yourself is REFUSED, because
;; a wired map already carries the derived `:webapp/view`.
(defn ^:app/entry app []
  {:webapp/state  state                         ; your atom
   :webapp/chrome (fn [state inner] [:div [nav state] inner])})
```

- **A page declares its address, and the entry declares no table.** The
  address used to appear twice — a `^{:webapp/path …}` marker AND a
  `:webapp/routes` row — with nothing to notice when they disagreed. Both
  entries derive the table from the markers now: the browser's is generated by
  the build, the headless one is scanned off the loaded vars. A declared
  `:webapp/routes` still wins, for an app with a reason to build its own.
- **A page is a FUNCTION of one map**: `{:state :params}` plus everything the
  app declared. There is no `:screen` keyword agreeing in three places —
  routes returning it, a view casing on it, a fetch receiving it — which is a
  rename away from a blank pane at a url that looks right.
- **A page ASKS for what it needs, while rendering**: `(webapp/ask! page
  descriptor params)` answers `{:status :value}` and STARTS the load if nobody
  has. Start-if-absent is what makes it safe to call from a render — the page
  re-runs when the answer lands and finds it already there. Ask for as many as
  you like, wherever you like; there is no per-screen `:request` slot to fit
  them into. `(webapp/stale! page descriptor params)` drops one so the next
  ask re-fetches, which is what you call after a write.
- **An endpoint is ONE var — a DESCRIPTOR — and a request is built from it.**
  `generate_client` emits `(def form {:http/method :get :http/path
  "/api/form/:id" :http/params #{:id :depth} :rest/response contracts/…})`, and
  `(slopp.http.endpoint/request form {:id "f1"})` answers `{:http/method
  :http/url}` plus `:http/body` and the contracts. The address is RESOLVED
  there, once: both performers — `fetch` in a page, `slopp.rest.client/call!`
  on a server — receive a finished url and decide nothing about it. Add
  `:http/headers` for a token, which is state rather than schema, so nothing
  about an endpoint declaration can produce it.

  It is a DEF rather than a function so the reference graph keeps seeing it:
  `query_depends` on a descriptor answers *which pages call this endpoint*,
  renames follow it, dead-surface counts it. A url typed at a call site is
  invisible to all three.

  **Two encoders, because a `/` means two things.** In a `:name` or `*` segment
  a slash is data trying to become structure and is escaped; in a `**`
  remainder it IS structure, so each sub-segment is encoded on its own and then
  joined. Getting that backwards stops the round trip closing in a way that
  reads as a missing form rather than a wrong url.

  **A param the descriptor does not name is REFUSED**, once, in
  `slopp.http.endpoint/request` — not copied into every generated endpoint. A
  descriptor with no `:http/params` enumerates nothing and guards nothing,
  because a guard built from a gap refuses what the boundary accepts.
- **A non-2xx is a FAILURE, and slopp is the one that knows.** `fetch` rejects
  only on a network error, so a hand-written performer hands a 500 to its
  success path and the screen renders the error page's body as data. That
  check lives in `:cljc` where a test watches it, which is the whole reason
  the performer is not yours.
- **A load REFUSES a bad answer with `:check`, never by throwing.**
  `(fn [response] -> nil | message)` — nil accepts, a message makes the load
  `:failed` carrying it, and `:xform` never runs. (`load!` is the layer under
  `ask!`; reach for it directly only when you are starting something `ask!`
  cannot express.) Validating inside `:xform` and throwing gives you two
  behaviours from one function: headless the performer calls `ok`
  synchronously so the throw escapes `load!` and takes the driver with it,
  while in a page it lands in the shim's `.catch` and renders a failure
  screen. A `:cljc` form cannot catch on both platforms — D3 denies the reader
  conditional — so the failure channel is a return value.
- **A DESCRIPTOR's path is JOINED against what you serve**
  (`webapp-request-paths-are-served`). Naming an endpoint that does not
  exist fails quietly — the url routes, the screen renders, one pane never loads
  while everything around it works, so it gets reported as slowness rather than
  as a missing endpoint. Two declarations stop it asking: a whole url for a
  third-party server, and `^{:http/external-path "why"}` on the form when
  something OUTSIDE your store serves the path — a proxied API under your own
  mount point cannot be written in full, because the prefix is runtime.
- **A request carries the MOUNT POINT, like every other address.** Write
  `/api/things` and slopp addresses it under your `:webapp/base`, for the same
  reason your `:href` gets it. An absolute url (scheme, or protocol-relative
  `//`) is left alone.
- **A request may name its OWN base, and the app's is only the default.**
  `(assoc (endpoint/request modules {}) :webapp/base (str "/api/p/" slug))` is
  measured from there instead. `:webapp/base` keeps the browser prefix on
  purpose — a MOUNT is the one genuinely browser-shaped thing in a request, and
  it is the only key the addressing step reads. **Which api a request belongs to is ROUTE STATE**, not
  configuration: a client-routed app switches which upstream it reads without a
  page load, and the app-level base is stamped once at load — so no value
  delivered that way can be right for an app that talks to more than one.
  **Give it the prefix your API is served under, not the one your PAGES are.**
  A client-routed app answers the SPA shell for everything below its own route
  prefixes, so a request measured from there gets `200 text/html` and the whole
  document — which reaches your JSON decoder wearing a success status. Measured
  on a real hub: `/api/p/<slug>/api/modules` is the project's JSON,
  `/p/<slug>/api/modules` is the shell. A 404 would have been the kinder
  failure.
  `:webapp/base ""` means the origin — which is what the retired
  `:webapp/from-origin` boolean used to say. **That flag is gone, not
  deprecated**: nothing reads it, so a request still carrying it is addressed
  under the app's base like any other.
- **The table is ADDRESSES, not screens** — a row's screen is not unique and a
  screen's row is not unique. One screen answers at several urls the moment you
  have a lens bar, a print view, an alternate rendering, or a detail page that
  also takes an optional segment. So anything reasoning "one row per screen" is
  wrong: a count, a completeness check, a generated index. If you want the two
  numbers, derive each from the table rather than asserting a literal — a real
  app watched `11` ratchet to `14` on one widening.
- **Prefer a ROW to a runtime table of what could exist.** The tempting
  alternative for a lens is to peel a trailing segment off, retry the subject
  underneath, and check the peeled part against a second table. Then that table
  has to reject `/things/bogus`, and it is one edit away from meaning "what
  could exist" instead of "what renders" — which is how five dead urls answered
  200 and drew a default view, with a reader certain they had looked at
  something they had not. With rows there is nothing to consult: an address no
  row matches is not-found, the same answer the server gives.
- **You do not write a view.** `:webapp/view` is derived and declaring one is
  refused. `:webapp/chrome` is your layout around a screen.
- **A screen is only called when its data is READY**, so it never writes the
  three-way case on load status. `:webapp/loading`, `:webapp/failed` and
  `:webapp/not-found` are declared screens with plain defaults, and chrome
  decides WHERE they sit — the framework will not guess at your layout.
- **Write client-route keys in your links** — `:href "/things/42"` — and slopp
  adds the mount point on the way to the DOM. Links you do not route are left
  alone, so `/api/…` still points at your server. A form's `:action` obeys the
  same rule and for the same reason: a search box written as a GET form is a
  NAVIGATION, and the route table already separates it from a POST to your
  server. Such a form submits as a full page load, which is correct — it lands
  on the client route and the app boots there.
- **On navigation, `:webapp/address-keys` are dropped with the route and every
  other key survives — LOADS INCLUDED.** A plain state key outlives a screen by
  saying nothing, which is what a nav rail wants. Do NOT read this as "state is
  wiped".
- **There is no session-load declaration, because there is nothing left for one
  to say.** A nav pane, a signed-in user, anything read on several pages: every
  page that shows it calls `ask!` for it, the first call starts it, and the
  rest find it already there. That deleted `:webapp/session-loads` and the
  membership rule it carried — which used to be honoured by `arrive` and by
  nothing else, so an app that navigated with its own code got no scope
  guarantee from declaring it.
- **Nothing evicts a load, so `stale!` is yours to call.** After a write that
  changes what an endpoint answers, `(webapp/stale! page descriptor params)`
  drops that entry and the next `ask!` re-fetches. An automatic sweep of what
  a render did not ask for is the obvious rule and it is exactly the kind slopp
  will not guess at: a wrong eviction shows an empty pane an app cannot explain.
  **Which `boot`?** `:webapp/boot` — the app's own pure `(fn [state] state)` —
  is unchanged. The `:boot` that gained a url is the one on the headless DRIVER
  contract, which `slopp.webapp/driver` derives for you; you write that
  signature only if you hand `slopp.cljnx/open!` a page by hand. The two names
  are one word apart and only one of them moved.
- **Read a load with `load-status` AND `load-value`, never value alone.** Four
  states — `:absent :loading :ready :failed` — because "nobody asked" and
  "answered nil" are different facts and `(if (:data s) …)` cannot tell them
  apart.
- **Actions are declared**, and there are three kinds: a plain state
  transition, `:effectful?` (a request through `:webapp/call`), and `:leaves?`
  (a full page load, for a destination that is not a client route). Declaring
  the kind is what stops a browser dispatcher and a headless one drifting
  apart. **All three see the VALUE** — `:webapp/act` and `:webapp/url-for` are
  both `[state action value]` — because a `<select>` has ONE handler for N
  options, so its choice cannot ride in the action vector the way a button's
  argument can, nor in an `:href` the way a link's can. That is what lets a
  dropdown whose choice IS the destination navigate on change:
  `(fn [_state _action slug] (when (seq slug) (str "/p/" slug)))`, with nil
  declining for the empty option. Without it the selection has to reach state
  first — and a `:leaves?` action does not run the reducer — so the app needs a
  second "go" button that exists only to read the state back.
- **Drive it headlessly**: `(cljnx/open! (webapp/driver app))`, then `visit!`
  the url a reader would type — mount point included — and `click!` a link.
  No browser, no compile, and the same functions the real page runs. **`visit!`
  takes the FULL url**, which is what is in the address bar; a drive that passes
  app-relative paths exercises a url no browser produces, and is correct only
  while the fixture's base is `""`.
- **Endpoint tests must ROUND-TRIP through JSON.** `http/handle!` returns the
  body as Clojure DATA — the adapter serializes — so a keyword sails through
  a `[:x :string]` contract in-image and reaches the browser as a string. A
  test that does not serialize is checking a value no client receives. Watch
  for vacuous validation too: `[:sequential …]` over an empty list checks
  nothing inside it, so give fixtures at least one real element.
- **`:uri` and `:query-string` are separate request keys.** Putting `?a=b`
  into `:uri` means no route matches, and the 404 you get looks exactly like
  the one you were trying to assert.
- **Send data, never markup.** Syntax highlighting ships as `[class text]`
  pairs and diffs ship as lines; the client decides what an element is. That
  is what keeps one renderer instead of two.
- **Dev loop (optional):** `config_file {path "client" key "auto-compile" value
  "true"}` recompiles the bundle after a client-ns write — ASYNC and
  non-blocking (single-flight + coalescing): the write returns
  `:client-recompiling`, and a `--live` server serves fresh JS once the
  background compile commits. Off by default. Also fine: `:cljs` forms can be
  renamed/moved/extracted like any code — the refactor ops handle them.
- **One benign rough edge:** the D6 `!`-effect warning fires on idiomatic cljs
  entry points (`^:export main` touches the DOM) — advisory, not a refusal.
  (Kondo lints each form in its platform's language, so `js/*` no longer draws a
  false "unresolved namespace" finding.)

**The typed client is GENERATED, never hand-written (D-web-contracts).** Once
your endpoints declare their `:rest/request`/`:rest/response` contracts (the write
gate requires it — see the D-web write gates), `generate_client` writes a stored
`:cljs` namespace (default `app.client.api`, set `client`/`generated-ns`) of
typed `fetch` wrappers — one fn per endpoint, validating params OUT and the
response IN against the SAME schema the server enforces. Call them from your
`.cljs`: `(api/create-order! params)` returns a promise; a wrong shape throws
before the request leaves. Rules of the road:
- **It's EXPLICIT** — run `generate_client` after changing an endpoint (like
  `compile_client`, not on every edit). A `rest-stale-client` done-advisory nudges you
  when a contract drifts from the last generation; with `client`/`auto-compile`
  on, the generate also refreshes the JS bundle.
- **NEVER hand-edit it.** Every wrapper is `^{:generated "<endpoint>"}` and the
  `http-generated-ns` gate REFUSES edits (regenerate instead; to take manual
  ownership, strip the marker). It's still fully inspectable — `query_source`,
  blast-radius, refs — and because the wrappers reference the schema VARS,
  "change a schema → every affected client call" falls out of the reference graph.
- **Schemas must be `.cljc`.** A `:rest/request`/`:rest/response` VAR the client
  ships has to live in a `:cljc` ns (so it compiles into the bundle AND is the
  one the server validates); `generate_client` SKIPS an endpoint whose schema
  isn't shippable and reports it in `:problems`. A `rest-inline-schema-dup` advisory
  nudges a shape shared across endpoints toward a named `.cljc` var.
- **Declare the entries, or the validator is off.** A field typed `[:sequential
  :map]` accepts any map, so the generated client's response validation — the
  thing built to catch drift — passes over it forever. Measured in the wild: a
  `:diff` moved from `[String]` to `[[String String]]` and nothing noticed for
  weeks. Name the entries (`[:map [:kind :string] [:text :string]]`), or declare
  `:any` if the shape genuinely is not settled — `:any` at least says so, where a
  bare `:map` looks like a type and admits everything. `rest-unconstrained-contract`
  (whole-store, advisory) lists them, and it is the PRIOR question to the one
  below: prose does not make a field real.
- **Put each field's prose ON the field.** A type says what SHAPE a value has and
  never what it MEANS — `:total :int` does not tell a caller the number counts
  hits BEFORE the limit is applied. Malli entry properties are open and travel
  with the schema, so `[:total {:doc "hits before the limit is applied"} :int]`
  reaches every consumer of the published contract; a docstring on the schema var
  does not, because a docstring is not a value. The `rest-undocumented-contract`
  advisory (whole-store, never blocking) lists the fields that say nothing.
  `:description` is accepted as malli's JSON-Schema spelling. **Build a long one
  with `(str …)`** — unlike a docstring this is a value, so a multi-line literal
  ships its own source indentation to everyone who renders it.
- **Serving under a path prefix: `set-base!`.** Every wrapper's path is
  root-absolute (`/api/orders`), so behind a reverse proxy that mounts the app
  at `/app/…` they all resolve at the PROXY and the page does nothing. The
  generated ns exports `set-base!` — call it once where you mount, and every
  wrapper follows. Default `""` is the served-at-the-root behaviour, so an app
  that needs none of this does nothing. Server side, send
  `X-Slopp-Base: /your/prefix` with the proxied request: the document reads it
  per REQUEST (not from config — the same server may also be answering
  directly on its own port) and emits its own asset urls prefixed.
- **Content is not generated for, and needs no flag to say so.** A page or a
  stylesheet declares `:http/path`, so it is not part of a typed contract and
  no wrapper is emitted. That used to need `^{:rest/client false}` on the page,
  because a page WAS an endpoint like any other and a schema could never tell
  HTML from JSON (`:string` is a perfectly good JSON response).
- **An endpoint says what it ANSWERS: `^{:rest/media-type "application/edn"}`.**
  Absent means `application/json`. Every wrapper used to call `.json()`
  unconditionally, so an endpoint answering anything else failed on the first
  character; a non-JSON one now reads `.text` and validates the body as it
  stands. This is a fact about the endpoint and is checkable — which the flag
  it replaced was not.
- **There is no way to say "generate no client for me."** `:rest/client` was
  retired: whether to generate is the generating consumer's question, and an
  endpoint does not know who will call it. Its three real uses turned out to be
  a base problem in one client namespace serving two APIs, "our browser does
  not call it" (not the producer's business), and an endpoint answering EDN —
  which is the media type above.

**Consuming someone else's API: publish a contract, generate against it.**
Everything above assumes the endpoints and the client live in ONE store. When
they don't — a UI in its own project, two services, anything across a process
boundary — the producer publishes its shape and the consumer generates from
that. Neither store reads the other.

- **Producer: serve `slopp.rest.paths/paths-document`.** It takes your
  served namespace list and returns `{:paths […]}`
  — method, path, name, the handler's QUALIFIED symbol, its docstring,
  `:media-type`, `:effectful?`, `:auth`, and the
  request/response schemas as VALUES. Publish it at `/api/rest/paths`: the
  convention is `/api/<capability>/<what it lists>`, so a consumer that learns
  one address learns `/api/http/paths` and `/api/webapp/paths` too. Each
  capability has its own document, because they gain keys on their own
  schedules and no consumer wants every kind listed together.

  **`/api/webapp/paths` is purely the paths WITHIN the app** — one row per
  `:webapp/path` page, naming the function that renders it and what that page
  is. `/api/http/paths` says what the SERVER hands a browser, and marks which
  of those documents is the shell; this says what the browser then does with
  it, which the other cannot, because a client-routed app is one server route
  and a dozen addresses.

  **No document carries a version key.** Nothing branched on one — it existed
  so a consumer could refuse rather than misread — and API compatibility is a
  coordination between an API and its client rather than something a framework
  invents. The rule that replaces it: **a document changes by RENAMING a key,
  never by redefining one in place.** A consumer that cannot find `:paths` has
  met a document it does not know, which is the signal `generate_client`
  refuses on. Serve it as EDN with `:http/raw true`,
  `Content-Type: application/edn`, and `^{:rest/media-type "application/edn"}`
  so a generated wrapper reads text rather than attempting JSON. It ships in the
  `slopp-web` slim jar, so any app can publish, not just one whose code lives in
  a store.

  This endpoint used to be marked "no client — describing the wrappers needs no
  wrapper." That was never the fact: nothing is circular at runtime, and the
  consumer who hand-wrote request paths for it would have had them generated.
  The fact was the encoding.
- **Your handler's docstring IS the endpoint's public description, and it is
  MARKDOWN.** It travels in `:doc`, de-indented and whole — there is
  deliberately no second summary field beside it, because one fact with two
  homes can disagree. This is the crossing where the format matters most: the
  consumer rendering it is a different store, written by a different agent,
  that cannot ask you what the text is. Every other field in that document is
  typed; the prose is declared instead. Write it for the CALLER:
  open with what the endpoint is for, and keep implementation notes out of it,
  because everyone generating a client reads it. `:handler` is there because
  `:name` alone does not resolve — on a real surface a third of endpoint names
  match more than one form, so a consumer linking by simple name points at the
  wrong one and looks right doing it.
- **Calling an upstream FROM YOUR SERVER: `slopp.rest.client/call!`.** The
  same builder your browser client uses, performed on the JVM:
  `(rest.client/call! {:rest/base-url "https://up"} (endpoint/request api/thing {:id 1})
  {:check check-fn})` → `{:status :headers :body}`, body decoded by what
  the far side declared. Pass `:requester` to swap in
  `slopp.http.client/fake-requester` and no socket opens.
  **It handles what a raw socket does not:** a timeout is always set
  (`slopp.http.client/default-timeout-ms`, 10s — a missing one means wait
  FOREVER), a request path may not name another origin (refused, not cleaned),
  redirects are not followed, EDN is read without evaluating reader tags, and
  no header reaches an `ex-data`.
  **Calling the port directly? Pass `:http/timeout-ms` yourself.** The port
  publishes the default and deliberately does not apply it, because it holds no
  policy — so omitting the key is a choice to wait forever, made by silence.
  Measured: a consuming store's only outbound call site had none, and so did
  two of slopp's own, one of them on the auth path at startup. It does
  NOT retry, back off, break circuits, or allowlist hosts beyond the base —
  named so nobody assumes them.
  Two failure lines: **an answered request returns whatever its status** (a 404
  is data — what a missing upstream resource means is yours), **an unanswered
  one throws** the port's own `:http/error :unreachable`, and a failed `:check`
  throws `:rest/error :contract` because the upstream broke a promise it
  published.
- **Consumer: `generate_client {from "http://host/api/rest/paths"}`.** Writes
  TWO namespaces — a `:cljc` contracts ns of the published schemas, and the
  usual `:cljs` client pointing at it. Both `^:generated`; regenerate, never
  hand-edit.
- **EDN, not JSON, and not OpenAPI.** A malli schema is keywords, symbols and
  vectors; JSON renders `:string` and `"string"` identically and the far end
  can't tell them apart. OpenAPI would work but only one direction is
  lossless (malli → JSON Schema), so it forces an importer into the path; keep
  EDN as the source of truth and derive OpenAPI later if a non-Clojure consumer
  ever needs it.
- **Names come from ENDPOINTS, not from the producer's schema names.** Metadata
  is evaluated at def time, so `^{:rest/response contracts/timeline}` is already
  a plain vector by the time anything can read it — the name `timeline` never
  existed at runtime. The consumer gets `timeline-response`, and a schema shared
  by two endpoints arrives inlined in both.
- **The version is there to be refused.** An unrecognised
  `:slopp/contract-version` generates nothing and reports a problem, rather than
  guessing at a shape it doesn't know. **So it has to MOVE when the shape does:
  adding a REQUIRED key moves the version, or the key ships `{:optional
  true}`.** Version 1 gained four keys without moving and a consumer fronting
  several producers could not tell which shape it had — a constant version is a
  field that cannot do the one job it exists for. `generate_client` reads
  exactly one version and refuses every other; there is deliberately no
  compatibility path, so a producer and its consumers upgrade together.
- **`:effectful?` is DERIVED, not a marker read back.** True when the endpoint
  declares `:http/effectful` OR its method is not safe — so it is answered for
  every endpoint rather than only the ones whose author wrote something. It
  over-warns on a POST that only searches, which is the safe direction for a
  caller deciding whether to confirm before firing.
- **Pass the served-namespace list to your performers as data.** Only the
  server knows what it serves. Thread it through `:http/perform-ctx` — reaching
  for it from a page namespace inverts the dependency, and forgetting it
  entirely publishes an empty contract with a 200, which a consumer will
  happily generate an empty client from. Test that one over a real socket: an
  in-image test builds `perform-ctx` itself and passes either way.

**If your store declares `io.github.nvoxland/slopp-web`, your DECLARATION is
the version you run — not the slopp hosting you.** The slopp process carries
`slopp/http/**` inside its own jar, and the declared coord still wins: tests,
`query_eval` and your server all load the pinned release. So a `slopp.http` fix
in a newer slopp does not reach you until that release is republished and you
`deps_add` it (then `restart` — a hot `deps_add` cannot displace an
already-loaded namespace). Nothing warns you when the pin is behind, so treat
"is my `slopp-web` current?" as a question you have to ask. Measure rather than
assume: `query_eval` `(.getResource (clojure.lang.RT/baseLoader)
"slopp/http/static.clj")` names the jar actually in force.

### Reviewing a UI without a browser

**`slopp.cljnx` drives your app like a browser, with no rendering engine** —
document, event dispatch, re-render, on the JVM, running your app's OWN client
code. Reach for it the moment you want to *look at* a screen, not just when
writing a test: opening a real browser to read a sentence is the habit this
replaces.

**It is `cljnx`, not `browser` and not `screen`.** Not `browser`, because a
real one is a thing you may also be testing with and that word has to keep one
meaning. Not `screen`, because in a `webapp` a screen is a PANE — what a route
row points at — and that vocabulary is load-bearing. The coined name can only
ever mean this one thing.

**It belongs to no capability, and that is the design.** It takes ONE driving
contract and knows no app type; each capability derives that contract from
what it owns. So it is vendored to every store rather than to one family's.

**Hand it a url, the way you would a browser.** That is the whole interface:
an address, and optionally a script of what to do once you are there.

**To LOOK, use the `screen` tool** — no code, no test, no browser:

```
screen {url "/store"}
screen {url "/store" steps [{fill "Filter" value "web"} {click "Add"}]
        region "main" detail "prose"}
```

It answers with the page plus what a browser would also tell you: `url` — the
ADDRESS BAR, which after a redirect is not what you asked for — `status`, and
`redirects` when the app sent you somewhere. Each is absent when there is
nothing to say, so a `{:state :view}` page with no urls answers exactly as it
always did.

**To ASSERT, the same thing in a test.** `cljnx/open!` takes the same address
and `cljnx/drive!` the same step script, so a screen you looked at is one you
can pin without retyping it as a call chain:

```clj
(require '[slopp.cljnx :as cljnx])

;; a SERVED app — its own ctx, through http's adapter
(def s (cljnx/open! (slopp.http/driver ctx) "/store"))
;; a BROWSER app — the same contract, through webapp's
(def s (cljnx/open! (webapp/driver (webapp/wiring app)) "/store"))

(cljnx/drive! s [{:click "Code"}])
(cljnx/text s "main")              ; ONE region — and it throws if absent
(cljnx/text s nil {:within "rate"}); ONE element, addressed like a click

(cljnx/status s)                   ; 404 — a NUMBER, not a sentence
(cljnx/url s)                      ; where you ENDED UP
(cljnx/redirects s)                ; [{:from … :status … :to …}]
```

**Assert a status with `status`, never by searching the page.** A non-hiccup
body renders as `HTTP 404` on the screen — a reader needs to see that — so it
is tempting to write `(str/includes? (text s) "404")`. Don't: a whole-page
match is one keystroke from asserting nothing in particular, and it will stay
green with the layout torn out. Same rule as regions: the narrow assertion is
the shorter one to write.

**Redirects are followed, so post-redirect-get works.** A 302 lands you on the
target and `url` reports the target, not what you asked for. Two urls pointing
at each other refuse by NAMING the cycle rather than counting hops, and a
redirect off-site refuses saying the APP sent you there — a different fact from
your test asking to leave.

**The `driver` call is the whole of what you write, and it is not ceremony.**
`cljnx/open!` refuses a served ctx, naming its producer: the fake browser used
to perform http's requests itself, which put one capability's adapter inside a
namespace that has to drive both. Hand-wiring the contract yourself is the
thing this exists to stop — `{:document (fn [path] hiccup)}` is a page you can
open, and a page you wrote by hand is a lookalike that drifts.

Mark the zero-arg PUBLIC defn that builds your app `^:app/entry` and the tool
can find it — it belongs to the always-on `app` owner rather than to a
capability, because the same marker names a served ctx, a browser app's
declaration and a hand-wired page. `cljnx/driver-for` is the ONE public
derivation from whatever it returns to what `open!` takes; point your own tests
at that rather than spelling the wrapping, or the tool and your tests wire the
app two different ways and each passes against its own reconstruction; there is deliberately no session between tool calls, so a script
is the whole interaction and the same script reproduces the same screen
(`trace true` shows the screen after every step of one run). A page may
declare `:boot (fn [state url] state')` — its entry point's state transform,
handed the ADDRESS the session opened at (nil without one) — and `open!` runs
it once, the way a browser runs an app's entry at page load: the loads that
belong to no particular screen START headlessly too, so their loading states
show instead of an absence nothing can distinguish from never-asked. The url is
passed because a session-scoped load can be independent of every SCREEN and
still depend on which tenant the ADDRESS names; the browser's `start!` has it
before routing for the same reason, so both producers answer identically. (`open!` is `!`-named for exactly that reason.)

**How to read a screen — one rule.** Plain text is the page's words,
HTML-escaped, so page text can never be mistaken for markup; an UNPREFIXED tag
or attr was really on the page and survives only where it carries something
you can act on; anything `slopp:`-prefixed the reader derived. A screen looks
like:

```
<slopp:region name="main">
  <h1>orders</h1>
  3 open, 1 overdue
  <input placeholder="filter orders" slopp:on="input :orders/filter"/>
  <svg class="aging-chart">2 bar</svg>
  <ul slopp:count="5">
    <li>#101 late</li>
    <slopp:elided count="4"/>
  </ul>
  <button slopp:on="click :orders/expand true">expand all</button>
</slopp:region>
```

The kept tags: controls (`a href`, `button`, `input`, `select`/`option`,
`textarea`, `form`, `label` — with the state a browser shows: `value`,
`checked`, `selected`, `disabled`, and the page's own not-a-control
statements, `aria-hidden`/`inert`), structure (`h1`–`h6`, `table`/`tr`, `pre`
verbatim, `img alt`, the `svg` class census), enumeration (`ul`/`ol`/`li` with
`slopp:count`, and `<slopp:elided count/>` where the TOOL's 3-row cap bit —
`drive!`/`text` in a test elide NOTHING by default, so an assertion can never
be eaten silently). The guarantee is an INVARIANT, not a property of this
marker set: every structured-mode marker lives inside `<…>` and that alphabet
is escaped in page text — a marker outside the escape is the v1 flaw
returning, however harmless it looks. `class`/`style`/`id` never reach the output (except
`class` on svg, where it is the census vocabulary), so sugar (`:h1.big`) and
plain spellings render identically. A handler on ANY element keeps that
element's tag so `slopp:on` has a place to ride — `slopp:on="click :save
true"` says what a click DOES with its scalar args (twin buttons differ by
them); a closure can only say `click (fn)`. `detail "prose"` drops every tag
and keeps the words, unescaped.

**A server-rendered app declares nothing.** `visit!` goes through
`slopp.http.dispatch/handle!`, so it is a real request down the real pipeline —
routing, auth policy, declared reads, the handler, effects. A page that 401s
here 401s when served, which is the point of driving dispatch rather than
calling a handler. Urls behave like a browser's: `/search?q=web` delivers a
`:query-string`, a `#fragment` is never sent, a bare `#anchor` click is a
scroll (no-op), `#/…` is hash routing and reaches your `:navigate`, and an
external `https://…` link REFUSES — a headless session has nowhere else to go.

An app with CLIENT state supplies a page instead: `{:state (atom …) :view (fn
[state] …) :navigate (fn [state path] …)}` — `:navigate` optional, and one
function rather than a router. An app can be both: routes for the server
render, a `:view` to re-render after a click. `open` REFUSES a page it cannot
run — a missing `:state`/`:view`, a non-atom state, or an unknown key (a
typo'd `:vew` used to render a blank page, the silent worst).

- **Put handlers IN THE TREE — all three idioms are driven.** `:on-click (fn
  [e] …)` (Reagent), `:on {:click (fn [e] …)}` (Replicant), and `:on {:click
  [:action …]}` (Replicant's DATA form), which goes to a page-level
  `:dispatch (fn [event data] …)` mirroring `replicant.dom/set-dispatch!`.
  Typing is the same, under `:on-change` or `:on {:input …}`. Clicks BUBBLE
  as DOM semantics: text inside a handled element reaches that handler, an
  `aria-label` addresses an icon-only control, and a `disabled` control
  refuses. **Hand-rolled `js/document.addEventListener` delegation cannot be
  driven** — it lives in `:cljs` and never runs here. You rarely need it: both
  libraries attach handlers to elements for you, precisely so a re-render does
  not strand them.
- **Prefer the DATA form where you have the choice — and for an INPUT it is the
  only portable one.** It reaches `:dispatch` as `(action value)`: the action
  verbatim, the typed text as a SCALAR, no event map invented by anybody. A
  `<select>` fill must name one of its options (a browser only lets you
  choose); a checkbox's value is its checked boolean.
  **Never write a handler that reads a value out of an event**
  (`(:value e)`, `(get-in e [:target :value])`). Replicant's real event map
  carries `:replicant/dom-event` and no `:value` — the text is behind
  `(.. e -target -value)`, which is interop and cannot run on a JVM. A handler
  reading an invented `:value` passes every headless test and does nothing in a
  browser, which is the one direction of wrong that a test actively conceals.
  **The rule that dissolves it:** your `:cljs` dispatcher normalises the event
  to a scalar, your `:cljc` interpreter takes `(state action value)` and never
  sees an event of any shape — so neither driver's event can be right while the
  other is wrong.
- **How slopp REFUSES a page it cannot open**, at the write: the marker on
  anything but a zero-arity public `defn` (a `def`'s arity cannot be read, a
  `defmethod` discards the marker at macroexpansion, a private page is
  invisible to the tool's scan); the entry in a `:cljs` namespace; a SECOND
  `^:app/entry` (the scan would answer from whichever it reached first,
  silently). And the one that catches real apps with no write to your entry at
  all: the entry's namespace CLOSURE reaching `:cljs` — `module_platform`
  reports the pages a `:cljs` declaration strands (`:stranded-pages`) at the
  moment of the declaration, the `http-page-reach` advisory re-grades a page
  you EDIT, and `full_check` re-grades every page.
- **What slopp assumes, so you can tell if you're outside it:** state is an
  atom, handlers are in the tree, the view is a pure function of state. The
  wiring is portable; only the EFFECTS are `:cljs`. An entry the JVM cannot
  call sends every headless test back to hand-building a map that RESEMBLES
  your app, and a resemblance passes while the real screen is wrong — which is
  the whole defect this removes.
- **Read the screen BEFORE asserting on it.** Most view bugs are plain wrong
  sentences, and they are obvious in a readout and invisible in a `get-in`.
  "Look at the UI" costing a browser is why they ship.
- **Scope an assertion to the region it names** — `(cljnx/text s "main")` is
  shorter than the whole page on purpose, and comes back DEDENTED so moving a
  `<div>` around the region cannot break it. A whole-page `str/includes?` is
  one keystroke from asserting nothing: a real tint check once matched its
  pattern anywhere on the page, claimed the diagram, checked a list, and stayed
  green with the layout torn out. In structured mode remember the text is
  ESCAPED: assert `a &lt; b` when the page says `a < b` (prose mode is
  unescaped).
- **A click refuses rather than shrugging** — nothing says it (and the message
  lists what can be clicked), it is on the screen but nothing over it handles
  a click, two distinct controls say it, it is disabled, or the app has no
  urls. Five different bugs, never one silent no-op.
- **`cljnx/lines` when you want to address ONE line**, e.g. the `<svg>` census
  — assertions are easy to write here and therefore easy to write too broadly.
- **A readout reveals what CSS was silently supplying, and that is a whole
  class of markup bug you get for free.** Two elements separated only by a
  margin have NOTHING between them in the text: a real page rendered
  `demo.orderstatic`, one wrong word made of two correct elements. Fix it in
  the MARKUP rather than by adding spaces to the reader — a reader without CSS
  is not only a headless driver, it is also a screen reader, and the same gap
  hits both. **And assert it in PROSE**: structured mode places a tag between
  adjacent words (`…rate</a>[kg zone]`), so word-gluing is invisible there
  whether the markup is fixed or not — prose is where adjacency is real, and
  the one mode this class of assertion can live in.
- **It is NOT a screenshot.** An `<svg>` is censused by CLASS rather than
  dumped as coordinates, which catches an overlay's tints with no pixels — but
  a list that wraps over three lines and a tint invisible against white still
  need eyes. Do not assert what a readout cannot see.

