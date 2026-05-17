package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.MainBinderDao
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderRepository @Inject constructor(
    private val mainBinderDao: MainBinderDao
) {
    fun observeSlots(): Flow<List<PokemonSlot>> =
        mainBinderDao.observeAll().map { entries -> entries.map { it.toDomain() } }

    suspend fun getSlotByPokemonId(pokemonId: String): PokemonSlot? =
        mainBinderDao.getByPokemonId(pokemonId)?.toDomain()

    suspend fun assignCard(pokemonId: String, cardId: String, cardImageUrl: String) {
        val existing = mainBinderDao.getByPokemonId(pokemonId) ?: return
        mainBinderDao.upsert(
            existing.copy(assignedCardId = cardId, assignedCardImageUrl = cardImageUrl)
        )
    }

    suspend fun clearCard(pokemonId: String) {
        val existing = mainBinderDao.getByPokemonId(pokemonId) ?: return
        mainBinderDao.upsert(existing.copy(assignedCardId = null, assignedCardImageUrl = null))
    }

    suspend fun seedFromJson(slots: List<MainBinderEntry>) {
        mainBinderDao.insertAll(slots)
    }

    private fun MainBinderEntry.toDomain() = PokemonSlot(
        id = pokemonId,
        name = pokemonName,
        dexNumber = 0,
        dexOrder = dexOrder,
        slotType = SlotType.BASE,
        assignedCardId = assignedCardId,
        assignedCardImageUrl = assignedCardImageUrl
    )
}
