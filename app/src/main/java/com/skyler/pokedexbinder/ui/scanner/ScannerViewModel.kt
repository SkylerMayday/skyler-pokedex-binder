package com.skyler.pokedexbinder.ui.scanner

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.AssignCardUseCase
import com.skyler.pokedexbinder.domain.GeminiCardScanner
import com.skyler.pokedexbinder.domain.PerceptualHasher
import com.skyler.pokedexbinder.domain.RateLimitException
import com.skyler.pokedexbinder.domain.SmartThresholdUseCase
import com.skyler.pokedexbinder.repository.CardSearchRepository
import com.skyler.pokedexbinder.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ScannerState {
    object Idle : ScannerState()
    object Scanning : ScannerState()
    object NoApiKey : ScannerState()
    object RateLimited : ScannerState()
    data class HighConfidence(val card: TcgCard) : ScannerState()
    data class LowConfidence(val cards: List<TcgCard>) : ScannerState()
    data class Error(val message: String) : ScannerState()
    data class Success(val card: TcgCard, val slotName: String) : ScannerState()
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val geminiCardScanner: GeminiCardScanner,
    private val cardSearchRepository: CardSearchRepository,
    private val perceptualHasher: PerceptualHasher,
    private val smartThresholdUseCase: SmartThresholdUseCase,
    private val assignCardUseCase: AssignCardUseCase,
    private val secondaryBinderDao: SecondaryBinderDao,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val slotId: String = savedStateHandle.get<String>("slotId") ?: ""
    private val slotName: String = savedStateHandle.get<String>("pokemonName") ?: ""
    private val isSecondary: Boolean = savedStateHandle.get<Boolean>("isSecondary") ?: false

    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)
    val state: StateFlow<ScannerState> = _state

    private var capturedBitmap: Bitmap? = null

    fun processImage(imageProxy: ImageProxy) {
        _state.value = ScannerState.Scanning
        viewModelScope.launch {
            try {
                val apiKey = settingsRepository.getGeminiApiKey()
                if (apiKey.isBlank()) {
                    _state.value = ScannerState.NoApiKey
                    return@launch
                }
                val bitmap = imageProxy.toBitmap()
                capturedBitmap = bitmap

                val parsed = geminiCardScanner.scan(bitmap, apiKey)
                val candidates = cardSearchRepository.searchByParsedInfo(parsed)

                if (candidates.isEmpty()) {
                    _state.value = ScannerState.LowConfidence(emptyList())
                    return@launch
                }

                val best = perceptualHasher.findBestMatch(candidates, bitmap) ?: candidates.first()
                val confidence = smartThresholdUseCase.evaluate(candidates, best.name, best.number)
                _state.value = if (confidence.isHighConfidence && confidence.topCard != null) {
                    ScannerState.HighConfidence(confidence.topCard)
                } else {
                    ScannerState.LowConfidence(candidates)
                }
            } catch (e: RateLimitException) {
                _state.value = ScannerState.RateLimited
            } catch (e: Exception) {
                _state.value = ScannerState.Error(e.message ?: "Scan failed")
            } finally {
                imageProxy.close()
            }
        }
    }

    fun confirmCard(card: TcgCard) {
        viewModelScope.launch {
            if (isSecondary || slotId.isBlank()) {
                // Secondary binder, or global scan with no specific slot → add to secondary
                secondaryBinderDao.insertAtEnd(
                    SecondaryBinderEntry(
                        pokemonId = card.pokemonNames.firstOrNull() ?: "",
                        pokemonName = card.name,
                        cardId = card.id,
                        cardImageUrl = card.imageUrl
                    )
                )
                _state.value = ScannerState.Success(card, "Secondary Binder")
            } else {
                assignCardUseCase.assign(slotId, card)
                _state.value = ScannerState.Success(card, slotName)
            }
        }
    }

    fun reset() { _state.value = ScannerState.Idle }

    fun onCaptureError(message: String) {
        _state.value = ScannerState.Error(message)
    }
}
