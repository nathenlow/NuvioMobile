package com.nuvio.app.features.player.audiosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleSpeechTrackTest {
    @Test
    fun soundEffectAndLyricCuesAreIgnored() {
        assertTrue(SubtitleSpeechTrack.isDialogue("Where were you?"))
        assertTrue(SubtitleSpeechTrack.isDialogue("JOHN: Where were you?"))
        assertFalse(SubtitleSpeechTrack.isDialogue("[door slams]"))
        assertFalse(SubtitleSpeechTrack.isDialogue("(thunder rumbling)"))
        assertFalse(SubtitleSpeechTrack.isDialogue("♪ la la la ♪"))
    }

    @Test
    fun cuesWithoutTextStillMarkDialogue() {
        assertTrue(SubtitleSpeechTrack.isDialogue(""))
    }

    @Test
    fun trackKeepsOnlyUsableDialogueInTimeOrder() {
        val track = SubtitleSpeechTrack.fromCues(
            listOf(
                Triple(9_000L, 10_000L, "Second line"),
                Triple(1_000L, 2_000L, "First line"),
                Triple(3_000L, 3_500L, "[music playing]"),
                Triple(5_000L, 5_000L, "Zero length"),
                Triple(-100L, 500L, "Starts before zero"),
                Triple(0L, 60_000L, "Far too long to be one spoken line"),
            ),
        )
        assertEquals(2, track.size)
        assertEquals(listOf(1_000L, 9_000L), track.startsMs.toList())
    }

    @Test
    fun renderCoversEachFrameByTheFractionOfItThatIsOnScreen() {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val track = SubtitleSpeechTrack.fromCues(listOf(Triple((frameMs * 2.5).toLong(), (frameMs * 5.0).toLong(), "line")))
        val coverage = track.render(fromFrame = 0, toFrame = 8, scale = 1.0)
        assertEquals(0.0, coverage[1], 1e-9)
        assertTrue(coverage[2] in 0.4..0.6, "half-covered frame was ${coverage[2]}")
        assertEquals(1.0, coverage[3], 1e-9)
        assertEquals(1.0, coverage[4], 0.05)
        assertEquals(0.0, coverage[6], 1e-9)
    }
}
