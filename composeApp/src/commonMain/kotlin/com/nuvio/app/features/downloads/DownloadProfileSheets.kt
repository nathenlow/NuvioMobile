package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Gives the built-in profiles their translated names. Call before reading the profiles. */
@Composable
fun rememberDownloadProfileNames() {
    val cached = stringResource(Res.string.downloads_profile_cached)
    val http = stringResource(Res.string.downloads_profile_http)
    val diy = stringResource(Res.string.downloads_profile_diy)
    remember(cached, http, diy) {
        DownloadProfilesRepository.setBuiltInNames(cached, http, diy)
        DownloadProfilesRepository.ensureLoaded()
    }
}

@Composable
private fun DownloadProfile.summary(): String = when (id) {
    PROFILE_ID_CACHED -> stringResource(Res.string.downloads_profile_cached_description)
    PROFILE_ID_HTTP -> stringResource(Res.string.downloads_profile_http_description)
    else -> stringResource(Res.string.downloads_profile_counts, rules.size, exclusions.size)
}

/** Pick the profile bulk download uses, or open one to view or edit it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadProfilesSheet(onDismiss: () -> Unit) {
    rememberDownloadProfileNames()
    val profiles by DownloadProfilesRepository.profiles.collectAsStateWithLifecycle()
    val activeId by DownloadProfilesRepository.activeId.collectAsStateWithLifecycle()
    var editingId by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    val copyName = stringResource(Res.string.downloads_profile_copy_name, "%s")

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        val editingProfile = editingId?.let { id -> profiles.firstOrNull { it.id == id } }
        if (editingProfile != null) {
            DownloadProfileEditorContent(profile = editingProfile, onBack = { editingId = null })
        } else if (showImport) {
            ImportProfileDialog(
                onDismiss = { showImport = false },
                onImported = { profile ->
                    showImport = false
                    DownloadProfilesRepository.setActive(profile.id)
                    editingId = profile.id
                },
            )
        } else Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Text(
                text = stringResource(Res.string.downloads_profile_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(Res.string.downloads_profile_sheet_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            NuvioBottomSheetDivider()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val preferredResolution by DownloadsRepository.preferredResolution.collectAsStateWithLifecycle()
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.downloads_resolution_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(Res.string.downloads_resolution_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PREFERRED_RESOLUTION_CHOICES.forEach { height ->
                            FilterChip(
                                selected = preferredResolution == height,
                                onClick = { DownloadsRepository.setPreferredResolution(height) },
                                label = {
                                    Text(
                                        if (height == 0) stringResource(Res.string.downloads_resolution_auto)
                                        else stringResource(Res.string.downloads_resolution_value, height),
                                    )
                                },
                            )
                        }
                    }
                }
                NuvioBottomSheetDivider()
                profiles.forEach { profile ->
                    NuvioBottomSheetActionRow(
                        title = profile.name,
                        selected = profile.id == activeId,
                        onClick = { DownloadProfilesRepository.setActive(profile.id) },
                        trailingContent = {
                            IconButton(onClick = { editingId = profile.id }) {
                                Icon(
                                    imageVector = if (profile.builtIn) Icons.Rounded.Visibility else Icons.Rounded.Edit,
                                    contentDescription = stringResource(
                                        if (profile.builtIn) Res.string.downloads_profile_view else Res.string.downloads_profile_edit,
                                    ),
                                )
                            }
                        },
                    )
                    Text(
                        text = profile.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                    )
                }
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.ContentCopy,
                    title = stringResource(Res.string.downloads_profile_new),
                    onClick = {
                        val source = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
                        val copy = DownloadProfilesRepository.duplicate(source, copyName.replace("%s", source.name))
                        DownloadProfilesRepository.setActive(copy.id)
                        editingId = copy.id
                    },
                )
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.FileDownload,
                    title = stringResource(Res.string.downloads_profile_import),
                    onClick = { showImport = true },
                )
            }
        }
    }

}

@Composable
private fun ImportProfileDialog(onDismiss: () -> Unit, onImported: (DownloadProfile) -> Unit) {
    var text by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    InlineDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.downloads_profile_import)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; failed = false },
                label = { Text(stringResource(Res.string.downloads_profile_import_hint)) },
                isError = failed,
                supportingText = if (failed) {
                    { Text(stringResource(Res.string.downloads_profile_import_failed)) }
                } else {
                    null
                },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val imported = DownloadProfilesRepository.import(text)
                    if (imported == null) failed = true else onImported(imported)
                },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(Res.string.downloads_dialog_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.downloads_dialog_cancel)) }
        },
    )
}
