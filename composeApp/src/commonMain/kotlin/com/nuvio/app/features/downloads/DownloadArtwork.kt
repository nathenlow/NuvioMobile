package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ArtworkKind(val fileName: String) {
    Poster("poster.img"),
    Background("background.img"),
    Logo("logo.img"),
    Thumbnail("thumbnail.img"),
}

/**
 * Keeps a title's artwork on the device, next to each downloaded file, so the offline library has
 * pictures with no network. The files sit in a folder named after the video file, so they are
 * removed together with it, and with the last download of a title nothing of it is left.
 */
internal object DownloadArtwork {
    private const val FETCH_TIMEOUT_MS = 15_000L
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    /** Saves whichever of the item's pictures are not on the device yet. Failures are ignored. */
    suspend fun prepare(item: DownloadItem, localVideoUri: String): Unit = withContext(Dispatchers.Default) {
        if (!localVideoUri.startsWith("file:")) return@withContext
        val storage = DownloadArtworkStorage(localVideoUri)
        val sources = listOf(
            ArtworkKind.Poster to item.poster,
            ArtworkKind.Background to item.background,
            ArtworkKind.Logo to item.logo,
            ArtworkKind.Thumbnail to item.episodeThumbnail,
        )
        for ((kind, url) in sources) {
            if (url.isNullOrBlank() || !(url.startsWith("https://", true) || url.startsWith("http://", true))) continue
            if (storage.localFileUri(kind.fileName) != null) continue
            val bytes = try {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    httpRequestRaw(
                        method = "GET",
                        url = url,
                        headers = emptyMap(),
                        body = "",
                        maxResponseBodyBytes = MAX_IMAGE_BYTES,
                    )
                }?.takeIf { it.status in 200..299 && it.bodyBytes.isNotEmpty() }?.bodyBytes
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            } ?: continue
            try {
                storage.write(kind.fileName, bytes)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }

    fun localUri(localVideoUri: String?, kind: ArtworkKind): String? {
        if (localVideoUri == null || !localVideoUri.startsWith("file:")) return null
        return DownloadArtworkStorage(localVideoUri).localFileUri(kind.fileName)
    }
}

/** The saved picture for [kind] if it is on the device. */
internal fun DownloadItem.localArtwork(kind: ArtworkKind): String? =
    DownloadArtwork.localUri(
        DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, fileName),
        kind,
    )

internal expect class DownloadArtworkStorage(localVideoUri: String) {
    fun write(fileName: String, bytes: ByteArray)
    fun localFileUri(fileName: String): String?
    fun remove()
}
