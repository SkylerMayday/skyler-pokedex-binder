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

        // Only one candidate — nothing to disambiguate, so it's automatically the best guess.
        if (cards.size == 1) return SearchConfidence(cards, true, cards.first())

        // Normalize "025" → "25" so OCR leading zeros don't break matching
        val normNumber = parsedNumber?.trimStart('0')?.ifEmpty { "0" }
        val numberMatch = normNumber?.let { n ->
            cards.firstOrNull { it.number.trimStart('0').ifEmpty { "0" } == n }
        }

        // Multiple same-name candidates are only high confidence when the parsed card number
        // actually pins down which printing it is. Without that, auto-picking cards.first()
        // risks silently assigning the wrong card — surface the full list instead.
        return if (numberMatch != null) {
            SearchConfidence(cards, true, numberMatch)
        } else {
            SearchConfidence(cards, false, cards.first())
        }
    }
}
