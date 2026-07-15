package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SecondaryBinderDao {

    @Query("SELECT * FROM secondary_binder ORDER BY position ASC, id ASC")
    fun observeAll(): Flow<List<SecondaryBinderEntry>>

    @Query("SELECT * FROM secondary_binder ORDER BY position ASC, id ASC")
    suspend fun getAll(): List<SecondaryBinderEntry>

    @Insert
    suspend fun insert(entry: SecondaryBinderEntry)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM secondary_binder")
    suspend fun getNextPosition(): Int

    @Transaction
    suspend fun insertAtEnd(entry: SecondaryBinderEntry) {
        val pos = getNextPosition()
        insert(entry.copy(position = pos))
    }

    @Query("UPDATE secondary_binder SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Int, position: Int)

    @Query("DELETE FROM secondary_binder WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("UPDATE secondary_binder SET language = :language WHERE id = :id")
    suspend fun updateLanguage(id: Int, language: String)
}
