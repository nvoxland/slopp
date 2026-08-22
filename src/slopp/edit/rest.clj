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
  or an inline `[:map …]` for a one-off shape. Inert until the store opts into
  HTTP (`http.enabled`), which `edit.gates/gate-check` decides — not this gate;
  auth is checked first, so a naked endpoint still refuses
  on `:http/auth` before this. Returns a teaching string, or nil when clean."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [m (edit.http/web-name-meta e)]
      ;; BOTH spellings, for the length of the marker wave and no longer — see
      ;; `edit.http/http-auth-refusal` for why. This gate requires a contract to
      ;; be PRESENT, so a sweep that re-tags it is refused at the first endpoint
      ;; unless the gate already answers to the name it is moving to.
      (when (or (:http/path m) (:http/path m))
        (let [body?   (contains? #{:post :put :patch}
                                 (or (:http/method m) (:http/method m)))
              has?    (fn [new old] (or (contains? m new) (contains? m old)))
              missing (cond-> []
                        (not (has? :rest/response :rest/response)) (conj :rest/response)
                        (and body? (not (has? :rest/request :rest/request))) (conj :rest/request))]
          (when (seq missing)
            (str ns-sym "/" form-name " declares the route "
                 (pr-str (or (:http/path m) (:http/path m)))
                 " but no " (str/join " / " (map str missing))
                 " — every endpoint types out its contract so the client"
                 " validates against the SAME schema (D-web-contracts). Add "
                 (str/join " and " (map str missing))
                 " to the name metadata: a .cljc malli schema VAR"
                 " (shareable/reusable, e.g. some.contracts/order) or an inline"
                 " [:map …] for a one-off shape.")))))))
