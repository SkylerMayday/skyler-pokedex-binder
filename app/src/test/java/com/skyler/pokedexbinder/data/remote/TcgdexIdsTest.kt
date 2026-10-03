package com.skyler.pokedexbinder.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcgdexIdsTest {

    @Test
    fun `parse reads an English tcgdex id`() {
        assertEquals(TcgdexRef("en", "sv08-048"), TcgdexIds.parse("tcgdex_sv08-048"))
    }

    @Test
    fun `parse reads every Asian language prefix`() {
        assertEquals(TcgdexRef("ja", "M6-048"), TcgdexIds.parse("tcgdex_ja_M6-048"))
        assertEquals(TcgdexRef("ko", "SV5K-004"), TcgdexIds.parse("tcgdex_ko_SV5K-004"))
        assertEquals(TcgdexRef("zh-tw", "SV1-1"), TcgdexIds.parse("tcgdex_zh-tw_SV1-1"))
        assertEquals(TcgdexRef("zh-cn", "CSV1C-036"), TcgdexIds.parse("tcgdex_zh-cn_CSV1C-036"))
        assertEquals(TcgdexRef("th", "SV6-036"), TcgdexIds.parse("tcgdex_th_SV6-036"))
        assertEquals(TcgdexRef("id", "SV6-036"), TcgdexIds.parse("tcgdex_id_SV6-036"))
    }

    @Test
    fun `parse is null for tcgcsv, legacy and empty ids`() {
        assertNull(TcgdexIds.parse("tcgcsv_527885"))
        assertNull(TcgdexIds.parse("xy1-1"))
        assertNull(TcgdexIds.parse("tcgdex_"))
        assertNull(TcgdexIds.parse("tcgdex_ja_"))
    }

    @Test
    fun `cardId round-trips through parse for every language`() {
        assertEquals("tcgdex_sv08-048", TcgdexIds.cardId("en", "sv08-048"))
        assertEquals("tcgdex_ja_M6-048", TcgdexIds.cardId("ja", "M6-048"))
        (TCGDEX_ASIAN_LANGS + "en").forEach { lang ->
            assertEquals(TcgdexRef(lang, "S1-001"), TcgdexIds.parse(TcgdexIds.cardId(lang, "S1-001")))
        }
    }

    @Test
    fun `isNonEnglish is true only for Asian-language tcgdex ids`() {
        assertTrue(TcgdexIds.isNonEnglish("tcgdex_ja_M6-048"))
        assertTrue(TcgdexIds.isNonEnglish("tcgdex_zh-cn_CSV1C-036"))
        assertFalse(TcgdexIds.isNonEnglish("tcgdex_sv08-048"))
        assertFalse(TcgdexIds.isNonEnglish("tcgcsv_1"))
    }

    @Test
    fun `setId strips the local number, localId keeps it, imageUrl appends high webp`() {
        assertEquals("048", TcgdexIds.localId("sv08-048"))
        assertEquals("GG01", TcgdexIds.localId("swsh12pt5gg-GG01"))
        assertEquals("sv08", TcgdexIds.setId("sv08-048"))
        assertEquals("swsh12pt5gg", TcgdexIds.setId("swsh12pt5gg-GG01"))
        assertEquals("https://a/b/48/high.webp", TcgdexIds.imageUrl("https://a/b/48"))
    }
}
