package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalCollectionDao {

    // ---- Cache ----
    @Query("SELECT * FROM personal_collection_cache WHERE pokemonKey = :pokemonKey ORDER BY releaseDate DESC")
    fun observeCacheForPokemon(pokemonKey: String): Flow<List<PersonalCollectionCache>>

    @Query("SELECT * FROM personal_collection_cache ORDER BY releaseDate DESC")
    fun observeAllCache(): Flow<List<PersonalCollectionCache>>

    @Query("SELECT * FROM personal_collection_cache ORDER BY releaseDate DESC")
    suspend fun getAllCache(): List<PersonalCollectionCache>

    @Query("SELECT COUNT(*) FROM personal_collection_cache")
    suspend fun cacheCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCache(cards: List<PersonalCollectionCache>)

    @Query("DELETE FROM personal_collection_cache WHERE pokemonKey = :pokemonKey")
    suspend fun deleteCacheForPokemon(pokemonKey: String)

    @Query("DELETE FROM personal_collection_cache")
    suspend fun clearCache()

    /** Replaces the cache rows for one Pokémon key atomically (used on refresh). */
    @Transaction
    suspend fun replaceCacheForPokemon(pokemonKey: String, cards: List<PersonalCollectionCache>) {
        deleteCacheForPokemon(pokemonKey)
        upsertCache(cards)
    }

    // ---- Owned entries ----
    @Query("SELECT * FROM personal_collection_entry")
    fun observeEntries(): Flow<List<PersonalCollectionEntry>>

    @Query("SELECT * FROM personal_collection_entry")
    suspend fun getAllEntries(): List<PersonalCollectionEntry>

    @Query("SELECT * FROM personal_collection_entry WHERE cardId = :cardId")
    suspend fun getEntry(cardId: String): PersonalCollectionEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: PersonalCollectionEntry)

    @Query("DELETE FROM personal_collection_entry WHERE cardId = :cardId")
    suspend fun deleteEntry(cardId: String)

    @Query("UPDATE personal_collection_entry SET language = :language WHERE cardId = :cardId")
    suspend fun updateLanguage(cardId: String, language: String)
}
