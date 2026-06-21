package com.macsia.teatiers.service

import java.text.Normalizer

/**
 * Cross-script helpers for identity matching (decision #136). The AUTHORITATIVE match normalization is
 * done in PostgreSQL (`lower(f_unaccent(...))`, the shared-normalizer invariant) -- [normalizeHint] here
 * is only a stored hint/diagnostic, never the match key.
 *
 * [palladiusToPinyin] is a curated, deliberately PARTIAL Palladius(Cyrillic)->pinyin syllable bridge:
 * `pypinyin` cannot transliterate Cyrillic, and there is no maintained Python/JVM Palladius library, so
 * this is a growing lookup, not a complete transliterator. It produces a REVIEW-QUEUE candidate only --
 * it never establishes identity (only the curated alias seed + human confirmation do).
 */
object CrossScriptKeys {

    enum class Script { LATIN, CYRILLIC, HANZI, MIXED, UNKNOWN }

    fun scriptOf(value: String): Script {
        var latin = false
        var cyrillic = false
        var hanzi = false
        value.forEach { c ->
            when {
                c in 'a'..'z' || c in 'A'..'Z' -> latin = true
                c in 'Ѐ'..'ӿ' -> cyrillic = true
                c in '一'..'鿿' -> hanzi = true
            }
        }
        val kinds = listOf(latin, cyrillic, hanzi).count { it }
        return when {
            kinds > 1 -> Script.MIXED
            hanzi -> Script.HANZI
            cyrillic -> Script.CYRILLIC
            latin -> Script.LATIN
            else -> Script.UNKNOWN
        }
    }

    /** Lowercase + strip combining marks + collapse whitespace. A display/storage hint, not the match key. */
    fun normalizeHint(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Best-effort Palladius(Cyrillic)->pinyin syllable bridge for a Cyrillic tea name. Greedy
     * longest-syllable match; returns null if nothing matched (the curated alias seed is the real bridge).
     * e.g. "Да Хун Пао" -> "da hong pao", "Те Гуань Инь" -> "tie guan yin".
     */
    fun palladiusToPinyin(cyrillic: String): String? {
        val lower = cyrillic.lowercase().replace(WHITESPACE, "")
        if (lower.isEmpty()) return null
        val out = StringBuilder()
        var i = 0
        var matchedAny = false
        while (i < lower.length) {
            val c = lower[i]
            if (!c.isCyrillic()) {
                // pass spaces/hyphens as separators, skip anything else
                if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                i++
                continue
            }
            val (syllable, len) = longestSyllableAt(lower, i) ?: return if (matchedAny) collapse(out) else null
            if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            out.append(syllable)
            matchedAny = true
            i += len
        }
        return if (matchedAny) collapse(out) else null
    }

    private fun longestSyllableAt(s: String, at: Int): Pair<String, Int>? {
        var len = MAX_SYLLABLE_LEN
        while (len >= 1) {
            if (at + len <= s.length) {
                val key = s.substring(at, at + len)
                PALLADIUS[key]?.let { return it to len }
            }
            len--
        }
        return null
    }

    private fun collapse(out: StringBuilder): String = out.toString().replace(WHITESPACE, " ").trim()

    private fun Char.isCyrillic(): Boolean = this in 'Ѐ'..'ӿ'

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_SYLLABLE_LEN = 5

    // Curated, partial Palladius syllable table (longest-match). Grows as new Cyrillic tea names appear.
    private val PALLADIUS: Map<String, String> = mapOf(
        "чжэн" to "zheng", "чжан" to "zhang", "чжун" to "zhong", "шэн" to "sheng", "чжэ" to "zhe",
        "гуань" to "guan", "хуан" to "huang", "цзин" to "jing", "цзян" to "jiang", "цзяо" to "jiao",
        "сян" to "xiang", "шань" to "shan", "чжу" to "zhu", "чай" to "cha", "дянь" to "dian",
        "хун" to "hong", "пао" to "pao", "гуй" to "gui", "инь" to "yin", "лун" to "long",
        "бай" to "bai", "хао" to "hao", "мао" to "mao", "фэн" to "feng", "тигр" to "tie",
        "те" to "tie", "да" to "da", "сяо" to "xiao", "шуй" to "shui", "сы" to "si",
        "пуэр" to "puer", "пу" to "pu", "эр" to "er", "люй" to "lyu", "ча" to "cha",
        "хэй" to "hei", "хэ" to "he", "цзы" to "zi", "вэй" to "wei", "лао" to "lao",
        "ган" to "gan", "ань" to "an", "цзинь" to "jin", "цзюнь" to "jun", "мэй" to "mei",
        "гун" to "gong", "шу" to "shu",
    )
}
