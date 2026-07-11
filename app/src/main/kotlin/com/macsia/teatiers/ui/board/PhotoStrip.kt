package com.macsia.teatiers.ui.board

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.TeaPhoto
import com.macsia.teatiers.ui.components.PhotoLoadError
import com.macsia.teatiers.ui.components.SourceChooserDialog
import java.io.File

private val ThumbSize = 96.dp

/** Max photos per multi-select (UX3-P2-11). Matches the UiEvent channel capacity so even an all-fail
 *  batch in edit mode can't drop a per-photo failure snackbar. */
private const val MAX_PHOTO_PICK = 8

/**
 * Editable photo strip on the add/edit screen (decisions.md #43).
 *
 * - Long-press a thumbnail to start dragging; release once it has moved past the neighbouring
 *   thumb to swap their order (mirrors the tier-list drag pattern, decisions.md #38). Reorder
 *   math runs locally; the parent commits the new order via [onReorder] on drop.
 * - The "+" tile launches the system photo picker (`PickMultipleVisualMedia`, UX2-F-2) — no manifest
 *   permission needed because the picker fronts the user-grant for whichever pictures they choose.
 *   On API <33, androidx falls back to `OPEN_DOCUMENT`, also permission-free. [onPick] still takes
 *   one [Uri] at a time (unchanged call sites) — a multi-selection just fans out one call per photo.
 * - Tapping the "x" badge prompts a confirm before [onRemove] — destructive even though we can
 *   re-pick, because the photo file goes with the row.
 */
@Composable
fun PhotoStripField(
    photos: List<TeaPhoto>,
    onPick: (Uri) -> Unit,
    onRemove: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // UX3-P2-11: cap the multi-select so a mass-add can't outrun the one-snackbar-per-failure event
    // channel (and keeps a tea's photo set sane). The system picker enforces the same ceiling itself.
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PHOTO_PICK),
    ) { uris: List<Uri> -> uris.forEach(onPick) }

    // UX2-F-1: photographing the tea directly (tin, leaves, packaging) had no entry point — only
    // the gallery picker above. Mirrors the OCR scan flow's camera launcher (AddTeaScreen.kt), but
    // the captured file is kept (not discarded): onPick copies it into the tea's own photo storage.
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success -> if (success) pendingCameraUri?.let(onPick) }
    var showSourceChooser by remember { mutableStateOf(false) }

    var pendingRemove by remember { mutableStateOf<TeaPhoto?>(null) }
    val ids = photos.map(TeaPhoto::id)
    val state = remember(ids) { PhotoStripDragState(ids) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.photos_section_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ThumbSize + 16.dp),
        ) {
            // animateContentSize lets the inner Row breathe when a thumb is added or removed:
            // the strip width animates and the trailing tiles slide in/out instead of popping.
            // We still cannot get a true exit animation on the removed thumbnail without
            // converting to LazyRow, but the layout-level animation is enough to read as
            // intentional motion.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
                    .animateContentSize(
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                photos.forEachIndexed { index, photo ->
                    PhotoThumbnail(
                        photo = photo,
                        index = index,
                        total = photos.size,
                        state = state,
                        onCommitOrder = onReorder,
                        onRemove = { pendingRemove = photo },
                        // TalkBack reorder fallback: drag is unreachable without sight (audit #6).
                        onMoveLeft = { onReorder(ids.swapped(index, index - 1)) },
                        onMoveRight = { onReorder(ids.swapped(index, index + 1)) },
                    )
                }
                AddPhotoTile(
                    onClick = { showSourceChooser = true },
                )
            }
        }
    }

    if (showSourceChooser) {
        // Camera vs gallery are two equal, non-hierarchical choices (UX2-P1-11's convention): both
        // live as rows in the body, and dismissButton is a real Cancel — not overloaded confirm/dismiss.
        SourceChooserDialog(
            title = stringResource(R.string.photos_add),
            onDismissRequest = { showSourceChooser = false },
            onCamera = {
                showSourceChooser = false
                val uri = captureUri(context, subDir = "photo-captures", filePrefix = "photo")
                pendingCameraUri = uri
                cameraLauncher.launch(uri)
            },
            onGallery = {
                showSourceChooser = false
                pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }

    pendingRemove?.let { photo ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.confirm_remove_photo_title)) },
            text = { Text(stringResource(R.string.confirm_remove_photo_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    onRemove(photo.id)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * A fresh FileProvider URI for the camera to write into, under `cacheDir/[subDir]/` (exposed in
 * `file_paths.xml`). Old files in that subdir are cleared first so captures don't accumulate.
 * Shared by [PhotoStripField]'s own photo capture (UX2-F-1, `subDir = "photo-captures"`, kept —
 * [onPick] copies it into permanent storage right away) and AddTeaScreen's OCR scan capture
 * (`subDir = "scans"`, discarded after recognition — the two never share a directory).
 */
internal fun captureUri(context: android.content.Context, subDir: String, filePrefix: String): Uri {
    val dir = File(context.cacheDir, subDir).apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }
    val file = File(dir, "$filePrefix-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
private fun PhotoThumbnail(
    photo: TeaPhoto,
    index: Int,
    total: Int,
    state: PhotoStripDragState,
    onCommitOrder: (List<String>) -> Unit,
    onRemove: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
) {
    val a11yLabel = stringResource(R.string.a11y_photo_thumbnail, index + 1, total)
    val removeLabel = stringResource(R.string.a11y_remove_photo)
    val moveLeftLabel = stringResource(R.string.a11y_photo_move_left)
    val moveRightLabel = stringResource(R.string.a11y_photo_move_right)
    val isDragging = state.draggedId == photo.id

    // While the finger is down we follow it 1:1 (snap spec); when the user releases the
    // thumbnail we spring the offset back to 0 so a no-op drop glides instead of teleports.
    val animatedOffsetX by animateFloatAsState(
        targetValue = if (isDragging) state.dragOffsetXPx else 0f,
        animationSpec = if (isDragging) snap() else spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "photo-thumb-offset",
    )
    val a11yActions = remember(photo.id, index, total, onRemove, onMoveLeft, onMoveRight) {
        buildList {
            if (index > 0) add(CustomAccessibilityAction(moveLeftLabel) { onMoveLeft(); true })
            if (index < total - 1) add(CustomAccessibilityAction(moveRightLabel) { onMoveRight(); true })
            add(CustomAccessibilityAction(removeLabel) { onRemove(); true })
        }
    }
    // R4-VIS-4: confirm the long-press pickup with a haptic, matching the board tea-card drag (which
    // this gesture mirrors, decisions.md #38) — the two long-press drags otherwise felt different.
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .size(ThumbSize)
            .onGloballyPositioned { state.recordCenter(photo.id, it.boundsInParent().center.x) }
            .graphicsLayer { translationX = animatedOffsetX }
            .pointerInput(photo.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.startDrag(photo.id)
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        state.updateDrag(drag.x)
                    },
                    onDragEnd = {
                        val finalOrder = state.endDrag()
                        if (finalOrder != null) onCommitOrder(finalOrder)
                    },
                    onDragCancel = { state.cancelDrag() },
                )
            }
            .semantics {
                contentDescription = a11yLabel
                customActions = a11yActions
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = if (isDragging) 6.dp else 1.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            PhotoImage(uri = photo.uri, modifier = Modifier.fillMaxSize())
        }
        // 40dp touch target (Material min ~48dp; capped at 40 to fit the 96dp thumb corner) with a
        // smaller 24dp visual badge inside, so the affordance stays light but is not a mis-tap trap (audit #7).
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(40.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.a11y_remove_photo),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Visible non-drag reorder alternative (UX-P2-2): the same move the TalkBack custom actions
        // already offer, now discoverable without sight or a long-press drag gesture.
        if (index > 0) {
            ThumbnailCornerButton(
                icon = Icons.Filled.KeyboardArrowLeft,
                contentDescription = moveLeftLabel,
                onClick = onMoveLeft,
                alignment = Alignment.BottomStart,
            )
        }
        if (index < total - 1) {
            ThumbnailCornerButton(
                icon = Icons.Filled.KeyboardArrowRight,
                contentDescription = moveRightLabel,
                onClick = onMoveRight,
                alignment = Alignment.BottomEnd,
            )
        }
    }
}

@Composable
private fun BoxScope.ThumbnailCornerButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    alignment: Alignment,
) {
    IconButton(onClick = onClick, modifier = Modifier.align(alignment).size(40.dp)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun AddPhotoTile(onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(ThumbSize),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.a11y_add_photo),
            )
        }
    }
}

@Composable
fun PhotoImage(uri: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val context = LocalContext.current
    val request = remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            .crossfade(true)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        // A revoked/deleted/offline image renders a broken-image glyph instead of a silent blank
        // box — the same fallback at every size, from a strip thumb to full-screen zoom (audit #6).
        error = { PhotoLoadError(Modifier.fillMaxSize()) },
        modifier = modifier.clip(MaterialTheme.shapes.medium).background(Color.Black.copy(alpha = 0.06f)),
    )
}

/**
 * Tracks the dragged thumb id, its center-x positions, and the live drag delta. Mirrors the
 * tier-list drag pattern (decisions.md #38): geometry in parent coordinates, swap on pointer
 * crossing the neighbour's center, no recompositions during drag — only `dragOffsetXPx` flips
 * and the thumbnail reads it through `graphicsLayer`.
 *
 * The constructor seed is the *initial* visible order; [endDrag] returns the new ordered ids
 * when something actually moved (else null so the caller skips the write).
 */
/** Returns a copy with items [i] and [j] swapped; the original if either index is out of range. */
private fun List<String>.swapped(i: Int, j: Int): List<String> {
    if (i !in indices || j !in indices) return this
    return toMutableList().also {
        it[i] = this[j]
        it[j] = this[i]
    }
}

internal class PhotoStripDragState(initialOrder: List<String>) {
    private val initialOrder: List<String> = initialOrder.toList()
    private val workingOrder: MutableList<String> = initialOrder.toMutableList()
    private val centers: MutableMap<String, Float> = mutableMapOf()

    private var _draggedId by mutableStateOf<String?>(null)
    private var _dragOffsetXPx by mutableFloatStateOf(0f)

    val draggedId: String? get() = _draggedId
    val dragOffsetXPx: Float get() = _dragOffsetXPx

    fun recordCenter(id: String, x: Float) {
        centers[id] = x
    }

    fun startDrag(id: String) {
        _draggedId = id
        _dragOffsetXPx = 0f
    }

    fun updateDrag(deltaX: Float) {
        val id = _draggedId ?: return
        _dragOffsetXPx += deltaX
        val ourIndex = workingOrder.indexOf(id)
        if (ourIndex < 0) return
        val ourCenter = (centers[id] ?: return) + _dragOffsetXPx
        val neighbourIndex = when {
            deltaX > 0 && ourIndex < workingOrder.lastIndex -> ourIndex + 1
            deltaX < 0 && ourIndex > 0 -> ourIndex - 1
            else -> return
        }
        val neighbour = workingOrder[neighbourIndex]
        val neighbourCenter = centers[neighbour] ?: return
        val crossed = if (deltaX > 0) ourCenter > neighbourCenter else ourCenter < neighbourCenter
        if (crossed) {
            workingOrder[ourIndex] = neighbour
            workingOrder[neighbourIndex] = id
            // Reset offset so the next swap measures against the new slot's center.
            _dragOffsetXPx = 0f
        }
    }

    fun endDrag(): List<String>? {
        _draggedId = null
        _dragOffsetXPx = 0f
        return workingOrder.toList().takeIf { it != initialOrder }
    }

    fun cancelDrag() {
        _draggedId = null
        _dragOffsetXPx = 0f
    }
}
