package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.FlavorDimension
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaDescription
import com.macsia.teatiers.domain.TeaFlavor
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.repository.TeaRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@Transactional
class TeaCatalogServiceIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var service: TeaCatalogService

    @Autowired
    lateinit var teaRepository: TeaRepository

    private var greenId: Long = 0
    private var oolongId: Long = 0
    private var blackId: Long = 0

    @BeforeTest
    fun seed() {
        greenId = save(
            type = TeaType.GREEN,
            origin = "CN",
            dedup = "longjing|longjing|GREEN",
            qid = "Q474971",
            names = listOf(Triple("en", "Longjing", true), Triple("zh-Hans", "龙井", true), Triple("pinyin", "longjing", false)),
            descriptionShort = "Pan-fired green tea from Hangzhou.",
            flavors = listOf(FlavorDimension.GRASSY to 4, FlavorDimension.UMAMI to 3),
        )
        oolongId = save(
            type = TeaType.OOLONG,
            origin = "CN",
            dedup = "tieguanyin|tieguanyin|OOLONG",
            qid = "Q1064759",
            names = listOf(Triple("en", "Tieguanyin", true), Triple("zh-Hans", "铁观音", true)),
            descriptionShort = null,
            flavors = listOf(FlavorDimension.FLORAL to 5),
        )
        blackId = save(
            type = TeaType.BLACK,
            origin = "IN",
            dedup = "assam|assam|BLACK",
            qid = "Q470066",
            names = listOf(Triple("en", "Assam", true)),
            descriptionShort = null,
            flavors = emptyList(),
        )
    }

    @Test
    fun `q matches a name substring case-insensitively`() {
        val page = service.search("long", null, null, null, null, 20)
        assertEquals(listOf(greenId), page.items.map { it.id })
        assertNull(page.nextCursor)
    }

    @Test
    fun `q honors the locale filter`() {
        assertEquals(listOf(greenId), service.search("龙", "zh-Hans", null, null, null, 20).items.map { it.id })
        assertTrue(service.search("龙", "en", null, null, null, 20).items.isEmpty())
    }

    @Test
    fun `type filter narrows results`() {
        val page = service.search(null, null, TeaType.OOLONG, null, null, 20)
        assertEquals(listOf(oolongId), page.items.map { it.id })
    }

    @Test
    fun `cursor paginates over all teas ordered by id`() {
        val first = service.search(null, null, null, null, null, 2)
        assertEquals(2, first.items.size)
        val cursor = requireNotNull(first.nextCursor)

        val second = service.search(null, null, null, null, cursor, 2)
        assertEquals(1, second.items.size)
        assertNull(second.nextCursor)

        assertEquals(
            listOf(greenId, oolongId, blackId).sorted(),
            (first.items + second.items).map { it.id }.sorted(),
        )
    }

    @Test
    fun `detail returns names, descriptions and flavors`() {
        val detail = requireNotNull(service.detail(greenId))
        assertEquals(3, detail.names.size)
        assertEquals(1, detail.descriptions.size)
        assertEquals(2, detail.flavors.size)
        assertEquals("Longjing", detail.names.first { it.isPrimary && it.locale == "en" }.name)

        assertNull(service.detail(-1))
    }

    @Test
    fun `facets expose distinct types and origins`() {
        val facets = service.facets()
        assertTrue(facets.types.containsAll(listOf(TeaType.GREEN, TeaType.OOLONG, TeaType.BLACK)))
        assertEquals(listOf("CN", "IN"), facets.origins)
    }

    private fun save(
        type: TeaType,
        origin: String,
        dedup: String,
        qid: String,
        names: List<Triple<String, String, Boolean>>,
        descriptionShort: String?,
        flavors: List<Pair<FlavorDimension, Int>>,
    ): Long {
        val tea = Tea(type = type, source = "curated", dedupKey = dedup, wikidataQid = qid, originCountry = origin)
        names.forEach { (locale, name, primary) ->
            tea.addName(TeaName(locale = locale, name = name, isPrimary = primary))
        }
        descriptionShort?.let { tea.addDescription(TeaDescription(locale = "en", shortText = it)) }
        flavors.forEach { (dimension, intensity) ->
            tea.addFlavor(TeaFlavor(dimension = dimension, intensity = intensity.toShort()))
        }
        return requireNotNull(teaRepository.saveAndFlush(tea).id)
    }
}
