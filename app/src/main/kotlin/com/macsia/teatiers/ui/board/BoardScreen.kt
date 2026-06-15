package com.macsia.teatiers.ui.board

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

private val InkOnLight = Color(0xFF1E1B16)
private val ScreenInset = 16.dp

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
    BoardContent(state = state, onBack = onBack, onOpenTea = onOpenTea, onAddTea = onAddTea, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardContent(
    state: BoardUiState?,
    onBack: () -> Unit,
    onOpenTea: (String) -> Unit,
    onAddTea: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ramp = TeaTheme.colors.tierRamp
    val featured = state?.tiers?.firstOrNull { it.teas.isNotEmpty() }?.teas?.firstOrNull()

    Scaffold(
        modifier = modifier,
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
                TierRow(tierWithTeas = row, rampColor = rampColor, onOpenTea = onOpenTea)
            }
            if (state.unranked.isNotEmpty()) {
                item(key = "unranked") { UnrankedSection(teas = state.unranked, onOpenTea = onOpenTea) }
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
private fun TierRow(tierWithTeas: TierWithTeas, rampColor: Color, onOpenTea: (String) -> Unit) {
    val onRamp = if (rampColor.luminance() > 0.55f) InkOnLight else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = ScreenInset),
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
                tierWithTeas.teas.forEach { tea -> TeaCard(tea = tea, onClick = { onOpenTea(tea.id) }) }
            }
        }
    }
}

@Composable
private fun UnrankedSection(teas: List<Tea>, onOpenTea: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.board_unranked),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = ScreenInset),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.width(ScreenInset))
            teas.forEach { tea -> TeaCard(tea = tea, onClick = { onOpenTea(tea.id) }) }
            Spacer(Modifier.width(ScreenInset))
        }
    }
}
