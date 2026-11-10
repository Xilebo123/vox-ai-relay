package pl.marcin.openclaw.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class DeterministicSentenceSegmenterTest {
    private val segmenter = DeterministicSentenceSegmenter()

    @Test fun extractsCompleteSentencesAndLeavesFragment() {
        val result = segmenter.extractCompleteSentences("Pierwsze zdanie. Drugie pytanie? Fragment")
        assertEquals(listOf("Pierwsze zdanie.", "Drugie pytanie?"), result.complete)
        assertEquals("Fragment", result.remainder)
    }

    @Test fun doesNotSplitDecimalsIpAddressesOrPolishAbbreviations() {
        val result = segmenter.extractCompleteSentences("Wersja 3.14 działa na 192.168.1.1, np. lokalnie. Gotowe!")
        assertEquals(listOf("Wersja 3.14 działa na 192.168.1.1, np. lokalnie.", "Gotowe!"), result.complete)
        assertEquals("", result.remainder)
    }

    @Test fun paragraphBreakIsBoundary() {
        val result = segmenter.extractCompleteSentences("Pierwszy akapit\n\nDrugi")
        assertEquals(listOf("Pierwszy akapit"), result.complete)
        assertEquals("Drugi", result.remainder)
    }

    @Test fun ellipsisDoesNotCreateEmptySentences() {
        val result = segmenter.extractCompleteSentences("Chwileczkę... Już gotowe!")
        assertEquals(listOf("Chwileczkę... Już gotowe!"), result.complete)
    }

    @Test fun semicolonColonAndLineBreakAreBoundaries() {
        val result = segmenter.extractCompleteSentences("Pierwsza część; druga:\ntrzecia")
        assertEquals(listOf("Pierwsza część;", "druga:"), result.complete)
        assertEquals("trzecia", result.remainder)
    }

    @Test fun emergencySplitUsesNaturalBreakBeforeLimit() {
        val longText = "a".repeat(120) + ", " + "b".repeat(90)
        val result = segmenter.extractCompleteSentences(longText)
        assertEquals(1, result.complete.size)
        assertEquals(true, result.complete.first().endsWith(","))
        assertEquals(90, result.remainder.length)
    }
}
