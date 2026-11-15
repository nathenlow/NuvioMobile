package com.nuvio.app.features.downloads

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskStateRunning
import platform.Foundation.NSURLSessionTaskStateSuspended
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val SESSION_IDENTIFIER = "com.nuvio.app.downloads"
private const val REQUEST_TIMEOUT_SECONDS = 60.0
private const val RESOURCE_TIMEOUT_SECONDS = 7.0 * 24.0 * 60.0 * 60.0
private const val PROGRESS_MIN_INTERVAL_SECONDS = 0.5
private const val PROGRESS_MIN_BYTE_DELTA = 512L * 1024L

internal class IosDownloadCallbacks(
    val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    val onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
    val onFailure: (message: String) -> Unit,
)

/**
 * Downloads through an iOS background session, so the system keeps transferring after the app is
 * left or closed and wakes the app when something finishes. The app side only has to attach to
 * what is running, and to pick up what finished while it was away.
 *
 * Small files in a state folder carry what must outlive the app: a marker for a download in
 * flight, the waiting line with the order of each download, resume data for a paused one, and the
 * outcome of one that ended while the app was away.
 *
 * The waiting line and the concurrent limit live here too, and are advanced whenever a transfer
 * ends, which the system reports to the app even when it was closed. So the next waiting download
 * starts with no screen open.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosBackgroundDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbacks = AtomicReference<Map<String, IosDownloadCallbacks>>(emptyMap())
    private val completionHandlers = AtomicReference<Map<String, () -> Unit>>(emptyMap())
    private val delegate = IosDownloadSessionDelegate()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pumpLock = NSLock()
    private var pumpRunning = false
    private var pumpAgain = false
    private val pumpsInFlight = AtomicInt(0)
    private val deferredHandler = AtomicReference<(() -> Unit)?>(null)

    val session: NSURLSession by lazy {
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION_IDENTIFIER).apply {
            sessionSendsLaunchEvents = true
            allowsCellularAccess = true
            timeoutIntervalForRequest = REQUEST_TIMEOUT_SECONDS
            timeoutIntervalForResource = RESOURCE_TIMEOUT_SECONDS
        }
        NSURLSession.sessionWithConfiguration(
            configuration = configuration,
            delegate = delegate,
            delegateQueue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 },
        )
    }

    /** Called when the system wakes the app for finished background transfers. */
    fun handleBackgroundEvents(identifier: String, completionHandler: () -> Unit) {
        if (identifier != SESSION_IDENTIFIER) {
            completionHandler()
            return
        }
        updateMap(completionHandlers) { it + (identifier to completionHandler) }
        // Creating the session is what makes the system deliver the waiting events to the delegate.
        session
    }

    /**
     * The system reported it has delivered every waiting event. Its completion handler lets the app be
     * suspended again, so it is held back until the downloads that should start next have been started.
     */
    fun finishBackgroundEvents(identifier: String) {
        var taken: (() -> Unit)? = null
        updateMap(completionHandlers) { current ->
            taken = current[identifier]
            current - identifier
        }
        val handler = taken ?: return
        deferredHandler.value = handler
        runDeferredHandlerIfIdle()
    }

    private fun runDeferredHandlerIfIdle() {
        if (pumpsInFlight.value > 0) return
        while (true) {
            val handler = deferredHandler.value ?: return
            if (deferredHandler.compareAndSet(handler, null)) {
                // The system expects this on the main thread.
                dispatch_async(dispatch_get_main_queue()) { handler() }
                return
            }
        }
    }

    /**
     * Hands a download over. It starts right away if a slot is free, and otherwise waits in the saved
     * line; either way it carries on without the app.
     */
    fun start(request: DownloadPlatformRequest, handlers: IosDownloadCallbacks) {
        val fileName = request.destinationFileName
        updateMap(callbacks) { it + (fileName to handlers) }
        // A partial file left by the earlier in-app downloader is not used any more.
        removePathIfExists("${downloadsDirectoryPath()}/$fileName.part")

        // Subtitles and pictures are fetched alongside, while the app is open.
        val videoUri = NSURL.fileURLWithPath("${downloadsDirectoryPath()}/$fileName").absoluteString
        if (videoUri != null) {
            scope.launch {
                runCatching { DownloadSubtitles.prepare(request.item, videoUri) }
                runCatching { DownloadArtwork.prepare(request.item, videoUri) }
            }
        }

        // It already finished while the app was away.
        readOutcome(fileName, "done")?.let { bytes ->
            clearState(fileName)
            updateMap(callbacks) { it - fileName }
            val destination = "${downloadsDirectoryPath()}/$fileName"
            handlers.onSuccess(fileUri(destination), bytes.toLongOrNull() ?: fileSizeOrNull(destination))
            return
        }

        writeText(statePath(fileName, "item"), json.encodeToString(request.item))
        val alreadyRunningOrWaiting = exists(statePath(fileName, "active")) || exists(statePath(fileName, "queued"))
        if (!alreadyRunningOrWaiting) writeText(statePath(fileName, "queued"), nextQueueOrder().toString())
        requestPump()
    }

    /**
     * Starts waiting downloads, first in line first, until the concurrent limit is reached. Runs when
     * a download is handed over, ends, is paused, or the order or the limit changes.
     */
    private fun requestPump() {
        // One pass at a time, each reading a fresh list of the running tasks. A second request that
        // comes in meanwhile makes the pass run again, so a stale list can never be acted on.
        pumpLock.lock()
        if (pumpRunning) {
            pumpAgain = true
            pumpLock.unlock()
            return
        }
        pumpRunning = true
        pumpsInFlight.incrementAndGet()
        pumpLock.unlock()
        runPumpPass()
    }

    private fun runPumpPass() {
        session.getTasksWithCompletionHandler { _, _, downloadTasks ->
            try {
                pump(downloadTasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>())
            } finally {
                pumpLock.lock()
                val again = pumpAgain
                pumpAgain = false
                if (!again) pumpRunning = false
                pumpLock.unlock()
                if (again) {
                    runPumpPass()
                } else {
                    pumpsInFlight.decrementAndGet()
                    runDeferredHandlerIfIdle()
                }
            }
        }
    }

    private fun pump(allTasks: List<NSURLSessionDownloadTask>) {
        val live = allTasks.filter {
            it.taskDescription != null &&
                (it.state == NSURLSessionTaskStateRunning || it.state == NSURLSessionTaskStateSuspended)
        }
        // A transfer that is neither marked running nor waiting was paused or removed: it must not hold a slot.
        val orphans = live.filter {
            val n = it.taskDescription.orEmpty()
            !exists(statePath(n, "active")) && !exists(statePath(n, "queued"))
        }
        orphans.forEach { it.cancel() }
        val liveTasks = live.filterNot { it in orphans }
        val liveNames = liveTasks.mapNotNull { it.taskDescription }.toSet()
        liveTasks.filter { it.state == NSURLSessionTaskStateSuspended }.forEach { it.resume() }

        // A download marked as running whose task is gone (the system dropped it) goes back in line.
        for (name in markerNames("active")) {
            if (name in liveNames) continue
            if (readOutcome(name, "done") != null || readOutcome(name, "failed") != null) continue
            removePathIfExists(statePath(name, "active"))
            if (exists(statePath(name, "item")) && !exists(statePath(name, "queued"))) {
                writeText(statePath(name, "queued"), nextQueueOrder().toString())
            }
        }

        val limit = (DownloadsStorage.loadMaxConcurrentDownloads() ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS)
            .coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
        var running = liveNames.size
        val waiting = markerNames("queued")
            .map { it to (readOutcome(it, "queued")?.trim()?.toLongOrNull() ?: Long.MAX_VALUE) }
            .sortedBy { it.second }
        for ((name, _) in waiting) {
            if (name in liveNames) {
                // Already running (the app was reopened): it only needs its running marker.
                removePathIfExists(statePath(name, "queued"))
                markActive(name)
                continue
            }
            if (running >= limit) break
            val item = readItem(name)
            if (item == null) {
                removePathIfExists(statePath(name, "queued"))
                continue
            }
            val task = createTask(name, item.sourceUrl, item.sourceHeaders)
            task.setTaskDescription(name)
            markActive(name)
            removePathIfExists(statePath(name, "queued"))
            task.resume()
            running++
        }
        refreshLiveActivityIfAppIsAway()
    }

    /**
     * The system woke the app only for finished downloads: no screen is attached, so the Live Activity
     * is refreshed here, from what is saved, to show the download that is running now (or to end).
     */
    private fun refreshLiveActivityIfAppIsAway() {
        if (callbacks.value.isNotEmpty()) return
        // Only what is running: a download waiting in line is not shown.
        val running = markerNames("active").mapNotNull { readItem(it) }.map { it.copy(status = DownloadStatus.Downloading) }
        DownloadsLiveStatusPlatform.onBackgroundItems(running)
    }

    /** A local notification for a download that finished while the app was away. */
    private fun postFinishedNotification(item: DownloadItem) {
        val content = UNMutableNotificationContent().apply {
            setTitle("Download complete")
            setBody(
                listOfNotNull(item.title, item.episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
            )
            setSound(UNNotificationSound.defaultSound)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "nuvio.download.finished.${item.id}",
            content = content,
            trigger = null,
        )
        // Nothing is shown if the user has not allowed notifications.
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, withCompletionHandler = null)
    }

    private fun createTask(fileName: String, sourceUrl: String, sourceHeaders: Map<String, String>): NSURLSessionDownloadTask {
        val resumeData = NSData.dataWithContentsOfFile(statePath(fileName, "resume"))
        if (resumeData != null) {
            // Resume data is used once; if it is stale the next attempt starts over.
            removePathIfExists(statePath(fileName, "resume"))
            return session.downloadTaskWithResumeData(resumeData)
        }
        val nativeRequest = NSMutableURLRequest(
            uRL = NSURL(string = sourceUrl),
            cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
            timeoutInterval = REQUEST_TIMEOUT_SECONDS,
        )
        nativeRequest.setHTTPMethod("GET")
        nativeRequest.setAllowsCellularAccess(true)
        sourceHeaders.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true)) {
                nativeRequest.setValue(value, forHTTPHeaderField = key)
            }
        }
        return session.downloadTaskWithRequest(nativeRequest)
    }

    fun isQueued(fileName: String): Boolean = exists(statePath(fileName, "queued"))

    /** Sets the waiting line to follow [fileNames], then starts whatever now fits. */
    fun reprioritize(fileNames: List<String>) {
        var order = 1L
        for (name in fileNames) {
            if (!exists(statePath(name, "queued"))) continue
            writeText(statePath(name, "queued"), order.toString())
            order++
        }
        requestPump()
    }

    /** Pauses a download, keeping what was received so it can carry on later. */
    fun pause(fileName: String) {
        updateMap(callbacks) { it - fileName }
        removePathIfExists(statePath(fileName, "queued"))
        session.getTasksWithCompletionHandler { _, _, downloadTasks ->
            val task = downloadTasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>().firstOrNull {
                it.taskDescription == fileName &&
                    (it.state == NSURLSessionTaskStateRunning || it.state == NSURLSessionTaskStateSuspended)
            }
            if (task == null) {
                removePathIfExists(statePath(fileName, "active"))
                requestPump()
                return@getTasksWithCompletionHandler
            }
            task.cancelByProducingResumeData { data ->
                if (data != null) data.writeToFile(statePath(fileName, "resume"), true)
                removePathIfExists(statePath(fileName, "active"))
                // Its slot is free: the next download in line can start.
                requestPump()
            }
        }
    }

    /** Stops a download for good and forgets everything kept for it. */
    fun cancelAndForget(fileName: String) {
        updateMap(callbacks) { it - fileName }
        session.getTasksWithCompletionHandler { _, _, downloadTasks ->
            downloadTasks.orEmpty().filterIsInstance<NSURLSessionDownloadTask>()
                .filter { it.taskDescription == fileName }
                .forEach { it.cancel() }
        }
        clearState(fileName)
        requestPump()
    }

    /**
     * What the app should believe about a download it finds in its saved list: one that ended while
     * the app was away has its outcome here, and one still in flight stays downloading.
     */
    fun restore(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Downloading) return item
        val fileName = item.fileName
        readOutcome(fileName, "done")?.let { bytes ->
            val destination = "${downloadsDirectoryPath()}/$fileName"
            if (NSFileManager.defaultManager.fileExistsAtPath(destination)) {
                clearState(fileName)
                val size = bytes.toLongOrNull() ?: fileSizeOrNull(destination) ?: item.downloadedBytes
                return item.copy(
                    status = DownloadStatus.Completed,
                    localFileUri = fileUri(destination),
                    downloadedBytes = size,
                    totalBytes = size,
                    errorMessage = null,
                )
            }
        }
        readOutcome(fileName, "failed")?.let { message ->
            clearState(fileName)
            return item.copy(status = DownloadStatus.Failed, errorMessage = message.ifBlank { null })
        }
        if (exists(statePath(fileName, "active")) || exists(statePath(fileName, "queued"))) return item
        return item.copy(status = DownloadStatus.Paused, errorMessage = null)
    }

    // --- called from the session delegate ---

    fun callbacksFor(fileName: String): IosDownloadCallbacks? = callbacks.value[fileName]

    fun succeed(fileName: String, destination: String) {
        val size = fileSizeOrNull(destination)
        removePathIfExists(statePath(fileName, "resume"))
        removePathIfExists(statePath(fileName, "active"))
        val handlers = callbacks.value[fileName]
        if (handlers != null) {
            updateMap(callbacks) { it - fileName }
            handlers.onSuccess(fileUri(destination), size)
        } else {
            // The app was away: leave the outcome for it to pick up on its next start, and fetch the
            // subtitles and pictures now if the network allows.
            writeText(statePath(fileName, "done"), (size ?: 0L).toString())
            readItem(fileName)?.let { item ->
                postFinishedNotification(item)
                scope.launch {
                    runCatching { DownloadSubtitles.prepare(item, fileUri(destination)) }
                    runCatching { DownloadArtwork.prepare(item, fileUri(destination)) }
                }
            }
        }
        removePathIfExists(statePath(fileName, "item"))
        // A slot is free: start the next download in line, even though no screen is open.
        requestPump()
    }

    fun fail(fileName: String, message: String) {
        removePathIfExists(statePath(fileName, "active"))
        val handlers = callbacks.value[fileName]
        if (handlers != null) {
            updateMap(callbacks) { it - fileName }
            handlers.onFailure(message)
        } else {
            writeText(statePath(fileName, "failed"), message)
        }
        removePathIfExists(statePath(fileName, "item"))
        requestPump()
    }

    fun saveResumeData(fileName: String, data: NSData) {
        data.writeToFile(statePath(fileName, "resume"), true)
    }

    // --- state files ---

    private fun markActive(fileName: String) = writeText(statePath(fileName, "active"), "1")

    private fun clearState(fileName: String) {
        listOf("active", "queued", "item", "resume", "done", "failed").forEach { removePathIfExists(statePath(fileName, it)) }
    }

    private fun readOutcome(fileName: String, kind: String): String? =
        NSString.stringWithContentsOfFile(statePath(fileName, kind), NSUTF8StringEncoding, null)

    private fun writeText(path: String, text: String) {
        NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null)
    }

    private fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    private fun readItem(fileName: String): DownloadItem? =
        readOutcome(fileName, "item")?.let { runCatching { json.decodeFromString<DownloadItem>(it) }.getOrNull() }

    /** File names that have a state file of [kind]. */
    private fun markerNames(kind: String): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(stateDirectoryPath(), null)
            ?.filterIsInstance<String>()
            ?.filter { it.endsWith(".$kind") }
            ?.map { it.removeSuffix(".$kind") }
            .orEmpty()

    /** A number that sorts after every download already waiting. */
    private fun nextQueueOrder(): Long =
        maxOf(
            (NSDate().timeIntervalSince1970 * 1000.0).toLong(),
            (markerNames("queued").maxOfOrNull { readOutcome(it, "queued")?.trim()?.toLongOrNull() ?: 0L } ?: 0L) + 1L,
        )

    private fun statePath(fileName: String, kind: String): String = "${stateDirectoryPath()}/$fileName.$kind"

    private fun <T> updateMap(reference: AtomicReference<Map<String, T>>, transform: (Map<String, T>) -> Map<String, T>) {
        while (true) {
            val current = reference.value
            if (reference.compareAndSet(current, transform(current))) return
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosDownloadSessionDelegate : NSObject(), NSURLSessionDownloadDelegateProtocol {
    // Only the delegate queue, which is serial, touches these.
    private val lastReportedBytes = mutableMapOf<String, Long>()
    private val lastReportedAt = mutableMapOf<String, Double>()

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        val fileName = downloadTask.taskDescription ?: return
        val handlers = IosBackgroundDownloads.callbacksFor(fileName) ?: return
        val now = NSDate().timeIntervalSince1970
        val total = totalBytesExpectedToWrite.takeIf { it > 0L }
        val reachedEnd = total != null && totalBytesWritten >= total
        val previousBytes = lastReportedBytes[fileName]
        if (
            previousBytes != null && !reachedEnd &&
            totalBytesWritten - previousBytes < PROGRESS_MIN_BYTE_DELTA &&
            now - (lastReportedAt[fileName] ?: 0.0) < PROGRESS_MIN_INTERVAL_SECONDS
        ) {
            return
        }
        lastReportedBytes[fileName] = totalBytesWritten
        lastReportedAt[fileName] = now
        handlers.onProgress(totalBytesWritten, total)
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        val fileName = downloadTask.taskDescription ?: return
        lastReportedBytes.remove(fileName)
        lastReportedAt.remove(fileName)
        val status = (downloadTask.response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 200
        if (status !in 200..299) {
            IosBackgroundDownloads.fail(fileName, "Request failed with HTTP $status")
            return
        }
        // The system deletes its temporary file when this returns, so the file is moved right here.
        val destination = "${downloadsDirectoryPath()}/$fileName"
        val source = didFinishDownloadingToURL.path
        removePathIfExists(destination)
        val moved = source != null &&
            NSFileManager.defaultManager.moveItemAtPath(srcPath = source, toPath = destination, error = null)
        if (!moved) {
            IosBackgroundDownloads.fail(fileName, "Failed to finalize download file")
            return
        }
        IosBackgroundDownloads.succeed(fileName, destination)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val error = didCompleteWithError ?: return
        // Pausing and cancelling end the task on purpose.
        if (error.code == NSURLErrorCancelled) return
        val fileName = task.taskDescription ?: return
        // Keep whatever was received, so trying again carries on instead of starting over.
        (error.userInfo[NSURLSessionDownloadTaskResumeData] as? NSData)?.let {
            IosBackgroundDownloads.saveResumeData(fileName, it)
        }
        IosBackgroundDownloads.fail(fileName, error.localizedDescription)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = session.configuration.identifier ?: return
        IosBackgroundDownloads.finishBackgroundEvents(identifier)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun stateDirectoryPath(): String {
    val path = "${NSHomeDirectory().trimEnd('/')}/Library/Application Support/nuvio_downloads_state"
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    return path
}

private fun fileUri(path: String): String = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
