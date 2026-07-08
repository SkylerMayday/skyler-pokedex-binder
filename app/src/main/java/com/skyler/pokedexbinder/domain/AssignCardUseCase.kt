package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.BinderRepository
import javax.inject.Inject

class AssignCardUseCase @Inject constructor(
    private val binderRepository: BinderRepository,
    private val secondaryBinderDao: SecondaryBinderDao
) {
    suspend fun assign(pokemonId: String, card: TcgCard) {
        val slot = binderRepository.getSlotByPokemonId(pokemonId) ?: return

        if (slot.isOccupied) {
            secondaryBinderDao.insertAtEnd(
                SecondaryBinderEntry(
                    pokemonId = slot.id,
                    pokemonName = slot.name,
                    cardId = slot.assignedCardId!!,
                    cardImageUrl = slot.assignedCardImageUrl!!
                )
            )
        }

        binderRepository.assignCard(pokemonId, card.id, card.imageUrl, card.name, card.setName)
    }
}
