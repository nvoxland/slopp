(ns slopp.http.routes-test
  "Deriving a route TABLE from var metadata — `:http/method`, `:http/path` and
  their neighbours — which is how a slopp app declares its surface without a
  routing DSL. The declaration and the thing declared are one var, so there is
  no table to drift.

  The SPA fallback is here too, and it is the case that needs saying out loud:
  serving deep links under a declared prefix must not swallow a genuine 404.
  That is the same behavioural change `http-client-routes-consequences` states at the
  done point — the rule tells the author once, and this holds the code to it."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.http.routes :as routes] [slopp.http.router :as router] [slopp.webapp :as webapp] [slopp.lang :as lang]))

(defn ^{:http/method :get :http/path "/t/users/:id" :http/auth :public
        :http/reads {:user [:user/by-id [:path-params :id]]}}
  t-get
  "Test endpoint."
  [req]
  {:status 200 :body (:http/reads req)})

(defn ^{:http/method :post :http/path "/t/users" :http/auth :authenticated
        :http/effects [:user/insert]
        :rest/request [:map [:name :string]]
        :rest/response [:map [:id :int]]}
  t-post
  "Test endpoint."
  [req]
  {:status 201 :http/effects [[:user/insert (:body req)]]})

(defn ^{:http/effect :user/insert} t-insert!
  "Test performer."
  [ctx row]
  (swap! (:db ctx) conj row))

(defn ^{:http/read :user/by-id} t-by-id
  "Test read performer."
  [_ctx id]
  {:user/id id})

(defn ^{:http/method :get :http/path "/t/page" :http/auth :public}
  t-html
  "Test endpoint that serves a document and publishes no typed contract."
  [_req]
  {:status 200 :http/raw true :body "<h1>hi</h1>"})

(defn ^{:unused-ok "the negative control for route discovery — it exists to be PASSED OVER by the scan, so having no caller is the property under test"} plain "Not an endpoint." [x] x)

(deftest routes-derive-from-var-metadata
  (let [rows (routes/from-namespaces ['slopp.http.routes-test])]
    (testing "endpoint vars become rows; unmarked vars don't"
      ;; hand-kept, which is the point: derived from the same metadata the rows
      ;; come from, it would compare a derivation to itself. `/api/fixture-orders`
      ;; is the `:rest/path` fixture — its presence here IS the assertion that
      ;; the runtime source sees both markers
      (is (= 4 (count rows)))
      (is (= #{"/t/users/:id" "/t/users" "/t/page" "/api/fixture-orders"}
             (set (map :path rows)))))

    (testing "and each row says which KIND of route it is"
      (let [kind (into {} (map (juxt :path :kind)) rows)]
        (is (= :content (kind "/t/page")))
        (is (= :rest (kind "/api/fixture-orders")))))
    (testing "the row carries the contract and the CALLABLE var"
      (let [row (first (filter #(= "/t/users/:id" (:path %)) rows))]
        (is (= :get (:method row)))
        (is (= :public (:auth row)))
        (is (= {:user [:user/by-id [:path-params :id]]} (:http/reads row)))
        (is (var? (:handler row)))
        (is (= 200 (:status ((:handler row) {:http/reads :probe}))))))
    (testing "performers index by kind, var-callable"
      (let [effects (routes/performers-from-namespaces ['slopp.http.routes-test] :http/effect)
            reads   (routes/performers-from-namespaces ['slopp.http.routes-test] :http/read)]
        (is (var? (get effects :user/insert)))
        (is (= {:user/id "7"} ((get reads :user/by-id) {} "7")))))))

(deftest a-SHELL-serves-deep-links-without-swallowing-404s
  ;; A client-routed app owns paths the server has no route for: /store/ns/foo
  ;; is real to the browser and meaningless to the router, so a refresh 404s.
  ;; The fix is not a catch-all — a catch-all at the root serves the app
  ;; document for EVERY unmatched path, and an app that can never 404 has no
  ;; way to tell a typo from a page.
  ;;
  ;; **It is DECLARED, and now it is declared ONCE, by the shell itself.** The
  ;; document says `:http/path "/store/**"` and `:webapp/shell true`; the
  ;; wildcard IS the fallback. That replaced a `:webapp/client-routes` prefix
  ;; list which generated one catch-all row per prefix — rows nobody wrote,
  ;; which every document publisher then had to filter back out, and which
  ;; a reader could not tell from a route an author typed.
  (let [rows [{:handler :app :method :get :path "/store/**" :auth :public
               :webapp/shell true}
              {:handler :ns-page :method :get :path "/store/ns/:ns" :auth :public}]]

    (testing "a real route still wins — the fallback never steals it"
      ;; positional precedence, not a rule of its own: a static segment beats
      ;; a wildcard at the same depth whatever order the rows arrive in
      (is (= :ns-page (:handler (router/match rows :get "/store/ns/demo.core")))))

    (testing "a deep client route the server has no row for gets the document"
      (is (= :app (:handler (router/match rows :get "/store/form/f123/detail")))))

    (testing "the shell's own ROOT is covered, because ** matches zero segments"
      ;; this used to need a SECOND explicit route per section: the generated
      ;; catch-all needed a segment below the prefix, so /store 404'd while
      ;; /store/form/9 answered — and the url an author was most likely to
      ;; share was the one that broke
      (is (= :app (:handler (router/match rows :get "/store")))))

    (testing "and a path outside the shell still 404s"
      ;; THE assertion that matters. A fallback that swallows this is worse
      ;; than no fallback: the app loses its only way to say \"no such thing\".
      (is (nil? (router/match rows :get "/nonsense")))
      (is (nil? (router/match rows :get "/api/typo"))))))

(deftest a-row-carries-the-CONTRACT-its-endpoint-declared
  ;; The row is what the dispatcher holds at request time, and until now it
  ;; carried the route, the policy and the effect vocabulary but NOT the
  ;; contract — so a dispatcher could not have honoured :rest/request even if it
  ;; had tried. That absence is why the wire crossing has been unchecked since
  ;; the day it was declared.
  ;;
  ;; Carried on the row rather than re-read from var metadata per request, for
  ;; the same reason everything else here is derived once: a second reader of
  ;; the same metadata is free to disagree with the first.
  (let [rows (routes/from-namespaces ['slopp.http.routes-test])
        by   (into {} (map (juxt :path identity)) rows)]
    (testing "a typed endpoint's schemas reach the row"
      (is (= [:map [:name :string]] (:rest/request (by "/t/users"))))
      (is (= [:map [:id :int]] (:rest/response (by "/t/users")))))

    (testing "an endpoint that declares neither carries neither"
      ;; ABSENCE has to stay absence. nil is what "declared no contract" means
      ;; to the boundary, and it is the state an HTML page is now allowed to be
      ;; in — serving a document is http's business, and typing a JSON contract
      ;; is rest's.
      (is (nil? (:rest/request (by "/t/page"))) (pr-str (by "/t/page")))
      (is (nil? (:rest/response (by "/t/page")))))))

(deftest the-CLIENT-and-SERVER-matchers-agree-about-the-pattern-grammar
  ;; `slopp.webapp/match-route` and `router/match` are two implementations of one
  ;; pattern grammar, and they are two rather than one for a reason that is not
  ;; laziness: `web.router` ships in the `http` family and `match-route` in
  ;; `webapp`. A store may vendor either without the other, so a require across
  ;; them is a load failure in whichever store has only one half.
  ;;
  ;; What must not drift is the GRAMMAR. An app declares one table of patterns
  ;; and both sides read it — the client to route a click, the server to decide
  ;; which paths its document answers for. The day one side learns a pattern form
  ;; the other does not, a deep link routes in the browser and 404s on refresh,
  ;; or the reverse; and neither is discoverable by reading either file.
  ;;
  ;; Same shape as `web.client-test/requester-contract`: one suite, two
  ;; implementations, and the suite is the only thing that makes the pair a pair.
  ;;
  ;; It lives HERE, on the server side, because `router/match` is exported only
  ;; within `slopp.rules.*` — running the comparison from inside `slopp.http`
  ;; needs no widening of that, and the module edge it does need is declared
  ;; test-only so production code under `slopp.http` still may not cross.
  (let [;; every form of the grammar, so a side that learns one the other has
        ;; not is caught here rather than by a deep link that routes in the
        ;; browser and 404s on refresh. The two WILDCARDS are the new members,
        ;; and they are the ones whose precedence differs from a capture's.
        patterns ["/" "/things" "/things/:id" "/things/:id/edit"
                  "/a/b/c" "/files/**" "/files/one/*" "/:only" "/deep/**"]
        paths    ["/" "/things" "/things/" "/things/42" "/things/42/edit"
                  "/a/b/c" "/files" "/files/x" "/files/a/b/c.txt"
                  "/files/one/x" "/files/one/x/y" "/deep" "/deep/a/b" "/solo"
                  "/nope/deeper" "/things/42/nonsense" ""]
        ;; the pattern is its own target, so a disagreement names itself
        rows     (mapv (fn [p] {:method :get :path p :handler p}) patterns)
        table    (mapv (fn [p] [p p]) patterns)]

    (testing "the detector bites: both DO answer, and differently per path"
      ;; without this the agreement below is satisfied by two matchers that
      ;; return nil for everything — the vacuous-green shape
      (is (< 5 (count (distinct (keep #(:path (router/match rows :get %)) paths))))
          "the server matcher resolved fewer than six distinct patterns")
      (is (< 5 (count (distinct (keep #(:screen (webapp/match-route table %)) paths))))
          "the client matcher resolved fewer than six distinct patterns"))

    (testing "every path routes to the same PATTERN on both sides"
      (doseq [path paths]
        (let [server (:path (router/match rows :get path))
              client (:screen (webapp/match-route table path))]
          (is (= server client)
              (str "client and server disagree about " (pr-str path)
                   " — server: " (pr-str server) ", client: " (pr-str client))))))

    (testing "and the capture NAMES and values agree too"
      ;; matching the same pattern while binding different names is a
      ;; disagreement a pattern-only comparison cannot see
      (doseq [path paths]
        (let [server (:path-params (router/match rows :get path))
              client (:params (webapp/match-route table path))]
          (when (seq server)
            (is (= server (select-keys client (keys server)))
                (str "captures differ for " (pr-str path)
                     " — server: " (pr-str server)
                     ", client: " (pr-str client)))))))))

(deftest an-ENCODED-segment-decodes-on-BOTH-sides-of-the-round-trip
  ;; slopp-ui, 2026-08-23, from a human clicking `read its source`:
  ;;
  ;;   GET /api/source/slopp-ui.hub/register!    → 200
  ;;   GET /api/source/slopp-ui.hub/register%21  → 404
  ;;   GET /api/source/slopp-ui.hub/projects     → 200   (control)
  ;;
  ;; `slopp.webapp` builds links with `lang/encode-component`, segment-wise and
  ;; fully encoded; both matchers then captured the segment VERBATIM. So the
  ;; handler looked up a form literally named "register%21".
  ;;
  ;; `encode-component` and `decode-component` are neighbours in `slopp.lang`,
  ;; written as a pair — the encoder's own docstring says the pair is the point.
  ;; The pair was split across the round trip with only one end wired.
  ;;
  ;; **What it costs is not one form.** `encode-component` escapes `!`, `?` and
  ;; `*`, which in Clojure marks every effectful var and every predicate. So the
  ;; failure set was exactly the interesting names: `projects` worked and
  ;; `register!` did not.
  ;;
  ;; Third instance in this project of a remembered alphabet biased against
  ;; precisely the marked members — shell globs eating `authorized?` and
  ;; `open!`, a regex mangling `!` in a var sweep, and now URL encoding. The
  ;; characters a language puts on its most interesting vars are the characters
  ;; other layers reserve.
  (doseq [nm ["register!" "authorized?" "*warn-on-reflection*" "a+b" "plain"]]
    (let [enc  (lang/encode-component nm)
          uri  (str "/api/source/demo.ns/" enc)
          rows [{:method :get :path "/api/source/:ns/:name" :handler :h}]
          srv  (router/match rows :get uri)
          cli  (webapp/match-route [["/api/source/:ns/:name" :screen]] uri)]
      (testing (str "the SERVER hands the handler the name that was linked: " nm)
        (is (= nm (get-in srv [:path-params :name]))
            (str "encoded as " enc " — a handler looking that up finds nothing,"
                 " and the 404 reads as a missing form")))
      (testing (str "and the CLIENT matcher agrees, as the grammar test requires: " nm)
        (is (= nm (get-in cli [:params :name]))))))

  (testing "a trailing catch-all decodes every segment it swallowed — BOTH sides"
    ;; a static mount serves a TREE, so the remainder is joined from segments
    ;; that were each encoded on their own. Asserted on both matchers for the
    ;; reason the whole finding is about: fixing one end of a pair and not the
    ;; other is how this arrived
    (let [uri (str "/assets/" (lang/encode-component "a b") "/" (lang/encode-component "x!.js"))
          srv (router/match [{:method :get :path "/assets/**" :handler :h}] :get uri)
          cli (webapp/match-route [["/assets/**" :screen]] uri)]
      (is (= "a b/x!.js" (get-in srv [:path-params :*])) (pr-str srv))
      (is (= "a b/x!.js" (get-in cli [:params :*])) (pr-str cli))))

  (testing "an encoded SLASH stays inside its segment"
    ;; the reason decoding happens AFTER the split and never before: %2F is a
    ;; slash in the VALUE, not a segment boundary, and decoding the whole uri
    ;; first would silently re-segment the address
    (let [uri (str "/api/source/demo.ns/" (lang/encode-component "a/b"))
          srv (router/match [{:method :get :path "/api/source/:ns/:name" :handler :h}] :get uri)]
      (is (= "a/b" (get-in srv [:path-params :name])) (pr-str srv)))))

(defn ^{:rest/path "/api/fixture-orders" :http/method :get :http/auth :public
        :rest/response [:map]
        :unused-ok "a route-discovery fixture — it exists to be FOUND by the scan, so having no caller is the property under test"}
  fixture-orders
  "A REST endpoint fixture."
  [_req]
  {:status 200 :body {}})

(deftest the-RUNTIME-route-source-finds-BOTH-markers
  ;; `from-namespaces` reads VAR metadata and ships in the slim jar — it is
  ;; what the router actually serves from. The store-side traversal is a
  ;; different walk over a different input, so a marker landing in one and not
  ;; the other is a route that passes every write gate and then does not exist
  ;; at runtime. Nothing would say so: the write is green, `query_surface`
  ;; lists it, and requests 404.
  ;;
  ;; That is why this is asserted here as well as at the traversal, rather than
  ;; trusted to follow from it.
  (let [rows (routes/from-namespaces ['slopp.http.routes-test])
        by-path (into {} (map (juxt :path identity)) rows)]
    (testing "a :rest/path var is discovered as a route"
      (is (contains? by-path "/api/fixture-orders")
          (str "declared, gated, listed — and unroutable: "
               (pr-str (sort (keys by-path))))))

    (testing "and it carries its kind, like the store-side row"
      (is (= :rest (:kind (by-path "/api/fixture-orders")))))

    (testing "a var carrying NEITHER marker is still passed over"
      ;; the negative control that already lived here — route discovery must
      ;; keep ignoring an ordinary public fn
      (is (not-any? #(= 'plain (:name %)) rows) (pr-str rows)))))
