package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
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
}
