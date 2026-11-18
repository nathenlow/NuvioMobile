package com.nuvio.app.features.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The buttons of the download notification. They act on the whole queue. */
class DownloadsNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val scheduler = DownloadsPlatformDownloader.scheduler(appContext)
        DownloadsStorage.initialize(appContext)
        DownloadsPlatformDownloader.initialize(appContext)
        DownloadsLiveStatusPlatform.initialize(appContext)
        DownloadsRepository.ensureLoaded()

        when (action) {
            actionPauseAll -> {
                scheduler.store.transfers.value.values
                    .filter { it.item.status == DownloadStatus.Downloading }
                    .forEach { scheduler.pause(it.item.fileName) }
                DownloadsRepository.pauseActiveDownloads()
            }
            actionResumeAll -> {
                val managed = scheduler.store.transfers.value.values
                    .filter { it.item.status == DownloadStatus.Paused || it.item.status == DownloadStatus.Failed }
                managed.forEach {
                    scheduler.enqueue(it.item)
                    DownloadsRepository.reattachBackgroundDownload(it.item.id)
                }
                val managedIds = managed.map { it.item.id }.toSet()
                DownloadsRepository.uiState.value.items
                    .filter { (it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed) && it.id !in managedIds }
                    .forEach { DownloadsRepository.resumeDownload(it.id) }
            }
            actionCancelAll -> DownloadsRepository.cancelUnfinishedDownloads()
        }
    }

    companion object {
        const val actionPauseAll = "com.nuvio.app.downloads.action.PAUSE_ALL"
        const val actionResumeAll = "com.nuvio.app.downloads.action.RESUME_ALL"
        const val actionCancelAll = "com.nuvio.app.downloads.action.CANCEL_ALL"
    }
}
