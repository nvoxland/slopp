(ns slopp.cli.spec
  "What a command line MEANS, as pure functions — argv and a declared schema in,
  typed arguments or teaching refusals out.

  Split from `slopp.cli` for the reason the whole capability exists: argv is
  UNTRUSTED INPUT, and every decision about it — is this option declared, does
  this value fit its type, is a positional missing — is decidable from data. A
  command that parsed its own arguments would put that boundary inside its
  business logic, where nothing checks it and each command re-implements it
  slightly differently.

  So this namespace holds the deciding and `slopp.cli` holds the streams. That
  split is what makes the interesting cases ordinary in-image assertions: a
  wrong flag, a value out of range, a typo'd option name and `--help` on a
  broken line are all `=` on a map here, with no process and no captured
  output.

  **Arguments are declared in malli**, the same language `rest` declares an
  endpoint's contract in, so one shape can serve a command and an endpoint
  without being written twice. Positionals are a `:catn` because argv is
  ordered AND named and that is the one malli schema which is both; options are
  a `:map`. Neighbours: `slopp.cli` performs what this decides."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]
            [malli.error :as me]))

(def ^:export Command
  "What a command DECLARES, as a schema — the shape carried in a `defn`'s name
  metadata and read by everything downstream.

  Named rather than inlined because four separate readers consume it — the
  parser, the help generator, the runner and the surface report — and a shape
  described four times is the registry-and-consumer drift this codebase keeps
  removing.

  `:cli/args` and `:cli/opts` are themselves malli schemas, so they are `:any`
  here rather than pretending to constrain what a schema looks like: malli has
  no schema-for-schemas that is cheaper than `m/schema` throwing, and claiming
  otherwise would be a contract that reports clean about shapes it cannot
  check."
  [:map
   [:cli/command :string]
   [:cli/doc {:optional true} :string]
   [:cli/args {:optional true} :any]
   [:cli/opts {:optional true} :any]])

(defn- split-argv
  "Split `argv` into `[positionals {opt-name raw-value}]`, interpreting nothing.

  Knows the SHAPE of a command line and nothing about any schema: `--opt v`,
  `--opt=v`, and a bare `--flag` (recorded as `\"true\"`, since only the schema
  knows whether a flag is boolean). A value is consumed for `--opt v` only when
  the next token is not itself an option, so `cmd --verbose --name x` does not
  silently eat `--name` as `--verbose`'s value.

  `--` ends option parsing: everything after it is positional however it is
  spelled, which is the only way to pass a value that begins with a dash.

  Raw strings out on purpose. Coercion belongs to the schema, and a splitter
  that guessed types would be a second place that decides what `\"5\"` means."
  [argv]
  (loop [[a & more :as all] (seq argv) pos [] opts {}]
    (cond
      (empty? all) [pos opts]

      (= "--" a) [(into pos more) opts]

      (str/starts-with? (str a) "--")
      (let [body (subs (str a) 2)
            [k v] (if-let [i (str/index-of body "=")]
                    [(subs body 0 i) (subs body (inc i))]
                    [body nil])
            next* (first more)
            takes-next? (and (nil? v) next* (not (str/starts-with? (str next*) "-")))]
        (recur (if takes-next? (rest more) more)
               pos
               (assoc opts (keyword k) (cond v v takes-next? (str next*) :else "true"))))

      :else (recur more (conj pos a) opts))))

(defn- nearest
  "The declared option name closest to `typed`, or nil when nothing is close.

  An unknown option REFUSES rather than being ignored: the reader typed
  something meaning to change behaviour, and doing nothing is the nil-pun. But
  a refusal that only says \"unknown\" makes them diff two strings by eye, and
  the cause is overwhelmingly a typo.

  Levenshtein with a distance CEILING proportional to the length typed —
  naming an unrelated option as the suggestion is worse than offering none,
  because it sends a reader to change the wrong thing. The population is one
  command's options, so the cost of the table never matters."
  [typed names]
  (let [t  (str typed)
        d  (fn [a b]
             (let [n (count b)]
               (peek
                (reduce
                 (fn [prev i]
                   (reduce
                    (fn [row j]
                      (conj row
                            (if (zero? j)
                              i
                              (min (inc (peek row))
                                   (inc (nth prev j))
                                   (+ (nth prev (dec j))
                                      (if (= (nth a (dec i)) (nth b (dec j))) 0 1))))))
                    [] (range (inc n))))
                 (vec (range (inc n)))
                 (range 1 (inc (count a)))))))
        best (first (sort-by second (for [n names] [n (d t (name n))])))]
    (when (and best (<= (second best) (max 1 (quot (count t) 3))))
      (first best))))

(defn ^:export parse
  "Parse `argv` against a command's declared `spec` — `{:cli/args <:catn>
  :cli/opts <:map>}`.

  Answers exactly one of:

  - `{:help? true}` — `--help` appeared anywhere;
  - `{:errors [teaching …]}` — with `:args` ABSENT;
  - `{:args {…}}` — every positional and option typed per its schema.

  **Never both.** argv is UNTRUSTED INPUT, and a partial parse reaching a
  handler is how a command ends up re-implementing the boundary in its body,
  slightly differently each time. So a single bad value withholds the whole
  map.

  **`--help` is answered BEFORE parsing**, which is not a nicety: the reader
  asking what the arguments ARE is exactly the reader who has them wrong, so
  help that only works on a correct command line works when it is not needed.

  Positionals are a `:catn` because argv is ordered AND named and that is the
  one malli schema that is both — `m/children` recovers the names, so an error
  can say `text` rather than `argument 0`. Options are a `:map`.

  **The COUNT being wrong is one mistake and is reported as one sentence**, in
  both directions. malli sees a shortfall as a problem per unfilled slot and
  names it `end of input`, which describes the parser's situation rather than
  the caller's — and reaches a caller who never wrote a schema. Too many and
  too few are the same event from opposite sides, so they read the same way and
  both name what the command actually takes, which is the next thing typed.

  Declared `:default`s are applied HERE rather than by
  `mt/default-value-transformer`, which does not fill `:optional` entries. The
  guarantee is that a declared option is always `contains?` the result: a
  caller reading it gets a value or the default, and never meets `nil` standing
  for \"unset\" — the same conflation the four-state load model removes one
  layer up."
  [spec argv]
  (let [argv (vec argv)]
    (if (some #{"--help" "-h"} argv)
      {:help? true}
      (let [[pos opts]   (split-argv argv)
            args-schema  (:cli/args spec)
            opts-schema  (:cli/opts spec)
            names        (when args-schema (mapv first (m/children args-schema)))
            declared     (when opts-schema (mapv first (m/children opts-schema)))
            props        (when opts-schema
                           (into {} (map (juxt first second)) (m/children opts-schema)))
            xf           (mt/string-transformer)
            unknown      (for [k (keys opts)
                               :when (not (some #{k} declared))]
                           (str "--" (name k) " is not an option of this command"
                                (if-let [n (nearest (name k) declared)]
                                  (str " — did you mean --" (name n) "?")
                                  (if (seq declared)
                                    (str " (options: "
                                         (str/join ", " (map #(str "--" (name %)) declared))
                                         ")")
                                    " (this command takes no options)"))))
            takes        (delay (str "this command takes " (count names) " argument"
                                     (when (not= 1 (count names)) "s")
                                     " (" (str/join ", " (map name names)) ")"))
            decoded-pos  (when args-schema (m/decode args-schema pos xf))
            pos-errors   (when args-schema
                           (for [[nm problem] (map vector names
                                                   (me/humanize (m/explain args-schema decoded-pos)))
                                 :when problem
                                 :let [text (str/join ", " (flatten [problem]))]
                                 ;; the unfilled slots are counted below, as one
                                 ;; event; a per-slot echo of them would report
                                 ;; a single mistake three times
                                 :when (not= "end of input" text)]
                             (str (name nm) " " text)))
            ;; a :catn reports a trailing surplus as one more problem than it
            ;; has entries, so the zip above cannot see it — asked separately
            extra        (when (and args-schema names (> (count pos) (count names)))
                           [(str @takes ", got " (count pos))])
            missing      (when (and args-schema names (< (count pos) (count names)))
                           [(str @takes " — missing: "
                                 (str/join ", " (map name (drop (count pos) names))))])
            decoded-opts (when opts-schema (m/decode opts-schema opts xf))
            opt-errors   (when opts-schema
                           (for [[k problem] (me/humanize (m/explain opts-schema decoded-opts))]
                             (str "--" (name k) " " (str/join ", " (flatten [problem])))))
            errors       (vec (concat unknown extra missing pos-errors opt-errors))]
        (if (seq errors)
          {:errors errors}
          {:args (merge (into {} (for [[k v] props
                                       :when (and (contains? v :default)
                                                  (not (contains? decoded-opts k)))]
                                   [k (:default v)]))
                        decoded-opts
                        (when names (zipmap names decoded-pos)))})))))

(defn ^:export schema-entries
  "The `[name props schema]` entries of malli schema form `s`, or nil when `s`
  is not a schema at all.

  Total on purpose. `:cli/args` and `:cli/opts` are `:any` in [[Command]]
  because malli has no cheap schema-for-schemas, so every reader of them has to
  survive being handed something that is not one — and the reader who most
  needs to survive it is HELP, which a person reaches for precisely when
  something is already wrong.

  A malformed declaration is caught at the WRITE by the cli gates, where the
  author can fix it. Throwing here would instead punish the reader, and would
  make `help-text` a partial function whose `:=>` contract could only be
  honest by claiming it throws."
  [s]
  (when s
    (try (m/children (m/schema s))
         (catch Exception _ nil))))

(defn ^:export ^{:malli/schema [:=> {:throws []} [:cat slopp.cli.spec/Command] :string]} help-text
  "Usage for one command, DERIVED from the same schemas `parse` validates
  against.

  A command cannot carry a hand-written usage string, which is the point: help
  and validation are the same facts read twice, and writing them twice is how a
  usage line ends up teaching a command line that refuses. The reader trusts
  help more than they trust the code, so stale help is worse than none.

  Defaults are shown because the question a reader has about an optional flag
  is what happens if they leave it out, and an entry's `:doc` rides through
  because a malli property map is the only place a REASON can live next to the
  shape."
  [{:cli/keys [command doc args opts]}]
  (let [positional (mapv first (schema-entries args))
        entries    (schema-entries opts)
        usage      (str "Usage: " command
                        (when (seq entries) " [options]")
                        (str/join "" (for [p positional] (str " <" (name p) ">"))))
        opt-line   (fn [[k props schema]]
                     (str "  --" (name k)
                          (when-not (= :boolean (m/type schema)) (str " <" (name k) ">"))
                          (when-let [d (:doc props)] (str "    " d))
                          (when (contains? props :default)
                            (str " (default " (pr-str (:default props)) ")"))))]
    (str/join "\n"
              (cond-> [usage]
                doc           (conj "" doc)
                (seq entries) (into (cons "" (cons "Options:" (map opt-line entries))))))))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:cli/name :string]
                          [:cli/commands [:map-of :string :any]]]]
                   :string]}
  program-help
  "What a BARE invocation answers: the program's name and every command it has,
  each with its own `:cli/doc`.

  A program invoked with no arguments has been asked a question, and answering
  it with a usage error alone makes the reader run a second command to learn
  anything. The reader who typed the name by itself is the one who knows least
  about it.

  Commands are listed in NAME order rather than declaration order, because
  declaration order is an accident of which namespace loaded first, and a
  reader scanning for a name expects the sorted list."
  [{:cli/keys [name commands]}]
  (str/join
   "\n"
   (concat [(str "Usage: " name " <command> [args]") "" "Commands:"]
           (for [[cmd command] (sort-by key commands)]
             (str "  " cmd (when-let [d (:cli/doc command)] (str "    " d))))
           ["" (str "Run '" name " <command> --help' for a command's arguments.")])))

(defn ^:export
  ^{:malli/schema [:=> {:throws []}
                   [:cat [:map [:cli/name :string]
                          [:cli/commands [:map-of :string :any]]] :string]
                   :string]}
  unknown-command-error
  "The refusal for a command name this program does not have.

  Names the commands that DO exist and suggests the near-miss, which is the
  same refusal an unknown option gets one level down and for the same reason: a
  mistyped name is overwhelmingly the cause, and a refusal that only says
  \"unknown\" makes the reader go and look up what they could have typed."
  [{:cli/keys [name commands]} typed]
  (let [known (sort (keys commands))]
    (str name ": '" typed "' is not a command"
         (when-let [n (nearest typed known)] (str " — did you mean '" n "'?"))
         "\n\nCommands: " (str/join ", " known))))

(defn ^:export render
  "Turn a command's returned value into the text a reader sees.

  This function is the other half of the decision that a command returns DATA.
  Something has to produce characters, and putting it HERE rather than in every
  handler is what buys the property: the handler stays an `=` on a map, and the
  presentation is one place that can be changed — or, later, switched to JSON
  by a flag — without editing a single command.

  A map prints as aligned `key: value` lines. **A sequence of MAPS prints as a
  table**, header once, columns aligned. Anything else prints as itself.
  `:cli/exit` is dropped because it is a CONTROL key rather than part of the
  answer, and printing it would put slopp's own vocabulary in the app's output.

  **The table is here because dogfooding measured its absence at 36 KB.** A
  `list` command returns a sequence of maps — that is what a list IS — and
  pr-str per row gave a wall of EDN that was correct, machine-readable, and
  unreadable. The framework had taken responsibility for presentation and then
  had none for the single commonest shape.

  **The line this docstring used to draw is still drawn, one step further
  out.** No colour, no wrapping, no configuration, no column selection: a cell
  is truncated at a fixed width because a table wider than a terminal is a wall
  again, and that is the last accommodation. A command wanting more returns the
  string it wants — the escape is unchanged and is still the honest answer for
  anything a table cannot say.

  Columns come from the first row, then any key later rows add. Rows are DATA
  and a caller controls what it puts in them, so choosing columns here would be
  slopp overriding an app about its own answer."
  [value]
  (let [cell (fn [v] (let [s (if (string? v) v (pr-str v))]
                       (if (> (count s) 40) (str (subs s 0 39) "…") s)))]
    (cond
      (nil? value) ""

      (map? value)
      (let [rows (dissoc value :cli/exit)
            w    (reduce max 0 (map (comp count name key) rows))]
        (str/join "\n" (for [[k v] rows]
                         (str (format (str "%-" (max 1 w) "s") (name k)) "  "
                              (if (string? v) v (pr-str v))))))

      ;; a sequence of maps is a TABLE. `every? map?` rather than `map? (first)`
      ;; so a mixed sequence falls through to the plain rendering instead of
      ;; producing a table with holes in it.
      (and (sequential? value) (seq value) (every? map? value))
      (let [cols  (reduce (fn [acc r] (into acc (remove (set acc)) (keys r)))
                          (vec (keys (first value)))
                          (rest value))
            cells (for [r value] (mapv #(cell (get r %)) cols))
            w     (mapv (fn [c i] (reduce max (count (name c))
                                          (map #(count (nth % i)) cells)))
                        cols (range))
            line  (fn [vs] (str/trimr
                            (str/join "  " (map #(format (str "%-" (max 1 %2) "s") %1)
                                                vs w))))]
        (str/join "\n" (cons (line (map name cols)) (map line cells))))

      (sequential? value)
      (str/join "\n" (map #(if (string? %) % (pr-str %)) value))

      :else (str value))))

(defn ^:export schema-names
  "The KEY NAMES malli schema form `s` describes, or the schema's own TYPE when
  there is nothing to enumerate. nil when `s` is not a schema at all.

  The companion to [[schema-entries]] and total for the same reason, with one
  more failure it exists to prevent. `(mapv first (m/children …))` reads a
  `:map`'s `[k props schema]` entries; EVERY other schema's children are
  compiled schema objects, and `first` on one of those throws. Measured on a
  real store the day its typed API was turned on: `query_surface` died with
  `Don't know how to create ISeq from: malli.core$_map_schema$reify` because a
  single endpoint answered `[:or [:map …] [:map …]]`. Nine endpoints were
  unreadable because of a tenth.

  **A report over a population must not be hostage to one member**, and the
  member most likely to be unusual is the one somebody reached for when a plain
  map would not do.

  A map under a single-child collection — `[:sequential [:map …]]` — reports the
  INNER keys, because a list endpoint is the commonest non-map contract there is
  and answering `:sequential` alone throws away everything the reader came for.
  Anything else answers with its type, which is a fact about the schema rather
  than `[]`, which would be a claim about the contract."
  [s]
  (let [sch (try (m/schema s) (catch Exception _ nil))]
    (when sch
      (loop [sch sch depth 0]
        (let [t  (m/type sch)
              ch (try (m/children sch) (catch Exception _ nil))]
          (cond
            (= :map t) (mapv first ch)

            (and (< depth 3) (= 1 (count ch))
                 (contains? #{:sequential :vector :set :maybe :seqable :every} t))
            (recur (first ch) (inc depth))

            :else t))))))
