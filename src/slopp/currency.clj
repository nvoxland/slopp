(ns slopp.currency
  "What store state a DERIVED value was built from, and whether that still
  holds — the record that makes \"is this answer stale?\" a COMPARISON rather
  than a guess.

  slopp tracks CONTENT rigorously: the delta log, form ids, closure hashes.
  It tracked DERIVATION not at all once a value left the process that made
  it. A served route table, a jar, a git projection, a verdict, a session's
  line pointer and a cached source render are each derived from the store and
  each carried no record of what they came from — so no reader could ask, and
  every reader answered confidently. Two agents on one store measured ten
  separate frictions that are all that one gap.

  The stance, and the reason this is a stamp rather than a doctor tool:
  **every read that returns a derived answer reports its own currency in the
  same breath.** A diagnostic somebody has to remember to run is the tax, not
  the fix — it only helps the reader who already suspects. Merge [[report]]
  into whatever the surface already returns.

  Identity is a LINE's head plus that line's `elements` digest, so it crosses
  process boundaries: the stamp is data, both sides read the store, and the
  store is the shared ground. That is what separates this from
  `slopp.image.currency`, which answers a narrower question — which forms one
  IMAGE loaded, in what order — and is deliberately in-process, storeless and
  IO-free so the write paths can stamp without taking on a dependency."
  (:require [slopp.store.db :as db]))

(defn ^:export of
  "The store's identity on `line-id` right now: the value a derived thing
  records so a later reader can ask whether it is still current.

  `{:line id :head delta-id :digest {...}}`, or **nil** when there is nothing
  to ask — no connection, or no line. nil is a real answer and callers must
  keep it: a value stamped nil was never measured, which is not the same as a
  value measured and found stale.

  Two fields because they catch different things, and the second is the one
  that is easy to leave out:

  - `:head` moves when a delta lands on the line. That is every ordinary
    change, and it is cheap — one indexed row.
  - `:digest` is [[slopp.store.db/elements-digest]] over the SAME line, and it
    moves when the materialized rows change WITHOUT a delta: a migration, a
    repair script, any external process rewriting `elements`. The head does
    not move for those, so a head-only stamp reports current about a store
    that changed underneath it. `engine/refresh-cache!` already gates the
    session cache on exactly this pair, for exactly this reason.

  Scoped to a LINE on purpose. An unscoped identity moves whenever any line is
  written, so every agent's derived value would read stale on every other
  agent's edit — which is the question `data_version` already answers badly."
  [conn line-id]
  (when (and conn line-id)
    {:line   line-id
     :head   (db/line-head conn line-id)
     :digest (db/elements-digest conn line-id)}))

(defn ^:export report
  "Whether `stamp` — a store identity [[of]] took when some value was derived
  — is still what the store says.

  `{:derived-from stamp :current? true|false|nil}`, plus `:why` and `:now`
  when the answer is anything but a plain yes.

  **`:current?` has three values and that is the whole point.** `true` means
  measured and matching, `false` means measured and moved, and `nil` means
  nothing could be measured — no stamp was ever taken, or there is no
  connection to compare against. Collapsing nil into false would invent a
  claim about the store; collapsing it into true is the failure this
  mechanism exists to end, since a derived value that cannot tell it is stale
  reports confidently by construction.

  Merge the result into whatever the reader is already returning, so the
  currency rides the answer rather than living in a separate checker somebody
  has to remember to run. A diagnostic that compensates for a silent surface
  is the tax, not the fix."
  [conn stamp]
  (cond
    (nil? stamp)
    {:derived-from nil :current? nil
     :why "nothing recorded what this was derived from"}

    (nil? conn)
    {:derived-from stamp :current? nil
     :why "no connection to compare against"}

    :else
    (let [now (of conn (:line stamp))]
      (cond
        (nil? now)
        {:derived-from stamp :current? nil
         :why (str "line " (:line stamp) " is not in this store")}

        (= now stamp)
        {:derived-from stamp :current? true}

        :else
        {:derived-from stamp :current? false :now now
         :why (if (not= (:head now) (:head stamp))
                (str "the line moved: derived at " (:head stamp)
                     ", now at " (:head now))
                (str "the line head is unchanged but its materialized rows are"
                     " not — something rewrote them without appending a delta"))}))))
