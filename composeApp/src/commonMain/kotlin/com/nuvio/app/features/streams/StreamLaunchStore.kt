package com.nuvio.app.features.streams

data class StreamLaunch(
    val profileId: Int,
    val type: String,
    val videoId: String,
    val parentMetaId: String? = null,
    val parentMetaType: String? = null,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val resumePositionMs: Long? = null,
    val resumeProgressFraction: Float? = null,
    val manualSelection: Boolean = false,
    val startFromBeginning: Boolean = false,
    /** When set, the stream route opens this stream straight away instead of waiting for a pick. */
    val preselectedStream: StreamItem? = null,
)

object StreamLaunchStore {
    private var nextLaunchId = 1L
    private val launches = mutableMapOf<Long, StreamLaunch>()

    fun put(launch: StreamLaunch): Long {
        val launchId = nextLaunchId++
        launches[launchId] = launch
        return launchId
    }

    fun get(launchId: Long): StreamLaunch? = launches[launchId]

    /**
     * Hands out the launch's preselected stream once and clears it, so a stream screen that is
     * created again after the player closes does not open the same stream a second time.
     */
    fun consumePreselectedStream(launchId: Long): StreamItem? {
        val launch = launches[launchId] ?: return null
        val stream = launch.preselectedStream ?: return null
        launches[launchId] = launch.copy(preselectedStream = null)
        return stream
    }

    fun remove(launchId: Long) {
        launches.remove(launchId)
    }

    fun clear() {
        nextLaunchId = 1L
        launches.clear()
    }
}
