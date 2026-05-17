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

    @Test
    fun `single result is high confidence`() {
        val result = useCase.evaluate(
            cards = listOf(card("xy1-1", "Charizard", "1")),
            parsedName = "Charizard",
            parsedNumber = "1"
        )
        assertTrue(result.isHighConfidence)
        assertEquals("xy1-1", result.topCard?.id)
    }

    @Test
    fun `empty results returns low confidence with null topCard`() {
        val result = useCase.evaluate(emptyList(), "Pikachu", null)
        assertFalse(result.isHighConfidence)
        assertNull(result.topCard)
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
    }

    @Test
    fun `multiple results with name match but no number is low confidence`() {
        val result = useCase.evaluate(
            cards = listOf(
                card("xy1-1", "Charizard", "1"),
                card("base1-4", "Charizard", "4")
            ),
            parsedName = "Charizard",
            parsedNumber = null
        )
        assertFalse(result.isHighConfidence)
    }
}
