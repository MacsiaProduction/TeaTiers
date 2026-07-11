package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.R
import com.macsia.teatiers.data.repository.AddPhotoResult
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

/**
 * The remaining locked dimensions (decisions.md #23/#44) reachable under the "show all" toggle —
 * the full eleven minus [QuickRateDimensions], kept in enum order. Quick-rate stays the default so
 * the form is short, but every dimension the radar can draw must be enterable too.
 */
val ExtendedRateDimensions: List<FlavorDimension> =
    FlavorDimension.entries.filter { it !in QuickRateDimensions }

/**
 * Which extended dimensions to render right now. Collapsed shows only the ones the user (or an
 * imported tea) already rated above zero, so editing a tea with a recorded smokiness never hides
 * it; [expanded] reveals the rest. Pure so it is unit-testable without Compose.
 */
fun visibleExtendedDimensions(
    flavors: Map<FlavorDimension, Int>,
    expanded: Boolean,
): List<FlavorDimension> =
    if (expanded) ExtendedRateDimensions
    else ExtendedRateDimensions.filter { (flavors[it] ?: 0) > 0 }

/**
 * State of the "scan packaging" flow on the add form (slice 3). Idle = no scan; Recognizing = the
 * image is uploading/OCR'ing; Review = the recognized text is shown for the user to edit/confirm
 * before it becomes `sourceText` (the opt-in/preview guardrail, decision #100).
 */
sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Recognizing : ScanUiState
    data class Review(val text: String) : ScanUiState
}

/**
 * How many add-mode draft photos failed to materialize on save, and the specific message to show
 * (UX-P1-1) — the first failure's reason when several draft photos fail for different reasons.
 */
data class PhotoSaveFailure(val count: Int, val messageRes: Int)

/** Maps a photo-copy failure reason to the specific snackbar message the user should see. */
fun AddPhotoResult.failureMessageRes(): Int = when (this) {
    is AddPhotoResult.Added -> error("Added is not a failure")
    AddPhotoResult.TooLarge -> R.string.error_photo_too_large
    AddPhotoResult.OutOfSpace -> R.string.error_photo_out_of_space
    AddPhotoResult.Failed -> R.string.error_photo_copy_failed
}

/** Editable draft for one purchase location row in the add/edit form. */
data class PurchaseDraft(
    val kind: PurchaseKind = PurchaseKind.TEXT,
    val label: String = "",
    val text: String = "",
    val url: String = "",
) {
    /**
     * UX2-P2-19: a marketplace URL had zero shape validation — any text was accepted just as
     * readily as a real link. Optional field, so this flags but never blocks Save. stdlib-only
     * (`java.net.URI`, not `android.util.Patterns`) so it stays testable in plain JUnit5.
     */
    val urlError: Boolean
        get() = kind == PurchaseKind.MARKETPLACE && url.isNotBlank() && !isPlausibleUrl(url.trim())

    private fun isPlausibleUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}

/**
 * Server cap on the grounded `sourceText` blurb (mirrors the backend `MAX_SOURCE_TEXT_LENGTH`,
 * decision #64); the add form hard-stops typing/pasting here so an over-long blurb never 400s.
 */
const val SourceTextMaxLength = 4_000

/**
 * Server cap on a tea name sent to `POST /resolve` (mirrors the backend `ResolveRequestDto
 * .MAX_NAME_LENGTH`). The add form hard-stops the name fields here so an over-long paste can't save a
 * tea whose background enrichment then 400s forever with a retry that can never succeed (R4-LOC-1).
 */
const val NameMaxLength = 200

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
    // P1-1 sample-identity (#132): which physical sample of the catalog tea this is. All optional.
    val vendor: String = "",
    val product: String = "",
    val harvestYear: String = "",
    val batch: String = "",
    val grade: String = "",
    // Set when the user prefilled from a catalog result; the strongest local identity key (#42) so the
    // tea dedups by catalog id, not just by name, and skips a redundant background re-resolve.
    val catalogTeaId: Long? = null,
    // Optional pasted vendor/packaging blurb that grounds background enrichment of a typed tea (#25).
    // Transient add-mode input only: sent once with /resolve, never written to Room (raw vendor text
    // is not stored locally or republished), so it is intentionally absent from toTea()/toForm().
    val sourceText: String = "",
) {
    // P1-2 (#132): a sample is valid with ≥1 non-blank name in ANY locale — ru is no longer required.
    val isValid: Boolean get() = listOf(nameRu, nameEn, pinyin, nameZh).any { it.isNotBlank() }

    /**
     * UX2-P2-19: the field already caps input to 4 digits (no overflow risk), but a well-formed,
     * implausible year ("0000") was still silently accepted with no feedback. Optional field, so this
     * flags but never blocks Save — matching the tier-label pattern (visible, not save-blocking).
     */
    val harvestYearError: Boolean
        get() = harvestYear.isNotBlank() &&
            (harvestYear.toIntOrNull() ?: -1) !in MinPlausibleHarvestYear..(java.time.Year.now().value + 1)

    private companion object {
        const val MinPlausibleHarvestYear = 1800
    }
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
        // P1-2: a blank ru name becomes null so the display resolver falls through to another locale.
        // NameMaxLength cap here is the single choke point (R4-LOC-1): the add-form fields cap on input,
        // but catalog-pick / "add manually from query" write the form programmatically — capping at the
        // save boundary keeps every path under the server's /resolve limit so enrichment can't 400 forever.
        nameRu = nameRu.trim().take(NameMaxLength).ifBlank { null },
        nameZh = nameZh.trim().take(NameMaxLength).ifBlank { null },
        pinyin = pinyin.trim().take(NameMaxLength).ifBlank { null },
        nameEn = nameEn.trim().take(NameMaxLength).ifBlank { null },
        type = type,
        origin = origin.trim().ifBlank { null },
        flavor = flavorScores,
        notes = notes.trim().ifBlank { null },
        vendor = vendor.trim().ifBlank { null },
        product = product.trim().ifBlank { null },
        harvestYear = harvestYear.trim().toIntOrNull(),
        batch = batch.trim().ifBlank { null },
        grade = grade.trim().ifBlank { null },
        purchaseLocations = purchases.mapNotNull { it.toLocation() },
        catalogTeaId = catalogTeaId,
    )
}

/**
 * Prefill the form for editing [Tea]. The tier picker is hidden in edit mode (drag-to-rank owns
 * placement), so [AddTeaForm.tierId] stays null and is ignored on save.
 */
fun Tea.toForm(): AddTeaForm = AddTeaForm(
    nameRu = nameRu.orEmpty(),
    nameEn = nameEn.orEmpty(),
    pinyin = pinyin.orEmpty(),
    nameZh = nameZh.orEmpty(),
    type = type,
    origin = origin.orEmpty(),
    notes = notes.orEmpty(),
    flavors = flavor.associate { it.dimension to it.intensity },
    tierId = null,
    purchases = purchaseLocations.map { it.toDraft() },
    catalogTeaId = catalogTeaId,
    vendor = vendor.orEmpty(),
    product = product.orEmpty(),
    harvestYear = harvestYear?.toString().orEmpty(),
    batch = batch.orEmpty(),
    grade = grade.orEmpty(),
)
