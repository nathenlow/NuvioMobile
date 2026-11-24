package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedEpisodeTitle
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun BulkDownloadScreen(
    session: BulkDownloadSession,
    onBack: () -> Unit,
    onPlayStream: (episode: BulkEpisodeState, stream: StreamItem) -> Unit,
) {
    val episodes by session.episodes.collectAsStateWithLifecycle()
    val downloads by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val request = session.request

    LaunchedEffect(session) { session.start() }

    var actionsFor by remember { mutableStateOf<String?>(null) }
    var switchFor by remember { mutableStateOf<String?>(null) }
    var queuing by remember { mutableStateOf(false) }
    var confirmCancelSeries by remember { mutableStateOf(false) }

    val downloadByKey = remember(downloads.items) {
        downloads.items.associateBy { it.logicalContentKey }
    }
    val selectable = episodes.filter { it.selected != null }
    val ready = selectable.size
    val chosen = selectable.filter { it.included }
    val stillWorking = episodes.any {
        it.status == BulkFetchStatus.Waiting || it.status == BulkFetchStatus.Loading
    }

    fun queue(videoIds: Collection<String>) {
        if (queuing || videoIds.isEmpty()) return
        scope.launch {
            queuing = true
            val queued = try {
                session.download(videoIds)
            } finally {
                queuing = false
            }
            if (queued > 0) {
                NuvioToastController.show(getString(Res.string.bulk_download_queued, queued))
            }
        }
    }

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = request.title,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { confirmCancelSeries = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.bulk_download_cancel_series),
                        )
                    }
                    IconButton(onClick = session::reloadAll) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(Res.string.bulk_download_reload_all),
                        )
                    }
                },
            )
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = request.scopeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.bulk_download_summary, ready, episodes.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (stillWorking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val allChosen = selectable.isNotEmpty() && selectable.all { it.included }
                    TextButton(
                        enabled = selectable.isNotEmpty(),
                        onClick = { session.setAllIncluded(!allChosen) },
                    ) {
                        Text(
                            stringResource(
                                if (allChosen) Res.string.bulk_download_select_none else Res.string.bulk_download_select_all,
                            ),
                        )
                    }
                    Button(
                        enabled = chosen.isNotEmpty() && !queuing,
                        onClick = { queue(chosen.map { it.ref.videoId }) },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (chosen.isEmpty()) {
                                stringResource(Res.string.bulk_download_button_none)
                            } else {
                                stringResource(Res.string.bulk_download_button, chosen.size)
                            },
                        )
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.bulk_download_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(items = episodes, key = { it.ref.videoId }) { state ->
            val download = downloadByKey[
                "${request.metaId.trim()}|${state.ref.seasonNumber}|${state.ref.episodeNumber}",
            ]
            BulkEpisodeRow(
                state = state,
                download = download,
                onToggleIncluded = { session.setIncluded(state.ref.videoId, !state.included) },
                onClick = {
                    when (state.status) {
                        BulkFetchStatus.Ready -> actionsFor = state.ref.videoId
                        BulkFetchStatus.NoStream,
                        BulkFetchStatus.Failed,
                        BulkFetchStatus.Skipped,
                        -> session.reload(state.ref.videoId)

                        BulkFetchStatus.Waiting,
                        BulkFetchStatus.Loading,
                        -> Unit
                    }
                },
            )
        }
    }

    if (confirmCancelSeries) {
        NuvioStatusModal(
            title = stringResource(Res.string.bulk_download_cancel_series_title),
            message = stringResource(Res.string.bulk_download_cancel_series_message),
            isVisible = true,
            destructive = true,
            confirmText = stringResource(Res.string.action_yes),
            dismissText = stringResource(Res.string.action_no),
            onConfirm = {
                confirmCancelSeries = false
                val count = DownloadsRepository.cancelUnfinishedDownloads(request.metaId)
                scope.launch {
                    NuvioToastController.show(
                        if (count > 0) getString(Res.string.downloads_cancelled_count, count)
                        else getString(Res.string.downloads_cancel_none),
                    )
                }
            },
            onDismiss = { confirmCancelSeries = false },
        )
    }

    val actionsEpisode = actionsFor?.let { id -> episodes.firstOrNull { it.ref.videoId == id } }
    if (actionsEpisode != null) {
        EpisodeActionsSheet(
            state = actionsEpisode,
            onDismiss = { actionsFor = null },
            onPlay = { stream -> onPlayStream(actionsEpisode, stream) },
            onSwitch = { switchFor = actionsEpisode.ref.videoId },
            onReload = { session.reload(actionsEpisode.ref.videoId) },
            onDownloadThis = { queue(listOf(actionsEpisode.ref.videoId)) },
        )
    }

    val switchEpisode = switchFor?.let { id -> episodes.firstOrNull { it.ref.videoId == id } }
    if (switchEpisode != null) {
        StreamSwitchSheet(
            subtitle = listOfNotNull(
                localizedSeasonEpisodeCode(switchEpisode.ref.seasonNumber, switchEpisode.ref.episodeNumber),
                localizedEpisodeTitle(switchEpisode.ref.title, switchEpisode.ref.episodeNumber),
            ).joinToString(" · "),
            groups = switchEpisode.groups,
            loading = switchEpisode.status == BulkFetchStatus.Loading || switchEpisode.status == BulkFetchStatus.Waiting,
            selected = switchEpisode.selected,
            onDismiss = { switchFor = null },
            onReload = { session.reload(switchEpisode.ref.videoId) },
            onSelect = { stream -> session.select(switchEpisode.ref.videoId, stream) },
        )
    }
}

@Composable
private fun BulkEpisodeRow(
    state: BulkEpisodeState,
    download: DownloadItem?,
    onToggleIncluded: () -> Unit,
    onClick: () -> Unit,
) {
    val code = localizedSeasonEpisodeCode(state.ref.seasonNumber, state.ref.episodeNumber).orEmpty()
    val episodeTitle = localizedEpisodeTitle(state.ref.title, state.ref.episodeNumber)
    val heading = listOfNotNull(code.takeIf { it.isNotBlank() }, episodeTitle).joinToString(" · ")
    val stream = state.selected

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.included && stream != null,
                enabled = stream != null,
                onCheckedChange = { onToggleIncluded() },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (stream != null && download == null) {
                    StreamDetailBlock(stream = stream)
                } else {
                    Text(
                        text = rowStatusText(state, download),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (download != null && download.status == DownloadStatus.Downloading) {
                    LinearProgressIndicator(
                        progress = { download.progressFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.status == BulkFetchStatus.Loading || state.status == BulkFetchStatus.Waiting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun rowStatusText(state: BulkEpisodeState, download: DownloadItem?): String {
    if (download != null) {
        return when (download.status) {
            DownloadStatus.Downloading ->
                stringResource(Res.string.bulk_download_state_downloading, (download.progressFraction * 100).toInt())
            DownloadStatus.Completed -> stringResource(Res.string.bulk_download_state_completed)
            DownloadStatus.Paused -> stringResource(Res.string.bulk_download_state_paused)
            DownloadStatus.Failed -> stringResource(Res.string.bulk_download_state_failed)
        }
    }
    return when (state.status) {
        BulkFetchStatus.Waiting -> stringResource(Res.string.bulk_download_status_waiting)
        BulkFetchStatus.Loading -> stringResource(Res.string.bulk_download_status_loading)
        BulkFetchStatus.NoStream -> stringResource(Res.string.bulk_download_status_no_stream)
        BulkFetchStatus.Failed -> stringResource(Res.string.bulk_download_status_failed)
        BulkFetchStatus.Skipped -> stringResource(Res.string.bulk_download_status_skipped)
        BulkFetchStatus.Ready -> ""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeActionsSheet(
    state: BulkEpisodeState,
    onDismiss: () -> Unit,
    onPlay: (StreamItem) -> Unit,
    onSwitch: () -> Unit,
    onReload: () -> Unit,
    onDownloadThis: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    val stream = state.selected

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            SheetHeader(
                title = listOfNotNull(
                    localizedSeasonEpisodeCode(state.ref.seasonNumber, state.ref.episodeNumber),
                    localizedEpisodeTitle(state.ref.title, state.ref.episodeNumber),
                ).joinToString(" · "),
                subtitle = null,
            )
            if (stream != null) {
                StreamDetailBlock(
                    stream = stream,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            NuvioBottomSheetDivider()
            if (stream != null) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.PlayArrow,
                    title = stringResource(Res.string.bulk_download_play),
                    onClick = {
                        onPlay(stream)
                        dismiss()
                    },
                )
            }
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.SwapHoriz,
                title = stringResource(Res.string.bulk_download_switch_stream),
                onClick = {
                    onSwitch()
                    dismiss()
                },
            )
            if (stream != null) {
                NuvioBottomSheetActionRow(
                    icon = Icons.Rounded.Download,
                    title = stringResource(Res.string.bulk_download_download_this),
                    onClick = {
                        onDownloadThis()
                        dismiss()
                    },
                )
            }
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.Refresh,
                title = stringResource(Res.string.bulk_download_reload_streams),
                onClick = {
                    onReload()
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
