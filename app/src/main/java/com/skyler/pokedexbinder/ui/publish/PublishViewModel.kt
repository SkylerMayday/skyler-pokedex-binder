package com.skyler.pokedexbinder.ui.publish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.domain.PublishBinderUseCase
import com.skyler.pokedexbinder.publish.PublishResult
import com.skyler.pokedexbinder.publish.PublishStep
import com.skyler.pokedexbinder.publish.model.PublishDiff
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ticker interval for the live elapsed-time readout while a publish is running. */
private const val ELAPSED_TICK_MS = 200L

sealed interface PublishUiState {
    data object Idle : PublishUiState
    data class Running(val step: PublishStep, val elapsedMs: Long) : PublishUiState
    data class Done(val diff: PublishDiff, val elapsedMs: Long) : PublishUiState
    data object NoChanges : PublishUiState
    data class Error(val step: PublishStep, val message: String, val elapsedMs: Long) : PublishUiState
}

@HiltViewModel
class PublishViewModel @Inject constructor(
    private val publishBinderUseCase: PublishBinderUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<PublishUiState>(PublishUiState.Idle)
    val state: StateFlow<PublishUiState> = _state.asStateFlow()

    private var tickerJob: Job? = null

    fun startPublish() {
        if (_state.value is PublishUiState.Running) return

        val start = System.currentTimeMillis()
        _state.value = PublishUiState.Running(PublishStep.FetchingCurrent, 0L)

        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(ELAPSED_TICK_MS)
                val current = _state.value
                if (current is PublishUiState.Running) {
                    _state.value = current.copy(elapsedMs = System.currentTimeMillis() - start)
                } else {
                    break
                }
            }
        }

        viewModelScope.launch {
            val result = publishBinderUseCase.publish { step ->
                val elapsed = System.currentTimeMillis() - start
                _state.value = PublishUiState.Running(step, elapsed)
            }

            tickerJob?.cancel()
            val elapsed = System.currentTimeMillis() - start

            _state.value = when (result) {
                is PublishResult.Success -> PublishUiState.Done(result.diff, elapsed)
                is PublishResult.NoChanges -> PublishUiState.NoChanges
                is PublishResult.Failure -> PublishUiState.Error(result.step, result.message, elapsed)
            }
        }
    }

    fun dismiss() {
        tickerJob?.cancel()
        _state.value = PublishUiState.Idle
    }
}
