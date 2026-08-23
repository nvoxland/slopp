# Typed APIs

`http` serves. `rest` is what makes a declared contract binding.

```clj
config_file {path "capabilities" key "rest.enabled" value "true"}
```

It implies `http`. Every rest rule, and `query_surface`'s `:rest` section, is
inert until it is true.

## An endpoint declares what it accepts and returns

```clojure
(defn ^{:http/method   :post
        :rest/path     "/api/orders"
        :http/auth     :public
        :rest/request  [:map [:sku :string] [:qty :int]]
        :rest/response [:map [:id :int]]}
  create! "Place an order." [req]
  {:status 200 :body {:id (order/place! (:body req))}})
```

The same [malli](https://github.com/metosin/malli) schemas serve three readers:
the boundary that validates, the generated client that validates on the other
side, and `/api/contracts` that publishes the shape to a consumer who never sees
your store.

## What you get without writing it

**A request that breaks its contract never reaches your handler.** It is a 400,
refused *before* the declared `:http/reads` run — doing work on unvalidated input
is what a boundary exists to prevent. The explain goes to the caller, because it
describes the caller's own data.

**What the wire cannot carry is decoded for you.** JSON has no keywords, dates
or UUIDs, so `[:tag :keyword]` reaches your handler as the keyword you declared
and you parse nothing.

What JSON *can* carry is judged, not repaired. `"7"` against `[:qty :int]` is a
client error, not something to fix up quietly — accepting it would publish a
contract the server does not actually require, and leniency there is how "it
worked when I tried it" and "it accepts anything" end up being the same system.

**A response that breaks its own contract is a 500**, explain logged
server-side and never sent. The client was generated from that schema, so a
violating response breaks the consumer anyway; failing at the source beats
failing obscurely at the far end.

Error responses are exempt. `:rest/response` describes the 200, so a 404's
`{:error …}` is not judged against it.

## Seeing what a client sees, without a server

```clj
query_eval {code "(slopp.rest/call ctx {:method :post :path \"/api/orders\"
                                        :body {:sku \"abc\" :qty 2}})"}
;; {:status 200 :body {:id 41}}
```

No socket, no port, no browser, no process — **and yet a keyword comes back as
a string and a set comes back as an array.** That is what a consumer actually
receives, and it is why asserting on a handler's return value has always been
checking a shape no client gets:

| your handler returns | the client receives |
|---|---|
| `{:x :foo}` | `{"x": "foo"}` — a string |
| `{:tags #{"a"}}` | `{"tags": ["a"]}` — an array |

Both directions bite. The first fails a `[:x :string]` contract in memory and
satisfies it on the wire; the second passes a `[:set :string]` contract in
memory and arrives as something that is not a set.

The boundary is real in `call` too: a bad body is refused exactly as it would be
over a socket. A test double that skipped validation would be a second
implementation of your server, and the two would disagree on the first change.

## The rules it arms

| Rule | Grain | Fires on |
|---|---|---|
| `rest-endpoint-schema` | write | an endpoint with no declared contract |
| `rest-unconstrained-contract` | done | a field declared as a bare `:map` — accepts anything, so validation passes over it forever |
| `rest-undocumented-contract` | done | a published field that says its shape and never what it means |
| `rest-inline-schema-dup` | done | the same inline schema in two endpoints, wanting a shared var |
| `rest-stale-client` | done | the generated client is older than the contract it was generated from |

`query_capabilities` lists them before you opt in, which is the point of listing
them.

!!! note "These used to belong to `http`"

    If you serve HTML and publish no typed API, you are no longer asked to
    declare `:rest/response` on every page. Serving a document is `http`'s
    business; typing a JSON contract is `rest`'s.

## Your typed surface, as a report

```clj
query_surface {}
;; :rest [{:kind :contract :method :post :path "/api/orders"
;;         :handler shop.api/create! :published true
;;         :request [:sku :qty] :response [:id]}]
```

Names rather than schemas, deliberately: this is the one report that grows with
your application rather than with your question. `query_slice` on the handler
gives the schemas exactly.

A contract that is not a map answers with its own type instead of a key list —
`:or`, `:string` — because there is nothing to enumerate, and `[]` would be a
claim about your contract rather than about the report. A map *inside* a
collection is seen through: `[:sequential [:map [:id :int]]]` reports `[:id]`,
since a list endpoint is the commonest non-map contract there is.

Content is absent from the report entirely: a page or a stylesheet declares
`:http/path` rather than `:rest/path`, so it is not part of a typed contract
and never was one. That used to need a `:rest/client false` flag on the page,
because one marker declared both kinds of route.

There is no way to exclude an API from the report or from the published
document. Whether to generate a client is the generating consumer's question,
and an endpoint does not know who will call it.

## `:rest/request` is what the caller sends

One schema covers every carrier. A GET sends a query string for the same reason
a POST sends a body, and the generated client reads the method to decide which
one each key takes.

Each is decoded by what its own wire can express:

| carrier | `[:qty :int]` given `"7"` | why |
|---|---|---|
| path segment | decoded to `7` | a URL has no way to carry a number |
| query parameter | decoded to `7` | same |
| JSON body | **refused** | JSON carries real numbers, so a string is a client error — repairing it would publish a contract you do not require |

Decoded **in place**, so your handler reads `:path-params`, `:query-params` and
`:body` where it always did and finds them typed:

```clojure
;; GET /api/form/f1?depth=2  against  [:map [:id :string] [:depth {:optional true} :int]]
(:depth (:query-params req))   ;; => 2, an int. You parse nothing.
```

`?depth=banana` is a 400 before your handler runs.

!!! note "Typed params are a property of the capability being on"

    The decoding happens when the boundary runs, so a handler receives typed
    params with `rest` enabled and text without it. Writing handlers against the
    typed shape commits you to the capability — turning it off is then a
    behaviour change, not only a loss of checking.
