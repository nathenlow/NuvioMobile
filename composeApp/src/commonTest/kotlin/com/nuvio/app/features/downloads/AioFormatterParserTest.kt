package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AioFormatterParserTest {

    private val nameTemplate = """{service.cached::istrue["⚡ "||""]}{service.cached::isfalse["⏳ "||""]}{stream.type::=p2p["🧲 "||""]}{stream.type::=http["🌐 "||""]}{stream.type::=live["📺 "||""]}{stream.resolution::exists["{stream.resolution::replace('2160p','UHD ⁴ᴷ')::replace('1440p','QHD ²ᴷ')::replace('1080p','FHD    ')::replace('p','P')}   "||""]}
{stream.quality::exists["⌜{stream.quality::title::replace('Bluray Remux','Remux')::smallcaps}⌟    "||""]}
{stream.nSeScore::>=10["{stream.nSeScore::pstar::replace('★','◆')} "||""]}"""

    @Test
    fun readsCachedAndSourceMarkersFromTheNameTemplate() {
        val markers = AioFormatterParser.parse(nameTemplate)

        assertEquals(
            listOf(
                StreamMarker("⚡", MarkerMeaning.Cached),
                StreamMarker("⏳", MarkerMeaning.NotCached),
                StreamMarker("🧲", MarkerMeaning.Torrent),
                StreamMarker("🌐", MarkerMeaning.Http),
            ),
            markers,
        )
    }

    @Test
    fun readsATemplateTypedWithCurlyQuotes() {
        val markers = AioFormatterParser.parse("{service.cached::isfalse[\u201CSLOW \u201D||\u201C\u201D]}")

        assertEquals(listOf(StreamMarker("SLOW", MarkerMeaning.NotCached)), markers)
    }

    @Test
    fun readsTheThreeWordForm() {
        val markers = AioFormatterParser.parse("""{service.cached["Cached"||"Uncached"||"Unknown"]}""")

        assertEquals(
            listOf(StreamMarker("Cached", MarkerMeaning.Cached), StreamMarker("Uncached", MarkerMeaning.NotCached)),
            markers,
        )
    }

    @Test
    fun findsAConditionalNestedInsideABranch() {
        val markers = AioFormatterParser.parse("""{stream.library::istrue["{service.cached::isfalse["⏳"||""]}"||""]}""")

        assertEquals(listOf(StreamMarker("⏳", MarkerMeaning.NotCached)), markers)
    }

    @Test
    fun ignoresTemplatesWithoutKnownConditionals() {
        assertTrue(AioFormatterParser.parse("{stream.filename} {stream.size::bytes}").isEmpty())
    }

    @Test
    fun theNegativeMarkerWinsWhenBothWordsMatch() {
        val meanings = setOf(MarkerMeaning.Cached, MarkerMeaning.NotCached)

        assertEquals(false, meanings.cachedByMarkers())
        assertEquals(true, setOf(MarkerMeaning.Cached).cachedByMarkers())
        assertEquals(null, emptySet<MarkerMeaning>().cachedByMarkers())
    }
}
