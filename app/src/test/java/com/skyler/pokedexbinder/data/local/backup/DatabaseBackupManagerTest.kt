package com.skyler.pokedexbinder.data.local.backup

import android.content.Context
import android.util.Log
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exercises [DatabaseBackupManager] against a fake [SqliteVersionReader] and a real JVM temp
 * directory standing in for `context.filesDir` — no Robolectric needed since only plain
 * `java.io.File` operations are involved (the Android-framework-only piece, the real SQLite
 * `PRAGMA user_version` read, lives in [FrameworkSqliteVersionReader] and is covered separately
 * by the instrumented test).
 */
class DatabaseBackupManagerTest {

    private lateinit var filesDir: File
    private lateinit var dbDir: File
    private lateinit var dbFile: File
    private lateinit var context: Context

    private class FakeVersionReader(private val version: Int?) : SqliteVersionReader {
        override fun readVersion(dbFile: File): Int? = version
    }

    private val backupDir: File get() = File(filesDir, DatabaseBackupManager.BACKUP_DIR_NAME)

    @Before
    fun setUp() {
        // android.util.Log isn't available in a plain JVM unit test (no Robolectric here) —
        // stub the static methods so DatabaseBackupManager's Log.w/Log.e calls don't throw.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0

        filesDir = Files.createTempDirectory("backup-test-filesdir").toFile()
        dbDir = Files.createTempDirectory("backup-test-dbdir").toFile()
        dbFile = File(dbDir, "pokedex_binder.db")

        context = mockk {
            every { filesDir } returns this@DatabaseBackupManagerTest.filesDir
            every { getDatabasePath(any()) } answers { File(dbDir, firstArg()) }
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        filesDir.deleteRecursively()
        dbDir.deleteRecursively()
    }

    private fun manager(version: Int?, maxBackups: Int = DatabaseBackupManager.MAX_BACKUPS) =
        DatabaseBackupManager(versionReader = FakeVersionReader(version), maxBackups = maxBackups)

    @Test
    fun `versions match - no backup dir created at all`() {
        dbFile.writeText("real db content")

        manager(version = PokedexDatabase.SCHEMA_VERSION).backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = "pokedex_binder.db",
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        assertFalse("backup dir must not be created on the matching-version fast path", backupDir.exists())
    }

    @Test
    fun `full incremental migration path exists - no backup`() {
        dbFile.writeText("real db content")

        // v7 -> v9 has a full path through MIGRATION_7_8 + MIGRATION_8_9.
        manager(version = 7).backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = "pokedex_binder.db",
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        assertFalse("backup dir must not be created when a clean migration path exists", backupDir.exists())
    }

    @Test
    fun `versions differ with no path - db copied with correct filename pattern`() {
        dbFile.writeText("real db content")

        // v3 has no declared migration edge at all - no path to v9.
        manager(version = 3).backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = "pokedex_binder.db",
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        assertTrue(backupDir.exists())
        val dbBackups = backupDir.listFiles { f -> f.name.endsWith(".db") }.orEmpty()
        assertEquals(1, dbBackups.size)
        assertTrue(
            "filename should match pokedex_binder_v<from>to<to>_<timestamp>.db",
            Regex("""pokedex_binder_v3to9_\d{8}_\d{6}\.db""").matches(dbBackups[0].name)
        )
        assertEquals("real db content", dbBackups[0].readText())
    }

    @Test
    fun `wal and shm sidecars present - both copied alongside db`() {
        dbFile.writeText("real db content")
        File(dbFile.path + "-wal").writeText("wal content")
        File(dbFile.path + "-shm").writeText("shm content")

        manager(version = 3).backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = "pokedex_binder.db",
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        val backedUp = backupDir.listFiles().orEmpty().map { it.name }
        assertEquals(3, backedUp.size)
        assertTrue(backedUp.any { it.endsWith(".db") })
        assertTrue(backedUp.any { it.endsWith(".db-wal") })
        assertTrue(backedUp.any { it.endsWith(".db-shm") })
    }

    @Test
    fun `wal and shm sidecars absent - db copied alone, no exception`() {
        dbFile.writeText("real db content")
        // deliberately no -wal / -shm files created

        manager(version = 3).backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = "pokedex_binder.db",
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        val backedUp = backupDir.listFiles().orEmpty()
        assertEquals(1, backedUp.size)
        assertTrue(backedUp[0].name.endsWith(".db"))
    }

    @Test
    fun `rotation keeps only the newest maxBackups sets, deleting older sets and their sidecars`() {
        backupDir.mkdirs()
        // Seed 6 pre-existing backup sets with strictly increasing lastModified timestamps
        // (oldest first). A 7th backup is then triggered for real, for 7 total - rotation
        // (maxBackups = 5) must keep the 5 newest and delete the 2 oldest, sidecars included.
        val seeded = (1..6).map { i ->
            val base = "seeded_$i"
            val db = File(backupDir, "$base.db").apply { writeText("seed $i") }
            val wal = File(backupDir, "$base.db-wal").apply { writeText("seed $i wal") }
            val shm = File(backupDir, "$base.db-shm").apply { writeText("seed $i shm") }
            val stamp = 1_000_000L * i
            db.setLastModified(stamp)
            wal.setLastModified(stamp)
            shm.setLastModified(stamp)
            base
        }

        dbFile.writeText("newest content")
        manager(version = 3).backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = "pokedex_binder.db",
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        val remainingDbBases = backupDir.listFiles { f -> f.name.endsWith(".db") }
            .orEmpty()
            .map { it.name.removeSuffix(".db") }
            .toSet()

        assertEquals(5, remainingDbBases.size)
        // The two oldest seeded sets (seeded_1, seeded_2) must be fully gone, sidecars included.
        assertFalse(remainingDbBases.contains("seeded_1"))
        assertFalse(remainingDbBases.contains("seeded_2"))
        assertFalse(File(backupDir, "seeded_1.db-wal").exists())
        assertFalse(File(backupDir, "seeded_1.db-shm").exists())
        assertFalse(File(backupDir, "seeded_2.db-wal").exists())
        assertFalse(File(backupDir, "seeded_2.db-shm").exists())
        // The 4 newer seeded sets plus the just-created one must all survive.
        assertTrue(remainingDbBases.contains("seeded_3"))
        assertTrue(remainingDbBases.contains("seeded_4"))
        assertTrue(remainingDbBases.contains("seeded_5"))
        assertTrue(remainingDbBases.contains("seeded_6"))
    }

    @Test
    fun `backup dir doesn't exist yet - created on demand`() {
        assertFalse(backupDir.exists())
        dbFile.writeText("real db content")

        manager(version = 3).backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = "pokedex_binder.db",
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        assertTrue(backupDir.exists())
    }

    @Test
    fun `IOException during copy - method returns normally, never blocks the caller`() {
        // Point the "source" at a directory instead of a regular file: File.copyTo() opens a
        // FileInputStream on it, which throws (you can't read a directory as a stream),
        // simulating a real copy failure without relying on OS-specific permission semantics.
        dbFile.mkdirs()

        manager(version = 3).backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = "pokedex_binder.db",
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )
        // No exception propagated out of the call above is the assertion - a failed backup must
        // never block app startup or the migration it's insuring against.
    }

    @Test
    fun `first launch - versionReader returns null - no directory created, no exception`() {
        // dbFile deliberately not created; a real FrameworkSqliteVersionReader would also
        // return null here since the file doesn't exist, but this test isolates the manager's
        // own handling of a null read regardless of the reason behind it.
        manager(version = null).backupIfDestructiveMigrationImminent(
            context = context,
            dbFileName = "pokedex_binder.db",
            targetVersion = PokedexDatabase.SCHEMA_VERSION,
            migrations = PokedexDatabase.ALL_MIGRATIONS
        )

        assertFalse(backupDir.exists())
    }
}
