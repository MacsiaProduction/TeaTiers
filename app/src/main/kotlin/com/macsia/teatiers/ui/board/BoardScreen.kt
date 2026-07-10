package com.macsia.teatiers.ui.board

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.isRetryable
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.ui.components.FlavorRadar
import com.macsia.teatiers.ui.components.FlavorStrip
import com.macsia.teatiers.ui.components.LiquorSwatch
import com.macsia.teatiers.ui.components.TeaCard
import com.macsia.teatiers.ui.components.TypeChip
import com.macsia.teatiers.ui.theme.TeaTheme
import com.macsia.teatiers.ui.theme.pickOnColorArgb
import com.macsia.teatiers.viewmodel.BoardUiState
import com.macsia.teatiers.viewmodel.BoardViewModel
import com.macsia.teatiers.viewmodel.CollectUiEvents
import com.macsia.teatiers.viewmodel.TierWithPlacements
import com.macsia.teatiers.viewmodel.toShareText
import kotlin.math.roundToInt

private val ScreenInset = 16.dp

/** A place a placement can be moved to via the accessibility menu: a tier (by id) or the tray. */
private data class MoveTarget(val tierId: String?, val label: String)

/** Per-card overflow callbacks; gathered here so the menu items stay short. */
private data class PlacementMenuActions(
    val onRemoveFromBoard: (placementId: String) -> Unit,
    val onDeleteEverywhere: (teaId: String) -> Unit,
    val onRetryEnrichment: (teaId: String) -> Unit,
    val onAddAnother: (catalogTeaId: Long) -> Unit,
)

@Composable
fun BoardScreen(
    boardId: String,
    onBack: () -> Unit,
    onOpenTea: (String) -> Unit,
    onAddTea: () -> Unit,
    onAddAnother: (catalogTeaId: Long) -> Unit,
    onEditTiers: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BoardViewModel = hiltViewModel(),
) {
    LaunchedEffect(boardId) { viewModel.bind(boardId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    CollectUiEvents(viewModel.events, snackbarHostState)
    BoardContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onOpenTea = onOpenTea,
        onAddTea = onAddTea,
        onAddAnother = onAddAnother,
        onEditTiers = onEditTiers,
        onMove = viewModel::movePlacement,
        onRemovePlacement = viewModel::removePlacement,
        onDeleteTea = viewModel::deleteTea,
        onRetryEnrichment = viewModel::retryEnrichment,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardContent(
    state: BoardUiState?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenTea: (String) -> Unit,
    onAddTea: () -> Unit,
    onAddAnother: (Long) -> Unit,
    onEditTiers: () -> Unit,
    onMove: (String, String?, Int) -> Unit,
    onRemovePlacement: (String) -> Unit,
    onDeleteTea: (String) -> Unit,
    onRetryEnrichment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ramp = TeaTheme.colors.tierRamp
    val featured = state?.tiers
        ?.firstOrNull { it.placements.isNotEmpty() }
        ?.placements
        ?.firstOrNull()
        ?.tea
    val dragState = remember { BoardDragState() }

    val unrankedLabel = stringResource(R.string.board_unranked)
    val moveTargets = remember(state?.tiers, unrankedLabel) {
        (state?.tiers?.map { MoveTarget(it.tier.id, it.tier.label) }.orEmpty()) + MoveTarget(null, unrankedLabel)
    }
    val groupOrder: (String) -> List<String> = { key ->
        if (key == UnrankedKey) {
            state?.unranked?.map { it.placementId }.orEmpty()
        } else {
            state?.tiers?.firstOrNull { it.tier.id == key }?.placements?.map { it.placementId }.orEmpty()
        }
    }
    val menuActions = PlacementMenuActions(
        onRemoveFromBoard = onRemovePlacement,
        onDeleteEverywhere = onDeleteTea,
        onRetryEnrichment = onRetryEnrichment,
        onAddAnother = onAddAnother,
    )

    Box(modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(state?.boardName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.a11y_back),
                            )
                        }
                    },
                    actions = {
                        if (state != null) {
                            val context = LocalContext.current
                            val shareChooserTitle = stringResource(R.string.board_share_chooser)
                            IconButton(onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TITLE, state.boardName)
                                    putExtra(Intent.EXTRA_TEXT, state.toShareText(unrankedLabel))
                                }
                                context.startActivity(Intent.createChooser(send, shareChooserTitle))
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.a11y_board_share),
                                )
                            }
                            TextButton(onClick = onEditTiers) {
                                Text(stringResource(R.string.tier_editor_action))
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onAddTea,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_tea_action)) },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { innerPadding ->
            if (state == null) {
                // UX3-P2-1: a spinner while the board loads, matching Boards/Detail/Browse — this was
                // the one screen that showed a blank scaffold during the cold-start read.
                Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }
            val isFullyEmpty = state.tiers.all { it.placements.isEmpty() } && state.unranked.isEmpty()
            if (isFullyEmpty) {
                EmptyBoard(
                    onAddTea = onAddTea,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
                return@Scaffold
            }
            val hasAnyRanked = state.tiers.any { it.placements.isNotEmpty() }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (featured != null) {
                    item(key = "featured") {
                        FeaturedTea(
                            tea = featured,
                            onClick = { onOpenTea(featured.id) },
                            modifier = Modifier.padding(horizontal = ScreenInset),
                        )
                    }
                }
                items(state.tiers, key = { it.tier.id }) { row ->
                    val rampColor = row.tier.colorArgb?.let { Color(it) }
                        ?: ramp.getOrElse(row.tier.position) { ramp.last() }
                    TierRow(
                        tierWithPlacements = row,
                        rampColor = rampColor,
                        dragState = dragState,
                        moveTargets = moveTargets,
                        groupOrder = groupOrder,
                        onOpenTea = onOpenTea,
                        onMove = onMove,
                        menuActions = menuActions,
                    )
                }
                // Hide the unranked tray once everything is ranked — celebrating an empty
                // tray adds noise. Keep it visible while there is anything unranked OR while
                // the board has no ranked teas at all (so a brand-new tea can land somewhere).
                if (state.unranked.isNotEmpty() || !hasAnyRanked) {
                    item(key = "unranked") {
                        UnrankedSection(
                            placements = state.unranked,
                            dragState = dragState,
                            moveTargets = moveTargets,
                            groupOrder = groupOrder,
                            onOpenTea = onOpenTea,
                            onMove = onMove,
                            menuActions = menuActions,
                        )
                    }
                }
            }
        }

        // Floating ghost that follows the finger; positioned in the layout phase so a drag move
        // relayouts the ghost only, without recomposing the board. The lift (scale + alpha) is
        // spring-driven so the pickup/drop reads as a "weight shift" instead of a hard pop.
        dragState.dragged?.let { dragged ->
            val liftScale by animateFloatAsState(
                targetValue = 1.06f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
                label = "ghost-lift",
            )
            Box(
                modifier = Modifier
                    .offset {
                        val o = dragState.ghostTopLeft
                        IntOffset(o.x.roundToInt(), o.y.roundToInt())
                    }
                    .zIndex(1f)
                    .graphicsLayer {
                        alpha = 0.96f
                        scaleX = liftScale
                        scaleY = liftScale
                    },
            ) {
                TeaCard(tea = dragged.placement.tea)
            }
        }
    }
}

@Composable
private fun FeaturedTea(tea: Tea, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val liquor = TeaTheme.colors.liquorByType[tea.type] ?: MaterialTheme.colorScheme.secondary

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiquorSwatch(type = tea.type, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tea.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tea.secondaryName.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tea.secondaryName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    TypeChip(type = tea.type)
                }
            }
            tea.shortBlurb?.let { blurb ->
                Spacer(Modifier.height(14.dp))
                Text(
                    text = blurb,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                tea.flavor.size >= 3 -> {
                    Spacer(Modifier.height(18.dp))
                    FlavorRadar(
                        flavors = tea.flavor,
                        accent = liquor,
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    )
                }
                tea.flavor.isNotEmpty() -> {
                    Spacer(Modifier.height(16.dp))
                    FlavorStrip(flavors = tea.flavor, accent = liquor, max = 5, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TierRow(
    tierWithPlacements: TierWithPlacements,
    rampColor: Color,
    dragState: BoardDragState,
    moveTargets: List<MoveTarget>,
    groupOrder: (String) -> List<String>,
    onOpenTea: (String) -> Unit,
    onMove: (String, String?, Int) -> Unit,
    menuActions: PlacementMenuActions,
) {
    val key = groupKey(tierWithPlacements.tier.id)
    val onRamp = Color(pickOnColorArgb(rampColor.luminance()))
    val highlightColor = MaterialTheme.colorScheme.primary
    // The hover highlight fades in/out so a fast drag does not flicker the rectangle on/off.
    val highlightAlpha by animateFloatAsState(
        targetValue = if (dragState.hoverKey == key) 0.12f else 0f,
        animationSpec = spring(stiffness = 800f),
        label = "tier-hover-alpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = ScreenInset)
            .onGloballyPositioned { dragState.reportGroupBounds(key, it.boundsInRoot()) }
            .drawBehind {
                if (highlightAlpha > 0f) {
                    drawRoundRect(
                        color = highlightColor.copy(alpha = highlightAlpha),
                        cornerRadius = CornerRadius(20f, 20f),
                    )
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .fillMaxHeight()
                .heightIn(min = 64.dp)
                .clip(MaterialTheme.shapes.small)
                .background(rampColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = tierWithPlacements.tier.label,
                color = onRamp,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (tierWithPlacements.placements.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.tier_empty_drop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // animateContentSize "breathes" the tier row when a placement enters or leaves the
            // group: the row width animates from old to new instead of jump-cutting. Cheaper
            // than per-card placement animations and reads as one cohesive motion.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .animateContentSize(
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                    ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Computed once per tier recomposition, not once per card — `groupOrder(key)` did the
                // same firstOrNull-scan-plus-map redundantly for every card in this tier (post-merge
                // review). The placements here already ARE that tier's order.
                val order = remember(tierWithPlacements.placements) {
                    tierWithPlacements.placements.map { it.placementId }
                }
                tierWithPlacements.placements.forEach { placement ->
                    DraggableTeaCard(
                        placement = placement,
                        currentKey = key,
                        order = order,
                        dragState = dragState,
                        moveTargets = moveTargets,
                        groupOrder = groupOrder,
                        onOpenTea = onOpenTea,
                        onMove = onMove,
                        menuActions = menuActions,
                    )
                }
            }
        }
    }
}

/**
 * Shown when a board has zero placements (no tiers populated and no unranked teas) — the
 * default "Пока пусто" / drop-hint chrome reads as broken on a brand-new board. A centered
 * card with an explicit CTA leaves no ambiguity about the next step.
 */
@Composable
private fun EmptyBoard(onAddTea: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.board_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.board_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onAddTea) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_tea_action))
            }
        }
    }
}

@Composable
private fun UnrankedSection(
    placements: List<Placement>,
    dragState: BoardDragState,
    moveTargets: List<MoveTarget>,
    groupOrder: (String) -> List<String>,
    onOpenTea: (String) -> Unit,
    onMove: (String, String?, Int) -> Unit,
    menuActions: PlacementMenuActions,
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    val highlightAlpha by animateFloatAsState(
        targetValue = if (dragState.hoverKey == UnrankedKey) 0.12f else 0f,
        animationSpec = spring(stiffness = 800f),
        label = "unranked-hover-alpha",
    )
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.board_unranked),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = ScreenInset),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .onGloballyPositioned { dragState.reportGroupBounds(UnrankedKey, it.boundsInRoot()) }
                .drawBehind {
                    if (highlightAlpha > 0f) {
                        drawRoundRect(
                            color = highlightColor.copy(alpha = highlightAlpha),
                            cornerRadius = CornerRadius(20f, 20f),
                        )
                    }
                }
                .horizontalScroll(rememberScrollState())
                .animateContentSize(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.width(ScreenInset))
            if (placements.isEmpty()) {
                Text(
                    text = stringResource(R.string.tier_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val order = remember(placements) { placements.map { it.placementId } }
                placements.forEach { placement ->
                    DraggableTeaCard(
                        placement = placement,
                        currentKey = UnrankedKey,
                        order = order,
                        dragState = dragState,
                        moveTargets = moveTargets,
                        groupOrder = groupOrder,
                        onOpenTea = onOpenTea,
                        onMove = onMove,
                        menuActions = menuActions,
                    )
                }
            }
            Spacer(Modifier.width(ScreenInset))
        }
    }
}

/**
 * A [TeaCard] that opens the tea on tap, is picked up by a long press to drag between tiers,
 * and offers an overflow with "Убрать с подборки" / "Удалить чай совсем". The original is
 * hidden (alpha 0) while its ghost is dragged. TalkBack users get one custom action per other
 * group ("move to tier X" / "to unranked"), so ranking never requires a drag.
 */
@Composable
private fun DraggableTeaCard(
    placement: Placement,
    currentKey: String,
    order: List<String>,
    dragState: BoardDragState,
    moveTargets: List<MoveTarget>,
    groupOrder: (String) -> List<String>,
    onOpenTea: (String) -> Unit,
    onMove: (String, String?, Int) -> Unit,
    menuActions: PlacementMenuActions,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val moveToTierTemplate = stringResource(R.string.a11y_move_to_tier)
    val moveToUnrankedLabel = stringResource(R.string.a11y_move_to_unranked)
    val removeFromBoardLabel = stringResource(R.string.a11y_remove_placement)
    val deleteTeaLabel = stringResource(R.string.a11y_delete_tea)
    val moveLeftLabel = stringResource(R.string.a11y_move_left_in_tier)
    val moveRightLabel = stringResource(R.string.a11y_move_right_in_tier)
    // UX2-P1-1: reordering within the SAME tier had no non-drag fallback — the cross-tier "move
    // to tier X" actions below explicitly exclude the current group. currentTierId is the real
    // (nullable) tier id backing currentKey (groupKey maps null -> UnrankedKey for the tray).
    val currentTierId = currentKey.takeIf { it != UnrankedKey }
    val myIndex = order.indexOf(placement.placementId)
    val actions = remember(
        currentKey, moveTargets, placement.placementId, onMove, menuActions, myIndex, order.size,
    ) {
        val reorderActions = buildList {
            if (myIndex > 0) {
                add(CustomAccessibilityAction(moveLeftLabel) { onMove(placement.placementId, currentTierId, myIndex - 1); true })
            }
            if (myIndex in 0 until order.size - 1) {
                add(CustomAccessibilityAction(moveRightLabel) { onMove(placement.placementId, currentTierId, myIndex + 1); true })
            }
        }
        val moveActions = moveTargets
            .filter { groupKey(it.tierId) != currentKey }
            .map { target ->
                val label =
                    if (target.tierId == null) moveToUnrankedLabel else moveToTierTemplate.format(target.label)
                // Int.MAX_VALUE => append to the end of the target group (the repository clamps it).
                CustomAccessibilityAction(label) {
                    onMove(placement.placementId, target.tierId, Int.MAX_VALUE)
                    true
                }
            }
        val removeAction = CustomAccessibilityAction(removeFromBoardLabel) {
            menuActions.onRemoveFromBoard(placement.placementId)
            true
        }
        val deleteAction = CustomAccessibilityAction(deleteTeaLabel) {
            menuActions.onDeleteEverywhere(placement.tea.id)
            true
        }
        reorderActions + moveActions + removeAction + deleteAction
    }

    Box(
        modifier = Modifier
            .onGloballyPositioned { c ->
                coords = c
                dragState.reportCardCenterX(placement.placementId, c.boundsInRoot().center.x)
            }
            .graphicsLayer { alpha = if (dragState.draggedPlacementId == placement.placementId) 0f else 1f }
            .pointerInput(placement.placementId) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { touch ->
                        // Confirm the pickup with a haptic tick — the gesture is otherwise invisible
                        // until the card lifts, so the cue makes drag-to-rank feel responsive.
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        dragState.start(placement, currentKey, coords?.positionInRoot() ?: Offset.Zero, touch)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragState.drag(amount)
                    },
                    onDragEnd = { dragState.end(groupOrder, onMove) },
                    onDragCancel = { dragState.cancel() },
                )
            }
            .semantics { customActions = actions },
    ) {
        Box {
            // Tap still opens the tea (keeps the ripple + click semantics); the long press above
            // starts a drag without conflicting, because it only fires after the press timeout.
            TeaCard(tea = placement.tea, onClick = { onOpenTea(placement.tea.id) })
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.a11y_card_menu),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                // UX2-P1-1: visible within-tier reorder fallback — the cross-tier moves below only
                // ever target a DIFFERENT group, so ranking two teas already in the same tier had no
                // non-drag path at all.
                if (myIndex > 0) {
                    DropdownMenuItem(
                        text = { Text(moveLeftLabel) },
                        onClick = {
                            menuExpanded = false
                            onMove(placement.placementId, currentTierId, myIndex - 1)
                        },
                    )
                }
                if (myIndex in 0 until order.size - 1) {
                    DropdownMenuItem(
                        text = { Text(moveRightLabel) },
                        onClick = {
                            menuExpanded = false
                            onMove(placement.placementId, currentTierId, myIndex + 1)
                        },
                    )
                }
                // Visible move-to-tier fallback so ranking never depends on discovering the
                // long-press drag (audit #2). Mirrors the TalkBack custom actions above; appends
                // to the end of the target group (Int.MAX_VALUE; the repository clamps it).
                moveTargets
                    .filter { groupKey(it.tierId) != currentKey }
                    .forEach { target ->
                        val label = if (target.tierId == null) {
                            moveToUnrankedLabel
                        } else {
                            moveToTierTemplate.format(target.label)
                        }
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuExpanded = false
                                onMove(placement.placementId, target.tierId, Int.MAX_VALUE)
                            },
                        )
                    }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_from_board)) },
                    onClick = {
                        menuExpanded = false
                        menuActions.onRemoveFromBoard(placement.placementId)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete_tea_forever)) },
                    onClick = {
                        menuExpanded = false
                        confirmDelete = true
                    },
                )
                // P1-1 "add another sample" (#132): only for catalog-linked teas — a custom add already
                // always creates a new sample, so reuse isn't in play there.
                placement.tea.catalogTeaId?.let { catalogTeaId ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_add_another_sample)) },
                        onClick = {
                            menuExpanded = false
                            menuActions.onAddAnother(catalogTeaId)
                        },
                    )
                }
                // Retry is offered for any deferred/failed state (UX3-P1-2), not just FAILED — a
                // QUEUED (offline) or RATE_LIMITED tea otherwise had no user-drivable retry until the
                // manager's 5-min resume cooldown elapsed. retry() bypasses that cooldown per-tea.
                if (placement.tea.enrichmentState.isRetryable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_retry_enrichment)) },
                        onClick = {
                            menuExpanded = false
                            menuActions.onRetryEnrichment(placement.tea.id)
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_tea_confirm_title)) },
            text = {
                Text(stringResource(R.string.delete_tea_confirm_message, placement.tea.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    menuActions.onDeleteEverywhere(placement.tea.id)
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
