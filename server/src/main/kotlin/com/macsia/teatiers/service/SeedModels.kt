package com.macsia.teatiers.service

import com.macsia.teatiers.domain.FlavorDimension
import com.macsia.teatiers.domain.TeaType

/** Shape of `resources/seed/catalog-seed.json` (curated, own-authored; deserialized by Jackson). */
data class SeedBundle(
    val version: Int,
    val teas: List<SeedTea>,
)

data class SeedTea(
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
