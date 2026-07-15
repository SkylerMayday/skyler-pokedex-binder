package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "main_binder")
data class MainBinderEntry(
    @PrimaryKey val pokemonId: String,
    val pokemonName: String,
    val dexNumber: Int = 0,
    val dexOrder: Int,
    val slotType: String = "BASE",
    val assignedCardId: String? = null,
    val assignedCardImageUrl: String? = null,
    val assignedCardName: String? = null,
    val assignedCardSetName: String? = null,
    val language: String = "EN",
    val remarks: String? = null,
    val isLocked: Boolean = false
)
