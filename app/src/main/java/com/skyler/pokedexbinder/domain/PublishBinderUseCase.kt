package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.publish.PublishRepository
import com.skyler.pokedexbinder.publish.PublishResult
import com.skyler.pokedexbinder.publish.PublishStep
import javax.inject.Inject

class PublishBinderUseCase @Inject constructor(
    private val publishRepository: PublishRepository
) {
    suspend fun publish(onStep: (PublishStep) -> Unit): PublishResult =
        publishRepository.publish(onStep)
}
