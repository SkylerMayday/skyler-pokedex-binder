package com.skyler.pokedexbinder.publish.model

enum class ChangeType { ADDED, REPLACED, REMOVED }

data class SlotDelta(
    val type: ChangeType,
    val slotId: String,
    val displayName: String,     // cardName ?: slotName
    val cardSet: String?
)

data class PublishDiff(
    val deltas: List<SlotDelta>,
    val isFirstPublish: Boolean,     // true when no baseline binder.json existed (404)
    val pokedexComplete: Int,
    val pokedexTotal: Int
) {
    val hasChanges: Boolean get() = deltas.isNotEmpty()
    val added get() = deltas.count { it.type == ChangeType.ADDED }
    val replaced get() = deltas.count { it.type == ChangeType.REPLACED }
    val removed get() = deltas.count { it.type == ChangeType.REMOVED }
}
