package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.audiosync.AudioSyncStatus
import com.nuvio.app.features.player.audiosync.SubtitleSyncModel

/**
 * Syncs a subtitle to the speech in the audio when AutoSync keeps its original timing: no embedded
 * subtitles, no usable reference, or no confident match. A platform provides one if it can get at the
 * audio; the shared coordinator only needs this much of it.
 */
internal interface AutoSyncAudioFallback {
    /**
     * Starts syncing the subtitle [subtitleKey], whose (startMs, endMs, text) cues are [dialogue].
     * [onModel] is called with each mapping found, an early estimate first and then the confirmed one;
     * [onStatus] reports progress. Returns false when nothing was started: the setting is off, the audio
     * is not reachable, or the subtitle has too few dialogue lines to line up.
     */
    fun takeOver(
        subtitleKey: String,
        dialogue: List<Triple<Long, Long, String>>,
        onModel: (SubtitleSyncModel) -> Unit,
        onStatus: (AudioSyncStatus) -> Unit,
    ): Boolean

    /** Ends any sync in progress and releases what it holds. */
    fun stop()
}
