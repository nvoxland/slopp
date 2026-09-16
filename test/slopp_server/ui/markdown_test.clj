(ns slopp-server.ui.markdown-test
  "What [[slopp-server.ui.markdown]] promises about real docstrings.

  The cases here are drawn from prose slopp actually ships rather than invented
  to exercise a branch — their audit found seven forms carrying markdown TABLES
  in their docstrings, which is what settled that docstrings ARE markdown, and
  a table is the one construct that is unreadable if rendered as plain text.

  The bias worth naming: a renderer's bugs are mostly in what it does with
  input it was NOT designed for — an unclosed `**`, a `[[` with no `]]`, a
  fence that never ends. Those are ordinary prose, not malformed input, and the
  rule they all answer to is that an unclosed mark reads as TEXT. Nothing here
  should ever throw on a docstring."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp-server.ui.markdown :as markdown]))

(deftest a-docstring-keeps-the-structure-its-author-wrote
  ;; The instance: `/p/slopp-server.ui/endpoints/get/api/projects` rendered a
  ;; three-paragraph docstring as one wall of text. Nathan read it and asked for
  ;; markdown, and slopp declared it — so this is the renderer that makes the
  ;; declaration mean something on a page.
  ;;
  ;; Measured across this store's 189 public docstrings before writing any of
  ;; it, because the subset worth supporting is the one that is actually there:
  ;;
  ;;     158  code spans      154  paragraph breaks     92  **strong**
  ;;      91  [[wikilinks]]     8  bullets               3  fenced blocks
  ;;       2  tables            2  numbered              0  headings
  (testing "a blank line is a PARAGRAPH BREAK, which is the visible half"
    (is (= [[:p "one"] [:p "two"]]
           (markdown/as-hiccup "one\n\ntwo"))))

  (testing "and a single newline is not — a docstring wraps at the margin, so
            joining is what the author meant"
    (is (= [[:p "one two"]]
           (markdown/as-hiccup "one\ntwo"))))

  (testing "the indentation a docstring carries is the SOURCE's, not the text's"
    ;; every line after the first is indented to the opening quote. Left in, it
    ;; would read as a code block under every markdown rule there is.
    (is (= [[:p "one two"] [:p "three"]]
           (markdown/as-hiccup "one\n  two\n\n  three"))))

  (testing "the three inline marks this store actually uses"
    (is (= [[:p "a " [:strong "b"] " c"]]     (markdown/as-hiccup "a **b** c")))
    (is (= [[:p "a " [:code "b"] " c"]]       (markdown/as-hiccup "a `b` c")))
    (is (= [[:p "see " [:code "views/chrome"]]]
           (markdown/as-hiccup "see [[views/chrome]]"))
        "with no resolver a wikilink degrades to its text, which is the
         property that made Nathan keep the syntax"))

  (testing "a wikilink becomes a LINK when the caller can address it"
    (is (= [[:p "see " [:a {:href "/store/source/views/chrome"} "views/chrome"]]]
           (markdown/as-hiccup "see [[views/chrome]]"
                         (fn [t] (str "/store/source/" t))))))

  (testing "an unclosed mark is TEXT, not a swallowed rest-of-document"
    ;; the failure mode that matters: a stray ` in prose must not eat the page.
    (is (= [[:p "a ` b"]]   (markdown/as-hiccup "a ` b")))
    (is (= [[:p "a ** b"]]  (markdown/as-hiccup "a ** b")))
    (is (= [[:p "a [[ b"]]  (markdown/as-hiccup "a [[ b")))))

(deftest the-block-kinds-these-docstrings-actually-contain
  ;; Counted rather than assumed, over this store's 189 public docstrings:
  ;; 8 bullet lists, 3 fenced blocks, 2 tables, 2 numbered lists, 0 headings,
  ;; 0 blockquotes. Supporting what is absent would be guessing at a format
  ;; nobody writes; leaving out what is present renders it as prose.
  (testing "a bullet list"
    (is (= [[:ul [:li "one"] [:li "two"]]]
           (markdown/as-hiccup "- one\n- two"))))

  (testing "a numbered list"
    (is (= [[:ol [:li "one"] [:li "two"]]]
           (markdown/as-hiccup "1. one\n2. two"))))

  (testing "a fenced block is VERBATIM — the marks inside it are not marks"
    (is (= [[:pre [:code "(defn f [x]\n  **not strong**"]]]
           (markdown/as-hiccup "```\n(defn f [x]\n  **not strong**\n```"))))

  (testing "and a fence survives a BLANK LINE, which is the reason fences are
            found before blocks are split rather than after"
    ;; splitting on blank lines first would cut this fence in half and render
    ;; the two pieces as paragraphs — with the code marked up as prose.
    (is (= [[:pre [:code "one\n\ntwo"]]]
           (markdown/as-hiccup "```\none\n\ntwo\n```"))))

  (testing "a table, header row and all"
    (is (= [[:table
             [:thead [:tr [:th "a"] [:th "b"]]]
             [:tbody [:tr [:td "1"] [:td "2"]]]]]
           (markdown/as-hiccup "| a | b |\n|---|---|\n| 1 | 2 |"))))

  (testing "and a table cell is INLINE, so a code span inside one renders"
    (is (= [[:table
             [:thead [:tr [:th "a"]]]
             [:tbody [:tr [:td [:code "x"]]]]]]
           (markdown/as-hiccup "| a |\n|---|\n| `x` |"))))

  (testing "blocks compose — a paragraph, a list and a paragraph"
    (is (= [[:p "before"] [:ul [:li "one"]] [:p "after"]]
           (markdown/as-hiccup "before\n\n- one\n\nafter")))))
