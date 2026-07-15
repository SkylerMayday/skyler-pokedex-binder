package com.skyler.pokedexbinder.publish

import android.util.Base64
import android.util.Log
import com.skyler.pokedexbinder.data.local.ConnectingArtGroup
import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.local.PersonalCollectionCache
import com.skyler.pokedexbinder.data.local.PersonalCollectionEntry
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.remote.DiscordApi
import com.skyler.pokedexbinder.data.remote.DiscordWebhookPayload
import com.skyler.pokedexbinder.data.remote.GitHubApi
import com.skyler.pokedexbinder.data.remote.GitHubContentDto
import com.skyler.pokedexbinder.data.remote.GitHubPutRequest
import com.skyler.pokedexbinder.publish.model.BinderSnapshot
import com.skyler.pokedexbinder.publish.model.ChangeType
import com.skyler.pokedexbinder.publish.model.Changelog
import com.skyler.pokedexbinder.publish.model.ChangelogEntry
import com.skyler.pokedexbinder.publish.model.CHANGELOG_MAX_ENTRIES
import com.skyler.pokedexbinder.publish.model.PublishDiff
import com.skyler.pokedexbinder.publish.model.PublishSummaryCounts
import com.skyler.pokedexbinder.publish.model.SlotChange
import com.skyler.pokedexbinder.publish.model.SlotDelta
import com.skyler.pokedexbinder.publish.model.SnapshotBinder
import com.skyler.pokedexbinder.publish.model.SnapshotSection
import com.skyler.pokedexbinder.publish.model.SnapshotSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.ConnectingArtRepository
import com.skyler.pokedexbinder.repository.PersonalCollectionRepository
import com.skyler.pokedexbinder.repository.PublishConfig
import com.skyler.pokedexbinder.repository.PublishSettingsRepository
import com.skyler.pokedexbinder.repository.UnownBinderRepository
import com.squareup.moshi.Moshi
import retrofit2.Response
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val BINDER_JSON_PATH = "binder.json"
private const val CHANGELOG_JSON_PATH = "changelog.json"
private const val POKEDEX_TOTAL = 1025            // matches Gen IX end range

private const val BINDER_ID_POKEDEX = "pokedex"
private const val BINDER_NAME_POKEDEX = "Pokédex"
private const val BINDER_ID_CARD_HISTORY = "cardHistory"
private const val BINDER_NAME_CARD_HISTORY = "Card History"
private const val SECTION_CARD_HISTORY = "Card History"
private const val SLOT_TYPE_BASE = "BASE"

private const val BINDER_ID_CONNECTING_ART = "connectingArt"
private const val BINDER_NAME_CONNECTING_ART = "Connecting Art"
private const val BINDER_ID_PERSONAL_COLLECTION = "personalCollection"
private const val BINDER_NAME_PERSONAL_COLLECTION = "Personal Collection"
private const val BINDER_ID_UNOWN = "unown"
private const val BINDER_NAME_UNOWN = "Unown"
private const val SECTION_UNOWN = "Unown"

sealed interface PublishStep {
    data object FetchingCurrent : PublishStep
    data object Uploading : PublishStep
    data object NotifyingDiscord : PublishStep
    data class Done(val diff: PublishDiff) : PublishStep
    data object NoChanges : PublishStep
}

sealed interface PublishResult {
    data class Success(val diff: PublishDiff, val elapsedMs: Long) : PublishResult
    data object NoChanges : PublishResult
    data class Failure(val step: PublishStep, val message: String) : PublishResult
}

class PublishFailedException(val step: PublishStep, message: String) : Exception(message)

@Singleton
class PublishRepository @Inject constructor(
    private val gitHubApi: GitHubApi,
    private val discordApi: DiscordApi,
    private val moshi: Moshi,
    private val publishSettingsRepository: PublishSettingsRepository,
    private val binderRepository: BinderRepository,
    private val secondaryBinderDao: SecondaryBinderDao,
    private val connectingArtRepository: ConnectingArtRepository,
    private val personalCollectionRepository: PersonalCollectionRepository,
    private val unownBinderRepository: UnownBinderRepository
) {

    /** Public page URL for the configured owner/repo, e.g. https://skylermayday.github.io/binders-pokedex-binder/ */
    private fun publicPageUrl(config: PublishConfig): String =
        "https://${config.githubOwner}.github.io/${config.githubRepo}/"

    suspend fun publish(onStep: (PublishStep) -> Unit): PublishResult {
        val start = System.currentTimeMillis()
        var currentStep: PublishStep = PublishStep.FetchingCurrent

        val config = publishSettingsRepository.getConfig()
        if (config.githubPat.isBlank() || config.githubOwner.isBlank() || config.githubRepo.isBlank()) {
            return PublishResult.Failure(
                PublishStep.FetchingCurrent,
                "GitHub not configured — add PAT and repo in Settings"
            )
        }

        return try {
            currentStep = PublishStep.FetchingCurrent
            onStep(currentStep)
            val auth = "Bearer ${config.githubPat}"

            val (baseline, binderSha) = fetchBaselineSnapshot(auth, config)

            val entries = binderRepository.getAllEntries()
            val secondaryEntries = if (config.publishCardHistory) secondaryBinderDao.getAll() else emptyList()

            // Content-gated binders — always read (no PublishConfig toggle, per spec non-goal).
            val connectingArtGroups = connectingArtRepository.getAllGroups()
            val connectingArtSlots = connectingArtRepository.getAllSlots()
            val personalCache = personalCollectionRepository.getAllCache()
            val personalEntries = personalCollectionRepository.getAllEntries()
            val unownEntries = unownBinderRepository.getAllEntries()
            val nextSnapshot = buildSnapshot(
                entries, secondaryEntries, config,
                connectingArtGroups, connectingArtSlots, personalCache, personalEntries, unownEntries
            )

            val diff = computeDiff(baseline, nextSnapshot)

            if (!diff.hasChanges && !diff.isFirstPublish) {
                currentStep = PublishStep.NoChanges
                onStep(currentStep)
                return PublishResult.NoChanges
            }

            currentStep = PublishStep.Uploading
            onStep(currentStep)

            // a. Upload binder.json
            val binderJson = moshi.adapter(BinderSnapshot::class.java).toJson(nextSnapshot)
            val binderPutResponse = gitHubApi.putContent(
                auth = auth,
                owner = config.githubOwner,
                repo = config.githubRepo,
                path = BINDER_JSON_PATH,
                body = GitHubPutRequest(
                    message = "Publish binder update",
                    content = encodeBase64(binderJson),
                    sha = binderSha
                )
            )
            if (!binderPutResponse.isSuccessful) {
                throw PublishFailedException(
                    PublishStep.Uploading,
                    "GitHub upload of binder.json failed: HTTP ${binderPutResponse.code()}"
                )
            }

            // b. Fetch + update changelog.json
            val changelogGetResponse = gitHubApi.getContent(
                auth = auth,
                owner = config.githubOwner,
                repo = config.githubRepo,
                path = CHANGELOG_JSON_PATH
            )
            val (existingChangelog, changelogSha) = when {
                changelogGetResponse.code() == 404 -> Changelog(emptyList()) to null
                changelogGetResponse.isSuccessful -> {
                    val dto = changelogGetResponse.body()
                    val decoded = dto?.content?.let { decodeBase64(it) }
                    val parsed = decoded?.let {
                        moshi.adapter(Changelog::class.java).fromJson(it)
                    } ?: Changelog(emptyList())
                    parsed to dto?.sha
                }
                else -> throw PublishFailedException(
                    PublishStep.Uploading,
                    "Failed to fetch current changelog.json: HTTP ${changelogGetResponse.code()}"
                )
            }

            val newEntry = ChangelogEntry(
                publishedAt = nextSnapshot.publishedAt,
                summary = PublishSummaryCounts(
                    added = diff.added,
                    replaced = diff.replaced,
                    removed = diff.removed,
                    pokedexComplete = diff.pokedexComplete,
                    pokedexTotal = diff.pokedexTotal
                ),
                changes = diff.deltas.map { delta ->
                    SlotChange(
                        type = delta.type.name,
                        slotId = delta.slotId,
                        slotName = delta.displayName,
                        cardSet = delta.cardSet
                    )
                }
            )
            val updatedChangelog = Changelog(
                entries = (listOf(newEntry) + existingChangelog.entries).take(CHANGELOG_MAX_ENTRIES)
            )
            val changelogJson = moshi.adapter(Changelog::class.java).toJson(updatedChangelog)
            val changelogPutResponse = gitHubApi.putContent(
                auth = auth,
                owner = config.githubOwner,
                repo = config.githubRepo,
                path = CHANGELOG_JSON_PATH,
                body = GitHubPutRequest(
                    message = "Update changelog",
                    content = encodeBase64(changelogJson),
                    sha = changelogSha
                )
            )
            if (!changelogPutResponse.isSuccessful) {
                // binder.json is already committed at this point. Per spec §2.7 step 5b,
                // we still treat this as a hard failure and skip the webhook so the user
                // retries — a known imperfection (changelog can drift from binder on retry
                // since the retry will re-diff against the now-updated binder.json).
                throw PublishFailedException(
                    PublishStep.Uploading,
                    "GitHub upload of changelog.json failed: HTTP ${changelogPutResponse.code()}"
                )
            }

            // Notify Discord
            currentStep = PublishStep.NotifyingDiscord
            onStep(currentStep)
            if (config.discordWebhookUrl.isNotBlank()) {
                val embed = buildEmbed(diff, publicPageUrl(config))
                val webhookResponse = discordApi.sendWebhook(
                    config.discordWebhookUrl,
                    DiscordWebhookPayload(embeds = listOf(embed))
                )
                if (!webhookResponse.isSuccessful) {
                    throw PublishFailedException(
                        PublishStep.NotifyingDiscord,
                        "Discord notification failed: HTTP ${webhookResponse.code()}"
                    )
                }
            } else {
                Log.w("PublishRepository", "Discord webhook not configured — skipping notification")
            }

            currentStep = PublishStep.Done(diff)
            onStep(currentStep)
            PublishResult.Success(diff, System.currentTimeMillis() - start)
        } catch (e: PublishFailedException) {
            PublishResult.Failure(e.step, e.message ?: "Unknown error")
        } catch (e: Throwable) {
            PublishResult.Failure(currentStep, e.message ?: "Unknown error")
        }
    }

    /** Returns baseline snapshot (or null if none exists yet) and its sha (or null). */
    internal suspend fun fetchBaselineSnapshot(
        auth: String,
        config: PublishConfig
    ): Pair<BinderSnapshot?, String?> {
        val response: Response<GitHubContentDto> = gitHubApi.getContent(
            auth = auth,
            owner = config.githubOwner,
            repo = config.githubRepo,
            path = BINDER_JSON_PATH
        )
        return when {
            response.code() == 404 -> null to null
            response.isSuccessful -> {
                val dto = response.body()
                val decoded = dto?.content?.let { decodeBase64(it) }
                val snapshot = decoded?.let { moshi.adapter(BinderSnapshot::class.java).fromJson(it) }
                snapshot to dto?.sha
            }
            else -> throw PublishFailedException(
                PublishStep.FetchingCurrent,
                "Failed to fetch current binder.json: HTTP ${response.code()}"
            )
        }
    }

    internal fun buildSnapshot(
        entries: List<MainBinderEntry>,
        secondaryEntries: List<SecondaryBinderEntry>,
        config: PublishConfig,
        connectingArtGroups: List<ConnectingArtGroup> = emptyList(),
        connectingArtSlots: List<ConnectingArtSlot> = emptyList(),
        personalCache: List<PersonalCollectionCache> = emptyList(),
        personalEntries: List<PersonalCollectionEntry> = emptyList(),
        unownEntries: List<UnownBinderEntry> = emptyList()
    ): BinderSnapshot {
        val binders = mutableListOf<SnapshotBinder>()

        if (config.publishPokedex) {
            val bySection = entries.groupBy { sectionNameFor(it) }
            val sections = SECTION_ORDER.mapNotNull { sectionName ->
                val sectionEntries = bySection[sectionName] ?: return@mapNotNull null
                if (sectionEntries.isEmpty()) return@mapNotNull null
                SnapshotSection(
                    name = sectionName,
                    slots = sectionEntries
                        .sortedWith(compareBy({ it.dexNumber }, { it.dexOrder }))
                        .map { it.toSnapshotSlot() }
                )
            }
            binders += SnapshotBinder(id = BINDER_ID_POKEDEX, name = BINDER_NAME_POKEDEX, sections = sections)
        }

        if (config.publishCardHistory) {
            val slots = secondaryEntries.map { entry ->
                SnapshotSlot(
                    dexNumber = 0,
                    slotName = entry.pokemonName,
                    slotType = SLOT_TYPE_BASE,
                    slotId = "${entry.pokemonId}-${entry.id}",
                    cardId = entry.cardId,
                    cardName = null,
                    cardSet = null,
                    imageUrl = entry.cardImageUrl
                )
            }
            binders += SnapshotBinder(
                id = BINDER_ID_CARD_HISTORY,
                name = BINDER_NAME_CARD_HISTORY,
                sections = listOf(SnapshotSection(name = SECTION_CARD_HISTORY, slots = slots))
            )
        }

        // Connecting Art — content-gated. Include a group's section only if it has ≥1 assigned slot.
        // Emit ALL of an included group's slots (empty ones too) to preserve grid layout.
        val caSlotsByGroup = connectingArtSlots.groupBy { it.groupId }
        val caSections = connectingArtGroups
            .sortedWith(compareBy({ it.position }, { it.id }))
            .mapNotNull { group ->
                val groupSlots = caSlotsByGroup[group.id].orEmpty()
                if (groupSlots.none { it.cardId != null }) return@mapNotNull null   // skip empty group
                SnapshotSection(
                    name = group.name,
                    slots = groupSlots
                        .sortedBy { it.slotIndex }
                        .map { slot ->
                            SnapshotSlot(
                                dexNumber = 0,
                                slotName = "${group.name} #${slot.slotIndex + 1}",
                                slotType = SLOT_TYPE_BASE,
                                slotId = "ca-${group.id}-${slot.slotIndex}",
                                cardId = slot.cardId,
                                cardName = slot.cardName,
                                cardSet = null,                 // CA slots carry no set name
                                imageUrl = slot.cardImageUrl,
                                owned = slot.owned              // R3
                            )
                        }
                )
            }
        if (caSections.isNotEmpty()) {
            binders += SnapshotBinder(BINDER_ID_CONNECTING_ART, BINDER_NAME_CONNECTING_ART, caSections)
        }

        // Personal Collection — content-gated. One section per fixed Pokémon section that has ≥1 cache row.
        // Publish ALL cached cards (owned + unowned); owned flag from personal_collection_entry.
        val pcOwnedIds = personalEntries.filter { it.owned }.map { it.cardId }.toSet()
        val pcCacheByKey = personalCache.groupBy { it.pokemonKey }
        val pcSections = PERSONAL_COLLECTION_SECTION_ORDER.mapNotNull { (key, title) ->
            val rows = pcCacheByKey[key].orEmpty()
            if (rows.isEmpty()) return@mapNotNull null          // skip empty section
            SnapshotSection(
                name = title,
                slots = rows
                    .sortedByDescending { it.releaseDate }       // same ordering as the app's cache query
                    .map { row ->
                        SnapshotSlot(
                            dexNumber = 0,
                            slotName = row.name,
                            slotType = SLOT_TYPE_BASE,
                            slotId = row.cardId,                 // cardId is the PK — globally unique
                            cardId = row.cardId,
                            cardName = row.name,
                            cardSet = row.setName,
                            imageUrl = row.imageUrl,
                            owned = row.cardId in pcOwnedIds     // R6: absent entry => false
                        )
                    }
            )
        }
        if (pcSections.isNotEmpty()) {
            binders += SnapshotBinder(BINDER_ID_PERSONAL_COLLECTION, BINDER_NAME_PERSONAL_COLLECTION, pcSections)
        }

        // Unown — content-gated, single flat section. Only publish if at least one letter is assigned.
        if (unownEntries.any { it.assignedCardId != null }) {
            val unownSlots = unownEntries
                .sortedBy { it.position }
                .map { entry ->
                    SnapshotSlot(
                        dexNumber = 0,
                        slotName = "Unown ${entry.letterId}",
                        slotType = SLOT_TYPE_BASE,
                        slotId = "unown-${entry.letterId}",
                        cardId = entry.assignedCardId,
                        cardName = entry.assignedCardName,
                        cardSet = entry.assignedCardSetName,
                        imageUrl = entry.assignedCardImageUrl
                    )
                }
            binders += SnapshotBinder(
                id = BINDER_ID_UNOWN,
                name = BINDER_NAME_UNOWN,
                sections = listOf(SnapshotSection(name = SECTION_UNOWN, slots = unownSlots))
            )
        }

        return BinderSnapshot(
            publishedAt = OffsetDateTime.now(ZoneOffset.of("+08:00")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            binders = binders
        )
    }

    private fun MainBinderEntry.toSnapshotSlot(): SnapshotSlot = SnapshotSlot(
        dexNumber = dexNumber,
        slotName = pokemonName,
        slotType = slotType.uppercase(),
        slotId = pokemonId,
        cardId = assignedCardId,
        cardName = assignedCardName,
        cardSet = assignedCardSetName,
        imageUrl = assignedCardImageUrl
    )

    internal fun computeDiff(baseline: BinderSnapshot?, next: BinderSnapshot): PublishDiff {
        val isFirstPublish = baseline == null

        val baselineSlots: Map<String, SnapshotSlot> = baseline
            ?.binders.orEmpty()
            .flatMap { it.sections }
            .flatMap { it.slots }
            .associateBy { it.slotId }

        val nextSlots: Map<String, SnapshotSlot> = next.binders
            .flatMap { it.sections }
            .flatMap { it.slots }
            .associateBy { it.slotId }

        val deltas = mutableListOf<SlotDelta>()

        if (isFirstPublish) {
            nextSlots.values.forEach { slot ->
                if (slot.cardId != null) {
                    deltas += SlotDelta(
                        type = ChangeType.ADDED,
                        slotId = slot.slotId,
                        displayName = slot.cardName ?: slot.slotName,
                        cardSet = slot.cardSet
                    )
                }
            }
        } else {
            val allSlotIds = baselineSlots.keys + nextSlots.keys
            allSlotIds.forEach { slotId ->
                val baselineSlot = baselineSlots[slotId]
                val nextSlot = nextSlots[slotId]
                val baselineCardId = baselineSlot?.cardId
                val nextCardId = nextSlot?.cardId

                when {
                    baselineCardId == null && nextCardId != null -> {
                        deltas += SlotDelta(
                            type = ChangeType.ADDED,
                            slotId = slotId,
                            displayName = nextSlot.cardName ?: nextSlot.slotName,
                            cardSet = nextSlot.cardSet
                        )
                    }
                    baselineCardId != null && nextCardId == null -> {
                        deltas += SlotDelta(
                            type = ChangeType.REMOVED,
                            slotId = slotId,
                            displayName = baselineSlot.cardName ?: baselineSlot.slotName,
                            cardSet = baselineSlot.cardSet
                        )
                    }
                    baselineCardId != null && nextCardId != null && baselineCardId != nextCardId -> {
                        deltas += SlotDelta(
                            type = ChangeType.REPLACED,
                            slotId = slotId,
                            displayName = nextSlot.cardName ?: nextSlot.slotName,
                            cardSet = nextSlot.cardSet
                        )
                    }
                    baselineCardId != null && nextCardId != null &&
                        baselineCardId == nextCardId &&
                        baselineSlot!!.owned != nextSlot!!.owned -> {
                        deltas += SlotDelta(
                            type = ChangeType.REPLACED,
                            slotId = slotId,
                            displayName = nextSlot.cardName ?: nextSlot.slotName,
                            cardSet = nextSlot.cardSet
                        )
                    }
                    // both null, or both non-null and equal → no change
                }
            }
        }

        val pokedexComplete = next.binders
            .firstOrNull { it.id == BINDER_ID_POKEDEX }
            ?.sections.orEmpty()
            .flatMap { it.slots }
            .count { it.slotType == SLOT_TYPE_BASE && it.cardId != null }

        return PublishDiff(
            deltas = deltas,
            isFirstPublish = isFirstPublish,
            pokedexComplete = pokedexComplete,
            pokedexTotal = POKEDEX_TOTAL
        )
    }

    private fun encodeBase64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decodeBase64(content: String): String =
        String(Base64.decode(content.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
}
