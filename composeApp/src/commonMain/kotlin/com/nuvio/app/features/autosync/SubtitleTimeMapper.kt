package com.nuvio.app.features.autosync

/** Where a subtitle line moves to: what rewriting a subtitle file's timestamps needs to know. */
internal interface SubtitleTimeMapper {
    fun mapStartMs(originalMs: Long): Long

    fun mapEndMs(originalMs: Long): Long
}
