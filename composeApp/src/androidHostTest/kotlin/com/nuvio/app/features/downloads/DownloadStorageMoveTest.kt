package com.nuvio.app.features.downloads

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadStorageMoveTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun source(): File = temporary.newFolder("internal").also { dir ->
        File(dir, "show.mkv").writeText("video bytes")
        File(dir, "show.mkv.subtitles").mkdirs()
        File(dir, "show.mkv.subtitles/0.srt").writeText("subtitle")
        File(dir, "show.mkv.artwork").mkdirs()
        File(dir, "show.mkv.artwork/poster.img").writeText("poster")
        File(dir, "other.mkv").writeText("not this download")
    }

    @Test
    fun movesTheVideoAndItsSideFoldersWithRename(): Unit = runBlocking {
        val from = source()
        val to = temporary.newFolder("sd")
        var bytes = 0L

        moveDownloadFiles("show.mkv", from, to, { bytes += it })

        assertEquals("video bytes", File(to, "show.mkv").readText())
        assertEquals("subtitle", File(to, "show.mkv.subtitles/0.srt").readText())
        assertEquals("poster", File(to, "show.mkv.artwork/poster.img").readText())
        assertFalse(File(from, "show.mkv").exists())
        assertFalse(File(from, "show.mkv.subtitles").exists())
        assertTrue(bytes > 0)
    }

    @Test
    fun copyPathBetweenDisksKeepsEverythingAndRemovesTheOriginals(): Unit = runBlocking {
        val from = source()
        val to = temporary.newFolder("sd")
        var bytes = 0L

        moveDownloadFiles("show.mkv", from, to, { bytes += it }, tryRename = false)

        assertEquals("video bytes", File(to, "show.mkv").readText())
        assertEquals("subtitle", File(to, "show.mkv.subtitles/0.srt").readText())
        assertFalse(File(from, "show.mkv").exists())
        assertFalse(File(from, "show.mkv.subtitles").exists())
        assertEquals("video bytes".length + "subtitle".length + "poster".length, bytes.toInt())
        // No temporary copy is left behind.
        assertTrue(to.walkTopDown().none { it.name.endsWith(".moving") })
    }

    @Test
    fun leavesOtherDownloadsAlone(): Unit = runBlocking {
        val from = source()
        val to = temporary.newFolder("sd")

        moveDownloadFiles("show.mkv", from, to, { })

        assertEquals("not this download", File(from, "other.mkv").readText())
        assertFalse(File(to, "other.mkv").exists())
    }

    @Test
    fun movesAPartialFileOfAPausedDownload(): Unit = runBlocking {
        val from = temporary.newFolder("internal")
        File(from, "paused.mkv.part").writeText("half")
        val to = temporary.newFolder("sd")

        moveDownloadFiles("paused.mkv", from, to, { }, tryRename = false)

        assertEquals("half", File(to, "paused.mkv.part").readText())
        assertFalse(File(from, "paused.mkv.part").exists())
    }
}
