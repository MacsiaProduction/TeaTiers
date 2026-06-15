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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.ui.components.FlavorRadar
import com.macsia.teatiers.ui.components.FlavorStrip
import com.macsia.teatiers.ui.components.LiquorSwatch
import com.macsia.teatiers.ui.components.TeaCard
import com.macsia.teatiers.ui.components.TypeChip
import com.macsia.teatiers.ui.theme.TeaTheme
import com.macsia.teatiers.viewmodel.BoardUiState
import com.macsia.teatiers.viewmodel.BoardViewModel
import com.macsia.teatiers.viewmodel.TierWithTeas
import kotlin.math.roundToInt

private val InkOnLight = Color(0xFF1E1B16)
private val ScreenInset = 16.dp

/** A place a tea can be moved to via the accessibility menu: a tier (by id) or the unranked tray. */
private data class MoveTarget(val tierId: String?, val label: String)

@Composable
fun BoardScreen(
    boardId: String,
    onBack: () -> Unit,
    onOpenTea: (String) -> Unit,
    onAddTea: () -> Unit,
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
        onMove = viewModel::moveTea,
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
    onMove: (String, String?, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ramp = TeaTheme.colors.tierRamp
    val featured = state?.tiers?.firstOrNull { it.teas.isNotEmpty() }?.teas?.firstOrNull()
    val dragState = remember { BoardDragState() }

    val unrankedLabel = stringResource(R.string.board_unranked)
    val moveTargets = remember(state?.tiers, unrankedLabel) {
        (state?.tiers?.map { MoveTarget(it.tier.id, it.tier.label) }.orEmpty()) + MoveTarget(null, unrankedLabel)
    }
    val groupOrder: (String) -> List<String> = { key ->
        if (key == UnrankedKey) {
            state?.unranked?.map { it.id }.orEmpty()
        } else {
            state?.tiers?.firstOrNull { it.tier.id == key }?.teas?.map { it.id }.orEmpty()
        }
    }

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
                        tierWithTeas = row,
                        rampColor = rampColor,
                        dragState = dragState,
                        moveTargets = moveTargets,
                        groupOrder = groupOrder,
                        onOpenTea = onOpenTea,
                        onMove = onMove,
                    )
                }
                item(key = "unranked") {
                    UnrankedSection(
                        teas = state.unranked,
                        dragState = dragState,
                        moveTargets = moveTargets,
                        groupOrder = groupOrder,
                        onOpenTea = onOpenTea,
                        onMove = onMove,
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
                TeaCard(tea = dragged.tea)
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
    tierWithTeas: TierWithTeas,
    rampColor: Color,
    dragState: BoardDragState,
    moveTargets: List<MoveTarget>,
    groupOrder: (String) -> List<String>,
    onOpenTea: (String) -> Unit,
    onMove: (String, String?, Int) -> Unit,
) {
    val key = groupKey(tierWithTeas.tier.id)
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
            Text(text = tierWithTeas.tier.label, color = onRamp, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        if (tierWithTeas.teas.isEmpty()) {
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
                tierWithTeas.teas.forEach { tea ->
                    DraggableTeaCard(
                        tea = tea,
                        currentKey = key,
                        dragState = dragState,
                        moveTargets = moveTargets,
                        groupOrder = groupOrder,
                        onOpenTea = onOpenTea,
                        onMove = onMove,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnrankedSection(
    teas: List<Tea>,
    dragState: BoardDragState,
    moveTargets: List<MoveTarget>,
    groupOrder: (String) -> List<String>,
    onOpenTea: (String) -> Unit,
    onMove: (String, String?, Int) -> Unit,
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
            if (teas.isEmpty()) {
                Text(
                    text = stringResource(R.string.tier_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                teas.forEach { tea ->
                    DraggableTeaCard(
                        tea = tea,
                        currentKey = UnrankedKey,
                        dragState = dragState,
                        moveTargets = moveTargets,
                        groupOrder = groupOrder,
                        onOpenTea = onOpenTea,
                        onMove = onMove,
                    )
                }
            }
            Spacer(Modifier.width(ScreenInset))
        }
    }
}

/**
 * A [TeaCard] that opens the tea on tap and is picked up by a long press to drag between tiers.
 * The original is hidden (alpha 0) while its ghost is dragged. TalkBack users get one custom
 * action per other group ("move to tier X" / "to unranked"), so ranking never requires a drag.
 */
@Composable
private fun DraggableTeaCard(
    tea: Tea,
    currentKey: String,
    dragState: BoardDragState,
    moveTargets: List<MoveTarget>,
    groupOrder: (String) -> List<String>,
    onOpenTea: (String) -> Unit,
    onMove: (String, String?, Int) -> Unit,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val moveToTierTemplate = stringResource(R.string.a11y_move_to_tier)
    val moveToUnrankedLabel = stringResource(R.string.a11y_move_to_unranked)
    val actions = remember(currentKey, moveTargets, tea.id, onMove) {
        moveTargets
            .filter { groupKey(it.tierId) != currentKey }
            .map { target ->
                val label =
                    if (target.tierId == null) moveToUnrankedLabel else moveToTierTemplate.format(target.label)
                // Int.MAX_VALUE => append to the end of the target group (the repository clamps it).
                CustomAccessibilityAction(label) {
                    onMove(tea.id, target.tierId, Int.MAX_VALUE)
                    true
                }
            }
    }

    Box(
        modifier = Modifier
            .onGloballyPositioned { c ->
                coords = c
                dragState.reportCardCenterX(tea.id, c.boundsInRoot().center.x)
            }
            .graphicsLayer { alpha = if (dragState.draggedTeaId == tea.id) 0f else 1f }
            .pointerInput(tea.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { touch ->
                        dragState.start(tea, currentKey, coords?.positionInRoot() ?: Offset.Zero, touch)
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
        // Tap still opens the tea (keeps the ripple + click semantics); the long press above
        // starts a drag without conflicting, because it only fires after the press timeout.
        TeaCard(tea = tea, onClick = { onOpenTea(tea.id) })
    }
}
