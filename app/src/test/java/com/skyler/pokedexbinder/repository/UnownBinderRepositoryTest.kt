package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.UnownBinderDao
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.model.Language
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UnownBinderRepositoryTest {

    private val unownDao = mockk<UnownBinderDao>(relaxed = true)

    @Test
    fun `assignCard upserts entry with card info and returns true when unlocked`() = runTest {
        val existing = UnownBinderEntry(letterId = "A", position = 0)
        coEvery { unownDao.getByLetterId("A") } returns existing
        val repo = UnownBinderRepository(unownDao)

        val result = repo.assignCard("A", "unown-1", "https://img.pokemontcg.io/unown/1.png", "Unown", "Neo Discovery")

        assertTrue(result)
        coVerify {
            unownDao.upsert(
                existing.copy(
                    assignedCardId = "unown-1",
                    assignedCardImageUrl = "https://img.pokemontcg.io/unown/1.png",
                    assignedCardName = "Unown",
                    assignedCardSetName = "Neo Discovery"
                )
            )
        }
    }

    @Test
    fun `assignCard no-ops and returns false when slot is locked`() = runTest {
        val existing = UnownBinderEntry(letterId = "A", position = 0, isLocked = true)
        coEvery { unownDao.getByLetterId("A") } returns existing
        val repo = UnownBinderRepository(unownDao)

        val result = repo.assignCard("A", "unown-1", "https://img.pokemontcg.io/unown/1.png", "Unown", "Neo Discovery")

        assertFalse(result)
        coVerify(exactly = 0) { unownDao.upsert(any()) }
    }

    @Test
    fun `clearCard clears assignment and returns true when unlocked`() = runTest {
        val existing = UnownBinderEntry(
            letterId = "A", position = 0,
            assignedCardId = "unown-1", assignedCardImageUrl = "https://img.pokemontcg.io/unown/1.png"
        )
        coEvery { unownDao.getByLetterId("A") } returns existing
        val repo = UnownBinderRepository(unownDao)

        val result = repo.clearCard("A")

        assertTrue(result)
        coVerify {
            unownDao.upsert(
                existing.copy(
                    assignedCardId = null,
                    assignedCardImageUrl = null,
                    assignedCardName = null,
                    assignedCardSetName = null
                )
            )
        }
    }

    @Test
    fun `clearCard no-ops and returns false when slot is locked`() = runTest {
        val existing = UnownBinderEntry(letterId = "A", position = 0, assignedCardId = "unown-1", isLocked = true)
        coEvery { unownDao.getByLetterId("A") } returns existing
        val repo = UnownBinderRepository(unownDao)

        val result = repo.clearCard("A")

        assertFalse(result)
        coVerify(exactly = 0) { unownDao.upsert(any()) }
    }

    @Test
    fun `updateDetails delegates to dao with raw language name`() = runTest {
        val repo = UnownBinderRepository(unownDao)

        repo.updateDetails("A", Language.KO, "Signed copy", true)

        coVerify { unownDao.updateDetails("A", "KO", "Signed copy", true) }
    }
}
