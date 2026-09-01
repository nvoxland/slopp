(ns slopp.read.anticipate
  "Answer the reader's NEXT question inside this one. Models read whole
  namespaces and then read their requires, one edge per turn — measured
  across six eval transcripts, 46-61% of one model's read calls were the
  require chain of the read before. This namespace ranks what to attach;
  the mcp layer decides where it rides. Pure: store value in, rows out."
  (:require [slopp.store :as store]
            [slopp.store.render :as store.render]))

(defn ^:export expansion
  "What a reader who just read `ns-sym` whole is about to ask for next — the
  namespace's DIRECT requires, as `[{:ns :source :tokens} …]`, smallest
  first, within a cumulative `budget` (~4 chars/token), skipping anything
  `held?` says the reader already has.

  Direct requires ONLY, by measurement rather than modesty: replayed against
  six real eval transcripts, this signal alone made 46-61% of sonnet's read
  calls answerable before they were asked for ~3k tokens per lifetime, while
  a 2-hop closure added nothing and reverse (caller) edges bought at most
  one more call for 7-20x the rent — fan-in explodes on exactly the
  namespaces everyone requires. The budget REFUSES a namespace that does
  not fit rather than truncating its source: a partial source reads as the
  whole thing, and a reader acting on half a namespace is worse off than
  one who asks."
  [st ns-sym held? budget]
  (let [known (set (keys (:namespaces st)))]
    (if-not (contains? known ns-sym)
      []
      (let [cands (->> (store/ns-requires st ns-sym)
                       (filter known)
                       (remove held?)
                       (map (fn [n]
                              (let [src (store.render/render-ns st n)]
                                {:ns n :source src
                                 :tokens (max 1 (quot (count src) 4))})))
                       (sort-by :tokens))]
        (loop [out [] left budget [r & more] cands]
          (if (nil? r)
            out
            (if (<= (:tokens r) left)
              (recur (conj out r) (- left (:tokens r)) more)
              (recur out left more))))))))
