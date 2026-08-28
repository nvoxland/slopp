(ns slopp.project.dev
  "The `dev` config path: what this project declares it wants RUN while
  somebody is working on it.

  **Separate from `slopp.project.capabilities` on purpose.** That registry's
  own rule is that a key's first segment names the CAPABILITY that owns it,
  and `run` names no capability — `dev` is not one either. It is a config
  path that `slopp.store/local-config-paths` keeps out of every built tree
  and every projected one, so a port a developer picked never reaches a jar
  and a second developer's pull does not carry the first one's choices.

  What is SHARED with capabilities is the machinery, not the keys: the type
  vocabulary, `check-value`, `parse-value` and `find-entry` all serve both
  registries, so adding a type is not done twice.

  What is NOT here, deliberately: WHETHER slopp should run a project's server
  at all. That stays derived (`slopp.webdev.live/managed?`), because a
  per-project on/off switch once let a bug masquerade as a preference and the
  capability that did it was deleted for that reason. This namespace answers
  what to run and how, which derivation cannot know."
  (:require [clojure.string :as str]
            [slopp.project.capabilities :as capabilities]))

(def ^{:export "slopp.project"} registry
  "The `dev` config keys — the same `{:key :type :default :doc}` shape as
  `capabilities/registry`, read through the same `check-value`, so a runnable
  is validated AT THE WRITE rather than discovered when nothing starts.

  `run.*.main` uses the trailing-`*` pattern the registry machinery already
  understands (`http.auth.groups.*.members` is the same shape), so the NAME of
  a runnable is a SEGMENT OF ITS KEY rather than a value to be parsed. Two
  entries cannot then collide by spelling, and each key validates on its own."
  [{:key "run.*.main" :type [:qualified-symbol] :default nil
    :doc "The entry fn to run under this name (shop.core/-main). The name is the key's own segment: run.admin.main declares `admin`."}
   {:key "run.*.args" :type [:csv-list] :default nil
    :doc "Arguments handed to the entry fn, comma-separated and IN ORDER — --port,8080 arrives as [\"--port\" \"8080\"]. Ordered because arguments are positional, which is why this is :csv-list and not :csv."}
   {:key "run.*.enabled" :type [:boolean] :default true
    :doc "Whether to start this entry. Default true, because declaring a runnable IS asking for it; set false to silence one for an afternoon without deleting its entry point."}])

(defn ^:export runnables
  "What `store` declares it wants RUN while somebody is working on it:
  `{\"app\" {:main shop.core/-main :args [\"--port\" \"8080\"] :enabled? true}}`.

  Named, because a project grows a second process — a worker, an admin port —
  and a single anonymous entry would have to be redesigned the day it does.
  The names are also what a per-user override layer keys on when it arrives.

  **An entry with no `:main` is dropped.** Arguments alone cannot start
  anything, and reporting one would hand the supervisor something it could
  only refuse. `:enabled? false` is a different thing and stays in the map: a
  declared entry deliberately silenced is still something a reader should see
  was asked for."
  [store]
  (let [values (get-in store [:config "dev" :values])
        read   (fn [k]
                 (when-let [entry (capabilities/find-entry registry k)]
                   (let [v (get values k)]
                     (if (and v (nil? (capabilities/check-value entry v)))
                       (capabilities/parse-value entry v)
                       (:default entry)))))
        ;; the name is the MIDDLE segment of `run.<name>.<field>`, so the set
        ;; of declared names comes from the keys rather than from a list
        ;; somebody has to keep in step with them
        names  (into (sorted-set)
                     (keep (fn [k]
                             (let [segs (str/split (str k) #"\.")]
                               (when (and (= 3 (count segs)) (= "run" (first segs)))
                                 (second segs)))))
                     (keys values))]
    (into {}
          (keep (fn [nm]
                  (when-let [main (read (str "run." nm ".main"))]
                    [nm {:main     main
                         :args     (or (read (str "run." nm ".args")) [])
                         :enabled? (read (str "run." nm ".enabled"))}])))
          names)))

(defn ^:export config-refusal
  "The `dev` config write gate: a teaching error for a key the registry does
  not govern or a value that fails its declared type — nil when the write may
  land.

  An unknown key MUST refuse, for the reason the `rules` registry was wired
  in: with nothing to disagree with, a renamed field and a MISTYPED one are
  the same event — both accepted, both governing nothing, neither reported.
  `run.app.prot` is not a port; it is silence.

  No credential clause, unlike `capabilities/config-refusal`. That one exists
  because capability config is tracked and git-projected, so a literal secret
  in it travels; `dev` is in `slopp.store/local-config-paths` and reaches no
  tree at all. The reasoning is worth stating rather than leaving as an
  absence, because the two gates otherwise look like one gate with a piece
  missing."
  [k v]
  (let [k (str k) v (str v)]
    (if-let [entry (capabilities/find-entry registry k)]
      (capabilities/check-value entry v)
      (str k " is not a dev setting — known keys: "
           (str/join ", " (map :key registry))
           ". The name of a runnable is the key's own middle segment, so"
           " run.admin.main declares `admin`."))))
