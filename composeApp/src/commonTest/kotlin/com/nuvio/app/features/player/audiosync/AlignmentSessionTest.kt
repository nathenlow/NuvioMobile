package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlignmentSessionTest {
    /** What a session reported while [full] played back with a 60 s look-ahead. */
    private class Replay(val statuses: List<AudioSyncStatus>, val updates: List<AlignmentSession.Update>)

    private fun play(session: AlignmentSession, full: SpeechTimeline): Replay {
        val range = full.knownRange()!!
        val all = full.snapshot(0, range.last + 1)
        val live = SpeechTimeline()
        var copied = 0
        val endMs = ((range.last + 1) * SpeechTimeline.FRAME_DURATION_MS).toLong()
        val updates = mutableListOf<AlignmentSession.Update>()
        var positionMs = 0L
        while (positionMs <= endMs) {
            val horizon = minOf(all.size, ((positionMs + SyntheticSpeech.LOOK_AHEAD_MS) / SpeechTimeline.FRAME_DURATION_MS).toInt())
            while (copied < horizon) {
                if (!all[copied].isNaN()) live.record(copied, all[copied])
                copied++
            }
            updates += session.update(live, positionMs)
            positionMs += 10_000L
        }
        return Replay(updates.mapNotNull { it.status }, updates)
    }

    @Test
    fun anEarlyEstimateComesBeforeTheConfirmedSync() {
        val cues = SyntheticSpeech.cues(Random(21), durationMs = 20 * 60_000L)
        val truth = SubtitleSyncSegment(0L, 1.0, -4_500.0)
        val session = AlignmentSession("subtitle", SubtitleSpeechTrack.fromCues(cues))
        val replay = play(session, SyntheticSpeech.speechFor(cues, truth, Random(22)))

        val estimated = replay.statuses.indexOfFirst { it is AudioSyncStatus.Estimated }
        val synced = replay.statuses.indexOfFirst { it is AudioSyncStatus.Synced }
        assertTrue(synced >= 0, "never synced: ${replay.statuses}")
        assertTrue(estimated in 0 until synced, "estimate at $estimated, sync at $synced")
        assertTrue(session.isConfirmed)
        val delayMs = session.model!!.delayUsAt(600_000_000L) / 1_000.0
        assertTrue(abs(delayMs - truth.shiftMs) < 50.0, "delay $delayMs")
    }

    @Test
    fun theLockIsConfirmedAndRememberedExactlyOnce() {
        val cues = SyntheticSpeech.cues(Random(21), durationMs = 20 * 60_000L)
        val session = AlignmentSession("subtitle", SubtitleSpeechTrack.fromCues(cues))
        val replay = play(session, SyntheticSpeech.speechFor(cues, SubtitleSyncSegment(0L, 1.0, -4_500.0), Random(22)))

        val confirming = replay.updates.filter { it.confirmed }
        assertEquals(1, confirming.size)
        assertNotNull(confirming.single().remember)
        assertIs<AudioSyncStatus.Synced>(confirming.single().status)
    }

    @Test
    fun aSubtitleThatDoesNotMatchTheSpeechNeverSyncs() {
        val spoken = SyntheticSpeech.cues(Random(100), durationMs = 20 * 60_000L)
        val unrelated = SyntheticSpeech.cues(Random(200), durationMs = 20 * 60_000L)
        val session = AlignmentSession("subtitle", SubtitleSpeechTrack.fromCues(unrelated))
        val replay = play(session, SyntheticSpeech.speechFor(spoken, SyntheticSpeech.IDENTITY, Random(300)))

        assertTrue(replay.statuses.none { it is AudioSyncStatus.Synced }, "${replay.statuses}")
        assertTrue(replay.updates.none { it.confirmed || it.remember != null })
        assertFalse(session.isConfirmed)
    }

    @Test
    fun aTimingJumpIsReportedAndRemembered() {
        val cues = SyntheticSpeech.cues(Random(31), durationMs = 30 * 60_000L)
        val early = SyntheticSpeech.speechFor(cues.filter { it.first < 720_000L }, SubtitleSyncSegment(0L, 1.0, 1_000.0), Random(32))
        val late = SyntheticSpeech.speechFor(cues.filter { it.first >= 720_000L }, SubtitleSyncSegment(0L, 1.0, 7_000.0), Random(33))
        val known = maxOf(early.knownRange()!!.last, late.knownRange()!!.last)
        val a = early.snapshot(0, known + 1)
        val b = late.snapshot(0, known + 1)
        val splitFrame = (721_000.0 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        val speech = SpeechTimeline()
        for (frame in 0..known) speech.record(frame, if (frame < splitFrame) a[frame] else b[frame])

        val replay = play(AlignmentSession("subtitle", SubtitleSpeechTrack.fromCues(cues)), speech)

        val adjusted = replay.updates.firstOrNull { it.status is AudioSyncStatus.Adjusted }
        assertNotNull(adjusted, "no jump reported: ${replay.statuses}")
        assertNotNull(adjusted.remember)
        assertEquals(2, adjusted.remember!!.segments.size)
    }

    @Test
    fun aRememberedMappingStartsConfirmed() {
        val cues = SyntheticSpeech.cues(Random(51), durationMs = 10 * 60_000L)
        val session = AlignmentSession("subtitle", SubtitleSpeechTrack.fromCues(cues))
        val remembered = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 25.0 / 23.976, 1_234.0)))

        val status = session.adopt(remembered, positionMs = 30_000L)

        assertTrue(session.isConfirmed)
        assertEquals(remembered, session.model)
        assertTrue(status.rateCorrected)
        assertEquals(remembered.delayUsAt(30_000_000L) / 1_000L, status.offsetMs)
    }

    @Test
    fun withoutAnyAudioThereIsNothingToReport() {
        val session = AlignmentSession("subtitle", SubtitleSpeechTrack.fromCues(SyntheticSpeech.cues(Random(1), 10 * 60_000L)))
        val update = session.update(SpeechTimeline(), positionMs = 0L)
        assertNull(update.status)
        assertNull(update.remember)
        assertFalse(update.confirmed)
        assertNull(session.model)
    }
}
