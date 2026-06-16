package com.macsia.teatiers.client

import com.macsia.teatiers.domain.TeaType

/**
 * Read-only Wikidata SPARQL client — the free (CC0) first tier of `/resolve` (plan.md section 6).
 * Behind an interface so the resolve service is unit-testable without the network (rule 30-backend).
 */
interface WikidataClient {

    /**
     * Exact (case-insensitive) match for a tea name/alias inside the Wikidata "tea" (Q6097) subtree;
     * null on miss or network failure. The subtree filter is what stops "Longjing" matching the
     * district. [locale] is only a primary-label hint — the lookup itself is language-agnostic.
     */
    fun findTea(name: String, locale: String?): WikidataTea?
}

/**
 * A tea matched in Wikidata. Names are the entity's labels (CC0); [type] is derived from the
 * entity's `instance of`/`subclass of` chain, defaulting to [TeaType.OTHER] when no known tea
 * category is reachable.
 */
data class WikidataTea(
    val qid: String,
    val type: TeaType,
    /** ISO 3166-1 alpha-2 country of origin (P495 -> P297), if Wikidata records one. */
    val originCountry: String?,
    val nameEn: String?,
    val nameRu: String?,
    val nameZhHans: String?,
    /** Short English gloss (the entity description); CC0, safe to store and show. */
    val descriptionEn: String?,
)
