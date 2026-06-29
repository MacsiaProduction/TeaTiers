package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.repository.TeaRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Seeding is disabled on startup for ITs (AbstractIntegrationTest), so this calls the seeder
 * directly inside a rolled-back transaction to avoid polluting the shared container.
 */
@Transactional
class CatalogSeederIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var seeder: CatalogSeeder

    @Autowired
    lateinit var teaRepository: TeaRepository

    @Autowired
    lateinit var legacyIdMap: com.macsia.teatiers.repository.TeaLegacyIdMapRepository

    @Test
    fun `seeds the curated catalog and is idempotent`() {
        val inserted = seeder.seed()
        assertTrue(inserted >= 12, "expected the curated seed to add teas, got $inserted")
        assertEquals(inserted.toLong(), teaRepository.count())

        assertEquals(0, seeder.seed(), "re-running the seed must insert nothing")
        assertEquals(inserted.toLong(), teaRepository.count())
    }

    @Test
    fun `seeded tea carries its localized names and flavors`() {
        seeder.seed()

        val longjing = assertNotNull(teaRepository.findActiveByDedupKey("longjing|longjing|GREEN"))
        assertEquals("龙井", longjing.names.first { it.locale == "zh-Hans" }.name)
        assertTrue(longjing.names.any { it.locale == "ru" && it.name == "Лунцзин" })
        assertTrue(longjing.flavors.isNotEmpty())
        assertTrue(longjing.descriptions.any { it.locale == "en" })
    }

    @Test
    fun `seeded public ids are frozen from the seed and mapped, not random (decision 137-C1)`() {
        seeder.seed()

        val longjing = assertNotNull(teaRepository.findActiveByDedupKey("longjing|longjing|GREEN"))
        // The frozen UUID committed in catalog-seed.json -- a rebuild reproduces it byte-for-byte.
        assertEquals(UUID.fromString("acba9bbe-3663-4f79-8054-dad1da9f7287"), longjing.publicId)
        // And the numeric->public_id compat map points the new row's id at that same frozen UUID.
        val mapped = assertNotNull(legacyIdMap.findById(requireNotNull(longjing.id)).orElse(null))
        assertEquals(longjing.publicId, mapped.publicId)
    }
}
