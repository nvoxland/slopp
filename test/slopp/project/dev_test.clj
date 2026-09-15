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
