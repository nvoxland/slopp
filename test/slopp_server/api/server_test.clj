(ns slopp-server.api.server-test
  "The project API's one remaining promise: the list of namespaces it serves
  is checked against what declares endpoints, so a route cannot be served by
  nobody. The listener that used to live here is the server's now."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp-server.api.server :as server]
            [clojure.set :as set] [clojure.string :as str]))

(deftest the-served-list-is-checked-against-what-declares-endpoints
  ;; `served-namespaces`' docstring argues at length that the list must be
  ;; SINGULAR — it had two mounts once, and a literal repeated at both is how
  ;; a namespace ends up served by nobody. That argument is correct and it
  ;; solved half the problem: the DUPLICATION half. The remaining single list
  ;; can still fall behind the code it stands for, and nothing compared them.
  ;;
  ;; Found by slopp-ui, who took the "hand-kept list vs something derivable"
  ;; shape, applied it to their own store, hit the identical defect in their
  ;; own `served-namespaces`, and handed back the heuristic that finds these:
  ;; **look for prose arguing that a list should be singular.** That argument
  ;; is made by an author who has noticed the list is load-bearing — which is
  ;; exactly when the derivation gap gets written and not seen.
  ;;
  ;; Derived from the IMAGE rather than a store: loaded vars carry their own
  ;; metadata, so this needs no store read — which matters, because nothing
  ;; in this tier can open slopp's own store.
  (let [declares? (fn [nsx] (some #(let [m (meta %)]
                                     (or (:rest/path m) (:http/path m) (:http/read m)))
                                  (vals (ns-publics nsx))))
        candidates (->> (all-ns) (map ns-name)
                        ;; the prefix is DATA — a rename rewrites code and
                        ;; walks straight past a string, so this scan can come
                        ;; to match nothing. That is why the liveness check
                        ;; below is not decoration: phase 2 broke this literal
                        ;; and the check turned it red, and the framework/server
                        ;; split (slopp.api.* → slopp-server.api.*) broke it
                        ;; again. A sibling guard without one shipped green
                        ;; against an empty search.
                        (filter #(str/starts-with? (str %) "slopp-server.api."))
                        ;; endpoint-shaped forms in tests are fixtures and
                        ;; claim no route — query_surface scopes the same way
                        (remove #(str/ends-with? (str %) "-test")))
        derived    (set (filter declares? candidates))
        listed     (set server/served-namespaces)]
    (testing "the scan found something — two empty sets agree"
      ;; the same trap as `crossings/unclassified-markers` returning empty
      ;; while a marker went unclassified for a week: a check whose population
      ;; can silently become zero reports success for the wrong reason
      (is (seq derived) (str "scanned " (count candidates) " namespaces")))
    (testing "and the list is exactly what declares an endpoint or a read performer"
      (is (= derived listed)
          (str "declares :http/path or :http/read but is not served: "
               (set/difference derived listed)
               " / served but declares neither: "
               (set/difference listed derived))))))
