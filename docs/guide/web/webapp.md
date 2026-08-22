# Browser applications

A browser app is the case where the *client* owns routing and state: the reader
clicks a link, the URL changes, a pane loads, and the server is never asked for
a page. Written by hand that is a lot of ClojureScript -- a router, a render
loop, a click listener, a dispatcher, a `fetch` -- and every line of it sits
outside the JVM oracle, verified by "it compiled" and nothing more.

The `webapp` capability takes all of it. You declare the application as data;
slopp owns the loop.

```clj
config_file {path "capabilities" key "webapp.enabled" value "true"}
```

```clj
(defn ^:app/entry app []
  (webapp/wiring
   {:webapp/state  state                       ; your atom
    :webapp/routes [["/"           index]      ; a bare fn IS a screen
                    ["/things"     things]
                    ["/things/:id" {:render  thing          ; a screen that
                                    :request thing-request  ; asks for its
                                    :derive  unwrap}]]      ; own data
    :webapp/chrome (fn [state inner] [:div [nav state] inner])}))
```

That is the whole application. **The goal is that your own code declares no
`:cljs` namespaces at all**, and it is stated as a test rather than as an
aspiration -- `query_surface`'s `:webapp` section reports the count, and zero is
the claim being true.

## Routes are a table, not a function

A routing *function* answers only when it is called, with a path, at runtime. So
nothing can list your screens, join a link to one, or check that the server
serves what the browser routes. Declaring the table is what makes all three
possible, and a function is refused.

**The table is addresses, not screens.** A row's screen is not unique and a
screen's row is not unique -- one screen answers at several URLs the moment you
have a lens bar, a print view, or a detail page that also takes an optional
segment. Anything that reasons "one row per screen" is wrong: a count, a
completeness check, a generated index. Derive both numbers from the table rather
than asserting a literal.

!!! tip "Prefer a row to a runtime table of what could exist"
    The tempting alternative for a lens is to peel the trailing segment off,
    retry the subject underneath, and check the peeled part against a second
    table. Now that table has to reject `/things/bogus`, and it is one edit away
    from meaning *what could exist* instead of *what renders*. In a real app
    that drift made five dead URLs answer 200 and draw the default view, with a
    reader certain they had looked at something they had not. With rows there is
    nothing to consult: an address no row matches is not-found, the same answer
    the server gives.

## A screen is a function of state

```clj
(defn thing [state]
  [:article [:h1 (str "Thing " (:id (:params state)))]])
```

`:webapp/view` is **derived** and declaring one is refused. A row points at the
screen itself, so the `:screen` keyword that used to have to agree in three
separate places -- the router returned it, the view cased on it, the fetch
received it, with nothing checking any of the three -- has nothing left to
mistype.

**A screen is only called when its data is ready**, so it never writes the
three-way case on load status. `:webapp/loading`, `:webapp/failed` and
`:webapp/not-found` are declared screens with plain framework defaults, and your
`:webapp/chrome` decides *where* they sit. The split is structural: not-found
replaces the whole page, so the framework can render it outright; loading and
failed replace one pane while the rest of the app stays up, and placement is
layout, which is the one thing this capability does not take.

## A screen names its own request

```clj
(defn thing-request [params]
  {:webapp/method      :get
   :webapp/path        "/api/things/:id"
   :webapp/path-params {:id (:id params)}
   :webapp/query       {:depth "2"}
   :webapp/headers     {"Authorization" (str "Bearer " @token)}})
```

`:request` is a pure `(fn [params] -> request | nil)` in `.cljc`, so **which
call a URL makes is a fact an in-image test reads**. slopp turns the request
into a finished URL and performs it; the browser entry supplies the performer,
so you never write `js/fetch`.

A nil request declines and starts no load at all -- a screen with nothing to ask
for renders immediately rather than spinning. `:derive` shapes that screen's own
answer, and runs inside the freshness guard, so an abandoned load never pays for
it. An unknown key inside a screen map is refused: `:reqeust` is not a crash, it
is a screen that renders with no data forever at a URL that matched.

`:check` is how a screen refuses what it was sent -- `(fn [response] -> nil |
message)`, where nil accepts and a message makes the load `:failed` carrying it,
with `:derive` never run. It runs inside the freshness guard too, so a
superseded answer is not validated either.

!!! warning "A rejection is a value, never a throw"
    Validating inside `:derive` and throwing gives you two behaviours from one
    function. Headless the performer calls `ok` synchronously, so the throw
    escapes `load!` and takes the driver with it. In a page `ok` is called from
    inside a `.then`, so the same throw lands in the shim's `.catch` and renders
    a failure screen. That is the one difference this capability exists to
    prevent, and it cannot be closed by catching -- a `.cljc` form cannot catch
    on both platforms without the reader conditional the dialect denies. So the
    failure channel is a return value and nothing throws anywhere.

JSON and EDN are both read and both sent -- the encoder follows the declared
`Content-Type`, and a type slopp does not encode for sends the body as given
rather than guessing. `application/edn` is not an afterthought: slopp's own
`:http/raw` endpoints publish it, so a framework that could not read it could not
browse the API slopp itself serves.

Two properties are worth knowing because they are security properties rather
than conveniences:

- **Substitution is segment-wise.** A parameter whose name prefixes another
  corrupts a path under `str/replace` -- `/api/:module/:m` becomes
  `/api/yodule/y` -- and the corrupted result looks like a URL.
- **Every value is percent-encoded**, so a `/` in a value is data rather than
  structure and a `?` does not start a query. A value cannot break out of its
  segment.

!!! warning "A non-2xx is a failure, and slopp is the one that knows"
    `fetch` rejects only on a *network* error, so a 500 resolves happily. A
    hand-written performer hands that to its success path and the screen renders
    the error page's body as though it were data -- the screen fills with
    something, which is why it survives review. slopp checks the status in
    `.cljc` where a test watches it fail, and the same check is emitted into
    `generate_client`'s wrappers. Without it, a wrapper that validates its
    response reports a 502 from a proxy as *"response failed validation"*, so
    real contract drift and a dead server produce identical words.

## Loads have four states

```clj
(webapp/load-status state :main)   ; :absent :loading :ready :failed
(webapp/load-value  state :main)
```

Read both, never the value alone. "Nobody asked" and "answered nil" are
different facts about the world, and `(if (:data s) ...)` cannot tell them
apart -- which is how a silent retry loop gets built on a failing endpoint.

On navigation, `:loads` is emptied and `:webapp/address-keys` are dropped with
the route. **Every other key survives** -- a plain state key outlives a screen by
saying nothing.

!!! warning "Do not read that as 'state is wiped'"
    It is the sentence that sends a reader with a nav rail to move the value
    *into* `:loads` to protect it, which is the one action that makes it start
    dying on every navigation. `:webapp/address-keys` is for the keys that must
    die with a route; `:webapp/session-loads` is for a load you want the load
    machinery for *and* want to outlive a screen.

### A load that belongs to no screen is declared too

```clj
:webapp/session-loads {:modules {:request (fn [state] {:webapp/path "/api/modules"})
                                 :derive  :names}
                       :user    {}}
```

slopp starts each one at page load, after `:webapp/boot` and before routing --
boot first because a session request reads the state boot established (a token,
say), and routing last because the first screen may read a session load and
rendering before they are in flight shows a flash of empty chrome.

A session `:request` takes **state** where a screen's takes **params**: a
session load has no address, so there are no captures to hand it. A nil request
declines, which is how a load waits for a sign-in. Declaring one with no
`:request` -- `:user` above -- scopes it without starting it, for the load you
begin yourself.

This is the piece that stops a nav rail being the last thing forcing a `:cljs`
namespace on an app: `load!` takes `(fn [ok err])`, so a load nothing declares
has to be hand-written where the wiring is, and in a browser that means
ClojureScript.

`webapp/load!` is public, because *scope* is your question and the *machinery*
is not. Run your own loads through it and you get the state model, a minted
freshness token and the supersession guard; the alternative is what one app
measured before this existed, where a load kept outside the machinery acquired
`(nil? value)` as its guard, a silent retry loop, and no token.

## Links carry no mount point

Write client-route keys -- `:href "/things/42"` -- and slopp adds the mount
prefix on the way to the DOM. Links you do not route are left alone, so
`/api/...` still points at your server. One producer adds the prefix and one
consumer strips it, which is what makes an app served at `/p/demo/` work without
a single view knowing it.

A form's `:action` obeys the same rule, and it needs no second judgement: a
search box written as a GET form is a *navigation* -- a reducer is pure and
navigating is an effect, so a form needs no dispatcher and no bundle at all --
while a POST to `/api/save` matches no row and is left alone. Such a form
submits as a full page load, which is correct: it lands on the client route and
the app boots there.

**A request carries the mount point too.** Write `:webapp/path "/api/things"`
and slopp addresses it under your base before performing it, exactly as it does
an `:href`; an absolute url (a scheme, or the protocol-relative `//` a CDN link
takes) is left alone. That makes three base-aware paths -- the pushed url, the
arriving url, and the outgoing request -- and it was two until an app served
under `/p/<slug>` found every one of its screens fetching a 404.

When a request is *not* under your mount point, say so on the request:

```clj
{:webapp/path "/api/projects" :webapp/from-origin true}
```

That is the escape an absolute URL cannot cover -- an app calling a different
application at the same origin cannot spell the whole URL, because the origin is
only known at runtime. It is declared per request rather than per app, because
that is where the fact lives: the same app's other requests *are* mounted.

## Actions are declared, in three kinds

```clj
:webapp/actions {:thing/typed    {}
                 :thing/save     {:effectful? true}
                 :project/switch {:leaves? true}}
```

A plain state transition, an `:effectful?` request through the performer, and a
`:leaves?` full page load for a destination that is not a client route at all.
Declaring the kind is what stops a browser dispatcher and a headless one
drifting apart -- the split is *looked up* rather than judged, and a lookup
cannot disagree with the map it looks in. An action nothing can apply is refused
rather than silently doing nothing.

## Driving it without a browser

```clj
(require '[slopp.cljnx :as cljnx])

(def s (cljnx/open! (webapp/driver app)))
(cljnx/visit! s "/p/demo/things")     ; the URL a reader would type
(cljnx/click! s "Anvil")
(cljnx/text s "main")
```

No browser, no compile, no headless Chrome -- and the same functions the real
page runs. Visit the full URL including the mount point, because that is what is
in the address bar; a drive that visited app-relative paths would exercise a URL
no browser ever produces.

This is where the slow-response race stops being a heisenbug: hold the
performer's callback, navigate again, then answer the first one, and assert the
superseded answer never lands.

## What the browser is left with

One namespace, and it may not branch -- checked, because slopp has no
ClojureScript test runner and a `:cljs` namespace's only verification is that it
compiled. **If it cannot branch it cannot decide**, so every judgement a browser
app makes is in `.cljc` and driven by an ordinary test: the URL, the method and
headers, which encoder a body wants, which decoder an answer wants, and whether
a response is data or a failure.

| What an app used to write | Who has it now |
|---|---|
| a render loop | `:webapp/render`, supplied by the entry |
| `history.pushState` | `:webapp/push-url!` |
| `location.assign` | `:webapp/leave!` |
| a click listener with `.closest` and `preventDefault` | the framework's |
| a popstate listener | the framework's |
| `(.. e -target -value)` in a dispatcher | the framework's |
| `js/fetch` | `:webapp/call`, supplied by the entry |
| reading the mount prefix off the DOM | a `data-base` attribute |

The effect plug-ins **override** whatever the app declared rather than filling
gaps, so a canned performer left in your wiring for tests cannot ship to a page.

!!! note "It is a proxy, and worth naming as one"
    "The shim cannot branch" proves that code makes no decisions. It says
    nothing about whether the interop is correct -- a property-name typo
    compiles and fails in a browser, and no guard catches that. Read as coverage
    it would be worse than absent.

## Generation follows who performs

```clj
generate_client {}
```

With `webapp` on, this emits **two `.cljc` namespaces** rather than the `.cljs`
fetch wrappers a server-rendered client gets:

```clj
;; app.client.api — request builders, requiring NOTHING
(defn ^{:generated "shop.api/get-order"} ^:export get-order-request [params]
  {:webapp/method      :get
   :webapp/path        "/api/orders/:id"
   :webapp/path-params {:id (:id params)}
   :webapp/query       (dissoc params :id)})

;; app.client.checks — contract checks, which reach malli
(defn ^{:generated "shop.api/get-order"} ^:export get-order-check [response]
  ;; nil when the contract holds, a message when it does not
  …)
```

Drop the builder into a row's `:request` and the check into its `:check`. The
capability decides the shape and there is no flag: which artifact is useful
*follows* from who performs the request, and a store that has declared that
should not have to declare it twice.

!!! note "Two namespaces, because two tiers"
    A builder requires nothing and returns a map. A check reaches malli, which
    the functional-core gate reads as IO. Shipped together, the builders
    inherit the checks' tier — and an app whose views are `:pure` then cannot
    name them at all, so it hand-writes every request map instead: correct by
    inspection rather than by construction, which is the drift generation
    exists to remove. The requests namespace is declared `:pure` for you.

Two things this buys beyond tidiness. The builders are **portable**, so which
URL a screen will ask for is an ordinary in-image value rather than a string
assembled in a browser. And the checks are the response validation back — it
used to live in the wrappers, and when the framework took over performing it
went with them.

When the contract came from **somebody else's server** (`generate_client` with a
`from` url), each builder carries `^{:http/external-path …}` naming that url, so
`webapp-request-paths-are-served` does not report a path your store genuinely
does not serve. That has to be generated rather than added by hand: the
namespace is `^:generated`, and the next regeneration would drop a hand-written
marker silently.

**Where it lands matters, so the default moves for a browser app.** Without
`webapp` the target defaults to `<family>.client.api`; with it, to
`<family>.wire.api`. A browser entry's natural home is `<family>.client.*`, and
it requires the app, which reads the route table in your views — so a client
generated under `client` closes `views → client → app → views`, which no
declaration can open. `wire` also says the useful thing: **a consumed API is not
part of your browser layer.** An explicit `ns` still wins, and
`client`/`generated-ns` still wins over both.

Generation also **refuses to overwrite a namespace it did not write**. It
derives more names than the one you pass — the schemas and the checks are
siblings — and `ingest` sits below the per-form gates so regeneration can
replace its own output wholesale. That same property would make overwriting
your own code silent, so a collision is a refusal naming the namespace.

One consequence worth knowing: a `.cljc` client **loads**, where a `.cljs` one
never could. So regenerating leaves the process serving MCP holding forms the
store has moved past, and `restart` — which rebuilds the *verification* image —
does not clear that. The result says so at the point it happens.

## What slopp checks

Turning the capability on arms these:

- **`webapp-client-routes-are-served`** -- your client table against the
  prefixes the server answers for. A deep link the server does not serve 404s on
  a hard refresh, and works perfectly until someone reloads.
- **`webapp-request-paths-are-served`** -- the other half of a route reference.
  A literal `:href` is joined against the served table; a screen's
  `:webapp/path` is the same claim in a different key. The failure is quiet: the
  URL routes, the screen renders, chrome and nav are fine, and one pane always
  fails to load while everything around it works -- so it gets reported as
  slowness rather than as a missing endpoint. Two declarations stop it asking:
  a whole URL for a third-party server, and `^{:http/external-path "why"}` on the
  form when something outside your store serves the path -- a proxied API under
  your own mount point cannot be written in full, because the prefix is only
  known at runtime.
- **`webapp-client-routes-consequences`** -- the consequences of declaring
  client-owned paths.
- **`webapp-page-reach`** -- a `^:app/entry` whose namespace closure reaches
  `:cljs`, so the page can no longer be opened headlessly. Reported at the
  `module_platform` write that stranded it, because that write is what broke the
  reach and no later form change would hang the finding anywhere.
- **`webapp-client-code`** -- the `:cljs` namespaces you still hand-write, named
  with what each costs. This is the capability's goal arriving rather than
  waiting to be asked, and it is silent at zero. Advisory, never a refusal: a
  browser-only binding with no portable form is a real answer, and there is no
  marker to silence it because the finding *is* the inventory.
- **`query_surface`'s `:webapp` section** -- every address, the screen that
  renders it and the URL it loads, every declared session load, every action
  with its kind, and the `:cljs` count.

!!! note "What the surface could not read, it names"
    Everything here is read from *literals* in your store, so a declaration
    written as a var (`:webapp/actions actions`) or built with a `cond->` is
    skipped rather than guessed at. Every skip lands in `:unreadable`, whether
    it cost a whole section or one entry -- because an entry quietly dropped
    reads as an app that declares fewer than it does, and an empty
    `:webapp/actions` is worse still: `[]` is an affirmative claim of
    emptiness, not an absence. Silence there means everything read cleanly.

## Vendoring

An app that declares `:webapp/client-routes` is using the browser framework, so
slopp vendors `slopp/webapp*` into its tree and declares what that framework
itself requires. A `^:app/entry` alone does **not** trigger it: a page declares
*here is an entry a reader can open*, which a server-rendered app wants too.

The two axes move independently and conflating them produces wrong predictions.
*Which* families a store gets is re-derived on every image launch from the
current store, so enabling a capability mid-session takes effect on the next
one. *What is in* a family comes from the running process's own jar and is
frozen until it restarts.
