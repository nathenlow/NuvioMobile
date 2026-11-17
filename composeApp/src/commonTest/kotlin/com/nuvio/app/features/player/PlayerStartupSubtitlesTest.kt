package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerStartupSubtitlesTest {
    @Test
    fun localUriDetectionMatchesFileAndContentSchemes() {
        assertTrue("file:///storage/emulated/0/Movie.en.srt".isLocalSubtitleUri())
        assertTrue("content://downloads/captions.vtt".isLocalSubtitleUri())
        assertTrue("android.resource://com.nuvio.app/raw/sample".isLocalSubtitleUri())
        assertTrue("/storage/emulated/0/Movie.en.srt".isLocalSubtitleUri())
        assertFalse("https://opensubtitles.example/download/12345".isLocalSubtitleUri())
        assertFalse("http://example.com/sub.srt".isLocalSubtitleUri())
    }

    @Test
    fun remoteStreamSidecarsJoinTheAddonPoolWithoutReplacingFetchedAddons() {
        val fetched = listOf(
            AddonSubtitle(
                id = "addon-en",
                url = "https://addon.example/en.srt",
                language = "en",
                display = "English",
                addonName = "OpenSubtitles",
            ),
        )
        val merged = mergeStreamAndAddonSubtitles(
            addonSubtitles = fetched,
            streamSubtitles = listOf(
                StreamSubtitle(
                    url = "https://stream.example/en.srt",
                    language = "en",
                    name = "Stream English",
                ),
                StreamSubtitle(
                    url = "file:///storage/Movie.en.srt",
                    language = "en",
                    name = "Downloaded",
                ),
            ),
        )

        assertEquals(
            listOf("https://stream.example/en.srt", "https://addon.example/en.srt"),
            merged.map { it.url },
        )
    }

    @Test
    fun savedAddonSubtitlesAreListedWithAddonsAndTheStreamsOwnStayTracks() {
        val merged = mergeStreamAndAddonSubtitles(
            addonSubtitles = emptyList(),
            streamSubtitles = listOf(
                StreamSubtitle(url = "file:///d/0.srt", language = "en", name = "English (Addon)", fromAddon = true),
                StreamSubtitle(url = "file:///d/1.srt", language = "en", name = "English (Stream)", fromAddon = false),
            ),
        )
        assertEquals(listOf("file:///d/0.srt"), merged.map { it.url })
    }
}
