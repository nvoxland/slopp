(ns slopp.ui.styles
  "The application's appearance, as garden data.

  Its own namespace because it is a hundred and fifty lines of styling and
  nothing else — `slopp.ui.hub` owns the registry, the routes and the wire,
  and a stylesheet buried in there would be the largest thing in the file
  while being the least related to any of it.

  Served from the HUB rather than from a project. That is new with the split
  and it is the right way round: one stylesheet for every project the hub
  fronts, addressed absolutely at `/css/style.css`, where before each project
  served its own copy and the hub proxied it under the project's mount point.

  CSS as data, tracked like every other form — so a selector is a value a
  test can assert on, which is how the child/descendant/group confusion below
  stopped being a thing anyone could ship twice."
  (:require [slopp.http.css :as css]
            [garden.stylesheet :as gs]))

(def module-graph-styles
  "Appearance of the Code screen's module map.

  Deliberately muted. An architecture diagram earns its legibility from
  spacing, hierarchy and the edges it does NOT draw — colour spent on
  decoration makes a graph harder to read, not easier. So exactly two things
  are tinted: the foundation band, which should read as ground rather than
  as more boxes, and whatever the reader has hovered.

  Its own var because the main stylesheet is a hundred lines and adding a
  block should not mean retyping it."
  [[:.module-graph {:width "100%" :height "auto" :max-width "52rem"
                    :display "block" :margin "1.5rem auto"
                    :font-family "ui-monospace, monospace"}]
   [:.module-node
    [:rect {:fill "#fff" :stroke "#bbb" :stroke-width "1.5"}]
    [:text {:fill "#333" :font-size "15px"}]]
   ;; the band reads as ground: filled, borderless, quieter type
   [:.module-node.foundation
    [:rect {:fill "#eef1f4" :stroke "none"}]
    [:text {:fill "#667" :font-size "13px"}]]
   [:.module-edge {:fill "none" :stroke "#c4c4c4" :stroke-width "1.5"}]
   ;; the shared root, once, in the corner — a plate, not a title
   [:.module-graph [:.graph-root {:fill "#bbb" :font-size "11px"
                                  :letter-spacing "0.08em"}]]
   ;; sketch outlines are PATHS, not rects — without their own fill rule they
   ;; render as filled black blobs, the same failure as an unstyled export
   [:.module-node [:path.sketch {:fill "none" :stroke "#999" :stroke-width "1.4"}]]
   [:.module-node.foundation [:path.sketch {:stroke "#aab"}]]
   [:.module-node:hover [:path.sketch {:stroke "#2a6" :stroke-width "2"}]]
   [:.module-graph [:marker [:path {:fill "#c4c4c4"}]]]
   ;; A box that highlights on hover and does nothing when clicked is worse
   ;; than one that never reacted — it reads as broken rather than as static.
   ;; The boxes are anchors now, so the pointer says so; a module with no
   ;; namespace to open is not wrapped and keeps the default cursor, which is
   ;; the honest difference between the two.
   [:a.module-link {:cursor "pointer" :color "inherit" :text-decoration "none"}]
   [:.module-node:hover
    [:rect {:stroke "#2a6" :stroke-width "2.5"}]]
   ;; the shared root, printed once above the list instead of on every row
   [:.ns-root {:margin "0.5rem 0 0.25rem" :font-size "0.7rem" :color "#999"
               :letter-spacing "0.08em" :text-transform "uppercase"}]
   [:.module-row {:list-style "none" :margin "0.15rem 0"}]
   [:.module-head {:display "flex" :gap "0.5rem" :align-items "baseline"
                   :cursor "pointer" :padding "0.15rem 0"}]
   [:.module-name {:font-weight "600"}]
   [:.module-meta {:color "#888" :font-size "0.8rem"}]
   [:.module-tag {:color "#667" :font-size "0.7rem" :border "1px solid #ccd"
                  :border-radius "3px" :padding "0 0.3rem"}]
   [:.ns-row {:padding-left "0.9rem" :font-size "0.9rem"}]
   [:.finding {:border-left "3px solid #c93" :padding "0.4rem 0.9rem"
               :background "#fdf6ec" :margin "1rem 0"}]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.module-node
                 [:rect {:fill "#1c1c1c" :stroke "#444"}]
                 [:text {:fill "#ccc"}]]
                [:.module-node.foundation
                 [:rect {:fill "#232733" :stroke "none"}]
                 [:text {:fill "#889"}]]
                [:.module-edge {:stroke "#555"}]
                [:.module-graph [:.graph-root {:fill "#555"}]]
                [:.module-node [:path.sketch {:stroke "#777"}]]
                [:.module-node.foundation [:path.sketch {:stroke "#667"}]]
                [:.module-node:hover [:path.sketch {:stroke "#5c9"}]]
                [:.module-graph [:marker [:path {:fill "#555"}]]]
                [:.module-node:hover [:rect {:stroke "#5c9"}]]
                [:.module-meta {:color "#888"}]
                [:.ns-root {:color "#777"}]
                [:.module-tag {:color "#99a" :border-color "#445"}]
                [:.finding {:background "#241f14" :border-left-color "#a83"}])])

(def ns-source-styles
  "Appearance of the namespace pane, which is laid out like SOURCE.

  The genre is the whole design. A docstring in this store is hard-wrapped
  prose meant to be read in a file; the previous pane flowed it into an `<li>`
  and it became an essay with bullets. So: one typeface, monospace, for
  everything including the heading — a heading in the body font over a
  monospace listing says the two are different kinds of thing, and here they
  are the same thing.

  `white-space: pre-wrap` rather than `pre` on the doc blocks. `pre` alone
  keeps the author's line breaks and refuses to wrap, so a pane narrower than
  78 characters gets a horizontal scrollbar per docstring — which is a worse
  failure than the one being fixed. `pre-wrap` keeps the breaks AND wraps what
  overflows.

  The comment colour is the one the source view already uses for comments, so
  a docstring reads the same on both screens. Its italic is dropped: a slant
  over three sentences is decoration, over a `;; note` it is emphasis."
  [;; the pane carries the gutter's width as padding; `.src-lead` hangs back
   ;; into it. Both numbers are the same 3.4em and have to stay that way —
   ;; the gutter is the reason for the padding.
   [:.ns-source {:font-family "ui-monospace, SFMono-Regular, Menlo, monospace"
                 :font-size "0.82rem" :line-height "1.55"
                 :padding-left "3.4em"}]
   [:.ns-source [:h1 {:font-size "0.95rem" :font-weight "700"
                      :margin "0 0 1.25rem" :letter-spacing "0.01em"}]]
   ;; the doc block: a comment, with the author's own wrapping intact
   [:.src-doc {:white-space "pre-wrap" :margin "0 0 0.35rem"
               :padding "0 0 0 0.75rem" :background "none"
               :border-left "2px solid #e3e3e0"
               :color "#6f7b64" :font-style "normal"
               :font-size "0.8rem" :line-height "1.5"}]
   [:.src-ns-doc {:margin-bottom "2rem"}]
   ;; space, not bullets, is what separates one definition from the next
   [:.src-def {:margin "0 0 2rem"}]
   ;; the signature shares the comment's LEFT GEOMETRY exactly — same border
   ;; width, same padding, the border merely invisible — so one straight edge
   ;; runs down the whole listing and a definition sits flush under its own
   ;; comment. Matching the padding alone leaves it 2px adrift, which is
   ;; nothing on one entry and a visible waver over fourteen. It matters more
   ;; the longer the signature gets, which is the direction this is going.
   [:.src-sig {:padding-left "0.75rem"
               :border-left "2px solid transparent"}]
   [:.src-name {:font-weight "700" :text-decoration "none"}]
   [:.src-name:hover {:text-decoration "underline"}]
   ;; the kind is a COLOUR now, not a word — see views/kind-classes. A name
   ;; with no kind class keeps the ordinary link colour, which is the honest
   ;; answer for a kind no rule here has an opinion about.
   [:.src-fn {:color "#1f8a52"}]
   [:.src-val {:color "#2f6fb5"}]
   ;; HUE says category, LIGHTNESS says importance within the namespace.
   ;; Four steps, because past four or five the differences stop being
   ;; distinguishable down a vertical list — and the lightest step still has to
   ;; pass contrast on white, so the usable band is narrow at that end and the
   ;; ramp is spent where it can be seen. The bare rules above stand for a kind
   ;; that has a hue but no step.
   [:.src-fn.src-w0 {:color "#7fb99b"}]
   [:.src-fn.src-w1 {:color "#4f9d74"}]
   [:.src-fn.src-w2 {:color "#28864f"}]
   [:.src-fn.src-w3 {:color "#19764a"}]
   [:.src-fn.src-w4 {:color "#0d6b3a"}]
   [:.src-val.src-w0 {:color "#93b3d6"}]
   [:.src-val.src-w1 {:color "#6194c4"}]
   [:.src-val.src-w2 {:color "#3574b0"}]
   [:.src-val.src-w3 {:color "#26639e"}]
   [:.src-val.src-w4 {:color "#17538c"}]
   ;; a badge is a MARK, so it sits quietly at the end of the line and never
   ;; competes with the name. Fixed width, because emoji are not monospace and
   ;; a ragged right edge in a source-shaped listing reads as a mistake.
   [:.src-badge {:display "inline-block" :width "1.3em" :text-align "center"
                 :margin-left "0.4rem" :font-size "0.75rem"
                 :opacity "0.75" :cursor "help"}]
   ;; The leading gutter HANGS: fixed width, pulled left by its own width, so
   ;; the marks sit outside the text column entirely and the name still starts
   ;; exactly where the comment above it does. Reserving the space inline
   ;; instead would push every name right of the comment bar and undo the one
   ;; alignment this listing is built on.
   ;;
   ;; Two slots is the real maximum: a form is `def` OR `defonce`, never both,
   ;; so the most a row can carry is one category mark plus 🔒.
   [:.src-lead {:display "inline-block" :width "3.4em" :margin-left "-3.4em"
                :text-align "right"}]
   [:.src-lead [:.src-badge {:margin-left 0 :margin-right "0.4rem"}]]
   ;; ORANGE, not a paler green. A lighter shade of the public colour reads as
   ;; "a lesser function", which is the wrong claim — private is a different
   ;; KIND of thing, not a weaker one, and lightness is about to mean
   ;; something else entirely (importance).
   ;; (.src-fn-private lived here. Privacy is a BADGE now: hue carries
   ;; category, and lightness is reserved for importance. A paler green for
   ;; private read as "a lesser function" — the wrong claim, and the exact
   ;; collision importance would have caused.)
   
   ;; (.src-state lived here; mutability is a badge, and `def` and `defonce`
   ;; share .src-val — a rare thing should read as a mark, not a hue)
   
   [:.src-args {:color "#888" :margin-left "0.5rem"}]
   ;; several arities stack under the name rather than running together; the
   ;; indent is the name's own width, so the brackets line up with each other
   [:.src-arity {:display "block" :margin-left "0.5rem"}]
   ;; THE ELIDED BODY — one line per step of importance. Blurred on purpose:
   ;; the claim is "there is this much code here", not "here is the code", and
   ;; a crisp skeleton invites someone to lean in and try to read it. The blur
   ;; is what makes it legible as an ELISION rather than as a loading state.
   ;; the skeleton is a LINK to the same place the name goes, so it wants a
   ;; pointer and a hint on hover — but no underline and no colour, because
   ;; it is not text and must not start reading as any
   [:.src-elide-link {:display "block" :text-decoration "none"
                      :cursor "pointer"}]
   [:.src-elide-link:hover [:.src-elide-line {:opacity "1"}]]
   [:.src-elide {:padding-left "0.75rem" :border-left "2px solid transparent"
                 :margin "0.3rem 0 0" :max-width "26rem"}]
   [:.src-elide-line {:display "block" :height "0.42em" :margin "0.22em 0"
                      :border-radius "2px" :background "#d8ddd4"
                      :filter "blur(1.1px)" :opacity "0.75"}]
   [:.src-elide-i0 {:margin-left "0"}]
   [:.src-elide-i1 {:margin-left "1.2em"}]
   [:.src-elide-i2 {:margin-left "2.4em"}]
   [:.src-schema {:color "#8a8ab0" :padding-left "0.75rem"
                  :border-left "2px solid transparent"
                  :margin-top "0.15rem"}]
   ;; (.src-tested-by lived here; coverage moved to the rail, where the
   ;; things you consult beside the code live)
   
   ;; the rail's toggles: a row each, the label clickable with the box
   [:.rail-toggle {:display "flex" :align-items "baseline" :gap "0.4rem"
                   :padding "0.2rem 0" :cursor "pointer"}]
   [:.rail-toggle [:input {:cursor "pointer" :margin 0}]]
   [:.rail-buttons {:display "flex" :gap "0.4rem" :margin "0.25rem 0"}]
   [:.doc-toggle {:font-family "inherit" :font-size "0.75rem"
                  :padding "0.2rem 0.5rem" :cursor "pointer"
                  :border "1px solid #ccc" :border-radius "3px"
                  :background "transparent" :color "inherit"}]
   ;; the one already in effect is disabled rather than hidden: a pair that
   ;; loses a member stops reading as one two-position control
   [:.doc-toggle:disabled {:opacity "0.45" :cursor "default"}]
   ;; a collapsed comment is marked by a trailing ellipsis rather than by a
   ;; colour, so it survives greyscale and does not spend the one channel the
   ;; listing already uses for importance
   [:.src-doc-short:after {:content "\" …\"" :opacity "0.6"}]
   ;; a comment with something hidden is CLICKABLE, both ways — the ellipsis
   ;; says there is more, the pointer says you can get it, and the same click
   ;; closes it again. The bar it already has is what lights up, so the
   ;; affordance costs no new element.
   ;; `(none)` is a FINDING, not prose — italic and faded so it reads as the
   ;; listing speaking rather than as something an author wrote, and so a
   ;; column of them is visibly a gap in the documentation rather than a
   ;; paragraph nobody bothered with
   [:.src-doc-none {:font-style "italic" :opacity "0.5"}]
   [:.src-doc-more {:cursor "pointer"}]
   [:.src-doc-more:hover {:border-left-color "#9aae86"}]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.src-doc {:color "#7d8a70" :border-left-color "#2e2e2e"}]
                [:.src-args {:color "#777"}]
                [:.src-fn {:color "#5cbb85"}]
                [:.src-val {:color "#79b0e8"}]
                ;; dark mode runs the ramp the other way: on #111 the IMPORTANT
                ;; end is the bright one, so w3 is lightest here and darkest on
                ;; white. "Lightness carries importance" means contrast against
                ;; the page, not a fixed direction.
                [:.src-fn.src-w0 {:color "#3d6b52"}]
                [:.src-fn.src-w1 {:color "#4f8f6b"}]
                [:.src-fn.src-w2 {:color "#5cb383"}]
                [:.src-fn.src-w3 {:color "#6ecb96"}]
                [:.src-fn.src-w4 {:color "#7fdca6"}]
                [:.src-val.src-w0 {:color "#3f5d7d"}]
                [:.src-val.src-w1 {:color "#5480a8"}]
                [:.src-val.src-w2 {:color "#6ea3d1"}]
                [:.src-val.src-w3 {:color "#83bbe8"}]
                [:.src-val.src-w4 {:color "#93c9f5"}]
                ;; the elided body on a dark page: darker than the text, so it
                ;; still reads as absence rather than as content
                [:.src-elide-line {:background "#2f342c" :opacity "0.9"}]
                [:.src-badge {:opacity "0.85"}]
                [:.doc-toggle {:border-color "#444"}]
                [:.src-doc-more:hover {:border-left-color "#4a5a3e"}]
                ;; (.src-state's dark counterpart retired with the hue itself)
                
                [:.src-schema {:color "#8888aa"}])])

(def spine-styles
  "Appearance of the path through a form, and of the sentences that name a gap.

  **Confidence marks the EXCEPTION, not the rule.** A `static` edge is what a
  reader already expects, so it is nearly silent; `observed` is loud. Marking
  every edge equally would make the page busy and leave the one edge worth
  doubting no easier to find — the same trade `tier-badges` makes by leaving
  `:pure` unmarked on purpose.

  Deliberately NOT a fourth colour channel over the listing's three. Hue there
  means category and lightness means importance; a rung is neither, and reusing
  either would have `observed` read as \"a value\" or as \"unimportant\". The
  spine's marks are weight and opacity on a label that says the word.

  A gap sentence is muted rather than red. It reports that the store holds
  nothing here, which is a fact about the record and not an error — and a page
  that shouts at its reader about missing docstrings gets its gap sentences
  turned off, which is the one outcome that makes them useless."
  [[:.spine {:margin "1.75rem 0"}]
   [:.spine-rungs {:list-style "none" :padding-left "0" :margin "0.5rem 0"
                   :border-left "2px solid #ddd"}]
   [:.spine-rung {:padding "0.3rem 0 0.3rem 0.9rem" :position "relative"
                  :font-size "0.95rem"}]
   [:.spine-here {:border-left "2px solid #2a6" :margin-left "-2px"
                  :background "#f4faf7"}]
   [:.spine-via {:font-size "0.75rem" :margin-left "0.5rem"
                 :letter-spacing "0.03em" :text-transform "uppercase"}]
   ;; the ramp: what you expect is quiet, what you should doubt is not
   [:.via-static {:color "#bbb"}]
   [:.via-carrier {:color "#a80" :font-weight "600"}]
   [:.via-declared {:color "#a80" :font-weight "600"}]
   [:.via-observed {:color "#c62" :font-weight "700"}]
   [:.spine-choice {:margin-left "0.5rem" :font-size "0.85rem"}]
   [:.spine-alt {:margin-left "0.4rem" :font-size "0.85rem" :opacity "0.75"}]
   [:.spine-weakest {:display "block" :margin-bottom "0.4rem"}]
   [:.spine-cycle {:display "block" :color "#a80"}]
   ;; a gap is a fact about the RECORD, so it reads as a note and not as an
   ;; error — the honest failure mode is a thin page, not a shouting one
   [:.src-gap {:color "#999" :font-style "italic" :margin "0.4rem 0"}]
   [:.src-why {:border-left "3px solid #cde" :padding-left "0.9rem" :color "#456"}]
   [:.src-escape {:margin-top "1rem"}]
   [:.src-escape-link {:margin-top "1.75rem" :font-size "0.9rem"}]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.spine-rungs {:border-left-color "#333"}]
                [:.spine-here {:border-left-color "#5c9" :background "#16211c"}]
                [:.via-static {:color "#555"}]
                [:.via-carrier {:color "#db4"}]
                [:.via-declared {:color "#db4"}]
                [:.via-observed {:color "#e85"}]
                [:.spine-cycle {:color "#db4"}]
                [:.src-gap {:color "#777"}]
                [:.src-why {:border-left-color "#345" :color "#9ab"}])])

(def gap-styles
  "The gap overlay's ramp — how thin the record is, light to dark.

  **A fourth channel, chosen not to collide with the other three.** The
  listing already spends hue on CATEGORY, lightness on IMPORTANCE and badges on
  MODIFIERS. This is a warm wash on the box FILL, which none of those use, so a
  darkly-tinted box says one thing and cannot be misread as an important one.

  `gap-w0` is deliberately transparent rather than a pale colour. Nothing
  missing is not a faint amount of missing, and a store with no gaps should
  look under this lens exactly as it looks without it — otherwise the overlay
  invents a finding out of a fully documented codebase."
  [[:.gap-w0 {}]
   ;; step 1 was #fdf6ec and invisible against white on a real store, where
   ;; almost every module lands at 0 or 1 — a ramp whose first rung cannot be
   ;; seen has four levels, not five, and the one it loses is the common case
   [:.module-node.gap-w1 [:rect {:fill "#faecd6"}] [:path.sketch {:fill "#faecd6"}]]
   [:.module-node.gap-w2 [:rect {:fill "#fbe9cf"}] [:path.sketch {:fill "#fbe9cf"}]]
   [:.module-node.gap-w3 [:rect {:fill "#f6d5a8"}] [:path.sketch {:fill "#f6d5a8"}]]
   [:.module-node.gap-w4 [:rect {:fill "#eeba78"}] [:path.sketch {:fill "#eeba78"}]]
   [:.gap-summary {:margin-top "2rem"}]
   [:.gap-rows {:list-style "none" :padding-left "0"}]
   [:.gap-rows [:li {:padding "0.25rem 0.5rem" :border-radius "3px"
                     :margin-bottom "0.15rem"}]]
   [:li.gap-w1 {:background "#fdf6ec"}]
   [:li.gap-w2 {:background "#fbe9cf"}]
   [:li.gap-w3 {:background "#f6d5a8"}]
   [:li.gap-w4 {:background "#eeba78"}]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.module-node.gap-w1 [:rect {:fill "#241d14"}] [:path.sketch {:fill "#241d14"}]]
                [:.module-node.gap-w2 [:rect {:fill "#33271a"}] [:path.sketch {:fill "#33271a"}]]
                [:.module-node.gap-w3 [:rect {:fill "#45321f"}] [:path.sketch {:fill "#45321f"}]]
                [:.module-node.gap-w4 [:rect {:fill "#5c4226"}] [:path.sketch {:fill "#5c4226"}]]
                [:li.gap-w1 {:background "#241d14"}]
                [:li.gap-w2 {:background "#33271a"}]
                [:li.gap-w3 {:background "#45321f"}]
                [:li.gap-w4 {:background "#5c4226"}])])

(def table-styles
  "Appearance of this app's data tables — the store's module table and the API
  index, which have the same job and now the same class.

  `.data-table` rather than `.module-table`, which is what it was called when
  there was one. A second table under a second name would have been the same
  rules twice, and the pair would drift the first time either was touched.

  Quiet on purpose. Horizontal rules only: a vertical line between two columns
  says they are separate things, and every row here is one subject.

  **Numbers right-aligned, and that is not typography.** Comparing a count
  down its length is why the module table exists at all — the diagram carries
  shape and cannot carry size — and a ragged left-aligned column of counts is
  a list of numbers rather than a measurement. `tabular-nums` for the same
  reason: proportional digits make 11 narrower than 88 and the comparison is
  by width.

  The foundation row is marked by WEIGHT, the same channel the lens bar spends
  on \"where you are\". It survives both colour schemes and does not spend a hue
  nobody chose — and the row says the word `foundation` in any case, because a
  reader without this stylesheet is the one this project actually has."
  [[:.data-table {:border-collapse "collapse" :width "100%"
                  :font-size "0.9rem" :margin-top "0.5rem"}]
   [:.data-table [:th {:text-align "left" :font-weight "600" :color "#777"
                       :padding "0.3rem 0.7rem 0.3rem 0"
                       :border-bottom "1px solid #ddd"
                       :white-space "nowrap"}]]
   [:.data-table [:td {:padding "0.3rem 0.7rem 0.3rem 0"
                       :border-bottom "1px solid #f0f0f0"
                       :vertical-align "top"}]]
   ;; wins over the :th rule above on specificity (0,2,0 against 0,1,1), which
   ;; is why the header and the cell can share one class instead of the column
   ;; being two rules that happen to agree
   [:.data-table [:.num {:text-align "right"
                         :font-variant-numeric "tabular-nums"}]]
   [:tr.foundation [:td {:font-weight "600"}]]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.data-table [:th {:border-bottom-color "#333" :color "#999"}]]
                [:.data-table [:td {:border-bottom-color "#222"}]])])

(def endpoint-styles
  "Appearance of the API surface section.

  **The indentation is NOT here**, and that is the point worth recording. A
  field's depth is a nested `<ul>` emitted by `slopp.ui.views/schema-fields`,
  so the browser indents it because it is really nested — and a reader without
  this stylesheet sees the same structure. Doing it with `padding-left` on a
  flat list would look identical here and read as one undifferentiated column
  everywhere else, which is this project's oldest rule and the source of three
  of its bugs.

  So all this does is stop the list looking like prose: kill the bullets, keep
  the type quiet, and rule off one endpoint from the next."
  [[:.endpoint {:border-left "none" :padding-left "0"
                :margin-top "1.5rem"}]
   [:.endpoint [:h2 {:font-family "ui-monospace, monospace" :font-size "1rem"
                     :margin-top "0"}]]
   [:.endpoint [:h3 {:font-size "0.85rem" :color "#777" :font-weight "600"
                     :text-transform "lowercase" :margin-bottom "0.15rem"}]]
   [:.schema-fields {:list-style "none" :padding-left "0" :margin "0.15rem 0"
                     :font-size "0.9rem"}]
   ;; the ONE indent rule, and it applies to a list inside a list — real
   ;; nesting, not a depth class counted out by the view
   [:.schema-fields [:.schema-fields {:padding-left "1.25rem"
                                      :border-left "1px solid #eee"}]]
   [:.field {:font-family "ui-monospace, monospace"}]
   ;; THE RAIL. Every endpoint, on both screens of the section — one row per
   ;; endpoint and nothing else, because the pane's job is crossing a large
   ;; surface rather than describing it.
   ;; both section rails, one rule. `#page-list` is a separate id rather than
   ;; the Pages rail borrowing `#endpoint-list`: only one rail renders at a
   ;; time so sharing would not collide, but an id is a NAME, and a list of
   ;; pages called `endpoint-list` misleads anyone reading the document.
   [:#endpoint-list :#page-list :#webapp-page-list
    {:list-style "none" :margin 0 :padding 0}]
   ;; what a webapp page FETCHES, on its own screen. A plain list of the
   ;; endpoints it calls — the index renders the same fact interposed inline,
   ;; because there it shares a table cell.
   [:.page-calls {:list-style "none" :margin "0.3rem 0 0" :padding 0}]
   [:.page-calls [:li {:padding "0.1rem 0"}]]
   [:.endpoint-row {:padding "0.1rem 0"}]
   [:.endpoint-row [:a {:text-decoration "none" :display "block"
                        :padding "0.15rem 0.25rem" :border-radius "3px"}]]
   [:.endpoint-row [:a.active {:background "#eef7f2"}]]
   [:.endpoint-path {:font-family "ui-monospace, monospace" :font-size "0.85rem"}]
   ;; the method CHIP. Colour is the second channel and never the only one:
   ;; the word GET is in the markup, so a reader without this stylesheet loses
   ;; the scanning aid and no information. A chip that said nothing without
   ;; CSS is the defect this file keeps arguing about.
   [:.method {:font-family "ui-monospace, monospace" :font-size "0.7rem"
              :font-weight "700" :letter-spacing "0.03em"
              :padding "0.05rem 0.3rem" :border-radius "3px"
              :border "1px solid transparent"}]
   [:.method-get  {:color "#276" :border-color "#bd9"}]
   [:.method-post {:color "#a63" :border-color "#e9b"}]
   ;; THE CALL FORM. Set off from the schemas above it, because those describe
   ;; the endpoint and this one acts on it — the same distance the gap summary
   ;; keeps from the diagram.
   [:.try-panel {:margin-top "1.5rem" :padding-top "0.75rem"
                 :border-top "1px dashed #ddd"}]
   [:.try-fields {:display "flex" :flex-direction "column" :gap "0.4rem"
                  :margin-bottom "0.6rem"}]
   ;; label ABOVE its box rather than beside it: at this pane's width a
   ;; side-by-side pair wraps, and a label that wraps away from its input is a
   ;; form you have to guess at
   [:.try-field {:display "flex" :flex-direction "column" :gap "0.15rem"}]
   [:.try-fields [:label {:font-size "0.85rem"}]]
   ;; the field's prose inside the form has no left indent — there is no row
   ;; above it to hang from here, unlike the schema lists where it sits under
   ;; one. Same class, one rule that does not apply.
   [:.try-field [:.fdoc {:margin-left "0"}]]
   [:.try-fields [:input {:font "inherit" :color "inherit"
                          :background "transparent"
                          :border "1px solid #ccc" :border-radius "4px"
                          :padding "0.25rem 0.4rem" :max-width "24rem"}]]
   [:.try-panel [:button {:font "inherit" :color "inherit" :cursor "pointer"
                          :background "transparent"
                          :border "1px solid #ccc" :border-radius "4px"
                          :padding "0.2rem 0.7rem"}]]
   [:.try-panel [:button:hover {:border-color "#2a6"}]]
   [:.try-result {:margin-top "0.75rem"}]
   [:.try-error {:color "#a33"}]
   ;; a FIELD's own prose. Quiet and indented, so it reads as belonging to the
   ;; row above rather than as a sibling of the row below — the one thing this
   ;; rule has to get right, since the fields are a list and a paragraph
   ;; between two of them is ambiguous by default.
   [:.fdoc {:margin "0.1rem 0 0.3rem 0.9rem" :font-size "0.85rem"
            :color "#777" :max-width "38rem"}]
   ;; THE REQUEST/RESPONSE TABS. They were `lens-bar`'s classes for one commit
   ;; and read as two words, one of them green — semantically the same control
   ;; and visually nothing at all. `lens-bar` gets away with plain text because
   ;; it sits alone in its own region at the top of a page, where POSITION says
   ;; what it is; inline in the middle of prose there is no position to read.
   ;;
   ;; UNDERLINE tabs, not boxed ones. A boxed tab joins its panel by painting
   ;; its bottom border in the PAGE's background colour, which means knowing
   ;; that colour in both schemes and keeping three rules in step when either
   ;; changes. An underline needs no background match at all.
   [:.endpoint-tabs {:display "flex" :gap "0.25rem"
                     :margin "1.25rem 0 0" :padding "0"
                     :border-bottom "1px solid #ddd"}]
   ;; a GROUP — the link and the current one are the same shape, and only the
   ;; marking differs. Written as one rule so they cannot drift apart in size.
   [:.endpoint-tabs [:a :strong {:padding "0.3rem 0.8rem"
                                 :font-size "0.9rem" :font-weight "400"
                                 :text-decoration "none"
                                 :border-bottom "2px solid transparent"
                                 ;; over the container's own 1px line, so the
                                 ;; current tab's mark replaces it rather than
                                 ;; sitting above it
                                 :margin-bottom "-1px"}]]
   [:.endpoint-tabs [:a {:color "#276"}]]
   [:.endpoint-tabs [:a:hover {:color "inherit" :border-bottom-color "#bd9"}]]
   ;; the CURRENT one carries weight AND a rule under it — two channels, so it
   ;; survives a reader who cannot separate the colours
   [:.endpoint-tabs [:.tab-current {:color "inherit" :font-weight "600"
                                    :border-bottom-color "#2a6"}]]
   ;; the panel under the tabs. Only spacing: what is IN it already has rules,
   ;; and the tabs above supply the separation the old `<h3>` used to.
   [:.schema-part {:margin-top "0.75rem"}]
   ;; the ENDPOINT's prose, not a field's: full size and no indent, because it
   ;; is the first thing a reader wants on the page rather than a footnote
   ;; under a row. Same content type, opposite typographic job.
   [:.endpoint-doc {:max-width "38rem" :margin "0.5rem 0 1rem"}]
   ;; the EFFECT mark. Colour is the second channel, never the only one: the
   ;; words `changes something` are in the markup, so a reader without this
   ;; stylesheet loses the scanning aid and none of the information. That
   ;; matters more here than anywhere else on the page — it is the one mark
   ;; whose absence could cost somebody a write they did not mean to make.
   [:.effectful {:color "#a63" :font-size "0.85rem"}]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.effectful {:color "#d94"}])
   (gs/at-media {:prefers-color-scheme :dark}
                [:.fdoc {:color "#999"}])
   [:.ftype {:color "#777"}]
   (gs/at-media {:prefers-color-scheme :dark}
                
                [:.endpoint [:h3 {:color "#999"}]]
                [:.schema-fields [:.schema-fields {:border-left-color "#2a2a2a"}]]
                [:.ftype {:color "#999"}]
                [:.endpoint-row [:a.active {:background "#1b2b23"}]]
                [:.method-get  {:color "#5c9" :border-color "#375"}]
                [:.method-post {:color "#d94" :border-color "#853"}]
                ;; the tabs: the divider and the link, both of which are set
                ;; against a light page above. Written here rather than left for
                ;; later, because a light-only rule leaves the other scheme
                ;; wrong and nothing on this screen would say so.
                [:.endpoint-tabs {:border-bottom-color "#2a2a2a"}]
                [:.endpoint-tabs [:a {:color "#5c9"}]]
                [:.endpoint-tabs [:a:hover {:border-bottom-color "#375"}]]
                [:.endpoint-tabs [:.tab-current {:border-bottom-color "#5c9"}]])])

(def search-styles
  "Appearance of the search results — the rows, and the one channel that marks
  a hit as a finding.

  Light and dark in the SAME form deliberately. A counterpart declared in the
  sheet's main dark block outlives the class it styles without anything
  noticing, which is the failure [[spine-styles]] already records: a
  `.via-observed` rule survived the class's removal because the two halves
  were written a hundred lines apart.

  A row's separator is a top border rather than a bottom one, so the list
  ends where its last row does instead of trailing a rule under nothing.

  `.matched-source` gets the WARNING channel rather than the muted one every
  other secondary line uses, because it is not secondary: it says the words
  you searched for appear only in the code, which on this UI's thesis is a
  finding about the store."
  [[:ul.hits {:list-style "none" :margin "0" :padding "0"}]
   [:.hit {:padding "0.6rem 0" :border-top "1px solid #eee"}]
   [:.hit-kind {:font-size "0.85rem" :color "#777"}]
   [:.hit-where {:font-size "0.85rem" :color "#777"}]
   [:.hit-sig {:color "#777"}]
   [:.hit-doc {:margin "0.25rem 0 0"}]
   [:.hit-matched {:margin "0.15rem 0 0"}]
   [:.matched-source [:small {:color "#a70" :font-style "italic"}]]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.hit {:border-top-color "#333"}]
                [:.hit-kind {:color "#999"}]
                [:.hit-where {:color "#999"}]
                [:.hit-sig {:color "#999"}]
                [:.matched-source [:small {:color "#d94"}]])])

(def config-styles
  "Appearance of the Config page — the one screen that shows every capability
  at once.

  Three things to separate, and the whole job is separating them, because the
  page is one long column of tables that all look alike:

  - **an OWNER group**, which is a heading, a sentence of vocabulary and a
    table. The heading is monospace because an owner label is a key SEGMENT —
    `http` is the first part of `http.port` — and setting it in prose type
    makes it read as a word rather than as the thing you would type.
  - **UNRECOGNISED keys**, the only rows on the page that ask the reader to do
    something. A left rule and a warm border, on the same shape `article`
    already uses, so it reads as set apart rather than as an alarm. **Colour is
    the second channel and never the only one** — the heading says
    `Unrecognised keys` and the prose says what to do about it, so a reader
    who cannot separate the border colour loses a scanning aid and nothing
    else. That rule is this file's oldest and the source of three of its bugs.
  - **the FAMILIES**, last, behind a rule. They are settable spaces rather
    than settings, so the divider is doing the work the sentence also does.

  No rule for `.data-table` here: the tables are the same tables the module
  index and the three path sections use, and `table-styles` already generalised
  to `.data-table` for exactly that reason.

  The dark counterpart is in this form rather than in the sheet's own
  `at-media` block, which is this file's rule: a rule and the colour it needs
  after dark are one edit, or the second one is written when someone notices."
  (list
   [:.config-owner {:margin-top "2rem"}]
   [:.config-owner [:h2 {:font-family "ui-monospace, monospace"
                         :font-size "1rem"
                         :margin-bottom "0.15rem"}]]
   ;; the owner's MEANING, tight under its heading — it belongs to the heading
   ;; above rather than to the table below, and at default paragraph spacing
   ;; it floats between the two and reads as either.
   [:.config-owner [:p {:margin "0 0 0.6rem"}]]
   [:.config-orphaned {:border-left "3px solid #d94"
                       :padding-left "0.9rem"
                       :margin "1.25rem 0"}]
   [:.config-patterns {:margin-top "2rem"
                       :border-top "1px solid #ddd"
                       :padding-top "0.75rem"}]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.config-orphaned {:border-left-color "#c83"}]
                [:.config-patterns {:border-top-color "#333"}])))

(def dashboard-styles
  "Appearance of the Dashboard — the one screen made of charts.

  **The bar is the ornament and the label is the fact.** Each row states its
  own number as ordinary text and the geometry only repeats it, because a chart
  whose magnitudes live in its widths alone is unreadable to this project's
  actual reader. These rules make the same rows legible to a browser without
  moving the fact into the picture.

  **`.bar` is an EMPTY span, so it needs a block box and a height to exist at
  all** — a zero-content inline element with a width is nothing on screen, and
  a headless assertion cannot see the difference because the label it sits
  beside renders either way. That is exactly the class of defect AGENTS.md
  keeps a browser for, which is why the rule is written down with its reason
  rather than tuned until it looked right.

  It is a block on its own LINE rather than a grid column, and that is
  deliberate: the label and the count are separated by a real space in the
  markup so the text reader sees `demo.core 42`, and in a grid or a flex row
  that space would become an anonymous item of its own and break the tracks.
  Spacing that a reader without CSS can see is worth more than a tidier box
  model."
  (list
   [:.bars {:list-style "none" :margin "0.5rem 0" :padding 0}]
   [:.bars [:li {:padding "0.25rem 0"}]]
   [:.bar-label {:font-family "ui-monospace, monospace" :font-size "0.9rem"}]
   [:.bar-count {:font-size "0.9rem" :color "#777"}]
   [:.bar {:display "block" :height "0.55rem" :min-width "2px"
           :border-radius "2px" :background "#2a6" :margin-top "0.15rem"}]
   ;; the cadence chart is full-width and short — a sparkline's job, which is
   ;; to show shape rather than to be read off. The sentence above it carries
   ;; the counts, so nothing here is the only place a number lives.
   [:.cadence {:display "block" :width "100%" :height "3rem"
               :margin-top "0.35rem"}]
   [:.cadence-bar {:fill "#2a6"}]
   ;; the growth line shares the cadence box. A stroke rather than a fill,
   ;; because a polyline with no fill and no stroke is invisible in exactly the
   ;; way an unstyled empty span is — the same failure text output cannot show.
   [:.growth-line {:fill "none" :stroke "#2a6" :stroke-width "1.5"}]
   (gs/at-media {:prefers-color-scheme :dark}
                [:.bar {:background "#5c9"}]
                [:.bar-count {:color "#999"}]
                [:.cadence-bar {:fill "#5c9"}]
                [:.growth-line {:stroke "#5c9"}])))

(def ^{:http/method :get :http/path "/css/style.css" :http/auth :public
       :http/media-type "text/css"}
  stylesheet
  "GET /css/style.css — the browser's own styling as garden data (CSS as
  Clojure data, tracked like every other form). A safe GET; served text/css.

  **A `def` holding a STRING, and both halves matter.** `:http/path` serves a
  stored VALUE now (slopp `d35663`), so a stylesheet is content that was
  already decided rather than something computed per request — and a `defn`
  left here would not be called, it would be DEREFERENCED, serving the string
  of its own function object under a perfectly plausible `text/css`.

  **`css/render` rather than `css/css-response`, and forgetting it is the trap
  worth knowing.** Garden rules are a VECTOR, and a vector is what the
  dispatcher reads as HICCUP. Declared as a bare rule vector this endpoint
  would answer 200, `Content-Type: text/css`, body `<main margin=\"0\"></main>` —
  right status, right header, nonsense body, and nothing downstream able to
  tell it from a page. slopp landed a refusal for it (`d35672`) after this
  project asked about the migration; without that, the only symptom is a site
  with no styling and a stylesheet that looks like it loaded.

  Combinators, because two of the three look alike and getting them wrong
  shipped a broken layout: `:.app>nav` (one keyword) is CHILD, `[:.app [:nav]]`
  (nesting) is DESCENDANT, and `[:.app :nav]` (siblings) is a selector GROUP.
  A bare `>` is none of them — it reads as `clojure.core/>` and garden renders
  the function object, which `css/render` refuses."
  (css/render
   [[:body {:font-family "system-ui, sans-serif" :line-height 1.5
            :margin 0 :padding 0}]
    ;; THE THREE PANES. Auto-sized outer columns rather than fixed ones, so a
    ;; page passing no :local or :detail leaves no gap where a pane would have
    ;; been — the shell omits the element and the grid simply has less in it.
    [:.app {:display "grid" :gap "0"
            :grid-template-columns "auto minmax(0, 1fr) auto"
            ;; an explicit second row, so the bar keeps its own height and the
            ;; panes take everything under it instead of sharing one row
            :grid-template-rows "auto minmax(0, 1fr)"
            :min-height "100vh"}]
    ;; THE GLOBAL BAR — spans every column, and reads as a bar: horizontal,
    ;; one line tall, its own background. Before this it was a vertical
    ;; bulleted list sitting in the first column, which made the app's global
    ;; navigation look like part of the left pane.
    [:header {:grid-column "1 / -1"
              :display "flex" :align-items "center" :gap "1.5rem"
              :height "3rem" :padding "0 1.25rem"
              :border-bottom "1px solid #ddd" :background "#fafafa"
              :font-size "0.95rem"}]
    [:header [:ul {:list-style "none" :margin 0 :padding 0
                   :display "flex" :gap "1.5rem"}]]
    [:header [:a {:text-decoration "none"}]]
;; THE PROJECT SWITCHER. It was a bare <select> flexed in beside the section
;; links, which means the OS renders it: its own font, its own box, its own
;; background. Structurally in the bar and visually foreign to it.
;;
;; Two changes and they answer different complaints. `font`/`color`/
;; `background` make it the same TEXT as the links, which is what "with them"
;; means. `margin-left: auto` moves it to the trailing edge, because it
;; changes which APPLICATION you are in while every link beside it moves you
;; around inside one — one undifferentiated run of items reads as though the
;; switcher were a fourth section.
;;
;; A transparent border rather than none, so the hover affordance can appear
;; without shifting the bar's layout by 2px when the pointer crosses it.
[:header [:.project-switcher {:font "inherit" :color "inherit"
                              :background "transparent"
                              :margin-left "auto"
                              :border "1px solid transparent"
                              :border-radius "4px"
                              :padding "0.15rem 0.35rem"}]]
;; **The class is back on the `<select>` itself.** It sat on a wrapper for a
;; day, while the control was a select plus a `go` button — a workaround for
;; `url-for` not seeing the dispatched value. The button is gone, so the
;; wrapper is too, and the rules above land where they were written to land.
;; `cursor:pointer` belongs on it again for the same reason it was right
;; originally: the whole box IS the control.
[:header [:.project-switcher {:cursor "pointer"}]]
[:header [:.project-switcher:hover {:border-color "#ccc"}]]
;; THE DOOR. Same three channels as the switcher above and for the same
    ;; reason — a control flexed into this bar without inheriting its type is
    ;; drawn by the OS, structurally in the bar and visually foreign to it.
    ;;
    ;; Deliberately NO margin-left:auto. The switcher owns the trailing edge
    ;; because it changes which APPLICATION you are in; this box acts on the
    ;; store you are already in, so it belongs beside the section links. Two
    ;; competing auto margins would split the free space BETWEEN them, which
    ;; puts a hole in the middle of the bar rather than a control at its end.
    [:header [:.store-search {:display "flex" :align-items "center"
                              :gap "0.35rem"}]]
    [:header [:.store-search [:input {:font "inherit" :color "inherit"
                                      :background "transparent"
                                      :border "1px solid #ccc"
                                      :border-radius "4px"
                                      :padding "0.2rem 0.45rem"
                                      :width "14rem"}]]]
    [:header [:.store-search [:button {:font "inherit" :color "inherit"
                                       :background "transparent"
                                       :cursor "pointer"
                                       :border "1px solid transparent"
                                       :border-radius "4px"
                                       :padding "0.15rem 0.45rem"}]]]
    [:header [:.store-search [:button:hover {:border-color "#ccc"}]]]
    ;; CHILD, not descendant: a breadcrumb <nav> inside <main> must not pick
    ;; up left-pane styling.
    [:.app>nav {:border-right "1px solid #ddd" :padding "1rem"
                :width "16rem" :overflow-y "auto"}]
    [:.app>nav [:ul {:list-style "none" :margin 0 :padding 0}]]
    [:.app>nav [:li {:padding "0.15rem 0"}]]
    [:#ns-filter {:width "100%" :box-sizing "border-box"
                  :padding "0.35rem 0.5rem" :margin-bottom "0.75rem"
                  :border "1px solid #ccc" :border-radius "4px"
                  :font-size "0.9rem" :background "transparent"
                  :color "inherit"}]
    [:main {:padding "1.25rem" :max-width "60rem"}]
    [:aside {:border-left "1px solid #ddd" :padding "1rem" :width "20rem"
             :font-size "0.9rem" :overflow-y "auto"}]
;; the rail's rows: a metadata line under each name, tight enough that six
    ;; callers do not become a scroll. `.ego-more` keeps normal weight — it is
    ;; the sentence that stops a reader concluding there are no other callers,
    ;; so it is not decoration to be faded out.
    [:.ego-meta {:margin "0.1rem 0"}]
    [:.ego-more {:margin "0.6rem 0 0"}]
;; the lens switcher. Quiet, above the subject, and deliberately NOT
    ;; styled like the global bar — it is scoped to what main shows, and a
    ;; second bar-looking row at the top of the page would say otherwise.
    ;; The current view is marked by WEIGHT, which survives both colour
    ;; schemes and does not depend on a hue nobody chose.
    [:.lens-bar {:font-size "0.9rem" :color "#777" :margin-bottom "0.75rem"}]
    [:.lens-bar [:.lens-current {:color "inherit" :font-weight "600"}]]
    [:.active {:font-weight "600"}]
    ;; TEMPORARY PROBE — reverted in the next write. A unique string so the
    ;; served sheet can be grepped for it: this separates "refresh! never runs
    ;; at done" from "it ran once and stopped".
    
    ;; narrow: stack. Three independently-scrolling boxes on a phone is worse
    ;; than a long document.
    (gs/at-media {:max-width "60rem"}
                 [:.app {:grid-template-columns "minmax(0, 1fr)"}]
                 [:.app>nav {:width "auto" :border-right "none"
                             :border-bottom "1px solid #ddd"}]
                 [:aside {:width "auto" :border-left "none"
                          :border-top "1px solid #ddd"}])
    [:h1 {:font-size "1.4rem"}]
    [:h2 {:font-size "1.1rem" :margin-top "1.75rem"}]
    [:h3 {:font-size "1rem" :margin-bottom "0.25rem"}]
    [:h4 {:font-size "0.95rem" :margin-bottom "0.25rem"}]
    [:a {:color "#2a6"}]
    [:small {:color "#777"}]
    [:nav {:font-size "0.9rem" :color "#777"}]
    [:pre {:background "#f4f4f4" :padding "1rem" :border-radius "4px"
           :overflow-x "auto"}]
    [:code {:font-family "ui-monospace, monospace"}]
    ;; syntax classes: only what the CST can tell apart WITHOUT guessing, so
    ;; nothing here is coloured on a hunch (leaf-class carries the rest as text)
    [:.string {:color "#a50"}]
    [:.keyword {:color "#279"}]
    [:.number {:color "#279"}]
    [:.comment {:color "#888" :font-style "italic"}]
    [:.special {:color "#83d" :font-weight "600"}]
    [:.delim {:color "#999"}]
    ;; a diff line is a BLOCK so its marker column and its background line
    ;; up down the whole hunk, rather than hugging the text
    [:.del {:display "block" :color "#a33" :background "#fdeeee"}]
    [:.add {:display "block" :color "#178" :background "#eef7f2"}]
    [:article {:border-left "3px solid #ddd" :padding-left "0.9rem"
               :margin "1.25rem 0"}]
    [:footer {:margin-top "2.5rem" :border-top "1px solid #ddd"
              :padding-top "0.6rem"}]
    module-graph-styles
    ns-source-styles
    spine-styles
    gap-styles
    table-styles
    
    endpoint-styles
search-styles
        config-styles
    dashboard-styles

    (gs/at-media {:prefers-color-scheme :dark}
                 [:body {:background "#111" :color "#ddd"}]
                 [:header {:background "#191919" :border-bottom-color "#333"}]
                 [:.app>nav {:border-right-color "#333"}]
                 [:aside {:border-left-color "#333"}]
                 [:#ns-filter {:border-color "#444"}]
                 [:header [:.project-switcher:hover {:border-color "#444"}]]
[:header [:.store-search [:input {:border-color "#444"}]]]
                 [:header [:.store-search [:button:hover {:border-color "#444"}]]]
                 [:pre {:background "#1c1c1c"}]
                 [:a {:color "#5c9"}]
                 [:small {:color "#999"}]
                 [:nav {:color "#999"}]
                 [:.string {:color "#d94"}]
                 [:.keyword {:color "#7bd"}]
                 [:.number {:color "#7bd"}]
                 [:.comment {:color "#777"}]
                 [:.special {:color "#b9f"}]
                 [:.delim {:color "#666"}]
                 [:.del {:color "#e88" :background "#2a1a1a"}]
                 [:.add {:color "#6cb" :background "#14241f"}]
                 [:article {:border-left-color "#333"}]
                 [:footer {:border-top-color "#333"}])]))
