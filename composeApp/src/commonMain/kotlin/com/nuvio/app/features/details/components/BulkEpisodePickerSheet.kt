package com.nuvio.app.features.details.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedEpisodeTitle
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.bulk_download_picker_clear
import nuvio.composeapp.generated.resources.bulk_download_picker_continue
import nuvio.composeapp.generated.resources.bulk_download_picker_select_all
import nuvio.composeapp.generated.resources.bulk_download_picker_title
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import org.jetbrains.compose.resources.stringResource

private fun MetaVideo.pickKey(): String = "${season ?: 0}x${episode ?: 0}"

/**
 * Tick the episodes to download, by season, such as the 2nd and the 5th. Watched ones carry a check
 * mark. Continue opens the download page for just those.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkEpisodePickerSheet(
    episodes: List<MetaVideo>,
    isWatched: (MetaVideo) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<MetaVideo>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }
    val bySeason = remember(episodes) {
        episodes
            .sortedWith(compareBy({ if ((it.season ?: 0) == 0) Int.MAX_VALUE else it.season ?: 0 }, { it.episode ?: 0 }))
            .groupBy { it.season ?: 0 }
            .toList()
    }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val chosen = remember(picked, episodes) {
        episodes.filter { it.pickKey() in picked }
            .sortedWith(compareBy({ if ((it.season ?: 0) == 0) Int.MAX_VALUE else it.season ?: 0 }, { it.episode ?: 0 }))
    }

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState, fullHeight = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.bulk_download_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row {
                    TextButton(onClick = { picked = episodes.mapTo(hashSetOf()) { it.pickKey() } }) {
                        Text(stringResource(Res.string.bulk_download_picker_select_all))
                    }
                    TextButton(enabled = picked.isNotEmpty(), onClick = { picked = emptySet() }) {
                        Text(stringResource(Res.string.bulk_download_picker_clear))
                    }
                }
            }
            NuvioBottomSheetDivider()
            LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 520.dp)) {
                bySeason.forEach { (season, seasonEpisodes) ->
                    item(key = "season:$season") {
                        val keys = seasonEpisodes.map { it.pickKey() }
                        val count = keys.count { it in picked }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (count == keys.size) picked - keys.toSet() else picked + keys
                                }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TriStateCheckbox(
                                state = when (count) {
                                    0 -> ToggleableState.Off
                                    keys.size -> ToggleableState.On
                                    else -> ToggleableState.Indeterminate
                                },
                                onClick = null,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            Text(
                                text = if (season == 0) {
                                    stringResource(Res.string.episodes_specials)
                                } else {
                                    stringResource(Res.string.episodes_season, season)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    items(items = seasonEpisodes, key = { it.pickKey() }) { episode ->
                        val key = episode.pickKey()
                        val label = listOfNotNull(
                            localizedSeasonEpisodeCode(episode.season, episode.episode),
                            localizedEpisodeTitle(episode.title, episode.episode ?: 0),
                        ).joinToString(" · ")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = if (key in picked) picked - key else picked + key }
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = key in picked,
                                onCheckedChange = null,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isWatched(episode)) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            NuvioBottomSheetDivider()
            Button(
                enabled = chosen.isNotEmpty(),
                onClick = {
                    onConfirm(chosen)
                    dismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(stringResource(Res.string.bulk_download_picker_continue, chosen.size))
            }
        }
    }
}
