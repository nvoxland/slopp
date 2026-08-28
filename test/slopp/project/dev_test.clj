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

(deftest a-project-DECLARES-what-to-run-in-development
  ;; Whether slopp runs a project's server is DERIVED (`live/managed?`), and
  ;; that stays derived — a per-project on/off switch let a bug masquerade as
  ;; a preference once, which is why the `dev.server` capability was deleted.
  ;;
  ;; This is the other question. Not WHETHER, but WHAT: an entry point, its
  ;; arguments, and a name to address it by. Derivation cannot know that a
  ;; developer wants the admin server on 9999, and until now there was
  ;; nowhere to say it.
  (let [st (-> (store/empty-store)
               (assoc-in [:config "dev" :values "run.app.main"] "shop.core/-main")
               (assoc-in [:config "dev" :values "run.app.args"] "--port,8080")
               (assoc-in [:config "dev" :values "run.worker.main"] "shop.jobs/-main"))]

    (testing "each declared name comes back with what to run"
      (is (= 'shop.core/-main (get-in (dev/runnables st) ["app" :main])))
      (is (= 'shop.jobs/-main (get-in (dev/runnables st) ["worker" :main]))))

    (testing "arguments are a LIST, in the order they were written"
      ;; a set would lose the order, and an entry point's arguments are
      ;; positional — `--port 8080` is not `8080 --port`. That is the whole
      ;; reason :csv-list exists beside :csv rather than reusing it
      (is (= ["--port" "8080"] (get-in (dev/runnables st) ["app" :args]))))

    (testing "an entry with no args declares none, rather than an empty string"
      (is (= [] (get-in (dev/runnables st) ["worker" :args]))))

    (testing "enabled by default, because declaring it IS asking for it"
      (is (true? (get-in (dev/runnables st) ["app" :enabled?]))))

    (testing "and a declared entry can be turned off without deleting it"
      ;; the reason this key exists rather than "just remove the line": a
      ;; developer silencing the worker for an afternoon should not have to
      ;; retype its entry point to get it back
      (let [off (assoc-in st [:config "dev" :values "run.worker.enabled"] "false")]
        (is (false? (get-in (dev/runnables off) ["worker" :enabled?])))
        (is (true? (get-in (dev/runnables off) ["app" :enabled?])))))

    (testing "a store that declares nothing runs nothing"
      ;; the common case — EMPTY, rather than a map of nils
      (is (= {} (dev/runnables (store/empty-store)))))

    (testing "an entry with no :main is not a runnable"
      ;; args alone cannot start anything, and reporting it would hand the
      ;; supervisor something it could only refuse
      (let [orphan (assoc-in (store/empty-store)
                             [:config "dev" :values "run.ghost.args"] "--port,1")]
        (is (= {} (dev/runnables orphan)))))

    (testing "a key the registry does not govern is ignored, not guessed at"
      ;; `run.app.typo` matches no pattern, so `find-entry` answers nil and
      ;; the entry is unaffected — a typo must not silently become a field
      (let [typo (assoc-in st [:config "dev" :values "run.app.prot"] "9999")]
        (is (= (dev/runnables st) (dev/runnables typo)))))))
