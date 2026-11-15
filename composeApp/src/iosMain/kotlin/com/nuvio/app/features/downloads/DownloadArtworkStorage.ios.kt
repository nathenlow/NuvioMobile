package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal actual class DownloadArtworkStorage actual constructor(localVideoUri: String) {
    private val directory = requireNotNull(NSURL(string = localVideoUri).path) + ".artwork"

    actual fun write(fileName: String, bytes: ByteArray) {
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        check(data.writeToFile(path(fileName), true))
    }

    actual fun localFileUri(fileName: String): String? =
        path(fileName).takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { NSURL.fileURLWithPath(it).absoluteString }

    actual fun remove() {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
    }

    private fun path(fileName: String): String {
        require(fileName.isNotBlank() && '/' !in fileName && fileName != "." && fileName != "..")
        return "$directory/$fileName"
    }
}
