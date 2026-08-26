(ns slopp.rest.paths
  "Publishing the SHAPE of an app's typed API, so something that is not this
  app can generate a client against it.

  This is what makes a client in a DIFFERENT codebase possible: the consumer
  reads a document instead of importing the producer's contracts namespace,
  and the two share no store. Publishing a contract has to be something ANY
  slopp app with `rest` can do; a version that only worked for an app whose
  code lives in a store would be the privilege it exists to remove.

  **It lives in the `rest` family because `:rest/path` is rest's marker.**
  Vendoring is per capability, so while this sat under `slopp.http.*` every
  http-only store shipped a REST publisher it could never use, and the webapp
  publisher had no obvious home at all. `undent` stays in `slopp.http.paths`
  and is shared: `rest` requires `http`, so reaching it is legitimate.

  The price of deriving from vars rather than source: schema NAMES are gone by
  runtime — `^{:rest/response contracts/timeline}` is evaluated at def time, so
  a schema referenced by name inlines into every endpoint that uses it. Names
  are a source-level convenience the wire never had."
  (:require [slopp.http.routes :as routes] [slopp.http.paths :as http.paths]))

(defn ^:export paths-document
  "The SHAPE of the API `ns-syms` serve, as data — what a consumer needs to
  generate a typed client without sharing a store.

  `{:slopp/contract-version 2
    :endpoints [{:method :get :path \"/x\" :name x
                 :handler my.app/x :doc \"GET /x — …\"
                 :media-type \"application/json\" :effectful? false
                 :auth :public :request nil :response […]}]}`

  **Version 2** (2026-08-25). `:handler`, `:doc`, `:media-type` and `:auth`
  all arrived while the version stayed 1, so a version-1 document was never one
  shape — a consumer could not tell which of them it was about to get. Two is
  the version that says every key is there, and it carries `:effectful?` with
  it: one bump for both, since each costs a consumer a migration.

  `:auth` is the endpoint's `:http/auth` declaration verbatim — `:public`, or
  `[:group \"admin\"]`, or whatever an app declares. It is the cheapest key
  here to trust, because the auth write gate REFUSES an endpoint that declares
  none: unlike `:request`, it can never be nil-because-nobody-said, so a
  consumer never has to tell \"public\" from \"unknown\". Published as the
  VALUE rather than a boolean because who may call is what a reader wants;
  whether anyone may is not a question anybody asks.

  `:handler` is the qualified symbol, and it is here because `:name` alone does
  not RESOLVE. Measured on slopp's own nine endpoints, three of them —
  `ns-outline`, `search`, `timeline` — match more than one form by simple name,
  so a consumer linking to \"the form called `:name`\" points at the wrong one
  for a third of the surface and looks right doing it. A namespace and a name
  are plain var metadata, so this costs the document nothing it was protecting:
  `:form-id` would be store identity and is deliberately still absent.

  `:doc` is the handler's own docstring, DE-INDENTED and otherwise WHOLE. A
  consumer wanting one line takes the first sentence; one that ships only a
  first line cannot be un-truncated by a consumer that wants the rest.

  **So a handler's docstring is public API copy.** That is the price of not
  inventing a second prose field beside it — one fact with two homes can
  disagree, and this one cannot. Write it for the caller: an implementation
  note left in there ships to everyone generating a client.

  **And it is MARKDOWN** (D-doc-markdown). This is the crossing where saying so
  matters: the consumer rendering this text is a different store, written by a
  different agent, that cannot ask the author what format it is in — and every
  other field here is typed while the prose was left to convention. Paragraph
  breaks, code spans, bold, lists and tables mean what they look like. The one
  non-markdown construct to expect is `[[name]]`, a reference to another form,
  which a renderer may resolve or may leave as text.

  slopp ships NO renderer, deliberately: publishing the data is this function's
  job, and deciding what it looks like belongs to whatever displays it.

  Derived from VAR METADATA, like every other route derivation here, so it
  answers identically from a live store, a jar, and a native binary — and so
  it ships in the slim jar. That is the whole point: publishing a contract is
  something any slopp-web app can do, not a privilege of the tool that happens
  to keep its code in a store.

  Schemas travel as VALUES, not source. `^{:rest/response contracts/timeline}`
  is evaluated at def time, so by the time we see it the schema is already
  plain malli data — no store read, no source text on the wire, and no schema
  importer at the far end. The cost is that the author's schema NAMES are gone:
  a schema referenced by name inlines into every endpoint that uses it. Names
  are a source-level convenience the runtime never had.

  A missing schema is published as an explicit nil rather than an absent key,
  so a consumer can tell \"no body\" from \"I don't know\".

  **Every `:rest/path` endpoint is here, and there is no way to opt one out.**
  Content is absent by KIND — an `:http/path` form is not part of a typed
  contract — and that is the only exclusion. `:rest/client false` used to
  remove an api as well, which put one consumer's decision on the producer and
  took it out of the published documentation every OTHER consumer reads.

  `:media-type` says what each endpoint ANSWERS, defaulting to
  `application/json`. A remote consumer has no store to ask, so an endpoint
  serving EDN would otherwise be generated a wrapper that calls `.json` and
  fails on the first character."
  [ns-syms]
  {:slopp/rest-paths-version 1
   :paths
   (vec (for [row  (routes/from-namespaces ns-syms)
              :let [m (meta (:handler row))]
              ;; a :webapp/client-routes var contributes catch-all rows pointing at the SAME
              ;; handler; they are one endpoint, so keep the declared path only
              ;; `:rest` rows ONLY — a contract describes the TYPED api, and content
              ;; is not part of one. That used to be `:rest/client false`'s job,
              ;; which is why a stylesheet needed a flag to stay out of its own
              ;; app's API document. The exclusion is about TYPING, not about
              ;; visibility: a document gets no generated client because a
              ;; typed wrapper over HTML would be nonsense, which is not a
              ;; reason for it to be undiscoverable.
              ;;
              ;; No client opt-out. `:rest/client false` used to exclude an
              ;; endpoint from BOTH generation and this document, and the
              ;; second half was never asked for: a public, schema'd, JSON
              ;; endpoint went missing from its own app's API documentation
              ;; because one consumer did not want a wrapper. Whether to
              ;; generate is the generating consumer's question, asked against
              ;; a document; an endpoint does not know who will call it.
              :when (and (= :rest (:kind row))
                         (= (:path row) (str (:rest/path m))))]
          {:method   (:method row)
           :path     (:path row)
           :name     (:name m)
           :handler  (symbol (str (:ns m)) (str (:name m)))
           :doc      (http.paths/undent (:doc m))
           :auth     (:http/auth m)
           ;; what it ANSWERS. A remote consumer generating from this document
           ;; has no store to ask, so an endpoint answering EDN would otherwise
           ;; get a `.json` wrapper that fails on the first character — the
           ;; same defect the local generator had, one process further away.
           :media-type (or (:rest/media-type m) "application/json")
           ;; **DERIVED, not the marker verbatim.** The question a consumer is
           ;; asking is "will firing this change something" — theirs is an
           ;; arming gate on an API explorer — and `:http/effectful` alone
           ;; cannot answer it: slopp's own ten endpoints declare it ZERO
           ;; times, so publishing it as it stands ships `false` everywhere and
           ;; reads like an answer. A consumer's gate would go from inert on
           ;; nil to inert on false, which is worse.
           ;;
           ;; So a non-safe METHOD counts too. That over-warns on a POST which
           ;; only searches, and over-warning is the safe direction for a gate
           ;; whose job is to make someone press twice.
           ;;
           ;; `:http/effects` is deliberately NOT a third arm: `http-unsafe-get`
           ;; refuses a :get/:head that declares any, so effects imply a
           ;; non-safe method already and the arm could never decide anything.
           :effectful? (or (boolean (:http/effectful m))
                           (not (contains? #{:get :head} (:method row))))
           :request  (:rest/request m)
           :response (:rest/response m)}))})
