(ns slopp.rest
  "The typed-API capability: the ONE namespace an app enabling `rest` requires.

  A contract declared in `:rest/request` / `:rest/response` was already gated at
  write time, published to consumers and used to generate typed clients — and
  honoured by nothing. An untrusted body reached the handler unchecked. This
  namespace is what turns the declaration into a boundary, and it is deliberately
  two calls: `validating` attaches the checks to a context, and `call` drives
  this app's own endpoints through the real wire encoding so a test sees what a
  consumer sees.

  **The dispatcher never learns this namespace exists.** It looks for
  `:rest/decode-request` and `:rest/check-response` on the context and calls
  whatever it finds, exactly as it treats `:http/read-performers`. That is what
  keeps malli here rather than in the http framework: an app serving HTML has no
  contracts to check and must not pay for a validation library to prove it.

  It mirrors `slopp.cli` on purpose — a pure core deciding what a boundary
  MEANS (`slopp.rest.contract`, argv's counterpart) under a shell that performs
  it. An author who has met one should recognise the other.

  Neighbours: `slopp.web` serves; `slopp.web.contract` publishes the document a
  consumer generates a client from; this enforces the same schemas at runtime,
  so the published promise and the served behaviour cannot disagree."
  (:require [slopp.rest.contract :as rest.contract]
            [slopp.web.dispatch :as dispatch]
            [cheshire.core :as json] [clojure.string :as str]))

(defn ^:export validating
  "`ctx` with the contract validators attached — the one call that turns a
  declared contract into an enforced one.

  Written as a function OVER a context rather than an option to
  `slopp.web/context` on purpose. The dispatcher must not know this namespace
  exists: it looks for `:rest/decode-request` and `:rest/check-response` and
  calls whatever it finds, the same way it treats `:http/read-performers`. That
  is what keeps malli here and out of the http framework, so an app serving
  HTML never pays for a validation library it has no contracts to use.

  So an app assembles its own boundary and can SEE that it did:

      (-> (web/context {:http/namespaces [...]}) (rest/validating))

  Leaving the call out is how an app opts out, and the absence is visible at
  the call site rather than in a config file somewhere else."
  [ctx]
  (assoc ctx
         :rest/decode-request rest.contract/decode-request
         :rest/check-response rest.contract/check-response))

(defn ^:export call
  "Call one of THIS app's endpoints in process, through the real wire encoding,
  and get back what a client would receive: `{:status :body}`.

  `req` is `{:method :path :body :headers :query-string}`. The body goes out
  through JSON and the answer comes back through JSON, because that is the only
  thing that makes the answer true.

  **The e2e loop without the e2e cost**, and the framework has been owing it to
  authors rather than paying it. `slopp.web.client/fake-requester` says in its
  own docstring that it does not model the server's parsing and sends you to a
  real server; `dispatch/handle!`'s teach marker told you to round-trip through
  JSON by hand. Both were correct advice about a gap. This closes it: no
  socket, no port, no browser, no process — and yet a keyword arrives as a
  string and a set arrives as an array, which is what a consumer gets and what
  every in-image assertion has been missing.

  **The boundary is REAL here.** `call` goes through `handle!`, so a body that
  violates its contract is refused exactly as it would be over a socket. A fake
  that skipped that would be a second implementation of the server, and the two
  would disagree on the first change — the failure every test double in this
  framework is written to avoid.

  A `:http/raw` response is handed back untouched: its body is bytes or markup
  the adapter writes verbatim, so parsing it as JSON would be inventing a
  shape. Same branch both adapters take.

  **Named without a `!` deliberately, and it is a close call.** This runs the
  app's own handlers and whatever effects they emit, so the bang convention has
  a real claim on it — the write gate suggests `call!` and is not wrong. It
  mirrors `slopp.cli/run` instead, because `slopp.cli`'s own docstring commits
  to the two capabilities looking alike (an author who has met one should
  recognise the other, and the shapes that are the same should look the same),
  and these ARE the same shape: drive this app's own entry point in process and
  answer with data.

  Worth knowing that `cli/run` is equally effectful and was never flagged — its
  writes go through Java interop, which effectfulness does not propagate
  through — so the two were split by DETECTION rather than by judgement. This
  is the judgement, made once for both."
  [ctx {:keys [method path body headers query-string]}]
  (let [wire (fn [v] (json/parse-string (json/generate-string v) true))
        ;; a `?` in the path is SPLIT, because the transport this stands in for
        ;; does it: an HTTP request line carries the path and the query string
        ;; separately, and both adapters hand the dispatcher two keys. A caller
        ;; writes "/api/search?q=hello" because that is what a URL looks like,
        ;; and a fake that answered 404 to one would be failing at the single
        ;; job it has — being the transport. Found by pointing this at slopp's
        ;; own API.
        [p q] (str/split (str path) #"\?" 2)
        resp (dispatch/handle!
              ctx
              (cond-> {:request-method (or method :get)
                       :uri p
                       :headers (or headers {})}
                (or query-string q) (assoc :query-string (or query-string q))
                ;; through the wire on the way IN as well: the adapter parses a
                ;; JSON string, so a caller handing us a keyword has to see it
                ;; become a string here too. A fake that is KINDER than the
                ;; socket passes tests production would refuse.
                (some? body) (assoc :body (wire body))))]
    (if (:http/raw resp)
      resp
      (assoc resp :body (wire (:body resp))))))
