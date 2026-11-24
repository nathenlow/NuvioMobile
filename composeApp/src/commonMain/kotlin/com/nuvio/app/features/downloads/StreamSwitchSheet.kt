package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.bulk_download_reload_streams
import nuvio.composeapp.generated.resources.bulk_download_stream_selected
import nuvio.composeapp.generated.resources.bulk_download_switch_empty
import nuvio.composeapp.generated.resources.bulk_download_switch_title
import org.jetbrains.compose.resources.stringResource

/** What an add-on says about a stream beyond its name: its description, or its title for older add-ons. */
internal fun StreamItem.detailText(): String? =
    listOfNotNull(description, title).firstOrNull { it.isNotBlank() }

/**
 * Pick one of the downloadable streams of an episode. The stream in use is marked with a tick and a
 * tinted row, and every stream is shown in full, not cut short.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StreamSwitchSheet(
    subtitle: String?,
    groups: List<AddonStreamGroup>,
    loading: Boolean,
    selected: StreamItem?,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onSelect: (StreamItem) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    val choices = remember(groups) {
        groups.map { it to it.downloadableStreams() }.filter { (_, streams) -> streams.isNotEmpty() }
    }

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState, fullHeight = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.bulk_download_switch_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                IconButton(onClick = onReload, enabled = !loading) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(Res.string.bulk_download_reload_streams),
                    )
                }
            }
            NuvioBottomSheetDivider()
            if (choices.isEmpty()) {
                Text(
                    text = stringResource(Res.string.bulk_download_switch_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
                    choices.forEach { (group, streams) ->
                        item(key = "header:${group.addonId}") {
                            Text(
                                text = group.addonName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(items = streams) { stream ->
                            StreamChoiceRow(
                                stream = stream,
                                selected = stream == selected,
                                onClick = {
                                    onSelect(stream)
                                    dismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamChoiceRow(
    stream: StreamItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (selected) {
                Text(
                    text = stringResource(Res.string.bulk_download_stream_selected),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
            Text(
                text = stream.streamLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            stream.detailText()?.let { details ->
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A stream written out in full: add-on, name and details, with no line cut short. */
@Composable
internal fun StreamDetailBlock(
    stream: StreamItem,
    modifier: Modifier = Modifier,
    nameColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "${stream.addonName} • ${stream.streamLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = nameColor,
        )
        stream.detailText()?.let { details ->
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
