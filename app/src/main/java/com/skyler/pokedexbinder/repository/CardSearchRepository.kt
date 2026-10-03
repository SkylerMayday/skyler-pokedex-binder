package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.TCGDEX_ASIAN_LANGS
import com.skyler.pokedexbinder.data.remote.FetchBudget
import com.skyler.pokedexbinder.data.remote.TcgcsvApi
import com.skyler.pokedexbinder.data.remote.TcgcsvJapanSource
import com.skyler.pokedexbinder.data.remote.TcgcsvProductDto
import com.skyler.pokedexbinder.data.remote.TcgdexApi
import com.skyler.pokedexbinder.data.remote.TcgdexCardBriefDto
import com.skyler.pokedexbinder.data.remote.TcgdexIds
import com.skyler.pokedexbinder.data.remote.TcgdexSetCatalog
import com.skyler.pokedexbinder.domain.ParsedCardInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed class SearchProgress {
    /** TCGdex results — rendered immediately. */
    data class Fast(val cards: List<TcgCard>) : SearchProgress()
    /** Fast results merged with the completed TCGCSV scan (appends + image backfill). */
    data class Complete(val cards: List<TcgCard>) : SearchProgress()
}

/** Every TCGdex call of a search failed and nothing else answered: "no results" would be a lie. */
class TcgdexUnavailableException :
    Exception("Card database (TCGdex) unreachable. Check your connection and try again.")

private const val JAPAN_IMAGE_FETCHES_PER_SEARCH = 3

/** Per-search tally of TCGdex call outcomes (concurrent callers share one instance). */
internal class CallStats {
    private val ok = AtomicInteger()
    private val failed = AtomicInteger()
    /** Product-list downloads the Japanese image fill may start in this search. */
    val japanBudget = FetchBudget(JAPAN_IMAGE_FETCHES_PER_SEARCH)
    fun succeeded() { ok.incrementAndGet() }
    fun failed() { failed.incrementAndGet() }
    fun allSucceeded(): Boolean = failed.get() == 0

    /** Returns [result]; throws only when it is empty AND every TCGdex call this search made failed. */
    fun <T> check(result: List<T>): List<T> {
        if (result.isEmpty() && failed.get() > 0 && ok.get() == 0) throw TcgdexUnavailableException()
        return result
    }
}

/** [matchedQuery] names which step of the cascade produced [cards], for scanner diagnostics. */
data class ParsedSearchResult(
    val cards: List<TcgCard>,
    val matchedQuery: String,
    /** True when TCGdex matched dex+number+total to exactly one card: identity known without an image. */
    val identityPinned: Boolean = false,
    /** True when a single ja/ko/zh/th/id number+total hit matched the scan's printedName. */
    val nameVerified: Boolean = false,
    /** True when the scan language is Asian (ja/ko/zh/th/id) but every card is an English printing: a stand-in, never auto-accept. */
    val crossLanguage: Boolean = false
)

/**
 * [complete] is false when any TCGdex call of the search failed (e.g. the dex lookup or one
 * language), so [cards] may be missing printings and must not replace a stored full list.
 */
data class NameSearchResult(val cards: List<TcgCard>, val complete: Boolean)

/** Cap on languages tried in any one cascade step. */
internal const val MAX_TCGDEX_LANGS = 3

private val SIMPLIFIED_ZH_TOKENS = setOf("cn", "hans", "sg")

/**
 * Scan language -> ordered TCGdex language codes to try. Null/blank/unknown never fans out to
 * ko/zh/th/id (a miss would cost 4+ calls for a rare gain); unknown codes keep the [en, ja] order.
 */
internal fun tcgdexLangsFor(language: String?): List<String> {
    val lang = language?.trim()?.lowercase()?.replace('_', '-').orEmpty()
    return when {
        lang.isEmpty() || lang == "ja" -> listOf("ja", "en")
        lang == "ko" || lang == "th" || lang == "id" -> listOf(lang, "en")
        lang.startsWith("zh") ->
            if (lang.split('-').any { it in SIMPLIFIED_ZH_TOKENS }) listOf("zh-cn", "zh-tw", "en")
            else listOf("zh-tw", "zh-cn", "en")
        else -> listOf("en", "ja")
    }
}

private val CROSS_LANGUAGE_BASES = setOf("ja", "ko", "zh", "th", "id")

/**
 * Asian-language scan whose cards are all English printings. European languages are excluded:
 * their printings share English set numbering, so an English number+total match is the real card.
 * Null/blank/unknown language = false.
 */
internal fun isCrossLanguageFallback(language: String?, cards: List<TcgCard>): Boolean {
    val base = language?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-').orEmpty()
    return base in CROSS_LANGUAGE_BASES && cards.isNotEmpty() && cards.none { TcgdexIds.isNonEnglish(it.id) }
}

@Singleton
class CardSearchRepository @Inject constructor(
    private val tcgdexApi: TcgdexApi,
    private val tcgcsvApi: TcgcsvApi,
    private val setCatalog: TcgdexSetCatalog,
    private val japanImages: TcgcsvJapanSource
) {
    suspend fun searchByParsedInfo(info: ParsedCardInfo): ParsedSearchResult {
        val stats = CallStats()
        val result = resolveParsed(info, stats).let { it.copy(crossLanguage = isCrossLanguageFallback(info.language, it.cards)) }
        stats.check(result.cards)
        val filled = result.copy(cards = result.cards.withJapanImages(stats))
        // Scanner ja results: confirmCard's global-scan slot match compares English slot names, so
        // seed pokemonNames from Gemini's English name instead of the Japanese card name.
        val names = info.cardName?.let { pokemonNamesOf(it) } ?: return filled
        return filled.copy(cards = filled.cards.map {
            if (TcgdexIds.isNonEnglish(it.id)) it.copy(pokemonNames = names) else it
        })
    }

    private suspend fun resolveParsed(info: ParsedCardInfo, stats: CallStats): ParsedSearchResult {
        val name   = info.cardName
        val number = info.cardNumber
        val total  = info.setTotal?.let { sendableTotal(it) }
        val dex    = info.dexNumber
        val langs  = tcgdexLangsFor(info.language)

        // Species-pinned (dex, then name) before the species-blind number+total steps.
        // Every TCGdex step is exact-number filtered (the API's localId filter is a substring match).
        if (dex != null && number != null && total != null) {
            for (lang in langs) {
                val results = tcgdexFilter(lang, number, total, dex, null, stats)
                if (results.isNotEmpty()) {
                    return ParsedSearchResult(
                        results, "tcgdex-$lang+dex+number+total", identityPinned = results.size == 1
                    )
                }
            }
        }
        if (name != null && number != null && total != null) {
            val results = tcgdexFilter("en", number, total, null, name, stats)
            if (results.isNotEmpty()) {
                return ParsedSearchResult(results, "tcgdex-en+name+number+total")
            }
        }
        // Non-English names are only comparable to the printed name, never Gemini's English
        // name. No printedName = nothing to verify the species-blind hit against, so skip.
        val printedName = info.printedName
        if (printedName != null && number != null && total != null) {
            for (lang in langs.filter { it != "en" }) {
                val results = tcgdexFilter(lang, number, total, null, null, stats).matchingParsedName(printedName)
                if (results.isNotEmpty()) {
                    return ParsedSearchResult(
                        results, "tcgdex-$lang+number+total", nameVerified = results.size == 1
                    )
                }
            }
        }
        if (number != null && total != null) {
            val results = tcgdexFilter("en", number, total, null, null, stats).matchingParsedName(name)
            if (results.isNotEmpty()) return ParsedSearchResult(results, "tcgdex-en+number+total")
        }

        // Looser steps for when the scan missed the set total (or the total filter matched nothing).
        if (name != null && number != null) {
            val results = tcgdexFilter("en", number, null, null, name, stats)
            if (results.isNotEmpty()) return ParsedSearchResult(results, "tcgdex-en+name+number")
        }
        if (dex != null && number != null) {
            for (lang in langs) {
                val results = tcgdexFilter(lang, number, null, dex, null, stats)
                if (results.isNotEmpty()) return ParsedSearchResult(results, "tcgdex-$lang+dex+number")
            }
        }

        // Broad name/dex queries.
        if (name != null) {
            val results = tcgdexSearchByName(name, stats)
            if (results.isNotEmpty()) return ParsedSearchResult(results, "broad-name")
        }
        if (dex != null) {
            // Not name-filtered: the dex already pins species. A dex hit proves the species, not the
            // card, so all same-species candidates go to the hasher and SmartThresholdUseCase decides.
            val results = firstNonEmptyByDex(langs, dex, stats)
            if (results.isNotEmpty()) return ParsedSearchResult(results, "broad-dex")
        }
        // Number alone is thousands of cards on TCGdex: only usable when a name can verify it.
        if (number != null && name != null) {
            val results = tcgdexFilter("en", number, null, null, null, stats).matchingParsedName(name)
            if (results.isNotEmpty()) return ParsedSearchResult(results, "broad-number")
        }
        return ParsedSearchResult(emptyList(), "none-matched")
    }

    /**
     * Manual search + Quick Scan: TCGdex en first, then every non-English printing of the species
     * (ja, ko, zh-tw, zh-cn, th, id). [dexNumber] skips the detail-endpoint dex lookup when the caller already knows it.
     */
    suspend fun searchByNameWithJapanese(name: String, dexNumber: Int? = null): List<TcgCard> =
        searchByNameAllLanguages(name, dexNumber).cards

    /** [searchByNameWithJapanese] plus whether every call succeeded (see [NameSearchResult.complete]). */
    suspend fun searchByNameAllLanguages(name: String, dexNumber: Int? = null): NameSearchResult {
        val stats = CallStats()
        val en = tcgdexSearchByName(name, stats)
        val dex = dexNumber ?: resolveDex(en, name, stats)
        val result = if (dex == null) en else {
            val names = en.bestNameMatch(name)?.pokemonNames ?: pokemonNamesOf(name)
            val nonEnglish = tcgdexAllLangsByDex(dex, stats).map { it.copy(pokemonNames = names) }
            (en + nonEnglish).distinctBy { it.id }
        }
        return NameSearchResult(stats.check(result).withJapanImages(stats), stats.allSucceeded())
    }

    /** All [TCGDEX_ASIAN_LANGS] printings of a species, in list order (6 parallel, each exception-safe). */
    private suspend fun tcgdexAllLangsByDex(dex: Int, stats: CallStats): List<TcgCard> = coroutineScope {
        TCGDEX_ASIAN_LANGS.map { lang -> async { tcgdexFilter(lang, null, null, dex, null, stats) } }
            .awaitAll().flatten()
    }

    /** First language in [langs] with any card of species [dex]; empty when none has one. */
    private suspend fun firstNonEmptyByDex(langs: List<String>, dex: Int, stats: CallStats): List<TcgCard> {
        for (lang in langs) {
            val results = tcgdexFilter(lang, null, null, dex, null, stats)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    /** Species dex from the TCGdex detail endpoint, via the en TCGdex card best matching [name]. */
    private suspend fun resolveDex(en: List<TcgCard>, name: String, stats: CallStats): Int? {
        val card = en.filter { TcgdexIds.parse(it.id)?.lang == "en" }.bestNameMatch(name) ?: return null
        val tcgdexId = TcgdexIds.parse(card.id)?.id ?: return null
        return try {
            tcgdexApi.getCard("en", tcgdexId).dexId?.firstOrNull()
                .also { stats.succeeded() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stats.failed()
            null
        }
    }

    /**
     * Exact normalized-name match first, substring [namesMatch] only as fallback: TCGdex name search
     * is a substring search, so "Mew" can list Mewtwo first and must not resolve to its species.
     */
    private fun List<TcgCard>.bestNameMatch(name: String): TcgCard? {
        val target = normalizeName(name)
        return firstOrNull { normalizeName(it.name) == target } ?: firstOrNull { namesMatch(it.name, name) }
    }

    /**
     * Promo / set-prefixed number lookup ("SWSH260", "SVP208", "MEP001"). TCGdex's localId filter is a
     * substring match, exact-filtered here; SVP/MEP numbers are the bare digits inside their set, so
     * they fall back to a set-scoped query.
     */
    suspend fun searchByNumber(query: String): List<TcgCard> {
        val stats = CallStats()
        val number = query.trim().uppercase()
        val direct = tcgdexFilter("en", number, null, null, null, stats)
        val promo = SET_PREFIXED_PROMO.matchEntire(number)
        val result = if (direct.isNotEmpty() || promo == null) direct else {
            val (prefix, digits) = promo.destructured
            tcgdexCards("en", stats) { tcgdexApi.cardsInSet("en", "eq:${prefix.lowercase()}", digits) }
        }
        return stats.check(result)
    }

    suspend fun searchByDexNumber(dexNumber: Int): List<TcgCard> = coroutineScope {
        val stats = CallStats()
        val en = async { tcgdexFilter("en", null, null, dexNumber, null, stats) }
        val nonEnglish = async { tcgdexAllLangsByDex(dexNumber, stats) }
        val result = en.await() + nonEnglish.await()
        stats.check(result).withJapanImages(stats)
    }

    suspend fun searchPromosByName(name: String): List<TcgCard> {
        val stats = CallStats()
        val result = tcgdexCards("en", stats) { tcgdexApi.searchPromos(name) }
        return stats.check(result)
    }

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
     * Progressive search: runs the caller-supplied fast source (TCGdex) and the TCGCSV 217-group scan
     * concurrently. Emits [SearchProgress.Fast] as soon as the fast source resolves, then
     * [SearchProgress.Complete] once TCGCSV finishes and its cards have been merged/back-filled into
     * the fast list. If the fast source throws (e.g. [TcgdexUnavailableException]) the TCGCSV scan
     * still runs: a non-empty scan is emitted as the only [SearchProgress.Complete], else the
     * fast source's exception is rethrown.
     *
     * @param tcgcsvQuery the name to scan TCGCSV for (the raw trimmed user query).
     * @param fastSearch  produces the fast (TCGdex) result list.
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
            // The failure is carried as a Result: a throwing async child would cancel its sibling scan.
            val fastDeferred = async {
                try {
                    Result.success(fastSearch())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure<List<TcgCard>>(e)
                }
            }
            val tcgcsvDeferred = async { searchTcgcsvByName(tcgcsvQuery) }

            val fast = fastDeferred.await().getOrElse { failure ->
                val tcgcsv = tcgcsvDeferred.await()
                if (tcgcsv.isEmpty()) throw failure
                emit(SearchProgress.Complete(tcgcsv))
                return@coroutineScope
            }
            emit(SearchProgress.Fast(fast))

            val tcgcsv = tcgcsvDeferred.await()
            emit(SearchProgress.Complete(mergeAndBackfillImages(fast, tcgcsv)))
        }
    }

    // ---- Merge helpers ----

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

    /** Keeps only cards whose name matches the parsed name; no parsed name = nothing to verify, unfiltered. */
    private fun List<TcgCard>.matchingParsedName(parsedName: String?): List<TcgCard> {
        val n = parsedName?.takeIf { it.isNotBlank() } ?: return this
        return filter { namesMatch(it.name, n) }
    }

    private fun namesMatch(a: String, b: String): Boolean {
        val na = normalizeName(a)
        val nb = normalizeName(b)
        return na.isNotEmpty() && (na == nb || na.contains(nb) || nb.contains(na))
    }

    // Combining marks are kept: Thai vowel/tone marks (and decomposed kana) are marks, not letters,
    // and dropping them collapses distinct names to a few base characters.
    private fun normalizeName(raw: String): String = raw.lowercase().filter {
        it.isLetterOrDigit() || Character.getType(it).let { t ->
            t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
        }
    }

    /**
     * Number equality tolerant of cross-source formatting differences:
     *  - TCGCSV numbers arrive as "030/034" (slash fraction) or "SVP208" (promo prefix).
     *  - TCGdex gives "208", "58", "SVP208", etc.
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

    private suspend fun tcgdexSearchByName(name: String, stats: CallStats): List<TcgCard> =
        tcgdexCards("en", stats) { tcgdexApi.searchCards(name) }

    /**
     * TCGdex filtered list query. [total] must already be sendable (see [sendableTotal]). Drops
     * TCG Pocket cards and exact-filters [number], since the API's localId filter is a substring match.
     * Never throws: a failure is counted in [stats] and yields an empty list so the cascade falls through.
     */
    private suspend fun tcgdexFilter(
        lang: String, number: String?, total: String?, dex: Int?, name: String?, stats: CallStats
    ): List<TcgCard> = tcgdexCards(lang, stats) {
        tcgdexApi.filterCards(
            lang,
            number?.trimStart('0')?.ifEmpty { "0" },
            total?.let { "eq:$it" },
            dex?.let { "eq:$it" },
            name
        )
    }.filter { number == null || numbersMatch(it.number, number) }

    /** Runs a TCGdex list [call], tallies the outcome in [stats], and maps to cards; failures yield empty. */
    private suspend fun tcgdexCards(
        lang: String, stats: CallStats, call: suspend () -> List<TcgdexCardBriefDto>
    ): List<TcgCard> = try {
        call().toCards(lang).also { stats.succeeded() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        stats.failed()
        emptyList()
    }

    /** Drops TCG Pocket cards (mobile game, `/tcgp/` assets; not physical cards) and maps the rest. */
    private suspend fun List<TcgdexCardBriefDto>.toCards(lang: String): List<TcgCard> {
        val physical = filterNot { it.image?.contains("/tcgp/") == true }
        if (physical.isEmpty()) return emptyList()
        val setNames = if (lang == "en") setCatalog.englishNames() else emptyMap()
        return physical.map { it.toDomain(lang, setNames) }
    }

    /**
     * TCGdex lacks images for new ja sets (e.g. M6): fills blank ja images from the cached TCGCSV
     * Japan source. Applied to a search's final cards only, so the per-search budget is never spent
     * on cards the number/name filters later drop.
     */
    private suspend fun List<TcgCard>.withJapanImages(stats: CallStats): List<TcgCard> = map { card ->
        val ref = TcgdexIds.parse(card.id)
        if (ref == null || ref.lang != "ja" || card.imageUrl.isNotBlank()) return@map card
        japanImages.imageUrl(TcgdexIds.setId(ref.id), card.number, stats.japanBudget)
            ?.let { card.copy(imageUrl = it) } ?: card
    }

    /** Set total as TCGdex wants it (no leading zeros), or null when it isn't a plain number. */
    private fun sendableTotal(raw: String): String? =
        raw.trim().trimStart('0').takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }

    private fun pokemonNamesOf(name: String) = name.split(" & ").map { it.substringBefore(" ").trim() }

    /**
     * Maps a TCGdex brief card to [TcgCard].
     * id format: "<setCode>-<localId>" e.g. "swsh3-136", "sve-213"
     * en keeps "tcgdex_<id>" (stable for saved entries) and gets its English set name from
     * [englishSetNames] when known (else the bare set code); other languages are
     * "tcgdex_<lang>_<id>" with setName "<SET> (<LANG>)" e.g. "(JA)", "(ZH-TW)", the marker that
     * surfaces in every "setName · #number" UI label.
     * image: base URL without extension; [TcgdexIds.imageUrl] makes it a usable URL.
     */
    private fun TcgdexCardBriefDto.toDomain(lang: String, englishSetNames: Map<String, String>): TcgCard {
        val setId = TcgdexIds.setId(id)
        val setCode = setId.uppercase()
        val isEn = lang == "en"
        return TcgCard(
            id = TcgdexIds.cardId(lang, id),
            name = name,
            number = localId,
            setName = if (isEn) englishSetNames[setId.lowercase()] ?: setCode else "$setCode (${lang.uppercase()})",
            imageUrl = image?.let { TcgdexIds.imageUrl(it) } ?: "",
            pokemonNames = pokemonNamesOf(name)
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
        )
    }

    companion object {
        private val SET_PREFIXED_PROMO = Regex("^(SVP|MEP)(\\d+)$")
        private const val TCGCSV_GROUP_CONCURRENCY = 8
    }
}
