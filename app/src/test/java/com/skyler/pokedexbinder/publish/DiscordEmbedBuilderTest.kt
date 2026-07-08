package com.skyler.pokedexbinder.publish

import com.skyler.pokedexbinder.publish.model.ChangeType
import com.skyler.pokedexbinder.publish.model.PublishDiff
import com.skyler.pokedexbinder.publish.model.SlotDelta
import org.junit.Assert.*
import org.junit.Test

class DiscordEmbedBuilderTest {

    private val pageUrl = "https://skylermayday.github.io/pokedex-binder/"

    private fun delta(type: ChangeType, id: String, name: String, set: String? = "Base Set") = SlotDelta(
        type = type, slotId = id, displayName = name, cardSet = set
    )

    @Test
    fun `first publish shows single initial publish line`() {
        val diff = PublishDiff(
            deltas = List(10) { delta(ChangeType.ADDED, "s$it", "Poke$it") },
            isFirstPublish = true,
            pokedexComplete = 10,
            pokedexTotal = 1025
        )

        val embed = buildEmbed(diff, pageUrl)

        assertTrue(embed.description.startsWith("Initial publish — 10 cards"))
        assertFalse(embed.description.contains("Poke0"))
        assertTrue(embed.description.contains("Pokédex: 10/1025"))
    }

    @Test
    fun `more than 15 changes caps at 15 lines and appends remaining count`() {
        val deltas = (1..20).map { delta(ChangeType.ADDED, "s$it", "Poke$it") }
        val diff = PublishDiff(deltas = deltas, isFirstPublish = false, pokedexComplete = 20, pokedexTotal = 1025)

        val embed = buildEmbed(diff, pageUrl)
        val lines = embed.description.lines()

        // 15 change lines + "…and 5 more" + blank line + completion line
        val changeLines = lines.filter { it.startsWith("+") }
        assertEquals(MAX_EMBED_LINES, changeLines.size)
        assertTrue(lines.any { it == "…and 5 more" })
    }

    @Test
    fun `added line omits parens when cardSet is null`() {
        val diff = PublishDiff(
            deltas = listOf(delta(ChangeType.ADDED, "s1", "Bulbasaur", set = null)),
            isFirstPublish = false,
            pokedexComplete = 1,
            pokedexTotal = 1025
        )

        val embed = buildEmbed(diff, pageUrl)

        assertTrue(embed.description.contains("+ Bulbasaur"))
        assertFalse(embed.description.contains("+ Bulbasaur ("))
    }

    @Test
    fun `completion line is present`() {
        val diff = PublishDiff(
            deltas = listOf(delta(ChangeType.REPLACED, "s1", "Charizard")),
            isFirstPublish = false,
            pokedexComplete = 412,
            pokedexTotal = 1025
        )

        val embed = buildEmbed(diff, pageUrl)

        assertTrue(embed.description.contains("Pokédex: 412/1025"))
    }

    @Test
    fun `url ends with hash latest`() {
        val diff = PublishDiff(deltas = emptyList(), isFirstPublish = true, pokedexComplete = 0, pokedexTotal = 1025)

        val embed = buildEmbed(diff, pageUrl)

        assertTrue(embed.url.endsWith("#latest"))
        assertEquals("$pageUrl#latest", embed.url)
    }

    @Test
    fun `deltas ordered added then replaced then removed`() {
        val diff = PublishDiff(
            deltas = listOf(
                delta(ChangeType.REMOVED, "r1", "Removed One"),
                delta(ChangeType.REPLACED, "p1", "Replaced One"),
                delta(ChangeType.ADDED, "a1", "Added One")
            ),
            isFirstPublish = false,
            pokedexComplete = 5,
            pokedexTotal = 1025
        )

        val embed = buildEmbed(diff, pageUrl)
        val lines = embed.description.lines().filter { it.isNotBlank() && !it.startsWith("Pokédex") }

        assertTrue(lines[0].startsWith("+"))
        assertTrue(lines[1].startsWith("↻"))
        assertTrue(lines[2].startsWith("−"))
    }
}
