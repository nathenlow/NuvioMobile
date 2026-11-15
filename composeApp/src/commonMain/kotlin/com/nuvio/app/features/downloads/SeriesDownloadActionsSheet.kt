package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_cancel_series_action
import nuvio.composeapp.generated.resources.downloads_move_episode_to_top
import nuvio.composeapp.generated.resources.downloads_move_series_to_top
import org.jetbrains.compose.resources.stringResource

/** Shown when an active download is held down: actions for every download of the same series. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDownloadActionsSheet(
    seriesTitle: String,
    onDismiss: () -> Unit,
    onMoveEpisodeToTop: () -> Unit,
    onMoveSeriesToTop: () -> Unit,
    onCancelSeries: () -> Unit,
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
                text = seriesTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.VerticalAlignTop,
                title = stringResource(Res.string.downloads_move_episode_to_top),
                onClick = {
                    onMoveEpisodeToTop()
                    dismiss()
                },
            )
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.VerticalAlignTop,
                title = stringResource(Res.string.downloads_move_series_to_top),
                onClick = {
                    onMoveSeriesToTop()
                    dismiss()
                },
            )
            NuvioBottomSheetActionRow(
                icon = Icons.Rounded.Close,
                title = stringResource(Res.string.downloads_cancel_series_action),
                onClick = {
                    onCancelSeries()
                    dismiss()
                },
            )
        }
    }
}
