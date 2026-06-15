package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import java.util.UUID

/** Which kind of purchase location the add form is editing (MVP: no map geopoint — #20). */
enum class PurchaseKind { TEXT, MARKETPLACE }

/** Flavor axes offered in the add form's quick-rate mode (decisions.md #28), not all eleven. */
val QuickRateDimensions: List<FlavorDimension> = listOf(
    FlavorDimension.SWEETNESS,
    FlavorDimension.BITTERNESS,
    FlavorDimension.ASTRINGENCY,
    FlavorDimension.FLORAL,
    FlavorDimension.FRUITINESS,
    FlavorDimension.ROASTED,
)

/** Editable draft for one purchase location row in the add/edit form. */
data class PurchaseDraft(
    val kind: PurchaseKind = PurchaseKind.TEXT,
    val label: String = "",
    val text: String = "",
    val url: String = "",
)

/** Mutable state of the add/edit form. [isValid] gates the Save action. */
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
    val purchases: List<PurchaseDraft> = emptyList(),
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
    }
}

/** Inverse of [PurchaseDraft.toLocation], for prefilling the form when editing an existing tea. */
fun PurchaseLocation.toDraft(): PurchaseDraft = when (this) {
    is PurchaseLocation.FreeText ->
        PurchaseDraft(kind = PurchaseKind.TEXT, label = label.orEmpty(), text = text)
    is PurchaseLocation.Marketplace ->
        PurchaseDraft(kind = PurchaseKind.MARKETPLACE, label = label.orEmpty(), url = url)
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
        purchaseLocations = purchases.mapNotNull { it.toLocation() },
    )
}

/**
 * Prefill the form for editing [Tea]. The tier picker is hidden in edit mode (drag-to-rank owns
 * placement), so [AddTeaForm.tierId] stays null and is ignored on save.
 */
fun Tea.toForm(): AddTeaForm = AddTeaForm(
    nameRu = nameRu,
    nameEn = nameEn.orEmpty(),
    pinyin = pinyin.orEmpty(),
    nameZh = nameZh.orEmpty(),
    type = type,
    origin = origin.orEmpty(),
    notes = notes.orEmpty(),
    flavors = flavor.associate { it.dimension to it.intensity },
    tierId = null,
    purchases = purchaseLocations.map { it.toDraft() },
)
