package com.skyler.pokedexbinder.ui.slotdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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

    fun loadSlot(pokemonId: String) {
        _pokemonId.value = pokemonId
    }

    fun clearCard() {
        viewModelScope.launch { binderRepository.clearCard(_pokemonId.value) }
    }
}
