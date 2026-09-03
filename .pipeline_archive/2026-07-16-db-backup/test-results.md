# Stage 3 (Tester) — Test Results

**Status: PASS (with a flagged coverage gap) — proceed to Reviewer (Stage 5).**

No test failures found. All claims in `changes.md` were independently re-verified with fresh
command output (not re-trusted from the document). One real regression-coverage gap was found and
confirmed empirically via a mutation test (see §5) — it does not block PASS per the spec's release
checklist (which only gates on tests passing + both compiles succeeding), but it must be surfaced
to Reviewer/Skyler since it bears directly on whether this feature actually protects against a
reintroduction of the original bug.

---

## 1. Environment confirmation (Phase 2)

- `JAVA_HOME=D:\jdk17\jdk-17.0.14+7` confirmed working: `& "D:\jdk17\jdk-17.0.14+7\bin\java.exe" -version` →
  `openjdk version "17.0.14" 2025-01-21, OpenJDK Runtime Environment Temurin-17.0.14+7`.
- `$env:TEMP`/`$env:TMP` set to `C:\Windows\Temp` for the Gradle daemon.
- All Gradle invocations run via `.\gradlew.bat` through the **PowerShell** tool (not Bash), from
  `D:\Claude Projects\PokedexBinderV2`, per the standing project constraint.

## 2. Fresh full-suite run (Phase 6 evidence)

Command:
```
.\gradlew.bat clean testDebugUnitTest --console=plain
```
Result: **BUILD SUCCESSFUL in 1m 30s** (32 actionable tasks: 32 executed — genuinely clean, not
cached; `clean` was run first specifically so nothing could be UP-TO-DATE/stale).

Aggregated the real JUnit XML output (not paraphrased) from
`app\build\test-results\testDebugUnitTest\*.xml` via a PowerShell XML parse across all 23 result
files:

```
Files: 23 Tests: 182 Failures: 0 Errors: 0 Skipped: 0
```

Matches `changes.md`'s claimed 182/182 (up from 168) — confirmed independently, not just read from
the document.

### Backup-package tests specifically (14 total, matches claim exactly)

`TEST-com.skyler.pokedexbinder.data.local.backup.MigrationPathResolverTest.xml` — tests=5 failures=0 errors=0:
- `a multi-version jump migration is honored by the graph search, not just adjacent steps` — 0.001s
- `contiguous forward chain from v4 to v9 through the 5 real migrations resolves true` — 0.001s
- `missing a single link in the chain resolves false` — 0.0s
- `same version resolves true trivially` — 0.0s
- `downgrade direction with only forward migrations declared resolves false` — 0.0s

`TEST-com.skyler.pokedexbinder.data.local.backup.DatabaseBackupManagerTest.xml` — tests=9 failures=0 errors=0:
- `first launch - versionReader returns null - no directory created, no exception` — 1.811s
- `versions match - no backup dir created at all` — 0.037s
- `IOException during copy - method returns normally, never blocks the caller` — 0.065s
- `full incremental migration path exists - no backup` — 0.037s
- `wal and shm sidecars absent - db copied alone, no exception` — 0.035s
- `rotation keeps only the newest maxBackups sets, deleting older sets and their sidecars` — 0.057s
- `backup dir doesn't exist yet - created on demand` — 0.035s
- `wal and shm sidecars present - both copied alongside db` — 0.043s
- `versions differ with no path - db copied with correct filename pattern` — 0.043s

## 3. `compileDebugAndroidTestKotlin` re-run (Phase 6 evidence, item 6 of the task)

Command:
```
.\gradlew.bat compileDebugAndroidTestKotlin --console=plain
```
Result: **BUILD SUCCESSFUL in 22s** (31 actionable tasks: 10 executed, 21 up-to-date). Confirms
`DatabaseBackupManagerInstrumentedTest.kt` compiles cleanly against the module's existing
`androidTest` source set (no repeat of the prior `PokedexDatabaseTest.kt` turbine/`dexOrder`
compile-break class of issue from 2026-07-15).

## 4. Code review of test files against spec edge cases (Phase 1/3)

Read in full: `SqliteVersionReader.kt`, `MigrationPathResolver.kt`, `DatabaseBackupManager.kt`,
`DatabaseModule.kt`, `PokedexDatabase.kt`, `MigrationPathResolverTest.kt`,
`DatabaseBackupManagerTest.kt`, `DatabaseBackupManagerInstrumentedTest.kt`, and cross-checked
against `specs.md` §5's edge-case table:

| Spec edge case | Covered by | Verdict |
|---|---|---|
| First launch, no DB file | `DatabaseBackupManagerTest`: "first launch - versionReader returns null - no directory created, no exception" | Covered |
| `db_backups/` doesn't exist yet | `DatabaseBackupManagerTest`: "backup dir doesn't exist yet - created on demand" | Covered |
| On-disk version == target (hot path) | `DatabaseBackupManagerTest`: "versions match - no backup dir created at all"; instrumented: `noBackupCreatedWhenOnDiskVersionAlreadyMatchesTarget` | Covered (both layers) |
| Full incremental path exists | `DatabaseBackupManagerTest`: "full incremental migration path exists - no backup"; `MigrationPathResolverTest`: contiguous-chain test | Covered |
| No migration path (destructive) | `DatabaseBackupManagerTest`: "versions differ with no path..."; instrumented: `backupCopiesRealSqliteFileAndWalSidecarWhenNoMigrationPathExists` | Covered (both layers) |
| WAL/SHM present | `DatabaseBackupManagerTest`: "wal and shm sidecars present..."; instrumented test also seeds a real `-wal` file and asserts content match | Covered |
| WAL/SHM absent | `DatabaseBackupManagerTest`: "wal and shm sidecars absent - db copied alone, no exception" | Covered |
| Rotation (keep newest 5) | `DatabaseBackupManagerTest`: seeds 6 + triggers a 7th, asserts exactly 5 survive, sidecars of deleted ones gone too | Covered, and slightly exceeds the spec's "seed 7" suggestion (uses 6+1=7 effectively) — fine, same assertion |
| IO failure during backup never blocks | `DatabaseBackupManagerTest`: "IOException during copy - method returns normally..." (source is a directory, forces a real `FileInputStream` failure) | Covered |
| Multi-version jump migration honored | `MigrationPathResolverTest`: anonymous `Migration(5,9)` jump test | Covered |
| Downgrade direction | `MigrationPathResolverTest`: "downgrade direction with only forward migrations declared resolves false" | Covered |
| **`versionReader.readVersion()` throws / corrupt / locked file** | **Not tested anywhere** — the catch block lives inside `FrameworkSqliteVersionReader.readVersion` itself (`SqliteVersionReader.kt` lines 14-21), which requires a real Android `SQLiteDatabase` and is therefore untestable from the JVM unit test layer (by design, per spec's own architecture rationale). No instrumented test exercises a corrupt/locked file either — both instrumented tests (`DatabaseBackupManagerInstrumentedTest.kt`) only cover a well-formed seeded database. | **Gap — see §6.1** |

Everything else in the spec's edge-case table is explicitly covered. The test scenarios in
`DatabaseBackupManagerTest.kt` follow the factory/fake pattern correctly (`FakeVersionReader`,
`mockk`-based `Context`), one behavior per `it`, and assert on real `java.io.File` state rather than
mock-call verification — this is testing actual behavior, not mock behavior.

## 5. Regression-safety check (task item 5) — empirical mutation test

The task specifically asked: **if someone accidentally removed the `DatabaseBackupManager` call
from `DatabaseModule.provideDatabase`, would any test in this suite fail?**

Rather than reasoning about this abstractly, I ran the actual experiment:

1. Backed up `DatabaseModule.kt` to the scratchpad directory.
2. Edited `DatabaseModule.kt` to delete the
   `DatabaseBackupManager().backupIfDestructiveMigrationImminent(...)` call entirely (replaced with
   a comment), leaving everything else (including `.addMigrations(*PokedexDatabase.ALL_MIGRATIONS)`
   and both destructive-fallback calls) untouched — i.e. exactly the "the wiring got silently
   dropped" scenario the task described.
3. Ran `.\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin --console=plain` fresh.
4. Result: **BUILD SUCCESSFUL** — `testDebugUnitTest` executed (not cached — Kotlin recompiled,
   `kspDebugKotlin`/`compileDebugKotlin` re-ran) and `compileDebugAndroidTestKotlin` also succeeded.
   No test failed, no compile error.
5. Restored `DatabaseModule.kt` from the backup, verified byte-identical via `diff`, then re-ran
   `testDebugUnitTest` once more to confirm the restored tree is clean — **BUILD SUCCESSFUL**.

**Confirmed: this is a real, empirically-verified coverage gap.** `grep`-ing the entire `test/` and
`androidTest/` source trees for `DatabaseModule`/`provideDatabase` returns zero matches — nothing
anywhere in the suite exercises `DatabaseModule.kt`'s DI wiring itself. All 14 new tests correctly
prove `DatabaseBackupManager`/`MigrationPathResolver`/`SqliteVersionReader` are individually
correct, but nothing proves `DatabaseBackupManager` is actually *called* from the real
`provideDatabase` production code path. A future accidental deletion of that one call (the exact
"insurance quietly stops being applied" failure mode this whole feature exists to prevent) would
ship silently with a fully green test suite.

### 5.1 Why this isn't a blocking FAIL

- `specs.md`'s own §8 Release Readiness checklist only gates on unit tests passing + both compiles
  succeeding — it does not list a DI-wiring regression test as a requirement, and §4 explicitly
  chose "no Hilt `@Provides` binding" for `DatabaseBackupManager` (plain stateless helper,
  constructed inline) specifically to avoid a DI graph node — which is also *why* the wiring itself
  ended up untestable without either Robolectric or an instrumented Hilt test.
- This gap is pre-existing in kind, not new: `DatabaseModule`'s other `@Provides` functions (DAOs)
  are likewise never tested for correct wiring either — this project has no established pattern for
  testing Hilt module wiring at all, so this isn't a regression introduced by this task, it's an
  extension of an existing gap into new, higher-stakes territory (this particular wiring exists
  specifically to prevent data loss, unlike the DAO providers).

### 5.2 Recommended follow-up (not performed — out of scope for Tester stage)

A cheap, low-effort closer exists and should be considered by Reviewer/Skyler:
- **Simplest fix**: an instrumented Hilt test (`@HiltAndroidTest`) that seeds a stale-version DB
  file at the real app database path, triggers `PokedexDatabase` injection through the real Hilt
  graph, and asserts a backup file appears in `filesDir/db_backups/` — this would exercise the
  *actual* `provideDatabase` function, not just `DatabaseBackupManager` in isolation. Same
  standing constraint applies (no emulator in this sandbox to run it), but it would at least make
  the wiring itself provably covered once a device is available.
- Alternatively, a plain JVm test cannot cover this (Hilt module functions + Room + real Context are
  all Android-framework-bound), so this gap can only be closed at the instrumented-test layer.

## 6. Files read in full (per project's "whole files, always" standard)

- `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/SqliteVersionReader.kt`
- `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/MigrationPathResolver.kt`
- `app/src/main/java/com/skyler/pokedexbinder/data/local/backup/DatabaseBackupManager.kt`
- `app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt`
- `app/src/main/java/com/skyler/pokedexbinder/data/local/PokedexDatabase.kt`
- `app/src/test/java/com/skyler/pokedexbinder/data/local/backup/MigrationPathResolverTest.kt`
- `app/src/test/java/com/skyler/pokedexbinder/data/local/backup/DatabaseBackupManagerTest.kt`
- `app/src/androidTest/java/com/skyler/pokedexbinder/data/local/backup/DatabaseBackupManagerInstrumentedTest.kt`
- `.pipeline/specs.md`, `.pipeline/changes.md`, `gaps.md`

No new test code was added this stage — the existing 14 tests already cover the spec's stated edge
cases at the unit level (see §4 table); the one real gap found (§5) requires a new instrumented
Hilt test to close, which is a design/architecture decision better suited to Reviewer sign-off
given it touches whether `DatabaseBackupManager` should get a `@Provides` binding after all (a
call explicitly deferred as YAGNI in `specs.md` §4, but this finding is exactly the scenario that
YAGNI call trades off against).

## 7. Summary for next stage

**PASS.** 182/182 unit tests green (fresh, clean-build evidence), 14/14 new backup-package tests
individually confirmed by name, `compileDebugAndroidTestKotlin` succeeds fresh. No test code
changes needed at this stage. One coverage gap empirically confirmed and documented for Reviewer:
**no test currently exercises `DatabaseModule.provideDatabase`'s wiring itself**, so a silent
removal of the backup call would not be caught by this suite. Recommend Reviewer either (a) accept
this as a documented, pre-existing-in-kind gap (add to `gaps.md`), or (b) request an instrumented
Hilt wiring test as a fast-follow before considering this feature's regression protection complete.
