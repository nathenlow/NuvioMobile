@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubtitleRetimerTest {
    private fun cue(startMs: Long, durationMs: Long, text: String = "line") =
        CuesWithTiming(listOf(Cue.Builder().setText(text).build()), startMs * 1_000L, durationMs * 1_000L)

    @Test
    fun withoutAMappingTheCuesAreReturnedAsTheyAre() {
        val cues = listOf(cue(1_000, 2_000))
        assertSame(cues, SubtitleRetimer.retime(cues, null))
    }

    @Test
    fun aShiftMovesEveryLineAndKeepsItsLength() {
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 2_500.0)))
        val retimed = SubtitleRetimer.retime(listOf(cue(10_000, 3_000), cue(20_000, 1_000)), model)
        assertEquals(listOf(12_500_000L, 22_500_000L), retimed.map { it.startTimeUs })
        assertEquals(listOf(3_000_000L, 1_000_000L), retimed.map { it.durationUs })
    }

    @Test
    fun aFrameRateStretchAlsoStretchesTheLineLength() {
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.1, 0.0)))
        val retimed = SubtitleRetimer.retime(listOf(cue(10_000, 2_000)), model).single()
        assertEquals(11_000_000L, retimed.startTimeUs)
        assertEquals(2_200_000L, retimed.durationUs)
    }

    @Test
    fun linesOfASceneTheReleaseCutAreDropped() {
        // From 1:00 the film is 5 s shorter than the subtitle: lines in that gap never play.
        val model = SubtitleSyncModel(
            listOf(SubtitleSyncSegment(0L, 1.0, 0.0), SubtitleSyncSegment(60_000L, 1.0, -5_000.0)),
        )
        val retimed = SubtitleRetimer.retime(listOf(cue(30_000, 1_000), cue(62_000, 1_000), cue(70_000, 1_000)), model)
        assertEquals(listOf(30_000_000L, 65_000_000L), retimed.map { it.startTimeUs })
    }

    @Test
    fun theResultIsInTimeOrder() {
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 0.0)))
        val retimed = SubtitleRetimer.retime(listOf(cue(30_000, 1_000), cue(10_000, 1_000)), model)
        assertEquals(listOf(10_000_000L, 30_000_000L), retimed.map { it.startTimeUs })
    }

    @Test
    fun aCueWithoutTimingPassesThrough() {
        val untimed = CuesWithTiming(emptyList(), C.TIME_UNSET, C.TIME_UNSET)
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 1_000.0)))
        assertTrue(SubtitleRetimer.retime(listOf(untimed), model).single() === untimed)
    }

    @Test
    fun dialogueIsReadAsStartEndAndText() {
        val dialogue = SubtitleRetimer.dialogueOf(
            listOf(
                cue(1_000, 2_000, "Hello"),
                CuesWithTiming(emptyList(), C.TIME_UNSET, C.TIME_UNSET),
                CuesWithTiming(listOf(Cue.Builder().setText("No end").build()), 5_000_000L, C.TIME_UNSET),
            ),
        )
        assertEquals(listOf(Triple(1_000L, 3_000L, "Hello")), dialogue)
    }
}
