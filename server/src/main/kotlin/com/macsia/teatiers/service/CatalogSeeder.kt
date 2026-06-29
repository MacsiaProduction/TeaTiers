package com.macsia.teatiers.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaDescription
import com.macsia.teatiers.domain.TeaFlavor
import com.macsia.teatiers.domain.TeaImage
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
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
    private val legacyIdMapRepository: TeaLegacyIdMapRepository,
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
            val dedupKey = DedupKeys.ofSeed(seed)
            // Active-scoped: skip only when a LIVE identity already holds this key. A retracted
            // tombstone sharing it (allowed by the active-only partial unique since V16) must not block
            // re-seeding, and the plain lookup is no longer single-result.
            if (teaRepository.findActiveByDedupKey(dedupKey) != null) continue
            // saveAndFlush so the row (and its public_id) exists before the legacy-map FK insert.
            val saved = teaRepository.saveAndFlush(seed.toEntity(dedupKey))
            legacyIdMapRepository.recordOnce(requireNotNull(saved.id), saved.publicId)
            inserted++
        }
        if (inserted > 0) {
            log.info("Catalog seed v{}: inserted {} new teas", bundle.version, inserted)
        }
        return inserted
    }

    private fun loadBundle(): SeedBundle =
        ClassPathResource(SEED_PATH).inputStream.use { objectMapper.readValue(it, SeedBundle::class.java) }

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
        // Pin the frozen public_id from the seed (decision #137-C1) so a rebuild is reproducible; only
        // legacy seed entries without one fall back to the entity's random default.
        publicId?.let { tea.publicId = it }
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
        images.forEachIndexed { index, img ->
            tea.addImage(
                TeaImage(
                    position = index.toShort(),
                    url = img.url,
                    license = img.license,
                    sourceUrl = img.sourceUrl,
                    source = img.source ?: source,
                ),
            )
        }
        return tea
    }

    private companion object {
        const val SEED_PATH = "seed/catalog-seed.json"
    }
}
