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
;; a PAGE declares its own address
(defn ^{:webapp/path "/things"} things
  "Every thing, listed."
  [page]
  [:main (for [t (:value (webapp/ask! page api/things {}))] [:li (:name t)])])

;; the entry declares the app, and no table at all
(defn ^:app/entry app []
  {:webapp/state  state                       ; your atom
   :webapp/chrome (fn [state inner] [:div [nav state] inner])})
```

That is the whole application. **The goal is that your own code declares no
`:cljs` namespaces at all**, and it is stated as a test rather than as an
aspiration -- `query_surface`'s `:webapp` section reports the count, and zero is
the claim being true.

## A page declares its own address

The address lives on the function that renders it, exactly as `:http/path` and
`:cli/command` live on the forms that implement them. Everything follows from
that one placement: a write gate refuses two pages claiming one address,
`/api/webapp/paths` publishes them, and the reference graph answers *which
endpoints does this page call*.

**You do not write a route table.** Both entries derive it from the markers --
the browser's is generated into the entry the build writes, the headless one is
scanned off the loaded vars. The address used to appear twice, as a marker and
as a `:webapp/routes` row, with nothing to notice when the two disagreed. (A
declared `:webapp/routes` still wins, for an app with a reason to build its
own.)

**The addresses are addresses, not pages.** A page's address is not unique and
an address's page is not unique -- one page answers at several URLs the moment
you have a lens bar, a print view, or a detail page that also takes an optional
segment. Anything that reasons "one address per page" is wrong: a count, a
completeness check, a generated index.

!!! tip "Prefer a row to a runtime table of what could exist"
    The tempting alternative for a lens is to peel the trailing segment off,
    retry the subject underneath, and check the peeled part against a second
    table. Now that table has to reject `/things/bogus`, and it is one edit away
    from meaning *what could exist* instead of *what renders*. In a real app
    that drift made five dead URLs answer 200 and draw the default view, with a
    reader certain they had looked at something they had not. With rows there is
    nothing to consult: an address no row matches is not-found, the same answer
    the server gives.

## A page is a function of one map

```clj
(defn ^{:webapp/path "/things/:id"} thing
  "One thing, in detail."
  [{:keys [params]}]
  [:article [:h1 (str "Thing " (:id params))]])
```

The map is the app's own declaration plus `:state` and `:params`, so a page
reads what it needs and nothing else has to be threaded to it.

`:webapp/view` is **derived** and declaring one is refused. The address points
at the page function itself, so the `:screen` keyword that used to have to
agree in three separate places -- the router returned it, the view cased on it,
the fetch received it, with nothing checking any of the three -- has nothing
left to mistype.

`:webapp/loading`, `:webapp/failed` and `:webapp/not-found` are declared screens
with plain framework defaults, and your `:webapp/chrome` decides *where* they
sit. The split is structural: not-found replaces the whole page, so the
framework can render it outright; loading and failed replace one pane while the
rest of the app stays up, and placement is layout, which is the one thing this
capability does not take.

## A page asks for what it needs

```clj
;; an endpoint DESCRIPTOR — one def, generated, carrying its own contract
(def thing
  {:http/method   :get
   :http/path     "/api/things/:id"
   :http/params   #{:id :depth}
   :rest/response contracts/thing})

;; and a page that asks for it, while rendering
(defn ^{:webapp/path "/things/:id"} thing-page
  "One thing."
  [{:keys [params] :as page}]
  (let [{:keys [status value]} (webapp/ask! page api/thing {:id (:id params)})]
    (if (= :ready status)
      [:article [:h1 (:name value)]]
      [:article "…"])))
```

`ask!` is **start-if-absent**: it answers `{:status :value}` and starts the load
if nobody has. That is what makes it safe to call from a render -- the page
re-runs when the answer lands, asks again, and finds it already there. Ask for
as many endpoints as you like, wherever you like; there is no per-page request
slot to fit them into, and no declaration beside the address that could describe
only one of them.

slopp turns the descriptor into a finished URL and performs it; the browser
entry supplies the performer, so you never write `js/fetch`. Which endpoint a
page calls is a fact the reference graph reads, which is why
`/api/webapp/paths` can publish it without anybody declaring it twice.

**Nothing evicts a load, so `stale!` is yours to call.** After a write that
changes what an endpoint answers, `(webapp/stale! page api/thing {:id id})`
drops that entry and the next `ask!` re-fetches it. An automatic sweep of what
a render did not ask for is the obvious rule, and it is exactly the kind slopp
declines to guess at: a wrong eviction shows an empty pane an app cannot
explain.

`:check` is how a load refuses what it was sent -- `(fn [response] -> nil |
message)`, where nil accepts and a message makes the load `:failed` carrying it,
with `:xform` never run. It runs inside the freshness guard, so a superseded
answer is not validated either.

!!! warning "A rejection is a value, never a throw"
    Validating inside `:xform` and throwing gives you two behaviours from one
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

## A page may answer a redirect

```clj
(defn ^{:webapp/path "/"} landing
  "The projects open here -- or the one project, entered directly."
  [page]
  (let [{:keys [status value]} (webapp/ask! page api/projects {})]
    (if (and (= :ready status) (= 1 (count value)))
      {:webapp/redirect (str "/p/" (:slug (first value)))}
      [:ul (for [p value] [:li (:slug p)])])))
```

A page answers hiccup -- a vector -- or `{:webapp/redirect "/path"}`, and the
two cannot be confused because top-level hiccup is never a map. The framework
follows the redirect in the render that received it: the address bar is
**replaced**, never pushed, so the back button skips the page that sent the
reader on instead of bouncing them forward again; the state arrives at the
destination exactly as a navigation would; and the destination renders in the
same pass, so nothing paints the sender first. It is a client route only --
leaving the app is what a `:leaves?` action is for -- and two pages sending
the reader to each other refuse as a named loop rather than hanging.

Headless, `cljnx/url` follows it: the driver carries the app's own address bar
as `:location`, so a test that opens `/` and reads the url sees where the
reader ended up, the same fact it reads after a server's 302.

## Loads have four states

```clj
(webapp/load-status state :main)   ; :absent :loading :ready :failed
(webapp/load-value  state :main)
```

Read both, never the value alone. "Nobody asked" and "answered nil" are
different facts about the world, and `(if (:data s) ...)` cannot tell them
apart -- which is how a silent retry loop gets built on a failing endpoint.

On navigation, `:webapp/address-keys` are dropped with the route. **Every
other key survives -- loads included** -- and a plain state key outlives a page
by saying nothing.

!!! warning "Do not read that as 'state is wiped'"
    It is the sentence that sends a reader with a nav rail to move the value
    *into* `:loads` to protect it. `:webapp/address-keys` is for the keys that
    must die with a route; everything else is yours until you drop it.

### A load that belongs to no page needs no declaration

A nav pane, a signed-in user, anything read on several pages: every page that
shows it calls `ask!` for it, the first call starts it, and the rest find it
already there. There is no session-load declaration, because there is nothing
left for one to say.

That deleted a real trap along with the key. `:webapp/session-loads` named
which loads survived a navigation -- a membership rule honoured by the
framework's own `arrive` and by nothing else -- so the load machinery travelled
to an app that navigated with its own code and the scope guarantee did not. One
app read that sentence three times without noticing it named a function they
never called. A guarantee stated where it is *implemented* reads as
unconditional at the point it is *consumed*.

Note which `boot` is which. `:webapp/boot` -- the app's own pure `(fn [state]
state)` -- is unchanged. The `:boot` that takes a url is the one on the headless
driver contract, and `slopp.webapp/driver` derives it for you; you only write
that signature if you hand `slopp.cljnx/open!` a page by hand.

`webapp/load!` is public, because *what* you load is your question and the
*machinery* is not. `ask!` is the layer over it; run something through `load!`
directly when you are starting a load `ask!` cannot express, and you still get
the state model, a minted freshness token and the supersession guard. The
alternative is what one app measured before this existed, where a load kept
outside the machinery acquired `(nil? value)` as its guard, a silent retry loop,
and no token.

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

**A request carries the mount point too.** Write `:http/path "/api/things"` on
the descriptor
and slopp addresses it under your base before performing it, exactly as it does
an `:href`; an absolute url (a scheme, or the protocol-relative `//` a CDN link
takes) is left alone. That makes three base-aware paths -- the pushed url, the
arriving url, and the outgoing request -- and it was two until an app served
under `/p/<slug>` found every one of its screens fetching a 404.

When a request is *not* measured from your mount point, say where it is
measured from:

```clj
(assoc (endpoint/request modules {}) :webapp/base (str "/api/p/" slug))
(assoc (endpoint/request projects {}) :webapp/base "")   ; the origin
```

That is the escape an absolute URL cannot cover -- an app calling a different
application at the same origin cannot spell the whole URL, because the origin is
only known at runtime.

!!! warning "A base that points at document space fails as a **200**"

    Give it the prefix your API is served under, not the one your *pages* are.
    A client-routed app answers the SPA shell for everything below its own
    route prefixes, so a request measured from there gets `200 text/html` and
    the whole document -- which then reaches your JSON decoder wearing a
    success status. Measured on a real hub: `/api/p/slug/api/modules` returns
    the project's JSON, `/p/slug/api/modules` returns the shell. A 404 would
    have been the kinder failure.

It is declared per request rather than per app because that is where the fact
lives, and the reason is sharper than "the same app's other requests *are*
mounted". **Which API a request belongs to is route state.** A client-routed app
switches which upstream it is reading by navigating, not by loading a page, and
the app-level base is stamped once at page load. No value delivered that way can
be right for an app that talks to more than one API -- so the base is a default
and the request has the last word.

`:webapp/base ""` is what the retired `:webapp/from-origin` boolean used to say.
That flag is *gone*, not deprecated: nothing reads it, so a request still
carrying it is addressed under the app's base like any other. It was an escape
from a field that could not hold two values, and once the field holds one per
request the escape has no cause left.

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

(def s (cljnx/open! (webapp/driver app) "/p/demo/things"))  ; open AT a URL
(cljnx/click! s "Anvil")
(cljnx/text s "main")
(cljnx/url s)                         ; the address bar
(cljnx/status s)                      ; the status, as a number
```

No browser, no compile, no headless Chrome -- and the same functions the real
page runs. You hand it an address the way you hand a browser one; `visit!` moves
it afterwards. Use the full URL including the mount point, because that is what
is in the address bar; a drive that visited app-relative paths would exercise a
URL no browser ever produces.

For a served page the address goes down the real request pipeline, so redirects
are followed and `url` reports where you *ended up*. Assert a status with
`status` rather than searching the page for the string `HTTP 404`: a whole-page
match is one keystroke from asserting nothing in particular.

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

With `webapp` on, this emits **one `.cljc` namespace** of endpoint
descriptors rather than the `.cljs` fetch wrappers a server-rendered client
gets:

```clj
;; app.client.api — one def per endpoint, reaching only the contracts
(def ^{:generated "shop.api/get-order"} ^:export get-order
  {:http/method   :get
   :http/path     "/api/orders/:id"
   :http/params   #{:id :depth}
   :rest/response app.client.contracts/order})
```

Turn one into a request with `slopp.http.endpoint/request` and drop that into a
row's `:request`. The capability decides the shape and there is no flag: which
artifact is useful *follows* from who performs the request, and a store that
has declared that should not have to declare it twice.

!!! note "One namespace, because a schema referenced is data"
    This used to be two: builders here and contract checks in a sibling,
    because a check calls malli and the functional-core gate reads that as IO.
    Shipped together, the builders inherited the tier and an app whose views
    are `:pure` could not name them at all.

    A descriptor *references* its schema instead of validating against it, so
    it is data, so the split has no cause left. The response contract now
    travels on the same var that carries the address -- which is also why
    there is no longer a second var to keep in step.

Two things this buys beyond tidiness. A descriptor is **portable**, so which
URL a screen will ask for is an ordinary in-image value rather than a string
assembled in a browser. And it carries `:rest/response`, which is the response
validation back -- that used to live in the fetch wrappers, and when the
framework took over performing it went with them.

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
  A literal `:href` is joined against the served table; an endpoint
  descriptor's `:http/path` is the same claim in a different key. The failure is quiet: the
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
- **`query_surface`'s `:webapp` section** -- every address, the page that
  renders it, the endpoints that page calls, every action with its kind, and
  the `:cljs` count.

!!! note "What the surface could not read, it names"
    The addresses are metadata on your pages, so they cannot be half-declared.
    `:webapp/actions` is still a map literal, and a declaration written as a
    var (`:webapp/actions actions`) or built with a `cond->` is skipped rather
    than guessed at. Every skip lands in `:unreadable`, whether it cost a whole
    section or one entry -- because an entry quietly dropped reads as an app
    that declares fewer than it does, and an empty `:webapp/actions` is worse
    still: `[]` is an affirmative claim of emptiness, not an absence. Silence
    there means everything read cleanly.

## Vendoring

An app that declares a `:webapp/path`, a `:webapp/shell` or
`:webapp/client-routes` is using the browser framework, so slopp vendors
`slopp/webapp*` into its tree and declares what that framework itself requires.
A `^:app/entry` alone does **not** trigger it: a page declares *here is an entry
a reader can open*, which a server-rendered app wants too.

**A family arrives with the families it requires.** `webapp` requires `http`,
because a browser app has to be served -- so a store vendored the browser family
is vendored the server one too. It has to be: `slopp.webapp` itself reaches into
`slopp.http.endpoint`, and a family handed over without what it requires lands
intact and then fails inside itself.

The two axes move independently and conflating them produces wrong predictions.
*Which* families a store gets is re-derived on every image launch from the
current store, so enabling a capability mid-session takes effect on the next
one. *What is in* a family comes from the running process's own jar and is
frozen until it restarts.
