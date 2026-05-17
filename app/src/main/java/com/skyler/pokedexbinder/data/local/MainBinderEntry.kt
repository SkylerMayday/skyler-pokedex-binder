package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "main_binder")
data class MainBinderEntry(
    @PrimaryKey val pokemonId: String,
    val pokemonName: String,
    val dexOrder: Int,
    val assignedCardId: String? = null,
    val assignedCardImageUrl: String? = null
)
