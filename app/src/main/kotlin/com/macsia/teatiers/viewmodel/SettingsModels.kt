package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.ThemeMode

/**
 * Languages the in-app picker offers (#28). [tag] is the BCP-47 primary subtag handed to
 * AppCompat's per-app locale store; [SYSTEM] clears the override (empty locale list).
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    RUSSIAN("ru"),
    ENGLISH("en"),
    // Chinese (zh) UI is deferred to the far future (decision #94) — not offered in the picker.
}

/**
 * Resolves the effective dark/light choice. Pure so it is unit-tested without a Compose or Android
 * runtime; [TeaTiersTheme] feeds it `isSystemInDarkTheme()`.
 */
fun isDarkTheme(mode: ThemeMode, systemInDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemInDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Maps the active locale's language tags (e.g. "ru", "en-US", "zh-Hans-CN", or null/empty for
 * "follow system") back to the picker enum, matching only the primary language subtag.
 */
fun appLanguageOf(languageTags: String?): AppLanguage {
    val primary = languageTags
        ?.substringBefore(',')
        ?.substringBefore('-')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    return when (primary) {
        "ru" -> AppLanguage.RUSSIAN
        "en" -> AppLanguage.ENGLISH
        // A previously-stored "zh" override now falls through to SYSTEM (Chinese UI deferred, #94).
        else -> AppLanguage.SYSTEM
    }
}
