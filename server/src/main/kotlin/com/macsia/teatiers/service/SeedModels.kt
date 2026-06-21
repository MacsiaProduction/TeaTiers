package com.macsia.teatiers.service

import com.macsia.teatiers.domain.FlavorDimension
import com.macsia.teatiers.domain.TeaType
import java.util.UUID

/** Shape of `resources/seed/catalog-seed.json` (curated, own-authored; deserialized by Jackson). */
data class SeedBundle(
    val version: Int,
    val teas: List<SeedTea>,
)

data class SeedTea(
    // Frozen client-facing identity (decision #137-C1): the seed pins a stable public_id per record so a
    // blank-database rebuild reproduces byte-identical public ids. Absent -> a random one (legacy rows only).
    val publicId: UUID? = null,
    val type: TeaType,
    val originCountry: String? = null,
    val region: String? = null,
    val cultivar: String? = null,
    val oxidationMin: Int? = null,
    val oxidationMax: Int? = null,
    val brand: String? = null,
    val wikidataQid: String? = null,
    val source: String = "curated",
    val sourceUrl: String? = null,
    val license: String? = null,
    val verificationStatus: String = "verified",
    val names: List<SeedName>,
    val descriptions: List<SeedDescription> = emptyList(),
    val flavors: List<SeedFlavor> = emptyList(),
    val images: List<SeedImage> = emptyList(),
)

data class SeedName(
    val locale: String,
    val name: String,
    val isPrimary: Boolean = false,
)

data class SeedDescription(
    val locale: String,
    val short: String? = null,
    val full: String? = null,
    val source: String? = null,
    val license: String? = null,
)

data class SeedFlavor(
    val dimension: FlavorDimension,
    val intensity: Int,
)

data class SeedImage(
    val url: String,
    val license: String? = null,
    val sourceUrl: String? = null,
    val source: String? = null,
)
