@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.net.Uri
import android.os.Process
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.ExtractorsFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Which streams may be read away from the playhead. */
internal object SpotSourcePolicy {
    /** Why the stream cannot be sampled across the film, or null when it can. */
    fun unavailableReason(scheme: String?, path: String?, localEngine: Boolean): String? {
        val lowerPath = path.orEmpty().lowercase()
        return when {
            localEngine -> "not used for torrent streams (would slow playback)"
            scheme != "http" && scheme != "https" -> "not available for this stream"
            lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".mpd") -> "not available for playlist streams"
            else -> null
        }
    }

    /** A stream served by this device: random access would compete with playback for the same engine. */
    fun isLoopbackHost(host: String?): Boolean {
        val lower = host.orEmpty().lowercase()
        return lower == "localhost" || lower == "127.0.0.1" || lower == "::1" || lower == "[::1]"
    }
}

/**
 * Samples a few dialogue-dense stretches across the film while the chosen subtitle is still
 * unsynced, so evidence from across the film arrives within seconds instead of only what playback
 * has reached. Once per stream, on unmetered networks only, and only after playback has some buffer.
 */
internal class SpotSampling(
    private val timeline: SpeechTimeline,
    private val weights: () -> SileroVadWeights?,
    private val isPlayingTrack: (Format) -> Boolean,
    private val isUnmetered: () -> Boolean,
    private val nowMs: () -> Long,
) {
    /** The stream, as the player reads it. */
    private class Source(
        val uri: Uri,
        val dataSourceFactory: DataSource.Factory,
        val extractorsFactory: ExtractorsFactory,
    )

    @Volatile
    private var source: Source? = null
    private val started = AtomicBoolean(false)
    private var lastMeterCheckMs: Long? = null
    private var loggedMetered = false

    /** Why sampling is not happening, when it is not; empty otherwise. For logs. */
    @Volatile
    var status: String = ""
        private set

    fun setSource(
        url: String,
        dataSourceFactory: DataSource.Factory,
        extractorsFactory: ExtractorsFactory,
        localEngine: Boolean,
    ) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val reason = if (uri == null) {
            "not available for this stream"
        } else {
            SpotSourcePolicy.unavailableReason(uri.scheme, uri.path, localEngine)
        }
        status = reason.orEmpty()
        source = if (reason == null && uri != null) Source(uri, dataSourceFactory, extractorsFactory) else null
    }

    /**
     * Starts sampling when the conditions are right. Called often, from the main thread, so it only
     * checks cheap state. [stillNeeded] is asked again while sampling runs and ends it when false.
     */
    fun maybeStart(
        track: SubtitleSpeechTrack,
        filmDurationMs: Long,
        positionMs: Long,
        stillNeeded: () -> Boolean,
    ) {
        if (started.get()) return
        val current = source ?: return
        if (filmDurationMs < MIN_FILM_MS || timeline.knownFrameCount() < START_AFTER_FRAMES) return
        if (!stillNeeded() || !unmeteredNow()) return
        if (!started.compareAndSet(false, true)) return
        Thread({ sample(current, track, filmDurationMs, positionMs, stillNeeded) }, "NuvioAudioSyncSpots").apply {
            isDaemon = true
            start()
        }
    }

    /** Checked at most every few seconds; a viewer who moves onto Wi-Fi mid-film gets sampling then. */
    private fun unmeteredNow(): Boolean {
        val now = nowMs()
        val last = lastMeterCheckMs
        if (last != null && now - last < METER_CHECK_INTERVAL_MS) return false
        lastMeterCheckMs = now
        if (isUnmetered()) return true
        if (!loggedMetered) {
            loggedMetered = true
            status = "waiting for an unmetered network"
            AudioSyncLog.i("not sampling audio across the film on a metered network")
        }
        return false
    }

    private fun sample(
        source: Source,
        track: SubtitleSpeechTrack,
        filmDurationMs: Long,
        positionMs: Long,
        stillNeeded: () -> Boolean,
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val loaded = weights() ?: return
        // One decoder per worker: each reads a different place, so their audio must not mix.
        val decoders = List(WORKERS) {
            val analyzer = SpeechAnalyzer(SileroVad(loaded), timeline)
            AudioSyncDecoder(analyzer, analyzer, allowVendorDecoders = false)
        }
        try {
            // A fresh start covers the opening itself; after a resume any part of the film helps.
            val coveredMs = timeline.segments(fromFrame = SpeechTimeline.frameForTimeUs(positionMs * 1_000L))
                .firstOrNull()?.let { (it.toFrame * SpeechTimeline.FRAME_DURATION_MS).toLong() } ?: 0L
            val notBeforeMs = if (positionMs < RESUME_THRESHOLD_MS) coveredMs + SPOT_MS else 0L
            val spots = DialogueSpotPlanner.plan(listOf(track), filmDurationMs, SPOT_COUNT, SPOT_MS, notBeforeMs)
            if (spots.isEmpty()) return
            AudioSyncLog.i("sampling audio at ${spots.map { it / 1_000 }}s")
            val startedAt = nowMs()
            val sampler = AudioSpotSampler(
                uri = source.uri,
                dataSourceFactory = source.dataSourceFactory,
                targetBytes = TARGET_BYTES,
                maxBytes = MAX_BYTES,
                minSpotMs = MIN_SPOT_AUDIO_MS,
            )
            val result = sampler.run(
                spotsMs = spots,
                spotMs = SPOT_MS,
                workers = decoders.map { AudioSyncExtractorsFactory(source.extractorsFactory, SpotSink(it, stillNeeded)) },
                isCancelled = { !stillNeeded() },
            ) { sampled, bytes ->
                status = "sampled $sampled of ${spots.size} dialogue spots in ${(nowMs() - startedAt) / 1_000}s (${bytes / 1_000_000} MB)"
            }
            AudioSyncLog.i("sampled ${result.sampled} spots, ${result.bytes / 1_000_000} MB, failure=${result.failure}")
            if (result.failure != null && result.sampled == 0) status = "not possible for this stream"
            decoders.forEach { it.awaitDrained(DRAIN_TIMEOUT_MS) }
        } catch (error: Throwable) {
            AudioSyncLog.w("audio sampling failed: ${error.message}")
        } finally {
            decoders.forEach(AudioSyncDecoder::release)
        }
    }

    /** Feeds sampled audio of the playing track to [decoder], waiting when it is busy. */
    private inner class SpotSink(
        private val decoder: AudioSyncDecoder,
        private val stillNeeded: () -> Boolean,
    ) : AudioSampleSink {
        override fun wantsSamples(format: Format): Boolean = stillNeeded() && isPlayingTrack(format)

        override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
            while (!decoder.offer(format, timeUs, data, offset, size)) {
                if (!decoder.accepts(format)) return
                Thread.sleep(BACKOFF_MS)
            }
        }

        override fun onDiscontinuity() {
            decoder.discontinuity()
        }
    }

    private companion object {
        const val SPOT_COUNT = 4

        /** One connection per spot, so every spot arrives in the same round. */
        const val WORKERS = SPOT_COUNT
        const val SPOT_MS = 30_000L
        const val TARGET_BYTES = 150L * 1_000_000L
        const val MAX_BYTES = 400L * 1_000_000L

        /** Enough for a few whole sentences, which speech detection needs to line up. */
        const val MIN_SPOT_AUDIO_MS = 8_000L
        const val MIN_FILM_MS = 20 * 60_000L
        const val RESUME_THRESHOLD_MS = 10 * 60_000L
        const val BACKOFF_MS = 5L
        const val DRAIN_TIMEOUT_MS = 10_000L
        const val METER_CHECK_INTERVAL_MS = 5_000L

        /** Playback has started and buffered this much before sampling competes for bandwidth. */
        val START_AFTER_FRAMES = (5_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
    }
}
