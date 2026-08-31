<!-- reference topic `rest` — served whole by `help {topic "rest"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## Typed APIs (`rest`)

**`http` serves. `rest` is what makes a declared contract binding.** Turn it on
with `config_file {path "capabilities" key "rest.enabled" value "true"}` — it
implies `http`.

**A REST api declares `:rest/path`; general HTTP content declares
`:http/path`. There is no overlap.**

```clojure
;; an API — typed, documented, generatable
(defn ^{:http/method   :post
        :rest/path     "/api/orders"
        :http/auth     :public
        :rest/request  [:map [:sku :string] [:qty :int]]
        :rest/response [:map [:id :int]]}
  create! "Place an order." [req]
  {:status 200 :body {:id (order/place! (:body req))}})

;; content — a document for a reader
(defn ^{:http/method :get :http/path "/styles/app.css" :http/auth :public}
  stylesheet "The stylesheet." [_req]
  (html/css-response …))
```

**A path is segments, and the grammar has exactly three special ones.**

| in a path | matches | bound as |
|---|---|---|
| `:name` | exactly one segment | `:name` in `:path-params` |
| `*` | exactly one segment | `:*` |
| `**` | ZERO or more segments, slash-joined | `:*` |

Both wildcards are **anonymous** and both are **end-only**. `**` matching zero
segments is what lets `/store/**` answer `/store` as well as `/store/form/9` —
so a section covers its own root and needs no second declaration for it.

```clojure
"/api/form/:id"     ; one segment, named
"/assets/**"        ; the whole tree under /assets, and /assets itself
"/p/:slug/store/*"  ; one segment under a captured one
```

Anything else carrying a `*` — `*path`, `*.css`, `pre*`, a wildcard in the
middle — **refuses at the write** (`http-path-pattern`). The router is pure, so
a pattern it has no rule for matches nothing and the route 404s while reading
as a live endpoint; the refusal is the only place that can say why. There is no
partial-segment globbing.

**Precedence is positional, so route order never decides anything.** Rank each
segment (literal < `:name` < `*` < `**`) and compare left to right: the longest
static prefix wins, `/my/**` beats `/**`, `*` beats `**`, and an exact route
beats a `**` that also covers it. It is what reitit and Spring's `PathPattern`
do, and the servlet spec's exact-then-longest-prefix ordering falls out of it.
Adding a route can never steal an existing one.

**`:rest/path` must live under the API prefix, and `:http/path` must not.**
The prefix is `rest.prefix`, default `"/api"` — one answer per store, so
`/api/*` means something to a proxy, a CSP or a reader WITHOUT consulting
metadata. Declaring both markers on one form refuses; so does either on the
wrong side. All three are write-time refusals and all three name the fix.

Before the split there was one path marker, so every route was asked the API's
questions — which is why a stylesheet ended up declaring `:rest/response
:string` (a lie about a `text/css` body) and then opting out of the wrapper
that followed. If you enable `rest` on a store that already serves something
under `/api`, that route refuses until it says which kind it is; that is the
partition becoming total rather than a regression.

With `rest` on, three things follow that you write no code for.

**A request that breaks its contract never reaches your handler.** It is a 400,
refused *before* the declared `:http/reads` run — work on unvalidated input is
the thing a boundary exists to prevent. The explain travels to the caller,
because it describes the caller's own data.

**What the wire cannot carry is DECODED for you.** JSON has no keywords, dates
or UUIDs, so `[:tag :keyword]` arrives as the keyword you declared and your
handler parses nothing. What JSON *can* carry is judged, not repaired: `"7"`
against `[:qty :int]` is a client error, because accepting it would publish a
contract the server does not actually require.

**A response that breaks its own contract is a 500**, with the explain logged
server-side and never in the body. The client was generated from that schema, so
a violating response breaks the consumer anyway — failing at the source beats
failing obscurely at the far end. Error responses are exempt: `:rest/response`
describes the 200, and a 404's `{:error …}` is not judged against it.

### `slopp.rest/call` — see what a client sees, with no server

This is the loop to reach for. It drives your own endpoints in process, through
the real wire encoding both ways:

```clj
query_eval {code "(slopp.rest/call ctx {:method :post :path \"/api/orders\"
                                        :body {:sku \"abc\" :qty 2}})"}
;; {:status 200 :body {:id 41}}
```

No socket, no port, no browser, no process — **and yet a keyword comes back as
a string and a set comes back as an array**, which is what a consumer actually
receives. That difference is why an in-image assertion on a handler's return
value has always been checking a shape no client gets, and it is the gap this
closes. `?q=x` in the path is split for you, because the transport does that.

The boundary is real here too: a bad body is refused exactly as it would be over
a socket. A fake that skipped validation would be a second implementation of
your server, and the two would disagree on the first change.

### What rest arms, and what moved

Enabling `rest` arms five rules — `rest-endpoint-schema` at the write, plus four
done-grain advisories about contract drift, duplication, unconstrained fields
and documentation. `query_capabilities` lists them before you opt in.

**These used to be `http`'s.** If you serve HTML and publish no typed API, you
are no longer asked to declare `:rest/response` on every page — serving a
document is `http`'s business, and typing a JSON contract is `rest`'s.

`query_surface` gains a `:rest` section: per endpoint, the NAMES its request and
response declare, and its handler. Content is absent from it by KIND — an
`:http/path` form is not part of a typed contract and needs no flag to say so.

**There is no way to exclude an api from the document.** `:rest/client false`
used to, and it was retired: whether to generate a client is the generating
CONSUMER's question, asked at generation time against a document, and an
endpoint does not know who will call it. Excluding one also removed it from the
published API documentation, which nobody asked for — a public, schema'd
endpoint went missing from its own app's docs because one consumer wanted no
wrapper.

### `:rest/request` is what the CALLER SENDS, wherever it travels

One schema covers the path segments, the query string and the body. A GET sends
a query string for the same reason a POST sends a body, and the generated client
reads the method to decide which carrier each key takes.

Each carrier is decoded by what its own wire can express, and the asymmetry is
the security posture rather than a detail:

| carrier | `[:qty :int]` given `"7"` | why |
|---|---|---|
| query / path | decoded to `7` | a URL segment is always text — there is no other way to carry a number |
| JSON body | **refused** | JSON carries real numbers, so a string is a client error, and repairing it would publish a contract you do not actually require |

Decoded **in place**: your handler reads `:path-params`, `:query-params` and
`:body` where it always did and finds them typed. `?depth=banana` against a
declared `[:depth :int]` is a 400 before your handler runs.

**`:rest/response` describes the DOCUMENT, not the bytes.** For an ordinary
endpoint that is automatic — the boundary round-trips your handler's return
through the wire before judging it. For one that serializes its OWN body
(`:http/raw` + a `:rest/media-type`), the boundary DECODES the envelope first,
so your schema still describes what a consumer reads. A media type slopp cannot
decode leaves the body a string, and then `:rest/response :string` is an honest
description of an endpoint that really answers text — rather than a type that is
true of any envelope and silent about everything inside it.

**A request contract is CLOSED: a key it does not name is a 400 that names the
key.** This is the one place a malli schema in slopp says what is FORBIDDEN
rather than only what is required — everywhere else (responses, `:malli/schema`
on ordinary functions) a schema still means "at least this". Closed at the top
level only, so a nested map you declared keeps whatever openness you gave it.

It matters because the failure it replaces was silent: a caller passing an
extra key got a correct response and a correct render, and the only witness was
the other service's access log. If you are calling an API with a map you happen
to have — route params, app state — send what the contract names, not what you
are holding. General HTTP content is unaffected: a page declares no contract,
so `?utm=x` on a link meets no schema at all.

**A GENERATED builder refuses the same thing before the request leaves**, so
you get the key named without a round trip. It is plain set membership emitted
from the contract's declared keys — no malli, which is what keeps the `:cljc`
builder namespace requiring nothing and reachable from `:pure` views. Path
segments stay allowed whether or not the contract names them, since the builder
needs them to build the url at all. **Regenerate after upgrading**
(`generate_client`) or your builders keep the old, permissive shape — nothing
prompts you, because the contract has not drifted.

!!! note "Typed params are a property of the capability being ON"

    The decoding happens when the boundary runs, so a handler receives typed
    params with `rest` enabled and text without it. Writing handlers against the
    typed shape commits you to the capability — turning it off then changes
    behaviour, not just checking.

