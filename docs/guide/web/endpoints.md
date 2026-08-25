# Endpoints

A slopp web app has no route table, no routing macro, and no template
directory. An endpoint is one `defn` whose name metadata carries the whole
contract, and the gates that check every other write check it too.

## Opt in once

```clj
config_file {path "capabilities" key "http.enabled" value "true"}
```

Every http rule, and `query_surface`'s `:http` section, is inert until that is
true. A store that never opts in has no web surface and never sees a web
refusal.

Capability keys are declared in a registry with a type, a default, and a doc
line, so a typo'd key or an ill-typed value is refused at the write rather than
doing nothing. `query_capabilities` lists them all with their effective values.
See [store configuration](../../reference/config.md#the-capabilities-file).

## An endpoint is a defn

```clj
(defn ^{:http/method   :get
        :rest/path     "/api/orders/:id"
        :http/auth     [:group "staff"]
        :http/reads    {:order [:order/by-id [:path-params :id]]}
        :rest/response shop.contracts/order}
  get-order
  "One order, by id."
  [req]
  {:status 200 :body (:order (:http/reads req))})
```

Request and response maps are Ring-shaped: `:request-method`, `:uri`,
`:headers`, `:body` in; `:status`, `:headers`, `:body` out. Everything slopp
adds is namespaced under the capability that reads it -- `:http/*` here,
`:rest/*` for a typed contract -- so the shape a model already knows stays
intact and the additions are visibly slopp's.

| Metadata key | Means |
|---|---|
| `:http/method` | `:get`, `:post`, `:put`, `:patch`, `:delete`. |
| `:rest/path` | A REST API's path. Must be under the API prefix (`rest.prefix`, default `/api`). |
| `:http/path` | General HTTP content -- a page, a stylesheet. Must be *outside* the API prefix. |
| `:http/auth` | The policy. Required -- see [auth](auth.md). |
| `:http/reads` | `{alias [<kind> <request-path>]}`. Fetched before the handler runs. |

| `:http/effects` | `[<kind> ...]` -- the effect kinds this endpoint is allowed to emit. |
| `:rest/request` | Malli schema for the body. Required on `:post`/`:put`/`:patch`. |
| `:rest/response` | Malli schema for the response. Required on every endpoint. |
| `:http/effectful` | `true` opts out of effects-as-data. The escape, not the default. |
| `:rest/media-type` | What the endpoint answers. Defaults to `application/json`; a wrapper reads text for anything else. |

Two markers go on *other* forms:

| Marker | On |
|---|---|
| `^{:http/read <kind>}` | A function that fetches one read kind: `(fn [ctx arg] ...)`. |
| `^{:http/effect <kind>}` | A function that performs one effect kind: `(fn [ctx & args] ...)`. |

## Both halves of the URL

The dispatcher puts `:path-params` and `:query-params` on the request -- the
query string is parsed once, there, so no application splits `:query-string`
itself. A declared read reaches a query parameter exactly as it reaches a path
one:

```clojure
:http/reads {:page [:doc/by-id    [:path-params  :id]]
            :view [:doc/fidelity [:query-params :view]]}
```

Need both in one read? Declare it over the whole request with `[]` and
destructure what you want.

A bare key (`?flag`) is present with an empty value, because present-and-empty
is not the same as absent. Malformed pairs are dropped rather than thrown: a
query string is arbitrary text off the network, and the answer to garbage is a
page, not a 500.

One rule worth adopting early: **a parameter value your endpoint cannot honour
should be a 404, not a quiet fall back to the default.** The day you add a
second value, every link already in the wild would otherwise mean "whatever the
default became".

These are declared edges in the reference graph, so endpoints and performers
never trip the dead-surface gate despite nothing in the store calling them.

## Reads in, effects out

The point of declaring reads and effects rather than performing them is that
the handler stays a function of data, and its test is an `=` on data with no
mocks anywhere.

```clj
;; the performers: the only forms that touch the database
(defn ^{:http/read :order/by-id :reads true} fetch-order [db id]
  (db/order db id))

(defn ^{:http/effect :order/insert} insert-order! [db row]
  (db/insert! db row))

;; the endpoint: declares both, performs neither
(defn ^{:http/method   :post
        :rest/path     "/api/orders"
        :http/auth     :authenticated
        :http/effects  [:order/insert]
        :rest/request  shop.contracts/new-order
        :rest/response shop.contracts/order}
  create-order
  "Place an order."
  [req]
  (let [order (assoc (:body req) :owner (:http/sub (:http/identity req)))]
    {:status 201
     :body order
     :http/effects [[:order/insert order]]}))
```

The dispatcher fetches the declared reads, calls the handler, and interprets
the returned effects through the marked performers -- validating every kind
before running any of them, so a typo cannot leave a partial write. A unit test
calls `create-order` with a plain map and asserts on the returned
`:http/effects` vector; the write never happens.

Performers are ordinary functions and the ordinary rules apply: the one that
mutates is bang-named, the one that only reads carries `:reads` so it is not
flagged for calling into an opaque database library.

The escape ladder, in order of preference: declared reads -> a read performer
-> `:http/effectful true` on an endpoint in an `:external` namespace, with its
dependencies arriving as `:http/deps` on the request rather than as ambient
state.

## What the gates check

All of these are inert until `http.enabled`, and every one is severity-dialable
like any other [rule](../verification.md#rules).

| Rule | Refuses |
|---|---|
| `http-auth-refusal` | An endpoint with no `:http/auth`. Default-deny: `:public` is typed out, never implied. |
| `rest-endpoint-schema` | A missing `:rest/response`, or `:rest/request` on a body method. **Belongs to `rest`, not `http`** — an app serving HTML and publishing no typed API is not asked for one. See [Typed APIs](typed-apis.md). |
| `http-route-collision` | A second owner for one method plus path. |
| `http-undeclared-effect` | A `:http/effects` kind no marked performer provides. |
| `http-undeclared-context` | A handler reading `:http/deps` with no `^{:http/context true}` builder in the store -- see [Running the app](running.md). |
| `http-unsafe-get` | A `:get`/`:head` endpoint that declares effects or reaches a mutation. |
| `http-unknown-group` | A `[:group "x"]` policy naming a group the capabilities config does not define. |
| `http-react-attrs` | `:className`, `:onClick` and friends in hiccup -- see [HTML and CSS](html.md). |

Two more fire at done time rather than at the write:

- `http-public-mutation` (advisory) asks about a changed `:public` endpoint that
  declares effects. A public signup or webhook is legitimate; the point is that
  it should be a decision.
- `http-dangling-route-refs` (error) fails a rendered link or form targeting a
  path nothing serves. See [HTML and CSS](html.md#links-are-checked).
- `http-unreachable-declaration` (error) refuses a route or performer marker on
  a *private* form. Both the route table and the performer vocabulary are built
  from `ns-publics`, so a private one declares a surface and contributes
  nothing. A private route 404s on a path `query_surface` lists; a private
  performer answers 500, on a request the store believes it serves. To keep the
  implementation private, put the marker on a public wrapper that calls it.

## Reading the surface

```clj
query_surface {}
```

`query_surface` answers for every capability at once — `:http` here, `:cli` for
a command-line program. One sectioned tool rather than one per capability,
because with separate tools an empty answer cannot tell you whether the app has
no endpoints or you asked the wrong tool.

One call returns every endpoint -- method, path, auth policy, handler, declared
`:http/reads` and `:http/effects`, whether it carries a schema, and
`:rendered-by` (which forms link to it) -- plus the derived `:read-kinds` and
`:effect-kinds` vocabularies. It is the same derivation the write gates run, so
it cannot disagree with them.

Check it before claiming a path, before coining an effect kind, and before
writing a link.

!!! note "Test namespaces are fixtures"
    Endpoint-shaped forms in a `-test` namespace are excluded from the route
    rows. They neither report in `query_surface` nor claim a path, so a test can
    define whatever surface it needs to exercise.
