package com.nuvio.app.features.player.audiosync

import android.util.Log

/**
 * Audio sync's diagnostics, written to logcat only. Messages carry timings and decisions; never put
 * stream or subtitle URLs (they can hold account tokens), subtitle text, or audio in one.
 */
internal object AudioSyncLog {
    private const val TAG = "NuvioAudioSync"

    fun i(message: String) = write { Log.i(TAG, message) }

    fun w(message: String) = write { Log.w(TAG, message) }

    fun d(message: String) = write { Log.d(TAG, message) }

    /** Logging must never fail its caller; plain JVM unit tests run against a stubbed [Log]. */
    private inline fun write(log: () -> Unit) {
        try {
            log()
        } catch (_: RuntimeException) {
        }
    }
}
