package com.nuvio.app.features.watched

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
private const val POSTER_LOOKUPS_AT_ONCE = 6

/**
 * What the library's History view needs from the Nuvio server's watch history: the full list of
 * titles (to delete them from the account), the posters looked up for them, and the newest of those
 * kept on disk.
 */
object WatchHistoryRepository {
    private val _titleKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Every title in the history, for "is this in my history?" checks. */
    val titleKeys: StateFlow<Set<String>> = _titleKeys.asStateFlow()

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
    private val _artwork = MutableStateFlow<Map<String, MetaPreview>>(emptyMap())
    /** Posters and details looked up for history titles, keyed "type:id". The server stores no artwork. */
    val artwork: StateFlow<Map<String, MetaPreview>> = _artwork.asStateFlow()

    /** Pulls the history from the server; a failed pull keeps what was there. */
    suspend fun refresh() {
        mutex.withLock { pullFromServer() }
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

    private fun WatchedItem.cacheKey() = "$type:$id"
}

/** Whether the title is in the Nuvio watch history (movie/series type aliases are treated alike). */
fun Set<String>.containsWatchHistoryTitle(type: String, id: String): Boolean =
    watchedItemKeys(type = type, id = id).any { it in this }
