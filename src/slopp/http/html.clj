(ns slopp.http.html
  "Pure hiccup→HTML rendering: escape-by-default (via hiccup 2.x, contract
  pinned by the SECURITY tests in slopp.http.html-test), validated tag and
  attribute names (hiccup renders crafted names VERBATIM — an injection door
  escaping does not cover), refused javascript:/data: URLs, and
  [:html/raw s] as the single raw-HTML door."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]))

(def ^:private url-attrs
  "Attributes whose value is a URL: a javascript:/data: scheme here survives
  escaping, so render refuses them."
  #{:href :src :action :formaction})

(defn- refused-url?
  "True for a string whose effective scheme is javascript: or data: —
  lowercased with whitespace and control characters stripped first, the way
  browsers parse it."
  [v]
  (boolean
   (when (string? v)
     (let [s (-> v str/lower-case (str/replace #"[\p{Cntrl}\s]" ""))]
       (or (str/starts-with? s "javascript:")
           (str/starts-with? s "data:"))))))

(defn- check-tag
  "Refuse a tag keyword hiccup would render verbatim: a plain element name
  (letters, digits, hyphens) optionally carrying #id/.class sugar."
  [tag]
  (when (or (namespace tag)
            (not (re-matches #"[a-zA-Z][a-zA-Z0-9-]*(?:[#.][a-zA-Z0-9_-]+)*" (name tag))))
    (throw (ex-info (str "invalid tag: " (pr-str tag)) {:tag tag}))))

(defn- check-attrs
  "Refuse attribute NAMES hiccup would render verbatim, and URL values whose
  scheme survives escaping (javascript:/data:)."
  [attrs form]
  (doseq [[k v] attrs]
    (when-not (and (keyword? k)
                   (nil? (namespace k))
                   (re-matches #"[a-zA-Z][a-zA-Z0-9-]*(?::[a-zA-Z0-9-]+)*" (name k)))
      (throw (ex-info (str "invalid attribute name: " (pr-str k)) {:attr k :form form})))
    (when (and (url-attrs k) (refused-url? v))
      (throw (ex-info (str "refused URL scheme in " k
                           " — javascript:/data: survive escaping; validate the value or serve a static asset")
                      {:attr k :value v :form form})))))

(defn- prepare
  "Validate hiccup data and convert [:html/raw s] islands for rendering.
  The teaching errors here target the common authoring mistakes: a map in
  child position (attrs go in position 2 only) and a vector used to group
  siblings (a vector is an element; a seq splices)."
  [x]
  (cond
    (vector? x)
    (let [tag (first x)]
      (cond
        (= :html/raw tag)
        (if (and (= 2 (count x)) (string? (second x)))
          (h/raw (second x))
          (throw (ex-info "[:html/raw s] takes exactly ONE string payload — it is the single escaping bypass"
                          {:form x})))

        (keyword? tag)
        (let [attrs    (second x)
              attrs?   (map? attrs)
              children (if attrs? (nnext x) (next x))]
          (check-tag tag)
          (when attrs? (check-attrs attrs x))
          (into (if attrs? [tag attrs] [tag]) (map prepare) children))

        :else
        (throw (ex-info (str "a vector is an ELEMENT and needs a keyword tag; "
                             "group siblings with a seq (for/map/list), got: " (pr-str x))
                        {:form x}))))

    (map? x)
    (throw (ex-info (str "a map in child position is not attributes — attrs go in "
                         "position 2 only; compute conditional attrs with cond->: " (pr-str x))
                    {:form x}))

    (seq? x) (map prepare x)
    :else x))

(defn ^:export render
  "Hiccup data → HTML string. Text and attribute values escape by default
  (the hiccup 2.x contract, pinned by slopp.http.html-test); tag and
  attribute names are validated; javascript:/data: URLs in
  href/src/action/formaction are refused; [:html/raw s] is the one
  raw-HTML door.

  **A top-level `[:html …]` gets `<!DOCTYPE html>` prepended.** A document
  without one renders in quirks mode, and quirks mode is a class of layout bug
  that looks like CSS. The rule is here rather than in a page HELPER because
  an app now writes its own document — `:http/path` names a stored hiccup
  value, so there is no assembly step left to hang it on. Only the top-level
  tag decides and only when it is exactly `:html`, so every fragment renders
  as it always did."
  [hiccup]
  (str (when (and (vector? hiccup) (= :html (first hiccup))) "<!DOCTYPE html>")
       (h/html {:mode :html} (prepare hiccup))))

(defn ^:export html-response
  "Ring response serving rendered hiccup as text/html. :http/raw true — both
  adapters write the body verbatim. opts may carry :status and extra
  :headers; Content-Type stays ours.

  **`:http/hiccup` carries the structure the body was rendered FROM.** An
  adapter writes `:body` and ignores it; a reader that wants the page rather
  than the bytes reads it — `slopp.http/driver` does, so a headless drive of a
  server-rendered page gets a tree instead of a string.

  It is here because this is the only place that has both. Downstream the
  hiccup is gone and getting it back would mean parsing HTML, which is a whole
  dependency and a lossy round trip to recover something that existed one
  function earlier. Same move as handing the whole response to the driver
  rather than its body: keep what you already hold.

  Costless to anyone who does not want it — the response is a map, and a key
  nobody reads is a key nobody pays for."
  ([hiccup] (html-response hiccup nil))
  ([hiccup {:keys [status headers]}]
   {:status  (or status 200)
    :http/raw true
    :http/hiccup hiccup
    :headers (merge headers {"Content-Type" "text/html; charset=utf-8"})
    :body    (render hiccup)}))

(defn ^:export content-response
  "A stored content value → a ring response. `media-type` is what the def
  declared, or nil.

  Two shapes, discriminated the way the whole content model is: **hiccup is a
  vector, and everything else is not.** Hiccup renders and carries
  `:http/hiccup`, so a headless drive of a static page reads the tree rather
  than parsing markup back out of the bytes — the same reason
  [[html-response]] carries it. Anything else is `str`'d and served verbatim.

  The default media type follows the shape rather than the path: hiccup is
  `text/html`, everything else `text/plain`. An extension says nothing here —
  a def named `robots.txt` is a var, not a file — so a def that answers
  anything else declares it."
  [value media-type]
  (if (vector? value)
    {:status 200
     :http/raw true
     :http/hiccup value
     :headers {"Content-Type" (or media-type "text/html; charset=utf-8")}
     :body (render value)}
    {:status 200
     :http/raw true
     :headers {"Content-Type" (or media-type "text/plain; charset=utf-8")}
     :body (str value)}))

(defn- transform-first
  "Replace the first node satisfying `pred` (depth-first, pre-order) with
  `(f node)`. Answers `[document found?]` — the flag is the point, since a
  shell missing what we came to change is a REFUSAL and not a no-op.

  Threading `found?` through the fold rather than carrying a mutable flag is
  not style: this namespace is `:pure`, and the gate is right that a walk with
  an escape hatch is the kind of thing that grows one."
  [document pred f]
  (letfn [(children [coll found?]
            (reduce (fn [[acc fd] c]
                      (let [[c' fd'] (step c fd)]
                        [(conj acc c') fd']))
                    [[] found?] coll))
          (step [n found?]
            (cond
              found?                          [n found?]
              (and (vector? n) (pred n))      [(f n) true]
              (vector? n)                     (children n found?)
              ;; a seq of children is hiccup's inline group, and returning a
              ;; vector would make it an ELEMENT — so it goes back a seq
              (seq? n)                        (let [[cs fd] (children n found?)]
                                                [(seq cs) fd])
              :else                           [n found?]))]
    (step document false)))

(defn- mount-element?
  "Is this hiccup node the app's mount point — `{:id \"app\"}` or the `#app`
  shorthand? Both spellings, because a hiccup author writes either and a page
  that renders nowhere is a blank screen with no message attached."
  [node]
  (let [tag   (first node)
        attrs (second node)]
    (or (and (map? attrs) (= "app" (:id attrs)))
        (and (keyword? tag)
             (some? (re-find #"#app(?:[.#]|$)" (name tag)))))))

(defn- stamp-attr
  "`node` with `k` set to `v`, inserting an attribute map when it has none.
  `[:div]` and `[:div {:id \"app\"}]` are both legal hiccup and the second
  element decides which — a child would be silently overwritten by a naive
  `assoc` at index 1."
  [node k v]
  (if (map? (second node))
    (update node 1 assoc k v)
    (into [(first node) {k v}] (rest node))))

(defn ^:export complete-shell
  "The app's SPA shell document, completed by the framework: `bundle` injected
  as a `<script>` at the end of the `<head>` the app wrote, and `base` stamped
  on its mount point as `data-base`. Returns hiccup; REFUSES a document that
  cannot carry either.

  **The app writes the whole document and the framework writes exactly two
  things**, which is the split worth stating: title, meta, stylesheets and
  everything else are hiccup the app already holds, so they need no
  declaration, no parameter and no assembly step. What is left is the two
  facts a stored value CANNOT hold — where the compiled bundle is served, and
  what prefix this app is mounted under, which an app served at `/p/x/store`
  cannot tell from its own url.

  **The mount point is `id=\"app\"`, and `slopp.webapp.dom/mount!` reads the
  same two names on the other side** — it looks that id up and reads the
  `data-base` attribute. That correspondence is the reason to REFUSE here
  rather than to inject a mount point: a shell whose author wrote `#root` gets
  a blank page in a browser and nothing anywhere says why, which is precisely
  what this function exists to end.

  Refusals name the shape rather than the symptom, because the symptom is a
  blank page and a blank page implicates everything."
  [document bundle base]
  (when-not (vector? document)
    (throw (ex-info (str "a :webapp/shell must be a hiccup DOCUMENT, and this"
                         " one is not — the framework injects the bundle script"
                         " into its head, and only hiccup has one")
                    {:http/shell-bundle bundle})))
  (let [[with-script found-head]
        (transform-first document
                         #(= :head (first %))
                         #(conj % [:script {:src bundle :defer true}]))
        [completed found-mount]
        (transform-first with-script mount-element?
                         #(stamp-attr % :data-base (or base "")))]
    (when-not found-head
      (throw (ex-info (str "this :webapp/shell has no [:head …], so there is"
                           " nowhere to inject the bundle script "
                           (pr-str bundle)
                           " — a shell is a WHOLE document:"
                           " [:html [:head …] [:body [:div {:id \"app\"}]]]")
                      {:http/shell-bundle bundle})))
    (when-not found-mount
      (throw (ex-info (str "this :webapp/shell has no mount point, so the app"
                           " has nothing to render into and the page stays"
                           " blank — add [:div {:id \"app\"}] to its body."
                           " slopp.webapp.dom/mount! looks that id up by name,"
                           " and the mount prefix is stamped onto it as"
                           " data-base")
                      {:http/shell-bundle bundle})))
    completed))
