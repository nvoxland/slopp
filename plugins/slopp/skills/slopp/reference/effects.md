<!-- reference topic `effects` — served whole by `help {topic "effects"}`; the one-page SKILL.md points here. Written against d077b2e3837dc. -->

## Effects, deps, escape hatches

- `deps_add` a library, then require it normally (hot classpath add, no
  restart). `deps.edn` is GENERATED — never hand-edit it.
- **A library slopp itself bundles runs at slopp's version in the SERVER
  process**, and `deps_add` says so as `:host-override {lib {:declared
  :in-force}}`. Your declaration still governs the oracle image, the test
  suite and anything `build` produces — so the server and your tests can run
  different versions of the same library. Usually harmless; pin to the
  `:in-force` version when it is not. A jar the server's parent classloader
  already holds cannot be displaced at runtime, so slopp reports the
  disagreement rather than claiming the declaration won. Separately,
  `:shadowed` names a namespace more than one classpath entry provides (the
  first url listed is the one in force) — usually two deps vendoring the same
  code.
- Calls into an opaque dep count as EFFECTFUL: name the caller `!`, or
  `deps_pure` the var/namespace/lib, or tag the form `^:reads` (reads take no
  bang).
- `^:unsafe` opts ONE form out of the dialect gate — the greppable last resort.
  It does not silence `!`-naming. The honest case is analysis code that NAMES a
  banned symbol as data; comparing head names as strings avoids the marker
  entirely.
- **Every memo goes through `slopp.cache`.** That is what keeps `:internal`
  checkable — an ad-hoc atom is indistinguishable from arbitrary mutation.
  `without-caching!` bypasses for a test; `reset-all!` clears every cache.
- `build {dir}` materializes plain files (absolute path, outside the repo);
  with `main` it also emits a GraalVM native-image recipe. Repo sync, uberjars,
  config files, CI: the `slopp-setup` skill.

