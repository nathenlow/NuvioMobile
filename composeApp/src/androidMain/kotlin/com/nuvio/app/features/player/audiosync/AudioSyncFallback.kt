@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.features.autosync.AutoSyncSyncedSubtitle
import com.nuvio.app.features.player.SidecarSubtitleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Audio subtitle sync as AutoSync's fallback, for one stream of one player.
 *
 * AutoSync decides first. While it analyses a subtitle the audio is already being listened to
 * ([arm]); when it keeps the subtitle's original timing (no embedded subtitles, no usable
 * reference, or a weak match) the audio takes over ([takeOver]); otherwise nothing more is done
 * ([disarm]). The mapping found is applied by retiming the subtitle AutoSync's sidecar shows, the
 * same way AutoSync applies its own result, so Nuvio's subtitle rendering is untouched.
 */
internal class AudioSyncFallback(
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val sidecar: SidecarSubtitleController,
    private val sourceKey: String,
    private val engine: AudioSyncEngine,
    /** Whether the fallback is turned on; turning it off mid-playback restores the original timing. */
    private val enabled: StateFlow<Boolean>,
    /** Progress worth showing; called from background threads. */
    private val onStatus: (AudioSyncStatus) -> Unit = {},
) {
    /** The subtitle being synced, with its original cues and the sidecar attachment it lives in. */
    private class Target(val url: String, val cues: List<CuesWithTiming>, val generation: Long)

    private val target = AtomicReference<Target?>(null)
    private var appliedModel: SubtitleSyncModel? = null
    private var ticker: Job? = null
    private var takeOverJob: Job? = null

    private val trackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            engine.onAudioTrackSelected(tracks.selectedAudioFormat())
        }
    }

    init {
        engine.onStatus = ::onEngineStatus
        AudioSyncTaps.attach(sourceKey, engine.capture)
        // Nothing is decoded until AutoSync starts on a subtitle ([arm]).
        engine.listensBeforeSession = false
        engine.enabled = enabled.value
        player.addListener(trackListener)
        engine.onAudioTrackSelected(player.currentTracks.selectedAudioFormat())
        // Turned off in Settings mid-playback: the subtitle goes back to AutoSync's (original) timing.
        scope.launch {
            enabled.collect { isEnabled ->
                engine.enabled = isEnabled
                if (!isEnabled && target.get() != null) stop()
            }
        }
    }

    /** AutoSync started analysing a subtitle: listen meanwhile, so a takeover starts with audio. */
    fun arm() {
        stop()
        if (!enabled.value) return
        engine.enabled = true
        engine.listensBeforeSession = true
        startTicker()
    }

    /** AutoSync applied its own result: nothing is left for the audio to do. */
    fun disarm() {
        stop()
    }

    /**
     * AutoSync kept [url]'s original timing, shown by the sidecar: sync it to the audio instead.
     * Waits briefly for the sidecar's cues, as AutoSync does before applying its own result.
     * Returns false when the fallback is off, so AutoSync reports the failure itself.
     */
    fun takeOver(url: String): Boolean {
        if (!enabled.value) {
            disarm()
            return false
        }
        takeOverJob?.cancel()
        takeOverJob = scope.launch {
            var waitedMs = 0L
            while (sidecar.activeSidecarSubtitleKey == url && sidecar.sidecarTimedCues.isEmpty() && waitedMs < CUES_WAIT_MS) {
                delay(CUES_POLL_MS)
                waitedMs += CUES_POLL_MS
            }
            val generation = sidecar.currentGenerationFor(url)
            val cues = sidecar.sidecarTimedCues
            if (generation == null || cues.isEmpty()) return@launch disarm()
            target.set(Target(url, cues, generation))
            appliedModel = null
            startTicker()
            AudioSyncLog.i("AutoSync kept the original timing; syncing the subtitle to the audio")
            engine.startSession(url, cues)
        }
        return true
    }

    /** The user moved to another subtitle or track, or AutoSync starts over. */
    fun stop() {
        takeOverJob?.cancel()
        takeOverJob = null
        ticker?.cancel()
        ticker = null
        target.getAndSet(null)?.let { previous ->
            AutoSyncSyncedSubtitle.clear()
            // Leave the subtitle on its original timing if it is still the one shown.
            sidecar.commitPreparedSidecarSubtitle(
                expectedCurrentUrl = previous.url,
                newUrl = previous.url,
                cues = previous.cues,
                expectedGeneration = previous.generation,
            )
        }
        appliedModel = null
        engine.listensBeforeSession = false
        engine.stopSession()
    }

    fun release() {
        stop()
        player.removeListener(trackListener)
        AudioSyncTaps.detach(engine.capture)
        engine.release()
    }

    /** Feeds the playhead and applies each new mapping to the sidecar, on the main thread. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                engine.onPlaybackPosition(player.currentPosition, durationMs)
                val current = target.get()
                val model = engine.currentModel()
                if (current != null && model !== appliedModel) {
                    appliedModel = model
                    apply(current, model)
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun apply(current: Target, model: SubtitleSyncModel?) {
        val retimed = withContext(Dispatchers.Default) { SubtitleRetimer.retime(current.cues, model) }
        if (target.get() !== current) return
        val committed = sidecar.commitPreparedSidecarSubtitle(
            expectedCurrentUrl = current.url,
            newUrl = current.url,
            cues = retimed,
            expectedGeneration = current.generation,
        )
        // The sidecar moved on (another subtitle, or it stopped): so does the fallback.
        if (!committed) stop()
    }

    /** Keeps the subtitle list's "Auto synced" chip in step with the audio sync's result. */
    private fun onEngineStatus(status: AudioSyncStatus) {
        when (status) {
            is AudioSyncStatus.Synced, is AudioSyncStatus.Adjusted ->
                target.get()?.let { AutoSyncSyncedSubtitle.mark(it.url) }
            AudioSyncStatus.Withdrawn -> AutoSyncSyncedSubtitle.clear()
            else -> Unit
        }
        onStatus(status)
    }

    companion object {
        private const val TICK_MS = 250L
        private const val CUES_WAIT_MS = 5_000L
        private const val CUES_POLL_MS = 50L

        /** The fallback for the stream at [sourceUrl], wired to the device. */
        fun create(
            context: Context,
            scope: CoroutineScope,
            player: ExoPlayer,
            sidecar: SidecarSubtitleController,
            sourceUrl: String,
            /** The audio's own URL when the player reads it separately from the video. */
            sourceAudioUrl: String?,
            dataSourceFactory: DataSource.Factory?,
            enabled: StateFlow<Boolean>,
            onStatus: (AudioSyncStatus) -> Unit = {},
        ): AudioSyncFallback {
            val engine = AudioSyncEngine.create(context, sourceUrl)
            val audioUrl = sourceAudioUrl?.takeIf { it.isNotBlank() } ?: sourceUrl
            if (dataSourceFactory != null) {
                engine.setSpotSource(
                    url = audioUrl,
                    dataSourceFactory = dataSourceFactory,
                    extractorsFactory = DefaultExtractorsFactory(),
                    localEngine = SpotSourcePolicy.isLoopbackHost(runCatching { Uri.parse(audioUrl).host }.getOrNull()),
                )
            }
            return AudioSyncFallback(scope, player, sidecar, sourceUrl, engine, enabled, onStatus)
        }
    }
}
