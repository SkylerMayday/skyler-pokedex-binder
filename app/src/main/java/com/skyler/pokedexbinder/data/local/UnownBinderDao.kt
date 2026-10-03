package com.skyler.pokedexbinder.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UnownBinderDao {

    @Query("SELECT * FROM unown_binder ORDER BY position ASC")
    fun observeAll(): Flow<List<UnownBinderEntry>>

    @Query("SELECT * FROM unown_binder ORDER BY position ASC")
    suspend fun getAll(): List<UnownBinderEntry>

    @Query("SELECT * FROM unown_binder WHERE letterId = :letterId")
    suspend fun getByLetterId(letterId: String): UnownBinderEntry?

    @Upsert
    suspend fun upsert(entry: UnownBinderEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<UnownBinderEntry>)

    @Query("SELECT COUNT(*) FROM unown_binder")
    suspend fun count(): Int

    @Query("""
        UPDATE unown_binder SET language = :language, remarks = :remarks, isLocked = :isLocked
        WHERE letterId = :letterId
    """)
    suspend fun updateDetails(letterId: String, language: String, remarks: String?, isLocked: Boolean)

    /** Distinct card ids whose image URL is blank (NULL or ''), for the image sync. */
    @Query("SELECT DISTINCT assignedCardId FROM unown_binder WHERE assignedCardId IS NOT NULL AND (assignedCardImageUrl IS NULL OR assignedCardImageUrl = '')")
    suspend fun blankImageCardIds(): List<String>

    /** Fills a blank image URL only; never overwrites an existing one. Returns rows changed. */
    @Query("UPDATE unown_binder SET assignedCardImageUrl = :url WHERE assignedCardId = :cardId AND (assignedCardImageUrl IS NULL OR assignedCardImageUrl = '')")
    suspend fun fillBlankImage(cardId: String, url: String): Int
}
