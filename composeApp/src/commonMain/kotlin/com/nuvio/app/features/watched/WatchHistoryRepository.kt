package com.nuvio.app.features.watched

import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.profiles.MAX_PROFILES
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watching.sync.SupabaseWatchedSyncAdapter
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val PULL_PAGE_SIZE = 500
private const val HISTORY_PAGE_SIZE = 30
private const val POSTER_LOOKUPS_AT_ONCE = 6

/**
 * The watch history stored on the Nuvio server, newest first, one entry per title.
 * Titles are shown a page at a time: [items] holds the pages loaded so far and [hasMore] says
 * whether older titles remain for [loadMore].
 */
object WatchHistoryRepository {
    private val _items = MutableStateFlow<List<MetaPreview>>(emptyList())
    val items: StateFlow<List<MetaPreview>> = _items.asStateFlow()
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _titleKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Every title in the history (not just the pages loaded), for "is this in my history?" checks. */
    val titleKeys: StateFlow<Set<String>> = _titleKeys.asStateFlow()

    private val _removed = MutableSharedFlow<MetaPreview>(extraBufferCapacity = 8)
    /** Emits each title taken out of the history, so screens holding their own copy can drop it. */
    val removed: SharedFlow<MetaPreview> = _removed.asSharedFlow()

    private val artworkJson = Json { ignoreUnknownKeys = true }
    private var artworkProfileId: Int? = null
    private var lastSavedArtwork: String? = null

    /** What is kept on disk for one title: just enough to draw its poster card before any lookup. */
    @Serializable
    private data class CachedArtwork(
        val type: String,
        val id: String,
        val name: String,
        val poster: String? = null,
        val releaseInfo: String? = null,
    )

    private val mutex = Mutex()
    private val posterLookups = Semaphore(POSTER_LOOKUPS_AT_ONCE)
    // Outlives any dialog or screen, so a delete is not cancelled when its dialog closes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var serverItems: List<WatchedItem> = emptyList()
    private var visibleCount = HISTORY_PAGE_SIZE
    private val _artwork = MutableStateFlow<Map<String, MetaPreview>>(emptyMap())
    /** Posters and details looked up for history titles, keyed "type:id". The server stores no artwork. */
    val artwork: StateFlow<Map<String, MetaPreview>> = _artwork.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        if (pullFromServer()) publish()
    }

    /** Loads the whole history from the server; returns false (keeping what we had) when that fails. */
    private suspend fun pullFromServer(): Boolean {
        val pulled = try {
            SupabaseWatchedSyncAdapter.pull(ProfileRepository.activeProfileId, PULL_PAGE_SIZE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return false
        }
        setServerItems(pulled)
        return true
    }

    private fun setServerItems(items: List<WatchedItem>) {
        serverItems = items
        _titleKeys.value = titles().mapTo(mutableSetOf()) { watchedItemKey(type = it.type, id = it.id) }
    }

    /**
     * Brings back the posters kept on disk for the active profile, so the history draws at once and
     * the lookups only run for titles that were not kept. Safe to call often.
     */
    fun loadCachedArtwork() {
        val profileId = ProfileRepository.activeProfileId
        if (artworkProfileId == profileId) return
        artworkProfileId = profileId
        lastSavedArtwork = null
        val stored = WatchHistoryArtworkStorage.load(profileId) ?: return
        val kept = runCatching { artworkJson.decodeFromString<List<CachedArtwork>>(stored) }.getOrNull() ?: return
        lastSavedArtwork = stored
        // A poster looked up in this session is newer than the one on disk.
        _artwork.value = kept.associate {
            "${it.type}:${it.id}" to MetaPreview(id = it.id, type = it.type, name = it.name, poster = it.poster, releaseInfo = it.releaseInfo)
        } + _artwork.value
    }

    /**
     * Keeps on disk the posters of [recent] titles (type, id), the newest first, and nothing else: a
     * title that falls out of the recent ones, or leaves the history, is dropped from the cache.
     */
    fun persistRecentArtwork(recent: List<Pair<String, String>>) {
        loadCachedArtwork()
        val artwork = _artwork.value
        val kept = recent.mapNotNull { (type, id) ->
            artwork["$type:$id"]?.takeUnless { it.poster.isNullOrBlank() }
        }.map { CachedArtwork(it.type, it.id, it.name, it.poster, it.releaseInfo) }
        val profileId = ProfileRepository.activeProfileId
        if (kept.isEmpty()) {
            if (lastSavedArtwork != null) WatchHistoryArtworkStorage.remove(profileId)
            lastSavedArtwork = null
            return
        }
        val payload = artworkJson.encodeToString(kept)
        if (payload == lastSavedArtwork) return
        lastSavedArtwork = payload
        WatchHistoryArtworkStorage.save(profileId, payload)
    }

    /** Forgets every saved poster, when the account's local data is wiped. */
    fun clearLocalState() {
        for (profileId in 1..MAX_PROFILES) WatchHistoryArtworkStorage.remove(profileId)
        artworkProfileId = null
        lastSavedArtwork = null
        _artwork.value = emptyMap()
    }

    /** One page of titles by offset, for callers that page themselves (collection folders). */
    suspend fun page(skip: Int, limit: Int = HISTORY_PAGE_SIZE): CatalogPage = mutex.withLock {
        if (skip == 0 && !pullFromServer() && serverItems.isEmpty()) error("Watch history could not be loaded")
        val titles = titles()
        val slice = titles.drop(skip).take(limit)
        resolvePreviews(slice)
        CatalogPage(
            items = slice.map(::preview),
            rawItemCount = slice.size,
            nextSkip = (skip + slice.size).takeIf { it < titles.size },
        )
    }

    fun loadMore() {
        scope.launch {
            mutex.withLock {
                if (!_hasMore.value) return@withLock
                visibleCount += HISTORY_PAGE_SIZE
                publish()
            }
        }
    }

    fun requestRemove(meta: MetaPreview) {
        scope.launch { remove(meta) }
    }

    /** Removes the title and all its watched episodes from the server history. */
    private suspend fun remove(meta: MetaPreview) = mutex.withLock {
        val aliases = watchedItemTypeAliases(meta.type)
        val matches = serverItems.filter { it.id == meta.id && it.type in aliases }
        if (matches.isNotEmpty()) {
            try {
                SupabaseWatchedSyncAdapter.delete(ProfileRepository.activeProfileId, matches)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                return@withLock
            }
        }
        // A title can also be here only through a playback that was not finished (Continue Watching).
        val local = WatchedRepository.uiState.value.items.filter { it.id == meta.id && it.type in aliases }
        WatchedRepository.unmarkWatchedLocally((matches + local).distinct())
        WatchProgressRepository.removeProgress(meta.id)
        setServerItems(serverItems - matches.toSet())
        _removed.emit(meta)
        publish()
    }

    private suspend fun publish() {
        val titles = titles()
        val shown = titles.take(visibleCount)
        _hasMore.value = titles.size > shown.size
        _items.value = shown.map(::preview)
        if (resolvePreviews(shown)) _items.value = shown.map(::preview)
    }

    /** One entry per title, newest first. */
    private fun titles(): List<WatchedItem> =
        serverItems.sortedByDescending { it.markedAtEpochMs }
            .distinctBy { watchedItemKey(type = it.type, id = it.id) }

    /** Looks up artwork for [titles] (type, id, name) not seen before, a few at a time. */
    suspend fun resolveArtwork(titles: List<Triple<String, String, String>>) {
        resolvePreviews(titles.map { (type, id, name) -> WatchedItem(id = id, type = type, name = name, markedAtEpochMs = 0L) })
    }

    /** Looks up artwork for titles not seen before; returns whether anything new was found. */
    private suspend fun resolvePreviews(shown: List<WatchedItem>): Boolean {
        val missing = shown.filter { it.cacheKey() !in _artwork.value }
        if (missing.isEmpty()) return false
        // The server stores no artwork, so look each new title up once, a few at a time.
        val found = coroutineScope {
            missing.map { item ->
                async { posterLookups.withPermit { MetaDetailsRepository.fetch(item.type, item.id)?.let { item to it } } }
            }.awaitAll()
        }.filterNotNull()
        _artwork.value = _artwork.value + found.associate { (item, meta) ->
            item.cacheKey() to MetaPreview(
                id = item.id,
                type = item.type,
                name = meta.name.ifBlank { item.name },
                poster = meta.poster,
                releaseInfo = meta.releaseInfo,
            )
        }
        return found.isNotEmpty()
    }

    private fun preview(item: WatchedItem): MetaPreview =
        _artwork.value[item.cacheKey()] ?: MetaPreview(
            id = item.id,
            type = item.type,
            name = item.name,
            poster = item.poster,
            releaseInfo = item.releaseInfo,
        )

    private fun WatchedItem.cacheKey() = "$type:$id"
}

/** Whether the title is in the Nuvio watch history (movie/series type aliases are treated alike). */
fun Set<String>.containsWatchHistoryTitle(type: String, id: String): Boolean =
    watchedItemKeys(type = type, id = id).any { it in this }
