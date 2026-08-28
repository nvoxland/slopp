(ns slopp.read.orient-test
  "Cover for `slopp.read.orient` — the first thing a session reads.

  Unusually for this codebase, the subject's output is largely PROSE, and the
  prose is the product: an agent acts on \"this verdict was produced by a host
  running code the store has moved past\" the way it acts on a return value. So
  the assertions here are about what the words CLAIM — that a failure names
  itself, that a note does not promise a retry it cannot deliver, that a quiet
  host says nothing rather than something reassuring.

  Mostly in-image, because the assembly is pure. The external ones are the
  cases that need a real session to have a real history to be oriented in."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.ops :as ops] [slopp.read.orient :as orient] [slopp.ops.external :as external] [clojure.string :as str] [slopp.store :as store]))

(deftest fit-report-keeps-reports-under-the-gate
  (let [fat {:milestones [{:commit "d9" :description "m"}]
             :changes (vec (for [i (range 80)]
                             {:ns (symbol (str "big.ns" i)) :form (symbol (str "fn" i))
                              :ops [:replace]
                              :asks [(apply str (repeat 130 "x")) (apply str (repeat 130 "y"))]}))
             :suite {:status :green} :verify "test_run"}
        r  (#'orient/fit-report fat)]
    (is (<= (count (pr-str r)) 6500) (str (count (pr-str r))))
    (is (seq (:milestones r)))
    (is (re-find #"narrows" (str (:note r))) (pr-str (keys r)))))

(deftest fit-report-aggregates-instead-of-amputating
  (let [fat {:milestones [{:commit "d9" :description "m"}]
             :changes (vec (for [i (range 80)]
                             {:ns (symbol (str "big.ns" (mod i 8))) :form (symbol (str "fn" i))
                              :ops [:replace]
                              :asks [(apply str (repeat 130 "x")) (apply str (repeat 130 "y"))]}))
             :suite {:status :green} :verify "test_run"}
        r  (#'orient/fit-report fat)]
    (is (<= (count (pr-str r)) 6500) (str (count (pr-str r))))
    (testing "over-budget changes ROLL UP by namespace — information aggregates, never amputates"
      (is (some #(and (:ns %) (number? (:forms %))) (:changes r)) (pr-str (take 3 (:changes r))))
      (is (re-find #"rolled up" (str (:note r))) (pr-str (:note r))))))

(deftest ^:external form-cards-are-the-interface-view
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'cd.core
                   (str "(ns cd.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn scale\n  \"Rounds to the nearest cent.\"\n  [cents rate]\n"
                        "  (long (Math/round (double (* cents rate)))))\n"
                        "(deftest scale-t (is (= 50 (scale 100 0.5))))\n"))
      (ops/test-run! sess 'cd.core)
      (ops/edit-replace! sess 'cd.core 'scale
                         "(defn scale\n  \"Rounds to the nearest cent.\"\n  [cents rate]\n  (long (Math/round (* (double cents) rate))))"
                         :prompt "avoid double-coercion of the product" :agent "t")
      (let [c (orient/form-card sess 'cd.core 'scale)]
        (is (= 'cd.core/scale (:form c)) (pr-str c))
        (is (= '[cents rate] (:sig c)) (pr-str c))
        (is (re-find #"nearest" (str (:doc c))) (pr-str c))
        (is (re-find #"double-coercion" (str (:why c))) (pr-str c))
        (is (= 1 (get-in c [:warranty :covered])) (pr-str c))
        (is (nil? (:source c)) (pr-str c))
        (is (< (count (pr-str c)) 400) (str (count (pr-str c)))))
      (finally (ops/close! sess)))))

(deftest ^:external cards-carry-observed-examples
  (let [dir  (str (java.nio.file.Files/createTempDirectory
                   "slopp-obs" (make-array java.nio.file.attribute.FileAttribute 0)))
        sess (external/open! {:slopp.ops/dir dir})]
    (try
      (ops/ingest! sess 'ob.core
                   "(ns ob.core)\n(defn scale \"Half it.\" [c r] (long (* c r)))\n")
      (ops/remember-observation! sess 'ob.core 'scale
                                 (ops/query-observe sess 'ob.core 'scale
                                                    "(ob.core/scale 100 0.5)"))
      (testing "the card carries observed input→output pairs (Q: examples don't lie)"
        (let [c (orient/form-card sess 'ob.core 'scale)]
          (is (vector? (:examples c)) (pr-str c))
          (is (some #(re-find #"100" %) (:examples c)) (pr-str c))
          (is (some #(re-find #"50" %) (:examples c)) (pr-str c))))
      (finally (ops/close! sess)))))

(deftest ^:external observed-examples-survive-a-reopen
  ;; The half cards-carry-observed-examples does NOT cover: examples written
  ;; in one session must still show in the next. This pins the durable path
  ;; before form-card stops reading the db directly — without it, moving
  ;; observations into session state could silently reduce them to
  ;; this-session-only and every existing test would still pass.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "slopp-obs-reopen" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (let [;; the same agent reopens — "the next session" means the same worker
          ;; coming back, and a session's writes live on its own line
          sess (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "obs"})]
      (try
        (ops/ingest! sess 'ob2.core
                     "(ns ob2.core)\n(defn scale \"Half it.\" [c r] (long (* c r)))\n")
        (ops/remember-observation! sess 'ob2.core 'scale
                                   (ops/query-observe sess 'ob2.core 'scale
                                                      "(ob2.core/scale 100 0.5)"))
        (finally (ops/close! sess))))
    (let [sess2 (external/open! {:slopp.ops/dir dir :slopp.ops/agent-id "obs"})]
      (try
        (let [c (orient/form-card sess2 'ob2.core 'scale)]
          (is (vector? (:examples c))
              (str "a reopened session must still carry observed examples: " (pr-str c)))
          (is (some #(re-find #"100" %) (:examples c)) (pr-str c)))
        (finally (ops/close! sess2))))))

(deftest host-brief-reads-the-currency-record
  ;; frictions #8 (three sightings): which code the serving host actually
  ;; runs was invisible and line-entangled — every incident began with not
  ;; knowing. The brief now says it: snapshot hosts name the deltas they
  ;; cannot be running, live hosts stay quiet unless a reload failed, and a
  ;; branch line teaches that host code tracks the MAIN journal only.
  (testing "no record (a non-boot process) → nil, section absent"
    (is (nil? (orient/host-brief nil 0 false nil))))
  (testing "snapshot mode names the CODE deltas the host cannot be running"
    (let [h (orient/host-brief {:mode :snapshot :booted-at 100} 3 false nil)]
      (is (= :snapshot (:mode h)))
      (is (re-find #"3 code delta" (:note h)))
      (is (re-find #"restart" (:note h)))))
  (testing "snapshot mode with nothing since boot is quiet — :mode carries it"
    (let [h (orient/host-brief {:mode :snapshot :booted-at 100} 0 false nil)]
      (is (= :snapshot (:mode h)))
      (is (nil? (:note h)))))
  (testing "review V-F3: a snapshot host ON a branch gets BOTH stances"
    (let [h (orient/host-brief {:mode :snapshot :booted-at 100} 2 true nil)]
      (is (re-find #"2 code delta" (:note h)))
      (is (re-find #"branch" (:note h)))))
  (testing "live on main with clean reloads is QUIET — no note, no noise"
    (let [h (orient/host-brief {:mode :live :booted-at 100 :last-reload-at 200
                                :reloads 4 :failed []} 0 false nil)]
      (is (= :live (:mode h)))
      (is (nil? (:note h)))
      (is (nil? (:failed h)))))
  (testing "live on a BRANCH teaches the main-journal blindness"
    (let [h (orient/host-brief {:mode :live :booted-at 100} 0 true nil)]
      (is (re-find #"branch" (:note h)))
      (is (re-find #"image" (:note h)))))
  (testing "failed reloads are NAMED — a silent hold-back is the old bug"
    (let [h (orient/host-brief {:mode :live :booted-at 100 :failed '[a.core]} 0 false nil)]
      (is (= '[a.core] (:failed h)))
      (is (re-find #"(?i)failed" (:note h))))))

(deftest host-warning-fires-only-when-a-verdict-should-be-doubted
  ;; The currency record was already right (host-brief); it was in the wrong
  ;; PLACE. session_brief is read once, at the start; `done` and `full_check`
  ;; are read after every unit of work, and they are the surfaces whose whole
  ;; output is a claim about the code. This is the same producer, aimed there.
  ;;
  ;; Quiet is load-bearing: a live host lags the store by up to one poll by
  ;; design, so a warning on every done would train the reader to ignore it.
  (testing "no record — the process did not boot from a store, so it cannot be stale"
    (is (nil? (orient/host-warning nil 0 nil))))
  (testing "live with clean reloads is SILENT — a poll-interval lag is not a finding"
    (is (nil? (orient/host-warning {:mode :live :booted-at 100 :last-reload-at 200
                                    :reloads 9 :failed []} 3 nil))))
  (testing "a FAILED reload rides the verdict, naming the namespace"
    (let [w (orient/host-warning {:mode :live :booted-at 100 :failed '[a.core]} 0 nil)]
      (is (= '[a.core] (:failed w)))
      (is (re-find #"(?i)failed" (:note w)))
      (is (re-find #"(?i)verdict" (:verdict-note w))
          "it must say what this means for the result it is attached to")))
  (testing "a snapshot host with post-boot code deltas is running old code, and says so"
    (let [w (orient/host-warning {:mode :snapshot :booted-at 100} 3 nil)]
      (is (= :snapshot (:mode w)))
      (is (re-find #"3 code delta" (:note w)))))
  (testing "a snapshot host with nothing since boot is current — silent"
    (is (nil? (orient/host-warning {:mode :snapshot :booted-at 100} 0 nil)))))

(deftest code-deltas-since-is-the-one-counter-for-host-currency
  ;; Markers (:verify :done :commit :turn-begin …) are bookkeeping — a host
  ;; that has not "loaded" a :done delta is not stale. The set lives in
  ;; store.fields/markers and this is the only place that reads it for this
  ;; question, so the count cannot drift between session_brief and a verdict.
  ;;
  ;; That sentence was ASPIRATIONAL for a while and read as enforced.
  ;; session_brief carried its own byte-identical copy of the expression below
  ;; — the two agreed, so nothing was wrong and nothing could notice, which is
  ;; the whole shape of a producer pair. Collapsed 2026-08-05; the claim is now
  ;; true by construction rather than by comment.
  ;;
  ;; Note what would NOT have caught it: a check for a comment that names
  ;; another form. This comment named none — it asserted UNIQUENESS, which is a
  ;; claim about a form that does not exist yet, and no per-form check can see
  ;; the absence of a second one.
  (let [st {:deltas [{:id "d1" :op :add     :at 50}
                     {:id "d2" :op :replace :at 150}
                     {:id "d3" :op :verify  :at 160}
                     {:id "d4" :op :done    :at 170}
                     {:id "d5" :op :commit  :at 180}
                     {:id "d6" :op :replace :at 190}]}]
    (testing "counts only CODE deltas after the mark"
      (is (= 2 (orient/code-deltas-since st 100))))
    (testing "everything after 0 is counted except the markers"
      (is (= 3 (orient/code-deltas-since st 0))))
    (testing "nothing after the newest"
      (is (zero? (orient/code-deltas-since st 999))))
    (testing "a nil mark reads as 0, never as 'skip the check'"
      (is (= 3 (orient/code-deltas-since st nil))))))

(deftest doc-summaries-end-at-a-sentence-not-mid-word
  ;; The card is the right vehicle at the right moment — every query_slice
  ;; returns one for each callee — and a 90-char cut destroyed what it was
  ;; carrying. The real example, in front of me while I wrote a broken
  ;; stylesheet: slopp.http.css/render's card read
  ;;
  ;;   "Garden rules → a minified CSS string. Every string in the rule data — a"
  ;;
  ;; A trailing fragment is worse than a clean stop: it looks like content.
  (let [doc (str "Garden rules → a minified CSS string. Every string in the"
                 " rule data — a selector or a value — is validated against"
                 " CSS block-breakout first.")]
    (testing "the first SENTENCE, whole"
      (is (= "Garden rules → a minified CSS string."
             (orient/doc-summary doc))))
    (testing "a one-sentence doc with no terminator is returned whole"
      (is (= "The form in `ns-sym` defining symbol `nm`, or nil"
             (orient/doc-summary "The form in `ns-sym` defining symbol `nm`, or nil"))))
    (testing "a decimal or an abbreviation is not a sentence end"
      ;; the naive split — on "." — cuts "1.5" in half and reads worse than
      ;; the character cut it replaces
      (is (= "Caps at 1.5 KB by default."
             (orient/doc-summary "Caps at 1.5 KB by default. More here.")))
      (is (= "Uses e.g. malli here."
             (orient/doc-summary "Uses e.g. malli here. Then more."))))
    (testing "a sentence longer than the cap still gets capped, with an ellipsis"
      ;; the budget is the point — an unbounded first sentence would let one
      ;; verbose docstring eat a whole result
      (let [long-doc (str (apply str (repeat 40 "verylongword ")) ". tail")
            out      (orient/doc-summary long-doc)]
        (is (<= (count out) 121) (str (count out) ": " out))
        (is (str/ends-with? out "…"))
        (testing "and the cap lands on a word boundary"
          (is (str/ends-with? out "verylongword…") out))))
    (testing "nil and blank are nil, not \"\" or \"…\""
      (is (nil? (orient/doc-summary nil)))
      (is (nil? (orient/doc-summary "   "))))))

(deftest a-card-carries-the-trap-not-just-the-purpose
  ;; The card is what a CALLER sees, and it already carries sig, doc, why and
  ;; warranty. What it could not carry is the one line that would have stopped
  ;; the caller making the mistake — because a docstring's first line says
  ;; what the function DOES and the trap is in paragraph three.
  ;;
  ;; The real case: web.css/render's card was on screen while a stylesheet was
  ;; written with `[:a :b]` meaning a descendant. It means a GROUP. The
  ;; docstring said so — four paragraphs down.
  ;;
  ;; ^{:teach "…"} is the same vocabulary every rule already uses for
  ;; explain-at-point-of-use, and it rides the card.
  (let [sess (atom {:store (store/ingest
                            (store/empty-store) 'app.css
                            (str "(ns app.css \"Styling.\")\n\n"
                                 "(defn ^{:teach \"[:a :b] is a GROUP, not a descendant\"}\n"
                                 "  render \"Rules to a CSS string.\" [rules] rules)\n\n"
                                 "(defn plain \"No trap here.\" [x] x)\n"))})]
    (testing "the caveat rides the card, beside the doc rather than inside it"
      (let [c (orient/form-card sess 'app.css 'render)]
        (is (= "[:a :b] is a GROUP, not a descendant" (:teach c)) (pr-str c))
        (is (= "Rules to a CSS string." (:doc c))
            "and the doc summary is untouched — they answer different questions")))
    (testing "a form with no trap carries no key at all"
      ;; not "" and not nil-valued: an empty :teach on every card would train
      ;; the reader to skip the field, which is how the one that matters gets
      ;; missed
      (is (not (contains? (orient/form-card sess 'app.css 'plain) :teach))))
    (testing "a non-STRING teach is dropped, not rendered as a literal list"
      ;; metadata is read as DATA and never evaluated, so ^{:teach (str "a" "b")}
      ;; is a LIST. Rendering it would put `(str "a" "b")` on the card — a
      ;; confident-looking value that teaches nothing, which is worse than the
      ;; absence it replaces. Caught while authoring the first real caveat.
      (let [s2 (atom {:store (store/ingest
                              (store/empty-store) 'app.bad
                              (str "(ns app.bad \"B.\")\n\n"
                                   "(defn ^{:teach (str \"a\" \"b\")} f \"D.\" [x] x)\n"))})]
        (is (not (contains? (orient/form-card s2 'app.bad 'f) :teach))
            "a teach that is not a string is not a teach")))))

(deftest a-stuck-reload-says-why-and-stops-promising-a-retry
  ;; Two complaints from the same incident, both about the same note.
  ;;
  ;; The REASON never reached the agent: "the failure is in the server log" —
  ;; a file in ~/Library/Logs that no slopp surface exposes, on a system whose
  ;; whole claim is that the store answers everything. Grepping it found
  ;; nothing usable.
  ;;
  ;; And the note promised "the next poll retries" for many minutes while the
  ;; reload had been failing identically every time. A reload is deterministic
  ;; over the same source: if it failed on this text it will fail on this text.
  ;; A retry loop that cannot converge should escalate, not reassure.
  (testing "the first failure names the reason, not the log file"
    (let [b (orient/host-brief {:mode :live :booted-at 1 :failed '[app.views]
                                :failed-why '{app.views {:why "Unable to resolve symbol: nsfilter"
                                                         :attempts 1}}} 0 false nil)]
      (is (= '[app.views] (:failed b)))
      (is (str/includes? (:note b) "Unable to resolve symbol: nsfilter"))
      (is (not (str/includes? (:note b) "server log"))
          "the reason is HERE now; pointing at a file nothing exposes was the bug")))
  (testing "a failure that keeps failing escalates instead of repeating itself"
    (let [b (orient/host-brief {:mode :live :booted-at 1 :failed '[app.views]
                                :failed-why '{app.views {:why "Unable to resolve symbol: nsfilter"
                                                         :attempts 12}}} 0 false nil)]
      (is (str/includes? (:note b) "12"))
      (is (str/includes? (:note b) "restart"))
      (is (not (str/includes? (:note b) "next poll retries"))
          "a loop that has failed twelve times identically is not about to succeed")))
  (testing "a host with nothing wrong still says nothing"
    (is (nil? (:note (orient/host-brief {:mode :live :booted-at 1} 0 false nil))))))

(deftest a-half-loaded-store-says-so-and-says-it-is-editable
  ;; frictions 3b/3f/19. boot used to rethrow on the first namespace that did
  ;; not compile, so one bad form took down every tool in every process — the
  ;; edit verbs that could have repaired it included. Loading best-effort is
  ;; only half the fix; the other half is that orientation NAMES what did not
  ;; load, because an agent who is not told discovers it as an unrelated
  ;; failure somewhere downstream.
  (let [info {:mode :live :booted-at 1
              :load-failures [{:ns 'app.views :why "Unable to resolve symbol: gone"}]}
        b    (orient/host-brief info 0 false [])]
    (is (re-find #"did NOT load at boot" (:note b)))
    (is (re-find #"app\.views" (:note b))
        "naming the namespace is the difference between a warning and a fix")
    (is (re-find #"FIX them" (:note b))
        "and it has to say the store is still editable, or the advice reads as 'reimport'"))

  (testing "a fully loaded store stays silent about it"
    (is (nil? (:note (orient/host-brief {:mode :live :booted-at 1} 0 false []))))))

(deftest a-failed-host-reload-is-not-exonerated-by-the-oracle
  ;; CORRECTION to what landed with the currency work. host-brief answered a
  ;; failed live-reload with "the image was COMPARED to the store and holds
  ;; every form at its current source, so this is the watcher stuck, not stale
  ;; code". Two different processes are being conflated there:
  ;;
  ;;   - the reload that FAILED is the HOST's — this server process, whose
  ;;     watcher lives in slopp.kernel.boot;
  ;;   - the image that was COMPARED is the child ORACLE, a separate JVM.
  ;;     `currency/stamp!` runs server-side, but it records what was pushed
  ;;     INTO the oracle, so `currency/drift` measures the oracle.
  ;;
  ;; A failed host reload is precisely the case where the two diverge, so the
  ;; oracle comparing clean is no evidence about the host at all. Friction
  ;; 20a's lesson still holds in the other direction — do not assert staleness
  ;; nobody measured either. What is left is the honest third answer: name the
  ;; watcher failure, and say the host's own currency is UNMEASURED.
  ;;
  ;; host-warning is a different reader and is correct as it stands: a VERDICT
  ;; is produced by the oracle, so oracle drift is exactly what should qualify
  ;; it. The bug is confined to the section that describes this process.
  (let [info {:mode :live :booted-at 1 :failed '[a.core]
              :failed-why '{a.core {:why "boom" :attempts 1}}}
        b    (orient/host-brief info 0 false [])
        note (str (:note b))]
    (is (nil? (:image-verified b))
        "an oracle comparison says nothing about whether THIS process is current")
    (is (:oracle-verified b)
        "the oracle's cleanliness is still worth reporting — under its own name")
    (is (not (re-find #"not stale code" note)) note)
    (is (re-find #"a\.core" note) "the failing namespace is still named")
    (is (re-find #"not been measured" note)
        "unmeasured is the honest answer, and it has to be said out loud"))
  (testing "the host section does not call the oracle's drift its own"
    (let [drift [{:ns 'a.core :form 'f :why :superseded}]
          b     (orient/host-brief {:mode :live :booted-at 1} 0 false drift)]
      (is (= 1 (:oracle-drift-count b)) (pr-str b))
      (is (nil? (:drift b)) (pr-str b))
      (is (re-find #"(?i)verification image" (str (:note b))) (pr-str (:note b))))))

(deftest a-failed-reload-is-a-stuck-watcher-when-THIS-process-compares-clean
  ;; The claim removed earlier today, now earned. It was wrong because the
  ;; comparison behind it was against the child ORACLE. Measured against this
  ;; process — `slopp.kernel.boot/host-drift`, which every door into this JVM writes
  ;; — "the watcher is stuck, not stale code" is exactly right, and it is what
  ;; friction 20a needed: a rename left the watcher retrying a namespace the
  ;; store no longer had, failing forever and reporting staleness that a
  ;; comparison would have cleared on the next poll.
  ;;
  ;; The two subjects now read side by side: :host-drift / :host-verified and
  ;; :oracle-drift / :oracle-verified. Same quantity, named for who it is about.
  (let [info {:mode :live :booted-at 1 :failed '[a.core]
              :failed-why '{a.core {:why "boom" :attempts 9}}
              :host-drift []}
        b    (orient/host-brief info 0 false nil)
        note (str (:note b))]
    (is (:host-verified b) "measured, and this process holds current source")
    (is (re-find #"watcher" note) note)
    (is (not (re-find #"not been measured" note)) note))
  (testing "measured behind names the namespaces rather than blaming the watcher"
    (let [b    (orient/host-brief {:mode :live :booted-at 1 :host-drift '[a.core b.core]}
                                  0 false nil)
          note (str (:note b))]
      (is (= '[a.core b.core] (:host-drift b)) (pr-str b))
      (is (nil? (:host-verified b)))
      (is (re-find #"a\.core" note) note)))
  (testing "absent still means nobody measured, and it still says so"
    (let [b (orient/host-brief {:mode :live :booted-at 1 :failed '[a.core]} 0 false nil)]
      (is (nil? (:host-verified b)))
      (is (re-find #"not been measured" (str (:note b))) (pr-str (:note b))))))

(deftest a-verdict-is-doubted-when-the-HOST-is-measured-behind
  ;; host-warning was right to use ORACLE drift — the tests run there. But the
  ;; host runs the code that ORCHESTRATES the run (slopp.api: what to verify,
  ;; what the trace says, how the result is judged), so a host measured behind
  ;; is also a reason to doubt a verdict, by a different route.
  ;;
  ;; And the failed-reload branch can stop guessing. It used to fire whenever a
  ;; reload had failed and the ORACLE had not been compared — the oracle
  ;; standing in for a question about the host. With the host measuring itself,
  ;; a failed reload on a host that compares clean is a watcher problem and
  ;; nothing more.
  (testing "measured behind qualifies the verdict and names the namespaces"
    (let [w (orient/host-warning {:mode :live :booted-at 1 :host-drift '[a.core]}
                                 0 nil)]
      (is (some? w))
      (is (re-find #"a\.core" (str (:verdict-note w) (:note w))) (pr-str w))))
  (testing "a failed reload whose HOST compares clean does not qualify anything"
    (is (nil? (orient/host-warning {:mode :live :booted-at 1 :failed '[a.core]
                                    :failed-why '{a.core {:why "boom" :attempts 2}}
                                    :host-drift []}
                                   0 nil))
        "the watcher is stuck; the code that produced this verdict is current"))
  (testing "a failed reload with NO host measurement still warns — cautious by default"
    (is (some? (orient/host-warning {:mode :live :booted-at 1 :failed '[a.core]}
                                    0 nil)))))

(deftest ^:external a-defs-VALUE-is-not-a-signature
  ;; `sig` was "the first vector anywhere after the head", which is a
  ;; wrong-index read wearing a heuristic's clothes: in `(def rates [1 2 3])`
  ;; the first vector is the VALUE. Measured over this store on 2026-08-02,
  ;; THIRTY-TWO defs would have reported one, `slopp.project.capabilities/
  ;; registry` among them — its entire 19-entry vector drawn as a parameter
  ;; list.
  ;;
  ;; The card is ^:export'ed precisely so a consumer can inline a callee
  ;; instead of linking to it, so a card that says a value takes arguments is
  ;; a false statement in the shape most likely to be believed. slopp-ui
  ;; reported the same class against the ns-outline the same day; this is
  ;; where it also lived.
  ;;
  ;; `form-doc` in slopp.api.reads carries the identical scar — it read
  ;; index 2 and took any string, so `(def greeting "hello")` documented
  ;; itself as "hello". Wrong-index reads do not throw. They return something
  ;; plausible, which is why one fix never closes the class.
  (let [sess (external/open!)]
    (try
      (ops/ingest! sess 'sg.core
                   (str "(ns sg.core)\n"
                        "(def rates \"Known rates.\" [0.07 0.20])\n"
                        "(def lookup {:a 1})\n"
                        "(defn scale [cents rate] (* cents rate))\n"
                        "(defn multi ([a] a) ([a b] [a b]))\n"))
      (testing "a def reports no signature at all, whatever its value is"
        (let [c (orient/form-card sess 'sg.core 'rates)]
          (is (nil? (:sig c)) (str "a def's value vector is not a sig: " (pr-str c)))
          (is (re-find #"Known rates" (str (:doc c)))
              (str "and its docstring still reads correctly: " (pr-str c)))))
      (testing "a defn still reports its arguments"
        (is (= '[cents rate] (:sig (orient/form-card sess 'sg.core 'scale)))))
      (testing "and every arity of a multi-arity, which is why sig is nested"
        (is (= '[[a] [a b]] (:sig (orient/form-card sess 'sg.core 'multi)))))
      (finally (ops/close! sess)))))

(deftest a-suspect-verdict-says-which-of-the-two-images-is-stale
  ;; slopp-ui, 2026-08-03: they read "restart the server", took it to mean the
  ;; MCP process a human owns — the one act they believed an agent cannot
  ;; perform — and reported the staleness as a WALL. It is not: `restart` was
  ;; in their tool list the whole time, and two calls proved it cleared their
  ;; drift. The mechanism was right and the sentence sent them elsewhere.
  ;;
  ;; But "say `restart` everywhere" is the WRONG fix, and writing it that way
  ;; first is how this test found out. `slopp.ops/restart!` calls
  ;; `session/fresh-image!` — it replaces the VERIFICATION image and does not
  ;; touch the JVM serving MCP. So there are two staleness stories here and
  ;; only one of them is the agent's:
  ;;
  ;;   oracle drift  → the verification image → `restart` clears it
  ;;   host  drift   → this very process      → it does not
  ;;
  ;; Pointing an agent at `restart` for host drift costs a call and hands back
  ;; a verdict just as suspect as the one before — the same failure as the
  ;; sentence being fixed, aimed the other way.
  (let [oracle (:verdict-note (orient/host-warning
                               {:mode :live :booted-at 100} 0
                               '[{:ns a.core :form f :why :derived-stale}]))
        hosts  [(:verdict-note (orient/host-warning
                                {:mode :live :booted-at 100 :host-drift '[a.core]} 0 nil))
                (:verdict-note (orient/host-warning
                                {:mode :live :booted-at 100 :failed '[a.core]} 0 nil))
                (:verdict-note (orient/host-warning
                                {:mode :snapshot :booted-at 100} 3 nil))]]
    (testing "all four doubt-worthy shapes produce a note — an empty
              population would make every assertion below true of nothing"
      (is (some? oracle))
      (is (= 3 (count (filter some? hosts))) (pr-str hosts)))
    (testing "the verification image names the TOOL that clears it, in the
              tool's own spelling — this is the one slopp-ui hit"
      (is (re-find #"`restart`" (str oracle)) (pr-str oracle))
      (is (not (re-find #"restart the server" (str oracle))) (pr-str oracle)))
    (testing "the host notes name `restart` too — and RULE IT OUT. Banning the
              word was this test's second wrong answer: an agent that has just
              been told a verdict is suspect will reach for the one restart-ish
              verb it has, so the note that stays silent about it and the note
              that recommends it cost the same call. Saying it does not apply
              is the only version that stops one."
      (doseq [n hosts]
        (is (re-find #"`restart` tool does not" (str n))
            (str "must rule the tool out explicitly, not omit it: " (pr-str n)))
        (is (re-find #"MCP server" (str n))
            (str "and name what does have to come up again: " (pr-str n)))))))

(deftest the-brief-reports-verification-drift-with-the-verb-that-clears-it
  ;; Sibling of the verdict note, one reader over: `host-brief` feeds
  ;; session_brief, so this is the FIRST place an agent learns the
  ;; verification image is behind — and it stated the fact with no remedy at
  ;; all, while the two host lines beside it both end in "restart the server".
  ;; Read together that is worse than silence: three staleness lines, the two
  ;; the agent cannot fix carry an instruction and the one it can carries none.
  (let [line (:note (orient/host-brief
                     {:mode :live :booted-at 100 :last-reload-at 200} 0 false
                     '[{:ns a.core :form f :why :derived-stale}]))]
    (is (re-find #"VERIFICATION image" (str line))
        (str "the drift line must be produced at all: " (pr-str line)))
    (is (re-find #"`restart`" (str line))
        (str "name the verb that builds a fresh verification image: "
             (pr-str line)))))

(deftest jar-currency-compares-only-when-the-two-heads-share-a-store
  ;; Three claims, not two — the same discipline current-boot-info holds for
  ;; :host-drift. "I did not look" must not render as "I looked and it was
  ;; fine", and here there is a third state the others do not have: a jar can
  ;; be perfectly current and belong to a DIFFERENT store. slopp's own jar
  ;; serves other projects, so counting deltas since its head against THEIR
  ;; log would measure their writing speed and call it the tool's age.
  (let [st (-> (store/empty-store)
               (store/ingest 'jc.a "(ns jc.a)\n\n(defn a \"A.\" [] 1)\n")
               (store/ingest 'jc.b "(ns jc.b)\n\n(defn b \"B.\" [] 2)\n"))
        ds (store/deltas st)
        h1 (:id (first ds))]
    (testing "no stamp — nothing to report, and no invented zero"
      (is (nil? (orient/jar-currency st nil))))
    (testing "a head this store has never seen is reported WITHOUT a count"
      (let [r (orient/jar-currency st "d-from-another-store")]
        (is (= "d-from-another-store" (:head r))
            "the identity still travels — it is what a human compares by hand")
        (is (not (contains? r :behind))
            "and the count is absent, not zero: this store cannot place that head")))
    (testing "a head this store HAS is placed, and counted the one way"
      (let [r (orient/jar-currency st h1)]
        (is (= h1 (:head r)))
        (is (= (orient/code-deltas-since st (:at (first ds))) (:behind r))
            "the same counter the host and the served app report, not a fourth spelling")))))

(deftest the-currency-record-says-which-artifact-is-answering
  ;; :host said which MODE this process runs in and never which CODE. "Am I
  ;; running the slopp that has the fix" was therefore unanswerable from the
  ;; brief, and six incidents answered it by hand — one of them wrongly,
  ;; reading a jar mid-write.
  ;;
  ;; It rides on `info` the way :host-drift does, for the same reason: both are
  ;; facts about THIS process that only a caller holding the store can finish.
  (testing "absent when the process cannot say — a checkout, a bare -M run"
    (is (not (contains? (orient/host-brief {:mode :live :booted-at 100} 0 false nil)
                        :jar))
        "no stamp is not a claim of currency"))
  (testing "present, verbatim, when it is"
    (let [h (orient/host-brief {:mode :live :booted-at 100
                                :jar {:head "d23479" :behind 12}}
                               0 false nil)]
      (is (= {:head "d23479" :behind 12} (:jar h))))))

(deftest a-suspect-verdict-names-the-edit-that-made-it-suspect
  ;; "Something is stale, restart" is a fix with no diagnosis, and the reader
  ;; is mid-verdict asking why. One write reloads a whole namespace, so every
  ;; value captured from it falls behind TOGETHER — which is what makes naming
  ;; one edit for the whole list honest rather than a guess.
  ;;
  ;; BOTH readers are driven from one drift value on purpose. `host-brief` and
  ;; `host-warning` are one producer aimed at two audiences, and this repo's
  ;; most repeated defect is an improvement landing on one of a pair.
  (let [info  {:mode :live :booted-at 100 :last-reload-at 200}
        drift '[{:ns slopp.mcp :form env-handlers! :why :derived-stale
                 :behind slopp.mcp.tools/cheat-sheet
                 :behind-edit {:delta "d25795" :prompt "say what where now does"}}
                {:ns slopp.mcp :form tail-handlers! :why :derived-stale
                 :behind slopp.mcp.tools/cheat-sheet
                 :behind-edit {:delta "d25795" :prompt "say what where now does"}}]]
    (doseq [[who note] [["verdict" (:verdict-note (orient/host-warning info 0 drift))]
                        ["brief"   (:note (orient/host-brief info 0 false drift))]]]
      (is (re-find #"d25795" (str note))
          (str who " must name the delta that put them behind: " (pr-str note)))
      (is (re-find #"say what where now does" (str note))
          (str who ": the prompt is what makes it click — a delta id alone is a"
               " lookup: " (pr-str note)))
      (is (re-find #"`restart`" (str note))
          (str who ": the fix survives the diagnosis: " (pr-str note)))))
  ;; positive control on the GUARD, not just the happy path: rows that disagree
  ;; about the cause must get none named, or the note would confidently blame
  ;; whichever row sorted first
  (let [info  {:mode :live :booted-at 100 :last-reload-at 200}
        split '[{:ns a.core :form f :why :derived-stale :behind b.core/x
                 :behind-edit {:delta "d1" :prompt "one"}}
                {:ns a.core :form g :why :derived-stale :behind c.core/y
                 :behind-edit {:delta "d2" :prompt "two"}}]]
    (doseq [[who note] [["verdict" (:verdict-note (orient/host-warning info 0 split))]
                        ["brief"   (:note (orient/host-brief info 0 false split))]]]
      (is (not (re-find #"went behind at" (str note)))
          (str who ": two causes, so no single one is named: " (pr-str note)))
      (is (re-find #"2 form" (str note))
          (str who ": and the note is still produced: " (pr-str note))))))

(deftest the-BUNDLE-says-how-far-behind-the-browser-is
  ;; Reported by slopp-ui, and it is the sharpest "green that is not one" this
  ;; wave produced. They rewrote ten screens in :cljc, then took a green `done`,
  ;; a green `commit_point`, a green `full_check` and `:app {:behind 0}` — and
  ;; the browser was still being served the PRE-rewrite bundle.
  ;;
  ;; Nothing was wrong. They simply had not run `compile_client`, and no check
  ;; knows that. `:app {:behind n}` tracks the IMAGE, which is a different
  ;; artifact answering a different question, and its zero is honest about the
  ;; thing it measures.
  ;;
  ;; So the browser is a third artifact that can be stale, beside the host and
  ;; the jar, and it was the only one with no report.
  (let [entry  {:sha "abc" :bytes 10 :content-type "application/javascript"}
        out    "public/cljs/main.js"
        client (fn [st nsx src platform]
                 (-> st
                     (store/ingest nsx src)
                     (as-> s (first (store/record-module-platform
                                     s (str nsx) platform)))))
        built  (fn [st] (first (store/record-artifact st out entry)))]

    (testing "no bundle recorded is NIL, not zero"
      ;; zero would claim a bundle exists and is current, which is the stronger
      ;; form of the mistake this whole record exists to avoid
      (is (nil? (orient/bundle-currency (store/empty-store) out))))

    (testing "a fresh compile is behind nothing"
      (let [st (-> (store/empty-store)
                   (client 'app.view "(ns app.view)\n\n(defn v \"V.\" [s] s)\n" :cljc)
                   built)]
        (is (= 0 (:behind (orient/bundle-currency st out))))
        (is (= "abc" (:sha (orient/bundle-currency st out))))))

    (testing "a CLIENT write after the compile counts"
      (let [st (-> (store/empty-store)
                   (client 'app.view "(ns app.view)\n\n(defn v \"V.\" [s] s)\n" :cljc)
                   built
                   (client 'app.other "(ns app.other)\n\n(defn w \"W.\" [s] s)\n" :cljs))]
        (is (= 1 (:behind (orient/bundle-currency st out)))
            "a :cljs namespace written after the compile is IN the browser's
             blind spot — the bundle predates it")))

    (testing "and a JVM-only write does NOT"
      ;; the discrimination that makes this worth having rather than noisy: a
      ;; server-side edit cannot stale a browser bundle, and reporting it would
      ;; train the reader to ignore the number
      (let [st (-> (store/empty-store)
                   (client 'app.view "(ns app.view)\n\n(defn v \"V.\" [s] s)\n" :cljc)
                   built
                   (store/ingest 'app.server "(ns app.server)\n\n(defn h \"H.\" [r] r)\n"))]
        (is (= 0 (:behind (orient/bundle-currency st out)))
            "a :jvm write is not something the browser can be behind on")))))

(deftest a-store-DECLARING-what-nothing-reads-says-so-as-one-fact
  ;; Reported by a consuming store, and the cost was a WRONG DIAGNOSIS rather
  ;; than time. They restarted onto a new jar, the hub refused its own check-in
  ;; with 404, five tests went red, and `:hub-note` said the beat contract
  ;; crosses the split by COPY so a refusal is where drift surfaces — pointing
  ;; at contract drift. It was not drift: their routes declared `:web/path`,
  ;; the jar reads `:http/path`, so NOT ONE endpoint was registered and
  ;; everything 404d.
  ;;
  ;; Every input was available at boot. The store declares ten endpoints; the
  ;; running code knows which marker it reads; the intersection was empty.
  ;;
  ;; `unknown-marker` already knows the per-form half — but it is per-form,
  ;; done-grain, and phrased as "nothing reads it" about ONE marker. Nobody
  ;; joins ten of those into "you are serving nothing", and a store with zero
  ;; readable endpoints is a different FACT from ten forms with unread markers.
  (let [st (-> (store/empty-store)
               (store/ingest 'app.a
                             (str "(ns app.a)\n\n"
                                  "(defn ^{:web/path \"/x\" :web/method :get}\n"
                                  "  h \"H.\" [_] {:status 200})\n"))
               (store/ingest 'app.b
                             (str "(ns app.b)\n\n"
                                  "(defn ^{:web/path \"/y\"} g \"G.\" [_] {:status 200})\n")))
        r  (orient/unread-declarations st)]

    (testing "the retired spelling is named, with how many forms carry it"
      (is (some? r) "a store serving nothing said nothing")
      (is (= 2 (get-in r [:markers :web/path :count])) (pr-str r)))

    (testing "and what the CURRENT spelling is, because that is the whole fix"
      (is (= :http/path (get-in r [:markers :web/path :now])) (pr-str r)))

    (testing "the note states the consequence, not the count"
      ;; "2 forms carry an unread marker" is a lint nit; "this store serves
      ;; nothing" is the fact that was needed, and it is the one a reader
      ;; cannot assemble from per-form findings
      (is (re-find #"(?i)serves nothing|0 (of|are)|none of them" (:note r))
          (:note r)))

    (testing "a store whose markers are all CURRENT says nothing at all"
      ;; silent at zero, or it becomes a line every brief carries and nobody reads
      (let [ok (store/ingest (store/empty-store) 'app.ok
                             (str "(ns app.ok)\n\n"
                                  "(defn ^{:http/path \"/x\" :http/method :get}\n"
                                  "  h \"H.\" [_] {:status 200})\n"))]
        (is (nil? (orient/unread-declarations ok)))))))

(deftest the-remedy-a-brief-NAMES-has-to-be-one-that-can-be-RUN
  ;; slopp-ui, 2026-08-23, on first contact with this report. It fired
  ;; unprompted and told them the right fact — and then named
  ;; `rename_sweep {from … to …}`, which REFUSES generated forms, correctly.
  ;; All nine of their stale-marker forms are `^:generated` in a wire namespace,
  ;; so the suggestion was 0-for-9 on the one store whose markers are written
  ;; by a generator rather than by hand.
  ;;
  ;; A remedy that cannot be taken is worse than none: it reads as the answer,
  ;; so the reader runs it, gets a refusal, and now has two problems to
  ;; separate. And this report already reads `form-name-meta`, which is where
  ;; `^:generated` lives — so it could tell them apart the whole time.
  (testing "all generated — name REGENERATION, and do not name the sweep"
    (let [st (store/ingest (store/empty-store) 'wire.api
                           (str "(ns wire.api)\n\n"
                                "(defn ^{:generated true :web/path \"/x\"}\n"
                                "  h \"H.\" [_] {:status 200})\n"))
          r  (orient/unread-declarations st)]
      (is (some? r))
      (is (not (re-find #"rename_sweep" (:note r)))
          (str "a sweep refuses generated forms, so naming it sends the reader"
               " to a refusal: " (:note r)))
      (is (re-find #"(?i)generat" (:note r)) (:note r))))

  (testing "none generated — the sweep is the remedy, unchanged"
    (let [st (store/ingest (store/empty-store) 'app.a
                           (str "(ns app.a)\n\n"
                                "(defn ^{:web/path \"/x\"} h \"H.\" [_] {:status 200})\n"))
          r  (orient/unread-declarations st)]
      (is (re-find #"rename_sweep" (:note r)) (:note r))))

  (testing "MIXED — both remedies, with how many each reaches"
    ;; the split matters more than either half: a reader who runs the sweep and
    ;; sees "moved 1" has no way to know two more are waiting on a generator
    (let [st (-> (store/empty-store)
                 (store/ingest 'app.a
                               (str "(ns app.a)\n\n"
                                    "(defn ^{:web/path \"/x\"} h \"H.\" [_] {:status 200})\n"))
                 (store/ingest 'wire.api
                               (str "(ns wire.api)\n\n"
                                    "(defn ^{:generated true :web/path \"/y\"}\n"
                                    "  g \"G.\" [_] {:status 200})\n"
                                    "(defn ^{:generated true :web/path \"/z\"}\n"
                                    "  k \"K.\" [_] {:status 200})\n")))
          r  (orient/unread-declarations st)]
      (is (re-find #"rename_sweep" (:note r)) (:note r))
      (is (re-find #"(?i)2 .*generat|generat.*2" (:note r))
          (str "the generated count has to be in the sentence, or the sweep's"
               " own \"moved 1\" reads as done: " (:note r)))
      (is (= 3 (get-in r [:markers :web/path :count])) (pr-str r))
      (is (= 2 (get-in r [:markers :web/path :generated])) (pr-str r)))))

(deftest a-counter-that-reads-ZERO-both-ways-is-not-a-counter
  ;; slopp-ui, 2026-08-23. A `done` went green and the served stylesheet stayed
  ;; byte-identical for about twenty minutes — the app image was running old
  ;; code while every surface said fine. They went to `session_brief`, which is
  ;; where you look, and it showed `:app <url>` and `:app-boot-ms` and NOTHING
  ;; about currency. `:app {:behind n}` exists and they had seen it fire twice
  ;; that day — in `full_check`, a different call.
  ;;
  ;; Their words, and the reason this is a defect rather than a preference: a
  ;; counter that reads 0 both when you are current and when nobody updated the
  ;; counter is a signal whose output cannot vary. The accounting that exists to
  ;; catch exactly this was silent in the one case it was for.
  ;;
  ;; Two docstrings were meanwhile telling readers this lived in the brief. It
  ;; did not. Now it does — as `:app-behind`, beside `:app-boot-ms`.
  (let [st (store/ingest (store/empty-store) 'app.a
                         "(ns app.a)\n\n(defn ^:unused-ok f \"F.\" [x] x)\n")]

    (testing "0 for an image served after the last code delta, and 0 is an ANSWER"
      (is (= 0 (orient/behind st {:serving? true
                                  :served-at (+ 1000 (System/currentTimeMillis))}))))

    (testing "and it COUNTS the code deltas an image was served before"
      (is (pos? (orient/behind st {:serving? true :served-at 0}))
          "an image from before every delta is behind by all of them"))

    (testing "nothing serving is a different answer from a current image"
      ;; nil, not 0. A store slopp runs no app for must not be told its app is
      ;; up to date — that is the same conflation one level up, and it is why
      ;; the brief omits the key entirely rather than reporting a reassuring
      ;; zero
      (is (nil? (orient/behind st nil)))
      (is (nil? (orient/behind st {:serving? false :served-at 0}))))))

(deftest a-marker-slopp-DELETED-is-reported-like-one-it-renamed
  ;; Reported by slopp-ui, 2026-08-23, at the boot after the `:rest/client`
  ;; retirement — and it is a hole this very session opened.
  ;;
  ;; At ONE boot, that store carried both:
  ;;
  ;;   :web/external-path → :http/external-path   RENAMED   reported
  ;;   :rest/client       → gone                  DELETED   silent
  ;;
  ;; The block was keyed on being able to say "the current spelling is X", so a
  ;; marker with no successor produced no row — and no row reads as an
  ;; all-clear. **That is the worse half.** A renamed marker usually breaks
  ;; something visible; a deleted one quietly stops meaning anything, and every
  ;; reader after that point reasons from a declaration nothing reads. Their
  ;; stylesheet still carried `:rest/client false` with a docstring explaining
  ;; why it was necessary.
  ;;
  ;; The join was already there — what the store DECLARES against what this
  ;; slopp READS. Only the rows whose right-hand side is empty went unreported.
  ;;
  ;; And the deleted case can carry MORE than the renamed one, because the
  ;; reason it went is known: "drop the marker" is a complete instruction, the
  ;; way "re-run whatever writes them" was for a generated form.
  ;;
  ;; **Any marker deleted rather than renamed has this property, and a
  ;; partition is exactly the kind of change that deletes vocabulary rather
  ;; than moving it.**
  (testing "a DELETED marker is reported, and told to go rather than to move"
    (let [st (store/ingest (store/empty-store) 'app.pages
                           (str "(ns app.pages)\n\n"
                                "(defn ^{:http/path \"/\" :rest/client false}\n"
                                "  home \"H.\" [_] {:status 200})\n"))
          r  (orient/unread-declarations st)]
      (is (some? r) "a declaration nothing reads, reported as nothing to see")
      (is (= 1 (get-in r [:markers :rest/client :count])) (pr-str r))
      (is (nil? (get-in r [:markers :rest/client :now]))
          "there is no current spelling — that is the whole point")
      (is (re-find #"(?i)drop|remove" (:note r)) (:note r))
      (is (not (re-find #"rename_sweep" (:note r)))
          (str "a sweep renames; there is nothing to rename it TO: " (:note r)))))

  (testing "and a RENAMED marker still says what the current spelling is"
    (let [st (store/ingest (store/empty-store) 'app.a
                           "(ns app.a)\n\n(defn ^{:web/path \"/x\"} h \"H.\" [_] {:status 200})\n")
          r  (orient/unread-declarations st)]
      (is (= :http/path (get-in r [:markers :web/path :now])) (pr-str r))
      (is (re-find #"rename_sweep" (:note r)) (:note r)))))

(deftest a-milestone-can-be-green-while-the-jar-was-never-built
  ;; Friction #17, hit twice in one night and caught both times by a CONSUMER
  ;; reading the artifact rather than by anything slopp said. A milestone is
  ;; the announcement other people act on, and it was making a claim about the
  ;; store while saying nothing about the jar that carries the store to them.
  ;;
  ;; Announcement → artifact → process are three states, and nothing joined
  ;; them. `jar-currency` already computed the middle one for `session_brief`;
  ;; this is the sentence a milestone can say.
  (let [st {:deltas [{:id "d1" :at 100 :op :add :ns 'a.b}
                     {:id "d2" :at 200 :op :add :ns 'a.b}
                     {:id "d3" :at 300 :op :add :ns 'a.b}]}]
    (testing "a jar built from the head has nothing to report"
      (is (nil? (orient/jar-warning st "d3"))))
    (testing "a jar behind the store names its own head and what it costs"
      (let [w (orient/jar-warning st "d1")]
        (is (string? w) (pr-str w))
        (is (str/includes? w "d1") (str "it must name the head it was built from: " w))))
    (testing "a FOREIGN jar head makes no claim about this store"
      ;; slopp's jar serves projects that are not slopp, so a head from one
      ;; store and a delta log from another share nothing — counting deltas
      ;; after it here would measure how fast THIS reader has been writing and
      ;; report it as the tool's age
      (is (nil? (orient/jar-warning st "d-from-some-other-store"))))
    (testing "and no jar at all is silence, not a warning"
      ;; a checkout or a bare -M run has no artifact to be stale
      (is (nil? (orient/jar-warning st nil))))))
