package com.skyler.pokedexbinder.data.model

enum class Language(val displayName: String) {
    EN("English"), JA("Japanese"), KO("Korean"), ZH("Chinese"),
    FR("French"), DE("German"), IT("Italian"), ES("Spanish");

    companion object {
        /** Defensive parse — legacy/unrecognized/null values fall back to EN, never throw. */
        fun fromRaw(raw: String?): Language = raw?.let {
            runCatching { valueOf(it) }.getOrNull()
        } ?: EN
    }
}
