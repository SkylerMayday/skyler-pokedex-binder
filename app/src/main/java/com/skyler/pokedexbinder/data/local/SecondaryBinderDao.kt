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

    /** Distinct card ids whose image URL is blank (NULL or ''), for the image sync. */
    @Query("SELECT DISTINCT cardId FROM secondary_binder WHERE cardId IS NOT NULL AND (cardImageUrl IS NULL OR cardImageUrl = '')")
    suspend fun blankImageCardIds(): List<String>

    /** Fills a blank image URL only; never overwrites an existing one. Returns rows changed. */
    @Query("UPDATE secondary_binder SET cardImageUrl = :url WHERE cardId = :cardId AND (cardImageUrl IS NULL OR cardImageUrl = '')")
    suspend fun fillBlankImage(cardId: String, url: String): Int
}
