package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreferredResolutionTest {
    private fun direct(label: String, cached: Boolean = true) = StreamItem(
        name = label,
        url = "https://cdn.example/${label.hashCode()}.mkv",
        addonName = "AIOStreams",
        addonId = "addon:aio",
        debridCacheStatus = if (cached) null else StreamDebridCacheStatus("rd", "RD", StreamDebridCacheState.NOT_CACHED),
    )

    @Test
    fun readsResolutionFromLabels() {
        assertEquals(2160, direct("Show S01E01 2160p WEB").resolutionHeight())
        assertEquals(2160, direct("Show 4K HDR").resolutionHeight())
        assertEquals(1080, direct("Show.1080p.BluRay").resolutionHeight())
        assertEquals(720, direct("Show 720p").resolutionHeight())
        assertNull(direct("Show WEB-DL").resolutionHeight())
    }

    @Test
    fun picksFirstCachedStreamAtPreferredResolutionInAddonOrder() {
        val list = listOf(
            direct("A 2160p"),
            direct("B 1080p first"),
            direct("C 1080p second"),
        )
        assertEquals("B 1080p first", pickPreferredResolution(list, 1080)?.name)
    }

    @Test
    fun skipsUncachedStreamsAtPreferredResolution() {
        val list = listOf(
            direct("A 1080p", cached = false),
            direct("B 720p"),
            direct("C 1080p"),
        )
        assertEquals("C 1080p", pickPreferredResolution(list, 1080)?.name)
    }

    @Test
    fun fallsBackWhenNoCachedStreamHasThatResolution() {
        val list = listOf(direct("A 1080p", cached = false), direct("B 720p"))
        assertNull(pickPreferredResolution(list, 1080))
        assertNull(pickPreferredResolution(list, 0))
    }
}
