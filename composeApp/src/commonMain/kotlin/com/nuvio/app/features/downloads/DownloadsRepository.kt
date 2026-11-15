package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()

    /** Downloads waiting for a free slot, in the order they were queued. */
    private val startQueue = ArrayDeque<String>()

    /** Every episode's details per show, kept while the show has downloads. Dropped when it has none. */
    private var episodeSnapshots: Map<String, List<SavedEpisode>> = emptyMap()

    private val _saveFullEpisodeList = MutableStateFlow(true)
    val saveFullEpisodeList: StateFlow<Boolean> = _saveFullEpisodeList.asStateFlow()

    private val _maxConcurrentDownloads = MutableStateFlow(DEFAULT_MAX_CONCURRENT_DOWNLOADS)
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()
    private val _preferredResolution = MutableStateFlow(0)
    /** The resolution bulk downloads prefer among cached streams; 0 follows the addon ranking only. */
    val preferredResolution: StateFlow<Int> = _preferredResolution.asStateFlow()
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun setMaxConcurrentDownloads(value: Int) {
        val limit = value.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
        _maxConcurrentDownloads.value = limit
        DownloadsStorage.saveMaxConcurrentDownloads(limit)
        pumpQueue()
        syncPlatformPriority()
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        startQueue.clear()
        hasLoaded = false
        _hasUnseenCompleted.value = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
    ): DownloadEnqueueResult {
        ensureLoaded()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            startQueue.remove(existing.id)
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(existing.fileName)
            currentItems.removeAll { it.id == existing.id }
        }

        // Save what the offline library needs to show this title without the network.
        val meta = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
        val episodeVideo = meta?.videos?.firstOrNull { video ->
            video.season == seasonNumber && video.episode == episodeNumber
        }

        val downloadId = nextDownloadId(now)
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            sourceUrl = sourceUrl,
            downloadId = downloadId,
        )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            subtitleRequests = addonSubtitleRequests(
                type = contentType,
                videoId = videoId,
                extras = SubtitleRequestExtras.from(stream.behaviorHints, sourceUrl),
            ),
            sourceSubtitles = stream.externalSubtitles,
            localFileUri = null,
            fileName = fileName,
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            description = meta?.description?.takeIf { it.isNotBlank() },
            releaseInfo = meta?.releaseInfo?.takeIf { it.isNotBlank() },
            genres = meta?.genres.orEmpty(),
            imdbRating = meta?.imdbRating?.takeIf { it.isNotBlank() },
            runtime = meta?.runtime?.takeIf { it.isNotBlank() },
            episodeOverview = episodeVideo?.overview?.takeIf { it.isNotBlank() },
        )

        // New downloads join the end of the active ones, so a batch keeps the order it was queued in.
        val lastActiveIndex = currentItems.indexOfLast { it.status != DownloadStatus.Completed }
        currentItems.add(lastActiveIndex + 1, item)
        publish(currentItems)
        meta?.let(::captureEpisodeSnapshot)
        persist()
        startDownload(item)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        activeHandles.remove(downloadId)?.cancel()
        startQueue.remove(downloadId)
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
        pumpQueue()
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val reset = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        startDownload(reset)
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    internal fun reattachBackgroundDownload(downloadId: String) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        activeHandles.remove(downloadId)?.cancel()
        val restored = DownloadsPlatformDownloader.restoreItem(item)
        replaceItem(restored)
        persist()
        if (restored.status == DownloadStatus.Downloading) startDownload(restored)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        activeHandles.remove(downloadId)?.cancel()
        startQueue.remove(downloadId)
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.fileName)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist()
        pumpQueue()
    }

    /**
     * Cancels every download that is not finished, deleting its partial file. Finished downloads are
     * kept. With [parentMetaId] only that show's downloads are cancelled. Returns how many were cancelled.
     */
    fun cancelUnfinishedDownloads(parentMetaId: String? = null): Int {
        ensureLoaded()
        val showId = parentMetaId?.trim()
        val targets = _uiState.value.items.filter { item ->
            item.status != DownloadStatus.Completed && (showId == null || item.parentMetaId.trim() == showId)
        }
        if (targets.isEmpty()) return 0

        targets.forEach { item ->
            activeHandles.remove(item.id)?.cancel()
            startQueue.remove(item.id)
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(item.fileName)
        }
        val removedIds = targets.mapTo(hashSetOf()) { it.id }
        publish(_uiState.value.items.filterNot { it.id in removedIds })
        persist()
        pumpQueue()
        return targets.size
    }

    /**
     * Resumes every paused or failed download, first episode first. With [parentMetaId] only that
     * show's downloads are resumed. Returns how many were resumed.
     */
    fun resumeAllDownloads(parentMetaId: String? = null): Int {
        ensureLoaded()
        val showId = parentMetaId?.trim()
        val targets = _uiState.value.items
            .filter { item ->
                (item.status == DownloadStatus.Paused || item.status == DownloadStatus.Failed) &&
                    (showId == null || item.parentMetaId.trim() == showId)
            }
            .sortedForSeriesDownloads()
        targets.forEach { resumeDownload(it.id) }
        return targets.size
    }

    /**
     * Refreshes the details saved with a title's downloads from freshly loaded [meta], so the offline
     * pages stay current. Only titles that already have downloads are touched: nothing is saved for
     * a title without any, and the details still go when its last download does.
     */
    fun refreshSavedMetadata(meta: com.nuvio.app.features.details.MetaDetails) {
        ensureLoaded()
        val showId = meta.id.trim()
        if (_uiState.value.items.none { it.parentMetaId.trim() == showId }) return
        val snapshotsBefore = episodeSnapshots
        captureEpisodeSnapshot(meta)
        var changed = snapshotsBefore != episodeSnapshots
        val updated = _uiState.value.items.map { item ->
            if (item.parentMetaId.trim() != showId) return@map item
            val video = meta.videos.firstOrNull { it.season == item.seasonNumber && it.episode == item.episodeNumber }
                .takeIf { item.isEpisode }
            val refreshed = item.copy(
                title = meta.name.takeIf { it.isNotBlank() } ?: item.title,
                poster = meta.poster?.takeIf { it.isNotBlank() } ?: item.poster,
                background = meta.background?.takeIf { it.isNotBlank() } ?: item.background,
                logo = meta.logo?.takeIf { it.isNotBlank() } ?: item.logo,
                description = meta.description?.takeIf { it.isNotBlank() } ?: item.description,
                releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: item.releaseInfo,
                genres = meta.genres.ifEmpty { item.genres },
                imdbRating = meta.imdbRating?.takeIf { it.isNotBlank() } ?: item.imdbRating,
                runtime = meta.runtime?.takeIf { it.isNotBlank() } ?: item.runtime,
                episodeTitle = video?.title?.takeIf { it.isNotBlank() } ?: item.episodeTitle,
                episodeThumbnail = video?.thumbnail?.takeIf { it.isNotBlank() } ?: item.episodeThumbnail,
                episodeOverview = video?.overview?.takeIf { it.isNotBlank() } ?: item.episodeOverview,
            )
            if (refreshed != item) changed = true
            refreshed
        }
        if (changed) {
            publish(updated)
            persist()
        }
        backfillArtwork()
    }

    private val artworkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var artworkJob: Job? = null

    /**
     * Saves the pictures of finished downloads that are not on the device yet, for downloads made
     * before pictures were kept. Needs the network; failures are ignored and tried again next time.
     */
    fun backfillArtwork() {
        ensureLoaded()
        if (artworkJob?.isActive == true) return
        val targets = _uiState.value.items.filter { it.status == DownloadStatus.Completed }
        if (targets.isEmpty()) return
        artworkJob = artworkScope.launch {
            var saved = false
            for (item in targets) {
                val uri = DownloadsPlatformDownloader.resolveLocalFileUri(item.localFileUri, item.fileName) ?: continue
                val before = ArtworkKind.entries.count { DownloadArtwork.localUri(uri, it) != null }
                DownloadArtwork.prepare(item, uri)
                val after = ArtworkKind.entries.count { DownloadArtwork.localUri(uri, it) != null }
                if (after > before) saved = true
            }
            if (saved) _uiState.update { it.copy(artworkRevision = it.artworkRevision + 1) }
        }
    }

    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var storageJob: Job? = null

    private val _storageMigration = MutableStateFlow(StorageMigrationState())
    val storageMigration: StateFlow<StorageMigrationState> = _storageMigration.asStateFlow()

    fun storageVolumes(): List<DownloadStorageVolume> = DownloadsPlatformDownloader.storageVolumes()

    fun selectedStorageVolumeId(): String = DownloadsPlatformDownloader.selectedStorageVolumeId()

    /** New downloads are saved to [volumeId]. Existing ones stay where they are until moved. */
    fun selectStorageVolume(volumeId: String) {
        DownloadsPlatformDownloader.selectStorageVolume(volumeId)
    }

    fun storageVolumeIdOf(item: DownloadItem): String? = DownloadsPlatformDownloader.storageVolumeIdOf(item)

    /** Downloads whose files are not on [volumeId]. */
    fun downloadsOutsideStorage(volumeId: String): List<DownloadItem> {
        ensureLoaded()
        return _uiState.value.items.filter { item ->
            val where = DownloadsPlatformDownloader.storageVolumeIdOf(item)
            where != null && where != volumeId
        }
    }

    /**
     * Moves every download's files to [volumeId], in the background of the app, reporting through
     * [storageMigration]. Needs no download to be running. [onResult] is called when it ends.
     */
    fun startMoveToStorage(volumeId: String, onResult: (StorageMoveResult) -> Unit) {
        if (storageJob?.isActive == true) {
            onResult(StorageMoveResult.Busy)
            return
        }
        storageJob = storageScope.launch { onResult(moveToStorage(volumeId)) }
    }

    fun cancelStorageMove() {
        storageJob?.cancel()
    }

    private suspend fun moveToStorage(volumeId: String): StorageMoveResult {
        ensureLoaded()
        val target = DownloadsPlatformDownloader.storageVolumes().firstOrNull { it.id == volumeId }
            ?: return StorageMoveResult.NotConnected
        if (_uiState.value.items.any { it.status == DownloadStatus.Downloading }) {
            return StorageMoveResult.ActiveDownloads
        }
        val toMove = downloadsOutsideStorage(volumeId)
        if (toMove.isEmpty()) return StorageMoveResult.Done(moved = 0, failed = 0)
        val totalBytes = toMove.sumOf { it.totalBytes ?: it.downloadedBytes }
        if (target.freeBytes < totalBytes + STORAGE_SAFETY_MARGIN_BYTES) {
            return StorageMoveResult.NotEnoughSpace(neededBytes = totalBytes, freeBytes = target.freeBytes)
        }

        _storageMigration.value = StorageMigrationState(running = true, totalBytes = totalBytes, totalItems = toMove.size)
        var moved = 0
        var failed = 0
        try {
            for (item in toMove) {
                _storageMigration.update { it.copy(currentTitle = item.episodeTitle?.takeIf(String::isNotBlank) ?: item.title) }
                try {
                    val updated = DownloadsPlatformDownloader.moveToVolume(item, volumeId) { bytes ->
                        _storageMigration.update { it.copy(movedBytes = it.movedBytes + bytes) }
                    }
                    if (updated.localFileUri != item.localFileUri) {
                        mutateItem(item.id) { current -> current.copy(localFileUri = updated.localFileUri) }
                    }
                    moved++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failed++
                }
                _storageMigration.update { it.copy(doneItems = it.doneItems + 1) }
            }
        } finally {
            // Even when stopped part-way, point finished downloads at the files where they are now.
            withContext(NonCancellable) {
                var changed = false
                val fixed = _uiState.value.items.map { item ->
                    normalizeCompletedLocalFileUri(item).also { if (it != item) changed = true }
                }
                if (changed) {
                    publish(fixed)
                    persist()
                }
                _storageMigration.value = StorageMigrationState()
            }
        }
        return StorageMoveResult.Done(moved = moved, failed = failed)
    }

    /** Deletes every download of a title, finished or not, along with the details saved for it. */
    fun deleteTitleDownloads(parentMetaId: String): Int {
        ensureLoaded()
        val showId = parentMetaId.trim()
        val targets = _uiState.value.items.filter { it.parentMetaId.trim() == showId }
        if (targets.isEmpty()) return 0
        targets.forEach { item ->
            activeHandles.remove(item.id)?.cancel()
            startQueue.remove(item.id)
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(item.fileName)
        }
        val removedIds = targets.mapTo(hashSetOf()) { it.id }
        publish(_uiState.value.items.filterNot { it.id in removedIds })
        persist()
        pumpQueue()
        return targets.size
    }

    /**
     * Writes a small record of a finished download next to its file, so the download list can be
     * rebuilt from the files alone. It lives in the same side folder as the subtitles and goes with it.
     */
    private fun writeRecord(item: DownloadItem) {
        val uri = DownloadsPlatformDownloader.resolveLocalFileUri(item.localFileUri, item.fileName) ?: return
        runCatching { DownloadSubtitleStorage(uri).write(RECORD_FILE_NAME, DownloadsCodec.encodeItem(item)) }
    }

    private fun readRecord(uri: String): DownloadItem? =
        runCatching { DownloadSubtitleStorage(uri).read(RECORD_FILE_NAME) }.getOrNull()
            ?.let(DownloadsCodec::decodeItem)

    /**
     * Brings the download list in line with the files on the device: drops finished downloads whose
     * video is gone, restores downloads whose video is there with a saved record, and cleans up
     * leftover partial files and side folders. Videos that cannot be identified are never deleted.
     */
    suspend fun verifyAgainstDisk(): DownloadsVerifyResult = withContext(Dispatchers.Default) {
        ensureLoaded()
        val items = _uiState.value.items

        // Finished downloads whose video file no longer exists.
        val withoutFile = items.filter { item ->
            item.status == DownloadStatus.Completed &&
                DownloadsPlatformDownloader.resolveLocalFileUri(item.localFileUri, item.fileName) == null
        }
        // A file on an SD card that is taken out is not gone: leave those downloads alone.
        val (unavailable, missing) = withoutFile.partition { item ->
            DownloadsPlatformDownloader.isOnDisconnectedStorage(item.localFileUri)
        }
        missing.forEach { DownloadsPlatformDownloader.removePartialFile(it.fileName) }
        val missingIds = missing.mapTo(hashSetOf()) { it.id }
        val kept = items.filterNot { it.id in missingIds }

        // Keep a record next to every finished video that has one missing.
        kept.filter { it.status == DownloadStatus.Completed }.forEach { item ->
            val uri = DownloadsPlatformDownloader.resolveLocalFileUri(item.localFileUri, item.fileName) ?: return@forEach
            if (runCatching { DownloadSubtitleStorage(uri).read(RECORD_FILE_NAME) }.getOrNull() == null) writeRecord(item)
        }

        // Videos on disk that the list does not know: restore them from their record.
        val entries = DownloadsPlatformDownloader.listDownloadEntries()
        val knownFiles = kept.mapTo(hashSetOf()) { it.fileName }
        val knownKeys = kept.mapTo(hashSetOf()) { it.logicalContentKey }
        val restored = mutableListOf<DownloadItem>()
        var unmatched = 0
        for (name in entries) {
            if (name.endsWith(".part") || name.endsWith(".subtitles") || name.endsWith(".artwork")) continue
            if (name in knownFiles) continue
            val uri = DownloadsPlatformDownloader.resolveLocalFileUri(null, name)
            val record = uri?.let(::readRecord)
            if (uri == null || record == null) {
                unmatched++
                continue
            }
            val item = record.copy(
                fileName = name,
                localFileUri = uri,
                status = DownloadStatus.Completed,
                errorMessage = null,
            )
            if (item.logicalContentKey in knownKeys) {
                unmatched++
                continue
            }
            knownKeys += item.logicalContentKey
            knownFiles += name
            restored += item
        }

        // Partial files and side folders whose video is not part of any download.
        var cleaned = 0
        val leftoverBases = entries.mapNotNull { name ->
            when {
                name.endsWith(".part") -> name.removeSuffix(".part")
                name.endsWith(".subtitles") -> name.removeSuffix(".subtitles")
                name.endsWith(".artwork") -> name.removeSuffix(".artwork")
                else -> null
            }
        }.distinct()
        for (base in leftoverBases) {
            if (base in knownFiles) continue
            if (base in entries && base !in knownFiles) continue // an unidentified video: leave its folders
            DownloadsPlatformDownloader.removePartialFile(base)
            cleaned++
        }

        // Apply to whatever the list is now, as downloads may have moved on while this ran.
        val current = _uiState.value.items
        val updated = current.filterNot { it.id in missingIds } + restored
        if (missingIds.isNotEmpty() || restored.isNotEmpty()) {
            publish(updated)
            persist()
        }
        DownloadsVerifyResult(
            removedRecords = missingIds.size,
            restoredRecords = restored.size,
            cleanedLeftovers = cleaned,
            unmatchedFiles = unmatched,
            unavailableFiles = unavailable.size,
        )
    }

    /** Moves one unfinished download to where another one is, as when it is dragged over it. */
    fun moveDownload(fromId: String, toId: String) {
        ensureLoaded()
        val items = _uiState.value.items.toMutableList()
        val from = items.indexOfFirst { it.id == fromId }
        val to = items.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to) return
        items.add(to, items.removeAt(from))
        publish(items)
        persist()
        syncPlatformPriority()
    }

    /** Puts one unfinished download first in line. A running download is not interrupted. */
    fun moveToTop(downloadId: String) {
        ensureLoaded()
        val items = _uiState.value.items.toMutableList()
        val from = items.indexOfFirst { it.id == downloadId }
        if (from < 0 || items[from].status == DownloadStatus.Completed) return
        val item = items.removeAt(from)
        items.add(items.indexOfFirst { it.status != DownloadStatus.Completed }.coerceAtLeast(0), item)
        publish(items)
        persist()
        syncPlatformPriority()
    }

    /** Puts every unfinished download of a show first in line, first episode first. */
    fun moveSeriesToTop(parentMetaId: String) {
        ensureLoaded()
        val showId = parentMetaId.trim()
        val items = _uiState.value.items.toMutableList()
        val series = items
            .filter { it.status != DownloadStatus.Completed && it.parentMetaId.trim() == showId }
            .sortedForSeriesDownloads()
        if (series.isEmpty()) return
        val seriesIds = series.mapTo(hashSetOf()) { it.id }
        items.removeAll { it.id in seriesIds }
        items.addAll(items.indexOfFirst { it.status != DownloadStatus.Completed }.coerceAtLeast(0), series)
        publish(items)
        persist()
        syncPlatformPriority()
    }

    private fun loadFromDisk() {
        _maxConcurrentDownloads.value = DownloadsStorage.loadMaxConcurrentDownloads()
            ?.coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
            ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS
        _saveFullEpisodeList.value = DownloadsStorage.loadSaveFullEpisodeList() ?: true
        _preferredResolution.value = DownloadsStorage.loadPreferredResolution()?.takeIf { it in PREFERRED_RESOLUTION_CHOICES } ?: 0
        startQueue.clear()
        _hasUnseenCompleted.value = false
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        episodeSnapshots = if (_saveFullEpisodeList.value) DownloadsCodec.decodeSnapshots(payload) else emptyMap()
        if (payload.isEmpty()) {
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val normalized = DownloadsCodec.decodeItems(payload)
            .map { item ->
                val statusNormalized = DownloadsPlatformDownloader.restoreItem(item)

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
        normalized.filter { it.status == DownloadStatus.Downloading && it.id !in activeHandles }
            .forEach(::startDownload)
    }

    /** Queues a download; it starts once fewer than the concurrent download limit are running. */
    private fun startDownload(item: DownloadItem) {
        if (item.id in activeHandles || item.id in startQueue) return
        startQueue.addLast(item.id)
        pumpQueue()
    }

    private fun pumpQueue() {
        // The list order is the priority order, so the first waiting download in the list starts next.
        // Where the platform keeps the queue itself (Android), everything is handed over at once.
        val capacity = if (DownloadsPlatformDownloader.managesQueue) Int.MAX_VALUE else _maxConcurrentDownloads.value
        while (activeHandles.size < capacity) {
            val item = _uiState.value.items.firstOrNull { candidate ->
                candidate.id in startQueue &&
                    candidate.status == DownloadStatus.Downloading &&
                    candidate.id !in activeHandles
            } ?: break
            startQueue.remove(item.id)
            launchDownload(item)
        }
        val items = _uiState.value.items
        startQueue.removeAll { id -> items.none { it.id == id && it.status == DownloadStatus.Downloading } }
        val queued = startQueue.toSet()
        if (_uiState.value.queuedIds != queued) {
            _uiState.value = _uiState.value.copy(queuedIds = queued)
        }
    }

    private fun launchDownload(item: DownloadItem) {
        val request = DownloadPlatformRequest(item)

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes ->
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            totalBytes = totalBytes?.takeIf { it > 0L },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            errorMessage = null,
                        )
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                var completed: DownloadItem? = null
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        errorMessage = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    ).also { completed = it }
                }
                completed?.let(::writeRecord)
                pumpQueue()
            },
            onFailure = { message ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
                pumpQueue()
            },
            onPaused = {
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(status = DownloadStatus.Paused, errorMessage = null)
                }
                pumpQueue()
            },
        )

        activeHandles[item.id] = handle
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        val previousStatuses = _uiState.value.items.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && previousStatuses[it.id].let { status -> status != null && status != DownloadStatus.Completed } }) {
            _hasUnseenCompleted.value = true
        }
        _uiState.value = _uiState.value.copy(
            items = items,
            queuedIds = startQueue.toSet() + platformQueuedIds(items),
        )
        notifyLiveStatusPlatform()
    }

    private fun platformQueuedIds(items: List<DownloadItem>): Set<String> =
        if (DownloadsPlatformDownloader.managesQueue) {
            items.filter { it.status == DownloadStatus.Downloading && DownloadsPlatformDownloader.isQueued(it.fileName) }
                .mapTo(hashSetOf()) { it.id }
        } else {
            emptySet()
        }

    /** Tells the platform the current priority order, and applies a changed concurrent limit. */
    private fun syncPlatformPriority() {
        if (!DownloadsPlatformDownloader.managesQueue) return
        DownloadsPlatformDownloader.reprioritize(
            _uiState.value.items.filter { it.status == DownloadStatus.Downloading }.map { it.fileName },
        )
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        // A show's saved episode list goes with its last download.
        val titlesWithDownloads = _uiState.value.items.mapTo(hashSetOf()) { it.parentMetaId }
        episodeSnapshots = episodeSnapshots.filterKeys { it in titlesWithDownloads }
        DownloadsStorage.savePayload(
            DownloadsCodec.encodeItems(_uiState.value.items, episodeSnapshots),
        )
    }

    /** The episode list saved for a show, empty when none is kept. */
    fun savedEpisodes(metaId: String): List<SavedEpisode> =
        if (_saveFullEpisodeList.value) episodeSnapshots[metaId.trim()].orEmpty() else emptyList()

    /**
     * Turns saving of every episode's details on or off. Off drops what was saved, so offline pages go
     * back to listing only the downloaded episodes; on fills it in again from the details pages.
     */
    fun setPreferredResolution(height: Int) {
        ensureLoaded()
        val value = height.takeIf { it in PREFERRED_RESOLUTION_CHOICES } ?: 0
        _preferredResolution.value = value
        DownloadsStorage.savePreferredResolution(value)
    }

    fun setSaveFullEpisodeList(enabled: Boolean) {
        ensureLoaded()
        _saveFullEpisodeList.value = enabled
        DownloadsStorage.saveSaveFullEpisodeList(enabled)
        if (!enabled) {
            episodeSnapshots = emptyMap()
            persist()
        } else {
            backfillEpisodeSnapshots()
        }
    }

    private fun captureEpisodeSnapshot(meta: com.nuvio.app.features.details.MetaDetails) {
        if (!_saveFullEpisodeList.value) return
        val episodes = meta.videos.mapNotNull { video ->
            val season = video.season ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            SavedEpisode(
                id = video.id,
                title = video.title,
                season = season,
                episode = episode,
                released = video.released,
                thumbnail = video.thumbnail,
                overview = video.overview,
                runtime = video.runtime,
            )
        }
        if (episodes.isEmpty() || episodeSnapshots[meta.id.trim()] == episodes) return
        episodeSnapshots = episodeSnapshots + (meta.id.trim() to episodes)
    }

    private var snapshotJob: Job? = null

    /** Fetches the details of downloaded shows that have no saved episode list. Needs the network. */
    private fun backfillEpisodeSnapshots() {
        if (snapshotJob?.isActive == true) return
        val missing = _uiState.value.items
            .filter { it.isEpisode && it.parentMetaId.trim() !in episodeSnapshots }
            .distinctBy { it.parentMetaId }
        if (missing.isEmpty()) return
        snapshotJob = artworkScope.launch {
            for (item in missing) {
                val meta = runCatching { MetaDetailsRepository.fetch(item.parentMetaType, item.parentMetaId) }.getOrNull()
                    ?: continue
                captureEpisodeSnapshot(meta)
            }
            persist()
        }
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
    val episodeSnapshots: Map<String, List<SavedEpisode>> = emptyMap(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    fun encodeItem(item: DownloadItem): String = json.encodeToString(item)

    fun decodeItem(text: String): DownloadItem? =
        runCatching { json.decodeFromString<DownloadItem>(text) }.getOrNull()

    fun decodeSnapshots(payload: String): Map<String, List<SavedEpisode>> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).episodeSnapshots
        }.getOrDefault(emptyMap())

    fun encodeItems(
        items: Collection<DownloadItem>,
        episodeSnapshots: Map<String, List<SavedEpisode>> = emptyMap(),
    ): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
                episodeSnapshots = episodeSnapshots,
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}

const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 3
const val MIN_CONCURRENT_DOWNLOADS = 1
const val MAX_CONCURRENT_DOWNLOADS = 10

private const val RECORD_FILE_NAME = "record.json"

/** What [DownloadsRepository.verifyAgainstDisk] changed. */
data class DownloadsVerifyResult(
    val removedRecords: Int,
    val restoredRecords: Int,
    val cleanedLeftovers: Int,
    val unmatchedFiles: Int,
    /** Downloads on storage that is not connected, which were left alone. */
    val unavailableFiles: Int = 0,
) {
    val changedAnything: Boolean
        get() = removedRecords + restoredRecords + cleanedLeftovers > 0
}

private const val STORAGE_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L

/** Progress of moving downloads to another storage. */
data class StorageMigrationState(
    val running: Boolean = false,
    val movedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val doneItems: Int = 0,
    val totalItems: Int = 0,
    val currentTitle: String? = null,
)

sealed interface StorageMoveResult {
    data class Done(val moved: Int, val failed: Int) : StorageMoveResult
    data object ActiveDownloads : StorageMoveResult
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : StorageMoveResult
    data object NotConnected : StorageMoveResult
    data object Busy : StorageMoveResult
}
