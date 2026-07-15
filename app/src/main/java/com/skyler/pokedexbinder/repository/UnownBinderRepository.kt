package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.UnownBinderDao
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnownBinderRepository @Inject constructor(
    private val unownBinderDao: UnownBinderDao
) {
    fun observeEntries(): Flow<List<UnownBinderEntry>> = unownBinderDao.observeAll()

    suspend fun getAllEntries(): List<UnownBinderEntry> = unownBinderDao.getAll()

    /** Inserts all 28 empty letter rows the first time the table is empty. Idempotent. */
    suspend fun seedIfEmpty() {
        if (unownBinderDao.count() > 0) return
        unownBinderDao.insertAll(LETTER_IDS.mapIndexed { index, id ->
            UnownBinderEntry(letterId = id, position = index)
        })
    }

    suspend fun assignCard(
        letterId: String,
        cardId: String,
        cardImageUrl: String,
        cardName: String?,
        cardSetName: String?
    ) {
        val existing = unownBinderDao.getByLetterId(letterId) ?: return
        unownBinderDao.upsert(
            existing.copy(
                assignedCardId = cardId,
                assignedCardImageUrl = cardImageUrl,
                assignedCardName = cardName,
                assignedCardSetName = cardSetName
            )
        )
    }

    suspend fun clearCard(letterId: String) {
        val existing = unownBinderDao.getByLetterId(letterId) ?: return
        unownBinderDao.upsert(
            existing.copy(
                assignedCardId = null,
                assignedCardImageUrl = null,
                assignedCardName = null,
                assignedCardSetName = null
            )
        )
    }

    /** Overwrites all rows (restore path — mirrors BinderRepository.seedFromJson). */
    suspend fun overwriteAll(entries: List<UnownBinderEntry>) = unownBinderDao.insertAll(entries)

    companion object {
        /** 28 fixed letter IDs: A..Z, then "!", then "?". Ordering defines grid position. */
        val LETTER_IDS: List<String> =
            ('A'..'Z').map { it.toString() } + listOf("!", "?")

        /**
         * The TCG search query used for every letter — deliberately broad ("Unown", not
         * "Unown A"), per Skyler's fallback: browse every Unown printing and manually pick which
         * one belongs in which slot, rather than relying on a per-letter query to disambiguate.
         */
        fun searchNameFor(letterId: String): String = "Unown"
    }
}
