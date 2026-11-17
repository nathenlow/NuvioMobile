@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.ExtractorsFactory

/**
 * Routes the audio the player demuxes or plays to the capture of the stream being played. The
 * player's extractors and audio output are created before the audio sync for a stream is, so they
 * look their target up here instead of holding it.
 */
internal object AudioSyncTaps {
    private class Registration(val sourceKey: String, val capture: AudioSyncCapture)

    @Volatile
    private var active: Registration? = null

    fun attach(sourceKey: String, capture: AudioSyncCapture) {
        active = Registration(sourceKey, capture)
    }

    fun detach(capture: AudioSyncCapture) {
        if (active?.capture === capture) active = null
    }

    /** Copies the audio of [sourceKey]'s stream while it is demuxed (look-ahead). */
    fun wrapExtractors(factory: ExtractorsFactory, sourceKey: String): ExtractorsFactory =
        AudioSyncExtractorsFactory(factory, ExtractorTap(sourceKey))

    /** Hears the player's own decoded audio where the look-ahead copy can't be decoded. */
    fun wrapAudioSink(sink: AudioSink): AudioSink = PlaybackAudioTap(sink, PlaybackTap)

    private class ExtractorTap(private val sourceKey: String) : AudioSampleSink {
        private fun target() = active?.takeIf { it.sourceKey == sourceKey }?.capture

        override fun wantsSamples(format: Format): Boolean = target()?.wantsSamples(format) == true

        override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
            target()?.onSample(format, timeUs, data, offset, size)
        }

        override fun onDiscontinuity() {
            target()?.onDiscontinuity()
        }
    }

    private object PlaybackTap : PlaybackPcmListener {
        private fun target() = active?.capture

        override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long): Boolean =
            target()?.wantsPlaybackPcm(mediaTimeUs, durationUs) == true

        override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
            target()?.onPlaybackPcm(mono, frames, sampleRate, mediaTimeUs)
        }
    }
}
