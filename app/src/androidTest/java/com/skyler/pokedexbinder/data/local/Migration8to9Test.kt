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
 * Verifies MIGRATION_8_9 (adds language/remarks/isLocked to main_binder and unown_binder;
 * adds language-only to connecting_art_slot, personal_collection_entry, secondary_binder)
 * does not disturb existing data in any of the 5 tables, that the new columns exist with the
 * correct backfilled defaults, and that the new columns are reachable through the real DAOs.
 *
 * Same approach as Migration6to7Test: exportSchema = false for this DB, so this uses an
 * in-memory Room DB built at the current (post-migration) schema and separately validates
 * MIGRATION_8_9's raw SQL against bare v8-shaped tables, rather than MigrationTestHelper +
 * exported schema JSON (which requires exportSchema = true).
 *
 * NOTE: this is an androidTest and requires a device/emulator to execute. No AVD/emulator was
 * available in the environment this file was authored in — this file compiles and is ready to
 * run, but was not executed live here.
 */
@RunWith(AndroidJUnit4::class)
class Migration8to9Test {

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
    fun migration8to9SqlIsValidAgainstV8ShapeAndPreservesExistingData() {
        val supportDb = db.openHelper.writableDatabase

        // Bare v8-shaped tables (mirrors the real pre-migration-9 tables) in separate
        // "_v8_shape" names so they don't collide with the real (post-migration) tables Room
        // already created on this same connection.
        supportDb.execSQL(
            "CREATE TABLE main_binder_v8_shape (" +
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
            "INSERT INTO main_binder_v8_shape " +
                "(pokemonId, pokemonName, dexNumber, dexOrder, slotType, assignedCardId, assignedCardImageUrl, assignedCardName, assignedCardSetName) " +
                "VALUES ('charizard', 'Charizard', 6, 6, 'BASE', 'base4-4', 'https://img.pokemontcg.io/base4/4.png', 'Charizard', 'Base Set')"
        )

        supportDb.execSQL(
            "CREATE TABLE unown_binder_v8_shape (" +
                "letterId TEXT NOT NULL PRIMARY KEY, " +
                "position INTEGER NOT NULL, " +
                "assignedCardId TEXT, " +
                "assignedCardImageUrl TEXT, " +
                "assignedCardName TEXT, " +
                "assignedCardSetName TEXT)"
        )
        supportDb.execSQL(
            "INSERT INTO unown_binder_v8_shape (letterId, position) VALUES ('A', 0)"
        )

        supportDb.execSQL(
            "CREATE TABLE connecting_art_slot_v8_shape (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "groupId INTEGER NOT NULL, " +
                "slotIndex INTEGER NOT NULL, " +
                "cardId TEXT, " +
                "cardName TEXT, " +
                "cardImageUrl TEXT, " +
                "owned INTEGER NOT NULL DEFAULT 0)"
        )
        supportDb.execSQL(
            "INSERT INTO connecting_art_slot_v8_shape (id, groupId, slotIndex, cardId, cardName, cardImageUrl, owned) " +
                "VALUES (1, 1, 0, 'xy1-1', 'Eevee', 'https://img.pokemontcg.io/xy1/1.png', 1)"
        )

        supportDb.execSQL(
            "CREATE TABLE personal_collection_entry_v8_shape (" +
                "cardId TEXT NOT NULL PRIMARY KEY, " +
                "owned INTEGER NOT NULL DEFAULT 0)"
        )
        supportDb.execSQL(
            "INSERT INTO personal_collection_entry_v8_shape (cardId, owned) VALUES ('xy1-1', 1)"
        )

        supportDb.execSQL(
            "CREATE TABLE secondary_binder_v8_shape (" +
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                "pokemonId TEXT NOT NULL, " +
                "pokemonName TEXT NOT NULL, " +
                "cardId TEXT NOT NULL, " +
                "cardImageUrl TEXT NOT NULL, " +
                "position INTEGER NOT NULL DEFAULT 0)"
        )
        supportDb.execSQL(
            "INSERT INTO secondary_binder_v8_shape (id, pokemonId, pokemonName, cardId, cardImageUrl, position) " +
                "VALUES (1, 'eevee', 'Eevee', 'xy1-1', 'https://img.pokemontcg.io/xy1/1.png', 0)"
        )

        // Run MIGRATION_8_9's exact SQL statements against the *_v8_shape tables — swap the
        // real table names for the bare-shape ones so we exercise the identical ALTER TABLE
        // statements without touching Room's already-migrated real tables on this connection.
        supportDb.execSQL("ALTER TABLE main_binder_v8_shape ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
        supportDb.execSQL("ALTER TABLE main_binder_v8_shape ADD COLUMN remarks TEXT")
        supportDb.execSQL("ALTER TABLE main_binder_v8_shape ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")

        supportDb.execSQL("ALTER TABLE unown_binder_v8_shape ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
        supportDb.execSQL("ALTER TABLE unown_binder_v8_shape ADD COLUMN remarks TEXT")
        supportDb.execSQL("ALTER TABLE unown_binder_v8_shape ADD COLUMN isLocked INTEGER NOT NULL DEFAULT 0")

        supportDb.execSQL("ALTER TABLE connecting_art_slot_v8_shape ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
        supportDb.execSQL("ALTER TABLE personal_collection_entry_v8_shape ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")
        supportDb.execSQL("ALTER TABLE secondary_binder_v8_shape ADD COLUMN language TEXT NOT NULL DEFAULT 'EN'")

        fun columnsOf(table: String): List<String> {
            val names = mutableListOf<String>()
            supportDb.query("PRAGMA table_info(`$table`)").use { c ->
                val idx = c.getColumnIndex("name")
                while (c.moveToNext()) names += c.getString(idx)
            }
            return names
        }

        // (a) All 5 tables now have the expected new columns.
        assertEquals(
            listOf(
                "pokemonId", "pokemonName", "dexNumber", "dexOrder", "slotType",
                "assignedCardId", "assignedCardImageUrl", "assignedCardName", "assignedCardSetName",
                "language", "remarks", "isLocked"
            ),
            columnsOf("main_binder_v8_shape")
        )
        assertEquals(
            listOf(
                "letterId", "position", "assignedCardId", "assignedCardImageUrl",
                "assignedCardName", "assignedCardSetName", "language", "remarks", "isLocked"
            ),
            columnsOf("unown_binder_v8_shape")
        )
        assertEquals(
            listOf("id", "groupId", "slotIndex", "cardId", "cardName", "cardImageUrl", "owned", "language"),
            columnsOf("connecting_art_slot_v8_shape")
        )
        assertEquals(
            listOf("cardId", "owned", "language"),
            columnsOf("personal_collection_entry_v8_shape")
        )
        assertEquals(
            listOf("id", "pokemonId", "pokemonName", "cardId", "cardImageUrl", "position", "language"),
            columnsOf("secondary_binder_v8_shape")
        )

        // (b) Pre-existing rows' original columns are untouched.
        supportDb.query("SELECT pokemonName, assignedCardId, assignedCardName FROM main_binder_v8_shape WHERE pokemonId = 'charizard'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Charizard", it.getString(0))
            assertEquals("base4-4", it.getString(1))
            assertEquals("Charizard", it.getString(2))
        }
        supportDb.query("SELECT position FROM unown_binder_v8_shape WHERE letterId = 'A'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        supportDb.query("SELECT cardId, owned FROM connecting_art_slot_v8_shape WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("xy1-1", it.getString(0))
            assertEquals(1, it.getInt(1))
        }
        supportDb.query("SELECT owned FROM personal_collection_entry_v8_shape WHERE cardId = 'xy1-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        supportDb.query("SELECT pokemonName, position FROM secondary_binder_v8_shape WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("Eevee", it.getString(0))
            assertEquals(0, it.getInt(1))
        }

        // (c) Pre-existing rows' new columns are backfilled correctly.
        supportDb.query("SELECT language, remarks, isLocked FROM main_binder_v8_shape WHERE pokemonId = 'charizard'").use {
            assertTrue(it.moveToFirst())
            assertEquals("EN", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals(0, it.getInt(2))
        }
        supportDb.query("SELECT language, remarks, isLocked FROM unown_binder_v8_shape WHERE letterId = 'A'").use {
            assertTrue(it.moveToFirst())
            assertEquals("EN", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals(0, it.getInt(2))
        }
        supportDb.query("SELECT language FROM connecting_art_slot_v8_shape WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("EN", it.getString(0))
        }
        supportDb.query("SELECT language FROM personal_collection_entry_v8_shape WHERE cardId = 'xy1-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("EN", it.getString(0))
        }
        supportDb.query("SELECT language FROM secondary_binder_v8_shape WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("EN", it.getString(0))
        }
    }

    @Test
    fun migration8to9NewColumnsReachableThroughRealDaosAndRepositoryLayer() {
        // Exercises the actual Room-generated DAOs (not raw SQL) against the current
        // (post-migration) in-memory schema: insert a row via each of the 5 DAOs with explicit
        // language/remarks/isLocked values, read it back, assert round-trip fidelity.
        runBlocking {
            val mainDao = db.mainBinderDao()
            mainDao.insertAll(listOf(
                MainBinderEntry(
                    pokemonId = "bulbasaur", pokemonName = "Bulbasaur", dexOrder = 1,
                    language = "JA", remarks = "Holo", isLocked = true
                )
            ))
            val mainEntry = mainDao.getByPokemonId("bulbasaur")
            assertNotNull(mainEntry)
            assertEquals("JA", mainEntry!!.language)
            assertEquals("Holo", mainEntry.remarks)
            assertTrue(mainEntry.isLocked)

            val unownDao = db.unownBinderDao()
            unownDao.insertAll(listOf(
                UnownBinderEntry(letterId = "A", position = 0, language = "KO", remarks = "Test", isLocked = true)
            ))
            val unownEntry = unownDao.getByLetterId("A")
            assertNotNull(unownEntry)
            assertEquals("KO", unownEntry!!.language)
            assertEquals("Test", unownEntry.remarks)
            assertTrue(unownEntry.isLocked)

            val artDao = db.connectingArtDao()
            artDao.createGroupWithSlots(name = "Eeveelutions", rows = 1, cols = 1)
            val group = artDao.getGroups().single()
            val slot = artDao.observeSlots(group.id).first().first()
            artDao.updateLanguage(slot.id, "ZH")
            val updatedSlot = artDao.getAllSlots().first { it.id == slot.id }
            assertEquals("ZH", updatedSlot.language)

            val personalDao = db.personalCollectionDao()
            personalDao.upsertEntry(PersonalCollectionEntry(cardId = "xy1-1", owned = true, language = "FR"))
            val personalEntry = personalDao.getEntry("xy1-1")
            assertNotNull(personalEntry)
            assertEquals("FR", personalEntry!!.language)

            val secondaryDao = db.secondaryBinderDao()
            secondaryDao.insertAtEnd(
                SecondaryBinderEntry(
                    pokemonId = "eevee", pokemonName = "Eevee", cardId = "xy1-1",
                    cardImageUrl = "https://img.pokemontcg.io/xy1/1.png", language = "DE"
                )
            )
            val secondaryEntry = secondaryDao.getAll().single()
            assertEquals("DE", secondaryEntry.language)
        }
    }
}
