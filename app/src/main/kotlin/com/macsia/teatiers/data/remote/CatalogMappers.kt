package com.macsia.teatiers.data.remote

import com.macsia.teatiers.data.remote.dto.TeaDescriptionDto
import com.macsia.teatiers.data.remote.dto.TeaDetailDto
import com.macsia.teatiers.data.remote.dto.TeaImageDto
import com.macsia.teatiers.data.remote.dto.TeaNameDto
import com.macsia.teatiers.data.remote.dto.TeaProvenanceDto
import com.macsia.teatiers.data.remote.dto.TeaSummaryDto
import com.macsia.teatiers.domain.model.CatalogDescription
import com.macsia.teatiers.domain.model.CatalogImage
import com.macsia.teatiers.domain.model.CatalogName
import com.macsia.teatiers.domain.model.CatalogProvenance
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import com.macsia.teatiers.domain.model.EnrichmentState
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.TeaType

/** Wire enum string -> app [TeaType]; an unknown/new server value folds to [TeaType.OTHER]. */
fun teaTypeFromWire(raw: String): TeaType =
    runCatching { TeaType.valueOf(raw) }.getOrDefault(TeaType.OTHER)

/** Wire flavor enum -> app [FlavorDimension]; an unknown axis the client doesn't model yet is dropped. */
fun flavorDimensionFromWire(raw: String): FlavorDimension? =
    runCatching { FlavorDimension.valueOf(raw) }.getOrNull()

/**
 * Wire enrichment state -> app [EnrichmentState]; null/absent (not LLM-managed) or an unknown value
 * maps to null. The server only ever sends PENDING/DONE/FAILED — QUEUED/NONE are client-only.
 */
fun enrichmentStateFromWire(raw: String?): EnrichmentState? =
    raw?.let { runCatching { EnrichmentState.valueOf(it) }.getOrNull() }

fun TeaNameDto.toDomain(): CatalogName =
    CatalogName(locale = locale, name = name, isPrimary = primary)

fun TeaSummaryDto.toDomain(): CatalogTea = CatalogTea(
    id = id,
    type = teaTypeFromWire(type),
    originCountry = originCountry,
    brand = brand,
    verificationStatus = verificationStatus,
    names = names.map { it.toDomain() },
)

private fun TeaDescriptionDto.toDomain(): CatalogDescription =
    CatalogDescription(locale = locale, short = short, full = full, source = source, license = license)

private fun TeaImageDto.toDomain(): CatalogImage =
    CatalogImage(url = url, license = license, sourceUrl = sourceUrl)

private fun TeaProvenanceDto.toDomain(): CatalogProvenance =
    CatalogProvenance(
        source = source,
        sourceUrl = sourceUrl,
        license = license,
        verificationStatus = verificationStatus,
        confidence = confidence,
    )

fun TeaDetailDto.toDomain(): CatalogTeaDetail = CatalogTeaDetail(
    id = id,
    type = teaTypeFromWire(type),
    originCountry = originCountry,
    region = region,
    cultivar = cultivar,
    oxidationMin = oxidationMin,
    oxidationMax = oxidationMax,
    brand = brand,
    image = image?.toDomain(),
    names = names.map { it.toDomain() },
    // A description with neither short nor full text carries no content — drop it.
    descriptions = descriptions
        .filter { !it.short.isNullOrBlank() || !it.full.isNullOrBlank() }
        .map { it.toDomain() },
    // Unknown axes are dropped; intensity is clamped to the shared 0..5 scale the radar expects.
    flavors = flavors.mapNotNull { dto ->
        flavorDimensionFromWire(dto.dimension)?.let { dim ->
            FlavorScore(dimension = dim, intensity = dto.intensity.coerceIn(0, 5))
        }
    },
    provenance = provenance?.toDomain()
        ?: CatalogProvenance(
            source = "unknown",
            sourceUrl = null,
            license = null,
            verificationStatus = "unverified",
            confidence = null,
        ),
    enrichmentState = enrichmentStateFromWire(enrichmentState),
)
