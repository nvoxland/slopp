(ns slopp.webapp.paths-test
  "Cover for the document that lists what a project's BROWSER owns.

  Two failures matter and neither is a missing key. Publishing the framework's
  generated catch-alls would present rows nobody wrote as surface somebody
  did — a reader cannot tell `/store/*client-path` from a route an author
  typed. And treating the overlap with `/api/http/paths` as duplication would
  quietly delete one of two answers: the same var is a served document AND the
  owner of browser-side paths, which are different questions."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.webapp.paths :as webapp.paths]
            [slopp.http.paths :as http.paths]))

(def ^{:http/method :get :http/path "/w/app" :http/auth :public
       :webapp/shell "/assets/cljs/main.js"
       :webapp/client-routes ["/w/store" "/w/ns"]}
  w-app
  "Fixture: the client-routed document, owning TWO prefixes.

   Two on purpose — a var contributing one row per prefix is the whole shape,
   and a single-prefix fixture cannot tell that from one row per var."
  [:html [:head] [:body [:div {:id "app"}]]])

(def ^{:http/method :get :http/path "/w/plain" :http/auth :public}
  w-plain
  "Fixture: an ordinary page whose browser owns nothing."
  [:main "static"])

(deftest a-client-routed-document-publishes-its-DECLARED-prefixes
  (let [doc (webapp.paths/paths-document ['slopp.webapp.paths-test])
        by  (into {} (map (juxt :prefix identity)) (:paths doc))]

    (testing "the document names its own version"
      (is (= 1 (:slopp/webapp-paths-version doc))))

    (testing "one row per DECLARED prefix, not one per var"
      (is (= #{"/w/store" "/w/ns"} (set (keys by)))))

    (testing "a page whose browser owns nothing contributes no row"
      (is (not-any? #(= 'w-plain (:name %)) (:paths doc))))

    (testing "the generated catch-alls are ABSENT — they are rows nobody wrote"
      ;; the framework generates /w/store/*client-path so a refreshed deep
      ;; link reaches the app. Publishing it would read as a route an author
      ;; typed, and a reader cannot tell the two apart.
      (is (not-any? #(re-find #"\*client-path" (str (:prefix %))) (:paths doc))
          (pr-str (:paths doc))))

    (testing "each row names the SERVER route that serves the prefix, because
              a prefix alone does not say where the browser fetches the page"
      (is (= "/w/app" (:document-path (by "/w/store"))))
      (is (= 'slopp.webapp.paths-test/w-app (:handler (by "/w/store")))))

    (testing "and the bundle, so a consumer can say which prefixes boot code
              slopp built"
      (is (= "/assets/cljs/main.js" (:shell (by "/w/store")))))

    (testing "auth rides along, from the route that serves the document"
      (is (= :public (:auth (by "/w/store")))))

    (testing "the doc is de-indented and whole"
      (let [d (:doc (by "/w/store"))]
        (is (re-find #"^Fixture: the client-routed document" d))
        (is (not (re-find #"\n   \S" d)) (pr-str d))))))

(deftest a-client-routed-document-appears-in-BOTH-views-and-says-different-things
  ;; The overlap is deliberate and is the thing most likely to be "cleaned up"
  ;; by someone who sees the same var in two documents. It is one var wearing
  ;; two hats: a page the server serves, and the owner of paths it does not.
  (let [content (http.paths/paths-document ['slopp.webapp.paths-test])
        client  (webapp.paths/paths-document ['slopp.webapp.paths-test])]

    (testing "the CONTENT document lists it once, at its server route"
      (is (= ["/w/app"]
             (map :path (filter #(= 'w-app (:name %)) (:paths content))))))

    (testing "the WEBAPP document lists it twice, at the prefixes it owns"
      (is (= ["/w/ns" "/w/store"]
             (sort (map :prefix (filter #(= 'w-app (:name %)) (:paths client)))))))

    (testing "and neither is a subset of the other, which is why there is no
              union document and will not be one"
      (is (not-any? (set (map :path (:paths content)))
                    (map :prefix (:paths client)))
          "a prefix is not a server route"))))

(deftest the-EMPTY-document-is-the-common-case
  ;; Most projects' browsers own nothing — slopp's own app is one — so this is
  ;; the answer a consumer renders most often.
  (let [doc (webapp.paths/paths-document ['slopp.http.routes])]
    (is (= 1 (:slopp/webapp-paths-version doc)))
    (is (= [] (:paths doc))
        "an empty vector, not nil and not a missing key: a consumer maps over it")))
