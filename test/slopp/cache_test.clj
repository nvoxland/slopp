(ns slopp.cache-test
  "The blessed cache: one construct every memo goes through, so caching is
  controllable in tests and mechanically recognizable by the tier gate."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.cache :as cache]))

(deftest cached-computes-once-per-key
  (cache/reset-all!)
  (let [calls (atom 0)
        f     (fn [k] (cache/cached ::demo k (fn [] (swap! calls inc) (str "v" k))))]
    (testing "the thunk runs once per key, and the value comes back"
      (is (= "v1" (f 1)))
      (is (= "v1" (f 1)))
      (is (= 1 @calls)))
    (testing "a different key is a different entry"
      (is (= "v2" (f 2)))
      (is (= 2 @calls)))))

(deftest reset-all-clears-every-cache
  (cache/reset-all!)
  (let [calls (atom 0)
        f     (fn [] (cache/cached ::demo2 :k (fn [] (swap! calls inc) :v)))]
    (f) (f)
    (is (= 1 @calls))
    (cache/reset-all!)
    (f)
    (is (= 2 @calls) "after reset the thunk runs again")))

(deftest without-caching-bypasses-so-a-test-can-see-the-real-computation
  ;; THE test utility: a cache can hide a bug by answering from a previous
  ;; call. Under `without-caching` every call recomputes, so a test proves the
  ;; computation rather than the cache.
  (cache/reset-all!)
  (let [calls (atom 0)
        f     (fn [] (cache/cached ::demo3 :k (fn [] (swap! calls inc) :v)))]
    (is (= :v (cache/without-caching! (fn [] (f) (f) (f)))))
    (is (= 3 @calls) "every call recomputed")
    (testing "and nothing was stored, so later callers are not served a stale entry"
      (f)
      (is (= 4 @calls)))))

(deftest registry-reports-what-is-cached
  (cache/reset-all!)
  (cache/cached ::demo4 :k (fn [] :v))
  (let [r (cache/registry)]
    (is (contains? r ::demo4) (pr-str r))
    (is (= 1 (get r ::demo4)) (pr-str r))))

(deftest cached-last-keys-on-identity-not-value
  ;; The second strategy in the store, and it is not a stylistic variant: the
  ;; whole-store graph memo keys on the STORE, which is a large map. Hashing it
  ;; on every call would cost more than the computation saved. The store is
  ;; immutable, so a new value appears only on a write — identity is a sound
  ;; key by construction.
  (cache/reset-all!)
  (let [calls (atom 0)
        big-a {:n 1} big-b {:n 1}          ; equal, NOT identical
        f     (fn [k] (cache/cached-last ::graph k (fn [] (swap! calls inc) [:v k])))]
    (testing "the same identity hits"
      (is (= [:v big-a] (f big-a)))
      (is (= [:v big-a] (f big-a)))
      (is (= 1 @calls)))
    (testing "an EQUAL but distinct value misses — identity, not value"
      (is (= [:v big-b] (f big-b)))
      (is (= 2 @calls)))
    (testing "it holds only the LAST entry, so going back recomputes"
      (f big-a)
      (is (= 3 @calls)))
    (testing "and it honours the test controls like every blessed cache"
      (is (= [:v big-a] (cache/without-caching! (fn [] (f big-a)))))
      (is (= 4 @calls)))))

(deftest cached-latest-keeps-a-bounded-number-of-groups
  ;; a group that stops being asked for, such as a landed thread's view line,
  ;; must not hold its value forever: one of these was a whole store copy
  (cache/reset-all!)
  (let [calls (atom 0)
        get!  (fn [g k] (cache/cached-latest :bounded g k (fn [] (swap! calls inc) [g k])))]
    (try
      (doseq [g (range 10)] (get! g :k))
      (testing "only a few groups stay"
        (is (<= (:bounded (cache/registry)) 4) (pr-str (cache/registry))))
      (testing "the newest are the ones kept, and hit"
        (reset! calls 0)
        (is (= [9 :k] (get! 9 :k)))
        (is (zero? @calls)))
      (testing "an evicted group only recomputes, and answers the same"
        (reset! calls 0)
        (is (= [0 :k] (get! 0 :k)))
        (is (= 1 @calls)))
      (testing "a moved key still replaces, within its group"
        (reset! calls 0)
        (is (= [9 :k2] (get! 9 :k2)))
        (is (= 1 @calls)))
      (finally (cache/reset-all!)))))
