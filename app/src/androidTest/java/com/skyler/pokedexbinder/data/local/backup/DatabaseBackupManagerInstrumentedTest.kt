package com.skyler.pokedexbinder.data.local.backup

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises [DatabaseBackupManager] end-to-end against a real on-disk SQLite file via a real
 * [android.content.Context] — the one path that can't be covered by the JVM unit tests in
 * [DatabaseBackupManagerTest], since [FrameworkSqliteVersionReader] calls the real
 * `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` which requires an actual Android SQLite
 * implementation, not the host JVM's.
 *
 * Mirrors the real-world incident this feature guards against: a device already has a build with
 * an on-disk schema version that predates [PokedexDatabase.SCHEMA_VERSION] and no forward
 * migration path exists for it (simulated here as v3, which has no registered [PokedexDatabase]
 * migration edge at all).
 *
 * NOTE: this is an androidTest and requires a device/emulator to execute. No AVD/emulator was
 * available in the environment this file was authored in — this file compiles and is ready to
 * run, but was not executed live here (same standing constraint as Migration8to9Test).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseBackupManagerInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbFileName = "pokedex_binder_backup_test.db"
    private lateinit var dbFile: File
    private lateinit var backupDir: File

    @Before
    fun setUp() {
        dbFile = context.getDatabasePath(dbFileName)
        dbFile.parentFile?.mkdirs()
        backupDir = File(context.filesDir, DatabaseBackupManager.BACKUP_DIR_NAME)
        cleanup()
    }

    @After
    fun tearDown() {
        cleanup()
    }

    private fun cleanup() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        backupDir.deleteRecursively()
    }

    @Test
    fun backupCopiesRealSqliteFileAndWalSidecarWhenNoMigrationPathExists() {
        // Seed a real bare SQLite file at the app's real getDatabasePath() location, with
        // PRAGMA user_version explicitly set to 7 — mirrors the real incident: a device already
        // has data at a stale/mismatched version with no forward migration path declared for it.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { seed ->
            seed.version = 3
            seed.execSQL("CREATE TABLE probe (id INTEGER PRIMARY KEY, note TEXT)")
            seed.execSQL("INSERT INTO probe (id, note) VALUES (1, 'pre-migration data')")
        }
        // Real WAL sidecar content alongside the main file (Room's default journal mode).
        File(dbFile.path + "-wal").writeText("wal sidecar content")

        DatabaseBackupManager().backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = dbFileName,
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        assertTrue("backup dir should be created when no migration path exists", backupDir.exists())
        val backedUpDb = backupDir.listFiles { f -> f.name.endsWith(".db") }.orEmpty()
        assertEquals(1, backedUpDb.size)
        assertTrue(
            "filename should match pokedex_binder_backup_test_v3to9_<timestamp>.db",
            Regex("""pokedex_binder_backup_test_v3to9_\d{8}_\d{6}\.db""").matches(backedUpDb[0].name)
        )

        // The backup is a real, independently-openable SQLite file with the seeded row intact.
        SQLiteDatabase.openDatabase(
            backedUpDb[0].absolutePath, null, SQLiteDatabase.OPEN_READONLY
        ).use { restored ->
            restored.rawQuery("SELECT note FROM probe WHERE id = 1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("pre-migration data", cursor.getString(0))
            }
        }

        val backedUpWal = backupDir.listFiles { f -> f.name.endsWith(".db-wal") }.orEmpty()
        assertEquals(1, backedUpWal.size)
        assertEquals("wal sidecar content", backedUpWal[0].readText())
    }

    @Test
    fun noBackupCreatedWhenOnDiskVersionAlreadyMatchesTarget() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { seed ->
            seed.version = PokedexDatabase.SCHEMA_VERSION
        }

        DatabaseBackupManager().backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = dbFileName,
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        assertTrue("no backup dir should be created on the matching-version fast path", !backupDir.exists())
    }
}
