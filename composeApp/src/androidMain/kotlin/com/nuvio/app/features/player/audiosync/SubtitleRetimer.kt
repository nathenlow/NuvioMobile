@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import androidx.media3.common.C
import androidx.media3.extractor.text.CuesWithTiming

/** Conversions between a sidecar subtitle's cues and what audio sync reasons about. */
internal object SubtitleRetimer {
    /**
     * [cues] placed on the media timeline by [model] (subtitle time -> media time); the original
     * cues when there is no mapping. Lines of a scene the release cuts are dropped, as a subtitle
     * delay would never show them either.
     */
    fun retime(cues: List<CuesWithTiming>, model: SubtitleSyncModel?): List<CuesWithTiming> {
        if (model == null) return cues
        return cues.mapNotNull { entry ->
            if (entry.startTimeUs == C.TIME_UNSET) return@mapNotNull entry
            val startUs = model.mediaTimeUs(entry.startTimeUs) ?: return@mapNotNull null
            val durationUs = durationOf(entry)
            val scaledUs = if (durationUs == C.TIME_UNSET) durationUs else (durationUs * model.segmentAt(startUs / 1_000L).scale).toLong()
            CuesWithTiming(entry.cues, startUs, scaledUs)
        }.sortedBy { it.startTimeUs }
    }

    /** The (startMs, endMs, text) form [SubtitleSpeechTrack.fromCues] takes; cues without timing are skipped. */
    fun dialogueOf(cues: List<CuesWithTiming>): List<Triple<Long, Long, String>> =
        cues.mapNotNull { entry ->
            val startUs = entry.startTimeUs
            if (startUs == C.TIME_UNSET) return@mapNotNull null
            val endUs = when {
                entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
                entry.durationUs != C.TIME_UNSET -> startUs + entry.durationUs
                else -> return@mapNotNull null
            }
            val text = entry.cues.joinToString("\n") { it.text?.toString().orEmpty() }
            Triple(startUs / 1_000L, endUs / 1_000L, text)
        }

    private fun durationOf(entry: CuesWithTiming): Long = when {
        entry.durationUs != C.TIME_UNSET -> entry.durationUs
        entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs - entry.startTimeUs
        else -> C.TIME_UNSET
    }
}
