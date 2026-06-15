package com.macsia.teatiers.ui.board

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.macsia.teatiers.domain.model.Placement
import kotlin.math.abs

/** Sentinel drop-group key for the unranked tray (a tier id is otherwise used). */
internal const val UnrankedKey = "__unranked__"

internal fun groupKey(tierId: String?): String = tierId ?: UnrankedKey

internal fun tierIdOfKey(key: String): String? = key.takeIf { it != UnrankedKey }

/** A placement picked up by a long press, plus where the gesture began (root coordinates). */
internal data class DraggedPlacement(
    val placement: Placement,
    val sourceKey: String,
    val cardTopLeft: Offset,
    val touch: Offset,
)

/**
 * Ephemeral state for dragging a placement between tiers. It lives in composition, not the
 * ViewModel — only the resolved drop calls back into the ViewModel. All geometry is tracked in
 * root coordinates so a card inside a horizontally-scrolling tier row can be hit-tested against
 * the vertically-stacked rows. Per-frame values (pointer/ghost/hover) are read from draw/layout
 * lambdas to avoid recomposing the board on every drag move.
 *
 * The drag handle is the placement (decisions.md #42) — a tea on board A and the same tea on
 * board B are different placements with different ids; moving one never affects the other.
 */
@Stable
internal class BoardDragState {

    var dragged by mutableStateOf<DraggedPlacement?>(null)
        private set

    private var delta by mutableStateOf(Offset.Zero)

    private val groupBounds: SnapshotStateMap<String, Rect> = mutableStateMapOf()
    private val cardCenterX: SnapshotStateMap<String, Float> = mutableStateMapOf()

    val draggedPlacementId: String? get() = dragged?.placement?.placementId

    /** Pointer position in root coordinates (start point plus accumulated drag). */
    val pointer: Offset get() = dragged?.let { it.cardTopLeft + it.touch + delta } ?: Offset.Zero

    /** Top-left of the floating ghost card in root coordinates. */
    val ghostTopLeft: Offset get() = dragged?.let { it.cardTopLeft + delta } ?: Offset.Zero

    /** Drop-group key currently under the pointer, or null when over a gap / not dragging. */
    val hoverKey: String?
        get() {
            if (dragged == null) return null
            val p = pointer
            return groupBounds.entries.firstOrNull { (_, r) -> p.y in r.top..r.bottom }?.key
        }

    fun reportGroupBounds(key: String, rect: Rect) {
        groupBounds[key] = rect
    }

    fun reportCardCenterX(placementId: String, centerX: Float) {
        cardCenterX[placementId] = centerX
    }

    fun start(placement: Placement, sourceKey: String, cardTopLeft: Offset, touch: Offset) {
        delta = Offset.Zero
        dragged = DraggedPlacement(placement, sourceKey, cardTopLeft, touch)
    }

    fun drag(amount: Offset) {
        delta += amount
    }

    fun cancel() {
        dragged = null
        delta = Offset.Zero
    }

    /**
     * Resolves the drop and calls [onMove] with (placementId, targetTierId, targetIndex) —
     * targetIndex is the slot among the *other* placements of the target group, derived from
     * card centers. Returns false when there is no active drag or no resolvable target.
     * [groupOrder] returns the ordered placement ids for a group key.
     */
    fun end(groupOrder: (String) -> List<String>, onMove: (String, String?, Int) -> Unit): Boolean {
        val active = dragged ?: return false
        val p = pointer
        val target = resolveTargetKey(p)
        if (target == null) {
            cancel()
            return false
        }
        val index = groupOrder(target)
            .filter { it != active.placement.placementId }
            .count { id -> (cardCenterX[id] ?: Float.POSITIVE_INFINITY) < p.x }
        cancel()
        onMove(active.placement.placementId, tierIdOfKey(target), index)
        return true
    }

    private fun resolveTargetKey(p: Offset): String? {
        if (groupBounds.isEmpty()) return null
        groupBounds.entries.firstOrNull { (_, r) -> p.y in r.top..r.bottom }?.let { return it.key }
        return groupBounds.entries.minByOrNull { (_, r) -> abs(p.y - r.center.y) }?.key
    }
}
