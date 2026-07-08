package com.skyler.pokedexbinder.ui.publish

import app.cash.turbine.test
import com.skyler.pokedexbinder.domain.PublishBinderUseCase
import com.skyler.pokedexbinder.publish.PublishResult
import com.skyler.pokedexbinder.publish.PublishStep
import com.skyler.pokedexbinder.publish.model.PublishDiff
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublishViewModelTest {

    private val useCase = mockk<PublishBinderUseCase>()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun diff(added: Int = 1) = PublishDiff(
        deltas = emptyList(), isFirstPublish = false, pokedexComplete = added, pokedexTotal = 1025
    )

    @Test
    fun `startPublish emits Running then Done on success`() = runTest {
        coEvery { useCase.publish(any()) } coAnswers {
            val onStep = firstArg<(PublishStep) -> Unit>()
            onStep(PublishStep.FetchingCurrent)
            onStep(PublishStep.Uploading)
            onStep(PublishStep.NotifyingDiscord)
            PublishResult.Success(diff(), 500L)
        }
        val viewModel = PublishViewModel(useCase)

        viewModel.state.test {
            assertEqualsState(PublishUiState.Idle, awaitItem())
            viewModel.startPublish()
            dispatcher.scheduler.runCurrent()

            // Drain intermediate Running states, then assert terminal Done state.
            var last: PublishUiState = awaitItem()
            while (last is PublishUiState.Running) {
                last = awaitItem()
            }
            assert(last is PublishUiState.Done) { "Expected Done, got $last" }
        }
    }

    @Test
    fun `startPublish emits Error with correct step on failure`() = runTest {
        coEvery { useCase.publish(any()) } coAnswers {
            val onStep = firstArg<(PublishStep) -> Unit>()
            onStep(PublishStep.FetchingCurrent)
            onStep(PublishStep.Uploading)
            PublishResult.Failure(PublishStep.Uploading, "GitHub upload of binder.json failed: HTTP 500")
        }
        val viewModel = PublishViewModel(useCase)

        viewModel.state.test {
            awaitItem() // Idle
            viewModel.startPublish()
            dispatcher.scheduler.runCurrent()

            var last: PublishUiState = awaitItem()
            while (last is PublishUiState.Running) {
                last = awaitItem()
            }
            assert(last is PublishUiState.Error) { "Expected Error, got $last" }
            val error = last as PublishUiState.Error
            assert(error.step == PublishStep.Uploading)
        }
    }

    @Test
    fun `startPublish emits NoChanges when nothing changed`() = runTest {
        coEvery { useCase.publish(any()) } coAnswers {
            val onStep = firstArg<(PublishStep) -> Unit>()
            onStep(PublishStep.FetchingCurrent)
            onStep(PublishStep.NoChanges)
            PublishResult.NoChanges
        }
        val viewModel = PublishViewModel(useCase)

        viewModel.state.test {
            awaitItem() // Idle
            viewModel.startPublish()
            dispatcher.scheduler.runCurrent()

            var last: PublishUiState = awaitItem()
            while (last is PublishUiState.Running) {
                last = awaitItem()
            }
            assert(last is PublishUiState.NoChanges) { "Expected NoChanges, got $last" }
        }
    }

    private fun assertEqualsState(expected: PublishUiState, actual: PublishUiState) {
        assert(expected == actual) { "Expected $expected but was $actual" }
    }
}
