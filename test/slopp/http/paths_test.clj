(ns slopp.http.paths-test
  "Cover for the document that makes a project's pages discoverable.

  The failure that matters is not a missing key. It is publishing what the
  author DECLARED where that differs from what the app SERVES — the shell in
  every real store declares no media type and serves `text/html`, so a
  document built from declarations reports nil for the most important page it
  has. A consumer cannot tell that from a page that genuinely has no type.

  Fixtures are `def`s, not `defn`s, because `:http/path` declares a def whose
  value IS the page: the dispatcher dereferences the var and never calls it."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.http.paths :as http.paths]))

(def ^{:http/method :get :http/path "/p/shell" :http/auth :public
       :webapp/shell "/assets/cljs/main.js"
       :webapp/client-routes ["/p/app"]}
  p-shell
  "Fixture: a whole HTML document that is also the SPA shell.

   Deliberately multi-line with a paragraph break, and deliberately declaring
   NO media type — this is the shape that made publishing declarations wrong,
   because it serves text/html and a declaration-derived document says nil."
  [:html [:head [:title "s"]] [:body [:div {:id "app"} "loading"]]])

(def ^{:http/method :get :http/path "/p/fragment" :http/auth :public}
  p-fragment
  "Fixture: hiccup that is NOT a whole document."
  [:main [:h1 "hello"] [:p "world"]])

(def ^{:http/method :get :http/path "/p/style.css" :http/auth :public
       :http/media-type "text/css"}
  p-style
  "Fixture: text content with a DECLARED type, which must win verbatim."
  "main{margin:0}")

(defn ^{:http/method :get :rest/path "/p/api" :http/auth :public
        :rest/response [:map [:ok :boolean]]}
  p-api
  "Fixture: a typed api, which this document must NOT carry."
  [_req]
  {:status 200 :body {:ok true}})

(deftest a-content-document-publishes-what-is-SERVED-not-what-was-declared
  (let [doc (http.paths/paths-document ['slopp.http.paths-test])
        by  (into {} (map (juxt :path identity)) (:paths doc))]

    (testing "the document names its own version, so a consumer can refuse a
              shape it does not know"
      (is (= 1 (:slopp/http-paths-version doc))))

    (testing "every :http/path form and nothing typed"
      (is (= #{"/p/shell" "/p/fragment" "/p/style.css"} (set (keys by))))
      (is (not (contains? by "/p/api"))
          "a contract describes the typed api; this describes content"))

    (testing "an undeclared page publishes the type it ACTUALLY serves"
      ;; content-response derives text/html from the value being a vector, so
      ;; a document built from declarations reports nil for the one page that
      ;; matters most in every real store.
      (is (= "text/html; charset=utf-8" (:media-type (by "/p/shell"))) (pr-str (by "/p/shell"))))

    (testing "and a DECLARED type wins verbatim, charset or not — the author's
              call and not ours"
      (is (= "text/css" (:media-type (by "/p/style.css")))))

    (testing "shape uses the same discriminator the whole content model does"
      (is (= :hiccup (:shape (by "/p/shell"))))
      (is (= :text (:shape (by "/p/style.css")))))

    (testing "a WHOLE document is distinguishable from a fragment, because
              html/render prepends the doctype for one and not the other"
      (is (= :html (:root-tag (by "/p/shell"))))
      (is (= :main (:root-tag (by "/p/fragment")))))

    (testing "the index DESCRIBES a page and never carries it"
      ;; an index that shipped bodies would put a stylesheet on the wire to
      ;; render one table row, and the reader who wants the body has the URL
      (let [row (by "/p/style.css")]
        (is (= 14 (:bytes row)) (pr-str row))
        (is (not-any? #(and (string? %) (re-find #"margin" %)) (vals row))
            (str "the body reached the document: " (pr-str row)))))

    (testing "a hiccup page is measured in NODES rather than bytes, since it
              is a tree until something renders it"
      (is (pos-int? (:nodes (by "/p/shell"))) (pr-str (by "/p/shell"))))

    (testing "a shell says so, because a consumer rendering a page list wants
              to know which page boots an application"
      (is (= "/assets/cljs/main.js" (:shell (by "/p/shell"))))
      (is (not (contains? (by "/p/fragment") :shell))
          "absent rather than nil — a page with no shell declared none"))

    (testing "the doc is de-indented and whole: a consumer wanting one line
              takes the first sentence, and one shipped only a first line
              cannot be un-truncated"
      (let [d (:doc (by "/p/shell"))]
        (is (re-find #"\n\n" d) "the paragraph break survives")
        (is (not (re-find #"\n   " d))
            (str "source indentation reached the consumer: " (pr-str d)))))

    (testing "and who may call it, from the same declaration the auth gate
              refuses an endpoint for omitting"
      (is (= :public (:auth (by "/p/shell")))))

    (testing "the handler RESOLVES — :name alone does not, and a reader
              following a page back to its source needs the namespace"
      (is (= 'slopp.http.paths-test/p-shell (:handler (by "/p/shell"))))
      (is (= 'p-shell (:name (by "/p/shell")))))))

(deftest the-EMPTY-document-is-the-common-case
  ;; Most stores serve no :http/path form at all — slopp's own app is one —
  ;; so this is the answer a consumer renders most often and the one a test
  ;; most easily does not have.
  (let [doc (http.paths/paths-document ['slopp.http.routes])]
    (is (= 1 (:slopp/http-paths-version doc)))
    (is (= [] (:paths doc))
        "an empty vector, not nil and not a missing key: a consumer maps over it")))
