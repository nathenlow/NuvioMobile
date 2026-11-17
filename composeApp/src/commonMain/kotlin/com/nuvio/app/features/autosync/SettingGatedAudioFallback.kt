package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.audiosync.AudioSyncStatus
import com.nuvio.app.features.player.audiosync.SubtitleSyncModel

/** An audio fallback that only starts while its setting is on; turning it off ends nothing already running. */
internal class SettingGatedAudioFallback(
    private val inner: AutoSyncAudioFallback,
    private val isEnabled: () -> Boolean,
) : AutoSyncAudioFallback {
    override fun takeOver(
        subtitleKey: String,
        dialogue: List<Triple<Long, Long, String>>,
        onModel: (SubtitleSyncModel) -> Unit,
        onStatus: (AudioSyncStatus) -> Unit,
    ): Boolean = isEnabled() && inner.takeOver(subtitleKey, dialogue, onModel, onStatus)

    override fun stop() = inner.stop()
}
