package com.macsia.teatiers.ui.board

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.BuildConfig
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.ThemeMode
import com.macsia.teatiers.viewmodel.AppLanguage
import com.macsia.teatiers.viewmodel.AppUpdateViewModel
import com.macsia.teatiers.viewmodel.DiagnosticsViewModel
import com.macsia.teatiers.viewmodel.BackupEvent
import com.macsia.teatiers.viewmodel.BackupViewModel
import com.macsia.teatiers.viewmodel.SettingsViewModel
import com.macsia.teatiers.viewmodel.UpdateUiState
import com.macsia.teatiers.viewmodel.appLanguageOf
import kotlinx.coroutines.delay

/**
 * App settings (decisions.md #28): theme mode, optional dynamic color (Android 12+), in-app
 * language, and an About/privacy block. Theme/dynamic-color persist via DataStore; language is
 * applied through AppCompat's per-app locale store, which recreates the activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAttributions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: BackupViewModel = hiltViewModel(),
    updateViewModel: AppUpdateViewModel = hiltViewModel(),
    diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backupBusy by backupViewModel.busy.collectAsStateWithLifecycle()
    val safetyBackupAvailable by backupViewModel.safetyBackupAvailable.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    // True once the user taps "Check for updates", so an Idle result reads as "up to date" (not blank).
    var updateChecked by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // LocalResources (not context.getString) so strings re-resolve on a locale/config change.
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showImportConfirm by rememberSaveable { mutableStateOf(false) }
    var showSafetyConfirm by rememberSaveable { mutableStateOf(false) }

    // SAF "save to" picker: the user chooses where the .zip lands (Files / Yandex Disk / ...).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(backupViewModel::exportTo) }

    // SAF "open" picker for restore. octet-stream is included because some providers report a
    // .zip with that generic type; BackupManager validates the contents regardless.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(backupViewModel::importFrom) }

    LaunchedEffect(backupViewModel, snackbarHostState) {
        backupViewModel.events.collect { event ->
            when (event) {
                is BackupEvent.Message ->
                    snackbarHostState.showSnackbar(resources.getString(event.res))
                is BackupEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(send, resources.getString(R.string.backup_share_chooser)),
                    )
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_theme_section)) {
                RadioRow(
                    title = stringResource(R.string.settings_theme_system),
                    selected = settings.themeMode == ThemeMode.SYSTEM,
                    onSelect = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                )
                RadioRow(
                    title = stringResource(R.string.settings_theme_light),
                    selected = settings.themeMode == ThemeMode.LIGHT,
                    onSelect = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                )
                RadioRow(
                    title = stringResource(R.string.settings_theme_dark),
                    selected = settings.themeMode == ThemeMode.DARK,
                    onSelect = { viewModel.setThemeMode(ThemeMode.DARK) },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(
                        title = stringResource(R.string.settings_dynamic_color),
                        hint = stringResource(R.string.settings_dynamic_color_hint),
                        checked = settings.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_language_section)) {
                val current = appLanguageOf(AppCompatDelegate.getApplicationLocales().toLanguageTags())
                // Don't offer English until a real `values-en` ships: picking it would just fall back to
                // the Russian strings (half-exposed locale, review 2026-06-19). The enum keeps ENGLISH so
                // a previously-persisted "en" still resolves; we just don't present it. Re-add when ready.
                AppLanguage.entries.filter { it != AppLanguage.ENGLISH }.forEach { language ->
                    RadioRow(
                        title = stringResource(language.labelRes()),
                        selected = current == language,
                        onSelect = { applyLanguage(language) },
                    )
                }
                Text(
                    text = stringResource(R.string.settings_language_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SettingsSection(title = stringResource(R.string.settings_data_section)) {
                ActionRow(
                    title = stringResource(R.string.backup_export),
                    hint = stringResource(R.string.backup_export_hint),
                    onClick = { exportLauncher.launch(defaultBackupName()) },
                )
                ActionRow(
                    title = stringResource(R.string.backup_share),
                    hint = null,
                    onClick = backupViewModel::share,
                )
                ActionRow(
                    title = stringResource(R.string.backup_import),
                    hint = stringResource(R.string.backup_import_hint),
                    onClick = { showImportConfirm = true },
                )
                // Only after a restore has left a pre-import snapshot to fall back to (auto safety-backup).
                if (safetyBackupAvailable) {
                    ActionRow(
                        title = stringResource(R.string.backup_undo_restore),
                        hint = stringResource(R.string.backup_undo_restore_hint),
                        onClick = { showSafetyConfirm = true },
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_updates_section)) {
                ActionRow(
                    title = stringResource(R.string.settings_check_updates),
                    hint = stringResource(R.string.settings_check_updates_hint),
                    onClick = {
                        updateChecked = true
                        updateViewModel.check()
                    },
                )
                if (updateState is UpdateUiState.Failed) {
                    // The check failed (no/flaky network); make it visible and offer a retry rather
                    // than a faint grey line the user can't act on (audit P2).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.update_check_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { updateViewModel.check() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                } else {
                    val status = when (updateState) {
                        UpdateUiState.Checking -> stringResource(R.string.update_checking)
                        UpdateUiState.Idle -> if (updateChecked) stringResource(R.string.update_up_to_date) else null
                        else -> null // Available / Working are shown as a dialog
                    }
                    if (status != null) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_diagnostics_section)) {
                val diagnosticsEnabled by diagnosticsViewModel.enabled.collectAsStateWithLifecycle()
                SwitchRow(
                    title = stringResource(R.string.settings_diagnostics_toggle),
                    hint = stringResource(R.string.settings_diagnostics_toggle_hint),
                    checked = diagnosticsEnabled,
                    onCheckedChange = diagnosticsViewModel::setEnabled,
                )
                Text(
                    text = stringResource(R.string.settings_diagnostics_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SettingsSection(title = stringResource(R.string.settings_about_section)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_credits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionRow(
                    title = stringResource(R.string.settings_attributions_entry),
                    hint = stringResource(R.string.settings_attributions_entry_hint),
                    onClick = onOpenAttributions,
                )
            }
        }
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text(stringResource(R.string.backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.backup_import_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                ) { Text(stringResource(R.string.backup_import_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showSafetyConfirm) {
        AlertDialog(
            onDismissRequest = { showSafetyConfirm = false },
            title = { Text(stringResource(R.string.backup_undo_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_undo_restore_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSafetyConfirm = false
                        backupViewModel.restoreSafetyBackup()
                    },
                ) { Text(stringResource(R.string.backup_import_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showSafetyConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (backupBusy) {
        // UX-P2-9: there is no safe way to cancel an in-flight archive write (it could leave a
        // half-written safety snapshot or a truncated export), so a hung SAF write is not made
        // cancellable — instead, a delayed hint tells the user it's still working rather than
        // leaving them stuck on a bare spinner with no signal anything is unusual.
        var showSlowHint by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(8_000L)
            showSlowHint = true
        }
        AlertDialog(
            onDismissRequest = {}, // a backup op in flight can't be dismissed
            confirmButton = {},
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.backup_working))
                    }
                    if (showSlowHint) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.backup_working_slow_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }

    UpdateDialogs(
        state = updateState,
        releasesUrl = stringResource(R.string.update_releases_url),
        onDismiss = updateViewModel::dismiss,
        // openExternalUrl toasts when no browser can handle the link, instead of a silent no-op.
        onOpenUrl = context::openExternalUrl,
    )
}

/**
 * The update prompts. The "available" dialog points the user to the GitHub releases page / Obtainium
 * (REL-P0-2, decision 2026-06-23): the in-app download-verify-install path is NOT promoted as the primary
 * channel until an offline Ed25519-signed manifest exists, because the current manifest is server-selected
 * and un-pinned (a MITM could choose both the APK and its claimed hash). The Working/Failed dialogs remain
 * for that future signed-manifest path (the [AppUpdateViewModel.installUpdate] machinery is retained).
 */
@Composable
private fun UpdateDialogs(
    state: UpdateUiState,
    releasesUrl: String,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> AlertDialog(
            // A forced update can't be dismissed by tapping outside or Back.
            onDismissRequest = { if (!state.forced) onDismiss() },
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.update_version, state.manifest.latestVersionName),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (state.manifest.releaseNotesRu.isNotBlank()) {
                        Text(text = state.manifest.releaseNotesRu, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = stringResource(R.string.update_get_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (state.forced) {
                        Text(
                            text = stringResource(R.string.update_forced_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                // Deep-link the actual APK asset so the user isn't left hunting the releases index;
                // fall back to the releases page if an older manifest carries no apkUrl.
                val downloadUrl = state.manifest.apkUrl.ifBlank { releasesUrl }
                TextButton(onClick = { onOpenUrl(downloadUrl) }) {
                    Text(stringResource(R.string.update_download_github))
                }
            },
            dismissButton = if (state.forced) {
                null
            } else {
                { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_action_later)) } }
            },
        )

        UpdateUiState.Working -> AlertDialog(
            onDismissRequest = {}, // a download/install in flight can't be dismissed
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.update_working))
                }
            },
        )

        // UX2-P1-9: a mere "check for updates" failure (reason == "check", no manifest — the only live
        // path today, since installUpdate() is dormant per the file doc comment above) already gets its
        // own purpose-built inline row + retry (see the "check failed" Row above this composable's call
        // site) — this modal's copy ("Failed to update... download manually") is written for a real
        // download/verify/install failure and would be actively wrong here, and doubling up on the same
        // failure with a second, blocking, worse-worded dialog is redundant. Only show it once there is
        // an actual manifest to fall back to (i.e. installUpdate() itself failed).
        is UpdateUiState.Failed -> if (state.manifest != null) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.update_failed_title)) },
                text = { Text(stringResource(R.string.update_failed_message)) },
                confirmButton = {
                    val url = state.manifest.apkUrl
                    if (url.isNotBlank()) {
                        TextButton(onClick = { onOpenUrl(url) }) {
                            Text(stringResource(R.string.update_download_github))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        UpdateUiState.Idle, UpdateUiState.Checking -> Unit // no dialog
    }
}

/** Suggested SAF file name with a sortable timestamp; the user can rename in the picker. */
private fun defaultBackupName(): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
    return "teatiers-backup-$stamp.zip"
}

private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.RUSSIAN -> R.string.settings_language_ru
    AppLanguage.ENGLISH -> R.string.settings_language_en
}

/** Applies (or clears, for [AppLanguage.SYSTEM]) the per-app locale; AppCompat recreates the UI. */
private fun applyLanguage(language: AppLanguage) {
    val locales = language.tag
        ?.let { LocaleListCompat.forLanguageTags(it) }
        ?: LocaleListCompat.getEmptyLocaleList()
    AppCompatDelegate.setApplicationLocales(locales)
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 8.dp, horizontal = 16.dp)) { content() }
        }
    }
}

@Composable
private fun ActionRow(title: String, hint: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // role = Button so TalkBack announces it as actionable (it was indistinguishable from
            // the surrounding descriptive text); the trailing chevron is the matching visual cue.
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RadioRow(title: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // role = Switch makes the whole row one toggleable TalkBack node.
            .selectable(selected = checked, role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
