package com.skyler.pokedexbinder.ui.personalcollection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.repository.PersonalCollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Fixed section definitions — the 5 Pokémon in spec order. */
data class PokemonSection(val key: String, val title: String, val queryNames: List<String>)

// Unown lives in its own standalone binder (hamburger menu, ui/unown/) — fixed 28-slot,
// one-card-per-slot assignment. Not part of Personal Collection's search-and-toggle model.
val PERSONAL_COLLECTION_SECTIONS: List<PokemonSection> = listOf(
    PokemonSection("charizard", "Charizard", listOf("Charizard")),
    PokemonSection("celebi", "Celebi", listOf("Celebi")),
    PokemonSection("tangela", "Tangela", listOf("Tangela")),
    PokemonSection("minccino_cinccino", "Minccino & Cinccino", listOf("Minccino", "Cinccino"))
)

data class PersonalCard(
    val cardId: String,
    val name: String,
    val imageUrl: String,
    val owned: Boolean,
    val language: String = "EN"
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
        val languageByCardId = entries.associate { it.cardId to it.language }
        val grouped = cache.groupBy { it.pokemonKey }.mapValues { (_, rows) ->
            rows.map { row ->
                PersonalCard(
                    cardId = row.cardId,
                    name = row.name,
                    imageUrl = row.imageUrl,
                    owned = row.cardId in ownedIds,
                    language = languageByCardId[row.cardId] ?: "EN"
                )
            }
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
            // Refreshed with bounded concurrency, each section isolated via runCatching so one
            // section throwing can't cancel its siblings or abort chunks that haven't run yet.
            val failedSections = mutableListOf<String>()
            try {
                coroutineScope {
                    PERSONAL_COLLECTION_SECTIONS.chunked(REFRESH_CONCURRENCY).forEach { chunk ->
                        chunk.map { section ->
                            async {
                                runCatching { repository.refreshPokemon(section.key, section.queryNames) }
                                    .onFailure { failedSections += section.title }
                            }
                        }.awaitAll()
                    }
                }
                if (failedSections.isNotEmpty()) {
                    _errorMessage.value = "Couldn't refresh: ${failedSections.joinToString()}"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to refresh collection"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private companion object {
        const val REFRESH_CONCURRENCY = 8
    }

    fun toggleOwned(cardId: String, currentlyOwned: Boolean) {
        viewModelScope.launch {
            if (currentlyOwned) repository.removeOwned(cardId) else repository.setOwned(cardId, true)
        }
    }

    fun updateLanguage(cardId: String, language: Language) {
        viewModelScope.launch { repository.updateLanguage(cardId, language) }
    }
}
