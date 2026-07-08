package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.publish.RestoreRepository
import com.skyler.pokedexbinder.publish.RestoreResult
import com.skyler.pokedexbinder.publish.RestoreStep
import javax.inject.Inject

class RestoreBinderUseCase @Inject constructor(
    private val restoreRepository: RestoreRepository
) {
    suspend fun restore(onStep: (RestoreStep) -> Unit): RestoreResult =
        restoreRepository.restore(onStep)
}
