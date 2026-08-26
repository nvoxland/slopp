(ns slopp.http.paths
  "Publishing the CONTENT an app serves — every `:http/path` form, as data.

  The sibling of `slopp.rest.paths` and the reason the two are separate: a
  contract describes a TYPED api and a document is not one. That exclusion was
  always about typing — a generated wrapper whose `(.json resp)` runs against
  HTML is nonsense — and never about visibility, but for a while it read as
  both, so a remote consumer of a slopp project could discover every API and
  zero pages.

  **Describe the value; never ship it.** A row says what SHAPE a page is, how
  big, and what it answers with. An index that carried bodies would put a
  stylesheet on the wire to render one table row, and the reader wanting the
  body has the URL.

  **What is published is what is SERVED, not what was declared**, wherever the
  two differ. `slopp.http.html/content-response` derives a media type from the
  value when the def declares none, so publishing the declaration would report
  nil for the most important page in a store — the shell that declares nothing
  and serves `text/html`.

  Derived from VAR METADATA and the def's VALUE, like `slopp.http.routes` next
  door, so it answers identically from a live store, a jar and a native binary
  — and so it ships in the slim jar. `undent` lives here rather than beside one
  publisher because all three share it and `rest` and `webapp` both require
  `http`."
  (:require [slopp.http.routes :as routes] [clojure.string :as str]))

(defn ^:export undent
  "A docstring with its SOURCE INDENTATION removed, or nil.

  A docstring's first line begins right after the opening quote and every later
  line carries however far the form is indented in its file. That indentation
  is a fact about our formatting, and publishing it puts it in every consumer's
  copy of the contract — the same trap a multi-line schema `:doc` falls into,
  one grain up.

  Line and PARAGRAPH structure survive: only the common leading run is removed,
  so a blank-line break still separates paragraphs for a consumer that renders
  them. A consumer that would rather collapse it all to one line still can; the
  reverse is not available if we flatten it here."
  [s]
  (when s
    (let [[head & tail] (str/split-lines s)
          indents (for [l tail :when (seq (str/trim l))]
                    (count (re-find #"^[ \t]*" l)))
          n       (if (seq indents) (apply min indents) 0)]
      (str/trimr
       (str/join "\n" (cons (str/trim head)
                            (map #(if (>= (count %) n) (subs % n) (str/triml %))
                                 tail)))))))

(defn ^:export paths-document
  "The CONTENT `ns-syms` serve, as data — what a consumer needs to list a
  project's pages without opening its store.

  `{:slopp/http-paths-version 1
    :paths [{:method :get :path \"/style.css\" :name style
             :handler my.app/style :doc \"…\" :auth :public
             :media-type \"text/css\" :shape :text :bytes 14263}]}`

  **`:media-type` is EFFECTIVE, not declared**, and that is the key this
  document exists to get right. `slopp.http.html/content-response` derives one
  from the VALUE when the def declares none — a vector is `text/html`,
  anything else `text/plain` — so a document built from declarations reports
  nil for the shell, which is the most important page in every real store and
  the one that declares nothing. A consumer cannot tell that nil from a page
  that genuinely has no type. Same move `slopp.rest.paths/paths-document`
  makes with its `application/json` default, for the same reason: a remote
  consumer has no store to ask.

  **DESCRIBES the value and never carries it.** `:shape` is `:hiccup` for a
  vector and `:text` otherwise — the discriminator the whole content model
  uses — with `:root-tag`/`:nodes` for the first and `:bytes` for the second.
  An index carrying bodies would put a stylesheet on the wire to render one
  table row, and a reader who wants the body has the URL. `:root-tag` answers
  whole-document vs fragment in one key, because `html/render` prepends the
  doctype for `:html` and not otherwise.

  `:shell` rides along when the document declares one, so a consumer listing
  pages can say which of them boots an application. The nodes counted are the
  value AS AUTHORED: what is served has the bundle injected by
  `complete-shell`, which needs a mount base this document does not have and
  would make the count depend on where the app happens to be mounted.

  Client-route prefixes are NOT here. A `:webapp/client-routes` var is a
  served document and also the owner of browser-side paths; the second half is
  `slopp.webapp.paths/paths-document`'s question, and a form appearing in both
  documents is deliberate rather than duplication.

  `:doc` is the def's own docstring, de-indented and whole — so a content
  handler's docstring is public API copy too, and it is MARKDOWN
  (D-doc-markdown). `:auth` is the declaration verbatim; the auth write gate
  refuses a route that declares none, so a consumer never has to tell public
  from unknown. `:handler` is the qualified symbol because `:name` alone does
  not resolve.

  A value that is neither hiccup nor a string is described exactly as it will
  be SERVED — `content-response` `str`s it — rather than special-cased here.
  Publishing something other than what the app answers with would be the one
  failure this document cannot afford."
  [ns-syms]
  {:slopp/http-paths-version 1
   :paths
   (vec (for [row  (routes/from-namespaces ns-syms)
              :let [m (meta (:handler row))]
              ;; a :webapp/client-routes var contributes catch-all rows
              ;; pointing at the SAME handler; they are one document, so keep
              ;; the declared path only
              :when (and (= :content (:kind row))
                         (= (:path row) (str (:http/path m))))
              :let [value   (var-get (:handler row))
                    hiccup? (vector? value)]]
          (cond-> {:method   (:method row)
                   :path     (:path row)
                   :name     (:name m)
                   :handler  (symbol (str (:ns m)) (str (:name m)))
                   :doc      (undent (:doc m))
                   :auth     (:http/auth m)
                   :media-type (or (:http/media-type m)
                                   (if hiccup?
                                     "text/html; charset=utf-8"
                                     "text/plain; charset=utf-8"))
                   :shape    (if hiccup? :hiccup :text)}
            hiccup?       (assoc :root-tag (first value)
                                 :nodes (count (tree-seq vector? seq value)))
            (not hiccup?) (assoc :bytes (count (str value)))
            (:webapp/shell m) (assoc :shell (:webapp/shell m)))))})
