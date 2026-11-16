package com.nuvio.app.features.details.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nuvio.app.core.ui.NuvioToastController
import nuvio.composeapp.generated.resources.details_action_download
import nuvio.composeapp.generated.resources.details_action_in_library
import nuvio.composeapp.generated.resources.details_action_library
import nuvio.composeapp.generated.resources.details_action_shuffle
import nuvio.composeapp.generated.resources.details_action_shuffling
import nuvio.composeapp.generated.resources.details_action_sources
import nuvio.composeapp.generated.resources.details_action_sources_description
import nuvio.composeapp.generated.resources.details_action_watched
import nuvio.composeapp.generated.resources.details_added_to_library
import nuvio.composeapp.generated.resources.details_change_list
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.backdropControlColor
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.bulk_download_action
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.hero_mark_unwatched
import nuvio.composeapp.generated.resources.hero_mark_watched
import nuvio.composeapp.generated.resources.hero_remove_from_library
import nuvio.composeapp.generated.resources.playback_unavailable
import nuvio.composeapp.generated.resources.random_episode_title
import nuvio.composeapp.generated.resources.shuffle_stop
import org.jetbrains.compose.resources.stringResource

/** Set by the details screen for shows with episodes; adds a "download episodes" action to [DetailActions]. */
val LocalDetailBulkDownloadAction = staticCompositionLocalOf<(() -> Unit)?> { null }

data class DetailSecondaryAction(
    /** The short label shown under the icon, such as "Watched". */
    val label: String,
    val icon: ImageVector,
    val isActive: Boolean = false,
    val onClick: () -> Unit = {},
    val onLongClick: (() -> Unit)? = null,
    /** The full name read by screen readers, such as "Mark as watched". Defaults to [label]. */
    val description: String? = null,
)

/**
 * The Play button with the other actions in a row below it, each an icon with a short label. They
 * used to sit behind a "more" button, where they were easy to miss.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailActionButtons(
    modifier: Modifier = Modifier,
    playLabel: String = stringResource(Res.string.action_play),
    playEnabled: Boolean = true,
    actions: List<DetailSecondaryAction> = emptyList(),
    isTablet: Boolean = false,
    onPlayClick: () -> Unit = {},
    onPlayLongClick: (() -> Unit)? = null,
) {
    val playPainter = appIconPainter(AppIconResource.PlayerPlay)
    val buttonHeight = if (isTablet) 56.dp else 52.dp
    val playShape = RoundedCornerShape(40.dp)
    val hapticFeedback = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .widthIn(max = if (isTablet) 520.dp else 420.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(buttonHeight),
            shape = playShape,
            color = if (playEnabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (playEnabled) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = playEnabled,
                        onClick = onPlayClick,
                        onLongClick = onPlayLongClick,
                        role = Role.Button,
                    )
                    .height(buttonHeight),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = playPainter,
                    contentDescription = null,
                    modifier = Modifier.size(if (isTablet) 20.dp else 18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = playLabel,
                    style = if (isTablet) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                actions.forEach { action ->
                    DetailLabeledAction(
                        action = action,
                        iconSize = if (isTablet) 48.dp else 44.dp,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            action.onClick()
                        },
                        onLongClick = action.onLongClick?.let { longClick ->
                            {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                longClick()
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailActions(
    playLabel: String,
    playEnabled: Boolean,
    isSaved: Boolean,
    isWatched: Boolean,
    isTablet: Boolean,
    shuffleEnabled: Boolean,
    onPlayClick: () -> Unit,
    onPlayLongClick: (() -> Unit)?,
    onShuffleClick: (() -> Unit)?,
    onWatchedClick: () -> Unit,
    onSaveClick: () -> Unit,
    onSaveLongClick: (() -> Unit)?,
) {
    val bulkDownload = LocalDetailBulkDownloadAction.current
    val addedMessage = stringResource(Res.string.details_added_to_library)
    val changeListLabel = stringResource(Res.string.details_change_list)
    DetailActionButtons(
        playLabel = if (playEnabled) playLabel else stringResource(Res.string.playback_unavailable),
        playEnabled = playEnabled,
        actions = buildList {
            add(
                DetailSecondaryAction(
                    label = stringResource(if (isSaved) Res.string.details_action_in_library else Res.string.details_action_library),
                    description = stringResource(if (isSaved) Res.string.hero_remove_from_library else Res.string.hero_add_to_library),
                    // A bookmark, so it never looks like the Watched tick next to it.
                    icon = if (isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    isActive = isSaved,
                    onClick = {
                        onSaveClick()
                        // Adding is one tap; the toast offers the list picker that long-press opens.
                        if (!isSaved && onSaveLongClick != null) {
                            NuvioToastController.show(
                                message = addedMessage,
                                actionLabel = changeListLabel,
                                onAction = onSaveLongClick,
                            )
                        }
                    },
                    onLongClick = onSaveLongClick,
                ),
            )
            add(
                DetailSecondaryAction(
                    label = stringResource(Res.string.details_action_watched),
                    description = stringResource(if (isWatched) Res.string.hero_mark_unwatched else Res.string.hero_mark_watched),
                    icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                    isActive = isWatched,
                    onClick = onWatchedClick,
                ),
            )
            bulkDownload?.let { onClick ->
                add(
                    DetailSecondaryAction(
                        label = stringResource(Res.string.details_action_download),
                        description = stringResource(Res.string.bulk_download_action),
                        icon = Icons.Default.Download,
                        onClick = onClick,
                    ),
                )
            }
            onShuffleClick?.let { onClick ->
                add(
                    DetailSecondaryAction(
                        label = stringResource(if (shuffleEnabled) Res.string.details_action_shuffling else Res.string.details_action_shuffle),
                        description = stringResource(if (shuffleEnabled) Res.string.shuffle_stop else Res.string.random_episode_title),
                        icon = Icons.Default.Shuffle,
                        isActive = shuffleEnabled,
                        onClick = onClick,
                    ),
                )
            }
            // Only offered when auto play is on, the one time Play does not already ask for a stream.
            if (playEnabled && onPlayLongClick != null) {
                add(
                    DetailSecondaryAction(
                        label = stringResource(Res.string.details_action_sources),
                        description = stringResource(Res.string.details_action_sources_description),
                        icon = Icons.AutoMirrored.Filled.List,
                        onClick = onPlayLongClick,
                    ),
                )
            }
        },
        isTablet = isTablet,
        onPlayClick = onPlayClick,
        onPlayLongClick = onPlayLongClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailLabeledAction(
    action: DetailSecondaryAction,
    iconSize: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val description = action.description ?: action.label
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.size(iconSize),
            shape = CircleShape,
            color = if (action.isActive) {
                MaterialTheme.colorScheme.onBackground
            } else {
                backdropControlColor(MaterialTheme.colorScheme.surfaceVariant)
            },
            contentColor = if (action.isActive) {
                MaterialTheme.colorScheme.background
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = action.icon, contentDescription = null, modifier = Modifier.size(21.dp))
            }
        }
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
