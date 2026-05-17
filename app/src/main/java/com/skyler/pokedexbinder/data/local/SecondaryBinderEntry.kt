package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "secondary_binder")
data class SecondaryBinderEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pokemonId: String,
    val pokemonName: String,
    val cardId: String,
    val cardImageUrl: String
)
