package com.macsia.teatiers.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.macsia.teatiers.domain.TeaType
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Wikidata client over the public SPARQL endpoint. The query is constrained to the `tea` (Q6097)
 * `P31/P279*` subtree so only real teas match (a plain label search ranks "Longjing District" first).
 * One round trip yields the QID, category (-> [TeaType]), en/ru/zh-Hans labels, ISO origin, and the
 * English gloss; query shape verified live (context/decisions.md #64).
 */
@Component
class WikidataSparqlClient(
    private val props: WikidataProperties,
) : WikidataClient {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        requireHttpUrl(props.endpoint, "teatiers.wikidata.endpoint")
    }

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(props.endpoint)
        .defaultHeader(HttpHeaders.USER_AGENT, props.userAgent)
        .defaultHeader(HttpHeaders.ACCEPT, "application/sparql-results+json")
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs.toLong()))
                setReadTimeout(Duration.ofMillis(props.readTimeoutMs.toLong()))
            },
        )
        .build()

    override fun findTea(name: String, locale: String?): WikidataTea? {
        if (!props.enabled) return null
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return null
        return parse(query(buildQuery(needle)))
    }

    /**
     * Folds the SPARQL bindings into a single match. A tea entity can span several rows (one per
     * matched category), so rows are grouped by item and the lexicographically smallest QID wins,
     * keeping the choice deterministic for the rare ambiguous name. Visible for unit testing.
     */
    internal fun parse(response: SparqlResponse?): WikidataTea? {
        val bindings = response?.results?.bindings.orEmpty().filter { it["item"]?.value != null }
        if (bindings.isEmpty()) return null

        val itemUri = bindings.mapNotNull { it["item"]?.value }.min()
        val rows = bindings.filter { it["item"]?.value == itemUri }
        val cats = rows.mapNotNull { it["cat"]?.value?.toQid() }.toSet()
        val first = rows.first()

        return WikidataTea(
            qid = itemUri.toQid(),
            type = teaTypeFor(cats),
            originCountry = first["iso2"].value(),
            nameEn = first["en"].value(),
            nameRu = first["ru"].value(),
            nameZhHans = first["zh"].value(),
            descriptionEn = first["descEn"].value(),
        )
    }

    private fun query(sparql: String): SparqlResponse? {
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                // Pass the SPARQL as a URI *variable* (`{q}`), not a literal queryParam value: in
                // RestClient's default TEMPLATE_AND_VALUES encoding mode only variables are
                // percent-encoded, so a literal SPARQL string leaves its `?`, `{`, `}` and spaces
                // raw -> URISyntaxException -> every resolve miss 500s (context/decisions.md #115).
                return restClient.get()
                    .uri("?query={q}&format=json", sparql)
                    .retrieve()
                    .body(SparqlResponse::class.java)
            } catch (ex: RestClientException) {
                // Transient transport/HTTP error -> retry, then degrade.
                lastError = ex
                if (attempt < MAX_ATTEMPTS - 1) Thread.sleep(RETRY_BACKOFF_MS)
            } catch (ex: RuntimeException) {
                // Any other client-side failure (e.g. a malformed request) is not transient: a tea
                // lookup must never fail the caller's /resolve, so fail closed -> "unresolved".
                log.warn("Wikidata lookup errored (non-transient): {}", ex.toString())
                return null
            }
        }
        // A Wikidata outage degrades resolve to "unresolved" rather than failing the request.
        log.warn("Wikidata lookup failed after {} attempts: {}", MAX_ATTEMPTS, lastError?.message)
        return null
    }

    /** Maps reachable tea categories to our enum, preferring the most specific base type. */
    internal fun teaTypeFor(cats: Set<String>): TeaType =
        TYPE_PRIORITY.firstOrNull { it.first in cats }?.second ?: TeaType.OTHER

    internal fun buildQuery(needleLower: String): String {
        val literal = escapeSparql(needleLower)
        val catFilter = TYPE_PRIORITY.joinToString(",") { "wd:${it.first}" }
        return """
            SELECT ?item ?cat ?en ?ru ?zh ?iso2 ?descEn WHERE {
              ?item wdt:P31/wdt:P279* wd:$TEA_QID .
              ?item rdfs:label|skos:altLabel ?l .
              FILTER(LCASE(STR(?l)) = "$literal") .
              OPTIONAL { ?item wdt:P31/wdt:P279* ?cat . FILTER(?cat IN ($catFilter)) }
              OPTIONAL { ?item rdfs:label ?en. FILTER(LANG(?en) = "en") }
              OPTIONAL { ?item rdfs:label ?ru. FILTER(LANG(?ru) = "ru") }
              OPTIONAL { ?item rdfs:label ?zh. FILTER(LANG(?zh) = "zh-hans") }
              OPTIONAL { ?item wdt:P495 ?c. ?c wdt:P297 ?iso2. }
              OPTIONAL { ?item schema:description ?descEn. FILTER(LANG(?descEn) = "en") }
            }
            ORDER BY ?item
            LIMIT 20
        """.trimIndent()
    }

    /** Escape the value so it cannot break out of the SPARQL string literal. */
    private fun escapeSparql(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace("\t", " ")

    private fun String.toQid(): String = substringAfterLast('/')

    private fun SparqlValue?.value(): String? = this?.value?.takeIf { it.isNotBlank() }

    private companion object {
        const val TEA_QID = "Q6097"
        const val MAX_ATTEMPTS = 2
        const val RETRY_BACKOFF_MS = 500L

        // Wikidata tea-category QID -> our TeaType, ordered most-specific first so a pu'er (a
        // subclass of "fermented tea") resolves to PUER rather than DARK. QIDs verified live
        // 2026-06-16 (see context/decisions.md).
        val TYPE_PRIORITY: List<Pair<String, TeaType>> = listOf(
            "Q690098" to TeaType.PUER, // pu'er
            "Q2542583" to TeaType.DARK, // fermented tea
            "Q231587" to TeaType.OOLONG, // oolong
            "Q824529" to TeaType.YELLOW, // yellow tea
            "Q238306" to TeaType.WHITE, // white tea
            "Q484083" to TeaType.GREEN, // green tea
            "Q203415" to TeaType.BLACK, // black tea
            "Q877661" to TeaType.BLENDED, // masala chai
            "Q3526264" to TeaType.BLENDED, // flavored tea
            "Q137611595" to TeaType.BLENDED, // flavoured tea (variant spelling)
            "Q11617958" to TeaType.HERBAL, // tea without tea leaves (herbal infusions)
        )
    }
}

/** Minimal view of the SPARQL JSON results format; unknown fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SparqlResponse(val results: SparqlResults?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SparqlResults(val bindings: List<Map<String, SparqlValue>>?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SparqlValue(val type: String?, val value: String?)
