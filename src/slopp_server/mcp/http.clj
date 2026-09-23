(ns slopp-server.mcp.http
  "MCP over STREAMABLE HTTP: the envelope a server speaks to every agent on
  the machine. One endpoint per project, one JSON-RPC message (or batch) per
  POST, plain-JSON answers; a session minted on initialize (`Mcp-Session-Id`)
  is what the server counts as an attachment. The dispatch is
  `slopp.mcp/handle!`, unchanged."
  (:require [cheshire.core :as json]
            [slopp-server.mcp :as mcp] [clojure.string :as str]))

(defn- parse-body
  "The JSON-RPC message(s) in a request body — a map, or a vector for a
  batch — whatever shape the adapter handed the body in: parsed data, a
  string, or a stream. nil for anything else."
  [body]
  (cond
    (or (map? body) (vector? body)) body
    (nil? body)                     nil
    (string? body)                  (try (json/parse-string body true)
                                         (catch Exception _ nil))
    :else                           (try (json/parse-string (slurp body) true)
                                         (catch Exception _ nil))))

(defn- raw
  "A response the adapter passes through untouched: a JSON body and our own
  headers — the session id rides as a header, which the pipeline's data
  responses cannot carry."
  [status headers m]
  {:status status :http/raw true
   :headers (merge {"Content-Type" "application/json"} headers)
   :body (if (nil? m) "" (json/generate-string m))})

(defn- rpc-error
  "A JSON-RPC error envelope at an HTTP status."
  [status headers id code message]
  (raw status headers {:jsonrpc "2.0" :id id :error {:code code :message message}}))

^:unsafe (defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map
                          [:slopp.mcp.http/lookup fn?]
                          [:slopp.mcp.http/attach! fn?]
                          [:slopp.mcp.http/detach! fn?]]
                    :map]
                   :map]}
  endpoint
  "Answer one HTTP request at a project's MCP endpoint. `doors` is what the
  server lends this envelope: `::lookup` (session id → slopp session, or
  nil), `::attach!` (request → `{:sid :session}` or `{:error}` — opens the
  project when it must) and `::detach!` (session id → ends it).

  The wire, per the streamable-HTTP transport: a POST carries one JSON-RPC
  message or a batch; `initialize` mints a session and the answer carries
  `Mcp-Session-Id`, which the client echoes on every later request; a POST
  that produced no response (notifications only) is `202` with no body;
  the answer is plain JSON, which a client that accepts an event stream
  accepts too. GET — a standalone stream for server-initiated messages —
  is `405`, which the client tolerates: push arrives with the server's
  stream later, and nothing here pretends to it. DELETE ends the session.

  A request naming a session this server does not hold — or naming none —
  is ATTACHED when it names its project (`X-Slopp-Dir`): the server
  restarted or reaped an idle session, and the spec's answer — 404, client
  re-initializes — is one a stdio client behind the pipe can never give,
  so a 404 was a dead session until a human reconnected. The answer carries
  the NEW id, which the pipe adopts; the agent sees one late answer. With
  no dir to attach by, a stale id is still `404` and no id at all `400`."
  [{:slopp.mcp.http/keys [lookup attach! detach!]} req]
  (let [sid (get-in req [:headers "mcp-session-id"])]
    (case (:request-method req)
      :get    (raw 405 {"Allow" "POST, DELETE"}
                   {:error "no standalone stream here — answers ride the POST responses"})
      :delete (if (and sid (lookup sid))
                (do (detach! sid)
                    (raw 200 {} {:ended sid}))
                (raw 404 {} {:error (str "no session " sid)}))
      :post
      (let [msgs (parse-body (:body req))]
        (if-not (or (map? msgs) (and (vector? msgs) (seq msgs) (every? map? msgs)))
          (rpc-error 400 {} nil -32700 "parse error: a JSON-RPC message or a batch of them")
          (let [batch? (vector? msgs)
                ms     (if batch? msgs [msgs])
                id     (:id (first ms))
                init?  (boolean (some #(= "initialize" (:method %)) ms))
                named? (not (str/blank? (str (get-in req [:headers "x-slopp-dir"]))))
                a      (cond
                         init? (attach! req)
                         ;; a session this server does not hold — or none at all
                         ;; — from a client that names its dir: attach where it
                         ;; stands. A pipe that lost its session after a restart
                         ;; may send nothing, and that is the same trust as an
                         ;; initialize from the same dir.
                         (and named? (or (nil? sid) (nil? (lookup sid))))
                         (attach! req)
                         :else nil)
                [sid session] (if a
                                [(:sid a) (:session a)]
                                [sid (when sid (lookup sid))])]
            (cond
              (:error a)
              (rpc-error (if init? 400 404) {} id -32000 (:error a))

              (nil? session)
              (rpc-error (if sid 404 400) {} id -32000
                         (if sid
                           (str "no session " sid " — initialize again")
                           "no Mcp-Session-Id — initialize first"))

              :else
              (let [resps (vec (keep #(mcp/handle! session %) ms))]
                (if (empty? resps)
                  (raw 202 {"Mcp-Session-Id" sid} nil)
                  (raw 200 {"Mcp-Session-Id" sid} (if batch? resps (first resps)))))))))
      (raw 405 {"Allow" "POST, DELETE"} {:error "method not allowed"}))))
