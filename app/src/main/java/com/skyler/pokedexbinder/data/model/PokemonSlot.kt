package com.skyler.pokedexbinder.data.model

enum class SlotType { BASE, REGIONAL, MEGA, GMAX }

data class PokemonSlot(
    val id: String,
    val name: String,
    val dexNumber: Int,
    val dexOrder: Int,
    val slotType: SlotType,
    val assignedCardId: String? = null,
    val assignedCardImageUrl: String? = null
) {
    val isOccupied: Boolean get() = assignedCardId != null
}
