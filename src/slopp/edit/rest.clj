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
  every page\". It does not.

  What WAS true, until `:rest/path` existed: once a store enabled `rest`, every
  route in it was asked for a contract, pages included — so a stylesheet
  declared `:rest/response :string` and then `:rest/client false` to undo the
  wrapper. Both markers existed only to answer a question the page should never
  have been asked, and this gate asking `:rest/path` alone is what ended it."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [m (edit.http/web-name-meta e)]
      ;; `:rest/path` ONLY. This used to fire on `:http/path`, which was the
      ;; only path marker there was — so it asked a stylesheet for a JSON
      ;; contract, the stylesheet answered `:rest/response :string`, and
      ;; `:rest/client false` existed to undo the wrapper that followed. The
      ;; question is right; it was being asked of the wrong things.
      (when (:rest/path m)
        (let [body?   (contains? #{:post :put :patch} (:http/method m))
              missing (cond-> []
                        (not (contains? m :rest/response)) (conj :rest/response)
                        (and body? (not (contains? m :rest/request))) (conj :rest/request))]
          (when (seq missing)
            (str ns-sym "/" form-name " declares the route "
                 (pr-str (str (:rest/path m)))
                 " but no " (str/join " / " (map str missing))
                 " — a :rest/path is a TYPED api, so it types out its contract"
                 " and the client validates against the SAME schema"
                 " (D-web-contracts). Add "
                 (str/join " and " (map str missing))
                 " to the name metadata: a .cljc malli schema VAR"
                 " (shareable/reusable, e.g. some.contracts/order) or an inline"
                 " [:map …] for a one-off shape. If this is content rather than"
                 " an api, declare :http/path instead and it is asked for"
                 " none of this.")))))))

(defn ^:export rest-path-prefix
  "The url prefix this store's REST API lives under — `rest.prefix`, or
  `\"/api\"` — NORMALISED: trailing slashes trimmed, a leading one added.

  **One answer per store**, which is what makes the API/content partition
  total: every route is on exactly one side of it. A setting rather than a
  constant because a real API may live at `/v1` or `/graphql`, and a framework
  that hard-codes `/api` tells such a store its API is content.

  Normalised HERE because the partition joins on `(str prefix \"/\")`: read
  raw, `/api/` became `/api//`, every :rest/path fell outside it and every
  :http/path under /api passed — the partition inverted by one character in a
  config value the registry accepts. Read here rather than at each gate so the
  default and the spelling have one definition; the registry row
  (`rest.prefix`) is what makes the escape the gate names a write
  `config_file` will take."
  [candidate]
  (let [raw (str/trim (str (get-in candidate [:config "capabilities" :values "rest.prefix"])))]
    (if (str/blank? raw)
      "/api"
      (let [p (str/replace raw #"/+$" "")]
        (cond
          (str/blank? p)             "/"
          (str/starts-with? p "/")   p
          :else                      (str "/" p))))))

(defn ^:export ^{:rule/applies-to :production} rest-path-partition
  "The API/CONTENT partition gate: a route is a REST API (`:rest/path`) or
  general HTTP content (`:http/path`), never both and never on the wrong side
  of [[rest-path-prefix]]. Returns a teaching string, or nil when clean.

  **There is no allowed intersection.** That is what lets `/api/*` mean
  something to a proxy, a CSP or a reader WITHOUT consulting metadata — the
  daemon mounts each project's API at `/api/projects/<slug>/<resource>` on
  exactly that assumption, the mount replacing the prefix rather than reading
  any route's metadata, and nothing was enforcing it.

  Three refusals, all grounded in a DECLARATION rather than a coincidence: the
  author says which kind the route is.

  **Why the split exists at all.** `:http/path` was once the only path
  declaration, so a stylesheet and a typed endpoint were the same thing to
  every gate — and `rest-endpoint-schema` asked both for a contract. A page
  therefore declared `:rest/response :string`, which is a lie about a
  `text/css` body, and `:rest/client false` existed to undo it. Measured in a
  consuming store at 10 of 11 endpoints carrying that flag, 9 of them bare
  copies recording no reason. The flag was the ABSENCE of this gate.

  Inert until the store opts into `rest`, decided by `edit.gates/gate-check`
  from the namespace this lives in. A store with no typed API has no partition
  to keep, and `:http/path` under `/api` is unremarkable there — which is a
  cost worth naming: enabling `rest` later will refuse such a route, and that
  is the partition becoming total rather than a regression."
  [candidate ns-sym form-name]
  (when-let [e (store/form-named candidate (symbol (str ns-sym)) (symbol (str form-name)))]
    (let [m       (edit.http/web-name-meta e)
          rest-p  (:rest/path m)
          http-p  (:http/path m)
          prefix  (rest-path-prefix candidate)
          under?  (fn [p] (let [s (str p)]
                            (or (= s prefix)
                                ;; a root prefix owns everything; anything
                                ;; else owns its own subtree
                                (str/starts-with? s (if (= "/" prefix) "/" (str prefix "/"))))))
          where   (str ns-sym "/" form-name)]
      (cond
        (and rest-p http-p)
        (str where " declares BOTH :rest/path " (pr-str (str rest-p))
             " and :http/path " (pr-str (str http-p))
             " — one endpoint is one kind. :rest/path is a typed API: it owes a"
             " :rest/response, it is published in the contract document, and a"
             " client is generated for it. :http/path is content a reader"
             " fetches, and owes none of that. Keep the one it IS.")

        (and rest-p (not (under? rest-p)))
        (str where " declares :rest/path " (pr-str (str rest-p))
             ", which is outside this store's API prefix " (pr-str prefix)
             " — so nothing can tell it from content by its url. Either move it"
             " under " (pr-str prefix) ", or change the prefix"
             " (config_file {path \"capabilities\" key \"rest.prefix\" value \"/v1\"})"
             " if this store's API genuinely lives elsewhere. One answer per"
             " store, or the partition is not total.")

        (and http-p (under? http-p))
        (str where " declares :http/path " (pr-str (str http-p))
             ", which is under this store's API prefix " (pr-str prefix)
             " — that space belongs to the typed API, so a proxy or a reader"
             " can rely on it without reading metadata. If this IS an API,"
             " declare :rest/path and give it a :rest/response; if it is"
             " content, serve it outside " (pr-str prefix) ".")))))
