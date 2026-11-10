package pl.marcin.openclaw.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpokenTextSanitizerTest {
    private val sanitizer = SpokenTextSanitizer()

    @Test fun removesMarkdownFormattingButKeepsWords() {
        assertEquals("Teraz ważny tekst i strona.", sanitizer.sanitize("**Teraz** *ważny* `tekst` i [strona](https://example.com)."))
    }

    @Test fun omitsImagesCodeBlocksAndTables() {
        val result = sanitizer.sanitize("""
            Początek.
            ![wykres](chart.png)
            | Nazwa | Wynik |
            |---|---|
            | A | 12 |
            ```kotlin
            println("nie czytaj")
            ```
            Koniec.
        """.trimIndent())
        assertEquals("Początek.\nKoniec.", result)
    }

    @Test fun omitsAsciiArtAndHorizontalRules() {
        val result = sanitizer.sanitize("Tekst.\n+---+---+\n|   |   |\nDalej.")
        assertEquals("Tekst.\nDalej.", result)
        assertFalse(result.contains("---"))
    }

    @Test fun removesStandaloneMarkdownHeadingMarksFromNumbers() {
        assertEquals("27 stopni. C# pozostaje.", sanitizer.sanitize("## 27 ## stopni. C# pozostaje."))
    }
}
