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
    fun secondaryBinderOrdersByIdDesc() = runTest {
        secondaryDao.insert(SecondaryBinderEntry(pokemonId = "bulbasaur", pokemonName = "Bulbasaur", cardId = "card-1", cardImageUrl = "url1"))
        secondaryDao.insert(SecondaryBinderEntry(pokemonId = "bulbasaur", pokemonName = "Bulbasaur", cardId = "card-2", cardImageUrl = "url2"))
        secondaryDao.observeAll().test {
            val items = awaitItem()
            assertEquals(2, items.size)
            assertEquals("card-2", items[0].cardId) // newest first
            cancelAndIgnoreRemainingEvents()
        }
    }
}
