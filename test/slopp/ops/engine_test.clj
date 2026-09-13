(ns slopp.ops.engine-test
  "The write engine's JUDGEMENTS, not its writing.

  `rebased-write!` is exercised everywhere — every edit test in the store runs
  it — so what needs its own tests is the part the engine DECIDES: which tests
  a change impacts (per form, with the declared-coverage union and the
  fall-back when there is no trace), which of those cross the tier boundary,
  when a require change is inert, and when a failed load is healed by replaying
  a namespace this call never touched.

  Those share a failure mode a green suite cannot show you: deciding to run TOO
  LITTLE looks exactly like passing. So they are pinned against hand-built
  stores where the right answer is known by construction.

  Also here: the engine's R6 guard, which is about what the engine may KNOW
  rather than about what it does."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops]
            [slopp.edit :as edit]
            [slopp.store :as store] [slopp.ops.engine :as engine] [slopp.ops.external :as external] [rewrite-clj.parser :as p] [slopp.store.render :as store.render] [slopp.store.db :as db] [rewrite-clj.node :as n] [next.jdbc :as jdbc] [clojure.string :as str]))

(deftest ^:external heal-replays-a-new-namespace-this-call-did-not-touch
  ;; The MERGE shape, and the gap the sibling test below does not cover.
  ;; merge-into-session! hot-loads each namespace in its OWN hot-load-all!
  ;; call, so a namespace the merge created EARLIER is not among this call's
  ;; form-ids. The heal boots from the COMMITTED store — which predates the
  ;; whole merge, nothing having been committed yet — so every namespace the
  ;; merge already loaded is gone, and replaying only THIS call's nses leaves
  ;; the new one missing. The dependent's :require then dies with
  ;; FileNotFound: an error the heal MANUFACTURED. It names a classpath
  ;; problem that never existed and buries the real first failure, which is
  ;; how a merge refusal reads as a merge-ordering bug for hours.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hp2.core "(ns hp2.core)\n\n(defn top \"T.\" [x] x)\n")
      (let [st   (:store @sess)
            st1  (store/ingest st 'hp2.newdep
                               "(ns hp2.newdep)\n\n(defn helper \"H.\" [x] (inc x))\n")
            [st2 _] (store/replace-node
                     st1 'hp2.core 'hp2.core
                     (:node (edit/parse-form
                             "(ns hp2.core (:require [hp2.newdep :as newdep]))")))
            [st3 _] (store/replace-node
                     st2 'hp2.core 'top
                     (:node (edit/parse-form
                             "(defn top \"T.\" [x] (newdep/helper x))")))
            ;; ONLY hp2.core's ids. hp2.newdep is in the candidate but belongs
            ;; to a different call — the one thing that differs from the
            ;; sibling test, and the whole bug.
            ids  [(:id (store/form-named st3 'hp2.core 'hp2.core))
                  (:id (store/form-named st3 'hp2.core 'top))]
            r    (#'engine/hot-load-all! sess st3 ids)]
        (is (not (re-find #"Could not locate" (str (:err r))))
            (str "the heal manufactured a classpath error: " (pr-str r)))
        (is (:healed r) (pr-str r))
        (is (= [3] (ops/query-eval sess "(hp2.core/top 2)"))))
      (finally (ops/close! sess)))))

(deftest ^:external heal-path-replays-candidate-namespaces
  ;; the extract_ns live failure: hot-load-all!'s heal boots a FRESH image
  ;; from the COMMITTED store, so a candidate that CREATES a namespace lost
  ;; it — the parent's (:require new-ns) then hit the classpath and
  ;; FileNotFound'd. The heal must replay the candidate's touched nses from
  ;; the CANDIDATE (dependency order, full load-ns! so *loaded-libs* is
  ;; stamped) before retrying.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'hp.core "(ns hp.core)\n\n(defn top \"T.\" [x] x)\n")
      (let [st   (:store @sess)
            st1  (store/ingest st 'hp.core.impl
                               "(ns hp.core.impl)\n\n(defn helper \"H.\" [x] (inc x))\n")
            [st2 _] (store/replace-node
                     st1 'hp.core 'hp.core
                     (:node (edit/parse-form
                             "(ns hp.core (:require [hp.core.impl :as impl]))")))
            [st3 _] (store/replace-node
                     st2 'hp.core 'top
                     (:node (edit/parse-form
                             "(defn top \"T.\" [x] (impl/helper x))")))
            ;; parent decl FIRST: its require fails (new ns never image-loaded,
            ;; no *loaded-libs* stamp) → simulates any transient first-attempt
            ;; failure → the heal path must recover from the CANDIDATE
            ids  (into [(:id (store/form-named st3 'hp.core 'hp.core))
                        (:id (store/form-named st3 'hp.core 'top))]
                       (mapv :id (store/forms st3 'hp.core.impl)))
            r    (#'engine/hot-load-all! sess st3 ids)]
        (is (:healed r) (pr-str r))
        (is (= [3] (ops/query-eval sess "(hp.core/top 2)"))))
      (finally (ops/close! sess)))))

(deftest external-among-splits-traced-tests-by-tier
  ;; done! already knows PRECISELY which tests a change reaches — that is what
  ;; the trace map is — but only the external tier can execute ^:external ones,
  ;; so the narrowed set must be split before it can be routed.
  ;;
  ;; Routing by require-closure instead (what done! did until #127) selects a
  ;; median 43 of 46 external test namespaces — measured over every source ns
  ;; 2026-07-17 — which blows the cap of 4 and defers 84.6% of the time. The
  ;; evidence was computed four lines above and thrown away.
  (let [st (-> (store/empty-store)
               (store/ingest 'z.core "(ns z.core)\n\n(defn f \"F.\" [x] x)\n")
               (store/ingest 'z.core-test
                             (str "(ns z.core-test (:require [z.core :as c]\n"
                                  "                          [clojure.test :refer [deftest is]]))\n\n"
                                  "(deftest fast-t (is (= 1 (c/f 1))))\n\n"
                                  "(deftest ^:external slow-t (is (= 2 (c/f 2))))\n")))]
    (testing "only the ^:external members come back — the rest already ran in-image"
      (is (= '[z.core-test/slow-t]
             (engine/external-among st '[z.core-test/fast-t z.core-test/slow-t]))))
    (testing "an all-in-image set routes nowhere: there is nothing for the
              external tier to do, which is NOT the same as a silent trace"
      (is (empty? (engine/external-among st '[z.core-test/fast-t]))))))

(deftest impacted-external-expands-untraced-forms-per-form
  ;; #127 gave the external tier trace-narrowing but kept the all-or-nothing
  ;; collapse: ONE untraced form made the answer nil and done! fell back to the
  ;; require-closure of EVERYTHING. #132 dissolves the silence per form: an
  ;; untraced form contributes its own namespace's reach, so the answer is
  ;; never nil — [] genuinely means no external test can be affected.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'z.core "(ns z.core)\n\n(defn f \"F.\" [x] x)\n\n(defn g \"G.\" [x] x)\n")
                 (store/ingest 'z.core-test
                               (str "(ns z.core-test (:require [z.core :as c]\n"
                                    "                          [clojure.test :refer [deftest is]]))\n\n"
                                    "(deftest fast-t (is (= 1 (c/f 1))))\n\n"
                                    "(deftest ^:external slow-t (is (= 2 (c/f 2))))\n")))
        sess (atom {:store st
                    :test-map {'z.core-test/fast-t #{'z.core/f}
                               'z.core-test/slow-t #{'z.core/f}}})
        fid  (:id (store/form-named st 'z.core 'f))
        gid  (:id (store/form-named st 'z.core 'g))]
    (testing "traced: only the ^:external half routes out — fast-t already ran in-image"
      (is (= '[z.core-test/slow-t] (engine/impacted-external sess st [fid]))))
    (testing "an UNTRACED form expands to its namespace's reach instead of
              collapsing the whole answer to nil"
      (is (= '[z.core-test/slow-t] (engine/impacted-external sess st [gid]))))
    (testing "mixed: the union, still never nil"
      (is (= '[z.core-test/slow-t] (engine/impacted-external sess st [fid gid]))))))

(deftest affected-tests-adds-declared-coverage-to-the-narrowed-set
  ;; ^{:covers} declares a test that reaches a form through a dispatch/data
  ;; path the tracer can't see. Such a test leaves NO trace evidence, so a
  ;; trace-narrowed result would drop it. Declared coverage is ADDED to any
  ;; non-nil narrowing (never narrows on its own — a declaration is a floor,
  ;; not a ceiling; nil already runs everything, declared included).
  (let [st (-> (store/empty-store)
               (store/ingest 'f.core "(ns f.core)\n(defn f [x] x)\n")
               (store/ingest 'f.t
                             (str "(ns f.t (:require [clojure.test :refer [deftest is]]))\n"
                                  "(deftest traced (is (= 1 (f.core/f 1))))\n"
                                  "(deftest ^{:covers \"f.core/f — dispatch path\"} declared-cover (is true))\n")))
        sess (atom {:store st :test-map {'f.t/traced #{'f.core/f}}})]
    (testing "the declared-coverage test is unioned into the trace-narrowed set"
      (is (= '[f.t/declared-cover f.t/traced]
             (vec (sort (engine/affected-tests sess 'f.core 'f))))))))

(deftest affected-tests-consults-every-name-and-refuses-opaque-bodies
  ;; Two consequences of D8 land here. (1) Evidence arrives keyed by the VAR
  ;; that ran — a test calling protocol method m records p.core/m — but the
  ;; form DEFINING m is the defprotocol, primary name P. Looking up only P
  ;; missed all of it. (2) defrecord/deftype method bodies and defmethod
  ;; bodies run where the tracer cannot fully see them (inline bodies compile
  ;; to class methods; the external tier records methods at multi grain), so
  ;; their evidence is PARTIAL — and narrowing on partial evidence is the
  ;; false-green shape. Those forms never narrow: nil means the caller falls
  ;; back to the closure, exactly as if the trace were silent.
  ;;
  ;; The test-map mirrors what instrument! actually writes: a dispatched call
  ;; records the multi ALWAYS, plus the method's form key when known — so
  ;; method evidence without multi evidence cannot occur. Form ids are derived,
  ;; not hardcoded: ids are store-global, and an earlier draft of this test
  ;; hardcoded f2 — which was a form in the OTHER namespace.
  (let [st (-> (store/empty-store)
               (store/ingest 'p.core
                             (str "(ns p.core)\n\n"
                                  "(defprotocol P \"P.\" (m [_] \"M.\") (n [_] \"N.\"))\n\n"
                                  "(defrecord R [x] P (m [_] 1) (n [_] 2))\n"))
               (store/ingest 'dm.core
                             (str "(ns dm.core)\n\n(defmulti area :shape)\n\n"
                                  "(defmethod area :square [s] 1)\n")))
        meth-id (symbol (:id (first (filter #(nil? (:name %))
                                            (store/forms st 'dm.core)))))
        sess (atom {:store st
                    :test-map {'p.t/proto-t  #{'p.core/m}
                               'p.t/ctor-t   #{'p.core/->R}
                               'dm.t/multi-t #{'dm.core/area}
                               'dm.t/meth-t  #{'dm.core/area
                                               (symbol "dm.core" (str meth-id))}}})]
    (testing "a defprotocol form NEVER narrows — found red (2026-07-17): its
              method vars ARE wrapped, but a protocol call site's inline cache
              hits the interface DIRECTLY for inline impls (the common case),
              bypassing the var. Evidence through the var exists only for
              extend-based dispatch, so it is partial, and partial must not
              select. The synthetic p.core/m evidence here is exactly the kind
              a real run might NOT produce."
      (is (nil? (engine/affected-tests sess 'p.core 'P))))
    (testing "a defrecord form NEVER narrows — its method bodies are invisible
              to the tracer, so ->R evidence alone would under-select"
      (is (nil? (engine/affected-tests sess 'p.core 'R))))
    (testing "a defmethod form never narrows — the external tier records it at
              multi grain, and partial evidence must not select"
      (is (nil? (engine/affected-tests sess 'dm.core meth-id))))
    (testing "the defmulti itself narrows: every dispatched call records it, in
              both tiers, so its evidence is complete"
      (is (= '[dm.t/meth-t dm.t/multi-t]
             (vec (sort (engine/affected-tests sess 'dm.core 'area))))))))

(deftest inert-require-respects-transitive-loads-and-metadata
  ;; review V-F1/V-F2: inert-ns-require-change? classified as inert two edits
  ;; that CAN change behaviour, so `done` skipped external tests it should
  ;; run (false-green — the class slopp most guards against).
  (let [st (-> (store/empty-store)
               (store/ingest 'tr.deep (str "(ns tr.deep)\n\n(defmulti area :kind)\n\n"
                                           "(defmethod area :sq [s] s)\n"))
               ;; tr.leaf has NO methods of its own, but REQUIRES tr.deep,
               ;; whose LOAD registers a defmethod — not inert transitively
               (store/ingest 'tr.leaf "(ns tr.leaf (:require [tr.deep :as d]))\n\n(defn g [x] x)\n")
               (store/ingest 'tr.b "(ns tr.b)\n\n(defn h \"H.\" [x] x)\n"))
        edit (fn [src] (first (store/replace-node st 'tr.b 'tr.b
                                                  (p/parse-string src) :prompt "t")))
        fid  (:id (store/form-named st 'tr.b 'tr.b))
        ;; the baseline is the caller's to name — the ns form as it stood
        ;; before the edit, exactly what the write path reads from the journal
        before (fn [store fid] (n/string (:node (store/form-by-id store fid))))]
    (testing "V-F1: adding a method-free lib whose CLOSURE loads defmethods is NOT inert"
      (let [st' (edit "(ns tr.b (:require [tr.leaf :as l]))")]
        (is (not (engine/inert-ns-require-change? st' fid (before st fid))))))
    (testing "a genuinely quiet leaf (no methods anywhere in its closure) stays inert"
      (let [st2 (store/ingest st 'tr.quiet "(ns tr.quiet)\n\n(defn q [x] x)\n")
            fid2 (:id (store/form-named st2 'tr.b 'tr.b))
            st' (first (store/replace-node st2 'tr.b 'tr.b
                                           (p/parse-string "(ns tr.b (:require [tr.quiet :as q]))")
                                           :prompt "t"))]
        (is (engine/inert-ns-require-change? st' fid2 (before st2 fid2)))))
    (testing "V-F2: an ns-NAME metadata change bundled with an alias add is NOT inert"
      (let [st' (edit "(ns ^:no-doc tr.b (:require [tr.quiet :as q]))")]
        ;; tr.quiet doesn't exist in `st` here, but the metadata change alone
        ;; must defeat inertness regardless
        (is (not (engine/inert-ns-require-change? st' fid (before st fid))))))))

(deftest an-endpoint-selects-the-tests-that-drive-its-route
  (let [s (store/ingest (store/empty-store) 'shop.api
                        (str "(ns shop.api)\n\n"
                             "(defn ^{:http/method :get :http/path \"/todos\" :http/auth :public} todos \"T.\" [req] req)\n"))
        s (store/ingest s 'shop.api-test
                        (str "(ns shop.api-test)\n\n"
                             "(deftest listing (handle! ctx {:request-method :get :uri \"/todos\"}))\n\n"
                             "(deftest unrelated (is (= 1 1)))\n"))
        sess (atom {:store s :test-map {}})]
    (testing "with no trace evidence, the route join still names the covering test"
      (is (= '[shop.api-test/listing]
             (engine/affected-tests sess 'shop.api 'todos))))
    (testing "a non-endpoint form with no evidence still returns nil (run everything)"
      (is (nil? (engine/affected-tests sess 'shop.api-test 'unrelated-missing))))
    (testing "recorded TRACE evidence still wins — the join is a fallback, not an override"
      (let [sess (atom {:store s
                        :test-map '{shop.api-test/unrelated #{shop.api/todos}}})]
        (is (= '[shop.api-test/unrelated]
               (engine/affected-tests sess 'shop.api 'todos)))))))

(deftest a-heal-that-changed-the-error-reports-both
  ;; D-surface-honesty: when the heal's retry fails DIFFERENTLY from the
  ;; first attempt, the post-heal error is an artifact of the recovery and
  ;; the pre-heal one is the fault. Reporting only :err is how a merge
  ;; refusal pointed at a classpath that was never the problem.
  (testing "no heal, or an unchanged error — the message is just the error"
    (is (nil? (engine/load-error-message nil)))
    (is (nil? (engine/load-error-message {:healed true})))
    (is (= "boom" (engine/load-error-message {:err "boom"}))))
  (testing "a heal that changed the error carries the pre-heal one too"
    (let [msg (engine/load-error-message {:err "post" :first-err "pre"})]
      (is (re-find #"post" msg))
      (is (re-find #"pre" msg))
      (is (re-find #"(?i)before" msg)
          "says which of the two came first"))))

(deftest ^:external the-write-engine-names-no-app-type
  ;; R6: no slopp.* surface may assume a project is a web project. The write
  ;; engine is the most generic surface slopp has — every edit verb hands it a
  ;; pure transform — and it carried five forms of ClojureScript BUNDLE
  ;; machinery, wired into add-form!/edit-replace!/ingest! so that every write
  ;; in the system asked whether there was a client to recompile.
  ;;
  ;; It reached the client build through (store/late-ref 'slopp.api.cljs/…),
  ;; and the reason is the tell: a STATIC require would have cycled, because
  ;; the client build requires the operation surface that calls the engine. The
  ;; ^:unsafe escape hatch existed only to hold a misplacement together.
  ;;
  ;; Why this is a named test and not a layering rule: layering is a
  ;; MODULE-grain question, and both namespaces are in module slopp.api. The
  ;; drawer was hiding the violation from the check built to find it. When the
  ;; client build leaves for its own module this becomes an ordinary layering
  ;; finding — which is the point of the split, and this test survives the move
  ;; as the specific statement of it.
  (let [st  (external/built-store)
        src (store.render/render-ns st 'slopp.ops.engine)]
    (testing "there is a population — the vacuity that ate a sibling guard"
      (is (< 50 (count (:namespaces st))))
      (is (re-find #"rebased-write!" src)
          "rendered the wrong namespace, or rendered nothing"))
    (testing "the write engine names no client-build namespace, by any path"
      ;; require, qualified ref, late-ref target and prose all read the same
      ;; here on purpose: an engine whose DOCS explain bundle recompilation
      ;; still knows about clients.
      ;; The pattern spelled `slopp.api.cljs` until the :stale-pattern rule
      ;; found it: phase 3 renamed that namespace to slopp.webdev.cljs, and a
      ;; search pattern is DATA, so no rename verb rewrote it and the prose
      ;; sweep missed the escaped dots. The assertion went on passing against a
      ;; string that could no longer occur.
      ;;
      ;; Which is why the control below exists. The two `is` forms above prove
      ;; the HAYSTACK is real — a rendered namespace, over 50 namespaces in the
      ;; store — and neither of them can prove the NEEDLE still matches
      ;; anything. A population control is not a pattern control.
      (is (seq (re-seq #"slopp\.webdev\.cljs" (store.render/render-ns st 'slopp.webdev.cljs)))
          "the pattern no longer matches a namespace that names itself, so the absence below proves nothing")
      (is (= [] (vec (re-seq #"slopp\.webdev\.cljs" src)))
          "the offending mentions are the failure value"))))

(deftest a-verdicts-closure-is-identified-by-CONTENT-not-by-when-it-ran
  ;; `verdict-cache.md` has been DESIGNED and unbuildable since 2026-07-22 for
  ;; one reason: a verdict had nothing to be keyed to. The observation delta is
  ;; that key now, and this is the value it carries — the content identity of
  ;; everything a verdict for a test namespace depends on.
  ;;
  ;; Two properties decide whether reuse is ever SOUND, and they pull opposite
  ;; ways, so both are pinned here. Miss the first and a stale green survives a
  ;; real change; miss the second and the hash changes constantly and answers
  ;; nothing.
  (let [st (-> (store/empty-store)
               (store/ingest 'cl.dep "(ns cl.dep)\n(defn helper [] 1)\n")
               (store/ingest 'cl.core "(ns cl.core (:require [cl.dep :as d]))\n(defn f [] (d/helper))\n")
               (store/ingest 'cl.other "(ns cl.other)\n(defn unrelated [] 99)\n")
               (store/ingest 'cl.core-test
                             (str "(ns cl.core-test (:require [clojure.test :refer [deftest is]]\n"
                                  "                            [cl.core :as c]))\n"
                                  "(deftest t (is (= 1 (c/f))))\n")))
        h  (fn [s] (get (engine/closure-hashes s '[cl.core-test]) 'cl.core-test))
        h0 (h st)]
    (testing "the same store hashes the same — a key recomputed is a key"
      (is (= h0 (h st))))
    (testing "a real DIGEST, not clojure.core/hash: this value is persisted in
              the journal and compared across processes, and `currency/hash-of`
              says in its own docstring that it is in-process only"
      (is (re-matches #"[0-9a-f]{64}" h0) (pr-str h0)))
    (testing "editing a namespace the test can REACH changes it — two hops away,
              through cl.core, which is where a naive one-hop key goes wrong"
      (let [st' (store/ingest st 'cl.dep "(ns cl.dep)\n(defn helper [] 2)\n")]
        (is (not= h0 (h st')))))
    (testing "editing a namespace the test canNOT reach does not — without this
              the key is just a store version and no verdict is ever reusable"
      (let [st' (store/ingest st 'cl.other "(ns cl.other)\n(defn unrelated [] 100)\n")]
        (is (= h0 (h st')))))
    (testing "and the dependency manifest counts: the same sources against
              different libraries are not the same verdict"
      (is (not= h0 (h (assoc st :deps {'org.clojure/data.json {:mvn/version "2.5.0"}})))))
    (testing "every namespace asked about gets an answer, and they differ"
      (let [m (engine/closure-hashes st '[cl.core-test cl.core cl.other])]
        (is (= '#{cl.core-test cl.core cl.other} (set (keys m))))
        (is (= 3 (count (set (vals m)))))))))

(deftest went-green-never-guesses-from-a-TRUNCATED-failure-list
  ;; Friction 4 of the capabilities wave, mechanism confirmed here. A write
  ;; reported `:went-green [… a-rule-owned-by-an-app-type-is-named-for-it …]`
  ;; and an immediate `test_run` on that same test showed three assertions
  ;; still failing, with the message it had before the write.
  ;;
  ;; The cause: the runner caps failure DETAIL at 20 blocks (a response-size
  ;; bound, and a right one), and `shape-episode-reds!` derived "still red"
  ;; from those blocks. On a run with more than 20 failing assertions every
  ;; previously-red test past the cap was therefore reported as having gone
  ;; GREEN.
  ;;
  ;; **A false green is the worst direction for this signal** — it is the one
  ;; an agent reads to decide it is finished — and it misfires only on large
  ;; red runs, which is exactly when the reader most needs it. It is also the
  ;; shape this namespace exists for: deciding wrongly looks like passing.
  ;;
  ;; The fix has two halves because the runner and the shaper SHIP SEPARATELY.
  ;; The runner carries every failing test's NAME (cheap — symbols, not
  ;; messages), and the shaper refuses to compute greens when handed a summary
  ;; without them whose detail it can see was capped. The injected runtime lags
  ;; the store by design — it is read off the reading process's classpath — so
  ;; the second half is the state of every session between a kernel edit and a
  ;; rebuild, not a hypothetical.
  (let [prev  '#{p.core-test/one p.core-test/two}
        shape (fn [summary]
                (engine/shape-episode-reds!
                 (atom {:episode-reds prev}) summary
                 ;; `affected` is the set of tests that RAN, qualified — a
                 ;; namespace here would make every case vacuous, since no
                 ;; previously-red test would be considered at all
                 '[p.core-test/one p.core-test/two] nil false))]

    (testing "a COMPLETE failure list still reports a real green"
      (let [r (shape {:test 2 :pass 0 :fail 1 :error 0
                      :failures [{:test 'p.core-test/one :type :fail}]})]
        (is (= '[p.core-test/two] (:went-green r)) (pr-str r))
        (is (= '[p.core-test/one] (:still-red r)) (pr-str r))))

    (testing "a TRUNCATED one claims no greens at all"
      ;; 40 failing assertions, 20 blocks: `two` is absent from the detail
      ;; because the CAP dropped it, which is indistinguishable from passing
      (let [r (shape {:test 2 :pass 0 :fail 40 :error 0
                      :failures (vec (repeat 20 {:test 'p.core-test/one :type :fail}))})]
        (is (nil? (:went-green r))
            (str "a test absent from a capped list has not been observed to "
                 "pass — it has not been observed: " (pr-str r)))
        (is (re-find #"(?i)capped|truncat" (str (:reds-uncertain r)))
            (str "and the silence says why, or the reader infers the wrong "
                 "cause from the same absence: " (pr-str r)))))

    (testing "names beat blocks: given them, the cap stops mattering"
      (let [r (shape {:test 2 :pass 0 :fail 40 :error 0
                      :failed-tests '[p.core-test/two]
                      :failures (vec (repeat 20 {:test 'p.core-test/one :type :fail}))})]
        (is (= '[p.core-test/one] (:went-green r)) (pr-str r))
        (is (= '[p.core-test/two] (:still-red r)) (pr-str r))
        (is (nil? (:reds-uncertain r))
            (str "nothing is uncertain once the names are complete: " (pr-str r)))))))

(deftest affected-tests-includes-the-tests-that-read-a-forms-MARKERS
  ;; Found in production by slopp-ui, migrating `:web/spa` → `:webapp/client-routes`:
  ;;
  ;;   edit_subform hub/project-root  →  {:ran 2, :pass 18, :status :green}
  ;;
  ;; **Green, on the edit that broke three assertions.** The tests that break
  ;; read the marker off metadata — and a test that reads a var's METADATA
  ;; never CALLS it, so there is no trace edge and no static reference for
  ;; anything to follow. Meanwhile other tests DID have trace evidence, so the
  ;; set narrowed to those and the readers fell out.
  ;;
  ;; It breaks the rule the whole narrowing rests on — PARTIAL EVIDENCE MUST
  ;; NOT SELECT — arriving through a path the existing guard does not cover.
  ;; `method-carrying?` forms never narrow because their bodies run where the
  ;; tracer cannot see; a MARKED form has the same problem one level out,
  ;; because its declaration is READ rather than executed.
  ;;
  ;; The fix is a UNION, deliberately: it can only ever add tests, so it cannot
  ;; create the failure it is fixing. Same shape as `^{:covers}` — a floor,
  ;; never a ceiling.
  (let [st (-> (store/empty-store)
               (store/ingest 'f.core
                             (str "(ns f.core)\n"
                                  "(defn ^{:http/path \"/x\" :http/method :get} page [_] {})\n"))
               (store/ingest 'f.core-test
                             (str "(ns f.core-test (:require [clojure.test :refer [deftest is]]))\n"
                                  "(deftest traced (is (map? (f.core/page {}))))\n"
                                  "(deftest reads-the-marker\n"
                                  "  (is (= \"/x\" (:http/path (meta #'f.core/page)))))\n")))
        sess (atom {:store st :test-map {'f.core-test/traced #{'f.core/page}}})]

    (testing "the marker-reading test is unioned into the trace-narrowed set"
      (is (= '[f.core-test/reads-the-marker f.core-test/traced]
             (vec (sort (engine/affected-tests sess 'f.core 'page))))
          "a test that reads a declaration has no call edge to it — narrowing on
           trace alone reports green while the declaration it asserts is gone"))

    (testing "a test that never names the form is STILL affected, and must be"
      ;; The case that decided the rule. slopp-ui's second reader is a SWEEP —
      ;; `(mapcat #(:web/spa (meta %)) vars)` over ns-publics — so it names no
      ;; form at all and is affected by ANY form gaining or losing that marker.
      ;; Keying the union on the form's NAME would have caught two of their
      ;; three readers and missed exactly the one that makes a declaration
      ;; checkable store-wide.
      (let [st2  (store/ingest st 'f.sweep-test
                               (str "(ns f.sweep-test (:require [clojure.test :refer [deftest is]]))\n"
                                    "(deftest every-page-has-a-path\n"
                                    "  (is (every? :http/path (map meta (vals (ns-publics 'f.core))))))\n"))
            sess (atom {:store st2 :test-map {'f.core-test/traced #{'f.core/page}}})]
        (is (contains? (set (engine/affected-tests sess 'f.core 'page))
                       'f.sweep-test/every-page-has-a-path))))

    (testing "but a marker this form does NOT carry pulls in nothing"
      ;; the union is keyed on the marks this form actually has, or it degrades
      ;; to running every test that mentions any slopp keyword. The accepted
      ;; imprecision is one notch narrower: a test merely USING `:http/path` as
      ;; data is indistinguishable from one reading it off metadata, and gets
      ;; included. That costs time and never correctness, which is the trade a
      ;; union is allowed to make
      (let [st2  (store/ingest st 'f.other-test
                               (str "(ns f.other-test (:require [clojure.test :refer [deftest is]]))\n"
                                    "(deftest unrelated\n"
                                    "  (is (= :public (:http/auth {:http/auth :public}))))\n"))
            sess (atom {:store st2 :test-map {'f.core-test/traced #{'f.core/page}}})]
        (is (not (contains? (set (engine/affected-tests sess 'f.core 'page))
                            'f.other-test/unrelated))
            ":http/auth is not a marker this form carries")))
(testing "MENTIONING a marker is not READING one, and that is the whole cost"
      ;; The second condition, and without it this producer is unusable.
      ;; Measured over slopp's own store — the worst case, because slopp IS the
      ;; machinery that tests markers — `:http/path` is mentioned by 74 test
      ;; forms and read off metadata by 3; `:http/method` by 69 and 1. Only 14
      ;; of 1457 test forms read metadata at all.
      ;;
      ;; Unconditioned, an endpoint edit would union in a tenth of the suite,
      ;; ^:external tests included, at a JVM each — for forms whose declaration
      ;; nothing asserts. A union may cost time; it may not cost that much time
      ;; for nothing.
      (let [st2  (store/ingest st 'f.fixture-test
                               (str "(ns f.fixture-test (:require [clojure.test :refer [deftest is]]))\n"
                                    "(deftest builds-a-route-fixture\n"
                                    "  (is (= :get (:http/method {:http/path \"/x\" :http/method :get}))))\n"))
            sess (atom {:store st2 :test-map {'f.core-test/traced #{'f.core/page}}})]
        (is (not (contains? (set (engine/affected-tests sess 'f.core 'page))
                            'f.fixture-test/builds-a-route-fixture))
            "it uses the keywords as ordinary data and asserts nothing about
             any form's declaration")))

    (testing "an unmarked form still narrows exactly as before"
      ;; the cost lands only on forms carrying a declaration, which is what
      ;; keeps an ordinary defn write as fast as it was
      (let [st2  (-> (store/empty-store)
                     (store/ingest 'g.core "(ns g.core)\n(defn g [x] x)\n")
                     (store/ingest 'g.core-test
                                   (str "(ns g.core-test (:require [clojure.test :refer [deftest is]]))\n"
                                        "(deftest traced (is (= 1 (g.core/g 1))))\n"
                                        "(deftest elsewhere (is (= :http/path :http/path)))\n")))
            sess (atom {:store st2 :test-map {'g.core-test/traced #{'g.core/g}}})]
        (is (= '[g.core-test/traced]
               (vec (sort (engine/affected-tests sess 'g.core 'g)))))))))

(deftest a-red-says-whether-it-is-mine-or-merely-present
  ;; The measured deadlock: `implicate` computes the correlation and then
  ;; collapses two different answers into one absence. A failing test whose
  ;; trace is KNOWN and disjoint from my edits is SOMEONE ELSE'S red; a
  ;; failing test with NO trace is a red nobody can attribute at all. Both
  ;; arrived as "the :implicated key is missing", so the only safe reading
  ;; was "assume it is mine" — which is what freezes one agent's thread
  ;; behind another agent's red.
  (let [summary {:fail 3
                 :failures [{:test 'p.core-test/mine-t}
                            {:test 'p.core-test/theirs-t}
                            {:test 'p.core-test/nobody-knows-t}]}
        tmap    {'p.core-test/mine-t   ['p.core/f]
                 'p.core-test/theirs-t ['p.core/g]}
        by-test (into {} (map (juxt :test identity))
                      (:failures (engine/implicate summary tmap #{'p.core/f})))]
    (testing "a failure that exercises an edited form is mine, and names it"
      (is (= ['p.core/f] (:implicated (by-test 'p.core-test/mine-t))))
      (is (= :mine (:attribution (by-test 'p.core-test/mine-t)))))
    (testing "a TRACED failure disjoint from my edits is someone else's"
      (is (= :foreign (:attribution (by-test 'p.core-test/theirs-t))))
      (is (nil? (:implicated (by-test 'p.core-test/theirs-t)))))
        (testing "an UNTRACED failure is evidence of neither — and must not read as foreign"
      (is (= :untraced (:attribution (by-test 'p.core-test/nobody-knows-t)))))
    (testing "a failing test the episode WROTE is its own red, trace or no trace"
      ;; the trace maps a test to the SOURCE it exercises, so it can never
      ;; name the test itself. Red-first — write the failing test, then make
      ;; it pass — edits only the test, and reading the trace alone called
      ;; that somebody else's red.
      (let [f (first (:failures (engine/implicate
                                 {:fail 1 :failures [{:test 'p.core-test/theirs-t}]}
                                 tmap
                                 #{'p.core-test/theirs-t})))]
        (is (= :mine (:attribution f)))
        (is (= ['p.core-test/theirs-t] (:implicated f)))))))

(defn- journaled!
  "A session over `st` WITH a journal: `st`'s whole delta log appended to a
  fresh store on disk, the trunk as the session's line, `test-map` as its
  trace evidence. The engine reads baselines (`db/sources-at`) from the
  journal rather than the value, so a fixture that judges an episode against
  a done has to put that done on disk. The caller closes the connection."
  [st test-map]
  (let [dir   (str (java.nio.file.Files/createTempDirectory
                    "slopp-engine" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn  (slopp.store.db/open! dir)
        trunk (slopp.store.db/trunk-line-id! conn)]
    (slopp.store.db/append! conn st (store/deltas st) (vec (keys (:namespaces st))) trunk nil)
    (atom {:store (store/committed st) :db conn :line trunk :test-map test-map})))

(deftest impacted-tests-falls-back-per-form-not-globally
  ;; THE collapse (measured 2026-07-17): done! discarded ALL narrowing when ONE
  ;; changed form had no trace evidence — (when (not-any? nil? per) ...). An ns
  ;; form can never be traced and ns_add_require edits one, so 54.4% of real
  ;; episodes (43.2% via ns forms alone) reverted to whole-closure runs, and
  ;; the evidence for every OTHER form in the episode was thrown away.
  ;;
  ;; Per-form is equally sound and far less pessimistic: an untraced form
  ;; contributes every test whose require-closure reaches ITS namespace — the
  ;; same tests the global fallback would run FOR THAT FORM, since
  ;; test-nses-reaching over a union of nses is the union of the per-ns calls —
  ;; while a traced form keeps contributing exactly its observed tests.
  (let [st (-> (store/empty-store)
               (store/ingest 'pf.a "(ns pf.a)\n\n(defn f \"F.\" [x] x)\n\n(defn g \"G.\" [x] x)\n")
               (store/ingest 'pf.b "(ns pf.b)\n\n(defn h \"H.\" [x] x)\n")
               (store/ingest 'pf.a-test
                             (str "(ns pf.a-test (:require [pf.a :as a]\n"
                                  "                        [clojure.test :refer [deftest is]]))\n\n"
                                  "(deftest f-t (is (= 1 (a/f 1))))\n\n"
                                  "(deftest g-t (is (= 1 (a/g 1))))\n"))
               (store/ingest 'pf.b-test
                             (str "(ns pf.b-test (:require [pf.b :as b]\n"
                                  "                        [clojure.test :refer [deftest is]]))\n\n"
                                  "(deftest h-t (is (= 1 (b/h 1))))\n")))
        sess (journaled! st {'pf.a-test/f-t #{'pf.a/f}
                             'pf.a-test/g-t #{'pf.a/g}
                             'pf.b-test/h-t #{'pf.b/h}})
        fid  (fn [nsx nm] (:id (store/form-named st nsx nm)))]
    (try
      (testing "all-traced: exactly the evidence, nothing else"
        (is (= '[pf.a-test/f-t]
               (engine/impacted-tests sess st [(fid 'pf.a 'f)]))))
      (testing "an untraced form (pf.b's NS FORM — the 43.2% case) expands to the
                tests reaching ITS namespace only"
        (is (= '[pf.b-test/h-t]
               (engine/impacted-tests sess st [(fid 'pf.b 'pf.b)]))))
      (testing "mixed episode: the traced form KEEPS its narrow set — g-t, whose
                subject was not touched, is not dragged in by pf.b's ns form"
        (is (= '[pf.a-test/f-t pf.b-test/h-t]
               (engine/impacted-tests sess st [(fid 'pf.a 'f) (fid 'pf.b 'pf.b)]))))
      (testing "a deleted fid is skipped, not an error"
        (is (= '[pf.a-test/f-t]
               (engine/impacted-tests sess st [(fid 'pf.a 'f) "f999"]))))
      (finally (.close ^java.sql.Connection (:db @sess))))))

(deftest an-alias-only-require-addition-impacts-nothing
  ;; frictions #2: ns_add_require on a hub namespace invalidated every
  ;; external test in its closure (331 on slopp.api) though an added alias
  ;; changes the resolution of NOTHING. The ns-form fallback is now
  ;; SEMANTIC — an added-requires-only diff, alias-only specs, each added
  ;; ns in-store with no method-carrying forms → zero impacted tests.
  ;; Everything else keeps the conservative whole-closure fallback.
  ;;
  ;; The prior source is read from the JOURNAL, so each candidate edit is
  ;; journaled before the engine judges it.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'ir.a "(ns ir.a)\n\n(defn f \"F.\" [x] x)\n")
                 (store/ingest 'ir.m (str "(ns ir.m)\n\n(defmulti area :kind)\n\n"
                                          "(defmethod area :sq [s] s)\n"))
                 (store/ingest 'ir.b (str "(ns ir.b (:require [clojure.string :as s]))\n\n"
                                          "(defn h \"H.\" [x] (s/trim x))\n"))
                 (store/ingest 'ir.b-test
                               (str "(ns ir.b-test (:require [ir.b :as b]\n"
                                    "                        [clojure.test :refer [deftest is]]))\n\n"
                                    "(deftest h-t (is (= \"1\" (b/h \" 1 \"))))\n")))
        tm   {'ir.b-test/h-t #{'ir.b/h}}
        edit (fn [src] (first (store/replace-node st 'ir.b 'ir.b
                                                  (p/parse-string src)
                                                  :prompt "t")))
        fid  (:id (store/form-named st 'ir.b 'ir.b))
        judge (fn [st']
                (let [sess (journaled! st' tm)]
                  (try (engine/impacted-tests sess st' [fid])
                       (finally (.close ^java.sql.Connection (:db @sess))))))]
    (testing "adding an alias-only in-store require impacts nothing"
      (is (= [] (judge (edit (str "(ns ir.b (:require [clojure.string :as s]\n"
                                  "                   [ir.a :as a]))"))))))
    (testing "an added :refer is NOT inert — it can change resolution"
      (is (= '[ir.b-test/h-t]
             (judge (edit (str "(ns ir.b (:require [clojure.string :as s]\n"
                               "                   [ir.a :refer [f]]))"))))))
    (testing "an added ns carrying defmethods is NOT inert — loading registers"
      (is (= '[ir.b-test/h-t]
             (judge (edit (str "(ns ir.b (:require [clojure.string :as s]\n"
                               "                   [ir.m :as m]))"))))))
    (testing "an out-of-store lib is NOT inert — load effects unknown"
      (is (= '[ir.b-test/h-t]
             (judge (edit (str "(ns ir.b (:require [clojure.string :as s]\n"
                               "                   [clojure.set :as cset]))"))))))
    (testing "a require REMOVAL is NOT inert"
      (is (= '[ir.b-test/h-t] (judge (edit "(ns ir.b)")))))))

(deftest impacted-tests-diffs-against-the-last-done-not-the-newest-delta
  ;; review V-F3: an episode with TWO ns edits — the first adds a :refer
  ;; (behaviour-changing), the second an alias-only require. Judged against
  ;; the NEWEST delta's prior, the alias edit looks inert and the :refer is
  ;; masked → done skips the tests. It must diff against the LAST-DONE
  ;; baseline, where the whole episode's net change (incl. the :refer) shows.
  (let [st0 (-> (store/empty-store)
                (store/ingest 'ep.a "(ns ep.a)\n\n(defn f \"F.\" [x] x)\n")
                (store/ingest 'ep.q "(ns ep.q)\n\n(defn q \"Q.\" [x] x)\n")
                (store/ingest 'ep.b "(ns ep.b)\n\n(defn h \"H.\" [x] x)\n")
                (store/ingest 'ep.b-test
                              (str "(ns ep.b-test (:require [ep.b :as b]\n"
                                   "                        [clojure.test :refer [deftest is]]))\n\n"
                                   "(deftest h-t (is (= 1 (b/h 1))))\n")))
        st1 (first (store/record-done st0 "baseline"))
        ;; edit 1: a :refer (non-inert)
        st2 (first (store/replace-node st1 'ep.b 'ep.b
                                       (p/parse-string "(ns ep.b (:require [ep.a :refer [f]]))")
                                       :prompt "add refer"))
        ;; edit 2: alias-only, quiet
        st3 (first (store/replace-node st2 'ep.b 'ep.b
                                       (p/parse-string "(ns ep.b (:require [ep.a :refer [f]] [ep.q :as q]))")
                                       :prompt "add alias"))
        ;; the baseline is read from the JOURNAL now, so the done has to be
        ;; on disk — a journaled session, not a bare atom
        sess (journaled! st3 {'ep.b-test/h-t #{'ep.b/h}})
        fid  (:id (store/form-named st3 'ep.b 'ep.b))]
    (try
      (testing "the episode's net ns change includes a :refer → NOT inert, tests selected"
        (is (= '[ep.b-test/h-t] (engine/impacted-tests sess st3 [fid]))))
      (finally (.close ^java.sql.Connection (:db @sess))))))

(deftest history-is-a-floor-under-trace-evidence
  ;; Measured on this store's own journal (2026-08-29): history-only
  ;; selection — the tests that went red beside a changed form before —
  ;; recalls 38% of failing tests at form grain, 48% with the namespace
  ;; grain, against a 95% bar. So history does not SELECT; it is a FLOOR,
  ;; unioned in on the same terms as a `^:covers` declaration: it can only
  ;; ever add tests, and what it adds is exactly what a trace cannot see —
  ;; a test that reads a marker, reaches the form through a dispatch or an
  ;; HTTP route, and went red beside it anyway.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'hf.core "(ns hf.core)\n(defn f [] 1)\n")
                 (store/ingest 'hf.core-test
                               (str "(ns hf.core-test (:require [clojure.test :refer [deftest is]]))\n"
                                    "(deftest traced (is true))\n"
                                    "(deftest reader (is true))\n")))
        sess (journaled! st {'hf.core-test/traced #{'hf.core/f}})
        fid  (:id (store/form-named st 'hf.core 'f))]
    (try
      (testing "with no history, trace evidence alone selects"
        (is (= '[hf.core-test/traced] (engine/affected-tests sess 'hf.core 'f))))
      (jdbc/execute! (:db @sess) ["INSERT INTO form_reds (form_id, test, n, last_delta)
                                   VALUES (?,?,1,'d-red')" fid "hf.core-test/reader"])
      (testing "a test that went red beside the form is unioned in, once"
        (is (= '[hf.core-test/reader hf.core-test/traced]
               (engine/affected-tests sess 'hf.core 'f))))
      (testing "a form with NO trace evidence still falls back to the closure — history never narrows"
        (is (nil? (engine/affected-tests sess 'hf.core-test 'reader))
            "no evidence about the test form itself: nil, so the caller runs the namespace"))
      (finally (.close (:db @sess))))))

(deftest ^:external a-replayed-add-renders-in-the-derived-order-not-at-the-end
  ;; The writer arranges definitions before callers at every write. A session
  ;; that absorbs those writes by INCREMENTAL REPLAY appends the added form
  ;; and trusts the derived order at render time — and rendered the helper
  ;; AFTER its caller three times tonight, each found by the dev daemon's
  ;; cold boot, which renders from a foreign session's value: `Unable to
  ;; resolve symbol`. Both sessions must render the same bytes; a fresh open
  ;; (from rows) is the control.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-replay" (make-array java.nio.file.attribute.FileAttribute 0)))
        a   (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "a"})]
    (try
      (ops/ingest! a 'ro.core "(ns ro.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n")
      (external/done! a :label "seed" :agent "a")
      (let [b (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "b"})]
        (try
          ;; B is open and current; A now adds a helper and makes f call it
          (let [r1 (ops/add-form! a 'ro.core "(defn- h \"H.\" [] 2)" :prompt "helper" :agent "a")
                r2 (ops/edit-replace! a 'ro.core 'f "(defn ^:unused-ok f \"F, via h.\" [] (h))" :prompt "call it" :agent "a")]
            (is (nil? (:error r1)) (pr-str r1))
            (is (nil? (:error r2)) (pr-str r2)))
          (external/done! a :label "land" :agent "a")
          (ops/sync-with-journal! b)
          (let [names  (fn [sess] (mapv :name (store/forms (:store @sess) 'ro.core)))
                render (fn [sess] (store.render/render-ns (:store @sess) 'ro.core))
                c      (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "c"})]
            (try
              (is (= (render a) (render c)) "the writer and a fresh open render the same bytes")
              (is (< (.indexOf ^java.util.List (names c) 'h) (.indexOf ^java.util.List (names c) 'f))
                  (str "rows: " (pr-str (names c))))
              (is (= (render a) (render b))
                  (str "replayed: " (pr-str (names b)) " vs written: " (pr-str (names a))))
              (finally (ops/close! c))))
          (finally (ops/close! b))))
      (finally (ops/close! a)))))

(deftest ^:external a-group-that-adds-a-helper-and-calls-it-persists-the-arranged-order
  ;; Three times in one evening a `change` group added a private helper and
  ;; replaced an earlier form to call it; the writer's own value arranged
  ;; the helper first, every warm check was green, and a fresh boot of the
  ;; namespace from the daemon's READER — a session on the branch line, the
  ;; one shape that absorbs a landing by incremental REPLAY rather than by
  ;; re-forking and reloading from rows — found the helper after its caller:
  ;; `Unable to resolve symbol`. The writer, the replaying reader, and a
  ;; fresh open must render the same bytes, helper first.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-group-order" (make-array java.nio.file.attribute.FileAttribute 0)))
        a   (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "a"})]
    (try
      (ops/ingest! a 'go.core "(ns go.core)\n(defn ^:unused-ok f \"F.\" [] 1)\n(defn ^:unused-ok g \"G.\" [] 3)\n")
      (external/done! a :label "seed" :agent "a")
      (let [b (external/open! {:slopp.ops/dir dir :slopp.ops/read-only? true :slopp.ops/lazy-image? true})]
        (try
          (let [r (ops/edit-group! a [{:ns 'go.core :source "(defn- h \"H.\" [] 2)"}
                                      {:action :replace :ns 'go.core :name 'f
                                       :source "(defn ^:unused-ok f \"F, via h.\" [] (h))"}]
                                   :prompt "helper and caller in one group" :agent "a")]
            (is (nil? (:error r)) (pr-str r)))
          (external/done! a :label "land" :agent "a")
          (ops/sync-with-journal! b)
          (let [names  (fn [sess] (mapv :name (store/forms (:store @sess) 'go.core)))
                render (fn [sess] (store.render/render-ns (:store @sess) 'go.core))
                before (fn [ns* x y] (< (.indexOf ^java.util.List ns* x) (.indexOf ^java.util.List ns* y)))
                c      (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "c"})]
            (try
              (is (before (names a) 'h 'f) (str "writer: " (pr-str (names a))))
              (is (before (names c) 'h 'f) (str "rows: " (pr-str (names c))))
              (is (= (render a) (render c)) "the writer and a fresh open render the same bytes")
              (is (= (render a) (render b)) (str "replayed by the reader: " (pr-str (names b))))
              (finally (ops/close! c))))
          (finally (ops/close! b))))
      (finally (ops/close! a)))))

(deftest ^:external a-checkout-reads-its-framework-from-source-when-no-jar-manifest-is-on-the-classpath
  ;; A checkout run — CI's native-proof lanes, `clojure -M -m slopp.daemon` —
  ;; had no framework to vendor: the kernel's reader answered nil without the
  ;; jar's generated manifest, and a web app's first namespace could not
  ;; resolve slopp.http. The source tree that build.clj reads to GENERATE that
  ;; manifest is on the classpath in exactly that case, so it is read there.
  ;; Lives here rather than in the kernel: the families come from the
  ;; capability catalog, and the kernel may not reach up to it.
  ;;
  ;; ^:external because the in-image oracle is the THIRD state: it loads store
  ;; code over the wire with no src/ on its classpath, so it sees neither the
  ;; manifest nor the source and the reader honestly answers nil there. The
  ;; external tier runs from a materialized tree, which is the checkout shape.
  (let [files (engine/framework-files-from-source)]
    (is (map? files))
    (is (contains? files "http") (pr-str (keys files)))
    (is (contains? (get files "http") "slopp/http.clj") (pr-str (keys (get files "http"))))
    (is (str/includes? (get-in files ["http" "slopp/http.clj"]) "(ns slopp.http"))
    (is (contains? (get files "_") "slopp/lang.cljc") "the common family rides along")
    (is (not-any? #(str/starts-with? % "slopp/api") (mapcat keys (vals files)))
        "and nothing outside the shipping families is handed over")
    (testing "and the one reader every consumer asks answers the same way"
      (is (seq (engine/framework-files*))))))
