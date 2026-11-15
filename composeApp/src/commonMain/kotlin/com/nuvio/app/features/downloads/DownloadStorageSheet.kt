package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The name to show for a storage: known ids are named here, removable ones by the system. */
@Composable
fun downloadStorageName(volume: DownloadStorageVolume): String =
    if (volume.id == "internal") stringResource(Res.string.downloads_storage_internal) else volume.label

/** "1.4 GB" style size for storage screens. */
@Composable
fun formatStorageBytes(bytes: Long): String {
    val gb = 1024.0 * 1024.0 * 1024.0
    val mb = 1024.0 * 1024.0
    return when {
        bytes >= gb -> "${((bytes / gb) * 10).toLong() / 10.0} ${localizedByteUnit("GB")}"
        bytes >= mb -> "${(bytes / mb).toLong()} ${localizedByteUnit("MB")}"
        else -> "${bytes / 1024} ${localizedByteUnit("KB")}"
    }
}

/**
 * Pick where new downloads are saved, and optionally move the existing ones there. Moving runs in
 * the background of the app and shows its progress in [DownloadStorageMigrationDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadStorageSheet(onDismiss: () -> Unit) {
    val downloads by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val volumes = remember { DownloadsRepository.storageVolumes() }
    var selectedId by remember { mutableStateOf(DownloadsRepository.selectedStorageVolumeId()) }
    var moveTarget by remember { mutableStateOf<DownloadStorageVolume?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    val counts = remember(downloads.items, selectedId) {
        volumes.associate { volume ->
            volume.id to downloads.items.count { DownloadsRepository.storageVolumeIdOf(it) == volume.id }
        }
    }
    val outsideSelected = remember(downloads.items, selectedId) {
        DownloadsRepository.downloadsOutsideStorage(selectedId).size
    }

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Text(
                text = stringResource(Res.string.downloads_storage_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(Res.string.downloads_storage_sheet_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            NuvioBottomSheetDivider()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                volumes.forEach { volume ->
                    NuvioBottomSheetActionRow(
                        icon = if (volume.removable) Icons.Rounded.SdStorage else Icons.Rounded.Smartphone,
                        title = downloadStorageName(volume),
                        selected = volume.id == selectedId,
                        onClick = {
                            DownloadsRepository.selectStorageVolume(volume.id)
                            selectedId = volume.id
                            if (DownloadsRepository.downloadsOutsideStorage(volume.id).isNotEmpty()) {
                                moveTarget = volume
                            }
                        },
                        trailingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(Res.string.downloads_storage_count, counts[volume.id] ?: 0),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(Res.string.downloads_storage_free, formatStorageBytes(volume.freeBytes)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                if (outsideSelected > 0) {
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Rounded.Download,
                        title = stringResource(Res.string.downloads_storage_move_here),
                        onClick = { moveTarget = volumes.firstOrNull { it.id == selectedId } },
                        trailingContent = {
                            Text(
                                text = stringResource(Res.string.downloads_storage_move_here_description, outsideSelected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }

    moveTarget?.let { target ->
        val outside = remember(target) { DownloadsRepository.downloadsOutsideStorage(target.id) }
        val bytes = outside.sumOf { it.totalBytes ?: it.downloadedBytes }
        NuvioStatusModal(
            title = stringResource(Res.string.downloads_storage_move_title),
            message = stringResource(
                Res.string.downloads_storage_move_message,
                outside.size,
                formatStorageBytes(bytes),
                downloadStorageName(target),
            ),
            isVisible = true,
            confirmText = stringResource(Res.string.downloads_storage_move_confirm),
            dismissText = stringResource(Res.string.downloads_storage_keep),
            onConfirm = {
                moveTarget = null
                startStorageMove(target)
                dismiss()
            },
            onDismiss = { moveTarget = null },
        )
    }
}

private fun startStorageMove(target: DownloadStorageVolume) {
    DownloadsRepository.startMoveToStorage(target.id) { result ->
        val message = when (result) {
            is StorageMoveResult.Done -> when {
                result.moved == 0 && result.failed == 0 -> null
                result.failed > 0 -> getStringBlocking { getString(Res.string.downloads_storage_moved_failed, result.moved, result.failed) }
                else -> getStringBlocking { getString(Res.string.downloads_storage_moved, result.moved) }
            }
            StorageMoveResult.ActiveDownloads -> getStringBlocking { getString(Res.string.downloads_storage_active) }
            is StorageMoveResult.NotEnoughSpace -> getStringBlocking {
                getString(
                    Res.string.downloads_storage_no_space,
                    target.label.ifBlank { getString(Res.string.downloads_storage_internal) },
                    formatBytesPlain(result.neededBytes),
                    formatBytesPlain(result.freeBytes),
                )
            }
            StorageMoveResult.NotConnected -> getStringBlocking { getString(Res.string.downloads_storage_not_connected) }
            StorageMoveResult.Busy -> getStringBlocking { getString(Res.string.downloads_storage_busy) }
        }
        message?.let(NuvioToastController::show)
    }
}

private fun getStringBlocking(block: suspend () -> String): String =
    kotlinx.coroutines.runBlocking { block() }

private fun formatBytesPlain(bytes: Long): String {
    val gb = 1024.0 * 1024.0 * 1024.0
    return if (bytes >= gb) "${((bytes / gb) * 10).toLong() / 10.0} GB" else "${bytes / (1024 * 1024)} MB"
}

/** Shown while downloads are being moved; moving carries on in the background if it is left open. */
@Composable
fun DownloadStorageMigrationDialog() {
    val state by DownloadsRepository.storageMigration.collectAsStateWithLifecycle()
    if (!state.running) return
    DialogSurface(
        onDismissRequest = {},
        title = stringResource(Res.string.downloads_storage_moving),
    ) {
        Text(
            text = state.currentTitle.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(
            progress = {
                if (state.totalBytes > 0L) (state.movedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(
                Res.string.downloads_storage_moving_progress,
                (state.doneItems + 1).coerceAtMost(state.totalItems),
                state.totalItems,
                formatStorageBytes(state.movedBytes),
                formatStorageBytes(state.totalBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = DownloadsRepository::cancelStorageMove,
            )
        }
    }
}
