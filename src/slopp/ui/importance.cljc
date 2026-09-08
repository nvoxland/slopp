(ns slopp.ui.importance
  "How much of a namespace each of its definitions accounts for, as a small
  ordinal a view can stain a name with.

  Its own namespace for the reason `slopp.ui.graph` is: this is COMPUTATION the
  view consumes, and it deserves tests that do not go through hiccup. The
  arithmetic here is the only part that can be quietly wrong — a colour is
  either applied or it is not, but a ranking can be plausible and false, which
  is the failure mode nothing but an assertion catches.

  It takes the WIRE shape — `GET /api/ns/:ns` form rows, with `:mass`,
  `:calls`, `:callers-out` — because those are facts only the store can
  compute. Weighting them and cutting the result into steps is drawing, and
  drawing is this project's job: the same split that took the laid-out
  `:picture` out of `/api/modules` (D-ui-hub part 5). The API ships the call
  graph; nothing here asks it for a score.

  Read [[steps]] first — it is the entry point and its docstring carries the
  weights, the reason they are RANKS rather than raw values, and the one limit
  worth knowing before trusting the output.")

(defn reach
  "Everything reachable from `start` in adjacency map `g`, transitively,
  excluding `start` itself unless something leads back to it.

  A worklist over a SET rather than a recursive walk, which is what makes
  mutual recursion terminate: `p` calls `q` calls `p` visits each once and
  returns both. A form that calls itself reaches itself, which is the honest
  answer — the recursion is part of what you read.

  Edges to names the map does not hold are dropped. That is not defensiveness:
  `:calls` is same-namespace by construction, but a caller can hand this a
  subset (the listing is filtered), and a callee that was filtered out is not
  in this namespace's picture any more."
  [g start]
  (loop [seen  #{}
         queue (vec (filter g (get g start)))]
    (if-let [n (first queue)]
      (if (seen n)
        (recur seen (subvec queue 1))
        (recur (conj seen n)
               (into (subvec queue 1) (filter g (get g n)))))
      seen)))

(defn weigh
  "`{name {:covers n :carries n}}` for `rows` — the wire's form rows, each with
  `:name`, `:mass` and `:calls`.

  Two axes, both weighted by mass, both INCLUDING the form itself:

  - `:covers` — the form plus everything in this namespace it transitively
    calls. *What you take on by reading it.*
  - `:carries` — the form plus everything that transitively calls it. *What
    stops making sense if it goes.*

  Both are needed and neither is enough. Fan-in alone is the intuitive answer
  and it is wrong: a form the whole app runs through can have NO caller inside
  the store at all, so a fan-in metric ranks it last. The example began as
  `views/app-view`, whose only caller was `client.app`; that form was deleted
  and the example moved to `chrome`, which the framework called from outside
  the reference graph.

  It has now weakened honestly, and the replacement is better: under Move A a
  PAGE calls `slopp.ui.pages/chrome`, so seventeen callers are in the graph.
  What carries the argument today is a page itself — nothing calls
  `pages/timeline-page`, because the route table resolves it from a
  `^{:webapp/path …}` marker at runtime.

  Size alone is wrong the other way: a big leaf renderer is not the spine.
  `covers` finds entry points and orchestrators, `carries` finds the nine-line
  helper that five things lean on.

  A missing `:mass` counts as zero rather than throwing. A row the wire did not
  fill should not take the whole ranking down with it, and a form of unknown
  size is honestly not evidence of importance.

  Mutual recursion gives both forms the same `:covers`, which is right — you
  cannot read either without the other."
  [rows]
  (let [mass (into {} (map (juxt :name #(or (:mass %) 0))) rows)
        out  (into {} (map (juxt :name #(vec (:calls %)))) rows)
        in   (reduce (fn [acc {:keys [name calls]}]
                       (reduce (fn [a c] (update a c (fnil conj []) name))
                               acc (filter out calls)))
                     (into {} (map (juxt :name (constantly []))) rows)
                     rows)
        ;; the UNION, not the sum of two parts: in a cycle `reach` already
        ;; contains `n`, and adding its mass separately would count the form
        ;; twice for the crime of being recursive
        sum  (fn [g n] (reduce + 0 (map #(mass % 0) (conj (reach g n) n))))]
    (into {} (for [{:keys [name]} rows]
               [name {:covers (sum out name) :carries (sum in name)}]))))

(defn steps
  "`{name step}` for `rows`, where step is `0`..`n-1` — light to dark.

  **Percentile RANKS, never raw values.** `covers` is in AST nodes (5..5000)
  and `:callers-out` is a count (0..30); any formula over the raw numbers needs
  normalising constants, and those constants would be tuned on one store and
  wrong on the next. Ranks are scale-free and distribution-free, so the same
  weights behave on a four-form namespace and a forty-form one. The weights
  below are a judgement; this part is not.

  Ranked WITHIN the namespace, because the question is \"important here\", and
  because a small namespace should still use the whole ramp.

  ```
  0.60  covers        what you must read to understand it
  0.25  carries       what stops making sense without it
  0.15  callers-out   how much of the REST of the store leans on it
  ```

  `:callers-out-test` is deliberately unused. A form called by four deftests
  and one caller is better EXERCISED, not more important — and slopp found that
  the hard way: with test callers folded in, `views/module-graph` took first
  place on four callers, every one a `deftest`, while `app-view` sat fourth.
  The field ships as its own integer so this stays a choice rather than a
  silent mix.

  **A known limit, and it is the definition working rather than failing:** a
  small pass-through ranks low. `views/app-shell` receives the page as an
  argument instead of calling it, so `covers` is honestly small — and only one
  form calls it, so `carries` is small too. Every axis agrees, which means no
  reweighting lifts it. Its importance is in what flows THROUGH it, and the
  call graph carries control flow, not data flow. Do not add a field to fix
  this; that would be a fact requested to make one expectation come true.

  Ties get the same step. One row gets the top step — a lone definition is not
  a ramp, and a mid-grey would say something about a comparison that never
  happened."
  [rows n]
  (if (empty? rows)
    {}
    (let [w    (weigh rows)
          rank (fn [vs] (let [sorted (sort vs) c (count sorted)]
                          ;; the share of values at or below this one, so ties
                          ;; share a rank and the top is always 1.0
                          (fn [v] (/ (double (count (take-while #(<= % v) sorted)))
                                     c))))
          axis (fn [f] (let [r (rank (map f rows))] (comp r f)))
          cov  (axis (fn [r] (:covers (w (:name r)) 0)))
          car  (axis (fn [r] (:carries (w (:name r)) 0)))
          out  (axis (fn [r] (or (:callers-out r) 0)))
          score (fn [r] (+ (* 0.60 (cov r)) (* 0.25 (car r)) (* 0.15 (out r))))
          ;; bucket by the RANK of the score, not its absolute value. A
          ;; CONSTANT axis — every form with zero external callers, which is
          ;; the ordinary case for a leaf namespace — hands every row that
          ;; axis's top percentile and floors the whole listing, so the light
          ;; end of the ramp would never be reached and a namespace would
          ;; render uniformly dark for a reason having nothing to do with its
          ;; code. Ranking the scores makes the ramp relative to the namespace
          ;; the same way each axis already is.
          scored (mapv (juxt :name score) rows)
          rank-of (rank (map second scored))]
      (into {} (for [[nm s] scored]
                 [nm (min (dec n) (long (* n (rank-of s))))])))))
