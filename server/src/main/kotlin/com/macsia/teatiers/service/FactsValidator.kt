package com.macsia.teatiers.service

import com.macsia.teatiers.dto.ScrapedFacts
import jakarta.validation.Validator
import org.springframework.stereotype.Component

/**
 * Semantic validation of scraped facts via jakarta bean-validation (decision #141, PR-3): the declarative
 * constraints on [ScrapedFacts] (@KnownTeaType, @Iso3166) are enforced here at ingest AND re-checked at
 * canonical apply against the exact reviewed revision. Complements [FactsOnlyGuard] (which enforces the
 * facts-not-prose boundary) -- this one enforces "the values are semantically valid".
 */
@Component
class FactsValidator(private val validator: Validator) {

    fun validate(facts: ScrapedFacts) {
        val violations = validator.validate(facts)
        if (violations.isNotEmpty()) {
            throw FactsValidationException(
                violations.sortedBy { it.propertyPath.toString() }
                    .joinToString("; ") { "${it.propertyPath} ${it.message} (was '${it.invalidValue}')" },
            )
        }
    }
}

/** A scraped observation carried a semantically-invalid fact (unknown type, non-ISO country) -- #141 PR-3. */
class FactsValidationException(message: String) : RuntimeException(message)
