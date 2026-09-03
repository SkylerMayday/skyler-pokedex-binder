# app/ — Claude Code Instructions

Android module. Kotlin + Compose + Hilt + Room.

## Adding a nav section is 7 hand-wired pieces

There is **no shared "binder" abstraction**. Every nav-drawer section is its own independent entity and table set, wired by hand. `ui/` currently holds 12 feature packages (`mainbinder`, `unown`, `quickscan`, `personalcollection`, `secondarybinder`, `scanner`, `slotdetail`, `manualsearch`, `connectingart`, `publish`, `restore`, `settings`), and `ui/navigation/AppNavigation.kt` wires 12 composable routes by hand.

A new section means all of:

1. Entity — `data/local/`
2. DAO — `data/local/`
3. Repository — `repository/`
4. ViewModel — `ui/<feature>/`
5. Screen composable — `ui/<feature>/`
6. `Screen` route entry
7. Drawer item + `composable(...)` block in `ui/navigation/AppNavigation.kt`

Budget for all seven before proposing a new section. Missing #7 is the usual silent failure — the code compiles and the screen is unreachable.

## Room

`data/local/PokedexDatabase.kt` declares the schema. **Any entity change needs a migration** — check the current `version` in that file rather than assuming; do not bump it without writing the migration.

## Build

Windows-only Gradle. From a Bash-only context (pipeline subagents), write a real `.ps1` and invoke `powershell.exe -NoProfile -ExecutionPolicy Bypass -File <script>.ps1`. **Inline `-Command` mangles `$env:` assignments** because Bash expands `$env` first. See `~/.claude/rules/lessons/no-powershell-tool-in-harness.md`.

Verifying someone else's build claim requires `--rerun-tasks` — a bare `UP-TO-DATE` is their cached result, not confirmation. See `~/.claude/rules/lessons/delete-stale-test-results-dir-before-rerun.md`.
