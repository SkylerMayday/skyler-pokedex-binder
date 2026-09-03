package com.skyler.pokedexbinder.data.local.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import com.skyler.pokedexbinder.publish.RestoreRepository
import com.skyler.pokedexbinder.publish.RestoreResult
import com.skyler.pokedexbinder.publish.model.BackupEnvelope
import com.skyler.pokedexbinder.publish.model.BinderSnapshot
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * Covers [BackupImporter.prepare]'s gates for both the JSON and `.db` paths (task 2's required AC
 * coverage), the temp-file lifecycle every non-accepted `.db` exit has to honour, and
 * [BackupImporter.applyDb]'s closed-database-then-restart composition — no Robolectric in this
 * project: [Context]/[ContentResolver] are mocked directly, and the `.db` path's on-disk facts
 * come from a fake [SqliteVersionReader] injected through the constructor rather than exercising
 * the real SQLite read. The one exception is `Intent`, which `applyDb` constructs directly with
 * no other seam available; its constructor is intercepted via `mockkConstructor` rather than
 * exercised for real.
 */
class BackupImporterTest {

    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val restoreRepository = mockk<RestoreRepository>()
    private val database = mockk<PokedexDatabase>(relaxed = true)
    private val databaseBackupManager = mockk<DatabaseBackupManager>(relaxed = true)
    private lateinit var cacheDir: File
    private lateinit var contentResolver: ContentResolver
    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var sharedPreferencesEditor: SharedPreferences.Editor

    /** Defaults to a file that passes every gate, so each test only states the fact it's about. */
    private class FakeVersionReader(
        private val version: Int?,
        private val tables: Set<String>? = setOf("main_binder", "secondary_binder", "unown_binder")
    ) : SqliteVersionReader {
        override fun readVersion(dbFile: File): Int? = version
        override fun readTableNames(dbFile: File): Set<String>? = tables
    }

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("backup-importer-test-cache").toFile()
        contentResolver = mockk()
        sharedPreferencesEditor = mockk()
        every { sharedPreferencesEditor.putString(any(), any()) } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.remove(any()) } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.commit() } returns true
        every { sharedPreferencesEditor.apply() } just Runs
        sharedPreferences = mockk()
        every { sharedPreferences.edit() } returns sharedPreferencesEditor
        context = mockk {
            every { contentResolver } returns this@BackupImporterTest.contentResolver
            every { cacheDir } returns this@BackupImporterTest.cacheDir
            every { getSharedPreferences(any(), any()) } returns sharedPreferences
            every { packageName } returns "com.skyler.pokedexbinder"
            every { startActivity(any()) } just Runs
        }
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
        unmockkAll()
    }

    private fun importer(versionReader: SqliteVersionReader = FakeVersionReader(PokedexDatabase.SCHEMA_VERSION)) =
        BackupImporter(context, moshi, restoreRepository, database, versionReader, databaseBackupManager)

    private fun uriNamed(fileName: String, bytes: ByteArray): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.lastPathSegment } returns fileName
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
        return uri
    }

    private fun envelopeJson(schemaVersion: Int): String {
        val envelope = BackupEnvelope(
            appVersionName = "1.0",
            roomSchemaVersion = schemaVersion,
            exportedAt = "2026-09-03T12:00:00+08:00",
            snapshot = BinderSnapshot(publishedAt = "2026-09-03T12:00:00+08:00", binders = emptyList())
        )
        return moshi.adapter(BackupEnvelope::class.java).toJson(envelope)
    }

    private fun assertCacheIsEmpty() {
        assertEquals(
            "no temp file or sidecar may survive a non-accepted import",
            emptyList<String>(),
            cacheDir.listFiles().orEmpty().map { it.name }.sorted()
        )
    }

    // --- JSON path ---

    @Test
    fun `json envelope at or below current schema version is accepted`() = runTest {
        val uri = uriNamed("backup.json", envelopeJson(PokedexDatabase.SCHEMA_VERSION).toByteArray())

        val result = importer().prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Ready)
        val preview = (result as ImportPrepareResult.Ready).preview
        assertTrue(preview is ImportPreview.Json)
        assertEquals(PokedexDatabase.SCHEMA_VERSION, (preview as ImportPreview.Json).envelope.roomSchemaVersion)
    }

    @Test
    fun `json envelope newer than current schema version is rejected and nothing is touched`() = runTest {
        val newerVersion = PokedexDatabase.SCHEMA_VERSION + 1
        val uri = uriNamed("backup.json", envelopeJson(newerVersion).toByteArray())

        val result = importer().prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Rejected)
        val message = (result as ImportPrepareResult.Rejected).message
        assertTrue(message.contains("$newerVersion"))
        assertTrue(message.contains("${PokedexDatabase.SCHEMA_VERSION}"))
        coVerify(exactly = 0) { restoreRepository.restoreFromSnapshot(any(), any()) }
    }

    @Test
    fun `malformed json is rejected with a specific message`() = runTest {
        val uri = uriNamed("backup.json", "{ not valid json".toByteArray())

        val result = importer().prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Rejected)
        assertTrue((result as ImportPrepareResult.Rejected).message.contains("not a valid backup JSON"))
    }

    @Test
    fun `applyJson routes the envelope snapshot through restoreRepository restoreFromSnapshot`() = runTest {
        val snapshot = BinderSnapshot(publishedAt = "2026-09-03T12:00:00+08:00", binders = emptyList())
        val preview = ImportPreview.Json(
            BackupEnvelope("1.0", PokedexDatabase.SCHEMA_VERSION, "2026-09-03T12:00:00+08:00", snapshot)
        )
        coEvery { restoreRepository.restoreFromSnapshot(snapshot, any()) } returns
            RestoreResult.Success(1, 0, 0, 5L)

        val result = importer().applyJson(preview) {}

        assertTrue(result is RestoreResult.Success)
        coVerify { restoreRepository.restoreFromSnapshot(snapshot, any()) }
    }

    // --- .db path: version + identity gates ---

    @Test
    fun `db file at or below current schema version is accepted`() = runTest {
        val uri = uriNamed("backup.db", byteArrayOf(1, 2, 3))

        val result = importer(FakeVersionReader(PokedexDatabase.SCHEMA_VERSION)).prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Ready)
        val preview = (result as ImportPrepareResult.Ready).preview
        assertTrue(preview is ImportPreview.Db)
        assertEquals(PokedexDatabase.SCHEMA_VERSION, (preview as ImportPreview.Db).onDiskVersion)
        assertTrue(preview.tempFile.exists())
    }

    @Test
    fun `db file from an older but migratable schema version is accepted`() = runTest {
        // v7 -> v9 has a full path through MIGRATION_7_8 + MIGRATION_8_9.
        val uri = uriNamed("backup.db", byteArrayOf(1, 2, 3))

        val result = importer(FakeVersionReader(7)).prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Ready)
        assertEquals(7, ((result as ImportPrepareResult.Ready).preview as ImportPreview.Db).onDiskVersion)
    }

    @Test
    fun `db file newer than current schema version is rejected and the temp copy is deleted`() = runTest {
        val newerVersion = PokedexDatabase.SCHEMA_VERSION + 1
        val uri = uriNamed("backup.db", byteArrayOf(1, 2, 3))

        val result = importer(FakeVersionReader(newerVersion)).prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Rejected)
        val message = (result as ImportPrepareResult.Rejected).message
        assertTrue(message.contains("$newerVersion"))
        assertTrue(message.contains("${PokedexDatabase.SCHEMA_VERSION}"))
        assertCacheIsEmpty()
    }

    @Test
    fun `db file too old for any migration path is rejected instead of wiping every table`() = runTest {
        // v1 has no declared migration edge at all — Room's fallbackToDestructiveMigration() would
        // drop and recreate every table on the next cold start, after the live DB was overwritten.
        val uri = uriNamed("backup.db", byteArrayOf(1, 2, 3))

        val result = importer(FakeVersionReader(1)).prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Rejected)
        assertTrue((result as ImportPrepareResult.Rejected).message.contains("wipe every table"))
        assertCacheIsEmpty()
    }

    @Test
    fun `sqlite file without this app's tables is rejected as not a binder backup`() = runTest {
        // A foreign SQLite file reports user_version 0, which passes any "not newer than mine" gate.
        val uri = uriNamed("backup.db", byteArrayOf(1, 2, 3))

        val result = importer(FakeVersionReader(0, tables = setOf("android_metadata", "messages")))
            .prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Rejected)
        assertTrue((result as ImportPrepareResult.Rejected).message.contains("Pokédex Binder backup"))
        assertCacheIsEmpty()
    }

    @Test
    fun `db file that fails to open as sqlite is rejected and the temp copy is deleted`() = runTest {
        val uri = uriNamed("backup.db", byteArrayOf(1, 2, 3))

        val result = importer(FakeVersionReader(null)).prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Rejected)
        assertTrue((result as ImportPrepareResult.Rejected).message.contains("not a valid SQLite database"))
        assertCacheIsEmpty()
    }

    @Test
    fun `a stream that dies mid-copy is rejected and leaves no partial temp file`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.lastPathSegment } returns "backup.db"
        every { contentResolver.openInputStream(uri) } returns object : InputStream() {
            private var servedFirstChunk = false
            override fun read(): Int = throw IOException("stream died")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (servedFirstChunk) throw IOException("stream died")
                servedFirstChunk = true
                b[off] = 1
                return 1
            }
        }

        val result = importer().prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Rejected)
        assertTrue((result as ImportPrepareResult.Rejected).message.contains("Could not read"))
        assertCacheIsEmpty()
    }

    // --- swapInDatabaseFile (the one irreversible step) ---

    @Test
    fun `swapInDatabaseFile replaces the target and leaves no staging file behind`() {
        val source = File(cacheDir, "import_temp_1.db").apply { writeText("imported bytes") }
        val target = File(cacheDir, "pokedex_binder.db").apply { writeText("live bytes") }

        importer().swapInDatabaseFile(source, target)

        assertEquals("imported bytes", target.readText())
        assertTrue("staging file must not survive a successful swap", !File(target.path + ".importing").exists())
    }

    @Test
    fun `swapInDatabaseFile drops the replaced database's stale sidecars`() {
        // A WAL belonging to the OLD database, replayed against the file that just replaced it,
        // corrupts it.
        val source = File(cacheDir, "import_temp_1.db").apply { writeText("imported bytes") }
        val target = File(cacheDir, "pokedex_binder.db").apply { writeText("live bytes") }
        File(target.path + "-wal").writeText("stale wal")
        File(target.path + "-shm").writeText("stale shm")

        importer().swapInDatabaseFile(source, target)

        assertTrue(!File(target.path + "-wal").exists())
        assertTrue(!File(target.path + "-shm").exists())
    }

    @Test
    fun `a failed swap leaves the live database untouched and removes the staging file`() {
        // The realistic failure: Android evicted the cacheDir temp copy between the confirmation
        // dialog and the swap, so there is nothing to import. The live database must survive it.
        val source = File(cacheDir, "evicted_temp.db")
        val target = File(cacheDir, "pokedex_binder.db").apply { writeText("live bytes") }

        var thrown: Throwable? = null
        try {
            importer().swapInDatabaseFile(source, target)
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("the failure must surface, not be swallowed", thrown != null)
        assertEquals("live bytes", target.readText())
        assertTrue(!File(target.path + ".importing").exists())
    }

    // --- applyDb (composes close() + a failed swap + the restart-or-recover path in one flow —
    // security review finding: a failed swap must not leave `database` closed with no restart).
    // `Intent`'s constructor is real Android framework code with no other seam in this project
    // (no Robolectric); `mockkConstructor` intercepts just it, everything else stays as before.

    @Test
    fun `applyDb forces a process restart even when the swap fails, instead of returning to the caller with a closed database`() =
        runTest {
            mockkStatic(Process::class)
            mockkConstructor(Intent::class)
            every { Process.myPid() } returns 4242
            every { Process.killProcess(any()) } just Runs
            every { anyConstructed<Intent>().addFlags(any()) } returns Intent()
            val dbFile = File(cacheDir, "pokedex_binder.db").apply { writeText("live bytes") }
            every { context.getDatabasePath(PokedexDatabase.DB_FILE_NAME) } returns dbFile
            // The realistic failure: Android evicted the cacheDir temp copy between the
            // confirmation dialog and the swap, so there is nothing for the move to find.
            val preview = ImportPreview.Db(File(cacheDir, "evicted_temp.db"), PokedexDatabase.SCHEMA_VERSION)

            importer().applyDb(preview)

            // The database must already be closed by the time the swap is attempted — that
            // precondition is exactly what makes a failure here dangerous without a forced
            // restart (Room has no reopen path for a manually-closed instance).
            verify { database.close() }
            // The live file itself survives untouched — the swap never got past staging.
            assertEquals("live bytes", dbFile.readText())
            // The regression: a failed swap must restart the process just like a successful one,
            // not return control to the caller with `database` left permanently closed.
            verify { context.startActivity(any()) }
            verify { Process.killProcess(4242) }
        }

    @Test
    fun `applyDb persists the failure message for MainActivity to show after the forced restart`() = runTest {
        mockkStatic(Process::class)
        mockkConstructor(Intent::class)
        every { Process.myPid() } returns 1
        every { Process.killProcess(any()) } just Runs
        every { anyConstructed<Intent>().addFlags(any()) } returns Intent()
        every { context.getDatabasePath(PokedexDatabase.DB_FILE_NAME) } returns
            File(cacheDir, "pokedex_binder.db").apply { writeText("live bytes") }
        val messageSlot = slot<String>()
        every { sharedPreferencesEditor.putString(any(), capture(messageSlot)) } returns sharedPreferencesEditor
        val preview = ImportPreview.Db(File(cacheDir, "evicted_temp.db"), PokedexDatabase.SCHEMA_VERSION)

        importer().applyDb(preview)

        verify { sharedPreferencesEditor.putString(any(), any()) }
        verify { sharedPreferencesEditor.commit() }
        assertTrue(
            "the persisted message must explain what failed, not just that something did",
            messageSlot.captured.contains("database")
        )
    }

    // --- discardPreview (Cancel-path cleanup) ---

    @Test
    fun `discardPreview deletes the db preview's temp file and its sidecars`() = runTest {
        val tempFile = File(cacheDir, "import_temp_12345.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        // Reading PRAGMA user_version opens the file, which makes SQLite drop these beside it.
        File(tempFile.path + "-wal").writeBytes(byteArrayOf(0))
        File(tempFile.path + "-shm").writeBytes(byteArrayOf(0))
        val preview = ImportPreview.Db(tempFile, PokedexDatabase.SCHEMA_VERSION)

        importer().discardPreview(preview)

        assertCacheIsEmpty()
    }

    @Test
    fun `discardPreview is a no-op for a json preview`() = runTest {
        val snapshot = BinderSnapshot(publishedAt = "2026-09-03T12:00:00+08:00", binders = emptyList())
        val preview = ImportPreview.Json(
            BackupEnvelope("1.0", PokedexDatabase.SCHEMA_VERSION, "2026-09-03T12:00:00+08:00", snapshot)
        )

        // Must not throw — json previews have no temp file to clean up.
        importer().discardPreview(preview)
    }

    // --- routing / no recognized file ---

    @Test
    fun `neither json nor db extension is rejected`() = runTest {
        val uri = uriNamed("notes.txt", byteArrayOf(1))

        val result = importer().prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Rejected)
    }

    @Test
    fun `routing reads the provider's DISPLAY_NAME column, not just the uri path`() = runTest {
        // The spec picked DISPLAY_NAME over ContentResolver.getType() precisely because providers
        // like Drive/Telegram hand back an opaque last path segment.
        val uri = uriNamed("opaque-document-id", envelopeJson(PokedexDatabase.SCHEMA_VERSION).toByteArray())
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.moveToFirst() } returns true
        every { cursor.getString(0) } returns "pokedex-binder-backup-20260903_120000.json"
        every { contentResolver.query(uri, any(), any(), any(), any()) } returns cursor

        val result = importer().prepare(listOf(uri))

        assertTrue(result is ImportPrepareResult.Ready)
        assertTrue((result as ImportPrepareResult.Ready).preview is ImportPreview.Json)
    }

    @Test
    fun `when both json and db are picked together, json is preferred`() = runTest {
        val jsonUri = uriNamed("backup.json", envelopeJson(PokedexDatabase.SCHEMA_VERSION).toByteArray())
        val dbUri = uriNamed("backup.db", byteArrayOf(1, 2, 3))

        val result = importer().prepare(listOf(dbUri, jsonUri))

        assertTrue(result is ImportPrepareResult.Ready)
        assertTrue((result as ImportPrepareResult.Ready).preview is ImportPreview.Json)
    }
}
