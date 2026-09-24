# ClojureScript client

Browser code is authored the way everything else is: forms in the store, edited
with the same tools, gated by the same dialect. What changes is the *platform* a
namespace targets, and what verifies it.

## Three platforms

```clj
module_platform {module "shop.contracts" platform ":cljc"
                 prompt "order schemas are shared by the server and the browser"}
ns_create {ns "shop.ui.dom" platform ":cljs" requires [...]}
```

| Platform | Renders as | Loads in the oracle | Verified by |
|---|---|---|---|
| `:jvm` (default) | `.clj` | yes | the test suite |
| `:cljc` | `.cljc` | yes, the `:clj` branch | the test suite |
| `:cljs` | `.cljs` under `cljs-src/` | never | the compiler |

Scope is a namespace path with most-specific-wins, exactly like
`module_purity`. `query_depends {modules true}` returns a `:platforms` map;
anything absent from it is `:jvm`.

A `:cljs` write lands `:unverified` with reason `:cljs-deferred-to-compile`.
That is honest rather than a gap: the JVM oracle cannot load `js/document`, so
the compiler is the gate for that code.

!!! tip "Keep the .cljs thin"
    A predicate, a schema, a state transition is platform-neutral. Put it in
    `.cljc` and the JVM oracle covers it for free -- write a `-test`, watch it
    go red, implement, green, exactly like Clojure. Reserve `.cljs` for the
    genuinely browser-bound edge. That code is verified by "it compiled" and
    nothing more until browser tests exist, which they do not yet.

    **If the browser owns your routing and state, that edge should be empty.**
    The router, the render loop, the click and popstate listeners, the
    dispatcher and `fetch` are the `webapp` capability's -- see [Browser
    applications](webapp.md), where the goal is that your own code declares no
    `:cljs` namespaces at all.

## Compiling

```clj
compile_client {}
```

Every `:cljc` and `:cljs` namespace compiles with the configured backend --
real ClojureScript, running on the JVM, no Node -- into one `:simple` bundle.
The default output path is `public/cljs/main.js`, so a `http.static./assets`
mount pointing at `public` serves it at `/js/main.js`:

```clj
config_file {path "capabilities" key "http.static./assets" value "public"}
```

The bundle is an **artifact**, not a tracked file: the store records its sha
and a recipe, and the bytes live in a gitignored on-disk cache. That keeps a
2 MB bundle out of the delta log -- inline, fifteen compiles of it were 30 MB
of journal. A clone starts with an empty cache, which is normal: `build`
reports the gap as `missing-artifacts` and `compile_client` fills it.

Compile errors and analyzer warnings are anchored to the owning store form, by
name, with no file or line. You do not reference the bundle yourself: mark the document
`:webapp/shell true` and the framework injects the script into the `[:head …]`
you wrote, along with the mount prefix. The URL is derived from the compile
output and the static mount that serves it — rename the mount and the
`<script>` follows; assemble a shell with nothing serving the bundle and it
refuses rather than shipping a tag pointing at a 404.

```clj
(def ^{:http/method :get :http/path "/" :http/auth :public
       :webapp/shell true}
  shell
  [:html [:head [:title "Orders"]] [:body [:div {:id "app"}]]])
```

A shell whose path is a wildcard covers every address beneath it, which is what
makes a refreshed deep link work. Hand the context the route table your pages
already declare and the **status** is derived from it while the **body** is
not:

```clj
(slopp.http/context {:http/namespaces [...]
                     :webapp/bundle "/js/main.js"
                     :webapp/routes [["/orders" 'app.ui/orders]
                                     ["/orders/:id" 'app.ui/order]]})
;; /orders/42        → 200 + shell
;; /orders/42/typo   → 404 + shell   (same bytes)
```

Same document either way: the app boots, finds an address its own table does
not match, and renders whatever not-found it wants. Rendering stays the app's
job and telling the truth becomes the server's — a crawler, a link checker and
`curl -f` all read the status and never see the page.

Without `:webapp/routes` a wildcard shell answers 200 for everything under it,
so a typo and a real page are indistinguishable at the server. That is the
compatibility answer for shells written before this existed, not the one to
choose.

A top-level `defonce` starts the bundle, so the page needs no inline script:

```clj
(defonce _start (main))
```

`compile_client` is explicit, like `build`, and a store that runs a dev
instance (`http.enabled` or a declared `app.main`) does not need it: every
write to a client namespace recompiles the bundle in the background. To opt
out, or to force it for a store that serves nothing:

```clj
config_file {path "client" key "auto-compile" value "false"}
```

A write to a client namespace returns `:client-recompiling` and schedules
a background compile (single-flight, coalescing), and a `--live` server serves
the fresh JS once it commits. Off by default.

The backend is a per-project setting -- `config_file {path "client" key
"compiler"}`, default `:clojurescript`. Other backends are a future
possibility from the same source.

!!! note "slopp provisions its own toolchain"
    There are two dependency configurations. **Yours** is the `deps_add`
    manifest: application libraries, delta-tracked, visible in `deps_list`.
    **slopp's** is the ClojureScript compiler and malli, injected at build
    time and versioned centrally with slopp. They never enter your manifest,
    never appear in `deps_list`, and never land as deltas in your history, so a
    slopp upgrade moves every store forward with no migration. Do not
    `deps_add` the compiler.

## The typed client is generated

Once endpoints declare their `:rest/request` and `:rest/response` -- which the
`rest-endpoint-schema` gate requires -- slopp can write the browser side of the
contract for you:

```clj
generate_client {}
```

That writes a stored `:cljs` namespace (default `app.client.api`, or the
`client`/`generated-ns` config) holding one typed `fetch` wrapper per endpoint.
Each wrapper validates parameters on the way out and the response on the way
in, against the *same* schema var the server enforces.

```clj
(api/create-order! {:sku "A1" :qty 2})   ; returns a promise; a wrong shape
                                          ; throws before the request leaves
```

Rules of the road:

- **It is explicit.** Run `generate_client` after changing an endpoint's
  contract, like `compile_client`. A `rest-stale-client` advisory at done time
  nudges you when a contract has drifted since the last generation. When
  the bundle is kept fresh, generating also refreshes it.
- **Never hand-edit it.** Every wrapper carries `^{:generated "<endpoint>"}`
  and the `http-generated-ns` gate refuses edits, because the next generate would
  overwrite them. To take manual ownership of a wrapper, strip the marker.
- **It is still ordinary code.** `query_source` reads it, blast radius covers
  it, and because the wrappers reference schema *vars*, "change this schema ->
  every affected client call" falls out of the reference graph rather than a
  grep.
- **Schemas must be `.cljc`.** A schema var the client ships has to compile
  into the bundle *and* be the one the server validates. `generate_client`
  skips an endpoint whose schema is not shippable and names it in
  `:problems`. A `rest-inline-schema-dup` advisory nudges a shape shared by two
  endpoints toward a named `.cljc` var.
- **Declare the entries, or response validation is off for that field.** A field
  typed `[:sequential :map]` accepts any map, so the generated client validates
  it on every call and can never find anything -- the mechanism built to catch
  drift is pointed at the field and switched off. Name the entries, or say
  `:any` when the shape is genuinely unsettled: `:any` admits it is saying
  nothing, while a bare `:map` looks like a type and admits everything. The
  `rest-unconstrained-contract` advisory lists them, whole-store, and it is the
  prior question to the one below -- prose does not make a field real.
- **Each field's prose belongs on the field.** A type says what shape a value
  has and never what it means: `:total :int` does not tell a caller the number
  counts hits *before* the limit is applied. Malli entry properties are open and
  travel with the schema, so `[:total {:doc "hits before the limit is applied"}
  :int]` reaches every consumer of the published contract, while a docstring on
  the schema var does not -- a docstring is not a value. The
  `rest-undocumented-contract` advisory lists the fields that say nothing; it
  runs over the whole store, because a published contract is stable and nothing
  episode-scoped would ever look at it again. `:description` is accepted as
  malli's JSON-Schema spelling. Build a long one with `(str ...)`: unlike a
  docstring this is a value, so a multi-line literal ships its own source
  indentation to everyone who renders it.
- **Pages get no wrapper because they are not APIs.** A page declares
  `:http/path` and an API declares `:rest/path`; only the second is generated
  for. No flag is involved. See [HTML and CSS](html.md).
- **Say what an endpoint answers** with `:rest/media-type` when it is not
  `application/json`. A wrapper called `.json()` unconditionally, so an
  endpoint serving EDN failed on the first character; a non-JSON one now reads
  text and validates the body as it stands.

## Sharing real logic

A malli schema in `.cljc` is verified by the JVM oracle here and compiled into
the browser bundle there, so one definition checks both sides of the wire. The
same goes for any pure transform: put it in `.cljc`, test it on the JVM, use it
in the browser.

Refactoring works on client code like any other -- `edit_rename`,
`edit_extract` and `edit_move_forms` all handle `:cljs` forms, and clj-kondo
lints each form in its own platform's language, so `js/*` does not draw a false
unresolved-namespace finding.

One rough edge worth knowing: the `!`-effect warning fires on idiomatic
ClojureScript entry points, because `^:export main` touches the DOM. It is
advisory, not a refusal.

## Non-trivial apps: a REST API and a browser app that consumes it

Past a handful of pages, the shape that keeps scaling is **a JSON API with
declared contracts, consumed by client-side code** -- rather than HTML
assembled on the server for the browser to slot in. Server-rendered pages and
static content stay fully supported; they just stop being the assumption.

The *consuming* half of that shape is the `webapp` capability: routing, state,
the render loop, load states, links and actions, all declared as data. This
section is the boundary reasoning that holds either way; for the app itself,
read [Browser applications](webapp.md).

The reason is not taste. The API is an explicit, testable boundary: one call
(`query_surface`) answers what the app can do, each endpoint is a function of
data you can assert on with `=`, and the frontend consumes a *generated*
contract instead of sharing the server's internals. HTML-over-the-wire models
blur exactly that boundary -- the server computes presentation, and there is
no contract left to point at.

Address the JSON surface at `/api/*`, and keep it separate from the pages, for
the same reason URLs name what they *are*: the whole data surface stays
readable without opening a single handler.

### Views go in `.cljc`, and that is what keeps them cheap to test

The rule from [the platform split](#sharing-real-logic), applied to UI:

> Views, state transitions and formatting are `.cljc` pure functions. Only
> mounting and event binding are `.cljs`.

Then the *same function* renders on both sides -- the server renders it into
the page, the client re-renders it after a fetch. One renderer means a click
and a refresh cannot show different things, which is the kind of drift only a
browser would otherwise reveal.

It also means a view is an ordinary in-image test asserting on returned hiccup
**data** (`(get-in v [2 1])`) -- no browser, no headless Chrome, no JS test
runner. **That is the check on whether you got the split right: if UI tests
need the external tier, too much logic drifted into `.cljs`.**

Two details that bite:

- **Take the wire shape in a shared view**, not the server's shape. JSON has
  no symbols; a view built around the server's data renders correctly on the
  server and renders `nil`s in the browser.
- **In `.cljs`, definitions must precede callers** -- there are no top-level
  forward declarations. The pipeline arranges definitions before their
  callers at every write, in `.cljs` exactly as in `.clj`; write forms in any
  order.

### Enhance progressively where a server route already exists

Intercept plain left-clicks only. Middle-clicks and cmd/ctrl/shift clicks mean
*open in a new tab*, and hijacking them takes away a capability the
enhancement did not give. On a failed fetch, fall back to a full page load: a
stale pane under a new URL is the failure mode that lies to the reader.

Both rules are the framework's if you enable `webapp` -- the click judgement is
one function with the modifier cases in it, and a load that has not arrived
renders the loading state rather than the previous screen. Write them by hand
only for a server-rendered page you are sprinkling behaviour onto, which is the
case `webapp` is not for.

### Two wiring rules the framework enforces

- **One list of served namespaces.** Routes and `:http/read` performers can
  live in different namespaces -- reads resolve by *vocabulary*, store-wide,
  so an API endpoint can reuse a page's read. `web/context` refuses a
  namespace list that cannot perform the reads its own routes declare, naming
  each unservable kind and the route that wanted it. Without that check the
  symptom is a **500, not a 404**, which is much harder to read from outside.
- **Health, metrics and RPC transports are APIs too, and get wrappers.** They
  used to be marked `:rest/client false` to keep them out of the client. That
  flag is retired: "this consumer does not call it" is not a fact about the
  endpoint, and excluding it also removed it from the published API document,
  where the next consumer would have looked for it. An unused wrapper costs a
  few lines in a namespace regenerated wholesale.
