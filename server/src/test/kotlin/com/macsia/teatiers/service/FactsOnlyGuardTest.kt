package com.macsia.teatiers.service

import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FactsOnlyGuardTest {

    private val guard = FactsOnlyGuard()

    private fun facts(
        names: List<ScrapedName> = listOf(ScrapedName("ru", "Да Хун Пао", true)),
        region: String? = null,
        brand: String? = null,
    ) = ScrapedFacts(names = names, type = "OOLONG", originCountry = "CN", region = region, brand = brand)

    @Test
    fun `accepts a clean facts-only observation`() {
        guard.validate(facts(region = "Wuyi"))
    }

    @Test
    fun `rejects a prose paragraph smuggled into region`() {
        val prose = "Этот чай собран на горе Уи и обладает богатым вкусом с нотами шоколада, ".repeat(3)
        assertFailsWith<FactsOnlyViolationException> { guard.validate(facts(region = prose)) }
    }

    @Test
    fun `rejects HTML markup in a fact`() {
        assertFailsWith<FactsOnlyViolationException> { guard.validate(facts(brand = "<b>Vendor</b>")) }
    }

    @Test
    fun `rejects a URL in a fact`() {
        assertFailsWith<FactsOnlyViolationException> { guard.validate(facts(brand = "see https://shop.example")) }
    }

    @Test
    fun `rejects a newline in a fact`() {
        assertFailsWith<FactsOnlyViolationException> { guard.validate(facts(region = "Wuyi\nMountains")) }
    }

    @Test
    fun `rejects an unsupported name locale`() {
        assertFailsWith<FactsOnlyViolationException> {
            guard.validate(facts(names = listOf(ScrapedName("de", "Da Hong Pao", true))))
        }
    }

    @Test
    fun `rejects facts with no names`() {
        assertFailsWith<FactsOnlyViolationException> { guard.validate(facts(names = emptyList())) }
    }
}
