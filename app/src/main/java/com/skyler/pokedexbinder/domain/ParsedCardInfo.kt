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
) {
    /** True when the reader returned no field at all (a blank-string field still counts as set). */
    val isEmpty: Boolean
        get() = cardName == null && cardNumber == null && setTotal == null && hp == null &&
            artist == null && dexNumber == null && language == null && printedName == null
}
