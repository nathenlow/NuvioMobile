package com.nuvio.app.features.autosync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_label_synced
import nuvio.composeapp.generated.resources.autosync_label_syncing_audio
import org.jetbrains.compose.resources.stringResource

/**
 * What AutoSync is doing to the add-on subtitle on screen: [url] is the subtitle whose timing it
 * corrected or confirmed, [syncingUrl] one the audio fallback is still lining up with the speech.
 * Both are cleared whenever the sidecar starts or stops a subtitle, since that renders the original
 * timing.
 */
internal object AutoSyncSyncedSubtitle {
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url.asStateFlow()

    private val _syncingUrl = MutableStateFlow<String?>(null)
    val syncingUrl: StateFlow<String?> = _syncingUrl.asStateFlow()

    /** [subtitleUrl]'s timing was corrected or confirmed. */
    fun mark(subtitleUrl: String) {
        _url.value = subtitleUrl
        _syncingUrl.value = null
    }

    /** [subtitleUrl] is still being lined up with the audio, so its timing is not confirmed yet. */
    fun markSyncing(subtitleUrl: String) {
        _syncingUrl.value = subtitleUrl
        _url.value = null
    }

    fun clear() {
        _url.value = null
        _syncingUrl.value = null
    }
}

/**
 * Chip for the selected add-on subtitle in the subtitle panel: "Auto synced" once its timing is
 * confirmed, "Syncing to audio…" while the audio fallback is still working; empty otherwise.
 */
@Composable
internal fun AutoSyncedChip(subtitleUrl: String, selected: Boolean) {
    val syncedUrl by AutoSyncSyncedSubtitle.url.collectAsState()
    val syncingUrl by AutoSyncSyncedSubtitle.syncingUrl.collectAsState()
    if (!selected) return
    when (subtitleUrl) {
        syncedUrl -> AutoSyncStateChip(stringResource(Res.string.autosync_label_synced), syncing = false)
        syncingUrl -> AutoSyncStateChip(stringResource(Res.string.autosync_label_syncing_audio), syncing = true)
    }
}

@Composable
private fun AutoSyncStateChip(label: String, syncing: Boolean) {
    val tokens = MaterialTheme.nuvio
    val contentColor = tokens.colors.onAccent.copy(alpha = 0.9f)
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(tokens.colors.onAccent.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (syncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = contentColor,
                strokeWidth = 1.5.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}
