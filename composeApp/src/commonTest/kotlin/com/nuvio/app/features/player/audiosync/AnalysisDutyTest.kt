package com.nuvio.app.features.player.audiosync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisDutyTest {
    private val clock = FakeClock()
    private val duty = AnalysisDuty(clock::now)

    private fun minute(index: Int) = index * 60_000_000L + 1L

    @Test
    fun everythingIsAnalysedUntilAMappingIsConfirmed() {
        assertTrue((0 until 6).all { duty.shouldAnalyze(minute(it)) })
    }

    @Test
    fun everythingIsStillAnalysedForTenMinutesAfterTheLock() {
        duty.markLocked()
        clock.advance(9 * 60_000L)
        assertTrue((0 until 6).all { duty.shouldAnalyze(minute(it)) })
    }

    @Test
    fun laterOnlyOneMinuteInThreeIsAnalysed() {
        duty.markLocked()
        clock.advance(11 * 60_000L)
        assertTrue(duty.shouldAnalyze(minute(0)))
        assertFalse(duty.shouldAnalyze(minute(1)))
        assertFalse(duty.shouldAnalyze(minute(2)))
        assertTrue(duty.shouldAnalyze(minute(3)))
    }

    @Test
    fun clearingGoesBackToAnalysingEverything() {
        duty.markLocked()
        clock.advance(11 * 60_000L)
        duty.clear()
        assertTrue((0 until 6).all { duty.shouldAnalyze(minute(it)) })
    }
}
