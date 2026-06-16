package com.macsia.teatiers.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaDescription
import com.macsia.teatiers.domain.TeaFlavor
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.repository.TeaRepository
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Loads the curated catalog seed from the classpath and upserts it. Idempotent: a tea already
 * present (matched on its normalized dedup key) is skipped, so running on every startup is safe.
 */
@Component
class CatalogSeeder(
    private val teaRepository: TeaRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Dedicated Jackson 2 mapper: the Spring-managed bean is Jackson 3 (Spring Boot 4) and the
    // seed file is a trusted build artifact, so we decouple it from the web layer's mapper.
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Transactional
    fun seed(): Int {
        val bundle = loadBundle()
        var inserted = 0
        for (seed in bundle.teas) {
            val dedupKey = seed.dedupKey()
            if (teaRepository.findByDedupKey(dedupKey) != null) continue
            teaRepository.save(seed.toEntity(dedupKey))
            inserted++
        }
        if (inserted > 0) {
            log.info("Catalog seed v{}: inserted {} new teas", bundle.version, inserted)
        }
        return inserted
    }

    private fun loadBundle(): SeedBundle =
        ClassPathResource(SEED_PATH).inputStream.use { objectMapper.readValue(it, SeedBundle::class.java) }

    private fun SeedTea.dedupKey(): String {
        val primary = names.firstOrNull { it.locale == "en" && it.isPrimary }
            ?: names.first { it.isPrimary }
        val pinyin = names.firstOrNull { it.locale == "pinyin" }?.name
        return DedupKeys.of(primary.name, pinyin, type)
    }

    private fun SeedTea.toEntity(dedupKey: String): Tea {
        val tea = Tea(
            type = type,
            source = source,
            dedupKey = dedupKey,
            wikidataQid = wikidataQid,
            originCountry = originCountry,
            region = region,
            cultivar = cultivar,
            oxidationMin = oxidationMin?.toShort(),
            oxidationMax = oxidationMax?.toShort(),
            brand = brand,
            sourceUrl = sourceUrl,
            license = license,
            verificationStatus = verificationStatus,
        )
        names.forEach { tea.addName(TeaName(locale = it.locale, name = it.name, isPrimary = it.isPrimary, source = source)) }
        descriptions.forEach {
            tea.addDescription(
                TeaDescription(
                    locale = it.locale,
                    shortText = it.short,
                    fullText = it.full,
                    source = it.source ?: source,
                    license = it.license,
                ),
            )
        }
        flavors.forEach { tea.addFlavor(TeaFlavor(dimension = it.dimension, intensity = it.intensity.toShort())) }
        return tea
    }

    private companion object {
        const val SEED_PATH = "seed/catalog-seed.json"
    }
}
