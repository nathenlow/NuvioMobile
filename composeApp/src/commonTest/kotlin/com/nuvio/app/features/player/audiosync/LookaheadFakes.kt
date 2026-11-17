package com.nuvio.app.features.player.audiosync

import kotlin.math.max

/** A player's read-ahead, scripted by the test: where it is, how far it has buffered, what recording does. */
internal class FakeLookaheadSource : LookaheadAudioSource {
    var positionMs = 0L
    var bufferEnd = Double.NaN

    /** Where the next recording's first packet sits on the media clock. */
    var anchorForNext = 0.0

    /** The next this-many attempts to start a recording fail, as while the buffer is still empty. */
    var failedStartsLeft = 0

    /** Decoding keeps returning the same audio, as a recording that has stopped growing would. */
    var decodeStalled = false

    val startedPaths = mutableListOf<String>()
    val deletedPaths = mutableListOf<String>()
    var stopCount = 0

    private var activePath: String? = null
    private var anchor = Double.NaN
    private val lengths = mutableMapOf<String, Int>()

    override fun startRecording(path: String): Boolean {
        if (failedStartsLeft > 0) {
            failedStartsLeft--
            return false
        }
        startedPaths += path
        activePath = path
        anchor = anchorForNext
        return true
    }

    override fun stopRecording() {
        stopCount++
        activePath = null
    }

    override fun anchorSeconds(): Double = if (activePath != null) anchor else Double.NaN

    override fun bufferEndSeconds(): Double = bufferEnd

    override fun positionMs(): Long = positionMs

    override fun decode(recordingPath: String, pcmPath: String, endSeconds: Double): Boolean {
        val samples = (endSeconds * LookaheadAudioSync.SAMPLE_RATE_HZ).toInt()
        val before = lengths[pcmPath] ?: 0
        lengths[pcmPath] = if (decodeStalled) before else samples
        return true
    }

    override fun readPcm(pcmPath: String, fromSample: Int): FloatArray =
        FloatArray(max(0, (lengths[pcmPath] ?: 0) - fromSample))

    override fun delete(path: String) {
        deletedPaths += path
    }
}

/**
 * Stands in for the speech detector: instead of analysing the samples (which are silence here), it
 * copies the speech a film really has at the times the audio claims to be from. If the engine stamps the
 * audio with the wrong place on the media clock, the wrong speech lands on the timeline.
 */
internal class SpeechReplaySink(
    private val truth: SpeechTimeline,
    private val live: SpeechTimeline,
) : PcmSink {
    class Chunk(val timeUs: Long, val count: Int)

    val chunks = mutableListOf<Chunk>()

    override fun accept(samples: FloatArray, count: Int, sampleRate: Int, timeUs: Long) {
        chunks += Chunk(timeUs, count)
        var offset = 0
        while (offset + SileroVad.CHUNK_SAMPLES <= count) {
            val frame = (timeUs + offset * 1_000_000L / sampleRate).floorDiv(SpeechTimeline.FRAME_DURATION_US).toInt()
            val probability = truth.snapshot(frame, frame + 1)[0]
            if (!probability.isNaN()) live.record(frame, probability)
            offset += SileroVad.CHUNK_SAMPLES
        }
    }
}
