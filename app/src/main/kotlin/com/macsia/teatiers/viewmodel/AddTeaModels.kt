package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.GeoProvider
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import java.util.UUID

/** Which kind of purchase location the add form is editing. */
enum class PurchaseKind { TEXT, MARKETPLACE, GEO }

/** Flavor axes offered in the add form's quick-rate mode (decisions.md #28), not all eleven. */
val QuickRateDimensions: List<FlavorDimension> = listOf(
    FlavorDimension.SWEETNESS,
    FlavorDimension.BITTERNESS,
    FlavorDimension.ASTRINGENCY,
    FlavorDimension.FLORAL,
    FlavorDimension.FRUITINESS,
    FlavorDimension.ROASTED,
)

/** Editable draft for one optional purchase location. */
data class PurchaseDraft(
    val kind: PurchaseKind = PurchaseKind.TEXT,
    val label: String = "",
    val text: String = "",
    val url: String = "",
    val provider: GeoProvider = GeoProvider.OSM,
    val latitude: String = "",
    val longitude: String = "",
)

/** Mutable state of the add-tea form. [isValid] gates the Save action. */
data class AddTeaForm(
    val nameRu: String = "",
    val nameEn: String = "",
    val pinyin: String = "",
    val nameZh: String = "",
    val type: TeaType = TeaType.GREEN,
    val origin: String = "",
    val notes: String = "",
    val flavors: Map<FlavorDimension, Int> = emptyMap(),
    val tierId: String? = null,
    val includePurchase: Boolean = false,
    val purchase: PurchaseDraft = PurchaseDraft(),
) {
    val isValid: Boolean get() = nameRu.isNotBlank()
}

fun PurchaseDraft.toLocation(): PurchaseLocation? {
    val cleanLabel = label.trim().ifBlank { null }
    return when (kind) {
        PurchaseKind.TEXT ->
            text.trim().ifBlank { null }?.let { PurchaseLocation.FreeText(it, cleanLabel) }
        PurchaseKind.MARKETPLACE ->
            url.trim().ifBlank { null }?.let { PurchaseLocation.Marketplace(it, cleanLabel) }
        PurchaseKind.GEO -> {
            val lat = latitude.trim().toDoubleOrNull()
            val lng = longitude.trim().toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                PurchaseLocation.Geo(provider, lat, lng, cleanLabel)
            } else {
                null
            }
        }
    }
}

fun AddTeaForm.toTea(): Tea {
    val flavorScores = flavors
        .filterValues { it > 0 }
        .map { (dimension, intensity) -> FlavorScore(dimension, intensity) }
    return Tea(
        id = "user-${UUID.randomUUID()}",
        nameRu = nameRu.trim(),
        nameZh = nameZh.trim().ifBlank { null },
        pinyin = pinyin.trim().ifBlank { null },
        nameEn = nameEn.trim().ifBlank { null },
        type = type,
        origin = origin.trim().ifBlank { null },
        flavor = flavorScores,
        notes = notes.trim().ifBlank { null },
        purchaseLocations = if (includePurchase) listOfNotNull(purchase.toLocation()) else emptyList(),
    )
}
