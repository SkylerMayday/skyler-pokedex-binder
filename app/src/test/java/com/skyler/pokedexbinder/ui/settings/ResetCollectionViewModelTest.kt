package com.skyler.pokedexbinder.ui.settings

import android.content.Intent
import com.skyler.pokedexbinder.data.local.ResetSummary
import com.skyler.pokedexbinder.data.local.backup.BackupExportResult
import com.skyler.pokedexbinder.data.local.backup.BackupExporter
import com.skyler.pokedexbinder.domain.ResetCollectionUseCase
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResetCollectionViewModelTest {

    private val useCase = mockk<ResetCollectionUseCase>()
    private val exporter = mockk<BackupExporter>()
    private val summary = ResetSummary(1, 2, 3, 4, 5)
    private val intent = mockk<Intent>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { useCase.summary() } returns summary
        coJustRun { useCase.reset() }
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = ResetCollectionViewModel(useCase, exporter)

    private fun ResetCollectionViewModel.toConfirm2() {
        coEvery { exporter.export() } returns BackupExportResult.Success(intent)
        begin()
        exportAndContinue()
    }

    @Test
    fun `begin loads the summary into Confirm1`() = runTest {
        val vm = vm()
        vm.begin()
        assertEquals(ResetUiState.Confirm1(summary), vm.state.value)
    }

    @Test
    fun `export failure goes to Error and never resets`() = runTest {
        coEvery { exporter.export() } returns BackupExportResult.Failure("disk full")
        val vm = vm()
        vm.begin()
        vm.exportAndContinue()

        assertTrue(vm.state.value is ResetUiState.Error)
        vm.confirmReset("RESET")
        coVerify(exactly = 0) { useCase.reset() }
    }

    @Test
    fun `reset needs the exact typed word`() = runTest {
        val vm = vm()
        vm.toConfirm2()

        vm.confirmReset("reset")
        vm.confirmReset("RESET ")
        vm.confirmReset("")
        assertEquals(ResetUiState.Confirm2(intent), vm.state.value)
        coVerify(exactly = 0) { useCase.reset() }

        vm.confirmReset("RESET")
        assertEquals(ResetUiState.Done, vm.state.value)
        coVerify(exactly = 1) { useCase.reset() }
    }

    @Test
    fun `reset cannot be reached from Confirm1 or Idle`() = runTest {
        val vm = vm()
        vm.confirmReset("RESET")
        vm.begin()
        vm.confirmReset("RESET")

        coVerify(exactly = 0) { useCase.reset() }
    }

    @Test
    fun `cancel at either confirmation writes nothing`() = runTest {
        val vm = vm()
        vm.toConfirm2()
        vm.dismiss()

        assertEquals(ResetUiState.Idle, vm.state.value)
        coVerify(exactly = 0) { useCase.reset() }
    }

    @Test
    fun `the automatic share sheet is one-shot and the reset still works after it`() = runTest {
        val vm = vm()
        vm.toConfirm2()
        assertEquals(ResetUiState.Confirm2(intent, shareSheetShown = false), vm.state.value)

        vm.onShareSheetShown()
        assertEquals(ResetUiState.Confirm2(intent, shareSheetShown = true), vm.state.value)

        vm.confirmReset("RESET")
        assertEquals(ResetUiState.Done, vm.state.value)
    }

    @Test
    fun `onShareSheetShown outside Confirm2 changes nothing`() = runTest {
        val vm = vm()
        vm.onShareSheetShown()
        assertEquals(ResetUiState.Idle, vm.state.value)
    }

    @Test
    fun `a failing reset surfaces Error`() = runTest {
        coEvery { useCase.reset() } throws IllegalStateException("db locked")
        val vm = vm()
        vm.toConfirm2()
        vm.confirmReset("RESET")

        assertEquals(ResetUiState.Error("db locked"), vm.state.value)
    }
}
