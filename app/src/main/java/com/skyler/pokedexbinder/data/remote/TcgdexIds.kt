package com.skyler.pokedexbinder.data.remote

/** TCGdex non-English languages; also the manual-search append order. */
internal val TCGDEX_ASIAN_LANGS = listOf("ja", "ko", "zh-tw", "zh-cn", "th", "id")

/** A TCGdex card addressed by language and TCGdex id, e.g. ("ja", "M6-048"). */
data class TcgdexRef(val lang: String, val id: String)

/** Pure helpers for the `tcgdex_[<lang>_]<id>` card-id scheme used by CardSearchRepository. */
object TcgdexIds {
    private const val PREFIX = "tcgdex_"

    /** Null for `tcgcsv_` and legacy (ptcg) ids; `tcgdex_<id>` is English. */
    fun parse(cardId: String): TcgdexRef? {
        if (!cardId.startsWith(PREFIX)) return null
        val rest = cardId.removePrefix(PREFIX)
        val lang = TCGDEX_ASIAN_LANGS.firstOrNull { rest.startsWith("${it}_") }
        val id = if (lang != null) rest.removePrefix("${lang}_") else rest
        return if (id.isEmpty()) null else TcgdexRef(lang ?: "en", id)
    }

    /** App card id for TCGdex card [id] in [lang]: `tcgdex_<id>` for en, else `tcgdex_<lang>_<id>`. */
    fun cardId(lang: String, id: String): String = if (lang == "en") "$PREFIX$id" else "$PREFIX${lang}_$id"

    /** True for a TCGdex card id in one of [TCGDEX_ASIAN_LANGS]. */
    fun isNonEnglish(cardId: String): Boolean = parse(cardId)?.lang.let { it != null && it != "en" }

    /** Set id of a TCGdex card id: "sv08-048" -> "sv08". */
    fun setId(localCardId: String): String = localCardId.substringBeforeLast("-")

    /** Local number of a TCGdex card id: "sv08-048" -> "048". */
    fun localId(localCardId: String): String = localCardId.substringAfterLast("-")

    fun imageUrl(base: String): String = "$base/high.webp"
}
