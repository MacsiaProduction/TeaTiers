package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decision #137 C1 (numeric id is a legacy-map compat path, never a direct load) + C3 (one visibility
 * predicate: browse/search/facets never surface merged/retracted rows or PENDING/FAILED stubs; identity
 * lookup still returns tombstone/redirect). Seeding is off and the test is @Transactional, so the tea
 * table starts empty.
 */
@Transactional
class CatalogVisibilityIT : AbstractIntegrationTest() {

    @Autowired lateinit var service: TeaCatalogService

    @Autowired lateinit var teaRepository: TeaRepository

    @Autowired lateinit var legacyIdMap: TeaLegacyIdMapRepository

    // ---- C1: numeric detail resolves ONLY through the immutable legacy map ----

    @Test
    fun `detailByLegacyId resolves an active tea through the map`() {
        val tea = saveMapped(name = "Alpha", type = TeaType.OOLONG, origin = "XX")

        val detail = assertNotNull(service.detailByLegacyId(requireNotNull(tea.id)))
        assertEquals(tea.publicId, detail.publicId)
        assertEquals("active", detail.status)
    }

    @Test
    fun `detailByLegacyId returns null for a numeric id that is not in the map (never direct-loads)`() {
        // Saved but deliberately NOT recorded in the legacy map: the row exists, yet the numeric compat
        // path must NOT find it via findById -- otherwise a renumbering rebuild could resolve to it.
        val unmapped = teaRepository.saveAndFlush(newTea("Lonely", TeaType.GREEN, "XX"))
        assertNull(service.detailByLegacyId(requireNotNull(unmapped.id)))
        assertNull(service.detailByLegacyId(9_999_999L))
    }

    @Test
    fun `detailByLegacyId of a merged id redirects to the survivor`() {
        val survivor = saveMapped(name = "Survivor", type = TeaType.OOLONG, origin = "XX")
        val merged = saveMapped(name = "OldDup", type = TeaType.OOLONG, origin = "XX", status = "merged") {
            it.mergedIntoPublicId = survivor.publicId
        }
        val detail = assertNotNull(service.detailByLegacyId(requireNotNull(merged.id)))
        assertEquals(survivor.publicId, detail.publicId)
        assertEquals(survivor.publicId, detail.supersededByPublicId, "redirect signals the survivor to re-cache")
    }

    @Test
    fun `detailByLegacyId of a retracted id returns a tombstone, not a 404`() {
        val gone = saveMapped(name = "Gone", type = TeaType.BLACK, origin = "XX", status = "retracted")
        val detail = assertNotNull(service.detailByLegacyId(requireNotNull(gone.id)))
        assertEquals("retracted", detail.status)
    }

    // ---- C3: visibility predicate across browse, fuzzy search, and facets ----

    @Test
    fun `browse, fuzzy search and facets exclude merged, retracted and PENDING or FAILED rows`() {
        val active = saveMapped(name = "Alpha", type = TeaType.OOLONG, origin = "XX")
        saveMapped(name = "Beta", type = TeaType.WHITE, origin = "MM", status = "merged") {
            it.mergedIntoPublicId = active.publicId
        }
        saveMapped(name = "Gamma", type = TeaType.BLACK, origin = "RR", status = "retracted")
        saveMapped(name = "Delta", type = TeaType.PUER, origin = "PP", enrichment = "PENDING")
        saveMapped(name = "Epsilon", type = TeaType.GREEN, origin = "FF", enrichment = "FAILED")

        // Browse: only the active, non-stub row.
        val browse = service.search(null, null, null, null, null, 50)
        assertEquals(listOf(active.id), browse.items.map { it.id })

        // Fuzzy: a hidden row never matches; the visible one does.
        assertTrue(service.search("Beta", null, null, null, null, 50).items.isEmpty())
        assertTrue(service.search("Gamma", null, null, null, null, 50).items.isEmpty())
        assertTrue(service.search("Delta", null, null, null, null, 50).items.isEmpty())
        assertTrue(service.search("Epsilon", null, null, null, null, 50).items.isEmpty())
        assertEquals(listOf(active.id), service.search("Alpha", null, null, null, null, 50).items.map { it.id })

        // Facets reflect only visible rows.
        val facets = service.facets()
        assertEquals(listOf(TeaType.OOLONG), facets.types)
        assertEquals(listOf("XX"), facets.origins)
    }

    private fun saveMapped(
        name: String,
        type: TeaType,
        origin: String,
        status: String = "active",
        enrichment: String? = null,
        tweak: (Tea) -> Unit = {},
    ): Tea {
        val tea = newTea(name, type, origin).apply {
            this.status = status
            this.enrichmentState = enrichment
            tweak(this)
        }
        val saved = teaRepository.saveAndFlush(tea)
        legacyIdMap.recordOnce(requireNotNull(saved.id), saved.publicId)
        return saved
    }

    private fun newTea(name: String, type: TeaType, origin: String): Tea {
        val tea = Tea(
            type = type,
            source = "curated",
            dedupKey = DedupKeys.of(name, null, type) + "|${UUID.randomUUID()}",
            originCountry = origin,
            verificationStatus = "verified",
        )
        tea.addName(TeaName(locale = "en", name = name, isPrimary = true))
        return tea
    }
}
