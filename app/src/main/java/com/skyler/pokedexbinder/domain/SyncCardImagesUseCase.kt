package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.local.ConnectingArtDao
import com.skyler.pokedexbinder.data.local.MainBinderDao
import com.skyler.pokedexbinder.data.local.PersonalCollectionDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.UnownBinderDao
import com.skyler.pokedexbinder.data.remote.TcgdexApi
import com.skyler.pokedexbinder.data.remote.TcgdexIds
import com.skyler.pokedexbinder.data.remote.TcgdexRef
import com.skyler.pokedexbinder.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @property checked cards looked up this run
 * @property updated cards whose blank rows were filled
 * @property stillMissing lookups that succeeded but have no image yet
 * @property unsyncable blank cards with no TCGdex id (tcgcsv_ / legacy), never looked up
 * @property failed lookups that errored (retried next run)
 */
data class ImageSyncResult(
    val checked: Int,
    val updated: Int,
    val stillMissing: Int,
    val unsyncable: Int,
    val failed: Int
)

/** Fills blank image URLs in the five card tables from TCGdex card detail. Never overwrites a URL. */
@Singleton
class SyncCardImagesUseCase @Inject constructor(
    private val tcgdexApi: TcgdexApi,
    private val mainDao: MainBinderDao,
    private val unownDao: UnownBinderDao,
    private val connectingArtDao: ConnectingArtDao,
    private val secondaryDao: SecondaryBinderDao,
    private val personalDao: PersonalCollectionDao,
    private val settings: SettingsRepository,
    private val resolvers: @JvmSuppressWildcards Set<BlankImageResolver>
) {
    private val mutex = Mutex()

    /** Manual run: uncapped, waits for a running sync. */
    suspend fun sync(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImageSyncResult =
        mutex.withLock { run(Int.MAX_VALUE, System.currentTimeMillis(), onProgress) }

    /** Auto run: null if synced within 24h or another sync is running; capped at [MAX_AUTO_IDS] cards. */
    suspend fun syncIfDue(now: Long = System.currentTimeMillis()): ImageSyncResult? {
        if (now - settings.getLastImageSyncAt() < AUTO_SYNC_INTERVAL_MS) return null
        if (!mutex.tryLock()) return null
        try {
            return run(MAX_AUTO_IDS, now) { _, _ -> }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun run(limit: Int, now: Long, onProgress: (Int, Int) -> Unit): ImageSyncResult {
        val blankIds = (mainDao.blankImageCardIds() + unownDao.blankImageCardIds() +
            connectingArtDao.blankImageCardIds() + secondaryDao.blankImageCardIds() +
            personalDao.blankImageCardIds()).distinct()
        val refs = blankIds.associateWith { TcgdexIds.parse(it) }
        val unsyncable = refs.count { it.value == null }
        // Languages TCGdex has no images for go last, so the capped auto run cannot spend itself on
        // them every day while resolvable ids wait. ponytail: still starves if >MAX_AUTO_IDS
        // resolvable-language ids stay image-less; rotate an offset if that ever happens.
        val targets = refs.mapNotNull { (id, ref) -> ref?.let { id to it } }
            .sortedBy { (_, ref) -> ref.lang in LANGS_WITHOUT_TCGDEX_IMAGES }
            .take(limit)

        val updated = AtomicInteger()
        val stillMissing = AtomicInteger()
        val failed = AtomicInteger()
        val done = AtomicInteger()
        onProgress(0, targets.size)
        coroutineScope {
            targets.chunked(CONCURRENCY).forEach { chunk ->
                chunk.map { (cardId, ref) ->
                    async {
                        when (syncOne(cardId, ref)) {
                            Outcome.UPDATED -> updated.incrementAndGet()
                            Outcome.MISSING -> stillMissing.incrementAndGet()
                            Outcome.FAILED -> failed.incrementAndGet()
                        }
                        onProgress(done.incrementAndGet(), targets.size)
                    }
                }.awaitAll()
            }
        }
        // An all-failed run (offline, TCGdex down) is not stamped so the next open retries.
        if (targets.isEmpty() || failed.get() < targets.size) settings.setLastImageSyncAt(now)
        return ImageSyncResult(targets.size, updated.get(), stillMissing.get(), unsyncable, failed.get())
    }

    private enum class Outcome { UPDATED, MISSING, FAILED }

    private suspend fun syncOne(cardId: String, ref: TcgdexRef): Outcome {
        val found = try {
            tcgdexApi.getCard(ref.lang, ref.id).image?.takeIf { it.isNotBlank() }?.let(TcgdexIds::imageUrl)
                ?: resolvers.firstNotNullOfOrNull { it.resolve(ref) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.FAILED
        }
        val url = found ?: return Outcome.MISSING
        mainDao.fillBlankImage(cardId, url)
        unownDao.fillBlankImage(cardId, url)
        connectingArtDao.fillBlankImage(cardId, url)
        secondaryDao.fillBlankImage(cardId, url)
        personalDao.fillBlankImage(cardId, url)
        return Outcome.UPDATED
    }

    companion object {
        const val AUTO_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val MAX_AUTO_IDS = 400
        private const val CONCURRENCY = 4
        private val LANGS_WITHOUT_TCGDEX_IMAGES = setOf("ko", "zh-cn")
    }
}
