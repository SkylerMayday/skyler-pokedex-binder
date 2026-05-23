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
        mainBinderDao.deleteRemovedSlots()
        mainBinderDao.migrateAltFormOrdering()
        mainBinderDao.migrateMegaSlots()
        mainBinderDao.migratePrimalDexNumbers()
        mainBinderDao.insertRegionalsIfAbsent(NEW_REGIONAL_SLOTS)
    }

    suspend fun seedMegasIfMissing() {
        mainBinderDao.insertMegasIfAbsent(MEGA_SLOTS)
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
        // Default/base forms use 9xxx dexOrder to sort before variant forms within the same
        // dexNumber group. Variant forms use 10001+ to keep them after all standard slots.
        val ALTERNATE_FORM_SLOTS = listOf(
            // ── Gen III ──────────────────────────────────────────────────────────────────
            entry("af_castform_normal",     "Castform (Normal Form)",    351, 9001),
            entry("af_castform_rainy",      "Castform Rainy Form",       351, 10001),
            entry("af_castform_snowy",      "Castform Snowy Form",       351, 10002),
            entry("af_castform_sunny",      "Castform Sunny Form",       351, 10003),
            entry("af_deoxys_normal",       "Deoxys (Normal Forme)",     386, 9002),
            entry("af_deoxys_attack",       "Deoxys Attack Forme",       386, 10004),
            entry("af_deoxys_defense",      "Deoxys Defense Forme",      386, 10005),
            entry("af_deoxys_speed",        "Deoxys Speed Forme",        386, 10006),
            // ── Gen IV ───────────────────────────────────────────────────────────────────
            entry("af_wormadam_plant",      "Wormadam Plant Cloak",      413, 9003),
            entry("af_wormadam_sandy",      "Wormadam Sandy Cloak",      413, 10007),
            entry("af_wormadam_trash",      "Wormadam Trash Cloak",      413, 10008),
            entry("af_cherrim_overcast",    "Cherrim Overcast Form",     421, 9004),
            entry("af_cherrim_sunshine",    "Cherrim Sunshine Form",     421, 10009),
            entry("af_burmy_plant",         "Burmy (Plant Cloak)",       412, 9034),
            entry("af_burmy_sandy",         "Burmy (Sandy Cloak)",       412, 10112),
            entry("af_burmy_trash",         "Burmy (Trash Cloak)",       412, 10113),
            entry("af_rotom_base",          "Rotom",                     479, 9005),
            entry("af_rotom_heat",          "Heat Rotom",                479, 10010),
            entry("af_rotom_wash",          "Wash Rotom",                479, 10011),
            entry("af_rotom_frost",         "Frost Rotom",               479, 10012),
            entry("af_rotom_fan",           "Fan Rotom",                 479, 10013),
            entry("af_rotom_mow",           "Mow Rotom",                 479, 10014),
            entry("af_dialga_base",         "Dialga",                    483, 9006),
            entry("af_dialga_origin",       "Dialga Origin Forme",       483, 10015),
            entry("af_palkia_base",         "Palkia",                    484, 9007),
            entry("af_palkia_origin",       "Palkia Origin Forme",       484, 10016),
            entry("af_giratina_altered",    "Giratina (Altered Forme)",  487, 9008),
            entry("af_giratina_origin",     "Giratina Origin Forme",     487, 10017),
            entry("af_shaymin_land",        "Shaymin (Land Forme)",      492, 9009),
            entry("af_shaymin_sky",         "Shaymin Sky Forme",         492, 10018),
            // ── Gen V ────────────────────────────────────────────────────────────────────
            entry("af_basculin_red",        "Basculin (Red Striped)",    550, 9032),
            entry("af_basculin_blue",       "Basculin (Blue Striped)",   550, 10114),
            entry("af_darmanitan_standard", "Darmanitan (Standard)",     555, 9033),
            entry("af_darmanitan_zen",      "Darmanitan Zen Mode",       555, 10115),
            entry("af_deerling_spring",     "Deerling (Spring)",         585, 10068),
            entry("af_deerling_summer",     "Deerling (Summer)",         585, 10069),
            entry("af_deerling_autumn",     "Deerling (Autumn)",         585, 10070),
            entry("af_deerling_winter",     "Deerling (Winter)",         585, 10071),
            entry("af_sawsbuck_spring",     "Sawsbuck (Spring)",         586, 10072),
            entry("af_sawsbuck_summer",     "Sawsbuck (Summer)",         586, 10073),
            entry("af_sawsbuck_autumn",     "Sawsbuck (Autumn)",         586, 10074),
            entry("af_sawsbuck_winter",     "Sawsbuck (Winter)",         586, 10075),
            entry("af_tornadus_incarnate",  "Tornadus (Incarnate)",      641, 9010),
            entry("af_tornadus_therian",    "Tornadus Therian",          641, 10019),
            entry("af_thundurus_incarnate", "Thundurus (Incarnate)",     642, 9011),
            entry("af_thundurus_therian",   "Thundurus Therian",         642, 10020),
            entry("af_landorus_incarnate",  "Landorus (Incarnate)",      645, 9012),
            entry("af_landorus_therian",    "Landorus Therian",          645, 10021),
            entry("af_kyurem_base",         "Kyurem",                    646, 9013),
            entry("af_kyurem_black",        "Black Kyurem",              646, 10022),
            entry("af_kyurem_white",        "White Kyurem",              646, 10023),
            entry("af_keldeo_ordinary",     "Keldeo (Ordinary Form)",    647, 9014),
            entry("af_keldeo_resolute",     "Keldeo Resolute Form",      647, 10024),
            entry("af_meloetta_aria",       "Meloetta (Aria Forme)",     648, 9015),
            entry("af_meloetta_pirouette",  "Meloetta Pirouette Forme",  648, 10025),
            // ── Gen VI ───────────────────────────────────────────────────────────────────
            entry("af_vivillon_meadow",     "Vivillon (Meadow Pattern)", 666, 9030),
            entry("af_vivillon_fancy",      "Vivillon (Fancy Pattern)",  666, 10077),
            entry("af_vivillon_pokeball",   "Vivillon (Poké Ball Pattern)", 666, 10078),
            entry("af_furfrou_natural",     "Furfrou (Natural Form)",    676, 9031),
            entry("af_furfrou_heart",       "Furfrou (Heart Trim)",      676, 10080),
            entry("af_furfrou_star",        "Furfrou (Star Trim)",       676, 10081),
            entry("af_furfrou_diamond",     "Furfrou (Diamond Trim)",    676, 10082),
            entry("af_furfrou_debutante",   "Furfrou (Debutante Trim)",  676, 10083),
            entry("af_furfrou_dandy",       "Furfrou (Dandy Trim)",      676, 10084),
            entry("af_meowstic_male",       "Meowstic (Male)",           678, 10045),
            entry("af_meowstic_female",     "Meowstic (Female)",         678, 10046),
            entry("af_aegislash_shield",    "Aegislash (Shield Forme)",  681, 9016),
            entry("af_aegislash_blade",     "Aegislash Blade Forme",     681, 10026),
            entry("af_zygarde_50",          "Zygarde (50% Forme)",       718, 9017),
            entry("af_zygarde_10",          "Zygarde 10% Forme",         718, 10027),
            entry("af_zygarde_complete",    "Zygarde Complete Forme",    718, 10028),
            entry("af_hoopa_confined",      "Hoopa (Confined)",          720, 9018),
            entry("af_hoopa_unbound",       "Hoopa Unbound",             720, 10029),
            // ── Gen VII ──────────────────────────────────────────────────────────────────
            entry("af_oricorio_baile",      "Oricorio Baile Style",      741, 9019),
            entry("af_oricorio_pompom",     "Oricorio Pom-Pom Style",    741, 10030),
            entry("af_oricorio_pau",        "Oricorio Pa'u Style",       741, 10031),
            entry("af_oricorio_sensu",      "Oricorio Sensu Style",      741, 10032),
            entry("af_lycanroc_midday",     "Lycanroc (Midday)",         745, 9021),
            entry("af_lycanroc_midnight",   "Lycanroc (Midnight)",       745, 10063),
            entry("af_lycanroc_dusk",       "Lycanroc (Dusk)",           745, 10064),
            entry("af_wishiwashi_solo",     "Wishiwashi (Solo Form)",    746, 9020),
            entry("af_wishiwashi_school",   "Wishiwashi School Form",    746, 10033),
            entry("af_minior_meteor",       "Minior (Meteor Form)",      774, 9022),
            entry("af_minior_core",         "Minior (Core Form)",        774, 10086),
            entry("af_necrozma_base",       "Necrozma",                  800, 9023),
            entry("af_necrozma_dusk_mane",  "Necrozma Dusk Mane",        800, 10034),
            entry("af_necrozma_dawn_wings", "Necrozma Dawn Wings",       800, 10035),
            // ── Gen VIII ─────────────────────────────────────────────────────────────────
            entry("af_toxtricity_amped",    "Toxtricity Amped",          849, 9035),
            entry("af_toxtricity_low_key",  "Light Toxtricity",          849, 10057),
            entry("af_eiscue_ice",          "Eiscue Ice Face",           875, 9036),
            entry("af_eiscue_noice",        "Eiscue Noice Face",         875, 10116),
            entry("af_indeedee_male",       "Indeedee (Male)",           876, 10047),
            entry("af_indeedee_female",     "Indeedee (Female)",         876, 10048),
            entry("af_morpeko_full_belly",  "Morpeko (Full Belly)",      877, 9024),
            entry("af_morpeko_hangry",      "Morpeko Hangry Mode",       877, 10036),
            entry("af_ursaluna_base",       "Ursaluna",                  901, 9038),
            entry("af_ursaluna_bloodmoon",  "Bloodmoon Ursaluna",        901, 10058),
            entry("af_eternatus_base",      "Eternatus",                 890, 9037),
            entry("af_eternatus_eternamax", "Eternatus Eternamax",       890, 10117),
            entry("af_urshifu_single",      "Urshifu (Single Strike)",   892, 9025),
            entry("af_urshifu_rapid",       "Urshifu Rapid Strike",      892, 10039),
            entry("af_calyrex_base",        "Calyrex",                   898, 9026),
            entry("af_calyrex_ice",         "Calyrex Ice Rider",         898, 10040),
            entry("af_calyrex_shadow",      "Calyrex Shadow Rider",      898, 10041),
            entry("af_oinkologne_male",     "Oinkologne (Male)",         916, 10051),
            entry("af_oinkologne_female",   "Oinkologne (Female)",       916, 10052),
            // ── Gen IX ───────────────────────────────────────────────────────────────────
            entry("af_maushold_four",       "Maushold (Family of Four)",  925, 9029),
            entry("af_maushold_three",      "Maushold (Family of Three)", 925, 10065),
            entry("af_palafin_zero",        "Palafin (Zero Form)",       964, 9027),
            entry("af_palafin_hero",        "Palafin (Hero Form)",       964, 10067),
            entry("af_terapagos_normal",    "Terapagos (Normal Form)",   1024, 9039),
            entry("af_terapagos_terastal",  "Terapagos (Terastal Form)", 1024, 10118),
            entry("af_terapagos_stellar",   "Terapagos (Stellar Form)",  1024, 10119),
            entry("af_ogerpon_teal",        "Ogerpon (Teal Mask)",       1017, 9028),
            entry("af_ogerpon_hearthflame", "Ogerpon (Hearthflame Mask)", 1017, 10059),
            entry("af_ogerpon_wellspring",  "Ogerpon (Wellspring Mask)",  1017, 10060),
            entry("af_ogerpon_cornerstone", "Ogerpon (Cornerstone Mask)", 1017, 10061),
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
            "af_castform_normal"     to "Castform",
            "af_castform_rainy"      to "Castform",
            "af_castform_snowy"      to "Castform",
            "af_castform_sunny"      to "Castform",
            "af_deoxys_normal"       to "Deoxys",
            "af_deoxys_attack"       to "Deoxys",
            "af_deoxys_defense"      to "Deoxys",
            "af_deoxys_speed"        to "Deoxys",
            // Gen IV
            "af_burmy_plant"         to "Burmy",
            "af_burmy_sandy"         to "Burmy",
            "af_burmy_trash"         to "Burmy",
            "af_wormadam_plant"      to "Wormadam",
            "af_wormadam_sandy"      to "Wormadam",
            "af_wormadam_trash"      to "Wormadam",
            "af_cherrim_overcast"    to "Cherrim",
            "af_cherrim_sunshine"    to "Cherrim",
            "af_rotom_heat"          to "Rotom",
            "af_rotom_wash"          to "Rotom",
            "af_rotom_frost"         to "Rotom",
            "af_rotom_fan"           to "Rotom",
            "af_rotom_mow"           to "Rotom",
            "af_dialga_origin"       to "Dialga",
            "af_palkia_origin"       to "Palkia",
            "af_giratina_altered"    to "Giratina",
            "af_giratina_origin"     to "Giratina",
            "af_shaymin_land"        to "Shaymin",
            "af_shaymin_sky"         to "Shaymin",
            // Gen V
            "af_basculin_red"        to "Basculin",
            "af_basculin_blue"       to "Basculin",
            "af_darmanitan_standard" to "Darmanitan",
            "af_darmanitan_zen"      to "Darmanitan",
            "af_deerling_spring"     to "Deerling",
            "af_deerling_summer"     to "Deerling",
            "af_deerling_autumn"     to "Deerling",
            "af_deerling_winter"     to "Deerling",
            "af_sawsbuck_spring"     to "Sawsbuck",
            "af_sawsbuck_summer"     to "Sawsbuck",
            "af_sawsbuck_autumn"     to "Sawsbuck",
            "af_sawsbuck_winter"     to "Sawsbuck",
            "af_tornadus_incarnate"  to "Tornadus",
            "af_tornadus_therian"    to "Tornadus",
            "af_thundurus_incarnate" to "Thundurus",
            "af_thundurus_therian"   to "Thundurus",
            "af_landorus_incarnate"  to "Landorus",
            "af_landorus_therian"    to "Landorus",
            "af_kyurem_black"        to "Kyurem",
            "af_kyurem_white"        to "Kyurem",
            "af_keldeo_ordinary"     to "Keldeo",
            "af_keldeo_resolute"     to "Keldeo",
            "af_meloetta_aria"       to "Meloetta",
            "af_meloetta_pirouette"  to "Meloetta",
            // Gen VI
            "af_vivillon_meadow"     to "Vivillon",
            "af_vivillon_fancy"      to "Vivillon",
            "af_vivillon_pokeball"   to "Vivillon",
            "af_furfrou_natural"     to "Furfrou",
            "af_furfrou_heart"       to "Furfrou",
            "af_furfrou_star"        to "Furfrou",
            "af_furfrou_diamond"     to "Furfrou",
            "af_furfrou_debutante"   to "Furfrou",
            "af_furfrou_dandy"       to "Furfrou",
            "af_meowstic_male"       to "Meowstic",
            "af_meowstic_female"     to "Meowstic",
            "af_aegislash_shield"    to "Aegislash",
            "af_aegislash_blade"     to "Aegislash",
            "af_zygarde_50"          to "Zygarde",
            "af_zygarde_10"          to "Zygarde",
            "af_zygarde_complete"    to "Zygarde",
            "af_hoopa_confined"      to "Hoopa",
            "af_hoopa_unbound"       to "Hoopa",
            // Gen VII
            "af_oricorio_baile"      to "Oricorio",
            "af_oricorio_pompom"     to "Oricorio",
            "af_oricorio_pau"        to "Oricorio",
            "af_oricorio_sensu"      to "Oricorio",
            "af_lycanroc_midday"     to "Lycanroc",
            "af_lycanroc_midnight"   to "Lycanroc",
            "af_lycanroc_dusk"       to "Lycanroc",
            "af_wishiwashi_solo"     to "Wishiwashi",
            "af_wishiwashi_school"   to "Wishiwashi",
            "af_minior_meteor"       to "Minior",
            "af_minior_core"         to "Minior",
            "af_necrozma_dusk_mane"  to "Necrozma",
            "af_necrozma_dawn_wings" to "Necrozma",
            // Gen VIII
            "af_toxtricity_amped"    to "Toxtricity",
            "af_toxtricity_low_key"  to "Toxtricity",
            "af_eiscue_ice"          to "Eiscue",
            "af_eiscue_noice"        to "Eiscue",
            "af_indeedee_male"       to "Indeedee",
            "af_indeedee_female"     to "Indeedee",
            "af_morpeko_hangry"      to "Morpeko",
            "af_morpeko_full_belly"  to "Morpeko",
            "af_ursaluna_bloodmoon"  to "Bloodmoon Ursaluna",
            "af_eternatus_base"      to "Eternatus",
            "af_eternatus_eternamax" to "Eternatus",
            "af_urshifu_single"      to "Urshifu",
            "af_urshifu_rapid"       to "Urshifu",
            "af_calyrex_ice"         to "Calyrex",
            "af_calyrex_shadow"      to "Calyrex",
            "af_oinkologne_male"     to "Oinkologne",
            "af_oinkologne_female"   to "Oinkologne",
            // Gen IX
            "af_maushold_four"       to "Maushold",
            "af_maushold_three"      to "Maushold",
            "af_palafin_zero"        to "Palafin",
            "af_palafin_hero"        to "Palafin",
            "af_terapagos_normal"    to "Terapagos",
            "af_terapagos_terastal"  to "Terapagos",
            "af_terapagos_stellar"   to "Terapagos",
            "af_ogerpon_teal"        to "Teal Mask Ogerpon",
            "af_ogerpon_hearthflame" to "Hearthflame Mask Ogerpon",
            "af_ogerpon_wellspring"  to "Wellspring Mask Ogerpon",
            "af_ogerpon_cornerstone" to "Cornerstone Mask Ogerpon",
        )

        /** All 96 mega evolution slots, seeded on existing installs via insertMegasIfAbsent. */
        val MEGA_SLOTS = listOf(
            // ── Gen I ──────────────────────────────────────────────────────────────────────
            mega("venusaur-mega",         "Mega Venusaur",                  3,   2001),
            mega("charizard-mega-x",      "Mega Charizard X",               6,   2002),
            mega("charizard-mega-y",      "Mega Charizard Y",               6,   2003),
            mega("blastoise-mega",        "Mega Blastoise",                 9,   2004),
            mega("beedrill-mega",         "Mega Beedrill",                  15,  2005),
            mega("pidgeot-mega",          "Mega Pidgeot",                   18,  2006),
            mega("raichu-mega-x",         "Mega Raichu X",                  26,  2007),
            mega("raichu-mega-y",         "Mega Raichu Y",                  26,  2008),
            mega("clefable-mega",         "Mega Clefable",                  36,  2009),
            mega("alakazam-mega",         "Mega Alakazam",                  65,  2010),
            mega("victreebel-mega",       "Mega Victreebel",                71,  2011),
            mega("slowbro-mega",          "Mega Slowbro",                   80,  2012),
            mega("gengar-mega",           "Mega Gengar",                    94,  2013),
            mega("kangaskhan-mega",       "Mega Kangaskhan",                115, 2014),
            mega("starmie-mega",          "Mega Starmie",                   121, 2015),
            mega("pinsir-mega",           "Mega Pinsir",                    127, 2016),
            mega("gyarados-mega",         "Mega Gyarados",                  130, 2017),
            mega("aerodactyl-mega",       "Mega Aerodactyl",                142, 2018),
            mega("dragonite-mega",        "Mega Dragonite",                 149, 2019),
            mega("mewtwo-mega-x",         "Mega Mewtwo X",                  150, 2020),
            mega("mewtwo-mega-y",         "Mega Mewtwo Y",                  150, 2021),
            // ── Gen II ─────────────────────────────────────────────────────────────────────
            mega("meganium-mega",         "Mega Meganium",                  154, 2022),
            mega("feraligatr-mega",       "Mega Feraligatr",                160, 2023),
            mega("ampharos-mega",         "Mega Ampharos",                  181, 2024),
            mega("steelix-mega",          "Mega Steelix",                   208, 2025),
            mega("scizor-mega",           "Mega Scizor",                    212, 2026),
            mega("heracross-mega",        "Mega Heracross",                 214, 2027),
            mega("skarmory-mega",         "Mega Skarmory",                  227, 2028),
            mega("houndoom-mega",         "Mega Houndoom",                  229, 2029),
            mega("tyranitar-mega",        "Mega Tyranitar",                 248, 2030),
            // ── Gen III ────────────────────────────────────────────────────────────────────
            mega("sceptile-mega",         "Mega Sceptile",                  254, 2031),
            mega("blaziken-mega",         "Mega Blaziken",                  257, 2032),
            mega("swampert-mega",         "Mega Swampert",                  260, 2033),
            mega("gardevoir-mega",        "Mega Gardevoir",                 282, 2034),
            mega("sableye-mega",          "Mega Sableye",                   302, 2035),
            mega("mawile-mega",           "Mega Mawile",                    303, 2036),
            mega("aggron-mega",           "Mega Aggron",                    306, 2037),
            mega("medicham-mega",         "Mega Medicham",                  308, 2038),
            mega("manectric-mega",        "Mega Manectric",                 310, 2039),
            mega("sharpedo-mega",         "Mega Sharpedo",                  319, 2040),
            mega("camerupt-mega",         "Mega Camerupt",                  323, 2041),
            mega("altaria-mega",          "Mega Altaria",                   334, 2042),
            mega("banette-mega",          "Mega Banette",                   354, 2043),
            mega("chimecho-mega",         "Mega Chimecho",                  358, 2044),
            mega("absol-mega",            "Mega Absol",                     359, 2045),
            mega("absol-mega-z",          "Mega Absol Z",                   359, 2046),
            mega("glalie-mega",           "Mega Glalie",                    362, 2047),
            mega("salamence-mega",        "Mega Salamence",                 373, 2048),
            mega("metagross-mega",        "Mega Metagross",                 376, 2049),
            mega("latias-mega",           "Mega Latias",                    380, 2050),
            mega("latios-mega",           "Mega Latios",                    381, 2051),
            mega("rayquaza-mega",         "Mega Rayquaza",                  384, 2052),
            // ── Gen IV ─────────────────────────────────────────────────────────────────────
            mega("staraptor-mega",        "Mega Staraptor",                 398, 2053),
            mega("lopunny-mega",          "Mega Lopunny",                   428, 2054),
            mega("garchomp-mega",         "Mega Garchomp",                  445, 2055),
            mega("garchomp-mega-z",       "Mega Garchomp Z",                445, 2056),
            mega("lucario-mega",          "Mega Lucario",                   448, 2057),
            mega("lucario-mega-z",        "Mega Lucario Z",                 448, 2058),
            mega("abomasnow-mega",        "Mega Abomasnow",                 460, 2059),
            mega("gallade-mega",          "Mega Gallade",                   475, 2060),
            mega("froslass-mega",         "Mega Froslass",                  478, 2061),
            mega("heatran-mega",          "Mega Heatran",                   485, 2062),
            mega("darkrai-mega",          "Mega Darkrai",                   491, 2063),
            // ── Gen V ──────────────────────────────────────────────────────────────────────
            mega("emboar-mega",           "Mega Emboar",                    500, 2064),
            mega("excadrill-mega",        "Mega Excadrill",                 530, 2065),
            mega("audino-mega",           "Mega Audino",                    531, 2066),
            mega("scolipede-mega",        "Mega Scolipede",                 545, 2067),
            mega("scrafty-mega",          "Mega Scrafty",                   560, 2068),
            mega("eelektross-mega",       "Mega Eelektross",                604, 2069),
            mega("chandelure-mega",       "Mega Chandelure",                609, 2070),
            mega("golurk-mega",           "Mega Golurk",                    623, 2071),
            // ── Gen VI ─────────────────────────────────────────────────────────────────────
            mega("chesnaught-mega",       "Mega Chesnaught",                652, 2072),
            mega("delphox-mega",          "Mega Delphox",                   655, 2073),
            mega("greninja-mega",         "Mega Greninja",                  658, 2074),
            mega("pyroar-mega",           "Mega Pyroar",                    668, 2075),
            mega("floette-mega-eternal",  "Mega Floette Eternal Flower",    670, 2076),
            mega("meowstic-mega",         "Mega Meowstic",                  678, 2077),
            mega("malamar-mega",          "Mega Malamar",                   687, 2078),
            mega("barbaracle-mega",       "Mega Barbaracle",                689, 2079),
            mega("dragalge-mega",         "Mega Dragalge",                  691, 2080),
            mega("hawlucha-mega",         "Mega Hawlucha",                  701, 2081),
            mega("zygarde-mega",          "Mega Zygarde",                   718, 2082),
            mega("diancie-mega",          "Mega Diancie",                   719, 2083),
            // ── Gen VII ────────────────────────────────────────────────────────────────────
            mega("crabominable-mega",     "Mega Crabominable",              740, 2084),
            mega("golisopod-mega",        "Mega Golisopod",                 768, 2085),
            mega("drampa-mega",           "Mega Drampa",                    780, 2086),
            mega("magearna-mega",         "Mega Magearna",                  801, 2087),
            mega("magearna-mega-original","Mega Magearna Original Color",   801, 2088),
            mega("zeraora-mega",          "Mega Zeraora",                   807, 2089),
            // ── Gen VIII ───────────────────────────────────────────────────────────────────
            mega("falinks-mega",          "Mega Falinks",                   870, 2090),
            // ── Gen IX ─────────────────────────────────────────────────────────────────────
            mega("scovillain-mega",       "Mega Scovillain",                952, 2091),
            mega("glimmora-mega",         "Mega Glimmora",                  970, 2092),
            mega("tatsugiri-mega",         "Mega Tatsugiri",                  978, 2093),
            mega("baxcalibur-mega",       "Mega Baxcalibur",                998, 2096),
            // ── Primal Reversions ──────────────────────────────────────────────────────────
            mega("kyogre-primal",         "Primal Kyogre",                  9997, 2097),
            mega("groudon-primal",        "Primal Groudon",                 9998, 2098),
        )

        private fun mega(id: String, name: String, dex: Int, order: Int) = MainBinderEntry(
            pokemonId = id,
            pokemonName = name,
            dexNumber = dex,
            dexOrder = order,
            slotType = "MEGA"
        )

        /** New regional variants seeded on existing installs via insertIfAbsent. */
        val NEW_REGIONAL_SLOTS = listOf(
            regional("farfetchd-galar",          "Galarian Farfetch'd",    83,  1077),
            regional("mr-mime-galar",             "Galarian Mr. Mime",      122, 1078),
            regional("darmanitan-galar-standard", "Galarian Darmanitan",    555, 1079),
            regional("darmanitan-galar-zen",      "Galarian Darmanitan",    555, 1080),
            regional("yamask-galar",              "Galarian Yamask",        562, 1081),
            regional("zorua-hisui",               "Hisuian Zorua",          570, 1082),
            regional("goodra-hisui",              "Hisuian Goodra",         706, 1083),
            regional("decidueye-hisui",           "Hisuian Decidueye",      724, 1084),
        )

        private fun regional(id: String, name: String, dex: Int, order: Int) = MainBinderEntry(
            pokemonId = id,
            pokemonName = name,
            dexNumber = dex,
            dexOrder = order,
            slotType = "regional"
        )
    }
}
