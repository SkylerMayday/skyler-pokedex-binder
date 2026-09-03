package com.skyler.pokedexbinder.publish

import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.publish.model.BinderSnapshot
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
private const val BINDER_ID_CARD_HISTORY = "cardHistory"

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

/**
 * Aggregate restored/cleared/skipped counts from one [RestoreRepository.applySnapshot] run —
 * shared shape between the cloud-fetch path ([RestoreRepository.restore]) and the local-import
 * path ([RestoreRepository.restoreFromSnapshot]) so both report identically.
 */
data class SnapshotApplyCounts(val restored: Int, val cleared: Int, val skipped: Int) {
    operator fun plus(other: SnapshotApplyCounts) = SnapshotApplyCounts(
        restored = restored + other.restored,
        cleared = cleared + other.cleared,
        skipped = skipped + other.skipped
    )

    companion object {
        val NONE = SnapshotApplyCounts(0, 0, 0)
    }
}

@Singleton
class RestoreRepository @Inject constructor(
    private val publishRepository: PublishRepository,
    private val publishSettingsRepository: PublishSettingsRepository,
    private val binderRepository: BinderRepository,
    private val connectingArtRepository: ConnectingArtRepository,
    private val personalCollectionRepository: PersonalCollectionRepository,
    private val unownBinderRepository: UnownBinderRepository,
    private val secondaryBinderDao: SecondaryBinderDao
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

            currentStep = RestoreStep.Restoring
            onStep(currentStep)
            val counts = applySnapshot(snapshot)

            currentStep = RestoreStep.Done(counts.restored, counts.cleared, counts.skipped)
            onStep(currentStep)
            RestoreResult.Success(counts.restored, counts.cleared, counts.skipped, System.currentTimeMillis() - start)
        } catch (e: PublishFailedException) {
            RestoreResult.Failure(currentStep, e.message ?: "Unknown error")
        } catch (e: Throwable) {
            RestoreResult.Failure(currentStep, e.message ?: "Unknown error")
        }
    }

    /**
     * Local-import entry point: applies an already-in-hand [snapshot] (e.g. parsed straight out of
     * a local JSON backup file) through the exact same per-binder overlay logic cloud restore uses
     * — no GitHub config check, no network fetch. A separate function (not [restore] with a stubbed
     * fetch) so a local import can never accidentally depend on GitHub being configured at all.
     */
    suspend fun restoreFromSnapshot(snapshot: BinderSnapshot, onStep: (RestoreStep) -> Unit): RestoreResult {
        val start = System.currentTimeMillis()
        var currentStep: RestoreStep = RestoreStep.Restoring
        return try {
            onStep(currentStep)
            val counts = applySnapshot(snapshot)
            currentStep = RestoreStep.Done(counts.restored, counts.cleared, counts.skipped)
            onStep(currentStep)
            RestoreResult.Success(counts.restored, counts.cleared, counts.skipped, System.currentTimeMillis() - start)
        } catch (e: Throwable) {
            RestoreResult.Failure(currentStep, e.message ?: "Unknown error")
        }
    }

    /**
     * Applies every recognized per-binder overlay from [snapshot] onto local state and returns the
     * aggregate restored/cleared/skipped counts. Shared by [restore] (cloud fetch) and
     * [restoreFromSnapshot] (local import) so the two entry points can never drift apart. Any
     * binder id in [snapshot] this function doesn't recognize is silently ignored (forward
     * compatibility with future binder types).
     */
    internal suspend fun applySnapshot(snapshot: BinderSnapshot): SnapshotApplyCounts =
        // Left-to-right, so the write order is the same one this function has always had.
        applyPokedex(snapshot) +
            applyConnectingArt(snapshot) +
            applyPersonalCollection(snapshot) +
            applyUnown(snapshot) +
            applyCardHistory(snapshot)

    /** Slots of one binder in [this] snapshot, flattened across its sections. */
    private fun BinderSnapshot.slotsOf(binderId: String): List<SnapshotSlot> =
        binders.firstOrNull { it.id == binderId }?.sections.orEmpty().flatMap { it.slots }

    /** Main Pokédex binder — single-assignment overlay keyed by `pokemonId`. */
    private suspend fun applyPokedex(snapshot: BinderSnapshot): SnapshotApplyCounts {
        val snapshotSlots: Map<String, SnapshotSlot> = snapshot.slotsOf(BINDER_ID_POKEDEX)
            .associateBy { it.slotId }

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
        val skipped = snapshotSlots.keys.count { it !in currentPokemonIds }

        binderRepository.seedFromJson(updated)
        return SnapshotApplyCounts(restored, cleared, skipped)
    }

    /** Connecting Art — match local slots by "ca-<groupId>-<slotIndex>" key. */
    private suspend fun applyConnectingArt(snapshot: BinderSnapshot): SnapshotApplyCounts {
        val caSnapshotSlots: Map<String, SnapshotSlot> = snapshot.slotsOf(BINDER_ID_CONNECTING_ART)
            .associateBy { it.slotId }
        if (caSnapshotSlots.isEmpty()) return SnapshotApplyCounts.NONE

        var restored = 0
        var cleared = 0
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
        val skipped = caSnapshotSlots.keys.count { it !in localCaKeys }
        if (caToUpdate.isNotEmpty()) connectingArtRepository.updateSlots(caToUpdate)
        return SnapshotApplyCounts(restored, cleared, skipped)
    }

    /**
     * Personal Collection — restores OWNED state onto personal_collection_entry by cardId. Does NOT
     * write personal_collection_cache (cache is network-refreshed separately). R6/R8.
     */
    private suspend fun applyPersonalCollection(snapshot: BinderSnapshot): SnapshotApplyCounts {
        val pcSnapshotSlots: List<SnapshotSlot> = snapshot.slotsOf(BINDER_ID_PERSONAL_COLLECTION)
        if (pcSnapshotSlots.isEmpty()) return SnapshotApplyCounts.NONE

        var restored = 0
        var cleared = 0
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
        // Personal Collection has no "skipped" notion — a snapshot cardId with no local row is
        // simply set owned, not counted as unmatched.
        return SnapshotApplyCounts(restored, cleared, skipped = 0)
    }

    /**
     * Unown — match local letter slots by "unown-<letterId>" key. Single-assignment overlay, same
     * shape as the main Pokédex binder's, not owned-toggle.
     */
    private suspend fun applyUnown(snapshot: BinderSnapshot): SnapshotApplyCounts {
        val unownSnapshotSlots: Map<String, SnapshotSlot> = snapshot.slotsOf(BINDER_ID_UNOWN)
            .associateBy { it.slotId }
        if (unownSnapshotSlots.isEmpty()) return SnapshotApplyCounts.NONE

        var restored = 0
        var cleared = 0
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
        val skipped = unownSnapshotSlots.keys.count { it !in localUnownKeys }
        if (unownToUpdate.isNotEmpty()) unownBinderRepository.overwriteAll(unownToUpdate)
        return SnapshotApplyCounts(restored, cleared, skipped)
    }

    /**
     * Card History — append-only insert, matched by cardId. Card History has no stable per-device
     * row id that survives across installs, so cardId is the only viable diff key here; existing
     * rows are never cleared/removed, only missing ones are appended.
     */
    private suspend fun applyCardHistory(snapshot: BinderSnapshot): SnapshotApplyCounts {
        val chSnapshotSlots: List<SnapshotSlot> = snapshot.slotsOf(BINDER_ID_CARD_HISTORY)
        if (chSnapshotSlots.isEmpty()) return SnapshotApplyCounts.NONE

        // Compare *counts* per cardId rather than presence: owning two copies of one card is
        // ordinary for a collector, and Card History's own write paths never dedupe, so a
        // presence-only check would silently collapse them to one row on restore. Matching by
        // count still makes a second restore of the same snapshot insert nothing.
        val unmatchedLocal = secondaryBinderDao.getAll()
            .groupingBy { it.cardId }
            .eachCount()
            .toMutableMap()

        var restored = 0
        chSnapshotSlots.forEach { snap ->
            val cardId = snap.cardId ?: return@forEach
            val localCopies = unmatchedLocal[cardId] ?: 0
            if (localCopies > 0) {
                unmatchedLocal[cardId] = localCopies - 1   // this slot is covered by a local row
                return@forEach
            }
            restored++
            secondaryBinderDao.insertAtEnd(
                SecondaryBinderEntry(
                    // Card History's pokemonId has no recoverable original value from a
                    // snapshot slot (only slotName/cardId/imageUrl/language are carried) and
                    // the field is unused by any query in SecondaryBinderDao — safe fallback.
                    pokemonId = snap.slotName,
                    pokemonName = snap.slotName,
                    cardId = cardId,
                    cardImageUrl = snap.imageUrl ?: "",
                    language = snap.language
                )
            )
        }
        return SnapshotApplyCounts(restored, cleared = 0, skipped = 0)
    }
}
