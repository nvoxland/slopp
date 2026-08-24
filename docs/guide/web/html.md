# HTML and CSS

Pages are store forms like everything else. There is no template directory,
because there are no files: a page is a `def` holding hiccup data, and a
stylesheet is a `defn` returning garden data.

That is not a stylistic preference. A template file is one opaque blob to the
merge, the reference graph, and the trace map. A page built from forms
merges per component, renames with `edit_rename`, and re-runs the tests that
touch it.

## Content is a stored value

A content endpoint is a **`def`**, not a `defn`. Its value *is* what gets
served.

```clj
(def ^{:http/method :get :http/path "/about" :http/auth :public}
  about
  "The about page."
  [:main
   [:h1 "About"]
   [:p "Hiccup data, stored. Rendered on the way out."]])

(def ^{:http/method :get :http/path "/robots.txt" :http/auth :public
       :http/media-type "text/plain"}
  robots
  "User-agent: *\nDisallow:\n")
```

Hiccup renders as `text/html`; anything else is served as it stands, as
`text/plain`. A `:http/media-type` you declare is used verbatim. There is no
extension-based guessing -- a def named `robots.txt` is a var, not a file.

`render` prepends `<!DOCTYPE html>` to a top-level `[:html ...]`, which is what
lets an app hold a whole document:

```clj
(def ^{:http/method :get :http/path "/" :http/auth :public}
  home
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:title "Orders"]
    [:link {:rel "stylesheet" :href "/styles/app.css"}]]
   [:body [:main [:h1 "Orders"]]]])
```

Title, language, stylesheets, meta tags: written where they go. There is no
page-shell helper and no options map, because there is nothing left for one to
assemble.

A content response also carries the hiccup it rendered from, as `:http/hiccup`
beside the `:body`. An HTTP adapter writes the body and ignores it; a reader
that wants the page rather than the bytes reads it -- which is how `cljnx`
drives a page as structure, with real regions and real links, instead of as
escaped markup.

## Dynamic means API

`:http/path` names content that does not vary. Anything that computes an answer
from a request is `:rest/path` -- a REST API, asked for a contract, published in
the API document, generated a client for.

A `defn` under `:http/path` is not a handler slopp calls. It is a value slopp
serves, so it renders as the string of its own function object. If a page needs
per-request data, it is either an API or a single-page app calling one.

That split is recent. There used to be one path marker, so a page was an
endpoint like any other: it was asked for a `:rest/response` (`:string`, a lie
about an HTML body), and then needed `:rest/client false` to suppress the typed
fetch wrapper that followed. Both declarations existed only to undo a question
the page should never have been asked.

## A single-page app: the shell

A SPA's document declares the bundle it boots, and the framework completes it:

```clj
(def ^{:http/method :get :http/path "/" :http/auth :public
       :webapp/shell "/js/main.js"}
  shell
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:title "Orders"]
    [:link {:rel "stylesheet" :href "/styles/app.css"}]]
   [:body [:div {:id "app"}]]])
```

Two things are added on the way out, and only two: the `<script>` at the end of
the `[:head ...]` you wrote, and `data-base` on your mount point. Those are the
two facts a stored value cannot hold -- where the compiled bundle is served, and
what prefix this app is mounted under, which an app served at `/p/x/store`
cannot tell from its own url.

Your half of the contract is a `[:head ...]` and a mount point (`[:div {:id
"app"}]`, or the `#app` shorthand). A document missing either refuses when the
app is assembled rather than at request time, because the symptom is a blank
page and a blank page implicates everything.

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
        }
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
