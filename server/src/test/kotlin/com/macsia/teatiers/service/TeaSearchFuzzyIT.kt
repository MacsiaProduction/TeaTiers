package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.repository.TeaRepository
import jakarta.persistence.EntityManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * Typo-tolerant search acceptance gate (decision #79, research run 09 Deliverable 4). Runs the
 * ru/en/pinyin/zh gold set against the real postgres:16-alpine via Testcontainers, and asserts the
 * collation prerequisite (pg_trgm needs a non-C/POSIX, UTF8 locale to case-fold and char-split
 * multibyte text) so a regression to a C-locale image fails loudly here rather than in production.
 */
@Transactional
class TeaSearchFuzzyIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var service: TeaCatalogService

    @Autowired
    lateinit var teaRepository: TeaRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private var oolongId = 0L
    private var puerId = 0L
    private var shuPuerId = 0L
    private var darjeelingId = 0L
    private var senchaId = 0L
    private var gunpowderId = 0L
    private var longjingId = 0L
    private var tieguanyinId = 0L
    private var lvchaId = 0L

    @BeforeTest
    fun seed() {
        oolongId = save(TeaType.OOLONG, "ru" to "Улун", "en" to "Oolong")
        puerId = save(TeaType.PUER, "ru" to "Пуэр", "en" to "Pu-erh")
        shuPuerId = save(TeaType.PUER, "ru" to "Шу Пуэр Менхай")
        darjeelingId = save(TeaType.BLACK, "ru" to "Дарджилинг", "en" to "Darjeeling")
        senchaId = save(TeaType.GREEN, "ru" to "Сенча", "en" to "Sencha")
        gunpowderId = save(TeaType.GREEN, "en" to "Gunpowder")
        longjingId = save(TeaType.GREEN, "en" to "Longjing", "zh-Hans" to "龙井", "pinyin" to "longjing")
        tieguanyinId = save(TeaType.OOLONG, "en" to "Tieguanyin", "zh-Hans" to "铁观音", "pinyin" to "tieguanyin")
        lvchaId = save(TeaType.GREEN, "zh-Hans" to "绿茶", "pinyin" to "lu cha")
    }

    @Test
    fun `database locale supports multibyte trigram search (non-C, UTF8)`() {
        val collate = entityManager
            .createNativeQuery("select datcollate from pg_database where datname = current_database()")
            .singleResult as String
        val encoding = entityManager
            .createNativeQuery("select pg_encoding_to_char(encoding) from pg_database where datname = current_database()")
            .singleResult as String
        assertTrue(collate !in setOf("C", "POSIX"), "pg_trgm needs a non-C/POSIX locale; got $collate")
        assertEquals("UTF8", encoding)
    }

    @Test
    fun `gold set - ru, en, pinyin typos and zh exact all recall the intended tea`() {
        // (query, expected tea id) — ru/en/pinyin typos + pinyin tone marks + zh exact substring.
        val gold = listOf(
            "улонг" to oolongId, // 1-char substitution (hardest: ws 0.333)
            "пуэер" to puerId, // insertion
            "шу пуер" to shuPuerId, // word-similarity substring
            "сенча" to senchaId, // exact
            "даржилинг" to darjeelingId, // deletion
            "oolng" to oolongId, // deletion
            "puerh" to puerId, // separator-insensitive
            "pu erh" to puerId,
            "senca" to senchaId, // deletion
            "gunpowdr" to gunpowderId, // deletion
            "darjeling" to darjeelingId, // deletion
            "longjing" to longjingId, // exact pinyin
            "lóngjǐng" to longjingId, // tone marks stripped by f_unaccent
            "long jing" to longjingId, // space-insensitive
            "tieguanyn" to tieguanyinId, // deletion
            "tie guan yin" to tieguanyinId, // space tolerance
            "lu cha" to lvchaId, // pinyin alias row
            "龙井" to longjingId, // exact Hanzi (substring path)
            "铁观音" to tieguanyinId,
            "绿茶" to lvchaId,
        )
        for ((q, expected) in gold) {
            val ids = topIds(q)
            assertTrue(expected in ids, "query '$q' should recall tea #$expected; got $ids")
        }
    }

    @Test
    fun `exact short name outranks the same word inside a longer name`() {
        // Both "Пуэр" and "Шу Пуэр Менхай" match "пуэр" at word_similarity 1.0; the similarity()
        // tiebreaker must put the exact short name first.
        assertEquals(puerId, topIds("пуэр").first())
    }

    @Test
    fun `locale filter scopes the fuzzy match`() {
        assertEquals(listOf(longjingId), topIds("龙", locale = "zh-Hans"))
        assertTrue(topIds("龙", locale = "en").isEmpty())
    }

    @Test
    fun `a non-matching query returns nothing (no false-positive flooding)`() {
        val page = service.search("zzzqqq", null, null, null, null, 20)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `fuzzy results are a single page with no cursor`() {
        // "пуэр" matches both pu-erh teas; a fuzzy page never carries a keyset cursor.
        val page = service.search("пуэр", null, null, null, null, 20)
        assertTrue(page.items.size >= 2)
        assertEquals(null, page.nextCursor)
    }

    private fun topIds(q: String, locale: String? = null): List<Long> =
        service.search(q, locale, null, null, null, 20).items.map { it.id }

    private fun save(type: TeaType, vararg names: Pair<String, String>): Long {
        val tea = Tea(type = type, source = "curated", dedupKey = "${type.name}|${names.first().second}")
        names.forEachIndexed { index, (locale, name) ->
            tea.addName(TeaName(locale = locale, name = name, isPrimary = index == 0))
        }
        return requireNotNull(teaRepository.saveAndFlush(tea).id)
    }
}
