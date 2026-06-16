package com.macsia.teatiers.service

/**
 * Pure text guards for the LLM enrichment tier (research 07 section 6). The model output and the
 * pasted vendor text are both untrusted, so all sanitization/overlap logic lives here, free of Spring
 * and the DB, and is unit-tested directly.
 */
object EnrichmentText {

    private val HAN = Regex("[\\u4e00-\\u9fff]")
    private val HTML_TAG = Regex("<[^>]*>")
    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F]")
    private val WHITESPACE = Regex("\\s+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    const val VENDOR_TEXT_CAP = 4_000
    const val BLURB_CAP = 240

    fun containsHan(text: String): Boolean = HAN.containsMatchIn(text)

    /**
     * Defang pasted vendor text before it reaches the model: strip all `<...>` markup (which also
     * removes any `</VENDOR_TEXT>` the payload uses to break out of its data block), drop zero-width
     * and control chars, collapse whitespace, and hard-cap the length.
     */
    fun sanitizeVendorText(raw: String): String =
        raw.replace(HTML_TAG, " ")
            .replace(ZERO_WIDTH, "")
            .replace(CONTROL, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .take(VENDOR_TEXT_CAP)

    /** Normalize the model's blurb: drop control chars, collapse whitespace, cap to [BLURB_CAP]. */
    fun cleanBlurb(raw: String): String =
        raw.replace(CONTROL, " ").replace(WHITESPACE, " ").trim().take(BLURB_CAP)

    /**
     * Fraction of the blurb's word [n]-gram shingles that also appear in [source]. Used to reject a
     * blurb that copies the vendor prose (copyright) or echoes an injected instruction. Returns 0 for
     * blurbs shorter than [n] words (nothing to copy).
     */
    fun shingleOverlap(blurb: String, source: String, n: Int = 4): Double {
        val blurbShingles = shingles(blurb, n)
        if (blurbShingles.isEmpty()) return 0.0
        val sourceShingles = shingles(source, n)
        if (sourceShingles.isEmpty()) return 0.0
        val shared = blurbShingles.count { it in sourceShingles }
        return shared.toDouble() / blurbShingles.size
    }

    private fun shingles(text: String, n: Int): Set<String> {
        val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
        if (words.size < n) return emptySet()
        return (0..words.size - n).map { words.subList(it, it + n).joinToString(" ") }.toSet()
    }
}
