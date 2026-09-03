<!-- reference topic `tools` — served whole by `help {topic "tools"}`; the one-page SKILL.md points here. Written against dc66cb7a963c5. -->

## The tool surface: fourteen families, every op by its own name

`tools/list` advertises FOURTEEN tools. Each is a family whose description
is an index — one line per operation: `op {required [optional]} — what it
does`. An operation keeps its own name and is called through its family
with `op`:

```
edit {op "change", prompt "why", impl [{action "add", ns "app.core", source "(defn f [x] x)"}]}
read {op "query_slice", ns "app.core", name "f"}
```

Every name you meet in a refusal, a docstring or this skill is an OP; the
family that holds it is in the table. `help {topic "<op>"}` returns the
op's full card (description + schema). `done` and `commit_point` are their
own tools and take no `op`.

| family | ops |
|---|---|
| `orient` | orient, session_brief |
| `read` | explore, check, query_slice, query_source, query_brief, query_detail, query_search, query_project |
| `depends` | query_flow (the call path between two forms or the reach around one, bodies on the way), query_depends, query_call, query_macroexpand |
| `history` | query_history, query_changes, query_commits, query_git, query_branches, report, file_history |
| `eval` | query_eval, query_observe, query_store |
| `edit` | change, edit_comment, edit_revert, undo, episode_revert — the former write ops (edit_group, edit_subform, edit_delete_form, edit_add_form, edit_replace_form, intent) and query_batch are de-advertised dispatchable aliases: `change {impl […]}` is the group write, `explore {ops […]}` is the batch read |
| `refactor` | rename_sweep, edit_rename, edit_extract, edit_requalify, change_signature, edit_move_forms, module_extract, ns_rename, ns_realias, cleanup |
| `declare` | ns_create, ns_delete, ns_add_require, ns_remove_require, module_dep, module_purity, module_role, module_platform, deps_add, deps_remove, deps_list, deps_pure, js_dep |
| `verify` | test_run, full_check, restart, review_scan, draft_test, screen |
| `build` | build, compile_client, generate_client, ui_serve |
| `store` | store_health, store_doctor, store_compact, config, config_file, query_capabilities, query_rules, query_vocabulary, query_surface, query_rule_telemetry, query_cost |
| `slopp` | git_push, git_clone, git_pull, git_conflicts, git_resolve, import_dir, branch_create, branch_switch, branch_merge, branch_delete, thread_list, thread_drop, merge_from, file_put, file_get, file_list, file_remove, turn_begin, turn_end, help |
| `done` | — |
| `commit_point` | — |

Refusals: `edit needs :op — ops: …`; `unknown op X for edit — ops: …`;
`unknown argument :nom for change — … accepted: …`; `change needs :impl —
required for this op`. A refusal is the index for the thing you got wrong.

Why families: with 96 tools advertised the client deferred every one of
them, and finding a tool cost a search turn each time (19–22 per task in
measurement). Fourteen indexes fit in context; the op names never changed,
so nothing written about them went stale.
