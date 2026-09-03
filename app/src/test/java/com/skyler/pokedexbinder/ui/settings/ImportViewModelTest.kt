package com.skyler.pokedexbinder.ui.settings

import app.cash.turbine.test
import com.skyler.pokedexbinder.data.local.PokedexDatabase
import com.skyler.pokedexbinder.data.local.backup.BackupImporter
import com.skyler.pokedexbinder.data.local.backup.ImportPrepareResult
import com.skyler.pokedexbinder.data.local.backup.ImportPreview
import com.skyler.pokedexbinder.publish.model.BackupEnvelope
import com.skyler.pokedexbinder.publish.model.BinderSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Regression coverage for the Cancel-path temp-file leak (debug-report bug #2): tapping Cancel on
 * the confirmation dialog calls [ImportViewModel.dismiss], which must delegate to
 * [BackupImporter.discardPreview] for a pending `.db` preview instead of silently dropping the
 * temp-file reference back to [ImportUiState.Idle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {

    private val backupImporter = mockk<BackupImporter>()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `dismiss from Idle does not call discardPreview`() = runTest {
        val viewModel = ImportViewModel(backupImporter)

        viewModel.dismiss()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { backupImporter.discardPreview(any()) }
    }

    @Test
    fun `dismiss from NeedsConfirmation with a db preview discards the pending temp file`() = runTest {
        val tempFile = Files.createTempFile("import-viewmodel-test", ".db").toFile()
        val preview = ImportPreview.Db(tempFile, PokedexDatabase.SCHEMA_VERSION)
        coEvery { backupImporter.prepare(any()) } returns ImportPrepareResult.Ready(preview)
        coEvery { backupImporter.discardPreview(preview) } returns Unit
        val viewModel = ImportViewModel(backupImporter)

        viewModel.state.test {
            assertTrue(awaitItem() is ImportUiState.Idle)

            viewModel.onFilesPicked(listOf(mockk(relaxed = true)))
            assertTrue(awaitItem() is ImportUiState.Preparing)
            dispatcher.scheduler.runCurrent()
            assertTrue(awaitItem() is ImportUiState.NeedsConfirmation)

            viewModel.dismiss()
            dispatcher.scheduler.runCurrent()
            assertTrue(awaitItem() is ImportUiState.Idle)
        }
        coVerify { backupImporter.discardPreview(preview) }
    }

    @Test
    fun `a failed db swap surfaces an Error state instead of escaping viewModelScope`() = runTest {
        val tempFile = Files.createTempFile("import-viewmodel-test", ".db").toFile()
        val preview = ImportPreview.Db(tempFile, PokedexDatabase.SCHEMA_VERSION)
        coEvery { backupImporter.prepare(any()) } returns ImportPrepareResult.Ready(preview)
        coEvery { backupImporter.applyDb(preview) } throws
            IOException("Could not move the imported database into place — your data is unchanged")
        val viewModel = ImportViewModel(backupImporter)

        viewModel.state.test {
            assertTrue(awaitItem() is ImportUiState.Idle)

            viewModel.onFilesPicked(listOf(mockk(relaxed = true)))
            assertTrue(awaitItem() is ImportUiState.Preparing)
            dispatcher.scheduler.runCurrent()
            assertTrue(awaitItem() is ImportUiState.NeedsConfirmation)

            viewModel.confirmImport()
            dispatcher.scheduler.runCurrent()
            assertTrue(awaitItem() is ImportUiState.Applying)
            val error = awaitItem()
            assertTrue(error is ImportUiState.Error)
            assertTrue((error as ImportUiState.Error).message.contains("your data is unchanged"))
        }
    }

    @Test
    fun `dismiss from NeedsConfirmation with a json preview still delegates to discardPreview`() = runTest {
        val snapshot = BinderSnapshot(publishedAt = "2026-09-03T12:00:00+08:00", binders = emptyList())
        val preview = ImportPreview.Json(
            BackupEnvelope("1.0", PokedexDatabase.SCHEMA_VERSION, "2026-09-03T12:00:00+08:00", snapshot)
        )
        coEvery { backupImporter.prepare(any()) } returns ImportPrepareResult.Ready(preview)
        coEvery { backupImporter.discardPreview(preview) } returns Unit
        val viewModel = ImportViewModel(backupImporter)

        viewModel.state.test {
            assertTrue(awaitItem() is ImportUiState.Idle)

            viewModel.onFilesPicked(listOf(mockk(relaxed = true)))
            assertTrue(awaitItem() is ImportUiState.Preparing)
            dispatcher.scheduler.runCurrent()
            assertTrue(awaitItem() is ImportUiState.NeedsConfirmation)

            viewModel.dismiss()
            dispatcher.scheduler.runCurrent()
            assertTrue(awaitItem() is ImportUiState.Idle)
        }
        // discardPreview is a no-op for Json inside BackupImporter itself (covered by
        // BackupImporterTest); the ViewModel's job is only to always delegate, unconditionally.
        coVerify { backupImporter.discardPreview(preview) }
    }
}
