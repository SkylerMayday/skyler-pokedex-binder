package com.skyler.pokedexbinder.publish

import com.skyler.pokedexbinder.data.local.MainBinderEntry

/**
 * Section names + generation dex ranges mirrored exactly from
 * [com.skyler.pokedexbinder.ui.mainbinder.MainBinderViewModel.buildGrouped] and its
 * companion `GENERATIONS` list, so the published snapshot and web viewer group slots
 * identically to the app. Keep these two lists in sync if the app's ranges/names change.
 */
val GENERATIONS = listOf(
    "Generation I" to 1..151,
    "Generation II" to 152..251,
    "Generation III" to 252..386,
    "Generation IV" to 387..493,
    "Generation V" to 494..649,
    "Generation VI" to 650..721,
    "Generation VII" to 722..809,
    "Generation VIII" to 810..905,
    "Generation IX" to 906..1025
)

const val SECTION_REGIONAL_VARIANTS = "Regional Variants"
const val SECTION_ALTERNATE_FORMS = "Alternate Forms"
const val SECTION_MEGA_EVOLUTIONS = "Mega Evolutions"
const val SECTION_VMAX = "VMax"

/** Ordered section names for the "pokedex" binder: 9 generations, then the 4 special sections. */
val SECTION_ORDER: List<String> =
    GENERATIONS.map { it.first } +
        listOf(SECTION_REGIONAL_VARIANTS, SECTION_ALTERNATE_FORMS, SECTION_MEGA_EVOLUTIONS, SECTION_VMAX)

/**
 * Pure function returning the section name a given [MainBinderEntry] belongs to, using the
 * same slotType/dexNumber rules as `MainBinderViewModel.buildGrouped`. Returns null only if
 * a BASE slot's dexNumber doesn't fall in any known generation range (shouldn't happen for
 * the seeded 1025 Pokédex slots).
 */
fun sectionNameFor(entry: MainBinderEntry): String? =
    when (entry.slotType.uppercase()) {
        "REGIONAL" -> SECTION_REGIONAL_VARIANTS
        "ALTERNATE_FORM" -> SECTION_ALTERNATE_FORMS
        "MEGA" -> SECTION_MEGA_EVOLUTIONS
        "GMAX" -> SECTION_VMAX
        else -> GENERATIONS.firstOrNull { (_, range) -> entry.dexNumber in range }?.first
    }

/**
 * Personal Collection fixed sections: pokemonKey -> display name, in app order.
 * Mirrors PERSONAL_COLLECTION_SECTIONS in ui/personalcollection/PersonalCollectionViewModel.kt —
 * keep the two lists in sync if the 4 Pokémon or their order change.
 */
val PERSONAL_COLLECTION_SECTION_ORDER: List<Pair<String, String>> = listOf(
    "charizard" to "Charizard",
    "celebi" to "Celebi",
    "tangela" to "Tangela",
    "minccino_cinccino" to "Minccino & Cinccino"
)
