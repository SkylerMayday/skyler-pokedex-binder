package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personal_collection_cache")
data class PersonalCollectionCache(
    @PrimaryKey val cardId: String,
    val pokemonKey: String,   // e.g. "charizard", "minccino_cinccino"
    val name: String,
    val imageUrl: String,
    val setName: String,
    val releaseDate: String
)
