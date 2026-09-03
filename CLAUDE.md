# PokedexBinderV2 — Claude Code Instructions

Android app (Kotlin / Compose / Hilt / Room) for tracking which Pokémon TCG cards Skyler physically owns, slot by slot, with a public web-shareable snapshot. Personal project — no users beyond himself, no metrics.

## Session start

Read these three, then **report what needs to be done** before touching anything:

| File | Holds |
|---|---|
| [project-overview.md](project-overview.md) | What this is, how it's architected, why the big decisions were made |
| [handoff.md](handoff.md) | Where the last session left off, next steps |
| [gaps.md](gaps.md) | Standing weakness register — debt, missing tests, fragile edges |

Update all three at session end / `wrapcon`. Spec: `~/.claude/rules/project-files.md`.

## Sharp edges

- **There is no shared "binder" abstraction.** Every nav-drawer section is its own independent entity and table set. Adding a section means seven hand-wired pieces: entity, DAO, repository, ViewModel, Screen, `Screen` route, and drawer item in `ui/navigation/AppNavigation.kt`. Budget for that before proposing one.
- **Room schema is versioned** (`pokedex_binder.db`). Any entity change needs a migration — check the current version in `project-overview.md` rather than assuming.
- Gradle build is Windows-only. From a Bash-only context, use a real `.ps1` with `powershell.exe -File`; inline `-Command` mangles `$env:` assignments. See `~/.claude/rules/lessons/no-powershell-tool-in-harness.md`.
- **Verifying another stage's build claim requires `--rerun-tasks`** — a bare `UP-TO-DATE` is the audited stage's own cached result, not confirmation. See `~/.claude/rules/lessons/delete-stale-test-results-dir-before-rerun.md`.

## Running it

```powershell
.\gradlew.bat assembleDebug          # build APK
.\gradlew.bat installDebug           # build + install to connected device
.\gradlew.bat testDebugUnitTest      # unit tests
```

- App id: `com.skyler.pokedexbinder`. `local.properties` is present.
- **There is no Unix `gradlew` here, only `gradlew.bat`** — Bash cannot invoke the wrapper directly. Use PowerShell, or a `.ps1` invoked with `powershell.exe -File` from a Bash-only context (inline `-Command` mangles `$env:` assignments).
- `JAVA_HOME` must be set. Both of these exist on this machine: `D:\jdk17\jdk-17.0.14+7` and `C:\Program Files\Android\Android Studio\jbr`.

### ⚠️ `build_debug.bat` and `run_build.bat` are broken

Both reference `D:\Claude Projects\PokedexBinder` — **without the `V2`** — a directory that does not exist. They are stale from before the rename and cannot work. Call `gradlew.bat` directly instead, or fix the paths. They also disagree with each other about `JAVA_HOME`.

---

Global rules: `~/.claude/CLAUDE.md`. Coding mandates in its section 9 apply here — `debugging` before any bug fix, `planning` before anything new, `dev-team-pipeline` for non-trivial changes.
