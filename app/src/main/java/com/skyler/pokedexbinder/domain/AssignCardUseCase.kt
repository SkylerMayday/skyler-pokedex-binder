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
    /** Returns false if the target slot is locked (assignment blocked); true if it proceeded. */
    suspend fun assign(pokemonId: String, card: TcgCard): Boolean {
        val slot = binderRepository.getSlotByPokemonId(pokemonId) ?: return false
        if (slot.isLocked) return false

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

        return binderRepository.assignCard(pokemonId, card.id, card.imageUrl, card.name, card.setName)
    }
}
