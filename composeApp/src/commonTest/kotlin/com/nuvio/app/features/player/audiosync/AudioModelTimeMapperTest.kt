package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.autosync.AutoSyncSubtitleTextRetimer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioModelTimeMapperTest {
    private val srt = listOf(
        "1", "00:00:10,000 --> 00:00:12,500", "Hello", "",
        "2", "00:01:00,000 --> 00:01:02,000", "World", "",
    ).joinToString("\n")

    private fun retime(text: String, model: SubtitleSyncModel): String =
        assertNotNull(AutoSyncSubtitleTextRetimer.retime(text, AudioModelTimeMapper(model)))

    @Test
    fun aShiftMovesEveryTimestampAndLeavesTheTextAlone() {
        val out = retime(srt, SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 2_500.0))))
        assertTrue("00:00:12,500 --> 00:00:15,000" in out, out)
        assertTrue("00:01:02,500 --> 00:01:04,500" in out, out)
        assertTrue("Hello" in out && "World" in out)
    }

    @Test
    fun anEarlierShiftMovesLinesBack() {
        val out = retime(srt, SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, -4_500.0))))
        assertTrue("00:00:05,500 --> 00:00:08,000" in out, out)
    }

    @Test
    fun aFrameRateStretchScalesTheTimes() {
        val out = retime(srt, SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.1, 0.0))))
        assertTrue("00:00:11,000 --> 00:00:13,750" in out, out)
        assertTrue("00:01:06,000 --> 00:01:08,200" in out, out)
    }

    @Test
    fun aLineThatWouldGoBeforeTheStartOfTheFilmStaysAtZero() {
        assertEquals(0L, SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, -30_000.0))).mappedMs(5_000L))
    }

    @Test
    fun aSubtitleWithAJumpGetsEachPartsOwnShift() {
        // From 1:00 of the film the release has 5 s more than the subtitle's source.
        val model = SubtitleSyncModel(
            listOf(SubtitleSyncSegment(0L, 1.0, 0.0), SubtitleSyncSegment(60_000L, 1.0, 5_000.0)),
        )
        assertEquals(10_000L, model.mappedMs(10_000L))
        assertEquals(75_000L, model.mappedMs(70_000L))
    }

    @Test
    fun aLineOfACutSceneIsKeptNearTheNearestPartNotDropped() {
        // The release cuts 5 s at 1:00, so a subtitle line at 62 s falls in the gap and has no place in the video.
        val model = SubtitleSyncModel(
            listOf(SubtitleSyncSegment(0L, 1.0, 0.0), SubtitleSyncSegment(60_000L, 1.0, -5_000.0)),
        )
        assertEquals(62_000L, model.mappedMs(62_000L))
        assertEquals(65_000L, model.mappedMs(70_000L))
    }

    @Test
    fun aSubtitleWithNoTimingIsLeftUntouched() {
        assertEquals(null, AutoSyncSubtitleTextRetimer.retime("not a subtitle", AudioModelTimeMapper(SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 1_000.0))))))
    }
}
