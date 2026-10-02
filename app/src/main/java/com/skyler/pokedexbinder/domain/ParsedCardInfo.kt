package com.skyler.pokedexbinder.domain

data class ParsedCardInfo(
    val cardName: String?,
    val cardNumber: String?,
    val setTotal: String?,
    val hp: String?,
    val artist: String?,
    val dexNumber: Int? = null,   // National Pokédex number — language-independent
    val language: String? = null, // ISO 639-1 printed language, lowercase ("en", "ja")
    val printedName: String? = null // name as printed, in the card's own script
)
