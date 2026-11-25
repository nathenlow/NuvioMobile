package com.nuvio.app.features.catalog

import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.catalogRouteKey
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.toDownloadedTitles
import com.nuvio.app.features.library.LIBRARY_GRID_PAGE_SIZE
import com.nuvio.app.features.library.buildLibraryHistory
import com.nuvio.app.features.library.filteredAndSorted
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.watched.WatchHistoryRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.flow.collectLatest
import com.nuvio.app.features.servers.serverMessage
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.filterReleasedItems
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object CatalogRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequest: CatalogRequest? = null
    private val scrollPositions = linkedMapOf<CatalogRequest, CatalogScrollPosition>()

    fun load(
        target: CatalogTarget,
        force: Boolean = false,
    ) {
        val request = catalogRequest(target)
        if (!force && activeRequest == request && (_uiState.value.items.isNotEmpty() || _uiState.value.isLoading)) {
            return
        }
        activeRequest = request
        if (target is CatalogTarget.Library) {
            observeInternalLibrary(request)
            return
        }
        if (target is CatalogTarget.LibraryHistory) {
            observeLibraryHistory(request, target)
            return
        }
        if (target is CatalogTarget.Downloaded) {
            observeDownloaded(request, target)
            return
        }
        fetchPage(
            request = request,
            reset = true,
            forceRefresh = force,
        )
    }

    // How many titles of a History or Downloaded shelf are shown; grows by a page as the grid is scrolled.
    private val shownLimit = MutableStateFlow(LIBRARY_GRID_PAGE_SIZE)

    /** One shelf of the library's History view, kept up to date with what is watched. */
    private fun observeLibraryHistory(request: CatalogRequest, target: CatalogTarget.LibraryHistory) {
        activeJob?.cancel()
        _uiState.value = CatalogUiState(isLoading = true)
        shownLimit.value = LIBRARY_GRID_PAGE_SIZE
        activeJob = scope.launch {
            WatchedRepository.ensureLoaded()
            WatchProgressRepository.ensureLoaded()
            combine(
                WatchedRepository.uiState,
                WatchProgressRepository.uiState,
                WatchHistoryRepository.artwork,
                shownLimit,
            ) { watched, progress, artwork, limit ->
                val all = buildLibraryHistory(watched.items, progress.entries, artwork)
                    .filteredAndSorted(target.sort, target.status, target.contentType)
                all.take(limit) to all.size.takeIf { it > limit }?.let { limit }
            }.collectLatest { (entries, nextSkip) ->
                if (activeRequest != request) return@collectLatest
                _uiState.value = CatalogUiState(
                    items = entries.map { it.preview },
                    isLoading = false,
                    nextSkip = nextSkip,
                )
                // The server keeps no posters; look them up for this page, which updates the list again.
                WatchHistoryRepository.resolveArtwork(
                    entries.filter { it.preview.poster.isNullOrBlank() }
                        .map { Triple(it.preview.type, it.preview.id, it.preview.name) },
                )
            }
        }
    }

    /** One shelf of the library's Downloaded view, from the details saved with the downloads. */
    private fun observeDownloaded(request: CatalogRequest, target: CatalogTarget.Downloaded) {
        activeJob?.cancel()
        _uiState.value = CatalogUiState(isLoading = true)
        shownLimit.value = LIBRARY_GRID_PAGE_SIZE
        activeJob = scope.launch {
            DownloadsRepository.ensureLoaded()
            combine(DownloadsRepository.uiState, shownLimit, ::Pair).collect { (state, limit) ->
                if (activeRequest != request) return@collect
                val wantSeries = target.contentType.equals("series", ignoreCase = true)
                val all = state.items.toDownloadedTitles().filter { it.isSeries == wantSeries }
                _uiState.value = CatalogUiState(
                    items = all.take(limit).map { it.toMetaPreview() },
                    isLoading = false,
                    nextSkip = limit.takeIf { all.size > limit },
                )
            }
        }
    }

    fun loadMore() {
        val request = activeRequest ?: return
        val current = _uiState.value
        if (current.isLoading || current.nextSkip == null) return
        if (request.target is CatalogTarget.LibraryHistory || request.target is CatalogTarget.Downloaded) {
            shownLimit.value += LIBRARY_GRID_PAGE_SIZE
            return
        }
        fetchPage(
            request = request,
            reset = false,
            forceRefresh = false,
        )
    }

    fun clear() {
        activeJob?.cancel()
        activeRequest = null
        scrollPositions.clear()
        _uiState.value = CatalogUiState()
    }

    fun scrollPosition(
        target: CatalogTarget,
    ): CatalogScrollPosition =
        scrollPositions[catalogRequest(target)]
            ?: CatalogScrollPosition()

    fun saveScrollPosition(
        target: CatalogTarget,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        val request = catalogRequest(target)
        scrollPositions[request] = CatalogScrollPosition(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        )
    }

    private fun observeInternalLibrary(request: CatalogRequest) {
        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
        )

        activeJob = scope.launch {
            try {
                val target = request.target as CatalogTarget.Library
                LibraryRepository.ensureLoaded()
                LibraryRepository.uiState.libraryCatalogStates(
                    target, LibraryRepository.uiState.libraryCatalogOrders(target),
                ).collect { state ->
                    if (activeRequest != request) return@collect
                    CustomPosterUrlRepository.ensureLoaded()
                    val posterPattern = CustomPosterUrlRepository.patternForScreen(
                        com.nuvio.app.core.poster.CustomPosterScreen.LIBRARY
                    )
                    _uiState.value = state.copy(
                        items = state.items.withCustomPosterUrls(posterPattern)
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (activeRequest != request) return@launch
                _uiState.value = CatalogUiState(
                    errorMessage = error.serverMessage() ?: error.message ?: getString(Res.string.catalog_load_failed),
                )
            }
        }
    }

    private fun fetchPage(
        request: CatalogRequest,
        reset: Boolean,
        forceRefresh: Boolean,
    ) {
        activeJob?.cancel()
        val current = _uiState.value
        val requestedSkip = if (reset) 0 else current.nextSkip ?: return

        _uiState.value = current.copy(
            items = if (reset) emptyList() else current.items,
            isLoading = true,
            nextSkip = if (reset) null else current.nextSkip,
            errorMessage = null,
        )

        activeJob = scope.launch {
            runCatching {
                when (val target = request.target) {
                    is CatalogTarget.Addon -> fetchCatalogPage(
                        manifestUrl = target.manifestUrl,
                        type = target.contentType,
                        catalogId = target.catalogId,
                        genre = target.genre,
                        skip = requestedSkip.takeIf { it > 0 },
                        forceRefresh = forceRefresh,
                    )

                    is CatalogTarget.CollectionSource -> fetchCollectionSourcePage(
                        target = target,
                        page = requestedSkip.takeIf { it > 0 } ?: 1,
                    )

                    is CatalogTarget.Server -> ServerCatalog.page(target, skip = requestedSkip)

                    is CatalogTarget.Library,
                    is CatalogTarget.LibraryHistory, is CatalogTarget.Downloaded -> error(getString(Res.string.catalog_load_failed))
                }.withUnreleasedFilter(request.hideUnreleasedContent)
            }.fold(
                onSuccess = { page ->
                    if (activeRequest != request) return@fold

                    val mergedItems = if (reset) {
                        dedupeCatalogItems(page.items)
                    } else {
                        mergeCatalogItems(_uiState.value.items, page.items)
                    }
                    val supportsPagination = request.target.supportsPagination || page.rawItemCount >= CATALOG_PAGE_SIZE
                    val loadedNewItems = reset || mergedItems.size > current.items.size
                    val paginationState = nextCatalogPaginationState(
                        supportsPagination = supportsPagination,
                        requestedSkip = requestedSkip,
                        page = page,
                        loadedNewItems = loadedNewItems,
                        consecutiveDuplicatePages = if (reset) 0 else current.consecutiveDuplicatePages,
                    )
                    CustomPosterUrlRepository.ensureLoaded()
                    val posterScreen = when (request.target) {
                        is CatalogTarget.Library -> com.nuvio.app.core.poster.CustomPosterScreen.LIBRARY
                        is CatalogTarget.CollectionSource -> com.nuvio.app.core.poster.CustomPosterScreen.COLLECTIONS
                        else -> com.nuvio.app.core.poster.CustomPosterScreen.HOME
                    }
                    val posterPattern = CustomPosterUrlRepository.patternForScreen(posterScreen)
                    _uiState.value = CatalogUiState(
                        items = mergedItems.withCustomPosterUrls(posterPattern),
                        isLoading = false,
                        nextSkip = paginationState.nextSkip,
                        consecutiveDuplicatePages = paginationState.consecutiveDuplicatePages,
                        errorMessage = null,
                    )
                },
                onFailure = { error ->
                    if (activeRequest != request) return@fold

                    _uiState.value = current.copy(
                        items = if (reset) emptyList() else current.items,
                        isLoading = false,
                        nextSkip = null,
                        errorMessage = error.serverMessage() ?: error.message ?: getString(Res.string.catalog_load_failed),
                    )
                },
            )
        }
    }

    private fun catalogRequest(target: CatalogTarget): CatalogRequest =
        CatalogRequest(
            target = target,
            hideUnreleasedContent = HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent,
        )
}

private fun CatalogPage.withUnreleasedFilter(hideUnreleasedContent: Boolean): CatalogPage {
    if (!hideUnreleasedContent) return this
    val filteredItems = items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
    return if (filteredItems.size == items.size) this else copy(items = filteredItems)
}

private suspend fun fetchCollectionSourcePage(
    target: CatalogTarget.CollectionSource,
    page: Int,
): CatalogPage {
    CollectionRepository.initialize()
    val source = CollectionRepository.getCollection(target.collectionId)
        ?.folders
        ?.firstOrNull { it.id == target.folderId }
        ?.resolvedSources
        ?.firstOrNull { it.catalogRouteKey() == target.sourceKey }
        ?: error(getString(Res.string.catalog_load_failed))

    return when {
        source.isTmdb -> TmdbCollectionSourceResolver.resolve(source = source, page = page)
        source.isTrakt -> TraktPublicListSourceResolver.resolve(source = source, page = page)
        else -> error(getString(Res.string.catalog_load_failed))
    }
}

private data class CatalogRequest(
    val target: CatalogTarget,
    val hideUnreleasedContent: Boolean,
)

