package com.macsia.teatiers.data.db

import com.macsia.teatiers.data.remote.teaTypeFromWire
import com.macsia.teatiers.domain.model.CatalogName
import com.macsia.teatiers.domain.model.CatalogTea
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stored shape of one cached name; mirrors [CatalogName] but is its own @Serializable type. */
@Serializable
private data class CachedName(val locale: String, val name: String, val isPrimary: Boolean)

private val cacheJson = Json { ignoreUnknownKeys = true }

/**
 * Cache row for a catalog tea. `searchText` is every name lowercased and space-joined so the
 * offline LIKE matches any locale; `namesJson` keeps the structured names for reconstruction.
 */
fun CatalogTea.toCacheEntity(fetchedAtEpochMs: Long): CatalogCacheEntity {
    val cached = names.map { CachedName(it.locale, it.name, it.isPrimary) }
    return CatalogCacheEntity(
        id = id,
        type = type.name,
        originCountry = originCountry,
        brand = brand,
        verificationStatus = verificationStatus,
        namesJson = cacheJson.encodeToString(cached),
        searchText = names.joinToString(" ") { it.name }.lowercase(),
        fetchedAtEpochMs = fetchedAtEpochMs,
    )
}

fun CatalogCacheEntity.toDomain(): CatalogTea {
    val names = runCatching {
        cacheJson.decodeFromString<List<CachedName>>(namesJson)
    }.getOrDefault(emptyList())
    return CatalogTea(
        id = id,
        type = teaTypeFromWire(type),
        originCountry = originCountry,
        brand = brand,
        verificationStatus = verificationStatus,
        names = names.map { CatalogName(it.locale, it.name, it.isPrimary) },
    )
}
