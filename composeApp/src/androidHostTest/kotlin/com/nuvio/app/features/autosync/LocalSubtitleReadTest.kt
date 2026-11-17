package com.nuvio.app.features.autosync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalSubtitleReadTest {
    private fun temp(text: String): File =
        File.createTempFile("sub", ".srt").apply { writeText(text); deleteOnExit() }

    @Test
    fun readsASavedSubtitleFile() = runTest {
        val file = temp("1\n00:00:01,000 --> 00:00:02,000\nHello\n")
        val response = AutoSyncSubtitleHttp.get(file.toURI().toString(), emptyMap(), 1024 * 1024)
        assertEquals(200, response.status)
        assertTrue("Hello" in response.body)
    }

    @Test
    fun truncatesAFileOverTheLimit() = runTest {
        val file = temp("x".repeat(100))
        val response = AutoSyncSubtitleHttp.get(file.toURI().toString(), emptyMap(), 10)
        assertTrue(response.body.endsWith("...[truncated]"))
    }
}
