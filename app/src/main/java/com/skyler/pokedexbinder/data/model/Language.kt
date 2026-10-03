package com.skyler.pokedexbinder.data.model

import com.skyler.pokedexbinder.data.remote.TcgdexIds

enum class Language(val displayName: String) {
    EN("English"), JA("Japanese"), KO("Korean"), ZH("Chinese"),
    FR("French"), DE("German"), IT("Italian"), ES("Spanish"),
    TH("Thai"), ID("Indonesian");

    companion object {
        /** Defensive parse — legacy/unrecognized/null values fall back to EN, never throw. */
        fun fromRaw(raw: String?): Language = raw?.let {
            runCatching { valueOf(it) }.getOrNull()
        } ?: EN

        /**
         * Language implied by a card id ([TcgdexIds] scheme): the Asian TCGdex languages, else EN.
         * LanguageTest checks every TCGDEX_ASIAN_LANGS code maps to a non-EN value here.
         */
        fun fromCardId(id: String): Language = when (TcgdexIds.parse(id)?.lang) {
            "ja" -> JA
            "ko" -> KO
            "zh-tw", "zh-cn" -> ZH
            "th" -> TH
            "id" -> ID
            else -> EN
        }
    }
}
