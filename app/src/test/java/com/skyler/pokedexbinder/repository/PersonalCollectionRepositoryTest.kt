package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.PersonalCollectionDao
import com.skyler.pokedexbinder.data.local.PersonalCollectionEntry
import com.skyler.pokedexbinder.data.model.Language
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersonalCollectionRepositoryTest {

    private val dao = mockk<PersonalCollectionDao>(relaxed = true)
    private val cardSearchRepository = mockk<CardSearchRepository>(relaxed = true)

    @Test
    fun `updateLanguage creates an owned=false entry row first when none exists yet`() = runTest {
        coEvery { dao.getEntry("card-1") } returns null
        val repo = PersonalCollectionRepository(dao, cardSearchRepository)

        repo.updateLanguage("card-1", Language.JA)

        coVerify { dao.upsertEntry(PersonalCollectionEntry(cardId = "card-1", owned = false)) }
        coVerify { dao.updateLanguage("card-1", "JA") }
    }

    @Test
    fun `updateLanguage does not touch owned state when an entry row already exists`() = runTest {
        coEvery { dao.getEntry("card-1") } returns PersonalCollectionEntry(cardId = "card-1", owned = true, language = "EN")
        val repo = PersonalCollectionRepository(dao, cardSearchRepository)

        repo.updateLanguage("card-1", Language.DE)

        coVerify(exactly = 0) { dao.upsertEntry(any()) }
        coVerify { dao.updateLanguage("card-1", "DE") }
    }
}
