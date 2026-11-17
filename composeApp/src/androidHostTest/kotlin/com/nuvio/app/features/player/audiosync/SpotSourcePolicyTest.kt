package com.nuvio.app.features.player.audiosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotSourcePolicyTest {
    @Test
    fun plainHttpAndHttpsFilesCanBeSampled() {
        assertNull(SpotSourcePolicy.unavailableReason("https", "/movie.mkv", localEngine = false))
        assertNull(SpotSourcePolicy.unavailableReason("http", "/movie.mp4", localEngine = false))
        assertNull(SpotSourcePolicy.unavailableReason("https", null, localEngine = false))
    }

    @Test
    fun aDownloadedFileCanBeSampled() {
        assertNull(SpotSourcePolicy.unavailableReason("file", "/data/film.mkv", localEngine = false))
    }

    @Test
    fun playlistsCannotBeSampled() {
        assertNotNull(SpotSourcePolicy.unavailableReason("https", "/stream/Master.M3U8", localEngine = false))
        assertNotNull(SpotSourcePolicy.unavailableReason("https", "/manifest.mpd", localEngine = false))
    }

    @Test
    fun otherSchemesCannotBeSampled() {
        assertNotNull(SpotSourcePolicy.unavailableReason("content", "/movie.mkv", localEngine = false))
        assertNotNull(SpotSourcePolicy.unavailableReason(null, "/movie.mkv", localEngine = false))
    }

    @Test
    fun aStreamServedByThisDeviceIsNotSampled() {
        assertNotNull(SpotSourcePolicy.unavailableReason("http", "/movie.mkv", localEngine = true))
    }

    @Test
    fun loopbackHostsAreRecognised() {
        listOf("localhost", "LOCALHOST", "127.0.0.1", "::1", "[::1]").forEach { assertTrue(SpotSourcePolicy.isLoopbackHost(it), it) }
        listOf("example.com", "192.168.1.5", "", null).forEach { assertFalse(SpotSourcePolicy.isLoopbackHost(it), "$it") }
        assertEquals(false, SpotSourcePolicy.isLoopbackHost(null))
    }
}
