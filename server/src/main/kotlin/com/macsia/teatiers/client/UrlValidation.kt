package com.macsia.teatiers.client

/**
 * Startup validation for operator-set endpoint URLs (review F10). These are config-controlled (not
 * attacker-reachable), but a typo like a missing scheme should fail fast at boot with a clear message
 * rather than surface as an opaque error on the first call. Blank is allowed — it deliberately
 * disables the optional tiers (OCR / LLM degrade to 503).
 */
internal fun requireHttpUrl(url: String, propertyName: String) {
    require(url.isBlank() || url.startsWith("http://") || url.startsWith("https://")) {
        "$propertyName must be an http(s) URL (or blank to disable), got: $url"
    }
}
