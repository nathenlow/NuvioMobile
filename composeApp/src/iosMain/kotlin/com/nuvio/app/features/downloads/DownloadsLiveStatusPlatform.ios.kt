package com.nuvio.app.features.downloads

import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSUserDefaults

/**
 * Feeds the Live Activity with the download that is running: its show and episode, that it is
 * downloading, and its file size. Progress is left out on purpose: iOS lets the app change what the
 * activity shows only while the app runs, so a progress figure would freeze with the app suspended.
 * It changes when the next download in the queue starts.
 */
internal actual object DownloadsLiveStatusPlatform {
    private const val notificationName = "NuvioDownloadsLiveStatusUpdated"
    private const val userDefaultsPayloadKey = "nuvio.downloads.live_status.payload"

    private val json = Json {
        encodeDefaults = true
    }

    private var lastPayload: String? = null

    actual fun onItemsChanged(items: List<DownloadItem>) {
        publish(items, appIsAwake = true)
    }

    /**
     * Called when the system wakes the app only to report a finished download, with the downloads that
     * are running now, so the activity can move on to the next one or end.
     */
    fun onBackgroundItems(items: List<DownloadItem>) {
        publish(items, appIsAwake = false)
    }

    private fun publish(items: List<DownloadItem>, appIsAwake: Boolean) {
        // A download waiting in line is not downloading yet, so it is not shown.
        val primary = items
            .filter { item ->
                val running = item.status == DownloadStatus.Downloading &&
                    !DownloadsPlatformDownloader.isQueued(item.fileName)
                running || item.status == DownloadStatus.Paused || item.status == DownloadStatus.Failed
            }
            .sortedWith(
                compareBy<DownloadItem> { statusPriority(it.status) }
                    .thenBy { it.createdAtEpochMs },
            )
            .firstOrNull()

        val payload = primary?.let { item ->
            json.encodeToString(
                DownloadsLiveStatusPayload(
                    id = item.id,
                    title = item.title,
                    subtitle = subtitleFor(item),
                    status = item.status.name,
                    totalBytes = item.totalBytes?.takeIf { it > 0L },
                ),
            )
        }

        // A wake-up from the system posts every time: this process may be new, with nothing remembered.
        if (appIsAwake && payload == lastPayload) return
        lastPayload = payload

        val defaults = NSUserDefaults.standardUserDefaults
        if (payload == null) {
            defaults.removeObjectForKey(userDefaultsPayloadKey)
        } else {
            defaults.setObject(payload, forKey = userDefaultsPayloadKey)
        }

        NSNotificationCenter.defaultCenter.postNotificationName(notificationName, null)
    }

    private fun subtitleFor(item: DownloadItem): String {
        val code = localizedSeasonEpisodeCode(item.seasonNumber, item.episodeNumber)
        val parts = listOfNotNull(code, item.episodeTitle?.takeIf { it.isNotBlank() })
        return if (parts.isEmpty()) item.displaySubtitle else parts.joinToString(" · ")
    }

    private fun statusPriority(status: DownloadStatus): Int = when (status) {
        DownloadStatus.Downloading -> 0
        DownloadStatus.Paused -> 1
        DownloadStatus.Failed -> 2
        DownloadStatus.Completed -> 3
    }
}

@Serializable
private data class DownloadsLiveStatusPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
    val totalBytes: Long? = null,
)
