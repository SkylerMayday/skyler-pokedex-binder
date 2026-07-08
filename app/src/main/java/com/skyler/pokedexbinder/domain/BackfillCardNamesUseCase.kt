package com.skyler.pokedexbinder.domain

import android.util.Log
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.repository.BinderRepository
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backfills `assignedCardName` / `assignedCardSetName` for `main_binder` rows that were
 * assigned before schema v6 added those columns. Non-destructive, idempotent, self-terminating:
 * the eligibility query only ever returns rows still missing a name, so a fully-backfilled
 * install runs a single empty SELECT on every launch. pokemontcg.io only, skip silently on
 * any per-row failure — see `.pipeline/specs.md`.
 */
@Singleton
class BackfillCardNamesUseCase @Inject constructor(
    private val binderRepository: BinderRepository,
    private val api: PokemonTcgApi
) {
    /** @return number of rows successfully backfilled. Never throws; failures are logged and skipped. */
    suspend fun backfill(): Int {
        val eligible = binderRepository.getRowsNeedingBackfill()
            .filter { it.assignedCardId != null && !it.assignedCardId.startsWith("tcgdex_") }

        if (eligible.isEmpty()) return 0

        var successCount = 0
        for (row in eligible) {
            val cardId = row.assignedCardId ?: continue
            try {
                val card = api.getCard(cardId).data
                binderRepository.backfillCardNameSet(row.pokemonId, cardId, card.name, card.set.name)
                successCount++
            } catch (e: Exception) {
                Log.w(TAG, "Backfill skipped for ${row.pokemonId} / $cardId", e)
            }
            delay(BACKFILL_REQUEST_DELAY_MS)
        }
        return successCount
    }

    companion object {
        private const val TAG = "BackfillCardNames"
        private const val BACKFILL_REQUEST_DELAY_MS = 300L
    }
}
