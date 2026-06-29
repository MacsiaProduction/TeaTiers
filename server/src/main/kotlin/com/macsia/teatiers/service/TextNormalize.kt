package com.macsia.teatiers.service

import java.text.Normalizer

/**
 * The shared diacritic-folding normalizer: strips diacritics from **Latin** letters only (pinyin tone
 * marks: 'Lǜ' -> 'lu'), lowercases, collapses whitespace, trims. Used by both the catalog dedup key
 * ([DedupKeys]) and the cross-script match hint ([CrossScriptKeys.normalizeHint]) so the two derive
 * byte-identical folded strings.
 *
 * Crucially it leaves Cyrillic and CJK **intact** — matching Postgres `f_unaccent` (Latin-only), which
 * backs the `lower(f_unaccent(name))` cache lookup. A blanket NFD + `\p{M}` strip would collapse the
 * Cyrillic й -> и and ё -> е (the breve/diaeresis are combining marks after decomposition), merging
 * genuinely distinct Russian tea names under one dedup_key and split-braining the Kotlin folder against
 * the DB normalizer. So diacritics are stripped per-char only when the decomposed base is a Latin letter.
 */
private val WHITESPACE = Regex("\\s+")

fun foldDiacritics(value: String): String {
    val sb = StringBuilder(value.length)
    for (ch in value) {
        val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
        val base = decomposed[0]
        if (decomposed.length > 1 && (base in 'a'..'z' || base in 'A'..'Z')) {
            sb.append(base) // Latin letter: drop its combining marks (e.g. 'ǜ' -> 'u')
        } else {
            sb.append(ch) // Cyrillic / CJK / undiacritic'd: keep the precomposed char intact
        }
    }
    return sb.toString()
        .lowercase()
        .replace(WHITESPACE, " ")
        .trim()
}
