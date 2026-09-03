(ns slopp.webdev.live-test
  "Tests for the framework-managed app server.

  What lives here is the DECIDING — is this a web project, what does it
  serve, on what address — which `serve-plan` answers as pure data from the
  store, so it needs no process. The launching, the done-grain refresh and
  the blue/green swap need a real image and are `^:external`."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.webdev.live :as live] [clojure.edn :as edn] [clojure.string :as str] [slopp.http.client :as http.client] [slopp.http :as slopp.http] [clojure.set :as set] [slopp.store.artifacts :as artifacts] [slopp.ops.external :as external] [slopp.ops :as ops] [clojure.java.io :as io]))

(deftest a-serve-plan-is-derived-from-the-store
  (let [src (str "(ns shop.api)\n\n"
                 "(defn ^{:http/method :get :http/path \"/api/users\"\n"
                 "        :http/auth :authenticated\n"
                 "        :malli/schema [:=> [:cat :map] :map]\n"
                 "        :rest/response :map} users \"U.\" [req] req)\n")
        off (store/ingest (store/empty-store) 'shop.api src)
        on  (first (store/record-config-put off "capabilities" :manifest
                                            "http.enabled" "true"))
        put (fn [s k v] (first (store/record-config-put s "capabilities"
                                                        :manifest k v)))]
    (testing "http.enabled is the opt-in, and refusing says how to opt in"
      (let [p (live/serve-plan off "/tmp/shop")]
        (is (false? (:enabled? p)))
        (is (re-find #"http\.enabled" (:reason p)))
        (is (nil? (:port p)) "nothing is bound for a store that never opted in")))
    (testing "what to serve comes from the store, never from the caller"
      (is (= ['shop.api] (:namespaces (live/serve-plan on "/tmp/shop")))))
    (testing "host and adapter are the declared capabilities"
      (let [p (live/serve-plan on "/tmp/shop")]
        (is (= "127.0.0.1" (:host p)))
        (is (= :http-kit (:adapter p)))))
    (testing "an explicitly set http.port WINS — a pinned address stays pinned"
      (is (= 9999 (:port (live/serve-plan (put on "http.port" "9999")
                                               "/tmp/shop")))))
    (testing "unset, the port DERIVES from the store dir"
      ;; http.port's registry DEFAULT is 8080, and a fixed default is exactly
      ;; what api.server/derived-port exists to refuse: it "worked for
      ;; exactly one project and collided for the second". Production wants a
      ;; known number, so the default still stands there — but two dev
      ;; sessions on one machine must not fight, so an UNSET port derives.
      (let [a (:port (live/serve-plan on "/tmp/shop"))
            b (:port (live/serve-plan on "/tmp/other"))]
        (is (not= 8080 a) "the fixed default is not what a dev session binds")
        (is (not= a b) "two projects on one machine derive different ports")
        (is (= a (:port (live/serve-plan on "/tmp/shop")))
            "stable across restarts — the url a human bookmarked keeps working")
        (is (< 1024 a 65536))))
    (testing "the client route table rides the plan, from the page markers"
      (let [ui (store/ingest on 'shop.ui
                             "(ns shop.ui)\n(defn ^{:webapp/path \"/app\"} home \"H.\" [_] {:page :home})\n")]
        (is (= [["/app" 'shop.ui/home]] (:page-routes (live/serve-plan ui "/tmp/shop"))))
        (is (= [] (:page-routes (live/serve-plan on "/tmp/shop")))
            "a store with no pages carries an empty table, and serve-code then omits :webapp/routes — the 200 compatibility answer")))
    (testing "the plan says it is dev, so nothing reads it as the shipped one"
      ;; the dev server and the built app answer the same routes from
      ;; different stores at different grains — a plan that does not say
      ;; which it is becomes a proxy for the other
      (is (= :dev (:mode (live/serve-plan on "/tmp/shop"))))) ))

(deftest the-app-image-loads-the-web-surface-and-what-it-reaches
  (let [s (-> (store/empty-store)
              (store/ingest 'shop.db "(ns shop.db)\n(defn fetch \"F.\" [id] id)\n")
              (store/ingest 'shop.api
                            (str "(ns shop.api (:require [shop.db :as db]))\n\n"
                                 "(defn ^{:http/method :get :http/path \"/api/u/:id\"\n"
                                 "        :http/auth :authenticated\n"
                                 "        :http/reads {:u [:u/by-id [:path-params :id]]}\n"
                                 "        :malli/schema [:=> [:cat :map] :map]\n"
                                 "        :rest/response :map} u \"U.\" [req] (db/fetch req))\n"))
              (store/ingest 'shop.data
                            (str "(ns shop.data)\n"
                                 "(defn ^{:http/read :u/by-id} by-id \"R.\" [ctx id] id)\n"))
              ;; nothing in the web surface reaches this
              (store/ingest 'shop.tools "(ns shop.tools)\n(defn cli \"C.\" [x] x)\n")
              (#(first (store/record-config-put % "capabilities" :manifest
                                                "http.enabled" "true"))))
        order (live/load-order s)]
    (testing "the web surface and everything it transitively requires"
      (is (= #{'shop.api 'shop.db 'shop.data} (set order))))
    (testing "a namespace the surface cannot reach is not loaded into the app"
      ;; the app image exists to run the APP; loading the whole store would
      ;; make its boot cost grow with the codebase and put code in a serving
      ;; process that nothing serving can call
      (is (not (some #{'shop.tools} order))))
    (testing "dependencies first — the child has no classpath to fall back on"
      (is (< (.indexOf ^java.util.List order 'shop.db)
             (.indexOf ^java.util.List order 'shop.api))))
    (testing "the framework loads from the store when the store is where it lives"
      ;; slopp's own store HOLDS slopp.http; an ordinary app gets it from the
      ;; declared slopp-web coord, already on the child's classpath. Neither
      ;; case may require the app to say which.
      (let [with-fw (store/ingest s 'slopp.http "(ns slopp.http)\n(defn serve! \"S.\" [o] o)\n")]
        (is (some #{'slopp.http} (live/load-order with-fw)))))
    (testing "and its absence from the store is not an error"
      (is (not (some #{'slopp.http} order))))
    (testing "a store with no web surface loads nothing"
      (is (= [] (live/load-order (store/empty-store)))))))

(deftest the-serve-call-is-built-from-the-plan-not-written-by-the-app
  ;; The whole directive is that an app holds no `serve!` call. So the call
  ;; has to be constructed, and constructing it is pure — which keeps the
  ;; only interesting decisions (what crosses, what comes back) answerable
  ;; without a JVM.
  ;;
  ;; The first cut generated four of serve!'s options — namespaces, host,
  ;; port, adapter, all of which say WHERE to serve — and dropped every one
  ;; that says what the app NEEDS. Measured on a real app: handlers taking
  ;; `:http/deps` got nil, which either 500s or, worse, answers 200 with an
  ;; empty body that a client generator reads as success.
  (let [plan {:enabled? true :mode :dev
              :namespaces ['shop.api 'shop.data]
              :host "127.0.0.1" :port 51234 :adapter :jdk
              :max-body-bytes 2048
              :context-builder 'shop.system/deps}
        form (edn/read-string (live/serve-code plan))
        read (nth form 3)                       ; (:port (slopp.http/serve! …))
        call (second read)]
    (testing "the app's CONTEXT is built by the declared builder and passed in"
      ;; not quoted — this one is a CALL, which is why the opts can no longer
      ;; be one flat quoted map
      (is (= '(shop.system/deps) (:http/perform-ctx (last call))) (pr-str call)))
    (testing "and its namespace is required, since nothing else need reach it"
      ;; the builder lives wherever the app's system does — it is not part of
      ;; the served surface and so is not in :http/namespaces
      (is (some #{'(require (quote shop.system))} form) (pr-str form)))
    (testing "the body cap rides too — it has a capability, and the generated
              call ignoring it made that capability describe nothing"
      (is (= 2048 (:http/max-body-bytes (last call)))))
    (testing "the address fields still cross, quoted, because a namespace
              symbol in evaluated position is read as a CLASS name"
      ;; not hypothetical: unquoted, `demo.app` came back from a real app
      ;; image as `Syntax error (ClassNotFoundException) … demo.app`
      (let [opts (last call)]
        (is (= '(quote [shop.api shop.data]) (:http/namespaces opts)))
        (is (= "127.0.0.1" (:http/host opts)))
        (is (= 51234 (:http/port opts)))
        (is (= :jdk (:http/adapter opts)))))
    (testing "an app that declares NO builder passes no context, rather than
              an empty map that would read as one"
      (let [none (edn/read-string (live/serve-code (dissoc plan :context-builder)))]
        (is (not (contains? (last (second (nth none 2))) :http/perform-ctx)))))
    (testing "and it still evaluates to the BOUND port — an integer, so a
              throw (which comes back as a string) cannot read as success"
      (is (= :port (first read))))))

(def fake-web-src
  "A stand-in `slopp.http` for the app-image test, as store source.

  It is FAKED for the same reason `api-test/a-built-web-app-RUNS-outside-slopp-entirely`
  fakes it: this suite runs from a checkout where `boot/framework-files` is
  nil, so vendoring supplies nothing, and a test that branched on that would
  assert shape in the only environment it ever executes in.

  The stand-in carries the properties under test — it binds a real socket on
  the address the plan derived, answers over HTTP, reports the BOUND port,
  and reaches the app's own code through `resolve` (so a page can only be
  served if the store's namespaces really loaded into that image). Routing
  is `slopp.http`'s job and is tested in `slopp.http-test`; nothing here
  stands in for it. Zero deps on purpose: the child image carries only the
  store's own manifest, which for this fixture is empty.

  It echoes `:http/routes` for the same reason it echoes `:http/perform-ctx`:
  the managed server's failures have all been options that never crossed,
  and an option is only observably carried if something on the far side can
  be asked about it."
  (str "(ns slopp.http\n"
       ;; the real framework ships these as one vendored family on the
       ;; child's classpath; here they are store namespaces, so the sibling
       ;; is only loaded if something makes it REACHABLE
       "  (:require [slopp.http.static])\n"
       "  (:import [com.sun.net.httpserver HttpServer HttpHandler]\n"
       "           [java.net InetSocketAddress]))\n"
       "\n"
       "(defn serve! \"Bind and answer.\" [opts]\n"
       "  (let [srv (HttpServer/create\n"
       "             (InetSocketAddress. ^String (:http/host opts)\n"
       "                                 (int (:http/port opts))) 0)]\n"
       "    (.createContext srv \"/\"\n"
       "      (reify HttpHandler\n"
       "        (handle [_ x]\n"
       "          (let [b (.getBytes\n"
       "                   (str \"ns=\" (pr-str (:http/namespaces opts))\n"
       "                        \" ctx=\" (pr-str (:http/perform-ctx opts))\n"
       "                        \" cap=\" (pr-str (:http/max-body-bytes opts))\n"
       "                        \" routes=\" (pr-str (:http/routes opts))\n"
       "                        \" app=\" (when-let [v (resolve 'demo.app/greeting)] (v))))]\n"
       "            (.sendResponseHeaders x 200 (long (alength b)))\n"
       "            (with-open [o (.getResponseBody x)] (.write o b))))))\n"
       "    (.start srv)\n"
       "    {:port (.getPort (.getAddress srv))}))\n"))

(def fake-static-src
  "A stand-in `slopp.http.static` for the app-image test, as store source.

  Faked for the same reason as [[fake-web-src]] — nothing is vendored in a
  checkout — but this one has to do REAL WORK to be worth anything. The
  wiring under test is store bytes → a temp dir the parent writes → a reader
  in a child JVM that has no store, and only a reader that actually opens
  the file proves the chain. So `file-or-resource-reader` here slurps from
  `root` rather than returning a canned value, and `mount-routes` puts what
  it read into the row.

  The real pair is tested in `web-test/static-mounts-serve-raw-bytes`;
  nothing here stands in for routing or for content-type resolution."
  (str "(ns slopp.http.static)\n"
       "\n"
       "(defn file-or-resource-reader \"R.\" [root]\n"
       "  (fn [path]\n"
       "    (let [f (java.io.File. (str root) (str path))]\n"
       "      (when (.isFile f) {:content (slurp f)}))))\n"
       "\n"
       "(defn mount-routes \"M.\" [mounts reader]\n"
       "  (vec (for [[url prefix] mounts]\n"
       "         {:mounted url\n"
       "          :read (:content (reader (str prefix \"/app.css\")))})))\n"))

(deftest ^:external the-app-server-comes-up-and-answers-without-the-app-asking
  ;; The directive this whole namespace exists for: "when we have a web slopp
  ;; project under development, there should always be a live/up-to-date
  ;; version of the server up and going." The store below holds no `serve!`
  ;; call, no namespace list and no port — everything the launch needs is
  ;; derived from what is already there.
  ;;
  ;; Driven through `client/request` rather than a raw slurp. `:direct-http`
  ;; asks for exactly that, and unlike `slopp.http-test`'s round-trips this
  ;; test has no reason to want an INDEPENDENT client: slopp.http.client is
  ;; not what is under test here, so using it is not circular.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-app"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        s    (-> (store/empty-store)
                 (store/ingest 'slopp.http fake-web-src)
                 (store/ingest 'slopp.http.static fake-static-src)
                 (store/ingest 'demo.app
                               (str "(ns demo.app)\n\n"
                                    "(defn greeting \"G.\" [] \"hello from the store\")\n\n"
                                    "(defn ^{:http/context true} deps \"D.\"\n"
                                    "  [] {:built-by :the-app})\n\n"
                                    "(defn ^{:http/method :get :http/path \"/hi\"\n"
                                    "        :malli/schema [:=> [:cat :map] :map]\n"
                                    "        :rest/response :map} hi \"H.\" [req] {:ok true})\n"))
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.enabled" "true"))))
        sess (atom {})
        r    (live/start! sess s dir)]
    (try
      (testing "it is up, and the app said nothing to make that happen"
        (is (:serving? r) (str "start! did not serve: " (:reason r))))
      (testing "at the address the plan derived — one answer, not two"
        ;; two derivations of "where does this serve" can disagree, and the
        ;; failure is a url that is reported and a port that is bound
        (is (= (:port (live/serve-plan s dir)) (:port r)))
        (is (= (str "http://127.0.0.1:" (:port r) "/") (:url r))))
      (let [body (:http/body (http.client/request {:http/url (:url r)
                                              :http/timeout-ms 5000}))]
        (testing "the DERIVED namespace list is what crossed into the image"
          (is (str/includes? body "demo.app")))
        (testing "and the store's own code is LOADED there — the page is
                  served by the app, not by something that merely booted"
          ;; this is what makes it an app server rather than a socket: the
          ;; handler reaches demo.app/greeting, which exists only in the
          ;; store and has no classpath to fall back on
          (is (str/includes? body "hello from the store")))
        (testing "and the app's declared CONTEXT was BUILT and passed in —
                  the failure that made a managed server useless to the one
                  app that measured it"
          ;; handlers receive this as :http/deps and performers as their first
          ;; argument. nil either 500s or, worse, answers 200 with an empty
          ;; body a client generator reads as success.
          (is (str/includes? body ":built-by :the-app") body))
        (testing "and the body cap, which had a capability describing nothing
                  while the generated call ignored it"
          (is (str/includes? body "cap=1048576") body)))
      (finally (live/stop! r)))
    (testing "and stop! takes the whole thing down, because the image IS the
              server — there is no half-stopped state to leak a port"
      (is (= :unreachable
             (:http/error
              (ex-data (try (http.client/request {:http/url (:url r)
                                             :http/timeout-ms 5000})
                            (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest ^:external a-refresh-swaps-the-app-and-a-red-store-does-not-take-it-down
  ;; "Always up" and "up to date" conflict exactly when a boot fails, and at
  ;; done grain a failing boot is not exotic — mid-episode the store is
  ;; intentionally incomplete, and red-first IS the normal state. So the new
  ;; version is proved to LOAD before the old one is killed.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-refresh"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        base (-> (store/empty-store)
                 (store/ingest 'slopp.http fake-web-src)
                 (store/ingest 'slopp.http.static fake-static-src)
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.enabled" "true"))))
        app  (fn [greeting]
               (store/ingest base 'demo.app
                             (str "(ns demo.app)\n\n"
                                  "(defn greeting \"G.\" [] \"" greeting "\")\n\n"
                                  "(defn ^{:http/method :get :http/path \"/hi\"\n"
                                  "        :malli/schema [:=> [:cat :map] :map]\n"
                                  "        :rest/response :map} hi \"H.\" [req] {:ok true})\n")))
        sess (atom {})
        body (fn [r] (:http/body (http.client/request {:http/url (:url r)
                                                  :http/timeout-ms 5000})))]
    (try
      (let [v1 (live/refresh! sess (app "version one") dir)]
        (testing "the first refresh is just a start"
          (is (:serving? v1) (str "refresh! did not serve: " (:reason v1)))
          (is (str/includes? (body v1) "version one")))
        (testing "and it is held on the session, so the next refresh knows
                  what it is replacing"
          (is (= v1 (:app-server @sess))))
        (let [v2 (live/refresh! sess (app "version two") dir)]
          (testing "a second refresh serves the CURRENT store"
            (is (:serving? v2) (str "refresh! did not re-serve: " (:reason v2)))
            (is (str/includes? (body v2) "version two")))
          (testing "on the same url — a stable address is the whole reason to
                    derive a port rather than take a free one"
            (is (= (:url v1) (:url v2))))
          (let [red (store/ingest (app "version three") 'demo.broken
                                  (str "(ns demo.broken (:require [demo.app :as a]))\n\n"
                                       "(defn ^{:http/method :get :http/path \"/b\"\n"
                                       "        :malli/schema [:=> [:cat :map] :map]\n"
                                       "        :rest/response :map} b \"B.\"\n"
                                       "  [req] (a/nope-not-a-thing))\n"))
                v3  (live/refresh! sess red dir)]
            (testing "a store that will not load does NOT come up"
              (is (not (:serving? v3)))
              (is (str/includes? (str (:reason v3)) "demo.broken")))
            (testing "and the PREVIOUS version is still answering — the app is
                      not down because someone was mid-thought"
              (is (str/includes? (body v2) "version two"))
              (is (= v2 (:app-server @sess)))))))
      (finally (live/stop! (:app-server @sess))))))

(deftest whether-slopp-manages-a-dev-server-is-its-own-question
  ;; http.enabled means "this project serves HTTP". It does NOT mean "slopp
  ;; should run that server for you", and the two came apart on the first
  ;; store we looked at — slopp's own. Its web surface IS the reviewer API,
  ;; which the live session already serves over the LIVE store; a managed
  ;; server there would boot a second image and serve a snapshot of the page
  ;; you are looking at, one done point behind it.
  ;;
  ;; That used to be the `dev.server` capability. It is now DERIVED, because
  ;; the exemption is a fact about the running process and never a project's
  ;; preference — see `the-only-store-that-should-not-be-managed-is-derivable`
  ;; for why a knob only one store should touch is a footgun for everyone.
  ;;
  ;; Deliberately NOT folded into serve-plan: that answers "what would this
  ;; store serve", which production will need too, and a dev-only exemption
  ;; does not belong in it.
  (let [web (-> (store/empty-store)
                (store/ingest 'app.api
                              (str "(ns app.api)\n\n"
                                   "(defn ^{:http/method :get :http/path \"/hi\"\n"
                                   "        :malli/schema [:=> [:cat :map] :map]\n"
                                   "        :rest/response :map} hi \"H.\" [req] {:ok true})\n"))
                (#(first (store/record-config-put % "capabilities" :manifest
                                                  "http.enabled" "true"))))]
    (testing "a web project is managed, and the app does not have to ask —
              nothing it can configure turns this off any more"
      (is (live/managed? web #{'something.else})))
    (testing "a store this process already serves is exempt, and stays a web
              project while it is"
      (is (not (live/managed? web #{'app.api})))
      (is (:enabled? (live/serve-plan web "/tmp/x")))
      (testing "and the plan still says where it WOULD serve, because that is
                what production asks"
        (is (pos? (:port (live/serve-plan web "/tmp/x"))))))
    (testing "a store that serves no HTTP at all is not managed either"
      (is (not (live/managed? (store/empty-store) #{}))))))

(deftest ^:external a-refresh-reports-what-it-cost
  ;; slopp-ui asked "measure app-image boot cost, and let the number pick the
  ;; project" — ~2s means state is the only argument for hot-loading a refresh
  ;; into the RUNNING image, ~15s means it pays on latency alone. But the
  ;; number is a property of the APP: it loads that store's namespaces with
  ;; that store's deps. Measuring slopp's own once answers for slopp once, so
  ;; the boot reports its own cost instead and every app reads its own.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-app"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        s    (-> (store/empty-store)
                 (store/ingest 'slopp.http fake-web-src)
                 (store/ingest 'slopp.http.static fake-static-src)
                 (store/ingest 'demo.app
                               (str "(ns demo.app)\n\n"
                                    "(defn ^{:http/method :get :http/path \"/hi\"\n"
                                    "        :malli/schema [:=> [:cat :map] :map]\n"
                                    "        :rest/response :map} hi \"H.\" [req] {:ok true})\n"))
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.enabled" "true"))))
        sess (atom {})
        r    (live/refresh! sess s dir)]
    (try
      (is (:serving? r) (str "refresh! did not serve: " (:reason r)))
      (testing "the running map carries how long the image took to come up"
        (is (integer? (:boot-ms r)) r)
        (is (pos? (:boot-ms r)) "a JVM launch plus a namespace load is never free"))
      (testing "and it is the BOOT, not the whole refresh — the number that a
                hot-load would remove, without the bind it would not"
        ;; if this ever measured the bind too, a slow port would read as a
        ;; slow image and the comparison hot-load exists to inform is wrong
        (is (< (:boot-ms r) 120000) r))
      (testing "the running map records WHICH store version it is serving"
        ;; without this nothing downstream can answer "is what I am looking
        ;; at current?" — the whole of slopp-ui's friction #5. How far behind
        ;; it is (`slopp.ops/app-behind`) is a journal read, covered where a
        ;; journal exists: ops-test and the full_check test beside this one.
        (is (integer? (:served-at r)) r)
        (is (= (:head-at s) (:served-at r))
            "served at the head's time — the stamp is the store's, not the clock's"))
      (finally (live/stop! r)))))

(deftest ^{:correspondence "the options webdev.live/serve-code GENERATES vs the */keys arglists of web/serve! + web/context (the destructuring IS the implementation, and the malli schemas drift from it) — plus the only exemption cross-check in the store: nothing may be both generated and declared-dropped"}
  the-generated-serve-call-accounts-for-every-option-it-could-carry
  ;; The generalisation of `catalog-covers-every-registered-rule`, which is
  ;; the one completeness test this codebase had and the only reason the new
  ;; write gate could not ship uncataloged.
  ;;
  ;; This is the instance that was MISSING: `serve-code` enumerated four of
  ;; the eight options by hand, and the four it dropped were every option
  ;; describing the APP rather than its address. Nothing compared the two, so
  ;; it took a real app measuring a live server to find it — and the loudest
  ;; symptom was the quiet one, `/api/contracts` answering 200 with an empty
  ;; document that `generate_client` reads as success.
  ;;
  ;; Derived from the ARGLISTS rather than from the malli schemas: the
  ;; destructuring IS the implementation, so it cannot drift from what the
  ;; functions actually read. The schemas can and do — `serve!`'s omits
  ;; :http/routes and :http/max-body-bytes, which `context` destructures.
  (let [;; EVERY `*/keys` entry, with its qualifier taken from the entry rather
        ;; than assumed. It read `:web/keys` and minted `(keyword "web" …)`,
        ;; which was true while one prefix owned the whole vocabulary — until
        ;; the marker wave moved options between prefixes and the assumed
        ;; qualifier silently dropped every option that had moved. The
        ;; vocabulary has since landed back under ONE prefix (`:http/*`), so
        ;; that bug is no longer reproducible from today's arglists — which is
        ;; the reason to say why this stays: it is not defending against a
        ;; mixture that exists now, it is defending against the next split,
        ;; and reading the entry means nothing here changes when one comes.
                opt-keys  (fn [v]
                    (let [m (->> (:arglists (meta v)) first first)]
                      (into
                       ;; the */keys families…
                       (set (for [[entry syms] m
                                  :when (and (keyword? entry)
                                             (= "keys" (name entry))
                                             (namespace entry))
                                  s syms]
                              (keyword (namespace entry) (name s))))
                       ;; …AND the rename entries (`client-routes :webapp/routes`):
                       ;; an option read under a local name is still an option the
                       ;; function reads, and this guard not seeing those is how
                       ;; the managed server shipped answering 200 to every
                       ;; address — :webapp/routes was destructured exactly there
                       (for [[sym k] m
                             :when (and (simple-symbol? sym)
                                        (keyword? k) (namespace k))]
                         k))))
        ;; serve! reads the address options and hands the whole map to
        ;; context, which reads the rest. Both, because either alone is half.
        accepted  (into (opt-keys #'slopp.http/serve!) (opt-keys #'slopp.http/context))
                ;; every option it COULD carry, so the plan has to exercise them all —
        ;; a plan missing :static made :http/routes look ungenerated and let the
        ;; stale "deliberately dropped" entry survive the change that generated it
        plan      {:namespaces ['demo.app] :host "127.0.0.1" :port 1234
                   :adapter :http-kit :max-body-bytes 42
                   :context-builder 'demo.sys/deps
                   :validate? true
                   :bundle "/assets/cljs/main.js"
                   :static {"/assets" "public"} :static-dir "/tmp/x"
                   :page-routes [["/app" 'demo.ui/home]]}
        generated (->> (edn/read-string {:default (fn [_ v] v)}
                                        (live/serve-code plan))
                       (tree-seq coll? seq)
                       (filter map?)
                       first keys set)
        dropped   (set (keys live/unserved-options))]
    (testing "every option is either generated or declared deliberately dropped"
      (is (= accepted (into generated dropped))
          (str "unaccounted for: " (set/difference accepted generated dropped))))
    (testing "and a dropped one says WHY, so the gap is a decision and not an omission"
      ;; the rule `crossings/internal-markers` already follows: a partial
      ;; classification is worse than none, because the one real hole drowns
      ;; in the entries nobody explained
      (is (every? #(and (string? %) (seq %)) (vals live/unserved-options))))
    (testing "nothing is BOTH generated and declared dropped"
      ;; The union check above cannot see this: a key in both sets still
      ;; satisfies it, so prose explaining why an option is missing survives
      ;; the write that stops it being missing. Same hand-kept-twin shape
      ;; this test exists to kill, one level up — the classification is
      ;; itself a list that has to track the code.
      (is (empty? (set/intersection generated dropped))
          (str "declared dropped but actually generated: "
               (set/intersection generated dropped))))))

(deftest a-managed-app-serves-the-static-assets-it-declares
  ;; The managed server answered 404 for every static mount, because mounts
  ;; read the store's file manifest and the child image has no store. The
  ;; project that hurt is the one whose whole purpose is to be LOOKED AT: a
  ;; UI's stylesheet and its cljs bundle are the product, so "managed" meant
  ;; an unstyled page with a dead bundle. That project switched dev.server
  ;; off, which reads as "the feature does not apply to me" and is really
  ;; "the feature is broken for me" — the switch hid the bug.
  ;;
  ;; The seam was already right: `mount-routes` takes a READER fn, not a
  ;; store, and `file-or-resource-reader` is the filesystem one. So the fix
  ;; is materialize-then-point, not a new mechanism.
  (let [plan {:namespaces ['demo.app] :host "127.0.0.1" :port 1234
              :adapter :http-kit
              :static {"/assets" "public"}
              :static-dir "/tmp/slopp-static-probe"}
        code (live/serve-code plan)]
    (testing "the generated call mounts the prefixes the store declared"
      (is (str/includes? code "mount-routes") "no mount call was generated")
      (is (str/includes? code "/assets"))
      (is (str/includes? code "public")))
    (testing "reading from the dir the parent materialized, since the child has no store"
      (is (str/includes? code "file-or-resource-reader"))
      (is (str/includes? code "/tmp/slopp-static-probe")))
    (testing "and the namespace providing both is REQUIRED in the child"
      ;; Asserting the substring "slopp.http.static" passes on the qualified
      ;; symbol alone, so the first version of this went green while the
      ;; generated code would still have thrown at runtime — the child had
      ;; the symbol and not the namespace. Match the require FORM.
      (is (str/includes? code "(require (quote slopp.http.static))")
          "the child resolves slopp.http.static/mount-routes only if it required it"))
    (testing "a plan with no mounts generates no routes key at all"
      (is (not (str/includes? (live/serve-code (dissoc plan :static :static-dir))
                              "mount-routes"))
          "an app without mounts must not pay for the machinery"))))

(deftest static-bytes-are-materialized-for-a-child-that-has-no-store
  ;; The whole reason mounts were unserved. The child image is a separate JVM
  ;; with no store, so the bytes have to be somewhere it can read them —
  ;; which `file-or-resource-reader` already does, given a dir.
  (let [put  (fn [s k v] (first (store/record-config-put s "capabilities" :manifest k v)))
        s    (-> (store/empty-store)
                 (put "http.enabled" "true")
                 (put "http.static./assets" "public"))
        s    (first (store/record-file-put s "public/app.css" "body{}"))
        s    (first (store/record-file-put s "public/deep/x.txt" "hi"))
        s    (first (store/record-file-put s "src/notes.md" "# not mounted"))
        ;; tracked files carry their content inline, so the blob fallback is
        ;; never consulted here — artifacts are the case that needs it
        dir  (live/materialize-static! s {"/assets" "public"} (constantly nil))]
    (testing "a mounted file lands under the dir at its manifest path"
      (is (= "body{}" (slurp (str dir "/public/app.css")))))
    (testing "nested paths keep their shape, so one mount serves a TREE"
      (is (= "hi" (slurp (str dir "/public/deep/x.txt")))))
    (testing "a file no mount covers is not copied — the dir is the mount, not the store"
      (is (not (.exists (java.io.File. (str dir "/src/notes.md"))))))
    (testing "no mounts means no dir, so an app without assets allocates nothing"
      (is (nil? (live/materialize-static! s {} (constantly nil)))))))

(deftest ^:external a-managed-server-carries-the-apps-assets-all-the-way-into-the-child
  ;; The wiring test. The unit tests prove `serve-code` generates a mount and
  ;; `materialize-static!` writes bytes; neither would have caught the actual
  ;; defect, which was that nothing connected them. The chain is: store
  ;; manifest → a temp dir the parent writes → a reader in a child JVM that
  ;; has no store — three processes' worth of assumptions and no single unit
  ;; that spans them.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-app-static"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        s    (-> (store/empty-store)
                 (store/ingest 'slopp.http fake-web-src)
                 (store/ingest 'slopp.http.static fake-static-src)
                 (store/ingest 'demo.app
                               (str "(ns demo.app)\n\n"
                                    "(defn greeting \"G.\" [] \"hello\")\n"))
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.enabled" "true")))
                 (#(first (store/record-config-put % "capabilities" :manifest
                                                   "http.static./assets" "public")))
                 (#(first (store/record-file-put % "public/app.css"
                                                 "body{color:rebeccapurple}"))))
        sess (atom {})
        r    (live/start! sess s dir)]
    (try
      (is (:serving? r) (str "start! did not serve: " (:reason r)))
      (let [body (:http/body (http.client/request {:http/url (:url r)
                                              :http/timeout-ms 5000}))]
        (testing "the mount the store declared reached the child as a route"
          (is (str/includes? body ":mounted \"/assets\"") body))
        (testing "and the child READ the file — store bytes, materialized,
                  opened by a process that cannot see the store"
          ;; the assertion that spans all three. A mount that arrived but
          ;; pointed at an empty dir passes the line above and fails here.
          (is (str/includes? body "rebeccapurple") body)))
      (finally (live/stop! r)))))

(deftest the-only-store-that-should-not-be-managed-is-derivable
  ;; `dev.server` existed for exactly one true case: a store whose HTTP
  ;; surface the live session ALREADY serves, where a managed server would
  ;; boot a second image and serve a staler copy of the page in front of
  ;; you. That is slopp's own store, and it is the only one on earth.
  ;;
  ;; A capability that one store should ever set is a footgun for everyone
  ;; else, and it went off within a week: the second project to meet it set
  ;; it false because its ASSETS were 404ing, and the switch then hid that
  ;; bug rather than reporting it. So the question stops being asked and
  ;; starts being computed — this process knows what it serves.
  (let [put  (fn [s k v] (first (store/record-config-put s "capabilities" :manifest k v)))
        web  (-> (store/empty-store)
                 (store/ingest 'app.api
                               (str "(ns app.api)\n\n"
                                    "(defn ^{:http/method :get :http/path \"/hi\"\n"
                                    "        :malli/schema [:=> [:cat :map] :map]\n"
                                    "        :rest/response :map} hi \"H.\" [req] {:ok true})\n"))
                 (put "http.enabled" "true"))]
    (testing "an ordinary web project is not self-served, whatever else is running"
      (is (not (live/self-served? web #{'some.other.ns}))))
    (testing "a store whose every serving namespace this process already serves IS"
      (is (live/self-served? web #{'app.api})))
    (testing "and a store that serves NOTHING is not self-served — two empty
              sets agree, and vacuous truth here would silently stop managing
              every non-web project's server for the wrong reason"
      (is (not (live/self-served? (store/empty-store) #{}))))))

(deftest static-bytes-for-an-artifact-come-from-the-on-disk-cache
  ;; Measured against slopp-ui's real store minutes after shipping mounts:
  ;; `/assets/cljs/main.js` still 404ed. Its assets are ARTIFACTS
  ;; (compile_client writes the bundle as one), `store/file-content`
  ;; answered `:content nil`, every path was filtered out, and the
  ;; materialized dir held nothing.
  ;;
  ;; An artifact keeps its bytes OUT OF LINE under
  ;; `<store-dir>/.slopp/artifacts/<sha>`. `:content nil` carries two facts
  ;; at once — there are no bytes, and the bytes are one indirection away —
  ;; and the skip written for the first silently swallowed the second,
  ;; producing precisely the 404 it existed to avoid.
  ;;
  ;; **This uses a REAL artifact deliberately** (slopp-ui's suggestion, and
  ;; they were right): the first version passed a synthetic fetcher, went
  ;; green, and proved only that the plumbing called something — while the
  ;; accessor underneath was the blob table, which does not hold artifacts
  ;; and returned nil for the real sha. A fixture standing in for the thing
  ;; under test cannot fail the way production does. Their store also had an
  ;; EMPTY `:files`, so a fixture with any tracked asset would half-work and
  ;; read as success.
  (let [dir   (str (java.nio.file.Files/createTempDirectory
                    "slopp-artifact-store"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        bs    (.getBytes "console.log(1)" "UTF-8")
        entry (artifacts/put! dir bs {:kind :cljs}
                              :content-type "application/javascript")
        st    {:artifacts {"public/cljs/main.js" entry} :blobs {}}
        out   (live/materialize-static! st {"/assets" "public"} dir)]
    (testing "the artifact's bytes are read from the cache and written out"
      (is (= "console.log(1)" (slurp (str out "/public/cljs/main.js")))))
    (testing "and inline content is NOT required — the store answers nil for it"
      (is (nil? (:content (store/file-content st "public/cljs/main.js")))))
    (testing "a store-dir holding no such artifact skips and does not throw —
              one dead asset must not take down a server serving every other path"
      (let [empty-dir (str (java.nio.file.Files/createTempDirectory
                            "slopp-empty-store"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
            o2 (live/materialize-static! st {"/assets" "public"} empty-dir)]
        (is (not (.exists (java.io.File. (str o2 "/public/cljs/main.js")))))))))

(deftest a-bind-failure-leads-with-the-diagnosis-and-says-what-to-do
  ;; slopp-ui hit this in anger (they squatted the port on purpose to see it)
  ;; and reported the message back: the ONE useful clause, "Address already in
  ;; use", arrived last, behind a class name, a stack frame and a `-2` line
  ;; number — and nothing said what was expected of them.
  (testing "the measured case: something else holds the port"
    (let [raw (str "class java.net.BindException: Execution error (BindException)"
                   " at sun.nio.ch.Net/bind0 (Net.java:-2).\nAddress already in use")
          m   (#'live/bind-failure 7999 raw)]
      (is (str/starts-with? m "port 7999 is already in use")
          (str "the diagnosis has to be the first thing read: " m))
      (is (str/includes? m "http.port")
          (str "a refusal that does not say what to do costs a round trip: " m))
      (is (not (str/includes? m "sun.nio.ch"))
          (str "the frame is noise in FRONT of the answer: " m))))
  (testing "an unrecognized failure keeps every byte rather than guessing"
    ;; the honest half. A bind can fail for reasons this does not model
    ;; (privileged port, unresolvable host), and a confident wrong sentence
    ;; is worse than a verbose right one.
    (let [m (#'live/bind-failure 7999 "Permission denied")]
      (is (str/includes? m "Permission denied") m)
      (is (str/includes? m "7999") m))))

(deftest ^:external full-check-says-how-far-behind-the-served-app-is
  ;; The REPORTING half of slopp-ui's friction #5. `behind` answers the
  ;; question; this puts the answer where they were already looking, which is
  ;; the whole argument for choosing it over the `serve` verb they also asked
  ;; for. A gap in reporting is not closed by adding a second thing to
  ;; remember to run — that is the same hand-checking habit with more steps.
  ;;
  ;; The running map's SHAPE is devserver's business and a real boot is
  ;; exercised by `a-refresh-reports-what-it-cost`; what is under test here is
  ;; that the whole-store check asks for it at all. Friction #19 is the
  ;; standing lesson: `module-debt` existed, was correct, and was never asked
  ;; by `full_check` — and a check that is never asked reads exactly like a
  ;; check that passes.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'fb.core "(ns fb.core)\n(defn ^:unused-ok a \"A.\" [] 1)\n")
      (testing "no app server — nothing to say, and that is most stores"
        (is (nil? (:app (external/full-check! sess)))))
      (testing "serving and behind — the number, and where to look"
        (swap! sess assoc :app-server
               {:serving? true :served-at 0 :url "http://127.0.0.1:9999/"})
        (let [a (:app (external/full-check! sess))]
          (is (pos? (:behind a)) (pr-str a))
          (is (= "http://127.0.0.1:9999/" (:url a))
              (str "a count with no address makes the reader go looking: "
                   (pr-str a)))))
      (testing "serving and current — ZERO is reported, never omitted"
        ;; the question is "is the page I am about to look at built from what
        ;; I just wrote?". Silence when the answer is YES puts the reader back
        ;; to curling the endpoint, which is the friction itself.
        (swap! sess assoc :app-server
               {:serving? true :url "http://127.0.0.1:9999/"
                :served-at (:head-at (:store @sess))})
        (let [a (:app (external/full-check! sess))]
          (is (= 0 (:behind a)) (pr-str a))))
      (finally (ops/close! sess)))))

(deftest a-managed-app-VALIDATES-when-the-store-enabled-rest
  ;; This is where `rest.enabled` stops being a line in a config file. The app
  ;; writes no serve! call — slopp generates it — so the capability switch has
  ;; to reach the generated code or the boundary exists and nothing invokes it.
  ;;
  ;; The generated call names `slopp.rest/validating` rather than passing the
  ;; validators inline, so the plan stays data and the wiring stays one symbol.
  (let [plan {:namespaces ['demo.app] :host "127.0.0.1" :port 1234
              :adapter :http-kit}]
    (testing "with rest off, nothing about validation is generated"
      ;; the http-only case, and it must be untouched — an app with no typed
      ;; API must not acquire a validation library or a wrapper
      (let [code (live/serve-code plan)]
        (is (not (str/includes? code "wrap-context")) code)
        (is (not (str/includes? code "slopp.rest")) code)))

    (testing "with rest on, the context is wrapped and the namespace required"
      (let [code (live/serve-code (assoc plan :validate? true))]
        ;; NOT ":http/wrap-context …" — the opts print as a namespaced map
        ;; (#:web{…}), so the qualifier is on the map and the key reads bare.
        ;; Matching the spelling that is actually emitted rather than the one
        ;; the source is written in.
        (is (str/includes? code "wrap-context slopp.rest/validating")
            (str "the seam is what carries it: " code))
        ;; asserting the require FORM, not the substring — the qualified symbol
        ;; alone would pass while the child had the symbol and not the
        ;; namespace, which is the exact bug the static-mount test records
        (is (str/includes? code "(require (quote slopp.rest))")
            (str "the child resolves slopp.rest/validating only if it required it: "
                 code))))))

(deftest a-plan-that-would-serve-NOTHING-says-so-before-the-port-binds
  ;; What the human actually experiences when a marker family moves underneath
  ;; a consuming store: the app server comes up, binds its port, answers 404 to
  ;; every path, and reports `:serving? true` with a url. `session_brief`
  ;; advertises that url and `start-app!` prints it to stderr as a healthy line.
  ;;
  ;; It happened today. My `:web/path` → `:http/path` rename made a consuming
  ;; store's routes unreadable; their app bound its port and served nothing, and
  ;; the surfaces all reported success. `serve-in!` cannot tell — it reports
  ;; health on BIND alone, and a bind succeeds whether or not anything is
  ;; mounted behind it.
  ;;
  ;; The store knows. `rules.http/endpoints` is a pure derivation over exactly
  ;; the markers this slopp reads, so a plan can count what it is about to serve
  ;; before spawning anything.
  (let [cfg       (fn [st values]
                    (assoc-in st [:config "capabilities"]
                              {:format :manifest :values values}))
        with-http (fn [src]
                    (-> (store/empty-store)
                        (cfg {"http.enabled" "true"})
                        (store/ingest 'app.h src)))]

    (testing "a store with a readable endpoint plans to serve it"
      ;; population control: without this the assertion below passes for a
      ;; store that has no web surface at all, which is a different thing
      (let [st (with-http (str "(ns app.h)\n\n"
                               "(defn ^{:http/method :get :http/path \"/x\" :http/auth :public}\n"
                               "  h \"H.\" [_] {:status 200})\n"))
            p  (live/serve-plan st "/tmp/nowhere")]
        (is (true? (:enabled? p)) (pr-str p))
        (is (= 1 (:endpoints p)) (pr-str p))
        (is (nil? (:serves-nothing p)) (pr-str p))))

    (testing "and one whose markers this slopp does not read plans to serve NOTHING"
      (let [st (with-http (str "(ns app.h)\n\n"
                               "(defn ^{:web/method :get :web/path \"/x\" :web/auth :public}\n"
                               "  h \"H.\" [_] {:status 200})\n"))
            p  (live/serve-plan st "/tmp/nowhere")]
        (is (= 0 (:endpoints p)) (pr-str p))
        (is (string? (:serves-nothing p))
            (str "a plan that will 404 everything reported nothing unusual: " (pr-str p)))
        (is (re-find #"(?i)404|nothing" (:serves-nothing p)) (:serves-nothing p))))

    (testing "static-only is NOT nothing — a store can legitimately serve just assets"
      ;; the failure this must not have: refusing to call an assets-only app
      ;; healthy would turn a working configuration into a warning
      (let [st (-> (store/empty-store)
                   (cfg {"http.enabled" "true" "http.static./assets" "public"})
                   (store/ingest 'app.h "(ns app.h)\n\n(defn f \"F.\" [x] x)\n"))
            p  (live/serve-plan st "/tmp/nowhere")]
        (is (= 0 (:endpoints p)) (pr-str p))
        (is (nil? (:serves-nothing p))
            (str "an assets-only app was called empty: " (pr-str p)))))))

(deftest a-DECLARED-entry-is-called-rather-than-generated
  ;; When a project declares what to run, slopp stops writing the `serve!`
  ;; call for it and calls what was declared. That is the point: a derived
  ;; call cannot know the flags a developer wants, and a worker is not a
  ;; `serve!` call at all.
  ;;
  ;; Two things the generated form has to get right, and both are about the
  ;; WIRE rather than about the app:
  ;;
  ;; 1. **The namespace must be REQUIRED.** The child loads the store's
  ;;    namespaces, but a qualified symbol in evaluated position resolves
  ;;    only if its namespace is loaded — the same trap `serve-code`
  ;;    documents for `slopp.rest/validating` and the static mount, both of
  ;;    which are asserted as require FORMS rather than as substrings for
  ;;    exactly this reason.
  ;;
  ;; 2. **It must not BLOCK.** A server's `-main` usually does not return —
  ;;    that is what makes it a server — and this expression crosses an nREPL
  ;;    wire whose reply is how slopp learns the start happened. Called
  ;;    inline, a blocking main wedges the wire and the refresh reads as
  ;;    hung, which is indistinguishable from a slow image.
  (let [form (edn/read-string {:default (fn [_ v] v)}
                              (live/run-code 'shop.core/-main ["--port" "8080"]))
        nodes (tree-seq coll? seq form)]

    (testing "the entry's namespace is required, as a FORM"
      (is (some #(= % '(require (quote shop.core))) nodes)
          (str "nothing requires shop.core, so the symbol resolves only by"
               " luck: " (pr-str form))))

    (testing "the declared fn is called with its declared args, in order"
      (is (some #(= % '(shop.core/-main "--port" "8080")) nodes)
          (pr-str form)))

    (testing "and it does not block the wire"
      (is (some #(and (seq? %) (= 'Thread. (first %))) nodes)
          (str "the call is inline, so a -main that does not return wedges"
               " the nREPL reply: " (pr-str form))))

    (testing "an entry with no args calls the fn with none"
      (let [bare (edn/read-string {:default (fn [_ v] v)}
                                  (live/run-code 'shop.jobs/-main []))]
        (is (some #(= % '(shop.jobs/-main)) (tree-seq coll? seq bare))
            (pr-str bare))))))

(deftest a-DECLARED-runnable-is-reason-enough-to-serve
  ;; `http.enabled` is the master web opt-in and it answers "is this a web
  ;; project". It cannot answer "does this project want a worker running",
  ;; and a project that declares one has said so as plainly as a config can.
  ;;
  ;; So the plan's gate becomes: this store serves HTTP, or it declared
  ;; something to run. Neither implies the other — a CLI project with a
  ;; declared worker is not a web project, and a web project that declares
  ;; nothing still gets its derived server.
  (let [declared (-> (store/empty-store)
                     (assoc-in [:config "dev" :values "run.worker.main"]
                               "shop.jobs/-main"))
        silenced (assoc-in declared [:config "dev" :values "run.worker.enabled"]
                           "false")]

    (testing "a declared runnable enables the plan with http.enabled UNSET"
      ;; the case that matters: a worker is not a web project
      (let [plan (live/serve-plan declared "/tmp/x")]
        (is (:enabled? plan) (pr-str plan))
        (is (= 'shop.jobs/-main (get-in plan [:runnables "worker" :main]))
            (pr-str plan))))

    (testing "a SILENCED entry is not in the plan"
      ;; `runnables` keeps it so a reader can see it was asked for; the PLAN
      ;; is what gets launched, and launching something declared-off is the
      ;; one reading of `:enabled? false` that would be wrong
      (let [plan (live/serve-plan silenced "/tmp/x")]
        (is (empty? (:runnables plan)) (pr-str plan))))

    (testing "and with nothing declared and no http, the plan still declines"
      ;; the guard on the guard: if a declared runnable enabled the plan, an
      ;; absent one must not — most stores are not web projects and must not
      ;; acquire a server by this change
      (let [plan (live/serve-plan (store/empty-store) "/tmp/x")]
        (is (not (:enabled? plan)) (pr-str plan))
        (is (re-find #"http\.enabled" (str (:reason plan))) (pr-str plan))))

    (testing "a web project with nothing declared carries no runnables"
      ;; the derived server is unchanged by this, which is what keeps every
      ;; existing store working
      (let [web  (assoc-in (store/empty-store)
                           [:config "capabilities" :values "http.enabled"] "true")
            plan (live/serve-plan web "/tmp/x")]
        (is (:enabled? plan) (pr-str plan))
        (is (empty? (:runnables plan)) (pr-str plan))))))

(deftest what-the-child-EVALUATES-is-decided-by-the-plan
  ;; `serve-in!` used to have one answer: evaluate the generated `serve!`
  ;; call. With declared entries there are two, and which one applies is a
  ;; property of the PLAN — so it is decided here, purely, rather than inside
  ;; the function that also spawns a JVM and binds a socket.
  ;;
  ;; That split is the same one `serve-plan` already makes and for the same
  ;; reason: everything worth getting wrong is decidable from the store.
  (let [derived  {:namespaces ['demo.app] :host "127.0.0.1" :port 1234
                  :adapter :http-kit :runnables {}}
        declared {:namespaces ['demo.app] :host "127.0.0.1" :port 1234
                  :adapter :http-kit
                  :runnables {"app"    {:main 'shop.core/-main
                                        :args ["--port" "8080"] :enabled? true}
                              "worker" {:main 'shop.jobs/-main
                                        :args [] :enabled? true}}}]

    (testing "with nothing declared, the child evaluates the GENERATED serve! call"
      ;; unchanged for every store that predates this
      (let [code (live/startup-code derived)]
        (is (= 1 (count code)) (pr-str code))
        (is (str/includes? (first code) "slopp.http/serve!") (pr-str code))))

    (testing "with entries declared, it evaluates THOSE and not the generated call"
      ;; "declared replaces the call" — a derived `serve!` beside a declared
      ;; entry would bind a port the project never asked for, and the reader
      ;; would have two servers where they asked for one
      (let [code (live/startup-code declared)]
        (is (= 2 (count code)) (pr-str code))
        (is (not-any? #(str/includes? % "slopp.http/serve!") code)
            (str "the derived server is still generated beside the declared"
                 " entries: " (pr-str code)))))

    (testing "one expression per declared entry, each naming its own fn"
      (let [code (live/startup-code declared)]
        (is (some #(str/includes? % "shop.core/-main") code) (pr-str code))
        (is (some #(str/includes? % "shop.jobs/-main") code) (pr-str code))))))

(deftest ^:external a-DECLARED-entry-actually-RUNS-in-the-child
  ;; Everything above this decides what SHOULD happen: `runnables` reads the
  ;; config, `serve-plan` gates on it, `startup-code` picks the expression.
  ;; All three are pure and all three can be right while nothing starts —
  ;; which is the shape that cost this store a day when a filter was correct
  ;; and never called.
  ;;
  ;; So this one crosses the wire. A real child image, the store's own code,
  ;; a declared entry that leaves EVIDENCE it ran.
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-declared"
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        ;; the entry writes a file, because a return value proves nothing here
        ;; — `run-code` answers :started whatever the fn does, deliberately,
        ;; and this test exists to check the fn was actually CALLED
        marker (str dir "/ran.txt")
        s    (-> (store/empty-store)
                 (store/ingest 'worker.core
                               (str "(ns worker.core)\n\n"
                                    "(defn -main \"Runs.\" [& args]\n"
                                    "  (spit \"" marker "\" (str \"ran:\" (vec args))))\n"))
                 (#(first (store/record-config-put % "dev" :manifest
                                                   "run.worker.main" "worker.core/-main")))
                 (#(first (store/record-config-put % "dev" :manifest
                                                   "run.worker.args" "--once,now"))))
        sess (atom {})
        r    (live/start! sess s dir)]
    (try
      (testing "it comes up on a declared entry alone — no http.enabled anywhere"
        ;; a worker is not a web project, and needing the web opt-in to run one
        ;; would be the derivation answering a question it cannot see
        (is (:serving? r) (str "start! did not serve: " (:reason r))))

      (testing "and it names WHICH entries it is carrying"
        (is (= ["worker"] (:started r)) (pr-str r)))

      (testing "no url is invented for an entry that declared none"
        ;; slopp reads a bound port back from the call it GENERATES; it has
        ;; nothing to read here, and a plausible url nobody can be sure
        ;; answers is worse than none
        (is (not (contains? r :url)) (pr-str r)))

      (testing "the declared fn RAN, with its declared arguments in order"
        ;; the assertion the pure tests above cannot make
        (let [ran? (loop [n 0]
                     (cond (.exists (io/file marker)) true
                           (> n 100) false
                           :else (do (Thread/sleep 100) (recur (inc n)))))]
          (is ran? "the entry never ran — :started reported a thread that did nothing")
          (is (= "ran:[\"--once\" \"now\"]" (slurp marker))
              "the arguments did not arrive in order")))

      (finally (live/stop! r)))))

(deftest a-running-app-CARRIES-what-it-was-started-from
  ;; A declared entry answers `:started` and nothing else — no bound socket,
  ;; no health. So "is this process running current code" cannot be inferred
  ;; from the start at all, and has to be RECORDED at it.
  ;;
  ;; `slopp.currency` is that record, and the reason to reuse it rather than
  ;; count deltas here is its third state: `:current?` is true, false, or
  ;; **nil** when nothing could be measured. A process nobody stamped is not
  ;; a stale one, and collapsing those is how a derived value reports
  ;; confidently about a store it never looked at.
  (let [running {:serving? true :currency {:line "L1" :head "d1" :digest {}}}]

    (testing "the report rides the running map rather than living in a checker"
      (let [r (live/currency nil running)]
        (is (= {:line "L1" :head "d1" :digest {}} (:derived-from r)) (pr-str r))))

    (testing "with no connection it is nil — NOT false"
      ;; false would be a claim about the store. nil is the absence of one.
      (let [r (live/currency nil running)]
        (is (nil? (:current? r)) (pr-str r))
        (is (seq (:why r)) "a nil answer must say why it could not measure")))

    (testing "and an unstamped process is also nil, with a different reason"
      ;; the two nils are different facts and the `:why` is what separates
      ;; them — "nobody recorded this" is not "I cannot reach the store"
      (let [r (live/currency nil {:serving? true})]
        (is (nil? (:current? r)) (pr-str r))
        (is (not= (:why r) (:why (live/currency nil running)))
            "an unstamped process and an unreachable store gave the same reason")))))

(deftest a-refresh-reloads-what-CHANGED-and-what-captured-from-it
  ;; `refresh!` replaces the whole child JVM. `serve-code`'s docstring records
  ;; the cost: the app's `:http/perform-ctx` is built once per image, so any
  ;; state the app accumulates in it — a cache, a registry, a connection pool
  ;; — is silently lost at every `done`. It names hot-loading as the fix and
  ;; says it is not made.
  ;;
  ;; The reload SET is the part worth getting right, and it is the same
  ;; question `--live` already answers: what changed, PLUS what captured a
  ;; value from it at def time. `kernel.boot/with-dependents` is that answer
  ;; and it is tested; a sixth reload implementation in this namespace would
  ;; be the fifth and sixth solving one failure class two ways, which this
  ;; store already has two of.
  (let [loaded {'app.schema "(ns app.schema)\n(def s :old)\n"
                'app.api    "(ns app.api (:require [app.schema :as sc]))\n(def e sc/s)\n"
                'app.other  "(ns app.other)\n(def x 1)\n"}
        moved  (assoc loaded 'app.schema "(ns app.schema)\n(def s :new)\n")]

    (testing "nothing changed, nothing reloads"
      ;; a done that touched no code must not churn the app image
      (is (= [] (live/hot-reload-set loaded loaded))))

    (testing "a changed namespace drags what REQUIRES it"
      ;; app.api's own source is byte-identical and it still has to reload:
      ;; `(def e sc/s)` captured the value at def time, which is the whole
      ;; reason with-dependents exists
      (let [set (live/hot-reload-set loaded moved)]
        (is (some #{'app.schema} set) (pr-str set))
        (is (some #{'app.api} set)
            (str "a dependent that captured a value at def time was left"
                 " holding the old one: " (pr-str set)))))

    (testing "and dependencies come FIRST"
      ;; reloading the dependent before its dependency re-captures the value
      ;; that is about to change — the same bug, one poll later
      (let [set (vec (live/hot-reload-set loaded moved))]
        (is (< (.indexOf set 'app.schema) (.indexOf set 'app.api)) (pr-str set))))

    (testing "an untouched namespace is left alone"
      ;; the cost of hot-loading is reloading more than a poll would; it must
      ;; not become reloading everything, or it is the image replacement it
      ;; was written to avoid
      (is (not-any? #{'app.other} (live/hot-reload-set loaded moved))))))

(deftest ^:external a-refresh-KEEPS-the-child-jvm-when-it-can
  ;; `hot-reload-set` is pure and tested, and being right about the SET proves
  ;; nothing about whether anything reloads it. Twice today a correct pure
  ;; decision sat behind an uncalled caller, so this one crosses the wire.
  ;;
  ;; IMAGE IDENTITY is the evidence, and it is the right evidence rather than
  ;; a convenient one: the app's `:http/perform-ctx` lives in that JVM. Same
  ;; image across a refresh IS the state surviving; a new image is the loss
  ;; `serve-code`'s docstring records, whatever else the refresh reports.
  (let [dir   (str (java.nio.file.Files/createTempDirectory
                    "slopp-hot" (make-array java.nio.file.attribute.FileAttribute 0)))
        app   (fn [greeting]
                (str "(ns demo.app)\n\n"
                     "(defn greeting \"G.\" [] \"" greeting "\")\n\n"
                     "(defn ^{:http/method :get :http/path \"/hi\"\n"
                     "        :http/auth :public} hi \"H.\" [req] {:ok true})\n"))
        base  (-> (store/empty-store)
                  (store/ingest 'slopp.http fake-web-src)
                  (store/ingest 'slopp.http.static fake-static-src)
                  (store/ingest 'demo.app (app "one"))
                  (#(first (store/record-config-put % "capabilities" :manifest
                                                    "http.enabled" "true"))))
        moved (store/ingest base 'demo.app (app "two"))
        ;; a SECOND endpoint namespace, so the load order genuinely grows —
        ;; `load-order` seeds from the served surface, so a namespace with no
        ;; route was never in it and adding one changes nothing
        grew  (store/ingest moved 'demo.extra
                            (str "(ns demo.extra)\n\n"
                                 "(defn ^{:http/method :get :http/path \"/extra\"\n"
                                 "        :http/auth :public} ex \"E.\" [req] {:ok true})\n"))
        sess  (atom {})
        r     (live/start! sess base dir)]
    (try
      (is (:serving? r) (str "fixture did not serve: " (:reason r)))

      (testing "a store whose code MOVED refreshes in place"
        (let [hot (live/hot-refresh! sess moved r)]
          (is (some? hot) "it fell back to a re-boot when it did not have to")
          (is (:hot? hot) (pr-str (dissoc hot :loaded :image)))
          (is (identical? (:image r) (:image hot))
              "the child JVM was replaced — the app's context state is gone")
          (is (some #{'demo.app} (:reloaded hot))
              (str "the changed namespace was not reloaded: "
                   (pr-str (:reloaded hot))))))

      (testing "and refreshing again on the SAME store reloads nothing"
        ;; a done that touched no code must not churn the image. Against
        ;; `moved`, which is what the image now holds — the previous block
        ;; updated it
        (let [hot (live/hot-refresh! sess moved (:app-server @sess))]
          (is (:hot? hot) (pr-str (dissoc hot :loaded :image)))
          (is (= [] (:reloaded hot)) (pr-str (:reloaded hot)))))

      (testing "a changed load ORDER falls back rather than pretending"
        ;; a namespace appearing cannot be served in place: it may need
        ;; requiring in a dependency order this image never had
        (is (nil? (live/hot-refresh! sess grew (:app-server @sess)))
            "it claimed an in-place refresh across a changed load order"))

      (finally (live/stop! (:app-server @sess))))))
