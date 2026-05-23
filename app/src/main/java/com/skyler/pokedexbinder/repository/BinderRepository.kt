package com.skyler.pokedexbinder.repository

import android.util.Log
import com.skyler.pokedexbinder.data.local.MainBinderDao
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BinderRepository @Inject constructor(
    private val mainBinderDao: MainBinderDao
) {
    fun observeSlots(): Flow<List<PokemonSlot>> =
        mainBinderDao.observeAll().map { entries -> entries.map { it.toDomain() } }

    suspend fun getSlotByPokemonId(pokemonId: String): PokemonSlot? =
        mainBinderDao.getByPokemonId(pokemonId)?.toDomain()

    suspend fun getAllSlots(): List<PokemonSlot> =
        mainBinderDao.getAll().map { it.toDomain() }

    fun observeSlot(pokemonId: String): Flow<PokemonSlot?> =
        mainBinderDao.observeByPokemonId(pokemonId).map { it?.toDomain() }

    suspend fun assignCard(pokemonId: String, cardId: String, cardImageUrl: String) {
        val existing = mainBinderDao.getByPokemonId(pokemonId) ?: return
        mainBinderDao.upsert(
            existing.copy(assignedCardId = cardId, assignedCardImageUrl = cardImageUrl)
        )
    }

    suspend fun clearCard(pokemonId: String) {
        val existing = mainBinderDao.getByPokemonId(pokemonId) ?: return
        mainBinderDao.upsert(existing.copy(assignedCardId = null, assignedCardImageUrl = null))
    }

    suspend fun seedFromJson(slots: List<MainBinderEntry>) {
        mainBinderDao.insertAll(slots)
    }

    suspend fun seedIfEmpty(context: android.content.Context) {
        if (mainBinderDao.count() > 0) return
        runCatching {
            val json = context.resources.openRawResource(com.skyler.pokedexbinder.R.raw.pokemon_slots)
                .bufferedReader().readText()
            val arr = JSONArray(json)
            val entries = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MainBinderEntry(
                    pokemonId = obj.getString("id"),
                    pokemonName = obj.getString("name"),
                    dexNumber = obj.getInt("dex_number"),
                    dexOrder = obj.getInt("dex_order"),
                    slotType = obj.getString("slot_type")
                )
            }
            mainBinderDao.insertAll(entries)
        }.onFailure { e ->
            Log.e("BinderRepository", "Failed to seed pokemon slots", e)
        }
    }

    suspend fun migrateSlotNamesIfNeeded() {
        mainBinderDao.migrateToxtricityGmaxDexNumber()
        mainBinderDao.migrateBaseSlotNames()
        mainBinderDao.migrateBaseSlotNamesWave2()
        mainBinderDao.migrateGiratinaName()
        mainBinderDao.migrateMegaNamesToPrefixFormat()
        mainBinderDao.migrateGmaxNamesToVMax()
    }

    suspend fun seedAlternateFormsIfMissing() {
        // Always insertIfAbsent so new slots added to ALTERNATE_FORM_SLOTS
        // are picked up on existing installs. IGNORE conflict strategy is safe.
        mainBinderDao.insertIfAbsent(ALTERNATE_FORM_SLOTS)
    }

    private fun MainBinderEntry.toDomain() = PokemonSlot(
        id = pokemonId,
        name = pokemonName,
        dexNumber = dexNumber,
        dexOrder = dexOrder,
        slotType = when (slotType.lowercase()) {
            "regional" -> SlotType.REGIONAL
            "alternate_form" -> SlotType.ALTERNATE_FORM
            "mega" -> SlotType.MEGA
            "gmax" -> SlotType.GMAX
            else -> SlotType.BASE
        },
        assignedCardId = assignedCardId,
        assignedCardImageUrl = assignedCardImageUrl,
        searchName = ALTERNATE_FORM_SEARCH_NAMES[pokemonId]
    )

    companion object {
        // Alternate forms with confirmed TCG card presence.
        // dex_order 10000+ keeps them after all standard slots.
        val ALTERNATE_FORM_SLOTS = listOf(
            // Gen III
            entry("af_castform_rainy",      "Castform Rainy Form",       351, 10001),
            entry("af_castform_snowy",      "Castform Snowy Form",       351, 10002),
            entry("af_castform_sunny",      "Castform Sunny Form",       351, 10003),
            entry("af_deoxys_attack",       "Deoxys Attack Forme",       386, 10004),
            entry("af_deoxys_defense",      "Deoxys Defense Forme",      386, 10005),
            entry("af_deoxys_speed",        "Deoxys Speed Forme",        386, 10006),
            // Gen IV
            entry("af_wormadam_sandy",      "Wormadam Sandy Cloak",      413, 10007),
            entry("af_wormadam_trash",      "Wormadam Trash Cloak",      413, 10008),
            entry("af_cherrim_sunshine",    "Cherrim Sunshine Form",     421, 10009),
            entry("af_rotom_heat",          "Heat Rotom",                479, 10010),
            entry("af_rotom_wash",          "Wash Rotom",                479, 10011),
            entry("af_rotom_frost",         "Frost Rotom",               479, 10012),
            entry("af_rotom_fan",           "Fan Rotom",                 479, 10013),
            entry("af_rotom_mow",           "Mow Rotom",                 479, 10014),
            entry("af_dialga_origin",       "Dialga Origin Forme",       483, 10015),
            entry("af_palkia_origin",       "Palkia Origin Forme",       484, 10016),
            entry("af_giratina_origin",     "Giratina Origin Forme",     487, 10017),
            entry("af_shaymin_sky",         "Shaymin Sky Forme",         492, 10018),
            // Gen V
            entry("af_tornadus_therian",    "Tornadus Therian",          641, 10019),
            entry("af_thundurus_therian",   "Thundurus Therian",         642, 10020),
            entry("af_landorus_therian",    "Landorus Therian",          645, 10021),
            entry("af_kyurem_black",        "Black Kyurem",              646, 10022),
            entry("af_kyurem_white",        "White Kyurem",              646, 10023),
            entry("af_keldeo_resolute",     "Keldeo Resolute Form",      647, 10024),
            entry("af_meloetta_pirouette",  "Meloetta Pirouette Forme",  648, 10025),
            // Gen VI
            entry("af_aegislash_blade",     "Aegislash Blade Forme",     681, 10026),
            entry("af_zygarde_10",          "Zygarde 10% Forme",         718, 10027),
            entry("af_zygarde_complete",    "Zygarde Complete Forme",    718, 10028),
            entry("af_hoopa_unbound",       "Hoopa Unbound",             720, 10029),
            // Gen VII
            entry("af_oricorio_pompom",     "Oricorio Pom-Pom Style",    741, 10030),
            entry("af_oricorio_pau",        "Oricorio Pa'u Style",       741, 10031),
            entry("af_oricorio_sensu",      "Oricorio Sensu Style",      741, 10032),
            entry("af_wishiwashi_school",   "Wishiwashi School Form",    746, 10033),
            entry("af_necrozma_dusk_mane",  "Necrozma Dusk Mane",        800, 10034),
            entry("af_necrozma_dawn_wings", "Necrozma Dawn Wings",       800, 10035),
            // Gen VIII
            entry("af_morpeko_hangry",      "Morpeko Hangry Mode",       877, 10036),
            entry("af_zacian_crowned",      "Zacian Crowned Sword",      888, 10037),
            entry("af_zamazenta_crowned",   "Zamazenta Crowned Shield",  889, 10038),
            entry("af_urshifu_rapid",       "Urshifu Rapid Strike",      892, 10039),
            entry("af_calyrex_ice",         "Calyrex Ice Rider",         898, 10040),
            entry("af_calyrex_shadow",      "Calyrex Shadow Rider",      898, 10041),
            // Gen IV — default forms previously missing
            entry("af_wormadam_plant",      "Wormadam Plant Cloak",      413, 10042),
            entry("af_cherrim_overcast",    "Cherrim Overcast Form",     421, 10043),
            // Gen VII — Oricorio default form
            entry("af_oricorio_baile",      "Oricorio Baile Style",      741, 10044),
            // Gender forms (distinct TCG card artwork per gender)
            entry("af_meowstic_male",       "Meowstic (Male)",           678, 10045),
            entry("af_meowstic_female",     "Meowstic (Female)",         678, 10046),
            entry("af_indeedee_male",       "Indeedee (Male)",           876, 10047),
            entry("af_indeedee_female",     "Indeedee (Female)",         876, 10048),
            entry("af_basculegion_male",    "Basculegion (Male)",        902, 10049),
            entry("af_basculegion_female",  "Basculegion (Female)",      902, 10050),
            entry("af_oinkologne_male",     "Oinkologne (Male)",         916, 10051),
            entry("af_oinkologne_female",   "Oinkologne (Female)",       916, 10052),
            // Sea form pairs with explicitly labeled TCG cards (DP era)
            entry("af_shellos_east",        "Shellos (East Sea)",        422, 10053),
            entry("af_shellos_west",        "Shellos (West Sea)",        422, 10054),
            entry("af_gastrodon_east",      "Gastrodon (East Sea)",      423, 10055),
            entry("af_gastrodon_west",      "Gastrodon (West Sea)",      423, 10056),
            // Gen VIII — explicitly named promo/alternate form cards
            entry("af_toxtricity_low_key",  "Light Toxtricity",          849, 10057),
            entry("af_ursaluna_bloodmoon",  "Bloodmoon Ursaluna",        901, 10058),
            // Gen IX — Ogerpon mask forms (all four masks have explicit card names)
            entry("af_ogerpon_hearthflame", "Ogerpon (Hearthflame Mask)", 1017, 10059),
            entry("af_ogerpon_wellspring",  "Ogerpon (Wellspring Mask)",  1017, 10060),
            entry("af_ogerpon_cornerstone", "Ogerpon (Cornerstone Mask)", 1017, 10061),
            // Gen VII — Lycanroc forms (distinct artwork per form; card names unlabeled)
            entry("af_lycanroc_midday",     "Lycanroc (Midday)",         745, 10062),
            entry("af_lycanroc_midnight",   "Lycanroc (Midnight)",       745, 10063),
            entry("af_lycanroc_dusk",       "Lycanroc (Dusk)",           745, 10064),
            // Gen IX — Maushold family forms (card names unlabeled)
            entry("af_maushold_three",      "Maushold (Family of Three)", 925, 10065),
            entry("af_maushold_four",       "Maushold (Family of Four)",  925, 10066),
            // Gen IX — Palafin Hero Form (card names unlabeled)
            entry("af_palafin_hero",        "Palafin (Hero Form)",       964, 10067),
            // Gen V — Deerling seasonal forms (card names unlabeled)
            entry("af_deerling_spring",     "Deerling (Spring)",         585, 10068),
            entry("af_deerling_summer",     "Deerling (Summer)",         585, 10069),
            entry("af_deerling_autumn",     "Deerling (Autumn)",         585, 10070),
            entry("af_deerling_winter",     "Deerling (Winter)",         585, 10071),
            // Gen V — Sawsbuck seasonal forms (card names unlabeled)
            entry("af_sawsbuck_spring",     "Sawsbuck (Spring)",         586, 10072),
            entry("af_sawsbuck_summer",     "Sawsbuck (Summer)",         586, 10073),
            entry("af_sawsbuck_autumn",     "Sawsbuck (Autumn)",         586, 10074),
            entry("af_sawsbuck_winter",     "Sawsbuck (Winter)",         586, 10075),
            // Gen VI — Vivillon wing patterns (3 distinct artworks confirmed in TCG)
            entry("af_vivillon_meadow",     "Vivillon (Meadow Pattern)", 666, 10076),
            entry("af_vivillon_fancy",      "Vivillon (Fancy Pattern)",  666, 10077),
            entry("af_vivillon_pokeball",   "Vivillon (Poké Ball Pattern)", 666, 10078),
            // Gen VI — Furfrou trims (6 distinct artworks confirmed in TCG)
            entry("af_furfrou_natural",     "Furfrou (Natural Form)",    676, 10079),
            entry("af_furfrou_heart",       "Furfrou (Heart Trim)",      676, 10080),
            entry("af_furfrou_star",        "Furfrou (Star Trim)",       676, 10081),
            entry("af_furfrou_diamond",     "Furfrou (Diamond Trim)",    676, 10082),
            entry("af_furfrou_debutante",   "Furfrou (Debutante Trim)",  676, 10083),
            entry("af_furfrou_dandy",       "Furfrou (Dandy Trim)",      676, 10084),
            // Gen VII — Minior forms (Meteor shell and coloured Core form)
            entry("af_minior_meteor",       "Minior (Meteor Form)",      774, 10085),
            entry("af_minior_core",         "Minior (Core Form)",        774, 10086),
            // Base forms for all Pokémon that have alt form slots
            // Gen III
            entry("af_castform_normal",     "Castform (Normal Form)",    351, 10087),
            entry("af_deoxys_normal",       "Deoxys (Normal Forme)",     386, 10088),
            // Gen IV
            entry("af_rotom_base",          "Rotom",                     479, 10089),
            entry("af_dialga_base",         "Dialga",                    483, 10090),
            entry("af_palkia_base",         "Palkia",                    484, 10091),
            entry("af_giratina_altered",    "Giratina (Altered Forme)",  487, 10092),
            entry("af_shaymin_land",        "Shaymin (Land Forme)",      492, 10093),
            // Gen V
            entry("af_tornadus_incarnate",  "Tornadus (Incarnate)",      641, 10094),
            entry("af_thundurus_incarnate", "Thundurus (Incarnate)",     642, 10095),
            entry("af_landorus_incarnate",  "Landorus (Incarnate)",      645, 10096),
            entry("af_kyurem_base",         "Kyurem",                    646, 10097),
            entry("af_keldeo_ordinary",     "Keldeo (Ordinary Form)",    647, 10098),
            entry("af_meloetta_aria",       "Meloetta (Aria Forme)",     648, 10099),
            // Gen VI
            entry("af_aegislash_shield",    "Aegislash (Shield Forme)",  681, 10100),
            entry("af_zygarde_50",          "Zygarde (50% Forme)",       718, 10101),
            entry("af_hoopa_confined",      "Hoopa (Confined)",          720, 10102),
            // Gen VII
            entry("af_wishiwashi_solo",     "Wishiwashi (Solo Form)",    746, 10103),
            entry("af_necrozma_base",       "Necrozma",                  800, 10104),
            // Gen VIII
            entry("af_morpeko_full_belly",  "Morpeko (Full Belly)",      877, 10105),
            entry("af_zacian_hero",         "Zacian (Hero)",             888, 10106),
            entry("af_zamazenta_hero",      "Zamazenta (Hero)",          889, 10107),
            entry("af_urshifu_single",      "Urshifu (Single Strike)",   892, 10108),
            entry("af_calyrex_base",        "Calyrex",                   898, 10109),
            // Gen IX
            entry("af_palafin_zero",        "Palafin (Zero Form)",       964, 10110),
            entry("af_ogerpon_teal",        "Ogerpon (Teal Mask)",       1017, 10111),
        )

        private fun entry(id: String, name: String, dex: Int, order: Int) = MainBinderEntry(
            pokemonId = id,
            pokemonName = name,
            dexNumber = dex,
            dexOrder = order,
            slotType = "ALTERNATE_FORM"
        )

        /**
         * Explicit TCG API search name override keyed by alternate-form pokemonId.
         * Only needed where the display name differs from what the API expects.
         * Base pokédex slots (the 1025) never appear here — they always search their plain name.
         */
        val ALTERNATE_FORM_SEARCH_NAMES = mapOf(
            // Gen III
            "af_castform_rainy"      to "Castform",
            "af_castform_snowy"      to "Castform",
            "af_castform_sunny"      to "Castform",
            "af_deoxys_attack"       to "Deoxys",
            "af_deoxys_defense"      to "Deoxys",
            "af_deoxys_speed"        to "Deoxys",
            // Gen IV
            "af_rotom_heat"          to "Rotom",
            "af_rotom_wash"          to "Rotom",
            "af_rotom_frost"         to "Rotom",
            "af_rotom_fan"           to "Rotom",
            "af_rotom_mow"           to "Rotom",
            "af_wormadam_sandy"      to "Wormadam",
            "af_wormadam_trash"      to "Wormadam",
            "af_cherrim_sunshine"    to "Cherrim",
            "af_dialga_origin"       to "Dialga",
            "af_palkia_origin"       to "Palkia",
            "af_giratina_origin"     to "Giratina",
            "af_shaymin_sky"         to "Shaymin",
            // Gen V
            "af_tornadus_therian"    to "Tornadus",
            "af_thundurus_therian"   to "Thundurus",
            "af_landorus_therian"    to "Landorus",
            "af_kyurem_black"        to "Kyurem",
            "af_kyurem_white"        to "Kyurem",
            "af_keldeo_resolute"     to "Keldeo",
            "af_meloetta_pirouette"  to "Meloetta",
            // Gen VI
            "af_aegislash_blade"     to "Aegislash",
            "af_zygarde_10"          to "Zygarde",
            "af_zygarde_complete"    to "Zygarde",
            "af_hoopa_unbound"       to "Hoopa",
            // Gen VII
            "af_oricorio_pompom"     to "Oricorio",
            "af_oricorio_pau"        to "Oricorio",
            "af_oricorio_sensu"      to "Oricorio",
            "af_wishiwashi_school"   to "Wishiwashi",
            "af_necrozma_dusk_mane"  to "Necrozma",
            "af_necrozma_dawn_wings" to "Necrozma",
            // Gen VIII
            "af_morpeko_hangry"      to "Morpeko",
            "af_zacian_crowned"      to "Zacian",
            "af_zamazenta_crowned"   to "Zamazenta",
            "af_urshifu_rapid"       to "Urshifu",
            "af_calyrex_ice"         to "Calyrex",
            "af_calyrex_shadow"      to "Calyrex",
            // Gen IV default forms
            "af_wormadam_plant"      to "Wormadam",
            "af_cherrim_overcast"    to "Cherrim",
            // Gen VII default form
            "af_oricorio_baile"      to "Oricorio",
            // Gender forms
            "af_meowstic_male"       to "Meowstic",
            "af_meowstic_female"     to "Meowstic",
            "af_indeedee_male"       to "Indeedee",
            "af_indeedee_female"     to "Indeedee",
            "af_basculegion_male"    to "Basculegion",
            "af_basculegion_female"  to "Basculegion",
            "af_oinkologne_male"     to "Oinkologne",
            "af_oinkologne_female"   to "Oinkologne",
            // Sea form pairs (DP era — explicit card names)
            "af_shellos_east"        to "Shellos",
            "af_shellos_west"        to "Shellos",
            "af_gastrodon_east"      to "Gastrodon",
            "af_gastrodon_west"      to "Gastrodon",
            // Gen VIII — exact card names (search narrows correctly without override,
            // listed here for clarity; pokemontcg.io wildcard still matches)
            "af_toxtricity_low_key"  to "Toxtricity",
            "af_ursaluna_bloodmoon"  to "Bloodmoon Ursaluna",
            // Gen IX — Ogerpon mask forms (exact prefix in card name)
            "af_ogerpon_hearthflame" to "Hearthflame Mask Ogerpon",
            "af_ogerpon_wellspring"  to "Wellspring Mask Ogerpon",
            "af_ogerpon_cornerstone" to "Cornerstone Mask Ogerpon",
            // Gen VII — Lycanroc forms (artwork differs; card names unlabeled by form)
            "af_lycanroc_midday"     to "Lycanroc",
            "af_lycanroc_midnight"   to "Lycanroc",
            "af_lycanroc_dusk"       to "Lycanroc",
            // Gen IX — Maushold & Palafin (unlabeled forms)
            "af_maushold_three"      to "Maushold",
            "af_maushold_four"       to "Maushold",
            "af_palafin_hero"        to "Palafin",
            // Gen V — Deerling & Sawsbuck seasonal forms (unlabeled)
            "af_deerling_spring"     to "Deerling",
            "af_deerling_summer"     to "Deerling",
            "af_deerling_autumn"     to "Deerling",
            "af_deerling_winter"     to "Deerling",
            "af_sawsbuck_spring"     to "Sawsbuck",
            "af_sawsbuck_summer"     to "Sawsbuck",
            "af_sawsbuck_autumn"     to "Sawsbuck",
            "af_sawsbuck_winter"     to "Sawsbuck",
            // Gen VI — Vivillon patterns (unlabeled)
            "af_vivillon_meadow"     to "Vivillon",
            "af_vivillon_fancy"      to "Vivillon",
            "af_vivillon_pokeball"   to "Vivillon",
            // Gen VI — Furfrou trims (unlabeled)
            "af_furfrou_natural"     to "Furfrou",
            "af_furfrou_heart"       to "Furfrou",
            "af_furfrou_star"        to "Furfrou",
            "af_furfrou_diamond"     to "Furfrou",
            "af_furfrou_debutante"   to "Furfrou",
            "af_furfrou_dandy"       to "Furfrou",
            // Gen VII — Minior forms (unlabeled)
            "af_minior_meteor"       to "Minior",
            "af_minior_core"         to "Minior",
            // Base forms for Pokémon with alt form slots
            "af_castform_normal"     to "Castform",
            "af_deoxys_normal"       to "Deoxys",
            "af_giratina_altered"    to "Giratina",
            "af_shaymin_land"        to "Shaymin",
            "af_tornadus_incarnate"  to "Tornadus",
            "af_thundurus_incarnate" to "Thundurus",
            "af_landorus_incarnate"  to "Landorus",
            "af_keldeo_ordinary"     to "Keldeo",
            "af_meloetta_aria"       to "Meloetta",
            "af_aegislash_shield"    to "Aegislash",
            "af_zygarde_50"          to "Zygarde",
            "af_hoopa_confined"      to "Hoopa",
            "af_wishiwashi_solo"     to "Wishiwashi",
            "af_morpeko_full_belly"  to "Morpeko",
            "af_zacian_hero"         to "Zacian",
            "af_zamazenta_hero"      to "Zamazenta",
            "af_urshifu_single"      to "Urshifu",
            "af_palafin_zero"        to "Palafin",
            "af_ogerpon_teal"        to "Teal Mask Ogerpon",
        )
    }
}
