package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem

/** The resolutions the download setting offers. 0 means no preference. */
internal val PREFERRED_RESOLUTION_CHOICES: List<Int> = listOf(0, 2160, 1080, 720, 480)

private val resolutionPattern = Regex(
    """(?<![0-9a-z])(2160p|4k|uhd|1440p|1080p|fhd|720p|576p|480p)(?![0-9a-z])""",
    RegexOption.IGNORE_CASE,
)

private fun String.toResolutionHeight(): Int? =
    resolutionPattern.find(this)?.value?.lowercase()?.let { token ->
        when (token) {
            "2160p", "4k", "uhd" -> 2160
            "1440p" -> 1440
            "1080p", "fhd" -> 1080
            "720p" -> 720
            "576p" -> 576
            else -> 480
        }
    }

/** The stream's vertical resolution, read from what the addon reports about it, or null when unknown. */
internal fun StreamItem.resolutionHeight(): Int? {
    val raw = clientResolve?.stream?.raw
    return sequenceOf(
        raw?.parsed?.resolution,
        raw?.filename,
        clientResolve?.filename,
        behaviorHints.filename,
        debridCacheStatus?.cachedName,
        raw?.torrentName,
        name,
        title,
        description,
    ).mapNotNull { it?.toResolutionHeight() }.firstOrNull()
}

/**
 * True when the file can be fetched right away: a cached debrid file, or a plain link the addon has
 * already resolved. A torrent that the debrid service does not have yet is not.
 */
internal fun StreamItem.isCachedForDownload(): Boolean {
    // What the addon's own formatting says (for example "⏳") wins over the guesses below.
    markerMeanings(DownloadProfilesRepository.markers()).cachedByMarkers()?.let { return it }
    if (debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) return false
    if (isCachedDebridTorrentStream || debridCacheStatus?.state == StreamDebridCacheState.CACHED) return true
    if (clientResolve?.isCached == true) return true
    return !isTorrentStream && !isDirectDebridStream && playableDirectUrl != null
}

/**
 * The first cached stream at [preferredHeight], keeping the addon's own order (its ranking) among
 * them. Null when no cached stream has that resolution, so the caller falls back to the addon ranking.
 */
internal fun pickPreferredResolution(candidates: List<StreamItem>, preferredHeight: Int): StreamItem? {
    if (preferredHeight <= 0) return null
    return candidates.firstOrNull { it.isCachedForDownload() && it.resolutionHeight() == preferredHeight }
}
