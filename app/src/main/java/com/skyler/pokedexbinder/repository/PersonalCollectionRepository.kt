package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.PersonalCollectionCache
import com.skyler.pokedexbinder.data.local.PersonalCollectionDao
import com.skyler.pokedexbinder.data.local.PersonalCollectionEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonalCollectionRepository @Inject constructor(
    private val dao: PersonalCollectionDao,
    private val cardSearchRepository: CardSearchRepository
) {
    fun observeCache(pokemonKey: String): Flow<List<PersonalCollectionCache>> =
        dao.observeCacheForPokemon(pokemonKey)

    fun observeAllCache(): Flow<List<PersonalCollectionCache>> = dao.observeAllCache()

    fun observeEntries(): Flow<List<PersonalCollectionEntry>> = dao.observeEntries()

    suspend fun cacheCount(): Int = dao.cacheCount()

    suspend fun setOwned(cardId: String, owned: Boolean) =
        dao.upsertEntry(PersonalCollectionEntry(cardId = cardId, owned = owned))

    suspend fun removeOwned(cardId: String) = dao.deleteEntry(cardId)

    /**
     * Queries pokemontcg.io/TCGdex for each name in [names] (Pocket filter enforced in
     * CardSearchRepository.searchByName), merges + dedupes by card id across names, maps to
     * PersonalCollectionCache, and atomically replaces the cache rows for [pokemonKey].
     *
     * Owned state is untouched: it lives in the separate personal_collection_entry table
     * keyed by cardId, which replaceCacheForPokemon never deletes.
     */
    suspend fun refreshPokemon(pokemonKey: String, names: List<String>) {
        val merged = LinkedHashMap<String, PersonalCollectionCache>()
        names.forEach { name ->
            cardSearchRepository.searchByName(name).forEach { card ->
                merged.putIfAbsent(
                    card.id,
                    PersonalCollectionCache(
                        cardId = card.id,
                        pokemonKey = pokemonKey,
                        name = card.name,
                        imageUrl = card.imageUrl,
                        setName = card.setName,
                        releaseDate = card.setReleaseDate ?: ""
                    )
                )
            }
        }
        dao.replaceCacheForPokemon(pokemonKey, merged.values.toList())
    }
}
