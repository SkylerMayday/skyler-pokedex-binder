# Handoff — 2026-07-16 (session 3, pre-migration DB backup safety net)

## Goals (this session)

Fix the highest-priority item in `gaps.md`: `fallbackToDestructiveMigrationOnDowngrade()` (and,
discovered this session, the unconditional `fallbackToDestructiveMigration()` upgrade-side
variant too) is a proven data-loss landmine — it already wiped Skyler's entire local database
once for real (v8→v7 downgrade, no warning). Build a safety net: automatically back up the DB
file before any destructive Room migration fallback actually fires.

Skyler also triaged the rest of the prior session's `gaps.md` register in this session: accepted
several items as-is (no action wanted), one was reclassified as already effectively resolved
through his own real-device usage. See Changes Made #2 below.

## Current State

**Not yet committed.** All changes below are in the working tree, unpushed. Full
`dev-team-pipeline` run completed (Planner → Coder → Tester → Reviewer), **verdict: SHIP**.

- Full unit suite: **182/182 passing, 0 failures** (up from 168 at last handoff — 14 new tests,
  no regressions). Verified independently three times across the pipeline (Coder, Tester,
  Reviewer each ran the suite fresh rather than trusting the prior stage's claim).
- `compileDebugAndroidTestKotlin` succeeds module-wide, including a new instrumented test.
- **Database schema is unchanged at v9** — this feature adds no migration, no schema bump. Purely
  additive safety-net code sitting in front of the existing `Room.databaseBuilder(...).build()`
  call.

**Database is at schema v9** via a real forward `MIGRATION_8_9` (not destructive) — do not revert
below v9 without reading `project-overview.md`'s Gotchas section first (a prior v8→v7 downgrade
wiped the user's device DB; this session's feature mitigates but does not eliminate that risk for
any *future* downgrade).

## Active Files (this session)

- `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/SqliteVersionReader.kt` — new.
  Reads on-disk `PRAGMA user_version` directly via `SQLiteDatabase.openDatabase(..., OPEN_READONLY)`,
  bypassing Room entirely so this check runs before Room ever opens the file.
- `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/MigrationPathResolver.kt` — new.
  BFS over `Migration.(startVersion, endVersion)` edges; direction-agnostic, honors multi-version
  jump migrations, doesn't hardcode a "+1 per step" assumption.
- `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/DatabaseBackupManager.kt` — new.
  Orchestrator: `backupIfDestructiveMigrationImminent(context, dbFileName, targetVersion, migrations)`.
  No-op fast path when versions match or a clean migration path exists (one cheap read-only PRAGMA
  query). Otherwise copies `.db` + `-wal`/`-shm` sidecars into `context.filesDir/db_backups/`,
  rotates to keep the newest 5 sets. All failures logged and swallowed — never blocks startup.
- `app/src/main/java/com/skyler/pokedexbinder/data/local/PokedexDatabase.kt` — added
  `SCHEMA_VERSION` const + `ALL_MIGRATIONS` array as the single source of truth (previously the
  `@Database(version = 9)` annotation and any migration list were separately hand-maintained).
- `app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt` — `provideDatabase` now calls
  the backup check synchronously before `.build()`; confirmed both `fallbackToDestructiveMigration()`
  and `fallbackToDestructiveMigrationOnDowngrade()` are covered by the same guard.
- `app/src/test/java/com/skyler/pokedexbinder/data/local/backup/` — new: `MigrationPathResolverTest.kt`
  (5 tests), `DatabaseBackupManagerTest.kt` (9 tests, fake version reader + real JVM temp dirs).
- `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/backup/DatabaseBackupManagerInstrumentedTest.kt`
  — new, 2 tests. Compiles; not executed (no emulator in this sandbox).
- `gaps.md` — updated: backup item marked mitigated with implementation detail + one known
  residual gap (see Next Steps #1); several other items marked accepted/no-action per Skyler's
  triage this session; two migration-test items reclassified per Skyler's reasoning.

## Changes Made

1. Ran the full `dev-team-pipeline` on the backup feature. Planner grounded in the actual codebase
   (found `DatabaseModule.kt` already had *both* destructive-fallback variants enabled, correcting
   a `gaps.md` entry that only named the downgrade one) and chose a pre-`.build()`
   `PRAGMA user_version` check over a `SupportSQLiteOpenHelper.Callback` wrapper (the callback
   fires too late — after Room has already opened/locked the connection).
2. **Skyler triaged the rest of the prior `gaps.md` register in this session** (2026-07-16), all
   reflected in the updated file:
   - Accepted, no action: TCGCSV's per-search full-group-scan cost ("it's fine"); no manual
     card-entry fallback for Connecting Art/Personal Collection ("fine to have... currently");
     website not yet consuming `language`/`remarks`/`isLocked` (already queued in a separate
     `skylermayday-site` session).
   - `Migration7to8Test.kt` missing, and `Migration5to6Test`/`Migration6to7Test` never run as
     standalone JUnit executions: accepted as low priority — "we will most likely not go back to
     [schema version] 7", so the downgrade path these would most protect against is unlikely to be
     exercised.
   - `Migration8to9Test.kt` "never run on a real device": reclassified as effectively verified —
     Skyler confirmed every test he actually performs is on his real phone, meaning the v8→v9
     migration itself has already run for real through normal app usage since it shipped, even
     though the standalone instrumented JUnit test file was never formally executed in CI/sandbox.
3. **Coder subagent hit the account session limit mid-task**, before writing `.pipeline/changes.md`
   or the instrumented test file. Per the `check-subagent-artifacts-before-rerunning` lesson,
   checked its on-disk output before resuming rather than blind-restarting: all main-source files
   (`SqliteVersionReader`, `MigrationPathResolver`, `DatabaseBackupManager`, `DatabaseModule`
   wiring, `PokedexDatabase` constants) and both JVM test files were already complete and correct
   on disk. Finished the remaining work directly in the orchestrating session rather than
   re-spawning a fresh Coder: fixed one test compile error (`arrayOf(jumpMigration)` on an
   anonymous `Migration` subclass inferred the wrong array type — one-line fix), wrote the missing
   instrumented test following the `Migration8to9Test.kt` convention, ran full verification
   (182/182 unit tests, `compileDebugAndroidTestKotlin` clean), and wrote `.pipeline/changes.md`.
4. Tester stage independently re-ran everything rather than trusting `changes.md`'s numbers, and
   went further: **ran a real mutation test** — temporarily removed the `DatabaseBackupManager`
   call from `DatabaseModule.provideDatabase`, confirmed the full suite still passes green with it
   missing, then restored the file and verified byte-identical via `git diff --stat`. This exposed
   a real gap: no test proves the DI wiring itself is in place, only that `DatabaseBackupManager`
   works correctly in isolation. Documented as a known, non-blocking gap (see Next Steps #1).
5. Reviewer independently re-verified test counts, traced the `DatabaseModule.provideDatabase`
   ordering by hand (confirmed the backup call is a synchronous statement fully preceding
   `.build()` — no path exists for Room to wipe data before the check runs), and confirmed the BFS
   correctness against the actual (linear, no-branch) migration chain. **Verdict: SHIP.**

## Failed Attempts

- Stage 2 (Coder) subagent hit the account session limit mid-task and was cut off before writing
  its output file or the instrumented test. Not a wasted run — checked artifacts on disk first
  (per the standing lesson), found the core implementation and JVM tests were already complete and
  correct, and finished the remainder directly rather than re-running the whole stage.

## Next Steps

1. **Known residual gap, not yet closed:** no test exercises `DatabaseModule.provideDatabase`'s
   actual wiring — a future accidental removal of the `DatabaseBackupManager` call would ship with
   a fully green test suite. Proper fix needs an instrumented `@HiltAndroidTest`, blocked by the
   same no-emulator constraint as everything else. Consistent with this project's existing pattern
   (no Hilt module here has ever had a wiring test), so treated as acceptable for now, not urgent.
2. **This session's work is uncommitted.** Confirm with Skyler before committing/pushing (per this
   project's usual flow — not a live-deploy-on-push repo, so no auto-push expectation here).
3. **Instrumented test still unexecuted on a real device** — `DatabaseBackupManagerInstrumentedTest.kt`
   compiles but has never run against real Android SQLite. Same standing no-emulator constraint as
   every other migration/instrumented test in this project; worth a real on-device pass whenever
   Skyler next has the app on a device/emulator he's willing to test destructive-migration
   scenarios against.
4. Carried over, still not started: custom domain/og:image for the maintenance-mode GitHub Pages
   viewer; the recent-pulls/changelog-driven stream feature idea (mostly website-side); "Braincheck"
   (Skyler's term, still undefined).

## Environment Notes (still true)

- `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`; Gradle wrapper is `gradlew.bat` (Windows-only — no Unix
  `gradlew` in this repo). Run it via the **PowerShell tool**, not Bash — Bash's `./gradlew` fails
  outright since the file doesn't exist; PowerShell needs `$env:JAVA_HOME`, `$env:TEMP`,
  `$env:TMP = "C:\Windows\Temp"` set first.
- No Android emulator in this sandbox — all builds/tests this session were unit-level +
  compile-level only, never on-device.
- Session JSONL for this project: `C:\Users\SkylerMayday\.claude\projects\D--Claude-Projects-PokedexBinderV2\`.
