package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.model.TcgCard
import javax.inject.Inject

enum class ConfidencePath { NUMBER_MATCH, HASH_MARGIN, SINGLE_CANDIDATE_HASH, NONE }

data class SearchConfidence(
    val cards: List<TcgCard>,
    val isHighConfidence: Boolean,
    val topCard: TcgCard?,
    val matchPath: ConfidencePath
)

class SmartThresholdUseCase @Inject constructor() {
    companion object {
        // First-guess, not calibrated beyond the 16-card sample in docs/specs/2026-09-13-
        // scanner-hash-confidence.md. Margin = runner-up distance minus best, out of 64 bits.
        // Not a blocker to ship — re-tune once Skyler runs real same-species multi-candidate
        // scans (same convention as GUIDE_FRAME_WIDTH_RATIO's history).
        const val HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD = 12

        // First-guess, calibrated against exactly one real data point (Furfrou/Zorua misread).
        // Absolute Hamming-distance ceiling for the single-candidate case (no runner-up to build
        // a relative margin against). Not a blocker to ship — re-tune on real device.
        const val HASH_SINGLE_CANDIDATE_MAX_DISTANCE = 16
    }

    fun evaluate(
        cards: List<TcgCard>,
        parsedName: String,
        parsedNumber: String?,
        hashMatch: HashMatchResult? = null
    ): SearchConfidence {
        if (cards.isEmpty()) return SearchConfidence(cards, false, null, ConfidencePath.NONE)

        if (cards.size == 1) {
            val single = cards.first()
            // No runner-up to build a margin against — absolute distance ceiling instead.
            // hashMatch == null (caller skipped hashing) stays conservative (reject).
            val visuallyConfirmed = hashMatch != null && hashMatch.card.id == single.id &&
                hashMatch.distance <= HASH_SINGLE_CANDIDATE_MAX_DISTANCE
            return if (visuallyConfirmed) {
                SearchConfidence(cards, true, single, ConfidencePath.SINGLE_CANDIDATE_HASH)
            } else {
                SearchConfidence(cards, false, single, ConfidencePath.NONE)
            }
        }

        val normNumber = parsedNumber?.trimStart('0')?.ifEmpty { "0" }
        val numberMatch = normNumber?.let { n ->
            cards.firstOrNull { it.number.trimStart('0').ifEmpty { "0" } == n }
        }
        if (numberMatch != null) {
            return SearchConfidence(cards, true, numberMatch, ConfidencePath.NUMBER_MATCH)
        }

        // Deliberately gated on parsedNumber == null, not "numberMatch == null" — a number that
        // was read but matched nothing is a stronger negative signal than nothing read at all,
        // and must NOT fall through to the hash-margin path.
        // hashMatch.distance == Int.MAX_VALUE means the *winning* candidate's own image download
        // failed (PerceptualHasher.findBestMatch()'s documented fallback) — the margin is an
        // artifact of a missing comparison, not a strong visual match, even though the raw
        // arithmetic can clear the threshold.
        if (parsedNumber == null && hashMatch != null &&
            hashMatch.distance != Int.MAX_VALUE &&
            hashMatch.margin >= HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD
        ) {
            return SearchConfidence(cards, true, hashMatch.card, ConfidencePath.HASH_MARGIN)
        }

        return SearchConfidence(cards, false, cards.first(), ConfidencePath.NONE)
    }
}
