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

  No credential clause, unlike `capabilities/config-refusal`. That one exists
  because capability config is tracked and git-projected, so a literal secret
  in it travels; `dev` is in `slopp.store/local-config-paths` and reaches no
  tree at all."
  [k v]
  (let [k (str k) v (str v)]
    (if-let [entry (capabilities/find-entry k)]
      (capabilities/check-value entry v)
      (str k " is not a capability key — the dev file OVERRIDES capabilities for"
           " the dev instance (dev.http.port overrides http.port), so its keys"
           " are capability keys."))))

(defn ^:export override
  "The `dev` overlay's value for capability key `k`, or nil when the dev file
  does not override it (and nil for a value that fails the key's type, so a bad
  override falls through to the capability's own effective value rather than
  breaking the boot).

  The `dev` file mirrors the CAPABILITY keys — `dev.http.port` overrides
  `http.port` for the dev instance — so `k` is validated and parsed against the
  capabilities registry, the same keys and the same `check-value` every config
  goes through. A per-process `SLOPP_DEV_<KEY>` env override wins over the
  stored value, which is what lets two daemons on one store run their dev
  instances on different ports.

  A dev SETTING rather than overriding `http.port` directly because `http.port`
  is the PRODUCTION address — for slopp's own store the machine daemon's — and
  the in-progress copy must not take it."
  [store k]
  (let [entry  (capabilities/find-entry k)
        stored (get-in store [:config "dev" :values k])
        raw    (or (capabilities/env-config "dev" k) stored)]
    (when (and entry raw (nil? (capabilities/check-value entry raw)))
      (capabilities/resolve-config entry raw nil))))
