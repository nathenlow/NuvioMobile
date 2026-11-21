package com.nuvio.app.features.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_filter_all_types
import nuvio.composeapp.generated.resources.library_filter_sort
import nuvio.composeapp.generated.resources.library_filter_type
import nuvio.composeapp.generated.resources.library_history_empty_message
import nuvio.composeapp.generated.resources.library_history_empty_title
import nuvio.composeapp.generated.resources.library_history_filter_status
import nuvio.composeapp.generated.resources.library_history_sort_oldest
import nuvio.composeapp.generated.resources.library_history_sort_recent
import nuvio.composeapp.generated.resources.library_history_status_in_progress
import nuvio.composeapp.generated.resources.library_sort_title_asc
import nuvio.composeapp.generated.resources.library_sort_title_desc
import nuvio.composeapp.generated.resources.library_watched_filter_all
import nuvio.composeapp.generated.resources.library_watched_filter_watched
import org.jetbrains.compose.resources.stringResource

/** One title in the watch history: when it was last watched and whether something is unfinished. */
internal data class LibraryHistoryEntry(
    val preview: MetaPreview,
    val lastWatchedAtMs: Long,
    /** A playback was started and not finished, so the title is also in Continue Watching. */
    val inProgress: Boolean,
) {
    val isSeries: Boolean get() = !preview.type.equals("movie", ignoreCase = true)
}

enum class LibraryHistorySort { RECENT, OLDEST, TITLE_ASC, TITLE_DESC }

enum class LibraryHistoryStatus { ALL, COMPLETED, IN_PROGRESS }

/**
 * The titles that were watched: every watched episode or movie, and every playback that was started
 * but not finished (what Continue Watching lists), one entry per title. [artwork] holds posters
 * looked up for titles that came with none.
 */
internal fun buildLibraryHistory(
    watched: List<WatchedItem>,
    progress: List<WatchProgressEntry>,
    artwork: Map<String, MetaPreview>,
): List<LibraryHistoryEntry> {
    class Draft(var preview: MetaPreview, var lastWatchedAtMs: Long, var inProgress: Boolean)

    val drafts = linkedMapOf<String, Draft>()
    fun draftFor(type: String, id: String, fallback: MetaPreview): Draft =
        drafts.getOrPut(watchedItemKey(type = type, id = id)) {
            val found = artwork["$type:$id"]
            Draft(
                preview = found?.copy(name = found.name.ifBlank { fallback.name }) ?: fallback,
                lastWatchedAtMs = 0L,
                inProgress = false,
            )
        }

    watched.forEach { item ->
        val draft = draftFor(
            type = item.type,
            id = item.id,
            fallback = MetaPreview(
                id = item.id,
                type = item.type,
                name = item.name,
                poster = item.poster,
                releaseInfo = item.releaseInfo,
            ),
        )
        draft.lastWatchedAtMs = maxOf(draft.lastWatchedAtMs, item.markedAtEpochMs)
    }
    progress.forEach { entry ->
        val draft = draftFor(
            type = entry.parentMetaType,
            id = entry.parentMetaId,
            fallback = MetaPreview(
                id = entry.parentMetaId,
                type = entry.parentMetaType,
                name = entry.title,
                poster = entry.poster,
                banner = entry.background,
                logo = entry.logo,
            ),
        )
        draft.lastWatchedAtMs = maxOf(draft.lastWatchedAtMs, entry.lastUpdatedEpochMs)
        if (!entry.isCompleted && entry.lastPositionMs > 0L) draft.inProgress = true
        // A title that was watched before has no poster of its own; Continue Watching does.
        if (draft.preview.poster.isNullOrBlank() && !entry.poster.isNullOrBlank()) {
            draft.preview = draft.preview.copy(poster = entry.poster)
        }
    }
    return drafts.values.map { LibraryHistoryEntry(it.preview, it.lastWatchedAtMs, it.inProgress) }
}

/** The history with the type and status filters applied, in the chosen order. */
internal fun List<LibraryHistoryEntry>.filteredAndSorted(
    sort: LibraryHistorySort,
    status: LibraryHistoryStatus,
    type: String?,
): List<LibraryHistoryEntry> {
    val filtered = filter { entry ->
        val statusMatches = when (status) {
            LibraryHistoryStatus.ALL -> true
            LibraryHistoryStatus.COMPLETED -> !entry.inProgress
            LibraryHistoryStatus.IN_PROGRESS -> entry.inProgress
        }
        val typeMatches = when (type) {
            null -> true
            "movie" -> !entry.isSeries
            else -> entry.isSeries
        }
        statusMatches && typeMatches
    }
    return when (sort) {
        LibraryHistorySort.RECENT -> filtered.sortedByDescending { it.lastWatchedAtMs }
        LibraryHistorySort.OLDEST -> filtered.sortedBy { it.lastWatchedAtMs }
        LibraryHistorySort.TITLE_ASC -> filtered.sortedBy { it.preview.name.lowercase() }
        LibraryHistorySort.TITLE_DESC -> filtered.sortedByDescending { it.preview.name.lowercase() }
    }
}

@Composable
private fun LibraryHistorySort.label(): String = stringResource(
    when (this) {
        LibraryHistorySort.RECENT -> Res.string.library_history_sort_recent
        LibraryHistorySort.OLDEST -> Res.string.library_history_sort_oldest
        LibraryHistorySort.TITLE_ASC -> Res.string.library_sort_title_asc
        LibraryHistorySort.TITLE_DESC -> Res.string.library_sort_title_desc
    },
)

@Composable
private fun LibraryHistoryStatus.label(): String = stringResource(
    when (this) {
        LibraryHistoryStatus.ALL -> Res.string.library_watched_filter_all
        LibraryHistoryStatus.COMPLETED -> Res.string.library_watched_filter_watched
        LibraryHistoryStatus.IN_PROGRESS -> Res.string.library_history_status_in_progress
    },
)

/** Sort, type and status, as chips like the saved library's. */
@Composable
internal fun LibraryHistoryControls(
    sort: LibraryHistorySort,
    status: LibraryHistoryStatus,
    type: String?,
    onSortSelected: (LibraryHistorySort) -> Unit,
    onStatusSelected: (LibraryHistoryStatus) -> Unit,
    onTypeSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allTypesLabel = stringResource(Res.string.library_filter_all_types)
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_sort),
            label = sort.label(),
            selectedKey = sort.name,
            options = LibraryHistorySort.entries.map { NuvioDropdownOption(key = it.name, label = it.label()) },
            onSelected = { option ->
                LibraryHistorySort.entries.firstOrNull { it.name == option.key }?.let(onSortSelected)
            },
        )
        NuvioDropdownChip(
            title = stringResource(Res.string.library_history_filter_status),
            label = status.label(),
            selectedKey = status.name,
            options = LibraryHistoryStatus.entries.map { NuvioDropdownOption(key = it.name, label = it.label()) },
            onSelected = { option ->
                LibraryHistoryStatus.entries.firstOrNull { it.name == option.key }?.let(onStatusSelected)
            },
        )
        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_type),
            label = type?.let(::localizedMediaTypeLabel) ?: allTypesLabel,
            selectedKey = type.orEmpty(),
            options = listOf(
                NuvioDropdownOption(key = "", label = allTypesLabel),
                NuvioDropdownOption(key = "movie", label = localizedMediaTypeLabel("movie")),
                NuvioDropdownOption(key = "series", label = localizedMediaTypeLabel("series")),
            ),
            onSelected = { option -> onTypeSelected(option.key.ifBlank { null }) },
        )
    }
}

/** The "History" view of the library: shelves or a grid of what was watched. */
internal fun LazyListScope.libraryHistoryContent(
    entries: List<LibraryHistoryEntry>,
    layoutMode: LibraryLayoutMode,
    columns: Int,
    onTitleClick: (MetaPreview) -> Unit,
    onTitleLongClick: (MetaPreview) -> Unit,
    onShelfViewAll: ((series: Boolean) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    watchedKeys: Set<String> = emptySet(),
    fullyWatchedSeriesKeys: Set<String> = emptySet(),
) {
    if (entries.isEmpty()) {
        item(key = "history-empty") {
            HomeEmptyStateCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = stringResource(Res.string.library_history_empty_title),
                message = stringResource(Res.string.library_history_empty_message),
            )
        }
        return
    }
    posterTitleContent(
        entries = entries,
        layoutMode = layoutMode,
        columns = columns,
        keyPrefix = "history",
        preview = LibraryHistoryEntry::preview,
        isSeries = LibraryHistoryEntry::isSeries,
        onClick = { onTitleClick(it.preview) },
        onLongClick = { onTitleLongClick(it.preview) },
        onShelfViewAll = onShelfViewAll,
        onLoadMore = onLoadMore,
        watchedKeys = watchedKeys,
        fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
    )
}
