package com.macsia.teatiers.service

import com.macsia.teatiers.dto.ScrapedFacts
import org.springframework.stereotype.Component

/**
 * Defense-in-depth for the facts-only boundary (decision #136). The import DTO has no prose field by
 * construction; this guard additionally rejects an observation that smuggles vendor prose, HTML, or an
 * image URL into a "fact" slot (e.g. a paragraph pasted into `region`). It does NOT judge correctness --
 * only that the values look like facts, not copyrightable expression.
 */
@Component
class FactsOnlyGuard {

    fun validate(facts: ScrapedFacts) {
        ensure(facts.names.isNotEmpty()) { "facts must carry at least one name" }
        ensure(facts.names.any { it.value.isNotBlank() }) { "facts must carry a non-blank name" }
        // A real tea has a handful of names/aliases; a long list is bulk prose chunked across entries.
        ensure(facts.names.size <= MAX_NAMES) { "too many names (${facts.names.size}) -- looks like bulk text" }

        facts.names.forEach { n ->
            ensure(n.locale in ALLOWED_LOCALES) { "unsupported name locale '${n.locale}'" }
            checkFactValue("name:${n.locale}", n.value, MAX_NAME_LEN)
        }
        checkFactValue("originCountry", facts.originCountry, MAX_SHORT_LEN)
        checkFactValue("region", facts.region, MAX_FIELD_LEN)
        checkFactValue("cultivar", facts.cultivar, MAX_FIELD_LEN)
        checkFactValue("brand", facts.brand, MAX_FIELD_LEN)
        checkFactValue("vendor", facts.vendor, MAX_FIELD_LEN)

        ensure(facts.oxidationMin == null || facts.oxidationMin in 0..100) { "oxidationMin out of range" }
        ensure(facts.oxidationMax == null || facts.oxidationMax in 0..100) { "oxidationMax out of range" }
    }

    private fun checkFactValue(field: String, value: String?, maxLen: Int) {
        val v = value ?: return
        ensure(v.length <= maxLen) {
            "field '$field' is ${v.length} chars (max $maxLen) -- looks like prose, not a fact"
        }
        // Reject every line break, incl. the Unicode NEL (0x85) and line/paragraph separators (0x2028/0x2029).
        ensure(v.none { it in LINE_BREAKS }) { "field '$field' contains line breaks -- not a fact" }
        ensure(!HTML.containsMatchIn(v)) { "field '$field' contains markup -- not a fact" }
        ensure(!URL.containsMatchIn(v)) { "field '$field' contains a URL -- not a fact" }
    }

    private inline fun ensure(condition: Boolean, message: () -> String) {
        if (!condition) throw FactsOnlyViolationException(message())
    }

    private companion object {
        val ALLOWED_LOCALES = setOf("en", "ru", "zh-Hans", "pinyin")
        const val MAX_NAMES = 12
        const val MAX_NAME_LEN = 120
        const val MAX_SHORT_LEN = 64
        const val MAX_FIELD_LEN = 160
        // Built from code points so the source stays pure ASCII (no invisible literals).
        val LINE_BREAKS: Set<Char> = intArrayOf(0x0A, 0x0D, 0x0B, 0x0C, 0x85, 0x2028, 0x2029)
            .map { it.toChar() }.toSet()
        val HTML = Regex("<[a-zA-Z/!]")
        val URL = Regex("https?://", RegexOption.IGNORE_CASE)
    }
}

/** A source observation tried to smuggle prose/markup/URL into a fact field. */
class FactsOnlyViolationException(message: String) : RuntimeException(message)
