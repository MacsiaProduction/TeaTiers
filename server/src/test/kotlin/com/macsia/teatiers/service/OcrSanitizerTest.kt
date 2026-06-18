package com.macsia.teatiers.service

import kotlin.test.Test
import kotlin.test.assertEquals

class OcrSanitizerTest {

    // Built from code points so the source stays pure ASCII (no invisible chars in literals).
    private val zwsp = Char(0x200B) // ZERO WIDTH SPACE
    private val bel = Char(0x0007) // BELL (control)
    private val cyrChe = Char(0x0447) // ч
    private val cyrA = Char(0x0430) // а
    private val cyrI = Char(0x0438) // и
    private val combBreve = Char(0x0306) // combining breve
    private val cyrShortI = Char(0x0439) // й (NFC of и + breve)

    @Test
    fun `caps to max length`() {
        assertEquals(5, OcrSanitizer.clean("a".repeat(100), 5).length)
    }

    @Test
    fun `strips zero-width and control characters`() {
        assertEquals("GreenTea", OcrSanitizer.clean("Green" + zwsp + "Tea" + bel, 100))
    }

    @Test
    fun `collapses inline whitespace, trims, and drops blank lines`() {
        assertEquals(
            "Green Tea\nblack tea",
            OcrSanitizer.clean("  Green   Tea \n\n\n  black  tea  ", 100),
        )
    }

    @Test
    fun `NFC-normalizes decomposed Cyrillic`() {
        val decomposed = "" + cyrChe + cyrA + cyrI + combBreve
        val composed = "" + cyrChe + cyrA + cyrShortI
        assertEquals(composed, OcrSanitizer.clean(decomposed, 100))
    }
}
