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

  **`:webapp/client-routes` contributes NOTHING here, and this paragraph used
  to say the opposite.** A var carrying it once produced one extra catch-all
  row per prefix, so a refreshed deep link reached the app instead of a 404. A
  shell declares its own `**` path now, so the catch-all IS the declaration and
  there is nothing to synthesize.

  The prose outlived the code by two commit-points and cited a function that had
  been deleted, which is exactly how a retired marker goes on reading as a live
  declaration: a store kept declaring it, sixteen deep links 404d on a hard
  load, and an in-app click to the same address worked. `edit.webapp/webapp-client-routes-retired`
  refuses the spelling at the write now — but the sentence that misled is this
  one, so it is the one that had to change."
  [ns-syms]
  (vec
   (mapcat
    ;; ONE row per declaration. A client-routed document used to contribute a
    ;; generated catch-all per `:webapp/client-routes` prefix so a refreshed
    ;; deep link reached the app; a shell now declares its own `**` path, so
    ;; the catch-all IS the declaration and there is nothing to synthesize —
    ;; which also ends the class of row nobody wrote appearing in the table.
    (fn [{:keys [row]}] [row])
    (for [ns-sym ns-syms
          :let   [nsx (find-ns (symbol ns-sym))]
          :when  nsx
          v      (vals (ns-publics nsx))
          :let   [m    (meta v)
                  kind (cond (:rest/path m) :rest
                             (:http/path m) :content)]
          :when  kind]
      {:kind kind
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
              (:rest/response m) (assoc :rest/response (:rest/response m))
              (:http/media-type m) (assoc :http/media-type (:http/media-type m))
              (:webapp/shell m) (assoc :webapp/shell (:webapp/shell m)))}))))

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
