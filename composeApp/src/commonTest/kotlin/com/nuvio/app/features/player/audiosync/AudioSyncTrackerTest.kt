package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioSyncTrackerTest {
    @Test
    fun locksOntoMatchingSubtitles() {
        val cues = SyntheticSpeech.cues(Random(21), durationMs = 20 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, -4_500.0)
        val speech = SyntheticSpeech.speechFor(cues, truth, Random(22))
        val model = assertNotNull(SyntheticSpeech.replay(speech, cues))
        val delayMs = model.delayUsAt(600_000_000L) / 1_000.0
        assertTrue(abs(delayMs - truth.shiftMs) < 50.0, "delay $delayMs")
    }

    @Test
    fun followsAJumpInSubtitleTiming() {
        val cues = SyntheticSpeech.cues(Random(31), durationMs = 30 * 60_000L)
        // Subtitles made for a cut that lacks 6 seconds after the 12 minute mark.
        val speech = SpeechTimeline()
        val early = SyntheticSpeech.speechFor(cues.filter { it.first < 720_000L }, SubtitleSyncSegment(0L, 1.0, 1_000.0), Random(32))
        val late = SyntheticSpeech.speechFor(cues.filter { it.first >= 720_000L }, SubtitleSyncSegment(0L, 1.0, 7_000.0), Random(33))
        val known = maxOf(early.knownRange()!!.last, late.knownRange()!!.last)
        val a = early.snapshot(0, known + 1)
        val b = late.snapshot(0, known + 1)
        val splitFrame = (721_000.0 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        for (frame in 0..known) speech.record(frame, if (frame < splitFrame) a[frame] else b[frame])
        val model = assertNotNull(SyntheticSpeech.replay(speech, cues))
        val before = model.delayUsAt(300_000_000L) / 1_000.0
        val after = model.delayUsAt(1_500_000_000L) / 1_000.0
        assertTrue(abs(before - 1_000.0) < 80.0, "before $before")
        assertTrue(abs(after - 7_000.0) < 80.0, "after $after")
    }

    @Test
    fun appliesAnEarlyEstimateWithinTheFirstMinute() {
        val cues = SyntheticSpeech.cues(Random(41), durationMs = 10 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, 2_600.0)
        val full = SyntheticSpeech.speechFor(cues, truth, Random(42))
        val tracker = AudioSyncTracker(SubtitleSpeechTrack.fromCues(cues))
        val all = full.snapshot(0, full.knownRange()!!.last + 1)
        val live = SpeechTimeline()
        var copied = 0
        var positionMs = 0L
        var firstMoveMs: Long? = null
        while (positionMs <= 120_000L && firstMoveMs == null) {
            val horizon = minOf(all.size, ((positionMs + SyntheticSpeech.LOOK_AHEAD_MS) / SpeechTimeline.FRAME_DURATION_MS).toInt())
            while (copied < horizon) {
                if (!all[copied].isNaN()) live.record(copied, all[copied])
                copied++
            }
            tracker.update(live, positionMs)
            val applied = tracker.model ?: tracker.provisionalModel
            if (applied != null) {
                firstMoveMs = positionMs
                val delayMs = applied.delayUsAt(positionMs * 1_000L) / 1_000.0
                assertTrue(abs(delayMs - truth.shiftMs) < 150.0, "early delay $delayMs")
            }
            positionMs += 3_000L
        }
        val moved = assertNotNull(firstMoveMs, "no early estimate within two minutes")
        assertTrue(moved <= 60_000L, "first estimate only at ${moved}ms")
    }

    @Test
    fun neverLocksOntoUnrelatedSubtitles() {
        repeat(6) { seed ->
            val spoken = SyntheticSpeech.cues(Random(100 + seed), durationMs = 20 * 60_000L)
            val unrelated = SyntheticSpeech.cues(Random(200 + seed), durationMs = 20 * 60_000L)
            val timeline = SyntheticSpeech.speechFor(spoken, SubtitleSyncSegment(0L, 1.0, 0.0), Random(300 + seed))
            val tracker = AudioSyncTracker(SubtitleSpeechTrack.fromCues(unrelated))
            assertNull(SyntheticSpeech.replay(timeline, unrelated, tracker), "false lock with seed $seed")
            assertNull(tracker.provisionalModel, "false early estimate with seed $seed")
        }
    }

    @Test
    fun anAdoptedMappingIsKeptAndNotSearchedAgain() {
        val cues = SyntheticSpeech.cues(Random(51), durationMs = 10 * 60_000L)
        val tracker = AudioSyncTracker(SubtitleSpeechTrack.fromCues(cues))
        val adopted = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 1_234.0)))
        tracker.adopt(adopted)
        assertTrue(tracker.model === adopted)
        assertNull(tracker.provisionalModel)
    }
}
