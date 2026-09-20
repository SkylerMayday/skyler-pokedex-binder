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
}
