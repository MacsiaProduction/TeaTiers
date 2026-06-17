package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.ThemeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsModelsTest {

    @Test
    fun `system theme mode follows the system dark flag`() {
        assertTrue(isDarkTheme(ThemeMode.SYSTEM, systemInDark = true))
        assertFalse(isDarkTheme(ThemeMode.SYSTEM, systemInDark = false))
    }

    @Test
    fun `explicit light and dark modes ignore the system flag`() {
        assertFalse(isDarkTheme(ThemeMode.LIGHT, systemInDark = true))
        assertTrue(isDarkTheme(ThemeMode.DARK, systemInDark = false))
    }

    @Test
    fun `appLanguageOf maps the primary subtag regardless of region or script`() {
        assertEquals(AppLanguage.RUSSIAN, appLanguageOf("ru"))
        assertEquals(AppLanguage.ENGLISH, appLanguageOf("en-US"))
    }

    @Test
    fun `appLanguageOf falls back to SYSTEM for empty, null, unknown, or deferred-zh tags`() {
        assertEquals(AppLanguage.SYSTEM, appLanguageOf(null))
        assertEquals(AppLanguage.SYSTEM, appLanguageOf(""))
        assertEquals(AppLanguage.SYSTEM, appLanguageOf("fr"))
        // Chinese UI is deferred (#94): a stored zh override now falls through to SYSTEM.
        assertEquals(AppLanguage.SYSTEM, appLanguageOf("zh-Hans-CN"))
    }

    @Test
    fun `appLanguageOf reads the first tag of a comma-separated list`() {
        // AppCompat returns the active locales as a comma-joined tag list; the first wins.
        assertEquals(AppLanguage.ENGLISH, appLanguageOf("en-US,ru"))
    }

    @Test
    fun `each picker language carries the expected BCP-47 tag`() {
        assertEquals(null, AppLanguage.SYSTEM.tag)
        assertEquals("ru", AppLanguage.RUSSIAN.tag)
        assertEquals("en", AppLanguage.ENGLISH.tag)
        // Chinese (zh) is intentionally absent from the picker — deferred to the far future (#94).
        assertEquals(listOf("ru", "en"), AppLanguage.entries.mapNotNull { it.tag })
    }
}
