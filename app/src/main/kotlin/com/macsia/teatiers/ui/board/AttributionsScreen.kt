package com.macsia.teatiers.ui.board

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.macsia.teatiers.R

/**
 * Data-source attributions (plan M3): the catalog mixes open datasets whose licenses (CC0,
 * CC BY-SA, ODbL) require crediting the source. Per-record/per-image links live on the tea card;
 * this screen credits the datasets as a whole. Static content — no state, no I/O — so it is a
 * stateless composable rather than a VM-backed screen. Source names and license ids stay literal
 * (proper nouns / standard identifiers); only the human summaries are localized.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttributionsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    fun openUrl(url: String) = context.openExternalUrl(url)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.attributions_title)) },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.attributions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dataSourceCredits.forEach { credit ->
                DataSourceCard(credit = credit, onOpen = ::openUrl)
            }
        }
    }
}

@Composable
private fun DataSourceCard(credit: DataSourceCredit, onOpen: (String) -> Unit) {
    val openSiteDescription = stringResource(R.string.a11y_open_link, credit.name)
    val openLicenseDescription = stringResource(R.string.a11y_open_link, credit.license)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = credit.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(credit.summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onOpen(credit.siteUrl) },
                    modifier = Modifier.semantics { contentDescription = openSiteDescription },
                ) {
                    Text(stringResource(R.string.attributions_open_site))
                }
                TextButton(
                    onClick = { onOpen(credit.licenseUrl) },
                    modifier = Modifier.semantics { contentDescription = openLicenseDescription },
                ) {
                    Text(credit.license)
                }
            }
        }
    }
}

/** One credited dataset. [name]/[license] are literal; [summary] is localized. */
private data class DataSourceCredit(
    val name: String,
    @param:StringRes val summary: Int,
    val license: String,
    val licenseUrl: String,
    val siteUrl: String,
)

private val dataSourceCredits = listOf(
    DataSourceCredit(
        name = "Wikidata",
        summary = R.string.attributions_wikidata_summary,
        license = "CC0 1.0",
        licenseUrl = "https://creativecommons.org/publicdomain/zero/1.0/",
        siteUrl = "https://www.wikidata.org/",
    ),
    DataSourceCredit(
        name = "Wikipedia",
        summary = R.string.attributions_wikipedia_summary,
        license = "CC BY-SA 4.0",
        licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
        siteUrl = "https://www.wikipedia.org/",
    ),
    DataSourceCredit(
        name = "Wikimedia Commons",
        summary = R.string.attributions_commons_summary,
        license = "CC BY-SA / CC BY",
        licenseUrl = "https://commons.wikimedia.org/wiki/Commons:Licensing",
        siteUrl = "https://commons.wikimedia.org/",
    ),
    DataSourceCredit(
        name = "Open Food Facts",
        summary = R.string.attributions_off_summary,
        license = "ODbL 1.0",
        licenseUrl = "https://opendatacommons.org/licenses/odbl/1-0/",
        siteUrl = "https://world.openfoodfacts.org/",
    ),
)
