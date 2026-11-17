package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SubtitleSyncModelTest {
    @Test
    fun retimedLinesPlayWhereTheDelayWouldShowThem() {
        // Frame-rate stretch plus a scene the release adds (+2.4 s from 5:00) and one it cuts (-3 s from 20:00).
        val model = SubtitleSyncModel(
            listOf(
                SubtitleSyncSegment(0L, 1.001, 1_500.0),
                SubtitleSyncSegment(300_000L, 1.001, 3_900.0),
                SubtitleSyncSegment(1_200_000L, 1.001, 900.0),
            ),
        )
        var dropped = 0
        for (subtitleMs in 0L..1_800_000L step 700L) {
            val mediaUs = model.mediaTimeUs(subtitleMs * 1_000L)
            if (mediaUs == null) {
                dropped++
                continue
            }
            // A subtitle delay shows subtitle time (media - delay) at that media time: the same line.
            val shownMs = (mediaUs - model.delayUsAt(mediaUs)) / 1_000.0
            assertTrue(abs(shownMs - subtitleMs) < 1.0, "line at ${subtitleMs}ms plays at ${mediaUs / 1_000}ms showing ${shownMs}ms")
        }
        // Only lines of the cut 3 s are dropped.
        assertTrue(dropped in 1..6, "dropped $dropped")
    }

    @Test
    fun singleSegmentIsAPlainShift() {
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, 2_000.0)))
        assertEquals(12_000_000L, model.mediaTimeUs(10_000_000L))
        assertEquals(2_000_000L, model.delayUsAt(50_000_000L))
    }

    @Test
    fun theFirstSegmentAlsoCoversLinesBeforeItsStart() {
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(60_000L, 1.0, -500.0)))
        assertEquals(-1_500_000L, model.mediaTimeUs(-1_000_000L))
    }

    @Test
    fun aModelNeedsAtLeastOneSegment() {
        assertFailsWith<IllegalArgumentException> { SubtitleSyncModel(emptyList()) }
    }
}
