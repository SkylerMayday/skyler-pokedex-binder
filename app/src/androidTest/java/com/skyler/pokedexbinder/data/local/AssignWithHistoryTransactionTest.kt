package com.skyler.pokedexbinder.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.BinderRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real-SQL proof that [BinderRepository.assignWithHistory] commits or rolls back as one unit. */
@RunWith(AndroidJUnit4::class)
class AssignWithHistoryTransactionTest {

    private lateinit var db: PokedexDatabase
    private lateinit var mainDao: MainBinderDao
    private lateinit var secondaryDao: SecondaryBinderDao
    private lateinit var repo: BinderRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PokedexDatabase::class.java
        ).allowMainThreadQueries().build()
        mainDao = db.mainBinderDao()
        secondaryDao = db.secondaryBinderDao()
        repo = BinderRepository(mainDao)
    }

    @After
    fun tearDown() { db.close() }

    private fun card(id: String) = TcgCard(
        id = id, name = "Charizard", number = "1", setName = "XY",
        imageUrl = "https://img.url/$id", pokemonNames = listOf("Charizard")
    )

    private suspend fun seedSlot(cardId: String? = null, locked: Boolean = false) = mainDao.upsert(
        MainBinderEntry(
            pokemonId = "charizard", pokemonName = "Charizard", dexNumber = 6, dexOrder = 6,
            assignedCardId = cardId, assignedCardImageUrl = cardId?.let { "https://img.url/$it" },
            isLocked = locked
        )
    )

    private suspend fun seedHistory(vararg cardIds: String) = cardIds.forEach {
        secondaryDao.insertAtEnd(
            SecondaryBinderEntry(pokemonId = "x", pokemonName = "X", cardId = it, cardImageUrl = "https://img.url/$it")
        )
    }

    @Test
    fun occupiedSlotMovesOldCardToHistoryAtEndAndAssigns() = runTest {
        seedSlot("A")
        seedHistory("h0", "h1")

        assertTrue(repo.assignWithHistory("charizard", card("B")))

        assertEquals("B", mainDao.getByPokemonId("charizard")?.assignedCardId)
        val history = secondaryDao.getAll()
        assertEquals(3, history.size)
        val moved = history.single { it.cardId == "A" }
        assertEquals(2, moved.position)
        assertEquals("charizard", moved.pokemonId)
    }

    @Test
    fun emptySlotAssignsWithoutHistory() = runTest {
        seedSlot()

        assertTrue(repo.assignWithHistory("charizard", card("B")))

        assertEquals("B", mainDao.getByPokemonId("charizard")?.assignedCardId)
        assertTrue(secondaryDao.getAll().isEmpty())
    }

    @Test
    fun lockedAndMissingSlotsAreNoOps() = runTest {
        seedSlot("A", locked = true)

        assertFalse(repo.assignWithHistory("charizard", card("B")))
        assertFalse(repo.assignWithHistory("missingno", card("B")))

        assertEquals("A", mainDao.getByPokemonId("charizard")?.assignedCardId)
        assertNull(mainDao.getByPokemonId("missingno"))
        assertTrue(secondaryDao.getAll().isEmpty())
    }

    @Test
    fun failedUpsertRollsBackHistoryInsert() = runTest {
        seedSlot("A")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_main_update BEFORE UPDATE ON main_binder " +
                "BEGIN SELECT RAISE(ABORT, 'forced'); END"
        )

        try {
            repo.assignWithHistory("charizard", card("B"))
            fail("expected the forced trigger failure")
        } catch (e: Exception) {
            // expected: the slot write aborts after the history insert
        }

        assertTrue("history insert must be rolled back", secondaryDao.getAll().isEmpty())
        assertEquals("A", mainDao.getByPokemonId("charizard")?.assignedCardId)
    }
}
