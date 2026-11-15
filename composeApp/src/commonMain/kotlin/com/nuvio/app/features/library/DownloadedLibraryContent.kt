package com.nuvio.app.features.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.features.downloads.DownloadedTitle
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomePosterCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_library_empty_message
import nuvio.composeapp.generated.resources.downloads_library_empty_title
import nuvio.composeapp.generated.resources.downloads_section_movies
import nuvio.composeapp.generated.resources.downloads_section_shows
import org.jetbrains.compose.resources.stringResource

/**
 * The "Downloaded" view of the library: the same poster shelves as the saved library, built only
 * from details saved with the downloads, so it works with no network.
 */
internal fun LazyListScope.downloadedLibraryContent(
    titles: List<DownloadedTitle>,
    onTitleClick: (DownloadedTitle) -> Unit,
    onTitleLongClick: (DownloadedTitle) -> Unit,
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

    val shows = titles.filter { it.isSeries }
    val movies = titles.filterNot { it.isSeries }
    if (shows.isNotEmpty()) {
        item(key = "downloaded-shows") {
            DownloadedShelf(
                title = stringResource(Res.string.downloads_section_shows),
                titles = shows,
                onTitleClick = onTitleClick,
                onTitleLongClick = onTitleLongClick,
            )
        }
    }
    if (movies.isNotEmpty()) {
        item(key = "downloaded-movies") {
            DownloadedShelf(
                title = stringResource(Res.string.downloads_section_movies),
                titles = movies,
                onTitleClick = onTitleClick,
                onTitleLongClick = onTitleLongClick,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun DownloadedShelf(
    title: String,
    titles: List<DownloadedTitle>,
    onTitleClick: (DownloadedTitle) -> Unit,
    onTitleLongClick: (DownloadedTitle) -> Unit,
) {
    NuvioShelfSection(
        title = title,
        entries = titles,
        headerHorizontalPadding = 16.dp,
        rowContentPadding = PaddingValues(horizontal = 16.dp),
        viewAllPillSize = NuvioViewAllPillSize.Compact,
        key = { it.metaId },
    ) { entry ->
        HomePosterCard(
            item = entry.toMetaPreview(),
            onClick = { onTitleClick(entry) },
            onLongClick = { onTitleLongClick(entry) },
        )
    }
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
