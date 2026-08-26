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

  **Three pattern segments, and only three.**

  | `:name` | captures exactly ONE segment, bound under `:name` |
  | `*`     | matches exactly ONE segment, bound under `:*` |
  | `**`    | matches ZERO OR MORE segments, slash-joined under `:*` |

  Both wildcards are ANONYMOUS and both are END-ONLY. Anonymous because a
  static mount wants a tree rather than a vocabulary — the name a splat used to
  carry was threaded through three generators and read by one of them.
  End-only because matching a wildcard in the middle needs backtracking and the
  pattern that needs it is nearly always a mistake; Spring's `PathPattern`
  restricts `**` the same way and for the same reason.

  **`**` matching ZERO segments is what covers a prefix ROOT.** `/store/**`
  answers `/store` as well as `/store/form/9`. The older catch-all required a
  segment below it, so every client-routed section needed a second declaration
  for its own root — a rule authors had to know and nothing enforced.

  **A `*` that is not a whole segment is not a pattern here, and never matches.**
  `*path`, `*.css` and `pre*` are all refused at the write (`http-path-pattern`)
  and match nothing if one arrives anyway. Deliberately not read as a LITERAL
  segment: a retired `/assets/*path` read literally would answer a url whose
  text really is `*path` and 404 every real asset — a spelling that keeps
  working, on the wrong requests.

  **Precedence is POSITIONAL: rank each segment (literal 0 < `:name` 1 < `*` 2
  < `**` 3) and compare the vectors LEFT TO RIGHT, shorter winning when one is
  a prefix of the other.** So the longest static prefix wins, `/my/**` beats
  `/**`, `*` beats `**`, and an exact route beats a `**` that also covers it —
  each a consequence of one rule rather than a rule of its own. It is what
  reitit and Spring's `PathPattern` both do, and the servlet spec's
  exact-then-longest-prefix ordering falls out of it.

  This replaces a global score (captures + 100×splats), which TIED patterns of
  equal counts and broke the tie by position in the route vector — and that
  vector comes from `(vals (ns-publics …))`, so the winner was not merely
  order-dependent but unreproducible between runs. Reachable between two
  framework-generated rows: a static tree mounted inside a client-routed
  prefix.

  A trailing slash is tolerated. Pure — request data in, decision data out."
  [routes method uri]
  (let [segs   (fn [s] (vec (remove str/blank? (str/split (str s) #"/"))))
        u      (segs uri)
        cap?   #(str/starts-with? % ":")
        one?   #(= "*" %)
        many?  #(= "**" %)
        ;; a pattern carrying a `*` anywhere this grammar does not have one.
        ;; Checked per ROW rather than refused here: this is pure and answers
        ;; with data, so an unmatchable pattern contributes no rows and the
        ;; write gate is what tells its author
        ill?   (fn [ps]
                 (boolean (some (fn [[i s]]
                                  (and (str/includes? s "*")
                                       (or (not (or (one? s) (many? s)))
                                           (not= i (dec (count ps))))))
                                (map-indexed vector ps))))
        rank   (fn [ps] (mapv #(cond (many? %) 3 (one? %) 2 (cap? %) 1 :else 0) ps))
        ;; left to right, first difference decides; all-equal-so-far means the
        ;; one that ENDED is the more specific — `/a` over `/a/**`
        rank<  (fn [a b] (or (first (remove zero? (map compare a b)))
                             (compare (count a) (count b))))
        row-match (fn [{:keys [path] :as row}]
                    (let [p (segs path)
                          done #(assoc row :path-params % ::rank (rank p))]
                      (when-not (ill? p)
                        (loop [p p, u u, params {}]
                          (cond
                            (empty? p)
                            (when (empty? u) (done params))

                            ;; zero or more, so this arm comes BEFORE the
                            ;; empty-uri test — that ordering is the whole of
                            ;; "** covers its own root"
                            (many? (first p))
                            (done (assoc params :*
                                         ;; each segment was encoded on its
                                         ;; own, so each decodes on its own and
                                         ;; THEN joins — a static mount serves
                                         ;; a tree
                                         (str/join "/" (map lang/decode-component u))))

                            (empty? u) nil

                            (one? (first p))
                            (when (= 1 (count u))
                              (done (assoc params :* (lang/decode-component (first u)))))

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
                 (sort-by ::rank rank<)
                 first)
            (dissoc ::rank))))
