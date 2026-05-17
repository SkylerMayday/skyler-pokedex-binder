package com.skyler.pokedexbinder.ui.mainbinder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainBinderViewModel @Inject constructor(
    private val binderRepository: BinderRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val slots: StateFlow<List<PokemonSlot>> = binderRepository.observeSlots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { binderRepository.seedIfEmpty(context) }
    }
}
