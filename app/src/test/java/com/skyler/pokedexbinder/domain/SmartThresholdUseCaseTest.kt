package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.model.TcgCard
import org.junit.Assert.*
import org.junit.Test

class SmartThresholdUseCaseTest {

    private val useCase = SmartThresholdUseCase()

    private fun card(id: String, name: String, number: String) = TcgCard(
        id = id, name = name, number = number,
        setName = "Test Set", imageUrl = "https://img.url",
        pokemonNames = listOf(name)
    )

    private fun hashMatch(card: TcgCard, distance: Int, margin: Int) =
        HashMatchResult(card, distance, margin)

    @Test
    fun `single result within hash distance ceiling is high confidence`() {
        val charizard = card("xy1-1", "Charizard", "1")
        val result = useCase.evaluate(
            cards = listOf(charizard),
            parsedName = "Charizard",
            parsedNumber = "1",
            hashMatch = hashMatch(charizard, distance = SmartThresholdUseCase.HASH_SINGLE_CANDIDATE_MAX_DISTANCE, margin = Int.MAX_VALUE)
        )
        assertTrue(result.isHighConfidence)
        assertEquals("xy1-1", result.topCard?.id)
        assertEquals(ConfidencePath.SINGLE_CANDIDATE_HASH, result.matchPath)
    }

    @Test
    fun `single result beyond hash distance ceiling is rejected instead of auto-accepted`() {
        val charizard = card("xy1-1", "Charizard", "1")
        val result = useCase.evaluate(
            cards = listOf(charizard),
            parsedName = "Charizard",
            parsedNumber = "1",
            hashMatch = hashMatch(charizard, distance = SmartThresholdUseCase.HASH_SINGLE_CANDIDATE_MAX_DISTANCE + 1, margin = Int.MAX_VALUE)
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `single result with no hashMatch stays low confidence`() {
        val result = useCase.evaluate(
            cards = listOf(card("xy1-1", "Charizard", "1")),
            parsedName = "Charizard",
            parsedNumber = "1"
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `empty results returns low confidence with null topCard`() {
        val result = useCase.evaluate(emptyList(), "Pikachu", null)
        assertFalse(result.isHighConfidence)
        assertNull(result.topCard)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `multiple results with exact name and number match is high confidence`() {
        val result = useCase.evaluate(
            cards = listOf(
                card("xy1-1", "Charizard", "1"),
                card("base1-4", "Charizard", "4")
            ),
            parsedName = "Charizard",
            parsedNumber = "1"
        )
        assertTrue(result.isHighConfidence)
        assertEquals("xy1-1", result.topCard?.id)
        assertEquals(ConfidencePath.NUMBER_MATCH, result.matchPath)
    }

    @Test
    fun `number match wins even when a wide hash margin is also present`() {
        val xy1 = card("xy1-1", "Charizard", "1")
        val base1 = card("base1-4", "Charizard", "4")
        val result = useCase.evaluate(
            cards = listOf(xy1, base1),
            parsedName = "Charizard",
            parsedNumber = "1",
            hashMatch = hashMatch(base1, distance = 2, margin = 40)
        )
        assertTrue(result.isHighConfidence)
        assertEquals("xy1-1", result.topCard?.id)
        assertEquals(ConfidencePath.NUMBER_MATCH, result.matchPath)
    }

    @Test
    fun `multiple results with name match but no number is low confidence when hashMatch is null`() {
        val result = useCase.evaluate(
            cards = listOf(
                card("xy1-1", "Charizard", "1"),
                card("base1-4", "Charizard", "4")
            ),
            parsedName = "Charizard",
            parsedNumber = null
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `hash margin wins when number is null and margin clears the threshold`() {
        val xy1 = card("xy1-1", "Charizard", "1")
        val base1 = card("base1-4", "Charizard", "4")
        val result = useCase.evaluate(
            cards = listOf(xy1, base1),
            parsedName = "Charizard",
            parsedNumber = null,
            hashMatch = hashMatch(xy1, distance = 2, margin = SmartThresholdUseCase.HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD)
        )
        assertTrue(result.isHighConfidence)
        assertEquals("xy1-1", result.topCard?.id)
        assertEquals(ConfidencePath.HASH_MARGIN, result.matchPath)
    }

    @Test
    fun `hash margin does not grant high confidence when winning distance is the download-failure sentinel`() {
        val xy1 = card("xy1-1", "Charizard", "1")
        val base1 = card("base1-4", "Charizard", "4")
        val result = useCase.evaluate(
            cards = listOf(xy1, base1),
            parsedName = "Charizard",
            parsedNumber = null,
            hashMatch = hashMatch(xy1, distance = Int.MAX_VALUE, margin = 999)
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `falls through when margin is narrower than the threshold`() {
        val xy1 = card("xy1-1", "Charizard", "1")
        val base1 = card("base1-4", "Charizard", "4")
        val result = useCase.evaluate(
            cards = listOf(xy1, base1),
            parsedName = "Charizard",
            parsedNumber = null,
            hashMatch = hashMatch(xy1, distance = 2, margin = SmartThresholdUseCase.HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD - 1)
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `a number that was read but matched nothing does not fall through to hash margin`() {
        val xy1 = card("xy1-1", "Charizard", "1")
        val base1 = card("base1-4", "Charizard", "4")
        val result = useCase.evaluate(
            cards = listOf(xy1, base1),
            parsedName = "Charizard",
            parsedNumber = "99",
            hashMatch = hashMatch(xy1, distance = 2, margin = 50)
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `pinned single card with no image is high confidence via IDENTITY_PINNED`() {
        val c = card("tcgdex_ja_M6-048", "Mega Malamar ex", "48").copy(imageUrl = "")
        val result = useCase.evaluate(
            listOf(c), "Malamar", "48", hashMatch(c, distance = Int.MAX_VALUE, margin = Int.MAX_VALUE), identityPinned = true
        )
        assertTrue(result.isHighConfidence)
        assertEquals(ConfidencePath.IDENTITY_PINNED, result.matchPath)
        assertEquals(c.id, result.topCard?.id)
    }

    @Test
    fun `pinned single card whose real image failed to download is rejected`() {
        // Int.MAX_VALUE from a 404/decode failure on a card that HAS an image is not "no image".
        val c = card("tcgdex_ja_M6-048", "Mega Malamar ex", "48")
        val result = useCase.evaluate(
            listOf(c), "Malamar", "48", hashMatch(c, distance = Int.MAX_VALUE, margin = Int.MAX_VALUE), identityPinned = true
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `pinned single card with a noisy real hash is accepted (Castform at 34)`() {
        val c = card("tcgdex_swsh9-116", "Castform", "116")
        val result = useCase.evaluate(
            listOf(c), "Castform", "116", hashMatch(c, distance = 34, margin = Int.MAX_VALUE), identityPinned = true
        )
        assertTrue(result.isHighConfidence)
        assertEquals(ConfidencePath.IDENTITY_PINNED, result.matchPath)
    }

    @Test
    fun `pinned single card is accepted at the contradiction ceiling and rejected one past it`() {
        val c = card("tcgdex_swsh9-116", "Castform", "116")
        val ceiling = SmartThresholdUseCase.PINNED_HASH_CONTRADICTION_DISTANCE
        val atCeiling = useCase.evaluate(
            listOf(c), "Castform", "116", hashMatch(c, distance = ceiling, margin = Int.MAX_VALUE), identityPinned = true
        )
        val pastCeiling = useCase.evaluate(
            listOf(c), "Castform", "116", hashMatch(c, distance = ceiling + 1, margin = Int.MAX_VALUE), identityPinned = true
        )
        assertEquals(45, ceiling)
        assertTrue(atCeiling.isHighConfidence)
        assertFalse(pastCeiling.isHighConfidence)
        assertEquals(ConfidencePath.NONE, pastCeiling.matchPath)
        assertEquals(c.id, pastCeiling.topCard?.id)
    }

    @Test
    fun `hash distance 20 keeps SINGLE_CANDIDATE_HASH and 21 falls to IDENTITY_PINNED when pinned`() {
        val c = card("tcgdex_swsh9-116", "Castform", "116")
        val ceiling = SmartThresholdUseCase.HASH_SINGLE_CANDIDATE_MAX_DISTANCE
        val at = useCase.evaluate(
            listOf(c), "Castform", "116", hashMatch(c, distance = ceiling, margin = Int.MAX_VALUE), identityPinned = true
        )
        val past = useCase.evaluate(
            listOf(c), "Castform", "116", hashMatch(c, distance = ceiling + 1, margin = Int.MAX_VALUE), identityPinned = true
        )
        assertEquals(ConfidencePath.SINGLE_CANDIDATE_HASH, at.matchPath)
        assertEquals(ConfidencePath.IDENTITY_PINNED, past.matchPath)
    }

    @Test
    fun `nameVerified single card at hash 28 is high confidence via IDENTITY_PINNED_NAME`() {
        val c = card("tcgdex_ja_SV7a-011", "ブラックキュレムex", "11")
        val result = useCase.evaluate(
            listOf(c), "Black Kyurem", "11", hashMatch(c, distance = 28, margin = Int.MAX_VALUE), nameVerified = true
        )
        assertTrue(result.isHighConfidence)
        assertEquals(ConfidencePath.IDENTITY_PINNED_NAME, result.matchPath)
    }

    @Test
    fun `nameVerified single card past the ceiling is rejected`() {
        val c = card("tcgdex_ja_SV7a-011", "ブラックキュレムex", "11")
        val result = useCase.evaluate(
            listOf(c), "Black Kyurem", "11", hashMatch(c, distance = 46, margin = Int.MAX_VALUE), nameVerified = true
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `nameVerified with a failed real-image download is rejected, with a blank image accepted`() {
        val withImage = card("tcgdex_ja_SV7a-011", "ブラックキュレムex", "11")
        val blank = withImage.copy(imageUrl = "")
        val rejected = useCase.evaluate(
            listOf(withImage), "Black Kyurem", "11",
            hashMatch(withImage, distance = Int.MAX_VALUE, margin = Int.MAX_VALUE), nameVerified = true
        )
        val accepted = useCase.evaluate(
            listOf(blank), "Black Kyurem", "11",
            hashMatch(blank, distance = Int.MAX_VALUE, margin = Int.MAX_VALUE), nameVerified = true
        )
        assertFalse(rejected.isHighConfidence)
        assertTrue(accepted.isHighConfidence)
        assertEquals(ConfidencePath.IDENTITY_PINNED_NAME, accepted.matchPath)
    }

    @Test
    fun `both pin flags label the path IDENTITY_PINNED`() {
        val c = card("tcgdex_swsh9-116", "Castform", "116")
        val result = useCase.evaluate(
            listOf(c), "Castform", "116", hashMatch(c, distance = 34, margin = Int.MAX_VALUE),
            identityPinned = true, nameVerified = true
        )
        assertEquals(ConfidencePath.IDENTITY_PINNED, result.matchPath)
    }

    @Test
    fun `unpinned single card at hash 34 is rejected`() {
        val c = card("tcgdex_swsh9-116", "Castform", "116")
        val result = useCase.evaluate(
            listOf(c), "Castform", "116", hashMatch(c, distance = 34, margin = Int.MAX_VALUE)
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `pinned single card without a hashMatch is rejected`() {
        val c = card("tcgdex_swsh9-116", "Castform", "116")
        val result = useCase.evaluate(listOf(c), "Castform", "116", null, identityPinned = true)
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `nameVerified has no effect with multiple candidates`() {
        val a = card("a", "Malamar", "48")
        val b = card("b", "Malamar", "52")
        val result = useCase.evaluate(
            listOf(a, b), "Malamar", "48", hashMatch(a, distance = 30, margin = 0), nameVerified = true
        )
        assertEquals(ConfidencePath.NUMBER_MATCH, result.matchPath)
    }

    @Test
    fun `unpinned single card with no image stays low confidence`() {
        val c = card("tcgdex_ja_M6-048", "Mega Malamar ex", "48")
        val result = useCase.evaluate(
            listOf(c), "Malamar", "48", hashMatch(c, distance = Int.MAX_VALUE, margin = Int.MAX_VALUE)
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.NONE, result.matchPath)
    }

    @Test
    fun `identityPinned has no effect with multiple candidates`() {
        val a = card("a", "Malamar", "48")
        val b = card("b", "Malamar", "52")
        val result = useCase.evaluate(
            listOf(a, b), "Malamar", "48", hashMatch(a, distance = Int.MAX_VALUE, margin = 0), identityPinned = true
        )
        assertEquals(ConfidencePath.NUMBER_MATCH, result.matchPath)
    }

    // --- crossLanguage guard ---

    @Test
    fun `crossLanguage rejects an identityPinned single card with a close hash`() {
        val c = card("tcgdex_sv1-1", "Palafin ex", "228")
        val result = useCase.evaluate(
            listOf(c), "Palafin ex", "228", hashMatch(c, distance = 5, margin = Int.MAX_VALUE),
            identityPinned = true, crossLanguage = true
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.CROSS_LANGUAGE, result.matchPath)
    }

    @Test
    fun `crossLanguage rejects a nameVerified single card with a close hash`() {
        val c = card("tcgdex_sv1-1", "Palafin ex", "228")
        val result = useCase.evaluate(
            listOf(c), "Palafin ex", "228", hashMatch(c, distance = 5, margin = Int.MAX_VALUE),
            nameVerified = true, crossLanguage = true
        )
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.CROSS_LANGUAGE, result.matchPath)
    }

    @Test
    fun `crossLanguage defeats the number-match auto-accept`() {
        val a = card("a", "Palafin ex", "228")
        val b = card("b", "Palafin ex", "52")
        val control = useCase.evaluate(listOf(a, b), "Palafin ex", "228", hashMatch(a, 5, 40))
        assertEquals(ConfidencePath.NUMBER_MATCH, control.matchPath)

        val result = useCase.evaluate(listOf(a, b), "Palafin ex", "228", hashMatch(a, 5, 40), crossLanguage = true)
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.CROSS_LANGUAGE, result.matchPath)
    }

    @Test
    fun `crossLanguage defeats the hash-margin auto-accept`() {
        val a = card("a", "Palafin ex", "10")
        val b = card("b", "Palafin ex", "52")
        val control = useCase.evaluate(listOf(a, b), "Palafin ex", null, hashMatch(a, 5, 40))
        assertEquals(ConfidencePath.HASH_MARGIN, control.matchPath)

        val result = useCase.evaluate(listOf(a, b), "Palafin ex", null, hashMatch(a, 5, 40), crossLanguage = true)
        assertFalse(result.isHighConfidence)
        assertEquals(ConfidencePath.CROSS_LANGUAGE, result.matchPath)
    }

    @Test
    fun `crossLanguage with no hash data is low confidence and tops with the first card`() {
        val a = card("a", "Palafin ex", "228")
        val b = card("b", "Palafin ex", "52")
        val result = useCase.evaluate(listOf(a, b), "Palafin ex", "228", null, crossLanguage = true)
        assertFalse(result.isHighConfidence)
        assertEquals(a, result.topCard)
    }
}
