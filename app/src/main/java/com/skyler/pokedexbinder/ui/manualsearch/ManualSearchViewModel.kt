package com.skyler.pokedexbinder.ui.manualsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.CardSearchRepository
import com.skyler.pokedexbinder.repository.SearchProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Results(val cards: List<TcgCard>, val stillSearching: Boolean = false) : SearchState()
    data class Error(val message: String) : SearchState()
}

@HiltViewModel
class ManualSearchViewModel @Inject constructor(
    private val cardSearchRepository: CardSearchRepository
) : ViewModel() {

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state

    private val _promoOnly = MutableStateFlow(false)
    val promoOnly: StateFlow<Boolean> = _promoOnly

    private var lastQuery: String = ""
    private var searchJob: Job? = null

    fun search(query: String) {
        if (query.isBlank()) return
        val trimmed = query.trim()
        lastQuery = query
        searchJob?.cancel()                 // supersede any in-flight previous search
        _state.value = SearchState.Loading
        searchJob = viewModelScope.launch {
            try {
                val fastSearch: suspend () -> List<TcgCard> = {
                    when {
                        isPromoNumber(trimmed) -> cardSearchRepository.searchByNumber(trimmed)
                            .ifEmpty { cardSearchRepository.searchByName(trimmed) }
                        _promoOnly.value -> cardSearchRepository.searchPromosByName(trimmed)
                        else -> cardSearchRepository.searchByName(trimmed)
                    }
                }
                cardSearchRepository.searchStreaming(trimmed, fastSearch).collect { progress ->
                    _state.value = when (progress) {
                        is SearchProgress.Fast ->
                            SearchState.Results(progress.cards, stillSearching = true)
                        is SearchProgress.Complete ->
                            SearchState.Results(progress.cards, stillSearching = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e // never swallow cancellation, never flip to Error on supersede
            } catch (e: Exception) {
                _state.value = SearchState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun togglePromoFilter() {
        _promoOnly.value = !_promoOnly.value
        if (lastQuery.isNotBlank()) search(lastQuery)
    }

    fun retry() {
        if (lastQuery.isBlank()) return
        search(lastQuery)
    }

    // Matches promo number formats: SWSH001, SM01, SVP001, XY01, BW01, etc.
    private fun isPromoNumber(query: String): Boolean =
        Regex("^[A-Za-z]{2,5}\\d{1,4}$").matches(query)
}
