package com.skyler.pokedexbinder.ui.personalcollection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.repository.PersonalCollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Fixed section definitions — the 5 Pokémon in spec order. */
data class PokemonSection(val key: String, val title: String, val queryNames: List<String>)

val PERSONAL_COLLECTION_SECTIONS = listOf(
    PokemonSection("charizard", "Charizard", listOf("Charizard")),
    PokemonSection("celebi", "Celebi", listOf("Celebi")),
    PokemonSection("leafeon", "Leafeon", listOf("Leafeon")),
    PokemonSection("tangela", "Tangela", listOf("Tangela")),
    PokemonSection("minccino_cinccino", "Minccino & Cinccino", listOf("Minccino", "Cinccino"))
)

data class PersonalCard(
    val cardId: String,
    val name: String,
    val imageUrl: String,
    val owned: Boolean
)

data class PersonalCollectionUiState(
    val sections: Map<String, List<PersonalCard>> = emptyMap(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class PersonalCollectionViewModel @Inject constructor(
    private val repository: PersonalCollectionRepository
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PersonalCollectionUiState> = combine(
        repository.observeAllCache(),
        repository.observeEntries(),
        _isRefreshing,
        _errorMessage
    ) { cache, entries, refreshing, error ->
        val ownedIds = entries.filter { it.owned }.map { it.cardId }.toSet()
        val grouped = cache.groupBy { it.pokemonKey }.mapValues { (_, rows) ->
            rows.map { PersonalCard(it.cardId, it.name, it.imageUrl, it.cardId in ownedIds) }
        }
        PersonalCollectionUiState(sections = grouped, isRefreshing = refreshing, errorMessage = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonalCollectionUiState())

    init {
        viewModelScope.launch {
            if (repository.cacheCount() == 0) refreshAll()
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            try {
                PERSONAL_COLLECTION_SECTIONS.forEach { section ->
                    repository.refreshPokemon(section.key, section.queryNames)
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to refresh collection"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleOwned(cardId: String, currentlyOwned: Boolean) {
        viewModelScope.launch {
            if (currentlyOwned) repository.removeOwned(cardId) else repository.setOwned(cardId, true)
        }
    }
}
