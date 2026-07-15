package com.skyler.pokedexbinder.publish.model

import com.squareup.moshi.JsonClass

const val SNAPSHOT_SCHEMA_VERSION = 1

@JsonClass(generateAdapter = true)
data class BinderSnapshot(
    val schemaVersion: Int = SNAPSHOT_SCHEMA_VERSION,
    val publishedAt: String,           // ISO-8601 with offset, e.g. 2026-07-04T21:00:00+08:00
    val binders: List<SnapshotBinder>
)

@JsonClass(generateAdapter = true)
data class SnapshotBinder(
    val id: String,                    // "pokedex" | "cardHistory"
    val name: String,                  // "Pokédex" | "Card History"
    val sections: List<SnapshotSection>
)

@JsonClass(generateAdapter = true)
data class SnapshotSection(
    val name: String,                  // "Generation I", ..., "Regional Variants", "Alternate Forms", "Mega Evolutions", "VMax"
    val slots: List<SnapshotSlot>
)

@JsonClass(generateAdapter = true)
data class SnapshotSlot(
    val dexNumber: Int,
    val slotName: String,              // the slot's pokemonName (always present)
    val slotType: String,              // BASE/REGIONAL/ALTERNATE_FORM/MEGA/GMAX
    val slotId: String,                // pokemonId — stable key for diffing
    val cardId: String?,               // null = empty slot
    val cardName: String?,             // fallback handled by viewer/Discord, not here
    val cardSet: String?,
    val imageUrl: String?,
    val owned: Boolean = true          // additive, backward-compatible (old JSON with no field defaults to true)
)
