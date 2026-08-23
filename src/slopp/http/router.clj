(ns slopp.http.router
  "Reading a URL, and nothing else. The two halves of one — match the PATH
  against a route table, parse the QUERY STRING into data — because they are
  the same question asked of the two sides of the `?`.

  Pure: request data in, decision data out. No identity, no policy, no
  handler, no socket. `slopp.http.dispatch` is what puts those in order around
  it; this namespace never learns they exist, which is why it is also the only
  part of the framework `slopp.api` exports (`match` carries
  `:export \"slopp.api\"` — the store's route analysis reasons about paths with
  the same matcher that serves them, so the two cannot disagree).

  Two rules carry most of the weight:

  - **Precedence is fewest-captures-wins, and a trailing `*rest` ranks far
    below both a static segment and a single-segment `:capture`.** That is what
    makes a catch-all safe to add: it can never steal a route that already
    matched, so a static mount or an SPA fallback is a pure addition.
  - **A malformed query pair is dropped, never thrown.** A query string is
    arbitrary text off the network, and the answer to garbage is a page, not a
    500. A bare key is PRESENT with an empty value — present-and-empty is a
    different fact from absent, and a handler must be able to tell them apart."
  (:require [clojure.string :as str] [slopp.lang :as lang]))

(defn ^{:export "slopp.rules"
        :teach "matches :uri ALONE — :query-string is a separate request key. A \"/x?y=z\" put in :uri matches no route and 404s, which looks exactly like the 404 you were testing for."}
  match
  "Match `method` + `uri` against `routes` (rows carrying :method :path
  :handler, the query_surface shape). Returns the matched row with
  `:path-params` merged ({:id \"42\"} for \"/api/users/:id\"), or nil.

  A `:x` segment captures ONE segment. A TRAILING `*x` captures the REMAINDER —
  one or more segments, slash-joined ({:path \"cljs/main.js\"} for
  \"/assets/*path\") — which is what lets a static mount serve a TREE; a `*`
  anywhere but last never matches. Precedence is fewest-captures-wins, and a
  catch-all ranks below BOTH a static segment and a single-segment capture, so
  adding one never steals an existing route. A trailing slash is tolerated.
  Pure — request data in, decision data out."
  [routes method uri]
  (let [segs   (fn [s] (vec (remove str/blank? (str/split (str s) #"/"))))
        u      (segs uri)
        cap?   #(str/starts-with? % ":")
        splat? #(str/starts-with? % "*")
        ;; a catch-all is the loosest possible match — rank it far below a
        ;; single capture so static > :one > *rest holds
        rank   (fn [ps] (+ (count (filter cap? ps))
                           (* 100 (count (filter splat? ps)))))
        row-match (fn [{:keys [path] :as row}]
                    (let [p (segs path)]
                      (when (if (some splat? p)
                              (>= (count u) (count p))
                              (= (count p) (count u)))
                        (loop [p p, u u, params {}]
                          (cond
                            (empty? p)
                            (when (empty? u)
                              (assoc row :path-params params ::captures (rank (segs path))))

                            ;; trailing catch-all: swallow the rest (>= 1 segment)
                            (splat? (first p))
                            (when (and (= 1 (count p)) (seq u))
                              (assoc row :path-params
                                     (assoc params (keyword (subs (first p) 1))
                                            ;; each segment was encoded on its
                                            ;; own, so each decodes on its own
                                            ;; and THEN joins — a static mount
                                            ;; serves a tree
                                            (str/join "/" (map lang/decode-component u)))
                                     ::captures (rank (segs path))))

                            (cap? (first p))
                            ;; DECODED, and after the split rather than before
                            ;; it: %2F is a slash in the VALUE, not a segment
                            ;; boundary, so decoding the whole uri first would
                            ;; silently re-segment the address. The other half
                            ;; of `lang/encode-component`, which builds every
                            ;; link — the pair was split across the round trip
                            ;; with only one end wired, so a var named
                            ;; `register!` arrived as `register%21` and 404d
                            ;; while `projects` worked.
                            (recur (rest p) (rest u)
                                   (assoc params (keyword (subs (first p) 1))
                                          (lang/decode-component (first u))))

                            (= (first p) (first u))
                            (recur (rest p) (rest u) params)

                            :else nil)))))]
    (some-> (->> routes
                 (filter #(= method (:method %)))
                 (keep row-match)
                 (sort-by ::captures)
                 first)
            (dissoc ::captures))))
