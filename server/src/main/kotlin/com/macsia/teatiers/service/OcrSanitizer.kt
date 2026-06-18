package com.macsia.teatiers.service

import java.text.Normalizer

/**
 * Cleans raw OCR output before it is returned for user review and (later) sent as `sourceText`
 * (research run 10 guards; #25/#44/#65). NFC-normalizes, strips zero-width and control characters
 * (a vector for prompt-injection obfuscation), collapses whitespace, drops blank lines, and caps
 * length. Pure + JVM-testable. The deeper injection defense lives in the LLM tier (#65); here we
 * just hand back clean, bounded text — the user edits/confirms it before it ever reaches `/resolve`.
 */
object OcrSanitizer {

    private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")
    private val INLINE_SPACE = Regex("[ \\t\\u00A0]+")

    fun clean(raw: String, maxLength: Int): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val noZeroWidth = ZERO_WIDTH.replace(normalized, "")
        val noControl = buildString(noZeroWidth.length) {
            for (ch in noZeroWidth) {
                when {
                    ch == '\n' -> append('\n')
                    ch == '\t' -> append(' ')
                    ch.isISOControl() -> Unit // drop other control chars (ANSI escapes, NUL, etc.)
                    else -> append(ch)
                }
            }
        }
        val collapsed = noControl.lineSequence()
            .map { INLINE_SPACE.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        return if (collapsed.length > maxLength) collapsed.substring(0, maxLength).trimEnd() else collapsed
    }
}
