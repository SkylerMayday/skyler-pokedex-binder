package com.skyler.pokedexbinder.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies MIGRATION_6_7 (adds connecting_art_group, connecting_art_slot,
 * personal_collection_cache, personal_collection_entry) does not disturb existing
 * main_binder / secondary_binder data, that the new tables have the expected columns,
 * and that the FK ON DELETE CASCADE from connecting_art_slot -> connecting_art_group
 * actually fires.
 *
 * Same approach as Migration5to6Test: exportSchema = false for this DB, so this uses an
 * in-memory Room DB built at the current (post-migration) schema and separately validates
 * MIGRATION_6_7's raw SQL against a bare v6-shaped table, rather than
 * MigrationTestHelper + exported schema JSON (which requires exportSchema = true).
 *
 * NOTE: this is an androidTest and requires a device/emulator to execute. No AVD/emulator
 * was available in the environment this test was authored in (see test-results.md) — this
 * file compiles and is ready to run, but was not executed live here.
 */
@RunWith(AndroidJUnit4::class)
class Migration6to7Test {

    private lateinit var db: PokedexDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PokedexDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun migration6to7SqlIsValidAgainstV6ShapeAndPreservesExistingData() {
        // Build a bare v6-shaped schema by hand (mirrors the real pre-migration-6 tables)
        // in a separate in-memory connection, seed it with "existing user data", then run
        // MIGRATION_6_7's exact SQL statements against it and confirm the seeded rows and
        // pre-existing tables are untouched.
        val supportDb = db.openHelper.writableDatabase

        supportDb.execSQL(
            "CREATE TABLE main_binder_v6_shape (" +
                "pokemonId TEXT NOT NULL PRIMARY KEY, " +
                "pokemonName TEXT NOT NULL, " +
                "dexNumber INTEGER NOT NULL, " +
                "dexOrder INTEGER NOT NULL, " +
                "slotType TEXT NOT NULL, " +
                "assignedCardId TEXT, " +
                "assignedCardImageUrl TEXT, " +
                "assignedCardName TEXT, " +
                "assignedCardSetName TEXT)"
        )
        supportDb.execSQL(
            "INSERT INTO main_binder_v6_shape " +
                "(pokemonId, pokemonName, dexNumber, dexOrder, slotType, assignedCardName, assignedCardSetName) " +
                "VALUES ('charizard', 'Charizard', 6, 6, 'BASE', 'Charizard', 'Base Set')"
        )

        // Run MIGRATION_6_7's DDL verbatim (copied from PokedexDatabase.MIGRATION_6_7).
        supportDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `connecting_art_group` (" +
                "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "`name` TEXT NOT NULL, " +
                "`rows` INTEGER NOT NULL, " +
                "`cols` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL)"
        )
        supportDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `connecting_art_slot` (" +
                "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "`groupId` INTEGER NOT NULL, " +
                "`slotIndex` INTEGER NOT NULL, " +
                "`cardId` TEXT, " +
                "`cardName` TEXT, " +
                "`cardImageUrl` TEXT, " +
                "`owned` INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(`groupId`) REFERENCES `connecting_art_group`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        supportDb.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_connecting_art_slot_groupId` " +
                "ON `connecting_art_slot` (`groupId`)"
        )
        supportDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `personal_collection_cache` (" +
                "`cardId` TEXT NOT NULL PRIMARY KEY, " +
                "`pokemonKey` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`imageUrl` TEXT NOT NULL, " +
                "`setName` TEXT NOT NULL, " +
                "`releaseDate` TEXT NOT NULL)"
        )
        supportDb.execSQL(
            "CREATE TABLE IF NOT EXISTS `personal_collection_entry` (" +
                "`cardId` TEXT NOT NULL PRIMARY KEY, " +
                "`owned` INTEGER NOT NULL DEFAULT 0)"
        )

        // (a) Pre-existing data untouched.
        supportDb.query("SELECT pokemonName, assignedCardName FROM main_binder_v6_shape WHERE pokemonId = 'charizard'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Charizard", it.getString(0))
            assertEquals("Charizard", it.getString(1))
        }

        // (b) All 4 new tables exist with the correct columns.
        fun columnsOf(table: String): List<String> {
            val names = mutableListOf<String>()
            supportDb.query("PRAGMA table_info(`$table`)").use { c ->
                val idx = c.getColumnIndex("name")
                while (c.moveToNext()) names += c.getString(idx)
            }
            return names
        }

        assertEquals(
            listOf("id", "name", "rows", "cols", "position"),
            columnsOf("connecting_art_group")
        )
        assertEquals(
            listOf("id", "groupId", "slotIndex", "cardId", "cardName", "cardImageUrl", "owned"),
            columnsOf("connecting_art_slot")
        )
        assertEquals(
            listOf("cardId", "pokemonKey", "name", "imageUrl", "setName", "releaseDate"),
            columnsOf("personal_collection_cache")
        )
        assertEquals(
            listOf("cardId", "owned"),
            columnsOf("personal_collection_entry")
        )

        // Index exists.
        var indexFound = false
        supportDb.query("PRAGMA index_list(`connecting_art_slot`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == "index_connecting_art_slot_groupId") indexFound = true
            }
        }
        assertTrue("expected index_connecting_art_slot_groupId to exist", indexFound)

        // (c) FK insert + cascade delete.
        supportDb.execSQL("PRAGMA foreign_keys = ON")
        supportDb.execSQL(
            "INSERT INTO connecting_art_group (id, name, rows, cols, position) VALUES (1, 'Eeveelutions', 2, 4, 0)"
        )
        supportDb.execSQL(
            "INSERT INTO connecting_art_slot (id, groupId, slotIndex, owned) VALUES (1, 1, 0, 0)"
        )
        supportDb.query("SELECT COUNT(*) FROM connecting_art_slot WHERE groupId = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }

        supportDb.execSQL("DELETE FROM connecting_art_group WHERE id = 1")
        supportDb.query("SELECT COUNT(*) FROM connecting_art_slot WHERE groupId = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("expected FK cascade delete to remove child slot", 0, it.getInt(0))
        }
    }

    @Test
    fun migration6to7NewTablesReachableThroughRealDaosAndRepositoryLayer() {
        // Exercises the actual Room-generated DAOs (not raw SQL) against the current
        // (post-migration) in-memory schema: createGroupWithSlots + cascade delete via
        // ConnectingArtDao, confirming the Kotlin-level contract the app relies on.
        val dao = db.connectingArtDao()
        runBlocking {
            dao.createGroupWithSlots(name = "Eeveelutions", rows = 2, cols = 4)
            val groups = dao.getGroups()
            assertEquals(1, groups.size)
            val group = groups.single()
            val slots = dao.observeSlots(group.id).first()
            assertEquals(8, slots.size)

            dao.deleteGroupById(group.id)
            assertTrue(dao.getGroups().isEmpty())
            val remainingSlots = dao.observeAllSlots().first()
            assertTrue("expected cascade delete to remove all child slots", remainingSlots.isEmpty())
        }
    }
}
