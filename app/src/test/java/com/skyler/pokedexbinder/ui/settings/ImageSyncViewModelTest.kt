package com.skyler.pokedexbinder.ui.settings

import com.skyler.pokedexbinder.domain.ImageSyncResult
import com.skyler.pokedexbinder.domain.SyncCardImagesUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageSyncViewModelTest {

    private val useCase = mockk<SyncCardImagesUseCase>()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `startSync ends in Done with the use case result`() = runTest {
        val result = ImageSyncResult(5, 3, 1, 1, 0)
        coEvery { useCase.sync(any()) } returns result
        val vm = ImageSyncViewModel(useCase)

        vm.startSync()

        assertEquals(ImageSyncUiState.Done(result), vm.state.value)
    }

    @Test
    fun `a throwing sync becomes Error`() = runTest {
        coEvery { useCase.sync(any()) } throws IllegalStateException("db closed")
        val vm = ImageSyncViewModel(useCase)

        vm.startSync()

        assertEquals(ImageSyncUiState.Error("db closed"), vm.state.value)
    }
}
