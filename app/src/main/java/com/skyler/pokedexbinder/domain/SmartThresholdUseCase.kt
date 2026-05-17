package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.model.TcgCard
import javax.inject.Inject

data class SearchConfidence(
    val cards: List<TcgCard>,
    val isHighConfidence: Boolean,
    val topCard: TcgCard?
)

class SmartThresholdUseCase @Inject constructor() {

    fun evaluate(cards: List<TcgCard>, parsedName: String, parsedNumber: String?): SearchConfidence {
        if (cards.isEmpty()) return SearchConfidence(cards, false, null)
        if (cards.size == 1) return SearchConfidence(cards, true, cards.first())

        // Find card matching both name and number exactly
        val exactMatch = cards.firstOrNull { card ->
            card.name.equals(parsedName, ignoreCase = true) &&
                parsedNumber != null && card.number == parsedNumber
        }

        return if (exactMatch != null) {
            SearchConfidence(cards, true, exactMatch)
        } else {
            SearchConfidence(cards, false, cards.first())
        }
    }
}
