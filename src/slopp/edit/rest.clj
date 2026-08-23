(ns slopp.edit.rest
  "The `rest` capability's WRITE gates — what a store may not declare while it
  publishes a typed API.

  One gate today: an endpoint must type out its contract. It lived in
  `slopp.edit.http` until 2026-08-15 and moving it is what changed its owner —
  `edit.gates/gate-capability` derives the capability from this namespace's last
  segment, so there is no second field to keep in step, and moving a gate is the
  only way to change who owns it.

  **The move has a consequence worth stating, because it is a loosening.** An
  app that serves HTML and publishes no typed API is no longer asked to declare
  `:rest/response` on every page. That was http demanding a JSON contract from a
  document, which is the R6 mistake — one app type's vocabulary applied to
  every project. It is now rest's demand, made only of stores that opted into
  publishing an API.

  Reading and refusing are split across two namespaces: this refuses at the
  WRITE, where an author can still fix it, and `slopp.rules.rest` describes what
  stands. Neighbours: `slopp.rest` is the shipped runtime that HONOURS the
  contract this gate insists exists — declared and honoured being different
  claims is the whole reason both halves are needed."
  (:require [clojure.string :as str]
            [slopp.edit.http :as edit.http]
            [slopp.store :as store]))

(defn ^:export ^{:rule/applies-to :production} rest-endpoint-schema
  "The API-contract gate (D-web-contracts): a `:http/path` endpoint must type out
  its contract so the client validates against the SAME schema. `:rest/response`
  is required on EVERY endpoint; `:rest/request` is required on a BODY method
  (`:post`/`:put`/`:patch`) — a `:get`/`:delete`/`:head` needs only a response.
  Declare a `.cljc` malli schema VAR (shareable/reusable — `some.contracts/order`)
  or an inline `[:map …]` for a one-off shape. Returns a teaching string, or nil
  when clean.

  **Inert until the store opts into `rest`**, which `edit.gates/gate-check`
  decides from the namespace this gate lives in — not this gate. That is the
  point of it living in `slopp.edit.rest` rather than in http: serving a
  document is http's business and publishing a typed API is rest's, so an app
  that renders HTML is never asked for a JSON contract.

  **This docstring said `http.enabled` for a while and that was wrong** — an
  error worth recording rather than quietly correcting, because a consumer
  derived a design proposal from it and reached \"slopp requires a contract on
  every page\". It does not. What is true is narrower and still worth knowing:
  once a store enables `rest`, EVERY endpoint in it is asked for a contract,
  including the pages, which is why `:rest/client false` accumulates on
  stylesheets in stores that also publish an API."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [m (edit.http/web-name-meta e)]
      (when (:http/path m)
        (let [body?   (contains? #{:post :put :patch} (:http/method m))
              missing (cond-> []
                        (not (contains? m :rest/response)) (conj :rest/response)
                        (and body? (not (contains? m :rest/request))) (conj :rest/request))]
          (when (seq missing)
            (str ns-sym "/" form-name " declares the route "
                 (pr-str (:http/path m))
                 " but no " (str/join " / " (map str missing))
                 " — every endpoint types out its contract so the client"
                 " validates against the SAME schema (D-web-contracts). Add "
                 (str/join " and " (map str missing))
                 " to the name metadata: a .cljc malli schema VAR"
                 " (shareable/reusable, e.g. some.contracts/order) or an inline"
                 " [:map …] for a one-off shape.")))))))
