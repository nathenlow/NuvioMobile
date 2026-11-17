@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks

/** MergingMediaSource prefixes track ids with the child index (for example "0:2"). */
private val periodPrefix = Regex("^(\\d+:)+")

/**
 * Whether [other] is the same audio track as this one, tolerating the differences between the
 * [Format] an extractor outputs and the one in the player's track groups.
 */
internal fun Format.isSameAudioTrack(other: Format): Boolean {
    if (this == other) return true
    if (sampleMimeType != other.sampleMimeType) return false
    val id = id?.replace(periodPrefix, "")
    val otherId = other.id?.replace(periodPrefix, "")
    if (!id.isNullOrEmpty() && !otherId.isNullOrEmpty()) return id == otherId
    return language == other.language && channelCount == other.channelCount && sampleRate == other.sampleRate
}

/** Format of the audio track the player currently has selected, if any. */
internal fun Tracks.selectedAudioFormat(): Format? {
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
    }
    return null
}
