(ns slopp.mcp.http
  "MCP over STREAMABLE HTTP: the envelope a daemon speaks to every agent on
  the machine. One endpoint per project, one JSON-RPC message (or batch) per
  POST, plain-JSON answers; a session minted on initialize (`Mcp-Session-Id`)
  is what the daemon counts as an attachment. The dispatch is
  `slopp.mcp/handle!`, unchanged."
  (:require [cheshire.core :as json]
            [slopp.mcp :as mcp]))

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
  daemon lends this envelope: `::lookup` (session id → slopp session, or
  nil), `::attach!` (request → `{:sid :session}` or `{:error}` — opens the
  project when it must) and `::detach!` (session id → ends it).

  The wire, per the streamable-HTTP transport: a POST carries one JSON-RPC
  message or a batch; `initialize` mints a session and the answer carries
  `Mcp-Session-Id`, which the client echoes on every later request; a POST
  that produced no response (notifications only) is `202` with no body;
  the answer is plain JSON, which a client that accepts an event stream
  accepts too. GET — a standalone stream for server-initiated messages —
  is `405`, which the client tolerates: push arrives with the daemon's
  stream later, and nothing here pretends to it. DELETE ends the session.

  A request naming a session this daemon does not hold is `404`, the signal
  a client re-initializes on; one naming none at all, `400`."
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
                a      (when init? (attach! req))
                [sid session] (if init?
                                [(:sid a) (:session a)]
                                [sid (when sid (lookup sid))])]
            (cond
              (:error a)
              (rpc-error 400 {} id -32000 (:error a))

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
