package com.skyler.pokedexbinder.data.remote

/** Brief card object returned by TCGdex list endpoints. */
data class TcgdexCardBriefDto(
    val id: String,          // e.g. "swsh3-136"
    val localId: String,     // e.g. "136"
    val name: String,
    val image: String? = null // base URL without extension, e.g. "https://assets.tcgdex.net/en/swsh/swsh3/136"
)
