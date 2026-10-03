package com.skyler.pokedexbinder.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** What a collection reset will clear, for the first confirmation dialog. */
data class ResetSummary(
    val mainBinderCards: Int,
    val unownCards: Int,
    val connectingArtCards: Int,
    val cardHistoryCards: Int,
    val personalCollectionOwned: Int
)

/**
 * "Reset collection data": clears card assignments in one transaction. Slot rows (seeded from
 * `pokemon_slots.json` / the 28 Unown letters) and `connecting_art_group` layouts stay; settings and
 * API keys live outside Room. Uses `@Query` (not raw execSQL) so open Flows invalidate.
 */
@Dao
interface CollectionResetDao {

    @Query("SELECT COUNT(*) FROM main_binder WHERE assignedCardId IS NOT NULL")
    suspend fun countMainBinderCards(): Int

    @Query("SELECT COUNT(*) FROM unown_binder WHERE assignedCardId IS NOT NULL")
    suspend fun countUnownCards(): Int

    @Query("SELECT COUNT(*) FROM connecting_art_slot WHERE cardId IS NOT NULL")
    suspend fun countConnectingArtCards(): Int

    @Query("SELECT COUNT(*) FROM secondary_binder")
    suspend fun countCardHistory(): Int

    @Query("SELECT COUNT(*) FROM personal_collection_entry WHERE owned = 1")
    suspend fun countPersonalCollectionOwned(): Int

    @Transaction
    suspend fun summary(): ResetSummary = ResetSummary(
        mainBinderCards = countMainBinderCards(),
        unownCards = countUnownCards(),
        connectingArtCards = countConnectingArtCards(),
        cardHistoryCards = countCardHistory(),
        personalCollectionOwned = countPersonalCollectionOwned()
    )

    @Query(
        "UPDATE main_binder SET assignedCardId = NULL, assignedCardImageUrl = NULL, " +
            "assignedCardName = NULL, assignedCardSetName = NULL, language = 'EN', remarks = NULL, isLocked = 0"
    )
    suspend fun clearMainBinder()

    @Query(
        "UPDATE unown_binder SET assignedCardId = NULL, assignedCardImageUrl = NULL, " +
            "assignedCardName = NULL, assignedCardSetName = NULL, language = 'EN', remarks = NULL, isLocked = 0"
    )
    suspend fun clearUnownBinder()

    @Query(
        "UPDATE connecting_art_slot SET cardId = NULL, cardName = NULL, cardImageUrl = NULL, " +
            "owned = 0, language = 'EN'"
    )
    suspend fun clearConnectingArtSlots()

    @Query("DELETE FROM secondary_binder")
    suspend fun clearCardHistory()

    @Query("DELETE FROM personal_collection_cache")
    suspend fun clearPersonalCollectionCache()

    @Query("DELETE FROM personal_collection_entry")
    suspend fun clearPersonalCollectionEntries()

    /** All-or-nothing: a failure in any statement rolls the whole reset back. */
    @Transaction
    suspend fun resetAll() {
        clearMainBinder()
        clearUnownBinder()
        clearConnectingArtSlots()
        clearCardHistory()
        clearPersonalCollectionCache()
        clearPersonalCollectionEntries()
    }
}
