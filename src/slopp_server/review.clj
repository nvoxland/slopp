(ns slopp-server.review
  "The human's door: the ONE place the served API writes. A person endorses or flags a commit point; no MCP or CLI tool does, because the record's value is that a person, not an agent, looked."
  (:require [slopp.ops :as ops]
            [slopp.ops.branch :as branch]
            [slopp-server.api.contracts :as contracts] [slopp.ops.external :as external] [clojure.string :as str] [slopp.ops.engine :as engine] [slopp.read.history :as history] [slopp.store :as store]))

(defn ^{:http/method :post :rest/path "/api/projects/:slug/review" :http/auth :authenticated
        :rest/request contracts/review-request
        :rest/response contracts/review-response
        :http/effects [:review/record]
        :http/resolve {:session [:project/reader [:path-params :slug]]}}
  review
  "POST /api/projects/:slug/review — a human endorses or flags a commit point.

  The human's door, and the only one: no MCP or CLI tool writes a review.
  The policy is `:authenticated` and the reviewer is `:by` the request's
  resolved identity — never a field the caller sends. The write itself is the
  `:review/record` effect, which lands it onto the branch."
  [req]
  (let [{:keys [target verdict note]} (:body req)
        by (:http/sub (:http/identity req))]
    {:status 200
     :http/effects [[:review/record target verdict by note]]
     :body {:target target :verdict verdict :by by}}))

(defn review-commit!
  "Record that a human reviewed the commit point `target` (its delta id):
  `verdict` is `:endorsed` or `:flagged`, `:by` names the reviewer, `:note` is
  optional. NOT a tool: no MCP or CLI op calls this — the record's whole value
  is that a person, not an agent, looked, so only the authenticated HTTP
  endpoint does, with `:by` from the request's identity. Refuses (as
  `{:error …}`, writing nothing) a verdict other than those two, a missing
  reviewer, and a target that is not a commit point in this store. A record
  with visible absence, never a gate: nothing refuses an unreviewed commit
  point."
  [session target verdict & {:keys [by note]}]
  (let [rows (history/commit-point-rows
              (:store @(ops/with-history session :ops [:commit])))
        ok?  (some #(or (= target (:commit %)) (= target (:origin-id %))) rows)]
    (cond
      (not (contains? #{:endorsed :flagged} verdict))
      {:error (str "review needs a verdict of endorsed or flagged, got " (pr-str verdict))}

      (str/blank? by)
      {:error "review needs a reviewer (:by) — the identity the request resolved"}

      (not ok?)
      {:error (str "no commit point " (pr-str target) " in this store — a review names a"
                   " commit point's id, as the timeline lists it")}

      :else
      (do (engine/commit-appended!
           session
           #(first (store/record-review % target verdict :by by :note note))
           [])
          {:reviewed target :verdict verdict :by by}))))

(defn ^{:http/effect :review/record} review-record!
  "Effect performer: write a human's review of a commit point, then land it
  onto the branch so the timeline (which reads the branch) shows it. `by` is
  the authenticated identity the handler took from the request.

  The route resolves the project's READER, which is read-only, so the write
  goes through a short-lived write session on the same project dir — opened,
  used, closed: a human's click is rare and a session held open for it would be
  an image held for nothing. A context that carries `:open-writer` (a test, or a
  host with a session of its own) supplies the session instead and keeps it.
  A refusal from the ops layer (not a commit point, bad verdict) throws a 400
  carrying its words."
  [{:keys [session open-writer]} target verdict by note]
  (let [own (when-not open-writer
              (external/open! {:slopp.ops/dir (:dir @session)
                               :slopp.ops/lazy-image? true
                               :slopp.ops/agent-id (str "review-" by)}))
        w   (or own (open-writer session))]
    (try
      (let [r (review-commit! w target (keyword verdict) :by by :note note)]
        (when-let [e (:error r)]
          (throw (ex-info e {:http/status 400})))
        (branch/land-thread! w)
        nil)
      (finally
        (when own (ops/close! own))))))
