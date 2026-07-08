package com.skyler.pokedexbinder.data.model

data class TcgCard(
    val id: String,
    val name: String,
    val number: String,
    val setName: String,
    val imageUrl: String,
    val pokemonNames: List<String>,
    val hp: String? = null,
    val artist: String? = null,
    val setReleaseDate: String? = null
) {
    val primaryPokemonName: String get() = pokemonNames.firstOrNull() ?: name
}
