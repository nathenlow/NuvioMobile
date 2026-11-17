@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Audio subtitle sync for one stream: listens to its audio, and for the subtitle being synced finds
 * the mapping from subtitle time to media time that the speech supports.
 *
 * Calls arrive from the main thread ([onPlaybackPosition], [startSession]); the work runs on one
 * background alignment thread. The result is read through [currentModel]. Applying it to the
 * subtitle on screen is left to the caller.
 */
internal class AudioSyncEngine(
    private val timeline: SpeechTimeline,
    private val duty: AnalysisDuty,
    val capture: AudioSyncCapture,
    private val sampling: SpotSampling,
    private val store: SyncMappingStore,
    private val alignment: ExecutorService,
    private val nowMs: () -> Long,
) {
    /** Progress worth showing to the viewer. Called from the main and alignment threads. */
    @Volatile
    var onStatus: (AudioSyncStatus) -> Unit = {}

    private val sessionRequest = AtomicInteger(0)
    private val alignRunning = AtomicBoolean(false)

    @Volatile
    private var session: AlignmentSession? = null

    @Volatile
    private var released = false

    @Volatile
    private var playbackPositionMs = 0L

    @Volatile
    private var mediaDurationMs = 0L

    // Only touched on the main thread, from onPlaybackPosition.
    private var lastAlignedSession: AlignmentSession? = null
    private var lastAlignVersion = -1L
    private var lastAlignAtMs = 0L

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            capture.enabled = value
        }

    /** Whether audio is analysed before any subtitle is chosen (see [AudioSyncCapture.listensBeforeSession]). */
    var listensBeforeSession: Boolean
        get() = capture.listensBeforeSession
        set(value) {
            capture.listensBeforeSession = value
        }

    init {
        capture.onLiveOnly = { mimeType -> if (session != null) notify(AudioSyncStatus.LiveOnly(mimeType)) }
    }

    /** The stream the player reads, so spots across the film can be sampled ahead of playback. */
    fun setSpotSource(
        url: String,
        dataSourceFactory: DataSource.Factory,
        extractorsFactory: ExtractorsFactory,
        localEngine: Boolean,
    ) = sampling.setSource(url, dataSourceFactory, extractorsFactory, localEngine)

    fun onAudioTrackSelected(format: Format?) = capture.onAudioTrackSelected(format)

    /** The mapping in effect for the subtitle being synced (subtitle time -> media time), if any. */
    fun currentModel(): SubtitleSyncModel? = session?.model.takeIf { enabled }

    /** Called from the main thread with the player's position; [durationMs] <= 0 when unknown. */
    fun onPlaybackPosition(positionMs: Long, durationMs: Long = 0L) {
        playbackPositionMs = positionMs.coerceAtLeast(0L)
        if (durationMs > 0) mediaDurationMs = durationMs
        val current = session ?: return
        scheduleAlignment(current)
        sampling.maybeStart(current.track, mediaDurationMs, playbackPositionMs) {
            enabled && !released && session === current && !current.isConfirmed
        }
    }

    /**
     * Starts syncing the subtitle identified by [key] with its parsed [cues]. The dialogue track is
     * built on the alignment thread; the session becomes active afterwards, unless another subtitle
     * was chosen meanwhile.
     */
    fun startSession(key: String, cues: List<CuesWithTiming>) {
        val request = sessionRequest.incrementAndGet()
        endSession()
        try {
            alignment.execute { prepareSession(request, key, cues) }
        } catch (_: RejectedExecutionException) {
            // Released: the player surface is going away.
        }
    }

    fun stopSession() {
        sessionRequest.incrementAndGet()
        if (session != null) AudioSyncLog.i("sync session stopped")
        endSession()
    }

    fun release() {
        released = true
        endSession()
        capture.release()
        alignment.shutdownNow()
    }

    private fun endSession() {
        session = null
        capture.sessionActive = false
        duty.clear()
    }

    private fun prepareSession(request: Int, key: String, cues: List<CuesWithTiming>) {
        val track = SubtitleSpeechTrack.fromCues(SubtitleRetimer.dialogueOf(cues))
        if (sessionRequest.get() != request || released) return
        if (track.size < MIN_AUDIO_SYNC_CUES) {
            AudioSyncLog.i("subtitle has only ${track.size} dialogue cues; audio sync skipped")
            return
        }
        capture.prepare()
        if (sessionRequest.get() != request || released) return
        val started = AlignmentSession(key, track)
        session = started
        capture.sessionActive = true
        AudioSyncLog.i("sync session started with ${track.size} dialogue cues")
        if (!enabled) return
        val remembered = store.load(key)
        if (remembered != null) {
            duty.markLocked()
            notify(started.adopt(remembered, playbackPositionMs))
        } else {
            notify(AudioSyncStatus.Listening)
        }
    }

    private fun scheduleAlignment(current: AlignmentSession) {
        if (!enabled || released) return
        val now = nowMs()
        val version = timeline.version
        // Check often while searching so an early estimate lands quickly; relax once confirmed.
        val interval = if (current.model == null) SEARCH_INTERVAL_MS else ALIGN_INTERVAL_MS
        if (current === lastAlignedSession && (version == lastAlignVersion || now - lastAlignAtMs < interval)) return
        if (!alignRunning.compareAndSet(false, true)) return
        lastAlignedSession = current
        lastAlignVersion = version
        lastAlignAtMs = now
        val position = playbackPositionMs
        try {
            alignment.execute {
                try {
                    align(current, position)
                } catch (error: Throwable) {
                    AudioSyncLog.w("alignment failed: ${error.message}")
                } finally {
                    alignRunning.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            alignRunning.set(false)
        }
    }

    private fun align(current: AlignmentSession, positionMs: Long) {
        val update = current.update(timeline, positionMs)
        if (session !== current) return
        // Only a confirmed mapping lets the analysis ease off; an early estimate or none keeps it full.
        if (!current.isConfirmed) duty.clear()
        if (update.confirmed) duty.markLocked()
        update.remember?.let { store.save(current.key, it) }
        update.status?.let(::notify)
    }

    private fun notify(status: AudioSyncStatus) {
        try {
            onStatus(status)
        } catch (_: Throwable) {
            // A failing listener must not stop the analysis.
        }
    }

    companion object {
        private const val ALIGN_INTERVAL_MS = 8_000L
        private const val SEARCH_INTERVAL_MS = 3_000L
        private const val PREFERENCES = "nuvio_audio_sync"

        /** An engine for the stream [sourceKey], wired to the device. */
        fun create(context: Context, sourceKey: String): AudioSyncEngine {
            val appContext = context.applicationContext
            val timeline = SpeechTimeline()
            val duty = AnalysisDuty(SystemClock::elapsedRealtime)
            val weights = SileroVadResources::weightsBlocking
            val capture = AudioSyncCapture(timeline, duty, weights)
            return AudioSyncEngine(
                timeline = timeline,
                duty = duty,
                capture = capture,
                sampling = SpotSampling(
                    timeline = timeline,
                    weights = weights,
                    isPlayingTrack = capture::isPlayingTrack,
                    isUnmetered = { NetworkMetering.isUnmetered(appContext) },
                    nowMs = SystemClock::elapsedRealtime,
                ),
                store = SyncMappingStore(
                    storage = SharedPreferencesMappingStorage(
                        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
                    ),
                    sourceKey = sourceKey,
                ),
                alignment = Executors.newSingleThreadExecutor { runnable ->
                    Thread({
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        runnable.run()
                    }, "NuvioAudioSyncAlign").apply { isDaemon = true }
                },
                nowMs = SystemClock::elapsedRealtime,
            )
        }
    }
}
