package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.streams.StreamItem

/** What turning a chosen stream into a downloadable link came to. */
internal sealed interface StreamResolution {
    data class Playable(val stream: StreamItem) : StreamResolution

    /** The debrid service does not have the file yet. */
    data object NotCached : StreamResolution

    data object Failed : StreamResolution
}

/**
 * Resolves a debrid stream to a plain file link, as the stream screen does for a single download.
 * A plain link is returned as it is. Problems are shown as a toast, except "not cached" when
 * [toastNotCached] is false, so the caller can try another stream first.
 */
internal suspend fun resolveDownloadableStream(
    stream: StreamItem,
    season: Int?,
    episode: Int?,
    toastNotCached: Boolean = true,
): StreamResolution {
    if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) return StreamResolution.Playable(stream)
    val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
        stream = stream,
        season = season,
        episode = episode,
    )
    return when (resolved) {
        is DirectDebridPlayableResult.Success -> StreamResolution.Playable(resolved.stream)
        DirectDebridPlayableResult.NotCached -> {
            if (toastNotCached) resolved.toastMessage()?.let { NuvioToastController.show(it) }
            StreamResolution.NotCached
        }
        else -> {
            resolved.toastMessage()?.let { NuvioToastController.show(it) }
            StreamResolution.Failed
        }
    }
}
