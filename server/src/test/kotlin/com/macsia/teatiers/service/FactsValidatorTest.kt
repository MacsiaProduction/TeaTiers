package com.macsia.teatiers.service

import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import jakarta.validation.Validation
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Decision #141, PR-3: semantic facts validation. An unknown tea type and a non-ISO country are REJECTED
 * (never coerced). Pure unit test against a default jakarta validator factory.
 */
class FactsValidatorTest {

    private val factsValidator = FactsValidator(Validation.buildDefaultValidatorFactory().validator)

    private fun facts(type: String? = "OOLONG", country: String? = "CN") =
        ScrapedFacts(names = listOf(ScrapedName("en", "Some Tea", true)), type = type, originCountry = country)

    @Test
    fun `valid facts pass`() {
        factsValidator.validate(facts())
    }

    @Test
    fun `a null type and null country pass (optional fields)`() {
        factsValidator.validate(facts(type = null, country = null))
    }

    @Test
    fun `an unknown tea type is rejected`() {
        assertFailsWith<FactsValidationException> { factsValidator.validate(facts(type = "PURPLE")) }
    }

    @Test
    fun `a known tea type is case-insensitive`() {
        factsValidator.validate(facts(type = "oolong")) // uppercased on check + on use
    }

    @Test
    fun `a non-ISO origin country is rejected`() {
        assertFailsWith<FactsValidationException> { factsValidator.validate(facts(country = "Cathay")) }
    }

    @Test
    fun `a non-canonical (lowercase) country code is rejected`() {
        assertFailsWith<FactsValidationException> { factsValidator.validate(facts(country = "cn")) }
    }

    private fun factsWithYear(year: Int?) =
        ScrapedFacts(names = listOf(ScrapedName("en", "Some Tea", true)), harvestYear = year)

    @Test
    fun `a plausible harvest year passes`() {
        factsValidator.validate(factsWithYear(2024))
        factsValidator.validate(factsWithYear(null)) // optional
    }

    @Test
    fun `a harvest year out of range is rejected`() {
        assertFailsWith<FactsValidationException> { factsValidator.validate(factsWithYear(1899)) }
        assertFailsWith<FactsValidationException> { factsValidator.validate(factsWithYear(2101)) }
    }
}
