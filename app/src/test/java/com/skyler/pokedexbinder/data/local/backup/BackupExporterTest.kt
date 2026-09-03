package com.skyler.pokedexbinder.data.local.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.publish.PublishRepository
import com.skyler.pokedexbinder.publish.model.BinderSnapshot
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.ConnectingArtRepository
import com.skyler.pokedexbinder.repository.PersonalCollectionRepository
import com.skyler.pokedexbinder.repository.UnownBinderRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exercises [BackupExporter] against mocked [Context]/[PokedexDatabase] and a real JVM temp
 * directory standing in for `filesDir` (same no-Robolectric pattern as [DatabaseBackupManagerTest]).
 * [FileProvider.getUriForFile] is the one genuinely Android-framework-only call — statically
 * mocked, mirroring [DatabaseBackupManagerTest]'s existing `mockkStatic(Log::class)` precedent.
 */
class BackupExporterTest {

    private lateinit var filesDir: File
    private lateinit var dbSourceFile: File
    private lateinit var context: Context
    private val database = mockk<PokedexDatabase>()
    private val sqliteDatabase = mockk<SupportSQLiteDatabase>(relaxed = true)
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val publishRepository = mockk<PublishRepository>()
    private val binderRepository = mockk<BinderRepository>()
    private val secondaryBinderDao = mockk<SecondaryBinderDao>()
    private val connectingArtRepository = mockk<ConnectingArtRepository>()
    private val personalCollectionRepository = mockk<PersonalCollectionRepository>()
    private val unownBinderRepository = mockk<UnownBinderRepository>()
    private lateinit var exporter: BackupExporter

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("backup-exporter-test-filesdir").toFile()
        val dbDir = Files.createTempDirectory("backup-exporter-test-dbdir").toFile()
        dbSourceFile = File(dbDir, PokedexDatabase.DB_FILE_NAME).apply { writeText("fake-sqlite-bytes") }

        context = mockk {
            every { filesDir } returns this@BackupExporterTest.filesDir
            every { getDatabasePath(PokedexDatabase.DB_FILE_NAME) } returns dbSourceFile
            every { packageName } returns "com.skyler.pokedexbinder"
        }

        every { database.openHelper } returns mockk<SupportSQLiteOpenHelper> {
            every { writableDatabase } returns sqliteDatabase
        }

        coEvery { binderRepository.getAllEntries() } returns emptyList()
        coEvery { secondaryBinderDao.getAll() } returns emptyList()
        coEvery { connectingArtRepository.getAllGroups() } returns emptyList()
        coEvery { connectingArtRepository.getAllSlots() } returns emptyList()
        coEvery { personalCollectionRepository.getAllCache() } returns emptyList()
        coEvery { personalCollectionRepository.getAllEntries() } returns emptyList()
        coEvery { unownBinderRepository.getAllEntries() } returns emptyList()

        val fakeSnapshot = BinderSnapshot(publishedAt = "2026-09-03T12:00:00+08:00", binders = emptyList())
        every {
            publishRepository.buildSnapshot(any(), any(), any(), any(), any(), any(), any(), any())
        } returns fakeSnapshot

        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk<Uri>(relaxed = true)

        exporter = BackupExporter(
            context, database, moshi, publishRepository,
            binderRepository, secondaryBinderDao, connectingArtRepository,
            personalCollectionRepository, unownBinderRepository
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(FileProvider::class)
    }

    @Test
    fun `export checkpoints the WAL before copying the db file`() = runTest {
        exporter.export()

        // Must go through query(), not execSQL() — PRAGMA wal_checkpoint returns a result row,
        // and execSQL() rejects any SQL that returns data.
        verify { sqliteDatabase.query("PRAGMA wal_checkpoint(FULL)") }
    }

    @Test
    fun `export copies the db file and writes a json envelope into filesDir exports`() = runTest {
        // export()'s final step builds a real android.content.Intent (ACTION_SEND_MULTIPLE) to
        // hand back as the share-sheet result — real Intent field assignment isn't usable in a
        // plain JVM unit test without Robolectric (not a dependency of this project), so that last
        // step's own Success/Failure result isn't asserted here. Everything before it (checkpoint,
        // db copy, envelope build, json write) already ran by the time that call is reached, so
        // this test verifies those file-level side effects directly instead.
        exporter.export()

        val exportDir = File(filesDir, "exports")
        val dbCopies = exportDir.listFiles { f -> f.name.endsWith(".db") }.orEmpty()
        val jsonCopies = exportDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        assertEquals(1, dbCopies.size)
        assertEquals(1, jsonCopies.size)
        assertEquals("fake-sqlite-bytes", dbCopies.single().readText())
        assertTrue(jsonCopies.single().readText().contains("\"roomSchemaVersion\":${PokedexDatabase.SCHEMA_VERSION}"))
    }

    @Test
    fun `export always includes every binder regardless of publish toggles`() = runTest {
        exporter.export()

        verify {
            publishRepository.buildSnapshot(
                emptyList(), emptyList(),
                match { it.publishPokedex && it.publishCardHistory },
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        }
    }

    @Test
    fun `export rotates filesDir exports down to the newest few pairs`() = runTest {
        // filesDir is never reclaimed by Android, so without rotation every Backup tap leaves
        // another full copy of the database behind forever.
        val exportDir = File(filesDir, "exports").apply { mkdirs() }
        val seeded = (1..6).map { i ->
            val db = File(exportDir, "pokedex-binder-2026010$i.db").apply { writeText("old $i") }
            val json = File(exportDir, "pokedex-binder-backup-2026010$i.json").apply { writeText("{}") }
            db.setLastModified(1_000_000L * i)
            json.setLastModified(1_000_000L * i)
            db.name
        }

        exporter.export()

        val remainingDb = exportDir.listFiles { f -> f.name.endsWith(".db") }.orEmpty().map { it.name }
        val remainingJson = exportDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name }
        assertEquals(5, remainingDb.size)
        assertEquals(5, remainingJson.size)
        // The two oldest seeded exports are gone; the just-written pair survived.
        assertTrue(seeded[0] !in remainingDb)
        assertTrue(seeded[1] !in remainingDb)
        assertTrue(remainingDb.any { it !in seeded })
    }

    @Test
    fun `export failure is surfaced as Failure, not thrown`() = runTest {
        every { database.openHelper } throws RuntimeException("db closed")

        val result = exporter.export()

        assertTrue(result is BackupExportResult.Failure)
        assertEquals("db closed", (result as BackupExportResult.Failure).message)
    }
}
