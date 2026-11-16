package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadProfileTest {
    private fun http(label: String, gb: Double? = null, type: String? = null) = StreamItem(
        name = label,
        url = "https://cdn.example/${label.hashCode()}.mkv",
        addonName = "AIOStreams",
        addonId = "addon:aio",
        streamType = type,
        behaviorHints = StreamBehaviorHints(videoSize = gb?.let { (it * 1024 * 1024 * 1024).toLong() }),
    )

    private fun torrent(label: String, cached: Boolean) = StreamItem(
        name = label,
        infoHash = "abcdef0123456789abcdef0123456789abcdef01",
        addonName = "AIOStreams",
        addonId = "addon:aio",
        debridCacheStatus = StreamDebridCacheStatus(
            "rd", "RD", if (cached) StreamDebridCacheState.CACHED else StreamDebridCacheState.NOT_CACHED,
        ),
    )

    @Test
    fun classifiesSources() {
        assertEquals(ProfileSource.Http, http("a").profileSource())
        assertEquals(ProfileSource.Usenet, http("a", type = "usenet").profileSource())
        assertEquals(ProfileSource.Debrid, torrent("a", cached = true).profileSource())
        assertEquals(ProfileSource.Torrent, torrent("a", cached = false).profileSource())
    }

    @Test
    fun cachedProfileMatchesTheOldBehaviour() {
        val profile = cachedDebridProfile("Cached")
        val list = listOf(http("A 2160p"), http("B 1080p"), http("C 1080p"))
        assertEquals("B 1080p", profile.pickStream(list, 1080)?.name)
        // No preferred resolution: no rule matches, so the caller uses the addon ranking.
        assertNull(profile.pickStream(list, 0))
        assertNull(profile.pickStream(list, 720))
    }

    @Test
    fun httpProfileExcludesUncachedTorrentsAndPrefersDirectLinks() {
        val profile = httpProfile("HTTP")
        val list = listOf(torrent("T 1080p", cached = false), torrent("D 1080p", cached = true), http("H 1080p"))
        val kept = profile.applyExclusions(list, 1080)
        assertEquals(listOf("D 1080p", "H 1080p"), kept.map { it.name })
        assertEquals("H 1080p", profile.pickStream(kept, 1080)?.name)
        // A direct link at another resolution still beats the cached torrent, via the second rule.
        val other = profile.applyExclusions(listOf(torrent("D 1080p", true), http("H 720p")), 1080)
        assertEquals("H 720p", profile.pickStream(other, 1080)?.name)
    }

    @Test
    fun sizeRulesPickSmallestAndSkipUnknownSizes() {
        val rule = ProfileRule(
            conditions = listOf(ProfileCondition(type = ProfileConditionType.Size, maxGb = 10.0)),
            pick = ProfilePick.Smallest,
        )
        val profile = DownloadProfile("p", "P", rules = listOf(rule))
        val list = listOf(http("big", gb = 40.0), http("unknown"), http("mid", gb = 8.0), http("small", gb = 3.0))
        assertEquals("small", profile.pickStream(list, 0)?.name)
    }

    @Test
    fun resolutionAtLeastAndAtMost() {
        val atLeast = ProfileCondition(
            type = ProfileConditionType.Resolution, resolutionOp = ProfileResolutionOp.AtLeast, resolution = 1080,
        )
        assertEquals(true, atLeast.matches(http("x 2160p"), 0))
        assertEquals(true, atLeast.matches(http("x 1080p"), 0))
        assertEquals(false, atLeast.matches(http("x 720p"), 0))
        assertEquals(false, atLeast.matches(http("x"), 0))
    }

    @Test
    fun textAndNegation() {
        val remux = ProfileCondition(type = ProfileConditionType.Text, text = "remux, hdr10")
        assertEquals(true, remux.matches(http("Show 2160p REMUX"), 0))
        assertEquals(false, remux.matches(http("Show 1080p WEB"), 0))
        assertEquals(true, remux.copy(negate = true).matches(http("Show 1080p WEB"), 0))
    }

    @Test
    fun exclusionNeedsAllItsConditionsAndDisabledRulesAreSkipped() {
        val profile = DownloadProfile(
            id = "p",
            name = "P",
            rules = listOf(
                ProfileRule(conditions = listOf(ProfileCondition(type = ProfileConditionType.Text, text = "x")), enabled = false),
            ),
            exclusions = listOf(
                ProfileConditionGroup(
                    listOf(
                        ProfileCondition(type = ProfileConditionType.Text, text = "cam"),
                        ProfileCondition(type = ProfileConditionType.Resolution, resolution = 480),
                    ),
                ),
            ),
        )
        val list = listOf(http("Show CAM 480p"), http("Show CAM 1080p"), http("Show x 1080p"))
        assertEquals(listOf("Show CAM 1080p", "Show x 1080p"), profile.applyExclusions(list, 0).map { it.name })
        assertNull(profile.pickStream(list, 0))
    }

    @Test
    fun profileSurvivesJson() {
        val profile = httpProfile("HTTP")
        val text = DownloadProfilesRepository.export(profile)
        val back = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<DownloadProfile>(text)
        assertEquals(profile.rules, back.rules)
        assertEquals(profile.exclusions, back.exclusions)
    }
}
