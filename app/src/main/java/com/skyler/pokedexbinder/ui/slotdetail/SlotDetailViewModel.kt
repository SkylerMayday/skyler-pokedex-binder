package com.skyler.pokedexbinder.ui.slotdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SlotDetailViewModel @Inject constructor(
    private val binderRepository: BinderRepository
) : ViewModel() {

    private val _pokemonId = MutableStateFlow("")

    val slot: StateFlow<PokemonSlot?> = _pokemonId
        .flatMapLatest { id ->
            if (id.isEmpty()) flowOf(null)
            else binderRepository.observeSlot(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Always false — loading is now implicit in the Flow (Room is near-instant)
    val isLoading: StateFlow<Boolean> = MutableStateFlow(false)

    /** One-shot event: emitted when a clear/reassign was blocked by a locked slot (defense in
     * depth — the triggering button should already be disabled, but a stale UI state or a race
     * is possible). Collected by the screen to show a dialog. */
    private val _blockedByLock = MutableSharedFlow<Unit>()
    val blockedByLock: SharedFlow<Unit> = _blockedByLock

    fun loadSlot(pokemonId: String) {
        _pokemonId.value = pokemonId
    }

    fun clearCard() {
        viewModelScope.launch {
            val proceeded = binderRepository.clearCard(_pokemonId.value)
            if (!proceeded) _blockedByLock.emit(Unit)
        }
    }

    fun updateDetails(language: Language, remarks: String?, isLocked: Boolean) {
        viewModelScope.launch {
            binderRepository.updateDetails(_pokemonId.value, language, remarks, isLocked)
        }
    }
}
