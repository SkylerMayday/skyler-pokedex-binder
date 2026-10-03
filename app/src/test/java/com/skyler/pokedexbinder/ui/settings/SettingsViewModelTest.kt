package com.skyler.pokedexbinder.ui.settings

import com.skyler.pokedexbinder.repository.PublishSettingsRepository
import com.skyler.pokedexbinder.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val settingsRepository = mockk<SettingsRepository>()
    private val publishSettingsRepository = mockk<PublishSettingsRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settingsRepository.settings } returns emptyFlow()
        every { publishSettingsRepository.config } returns emptyFlow()
        coEvery { publishSettingsRepository.setPostToDiscord(any()) } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setPostToDiscord forwards the value to the publish settings repository`() = runTest {
        val vm = SettingsViewModel(settingsRepository, publishSettingsRepository)

        vm.setPostToDiscord(true)

        coVerify(exactly = 1) { publishSettingsRepository.setPostToDiscord(true) }
    }
}
