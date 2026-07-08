package com.skyler.pokedexbinder.publish

import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.publish.model.SnapshotSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.PublishSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val BINDER_ID_POKEDEX = "pokedex"

sealed interface RestoreStep {
    data object Fetching : RestoreStep
    data object Restoring : RestoreStep
    data class Done(val restoredCount: Int, val clearedCount: Int, val skippedCount: Int) : RestoreStep
    data object NoSnapshot : RestoreStep
}

sealed interface RestoreResult {
    data class Success(
        val restoredCount: Int,
        val clearedCount: Int,
        val skippedCount: Int,
        val elapsedMs: Long
    ) : RestoreResult
    data object NoSnapshot : RestoreResult
    data class Failure(val step: RestoreStep, val message: String) : RestoreResult
}

@Singleton
class RestoreRepository @Inject constructor(
    private val publishRepository: PublishRepository,
    private val publishSettingsRepository: PublishSettingsRepository,
    private val binderRepository: BinderRepository
) {

    suspend fun restore(onStep: (RestoreStep) -> Unit): RestoreResult {
        val start = System.currentTimeMillis()
        var currentStep: RestoreStep = RestoreStep.Fetching

        val config = publishSettingsRepository.getConfig()
        if (config.githubPat.isBlank() || config.githubOwner.isBlank() || config.githubRepo.isBlank()) {
            return RestoreResult.Failure(
                RestoreStep.Fetching,
                "GitHub not configured — add PAT and repo in Settings"
            )
        }

        return try {
            currentStep = RestoreStep.Fetching
            onStep(currentStep)
            val auth = "Bearer ${config.githubPat}"

            val (snapshot, _) = publishRepository.fetchBaselineSnapshot(auth, config)

            if (snapshot == null) {
                onStep(RestoreStep.NoSnapshot)
                return RestoreResult.NoSnapshot
            }

            val snapshotSlots: Map<String, SnapshotSlot> = snapshot.binders
                .firstOrNull { it.id == BINDER_ID_POKEDEX }
                ?.sections.orEmpty()
                .flatMap { it.slots }
                .associateBy { it.slotId }

            currentStep = RestoreStep.Restoring
            onStep(currentStep)

            val currentEntries: List<MainBinderEntry> = binderRepository.getAllEntries()

            var restored = 0
            var cleared = 0
            val updated = currentEntries.map { entry ->
                val snap = snapshotSlots[entry.pokemonId] ?: return@map entry
                when {
                    snap.cardId != null -> {
                        restored++
                        entry.copy(
                            assignedCardId = snap.cardId,
                            assignedCardName = snap.cardName,
                            assignedCardSetName = snap.cardSet,
                            assignedCardImageUrl = snap.imageUrl
                        )
                    }
                    entry.assignedCardId != null -> {
                        cleared++
                        entry.copy(
                            assignedCardId = null,
                            assignedCardName = null,
                            assignedCardSetName = null,
                            assignedCardImageUrl = null
                        )
                    }
                    else -> entry
                }
            }

            val currentPokemonIds = currentEntries.map { it.pokemonId }.toSet()
            val skipped = snapshotSlots.keys.count { it !in currentPokemonIds }

            binderRepository.seedFromJson(updated)

            currentStep = RestoreStep.Done(restored, cleared, skipped)
            onStep(currentStep)
            RestoreResult.Success(restored, cleared, skipped, System.currentTimeMillis() - start)
        } catch (e: PublishFailedException) {
            RestoreResult.Failure(currentStep, e.message ?: "Unknown error")
        } catch (e: Throwable) {
            RestoreResult.Failure(currentStep, e.message ?: "Unknown error")
        }
    }
}
