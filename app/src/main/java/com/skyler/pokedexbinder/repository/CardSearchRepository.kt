package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.data.remote.TcgCardDto
import com.skyler.pokedexbinder.data.remote.TcgdexApi
import com.skyler.pokedexbinder.data.remote.TcgdexCardBriefDto
import com.skyler.pokedexbinder.domain.ParsedCardInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardSearchRepository @Inject constructor(
    private val api: PokemonTcgApi,
    private val tcgdexApi: TcgdexApi
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

    companion object {
        private const val POCKET_EXCLUSION = "-set.series:Pocket"
    }
}
