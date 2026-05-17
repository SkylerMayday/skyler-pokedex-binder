package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SecondaryBinderDao {
    @Query("SELECT * FROM secondary_binder ORDER BY id DESC")
    fun observeAll(): Flow<List<SecondaryBinderEntry>>

    @Insert
    suspend fun insert(entry: SecondaryBinderEntry)
}
