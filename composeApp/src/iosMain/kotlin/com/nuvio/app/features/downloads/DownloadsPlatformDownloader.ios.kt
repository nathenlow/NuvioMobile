package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.UIKit.UIApplication

/** Called by the app delegate when the system wakes the app for finished background downloads. */
fun handleDownloadsBackgroundEvents(
    identifier: String,
    completionHandler: () -> Unit,
) {
    IosBackgroundDownloads.handleBackgroundEvents(identifier, completionHandler)
}

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        // The transfer runs in a background session: it carries on when the app is left or closed.
        IosBackgroundDownloads.start(
            request = request,
            handlers = IosDownloadCallbacks(onProgress = onProgress, onSuccess = onSuccess, onFailure = onFailure),
        )
        return IosDownloadsTaskHandle(request.destinationFileName)
    }

    actual fun restoreItem(item: DownloadItem): DownloadItem = IosBackgroundDownloads.restore(item)

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val path = localFileUri.toLocalPath() ?: return false
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
            return removePathIfExists(path)
        }

        val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        return removePathIfExists("${downloadsDirectoryPath()}/$fileName")
    }

    actual val managesQueue: Boolean = true

    actual fun storageVolumes(): List<DownloadStorageVolume> =
        listOf(DownloadStorageVolume(id = "internal", label = "", removable = false, freeBytes = 0L, totalBytes = 0L))

    actual fun selectedStorageVolumeId(): String = "internal"

    actual fun selectStorageVolume(volumeId: String) = Unit

    actual fun storageVolumeIdOf(item: DownloadItem): String? = "internal"

    actual fun isOnDisconnectedStorage(localFileUri: String?): Boolean = false

    actual suspend fun moveToVolume(item: DownloadItem, volumeId: String, onBytes: (Long) -> Unit): DownloadItem = item

    actual fun isQueued(fileName: String): Boolean = IosBackgroundDownloads.isQueued(fileName)

    actual fun reprioritize(fileNames: List<String>) = IosBackgroundDownloads.reprioritize(fileNames)

    @OptIn(ExperimentalForeignApi::class)
    actual fun listDownloadEntries(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(downloadsDirectoryPath(), null)
            ?.filterIsInstance<String>()
            .orEmpty()

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val destinationPath = "${downloadsDirectoryPath()}/$destinationFileName"
        DownloadSubtitleStorage(NSURL.fileURLWithPath(destinationPath).absoluteString!!).remove()
        DownloadArtworkStorage(NSURL.fileURLWithPath(destinationPath).absoluteString!!).remove()
        IosBackgroundDownloads.cancelAndForget(destinationFileName)
        return removePathIfExists("$destinationPath.part")
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri?.toLocalPath()
            ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { path ->
                return NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
            }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalPath()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: return null
        val currentPath = "${downloadsDirectoryPath()}/$fileName"
        return if (NSFileManager.defaultManager.fileExistsAtPath(currentPath)) {
            NSURL.fileURLWithPath(currentPath).absoluteString ?: "file://$currentPath"
        } else {
            null
        }
    }

    actual fun openDownloadsDirectory(): Boolean {
        val url = NSURL.fileURLWithPath(downloadsDirectoryPath())
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
        return true
    }
}

private class IosDownloadsTaskHandle(
    private val fileName: String,
) : DownloadsTaskHandle {
    // Stopping keeps what was received, so the download can carry on later; deleting it clears that.
    override fun cancel() = IosBackgroundDownloads.pause(fileName)
}

@OptIn(ExperimentalForeignApi::class)
internal fun downloadsDirectoryPath(): String {
    val root = NSHomeDirectory().trimEnd('/')
    val path = "$root/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    excludeFromBackupOnce(path)
    return path
}

private var downloadsExcludedFromBackup = false

/**
 * Downloads can be many GB and can be downloaded again, so they must not be part of iCloud or
 * computer backups. The flag is set on the folder and covers everything inside it, files that
 * were downloaded before this change included.
 */
@OptIn(ExperimentalForeignApi::class)
private fun excludeFromBackupOnce(path: String) {
    if (downloadsExcludedFromBackup) return
    val excluded = NSURL.fileURLWithPath(path)
        .setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    if (excluded) downloadsExcludedFromBackup = true
}

@OptIn(ExperimentalForeignApi::class)
internal fun removePathIfExists(path: String): Boolean {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return true
    return NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
internal fun fileSizeOrNull(path: String): Long? {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    val value = attrs?.get("NSFileSize")
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}

private fun String.toLocalPath(): String? {
    val value = trim()
    if (value.startsWith("file:")) {
        return NSURL(string = value).path ?: value.removePrefix("file://")
    }
    return value.takeIf { it.isNotBlank() }
}
