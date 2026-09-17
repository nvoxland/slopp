(ns slopp.project.dev-test
  "Cover for `slopp.project.dev` — the `dev` config path.

  Two properties matter and they pull in opposite directions: the declaration
  must be RICH enough to say what a developer means (an entry point, ordered
  arguments, a way to silence one entry), and it must be INERT everywhere
  outside a development session. The second half is asserted in
  `slopp.sync-test`, `slopp.git-projection-test` and `slopp.build-native-test`,
  against the artifacts rather than against the filter."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.project.dev :as dev]))

(deftest dev-override-reads-the-dev-overlay-of-a-capability-key
  ;; The `dev` file overrides a CAPABILITY for the dev instance: `dev.http.port`
  ;; overrides `http.port`. The keys ARE capability keys, validated against the
  ;; capabilities registry, so a dev override is checked at the write like any
  ;; config and parsed to the capability's own type.
  (let [st (-> (store/empty-store)
               (assoc-in [:config "dev" :values "http.port"] "7358"))]
    (testing "the dev value, parsed to the capability's declared type"
      (is (= 7358 (dev/override st "http.port"))))
    (testing "nil when the dev file does not override the key"
      (is (nil? (dev/override st "http.host"))))
    (testing "nil when there is no dev config at all"
      (is (nil? (dev/override (store/empty-store) "http.port"))))
    (testing "a bad dev value is ignored (nil), not thrown — it must not break the boot"
      (is (nil? (dev/override (assoc-in (store/empty-store) [:config "dev" :values "http.port"] "nope")
                             "http.port"))))))

(deftest dev-local-overrides-the-shared-dev-config
  ;; Three layers, highest first: a per-process `SLOPP_DEV_<KEY>` env override,
  ;; this machine's `dev.local`, then the project's shared `dev`. The shared
  ;; one projects to git so a clone serves without setup; `dev.local` stays
  ;; in the db so one developer's port never reaches anyone else.
  (let [st (-> (store/empty-store)
               (assoc-in [:config "dev" :values "http.port"] "7358")
               (assoc-in [:config "dev" :values "http.host"] "127.0.0.1"))]
    (testing "the shared dev value, when nothing local overrides it"
      (is (= 7358 (dev/override st "http.port"))))
    (testing "dev.local wins over dev for the key it sets"
      (let [st* (assoc-in st [:config "dev.local" :values "http.port"] "7399")]
        (is (= 7399 (dev/override st* "http.port")))
        (is (= "127.0.0.1" (dev/override st* "http.host"))
            "and only for the key it sets — the shared value stands otherwise")))
    (testing "a bad dev.local value falls through to the shared one, not to nil"
      (let [st* (assoc-in st [:config "dev.local" :values "http.port"] "nope")]
        (is (= 7358 (dev/override st* "http.port")))))))
