package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBadgeMatcher
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import kotlinx.serialization.Serializable

/**
 * A download profile decides which stream a bulk download picks for each episode.
 *
 * It works like a small script: streams matching any exclusion are dropped first, then the rules are
 * tried from top to bottom, and the first rule with a matching stream picks it. When no rule matches,
 * the caller falls back to the addon ranking (among the streams that were not excluded).
 */
@Serializable
data class DownloadProfile(
    val id: String,
    val name: String,
    /** Built-in profiles cannot be edited: duplicate one to change it. */
    val builtIn: Boolean = false,
    val rules: List<ProfileRule> = emptyList(),
    val exclusions: List<ProfileConditionGroup> = emptyList(),
)

/** Conditions that must all match. */
@Serializable
data class ProfileConditionGroup(
    val conditions: List<ProfileCondition> = emptyList(),
)

@Serializable
data class ProfileRule(
    val conditions: List<ProfileCondition> = emptyList(),
    val pick: ProfilePick = ProfilePick.First,
    val enabled: Boolean = true,
)

@Serializable
enum class ProfilePick {
    /** The first match, in the addon's own order (its ranking). */
    First,
    Smallest,
    Largest,
}

@Serializable
enum class ProfileConditionType {
    Cached, Source, Resolution, Size, Text,
    Addon, Quality, Codec, Hdr, BitDepth, Audio, Language, Edition,
}

@Serializable
enum class ProfileSource { Debrid, Usenet, Http, Torrent }

@Serializable
enum class ProfileResolutionOp { Equals, AtLeast, AtMost }

/**
 * One test on a stream. Only the fields of its [type] are read. [negate] turns it into "is not".
 * A test about something the stream does not say (an unknown size or resolution) does not match,
 * and [negate] then flips that.
 */
@Serializable
data class ProfileCondition(
    val type: ProfileConditionType,
    val negate: Boolean = false,
    /** Cached: true means "is cached". */
    val cached: Boolean = true,
    val sources: List<ProfileSource> = emptyList(),
    val resolutionOp: ProfileResolutionOp = ProfileResolutionOp.Equals,
    /** The height to compare with; 0 means the "Preferred resolution" download setting. */
    val resolution: Int = 0,
    val minGb: Double? = null,
    val maxGb: Double? = null,
    /** Comma separated words: the stream matches when its text contains any of them. */
    val text: String = "",
    /** Resolution "is one of": heights, 0 meaning the preferred resolution. Empty means [resolution]. */
    val resolutions: List<Int> = emptyList(),
    /** The chosen options of an Addon (addon names) or tag condition (ids from [ProfileTags]). */
    val values: List<String> = emptyList(),
)

// ---- Reading a stream ------------------------------------------------------------------------

/** What kind of link the stream is. Usenet is read from the type the addon reports. */
internal fun StreamItem.profileSource(): ProfileSource {
    markerMeanings(DownloadProfilesRepository.markers()).sourceByMarkers()?.let { return it }
    val reported = streamType ?: clientResolve?.type
    if (reported.equals("usenet", ignoreCase = true)) return ProfileSource.Usenet
    if (
        isDirectDebridStream ||
        isCachedDebridTorrentStream ||
        debridResolved ||
        debridCacheStatus?.state == StreamDebridCacheState.CACHED ||
        reported.equals("debrid", ignoreCase = true)
    ) {
        return ProfileSource.Debrid
    }
    if (isTorrentStream) return ProfileSource.Torrent
    return ProfileSource.Http
}

/** The file size in bytes as the addon reports it, or null. */
internal fun StreamItem.fileSizeBytes(): Long? =
    (
        behaviorHints.videoSize
            ?: clientResolve?.stream?.raw?.size
            ?: debridCacheStatus?.cachedSize
        )?.takeIf { it > 0 }

// The pieces are joined with " | " so a tag never runs across two of them.
private fun StreamItem.searchableText(): String =
    StreamBadgeMatcher.badgeMatchCandidates(this).joinToString(" | ").lowercase()

private fun wordPattern(word: String) = Regex("(?<![a-z0-9])${Regex.escape(word)}(?![a-z0-9])")

private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

// ---- Matching ---------------------------------------------------------------------------------

internal fun ProfileCondition.matches(stream: StreamItem, preferredHeight: Int): Boolean {
    val result = when (type) {
        ProfileConditionType.Cached -> stream.isCachedForDownload() == cached
        ProfileConditionType.Source -> sources.isNotEmpty() && stream.profileSource() in sources
        ProfileConditionType.Resolution -> {
            val height = stream.resolutionHeight()
            fun resolve(value: Int) = if (value > 0) value else preferredHeight
            if (height == null) {
                false
            } else if (resolutionOp == ProfileResolutionOp.Equals) {
                resolutions.ifEmpty { listOf(resolution) }.map(::resolve).filter { it > 0 }.any { it == height }
            } else {
                val target = resolve(resolution)
                target > 0 && when (resolutionOp) {
                    ProfileResolutionOp.AtLeast -> height >= target
                    else -> height <= target
                }
            }
        }
        ProfileConditionType.Addon -> values.any { it.equals(stream.addonName, ignoreCase = true) }
        ProfileConditionType.Language -> {
            val words = text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val reported = stream.clientResolve?.stream?.raw?.parsed?.languages.orEmpty().map { it.lowercase() }
            val haystack = stream.searchableText()
            words.isNotEmpty() && words.any { word ->
                reported.any { it == word || it.startsWith(word) } || wordPattern(word).containsMatchIn(haystack)
            }
        }
        ProfileConditionType.Quality, ProfileConditionType.Codec, ProfileConditionType.Hdr,
        ProfileConditionType.BitDepth, ProfileConditionType.Audio, ProfileConditionType.Edition ->
            ProfileTags.matchesAny(type, values, stream.searchableText())
        ProfileConditionType.Size -> {
            val gb = stream.fileSizeBytes()?.div(BYTES_PER_GB)
            gb != null && (minGb == null || gb >= minGb) && (maxGb == null || gb <= maxGb)
        }
        ProfileConditionType.Text -> {
            val words = text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            words.isNotEmpty() && stream.searchableText().let { haystack -> words.any { it in haystack } }
        }
    }
    return result != negate
}

private fun List<ProfileCondition>.allMatch(stream: StreamItem, preferredHeight: Int): Boolean =
    isNotEmpty() && all { it.matches(stream, preferredHeight) }

/** The streams that no exclusion of the [profile] removes. */
internal fun DownloadProfile.applyExclusions(candidates: List<StreamItem>, preferredHeight: Int): List<StreamItem> {
    if (exclusions.isEmpty()) return candidates
    return candidates.filterNot { stream ->
        exclusions.any { it.conditions.allMatch(stream, preferredHeight) }
    }
}

/**
 * The stream the first matching rule picks from [candidates] (already without the excluded ones),
 * or null when no rule matches, so the caller falls back to the addon ranking.
 */
internal fun DownloadProfile.pickStream(candidates: List<StreamItem>, preferredHeight: Int): StreamItem? {
    for (rule in rules) {
        if (!rule.enabled) continue
        val matches = candidates.filter { rule.conditions.allMatch(it, preferredHeight) }
        if (matches.isEmpty()) continue
        return when (rule.pick) {
            ProfilePick.First -> matches.first()
            // Streams without a known size come last, so a known size always wins.
            ProfilePick.Smallest -> matches.minByOrNull { it.fileSizeBytes() ?: Long.MAX_VALUE }
            ProfilePick.Largest -> matches.maxByOrNull { it.fileSizeBytes() ?: -1L }
        }
    }
    return null
}

// ---- Built-in profiles ------------------------------------------------------------------------

internal const val PROFILE_ID_CACHED = "builtin-cached"
internal const val PROFILE_ID_HTTP = "builtin-http"
internal const val PROFILE_ID_DIY = "diy"

private fun cachedAtPreferredResolution() = listOf(
    ProfileCondition(type = ProfileConditionType.Cached),
    ProfileCondition(type = ProfileConditionType.Resolution),
)

/** Cached first, then the preferred resolution. This is how bulk download always chose. */
internal fun cachedDebridProfile(name: String) = DownloadProfile(
    id = PROFILE_ID_CACHED,
    name = name,
    builtIn = true,
    rules = listOf(ProfileRule(conditions = cachedAtPreferredResolution())),
)

/** For people whose addons return plain links: those at the preferred resolution, then any of them. */
internal fun httpProfile(name: String): DownloadProfile {
    val direct = ProfileCondition(
        type = ProfileConditionType.Source,
        sources = listOf(ProfileSource.Http, ProfileSource.Usenet),
    )
    return DownloadProfile(
        id = PROFILE_ID_HTTP,
        name = name,
        builtIn = true,
        rules = listOf(
            ProfileRule(conditions = listOf(direct, ProfileCondition(type = ProfileConditionType.Resolution))),
            ProfileRule(conditions = listOf(direct)),
        ),
        // A torrent the debrid service does not have cannot be downloaded.
        exclusions = listOf(
            ProfileConditionGroup(
                listOf(ProfileCondition(type = ProfileConditionType.Source, sources = listOf(ProfileSource.Torrent))),
            ),
        ),
    )
}

/** The editable profile, which starts as a copy of the first one. */
internal fun diyProfile(name: String) = cachedDebridProfile(name).copy(id = PROFILE_ID_DIY, builtIn = false)
