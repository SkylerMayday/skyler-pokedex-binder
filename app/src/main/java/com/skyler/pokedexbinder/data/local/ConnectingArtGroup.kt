package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connecting_art_group")
data class ConnectingArtGroup(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val rows: Int,
    val cols: Int,
    val position: Int = 0
)
