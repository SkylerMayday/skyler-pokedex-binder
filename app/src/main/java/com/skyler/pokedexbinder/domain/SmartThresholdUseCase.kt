package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.model.TcgCard
import javax.inject.Inject

enum class ConfidencePath { NUMBER_MATCH, HASH_MARGIN, SINGLE_CANDIDATE_HASH, IDENTITY_PINNED, IDENTITY_PINNED_NAME, CROSS_LANGUAGE, NONE }

data class SearchConfidence(
    val cards: List<TcgCard>,
    val isHighConfidence: Boolean,
    val topCard: TcgCard?,
    val matchPath: ConfidencePath
)

class SmartThresholdUseCase @Inject constructor() {
    companion object {
        // First-guess, not calibrated beyond the 16-card sample in docs/specs/2026-09-13-
        // scanner-hash-confidence.md. Margin = runner-up distance minus best, out of 63 bits.
        // Not a blocker to ship — re-tune once Skyler runs real same-species multi-candidate
        // scans (same convention as GUIDE_FRAME_WIDTH_RATIO's history).
        const val HASH_MARGIN_HIGH_CONFIDENCE_THRESHOLD = 12

        // Art-crop pHash (63 bits), 2026-10-01 device scans: confirmed-correct cards scored 8 and 18;
        // wrong / not-in-database picks scored 24-30. 20 sits in that gap. Only two correct
        // samples — re-tune from the `ranked=` log once more confirmed scans exist.
        // Absolute Hamming-distance ceiling for the single-candidate case (no runner-up to build
        // a relative margin against).
        const val HASH_SINGLE_CANDIDATE_MAX_DISTANCE = 20

        // Sanity ceiling for a single candidate pinned by metadata (identityPinned: exact dex +
        // number + total; nameVerified: exact number + total + printedName match). NOT a
        // discriminator: 2026-10-01 device scans scored correct cards 8, 18, 28 (Black Kyurem
        // SV7a-011) and 34 (Castform swsh9-116) while wrong / not-in-DB picks scored 24-30, so
        // the hash cannot separate right from wrong in the 20-45 band. Random unrelated art
        // averages ~31 of 63 bits (sd ~4), so 45 is ~3.4 sd out: it only catches a blank, black
        // or inverted crop, never a wrong card. The metadata is the evidence; if a wrong pinned
        // card is ever accepted, tighten this constant, not HASH_SINGLE_CANDIDATE_MAX_DISTANCE.
        // Int.MAX_VALUE (image download/decode failed) with a non-blank image stays rejected:
        // there is no visual evidence either way and none of the observed failures were failed
        // downloads. Widening later is a one-line change (accept `pinned` alone on MAX_VALUE).
        const val PINNED_HASH_CONTRADICTION_DISTANCE = 45
    }

    fun evaluate(
        cards: List<TcgCard>,
        parsedName: String,
        parsedNumber: String?,
        hashMatch: HashMatchResult? = null,
        identityPinned: Boolean = false,
        nameVerified: Boolean = false,
        // Cards are stand-in printings from another language than the physical card.
        crossLanguage: Boolean = false
    ): SearchConfidence {
        if (cards.isEmpty()) return SearchConfidence(cards, false, null, ConfidencePath.NONE)

        // Stand-in printing from another language than the physical card: never auto-accept by number, pin or hash.
        if (crossLanguage) return SearchConfidence(cards, false, cards.first(), ConfidencePath.CROSS_LANGUAGE)

        if (cards.size == 1) {
            val single = cards.first()
            // No runner-up to build a margin against — absolute distance ceiling instead.
            // hashMatch == null (caller skipped hashing) stays conservative (reject).
            val visuallyConfirmed = hashMatch != null && hashMatch.card.id == single.id &&
                hashMatch.distance <= HASH_SINGLE_CANDIDATE_MAX_DISTANCE
            // Metadata pinned the card (dex+number+total, or number+total+printedName): trust it
            // unless the hash is a real number past the gross-failure ceiling. Int.MAX_VALUE only
            // passes for a blank imageUrl (nothing to compare); a failed real-image download rejects.
            val pinned = identityPinned || nameVerified
            val pinnedAccepted = pinned && hashMatch != null && hashMatch.card.id == single.id &&
                (if (hashMatch.distance == Int.MAX_VALUE) single.imageUrl.isBlank()
                else hashMatch.distance <= PINNED_HASH_CONTRADICTION_DISTANCE)
            return if (visuallyConfirmed) {
                SearchConfidence(cards, true, single, ConfidencePath.SINGLE_CANDIDATE_HASH)
            } else if (pinnedAccepted) {
                val path = if (identityPinned) ConfidencePath.IDENTITY_PINNED else ConfidencePath.IDENTITY_PINNED_NAME
                SearchConfidence(cards, true, single, path)
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
