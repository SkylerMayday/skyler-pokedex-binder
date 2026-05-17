package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.data.remote.TcgCardDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardSearchRepository @Inject constructor(
    private val api: PokemonTcgApi
) {
    suspend fun searchByNameAndNumber(name: String, number: String): List<TcgCard> =
        api.searchCards(query = "name:\"$name\" number:\"$number\"").data.map { it.toDomain() }

    suspend fun searchByName(name: String): List<TcgCard> =
        api.searchCards(query = "name:\"$name\"").data.map { it.toDomain() }

    private fun TcgCardDto.toDomain() = TcgCard(
        id = id,
        name = name,
        number = number,
        setName = set.name,
        imageUrl = images.large,
        // Tag Team cards (e.g. "Pikachu & Zekrom GX") split on " & " to get both names.
        // Single-Pokémon cards produce a one-element list.
        pokemonNames = name.split(" & ").map { it.substringBefore(" ").trim() }
    )
}
