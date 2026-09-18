(ns slopp-server.ui.basepath
  "Taking a query string off a path. One function, [[split-query]], is what is
  left of a namespace that once added and removed the prefix a reverse proxy
  served this app under: the daemon serves the pages itself now, and
  `slopp.webapp/strip-base` and `prefix-links` do that arithmetic, so
  `prefixed`, `strip` and `normalize` had test callers only and went.

  `:cljc` because both ends need the same rule for where a path ends and its
  query begins — the browser router and the JVM tests — and a second
  implementation of a rule like this is how the two quietly disagree."
  (:require [clojure.string :as str] [slopp.lang :as lang]))

(defn split-query
  "`path` as `[path params]` — the query string taken off and parsed into a
  map of keyword to decoded text, `{}` when there is none.

  The client router parses PATHS, and a query string is not one: before this,
  `/store/ns/demo.core?x=1` parsed as a namespace literally NAMED
  `demo.core?x=1`. Nothing had ever sent a query string, which is why it
  survived — search is the first screen whose subject is TEXT rather than a
  name, and text belongs in a query string rather than in a path segment
  where a `/` in the query would silently become another route.

  Here rather than in the router (`slopp.webapp/match-route`) because this namespace owns
  url arithmetic, and a router growing its own opinion about urls is the
  second implementation this file exists to prevent.

  Keys are keywords because they name PARAMETERS, which is what the rest of
  the app calls the same thing. A parameter written with no `=` is the empty
  string rather than nil, so nothing downstream has to tell absent from blank
  in two different ways."
  [path]
  (let [p (str path)
        i (str/index-of p "?")]
    (if (nil? i)
      [p {}]
      [(subs p 0 i)
       (into {} (for [pair (str/split (subs p (inc i)) #"&")
                      :when (seq pair)
                      :let [j (str/index-of pair "=")]]
                  [(keyword (lang/decode-component (if j (subs pair 0 j) pair)))
                   (lang/decode-component (if j (subs pair (inc j)) ""))]))])))
