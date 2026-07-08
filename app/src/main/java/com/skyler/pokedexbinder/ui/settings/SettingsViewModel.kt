package com.skyler.pokedexbinder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.repository.AppSettings
import com.skyler.pokedexbinder.repository.PublishConfig
import com.skyler.pokedexbinder.repository.PublishSettingsRepository
import com.skyler.pokedexbinder.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val publishSettingsRepository: PublishSettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val publishConfig: StateFlow<PublishConfig> = publishSettingsRepository.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PublishConfig())

    fun setShowRegional(v: Boolean) = viewModelScope.launch { settingsRepository.setShowRegional(v) }
    fun setShowAlternateForms(v: Boolean) = viewModelScope.launch { settingsRepository.setShowAlternateForms(v) }
    fun setShowMega(v: Boolean) = viewModelScope.launch { settingsRepository.setShowMega(v) }
    fun setShowGmax(v: Boolean) = viewModelScope.launch { settingsRepository.setShowGmax(v) }
    fun setUseCameraScanner(v: Boolean) = viewModelScope.launch { settingsRepository.setUseCameraScanner(v) }
    fun setDarkMode(v: Boolean) = viewModelScope.launch { settingsRepository.setDarkMode(v) }
    fun setGeminiApiKey(key: String) = viewModelScope.launch { settingsRepository.setGeminiApiKey(key) }

    fun setGithubOwner(v: String) = viewModelScope.launch { publishSettingsRepository.setGithubOwner(v) }
    fun setGithubRepo(v: String) = viewModelScope.launch { publishSettingsRepository.setGithubRepo(v) }
    fun setGithubPat(v: String) = viewModelScope.launch { publishSettingsRepository.setGithubPat(v) }
    fun setDiscordWebhookUrl(v: String) = viewModelScope.launch { publishSettingsRepository.setDiscordWebhookUrl(v) }
    fun setPublishPokedex(v: Boolean) = viewModelScope.launch { publishSettingsRepository.setPublishPokedex(v) }
    fun setPublishCardHistory(v: Boolean) = viewModelScope.launch { publishSettingsRepository.setPublishCardHistory(v) }
}
