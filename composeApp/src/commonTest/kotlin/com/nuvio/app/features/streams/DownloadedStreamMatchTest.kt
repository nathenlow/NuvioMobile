package com.nuvio.app.features.streams

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadedStreamMatchTest {
    private fun item(subtitle: String?) = DownloadItem(
        id = "1", contentType = "series", parentMetaId = "tt1", parentMetaType = "series",
        videoId = "tt1:1:1", title = "Show", streamTitle = "FHD WEB-DL", streamSubtitle = subtitle,
        providerName = "AIOStreams", providerAddonId = "addon:a", sourceUrl = "https://x/resolved",
        fileName = "f.mp4", status = DownloadStatus.Completed, createdAtEpochMs = 0, updatedAtEpochMs = 0,
    )

    private fun stream(description: String?, name: String = "FHD WEB-DL", addonId: String = "addon:a") =
        StreamItem(name = name, description = description, url = "https://x/other", addonName = "AIOStreams", addonId = addonId)

    @Test
    fun matchesWhenDescriptionDriftsButSizeIsSame() {
        val saved = item("1.07 GB · 5.54 Mbps 🕒 625d")
        assertTrue(stream("1.07 GB · 5.56 Mbps 🕒 626d").matchesDownload(saved))
    }

    @Test
    fun rejectsDifferentSizeNameOrAddon() {
        val saved = item("1.07 GB · 5.54 Mbps")
        assertFalse(stream("640 MB · 3.42 Mbps").matchesDownload(saved))
        assertFalse(stream("1.07 GB", name = "480P").matchesDownload(saved))
        assertFalse(stream("1.07 GB", addonId = "addon:b").matchesDownload(saved))
    }
}
