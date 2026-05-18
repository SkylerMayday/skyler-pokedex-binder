package com.skyler.pokedexbinder.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.AssignCardUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssignmentViewModel @Inject constructor(
    private val assignCardUseCase: AssignCardUseCase
) : ViewModel() {
    fun assign(pokemonId: String, card: TcgCard) {
        viewModelScope.launch { assignCardUseCase.assign(pokemonId, card) }
    }
}
