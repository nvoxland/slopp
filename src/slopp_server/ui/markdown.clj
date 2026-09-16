(ns slopp-server.ui.markdown
  "Docstring markdown → hiccup, because slopp ships no renderer and said so.

  Every docstring this app displays is markdown by DECLARATION (slopp's
  `D-doc-markdown`, 2026-08-24) with one non-markdown construct to expect:
  `[[wikilinks]]`. Nothing in slopp reads those — no rule, no index, no
  derivation — so resolving them is entirely this app's business, and
  `views/doc-link` is where a target becomes an address.

  **slopp declined to ship the renderer, and the reason is worth keeping**
  rather than reading as a refusal. This project argued for a framework helper
  the way it won one for percent-decoding, and the distinction drawn back was
  that decoding has one correct answer while rendering does not: a UI decides
  what a table, a code span and a bullet list look like in its own design, and
  a shipped renderer would be slopp making that decision for every consumer.

  `:pure`, and that is load-bearing: every screen showing a docstring is a
  `:pure` view, and a renderer that reached anything looser would drag them all
  down a tier. It requires `clojure.string` and nothing else — no regex engine
  interop, which is why [[inline]] scans BY INDEX. `re-matcher` and `.start`
  are JVM interop and this namespace is `:cljc`.

  Expect: a scanner ([[inline]], [[marks]]) for spans inside one line, a
  fence-aware grouper ([[chunks]]) for what a blank line separates, and
  [[block]] for what each group becomes. [[as-hiccup]] is the entry."
  (:require [clojure.string :as str]))

(defn- dedent
  "`text`'s lines with the docstring INDENTATION removed.

  Every line after the first is indented to the opening quote, which is a fact
  about the source and not about the text. Left in, markdown reads four of those
  spaces as a code block and the whole docstring renders as one.

  The first line is exempt because it begins AT the quote and carries no
  indentation to strip. The common indent of the rest is what comes off, so a
  block that is deliberately indented further keeps the difference."
  [text]
  (let [lines (str/split-lines (str text))
        tail  (remove str/blank? (rest lines))
        n     (if (seq tail)
                (apply min (map #(count (re-find #"^ *" %)) tail))
                0)]
    (cons (first lines)
          (map #(if (>= (count %) n) (subs % n) %) (rest lines)))))

(defn- coalesce
  "`nodes` with adjacent STRINGS joined.

  An unclosed mark emits its opener as text and continues, so `\"a ` b\"` arrives
  here as two strings that are one run of prose. Joining them is what makes an
  unclosed mark indistinguishable from text that never had one."
  [nodes]
  (reduce (fn [acc n]
            (if (and (string? n) (string? (peek acc)))
              (conj (pop acc) (str (peek acc) n))
              (conj acc n)))
          []
          nodes))

(def ^:private marks
  "The inline marks, as `[opener closer kind]`.

  **Three, because three is what this store uses** — 158 code spans, 92
  `**strong**` and 91 `[[wikilinks]]` across 189 public docstrings. Single-`*`
  emphasis is deliberately absent: nothing writes it, and supporting it would
  make every literal asterisk in prose a mark to escape."
  [["`"  "`"  :code]
   ["**" "**" :strong]
   ["[[" "]]" :wiki]])

(defn- inline
  "One line of markdown as a seq of hiccup nodes.

  **Scanned by INDEX, not by regex position.** `re-matcher` and `.start` are JVM
  interop and this namespace is `:cljc`, so the portable move is `index-of` and
  a loop. It is also the clearer one: the rule is *earliest opener wins*, and
  that is what the code says.

  Earliest-wins is what makes a wikilink inside a code span literal —
  `` `[[x]]` `` opens on the backtick, and everything to its closer is text.

  **An unclosed opener is TEXT.** It emits the opener and continues past it, so
  a stray backtick in prose costs three characters rather than swallowing the
  rest of the document. That failure is the one worth designing against: a
  renderer that ate the page would look like a data problem."
  [line link-for]
  (coalesce
   (loop [s (str line), out []]
     (let [hit (->> marks
                    (keep (fn [[open close kind]]
                            (when-let [i (str/index-of s open)]
                              [i open close kind])))
                    (sort-by first)
                    first)]
       (if-not hit
         (if (seq s) (conj out s) out)
         (let [[i open close kind] hit
               after (+ i (count open))
               j     (str/index-of s close after)]
           (if-not j
             ;; unclosed: the opener is prose. Emit up to and including it.
             (recur (subs s after) (conj out (subs s 0 after)))
             (let [before (subs s 0 i)
                   inner  (subs s after j)
                   node   (case kind
                            :code   [:code inner]
                            :strong [:strong inner]
                            ;; a wikilink with no resolver degrades to its own
                            ;; text — the property that made Nathan keep the
                            ;; syntax over `[name](#target)`.
                            :wiki   (if-let [href (and link-for (link-for inner))]
                                      [:a {:href href} inner]
                                      [:code inner]))]
                              (recur (subs s (+ j (count close)))
                      (-> (cond-> out (seq before) (conj before))
                          (conj node)))))))))))

(defn- chunks
  "`lines` grouped into blocks, as a seq of line-seqs.

  **A FENCE survives a blank line and everything else splits on one.** That
  asymmetry is the whole reason fences are found here rather than after a
  blank-line split: splitting first cuts a fenced block in half, and the two
  halves then render as paragraphs with the code marked up as prose. A `**` in
  a code sample would come out bold.

  The fence lines stay in the chunk; [[block]] strips them, because that is
  where the chunk's kind is decided and stripping is part of rendering it."
  [lines]
  (loop [[l & more :as all] lines, cur [], acc [], fence? false]
    (let [fence-line? (and l (str/starts-with? (str/trim l) "```"))
          flush       (fn [] (if (seq cur) (conj acc cur) acc))]
      (cond
        (empty? all) (if (seq cur) (conj acc cur) acc)
        fence?       (if fence-line?
                       (recur more [] (conj acc (conj cur l)) false)
                       (recur more (conj cur l) acc true))
        fence-line?  (recur more [l] (flush) true)
        (str/blank? l) (recur more [] (flush) false)
        :else        (recur more (conj cur l) acc false)))))

(defn- cells
  "A table row's cells, without the delimiting pipes."
  [line]
  (->> (str/split line #"\|")
       (drop 1)
       (map str/trim)
       vec))

(defn- block
  "One chunk of `lines` as ONE hiccup block.

  The kinds are the ones this store's docstrings contain, counted rather than
  assumed: 8 bullet lists, 3 fenced blocks, 2 tables, 2 numbered lists, and no
  headings or blockquotes at all. **A chunk matching none of them is a
  paragraph**, which is the honest default — prose is what most of a docstring
  is, and a kind nobody writes would be a guess at a format this store does not
  have.

  A fenced block is VERBATIM: its content never reaches [[inline]], so `**` in a
  code sample stays two asterisks. That is the one place where not parsing is
  the feature."
  [lines link-for]
  (let [trimmed (mapv str/trim lines)
        head    (first trimmed)
        item    (fn [tag re ls]
                  (into [tag]
                        (map #(into [:li] (inline (str/replace % re "") link-for)) ls)))]
    (cond
      (str/starts-with? head "```")
      (let [body (cond-> (vec (rest lines))
                   (str/starts-with? (str/trim (or (last lines) "")) "```")
                   pop)]
        [:pre [:code (str/join "\n" body)]])

      (and (every? #(str/starts-with? % "|") trimmed) (< 1 (count trimmed)))
      (let [rows (remove #(re-matches #"[\s|:-]+" %) trimmed)]
        [:table
         [:thead (into [:tr] (map #(into [:th] (inline % link-for))
                                  (cells (first rows))))]
         (into [:tbody]
               (map (fn [r] (into [:tr] (map #(into [:td] (inline % link-for))
                                             (cells r))))
                    (rest rows)))])

      (every? #(re-find #"^[-*] " %) trimmed) (item :ul #"^[-*] " trimmed)
      (every? #(re-find #"^\d+\. " %) trimmed) (item :ol #"^\d+\. " trimmed)

      :else (into [:p] (inline (str/join " " trimmed) link-for)))))

(defn as-hiccup
  "Markdown `text` as a seq of hiccup BLOCKS.

  Docstrings are markdown — slopp declared it (`D-doc-markdown`) after this
  project reported that the API page rendered a three-paragraph docstring as one
  wall of text. **The rendering is this store's**, all of it, including
  `[[wikilink]]` resolution: slopp ships no renderer on the argument that
  *decoding has one correct answer and rendering does not*, so what a code span
  or a table looks like is a UI's decision rather than a framework's.

  `link-for` turns a wikilink's target into an href, or nil. Nothing in slopp
  reads `[[…]]` — no rule, no index, no derivation — so it is prose with no
  machine reader and the meaning of a target is entirely the caller's. With no
  resolver, or none for that target, the link degrades to its own text.

  **A blank line separates blocks; a single newline does not.** A docstring
  wraps at the source margin, so joining wrapped lines is what the author meant
  and splitting them would put a break at whatever column they happened to stop
  at."
  ([text] (as-hiccup text nil))
  ([text link-for]
   (when (seq (str text))
          (->> (dedent text)
          chunks
          (mapv #(block % link-for))))))
