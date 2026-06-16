package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.repository.TeaRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

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

        val longjing = assertNotNull(teaRepository.findByDedupKey("longjing|longjing|GREEN"))
        assertEquals("龙井", longjing.names.first { it.locale == "zh-Hans" }.name)
        assertTrue(longjing.names.any { it.locale == "ru" && it.name == "Лунцзин" })
        assertTrue(longjing.flavors.isNotEmpty())
        assertTrue(longjing.descriptions.any { it.locale == "en" })
    }
}
