package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.PersonalCollectionCache
import com.skyler.pokedexbinder.data.local.PersonalCollectionDao
import com.skyler.pokedexbinder.data.local.PersonalCollectionEntry
import com.skyler.pokedexbinder.data.model.Language
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonalCollectionRepository @Inject constructor(
    private val dao: PersonalCollectionDao,
    private val cardSearchRepository: CardSearchRepository
) {
    fun observeAllCache(): Flow<List<PersonalCollectionCache>> = dao.observeAllCache()

    fun observeEntries(): Flow<List<PersonalCollectionEntry>> = dao.observeEntries()

    suspend fun cacheCount(): Int = dao.cacheCount()

    suspend fun getAllCache(): List<PersonalCollectionCache> = dao.getAllCache()

    suspend fun getAllEntries(): List<PersonalCollectionEntry> = dao.getAllEntries()

    /** Keeps an existing entry's language (REPLACE would reset it to EN); new rows infer it from the card id. */
    suspend fun setOwned(cardId: String, owned: Boolean) {
        val language = dao.getEntry(cardId)?.language ?: Language.fromCardId(cardId).name
        dao.upsertEntry(PersonalCollectionEntry(cardId = cardId, owned = owned, language = language))
    }

    suspend fun removeOwned(cardId: String) = dao.deleteEntry(cardId)

    /**
     * Sets language for [cardId], creating the entry row first (default `owned = false`) if one
     * doesn't exist yet — entries are otherwise only created via the first owned-toggle
     * ([setOwned]), and a plain UPDATE would silently no-op on a nonexistent row.
     */
    suspend fun updateLanguage(cardId: String, language: Language) {
        if (dao.getEntry(cardId) == null) dao.upsertEntry(PersonalCollectionEntry(cardId = cardId, owned = false))
        dao.updateLanguage(cardId, language.name)
    }

    /**
     * Queries TCGdex for each name in [names] (Pocket filter enforced in
     * CardSearchRepository), merges + dedupes by card id across names, maps to
     * PersonalCollectionCache, and atomically replaces the cache rows for [pokemonKey].
     *
     * An empty merged result for an already-cached section keeps the cache (transient TCGdex gaps
     * must not blank a section). A partial result (some call failed, e.g. one language) is upserted
     * instead of replacing, so cached rows the failed call would have returned are kept.
     * Ordering is not date-based: the DAO sorts deterministically.
     *
     * Owned state is untouched: it lives in the separate personal_collection_entry table
     * keyed by cardId, which replaceCacheForPokemon never deletes.
     */
    suspend fun refreshPokemon(pokemonKey: String, names: List<String>) {
        val merged = LinkedHashMap<String, PersonalCollectionCache>()
        var complete = true
        names.forEach { name ->
            val search = cardSearchRepository.searchByNameAllLanguages(name)
            if (!search.complete) complete = false
            search.cards.forEach { card ->
                merged.putIfAbsent(
                    card.id,
                    PersonalCollectionCache(
                        cardId = card.id,
                        pokemonKey = pokemonKey,
                        name = card.name,
                        imageUrl = card.imageUrl,
                        setName = card.setName,
                        releaseDate = ""
                    )
                )
            }
        }
        if (merged.isEmpty() && dao.cacheCountForPokemon(pokemonKey) > 0) return
        if (complete) dao.replaceCacheForPokemon(pokemonKey, merged.values.toList())
        else dao.upsertCache(merged.values.toList())
    }
}
