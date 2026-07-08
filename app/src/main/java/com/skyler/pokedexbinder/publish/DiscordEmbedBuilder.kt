package com.skyler.pokedexbinder.publish

import com.skyler.pokedexbinder.data.remote.DiscordEmbed
import com.skyler.pokedexbinder.publish.model.ChangeType
import com.skyler.pokedexbinder.publish.model.PublishDiff

const val MAX_EMBED_LINES = 15

/** Minus sign (U+2212), matching the spec's REMOVED prefix — not an ASCII hyphen. */
private const val MINUS_SIGN = '−'

fun buildEmbed(diff: PublishDiff, pageUrl: String): DiscordEmbed {
    val url = "$pageUrl#latest"

    val description = if (diff.isFirstPublish) {
        "Initial publish — ${diff.added} cards\n\nPokédex: ${diff.pokedexComplete}/${diff.pokedexTotal}"
    } else {
        val ordered = diff.deltas.filter { it.type == ChangeType.ADDED } +
            diff.deltas.filter { it.type == ChangeType.REPLACED } +
            diff.deltas.filter { it.type == ChangeType.REMOVED }

        val lines = ordered.take(MAX_EMBED_LINES).map { delta ->
            when (delta.type) {
                ChangeType.ADDED -> {
                    val suffix = delta.cardSet?.let { " ($it)" } ?: ""
                    "+ ${delta.displayName}$suffix"
                }
                ChangeType.REPLACED -> "↻ ${delta.displayName} (card swapped)"
                ChangeType.REMOVED -> "$MINUS_SIGN ${delta.displayName}"
            }
        }.toMutableList()

        val remaining = ordered.size - MAX_EMBED_LINES
        if (remaining > 0) {
            lines += "…and $remaining more"
        }

        lines.joinToString("\n") + "\n\nPokédex: ${diff.pokedexComplete}/${diff.pokedexTotal}"
    }

    return DiscordEmbed(
        title = "Pokédex Binder updated",
        description = description,
        url = url
    )
}
