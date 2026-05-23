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
}
