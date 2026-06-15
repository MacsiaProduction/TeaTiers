package com.macsia.teatiers.ui.board

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.ui.components.FlavorRadar
import com.macsia.teatiers.ui.components.FlavorStrip
import com.macsia.teatiers.ui.components.LiquorSwatch
import com.macsia.teatiers.ui.components.TeaCard
import com.macsia.teatiers.ui.components.TypeChip
import com.macsia.teatiers.ui.theme.TeaTheme
import com.macsia.teatiers.viewmodel.BoardUiState
import com.macsia.teatiers.viewmodel.BoardViewModel
import com.macsia.teatiers.viewmodel.TierWithPlacements
import kotlin.math.roundToInt

private val InkOnLight = Color(0xFF1E1B16)
private val ScreenInset = 16.dp

/** A place a placement can be moved to via the accessibility menu: a tier (by id) or the tray. */
private data class MoveTarget(val tierId: String?, val label: String)

/** Per-card overflow callbacks; gathered here so the menu items stay short. */
private data class PlacementMenuActions(
    val onRemoveFromBoard: (placementId: String) -> Unit,
    val onDeleteEverywhere: (teaId: String) -> Unit,
)

@Composable
fun BoardScreen(
    boardId: String,
    onBack: () -> Unit,
    onOpenTea: (String) -> Unit,
    onAddTea: () -> Unit,
    onEditTiers: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BoardViewModel = hiltViewModel(),
) {
    LaunchedEffect(boardId) { viewModel.bind(boardId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BoardContent(
        state = state,
        onBack = onBack,
        onOpenTea = onOpenTea,
        onAddTea = onAddTea,
        onEditTiers = onEditTiers,
        onMove = viewModel::movePlacement,
        onRemovePlacement = viewModel::removePlacement,
        onDeleteTea = viewModel::deleteTea,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardContent(
    state: BoardUiState?,
    onBack: () -> Unit,
    onOpenTea: (String) -> Unit,
    onAddTea: () -> Unit,
    onEditTiers: () -> Unit,
    onMove: (String, String?, Int) -> Unit,
    onRemovePlacement: (String) -> Unit,
    onDeleteTea: (String) -> Unit,
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
        ) { innerPadding ->
            if (state == null) {
                Box(Modifier.fillMaxSize().padding(innerPadding))
                return@Scaffold
            }
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

        // Floating ghost that follows the finger; positioned in the layout phase so a drag move
        // relayouts the ghost only, without recomposing the board.
        dragState.dragged?.let { dragged ->
            Box(
                modifier = Modifier
                    .offset {
                        val o = dragState.ghostTopLeft
                        IntOffset(o.x.roundToInt(), o.y.roundToInt())
                    }
                    .zIndex(1f)
                    .graphicsLayer {
                        alpha = 0.96f
                        scaleX = 1.04f
                        scaleY = 1.04f
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
                        text = tea.nameRu,
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
    val onRamp = if (rampColor.luminance() > 0.55f) InkOnLight else Color.White
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = ScreenInset)
            .onGloballyPositioned { dragState.reportGroupBounds(key, it.boundsInRoot()) }
            .drawBehind {
                if (dragState.hoverKey == key) {
                    drawRoundRect(color = highlight, cornerRadius = CornerRadius(20f, 20f))
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
            Text(text = tierWithPlacements.tier.label, color = onRamp, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        if (tierWithPlacements.placements.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.tier_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                tierWithPlacements.placements.forEach { placement ->
                    DraggableTeaCard(
                        placement = placement,
                        currentKey = key,
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
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
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
                    if (dragState.hoverKey == UnrankedKey) {
                        drawRoundRect(color = highlight, cornerRadius = CornerRadius(20f, 20f))
                    }
                }
                .horizontalScroll(rememberScrollState()),
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
                placements.forEach { placement ->
                    DraggableTeaCard(
                        placement = placement,
                        currentKey = UnrankedKey,
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

    val moveToTierTemplate = stringResource(R.string.a11y_move_to_tier)
    val moveToUnrankedLabel = stringResource(R.string.a11y_move_to_unranked)
    val removeFromBoardLabel = stringResource(R.string.a11y_remove_placement)
    val deleteTeaLabel = stringResource(R.string.a11y_delete_tea)
    val actions = remember(currentKey, moveTargets, placement.placementId, onMove, menuActions) {
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
        moveActions + removeAction + deleteAction
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
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_tea_confirm_title)) },
            text = {
                Text(stringResource(R.string.delete_tea_confirm_message, placement.tea.nameRu))
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
