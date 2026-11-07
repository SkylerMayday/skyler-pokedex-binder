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

- **No shared "binder" abstraction.** Adding a nav section is seven hand-wired pieces — see `app/CLAUDE.md` before proposing one.
- **Room schema is versioned** (`pokedex_binder.db`) — any entity change needs a migration; details in `app/CLAUDE.md`.
- **Verifying another stage's build claim requires `--rerun-tasks`** — a bare `UP-TO-DATE` is the audited stage's own cached result, not confirmation. See `~/.claude/rules/lessons/delete-stale-test-results-dir-before-rerun.md`.

## Running it

```powershell
.\gradlew.bat assembleDebug          # build APK
.\gradlew.bat installDebug           # build + install to connected device
.\gradlew.bat testDebugUnitTest      # unit tests
```

- App id: `com.skyler.pokedexbinder`. `local.properties` is present.
- **There is no Unix `gradlew` here, only `gradlew.bat`.** From Bash, `JAVA_HOME='D:\jdk17\jdk-17.0.14+7' ./gradlew.bat <task>` starts Gradle. Otherwise use PowerShell, or a `.ps1` invoked with `powershell.exe -File` (inline `-Command` mangles `$env:` assignments). See `~/.claude/rules/lessons/no-powershell-tool-in-harness.md`.
- **Gradle sometimes dies with `Unable to establish loopback connection` when run by Claude or a subagent** (seen with direct `./gradlew.bat` and targeted runs; `./run_build.bat <task>` from Bash worked throughout sessions 22-25, including in worktrees once `local.properties` is copied in). Not a code problem. If it recurs, ask Skyler to run the same command in his own PowerShell.
- **`PKIX path validation failed` on uncached Gradle artifacts in the sandbox is fixed by `export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` before `./run_build.bat <task>`** (verified 2026-11-06: `lintDebug`, `compileDebugAndroidTestKotlin` and a dependency bump all ran). Without it, those tasks fail on download. `connectedDebugAndroidTest` still needs a pinned emulator (`ANDROID_SERIAL=emulator-5554`), never the phone.
- `JAVA_HOME` must be set. Both of these exist on this machine: `D:\jdk17\jdk-17.0.14+7` and `C:\Program Files\Android\Android Studio\jbr`.

`run_build.bat <task>` wraps `gradlew.bat` with JDK 17 (`D:\jdk17\jdk-17.0.14+7`) and resolves its own directory (`%~dp0`); `build_debug.bat` runs `installDebug` through it.

---

Global rules: `~/.claude/CLAUDE.md`. Coding mandates in its section 9 apply here — `debugging` before any bug fix, `planning` before anything new, `dev-team-pipeline` for non-trivial changes.
