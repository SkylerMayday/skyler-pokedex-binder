package com.skyler.pokedexbinder.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.skyler.pokedexbinder.data.local.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PokedexDatabaseTest {

    private lateinit var db: PokedexDatabase
    private lateinit var mainDao: MainBinderDao
    private lateinit var secondaryDao: SecondaryBinderDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PokedexDatabase::class.java
        ).allowMainThreadQueries().build()
        mainDao = db.mainBinderDao()
        secondaryDao = db.secondaryBinderDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun insertAndObserveMainBinder() = runTest {
        val entry = MainBinderEntry("bulbasaur", "Bulbasaur", dexNumber = 1, dexOrder = 1)
        mainDao.upsert(entry)
        mainDao.observeAll().test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("bulbasaur", items[0].pokemonId)
            assertNull(items[0].assignedCardId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upsertUpdatesExistingEntry() = runTest {
        val entry = MainBinderEntry("bulbasaur", "Bulbasaur", dexNumber = 1, dexOrder = 1)
        mainDao.upsert(entry)
        mainDao.upsert(entry.copy(assignedCardId = "xy1-1", assignedCardImageUrl = "https://img.url"))
        val result = mainDao.getByPokemonId("bulbasaur")
        assertEquals("xy1-1", result?.assignedCardId)
    }

    @Test
    fun secondaryBinderOrdersByPositionThenInsertionOrder() = runTest {
        // Root cause of this test's previous failure: it called the raw insert() DAO method
        // directly (leaving position at the entity default, 0, for both rows) and asserted
        // "newest first" — a design SecondaryBinderDao never implements. The real, only
        // production caller (SecondaryBinderViewModel.addCard) always goes through
        // insertAtEnd(), which assigns each new row the next position — new cards are appended
        // to the end (oldest first), matching Card History's actual drag-to-reorder grid
        // (SecondaryBinderScreen.kt) where users manually place cards, not a recency feed.
        secondaryDao.insertAtEnd(SecondaryBinderEntry(pokemonId = "bulbasaur", pokemonName = "Bulbasaur", cardId = "card-1", cardImageUrl = "url1"))
        secondaryDao.insertAtEnd(SecondaryBinderEntry(pokemonId = "bulbasaur", pokemonName = "Bulbasaur", cardId = "card-2", cardImageUrl = "url2"))
        secondaryDao.observeAll().test {
            val items = awaitItem()
            assertEquals(2, items.size)
            assertEquals("card-1", items[0].cardId) // appended first -> lowest position -> first
            assertEquals("card-2", items[1].cardId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun resetAllClearsAssignmentsButKeepsSlotRowsAndGroups() = runTest {
        mainDao.upsert(
            MainBinderEntry(
                "bulbasaur", "Bulbasaur", dexNumber = 1, dexOrder = 1,
                assignedCardId = "tcgdex_sv08-048", assignedCardImageUrl = "u", assignedCardName = "n",
                assignedCardSetName = "s", language = "JA", remarks = "r", isLocked = true
            )
        )
        val artDao = db.connectingArtDao()
        artDao.createGroupWithSlots("Group", 1, 2)
        val slot = artDao.getAllSlots().first()
        artDao.assignCard(slot.id, "tcgdex_sv08-048", "n", "u")
        secondaryDao.insertAtEnd(SecondaryBinderEntry(pokemonId = "b", pokemonName = "B", cardId = "c", cardImageUrl = "u"))
        val pcDao = db.personalCollectionDao()
        pcDao.upsertCache(listOf(PersonalCollectionCache("c1", "charizard", "n", "u", "s", "")))
        pcDao.upsertEntry(PersonalCollectionEntry("c1", owned = true, language = "JA"))

        db.collectionResetDao().resetAll()

        val main = mainDao.getByPokemonId("bulbasaur")!!
        assertNull(main.assignedCardId)
        assertNull(main.assignedCardImageUrl)
        assertEquals("EN", main.language)
        assertNull(main.remarks)
        assertFalse(main.isLocked)
        assertEquals(2, artDao.getAllSlots().size)
        assertTrue(artDao.getAllSlots().all { it.cardId == null && !it.owned })
        assertEquals(1, artDao.getGroups().size)
        assertEquals(0, db.collectionResetDao().countCardHistory())
        assertEquals(0, pcDao.cacheCount())
        assertTrue(pcDao.getAllEntries().isEmpty())
    }

    @Test
    fun fillBlankImageOnlyTouchesBlankRows() = runTest {
        mainDao.upsert(MainBinderEntry("a", "A", dexOrder = 1, assignedCardId = "card-1", assignedCardImageUrl = null))
        mainDao.upsert(MainBinderEntry("b", "B", dexOrder = 2, assignedCardId = "card-1", assignedCardImageUrl = ""))
        mainDao.upsert(MainBinderEntry("c", "C", dexOrder = 3, assignedCardId = "card-1", assignedCardImageUrl = "keep"))
        mainDao.upsert(MainBinderEntry("d", "D", dexOrder = 4))

        assertEquals(listOf("card-1"), mainDao.blankImageCardIds())
        assertEquals(2, mainDao.fillBlankImage("card-1", "new"))

        assertEquals("new", mainDao.getByPokemonId("a")!!.assignedCardImageUrl)
        assertEquals("new", mainDao.getByPokemonId("b")!!.assignedCardImageUrl)
        assertEquals("keep", mainDao.getByPokemonId("c")!!.assignedCardImageUrl)
        assertNull(mainDao.getByPokemonId("d")!!.assignedCardImageUrl)
        assertTrue(mainDao.blankImageCardIds().isEmpty())
    }
}
