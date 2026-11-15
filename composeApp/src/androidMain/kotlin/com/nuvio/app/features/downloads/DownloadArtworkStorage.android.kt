package com.nuvio.app.features.downloads

import android.util.AtomicFile
import java.io.File
import java.net.URI

internal actual class DownloadArtworkStorage actual constructor(localVideoUri: String) {
    private val directory = File(File(URI(localVideoUri)).path + ".artwork")

    actual fun write(fileName: String, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create artwork directory" }
        val file = AtomicFile(file(fileName))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    actual fun localFileUri(fileName: String): String? =
        file(fileName).takeIf { it.isFile && it.length() > 0L }?.toURI()?.toString()

    actual fun remove() {
        directory.deleteRecursively()
    }

    private fun file(fileName: String): File {
        require(fileName.isNotBlank() && File(fileName).name == fileName && fileName != "." && fileName != "..")
        return File(directory, fileName)
    }
}
