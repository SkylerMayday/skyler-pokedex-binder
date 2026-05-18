package com.skyler.pokedexbinder.ui.manualsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.CardSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Results(val cards: List<TcgCard>) : SearchState()
    data class Error(val message: String) : SearchState()
}

@HiltViewModel
class ManualSearchViewModel @Inject constructor(
    private val cardSearchRepository: CardSearchRepository
) : ViewModel() {

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state

    fun search(query: String) {
        if (query.isBlank()) return
        _state.value = SearchState.Loading
        viewModelScope.launch {
            try {
                val cards = cardSearchRepository.searchByName(query)
                _state.value = SearchState.Results(cards)
            } catch (e: Exception) {
                _state.value = SearchState.Error(e.message ?: "Search failed")
            }
        }
    }
}
