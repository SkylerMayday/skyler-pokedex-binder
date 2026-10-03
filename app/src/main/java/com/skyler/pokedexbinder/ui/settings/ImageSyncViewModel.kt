package com.skyler.pokedexbinder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.domain.ImageSyncResult
import com.skyler.pokedexbinder.domain.SyncCardImagesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImageSyncUiState {
    data object Idle : ImageSyncUiState
    data class Running(val done: Int, val total: Int) : ImageSyncUiState
    data class Done(val result: ImageSyncResult) : ImageSyncUiState
    data class Error(val message: String) : ImageSyncUiState
}

@HiltViewModel
class ImageSyncViewModel @Inject constructor(
    private val syncCardImages: SyncCardImagesUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<ImageSyncUiState>(ImageSyncUiState.Idle)
    val state: StateFlow<ImageSyncUiState> = _state.asStateFlow()

    fun startSync() {
        if (_state.value is ImageSyncUiState.Running) return
        _state.value = ImageSyncUiState.Running(0, 0)
        viewModelScope.launch {
            _state.value = try {
                ImageSyncUiState.Done(syncCardImages.sync { done, total ->
                    _state.value = ImageSyncUiState.Running(done, total)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImageSyncUiState.Error(e.message ?: "Image sync failed")
            }
        }
    }
}
