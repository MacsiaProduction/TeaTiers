package com.macsia.teatiers.repository

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.FlavorDimension
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaDescription
import com.macsia.teatiers.domain.TeaFlavor
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@Transactional
class TeaRepositoryIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var teaRepository: TeaRepository

    @PersistenceContext
    lateinit var entityManager: EntityManager

    @Test
    fun `persists a tea with names, descriptions and flavors`() {
        val tea = newLongjing()

        val saved = teaRepository.saveAndFlush(tea)
        entityManager.clear()

        val reloaded = teaRepository.findById(requireNotNull(saved.id)).orElseThrow()
        assertEquals(TeaType.GREEN, reloaded.type)
        assertEquals(3, reloaded.names.size)
        assertEquals(1, reloaded.descriptions.size)
        assertEquals(2, reloaded.flavors.size)

        val grassy = reloaded.flavors.first { it.dimension == FlavorDimension.GRASSY }
        assertEquals(4.toShort(), grassy.intensity)
        assertEquals("龙井", reloaded.names.first { it.locale == "zh-Hans" }.name)
    }

    @Test
    fun `looks up by wikidata qid and dedup key`() {
        teaRepository.saveAndFlush(newLongjing())
        entityManager.clear()

        assertNotNull(teaRepository.findByWikidataQid("Q474971"))
        assertNotNull(teaRepository.findByDedupKey("longjing|longjing|GREEN"))
        assertNull(teaRepository.findByDedupKey("absent|absent|OTHER"))
    }

    private fun newLongjing(): Tea {
        val tea = Tea(
            type = TeaType.GREEN,
            source = "curated",
            dedupKey = "longjing|longjing|GREEN",
            wikidataQid = "Q474971",
            originCountry = "CN",
        )
        tea.addName(TeaName(locale = "en", name = "Longjing", isPrimary = true))
        tea.addName(TeaName(locale = "zh-Hans", name = "龙井", isPrimary = true))
        tea.addName(TeaName(locale = "pinyin", name = "lóngjǐng"))
        tea.addDescription(
            TeaDescription(locale = "en", shortText = "Pan-fired green tea from Hangzhou."),
        )
        tea.addFlavor(TeaFlavor(dimension = FlavorDimension.GRASSY, intensity = 4))
        tea.addFlavor(TeaFlavor(dimension = FlavorDimension.UMAMI, intensity = 3))
        return tea
    }
}
