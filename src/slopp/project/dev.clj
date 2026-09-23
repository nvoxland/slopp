(ns slopp.project.dev
  "The `dev` config path: what this project declares it wants RUN while
  somebody is working on it.

  **Separate from `slopp.project.capabilities` on purpose.** That registry's
  own rule is that a key's first segment names the CAPABILITY that owns it,
  and `run` names no capability — `dev` is not one either. It is a config
  path in two layers: `dev` is the project's SHARED dev setup, projected to
  git so a clone serves without a step (and kept out of every built tree by
  `slopp.store/unbuilt-config-paths` — a jar has no use for a dev port), and
  `dev.local` is one developer's override of it on one machine, in
  `slopp.store/local-config-paths` so it reaches no tree at all.

  What is SHARED with capabilities is the machinery, not the keys: the type
  vocabulary, `check-value`, `parse-value` and `find-entry` all serve both
  registries, so adding a type is not done twice.

  What is NOT here, deliberately: WHETHER slopp should run a project's server
  at all. That stays derived (`slopp.webdev.live/managed?`), because a
  per-project on/off switch once let a bug masquerade as a preference and the
  capability that did it was deleted for that reason. This namespace answers
  what to run and how, which derivation cannot know."
  (:require [slopp.project.capabilities :as capabilities]))

(defn ^:export config-refusal
  "The `dev` config write gate: a teaching error for a key that is not a
  capability or a value that fails its type — nil when the write may land.

  The `dev` file OVERRIDES capabilities for the dev instance, so its keys ARE
  capability keys (`dev.http.port` overrides `http.port`) and validate against
  the SAME registry and the same `check-value` — a mistyped dev key and a
  mistyped capability are the same error, caught the same way.

  An unknown key MUST refuse, for the reason the `rules` registry was wired
  in: with nothing to disagree with, a renamed field and a MISTYPED one are
  the same event — both accepted, both governing nothing, neither reported.

  No credential clause, unlike `capabilities/config-refusal`, and that is a
  judgment rather than an oversight: `dev` projects to git now, but its keys
  are a port, a host, an entry point — the capability keys, none of which
  is a place a secret has any business being. `dev.local` never leaves the
  db either way. The same gate serves both paths."
  [k v]
  (let [k (str k) v (str v)]
    (if-let [entry (capabilities/find-entry k)]
      (capabilities/check-value entry v)
      (str k " is not a capability key — the dev file OVERRIDES capabilities for"
           " the dev instance (dev.http.port overrides http.port), so its keys"
           " are capability keys."))))

(defn ^:export override
  "The dev overlay's value for capability key `k`, or nil when no dev layer
  overrides it. Three layers, highest first: a per-process `SLOPP_DEV_<KEY>`
  env override, this machine's `dev.local` config, then the project's shared
  `dev` config. A value that fails the key's type is skipped, not thrown — a
  bad local override falls through to the shared one, and a bad shared one to
  the capability's own effective value, so no layer can break the boot.

  The `dev` file mirrors the CAPABILITY keys — `dev.http.port` overrides
  `http.port` for the dev instance — so `k` is validated and parsed against the
  capabilities registry, the same keys and the same `check-value` every config
  goes through.

  `dev` PROJECTS: it is the setup anyone who clones the project gets, complete
  enough to serve without a step. `dev.local` stays in the db: it is what lets
  one developer move the dev instance to a port this box has free, or two
  servers on one store run their dev instances apart, without pushing that
  choice at anyone. A dev SETTING rather than overriding `http.port` directly
  because `http.port` is the PRODUCTION address — for slopp's own store the
  machine server's — and the in-progress copy must not take it."
  [store k]
  (when-let [entry (capabilities/find-entry k)]
    (some (fn [raw]
            (when (and raw (nil? (capabilities/check-value entry raw)))
              (capabilities/resolve-config entry raw nil)))
          [(capabilities/env-config "dev" k)
           (get-in store [:config "dev.local" :values k])
           (get-in store [:config "dev" :values k])])))
