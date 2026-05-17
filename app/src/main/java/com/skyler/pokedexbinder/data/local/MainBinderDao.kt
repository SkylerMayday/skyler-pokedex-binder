package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MainBinderDao {
    @Query("SELECT * FROM main_binder ORDER BY dexOrder ASC")
    fun observeAll(): Flow<List<MainBinderEntry>>

    @Query("SELECT * FROM main_binder WHERE pokemonId = :pokemonId")
    suspend fun getByPokemonId(pokemonId: String): MainBinderEntry?

    @Upsert
    suspend fun upsert(entry: MainBinderEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MainBinderEntry>)

    @Query("SELECT COUNT(*) FROM main_binder")
    suspend fun count(): Int
}
