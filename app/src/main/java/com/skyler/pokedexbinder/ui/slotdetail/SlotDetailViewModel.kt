package com.skyler.pokedexbinder.ui.slotdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SlotDetailViewModel @Inject constructor(
    private val binderRepository: BinderRepository
) : ViewModel() {

    private val _slot = MutableStateFlow<PokemonSlot?>(null)
    val slot: StateFlow<PokemonSlot?> = _slot

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun loadSlot(pokemonId: String) {
        _isLoading.value = true
        viewModelScope.launch {
            _slot.value = binderRepository.getSlotByPokemonId(pokemonId)
            _isLoading.value = false
        }
    }
}
