package com.skyler.pokedexbinder.repository

import android.util.Log
import com.skyler.pokedexbinder.data.local.MainBinderDao
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
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

    fun observeSlot(pokemonId: String): Flow<PokemonSlot?> =
        mainBinderDao.observeByPokemonId(pokemonId).map { it?.toDomain() }

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

    suspend fun seedIfEmpty(context: android.content.Context) {
        if (mainBinderDao.count() > 0) return
        runCatching {
            val json = context.resources.openRawResource(com.skyler.pokedexbinder.R.raw.pokemon_slots)
                .bufferedReader().readText()
            val arr = JSONArray(json)
            val entries = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MainBinderEntry(
                    pokemonId = obj.getString("id"),
                    pokemonName = obj.getString("name"),
                    dexNumber = obj.getInt("dex_number"),
                    dexOrder = obj.getInt("dex_order"),
                    slotType = obj.getString("slot_type")
                )
            }
            mainBinderDao.insertAll(entries)
        }.onFailure { e ->
            Log.e("BinderRepository", "Failed to seed pokemon slots", e)
        }
    }

    private fun MainBinderEntry.toDomain() = PokemonSlot(
        id = pokemonId,
        name = pokemonName,
        dexNumber = dexNumber,
        dexOrder = dexOrder,
        slotType = when (slotType.lowercase()) {
            "regional" -> SlotType.REGIONAL
            "mega" -> SlotType.MEGA
            "gmax" -> SlotType.GMAX
            else -> SlotType.BASE
        },
        assignedCardId = assignedCardId,
        assignedCardImageUrl = assignedCardImageUrl
    )
}
