package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo

/**
 * The details page content for a downloaded title, built only from what was saved with its
 * downloads. It lets the normal details page open with no network: its episode list is the
 * downloaded episodes, and playing one plays the file on the device.
 */
fun DownloadedTitle.toMetaDetails(savedEpisodes: List<SavedEpisode> = emptyList()): MetaDetails =
    MetaDetails(
        id = metaId,
        type = metaType,
        name = title,
        poster = poster,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        runtime = runtime,
        genres = genres,
        videos = mergeEpisodes(savedEpisodes),
    )

private fun DownloadedTitle.mergeEpisodes(savedEpisodes: List<SavedEpisode>): List<MetaVideo> {
    val downloaded = items.filter { it.isEpisode }.map { item ->
            MetaVideo(
                id = item.videoId,
                title = item.episodeTitle?.takeIf { it.isNotBlank() }
                    ?: "Episode ${item.episodeNumber}",
                thumbnail = item.localArtwork(ArtworkKind.Thumbnail) ?: item.episodeThumbnail,
                season = item.seasonNumber,
                episode = item.episodeNumber,
                overview = item.episodeOverview,
            )
        }
    // Every episode saved for the show, with the downloaded ones taking their own details.
    val downloadedKeys = downloaded.mapTo(hashSetOf()) { it.season to it.episode }
    val others = savedEpisodes
        .filter { (it.season to it.episode) !in downloadedKeys }
        .map { saved ->
            MetaVideo(
                id = saved.id,
                title = saved.title,
                released = saved.released,
                thumbnail = saved.thumbnail,
                season = saved.season,
                episode = saved.episode,
                overview = saved.overview,
                runtime = saved.runtime,
            )
        }
    return downloaded + others
}

/** The saved details of a downloaded title, if it has finished downloads. */
fun DownloadsRepository.savedTitleDetails(metaId: String): MetaDetails? {
    ensureLoaded()
    return uiState.value.items.toDownloadedTitles().firstOrNull { it.metaId == metaId.trim() }
        ?.let { it.toMetaDetails(savedEpisodes = savedEpisodes(it.metaId)) }
}
