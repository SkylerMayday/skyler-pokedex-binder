package com.skyler.pokedexbinder.publish.model

import com.squareup.moshi.JsonClass

const val CHANGELOG_MAX_ENTRIES = 50

@JsonClass(generateAdapter = true)
data class Changelog(
    val entries: List<ChangelogEntry>
)

@JsonClass(generateAdapter = true)
data class ChangelogEntry(
    val publishedAt: String,
    val summary: PublishSummaryCounts,      // added/replaced/removed counts + completion
    val changes: List<SlotChange>
)

@JsonClass(generateAdapter = true)
data class PublishSummaryCounts(
    val added: Int,
    val replaced: Int,
    val removed: Int,
    val pokedexComplete: Int,               // e.g. 412
    val pokedexTotal: Int                   // e.g. 1025
)

@JsonClass(generateAdapter = true)
data class SlotChange(
    val type: String,                        // "ADDED" | "REPLACED" | "REMOVED"
    val slotId: String,
    val slotName: String,                    // display name (card name if present else slotName)
    val cardSet: String?                     // for the "(Flashfire)" suffix; null ok
)
