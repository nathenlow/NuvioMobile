package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.audiosync.AudioSyncStatus
import com.nuvio.app.features.player.audiosync.SubtitleSyncModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingGatedAudioFallbackTest {
    private class Recording : AutoSyncAudioFallback {
        var takeOvers = 0
        var stops = 0
        var result = true

        override fun takeOver(
            subtitleKey: String,
            dialogue: List<Triple<Long, Long, String>>,
            onModel: (SubtitleSyncModel) -> Unit,
            onStatus: (AudioSyncStatus) -> Unit,
        ): Boolean {
            takeOvers++
            return result
        }

        override fun stop() {
            stops++
        }
    }

    private fun startWith(gate: SettingGatedAudioFallback) = gate.takeOver("subtitle", emptyList(), {}, {})

    @Test
    fun startsOnlyWhileTheSettingIsOn() {
        val inner = Recording()
        var enabled = false
        val gate = SettingGatedAudioFallback(inner) { enabled }

        assertFalse(startWith(gate))
        assertEquals(0, inner.takeOvers)

        enabled = true
        assertTrue(startWith(gate))
        assertEquals(1, inner.takeOvers)
    }

    @Test
    fun reportsWhetherTheFallbackReallyStarted() {
        val inner = Recording().apply { result = false }
        assertFalse(startWith(SettingGatedAudioFallback(inner) { true }))
        assertEquals(1, inner.takeOvers)
    }

    @Test
    fun stoppingAlwaysReachesTheFallbackEvenWithTheSettingOff() {
        val inner = Recording()
        SettingGatedAudioFallback(inner) { false }.stop()
        assertEquals(1, inner.stops)
    }
}
