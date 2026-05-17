package com.skyler.pokedexbinder.domain

import org.junit.Assert.*
import org.junit.Test

class OcrCardParserTest {

    private val parser = OcrCardParser()

    @Test
    fun `parses card name and set number from standard card text`() {
        val rawText = "Charizard\nHP 120\nFire\n4/102\nBase Set"
        val result = parser.parse(rawText)
        assertEquals("Charizard", result.cardName)
        assertEquals("4", result.cardNumber)
    }

    @Test
    fun `handles three digit set numbers`() {
        val rawText = "Pikachu V\nHP 90\n025/185\nVivid Voltage"
        val result = parser.parse(rawText)
        assertEquals("Pikachu V", result.cardName)
        assertEquals("025", result.cardNumber)
    }

    @Test
    fun `returns null number when no set number found`() {
        val rawText = "Bulbasaur\nHP 40\nSeed Pokémon"
        val result = parser.parse(rawText)
        assertEquals("Bulbasaur", result.cardName)
        assertNull(result.cardNumber)
    }

    @Test
    fun `handles tag team card names`() {
        val rawText = "Pikachu & Zekrom GX\nHP 240\nTag Team\n33/181"
        val result = parser.parse(rawText)
        assertEquals("Pikachu & Zekrom GX", result.cardName)
        assertEquals("33", result.cardNumber)
    }
}
