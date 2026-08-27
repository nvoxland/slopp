(ns slopp.http.endpoint
  "An endpoint as a DESCRIPTOR — one var carrying its address, method, declared
  params and contracts — and the one function that turns one into a request.

  **Why a descriptor rather than a generated builder.** Generation used to emit
  a `-request` function and a `-check` function in two namespaces, and neither
  carried the other's half: the builder discarded the response schema entirely
  and used the request schema only as a boolean. The split was there to dodge a
  tier — a check calls malli, malli reads as IO under the functional-core gate,
  and a builder shipped beside one could not be named by a `:pure` view. A
  descriptor REFERENCES its schemas, so it is data, so the question disappears
  and the two facts about an endpoint stay together.

  **One request shape, two performers.** A browser performs with `fetch`,
  a server with `slopp.rest.client/call!`, and both receive a finished
  `:http/url` — every decision about the address is made here, once. That is
  the same split `slopp.cljnx` makes for drivers.

  **Both halves of a request live here, and one of them arrived late.** The
  address is what [[request]] resolves; the method spelling, the headers and
  WHICH encoder a body wants are what [[request-init]] decides. Those used to
  sit in `slopp.webapp`, so the server-side performer required the BROWSER's
  namespace to encode a body. That is not merely untidy: the framework is
  vendored per capability, so the require resolved in a store that had opted
  into `webapp` and in no other.

  `:cljc` and `:pure` deliberately: a page names a descriptor from a view, and
  a JVM test reads the request a page will make without a browser anywhere."
  (:require [clojure.string :as str]
            [slopp.lang :as lang]))

(defn ^:export
  ^{:malli/schema
    [:=> {:throws [[:map [:rest/error [:enum :undeclared-params]]]]}
     [:cat [:map
            [:http/path :string]
            [:http/method {:optional true} :keyword]
            [:http/params {:optional true} [:maybe [:set :keyword]]]
            [:rest/request {:optional true} :any]
            [:rest/response {:optional true} :any]]
      [:? [:maybe [:map-of :keyword :any]]]]
     [:map [:http/method :keyword] [:http/url :string]]]}
  request
  "The request `descriptor` names, with `params` applied — `{:http/method
  :http/url}` plus `:http/body` and the contracts when there are any.

  A DESCRIPTOR is one generated var per endpoint:

  ```clojure
  {:http/method   :get
   :http/path     \"/api/form/:id\"
   :http/params   #{:id :depth}
   :rest/request  contracts/form-query      ; when the endpoint declares one
   :rest/response contracts/form-view}
  ```

  **One var, because the two facts about an endpoint always travel together.**
  Generation used to emit a `-request` builder and a `-check` in two
  namespaces, and the split existed to dodge a tier: a check calls malli, malli
  reads as IO under the functional-core gate, and a builder shipped beside one
  was tiered out of reach of the `:pure` views that name it. A descriptor
  REFERENCES its schemas instead of calling them, so it stays `:pure` and the
  question does not arise.

  It is a var rather than a bare path string so the reference graph keeps
  seeing it: `query_depends` on a descriptor answers *which pages call this
  endpoint*, renames follow it, and dead-surface counts it. A url typed at a
  call site is invisible to all three.

  **The address is resolved HERE, once.** Both performers — `fetch` in a page,
  `slopp.rest.client/call!` on a server — receive a finished `:http/url` and
  decide nothing about it. A mount prefix is applied later, to the built
  request, because where an app is served is not something a descriptor knows.

  **Two encoders, because a `/` means two different things.** In a `:name` or
  `*` segment a slash is DATA trying to become structure and is escaped; in a
  `**` remainder it IS structure, so each sub-segment is encoded on its own and
  then joined — the exact inverse of what both matchers do on the way in. Get
  that backwards and the round trip stops closing, which is how a var named
  `register!` once arrived as a form nobody had defined.

  **What the path does not consume is placed by the VERB**: a body verb sends
  it as `:http/body`, anything else as a query string. `:rest/request` already
  means *what the caller sends*, and the method already says how it travels —
  no second declaration.

  **A param the descriptor does not name is REFUSED.** The measured failure is
  a caller passing the map it HAD, its own route params, with the extras riding
  out as a query string to a service that never asked. A descriptor with no
  `:http/params` enumerates nothing and therefore guards nothing: a guard
  invented from a gap refuses what the boundary would accept."
  ([descriptor] (request descriptor {}))
  ([{:http/keys [method path params] :as descriptor} supplied]
   (let [segs   (str/split (str path) #"/" -1)
         wild?  #{"*" "**"}
         cap?   #(str/starts-with? % ":")
         ;; the path's OWN parameters are always allowed: the url cannot be
         ;; built without them, and whether a contract ought to name its own
         ;; segments is the boundary's question rather than this one's
         in-path (set (keep #(cond (cap? %)  (keyword (subs % 1))
                                   (wild? %) :*)
                            segs))
         allowed (when (seq params) (into (set params) in-path))
         extra   (when allowed (remove allowed (keys supplied)))]
     (when (seq extra)
       (throw (ex-info (str "this endpoint's contract does not name "
                            (pr-str (vec (sort extra)))
                            " — " (str method) " " path " takes "
                            (pr-str (vec (sort allowed))))
                       {:rest/error :undeclared-params
                        :rest/undeclared (vec (sort extra))
                        :http/path path})))
     (let [enc    lang/encode-component
           ;; a REMAINDER: each sub-segment on its own, then joined. `**`
           ;; matches zero segments, so an absent value is legal and empty
           remain (fn [v] (str/join "/" (map enc (remove str/blank?
                                                        (str/split (str v) #"/")))))
           seg    (fn [s] (cond (cap? s)   (enc (get supplied (keyword (subs s 1))))
                                (= "**" s) (remain (:* supplied))
                                (= "*" s)  (enc (:* supplied))
                                :else      s))
           url    (str/join "/" (map seg segs))
           body?  (contains? #{:post :put :patch} method)
           rest'  (apply dissoc supplied in-path)
           query  (when (and (seq rest') (not body?))
                    (->> (sort-by key rest')
                         (remove (comp nil? val))
                         (map (fn [[k v]] (str (enc (name k)) "=" (enc v))))
                         (str/join "&")))]
       (cond-> {:http/method (or method :get)
                :http/url    (str url (when (seq query) (str "?" query)))}
         (and body? (seq rest'))     (assoc :http/body rest')
         ;; an endpoint may name the BASE it is measured from — a hub endpoint at
         ;; the origin, reached by an app mounted under a prefix. Carried
         ;; through rather than interpreted: `slopp.webapp/addressed` is the
         ;; only thing that reads it, and a mount is a browser fact
         (:webapp/base descriptor)   (assoc :webapp/base (:webapp/base descriptor))
         (:rest/request descriptor)  (assoc :rest/request (:rest/request descriptor))
         (:rest/response descriptor) (assoc :rest/response (:rest/response descriptor)))))))

(defn ^:export
  ^{:malli/schema [:=> {:throws []} [:cat [:maybe :string]] [:maybe :string]]}
  media-type
  "The MEDIA TYPE inside a `Content-Type` header — `nil` for a header that is
  absent or empty.

  A browser answers `application/json; charset=utf-8`, so a decoder map keyed
  by `\"application/json\"` misses on the string the response actually carries.
  Taking the parameters off is a decision, small enough to look like none, and
  it belongs on this side of the seam for the reason every other one does: in
  the shim it would be a string-split nothing can run, and its failure mode is
  every response falling through to the default decoder — which for JSON means
  a screen rendering a string of JSON rather than the data in it.

  `nil` rather than `\"\"` when there is no header, so a caller's `get` reaches
  its default instead of matching an entry somebody keyed by the empty string."
  [content-type]
  (let [t (str/trim (str/lower-case (str content-type)))
        t (str/trim (first (str/split t #";")))]
    (when (seq t) t)))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map
                          [:http/method {:optional true} [:maybe :keyword]]
                          [:http/headers {:optional true} [:maybe [:map-of :string :string]]]
                          [:http/body {:optional true} :any]]]
                   [:map [:method :string] [:headers [:map-of :string :string]]
                    [:encode :keyword]]]}
  request-init
  "Everything `fetch` needs about a request except its URL — as DATA, with the
  encoder NAMED rather than run.

  The browser shim may not branch, because a namespace whose only verification
  is that it compiled must not be where a decision lives. So every judgement
  moves here: the method and its spelling, the headers, and WHICH encoder the
  body wants. What the shim does with `:encode` is one `get` into a map of
  encoders — the choice was already made, in a function this test suite drives.

  **The encoder follows the DECLARED content type**, and JSON is the default
  rather than the only option. That asymmetry was real and the consuming app
  named it: slopp's own API publishes `application/edn`, so a framework that
  could only send JSON could not POST to the endpoints slopp itself serves.

  | declared `Content-Type` | `:encode` |
  |---|---|
  | absent, or `application/json` | `:json` |
  | `application/edn` | `:edn` |
  | anything else | `:text` — the body AS GIVEN |

  `:text` is the honest answer rather than a gap: slopp does not know how to
  encode for `image/png`, and guessing JSON would corrupt what the caller
  handed over.

  `:encode` is `:none` when there is no body, whatever the declared type says.
  `fetch` throws on a GET carrying one, so a request that quietly acquired an
  empty body would stop working rather than send something harmless.

  **`false` is a body and `nil` is not**, which is the nil-pun this framework
  keeps removing, in the one place it would silently drop a value somebody
  meant to send.

  **Headers are in the shape from the start**, rather than after the first app
  needs them. A token is STATE and not schema — nothing about an endpoint
  declaration can produce it — so an app without this key would fall straight
  back to writing its own `fetch`, which is the whole thing being avoided. A
  declared header WINS over the default content type, which is also what makes
  the table above reachable at all."
  [{:http/keys [method headers body]}]
  (let [has-body? (some? body)
        declared  (media-type (get headers "Content-Type"))]
    {:method  (str/upper-case (name (or method :get)))
     ;; starts from {} so a request with no body and no declared headers
     ;; still answers a MAP — `clj->js` on nil is null, and a caller reading
     ;; (get (:headers init) …) would be asking a nil the same question
     :headers (merge {} (when has-body? {"Content-Type" "application/json"})
                     headers)
     :encode  (if has-body?
                (case declared
                  "application/edn" :edn
                  ("application/json" nil) :json
                  :text)
                :none)
     :body    body}))
