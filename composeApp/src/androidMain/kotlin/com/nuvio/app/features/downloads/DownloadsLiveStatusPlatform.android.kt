package com.nuvio.app.features.downloads

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nuvio.app.core.deeplink.buildDownloadsDeepLinkUrl
import com.nuvio.app.features.settings.AppIconPlatform
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

/**
 * One notification for the whole download queue, the way Aniyomi and Animetail do it: it shows the
 * download that is running with its percent, and "Pause" pauses them all. Paused downloads become one
 * "Downloads paused" notification with Resume and Cancel all. A failure is a separate notification on
 * its own channel, and finished downloads leave nothing behind.
 */
internal actual object DownloadsLiveStatusPlatform {
    private const val progressChannelId = "downloads_live_status"
    private const val errorChannelId = "downloads_errors"
    private const val queueNotificationId = 4_410_001
    private const val errorNotificationId = 4_410_002
    private const val maxTitleLength = 30

    private var appContext: Context? = null
    private var lastQueueState: String? = null
    private val reportedFailures = mutableSetOf<String>()
    // The status each download had at the last update, to tell a download that just failed from one that was already failed.
    private val knownStatuses = mutableMapOf<String, DownloadStatus>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureNotificationChannels()
    }

    actual fun onItemsChanged(items: List<DownloadItem>) {
        render(items, live = null)
    }

    /** A transfer reported progress or changed state; [item] is the freshest copy of it. */
    internal fun notifyTransfer(item: DownloadItem) {
        render(currentItems(item), live = item)
    }

    /** A transfer was removed from the scheduler. */
    internal fun removeNotification(downloadId: String) {
        render(currentItems(null).filterNot { it.id == downloadId }, live = null)
    }

    /** Every download shares the queue notification, so every transfer's foreground notice is the same one. */
    @Suppress("UNUSED_PARAMETER")
    internal fun notificationId(downloadId: String): Int = queueNotificationId

    /** The notification a transfer starts its foreground job with. */
    internal fun buildNotification(context: Context, item: DownloadItem): android.app.Notification {
        val items = currentItems(item)
        val downloading = items.filter { it.status == DownloadStatus.Downloading }
        val shown = downloading.ifEmpty { listOf(item) }
        return buildDownloading(context, shown, current = firstDownloading(shown))
    }

    // The app's list when it is loaded, otherwise the scheduler's own (the app may be closed).
    private fun currentItems(overlay: DownloadItem?): List<DownloadItem> {
        val repository = DownloadsRepository.uiState.value.items
        val base = repository.ifEmpty { DownloadsPlatformDownloader.managedTransfers().map { it.item } }
        if (overlay == null) return base
        return if (base.any { it.id == overlay.id }) base.map { if (it.id == overlay.id) overlay else it } else base + overlay
    }

    private fun render(items: List<DownloadItem>, live: DownloadItem?) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return
        val manager = NotificationManagerCompat.from(context)

        reportFailures(context, manager, items, live)

        val downloading = items.filter { it.status == DownloadStatus.Downloading }
        val paused = items.filter { it.status == DownloadStatus.Paused }
        when {
            downloading.isNotEmpty() -> {
                val current = firstDownloading(downloading)
                val key = "d|${current.id}|${progressPercent(current)}|${downloading.size}"
                if (key != lastQueueState) {
                    lastQueueState = key
                    manager.notify(queueNotificationId, buildDownloading(context, downloading, current))
                }
            }
            paused.isNotEmpty() -> {
                val key = "p|${paused.size}"
                if (key != lastQueueState) {
                    lastQueueState = key
                    manager.notify(queueNotificationId, buildPaused(context, paused.size))
                }
            }
            else -> {
                lastQueueState = null
                manager.cancel(queueNotificationId)
            }
        }
    }

    // Always the first of the queue, so the notification does not switch between concurrent downloads.
    private fun firstDownloading(downloading: List<DownloadItem>): DownloadItem =
        downloading.minWith(compareBy<DownloadItem>({ it.createdAtEpochMs }, { it.id }))

    private fun buildDownloading(context: Context, downloading: List<DownloadItem>, current: DownloadItem): android.app.Notification {
        val percent = progressPercent(current)
        val progressText = if (percent <= 0) {
            runBlocking { getString(Res.string.downloads_notification_downloading) }
        } else {
            runBlocking { getString(Res.string.downloads_notification_progress, percent) }
        }
        val more = downloading.size - 1
        val text = if (more > 0) runBlocking { getString(Res.string.downloads_notification_more, progressText, more) } else progressText
        return NotificationCompat.Builder(context, progressChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(chop(titleOf(current)))
            .setContentText(text)
            .setProgress(100, percent.coerceAtLeast(0), percent <= 0)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openDownloadsIntent(context))
            .addAction(
                android.R.drawable.ic_media_pause,
                runBlocking { getString(Res.string.compose_action_pause) },
                actionIntent(context, DownloadsNotificationActionReceiver.actionPauseAll),
            )
            .build()
    }

    private fun buildPaused(context: Context, count: Int): android.app.Notification =
        NotificationCompat.Builder(context, progressChannelId)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(runBlocking { getString(Res.string.downloads_notification_paused_title) })
            .setContentText(runBlocking { getString(Res.string.downloads_notification_paused_text, count) })
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openDownloadsIntent(context))
            .addAction(
                android.R.drawable.ic_media_play,
                runBlocking { getString(Res.string.action_resume) },
                actionIntent(context, DownloadsNotificationActionReceiver.actionResumeAll),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                runBlocking { getString(Res.string.downloads_notification_cancel_all) },
                actionIntent(context, DownloadsNotificationActionReceiver.actionCancelAll),
            )
            .build()

    /** A failure the user should see, kept apart from the progress so it is not overwritten by it. */
    private fun reportFailures(context: Context, manager: NotificationManagerCompat, items: List<DownloadItem>, live: DownloadItem?) {
        val failed = items.filter { it.status == DownloadStatus.Failed }
        reportedFailures.retainAll(failed.map { it.id }.toSet())
        // Only a failure that just happened, not the ones already in the list when the app opens. One that
        // the app rejects after the transfer finished (a file too small to be a video) counts too: it
        // was known as downloading or finished a moment before.
        val justFailed = failed.filter { item ->
            val before = knownStatuses[item.id]
            before != null && before != DownloadStatus.Failed
        }
        val fresh = (listOfNotNull(live?.takeIf { it.status == DownloadStatus.Failed }) + justFailed)
            .firstOrNull { it.id !in reportedFailures }
        knownStatuses.clear()
        items.forEach { knownStatuses[it.id] = it.status }
        if (fresh == null) return
        reportedFailures += fresh.id
        val many = failed.size > 1
        manager.notify(
            errorNotificationId,
            NotificationCompat.Builder(context, errorChannelId)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(
                    if (many) runBlocking { getString(Res.string.downloads_notification_failed_title) } else chop(titleOf(fresh)),
                )
                .setContentText(
                    if (many) {
                        runBlocking { getString(Res.string.downloads_notification_failed_many, failed.size) }
                    } else {
                        fresh.errorMessage?.takeIf { it.isNotBlank() }
                            ?: runBlocking { getString(Res.string.downloads_live_failed) }
                    },
                )
                .setStyle(NotificationCompat.BigTextStyle().bigText(fresh.errorMessage.orEmpty()))
                .setAutoCancel(true)
                .setContentIntent(openDownloadsIntent(context))
                .build(),
        )
    }

    private fun titleOf(item: DownloadItem): String =
        item.displaySubtitle.takeIf { it.isNotBlank() }?.let { "${item.title} - $it" } ?: item.title

    private fun chop(text: String): String =
        if (text.length <= maxTitleLength) text else text.take(maxTitleLength - 1).trimEnd() + "…"

    private fun progressPercent(item: DownloadItem): Int {
        val total = item.totalBytes?.takeIf { it > 0L } ?: return -1
        return ((item.downloadedBytes.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun openDownloadsIntent(context: Context): PendingIntent {
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(context)
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(buildDownloadsDeepLinkUrl())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            queueNotificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode() and Int.MAX_VALUE,
            Intent(context, DownloadsNotificationActionReceiver::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureNotificationChannels() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(progressChannelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    progressChannelId,
                    runBlocking { getString(Res.string.downloads_channel_name) },
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = runBlocking { getString(Res.string.downloads_channel_description) } },
            )
        }
        if (manager.getNotificationChannel(errorChannelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    errorChannelId,
                    runBlocking { getString(Res.string.downloads_error_channel_name) },
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionState = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (permissionState != PackageManager.PERMISSION_GRANTED) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
