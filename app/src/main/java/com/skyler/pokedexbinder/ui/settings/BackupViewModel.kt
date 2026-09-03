package com.skyler.pokedexbinder.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.backup.BackupExportResult
import com.skyler.pokedexbinder.data.local.backup.BackupExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A fast local op (checkpoint + file copy + JSON write) — simpler stepped-ticker not needed. */
sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Loading : BackupUiState
    data class Success(val shareIntent: Intent) : BackupUiState
    data class Error(val message: String) : BackupUiState
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupExporter: BackupExporter
) : ViewModel() {

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun startBackup() {
        if (_state.value is BackupUiState.Loading) return
        _state.value = BackupUiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = backupExporter.export()) {
                is BackupExportResult.Success -> BackupUiState.Success(result.shareIntent)
                is BackupExportResult.Failure -> BackupUiState.Error(result.message)
            }
        }
    }

    fun dismiss() {
        _state.value = BackupUiState.Idle
    }
}
