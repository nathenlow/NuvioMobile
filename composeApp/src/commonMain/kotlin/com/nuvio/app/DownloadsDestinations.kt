package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.downloads.BulkDownloadScreen
import com.nuvio.app.features.downloads.BulkDownloadSessions
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLaunch
import com.nuvio.app.features.streams.StreamLaunchStore
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.navigation.BulkDownloadRoute
import com.nuvio.app.navigation.StreamRoute
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadsScreen
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadShowRoute
import com.nuvio.app.navigation.DownloadsRoute
import com.nuvio.app.navigation.NuvioNavigator

@Composable
internal fun DownloadsDestination(
    route: DownloadsRoute,
    navController: NuvioNavigator,
    useNativeNavigation: Boolean,
    onOpenDownload: (DownloadItem) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        onOpenDetails = navController::openDownloadDetails,
        onNavigateToShow = if (useNativeNavigation) {
            { showId, title -> navController.navigate(DownloadShowRoute(showId, title)) }
        } else {
            null
        },
    )
}

@Composable
internal fun DownloadShowDestination(
    route: DownloadShowRoute,
    navController: NuvioNavigator,
    onOpenDownload: (DownloadItem) -> Unit,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    DownloadsScreen(
        onBack = onBack,
        onOpenDownload = onOpenDownload,
        onOpenDetails = navController::openDownloadDetails,
        initialShowId = route.showId,
        onBackFromShow = onBack,
    )
}

@Composable
internal fun BulkDownloadDestination(
    route: BulkDownloadRoute,
    navController: NuvioNavigator,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    val session = BulkDownloadSessions.get(route.launchId)
    if (session == null) {
        LaunchedEffect(route.launchId) { onBack() }
        return
    }
    val request = session.request
    BulkDownloadScreen(
        session = session,
        onBack = onBack,
        onPlayStream = { episode, stream: StreamItem ->
            val ref = episode.ref
            val progress = WatchProgressRepository
                .progressForVideo(
                    videoId = ref.videoId,
                    parentMetaId = request.metaId,
                    seasonNumber = ref.seasonNumber,
                    episodeNumber = ref.episodeNumber,
                )
                ?.takeIf { it.isResumable }
            val launchId = StreamLaunchStore.put(
                StreamLaunch(
                    profileId = ProfileRepository.activeProfileId,
                    type = request.metaType,
                    videoId = ref.videoId,
                    parentMetaId = request.metaId,
                    parentMetaType = request.metaType,
                    title = request.title,
                    logo = request.logo,
                    poster = request.poster,
                    background = request.background,
                    seasonNumber = ref.seasonNumber,
                    episodeNumber = ref.episodeNumber,
                    episodeTitle = ref.title,
                    episodeThumbnail = ref.thumbnail,
                    pauseDescription = ref.pauseDescription,
                    resumePositionMs = progress?.lastPositionMs,
                    resumeProgressFraction = progress?.progressFraction,
                    manualSelection = true,
                    preselectedStream = stream,
                ),
            )
            navController.navigate(StreamRoute(launchId = launchId, title = request.title))
        },
    )
}

private fun NuvioNavigator.openDownloadDetails(item: DownloadItem) {
    navigate(
        DetailRoute(
            type = item.parentMetaType,
            id = item.parentMetaId,
            title = item.title,
        ),
    )
}
