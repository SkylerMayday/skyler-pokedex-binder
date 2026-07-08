package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "connecting_art_slot",
    foreignKeys = [
        ForeignKey(
            entity = ConnectingArtGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId")]
)
data class ConnectingArtSlot(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val groupId: Int,
    val slotIndex: Int,          // row-major, 0-based
    val cardId: String? = null,
    val cardName: String? = null,
    val cardImageUrl: String? = null,
    val owned: Boolean = false
)
