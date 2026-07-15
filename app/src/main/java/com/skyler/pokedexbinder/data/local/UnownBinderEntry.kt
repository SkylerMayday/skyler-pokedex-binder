package com.skyler.pokedexbinder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "unown_binder")
data class UnownBinderEntry(
    @PrimaryKey val letterId: String,       // "A".."Z", "!", "?" — 28 fixed IDs; doubles as display label
    val position: Int,                       // 0..27 stable grid ordering (A=0..Z=25, !=26, ?=27)
    val assignedCardId: String? = null,
    val assignedCardImageUrl: String? = null,
    val assignedCardName: String? = null,
    val assignedCardSetName: String? = null,
    val language: String = "EN",
    val remarks: String? = null,
    val isLocked: Boolean = false
)
