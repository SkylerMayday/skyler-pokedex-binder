package com.skyler.pokedexbinder.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonSlotTest {

    @Test
    fun `isOccupied returns false when no card assigned`() {
        val slot = PokemonSlot(
            id = "bulbasaur", name = "Bulbasaur", dexNumber = 1,
            dexOrder = 1, slotType = SlotType.BASE,
            assignedCardId = null, assignedCardImageUrl = null
        )
        assertFalse(slot.isOccupied)
    }

    @Test
    fun `isOccupied returns true when card assigned`() {
        val slot = PokemonSlot(
            id = "bulbasaur", name = "Bulbasaur", dexNumber = 1,
            dexOrder = 1, slotType = SlotType.BASE,
            assignedCardId = "xy1-1", assignedCardImageUrl = "https://example.com/card.png"
        )
        assertTrue(slot.isOccupied)
    }

    @Test
    fun `displayName returns name for base pokemon`() {
        val slot = PokemonSlot(
            id = "charizard", name = "Charizard", dexNumber = 6,
            dexOrder = 6, slotType = SlotType.BASE,
            assignedCardId = null, assignedCardImageUrl = null
        )
        assertEquals("Charizard", slot.name)
    }
}
