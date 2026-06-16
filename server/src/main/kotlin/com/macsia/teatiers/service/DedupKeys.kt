package com.macsia.teatiers.service

import com.macsia.teatiers.domain.TeaType
import java.text.Normalizer

/**
 * Builds the normalized `tea.dedup_key` (plan.md section 4a): unaccented-lower primary name +
 * pinyin slug + type. The same key is used by the seed (this PR) and the section 6 enrich-on-miss
 * upsert (M4) so concurrent resolves of the same tea cannot create duplicate rows.
 */
object DedupKeys {

    fun of(primaryName: String, pinyin: String?, type: TeaType): String {
        val name = normalize(primaryName)
        val slug = pinyin?.let { normalize(it).replace(NON_ALNUM, "") }.orEmpty()
        return "$name|$slug|${type.name}"
    }

    /** Strip diacritics (NFD + combining-mark removal), lowercase, collapse whitespace. */
    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(WHITESPACE, " ")
            .trim()

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("\\s+")
    private val NON_ALNUM = Regex("[^a-z0-9]")
}
