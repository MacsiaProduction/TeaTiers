package com.macsia.teatiers.client

import com.macsia.teatiers.domain.TeaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-logic tests for the Wikidata client: binding -> match folding, type priority, query shape. */
class WikidataSparqlClientTest {

    private val client = WikidataSparqlClient(WikidataProperties())

    private fun v(value: String) = SparqlValue(type = "literal", value = value)
    private fun uri(value: String) = SparqlValue(type = "uri", value = value)

    @Test
    fun `parse folds one entity's rows and maps its category to a TeaType`() {
        val response = SparqlResponse(
            SparqlResults(
                bindings = listOf(
                    mapOf(
                        "item" to uri("http://www.wikidata.org/entity/Q1069130"),
                        "cat" to uri("http://www.wikidata.org/entity/Q484083"),
                        "en" to v("Longjing tea"),
                        "ru" to v("Лунцзин"),
                        "zh" to v("龙井茶"),
                        "iso2" to v("CN"),
                        "descEn" to v("pan-roasted green tea"),
                    ),
                ),
            ),
        )

        val match = client.parse(response)

        assertEquals("Q1069130", match?.qid)
        assertEquals(TeaType.GREEN, match?.type)
        assertEquals("CN", match?.originCountry)
        assertEquals("Longjing tea", match?.nameEn)
        assertEquals("Лунцзин", match?.nameRu)
        assertEquals("龙井茶", match?.nameZhHans)
        assertEquals("pan-roasted green tea", match?.descriptionEn)
    }

    @Test
    fun `parse picks the lexicographically smallest QID for an ambiguous name`() {
        val response = SparqlResponse(
            SparqlResults(
                bindings = listOf(
                    mapOf("item" to uri("http://www.wikidata.org/entity/Q9999"), "en" to v("Ambi B")),
                    mapOf("item" to uri("http://www.wikidata.org/entity/Q1069130"), "en" to v("Ambi A")),
                ),
            ),
        )

        assertEquals("Q1069130", client.parse(response)?.qid)
    }

    @Test
    fun `parse returns null on empty or missing results`() {
        assertNull(client.parse(null))
        assertNull(client.parse(SparqlResponse(SparqlResults(bindings = emptyList()))))
    }

    @Test
    fun `teaTypeFor prefers the most specific base type`() {
        // pu'er (Q690098) is a subclass of fermented tea (Q2542583); PUER must win over DARK.
        assertEquals(TeaType.PUER, client.teaTypeFor(setOf("Q690098", "Q2542583")))
        assertEquals(TeaType.DARK, client.teaTypeFor(setOf("Q2542583")))
        assertEquals(TeaType.GREEN, client.teaTypeFor(setOf("Q484083")))
        assertEquals(TeaType.OTHER, client.teaTypeFor(emptySet()))
        assertEquals(TeaType.OTHER, client.teaTypeFor(setOf("Q123456")))
    }

    @Test
    fun `buildQuery constrains to the tea subtree and escapes the literal`() {
        val query = client.buildQuery("da hong \"pao\"")

        assertTrue(query.contains("wd:Q6097"), "must constrain to the tea subtree")
        assertTrue(query.contains("wd:Q484083"), "must list the green-tea category")
        assertTrue(query.contains("""da hong \"pao\""""), "must escape the embedded quote")
    }
}
