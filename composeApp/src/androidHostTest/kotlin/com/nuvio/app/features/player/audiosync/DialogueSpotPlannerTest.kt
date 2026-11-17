package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DialogueSpotPlannerTest {
    private val filmMs = 100 * 60_000L

    @Test
    fun spotsLandOnDialogueEvenWithOffTiming() {
        // Dialogue only in a few scenes; the subtitle used for planning is 8 s late.
        val scenes = listOf(12L, 31L, 47L, 66L, 83L).map { it * 60_000L }
        val spoken = scenes.flatMap { scene -> SyntheticSpeech.cues(Random(scene), scene, scene + 90_000L) }
        val lateSubtitle = SubtitleSpeechTrack.fromCues(spoken.map { (a, b, t) -> Triple(a + 8_000L, b + 8_000L, t) })
        val spots = DialogueSpotPlanner.plan(listOf(lateSubtitle), filmMs, count = 4, spotMs = 30_000L)
        assertEquals(4, spots.size)
        for (spot in spots) {
            val speech = spoken.sumOf { (a, b, _) -> overlap(a, b, spot, spot + 30_000L) }
            assertTrue(speech > 10_000L, "spot at ${spot / 1_000}s has only ${speech / 1_000}s of dialogue")
        }
        // Spread over the film, widest first.
        assertTrue(abs(spots[0] - spots[1]) > 20 * 60_000L, "spots $spots")
    }

    @Test
    fun spotOpeningsFindSpeechWhenTheSubtitleIsOff() {
        // Each part of the film has a short dense exchange and a long conversation; the subtitle
        // used for planning is 15 s early. On a high-bitrate stream only a spot's first 8 s are
        // read, so those must be speech, which only the long conversations guarantee.
        val spoken = (0 until 4).flatMap { part ->
            val base = (8 + part * 22) * 60_000L
            SyntheticSpeech.cues(Random(70L + part), base, base + 25_000L) +
                SyntheticSpeech.cues(Random(80L + part), base + 9 * 60_000L, base + 12 * 60_000L)
        }
        val earlySubtitle = SubtitleSpeechTrack.fromCues(spoken.map { (a, b, t) -> Triple(a - 15_000L, b - 15_000L, t) })
        val spots = DialogueSpotPlanner.plan(listOf(earlySubtitle), filmMs, count = 4, spotMs = 30_000L)
        assertEquals(4, spots.size)
        for (spot in spots) {
            val speech = spoken.sumOf { (a, b, _) -> overlap(a, b, spot, spot + 8_000L) }
            assertTrue(speech > 4_000L, "spot at ${spot / 1_000}s opens with only ${speech / 1_000}s of dialogue")
        }
    }

    @Test
    fun spotsAreEvenlySpreadWithoutSubtitles() {
        val spots = DialogueSpotPlanner.plan(emptyList(), filmMs, count = 4, spotMs = 30_000L, notBeforeMs = 5 * 60_000L)
        assertEquals(4, spots.size)
        assertTrue(spots.all { it >= 5 * 60_000L && it <= filmMs - 6 * 60_000L }, "spots $spots")
        val sorted = spots.sorted()
        for (i in 1 until sorted.size) assertTrue(sorted[i] - sorted[i - 1] > 15 * 60_000L, "spots $spots")
    }

    @Test
    fun nothingIsPlannedWhenTheFilmIsTooShort() {
        assertTrue(DialogueSpotPlanner.plan(emptyList(), durationMs = 8 * 60_000L, count = 4, spotMs = 30_000L).isEmpty())
        assertTrue(DialogueSpotPlanner.plan(emptyList(), filmMs, count = 0, spotMs = 30_000L).isEmpty())
    }

    @Test
    fun scatteredSpotsLockQuicklyAndCatchFrameRate() {
        val spoken = SyntheticSpeech.cues(Random(40), 20_000L, filmMs - 5 * 60_000L)
        // Subtitle made for 25 fps, video at 23.976 fps, plus a 1.2 s offset.
        val truth = SubtitleSyncSegment(0L, 25.0 / 23.976, -1_200.0)
        val subtitle = spoken.map { (a, b, t) ->
            Triple(((a - truth.shiftMs) / truth.scale).toLong(), ((b - truth.shiftMs) / truth.scale).toLong(), t)
        }
        val track = SubtitleSpeechTrack.fromCues(subtitle)
        val full = SyntheticSpeech.speechFor(spoken, SyntheticSpeech.IDENTITY, Random(41))
        // Only the first 90 s near the playhead plus four sampled spots are known.
        val live = SpeechTimeline()
        val spots = DialogueSpotPlanner.plan(listOf(track), filmMs, count = 4, spotMs = 30_000L, notBeforeMs = 90_000L)
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val known = listOf(0L to 90_000L) + spots.map { it to it + 30_000L }
        val all = full.snapshot(0, (filmMs / frameMs).toInt())
        for ((from, to) in known) {
            for (f in (from / frameMs).toInt() until (to / frameMs).toInt()) if (!all[f].isNaN()) live.record(f, all[f])
        }
        val tracker = AudioSyncTracker(track)
        tracker.update(live, 10_000L)
        val model = assertNotNull(tracker.model, "no lock from ${known.size} stretches")
        val segment = model.segments.single()
        assertEquals(truth.scale, segment.scale, 1e-9)
        val delayMs = model.delayUsAt(3_000_000_000L) / 1_000.0
        val expectedMs = SubtitleSyncModel(listOf(truth)).delayUsAt(3_000_000_000L) / 1_000.0
        assertTrue(abs(delayMs - expectedMs) < 80.0, "delay $delayMs vs $expectedMs")
    }

    private fun overlap(a: Long, b: Long, from: Long, to: Long): Long = (minOf(b, to) - maxOf(a, from)).coerceAtLeast(0L)
}
