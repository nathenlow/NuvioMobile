package com.nuvio.app.features.watched

/** The posters kept for the most recent watch history titles, one small payload per profile. */
internal expect object WatchHistoryArtworkStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)
    fun remove(profileId: Int)
}
