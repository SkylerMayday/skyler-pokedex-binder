package com.skyler.pokedexbinder.ui.quickscan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.AssignCardUseCase
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.CardSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class QuickScanState {
    object Idle : QuickScanState()
    object Searching : QuickScanState()
    data class CardSelection(val cards: List<TcgCard>) : QuickScanState()
    data class CardConfirm(val card: TcgCard, val allCards: List<TcgCard>, val isReplace: Boolean) : QuickScanState()
    data class SlotSelection(val card: TcgCard, val slots: List<PokemonSlot>) : QuickScanState()
    data class Success(val card: TcgCard, val slotName: String) : QuickScanState()
    data class NoSlot(val card: TcgCard) : QuickScanState()
    data class NotFound(val query: String) : QuickScanState()
    data class Error(val message: String) : QuickScanState()
    /** User wants to manually enter a card that isn't in the database. */
    data class ManualEntry(val prefillName: String) : QuickScanState()
}

@HiltViewModel
class QuickScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val cardSearchRepository: CardSearchRepository,
    private val binderRepository: BinderRepository,
    private val secondaryBinderDao: SecondaryBinderDao,
    private val assignCardUseCase: AssignCardUseCase
) : ViewModel() {

    private val targetSlotId: String = savedStateHandle.get<String>("slotId") ?: ""
    private val isReplace: Boolean = savedStateHandle.get<Boolean>("replacing") ?: false

    private val _state = MutableStateFlow<QuickScanState>(QuickScanState.Idle)
    val state: StateFlow<QuickScanState> = _state

    private var lastQuery: String = ""

    /** Display name for the slot, set after DB lookup. Used in success/confirm messages. */
    private var targetSlotName: String = ""

    init {
        if (targetSlotId.isNotBlank()) {
            viewModelScope.launch {
                val slot = binderRepository.getSlotByPokemonId(targetSlotId)
                if (slot != null) {
                    targetSlotName = slot.name
                    searchForSlot(slot.effectiveSearchName)
                }
            }
        }
    }

    /** Auto-search triggered by tapping a slot. Receives effectiveSearchName from DB lookup. */
    private fun searchForSlot(slotName: String) {
        lastQuery = slotName
        _state.value = QuickScanState.Searching
        viewModelScope.launch {
            try {
                val cards = cardSearchRepository.searchByName(slotName)
                _state.value = if (cards.isEmpty()) QuickScanState.NotFound(slotName)
                else QuickScanState.CardSelection(cards)
            } catch (e: Exception) {
                _state.value = QuickScanState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        lastQuery = query
        _state.value = QuickScanState.Searching
        viewModelScope.launch {
            try {
                val trimmed = query.trim()
                val dexNum = trimmed.toIntOrNull()
                val cards = when {
                    dexNum != null -> cardSearchRepository.searchByDexNumber(dexNum)
                    isPromoNumber(trimmed) -> cardSearchRepository.searchByNumber(trimmed)
                        .ifEmpty { cardSearchRepository.searchByName(trimmed) }
                    else -> cardSearchRepository.searchByName(trimmed)
                }
                _state.value = if (cards.isEmpty()) QuickScanState.NotFound(query)
                else QuickScanState.CardSelection(cards)
            } catch (e: Exception) {
                _state.value = QuickScanState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun retry() {
        if (lastQuery.isNotBlank()) search(lastQuery) else _state.value = QuickScanState.Idle
    }

    fun selectCard(card: TcgCard) {
        if (targetSlotId.isNotBlank()) {
            val currentCards = (_state.value as? QuickScanState.CardSelection)?.cards ?: listOf(card)
            _state.value = QuickScanState.CardConfirm(card, currentCards, isReplace)
            return
        }
        _state.value = QuickScanState.Searching
        viewModelScope.launch {
            val allSlots = binderRepository.getAllSlots()
            val matching = findMatchingSlots(allSlots, card)
            _state.value = when {
                matching.isEmpty() -> QuickScanState.NoSlot(card)
                matching.size == 1 -> {
                    assignCardUseCase.assign(matching[0].id, card)
                    QuickScanState.Success(card, matching[0].name)
                }
                else -> QuickScanState.SlotSelection(card, matching)
            }
        }
    }

    fun confirmCard(card: TcgCard) {
        viewModelScope.launch {
            assignCardUseCase.assign(targetSlotId, card)
            _state.value = QuickScanState.Success(card, targetSlotName)
        }
    }

    fun wrongCard(allCards: List<TcgCard>) {
        _state.value = QuickScanState.CardSelection(allCards)
    }

    fun assignToSlot(slot: PokemonSlot, card: TcgCard) {
        viewModelScope.launch {
            assignCardUseCase.assign(slot.id, card)
            _state.value = QuickScanState.Success(card, slot.name)
        }
    }

    fun addToSecondary(card: TcgCard) {
        viewModelScope.launch {
            secondaryBinderDao.insertAtEnd(
                SecondaryBinderEntry(
                    pokemonId = card.pokemonNames.firstOrNull() ?: "",
                    pokemonName = card.name,
                    cardId = card.id,
                    cardImageUrl = card.imageUrl
                )
            )
            _state.value = QuickScanState.Success(card, "Secondary Binder")
        }
    }

    fun showManualEntry() {
        _state.value = QuickScanState.ManualEntry(prefillName = lastQuery)
    }

    fun confirmCustomCard(name: String, setName: String, number: String, imageUrl: String) {
        val card = TcgCard(
            id = "custom_${System.currentTimeMillis()}",
            name = name,
            number = number,
            setName = setName,
            imageUrl = imageUrl,
            pokemonNames = listOf(name)
        )
        if (targetSlotId.isNotBlank()) {
            viewModelScope.launch {
                assignCardUseCase.assign(targetSlotId, card)
                _state.value = QuickScanState.Success(card, targetSlotName)
            }
        } else {
            // No slot pre-selected — let user pick
            _state.value = QuickScanState.CardSelection(listOf(card))
        }
    }

    fun reset() { _state.value = QuickScanState.Idle }

    private fun findMatchingSlots(slots: List<PokemonSlot>, card: TcgCard): List<PokemonSlot> =
        slots.filter { slot ->
            card.pokemonNames.any { name ->
                slot.name.lowercase().contains(name.lowercase())
            }
        }

    // Matches promo number formats: SWSH001, SM01, SVP001, XY01, BW01, etc.
    private fun isPromoNumber(query: String): Boolean =
        Regex("^[A-Za-z]{2,5}\\d{1,4}$").matches(query)
}
