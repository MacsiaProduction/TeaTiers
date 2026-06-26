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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.TeaPhoto

private val ThumbSize = 96.dp

/**
 * Editable photo strip on the add/edit screen (decisions.md #43).
 *
 * - Long-press a thumbnail to start dragging; release once it has moved past the neighbouring
 *   thumb to swap their order (mirrors the tier-list drag pattern, decisions.md #38). Reorder
 *   math runs locally; the parent commits the new order via [onReorder] on drop.
 * - The "+" tile launches the system photo picker (`PickVisualMedia`) — no manifest permission
 *   needed because the picker fronts the user-grant for whichever single picture they choose.
 *   On API <33, androidx falls back to `OPEN_DOCUMENT`, also permission-free.
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
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? -> uri?.let(onPick) }

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
                    onClick = {
                        pickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            }
        }
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

    Box(
        modifier = Modifier
            .size(ThumbSize)
            .onGloballyPositioned { state.recordCenter(photo.id, it.boundsInParent().center.x) }
            .graphicsLayer { translationX = animatedOffsetX }
            .pointerInput(photo.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { state.startDrag(photo.id) },
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
 * Fallback for a photo that can't be loaded (file gone, gallery permission revoked, an offline
 * catalog image). The glyph scales with its container — capped — so it reads on a small thumbnail
 * and a full-screen view alike.
 */
@Composable
fun PhotoLoadError(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val iconSize = (minOf(maxWidth, maxHeight) * 0.4f).coerceIn(14.dp, 40.dp)
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = stringResource(R.string.a11y_photo_failed),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
    }
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
