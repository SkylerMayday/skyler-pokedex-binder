package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MainBinderDao {
    @Query("SELECT * FROM main_binder ORDER BY dexOrder ASC")
    fun observeAll(): Flow<List<MainBinderEntry>>

    @Query("SELECT * FROM main_binder WHERE pokemonId = :pokemonId")
    suspend fun getByPokemonId(pokemonId: String): MainBinderEntry?

    @Query("SELECT * FROM main_binder WHERE pokemonId = :pokemonId")
    fun observeByPokemonId(pokemonId: String): Flow<MainBinderEntry?>

    @Upsert
    suspend fun upsert(entry: MainBinderEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MainBinderEntry>)

    @Query("SELECT * FROM main_binder ORDER BY dexOrder ASC")
    suspend fun getAll(): List<MainBinderEntry>

    @Query("SELECT COUNT(*) FROM main_binder")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM main_binder WHERE slotType = 'ALTERNATE_FORM'")
    suspend fun countAlternateForms(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entries: List<MainBinderEntry>)

    /** Strips the "Altered" qualifier from the Giratina base slot for existing DB rows. */
    @Query("UPDATE main_binder SET pokemonName = 'Giratina' WHERE pokemonId = 'giratina-altered' AND pokemonName = 'Giratina Altered'")
    suspend fun migrateGiratinaName()

    /** Fixes toxtricity-gmax dex_number from 0 to its correct form ID so sorting works. */
    @Query("UPDATE main_binder SET dexNumber = 10219 WHERE pokemonId = 'toxtricity-gmax' AND dexNumber = 0")
    suspend fun migrateToxtricityGmaxDexNumber()

    /** Strips form qualifiers from base Pokédex slots so search uses the plain Pokémon name. */
    @Query("""
        UPDATE main_binder SET pokemonName = CASE pokemonId
            WHEN 'deoxys-normal'         THEN 'Deoxys'
            WHEN 'shaymin-land'          THEN 'Shaymin'
            WHEN 'tornadus-incarnate'    THEN 'Tornadus'
            WHEN 'thundurus-incarnate'   THEN 'Thundurus'
            WHEN 'landorus-incarnate'    THEN 'Landorus'
            WHEN 'keldeo-ordinary'       THEN 'Keldeo'
            WHEN 'meloetta-aria'         THEN 'Meloetta'
            WHEN 'aegislash-shield'      THEN 'Aegislash'
            WHEN 'oricorio-baile'        THEN 'Oricorio'
            WHEN 'wishiwashi-solo'       THEN 'Wishiwashi'
            WHEN 'morpeko-full-belly'    THEN 'Morpeko'
            WHEN 'urshifu-single-strike' THEN 'Urshifu'
            WHEN 'enamorus-incarnate'    THEN 'Enamorus'
            WHEN 'palafin-zero'         THEN 'Palafin'
            ELSE pokemonName
        END
        WHERE pokemonId IN (
            'deoxys-normal', 'shaymin-land', 'tornadus-incarnate', 'thundurus-incarnate',
            'landorus-incarnate', 'keldeo-ordinary', 'meloetta-aria', 'aegislash-shield',
            'oricorio-baile', 'wishiwashi-solo', 'morpeko-full-belly', 'urshifu-single-strike',
            'enamorus-incarnate', 'palafin-zero'
        )
    """)
    suspend fun migrateBaseSlotNames()

    /** Strips form qualifiers from a second batch of base Pokédex slots (wave 2). */
    @Query("""
        UPDATE main_binder SET pokemonName = CASE pokemonId
            WHEN 'wormadam-plant'             THEN 'Wormadam'
            WHEN 'basculin-red-striped'       THEN 'Basculin'
            WHEN 'darmanitan-standard'        THEN 'Darmanitan'
            WHEN 'frillish-male'              THEN 'Frillish'
            WHEN 'jellicent-male'             THEN 'Jellicent'
            WHEN 'pyroar-male'               THEN 'Pyroar'
            WHEN 'meowstic-male'             THEN 'Meowstic'
            WHEN 'pumpkaboo-average'          THEN 'Pumpkaboo'
            WHEN 'gourgeist-average'          THEN 'Gourgeist'
            WHEN 'zygarde-50'                 THEN 'Zygarde'
            WHEN 'lycanroc-midday'            THEN 'Lycanroc'
            WHEN 'minior-red-meteor'          THEN 'Minior'
            WHEN 'mimikyu-disguised'          THEN 'Mimikyu'
            WHEN 'toxtricity-amped'           THEN 'Toxtricity'
            WHEN 'eiscue-ice'                 THEN 'Eiscue'
            WHEN 'indeedee-male'              THEN 'Indeedee'
            WHEN 'basculegion-male'           THEN 'Basculegion'
            WHEN 'oinkologne-male'            THEN 'Oinkologne'
            WHEN 'maushold-family-of-four'    THEN 'Maushold'
            WHEN 'squawkabilly-green-plumage' THEN 'Squawkabilly'
            WHEN 'tatsugiri-curly'            THEN 'Tatsugiri'
            WHEN 'dudunsparce-two-segment'    THEN 'Dudunsparce'
            ELSE pokemonName
        END
        WHERE pokemonId IN (
            'wormadam-plant', 'basculin-red-striped', 'darmanitan-standard',
            'frillish-male', 'jellicent-male', 'pyroar-male', 'meowstic-male',
            'pumpkaboo-average', 'gourgeist-average', 'zygarde-50', 'lycanroc-midday',
            'minior-red-meteor', 'mimikyu-disguised', 'toxtricity-amped', 'eiscue-ice',
            'indeedee-male', 'basculegion-male', 'oinkologne-male', 'maushold-family-of-four',
            'squawkabilly-green-plumage', 'tatsugiri-curly', 'dudunsparce-two-segment'
        )
    """)
    suspend fun migrateBaseSlotNamesWave2()

    /** Renames old "[Pokemon] Mega [X/Y]" format to "Mega [Pokemon] [X/Y]" for existing DB rows. */
    @Query("""
        UPDATE main_binder
        SET pokemonName = 'Mega ' || SUBSTR(pokemonName, 1, INSTR(pokemonName, ' Mega') - 1) || SUBSTR(pokemonName, INSTR(pokemonName, ' Mega') + 5)
        WHERE UPPER(slotType) = 'MEGA' AND pokemonName NOT LIKE 'Mega %' AND pokemonName LIKE '% Mega%'
    """)
    suspend fun migrateMegaNamesToPrefixFormat()

    /** Fixes Primal names corrupted by migrateMegaNamesToPrefixFormat (e.g. "Mega al Kyogre"). */
    @Query("""
        UPDATE main_binder SET pokemonName = CASE pokemonId
            WHEN 'kyogre-primal' THEN 'Primal Kyogre'
            WHEN 'groudon-primal' THEN 'Primal Groudon'
            ELSE pokemonName
        END
        WHERE pokemonId IN ('kyogre-primal', 'groudon-primal')
    """)
    suspend fun migratePrimalNames()

    /** Renames old "[Pokemon] (Gigantamax)" format to "[Pokemon] VMax" for existing DB rows. */
    @Query("UPDATE main_binder SET pokemonName = SUBSTR(pokemonName, 1, INSTR(pokemonName, ' (Gigantamax)') - 1) || ' VMax' WHERE UPPER(slotType) = 'GMAX' AND pokemonName LIKE '%(Gigantamax)%'")
    suspend fun migrateGmaxNamesToVMax()

    /** Strips the hyphen from "[Pokemon] V-Max" → "[Pokemon] VMax" for installs that already ran the old migration. */
    @Query("UPDATE main_binder SET pokemonName = REPLACE(pokemonName, ' V-Max', ' VMax') WHERE UPPER(slotType) = 'GMAX' AND pokemonName LIKE '% V-Max'")
    suspend fun migrateGmaxVMaxHyphen()

    /** Removes entries that were pruned from the binder (duplicates, incorrect inclusions). */
    @Query("""
        DELETE FROM main_binder WHERE pokemonId IN (
            'raticate-totem-alola',
            'af_shellos_east', 'af_shellos_west',
            'af_gastrodon_east', 'af_gastrodon_west',
            'af_zacian_crowned', 'af_zacian_hero',
            'af_zamazenta_crowned', 'af_zamazenta_hero',
            'af_basculegion_male', 'af_basculegion_female',
            'tatsugiri-mega-curly', 'tatsugiri-mega-droopy', 'tatsugiri-mega-stretchy'
        )
    """)
    suspend fun deleteRemovedSlots()

    /** Moves default/base alt-form entries to 9xxx dexOrder so they sort before variant forms. */
    @Query("""
        UPDATE main_binder SET dexOrder = CASE pokemonId
            WHEN 'af_castform_normal'     THEN 9001
            WHEN 'af_deoxys_normal'       THEN 9002
            WHEN 'af_wormadam_plant'      THEN 9003
            WHEN 'af_cherrim_overcast'    THEN 9004
            WHEN 'af_rotom_base'          THEN 9005
            WHEN 'af_dialga_base'         THEN 9006
            WHEN 'af_palkia_base'         THEN 9007
            WHEN 'af_giratina_altered'    THEN 9008
            WHEN 'af_shaymin_land'        THEN 9009
            WHEN 'af_tornadus_incarnate'  THEN 9010
            WHEN 'af_thundurus_incarnate' THEN 9011
            WHEN 'af_landorus_incarnate'  THEN 9012
            WHEN 'af_kyurem_base'         THEN 9013
            WHEN 'af_keldeo_ordinary'     THEN 9014
            WHEN 'af_meloetta_aria'       THEN 9015
            WHEN 'af_aegislash_shield'    THEN 9016
            WHEN 'af_zygarde_50'          THEN 9017
            WHEN 'af_hoopa_confined'      THEN 9018
            WHEN 'af_oricorio_baile'      THEN 9019
            WHEN 'af_wishiwashi_solo'     THEN 9020
            WHEN 'af_lycanroc_midday'     THEN 9021
            WHEN 'af_minior_meteor'       THEN 9022
            WHEN 'af_necrozma_base'       THEN 9023
            WHEN 'af_morpeko_full_belly'  THEN 9024
            WHEN 'af_urshifu_single'      THEN 9025
            WHEN 'af_calyrex_base'        THEN 9026
            WHEN 'af_palafin_zero'        THEN 9027
            WHEN 'af_ogerpon_teal'        THEN 9028
            WHEN 'af_maushold_four'       THEN 9029
            WHEN 'af_vivillon_meadow'     THEN 9030
            WHEN 'af_furfrou_natural'     THEN 9031
            ELSE dexOrder
        END
        WHERE pokemonId IN (
            'af_castform_normal', 'af_deoxys_normal', 'af_wormadam_plant', 'af_cherrim_overcast',
            'af_rotom_base', 'af_dialga_base', 'af_palkia_base', 'af_giratina_altered',
            'af_shaymin_land', 'af_tornadus_incarnate', 'af_thundurus_incarnate',
            'af_landorus_incarnate', 'af_kyurem_base', 'af_keldeo_ordinary', 'af_meloetta_aria',
            'af_aegislash_shield', 'af_zygarde_50', 'af_hoopa_confined', 'af_oricorio_baile',
            'af_wishiwashi_solo', 'af_lycanroc_midday', 'af_minior_meteor', 'af_necrozma_base',
            'af_morpeko_full_belly', 'af_urshifu_single', 'af_calyrex_base', 'af_palafin_zero',
            'af_ogerpon_teal', 'af_maushold_four', 'af_vivillon_meadow', 'af_furfrou_natural'
        )
    """)
    suspend fun migrateAltFormOrdering()

    /** Inserts new regional variants for existing installs (INSERT OR IGNORE). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRegionalsIfAbsent(entries: List<MainBinderEntry>)

    /**
     * Fixes the 48 existing mega entries: updates dexNumber from PokeAPI form IDs to the
     * base Pokémon's national dex number, and dexOrder to the new 2001-2096 range.
     * Safe to re-run — existing rows already at the correct dexNumber will no-op via WHERE.
     */
    @Query("""
        UPDATE main_binder SET
            dexNumber = CASE pokemonId
                WHEN 'venusaur-mega'    THEN 3
                WHEN 'charizard-mega-x' THEN 6
                WHEN 'charizard-mega-y' THEN 6
                WHEN 'blastoise-mega'   THEN 9
                WHEN 'beedrill-mega'    THEN 15
                WHEN 'pidgeot-mega'     THEN 18
                WHEN 'alakazam-mega'    THEN 65
                WHEN 'slowbro-mega'     THEN 80
                WHEN 'gengar-mega'      THEN 94
                WHEN 'kangaskhan-mega'  THEN 115
                WHEN 'pinsir-mega'      THEN 127
                WHEN 'gyarados-mega'    THEN 130
                WHEN 'aerodactyl-mega'  THEN 142
                WHEN 'mewtwo-mega-x'    THEN 150
                WHEN 'mewtwo-mega-y'    THEN 150
                WHEN 'ampharos-mega'    THEN 181
                WHEN 'steelix-mega'     THEN 208
                WHEN 'scizor-mega'      THEN 212
                WHEN 'heracross-mega'   THEN 214
                WHEN 'houndoom-mega'    THEN 229
                WHEN 'tyranitar-mega'   THEN 248
                WHEN 'sceptile-mega'    THEN 254
                WHEN 'blaziken-mega'    THEN 257
                WHEN 'swampert-mega'    THEN 260
                WHEN 'gardevoir-mega'   THEN 282
                WHEN 'sableye-mega'     THEN 302
                WHEN 'mawile-mega'      THEN 303
                WHEN 'aggron-mega'      THEN 306
                WHEN 'medicham-mega'    THEN 308
                WHEN 'manectric-mega'   THEN 310
                WHEN 'sharpedo-mega'    THEN 319
                WHEN 'camerupt-mega'    THEN 323
                WHEN 'altaria-mega'     THEN 334
                WHEN 'banette-mega'     THEN 354
                WHEN 'absol-mega'       THEN 359
                WHEN 'glalie-mega'      THEN 362
                WHEN 'salamence-mega'   THEN 373
                WHEN 'metagross-mega'   THEN 376
                WHEN 'latias-mega'      THEN 380
                WHEN 'latios-mega'      THEN 381
                WHEN 'rayquaza-mega'    THEN 384
                WHEN 'lopunny-mega'     THEN 428
                WHEN 'garchomp-mega'    THEN 445
                WHEN 'lucario-mega'     THEN 448
                WHEN 'abomasnow-mega'   THEN 460
                WHEN 'gallade-mega'     THEN 475
                WHEN 'audino-mega'      THEN 531
                WHEN 'diancie-mega'     THEN 719
                ELSE dexNumber
            END,
            dexOrder = CASE pokemonId
                WHEN 'venusaur-mega'    THEN 2001
                WHEN 'charizard-mega-x' THEN 2002
                WHEN 'charizard-mega-y' THEN 2003
                WHEN 'blastoise-mega'   THEN 2004
                WHEN 'beedrill-mega'    THEN 2005
                WHEN 'pidgeot-mega'     THEN 2006
                WHEN 'alakazam-mega'    THEN 2010
                WHEN 'slowbro-mega'     THEN 2012
                WHEN 'gengar-mega'      THEN 2013
                WHEN 'kangaskhan-mega'  THEN 2014
                WHEN 'pinsir-mega'      THEN 2016
                WHEN 'gyarados-mega'    THEN 2017
                WHEN 'aerodactyl-mega'  THEN 2018
                WHEN 'mewtwo-mega-x'    THEN 2020
                WHEN 'mewtwo-mega-y'    THEN 2021
                WHEN 'ampharos-mega'    THEN 2024
                WHEN 'steelix-mega'     THEN 2025
                WHEN 'scizor-mega'      THEN 2026
                WHEN 'heracross-mega'   THEN 2027
                WHEN 'houndoom-mega'    THEN 2029
                WHEN 'tyranitar-mega'   THEN 2030
                WHEN 'sceptile-mega'    THEN 2031
                WHEN 'blaziken-mega'    THEN 2032
                WHEN 'swampert-mega'    THEN 2033
                WHEN 'gardevoir-mega'   THEN 2034
                WHEN 'sableye-mega'     THEN 2035
                WHEN 'mawile-mega'      THEN 2036
                WHEN 'aggron-mega'      THEN 2037
                WHEN 'medicham-mega'    THEN 2038
                WHEN 'manectric-mega'   THEN 2039
                WHEN 'sharpedo-mega'    THEN 2040
                WHEN 'camerupt-mega'    THEN 2041
                WHEN 'altaria-mega'     THEN 2042
                WHEN 'banette-mega'     THEN 2043
                WHEN 'absol-mega'       THEN 2045
                WHEN 'glalie-mega'      THEN 2047
                WHEN 'salamence-mega'   THEN 2048
                WHEN 'metagross-mega'   THEN 2049
                WHEN 'latias-mega'      THEN 2050
                WHEN 'latios-mega'      THEN 2051
                WHEN 'rayquaza-mega'    THEN 2052
                WHEN 'lopunny-mega'     THEN 2054
                WHEN 'garchomp-mega'    THEN 2055
                WHEN 'lucario-mega'     THEN 2057
                WHEN 'abomasnow-mega'   THEN 2059
                WHEN 'gallade-mega'     THEN 2060
                WHEN 'audino-mega'      THEN 2066
                WHEN 'diancie-mega'     THEN 2083
                ELSE dexOrder
            END
        WHERE pokemonId IN (
            'venusaur-mega', 'charizard-mega-x', 'charizard-mega-y', 'blastoise-mega',
            'beedrill-mega', 'pidgeot-mega', 'alakazam-mega', 'slowbro-mega',
            'gengar-mega', 'kangaskhan-mega', 'pinsir-mega', 'gyarados-mega',
            'aerodactyl-mega', 'mewtwo-mega-x', 'mewtwo-mega-y', 'ampharos-mega',
            'steelix-mega', 'scizor-mega', 'heracross-mega', 'houndoom-mega',
            'tyranitar-mega', 'sceptile-mega', 'blaziken-mega', 'swampert-mega',
            'gardevoir-mega', 'sableye-mega', 'mawile-mega', 'aggron-mega',
            'medicham-mega', 'manectric-mega', 'sharpedo-mega', 'camerupt-mega',
            'altaria-mega', 'banette-mega', 'absol-mega', 'glalie-mega',
            'salamence-mega', 'metagross-mega', 'latias-mega', 'latios-mega',
            'rayquaza-mega', 'lopunny-mega', 'garchomp-mega', 'lucario-mega',
            'abomasnow-mega', 'gallade-mega', 'audino-mega', 'diancie-mega'
        )
    """)
    suspend fun migrateMegaSlots()

    /** Inserts new mega slots for existing installs (INSERT OR IGNORE). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMegasIfAbsent(entries: List<MainBinderEntry>)

    /** Fixes Primal Kyogre/Groudon dexNumber to sort them at the end of the Mega section. */
    @Query("""
        UPDATE main_binder SET dexNumber = CASE pokemonId
            WHEN 'kyogre-primal' THEN 9997
            WHEN 'groudon-primal' THEN 9998
            ELSE dexNumber
        END
        WHERE pokemonId IN ('kyogre-primal', 'groudon-primal')
    """)
    suspend fun migratePrimalDexNumbers()

    /** Rows that have a card assigned but are missing the card's name (pre-v6 assignments). */
    @Query("SELECT * FROM main_binder WHERE assignedCardId IS NOT NULL AND assignedCardName IS NULL")
    suspend fun getRowsNeedingCardBackfill(): List<MainBinderEntry>

    /**
     * Writes the resolved name/set onto a single row, leaving id/image untouched.
     * Guarded by [expectedCardId] and `assignedCardName IS NULL` so a concurrent
     * reassignment mid-backfill pass cannot desync the name from the id (no-op if stale).
     */
    @Query("""
        UPDATE main_binder SET assignedCardName = :cardName, assignedCardSetName = :cardSetName
        WHERE pokemonId = :pokemonId AND assignedCardId = :expectedCardId AND assignedCardName IS NULL
    """)
    suspend fun backfillCardNameSet(pokemonId: String, expectedCardId: String, cardName: String, cardSetName: String)

    @Query("""
        UPDATE main_binder SET language = :language, remarks = :remarks, isLocked = :isLocked
        WHERE pokemonId = :pokemonId
    """)
    suspend fun updateDetails(pokemonId: String, language: String, remarks: String?, isLocked: Boolean)
}
