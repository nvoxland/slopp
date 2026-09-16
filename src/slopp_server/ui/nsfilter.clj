(ns slopp-server.ui.nsfilter
  "Platform-neutral (.cljc) predicate for the store-browser namespace filter:
  does a namespace row match the current search box text? Pure string logic,
  verified by the JVM oracle (`slopp-server.ui.nsfilter-test`) AND compiled to JS for
  the browser. That pairing is the whole point of the namespace: the filter a
  user actually types into runs in JS, where slopp has no oracle, so the logic
  is kept somewhere the oracle CAN reach and the browser merely calls it."
  (:require [clojure.string :as str]))

(defn matches?
  "True when `needle` (the search box text) is a case-insensitive substring of
  `text` (a namespace name). A blank needle matches everything — nothing typed
  shows every row. The needle is trimmed first. Pure string logic, so it runs
  identically on the JVM (where this test suite verifies it) and in the browser
  (where the browser calls the compiled form)."
  [needle text]
  (let [needle (str/trim (str needle))]
    (or (str/blank? needle)
        (str/includes? (str/lower-case (str text))
                       (str/lower-case needle)))))

(defn common-root
  "The longest dotted prefix shared by every name in `names`, or nil.

  The nav repeated `slopp-server.ui.` on all fourteen rows, which is fourteen copies
  of the one fact a reader already knows and the widest thing in a narrow
  pane. Printed ONCE as a category heading it costs one line and every row
  gets shorter.

  Three answers are deliberately nil, because a category has to be worth
  having:

  - nothing shared (`foo.a`, `bar.b`) — there is no root to name.
  - one name — a heading over a list of one is a heading over nothing, and it
    would strip the only row down to a fragment.
  - a name that IS the prefix (`demo` alongside `demo.a`) — stripping would
    leave that row with an empty label, so the prefix stops one segment short
    and here that leaves nothing.

  Segments, never characters: `demo` is not a root of `demonstrate.x`. A
  character prefix would silently mangle a store whose modules merely start
  with the same letters, which is the kind of wrong that looks fine until
  someone reads it."
  [names]
  (let [segs (mapv #(str/split (str %) #"\.") names)]
    (when (< 1 (count segs))
      (let [shortest (reduce min (map count segs))
            shared   (count (take-while (fn [i] (apply = (map #(nth % i) segs)))
                                        (range (dec shortest))))]
        (when (pos? shared)
          (str/join "." (take shared (first segs))))))))

(defn without-root
  "`name` with `root` and its dot removed, or `name` unchanged.

  Total on purpose: a nil `root` (no category was worth hoisting) and a name
  that does not live under it both come back whole. That is what lets the nav
  call this on every row without first asking whether [[common-root]] found
  anything — the branch exists once, here, instead of at every use.

  The dot is part of the match, so `demo` does not strip `demonstrate.x`."
  [root name]
  (let [s (str name)
        p (str root ".")]
    (if (and root (str/starts-with? s p))
      (subs s (count p))
      s)))
