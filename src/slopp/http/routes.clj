(ns slopp.http.routes
  "Where an app's shape comes from: **var metadata, read off the loaded
  namespaces.** A public var carrying `:http/path` IS a route; a var carrying
  `:http/read` or `:http/effect` IS an entry in a performer vocabulary. There is
  no route table to keep in step with the code, because there is no route
  table.

  That makes this the UNIVERSAL source — a live store, a jar, and a native
  binary all answer from the same var metadata, and it is the same contract
  `query_surface` reads off the STORED node. The store-side gates and this
  namespace are two readings of one declaration, which is the only reason a
  write-time refusal can predict a runtime behaviour.

  One consequence worth stating plainly, since it is silent: **a namespace
  that isn't loaded contributes nothing.** Not an error — nothing. That is why
  `slopp.http/context` checks the assembled result against what the routes
  declare rather than trusting the namespace list it was handed.

  `client-route-rows` is the one place this namespace generates rather than reads, and
  it is deliberately narrow: a client-routed document declares the prefixes it
  owns (`:webapp/client-routes [\"/store\"]`) and gets one scoped catch-all each. A root
  catch-all would be worse than the bug it fixes — an app that can never 404
  has no way left to distinguish a typo from a page.")

(defn ^:export client-route-rows
  "The catch-all rows a client-routed document contributes — one per declared
  prefix in `:webapp/client-routes`, all pointing at the same handler as `row`.

  A client-routed app owns paths the server has no route for: `/store/ns/foo`
  is real to the browser and meaningless to the router, so refreshing it 404s.
  The obvious fix — a catch-all at the root — is worse than the bug: it serves
  the app document for EVERY unmatched path, and an app that can never 404 has
  no way left to distinguish a typo from a page.

  So the fallback is DECLARED and SCOPED. `:webapp/client-routes [\"/store\"]` says \"I am
  the document for client routes under /store\", and paths outside every
  declared prefix still 404 exactly as before.

  No new matching rules are needed. The router already ranks a trailing
  catch-all far below both a static segment and a single-segment capture, so
  these rows cannot steal a real route no matter what order they are in —
  `/store/ns/:ns` still wins over `/store/*`.

  Note the prefix ROOT is not covered: `[\"/store\"]` generates `/store/*path`,
  which needs at least one segment below it. If `/store` itself should render
  the app, that is an ordinary route the app declares — an intent, not a
  side effect of a fallback."
  [row prefixes]
  (vec (for [p prefixes]
         (assoc row :path (str p "/*client-path")))))

(defn ^:export from-namespaces
  "Route rows from the loaded namespaces' public vars carrying `:rest/path`
  (a REST API) or `:http/path` (general HTTP content) — the UNIVERSAL route
  source: a live store, a jar, and a native binary all answer from var
  metadata, the same contract query_surface reads off the stored node. A
  namespace that isn't loaded contributes no rows.
  Rows: {:handler <the var, callable> :method :path :kind :auth :http/effects
  :http/reads :effectful?}.

  **Both markers, because this is what actually serves.** The store-side
  traversal (`edit.http/web-endpoint-rows`) is a different walk over a
  different input, so a marker landing in one and not here is a route that
  passes every write gate, appears in `query_surface`, and 404s — with nothing
  able to say why. `:kind` rides the row so the dispatcher and the contract
  never re-derive which marker carried the path.

  A var may also carry `:webapp/client-routes` — a vector of path prefixes it serves as the
  client-routed document — and then contributes one extra catch-all row per
  prefix (see `client-route-rows`), so a refreshed deep link reaches the app instead of
  a 404. Scoped deliberately: paths outside every declared prefix still 404,
  which is the property a root catch-all would destroy."
  [ns-syms]
  (vec
   (mapcat
    (fn [{:keys [row client-routes]}]
      (if (seq client-routes)
        (cons row (client-route-rows row client-routes))
        [row]))
    (for [ns-sym ns-syms
          :let   [nsx (find-ns (symbol ns-sym))]
          :when  nsx
          v      (vals (ns-publics nsx))
          :let   [m    (meta v)
                  kind (cond (:rest/path m) :rest
                             (:http/path m) :content)]
          :when  kind]
      {:client-routes (:webapp/client-routes m)
       ;; the CONTRACT rides the row when the endpoint declared one. The
       ;; dispatcher holds a row at request time and nothing else, so a
       ;; schema that is not here cannot be honoured — which is exactly why
       ;; the wire crossing was unchecked until `rest` existed. `cond->`
       ;; rather than plain keys: an endpoint that declares no contract must
       ;; carry no key, because nil-because-absent and nil-because-broken
       ;; would otherwise be the same row.
       :row (cond-> {:handler   v
                     :method    (:http/method m)
                     :path      (str (or (:rest/path m) (:http/path m)))
                     :kind      kind
                     :auth      (:http/auth m)
                     :http/effects (:http/effects m)
                     :http/reads   (:http/reads m)
                     :effectful? (boolean (:http/effectful m))}
              (:rest/request m)  (assoc :rest/request (:rest/request m))
              (:rest/response m) (assoc :rest/response (:rest/response m)))}))))

(defn ^:export performers-from-namespaces
  "The performer vocabulary off loaded var metadata: {kind → the var,
  callable} for `marker-key` (`:http/effect` or `:http/read`) — the runtime
  twin of the store-side derivation the gates check. A namespace that
  isn't loaded contributes nothing."
  [ns-syms marker-key]
  (into {}
        (for [ns-sym ns-syms
              :let   [nsx (find-ns (symbol ns-sym))]
              :when  nsx
              v      (vals (ns-publics nsx))
              :let   [kind (get (meta v) marker-key)]
              :when  kind]
          [kind v])))
