package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.bulk_download_option_choose
import nuvio.composeapp.generated.resources.bulk_download_option_count
import nuvio.composeapp.generated.resources.bulk_download_options_title
import nuvio.composeapp.generated.resources.downloads_delete_series_all
import org.jetbrains.compose.resources.stringResource

/** One way of choosing episodes to download, such as "Unwatched episodes" or "Season 2". */
data class BulkDownloadOption(
    val label: String,
    val episodes: List<MetaVideo>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkDownloadOptionsSheet(
    options: List<BulkDownloadOption>,
    onDismiss: () -> Unit,
    onSelect: (BulkDownloadOption) -> Unit,
    /** Number of downloaded episodes; when above zero a delete-all row is shown. */
    downloadedCount: Int = 0,
    onDeleteAllDownloaded: (() -> Unit)? = null,
    /** Opens a list to tick individual episodes; the row is hidden when null. */
    onChooseEpisodes: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Text(
                text = stringResource(Res.string.bulk_download_options_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            NuvioBottomSheetDivider()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.Download,
                        title = option.label,
                        onClick = {
                            onSelect(option)
                            dismiss()
                        },
                        trailingContent = {
                            Text(
                                text = stringResource(Res.string.bulk_download_option_count, option.episodes.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                if (onChooseEpisodes != null) {
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.Checklist,
                        title = stringResource(Res.string.bulk_download_option_choose),
                        onClick = {
                            onChooseEpisodes()
                            dismiss()
                        },
                    )
                }
                if (downloadedCount > 0 && onDeleteAllDownloaded != null) {
                    NuvioBottomSheetDivider()
                    NuvioBottomSheetActionRow(
                        icon = Icons.Default.Delete,
                        title = stringResource(Res.string.downloads_delete_series_all),
                        onClick = {
                            onDeleteAllDownloaded()
                            dismiss()
                        },
                        trailingContent = {
                            Text(
                                text = stringResource(Res.string.bulk_download_option_count, downloadedCount),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}
