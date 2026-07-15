package com.skyler.pokedexbinder.publish

import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.publish.model.SnapshotSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.ConnectingArtRepository
import com.skyler.pokedexbinder.repository.PersonalCollectionRepository
import com.skyler.pokedexbinder.repository.PublishSettingsRepository
import com.skyler.pokedexbinder.repository.UnownBinderRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val BINDER_ID_POKEDEX = "pokedex"
private const val BINDER_ID_CONNECTING_ART = "connectingArt"
private const val BINDER_ID_PERSONAL_COLLECTION = "personalCollection"
private const val BINDER_ID_UNOWN = "unown"

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
    private val binderRepository: BinderRepository,
    private val connectingArtRepository: ConnectingArtRepository,
    private val personalCollectionRepository: PersonalCollectionRepository,
    private val unownBinderRepository: UnownBinderRepository
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
                            assignedCardImageUrl = snap.imageUrl,
                            language = snap.language,
                            remarks = snap.remarks,
                            isLocked = snap.isLocked
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
            var skipped = snapshotSlots.keys.count { it !in currentPokemonIds }

            binderRepository.seedFromJson(updated)

            // --- Connecting Art overlay — match local slots by "ca-<groupId>-<slotIndex>" key ---
            val caSnapshotSlots: Map<String, SnapshotSlot> = snapshot.binders
                .firstOrNull { it.id == BINDER_ID_CONNECTING_ART }
                ?.sections.orEmpty()
                .flatMap { it.slots }
                .associateBy { it.slotId }
            if (caSnapshotSlots.isNotEmpty()) {
                val localCaSlots = connectingArtRepository.getAllSlots()
                val caToUpdate = mutableListOf<ConnectingArtSlot>()
                localCaSlots.forEach { caSlot ->
                    val key = "ca-${caSlot.groupId}-${caSlot.slotIndex}"
                    val snap = caSnapshotSlots[key] ?: return@forEach
                    when {
                        snap.cardId != null -> {
                            restored++
                            caToUpdate += caSlot.copy(
                                cardId = snap.cardId,
                                cardName = snap.cardName,
                                cardImageUrl = snap.imageUrl,
                                owned = snap.owned,           // R7
                                language = snap.language
                            )
                        }
                        caSlot.cardId != null -> {
                            cleared++
                            caToUpdate += caSlot.copy(
                                cardId = null, cardName = null, cardImageUrl = null, owned = false
                            )
                        }
                        // else: both empty — no-op
                    }
                }
                val localCaKeys = localCaSlots.map { "ca-${it.groupId}-${it.slotIndex}" }.toSet()
                skipped += caSnapshotSlots.keys.count { it !in localCaKeys }
                if (caToUpdate.isNotEmpty()) connectingArtRepository.updateSlots(caToUpdate)
            }

            // --- Personal Collection overlay — restore OWNED state onto personal_collection_entry by cardId.
            // Does NOT write personal_collection_cache (cache is network-refreshed separately). R6/R8. ---
            val pcSnapshotSlots: List<SnapshotSlot> = snapshot.binders
                .firstOrNull { it.id == BINDER_ID_PERSONAL_COLLECTION }
                ?.sections.orEmpty()
                .flatMap { it.slots }
            if (pcSnapshotSlots.isNotEmpty()) {
                val localPcEntriesByCardId = personalCollectionRepository.getAllEntries()
                    .associateBy { it.cardId }
                pcSnapshotSlots.forEach { snap ->
                    val cardId = snap.cardId ?: return@forEach   // PC slots always carry cardId; guard anyway
                    val localEntry = localPcEntriesByCardId[cardId]
                    val isLocallyOwned = localEntry?.owned == true
                    when {
                        snap.owned && !isLocallyOwned -> {
                            restored++
                            personalCollectionRepository.setOwned(cardId, true)
                        }
                        !snap.owned && isLocallyOwned -> {
                            cleared++
                            personalCollectionRepository.removeOwned(cardId)
                        }
                        // else: already matches snapshot — no-op
                    }
                    // Language is a slot-level property independent of the owned overlay above —
                    // restore it whenever it differs from the locally stored value. A missing local
                    // entry implies the Room column default ("EN"), same as the publish-side fallback,
                    // so this doesn't force-create an entry row just to restate the default.
                    val localLanguage = localEntry?.language ?: "EN"
                    if (localLanguage != snap.language) {
                        personalCollectionRepository.updateLanguage(cardId, Language.fromRaw(snap.language))
                    }
                }
            }

            // --- Unown overlay — match local letter slots by "unown-<letterId>" key.
            // Single-assignment overlay, same shape as the main Pokédex binder's, not owned-toggle. ---
            val unownSnapshotSlots: Map<String, SnapshotSlot> = snapshot.binders
                .firstOrNull { it.id == BINDER_ID_UNOWN }
                ?.sections.orEmpty()
                .flatMap { it.slots }
                .associateBy { it.slotId }
            if (unownSnapshotSlots.isNotEmpty()) {
                val localUnownEntries = unownBinderRepository.getAllEntries()
                val unownToUpdate = mutableListOf<UnownBinderEntry>()
                localUnownEntries.forEach { entry ->
                    val key = "unown-${entry.letterId}"
                    val snap = unownSnapshotSlots[key] ?: return@forEach
                    when {
                        snap.cardId != null -> {
                            restored++
                            unownToUpdate += entry.copy(
                                assignedCardId = snap.cardId,
                                assignedCardName = snap.cardName,
                                assignedCardSetName = snap.cardSet,
                                assignedCardImageUrl = snap.imageUrl,
                                language = snap.language,
                                remarks = snap.remarks,
                                isLocked = snap.isLocked
                            )
                        }
                        entry.assignedCardId != null -> {
                            cleared++
                            unownToUpdate += entry.copy(
                                assignedCardId = null,
                                assignedCardName = null,
                                assignedCardSetName = null,
                                assignedCardImageUrl = null
                            )
                        }
                        // else: both empty — no-op
                    }
                }
                val localUnownKeys = localUnownEntries.map { "unown-${it.letterId}" }.toSet()
                skipped += unownSnapshotSlots.keys.count { it !in localUnownKeys }
                if (unownToUpdate.isNotEmpty()) unownBinderRepository.overwriteAll(unownToUpdate)
            }

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
