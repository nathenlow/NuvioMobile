package com.nuvio.app.features.library

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryHistoryTest {

    private fun watched(id: String, type: String, name: String, at: Long, season: Int? = null, episode: Int? = null) =
        WatchedItem(id = id, type = type, name = name, season = season, episode = episode, markedAtEpochMs = at)

    private fun progress(id: String, type: String, name: String, at: Long, position: Long, completed: Boolean = false) =
        WatchProgressEntry(
            contentType = type,
            parentMetaId = id,
            parentMetaType = type,
            videoId = "$id:1:1",
            title = name,
            poster = "poster-$id",
            lastPositionMs = position,
            durationMs = 3_600_000L,
            lastUpdatedEpochMs = at,
            isCompleted = completed,
        )

    @Test
    fun everyWatchedEpisodeOfATitleBecomesOneEntryDatedByTheLatest() {
        val history = buildLibraryHistory(
            watched = listOf(
                watched("tt1", "series", "Show", at = 100, season = 1, episode = 1),
                watched("tt1", "series", "Show", at = 300, season = 1, episode = 2),
                watched("tt2", "movie", "Film", at = 200),
            ),
            progress = emptyList(),
            artwork = emptyMap(),
        )

        assertEquals(2, history.size)
        assertEquals(300L, history.first { it.preview.id == "tt1" }.lastWatchedAtMs)
    }

    @Test
    fun anUnfinishedPlaybackIsInTheHistoryAndMarkedInProgress() {
        val history = buildLibraryHistory(
            watched = listOf(watched("tt1", "series", "Show", at = 100)),
            progress = listOf(
                progress("tt1", "series", "Show", at = 500, position = 120_000L),
                progress("tt3", "movie", "Half seen", at = 400, position = 60_000L),
            ),
            artwork = emptyMap(),
        )

        assertEquals(2, history.size)
        assertTrue(history.first { it.preview.id == "tt1" }.inProgress)
        assertEquals(500L, history.first { it.preview.id == "tt1" }.lastWatchedAtMs)
        assertEquals("poster-tt3", history.first { it.preview.id == "tt3" }.preview.poster)
    }

    @Test
    fun aFinishedPlaybackIsNotInProgress() {
        val history = buildLibraryHistory(
            watched = emptyList(),
            progress = listOf(progress("tt1", "series", "Show", at = 100, position = 3_500_000L, completed = true)),
            artwork = emptyMap(),
        )

        assertEquals(false, history.single().inProgress)
    }

    @Test
    fun lookedUpArtworkReplacesAMissingPoster() {
        val history = buildLibraryHistory(
            watched = listOf(watched("tt1", "movie", "Film", at = 100)),
            progress = emptyList(),
            artwork = mapOf("movie:tt1" to MetaPreview(id = "tt1", type = "movie", name = "Film", poster = "found")),
        )

        assertEquals("found", history.single().preview.poster)
    }

    @Test
    fun theNewestTitlesOfEachShelfAreTheOnesKept() {
        val history = buildLibraryHistory(
            watched = (1..5).map { watched("m$it", "movie", "Movie $it", at = it.toLong()) } +
                (1..5).map { watched("s$it", "series", "Series $it", at = 100L + it) },
            progress = emptyList(),
            artwork = emptyMap(),
        )

        assertEquals(
            listOf("movie" to "m5", "movie" to "m4", "series" to "s5", "series" to "s4"),
            history.recentTitles(perShelf = 2),
        )
    }

    @Test
    fun sortsAndFiltersByStatusAndType() {
        val history = buildLibraryHistory(
            watched = listOf(
                watched("a", "movie", "Beta", at = 100),
                watched("b", "series", "Alpha", at = 300),
            ),
            progress = listOf(progress("c", "series", "Gamma", at = 200, position = 1_000L)),
            artwork = emptyMap(),
        )

        assertEquals(
            listOf("b", "c", "a"),
            history.filteredAndSorted(LibraryHistorySort.RECENT, LibraryHistoryStatus.ALL, null).map { it.preview.id },
        )
        assertEquals(
            listOf("a", "c", "b"),
            history.filteredAndSorted(LibraryHistorySort.OLDEST, LibraryHistoryStatus.ALL, null).map { it.preview.id },
        )
        assertEquals(
            listOf("b", "a", "c"),
            history.filteredAndSorted(LibraryHistorySort.TITLE_ASC, LibraryHistoryStatus.ALL, null).map { it.preview.id },
        )
        assertEquals(
            listOf("c", "a", "b"),
            history.filteredAndSorted(LibraryHistorySort.TITLE_DESC, LibraryHistoryStatus.ALL, null).map { it.preview.id },
        )
        assertEquals(
            listOf("c"),
            history.filteredAndSorted(LibraryHistorySort.RECENT, LibraryHistoryStatus.IN_PROGRESS, null).map { it.preview.id },
        )
        assertEquals(
            listOf("a"),
            history.filteredAndSorted(LibraryHistorySort.RECENT, LibraryHistoryStatus.COMPLETED, "movie").map { it.preview.id },
        )
    }
}
