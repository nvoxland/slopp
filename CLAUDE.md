@AGENTS.md

## Claude Code specifics

Everything above is the shared instruction set, kept in `AGENTS.md` so every
harness reads one copy. Claude Code reads `CLAUDE.md` and not `AGENTS.md`, so
this file imports it. Put shared rules in `AGENTS.md`; only Claude-specific
wiring belongs below.

- **The slopp tools and skills come from the plugin** (`/plugin install
  slopp@slopp`), so `slopp`, `slopp-setup`, `slopp-style` and `slopp-review`
  are available without reading files — invoke them rather than opening
  `plugins/slopp/skills/**/SKILL.md`. Editing those files is a different
  matter: they SHIP, so treat a change to one as a product change.
- **Turns and identity are automatic here.** The plugin's prompt hook records
  the verbatim ask and derives identity from the session id; never call
  `turn_begin` unless a write is refused with `no open turn`.
- **`slopp-reader` is the comprehension subagent.** Delegate broad "how does X
  work / what would Y touch" questions to it so source dumps stay out of the
  main context.
- **Develop against the dev instance.** `slopp dev .` boots this store's
  dev instance on 7357 and re-serves it at every `done`; it is the machine's
  slopp server while it runs, so a plain `claude` works on the in-progress
  slopp. A slopp server runs a snapshot jar with no live-reload
  (`D-no-daemon-live`) and needs a rebuild only when the base must move.
  DEV.md has the procedure.
