# Spec: Pre-Migration Automatic Local Database Backup

**Status:** Proposed
**Date:** 2026-07-16
**Author (stage):** Planner (Stage 1 of dev-team-pipeline)
**Feeds:** Coder (Stage 2) → Tester (Stage 3) → Debugger (Stage 4) → Reviewer (Stage 5)

---

## 1. Problem

`DatabaseModule.kt` (`app/src/main/java/com/skyler/pokedexbinder/di/DatabaseModule.kt`, lines 20-30)
configures Room with:

```kotlin
Room.databaseBuilder(context, PokedexDatabase::class.java, "pokedex_binder.db")
    .addMigrations(
        PokedexDatabase.MIGRATION_4_5,
        PokedexDatabase.MIGRATION_5_6,
        PokedexDatabase.MIGRATION_6_7,
        PokedexDatabase.MIGRATION_7_8,
        PokedexDatabase.MIGRATION_8_9
    )
    .fallbackToDestructiveMigration()
    .fallbackToDestructiveMigrationOnDowngrade()
    .build()
```

**Confirmed finding (corrects `gaps.md`'s framing):** BOTH destructive fallbacks are enabled, not
just the downgrade one. `fallbackToDestructiveMigration()` (no version arguments) means *any*
upgrade for which no unbroken migration chain exists ALSO destructively wipes and recreates every
table — this is symmetric risk with the downgrade case, not a downgrade-only problem. Any future
schema bump that accidentally skips a version, or any code that reverts `version = 9` to a lower
number (already proven to happen — see `project-overview.md` Gotchas), silently wipes
`pokedex_binder.db` with no confirmation dialog. This already happened once for real: a v8→v7
downgrade wiped Skyler's entire Pokédex/Card History/Connecting Art/Personal Collection data.

**Requirement:** before Room's destructive fallback actually executes (drops + recreates tables),
copy the current on-disk database file — plus its `-wal`/`-shm` sidecars, since Room defaults to
WAL journal mode — to a timestamped, rotated backup location in app-internal storage. Safety net
only; no restore UI.

---

## 2. Grounding: how Room/SQLite actually behaves here (not assumption)

Investigated two viable mechanisms before choosing one. Both are real, documented patterns —
picking between them is a genuine architectural decision, not a guess.

### Mechanism A — `SupportSQLiteOpenHelper.Callback` decorator via `openHelperFactory()`

Room's `RoomDatabase.Builder.openHelperFactory()` lets you substitute a custom
`SupportSQLiteOpenHelper.Factory`. A decorating factory can wrap Room's real callback in a
`SupportSQLiteOpenHelper.Callback` subclass that overrides `onUpgrade()`, `onDowngrade()`, and
`onCorruption()`, runs backup logic, then delegates to the original callback. This is a documented
community pattern (Damian Terlecki, "How to listen for database upgrade with Room",
blog.termian.dev/posts/room-on-upgrade/; backup-specific variant at
gist.github.com/t3rmian/8ffe844882d4c009abd730cb98f75dac). Confirmed: these hooks fire **before**
the DROP/CREATE cycle, and the on-disk file is still readable at that point.

**Rejected for this project** because:
- `onUpgrade`/`onDowngrade` fire on a connection Android's `SQLiteOpenHelper` already has open,
  **inside an active transaction** (framework contract: begin → onUpgrade/onDowngrade → setVersion
  → commit). A raw file-level copy of the `.db`/`-wal`/`-shm` triplet taken mid-transaction on a
  live, locked connection is not guaranteed byte-consistent — the safer construct in that pattern
  is exporting *rows* via SQL inside the callback (which is what the cited gist actually does: it
  runs `SELECT` queries and re-inserts into a second Room DB, it does not raw-copy the file).
- Requires wrapping Room's `openHelperFactory()`, which changes what `.build()` returns wraps and
  adds a second layer other future migration debugging has to reason about.

### Mechanism B — pre-`.build()` raw version check (chosen)

Before calling `Room.databaseBuilder(...).build()` at all:
1. Resolve the DB file via `context.getDatabasePath("pokedex_binder.db")`. If it doesn't exist
   (first launch), skip everything below — no version to compare, nothing to back up.
2. Open a **separate, short-lived, read-only** connection with
   `android.database.sqlite.SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)`
   and read `.version` (this reads SQLite's `PRAGMA user_version`, which Room itself uses to store
   schema version — confirmed via Android's `SQLiteDatabase`/Room migration docs,
   developer.android.com/training/data-storage/room/migrating-db-versions). Close immediately.
   This is safe pre-Room-connection: `user_version` lives in a fixed offset in the SQLite file
   header, so reading it never requires WAL-frame replay or a checkpoint, and no other connection
   is open yet at this point in the app's lifecycle (single-process app, no other component has
   touched this file this launch).
3. Compare the on-disk version against the target schema version (`PokedexDatabase.SCHEMA_VERSION`,
   new constant — see Task 1). Determine whether Room's registered `Migration` objects
   (`PokedexDatabase.ALL_MIGRATIONS`, new constant) provide an unbroken path from the on-disk
   version to the target version, in either direction, via graph reachability (not a hardcoded
   "must be exactly +1 each step" assumption — see Task 2's `MigrationPathResolver`). If no path
   exists, Room's `.fallbackToDestructiveMigration()` / `.fallbackToDestructiveMigrationOnDowngrade()`
   is about to fire.
4. If (and only if) no path exists and the on-disk version differs from the target: copy the file
   + sidecars to a rotated backup dir.
5. Proceed to the real `Room.databaseBuilder(...).build()` call, unchanged, exactly as today.

**Chosen** because it operates on a fully closed, un-touched file (maximum safety for a raw copy),
requires no change to Room's own builder wiring beyond one call site in `DatabaseModule.kt`, and
matches the "cheap check on the fast path, only pay for the copy on the rare path" requirement
directly: the common case (version matches, or a full incremental path exists) costs one
`PRAGMA user_version` read on a throwaway connection — no file copy, no table scan.

**Sources:**
- developer.android.com/training/data-storage/room/migrating-db-versions
- blog.termian.dev/posts/room-on-upgrade/
- gist.github.com/t3rmian/8ffe844882d4c009abd730cb98f75dac
- developer.android.com/reference/android/arch/persistence/room/RoomDatabase.Builder

---

## 3. Scope

**Goals**
- Detect, before Room's `.build()` executes the destructive path, that a destructive
  upgrade-or-downgrade is about to happen.
- Copy `pokedex_binder.db` + `-wal`/`-shm` (if present) to `filesDir/db_backups/` before that
  happens.
- Rotate: keep the newest 5 backup sets, delete older ones.
- Zero added cost to the common case (matching version or a clean incremental migration path).

**Non-goals**
- No restore UI. (If genuinely trivial to bolt on later, a follow-up task, not this one.)
- Not a general-purpose backup/export feature (no cloud upload, no user-triggered "backup now").
- Not fixing the underlying destructive-fallback configuration itself (still an accepted risk per
  `gaps.md` — this task only adds insurance under it, doesn't remove the landmine).
- No on-device/emulator proof (see §6).

---

## 4. Architecture — new package `data/local/backup/`

Three new files, one modified DI call site, one modified `PokedexDatabase.kt`. Follows the
project's existing pattern of small single-responsibility classes injected via Hilt modules
(mirrors `PublishRepository`/`RestoreRepository`'s separation), and keeps Android-framework-only
code (`SQLiteDatabase.openDatabase`) behind a narrow interface so the graph-reachability and
rotation logic stay plain-Kotlin JVM-testable without Robolectric (not a project dependency today
— confirmed via `app/build.gradle.kts`, no `robolectric` entry).

```
data/local/
  PokedexDatabase.kt                          [MODIFIED]
  backup/
    SqliteVersionReader.kt                    [NEW] — reads on-disk PRAGMA user_version
    MigrationPathResolver.kt                  [NEW] — pure graph reachability over Migration list
    DatabaseBackupManager.kt                  [NEW] — orchestrator: check → copy → rotate
di/
  DatabaseModule.kt                           [MODIFIED] — calls the manager before .build()
```

### `PokedexDatabase.kt` — new companion constants

```kotlin
companion object {
    const val SCHEMA_VERSION = 9   // single source of truth; @Database(version = SCHEMA_VERSION, ...)
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
    )
    // ... existing MIGRATION_x_y vals unchanged
}
```
`@Database(entities = [...], version = SCHEMA_VERSION, exportSchema = false)` — Kotlin `const val`
is a compile-time constant, valid as an annotation argument. This removes the current drift risk
where the annotation's `version = 9` and any future backup/migration-check code could silently
disagree on "what version is current." `ALL_MIGRATIONS` becomes the one array both
`DatabaseModule.provideDatabase`'s `.addMigrations(...)` and `DatabaseBackupManager` read from —
today `DatabaseModule` lists the five migrations by hand a second time; this task consolidates
that into one list (`.addMigrations(*PokedexDatabase.ALL_MIGRATIONS)`), incidentally fixing the
"migration added to `PokedexDatabase.kt` but the module wasn't updated" class of bug.

### `SqliteVersionReader.kt` [NEW]

```kotlin
package com.skyler.pokedexbinder.data.local.backup

import java.io.File

/** Reads the on-disk SQLite `PRAGMA user_version` without opening the file via Room. */
interface SqliteVersionReader {
    /** Returns null if [dbFile] doesn't exist or can't be opened (corrupt/locked). */
    fun readVersion(dbFile: File): Int?
}

class FrameworkSqliteVersionReader : SqliteVersionReader {
    override fun readVersion(dbFile: File): Int? {
        if (!dbFile.exists()) return null
        return try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { it.version }
        } catch (e: Exception) {
            android.util.Log.w("SqliteVersionReader", "Could not read on-disk version for ${dbFile.name}", e)
            null
        }
    }
}
```
(`SQLiteDatabase` implements `Closeable`, so `.use {}` closes it deterministically even on the
`.version` read path.) Interface + real impl split purely so `DatabaseBackupManager`'s decision
logic can be unit-tested against a fake reader without Robolectric.

### `MigrationPathResolver.kt` [NEW]

```kotlin
package com.skyler.pokedexbinder.data.local.backup

import androidx.room.migration.Migration

/**
 * Answers "does an unbroken chain of the given [Migration]s connect [from] to [to]?" via BFS
 * over each Migration's declared (startVersion, endVersion) edge — not a hardcoded assumption
 * that every step is exactly +1, since Room itself allows multi-version jump migrations.
 * Direction-agnostic: works for both upgrade gaps and downgrade gaps (today no downgrade
 * Migration is declared in this codebase, so any on-disk version > target always resolves to
 * "no path" — see PokedexDatabase Gotchas on why downgrades are never given a migration path).
 */
object MigrationPathResolver {
    fun hasPath(migrations: Array<Migration>, from: Int, to: Int): Boolean {
        if (from == to) return true
        val edges = migrations.groupBy { it.startVersion }
        val visited = mutableSetOf(from)
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (m in edges[current].orEmpty()) {
                if (m.endVersion == to) return true
                if (visited.add(m.endVersion)) queue.add(m.endVersion)
            }
        }
        return false
    }
}
```
`androidx.room.migration.Migration` is a plain Java/Kotlin class (part of `room-migration`, no
Android-framework dependency) — confirmed usable directly in JVM unit tests (`app/src/test/...`)
without Robolectric, same as the rest of this app's `room.runtime`/`room.ktx` usage in non-Android
test classes.

### `DatabaseBackupManager.kt` [NEW]

```kotlin
package com.skyler.pokedexbinder.data.local.backup

import android.content.Context
import android.util.Log
import androidx.room.migration.Migration
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DatabaseBackupManager(
    private val versionReader: SqliteVersionReader = FrameworkSqliteVersionReader(),
    private val maxBackups: Int = MAX_BACKUPS
) {
    /**
     * Call BEFORE Room.databaseBuilder(...).build(). If the on-disk schema version differs from
     * [targetVersion] AND no unbroken [migrations] path connects them (i.e. Room's destructive
     * fallback is about to fire), copies [dbFileName] + -wal/-shm sidecars (if present) into
     * context.filesDir/db_backups/ before returning, then rotates old backups down to
     * [maxBackups]. Best-effort: any failure is logged and swallowed — a failed backup must never
     * block app startup or prevent the (already-inevitable) migration/build from proceeding.
     *
     * Cheap on the common path: exactly one read-only PRAGMA user_version query when versions
     * already match or a full incremental path exists; the file copy only runs on the rare
     * destructive-bound path.
     */
    fun backupIfDestructiveMigrationImminent(
        context: Context,
        dbFileName: String,
        targetVersion: Int,
        migrations: Array<Migration>
    ) {
        try {
            val dbFile = context.getDatabasePath(dbFileName)
            val onDiskVersion = versionReader.readVersion(dbFile) ?: return // no file yet, or unreadable — nothing to protect
            if (onDiskVersion == targetVersion) return
            if (MigrationPathResolver.hasPath(migrations, onDiskVersion, targetVersion)) return

            Log.w(TAG, "Destructive migration imminent (on-disk v$onDiskVersion -> target v$targetVersion, no path) — backing up first")
            val backupDir = File(context.filesDir, BACKUP_DIR_NAME).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val baseName = "pokedex_binder_v${onDiskVersion}to${targetVersion}_$stamp"

            copyIfExists(dbFile, File(backupDir, "$baseName.db"))
            copyIfExists(File(dbFile.path + "-wal"), File(backupDir, "$baseName.db-wal"))
            copyIfExists(File(dbFile.path + "-shm"), File(backupDir, "$baseName.db-shm"))

            rotate(backupDir)
        } catch (e: Exception) {
            // Never let a backup failure block app startup or the migration it's insuring against.
            Log.e(TAG, "Pre-migration backup failed — proceeding without it", e)
        }
    }

    private fun copyIfExists(source: File, dest: File) {
        if (source.exists()) source.copyTo(dest, overwrite = true)
    }

    /** Keeps the newest [maxBackups] `.db` base names (by lastModified), deletes the rest + their sidecars. */
    private fun rotate(backupDir: File) {
        val dbFiles = backupDir.listFiles { f -> f.name.endsWith(".db") } ?: return
        val stale = dbFiles.sortedByDescending { it.lastModified() }.drop(maxBackups)
        for (old in stale) {
            val base = old.name.removeSuffix(".db")
            old.delete()
            File(backupDir, "$base.db-wal").delete()
            File(backupDir, "$base.db-shm").delete()
        }
    }

    companion object {
        private const val TAG = "DatabaseBackupManager"
        const val BACKUP_DIR_NAME = "db_backups"
        const val MAX_BACKUPS = 5
    }
}
```

### `DatabaseModule.kt` [MODIFIED]

```kotlin
@Provides
@Singleton
fun provideDatabase(@ApplicationContext context: Context): PokedexDatabase {
    DatabaseBackupManager().backupIfDestructiveMigrationImminent(
        context = context,
        dbFileName = "pokedex_binder.db",
        targetVersion = PokedexDatabase.SCHEMA_VERSION,
        migrations = PokedexDatabase.ALL_MIGRATIONS
    )
    return Room.databaseBuilder(context, PokedexDatabase::class.java, "pokedex_binder.db")
        .addMigrations(*PokedexDatabase.ALL_MIGRATIONS)
        .fallbackToDestructiveMigration()
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
}
```
No Hilt `@Provides` for `DatabaseBackupManager` itself — it's a plain stateless helper constructed
inline, consistent with the file having no injected dependencies worth a DI graph node (mirrors how
this module already constructs `Room.databaseBuilder` inline rather than injecting a builder).
If a future task needs to unit-test `DatabaseModule` itself or inject `DatabaseBackupManager`
elsewhere, promote it to a `@Provides` binding then — YAGNI for now.

---

## 5. Edge cases (explicit, from the request)

| Case | Behavior |
|---|---|
| First app launch, no `pokedex_binder.db` file yet | `versionReader.readVersion()` returns `null` (file doesn't exist) → early return, no backup dir even created. |
| `db_backups/` doesn't exist yet | `.apply { mkdirs() }` creates it lazily, only when a backup is actually about to happen. |
| On-disk version == target version | Early return before any file I/O — this is the hot path (normal app open), must stay cheap. |
| Full incremental migration path exists (normal forward upgrade) | `MigrationPathResolver.hasPath` returns true → early return, no backup. This is intentional: a clean migration doesn't destroy data, no insurance needed, and the requirement explicitly says don't slow this path down. |
| No migration path (destructive upgrade OR any downgrade) | Backup fires. |
| `-wal`/`-shm` not present (TRUNCATE journal mode, or a prior clean shutdown already checkpointed them away) | `copyIfExists` silently skips missing sidecars — not an error, `.db` alone is still a valid restorable snapshot for a checkpointed DB. |
| Backup rotation | Newest 5 `.db` base names kept (by `lastModified`), older ones deleted along with their `-wal`/`-shm` counterparts. Fewer than 5 existing backups is a no-op (`drop(5)` on a smaller list yields empty). |
| Permission / IO failure during backup (disk full, `SecurityException`, etc.) | Caught by the outer `try/catch` in `backupIfDestructiveMigrationImminent`, logged via `Log.e`, swallowed. **Decision: does not block the migration.** The destructive fallback was going to happen regardless (this manager only adds insurance around it, it doesn't gate Room's own behavior) — blocking app startup on a failed *backup* would turn a data-loss risk into an availability regression too. Flagged as an explicit product decision below, not purely technical — confirm with Skyler if he'd rather see a one-time warning surfaced in-app (e.g. a Settings banner) on backup failure; out of scope for this pass unless requested. |
| `versionReader.readVersion()` throws / file is corrupt/locked | Caught inside `FrameworkSqliteVersionReader.readVersion` itself, returns `null` → treated same as "no file", no backup attempted (nothing safely readable to copy anyway), logged via `Log.w`. |

---

## 6. Testing plan

No emulator/device available in this sandbox (standing constraint, per `gaps.md` and
`handoff.md` — every "verified" claim across the last two sessions has been compile/unit-level
only). This task follows the same limitation; do not claim on-device proof.

**JVM unit tests (`app/src/test/java/com/skyler/pokedexbinder/data/local/backup/`)** — run in this
sandbox, no Robolectric needed since these test pure logic + real `java.io.File` against real JVM
temp directories:

- `MigrationPathResolverTest.kt`
  - Contiguous forward chain (v4→v9 through the 5 real migrations) resolves true.
  - Missing single link in the chain resolves false.
  - Same version (`from == to`) resolves true trivially.
  - Downgrade direction (e.g. 9→7) with only forward migrations declared resolves false.
  - A hypothetical multi-version jump migration (e.g. a fake `Migration(5, 9)`) is honored by the
    graph search, not just adjacent +1 steps — proves the resolver isn't hardcoded to this
    project's current all-single-step migration shape.

- `DatabaseBackupManagerTest.kt` — construct with a fake `SqliteVersionReader`, point `Context`'s
  `getDatabasePath`/`filesDir` at real JUnit `@TempFolder`/`Files.createTempDirectory()` locations
  (use a lightweight fake/mock `Context` via mockk, following this project's existing mockk
  convention — see `BinderRepositoryTest.kt`/`CardSearchRepositoryTest.kt` for the established
  mocking style):
  - Versions match → no `db_backups/` dir created at all (asserts the cheap-path guarantee).
  - Versions differ, no path → `.db` copied, correct filename pattern.
  - `-wal`/`-shm` present → both copied alongside `.db`.
  - `-wal`/`-shm` absent → `.db` copied alone, no exception.
  - Rotation: seed 7 fake backup sets with staggered `lastModified`, run rotate, assert exactly 5
    newest survive (`.db` + both sidecars each), oldest 2 fully removed (including sidecars).
  - Backup dir doesn't exist yet → created on demand.
  - Simulated `IOException` during copy (e.g. dest dir made read-only, or mock a `File` that
    throws) → method returns normally (doesn't throw), asserts via `Log.e` not crashing the
    caller — verifies the "never block startup" contract.
  - First-launch case (`versionReader` returns `null`) → no directory created, no exception.

**Instrumented test (`app/src/androidTest/java/com/skyler/pokedexbinder/data/local/backup/`)** —
follows the exact convention of `Migration8to9Test.kt`/`Migration6to7Test.kt` (real
`ApplicationProvider.getApplicationContext()`, documented as compiled-but-unrun in this sandbox):

- `DatabaseBackupManagerInstrumentedTest.kt`
  - Write a real bare-shape SQLite file at the app's real `getDatabasePath("pokedex_binder.db")`
    path with `PRAGMA user_version = 7` set explicitly (mirroring the real-world incident: device
    already has a build with a stale/mismatched version), plus real `-wal` sidecar content.
  - Call `DatabaseBackupManager().backupIfDestructiveMigrationImminent(...)` with
    `PokedexDatabase.SCHEMA_VERSION` (9) and `PokedexDatabase.ALL_MIGRATIONS`.
  - Assert a backup appears under `context.filesDir/db_backups/`, contains the seeded data
    (open the copy directly, read a known row back), and `-wal` sidecar copied too.
  - This is the one test that actually exercises `FrameworkSqliteVersionReader`'s real
    `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` call against a live file — cannot be done in
    a JVM unit test without Robolectric.

**Manual/product verification once a device is available (documented, not performed here):**
reproduce the original incident — install a build at v9, then sideload/build at a lower declared
version — and confirm a backup file appears in `db_backups/` before data loss, matching this
spec's mechanism.

---

## 7. Task decomposition (dependency-ordered)

| # | Task | Type | Size | Depends on | Files |
|---|---|---|---|---|---|
| 1 | Add `SCHEMA_VERSION` + `ALL_MIGRATIONS` constants to `PokedexDatabase`; point `@Database(version = ...)` at the constant | MODEL | S | — | `data/local/PokedexDatabase.kt` |
| 2 | `SqliteVersionReader` interface + `FrameworkSqliteVersionReader` impl | CORE | S | — | `data/local/backup/SqliteVersionReader.kt` |
| 3 | `MigrationPathResolver` (BFS reachability) | CORE | S | — | `data/local/backup/MigrationPathResolver.kt` |
| 4 | `DatabaseBackupManager` (orchestrator: check/copy/rotate) | CORE | M | 1, 2, 3 | `data/local/backup/DatabaseBackupManager.kt` |
| 5 | Wire into `DatabaseModule.provideDatabase` (call before `.build()`, switch `.addMigrations` to use `ALL_MIGRATIONS`) | INTG | S | 1, 4 | `di/DatabaseModule.kt` |
| 6 | `MigrationPathResolverTest` (JVM) | TEST | S | 3 | `test/.../backup/MigrationPathResolverTest.kt` |
| 7 | `DatabaseBackupManagerTest` (JVM, fake reader + real temp `File`s) | TEST | M | 4 | `test/.../backup/DatabaseBackupManagerTest.kt` |
| 8 | `DatabaseBackupManagerInstrumentedTest` (androidTest, real SQLite file) | TEST | M | 4, 5 | `androidTest/.../backup/DatabaseBackupManagerInstrumentedTest.kt` |

No task exceeds M — this is a small, well-bounded addition; no L/XL tier needed. Tasks 2 and 3 are
independent of each other and can be built in either order (or in parallel by two agents) before
task 4 needs both.

---

## 8. Release readiness

- [ ] All new JVM unit tests passing (`./gradlew testDebugUnitTest`).
- [ ] `./gradlew compileDebugAndroidTestKotlin` still succeeds (this module has a documented
      history of pre-existing compile breaks in this source set — see `gaps.md`/`project-overview.md`
      re: the `PokedexDatabaseTest.kt` turbine/`dexOrder` breaks fixed 2026-07-15; verify this
      change doesn't introduce a new one).
- [ ] Full existing suite still green (168 tests as of `handoff.md`'s last count, plus this task's
      additions).
- [ ] Code review confirms: the common-path cost really is one read-only PRAGMA query (no
      accidental file copy or dir creation when versions match).
- [ ] Rollback: this is additive-only (new files + one call added ahead of an unchanged `.build()`
      chain) — reverting task 5's `DatabaseModule.kt` edit alone fully disables the feature with
      zero blast radius on anything else.
- **Not gated on:** on-device verification (unavailable in this sandbox, per standing constraint —
  flag in `gaps.md` after implementation exactly as `Migration8to9Test` was flagged).

---

## 9. Open questions (tagged for Skyler — non-blocking, defaults stated above)

1. **Backup-failure UX** — current default: fail silently (logged only), never blocks startup.
   Confirm this is acceptable, or whether a one-time in-app banner/toast on next launch ("last
   pre-migration backup failed") is wanted. Non-blocking for this implementation pass; can be a
   fast follow-up if desired.
2. **Retention count** — spec defaults to 5 (`MAX_BACKUPS`), within the requested 3-5 range.
   Confirm 5 vs 3 (5 costs marginally more storage; each backup is the full `pokedex_binder.db`,
   currently small given this is a personal single-user dataset).
3. **Restore path** — explicitly out of scope per the request ("unless genuinely trivial"). Given
   the backup is a plain copy of a valid SQLite file, a manual restore is technically just
   "copy the chosen backup `.db`(+sidecars) back over `pokedex_binder.db` while the app is closed,
   via adb" — genuinely possible today without any new code, just undocumented. Worth a short
   note in `project-overview.md`'s Gotchas once this ships, not a new UI surface.

---

## 10. Handoff notes for Stage 2 (Coder)

- Read `project-overview.md`'s Gotchas section (already done for this spec) before touching
  `PokedexDatabase.kt` or `DatabaseModule.kt` — this file has direct history with the exact
  failure this task is insuring against.
- `androidx.room.migration.Migration`'s `startVersion`/`endVersion` are public `val`s on the
  class (framework API, not something this task defines) — confirm exact property names against
  the actual `room-migration` artifact version this project pins (`libs.versions.toml`) before
  wiring `MigrationPathResolver`; API has been stable across recent Room versions but verify against
  the resolved dependency rather than assuming.
- Keep `DatabaseBackupManager` framework-light (no Hilt injection) per §4 — do not over-engineer a
  DI binding for a single stateless helper with no dependencies worth injecting.
- This is size M overall (largest single task is M) — should fit one Coder session; no need to
  split further.
