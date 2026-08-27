(ns slopp.http-test
  "The web facade from the OUTSIDE: `serve!` on a real port, answered by a real
  client, across both server adapters.

  It is also the home of `reader-contract` — the suite both adapters of the
  static-file reader port must pass — which is why a test namespace here is
  required by others rather than being a leaf.

  Everything that binds a socket in here uses its OWN client, declared
  `^{:adapter \"http — …\"}` per test rather than exempted by rule. That is the
  one deliberate exception to \"all HTTP goes through `slopp.http.client`\":
  `requester-contract` uses `serve!` as ITS far side, so routing the server's
  own tests through the client would close the loop and let a symmetric bug —
  client omits a header, server ignores it — pass both suites."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.http :as slopp.http] [slopp.http.static :as static] [clojure.string :as str] [slopp.lang :as lang] [slopp.rest.contract :as rest.contract]))

(def ^:private honouring
  "`:http/wrap-context` that puts the contract validators on, which every
  context in this namespace now needs.

  `t-mine` declares `:rest/response`, because in THIS store every `defn` route
  must: the content gate refuses a `defn` under `:http/path`, and
  `rest-endpoint-schema` asks a `:rest/path` for its contract. So the http
  facade's own tests cannot build a context over their own fixtures without the
  rest capability — which is a true statement about a store whose surface is
  entirely typed, rather than about http.

  `slopp.rest/validating` is what an app writes; this reaches
  `slopp.rest.contract` directly through the TEST-ONLY module edge
  `slopp.http.dispatch-test` already declares, so the facade's tests do not put
  a production dependency on rest to say what they are saying."
  #(assoc % :rest/decode-request rest.contract/decode-request
          :rest/check-response rest.contract/check-response))

(defn ^{:http/method :get :rest/path "/api/w/mine/:owner"
        :http/auth :authenticated
        :rest/response [:map [:yours :boolean]]}
  t-mine
  "Row-level check inside the handler: only the owner may read.

  Declared `:rest/path` and not `:http/path` because it COMPUTES an answer
  from the request. `:http/path` names a stored value now, and a `defn` under
  it would be served as the string of its own function object."
  [req]
  (slopp.http/enforce (= (:owner (:path-params req))
                        (:http/sub (:http/identity req))))
  {:status 200 :body {:yours true}})

(def ^{:http/method :get :http/path "/about" :http/auth :public}
  t-about
  "Hiccup content: the def's VALUE is the page."
  [:main [:h1 "About"]])

(def ^{:http/method :get :http/path "/robots.txt" :http/auth :public
       :http/media-type "text/plain"}
  t-robots
  "String content: served as it stands, at a declared media type."
  "User-agent: *\nDisallow:\n")

(def ^{:http/method :get :http/path "/" :http/auth :public
       :webapp/shell true}
  t-shell
  "The SPA shell. The app writes the WHOLE document — title, meta, stylesheet,
  mount point — and writes neither the bundle script nor the mount prefix,
  because neither is a static fact about this page."
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:title "Demo"]
    [:link {:rel "stylesheet" :href "/css/style.css"}]]
   [:body [:div {:id "app"}]]])

(def t-broken-shell
  "A shell an app got wrong: no mount point, so nothing renders. Carries no
  markers — it is handed to a context as an explicit row instead, so it stays
  out of every other test's route table."
  [:html [:head [:title "Broken"]] [:body [:div {:id "root"}]]])

(deftest facade-assembles-and-enforces
  (let [ctx (slopp.http/context {:http/namespaces ['slopp.http-test]
                                       ;; this namespace declares a shell, and a
                                       ;; shell with no bundle refuses at assembly
                                       :webapp/bundle "/js/main.js"
                                       :http/wrap-context honouring})]
    (testing "context derives the route table from var metadata"
      (is (= {"/api/w/mine/:owner" :rest, "/" :content
              "/about" :content, "/robots.txt" :content}
             (into {} (map (juxt :path :kind)) (:http/routes ctx)))
          "both markers, and the row says which one carried the path"))
    (testing "handle! is the portless test surface"
      (let [r (slopp.http/handle! ctx {:request-method :get :uri "/api/w/mine/ada"
                                :http/identity {:http/sub "ada" :http/groups #{}}})]
        (is (= 200 (:status r)) (pr-str r))))
    (testing "enforce inside the handler maps to 403 response data"
      (let [r (slopp.http/handle! ctx {:request-method :get :uri "/api/w/mine/ada"
                                :http/identity {:http/sub "eve" :http/groups #{}}})]
        (is (= 403 (:status r)) (pr-str r))))
    (testing "authorized? answers booleans for branching"
      (is (slopp.http/authorized? [:group "admin"] {:http/groups #{"admin"}}))
      (is (not (slopp.http/authorized? [:group "admin"] nil))))))

(deftest content-is-a-VALUE-the-dispatcher-serves-not-a-handler-it-calls
  (let [ctx (slopp.http/context {:http/namespaces ['slopp.http-test]
                                       :webapp/bundle "/js/main.js"
                                       :http/wrap-context honouring})]
    (testing "hiccup content renders, and keeps its structure for a headless drive"
      (let [r (slopp.http/handle! ctx {:request-method :get :uri "/about"})]
        (is (= 200 (:status r)) (pr-str r))
        (is (= "text/html; charset=utf-8" (get-in r [:headers "Content-Type"])))
        (is (= [:main [:h1 "About"]] (:http/hiccup r))
            "the tree the body was rendered from, same as html-response carries")
        (is (str/includes? (str (:body r)) "<h1>About</h1>") (pr-str (:body r)))))
    (testing "a string is served as it stands, at the media type the def declares"
      (let [r (slopp.http/handle! ctx {:request-method :get :uri "/robots.txt"})]
        (is (= 200 (:status r)) (pr-str r))
        (is (= "text/plain" (get-in r [:headers "Content-Type"]))
            "verbatim, and deliberately NOT the \"text/plain; charset=utf-8\"
             default — an assertion that matched the default would hold
             whether or not the declaration was ever read")
        (is (= "User-agent: *\nDisallow:\n" (:body r)))
        (is (nil? (:http/hiccup r))
            "nothing was rendered, so there is no tree to carry")))))

(deftest a-webapp-SHELL-is-completed-by-the-framework
  (let [ctx  (slopp.http/context {:http/namespaces ['slopp.http-test]
                                  :http/wrap-context honouring
                                  :webapp/base   "/p/demo"
                                  ;; the row declares THAT it is a shell; which
                                  ;; bundle is a deployment fact, stated once
                                  ;; here beside the mount point
                                  :webapp/bundle "/js/main.js"})
        r    (slopp.http/handle! ctx {:request-method :get :uri "/"})
        body (str (:body r))]
    (is (= 200 (:status r)) (pr-str r))
    (testing "the bundle script is injected, INTO the head the app wrote"
      (is (str/includes? body "src=\"/js/main.js\"") body)
      (is (str/includes? body "<script defer") body)
      (is (< (str/index-of body "<script") (str/index-of body "</head>"))
          "in the head and not merely somewhere in the document"))
    (testing "the mount point carries the base, which no static document knows"
      (is (str/includes? body "data-base=\"/p/demo\"") body)
      (is (str/includes? body "id=\"app\"") body))
    (testing "what the app wrote survives untouched"
      (is (str/includes? body "<title>Demo</title>") body)
      (is (str/includes? body "href=\"/css/style.css\"") body)
      (is (str/starts-with? body "<!DOCTYPE html>") body))
    (testing "and the app's own hiccup is NOT what gets served"
      (is (not= t-shell (:http/hiccup r))
          "the tree a headless drive reads is the completed one, so what it
           drives is what a browser would get"))))

(deftest a-broken-shell-refuses-when-the-app-is-ASSEMBLED
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"no mount point"
       (slopp.http/context
        {:http/namespaces []
         :webapp/bundle "/js/main.js"
         :http/routes [{:handler #'t-broken-shell :kind :content :method :get
                        :path "/bad" :auth :public
                        :webapp/shell true}]}))
      "assembly and not the first request: a shell is checked once, where the
       app comes up, rather than answering 500 to whoever loads it first"))

(deftest ^:external
  ^{:adapter "http — a deliberately INDEPENDENT client. requester-contract's
              real run uses serve! as ITS far side, so routing the server's own
              tests through slopp.http.client would make the two mutually
              circular and let a symmetric bug (client omits a header, server
              ignores it) pass both. The server tests are the one place that
              must not go through the port."}
  serve-round-trips-the-facade
  (let [srv (slopp.http/serve! {:http/namespaces ['slopp.http-test]
                         :http/wrap-context honouring
                         ;; this namespace declares a shell, and a shell with
                         ;; no bundle refuses at assembly
                         :webapp/bundle "/js/main.js"
                         :http/port 0})
        http (java.net.http.HttpClient/newHttpClient)
        resp (.send http
                    (-> (java.net.http.HttpRequest/newBuilder)
                        (.uri (java.net.URI/create
                               (str "http://127.0.0.1:" (:port srv) "/api/w/mine/ada")))
                        (.build))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (try
      (testing "the anonymous request is refused by the declared policy, over the wire"
        (is (= 401 (.statusCode resp))))
      (finally (slopp.http/stop! srv)))))

(deftest ^:external ^{:adapter "http — independent client on purpose; same reason as
              serve-round-trips-the-facade, and doubly so here: this test exists
              to prove a SECOND server adapter behaves like the first, which a
              shared client cannot witness."}
  httpkit-adapter-round-trips-the-facade
  (let [srv (slopp.http/serve! {:http/namespaces ['slopp.http-test]
                         :http/wrap-context honouring
                         :http/adapter :http-kit
                         :webapp/bundle "/js/main.js"
                         :http/port 0})
        http (java.net.http.HttpClient/newHttpClient)
        resp (.send http
                    (-> (java.net.http.HttpRequest/newBuilder)
                        (.uri (java.net.URI/create
                               (str "http://127.0.0.1:" (:port srv) "/api/w/mine/ada")))
                        (.build))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (try
      (testing "the declared policy refuses over http-kit exactly as over jdk"
        (is (= 401 (.statusCode resp))))
      (finally (slopp.http/stop! srv)))))

(deftest ^:external ^{:adapter "http — independent client on purpose; same reason as
              serve-round-trips-the-facade. This one sends AUTH headers, which
              is precisely the shape a symmetric client/server bug would hide."}
  auth-round-trips-over-the-wire
  (let [srv (slopp.http/serve! {:http/namespaces ['slopp.http-test]
                         :http/wrap-context honouring
                         :http/adapter :http-kit
                         :http/port 0
                         :webapp/bundle "/js/main.js"
                         :http/auth-config {:auth/providers [:bearer]
                                           :auth/bearer {"ada" {:secret "tok-ada"
                                                                :groups ["dev"]}}}})
        http (java.net.http.HttpClient/newHttpClient)
        GET (fn [path & [token]]
              (let [b (cond-> (java.net.http.HttpRequest/newBuilder)
                        true (.uri (java.net.URI/create
                                    (str "http://127.0.0.1:" (:port srv) path)))
                        token (.header "Authorization" (str "Bearer " token)))]
                (.statusCode (.send http (.build b)
                                    (java.net.http.HttpResponse$BodyHandlers/ofString)))))]
    (try
      (testing "anonymous → 401; wrong token → 401; the right token → 200 (t-mine checks sub=owner)"
        (is (= 401 (GET "/api/w/mine/ada")))
        (is (= 401 (GET "/api/w/mine/ada" "wrong")))
        (is (= 200 (GET "/api/w/mine/ada" "tok-ada")))
        (testing "and enforce still 403s the wrong owner, authenticated or not"
          (is (= 403 (GET "/api/w/mine/someone-else" "tok-ada")))))
      (finally (slopp.http/stop! srv)))))

(deftest ^:external ^{:adapter "http — independent client on purpose; same reason as
              serve-round-trips-the-facade. Raw BYTES are the case where a
              shared client's own decoding would be indistinguishable from the
              server's encoding."}
  static-mounts-serve-raw-bytes
  (let [png (byte-array [(byte -119) 80 78 71 9 8 7])
        reader (fn [path]
                 (get {"public/logo.png" {:content png :content-type "image/png"}
                       "public/app.css"  {:content "body{}" :content-type "text/css"}}
                      path))
        rows (static/mount-routes {"/assets" "public"} reader)
        srv  (slopp.http/serve! {:http/namespaces []
                          :http/routes rows
                          :http/adapter :http-kit
                          :http/port 0})
        http (java.net.http.HttpClient/newHttpClient)
        GET  (fn [path]
               (let [resp (.send http
                                 (-> (java.net.http.HttpRequest/newBuilder)
                                     (.uri (java.net.URI/create
                                            (str "http://127.0.0.1:" (:port srv) path)))
                                     (.build))
                                 (java.net.http.HttpResponse$BodyHandlers/ofByteArray))]
                 {:status (.statusCode resp)
                  :type (.orElse (.firstValue (.headers resp) "content-type") nil)
                  :body (.body resp)}))]
    (try
      (testing "bytes round-trip with their content type, no JSON wrapping"
        (let [r (GET "/assets/logo.png")]
          (is (= 200 (:status r)))
          (is (= "image/png" (:type r)))
          (is (java.util.Arrays/equals png ^bytes (:body r)))))
      (testing "text assets serve as their own media type"
        (let [r (GET "/assets/app.css")]
          (is (= "text/css" (:type r)))
          (is (= "body{}" (String. ^bytes (:body r) "UTF-8")))))
      (testing "an unknown file is a 404"
        (is (= 404 (:status (GET "/assets/nope.js")))))
      (testing "a path prefix written with a TRAILING SLASH mounts the same
                tree. The handler adds its own separator, so `public/` asked
                the reader for `public//app.css` — and a store-backed reader,
                which looks a path up in a manifest rather than on a
                filesystem that would normalise it, answered nothing."
        (let [srv2 (slopp.http/serve! {:http/namespaces []
                                :http/routes (static/mount-routes {"/assets" "public/"} reader)
                                :http/adapter :http-kit :http/port 0})
              get2 (fn [path]
                     (.statusCode
                      (.send http
                             (-> (java.net.http.HttpRequest/newBuilder)
                                 (.uri (java.net.URI/create
                                        (str "http://127.0.0.1:" (:port srv2) path)))
                                 (.build))
                             (java.net.http.HttpResponse$BodyHandlers/ofByteArray))))]
          (try
            (is (= 200 (get2 "/assets/app.css")))
            (finally (slopp.http/stop! srv2)))))
      (finally (slopp.http/stop! srv)))))

(deftest ^:external built-app-reader-resolves-fs-then-resources
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-static" (make-array java.nio.file.attribute.FileAttribute 0)))
        _   (.mkdirs (java.io.File. dir "public"))
        _   (spit (java.io.File. dir "public/app.css") "body{}")
        rdr (static/file-or-resource-reader dir)]
    (testing "a filesystem file resolves with its extension's type"
      (let [{:keys [content content-type]} (rdr "public/app.css")]
        (is (= "text/css" content-type))
        (is (= "body{}" (String. ^bytes content "UTF-8")))))
    (testing "a classpath resource resolves when the file is absent"
      ;; clojure/core.clj is guaranteed on the classpath of any test JVM
      (is (some? (:content (rdr "clojure/version.properties")))))
    (testing "missing everywhere is nil"
      (is (nil? (rdr "public/nope.js"))))))

(deftest ^:external built-app-reader-refuses-path-traversal
  ;; review W5: the reader built File(root, path) with no containment check,
  ;; so `../secret` escaped root. Contained today only by the router's
  ;; single-segment accident — the reader itself must refuse traversal, since
  ;; it is ^:export public surface and the docstring flags the single-segment
  ;; constraint as temporary.
  (let [base (str (java.nio.file.Files/createTempDirectory
                   "slopp-trav" (make-array java.nio.file.attribute.FileAttribute 0)))
        pub  (java.io.File. base "public")
        _    (.mkdirs pub)
        _    (spit (java.io.File. pub "ok.txt") "fine")
        _    (spit (java.io.File. base "secret.txt") "TOP SECRET")
        rdr  (static/file-or-resource-reader (str pub))]
    (testing "an in-root file still serves"
      (is (= "fine" (String. ^bytes (:content (rdr "ok.txt")) "UTF-8"))))
    (testing "a traversal to a file ABOVE root is refused (nil)"
      (is (nil? (rdr "../secret.txt")))
      (is (nil? (rdr "../../etc/hosts")))
      (is (nil? (rdr "sub/../../secret.txt"))))))

(deftest static-mounts-serve-a-tree-and-refuse-traversal
  ;; F6: slopp's own default bundle path (public/cljs/main.js -> /assets/cljs/
  ;; main.js) was unservable because the mount matched one segment only. Now it
  ;; is a catch-all — which REMOVES the accidental containment that was the only
  ;; thing preventing /assets/../../etc/passwd, so the refusal must be explicit
  ;; and the reader must never even be reached.
  (let [seen   (atom [])
        reader (fn [p] (swap! seen conj p) {:content "x" :content-type "text/plain"})
        row    (first (static/mount-routes {"/assets" "public"} reader))
        call   (fn [captured] ((:handler row) {:path-params {:* captured}}))]
    (testing "the mount is a catch-all, so nested assets are reachable"
      (is (= "/assets/**" (:path row))))
    (testing "the mount ROOT is refused too, and it now REACHES this handler"
      ;; `**` matches zero segments, so GET /assets routes here with an empty
      ;; remainder where the old `*path` matched nothing at all. Same answer to
      ;; the caller, decided somewhere that can say why
      (is (= 404 (:status (call "")))))
    (testing "a nested path reads under the mount prefix"
      (is (= 200 (:status (call "cljs/main.js"))))
      (is (= "public/cljs/main.js" (last @seen))))
    (testing "traversal is refused, and the reader is never called"
      (reset! seen [])
      (is (= 404 (:status (call "../../etc/passwd"))))
      (is (= 404 (:status (call "cljs/../../../secret"))))
      (is (= 404 (:status (call "/etc/passwd"))))
      (is (empty? @seen) "no traversal attempt may reach the reader"))))

(deftest static-mounts-fall-back-to-extension-content-type
  ;; F7 (dogfood): mount-routes emitted Content-Type ONLY when the reader
  ;; supplied one — and a store-backed reader returns none for a blob, so the
  ;; compiled JS bundle served with NO Content-Type at all. Browsers applying
  ;; strict MIME checking refuse to execute such a script. The extension table
  ;; already existed in this namespace for the built-app reader; the mount now
  ;; uses it as a fallback, so EVERY reader gets a correct type.
  (let [row  (first (static/mount-routes {"/assets" "public"}
                                         (fn [_] {:content "x"})))
        call (fn [p] ((:handler row) {:path-params {:* p}}))]
    (testing "a typeless blob still serves with the right type"
      (is (= "text/javascript" (get-in (call "cljs/main.js") [:headers "Content-Type"])))
      (is (= "text/css" (get-in (call "app.css") [:headers "Content-Type"]))))
    (testing "a reader-supplied type still wins"
      (let [row2 (first (static/mount-routes
                         {"/assets" "public"}
                         (fn [_] {:content "x" :content-type "text/plain"})))]
        (is (= "text/plain" (get-in ((:handler row2) {:path-params {:* "a.js"}})
                                    [:headers "Content-Type"])))))
    (testing "an unknown extension omits the header rather than guessing"
      (is (nil? (get-in (call "thing.zzz") [:headers "Content-Type"]))))))

(deftest query-params-are-parsed-onto-the-request
  ;; Both adapters put :query-string on the request and NOTHING parsed it,
  ;; so the first app that wanted `?view=x` had to write its own splitter —
  ;; and so would the second. Found by building slopp's own UI on this
  ;; framework: a place the app has to reach around slopp.http is a gap in
  ;; slopp.http.
  (testing "the shapes a URL actually arrives in"
    (is (= {} (lang/query-params nil)))
    (is (= {} (lang/query-params "")))
    (is (= {:view "labeled"} (lang/query-params "view=labeled")))
    (is (= {:a "1" :b "2"} (lang/query-params "a=1&b=2")))
    (is (= {:flag ""} (lang/query-params "flag"))
        "a bare key is present with an empty value — present and empty are not absent"))
  (testing "percent- and plus-encoding, since a value is arbitrary text"
    (is (= {:q "a b"} (lang/query-params "q=a+b")))
    (is (= {:q "a/b?c"} (lang/query-params "q=a%2Fb%3Fc")))
    (is (= {:ns "demo.core"} (lang/query-params "ns=demo.core"))))
  (testing "malformed input is data, never a 500"
    (is (map? (lang/query-params "%%%=x&=y&&"))))
  (testing "and malformed text ARRIVES, rather than the pair being dropped"
    ;; changed when decoding moved to slopp.lang. URLDecoder throws on a stray
    ;; `%`, and the old code caught that and dropped the pair — so `?q=100%`,
    ;; a real search typed by a real person, reached the handler as no query
    ;; at all. Losing the parameter is a worse answer than handing over the
    ;; characters that were typed.
    ;; MEASURED against the old implementation rather than asserted about it,
    ;; because these landed green and a green nobody watched fail proves
    ;; nothing. `(URLDecoder/decode "100%" "UTF-8")` throws, the old code
    ;; caught it and returned nil, and a nil key or value dropped the pair —
    ;; so the two assertions below returned `{}` before this change and are
    ;; the two that discriminate. The `café` and `=y` cases passed BEFORE as
    ;; well; they are regression guards, not evidence, and calling all four
    ;; evidence would be the coverage theatre the advisory names.
    (is (= {:q "100%"} (lang/query-params "q=100%")))
    (is (= {:q "a%zzb"} (lang/query-params "q=a%zzb")))
    (is (= {:q "café"} (lang/query-params "q=caf%C3%A9"))
        "and the portable decoder still does real UTF-8")
    (is (= {} (lang/query-params "=y"))
        "a pair with no KEY is still dropped — there is nothing to be present under")))

(deftest a-context-cannot-promise-reads-it-cannot-perform
  ;; Reads resolve by VOCABULARY store-wide, so an endpoint in one namespace
  ;; can and should reuse a performer declared in another. Good property —
  ;; but it means a context assembled from HALF the namespaces answers 500,
  ;; not 404, at request time, with the detail server-side and a generic
  ;; error in the body. That is the worst of both: the failure with no check
  ;; is also the failure that is hardest to read.
  ;;
  ;; Every input needed is already in hand at assembly. So assemble-time is
  ;; where it is caught.
  (testing "a route declaring a read nobody performs is refused at assembly"
    (let [e (try (slopp.http/context {:http/namespaces ['slopp.http-test]
                              :http/routes [{:method :get :path "/orphan"
                                            :handler identity
                                            :auth :public
                                            :http/reads {:x [:nobody/serves-this []]}}]})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "assembling this context has to fail, not defer to a 500")
      (is (re-find #"nobody/serves-this" (ex-message e))
          (str "the message has to name the unservable KIND: " (ex-message e)))
      (is (re-find #"/orphan" (ex-message e))
          (str "and the route that declared it: " (ex-message e)))))
  (testing "a context that can perform every read it declares assembles"
    ;; the guard must not fire on the ordinary case, including a route with
    ;; no declared reads at all
    (is (map? (slopp.http/context {:http/namespaces ['slopp.http-test]
                                   :webapp/bundle "/js/main.js"
                                   :http/wrap-context honouring})))))

(defn reader-contract
  "Every property a `mount-routes` reader must satisfy, run against whatever
  `make-reader` builds — `{path → content}` in, a reader out.

  The suite may name NOTHING but the port. That is not style: an assertion
  reaching into one adapter would stop running against the other, so the
  constraint is what keeps this from silently becoming one implementation's
  test."
  [label make-reader]
  (let [rdr (make-reader {"public/app.css" "body{}"})]
    (testing (str label ": a path the source holds answers with content")
      (is (some? (:content (rdr "public/app.css")))))
    (testing (str label ": a path nothing holds is nil — MISSING, not a throw")
      (is (nil? (rdr "public/nope.css"))))
    (testing (str label ": a traversal segment is nil, however it is refused")
      ;; the filesystem reader refuses it explicitly; the store-backed one
      ;; simply has no such manifest key. Same answer, different reason —
      ;; which is exactly what a contract is allowed to be indifferent to.
      (is (nil? (rdr "public/../public/app.css"))))
    (testing (str label ": a PREFIX of a real path is not a partial hit")
      (is (nil? (rdr "public/app"))))))

(deftest ^:external the-filesystem-reader-meets-the-reader-contract
  ;; The other side of reader-contract. ^:external because this adapter's whole
  ;; job is the filesystem — the store-backed run of the SAME suite is in-image
  ;; and costs nothing, which is the two-tier split doing what it is for.
  (reader-contract "filesystem"
                   (fn [files]
                     (let [dir (str (java.nio.file.Files/createTempDirectory
                                     "slopp-contract"
                                     (make-array java.nio.file.attribute.FileAttribute 0)))]
                       (doseq [[path content] files]
                         (let [f (java.io.File. dir (str path))]
                           (.mkdirs (.getParentFile f))
                           (spit f content)))
                       (static/file-or-resource-reader dir)))))

(deftest bind-diagnosis-is-the-one-recognizer-for-a-taken-port
  ;; Three listeners answered "the port is taken" three different ways, and
  ;; the plan's founding symptom was that they disagreed. Measured before
  ;; this: `http-api.server/serve!` walked the cause chain and said "port N
  ;; is not available"; `api.devserver/bind-failure` regexed a wire string
  ;; and said "port N is already in use"; `slopp.http/serve!` said nothing at
  ;; all and let a BindException reach the operator.
  ;;
  ;; They differ for ONE honest reason — they hold different things. The
  ;; in-process caller has a Throwable; the dev server has a text blob that
  ;; crossed an nREPL wire. So the recognizer takes either, and the sentence
  ;; is written once. What each caller adds is its own NEXT STEP, which is
  ;; the part that legitimately differs: only the dev server knows the
  ;; failure is fixable with `http.port`.
  (testing "a Throwable carrying a BindException anywhere in its cause chain"
    (is (= "port 8080 is already in use"
           (slopp.http/bind-diagnosis 8080 (java.net.BindException. "Address already in use"))))
    (is (= "port 8080 is already in use"
           (slopp.http/bind-diagnosis 8080 (ex-info "wrapped" {} (java.net.BindException. "nope"))))
        "the cause chain is walked — http-kit wraps"))
  (testing "a text blob that crossed a wire, where the class is gone"
    (is (= "port 7357 is already in use"
           (slopp.http/bind-diagnosis
            7357
            (str "class java.net.BindException: Execution error (BindException) at"
                 " sun.nio.ch.Net/bind0 (Net.java:-2).\nAddress already in use")))))
  (testing "nil for anything it does not recognize — the caller keeps every byte"
    (is (nil? (slopp.http/bind-diagnosis 8080 (java.net.UnknownHostException. "nowhere"))))
    (is (nil? (slopp.http/bind-diagnosis 8080 "Syntax error compiling at (app/core.clj:1:1)")))
    (is (nil? (slopp.http/bind-diagnosis 8080 nil)))))

(deftest ^:external serve-on-a-taken-port-leads-with-the-diagnosis
  ;; The production half of the same rule the dev server already follows. An
  ;; operator starting a built app on a held port got
  ;; `class java.net.BindException: Execution error (BindException) at
  ;; sun.nio.ch.Net/bind0 (Net.java:-2).` and then, after a newline, the one
  ;; clause that matters. Three pieces of noise before the answer.
  ;;
  ;; A clash is an ERROR here and stays one — never a hunt for a free port.
  ;; The url an operator was handed must not quietly stop being the url that
  ;; works, which is the same stance api.server/serve! takes.
  (let [held (slopp.http/serve! {:http/namespaces [] :http/port 0})
        port (:port held)]
    (try
      (let [t (try (slopp.http/serve! {:http/namespaces [] :http/port port})
                   nil
                   (catch Throwable t t))]
        (testing "it still fails — a taken port is never routed around"
          (is (some? t) "binding a held port must not succeed"))
        (testing "the diagnosis leads"
          (is (str/starts-with? (str (ex-message t))
                                (str "port " port " is already in use"))))
        (testing "and the raw failure survives behind it, not squeezed out"
          (is (re-find #"(?i)address already in use" (str (ex-message t)))))
        (testing "the port rides as data, so a caller need not re-parse the sentence"
          (is (= port (:http/port (ex-data t))))))
      (finally (slopp.http/stop! held)))))

(deftest a-context-can-be-WRAPPED-before-it-is-served
  ;; slopp GENERATES the serve! call for a managed app — `webdev.live/serve-code`
  ;; writes it, because a hand-written one could disagree with the plan and the
  ;; running server would be the half that disagreed. So a capability needing to
  ;; add something to the assembled context has no call site of its own to add
  ;; it at.
  ;;
  ;; This is that seam, and it is deliberately GENERIC: a function applied to
  ;; the context between assembly and serving. `slopp.rest/validating` is its
  ;; first user, and `slopp.http` does not learn that rest exists — which is the
  ;; whole reason malli is not in this framework.
  (let [seen (atom nil)
        ;; honours on the way through as well as probing: what it wraps here is a
        ;; namespace of typed endpoints, and a wrapper is exactly the seam that
        ;; is supposed to carry that
        wrap (fn [ctx] (reset! seen ctx) (honouring (assoc ctx :probe/wrapped true)))]
    (testing "the wrapper receives the ASSEMBLED context, not the opts"
      ;; it has to run after `context` has derived the routes and the performer
      ;; vocabularies, or a wrapper deciding anything from the surface would be
      ;; deciding it from a map that does not have one yet
      (let [srv (slopp.http/serve! {:http/namespaces ['slopp.http-test]
                                   :http/port 0
                                   ;; this namespace declares a shell, and a
                                   ;; shell with no bundle refuses at assembly
                                   :webapp/bundle "/js/main.js"
                                   :http/wrap-context wrap})]
        (try
          (is (some? (:http/routes @seen)) (pr-str (keys @seen)))
          (is (contains? @seen :http/read-performers))
          (finally (slopp.http/stop! srv)))))

    (testing "and no wrapper leaves serving exactly as it was"
      ;; every app enabling no such capability is this case, and it must cost
      ;; nothing. Served over NO namespaces on purpose: every fixture route in
      ;; this one is a typed endpoint, and a context over those may not serve
      ;; unwrapped any more — so reusing them here would have exercised the
      ;; refusal while claiming to show its absence.
      (let [srv (slopp.http/serve! {:http/namespaces [] :http/port 0})]
        (try (is (map? srv))
             (finally (slopp.http/stop! srv)))))))

(deftest a-served-app-becomes-a-DRIVER-the-fake-browser-can-open
  ;; D-cljnx, wave 1. `slopp.cljnx/open!` branches on `:http/routes` and
  ;; performs the request itself, which is the http ADAPTER inlined into the
  ;; fake browser — the reason `screen` sits inside http's namespace family and
  ;; cannot reach `slopp.webapp` (vendoring is per family, so an http-only
  ;; store has no webapp source at all).
  ;;
  ;; Named here instead, beside `handle!`, as the mirror of `webapp/driver`:
  ;; ONE neutral contract, two producers, each owned by the capability whose
  ;; code it needs.
  (let [ctx    (slopp.http/context
                {:http/routes [{:method  :get :path "/hi" :auth :public
                               :handler (fn [_req]
                                          {:status 200 :body [:main [:h1 "hello"]]})}]})
        driver (slopp.http/driver ctx)]

    (testing "the driver answers a PATH with the whole RESPONSE"
      ;; it used to answer `(:body resp)` and render anything non-hiccup as its
      ;; status. That threw away the two facts the caller needed: the STATUS,
      ;; which left `(= 404 …)` as a whole-page string search over a rendered
      ;; sentence, and `Location`, so a redirect was never followed and
      ;; post-redirect-get did not work at all. Rendering is the reader's half
      ;; and following is the browser's; producing this is the only part that
      ;; is http's
      (is (fn? (:document driver))
          "no :document — nothing would render a served page headlessly")
      (is (= {:status 200 :body [:main [:h1 "hello"]]}
             ((:document driver) "/hi"))))

    (testing "and it is a real request down the real pipeline, so a path
              nothing serves comes back as the 404 it is"
      ;; the property `open!`'s ctx branch had, and the reason it drove
      ;; `dispatch/handle!` rather than calling a handler
      (let [doc ((:document driver) "/nope")]
        (is (= 404 (:status doc)) (pr-str doc))))

    (testing "a redirect reaches the browser INTACT, headers and all"
      ;; the fact this change exists for: `slopp.cljnx/visit!` follows it, and
      ;; it cannot follow what the adapter dropped
      (let [c3 (slopp.http/context
                {:http/routes [{:method :get :path "/old" :auth :public
                               :handler (fn [_] {:status 302
                                                 :headers {"Location" "/new"}})}]})]
        (is (= {:status 302 :headers {"Location" "/new"}}
               ((:document (slopp.http/driver c3)) "/old")))))

    (testing "the query string is SPLIT the way a browser sends it"
      ;; `:uri` never carries the `?`; measured as `/search?q=web` 404ing on a
      ;; mounted route, so every pagination link read as a broken route
      (let [seen (atom nil)
            c2   (slopp.http/context
                  {:http/routes [{:method :get :path "/s" :auth :public
                                 :handler (fn [req]
                                            (reset! seen (select-keys req [:uri :query-string]))
                                            {:status 200 :body [:p "ok"]})}]})]
        ((:document (slopp.http/driver c2)) "/s?q=web")
        (is (= {:uri "/s" :query-string "q=web"} @seen))))))

(deftest an-UNASSEMBLED-context-says-so-instead-of-404ing-everything
  ;; Reported by slopp-ui, 2026-08-23, after three attempts: they built
  ;; {:http/namespaces … :http/routes …} — the shape `serve!` documents — and
  ;; handed it straight to `slopp.http/driver`. Every path answered
  ;; {:status 404 :body {:error "no route"}}, which is a CORRECT answer to a
  ;; question nobody asked: an unassembled ctx has no derived router, so every
  ;; path genuinely misses.
  ;;
  ;; The cost is that it implicates the caller's ROUTES. They looked there
  ;; first, and only found it via a sentence in this namespace's docstring
  ;; while reading it for something else.
  ;;
  ;; The tell is exact rather than a guess, which is what makes it a rule worth
  ;; having: `context` does not carry :http/namespaces into what it returns, so
  ;; a ctx holding one is an INPUT map that never went through it. Nothing
  ;; legitimate looks like that.
  (testing "the input map to context is refused, naming the call that fixes it"
    (let [m (try (slopp.http/handle! {:http/namespaces ['slopp.http-test]}
                                     {:request-method :get :uri "/anything"})
                 nil
                 (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (is (some? m) "a 404 here sends the reader to their own route table")
      (is (str/includes? m "slopp.http/context") m)
      (is (str/includes? m ":http/namespaces") m)))

  (testing "and through the driver, which is how it was actually hit"
    (let [m (try ((:document (slopp.http/driver {:http/namespaces ['slopp.http-test]})) "/")
                 nil
                 (catch clojure.lang.ExceptionInfo e (ex-message e)))]
      (is (some? m))
      (is (str/includes? m "slopp.http/context") m)))

  (testing "an ASSEMBLED context is untouched, and so is a bare route table"
    ;; a hand-built {:http/routes [...]} is a legitimate minimal ctx — the
    ;; fixtures in this repo use it — so the refusal keys on the input marker
    ;; and not on the absence of assembly
    (let [ctx (slopp.http/context
               {:http/namespaces []
                :http/routes [{:method :get :path "/hi" :auth :public
                               :handler (fn [_] {:status 200 :body [:p "hi"]})}]})]
      (is (= 200 (:status (slopp.http/handle! ctx {:request-method :get :uri "/hi"}))))
      (is (= 200 (:status (slopp.http/handle!
                           {:http/routes [{:method :get :path "/hi" :auth :public
                                           :handler (fn [_] {:status 200 :body [:p "hi"]})}]}
                           {:request-method :get :uri "/hi"})))))))

(deftest a-SHELL-declares-that-it-IS-one-and-the-framework-supplies-the-bundle
  ;; `:webapp/shell` used to hold the bundle URL, so every shell route repeated
  ;; a fact about the BUILD — where the compiled JavaScript is served — in a
  ;; declaration about a PAGE. Two shells meant two copies, and a store that
  ;; moved its static mount had to find them.
  ;;
  ;; The page's own statement is "I am the shell". Which bundle is the app's,
  ;; declared once where it is assembled, beside the mount point — the other
  ;; deployment fact a document has no business knowing.
  (let [shell (with-meta 'shell {:http/method :get :http/path "/x/**"
                                 :http/auth :public :webapp/shell true})]
    (is (true? (:webapp/shell (meta shell)))
        "the marker is a boolean — a route says it is a shell, not where a build put its output")

    (testing "the framework injects the bundle it was given"
      (let [ctx  (slopp.http/context {:http/namespaces ['slopp.http-test]
                                      :http/wrap-context honouring
                                      :webapp/base   "/p/demo"
                                      :webapp/bundle "/js/main.js"})
            body (str (:body (slopp.http/handle! ctx {:request-method :get :uri "/"})))]
        (is (str/includes? body "src=\"/js/main.js\"") body)))

    (testing "and a shell with NO bundle configured refuses at ASSEMBLY"
      ;; the same place a broken shell already refuses, and for the same
      ;; reason: an app that comes up serving a document with no script is one
      ;; whose every page is blank, and the first reader is a worse place to
      ;; find that out than the boot
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?i)bundle"
           (slopp.http/context {:http/namespaces ['slopp.http-test]
                                :http/wrap-context honouring
                                :webapp/base "/p/demo"}))))))
