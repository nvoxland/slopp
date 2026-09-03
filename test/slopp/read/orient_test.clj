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
  (let [fat {:commit-points [{:commit "d9" :description "m"}]
             :changes (vec (for [i (range 80)]
                             {:ns (symbol (str "big.ns" i)) :form (symbol (str "fn" i))
                              :ops [:replace]
                              :asks [(apply str (repeat 130 "x")) (apply str (repeat 130 "y"))]}))
             :suite {:status :green} :verify "test_run"}
        r  (#'orient/fit-report fat)]
    (is (<= (count (pr-str r)) 6500) (str (count (pr-str r))))
    (is (seq (:commit-points r)))
    (is (re-find #"narrows" (str (:note r))) (pr-str (keys r)))))

(deftest fit-report-aggregates-instead-of-amputating
  (let [fat {:commit-points [{:commit "d9" :description "m"}]
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
  ;; the jar, and it was the only one with no report. The two journal reads
  ;; (`slopp.store.db/last-artifact-put`, `ops-after`) are handed in; what is
  ;; under test here is the discrimination over them.
  (let [artifact {:id "c1" :op :artifact-put :entry {:sha "abc"}}
        st       (-> (store/empty-store)
                     (store/ingest 'app.view "(ns app.view)\n\n(defn v \"V.\" [s] s)\n")
                     (as-> s (first (store/record-module-platform s "app.view" :cljc)))
                     (store/ingest 'app.other "(ns app.other)\n\n(defn w \"W.\" [s] s)\n")
                     (as-> s (first (store/record-module-platform s "app.other" :cljs)))
                     (store/ingest 'app.server "(ns app.server)\n\n(defn h \"H.\" [r] r)\n"))]

    (testing "no bundle recorded is NIL, not zero"
      ;; zero would claim a bundle exists and is current, which is the stronger
      ;; form of the mistake this whole record exists to avoid
      (is (nil? (orient/bundle-currency st nil []))))

    (testing "a fresh compile is behind nothing"
      (is (= {:sha "abc" :behind 0} (orient/bundle-currency st artifact []))))

    (testing "a CLIENT write after the compile counts"
      (is (= 1 (:behind (orient/bundle-currency
                         st artifact [{:id "x" :op :ingest :ns 'app.other}])))
          "a :cljs namespace written after the compile is IN the browser's
           blind spot — the bundle predates it")
      (is (= 2 (:behind (orient/bundle-currency
                         st artifact [{:id "x" :op :ingest :ns 'app.other}
                                      {:id "y" :op :replace :ns 'app.view}])))
          "and :cljc counts the same way"))

    (testing "and a JVM-only write does NOT, nor does a marker"
      ;; the discrimination that makes this worth having rather than noisy: a
      ;; server-side edit cannot stale a browser bundle, and reporting it would
      ;; train the reader to ignore the number
      (is (= 0 (:behind (orient/bundle-currency
                         st artifact [{:id "x" :op :replace :ns 'app.server}
                                      {:id "y" :op :verify :ns 'app.view}
                                      {:id "z" :op :done :ns '*session*}])))
          "a :jvm write is not something the browser can be behind on"))))

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

(deftest a-commit-point-can-be-green-while-the-jar-was-never-built
  ;; Friction #17, hit twice in one night and caught both times by a CONSUMER
  ;; reading the artifact rather than by anything slopp said. A commit-point is
  ;; the announcement other people act on, and it was making a claim about the
  ;; store while saying nothing about the jar that carries the store to them.
  ;;
  ;; Announcement → artifact → process are three states, and nothing joined
  ;; them. `slopp.ops/jar-currency` derives the middle one from the journal;
  ;; this is the sentence a commit-point can say about it.
  (testing "a jar built from the head has nothing to report"
    (is (nil? (orient/jar-warning {:head "d3" :behind 0}))))
  (testing "a jar behind the store names its own head and what it costs"
    (let [w (orient/jar-warning {:head "d1" :behind 2})]
      (is (string? w) (pr-str w))
      (is (str/includes? w "d1") (str "it must name the head it was built from: " w))
      (is (str/includes? w "2 code deltas") w)))
  (testing "a FOREIGN jar head makes no claim about this store"
    ;; slopp's jar serves projects that are not slopp, so a head from one
    ;; store and a delta log from another share nothing — the currency map
    ;; carries no :behind, and the warning stays silent
    (is (nil? (orient/jar-warning {:head "d-from-some-other-store"}))))
  (testing "and no jar at all is silence, not a warning"
    ;; a checkout or a bare -M run has no artifact to be stale
    (is (nil? (orient/jar-warning nil)))))

(deftest orient-map-ranks-the-neighbourhood-of-an-ask-and-names-why
  ;; Measured on this store (2026-08-29): 48 tool calls per ask, most of them
  ;; the same tool with a different name argument, before an agent had the
  ;; dozen forms an ask actually turns on. `orient-map` is the one budgeted
  ;; call: seeds from the ask (or named), a personalized walk over the
  ;; reference graph and the trace map's coverage edges, and every row saying
  ;; which edge put it there.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'o.core
                               (str "(ns o.core)\n"
                                    "(defn c \"Leaf.\" [x] x)\n"
                                    "(defn b \"Middle — calls c.\" [x] (c x))\n"
                                    "(defn a \"Entry — calls b.\" [x] (b x))\n"
                                    "(defn unrelated \"Nothing here.\" [x] x)\n"))
                 (store/ingest 'o.core-test
                               (str "(ns o.core-test (:require [clojure.test :refer [deftest is]] [o.core :as c]))\n"
                                    "(deftest b-t (is (= 1 (c/b 1))))\n")))
        sess (atom {:store st :test-map {'o.core-test/b-t #{'o.core/b}}})
        rank (fn [r] (mapv :form (:rows r)))
        pos  (fn [r q] (.indexOf ^java.util.List (rank r) q))]
    (testing "seeding on a ranks its callee b above b's callee c, and unrelated last"
      (let [r (orient/orient-map sess :seeds ["o.core/a"] :tokens 4000)]
        (is (= ['o.core/a] (:seeds r)))
        (is (< (pos r 'o.core/a) (pos r 'o.core/b) (pos r 'o.core/c)) (pr-str (rank r)))
        (is (= -1 (pos r 'o.core/unrelated))
            "a form the walk never reached is not an answer to the ask")))
    (testing "every row names the edge that made it relevant"
      (let [r   (orient/orient-map sess :seeds ["o.core/a"] :tokens 4000)
            row (fn [q] (first (filter #(= q (:form %)) (:rows r))))]
        (is (= "seed" (:via (row 'o.core/a))))
        (is (re-find #"called by o\.core/a" (:via (row 'o.core/b))) (pr-str (row 'o.core/b)))
        (is (re-find #"covered by o\.core-test/b-t" (:via (row 'o.core/b)))
            "the trace map's coverage is an edge like any other")
        (is (re-find #"called by o\.core/b" (:via (row 'o.core/c))) (pr-str (row 'o.core/c)))
        (is (:sig (row 'o.core/b)) "and a row is a card — signature, doc line")
        (is (= "Middle — calls c." (:doc (row 'o.core/b))))))
    (testing "an ask seeds itself from the form names it mentions"
      (let [r (orient/orient-map sess :ask "make unrelated return twice its input" :tokens 4000)]
        (is (= ['o.core/unrelated] (:seeds r)) (pr-str r))
        (is (= 'o.core/unrelated (first (rank r))))))
    (testing "the budget bounds the answer and says what it left out"
      (let [big   (orient/orient-map sess :seeds ["o.core/a"] :tokens 4000)
            small (orient/orient-map sess :seeds ["o.core/a"] :tokens 60)]
        (is (< (count (:rows small)) (count (:rows big))))
        (is (<= (:tokens small) 60) (pr-str (select-keys small [:tokens :budget])))
        (is (pos? (:more small)) "what was cut is counted, not silently dropped")
        (is (nil? (:more big)))))
    (testing "with nothing to seed on, the walk is a plain ranking of what the graph turns on"
      (let [r (orient/orient-map sess :tokens 4000)]
        (is (empty? (:seeds r)))
        (is (= 5 (count (:rows r))) (pr-str (rank r)))))))

(deftest orient-map-carries-the-seeds-source-inside-its-budget
  ;; eval10 (s1, s2 census): `orient` answered in ~10k chars and the agent
  ;; still made eleven reads before its first write — the map named the
  ;; entry point and the agent then fetched it. The forms the ask NAMES are
  ;; the ones about to be edited, so their source rides the row when the
  ;; budget allows: seeds first, with source, then the neighbourhood as
  ;; cards. A budget too small for a seed's source keeps its card.
  (let [st   (-> (store/empty-store)
                 (store/ingest 'os.core
                               (str "(ns os.core)\n"
                                    "(defn leaf \"Leaf.\" [x] x)\n"
                                    "(defn entry \"Entry — calls leaf.\" [x] (leaf x))\n")))
        sess (atom {:store st :test-map {}})
        row  (fn [r q] (first (filter #(= q (:form %)) (:rows r))))]
    (testing "a seed row carries its source; a neighbour stays a card"
      (let [r (orient/orient-map sess :seeds ["os.core/entry"] :tokens 4000)]
        (is (re-find #"\(defn entry" (:source (row r 'os.core/entry))) (pr-str (row r 'os.core/entry)))
        (is (nil? (:source (row r 'os.core/leaf))) "the neighbourhood is cards")
        (is (<= (:tokens r) 4000))))
    (testing "the source counts against the budget, and a budget too small for it keeps the card"
      (let [r (orient/orient-map sess :seeds ["os.core/entry"] :tokens 30)]
        (is (= 'os.core/entry (:form (first (:rows r)))) "the seed still leads")
        (is (nil? (:source (first (:rows r)))) (pr-str r))
        (is (<= (:tokens r) 30))))))

(deftest a-seed-arrives-with-its-test
  ;; eval11: bundle coverage of edited pre-existing forms was 60-75% and
  ;; the misses were almost all DEFTESTS. s10 then measured the first
  ;; fix's cost: rows APPENDED past the budget reshaped step-1 orientation
  ;; (8->16 turns, two takes). So the guarantee is now bounded: the TOP
  ;; seed's test only, DISPLACING tail cards inside the budget — never
  ;; growing the bundle. The fixture's tests share no name token with
  ;; their subjects, or they would arrive as seeds and prove nothing.
  (let [src (str "(ns sw.core (:require [clojure.test :refer [deftest is]]))\n"
                 "(defn rate \"Cents per unit.\" [x] (* 2 x))\n"
                 "(defn levy \"Cents per parcel.\" [x] (+ 7 x))\n"
                 "(defn ^:unused-ok a1 \"Caller.\" [x] (rate (levy x)))\n"
                 "(defn ^:unused-ok a2 \"Caller.\" [x] (rate (levy x)))\n"
                 "(defn ^:unused-ok a3 \"Caller.\" [x] (rate (levy x)))\n"
                 "(defn ^:unused-ok a4 \"Caller.\" [x] (rate (levy x)))\n"
                 "(defn ^:unused-ok a5 \"Caller.\" [x] (rate (levy x)))\n"
                 "(defn ^:unused-ok a6 \"Caller.\" [x] (rate (levy x)))\n"
                 "(deftest pricing-behaviour (is (= 4 (rate 2))))\n"
                 "(deftest surcharge-behaviour (is (= 9 (levy 2))))\n")
        sess (atom {:store (store/ingest (store/empty-store) 'sw.core src)})
        m    (orient/orient-map sess :ask "change how rate and levy combine" :tokens 220)]
    (testing "the TOP seed's test is among the rows even under a tight budget"
      (is (some #(re-find #"^tests " (str (:via %))) (:rows m))
          (pr-str (mapv (juxt :form :via) (:rows m)))))
    (testing "… and only the top seed's — the guarantee is bounded, not per seed"
      (is (= 1 (count (filter #(re-find #"^tests " (str (:via %))) (:rows m))))
          (pr-str (mapv (juxt :form :via) (:rows m)))))
    (testing "the guarantee DISPLACES — the reported budget is not exceeded"
      (is (<= (:tokens m) 220) (pr-str (select-keys m [:tokens :budget]))))
    (testing "a seed with no test gains no phantom row"
      (let [m2 (orient/orient-map
                (atom {:store (store/ingest (store/empty-store) 'nw.core
                                            "(ns nw.core)\n(defn ^:unused-ok lone \"L.\" [x] x)\n")})
                :ask "change lone" :tokens 220)]
        (is (not-any? #(re-find #"^tests " (str (:via %))) (:rows m2)))))))

(deftest ask-words-name-namespaces-too
  ;; eval22 step 2: "everywhere carriers are accepted (quoting, booking,
  ;; billing, invoices)" seeded nothing from those four namespaces — seeds
  ;; came from FORM names only, and "quoting"/"invoices" match no form. The
  ;; agent then read each namespace by hand, one call apiece.
  (let [st (-> (store/empty-store)
               (store/ingest 'logi.quoting "(ns logi.quoting)\n(defn quote-cents \"Q.\" [p] p)\n")
               (store/ingest 'logi.booking "(ns logi.booking)\n(defn book! \"B.\" [p] p)\n")
               (store/ingest 'logi.billing "(ns logi.billing)\n(defn bill \"Bill.\" [p] p)\n")
               (store/ingest 'logi.invoice "(ns logi.invoice)\n(defn invoice-str \"I.\" [p] (str p))\n")
               (store/ingest 'logi.carrier "(ns logi.carrier)\n(defn make-carrier \"C.\" [id] id)\n")
               (store/ingest 'logi.money "(ns logi.money)\n(defn cents \"M.\" [x] x)\n"))]
    (testing "a word names a namespace by its last segment, plural and -ing allowed"
      (is (= #{'logi.quoting 'logi.booking 'logi.billing 'logi.invoice 'logi.carrier}
             (set (orient/ask-namespaces st "Creating carriers with the :eco class must work everywhere carriers are accepted (quoting, booking, billing, invoices)."))))
      (is (= ['logi.invoice] (orient/ask-namespaces st "the invoices")))
      (is (empty? (orient/ask-namespaces st "the eco class")) "a short word or a stop word names nothing"))
    (testing "the map seeds the named namespaces' forms and says so"
      (let [r (orient/orient-map (atom {:store st :test-map {}})
                                 :ask "must work everywhere carriers are accepted (quoting, booking, billing, invoices)"
                                 :tokens 4000)
            forms (set (map :form (:rows r)))]
        (is (= #{'logi.quoting 'logi.booking 'logi.billing 'logi.invoice 'logi.carrier} (set (:ns-seeds r))) (pr-str r))
        (is (every? forms ['logi.quoting/quote-cents 'logi.booking/book! 'logi.billing/bill 'logi.invoice/invoice-str]) (pr-str forms))
        (is (not (contains? forms 'logi.money/cents)) "a namespace the ask did not name is not an answer to it")))))

(deftest the-bundle-sends-no-namespace-whole-and-names-the-flow-read
  ;; The whole-namespace section (2026-09-03, morning) bought turns by
  ;; sending more source: the file habit served faster, not replaced. The
  ;; bundle is the seeds with their source, cards with :v for the rest, and
  ;; a header that names query_flow for how things connect.
  (let [st  (-> (store/empty-store)
                (store/ingest 'logi.booking "(ns logi.booking)\n(defn book! \"B.\" [p] p)\n(defn cancel! \"C.\" [p] p)\n")
                (store/ingest 'logi.billing "(ns logi.billing)\n(defn bill \"Bill.\" [p] p)\n"))
        sess (atom {:store st :test-map {}})
        r    (orient/bundle sess "eco carriers everywhere: booking and billing; make bill charge more")
        txt  (:text r)]
    (is (not (re-find #"names, whole" txt)) txt)
    (is (not (re-find #"\(defn book!" txt)) "a namespace named by word is cards, not source")
    (is (re-find #"logi\.booking/book!" txt) "and its forms are on the map")
    (is (re-find #"query_flow" txt) "the header names the flow read")
    (is (re-find #":v \[" txt) "cards carry a version stamp")
    (is (re-find #"\(defn bill" txt) "a form the ask names by NAME still arrives whole")))
