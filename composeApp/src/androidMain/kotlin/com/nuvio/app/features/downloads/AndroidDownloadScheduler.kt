package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool

internal class AndroidDownloadScheduler(val context: Context) {
    val store = AndroidDownloadStore(File(context.filesDir, "download-transfers"))
    /** The folder new downloads go to: the chosen storage, or the private storage by default. */
    val directory: File get() = AndroidDownloadStorage.currentVolume(context).directory
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // The limit is read here, also when a background job runs without the app's screens.
        DownloadsStorage.initialize(context)
    }

    private fun concurrencyLimit(): Int =
        (DownloadsStorage.loadMaxConcurrentDownloads() ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS)
            .coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)

    private fun runningCount(except: String? = null): Int = store.transfers.value.values.count {
        it.item.status == DownloadStatus.Downloading && !it.queued && it.item.fileName != except
    }

    fun isQueued(fileName: String): Boolean = store.get(fileName)?.let {
        it.queued && it.item.status == DownloadStatus.Downloading
    } == true

    /**
     * Starts a download, or lines it up when the concurrent limit is reached. The waiting line is
     * kept on disk and advanced here, as downloads finish, so it keeps moving with the app closed.
     */
    fun enqueue(item: DownloadItem): AndroidDownloadTransfer {
        val previous = store.get(item.fileName)
        val transfer = store.begin(item)
        val existing = previous?.generation == transfer.generation
        if (existing && transfer.queued) {
            pump()
            return store.get(item.fileName) ?: transfer
        }
        if (existing && Build.VERSION.SDK_INT >= 34) return transfer
        val waitingLineExists = store.transfers.value.values.any {
            it.queued && it.item.status == DownloadStatus.Downloading && it.item.fileName != item.fileName
        }
        if (!existing && (waitingLineExists || runningCount(except = item.fileName) >= concurrencyLimit())) {
            store.update(item.fileName, transfer.generation) {
                it.copy(queued = true, queueOrder = nextQueueOrder())
            }
            return store.get(item.fileName) ?: transfer
        }
        schedule(transfer, existing)
        return store.get(item.fileName) ?: transfer
    }

    private fun nextQueueOrder(): Long =
        (store.transfers.value.values.maxOfOrNull { it.queueOrder } ?: 0L) + 1L

    private fun schedule(transfer: AndroidDownloadTransfer, existing: Boolean) {
        val item = transfer.item
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                check(jobScheduler().schedule(buildJob(transfer)) == JobScheduler.RESULT_SUCCESS) {
                    "Android could not start the download. Open the app and try again."
                }
            } else {
                val work = OneTimeWorkRequestBuilder<DownloadsTransferWorker>()
                    .setInputData(workDataOf(FILE_NAME to item.fileName, GENERATION to transfer.generation))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(transfer), if (existing) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, work,
                )
                operation.result.addListener({
                    try {
                        operation.result.get()
                    } catch (error: Exception) {
                        fail(transfer, error)
                        notifyCurrent(item.fileName)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        } catch (error: Exception) {
            fail(transfer, error)
        }
    }

    /** Starts waiting downloads, first in line first, until the concurrent limit is reached. */
    @Synchronized
    fun pump() {
        val limit = concurrencyLimit()
        var running = runningCount()
        val waiting = store.transfers.value.values
            .filter { it.queued && it.item.status == DownloadStatus.Downloading }
            .sortedBy { it.queueOrder }
        for (candidate in waiting) {
            if (running >= limit) break
            val ready = store.update(candidate.item.fileName, candidate.generation) { it.copy(queued = false) }
                ?: continue
            schedule(ready, existing = false)
            running++
        }
    }

    /** Sets the waiting line to follow [fileNames], then starts whatever now fits. */
    @Synchronized
    fun reprioritize(fileNames: List<String>) {
        var order = 1L
        for (name in fileNames) {
            val waiting = store.get(name)?.takeIf { it.queued && it.item.status == DownloadStatus.Downloading } ?: continue
            store.update(name, waiting.generation) { it.copy(queueOrder = order) }
            order++
        }
        pump()
    }

    @RequiresApi(34)
    internal fun buildJob(transfer: AndroidDownloadTransfer): JobInfo =
        JobInfo.Builder(transfer.jobId, ComponentName(context, DownloadsTransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setEstimatedNetworkBytes(transfer.item.totalBytes ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply {
                putString(FILE_NAME, transfer.item.fileName)
                putString(GENERATION, transfer.generation)
            })
            .build()

    @RequiresApi(34)
    private fun jobScheduler(): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE)

    fun restore(item: DownloadItem): DownloadItem {
        var transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return if (item.status == DownloadStatus.Downloading) {
                item.copy(status = DownloadStatus.Paused, errorMessage = null)
            } else item
        if (transfer.item.status == DownloadStatus.Downloading && !transfer.queued && Build.VERSION.SDK_INT >= 34 &&
            jobScheduler().getPendingJob(transfer.jobId) == null) {
            pause(item.fileName)
            transfer = store.get(item.fileName) ?: transfer
        }
        return transfer.item
    }

    fun pause(fileName: String) {
        val transfer = store.get(fileName) ?: return
        if (transfer.item.status != DownloadStatus.Downloading) return
        val paused = store.update(fileName, transfer.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused, errorMessage = null), queued = false)
        }
        cancelScheduled(transfer)
        paused?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
        pump()
    }

    fun remove(fileName: String) {
        val transfer = store.get(fileName)
        store.remove(fileName)
        transfer?.let(::cancelScheduled)
        transfer?.let { DownloadsLiveStatusPlatform.removeNotification(it.item.id) }
        pump()
        cleanupScope.launch {
            lock(fileName).withLock {
                if (store.get(fileName) == null) {
                    // The files may be on any storage the downloads were kept on.
                    AndroidDownloadStorage.volumes(context).map { it.directory }.forEach { folder ->
                        File(folder, "$fileName.part").delete()
                        DownloadSubtitleStorage(File(folder, fileName).toURI().toString()).remove()
                        DownloadArtworkStorage(File(folder, fileName).toURI().toString()).remove()
                    }
                }
            }
        }
    }

    private fun cancelScheduled(transfer: AndroidDownloadTransfer) {
        if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
        else WorkManager.getInstance(context).cancelUniqueWork(workName(transfer))
    }

    suspend fun execute(
        transfer: AndroidDownloadTransfer,
        network: Network? = null,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ): Boolean = try {
        executeTransfer(transfer, network, onProgress)
    } finally {
        // This transfer is done or stopped, so a waiting download can take its place, even with no screen open.
        pump()
    }

    private suspend fun executeTransfer(
        transfer: AndroidDownloadTransfer,
        network: Network?,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ): Boolean = lock(transfer.item.fileName).withLock {
        val fileName = transfer.item.fileName
        if (!isActive(transfer)) return@withLock false
        // A download continues where it started, even if the chosen storage changed since.
        val directory = AndroidDownloadStorage.directoryFor(context, fileName)
        val destination = File(directory, fileName)
        val client = if (network != null) {
            downloadHttpClient.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { network.getAllByName(it).toList() }
                .connectionPool(ConnectionPool())
                .build()
        } else downloadHttpClient
        try {
            DownloadSubtitles.prepare(transfer.item, destination.toURI().toString())
            DownloadArtwork.prepare(transfer.item, destination.toURI().toString())
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            var lastProgressAt = 0L
            val partial = if (destination.isFile) destination else transferAndroidDownload(
                item = transfer.item,
                directory = directory,
                validator = transfer.validator,
                client = client,
                onHeaders = { total, validator ->
                    updateActive(transfer) { it.copy(validator = validator, item = it.item.copy(totalBytes = total)) }
                },
                onProgress = { bytes, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 1_000L || bytes == total) {
                        lastProgressAt = now
                        updateActive(transfer) {
                            it.copy(item = it.item.copy(downloadedBytes = bytes, totalBytes = total))
                        }?.let(onProgress)
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            updateActive(transfer) { current ->
                if (partial != destination && !partial.renameTo(destination)) {
                    throw IOException("Could not finalize the downloaded file")
                }
                val bytes = destination.length()
                current.copy(item = current.item.copy(
                    status = DownloadStatus.Completed,
                    localFileUri = destination.toURI().toString(),
                    downloadedBytes = bytes,
                    totalBytes = bytes,
                    errorMessage = null,
                ))
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            val retry = shouldRetryAndroidDownload(error, transfer.retryCount)
            if (retry) updateActive(transfer) { it.copy(retryCount = it.retryCount + 1) }
            else fail(transfer, error)
            retry
        } finally {
            if (network != null) withContext(NonCancellable + Dispatchers.IO) { client.connectionPool.evictAll() }
        }
    }

    fun isActive(transfer: AndroidDownloadTransfer): Boolean = store.get(transfer.item.fileName)?.let {
        it.generation == transfer.generation && it.item.status == DownloadStatus.Downloading
    } == true

    fun fail(transfer: AndroidDownloadTransfer, error: Exception) {
        updateActive(transfer) {
            it.copy(item = it.item.copy(status = DownloadStatus.Failed, errorMessage = error.message ?: "Download failed"))
        }
        pump()
    }

    fun notifyCurrent(fileName: String) {
        store.get(fileName)?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    private fun updateActive(
        transfer: AndroidDownloadTransfer,
        update: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = store.update(transfer.item.fileName, transfer.generation) {
        if (it.item.status == DownloadStatus.Downloading) {
            update(it).let { changed -> changed.copy(item = changed.item.copy(updatedAtEpochMs = System.currentTimeMillis())) }
        } else it
    }

    private fun lock(fileName: String): Mutex = locks.getOrPut(fileName) { Mutex() }
    private fun workName(transfer: AndroidDownloadTransfer) = "nuvio-download-${transfer.jobId}"

    companion object {
        const val FILE_NAME = "download_file_name"
        const val GENERATION = "download_generation"
        const val JOB_NAMESPACE = "nuvio-downloads"
    }
}

internal fun shouldRetryAndroidDownload(error: Exception, retries: Int): Boolean =
    retries < 4 && when (error) {
        is DownloadHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        is IOException -> true
        else -> false
    }
