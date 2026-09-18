(ns slopp-server.ui.shell
  "The application document the daemon serves at every page address: one
  stored value, completed by the framework with the bundle script and the
  mount prefix. Lifted from the retired slopp-ui hub, where it was the only
  page that process served; the daemon serves it beside every project's API."
  (:require [slopp-server.ui.styles]))

(def ^{:http/method :get :http/path "/**" :http/auth :public
       :webapp/shell "/assets/cljs/main.js"}
  shell
  "GET /** — the whole application, as one stored document.

  **The only page this process serves**, at the only address it needs. `**`
  matches zero or more segments, so `/` and every client route beneath
  `/p/<slug>` are one marker: a hard load of `/p/slopp2/store` answers with
  this, or a refresh 404s on a url the app itself emits.

  **It was TWO markers for a day, and the reason it is one again is worth the
  paragraph.** `/` plus a second `/p/**` over this same value, because a shell
  at `/**` made the whole origin a catch-all — `/nonsense` answered 200 with
  the document, so a typo, a stale asset url and a real page were
  indistinguishable at the server, and
  `a-registered-projects-bytes-arrive-through-the-proxy-unchanged` caught
  exactly that when the fallback was first widened.

  slopp built the answer rather than leaving the trade-off: **a shell DERIVES
  its status from the client route table.** Same bytes at every address; 200
  when `:webapp/routes` matches, 404 when it does not. So the prefix is no
  longer doing the discriminating, and the 404s are BETTER than it managed —
  `/p/<slug>/nonsense` is inside `/p` and was answered 200 by the old marker.

  **It depends on `serve!` passing `:webapp/routes`.** Absent, the derivation
  is skipped and every address is 200 again — deliberately, so that shells
  written before the feature keep working. That makes it a silent degradation,
  which is why the check that guards it asserts a 404 on the WIRE rather than
  asserting anything about this marker.

  **The framework adds exactly two things and this must not write either** —
  the `<script>` at the end of the head, from `:webapp/shell`, and the mount
  prefix stamped on `[:div {:id \"app\"}]` as `data-base`. Those are the two
  facts a stored value cannot hold. Writing them here would double the first
  and freeze the second.

  **No `<!DOCTYPE html>` either.** `render` prepends it to a top-level
  `[:html …]`, so writing one gives two.

  Four endpoints became one, and it is fewer rather than the same number
  rearranged: `picker`, `project-root`, `project-code` and `project-endpoints`
  were a document each, and three of them were the SAME document differing only
  in `data-base`. The slug was a server-side fact because the app was mounted
  per project. With the app at the root it is the first segment of a client
  route, which is where it belonged — a project switcher changes it without a
  page load, and nothing re-stamps an attribute mid-page.

  **The landing enters a lone project from the CLIENT, and this document never
  redirects.** `picker` answered `/` with a 302 to the first project answering,
  arguing that a list of one is not a choice but a page you read once and
  click through. A stored value cannot redirect, and for a while the landing
  LISTED a single project instead, because a navigate-on-arrival by hand puts
  a trap on the back button that a 302 did not. The click did turn out to
  matter more than the trap, and the trap had an answer: the landing page
  ANSWERS `{:webapp/redirect …}` and the framework performs it with a
  REPLACE, so back skips the landing exactly as it skipped the 302."
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:title "slopp"]
    [:link {:rel "stylesheet" :href "/css/style.css"}]]
   [:body
    [:div {:id "app"}]]])
