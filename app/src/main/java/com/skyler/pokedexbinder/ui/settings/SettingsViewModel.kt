package com.skyler.pokedexbinder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.repository.AppSettings
import com.skyler.pokedexbinder.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setShowRegional(v: Boolean) = viewModelScope.launch { settingsRepository.setShowRegional(v) }
    fun setShowMega(v: Boolean) = viewModelScope.launch { settingsRepository.setShowMega(v) }
    fun setShowGmax(v: Boolean) = viewModelScope.launch { settingsRepository.setShowGmax(v) }
    fun setShowSecondaryBinder(v: Boolean) = viewModelScope.launch { settingsRepository.setShowSecondaryBinder(v) }
    fun setUseCameraScanner(v: Boolean) = viewModelScope.launch { settingsRepository.setUseCameraScanner(v) }
    fun setGeminiApiKey(key: String) = viewModelScope.launch { settingsRepository.setGeminiApiKey(key) }
}
