package com.skyler.pokedexbinder.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.ResetSummary
import com.skyler.pokedexbinder.data.local.backup.BackupExportResult
import com.skyler.pokedexbinder.data.local.backup.BackupExporter
import com.skyler.pokedexbinder.domain.ResetCollectionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Idle -> Confirm1 -> Exporting -> Confirm2 -> Wiping -> Done | Error. Wiping is only reachable from Confirm2. */
sealed interface ResetUiState {
    data object Idle : ResetUiState
    data class Confirm1(val summary: ResetSummary) : ResetUiState
    data object Exporting : ResetUiState
    /**
     * Reached only after the forced export succeeded; [shareIntent] lets the user re-share the file.
     * [shareSheetShown] makes the automatic share sheet one-shot, so recreation (rotation) can't reopen it.
     */
    data class Confirm2(val shareIntent: Intent, val shareSheetShown: Boolean = false) : ResetUiState
    data object Wiping : ResetUiState
    data object Done : ResetUiState
    data class Error(val message: String) : ResetUiState
}

const val RESET_CONFIRM_WORD = "RESET"

@HiltViewModel
class ResetCollectionViewModel @Inject constructor(
    private val resetCollection: ResetCollectionUseCase,
    private val backupExporter: BackupExporter
) : ViewModel() {

    private val _state = MutableStateFlow<ResetUiState>(ResetUiState.Idle)
    val state: StateFlow<ResetUiState> = _state.asStateFlow()

    fun begin() {
        if (_state.value != ResetUiState.Idle) return
        viewModelScope.launch {
            _state.value = try {
                ResetUiState.Confirm1(resetCollection.summary())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResetUiState.Error(e.message ?: "Couldn't read collection counts")
            }
        }
    }

    /** Forced export. A failure goes to Error with zero writes. */
    fun exportAndContinue() {
        if (_state.value !is ResetUiState.Confirm1) return
        _state.value = ResetUiState.Exporting
        viewModelScope.launch {
            _state.value = when (val result = backupExporter.export()) {
                is BackupExportResult.Success -> ResetUiState.Confirm2(result.shareIntent)
                is BackupExportResult.Failure -> ResetUiState.Error("Backup failed, nothing was reset: ${result.message}")
            }
        }
    }

    /** Called once the screen has opened the automatic share sheet for Confirm2. */
    fun onShareSheetShown() {
        val current = _state.value as? ResetUiState.Confirm2 ?: return
        _state.value = current.copy(shareSheetShown = true)
    }

    /** Requires Confirm2 and exactly [RESET_CONFIRM_WORD]; anything else is a no-op. */
    fun confirmReset(typed: String) {
        if (_state.value !is ResetUiState.Confirm2 || typed != RESET_CONFIRM_WORD) return
        _state.value = ResetUiState.Wiping
        viewModelScope.launch {
            _state.value = try {
                resetCollection.reset()
                ResetUiState.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResetUiState.Error(e.message ?: "Reset failed")
            }
        }
    }

    fun dismiss() {
        if (_state.value == ResetUiState.Exporting || _state.value == ResetUiState.Wiping) return
        _state.value = ResetUiState.Idle
    }
}
