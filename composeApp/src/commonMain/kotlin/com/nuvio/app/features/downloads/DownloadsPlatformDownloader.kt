package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val item: DownloadItem,
) {
    val sourceUrl: String get() = item.sourceUrl
    val sourceHeaders: Map<String, String> get() = item.sourceHeaders
    val destinationFileName: String get() = item.fileName
}

internal interface DownloadsTaskHandle {
    fun cancel()
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle

    fun restoreItem(item: DownloadItem): DownloadItem

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun openDownloadsDirectory(): Boolean

    /**
     * True when the platform keeps the waiting line and the concurrent limit itself, so queued
     * downloads still start while the app is closed. Then every download is handed over at once.
     */
    val managesQueue: Boolean

    /** Whether the platform is holding this download back for a free slot. */
    fun isQueued(fileName: String): Boolean

    /** Tells the platform the order downloads should start in, and to start any that now fit. */
    fun reprioritize(fileNames: List<String>)

    /** Places downloads can be stored. A single entry means there is nothing to choose. */
    fun storageVolumes(): List<DownloadStorageVolume>

    /** The volume new downloads go to. Falls back to the app's own storage when the chosen one is not connected. */
    fun selectedStorageVolumeId(): String

    fun selectStorageVolume(volumeId: String)

    /** The volume holding this download's files, or null when none are found. */
    fun storageVolumeIdOf(item: DownloadItem): String?

    /** True when the file is on removable storage that is not connected right now. */
    fun isOnDisconnectedStorage(localFileUri: String?): Boolean

    /**
     * Moves a download's files (video or partial file, subtitles and pictures) to [volumeId] and returns the
     * item with its new file location. [onBytes] reports bytes handled. Throws if it cannot finish, leaving
     * the files where they were.
     */
    suspend fun moveToVolume(item: DownloadItem, volumeId: String, onBytes: (Long) -> Unit): DownloadItem

    /** Names of everything in the downloads folder: videos, partial files and their side folders. */
    fun listDownloadEntries(): List<String>
}
