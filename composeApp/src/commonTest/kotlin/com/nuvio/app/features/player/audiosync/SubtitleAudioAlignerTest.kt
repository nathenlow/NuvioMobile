package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubtitleAudioAlignerTest {
    @Test
    fun recoversAConstantShift() {
        val cues = SyntheticSpeech.cues(Random(7), durationMs = 8 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, 2_370.0)
        val timeline = SyntheticSpeech.speechFor(cues, truth, Random(11))
        val estimate = assertNotNull(SyntheticSpeech.estimateAll(timeline, cues))
        assertEquals(1.0, estimate.scale)
        assertTrue(abs(estimate.shiftMs - truth.shiftMs) < 50.0, "shift ${estimate.shiftMs}")
    }

    @Test
    fun recoversANegativeShift() {
        val cues = SyntheticSpeech.cues(Random(8), durationMs = 8 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, -3_200.0)
        val timeline = SyntheticSpeech.speechFor(cues, truth, Random(12))
        val estimate = assertNotNull(SyntheticSpeech.estimateAll(timeline, cues))
        assertTrue(abs(estimate.shiftMs - truth.shiftMs) < 50.0, "shift ${estimate.shiftMs}")
    }

    @Test
    fun detectsAFrameRateMismatch() {
        val cues = SyntheticSpeech.cues(Random(3), durationMs = 12 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 25.0 / 23.976, -1_200.0)
        val timeline = SyntheticSpeech.speechFor(cues, truth, Random(5))
        val estimate = assertNotNull(SyntheticSpeech.estimateAll(timeline, cues))
        assertEquals(truth.scale, estimate.scale, 1e-9)
        assertTrue(abs(estimate.shiftMs - truth.shiftMs) < 80.0, "shift ${estimate.shiftMs}")
    }

    @Test
    fun anEmptyTrackHasNothingToAlign() {
        val cues = SyntheticSpeech.cues(Random(9), durationMs = 4 * 60_000L)
        val timeline = SyntheticSpeech.speechFor(cues, SubtitleSyncSegment(0L, 1.0, 0.0), Random(10))
        val prepared = assertNotNull(
            SubtitleAudioAligner.prepare(
                timeline.segments(),
                minShiftMs = -60_000.0,
                maxShiftMs = 60_000.0,
            ),
        )
        assertEquals(null, prepared.estimate(SubtitleSpeechTrack.fromCues(emptyList())))
    }

    @Test
    fun separateStretchesMatchOneArrayExactly() {
        val spoken = SyntheticSpeech.cues(Random(50), 20_000L, 30 * 60_000L)
        val track = SubtitleSpeechTrack.fromCues(spoken.map { (a, b, t) -> Triple(a - 2_000L, b - 2_000L, t) })
        val timeline = SyntheticSpeech.speechFor(spoken, SyntheticSpeech.IDENTITY, Random(51))
        val frames = (25 * 60_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        val whole = timeline.snapshot(0, frames)
        // Blank out a 5 minute hole so the timeline splits into two stretches.
        val holed = SpeechTimeline()
        val holeFrom = frames / 3
        val holeTo = holeFrom + (5 * 60_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        for (f in 0 until frames) if (f !in holeFrom until holeTo) holed.record(f, whole[f])
        val segments = holed.segments()
        assertEquals(2, segments.size)
        val single = SubtitleAudioAligner.estimate(
            probabilities = holed.snapshot(0, frames), fromFrame = 0, track = track,
            minShiftMs = -60_000.0, maxShiftMs = 60_000.0,
        )!!
        val split = SubtitleAudioAligner.estimate(segments, track, minShiftMs = -60_000.0, maxShiftMs = 60_000.0)!!
        assertEquals(single.scale, split.scale)
        assertEquals(single.shiftMs, split.shiftMs, 1e-6)
        assertEquals(single.peak, split.peak, 1e-9)
        // Only lines where audio is known count as evidence; the single array also counted the hole.
        assertTrue(split.cueCount < single.cueCount, "${split.cueCount} vs ${single.cueCount}")
    }

    @Test
    fun fastCorrelationMatchesDirectComputationWithAndWithoutGaps() {
        val spoken = SyntheticSpeech.cues(Random(60), 5_000L, 90_000L)
        val track = SubtitleSpeechTrack.fromCues(spoken.map { (a, b, t) -> Triple(a - 1_300L, b - 1_300L, t) })
        val base = SyntheticSpeech.speechFor(spoken, SyntheticSpeech.IDENTITY, Random(61)).snapshot(0, 2_500)
        val withGaps = base.copyOf().also { for (f in 900 until 960) it[f] = Float.NaN }
        for (probabilities in listOf(base, withGaps)) {
            val fromFrame = 40
            val slice = probabilities.copyOfRange(fromFrame, probabilities.size)
            val estimate = SubtitleAudioAligner.estimate(
                probabilities = slice, fromFrame = fromFrame, track = track, scales = doubleArrayOf(1.0),
                minShiftMs = -10_000.0, maxShiftMs = 10_000.0,
            )!!
            assertEquals(directBestPeak(slice, fromFrame, track), estimate.peak, 1e-9)
            assertTrue(abs(estimate.shiftMs - 1_300.0) < 64.0, "shift ${estimate.shiftMs}")
        }
    }

    /** Masked Pearson correlation computed lag by lag, without FFTs. */
    private fun directBestPeak(slice: FloatArray, fromFrame: Int, track: SubtitleSpeechTrack): Double {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val bias = SubtitleAudioAligner.DETECTOR_BIAS_MS
        val minLag = (-10_000.0 + bias).roundToInt().floorDiv(frameMs.toInt())
        val maxLag = (10_000.0 + bias).roundToInt().floorDiv(frameMs.toInt()) + 1
        val known = slice.indices.filter { !slice[it].isNaN() }
        val mean = known.sumOf { slice[it].toDouble() } / known.size
        val norm = sqrt(known.sumOf { (slice[it] - mean) * (slice[it] - mean) })
        var bestPeak = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val g = track.render(fromFrame - lag, fromFrame - lag + slice.size, 1.0)
            var c1 = 0.0
            var cm = 0.0
            var cm2 = 0.0
            for (i in known) {
                c1 += (slice[i] - mean) * g[i]
                cm += g[i]
                cm2 += g[i] * g[i]
            }
            val variance = cm2 - cm * cm / known.size
            if (variance > 1e-6 * known.size && cm > 0.5) bestPeak = maxOf(bestPeak, c1 / (norm * sqrt(variance)))
        }
        return bestPeak
    }
}
