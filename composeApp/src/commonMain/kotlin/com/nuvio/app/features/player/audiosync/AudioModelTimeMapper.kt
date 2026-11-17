package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.autosync.SubtitleTimeMapper

/** Moves subtitle lines to where the audio showed they belong. */
internal class AudioModelTimeMapper(private val model: SubtitleSyncModel) : SubtitleTimeMapper {
    override fun mapStartMs(originalMs: Long): Long = model.mappedMs(originalMs)

    override fun mapEndMs(originalMs: Long): Long = model.mappedMs(originalMs)
}
