package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.repository.LegacyIdReuseException
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * V7 / decision #136 foundation: the unblock (`tea.source += 'scrape'`) and the stable public_id with
 * soft-rollback (retract / merge) + the legacy-id map. No importer yet — these assert the schema + the
 * public-id resolution the API now depends on.
 */
@Transactional
class ScrapeFoundationIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var teaRepository: TeaRepository

    @Autowired
    lateinit var legacyIdMap: TeaLegacyIdMapRepository

    @Autowired
    lateinit var catalogService: TeaCatalogService

    @Test
    fun `a scrape-source row is now insertable and gets a public_id`() {
        val tea = newTea(source = "scrape", primary = "Da Hong Pao")
        val saved = teaRepository.saveAndFlush(tea)

        assertNotNull(saved.publicId, "public_id is populated by the entity default")
        assertEquals("active", saved.status)
        // The DB default also fills it on a raw insert path; confirm the round-trip reads it back.
        val reread = teaRepository.findByPublicId(saved.publicId)
        assertEquals(saved.id, reread?.id)
    }

    @Test
    fun `the source CHECK still rejects an unknown provenance value`() {
        // 'borrowed' is not in the V7 allowlist; the CHECK must reject it.
        assertFailsWith<DataIntegrityViolationException> {
            teaRepository.saveAndFlush(newTea(source = "borrowed", primary = "Bad Source"))
        }
    }

    @Test
    fun `legacy-id map is idempotent but refuses a conflicting remap (decision 137-C1)`() {
        val saved = teaRepository.saveAndFlush(newTea(source = "scrape", primary = "Tie Guan Yin"))
        val id = requireNotNull(saved.id)

        legacyIdMap.recordOnce(id, saved.publicId)
        // Re-recording the SAME pairing (e.g. a reseed) is a no-op, never a throw.
        legacyIdMap.recordOnce(id, saved.publicId)
        assertEquals(saved.publicId, legacyIdMap.findById(id).orElseThrow().publicId)

        // Re-recording the same legacy id with a DIFFERENT public_id is numeric-id reuse: fail loudly
        // rather than silently keep a now-wrong mapping that would resolve an old client to the wrong tea.
        assertFailsWith<LegacyIdReuseException> { legacyIdMap.recordOnce(id, UUID.randomUUID()) }
        assertEquals(saved.publicId, legacyIdMap.findById(id).orElseThrow().publicId, "mapping is unchanged")
    }

    @Test
    fun `detail by public id resolves an active tea`() {
        val saved = teaRepository.saveAndFlush(newTea(source = "scrape", primary = "Long Jing"))

        val detail = catalogService.detailByPublicId(saved.publicId)

        assertNotNull(detail)
        assertEquals(saved.id, detail.id)
        assertEquals(saved.publicId, detail.publicId)
        assertEquals("active", detail.status)
        assertNull(detail.supersededByPublicId)
    }

    @Test
    fun `a merged tea resolves to its survivor`() {
        val survivor = teaRepository.saveAndFlush(newTea(source = "curated", primary = "Survivor"))
        val merged = newTea(source = "scrape", primary = "Dup").apply {
            status = "merged"
            mergedIntoPublicId = survivor.publicId
        }
        val savedMerged = teaRepository.saveAndFlush(merged)

        val detail = catalogService.detailByPublicId(savedMerged.publicId)

        // Resolving the old (merged) public_id returns the survivor's detail so the client re-caches it.
        assertEquals(survivor.id, detail?.id)
        assertEquals(survivor.publicId, detail?.publicId)
    }

    @Test
    fun `a merge chain resolves to the terminal survivor with a re-cache signal`() {
        val survivor = teaRepository.saveAndFlush(newTea(source = "curated", primary = "Survivor C"))
        val mid = newTea(source = "scrape", primary = "Mid B").apply {
            status = "merged"
            mergedIntoPublicId = survivor.publicId
        }
        val savedMid = teaRepository.saveAndFlush(mid)
        val old = newTea(source = "scrape", primary = "Old A").apply {
            status = "merged"
            mergedIntoPublicId = savedMid.publicId
        }
        val savedOld = teaRepository.saveAndFlush(old)

        val detail = catalogService.detailByPublicId(savedOld.publicId)

        assertEquals(survivor.id, detail?.id, "A -> B -> C resolves to terminal C, not the intermediate B")
        assertEquals(survivor.publicId, detail?.supersededByPublicId, "redirect signals the survivor id to re-cache")
    }

    @Test
    fun `a retracted tea returns a tombstone, not a 404`() {
        val tea = newTea(source = "scrape", primary = "Gone").apply { status = "retracted" }
        val saved = teaRepository.saveAndFlush(tea)

        val detail = catalogService.detailByPublicId(saved.publicId)

        assertNotNull(detail, "a retracted tea is a tombstone, not absent")
        assertEquals("retracted", detail.status)
        assertEquals(saved.id, detail.id)
    }

    @Test
    fun `an unissued public id resolves to null`() {
        assertNull(catalogService.detailByPublicId(UUID.randomUUID()))
    }

    @Test
    fun `an active row may not carry a merge target`() {
        // The V7 CHECK ties merged_into_public_id to status='merged'.
        val survivor = teaRepository.saveAndFlush(newTea(source = "curated", primary = "Keep"))
        val bad = newTea(source = "scrape", primary = "BadActive").apply { mergedIntoPublicId = survivor.publicId }
        assertFailsWith<DataIntegrityViolationException> { teaRepository.saveAndFlush(bad) }
    }

    private fun newTea(source: String, primary: String): Tea {
        val tea = Tea(
            type = TeaType.OOLONG,
            source = source,
            dedupKey = DedupKeys.of(primary, pinyin = null, type = TeaType.OOLONG) + "|${UUID.randomUUID()}",
            verificationStatus = "unverified",
        )
        tea.addName(TeaName(locale = "en", name = primary, isPrimary = true, source = source))
        return tea
    }
}
