package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.NuvioPlayerBridge
import com.nuvio.app.features.player.audiosync.LookaheadAudioSync
import com.nuvio.app.features.player.audiosync.SyncMappingStore
import com.nuvio.app.features.player.audiosync.sileroFrontEnd
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * The audio fallback for one iOS player: mpv's read-ahead recorded and decoded for the speech detector,
 * started only while its setting is on. Recordings go to a folder in Caches that is emptied first, so a
 * run that was cut short never leaves a large file behind.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun createIosAudioFallback(
    scope: CoroutineScope,
    bridge: NuvioPlayerBridge,
    sourceUrl: String,
): AutoSyncAudioFallback {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
    // A download plays from a file: its audio is decoded from that file, with nothing to record.
    val localFilePath = if (isLocalFileUrl(sourceUrl)) NSURL(string = sourceUrl).path else null
    val directory = "${caches.orEmpty()}/lookahead"
    NSFileManager.defaultManager.removeItemAtPath(directory, null)
    val engine = LookaheadAudioSync(
        scope = scope,
        source = localFilePath?.let { IosLocalFileAudioSource(bridge, it) } ?: IosLookaheadAudioSource(bridge),
        directory = directory,
        frontEnd = ::sileroFrontEnd,
        store = SyncMappingStore(UserDefaultsMappingStorage, sourceUrl),
        worker = Dispatchers.Default,
        nowMs = ::autoSyncElapsedRealtimeMs,
    )
    return SettingGatedAudioFallback(engine) {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.audioSyncFallbackEnabled.value
    }
}
