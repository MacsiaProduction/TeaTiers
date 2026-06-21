package com.macsia.teatiers.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrossScriptKeysTest {

    @Test
    fun `detects script`() {
        assertEquals(CrossScriptKeys.Script.CYRILLIC, CrossScriptKeys.scriptOf("Да Хун Пао"))
        assertEquals(CrossScriptKeys.Script.LATIN, CrossScriptKeys.scriptOf("Da Hong Pao"))
        assertEquals(CrossScriptKeys.Script.HANZI, CrossScriptKeys.scriptOf("大红袍"))
        assertEquals(CrossScriptKeys.Script.MIXED, CrossScriptKeys.scriptOf("Da Hong 袍"))
    }

    @Test
    fun `bridges common Cyrillic tea names to pinyin candidates`() {
        assertEquals("da hong pao", CrossScriptKeys.palladiusToPinyin("Да Хун Пао"))
        assertEquals("tie guan yin", CrossScriptKeys.palladiusToPinyin("Те Гуань Инь"))
    }

    @Test
    fun `returns null when nothing in the curated table matches`() {
        // A Latin string has no Cyrillic syllables to bridge.
        assertNull(CrossScriptKeys.palladiusToPinyin("Earl Grey"))
    }

    @Test
    fun `normalize hint lowercases and strips latin diacritics`() {
        assertEquals("da hong pao", CrossScriptKeys.normalizeHint("Dà Hóng Páo"))
    }
}
