(ns slopp.http.router-test
  "`slopp.http.router` matching a request to a declared route: method, path
  segments, params, and the catch-all that takes a nested remainder.

  Layer 0 and store-blind, which is the constraint worth stating — this is the
  framework a USER's app runs on, and it knows nothing about stores. So the
  fixtures are plain route data rather than anything derived from a store, and
  they have to be: `slopp.modules-test/no-shipped-framework-family-reaches-back-into-slopp`
  holds the whole `slopp.http.*` subtree at layer 0, and a test reaching for a
  store here would be the first crack in the property that lets this ship as a
  slim jar."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.http.router :as router]))

(deftest routes-match-methods-paths-and-params
  (let [routes [{:method :get :path "/api/users/:id" :handler 'a/get-user}
                {:method :post :path "/api/users" :handler 'a/create-user}
                {:method :get :path "/api/users/me" :handler 'a/me}
                {:method :get :path "/health" :handler 'a/health}]]
    (testing "params capture into :path-params"
      (let [m (router/match routes :get "/api/users/42")]
        (is (= 'a/get-user (:handler m)))
        (is (= {:id "42"} (:path-params m)))))
    (testing "a static segment beats a param capture"
      (is (= 'a/me (:handler (router/match routes :get "/api/users/me")))))
    (testing "the method discriminates"
      (is (= 'a/create-user (:handler (router/match routes :post "/api/users"))))
      (is (nil? (router/match routes :delete "/api/users"))))
    (testing "no match is nil, never a throw"
      (is (nil? (router/match routes :get "/nope")))
      (is (nil? (router/match routes :get ""))))
    (testing "a trailing slash is tolerated"
      (is (= 'a/health (:handler (router/match routes :get "/health/")))))))

(deftest catch-all-segment-matches-a-nested-remainder
  ;; F6: a static mount must be able to serve a TREE (/assets/cljs/main.js),
  ;; which the exact-segment-count router could not express — so slopp's own
  ;; default bundle path 404'd. `**` captures the remaining segments and must
  ;; rank BELOW static and single-segment captures so existing precedence is
  ;; untouched.
  ;;
  ;; Spelled `**` and anonymous since the grammar rework; it was `*path`, and
  ;; the name was read by exactly one of the three places that carried it.
  (let [routes [{:method :get :path "/assets/**"          :handler 'a/asset}
                {:method :get :path "/assets/favicon.ico" :handler 'a/favicon}
                {:method :get :path "/assets/:one"        :handler 'a/one}]]
    (testing "a catch-all captures the whole remainder, slash-joined"
      (let [m (router/match routes :get "/assets/cljs/main.js")]
        (is (= 'a/asset (:handler m)))
        (is (= "cljs/main.js" (:* (:path-params m))))))
    (testing "a static segment still beats the catch-all"
      (is (= 'a/favicon (:handler (router/match routes :get "/assets/favicon.ico")))))
    (testing "a single-segment capture still beats the catch-all"
      (is (= 'a/one (:handler (router/match routes :get "/assets/app.css")))))
    (testing "the mount ROOT now reaches the catch-all with an empty remainder"
      ;; it used to match nothing — `*path` needed a segment. The handler is
      ;; what decides: `http.static/mount-routes` refuses an empty remainder
      ;; and answers 404, which is what a directory should say.
      (let [m (router/match [{:method :get :path "/assets/**" :handler 'a/asset}]
                            :get "/assets")]
        (is (= 'a/asset (:handler m)))
        (is (= "" (:* (:path-params m))))))))

(deftest wildcards-are-ANONYMOUS-and-END-ONLY
  ;; The grammar, stated as behaviour. Two wildcards, no names:
  ;;
  ;;   :name   captures ONE segment under that name  — an API's /form/:id
  ;;   *       matches exactly ONE segment           — anonymous
  ;;   **      matches ZERO OR MORE segments         — anonymous
  ;;
  ;; Both wildcards are end-only, and both bind `:*` because there can be at
  ;; most one. Named splats (`*path`) are GONE: a static mount wants a tree,
  ;; not a vocabulary, and the name was carried through three generators for
  ;; nothing.
  ;;
  ;; Follows Spring's PathPattern, which restricts `**` to the end for the
  ;; same reason slopp already refused a mid-pattern `*`: matching it needs
  ;; backtracking, and the pattern that needs it is nearly always a mistake.
  (let [r  (fn [p] {:method :get :path p :handler p})
        at (fn [routes uri] (let [m (router/match routes :get uri)]
                              [(:handler m) (:path-params m)]))]

    (testing "* is exactly one segment — not zero, not two"
      (let [rs [(r "/my/*")]]
        (is (= ["/my/*" {:* "abc"}] (at rs "/my/abc")))
        (is (= [nil nil] (at rs "/my")))
        (is (= [nil nil] (at rs "/my/abc/xyz")))))

    (testing "** is ZERO or more, which is what covers a prefix ROOT"
      ;; the hole that forced a third declaration on every client-routed
      ;; section: the old catch-all needed a segment below the prefix, so
      ;; `/store` itself 404'd while `/store/form/9` answered
      (let [rs [(r "/my/**")]]
        (is (= ["/my/**" {:* ""}]            (at rs "/my")))
        (is (= ["/my/**" {:* "abc"}]         (at rs "/my/abc")))
        (is (= ["/my/**" {:* "abc/xyz"}]     (at rs "/my/abc/xyz")))))

    (testing "/** answers the root itself"
      (is (= ["/**" {:* ""}] (at [(r "/**")] "/"))))

    (testing "a named splat is no longer a wildcard, and does not match as a LITERAL either"
      ;; the dangerous reading. `*path` as a literal segment would 200 on a
      ;; url whose text really is `/assets/*path` and 404 on every real asset
      ;; — a retired spelling that keeps answering, on the wrong requests
      (let [rs [(r "/assets/*path")]]
        (is (= [nil nil] (at rs "/assets/cljs/main.js")))
        (is (= [nil nil] (at rs "/assets/*path")))))

    (testing "a wildcard anywhere but last never matches"
      (is (= [nil nil] (at [(r "/x/*/y")] "/x/a/y")))
      (is (= [nil nil] (at [(r "/x/**/y")] "/x/a/y"))))

    (testing "a * fused to a literal is not a pattern this grammar has"
      ;; no intra-segment globbing: `*.css` and `pre*` are a separate feature
      ;; with their own precedence question, and nothing declares one
      (is (= [nil nil] (at [(r "/files/*.css")] "/files/a.css")))
      (is (= [nil nil] (at [(r "/files/pre*")] "/files/prefix"))))))

(deftest precedence-is-POSITIONAL-and-never-depends-on-route-ORDER
  ;; The bug this replaces, measured before it was written: precedence was a
  ;; global score — captures + 100×splats — so two patterns with the same
  ;; counts tied, and the tie was broken by position in the route vector.
  ;; That vector comes from `(vals (ns-publics …))`, which is hash order, so
  ;; the winner was not merely order-dependent but unreproducible.
  ;;
  ;; Replaced with the rule reitit and Spring's PathPattern both use: rank
  ;; each segment (literal 0 < :name 1 < * 2 < ** 3) and compare the vectors
  ;; LEFT TO RIGHT. The longest static prefix wins as a consequence rather
  ;; than as a second rule, which is also the servlet spec's ordering.
  (let [r  (fn [p] {:method :get :path p :handler p})
        hit (fn [routes uri] (:handler (router/match routes :get uri)))
        ;; the whole point: BOTH orders must answer the same
        both (fn [a b uri] [(hit [a b] uri) (hit [b a] uri)])]

    (testing "the more specific wildcard wins, whichever order it arrives in"
      (is (= ["/my/**" "/my/**"] (both (r "/**") (r "/my/**") "/my/abc/xyz")))
      (is (= ["/a/b/**" "/a/b/**"] (both (r "/a/**") (r "/a/b/**") "/a/b/c"))))

    (testing "* beats ** — the narrower wildcard is the more specific one"
      (is (= ["/a/*" "/a/*"] (both (r "/a/*") (r "/a/**") "/a/b"))))

    (testing "a named capture beats an anonymous wildcard"
      ;; identical matching power, so this is a decision rather than a
      ;; discovery: `:id` is an author naming an address, `*` is a catch
      (is (= ["/a/:id" "/a/:id"] (both (r "/a/:id") (r "/a/*") "/a/7"))))

    (testing "an EXACT route beats a ** that also covers it"
      (is (= ["/a" "/a"] (both (r "/a") (r "/a/**") "/a")))
      (is (= ["/" "/"] (both (r "/") (r "/**") "/"))))

    (testing "specificity is decided at the FIRST differing segment, not by a total"
      ;; /a/:x/c and /a/b/:y both hold one capture, so the old score tied them
      ;; and declaration order picked. Static earlier is more specific.
      (is (= ["/a/b/:y" "/a/b/:y"] (both (r "/a/:x/c") (r "/a/b/:y") "/a/b/c"))))

    (testing "the collision this was found by: a static tree inside a client-routed prefix"
      ;; both rows are framework-GENERATED — http.static/mount-routes and
      ;; routes/client-route-rows — so an app could not have avoided it by
      ;; writing its routes differently
      (let [shell  (r "/p/:slug/**")
            assets (r "/p/:slug/assets/**")]
        (is (= ["/p/:slug/assets/**" "/p/:slug/assets/**"]
               (both shell assets "/p/x/assets/cljs/main.js")))))))
