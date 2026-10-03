package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.local.CollectionResetDao
import com.skyler.pokedexbinder.data.local.ResetSummary
import javax.inject.Inject

/** Thin seam over [CollectionResetDao] so the reset ViewModel is testable without Room. */
class ResetCollectionUseCase @Inject constructor(private val dao: CollectionResetDao) {
    suspend fun summary(): ResetSummary = dao.summary()
    suspend fun reset() = dao.resetAll()
}
