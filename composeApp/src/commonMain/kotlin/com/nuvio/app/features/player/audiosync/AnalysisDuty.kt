package com.nuvio.app.features.player.audiosync

/**
 * Decides which stretches of audio are worth analysing. Until a mapping is confirmed everything is.
 * Once it has been refined over a long stretch, analysing one minute in three is enough to follow
 * later jumps, and saves most of the CPU.
 */
internal class AnalysisDuty(private val nowMs: () -> Long) {
    @Volatile
    private var lockedAtMs = 0L

    fun markLocked() {
        lockedAtMs = nowMs().coerceAtLeast(1L)
    }

    fun clear() {
        lockedAtMs = 0L
    }

    fun shouldAnalyze(timeUs: Long): Boolean {
        val lockedAt = lockedAtMs
        if (lockedAt == 0L || nowMs() - lockedAt < FULL_ANALYSIS_AFTER_LOCK_MS) return true
        return (timeUs / WINDOW_US) % CYCLE == 0L
    }

    private companion object {
        const val FULL_ANALYSIS_AFTER_LOCK_MS = 10 * 60_000L
        const val WINDOW_US = 60_000_000L
        const val CYCLE = 3L
    }
}
