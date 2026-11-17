package com.nuvio.app.features.player.audiosync

import java.io.File
import kotlin.math.roundToInt
import kotlin.random.Random

/** A subtitle cue as (startMs, endMs, text), the shape [SubtitleSpeechTrack.fromCues] takes. */
internal typealias TestCue = Triple<Long, Long, String>

/**
 * Test fixtures shared by the audio sync tests: dialogue-like subtitles, the noisy speech detector
 * output they would produce, and a replay loop that feeds a tracker the way playback does.
 */
internal object SyntheticSpeech {
    /** The bundled Silero VAD weights. Host tests run with the module directory as working directory. */
    fun sileroWeights(): SileroVadWeights =
        File("src/androidMain/res/raw/silero_vad_v5_16k.bin").inputStream().use(SileroVadWeights::read)

    /** Dialogue-like cues: 0.8-4.5 s lines separated by 0.2-9 s gaps. */
    fun cues(random: Random, durationMs: Long): List<TestCue> {
        val cues = mutableListOf<TestCue>()
        var t = 5_000L + random.nextLong(20_000L)
        while (t < durationMs) {
            val length = 800L + random.nextLong(3_700L)
            cues += Triple(t, t + length, "line ${cues.size}")
            t += length + 200L + random.nextLong(if (random.nextInt(5) == 0) 9_000L else 2_500L)
        }
        return cues
    }

    /** Noisy detector output for speech that happens where [cues] land under [mapping]. */
    fun speechFor(cues: List<TestCue>, mapping: SubtitleSyncSegment, random: Random): SpeechTimeline {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val end = cues.maxOf { it.second } * mapping.scale + mapping.shiftMs + 30_000
        val frames = (end / frameMs).toInt()
        val speaking = BooleanArray(frames)
        // The real detector reports speech slightly late; the aligner compensates for that.
        val latencyMs = SubtitleAudioAligner.DETECTOR_BIAS_MS
        for ((start, stop, _) in cues) {
            val from = ((start * mapping.scale + mapping.shiftMs + latencyMs) / frameMs).roundToInt().coerceIn(0, frames)
            val to = ((stop * mapping.scale + mapping.shiftMs + latencyMs) / frameMs).roundToInt().coerceIn(0, frames)
            for (f in from until to) speaking[f] = true
        }
        val timeline = SpeechTimeline()
        for (f in 0 until frames) {
            val p = if (speaking[f]) {
                if (random.nextFloat() < 0.25f) random.nextFloat() * 0.3f else 0.7f + random.nextFloat() * 0.3f
            } else {
                if (random.nextFloat() < 0.08f) 0.4f + random.nextFloat() * 0.5f else random.nextFloat() * 0.1f
            }
            timeline.record(f, p)
        }
        return timeline
    }

    /** The best mapping of [cues] over everything [timeline] has heard, or null. */
    fun estimateAll(timeline: SpeechTimeline, cues: List<TestCue>): SubtitleAudioAligner.Estimate? {
        val range = timeline.knownRange()!!
        return SubtitleAudioAligner.estimate(
            probabilities = timeline.snapshot(range.first, range.last + 1),
            fromFrame = range.first,
            track = SubtitleSpeechTrack.fromCues(cues),
            minShiftMs = -60_000.0,
            maxShiftMs = 60_000.0,
        )
    }

    /** Plays [full] back with a 60 s look-ahead, updating the tracker every 10 s. */
    fun replay(
        full: SpeechTimeline,
        cues: List<TestCue>,
        tracker: AudioSyncTracker = AudioSyncTracker(SubtitleSpeechTrack.fromCues(cues)),
    ): SubtitleSyncModel? {
        val range = full.knownRange()!!
        val all = full.snapshot(0, range.last + 1)
        val live = SpeechTimeline()
        var copied = 0
        val endMs = ((range.last + 1) * SpeechTimeline.FRAME_DURATION_MS).toLong()
        var positionMs = 0L
        while (positionMs <= endMs) {
            val horizon = minOf(all.size, ((positionMs + LOOK_AHEAD_MS) / SpeechTimeline.FRAME_DURATION_MS).toInt())
            while (copied < horizon) {
                if (!all[copied].isNaN()) live.record(copied, all[copied])
                copied++
            }
            tracker.update(live, positionMs)
            positionMs += REPLAY_STEP_MS
        }
        return tracker.model
    }

    const val LOOK_AHEAD_MS = 60_000L
    private const val REPLAY_STEP_MS = 10_000L
}
