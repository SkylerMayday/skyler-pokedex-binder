package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.data.remote.TcgCardDto
import com.skyler.pokedexbinder.data.remote.TcgcsvApi
import com.skyler.pokedexbinder.data.remote.TcgcsvProductDto
import com.skyler.pokedexbinder.data.remote.TcgdexApi
import com.skyler.pokedexbinder.data.remote.TcgdexCardBriefDto
import com.skyler.pokedexbinder.domain.ParsedCardInfo
import kotlinx.coroutines.CancellationException
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

/** TCGdex non-English languages; also the manual-search append order. */
internal val TCGDEX_ASIAN_LANGS = listOf("ja", "ko", "zh-tw", "zh-cn", "th", "id")

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

private fun isNonEnglishTcgdexId(id: String) = TCGDEX_ASIAN_LANGS.any { id.startsWith("tcgdex_${it}_") }

private val CROSS_LANGUAGE_BASES = setOf("ja", "ko", "zh", "th", "id")

/**
 * Asian-language scan whose cards are all English printings. European languages are excluded:
 * their printings share English set numbering, so an English number+total match is the real card.
 * Null/blank/unknown language = false.
 */
internal fun isCrossLanguageFallback(language: String?, cards: List<TcgCard>): Boolean {
    val base = language?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-').orEmpty()
    return base in CROSS_LANGUAGE_BASES && cards.isNotEmpty() && cards.none { isNonEnglishTcgdexId(it.id) }
}

@Singleton
class CardSearchRepository @Inject constructor(
    private val api: PokemonTcgApi, // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
    private val tcgdexApi: TcgdexApi,
    private val tcgcsvApi: TcgcsvApi
) {
    suspend fun searchByParsedInfo(info: ParsedCardInfo): ParsedSearchResult {
        val result = resolveParsed(info).let { it.copy(crossLanguage = isCrossLanguageFallback(info.language, it.cards)) }
        // Scanner ja results: confirmCard's global-scan slot match compares English slot names, so
        // seed pokemonNames from Gemini's English name instead of the Japanese card name.
        val names = info.cardName?.let { pokemonNamesOf(it) } ?: return result
        return result.copy(cards = result.cards.map {
            if (isNonEnglishTcgdexId(it.id)) it.copy(pokemonNames = names) else it
        })
    }

    private suspend fun resolveParsed(info: ParsedCardInfo): ParsedSearchResult = coroutineScope {
        val name   = info.cardName
        val number = info.cardNumber
        val total  = info.setTotal?.let { sendableTotal(it) }
        val dex    = info.dexNumber
        val langs  = tcgdexLangsFor(info.language)

        // TCGdex first: species-pinned (dex, then name) before the species-blind number+total steps.
        // Every TCGdex step is exact-number filtered (the API's localId filter is a substring match).
        if (dex != null && number != null && total != null) {
            for (lang in langs) {
                val results = tcgdexFilter(lang, number, total, dex, null)
                if (results.isNotEmpty()) {
                    return@coroutineScope ParsedSearchResult(
                        results, "tcgdex-$lang+dex+number+total", identityPinned = results.size == 1
                    )
                }
            }
        }
        if (name != null && number != null && total != null) {
            val results = tcgdexFilter("en", number, total, null, name)
            if (results.isNotEmpty()) {
                return@coroutineScope ParsedSearchResult(results, "tcgdex-en+name+number+total")
            }
        }
        // Non-English names are only comparable to the printed name, never Gemini's English
        // name. No printedName = nothing to verify the species-blind hit against, so skip.
        val printedName = info.printedName
        if (printedName != null && number != null && total != null) {
            for (lang in langs.filter { it != "en" }) {
                val results = tcgdexFilter(lang, number, total, null, null).matchingParsedName(printedName)
                if (results.isNotEmpty()) {
                    return@coroutineScope ParsedSearchResult(
                        results, "tcgdex-$lang+number+total", nameVerified = results.size == 1
                    )
                }
            }
        }
        if (number != null && total != null) {
            val results = tcgdexFilter("en", number, total, null, null).matchingParsedName(name)
            if (results.isNotEmpty()) return@coroutineScope ParsedSearchResult(results, "tcgdex-en+number+total")
        }

        ptcgSpecificCascade(name, number, info.setTotal, dex)?.let { return@coroutineScope it }

        // Broad name/dex queries: TCGdex first, pokemontcg.io merged in so promo variants aren't missed.
        if (name != null) {
            val tcgdex = async { tcgdexSearchByName(name) }
            val ptcg = async { safeSearch(nameQuery(name)) } // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
            val merged = mergeResults(tcgdex.await(), ptcg.await())
            if (merged.isNotEmpty()) return@coroutineScope ParsedSearchResult(merged, "broad-name")
        }
        if (dex != null) {
            // Not name-filtered: the dex already pins species. A dex hit proves the species, not the
            // card, so all same-species candidates go to the hasher and SmartThresholdUseCase decides.
            val results = firstNonEmptyByDex(langs, dex)
                .ifEmpty { safeSearch("nationalPokedexNumbers:$dex") } // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
            if (results.isNotEmpty()) return@coroutineScope ParsedSearchResult(results, "broad-dex")
        }
        ptcgBroadNumber(name, number)
    }

    /**
     * The original pokemontcg.io specific-query cascade, verbatim. Species-pinned (name, then dex)
     * before the species-blind number+total, which is name-verified against the parsed name;
     * a miss falls through instead of offering a same-numbered different card.
     */
    // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
    private suspend fun ptcgSpecificCascade(
        name: String?, number: String?, total: String?, dex: Int?
    ): ParsedSearchResult? {
        val specificQueries = buildList {
            if (name != null && number != null && total != null)
                add("name+number+total" to "${nameQuery(name)} number:\"$number\" set.total:$total")
            if (name != null && number != null)
                add("name+number" to "${nameQuery(name)} number:\"$number\"")
            if (dex != null && number != null && total != null)
                add("dex+number+total" to "nationalPokedexNumbers:$dex number:\"$number\" set.total:$total")
            if (dex != null && number != null)
                add("dex+number" to "nationalPokedexNumbers:$dex number:\"$number\"")
            if (number != null && total != null)
                add("number+total" to "number:\"$number\" set.total:$total")
        }
        for ((label, query) in specificQueries) {
            val raw = safeSearch(query)
            val results = if (label == "number+total") raw.matchingParsedName(name) else raw
            if (results.isNotEmpty()) return ParsedSearchResult(results, label)
        }
        return null
    }

    /** Last-resort pokemontcg.io number-only lookup, name-verified. */
    // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
    private suspend fun ptcgBroadNumber(name: String?, number: String?): ParsedSearchResult {
        val byNumber = (if (number != null) safeSearch("number:\"$number\"") else emptyList())
            .matchingParsedName(name)
        return ParsedSearchResult(byNumber, if (byNumber.isNotEmpty()) "broad-number" else "none-matched")
    }

    // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01 (no callers)
    suspend fun searchByNameAndNumber(name: String, number: String): List<TcgCard> =
        safeSearch("${nameQuery(name)} number:\"$number\"")
            .ifEmpty { safeSearch(nameQuery(name)) }

    /** pokemontcg.io-first on purpose: PersonalCollectionRepository keys owned state by cardId. */
    suspend fun searchByName(name: String): List<TcgCard> = coroutineScope {
        val primary = async { safeSearch(nameQuery(name)) } // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
        val secondary = async { tcgdexSearchByName(name) }
        mergeResults(primary.await(), secondary.await())
    }

    /**
     * Manual search + Quick Scan: TCGdex en first, ptcg merged as fallback, then ja printings of
     * the species (ja, ko, zh-tw, zh-cn, th, id). [dexNumber] skips the detail-endpoint dex lookup when the caller already knows it.
     */
    suspend fun searchByNameWithJapanese(name: String, dexNumber: Int? = null): List<TcgCard> {
        val en = coroutineScope {
            val tcgdex = async { tcgdexSearchByName(name) }
            val ptcg = async { safeSearch(nameQuery(name)) } // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
            mergeResults(tcgdex.await(), ptcg.await())
        }
        val dex = dexNumber ?: resolveDex(en, name) ?: return en
        val names = en.bestNameMatch(name)?.pokemonNames ?: pokemonNamesOf(name)
        val nonEnglish = tcgdexAllLangsByDex(dex).map { it.copy(pokemonNames = names) }
        return (en + nonEnglish).distinctBy { it.id }
    }

    /** All [TCGDEX_ASIAN_LANGS] printings of a species, in list order (6 parallel, each exception-safe). */
    private suspend fun tcgdexAllLangsByDex(dex: Int): List<TcgCard> = coroutineScope {
        TCGDEX_ASIAN_LANGS.map { lang -> async { tcgdexFilter(lang, null, null, dex, null) } }
            .awaitAll().flatten()
    }

    /** First language in [langs] with any card of species [dex]; empty when none has one. */
    private suspend fun firstNonEmptyByDex(langs: List<String>, dex: Int): List<TcgCard> {
        for (lang in langs) {
            val results = tcgdexFilter(lang, null, null, dex, null)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    /** Species dex from the TCGdex detail endpoint, via the en TCGdex card best matching [name]. */
    private suspend fun resolveDex(en: List<TcgCard>, name: String): Int? {
        val card = en.filter {
            it.id.startsWith(TCGDEX_EN_ID_PREFIX) && !isNonEnglishTcgdexId(it.id)
        }.bestNameMatch(name) ?: return null
        return try {
            tcgdexApi.getCard("en", card.id.removePrefix(TCGDEX_EN_ID_PREFIX)).dexId?.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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

    // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
    suspend fun searchByNumber(number: String): List<TcgCard> =
        safeSearch("number:\"$number\"")

    // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01 (no callers)
    suspend fun searchByNumberAndTotal(number: String, total: String): List<TcgCard> =
        safeSearch("number:\"$number\" set.total:$total")
            .ifEmpty { safeSearch("number:\"$number\"") }

    suspend fun searchByDexNumber(dexNumber: Int): List<TcgCard> = coroutineScope {
        val en = async { tcgdexFilter("en", null, null, dexNumber, null) }
        val nonEnglish = async { tcgdexAllLangsByDex(dexNumber) }
        val ptcg = async { safeSearch("nationalPokedexNumbers:$dexNumber") } // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
        mergeResults(en.await() + nonEnglish.await(), ptcg.await())
    }

    // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
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

    // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
    private suspend fun safeSearch(query: String): List<TcgCard> = try {
        val filtered = if (query.isBlank()) POCKET_EXCLUSION.trim() else "$query $POCKET_EXCLUSION"
        api.searchCards(query = filtered).data.map { it.toDomain() }
    } catch (e: Exception) {
        emptyList()
    }

    // POKEMONTCG-IO-FALLBACK: remove before 2027-03-01
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
     * Combines primary and secondary results, deduplicating cross-source by physical-card identity
     * (name + number, setName-agnostic — see isSameCard) rather than exact string match, since the
     * two sources format setName differently for the same physical card. Primary results come first;
     * TCGdex fills in cards the primary API is missing.
     */
    private fun mergeResults(primary: List<TcgCard>, secondary: List<TcgCard>): List<TcgCard> {
        val newFromSecondary = secondary.filter { s -> primary.none { p -> isSameCard(p, s) } }
        return primary + newFromSecondary
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
        // TCG Pocket (mobile game) cards live under /tcgp/ and are not physical cards.
        tcgdexApi.searchCards(name).filterNot { it.image?.contains("/tcgp/") == true }.map { it.toDomain() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * TCGdex filtered list query. [total] must already be sendable (see [sendableTotal]). Drops
     * TCG Pocket cards and exact-filters [number], since the API's localId filter is a substring match.
     * Never throws: any failure is an empty list so the cascade falls through.
     */
    private suspend fun tcgdexFilter(
        lang: String, number: String?, total: String?, dex: Int?, name: String?
    ): List<TcgCard> = try {
        tcgdexApi.filterCards(
            lang,
            number?.trimStart('0')?.ifEmpty { "0" },
            total?.let { "eq:$it" },
            dex?.let { "eq:$it" },
            name
        )
            .filterNot { it.image?.contains("/tcgp/") == true }
            .map { it.toDomain(lang) }
            .filter { number == null || numbersMatch(it.number, number) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** Set total as TCGdex wants it (no leading zeros), or null when it isn't a plain number. */
    private fun sendableTotal(raw: String): String? =
        raw.trim().trimStart('0').takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }

    private fun pokemonNamesOf(name: String) = name.split(" & ").map { it.substringBefore(" ").trim() }

    /**
     * Maps a TCGdex brief card to [TcgCard].
     * id format: "<setCode>-<localId>" e.g. "swsh3-136", "sve-213"
     * en keeps "tcgdex_<id>" (stable for saved entries); other languages are "tcgdex_<lang>_<id>"
     * with setName "<SET> (<LANG>)" e.g. "(JA)", "(ZH-TW)", the marker that surfaces in every
     * "setName · #number" UI label.
     * image: base URL without extension; append "/high.webp" for a usable URL.
     */
    private fun TcgdexCardBriefDto.toDomain(lang: String = "en"): TcgCard {
        val setCode = id.substringBeforeLast("-").uppercase()
        val imageUrl = image?.let { "$it/high.webp" } ?: ""
        val isEn = lang == "en"
        return TcgCard(
            id = (if (isEn) TCGDEX_EN_ID_PREFIX else "${TCGDEX_EN_ID_PREFIX}${lang}_") + id,
            name = name,
            number = localId,
            setName = if (isEn) setCode else "$setCode (${lang.uppercase()})",
            imageUrl = imageUrl,
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
            // hp/artist/setReleaseDate left null — TCGCSV product listings carry no rarity/artist
        )
    }

    companion object {
        private const val TCGDEX_EN_ID_PREFIX = "tcgdex_"
        private const val POCKET_EXCLUSION = "-set.series:Pocket"
        private const val TCGCSV_GROUP_CONCURRENCY = 8
    }
}
