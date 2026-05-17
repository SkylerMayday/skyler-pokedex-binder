package com.skyler.pokedexbinder.domain

import javax.inject.Inject

data class ParsedCardInfo(
    val cardName: String,
    val cardNumber: String?
)

class OcrCardParser @Inject constructor() {

    // Matches formats: 4/102, 025/185, 033/181
    private val setNumberRegex = Regex("""(\d{1,3})/\d{1,3}""")

    // HP line signals end of name area
    private val hpRegex = Regex("""HP\s+\d+""", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): ParsedCardInfo {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        val cardNumber = setNumberRegex.find(rawText)?.groupValues?.get(1)

        // Card name is text before the HP line, joined. Exclude lines that are just numbers.
        val nameLines = mutableListOf<String>()
        for (line in lines) {
            if (hpRegex.containsMatchIn(line)) break
            if (line.matches(Regex("""^\d+$"""))) continue
            nameLines.add(line)
        }

        val cardName = nameLines
            .joinToString(" ")
            .trim()
            .ifEmpty { lines.firstOrNull() ?: "" }

        return ParsedCardInfo(cardName = cardName, cardNumber = cardNumber)
    }
}
