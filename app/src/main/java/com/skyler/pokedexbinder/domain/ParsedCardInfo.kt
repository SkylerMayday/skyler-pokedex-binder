package com.skyler.pokedexbinder.domain

data class ParsedCardInfo(
    val cardName: String?,
    val cardNumber: String?,
    val setTotal: String?,
    val hp: String?,
    val artist: String?,
    val dexNumber: Int? = null   // National Pokédex number — language-independent
)
