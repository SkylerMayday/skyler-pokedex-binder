package com.skyler.pokedexbinder.ui.scanner

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.ParsedCardInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for a real, live-confirmed bug (2026-09-10): confidenceCheckInputs() is
 * what SmartThresholdUseCase.evaluate() receives to disambiguate same-name candidates. It must
 * use Gemini's own OCR'd values, never the perceptual hasher's already-chosen `best` candidate's
 * own number -- see confidenceCheckInputs()'s doc comment in ScannerViewModel.kt for the full
 * git-archaeology trace of how this regressed (455178a correct -> 498d035 silently broke it).
 */
class ScannerViewModelTest {

    private fun card(name: String, number: String) = TcgCard(
        id = "$name-$number", name = name, number = number, setName = "Set",
        imageUrl = "https://img/$name-$number.jpg", pokemonNames = listOf(name)
    )

    @Test
    fun `uses Gemini's OCR'd number, not the perceptual hasher's own pick`() {
        val parsed = ParsedCardInfo(
            cardName = "Castform", cardNumber = "62", setTotal = "172", hp = null, artist = null
        )
        // The perceptual hasher picked a DIFFERENT print than what Gemini actually read off the
        // card -- exactly the live-reproduced failure. confidenceCheckInputs must still surface
        // Gemini's "62", not the hasher's "20", so evaluate() can correctly reject this pick.
        val best = card(name = "Castform Sunny Form", number = "20")

        val (checkName, checkNumber) = confidenceCheckInputs(parsed, best)

        assertEquals("Castform", checkName)
        assertEquals("62", checkNumber)
    }

    @Test
    fun `falls back to best's name only when Gemini didn't read one, number still stays Gemini's (even if null)`() {
        val parsed = ParsedCardInfo(
            cardName = null, cardNumber = null, setTotal = null, hp = null, artist = null
        )
        val best = card(name = "Castform Sunny Form", number = "20")

        val (checkName, checkNumber) = confidenceCheckInputs(parsed, best)

        assertEquals("Castform Sunny Form", checkName) // fallback to best.name
        assertEquals(null, checkNumber) // NOT best.number -- an unread number must stay null,
        // so evaluate() correctly reports low confidence instead of rubber-stamping best's own pick.
    }

    @Test
    fun `Gemini's read number is preserved even when it happens to equal best's own number`() {
        // The trivial-self-match failure mode is invisible in this case (checkNumber ==
        // best.number by coincidence, not by construction) -- included so the fix's intent
        // (always pass parsed's own number) is asserted directly, not just distinguished by
        // this one adversarial case above.
        val parsed = ParsedCardInfo(
            cardName = "Pikachu", cardNumber = "25", setTotal = "102", hp = null, artist = null
        )
        val best = card(name = "Pikachu", number = "25")

        val (checkName, checkNumber) = confidenceCheckInputs(parsed, best)

        assertEquals("Pikachu", checkName)
        assertEquals("25", checkNumber)
    }
}
