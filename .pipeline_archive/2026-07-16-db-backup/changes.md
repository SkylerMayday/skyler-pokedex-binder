# Stage 2 (Coder) — Changes

**Note on provenance:** the Coder subagent implemented all main-source and JVM-unit-test files
before hitting a session limit mid-task (before writing this file or the androidTest file). The
orchestrating session verified the Coder's on-disk work, fixed one test compile error, wrote the
missing instrumented test, and ran full verification. Documented here as a single coherent record.

## Files changed

### New: `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/`

- **`SqliteVersionReader.kt`** — `SqliteVersionReader` interface (`readVersion(dbFile: File): Int?`)
  + `FrameworkSqliteVersionReader` impl using `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY).use { it.version }`,
  returns `null` if the file doesn't exist or can't be opened (first launch / corrupt / locked).
- **`MigrationPathResolver.kt`** — `MigrationPathResolver.hasPath(migrations, from, to): Boolean`,
  BFS over each `Migration`'s `(startVersion, endVersion)` edge. Not a hardcoded "+1 per step"
  assumption — honors multi-version jump migrations and is direction-agnostic (works for both
  upgrade and downgrade gaps; today no downgrade `Migration` is declared, so any on-disk version
  above target always resolves to "no path", matching the documented Gotcha).
- **`DatabaseBackupManager.kt`** — `DatabaseBackupManager(versionReader, maxBackups = 5)` with
  `backupIfDestructiveMigrationImminent(context, dbFileName, targetVersion, migrations)`:
  reads on-disk version → returns early if null/matching/path-exists → otherwise copies the `.db`
  file + `-wal`/`-shm` sidecars (if present) into `context.filesDir/db_backups/` under a
  `<name>_v<from>to<to>_<yyyyMMdd_HHmmss>` base name, then rotates down to `maxBackups` by
  `lastModified`. Entire method wrapped in try/catch — any failure is logged (`Log.e`) and
  swallowed; a failed backup never blocks the caller or the migration it's insuring against.

### Modified

- **`data/local/PokedexDatabase.kt`** — added `SCHEMA_VERSION = 9` const and `ALL_MIGRATIONS`
  array (`MIGRATION_4_5` through `MIGRATION_8_9`); `@Database(version = ...)` now points at
  `PokedexDatabase.SCHEMA_VERSION` instead of a separate hardcoded `9`. Kills the drift risk
  between the annotation and any hand-maintained migration list at other call sites.
- **`di/DatabaseModule.kt`** — `provideDatabase` now calls
  `DatabaseBackupManager().backupIfDestructiveMigrationImminent(...)` before
  `Room.databaseBuilder(...).build()`, and `.addMigrations(*PokedexDatabase.ALL_MIGRATIONS)`
  replaces whatever migration list was hand-maintained there before (single source of truth).
  Both `.fallbackToDestructiveMigration()` (upgrade) and `.fallbackToDestructiveMigrationOnDowngrade()`
  remain in place unchanged — confirmed both were already present (gaps.md previously only named
  the downgrade variant; this session's Planner stage found the upgrade-side one too). The new
  backup check now guards both directions symmetrically since it only checks "is there a path",
  not "which direction".

### New tests

- **`test/.../data/local/backup/MigrationPathResolverTest.kt`** (JVM, 5 tests) — contiguous chain
  resolves true, a broken link resolves false, same-version trivially true, downgrade-with-only-
  forward-migrations resolves false, multi-version jump migration honored by the graph search.
- **`test/.../data/local/backup/DatabaseBackupManagerTest.kt`** (JVM, 9 tests) — fake
  `SqliteVersionReader` + real JVM temp dirs standing in for `context.filesDir`, `android.util.Log`
  stubbed via `mockkStatic`. Covers: matching versions → no backup dir created at all; full
  incremental path exists → no backup; no path → correct filename pattern + content copied;
  WAL/SHM sidecars copied when present / absent without exception; rotation keeps newest 5 of 7
  seeded sets and removes sidecars of the deleted ones; backup dir created on demand; IOException
  during copy (source is a directory, not a file) doesn't propagate; first-launch/null-version
  read creates nothing.
- **`androidTest/.../data/local/backup/DatabaseBackupManagerInstrumentedTest.kt`** (2 tests,
  written directly in this session after the Coder subagent was cut off) — real
  `ApplicationProvider.getApplicationContext()`, a real bare SQLite file at the app's actual
  `getDatabasePath()` location with `PRAGMA user_version = 3` and real `-wal` sidecar content
  (mirroring the actual incident: stale on-disk version, no forward migration path). Asserts the
  backup file is independently openable via `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` and
  contains the seeded row, the WAL sidecar content matches, and the filename pattern is correct.
  Second test confirms no backup dir is created when the on-disk version already matches target
  (the common-path no-op). Follows the exact convention of `Migration8to9Test.kt` — compiles, not
  executed live (no emulator in this sandbox).

## Simplification / review pass (Phase 5)

Reviewed during the orchestrating session's verification pass (the Coder subagent was cut off
before reaching this phase itself):

- No duplicate logic found — `MigrationPathResolver` and `SqliteVersionReader` are each used
  exactly once (by `DatabaseBackupManager`) plus independently unit-tested.
- `DatabaseBackupManager`'s constructor-injected `versionReader`/`maxBackups` (defaulted for
  production use, overridable in tests) is the minimal seam needed for the JVM unit tests to avoid
  Robolectric — not over-abstracted beyond that.
- One correctness fix applied during this pass: `MigrationPathResolverTest.kt` line 42 had
  `arrayOf(jumpMigration)` where `jumpMigration` is an anonymous `Migration` subclass — Kotlin
  inferred `Array<[anonymous]>` instead of `Array<Migration>`, failing to compile against
  `hasPath(migrations: Array<Migration>, ...)`. Fixed to `arrayOf<Migration>(jumpMigration)`
  (explicit type argument). This is a test-only type-inference fix, not a logic change.

## Verification (Phase 6) — fresh evidence, this session

Run from `D:\Claude Projects\PokedexBinderV2` via `.\gradlew.bat` (PowerShell; `JAVA_HOME=D:\jdk17\jdk-17.0.14+7`):

1. `compileDebugKotlin compileDebugUnitTestKotlin` — **initially FAILED** on the
   `MigrationPathResolverTest.kt` type-inference error above. Fixed, re-ran:
2. `testDebugUnitTest --tests "com.skyler.pokedexbinder.data.local.backup.*"` — **BUILD SUCCESSFUL**,
   all 14 new backup-package tests (5 resolver + 9 manager) passed.
3. `testDebugUnitTest` (full suite) — **BUILD SUCCESSFUL**. Parsed `app/build/test-results/testDebugUnitTest/*.xml`:
   **182 total tests, 0 failures** across 23 result files (up from the 168 recorded in the last
   handoff — +14 for this feature, no regressions).
4. `compileDebugAndroidTestKotlin` (full androidTest source set, including the new instrumented
   test) — **BUILD SUCCESSFUL**.

Not run (standing sandbox constraint, same as every prior migration test in this project): the
instrumented test itself was not executed on a real device/emulator — none available. Compile
success only.

## Status

DONE. Ready for Stage 3 (Tester).
