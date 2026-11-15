package com.nuvio.app.features.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A one-shot request for the Library to show its "Downloaded" view, for callers outside the
 * Library such as the offline launch route. The Library takes it when it next shows.
 */
object DownloadLibraryRequest {
    var pending by mutableStateOf(false)
        private set

    fun request() {
        pending = true
    }

    internal fun consume() {
        pending = false
    }
}
