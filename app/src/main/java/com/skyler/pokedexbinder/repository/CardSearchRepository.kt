package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.data.remote.TcgCardDto
import com.skyler.pokedexbinder.domain.ParsedCardInfo
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardSearchRepository @Inject constructor(
    private val api: PokemonTcgApi
) {
    suspend fun searchByParsedInfo(info: ParsedCardInfo): List<TcgCard> {
        val name = info.cardName
        val number = info.cardNumber
        val total = info.setTotal
        val queries = buildList {
            if (name != null && number != null && total != null)
                add("${nameQuery(name)} number:\"$number\" set.total:$total")
            if (name != null && number != null)
                add("${nameQuery(name)} number:\"$number\"")
            if (number != null && total != null)
                add("number:\"$number\" set.total:$total")
            if (name != null)
                add(nameQuery(name))
            if (number != null)
                add("number:\"$number\"")
        }
        for (query in queries) {
            val results = safeSearch(query)
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    suspend fun searchByNameAndNumber(name: String, number: String): List<TcgCard> =
        safeSearch("${nameQuery(name)} number:\"$number\"")
            .ifEmpty { safeSearch(nameQuery(name)) }

    suspend fun searchByName(name: String): List<TcgCard> =
        safeSearch(nameQuery(name))

    suspend fun searchByNumber(number: String): List<TcgCard> =
        safeSearch("number:\"$number\"")

    suspend fun searchByNumberAndTotal(number: String, total: String): List<TcgCard> =
        safeSearch("number:\"$number\" set.total:$total")
            .ifEmpty { safeSearch("number:\"$number\"") }

    suspend fun searchByDexNumber(dexNumber: Int): List<TcgCard> =
        safeSearch("nationalPokedexNumbers:$dexNumber")

    private fun nameQuery(name: String): String {
        val sanitized = name.trim()
            .replace(" ", "*")
            .replace("-", "*")
            .replace(":", "*")
        return "name:*$sanitized*"
    }

    private suspend fun safeSearch(query: String): List<TcgCard> = try {
        api.searchCards(query = query).data.map { it.toDomain() }
    } catch (e: HttpException) {
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
        artist = artist
    )
}
