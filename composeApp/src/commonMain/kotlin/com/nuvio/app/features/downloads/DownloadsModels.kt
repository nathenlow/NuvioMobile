package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.SubtitleAddonRequest
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.serialization.Serializable
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_enqueue_missing_url
import nuvio.composeapp.generated.resources.downloads_enqueue_replaced
import nuvio.composeapp.generated.resources.downloads_enqueue_started
import nuvio.composeapp.generated.resources.downloads_enqueue_unsupported_format
import org.jetbrains.compose.resources.getString

@Serializable
enum class DownloadStatus {
    Downloading,
    Paused,
    Completed,
    Failed,
}

@Serializable
data class DownloadItem(
    val id: String,
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamTitle: String,
    val streamSubtitle: String? = null,
    val providerName: String,
    val providerAddonId: String? = null,
    val sourceUrl: String,
    val sourceHeaders: Map<String, String> = emptyMap(),
    val sourceResponseHeaders: Map<String, String> = emptyMap(),
    val subtitleRequests: List<SubtitleAddonRequest> = emptyList(),
    val sourceSubtitles: List<StreamSubtitle> = emptyList(),
    val localFileUri: String? = null,
    val fileName: String,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /**
     * Details of the title saved with the download so it can be browsed offline. They live and go
     * with the download itself, so deleting the last download of a title deletes them too.
     */
    val description: String? = null,
    val releaseInfo: String? = null,
    val genres: List<String> = emptyList(),
    val imdbRating: String? = null,
    val runtime: String? = null,
    val episodeOverview: String? = null,
) {
    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    val isPlayable: Boolean
        get() = status == DownloadStatus.Completed && !localFileUri.isNullOrBlank()

    val displaySubtitle: String
        get() = episodeTitle.orEmpty()

    val progressFraction: Float
        get() {
            val total = totalBytes?.takeIf { it > 0L } ?: return 0f
            return (downloadedBytes.toDouble() / total.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

    val logicalContentKey: String
        get() = if (isEpisode) {
            "${parentMetaId.trim()}|${seasonNumber ?: -1}|${episodeNumber ?: -1}"
        } else {
            "${parentMetaId.trim()}|movie"
        }
}

data class DownloadsUiState(
    val items: List<DownloadItem> = emptyList(),
    /** Downloads that are waiting for a free slot under the concurrent download limit. */
    val queuedIds: Set<String> = emptySet(),
    /** Bumped when pictures were saved to the device, so screens showing them read them again. */
    val artworkRevision: Int = 0,
) {
    val activeItems: List<DownloadItem>
        get() = items.filter { it.status != DownloadStatus.Completed }

    val completedItems: List<DownloadItem>
        get() = items.filter { it.status == DownloadStatus.Completed }
}

enum class DownloadEnqueueResult {
    Started,
    Replaced,
    MissingUrl,
    UnsupportedFormat;

    fun toastMessage(): String = runBlocking {
        when (this@DownloadEnqueueResult) {
            Started -> getString(Res.string.downloads_enqueue_started)
            Replaced -> getString(Res.string.downloads_enqueue_replaced)
            MissingUrl -> getString(Res.string.downloads_enqueue_missing_url)
            UnsupportedFormat -> getString(Res.string.downloads_enqueue_unsupported_format)
        }
    }
}

/** One episode's details, saved for a downloaded show so its page can list every episode offline. */
@Serializable
data class SavedEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
)

/** A place downloads can be stored: the app's private storage, or a removable SD card or USB drive. */
data class DownloadStorageVolume(
    val id: String,
    /** The name to show for removable volumes. Known ids ("internal") are named by the screen. */
    val label: String,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

/** A downloaded movie or show, as listed in the download library. */
data class DownloadedTitle(
    val metaId: String,
    val metaType: String,
    val title: String,
    val isSeries: Boolean,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val genres: List<String>,
    val imdbRating: String?,
    val runtime: String?,
    /** Finished downloads of this title; episodes are in season and episode order. */
    val items: List<DownloadItem>,
)

/** Groups finished downloads by title, A to Z. */
fun List<DownloadItem>.toDownloadedTitles(): List<DownloadedTitle> =
    filter { it.status == DownloadStatus.Completed }
        .groupBy { it.parentMetaId }
        .map { (metaId, group) ->
            val newestFirst = group.sortedByDescending { it.updatedAtEpochMs }
            fun <T : Any> newest(selector: (DownloadItem) -> T?): T? = newestFirst.firstNotNullOfOrNull(selector)
            val first = newestFirst.first()
            DownloadedTitle(
                metaId = metaId,
                metaType = first.parentMetaType,
                title = first.title,
                isSeries = group.any { it.isEpisode },
                poster = newest { it.localArtwork(ArtworkKind.Poster) ?: it.poster?.takeIf(String::isNotBlank) },
                background = newest { it.localArtwork(ArtworkKind.Background) ?: it.background?.takeIf(String::isNotBlank) },
                logo = newest { it.localArtwork(ArtworkKind.Logo) ?: it.logo?.takeIf(String::isNotBlank) },
                description = newest { it.description?.takeIf(String::isNotBlank) },
                releaseInfo = newest { it.releaseInfo?.takeIf(String::isNotBlank) },
                genres = newestFirst.firstOrNull { it.genres.isNotEmpty() }?.genres.orEmpty(),
                imdbRating = newest { it.imdbRating?.takeIf(String::isNotBlank) },
                runtime = newest { it.runtime?.takeIf(String::isNotBlank) },
                items = group.sortedForSeriesDownloads(),
            )
        }
        .sortedBy { it.title.lowercase() }

internal fun List<DownloadItem>.sortedForSeriesDownloads(): List<DownloadItem> =
    sortedWith(downloadSeriesEpisodeComparator)

internal val downloadSeriesEpisodeComparator: Comparator<DownloadItem> =
    compareBy<DownloadItem> { it.seasonNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeTitle?.trim().orEmpty().lowercase() }
        .thenBy { it.title.trim().lowercase() }
        .thenBy { it.id }
