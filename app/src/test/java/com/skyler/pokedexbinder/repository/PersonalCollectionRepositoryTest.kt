package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.PersonalCollectionCache
import com.skyler.pokedexbinder.data.local.PersonalCollectionDao
import com.skyler.pokedexbinder.data.local.PersonalCollectionEntry
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.data.model.TcgCard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    @Test
    fun `refreshPokemon pulls from searchByNameAllLanguages and dedupes by card id across names`() = runTest {
        val en = TcgCard("tcgdex_sv1-1", "Pikachu", "1", "Scarlet & Violet", "img", listOf("Pikachu"))
        val ja = TcgCard("tcgdex_ja_M6-1", "Pikachu", "1", "M6 (JA)", "", listOf("Pikachu"))
        coEvery { cardSearchRepository.searchByNameAllLanguages("Pikachu") } returns NameSearchResult(listOf(en, ja), complete = true)
        coEvery { cardSearchRepository.searchByNameAllLanguages("Raichu") } returns NameSearchResult(listOf(en), complete = true)
        val repo = PersonalCollectionRepository(dao, cardSearchRepository)

        repo.refreshPokemon("pikachu", listOf("Pikachu", "Raichu"))

        coVerify {
            dao.replaceCacheForPokemon(
                "pikachu",
                match { rows -> rows.map { it.cardId } == listOf("tcgdex_sv1-1", "tcgdex_ja_M6-1") }
            )
        }
    }

    @Test
    fun `refreshPokemon with an empty result keeps an already cached section`() = runTest {
        coEvery { cardSearchRepository.searchByNameAllLanguages("Pikachu") } returns NameSearchResult(emptyList(), complete = true)
        coEvery { dao.cacheCountForPokemon("pikachu") } returns 3
        val repo = PersonalCollectionRepository(dao, cardSearchRepository)

        repo.refreshPokemon("pikachu", listOf("Pikachu"))

        coVerify(exactly = 0) { dao.replaceCacheForPokemon(any(), any()) }
    }

    @Test
    fun `refreshPokemon with an empty result and no cache still writes the empty section`() = runTest {
        coEvery { cardSearchRepository.searchByNameAllLanguages("Pikachu") } returns NameSearchResult(emptyList(), complete = true)
        coEvery { dao.cacheCountForPokemon("pikachu") } returns 0
        val repo = PersonalCollectionRepository(dao, cardSearchRepository)

        repo.refreshPokemon("pikachu", listOf("Pikachu"))

        coVerify { dao.replaceCacheForPokemon("pikachu", emptyList()) }
    }

    @Test
    fun `refreshPokemon with a partial result upserts instead of dropping the cached rows of the failed call`() = runTest {
        val en = TcgCard("tcgdex_sv1-1", "Pikachu", "1", "Scarlet & Violet", "img", listOf("Pikachu"))
        coEvery { cardSearchRepository.searchByNameAllLanguages("Pikachu") } returns
            NameSearchResult(listOf(en), complete = false)
        val repo = PersonalCollectionRepository(dao, cardSearchRepository)

        repo.refreshPokemon("pikachu", listOf("Pikachu"))

        coVerify(exactly = 0) { dao.replaceCacheForPokemon(any(), any()) }
        coVerify { dao.upsertCache(match { rows -> rows.map { it.cardId } == listOf("tcgdex_sv1-1") }) }
    }

    @Test
    fun `a second identical refresh writes the same rows and never touches owned entries`() = runTest {
        val en = TcgCard("tcgdex_sv1-1", "Pikachu", "1", "Scarlet & Violet", "img", listOf("Pikachu"))
        val ja = TcgCard("tcgdex_ja_M6-1", "Pikachu", "1", "M6 (JA)", "", listOf("Pikachu"))
        coEvery { cardSearchRepository.searchByNameAllLanguages("Pikachu") } returns
            NameSearchResult(listOf(en, ja), complete = true)
        val writes = mutableListOf<List<String>>()
        coEvery { dao.replaceCacheForPokemon("pikachu", any()) } answers {
            writes += secondArg<List<PersonalCollectionCache>>().map { it.cardId }
        }
        val repo = PersonalCollectionRepository(dao, cardSearchRepository)

        repo.refreshPokemon("pikachu", listOf("Pikachu"))
        repo.refreshPokemon("pikachu", listOf("Pikachu"))

        assertEquals(2, writes.size)
        assertEquals(writes[0], writes[1])
        coVerify(exactly = 0) { dao.upsertEntry(any()) }
        coVerify(exactly = 0) { dao.deleteEntry(any()) }
        coVerify(exactly = 0) { dao.updateLanguage(any(), any()) }
    }

    @Test
    fun `setOwned keeps the language of an existing entry`() = runTest {
        coEvery { dao.getEntry("tcgdex_ja_M6-1") } returns
            PersonalCollectionEntry(cardId = "tcgdex_ja_M6-1", owned = false, language = "DE")
        val repo = PersonalCollectionRepository(dao, cardSearchRepository)

        repo.setOwned("tcgdex_ja_M6-1", true)

        coVerify { dao.upsertEntry(PersonalCollectionEntry("tcgdex_ja_M6-1", true, "DE")) }
    }

    @Test
    fun `setOwned on a new ja card infers JA from the card id`() = runTest {
        coEvery { dao.getEntry("tcgdex_ja_M6-1") } returns null
        val repo = PersonalCollectionRepository(dao, cardSearchRepository)

        repo.setOwned("tcgdex_ja_M6-1", true)

        coVerify { dao.upsertEntry(PersonalCollectionEntry("tcgdex_ja_M6-1", true, "JA")) }
    }
}
