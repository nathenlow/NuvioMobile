package com.nuvio.app.features.player.audiosync

import co.touchlab.kermit.Logger

/**
 * Audio sync's diagnostics, written through the app's logger (Logcat on Android, the system log on iOS).
 * Messages carry timings and decisions; never put stream or subtitle URLs (they can hold account
 * tokens), subtitle text, or audio in one.
 */
internal object AudioSyncLog {
    private val logger = Logger.withTag("NuvioAudioSync")

    fun i(message: String) = safely { logger.i { message } }

    fun w(message: String) = safely { logger.w { message } }

    fun d(message: String) = safely { logger.d { message } }

    /** Logging must never fail its caller; plain JVM unit tests run against a stubbed Android log. */
    private inline fun safely(write: () -> Unit) {
        try {
            write()
        } catch (_: Exception) {
        }
    }
}
