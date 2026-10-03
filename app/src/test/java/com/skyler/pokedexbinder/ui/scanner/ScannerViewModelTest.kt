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

    // --- lowConfidenceCards ---

    private fun cards(vararg ids: String) = ids.map { card(it, "1") }
    private fun ids(list: List<TcgCard>) = list.map { it.name }

    @Test
    fun `cross-language orders ranked cards by ascending distance`() {
        val c = cards("a", "b", "c")
        val out = lowConfidenceCards(c, listOf("b-1" to 26, "a-1" to 30, "c-1" to 34), true)
        assertEquals(listOf("b", "a", "c"), ids(out))
    }

    @Test
    fun `failed downloads and unranked cards trail in original order`() {
        val c = cards("a", "b", "c", "d")
        val out = lowConfidenceCards(c, listOf("a-1" to Int.MAX_VALUE, "c-1" to 40), true)
        assertEquals(listOf("c", "a", "b", "d"), ids(out))
    }

    @Test
    fun `ranked ids missing from candidates are ignored and result is a permutation`() {
        val c = cards("a", "b")
        val out = lowConfidenceCards(c, listOf("zzz" to 1, "b-1" to 5), true)
        assertEquals(listOf("b", "a"), ids(out))
        assertEquals(c.toSet(), out.toSet())
        assertEquals(c.size, out.size)
    }

    @Test
    fun `tail excludes by id, not by equality`() {
        val first = card("dup", "1").copy(imageUrl = "https://img/first.jpg")
        val second = card("dup", "1").copy(imageUrl = "https://img/second.jpg")
        val other = card("other", "1")
        val out = lowConfidenceCards(listOf(first, second, other), listOf("dup-1" to 5), true)
        assertEquals(listOf("dup-1", "other-1"), out.map { it.id })
    }

    @Test
    fun `equal distances keep ranked order`() {
        val c = cards("a", "b")
        val out = lowConfidenceCards(c, listOf("b-1" to 10, "a-1" to 10), true)
        assertEquals(listOf("b", "a"), ids(out))
    }

    @Test
    fun `no ranked data or non-cross leaves order unchanged`() {
        val c = cards("a", "b", "c")
        assertEquals(c, lowConfidenceCards(c, null, true))
        assertEquals(c, lowConfidenceCards(c, emptyList(), true))
        assertEquals(c, lowConfidenceCards(c, listOf("c-1" to 1), false))
    }
}
