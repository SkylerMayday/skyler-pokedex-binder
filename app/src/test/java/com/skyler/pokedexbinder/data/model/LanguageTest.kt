package com.skyler.pokedexbinder.data.model

import com.skyler.pokedexbinder.data.remote.TCGDEX_ASIAN_LANGS
import com.skyler.pokedexbinder.data.remote.TcgdexIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LanguageTest {

    @Test
    fun `fromCardId maps Asian TCGdex prefixes and defaults to EN`() {
        assertEquals(Language.JA, Language.fromCardId("tcgdex_ja_M6-048"))
        assertEquals(Language.KO, Language.fromCardId("tcgdex_ko_SV5K-004"))
        assertEquals(Language.ZH, Language.fromCardId("tcgdex_zh-tw_SV1-1"))
        assertEquals(Language.ZH, Language.fromCardId("tcgdex_zh-cn_CSV1C-036"))
        assertEquals(Language.TH, Language.fromCardId("tcgdex_th_SV6-036"))
        assertEquals(Language.ID, Language.fromCardId("tcgdex_id_SV6-036"))
        assertEquals(Language.EN, Language.fromCardId("tcgdex_sv08-048"))
        assertEquals(Language.EN, Language.fromCardId("tcgcsv_1"))
        assertEquals(Language.EN, Language.fromCardId("xy1-1"))
    }

    @Test
    fun `every TCGdex Asian language maps to a non-English Language, so a new one cannot silently become EN`() {
        TCGDEX_ASIAN_LANGS.forEach { lang ->
            assertNotEquals(lang, Language.EN, Language.fromCardId(TcgdexIds.cardId(lang, "s1-001")))
        }
        assertEquals(Language.EN, Language.fromCardId(TcgdexIds.cardId("en", "s1-001")))
    }

    @Test
    fun `fromRaw parses the new Thai and Indonesian values`() {
        assertEquals(Language.TH, Language.fromRaw("TH"))
        assertEquals(Language.ID, Language.fromRaw("ID"))
        assertEquals(Language.EN, Language.fromRaw("bogus"))
    }
}
