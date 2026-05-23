package com.skyler.pokedexbinder.data.model

enum class SlotType { BASE, REGIONAL, ALTERNATE_FORM, MEGA, GMAX }

data class PokemonSlot(
    val id: String,
    val name: String,
    val dexNumber: Int,
    val dexOrder: Int,
    val slotType: SlotType,
    val assignedCardId: String? = null,
    val assignedCardImageUrl: String? = null,
    /** Override for TCG API search. If null, `name` is used directly. */
    val searchName: String? = null
) {
    val isOccupied: Boolean get() = assignedCardId != null

    /**
     * The name sent to the TCG search API — always the base Pokémon name, no qualifiers.
     * - Explicit override wins (used for alternate forms with non-obvious base names).
     * - REGIONAL: strips region prefix ("Alolan Vulpix" → "Vulpix").
     * - MEGA: strips "Mega " prefix (new format) or " Mega…" suffix (old format), plus X/Y/Z.
     *         "Mega Charizard X" → "Charizard" | "Charizard Mega X" → "Charizard"
     * - GMAX: strips " VMax" (new), " V-Max" (legacy), or " (Gigantamax)" (old) suffix.
     *         "Blastoise VMax" → "Blastoise" | "Blastoise V-Max" → "Blastoise" | "Blastoise (Gigantamax)" → "Blastoise"
     * - BASE/ALTERNATE_FORM with no override: use name as-is.
     */
    val effectiveSearchName: String get() = when {
        searchName != null -> searchName
        slotType == SlotType.REGIONAL -> name.substringAfter(" ")
        slotType == SlotType.MEGA -> {
            val noPrefix = name.removePrefix("Mega ")
            if (noPrefix != name) {
                // New format: "Mega Charizard X" → strip suffix X/Y/Z
                noPrefix.trimEnd { it == 'X' || it == 'Y' || it == 'Z' }.trim()
            } else {
                // Old format: "Charizard Mega X" → take everything before " Mega"
                name.substringBefore(" Mega")
            }
        }
        slotType == SlotType.GMAX -> name
            .removeSuffix(" VMax")
            .removeSuffix(" V-Max")
            .removeSuffix(" (Gigantamax)")
            .trim()
        else -> name
    }
}
