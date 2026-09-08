(ns slopp.rules.http-test
  "Cover for the web surface's DERIVATIONS — what slopp reads off endpoint
  metadata, as opposed to what happens when a request arrives.

  The runtime is `slopp.http`'s business and is tested portlessly there. Here
  the subject is everything derived BEFORE that: which routes a store
  declares, which URL attributes count as route references, what a contract
  declaration obliges, and what a declaration's consequences are worth saying
  out loud.

  That last one is a genre of its own and worth naming: `:webapp/client-routes` changes
  every status code under a prefix from 404 to 200, so the test asserts that
  slopp SAYS so once, and stops. A consequence nobody states is one somebody
  discovers."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.rules.http :as rules.http] [slopp.ops :as ops] [slopp.ops.external :as external] [slopp.http-test :as slopp.http-test] [clojure.string :as str] [slopp.rules.rest :as rules.rest] [slopp.edit.http :as edit.http]))

(deftest routes-derive-from-stored-nodes
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:http/method :get :http/path \"/api/users/:id\"\n"
                 "        :http/auth [:group \"admin\"]\n"
                 "        :http/reads {:user [:user/by-id [:path-params :id]]}\n"
                 "        :malli/schema [:=> [:cat :map] :map]\n"
                 "        :rest/response :map} get-user \"U.\" [req] req)\n\n"
                 "(defn ^{:http/method :post :http/path \"/api/users\"\n"
                 "        :http/auth :authenticated\n"
                 "        :http/effects [:user/insert]} create-user \"C.\" [req] req)\n\n"
                 "(defn ^{:http/effect :user/insert} insert-user! \"I.\" [ctx row] row)\n\n"
                 "(defn ^{:http/read :user/by-id} user-by-id \"R.\" [ctx id] id)\n\n"
                 "(defn plain \"P.\" [x] x)\n")
        s0  (store/ingest (store/empty-store) 'shop.api src)
        on  (first (store/record-config-put s0 "capabilities" :manifest "http.enabled" "true"))]
    (testing "endpoints: every :http/path form, read off the stored node"
      (let [eps (rules.http/endpoints s0)
            by-path (fn [p] (some #(when (= p (:path %)) %) eps))]
        (is (= 2 (count eps)))
        (let [e (by-path "/api/users/:id")]
          (is (= :get (:method e)))
          (is (= 'shop.api/get-user (:handler e)))
          (is (= [:group "admin"] (:auth e)))
          (is (= {:user [:user/by-id [:path-params :id]]} (:http/reads e)))
          (is (true? (:schema? e))))
        (let [e (by-path "/api/users")]
          (is (= :post (:method e)))
          (is (= [:user/insert] (:http/effects e)))
          (is (not (:schema? e))))))
    (testing "performers: the app-defined effect/read vocabulary"
      (is (= {:user/insert 'shop.api/insert-user!} (rules.http/performers s0 :http/effect)))
      (is (= {:user/by-id 'shop.api/user-by-id} (rules.http/performers s0 :http/read))))
    (testing "routes-report is empty-and-says-why until http.enabled"
      (is (false? (:enabled (rules.http/routes-report s0))))
      (is (empty? (:routes (rules.http/routes-report s0)))))
    (testing "routes-report with the capability on"
      (let [rep (rules.http/routes-report on)]
        (is (true? (:enabled rep)))
        (is (= 2 (count (:routes rep))))
        (is (= #{:user/insert} (:effect-kinds rep)))
        (is (= #{:user/by-id} (:read-kinds rep)))))
    (testing "a test namespace's endpoint-shaped form is a fixture, not surface"
      (let [s2 (store/ingest on 'shop.api-test
                             (str "(ns shop.api-test)\n\n"
                                  "(defn ^{:http/method :get :http/path \"/fixture\"} fx \"F.\" [req] req)\n"))]
        (is (= 2 (count (:routes (rules.http/routes-report s2)))))))))

(deftest ^:external web-gates-ride-the-write-path
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'shop.api "(ns shop.api)\n\n(defn seed \"S.\" [x] x)\n")
      (testing "before opting in, an endpoint-shaped form lands ungated (the adoption story)"
        (let [r (ops/add-form! sess 'shop.api
                               "(defn ^{:http/method :get :http/path \"/pre\"} pre \"P.\" [req] req)"
                               :prompt "pre-optin endpoint")]
          (is (nil? (:error r)) (pr-str r))))
      (ops/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "an endpoint with no :http/auth is refused with teaching, and never lands"
        (let [r (ops/add-form! sess 'shop.api
                               "(def ^{:http/method :get :http/path \"/naked\"} naked \"N.\" [:p \"x\"])"
                               :prompt "endpoint without auth")]
          (is (re-find #":http/auth" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'shop.api 'naked)))))
      (testing "with a declared policy and response contract it lands, and the route reports"
        (let [r (ops/add-form! sess 'shop.api
                               (str "(defn ^{:http/method :get :rest/path \"/api/ping\""
                                    " :http/auth :public :rest/response :map} ping \"P.\" [req] req)")
                               :prompt "a public endpoint")]
          (is (nil? (:error r)) (pr-str r))
          (let [rep (rules.http/routes-report (:store @sess))]
            (is (true? (:enabled rep)))
            (is (some #(= "/api/ping" (:path %)) (:routes rep))))))
      (finally (ops/close! sess)))))

(deftest ui-route-refs-classify-link-targets
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn nav \"N.\" []\n"
                 "  [:nav [:a {:href \"/store\"} \"s\"]\n"
                 "        [:a {:href \"https://x.example/a\"} \"ext\"]\n"
                 "        [:a {:href \"#top\"} \"anchor\"]])\n\n"
                 "(defn source-link \"S.\" [nsx]\n"
                 "  [:a {:href (str \"/store/source/\" nsx)} \"src\"])\n\n"
                 "(defn todo-form \"F.\" []\n"
                 "  [:form {:action \"/todos\" :method \"post\"} [:button \"go\"]])\n\n"
                 "(defn dyn \"D.\" [req] [:a {:href (:uri req)} \"d\"])\n\n"
                 "(defn ^{:http/external-path \"nginx serves it\"} ext-link \"E.\" []\n"
                 "  [:a {:href \"/behind-nginx\"} \"x\"])\n")
        s    (store/ingest (store/empty-store) 'shop.ui src)
        s    (store/ingest s 'shop.ui-test
                           "(ns shop.ui-test)\n\n(defn fx \"X.\" [] [:a {:href \"/fixture-only\"} \"f\"])\n")
        refs (rules.http/ui-route-refs s)
        of   (fn [kind] (set (map #(select-keys % [:form :attr :method :path])
                                  (filter #(= kind (:kind %)) refs))))]
    (testing "root-relative literals are exact refs; absolute URLs and anchors are skipped"
      (is (= #{{:form 'shop.ui/nav :attr :href :method :get :path "/store"}
               {:form 'shop.ui/todo-form :attr :action :method :post :path "/todos"}}
             (of :exact))))
    (testing "(str \"/literal/\" …) is a prefix ref"
      (is (= #{{:form 'shop.ui/source-link :attr :href :method :get :path "/store/source/"}}
             (of :prefix))))
    (testing "a dynamic value is NAMED, never counted clean"
      (is (= '[shop.ui/dyn] (mapv :form (filter #(= :unresolved (:kind %)) refs)))))
    (testing "^{:http/external-path} discharges the form's refs; test namespaces are fixtures"
      (is (not-any? #(#{'shop.ui/ext-link 'shop.ui-test/fx} (:form %)) refs)))))

(deftest dangling-route-refs-join-declared-routes-and-static-mounts
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:http/method :get :http/path \"/todos\" :http/auth :public} todos-page \"T.\" [req]\n"
                 "  [:div [:a {:href \"/todos\"} \"self\"]\n"
                 "        [:a {:href \"/nowhere\"} \"bad\"]\n"
                 "        [:a {:href \"/assets/app.css\"} \"css\"]\n"
                 "        [:a {:href \"/assets/missing.css\"} \"gone-file\"]\n"
                 "        [:a {:href (str \"/todo/\" 7)} \"one\"]\n"
                 "        [:a {:href (str \"/gone/\" 7)} \"prefix-bad\"]\n"
                 "        [:a {:href (:uri req)} \"dyn\"]])\n\n"
                 "(defn ^{:http/method :get :http/path \"/todo/:id\" :http/auth :public} todo-page \"O.\" [req] req)\n")
        s (store/ingest (store/empty-store) 'shop.ui src)
        s (first (store/record-config-put s "capabilities" :manifest "http.enabled" "true"))
        s (first (store/record-config-put s "capabilities" :manifest "http.static./assets" "public"))
        s (first (store/record-file-put s "public/app.css" "body{}"))
        {:keys [dangling unresolved]} (rules.http/dangling-route-refs s)]
    (testing "unserved refs: no route, mount without the file, prefix into nothing"
      (is (= #{["/nowhere" :exact] ["/assets/missing.css" :exact] ["/gone/" :prefix]}
             (set (map (juxt :path :kind) dangling)))))
    (testing "dynamic refs are named, not counted clean"
      (is (= '[shop.ui/todos-page] (mapv :form unresolved))))
    (testing "a mount whose KEY carries a trailing slash resolves the same way too.
              The registry doc promises a trailing slash on EITHER side is
              trimmed, and `static-mounts` — what serves — keeps that promise;
              this check parsed the family with a regex of its own that
              trimmed only the value, so `http.static./assets/` served every
              asset and reported every asset link as dangling."
      (let [s3 (-> (store/ingest (store/empty-store) 'shop.ui src)
                   (store/record-config-put "capabilities" :manifest "http.enabled" "true") first
                   (store/record-config-put "capabilities" :manifest "http.static./assets/" "public") first
                   (store/record-file-put "public/app.css" "body{}") first)]
        (is (not-any? #(= "/assets/app.css" (:path %))
                      (:dangling (rules.http/dangling-route-refs s3)))
            (pr-str (:dangling (rules.http/dangling-route-refs s3))))))
    (testing "a mount written with a TRAILING SLASH resolves the same way.
              The capability's own doc line showed `http.static./assets =
              public/`, and that form built `public//app.css`, which no
              manifest holds — so following the documentation made every
              asset link in the app read as dangling."
      (let [s2 (first (store/record-config-put s "capabilities" :manifest
                                               "http.static./assets" "public/"))]
        (is (= #{["/nowhere" :exact] ["/assets/missing.css" :exact] ["/gone/" :prefix]}
               (set (map (juxt :path :kind)
                         (:dangling (rules.http/dangling-route-refs s2))))))))
    (testing "an ARTIFACT under a mount is served too. compile_client writes the
              bundle as an artifact — bytes to the content-addressed cache,
              sha to the journal, because inlining it cost 30MB of delta log —
              and then tells you to add an http.static mount. A mount that
              could not see it made that advice impossible to follow: the
              bundle every page loads read as a dangling link."
      (let [src2 (str "(ns shop.doc)\n\n"
                      "(defn ^{:http/method :get :http/path \"/\" :http/auth :public} page \"P.\" [req]\n"
                      "  [:html [:script {:src \"/assets/cljs/main.js\"}]])\n")
            s2 (store/ingest s 'shop.doc src2)
            s2 (first (store/record-artifact
                       s2 "public/cljs/main.js"
                       {:sha "abc123" :bytes 10 :content-type "application/javascript"
                        :recipe {:kind :build :tool "compile_client"}}))]
        (is (not-any? #(= "/assets/cljs/main.js" (:path %))
                      (:dangling (rules.http/dangling-route-refs s2))))))))

(deftest query-routes-carries-rendered-by
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:http/method :get :http/path \"/todos\" :http/auth :public} todos-page \"T.\" [req]\n"
                 "  [:div [:a {:href \"/todos\"} \"self\"] [:a {:href (str \"/todo/\" 7)} \"one\"]])\n\n"
                 "(defn ^{:http/method :get :http/path \"/todo/:id\" :http/auth :public} todo-page \"O.\" [req]\n"
                 "  [:a {:href \"/todos\"} \"back\"])\n")
        s (store/ingest (store/empty-store) 'shop.ui src)
        s (first (store/record-config-put s "capabilities" :manifest "http.enabled" "true"))
        rows (:routes (rules.http/routes-report s))
        by-path (fn [p] (some #(when (= p (:path %)) %) rows))]
    (testing "exact refs attach through the matcher, prefix refs through the path pattern"
      (is (= '[shop.ui/todo-page shop.ui/todos-page]
             (:rendered-by (by-path "/todos"))))
      (is (= '[shop.ui/todos-page]
             (:rendered-by (by-path "/todo/:id")))))))

(deftest ^:external done-surfaces-dangling-route-refs
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ui.core
                   (str "(ns ui.core)\n\n"
                        "(def ^{:http/method :get :http/path \"/home\" :http/auth :public} home \"H.\"\n"
                        "  [:a {:href \"/nowhere\"} \"x\"])\n"))
      (testing "inert until http.enabled"
        (let [r (external/done! sess :label "pre-optin")]
          (is (empty? (get-in r [:findings :http-dangling-route-refs]))
              (pr-str (:findings r)))))
      (ops/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "a dangling href fires with the form and path"
        (let [r (external/done! sess :label "dangling")]
          (is (= [{:form 'ui.core/home :attr :href :path "/nowhere"}]
                 (mapv #(select-keys % [:form :attr :path])
                       (get-in r [:findings :http-dangling-route-refs])))
              (pr-str (:findings r)))))
      (testing "adding the route discharges"
        (ops/add-form! sess 'ui.core
                       (str "(def ^{:http/method :get :http/path \"/nowhere\""
                            " :http/auth :public} nowhere \"N.\" [:p \"here\"])")
                       :prompt "serve the missing route")
        (let [r (external/done! sess :label "served")]
          (is (empty? (get-in r [:findings :http-dangling-route-refs]))
              (pr-str (:findings r)))))
      (finally (ops/close! sess)))))

(deftest ^:external react-attr-names-refuse-at-the-write
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'ui.rx "(ns ui.rx)\n\n(defn seed \"S.\" [x] x)\n")
      (ops/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "opt into HTTP")
      (testing "a React attribute name in a literal hiccup element refuses, teaching the HTML spelling"
        (let [r (ops/add-form! sess 'ui.rx
                               "(defn card \"C.\" [] [:div {:className \"x\"} \"c\"])"
                               :prompt "a React-ism")]
          (is (re-find #":class\b" (str (:error r))) (pr-str r))
          (is (nil? (store/form-named (:store @sess) 'ui.rx 'card)))))
      (testing "the HTML spelling lands"
        (let [r (ops/add-form! sess 'ui.rx
                               "(defn card \"C.\" [] [:div {:class \"x\"} \"c\"])"
                               :prompt "correct spelling")]
          (is (nil? (:error r)) (pr-str r))))
      (finally (ops/close! sess)))))

(deftest routes-surface-the-declared-contract
  ;; D-web-contracts dogfood finding: the endpoint-schema gate makes
  ;; :rest/request / :rest/response MANDATORY, but query_surface computed :schema?
  ;; from :malli/schema — a DIFFERENT key — so every contract-carrying endpoint
  ;; reported :schema? false. What query_surface shows must be what the gate
  ;; enforces.
  (let [s   (store/ingest (store/empty-store) 'rc.api
                          (str "(ns rc.api)\n\n"
                               "(defn ^{:http/method :post :http/path \"/o\" :http/auth :public"
                               " :rest/request rc.c/new :rest/response rc.c/one}"
                               " make \"M.\" [r] r)\n\n"
                               "(defn ^{:http/method :get :http/path \"/bare\" :http/auth :public}"
                               " bare \"B.\" [r] r)\n"))
        by  (into {} (map (juxt :name identity)) (rules.http/endpoints s))]
    (testing "the declared contract rides the route row"
      (is (= 'rc.c/new (:rest/request (by 'make))))
      (is (= 'rc.c/one (:rest/response (by 'make))))
      (is (true? (:schema? (by 'make)))))
    (testing "an endpoint with no contract reads as unschema'd"
      (is (nil? (:rest/response (by 'bare))))
      (is (false? (:schema? (by 'bare)))))))

(deftest route-refs-only-read-hiccup-attribute-position
  (let [src (str "(ns plan.core)\n\n"
                 "(defn steps \"S.\" [x]\n"
                 "  [{:op :add :action :replace}\n"
                 "   {:action \"action\" :method :get}])\n\n"
                 "(defn ^{:http/method :get :http/path \"/p\" :http/auth :public} page \"P.\" [req]\n"
                 "  [:div [:a {:href \"/nowhere\"} \"bad\"]\n"
                 "        [:form {:action (:uri req) :method \"post\"} \"dyn\"]])\n")
        s (store/ingest (store/empty-store) 'plan.core src)
        refs (rules.http/ui-route-refs s)]
    (testing "a data map that happens to carry :action is NOT a route reference"
      (is (= '#{plan.core/page} (set (map :form refs)))
          (pr-str (mapv (juxt :form :attr :value) refs))))
    (testing "genuine hiccup attrs still register, dynamic ones as :unresolved"
      (is (= #{[:href :exact] [:action :unresolved]}
             (set (map (juxt :attr :kind) refs)))))
    (testing "and the method still comes from the same attr map"
      (is (= :post (:method (first (filter #(= :action (:attr %)) refs))))))))

(deftest the-tag-decides-whether-an-attr-is-a-url
  (let [src (str "(ns plan.two)\n\n"
                 "(defn plan \"P.\" [x]\n"
                 "  [:step {:action :replace :name x}])\n\n"
                 "(defn widget \"W.\" [x]\n"
                 "  [:div {:href \"/not-a-link\"} x])\n\n"
                 "(defn ^{:http/method :get :http/path \"/q\" :http/auth :public} page \"P.\" [req]\n"
                 "  [:div [:a.nav#top {:href \"/styled\"} \"sugar\"]\n"
                 "        [:link {:href \"/site.css\"}]\n"
                 "        [:form {:action (:uri req) :method \"post\"} \"dyn\"]])\n")
        s (store/ingest (store/empty-store) 'plan.two src)
        refs (rules.http/ui-route-refs s)]
    (testing ":action on a non-<form> element is data, not a target"
      (is (not (contains? (set (map :form refs)) 'plan.two/plan))
          (pr-str (mapv (juxt :form :attr :value) refs))))
    (testing ":href on a <div> is an attribute the browser ignores, not a link"
      (is (not (contains? (set (map :form refs)) 'plan.two/widget))))
    (testing "the URL-bearing elements register, #id/.class sugar and all"
      (is (= #{"/styled" "/site.css"}
             (set (keep :path refs)))))
    (testing "a <form> action that is code stays :unresolved, with its method"
      (let [dyn (first (filter #(= :unresolved (:kind %)) refs))]
        (is (= [:action :post] [(:attr dyn) (:method dyn)]))))))

(deftest tests-are-joined-to-the-endpoints-whose-paths-they-exercise
  (let [s (store/ingest (store/empty-store) 'shop.api
                        (str "(ns shop.api)\n\n"
                             "(defn ^{:http/method :get :http/path \"/todos\" :http/auth :public} todos \"T.\" [req] req)\n\n"
                             "(defn ^{:http/method :get :http/path \"/todo/:id\" :http/auth :public} one \"O.\" [req] req)\n\n"
                             "(defn ^{:http/method :post :http/path \"/todos\" :http/auth :public} add! \"A.\" [req] req)\n"))
        s (store/ingest s 'shop.api-test
                        (str "(ns shop.api-test)\n\n"
                             "(deftest listing (handle! ctx {:request-method :get :uri \"/todos\"}))\n\n"
                             "(deftest detail (handle! ctx {:request-method :get :uri \"/todo/7\"}))\n\n"
                             "(deftest elsewhere (is (= 1 1)))\n"))
        joined (rules.http/endpoint-test-refs s)]
    (testing "a literal URI in a test resolves through the ROUTER to its endpoint"
      (is (= '#{shop.api-test/listing} (get joined 'shop.api/todos))))
    (testing "a parameterized route matches the concrete path the test uses"
      (is (= '#{shop.api-test/detail} (get joined 'shop.api/one))))
    (testing "method matters — a POST endpoint is not exercised by a GET test"
      (is (nil? (get joined 'shop.api/add!))))
    (testing "a test touching no route joins to nothing"
      (is (not-any? #(contains? % 'shop.api-test/elsewhere) (vals joined))))))

(deftest src-is-a-route-reference-too
  ;; `url-attrs` answers "is this a link" from the HTML spec rather than by
  ;; guessing, which is right — and it listed only :href (a/link/area/base)
  ;; and :action (form). `:src` was simply missing, so a <script> or an <img>
  ;; pointing at a path nothing serves was invisible to the gate built to
  ;; catch exactly that.
  ;;
  ;; Found in anger: slopp's OWN reviewer UI shipped
  ;; `[:script {:src "/assets/cljs/main.js"}]` in the shell of every page,
  ;; served by nothing, 404ing on every request since the wave that added it.
  ;; The gate that should have failed `done` never saw it.
  (let [st (store/ingest (store/empty-store) 'sr.pages
                         (str "(ns sr.pages)\n"
                              "(defn ^{:http/method :get :http/path \"/real\""
                              "        :http/auth :public :rest/response :string}\n"
                              "  page [_req]\n"
                              "  [:html [:head\n"
                              "    [:script {:src \"/nowhere/main.js\"}]\n"
                              "    [:script {:src \"/real\"}]\n"
                              "    [:img {:src \"/missing.png\"}]]])\n"))
        refs (rules.http/ui-route-refs st)
        by-path (into {} (map (juxt :path identity)) refs)]
    (testing "a script src is a route reference"
      (is (contains? by-path "/nowhere/main.js") (pr-str refs))
      (is (= :src (:attr (by-path "/nowhere/main.js")))))
    (testing "an img src is one too"
      (is (contains? by-path "/missing.png") (pr-str refs)))
    (testing "and one that IS served does not dangle"
      (let [{:keys [dangling]} (rules.http/dangling-route-refs st)
            paths (set (map :path dangling))]
        (is (contains? paths "/nowhere/main.js"))
        (is (contains? paths "/missing.png"))
        (is (not (contains? paths "/real"))
            "a src pointing at a declared endpoint is served, like any href")))))

(deftest client-routes-under-a-declared-prefix-are-served
  ;; `:webapp/client-routes` says a document serves client routes under a prefix, so a link
  ;; to /store/form/f1 IS served even though no endpoint declares that path.
  ;; The gate has to know, or every in-app link in a client-routed app reads
  ;; as dangling and the finding becomes noise someone learns to ignore.
  ;;
  ;; The other half matters more: a link OUTSIDE every declared prefix must
  ;; still be flagged. A gate that treats a fallback as "anything goes" has
  ;; given up the only thing it does.
  (let [st (store/ingest (store/empty-store) 'sp.pages
                         (str "(ns sp.pages)\n"
                              "(defn ^{:http/method :get :http/path \"/\""
                              "        :http/auth :public :rest/response :string\n"
                              "        :webapp/client-routes [\"/store\"]}\n"
                              "  app [_req]\n"
                              "  [:html [:body\n"
                              "    [:a {:href \"/store/form/f1\"} \"a client route\"]\n"
                              "    [:a {:href \"/nope/x\"} \"nothing serves this\"]]])\n"))
        {:keys [dangling]} (rules.http/dangling-route-refs st)
        paths (set (map :path dangling))]
    (testing "the endpoint row carries the declared prefixes, so a reader sees them"
      (is (= ["/store"] (:webapp/client-routes (first (rules.http/endpoints st))))))
    (testing "a client route under the prefix is served by the fallback"
      (is (not (contains? paths "/store/form/f1")) (pr-str dangling)))
    (testing "a path outside every prefix still dangles"
      (is (contains? paths "/nope/x") (pr-str dangling)))))

(deftest a-client-router-path-is-not-somebody-elses-server
  ;; Friction 13, measured on slopp-ui: view forms render `/store/ns/foo`, which
  ;; no SERVER route matches, so the dangling-route check flagged them. The only
  ;; escape was `^{:http/external-path}`, and it discharged the check while filing
  ;; a FALSE statement — the crossings inventory then reported those forms as
  ;; leaving for "somebody else's server".
  ;;
  ;; The fix at the time was a second marker, `^:webapp/client-path`: same
  ;; discharge, truthful category. Teaching the check to SEE the prefixing was
  ;; the alternative and could not be done, because the mount point arrived
  ;; through an ordinary function call.
  ;;
  ;; **That marker is now RETIRED, and this test is what it leaves behind.**
  ;; slopp does the prefixing and `:webapp/routes` is a declared table, so a
  ;; literal is a client route key with something to be a key INTO. The
  ;; distinction the marker existed to protect is still the point — a client
  ;; path is not somebody else's server — but it is CHECKED rather than
  ;; declared, which is strictly better: a marker asserts, a join verifies.
  (let [src (str "(ns browser.ui)\n\n"
                 "(defn ^{:webapp/path \"/store/ns/:ns\"} screen\n"
                 "  \"S.\" [_a _p] [:p \"s\"])\n\n"
                 "(defn ns-link \"N.\" [] [:a {:href \"/store/ns/shop.core\"} \"ns\"])\n\n"
                 "(defn plain \"P.\" [] [:a {:href \"/served-by-nobody\"} \"x\"])\n")
        s     (store/ingest (store/empty-store) 'browser.ui src)
        found (rules.http/dangling-route-refs s)
        paths (set (map :path (:dangling found)))]

    (testing "a client route resolves with no marker at all"
      ;; what thirteen identical `^:webapp/client-path` sentences used to buy
      (is (not (contains? paths "/store/ns/shop.core"))
          (pr-str (:dangling found))))

    (testing "and a path nothing serves is still reported"
      ;; the arm that keeps this a check rather than a blanket pass: if every
      ;; in-app-looking literal resolved, retiring the marker would have been
      ;; switching the rule off and calling it progress
      (is (contains? paths "/served-by-nobody") (pr-str found)))

    (testing "the crossings category is what the marker was really protecting"
      ;; filing an app's own screen as `external-path` put a false statement in
      ;; the one report someone reads to learn what is NOT checked. Nobody needs
      ;; to reach for it now, which is the honest way for that risk to end
      (is (not (contains? paths "/store/ns/shop.core"))
          "an app that must mark its own screens as foreign will mark them wrong"))))

(deftest serving-namespaces-derive-from-the-store-not-a-hand-kept-list
  ;; `:http/namespaces` is the one REQUIRED opt on serve!, and `web/context`'s
  ;; own docstring warns that "a :http/namespaces list missing half the app
  ;; assembles happily and answers". A hand-kept list of what to serve IS
  ;; that defect, held by every app. The store already knows: endpoint rows
  ;; carry :ns, and the performer vocabularies carry qualified syms.
  (let [api   (str "(ns shop.api)\n\n"
                   "(defn ^{:http/method :get :http/path \"/api/users/:id\"\n"
                   "        :http/auth :authenticated\n"
                   "        :http/reads {:user [:user/by-id [:path-params :id]]}\n"
                   "        :malli/schema [:=> [:cat :map] :map]\n"
                   "        :rest/response :map} get-user \"U.\" [req] req)\n")
        ;; the performer lives in ANOTHER namespace — this is the one a hand
        ;; list forgets, and omitting it is not a quiet degradation: context
        ;; throws :http/missing-performers because the route above promises a
        ;; read nothing listed can serve.
        data  (str "(ns shop.data)\n\n"
                   "(defn ^{:http/read :user/by-id} user-by-id \"R.\" [ctx id] id)\n"
                   "(defn ^{:http/effect :user/insert} insert! \"I.\" [ctx row] row)\n")
        ui    (str "(ns shop.ui)\n\n"
                   "(defn ^{:http/method :get :http/path \"/users\"\n"
                   "        :rest/response :hiccup} users-page \"P.\" [req] [:div])\n")
        plain (str "(ns shop.util)\n\n(defn helper \"H.\" [x] x)\n")
        fixt  (str "(ns shop.api-test)\n\n"
                   "(defn ^{:http/method :get :http/path \"/fixture\"} fx \"F.\" [req] req)\n")
        s     (-> (store/empty-store)
                  (store/ingest 'shop.api api)
                  (store/ingest 'shop.data data)
                  (store/ingest 'shop.ui ui)
                  (store/ingest 'shop.util plain)
                  (store/ingest 'shop.api-test fixt))]
    (testing "every namespace carrying route or performer surface, and no other"
      (is (= ['shop.api 'shop.data 'shop.ui] (rules.http/serving-namespaces s))))
    (testing "sorted, so a build's emitted main is byte-stable across runs"
      (is (= (sort (rules.http/serving-namespaces s)) (rules.http/serving-namespaces s))))
    (testing "a namespace with no web surface is not served"
      (is (not (some #{'shop.util} (rules.http/serving-namespaces s)))))
    (testing "a -test namespace's endpoint-shaped form is a fixture, not surface"
      ;; the same rule routes-report already applies; serving it would mount
      ;; a test's fake endpoint on the real app
      (is (not (some #{'shop.api-test} (rules.http/serving-namespaces s)))))
    (testing "a store with no web surface serves nothing, rather than erroring"
      (is (= [] (rules.http/serving-namespaces (store/empty-store)))))))

(deftest the-store-backed-reader-meets-the-reader-contract
  ;; The RUN lives beside the adapter so the contract can reach a
  ;; package-private implementation without exporting it for a test's benefit.
  ;; The contract itself belongs to the port's owner (slopp.http.static), which
  ;; is the only home that does not make it one implementation's test.
  ;;
  ;; It travelled here from slopp.mcp.http-test when the HTTP MCP transport
  ;; was retired. The adapter was only ever housed there; it answers the same
  ;; port as `static/file-or-resource-reader`, and the two have diverged
  ;; before — a mount prefix written `public/` asks for `public//app.css`,
  ;; which a filesystem normalises away and a manifest lookup does not.
  ;;
  ;; In-image and cheap: a store's :files is a plain map, so this adapter
  ;; needs no database, no session and no socket.
  (slopp.http-test/reader-contract "store"
                            (fn [files]
                              (rules.http/store-reader (constantly {:files files})
                                                (constantly nil)))))

(deftest the-app-declares-its-context-builder-with-a-marker
  ;; The managed app server writes the `serve!` call, so it needs to know how
  ;; to build `:http/perform-ctx` — the map a handler receives as `:http/deps`
  ;; and every performer receives as its first argument. It is app-specific
  ;; by definition (a registry, a pool, a database handle), so the app has to
  ;; say, and a MARKER is how everything else in this framework is addressed.
  ;;
  ;; A marker rather than a capability naming a qualified symbol, for a
  ;; reason slopp-ui named: a marker makes a GATE possible. Both halves are
  ;; then visible in the store — handlers that take `:http/deps`, and whether
  ;; anything claims to build it — so "this store takes :http/deps and
  ;; declares no builder" can refuse at the WRITE instead of 500ing in a
  ;; browser. A capability is a string in config, checkable at boot, which is
  ;; later and weaker.
  ;;
  ;; It cannot be a PERFORMER, and that idea is circular rather than merely
  ;; wrong: performers already RECEIVE the perform-ctx as their first
  ;; argument, so the context is strictly upstream of the vocabulary and
  ;; cannot be a member of it. Stated here because it is the obvious
  ;; suggestion.
  (let [ns-src (fn [body] (str "(ns app.system)\n\n" body))
        one    (store/ingest (store/empty-store) 'app.system
                             (ns-src (str "(defn ^{:http/context true} deps \"D.\""
                                          " [] {:registry (atom {})})\n")))]
    (testing "the marked var, fully qualified — the generated serve call has
              to name it from another image"
      (is (= 'app.system/deps (rules.http/context-builder one))))
    (testing "a store that declares none says so plainly, because MOST apps
              need no context and that is not a defect"
      (is (nil? (rules.http/context-builder (store/empty-store)))))
    (testing "TWO builders is a refusal, not a pick — the context is a
              singleton and choosing one silently is how an app ends up
              running on the deps it did not mean"
      (let [two (store/ingest one 'app.other
                              (str "(ns app.other)\n\n"
                                   "(defn ^{:http/context true} deps \"D.\" [] {})\n"))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)one"
                              (rules.http/context-builder two)))))))

(deftest static-mounts-are-read-once-from-the-capability-family
  ;; The family was parsed privately inside `cljs/served-by-a-mount?`, so the
  ;; managed server could not ask the same question without writing a second
  ;; parser — and two parsers of one config family agree right up until one
  ;; of them learns about trailing slashes. This is the shared one.
  (let [put (fn [s k v] (first (store/record-config-put s "capabilities" :manifest k v)))
        s   (-> (store/empty-store)
                (put "http.enabled" "true")
                (put "http.static./assets" "public")
                (put "http.static./js" "public/cljs/"))]
    (testing "the key tail is the URL prefix, the value the manifest prefix"
      (is (= {"/assets" "public" "/js" "public/cljs"} (rules.http/static-mounts s))))
    (testing "a trailing slash is trimmed, as the capability doc promises"
      ;; not cosmetic: a store-backed reader looks the path up in a manifest
      ;; rather than on a filesystem that would normalise it, so `public/`
      ;; asks for `public//main.js` and gets nothing
      (is (= "public/cljs" (get (rules.http/static-mounts s) "/js"))))
    (testing "non-mount capabilities are not mistaken for mounts"
      (is (nil? (get (rules.http/static-mounts s) "enabled"))))
    (testing "a store with no mounts declares none"
      (is (empty? (rules.http/static-mounts (store/empty-store)))))))

(deftest ^:external declaring-cljs-reports-the-pages-it-strands
  ;; Review B-F6: stranding happens with NO write to the page — declaring a
  ;; dependency :cljs changes no form, so the page's done has nothing to hang
  ;; the finding on, and the old prose claimed a coverage the advisory could
  ;; not deliver. The write that does the stranding is the surface that can
  ;; name it at the moment it happens.
  ;;
  ;; The fixture ASSERTS its own construction: ui.app requiring ui.views is a
  ;; cross-MODULE require, and the first cut of this test left that ingest
  ;; refused and unchecked — so the strand report was measured against a
  ;; store where the page never existed, and [] looked like a bug in the
  ;; report. A fixture that failed to build satisfies every absence assertion
  ;; downstream of it.
  (let [sess (external/open!)]
    (try
      (ops/config-file! sess "capabilities" :key "http.enabled" :value "true"
                        :prompt "opt into HTTP")
      (let [r1 (ops/ingest! sess 'ui.views "(ns ui.views)\n\n(defn view \"V.\" [s] [:div])\n")
            _  (ops/module-dep! sess "ui.app" "ui.views" :prompt "the page renders the views")
            r2 (ops/ingest! sess 'ui.app
                            (str "(ns ui.app (:require [ui.views :as v]))\n\n"
                                 "(defn ^:app/entry page \"P.\" [] {:state (atom {}) :view v/view})\n"))]
        (is (nil? (:error r1)) (pr-str r1))
        (is (nil? (:error r2)) (pr-str r2)))

      (testing "the declaration that strands a page says so, naming page and culprit"
        (let [r (ops/module-platform! sess "ui.views" "cljs"
                                      :prompt "views to the client — the stranding move")]
          (is (nil? (:error r)) (pr-str r))
          (is (= '[{:page ui.app/page :cljs [ui.views]}] (:stranded-pages r)) (pr-str r))
          (is (str/includes? (str (:warning r)) "unreachable"))))

      (testing "a later unrelated :cljs declaration does not re-report the standing strand"
        (ops/ingest! sess 'ui.other "(ns ui.other)\n\n(defn f \"F.\" [x] x)\n")
        (let [r (ops/module-platform! sess "ui.other" "cljs"
                                      :prompt "unrelated client module")]
          (is (nil? (:error r)) (pr-str r))
          (is (nil? (:stranded-pages r))
              "a standing stranding belongs to the declaration that caused it")))
      (finally (ops/close! sess)))))

(deftest every-field-of-a-published-contract-can-say-what-it-IS
  ;; From slopp-ui, 2026-08-08, and the finding is measured rather than
  ;; aesthetic: every entry-property map in slopp's own published 9-endpoint
  ;; document is `{:optional true}` and nothing else. A caller reads
  ;; `:total :int` and cannot learn that it counts hits BEFORE the limit is
  ;; applied — which is a term of the contract, is written down in the schema
  ;; def's docstring, and does not travel, because a docstring is not a value.
  ;;
  ;; Malli property maps are open and already survive the wire, so this is a
  ;; RULE and not a feature: nothing in the framework moves.
  ;;
  ;; The fixture is shaped like the real case rather than the easy one. All ten
  ;; of slopp's own endpoints name their schema through an ALIAS, and simple-name
  ;; resolution is not an option here — `timeline`, `module-index`, `form-view`,
  ;; `change-view`, `ns-outline` and `module-detail` each exist in two or three
  ;; namespaces of this store, so a name-matching resolver would skip most of
  ;; the population it was written for and report clean.
  (let [s (-> (store/empty-store)
              (store/ingest 'dc.contracts
                            (str "(ns dc.contracts)\n\n"
                                 "(def row \"A row.\"\n"
                                 "  [:map [:id {:doc \"the form id\"} :string] [:loc :int]])\n\n"
                                 "(def listing \"A listing.\"\n"
                                 "  [:map [:rows [:sequential row]]\n"
                                 "        [:total {:description \"hits before the limit\"} :int]])\n"))
              (store/ingest 'dc.api
                            (str "(ns dc.api (:require [dc.contracts :as c]))\n\n"
                                 "(defn ^{:http/method :get :http/path \"/l\" :http/auth :public"
                                 " :rest/response c/listing} listing \"L.\" [r] r)\n\n"
                                 "(defn ^{:http/method :get :http/path \"/i\" :http/auth :public"
                                 " :rest/response [:map [:ok {:doc \"it worked\"} :boolean]"
                                 " [:why :string]]} inline \"I.\" [r] r)\n")))
        by (group-by :endpoint (rules.http/undocumented-contract-fields s))]
    (testing "a schema named through an alias is RESOLVED and walked — and the
              walk follows :sequential into the second named schema, which is
              where slopp's own worst case lives (:gaps on /api/modules is four
              bare ints nested one level down)"
      (is (= [{:endpoint 'dc.api/listing :schema :rest/response
               :fields [[:rows] [:rows :loc]]}]
             (by 'dc.api/listing))))
    (testing "an inline schema is walked the same way"
      (is (= [{:endpoint 'dc.api/inline :schema :rest/response
               :fields [[:why]]}]
             (by 'dc.api/inline))))
    (testing "BOTH spellings count as prose — :doc is preferred and :description
              is malli's JSON-Schema spelling, so an imported schema does not
              fail a check for having documented itself in the other dialect"
      (let [flat (set (mapcat :fields (rules.http/undocumented-contract-fields s)))]
        (is (not (contains? flat [:rows :id])) ":doc discharges it")
        (is (not (contains? flat [:total])) ":description discharges it")
        (is (not (contains? flat [:ok])))))
    (testing "a doc BUILT with (str …) is prose. The rule reads source, not
              values, so a computed doc arrives as a list — and (str …) is what
              this codebase writes everywhere, because unlike a docstring a
              schema doc is a VALUE and a multi-line literal ships its own
              indentation to every consumer. The teach says so; the check has to
              agree with it, and did not until this test."
      (let [built (-> (store/empty-store)
                      (store/ingest 'db.api
                                    (str "(ns db.api)\n\n"
                                         "(defn ^{:http/method :get :http/path \"/b\" :http/auth :public"
                                         " :rest/response [:map [:total {:doc (str \"hits before\""
                                         " \" the limit\")} :int]]} b \"B.\" [r] r)\n")))]
        (is (= [] (vec (rules.http/undocumented-contract-fields built))))))
    (testing "a fully documented contract reports NOTHING — without this every
              assertion above is satisfied by a walker that returns every field"
      (let [ok (-> (store/empty-store)
                   (store/ingest 'dd.api
                                 (str "(ns dd.api)\n\n"
                                      "(defn ^{:http/method :get :http/path \"/d\" :http/auth :public"
                                      " :rest/response [:map [:n {:doc \"how many\"} :int]]}"
                                      " d \"D.\" [r] r)\n")))]
        (is (= [] (vec (rules.http/undocumented-contract-fields ok))))))
    (testing "the finding teaches the fix as a literal form, and says which
              spelling it wants"
      (let [f (first (rules.rest/rest-undocumented-contract-check nil s nil))]
        (is (re-find #":doc" (:teach f)) (pr-str f))
        (is (re-find #"\[:rows \{:doc" (:teach f)) (pr-str f))))))

(deftest a-field-that-constrains-NOTHING-is-not-a-declared-field
  ;; slopp-ui, 2026-08-08, and they ranked it above the prose rule they had
  ;; asked for the day before: an undocumented declared field costs a reader a
  ;; lookup, an UNDECLARED one costs everyone the validation they think they
  ;; have. Their `:diff` consumer broke when the shape moved from [String] to
  ;; [[String String]]; the generated client validates every response on
  ;; arrival and could not see it, because the schema said `[:sequential :map]`
  ;; and a bare :map validates any map at all.
  ;;
  ;; The asymmetry that decides the teach: `:any` ADMITS it is saying nothing,
  ;; and a bare `:map` looks like a type while saying the same thing. Both are
  ;; reported; only one is misleading.
  (let [s (-> (store/empty-store)
              (store/ingest 'uc.contracts
                            (str "(ns uc.contracts)\n\n"
                                 "(def row \"A row.\" [:map [:id :string] [:body :map]])\n\n"
                                 "(def listing \"A listing.\"\n"
                                 "  [:map [:rows [:sequential row]] [:arc [:sequential :any]]])\n"))
              (store/ingest 'uc.api
                            (str "(ns uc.api (:require [uc.contracts :as c]))\n\n"
                                 "(defn ^{:http/method :get :http/path \"/l\" :http/auth :public"
                                 " :rest/response c/listing} listing \"L.\" [r] r)\n\n"
                                 "(defn ^{:http/method :get :http/path \"/k\" :http/auth :public"
                                 " :rest/response [:map [:ok :boolean]]} ok \"K.\" [r] r)\n")))
        by (into {} (map (juxt :endpoint identity))
                 (rules.http/unconstrained-contract-fields s))]
    (testing "a bare :map nested behind a named schema is found, with the PATH
              that reaches it — and so is a bare :any, tagged so the teach can
              tell a field that lies from one that abstains"
      (is (= {:endpoint 'uc.api/listing :schema :rest/response
              :fields [{:path [:rows :body] :declares :map}
                       {:path [:arc] :declares :any}]}
             (by 'uc.api/listing))))

    (testing "a fully constrained contract reports NOTHING — without this every
              assertion above is satisfied by a check that flags every field"
      (is (nil? (by 'uc.api/ok)))
      (is (= 1 (count (rules.http/unconstrained-contract-fields s)))))

    (testing "the two rules ask DIFFERENT questions of the same field: :body is
              unconstrained AND undocumented, and prose would discharge only one
              of them — which is the whole reason this is a sibling and not a
              widening"
      (let [documented (store/ingest s 'uc.contracts
                                     (str "(ns uc.contracts)\n\n"
                                          "(def row \"A row.\"\n"
                                          "  [:map [:id {:doc \"the id\"} :string]\n"
                                          "        [:body {:doc \"the payload\"} :map]])\n\n"
                                          "(def listing \"A listing.\"\n"
                                          "  [:map [:rows {:doc \"the rows\"} [:sequential row]]\n"
                                          "        [:arc {:doc \"the arc\"} [:sequential :any]]])\n"))]
        (is (= [] (filterv #(= 'uc.api/listing (:endpoint %))
                           (rules.http/undocumented-contract-fields documented)))
            "prose on THIS endpoint is now complete")
        (is (= [{:path [:rows :body] :declares :map}
                {:path [:arc] :declares :any}]
               (:fields (first (rules.http/unconstrained-contract-fields documented))))
            "and the shape is still undeclared")))

    (testing "the finding teaches what a bare :map costs, in the words that
              matter: it is not a type, and the validator believes it"
      (let [f (first (rules.rest/rest-unconstrained-contract-check nil s nil))]
        (is (re-find #"validat" (:teach f)) (pr-str f))))))

(deftest an-endpoint-that-cannot-constrain-can-say-so-and-the-marker-polices-itself
  ;; slopp-ui found this, and it is a defect in the rule rather than in their
  ;; code. The teach ends "if the shape genuinely is not settled, say :any and
  ;; let the document be honest about it" — and the rule REPORTS :any. So the
  ;; advice names the state it is flagging, and their `project-api`, a proxy
  ;; forwarding a project's bytes verbatim, had no way to discharge it. Their
  ;; argument for why that matters is the one worth keeping: a permanent
  ;; finding with no available action trains a reader to skim the list, which
  ;; spends the credibility of every OTHER finding in it.
  ;;
  ;; `:any` stays reported — it is honest, not discharged. What discharges is a
  ;; marker carrying its REASON, the shape `^:foreign-keys` and `^:unused-ok`
  ;; already have here.
  (let [mk (fn [extra resp]
             (store/ingest (store/empty-store) 'uw.api
                           (str "(ns uw.api)\n\n"
                                "(defn ^{:http/method :get :http/path \"/p\""
                                " :http/auth :public" extra
                                " :rest/response " resp "} proxy \"P.\" [r] r)\n")))
        plain (mk "" ":any")
        proxy (mk (str " :rest/unconstrained-ok \"a proxy: the response IS whatever"
                       " the project sent, forwarded byte for byte\"")
                  ":any")
        stale (mk " :rest/unconstrained-ok \"no longer true\"" "[:map [:id :string]]")]

    (testing "without the marker it still fires — the finding is TRUE"
      (is (seq (rules.rest/rest-unconstrained-contract-check nil plain nil))))

    (testing "the marker discharges it"
      (is (empty? (rules.rest/rest-unconstrained-contract-check nil proxy nil))))

    (testing "and it polices itself — a marker on a schema that DOES constrain
              is reported, so this cannot quietly become a mute button"
      (let [f (rules.rest/rest-unconstrained-contract-check nil stale nil)]
        (is (= 1 (count f)) (pr-str f))
        (is (:stale-marker (first f)) (pr-str f))
        (is (re-find #"unconstrained-ok" (str (:teach (first f)))) (pr-str f))))))

(deftest a-link-to-a-declared-CLIENT-route-needs-no-escape
  ;; `^:webapp/client-path` existed because this check could not see the prefixing:
  ;;
  ;;   teaching the check to SEE the prefixing is not possible in general,
  ;;   because the base arrives through an ordinary function call
  ;;
  ;; Both halves of that changed. slopp does the prefixing, so a literal in a
  ;; view is a CLIENT ROUTE KEY rather than an ambiguous string — and the route
  ;; table is data, so there is something to join it to.
  ;;
  ;; In one consuming store the escape was on THIRTEEN views, every one
  ;; discharged with the same accurate sentence. That is what this retires.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:webapp/path \"/store\"} things\n"
                 "  \"T.\" [_a _p] [:p \"things\"])\n\n"
                 "(defn ^{:webapp/path \"/store/form/:id\"} form-page\n"
                 "  \"F.\" [_a _p] [:p \"form\"])\n\n"
                 "(defn nav \"N.\" [_s]\n"
                 "  [:nav [:a {:href \"/store\"} \"Store\"]\n"
                 "        [:a {:href \"/store/form/f1\"} \"A form\"]\n"
                 "        [:a {:href \"/store/nope\"} \"Typo\"]])\n")
        st  (store/ingest (store/empty-store) 'shop.ui src)
        found (rules.http/dangling-route-refs st)
        paths (set (map :path (:dangling found)))]

    (testing "a link matching a declared client route is SERVED, unmarked"
      (is (not (contains? paths "/store")) (pr-str (:dangling found)))
      (is (not (contains? paths "/store/form/f1"))
          (str "a parameterised client route must match like a server one: "
               (pr-str (:dangling found)))))

    (testing "and a link matching NO client route still dangles"
      ;; the half that keeps the check worth having. If every in-app-looking
      ;; path resolved, the escape would have been replaced by a blanket pass
      ;; and a typo would go to a blank screen at a plausible url
      (is (contains? paths "/store/nope")
          (str "a typo'd client link is exactly what this check is for: "
               (pr-str found))))))

(deftest a-PREFIX-literal-resolves-against-a-client-route-pattern
  ;; The shape that decides whether retiring `^:webapp/client-path` helps anybody.
  ;; slopp-ui counted their own links: 6 whole literals, ~18 PREFIX literals
  ;; inside `(str …)`, 2 with no literal at all. The dominant shape is
  ;;
  ;;   [:a {:href (str "/store/form/" (:form-id r))} …]
  ;;
  ;; whose literal is `"/store/form/"` — a prefix of the pattern
  ;; `/store/form/:id`, not the pattern and not a whole path. The rule already
  ;; classifies it as a `:prefix` reference, which is why the markers went on
  ;; those views in the first place.
  ;;
  ;; So the retirement only helps if resolution treats it the same way the
  ;; classification does. The server arm of this branch already asks whether
  ;; some declared route STARTS WITH the reference; the client arm has to ask
  ;; the same question of the client table, or eighteen of nineteen markers stay
  ;; on for exactly the reason they went on.
  (let [src (str "(ns shop.ui)\n\n"
                 "(defn ^{:webapp/path \"/store/form/:id\"} form-page\n"
                 "  \"F.\" [_a _p] [:p \"s\"])\n\n"
                 "(defn ^{:webapp/path \"/store/ns/:ns\"} ns-page\n"
                 "  \"N.\" [_a _p] [:p \"s\"])\n\n"
                 "(defn links \"L.\" [r]\n"
                 "  [:nav [:a {:href (str \"/store/form/\" (:id r))} \"form\"]\n"
                 "        [:a {:href (str \"/store/ns/\" (:ns r))} \"ns\"]\n"
                 "        [:a {:href (str \"/nowhere/\" (:x r))} \"nope\"]])\n")
        st    (store/ingest (store/empty-store) 'shop.ui src)
        found (rules.http/dangling-route-refs st)
        paths (set (map :path (:dangling found)))]

    (testing "the reference really is classified as a PREFIX"
      ;; the premise. If these were :exact the test would be about a different
      ;; shape than the one that matters
      (is (= #{:prefix}
             (set (map :kind (filter #(= "/store/form/" (:path %))
                                     (rules.http/ui-route-refs st)))))
          (pr-str (rules.http/ui-route-refs st))))

    (testing "a prefix literal covering a declared client route resolves"
      (is (not (contains? paths "/store/form/")) (pr-str (:dangling found)))
      (is (not (contains? paths "/store/ns/")) (pr-str (:dangling found))))

    (testing "and a prefix literal covering NOTHING still dangles"
      ;; the arm that keeps this from being a blanket pass on anything ending
      ;; in a slash
      (is (contains? paths "/nowhere/") (pr-str found)))))

(deftest a-route-is-an-API-or-CONTENT-and-the-traversal-says-which
  ;; Nathan, 2026-08-23: `:http/path` was the only path declaration slopp had,
  ;; so a REST endpoint and a stylesheet were the same kind of thing to every
  ;; gate. `rest-endpoint-schema` then asked EVERY endpoint for a contract once
  ;; `rest` was on — including the pages — so a page declared
  ;; `:rest/response :string`, which is a lie about a `text/css` body, and
  ;; `:rest/client false` existed to undo it. The flag was the ABSENCE of this
  ;; distinction, worked around: measured at 10 of 11 endpoints in the
  ;; consuming store, 9 of them bare copies recording no reason.
  ;;
  ;; `:rest/path` is the concept. The traversal is where it has to land first,
  ;; because `web-endpoint-rows` is the SINGLE route walk — the router, the
  ;; collision gate, query_surface, vendoring and the contract all build on it,
  ;; and a second walk for the second marker is how the two would drift.
  (let [st (-> (store/empty-store)
               (store/ingest 'demo.api
                             (str "(ns demo.api)\n\n"
                                  "(defn ^{:rest/path \"/api/orders\" :http/method :get"
                                  " :http/auth :public :rest/response [:map]}\n"
                                  "  orders \"O.\" [_] {:status 200})\n"))
               (store/ingest 'demo.pages
                             (str "(ns demo.pages)\n\n"
                                  "(defn ^{:http/path \"/css/app.css\" :http/method :get"
                                  " :http/auth :public}\n"
                                  "  stylesheet \"S.\" [_] {:status 200})\n")))
        rows (edit.http/web-endpoint-rows st)
        by-path (into {} (map (juxt #(str (or (:rest/path (:meta %)) (:http/path (:meta %))))
                                    identity))
                      rows)]

    (testing "BOTH kinds are routes, and one walk finds them"
      (is (= 2 (count rows))
          (str "a marker the traversal cannot see is a route that never serves,"
               " with no write-time signal: " (pr-str rows))))

    (testing "and every row says which kind it is"
      (is (= :rest (:kind (by-path "/api/orders"))) (pr-str rows))
      (is (= :content (:kind (by-path "/css/app.css"))) (pr-str rows)))

    (testing "rules/endpoints carries the path and the kind through"
      (let [es (into {} (map (juxt :path identity)) (rules.http/endpoints st))]
        (is (= :rest (:kind (es "/api/orders"))) (pr-str es))
        (is (= :content (:kind (es "/css/app.css"))) (pr-str es))
        (is (= '[demo.api/orders demo.pages/stylesheet]
               (sort (map :handler (vals es)))))))))

(deftest an-app-that-REWRITES-its-own-links-makes-the-join-unresolvable
  ;; The blindness a consuming store measured: it applies its own prefix at
  ;; render (`slopp.webapp/prefix-links` with a project-relative table), so an
  ;; `:href` literal is in APP space and the served table is in SERVER space.
  ;; Every one of its 23 links read as dangling and every one worked.
  ;;
  ;; Reporting them as dangling is the wrong answer twice: they are not
  ;; dangling, and 23 findings nobody can clear spend the credibility of every
  ;; other finding in the list. Silently passing them is worse — the guarantee
  ;; would stop applying with nothing saying so.
  ;;
  ;; So they become UNRESOLVED: named, `:severity :info`, never counted clean.
  ;; The same bucket a computed path already lands in, for the same reason —
  ;; this check cannot answer, and says which.
  (let [view (str "(defn nav \"N.\" [_s]\n"
                  "  [:nav [:a {:href \"/store/nope\"} \"Typo\"]])\n")
        app  (str "(defn ^:app/entry app \"A.\" []\n"
                  "  {:webapp/routes [[\"/store\" nav]]})\n")]

    (testing "WITHOUT self-prefixing, an unserved literal dangles as it always did"
      (let [st (store/ingest (store/empty-store) 'shop.ui
                             (str "(ns shop.ui)\n\n" view "\n" app))
            {:keys [dangling]} (rules.http/dangling-route-refs st)]
        (is (contains? (set (map :path dangling)) "/store/nope")
            (str "the control: without it this MUST dangle, or the assertion"
                 " below holds for a reason that has nothing to do with"
                 " prefixing: " (pr-str dangling)))))

    (testing "WITH it, the same literal is unresolved and NAMES why"
      (let [st (store/ingest (store/empty-store) 'shop.ui
                             (str "(ns shop.ui\n"
                                  "  (:require [slopp.webapp :as webapp]))\n\n"
                                  "(defn chrome \"C.\" [s body]\n"
                                  "  (webapp/prefix-links \"/p/x\" [\"/store\"] body))\n\n"
                                  view "\n" app))
            {:keys [dangling unresolved]} (rules.http/dangling-route-refs st)]
        (is (not (contains? (set (map :path dangling)) "/store/nope"))
            (str "a link this check cannot resolve is not a link it knows is"
                 " broken: " (pr-str dangling)))
        (let [row (first (filter #(= "/store/nope" (:path %)) unresolved))]
          (is (some? row) (pr-str unresolved))
          (is (re-find #"prefix-links" (str (:why row)))
              (str "and the reason has to name the mechanism, or the reader"
                   " cannot tell this from an ordinary dynamic path: "
                   (pr-str row))))))))

(deftest a-PRIVATE-route-already-in-the-store-is-swept
  ;; `http-unreachable-declaration` is a per-form WRITE gate: it refuses the
  ;; next form to declare a route privately and never asks the question of one
  ;; already there. A violation arriving by any path that is not a write —
  ;; import_dir, a branch merge, an episode_revert — passes it untouched, and
  ;; done is episode-scoped so nothing asks again.
  ;;
  ;; The asymmetry was in slopp's own catalog and a consuming store found it:
  ;; `webapp-page-reach` is the PAGE version of this exact question, whose
  ;; wording the route gate reuses, and it IS swept. Pages got both grains;
  ;; routes got one. They declined to delete their temporary store-side guard
  ;; until this existed, which was the right call.
  (let [on (fn [src]
             (-> (store/ingest (store/empty-store) 'shop.api src)
                 (assoc-in [:config "capabilities" :values "http.enabled"] "true")))]

    (testing "a private ROUTE that never passed a write gate is reported"
      (let [st (on (str "(ns shop.api)\n\n"
                        "(defn- ^{:http/method :get :rest/path \"/api/x\"\n"
                        "         :http/auth :public :rest/response :map}\n"
                        "  x \"X.\" [req] req)\n"))
            f  (rules.http/http-unreachable-declaration-check nil st nil)]
        (is (seq f) "a route nothing serves must not be silent once it is in")
        (is (re-find #"ns-publics" (str (:teach (first f)))) (pr-str f))))

    (testing "a private PERFORMER too, and it says 500 rather than 404"
      (let [st (on (str "(ns shop.api)\n\n"
                        "(defn- ^{:http/read :thing/one} one \"O.\" [ctx k] k)\n"))
            f  (rules.http/http-unreachable-declaration-check nil st nil)]
        (is (seq f) (pr-str f))
        (is (re-find #"500" (str (:teach (first f)))) (pr-str f))))

    (testing "public forms are clean, and a private form with no marker is not ours"
      (let [st (on (str "(ns shop.api)\n\n"
                        "(defn ^{:http/method :get :rest/path \"/api/y\"\n"
                        "        :http/auth :public :rest/response :map}\n"
                        "  y \"Y.\" [req] req)\n\n"
                        "(defn- helper \"H.\" [x] x)\n"))]
        (is (empty? (rules.http/http-unreachable-declaration-check nil st nil))
            (pr-str (rules.http/http-unreachable-declaration-check nil st nil)))))

    (testing "the check does NOT gate itself on the capability"
      ;; inertness is the RUNNER's: `capabilities/rule-owner` derives the owner
      ;; from the rule's NAME. A check that gated itself would answer nothing
      ;; on its own `:fires-on` fixture, which is how this was caught before it
      ;; could ship as a rule that can never fire.
      (let [off (store/ingest (store/empty-store) 'shop.api
                              (str "(ns shop.api)\n\n"
                                   "(defn- ^{:http/method :get :rest/path \"/api/x\"\n"
                                   "         :http/auth :public :rest/response :map}\n"
                                   "  x \"X.\" [req] req)\n"))]
        (is (seq (rules.http/http-unreachable-declaration-check nil off nil)))))))
