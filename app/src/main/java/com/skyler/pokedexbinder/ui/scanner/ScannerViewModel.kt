package com.skyler.pokedexbinder.ui.scanner

import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.OcrCardParser
import com.skyler.pokedexbinder.domain.SmartThresholdUseCase
import com.skyler.pokedexbinder.repository.CardSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

sealed class ScannerState {
    object Idle : ScannerState()
    object Scanning : ScannerState()
    data class HighConfidence(val card: TcgCard) : ScannerState()
    data class LowConfidence(val cards: List<TcgCard>) : ScannerState()
    data class Error(val message: String) : ScannerState()
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val cardSearchRepository: CardSearchRepository,
    private val ocrCardParser: OcrCardParser,
    private val smartThresholdUseCase: SmartThresholdUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<ScannerState>(ScannerState.Idle)
    val state: StateFlow<ScannerState> = _state

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun processImage(imageProxy: ImageProxy) {
        _state.value = ScannerState.Scanning
        viewModelScope.launch {
            try {
                val mediaImage = imageProxy.image ?: run {
                    imageProxy.close()
                    _state.value = ScannerState.Error("Failed to read image")
                    return@launch
                }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                val result = recognizer.process(image).await()
                imageProxy.close()

                val parsed = ocrCardParser.parse(result.text)
                if (parsed.cardName.isBlank()) {
                    _state.value = ScannerState.Error("Could not read card text. Try again.")
                    return@launch
                }

                val cards = if (parsed.cardNumber != null) {
                    cardSearchRepository.searchByNameAndNumber(parsed.cardName, parsed.cardNumber)
                } else {
                    cardSearchRepository.searchByName(parsed.cardName)
                }

                val confidence = smartThresholdUseCase.evaluate(cards, parsed.cardName, parsed.cardNumber)
                _state.value = if (confidence.isHighConfidence && confidence.topCard != null) {
                    ScannerState.HighConfidence(confidence.topCard)
                } else {
                    ScannerState.LowConfidence(cards)
                }
            } catch (e: Exception) {
                imageProxy.close()
                _state.value = ScannerState.Error(e.message ?: "Scan failed")
            }
        }
    }

    fun searchManually(query: String) {
        _state.value = ScannerState.Scanning
        viewModelScope.launch {
            try {
                val cards = cardSearchRepository.searchByName(query)
                _state.value = ScannerState.LowConfidence(cards)
            } catch (e: Exception) {
                _state.value = ScannerState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun reset() { _state.value = ScannerState.Idle }

    fun onCaptureError(message: String) {
        _state.value = ScannerState.Error(message)
    }

    override fun onCleared() {
        super.onCleared()
        recognizer.close()
    }
}
