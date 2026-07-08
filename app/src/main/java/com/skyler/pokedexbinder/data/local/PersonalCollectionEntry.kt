package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personal_collection_entry")
data class PersonalCollectionEntry(
    @PrimaryKey val cardId: String,
    val owned: Boolean = false
)
