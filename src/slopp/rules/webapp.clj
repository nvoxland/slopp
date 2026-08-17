(ns slopp.rules.webapp
  "Done-grain advisories for the app that runs IN THE PAGE, and the store reads
  they share.

  The sibling of `slopp.edit.webapp` across the grain boundary: a write gate
  judges ONE form against the store it would produce, and these judge an
  EPISODE — which is what lets them see a page whose closure changed without
  the page itself being written.

  **`page-cljs-reach` is one producer on purpose.** The done advisory, the
  `full_check` sweep, and `module_platform`'s stranded-page report all answer
  from it, because a rule that refuses at one surface and a report that lists
  at another must agree, and they only can if they are one derivation.

  Both advisories gate on `webapp.enabled`. `webapp-client-routes-consequences-check`
  gated on nothing until wave 4 — the same defect slopp-ui measured in `rest`'s
  contract advisories, where a check with no capability test runs on every
  store while the arms report claims its owner controls it.

  Neighbours: `slopp.rules` holds the registry these are declared in and the
  severity dial; `slopp.rules.http` is the server-side half, which judges
  routes, links and static mounts rather than pages."
  (:require [rewrite-clj.parser :as p]
            [slopp.store :as store]
            [slopp.project.capabilities :as capabilities]))

(defn webapp-client-routes-consequences-check
  "Done-advisory: an endpoint gained `:web/client-routes` this episode — state what that
   changed, once. Inert until the store opts into `webapp`.

   Declaring a client-routed prefix is the single biggest behavioural change
   available in one piece of metadata, and nothing said so. Before: a bad deep
   link under the prefix was a 404, resolved and refused by the server. After:
   the server serves the document (it cannot know the path is bad), the client
   fetches, gets its own 404, and renders a not-found screen. **The HTTP status
   for every path under that prefix changed from 404 to 200.**

   That is correct — it is what `:web/client-routes` is FOR — but it is a real semantic
   change that only surfaced here because two existing tests happened to assert
   the old status.

   Fires only for the episode that ADDED the declaration, like
   `shell-widening`: it asks once, while the reason is still in context, and
   cannot decay into a standing warning to scroll past. It teaches rather than
   checks, and the boundary inventory still reports `:webapp/client-routing` as an
   UNCHECKED exit — nothing compares the client's route table to the server's,
   and a teach is not a check.

   **It gated on NOTHING until wave 4**, which is the defect slopp-ui found in
   `rest`'s four contract advisories one capability over: a check with no
   capability test runs on every store while the arms report claims its owner
   controls it. Declaring a client-routed prefix is meaningless without the
   capability that makes the browser own routing, so the gate is `webapp`."
  [_session st* changed]
  (when (capabilities/enabled? st* "webapp")
    (let [ds       (store/deltas st*)
          baseline (->> ds (filter #(= :done (:op %))) last :id)
          old-srcs (when baseline (store/sources-at st* baseline))
          declares-client-routes?     (fn [form] (when (and (seq? form) (symbol? (second form)))
                                (:web/client-routes (meta (second form)))))]
      (vec (for [fid changed
                 :let [e (store/form-by-id st* fid)]
                 :when (and e (:name e))
                 :let [new (store/form-sexpr (:node e))
                       old (some-> (get old-srcs fid) p/parse-string store/form-sexpr)
                       ps  (declares-client-routes? new)]
                 ;; only when the declaration is NEW: either the form is new, or
                 ;; its previous version did not carry one
                 :when (and ps (not (declares-client-routes? old)))]
             {:form  (symbol (str (store/ns-of-form-id st* fid)) (str (:name e)))
              :teach (str "every path under " (pr-str ps) " now answers 200, not 404 —"
                          " the server serves this document for any path below the"
                          " prefix and NOT-FOUND moves into the client. Make sure the"
                          " client renders a not-found screen for a path its own"
                          " router does not know, or a bad deep link shows a blank"
                          " pane at a URL that looks valid. The prefix ROOT is not"
                          " covered by the fallback and still needs its own route")})))))

(defn ^:export page-cljs-reach
  "The `:cljs` namespaces `ns-sym`'s require closure reaches, sorted — empty
  when a JVM can load the whole closure.

  ONE producer on purpose: the `http-page-reach` done-advisory, the full_check
  sweep, and `module_platform`'s stranded-page report all answer from here,
  because a rule that refuses at one surface and a report that lists at
  another must agree, and they only can if they are one derivation."
  [st ns-sym]
  (->> (store/ns-closure st ns-sym)
       (filter #(= :cljs (store/platform-for st %)))
       sort
       vec))

(defn ^:export stranded-pages
  "Every `^:web/page` in `st` whose namespace closure reaches `:cljs`, as
  `[{:page ns/name :cljs [namespaces]} …]` — empty when every page opens.

  This is the whole-store face of [[page-cljs-reach]], and it exists for the
  surface the done-advisory structurally cannot serve: declaring a namespace
  `:cljs` strands a page WITHOUT any write to the page, so the done that
  follows has no changed form to hang the finding on. `module_platform` is
  the write that does the stranding, so `module_platform` is where this
  report belongs — the reader who broke the reach is told at the moment they
  broke it, not at the next full_check."
  [st]
  (vec (for [n     (keys (:namespaces st))
             f     (store/forms st n)
             :when (and (:name f) (:web/page (store/form-name-meta f)))
             :let  [cljs (page-cljs-reach st n)]
             :when (seq cljs)]
         {:page (symbol (str n) (str (:name f))) :cljs cljs})))

(defn webapp-page-reach-check
  "Done-advisory: a `^:web/page` entry whose namespace CLOSURE reaches a
  `:cljs` namespace. Reports `{:form :cljs [namespaces]}`; inert until the
  store opts into `webapp`.

  **The write gate is the shallow half.** `webapp-page-unreachable` refuses an
  entry marked in a `:cljs` namespace, which catches the entry itself and
  nothing it calls. An entry sitting in `:cljc` and reaching a `:cljs` view
  passes the gate and fails the tool — and that is where a real app lands,
  because the entry is small and the views are where the code is.

  **Its FRAME, stated honestly (the review caught the prose overstating it):**
  at `done` this sees only pages in `changed`, so the case its class exists
  for — declaring some OTHER namespace `:cljs`, which strands an entry nobody
  wrote to — is silent here. Two surfaces cover that case instead:
  `module_platform` reports [[stranded-pages]] at the write that does the
  stranding, and the `full_check` sweep re-grades every page. The advisory
  earns its keep on the ordinary edit-the-page path; it is not the safety
  net, and prose claiming otherwise was teaching a false comfort.

  Namespace grain, because platform is declared per namespace, so a finer
  answer would be a proxy for one slopp does not actually have.

  It names the `:cljs` namespaces rather than the entry alone. The entry is
  usually fine; the finding is which dependency stranded it, and that is what
  a reader has to move or split.

  Reads the marker through `store/form-name-meta`, the generic address, rather
  than through an app type's own reader — a webapp check has no business
  requiring http's namespace to ask what metadata a form carries."
  [_session st* changed]
  (when (capabilities/enabled? st* "webapp")
    (vec (keep (fn [fid]
                 (when-let [e (store/form-by-id st* fid)]
                   (when (:web/page (store/form-name-meta e))
                     (let [own  (store/ns-of-form-id st* fid)
                           cljs (page-cljs-reach st* own)]
                       (when (seq cljs)
                         {:form (symbol (str own) (str (:name e)))
                          :cljs cljs})))))
               changed))))

(defn ^{:export "slopp.rules"} client-routes
  "Every client route PATTERN this store declares, sorted — `[]` when it has no
  browser app.

  **The value that retires an escape hatch.** `rules.http/ui-route-refs` skips
  any form marked `^:web/client-path`, and its docstring says exactly why:
  *teaching the check to SEE the prefixing is not possible in general, because
  the base arrives through an ordinary function call.* That stopped being true
  when the framework took over the prefixing — a literal `:href` in a view is now
  a CLIENT ROUTE KEY, and this is the table it is a key into.

  In one consuming store that escape was on thirteen views, every one discharged
  with the same sentence. Thirteen copies of one accurate justification is one
  missing mechanism wearing thirteen hats; this is the mechanism.

  **Read from `:webapp/routes` literals anywhere in the store**, rather than from
  the `^:web/page` entry alone. A big app builds its table in pieces and
  concatenates them, and a reader that insisted on one literal in one place would
  report a partial table as the whole one — the shape that makes a join silently
  incomplete.

  **A row whose pattern is not a literal string is SKIPPED rather than guessed
  at.** A computed pattern is one this cannot read; inventing an answer would
  make the join quietly partial, which is worse than a link reported as dangling,
  because a dangling report at least gets looked at.

  `[]` and never nil, so a caller joining against it does not have to tell \"no
  webapp\" apart from \"a webapp that routes nothing\"."
  [st]
  (vec (sort (distinct
              (for [nsx  (keys (:namespaces st))
                    e    (store/forms st nsx)
                    :let [sx (try (store/form-sexpr (:node e)) (catch Exception _ nil))]
                    node (tree-seq coll? seq sx)
                    :when (map? node)
                    row  (get node :webapp/routes)
                    :when (and (vector? row) (string? (first row)))]
                (first row))))))
