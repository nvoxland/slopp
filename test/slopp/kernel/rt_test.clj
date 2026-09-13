(ns slopp.kernel.rt-test
  "`slopp.kernel.rt` is the instrumentation seam: it records which forms a test
  run touched, and the trace map that comes out is what every warranty in the
  system is derived from.

  So these are mostly about the seam RESTORING itself — nesting, multimethods,
  rt observing its own calls. A sink left installed does not fail; it
  attributes one run's touches to the next, and the coverage numbers stay
  plausible while meaning something else. `slopp.image` and `slopp.ops.engine`
  read the result and cannot tell."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.kernel.rt :as rt] [clojure.java.shell :as sh]))

^:unsafe (deftest instrument-seam-records-and-restores
  ;; THE instrumentation seam (#121). traced-run had this loop inline; the
  ;; external trace runner needs the SAME wrapping around cognitect's runner
  ;; instead of around its own per-var loop. Extracted so there is exactly one
  ;; tracer — never two.
  (let [n (create-ns 'rt-probe.core)]
    (intern n 'twice (fn [x] (* 2 x)))
    (intern n 'not-a-fn 42)
    (let [touched   (atom #{})
          originals (rt/instrument! ['rt-probe.core] touched)]
      (try
        (testing "a wrapped fn still returns its value AND records itself"
          (is (= 4 ((ns-resolve n 'twice) 2)))
          (is (= '#{rt-probe.core/twice} @touched)))
        (testing "non-fn vars are left alone"
          (is (= 42 @(ns-resolve n 'not-a-fn))))
        (finally (rt/restore! originals))))
    (testing "restore! un-wraps — instrumentation is temporary"
      (let [touched (atom #{})]
        (is (= 4 ((ns-resolve n 'twice) 2)))
        (is (= #{} @touched))))))

^:unsafe (deftest self-instrument-catches-rts-own-calls
  ;; In a CHILD image, slopp.kernel.rt is the ONLY slopp code that runs (inject-rt! is
  ;; the sole place slopp code is evaled in; the child otherwise loads its own
  ;; store). Nothing there wraps rt, so its calls were invisible.
  ;;
  ;; The wrap must be installed at INJECTION time, not inside traced-run: a fn
  ;; already on the stack cannot record its own entry, which is exactly why
  ;; traced-run measured 0 covering tests while 213 exercised it.
  ;;
  ;; NOTE drain-self! records ITSELF (it is an rt fn, called in the child, on
  ;; the parent test's behalf) — that is honest, not noise, so nothing here
  ;; asserts the drained set is empty.
  (let [originals (rt/self-instrument!)]
    (try
      (testing "the installer records ITSELF — it is on the stack when the wrap
                goes on, so no wrapper can ever catch its entry. Left out it
                reads as covered by the one test that calls it directly, and a
                PARTIAL count is worse than none: zero falls back to running
                everything, one narrows to one test and says green."
        (is (contains? @rt/self-touched 'slopp.kernel.rt/self-instrument!)))
      (rt/qualified #'clojure.core/inc)
      (let [drained (rt/drain-self!)]
        (testing "an rt call made here is recorded against rt's own atom"
          (is (contains? drained 'slopp.kernel.rt/qualified) (pr-str drained))))
      (testing "the drain CLEARS — it runs once per eval, and re-reporting a
                call would inflate whichever test is traced next"
        (is (not (contains? (rt/drain-self!) 'slopp.kernel.rt/qualified))))
      (finally (rt/restore! originals))))
  (testing "restored — self-instrumentation is temporary like every other"
    (rt/qualified #'clojure.core/inc)
    (is (not (contains? (rt/drain-self!) 'slopp.kernel.rt/qualified)))))

^:unsafe (deftest nested-instrument-restores-the-outer-sink
  ;; The sink is how rt calls made in a CHILD image find the test being traced
  ;; (#126). instrument! calls NEST, always: traced-run instruments in-image and
  ;; testmain instruments once around the whole external run, and individual
  ;; tests — this file's probes — instrument again inside that. So restore! must
  ;; hand the sink BACK to the enclosing run rather than clear it. Clearing it
  ;; stops the drain for every later test in the shard, SILENTLY: a nil sink
  ;; just drains nowhere.
  ;;
  ;; There is ALWAYS an enclosing run here — this test is running under one — so
  ;; nothing below asserts the sink is nil. An earlier version of this test did,
  ;; and passed only because the bug it was meant to catch nulled the sink first.
  (let [enclosing @rt/touched-sink]
    (is (some? enclosing) "the run tracing THIS test owns the sink right now")
    (let [outer      (atom #{})
          outer-orig (rt/instrument! [] outer)]
      (try
        (is (identical? outer @rt/touched-sink))
        (let [inner      (atom #{})
              inner-orig (rt/instrument! [] inner)]
          (try
            (is (identical? inner @rt/touched-sink))
            (finally (rt/restore! inner-orig))))
        (testing "the inner restore hands the sink back to the OUTER run"
          (is (identical? outer @rt/touched-sink)))
        (finally (rt/restore! outer-orig))))
    (testing "…and the outer restore hands it back to what enclosed us"
      (is (identical? enclosing @rt/touched-sink)))))

^:unsafe (deftest instrument-sees-multimethod-calls
  ;; MultiFn is ifn? but NOT fn? (probed 2026-07-17), so instrumentable? skips
  ;; it — and wrapping the VAR with a plain fn would break it for real:
  ;; defmethod macroexpands to (.addMethod multifn ...), which needs the var to
  ;; still hold a MultiFn. So multimethods were structurally invisible to the
  ;; trace, and a test exercising one produced no evidence for it.
  ;;
  ;; The wrap goes on the METHOD TABLE instead: each entry is a plain fn we can
  ;; wrap and delegate. Dispatch still runs the real dispatch fn and the real
  ;; hierarchy/prefers — only the resolved method is ours.
  (let [n  (create-ns 'rt-probe.mm)
        mf (clojure.lang.MultiFn. "area" :shape :default
                                  #'clojure.core/global-hierarchy)
        sq (fn [s] (* (:side s) (:side s)))]
    (.addMethod mf :square sq)
    (intern n 'area mf)
    (let [touched   (atom #{})
          originals (rt/instrument! ['rt-probe.mm] touched)]
      (try
        (testing "a dispatched call returns its value AND records the multi"
          (is (= 4 (mf {:shape :square :side 2})))
          (is (contains? @touched 'rt-probe.mm/area) (pr-str @touched)))
        (testing "the var still holds the MultiFn — defmethod/get-method survive"
          (is (instance? clojure.lang.MultiFn @(ns-resolve n 'area))))
        (finally (rt/restore! originals))))
    (testing "restore! puts the ORIGINAL method fns back in the table"
      (is (identical? sq (get (.getMethodTable mf) :square))))))

^:unsafe (deftest instrument-attributes-methods-when-told-how
  ;; A method's form has no name (D8 — registrations define nothing), so its
  ;; trace key is its form id, ns/f2-style. The tracer cannot derive that from
  ;; the runtime MultiFn — only the store knows which dispatch value lives in
  ;; which form — so the mapping arrives as data: {multi-qsym {dispatch form-key}}.
  ;; A dispatched call then records BOTH the multi and the method's own form,
  ;; and a dispatch value the map does not know still records the multi alone.
  (let [n  (create-ns 'rt-probe.attr)
        mf (clojure.lang.MultiFn. "area" :shape :default
                                  #'clojure.core/global-hierarchy)]
    (.addMethod mf :square (fn [_s] 4))
    (.addMethod mf :circle (fn [_c] 3))
    (intern n 'area mf)
    (let [touched   (atom #{})
          originals (rt/instrument! ['rt-probe.attr] touched
                                    {'rt-probe.attr/area {:square 'rt-probe.attr/f7}})]
      (try
        (is (= 4 (mf {:shape :square})))
        (testing "the known dispatch records multi AND method form"
          (is (contains? @touched 'rt-probe.attr/area))
          (is (contains? @touched 'rt-probe.attr/f7)))
        (reset! touched #{})
        (is (= 3 (mf {:shape :circle})))
        (testing "an unknown dispatch still records the multi — never less than C1"
          (is (contains? @touched 'rt-probe.attr/area))
          (is (not-any? #{'rt-probe.attr/f7} @touched)))
        (finally (rt/restore! originals))))))

(deftest ^:external the-runner-watchdog-installs-once
  ;; Test-runner JVMs carried NO parent-death watchdog (it lived only in the
  ;; image-boot path), so a hung or orphaned runner survived its parent — the
  ;; same leak class d9279 closed for images. The install is guarded by
  ;; thread name: however many surfaces run it, exactly one thread results.
  ;;
  ;; In a CHILD JVM with an open pipe, never in the one running this suite:
  ;; armed here, the watchdog read the runner's /dev/null stdin as a dead
  ;; parent and exited the whole suite mid-run with status 0 — which is how
  ;; the from-files CI lane stayed green while never finishing. ^:external
  ;; because the child requires slopp.kernel.rt off the classpath.
  (let [code (str "(require 'slopp.kernel.rt)"
                  " (slopp.kernel.rt/install-parent-watchdog!)"
                  " (slopp.kernel.rt/install-parent-watchdog!)"
                  " (println :threads (count (filter #(= \"slopp-parent-watchdog\" (.getName %))"
                  " (keys (Thread/getAllStackTraces)))))")
        r    (sh/sh "sh" "-c" (str "( sleep 3 ) | clojure -M -e " (pr-str code)))]
    (is (re-find #":threads 1" (str (:out r))) (pr-str r))))

(deftest ^:external the-watchdog-does-not-read-a-closed-stdin-as-a-dead-parent
  ;; The watchdog treated stdin-EOF as \"the parent closed its pipe\" — true for
  ;; an image, whose stdin IS that pipe, and false for any JVM started with
  ;; stdin at /dev/null, which reads EOF at once and has no parent to mourn.
  ;; A CI runner is exactly that. `the-runner-watchdog-installs-once` used to
  ;; arm a real watchdog in the JVM running the suite, so on the runner the
  ;; suite died with exit 0 and no summary after this namespace, and the
  ;; from-files lane reported the truncated run as green on every run.
  ;; Proven locally: same namespaces, `< /dev/null` exits 0 mid-run,
  ;; `< <(sleep 600)` prints the summary and exits 1 on the real failure.
  ;;
  ;; Driven in CHILD JVMs, because the property is about the process the
  ;; watchdog lives in. /dev/null cannot be told from a pipe by attributes on
  ;; every platform, so the discriminator is TIME: a stdin already at EOF when
  ;; the watchdog installs was never a live parent's pipe. ^:external: the
  ;; child requires slopp.kernel.rt off the classpath, which the in-image
  ;; oracle does not carry.
  (let [code (str "(require 'slopp.kernel.rt)"
                  " (println :armed (some? (slopp.kernel.rt/install-parent-watchdog!)))"
                  " (Thread/sleep 1500)"
                  " (println :survived)")]
    (testing "stdin at EOF from the start: nothing to watch, the JVM lives"
      (let [r (sh/sh "sh" "-c" (str "clojure -M -e " (pr-str code) " < /dev/null"))]
        (is (zero? (:exit r)) (pr-str r))
        (is (re-find #":armed false" (str (:out r))) (pr-str r))
        (is (re-find #":survived" (str (:out r)))
            (str "a JVM whose stdin was never a parent's pipe must outlive the install: " (pr-str r)))))
    (testing "stdin an open pipe: the watchdog arms, and a parent that closes it ends the child"
      (let [;; the writer OUTLIVES the boot: a JVM takes seconds to come up,
            ;; and a pipe closed before the install is the /dev/null case
            ;; above, not a parent dying. It closes once :armed has printed,
            ;; which is what a parent's death looks like from the child
            script (str "tmp=$(mktemp); ( while ! grep -q armed \"$tmp\" 2>/dev/null; do sleep 0.2; done ) | "
                        "clojure -M -e " (pr-str code) " | tee \"$tmp\"; rm -f \"$tmp\"")
            r (sh/sh "sh" "-c" script)]
        ;; the pipe closes the moment :armed shows, long before the 1500ms
        ;; sleep ends: the watchdog fires and :survived never prints
        (is (re-find #":armed true" (str (:out r))) (pr-str r))
        (is (not (re-find #":survived" (str (:out r))))
            (str "a closed parent pipe must still end the child: " (pr-str r)))))))
