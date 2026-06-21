package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Facts-only CI tripwire (decision #136). The public catalog must carry STRUCTURED FACTS only -- never
 * scraped vendor prose or images, and a scrape row can never be 'verified'. Runs in `check`, so it fails
 * the build the moment any scraped prose/image/verified-scrape row leaks into the public tables.
 */
@Transactional
class PublicCatalogFactsOnlyIT : AbstractIntegrationTest() {

    @Autowired lateinit var seeder: CatalogSeeder

    @PersistenceContext lateinit var em: EntityManager

    @Test
    fun `the public catalog ships no scraped prose, images, or verified-scrape rows`() {
        seeder.seed()

        assertEquals(0L, count("SELECT count(*) FROM tea_description WHERE source = 'scrape'"),
            "no scraped vendor prose may ship -- the #22 LLM tier writes our OWN blurb (source='ai')")
        assertEquals(0L, count("SELECT count(*) FROM tea_image WHERE source = 'scrape'"),
            "no scraped vendor images may ship")
        assertEquals(0L, count("SELECT count(*) FROM tea WHERE source = 'scrape' AND verification_status = 'verified'"),
            "a scrape can never self-certify as verified")
    }

    private fun count(sql: String): Long = (em.createNativeQuery(sql).singleResult as Number).toLong()
}
