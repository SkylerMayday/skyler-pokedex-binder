package com.skyler.pokedexbinder.di

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import com.skyler.pokedexbinder.data.local.backup.DatabaseBackupManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Closes the coverage gap found during the pre-migration-backup feature's Tester stage: every
 * existing test (JVM + [com.skyler.pokedexbinder.data.local.backup.DatabaseBackupManagerInstrumentedTest])
 * validates [DatabaseBackupManager] in isolation, but none of them prove the actual
 * [DatabaseModule.provideDatabase] wiring calls it — a mutation test that removed the call left
 * the full suite green. This test calls [DatabaseModule.buildDatabase] directly (the exact
 * function [DatabaseModule.provideDatabase] delegates to, not a reimplementation) against a
 * test-only file name, so a future accidental removal of the backup call from that function
 * fails this test.
 *
 * Deliberately does NOT use [DatabaseModule.provideDatabase] itself or Hilt's test runner: this
 * project has no Hilt test infra (`hilt-android-testing` isn't a dependency, no
 * `HiltTestApplication`), and [DatabaseModule.provideDatabase] hardcodes the real
 * `pokedex_binder.db` file name — calling it directly in a test would operate on the actual
 * on-device database if this ever runs on a device where the app is already installed, which is
 * exactly the data this feature exists to protect.
 *
 * NOTE: this is an androidTest and requires a device/emulator to execute. No AVD/emulator was
 * available in the environment this file was authored in — this file compiles and is ready to
 * run, but was not executed live here (same standing constraint as every other instrumented test
 * in this project).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseModuleWiringTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbFileName = "pokedex_binder_wiring_test.db"
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
    fun tearDown() = cleanup()

    private fun cleanup() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        backupDir.deleteRecursively()
        context.deleteDatabase(dbFileName)
    }

    @Test
    fun buildDatabase_backsUpBeforeDestructiveRebuild_whenNoMigrationPathExists() {
        // Seed a stale on-disk file (v3, no registered migration edge to SCHEMA_VERSION) with
        // real content, mirroring the original incident: a device already has data at a version
        // Room has no forward path for.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { seed ->
            seed.version = 3
            seed.execSQL("CREATE TABLE probe (id INTEGER PRIMARY KEY, note TEXT)")
            seed.execSQL("INSERT INTO probe (id, note) VALUES (1, 'pre-wipe data')")
        }

        // Calls the real DatabaseModule.buildDatabase — the same function provideDatabase
        // delegates to in production. If the DatabaseBackupManager call is ever removed from
        // that function, this backup dir will not appear and this test fails.
        val db = DatabaseModule.buildDatabase(context, dbFileName)
        try {
            assertTrue("provideDatabase's real code path must back up before Room's destructive rebuild", backupDir.exists())
            val backedUpDb = backupDir.listFiles { f -> f.name.endsWith(".db") }.orEmpty()
            assertEquals(1, backedUpDb.size)

            SQLiteDatabase.openDatabase(
                backedUpDb[0].absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { restored ->
                restored.rawQuery("SELECT note FROM probe WHERE id = 1", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("pre-wipe data", cursor.getString(0))
                }
            }

            // The database Room actually returns is a working, fully-migrated instance at the
            // target schema version, despite the destructive rebuild that just happened.
            assertTrue(db.isOpen)
        } finally {
            db.close()
        }
    }

    @Test
    fun buildDatabase_noBackupCreated_whenOnDiskVersionAlreadyMatchesTarget() {
        // First call creates a real, fully-schema'd v9 database (no file exists yet, so the
        // version reader returns null -> no backup -> Room creates it fresh). This avoids seeding
        // a bare file with a matching PRAGMA user_version but no actual tables, which would make
        // Room's schema validation crash on open once versions already match (no migration or
        // destructive fallback runs to fix up a mismatched/empty schema in that case).
        DatabaseModule.buildDatabase(context, dbFileName).close()
        assertTrue("first call must not back up a file that didn't exist yet", !backupDir.exists())

        // Second call: on-disk version now genuinely matches target with valid Room-created
        // schema — the real common-path scenario this test is meant to exercise.
        val db = DatabaseModule.buildDatabase(context, dbFileName)
        try {
            assertTrue("no backup should be made on the common, matching-version path", !backupDir.exists())
        } finally {
            db.close()
        }
    }
}
