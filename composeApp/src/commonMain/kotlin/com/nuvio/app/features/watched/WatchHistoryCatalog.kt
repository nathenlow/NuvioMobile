package com.nuvio.app.features.watched

import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.collection.AvailableCatalog
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.home.HomeCatalogDefinition
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_history_source
import nuvio.composeapp.generated.resources.watch_history_source_group
import nuvio.composeapp.generated.resources.watch_history_title
import org.jetbrains.compose.resources.getString

/** A built-in Home catalog listing what was watched. It is hidden until the user turns it on. */
const val WATCH_HISTORY_CATALOG_KEY = "nuvio:history:watched"

/** How a collection folder refers to the watch history: a reserved catalog of the built-in "nuvio" source. */
const val WATCH_HISTORY_SOURCE_ADDON_ID = "nuvio"
const val WATCH_HISTORY_SOURCE_CATALOG_ID = "history"
const val WATCH_HISTORY_SOURCE_TYPE = "history"

fun CollectionSource.isWatchHistory(): Boolean =
    !isTmdb && !isTrakt &&
        addonId == WATCH_HISTORY_SOURCE_ADDON_ID && catalogId == WATCH_HISTORY_SOURCE_CATALOG_ID

fun watchHistoryTitle(): String = runBlocking { getString(Res.string.watch_history_title) }

/** The entry offered in the collection editor's catalog picker. */
fun watchHistoryAvailableCatalog() = AvailableCatalog(
    addonId = WATCH_HISTORY_SOURCE_ADDON_ID,
    addonName = runBlocking { getString(Res.string.watch_history_source_group) },
    type = WATCH_HISTORY_SOURCE_TYPE,
    catalogId = WATCH_HISTORY_SOURCE_CATALOG_ID,
    catalogName = watchHistoryTitle(),
)

fun isWatchHistoryCatalogKey(key: String): Boolean = key == WATCH_HISTORY_CATALOG_KEY

/** Catalogs that start switched off, so users add them from the Home catalog settings. */
fun isHiddenByDefaultCatalog(key: String): Boolean = isWatchHistoryCatalogKey(key)

fun watchHistoryDefinition(): HomeCatalogDefinition {
    val title = watchHistoryTitle()
    return HomeCatalogDefinition(
        key = WATCH_HISTORY_CATALOG_KEY,
        defaultTitle = title,
        catalogName = title,
        addonName = runBlocking { getString(Res.string.watch_history_source) },
        manifestUrl = "",
        type = "movie",
        catalogId = "history",
        supportsPagination = false,
        descriptorSignature = "watch-history",
    )
}

fun watchHistorySection(items: List<MetaPreview>, title: String): HomeCatalogSection =
    HomeCatalogSection(
        key = WATCH_HISTORY_CATALOG_KEY,
        title = title,
        subtitle = "",
        addonName = "",
        target = CatalogTarget.WatchHistory,
        items = items,
        availableItemCount = items.size,
        hasMore = false,
    )
