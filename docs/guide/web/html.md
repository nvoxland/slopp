# HTML and CSS

Server-rendered pages are store forms like everything else. There is no
template directory, because there are no files: a page is a `defn` returning
hiccup data, and a stylesheet is a `defn` returning garden data.

That is not a stylistic preference. A template file is one opaque blob to the
merge, the reference graph, and the trace map. A page built from `defn`s
merges per component, renames with `edit_rename`, and re-runs the tests that
touch it.

## A page

```clj
(defn order-row
  "One row of the orders table."
  [{:keys [id total]}]
  [:tr [:td [:a {:href (str "/orders/" id)} id]] [:td total]])

(defn ^{:http/method :get :http/path "/orders" :http/auth :authenticated
        :http/reads {:orders [:order/for-user [:http/identity :http/sub]]}
        :rest/response :string :rest/client false}
  orders-page
  "The orders list."
  [req]
  (html/html-response
   (html/page {:html/title "Orders"
               :html/head [[:link {:rel "stylesheet" :href "/styles/app.css"}]]}
     [:main
      [:h1 "Orders"]
      [:table (for [o (:orders (:http/reads req))] (order-row o))]])))
```

`page` is the full-document shell: doctype, charset, an escaped title, optional
`:html/lang`, and whatever extra `:html/head` elements you pass. It emits no
inline `<script>` or `<style>`, so a strict Content-Security-Policy needs no
carve-outs. `html-response` renders and wraps as a `text/html` Ring map.

It also carries the hiccup it rendered from, as `:http/hiccup` beside the
`:body`. An HTTP adapter writes the body and ignores it; a reader that wants
the page rather than the bytes reads it -- which is how `cljnx` drives a
server-rendered page as structure, with real regions and real links, instead
of as escaped markup. The two views come from one call site, so they cannot
disagree; recovering the structure downstream would have meant parsing the
HTML back into a second derivation of the same page.

An HTML page is a `:http/path` endpoint like any other, so it would otherwise
get a typed fetch wrapper whose `.json()` can never succeed. `:rest/client
false` opts it out. Declare it rather than relying on the response schema to
imply it: `:string` is a perfectly good JSON response, so nothing but you can
tell HTML from JSON.

## The rules that actually bite

**Attributes are position 2, and always a map or absent.**

```clj
(cond-> {:class "todo"} done? (update :class str " done"))   ; yes
(when done? {:class "done"})                                 ; no
```

A `when` in position 2 is not a conditional attribute map -- when the test is
false it is a vanishing *child*, and the next element shifts into the attribute
slot.

**A vector is an element; a seq splices.** Repeat with `for` or `map`. Never
group siblings in a vector, which produces an element whose tag is your first
child.

**Everything escapes, and you never pre-escape.** `[:html/raw s]` is the one
door, and it takes a string payload only. Crafted tag and attribute *names*
survive escaping, so `render` validates them; `javascript:` and `data:` URLs in
`:href`, `:src`, `:action` and `:formaction` are refused outright, matched the
way a browser parses them (lowercased, whitespace and control characters
stripped).

**No React attribute names.** `:class`, not `:className`; `:for`, not
`:htmlFor`; no `:onClick`-style handlers. The `http-react-attrs` gate refuses
them because browsers silently ignore unknown attributes, so the mistake ships
and does nothing.

**One component per `defn`.** A thin page shell composing small component
functions is the merge grain, the test grain, and the thing that keeps each
piece `=`-testable data. Test on data first, then pin one rendered string per
component.

## Links are checked

Literal `:href`, `:src` and `:action` values are indexed. Route rows carry
`:rendered-by` -- which forms link to them -- and at done time
`http-dangling-route-refs` fails a link to a path no declared route or static
mount serves. The UI nil pun is that a broken link ships and 404s in front of a
user; this catches it at the same moment as a failing test.

```clj
query_surface {}     ; check the path before writing the link
```

`(str "/orders/" id)` checks by prefix. A fully dynamic path is reported
`:unresolved` and rides along as information -- never counted clean, never
status-flipping. When something outside this store serves the path, say so on
the rendering form:

```clj
^{:http/external-path "the marketing site serves /pricing"}
```

## CSS is garden

Same story, one layer down. A stylesheet is a `defn` GET endpoint returning
`css-response`, and its rules are data:

```clj
(defn ^{:http/method :get :http/path "/styles/app.css" :http/auth :public
        :rest/response :string :rest/client false}
  app-stylesheet
  "The application stylesheet, as garden data."
  [_req]
  (css/css-response
   [[:body {:font-family "system-ui, sans-serif" :max-width "50rem"}]
    [:main [:a {:color "#2a6"}]]
    (gs/at-media {:prefers-color-scheme :dark}
                 [:body {:background "#111" :color "#ddd"}])]))
```

`render` serializes minified and validates every selector and value string
against block breakout: `{`, `}` and `<` throw, because garden renders strings
verbatim and an interpolated value is otherwise an injection door. `;` is
allowed -- data URIs use it, and without a `}` a stray `;` can only add a
declaration to the same rule.

The `:href` in the page's `[:link ...]` is a literal, so the dangling-route
check ties the stylesheet endpoint to every page linking it, like any other
route.

Raw or vendored CSS is not a renderer problem: `file_put` the `.css` and serve
it through an `http.static.*` mount. See [static
assets](running.md#static-assets).

## Seeing it

```clj
query_eval "(slopp.http/handle! (slopp.http/context {:http/namespaces ['shop.ui]})
                               {:request-method :get :uri \"/orders\"})"
```

Full pipeline, rendered HTML in the response map, no server. Under `--live` an
edited page hot-serves and the browser only needs F5.

!!! note "Partial updates"
    There is no htmx or fragment-swap integration yet. Interactive behaviour
    today is [ClojureScript](client.md) or a full page load.
