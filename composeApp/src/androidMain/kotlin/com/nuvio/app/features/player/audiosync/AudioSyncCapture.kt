@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import androidx.media3.common.Format

/**
 * Turns the audio of the playing stream into speech probabilities on a [SpeechTimeline].
 *
 * It is the player-facing end of the pipeline: the extractor tap offers compressed samples, the
 * audio sink tap offers decoded PCM, and this class decides whether either is worth analysing. The
 * decisions are cheap and lock-free because they are made on the player's loading and playback threads.
 */
internal class AudioSyncCapture(
    private val timeline: SpeechTimeline,
    private val duty: AnalysisDuty,
    private val weights: () -> SileroVadWeights?,
) : AudioSampleSink, PlaybackPcmListener {
    @Volatile
    var enabled: Boolean = true

    /** A subtitle is being synced: listen to everything. */
    @Volatile
    var sessionActive: Boolean = false

    /**
     * Whether audio is analysed before any subtitle is chosen, so a session that starts later finds
     * audio already heard. Off: nothing is decoded until a session starts.
     */
    @Volatile
    var listensBeforeSession: Boolean = true

    /** Called once per audio format that no look-ahead decoder can handle. */
    @Volatile
    var onLiveOnly: (mimeType: String) -> Unit = {}

    /** True once a format had to be heard through the playback tap instead of the look-ahead copy. */
    @Volatile
    var liveOnly: Boolean = false
        private set

    @Volatile
    var decoderUnavailable: Boolean = false
        private set

    @Volatile
    private var released = false

    @Volatile
    private var decoder: AudioSyncDecoder? = null

    @Volatile
    private var selectedAudioFormat: Format? = null

    /** The first audio format seen, until the player reports which track it selected. */
    @Volatile
    private var provisionalAudioFormat: Format? = null

    fun onAudioTrackSelected(format: Format?) {
        if (format == null || selectedAudioFormat?.isSameAudioTrack(format) == true) return
        selectedAudioFormat = format
        AudioSyncLog.d("audio track selected: ${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz")
    }

    /** Whether [format] is the audio the viewer is listening to. */
    fun isPlayingTrack(format: Format): Boolean {
        val playing = selectedAudioFormat ?: provisionalAudioFormat ?: return false
        return format.isSameAudioTrack(playing)
    }

    /** Loads the speech detector now (off the main thread) so the first samples are not delayed. */
    fun prepare() {
        createDecoder()
    }

    fun release() {
        released = true
        decoder?.release()
    }

    override fun wantsSamples(format: Format): Boolean {
        if (!enabled || released || decoderUnavailable || !listening()) return false
        val selected = selectedAudioFormat
        if (selected != null) return format.isSameAudioTrack(selected)
        val provisional = provisionalAudioFormat ?: format.also { provisionalAudioFormat = it }
        return format.isSameAudioTrack(provisional)
    }

    override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
        if (!duty.shouldAnalyze(timeUs)) return
        val current = decoder ?: createDecoder() ?: return
        current.offer(format, timeUs, data, offset, size)
    }

    override fun onDiscontinuity() {
        decoder?.discontinuity()
    }

    /**
     * Playback audio is only analysed where the look-ahead capture left the timeline unknown: codecs
     * without a usable second decoder, HLS/DASH streams, or gaps. Otherwise it is skipped cheaply.
     */
    override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long): Boolean {
        if (!enabled || released || decoder == null || !listening()) return false
        if (mediaTimeUs < 0 || !duty.shouldAnalyze(mediaTimeUs)) return false
        val from = SpeechTimeline.frameForTimeUs(mediaTimeUs)
        val to = SpeechTimeline.frameForTimeUs(mediaTimeUs + durationUs) + 1
        return timeline.knownFramesIn(from, to) < to - from - 1
    }

    override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
        decoder?.offerPlaybackPcm(mono, frames, sampleRate, mediaTimeUs)
    }

    /** Before a subtitle is picked, listen to the first minutes so a pick can sync at once. */
    private fun listening(): Boolean =
        sessionActive || (listensBeforeSession && timeline.knownFrameCount() < PRE_SESSION_FRAMES)

    @Synchronized
    private fun createDecoder(): AudioSyncDecoder? {
        decoder?.let { return it }
        if (decoderUnavailable || released) return null
        val loaded = weights()
        if (loaded == null) {
            decoderUnavailable = true
            return null
        }
        return AudioSyncDecoder(
            analyzer = SpeechAnalyzer(SileroVad(loaded), timeline),
            liveAnalyzer = SpeechAnalyzer(SileroVad(loaded), timeline),
            onUnsupportedFormat = { mimeType ->
                AudioSyncLog.i("look-ahead capture unavailable for $mimeType; syncing from playback audio instead")
                liveOnly = true
                onLiveOnly(mimeType)
            },
        ).also { decoder = it }
    }

    private companion object {
        /** 10 minutes of audio analysed before any subtitle is chosen. */
        val PRE_SESSION_FRAMES = (10 * 60 * 1_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
    }
}
