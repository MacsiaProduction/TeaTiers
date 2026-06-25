package com.macsia.teatiers.service

import java.text.Normalizer

/**
 * The shared diacritic-folding normalizer: NFD-decompose, strip combining marks, lowercase, collapse
 * whitespace, trim. Used by both the catalog dedup key ([DedupKeys]) and the cross-script match hint
 * ([CrossScriptKeys.normalizeHint]) so the two derive byte-identical folded strings.
 *
 * Note: this is NFD (decompose so `\p{M}+` can strip the marks), distinct from [OcrSanitizer], which
 * uses NFC for a different purpose (clean display text, marks preserved).
 */
private val COMBINING_MARKS = Regex("\\p{M}+")
private val WHITESPACE = Regex("\\s+")

fun foldDiacritics(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace(WHITESPACE, " ")
        .trim()
