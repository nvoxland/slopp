(ns slopp.rest.endpoint
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
         (:rest/request descriptor)  (assoc :rest/request (:rest/request descriptor))
         (:rest/response descriptor) (assoc :rest/response (:rest/response descriptor)))))))
