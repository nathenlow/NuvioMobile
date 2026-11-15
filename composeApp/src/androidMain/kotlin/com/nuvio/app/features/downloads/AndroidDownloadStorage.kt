package com.nuvio.app.features.downloads

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

internal data class AndroidStorageVolume(
    val id: String,
    val label: String,
    val directory: File,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

/**
 * Where downloads can live: the app's private storage, or the app folder on a removable SD card or
 * USB drive. The folder on the device's own shared storage is left out, as it is the same disk as the
 * private storage and only less safe.
 */
internal object AndroidDownloadStorage {
    const val INTERNAL_ID = "internal"
    private const val DOWNLOADS_FOLDER = "downloads"

    fun volumes(context: Context): List<AndroidStorageVolume> {
        val internalRoot = context.filesDir
        val result = mutableListOf(
            AndroidStorageVolume(
                id = INTERNAL_ID,
                label = "",
                directory = File(internalRoot, DOWNLOADS_FOLDER),
                removable = false,
                freeBytes = internalRoot.usableSpace,
                totalBytes = internalRoot.totalSpace,
            ),
        )
        val manager = context.getSystemService(StorageManager::class.java)
        // The first entry is the shared storage of the device itself; the others are SD cards and USB drives.
        context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { root ->
            if (Environment.getExternalStorageState(root) != Environment.MEDIA_MOUNTED) return@forEach
            val volume = runCatching { manager?.getStorageVolume(root) }.getOrNull()
            result += AndroidStorageVolume(
                id = "volume:" + (volume?.uuid ?: root.path.hashCode().toString()),
                label = volume?.getDescription(context)?.takeIf { it.isNotBlank() } ?: "External storage",
                directory = File(root, DOWNLOADS_FOLDER),
                removable = true,
                freeBytes = root.usableSpace,
                totalBytes = root.totalSpace,
            )
        }
        return result
    }

    /** The volume new downloads go to; the private storage when the chosen one is not connected. */
    fun currentVolume(context: Context): AndroidStorageVolume {
        val all = volumes(context)
        val wanted = DownloadsStorage.loadStorageVolumeId() ?: INTERNAL_ID
        return all.firstOrNull { it.id == wanted } ?: all.first()
    }

    /**
     * The folder a download's files are in. A download keeps working where it started even after the
     * chosen storage changes; new ones go to the current volume.
     */
    fun directoryFor(context: Context, fileName: String): File {
        val current = currentVolume(context)
        val ordered = listOf(current) + volumes(context).filter { it.id != current.id }
        return ordered.map { it.directory }.firstOrNull { directory ->
            File(directory, "$fileName.part").exists() || File(directory, fileName).exists()
        } ?: current.directory
    }

    fun volumeIdOf(context: Context, file: File): String? {
        val path = file.absoluteFile.path
        return volumes(context).firstOrNull { path.startsWith(it.directory.absolutePath + File.separator) }?.id
    }

    /** True for a file on removable storage that is not mounted now (an SD card taken out). */
    fun isOnDisconnectedStorage(context: Context, file: File): Boolean {
        val path = file.absoluteFile.path
        if (volumeIdOf(context, file) != null) return false
        return path.startsWith("/storage/") && !path.startsWith("/storage/emulated/")
    }
}
