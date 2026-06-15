package com.macsia.teatiers.domain.model

/** How the app picks light vs dark colors (decisions.md #28). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * User-controlled appearance settings persisted in Preferences DataStore (#36). Language is not
 * here: it lives in AppCompat's per-app locale store, which already persists and survives reinstalls
 * differently from our own preferences.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
)
