package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.data.remote.TcgCardDto
import com.skyler.pokedexbinder.data.remote.TcgcsvApi
import com.skyler.pokedexbinder.data.remote.TcgcsvProductDto
import com.skyler.pokedexbinder.data.remote.TcgdexApi
import com.skyler.pokedexbinder.data.remote.TcgdexCardBriefDto
import com.skyler.pokedexbinder.domain.ParsedCardInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

sealed class SearchProgress {
    /** pokemontcg.io + TCGdex results — rendered immediately. */
    data class Fast(val cards: List<TcgCard>) : SearchProgress()
    /** Fast results merged with the completed TCGCSV scan (appends + image backfill). */
    data class Complete(val cards: List<TcgCard>) : SearchProgress()
}

@Singleton
class CardSearchRepository @Inject constructor(
    private val api: PokemonTcgApi,
    private val tcgdexApi: TcgdexApi,
    private val tcgcsvApi: TcgcsvApi
) {
    suspend fun searchByParsedInfo(info: ParsedCardInfo): List<TcgCard> = coroutineScope {
        val name   = info.cardName
        val number = info.cardNumber
        val total  = info.setTotal
        val dex    = info.dexNumber

        // Specific queries (name+number or dex+number) — run on primary only, return immediately if found
        val specificQueries = buildList {
            if (name != null && number != null && total != null)
                add("${nameQuery(name)} number:\"$number\" set.total:$total")
            if (name != null && number != null)
                add("${nameQuery(name)} number:\"$number\"")
            if (number != null && total != null)
                add("number:\"$number\" set.total:$total")
            if (dex != null && number != null && total != null)
                add("nationalPokedexNumbers:$dex number:\"$number\" set.total:$total")
            if (dex != null && number != null)
                add("nationalPokedexNumbers:$dex number:\"$number\"")
        }
        for (query in specificQueries) {
            val results = safeSearch(query)
            if (results.isNotEmpty()) return@coroutineScope results
        }

        // Broad name/dex queries — merge both APIs so promo variants aren't missed
        if (name != null) {
            val primary = async { safeSearch(nameQuery(name)) }
            val secondary = async { tcgdexSearchByName(name) }
            val merged = mergeResults(primary.await(), secondary.await())
            if (merged.isNotEmpty()) return@coroutineScope merged
        }
        if (dex != null) {
            val results = safeSearch("nationalPokedexNumbers:$dex")
            if (results.isNotEmpty()) return@coroutineScope results
        }
        if (number != null) safeSearch("number:\"$number\"") else emptyList()
    }

    suspend fun searchByNameAndNumber(name: String, number: String): List<TcgCard> =
        safeSearch("${nameQuery(name)} number:\"$number\"")
            .ifEmpty { safeSearch(nameQuery(name)) }

    suspend fun searchByName(name: String): List<TcgCard> = coroutineScope {
        val primary = async { safeSearch(nameQuery(name)) }
        val secondary = async { tcgdexSearchByName(name) }
        mergeResults(primary.await(), secondary.await())
    }

    suspend fun searchByNumber(number: String): List<TcgCard> =
        safeSearch("number:\"$number\"")

    suspend fun searchByNumberAndTotal(number: String, total: String): List<TcgCard> =
        safeSearch("number:\"$number\" set.total:$total")
            .ifEmpty { safeSearch("number:\"$number\"") }

    suspend fun searchByDexNumber(dexNumber: Int): List<TcgCard> =
        safeSearch("nationalPokedexNumbers:$dexNumber")

    suspend fun searchPromosByName(name: String): List<TcgCard> =
        safeSearch("${nameQuery(name)} rarity:Promo")

    /**
     * Retry-on-empty fallback source: TCGCSV has no name-search endpoint, so this fetches all
     * Pokémon groups and fans out to per-group product listings (bounded concurrency), filtering
     * by name client-side. Never throws — any failure yields an empty list.
     */
    suspend fun searchTcgcsvByName(name: String): List<TcgCard> = try {
        coroutineScope {
            val query = name.trim().lowercase()
            if (query.isEmpty()) return@coroutineScope emptyList()

            val groups = tcgcsvApi.getGroups().results
            val groupNameById = groups.associate { it.groupId to it.name }

            // Token-based match, not whole-phrase substring: a query like "CLB Mr Mime" or
            // "Victini SVP" has words (set prefixes/abbreviations) that never appear in TCGCSV's
            // own product name/cleanName fields, so a strict `.contains(query)` check silently
            // misses cards that genuinely exist. Split into words >=3 chars (skips noise like
            // "a"/"of") and match if ANY of them appears in the product text — verified live
            // 2026-07-12 that this is required for "CLB Mr Mime" to find "Mr. Mime".
            val queryTokens = query.split(Regex("\\s+")).filter { it.length >= 3 }
                .ifEmpty { listOf(query) }

            // Bounded fan-out: process groups in chunks so we never open 200+ sockets at once.
            val matches = mutableListOf<TcgCard>()
            groups.map { it.groupId }.chunked(TCGCSV_GROUP_CONCURRENCY).forEach { chunk ->
                val chunkResults = chunk.map { groupId ->
                    async {
                        runCatching { tcgcsvApi.getProducts(groupId).results }
                            .getOrDefault(emptyList())
                            .filter { product ->
                                val haystack = (product.name + " " + product.cleanName.orEmpty()).lowercase()
                                queryTokens.any { token -> haystack.contains(token) }
                            }
                            .map { it.toDomain(groupNameById[it.groupId] ?: "") }
                    }
                }.awaitAll().flatten()
                matches += chunkResults
            }

            // Dedupe against the existing name|number|setName key (reuse dedupeKey).
            val seen = HashSet<String>()
            matches.filter { seen.add(dedupeKey(it)) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Progressive search: runs the caller-supplied fast source (pokemontcg.io + TCGdex, already
     * merged) and the TCGCSV 217-group scan concurrently. Emits [SearchProgress.Fast] as soon as the
     * fast source resolves, then [SearchProgress.Complete] once TCGCSV finishes and its cards have been
     * merged/back-filled into the fast list.
     *
     * @param tcgcsvQuery the name to scan TCGCSV for (the raw trimmed user query).
     * @param fastSearch  produces the fast (pokemontcg.io + TCGdex) result list.
     *
     * Cancellation: collecting coroutine cancelled -> flow's [coroutineScope] cancels the in-flight
     * TCGCSV scan via structured concurrency. No manual teardown needed.
     */
    fun searchStreaming(
        tcgcsvQuery: String,
        fastSearch: suspend () -> List<TcgCard>
    ): Flow<SearchProgress> = flow {
        coroutineScope {
            // Both start immediately -> the 10-13s TCGCSV cost overlaps the fast search + user reading.
            val fastDeferred = async { fastSearch() }
            val tcgcsvDeferred = async { searchTcgcsvByName(tcgcsvQuery) }

            val fast = fastDeferred.await()
            emit(SearchProgress.Fast(fast))

            val tcgcsv = tcgcsvDeferred.await()
            emit(SearchProgress.Complete(mergeAndBackfillImages(fast, tcgcsv)))
        }
    }

    private fun nameQuery(name: String): String {
        val sanitized = name.trim()
            .replace(" ", "*")
            .replace("-", "*")
            .replace(":", "*")
        return "name:*$sanitized*"
    }

    private suspend fun safeSearch(query: String): List<TcgCard> = try {
        val filtered = if (query.isBlank()) POCKET_EXCLUSION.trim() else "$query $POCKET_EXCLUSION"
        api.searchCards(query = filtered).data.map { it.toDomain() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun TcgCardDto.toDomain() = TcgCard(
        id = id,
        name = name,
        number = number,
        setName = set.name,
        imageUrl = images.large,
        pokemonNames = name.split(" & ").map { it.substringBefore(" ").trim() },
        hp = hp,
        artist = artist,
        setReleaseDate = set.releaseDate
    )

    // ---- Merge helpers ----

    /**
     * Combines primary and secondary results, deduplicating by name+number+setName.
     * Primary results come first; TCGdex fills in cards the primary API is missing.
     */
    private fun mergeResults(primary: List<TcgCard>, secondary: List<TcgCard>): List<TcgCard> {
        val seen = primary.mapTo(HashSet()) { dedupeKey(it) }
        return primary + secondary.filter { dedupeKey(it) !in seen }
    }

    private fun dedupeKey(card: TcgCard) =
        "${card.name.lowercase()}|${card.number.lowercase()}|${card.setName.lowercase()}"

    /**
     * Merges the completed TCGCSV scan into the fast results:
     *  - Fast cards with a blank image get their image patched in from a name+number-matching TCGCSV
     *    product that HAS an image (identity/id unchanged — only [TcgCard.imageUrl] is replaced).
     *  - TCGCSV cards that are NOT the same card as any fast card (by name+number) AND whose dedupeKey
     *    isn't already present are appended.
     *  - A TCGCSV card that IS the same card as a fast card is never appended (it only ever contributes
     *    a possible image backfill) — this is what prevents the "duplicate second Victini" entry.
     */
    private fun mergeAndBackfillImages(
        fast: List<TcgCard>,
        tcgcsv: List<TcgCard>
    ): List<TcgCard> {
        // 1. Backfill blank images in place. Only patch when the fast card's image is blank.
        val patched = fast.map { card ->
            if (card.imageUrl.isBlank()) {
                val match = tcgcsv.firstOrNull { it.imageUrl.isNotBlank() && isSameCard(card, it) }
                if (match != null) card.copy(imageUrl = match.imageUrl) else card
            } else {
                card // existing non-blank image is never overwritten
            }
        }

        // 2. Append genuinely-new TCGCSV cards. Suppress any that are the same card as a fast entry
        //    (whether or not it was used for backfill) so patched cards don't get a duplicate.
        val seenKeys = fast.mapTo(HashSet()) { dedupeKey(it) }
        val appended = tcgcsv.filter { c ->
            dedupeKey(c) !in seenKeys && fast.none { isSameCard(it, c) }
        }

        return patched + appended
    }

    /** Cross-source "same physical card" test used for backfill + de-dup. */
    private fun isSameCard(a: TcgCard, b: TcgCard): Boolean =
        namesMatch(a.name, b.name) && numbersMatch(a.number, b.number)

    private fun namesMatch(a: String, b: String): Boolean {
        val na = a.lowercase().filter { it.isLetterOrDigit() }
        val nb = b.lowercase().filter { it.isLetterOrDigit() }
        return na.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))
    }

    /**
     * Number equality tolerant of cross-source formatting differences:
     *  - TCGCSV numbers arrive as "030/034" (slash fraction) or "SVP208" (promo prefix).
     *  - TCGdex/pokemontcg.io give "208", "58", "SVP208", etc.
     * Strategy: take the part before '/', keep alphanumerics, lowercase, drop leading zeros; compare.
     * If those differ, fall back to comparing the digit-only portions (so "svp208" == "208").
     */
    private fun numbersMatch(a: String, b: String): Boolean {
        val na = normalizeNumber(a)
        val nb = normalizeNumber(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        val da = na.filter { it.isDigit() }.trimStart('0')
        val db = nb.filter { it.isDigit() }.trimStart('0')
        return da.isNotEmpty() && da == db
    }

    private fun normalizeNumber(raw: String): String =
        raw.substringBefore('/').filter { it.isLetterOrDigit() }.lowercase().trimStart('0')

    private suspend fun tcgdexSearchByName(name: String): List<TcgCard> = try {
        tcgdexApi.searchCards(name).map { it.toDomain() }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Maps a TCGdex brief card to [TcgCard].
     * id format: "<setCode>-<localId>" e.g. "swsh3-136", "sve-213"
     * image: base URL without extension; append "/high.webp" for a usable URL.
     */
    private fun TcgdexCardBriefDto.toDomain(): TcgCard {
        val setCode = id.substringBeforeLast("-").uppercase()
        val imageUrl = image?.let { "$it/high.webp" } ?: ""
        return TcgCard(
            id = "tcgdex_$id",
            name = name,
            number = localId,
            setName = setCode,
            imageUrl = imageUrl,
            pokemonNames = name.split(" & ").map { it.substringBefore(" ").trim() }
        )
    }

    private fun TcgcsvProductDto.toDomain(setName: String): TcgCard {
        val number = extendedData.firstOrNull { it.name == "Number" }?.value ?: ""
        return TcgCard(
            id = "tcgcsv_$productId",                       // §spec collision-avoidance prefix
            name = name,
            number = number,
            setName = setName,
            imageUrl = imageUrl ?: "",
            pokemonNames = name.split(" & ").map { it.substringBefore(" ").trim() }
            // hp/artist/setReleaseDate left null — TCGCSV product listings carry no rarity/artist
        )
    }

    companion object {
        private const val POCKET_EXCLUSION = "-set.series:Pocket"
        private const val TCGCSV_GROUP_CONCURRENCY = 8
    }
}
