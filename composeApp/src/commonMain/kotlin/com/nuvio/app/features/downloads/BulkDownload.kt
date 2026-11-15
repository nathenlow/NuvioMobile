package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One episode to fetch streams for. */
data class BulkEpisodeRef(
    /** The id streams are requested with. */
    val videoId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val thumbnail: String?,
    val pauseDescription: String?,
)

/** Everything the bulk download page needs to know about the show and the chosen episodes. */
data class BulkDownloadRequest(
    val metaType: String,
    val metaId: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    /** What was asked for, such as "Season 2" or "Unwatched episodes". */
    val scopeLabel: String,
    val episodes: List<BulkEpisodeRef>,
)

enum class BulkFetchStatus {
    /** Queued behind other episodes. */
    Waiting,
    Loading,
    Ready,
    NoStream,
    Failed,

    /** Left alone because the episode is already downloaded; fetched only when asked. */
    Skipped,
}

data class BulkEpisodeState(
    val ref: BulkEpisodeRef,
    val status: BulkFetchStatus = BulkFetchStatus.Waiting,
    /** Every group the last search returned. Kept so switching streams needs no new request. */
    val groups: List<AddonStreamGroup> = emptyList(),
    val selected: StreamItem? = null,
    /** Whether "Download" at the top will include this episode. */
    val included: Boolean = true,
) {
    val streamCount: Int
        get() = groups.sumOf { it.streams.size }
}

/**
 * One bulk download page's state: it asks for streams episode by episode, picks one for each the way
 * auto play would, and keeps every result in memory so a stream can be switched without a new request.
 * Nothing is saved to disk, as addon links may expire.
 */
class BulkDownloadSession(val request: BulkDownloadRequest) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val limiter = Semaphore(permits = MAX_PARALLEL_SEARCHES)
    private val jobs = mutableMapOf<String, Job>()

    private val _episodes = MutableStateFlow(initialEpisodes())
    val episodes: StateFlow<List<BulkEpisodeState>> = _episodes.asStateFlow()

    private fun initialEpisodes(): List<BulkEpisodeState> {
        DownloadsRepository.ensureLoaded()
        val existingKeys = DownloadsRepository.uiState.value.items.mapTo(hashSetOf()) { it.logicalContentKey }
        return request.episodes.map { ref ->
            val downloaded = "${request.metaId.trim()}|${ref.seasonNumber}|${ref.episodeNumber}" in existingKeys
            BulkEpisodeState(
                ref = ref,
                status = if (downloaded) BulkFetchStatus.Skipped else BulkFetchStatus.Waiting,
                included = !downloaded,
            )
        }
    }

    /** Starts fetching every episode that still has to be fetched. Safe to call more than once. */
    fun start() {
        _episodes.value
            .filter { it.status == BulkFetchStatus.Waiting && jobs[it.ref.videoId]?.isActive != true }
            .forEach { fetch(it.ref.videoId, forceRefresh = false) }
    }

    /** Fetches again, bypassing any search held in memory. */
    fun reload(videoId: String) = fetch(videoId, forceRefresh = true)

    fun reloadAll() {
        _episodes.value.forEach { state ->
            if (state.status != BulkFetchStatus.Skipped) fetch(state.ref.videoId, forceRefresh = true)
        }
    }

    private fun fetch(videoId: String, forceRefresh: Boolean) {
        jobs.remove(videoId)?.cancel()
        update(videoId) { it.copy(status = BulkFetchStatus.Waiting) }
        jobs[videoId] = scope.launch {
            limiter.withPermit {
                val ref = _episodes.value.firstOrNull { it.ref.videoId == videoId }?.ref
                    ?: return@withPermit
                update(videoId) { it.copy(status = BulkFetchStatus.Loading) }
                val groups = try {
                    StreamsRepository.fetchStreamGroups(
                        type = request.metaType,
                        videoId = ref.videoId,
                        season = ref.seasonNumber,
                        episode = ref.episodeNumber,
                        forceRefresh = forceRefresh,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    update(videoId) { it.copy(status = BulkFetchStatus.Failed) }
                    return@withPermit
                }
                val picked = autoSelect(groups)
                update(videoId) { current ->
                    // Keep a stream the user picked by hand if it is still offered by this search.
                    val keep = current.selected?.takeIf { previous ->
                        groups.any { group -> previous in group.streams }
                    }
                    val chosen = keep ?: picked
                    current.copy(
                        groups = groups,
                        selected = chosen,
                        status = if (chosen != null) BulkFetchStatus.Ready else BulkFetchStatus.NoStream,
                        included = if (chosen != null) current.included else false,
                    )
                }
            }
        }
    }

    fun select(videoId: String, stream: StreamItem) {
        update(videoId) { it.copy(selected = stream, status = BulkFetchStatus.Ready) }
    }

    fun setIncluded(videoId: String, included: Boolean) {
        update(videoId) { state ->
            if (state.selected == null) state else state.copy(included = included)
        }
    }

    fun setAllIncluded(included: Boolean) {
        _episodes.update { list ->
            list.map { state -> if (state.selected == null) state else state.copy(included = included) }
        }
    }

    /**
     * Queues a download for each of [videoIds] that has a stream picked. Returns how many were queued.
     * Debrid links are resolved first, as the stream screen does for a single download.
     */
    suspend fun download(videoIds: Collection<String>): Int {
        var queued = 0
        // Queue in episode order (specials last) so downloads start from the first episode.
        val ordered = _episodes.value
            .filter { it.ref.videoId in videoIds }
            .sortedWith(
                compareBy<BulkEpisodeState>(
                    { if (it.ref.seasonNumber == 0) Int.MAX_VALUE else it.ref.seasonNumber },
                    { it.ref.episodeNumber },
                ),
            )
        for (state in ordered) {
            val stream = state.selected ?: continue
            val playable = resolvePlayable(state, stream) ?: continue
            val result = DownloadsRepository.enqueueFromStream(
                contentType = request.metaType,
                videoId = state.ref.videoId,
                parentMetaId = request.metaId,
                parentMetaType = request.metaType,
                title = request.title,
                logo = request.logo,
                poster = request.poster,
                background = request.background,
                seasonNumber = state.ref.seasonNumber,
                episodeNumber = state.ref.episodeNumber,
                episodeTitle = state.ref.title,
                episodeThumbnail = state.ref.thumbnail,
                stream = playable,
            )
            if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                queued++
                update(state.ref.videoId) { it.copy(included = false) }
            } else {
                NuvioToastController.show(result.toastMessage())
            }
        }
        return queued
    }

    private suspend fun resolvePlayable(state: BulkEpisodeState, stream: StreamItem): StreamItem? {
        if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) return stream
        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = stream,
            season = state.ref.seasonNumber,
            episode = state.ref.episodeNumber,
        )
        return when (resolved) {
            is DirectDebridPlayableResult.Success -> resolved.stream
            else -> {
                resolved.toastMessage()?.let { NuvioToastController.show(it) }
                null
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun update(videoId: String, transform: (BulkEpisodeState) -> BulkEpisodeState) {
        _episodes.update { list ->
            list.map { state -> if (state.ref.videoId == videoId) transform(state) else state }
        }
    }

    private companion object {
        const val MAX_PARALLEL_SEARCHES = 3
    }
}

/** True when a stream can be turned into a downloadable file link. */
internal fun StreamItem.isBulkDownloadCandidate(): Boolean {
    if (serverTarget != null) return false
    val direct = playableDirectUrl?.trim()?.lowercase()
    if (direct != null) {
        return (direct.startsWith("http://") || direct.startsWith("https://")) &&
            !direct.contains(".m3u8") &&
            !direct.contains(".mpd") &&
            !direct.contains(".torrent")
    }
    return DirectDebridPlaybackResolver.shouldResolveToPlayableStream(this)
}

/** Whether [stream] can be listed as a choice on the switch-stream sheet. */
internal fun AddonStreamGroup.downloadableStreams(): List<StreamItem> =
    streams.filter { it.isBulkDownloadCandidate() }

/**
 * Picks a stream the way auto play would, limited to streams that can be downloaded. A "manual" auto
 * play setting still gets a pick here, since the user can switch it afterwards.
 */
internal fun autoSelect(groups: List<AddonStreamGroup>): StreamItem? {
    val candidates = groups.flatMap { it.downloadableStreams() }
    if (candidates.isEmpty()) return null

    // A cached stream at the preferred resolution wins; without one, the addon ranking below decides.
    DownloadsRepository.ensureLoaded()
    pickPreferredResolution(candidates, DownloadsRepository.preferredResolution.value)?.let { return it }

    PlayerSettingsRepository.ensureLoaded()
    val settings = PlayerSettingsRepository.uiState.value
    val debrid = DebridSettingsRepository.snapshot()
    val installedAddonNames = groups
        .filter { it.addonId.startsWith("addon:") }
        .mapTo(hashSetOf()) { it.addonName }

    fun evaluate(
        mode: StreamAutoPlayMode,
        source: StreamAutoPlaySource,
        addons: Set<String>,
        plugins: Set<String>,
    ): StreamItem? =
        StreamAutoPlaySelector.selectAutoPlayStream(
            streams = candidates,
            mode = mode,
            regexPattern = settings.streamAutoPlayRegex,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = addons,
            selectedPlugins = plugins,
            debridEnabled = debrid.canResolvePlayableLinks,
            activeResolverProviderId = debrid.activeResolverProviderId,
        )

    val preferredMode = settings.streamAutoPlayMode
    val preferred = if (preferredMode == StreamAutoPlayMode.MANUAL) {
        null
    } else {
        evaluate(
            mode = preferredMode,
            source = settings.streamAutoPlaySource,
            addons = settings.streamAutoPlaySelectedAddons,
            plugins = settings.streamAutoPlaySelectedPlugins,
        )
    }
    return preferred
        ?: evaluate(
            mode = StreamAutoPlayMode.FIRST_STREAM,
            source = StreamAutoPlaySource.ALL_SOURCES,
            addons = emptySet(),
            plugins = emptySet(),
        )
        ?: candidates.first()
}

/** Holds the open bulk download pages by launch id, as the stream launch store does. */
object BulkDownloadSessions {
    private var nextId = 1L
    private val sessions = mutableMapOf<Long, BulkDownloadSession>()

    fun put(request: BulkDownloadRequest): Long {
        val id = nextId++
        sessions[id] = BulkDownloadSession(request)
        return id
    }

    fun get(id: Long): BulkDownloadSession? = sessions[id]

    fun remove(id: Long) {
        sessions.remove(id)?.close()
    }
}
