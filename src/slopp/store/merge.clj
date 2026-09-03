(ns slopp.store.merge
  "Merging one delta log into another — PURE, and deliberately blind.

  Everything here takes stores and returns stores. No image, no loading, no
  verification, no I/O: `merge-logs` computes what the merged log WOULD be
  and hands the caller a store plus conflicts, notes and the ids it applied.
  `slopp.ops.branch` owns the consequences. That split is what lets the
  merge be tested against synthetic stores built from `empty-store`.

  It also bounds what merging may JUDGE. At this layer a store is a delta
  log and some maps; nothing here knows which namespaces are tests, which
  requires are production, or what a module means beyond a string key. So a
  rule needing any of that belongs above — the module-cycle warning lived
  here once and reported, on every merge into slopp's own main, a cycle that
  existed only because a `-test` namespace's fixture requires are declared
  edges. Blindness is the design; judging anyway is the bug."
  (:require [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [slopp.store.semver :as semver]
            [slopp.store :as store] [clojure.string :as str] [slopp.store.fields :as fields]))

(defn ^:export record-merge
  "Append a `:merge` delta — what arrived, from where, the surfaced conflicts
  (MV records the agent resolves by hand), and `:applied` — the ids of THEIR
  deltas now causally delivered here, which is what keeps iterated merges
  exact. Returns [store' delta]."
  [store from {:keys [merged conflicts new-nses applied id-map agent]}]
  (let [[did store'] (store/gen-id store "d")
        delta (cond-> {:id did :parent (:head store)
                       :op :merge :ns '*session* :from (str from)
                       :at (store/now-ms) :merged merged}
                agent           (assoc :agent agent)
                (seq applied)   (assoc :applied (vec applied))
                (seq id-map)    (assoc :id-map id-map)
                (seq conflicts) (assoc :conflicts (mapv #(dissoc % :ours) conflicts))
                (seq new-nses)  (assoc :new-nses (vec new-nses)))]
    [(store/record-delta store' delta) delta]))

(defn ^:export tag-merged
  "Mark the just-appended delta as a replay of THEIR delta `their-id` — it is
  their work, not ours, and later merges must treat it that way.

  Tagged wherever the value holds that delta: `:pending` (what `try-commit!`
  sends to the journal — the copy that MATTERS, since a merge is judged
  causal from the journal on the next round), `:recent`, and `:deltas` when
  the value carries the list. Tagging only the list, as this once did, left
  the journal's copy untagged and every iterated merge replaying the same
  work again."
  [store their-id]
  (let [tag (fn [ds] (if (seq ds)
                       (conj (pop ds) (assoc (peek ds) :merged-from their-id))
                       ds))]
    (cond-> store
      (:pending store) (update :pending tag)
      (:recent store)  (update :recent tag)
      (:deltas store)  (update :deltas tag))))

(defn ^:export merge-logs
  "Phase 4 m2 (C4/C5 activation): merge `theirs` — a store sharing a common
  delta-log prefix with `ours` (a fork = a copied project dir) — into ours by
  REPLAYING theirs' suffix, form-id-keyed:
  - different-form work merges clean (the granularity dodge, across replicas)
  - identical changes converge silently
  - same-form divergence = MV conflict: ours kept, theirs surfaced
  - a SURFACED conflict whose form our line rewrote AFTER the merge that
    surfaced it is RESOLVED: ours stands, theirs is delivered — the rewrite
    is the resolution the refusal asked for, so \"resolve, then call done
    again\" terminates instead of recomputing the same conflict forever
  - add/add id collisions are remapped to fresh ids
  - whole namespaces created on their side arrive intact (provenance kept,
    the birth :prompt included)
  - a historical :move delta is marked applied and changes nothing: order
    is DERIVED from the forms, and the committer (merge-into-session!)
    arranges every namespace the merge touched before the cold-load gate
  Iterated merges stay exact via causal delivery: replayed deltas carry
  :merged-from (their id), the :merge delta records :applied, and neither
  replays again nor counts as OUR work in conflict detection. ROUND TRIPS
  are causal too (#16): a theirs-delta whose :merged-from names OUR OWN
  delta (content-matched) is our work returning and converges silently;
  fids resolve to the first LIVE candidate (mapped, then original) so a
  stale ping-pong mapping cannot drop an edit; and a candidate that would
  hold two same-named forms in one ns REFUSES (the shadow corruption).
  Returns {:store :merged :conflicts :notes :changed-form-ids :new-nses
           :applied :fork-point} — pure; the caller owns image loads +
  verification."
  [ours theirs & {:keys [from]}]
  (let [od         (:deltas ours)
        td         (:deltas theirs)
        ;; full-value comparison: both sides allocate the same NEXT id for
        ;; different work, so id equality would swallow the first divergence
        common     (count (take-while true? (map = od td)))
        fork-point (:id (last (take common od)))
        ours-sfx   (drop common od)
        ;; causal state from PRIOR merges of THIS source (delta ids collide
        ;; across different sources, so scope by :from): what's delivered,
        ;; and how their form ids were remapped into ours
        prior      (filter #(and (= :merge (:op %)) (= (str from) (:from %)))
                           od)
        delivered  (into #{} (mapcat :applied) prior)
        idmap0     (into {} (mapcat :id-map) prior)
        ;; conflicts a PRIOR merge from this source SURFACED, resolved since:
        ;; the merge delta records each conflict's :fid (ours) and :delta
        ;; (theirs), and a content delta of OURS that touches that fid AFTER
        ;; the surfacing merge is the agent doing exactly what the refusal
        ;; said — rewriting the form to the version they intend. Such a
        ;; theirs-delta converges as delivered, ours standing. Without this
        ;; the conflict recomputed from the fork point on every land (s16:
        ;; four identical refusals while :ours already held the resolution;
        ;; the only exit was thread_drop). :normalize is excluded — a
        ;; machine rewrite is not an agent's decision.
        od-pos     (into {} (map-indexed (fn [i d] [(:id d) i])) od)
        resolved   (into #{}
                         (for [m prior
                               c (:conflicts m)
                               :let [at (od-pos (:id m))]
                               :when (and (:fid c) (:delta c) at
                                          (some (fn [d]
                                                  (and (> (od-pos (:id d) -1) at)
                                                       (not (:merged-from d))
                                                       (contains? #{:add :replace :delete :rename} (:op d))
                                                       (or (= (:fid c) (:form-id d))
                                                           (contains? (:sources d) (:fid c)))))
                                                od))]
                           (:delta c)))
        dropped    (filter #(delivered (:id %)) (drop common td))
        ;; recreated-source guard: a \"delivered\" delta whose content doesn't
        ;; match OUR replayed copy of it means the source was deleted and
        ;; recreated at the same path/name — its ids alias dead history, and
        ;; silently dropping its work would be corruption
        imposter   (some (fn [d]
                           (when-let [copy (first (filter #(= (:id d)
                                                              (:merged-from %))
                                                          od))]
                             ;; compare CONTENT — replay remaps the form-id
                             ;; keys, so only the source texts are stable
                             ;; A PARTIAL replay (some forms didn't resolve
                             ;; here, so our copy holds a SUBSET) is honest
                             ;; delivered history; recreation means the copy
                             ;; carries content the original NEVER had.
                             (when (and (:sources copy)
                                        (not (every? (set (map str (vals (:sources d))))
                                                     (map str (vals (:sources copy))))))
                               d)))
                         dropped)
        ;; #16 round-trip causality: a theirs-delta tagged :merged-from
        ;; with OUR OWN delta's id is our work COMING BACK (they merged
        ;; us earlier). Content-matched (the imposter rule's comparison),
        ;; it converges silently instead of re-litigating as \"theirs
        ;; edited\" — the poison that once dropped a wave's edit and
        ;; noise-conflicted whole resyncs. A copy THEY EDITED after is a
        ;; separate, untagged delta and still replays.
        ours-by-id (into {} (map (juxt :id identity)) od)
        returning? (fn [d]
                     (when-let [orig (ours-by-id (:merged-from d))]
                       (or (nil? (:sources d)) (nil? (:sources orig))
                           (= (sort (map str (vals (:sources d))))
                              (sort (map str (vals (:sources orig))))))))
        theirs-sfx (remove #(or (delivered (:id %)) (returning? %))
                           (drop common td))
        ;; #16 poisoned idmaps: ping-pong accumulates mappings whose target
        ;; died while the ORIGINAL id lives on — resolve to the first LIVE
        ;; candidate (mapped first, then the original) so a stale entry
        ;; cannot silently drop an edit as \"we deleted it\"
        ;; their :merge deltas recorded {our-fid → their-copy-id}; INVERTED,
        ;; an edit they made to their COPY resolves back onto our ORIGINAL
        ;; (the id spaces bifurcate at the first remap and never re-join)
        inverse-map (into {}
                          (comp (filter #(= :merge (:op %)))
                                (mapcat :id-map)
                                (map (fn [[k v]] [v k])))
                          td)
        live-fid   (fn [st idmap fid0]
                     (or (some #(when (and % (store/form-by-id st %)) %)
                               [(get idmap fid0) (get inverse-map fid0) fid0])
                         (get idmap fid0)
                         fid0))
        ;; the content THEIR line held for `fid0` just before delta `d` —
        ;; the base their edit built on. When OUR current content equals it,
        ;; their edit FAST-FORWARDS ours (we haven't moved since they copied
        ;; us), so a creation-touch alone is not a conflict.
        their-base (fn [d fid0]
                     (->> td
                          (take-while #(not= (:id %) (:id d)))
                          reverse
                          (some (fn [pd] (get (:sources pd) fid0)))))
        ;; The content THEIR line ends up holding for `fid0`, from delta `d`
        ;; onward — so an `:add` can ask what the form FINISHES as, not only
        ;; what it started as. The `:add` arm resolves by NAME (a replayed copy
        ;; arrives under a new id, so the name is all that survives), and a
        ;; RENAME in their own log is precisely what invalidates that: we look
        ;; for the pre-rename name, find nothing, append a second copy, and
        ;; their rename then collides it with the one we already hold.
        their-final (fn [d fid0]
                      (->> td
                           (drop-while #(not= (:id %) (:id d)))
                           (keep (fn [pd] (get (:sources pd) fid0)))
                           last))
        ;; successive theirs-ops on ONE diverged form COALESCE into a single
        ;; conflict reflecting the NEWEST theirs — sixteen rows for one form
        ;; bury the real signal (fid-keyed; deps/rename conflicts unaffected)
        note-conflict (fn [conflicts c]
                        (if-let [ix (first (keep-indexed
                                            (fn [ix e]
                                              (when (and (:fid e)
                                                         (= (:fid e) (:fid c)))
                                                ix))
                                            conflicts))]
                          (assoc conflicts ix c)
                          (conj conflicts c)))
        touched    (store/suffix-touched (remove :merged-from ours-sfx))
        ;; What OUR suffix DELETED, by name. `touched` cannot answer this: it
        ;; is form-id keyed, and a replayed copy arrives under a new id, so the
        ;; id we deleted and the id they are re-adding are different values for
        ;; one piece of code. The NAME is the only thing that survives replay,
        ;; which is why the `:add` arm resolves by it — this is the same
        ;; question asked of our own suffix.
        deleted-here (into #{}
                           (comp (remove :merged-from)
                                 (filter #(= :delete (:op %)))
                                 (map (fn [d] [(:ns d) (str (:name d))])))
                           ours-sfx)]
    (if imposter
      {:error (str "merge identity mismatch: delta " (:id imposter)
                   " looks like a recreated fork/branch at the same"
                   " path/name — use a fresh path (or a new branch)")
       ;; the fork point rides the error so callers can tell \"identity
       ;; mismatch\" from \"no shared history\" — one masked the other once
       :fork-point fork-point}
      (loop [st ours, dds (seq theirs-sfx), idmap idmap0, merged 0,
             conflicts [], notes [], changed [], new-nses [], applied []]
        (if-let [d (first dds)]
          (let [ds        (rest dds)
                op        (:op d)
                done      (fn [st idmap merged conflicts notes changed new-nses applied]
                            [st idmap merged conflicts notes changed new-nses applied])
                [st idmap merged conflicts notes changed new-nses applied]
                (case op
                  :trivia
                  ;; cosmetic payload, form-id aliasing risk — deliberate skip
                  (done st idmap merged conflicts
                        (conj notes {:skipped :trivia :delta (:id d)})
                        changed new-nses (conj applied (:id d)))

                  :ns-delete
                  ;; they removed an empty husk. Apply only when OUR copy is
                  ;; also empty (or already gone) — content we grew since is
                  ;; not deletable by their housekeeping
                  (let [nsx (:ns d)
                        live (seq (store/body-forms st nsx))]
                    (if live
                      (done st idmap merged conflicts
                            (conj notes {:skipped :ns-delete :ns nsx
                                         :reason "our copy holds forms theirs never saw"})
                            changed new-nses (conj applied (:id d)))
                      (done (update st :namespaces dissoc nsx)
                            idmap (inc merged) conflicts notes changed new-nses
                            (conj applied (:id d)))))

                  :deps-add
                  ;; a foreign dep declaration. No divergence (new lib or same
                  ;; coord) → land it. Divergence of two mvn versions → auto-
                  ;; resolve to the NEWER (numeric, via slopp.semver) with a note.
                  ;; Diverging incomparable coords (git sha, mixed) → a conflict.
                  (let [lib (:lib d) cur (get-in st [:deps lib]) theirs (:coord d)
                        land (fn [st*]
                               (-> st* (assoc-in [:deps lib] theirs)
                                   (assoc-in [:dep-ns lib] (set (:namespaces d)))))]
                    (cond
                      (or (nil? cur) (= cur theirs))
                      (done (land st) idmap (inc merged) conflicts notes changed
                            new-nses (conj applied (:id d)))

                      (and (:mvn/version cur) (:mvn/version theirs))
                      (let [theirs-newer? (semver/newer? (:mvn/version theirs)
                                                         (:mvn/version cur))]
                        (done (if theirs-newer? (land st) st)
                              idmap (inc merged) conflicts
                              (conj notes {:resolved :deps :lib lib
                                           :kept    (if theirs-newer? theirs cur)
                                           :dropped (if theirs-newer? cur theirs)
                                           :reason "version divergence auto-resolved to newer"})
                              changed new-nses (conj applied (:id d))))

                      :else
                      (done st idmap merged
                            (conj conflicts {:dep lib :ours cur :theirs theirs})
                            (conj notes {:conflict :deps :lib lib
                                         :reason "same dependency pinned to incomparable coords"})
                            changed new-nses (conj applied (:id d)))))

                  :ingest
                  (let [ns-sym (:ns d)]
                    (if (get-in st [:namespaces ns-sym])
                      (done st idmap merged conflicts
                            (conj notes {:skipped :ingest :ns ns-sym
                                         :reason "namespace exists on our side"})
                            changed new-nses (conj applied (:id d)))
                      (let [src (apply str (map #(str (get (:sources d) %) "\n")
                                                (:form-ids d)))
                            st' (tag-merged (store/ingest st ns-sym src
                                                          :agent (:agent d)
                                                          :prompt (:prompt d))
                                            (:id d))
                            new-ids (into [] (keep :id) (store/elements st' ns-sym))]
                        (done st' (merge idmap (zipmap (:form-ids d) new-ids))
                              (inc merged) conflicts notes changed
                              (conj new-nses ns-sym) (conj applied (:id d))))))

                  :add
                  (let [ns-sym (:ns d)
                        fid    (:form-id d)
                        src    (get (:sources d) fid)
                        node   (p/parse-string src)
                        nm     (store/form-symbol node)
                        cur    (when nm (store/form-named st ns-sym nm))
                        ;; what this form FINISHES as on their line. Only
                        ;; interesting when their own log renames it, which is
                        ;; the case `cur` cannot see.
                        fin-src (their-final d fid)
                        fin-nm  (when (and fin-src (not= fin-src src))
                                  (store/form-symbol (p/parse-string fin-src)))
                        fin-cur (when (and fin-nm (not= fin-nm nm))
                                  (store/form-named st ns-sym fin-nm))]
                    (cond
                      (and cur (= (n/string (:node cur)) src)) ; converged
                      (done st (assoc idmap fid (:id cur)) merged conflicts notes
                            changed new-nses (conj applied (:id d)))

                      ;; a prior merge SURFACED this clash and our line
                      ;; rewrote the form since — ours is the resolution
                      (and cur (resolved (:id d)))
                      (done st (assoc idmap fid (:id cur)) merged conflicts notes
                            changed new-nses (conj applied (:id d)))

                      cur                                      ; name clash
                      (done st idmap merged
                            (conj conflicts {:form (symbol (str ns-sym) (str nm))
                                             :ns ns-sym :delta (:id d)
                                             :fid (:id cur)
                                             :ours (n/string (:node cur))
                                             :theirs src
                                             :reason "both sides added this name"})
                            notes changed new-nses applied)

                      ;; WE DELETED THIS NAME and they still have it. Their
                      ;; delta is an `:add` only because landing REPLAYS forms
                      ;; under new ids, so one piece of code has several
                      ;; identities and the copy arriving here is not the one
                      ;; we removed. Appending it would UNDO a deletion in
                      ;; silence — the only merge outcome that produces a wrong
                      ;; store with no signal, and the reason a resurrected
                      ;; `ensure-id-block!` was found by its caller failing to
                      ;; compile rather than by anything here.
                      ;;
                      ;; The twin of `:replace`'s \"we deleted it; they edited
                      ;; it\", which has always conflicted. Ours is KEPT — the
                      ;; deletion stands — and the disagreement is reported
                      ;; rather than resolved by whoever wrote last.
                      (deleted-here [ns-sym (str nm)])
                      (done st idmap merged
                            (conj conflicts {:form (symbol (str ns-sym) (str nm))
                                             :ns ns-sym :delta (:id d)
                                             :ours nil :theirs src
                                             :reason (str "we deleted it; their line still has it"
                                                          " (a replayed copy under a new id)")})
                            notes changed new-nses applied)

                      ;; ALREADY HERE, under the name their own log renames it
                      ;; to. `cur` asked for the name the add MINTED and found
                      ;; nothing, because we hold the renamed result — usually
                      ;; from having merged this same work before it was
                      ;; replayed under fresh ids. Appending here is what
                      ;; produced two forms of one name: their rename follows
                      ;; and lands the copy straight onto ours, and the
                      ;; duplicate postcondition then refuses the WHOLE merge,
                      ;; naming a collision neither line authored and an action
                      ;; (\"rename on one line first\") that whoever reads it
                      ;; cannot take.
                      (and fin-cur (= (n/string (:node fin-cur)) fin-src))
                      (done st (assoc idmap fid (:id fin-cur)) merged conflicts
                            notes changed new-nses (conj applied (:id d)))

                      ;; Same name, different content: we cannot tell their
                      ;; replayed form from one we authored ourselves, so this
                      ;; is the ordinary both-sides-added-this-name question —
                      ;; reported per FORM, for the agent to resolve. That is
                      ;; the point: a per-form conflict is answerable, and the
                      ;; whole-merge refusal it replaces was not.
                      fin-cur
                      (done st idmap merged
                            (conj conflicts {:form (symbol (str ns-sym) (str fin-nm))
                                             :ns ns-sym :delta (:id d)
                                             :fid (:id fin-cur)
                                             :ours (n/string (:node fin-cur))
                                             :theirs fin-src
                                             :reason (str "both sides have " fin-nm
                                                          " — theirs arrived as " nm
                                                          " and their own line renamed it")})
                            notes changed new-nses applied)

                      :else
                      (if-let [[st' d'] (store/append-form st ns-sym node
                                                     :prompt (:prompt d)
                                                     :agent (:agent d))]
                        (done (tag-merged st' (:id d))
                              (assoc idmap fid (:form-id d'))
                              (inc merged) conflicts notes
                              (conj changed (:form-id d')) new-nses
                              (conj applied (:id d)))
                        (done st idmap merged conflicts
                              (conj notes {:skipped :add :reason "no namespace"
                                           :ns ns-sym})
                              changed new-nses (conj applied (:id d))))))

                  :replace
                  (let [ns-sym (:ns d)
                        fid0   (:form-id d)
                        fid    (live-fid st idmap fid0)
                        src    (get (:sources d) fid0)
                        cur    (store/form-by-id st fid)]
                    (cond
                      ;; a prior merge from this source SURFACED this exact
                      ;; divergence and our line rewrote the form since —
                      ;; that rewrite is the resolution; ours stands
                      (resolved (:id d))
                      (done st idmap merged conflicts notes changed new-nses
                            (conj applied (:id d)))

                      (nil? cur)                               ; deleted on our side
                      (done st idmap merged
                            (note-conflict conflicts
                                           {:form (store/qform st ns-sym fid0 theirs)
                                            :ns ns-sym :delta (:id d) :fid fid
                                            :ours nil :theirs src
                                            :reason "we deleted it; they edited it"})
                            notes changed new-nses applied)

                      (= (n/string (:node cur)) src)           ; converged
                      (done st idmap merged conflicts notes changed new-nses
                            (conj applied (:id d)))

                      (and (touched fid) (not (contains? (set changed) fid))
                           ;; FAST-FORWARD: when our current content equals
                           ;; the base THEIR edit built on (their log's last
                           ;; prior source for this form), we haven't moved
                           ;; since they copied us — taking their edit is
                           ;; safe, and a creation-touch alone never conflicts
                           (not= (n/string (:node cur)) (their-base d fid0)))
                      (done st idmap merged
                            (note-conflict conflicts
                                           {:form (store/qform st ns-sym fid0 theirs)
                                            :ns ns-sym :delta (:id d) :fid fid
                                            :ours (n/string (:node cur))
                                            :theirs src
                                            :reason "both sides edited this form"})
                            notes changed new-nses applied)

                      (nil? (:name cur))
                      (done st idmap merged conflicts
                            (conj notes {:skipped :replace :form fid
                                         :reason "anonymous form"})
                            changed new-nses (conj applied (:id d)))

                      :else
                      ;; the resolved form must LIVE in the delta's ns — an
                      ;; unmapped cross-line id can alias a different
                      ;; namespace's form (a round-tripped copy of our own
                      ;; work), and replacing through that alias nils the
                      ;; store via replace-node's miss (the wave-3 merge)
                      (let [rr (when (= (str ns-sym)
                                        (str (store/ns-of-form-id st fid)))
                                 (store/replace-node st ns-sym (:name cur)
                                                     (p/parse-string src)
                                                     :prompt (:prompt d)
                                                     :agent (:agent d)))]
                        (if rr
                          (done (tag-merged (first rr) (:id d)) idmap (inc merged)
                                conflicts notes (conj changed fid) new-nses
                                (conj applied (:id d)))
                          (done st idmap merged conflicts
                                (conj notes {:skipped :replace :form fid
                                             :ns ns-sym
                                             :reason "form-id aliases a different namespace's form (cross-line id collision)"})
                                changed new-nses (conj applied (:id d)))))))

                  :delete
                  (let [ns-sym (:ns d)
                        fid0   (:form-id d)
                        fid    (live-fid st idmap fid0)
                        cur    (store/form-by-id st fid)]
                    (cond
                      (nil? cur)                               ; converged
                      (done st idmap merged conflicts notes changed new-nses
                            (conj applied (:id d)))

                      ;; a prior merge SURFACED \"we edited it; they deleted
                      ;; it\" and our line rewrote the form since — the
                      ;; rewrite stands, their deletion is delivered-and-
                      ;; declined
                      (resolved (:id d))
                      (done st idmap merged conflicts notes changed new-nses
                            (conj applied (:id d)))

                      (touched fid)
                      (done st idmap merged
                            (note-conflict conflicts
                                           {:form (store/qform st ns-sym fid0 theirs)
                                            :ns ns-sym :delta (:id d) :fid fid
                                            :ours (n/string (:node cur)) :theirs nil
                                            :reason "we edited it; they deleted it"})
                            notes changed new-nses applied)

                      :else
                      (let [[st' _] (store/remove-form st ns-sym (:name cur)
                                                 :prompt (:prompt d)
                                                 :agent (:agent d))]
                        (done (tag-merged st' (:id d)) idmap (inc merged)
                              conflicts notes changed new-nses
                              (conj applied (:id d))))))

                  (:rename :normalize)
                ;; changeset ops are all-or-conflict: a partially applied
                ;; rename would leave broken references
                  (let [srcs (:sources d)
                        fids (map #(get idmap % %) (keys srcs))
                        blocked (filter #(and (touched %)
                                              (not (contains? (set changed) %)))
                                        fids)]
                    (if (seq blocked)
                      (done st idmap merged
                            (conj conflicts {:form (store/qform st (:ns d)
                                                          (first blocked) theirs)
                                             :ns (:ns d) :delta (:id d)
                                             :theirs (pr-str (select-keys d [:old :new]))
                                             :reason "their rename touches forms we edited"})
                            notes changed new-nses applied)
                      (let [changeset (into {}
                                            (keep (fn [[fid0 src]]
                                                    (let [fid (live-fid st idmap fid0)]
                                                      (when (store/form-by-id st fid)
                                                        [fid (p/parse-string src)]))))
                                            srcs)
                            [st' _] (store/apply-changeset st (:op d) (:ns d) changeset
                                                     :prompt (:prompt d)
                                                     :agent (:agent d)
                                                     :extra (select-keys d [:old :new]))]
                        (done (tag-merged st' (:id d)) idmap (inc merged)
                              conflicts notes (into changed (keys changeset))
                              new-nses (conj applied (:id d))))))

                  :move
                  ;; REPLAY the reordering (was: skipped as cosmetic — that
                  ;; predates D7/auto-ordering; order is load-bearing now, the
                  ;; merge gate itself refuses a store that won't cold-load).
                  ;; Their form-id maps through idmap; our current NAME is
                  ;; resolved from it (rename-proof); a missing form or target
                  ;; on our side skips with a note, never errors.
                  ;; HISTORICAL: a `:move` in a journal older than 2026-08-29 recorded an
                  ;; arrangement the pipeline had derived. Order is derived at every
                  ;; write and every fold now, so there is nothing to replay — the
                  ;; merged store is arranged by whoever commits it (merge-into-session!
                  ;; resolves every changed namespace). Applied, so it never replays
                  ;; again; not counted, because it changed nothing.
                  (done st idmap merged conflicts notes changed new-nses
                        (conj applied (:id d)))

                  ;; module edges are CRDT-grain: fold theirs in (adds union,
                  ;; removes disj) — never a conflict. A union CAN close a
                  ;; cycle neither side saw, but it cannot be judged HERE:
                  ;; only the DECLARED manifest is in reach, and a -test
                  ;; namespace's fixture requires are declared edges. That
                  ;; reported a cycle on every merge into slopp's own main
                  ;; which no production code had. The caller judges
                  ;; production edges — api.modules/merge-production-cycle.
                  ;; …and the test-only twin folds identically, which is the point of
                  ;; making it a separate relation rather than a flag: same
                  ;; edge grain, same union, no new conflict story.
                  ;;
                  ;; Both go through `fields/fold` rather than re-deriving the
                  ;; update here. The registry's own docstring says that fold is
                  ;; THE fold precisely so record-*, replay-delta and this can
                  ;; never drift — and this arm had quietly become a second copy
                  ;; of it.
                  (:module-edge :module-test-edge)
                  (let [st' (fields/fold st d)]
                    (done st' idmap (inc merged) conflicts notes
                          changed new-nses (conj applied (:id d))))

                  ;; unknown op: never guess with someone's code
                  (cond
                    (fields/replay-merge-op? op)
                    ;; state-carrying non-code ops cross path/key-grain
                    ;; last-writer-wins (main once lost its whole web config to
                    ;; a silent skip here); binary file-put BYTES ride the
                    ;; end-of-merge :blobs union. The delta is RE-MINTED with a
                    ;; fresh id + :merged-from — landing theirs' id verbatim
                    ;; duplicated it against our own post-fork delta of the same
                    ;; number (both lines allocate from the same counter), which
                    ;; db/append!'s UNIQUE id rejected, failing every durable
                    ;; branch merge that touched config/deps/tiers on both sides
                    (let [[nid st1] (store/gen-id st "d")
                          d'  (assoc d :id nid
                                     :parent (:id (last (:deltas st1)))
                                     :merged-from (:id d))
                          st2 (store/record-delta (fields/fold st1 d') d')]
                      (done st2 idmap (inc merged) conflicts notes changed
                            new-nses (conj applied (:id d))))

                    (contains? fields/markers op)
                    ;; line-scoped bookkeeping does not travel — commit-points
                    ;; deliberately (noted; the travel question is an open
                    ;; decision), verification/merge chatter silently
                    (done st idmap merged conflicts
                          (cond-> notes
                            (not (contains? fields/silent-markers op))
                            (conj {:skipped op :delta (:id d)}))
                          changed new-nses (conj applied (:id d)))

                    :else
                    ;; an op NO registry set knows: note it — the end of the
                    ;; merge turns any such note into a REFUSAL (never guess
                    ;; with, or silently drop, someone's state)
                    (done st idmap merged conflicts
                          (conj notes {:unregistered-op op :delta (:id d)})
                          changed new-nses applied)))]
            (recur st ds idmap merged conflicts notes changed new-nses applied))
          ;; #16/#19 postcondition: a candidate holding two same-named forms in
          ;; one ns is CORRUPT (last-definition-wins shadows silently — the
          ;; image runs one form while every name-keyed read shows the other).
          ;; The rename-interplay minted exactly that once; refuse, never land.
          (let [dupes (for [nsx (keys (:namespaces st))
                            [nm cnt] (frequencies
                                      (keep :name (store/forms st nsx)))
                            :when (> cnt 1)]
                        (symbol (str nsx) (str nm)))]
            (cond
              (seq (keep :unregistered-op notes))
              {:error (str "merge met unregistered delta op(s) "
                           (str/join ", " (distinct (map (comp str :unregistered-op)
                                                         (filter :unregistered-op notes))))
                           " — register the op in slopp.store.fields (fold +"
                           " merge strategy + sample) before it can cross a"
                           " merge; a merge never guesses with, or silently"
                           " drops, unknown state")}

              (seq dupes)
              {:error (str "merge would mint duplicate names — refused: "
                           (str/join ", " (map str dupes))
                           " (a same-ns name collision shadows silently in the"
                           " image; resolve by renaming on one line first)")}

              :else
              {:store (update st :blobs #(merge (:blobs theirs) (or % {})))
               :merged merged :conflicts conflicts :notes notes
               :changed-form-ids (vec (distinct changed)) :new-nses new-nses
               :applied applied :id-map idmap :fork-point fork-point})))))))

(defn ^:export merge-text
  "Three-way merge of TEXT: `{:merged s}` when it resolves, `{:conflict s}`
  with the overlapping hunks marked when it does not. `base` may be nil (a
  file the other side ADDED has nothing to merge against).

  **Pure, and knows nothing about git.** Three strings in, one out — no
  Repository, no working tree, no disk. That is the whole reason it lives
  here rather than beside the projection: an import from a directory that
  was never in a git repo has to reach the same logic, and slopp WORKS WITH
  git rather than depending on it.

  The algorithm is jgit's — the same one git itself performs, already on the
  classpath because the projection needs jgit for objects and refs.
  Borrowing a battle-tested merge is worth more than owning one: a merge
  that is subtly wrong corrupts a file while reporting success, which is the
  worst failure shape available here.

  Why a whole-file 3-way at all, when a form is slopp's unit: a tracked file
  has no forms, and `:files` is path→text with no sub-file addressing, so the
  whole file IS the store's unit for it. Absorbing one wholesale was
  therefore consistent — and it made two edits to different paragraphs of one
  README a hand-merge for an agent, which is work nothing was gaining from.
  Conflicting hunks still stop, and show the reader the overlap rather than
  the file."
  [base ours theirs]
  (let [b (or base "") o (or ours "") t (or theirs "")]
    (cond
      ;; The trivial cases, short-circuited BEFORE the algorithm — cheaper, and
      ;; total where the algorithm is not: an empty base against an empty side
      ;; is a degenerate input jgit reports as a conflict, and "we changed
      ;; nothing" is not a conflict in any reading.
      (= o t) {:merged o}
      (= o b) {:merged t}
      (= t b) {:merged o}

      :else
      (let [raw (fn [s] (org.eclipse.jgit.diff.RawText. (.getBytes ^String s "UTF-8")))
            res (.merge (org.eclipse.jgit.merge.MergeAlgorithm.)
                        org.eclipse.jgit.diff.RawTextComparator/DEFAULT
                        (raw b) (raw o) (raw t))
            out (java.io.ByteArrayOutputStream.)]
        (.formatMerge (org.eclipse.jgit.merge.MergeFormatter.) out res
                      ["base" "ours" "theirs"]
                      (java.nio.charset.Charset/forName "UTF-8"))
        (let [text (.toString out "UTF-8")]
          (if (.containsConflicts res)
            {:conflict text}
            {:merged text}))))))
