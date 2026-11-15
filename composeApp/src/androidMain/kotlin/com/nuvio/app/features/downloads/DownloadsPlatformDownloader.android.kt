package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.takeWhile
import java.io.File
import java.net.URI

internal actual object DownloadsPlatformDownloader {
    private var appContext: Context? = null
    private var downloadScheduler: AndroidDownloadScheduler? = null
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun initialize(context: Context) {
        scheduler(context)
    }

    @Synchronized
    internal fun scheduler(context: Context): AndroidDownloadScheduler {
        appContext = context.applicationContext
        return downloadScheduler ?: AndroidDownloadScheduler(context.applicationContext).also { downloadScheduler = it }
    }

    internal fun managedTransfers(): List<AndroidDownloadTransfer> =
        downloadScheduler?.store?.transfers?.value?.values?.toList().orEmpty()

    actual fun restoreItem(item: DownloadItem): DownloadItem = downloadScheduler?.restore(item)
        ?: if (item.status == DownloadStatus.Downloading) item.copy(status = DownloadStatus.Paused) else item

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val scheduler = checkNotNull(downloadScheduler) { "Downloads are not initialized" }
        val transfer = scheduler.enqueue(request.item)
        val observer = observerScope.launch {
            scheduler.store.transfers.map { it[request.destinationFileName] }
                .distinctUntilChanged()
                .takeWhile { current ->
                    if (current == null || current.generation != transfer.generation) return@takeWhile false
                    val item = current.item
                    when (item.status) {
                        DownloadStatus.Downloading -> onProgress(item.downloadedBytes, item.totalBytes)
                        DownloadStatus.Completed -> onSuccess(checkNotNull(item.localFileUri), item.totalBytes)
                        DownloadStatus.Failed -> onFailure(item.errorMessage ?: "Download failed")
                        DownloadStatus.Paused -> onPaused()
                    }
                    item.status == DownloadStatus.Downloading
                }.collect()
        }
        return object : DownloadsTaskHandle {
            override fun cancel() {
                observer.cancel()
                if (scheduler.store.get(request.destinationFileName)?.generation == transfer.generation) {
                    scheduler.pause(request.destinationFileName)
                }
            }
        }
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual val managesQueue: Boolean = true

    actual fun isQueued(fileName: String): Boolean = downloadScheduler?.isQueued(fileName) == true

    actual fun reprioritize(fileNames: List<String>) {
        downloadScheduler?.reprioritize(fileNames)
    }

    actual fun listDownloadEntries(): List<String> {
        val context = appContext ?: return emptyList()
        return AndroidDownloadStorage.volumes(context)
            .flatMap { it.directory.list()?.toList().orEmpty() }
            .distinct()
    }

    actual fun storageVolumes(): List<DownloadStorageVolume> {
        val context = appContext ?: return emptyList()
        return AndroidDownloadStorage.volumes(context).map {
            DownloadStorageVolume(
                id = it.id,
                label = it.label,
                removable = it.removable,
                freeBytes = it.freeBytes,
                totalBytes = it.totalBytes,
            )
        }
    }

    actual fun selectedStorageVolumeId(): String {
        val context = appContext ?: return AndroidDownloadStorage.INTERNAL_ID
        return AndroidDownloadStorage.currentVolume(context).id
    }

    actual fun selectStorageVolume(volumeId: String) {
        DownloadsStorage.saveStorageVolumeId(volumeId)
    }

    actual fun storageVolumeIdOf(item: DownloadItem): String? {
        val context = appContext ?: return null
        item.localFileUri?.toLocalFileOrNull()?.takeIf { it.exists() }?.let {
            return AndroidDownloadStorage.volumeIdOf(context, it)
        }
        return AndroidDownloadStorage.volumes(context).firstOrNull { volume ->
            File(volume.directory, item.fileName).exists() || File(volume.directory, "${item.fileName}.part").exists()
        }?.id
    }

    actual fun isOnDisconnectedStorage(localFileUri: String?): Boolean {
        val context = appContext ?: return false
        val file = localFileUri?.toLocalFileOrNull() ?: return false
        return AndroidDownloadStorage.isOnDisconnectedStorage(context, file)
    }

    actual suspend fun moveToVolume(item: DownloadItem, volumeId: String, onBytes: (Long) -> Unit): DownloadItem =
        withContext(Dispatchers.IO) {
            val context = checkNotNull(appContext) { "Downloads are not initialized" }
            val target = AndroidDownloadStorage.volumes(context).firstOrNull { it.id == volumeId }
                ?: error("That storage is not connected")
            check(target.directory.isDirectory || target.directory.mkdirs()) { "Cannot create the downloads folder" }
            val video = item.localFileUri?.toLocalFileOrNull()?.takeIf { it.exists() }
            val sourceDir = video?.parentFile ?: AndroidDownloadStorage.directoryFor(context, item.fileName)
            if (sourceDir.canonicalPath == target.directory.canonicalPath) return@withContext item
            moveDownloadFiles(item.fileName, sourceDir, target.directory, onBytes)
            val moved = File(target.directory, item.fileName)
            if (item.status == DownloadStatus.Completed && moved.exists()) {
                item.copy(localFileUri = moved.toURI().toString())
            } else {
                item
            }
        }


    actual fun removePartialFile(destinationFileName: String): Boolean {
        val scheduler = downloadScheduler ?: return false
        scheduler.remove(destinationFileName)
        return true
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val context = appContext ?: return null
        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri
                ?.toLocalFileOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
            ?: return null
        // The file may be on any storage the downloads were kept on.
        return AndroidDownloadStorage.volumes(context)
            .map { File(it.directory, fileName) }
            .firstOrNull { it.exists() }
            ?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val context = appContext ?: return false
        val downloadsDir = AndroidDownloadStorage.currentVolume(context).directory.apply { mkdirs() }
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                downloadsDir,
            )
        }.getOrNull() ?: return false

        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
            },
        )

        return intents.any { intent ->
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}

private fun String.toLocalFileOrNull(): File? {
    return runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()
}

/**
 * Moves one file or folder. On the same disk it is renamed; between disks it is copied to a temporary
 * name and renamed, and the original is removed only after the copy is complete.
 */
/**
 * Moves one download's files from [sourceDir] to [targetDir]: the video or partial file, and the side
 * folders (subtitles, pictures, record) next to it. Set [tryRename] to false to force the copy path
 * used between two disks.
 */
internal suspend fun moveDownloadFiles(
    fileName: String,
    sourceDir: File,
    targetDir: File,
    onBytes: (Long) -> Unit,
    tryRename: Boolean = true,
) {
    listOf(fileName, "$fileName.part", "$fileName.subtitles", "$fileName.artwork").forEach { name ->
        val source = File(sourceDir, name)
        if (source.exists()) moveEntry(source, File(targetDir, name), onBytes, tryRename)
    }
}

private suspend fun moveEntry(source: File, destination: File, onBytes: (Long) -> Unit, tryRename: Boolean = true) {
    if (source.isDirectory) {
        check(destination.isDirectory || destination.mkdirs()) { "Cannot create ${destination.name}" }
        source.listFiles().orEmpty().forEach { moveEntry(it, File(destination, it.name), onBytes, tryRename) }
        source.delete()
        return
    }
    val size = source.length()
    if (tryRename && source.renameTo(destination)) {
        onBytes(size)
        return
    }
    val temporary = File(destination.parentFile, destination.name + ".moving")
    try {
        source.inputStream().use { input ->
            temporary.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    onBytes(read.toLong())
                }
                output.fd.sync()
            }
        }
        if (!temporary.renameTo(destination)) throw java.io.IOException("Could not finish moving ${source.name}")
        source.delete()
    } catch (error: Throwable) {
        temporary.delete()
        throw error
    }
}
