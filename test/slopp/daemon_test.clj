(ns slopp.daemon-test
  "The daemon: one process, every project, attached exactly while some agent
  is. Driven through the socket-free dispatch, so what is tested is the
  envelope and the lifecycle rather than a port."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [slopp.daemon :as daemon]
            [slopp.http :as http]))

(defn- tmp-dir!
  "A fresh empty directory: a project nobody has written to yet. Canonical,
  because that is what the registry holds (a temp dir on macOS is a symlink
  away from itself)."
  []
  (let [f (java.io.File/createTempFile "slopp-daemon" "")]
    (.delete f)
    (.mkdirs f)
    (.getCanonicalPath f)))

(defn- projects!
  "What /slopp/projects lists, as data."
  [ctx]
  (:body (http/handle! ctx {:request-method :get :uri "/slopp/projects"})))

(deftest ^:external an-agent-attaches-by-dir-and-the-daemon-serves-its-project
  ;; The client names its project by DIR (a header written into .mcp.json
  ;; before any daemon exists); the slug in the path is the display name. A
  ;; session is minted on initialize and echoed back by the client; it is
  ;; the attachment. Two dirs are two projects. The last DELETE closes one.
  (let [d1   (tmp-dir!)
        d2   (tmp-dir!)
        ctx  (daemon/context)
        post (fn [dir slug sid msg]
               (http/handle! ctx {:request-method :post
                                  :uri (str "/slopp/projects/" slug "/mcp")
                                  :headers (cond-> {"x-slopp-dir" dir}
                                             sid (assoc "mcp-session-id" sid))
                                  :body (json/generate-string msg)}))
        body (fn [r] (json/parse-string (:body r) true))
        init {:jsonrpc "2.0" :id 1 :method "initialize"
              :params {:protocolVersion "2025-03-26" :capabilities {}
                       :clientInfo {:name "t" :version "0"}}}]
    (try
      (let [r   (post d1 "one" nil init)
            sid (get-in r [:headers "Mcp-Session-Id"])]
        (is (= 200 (:status r)) (pr-str r))
        (is (string? sid) (pr-str r))
        (is (= "slopp" (get-in (body r) [:result :serverInfo :name])) (pr-str r))
        (testing "the project is open and listed, with its one attachment"
          (let [ps (projects! ctx)]
            (is (= [d1] (mapv :dir ps)) (pr-str ps))
            (is (= 1 (:sessions (first ps))) (pr-str ps))
            (is (= "one" (:slug (first ps))) (pr-str ps))))
        (testing "a call on the session reaches the store: thread_open answers"
          (let [r   (post d1 "one" sid {:jsonrpc "2.0" :id 2 :method "tools/call"
                                        :params {:name "thread_open"
                                                 :arguments {:thread "t-http"}}})
                txt (get-in (body r) [:result :content 0 :text])]
            (is (= 200 (:status r)) (pr-str r))
            (is (re-find #"t-http" (str txt)) (pr-str r))))
        (testing "a notification is accepted with no body"
          (let [r (post d1 "one" sid {:jsonrpc "2.0" :method "notifications/initialized"})]
            (is (= 202 (:status r)) (pr-str r))))
        (testing "a second dir is a second project with its own session"
          (let [r2 (post d2 "two" nil init)
                ps (projects! ctx)]
            (is (= 200 (:status r2)) (pr-str r2))
            (is (= #{d1 d2} (set (map :dir ps))) (pr-str ps))
            (daemon/detach! (get-in r2 [:headers "Mcp-Session-Id"]))))
        (testing "an unknown session is told to initialize again"
          (let [r (post d1 "one" "nope" {:jsonrpc "2.0" :id 3 :method "ping"})]
            (is (= 404 (:status r)) (pr-str r))))
        (testing "a standalone stream is declined, not broken"
          (let [r (http/handle! ctx {:request-method :get :uri "/slopp/projects/one/mcp"
                                     :headers {"mcp-session-id" sid}})]
            (is (= 405 (:status r)) (pr-str r))))
        (testing "DELETE detaches, and the last detach closes the project"
          (let [r (http/handle! ctx {:request-method :delete :uri "/slopp/projects/one/mcp"
                                     :headers {"mcp-session-id" sid}})]
            (is (= 200 (:status r)) (pr-str r)))
          (is (empty? (projects! ctx)) (pr-str (projects! ctx)))))
      (finally (daemon/reset-all!)))))
