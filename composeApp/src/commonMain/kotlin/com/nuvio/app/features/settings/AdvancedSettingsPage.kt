package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.downloads.DownloadStorageMigrationDialog
import com.nuvio.app.features.downloads.DownloadStorageSheet
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.downloadStorageName
import com.nuvio.app.features.downloads.formatStorageBytes
import com.nuvio.app.features.downloads.MAX_CONCURRENT_DOWNLOADS
import com.nuvio.app.features.downloads.MIN_CONCURRENT_DOWNLOADS
import org.jetbrains.compose.resources.getString
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_concurrent_description
import nuvio.composeapp.generated.resources.downloads_storage_free
import nuvio.composeapp.generated.resources.downloads_storage_title
import nuvio.composeapp.generated.resources.downloads_verify_unavailable
import nuvio.composeapp.generated.resources.downloads_concurrent_title
import nuvio.composeapp.generated.resources.downloads_full_episode_list_description
import nuvio.composeapp.generated.resources.downloads_full_episode_list_title
import nuvio.composeapp.generated.resources.downloads_verify_action
import nuvio.composeapp.generated.resources.downloads_verify_clean
import nuvio.composeapp.generated.resources.downloads_verify_description
import nuvio.composeapp.generated.resources.downloads_verify_result
import nuvio.composeapp.generated.resources.downloads_verify_running
import nuvio.composeapp.generated.resources.downloads_verify_unmatched
import nuvio.composeapp.generated.resources.settings_advanced_section_downloads
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_done
import nuvio.composeapp.generated.resources.settings_advanced_clear_cw_cache_subtitle
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile
import nuvio.composeapp.generated.resources.settings_advanced_remember_last_profile_description
import nuvio.composeapp.generated.resources.settings_advanced_section_cache
import nuvio.composeapp.generated.resources.settings_advanced_section_diagnostics
import nuvio.composeapp.generated.resources.settings_advanced_section_startup
import nuvio.composeapp.generated.resources.settings_advanced_sentry_reports
import nuvio.composeapp.generated.resources.settings_advanced_sentry_reports_subtitle
import nuvio.composeapp.generated.resources.sentry_disable_dialog_subtitle
import nuvio.composeapp.generated.resources.sentry_disable_dialog_title
import nuvio.composeapp.generated.resources.sentry_enable_dialog_subtitle
import nuvio.composeapp.generated.resources.sentry_enable_dialog_title
import nuvio.composeapp.generated.resources.sentry_help_body
import nuvio.composeapp.generated.resources.sentry_help_title
import nuvio.composeapp.generated.resources.sentry_keep_enabled
import nuvio.composeapp.generated.resources.sentry_not_sent_body
import nuvio.composeapp.generated.resources.sentry_not_sent_title
import nuvio.composeapp.generated.resources.sentry_sent_body
import nuvio.composeapp.generated.resources.sentry_sent_title
import nuvio.composeapp.generated.resources.sentry_turn_off
import nuvio.composeapp.generated.resources.sentry_turn_on
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.advancedSettingsContent(
    isTablet: Boolean,
    rememberLastProfileEnabled: Boolean,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_startup),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_advanced_remember_last_profile),
                    description = stringResource(Res.string.settings_advanced_remember_last_profile_description),
                    checked = rememberLastProfileEnabled,
                    isTablet = isTablet,
                    onCheckedChange = ProfileRepository::setRememberLastProfileEnabled,
                )
            }
        }
    }
    item {
        DownloadSettingsSection(isTablet = isTablet)
    }
    if (SentrySettingsRepository.isSupported) {
        item {
            val sentryEnabledFlow = remember {
                SentrySettingsRepository.ensureLoaded()
                SentrySettingsRepository.enabled
            }
            val sentryEnabled by sentryEnabledFlow.collectAsStateWithLifecycle()
            var showSentryDialog by rememberSaveable { mutableStateOf(false) }

            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_diagnostics),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_advanced_sentry_reports),
                        description = stringResource(Res.string.settings_advanced_sentry_reports_subtitle),
                        checked = sentryEnabled,
                        isTablet = isTablet,
                        onCheckedChange = { showSentryDialog = true },
                    )
                }
            }

            if (showSentryDialog) {
                SentrySettingsDialog(
                    enabled = sentryEnabled,
                    onConfirm = {
                        SentrySettingsRepository.setEnabled(!sentryEnabled)
                    },
                    onDismiss = {
                        showSentryDialog = false
                    },
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_advanced_section_cache),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                val scope = rememberCoroutineScope()
                var cleared by rememberSaveable { mutableStateOf(false) }
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_advanced_clear_cw_cache),
                    description = if (cleared) {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_done)
                    } else {
                        stringResource(Res.string.settings_advanced_clear_cw_cache_subtitle)
                    },
                    isTablet = isTablet,
                    onClick = {
                        if (!cleared) {
                            ContinueWatchingEnrichmentCache.clearAll(ProfileRepository.activeProfileId)
                            cleared = true
                            scope.launch {
                                WatchProgressRepository.clearLocalAndForceSnapshotRefreshFromServer(
                                    ProfileRepository.activeProfileId,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadSettingsSection(isTablet: Boolean) {
    val tokens = MaterialTheme.nuvio
    val limit by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.maxConcurrentDownloads
    }.collectAsStateWithLifecycle()
    val saveFullEpisodeList by DownloadsRepository.saveFullEpisodeList.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var verifying by remember { mutableStateOf(false) }
    var showStorageSheet by remember { mutableStateOf(false) }
    // Read again each time the sheet closes, as an SD card may have been connected or removed.
    val storageVolumes = remember(showStorageSheet) { DownloadsRepository.storageVolumes() }
    DownloadStorageMigrationDialog()
    if (showStorageSheet) DownloadStorageSheet(onDismiss = { showStorageSheet = false })

    SettingsSection(
        title = stringResource(Res.string.settings_advanced_section_downloads),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (isTablet) 20.dp else 16.dp,
                        vertical = if (isTablet) 16.dp else 14.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.downloads_concurrent_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.downloads_concurrent_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
                FilledTonalIconButton(
                    enabled = limit > MIN_CONCURRENT_DOWNLOADS,
                    onClick = { DownloadsRepository.setMaxConcurrentDownloads(limit - 1) },
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = null)
                }
                Text(
                    text = limit.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = tokens.colors.textPrimary,
                    modifier = Modifier.width(40.dp),
                )
                FilledTonalIconButton(
                    enabled = limit < MAX_CONCURRENT_DOWNLOADS,
                    onClick = { DownloadsRepository.setMaxConcurrentDownloads(limit + 1) },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                }
            }
            if (storageVolumes.size > 1) {
                SettingsGroupDivider(isTablet = isTablet)
                val selected = storageVolumes.firstOrNull { it.id == DownloadsRepository.selectedStorageVolumeId() }
                    ?: storageVolumes.first()
                SettingsNavigationRow(
                    title = stringResource(Res.string.downloads_storage_title),
                    description = "${downloadStorageName(selected)} • " +
                        stringResource(Res.string.downloads_storage_free, formatStorageBytes(selected.freeBytes)),
                    isTablet = isTablet,
                    onClick = { showStorageSheet = true },
                )
            }
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.downloads_full_episode_list_title),
                description = stringResource(Res.string.downloads_full_episode_list_description),
                checked = saveFullEpisodeList,
                isTablet = isTablet,
                onCheckedChange = DownloadsRepository::setSaveFullEpisodeList,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(
                    if (verifying) Res.string.downloads_verify_running else Res.string.downloads_verify_action,
                ),
                description = stringResource(Res.string.downloads_verify_description),
                enabled = !verifying,
                isTablet = isTablet,
                onClick = {
                    scope.launch {
                        verifying = true
                        val result = try {
                            DownloadsRepository.verifyAgainstDisk()
                        } finally {
                            verifying = false
                        }
                        val summary = if (result.changedAnything) {
                            getString(
                                Res.string.downloads_verify_result,
                                result.removedRecords,
                                result.restoredRecords,
                                result.cleanedLeftovers,
                            )
                        } else {
                            getString(Res.string.downloads_verify_clean)
                        }
                        val notes = buildList {
                            add(summary)
                            if (result.unmatchedFiles > 0) {
                                add(getString(Res.string.downloads_verify_unmatched, result.unmatchedFiles))
                            }
                            if (result.unavailableFiles > 0) {
                                add(getString(Res.string.downloads_verify_unavailable, result.unavailableFiles))
                            }
                        }
                        NuvioToastController.show(notes.joinToString("\n"))
                    }
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SentrySettingsDialog(
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(
                        if (enabled) {
                            Res.string.sentry_disable_dialog_title
                        } else {
                            Res.string.sentry_enable_dialog_title
                        },
                    ),
    ) {
        Text(
            text = stringResource(
                if (enabled) {
                    Res.string.sentry_disable_dialog_subtitle
                } else {
                    Res.string.sentry_enable_dialog_subtitle
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textMuted,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            SentryInfoSection(
                title = stringResource(Res.string.sentry_help_title),
                body = stringResource(Res.string.sentry_help_body),
            )
            SentryInfoSection(
                title = stringResource(Res.string.sentry_sent_title),
                body = stringResource(Res.string.sentry_sent_body),
            )
            SentryInfoSection(
                title = stringResource(Res.string.sentry_not_sent_title),
                body = stringResource(Res.string.sentry_not_sent_body),
            )
        }
        DialogButtons {
            DialogButton(
                text = stringResource(
                        if (enabled) {
                            Res.string.sentry_keep_enabled
                        } else {
                            Res.string.action_cancel
                        },
                    ),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(
                        if (enabled) {
                            Res.string.sentry_turn_off
                        } else {
                            Res.string.sentry_turn_on
                        },
                    ),
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun SentryInfoSection(
    title: String,
    body: String,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }
}
