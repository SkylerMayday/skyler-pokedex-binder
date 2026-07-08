package com.skyler.pokedexbinder.ui.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.domain.RestoreBinderUseCase
import com.skyler.pokedexbinder.publish.RestoreResult
import com.skyler.pokedexbinder.publish.RestoreStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ticker interval for the live elapsed-time readout while a restore is running. */
private const val ELAPSED_TICK_MS = 200L

sealed interface RestoreUiState {
    data object Idle : RestoreUiState
    data class Running(val step: RestoreStep, val elapsedMs: Long) : RestoreUiState
    data class Done(
        val restoredCount: Int, val clearedCount: Int, val skippedCount: Int, val elapsedMs: Long
    ) : RestoreUiState
    data object NoSnapshot : RestoreUiState
    data class Error(val step: RestoreStep, val message: String, val elapsedMs: Long) : RestoreUiState
}

@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val restoreBinderUseCase: RestoreBinderUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    private var tickerJob: Job? = null

    fun startRestore() {
        if (_state.value is RestoreUiState.Running) return

        val start = System.currentTimeMillis()
        _state.value = RestoreUiState.Running(RestoreStep.Fetching, 0L)

        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(ELAPSED_TICK_MS)
                val current = _state.value
                if (current is RestoreUiState.Running) {
                    _state.value = current.copy(elapsedMs = System.currentTimeMillis() - start)
                } else {
                    break
                }
            }
        }

        viewModelScope.launch {
            val result = restoreBinderUseCase.restore { step ->
                val elapsed = System.currentTimeMillis() - start
                _state.value = RestoreUiState.Running(step, elapsed)
            }

            tickerJob?.cancel()
            val elapsed = System.currentTimeMillis() - start

            _state.value = when (result) {
                is RestoreResult.Success -> RestoreUiState.Done(
                    result.restoredCount, result.clearedCount, result.skippedCount, elapsed
                )
                is RestoreResult.NoSnapshot -> RestoreUiState.NoSnapshot
                is RestoreResult.Failure -> RestoreUiState.Error(result.step, result.message, elapsed)
            }
        }
    }

    fun dismiss() {
        tickerJob?.cancel()
        _state.value = RestoreUiState.Idle
    }
}
