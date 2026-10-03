package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.local.CollectionResetDao
import com.skyler.pokedexbinder.data.local.ResetSummary
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ResetCollectionUseCaseTest {

    private val dao = mockk<CollectionResetDao>()
    private val useCase = ResetCollectionUseCase(dao)

    @Test
    fun `summary and reset delegate to the single transactional dao`() = runTest {
        val summary = ResetSummary(1, 2, 3, 4, 5)
        coEvery { dao.summary() } returns summary
        coJustRun { dao.resetAll() }

        assertEquals(summary, useCase.summary())
        useCase.reset()

        coVerify(exactly = 1) { dao.resetAll() }
    }
}
