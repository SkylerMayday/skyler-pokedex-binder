package com.skyler.pokedexbinder.data.remote

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caps the product-list downloads one search/scan may start. The groups list is not counted: the
 * failure cooldown in [TcgcsvJapanSource] already limits it to one call per window, process-wide.
 */
class FetchBudget(initial: Int) {
    var left: Int = initial
        private set

    @Synchronized
    fun tryTake(): Boolean {
        if (left <= 0) return false
        left--
        return true
    }
}

@JsonClass(generateAdapter = true)
internal data class CachedGroup(val g: Int, val a: String, val n: String, val p: String)

@JsonClass(generateAdapter = true)
internal data class GroupsFile(val fetchedAt: Long, val groups: List<CachedGroup>)

/** [t] is the numerator of the product's `Number` ("048/076" -> 48). */
@JsonClass(generateAdapter = true)
internal data class CachedItem(val i: Int, val n: String, val t: Int)

@JsonClass(generateAdapter = true)
internal data class ProductsFile(val fetchedAt: Long, val items: List<CachedItem>)

/**
 * Image URLs for Japanese cards from TCGCSV (TCGplayer category 85), cached on disk under
 * `cacheDir/tcgcsv_ja/`. Image only, never identity. Join: lowercase group abbreviation == TCGdex set id
 * and `Number` numerator == localId (the denominator is ignored; it disagrees with TCGdex for many sets).
 * One lookup costs at most one groups call plus one product-list call; a warm cache costs none.
 */
@Singleton
class TcgcsvJapanSource @Inject constructor(
    private val api: TcgcsvApi,
    @ApplicationContext context: Context
) {
    internal var nowMs: () -> Long = System::currentTimeMillis
    internal var cacheRoot: File = File(context.cacheDir, "tcgcsv_ja")
    internal var log: (String) -> Unit = { android.util.Log.i("TcgcsvJa", it) }

    private val moshi = Moshi.Builder().build()
    private val groupsAdapter: JsonAdapter<GroupsFile> = moshi.adapter(GroupsFile::class.java)
    private val productsAdapter: JsonAdapter<ProductsFile> = moshi.adapter(ProductsFile::class.java)
    private val groupsLock = Mutex()
    private val groupLocks = ConcurrentHashMap<Int, Mutex>()
    private val memFiles = ConcurrentHashMap<Int, ProductsFile>()
    @Volatile private var memGroups: GroupsFile? = null
    /** Earliest time the groups list may be requested again after a failed refresh (0 = no cooldown). */
    private var groupsRetryAt = 0L

    /** `_400w` image of the Japanese card [setId]-[localId], or null. Never throws. */
    suspend fun imageUrl(setId: String, localId: String, budget: FetchBudget? = null): String? = try {
        lookup(setId, localId, budget)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private class Hit(val item: CachedItem, val fetched: Boolean)

    private suspend fun lookup(setId: String, localId: String, budget: FetchBudget?): String? {
        val number = localId.trim().toIntOrNull() ?: return null
        val groups = loadGroups() ?: return null
        for (group in candidateGroups(groups, setId)) {
            val hit = findInGroup(group, number, budget) ?: continue
            log("$setId-$localId -> ${hit.item.i} (${if (hit.fetched) "fetch" else "cache"})")
            return "$CDN/${hit.item.i}_400w.jpg"
        }
        return null
    }

    /** The group whose name starts "<setId>:" if several share the abbreviation, else the 2 newest. */
    private fun candidateGroups(groups: GroupsFile, setId: String): List<CachedGroup> {
        val matching = groups.groups.filter { it.a.equals(setId, ignoreCase = true) }
        if (matching.size <= 1) return matching
        val prefixed = matching.filter { it.n.startsWith("$setId:", ignoreCase = true) }
        return if (prefixed.isNotEmpty()) prefixed.take(1) else matching.sortedByDescending { it.p }.take(2)
    }

    private suspend fun loadGroups(): GroupsFile? = groupsLock.withLock {
        val cached = memGroups ?: readFile(groupsFile(), groupsAdapter)?.also { memGroups = it }
        if (cached != null && nowMs() - cached.fetchedAt < DAY_MS) return@withLock cached
        // A failed refresh is not retried per lookup: one search or PC list does dozens of lookups.
        if (nowMs() < groupsRetryAt) return@withLock cached
        val fresh = try {
            val groups = api.getJapanGroups().results
                .filter { !it.abbreviation.isNullOrBlank() }
                .map { CachedGroup(it.groupId, it.abbreviation.orEmpty(), it.name, it.publishedOn.orEmpty()) }
            check(groups.isNotEmpty()) { "empty groups list" }
            GroupsFile(nowMs(), groups)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val blocked = e is HttpException && (e.code() == 401 || e.code() == 403)
            groupsRetryAt = nowMs() + if (blocked) GROUPS_BLOCKED_RETRY_MS else GROUPS_RETRY_MS
            return@withLock cached // stale copy (or null) on failure
        }
        groupsRetryAt = 0L
        memGroups = fresh
        writeFile(groupsFile(), groupsAdapter, fresh)
        deleteOrphanFiles(fresh.groups.map { it.g }.toSet())
        fresh
    }

    private suspend fun findInGroup(group: CachedGroup, number: Int, budget: FetchBudget?): Hit? =
        groupLocks.getOrPut(group.g) { Mutex() }.withLock {
            val stored = memFiles[group.g]
                ?: readFile(productsFile(group.g), productsAdapter)?.also { memFiles[group.g] = it }
            var attempted = false
            var fetched = false
            var file = stored
            if (file == null || !isFresh(group, file)) {
                attempted = true
                val downloaded = fetchProducts(group, budget)
                if (downloaded != null) {
                    file = downloaded
                    fetched = true
                }
            }
            if (file == null) return@withLock null // cold and offline/over budget: nothing written
            pick(file, number)?.let { return@withLock Hit(it, fetched) }
            // A miss in a file older than a day may just be a newly added product: refetch once.
            if (!attempted && nowMs() - file.fetchedAt >= DAY_MS) {
                val downloaded = fetchProducts(group, budget)
                pick(downloaded ?: return@withLock null, number)?.let { return@withLock Hit(it, true) }
            }
            null
        }

    /** Fetches and stores [group]'s product list; null when over [budget] or on any failure. */
    private suspend fun fetchProducts(group: CachedGroup, budget: FetchBudget?): ProductsFile? {
        if (budget != null && !budget.tryTake()) return null
        return try {
            val items = api.getJapanProducts(group.g).results.mapNotNull { p ->
                val numerator = p.extendedData.firstOrNull { it.name == "Number" }?.value
                    ?.let { NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                val image = p.imageUrl
                // Only the standard CDN pattern is kept: the URL is rebuilt from the product id.
                if (numerator == null || image == null || !image.endsWith("/${p.productId}_200w.jpg")) null
                else CachedItem(p.productId, p.name, numerator)
            }
            ProductsFile(nowMs(), items).also {
                memFiles[group.g] = it
                writeFile(productsFile(group.g), productsAdapter, it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Lowest productId with this number; "[Set of 2]" and "(... Pattern)" variants only if nothing else. */
    private fun pick(file: ProductsFile, number: Int): CachedItem? {
        val same = file.items.filter { it.t == number }
        val plain = same.filter { '[' !in it.n && !it.n.endsWith(")") }
        return plain.ifEmpty { same }.minByOrNull { it.i }
    }

    /** Sets published over 90 days ago no longer get new products, so their file never expires. */
    private fun isFresh(group: CachedGroup, file: ProductsFile): Boolean {
        val now = nowMs()
        val publishedMs = runCatching {
            LocalDate.parse(group.p.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
        if (publishedMs != null && now - publishedMs > SETTLED_AGE_MS) return true
        return now - file.fetchedAt < DAY_MS
    }

    private fun groupsFile() = File(cacheRoot, "groups.json")
    private fun productsFile(groupId: Int) = File(cacheRoot, "g$groupId.json")

    private suspend fun <T> readFile(file: File, adapter: JsonAdapter<T>): T? = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) adapter.fromJson(file.readText()) else null
        } catch (e: Exception) {
            null // corrupt or unreadable: treated as missing and refetched
        }
    }

    private suspend fun <T> writeFile(file: File, adapter: JsonAdapter<T>, value: T) = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(adapter.toJson(value))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // Cache write is best effort; the in-memory copy still serves this process.
        }
    }

    /** Deletes product files of groups that left the groups list. */
    private suspend fun deleteOrphanFiles(liveIds: Set<Int>) {
        memFiles.keys.retainAll(liveIds)
        withContext(Dispatchers.IO) {
            cacheRoot.listFiles()?.forEach { f ->
                val id = PRODUCT_FILE.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
                if (id != null && id !in liveIds) f.delete()
            }
        }
    }

    private companion object {
        const val CDN = "https://tcgplayer-cdn.tcgplayer.com/product"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val SETTLED_AGE_MS = 90L * DAY_MS
        const val GROUPS_RETRY_MS = 15L * 60 * 1000
        /** 401/403 is the host blocking us (it blocked a User-Agent after a burst): back off longer. */
        const val GROUPS_BLOCKED_RETRY_MS = 6L * 60 * 60 * 1000
        val NUMBER = Regex("""^\D*0*(\d+)""")
        val PRODUCT_FILE = Regex("""g(\d+)\.json""")
    }
}
