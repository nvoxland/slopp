(ns slopp.ui.callgraph-test
  "Tests for the call-navigation judgement half.

  **The fixture is authored from a schema that does not exist yet.**
  `GET /api/form/:id?depth=N` was asked for in
  `slopp-talk/20260804T131500-slopp-ui-to-slopp.md`; until it ships, `wire`
  below is what that ask says the response will look like. So a mismatch when
  the endpoint lands is a CONTRACT bug on whichever side moved, not a bad test
  — which is the whole reason the ask carried a schema instead of a
  description.

  Every assertion over the fixture is preceded by a control that the thing
  being looked for is actually in it. The population control alone is not
  enough and this project has slopp's own worked example of why: a haystack can
  be real while the needle no longer matches anything, and every assertion
  still passes."
  (:require [clojure.test :refer [deftest testing is]]
            [slopp.ui.callgraph :as callgraph]))

(def wire
  "A neighbourhood as `GET /api/form/:id?depth=2` was asked to send it:
  every node ONCE, edges as a list, `:via` on each.

  A chain with a branch and a weak last link:

  ```
  post.web/handle ──┐
                    ├─▶ post.order/quote ──▶ post.tariff/rate ──▶ post.tariff/band-for
  post.job/nightly ─┘                    ▲                              │ observed
                                         │                              ▼
                       post.web/preview ─┘                    post.money/round
  ```

  Two callers of `rate` and two of `quote`, so ranking has something to order
  at both depths, and one `observed` edge so a path has a weakest link that is
  not its first rung."
  {:focus "f-rate"
   :nodes [{:form-id "f-handle"  :form "handle"   :ns "post.web"    :module "post.web"
            :sig ["[req]"]      :doc "Answer a quote request."  :why "the http door"
            :warranty {:covered 2}}
           {:form-id "f-nightly" :form "nightly"  :ns "post.job"    :module "post.job"
            :sig ["[]"]         :doc nil                          :why nil
            :warranty {:covered 0}}
           {:form-id "f-preview" :form "preview"  :ns "post.web"    :module "post.web"
            :sig ["[req]"]      :doc "Price without booking."     :why nil
            :warranty {:covered 1}}
           {:form-id "f-quote"   :form "quote"    :ns "post.order"  :module "post.order"
            :sig ["[order]"]    :doc "Price one order."           :why "the order path"
            :warranty {:covered 4}}
           {:form-id "f-rate"    :form "rate"     :ns "post.tariff" :module "post.tariff"
            :sig ["[kg zone]"]  :doc "Rate for a weight in a zone." :why "the subject"
            :warranty {:covered 3}}
           {:form-id "f-band"    :form "band-for" :ns "post.tariff" :module "post.tariff"
            :sig ["[kg]"]       :doc "Which weight band."         :why nil
            :warranty {:covered 1}}
           {:form-id "f-round"   :form "round"    :ns "post.money"  :module "post.money"
            :sig ["[amount]"]   :doc "To the minor unit."         :why nil
            :warranty {:covered 6}}]
   :edges [{:from "f-handle"  :to "f-quote" :via "static"   :calls 1}
           {:from "f-nightly" :to "f-quote" :via "static"   :calls 1}
           {:from "f-quote"   :to "f-rate"  :via "static"   :calls 1}
           {:from "f-preview" :to "f-rate"  :via "static"   :calls 1}
           {:from "f-rate"    :to "f-band"  :via "static"   :calls 2}
           {:from "f-band"    :to "f-round" :via "observed" :calls 1}]})

(deftest neighbours-merges-each-edge-onto-the-node-at-its-far-end
  (testing "the fixture is a real haystack AND holds the needles"
    (is (= 7 (count (:nodes wire))))
    (is (some #(= "f-rate" (:to %)) (:edges wire))   "no inbound edge to the focus")
    (is (some #(= "f-rate" (:from %)) (:edges wire)) "no outbound edge from the focus"))
  (let [{:keys [callers callees]} (callgraph/neighbours wire "f-rate")]
    (testing "one hop, both directions"
      (is (= #{"f-quote" "f-preview"} (set (map :form-id callers))))
      (is (= #{"f-band"} (set (map :form-id callees)))))
    (testing "a caller arrives with its whole card, not just a name"
      (let [q (first (filter #(= "f-quote" (:form-id %)) callers))]
        (is (= "quote" (:form q)))
        (is (= ["[order]"] (:sig q)))
        (is (= "Price one order." (:doc q)))
        (is (= {:covered 4} (:warranty q)))))
    (testing "the edge's own facts ride along"
      (is (= "static" (:via (first callers))))
      (is (= 2 (:calls (first callees))) "band-for is called twice from rate"))
    (testing "a node two hops out is not a neighbour"
      (is (not (contains? (set (map :form-id callers)) "f-handle"))))))

(deftest rank-puts-confident-edges-first-then-what-carries-the-most-traffic
  (testing "the fixture holds the needle: two callers of the focus, differing in reach"
    (is (= 2 (count (:callers (callgraph/neighbours wire "f-rate")))))
    (is (some #(= "f-quote" (:to %)) (:edges wire)) "nothing reaches quote, so reach cannot differ"))
  (testing "same confidence — more of the graph flows through quote than through preview"
    (let [callers (:callers (callgraph/neighbours wire "f-rate"))]
      (is (= ["f-quote" "f-preview"] (map :form-id (callgraph/rank wire :callers callers)))
          "quote is reached by handle and nightly; preview is reached by nothing")))
  (testing "confidence outranks traffic — a weak edge sinks below a strong one"
    (let [nbhd {:nodes [{:form-id "a" :form "a" :ns "p.x" :module "p.x"}
                        {:form-id "b" :form "b" :ns "p.x" :module "p.x"}
                        {:form-id "c" :form "c" :ns "p.x" :module "p.x"}
                        {:form-id "hub" :form "hub" :ns "p.y" :module "p.y"}]
                :edges [{:from "a" :to "hub" :via "observed" :calls 9}
                        {:from "b" :to "hub" :via "static"   :calls 1}
                        {:from "c" :to "a"   :via "static"   :calls 1}]}
          callers (:callers (callgraph/neighbours nbhd "hub"))]
      (is (= #{"a" "b"} (set (map :form-id callers))) "fixture lost a caller")
      (is (= ["b" "a"] (map :form-id (callgraph/rank nbhd :callers callers)))
          "a has more reach and more call sites, but its edge is only observed")))
  (testing "ties break lexically, so the same input always renders the same way"
    (let [nbhd {:nodes [{:form-id "z" :form "zeta"  :ns "p.a" :module "p.a"}
                        {:form-id "m" :form "alpha" :ns "p.a" :module "p.a"}
                        {:form-id "t" :form "t"     :ns "p.b" :module "p.b"}]
                :edges [{:from "z" :to "t" :via "static" :calls 1}
                        {:from "m" :to "t" :via "static" :calls 1}]}
          callers (:callers (callgraph/neighbours nbhd "t"))]
      (is (= ["alpha" "zeta"] (map :form (callgraph/rank nbhd :callers callers)))))))

(deftest ego-shows-a-readable-few-and-says-how-many-it-held-back
  (testing "the fixture holds more callers than the cap under test"
    (is (= 2 (count (:callers (callgraph/neighbours wire "f-quote"))))))
  (testing "under the cap, nothing is held back"
    (let [{:keys [callers callees]} (callgraph/ego wire "f-rate" 6)]
      (is (= ["f-quote" "f-preview"] (map :form-id (:shown callers))))
      (is (zero? (:more callers)))
      (is (= ["f-band"] (map :form-id (:shown callees))))
      (is (zero? (:more callees)))))
  (testing "over the cap, the rest is COUNTED — never silently dropped"
    (let [{:keys [callers]} (callgraph/ego wire "f-quote" 1)]
      (is (= 1 (count (:shown callers))))
      (is (= 1 (:more callers)) "the held-back caller must be reported, not vanish")))
  (testing "what survives the cap is what rank put first"
    (is (= (take 1 (map :form-id (callgraph/rank wire :callers (:callers (callgraph/neighbours wire "f-quote")))))
           (map :form-id (:shown (:callers (callgraph/ego wire "f-quote" 1)))))))
  (testing "a form with nothing on one side says so with an empty list, not nil"
    (let [{:keys [callees]} (callgraph/ego wire "f-round" 6)]
      (is (= [] (:shown callees)))
      (is (zero? (:more callees)))))
  (testing "the default cap is the documented working-set size"
    (is (= (callgraph/ego wire "f-rate" 6) (callgraph/ego wire "f-rate")))))

(deftest spine-draws-one-whole-path-through-the-focus
  (testing "the fixture holds a path longer than one hop in BOTH directions"
    (is (seq (:callers (callgraph/neighbours wire "f-quote")))  "nothing above the focus's caller")
    (is (seq (:callees (callgraph/neighbours wire "f-band")))   "nothing below the focus's callee"))
  (let [{:keys [rungs focus cycle? weakest]} (callgraph/spine wire "f-rate")]
    (testing "root caller at the top, leaf callee at the bottom, focus in between"
      (is (= ["f-nightly" "f-quote" "f-rate" "f-band" "f-round"] (map :form-id rungs)))
      (is (= 2 focus))
      (is (= "f-rate" (:form-id (nth rungs focus)))))
    (testing "each rung carries the card, so the path is readable without another fetch"
      (is (= "Price one order." (:doc (nth rungs 1))))
      (is (= ["[kg zone]"] (:sig (nth rungs 2)))))
    (testing "a rung's :via is the call it makes to the rung BELOW it"
      (is (= ["static" "static" "static" "observed" nil] (map :via rungs))))
    (testing "the whole path is only as trustworthy as its weakest edge"
      (is (= "observed" weakest)
          "every rung but one is static; the path must not claim to be"))
    (testing "each rung says how many others could sit in its place, and which this is"
      (is (= [2 2 1 1 1] (map :alternatives rungs))
          "nightly and handle both call quote; quote and preview both call rate")
      (is (= [0 0 0 0 0] (map :choice rungs))))
    (is (false? cycle?)))
  (testing "a walk that comes back on itself stops and SAYS it stopped"
    (let [nbhd {:nodes [{:form-id "a" :form "a" :ns "p.x" :module "p.x"}
                        {:form-id "b" :form "b" :ns "p.x" :module "p.x"}]
                :edges [{:from "a" :to "b" :via "static" :calls 1}
                        {:from "b" :to "a" :via "static" :calls 1}]}
          {:keys [rungs cycle?]} (callgraph/spine nbhd "a")]
      (is (true? cycle?) "mutual recursion must be reported, not walked forever")
      (is (= (count rungs) (count (distinct (map :form-id rungs))))
          "a form appears at most ONCE in a path — the up-walk and the down-walk
           share one visited set, or `a` and `b` calling each other renders as
           b/a/b and the reader reads a loop as a chain")
      (is (= ["b" "a"] (map :form-id rungs)))))
  (testing "a form alone on the graph is a path of one, and says nothing false"
    (let [nbhd {:nodes [{:form-id "lone" :form "lone" :ns "p.x" :module "p.x"}] :edges []}
          {:keys [rungs focus weakest]} (callgraph/spine nbhd "lone")]
      (is (= ["lone"] (map :form-id rungs)))
      (is (zero? focus))
      (is (nil? weakest) "no edges means no claim about confidence, not a strong one"))))

(deftest spine-routes-through-what-you-prefer-so-a-swap-is-a-url
  (testing "the default already picks something else, or preferring proves nothing"
    (is (= "f-nightly" (:form-id (first (:rungs (callgraph/spine wire "f-rate")))))
        "handle must NOT already be the default, or the reroute assertion is vacuous"))
  (testing "preferring a rung reroutes the path through it"
    (let [{:keys [rungs]} (callgraph/spine wire "f-rate" #{"f-handle"})]
      (is (= ["f-handle" "f-quote" "f-rate" "f-band" "f-round"] (map :form-id rungs)))
      (is (= 1 (:choice (first rungs))) "handle is the second-ranked caller of quote")
      (is (= 2 (:alternatives (first rungs))) "the count of options does not move")))
  (testing "a preference nearer the focus reshapes everything above it"
    (let [{:keys [rungs focus]} (callgraph/spine wire "f-rate" #{"f-preview"})]
      (is (= ["f-preview" "f-rate" "f-band" "f-round"] (map :form-id rungs))
          "preview has no callers, so the path above the focus gets shorter")
      (is (= 1 focus))))
  (testing "preferring something already on the path changes nothing"
    (is (= (callgraph/spine wire "f-rate") (callgraph/spine wire "f-rate" #{"f-round"}))))
  (testing "the no-preference arity is the empty preference"
    (is (= (callgraph/spine wire "f-rate") (callgraph/spine wire "f-rate" #{})))))

(deftest a-depth-one-form-view-becomes-a-neighbourhood-the-same-functions-read
  ;; `/api/form/:id` answers TODAY, in its own shape: callers grouped by the
  ;; `:via` that found them, callees flat and carrying their cards. The
  ;; neighbourhood shape asked for in slopp-talk is nodes-once-plus-edges.
  ;; Adapting between them here is what lets `ego` ship on the endpoint that
  ;; exists rather than waiting for the one that does not — and when `?depth=N`
  ;; lands, the same `ego` and `spine` read it with no view changing.
  (let [form-view {:form-id "f-rate" :name "rate" :form "post.tariff/rate"
                   :ns "post.tariff" :module "post.tariff"
                   :sig ["[kg zone]"] :doc "Rate for a weight in a zone."
                   :why "the subject" :warranty {:covered 3}
                   :callers [{:via "static" :count 2
                              :forms [{:form "quote" :ns "post.order" :module "post.order"
                                       :form-id "f-quote" :calls 1}
                                      {:form "preview" :ns "post.web" :module "post.web"
                                       :form-id "f-preview" :calls 1}]}
                             {:via "observed" :count 1
                              :forms [{:form "audit" :ns "post.job" :module "post.job"
                                       :form-id "f-audit" :calls 1}]}]
                   :callees [{:form "band-for" :ns "post.tariff" :module "post.tariff"
                              :form-id "f-band" :via "static" :calls 2
                              :sig ["[kg]"] :doc "Which weight band."
                              :warranty {:covered 1}}]}
        nbhd      (callgraph/from-form-view form-view)]
    (testing "the fixture holds callers under MORE THAN ONE via, or the
              regrouping assertion below is vacuous"
      (is (= 2 (count (:callers form-view))))
      (is (= #{"static" "observed"} (set (map :via (:callers form-view))))))

    (testing "the focus is a node like any other, so ego and spine can centre on it"
      (is (= "f-rate" (:focus nbhd)))
      (is (some #(= "f-rate" (:form-id %)) (:nodes nbhd))))
    (testing "every form in the response becomes exactly one node"
      (is (= #{"f-rate" "f-quote" "f-preview" "f-audit" "f-band"}
             (set (map :form-id (:nodes nbhd)))))
      (is (= (count (:nodes nbhd)) (count (distinct (map :form-id (:nodes nbhd)))))))
    (testing "a caller's group :via becomes the EDGE's via — the grouping was
              a property of the reference, not of the caller"
      (is (= #{["f-quote" "f-rate" "static"] ["f-preview" "f-rate" "static"]
               ["f-audit" "f-rate" "observed"] ["f-rate" "f-band" "static"]}
             (set (map (juxt :from :to :via) (:edges nbhd))))))
    (testing "and the adapted neighbourhood reads correctly through ego —
              the observed caller sinks below both static ones"
      (let [{:keys [callers callees]} (callgraph/ego nbhd "f-rate")]
        (is (= "f-audit" (:form-id (last (:shown callers))))
            "an observed edge must not outrank a static one")
        (is (= ["f-band"] (map :form-id (:shown callees))))))))

(deftest a-rung-carries-the-alternatives-it-was-chosen-from
  ;; `:alternatives` says "1 of 2" — which tells a reader another path exists
  ;; and gives them no way to reach it. Blaze's combination lock is the whole
  ;; idiom: each position shows how many options there are AND lets you turn
  ;; to one. That needs the rows, not the count.
  (testing "the fixture has a position with a real choice at it"
    (is (= 2 (count (:callers (callgraph/neighbours wire "f-quote"))))))
  (let [{:keys [rungs]} (callgraph/spine wire "f-rate")
        top (first rungs)]
    (testing "the options are the ranked rows at that position, chosen one included"
      (is (= ["f-nightly" "f-handle"] (map :form-id (:options top))))
      (is (= (count (:options top)) (:alternatives top))
          ":alternatives must be the size of :options, not a separately counted number"))
    (testing "and every rung has options, including the focus, so a view never
              has to special-case the middle of the path"
      (is (every? #(seq (:options %)) rungs))
      (is (= ["f-rate"] (map :form-id (:options (nth rungs (:focus (callgraph/spine wire "f-rate")))))))
      (is (every? #(= (:form-id %) (:form-id (nth (:options %) (:choice %)))) rungs)
          ":choice must index into :options — a view renders the dots from both"))))

(deftest a-graph-in-the-response-supersedes-the-one-hop-view
  ;; `?depth=N` is finally reachable — three separate causes deep. The response
  ;; now carries `:graph` alongside `:callers`/`:callees`, and they are not
  ;; redundant: the graph reaches N hops with LEAN nodes, while the one-hop
  ;; lists carry the full card. Taking the graph and keeping the cards is the
  ;; whole point — depth for the spine, cards for the rail, one fetch.
  (let [view {:form-id "f-rate" :name "rate" :ns "post.tariff" :module "post.tariff"
              :sig ["[kg zone]"] :doc "Rate for a weight." :why "the subject"
              :warranty {:covered 3}
              :callers [{:via "static" :count 1
                         :forms [{:form "quote" :ns "post.order" :module "post.order"
                                  :form-id "f-quote" :calls 1
                                  :sig ["[order]"] :doc "Price one order."
                                  :warranty {:covered 4}}]}]
              :callees []
              :graph {:depth 2
                      :nodes [{:form-id "f-rate" :form "rate" :ns "post.tariff"
                               :module "post.tariff"}
                              {:form-id "f-quote" :form "quote" :ns "post.order"
                               :module "post.order"}
                              ;; TWO hops out — invisible to :callers entirely
                              {:form-id "f-handle" :form "handle" :ns "post.web"
                               :module "post.web"}]
                      :edges [{:from "f-quote" :to "f-rate" :via "static"}
                              {:from "f-handle" :to "f-quote" :via "static"}]}}
        nbhd (callgraph/from-form-view view)]

    (testing "the fixture's graph reaches something the one-hop lists cannot"
      (is (= 1 (count (:callers view))))
      (is (some #(= "f-handle" (:form-id %)) (:nodes (:graph view))))
      (is (not-any? #(= "f-handle" (:form-id %))
                    (mapcat :forms (:callers view)))))

    (testing "every graph node is present, including the two-hop one"
      (is (= #{"f-rate" "f-quote" "f-handle"} (set (map :form-id (:nodes nbhd))))))
    (testing "and each still appears exactly once"
      (is (= (count (:nodes nbhd)) (count (distinct (map :form-id (:nodes nbhd)))))))
    (testing "the graph's edges are the edges, :via and all"
      (is (= #{["f-quote" "f-rate" "static"] ["f-handle" "f-quote" "static"]}
             (set (map (juxt :from :to :via) (:edges nbhd))))))

    (testing "the richer depth-1 cards SURVIVE — a graph node is lean, and
              dropping the card to gain the hop would trade the rail for the spine"
      (let [q (first (filter #(= "f-quote" (:form-id %)) (:nodes nbhd)))]
        (is (= "Price one order." (:doc q)))
        (is (= {:covered 4} (:warranty q)))))
    (testing "the focus keeps its own card too"
      (let [r (first (filter #(= "f-rate" (:form-id %)) (:nodes nbhd)))]
        (is (= "the subject" (:why r)))))

    (testing "and the spine now actually walks two hops"
      (is (= ["f-handle" "f-quote" "f-rate"]
             (map :form-id (:rungs (callgraph/spine nbhd "f-rate"))))))

    (testing "a capped graph says so, rather than reading as the whole of it"
      (let [capped (assoc-in view [:graph :truncated] {:node-cap 250 :depth-reached 2})]
        (is (= {:node-cap 250 :depth-reached 2} (:truncated (callgraph/from-form-view capped))))
        (is (nil? (:truncated nbhd)) "absent means nothing was dropped")))))
