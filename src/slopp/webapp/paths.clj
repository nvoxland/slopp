(ns slopp.webapp.paths
  "Publishing the paths a project's BROWSER owns — every declared
  `:webapp/client-routes` prefix, as data.

  The third sibling of `slopp.rest.paths` and `slopp.http.paths`, and the one
  whose rows are not routes the server has. A client-routed app owns paths the
  router has no entry for: `/store/ns/foo` is real to the browser and
  meaningless to the dispatcher, which is why the framework generates a scoped
  catch-all per declared prefix.

  **Published as DECLARED, never as expanded.** The catch-alls are rows nobody
  wrote; three `/store/*client-path` entries in a document would read as
  surface somebody did. Same call `slopp.rules.http/endpoints` already makes
  for `query_surface`.

  Derived from VAR METADATA like its siblings, so it answers identically from
  a live store, a jar and a native binary, and ships in the slim jar. `webapp`
  requires `http`, so reaching `slopp.http.routes` and `slopp.http.paths` here
  is legitimate."
  (:require [slopp.http.routes :as routes]
            [slopp.http.paths :as http.paths]))

(defn ^:export paths-document
  "The paths `ns-syms`' BROWSER owns, as data — one row per declared
  `:webapp/client-routes` prefix.

  `{:slopp/webapp-paths-version 1
    :paths [{:prefix \"/store\" :document-path \"/\" :name shell
             :handler my.app/shell :doc \"…\" :auth :public
             :shell \"/assets/cljs/main.js\"}]}`

  **One row per PREFIX, not per var.** A document declaring
  `[\"/store\" \"/ns\"]` owns two, and they are two answers to *who serves this
  path* — a consumer routing a click needs the prefix, not the var.

  **Declared, never expanded.** `slopp.http.routes/client-route-rows`
  generates a `/store/*client-path` catch-all per prefix so a refreshed deep
  link reaches the app, and those are route-table entries rather than
  published rows. A row nobody wrote reads exactly like a route an author
  typed and a reader cannot tell them apart — the same call
  `slopp.rules.http/endpoints` makes for `query_surface`.

  `:document-path` is the SERVER route of the var that owns the prefix,
  because a prefix alone does not say where the browser fetches the page that
  then routes client-side. A form therefore appears here AND in
  `slopp.http.paths/paths-document`: it is a served document and also the
  owner of browser-side paths. Deliberate, and neither document is a subset of
  the other.

  Filtered on the MARKER rather than on the path pattern, which is why the
  declared-row check reads `:http/path`: the generated catch-alls carry the
  same handler and the same metadata, so a path-shaped filter would be a guess
  where the marker is a fact."
  [ns-syms]
  {:slopp/webapp-paths-version 1
   :paths
   (vec (for [row    (routes/from-namespaces ns-syms)
              :let   [m (meta (:handler row))]
              ;; the DECLARED row only — a var contributes one catch-all per
              ;; prefix pointing at the same handler with the same metadata,
              ;; so without this every prefix would be published N+1 times
              :when  (= (:path row) (str (:http/path m)))
              prefix (:webapp/client-routes m)]
          (cond-> {:prefix        (str prefix)
                   :document-path (:path row)
                   :name          (:name m)
                   :handler       (symbol (str (:ns m)) (str (:name m)))
                   :doc           (http.paths/undent (:doc m))
                   :auth          (:http/auth m)}
            (:webapp/shell m) (assoc :shell (:webapp/shell m)))))})
