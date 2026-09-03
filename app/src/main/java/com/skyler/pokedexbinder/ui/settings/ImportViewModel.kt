package com.skyler.pokedexbinder.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.backup.BackupImporter
import com.skyler.pokedexbinder.data.local.backup.ImportPrepareResult
import com.skyler.pokedexbinder.data.local.backup.ImportPreview
import com.skyler.pokedexbinder.publish.RestoreResult
import com.skyler.pokedexbinder.publish.RestoreStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Preparing : ImportUiState
    /** Nothing is touched yet — gated on the caller's own confirmation dialog. */
    data class NeedsConfirmation(val preview: ImportPreview) : ImportUiState
    data class Applying(val step: RestoreStep) : ImportUiState
    data class Done(val restoredCount: Int, val clearedCount: Int, val skippedCount: Int) : ImportUiState
    data class Rejected(val message: String) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val backupImporter: BackupImporter
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** Only reads/parses the picked files and version-gates them — touches no local app data. */
    fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.value = ImportUiState.Preparing
        viewModelScope.launch {
            _state.value = when (val result = backupImporter.prepare(uris)) {
                is ImportPrepareResult.Ready -> ImportUiState.NeedsConfirmation(result.preview)
                is ImportPrepareResult.Rejected -> ImportUiState.Rejected(result.message)
            }
        }
    }

    /** Called only after the caller's own "this replaces your data" confirmation dialog. */
    fun confirmImport() {
        val current = _state.value
        if (current !is ImportUiState.NeedsConfirmation) return

        viewModelScope.launch {
            when (val preview = current.preview) {
                is ImportPreview.Json -> {
                    _state.value = ImportUiState.Applying(RestoreStep.Restoring)
                    val result = backupImporter.applyJson(preview) { step ->
                        _state.value = ImportUiState.Applying(step)
                    }
                    _state.value = when (result) {
                        is RestoreResult.Success -> ImportUiState.Done(
                            result.restoredCount, result.clearedCount, result.skippedCount
                        )
                        is RestoreResult.NoSnapshot -> ImportUiState.Error("Backup file had no binder data to restore")
                        is RestoreResult.Failure -> ImportUiState.Error(result.message)
                    }
                }
                is ImportPreview.Db -> {
                    _state.value = ImportUiState.Applying(RestoreStep.Restoring)
                    try {
                        // Replaces the live DB file and kills the process to force a cold restart —
                        // this call does not return on a real device, on success OR failure
                        // (applyDb forces the same restart on a failed swap too, since the live
                        // database is already closed by that point either way — see its own doc).
                        backupImporter.applyDb(preview)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Backstop only: applyDb isn't expected to reach here in practice (it
                        // restarts on its own failure path), but if something above it throws
                        // before that point, surface it rather than escaping viewModelScope with
                        // no dialog.
                        _state.value = ImportUiState.Error(
                            e.message ?: "Could not replace the database file — your data is unchanged"
                        )
                    }
                }
            }
        }
    }

    /** Also used as the confirmation dialog's Cancel action — must discard any pending temp file. */
    fun dismiss() {
        val current = _state.value
        if (current is ImportUiState.NeedsConfirmation) {
            // NonCancellable: leaving Settings right after Cancel clears the ViewModel, and a
            // cancelled cleanup would leak the temp file this call exists to delete.
            viewModelScope.launch(NonCancellable) { backupImporter.discardPreview(current.preview) }
        }
        _state.value = ImportUiState.Idle
    }
}
