package com.nuvio.app.features.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.PosterGridRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.media_movie
import nuvio.composeapp.generated.resources.media_series
import org.jetbrains.compose.resources.stringResource

/**
 * Titles as the library shows them: a Series shelf and a Movie shelf, or one grid of everything.
 * The Downloaded and History views of the library both use it.
 */
internal fun <T> LazyListScope.posterTitleContent(
    entries: List<T>,
    layoutMode: LibraryLayoutMode,
    columns: Int,
    keyPrefix: String,
    preview: (T) -> MetaPreview,
    isSeries: (T) -> Boolean,
    onClick: (T) -> Unit,
    onLongClick: (T) -> Unit,
    /** Opens the full list of a shelf (true for Series, false for Movie); shown past the preview limit. */
    onShelfViewAll: ((series: Boolean) -> Unit)? = null,
    /** Called when the grid is scrolled to its end, to show the next page. Null when everything is shown. */
    onLoadMore: (() -> Unit)? = null,
) {
    if (layoutMode == LibraryLayoutMode.VERTICAL) {
        val byKey = entries.associateBy { preview(it).let { meta -> meta.type to meta.id } }
        val resolve: (MetaPreview) -> T? = { meta -> byKey[meta.type to meta.id] }
        items(
            items = entries.chunked(columns),
            key = { row -> preview(row.first()).let { "$keyPrefix-grid:${it.type}:${it.id}" } },
        ) { row ->
            PosterGridRow(
                items = row.map(preview),
                columns = columns,
                modifier = Modifier.padding(horizontal = 16.dp),
                onPosterClick = { meta -> resolve(meta)?.let(onClick) },
                onPosterLongClick = { meta -> resolve(meta)?.let(onLongClick) },
            )
        }
        if (onLoadMore != null) {
            item(key = "$keyPrefix-load-more") {
                // Composed only when the end of the grid is reached.
                LaunchedEffect(entries.size) { onLoadMore() }
                Spacer(Modifier.height(24.dp))
            }
        }
        return
    }

    val movies = entries.filterNot(isSeries)
    val series = entries.filter(isSeries)
    // Movie first, then Series, as the saved library lists them.
    if (movies.isNotEmpty()) {
        item(key = "$keyPrefix-movies") {
            PosterTitleShelf(
                title = stringResource(Res.string.media_movie),
                entries = movies,
                preview = preview,
                onClick = onClick,
                onLongClick = onLongClick,
                onViewAllClick = onShelfViewAll?.let { open -> { open(false) } },
            )
        }
    }
    if (series.isNotEmpty()) {
        item(key = "$keyPrefix-series") {
            PosterTitleShelf(
                title = stringResource(Res.string.media_series),
                entries = series,
                preview = preview,
                onClick = onClick,
                onLongClick = onLongClick,
                onViewAllClick = onShelfViewAll?.let { open -> { open(true) } },
            )
        }
    }
}

/** How many titles a library shelf shows before "View all" takes over. */
internal const val LIBRARY_SECTION_PREVIEW_LIMIT = 18

/** How many titles a grid shows at a time; more are added as it is scrolled. */
internal const val LIBRARY_GRID_PAGE_SIZE = 30

@Composable
private fun <T> PosterTitleShelf(
    title: String,
    entries: List<T>,
    preview: (T) -> MetaPreview,
    onClick: (T) -> Unit,
    onLongClick: (T) -> Unit,
    onViewAllClick: (() -> Unit)?,
) {
    NuvioShelfSection(
        title = title,
        entries = entries.take(LIBRARY_SECTION_PREVIEW_LIMIT),
        onViewAllClick = onViewAllClick.takeIf { entries.size > LIBRARY_SECTION_PREVIEW_LIMIT },
        headerHorizontalPadding = 16.dp,
        rowContentPadding = PaddingValues(horizontal = 16.dp),
        viewAllPillSize = NuvioViewAllPillSize.Compact,
        key = { entry -> preview(entry).let { "${it.type}:${it.id}" } },
    ) { entry ->
        HomePosterCard(
            item = preview(entry),
            onClick = { onClick(entry) },
            onLongClick = { onLongClick(entry) },
        )
    }
}
