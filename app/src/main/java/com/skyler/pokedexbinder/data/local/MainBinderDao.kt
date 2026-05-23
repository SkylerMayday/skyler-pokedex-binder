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
        WHERE slotType = 'MEGA' AND pokemonName NOT LIKE 'Mega %'
    """)
    suspend fun migrateMegaNamesToPrefixFormat()

    /** Renames old "[Pokemon] (Gigantamax)" format to "[Pokemon] V-Max" for existing DB rows. */
    @Query("UPDATE main_binder SET pokemonName = SUBSTR(pokemonName, 1, INSTR(pokemonName, ' (Gigantamax)') - 1) || ' V-Max' WHERE slotType = 'GMAX' AND pokemonName LIKE '%(Gigantamax)%'")
    suspend fun migrateGmaxNamesToVMax()

    /** Removes entries that were pruned from the binder (duplicates, incorrect inclusions). */
    @Query("""
        DELETE FROM main_binder WHERE pokemonId IN (
            'raticate-totem-alola',
            'af_shellos_east', 'af_shellos_west',
            'af_zacian_crowned', 'af_zacian_hero',
            'af_zamazenta_crowned', 'af_zamazenta_hero',
            'af_basculegion_male', 'af_basculegion_female'
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
}
