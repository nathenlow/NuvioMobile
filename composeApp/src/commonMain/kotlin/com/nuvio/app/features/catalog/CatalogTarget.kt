package com.nuvio.app.features.catalog

import com.nuvio.app.features.library.LibraryHistorySort
import com.nuvio.app.features.library.LibraryHistoryStatus
import com.nuvio.app.features.library.LibrarySortOption
import kotlinx.serialization.Serializable

sealed interface CatalogTarget {
    val contentType: String
    val supportsPagination: Boolean

    data class Addon(
        val manifestUrl: String,
        override val contentType: String,
        val catalogId: String,
        val genre: String? = null,
        override val supportsPagination: Boolean = false,
    ) : CatalogTarget

    data class Library(
        override val contentType: String,
        val sectionType: String,
        val sortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    ) : CatalogTarget {
        override val supportsPagination: Boolean = false
    }

    /** One shelf of the library's History view, opened with its View all (Movie or Series). */
    data class LibraryHistory(
        override val contentType: String,
        val sort: LibraryHistorySort = LibraryHistorySort.RECENT,
        val status: LibraryHistoryStatus = LibraryHistoryStatus.ALL,
    ) : CatalogTarget {
        override val supportsPagination: Boolean = false
    }

    /** One shelf of the library's Downloaded view, opened with its View all (Movie or Series). */
    data class Downloaded(override val contentType: String) : CatalogTarget {
        override val supportsPagination: Boolean = false
    }

    data class Server(
        val connectionId: String,
        val libraryId: String?,
        override val contentType: String,
        val collectionId: String? = null,
    ) : CatalogTarget {
        override val supportsPagination: Boolean = true
    }

    data class CollectionSource(
        val collectionId: String,
        val folderId: String,
        val sourceKey: String,
        override val contentType: String,
        override val supportsPagination: Boolean = false,
    ) : CatalogTarget
}

@Serializable
enum class CatalogTargetKind {
    ADDON,
    LIBRARY,
    COLLECTION_SOURCE,
}
