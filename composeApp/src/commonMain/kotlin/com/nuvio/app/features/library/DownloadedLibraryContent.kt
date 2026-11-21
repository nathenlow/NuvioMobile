package com.nuvio.app.features.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.downloads.DownloadedTitle
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_library_empty_message
import nuvio.composeapp.generated.resources.downloads_library_empty_title
import org.jetbrains.compose.resources.stringResource

/**
 * The "Downloaded" view of the library: the same poster shelves as the saved library, built only
 * from details saved with the downloads, so it works with no network.
 */
internal fun LazyListScope.downloadedLibraryContent(
    titles: List<DownloadedTitle>,
    layoutMode: LibraryLayoutMode,
    columns: Int,
    onTitleClick: (DownloadedTitle) -> Unit,
    onTitleLongClick: (DownloadedTitle) -> Unit,
    onShelfViewAll: ((series: Boolean) -> Unit)? = null,
) {
    if (titles.isEmpty()) {
        item(key = "downloaded-empty") {
            HomeEmptyStateCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = stringResource(Res.string.downloads_library_empty_title),
                message = stringResource(Res.string.downloads_library_empty_message),
            )
        }
        return
    }

    posterTitleContent(
        entries = titles,
        layoutMode = layoutMode,
        columns = columns,
        keyPrefix = "downloaded",
        preview = DownloadedTitle::toMetaPreview,
        isSeries = DownloadedTitle::isSeries,
        onClick = onTitleClick,
        onLongClick = onTitleLongClick,
        onShelfViewAll = onShelfViewAll,
    )
}

internal fun DownloadedTitle.toMetaPreview(): MetaPreview =
    MetaPreview(
        id = metaId,
        type = metaType,
        name = title,
        poster = poster,
        rawPosterUrl = poster,
        banner = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        genres = genres,
        imdbRating = imdbRating,
        runtime = runtime,
    )
