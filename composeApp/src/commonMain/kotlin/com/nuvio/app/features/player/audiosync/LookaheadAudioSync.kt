package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.autosync.AutoSyncAudioFallback
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the audio sync needs from a player whose audio it cannot tap: the player's own read-ahead,
 * recorded to a file and decoded to PCM. On iOS this is mpv (LookaheadAudio.swift).
 */
internal interface LookaheadAudioSource {
    /** Starts recording the buffered range that holds the playhead, from its start. False while nothing is buffered. */
    fun startRecording(path: String): Boolean

    fun stopRecording()

    /** Where the recording's first packet sits on the media clock, in seconds; NaN when there is none. */
    fun anchorSeconds(): Double

    /** How far the player has buffered, in seconds on the media clock; NaN while nothing is. */
    fun bufferEndSeconds(): Double

    fun positionMs(): Long

    /**
     * Decodes the recording to 16 kHz mono float PCM at [pcmPath], up to [endSeconds] on the recording's own
     * clock. Blocks, so it runs off the main thread.
     */
    fun decode(recordingPath: String, pcmPath: String, endSeconds: Double): Boolean

    /** The samples of a PCM file from [fromSample] on; empty when there are none. Blocks. */
    fun readPcm(pcmPath: String, fromSample: Int): FloatArray

    fun delete(path: String)
}

/** The speech detector's front end: takes mono PCM stamped with its place on the media clock. */
internal fun interface PcmSink {
    fun accept(samples: FloatArray, count: Int, sampleRate: Int, timeUs: Long)
}

/**
 * Audio subtitle sync for a player that gives no access to its audio, built on its read-ahead: the
 * buffered range is recorded as the player reads it, decoded to PCM, run through the speech detector,
 * and handed to the same alignment code Android uses. There is no second connection and no audio tap.
 *
 * A recording's clock starts at 0, so each recording carries its anchor on the media clock. A seek out of
 * the recorded range, or a recording that stops growing, starts a new recording with a new anchor; what was
 * heard before stays on the timeline. Recording stops as soon as a mapping is confirmed, since it writes
 * the video along with the audio.
 */
internal class LookaheadAudioSync(
    private val scope: CoroutineScope,
    private val source: LookaheadAudioSource,
    private val directory: String,
    /** Creates the speech detector's front end writing into the given timeline; null if it cannot load. */
    private val frontEnd: suspend (SpeechTimeline) -> PcmSink?,
    /** Mappings remembered for this stream, or null. */
    private val store: SyncMappingStore?,
    /** Where decoding and analysis run; the work is CPU-heavy and blocking. */
    private val worker: CoroutineDispatcher,
    private val nowMs: () -> Long,
    private val pollMs: Long = POLL_MS,
) : AutoSyncAudioFallback {
    private var job: Job? = null
    private var nextRecording = 0

    /** [bufferEnd] is how far the player had buffered when recording began. */
    private class Recording(
        val path: String,
        val pcmPath: String,
        val anchorSeconds: Double,
        bufferEnd: Double,
        startedAtMs: Long,
    ) {
        var fedSamples = 0
        var lastGrowthMs = startedAtMs
        var lastBufferEnd = bufferEnd
        var bufferEndAtGrowth = bufferEnd
    }

    override fun takeOver(
        subtitleKey: String,
        dialogue: List<Triple<Long, Long, String>>,
        onModel: (SubtitleSyncModel) -> Unit,
        onStatus: (AudioSyncStatus) -> Unit,
    ): Boolean {
        stop()
        val track = SubtitleSpeechTrack.fromCues(dialogue)
        if (track.size < MIN_AUDIO_SYNC_CUES) {
            AudioSyncLog.i("subtitle has only ${track.size} dialogue cues; audio sync skipped")
            return false
        }
        val session = AlignmentSession(subtitleKey, track)
        job = scope.launch { run(session, onModel, onStatus) }
        return true
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(session: AlignmentSession, onModel: (SubtitleSyncModel) -> Unit, onStatus: (AudioSyncStatus) -> Unit) {
        store?.load(session.key)?.let { remembered ->
            onModel(remembered)
            onStatus(session.adopt(remembered, source.positionMs()))
            return
        }
        val timeline = SpeechTimeline()
        val sink = frontEnd(timeline)
        if (sink == null) {
            AudioSyncLog.w("the speech detector could not be loaded; audio sync skipped")
            return
        }
        onStatus(AudioSyncStatus.Listening)

        var recording: Recording? = null
        var recordedEnough = false
        var announced: SubtitleSyncModel? = null
        var announcedConfirmed = false
        val startedAt = nowMs()
        try {
            while (nowMs() - startedAt < MAX_RUNNING_MS) {
                val positionMs = source.positionMs()
                val current = recording
                if (current != null && needsRestart(current, positionMs)) {
                    end(current)
                    recording = null
                    recordedEnough = false
                }
                if (recording == null && !recordedEnough) recording = begin()
                recording?.let { active ->
                    feed(active, sink)
                    if (active.fedSamples / SAMPLE_RATE_HZ > MAX_RECORDED_SECONDS) {
                        end(active)
                        recording = null
                        recordedEnough = true
                    }
                }

                val update = withContext(worker) { session.update(timeline, positionMs) }
                update.remember?.let { store?.save(session.key, it) }
                update.status?.let(onStatus)
                val model = session.model
                if (model != null && (announced == null || differs(announced, model) || (session.isConfirmed && !announcedConfirmed))) {
                    announced = model
                    announcedConfirmed = session.isConfirmed
                    onModel(model)
                }
                if (session.isConfirmed) return
                delay(pollMs)
            }
            AudioSyncLog.i("audio sync gave up after ${MAX_RUNNING_MS / 1_000} s without a confirmed mapping")
        } finally {
            withContext(NonCancellable) { recording?.let { end(it) } }
        }
    }

    private fun begin(): Recording? {
        val index = nextRecording++
        val path = "$directory/lookahead-$index.mkv"
        if (!source.startRecording(path)) return null
        val anchor = source.anchorSeconds()
        if (!anchor.isFinite()) {
            source.stopRecording()
            source.delete(path)
            return null
        }
        AudioSyncLog.i("recording started at media time ${anchor.toFixed(2)} s")
        // The playhead may already be well inside the buffered range; that is not a seek.
        val bufferEnd = source.bufferEndSeconds().takeIf { it.isFinite() } ?: anchor
        return Recording(path, "$directory/lookahead-$index.pcm", anchor, bufferEnd, nowMs())
    }

    private fun end(recording: Recording) {
        source.stopRecording()
        source.delete(recording.path)
        source.delete(recording.pcmPath)
    }

    /**
     * A seek leaves the recorded range and makes the recording jump, and a recording that stops growing
     * while the buffer does has failed; either way a new recording needs a new anchor.
     */
    private fun needsRestart(recording: Recording, positionMs: Long): Boolean {
        val position = positionMs / 1_000.0
        val bufferEnd = source.bufferEndSeconds()
        val previousEnd = recording.lastBufferEnd
        if (bufferEnd.isFinite()) recording.lastBufferEnd = bufferEnd
        if (position < recording.anchorSeconds - SEEK_BACK_SLACK_SECONDS || position > previousEnd + SEEK_AHEAD_SLACK_SECONDS) {
            AudioSyncLog.i("seek out of the recorded range; starting a new recording")
            return true
        }
        val stalled = bufferEnd.isFinite() &&
            bufferEnd - recording.bufferEndAtGrowth > STALL_BUFFER_GROWTH_SECONDS &&
            nowMs() - recording.lastGrowthMs > STALL_MS
        if (stalled) AudioSyncLog.w("recording stopped growing; starting a new one")
        return stalled
    }

    /** Decodes what has been recorded since the last time and gives only the new audio to the detector. */
    private suspend fun feed(recording: Recording, sink: PcmSink) {
        val bufferEnd = source.bufferEndSeconds()
        if (!bufferEnd.isFinite()) return
        // Stop short of the newest data so the decoder does not wait at a file that is still growing.
        val endSeconds = bufferEnd - recording.anchorSeconds - END_MARGIN_SECONDS
        if (endSeconds * SAMPLE_RATE_HZ - recording.fedSamples < MIN_NEW_SECONDS * SAMPLE_RATE_HZ) return
        if (!withContext(worker) { source.decode(recording.path, recording.pcmPath, endSeconds) }) return
        val fresh = withContext(worker) { source.readPcm(recording.pcmPath, recording.fedSamples) }
        if (fresh.isEmpty()) return
        val firstSample = recording.fedSamples
        withContext(worker) {
            var offset = 0
            while (offset < fresh.size) {
                val count = min(CHUNK_SAMPLES, fresh.size - offset)
                val timeUs = (recording.anchorSeconds * 1_000_000.0).roundToLong() +
                    (firstSample + offset) * 1_000_000L / SAMPLE_RATE_HZ
                sink.accept(fresh.copyOfRange(offset, offset + count), count, SAMPLE_RATE_HZ, timeUs)
                offset += count
            }
        }
        recording.fedSamples = firstSample + fresh.size
        recording.lastGrowthMs = nowMs()
        recording.bufferEndAtGrowth = bufferEnd
    }

    /** A mapping worth telling the subtitle about again: a different frame rate, segment count, or a shift beyond 100 ms. */
    private fun differs(a: SubtitleSyncModel, b: SubtitleSyncModel): Boolean {
        if (a.segments.size != b.segments.size) return true
        return a.segments.indices.any { index ->
            abs(a.segments[index].scale - b.segments[index].scale) > 1e-9 ||
                abs(a.segments[index].shiftMs - b.segments[index].shiftMs) > ANNOUNCE_SHIFT_MS
        }
    }

    internal companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val POLL_MS = 2_000L

        /** Longest a sync runs without a confirmed mapping; recording writes the video too. */
        const val MAX_RUNNING_MS = 10 * 60_000L

        /** Enough audio to lock on in nearly every film; past it recording stops and analysis continues. */
        const val MAX_RECORDED_SECONDS = 360
        private const val CHUNK_SAMPLES = SAMPLE_RATE_HZ
        private const val END_MARGIN_SECONDS = 2.0
        private const val MIN_NEW_SECONDS = 3.0
        private const val SEEK_BACK_SLACK_SECONDS = 1.5
        private const val SEEK_AHEAD_SLACK_SECONDS = 5.0
        private const val STALL_BUFFER_GROWTH_SECONDS = 15.0
        private const val STALL_MS = 20_000L
        private const val ANNOUNCE_SHIFT_MS = 100.0
    }
}
