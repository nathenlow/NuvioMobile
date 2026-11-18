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
import androidx.compose.material.icons.rounded.Tune
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
    var showMarkers by remember { mutableStateOf(false) }
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
        } else if (showMarkers) {
            StreamMarkersContent(onBack = { showMarkers = false })
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
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(Res.string.downloads_markers_row),
                    onClick = { showMarkers = true },
                )
                Text(
                    text = stringResource(Res.string.downloads_markers_row_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
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

@Composable
private fun MarkerMeaning.label(): String = when (this) {
    MarkerMeaning.Cached -> stringResource(Res.string.downloads_condition_cached_yes)
    MarkerMeaning.NotCached -> stringResource(Res.string.downloads_condition_cached_no)
    MarkerMeaning.Debrid -> stringResource(Res.string.downloads_condition_source_debrid)
    MarkerMeaning.Usenet -> stringResource(Res.string.downloads_condition_source_usenet)
    MarkerMeaning.Http -> stringResource(Res.string.downloads_condition_source_http)
    MarkerMeaning.Torrent -> stringResource(Res.string.downloads_condition_source_torrent)
}

/** Paste the addon's formatter templates; the symbols found in them become markers for the profiles. */
@Composable
private fun StreamMarkersContent(onBack: () -> Unit) {
    val saved by DownloadProfilesRepository.format.collectAsStateWithLifecycle()
    var nameTemplate by remember { mutableStateOf(saved.nameTemplate) }
    var descriptionTemplate by remember { mutableStateOf(saved.descriptionTemplate) }
    // Markers found in the saved templates but left out when saved start switched off.
    var disabled by remember {
        mutableStateOf(
            if (saved.markers.isEmpty()) {
                emptySet()
            } else {
                (AioFormatterParser.parse(saved.nameTemplate) + AioFormatterParser.parse(saved.descriptionTemplate))
                    .filter { it !in saved.markers }.toSet()
            },
        )
    }
    var sample by remember { mutableStateOf("") }
    val found = remember(nameTemplate, descriptionTemplate) {
        (AioFormatterParser.parse(nameTemplate) + AioFormatterParser.parse(descriptionTemplate)).distinct()
    }
    val active = found.filter { it !in disabled }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(Res.string.downloads_markers_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = stringResource(Res.string.downloads_markers_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = nameTemplate,
            onValueChange = { nameTemplate = it },
            label = { Text(stringResource(Res.string.downloads_markers_name_template)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = descriptionTemplate,
            onValueChange = { descriptionTemplate = it },
            label = { Text(stringResource(Res.string.downloads_markers_description_template)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(Res.string.downloads_markers_found),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (found.isEmpty()) {
            Text(
                text = stringResource(Res.string.downloads_markers_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        found.forEach { marker ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = marker.text,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Text(
                    text = marker.meaning.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = marker !in disabled,
                    onCheckedChange = { on -> disabled = if (on) disabled - marker else disabled + marker },
                )
            }
        }
        OutlinedTextField(
            value = sample,
            onValueChange = { sample = it },
            label = { Text(stringResource(Res.string.downloads_markers_try)) },
            placeholder = { Text(stringResource(Res.string.downloads_markers_try_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (sample.isNotBlank()) {
            val meanings = active.filter { sample.contains(it.text, ignoreCase = true) }.map { it.meaning }.distinct()
            Text(
                text = if (meanings.isEmpty()) {
                    stringResource(Res.string.downloads_markers_try_nothing)
                } else {
                    stringResource(Res.string.downloads_markers_try_result, meanings.map { it.label() }.joinToString())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = {
                DownloadProfilesRepository.saveFormat(StreamFormatSetup(nameTemplate, descriptionTemplate, active))
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(Res.string.downloads_markers_save)) }
    }
}
