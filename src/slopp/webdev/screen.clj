(ns slopp.webdev.screen
  "The `screen` TOOL — opening this store's app and driving it, so an agent can
  look at a screen with no code written and no browser opened.

  The framework half is `slopp.cljnx`, which SHIPS: any project can open a
  page, click, fill and read. This is the half that does not ship — finding the
  app's `^:app/entry` entry and running the driving where the app's vars live,
  which is slopp's own tooling and belongs beside `slopp.webdev.live`.

  **It drives in the VERIFICATION image, not the served app.** Looking at a
  screen to decide what to write next has to show the code you are writing; the
  served app can be behind, and `session_brief`'s `:app-behind` is the
  surface for that question instead.

  Deliberately thin. The steps travel as EDN and `slopp.cljnx/drive!`
  interprets them, so the tool and a test run the same interpreter rather than
  two producers of one behaviour."
  (:require [slopp.image.repl :as repl]))

(defn drive-code
  "The script that opens the app's declared page and drives it, as source to
  eval INSIDE the verification image. `opts`: `:url` (the address to open AT),
  `:region`, `:detail`, `:list-head` (the tool's cap — the test path's default
  is nil, but a LOOK is skimmed, so the tool caps and the elision is a
  machine-visible tag), `:trace` (one screen per step).

  A string rather than a call, for the same reason the schema oracle is one:
  the app's code lives in that image and nowhere else, so the driving has to
  happen where the vars are.

  **The `^:app/entry` entry is found by scanning the image's own vars**, which
  needs no store analysis and cannot disagree with what is actually loaded —
  a store-side scan would answer for source the image may not have reloaded.

  **`:url` is passed at OPEN, not prepended as a `{:visit …}` step.** That is
  where a browser takes an address, and the difference is not cosmetic: an app
  with no urls at all gets `open!`'s own one-arg call, so looking at a
  `{:state :view}` page keeps working untouched.

  **What comes back besides the page is what a browser would also tell you** —
  the address bar (which after a redirect is not what was asked for), the
  status, and the hops. Each is omitted when there is nothing to say, so an app
  that makes no requests answers exactly as it did before.

  Deliberately THIN: the steps travel as EDN and
  [[slopp.cljnx/drive!]] interprets them. A generated call chain would be
  a second producer of the driving behaviour, free to drift from the one a
  test exercises — which is why `:trace` drives ONE session one step at a
  time through the same `drive!` rather than unrolling a loop of its own. Not
  prefix re-drives either, deliberately: an app whose state outlives an open
  (a def'd atom) would re-run every non-idempotent step per prefix, and the
  trace's last screen would disagree with a plain run of the same script.

  The catch renders the CAUSE CHAIN, conditional separator and all, in parity
  with `slopp.read.query/cause-chain`. The two cannot be one fn because this
  code runs in a user's image where the only slopp on the classpath is the
  vendored framework, but they must not drift: a message-less cause with a
  trailing colon is how parity dies one cosmetic notch at a time."
  [steps {:keys [url region detail list-head trace]}]
  (let [shot-opts (pr-str {:detail detail :list-head list-head})
        open-call (str "(open (as-page ((var-get pv)))"
                       (when url (str " " (pr-str url)))
                       ")")]
    (str "(let [pv (first (for [n (all-ns) [_ v] (ns-publics n)"
         "                      :when (:app/entry (meta v))] v))]"
         "  (if-not pv"
         "    {:error \"no ^:app/entry in this store — mark the zero-arg fn that"
         " builds your app (a :http/routes ctx, a :webapp/routes declaration, or"
         " {:state :view}) and slopp can open it; nothing else has to change\"}"
         ;; fully qualified, NOT an alias: a `require` inside this form runs at
         ;; runtime while the body compiles at read time, so an :as here is a
         ;; "No such namespace" every time.
         "    (try"
         "      (let [open  (requiring-resolve 'slopp.cljnx/open!)"
         "            drive (requiring-resolve 'slopp.cljnx/drive!)"
         "            text  (requiring-resolve 'slopp.cljnx/text)"
         ;; THE shared derivation, not a copy of it. This branched on the
         ;; entry's shape itself for one milestone, which is a second wiring of
         ;; one app with nothing comparing it to the consumer's — so a project
         ;; whose tests spelled the wrapping by hand would drive a lookalike
         ;; and pass, each half asserting against its own reconstruction.
         ;; `driver-for` ships to every store, so pointing at it costs nothing
         ;; a copy would have saved.
         "            as-page (requiring-resolve 'slopp.cljnx/driver-for)"
         "            addr  (requiring-resolve 'slopp.cljnx/url)"
         "            stat  (requiring-resolve 'slopp.cljnx/status)"
         "            hops  (requiring-resolve 'slopp.cljnx/redirects)"
         "            steps " (pr-str (vec steps))
         "            shot  (fn [s] (text s " (pr-str region) " " shot-opts "))"
         ;; everything a browser reports BESIDES the page. Each key is omitted
         ;; when there is nothing to say, so a page that made no request
         ;; answers exactly as it did before this existed
         "            where (fn [s] (cond-> {}"
         "                            (addr s)       (assoc :url (addr s))"
         "                            (stat s)       (assoc :status (stat s))"
         "                            (seq (hops s)) (assoc :redirects (hops s))))]"
         (if trace
           (str "        (let [s " open-call "]"
                "          {:screens (mapv (fn [step]"
                "                            (drive s [step])"
                "                            (merge {:step step :screen (shot s)} (where s)))"
                "                          steps)"
                "           :entry (str pv)}))")
           (str "        (let [s (drive " open-call " steps)]"
                "          (merge {:screen (shot s) :entry (str pv)} (where s))))"))
         "      (catch Throwable e"
         "        {:error (clojure.string/join \" <- \""
         "                  (take 4 (map #(let [m (.getMessage %)]"
         "                                  (cond-> (.getSimpleName (class %))"
         "                                    (seq (str m)) (str \": \" m)))"
         "                               (take-while some? (iterate #(.getCause %) e)))))"
         "         :entry (str pv)}))))")))

(defn ^:export screen!
  "Look at a screen of THIS store's app, driven headlessly. The `screen` tool.

  `url` is the address to open AT — what you would type into a browser.
  `steps` is an ordered script of `{:visit path}` / `{:click label}` /
  `{:fill field :value v}`, run after it; `region` scopes the answer to one
  pane; `detail` is `\\\"structured\\\"` (default) or `\\\"prose\\\"`; `trace`
  returns one screen per step, driving ONE session a step at a time through
  the same interpreter a test uses.

  Runs in the VERIFICATION image, so what comes back is built from the code
  the store currently holds — the same oracle every write is checked against,
  and deliberately not the SERVED app, which can be behind. Looking at a
  screen to decide what to write next must show the code you are writing.

  Returns `{:screen <text> :entry <the ^:app/entry var>}` (`:screens [{:step
  :screen} …]` under trace), plus what a browser reports besides the page:
  `:url` — the address bar, which after a redirect is not what you asked for —
  `:status`, and `:redirects` when the app sent you somewhere. Each is absent
  when there is nothing to say, so an app that makes no requests answers as it
  always did. The entry travels on purpose: a screen is only as trustworthy as
  the app it came from, and a store with two marked pages would otherwise
  answer from whichever the scan reached first without ever saying which.

  Input it cannot mean is refused HERE, before the image: a `detail` outside
  its two values used to silently render structured (a wrong default reported
  as success), and a malformed one corrupted the GENERATED code, whose read
  failure came back blamed on the image's answer. An eval-level error string
  now IS `:error` — it is the diagnosis, not an unreadable answer — and only a
  genuinely unreadable non-map still lands under `:raw`."
  [session & {:keys [url steps region detail trace]}]
  (let [d (or detail "structured")]
    (cond
      (not (contains? #{"structured" "prose"} d))
      {:error (str "detail must be \"structured\" or \"prose\" — got "
                   (pr-str detail) ". A silent default over a typo would"
                   " answer the wrong question confidently")}

      (and (some? url) (not (string? url)))
      {:error (str "url must be a string — the address you would type, like"
                   " \"/things/42\" — got " (pr-str url))}

      (and steps (not (and (sequential? steps) (every? map? steps))))
      {:error (str "steps must be an array of step objects — {visit …} /"
                   " {click …} / {fill … value …} — got " (pr-str steps))}

      :else
      (let [r (first (repl/eval! (:image @session)
                                 (drive-code (or steps [])
                                             {:url       url
                                              :region    region
                                              :detail    (keyword d)
                                              :list-head 3
                                              :trace     (boolean trace)})))]
        (cond
          (map? r)    r
          (nil? r)    {:error "the image returned nothing for this screen"}
          (string? r) {:error r}
          :else       {:error "the image's answer was not readable as data"
                       :raw (str r)})))))
